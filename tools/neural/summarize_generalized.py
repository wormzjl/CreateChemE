"""Summarize complete or live generalized experiments without changing the design.

Example:
  python tools/neural/summarize_generalized.py --teacher build/neural-generalized/v2/cases.jsonl \
    --evaluation build/neural-generalized/evaluation/evaluation.jsonl \
    --benchmark build/neural-generalized/benchmark/evaluation.jsonl \
    --output build/neural-generalized/analysis/summary.json

Use --final to require a complete teacher journal and complete supplied evaluation
journals. Validation/test/recovery are reported separately; outcome-selected retry
cohorts are never presented as an unbiased held-out acceptance rate.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
import json
import math
from pathlib import Path
import statistics

from generalized_design import canonical
from prepare_generalized_evaluation import canonical_input_hash, load_jsonl, sha, stage_bucket


ROOT = Path(__file__).resolve().parents[2]
QUALIFIED = {"DRY_EQUILIBRIUM", "WET_EQUILIBRIUM"}
PROFILE_METRICS = (
    "temperatureRmseKelvin", "temperatureMaxAbsKelvin", "componentFlowRmseMolPerSecond",
    "componentFlowMaxAbsMolPerSecond", "log1pComponentFlowRmse", "phaseTotalFlowRelativeToFeedRmse",
    "phaseTotalFlowRelativeToFeedMaxAbs", "freeWaterRmseMolPerSecond", "freeWaterMaxAbsMolPerSecond",
    "wetMaskMismatches",
)


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def percentile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low, high = math.floor(position), math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def distribution(values):
    values = [v for v in values if finite(v)]
    if not values:
        return {"count": 0, "median": None, "p95": None, "max": None, "min": None}
    return {"count": len(values), "median": statistics.median(values), "p95": percentile(values, 0.95),
            "max": max(values), "min": min(values)}


def water_qualified(row):
    return row.get("success") is True and (row.get("equilibriumQualified") is True or row.get("waterQualification") in QUALIFIED)


def outcome_class(row):
    if row.get("success") is True:
        return "EQUILIBRIUM_QUALIFIED" if water_qualified(row) else "ACCEPTED_ADVISORY_OR_UNQUALIFIED"
    status = row.get("status", "UNKNOWN_FAILURE")
    if row.get("failureClass") == "NATIVE_SOLVER_ADMISSION_OR_PATH_BOUND" or status == "INFEASIBLE_SPECIFICATION":
        return "NATIVE_SOLVER_ADMISSION_OR_PATH_BOUND"
    if status in ("EXCLUDED_BY_DESIGN", "UNSUPPORTED_COMPONENT_BASIS"):
        return status
    if status == "DEADLINE_EXCEEDED":
        return "RESOURCE_DEADLINE"
    if status in ("INVALID_INPUT", "PROPERTY_OR_INPUT_REJECTION"):
        return "PROPERTY_OR_MODEL_CONTRACT"
    return "NUMERICAL_FAILURE"


def solve_summary(rows):
    rows = list(rows)
    accepted = [row for row in rows if row.get("success") is True]
    qualified = [row for row in rows if water_qualified(row)]
    count = len(rows)
    return {"cases": count, "accepted": len(accepted), "equilibriumQualified": len(qualified),
        "acceptedAdvisoryOrUnqualified": len(accepted) - len(qualified),
        "acceptedFraction": len(accepted) / count if count else None,
        "qualifiedFraction": len(qualified) / count if count else None,
        "statuses": dict(Counter(row.get("status", "ACCEPTED" if row.get("success") else "UNKNOWN_FAILURE") for row in rows)),
        "outcomeClasses": dict(Counter(outcome_class(row) for row in rows)),
        "waterQualifications": dict(Counter(row.get("waterQualification", "FAILED" if not row.get("success") else "UNASSESSED") for row in rows)),
        "wallMillisAllAttempts": distribution(row.get("ms", row.get("cold_ms")) for row in rows),
        "wallMillisAccepted": distribution(row.get("ms", row.get("cold_ms")) for row in accepted),
        "wallMillisQualified": distribution(row.get("ms", row.get("cold_ms")) for row in qualified),
        "cpuMillisAllAttempts": distribution(row.get("cpuMillis") for row in rows),
        "allocatedBytesAllAttempts": distribution(row.get("allocatedBytes") for row in rows),
        "newtonIterationsAccepted": distribution((row.get("diagnostics") or {}).get("newtonIterations") for row in accepted),
        "finalMaximumScaledResidualAccepted": distribution((row.get("diagnostics") or {}).get("maximumScaledResidual") for row in accepted)}


def profile_summary(values):
    values = [value for value in values if isinstance(value, dict)]
    unavailable = [value for value in values if "unavailable" in value]
    values = [value for value in values if "unavailable" not in value]
    branch = [v["branchMatches"] for v in values if isinstance(v.get("branchMatches"), bool)]
    wet = [v["wetMaskMismatches"] for v in values if finite(v.get("wetMaskMismatches"))]
    return {"profilesCompared": len(values), "profilesUnavailable": len(unavailable), "scope": "Case-level error distributions; these medians are not pooled pointwise RMSE.",
        "metrics": {name: distribution(value.get(name) for value in values) for name in PROFILE_METRICS},
        "branchCompared": len(branch), "branchMismatches": sum(not b for b in branch),
        "profilesWithWetMaskMismatch": sum(v != 0 for v in wet)}


def profile_difference(raw, actual, input_value):
    """Independent raw/solved profile comparison; reject mismatched axes/shapes."""
    if not raw or not actual:
        return None
    if raw.get("input", {}).get("componentBasis") != actual.get("input", {}).get("componentBasis"):
        return {"unavailable": "profile component axes differ"}
    try:
        if any(len(raw[key]) != len(actual[key]) for key in ("temperatures", "liquid", "vapor", "freeWater", "wetTrays")):
            return {"unavailable": "profile node counts differ"}
        dt = [a - b for a, b in zip(raw["temperatures"], actual["temperatures"])]
        dw = [a - b for a, b in zip(raw["freeWater"], actual["freeWater"])]
        reference = math.fsum(input_value["feedComponentMolarFlowsMolPerSecond"])
        df, dl, totals = [], [], []
        for phase in ("liquid", "vapor"):
            for predicted, solved in zip(raw[phase], actual[phase]):
                if len(predicted) != len(solved):
                    return {"unavailable": "profile component counts differ"}
                df.extend(a - b for a, b in zip(predicted, solved))
                dl.extend(math.log1p(a) - math.log1p(b) for a, b in zip(predicted, solved))
                totals.append((math.fsum(predicted) - math.fsum(solved)) / reference)

        def rmse(values):
            return math.sqrt(math.fsum(v * v for v in values) / len(values))

        return {"temperatureRmseKelvin": rmse(dt), "temperatureMaxAbsKelvin": max(map(abs, dt)),
            "componentFlowRmseMolPerSecond": rmse(df), "componentFlowMaxAbsMolPerSecond": max(map(abs, df)),
            "log1pComponentFlowRmse": rmse(dl), "phaseTotalFlowRelativeToFeedRmse": rmse(totals),
            "phaseTotalFlowRelativeToFeedMaxAbs": max(map(abs, totals)),
            "freeWaterRmseMolPerSecond": rmse(dw), "freeWaterMaxAbsMolPerSecond": max(map(abs, dw)),
            "wetMaskMismatches": sum(a != b for a, b in zip(raw["wetTrays"], actual["wetTrays"])),
            "branchMatches": raw.get("branch") == actual.get("branch")}
    except (KeyError, TypeError, ValueError, ZeroDivisionError) as exc:
        return {"unavailable": str(exc)}


def raw_summary(rows):
    available = [row.get("rawPrediction") or {} for row in rows if "rawPrediction" in row]
    return {"measured": len(available), "supported": sum(item.get("supported") is True for item in available),
        "unsupported": sum(item.get("supported") is False for item in available),
        "predictionMillis": distribution(item.get("ms") for item in available),
        "predictionCpuMillis": distribution(item.get("cpuMillis") for item in available),
        "predictionAllocatedBytes": distribution(item.get("allocatedBytes") for item in available),
        "nativeResidual": distribution((item.get("nativeResidual") or {}).get("maximumScaledMeshResidual") for item in available),
        "nativeResidualUnavailable": sum("unavailable" in (item.get("nativeResidual") or {}) for item in available),
        "predictionFailureReasons": dict(Counter(item.get("failure", "NO_PREDICTION") for item in available if item.get("supported") is False))}


def read_run(path, final=False):
    path = Path(path)
    rows, snapshot = load_jsonl(path, allow_partial=not final)
    ids = [str(row["id"]) for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate case IDs in journal: " + str(path))
    metadata_path, memory_path = path.parent / "run.json", path.parent / "memory.json"
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8-sig")) if metadata_path.exists() else {}
    except json.JSONDecodeError:
        if final:
            raise
        metadata = {}
    try:
        memory = json.loads(memory_path.read_text(encoding="utf-8-sig")) if memory_path.exists() else None
    except json.JSONDecodeError:
        if final:
            raise
        memory = {"snapshot": "memory monitor is writing its next sample"}
    expected = metadata.get("caseCount")
    source = Path(metadata["source"]) if metadata.get("source") else None
    if source and not source.is_absolute() and not source.exists():
        source = ROOT / source
    source_rows = None
    if source and source.exists():
        source_rows, _ = load_jsonl(source)
        expected = len(source_rows)
        expected_ids = {str(row["id"]) for row in source_rows}
        source_by_id = {str(row["id"]): row for row in source_rows}
        if not set(ids) <= expected_ids:
            raise ValueError("Unexpected evaluation case IDs: " + str(path))
        if metadata.get("sourceSha256") and sha(source) != metadata["sourceSha256"]:
            raise ValueError("Run source changed after execution: " + str(source))
        for row in rows:
            authored = source_by_id[str(row["id"])]
            if row.get("split") != authored.get("split") or canonical_input_hash(row["input"]) != canonical_input_hash(authored["input"]):
                raise ValueError("Run changed the authored input or split: " + str(row["id"]))
    complete = bool(metadata) and expected is not None and len(rows) == expected and metadata.get("completed") == expected
    if final and not complete:
        raise ValueError("Incomplete experiment: " + str(path))
    timing = {"kind": "serial_latency" if metadata.get("workers") == 1 else "concurrent_throughput" if metadata.get("workers", 0) > 1 else "unavailable_until_run_metadata_written",
              "elapsedSeconds": metadata.get("elapsedSeconds"), "workers": metadata.get("workers"),
              "casesPerSecond": len(rows) / metadata["elapsedSeconds"] if finite(metadata.get("elapsedSeconds")) and metadata["elapsedSeconds"] > 0 else None}
    return rows, {"journal": str(path), "journalSha256AtSnapshot": sha(path) if path.exists() else None,
        "snapshot": snapshot, "expectedRows": expected, "complete": complete, "run": metadata,
        "throughput": timing, "processMemory": memory,
        "memoryInterpretation": "Working set/private bytes and JVM heap are whole-process measures. Per-thread allocated bytes are cumulative allocation volume, not retained RAM. Separate pool peaks are not summed."}


def bin_value(value, edges):
    for low, high in zip(edges, edges[1:]):
        if low <= value < high or value == high == edges[-1]:
            return f"{low:g}..{high:g}" + (" inclusive upper" if high == edges[-1] else "")
    return "outside_declared_range"


def input_zones(input_value, baseline=None):
    n = input_value["stageCount"]
    steam = input_value.get("steamFeeds", [])
    pas, draws = input_value.get("pumparounds", []), input_value.get("sideDraws", [])
    total = math.fsum(input_value["feedComponentMolarFlowsMolPerSecond"])
    specs = input_value["specifications"]
    reflux = next(s["ratio"] for s in specs if "ratio" in s)
    condenser = next(s["kelvin"] for s in specs if "kelvin" in s)
    reboiler = next(s["watts"] for s in specs if "watts" in s)
    pressure = input_value["topPressurePascal"] / 1000.0
    zones = {"stageCount": str(n), "stageBucket": stage_bucket(n),
        "steam": "on" if steam else "off", "paCount": str(len(pas)), "sideDrawCount": str(len(draws)),
        "pressureKPa": bin_value(pressure, [100, 150, 200, 250, 300]),
        "refluxRatio": "exactly_zero" if reflux == 0 else bin_value(reflux, [0, 2, 4, 6, 8, 10]),
        "feedTemperatureKelvin": bin_value(input_value["feedTemperatureKelvin"], [510.52, 561.572, 612.624, 663.676, 714.728, 765.781]),
        "condenserTemperatureCelsius": bin_value(condenser - 273.15, [25, 40, 60, 80, 100, 125.431]),
        "pressureDropPaPerTray": "exactly_zero" if input_value["stagePressureDropPascal"] == 0 else bin_value(input_value["stagePressureDropPascal"], [0, 250, 500, 750, 1000]),
        "reboilerDutyMW": bin_value(reboiler / 1e6, [0, 3.6885, 7.377, 11.0655, 14.754]),
        "sideDrawFractionOfFeed": bin_value(math.fsum(d["molarFlowMolPerSecond"] for d in draws) / total, [0, 0.2, 0.4, 0.6, 0.8, 1.0]),
        "steamFractionOfFeed": "off" if not steam else bin_value(math.fsum(s["molarFlowMolPerSecond"] for s in steam) / total, [0, 0.25, 0.5, 0.75, 1]),
        "stageSteamPa": f"{stage_bucket(n)}|steam={int(bool(steam))}|PA={len(pas)}"}
    if pas:
        strongest = min(pas, key=lambda pa: pa["dutyWatts"])
        zones["strongestPaReturnHeight"] = bin_value((strongest["returnTray"] - 1) / (n - 1), [0, 0.25, 0.5, 0.75, 1])
    if baseline and input_value["componentBasis"] == baseline["componentBasis"]:
        reference_total = math.fsum(baseline["feedComponentMolarFlowsMolPerSecond"])
        zones["totalFeedRelativeToBaseline"] = bin_value(total / reference_total, [0.8, 0.9, 1, 1.1, 1.200001])
        for name, actual, reference in zip(input_value["componentBasis"]["componentIds"], input_value["feedComponentMolarFlowsMolPerSecond"], baseline["feedComponentMolarFlowsMolPerSecond"]):
            ratio = actual / total / (reference / reference_total)
            zones["mixture:" + name] = "low" if ratio < 0.933333333333 else "high" if ratio > 1.066666666667 else "middle"
    return zones


def zone_summary(rows, baseline=None):
    grouped = defaultdict(list)
    for row in rows:
        for dimension, value in input_zones(row["input"], baseline).items():
            grouped[(dimension, value)].append(row)
    result = []
    for (dimension, value), group in sorted(grouped.items()):
        successes = sum(row.get("success") is True for row in group)
        qualified = sum(water_qualified(row) for row in group)
        supported = [row.get("rawPrediction", {}).get("supported") for row in group if "rawPrediction" in row]
        source = [row for row in group if isinstance(row.get("sourceSuccess"), bool)]
        source_successes = sum(row["sourceSuccess"] for row in source)
        result.append({"dimension": dimension, "zone": value, "cases": len(group), "accepted": successes,
            "qualified": qualified, "failed": len(group) - successes, "acceptedFraction": successes / len(group),
            "qualifiedFraction": qualified / len(group), "unsupportedPredictions": sum(x is False for x in supported),
            "solverAdmissionsOrPathBounds": sum(outcome_class(row) == "NATIVE_SOLVER_ADMISSION_OR_PATH_BOUND" for row in group),
            "deadlines": sum(row.get("status") == "DEADLINE_EXCEEDED" for row in group),
            "wallMillisMedianAllAttempts": distribution(row.get("ms", row.get("cold_ms")) for row in group)["median"],
            "sourceKnownCases": len(source), "sourceAccepted": source_successes,
            "sourceAcceptedFraction": source_successes / len(source) if source else None,
            "acceptanceDeltaAgainstKnownSource": (sum(row.get("success") is True for row in source) - source_successes) / len(source) if source else None})
    return result


def cohorts(row):
    explicit = (row.get("design") or {}).get("evaluationCohorts")
    if explicit:
        return explicit
    return [row["split"]] if row.get("split") in ("validation", "test") else []


def rescue_summary(rows):
    failures = [row for row in rows if row.get("sourceSuccess") is False]
    grouped = defaultdict(list)
    for row in failures:
        origin = (row.get("design") or {}).get("evaluationOrigin") or {}
        name = "prior:" + str(origin.get("journal", "unknown")) if origin.get("kind") == "prior_failure" else "original_matrix"
        grouped[name].append(row)
    return {name: {"originalFailuresRetried": len(group), "rawPredictionSupported": sum((row.get("rawPrediction") or {}).get("supported") is True for row in group),
            "acceptedRescues": sum(row.get("success") is True for row in group),
            "qualifiedRescues": sum(water_qualified(row) for row in group),
            "advisoryOnlyRescues": sum(row.get("success") is True and not water_qualified(row) for row in group),
            "unrescued": sum(row.get("success") is not True for row in group),
            "originalStatuses": dict(Counter(row.get("sourceStatus", "PRIOR_FAILURE") for row in group)),
            "retryStatuses": dict(Counter(row.get("status", "UNKNOWN") for row in group)),
            "acceptedCaseIds": [row["id"] for row in group if row.get("success") is True],
            "qualifiedCaseIds": [row["id"] for row in group if water_qualified(row)]}
            for name, group in sorted(grouped.items())}


def evaluation_summary(rows, teacher_by_hash, baseline):
    final_teacher_pairs, qualified_final_teacher_pairs = [], []
    raw_teacher_qualified = []
    for row in rows:
        teacher = teacher_by_hash.get(canonical_input_hash(row["input"]))
        if teacher and water_qualified(teacher) and isinstance(row.get("rawVsTeacher"), dict):
            raw_teacher_qualified.append(row["rawVsTeacher"])
        if teacher and teacher.get("success") and row.get("success"):
            difference = profile_difference(row.get("seed"), teacher.get("seed"), row["input"])
            if difference and "unavailable" not in difference:
                final_teacher_pairs.append(difference)
                if water_qualified(row) and water_qualified(teacher):
                    qualified_final_teacher_pairs.append(difference)
    groups = {name: [row for row in rows if name in cohorts(row)] for name in ("validation", "test", "matrix_failure", "prior_failure")}
    result = {"observedAll": solve_summary(rows), "cohorts": {name: solve_summary(group) for name, group in groups.items()},
        "rawPrediction": raw_summary(rows), "rawVsFinalAllAccepted": profile_summary(row.get("rawVsFinal") for row in rows),
        "rawVsFinalQualified": profile_summary(row.get("rawVsFinal") for row in rows if water_qualified(row)),
        "rawVsTeacherAllAccepted": profile_summary(row.get("rawVsTeacher") for row in rows),
        "rawVsTeacherQualified": profile_summary(raw_teacher_qualified),
        "finalVsTeacherAllAccepted": profile_summary(final_teacher_pairs),
        "finalVsTeacherBothQualified": profile_summary(qualified_final_teacher_pairs),
        "rescues": rescue_summary(rows),
        "cohortInterpretation": "Validation is for model selection. Test includes every requested held-out input only when its expected count is complete. Failure retries are outcome-selected and overlap held-out cohorts; do not add cohort counts or interpret retry acceptance as general accuracy."}
    zones = zone_summary(groups["test"], baseline)
    result["testZones"] = zones
    test_qualified_teacher_rows = [row for row in groups["test"]
        if water_qualified(teacher_by_hash.get(canonical_input_hash(row["input"]), {}))]
    result["testProfileComparisons"] = {
        "rawVsQualifiedTeacher": profile_summary(row.get("rawVsTeacher") for row in test_qualified_teacher_rows),
        "rawVsQualifiedNeuralFinal": profile_summary(row.get("rawVsFinal") for row in groups["test"] if water_qualified(row)),
        "bothQualifiedFinalProfiles": profile_summary(profile_difference(row.get("seed"),
            teacher_by_hash[canonical_input_hash(row["input"])].get("seed"), row["input"])
            for row in test_qualified_teacher_rows if water_qualified(row))}
    result["weakTestZonesWithAtLeast10Cases"] = sorted([zone for zone in zones if zone["cases"] >= 10], key=lambda zone: (zone["qualifiedFraction"], zone["acceptedFraction"], -zone["cases"]))[:30]
    return result


def benchmark_summary(rows):
    valid = [row for row in rows if isinstance(row.get("current"), dict) and isinstance(row.get("neural"), dict)]
    paired = [row for row in valid if row["current"].get("success") and row["neural"].get("success")]
    qualified = [row for row in paired if water_qualified(row["current"]) and water_qualified(row["neural"])]

    def ratios(group, metric):
        return distribution(row["current"][metric] / row["neural"][metric] for row in group
                            if finite(row["current"].get(metric)) and finite(row["neural"].get(metric)) and row["neural"][metric] > 0)

    result = {"pairedCases": len(valid), "current": solve_summary(row["current"] for row in valid),
        "neural": solve_summary(row["neural"] for row in valid),
        "bothAcceptedCases": len(paired), "bothQualifiedCases": len(qualified),
        "currentOnlyAcceptedCases": sum(row["current"].get("success") and not row["neural"].get("success") for row in valid),
        "neuralOnlyAcceptedCases": sum(row["neural"].get("success") and not row["current"].get("success") for row in valid),
        "acceptedPairsClassicalOverNeuralWallRatio": ratios(paired, "ms"),
        "qualifiedPairsClassicalOverNeuralWallRatio": ratios(qualified, "ms"),
        "qualifiedPairsClassicalOverNeuralCpuRatio": ratios(qualified, "cpuMillis"),
        "qualifiedPairsClassicalOverNeuralAllocationRatio": ratios(qualified, "allocatedBytes"),
        "rawPrediction": raw_summary(rows),
        "rawVsNeuralFinal": profile_summary(row["neural"].get("rawVsFinal") for row in valid),
        "rawVsClassicalFinal": profile_summary(row["current"].get("rawVsFinal") for row in valid),
        "pairedQualifiedFinalProfiles": profile_summary(profile_difference(row["neural"].get("seed"), row["current"].get("seed"), row["input"]) for row in qualified),
        "interpretation": "Benchmark inputs were selected using design inputs only, before fitting. This is a serial warmed-JVM comparison; failed and unsupported attempts stay in denominators. Timing ratios compare the same inputs accepted by both methods and do not count fast rejection as a speedup. One run per input does not establish timing confidence intervals."}
    fallback_rows = [row for row in valid if isinstance(row.get("neuralFirst"), dict)]
    if fallback_rows:
        result["neuralFirst"] = solve_summary(row["neuralFirst"] for row in fallback_rows)
        accepted_pairs = [row for row in fallback_rows if row["current"].get("success") and row["neuralFirst"].get("success")]
        qualified_pairs = [row for row in accepted_pairs if water_qualified(row["current"]) and water_qualified(row["neuralFirst"])]
        result["neuralFirstComparison"] = {"cases": len(fallback_rows), "bothAcceptedCases": len(accepted_pairs),
            "bothQualifiedCases": len(qualified_pairs),
            "classicalAcceptedCasesRetained": len(accepted_pairs),
            "classicalAcceptedCasesLost": sum(row["current"].get("success") and not row["neuralFirst"].get("success") for row in fallback_rows),
            "classicalFailuresRescued": sum(not row["current"].get("success") and row["neuralFirst"].get("success") for row in fallback_rows),
            "classicalFallbackEvents": sum(any("initializer=CURRENT_BACKUP" in event for event in (row["neuralFirst"].get("diagnostics") or {}).get("events", [])) for row in fallback_rows),
            "qualifiedPairsClassicalOverNeuralFirstWallRatio": distribution(row["current"]["ms"] / row["neuralFirst"]["ms"] for row in qualified_pairs
                if finite(row["current"].get("ms")) and finite(row["neuralFirst"].get("ms")) and row["neuralFirst"]["ms"] > 0),
            "qualifiedFinalProfiles": profile_summary(profile_difference(row["neuralFirst"].get("seed"), row["current"].get("seed"), row["input"]) for row in qualified_pairs),
            "rawVsFinal": profile_summary(row["neuralFirst"].get("rawVsFinal") for row in fallback_rows),
            "interpretation": "LNN_FIRST includes prediction, attempted neural correction and any classical fallback within the same request. Its latency is not the standalone neural initialization cost."}
    return result


def compact_row(row, run_label, baseline):
    item = row["input"]
    zones = input_zones(item, baseline)
    result = {"run": run_label, "id": row["id"], "split": row.get("split"),
        "cohorts": "|".join(cohorts(row)), "canonicalInputSha256": canonical_input_hash(item),
        "stageCount": item["stageCount"], "feedStage": item["feedStageNumber"],
        "feedTemperatureKelvin": item["feedTemperatureKelvin"], "feedMolPerSecond": math.fsum(item["feedComponentMolarFlowsMolPerSecond"]),
        "topPressureKPa": item["topPressurePascal"] / 1000,
        "bottomPressureKPa": (item["topPressurePascal"] + (item["stageCount"] - 1) * item["stagePressureDropPascal"]) / 1000,
        "stagePressureDropPa": item["stagePressureDropPascal"],
        "steamEnabled": bool(item.get("steamFeeds")), "paCount": len(item.get("pumparounds", [])),
        "sideDrawCount": len(item.get("sideDraws", [])),
        "paLocations": canonical([(p["returnTray"], p["drawTray"]) for p in item.get("pumparounds", [])]),
        "sideDrawLocations": canonical([d["trayNumber"] for d in item.get("sideDraws", [])]),
        "status": row.get("status"), "outcomeClass": outcome_class(row), "success": row.get("success"),
        "equilibriumQualified": water_qualified(row), "waterQualification": row.get("waterQualification"),
        "sourceSuccess": row.get("sourceSuccess"), "sourceStatus": row.get("sourceStatus"),
        "rawSupported": (row.get("rawPrediction") or {}).get("supported"),
        "wallMillis": row.get("ms", row.get("cold_ms")), "cpuMillis": row.get("cpuMillis"),
        "allocatedBytes": row.get("allocatedBytes"), "failure": row.get("failure"),
        "solvePath": (row.get("diagnostics") or {}).get("solvePath")}
    for key in ("kelvin", "ratio", "watts"):
        result[{"kelvin": "condenserKelvin", "ratio": "refluxRatio", "watts": "reboilerWatts"}[key]] = next(s[key] for s in item["specifications"] if key in s)
    result.update({"zone:" + name: value for name, value in zones.items()})
    for kind in ("rawVsFinal", "rawVsTeacher"):
        for key, value in (row.get(kind) or {}).items():
            if finite(value) or isinstance(value, bool):
                result[kind + ":" + key] = value
    return result


def write_csv(path, rows):
    names = list(dict.fromkeys(key for row in rows for key in row))
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=names)
        writer.writeheader()
        writer.writerows(rows)


def display(value, digits=2):
    return "unavailable" if value is None else f"{value:,.{digits}f}"


def markdown_report(card):
    design, teacher = card["design"], card["teacher"]
    stats = teacher["summary"]
    text = ["# Generalized initializer experiment", "",
        "This report keeps strict equilibrium qualification separate from the solver's accepted dry supersaturation advisories.", "",
        f"The frozen matrix contains {design.get('candidateRows', '?')} candidates and {design.get('admittedRows', '?')} preflight-admitted inputs. "
        f"The observed teacher journal has {stats['cases']} cases, {stats['accepted']} accepted and {stats['equilibriumQualified']} equilibrium-qualified. "
        f"Journal complete: **{teacher['evidence']['complete']}**.", "",
        "| Teacher outcome | Cases |", "| --- | ---: |"]
    text += [f"| {key} | {value} |" for key, value in stats["outcomeClasses"].items()]
    text += ["", "Native solver admissions and continuation-path bounds are retained as solver outcomes. They are not relabelled as proven physical impossibility.", ""]
    for experiment in card["evaluations"]:
        summary = experiment["summary"]
        text += [f"## Evaluation: {Path(experiment['evidence']['journal']).parent.name}", "",
            "| Cohort | Observed | Accepted | Equilibrium-qualified |", "| --- | ---: | ---: | ---: |"]
        for name, group in summary["cohorts"].items():
            text.append(f"| {name} | {group['cases']} | {group['accepted']} | {group['equilibriumQualified']} |")
        text += ["", "Failure-retry cohorts overlap validation/test cases and are selected by the original outcome; their counts must not be added to held-out counts.", "",
            "| Original failure cohort | Retried | Accepted rescues | Qualified rescues |", "| --- | ---: | ---: | ---: |"]
        for name, values in summary["rescues"].items():
            text.append(f"| {name} | {values['originalFailuresRetried']} | {values['acceptedRescues']} | {values['qualifiedRescues']} |")
        raw = summary["rawVsFinalQualified"]
        text += ["", f"Raw predictions were compared to {raw['profilesCompared']} qualified final profiles. "
            f"Median case temperature RMSE: {display(raw['metrics']['temperatureRmseKelvin']['median'])} K; "
            f"median component-flow RMSE: {display(raw['metrics']['componentFlowRmseMolPerSecond']['median'])} mol/s.", "",
            "Weak zones below are descriptive subsets of the observed test cohort, each with at least ten cases; overlapping zones are not independent experiments.", "",
            "| Test dimension | Zone | Cases | Qualified | Accepted |", "| --- | --- | ---: | ---: | ---: |"]
        for zone in summary["weakTestZonesWithAtLeast10Cases"][:12]:
            text.append(f"| {zone['dimension']} | {zone['zone'].replace('|', '/')} | {zone['cases']} | {zone['qualified']} | {zone['accepted']} |")
        text.append("")
    if card.get("benchmark"):
        benchmark = card["benchmark"]["summary"]
        text += ["## Serial latency and allocation", "", "| Method | Cases | Accepted | Qualified | Median all-attempt ms | p95 all-attempt ms |", "| --- | ---: | ---: | ---: | ---: | ---: |"]
        for name in ("current", "neural", *(["neuralFirst"] if "neuralFirst" in benchmark else [])):
            item = benchmark[name]
            text.append(f"| {name} | {item['cases']} | {item['accepted']} | {item['equilibriumQualified']} | {display(item['wallMillisAllAttempts']['median'])} | {display(item['wallMillisAllAttempts']['p95'])} |")
        text += ["", f"Both methods produced qualified results for {benchmark['bothQualifiedCases']} identical cases. "
            f"Median paired classical/neural wall-time ratio: {display(benchmark['qualifiedPairsClassicalOverNeuralWallRatio']['median'])}. "
            "Fast rejection is excluded from that ratio. All-attempt timing includes failures and must be read with the acceptance counts.", ""]
        if "neuralFirstComparison" in benchmark:
            first = benchmark["neuralFirstComparison"]
            text += [f"LNN_FIRST retained {first['classicalAcceptedCasesRetained']} classical successes and lost {first['classicalAcceptedCasesLost']} within the shared request budget; "
                f"it rescued {first['classicalFailuresRescued']} failures in this serial comparison. Its latency includes any classical fallback.", ""]
    text += ["## Memory and scope", "", "Per-thread allocated bytes measure allocation volume, not retained RAM. "
        "JVM heap, private bytes and working set include the whole process; a ten-worker peak cannot be attributed to one initializer. "
        "Memory-pool peaks are not summed because they need not occur simultaneously. Process memory is reported in the JSON evidence where available.", "",
        "The complete case CSV and zone CSV preserve numerical failures, deadlines, solver admissions, warnings, locations and all-component mixture bins. "
        "No failed or advisory-only profile is a training label. Validation may select a model; held-out test and prior-failure retries must be run only after its artifact hash and policies freeze.", ""]
    if card.get("isolatedMemoryRuns"):
        text += ["### Separate JVM profiles", "", "Each mode below ran the same frozen input-only benchmark in its own serial JVM. "
            "Peaks include that JVM, its warm-up and source inputs; they are not retained bytes per case or an in-game memory measurement.", "",
            "| Mode | Cases | Accepted | Qualified | Sampled peak working set MiB | Sampled peak private MiB |", "| --- | ---: | ---: | ---: | ---: | ---: |"]
        for profile in card["isolatedMemoryRuns"]:
            memory = profile["evidence"].get("processMemory") or {}
            mode = profile["evidence"]["run"].get("mode", Path(profile["evidence"]["journal"]).parent.name)
            working, private = memory.get("sampledPeakWorkingSetBytes"), memory.get("sampledPeakPrivateBytes")
            stats = profile["summary"]
            text.append(f"| {mode} | {stats['cases']} | {stats['accepted']} | {stats['equilibriumQualified']} | "
                        f"{display(working / 1048576 if finite(working) else None)} | {display(private / 1048576 if finite(private) else None)} |")
        text.append("")
    return "\n".join(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--design", type=Path, default=ROOT / "build/neural-generalized/design")
    parser.add_argument("--teacher", type=Path, default=ROOT / "build/neural-generalized/v2/cases.jsonl")
    parser.add_argument("--evaluation", type=Path, action="append", default=[])
    parser.add_argument("--benchmark", type=Path)
    parser.add_argument("--isolated-profile", type=Path, action="append", default=[],
                        help="A profile-current/profile-neural journal from a separate serial JVM; repeatable")
    parser.add_argument("--prepared", type=Path, default=ROOT / "build/neural-generalized/evaluation-inputs")
    parser.add_argument("--output", type=Path, default=ROOT / "build/neural-generalized/analysis/summary.json")
    parser.add_argument("--final", action="store_true")
    args = parser.parse_args()
    design = json.loads((args.design / "design.json").read_text(encoding="utf-8-sig"))
    matrix, _ = load_jsonl(args.design / "matrix.jsonl")
    exclusions, _ = load_jsonl(args.design / "exclusions.jsonl")
    teacher, evidence = read_run(args.teacher, args.final)
    expected_ids = {str(row["id"]) for row in matrix}
    actual_ids = {str(row["id"]) for row in teacher}
    if not actual_ids <= expected_ids or args.final and actual_ids != expected_ids:
        raise ValueError("Teacher IDs do not match the frozen admitted matrix")
    matrix_by_id = {str(row["id"]): row for row in matrix}
    for row in teacher:
        authored = matrix_by_id[str(row["id"])]
        if row["split"] != authored["split"] or canonical_input_hash(row["input"]) != canonical_input_hash(authored["input"]):
            raise ValueError("Teacher changed a frozen input or split: " + str(row["id"]))
    evidence["expectedDesignRows"] = len(matrix)
    evidence["missingDesignRows"] = len(expected_ids - actual_ids)
    if len(teacher) != len(matrix):
        evidence["complete"] = False
    teacher_hashes = {canonical_input_hash(row["input"]): row for row in teacher}
    baseline = design.get("baselineInput")
    card = {"revision": "generalized-experiment-summary-v1", "finalRequested": args.final,
        "design": {key: value for key, value in design.items() if key not in ("baselineInput", "factorNames")},
        "preflightExclusions": {"rows": len(exclusions),
            "categoryCounts": dict(Counter(reason["category"] for row in exclusions for reason in row["exclusions"])),
            "codeCounts": dict(Counter(reason["code"] for row in exclusions for reason in row["exclusions"])),
            "meaning": "Multiple exclusion reasons can apply to one row. Model-contract exclusions are not general physical impossibility."},
        "teacher": {"evidence": evidence, "summary": solve_summary(teacher),
            "splitSummaries": {name: solve_summary([row for row in teacher if row["split"] == name]) for name in ("train", "validation", "test")},
            "zones": zone_summary(teacher, baseline)}, "evaluations": [], "isolatedMemoryRuns": [],
        "trainingPolicy": "Only equilibrium-qualified, original training-split profiles may be fitted. Accepted dry supersaturation advisories and all original failures remain mapped but have no training labels."}
    case_rows = [compact_row(row, "teacher", baseline) for row in teacher]
    zone_rows = [{"run": "teacher", **zone} for zone in card["teacher"]["zones"]]
    for path in args.evaluation:
        rows, run_evidence = read_run(path, args.final)
        summary = evaluation_summary(rows, teacher_hashes, baseline)
        card["evaluations"].append({"evidence": run_evidence, "summary": summary})
        label = path.parent.name
        case_rows += [compact_row(row, label, baseline) for row in rows]
        zone_rows += [{"run": label, **zone} for zone in summary["testZones"]]
    if args.benchmark:
        rows, run_evidence = read_run(args.benchmark, args.final)
        card["benchmark"] = {"evidence": run_evidence, "summary": benchmark_summary(rows)}
        for row in rows:
            for name in ("current", "neural", "neuralFirst"):
                if isinstance(row.get(name), dict):
                    case_rows.append(compact_row({**row, **row[name]}, "serial:" + name, baseline))
    for path in args.isolated_profile:
        rows, run_evidence = read_run(path, args.final)
        if run_evidence["run"] and run_evidence["run"].get("workers") != 1:
            raise ValueError("An isolated initializer profile must use one solver worker: " + str(path))
        card["isolatedMemoryRuns"].append({"evidence": run_evidence, "summary": solve_summary(rows),
            "scope": "Separate serial JVM, frozen benchmark inputs only; no accepted teacher profiles should be loaded. Whole-process peak memory includes warm-up, runtime and input parsing; it is not retained RAM per request or an in-game estimate."})
        case_rows += [compact_row(row, "isolated:" + path.parent.name, baseline) for row in rows]
    unsupported_path = args.prepared / "unsupported-prior-failures.jsonl"
    unsupported, _ = load_jsonl(unsupported_path, allow_partial=True)
    card["unsupportedPriorFailures"] = {"count": len(unsupported), "ids": [row["id"] for row in unsupported],
        "reason": "Legacy component axes do not match the model's registered 20-component basis. These cases are reported separately, without a padding/remapping claim."}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(card, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    args.output.with_suffix(".md").write_text(markdown_report(card), encoding="utf-8")
    write_csv(args.output.with_name(args.output.stem + "-cases.csv"), case_rows)
    write_csv(args.output.with_name(args.output.stem + "-zones.csv"), zone_rows)
    print(canonical({"teacherObserved": len(teacher), "teacherExpected": len(matrix), "teacherQualified": sum(water_qualified(row) for row in teacher),
        "teacherComplete": evidence["complete"], "evaluationRuns": len(card["evaluations"]), "output": str(args.output)}))


if __name__ == "__main__":
    main()

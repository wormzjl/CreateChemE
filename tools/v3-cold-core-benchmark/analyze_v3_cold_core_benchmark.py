#!/usr/bin/env python3
"""Analyze append-only V3 cold-core benchmark samples without third-party modules.

Usage:
  python scripts/analyze_v3_cold_core_benchmark.py RUN_DIRECTORY
  python scripts/analyze_v3_cold_core_benchmark.py --self-test

The run directory must contain samples.jsonl.  comparison.json and report.md are
written there unless --output-directory is supplied.  This intentionally reports
unknown/incomplete values as null rather than filling them with zero or a timeout.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

SUCCESS = {"SUCCESS_EXACT", "SUCCESS_REDUCED", "SUCCESS_IDENTITY", "SUCCESS_FALLBACK",
           "EXACT", "REDUCED", "IDENTITY", "FALLBACK"}
RESOURCE = {"COOPERATIVE_TIMEOUT", "DEADLINE_EXCEEDED", "LATE_SUCCESS", "WATCHDOG_KILLED",
            "RESOURCE_LIMIT", "HARNESS_CRASH", "HARNESS_START_TIMEOUT"}
FAILURE = {"API_FAILURE", "DEADLINE_EXCEEDED", "LATE_SUCCESS", "WATCHDOG_KILLED",
           "RESOURCE_LIMIT", "EXCEPTION", "HARNESS_CRASH", "NOT_RUN", "CANCELLED"}
VALID_PHASES = {"warmup", "screen", "confirm", "timing", "slowdown", "pilot"}


def nested(value: dict[str, Any], *names: str) -> Any:
    """Read the first present dotted name, returning None for an absent value."""
    for name in names:
        current: Any = value
        found = True
        for part in name.split("."):
            if not isinstance(current, dict) or part not in current:
                found = False
                break
            current = current[part]
        if found:
            return current
    return None


def status_of(sample: dict[str, Any]) -> str | None:
    value = nested(sample, "status")
    return str(value).upper() if value is not None else None


def accepted(sample: dict[str, Any] | None) -> bool | None:
    if sample is None:
        return None
    state = status_of(sample)
    if state in SUCCESS:
        return True
    if state in FAILURE:
        return False
    return None


def as_number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)) and math.isfinite(value):
        return float(value)
    return None


def median(values: Iterable[Any]) -> float | None:
    clean = [number for value in values if (number := as_number(value)) is not None]
    return statistics.median(clean) if clean else None


def geometric_mean(values: Iterable[float]) -> float | None:
    clean = [value for value in values if value > 0 and math.isfinite(value)]
    return math.exp(sum(math.log(value) for value in clean) / len(clean)) if clean else None


def normalise_sample(raw: dict[str, Any], index: int) -> dict[str, Any]:
    """Keep worker fields, while accepting the supervisor's harness observations."""
    sample = dict(raw)
    sample["_line"] = index
    sample["phase"] = str(sample.get("phase", "screen")).lower()
    sample["revision"] = str(sample.get("revision", "")).lower()
    sample["caseId"] = sample.get("caseId")
    sample["repetition"] = sample.get("repetition", 0)
    sample["elapsedMs"] = nested(sample, "elapsedMs", "elapsedMillis")
    return sample


def read_jsonl(path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    diagnostics: dict[str, Any] = {"invalidJsonLines": 0, "nonObjectLines": 0,
                                   "unknownPhaseLines": 0, "missingIdentityLines": 0,
                                   "ignoredNonTerminalJournalRecords": 0}
    if not path.exists():
        raise FileNotFoundError(path)
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                raw = json.loads(line)
            except json.JSONDecodeError:
                diagnostics["invalidJsonLines"] += 1
                continue
            if not isinstance(raw, dict):
                diagnostics["nonObjectLines"] += 1
                continue
            # The supervisor journals readiness and CALL_STARTED events too.  They
            # are provenance, not terminal measurements and must not become fake
            # missing-status screen records.
            if raw.get("kind") is not None and raw.get("kind") not in {"sample", "harness"}:
                diagnostics["ignoredNonTerminalJournalRecords"] += 1
                continue
            sample = normalise_sample(raw, line_number)
            if sample["phase"] not in VALID_PHASES:
                diagnostics["unknownPhaseLines"] += 1
            if not sample["caseId"] or not sample["revision"]:
                diagnostics["missingIdentityLines"] += 1
            samples.append(sample)
    diagnostics["parsedLines"] = len(samples)
    return samples, diagnostics


def load_manifest(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def manifest_cases(manifest: dict[str, Any]) -> tuple[dict[str, dict[str, Any]], list[str]]:
    cases = {case["id"]: case for case in manifest.get("cases", []) if "id" in case}
    default = list(nested(manifest, "suites.default") or [])
    if not default:
        default = [case_id for case_id, case in cases.items() if case.get("enabledByDefault")]
    return cases, default


def case_details(case: dict[str, Any] | None, sample: dict[str, Any] | None = None) -> dict[str, Any]:
    source = sample or {}
    supplied = nested(source, "input", "inputJson")
    supplied = supplied if isinstance(supplied, dict) else {}
    manifest_input = (case or {}).get("input", {})
    manifest_input = manifest_input if isinstance(manifest_input, dict) else {}
    cutoff = nested(source, "requestedCutoffMoleFraction", "requestedCutoff")
    if cutoff is None and case is not None:
        cutoff = case.get("requestedCutoffMoleFraction")
    steam = nested(supplied, "steamFeeds")
    if steam is None:
        steam = nested(manifest_input, "steamFeeds")
    feed_total = nested(supplied, "feedMolarFlowMolPerSecond")
    if feed_total is None:
        feed_vector = nested(supplied, "feedComponentMolarFlowsMolPerSecond")
        if isinstance(feed_vector, dict):
            feed_total = sum(value for value in (as_number(item) for item in feed_vector.values()) if value is not None)
    if feed_total is None:
        feed_total = nested(manifest_input, "feedMolarFlowMolPerSecond")
    return {
        "panel": (case or {}).get("panel") or source.get("panel"),
        "cutoff": cutoff,
        "steam": bool(steam) if isinstance(steam, list) else None,
        "feedMolarFlowMolPerSecond": feed_total,
    }


def latest_by_key(samples: Iterable[dict[str, Any]]) -> dict[tuple[str, str], dict[str, Any]]:
    selected: dict[tuple[str, str], dict[str, Any]] = {}
    for sample in samples:
        key = (sample["revision"], str(sample["caseId"]))
        current = selected.get(key)
        if current is None or sample["_line"] > current["_line"]:
            selected[key] = sample
    return selected


def confirmation_result(confirmations: list[dict[str, Any]], minimum_samples: int = 1) -> dict[str, Any]:
    """Confirmation is authoritative only when the serial outcomes are repeatable."""
    values = [accepted(sample) for sample in confirmations]
    known = [value for value in values if value is not None]
    if not confirmations:
        return {"state": "not_requested", "accepted": None, "status": None, "samples": 0}
    if len(confirmations) < minimum_samples:
        return {"state": "incomplete", "accepted": None, "status": None, "samples": len(confirmations),
                "minimumSamples": minimum_samples}
    if not known:
        return {"state": "unknown", "accepted": None, "status": None, "samples": len(confirmations)}
    if len(known) != len(confirmations) or len(set(known)) != 1:
        return {"state": "unstable", "accepted": None, "status": None, "samples": len(confirmations)}
    return {"state": "repeatable", "accepted": known[0],
            "status": status_of(confirmations[-1]), "samples": len(confirmations)}


def authoritative(screen: dict[str, Any] | None, confirmations: list[dict[str, Any]],
                  minimum_samples: int = 1, required: bool = False) -> dict[str, Any]:
    confirmed = confirmation_result(confirmations, minimum_samples)
    if required and confirmed["state"] == "not_requested":
        return {"state": "required_missing", "accepted": None, "status": None, "samples": 0,
                "minimumSamples": minimum_samples, "source": "serial_confirmation"}
    if confirmed["state"] == "repeatable":
        return {**confirmed, "source": "serial_confirmation"}
    if confirmed["state"] in {"unstable", "unknown", "incomplete"}:
        return {**confirmed, "source": "serial_confirmation"}
    return {"state": "screen_only", "accepted": accepted(screen), "status": status_of(screen) if screen else None,
            "samples": 0, "source": "screen"}


def pair_category(baseline: bool | None, candidate: bool | None) -> str:
    if baseline is None or candidate is None:
        return "incomplete_or_unstable"
    if baseline and candidate:
        return "both_accepted"
    if baseline and not candidate:
        return "baseline_only_regression"
    if not baseline and candidate:
        return "candidate_only_win"
    return "both_not_accepted"


def group_counts(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[Any, Any, Any], Counter[str]] = defaultdict(Counter)
    for row in rows:
        details = row["details"]
        key = (details["panel"], details["cutoff"], details["steam"])
        groups[key][row["comparison"]] += 1
    result = []
    for key in sorted(groups, key=lambda value: tuple("" if part is None else str(part) for part in value)):
        counter = groups[key]
        result.append({"panel": key[0], "requestedCutoffMoleFraction": key[1], "steam": key[2],
                       "caseCount": sum(counter.values()), **{name: counter.get(name, 0) for name in
                       ("both_accepted", "candidate_only_win", "baseline_only_regression",
                        "both_not_accepted", "incomplete_or_unstable")}})
    return result


def status_summary(samples: Iterable[dict[str, Any]]) -> dict[str, Any]:
    counts: Counter[str] = Counter()
    elapsed: dict[str, list[Any]] = defaultdict(list)
    for sample in samples:
        state = status_of(sample) or "MISSING_STATUS"
        counts[state] += 1
        elapsed[state].append(sample.get("elapsedMs"))
    accepted_elapsed = [sample.get("elapsedMs") for sample in samples if accepted(sample)]
    failed_elapsed = [sample.get("elapsedMs") for sample in samples if accepted(sample) is False]
    return {"counts": dict(sorted(counts.items())),
            "medianElapsedMsByStatus": {state: median(values) for state, values in sorted(elapsed.items())},
            "timelyAcceptedCost": {"sampleCount": len(accepted_elapsed), "medianElapsedMs": median(accepted_elapsed)},
            "failureAndTimeoutCost": {"sampleCount": len(failed_elapsed), "medianElapsedMs": median(failed_elapsed)}}


def support_summary(samples: Iterable[dict[str, Any]]) -> dict[str, Any]:
    accepted_samples = [sample for sample in samples if accepted(sample)]
    route = Counter(status_of(sample) or "MISSING_STATUS" for sample in accepted_samples)
    positive = [sample for sample in accepted_samples
                if (cutoff := as_number(nested(sample, "requestedCutoffMoleFraction", "requestedCutoff"))) is not None and cutoff > 0]
    support_counts = [nested(sample, "terminalSupport.returnedSupportCount", "terminalSupport.supportCount",
                             "terminalSupport.retainedComponentCount", "terminalSupport.retainedPointCount") for sample in positive]
    return {"acceptedSampleCount": len(accepted_samples), "acceptedRoutes": dict(sorted(route.items())),
            "positiveCutoffAcceptedCount": len(positive),
            "medianReturnedSupportCount": median(support_counts),
            "fallbackCount": sum(status_of(sample) in {"SUCCESS_FALLBACK", "FALLBACK"} for sample in positive),
            "fallbackRate": (sum(status_of(sample) in {"SUCCESS_FALLBACK", "FALLBACK"} for sample in positive) / len(positive)
                             if positive else None)}


def bool_field_summary(samples: Iterable[dict[str, Any]], dotted: str) -> dict[str, Any]:
    values = [nested(sample, dotted) for sample in samples if accepted(sample)]
    known = [value for value in values if isinstance(value, bool)]
    return {"known": len(known), "true": sum(known), "false": len(known) - sum(known),
            "unknown": len(values) - len(known)}


def closure_summary(samples: Iterable[dict[str, Any]]) -> dict[str, Any]:
    successes = [sample for sample in samples if accepted(sample)]
    # The worker keeps full closure records.  The generic summary is deliberately
    # structural: no numerical closure is invented if feed vectors were unavailable.
    records = [nested(sample, "closure") for sample in successes]
    present = [record for record in records if isinstance(record, dict)]
    scalar_flags: dict[str, dict[str, Any]] = {}
    for name in ("exactHydrocarbonClosurePassed", "waterClosurePassed", "nativeSinkEdgeTruncationAuditAvailability",
                 "reconstructedHydrocarbonLossMatchesNativeAudit",
                 "hydrocarbonClosed", "waterClosed", "energyClosed", "equilibriumPassed", "phasePassed",
                 "passed", "withinBound", "externalHydrocarbonClosurePassed", "externalWaterClosurePassed"):
        values = [nested(record, name, "externalHydrocarbon." + name, "externalWater." + name) for record in present]
        known = [value for value in values if isinstance(value, bool)]
        if known:
            scalar_flags[name] = {"true": sum(known), "false": len(known) - sum(known),
                                  "unknown": len(values) - len(known)}
    def scoped_flags(records: list[dict[str, Any]], name: str) -> dict[str, Any]:
        values = [record.get(name) for record in records]
        known = [value for value in values if isinstance(value, bool)]
        return {"true": sum(known), "false": len(known) - sum(known), "unknown": len(values) - len(known)}
    exact_zero = [record for sample, record in zip(successes, records)
                  if isinstance(record, dict) and as_number(nested(sample, "requestedCutoffMoleFraction", "requestedCutoff")) == 0]
    reduced = [record for sample, record in zip(successes, records)
               if isinstance(record, dict) and (cutoff := as_number(nested(sample, "requestedCutoffMoleFraction", "requestedCutoff"))) is not None and cutoff > 0]
    return {"successfulSamples": len(successes), "closureRecordCount": len(present),
            "closureMissingCount": len(successes) - len(present), "flags": scalar_flags,
            "cutoffZeroExactHydrocarbonClosure": scoped_flags(exact_zero, "exactHydrocarbonClosurePassed"),
            "positiveCutoffExternalHydrocarbonClosure": scoped_flags(reduced, "externalHydrocarbonClosurePassed"),
            "positiveCutoffExactClosureIsNotExpected": len(reduced)}


def stream_signature(sample: dict[str, Any]) -> dict[str, Any] | None:
    streams = nested(sample, "streams")
    if not isinstance(streams, list):
        return None
    result: dict[str, Any] = {}
    for stream in streams:
        if not isinstance(stream, dict):
            continue
        stream_id = nested(stream, "id", "streamId", "stableId")
        if stream_id is None:
            continue
        components = nested(stream, "componentMolarFlowsMolPerSecond", "componentMolarFlows")
        if not isinstance(components, dict):
            components = {}
        result[str(stream_id)] = {"phase": nested(stream, "phase"),
                                  "temperatureKelvin": nested(stream, "temperatureKelvin", "temperatureK"),
                                  "molarFlowMolPerSecond": nested(stream, "molarFlowMolPerSecond", "totalMolarFlowMolPerSecond"),
                                  "components": {str(key): as_number(value) for key, value in components.items()}}
    return result


def output_drift(baseline: dict[str, Any] | None, candidate: dict[str, Any] | None,
                 feed_total: Any) -> dict[str, Any]:
    left, right = (stream_signature(baseline) if baseline else None), (stream_signature(candidate) if candidate else None)
    first_fingerprint = nested(baseline or {}, "outputFingerprintSha256")
    second_fingerprint = nested(candidate or {}, "outputFingerprintSha256")
    bit_identical = (first_fingerprint == second_fingerprint
                     if first_fingerprint is not None and second_fingerprint is not None else None)
    if left is None or right is None:
        return {"available": False, "reason": "missing_streams", "bitIdenticalOutput": bit_identical,
                "reviewFlag": None}
    feed = as_number(feed_total)
    component_limit = 1e-6 * feed if feed is not None else None
    stream_ids_changed = set(left) != set(right)
    phase_changed = False
    max_component_delta = 0.0
    max_stream_molar_flow_delta = 0.0
    max_temperature_delta = 0.0
    for stream_id in set(left) & set(right):
        first, second = left[stream_id], right[stream_id]
        phase_changed |= first["phase"] != second["phase"]
        first_temp, second_temp = as_number(first["temperatureKelvin"]), as_number(second["temperatureKelvin"])
        if first_temp is not None and second_temp is not None:
            max_temperature_delta = max(max_temperature_delta, abs(first_temp - second_temp))
        first_total, second_total = as_number(first["molarFlowMolPerSecond"]), as_number(second["molarFlowMolPerSecond"])
        if first_total is not None and second_total is not None:
            max_stream_molar_flow_delta = max(max_stream_molar_flow_delta, abs(first_total - second_total))
        for component in set(first["components"]) | set(second["components"]):
            first_flow, second_flow = first["components"].get(component), second["components"].get(component)
            if first_flow is not None and second_flow is not None:
                max_component_delta = max(max_component_delta, abs(first_flow - second_flow))
    review = stream_ids_changed or phase_changed or max_temperature_delta > 0.01
    if component_limit is not None:
        review |= max_component_delta > component_limit
        review |= max_stream_molar_flow_delta > component_limit
    return {"available": True, "bitIdenticalOutput": bit_identical,
            "streamIdentityChanged": stream_ids_changed, "phaseChanged": phase_changed,
            "maxComponentMolarFlowDelta": max_component_delta, "componentReviewLimit": component_limit,
            "maxStreamMolarFlowDelta": max_stream_molar_flow_delta,
            "maxTemperatureDeltaKelvin": max_temperature_delta, "temperatureReviewLimitKelvin": 0.01,
            "reviewFlag": review}


def support_signature(sample: dict[str, Any] | None) -> tuple[Any, ...] | None:
    if sample is None or not accepted(sample):
        return None
    support = nested(sample, "terminalSupport")
    if not isinstance(support, dict):
        return None
    return tuple(nested(support, name) for name in ("returnedSupportCount", "supportCount",
                 "retainedComponentCount", "retainedPointCount", "removedPointCount", "closurePrunedPointCount",
                 "returnedCutoffMoleFraction", "identity", "finalCondenserBranch", "nativeReducedSuccess",
                 "fallbackUsed", "fallbackToUntruncated"))


def outcome_difference(baseline: dict[str, Any] | None, candidate: dict[str, Any] | None,
                       details: dict[str, Any]) -> tuple[dict[str, bool], dict[str, Any], bool]:
    fields = {field: nested(baseline or {}, field) != nested(candidate or {}, field)
              for field in ("status", "apiOutcome", "apiFailureCode", "terminalSupport")}
    drift = output_drift(baseline, candidate, details["feedMolarFlowMolPerSecond"])
    return fields, drift, any(fields.values()) or drift.get("reviewFlag") is True


def confirmation_for_repetition(samples: list[dict[str, Any]], repetition: int) -> dict[str, Any] | None:
    matches = [sample for sample in samples if str(sample.get("repetition")) == str(repetition)]
    return matches[-1] if matches else None


def timing_summary(samples: list[dict[str, Any]], selected_ids: list[str] | None) -> dict[str, Any]:
    phases = [sample for sample in samples if sample["phase"] in {"timing", "slowdown"}]
    initial_timing = [sample for sample in samples if sample["phase"] == "timing"]
    by_case_revision: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for sample in phases:
        if accepted(sample):
            by_case_revision[(str(sample["caseId"]), sample["revision"])].append(sample)
    initial_by_key: dict[tuple[str, str, str], dict[str, Any]] = {}
    for sample in initial_timing:
        initial_by_key[(str(sample["caseId"]), sample["revision"], str(sample.get("repetition")))] = sample
    all_case_ids = sorted({case_id for case_id, _ in by_case_revision})
    if selected_ids is not None:
        all_case_ids = sorted(set(all_case_ids) | set(selected_ids))
    rows = []
    for case_id in all_case_ids:
        baseline = sorted(by_case_revision.get((case_id, "baseline"), []), key=lambda sample: str(sample.get("repetition")))
        candidate = sorted(by_case_revision.get((case_id, "candidate"), []), key=lambda sample: str(sample.get("repetition")))
        by_rep_a = {str(sample.get("repetition")): sample for sample in baseline}
        by_rep_b = {str(sample.get("repetition")): sample for sample in candidate}
        ratios = []
        for repetition in sorted(set(by_rep_a) & set(by_rep_b)):
            first, second = as_number(by_rep_a[repetition].get("elapsedMs")), as_number(by_rep_b[repetition].get("elapsedMs"))
            if first is not None and second is not None and first > 0:
                ratios.append(second / first)
        initial = ratios[:3]
        initial_a = baseline[:3]
        initial_b = candidate[:3]
        initial_ratio = (median([sample.get("elapsedMs") for sample in initial_b]) /
                         median([sample.get("elapsedMs") for sample in initial_a])
                         if median([sample.get("elapsedMs") for sample in initial_a]) not in (None, 0) and
                         median([sample.get("elapsedMs") for sample in initial_b]) is not None else None)
        rows.append({"caseId": case_id, "baselineSuccessfulSamples": len(baseline),
                     "candidateSuccessfulSamples": len(candidate),
                     "baselineMedianElapsedMs": median(sample.get("elapsedMs") for sample in baseline),
                     "candidateMedianElapsedMs": median(sample.get("elapsedMs") for sample in candidate),
                     "baselineMedianThreadCpuMs": median(sample.get("threadCpuMs") for sample in baseline),
                     "candidateMedianThreadCpuMs": median(sample.get("threadCpuMs") for sample in candidate),
                     "baselineMedianThreadAllocatedBytes": median(sample.get("threadAllocatedBytes") for sample in baseline),
                     "candidateMedianThreadAllocatedBytes": median(sample.get("threadAllocatedBytes") for sample in candidate),
                     "baselineAllAcceptedSamplesWithin45Seconds": (all((as_number(sample.get("elapsedMs")) or math.inf) <= 45000 for sample in baseline) if baseline else None),
                     "candidateAllAcceptedSamplesWithin45Seconds": (all((as_number(sample.get("elapsedMs")) or math.inf) <= 45000 for sample in candidate) if candidate else None),
                     "pairedRatioCount": len(ratios), "pairedGeometricMeanCandidateOverBaseline": geometric_mean(ratios),
                     "initialThreeMedianRatio": initial_ratio,
                     "slowdownReviewTriggered": initial_ratio is not None and initial_ratio >= 1.10,
                     "slowdownAdditionalPairsComplete": (len(ratios) >= 8 if initial_ratio is not None and initial_ratio >= 1.10 else None)})
    valid_ratios = [row["pairedGeometricMeanCandidateOverBaseline"] for row in rows
                    if row["pairedGeometricMeanCandidateOverBaseline"] is not None]
    expected_initial = (len(selected_ids) * 2 * 3 if selected_ids is not None else None)
    expected_keys = {(case_id, revision, str(repetition)) for case_id in (selected_ids or [])
                     for revision in ("baseline", "candidate") for repetition in range(1, 4)}
    observed_expected = {key: sample for key, sample in initial_by_key.items() if key in expected_keys}
    all_present = (len(observed_expected) == len(expected_keys) if selected_ids is not None else None)
    all_accepted = (all(accepted(sample) is True for sample in observed_expected.values()) if all_present else None)
    return {"timedCaseCount": len(rows), "selectedTimingCaseIds": selected_ids,
            "selectionExceedsTwelve": (len(selected_ids) > 12 if selected_ids is not None else None),
            "initialTimingCompleteness": {"plannedTerminalCalls": expected_initial,
                                           "observedTerminalCalls": len(observed_expected),
                                           "missingTerminalCalls": (len(expected_keys) - len(observed_expected) if selected_ids is not None else None),
                                           "allPlannedCallsPresent": all_present,
                                           "allPlannedCallsAccepted": all_accepted},
            "timingStatusAndCost": status_summary(initial_timing),
            "suiteEqualWeightGeometricMeanCandidateOverBaseline": (geometric_mean(valid_ratios) if all_accepted else None),
            "cases": rows}


def cutoff_sensitivity(screen: dict[tuple[str, str], dict[str, Any]],
                       cases: dict[str, dict[str, Any]]) -> dict[str, Any]:
    rows = []
    for off_id in sorted(case_id for case_id in cases if case_id.endswith("-off") and case_id[:-4] + "-on" in cases):
        on_id = off_id[:-4] + "-on"
        for revision in ("baseline", "candidate"):
            off, on = screen.get((revision, off_id)), screen.get((revision, on_id))
            jointly_accepted = accepted(off) is True and accepted(on) is True
            details = case_details(cases[off_id], off)
            drift = output_drift(off, on, details["feedMolarFlowMolPerSecond"]) if jointly_accepted else None
            rows.append({"revision": revision, "offCaseId": off_id, "onCaseId": on_id,
                         "offStatus": status_of(off) if off else None, "onStatus": status_of(on) if on else None,
                         "jointlyAccepted": jointly_accepted, "outputSensitivity": drift})
    available = [row for row in rows if row["jointlyAccepted"]]
    return {"pairedCaseRevisionCount": len(rows), "jointlyAcceptedCount": len(available),
            "notJointlyAcceptedCount": len(rows) - len(available),
            "reviewFlagCount": sum(row["outputSensitivity"]["reviewFlag"] is True for row in available),
            "pairs": rows}


def analyze(samples: list[dict[str, Any]], manifest: dict[str, Any], diagnostics: dict[str, Any],
            run_metadata: dict[str, Any] | None = None) -> dict[str, Any]:
    cases, default_ids = manifest_cases(manifest)
    active_ids = list(default_ids)
    for sample in samples:
        case_id = str(sample.get("caseId"))
        if sample.get("caseId") and case_id not in active_ids and sample["phase"] != "warmup":
            active_ids.append(case_id)
    screen = latest_by_key(sample for sample in samples if sample["phase"] == "screen")
    confirms: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for sample in samples:
        if sample["phase"] == "confirm":
            confirms[(sample["revision"], str(sample["caseId"]))].append(sample)
    for grouped in confirms.values():
        grouped.sort(key=lambda sample: sample["_line"])
    rows = []
    for case_id in active_ids:
        case = cases.get(case_id)
        first = screen.get(("baseline", case_id))
        second = screen.get(("candidate", case_id))
        details = case_details(case, first or second)
        resource_observation = any(status_of(sample) in RESOURCE for sample in (first, second) if sample)
        field_changes, drift, outcome_changed = outcome_difference(first, second, details)
        # This matches the supervisor's adaptive resource rule.  A matching
        # parallel deadline/resource pair starts with one serial pair; if its
        # first serial pair differs, it expands to three before any classification.
        first_confirm_a = confirmation_for_repetition(confirms.get(("baseline", case_id), []), 0)
        first_confirm_b = confirmation_for_repetition(confirms.get(("candidate", case_id), []), 0)
        confirmation_difference = False
        if (resource_observation and not outcome_changed and first_confirm_a is not None and first_confirm_b is not None
                and status_of(first_confirm_a) != "NOT_RUN" and status_of(first_confirm_b) != "NOT_RUN"):
            _, _, confirmation_difference = outcome_difference(first_confirm_a, first_confirm_b, details)
        required = resource_observation or outcome_changed
        minimum = 3 if (outcome_changed or confirmation_difference) else (1 if resource_observation else 1)
        a_authoritative = authoritative(first, confirms.get(("baseline", case_id), []), minimum, required)
        b_authoritative = authoritative(second, confirms.get(("candidate", case_id), []), minimum, required)
        comparison = pair_category(a_authoritative["accepted"], b_authoritative["accepted"])
        confirmation_complete = (a_authoritative["state"] == "repeatable" and b_authoritative["state"] == "repeatable") if required else None
        rows.append({"caseId": case_id, "details": details,
                     "screen": {"baselineStatus": status_of(first) if first else None, "candidateStatus": status_of(second) if second else None},
                     "authoritative": {"baseline": a_authoritative, "candidate": b_authoritative},
                     "comparison": comparison, "serialConfirmationRequired": required,
                      "serialConfirmationComplete": confirmation_complete,
                     "serialReviewReasons": [reason for reason, occurred in (("resource_or_deadline", resource_observation),
                         ("status", field_changes["status"]), ("api_outcome", field_changes["apiOutcome"]),
                         ("api_failure_code", field_changes["apiFailureCode"]),
                         ("terminal_support", field_changes["terminalSupport"]),
                         ("output", drift.get("reviewFlag") is True),
                         ("first_serial_pair_difference", confirmation_difference)) if occurred],
                     "outputDrift": drift})
    screen_samples = [sample for sample in samples if sample["phase"] == "screen"]
    recorded_selection = (run_metadata or {}).get("timingSelection")
    if not isinstance(recorded_selection, list):
        recorded_selection = nested(run_metadata or {}, "phases.timing.selected")
    selected = list(recorded_selection) if isinstance(recorded_selection, list) else None
    matrix = Counter(row["comparison"] for row in rows)
    return {"schemaVersion": 1, "benchmark": manifest.get("benchmark"),
            "expectedDefaultCasesPerRevision": len(default_ids),
            "activeCaseCount": len(active_ids), "rawInputDiagnostics": diagnostics,
            "completeness": {"defaultCasesPerRevisionExpected": len(default_ids),
                             "baselineScreenRecords": sum(("baseline", case_id) in screen for case_id in default_ids),
                             "candidateScreenRecords": sum(("candidate", case_id) in screen for case_id in default_ids),
                             "completeDefaultScreen": all((revision, case_id) in screen for revision in ("baseline", "candidate") for case_id in default_ids),
                             "notRunOrMissingDefaultCells": [{"revision": revision, "caseId": case_id} for revision in ("baseline", "candidate") for case_id in default_ids if (revision, case_id) not in screen]},
            "matchedOutcomeMatrix": {name: matrix.get(name, 0) for name in ("both_accepted", "candidate_only_win", "baseline_only_regression", "both_not_accepted", "incomplete_or_unstable")},
            "serialReviewCases": [{"caseId": row["caseId"], "reasons": row["serialReviewReasons"],
                                   "confirmationComplete": row["serialConfirmationComplete"]}
                                  for row in rows if row["serialConfirmationRequired"]],
            "panelCutoffSteamCounts": group_counts(rows),
            "screenStatusAndCost": status_summary(screen_samples),
            "supportAndFallback": {"baseline": support_summary(sample for sample in screen_samples if sample["revision"] == "baseline"),
                                    "candidate": support_summary(sample for sample in screen_samples if sample["revision"] == "candidate")},
            "auditAvailability": {"acceptanceAuditPassed": bool_field_summary(screen_samples, "acceptanceAudit.accepted"),
                                  "rawNewtonCertificateVerified": bool_field_summary(screen_samples, "rawNewtonCertificateVerified")},
            "externalClosure": {"baseline": closure_summary(sample for sample in screen_samples if sample["revision"] == "baseline"),
                                "candidate": closure_summary(sample for sample in screen_samples if sample["revision"] == "candidate")},
            "cutoffSensitivity": cutoff_sensitivity(screen, {case_id: cases[case_id] for case_id in active_ids if case_id in cases}),
            "timing": timing_summary(samples, selected), "cases": rows}


def text_status(value: Any) -> str:
    if value is None:
        return "—"
    return str(value)


def report(comparison: dict[str, Any]) -> str:
    complete = comparison["completeness"]
    matrix = comparison["matchedOutcomeMatrix"]
    timing = comparison["timing"]
    lines = ["# V3 cold-core benchmark comparison", "",
             "This report preserves missing, invalid, and unconfirmed observations. A complete-suite claim is valid only when both screen columns contain all default manifest cases.", "",
             f"Default screen completeness: **{complete['baselineScreenRecords']}/{complete['defaultCasesPerRevisionExpected']} baseline**, **{complete['candidateScreenRecords']}/{complete['defaultCasesPerRevisionExpected']} candidate** (`completeDefaultScreen={complete['completeDefaultScreen']}`).", "",
             "## Matched outcomes", "",
             f"Both accepted: {matrix['both_accepted']}; candidate-only wins: {matrix['candidate_only_win']}; baseline-only regressions: {matrix['baseline_only_regression']}; both not accepted: {matrix['both_not_accepted']}; incomplete or unstable: {matrix['incomplete_or_unstable']}.", "",
             "## Isolated timing", "",
             f"Equal-weight geometric mean of per-case candidate/baseline paired ratios: {text_status(timing['suiteEqualWeightGeometricMeanCandidateOverBaseline'])}. Timed cases: {timing['timedCaseCount']}; selection recorded in run metadata: {', '.join(timing['selectedTimingCaseIds'] or []) or '—'}.", "",
             f"Initial timing calls: planned={text_status(timing['initialTimingCompleteness']['plannedTerminalCalls'])}, observed={timing['initialTimingCompleteness']['observedTerminalCalls']}, all present={text_status(timing['initialTimingCompleteness']['allPlannedCallsPresent'])}, all accepted={text_status(timing['initialTimingCompleteness']['allPlannedCallsAccepted'])}. Failures and deadlines are reported separately in comparison.json.", "",
             "Screen elapsed time is retained as failure/timeout cost and is not an isolated speed claim.", "",
             "## Full case table", "",
             "| Case | Panel | Cutoff | Steam | Screen A | Screen B | Authoritative A | Authoritative B | Paired outcome | Confirmation | Output review |", "|---|---|---:|:---:|---|---|---|---|---|---|---|"]
    for row in comparison["cases"]:
        details, auth = row["details"], row["authoritative"]
        confirmation = "required=" + str(row["serialConfirmationRequired"]) + "; complete=" + text_status(row["serialConfirmationComplete"])
        lines.append("| {case} | {panel} | {cutoff} | {steam} | {sa} | {sb} | {aa} ({as_}) | {ab} ({bs}) | {outcome} | {confirm} | {drift} |".format(
            case=row["caseId"], panel=text_status(details["panel"]), cutoff=text_status(details["cutoff"]), steam=text_status(details["steam"]),
            sa=text_status(row["screen"]["baselineStatus"]), sb=text_status(row["screen"]["candidateStatus"]),
            aa=text_status(auth["baseline"]["status"]), as_=auth["baseline"]["state"], ab=text_status(auth["candidate"]["status"]), bs=auth["candidate"]["state"],
            outcome=row["comparison"], confirm=confirmation, drift=text_status(row["outputDrift"]["reviewFlag"])))
    return "\n".join(lines) + "\n"


def self_test() -> None:
    assert median([1, 3, 2]) == 2
    assert median([]) is None
    assert round(geometric_mean([2, 8]) or 0, 8) == 4
    assert pair_category(True, False) == "baseline_only_regression"
    assert pair_category(None, True) == "incomplete_or_unstable"
    assert confirmation_result([])["state"] == "not_requested"
    assert confirmation_result([{"status": "SUCCESS_EXACT"}] * 3, 3)["state"] == "repeatable"
    assert confirmation_result([{"status": "SUCCESS_EXACT"}], 3)["state"] == "incomplete"
    assert confirmation_result([{"status": "SUCCESS_EXACT"}, {"status": "API_FAILURE"}])["state"] == "unstable"
    manifest = {"benchmark": "test", "suites": {"default": ["C1", "C2"]}, "cases": [
        {"id": "C1", "panel": "p", "requestedCutoffMoleFraction": 0, "input": {"steamFeeds": []}},
        {"id": "C2", "panel": "p", "requestedCutoffMoleFraction": 1e-6, "input": {"steamFeeds": []}}]}
    result = analyze([normalise_sample({"phase": "screen", "revision": "baseline", "caseId": "C1", "status": "SUCCESS_EXACT"}, 1)], manifest, {})
    assert result["completeness"]["completeDefaultScreen"] is False
    assert result["matchedOutcomeMatrix"]["incomplete_or_unstable"] == 2
    resource_manifest = {"benchmark": "resource", "suites": {"default": ["R1"]}, "cases": [
        {"id": "R1", "panel": "p", "requestedCutoffMoleFraction": 0,
         "input": {"feedMolarFlowMolPerSecond": 1.0, "steamFeeds": []}}]}
    resource_samples = [
        normalise_sample({"phase": "screen", "revision": "baseline", "caseId": "R1", "status": "DEADLINE_EXCEEDED"}, 1),
        normalise_sample({"phase": "screen", "revision": "candidate", "caseId": "R1", "status": "DEADLINE_EXCEEDED"}, 2),
        normalise_sample({"phase": "confirm", "revision": "baseline", "caseId": "R1", "repetition": 0, "status": "API_FAILURE"}, 3),
        normalise_sample({"phase": "confirm", "revision": "candidate", "caseId": "R1", "repetition": 0, "status": "SUCCESS_EXACT"}, 4)]
    adaptive = analyze(resource_samples, resource_manifest, {})
    assert adaptive["cases"][0]["authoritative"]["baseline"]["state"] == "incomplete"
    assert "first_serial_pair_difference" in adaptive["cases"][0]["serialReviewReasons"]
    timing_samples = [normalise_sample({"phase": "timing", "revision": revision, "caseId": "T1",
                                         "repetition": repetition, "status": "SUCCESS_EXACT", "elapsedMs": 10}, line)
                      for line, (revision, repetition) in enumerate(
                          ((revision, repetition) for revision in ("baseline", "candidate") for repetition in range(1, 4)), 1)]
    complete_timing = timing_summary(timing_samples, ["T1"])["initialTimingCompleteness"]
    assert complete_timing["plannedTerminalCalls"] == 6 and complete_timing["allPlannedCallsPresent"] is True
    assert complete_timing["allPlannedCallsAccepted"] is True
    missing_timing = timing_summary(timing_samples[:-1], ["T1"])["initialTimingCompleteness"]
    assert missing_timing["missingTerminalCalls"] == 1 and missing_timing["allPlannedCallsPresent"] is False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_directory", nargs="?", type=Path)
    parser.add_argument("--manifest", type=Path,
                        help="manifest JSON; defaults to RUN_DIRECTORY/manifest.json when present")
    parser.add_argument("--output-directory", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("self-test passed")
        return 0
    if args.run_directory is None:
        parser.error("run_directory is required unless --self-test is used")
    samples, diagnostics = read_jsonl(args.run_directory / "samples.jsonl")
    manifest_path = args.manifest or args.run_directory / "manifest.json"
    if not manifest_path.exists():
        manifest_path = Path("src/test/resources/v3-benchmarks/v3-cold-core-v1.json")
    run_metadata_path = args.run_directory / "run.json"
    run_metadata = load_manifest(run_metadata_path) if run_metadata_path.exists() else {}
    comparison = analyze(samples, load_manifest(manifest_path), diagnostics, run_metadata)
    comparison["manifestPath"] = str(manifest_path)
    comparison["runMetadataPath"] = str(run_metadata_path) if run_metadata_path.exists() else None
    output = args.output_directory or args.run_directory
    output.mkdir(parents=True, exist_ok=True)
    (output / "comparison.json").write_text(json.dumps(comparison, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    (output / "report.md").write_text(report(comparison), encoding="utf-8")
    print(f"Wrote {output / 'comparison.json'} and {output / 'report.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

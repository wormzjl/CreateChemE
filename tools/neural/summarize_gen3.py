"""Analyze generation 3 flat candidate journals without rewriting gen2 evidence.

Supply --run LABEL=JOURNAL once per candidate/run. A label can span disjoint
cohorts, but duplicate candidate/cohort/input observations are rejected rather
than silently changing denominators. Use distinct labels for repeated or cached
historical runs. Only original validation may select a candidate.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path

from prepare_generalized_evaluation import canonical_input_hash, load_jsonl, sha
from summarize_generalized import (benchmark_summary, compact_row, display, distribution, finite,
    profile_difference, profile_summary, raw_summary, read_run, solve_summary,
    water_qualified, write_csv, zone_summary)


ROOT = Path(__file__).resolve().parents[2]
COHORT_FILES = {
    "validation": "validation-replay.jsonl",
    "fresh_test": "matrix.jsonl",
    "old_geometry_test": "old-test-replay.jsonl",
    "recovery_comparison": "recovery-comparison.jsonl",
    "remaining_gen2_failure": "remaining-gen2-failures.jsonl",
    "rescued_train_replay": "train-rescue-replay.jsonl",
    "fresh_serial_benchmark": "fresh-benchmark.jsonl",
}
COHORT_MEANING = {
    "validation": "Original405 inputs; model selection allowed; no labels fitted.",
    "fresh_test": "Primary prospective252 operating inputs; no previous solver outputs or fitted labels.",
    "old_geometry_test": "Original395 geometry-test inputs, never fitted but previously reported; regression evidence rather than a newly blind test.",
    "recovery_comparison": "Predeclared256 still-failed gen2 inputs; outcome-conditioned diagnostic sample, including all35 historical failures.",
    "remaining_gen2_failure": "Remaining1934 gen2 failures; overlaps the recovery comparison and original geometry test.",
    "rescued_train_replay": "96 profiles newly included in fitting; success here is fitted-input replay, not generalization.",
    "fresh_serial_benchmark": "64 fresh input-only cases selected before fitting; repeated modes are not new independent cases.",
}


def parse_run(value):
    if "=" not in value:
        raise argparse.ArgumentTypeError("Use candidate-label=journal.jsonl")
    label, path = value.split("=", 1)
    if not label or not path:
        raise argparse.ArgumentTypeError("Both candidate label and journal path are required")
    return label, Path(path)


def load_cohorts(directory):
    manifest = json.loads((directory / "design.json").read_text(encoding="utf-8-sig"))
    populations = {}
    rows_by_cohort = {}
    for cohort, filename in COHORT_FILES.items():
        path = directory / filename
        if sha(path) != manifest["fileSha256"][filename]:
            raise ValueError("Frozen gen3 cohort changed: " + filename)
        rows, _ = load_jsonl(path)
        values = {canonical_input_hash(row["input"]): row for row in rows}
        if len(values) != len(rows):
            raise ValueError("Duplicate canonical inputs in frozen cohort: " + cohort)
        populations[cohort] = set(values)
        rows_by_cohort[cohort] = values
    if populations["rescued_train_replay"] & set.union(*(values for name, values in populations.items() if name != "rescued_train_replay")):
        raise ValueError("Newly fitted rescues overlap a holdout cohort")
    return manifest, populations, rows_by_cohort


def row_cohorts(row, populations):
    key = canonical_input_hash(row["input"])
    declared = (row.get("design") or {}).get("gen3Cohorts") or []
    recognized = [cohort for cohort in declared if cohort in populations]
    if recognized:
        if any(key not in populations[cohort] for cohort in recognized):
            raise ValueError("Authored gen3 cohort tag does not match frozen inputs: " + str(row["id"]))
        return recognized
    # Older cached rows have no gen3 tag. Their exact input hashes still identify
    # cohorts, but a caller should give their historical execution a distinct label.
    return [cohort for cohort, values in populations.items() if key in values]


def run_cohorts(label, row, populations):
    if label.startswith("validation:"):
        if row.get("split") != "validation" or canonical_input_hash(row["input"]) not in populations["validation"]:
            raise ValueError("Validation-labelled run contains an input outside frozen validation")
        return ["validation"]
    return row_cohorts(row, populations)


def strict_rescues(rows):
    def historical(row):
        item = row.get("design") or {}
        return item.get("gen3Origin") == "remaining_gen2_prior_failure" or (item.get("evaluationOrigin") or {}).get("kind") == "prior_failure"

    prior = [row for row in rows if historical(row)]
    original = [row for row in rows if not historical(row)]
    return {name: {"attempted": len(group), "nativeAccepted": sum(row.get("success") is True for row in group),
            "strictQualified": sum(water_qualified(row) for row in group),
            "advisoryOnlyOrUnqualified": sum(row.get("success") is True and not water_qualified(row) for row in group),
            "qualifiedIds": [row["id"] for row in group if water_qualified(row)]}
            for name, group in (("original_matrix", original), ("historical_prior", prior))}


def cohort_summary(rows, expected, cohort, baseline):
    solved = solve_summary(rows)
    zones = zone_summary(rows, baseline)
    strict_references = [row for row in rows if ((row.get("design") or {}).get("gen3Reference") or {}).get("equilibriumQualified") is True]
    result = {"population": expected, "observedUniqueInputs": len(rows), "completePopulation": len(rows) == expected,
        "meaning": COHORT_MEANING[cohort], "solve": solved, "rawPrediction": raw_summary(rows),
        "rawVsFinalAllAccepted": profile_summary(row.get("rawVsFinal") for row in rows),
        "rawVsFinalQualified": profile_summary(row.get("rawVsFinal") for row in rows if water_qualified(row)),
        "rawVsProvidedReference": profile_summary(row.get("rawVsTeacher") for row in rows),
        "rawVsStrictProvidedReference": {"referenceQualifiedInputs": len(strict_references),
            **profile_summary(row.get("rawVsTeacher") for row in strict_references)},
        "zones": zones,
        "weakZonesAtLeast10Inputs": sorted([zone for zone in zones if zone["cases"] >= 10],
            key=lambda zone: (zone["qualifiedFraction"], zone["acceptedFraction"], -zone["cases"]))[:30]}
    if cohort in ("recovery_comparison", "remaining_gen2_failure"):
        result["rescuesOfGen2Failures"] = strict_rescues(rows)
    if cohort == "rescued_train_replay":
        result["generalizationClaimAllowed"] = False
    return result


def matched_comparison(baseline, candidate):
    base = {canonical_input_hash(row["input"]): row for row in baseline}
    test = {canonical_input_hash(row["input"]): row for row in candidate}
    shared = sorted(base.keys() & test.keys())
    qualified_profiles = []
    for key in shared:
        if water_qualified(base[key]) and water_qualified(test[key]):
            difference = profile_difference(test[key].get("seed"), base[key].get("seed"), test[key]["input"])
            if difference:
                qualified_profiles.append(difference)
    return {"matchedInputs": len(shared),
        "baselineQualified": sum(water_qualified(base[key]) for key in shared),
        "candidateQualified": sum(water_qualified(test[key]) for key in shared),
        "qualifiedGains": sum(not water_qualified(base[key]) and water_qualified(test[key]) for key in shared),
        "qualifiedLosses": sum(water_qualified(base[key]) and not water_qualified(test[key]) for key in shared),
        "bothQualified": sum(water_qualified(base[key]) and water_qualified(test[key]) for key in shared),
        "nativeSuccessGains": sum(base[key].get("success") is not True and test[key].get("success") is True for key in shared),
        "nativeSuccessLosses": sum(base[key].get("success") is True and test[key].get("success") is not True for key in shared),
        "qualifiedFinalProfileAgreement": profile_summary(qualified_profiles),
        "timingComparison": "Concurrent per-case wall times are not interpreted as speedups; use the separate same-input serial benchmark."}


def paired_isolated_comparison(current, candidate):
    """Strictly qualified identical-input pairs only; rejection has no speed ratio."""
    classical = {canonical_input_hash(row["input"]): row for row in current}
    proposed = {canonical_input_hash(row["input"]): row for row in candidate}
    if len(classical) != len(current) or len(proposed) != len(candidate):
        raise ValueError("Isolated timing profiles must have unique canonical inputs")
    shared = sorted(classical.keys() & proposed.keys())
    qualified = [key for key in shared if water_qualified(classical[key]) and water_qualified(proposed[key])]
    metrics = {}
    for metric in ("ms", "cpuMillis", "allocatedBytes"):
        observed = [key for key in qualified if finite(classical[key].get(metric)) and finite(proposed[key].get(metric))]
        metrics[metric] = {
            "currentOverCandidateRatio": distribution(classical[key][metric] / proposed[key][metric] for key in observed if proposed[key][metric] > 0),
            "candidateMinusCurrentDifference": distribution(proposed[key][metric] - classical[key][metric] for key in observed),
            "differenceSign": "Negative means candidate used less wall time, thread CPU time or allocated bytes.",
        }
    return {"matchedInputs": len(shared), "currentObservedInputs": len(current), "candidateObservedInputs": len(candidate),
        "currentQualifiedOnMatchedInputs": sum(water_qualified(classical[key]) for key in shared),
        "candidateQualifiedOnMatchedInputs": sum(water_qualified(proposed[key]) for key in shared),
        "strictBothQualifiedPairs": len(qualified), "metrics": metrics,
        "qualifiedPairIds": [proposed[key]["id"] for key in qualified],
        "scope": "Separate one-worker JVMs on identical fresh input-only cases. Ratios/differences use only pairs strictly qualified by both methods. Fast rejection and one-sided successes never enter speed ratios. Allocation is volume, not retained RAM; process memory stays separately reported."}


def full_recovery_union(neural, transfer, population):
    """Combine full retry successes by exact request hash, never by adding samples."""
    nn = {canonical_input_hash(row["input"]): row for row in neural}
    knn = {canonical_input_hash(row["input"]): row for row in transfer}
    if len(nn) != len(neural) or len(knn) != len(transfer) or not nn.keys() <= population or not knn.keys() <= population:
        raise ValueError("Full recovery union requires unique inputs from the frozen remaining-failure pool")
    shared = set(nn) & set(knn)
    nn_qualified = {key for key in shared if water_qualified(nn[key])}
    transfer_qualified = {key for key in shared if water_qualified(knn[key])}
    nn_native = {key for key in shared if nn[key].get("success") is True}
    transfer_native = {key for key in shared if knn[key].get("success") is True}

    def overlaps(left, right):
        return {"neural": len(left), "transfer": len(right), "intersection": len(left & right),
                "union": len(left | right), "neuralOnly": len(left - right), "transferOnly": len(right - left),
                "neitherOnPairedInputs": len(shared - left - right)}

    return {"population": len(population), "neuralObserved": len(nn), "transferObserved": len(knn),
        "pairedInputs": len(shared), "notYetPaired": len(population - shared), "completePopulation": shared == population,
        "strictQualified": overlaps(nn_qualified, transfer_qualified),
        "nativeAccepted": overlaps(nn_native, transfer_native),
        "strictUnionCaseIds": sorted(nn[key]["id"] for key in nn_qualified | transfer_qualified),
        "scope": "Separate diagnostic on full remaining-gen2-failure runs only. One input contributes once to the union, even if both methods solve it. The predeclared256 recovery sample is not added or pooled. This is outcome-conditioned recovery, not prospective population accuracy."}


def metadata_file(path):
    if path is None:
        return None
    return {"path": str(path), "sha256": sha(path), "document": json.loads(path.read_text(encoding="utf-8-sig"))}


def markdown_report(card):
    lines = ["# Generation 3 initializer comparison", "",
        "Only the96 strictly qualified training-fold rescues were eligible for fitting:483 original profiles plus96 rescued profiles gives579 training inputs. "
        "The26 validation and19 test rescues remain excluded from fitting. Generation2 evidence stays unchanged.", ""]
    for cohort in ("validation", "fresh_test", "old_geometry_test", "recovery_comparison", "remaining_gen2_failure", "rescued_train_replay"):
        reports = [(candidate, values[cohort]) for candidate, values in card["candidateCohorts"].items() if cohort in values]
        if not reports:
            continue
        lines += ["## " + cohort.replace("_", " ").capitalize(), "", COHORT_MEANING[cohort], "",
            "| Candidate | Observed / population | Native accepted | Strictly qualified | Advisory or unqualified accepts |",
            "| --- | ---: | ---: | ---: | ---: |"]
        for candidate, report in reports:
            value = report["solve"]
            lines.append(f"| {candidate} | {report['observedUniqueInputs']} / {report['population']} | {value['accepted']} | {value['equilibriumQualified']} | {value['acceptedAdvisoryOrUnqualified']} |")
        lines.append("")
    for serial in card["serialBenchmarks"]:
        report = serial["summary"]
        lines += ["## Fresh serial benchmark", "", "The same frozen fresh64 inputs are used by every mode; the old generation2 benchmark used different inputs and remains historical evidence.", "",
            "| Mode | Cases | Accepted | Qualified | Median all-attempt ms | p95 all-attempt ms |",
            "| --- | ---: | ---: | ---: | ---: | ---: |"]
        for key, label in (("current", "Classical"), ("neural", "Selected candidate only"), ("neuralFirst", "Selected candidate first with fallback")):
            if key in report:
                value = report[key]
                lines.append(f"| {label} | {value['cases']} | {value['accepted']} | {value['equilibriumQualified']} | {display(value['wallMillisAllAttempts']['median'])} | {display(value['wallMillisAllAttempts']['p95'])} |")
        lines += ["", f"Both standalone methods qualified {report['bothQualifiedCases']} identical inputs. "
            f"Median paired classical/candidate wall-time ratio: {display(report['qualifiedPairsClassicalOverNeuralWallRatio']['median'])}. "
            "Fast rejection is excluded from that ratio.", ""]
    if card["isolatedProfiles"]:
        lines += ["## Separate JVM memory profiles", "", "Peaks include each complete JVM, input parsing, model or profile storage and warm-up. Allocated bytes are cumulative allocation volume, not retained RAM.", "",
            "| Candidate / mode | Cases | Qualified | Peak working set MiB | Peak private MiB |",
            "| --- | ---: | ---: | ---: | ---: |"]
        for item in card["isolatedProfiles"]:
            memory = item["evidence"].get("processMemory") or {}
            working = memory.get("sampledPeakWorkingSetBytes")
            private = memory.get("sampledPeakPrivateBytes")
            lines.append(f"| {item['label']} | {item['summary']['cases']} | {item['summary']['equilibriumQualified']} | "
                f"{display(working /1048576 if isinstance(working, (int,float)) else None)} | {display(private /1048576 if isinstance(private, (int,float)) else None)} |")
        lines.append("")
    if card.get("pairedIsolatedProfiles"):
        lines += ["## Paired isolated performance", "", "Only identical inputs strictly qualified by both CURRENT and the candidate enter these ratios and differences. "
            "Positive current/candidate ratios above one indicate less candidate time or allocation; fast rejection is excluded.", "",
            "| Candidate | Strict pairs | Median wall ratio | Median wall difference ms | Median CPU ratio | Median allocation ratio |",
            "| --- | ---: | ---: | ---: | ---: | ---: |"]
        for item in card["pairedIsolatedProfiles"]:
            values = item["comparison"]
            metrics = values["metrics"]
            lines.append(f"| {item['candidate']} | {values['strictBothQualifiedPairs']} | {display(metrics['ms']['currentOverCandidateRatio']['median'])} | "
                f"{display(metrics['ms']['candidateMinusCurrentDifference']['median'])} | {display(metrics['cpuMillis']['currentOverCandidateRatio']['median'])} | "
                f"{display(metrics['allocatedBytes']['currentOverCandidateRatio']['median'])} |")
        lines.append("")
    if card.get("fullRecoveryUnionDiagnostic"):
        union = card["fullRecoveryUnionDiagnostic"]
        strict = union["strictQualified"]
        lines += ["## Full recovery overlap", "", f"Among {union['pairedInputs']} paired requests from the frozen {union['population']}-case remaining-failure pool, "
            f"the neural method qualified {strict['neural']}, transfer qualified {strict['transfer']}, both qualified {strict['intersection']}, and their strict union qualified {strict['union']}. "
            "Each input counts once. The256-case diagnostic sample is not added to these full-run totals.", ""]
    lines += ["Only validation can select a candidate. Fresh test, previously reported geometry-test replay, outcome-conditioned recovery and fitted-rescue replay remain separate. "
        "Overlapping cohorts and repeated runs must not be added as independent samples. New comparison files do not rewrite the cached generation2 metrics.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--design", type=Path, default=ROOT / "build/neural-gen3/fresh-design")
    parser.add_argument("--run", type=parse_run, action="append", default=[])
    parser.add_argument("--baseline-label", action="append", default=[], help="Candidate labels to use as matched physical-success controls; repeatable")
    parser.add_argument("--serial", type=Path, action="append", default=[])
    parser.add_argument("--isolated-profile", type=parse_run, action="append", default=[])
    parser.add_argument("--selection", type=Path)
    parser.add_argument("--cache-manifest", type=Path)
    parser.add_argument("--historical", type=Path, action="append", default=[], help="Read-only prior model cards/run metadata; not pooled into new test metrics")
    parser.add_argument("--output", type=Path, default=ROOT / "build/neural-gen3/analysis/summary.json")
    parser.add_argument("--final", action="store_true")
    args = parser.parse_args()
    manifest, populations, frozen = load_cohorts(args.design)
    reference_input = next(iter(frozen["fresh_test"].values()))["input"]
    baseline_path = ROOT / "tools/neural/methane-qualification.json"
    baseline = json.loads(baseline_path.read_text(encoding="utf-8-sig"))["input"]
    if reference_input["componentBasis"] != baseline["componentBasis"]:
        raise ValueError("Unexpected component axis in the frozen gen3 design")
    card = {"revision": "gen3-candidate-comparison-v1", "finalRequested": args.final,
        "prospectiveDesign": {"path": str(args.design / "design.json"), "sha256": sha(args.design / "design.json"), "document": manifest},
        "selectionFreeze": metadata_file(args.selection), "gen2CacheManifest": metadata_file(args.cache_manifest),
        "historicalEvidence": [metadata_file(path) for path in args.historical],
        "runs": [], "candidateCohorts": {}, "matchedControls": [], "serialBenchmarks": [], "isolatedProfiles": [], "pairedIsolatedProfiles": [],
        "labelPolicy": {"originalTrain": 483, "addedTrainRescues": 96, "totalTrain": 579,
            "validationRescuesExcludedFromFit": 26, "testRescuesExcludedFromFit": 19,
            "fitReplayIsGeneralization": False},
        "comparisonPolicy": "Only original validation selects candidates. Fresh252 is the primary new operating holdout; old395 is previously reported geometry-test replay. Recovery samples are outcome-conditioned; fitted-rescue replay is explicitly not generalization."}
    grouped = defaultdict(lambda: defaultdict(dict))
    case_rows, zone_rows = [], []
    candidate_hashes = defaultdict(set)
    for label, path in args.run:
        rows, evidence = read_run(path, args.final)
        card["runs"].append({"label": label, "evidence": evidence})
        if evidence["run"].get("modelSha256"):
            candidate_hashes[label].add(evidence["run"]["modelSha256"])
        if len(candidate_hashes[label]) > 1:
            raise ValueError("A candidate label must not combine different model artifacts: " + label)
        for row in rows:
            key = canonical_input_hash(row["input"])
            memberships = run_cohorts(label, row, populations)
            if not memberships:
                raise ValueError("Unrecognized input in gen3 candidate comparison: " + str(row["id"]))
            for cohort in memberships:
                if key in grouped[label][cohort]:
                    raise ValueError(f"Duplicate {label}/{cohort} input; give repeated or historical runs separate labels: {row['id']}")
                grouped[label][cohort][key] = row
            flattened = compact_row(row, label, baseline)
            flattened["gen3Cohorts"] = "|".join(memberships)
            flattened["newlyFittedRescueInput"] = key in populations["rescued_train_replay"]
            case_rows.append(flattened)
    for label, values in grouped.items():
        card["candidateCohorts"][label] = {}
        for cohort, by_input in values.items():
            report = cohort_summary(list(by_input.values()), len(populations[cohort]), cohort, baseline)
            card["candidateCohorts"][label][cohort] = report
            zone_rows.extend({"candidate": label, "cohort": cohort, **zone} for zone in report["zones"])
    for control in args.baseline_label:
        if control not in grouped:
            raise ValueError("Requested baseline label is missing: " + control)
        for label, values in grouped.items():
            if label == control:
                continue
            for cohort in grouped[control].keys() & values.keys():
                card["matchedControls"].append({"baseline": control, "candidate": label, "cohort": cohort,
                    **matched_comparison(list(grouped[control][cohort].values()), list(values[cohort].values()))})
    neural_full_label, transfer_full_label = "full-recovery:gen3-factorized", "full-recovery:nearest-k1"
    if neural_full_label in grouped and transfer_full_label in grouped:
        nn_rows = list(grouped[neural_full_label].get("remaining_gen2_failure", {}).values())
        transfer_rows = list(grouped[transfer_full_label].get("remaining_gen2_failure", {}).values())
        card["fullRecoveryUnionDiagnostic"] = {"neuralLabel": neural_full_label, "transferLabel": transfer_full_label,
            **full_recovery_union(nn_rows, transfer_rows, populations["remaining_gen2_failure"])}
    for path in args.serial:
        rows, evidence = read_run(path, args.final)
        if any(canonical_input_hash(row["input"]) not in populations["fresh_serial_benchmark"] for row in rows):
            raise ValueError("Primary gen3 serial benchmark must use the frozen fresh64 source")
        card["serialBenchmarks"].append({"evidence": evidence, "summary": benchmark_summary(rows),
            "population": len(populations["fresh_serial_benchmark"]), "completePopulation": len(rows) == len(populations["fresh_serial_benchmark"])})
        for row in rows:
            for key in ("current", "neural", "neuralFirst"):
                if isinstance(row.get(key), dict):
                    case_rows.append({**compact_row({**row, **row[key]}, "serial:" + key, baseline), "gen3Cohorts": "fresh_serial_benchmark", "newlyFittedRescueInput": False})
    isolated_rows = []
    for label, path in args.isolated_profile:
        rows, evidence = read_run(path, args.final)
        if evidence["run"] and evidence["run"].get("workers") != 1:
            raise ValueError("An isolated profile requires one worker")
        if any(canonical_input_hash(row["input"]) not in populations["fresh_serial_benchmark"] for row in rows):
            raise ValueError("An isolated gen3 profile must use the same frozen fresh64 inputs")
        source = Path(evidence["run"]["source"]) if evidence["run"].get("source") else None
        if source and not source.is_absolute() and not source.exists():
            source = ROOT / source
        input_only = False
        if source and source.exists():
            source_rows, _ = load_jsonl(source)
            input_only = len(source_rows) == 64 and all("seed" not in row and "targets" not in row and "gen2ReferenceSeed" not in row for row in source_rows)
            if not input_only:
                raise ValueError("An isolated memory/timing profile must load only the frozen64 inputs, not teacher profiles")
        if args.final and not input_only:
            raise ValueError("Final isolated profiles require verified input-only source evidence")
        card["isolatedProfiles"].append({"label": label, "evidence": evidence, "summary": solve_summary(rows),
            "inputOnly64Verified": input_only})
        isolated_rows.append((label, rows, evidence))
        case_rows.extend({**compact_row(row, "isolated:" + label, baseline), "gen3Cohorts": "fresh_serial_benchmark", "newlyFittedRescueInput": False} for row in rows)
    isolated_current = [item for item in isolated_rows if item[2]["run"].get("mode") == "profile-current"
                        or item[0].lower() in ("current", "current_only", "isolated:current")]
    for control_label, current_rows, _ in isolated_current:
        for label, candidate_rows, candidate_evidence in isolated_rows:
            if label == control_label or candidate_evidence["run"].get("mode") == "profile-current":
                continue
            card["pairedIsolatedProfiles"].append({"baseline": control_label, "candidate": label,
                "comparison": paired_isolated_comparison(current_rows, candidate_rows)})
    card["modelHashesByCandidate"] = {label: sorted(values) for label, values in candidate_hashes.items()}
    output = args.output.resolve()
    old_area = (ROOT / "build/neural-generalized").resolve()
    if output == old_area or old_area in output.parents:
        raise ValueError("Gen3 summaries must not overwrite gen2 artifacts")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(card, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    output.with_suffix(".md").write_text(markdown_report(card), encoding="utf-8")
    write_csv(output.with_name(output.stem + "-cases.csv"), case_rows)
    write_csv(output.with_name(output.stem + "-zones.csv"), zone_rows)
    print(json.dumps({"output": str(output), "runs": len(card["runs"]),
        "candidateCohortCounts": {label: {cohort: report["observedUniqueInputs"] for cohort, report in values.items()} for label, values in card["candidateCohorts"].items()},
        "serialRuns": len(card["serialBenchmarks"])}, indent=2))


if __name__ == "__main__":
    main()

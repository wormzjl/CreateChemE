"""Freeze new gen3 holdouts before fitting, leaving every gen2 file unchanged.

252 new inputs: exactly four per stage count 2..64 and two per steam state.
64 fresh benchmark inputs: input-only balanced subset of those 252.
256 recovery comparisons: all 35 prior failures plus 221 remaining gen2 matrix
failures, selected by input strata and fixed hash priority, never gen3 outcomes.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import copy
import hashlib
import json
from pathlib import Path

import generalized_design
from prepare_generalized_evaluation import canonical_input_hash, freeze_json, freeze_rows, load_jsonl, sha, stage_bucket


ROOT = Path(__file__).resolve().parents[2]
REVISION = "gen3-prospective-holdouts-v1"
NEW_POOL_SEED = 202609103
SELECTION_SEED = "gen3-independent-inputs-20260910-v1"


def priority(value):
    return hashlib.sha256((SELECTION_SEED + "|" + generalized_design.canonical(value)).encode("utf-8")).hexdigest()


def structural_stratum(row):
    item = row["input"]
    return stage_bucket(item["stageCount"]), bool(item.get("steamFeeds")), len(item.get("pumparounds", []))


def balanced_subset(rows, count, purpose):
    groups = defaultdict(list)
    for row in rows:
        groups[structural_stratum(row)].append(row)
    for values in groups.values():
        values.sort(key=lambda row: priority([purpose, str(row["id"])]))
    strata = sorted(groups, key=lambda value: priority([purpose, value]))
    selected, depth = [], 0
    if len(rows) < count:
        raise ValueError(f"Not enough eligible {purpose} inputs: {len(rows)} < {count}")
    while len(selected) < count:
        for key in strata:
            if depth < len(groups[key]):
                selected.append(groups[key][depth])
                if len(selected) == count:
                    break
        depth += 1
    return selected


def cohort_row(row, origin, cohorts):
    value = {key: copy.deepcopy(row[key]) for key in ("id", "split", "input", "success", "status", "seed", "equilibriumQualified", "waterQualification") if key in row}
    value["id"] = str(value["id"])
    value["design"] = copy.deepcopy(row.get("design") or {})
    value["design"]["gen3Origin"] = origin
    value["design"]["gen3Cohorts"] = list(cohorts)
    value["design"]["canonicalInputSha256"] = canonical_input_hash(value["input"])
    return value


def new_row(row, cohorts):
    value = cohort_row(row, "new_operating_holdout", cohorts)
    value["id"] = "g3fresh-" + str(row["id"]).removeprefix("gd-")
    value["split"] = "test"
    value["design"]["origin"] = "fresh_gen3_design"
    value["design"]["cohort"] = "fresh_test"
    value["design"]["trainingAllowed"] = False
    value["design"]["previousPoolSplitIgnored"] = row["split"]
    return value


def select_fresh(rows):
    grouped = defaultdict(list)
    for row in rows:
        item = row["input"]
        grouped[item["stageCount"], bool(item["steamFeeds"])].append(row)
    selected = []
    pa_counts, side_counts, pair_counts = Counter(), Counter(), Counter()
    # Stage processing order is fixed by a hash, so small geometries do not
    # systematically claim the globally underrepresented equipment categories.
    for n in sorted(range(2, 65), key=lambda value: priority(["stage-order", value])):
        stage_pas, stage_sides, used_ids = set(), set(), set()
        steam_order = (False, True) if int(priority(["steam-order", n])[:2], 16) % 2 == 0 else (True, False)
        for repeat in range(2):
            for enabled in steam_order:
                candidates = [row for row in grouped[n, enabled] if row["id"] not in used_ids]

                def score(row):
                    pa = len(row["input"]["pumparounds"])
                    side = len(row["input"]["sideDraws"])
                    return (pa in stage_pas, side in stage_sides, pa_counts[pa], side_counts[side],
                            pair_counts[pa, side], priority(["within-stage", n, enabled, repeat, row["id"]]))

                winner = min(candidates, key=score)
                pa, side = len(winner["input"]["pumparounds"]), len(winner["input"]["sideDraws"])
                stage_pas.add(pa)
                stage_sides.add(side)
                used_ids.add(winner["id"])
                pa_counts[pa] += 1
                side_counts[side] += 1
                pair_counts[pa, side] += 1
                selected.append(winner)
    return sorted(selected, key=lambda row: priority(["execution-order", row["id"]]))


def make_holdouts(old_design, teacher_path, gen2_evaluation, prior_path, output):
    old, _ = load_jsonl(old_design)
    teacher, _ = load_jsonl(teacher_path)
    evaluation, _ = load_jsonl(gen2_evaluation)
    prior, _ = load_jsonl(prior_path)
    if len(old) != 2793 or len(teacher) != 2793 or len(evaluation) != 2312 or len(prior) != 35:
        raise ValueError("This prospective revision requires the complete frozen gen2 campaign")
    old_by_id = {str(row["id"]): row for row in old}
    teacher_by_id = {str(row["id"]): row for row in teacher}
    eval_by_id = {str(row["id"]): row for row in evaluation}
    if len(old_by_id) != len(old) or old_by_id.keys() != teacher_by_id.keys() or len(eval_by_id) != len(evaluation):
        raise ValueError("Duplicate or mismatched old campaign IDs")
    baseline = json.loads(generalized_design.DEFAULT_BASELINE.read_text(encoding="utf-8-sig"))["input"]
    candidates, admitted, excluded, oa_report = generalized_design.generate(baseline, NEW_POOL_SEED)
    existing_hashes = {canonical_input_hash(row["input"]) for row in [*old, *prior]}
    fresh_hashes = [canonical_input_hash(row["input"]) for row in candidates]
    if existing_hashes.intersection(fresh_hashes) or len(fresh_hashes) != len(set(fresh_hashes)):
        raise ValueError("New candidate pool overlaps old inputs or itself")
    selected = [new_row(row, ["fresh_test"]) for row in select_fresh(admitted)]
    benchmark = [cohort_row(row, "new_operating_holdout", ["fresh_test", "fresh_serial_benchmark"])
                 for row in balanced_subset(selected, 64, "fresh-serial-benchmark")]
    if Counter(row["input"]["stageCount"] for row in selected) != Counter({n: 4 for n in range(2, 65)}):
        raise AssertionError("Fresh test must have exactly four inputs per every stage count")
    if any(sum(bool(row["input"]["steamFeeds"]) for row in selected if row["input"]["stageCount"] == n) != 2 for n in range(2, 65)):
        raise AssertionError("Each stage count must have two steam-on and two steam-off cases")
    remaining, eligible_rescues = [], []
    for row in teacher:
        if row.get("success") is not False:
            continue
        attempt = eval_by_id.get(str(row["id"]))
        if not attempt or canonical_input_hash(attempt["input"]) != canonical_input_hash(row["input"]):
            raise ValueError("Missing or mismatched gen2 attempt for an original failure")
        if attempt.get("success") is False:
            replay = cohort_row(row, "remaining_gen2_matrix_failure", ["remaining_gen2_failure"])
            replay["design"]["gen2Status"] = attempt.get("status")
            remaining.append(replay)
        elif attempt.get("equilibriumQualified") is True:
            rescue = cohort_row(attempt, "gen2_qualified_rescue", ["rescued_train_replay" if row["split"] == "train" else "rescued_heldout_reference"])
            eligible_rescues.append(rescue)
    prior_remaining = []
    for row in prior:
        attempt = eval_by_id.get(str(row["id"]))
        if not attempt or attempt.get("success") is not False or canonical_input_hash(attempt["input"]) != canonical_input_hash(row["input"]):
            raise ValueError("The prospective historical cohort requires all35 prior failures to remain unrescued")
        replay = cohort_row(row, "remaining_gen2_prior_failure", ["remaining_gen2_failure", "prior_failure"])
        replay["design"]["gen2Status"] = attempt.get("status")
        prior_remaining.append(replay)
    if len(remaining) != 1899 or len(eligible_rescues) != 141:
        raise ValueError("Gen2 outcomes changed since the prospective protocol was agreed")
    recovery = [cohort_row(row, row["design"]["gen3Origin"], [*row["design"]["gen3Cohorts"], "recovery_comparison"])
                for row in [*balanced_subset(remaining, 221, "matrix-failure-comparison"), *prior_remaining]]
    old_test = [cohort_row(row, "previously_reported_geometry_test", ["old_geometry_test"]) for row in teacher if row["split"] == "test"]
    validation = [cohort_row(row, "original_validation", ["validation"]) for row in teacher if row["split"] == "validation"]
    # Improve only the reference available for validation diagnostics; these
    # rescued seeds remain validation data and are never inserted into fitting.
    validation_rescues = {row["id"]: row for row in eligible_rescues if row["split"] == "validation"}
    for row in validation:
        if row["id"] in validation_rescues:
            row["design"]["gen2QualifiedRescueReference"] = True
            row["gen2ReferenceSeed"] = validation_rescues[row["id"]]["seed"]
    comparison = {}
    for row in [*selected, *old_test, *recovery]:
        key = canonical_input_hash(row["input"])
        if key not in comparison:
            comparison[key] = copy.deepcopy(row)
        else:
            merged = comparison[key]["design"]["gen3Cohorts"]
            merged.extend(cohort for cohort in row["design"]["gen3Cohorts"] if cohort not in merged)
    pool_rows = [new_row(row, ["unused_fresh_candidate_pool"]) for row in candidates]
    selected_ids = {row["id"] for row in selected}
    for row in pool_rows:
        row["design"]["selectedForFreshTest"] = row["id"] in selected_ids
    excluded_rows = []
    for row in excluded:
        value = new_row(row, ["fresh_pool_preflight_excluded"])
        value["exclusions"] = row["exclusions"]
        value["status"] = "preflight_excluded"
        excluded_rows.append(value)
    output.mkdir(parents=True, exist_ok=True)
    files = {"candidate-pool.jsonl": pool_rows, "matrix.jsonl": selected, "exclusions.jsonl": excluded_rows,
        "fresh-benchmark.jsonl": benchmark, "recovery-comparison.jsonl": recovery,
        "remaining-gen2-failures.jsonl": sorted([*remaining, *prior_remaining], key=lambda row: row["id"]),
        "validation-replay.jsonl": validation, "old-test-replay.jsonl": old_test,
        "train-rescue-replay.jsonl": [row for row in eligible_rescues if row["split"] == "train"],
        "comparison-source.jsonl": sorted(comparison.values(), key=lambda row: priority(["comparison-order", row["id"]]))}
    for name, rows in files.items():
        freeze_rows(output / name, rows)
    manifest = {"revision": REVISION, "candidatePoolSeed": NEW_POOL_SEED, "selectionSeed": SELECTION_SEED,
        "counts": {name: len(rows) for name, rows in files.items()},
        "oldSourceSha256": {"admittedMatrix": sha(old_design), "teacher": sha(teacher_path), "gen2Evaluation": sha(gen2_evaluation), "priorFailures": sha(prior_path)},
        "oldInputHashesChecked": len(existing_hashes), "oldInputOverlapCount": 0,
        "candidatePoolOrthogonalArray": oa_report["orthogonalArray"],
        "freshSubsetIsOrthogonalArray": False,
        "freshSubsetMethod": "Exactly four per stage count2..64, two per steam state. Deterministic hash stage ordering; sequential input-only score favors distinct PA and draw counts within each stage, then global PA/side/pair balance, then SHA priority. No solver outcome or label is used.",
        "freshCountsByStage": dict(Counter(row["input"]["stageCount"] for row in selected)),
        "freshCountsBySteam": dict(Counter("on" if row["input"]["steamFeeds"] else "off" for row in selected)),
        "freshCountsByPaCount": dict(Counter(len(row["input"]["pumparounds"]) for row in selected)),
        "freshCountsBySideDrawCount": dict(Counter(len(row["input"]["sideDraws"]) for row in selected)),
        "benchmarkStrata": {generalized_design.canonical(key): value for key, value in Counter(structural_stratum(row) for row in benchmark).items()},
        "recoveryComparisonStrata": {generalized_design.canonical(key): value for key, value in Counter(structural_stratum(row) for row in recovery if row["design"]["gen3Origin"] == "remaining_gen2_matrix_failure").items()},
        "rescueLabelsByOriginalSplit": dict(Counter(row["split"] for row in eligible_rescues)),
        "trainingPolicy": "Exactly96 strictly qualified TRAIN rescues may augment483 original qualified training labels.26 validation and19 test rescues never fit. Original folds are immutable. Fresh pool and selected holdouts never fit.",
        "recoveryDefinition": "Original2046 failed cases minus147 native-accepted gen2 rescues leaves1899 matrix failures; add35 prior failures. Six advisory-only rescues are not called failures or used as fitted labels.",
        "primaryComparison": "Fresh252 never-before-solved operating inputs. Old395 geometry-test inputs were previously reported and are regression evidence, not a newly blind test. Recovery256 is outcome-conditioned diagnostic evidence. All candidate selection uses original405 validation only.",
        "benchmarkDefinition": "64 fresh input-only cases balanced by stage bucket, steam and PAcount; all methods use these same64. Historical gen2 old64 benchmark stays a separate cached reference.",
        "fileSha256": {name: sha(output / name) for name in files}}
    freeze_json(output / "design.json", manifest)
    print(json.dumps({"output": str(output), "counts": manifest["counts"], "freshSteam": manifest["freshCountsBySteam"],
        "freshPa": manifest["freshCountsByPaCount"], "freshSideDraw": manifest["freshCountsBySideDrawCount"],
        "benchmarkOccupiedStrata": len(manifest["benchmarkStrata"]), "oldInputOverlapCount": 0}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-design", type=Path, default=ROOT / "build/neural-generalized/design/matrix.jsonl")
    parser.add_argument("--teacher", type=Path, default=ROOT / "build/neural-generalized/v2/cases.jsonl")
    parser.add_argument("--gen2-evaluation", type=Path, default=ROOT / "build/neural-generalized/evaluation/evaluation.jsonl")
    parser.add_argument("--prior", type=Path, default=ROOT / "build/neural-generalized/evaluation-inputs/prior-failures.jsonl")
    parser.add_argument("--output", type=Path, default=ROOT / "build/neural-gen3/fresh-design")
    args = parser.parse_args()
    make_holdouts(args.old_design, args.teacher, args.gen2_evaluation, args.prior, args.output)


if __name__ == "__main__":
    main()

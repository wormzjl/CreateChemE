"""Freeze generalized evaluation selections, then attach teacher outcomes.

freeze uses design inputs only for held-out and benchmark selection. finalize
requires a complete teacher journal; it does not change any frozen selection.
Run validation-source.jsonl for model selection first. Test and retry sources are
separate so they can remain sealed until the model and its policies are frozen.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import copy
import hashlib
import json
from pathlib import Path

from generalized_design import canonical


SELECTION_REVISION = "generalized-holdout-and-prior-retry-v1"
SELECTION_SEED = "generalized-test-benchmark-20260910"
ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical_input_hash(input_value):
    """Canonicalize unordered authored collections and equivalent JSON numbers."""
    item = copy.deepcopy(input_value)
    item["specifications"] = sorted(item["specifications"], key=lambda s: next(iter(s)))
    item["sideDraws"] = sorted(item.get("sideDraws", []), key=lambda d: d["trayNumber"])
    item["steamFeeds"] = sorted(item.get("steamFeeds", []), key=lambda s: s["stageNumber"])
    item["pumparounds"] = sorted(item.get("pumparounds", []), key=lambda p: (p["returnTray"], p["drawTray"]))

    def numbers(value):
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return float(value)
        if isinstance(value, list):
            return [numbers(v) for v in value]
        if isinstance(value, dict):
            return {k: numbers(v) for k, v in value.items()}
        return value

    return hashlib.sha256(canonical(numbers(item)).encode("utf-8")).hexdigest()


def load_jsonl(path, allow_partial=False):
    """Ignore only an incomplete final write when explicitly reading a live journal."""
    path = Path(path)
    if not path.exists():
        if allow_partial:
            return [], {"exists": False, "rows": 0, "incompleteFinalLine": False}
        raise FileNotFoundError(path)
    text = path.read_text(encoding="utf-8-sig")
    lines = text.splitlines()
    rows = []
    ignored = False
    for index, line in enumerate(lines):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            if allow_partial and index == len(lines) - 1 and not text.endswith("\n"):
                ignored = True
            else:
                raise
    return rows, {"exists": True, "rows": len(rows), "incompleteFinalLine": ignored}


def write_frozen(path, text):
    path = Path(path)
    data = text.encode("utf-8")
    if path.exists():
        if path.read_bytes() == data:
            return
        raise FileExistsError(f"Refusing to change frozen selection: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as handle:
        handle.write(data)


def freeze_rows(path, rows):
    write_frozen(path, "".join(canonical(row) + "\n" for row in rows))


def freeze_json(path, value):
    write_frozen(path, json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False, allow_nan=False) + "\n")


def stage_bucket(n):
    return "02-08" if n <= 8 else "09-16" if n <= 16 else "17-32" if n <= 32 else "33-48" if n <= 48 else "49-64"


def benchmark_stratum(row):
    item = row["input"]
    return (stage_bucket(item["stageCount"]), bool(item.get("steamFeeds")), len(item.get("pumparounds", [])))


def priority(value):
    return hashlib.sha256((SELECTION_SEED + "|" + canonical(value)).encode("utf-8")).hexdigest()


def select_benchmark(rows, count=64):
    """Equal round-robin allocation across input-only strata, with hashed priority."""
    groups = defaultdict(list)
    for row in rows:
        if row["split"] == "test":
            groups[benchmark_stratum(row)].append(row)
    for group in groups.values():
        group.sort(key=lambda row: priority(str(row["id"])))
    strata = sorted(groups, key=priority)
    selected = []
    depth = 0
    target = min(count, sum(len(group) for group in groups.values()))
    while len(selected) < target:
        for stratum in strata:
            if depth < len(groups[stratum]):
                selected.append(groups[stratum][depth])
                if len(selected) == target:
                    break
        depth += 1
    return selected


def request_row(row, origin, cohorts):
    result = {key: copy.deepcopy(row[key]) for key in ("id", "split", "input", "success", "status", "seed", "equilibriumQualified", "waterQualification") if key in row}
    result["id"] = str(result["id"])
    result["design"] = copy.deepcopy(row.get("design") or {})
    result["design"]["evaluationOrigin"] = origin
    result["design"]["evaluationCohorts"] = list(cohorts)
    result["design"]["canonicalInputSha256"] = canonical_input_hash(row["input"])
    return result


def freeze_design(design_directory, output, prior_paths, legacy_paths, benchmark_count):
    matrix_path = design_directory / "matrix.jsonl"
    rows, _ = load_jsonl(matrix_path)
    basis = rows[0]["input"]["componentBasis"]["componentIds"]
    heldouts = [request_row(row, {"kind": "original_matrix"}, [row["split"]]) for row in rows if row["split"] in ("validation", "test")]
    benchmark = [request_row(row, {"kind": "original_matrix"}, ["test", "serial_benchmark"]) for row in select_benchmark(rows, benchmark_count)]
    prior, unsupported, duplicates = [], [], []
    seen = {}
    prior_evidence = []
    for path in [*prior_paths, *legacy_paths]:
        history, _ = load_jsonl(path)
        failures = [row for row in history if row.get("success") is False]
        prior_evidence.append({"path": str(path), "sha256": sha(path), "rows": len(history), "failedRows": len(failures)})
        for row in failures:
            origin = {"kind": "prior_failure", "journal": str(path), "priorCaseId": row["id"],
                      "priorSplit": row.get("split", "unspecified"), "priorFailure": row.get("failure", row.get("teacher"))}
            candidate = request_row(row, origin, ["prior_failure"])
            candidate["status"] = row.get("status", "PRIOR_NONCONVERGENCE")
            candidate["id"] = "prior-" + path.parent.name + "-" + str(row["id"])
            key = candidate["design"]["canonicalInputSha256"]
            if row["input"]["componentBasis"]["componentIds"] != basis:
                unsupported.append({**candidate, "status": "UNSUPPORTED_COMPONENT_BASIS",
                    "unsupportedEvidence": {"expectedComponentIds": basis, "actualComponentIds": row["input"]["componentBasis"]["componentIds"],
                       "reason": "The new initializer has a fixed registered 20-component axis; a 19-component input is not silently padded or relabelled."}})
            elif key in seen:
                duplicates.append({"duplicate": origin, "retainedId": seen[key], "canonicalInputSha256": key})
            else:
                seen[key] = candidate["id"]
                prior.append(candidate)
    output.mkdir(parents=True, exist_ok=True)
    files = {
        "evaluation-design.jsonl": heldouts,
        "validation-design.jsonl": [row for row in heldouts if row["split"] == "validation"],
        "test-design.jsonl": [row for row in heldouts if row["split"] == "test"],
        "benchmark-design.jsonl": benchmark,
        "prior-failures.jsonl": sorted(prior, key=lambda row: row["id"]),
        "unsupported-prior-failures.jsonl": unsupported,
    }
    for name, values in files.items():
        freeze_rows(output / name, values)
    manifest = {"revision": SELECTION_REVISION, "matrixPath": str(matrix_path), "matrixSha256": sha(matrix_path),
        "selectionSeed": SELECTION_SEED, "heldoutSelection": "Every admitted validation and test case, independently of every solver outcome.",
        "benchmarkSelection": "Input-only strata (tray bucket, steam enabled, PA count); one per stratum then round-robin extras; deterministic SHA-256 ID priority. Test split only.",
        "benchmarkStageBuckets": ["02-08", "09-16", "17-32", "33-48", "49-64"],
        "benchmarkStratumCounts": {canonical(key): value for key, value in Counter(benchmark_stratum(row) for row in benchmark).items()},
        "counts": {name: len(values) for name, values in files.items()}, "priorJournals": prior_evidence,
        "priorDuplicates": duplicates, "fileSha256": {name: sha(output / name) for name in files},
        "sequence": "Freeze design selections before fitting. Run validation-source only for model selection; freeze model hash/policies, then run test-source, failure-source and benchmark-source.",
        "trainingPolicy": "Only equilibrium-qualified original training cases produce labels. No failure, advisory-only, validation, test, or prior-failure target is fitted."}
    freeze_json(output / "design-freeze.json", manifest)
    print(canonical({"phase": "freeze", "counts": manifest["counts"], "benchmarkStrata": len(manifest["benchmarkStratumCounts"])}))


def finalize_sources(design_directory, teacher_path, output):
    design_rows, _ = load_jsonl(design_directory / "matrix.jsonl")
    teacher, _ = load_jsonl(teacher_path)
    expected = {str(row["id"]): row for row in design_rows}
    actual = {str(row["id"]): row for row in teacher}
    if len(actual) != len(teacher):
        raise ValueError("Teacher journal contains duplicate case IDs")
    if expected.keys() != actual.keys():
        raise ValueError(f"Teacher journal must be complete: missing={len(expected.keys()-actual.keys())}, unexpected={len(actual.keys()-expected.keys())}")
    for case_id, row in actual.items():
        if row.get("success") not in (True, False) or canonical_input_hash(row["input"]) != canonical_input_hash(expected[case_id]["input"]) or row["split"] != expected[case_id]["split"]:
            raise ValueError("Teacher input/split/outcome mismatch: " + case_id)
    freeze = json.loads((output / "design-freeze.json").read_text(encoding="utf-8"))
    if freeze["matrixSha256"] != sha(design_directory / "matrix.jsonl"):
        raise ValueError("Matrix changed after design freeze")
    for name, digest in freeze["fileSha256"].items():
        if sha(output / name) != digest:
            raise ValueError("Frozen source changed: " + name)

    def attach(row):
        source = actual[str(row["id"])]
        result = request_row(source, row["design"]["evaluationOrigin"], row["design"]["evaluationCohorts"])
        if not source["success"]:
            result["design"]["evaluationCohorts"].append("matrix_failure")
        return result

    validation, _ = load_jsonl(output / "validation-design.jsonl")
    test, _ = load_jsonl(output / "test-design.jsonl")
    benchmark, _ = load_jsonl(output / "benchmark-design.jsonl")
    prior, _ = load_jsonl(output / "prior-failures.jsonl")
    matrix_failures = [request_row(row, {"kind": "original_matrix"}, ["matrix_failure", *([row["split"]] if row["split"] in ("validation", "test") else [])])
                       for row in teacher if row["success"] is False]
    files = {"validation-source.jsonl": [attach(row) for row in validation],
        "test-source.jsonl": [attach(row) for row in test],
        "benchmark-source.jsonl": [attach(row) for row in benchmark],
        "matrix-failures-source.jsonl": sorted(matrix_failures, key=lambda row: row["id"])}
    failures = {row["design"]["canonicalInputSha256"]: row for row in matrix_failures}
    duplicate_prior = []
    for row in prior:
        key = row["design"]["canonicalInputSha256"]
        if key in failures:
            duplicate_prior.append({"retainedId": failures[key]["id"], "priorId": row["id"], "canonicalInputSha256": key})
            failures[key]["design"].setdefault("alsoPriorFailureIds", []).append(row["id"])
        else:
            failures[key] = row
    files["failure-source.jsonl"] = sorted(failures.values(), key=lambda row: row["id"])
    # Combined replay is available, but validation-only evaluation remains a separate explicit file.
    combined = {row["design"]["canonicalInputSha256"]: row for name in ("validation-source.jsonl", "test-source.jsonl") for row in files[name]}
    for key, row in failures.items():
        combined.setdefault(key, row)
    files["evaluation-source.jsonl"] = sorted(combined.values(), key=lambda row: row["id"])
    for name, values in files.items():
        freeze_rows(output / name, values)
    training_hashes = {canonical_input_hash(row["input"]) for row in teacher if row["split"] == "train" and row["success"] and row.get("equilibriumQualified") is True}
    forbidden_overlap = [row["id"] for row in [*files["validation-source.jsonl"], *files["test-source.jsonl"], *files["failure-source.jsonl"]]
                         if canonical_input_hash(row["input"]) in training_hashes]
    if forbidden_overlap:
        raise ValueError("Evaluation source overlaps equilibrium-qualified training inputs: " + str(forbidden_overlap))
    manifest = {"revision": SELECTION_REVISION, "teacherPath": str(teacher_path), "teacherSha256": sha(teacher_path),
        "teacherRows": len(teacher), "designFreezeSha256": sha(output / "design-freeze.json"),
        "counts": {name: len(rows) for name, rows in files.items()}, "priorMatrixDuplicateInputs": duplicate_prior,
        "qualifiedTrainingInputCount": len(training_hashes), "qualifiedTrainingOverlapCount": 0,
        "fileSha256": {name: sha(output / name) for name in files},
        "failureDefinition": "Original success=false only; accepted advisory states are not renamed failures or fitted as labels.",
        "sequence": "Run validation-source for model selection. Freeze weights, artifact hash and routing policy before test-source, failure-source, and benchmark-source."}
    freeze_json(output / "sources-freeze.json", manifest)
    print(canonical({"phase": "finalize", "counts": manifest["counts"], "qualifiedTrainingInputCount": len(training_hashes)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("freeze", "finalize"))
    parser.add_argument("--design", type=Path, default=ROOT / "build/neural-generalized/design")
    parser.add_argument("--output", type=Path, default=ROOT / "build/neural-generalized/evaluation-inputs")
    parser.add_argument("--teacher", type=Path, default=ROOT / "build/neural-generalized/v2/cases.jsonl")
    parser.add_argument("--prior", type=Path, action="append", help="Prior 20-component journal; repeatable")
    parser.add_argument("--legacy", type=Path, action="append", help="Prior journal that may have an unsupported axis; repeatable")
    parser.add_argument("--benchmark-count", type=int, default=64)
    args = parser.parse_args()
    if args.benchmark_count < 1:
        parser.error("benchmark-count must be positive")
    if args.phase == "freeze":
        priors = args.prior or [ROOT / "build/neural-methane/v1/cases.jsonl", ROOT / "build/neural-methane/wet-v3/cases.jsonl"]
        legacy = args.legacy or [ROOT / "build/neural-mvp/tjl19-pilot/cases.jsonl"]
        freeze_design(args.design, args.output, priors, legacy, args.benchmark_count)
    else:
        finalize_sources(args.design, args.teacher, args.output)


if __name__ == "__main__":
    main()

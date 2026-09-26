"""Attach completed CURRENT references to new gen3 evaluation source files.

Only reference outcomes are added. Authored inputs, IDs, folds, and frozen cohort
tags are preserved. No native solver or neural candidate is called. Gen2 files
and the frozen prospective design are read-only inputs.
"""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import json
from pathlib import Path

from prepare_generalized_evaluation import canonical_input_hash, freeze_json, freeze_rows, load_jsonl, sha


ROOT = Path(__file__).resolve().parents[2]
REFERENCE_FIELDS = ("success", "status", "seed", "equilibriumQualified", "waterQualification", "wetTrayCount",
                    "waterEvidence", "diagnostics", "failure", "failureClass", "formulationRevision", "streams")


def attach_reference(authored, reference, origin):
    expected = canonical_input_hash(authored["input"])
    if canonical_input_hash(reference["input"]) != expected:
        raise ValueError("Reference changed the authored input: " + str(authored["id"]))
    if reference.get("split") != authored.get("split"):
        raise ValueError("Reference changed the original fold: " + str(authored["id"]))
    if str(reference.get("id")) != str(authored.get("id")):
        raise ValueError("Reference belongs to another authored case ID")
    if reference.get("success") not in (True, False):
        raise ValueError("Reference has no completed success/failure outcome")
    if reference.get("success") is True and not reference.get("seed"):
        raise ValueError("Successful CURRENT reference omitted its accepted profile")
    if reference.get("seed") and canonical_input_hash(reference["seed"]["input"]) != expected:
        raise ValueError("Reference seed belongs to another physical request")
    result = copy.deepcopy(authored)
    for key in REFERENCE_FIELDS:
        result.pop(key, None)
        if key in reference:
            result[key] = copy.deepcopy(reference[key])
    result["status"] = reference.get("status", "ACCEPTED" if reference["success"] else "HISTORICAL_NONCONVERGENCE")
    result["equilibriumQualified"] = reference.get("equilibriumQualified") is True
    result["design"]["gen3Reference"] = {"origin": origin, "referenceCaseId": str(reference["id"]),
        "nativeSuccess": reference["success"], "equilibriumQualified": result["equilibriumQualified"],
        "waterQualification": reference.get("waterQualification"), "canonicalInputSha256": expected,
        "use": "Post-freeze diagnostic reference only; never supplied to candidate prediction or fitted as a new label."}
    # Preserve the exact authored representation, not just semantic equivalence.
    if result["input"] != authored["input"] or result["id"] != authored["id"] or result["split"] != authored["split"]:
        raise AssertionError("Attachment mutated authored request identity")
    if result["design"].get("gen3Cohorts") != authored["design"].get("gen3Cohorts"):
        raise AssertionError("Attachment mutated frozen cohort membership")
    return result


def baseline_row(source):
    row = copy.deepcopy(source)
    # Different historical journals had different concurrency/deadlines, and five
    # prior failures used the old wet-continuation protocol. Keep outcome evidence
    # without fabricating one new 877-request CURRENT timing experiment.
    for key in ("ms", "cold_ms", "cpuMillis", "allocatedBytes", "heapUsedBeforeBytes", "heapUsedAfterBytes"):
        row.pop(key, None)
    row["sourceSuccess"] = None
    row["sourceStatus"] = None
    row["referenceOnly"] = True
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--design", type=Path, default=ROOT / "build/neural-gen3/fresh-design")
    parser.add_argument("--fresh-current", type=Path, default=ROOT / "build/neural-gen3/fresh-current/cases.jsonl")
    parser.add_argument("--original-current", type=Path, default=ROOT / "build/neural-generalized/v2/cases.jsonl")
    parser.add_argument("--output", type=Path, default=ROOT / "build/neural-gen3/evaluation-inputs")
    args = parser.parse_args()
    design = json.loads((args.design / "design.json").read_text(encoding="utf-8-sig"))
    for name, digest in design["fileSha256"].items():
        if sha(args.design / name) != digest:
            raise ValueError("Frozen gen3 design changed: " + name)
    fresh_run_path = args.fresh_current.parent / "run.json"
    fresh_run = json.loads(fresh_run_path.read_text(encoding="utf-8-sig"))
    if fresh_run.get("completed") != 252 or fresh_run.get("caseCount") != 252 or fresh_run.get("mode") != "generate":
        raise ValueError("Fresh CURRENT_ONLY teacher must have completed all252 requests")
    if fresh_run.get("sourceSha256") != design["fileSha256"]["matrix.jsonl"]:
        raise ValueError("Fresh teacher did not run the frozen fresh252 matrix")
    if sha(args.original_current) != design["oldSourceSha256"]["teacher"]:
        raise ValueError("Original CURRENT journal changed after prospective design freeze")
    fresh, _ = load_jsonl(args.fresh_current)
    original, _ = load_jsonl(args.original_current)
    fresh_by_id = {str(row["id"]): row for row in fresh}
    original_by_id = {str(row["id"]): row for row in original}
    fresh_inputs, _ = load_jsonl(args.design / "matrix.jsonl")
    if len(fresh) != 252 or len(fresh_by_id) != 252 or fresh_by_id.keys() != {str(row["id"]) for row in fresh_inputs}:
        raise ValueError("Fresh CURRENT journal IDs are incomplete or duplicated")
    if len(original) != 2793 or len(original_by_id) != 2793:
        raise ValueError("Original CURRENT journal is incomplete or duplicated")

    def reference_for(row):
        case_id = str(row["id"])
        if case_id in fresh_by_id:
            return fresh_by_id[case_id], "fresh252_CURRENT_ONLY"
        if case_id in original_by_id:
            return original_by_id[case_id], "cached_original_CURRENT_ONLY"
        if (row.get("design") or {}).get("gen3Origin") == "remaining_gen2_prior_failure":
            journal = ((row.get("design") or {}).get("evaluationOrigin") or {}).get("journal", "")
            return row, "historical_wet_continuation_failure" if "wet-v3" in journal else "historical_prior_CURRENT_failure"
        raise ValueError("No authorized CURRENT/prior reference exists for: " + case_id)

    comparison, _ = load_jsonl(args.design / "comparison-source.jsonl")
    benchmark, _ = load_jsonl(args.design / "fresh-benchmark.jsonl")
    enriched = [attach_reference(row, *reference_for(row)) for row in comparison]
    enriched_benchmark = [attach_reference(row, *reference_for(row)) for row in benchmark]
    if len(enriched) != 877 or len(enriched_benchmark) != 64:
        raise ValueError("Unexpected frozen comparison or benchmark population")
    baseline = [baseline_row(row) for row in enriched]
    args.output.mkdir(parents=True, exist_ok=True)
    comparison_path = args.output / "comparison-source.jsonl"
    benchmark_path = args.output / "fresh-benchmark-source.jsonl"
    baseline_dir = args.output / "current-reference"
    baseline_dir.mkdir(parents=True, exist_ok=True)
    freeze_rows(comparison_path, enriched)
    freeze_rows(benchmark_path, enriched_benchmark)
    freeze_rows(baseline_dir / "evaluation.jsonl", baseline)
    origins = dict(Counter(row["design"]["gen3Reference"]["origin"] for row in enriched))
    metadata = {"mode": "cached_CURRENT_and_historical_prior_reference", "referenceOnly": True,
        "modelId": "CURRENT_reference", "source": str(comparison_path.resolve()), "sourceSha256": sha(comparison_path),
        "caseCount": 877, "completed": 877, "accepted": sum(row["success"] for row in baseline),
        "workers": 0, "elapsedSeconds": None, "referenceOrigins": origins,
        "timingScope": "No new877-case solver run. Cached physical outcomes only; mixed-run timing was intentionally omitted.",
        "qualificationScope": "Fresh252 and original matrix references use CURRENT_ONLY. Historical prior failures retain their original protocol, including five wet-continuation failures; those five are not relabelled CURRENT_ONLY.",
        "sourceJournalsSha256": {"freshCurrent": sha(args.fresh_current), "originalCurrent": sha(args.original_current)},
        "memoryScope": "No process-memory claim for assembled reference outcomes."}
    freeze_json(baseline_dir / "run.json", metadata)
    attachment = {"revision": "gen3-current-reference-attachment-v1", "designSha256": sha(args.design / "design.json"),
        "counts": {"comparison": len(enriched), "freshBenchmark": len(enriched_benchmark), "currentReference": len(baseline)},
        "referenceOrigins": origins,
        "sourceSha256": {"freshCurrent": sha(args.fresh_current), "freshRun": sha(fresh_run_path), "originalCurrent": sha(args.original_current)},
        "fileSha256": {"comparison-source.jsonl": sha(comparison_path), "fresh-benchmark-source.jsonl": sha(benchmark_path),
            "current-reference/evaluation.jsonl": sha(baseline_dir / "evaluation.jsonl"), "current-reference/run.json": sha(baseline_dir / "run.json")},
        "integrity": "Every input, ID, fold and frozen gen3 cohort tag preserved; current-reference seeds are post-freeze diagnostics only. No gen2 or frozen-design file was written."}
    freeze_json(args.output / "attachment.json", attachment)
    print(json.dumps({"output": str(args.output), "counts": attachment["counts"], "referenceOrigins": origins,
        "comparisonSource": str(comparison_path), "freshBenchmarkSource": str(benchmark_path),
        "currentReferenceJournal": str(baseline_dir / "evaluation.jsonl")}, indent=2))


if __name__ == "__main__":
    main()

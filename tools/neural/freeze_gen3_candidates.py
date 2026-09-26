"""Freeze the finite generation 3 comparison using original validation only."""
from datetime import datetime, timezone
import json
from pathlib import Path

from prepare_generalized_evaluation import canonical_input_hash, load_jsonl, sha
from summarize_generalized import water_qualified

ROOT = Path(__file__).resolve().parents[2]
AREA = ROOT / "build/neural-gen3"
CANDIDATES = {
    "gen2": (ROOT / ".neural-cache/gen2-1a4a01d/model.json", ROOT / ".neural-cache/gen2-1a4a01d/study/validation-v1", "frozen neural control"),
    "gen3-mlp": (AREA / "mlp-v1/general-model.json", AREA / "validation-mlp", "neural"),
    "gen3-factorized": (AREA / "factorized-v1/factorized-model.json", AREA / "validation-factorized", "neural"),
    "nearest-k1": (AREA / "nearest-k1/model.json", AREA / "validation-nearest-k1", "profile transfer"),
    "nearest-k3": (AREA / "nearest-k3/model.json", AREA / "validation-nearest-k3", "profile transfer"),
}


def main():
    output = AREA / "selection.json"
    if output.exists():
        previous = json.loads(output.read_text())
        for label, (model, directory, _) in CANDIDATES.items():
            saved = previous["candidates"][label]
            if sha(model) != saved["modelSha256"] or sha(directory / "evaluation.jsonl") != saved["validationSha256"]:
                raise ValueError("Frozen candidate changed: " + label)
        print("Existing selection and model hashes verified: " + str(output))
        return
    if any((AREA / ("comparison-" + label) / "evaluation.jsonl").exists() for label in CANDIDATES):
        raise ValueError("Selection must be frozen before candidate comparison begins")
    expected, _ = load_jsonl(AREA / "fresh-design/validation-replay.jsonl")
    expected = {canonical_input_hash(row["input"]) for row in expected}
    candidates = {}
    for label, (model, directory, kind) in CANDIDATES.items():
        rows, _ = load_jsonl(directory / "evaluation.jsonl")
        metadata = json.loads((directory / "run.json").read_text())
        if len(rows) != 405 or {canonical_input_hash(row["input"]) for row in rows} != expected:
            raise ValueError("Validation population mismatch: " + label)
        if metadata["completed"] != 405 or metadata["modelSha256"] != sha(model):
            raise ValueError("Incomplete or mismatched validation: " + label)
        if metadata["workers"] != 10 or metadata["deadlineMillis"] != 30000 or metadata["neuralBudgetMillis"] != 10000:
            raise ValueError("Validation budget mismatch: " + label)
        # Gen2's journal predates the explicit metadata field; its frozen probe
        # and published selection card document the same 16-iteration default.
        if metadata.get("neuralMaximumIterations", 16) != 16:
            raise ValueError("Validation iteration mismatch: " + label)
        candidates[label] = {"kind": kind, "modelPath": str(model.relative_to(ROOT)), "modelSha256": sha(model),
            "modelBytes": model.stat().st_size, "validationPath": str(directory.relative_to(ROOT)),
            "validationSha256": sha(directory / "evaluation.jsonl"), "cases": len(rows),
            "nativeAccepted": sum(row.get("success") is True for row in rows),
            "strictQualified": sum(water_qualified(row) for row in rows)}
    rank = lambda label: (-candidates[label]["strictQualified"], candidates[label]["modelBytes"], label)
    selected_neural = min((label for label in candidates if candidates[label]["kind"] == "neural"), key=rank)
    selected_transfer = min((label for label in candidates if candidates[label]["kind"] == "profile transfer"), key=rank)
    selected_all = min((label for label in candidates if label != "gen2"), key=rank)
    record = {"revision": "gen3-validation-selection-1", "frozenUtc": datetime.now(timezone.utc).isoformat(),
        "selectionRule": "Maximize strict-qualified native validation solves among declared new candidates; ties use smaller artifact then lexical id. Select a neural model and a transfer model independently; no fresh-test outcomes are consulted.",
        "candidates": candidates, "selectedNeural": selected_neural, "selectedTransfer": selected_transfer,
        "selectedOverall": selected_all, "comparisonCandidateLabels": list(candidates),
        "fullRecoveryLabels": [selected_neural, selected_transfer], "fullRecoveryInputs": 1934,
        "serialCandidateLabels": ["current", *candidates], "serialInputs": 64,
        "fallbackBenchmarkLabels": [selected_neural, selected_transfer],
        "parallelBudget": {"workers": 10, "parentDeadlineMillis": 30000, "candidateMillis": 10000, "candidateIterations": 16},
        "serialBudget": {"workers": 1, "parentDeadlineMillis": 30000, "candidateMillis": 2000, "candidateIterations": 16},
        "freshDesignSha256": sha(AREA / "fresh-design/design.json"), "trainingDataSha256": sha(AREA / "data/cases.jsonl"),
        "gen2CacheManifestSha256": sha(ROOT / ".neural-cache/gen2-1a4a01d/manifest.json"),
        "runtimePolicy": "Expose selected Gen3 neural model as an explicit experimental family; preserve Gen2 and local-expert default. Profile transfer remains an offline comparison candidate.",
        "testTuningAllowed": False}
    output.write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record, indent=2))


if __name__ == "__main__":
    main()

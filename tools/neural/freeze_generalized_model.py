"""Freeze a validation-selected general model and its correction policy before held-out evaluation."""
import hashlib
import json
from pathlib import Path
import shutil
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "build/neural-generalized"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    output = ROOT / "tools/neural/generalized-selection.json"
    if output.exists():
        raise FileExistsError("Selection is already frozen; author a new revision explicitly")
    candidates = []
    for folder, model_folder in (("validation-v1", "model-v1"), ("validation-v2", "model-v2"), ("validation-v1-32", "model-v1")):
        journal = BASE / folder / "evaluation.jsonl"
        rows = [json.loads(line) for line in journal.read_text().splitlines()]
        run = json.loads((journal.parent / "run.json").read_text())
        training = json.loads((BASE / model_folder / "general-training.json").read_text())
        model_file = BASE / model_folder / "general-model.json"
        if len(rows) != 405 or run["completed"] != 405 or any(row["split"] != "validation" for row in rows):
            raise ValueError("Model selection requires the complete frozen validation cohort")
        if run["modelSha256"] != sha(model_file) or training["model_sha256"] != sha(model_file):
            raise ValueError("Candidate artifact changed since validation")
        candidates.append({"validationDirectory": folder, "modelDirectory": model_folder,
            "modelId": run["modelId"], "modelSha256": sha(model_file), "validationJournalSha256": sha(journal),
            "validationInputs": len(rows), "accepted": sum(row["success"] for row in rows),
            "equilibriumQualified": sum(row.get("equilibriumQualified", False) for row in rows),
            "classicalSuccessesRetained": sum(row["success"] and row["sourceSuccess"] for row in rows),
            "classicalFailuresRescued": sum(row["success"] and not row["sourceSuccess"] for row in rows),
            "weightParameters": training["weight_parameters"], "maximumCorrectionIterations": run.get("neuralMaximumIterations", 16),
            "validationWorkers": run["workers"], "validationNeuralBudgetMillis": run["neuralBudgetMillis"]})
    selected = max(candidates, key=lambda c: (c["equilibriumQualified"], c["accepted"], -c["weightParameters"], -c["maximumCorrectionIterations"]))
    source = BASE / selected["modelDirectory"] / "general-model.json"
    destination = ROOT / "src/main/resources/data/createcheme/neural/v3-general-stage.json"
    if destination.exists():
        raise FileExistsError("Refusing to replace an existing promoted model")
    shutil.copyfile(source, destination)
    source_paths = ["src/main/java/com/wormzjl/createcheme/science/column/v3/" + name for name in (
        "V3GeneralNeuralFeatures.java", "V3GeneralNeuralInitializer.java", "V3ColumnCalculator.java",
        "V3SimultaneousColumnSolver.java", "V3InitializationOptions.java", "V3NeuralModels.java")]
    document = {"revision": "generalized-selection-v1", "frozenUtc": datetime.now(timezone.utc).isoformat(),
        "selectionRule": "Highest equilibrium-qualified validation count, then native accepted count, then fewer parameters/iterations",
        "candidates": candidates, "selected": selected, "bundledResource": str(destination.relative_to(ROOT)).replace("\\", "/"),
        "bundledSha256": sha(destination), "datasetSha256": sha(BASE / "v2/cases.jsonl"),
        "designSha256": sha(BASE / "design/design.json"), "evaluationSourcesFreezeSha256": sha(BASE / "evaluation-inputs/sources-freeze.json"),
        "sourceSha256": {path: sha(ROOT / path) for path in source_paths},
        "finalPolicy": {"parallelEvaluationWorkers": 10, "parallelNeuralBudgetMillis": 10000, "serialNeuralBudgetMillis": 2000,
            "maximumCorrectionIterations": selected["maximumCorrectionIterations"], "parentDeadlineMillis": 30000,
            "trainingLabelPolicy": "equilibrium-only", "runtimeWarningPolicy": "existing native advisory policy retained",
            "runtimeMode": "LNN_FIRST with existing experts and classical fallback"},
        "testOrRetryOutcomesUsedForSelection": False,
        "knownValidationLimits": "No wet training labels; large profile-flow errors and failures on tall columns. The general model is an optional initializer, not a standalone replacement for classical initialization."}
    output.write_text(json.dumps(document, indent=2) + "\n")
    print(json.dumps(selected, indent=2))


if __name__ == "__main__":
    main()

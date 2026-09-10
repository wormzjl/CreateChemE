"""Verify real Java model inference against the frozen Python export on selected training rows."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import train_generalized as training


def prediction(model, inp):
    g = training.global_features(inp)
    low, high = np.asarray(model["globalMin"]), np.asarray(model["globalMax"])
    slack = 1e-9 * np.maximum(1, np.maximum(np.abs(low), np.abs(high)))
    if np.any(g < low-slack) or np.any(g > high+slack) or not np.isfinite(g).all(): return None
    if not model["minimumStages"] <= inp["stageCount"] <= model["maximumStages"]: return None
    if "designConstraints" in model and training.design_constraint_rejection(inp, model["designConstraints"]): return None
    normalized = (g-model["globalMean"]) / model["globalScale"]
    if "coverageGuard" in model:
        guard = model["coverageGuard"]
        if np.min(np.mean((np.asarray(guard["centers"])-normalized)**2, axis=1)) > guard["maximumNearestMeanSquare"]: return None
    logits = training.forward(training.import_layers(model["branchLayers"]), normalized[None, :])[-1][0]
    logits[~np.asarray(model["branchesSeen"])] = -np.inf
    if any(spec.get("ratio", 0) > 0 for spec in inp["specifications"]): logits[2] = -np.inf
    if not np.isfinite(logits).any(): return None
    branch = training.BRANCHES[int(np.argmax(logits))]
    x = training.node_features(inp, branch)
    y = training.forward(training.import_layers(model["nodeLayers"]), (x-model["nodeMean"])/model["nodeScale"])[-1]
    y = y * model["outputScale"] + model["outputMean"]
    try: temperatures, liquid, vapor, water, wet = training.decode(inp, y, branch)
    except ValueError: return None
    return {"branch": branch, "propertyRevision": model["propertyRevision"], "temperatures": temperatures,
            "liquid": liquid, "vapor": vapor, "freeWater": water, "wetTrays": wet}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model_directory", type=Path)
    parser.add_argument("java_output", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    model_path = args.model_directory / "general-model.json"
    model = json.loads(model_path.read_text())
    actual = json.loads(args.java_output.read_text())
    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
    if digest != actual["modelSha256"]: raise ValueError("Model changed between Java and Python parity checks")
    fixture = {str(r["id"]): r for r in json.loads((args.model_directory / "general-feature-parity.json").read_text())}
    absolute_tolerance, relative_tolerance = 1e-8, 1e-8
    counts, maxima = {}, {}

    def compare(field, java, python):
        a, b = np.asarray(java, dtype=float), np.asarray(python, dtype=float)
        if a.shape != b.shape: raise AssertionError(f"{field} shape mismatch: {a.shape} vs {b.shape}")
        np.testing.assert_allclose(a, b, rtol=relative_tolerance, atol=absolute_tolerance, err_msg=field)
        counts[field] = counts.get(field, 0) + a.size
        maxima[field] = max(maxima.get(field, 0), float(np.max(np.abs(a-b))))

    for row in actual["rows"]:
        if row["split"] != "train": raise ValueError("Parity check may use training rows only")
        reference = fixture[str(row["id"])]
        inp = row["input"]
        compare("global_features", row["global"], training.global_features(inp))
        compare("node_features", row["nodes"], training.node_features(inp, row["teacherBranch"]))
        compare("teacher_targets", row["targets"], reference["targets"])
        expected = prediction(model, inp)
        if row["predictionSupported"] != (expected is not None): raise AssertionError("Java/Python prediction support disagrees")
        if expected is None: raise AssertionError("Selected training parity row has no raw prediction to compare")
        java = row["prediction"]
        for field in ("branch", "propertyRevision"):
            if java[field] != expected[field]: raise AssertionError(f"Prediction {field} mismatch")
        np.testing.assert_array_equal(java["wetTrays"], expected["wetTrays"], err_msg="wet mask")
        for field in ("temperatures", "liquid", "vapor", "freeWater"):
            compare("prediction_"+field, java[field], expected[field])
    if len(actual["rows"]) != len(fixture): raise AssertionError("Parity fixture cases missing from Java result")
    report = {"passed": True, "model_sha256": digest, "training_columns": len(actual["rows"]),
              "absolute_tolerance": absolute_tolerance, "relative_tolerance": relative_tolerance,
              "values_compared": counts, "maximum_absolute_errors": maxima,
              "wet_masks_and_branches_exact": True, "test_targets_used": False}
    if args.output: args.output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__": main()

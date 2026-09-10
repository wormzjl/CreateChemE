"""Compare Java's real factorized predictions with the frozen Python export on training fixture rows."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import train_gen3_factorized as factorized


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path); parser.add_argument("java_output", type=Path); parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    path = args.directory/"factorized-model.json"
    model = json.loads(path.read_text()); java = json.loads(args.java_output.read_text())
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != java["modelSha256"]: raise ValueError("Different model artifacts in Java/Python parity check")
    fixture = {str(r["id"]): r for r in json.loads((args.directory/"factorized-feature-parity.json").read_text())}
    counts, maxima = {}, {}

    def compare(name, a, b):
        a, b = np.asarray(a, dtype=float), np.asarray(b, dtype=float)
        if a.shape != b.shape: raise AssertionError(name+" shape mismatch")
        np.testing.assert_allclose(a, b, atol=1e-8, rtol=1e-8, err_msg=name)
        counts[name] = counts.get(name, 0)+a.size
        maxima[name] = max(maxima.get(name, 0), float(np.max(np.abs(a-b))))

    if len(java["rows"]) != len(fixture): raise ValueError("Parity training rows missing")
    for row in java["rows"]:
        expected = fixture[str(row["id"])]
        if row["split"] != "train" or expected["split"] != "train": raise ValueError("Training fixture only")
        compare("global", row["global"], factorized.base.global_features(row["input"]))
        compare("nodes", row["nodes"], factorized.base.node_features(row["input"], row["teacherBranch"]))
        compare("targets", row["targets"], factorized.targets(expected)[0])
        prediction = factorized.predict(model, row["input"])
        if row["prediction"]["branch"] != prediction["branch"]: raise AssertionError("Condenser branch mismatch")
        np.testing.assert_array_equal(row["prediction"]["wetTrays"], prediction["wetTrays"])
        for field in ("temperatures", "liquid", "vapor", "freeWater"):
            compare("prediction_"+field, row["prediction"][field], prediction[field])
    report = {"passed": True, "model_sha256": digest, "training_columns": len(fixture), "absolute_tolerance": 1e-8,
              "relative_tolerance": 1e-8, "values_compared": counts, "maximum_absolute_errors": maxima,
              "branch_and_wet_mask_exact": True, "test_targets_used": False}
    if args.output: args.output.write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__": main()

"""Train the bounded dense/PCA baseline from accepted V3 profiles. NumPy only; no test-set fitting."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

import numpy as np


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--epochs", type=int, default=5000)
    parser.add_argument("--model-id", default="tjl19-temperature-pilot-v1")
    parser.add_argument("--hidden-width", type=int, default=32)
    parser.add_argument("--pca-rank", type=int, default=8)
    parser.add_argument("--label-policy", choices=["current-solver-accepted", "equilibrium-only"], default="current-solver-accepted")
    parser.add_argument("--design-bounds", action="store_true")
    parser.add_argument("--coverage-guard", action="store_true")
    args = parser.parse_args()
    rows = [json.loads(line) for line in (args.directory / "cases.jsonl").read_text().splitlines()]
    if args.epochs < 25 or not 1 <= args.hidden_width <= 256 or not 1 <= args.pca_rank <= 128:
        raise ValueError("Invalid training dimensions or epoch budget")
    accepted = [r for r in rows if r["success"] and
                (args.label_policy == "current-solver-accepted" or r.get("equilibriumQualified") is True)]
    train = [r for r in accepted if r["split"] == "train"]
    validation = [r for r in accepted if r["split"] == "validation"]
    if len(train) < 12 or len(validation) < 3:
        raise ValueError("Insufficient accepted training/validation cases")
    first = train[0]
    if any(r["seed"]["branch"] != first["seed"]["branch"] for r in train + validation):
        raise ValueError("This baseline requires one validated condenser branch")
    x = np.array([r["features"] for r in train], dtype=np.float64)
    y = np.array([r["targets"] for r in train], dtype=np.float64)
    xm, xs = x.mean(0), x.std(0)
    xs = np.where(xs > 1e-8, xs, 1.0)
    ym, ys = y.mean(0), np.maximum(y.std(0), 0.02)
    stride = 2 * len(first["input"]["componentBasis"]["componentIds"]) + 3
    ys[::stride] = np.maximum(ys[::stride], 0.05)
    ys[stride - 1::stride] = 1.0
    z = (y - ym) / ys
    _, _, vt = np.linalg.svd(z, full_matrices=False)
    rank = min(args.pca_rank, len(train) - 1)
    basis = vt[:rank]
    target = z @ basis.T
    xn = (x - xm) / xs
    xv = (np.array([r["features"] for r in validation]) - xm) / xs
    yv = (np.array([r["targets"] for r in validation]) - ym) / ys
    rng = np.random.default_rng(42)
    w1 = rng.normal(0, 0.15, (x.shape[1], args.hidden_width))
    b1 = np.zeros(args.hidden_width)
    w2 = rng.normal(0, 0.1, (args.hidden_width, rank))
    b2 = np.zeros(rank)
    params = [w1, b1, w2, b2]
    moments = [np.zeros_like(p) for p in params]
    variances = [np.zeros_like(p) for p in params]
    best, best_loss, best_epoch = None, float("inf"), 0
    for epoch in range(1, args.epochs + 1):
        h = np.tanh(xn @ w1 + b1)
        error = h @ w2 + b2 - target
        d = 2 * error / error.size
        dh = (d @ w2.T) * (1 - h * h)
        gradients = [xn.T @ dh, dh.sum(0), h.T @ d, d.sum(0)]
        for p, g, m, v in zip(params, gradients, moments, variances):
            g += 1e-6 * p
            m *= 0.9; m += 0.1 * g
            v *= 0.999; v += 0.001 * g * g
            p -= 0.005 * (m / (1 - 0.9 ** epoch)) / (np.sqrt(v / (1 - 0.999 ** epoch)) + 1e-8)
        if epoch % 25 == 0:
            predicted = (np.tanh(xv @ w1 + b1) @ w2 + b2) @ basis
            loss = float(np.mean((predicted - yv) ** 2))
            if loss < best_loss:
                best_loss, best_epoch, best = loss, epoch, copy.deepcopy(params)
    w1, b1, w2, b2 = best
    model = {
        "featureRevision": "v3-physical-dense-1",
        "modelId": args.model_id,
        "trainingLabelPolicy": args.label_policy,
        "packageId": first["input"]["packageId"],
        "propertyRevision": first["seed"]["propertyRevision"],
        "formulationRevision": first["formulationRevision"],
        "components": first["input"]["componentBasis"]["componentIds"],
        "stages": first["input"]["stageCount"], "branch": first["seed"]["branch"],
        "inputMean": xm.tolist(), "inputScale": xs.tolist(),
        "inputMin": x.min(0).tolist(), "inputMax": x.max(0).tolist(),
        "outputMean": ym.tolist(), "outputScale": ys.tolist(),
        "layers": [
            {"weights": w1.T.tolist(), "bias": b1.tolist(), "activation": "tanh"},
            {"weights": (basis.T @ w2.T).tolist(), "bias": (basis.T @ b2).tolist(), "activation": "linear"},
        ],
    }
    design = json.loads((args.directory / "design.json").read_text()) if args.design_bounds else None
    if design:
        model["inputMin"], model["inputMax"] = design["inputMin"], design["inputMax"]
    if args.coverage_guard:
        # Learn correlations from training inputs only. Threshold selection uses validation inputs;
        # held-out test inputs, outcomes and targets never participate.
        _, singular, directions = np.linalg.svd(xn, full_matrices=False)
        input_rank = max(1, int(np.sum(singular > singular[0] * 1e-5)))
        projection = directions[:input_rank]
        residual = xv - (xv @ projection.T) @ projection
        off_manifold = np.mean(residual**2, axis=1)
        nearest = np.min(np.mean((xv[:, None, :] - xn[None, :, :])**2, axis=2), axis=1)
        model["coverageGuard"] = {
            "projection": projection.tolist(), "centers": xn.tolist(),
            "maximumProjectionMeanSquare": max(1e-10, float(np.max(off_manifold)) * 2),
            "maximumNearestMeanSquare": max(0.05, float(np.max(nearest)) * 1.5),
        }
    (args.directory / "model.json").write_text(json.dumps(model, separators=(",", ":"), allow_nan=False))
    report = {
        "seed": 42, "epochs": args.epochs, "selected_epoch": best_epoch,
        "accepted": len(accepted), "attempted": len(rows), "train": len(train), "validation": len(validation),
        "test": sum(r["split"] == "test" for r in rows), "validation_normalized_mse": best_loss,
        "pca_rank": rank, "hidden_width": args.hidden_width,
        "training_normalized_pca_reconstruction_mse": float(np.mean((z - target @ basis) ** 2)),
        "data_sha256": hashlib.sha256((args.directory / "cases.jsonl").read_bytes()).hexdigest(),
        "coverage": design["coverage"] if design else "Single TJL19 40-tray temperature slice; all other physical features fixed. Not refinery-wide.",
        "split": design["split"] if design else "Preassigned withheld temperature blocks; related trays never split into independent samples.",
        "model_id": args.model_id, "label_policy": args.label_policy,
        "training_water_qualification": {grade: sum(r.get("waterQualification", "UNCLASSIFIED") == grade for r in train)
                                           for grade in sorted({r.get("waterQualification", "UNCLASSIFIED") for r in train})},
        "rejected_label_count": sum(r["success"] for r in rows) - len(accepted),
        "test_used_for_training_or_selection": False,
    }
    (args.directory / "training.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

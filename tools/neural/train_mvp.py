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
    args = parser.parse_args()
    rows = [json.loads(line) for line in (args.directory / "cases.jsonl").read_text().splitlines()]
    accepted = [r for r in rows if r["success"]]
    train = [r for r in accepted if r["split"] == "train"]
    validation = [r for r in accepted if r["split"] == "validation"]
    if len(train) < 12 or len(validation) < 3:
        raise ValueError("Insufficient accepted training/validation cases")
    first = train[0]
    if any(r["seed"]["branch"] != first["seed"]["branch"] for r in accepted):
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
    rank = min(8, len(train) - 1)
    basis = vt[:rank]
    target = z @ basis.T
    xn = (x - xm) / xs
    xv = (np.array([r["features"] for r in validation]) - xm) / xs
    yv = (np.array([r["targets"] for r in validation]) - ym) / ys
    rng = np.random.default_rng(42)
    w1 = rng.normal(0, 0.15, (x.shape[1], 32))
    b1 = np.zeros(32)
    w2 = rng.normal(0, 0.1, (32, rank))
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
        "modelId": "tjl19-temperature-pilot-v1",
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
    (args.directory / "model.json").write_text(json.dumps(model, separators=(",", ":"), allow_nan=False))
    report = {
        "seed": 42, "epochs": args.epochs, "selected_epoch": best_epoch,
        "accepted": len(accepted), "attempted": len(rows), "train": len(train), "validation": len(validation),
        "test": sum(r["split"] == "test" for r in rows), "validation_normalized_mse": best_loss,
        "pca_rank": rank, "hidden_width": 32,
        "training_normalized_pca_reconstruction_mse": float(np.mean((z - target @ basis) ** 2)),
        "data_sha256": hashlib.sha256((args.directory / "cases.jsonl").read_bytes()).hexdigest(),
        "coverage": "Single TJL19 40-tray temperature slice; all other physical features fixed. Not refinery-wide.",
        "split": "Preassigned withheld temperature blocks; related trays never split into independent samples.",
        "test_used_for_training_or_selection": False,
    }
    (args.directory / "training.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

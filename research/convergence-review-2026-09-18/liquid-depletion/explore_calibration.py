"""Scratch exploration: how well can the converged tray liquid at a draw tray be predicted
from the request alone, and what does that buy for the depletion screen?

Read-only. Prints to stdout; writes nothing.
"""
from __future__ import annotations

import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze as A  # noqa: E402


def draw_rows(records):
    """One row per (request, draw tray)."""
    rows = []
    for r in records:
        inp = r["input"]
        liquid = r.get("liquidTotals")
        for tray, rate in inp["draws"]:
            above = sum(d for t, d in inp["draws"] if t < tray)
            rows.append({
                "rec": r, "inp": inp, "tray": tray, "rate": rate, "above": above,
                "L": (liquid[tray] if liquid and tray < len(liquid) else None),
            })
    return rows


def features(row):
    inp = row["inp"]
    R = inp["reflux"]
    k = R / (R + 1.0)
    q = inp["reboilerWatts"] or 0.0
    cool_t = A.total_cooling_watts(inp)
    cool_a = A.cooling_above_watts(inp, row["tray"])
    heat_t = A.total_heating_watts(inp)
    F = inp["feedTotal"]
    S = inp["steamTotal"]
    below = 1.0 if row["tray"] >= inp["feedStageNumber"] else 0.0
    return np.array([k * q, k * F, k * S, k * cool_t, cool_a, k * heat_t, below * F, 1.0])


FEATNAMES = ["k*Q", "k*F", "k*S", "k*cool_total", "cool_above", "k*heat_total", "F below feed", "1"]


def main():
    data = {n: A.load(n) for n in A.JOURNALS}
    allrec = [r for n in A.JOURNALS for r in data[n] if r["input"]["drawCount"] > 0]
    succ = [r for r in allrec if r["success"] and r.get("liquidTotals")]
    rows = [r for r in draw_rows(succ) if r["L"] is not None and r["L"] > 0]
    X = np.array([features(r) for r in rows])
    y = np.array([r["L"] + r["above"] for r in rows])  # = V[T+1] - D
    print("rows", len(rows))

    coef, *_ = np.linalg.lstsq(X, y, rcond=None)
    pred = X @ coef
    print("linear OLS R2", 1 - ((y - pred) ** 2).sum() / ((y - y.mean()) ** 2).sum())
    for n, c in zip(FEATNAMES, coef):
        print(f"   {n:14s} {c:12.6g}")

    # log-space fit
    logy = np.log(y)
    Xl = np.array([[math.log(max(v, 1e-9)) if i < 6 else v for i, v in enumerate(f[:6])] +
                   [f[6] > 0, 1.0] for f in X], dtype=float)
    cl, *_ = np.linalg.lstsq(Xl, logy, rcond=None)
    pl = Xl @ cl
    print("log OLS R2", 1 - ((logy - pl) ** 2).sum() / ((logy - logy.mean()) ** 2).sum())

    # what actually matters: the ratio draw / L_hat versus the measured withdrawal
    def scores(L_hat_fn, label):
        pos, neg, nearf = [], [], []
        for r in allrec:
            w = r["withdrawal"]
            if w is None:
                continue
            worst = 0.0
            for row in draw_rows([r]):
                lh = L_hat_fn(row)
                v = row["rate"] / lh if lh > 0 else math.inf
                worst = max(worst, v)
            if w >= 1.0:
                pos.append(worst)
            elif w >= 0.8 and not r["success"]:
                nearf.append(worst)
            elif r["success"]:
                neg.append(worst)
        pos, neg, nearf = np.array(pos), np.array(neg), np.array(nearf)
        finite_neg = neg[np.isfinite(neg)]
        worst_solved = finite_neg.max() if len(finite_neg) else math.nan
        # recall at zero false positives on successes
        r0 = float((pos > worst_solved).mean())
        rn0 = float((nearf > worst_solved).mean()) if len(nearf) else math.nan
        # recall at the threshold giving <=0.5% FP
        t05 = float(np.quantile(neg[np.isfinite(neg)], 0.995))
        r05 = float((pos >= t05).mean())
        allv = np.concatenate([pos, neg])
        ranks = np.empty(len(allv))
        ranks[allv.argsort()] = np.arange(1, len(allv) + 1)
        auc = (ranks[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))
        print(f"{label:34s} AUC={auc:.3f} worstSolved={worst_solved:.4g} "
              f"recall@0FP={r0:.3f} nearRecall@0FP={rn0:.3f} thr@0.5%FP={t05:.4g} recall={r05:.3f} "
              f"(hard n={len(pos)}, succ n={len(neg)}, nearfail n={len(nearf)})")
        return worst_solved

    scores(lambda row: max(1e-9, float(features(row) @ coef) - row["above"]), "linear OLS L_hat")
    scores(lambda row: math.exp(float(np.array(
        [math.log(max(v, 1e-9)) for v in features(row)[:6]] +
        [float(features(row)[6] > 0), 1.0]) @ cl)) - row["above"], "log OLS L_hat")

    # hand structural forms
    for lam in (30e3, 60e3, 120e3, 240e3, 1e9):
        def fn(row, lam=lam):
            inp = row["inp"]
            R = inp["reflux"]
            v1 = max(0.0, (inp["reboilerWatts"] or 0.0) / lam + inp["feedTotal"] + inp["steamTotal"]
                     - A.total_cooling_watts(inp) / lam)
            d = v1 / (R + 1.0)
            return max(1e-9, R * d + A.cooling_above_watts(inp, row["tray"]) / lam - row["above"])
        scores(fn, f"structural lam={lam/1e3:g} kJ/mol vf=1")

    # the plain material bound used by rho, expressed per tray
    def rho_like(row):
        inp = row["inp"]
        R = inp["reflux"]
        dmax = max(0.0, inp["feedTotal"] + inp["steamTotal"] - inp["drawTotal"])
        return max(1e-9, R * dmax + inp["steamTotal"]
                   + A.cooling_above_watts(inp, row["tray"]) / 30e3
                   + (inp["feedTotal"] if row["tray"] >= inp["feedStageNumber"] else 0.0))
    scores(rho_like, "rho supply (per tray)")

    # simple ratios
    for label, fn in [
        ("draw / (R*Dmax)", lambda row: max(1e-9, row["inp"]["reflux"] * max(
            0.0, row["inp"]["feedTotal"] + row["inp"]["steamTotal"] - row["inp"]["drawTotal"]))),
        ("draw / F", lambda row: row["inp"]["feedTotal"]),
        ("draw / (F+S)", lambda row: row["inp"]["feedTotal"] + row["inp"]["steamTotal"]),
    ]:
        scores(fn, label)


if __name__ == "__main__":
    main()

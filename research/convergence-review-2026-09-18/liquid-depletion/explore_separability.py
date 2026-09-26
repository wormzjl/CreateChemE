"""Scratch exploration: an upper bound on how well ANY request-only statistic can separate the
liquid-depletion family from solvable requests, via a fitted logistic model over many request
features (in-sample AUC is an optimistic bound) plus a leave-one-journal-out check.

Read-only. Prints to stdout; writes nothing.
"""
from __future__ import annotations

import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze as A  # noqa: E402


def feature_row(r):
    inp = r["input"]
    R = inp["reflux"]
    N = inp["stageCount"]
    F = inp["feedTotal"]
    S = inp["steamTotal"]
    Q = inp["reboilerWatts"] or 0.0
    cool = A.total_cooling_watts(inp)
    heat = A.total_heating_watts(inp)
    trays = [t for t, _ in inp["draws"]]
    rates = [d for _, d in inp["draws"]]
    deepest = max(trays)
    shallowest = min(trays)
    rho, rho_tray = A.rho_screen(inp)
    cool_deep = A.cooling_above_watts(inp, deepest)
    return [
        math.log(R + 1e-6),
        math.log(F),
        math.log(Q + 1e4),
        math.log(S + 1.0),
        math.log(cool + 1e4),
        math.log(heat + 1e4),
        len(trays),
        N,
        inp["feedStageNumber"] / N,
        deepest / N,
        shallowest / N,
        sum(rates) / F,
        max(rates) / F,
        rho,
        math.log(cool_deep + 1e4),
        Q / max(1.0, cool),
        inp["condenserKelvin"],
        inp["feedTemperatureKelvin"],
        math.log(inp["topPressurePascal"]),
        inp["stagePressureDropPascal"] * N / inp["topPressurePascal"],
        sum(rates) / max(1e-9, R * max(0.0, F + S - sum(rates))),
        1.0 if deepest >= inp["feedStageNumber"] else 0.0,
        1.0,
    ]


NAMES = ["logR", "logF", "logQ", "logS", "logCool", "logHeat", "nDraws", "N", "feedStage/N",
         "deepest/N", "shallowest/N", "draws/F", "maxDraw/F", "rho", "logCoolAboveDeepest",
         "Q/cool", "condT", "feedT", "logPtop", "dP*N/Ptop", "draws/(R*Dmax)", "deepestBelowFeed",
         "bias"]


def logistic_fit(X, y, iters=60, ridge=1e-3):
    w = np.zeros(X.shape[1])
    for _ in range(iters):
        z = X @ w
        p = 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))
        g = X.T @ (p - y) + ridge * w
        s = p * (1 - p) + 1e-9
        H = X.T @ (X * s[:, None]) + ridge * np.eye(X.shape[1])
        try:
            step = np.linalg.solve(H, g)
        except np.linalg.LinAlgError:
            break
        w -= step
        if np.max(np.abs(step)) < 1e-9:
            break
    return w


def auc(scores, labels):
    pos = scores[labels == 1]
    neg = scores[labels == 0]
    allv = np.concatenate([pos, neg])
    ranks = np.empty(len(allv))
    ranks[allv.argsort()] = np.arange(1, len(allv) + 1)
    return (ranks[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))


def recall_at_fp(scores, labels, cap):
    neg = np.sort(scores[labels == 0])
    k = int(math.floor((1 - cap) * len(neg)))
    thr = neg[min(k, len(neg) - 1)]
    pos = scores[labels == 1]
    fp = float((neg >= thr).mean())
    return float((pos >= thr).mean()), thr, fp


def main():
    data = {n: A.load(n) for n in A.JOURNALS}
    rows, labels, journals, kinds = [], [], [], []
    for n in A.JOURNALS:
        for r in data[n]:
            if r["input"]["drawCount"] == 0 or r["withdrawal"] is None:
                continue
            if r["success"]:
                lab, kind = 0, "success"
            elif r["withdrawal"] >= 1.0:
                lab, kind = 1, "hard"
            elif r["withdrawal"] >= 0.8:
                lab, kind = 1, "nearfail"
            else:
                continue  # other failures: excluded from the two-class comparison
            rows.append(feature_row(r))
            labels.append(lab)
            journals.append(n)
            kinds.append(kind)
    X = np.array(rows)
    y = np.array(labels, dtype=float)
    journals = np.array(journals)
    kinds = np.array(kinds)
    mu, sd = X.mean(0), X.std(0) + 1e-9
    sd[-1] = 1.0
    mu[-1] = 0.0
    Xn = (X - mu) / sd
    print("n =", len(y), " family =", int(y.sum()), " successes =", int((1 - y).sum()))

    w = logistic_fit(Xn, y)
    s = Xn @ w
    print(f"in-sample logistic AUC = {auc(s, y):.3f}")
    for cap in (0.0, 0.005, 0.02, 0.05):
        r, thr, fp = recall_at_fp(s, y, cap)
        print(f"  in-sample recall at FP<={cap:.3%}: {r:.3f} (actual FP {fp:.3%})")
    order = np.argsort(-np.abs(w))
    print("  strongest standardized coefficients:")
    for i in order[:10]:
        print(f"    {NAMES[i]:22s} {w[i]:+.3f}")

    # leave-one-journal-out
    for held in A.JOURNALS:
        tr = journals != held
        te = ~tr
        if y[te].sum() < 5:
            continue
        wt = logistic_fit(Xn[tr], y[tr])
        st = Xn[te] @ wt
        r0, thr0, fp0 = recall_at_fp(st, y[te], 0.005)
        print(f"  hold out {held:10s} AUC={auc(st, y[te]):.3f} recall@0.5%FP={r0:.3f}")

    # the same, restricted to the hard band only
    mask = (kinds != "nearfail")
    Xh, yh = Xn[mask], y[mask]
    wh = logistic_fit(Xh, yh)
    sh = Xh @ wh
    print(f"hard-only in-sample AUC = {auc(sh, yh):.3f}")
    for cap in (0.0, 0.005, 0.02):
        r, thr, fp = recall_at_fp(sh, yh, cap)
        print(f"  hard-only in-sample recall at FP<={cap:.3%}: {r:.3f}")

    # and restricted to the converged-but-audit-rejected subset (the only requests with
    # solver-side proof that no positive-downflow steady state was found)
    proven, succ = [], []
    for n in A.JOURNALS:
        for r in data[n]:
            if r["input"]["drawCount"] == 0 or r["withdrawal"] is None:
                continue
            if r["status"] == "ACCEPTANCE_AUDIT_FAILURE" and r["withdrawal"] >= 1.0:
                proven.append(feature_row(r))
            elif r["success"]:
                succ.append(feature_row(r))
    Xp = (np.array(proven + succ) - mu) / sd
    yp = np.array([1.0] * len(proven) + [0.0] * len(succ))
    wp = logistic_fit(Xp, yp)
    sp = Xp @ wp
    print(f"converged-audit-rejected vs success: n_pos={len(proven)} AUC={auc(sp, yp):.3f}")
    for cap in (0.0, 0.005, 0.02):
        r, thr, fp = recall_at_fp(sp, yp, cap)
        print(f"  recall at FP<={cap:.3%}: {r:.3f}")

    # single-feature recall at zero false positives, for the record
    print("\nsingle-feature screening (hard band vs successes):")
    hardmask = kinds != "nearfail"
    for i, name in enumerate(NAMES[:-1]):
        v = X[hardmask][:, i]
        lab = y[hardmask]
        a = auc(v, lab)
        a = max(a, 1 - a)
        sign = 1.0 if auc(v, lab) >= 0.5 else -1.0
        r0, thr0, fp0 = recall_at_fp(sign * v, lab, 0.0)
        r5, thr5, fp5 = recall_at_fp(sign * v, lab, 0.005)
        print(f"  {name:22s} AUC={a:.3f} recall@0FP={r0:.3f} recall@0.5%FP={r5:.3f}")


if __name__ == "__main__":
    main()

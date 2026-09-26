"""Scratch exploration: search a parameterised family of request-only depletion proxies and a few
compact logistic models, scoring each by recall at a capped false-positive rate on solvable
requests, with leave-one-journal-out validation.

Read-only. Prints to stdout; writes nothing.
"""
from __future__ import annotations

import itertools
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze as A  # noqa: E402
import thermo as T  # noqa: E402
from explore_flash import cache_flash, recall_at_fp, tie_aware_auc  # noqa: E402


def supply(inp, tray, *, use_lambda, use_cooling_credit, subtract_cooling, feed_liquid,
           steam_credit, reflux_weight):
    R = inp["reflux"]
    lam = inp["lambdaTop"] if use_lambda == "flash" else float(use_lambda)
    q = inp["reboilerWatts"] or 0.0
    cool = A.total_cooling_watts(inp)
    F = inp["feedTotal"]
    S = inp["steamTotal"]
    vf = inp["vfFeed"]
    v_avail = q / lam + vf * F + (S if steam_credit else 0.0)
    if subtract_cooling:
        v_avail -= cool / lam
    v_avail = max(0.0, v_avail)
    if reflux_weight == "R/(R+1)":
        liq = R / (R + 1.0) * v_avail
    elif reflux_weight == "R":
        liq = R * v_avail
    else:
        liq = v_avail
    if use_cooling_credit:
        liq += A.cooling_above_watts(inp, tray) / lam
    if feed_liquid and tray >= inp["feedStageNumber"]:
        liq += (1.0 - vf) * F
    return liq


def proxy(inp, **kw):
    worst, cum = 0.0, 0.0
    for tray in range(1, inp["stageCount"] + 1):
        cum += sum(rate for t, rate in inp["draws"] if t == tray)
        if cum <= 0:
            continue
        s = supply(inp, tray, **kw)
        v = cum / s if s > 0 else math.inf
        worst = max(worst, v)
    return worst


def main():
    data = {n: A.load(n) for n in A.JOURNALS}
    allrec = [r for n in A.JOURNALS for r in data[n] if r["input"]["drawCount"] > 0]
    cache_flash(allrec)

    labelled = []
    for r in allrec:
        if r["withdrawal"] is None:
            continue
        if r["success"]:
            labelled.append((r, 0))
        elif r["withdrawal"] >= 1.0:
            labelled.append((r, 1))
        elif r["withdrawal"] >= 0.8:
            labelled.append((r, 2))
    recs = [r for r, _ in labelled]
    lab = np.array([l for _, l in labelled])
    jour = np.array([r["journal"] for r in recs])

    hard_mask = (lab != 2)
    y_hard = (lab[hard_mask] == 1).astype(int)
    y_fam = (lab != 0).astype(int)

    grid = list(itertools.product(
        ["flash", 30_000.0, 60_000.0],       # lambda
        [True, False],                        # cooling credit above the tray
        [True, False],                        # subtract total cooling from the vapour budget
        [True, False],                        # feed liquid credit below the feed tray
        [True, False],                        # steam credit in the vapour budget
        ["R/(R+1)", "R", "1"],               # how vapour turns into internal liquid
    ))
    results = []
    for lam, cc, sc, fl, st, rw in grid:
        kw = dict(use_lambda=lam, use_cooling_credit=cc, subtract_cooling=sc,
                  feed_liquid=fl, steam_credit=st, reflux_weight=rw)
        vals = np.array([proxy(r["input"], **kw) for r in recs])
        vals = np.where(np.isfinite(vals), vals, 1e12)
        a = tie_aware_auc(vals[hard_mask], y_hard)
        r5 = recall_at_fp(vals[hard_mask], y_hard, 0.005)
        r2 = recall_at_fp(vals, y_fam, 0.02)
        results.append((r5[0], r2[0], a, kw, r5[1], r2[1], vals))
    results.sort(key=lambda t: (-t[0], -t[2]))
    print("top parameterised proxies by hard recall at FP<=0.5%:")
    for rec5, rec2, a, kw, t5, t2, _ in results[:12]:
        print(f"  r@0.5%={rec5:.3f} (t={t5:.4g})  familyR@2%={rec2:.3f} (t={t2:.4g})  AUC={a:.3f}  "
              f"lam={kw['use_lambda']} coolCredit={kw['use_cooling_credit']} "
              f"subCool={kw['subtract_cooling']} feedLiq={kw['feed_liquid']} "
              f"steam={kw['steam_credit']} w={kw['reflux_weight']}")

    best = results[0]
    vals = best[6]
    print("\nleave-one-journal-out for the best parameterised proxy:")
    for held in A.JOURNALS:
        m = (jour == held) & hard_mask
        if m.sum() == 0:
            continue
        yv = (lab[m] == 1).astype(int)
        r5 = recall_at_fp(vals[m], yv, 0.005)
        print(f"  {held:10s} n_hard={int(yv.sum())} AUC={tie_aware_auc(vals[m], yv):.3f} "
              f"r@0.5%FP={r5[0]:.3f} (t={r5[1]:.4g})  worstSolved={vals[m][yv == 0].max():.4g}")

    # the simple headline ratio
    simple = np.array([r["input"]["drawTotal"] /
                       max(1e-9, r["input"]["vfFeed"] * r["input"]["feedTotal"]) for r in recs])
    print("\ndraws/(vf*F):")
    for held in A.JOURNALS:
        m = (jour == held) & hard_mask
        yv = (lab[m] == 1).astype(int)
        r5 = recall_at_fp(simple[m], yv, 0.005)
        print(f"  {held:10s} AUC={tie_aware_auc(simple[m], yv):.3f} r@0.5%FP={r5[0]:.3f} "
              f"(t={r5[1]:.4g}) worstSolved={simple[m][yv == 0].max():.4g}")

    # compact logistic models
    def featureset(r, names):
        inp = r["input"]
        R = inp["reflux"]
        F = inp["feedTotal"]
        S = inp["steamTotal"]
        Q = inp["reboilerWatts"] or 0.0
        cool = A.total_cooling_watts(inp)
        lam = inp["lambdaTop"]
        vf = inp["vfFeed"]
        vav = max(1e-9, Q / lam + vf * F + S - cool / lam)
        book = {
            "rho": A.rho_screen(inp)[0],
            "drawsOverVfF": inp["drawTotal"] / max(1e-9, vf * F),
            "drawsOverLavail": inp["drawTotal"] / (R / (R + 1.0) * vav),
            "logR": math.log(R + 1e-6),
            "vfFeed": vf,
            "lightCond": inp["lightCond"],
            "nDraws": inp["drawCount"],
            "coolOverQ": cool / max(1.0, Q + 1e4),
            "logQ": math.log(Q + 1e4),
            "condT": inp["condenserKelvin"],
            "feedT": inp["feedTemperatureKelvin"],
            "drawsOverF": inp["drawTotal"] / F,
            "steamOverF": S / F,
        }
        return [book[n] for n in names] + [1.0]

    def logistic_fit(X, y, iters=80, ridge=1e-2):
        w = np.zeros(X.shape[1])
        for _ in range(iters):
            p = 1.0 / (1.0 + np.exp(-np.clip(X @ w, -30, 30)))
            g = X.T @ (p - y) + ridge * w
            s = p * (1 - p) + 1e-9
            H = X.T @ (X * s[:, None]) + ridge * np.eye(X.shape[1])
            try:
                step = np.linalg.solve(H, g)
            except np.linalg.LinAlgError:
                break
            w -= step
            if np.max(np.abs(step)) < 1e-10:
                break
        return w

    sets = [
        ["rho", "drawsOverVfF"],
        ["rho", "drawsOverVfF", "condT"],
        ["rho", "drawsOverVfF", "condT", "vfFeed"],
        ["rho", "drawsOverLavail", "condT", "vfFeed", "logR"],
        ["rho", "drawsOverVfF", "drawsOverLavail", "condT", "vfFeed", "logR", "nDraws"],
        ["rho", "drawsOverVfF", "drawsOverLavail", "condT", "feedT", "vfFeed", "logR", "nDraws",
         "lightCond", "coolOverQ", "logQ", "drawsOverF", "steamOverF"],
    ]
    print("\ncompact logistic models (family = hard + near-depletion failures):")
    for names in sets:
        X = np.array([featureset(r, names) for r in recs])
        mu, sd = X.mean(0), X.std(0) + 1e-9
        mu[-1], sd[-1] = 0.0, 1.0
        Xn = (X - mu) / sd
        w = logistic_fit(Xn, y_fam.astype(float))
        s_in = Xn @ w
        a_in = tie_aware_auc(s_in, y_fam)
        r_in = recall_at_fp(s_in, y_fam, 0.005)[0]
        oos_scores = np.zeros(len(recs))
        for held in A.JOURNALS:
            tr = jour != held
            wt = logistic_fit(Xn[tr], y_fam[tr].astype(float))
            oos_scores[~tr] = Xn[~tr] @ wt
        a_oos = tie_aware_auc(oos_scores, y_fam)
        r_oos5 = recall_at_fp(oos_scores, y_fam, 0.005)
        r_oos2 = recall_at_fp(oos_scores, y_fam, 0.02)
        print(f"  {len(names)}f {','.join(names)[:58]:58s} inAUC={a_in:.3f} r@0.5%={r_in:.3f} | "
              f"oosAUC={a_oos:.3f} r@0.5%={r_oos5[0]:.3f} r@2%={r_oos2[0]:.3f}")


if __name__ == "__main__":
    main()

"""Scratch exploration: do flash-derived request-only quantities (real feed vapour fraction, real
latent heat, condenser light-end fraction) sharpen the depletion screen?

Read-only. Prints to stdout; writes nothing.
"""
from __future__ import annotations

import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import analyze as A  # noqa: E402
import thermo as T  # noqa: E402


def tie_aware_auc(scores, labels):
    scores = np.asarray(scores, dtype=float)
    labels = np.asarray(labels)
    order = scores.argsort()
    ranks = np.empty(len(scores), dtype=float)
    s = scores[order]
    i = 0
    while i < len(s):
        j = i
        while j + 1 < len(s) and s[j + 1] == s[i]:
            j += 1
        ranks[order[i:j + 1]] = 0.5 * (i + j) + 1.0
        i = j + 1
    npos = int((labels == 1).sum())
    nneg = int((labels == 0).sum())
    if npos == 0 or nneg == 0:
        return float("nan")
    return (ranks[labels == 1].sum() - npos * (npos + 1) / 2) / (npos * nneg)


def recall_at_fp(scores, labels, cap):
    """Highest recall achievable with a >= threshold whose false-positive rate is at most cap."""
    scores = np.asarray(scores, dtype=float)
    labels = np.asarray(labels)
    pos = scores[labels == 1]
    neg = scores[labels == 0]
    candidates = np.unique(np.concatenate([pos, neg]))
    best = (0.0, math.inf, 0.0)
    for t in candidates:
        fp = float((neg >= t).mean())
        if fp > cap:
            continue
        rec = float((pos >= t).mean())
        if rec > best[0]:
            best = (rec, float(t), fp)
    return best


def cache_flash(records):
    for r in records:
        inp = r["input"]
        cids = inp["componentIds"]
        flows = inp["feedFlows"]
        n = inp["stageCount"]
        p_feed = inp["topPressurePascal"] + inp["stagePressureDropPascal"] * max(
            0, inp["feedStageNumber"] - 1)
        inp["vfFeed"] = T.vapour_fraction(cids, flows, inp["feedTemperatureKelvin"], p_feed)
        inp["lightCond"] = T.light_fraction(cids, flows, inp["condenserKelvin"],
                                            inp["topPressurePascal"])
        inp["vfCond"] = T.vapour_fraction(cids, flows, inp["condenserKelvin"],
                                          inp["topPressurePascal"])
        inp["lambdaTop"] = T.clausius_latent_heat(cids, flows, inp["condenserKelvin"])
        inp["lambdaFeed"] = T.clausius_latent_heat(cids, flows, inp["feedTemperatureKelvin"])


def build(records):
    rows = []
    for r in records:
        if r["input"]["drawCount"] == 0 or r["withdrawal"] is None:
            continue
        if r["success"]:
            lab = 0
        elif r["withdrawal"] >= 1.0:
            lab = 1
        elif r["withdrawal"] >= 0.8:
            lab = 2  # near-depletion failure
        else:
            continue
        rows.append((r, lab))
    return rows


def main():
    data = {n: A.load(n) for n in A.JOURNALS}
    allrec = [r for n in A.JOURNALS for r in data[n] if r["input"]["drawCount"] > 0]
    cache_flash(allrec)
    rows = build(allrec)
    print("labelled rows", len(rows), "hard", sum(1 for _, l in rows if l == 1),
          "nearfail", sum(1 for _, l in rows if l == 2),
          "success", sum(1 for _, l in rows if l == 0))

    print("\nflash sanity:")
    vf = np.array([r["input"]["vfFeed"] for r, _ in rows])
    lam = np.array([r["input"]["lambdaTop"] for r, _ in rows])
    lc = np.array([r["input"]["lightCond"] for r, _ in rows])
    print("  vfFeed quantiles ", np.round(np.quantile(vf, [0, .05, .5, .95, 1]), 3))
    print("  lambdaTop kJ/mol ", np.round(np.quantile(lam, [0, .05, .5, .95, 1]) / 1e3, 1))
    print("  lightCond        ", np.round(np.quantile(lc, [0, .05, .5, .95, 1]), 4))

    def proxy_flash(inp, kind):
        R = inp["reflux"]
        lam = inp["lambdaTop"]
        q = inp["reboilerWatts"] or 0.0
        cool = A.total_cooling_watts(inp)
        F = inp["feedTotal"]
        S = inp["steamTotal"]
        vtop = max(0.0, q / lam + inp["vfFeed"] * F + S - cool / lam)
        d_energy = vtop / (R + 1.0)
        d_light = inp["lightCond"] * F
        d_mat = max(0.0, F + S - inp["drawTotal"])
        if kind == "energy":
            d = d_energy
        elif kind == "light":
            d = d_light
        elif kind == "minEL":
            d = min(d_energy, d_light)
        elif kind == "minAll":
            d = min(d_energy, d_light, d_mat)
        elif kind == "minEM":
            d = min(d_energy, d_mat)
        else:
            raise ValueError(kind)
        worst, cum = 0.0, 0.0
        for tray in range(1, inp["stageCount"] + 1):
            cum += sum(rate for t, rate in inp["draws"] if t == tray)
            if cum <= 0:
                continue
            supply = R * d + A.cooling_above_watts(inp, tray) / lam \
                + ((1.0 - inp["vfFeed"]) * F if tray >= inp["feedStageNumber"] else 0.0)
            v = cum / supply if supply > 0 else math.inf
            worst = max(worst, v)
        return worst

    labels_hard = np.array([1 if l == 1 else 0 for _, l in rows if l in (0, 1)])
    recs_hard = [r for r, l in rows if l in (0, 1)]
    labels_fam = np.array([1 if l in (1, 2) else 0 for _, l in rows])
    recs_fam = [r for r, _ in rows]

    print("\nflash proxies (hard band vs successes / whole family vs successes):")
    for kind in ("energy", "light", "minEL", "minEM", "minAll"):
        sh = np.array([proxy_flash(r["input"], kind) for r in recs_hard])
        sf = np.array([proxy_flash(r["input"], kind) for r in recs_fam])
        sh = np.where(np.isfinite(sh), sh, 1e12)
        sf = np.where(np.isfinite(sf), sf, 1e12)
        a = tie_aware_auc(sh, labels_hard)
        r0 = recall_at_fp(sh, labels_hard, 0.0)
        r5 = recall_at_fp(sh, labels_hard, 0.005)
        r2 = recall_at_fp(sf, labels_fam, 0.02)
        print(f"  {kind:8s} AUC={a:.3f} recall@0FP={r0[0]:.3f}(t={r0[1]:.4g}) "
              f"recall@0.5%FP={r5[0]:.3f}(t={r5[1]:.4g}) familyRecall@2%FP={r2[0]:.3f}(t={r2[1]:.4g})")

    print("\nsingle flash features (hard band vs successes):")
    feats = {
        "vfFeed": lambda i: -i["vfFeed"],
        "lightCond": lambda i: -i["lightCond"],
        "lambdaTop": lambda i: i["lambdaTop"],
        "draws/(vfFeed*F)": lambda i: i["drawTotal"] / max(1e-9, i["vfFeed"] * i["feedTotal"]),
        "draws/(lightCond*F*R)": lambda i: i["drawTotal"] / max(
            1e-9, i["lightCond"] * i["feedTotal"] * i["reflux"]),
        "draws/(R*minEL)": lambda i: i["drawTotal"] / max(1e-9, i["reflux"] * min(
            i["lightCond"] * i["feedTotal"],
            max(0.0, (i["reboilerWatts"] or 0.0) / i["lambdaTop"] + i["vfFeed"] * i["feedTotal"]
                + i["steamTotal"] - A.total_cooling_watts(i) / i["lambdaTop"]) / (i["reflux"] + 1))),
        "rho": lambda i: A.rho_screen(i)[0],
    }
    for name, fn in feats.items():
        s = np.array([fn(r["input"]) for r in recs_hard])
        s = np.where(np.isfinite(s), s, 1e12)
        a = tie_aware_auc(s, labels_hard)
        r0 = recall_at_fp(s, labels_hard, 0.0)
        r5 = recall_at_fp(s, labels_hard, 0.005)
        r2 = recall_at_fp(s, labels_hard, 0.02)
        print(f"  {name:24s} AUC={a:.3f} r@0FP={r0[0]:.3f} r@0.5%={r5[0]:.3f}(t={r5[1]:.4g}) "
              f"r@2%={r2[0]:.3f}(t={r2[1]:.4g})")


if __name__ == "__main__":
    main()

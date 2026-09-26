"""Does authored pumparound cooling above a side draw prevent liquid depletion?

Quantifies the design rule "large side draws need appropriate pumparound cooling above them" on the
same pooled population as analyze.py. Read-only; consumes extracted-*.jsonl, writes kappa-tables.md
and merges a `pumparoundCoverage` block into summary.json.

kappa(T) = coolingAbove(T) / (lambda * cumulativeDraws(T)),  lambda = 60 kJ/mol
         = authored cooling duty on trays 1..T divided by the duty needed to condense the liquid
           withdrawn at and above T.
kappa_req = min over the authored draw trays.

Run:  python kappa.py
"""
from __future__ import annotations

import json
import math
import os
import sys
from collections import defaultdict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import analyze as A  # noqa: E402

LAMBDA = 60_000.0

BANDS = [
    ("0", lambda k: k <= 0.0),
    ("(0,0.25]", lambda k: 0.0 < k <= 0.25),
    ("(0.25,0.5]", lambda k: 0.25 < k <= 0.5),
    ("(0.5,1]", lambda k: 0.5 < k <= 1.0),
    ("(1,2]", lambda k: 1.0 < k <= 2.0),
    ("(2,4]", lambda k: 2.0 < k <= 4.0),
    (">4", lambda k: k > 4.0),
]
K_CUTS = [0.25, 0.5, 1.0, 2.0]
SIGMA_CUT = 0.66


# ------------------------------------------------------------------ statistics


def kappa_required(inp):
    """min over draw trays of coolingAbove(T) / (lambda * cumulative draws at and above T)."""
    draws = inp["draws"]
    if not draws:
        return None, 0
    worst, tray_at = math.inf, 0
    cumulative = 0.0
    for tray in range(1, inp["stageCount"] + 1):
        cumulative += math.fsum(rate for t, rate in draws if t == tray)
        if cumulative <= 0.0:
            continue
        if not any(t == tray for t, _ in draws):
            continue  # only the authored draw trays define the requirement
        k = A.cooling_above_watts(inp, tray) / (LAMBDA * cumulative)
        if k < worst:
            worst, tray_at = k, tray
    return (None, 0) if worst is math.inf else (worst, tray_at)


def cooling_zones(inp):
    return [(low, high) for low, high, watts, _ in inp["pumparounds"] if watts < 0.0]


def flag_all_covered(inp):
    """Every draw tray has a cooling zone lying entirely at or above it."""
    zones = cooling_zones(inp)
    if not zones:
        return False
    return all(any(high <= tray for _, high in zones) for tray, _ in inp["draws"])


def flag_any_covered(inp):
    """At least one draw tray has a cooling zone lying entirely at or above it."""
    zones = cooling_zones(inp)
    if not zones:
        return False
    return any(any(high <= tray for _, high in zones) for tray, _ in inp["draws"])


def flag_shallowest_covered(inp):
    """A cooling zone ends at or above the shallowest draw (identical to flag_all_covered)."""
    zones = cooling_zones(inp)
    if not zones or not inp["draws"]:
        return False
    shallowest = min(t for t, _ in inp["draws"])
    return any(high <= shallowest for _, high in zones)


def label_of(r):
    if r["withdrawal"] >= 1.0:
        return "hard"
    if r["withdrawal"] >= 0.8:
        return "nearsolved" if r["success"] else "nearfail"
    return "solved" if r["success"] else "otherfail"


# ------------------------------------------------------------------ reporting


def group_stats(rows):
    n = len(rows)
    if n == 0:
        return None
    labs = [label_of(r) for r in rows]
    hard = labs.count("hard")
    nearfail = labs.count("nearfail")
    nearsolved = labs.count("nearsolved")
    solved = sum(1 for r in rows if r["success"])
    return {
        "n": n,
        "solved": solved,
        "successRate": solved / n,
        "hard": hard,
        "hardRate": hard / n,
        "nearFail": nearfail,
        "family": hard + nearfail,
        "familyRate": (hard + nearfail) / n,
        "nearSolved": nearsolved,
        "nearSolvedRate": nearsolved / n,
    }


def pct(num, den):
    return "n/a" if not den else f"{100.0 * num / den:.1f}%"


def md_table(headers, rows):
    out = ["| " + " | ".join(str(h) for h in headers) + " |",
           "|" + "|".join("---" for _ in headers) + "|"]
    for row in rows:
        out.append("| " + " | ".join("" if c is None else str(c) for c in row) + " |")
    return "\n".join(out)


def main():
    data = {name: A.load(name) for name in A.JOURNALS}
    for name in A.JOURNALS:
        A.cache_flash(data[name])

    pool = []
    for name in A.JOURNALS:
        for r in data[name]:
            if r["input"]["drawCount"] == 0 or r["withdrawal"] is None:
                continue
            inp = r["input"]
            k, tray = kappa_required(inp)
            r["kappa"] = k
            r["kappaTray"] = tray
            r["sigma"] = A.sigma(inp)[0]
            r["flagAll"] = flag_all_covered(inp)
            r["flagAny"] = flag_any_covered(inp)
            r["flagShallow"] = flag_shallowest_covered(inp)
            pool.append(r)

    out = {"lambdaJoulesPerMol": LAMBDA, "pooledKnownW": len(pool)}
    tables = []

    # the two structural flags the brief names are the same predicate
    same = sum(1 for r in pool if r["flagAll"] == r["flagShallow"])
    out["flagAllEqualsFlagShallowest"] = (same == len(pool))

    # ---------------------------------------------------------- kappa bands
    out["byBand"] = {}
    rws = []
    for scope in A.JOURNALS + ["pooled"]:
        rows = pool if scope == "pooled" else [r for r in pool if r["journal"] == scope]
        out["byBand"][scope] = {}
        for label, test in BANDS:
            sel = [r for r in rows if r["kappa"] is not None and test(r["kappa"])]
            st = group_stats(sel)
            out["byBand"][scope][label] = st
            if st:
                rws.append([scope, label, st["n"], pct(st["solved"], st["n"]),
                            st["hard"], pct(st["hard"], st["n"]),
                            st["family"], pct(st["family"], st["n"]),
                            st["nearSolved"], pct(st["nearSolved"], st["n"])])
    tables.append(("K1 Outcome by kappa_req band (draw requests with a known withdrawal)", md_table(
        ["scope", "kappa_req band", "n", "success rate", "hard W>=1", "hard rate",
         "family", "family rate", "near band and solved", "near-solved rate"], rws)))

    # ---------------------------------------------------------- structural flags
    out["byFlag"] = {}
    rws = []
    for scope in A.JOURNALS + ["pooled"]:
        rows = pool if scope == "pooled" else [r for r in pool if r["journal"] == scope]
        out["byFlag"][scope] = {}
        for flag, label in [("flagAll", "every draw tray covered by a zone above it"),
                            ("flagAny", "at least one draw tray covered"),
                            ("flagShallow", "a zone ends at or above the shallowest draw")]:
            for value in (True, False):
                sel = [r for r in rows if r[flag] is value]
                st = group_stats(sel)
                out["byFlag"][scope][f"{flag}={value}"] = st
                if st:
                    rws.append([scope, label, str(value), st["n"], pct(st["solved"], st["n"]),
                                st["hard"], pct(st["hard"], st["n"]),
                                st["family"], pct(st["family"], st["n"]),
                                st["nearSolved"], pct(st["nearSolved"], st["n"])])
    tables.append(("K2 Outcome by structural coverage flag", md_table(
        ["scope", "flag", "value", "n", "success rate", "hard W>=1", "hard rate", "family",
         "family rate", "near band and solved", "near-solved rate"], rws)))

    # ---------------------------------------------------------- screen view
    def screen(rows, fires):
        hard = [r for r in rows if label_of(r) == "hard"]
        nearf = [r for r in rows if label_of(r) == "nearfail"]
        solved = [r for r in rows if r["success"]]
        fam = hard + nearf
        return {
            "hard": len(hard), "hardHits": sum(1 for r in hard if fires(r)),
            "hardRecall": (sum(1 for r in hard if fires(r)) / len(hard)) if hard else None,
            "nearFail": len(nearf), "nearHits": sum(1 for r in nearf if fires(r)),
            "nearRecall": (sum(1 for r in nearf if fires(r)) / len(nearf)) if nearf else None,
            "solved": len(solved), "falsePositives": sum(1 for r in solved if fires(r)),
            "fpRate": (sum(1 for r in solved if fires(r)) / len(solved)) if solved else None,
            "family": len(fam), "residualFamily": sum(1 for r in fam if not fires(r)),
            "fired": sum(1 for r in rows if fires(r)),
        }

    rules = {"sigma >= 0.66": lambda r: r["sigma"] >= SIGMA_CUT}
    for k in K_CUTS:
        rules[f"kappa_req < {k:g}"] = (lambda r, k=k: r["kappa"] is not None and r["kappa"] < k)
        rules[f"sigma >= 0.66 OR kappa_req < {k:g}"] = (
            lambda r, k=k: r["sigma"] >= SIGMA_CUT or (r["kappa"] is not None and r["kappa"] < k))
    rules["sigma >= 0.66 AND kappa_req < 1"] = (
        lambda r: r["sigma"] >= SIGMA_CUT and r["kappa"] is not None and r["kappa"] < 1.0)

    out["screens"] = {}
    rws = []
    for name, fires in rules.items():
        out["screens"][name] = {}
        for scope in A.JOURNALS + ["pooled"]:
            rows = pool if scope == "pooled" else [r for r in pool if r["journal"] == scope]
            s = screen(rows, fires)
            out["screens"][name][scope] = s
            rws.append([name, scope, s["fired"], f"{s['hardHits']}/{s['hard']}",
                        pct(s["hardHits"], s["hard"]), f"{s['nearHits']}/{s['nearFail']}",
                        pct(s["nearHits"], s["nearFail"]),
                        f"{s['falsePositives']}/{s['solved']}",
                        pct(s["falsePositives"], s["solved"]), s["residualFamily"]])
    tables.append(("K3 Screen view: kappa_req thresholds against, and combined with, sigma >= 0.66",
                   md_table(["rule", "scope", "fired", "hard hits", "hard recall",
                             "near-fail hits", "near recall", "false positives on solved",
                             "FP rate", "residual family"], rws)))

    # ---------------------------------------------------------- distributions
    out["distributions"] = {}
    rws = []
    for label in ("hard", "nearfail", "nearsolved", "solved"):
        vals = np.array([r["kappa"] for r in pool
                         if label_of(r) == label and r["kappa"] is not None])
        if len(vals) == 0:
            continue
        q = np.quantile(vals, [0.05, 0.25, 0.5, 0.75, 0.95])
        out["distributions"][label] = {
            "n": int(len(vals)), "zeroShare": float((vals <= 0).mean()),
            "p05": float(q[0]), "p25": float(q[1]), "p50": float(q[2]),
            "p75": float(q[3]), "p95": float(q[4])}
        rws.append([label, len(vals), pct(int((vals <= 0).sum()), len(vals))]
                   + [f"{v:.3g}" for v in q])
    tables.append(("K4 kappa_req distribution by outcome (pooled)", md_table(
        ["outcome", "n", "share with kappa_req = 0", "p05", "p25", "p50", "p75", "p95"], rws)))

    # AUC of -kappa against hard, and of sigma, for a like-for-like comparison
    def auc(scores, labels):
        scores = np.asarray(scores, float)
        labels = np.asarray(labels)
        order = scores.argsort()
        ranks = np.empty(len(scores), float)
        s = scores[order]
        i = 0
        while i < len(s):
            j = i
            while j + 1 < len(s) and s[j + 1] == s[i]:
                j += 1
            ranks[order[i:j + 1]] = 0.5 * (i + j) + 1.0
            i = j + 1
        npos, nneg = int((labels == 1).sum()), int((labels == 0).sum())
        return float((ranks[labels == 1].sum() - npos * (npos + 1) / 2) / (npos * nneg))

    hs = [r for r in pool if label_of(r) in ("hard", "solved") and r["kappa"] is not None]
    y = np.array([1 if label_of(r) == "hard" else 0 for r in hs])
    out["auc"] = {
        "minusKappaVsSolved": auc([-min(r["kappa"], 50.0) for r in hs], y),
        "sigmaVsSolved": auc([r["sigma"] for r in hs], y),
    }

    # how much of sigma's signal is the cooling term: sigma with the cooling credit removed
    def sigma_no_cooling(inp):
        R, F, vf = inp["reflux"], inp["feedTotal"], inp["vfFeed"]
        base = R / (R + 1.0) * max(0.0, (inp["reboilerWatts"] or 0.0) / LAMBDA + vf * F)
        worst, cumulative = 0.0, 0.0
        for tray in range(1, inp["stageCount"] + 1):
            cumulative += math.fsum(rate for t, rate in inp["draws"] if t == tray)
            if cumulative <= 0.0:
                continue
            supply = base + ((1.0 - vf) * F if tray >= inp["feedStageNumber"] else 0.0)
            worst = max(worst, cumulative / supply if supply > 0 else math.inf)
        return worst
    out["auc"]["sigmaWithoutCoolingCredit"] = auc(
        [min(sigma_no_cooling(r["input"]), 1e6) for r in hs], y)

    # joint table: family rate on the sigma x kappa grid
    rws = []
    for slab, stest in [("sigma < 0.45", lambda s: s < 0.45),
                        ("0.45 <= sigma < 0.66", lambda s: 0.45 <= s < 0.66),
                        ("sigma >= 0.66", lambda s: s >= 0.66)]:
        for klab, ktest in [("kappa_req = 0", lambda k: k <= 0),
                            ("0 < kappa_req <= 1", lambda k: 0 < k <= 1),
                            ("kappa_req > 1", lambda k: k > 1)]:
            sel = [r for r in pool if r["kappa"] is not None
                   and stest(r["sigma"]) and ktest(r["kappa"])]
            st = group_stats(sel)
            if st:
                rws.append([slab, klab, st["n"], pct(st["solved"], st["n"]),
                            st["hard"], pct(st["hard"], st["n"]),
                            st["family"], pct(st["family"], st["n"])])
    tables.append(("K5 Family rate on the sigma x kappa_req grid (pooled)", md_table(
        ["sigma band", "kappa_req band", "n", "success rate", "hard", "hard rate", "family",
         "family rate"], rws)))

    # per-draw view: is it the shallow draws that lack cooling?
    rws = []
    for band, lo, hi in [("tray 1", 1, 1), ("trays 2-3", 2, 3), ("trays 4-10", 4, 10),
                         ("tray >10", 11, 10 ** 9)]:
        sel = [r for r in pool if lo <= min(t for t, _ in r["input"]["draws"]) <= hi]
        kv = np.array([r["kappa"] for r in sel if r["kappa"] is not None])
        st = group_stats(sel)
        if st:
            rws.append([band, st["n"], f"{np.median(kv):.3g}", pct(int((kv <= 0).sum()), len(kv)),
                        pct(st["solved"], st["n"]), pct(st["family"], st["n"])])
    tables.append(("K6 kappa_req and outcome by the shallowest authored draw tray (pooled)",
                   md_table(["shallowest draw", "n", "median kappa_req",
                             "share kappa_req = 0", "success rate", "family rate"], rws)))

    # interaction with the condenser-temperature corner
    rws = []
    ct = np.array([r["input"]["condenserKelvin"] for r in pool])
    edges = np.quantile(ct, [0, 0.5, 1.0])
    for ci, (clo, chi) in enumerate([(edges[0], edges[1]), (edges[1], edges[2])]):
        for klab, ktest in [("kappa_req <= 1", lambda k: k <= 1), ("kappa_req > 1", lambda k: k > 1)]:
            sel = [r for r in pool if r["kappa"] is not None and ktest(r["kappa"])
                   and (clo <= r["input"]["condenserKelvin"] < chi if ci == 0
                        else clo <= r["input"]["condenserKelvin"] <= chi)]
            st = group_stats(sel)
            if st:
                rws.append([f"condenser T {'below' if ci == 0 else 'above'} median "
                            f"({clo:.0f}-{chi:.0f} K)", klab, st["n"],
                            pct(st["solved"], st["n"]), st["hard"], pct(st["hard"], st["n"]),
                            pct(st["family"], st["n"])])
    tables.append(("K7 Cooling coverage inside and outside the hot-condenser corner (pooled)",
                   md_table(["condenser half", "kappa_req band", "n", "success rate", "hard",
                             "hard rate", "family rate"], rws)))

    # ------------------------------------------------- the counterweight: heat gating
    # More cooling is not free: it is what produces the condensation cap. Measured over the WHOLE
    # draw population, including the requests whose withdrawal is not measurable.
    everything = []
    for name in A.JOURNALS:
        for r in data[name]:
            if r["input"]["drawCount"] == 0:
                continue
            k, _ = kappa_required(r["input"])
            everything.append((name, r, k))
    out["heatGateByBand"] = {}
    rws = []
    for label, test in BANDS:
        sel = [(n, r) for n, r, k in everything if k is not None and test(k)]
        if not sel:
            continue
        n_all = len(sel)
        gated = sum(1 for _, r in sel if A.request_class(r) == "heat-gated")
        unknown = sum(1 for _, r in sel if r["withdrawal"] is None)
        solved = sum(1 for _, r in sel if r["success"])
        fam = sum(1 for _, r in sel
                  if r["withdrawal"] is not None and r["withdrawal"] >= 0.8 and not r["success"])
        out["heatGateByBand"][label] = {"n": n_all, "heatGated": gated, "unknownW": unknown,
                                        "solved": solved, "family": fam}
        rws.append([label, n_all, gated, pct(gated, n_all), unknown, pct(unknown, n_all),
                    pct(solved, n_all), fam, pct(fam, n_all)])
    tables.append(("K8 The counterweight: heat gating and unmeasurable outcomes by kappa_req band, "
                   "over ALL draw requests (pooled)", md_table(
                       ["kappa_req band", "n", "heat-gated", "heat-gate rate", "unknown W",
                        "unknown rate", "success rate", "family", "family rate"], rws)))

    # is the kappa effect just "more pumparounds"? hold the pumparound count fixed
    out["byPumparoundCount"] = {}
    rws = []
    for pa in (1, 2, 3, 4):
        for klab, ktest in [("kappa_req = 0", lambda k: k <= 0),
                            ("0 < kappa_req <= 1", lambda k: 0 < k <= 1),
                            ("kappa_req > 1", lambda k: k > 1)]:
            sel = [r for r in pool if r["input"]["pumparoundCount"] == pa
                   and r["kappa"] is not None and ktest(r["kappa"])]
            st = group_stats(sel)
            out["byPumparoundCount"][f"pa={pa};{klab}"] = st
            if st:
                rws.append([pa, klab, st["n"], pct(st["solved"], st["n"]), st["hard"],
                            pct(st["hard"], st["n"]), st["family"], pct(st["family"], st["n"])])
    tables.append(("K10 Family rate by kappa_req with the pumparound count held fixed (pooled, "
                   "known W)", md_table(
                       ["pumparounds", "kappa_req band", "n", "success rate", "hard", "hard rate",
                        "family", "family rate"], rws)))

    # feasibility of the proposed constraint on the existing structural cells
    out["feasibility"] = {}
    rws = []
    for scope in A.JOURNALS + ["pooled"]:
        rows = [(n, r, k) for n, r, k in everything if scope in (n, "pooled")]
        n_all = len(rows)
        no_pa = sum(1 for _, r, _ in rows if not cooling_zones(r["input"]))
        shallow1 = sum(1 for _, r, _ in rows if min(t for t, _ in r["input"]["draws"]) == 1)
        meets = sum(1 for _, _, k in rows if k is not None and k >= 1.0)
        out["feasibility"][scope] = {"drawRequests": n_all, "noCoolingPumparound": no_pa,
                                     "drawOnTray1": shallow1, "alreadyMeetsKappa1": meets}
        rws.append([scope, n_all, no_pa, pct(no_pa, n_all), shallow1, pct(shallow1, n_all),
                    meets, pct(meets, n_all)])
    tables.append(("K9 Feasibility of the proposed constraint on the existing matrices", md_table(
        ["scope", "draw requests", "no cooling pumparound at all", "share", "a draw on tray 1",
         "share", "already meets kappa_req >= 1", "share"], rws)))

    with open(os.path.join(HERE, "kappa-tables.md"), "w", encoding="utf-8") as handle:
        for title, table in tables:
            handle.write(f"### {title}\n\n{table}\n\n")

    summary_path = os.path.join(HERE, "summary.json")
    with open(summary_path, "r", encoding="utf-8") as handle:
        summary = json.load(handle)
    summary["pumparoundCoverage"] = out
    with open(summary_path, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=1, default=float)

    print("wrote kappa-tables.md and merged pumparoundCoverage into summary.json")
    print(json.dumps({"auc": out["auc"], "distributions": out["distributions"],
                      "flagAllEqualsFlagShallowest": out["flagAllEqualsFlagShallowest"]},
                     indent=1, default=float))
    for title, table in tables:
        print(f"\n### {title}\n\n{table}")


if __name__ == "__main__":
    main()

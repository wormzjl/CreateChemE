"""Liquid-depletion analysis of the V3 classical-solver training journals.

Read-only. Consumes the compact records written by extract.js and emits

  summary.json   every number quoted in summary.md
  tables.md      the generated markdown tables that summary.md embeds

Run:  python analyze.py
"""

from __future__ import annotations

import itertools
import json
import math
import os
import re
from collections import Counter, defaultdict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
import sys  # noqa: E402

sys.path.insert(0, HERE)
import thermo as T  # noqa: E402

JOURNALS = ["design-v2", "design-g", "codex"]
PUMPAROUND_LATENT_J_PER_MOL = 30_000.0  # V3LiquidSupplyScreen.PUMPAROUND_LATENT_HEAT_JOULES_PER_MOL
HEAT_GATE = re.compile(r"condensation-capped|not below the")
ID_SUFFIX = re.compile(r"-w(\d+)p(\d+)d(\d+)-")
STRICT_WATER = ("DRY_EQUILIBRIUM", "WET_EQUILIBRIUM")

HARD = "hard(>=1)"
NEAR = "near(0.8-1)"

STAGE_BANDS = [(2, 9), (10, 19), (20, 34), (35, 49), (50, 64)]
BANDS_W = [(0.0, 0.2), (0.2, 0.4), (0.4, 0.6), (0.6, 0.8), (0.8, 1.0), (1.0, math.inf)]
THRESHOLDS = [0.3, 0.5, 0.7, 1.0, 1.5, 2.0, 3.0]


# ---------------------------------------------------------------- loading


def load(name):
    path = os.path.join(HERE, f"extracted-{name}.jsonl")
    with open(path, "r", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def stage_band(n):
    for lo, hi in STAGE_BANDS:
        if lo <= n <= hi:
            return f"{lo}-{hi}"
    return f">{STAGE_BANDS[-1][1]}"


def withdrawal_class(record):
    w = record.get("withdrawal")
    if w is None:
        return "unknown"
    if w >= 1.0:
        return HARD
    if w >= 0.8:
        return NEAR
    return "other"


def band_of(w):
    for lo, hi in BANDS_W:
        if lo <= w < hi:
            return f"[{lo:g},{'inf' if hi == math.inf else format(hi, 'g')})"
    return "?"


# ------------------------------------------------- request-only statistics


def cooling_above_watts(inp, tray):
    """Authored cooling duty on trays 1..tray, watts, positive. Port of V3LiquidSupplyScreen."""
    duty = 0.0
    for low, high, watts, split in inp["pumparounds"]:
        if watts >= 0.0:
            continue
        if split == "RETURN_TRAY":
            duty += -watts if low <= tray else 0.0
        else:
            duty += -watts * max(0, min(high, tray) - low + 1) / (high - low + 1)
    return duty if math.isfinite(duty) else 0.0


def total_cooling_watts(inp):
    return sum(-w for _, _, w, _ in inp["pumparounds"] if w < 0.0)


def total_heating_watts(inp):
    return sum(w for _, _, w, _ in inp["pumparounds"] if w > 0.0)


def rho_screen(inp):
    """Exact reimplementation of V3LiquidSupplyScreen.evaluate: (ratio, limitingTray)."""
    draws = inp["draws"]
    stages = inp["stageCount"]
    if not draws or stages < 1:
        return 0.0, 0
    reflux = inp["reflux"]
    if reflux is None:
        return 0.0, 0
    feed = inp["feedTotal"]
    steam = inp["steamTotal"]
    total_draw = inp["drawTotal"]
    if not all(math.isfinite(v) for v in (feed, steam, total_draw)):
        return 0.0, 0
    distillate = max(0.0, feed + steam - total_draw)
    worst, limiting, cumulative = 0.0, 0, 0.0
    for tray in range(1, stages + 1):
        cumulative += math.fsum(rate for at, rate in draws if at == tray)
        if cumulative <= 0.0:
            continue
        supply = (reflux * distillate + steam
                  + cooling_above_watts(inp, tray) / PUMPAROUND_LATENT_J_PER_MOL
                  + (feed if tray >= inp["feedStageNumber"] else 0.0))
        ratio = cumulative / supply if supply > 0.0 else math.inf
        if ratio > worst:
            worst, limiting = ratio, tray
    return (worst, limiting) if limiting else (0.0, 0)


def energy_estimates(inp, lam, vf):
    """V_top, D_est, rectifying L_est exactly as specified in the brief."""
    q = inp["reboilerWatts"] or 0.0
    feed = inp["feedTotal"]
    steam = inp["steamTotal"]
    heat = total_heating_watts(inp)
    cool = total_cooling_watts(inp)
    v_top = max(0.0, q / lam + heat / lam + vf * feed + steam - cool / lam)
    reflux = inp["reflux"]
    d_est = v_top / (reflux + 1.0)
    return v_top, d_est, reflux * d_est


def proxy1(inp, lam, vf):
    draws = inp["draws"]
    if not draws:
        return 0.0, 0
    _, _, l_rect = energy_estimates(inp, lam, vf)
    feed = inp["feedTotal"]
    worst, limiting, cumulative = 0.0, 0, 0.0
    for tray in range(1, inp["stageCount"] + 1):
        cumulative += math.fsum(rate for at, rate in draws if at == tray)
        if cumulative <= 0.0:
            continue
        l_est = l_rect + ((1.0 - vf) * feed if tray >= inp["feedStageNumber"] else 0.0)
        ratio = cumulative / l_est if l_est > 0.0 else math.inf
        if ratio > worst:
            worst, limiting = ratio, tray
    return (worst, limiting) if limiting else (0.0, 0)


def proxy2(inp, lam, vf):
    _, _, l_rect = energy_estimates(inp, lam, vf)
    return inp["drawTotal"] / l_rect if l_rect > 0.0 else math.inf


def proxy3(inp, lam):
    reflux = inp["reflux"]
    q = inp["reboilerWatts"] or 0.0
    denom = reflux / (reflux + 1.0) * (q / lam + inp["steamTotal"] + 0.5 * inp["feedTotal"])
    return inp["drawTotal"] / denom if denom > 0.0 else math.inf


# ------------------------------------------------ flash-informed statistic


def cache_flash(records):
    """One ideal feed flash per request; caches vf, the condenser light fraction and a latent heat."""
    for r in records:
        inp = r["input"]
        if "vfFeed" in inp:
            continue
        cids, flows = inp["componentIds"], inp["feedFlows"]
        p_feed = inp["topPressurePascal"] + inp["stagePressureDropPascal"] * max(
            0, inp["feedStageNumber"] - 1)
        inp["vfFeed"] = T.vapour_fraction(cids, flows, inp["feedTemperatureKelvin"], p_feed)
        inp["lightCond"] = T.light_fraction(cids, flows, inp["condenserKelvin"],
                                            inp["topPressurePascal"])
        inp["lambdaTop"] = T.clausius_latent_heat(cids, flows, inp["condenserKelvin"])


def sigma(inp, lam=60_000.0, use_flash_lambda=False):
    """Recommended request-only depletion statistic.

    Worst over the draw trays of the cumulative withdrawal at and above the tray divided by the
    internal liquid the request's own energy input can deliver there:

        L(T) = R/(R+1) * (Q/lambda + vf*F) + coolingAbove(T)/lambda + (1-vf)*F  [T >= feed tray]

    ``vf`` is the ideal feed vapour fraction at the feed stage; steam is deliberately not credited
    because it leaves as free water rather than as tray liquid.
    """
    draws = inp["draws"]
    if not draws:
        return 0.0, 0
    lam_used = inp["lambdaTop"] if use_flash_lambda else lam
    R = inp["reflux"]
    F = inp["feedTotal"]
    vf = inp["vfFeed"]
    v_gen = max(0.0, (inp["reboilerWatts"] or 0.0) / lam_used + vf * F)
    base = R / (R + 1.0) * v_gen
    worst, limiting, cumulative = 0.0, 0, 0.0
    for tray in range(1, inp["stageCount"] + 1):
        cumulative += math.fsum(rate for at, rate in draws if at == tray)
        if cumulative <= 0.0:
            continue
        supply = base + cooling_above_watts(inp, tray) / lam_used \
            + ((1.0 - vf) * F if tray >= inp["feedStageNumber"] else 0.0)
        ratio = cumulative / supply if supply > 0.0 else math.inf
        if ratio > worst:
            worst, limiting = ratio, tray
    return (worst, limiting) if limiting else (0.0, 0)


# ---------------------------------------------------------------- scoring


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
    npos, nneg = int((labels == 1).sum()), int((labels == 0).sum())
    if npos == 0 or nneg == 0:
        return None
    return float((ranks[labels == 1].sum() - npos * (npos + 1) / 2) / (npos * nneg))


def best_threshold_at_fp(scores, labels, cap):
    """Highest recall reachable by a `>= t` rule whose false-positive rate is at most cap."""
    scores = np.asarray(scores, dtype=float)
    labels = np.asarray(labels)
    pos, neg = scores[labels == 1], scores[labels == 0]
    if len(pos) == 0 or len(neg) == 0:
        return {"recall": None, "threshold": None, "fpRate": None}
    best = {"recall": 0.0, "threshold": math.inf, "fpRate": 0.0}
    for t in np.unique(np.concatenate([pos, neg])):
        fp = float((neg >= t).mean())
        if fp > cap:
            continue
        rec = float((pos >= t).mean())
        if rec > best["recall"]:
            best = {"recall": rec, "threshold": float(t), "fpRate": fp}
    return best


# ---------------------------------------------------------------- tables


def md_table(headers, rows):
    out = ["| " + " | ".join(str(h) for h in headers) + " |",
           "|" + "|".join("---" for _ in headers) + "|"]
    for row in rows:
        out.append("| " + " | ".join("" if c is None else str(c) for c in row) + " |")
    return "\n".join(out)


def fmt(x, digits=4):
    if x is None:
        return ""
    if isinstance(x, float):
        if not math.isfinite(x):
            return "inf"
        if abs(x) >= 1e6 or (x != 0 and abs(x) < 1e-3):
            return f"{x:.3g}"
        return f"{x:.{digits}g}"
    return str(x)


def pct(num, den):
    return "n/a" if den == 0 else f"{100.0 * num / den:.1f}%"


# ---------------------------------------------------------------- task 1


def counts_for(records):
    draws = [r for r in records if r["input"]["drawCount"] > 0]
    classes = Counter(withdrawal_class(r) for r in draws)
    failures = [r for r in draws if not r["success"]]
    known_fail = [r for r in failures if r["withdrawal"] is not None]
    fam_fail = [r for r in failures if r["withdrawal"] is not None and r["withdrawal"] >= 0.8]
    proven = [r for r in draws if r["status"] == "ACCEPTANCE_AUDIT_FAILURE"
              and r["withdrawal"] is not None and r["withdrawal"] >= 1.0]
    return {
        "requests": len(records),
        "drawRequests": len(draws),
        "drawSuccesses": sum(1 for r in draws if r["success"]),
        "drawFailures": len(failures),
        "hard": classes[HARD],
        "near": classes[NEAR],
        "family": classes[HARD] + classes[NEAR],
        "nearSuccesses": sum(1 for r in draws if withdrawal_class(r) == NEAR and r["success"]),
        "unknown": classes["unknown"],
        "knownWithdrawal": len(draws) - classes["unknown"],
        "familyFailures": len(fam_fail),
        "convergedAuditRejected": len(proven),
        "failuresWithKnownWithdrawal": len(known_fail),
        "familyShareOfDrawFailures": (len(fam_fail) / len(failures)) if failures else None,
        "familyShareOfKnownFailures": (len(fam_fail) / len(known_fail)) if known_fail else None,
    }


def breakdown(records, keyfn):
    groups = defaultdict(list)
    for r in records:
        if r["input"]["drawCount"] == 0:
            continue
        groups[keyfn(r)].append(r)
    out = {}
    for key, rows in groups.items():
        classes = Counter(withdrawal_class(r) for r in rows)
        successes = sum(1 for r in rows if r["success"])
        known = len(rows) - classes["unknown"]
        out[str(key)] = {
            "draws": len(rows),
            "success": successes,
            "successRate": successes / len(rows),
            "hard": classes[HARD],
            "near": classes[NEAR],
            "family": classes[HARD] + classes[NEAR],
            "unknown": classes["unknown"],
            "familyRateOfDraws": (classes[HARD] + classes[NEAR]) / len(rows),
            "familyRateOfKnown": ((classes[HARD] + classes[NEAR]) / known) if known else None,
        }
    return dict(sorted(out.items()))


def request_class(record):
    inp = record["input"]
    if record["status"] == "INFEASIBLE_SPECIFICATION":
        return "typed-infeasible"
    if inp["steamCount"] == 0 and (inp["reboilerWatts"] or 0.0) == 0.0 \
            and inp["feedStageNumber"] < inp["stageCount"]:
        return "zero-boilup"
    if record["failure"] and HEAT_GATE.search(record["failure"]):
        return "heat-gated"
    return "D-open"


# ---------------------------------------------------------------- main


def main():
    data = {name: load(name) for name in JOURNALS}
    holdout = load("holdout")
    for name in JOURNALS:
        cache_flash(data[name])
    cache_flash(holdout)
    summary = {}
    tables = []

    # ========================================================== task 1
    summary["counts"] = {name: counts_for(rows) for name, rows in data.items()}
    rows = []
    for name in JOURNALS:
        c = summary["counts"][name]
        rows.append([name, c["requests"], c["drawRequests"], c["drawSuccesses"], c["drawFailures"],
                     c["hard"], c["near"], c["family"], c["nearSuccesses"], c["unknown"],
                     c["convergedAuditRejected"],
                     pct(c["familyFailures"], c["drawFailures"]),
                     pct(c["familyFailures"], c["failuresWithKnownWithdrawal"])])
    tables.append(("T1 Liquid-depletion family per journal", md_table(
        ["journal", "requests", "draw requests", "draw successes", "draw failures",
         "hard >=1", "near 0.8-1", "family", "near that solved", "unknown W",
         "converged + audit-rejected", "family / all draw failures",
         "family / draw failures with known W"], rows)))

    keyfns = {
        "drawCount": lambda r: r["input"]["drawCount"],
        "stageBand": lambda r: stage_band(r["input"]["stageCount"]),
        "steam": lambda r: "steam" if r["input"]["steamCount"] > 0 else "dry",
        "pumparoundCount": lambda r: r["input"]["pumparoundCount"],
        "designFamily": lambda r: r["family"] or "-",
        "idSuffix": lambda r: (ID_SUFFIX.search(r["id"]).group(0).strip("-")
                               if ID_SUFFIX.search(r["id"]) else "(neighbourhood)"),
        "split": lambda r: r["split"] or "-",
        "source": lambda r: r["source"] or (r["id"].split("-")[0]),
    }
    summary["breakdowns"] = {name: {k: breakdown(data[name], f) for k, f in keyfns.items()}
                             for name in JOURNALS}

    for dim, label in [("drawCount", "number of side draws"), ("stageBand", "stage-count band"),
                       ("steam", "steam on/off"), ("pumparoundCount", "pumparound count")]:
        rws = []
        for name in JOURNALS:
            for key, v in summary["breakdowns"][name][dim].items():
                rws.append([name, key, v["draws"], pct(v["success"], v["draws"]),
                            v["hard"], v["near"], v["family"], pct(v["family"], v["draws"]),
                            v["unknown"],
                            pct(v["family"], v["draws"] - v["unknown"])])
        tables.append((f"T2.{dim} Family by {label}", md_table(
            ["journal", label, "draw reqs", "succ rate", "hard", "near", "family",
             "family / draw reqs", "unknown W", "family / known W"], rws)))

    rws = []
    for name in ["design-v2", "design-g"]:
        for key, v in summary["breakdowns"][name]["designFamily"].items():
            rws.append([name, key, v["draws"], pct(v["success"], v["draws"]), v["hard"], v["near"],
                        pct(v["family"], v["draws"]), pct(v["family"], v["draws"] - v["unknown"])])
    tables.append(("T2.designFamily Family by design.family", md_table(
        ["journal", "design.family", "draw reqs", "succ rate", "hard", "near",
         "family / draw reqs", "family / known W"], rws)))

    rws = []
    for name in ["design-v2", "design-g"]:
        for key, v in sorted(summary["breakdowns"][name]["idSuffix"].items()):
            rws.append([name, key, v["draws"], pct(v["success"], v["draws"]), v["hard"], v["near"],
                        pct(v["family"], v["draws"]), pct(v["family"], v["draws"] - v["unknown"])])
    tables.append(("T2.idSuffix Family by the -w{steam}p{pa}d{draws} cell", md_table(
        ["journal", "cell", "draw reqs", "succ rate", "hard", "near", "family / draw reqs",
         "family / known W"], rws)))

    rws = []
    for key, v in summary["breakdowns"]["codex"]["split"].items():
        rws.append(["codex", key, v["draws"], pct(v["success"], v["draws"]), v["hard"], v["near"],
                    v["unknown"], pct(v["family"], v["draws"]),
                    pct(v["family"], v["draws"] - v["unknown"])])
    tables.append(("T2.split Codex journal by split", md_table(
        ["journal", "split", "draw reqs", "succ rate", "hard", "near", "unknown W",
         "family / draw reqs", "family / known W"], rws)))

    # ---------------------------------------------------- holdout 312
    hold = []
    for r in holdout:
        if r["input"]["drawCount"] == 0:
            continue
        strict = {k: (v["success"] and v["waterQualification"] in STRICT_WATER)
                  for k, v in r["lanes"].items()}
        known = [v["withdrawal"] for v in r["lanes"].values() if v["withdrawal"] is not None]
        solved_w = [r["lanes"][k]["withdrawal"] for k in r["lanes"]
                    if strict.get(k) and r["lanes"][k]["withdrawal"] is not None]
        w_max = max(known) if known else None
        w_best = min(solved_w) if solved_w else (min(known) if known else None)
        hold.append({"id": r["id"], "input": r["input"], "strict": strict,
                     "wMax": w_max, "wBest": w_best, "anyStrict": any(strict.values())})

    def cls(w):
        if w is None:
            return "unknown"
        return HARD if w >= 1.0 else NEAR if w >= 0.8 else "other"

    summary["holdout312"] = {
        "requests": len(holdout),
        "drawRequests": len(hold),
        "byWorstLane": {k: sum(1 for h in hold if cls(h["wMax"]) == k)
                        for k in (HARD, NEAR, "other", "unknown")},
        "byBestLane": {k: sum(1 for h in hold if cls(h["wBest"]) == k)
                       for k in (HARD, NEAR, "other", "unknown")},
        "worstLaneHardSolvedStrict": sum(1 for h in hold if cls(h["wMax"]) == HARD and h["anyStrict"]),
        "worstLaneNearSolvedStrict": sum(1 for h in hold if cls(h["wMax"]) == NEAR and h["anyStrict"]),
        "bestLaneHardSolvedStrict": sum(1 for h in hold if cls(h["wBest"]) == HARD and h["anyStrict"]),
        "familySolvedStrictPerLane": {
            lane: sum(1 for h in hold if cls(h["wMax"]) in (HARD, NEAR) and h["strict"].get(lane))
            for lane in ("current", "neural", "neuralFirst")},
        "allStrictPerLane": {
            lane: sum(1 for r in holdout
                      if r["lanes"].get(lane, {}).get("success")
                      and r["lanes"][lane].get("waterQualification") in STRICT_WATER)
            for lane in ("current", "neural", "neuralFirst")},
    }
    rws = [["worst lane (max W over the three lanes)"] +
           [summary["holdout312"]["byWorstLane"][k] for k in (HARD, NEAR, "other", "unknown")] +
           [summary["holdout312"]["worstLaneHardSolvedStrict"],
            summary["holdout312"]["worstLaneNearSolvedStrict"]],
           ["best lane (W of a strictly solved lane, else min W)"] +
           [summary["holdout312"]["byBestLane"][k] for k in (HARD, NEAR, "other", "unknown")] +
           [summary["holdout312"]["bestLaneHardSolvedStrict"], ""]]
    tables.append(("T10 The 312-request holdout: family size depends on which lane you read",
                   md_table(["withdrawal read from", "hard >=1", "near 0.8-1", "other",
                             "unknown", "hard solved strictly by some lane",
                             "near solved strictly by some lane"], rws)))

    # ========================================================== task 2
    summary["successByWithdrawalBand"] = {}
    for name in JOURNALS:
        per = defaultdict(lambda: [0, 0])
        for r in data[name]:
            if r["input"]["drawCount"] == 0 or r["withdrawal"] is None:
                continue
            slot = per[band_of(r["withdrawal"])]
            slot[0] += 1
            slot[1] += 1 if r["success"] else 0
        summary["successByWithdrawalBand"][name] = {
            k: {"n": v[0], "success": v[1], "rate": v[1] / v[0]} for k, v in per.items()}
    order = [band_of(lo + 1e-9) for lo, _ in BANDS_W]
    rws = []
    for b in order:
        row = [b]
        for name in JOURNALS:
            v = summary["successByWithdrawalBand"][name].get(b)
            row += [v["n"] if v else 0, pct(v["success"], v["n"]) if v else "n/a"]
        rws.append(row)
    tables.append(("T3 Success rate by measured final-state withdrawal band", md_table(
        ["withdrawal band"] + [c for name in JOURNALS for c in (f"{name} n", f"{name} succ")], rws)))

    # ========================================================== task 3
    pool = [r for name in JOURNALS for r in data[name]
            if r["input"]["drawCount"] > 0 and r["withdrawal"] is not None]
    journal_of = np.array([r["journal"] for r in pool])

    def label_of(r):
        if r["withdrawal"] >= 1.0:
            return "hard"
        if r["withdrawal"] >= 0.8 and not r["success"]:
            return "nearfail"
        if r["success"]:
            return "success"
        return "otherfail"

    labels = np.array([label_of(r) for r in pool])

    lam_grid = [30_000.0, 40_000.0, 50_000.0]
    vf_grid = [0.0, 0.3, 0.6, 0.9]

    proxies = {"rho": [rho_screen(r["input"])[0] for r in pool]}
    for lam in lam_grid:
        for vf in vf_grid:
            proxies[f"proxy1_l{int(lam/1000)}_vf{vf}"] = [proxy1(r["input"], lam, vf)[0] for r in pool]
            proxies[f"proxy2_l{int(lam/1000)}_vf{vf}"] = [proxy2(r["input"], lam, vf) for r in pool]
        proxies[f"proxy3_l{int(lam/1000)}"] = [proxy3(r["input"], lam) for r in pool]
    proxies["sigma_l60"] = [sigma(r["input"], 60_000.0)[0] for r in pool]
    proxies["sigma_l30"] = [sigma(r["input"], 30_000.0)[0] for r in pool]
    proxies["sigma_flashLambda"] = [sigma(r["input"], use_flash_lambda=True)[0] for r in pool]
    proxies["drawsOverVfF"] = [r["input"]["drawTotal"]
                               / max(1e-9, r["input"]["vfFeed"] * r["input"]["feedTotal"])
                               for r in pool]
    proxy_arrays = {k: np.where(np.isfinite(np.array(v, dtype=float)),
                                np.array(v, dtype=float), 1e12) for k, v in proxies.items()}

    def evaluate(values, threshold, mask=None):
        m = np.ones(len(pool), dtype=bool) if mask is None else mask
        hard = (labels == "hard") & m
        nearf = (labels == "nearfail") & m
        succ = (labels == "success") & m
        v = values
        worst = v[succ]
        worst = worst[np.isfinite(worst) & (worst < 1e12)]
        return {
            "hard": int(hard.sum()),
            "hardHits": int((v[hard] >= threshold).sum()),
            "hardRecall": float((v[hard] >= threshold).mean()) if hard.sum() else None,
            "near": int(nearf.sum()),
            "nearHits": int((v[nearf] >= threshold).sum()),
            "nearRecall": float((v[nearf] >= threshold).mean()) if nearf.sum() else None,
            "successes": int(succ.sum()),
            "falsePositives": int((v[succ] >= threshold).sum()),
            "falsePositiveRate": float((v[succ] >= threshold).mean()) if succ.sum() else None,
            "worstSolved": float(worst.max()) if len(worst) else None,
        }

    summary["proxySweep"] = {k: {str(t): evaluate(v, t) for t in THRESHOLDS}
                             for k, v in proxy_arrays.items()}
    summary["proxyAuc"] = {}
    summary["proxyOperatingPoints"] = {}
    hard_or_success = (labels == "hard") | (labels == "success")
    family_or_success = (labels != "nearfail") | True  # every row is used below via masks
    y_hard = (labels[hard_or_success] == "hard").astype(int)
    fam_mask = (labels == "hard") | (labels == "nearfail") | (labels == "success")
    y_fam = np.isin(labels[fam_mask], ["hard", "nearfail"]).astype(int)
    for k, v in proxy_arrays.items():
        summary["proxyAuc"][k] = {
            "hardVsSuccess": tie_aware_auc(v[hard_or_success], y_hard),
            "familyVsSuccess": tie_aware_auc(v[fam_mask], y_fam),
        }
        summary["proxyOperatingPoints"][k] = {
            "hard@0.5%FP": best_threshold_at_fp(v[hard_or_success], y_hard, 0.005),
            "hard@2%FP": best_threshold_at_fp(v[hard_or_success], y_hard, 0.02),
            "family@0.5%FP": best_threshold_at_fp(v[fam_mask], y_fam, 0.005),
            "family@2%FP": best_threshold_at_fp(v[fam_mask], y_fam, 0.02),
            "worstSolved": evaluate(v, math.inf)["worstSolved"],
        }

    # the brief's headline table
    headline = ["rho", "proxy1_l40_vf0.3", "proxy1_l30_vf0.0", "proxy1_l50_vf0.9",
                "proxy2_l40_vf0.3", "proxy3_l40", "drawsOverVfF", "sigma_l60"]
    rws = []
    for pname in headline:
        for t in THRESHOLDS:
            e = summary["proxySweep"][pname][str(t)]
            rws.append([pname, t, f"{e['hardHits']}/{e['hard']}", pct(e["hardHits"], e["hard"]),
                        f"{e['nearHits']}/{e['near']}", pct(e["nearHits"], e["near"]),
                        f"{e['falsePositives']}/{e['successes']}",
                        pct(e["falsePositives"], e["successes"]), fmt(e["worstSolved"])])
    tables.append(("T4 Request-only predictor sweep, pooled over the three classical journals",
                   md_table(["proxy", "threshold", "hard hits", "hard recall", "near-fail hits",
                             "near recall", "false positives", "FP rate", "worst solved value"],
                            rws)))

    rws = []
    for pname in sorted(proxy_arrays):
        a = summary["proxyAuc"][pname]
        op = summary["proxyOperatingPoints"][pname]
        rws.append([pname, fmt(a["hardVsSuccess"], 3), fmt(a["familyVsSuccess"], 3),
                    fmt(op["worstSolved"]),
                    fmt(op["hard@0.5%FP"]["recall"], 3), fmt(op["hard@0.5%FP"]["threshold"]),
                    fmt(op["hard@2%FP"]["recall"], 3), fmt(op["hard@2%FP"]["threshold"]),
                    fmt(op["family@2%FP"]["recall"], 3), fmt(op["family@2%FP"]["threshold"])])
    tables.append(("T5 Every proxy at its best threshold under a false-positive cap "
                   "(pooled, false positives counted on solvable requests)", md_table(
                       ["proxy", "AUC hard vs solved", "AUC family vs solved", "worst solved value",
                        "hard recall @<=0.5% FP", "threshold", "hard recall @<=2% FP", "threshold",
                        "family recall @<=2% FP", "threshold"], rws)))

    # V_top degeneracy of the brief's proxy1/proxy2
    rws = []
    for lam in lam_grid:
        for vf in vf_grid:
            zero = sum(1 for r in pool if energy_estimates(r["input"], lam, vf)[0] <= 0.0)
            zero_succ = sum(1 for r, l in zip(pool, labels)
                            if l == "success" and energy_estimates(r["input"], lam, vf)[0] <= 0.0)
            rws.append([int(lam / 1000), vf, zero, pct(zero, len(pool)), zero_succ,
                        pct(zero_succ, int((labels == "success").sum()))])
    tables.append(("T6 The brief's V_top = Q/lambda + vf*F + S - cooling/lambda collapses to zero "
                   "on most requests, which makes proxy1/proxy2 infinite", md_table(
                       ["lambda kJ/mol", "vf", "requests with V_top = 0", "share of draw requests",
                        "solvable requests with V_top = 0", "share of solvable"], rws)))

    # per-journal behaviour of the recommended statistic
    summary["sigmaPerJournal"] = {}
    rws = []
    for name in JOURNALS:
        m = journal_of == name
        v = proxy_arrays["sigma_l60"]
        hs = m & hard_or_success
        y = (labels[hs] == "hard").astype(int)
        op5 = best_threshold_at_fp(v[hs], y, 0.005)
        op2 = best_threshold_at_fp(v[hs], y, 0.02)
        e = evaluate(v, math.inf, m)
        summary["sigmaPerJournal"][name] = {"auc": tie_aware_auc(v[hs], y),
                                            "hard@0.5%FP": op5, "hard@2%FP": op2,
                                            "worstSolved": e["worstSolved"]}
        rws.append([name, e["hard"], e["successes"], fmt(tie_aware_auc(v[hs], y), 3),
                    fmt(e["worstSolved"]), fmt(op5["recall"], 3), fmt(op5["threshold"]),
                    fmt(op2["recall"], 3), fmt(op2["threshold"])])
    tables.append(("T7 The recommended statistic sigma, evaluated inside each journal separately",
                   md_table(["journal", "hard", "solvable", "AUC", "worst solved sigma",
                             "hard recall @<=0.5% FP", "threshold", "hard recall @<=2% FP",
                             "threshold"], rws)))

    # pooled fine sweep of the recommended statistic
    fine = [0.40, 0.45, 0.50, 0.55, 0.60, 0.66, 0.70, 0.75, 0.82, 0.85, 0.90, 1.00, 1.20, 1.50]
    summary["sigmaFineSweep"] = {str(t): evaluate(proxy_arrays["sigma_l60"], t) for t in fine}
    rws = []
    for t in fine:
        e = summary["sigmaFineSweep"][str(t)]
        rws.append([fmt(t), f"{e['hardHits']}/{e['hard']}", pct(e["hardHits"], e["hard"]),
                    f"{e['nearHits']}/{e['near']}", pct(e["nearHits"], e["near"]),
                    f"{e['falsePositives']}/{e['successes']}",
                    pct(e["falsePositives"], e["successes"])])
    tables.append(("T7b Pooled threshold sweep of the recommended statistic sigma", md_table(
        ["threshold", "hard hits", "hard recall", "near-fail hits", "near recall",
         "false positives on solvable", "FP rate"], rws)))

    # sigma at fixed thresholds, per journal
    rws = []
    for t in [0.5, 0.6, 0.66, 0.7, 0.8, 1.0, 1.5]:
        for name in JOURNALS:
            e = evaluate(proxy_arrays["sigma_l60"], t, journal_of == name)
            rws.append([fmt(t), name, f"{e['hardHits']}/{e['hard']}", pct(e["hardHits"], e["hard"]),
                        f"{e['nearHits']}/{e['near']}", f"{e['falsePositives']}/{e['successes']}",
                        pct(e["falsePositives"], e["successes"])])
    tables.append(("T8 sigma at fixed thresholds, per journal", md_table(
        ["threshold", "journal", "hard hits", "hard recall", "near-fail hits", "false positives",
         "FP rate"], rws)))

    # --------------------------------------------- calibration on converged liquid
    fit_rows, fit_y = [], []
    for r in pool:
        if not r["success"] or not r.get("liquidTotals"):
            continue
        inp = r["input"]
        liquid = r["liquidTotals"]
        R, F, vf = inp["reflux"], inp["feedTotal"], inp["vfFeed"]
        for tray, rate in inp["draws"]:
            if tray >= len(liquid) or liquid[tray] <= 0:
                continue
            above = sum(d for t, d in inp["draws"] if t < tray)
            k = R / (R + 1.0)
            fit_rows.append([
                k * (inp["reboilerWatts"] or 0.0) / 1e6,
                k * vf * F,
                k * inp["steamTotal"],
                cooling_above_watts(inp, tray) / 1e6,
                above,
                (1.0 - vf) * F * (1.0 if tray >= inp["feedStageNumber"] else 0.0),
                1.0,
            ])
            fit_y.append(liquid[tray])
    A_fit = np.array(fit_rows, dtype=float)
    y_fit = np.array(fit_y, dtype=float)
    coef, *_ = np.linalg.lstsq(A_fit, y_fit, rcond=None)
    pred = A_fit @ coef
    r2_lin = 1.0 - float(((y_fit - pred) ** 2).sum() / ((y_fit - y_fit.mean()) ** 2).sum())
    logA = np.column_stack([np.log(np.maximum(A_fit[:, :4], 1e-6)), A_fit[:, 4:5],
                            np.log(np.maximum(A_fit[:, 5:6], 1e-6)), A_fit[:, 6:7]])
    coef_log, *_ = np.linalg.lstsq(logA, np.log(y_fit), rcond=None)
    pred_log = logA @ coef_log
    r2_log = 1.0 - float(((np.log(y_fit) - pred_log) ** 2).sum()
                         / ((np.log(y_fit) - np.log(y_fit).mean()) ** 2).sum())
    ratio = np.exp(pred_log) / y_fit
    summary["calibration"] = {
        "drawTraySamples": int(len(y_fit)),
        "features": ["k*Q(MW)", "k*vf*F", "k*S", "coolingAbove(MW)", "drawsAbove",
                     "(1-vf)*F below feed tray", "intercept"],
        "linearCoefficients": [float(c) for c in coef],
        "linearR2": r2_lin,
        "logR2": r2_log,
        "logFitRatioQuantiles": {q: float(np.quantile(ratio, q)) for q in (0.05, 0.25, 0.5, 0.75,
                                                                          0.95)},
        "liquidQuantiles": {q: float(np.quantile(y_fit, q)) for q in (0.0, 0.05, 0.5, 0.95, 1.0)},
    }
    # calibrated withdrawal estimate from the log fit
    def calibrated_w(inp):
        R, F, vf = inp["reflux"], inp["feedTotal"], inp["vfFeed"]
        k = R / (R + 1.0)
        worst = 0.0
        for tray, rate in inp["draws"]:
            above = sum(d for t, d in inp["draws"] if t < tray)
            x = np.array([
                math.log(max(k * (inp["reboilerWatts"] or 0.0) / 1e6, 1e-6)),
                math.log(max(k * vf * F, 1e-6)),
                math.log(max(k * inp["steamTotal"], 1e-6)),
                math.log(max(cooling_above_watts(inp, tray) / 1e6, 1e-6)),
                above,
                math.log(max((1.0 - vf) * F * (1.0 if tray >= inp["feedStageNumber"] else 0.0),
                             1e-6)),
                1.0])
            l_hat = math.exp(float(x @ coef_log))
            worst = max(worst, rate / l_hat if l_hat > 0 else math.inf)
        return worst

    proxy_arrays["calibratedW"] = np.array([calibrated_w(r["input"]) for r in pool])
    summary["proxyAuc"]["calibratedW"] = {
        "hardVsSuccess": tie_aware_auc(proxy_arrays["calibratedW"][hard_or_success], y_hard),
        "familyVsSuccess": tie_aware_auc(proxy_arrays["calibratedW"][fam_mask], y_fam)}
    summary["proxyOperatingPoints"]["calibratedW"] = {
        "hard@0.5%FP": best_threshold_at_fp(proxy_arrays["calibratedW"][hard_or_success], y_hard,
                                            0.005),
        "hard@2%FP": best_threshold_at_fp(proxy_arrays["calibratedW"][hard_or_success], y_hard,
                                          0.02),
        "family@2%FP": best_threshold_at_fp(proxy_arrays["calibratedW"][fam_mask], y_fam, 0.02),
        "worstSolved": evaluate(proxy_arrays["calibratedW"], math.inf)["worstSolved"]}
    summary["proxySweep"]["calibratedW"] = {str(t): evaluate(proxy_arrays["calibratedW"], t)
                                            for t in THRESHOLDS}

    rws = [[n, fmt(c)] for n, c in zip(summary["calibration"]["features"], coef)]
    tables.append(("T9 Linear calibration of the converged liquid at a draw tray against "
                   "request-only terms", md_table(["term", "coefficient (mol/s per unit)"], rws)))

    # ========================================================== task 4
    summary["classes"] = {}
    rws = []
    for name in JOURNALS:
        cls_all = Counter(request_class(r) for r in data[name])
        dopen = [r for r in data[name] if request_class(r) == "D-open"]
        dopen_draws = [r for r in dopen if r["input"]["drawCount"] > 0]
        dopen_fail = [r for r in dopen_draws if not r["success"]]
        fam = [r for r in dopen_draws
               if r["withdrawal"] is not None and r["withdrawal"] >= 0.8]
        famfail = [r for r in fam if not r["success"]]
        known = [r for r in dopen_draws if r["withdrawal"] is not None]
        summary["classes"][name] = {
            "all": dict(cls_all),
            "dOpenAll": len(dopen),
            "dOpenSuccesses": sum(1 for r in dopen if r["success"]),
            "dOpenDraws": len(dopen_draws),
            "dOpenDrawFailures": len(dopen_fail),
            "dOpenFamily": len(fam),
            "dOpenFamilyFailures": len(famfail),
            "dOpenKnownW": len(known),
            "familyShareOfDOpenDrawFailures": (len(famfail) / len(dopen_fail)) if dopen_fail else None,
            "familyShareOfDOpenKnownW": (len(fam) / len(known)) if known else None,
        }
        rws.append([name, cls_all.get("zero-boilup", 0), cls_all.get("heat-gated", 0),
                    cls_all.get("typed-infeasible", 0), cls_all.get("D-open", 0),
                    len(dopen_draws), len(dopen_fail), len(famfail),
                    pct(len(famfail), len(dopen_fail)), pct(len(fam), len(known))])
    tables.append(("T11 Request classes and the family's share of the D-open draw failures",
                   md_table(["journal", "zero-boil-up", "heat-gated", "typed infeasible", "D open",
                             "D-open draw reqs", "D-open draw failures", "of which family",
                             "family / D-open draw failures", "family / D-open draws with known W"],
                            rws)))

    corner = {}
    for name in JOURNALS:
        dopen_draws = [r for r in data[name]
                       if request_class(r) == "D-open" and r["input"]["drawCount"] > 0
                       and r["withdrawal"] is not None]
        fam = [r for r in dopen_draws if r["withdrawal"] >= 0.8]
        solved = [r for r in dopen_draws if r["success"]]

        def stats(rows_):
            if not rows_:
                return {}
            med = lambda f: float(np.median([f(r) for r in rows_]))  # noqa: E731
            return {
                "n": len(rows_),
                "Q_MW": med(lambda r: (r["input"]["reboilerWatts"] or 0) / 1e6),
                "R": med(lambda r: r["input"]["reflux"]),
                "F": med(lambda r: r["input"]["feedTotal"]),
                "drawOverFeed": med(lambda r: r["input"]["drawTotal"] / r["input"]["feedTotal"]),
                "steamShare": float(np.mean([1.0 if r["input"]["steamCount"] > 0 else 0.0
                                             for r in rows_])),
                "steamTotal": med(lambda r: r["input"]["steamTotal"]),
                "coolingMW": med(lambda r: total_cooling_watts(r["input"]) / 1e6),
                "paCount": med(lambda r: r["input"]["pumparoundCount"]),
                "drawCount": med(lambda r: r["input"]["drawCount"]),
                "stages": med(lambda r: r["input"]["stageCount"]),
                "condT": med(lambda r: r["input"]["condenserKelvin"]),
                "feedT": med(lambda r: r["input"]["feedTemperatureKelvin"]),
                "vfFeed": med(lambda r: r["input"]["vfFeed"]),
                "rho": med(lambda r: rho_screen(r["input"])[0]),
                "sigma": med(lambda r: sigma(r["input"])[0]),
            }
        corner[name] = {"family": stats(fam), "solved": stats(solved)}
    summary["corners"] = corner
    fields = ["n", "Q_MW", "R", "drawOverFeed", "steamShare", "steamTotal", "coolingMW", "paCount",
              "drawCount", "stages", "condT", "feedT", "vfFeed", "rho", "sigma"]
    rws = []
    for name in JOURNALS:
        for group in ("family", "solved"):
            s = corner[name][group]
            if not s:
                continue
            rws.append([name, group] + [fmt(s[f], 4) for f in fields])
    tables.append(("T12 Median request parameters, liquid-depletion family vs solved requests, "
                   "within the D-open draw population", md_table(
                       ["journal", "group", "n", "Q (MW)", "R", "draws/F", "steam share",
                        "steam mol/s", "cooling (MW)", "pumparounds", "draws", "stages",
                        "condenser T (K)", "feed T (K)", "vf feed", "rho", "sigma"], rws)))

    rws = []
    for name in JOURNALS:
        dopen = [r for r in data[name]
                 if request_class(r) == "D-open" and r["input"]["drawCount"] > 0
                 and r["withdrawal"] is not None]
        if not dopen:
            continue
        fam = np.array([1.0 if r["withdrawal"] >= 0.8 else 0.0 for r in dopen])
        succ = np.array([1.0 if r["success"] else 0.0 for r in dopen])
        for label, values in [
                ("Q (MW)", np.array([(r["input"]["reboilerWatts"] or 0) / 1e6 for r in dopen])),
                ("R", np.array([r["input"]["reflux"] for r in dopen])),
                ("cooling (MW)", np.array([total_cooling_watts(r["input"]) / 1e6 for r in dopen])),
                ("condenser T (K)", np.array([r["input"]["condenserKelvin"] for r in dopen])),
                ("vf feed", np.array([r["input"]["vfFeed"] for r in dopen])),
                ("draws / F", np.array([r["input"]["drawTotal"] / r["input"]["feedTotal"]
                                        for r in dopen]))]:
            edges = np.quantile(values, [0, 0.25, 0.5, 0.75, 1.0])
            for i in range(4):
                m = (values >= edges[i]) & ((values <= edges[i + 1]) if i == 3
                                            else (values < edges[i + 1]))
                if m.sum() == 0:
                    continue
                rws.append([name, label, f"Q{i + 1} [{edges[i]:.3g},{edges[i + 1]:.3g}]",
                            int(m.sum()), pct(int(fam[m].sum()), int(m.sum())),
                            pct(int(succ[m].sum()), int(m.sum()))])
    # where the draws sit: the design samples draw trays uniformly over 1..N
    def shallow_band(r):
        t = min(t_ for t_, _ in r["input"]["draws"])
        return "tray 1" if t == 1 else "trays 2-3" if t <= 3 else "trays 4-10" if t <= 10 \
            else "tray >10"

    summary["breakdownShallowestDraw"] = {name: breakdown(data[name], shallow_band)
                                          for name in JOURNALS}
    rws = []
    for name in JOURNALS:
        for key in ("tray 1", "trays 2-3", "trays 4-10", "tray >10"):
            v = summary["breakdownShallowestDraw"][name].get(key)
            if not v:
                continue
            rws.append([name, key, v["draws"], pct(v["success"], v["draws"]), v["hard"], v["near"],
                        pct(v["family"], v["draws"]),
                        pct(v["family"], v["draws"] - v["unknown"])])
    tables.append(("T13b Family rate by the shallowest authored draw tray", md_table(
        ["journal", "shallowest draw", "draw reqs", "succ rate", "hard", "near",
         "family / draw reqs", "family / known W"], rws)))

    tables.append(("T13 Family rate across quartiles of each design knob "
                   "(D-open draw requests with a known withdrawal)",
                   md_table(["journal", "knob", "quartile", "n", "family rate", "success rate"],
                            rws)))

    # ========================================================== task 5
    def rule_removal(stat_fn, t):
        out = {}
        for name in JOURNALS:
            rows_ = [r for r in data[name] if r["input"]["drawCount"] > 0]
            removed = removed_success = removed_unknown = removed_family = 0
            kept_family = kept_success = 0
            for r in rows_:
                v = stat_fn(r["input"])
                fam = r["withdrawal"] is not None and r["withdrawal"] >= 0.8
                if v >= t:
                    removed += 1
                    removed_success += 1 if r["success"] else 0
                    removed_unknown += 1 if r["withdrawal"] is None else 0
                    removed_family += 1 if fam else 0
                else:
                    kept_family += 1 if fam else 0
                    kept_success += 1 if r["success"] else 0
            out[name] = {"drawRequests": len(rows_), "removed": removed,
                         "removedFamily": removed_family,
                         "removedSolvable": removed_success,
                         "removedUnknownW": removed_unknown,
                         "residualFamily": kept_family, "keptSolvable": kept_success}
        return out

    candidates = {
        "rho >= 0.30 (shipped screen)": (lambda i: rho_screen(i)[0], 0.30),
        "sigma >= 0.66": (lambda i: sigma(i)[0], 0.66),
        "sigma >= 0.55": (lambda i: sigma(i)[0], 0.55),
        "sigma >= 0.85": (lambda i: sigma(i)[0], 0.85),
        "sigma >= 1.0": (lambda i: sigma(i)[0], 1.0),
        "drawTotal >= 0.9*vf*F": (lambda i: i["drawTotal"] / max(1e-9, i["vfFeed"]
                                                                 * i["feedTotal"]), 0.9),
    }
    summary["ruleRemoval"] = {k: rule_removal(fn, t) for k, (fn, t) in candidates.items()}
    rws = []
    for key, per in summary["ruleRemoval"].items():
        for name in JOURNALS:
            v = per[name]
            rws.append([key, name, v["drawRequests"], v["removed"], v["removedFamily"],
                        v["removedSolvable"], pct(v["removedSolvable"], v["keptSolvable"]
                                                  + v["removedSolvable"]),
                        v["removedUnknownW"], v["residualFamily"]])
    tables.append(("T14 What each candidate rule removes from the draw population as authored",
                   md_table(["rule", "journal", "draw reqs", "removed", "family removed",
                             "solvable removed", "solvable removed (share of all solvable)",
                             "unknown-W removed", "residual family"], rws)))

    # sigma on the 312-request holdout
    hold_sigma = []
    for h in hold:
        s = sigma(h["input"])[0]
        hold_sigma.append((h, s))
    for t in (0.55, 0.66, 0.85):
        fired = [h for h, s in hold_sigma if s >= t]
        summary.setdefault("holdoutSigma", {})[str(t)] = {
            "fired": len(fired),
            "firedSolvedStrict": sum(1 for h in fired if h["anyStrict"]),
            "firedWorstLaneHard": sum(1 for h in fired if cls(h["wMax"]) == HARD),
            "firedBestLaneHard": sum(1 for h in fired if cls(h["wBest"]) == HARD),
            "totalWorstLaneHard": sum(1 for h in hold if cls(h["wMax"]) == HARD),
            "totalSolvedStrict": sum(1 for h in hold if h["anyStrict"]),
        }
    rws = []
    for t, v in summary["holdoutSigma"].items():
        rws.append([t, v["fired"], f"{v['firedWorstLaneHard']}/{v['totalWorstLaneHard']}",
                    f"{v['firedSolvedStrict']}/{v['totalSolvedStrict']}",
                    pct(v["firedSolvedStrict"], v["totalSolvedStrict"])])
    tables.append(("T15 sigma applied to the 312-request holdout (252 draw requests)", md_table(
        ["threshold", "requests fired", "worst-lane hard caught", "strictly solved caught",
         "FP rate on strictly solved"], rws)))

    with open(os.path.join(HERE, "summary.json"), "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=1, default=float)
    with open(os.path.join(HERE, "tables.md"), "w", encoding="utf-8") as handle:
        for title, table in tables:
            handle.write(f"### {title}\n\n{table}\n\n")
    print("wrote summary.json and tables.md")
    print(json.dumps(summary["counts"], indent=1))
    print(json.dumps(summary["sigmaPerJournal"], indent=1, default=float))
    print(json.dumps(summary["calibration"], indent=1, default=float))
    print(json.dumps(summary["holdout312"], indent=1, default=float))
    print(json.dumps(summary["holdoutSigma"], indent=1, default=float))


if __name__ == "__main__":
    main()

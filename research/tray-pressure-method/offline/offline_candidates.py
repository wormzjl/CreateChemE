"""Offline test of lagged-state pressure predictors against the Codex 50-case journal (no new solves)."""
import json, glob, math, statistics as st, os, sys
CODEX = "C:/Users/wormz/.codex/worktrees/tray-pressure-study/CreateChemE"
STUDY = CODEX + "/experiments/tray-pressure-study"
MAT = CODEX + "/src/main/resources/data/createcheme/materials"
R, MWW = 8.314462618, .01801528
pkg = json.load(open(MAT + "/packages/tjl19.json"))
props = {}
for f in glob.glob(MAT + "/properties/*.json"):
    d = json.load(open(f)); props[d["id"]] = d
mw = [props[p]["molecular_weight_kg_per_mol"] for p in pkg["properties"]]
cal = json.load(open(STUDY + "/full/metadata.json"))["calibration"]
rows = [json.loads(l) for l in open(STUDY + "/full/cases.jsonl")]

def steam(inp, j): return sum(s["molarFlowMolPerSecond"] for s in inp["steamFeeds"] if s["stageNumber"] >= j)
def traffic(inp, seed):
    """per tray j: vapor mol (incl water), vapor mass, liquid volume, T"""
    n = inp["stageCount"]; out = {}
    v, l, t, w = seed["vapor"], seed["liquid"], seed["temperatures"], seed["freeWater"]
    for j in range(1, n + 1):
        water = steam(inp, j) + (w[j - 1] if j >= 2 else 0)
        nv = sum(v[j]) + water; mv = sum(a * b for a, b in zip(v[j], mw)) + water * MWW
        ql = sum(a * b for a, b in zip(l[j], mw)) / 800 + w[j] * MWW / 1000
        out[j] = (nv, mv, ql, t[j])
    return out
def drop_terms(nv, mv, ql, T, P):
    qv = nv * R * T / P; rho = mv / qv
    return 100 + 450 * (rho / cal["vaporRho"]) * (qv / cal["vaporQ"]) ** 2 + 200 * (ql / cal["liquidQ"]) ** (2 / 3)
def naive(inp, seed, P):
    n = inp["stageCount"]; tr = traffic(inp, seed); p = [0] * (n + 2); p[0] = p[1] = inp["topPressurePascal"]
    for j in range(2, n + 1): p[j] = p[j - 1] + drop_terms(*tr[j], P[j])
    p[n + 1] = p[n]; return p
def march(inp, seed, Pstate, tcorr=0.0):
    """pressure-consistent: dry term ~ A/P[j]; solve quadratic per tray. tcorr = dlnT/dlnP correction."""
    n = inp["stageCount"]; tr = traffic(inp, seed); p = [0] * (n + 2); p[0] = p[1] = inp["topPressurePascal"]
    for j in range(2, n + 1):
        nv, mv, ql, T = tr[j]
        pj = p[j - 1] + 750
        for _ in range(30):
            Tj = T * (pj / Pstate[j]) ** tcorr
            new = p[j - 1] + drop_terms(nv, mv, ql, Tj, pj)
            if abs(new - pj) < 1e-6: pj = new; break
            pj = 0.5 * (pj + new)
        p[j] = pj
    p[n + 1] = p[n]; return p
def err(p, ref, n):
    tot = 100 * ((p[n] - p[1]) - (ref[n] - ref[1])) / (ref[n] - ref[1])
    mx = max(abs(p[j] - ref[j]) for j in range(1, n + 1)); mr = max(100 * abs(p[j] - ref[j]) / ref[j] for j in range(1, n + 1))
    return tot, mx, mr
cands = {"C1 naive(fixed)": [], "C2 march(fixed)": [], "C5 march+T(fixed)": [], "C3 naive(frozen)": [], "C4 march(frozen)": [], "C6 march+T(frozen)": [], "C0 codex frozen": [], "fixed750": []}
detail = []
for r in rows:
    if "errors" not in r: continue
    inp = r["input"]; n = inp["stageCount"]; ref = r["reference"]["last"]["pressuresPa"]
    fz, fx = r["frozen"], r["fixed"]
    cands["C0 codex frozen"].append(err(fz["pressuresPa"], ref, n))
    cands["C3 naive(frozen)"].append(err(naive(inp, fz["seed"], fz["pressuresPa"]), ref, n))
    cands["C4 march(frozen)"].append(err(march(inp, fz["seed"], fz["pressuresPa"]), ref, n))
    cands["C6 march+T(frozen)"].append(err(march(inp, fz["seed"], fz["pressuresPa"], 0.10), ref, n))
    if fx["status"] == "SUCCESS":
        cands["fixed750"].append(err(fx["pressuresPa"], ref, n))
        cands["C1 naive(fixed)"].append(err(naive(inp, fx["seed"], fx["pressuresPa"]), ref, n))
        c2 = err(march(inp, fx["seed"], fx["pressuresPa"]), ref, n); cands["C2 march(fixed)"].append(c2)
        cands["C5 march+T(fixed)"].append(err(march(inp, fx["seed"], fx["pressuresPa"], 0.10), ref, n))
        detail.append((r["id"], round(err(fx["pressuresPa"], ref, n)[0], 1), round(c2[0], 2), round((ref[n] - ref[1]) / (n - 1)), round(ref[n]/1000,1)))
def pct(a, q):
    a = sorted(a); k = (len(a) - 1) * q; lo = int(k); hi = min(lo + 1, len(a) - 1); return a[lo] + (k - lo) * (a[hi] - a[lo])
print(f"{'candidate':22s} n  | total-drop err %: median|abs| p95|abs| worst|abs| signed-med | max tray P err %: med p95 worst")
for k, v in cands.items():
    a = [abs(x[0]) for x in v]; s = [x[0] for x in v]; m = [x[2] for x in v]
    print(f"{k:22s} {len(v):2d} | {pct(a,.5):7.2f} {pct(a,.95):7.2f} {max(a):7.2f} {st.median(s):8.2f} | {pct(m,.5):6.2f} {pct(m,.95):6.2f} {max(m):6.2f}")
print("\ncase, fixed750 total err %, C2 total err %, reference Pa/tray, reference bottom kPa")
for d in detail: print(d)
# contraction of the damped reference iteration -> implied G'
print("\nimplied G' (from first damped step ratio r: G'=2r-1):")
g = []
for r in rows:
    if "errors" not in r: continue
    t = r["reference"]["trace"]
    if len(t) > 2: g.append(2 * t[1]["maxAbsPa"] / t[0]["maxAbsPa"] - 1)
print("median", round(st.median(g), 3), "min", round(min(g), 3), "max", round(max(g), 3))

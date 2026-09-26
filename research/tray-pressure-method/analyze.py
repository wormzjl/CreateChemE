"""Summarise a tray-pressure-method journal: accuracy, reliability and cost of each arm."""
import json, sys, statistics as st
def pct(a, q):
    a = sorted(a); k = (len(a) - 1) * q; lo = int(k); hi = min(lo + 1, len(a) - 1); return a[lo] + (k - lo) * (a[hi] - a[lo])
def line(name, v, unit=""):
    if not v: print(f"  {name:34s} n=0"); return
    print(f"  {name:34s} n={len(v):2d} median {pct(v,.5):8.2f} p95 {pct(v,.95):8.2f} max {max(v):8.2f} {unit}")
d = sys.argv[1]; rows = [json.loads(l) for l in open(d + "/cases.jsonl")]
print(f"== {d}: {len(rows)} cases")
from collections import Counter
for arm in ("fixed", "ladder"):
    print(f"  {arm} outcomes:", dict(Counter(r[arm]["status"] for r in rows if arm in r)))
print("  oneShot correction outcomes:", dict(Counter(r["oneShot"]["correction"]["status"] for r in rows if "oneShot" in r)),
      "half-step fallbacks:", sum(1 for r in rows if "oneShot" in r and "halfStep" in r["oneShot"]))
print("  reference:", dict(Counter(r["reference"]["status"] for r in rows if "reference" in r)),
      "outer iterations median", st.median([len(r["reference"]["trace"]) for r in rows if "reference" in r]))
trig = [r for r in rows if "ladderTriggered" in r]; fired = [r for r in trig if "correction" in r["ladderTriggered"]]
print(f"  ladder accepted {len(trig)}; correction fired (|mismatch|>5%) on {len(fired)}:", dict(Counter(r['ladderTriggered']['correction']['status'] for r in fired)))
both = [r for r in rows if r["fixed"]["status"] == "SUCCESS" and r["ladder"]["status"] == "SUCCESS"]
print("  fixed-only successes:", [r["id"] for r in rows if r["fixed"]["status"] == "SUCCESS" and r["ladder"]["status"] != "SUCCESS"])
print("  ladder-only successes:", [r["id"] for r in rows if r["fixed"]["status"] != "SUCCESS" and r["ladder"]["status"] == "SUCCESS"])
ok = [r for r in rows if "errors" in r]
print(f"-- accuracy vs converged hydraulic reference ({len(ok)} cases)")
for arm in ("fixed", "oneShot", "ladderRaw", "ladderTriggered"):
    e = [r["errors"][arm] for r in ok if arm in r["errors"]]
    print(f" [{arm}]")
    line("|total dP error| %", [abs(x["totalDropErrorPct"]) for x in e]); line("max tray P error % (absolute P)", [x["maxRelativePct"] for x in e])
    line("max T error K", [x["maxTemperatureK"] for x in e]); line("max composition error pp", [x["maxCompositionPercentagePoints"] for x in e])
    line("max product mass error % feed", [x["maxProductMassErrorPctFeed"] for x in e])
if any("referenceMaxFloodFraction" in r for r in ok):
    inside = [r for r in ok if r["referenceMaxFloodFraction"] <= 1.0]
    print(f"-- within flood limit only ({len(inside)} cases)")
    for arm in ("oneShot", "ladderRaw", "ladderTriggered"):
        line(f"[{arm}] |total dP error| %", [abs(r["errors"][arm]["totalDropErrorPct"]) for r in inside if arm in r["errors"]])
    line("reference drop Pa/tray (all)", [ (r["referenceState"]["pressuresPa"][-2]-r["referenceState"]["pressuresPa"][1])/(r["scenario"]["trays"]-1) for r in ok])
    line("reference max flood fraction", [r["referenceMaxFloodFraction"] for r in ok])
print("-- cost (ms)")
line("fixed cold solve", [r["fixed"]["ms"] for r in both]); line("ladder cold solve", [r["ladder"]["ms"] for r in both])
line("ladder/fixed paired ratio", [r["ladder"]["ms"] / r["fixed"]["ms"] for r in both])
line("ladder hook total per request", [r["ladderHook"]["ms"] for r in both])
one = [r for r in rows if "oneShot" in r and r["oneShot"]["correction"]["status"] == "SUCCESS"]
line("oneShot: march", [r["oneShot"]["marchMs"] for r in one]); line("oneShot: warm correction", [r["oneShot"]["correction"]["ms"] for r in one])
line("oneShot: correction / cold solve", [r["oneShot"]["correction"]["ms"] / r["fixed"]["ms"] for r in one])
line("oneShot: correction Newton its", [r["oneShot"]["correction"]["newtonIterations"] for r in one])
line("oneShot residual mismatch |%|", [abs(r["oneShot"]["residualMismatch"]["totalDropErrorPct"]) for r in one])
line("ladder raw self-mismatch |%|", [abs(r["ladderTriggered"]["mismatch"]["totalDropErrorPct"]) for r in trig])
line("ladder triggered correction ms", [r["ladderTriggered"]["correction"]["ms"] for r in fired])
print("-- per case: id fixed ladder | total dP err %: fixed oneShot ladderRaw ladderTrig | corr ms | flood")
for r in rows:
    e = r.get("errors", {}); g = lambda a: f"{e[a]['totalDropErrorPct']:7.1f}" if a in e else "      -"
    cm = r.get("oneShot", {}).get("correction", {}).get("ms"); fl = r.get("referenceMaxFloodFraction")
    print(f"  {r['id']} {r['fixed']['status'][:7]:7s} {r['ladder']['status'][:7]:7s} | {g('fixed')} {g('oneShot')} {g('ladderRaw')} {g('ladderTriggered')} | {cm and round(cm)} | {fl and round(fl,2)}")

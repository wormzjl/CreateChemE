"""Summarize the frozen bundle's held-out methane studies without treating repeats as new cases."""
import argparse
import collections
import hashlib
import json
import math
import statistics
from pathlib import Path

MODES = ["CURRENT_ONLY", "LNN_ONLY", "LNN_FIRST"]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def percentile(values, fraction):
    values = sorted(values)
    position = fraction * (len(values) - 1)
    low, high = math.floor(position), math.ceil(position)
    return values[low] + (values[high] - values[low]) * (position - low)


def mode_summary(rows, mode):
    cases = collections.defaultdict(list)
    for row in rows:
        cases[(row["domain"], row["id"])].append(row[mode])
    values = [r[mode] for r in rows]
    return {
        "runs": len(values), "accepted_runs": sum(v["success"] for v in values),
        "cases": len(cases), "cases_accepted_every_repeat": sum(all(v["success"] for v in group) for group in cases.values()),
        "median_ms": statistics.median(v["ms"] for v in values),
        "p95_ms": percentile([v["ms"] for v in values], .95),
        "fallback_runs": sum(any(e.startswith("initializer=CURRENT_BACKUP;")
                                 for e in v.get("diagnostics", {}).get("events", [])) for v in values),
        "water_qualification_by_run": dict(collections.Counter(v.get("waterQualification", "FAILED") for v in values)),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dry", type=Path)
    parser.add_argument("wet", type=Path)
    parser.add_argument("--output", type=Path, default=Path("tools/neural/methane-model-card.json"))
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    all_rows, domains = [], {}
    for domain, root, artifact in [("operating", args.dry, "v3-tjl20-dry.json"),
                                   ("wet_boundary", args.wet, "v3-tjl20-wet.json")]:
        cases = [json.loads(line) for line in (root / "cases.jsonl").read_text().splitlines()]
        rows = [json.loads(line) for line in (root / "evaluation-test.jsonl").read_text().splitlines()]
        expected = {r["id"] for r in cases if r["split"] == "test"}
        if {r["id"] for r in rows} != expected:
            raise ValueError("Incomplete held-out evaluation: " + domain)
        seen = set()
        for row in rows:
            key = (row["id"], row["repeat"])
            if key in seen:
                raise ValueError("Duplicate evaluation: " + str(key))
            seen.add(key)
            row["domain"] = domain
        if len({sum(r["id"] == i for r in rows) for i in expected}) != 1:
            raise ValueError("Uneven evaluation repetitions")
        bundled = repo / "src/main/resources/data/createcheme/neural" / artifact
        if sha(root / "model.json") != sha(bundled):
            raise ValueError("Dataset model differs from bundled artifact: " + domain)
        domains[domain] = {
            "training": json.loads((root / "training.json").read_text()),
            "attempted": len(cases), "accepted_teacher_profiles": sum(r["success"] for r in cases),
            "teacher_water_counts": dict(collections.Counter(r.get("waterQualification", "FAILED") for r in cases)),
            "model_sha256": sha(bundled), "model_bytes": bundled.stat().st_size,
            "journal_sha256": sha(root / "cases.jsonl"), "evaluation_sha256": sha(root / "evaluation-test.jsonl"),
            "modes": {mode: mode_summary(rows, mode) for mode in MODES},
        }
        all_rows.extend(rows)
    differences = {"temperature_K": 0., "flow_mol_s": 0., "mole_fraction": 0.}
    pairs, stream_mismatches = 0, 0
    for row in all_rows:
        if not (row["CURRENT_ONLY"]["success"] and row["LNN_ONLY"]["success"]):
            continue
        pairs += 1
        reference = {s["streamId"]: s for s in row["CURRENT_ONLY"]["streams"]}
        predicted = {s["streamId"]: s for s in row["LNN_ONLY"]["streams"]}
        if reference.keys() != predicted.keys():
            stream_mismatches += 1
        for key in reference.keys() & predicted.keys():
            a, b = reference[key], predicted[key]
            differences["temperature_K"] = max(differences["temperature_K"], abs(a["temperatureKelvin"] - b["temperatureKelvin"]))
            differences["flow_mol_s"] = max(differences["flow_mol_s"], abs(a["molarFlowMolPerSecond"] - b["molarFlowMolPerSecond"]))
            fractions = {f["componentId"]: f["moleFraction"] for f in a["moleFractions"]}
            for f in b["moleFractions"]:
                differences["mole_fraction"] = max(differences["mole_fraction"], abs(f["moleFraction"] - fractions[f["componentId"]]))
    card = {
        "bundle": "v3-phase-aware-bundle-v2", "package": "createcheme:tjl20_methane", "property_revision": "tjl20-methane-nist-r1",
        "new_training_profiles": sum(d["training"]["train"] for d in domains.values()),
        "model_selection": "Training-only normalization/PCA/weights/correlation fit; validation-only epoch and guard selection; frozen before test.",
        "benchmark": "Two warmed repetitions per held-out input, rotating all three modes; 15 s request deadline, shared 2 s neural budget, 16 iterations per native pass.",
        "timing_limits": "One local JVM study per domain; JIT and machine effects remain. Repetitions are not independent operating cases; no broad speedup or confidence-interval claim.",
        "domains": domains, "combined": {mode: mode_summary(all_rows, mode) for mode in MODES},
        "paired_accepted_runs": pairs, "paired_stream_set_mismatches": stream_mismatches,
        "paired_maximum_product_differences": differences if pairs else None,
        "qualification": "Fixed TJL20 geometry. Wet training samples are a coupled one-wet-tray boundary branch; no general wet-zone, new-chemistry or refinery-wide qualification. Runtime legacy dry-supersaturation advisories are explicitly classified.",
        "cases": [{"domain": r["domain"], "id": r["id"], "repeat": r["repeat"],
                   **{m: {k: r[m].get(k) for k in ["success", "ms", "waterQualification", "wetTrayCount"]} for m in MODES}}
                  for r in all_rows],
    }
    args.output.write_text(json.dumps(card, indent=2, allow_nan=False) + "\n")
    print(json.dumps({"combined": card["combined"], "product_differences": card["paired_maximum_product_differences"]}, indent=2))


if __name__ == "__main__":
    main()

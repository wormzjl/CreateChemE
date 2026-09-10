"""Write a compact, reproducible model card from the actual pilot journal and paired correction results."""
import hashlib
import json
import statistics
import sys
from pathlib import Path

root = Path(sys.argv[1])
training = json.loads((root / "training.json").read_text())
evaluation = json.loads((root / "evaluation.json").read_text())
if not evaluation:
    raise ValueError("No held-out evaluations")


def diagnostic(row):
    return row["neural"].get("diagnostics") or {}


def checks(row):
    return diagnostic(row).get("acceptanceAudit", {}).get("checks", [])


pairs = [r for r in evaluation if r["neural"]["success"] and r["current"]["success"]]
temperature_difference = flow_difference = composition_difference = 0.0
for row in pairs:
    reference = {s["streamId"]: s for s in row["current"]["streams"]}
    for predicted in row["neural"]["streams"]:
        actual = reference[predicted["streamId"]]
        temperature_difference = max(temperature_difference, abs(predicted["temperatureKelvin"] - actual["temperatureKelvin"]))
        flow_difference = max(flow_difference, abs(predicted["molarFlowMolPerSecond"] - actual["molarFlowMolPerSecond"]))
        composition = {c["componentId"]: c["moleFraction"] for c in actual["moleFractions"]}
        for c in predicted["moleFractions"]:
            composition_difference = max(composition_difference, abs(c["moleFraction"] - composition[c["componentId"]]))
training.update({
    "model_sha256": hashlib.sha256((root / "model.json").read_bytes()).hexdigest(),
    "held_out_cases": len(evaluation),
    "neural_accepted": sum(r["neural"]["success"] for r in evaluation),
    "current_accepted": sum(r["current"]["success"] for r in evaluation),
    "neural_median_ms": statistics.median(r["neural"]["ms"] for r in evaluation),
    "current_median_ms_all_attempts": statistics.median(r["current"]["ms"] for r in evaluation),
    "paired_accepted_cases": len(pairs),
    "median_paired_speedup": statistics.median(r["current"]["ms"] / r["neural"]["ms"] for r in pairs) if pairs else None,
    "paired_max_product_temperature_difference_K": temperature_difference if pairs else None,
    "paired_max_product_flow_difference_mol_s": flow_difference if pairs else None,
    "paired_max_product_mole_fraction_difference": composition_difference if pairs else None,
    "cases": [{
        "id": r["id"], "neural_accepted": r["neural"]["success"], "current_accepted": r["current"]["success"],
        "neural_ms": r["neural"]["ms"], "current_ms": r["current"]["ms"],
        "neural_scaled_residual": diagnostic(r).get("maximumScaledResidual"),
        "neural_audit_accepted": bool(checks(r)) and all(c["passed"] for c in checks(r)),
        "water_dew_advisory": [c["detail"] for c in checks(r)
                               if c["family"] == "WATER_DEW_POINT" and c["detail"].startswith("warning:")],
    } for r in evaluation],
    "qualification": "Local interpolation MVP only. No refinery-wide, new-chemistry or genuine wet-tray model qualification.",
    "timing_note": "One paired run, alternating method order; includes JIT effects. No confidence interval or broad speedup claim.",
})
(root / "model-card.json").write_text(json.dumps(training, indent=2, allow_nan=False))
print(json.dumps({k: v for k, v in training.items() if k != "cases"}, indent=2))

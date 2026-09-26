"""Reproduce the research-only units check against the recorded DWSIM 10.2.5 probe.

The public Letsou-Stiel implementation returns dynamic viscosity in Pa s:
https://github.com/DanWBR/dwsim/blob/windows/DWSIM.Thermodynamics/PropertyPackages/Models/FluidProperties.vb
This does not qualify that correlation for residue or correct production data.
"""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "cold-flow"
probe = json.loads((ROOT / "dwsim-probe.json").read_text())
checks = 0
maximum_error = 0.0
fallback_rows = []
for row in probe["records"]:
    p = row["metadata"]
    tc, pc_bar = p["Critical_Temperature"], p["Critical_Pressure"] / 100000
    mw, omega = p["Molar_Weight"], p["Acentric_Factor"]
    for sample in row["samples"]:
        tr = sample["temperature_kelvin"] / tc
        e0 = (2.648 - 3.725 * tr + 1.309 * tr ** 2) * .001
        e1 = (7.425 - 13.39 * tr + 5.933 * tr ** 2) * .001
        scale = .176 * (tc / (mw ** 3 * pc_bar ** 4)) ** (1 / 6)
        dynamic_mu = (e0 + omega * e1) / scale / 1000
        error = abs(dynamic_mu / sample["letsou_raw"] - 1)
        assert error < 1e-12
        maximum_error = max(maximum_error, error)
        checks += 1
        if sample["twu_kinematic_raw"] == "NaN":
            assert math.isclose(sample["viscosity_pascal_seconds"], dynamic_mu * sample["density_kg_per_cubic_metre"], rel_tol=1e-12)
            if sample["temperature_kelvin"] == 298:
                fallback_rows.append({
                    "component": row["name"],
                    "temperature_kelvin": 298,
                    "reduced_temperature": tr,
                    "letsou_dynamic_viscosity_pascal_seconds": dynamic_mu,
                    "api_reported_dynamic_viscosity_pascal_seconds": sample["viscosity_pascal_seconds"],
                    "extra_density_multiplier_numeric_value": sample["density_kg_per_cubic_metre"],
                    "assessment": "The fallback already returns Pa s; multiplying by density is dimensionally inconsistent. Removing that factor alone does not make a low-Tr residue prediction valid.",
                })
assert checks == 104 and len(fallback_rows) == 2
output = {"purpose": "research_only_no_production_change", "engine_version": probe["engine_version"],
          "formula_checks": checks, "maximum_relative_difference": maximum_error,
          "fallback_at_298_kelvin": fallback_rows}
(ROOT / "units-audit.json").write_text(json.dumps(output, indent=2, allow_nan=False) + "\n")
print(f"Verified {checks} installed-API samples; maximum relative difference {maximum_error:.3g}; confirmed two residue fallback unit inconsistencies.")

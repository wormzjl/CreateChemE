"""Research screen only; reads catalog tables and a DWSIM probe, never updates them.

Usage: python examples/analyze_cold_flow.py examples/cold-flow/dwsim-probe.json
"""
import argparse
import bisect
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def table_value(curve, temperature):
    ts, vs = curve["temperatures_kelvin"], curve["coefficients"]
    assert ts[0] <= temperature <= ts[-1]
    i = min(max(bisect.bisect_right(ts, temperature) - 1, 0), len(ts) - 2)
    fraction = (temperature - ts[i]) / (ts[i + 1] - ts[i])
    return math.exp(math.log(vs[i]) + fraction * math.log(vs[i + 1] / vs[i]))


def analyze(report):
    results = []
    assert report["engine_version"] == "10.2.5.0"
    assert len(report["records"]) == 13
    for row in sorted(report["records"], key=lambda r: r["name"]):
        component = "tjl19_pc" + row["name"][-2:]
        prop = json.loads((ROOT / f"src/main/resources/data/createcheme/materials/properties/tjl19_{component}.json").read_text())
        name = json.loads((ROOT / f"src/main/resources/data/createcheme/materials/components/{component}.json").read_text())
        native = row["metadata"]
        mw = native["Molar_Weight"]
        assert math.isclose(mw / 1000, prop["molecular_weight_kg_per_mol"], rel_tol=1e-10)
        assert math.isclose(native["Normal_Boiling_Point"], prop["normal_boiling_point_kelvin"], rel_tol=1e-10)
        assert native["TemperatureOfFusion"] == native["EnthalpyOfFusionAtTf"] == 0
        curve = prop["viscosity"]["liquid"]
        for sample in row["samples"]:
            t, mu = sample["temperature_kelvin"], sample["viscosity_pascal_seconds"]
            assert math.isfinite(mu) and mu > 0
            if curve["temperature_min_kelvin"] <= t <= curve["temperature_max_kelvin"]:
                assert abs(table_value(curve, t) / mu - 1) <= .000501
        fallback = any(s["twu_kinematic_raw"] == "NaN" for s in row["samples"])
        if fallback:
            assert all(math.isclose(s["viscosity_pascal_seconds"], s["fallback_times_density"], rel_tol=1e-12) for s in row["samples"])
        crossings = {}
        for crossing in row["crossings"]:
            threshold = crossing["threshold_pascal_seconds"]
            for t in crossing["temperatures_kelvin"]:
                assert abs(table_value(curve, t) / threshold - 1) <= .000501
            crossings[str(threshold)] = {
                "temperatures_kelvin": crossing["temperatures_kelvin"],
                "status": "unqualified_fallback" if fallback else "estimated_liquid_curve_only",
                "not_reached_in_sampled_interval": not crossing["temperatures_kelvin"],
            }
        results.append({
            "component": component,
            "cut_metadata": name["cut"],
            "representative_nbp_kelvin": prop["normal_boiling_point_kelvin"],
            "molecular_weight_grams_per_mole": mw,
            "solidification_temperature_kelvin": None,
            "solidification_status": "missing_fusion_and_wax_characterization",
            "dynamic_viscosity_at_298_kelvin_pascal_seconds": next(s["viscosity_pascal_seconds"] for s in row["samples"] if s["temperature_kelvin"] == 298),
            "viscosity_crossings": crossings,
            "fusion_sensitivity_not_a_prediction": {
                "won_n_alkane_kelvin": 374.5 + .02617 * mw - 20172 / mw,
                "lira_galeana_petroleum_fraction_kelvin": 333.46 - 419.01 * math.exp(-.008546 * mw),
                "warning": "Alternative hypothetical characterizations, not bounds, measured values, WAT or whole-cut freezing points; particularly weak extrapolation for the residue cuts.",
            },
        })
    return {"schema_version": 1, "purpose": "research_only_not_production_properties", "pressure_pascal": report["pressure_pascal"],
            "threshold_note": "1, 10 and 100 Pa s are selected reporting levels, not universal pumping or solidification limits.", "records": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("probe", type=Path)
    args = parser.parse_args()
    result = analyze(json.loads(args.probe.read_text(encoding="utf-8")))
    output = args.probe.with_name("screening.json")
    output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(f"Verified API/table agreement and screened {len(result['records'])} cuts: {output}")

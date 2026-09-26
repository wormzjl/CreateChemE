"""Writes the four pilot property records (P3 WP3, batch 2026-09-24-coolprop-low-temperature).

Inputs: ../pilot-volume-anchors/anchors.json (Java Helmholtz oracle, PrintPilotVolumeAnchors) and viscosity-tables.json
(build_tables.py, CoolProp 8.0.0). The PR78 constants, molar masses, normal boiling points and standard densities are
those of the bundled records the pilot replaces (fluid_nitrogen, tjl20_methane, tjl19_ethane) and of the P2 CO2 record,
unchanged; the records carry no ideal_gas_cp (the pilot package's reference spines supply the ideal gas, D6).
Standard library only; the output is byte-for-byte reproducible from the two inputs.

    python assemble_pilot_properties.py --anchors ../pilot-volume-anchors/anchors.json --tables viscosity-tables.json \
        --out <worktree>/src/main/resources/data/createcheme/materials/properties [--check]
"""
import argparse
import json
import os
import sys

ORACLE = ("science.thermo.reference.HelmholtzFluid (Java port of CoolProp's pure-fluid Helmholtz equations, test oracle of "
          "decision D4, commit 6ded7c7), saturation(T): equal pressure and Gibbs energy, vapour-side pressure")
COOLPROP_FILES = "CoolProp dev/fluids/%s.json @ ae81610e7d23efc57f9d051c8e70a4d66e87537f (MIT; SHA-256 %s)"
SHA = {"Nitrogen": "791432d1685eafcc75b43e776e8fe4e30f7cf73a2a73b67de0c1a94c57a2b9aa",
       "Methane": "534b6a8906be63dd491972ddb29f2856c565c2c209320044858e67b90cb7a171",
       "Ethane": "ad4199d0665196aa99203515feb33db04c3ce6a3271c9536de6efb13a4f8803e",
       "CarbonDioxide": "08e27e1a5a6029e508976496b9d1fd4f7f0caa2c46fd35c84b7bf520260a5217"}
EOS = {"Nitrogen": "Span, Lemmon, Jacobsen, Wagner and Yokozeki (2000), J. Phys. Chem. Ref. Data 29, 1361",
       "Methane": "Setzmann and Wagner (1991), J. Phys. Chem. Ref. Data 20, 1061",
       "Ethane": "Buecker and Wagner (2006), J. Phys. Chem. Ref. Data 35, 205",
       "CarbonDioxide": "Span and Wagner (1996), J. Phys. Chem. Ref. Data 25, 1509"}
VISCOSITY = {"Lemmon-IJT-2004": "Lemmon and Jacobsen (2004), Int. J. Thermophys. 25, 21",
             "QuinonesCisneros-JPCB-2006": "Quinones-Cisneros and Deiters (2006), J. Phys. Chem. B 110, 12820",
             "Friend-JPCRD-1991": "Friend, Ingham and Ely (1991), J. Phys. Chem. Ref. Data 20, 275",
             "Laesecke-JPCRD-2017-CO2": "Laesecke and Muzny (2017), J. Phys. Chem. Ref. Data 46, 013107"}
CEILING = 1200
DOMAIN_EVIDENCE = ("Pilot package createcheme:pilot_cryogenic (P3 design sections 1.3 and 8.1): from %s to 1200 K (steam-cracking "
                   "coil outlet 1143 K target, 1200 K provisional) and 100 Pa to 10 MPa (hydrotreating ceiling). The declared "
                   "critical band (plan section 3, widened in D7) is research-only inside this range; the range is qualified at G3 "
                   "(WP9), not by this record. Viscosity above 2 MPa for dense states is a reference-pressure value (P3 design "
                   "section 10).")

SPECIES = [
    dict(key="nitrogen", fluid="Nitrogen", component="Nitrogen", record="pilot_nitrogen", mw=0.0280134, nbp=77.355,
         density=751.4251189333391, st=90, sp=2000000, tmin=63.151, pr=(126.192, 3395800, 0.0372),
         bundled="createcheme:fluid_nitrogen", nbp_note="",
         low="the nitrogen triple point 63.151 K (Span et al. 2000)", note=""),
    dict(key="methane", fluid="Methane", component="Methane", record="pilot_methane", mw=0.0160428, nbp=111.66,
         density=356.07, st=288.7055555556, sp=101325, tmin=90.6941, pr=(190.564, 4599200.0, 0.01142),
         bundled="createcheme:tjl20_methane", nbp_note="",
         low="the methane triple point 90.6941 K (Setzmann and Wagner 1991)", note=""),
    dict(key="ethane", fluid="Ethane", component="Ethane", record="pilot_ethane", mw=0.03006904, nbp=184.55,
         density=360.15050819211837, st=288.7055555556, sp=101325, tmin=90.368, pr=(305.32, 4872000.0, 0.099),
         bundled="createcheme:tjl19_ethane", nbp_note="",
         low="the ethane triple point 90.368 K (Buecker and Wagner 2006)",
         note=" Ethane's saturation pressure below 150 K is a declared estimate (D3)."),
    dict(key="carbon_dioxide", fluid="CarbonDioxide", component="CarbonDioxide", record="pilot_carbon_dioxide", mw=0.0440098,
         nbp=194.6855, density=1046.8822148292945, st=250, sp=2000000, tmin=216.592, pr=(304.1282, 7377300, 0.22394),
         bundled="createcheme:pilot_carbon_dioxide (P2 test record)",
         nbp_note=" normal_boiling_point_kelvin carries the normal sublimation temperature, 194.6855 K at 1 atm (Span and Wagner 1996): CO2 has no normal boiling point.",
         low="the CO2 triple point 216.592 K (Span and Wagner 1996)",
         note=" CO2 below its triple point (the CO2-in-liquid-methane states of fixture F7) is a research contract of the engine, not a network state."),
]


def number(value):
    return int(value) if isinstance(value, float) and value.is_integer() and abs(value) < 1e15 and not isinstance(value, bool) else value


def record(s, anchor, tables, meta):
    fluid = s["fluid"]
    liquid, vapor = tables["liquid"], tables["vapor"]
    model = VISCOSITY[tables["viscosity_model"]]
    eos_max = tables["eos_maximum_temperature_kelvin"]
    vapor_estimated = eos_max < CEILING
    tc, pc, w = s["pr"]
    if anchor["record_critical_temperature_kelvin"] != tc:
        sys.exit("anchor for %s was computed with Tc %r, the record carries %r" % (s["record"], anchor["record_critical_temperature_kelvin"], tc))
    source = ("P3 pilot property record of createcheme:pilot_cryogenic (batch 2026-09-24-coolprop-low-temperature, WP3). PR78 "
              "constants, molar mass, normal boiling point and standard density identical to %s. No ideal_gas_cp: the package's "
              "reference spine createcheme:spine_%s is the only ideal-gas source (D6). volume_translation: the Tr = 0.8 "
              "saturated liquid of the reference equation from the Java Helmholtz oracle (P1 rule b). Viscosity tables from "
              "CoolProp %s (%s). Written by tools/pilot-transport-tables/assemble_pilot_properties.py.%s"
              % (s["bundled"], s["key"], meta["coolprop_version"], meta["coolprop_revision"][:8], s["nbp_note"]))
    return {
        "schema_version": 1,
        "id": "createcheme:" + s["record"],
        "component": s["component"],
        "revision": "pilot-%s-p3-r1" % s["key"].replace("_", "-"),
        "source": source,
        "molecular_weight_kg_per_mol": s["mw"],
        "normal_boiling_point_kelvin": s["nbp"],
        "standard_liquid_density_kg_per_m3": s["density"],
        "standard_temperature_kelvin": s["st"],
        "standard_pressure_pascal": s["sp"],
        "temperature_min_kelvin": s["tmin"],
        "temperature_max_kelvin": CEILING,
        "fluid_domain": {
            "temperature_min_kelvin": s["tmin"],
            "temperature_max_kelvin": CEILING,
            "pressure_min_pascal": 100,
            "pressure_max_pascal": 10000000,
            "evidence": DOMAIN_EVIDENCE % s["low"] + s["note"],
        },
        "estimated_heavy_residue": False,
        "volume_translation": {
            "type": "saturated_liquid_reduced_temperature",
            "reduced_temperature": 0.8,
            "temperature_kelvin": anchor["temperature_kelvin"],
            "pressure_pascal": anchor["pressure_pascal"],
            "molar_volume_m3_per_mol": anchor["molar_volume_m3_per_mol"],
            "reference": COOLPROP_FILES % (fluid, SHA[fluid]) + "; " + EOS[fluid],
            "oracle": ORACLE,
            "source": ("0.8 x Tc of this record's PR78 constants; saturation pressure and saturated-liquid molar volume of the "
                       "reference equation there, printed by tools/pilot-volume-anchors (anchors.json) and held to the oracle at "
                       "1e-12 by PilotVolumeAnchorTest; shift c = v_ref - v_PR,L(T, Psat_ref) = %s cm3/mol on the untranslated "
                       "kernel (old 2 MPa anchor: %s cm3/mol)" % (anchor["shift_cm3_per_mol"], anchor["old_shift_cm3_per_mol"])),
        },
        "models": {"pr78": {"critical_temperature_kelvin": tc, "critical_pressure_pascal": pc, "acentric_factor": w}},
        "viscosity": {
            "liquid": {
                "type": "log_table",
                "revision": "coolprop-8.0.0-saturated-liquid-r1",
                "source": ("CoolProp %s HEOS saturated-liquid viscosity (Q = 0), %s via the %s.json transport block, from the triple "
                           "point to the critical temperature; log-linear interpolation within %.3f %% of CoolProp everywhere but "
                           "the last %.4f K below the critical point (%.2f %% there). Saturation-pressure (reference-pressure) "
                           "approximation: no pressure dependence." % (meta["coolprop_version"], model, fluid,
                           100 * liquid["worst_interpolation_deviation_before_last_interval"],
                           liquid["last_interval_kelvin"][1] - liquid["last_interval_kelvin"][0], 100 * liquid["worst_interpolation_deviation"])),
                "estimated": False,
                "temperature_min_kelvin": liquid["temperatures_kelvin"][0],
                "temperature_max_kelvin": liquid["temperatures_kelvin"][-1],
                "pressure_min_pascal": liquid["triple_point_pressure_pascal"],
                "pressure_max_pascal": liquid["critical_pressure_pascal"],
                "reference_temperature_kelvin": s["nbp"] if s["key"] != "carbon_dioxide" else s["tmin"],
                "temperatures_kelvin": liquid["temperatures_kelvin"],
                "coefficients": liquid["viscosities_pascal_seconds"],
            },
            "vapor": {
                "type": "log_table",
                "revision": "coolprop-8.0.0-dilute-gas-r1",
                "source": ("CoolProp %s HEOS zero-density viscosity (evaluated at 1e-6 mol/m3), %s, from the triple point to 1200 K; "
                           "log-linear interpolation within %.3f %% of CoolProp. The pressure fields name the network's minimum "
                           "pressure at which the table is read (reference-pressure approximation).%s"
                           % (meta["coolprop_version"], model, 100 * vapor["worst_interpolation_deviation"],
                              (" Estimated above %g K, the upper limit of the reference equation of state CoolProp carries for %s: "
                               "the dilute-gas correlation is extrapolated there." % (eos_max, fluid)) if vapor_estimated else "")),
                "estimated": vapor_estimated,
                "temperature_min_kelvin": vapor["temperatures_kelvin"][0],
                "temperature_max_kelvin": vapor["temperatures_kelvin"][-1],
                "pressure_min_pascal": 100,
                "pressure_max_pascal": 100,
                "reference_temperature_kelvin": 298.15,
                "temperatures_kelvin": vapor["temperatures_kelvin"],
                "coefficients": vapor["viscosities_pascal_seconds"],
            },
        },
        "provenance": {
            "critical": "https://coolprop.org/fluid_properties/fluids/%s.html (as in %s)" % (fluid, s["bundled"]),
            "eos": EOS[fluid],
            "viscosity": model,
            "anchor": "tools/pilot-volume-anchors/anchors.json, oracle commit 6ded7c7",
        },
    }


def render(obj):
    def fix(value):
        if isinstance(value, dict):
            return {k: fix(v) for k, v in value.items()}
        if isinstance(value, list):
            return [fix(v) for v in value]
        return number(value)
    return (json.dumps(fix(obj), indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--anchors", required=True)
    parser.add_argument("--tables", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    anchors = {a["record"]: a for a in json.load(open(args.anchors, encoding="utf-8"))}
    tables = json.load(open(args.tables, encoding="utf-8"))
    same = True
    for s in SPECIES:
        data = render(record(s, anchors["createcheme:" + s["record"]], tables["species"][s["key"]], tables))
        path = os.path.join(args.out, s["record"] + ".json")
        if args.check:
            existing = open(path, "rb").read().replace(b"\r\n", b"\n")
            print("%s: %s" % (path, "identical" if existing == data else "DIFFERENT"))
            same &= existing == data
        else:
            open(path, "wb").write(data)
            print("wrote %s (%d bytes)" % (path, len(data)))
    sys.exit(0 if same else 1)


if __name__ == "__main__":
    main()

"""Parity fixtures for the Java Helmholtz reference oracle (batch 2026-09-24-coolprop-low-temperature, P1 item 1).

Writes parity-<Fluid>.json next to the CoolProp fluid files for N2, CO2, CH4, C2H6, H2 and water:
  single_phase: a (T, P) grid of 11 temperatures from Tmin + 1 K to min(Tmax, 1000 K) at 0.01..20 MPa plus the states of the review's
                PR78-vs-CoolProp probe; each state carries CoolProp's phase string, Dmolar, Hmolar, Smolar, Cpmolar,
                Cvmolar and the fugacity coefficient from the PT flash, and eos_at_Dmolar = the same properties and the
                pressure from a DmolarT update at (Dmolar, T), i.e. CoolProp's EOS at exactly the density it reported.
  saturation:   6+ temperatures from Ttriple to 0.99 Tc plus the probe's saturation temperatures; Psat, rhoL, rhoV,
                hL, hV, sL, sV from CoolProp's default QT flash (superancillaries on), with diagnostics:
                pV_eos = CoolProp's EOS pressure at (T, rhoV), and the iterative (superancillaries off) Psat, rhoL, rhoV.
  refused:      grid states CoolProp refuses (below the melting line), with its message.

Fugacity coefficient: CoolProp.AbstractState('HEOS', fluid).fugacity_coefficient(0) after a PT_INPUTS update;
PropsSI has no keyed output for it. For a pure fluid it equals exp(alphar + delta*dalphar_ddelta - ln Z).
Every single-phase value is cross-checked against PropsSI(..., 'T', T, 'P', P, fluid).

Run from the worktree root:
  "$TEMP/coolprop-probe-venv/Scripts/python.exe" tools/coolprop-parity/generate_parity.py src/test/resources/science/thermo/coolprop
"""
import json
import math
import sys
from pathlib import Path

import CoolProp
import CoolProp.CoolProp as CP
import numpy as np

FLUIDS = ["Nitrogen", "CarbonDioxide", "Methane", "Ethane", "Hydrogen", "Water"]
PRESSURES = [0.01e6, 0.1e6, 1e6, 2e6, 5e6, 10e6, 20e6]

# States of research/2026-09-24-coolprop-low-temperature/pr78-vs-coolprop/probe.py (main checkout).
PROBE_SAT = {"Nitrogen": [63.151, 70, 77.355, 90, 100, 110, 120],
             "Methane": [90.7, 100, 111.67, 130, 150, 170, 185],
             "Ethane": [90.4, 120, 150, 184.55, 220, 250, 280, 295],
             "CarbonDioxide": [216.6, 230, 250, 270, 290, 300]}
PROBE_DENSE = {"Nitrogen": [(77.355, 5), (77.355, 10), (100, 10), (126.2, 3.4), (130, 4), (130, 6), (130, 10),
                            (150, 10), (300, 10)],
               "CarbonDioxide": [(280, 8), (280, 10), (300, 8), (304.5, 7.4), (310, 8), (310, 10), (320, 8),
                                 (320, 10), (350, 10), (400, 10)],
               "Methane": [(110, 10), (150, 10), (190.6, 4.6), (200, 6), (200, 10), (250, 10), (300, 10)],
               "Ethane": [(200, 10), (300, 10), (305.4, 4.9), (320, 6), (320, 10), (400, 10)],
               "Hydrogen": [(300, 10), (623, 6), (650, 9), (663, 9.1), (700, 10)]}


def rel(a, b):
    return abs(a / b - 1) if b != 0 else abs(a)


def single_phase(fluid, T, P, source):
    st = CP.AbstractState("HEOS", fluid)
    st.update(CP.PT_INPUTS, P, T)
    row = {"source": source, "T": T, "P": P, "phase": CP.PhaseSI("T", T, "P", P, fluid),
           "Dmolar": st.rhomolar(), "Hmolar": st.hmolar(), "Smolar": st.smolar(),
           "Cpmolar": st.cpmolar(), "Cvmolar": st.cvmolar(), "fugacity_coefficient": st.fugacity_coefficient(0)}
    for key in ["Dmolar", "Hmolar", "Smolar", "Cpmolar", "Cvmolar"]:
        via_props = CP.PropsSI(key, "T", T, "P", P, fluid)
        if rel(via_props, row[key]) > 1e-15:
            raise RuntimeError(f"PropsSI and AbstractState disagree for {fluid} {key} at {T} K, {P} Pa")
    # CoolProp's PT-flash outputs are not exactly its EOS at the density it reports (up to 3e-6 on cp at ethane
    # 305.4 K, 4.9 MPa); a DmolarT update at that density gives the EOS values themselves.
    check = CP.AbstractState("HEOS", fluid)
    check.update(CP.DmolarT_INPUTS, row["Dmolar"], T)
    row["eos_at_Dmolar"] = {"P": check.p(), "Hmolar": check.hmolar(), "Smolar": check.smolar(),
                            "Cpmolar": check.cpmolar(), "Cvmolar": check.cvmolar(),
                            "fugacity_coefficient": check.fugacity_coefficient(0)}
    return row


def saturation(fluid, T, source):
    st = CP.AbstractState("HEOS", fluid)
    st.update(CP.QT_INPUTS, 0, T)
    p, rho_l, h_l, s_l = st.p(), st.rhomolar(), st.hmolar(), st.smolar()
    st.update(CP.QT_INPUTS, 1, T)
    p_v, rho_v, h_v, s_v = st.p(), st.rhomolar(), st.hmolar(), st.smolar()
    if rel(CP.PropsSI("P", "T", T, "Q", 0, fluid), p) > 1e-15 or rel(CP.PropsSI("Hmolar", "T", T, "Q", 1, fluid), h_v) > 1e-15:
        raise RuntimeError(f"PropsSI and AbstractState disagree for {fluid} saturation at {T} K")
    check = CP.AbstractState("HEOS", fluid)
    check.update(CP.DmolarT_INPUTS, rho_v, T)
    row = {"source": source, "T": T, "Psat": p, "Psat_Q1": p_v, "rhoL": rho_l, "rhoV": rho_v,
           "hL": h_l, "hV": h_v, "sL": s_l, "sV": s_v, "pV_eos": check.p()}
    CP.set_config_bool(CP.ENABLE_SUPERANCILLARIES, False)
    try:
        it = CP.AbstractState("HEOS", fluid)
        it.update(CP.QT_INPUTS, 0, T)
        row["iterative"] = {"Psat": it.p(), "rhoL": it.rhomolar()}
        it.update(CP.QT_INPUTS, 1, T)
        row["iterative"]["rhoV"] = it.rhomolar()
    except Exception as e:  # diagnostics only
        row["iterative"] = {"error": str(e)}
    finally:
        CP.set_config_bool(CP.ENABLE_SUPERANCILLARIES, True)
    return row


def main(out_dir):
    out = Path(out_dir)
    for fluid in FLUIDS:
        st = CP.AbstractState("HEOS", fluid)
        t_min, t_max = st.Tmin(), min(st.Tmax(), 1000.0)
        t_crit, t_triple = st.T_critical(), st.Ttriple()
        states, refused = [], []
        # Five subcritical temperatures (1 K above Tmin to 0.95 Tc) and six from 1.05 Tc to min(Tmax, 1000 K).
        temperatures = list(np.linspace(t_min + 1.0, 0.95 * t_crit, 5)) + list(np.linspace(1.05 * t_crit, t_max, 6))
        grid = [(float(T), P, "grid") for T in temperatures for P in PRESSURES]
        grid += [(float(T), PMPa * 1e6, "probe") for T, PMPa in PROBE_DENSE.get(fluid, [])]
        for T, P, source in grid:
            try:
                states.append(single_phase(fluid, T, P, source))
            except Exception as e:
                refused.append({"source": source, "T": T, "P": P, "message": str(e)})
        sat_t = [(float(T), "grid") for T in np.linspace(t_triple, 0.99 * t_crit, 7)]
        sat_t += [(float(T), "probe") for T in PROBE_SAT.get(fluid, []) if t_triple <= T <= 0.99 * t_crit]
        sat = [saturation(fluid, T, source) for T, source in sat_t]
        doc = {
            "fluid": fluid,
            "generator": "tools/coolprop-parity/generate_parity.py (batch 2026-09-24-coolprop-low-temperature)",
            "coolprop_version": CoolProp.__version__,
            "coolprop_gitrevision": CoolProp.__gitrevision__,
            "backend": "HEOS, default configuration (ENABLE_SUPERANCILLARIES true); PT_INPUTS and QT_INPUTS updates",
            "fugacity_coefficient_source": "AbstractState.fugacity_coefficient(0) after PT_INPUTS",
            "gas_constant": st.gas_constant(), "Tmin": t_min, "Tmax_used": t_max,
            "Tcrit": t_crit, "pcrit": st.p_critical(), "Ttriple": t_triple,
            "single_phase": states, "saturation": sat, "refused": refused,
        }
        path = out / f"parity-{fluid}.json"
        path.write_text(json.dumps(doc, indent=1) + "\n", encoding="utf-8", newline="\n")
        print(f"{fluid}: {len(states)} single-phase states, {len(sat)} saturation states, {len(refused)} refused -> {path}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "src/test/resources/science/thermo/coolprop")

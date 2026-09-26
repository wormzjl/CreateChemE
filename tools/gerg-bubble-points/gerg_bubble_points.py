#!/usr/bin/env python3
"""WP8 (P3, batch 2026-09-24-coolprop-low-temperature): GERG-2008 bubble and dew
points of the six pilot binaries from CoolProp 8.0.0 (HEOS backend), plus the
comparison of CoolProp's binary parameters with NIST's GERG2008.cpp.

Writes the test fixture src/test/resources/science/thermo/gerg2008/pilot-binaries.json.
Run from the worktree root (see README.md):

    python tools/gerg-bubble-points/gerg_bubble_points.py \
        --gerg-cpp research/2026-09-24-coolprop-low-temperature/sources/nist-aga8/GERG2008.cpp \
        --departure research/2026-09-24-coolprop-low-temperature/sources/coolprop/mixtures/mixture_departure_functions.json \
        --out src/test/resources/science/thermo/gerg2008/pilot-binaries.json
"""
import argparse
import json
import math
import platform
import re
import sys

import CoolProp
import CoolProp.CoolProp as CP
from CoolProp import AbstractState

X_GRID = [0.05, 0.1, 0.2, 0.3, 0.5, 0.7, 0.9, 0.95]

# (label, CoolProp name 1, CoolProp name 2, CAS 1, CAS 2, temperatures K, index of the
# more volatile component: the isotherms are traced from the other side)
PAIRS = [
    ("N2/CH4", "Nitrogen", "Methane", "7727-37-9", "74-82-8", [95.0, 110.0, 125.0, 140.0, 155.0, 170.0], 0),
    ("N2/C2H6", "Nitrogen", "Ethane", "7727-37-9", "74-84-0", [150.0, 180.0, 210.0, 240.0, 270.0], 0),
    ("CH4/C2H6", "Methane", "Ethane", "74-82-8", "74-84-0", [150.0, 175.0, 200.0, 225.0, 250.0, 275.0], 0),
    ("CO2/N2", "CarbonDioxide", "Nitrogen", "124-38-9", "7727-37-9", [220.0, 240.0, 260.0, 280.0, 290.0], 1),
    ("CO2/CH4", "CarbonDioxide", "Methane", "124-38-9", "74-82-8", [220.0, 235.0, 250.0, 265.0, 280.0], 1),
    ("CO2/C2H6", "CarbonDioxide", "Ethane", "124-38-9", "74-84-0", [220.0, 235.0, 250.0, 265.0, 280.0], 0),
]

# GERG-2008 component numbering in GERG2008.cpp
GERG_INDEX = {"Methane": 1, "Nitrogen": 2, "CarbonDioxide": 3, "Ethane": 4}
# departure-function index k of GERG2008.cpp (dijk[k][..]) per unordered pair
GERG_DEPARTURE = {frozenset(("Methane", "Nitrogen")): 3, frozenset(("Methane", "CarbonDioxide")): 4,
                  frozenset(("Methane", "Ethane")): 1, frozenset(("Nitrogen", "CarbonDioxide")): 5,
                  frozenset(("Nitrogen", "Ethane")): 6}

NEAR_CRITICAL_RHO_RATIO = 3.0     # rhoL/rhoV below this: flagged near_critical
PILOT_PRESSURE_CEILING = 10.0e6   # Pa; rows above are flagged above_10_MPa
P_TOL = 1.0e-6                    # validation: |p(T, rho, z)/P - 1|
LNF_TOL = 1.0e-6                  # validation: |ln f_L - ln f_V|
TRIVIAL_RHO_RATIO = 1.01          # rhoL/rhoV below this: trivial solution


def g10(value):
    """Ten significant digits, as a JSON number."""
    return float(f"{value:.10g}")


def short(exc):
    text = " ".join(str(exc).split())
    return text[:160]


# --------------------------------------------------------------------------
# Parameter comparison
# --------------------------------------------------------------------------
def parse_gerg_cpp(text):
    params = {}
    for key in ("bvij", "gvij", "btij", "gtij", "fij"):
        for m in re.finditer(key + r"\[(\d+)\]\[(\d+)\]\s*=\s*([-0-9.Ee+]+);", text):
            params[(key, int(m.group(1)), int(m.group(2)))] = float(m.group(3))
    departure = {}
    for key in ("dijk", "tijk", "cijk", "eijk", "bijk", "gijk", "nijk"):
        for m in re.finditer(key + r"\[(\d+)\]\[(\d+)\]\s*=\s*([-0-9.Ee+]+);", text):
            departure[(key, int(m.group(1)), int(m.group(2)))] = float(m.group(3))
    # the exponent form used by AlpharGERG, recorded for the comparison
    # SetupGERG folds -c(delta - e)^2 - b(delta - g) into three constants; finding
    # that transform confirms the exponent form CoolProp's GERG-2008 type uses
    form = re.search(r"gijk\[i\]\[j\]\s*=\s*-cijk\[i\]\[j\]\s*\*\s*pow\(eijk\[i\]\[j\],\s*2\)"
                     r"\s*\+\s*bijk\[i\]\[j\]\s*\*\s*gijk\[i\]\[j\]", text) is not None
    return params, departure, form


def compare_parameters(label, name1, name2, cas1, cas2, gerg_params, gerg_dep, departure_json):
    i, j = GERG_INDEX[name1], GERG_INDEX[name2]
    lo, hi = min(i, j), max(i, j)
    swapped_vs_gerg = i > j
    # CoolProp stores one orientation; find it
    try:
        CP.get_mixture_binary_pair_data(cas1, cas2, "betaT")
        cp_first, cp_second = cas1, cas2
        cp_names = (name1, name2)
    except ValueError:
        cp_first, cp_second = cas2, cas1
        cp_names = (name2, name1)
    cp = {k: float(CP.get_mixture_binary_pair_data(cp_first, cp_second, k))
          for k in ("betaT", "gammaT", "betaV", "gammaV", "F")}
    try:
        cp_function = CP.get_mixture_binary_pair_data(cp_first, cp_second, "function")
    except ValueError:
        cp_function = None  # no departure function (CO2/C2H6: F = 0 in both)
    cp_bibtex = CP.get_mixture_binary_pair_data(cp_first, cp_second, "BibTeX")
    cp_in_gerg_order = GERG_INDEX[cp_names[0]] < GERG_INDEX[cp_names[1]]
    gerg = {"betaT": gerg_params.get(("btij", lo, hi), 1.0), "gammaT": gerg_params.get(("gtij", lo, hi), 1.0),
            "betaV": gerg_params.get(("bvij", lo, hi), 1.0), "gammaV": gerg_params.get(("gvij", lo, hi), 1.0),
            "F": gerg_params.get(("fij", lo, hi), 0.0)}
    rows = {}
    worst = 0.0
    for key in ("betaT", "gammaT", "betaV", "gammaV", "F"):
        g = gerg[key]
        c = cp[key]
        # beta is antisymmetric under exchange (beta_ji = 1/beta_ij); gamma and F are symmetric
        c_in_gerg_order = (1.0 / c) if (key.startswith("beta") and not cp_in_gerg_order) else c
        rel = 0.0 if g == c_in_gerg_order else abs(c_in_gerg_order / g - 1.0)
        worst = max(worst, rel)
        rows[key] = {"gerg2008_cpp": g, "coolprop": c, "coolprop_in_gerg_order": g10(c_in_gerg_order),
                     "relative_difference": rel}
    dep_result = None
    k = GERG_DEPARTURE.get(frozenset((name1, name2)))
    if k is not None:
        entry = next(d for d in departure_json if d["Name"] == cp_function)
        n_terms = len(entry["n"])
        mapping = [("dijk", "d"), ("tijk", "t"), ("nijk", "n"), ("cijk", "eta"), ("eijk", "epsilon"),
                   ("bijk", "beta"), ("gijk", "gamma")]
        max_dev = 0.0
        for m in range(1, n_terms + 1):
            for gkey, ckey in mapping:
                gv = gerg_dep.get((gkey, k, m), 0.0)
                cv = float(entry[ckey][m - 1])
                if gv != cv:
                    max_dev = max(max_dev, abs(gv - cv) / max(abs(gv), 1e-300))
        gerg_terms = max(m for (key, kk, m) in gerg_dep if kk == k and key == "nijk")
        dep_result = {"coolprop_function": cp_function, "gerg2008_cpp_index": k,
                      "terms_coolprop": n_terms, "terms_gerg2008_cpp": gerg_terms,
                      "power_terms_coolprop": entry.get("Npower"),
                      "max_relative_difference": max_dev}
    return {
        "pair": label, "coolprop_order": [cp_names[0], cp_names[1]], "coolprop_bibtex": cp_bibtex,
        "gerg2008_cpp_order": [name for name, idx in sorted(GERG_INDEX.items(), key=lambda kv: kv[1])
                               if idx in (lo, hi)],
        "reducing": rows, "departure": dep_result,
        "identical": worst < 1.0e-8 and (dep_result is None or dep_result["max_relative_difference"] == 0.0),
        "worst_reducing_relative_difference": worst,
        "note": ("CoolProp stores the pair in the opposite order to GERG2008.cpp; beta compared as 1/beta"
                 if not cp_in_gerg_order else "same order as GERG2008.cpp"),
    }


# --------------------------------------------------------------------------
# Envelope, points, validation
# --------------------------------------------------------------------------
def validate(names, temperature, pressure, x, y, rho_l, rho_v):
    """Independent check of a two-phase point: pressure and fugacities of each
    phase from (T, rho, composition) with the phase imposed."""
    result = {}
    lnf = []
    for label, comp, rho, phase in (("L", x, rho_l, CP.iphase_liquid), ("V", y, rho_v, CP.iphase_gas)):
        st = AbstractState("HEOS", names)
        st.set_mole_fractions(comp)
        st.specify_phase(phase)
        st.update(CP.DmolarT_INPUTS, rho, temperature)
        result["p_" + label] = st.p()
        fug = [st.fugacity(i) for i in range(2)]
        if not all(math.isfinite(v) and v > 0.0 for v in fug):
            result["dp"] = result["dlnf"] = float("inf")
            result["bad"] = f"non-positive fugacity in phase {label}: {fug}"
            return result
        lnf.append([math.log(v) for v in fug])
    result["dp"] = max(abs(result["p_L"] / pressure - 1.0), abs(result["p_V"] / pressure - 1.0))
    result["dlnf"] = max(abs(lnf[0][i] - lnf[1][i]) for i in range(2))
    return result


def liquid_diffusion_stable(names, temperature, pressure, x1, rho_l):
    """Binary intrinsic (spinodal) test at fixed T and P on the liquid root:
    d ln f1 / d x1 > 0, by central difference with h = min(1e-4, x1/100,
    (1 - x1)/100). Returns True, False or None (liquid root not found)."""
    h = min(1.0e-4, 0.01 * x1, 0.01 * (1.0 - x1))
    lnf1 = []
    try:
        for xx in (x1 - h, x1 + h):
            st = AbstractState("HEOS", names)
            st.set_mole_fractions([xx, 1.0 - xx])
            st.specify_phase(CP.iphase_liquid)
            rho = rho_l
            for _ in range(50):
                st.update(CP.DmolarT_INPUTS, rho, temperature)
                dpdrho = st.first_partial_deriv(CP.iP, CP.iDmolar, CP.iT)
                if not dpdrho > 0.0:
                    return None
                step = (st.p() - pressure) / dpdrho
                rho -= step
                if not rho > 0.0:
                    return None
                if abs(step) < 1e-10 * rho:
                    break
            else:
                return None
            st.update(CP.DmolarT_INPUTS, rho, temperature)
            f1 = st.fugacity(0)
            if not (math.isfinite(f1) and f1 > 0.0):
                return None
            lnf1.append(math.log(f1))
    except ValueError:
        return None
    return lnf1[1] > lnf1[0]


def continuation_path(grid_light):
    """Light-component fractions visited along an isotherm: 0.01 steps from 0.01
    to 0.99 plus every grid point, sorted."""
    fine = [round(0.01 * k, 10) for k in range(1, 100)]
    return sorted(set(fine) | set(round(g, 10) for g in grid_light))


def qt_point(names, quality, temperature, light_index, z_light, previous):
    """One QT flash at light-component fraction z_light (liquid for Q = 0,
    vapour for Q = 1). previous = None: CoolProp's own initial guess;
    otherwise the previous converged point on the isotherm seeds
    update_with_guesses. Returns (P, x, y, rhoL, rhoV) in component order."""
    z = [0.0, 0.0]
    z[light_index] = z_light
    z[1 - light_index] = 1.0 - z_light
    state = AbstractState("HEOS", names)
    state.set_mole_fractions(z)
    if previous is None:
        state.update(CP.QT_INPUTS, quality, temperature)
    else:
        guess = CP.PyGuessesStructure()
        guess.p = previous[0]
        guess.x = z if quality == 0.0 else previous[1]
        guess.y = previous[2] if quality == 0.0 else z
        guess.rhomolar_liq = previous[3]
        guess.rhomolar_vap = previous[4]
        state.update_with_guesses(CP.QT_INPUTS, quality, temperature, guess)
    pressure = state.p()
    x = list(state.mole_fractions_liquid())
    y = list(state.mole_fractions_vapor())
    rho_l = state.saturated_liquid_keyed_output(CP.iDmolar)
    rho_v = state.saturated_vapor_keyed_output(CP.iDmolar)
    if not (math.isfinite(pressure) and pressure > 0.0 and 0.0 < x[0] < 1.0 and 0.0 < y[0] < 1.0
            and math.isfinite(rho_l) and math.isfinite(rho_v) and rho_v > 0.0):
        raise ValueError(f"unphysical result P {pressure:.6g}, x1 {x[0]:.6g}, y1 {y[0]:.6g}")
    if rho_l / rho_v < TRIVIAL_RHO_RATIO:
        raise ValueError(f"trivial solution, rhoL/rhoV {rho_l / rho_v:.4f}")
    return pressure, x, y, rho_l, rho_v


def trace_isotherm(names, quality, temperature, light_index, grid_light, seed=None):
    """Continuation along one isotherm from the heavy side in the light
    fraction of the specified phase. A failed step is retried with the step
    halved down to 1/64 of the path step; the trace ends at the first
    composition it cannot reach. The start uses CoolProp's own guess and, if
    that fails and a seed point is given (the first point of the bubble trace
    of the same isotherm), the seed as the guess; up to four further path
    points are tried as starts. Returns ({z_light: point}, end record)."""
    path = continuation_path(grid_light)
    points = {}
    previous = None
    z_prev = None
    end = None
    skipped_start = []
    for z_target in path:
        z_from = z_prev
        reached = False
        attempt_error = None
        # sub-steps toward z_target
        step = z_target - (z_from if z_from is not None else 0.0)
        z_cur = z_from
        min_step = step / 64.0
        while True:
            z_try = z_target if z_cur is None else min(z_target, z_cur + step)
            try:
                point = qt_point(names, quality, temperature, light_index, z_try, previous)
            except Exception as exc:  # noqa: BLE001 - CoolProp raises ValueError/RuntimeError
                attempt_error = short(exc)
                point = None
                if previous is None and seed is not None:
                    try:
                        point = qt_point(names, quality, temperature, light_index, z_try, seed)
                    except Exception as exc2:  # noqa: BLE001
                        attempt_error = short(exc2)
                if point is None:
                    if z_cur is None or step <= min_step * 1.0000001:
                        break
                    step *= 0.5
                    continue
            previous = point
            z_cur = z_try
            if z_cur >= z_target - 1e-12:
                reached = True
                break
        if not reached and previous is None and len(skipped_start) < 4:
            skipped_start.append(z_target)  # no start yet: try the next path point
            continue
        if not reached:
            end = {"light_fraction_last": z_prev, "reason": attempt_error,
                   "light_fraction_failed": z_target}
            if previous is not None:
                end.update({"P_last": g10(previous[0]), "rho_ratio_last": g10(previous[3] / previous[4])})
            break
        points[round(z_target, 10)] = previous
        z_prev = z_target
    if end is None:
        end = {"light_fraction_last": z_prev, "reason": "reached the end of the path"}
        if previous is not None:
            end.update({"P_last": g10(previous[0]), "rho_ratio_last": g10(previous[3] / previous[4])})
    if skipped_start:
        end["start_failed_at"] = skipped_start
    return points, end


def run_pair(label, name1, name2, temperatures, light_index):
    names = f"{name1}&{name2}"
    bubble, dew, failures, ends = [], [], [], []
    grid_light = [x1 if light_index == 0 else round(1.0 - x1, 10) for x1 in X_GRID]
    vapour_light_max = {}
    bubble_first = {}
    worst = {"dp": 0.0, "dlnf": 0.0}
    for kind, quality in (("bubble", 0.0), ("dew", 1.0)):
        for temperature in temperatures:
            seed = None
            if kind == "dew" and bubble_first.get(temperature) is not None:
                seed = bubble_first[temperature]
            points, end = trace_isotherm(names, quality, temperature, light_index, grid_light, seed)
            if kind == "bubble" and points:
                bubble_first[temperature] = points[min(points)]
            end_record = {"kind": kind, "T": temperature}
            end_record.update(end)
            if kind == "bubble" and points:
                vapour_light_max[temperature] = max(pt[2][light_index] for pt in points.values())
                end_record["vapour_light_fraction_max"] = g10(vapour_light_max[temperature])
            ends.append(end_record)
            for x1 in X_GRID:
                z_light = round(x1 if light_index == 0 else 1.0 - x1, 10)
                point = points.get(z_light)
                if point is None:
                    last = end.get("light_fraction_last")
                    ratio = end.get("rho_ratio_last")
                    reason = end.get("reason")
                    if kind == "bubble":
                        absent = ratio is not None and ratio < 1.5
                        why = f"bubble trace ended at light fraction {last} with rhoL/rhoV {ratio}"
                    else:
                        ymax = vapour_light_max.get(temperature)
                        absent = ymax is not None and z_light > ymax + 0.005
                        shown = None if ymax is None else round(ymax, 4)
                        why = (f"richest equilibrium vapour on the bubble trace has light fraction "
                               f"{shown}; dew trace ended at {last}")
                    failures.append({
                        "kind": kind, "T": temperature, "z1": x1,
                        "class": "beyond_isotherm_end" if absent else "coolprop_error",
                        "detail": f"{why}; failed step: {reason}"})
                    continue
                pressure, x, y, rho_l, rho_v = point
                check = validate(names, temperature, pressure, x, y, rho_l, rho_v)
                problems = []
                if "bad" in check:
                    problems.append(check["bad"])
                else:
                    if check["dp"] > P_TOL:
                        problems.append(f"pressure mismatch {check['dp']:.1e}")
                    if check["dlnf"] > LNF_TOL:
                        problems.append(f"fugacity mismatch {check['dlnf']:.1e}")
                if not problems:
                    worst["dp"] = max(worst["dp"], check["dp"])
                    worst["dlnf"] = max(worst["dlnf"], check["dlnf"])
                if problems:
                    failures.append({"kind": kind, "T": temperature, "z1": x1, "class": "invalid_solution",
                                     "detail": "; ".join(problems) + f" (P {pressure:.6g} Pa)"})
                    continue
                flags = []
                if rho_l / rho_v < NEAR_CRITICAL_RHO_RATIO:
                    flags.append("near_critical")
                if pressure > PILOT_PRESSURE_CEILING:
                    flags.append("above_10_MPa")
                stable = liquid_diffusion_stable(names, temperature, pressure, x[0], rho_l)
                if stable is False:
                    flags.append("liquid_unstable")
                elif stable is None:
                    flags.append("liquid_stability_unchecked")
                row = {"T": temperature}
                if kind == "bubble":
                    row.update({"x1": x1, "P": g10(pressure), "y1": g10(y[0])})
                else:
                    row.update({"y1": x1, "P": g10(pressure), "x1": g10(x[0])})
                row.update({"rhoL": g10(rho_l), "rhoV": g10(rho_v), "flags": flags})
                (bubble if kind == "bubble" else dew).append(row)
    validation = {"max_relative_pressure_residual": float(f"{worst['dp']:.3g}"),
                  "max_ln_fugacity_residual": float(f"{worst['dlnf']:.3g}")}
    return bubble, dew, failures, ends, validation


def dumps_compact(obj, indent=0):
    """JSON with one grid row per line (rows are flat dicts)."""
    pad = "  " * indent
    if isinstance(obj, dict):
        if all(not isinstance(v, (dict, list)) or (isinstance(v, list) and all(not isinstance(e, (dict, list)) for e in v))
               for v in obj.values()) and len(obj) <= 12:
            return json.dumps(obj, separators=(", ", ": "))
        items = [f'{pad}  {json.dumps(k)}: {dumps_compact(v, indent + 1)}' for k, v in obj.items()]
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"
    if isinstance(obj, list):
        if not obj:
            return "[]"
        if all(not isinstance(e, (dict, list)) for e in obj):
            return json.dumps(obj)
        items = [f"{pad}  {dumps_compact(e, indent + 1)}" for e in obj]
        return "[\n" + ",\n".join(items) + "\n" + pad + "]"
    return json.dumps(obj)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gerg-cpp", required=True)
    parser.add_argument("--departure", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    with open(args.gerg_cpp, "r") as handle:
        gerg_params, gerg_dep, exponent_form = parse_gerg_cpp(handle.read())
    with open(args.departure, "r") as handle:
        departure_json = json.load(handle)

    pairs_out = []
    summary = []
    for label, name1, name2, cas1, cas2, temperatures, light_index in PAIRS:
        comparison = compare_parameters(label, name1, name2, cas1, cas2, gerg_params, gerg_dep, departure_json)
        bubble, dew, failures, ends, validation = run_pair(label, name1, name2, temperatures, light_index)
        pairs_out.append({
            "pair": label, "component1": name1, "component2": name2, "cas1": cas1, "cas2": cas2,
            "temperatures": temperatures, "x1_grid": X_GRID,
            "traced_from": f"pure {name2 if light_index == 0 else name1} (light component {name1 if light_index == 0 else name2})",
            "parameter_comparison": comparison,
            "isotherm_ends": ends,
            "validation_at_full_precision": validation,
            "bubble": bubble, "dew": dew, "failures": failures,
        })
        counts = {}
        for f in failures:
            counts[f["kind"] + ":" + f["class"]] = counts.get(f["kind"] + ":" + f["class"], 0) + 1
        summary.append((label, len(temperatures) * len(X_GRID), len(bubble), len(dew), counts,
                        comparison["identical"], comparison["worst_reducing_relative_difference"]))

    fixture = {
        "schema": "createcheme.gerg2008.pilot-binaries/1",
        "description": ("GERG-2008 bubble and dew points of the six pilot binaries (P3 WP8, batch "
                        "2026-09-24-coolprop-low-temperature): model reference for the D7 K-value row, "
                        "model against model, not experiment"),
        "model": ("CoolProp HEOS mixture: GERG-2008 reducing functions and departure functions (Kunz and Wagner "
                  "2012) as shipped in CoolProp dev/mixtures, on CoolProp's reference pure-fluid equations "
                  "(Span et al. 2000 N2, Setzmann and Wagner 1991 CH4, Buecker and Wagner 2006 C2H6, Span and "
                  "Wagner 1996 CO2), not the GERG-2008 pure-fluid equations"),
        "generator": {
            "tool": "tools/gerg-bubble-points/gerg_bubble_points.py (git-ignored; main checkout tools/)",
            "command": ("python tools/gerg-bubble-points/gerg_bubble_points.py --gerg-cpp research/2026-09-24-"
                        "coolprop-low-temperature/sources/nist-aga8/GERG2008.cpp --departure research/2026-09-24-"
                        "coolprop-low-temperature/sources/coolprop/mixtures/mixture_departure_functions.json "
                        "--out src/test/resources/science/thermo/gerg2008/pilot-binaries.json"),
            "coolprop_version": CoolProp.__version__,
            "coolprop_gitrevision": CoolProp.__gitrevision__,
            "python": platform.python_version(),
            "platform": platform.platform(terse=True),
            "gerg2008_cpp": "usnistgov/AGA8 3bdb9ab8ff317c618b0b59d1b704c2c86ddc5fce, sha256 "
                            "901c03cd98263acae8d10480f9ada67ea9bbdf1a5a1b2f1b4a862a493266dec1",
            "gerg2008_cpp_exponent_form_found": exponent_form,
        },
        "units": {"T": "K", "P": "Pa", "rhoL": "mol/m3", "rhoV": "mol/m3",
                  "x1": "liquid mole fraction of component1", "y1": "vapour mole fraction of component1"},
        "method": {
            "flash": ("isothermal continuation in the mole fraction of the more volatile component of the "
                      "specified phase (liquid for bubble rows, vapour for dew rows), from 0.01 to 0.99 in 0.01 "
                      "steps plus every grid point, starting at 0.01 with CoolProp's own QT_INPUTS guess (dew traces fall back "
                      "on the first bubble point of the isotherm as the guess, and up to four later start "
                      "points) and continuing with update_with_guesses seeded by the previous point; a failed step is "
                      "retried with the step halved down to 1/64; the isotherm ends at the first composition "
                      "it cannot reach. Dew rows are therefore on the lower-pressure (normal) dew branch"),
            "validation": (f"each grid point recomputed from (T, rho, composition) with the phase imposed: "
                           f"|p/P - 1| <= {P_TOL}, |ln f_L - ln f_V| <= {LNF_TOL} per component; "
                           f"rhoL/rhoV >= {TRIVIAL_RHO_RATIO} during the trace; failures are listed, not filled"),
            "digits": ("10 significant digits; the residuals of each pair's validation_at_full_precision are "
                       "those of the unrounded values; recomputing P from the rounded (T, rhoL, x) reproduces it "
                       "only to about 2e-5 relative, because a liquid's pressure is stiff in its density"),
            "flags": {
                "near_critical": f"rhoL/rhoV < {NEAR_CRITICAL_RHO_RATIO} (proposed exclusion for the D7 row)",
                "above_10_MPa": "P above the pilot's 10 MPa ceiling",
                "liquid_unstable": "liquid fails the binary spinodal test d ln f1/d x1 > 0 at (T, P)",
                "liquid_stability_unchecked": "spinodal test could not find the liquid root at x1 +- h",
            },
            "failure_classes": {
                "beyond_isotherm_end": ("no point expected. Bubble: the grid composition lies beyond the end of "
                                        "the traced isotherm and the last traced point has rhoL/rhoV < 1.5 (the "
                                        "isotherm ends at its mixture critical point). Dew: the grid vapour is "
                                        "richer in the light component (by more than 0.005) than the richest "
                                        "equilibrium vapour found on the bubble trace of that isotherm"),
                "coolprop_error": ("the trace did not reach the grid point although a point is expected by the "
                                   "rules above: CoolProp failed"),
                "invalid_solution": "the trace reached the grid point but it failed validation",
            },
            "isotherm_ends": ("per kind and temperature: last light-component fraction reached, its pressure and "
                              "rhoL/rhoV, the error of the step that failed, start points that failed, and for "
                              "bubble traces the richest light-component vapour fraction found"),
        },
        "d7_targets": {"bubble_pressure_aad": 0.10, "bubble_pressure_point": 0.20, "abs_dy": 0.02,
                       "abs_ln_k": 0.15, "ln_k_species_threshold": 1.0e-3,
                       "scope": "outside the mixture critical region (P0 section 3.2, D7)"},
        "pairs": pairs_out,
    }
    text = dumps_compact(fixture) + "\n"
    with open(args.out, "w", newline="\n", encoding="ascii") as handle:
        handle.write(text)

    print(f"CoolProp {CoolProp.__version__} ({CoolProp.__gitrevision__})")
    print("pair       grid  bubble  dew  failures                                  params identical (worst)")
    for label, grid, nb, nd, counts, identical, worst in summary:
        print(f"{label:9s} {grid:5d} {nb:7d} {nd:4d}  {json.dumps(counts, sort_keys=True):40s}  {identical} ({worst:.1e})")


if __name__ == "__main__":
    sys.exit(main())

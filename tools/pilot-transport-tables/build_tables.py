"""Viscosity tables of the four pilot property records from CoolProp 8.0.0 (P3 WP3).

Batch 2026-09-24-coolprop-low-temperature. For N2, CH4, C2H6 and CO2:

- vapour: the zero-density (dilute-gas) viscosity, CoolProp HEOS evaluated at 1e-6 mol/m3, from the triple point to
  1200 K (the pilot envelope's ceiling);
- liquid: the saturated-liquid viscosity (Q = 0) from the triple point to the fluid file's critical temperature.

Nodes are chosen by bisection until log-linear interpolation (the ViscosityCorrelation LOG_TABLE rule) reproduces
CoolProp within TOLERANCE at the quarter points and the midpoint of every interval, with a maximum interval width;
the result is then checked at 16 interior points per interval and the worst deviation is recorded. Output:
viscosity-tables.json (numbers as Python repr, so it is byte-for-byte reproducible with CoolProp 8.0.0).

    <venv>/Scripts/python.exe build_tables.py > viscosity-tables.json
"""
import json
import math
import sys

import CoolProp
import CoolProp.CoolProp as CP

EXPECTED_VERSION = "8.0.0"
EXPECTED_REVISION = "ae81610e7d23efc57f9d051c8e70a4d66e87537f"
TOLERANCE = 5.0e-4
MIN_WIDTH = 1.0e-3
MAX_WIDTH = {"liquid": 20.0, "vapor": 50.0}
CEILING = 1200.0
# Fluid file name, triple point and critical temperature as the CoolProp fluid files state them (STATES / EOS[0]).
FLUIDS = {
    "nitrogen": ("Nitrogen", 63.151, 126.192),
    "methane": ("Methane", 90.6941, 190.564),
    "ethane": ("Ethane", 90.368, 305.322),
    "carbon_dioxide": ("CarbonDioxide", 216.592, 304.1282),
}


def evaluator(fluid, phase):
    state = CP.AbstractState("HEOS", fluid)

    def vapor(t):
        state.update(CP.DmolarT_INPUTS, 1.0e-6, t)
        return state.viscosity()

    numerical_critical = state.T_critical()
    critical_density = state.rhomolar_critical()

    def saturated(t):
        # At the fluid file's critical temperature, just above CoolProp's numerically located critical point, the
        # saturated liquid is the critical state itself: evaluated single-phase at the critical density.
        if t >= numerical_critical:
            state.update(CP.DmolarT_INPUTS, critical_density, t)
        else:
            state.update(CP.QT_INPUTS, 0.0, t)

    def liquid(t):
        saturated(t)
        return state.viscosity()

    def pressure(t):
        saturated(t)
        return state.p()

    return (vapor if phase == "vapor" else liquid), pressure


def interpolate(t, t0, t1, m0, m1):
    fraction = (t - t0) / (t1 - t0)
    return math.exp(math.log(m0) + fraction * (math.log(m1) - math.log(m0)))


def acceptable(f, t0, t1, m0, m1, phase):
    if t1 - t0 > MAX_WIDTH[phase]:
        return False
    if t1 - t0 <= MIN_WIDTH:
        return True
    for q in (0.25, 0.5, 0.75):
        t = t0 + q * (t1 - t0)
        if abs(interpolate(t, t0, t1, m0, m1) / f(t) - 1) > TOLERANCE:
            return False
    return True


def build(f, lo, hi, phase):
    nodes = [(lo, f(lo))]
    stack = [(hi, f(hi))]
    while stack:
        t0, m0 = nodes[-1]
        t1, m1 = stack[-1]
        if acceptable(f, t0, t1, m0, m1, phase):
            nodes.append(stack.pop())
        else:
            mid = 0.5 * (t0 + t1)
            stack.append((mid, f(mid)))
    worst = []
    for (t0, m0), (t1, m1) in zip(nodes, nodes[1:]):
        worst.append(max(abs(interpolate(t0 + k * (t1 - t0) / 17, t0, t1, m0, m1) / f(t0 + k * (t1 - t0) / 17) - 1) for k in range(1, 17)))
    # The overall worst, and the worst without the last interval (for the liquid, the last millikelvin below the
    # critical point, where the saturated-liquid viscosity has an infinite slope).
    return nodes, (max(worst), max(worst[:-1]))


def main():
    if CoolProp.__version__ != EXPECTED_VERSION or CoolProp.__gitrevision__ != EXPECTED_REVISION:
        sys.exit("expected CoolProp %s (%s), found %s (%s)" % (EXPECTED_VERSION, EXPECTED_REVISION, CoolProp.__version__, CoolProp.__gitrevision__))
    result = {"coolprop_version": CoolProp.__version__, "coolprop_revision": CoolProp.__gitrevision__,
              "tolerance": TOLERANCE, "species": {}}
    for key, (fluid, triple, critical) in FLUIDS.items():
        entry = {"fluid": fluid, "viscosity_model": CP.get_fluid_param_string(fluid, "BibTeX-VISCOSITY"),
                 "eos_maximum_temperature_kelvin": CP.PropsSI("Tmax", fluid)}
        vapor, pressure = evaluator(fluid, "vapor")
        nodes, worst = build(vapor, triple, CEILING, "vapor")
        entry["vapor"] = {"temperatures_kelvin": [t for t, _ in nodes], "viscosities_pascal_seconds": [m for _, m in nodes],
                          "worst_interpolation_deviation": worst[0]}
        liquid, _ = evaluator(fluid, "liquid")
        nodes, worst = build(liquid, triple, critical, "liquid")
        entry["liquid"] = {"temperatures_kelvin": [t for t, _ in nodes], "viscosities_pascal_seconds": [m for _, m in nodes],
                           "worst_interpolation_deviation": worst[0], "worst_interpolation_deviation_before_last_interval": worst[1],
                           "last_interval_kelvin": [nodes[-2][0], nodes[-1][0]],
                           "triple_point_pressure_pascal": pressure(triple), "critical_pressure_pascal": pressure(critical)}
        result["species"][key] = entry
        print("%s: vapour %d nodes (worst %.2e), liquid %d nodes (worst %.2e; %.2e below %.4f K)" % (key, len(entry["vapor"]["temperatures_kelvin"]),
              entry["vapor"]["worst_interpolation_deviation"], len(entry["liquid"]["temperatures_kelvin"]),
              worst[0], worst[1], nodes[-2][0]), file=sys.stderr)
    json.dump(result, sys.stdout, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()

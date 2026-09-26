"""CO2 liquid viscosity nodes below the triple point (P5, batch 2026-09-24-coolprop-low-temperature).

The pilot record createcheme:pilot_carbon_dioxide carries the CoolProp 8.0.0 saturated-liquid viscosity of CO2 (Laesecke and
Muzny 2017) as a log table from the triple point 216.592 K to the critical point (tools/pilot-transport-tables). Since P5 a
liquid carries CO2 below its triple point (CO2 dissolved in liquid methane, the mixture-stabilised liquid of the crystal
competition), and the network's logarithmic liquid mixing reads the pure CO2 liquid viscosity of every liquid that carries
it. This script builds nodes of [90 K, 216.592 K] for the subcooled (metastable) liquid: the Laesecke-Muzny correlation
evaluated by CoolProp at the density of the Span-Wagner equation's liquid branch at the table's own reference pressure
(517964.3433349451 Pa, the saturation pressure at the triple point), with the rule of build_tables.py (bisection until
log-linear interpolation reproduces the function within 5e-4 at the quarter points and the midpoint, maximum width 50 K,
then 16 interior checks per interval). At 216.592 K the value is the table's existing first node (the saturated liquid),
so the nodes from 216.592 K up stay unchanged byte for byte. Below the triple point no measurement exists: both the
equation of state's liquid and the viscosity correlation are extrapolated there, and the record's source text says so.

    "$TEMP/coolprop-probe-venv/Scripts/python.exe" co2_liquid_viscosity_below_triple_point.py > co2-liquid-viscosity-90K.json
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
MAX_WIDTH = 50.0
FLOOR = 90.0
TRIPLE = 216.592
REFERENCE_PRESSURE = 517964.3433349451
TRIPLE_VALUE = 0.0002533777943194043


def main():
    if CoolProp.__version__ != EXPECTED_VERSION or CoolProp.__gitrevision__ != EXPECTED_REVISION:
        sys.exit("expected CoolProp %s (%s)" % (EXPECTED_VERSION, EXPECTED_REVISION))
    state = CP.AbstractState("HEOS", "CarbonDioxide")
    state.specify_phase(CP.iphase_liquid)

    def pressure(rho, t):
        state.update(CP.DmassT_INPUTS, rho, t)
        return state.p()

    def density(t):
        # The liquid branch: the first density above the saturated liquid of the triple point where p rises through P_ref.
        rho, step, previous = 1150.0, 1.0, None
        while rho < 1800.0:
            f = pressure(rho, t) - REFERENCE_PRESSURE
            if previous is not None and previous[1] < 0.0 <= f:
                low, high = previous[0], rho
                for _ in range(200):
                    mid = 0.5 * (low + high)
                    if pressure(mid, t) - REFERENCE_PRESSURE < 0.0:
                        low = mid
                    else:
                        high = mid
                    if high - low <= 1.0e-12 * high:
                        break
                return 0.5 * (low + high)
            previous = (rho, f)
            rho += step
        raise ValueError("no liquid root at %g K" % t)

    def f(t):
        if t == TRIPLE:
            return TRIPLE_VALUE
        state.update(CP.DmassT_INPUTS, density(t), t)
        return state.viscosity()

    def interpolate(t, t0, t1, m0, m1):
        fraction = (t - t0) / (t1 - t0)
        return math.exp(math.log(m0) + fraction * (math.log(m1) - math.log(m0)))

    def acceptable(t0, t1, m0, m1):
        if t1 - t0 > MAX_WIDTH:
            return False
        if t1 - t0 <= MIN_WIDTH:
            return True
        return all(abs(interpolate(t0 + q * (t1 - t0), t0, t1, m0, m1) / f(t0 + q * (t1 - t0)) - 1) <= TOLERANCE for q in (0.25, 0.5, 0.75))

    # Continuity at the triple point: the metastable branch just below it against the saturated liquid.
    below = f(TRIPLE - 1.0e-6)
    nodes = [(FLOOR, f(FLOOR))]
    stack = [(TRIPLE, f(TRIPLE))]
    while stack:
        t0, m0 = nodes[-1]
        t1, m1 = stack[-1]
        if acceptable(t0, t1, m0, m1):
            nodes.append(stack.pop())
        else:
            mid = 0.5 * (t0 + t1)
            stack.append((mid, f(mid)))
    worst = max(max(abs(interpolate(t0 + k * (t1 - t0) / 17, t0, t1, m0, m1) / f(t0 + k * (t1 - t0) / 17) - 1) for k in range(1, 17))
                for (t0, m0), (t1, m1) in zip(nodes, nodes[1:]))
    json.dump({"coolprop_version": CoolProp.__version__, "coolprop_revision": CoolProp.__gitrevision__, "tolerance": TOLERANCE,
               "reference_pressure_pascal": REFERENCE_PRESSURE,
               "densities_kg_per_m3": [density(t) if t != TRIPLE else None for t, _ in nodes],
               "temperatures_kelvin": [t for t, _ in nodes], "viscosities_pascal_seconds": [m for _, m in nodes],
               "worst_interpolation_deviation": worst, "just_below_triple_relative_to_saturated": below / TRIPLE_VALUE - 1},
              sys.stdout, indent=1)
    sys.stdout.write("\n")
    print("CO2 liquid below the triple point: %d nodes, worst %.2e, continuity %.2e" % (len(nodes), worst, below / TRIPLE_VALUE - 1),
          file=sys.stderr)


if __name__ == "__main__":
    main()

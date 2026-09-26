"""CO2 zero-density viscosity nodes below the triple point (P4 stage 2b, batch 2026-09-24-coolprop-low-temperature).

The pilot record createcheme:pilot_carbon_dioxide carries the CoolProp 8.0.0 zero-density (dilute-gas) viscosity of
CO2 (Laesecke and Muzny 2017) as a log table from the triple point 216.592 K to 1200 K (tools/pilot-transport-tables).
Under the crystal competition a gas carries CO2 down to 90 K, so the network needs that viscosity there. This script
builds the nodes of [90 K, 216.592 K] with the same rule as build_tables.py (bisection until log-linear interpolation
reproduces CoolProp within 5e-4 at the quarter points and the midpoint, maximum width 50 K, then 16 interior checks per
interval), and prints them as JSON. They are prepended to the existing table, whose nodes from 216.592 K up stay
unchanged byte for byte.

    "$TEMP/coolprop-probe-venv/Scripts/python.exe" co2_vapour_viscosity_below_triple_point.py > co2-vapour-viscosity-90K.json
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


def main():
    if CoolProp.__version__ != EXPECTED_VERSION or CoolProp.__gitrevision__ != EXPECTED_REVISION:
        sys.exit("expected CoolProp %s (%s)" % (EXPECTED_VERSION, EXPECTED_REVISION))
    state = CP.AbstractState("HEOS", "CarbonDioxide")

    def f(t):
        state.update(CP.DmolarT_INPUTS, 1.0e-6, t)
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
               "temperatures_kelvin": [t for t, _ in nodes], "viscosities_pascal_seconds": [m for _, m in nodes],
               "worst_interpolation_deviation": worst}, sys.stdout, indent=1)
    sys.stdout.write("\n")
    print("CO2 vapour below the triple point: %d nodes, worst %.2e" % (len(nodes), worst), file=sys.stderr)


if __name__ == "__main__":
    main()

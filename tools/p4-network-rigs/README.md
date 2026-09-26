# p4-network-rigs

Batch `2026-09-24-coolprop-low-temperature`, P4 stage 2b (the fluid network on the crystal competition), 2026-09-25.
Stage document: `documentation/2026-09-24-coolprop-low-temperature/P4_NETWORK_COUPLING.md`.

## Purpose

`co2_vapour_viscosity_below_triple_point.py` builds the CO2 zero-density (dilute-gas) viscosity nodes from 90 K to the
triple point 216.592 K with CoolProp 8.0.0 (revision `ae81610e7d23efc57f9d051c8e70a4d66e87537f`, Laesecke and Muzny
2017), with the rule of `tools/pilot-transport-tables/build_tables.py` (bisection until log-linear interpolation
reproduces CoolProp within 5e-4 at the quarter points and the midpoint of every interval, maximum width 50 K, then 16
interior checks per interval). Under the crystal competition a gas carries CO2 down to 90 K, and the network's transport
reads the vapour viscosity of every node, so the pilot record needed the table there.

`co2-vapour-viscosity-90K.json` is its output of 2026-09-25: 20 nodes (90 K to 216.592 K), worst interpolation deviation
4.31e-4. The 19 nodes below 216.592 K were prepended to the vapour table of
`src/main/resources/data/createcheme/materials/properties/pilot_carbon_dioxide.json` (revision
`coolprop-8.0.0-dilute-gas-r2`, `temperature_min_kelvin` 90) by hand in commit `0ea358a`; the nodes from 216.592 K up
are r1's, unchanged (the 216.592 K value is the same CoolProp number in both).

## How to run

With the batch's throw-away venv (`uv venv "$TEMP/coolprop-probe-venv"`, `uv pip install coolprop==8.0.0 numpy`):

    "$TEMP/coolprop-probe-venv/Scripts/python.exe" co2_vapour_viscosity_below_triple_point.py > co2-vapour-viscosity-90K.json

## Reattachment

Nothing of this folder was ever in a tracked path, and no code left the tree in this stage (no probe, rig or Gradle
switch was added to a tracked path), so there is no reattach patch.

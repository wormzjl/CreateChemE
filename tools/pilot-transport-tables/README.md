# pilot-transport-tables

Batch `2026-09-24-coolprop-low-temperature`, P3 WP3 (bundled pilot package). Offline scripts; not part of the build or of any Gradle task.

## Purpose

1. `build_tables.py` builds the viscosity tables of the four pilot property records from CoolProp 8.0.0 (revision `ae81610e7d23efc57f9d051c8e70a4d66e87537f`; the script refuses another version):
   - vapour: the zero-density (dilute-gas) viscosity, HEOS at 1e-6 mol/m3, from each triple point to 1200 K;
   - liquid: the saturated-liquid viscosity (Q = 0) from each triple point to the fluid file's critical temperature (at that temperature, just above CoolProp's numerically located critical point, the critical state at the critical density).
   Nodes by bisection until log-linear interpolation (the `ViscosityCorrelation` `log_table` rule) reproduces CoolProp within 0.05 % at the quarter points and midpoint of every interval (maximum width 50 K vapour, 20 K liquid; minimum width 1 mK), then checked at 16 interior points per interval. Output: `viscosity-tables.json`.
2. `assemble_pilot_properties.py` writes `src/main/resources/data/createcheme/materials/properties/pilot_{nitrogen,methane,ethane,carbon_dioxide}.json` from `viscosity-tables.json` and `../pilot-volume-anchors/anchors.json`; the PR78 constants, molar masses, normal boiling points and standard densities are those of the bundled records the pilot replaces (`fluid_nitrogen`, `tjl20_methane`, `tjl19_ethane`) and of the P2 CO2 record, the `fluid_domain` is the pilot envelope (triple point to 1200 K, 100 Pa to 10 MPa), and there is no `ideal_gas_cp` (the spines supply the ideal gas, D6). `--check` compares with the committed files byte for byte (CRLF normalised).

## How to run

With the throw-away venv of the batch (`uv venv "$TEMP/coolprop-probe-venv"`, then `uv pip install coolprop==8.0.0 numpy` into it), from this folder:

    "$TEMP/coolprop-probe-venv/Scripts/python.exe" build_tables.py > viscosity-tables.json
    "$TEMP/coolprop-probe-venv/Scripts/python.exe" assemble_pilot_properties.py --anchors ../pilot-volume-anchors/anchors.json \
        --tables viscosity-tables.json --out ../../src/main/resources/data/createcheme/materials/properties [--check]

`viscosity-tables.json` of 2026-09-24: SHA-256 `fb6e2e5040dbdb114b25aa5ee62dd51cae03c0dfafa5d22b265e7d085195164f` (two runs identical).

## Ranges and accuracy (2026-09-24)

| Species | Viscosity model (CoolProp) | Vapour table | Nodes | Worst | Liquid table | Nodes | Worst (last interval) |
|---|---|---|---|---|---|---|---|
| N2 | Lemmon and Jacobsen 2004 | 63.151-1200 K | 70 | 0.048 % | 63.151-126.192 K, 12.52 kPa-3.3958 MPa | 77 | 0.049 % (0.56 % in the last 1 mK) |
| CH4 | Quinones-Cisneros and Deiters 2006 | 90.6941-1200 K, estimated above 625 K | 65 | 0.048 % | 90.6941-190.564 K, 11.70 kPa-4.5992 MPa | 73 | 0.049 % (0.29 %) |
| C2H6 | Friend, Ingham and Ely 1991 | 90.368-1200 K, estimated above 675 K | 59 | 0.049 % | 90.368-305.322 K, 1.14 Pa-4.8722 MPa | 100 | 0.049 % (0.36 %) |
| CO2 | Laesecke and Muzny 2017 | 216.592-1200 K | 43 | 0.050 % | 216.592-304.1282 K, 517.96 kPa-7.3773 MPa | 61 | 0.048 % (1.1 %) |

"Estimated above" is the upper limit of the reference equation of state CoolProp carries for the fluid (625 K methane, 675 K ethane): the dilute-gas correlation is extrapolated there, so those two vapour tables carry `estimated: true`. The liquid tables are saturation-pressure values and the vapour tables zero-density values: the network reads them at their own pressure (reference-pressure approximation, `MixtureViscosity`), so dense and supercritical states above 2 MPa get estimated viscosities (P3 design section 10). No liquid table extends above the critical temperature.

No code of this folder was ever in a tracked path; nothing to re-attach.

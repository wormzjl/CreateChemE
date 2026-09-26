# PR78 (mod formulation) versus CoolProp reference equations

Batch: `2026-09-24-coolprop-low-temperature`. Written 2026-09-24 for `documentation/2026-09-24-coolprop-low-temperature/UNIFIED_MULTIPHASE_THERMO_PLAN_REVIEW.md`. Research only; no product data, sources or tests changed.

## Purpose

Quantify how far the fluid network's current pure-component model is from reference-grade values at the states the unified multiphase thermo plan targets (cryogenic saturation, dense and supercritical fluids to 10 MPa, hydrogen at hydrotreating conditions), so the backend decision in the plan's P1 starts from numbers instead of from the model's name.

The Python model reproduces the mod's formulation exactly for a pure component:

- PR78 with the Soave kappa and the 0.491 acentric-factor split (`science/thermo/PengRobinsonKernel.kappa`), critical constants and acentric factors read from CoolProp (the bundled records cite CoolProp for their PR constants);
- a constant volume translation anchored so the translated liquid volume at 2 MPa equals the reference volume at the anchor temperature (`HydrocarbonModel` with the `liquid_calibration.json` points: nitrogen 90 K, methane 150 K, ethane 240 K; CO2 anchored at 250 K here, hydrogen untranslated);
- the network liquid path: liquid evaluated at the 2 MPa reference and carried to the state pressure by `GlobalLiquidResponse` with k = 1e-9 /Pa (`netpath%` columns);
- the ideal-gas heat capacity taken from CoolProp's `Cp0molar`, so every deviation in the tables is the residual (EOS) part.

Reference: CoolProp 8.0.0 (revision ae81610e7d23efc57f9d051c8e70a4d66e87537f, the same build the batch's earlier CoolProp survey queried) HEOS backend (Span et al. 2000 nitrogen, Setzmann and Wagner 1991 methane, Bücker and Wagner 2006 ethane, Span and Wagner 1996 CO2, Leachman et al. 2009 hydrogen).

## Files

- `probe.py`: the model and the state lists. Run with a Python that has `CoolProp` and `numpy` installed (a throw-away `uv venv` + `uv pip install CoolProp numpy` was used; nothing is installed system-wide).
- `probe-output.txt`: the run of 2026-09-24. Three tables: saturation (Psat, saturated liquid density raw / translated / network path, liquid cp, enthalpy of vaporization, reference isothermal compressibility), dense and supercritical single-phase states (density, cp, Z), and the CoolProp temperature ceilings of the reference equations.

## Reading the output

- `raw%`: untranslated PR78 liquid density error; `transl%`: with the constant translation evaluated directly at (T, Psat); `netpath%`: the network's actual liquid path. The network path fails wherever Psat(T) exceeds the 2 MPa reference pressure (nitrogen above about 115 K, methane above 165 K, ethane above 270 K, CO2 above 253 K), because the "liquid at 2 MPa" reference state no longer exists there; those rows show -20 % to -95 % and are the reason the plan's P3 must evaluate liquids directly at the state pressure.
- `kT ref`: the reference liquid isothermal compressibility, to compare with the global 1e-9 /Pa.
- Rows within about 5 % of the critical temperature (Tr above 0.95) are the cubic's known critical-region weakness; they are kept to show the size of the excluded neighbourhood, not to score the model.

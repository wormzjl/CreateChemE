# P1 items 2 and 4: alpha function and direct liquid evaluation

Batch: `2026-09-24-coolprop-low-temperature`. Written 2026-09-24 for `documentation/2026-09-24-coolprop-low-temperature/P1_ALPHA_AND_LIQUID_PATH_STUDY.md`. Research only; no product source, data or test changed; no Gradle run.

## Purpose

- Item 4 (review finding F1): measure the translated PR78 evaluated directly at the state (T, P), with the 2 MPa reference path and the global compressibility removed, against CoolProp HEOS on density, cp, enthalpy and fugacity coefficient, for two translation anchors: (a) the current calibration points, (b) the D1 anchor (saturated-liquid density at Tr = 0.8).
- Item 2 (decision D3): measure the Twu 1991 alpha against the Soave alpha in the same PR78 (unchanged a_c, b; translation re-anchored per alpha at Tr = 0.8), with the parameter provenance and the Le Guennec et al. 2016 consistency check.

## Files

- `pr78.py`: the pure-component model shared by both scripts. It is the model of `../pr78-vs-coolprop/probe.py` (Soave kappa with the 0.491 split, constant translation, h gets +P c and ln phi gets +P c/(RT) as `TranslatedPengRobinson` does), plus a pluggable alpha (Soave or Twu), the two translation anchors, a Newton saturation solver and a Newton polish of the cubic roots. The polish matters: without it the liquid root loses about 1e-3 relative accuracy at pressures of a few pascal, which made the probe's finite-difference cp at ethane 90.4 K read -8.8 % instead of -10.6 %.
- `item4_direct_liquid.py` -> `item4-output.txt` (summary tables) and `item4-points.csv` (every state: fluid, set, T, P, Tr, Pr, CoolProp phase class, critical-band flag, reference values, deviations for anchors a and b). About 1 s.
- `item2_twu_alpha.py` -> `item2-output.txt` (parameter routes, consistency results, fits, saturation and dense-state comparisons). About 45 s (Nelder-Mead fits).

## How to run

From Git Bash, with the throw-away probe environment (CoolProp 8.0.0, numpy, scipy):

```
uv venv "$TEMP/coolprop-probe-venv" --python 3.12      # only if missing
uv pip install --python "$TEMP/coolprop-probe-venv/Scripts/python.exe" CoolProp numpy scipy
cd research/2026-09-24-coolprop-low-temperature/p1-alpha-and-liquid-path
"$TEMP/coolprop-probe-venv/Scripts/python.exe" item4_direct_liquid.py
"$TEMP/coolprop-probe-venv/Scripts/python.exe" item2_twu_alpha.py
```

## Conventions

- Reference: CoolProp 8.0.0 HEOS; critical constants and acentric factors from CoolProp; the model's ideal-gas part is CoolProp's, so every deviation is the residual model's plus the translation.
- Enthalpy: h - h_ig(298.15 K, 0.1 MPa) in both models; deviations in J/mol.
- Fugacity: absolute difference of ln phi (x100 is about the percent error on phi).
- Root choice (item 4): CoolProp phase `liquid`/`supercritical_liquid` -> liquid root, `gas`/`supercritical_gas` -> vapour root, `supercritical` -> lowest-Gibbs root. Saturated states are evaluated at (T, Psat_ref) on the root named by the quality.
- Critical band: Tr 0.95 to 1.1 with Pr 0.8 to 1.5, flagged and excluded from means.
- Twu parameters: route (1) tc-PR table values from the Clapeyron.jl copy (MIT) at master `0778184abbe0de50791b6338cffe3444d4017508`, `database/cubic/tcPR/tcPR_single.csv`, because the ACS supporting information of Pina-Martinez et al. 2018 returned HTTP 403; hydrogen has no entry, the generalized omega correlations fail the consistency check at omega = -0.219, and the own fit (route 3) degenerates to alpha = 1. Fitted values are marked as not literature.

# Component-based crude and atmospheric-residue viscosity plots

Local research output, 2026-09-17. No production JSON, thermodynamic properties, Java implementation, or saved state was changed. These files are ignored by Git.

This is the current deliverable, superseding the earlier bulk-assay fits in the parent directory. **No measured whole-crude or aggregate-residue viscosity is used to construct or tune these curves.** All six cases are rebuilt from their implemented 20-component compositions. The graph ordinate is **dynamic viscosity in Pa·s** (multiply by 1,000 for cP), not kinematic viscosity in cSt.

## What is plotted

- Tia Juana Light with the implemented synthetic methane addition, WTI Light Export, Upper Zakum, Bonga, Dalia, and Cold Lake Blend.
- Whole-feed fixed-composition liquid viscosity, plus an ideal 370°C+ cut reconstructed on the same component basis.
- 0–500°C, with linear and logarithmic Y axes. Comparison figures and a four-panel figure for each crude are supplied as PNG and editable SVG.
- Main colored curves have the verified PC12/13 unit error repaired **in this research calculation only**. Individual figures also show the original catalog reconstruction in gray dotted lines.
- `heavy_components_audit.png` shows why the corrected numbers still cannot be treated as reliable heavy-oil predictions.

## Composition and ideal residue

The script reads the actual package-selected property files and assay amounts. Mass-based feeds are converted to moles using the selected component molecular weights. Tia Juana's molar feed is used directly. Fractions are normalized without changing order or discarding light components. Molecular weights, boiling points, densities, PR parameters, and heat capacities are not altered.

The simulator's existing mixture rule is retained:

```
ln(mu_mix) = sum_i x_i ln(mu_i)
```

Here x is mole fraction and mu is dynamic viscosity. A fitted temperature curve for a measured sample does not independently validate this mixture rule. No viscous interaction coefficients have been fitted.

For the ideal residue, the catalog's midpoint-derived NBP intervals are intersected with 370°C+. PC01–06 and the seven explicit light chemicals are excluded; PC08–13 are fully retained. PC07 spans approximately 329.149–377.875°C, so about 16.162% of that component is retained. This assumes uniform amount per degree within PC07; since its molecular weight is fixed, its mass and mole retention factors are identical. Retained moles are then normalized. The exported JSON contains the exact factors and mass/mole yields.

These are estimated cuts of the **implemented discretized composition**, not exact source-assay 370°C+ yields and not a column bottoms simulation. No terminal upper boiling limit is invented for PC13. The original imported feeds already include an estimated heavy TBP tail, which remains unchanged.

## Component curves and 0–500°C coverage

Inside each admitted liquid-table domain the script reproduces the catalog's linear interpolation of ln(mu) against temperature. Above the pure-liquid domain, the existing conditional-solute curves are used where available, following `MixtureViscosity` for the explicit light chemicals. A conditional-solute factor is not a pure-liquid methane viscosity.

Outside a table's domain, the research script continues ln(mu) linearly against 1/T, using the nearest two endpoint nodes. This is a local Andrade continuation, chosen explicitly for this plot; it is not a measured or newly validated correlation. Values at the boundary are continuous. There is no arbitrary viscosity cap.

The raw-input table coverage common to a whole feed is approximately 20–210.39°C, limited by PC01 at the upper end. For the ideal residue the raw-input tables cover 20–500°C. Dashed lines and shaded regions mark where at least one component needs a table-range extension. **Solid lines only denote table-range coverage, not physical validation.** The bad heavy-component formulas remain unqualified even within their stored ranges.

All curves keep the chosen composition fixed. No flash calculation is performed, no pressure is selected to force an all-liquid phase, and no gas evolution, wax, glass formation, yield stress, gelation, or thermal cracking is modeled. The underlying viscosity tables use a 100 kPa reference with no pressure correction. The graphs therefore do not describe atmospheric heating of an actual sample to 500°C, nor establish that a sample is liquid or flowable at 0°C.

## What was corrected, and what was not

The [public DWSIM FluidProperties implementation](https://github.com/DanWBR/dwsim/blob/windows/DWSIM.Thermodynamics/PropertyPackages/Models/FluidProperties.vb) returns kinematic viscosity from `oilvisc_twu` but dynamic viscosity from `viscl_letsti`. The petroleum path in [PropertyPackage.vb](https://github.com/DanWBR/dwsim/blob/windows/DWSIM.Thermodynamics/PropertyPackages/PropertyPackage.vb) substitutes the latter when the former is NaN, then multiplies by density. The installed DWSIM probe confirms this extra multiplier for PC12/13.

The plotted unit repair evaluates the same fallback directly in Pa·s from each component's unchanged MW, Tc, Pc, and acentric factor:

```
Tr = T / Tc
eta0 = 0.001 (2.648 - 3.725 Tr + 1.309 Tr^2)
eta1 = 0.001 (7.425 - 13.39 Tr + 5.933 Tr^2)
xi = 0.176 [Tc / (MW_g_mol^3 Pc_bar^4)]^(1/6)
mu_Pa_s = (eta0 + omega eta1) / xi / 1000
```

It does **not** multiply by density or divide by a guessed constant reference density. Sixteen recorded PC12/13 probe points verify both the direct fallback and the erroneous multiplication, with a maximum relative discrepancy below 3e-15. This repairs dimensional consistency; it does not repair the choice of correlation.

PC12/13 are far below the stated Letsou–Stiel reduced-temperature application range across the plotted temperatures; the resulting low heavy-cut viscosities are not credible calibrated residue properties. PC11 retains its original extreme Twu-derived behavior because there is no verified replacement calibration for that component. A simple smoothing operation, viscosity ordering constraint, or borrowed Cold Lake anchor would conceal the missing characterization rather than establish it.

The reviewed [Mehrotra–Eastick–Svrcek fraction study](https://doi.org/10.1002/cjce.5450670620) supports fraction-level temperature fitting and reconstruction from fractions. Its measured Cut 1/4/5 are not identified with our PC01–13, and the full cut boundaries and corrected fitting tables were not recovered. Its [erratum](https://doi.org/10.1002/cjce.5450680226) must also be checked before adopting its equations. No paper values were assigned to our components on the basis of cut number alone.

The [2020 viscosity-mixing study](https://doi.org/10.1021/acs.energyfuels.0c01231) also shows why the mixture law requires separate qualification; it does not establish the current molar log-dynamic rule for these six feeds. A physical heavy-end correction still requires mapped fraction measurements or a qualified residue model with the missing chemical-quality inputs. Consequently these are **component-model diagnostics, not literature-calibrated predictions**.

## Results and verification

See `values.md` for selected temperatures and yields, `reconstruction.json` for all 2,001 temperatures, compositions, input hashes, component curves, raw mixture curves and unit-repaired mixture curves. The model predicts Dalia above Cold Lake Blend in ambient whole-feed viscosity; that ordering arises from the current shared pseudo properties and mixing rule, and is not a validated crude-property conclusion.

The Python checks verify normalization, finite positive curves, mixture bounds, decreasing mixture curves, all catalog interpolation nodes, endpoint continuity, and the independent installed DWSIM unit audit. `VerifyReconstruction.java` calls the real production `MixtureViscosity` against the raw reconstruction at 72 feed/residue/temperature combinations, including residues at 400 and 500°C. Maximum relative difference: 3.56e-15. These are computational consistency checks, not experimental accuracy claims.

Run the local script from its existing repository location with Python plus NumPy and Matplotlib:

```
python research/viscosity-temperature-plots/component-reconstruction/reconstruct.py
```

The configured desktop runtime is `C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`; this script first checks the local `build/viscosity-plot-deps` library directory. Production data and the ignored prior DWSIM probe are required to regenerate the figures. The archive contains generated component curves and results, so viewing and inspecting it does not require those inputs.

The Java source launcher needs the compiled main classes, `src/main/resources`, and Gson on its classpath. Pass `reconstruction.json` and `java-verification.json` as its two arguments.

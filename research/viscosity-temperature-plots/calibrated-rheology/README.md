# Crude-specific transport calibration with cold rheology

Local research, 17 September 2026. The user authorized calibration against whole-crude and residue observations while retaining the component-based reconstruction. This work does not modify production Java, catalog JSON, component identities, feed amounts, molecular weights, densities used by the thermodynamic model, NBP, PR, or Cp. Research files remain Git-ignored.

## What is now supported by data

The producer assays contain more transport information than was used in the earlier screens. They report **110 viscosity points for individual source cuts**, 30 whole-crude/residue viscosity points, cut pour/cloud points, and whole/residue/heavy-cut total-wax contents. The earlier claim that wax-content data were unavailable was too broad. DSC precipitation curves, carbon-number wax distributions, test shear rates, and crude-specific yield-stress data are still unavailable.

| Crude | Reported whole wax, wt% | Reported 370°C+ wax, wt% |
|---|---:|---:|
| [WTI Light Export](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/wti_light.pdf) | 11.5 | 15.7 |
| [Upper Zakum](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/upper_zakum.pdf) | 12.4 | 9.8 |
| [Bonga](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/2025/bonga.pdf) | 2.8 | 1.8 |
| [Dalia](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/dalia.pdf) | 1.4 | 1.9 |
| [Cold Lake Blend](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/2024/cold_lake_blend.pdf) | 0.8 | 0.2 |

These are reported analytical values, not DSC solid fractions at a given temperature. Printed 0.0 is retained as a nominal zero; detection limits and analytical uncertainty are unspecified. In particular, Cold Lake's 550°C+ cut reports 0.0 wt% wax but **1,040,038 cSt at 100°C**. Its very high viscosity must not be fabricated by assigning it a large paraffin inventory.

The source PDFs are SHA256-checked against the original imported assays. Extraction uses column coordinates and retains blanks, including the extra IBP-C5 column in four layouts. `source_transport.json` preserves the extracted records. The producer cautions that assay information is a mixture of data and information; it does not identify every entry as a direct measurement.

## Component reconstruction and mixture calibration

1. Fit the 9 source intervals with viscosity data for each crude using the extended Walther transform. The three unmeasured naphtha intervals retain the earlier light-component estimate.
2. Rebin those log-viscosity curves onto the unchanged 13 pseudocomponents using the existing source-cut-to-component mass allocations. The seven explicit light chemicals retain their existing transport curves. A 550°C+ source curve is shared across its unresolved heavy tail; no invented viscosity gradient is attached to PC11–13.
3. Use the original molar logarithmic mixture basis, augmented by symmetric pair-interaction terms. Bulk fitting never changes the pure-component limit or arbitrarily inflates naphtha viscosities.
4. Reconcile the component wax inventory to both reported whole-crude and 370°C+ totals. Known heavy-cut wax patterns are retained up to a fitted scale; missing distillate wax allocation uses an explicit gas-oil-weighted shape. This is a mass-balanced estimate, not measured wax composition for every pseudo cut.

The extended Walther transformation uses nu in cSt:

```
log10(log10(Z)) = a + b log10(T_K)
Z = nu + 0.7 + exp(-1.47 - 1.84 nu - 0.51 nu^2)
```

This is not a claim of full ASTM D341 compliance. Density for transport conversion is approximated as `rho(T) = rho15 / [1 + 0.0007 (T_C - 15)]`. Source-cut densities are used for their curves; reported aggregate densities are used for comparing predicted aggregate kinematic viscosities. Thermal expansion is assumed, not measured, so dynamic-viscosity accuracy is additionally density-limited. These research density conversions do not alter production physical properties.

The mixture structure follows the interaction-correction idea of [Grunberg and Nissan](https://doi.org/10.1038/164799b0), with an explicitly chosen two-group reduction:

```
ln(mu_L) = sum_i x_i ln(mu_i) + sum_(i<j) 2 x_i x_j G_ij(T)
G_ij(T) = (1-h_i h_j)[a_L + b_L E_ij q(T)]
          + h_i h_j [a_H + b_H E_ij q(T)]
q(T) = 1000 [1/(min(T_C,100)+273.15) - 1/323.15]
E_ij = (E_i + E_j)/2
```

`h_i` is the existing estimated 370°C+ retention factor. `E_i` is the source curve's positive slope against `1000/T` between 40 and 50°C. The four coefficients per crude are fitted to whole-crude values at 20/50°C and residue values at 50/100°C. Temperature dependence of the empirical interaction correction is held fixed above 100°C. The source component temperature curves continue beyond that, explicitly as extrapolation.

This grouping and temperature form are research choices; they are not a published universal petroleum parameter set. Their pure-component correction is identically zero. The coefficients are crude-specific and are not validated for arbitrary mixtures of different crude presets or for chemical conversion.

The nominal 100 s^-1 reference rate used during calibration is a modeling convention. The assay PDFs report kinematic viscosity but do not supply a corresponding shear-rate protocol. At almost all fitted temperatures the chosen cold branch is inactive; Cold Lake residue at 50°C has a small modeled correction. Fitting subtracts that yield contribution and divides out the crowding multiplier before solving the linear interaction system.

## Cold rheology: added, but not independently identified

The earlier ideal-solid calculation could not establish a realistic wax distribution from the parent MWs. This version therefore uses a **bounded empirical precipitation profile constrained by reported wax inventory and a pour-point-based transition prior**. It is not relabeled as a thermodynamic solid-equilibrium calculation.

For each complete crude/residue, set a reference structure-onset temperature `Tg = reported pour point + 10°C`, with 5–20°C tested as a sensitivity range. Then:

```
r(T) = 1 - exp[-max(Tg - T, 0)/10 K]
solid_i = initial_parent_moles_i * wax_share_i * r(T)
liquid_i = initial_parent_moles_i - solid_i
```

Wax and nonwax virtual parts retain the parent MW for this inventory calculation. The remaining liquid is renormalized and its viscosity recalculated with the component/pair-interaction model. Material is conserved; isolated slurry viscosities are not blended. The same temperature profile for all virtual wax within one stream is a simplification. Measured source-cut cloud points are retained for future characterization, but are not silently equated with a complete crude's WAT.

Crystal volume uses 900 kg/m³; liquid volume uses the estimated aggregate density. The constitutive relation for a fixed prior structural state lambda is:

```
mu_disp = mu_L (1 - phi/0.64)^(-1.6)
tau_y = lambda * A * (w_s/0.05)^2.3
mu_app(T, shear_rate, lambda) = mu_disp + tau_y/shear_rate
```

The reference is a rested state, lambda = 1, evaluated at 0.1, 1, 10, and 100 s^-1. The stress law at fixed structure is monotone with rate. At zero imposed shear, the code refuses to fabricate a finite apparent viscosity. A stress-controlled material below tau_y would remain unyielded in this simplified Bingham interpretation; the plots instead show the stress-required response at imposed positive rates.

The exponent 2.3 is informed by [Venkatesan et al., 2005](https://doi.org/10.1016/j.ces.2005.02.045), whose Figure 14 relates yield stress to precipitated wax in a particular model wax/oil system at 40°F. **A = 500 Pa at 5 wt% solid wax is an order-of-magnitude visual prior from that figure, not a printed universal coefficient or a fitted value for these crudes.** The calculations sweep A = 50, 500, and 5,000 Pa. No reported pour point is used to claim that A has been measured.

A simple structural-history state is also provided:

```
d(lambda)/dt = [1 - lambda - (pre_shear_rate/10 s^-1)*lambda]/300 s
```

The analytic solution is used for the pre-sheared comparison (100 s^-1 for 300 s), with subsequent curves evaluated at a fixed resulting structure. The 300 s rebuild time and 10 s^-1 breakdown scale are assumptions. This simple monotone breakdown model does not reproduce the shear-induced strengthening seen in some experiments, and is not a validated cooling-history model.

The shaded band in the 10 s^-1 plots spans the selected strength/onset scenarios and one pre-sheared state. It is **not a confidence interval** and does not include all liquid-extrapolation, density, composition or model uncertainty. In low-wax Cold Lake material, the steep source-based liquid viscosity is often dominant; no asphaltene/glass yield stress is invented to force a pour-point match.

**Pour point remains an input that locates a transition prior, not an independently reproduced standardized flow-test result.** A unique realistic low-temperature curve still requires DSC/precipitated-wax data and shear/time-dependent rheometry. The extreme sub-pour liquid continuations can represent no stable liquid state; they must not be installed as certified material properties.

## Validation and limits

The 20 calibration anchors are reproduced to floating-point precision, as expected from four coefficients per crude. The additional whole-crude 40°C and residue 60°C points were not used in solving those coefficients. Across these **10 same-assay temperature checks**, the maximum absolute error is **3.87%** and mean absolute error **0.874%**, evaluated in the original cSt units. See `validation.md`. This is a local interpolation check, not blind external validation, proof of unique parameters, or validation of the cold gel branch.

Checks also cover all pure-component limits of the interaction model, positive finite curves, monotonic pure-cut temperature curves, component/total mass conservation, exact whole/residue wax inventories, high-temperature/no-wax recovery, positive stress-rate slope at fixed history, and history-state limits.

The full plots retain 0–500°C for comparison with the original request; cold details extend to -60°C so the reported whole-crude pour points are included. Neither vaporization, pressure-dependent viscosity nor cracking is calculated. Extrapolation above the measured source-cut range, particularly toward 500°C, remains unqualified.

Tia Juana lacks a matched transport/wax/pour assay. Its separate transfer plot applies the five fitted donor descriptions to the unchanged Tia composition and shows a range. **No donor pour point, wax inventory or gel response is assigned to Tia Juana**, and the range is not a statistical uncertainty interval.

## Files and regeneration

- `index.html`: gallery, calibration checks and source links.
- `*_cold_detail.png/svg`: previous/new curves, four shear rates, and selected cold-model sensitivities.
- `*_full_range.png/svg`: whole/residue linear/log comparisons over 0–500°C.
- `*_cut_transport.png/svg`: all 13 source-rebinned cut curves versus the old common dataset.
- `calibrated_results.json`: temperatures, component curves, mixture coefficients, wax inventory, rate-dependent outputs and assumptions.
- `verification.json`, `validation.md`: mathematical checks and the ten excluded-temperature comparisons.
- `extract_transport.py`, `model.py`, `rheology.py`, `build_results.py`, `package_results.py`: reproducible local research code.

Use the existing Python runtime with NumPy, Matplotlib and pdfplumber. Run extraction, `build_results.py`, then `package_results.py`. The original source PDFs, existing composition allocations and catalog files are required for regeneration. Source articles and QA images are deliberately excluded from the delivery archive.

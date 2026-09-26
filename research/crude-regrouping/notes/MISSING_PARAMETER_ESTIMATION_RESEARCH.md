# Estimating missing crude-cut chemistry

Research and screening, 2026-09-15. No estimator in this note has replaced the production profiles. The existing physical and thermodynamic datasets are unchanged.

Scope clarification, 2026-09-17: all cut-quality profiles and estimators are research artifacts only. The premature Java integration and appended production assay data were reverted. References below to existing chemistry profiles mean the research JSON files, not runtime catalog records.

## Recommended direction

Use **constrained estimation of assay-specific chemistry**, with reported source data, correlation estimates and explicit assumptions stored separately. There is useful information for narrowing the gaps, but no verified formula found here can independently recover every cut's C/H/O/SARA/metals from boiling point alone.

Use the producer's actual cut density, viscosity and characterization data as estimator inputs. They may be stored as evidence alongside a quality profile without replacing the shared TJL density, MW, Cp or PR values. Feeding the shared TJL values into every crude's estimator would suppress the very differences the quality layer is intended to capture.

| Missing quantity | Best next method | Current readiness |
|---|---|---|
| 550°C+ hydrogen | Whole-crude balance plus uncertainty-aware reconciliation; assess correlations as weak priors | Balance available; direct replacement correlation not qualified |
| Carbon | Elemental closure after defining oxygen and other untracked matter | Conditional estimate possible; not independently identifiable |
| Oxygen | Direct analysis, or explicit matched-feed/molecular-family prior constrained by available chemistry | TAN supplies only limited conditional information |
| Residue SARA | A vacuum-residue-specific model using real density, viscosity, carbon-residue and asphaltene evidence | Promising; exact coefficients and analytical basis still need qualification |
| Whole-crude saturates | Density/pour-point correlation | Screened here; not a cut or full-SARA model |
| Missing Ni/V/Fe/N distribution | Constrained reconciliation of cut and aggregate data | Can improve the existing zero-fill assumption |
| Na/Hg/As distribution | Separate chemical-form/phase-partition scenarios | No universal TBP-only allocation justified |
| Kinetics/product yields | Fit a small network to process-specific experiments | Cannot derive uniquely from elemental composition |
| Chemical energy/reaction heat | Measured heat of combustion or conditional elemental HHV correlation, with consistent reference states | Suitable for screening; precision may be inadequate for reactor heat differences |

## 1. Hydrogen: a concrete correlation, with an important limit

The Phillips residual-oil characterization patent gives:

`H (wt%) = −20.77(SG60/60 − 0.8510) + 0.58(K − 12.5) + 14`

The published calibration ranges include SG 0.8838–1.0736, Watson K 10.3–12.1 and at most 53.3% material above 1000°F. Its total-hydrogen regression error was 0.28 wt% across 367 samples. These are calibration statistics, not uncertainty guarantees for our feeds. [Primary patent, claims and calibration table](https://patents.google.com/patent/US6275776B1/en).

I applied the equation as an **out-of-domain diagnostic**, using each reported residue API gravity to calculate `SG60/60 = 141.5/(API+131.5)` and its reported UOPK:

| Crude | Existing balance estimate, H wt% | Phillips extrapolation, H wt% |
|---|---:|---:|
| WTI Light - Export | 14.34 | 13.11 |
| Upper Zakum | 11.82 | 9.60 |
| Bonga | 11.56 | 10.38 |
| Dalia | 12.26 | 10.30 |
| Cold Lake Blend | 11.07 | 8.60 |

Every isolated 550°C+ cut is entirely above 1000°F (537.78°C), outside the stated mixture calibration. Other descriptor ranges can fail too. The mismatch is not proof that either estimate is correct. Do not automatically replace our balance estimates or attach the patent's regression error to these extrapolations.

The Goossens hydrogen correlation is another candidate, but it requires density and refractive index at 20°C and the 50 wt% TBP temperature. Its abstract also identifies oxygen as an exception to otherwise implicit heteroatom effects. We do not currently have those exact inputs for the residue. Estimating refractive index adds another uncertain model, rather than another independent observation. [Goossens, 1997](https://doi.org/10.1021/ie960772x).

For now, improve the mass balance rather than substitute a less applicable equation. Keep reported lighter-cut H and whole-crude H as observations with precision/uncertainty, keep exact chemical formulas fixed, and fit the unresolved residue H with a soft prior. WTI needs especially broad uncertainty because dividing a whole-crude discrepancy by its small residue mass magnifies the error. Do not impose decreasing hydrogen versus boiling point as a universal rule.

## 2. Oxygen and carbon: conditional estimates are possible

If all titrated acidity were due to carboxyl groups, stoichiometry gives:

`acid-associated oxygen equivalent (wt%) = TAN (mg KOH/g) × 0.05703`

This uses two oxygen atoms per acid equivalent and the molecular weight of KOH. It is **not total oxygen and not an unconditional lower bound**: acid functionality and titration response must satisfy the assumption. Neutral oxygen compounds are not captured. A study of crude-oil oxygen classes found no positive relationship between O2-class relative abundance and TAN and found oxygen-containing classes in both acidic and nonacidic fractions. [Primary experimental study](https://doi.org/10.1021/acs.energyfuels.6b01597).

| Crude | Whole-crude TAN, mg KOH/g | Conditional acid-oxygen equivalent, wt% |
|---|---:|---:|
| WTI Light - Export | 0.05 | 0.00285 |
| Upper Zakum | 0.07 | 0.00399 |
| Bonga | 0.51 | 0.02909 |
| Dalia | 1.50 | 0.08555 |
| Cold Lake Blend | 1.12 | 0.06388 |

These numbers cannot fill our missing oxygen fields. With O and all other material accounting specified on a consistent basis, carbon can be computed as the remainder:

`w_C = 1 − w_H − w_O − w_N − w_S − sum(other disjoint elemental/material inventories)`.

For a gameplay-oriented estimate, create named oxygen scenarios or a calibrated matched-feed prior, then compute carbon for each scenario. A change of +0.5 percentage points in assumed O changes inferred C by −0.5 points, all else fixed. This is exact sensitivity accounting, not evidence that 0.5 wt% is the correct oxygen concentration for any crude. Do not count measured metals both individually and again inside an ash fraction.

A more elaborate route is molecular-family reconstruction: select plausible hydrocarbon and heteroatom-bearing families and fit nonnegative family weights to H, S, N, TAN, asphaltenes, boiling data and actual source density. A published characterization approach fits molecular distributions to multiple assay properties. It is a useful architecture reference, not evidence of a unique solution from our limited inputs. [Primary characterization disclosure](https://patents.google.com/patent/US20130185044A1/en).

Such reconstruction can operate in the quality layer. Retain multiple plausible solutions or regularize toward a declared prior; do not overwrite the production thermo properties with the reconstruction's fitted physical properties.

## 3. SARA: separate whole-crude and vacuum-residue models

A 2023 study estimates whole-crude saturates from density and pour point. Its density-only equation has an average absolute deviation of 3.3 wt%; the density/pour-point equation improves this to 2.5 wt% with a 6.6 wt% maximum deviation on the reported evaluation. Equations 10–11 were checked against the rendered paper before coding the screen. [Primary paper](https://doi.org/10.3390/pr11020420).

| Crude | Density-only saturates, wt% | Density + pour point, wt% | Input-range note |
|---|---:|---:|---|
| WTI Light - Export | 84.47 | 82.13 | Within reported scalar ranges |
| Upper Zakum | 61.57 | 60.56 | Within reported scalar ranges |
| Bonga | 52.25 | 52.55 | Within reported scalar ranges |
| Dalia | 44.04 | 45.13 | Pour point −48°C is below the study's −45.6°C minimum |
| Cold Lake Blend | 39.13 | 40.63 | Within reported scalar ranges |

These are whole-crude prior estimates, not measurements or predictions for individual 550°C+ cuts. Matching input ranges alone does not validate a new feed.

Our producer PNA figures cannot directly fill SARA. Cold Lake reports 15.1% paraffins, 21.6% naphthenes and 62.7% aromatics; adding its 9.9% C7 asphaltenes totals **109.3%**. They cannot be treated as four disjoint fractions. [Cold Lake producer assay](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/2024/cold_lake_blend.pdf). Likewise, pentane-insoluble and heptane-insoluble asphaltenes need distinct method labels.

For residue, Samie and Mortaheb's 2021 model is a better candidate: it uses density, viscosity and Conradson carbon residue and predicts all four SARA fractions. The published abstract reports RMSEs of about 3.99/3.95/2.27/1.53 wt% for saturates/aromatics/resins/asphaltenes. I verified the input requirements and error summary, but have not obtained and verified the complete coefficient tables and viscosity/reference-condition conventions, so it has not been implemented. [Primary paper](https://doi.org/10.1016/j.fuel.2021.121609).

Our assays provide MCR rather than a labeled CCR measurement. ASTM D4530 describes equivalence with the Conradson test, making this a reasonable proxy to assess, while retaining the actual analytical-method label and checking model sensitivity. It remains an indicator of coke-forming tendency rather than reactor coke yield. [ASTM D4530 scope](https://store.astm.org/d4530-00.html).

For thermal conversion, a particularly relevant follow-on is the 2024 DSARA model for visbroken bitumen, vacuum bottoms and deasphalted oil. It links distillate and residue SARA composition to conversion using feed characterization and measured product data. It uses **pentane-insoluble** asphaltenes, whereas our profiles contain C7 values; that mismatch must be addressed before importing coefficients. [Beleno et al., 2024](https://doi.org/10.1021/acs.energyfuels.4c03849).

## 4. Trace elements: improve reconciliation before inventing distributions

The present zero-fill-plus-rescaling is a declared first approximation. A better next estimator would use all three observation levels: whole crude, individual source cuts and the reported 370°C+ aggregate. Treat rounded values as observations, not mutually exact equations. Estimate unknown cut concentrations with nonnegativity, total-inventory closure and weak distribution priors. Preserve raw observations and report the fitted residuals.

For instance, a missing 370–450°C metal inventory is constrained by the 370°C+ aggregate minus the measured heavier-cut inventories. If rounding makes this difference negative, solve a constrained reconciliation rather than silently clamping and claiming a measured zero. A printed zero may be assigned a reporting-precision interval as an explicit rounding assumption; that is not a known analytical detection limit. A blank has no such bound.

Na, Hg and As require different treatment. Sodium commonly appears in extractable salts, so a brine/desalter inventory is more useful than assigning it to a petroleum boiling cut. Extraction experiments show strong differences between metal cation and chloride behavior. [Primary crude-salt study](https://doi.org/10.1021/bk-2019-1320.ch012).

Mercury partitioning depends on chemical form; studies of petroleum processing residues found different species distributions across process locations and feed types. A total Hg concentration is insufficient to infer a unique TBP distribution. [Primary Hg speciation study](https://pubmed.ncbi.nlm.nih.gov/29224346/). For Hg/As, use named volatile/dissolved/particulate partition scenarios until actual speciation or cut measurements are available. Scenario fractions must sum to one, but that constraint alone does not identify them. No numerical As partition correlation was qualified in this investigation.

## 5. Kinetics and reaction heat remain a different estimation task

Elemental analysis constrains what products are possible; it does not determine how fast reactions occur or which products form. Fit a small process-specific kinetic network to conversion, gas/liquid/coke yield and product-quality data versus temperature and residence time. For hydrocracking include hydrogen conditions and catalyst identity/activity. A published dispersed-catalyst CSTR study used 410–450°C, 0.25–1 h⁻¹, 160 bar and a defined Mo catalyst; its parameters describe that experimental setting, not arbitrary residue reactors. [Primary hydrocracking study](https://www.sciencedirect.com/science/article/pii/S0920410520310524).

Heat-of-combustion estimation is possible once C/H/O/N/S/ash are defined. Channiwala–Parikh provides a broad elemental HHV correlation, developed on 225 points and checked on another 50, with reported mean absolute error of 1.45%. [Primary HHV study](https://doi.org/10.1016/S0016-2361(01)00131-4). At 45 MJ/kg, that percentage corresponds to roughly 0.65 MJ/kg as a scale illustration, not a confidence bound. Subtracting two estimated combustion energies can make a much smaller reaction heat poorly determined.

Prefer measured combustion/reaction heats when available. Any estimated formation-energy offsets must use compatible reference phases and water conventions, be carried separately from the current sensible/departure enthalpy, and avoid double-counting reaction heat. Keep H2 uptake and product elemental balances explicit; do not infer a universal hydrogen demand from sulfur alone.

## Proposed implementation order

1. Extend source evidence with actual cut API/density, UOPK, viscosity reference temperatures, TAN, basic nitrogen and analytical methods; keep the production property packages frozen.
2. Replace blanket reconciliation with a constrained estimator that records observation residuals, estimation domain, uncertainty provenance and unresolved variables.
3. Add optional C/O scenario profiles; retain the current incomplete profile as the strict mode. Quality scenarios must have separate fingerprints.
4. Qualify a residue-specific SARA model using all five crude residues and an independent comparison set. Whole-crude saturate estimates can constrain totals but cannot supply a unique per-cut SARA distribution.
5. Add process-specific reaction datasets and energy offsets only after matching the experimental basis. Validate thermal and hydrocracking models separately.

The most valuable missing evidence is direct residue H, independent C or O, a consistent SARA analysis, and reaction product-yield/heat data. Obtaining one independent C or O datum is more informative than applying several correlated density-based formulas to the same inputs.

## Reproduction and scope

`python research/crude-assays/screen_estimation_methods.py` generates [estimation-screening.json](../../crude-assays/estimation-screening.json) from the hash-verified producer PDFs already downloaded into `research/crude-assay-sources`. It uses pdfplumber only for extraction. Outputs contain source rows, equation results, applicability flags, known-cut hydrogen probes, and the PNA/asphaltene consistency check. It writes no production records.

The hydrogen probes compare predictions with **reported** assay values, which may themselves be estimates. They are screening comparisons, not independent experimental validation. Existing source/quality assumptions remain described in [CUT_ELEMENTAL_PROFILES.md](CUT_ELEMENTAL_PROFILES.md).

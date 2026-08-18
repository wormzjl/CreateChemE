# Gameplay thermodynamics and transport model

**Target:** Minecraft 1.21.1, NeoForge<br>
**Purpose:** canonical scientific model for a process-simulation game, not professional process design<br>
**Status:** proposed version-0 model, 2026-08-18

This document defines the reusable equations, property representations, validity rules, and scientific tests. The route-specific numbers and kinetics are isolated in [the crude-to-ethylene-glycol worked example](./CRUDE_TO_ETHYLENE_GLYCOL_EXAMPLE.md). The [atmospheric crude-distillation benchmark](./CRUDE_DISTILLATION_BENCHMARK.md) tests the petroleum/column scope against literature. Game ownership, persistence, scheduling, and networking belong in [the NeoForge architecture](./ADAPTIVE_SIMULATION_ARCHITECTURE.md).

## 1. Feasibility and version-0 scope

Quasi-realistic chemical-process simulation is feasible at game scale when the simulated objects are **well-mixed equipment and connected plant graphs**, not fluid voxels or individual molecules. Widely available data are adequate for common pure compounds. Petroleum cuts, strongly associating mixtures, and industrial catalyst kinetics are the main sources of uncertainty; they can be represented honestly with pseudocomponents, restricted validity ranges, and calibrated lumped reactions.

The initial scientific envelope should contain:

- one hydrocarbon-rich liquid, one polar/aqueous liquid, and one vapour phase;
- roughly 6–12 petroleum pseudocomponents per crude assay: six is an arcade splitter, while 10–12 is the recommended minimum for an atmospheric tower producing five overlapping cuts;
- roughly 20–40 real compounds across the mod, while each plant solves only the compounds actually present;
- Peng–Robinson vapour/liquid equilibrium for hydrocarbons and light gases;
- a separate ideal aqueous/associating liquid initially, with pair-specific Henry-law records for dilute neutral gases and an upgrade path to NRTL;
- optionally, one deliberately narrow, calibrated aqueous-MDEA/H2S loading package for gas sweetening;
- full mass and energy conservation even when kinetics or yields are game-calibrated;
- phase-specific caloric and viscosity correlations with explicit validity ranges;
- stirred-tank and continuous-flow balances with adaptive internal substeps.

Initially omit general electrolyte speciation, pH prediction, corrosion, salt precipitation, general solid-phase equilibrium and slurries, emulsions, rigorous petroleum molecular chemistry, catalyst ageing, detailed radical mechanisms, three-dimensional CFD, two-phase pipe correlations, and predictive safety engineering. Version 0 permits two deliberately narrow exceptions: an immobile coke/fouling inventory produced by a lumped reaction, and a data-fitted MDEA/H2S **total-loading surrogate**. Neither exception is a general equilibrium phase model; the amine surrogate must never claim ionic speciation or validity outside its fitted solvent formulation.

## 2. Data policy and availability

### 2.1 Confidence labels

Every property, binary parameter, and kinetic coefficient carries a confidence label:

| Label | Meaning | Intended use |
|---|---|---|
| `M` | Measured or critically reviewed | Pure-species anchors and validation data |
| `C` | Correlated, fitted, or estimated from measured anchors | Missing properties, mixtures, and petroleum cuts |
| `G` | Game-calibrated | Difficulty, yields, catalyst activity, and residence-time tuning |

Store the source identifier, source revision, original units, valid temperature/pressure/composition range, fitting error, and model revision with the label. Do not silently average conflicting datasets.

### 2.2 Availability by material family

| Family | Pure properties | Mixture properties | Kinetics | Version-0 representation |
|---|---|---|---|---|
| Refinery gas/LPG | Excellent | Good | Good for combustion; conversion varies | Real compounds |
| Naphtha/gasoline | Excellent for model compounds | Good for selected binaries | Detailed mechanisms exist, industrial yields vary | A few boiling/PNA lumps |
| Kerosene/diesel/gas oil | Good for model compounds | Sparse for arbitrary cuts | Feed- and catalyst-specific | TBP pseudocomponents |
| Residue/bitumen | No unique molecular identity | Poor | Sparse | One or two quality-bearing cuts |
| Light olefins and BTX | Excellent | Good | Generally usable but catalyst-specific | Real compounds or one early BTX lump |
| Oxygenates and glycols | Good pure anchors | Moderate; association matters | Some unusually accessible systems | Real compounds plus aqueous model |
| Dissolved H2, O2, and H2S | Good for water and selected solvents; uneven for arbitrary cuts | Good enough for selected hydrocarbon, glycol, and aqueous pairs | Mass transfer is equipment-specific | Henry records for dilute aqueous/polar pairs; calibrated PR/VLE for hydrocarbon liquids |
| Aqueous amine sweetening | Good equilibrium data for common fixed formulations | Reactive, ionic, and formulation-specific | Column rates are equipment-specific | One bounded empirical H2S-loading package; rigorous electrolytes later |
| Syngas products | Excellent | Good | Many public models | Real compounds plus lumped catalyst state |
| Polymers/lubes | Poor as discrete molecules | Application-specific | Distribution kinetics | Grade scalars or distribution moments |

Pure-component identity is usually not the bottleneck. New conversion processes require the most scientific work because selectivity and catalyst behaviour are strongly process-specific.

### 2.3 Source priority

For the study dataset, prefer:

1. [IAPWS](https://www.iapws.org/relguide/IAPWS-95.html) formulations for water and steam.
2. [NIST REFPROP](https://www.nist.gov/srd/refprop) reference equations and transport correlations for supported fluids.
3. [DIPPR 801](https://www.aiche.org/dippr/events-products/801-database/thermophysical-properties) evaluated constant and temperature-dependent properties.
4. NIST WebBook/JANAF, NASA thermochemistry, and peer-reviewed primary measurements.
5. [API Technical Data Book](https://www.api.org/products-and-services/standards/purchase), measured assays, and petroleum correlations.
6. Transparent group-contribution or corresponding-states estimates when measurements are absent.

Use high-quality tools offline to generate compact game data. The Minecraft server should not invoke REFPROP or a large property database at runtime.

For machine-readable build-time cross-checks, the official [CoolProp fluid collection](https://github.com/CoolProp/CoolProp/tree/master/dev/fluids) provides evaluated equations and anchors for a smaller set of common fluids. It is useful for validation and coefficient generation, not as a requirement to embed a high-accuracy EOS in the running server.

## 3. Canonical state and phase structure

Use K, Pa, mol, kg, m³, J, and s internally. A material holdup canonically stores:

- moles of every present component;
- one explicit state specification, such as `RIGID_UV` or `ISOBARIC_HP`;
- `energyBasis = INTERNAL_ENERGY` with total `U` in J, or `energyBasis = ENTHALPY` with total `H` in J, as required by that specification;
- the corresponding constraint value, such as volume in m³ or pressure in Pa;
- conserved quality scalars such as sulfur or catalyst poison;
- the property-dataset revision that interprets the state.

The basis and constraint are serialized fields, never inferred from the containing machine. Flowing streams carry molar enthalpy `h` in J/mol plus molar flow in mol/s; authored mass-specific correlations are converted during dataset compilation.

Temperature, pressure, entropy, density, viscosity, phase fractions, and render colour are derived results. Do not independently persist them as competing canonical state.

An equilibrium-stage calculation may retain only total component inventories and derive the phase split. A finite-rate gas/liquid contactor cannot do that: it must own explicit vapour and liquid sub-holdups, or an equivalent constrained phase inventory, so that

\[
n_i=n_i^V+n_i^{HC}+n_i^{AQ}.
\]

Persist either the constrained phase inventories or the totals plus an independent minimal partition state, never both as unconstrained authorities. The phases may share one `H` or `U` when the contactor assumes a common temperature. Dissolved-gas loading and amine loading are derived from those inventories; they are not additional conserved quantities.

The version-0 flash may return:

1. a vapour phase;
2. a hydrocarbon-rich liquid;
3. an aqueous/associating liquid.

Every derived property is phase-specific. In particular, never average liquid and vapour viscosity across an unresolved two-phase state.

An equipment model may additionally own a `SolidDepositInventory` for immobile coke. It stores component moles and uses a solid formation-enthalpy/Cp curve at the equipment temperature, so its mass and caloric contribution remain inside total `H` or `U`. It is excluded from EOS, vapour-liquid flash, viscosity, and fluid outlets. Cleaning is an explicit solid/item transfer that removes both deposited moles and their associated enthalpy; version 0 has no dissolution, slurry flow, or solid equilibrium.

## 4. Phase-equilibrium package

### 4.1 Petroleum pseudocomponents

A crude assay is a cumulative true-boiling-point curve plus density and optional quality curves. Integrate the assay over selected cut boundaries and assign each cut a normal-boiling midpoint, specific gravity, average molecular weight/formula, and quality scalars.

True compounds use integer elemental counts. A pseudocut or virtual kinetic lump may use an exact rational/normalized elemental vector; compile it to doubles only after validating every associated reaction in the authored rational basis. A virtual lump should expand back to its real stored cut identities rather than become unexplained inventory.

When only boiling point and SG exist, Riazi–Daubert-style estimates can initialize critical properties:

\[
T_c=19.06232\,T_b^{0.58848}SG^{0.3596}
\]

\[
P_c[\mathrm{bar}]=5.53027\times10^7\,T_b^{-2.3125}SG^{2.3201}
\]

with temperatures in kelvin. An Edmister estimate is

\[
\omega=\frac{3\log_{10}(P_c[\mathrm{psia}]/14.6959)}
{7(T_c/T_b-1)}-1.
\]

These are data-generation correlations, not truth. Clamp unreasonable heavy-cut results, retain their `C` label, and never infer viscosity uniquely from boiling point and SG. The [original Riazi–Daubert work](https://doi.org/10.1021/ie00064a023) and [Twu heavy-fraction treatment](https://doi.org/10.1016/0378-3812(95)02956-7) describe the applicable idea and limitations.

### 4.2 Hydrocarbon and vapour phase

Use Peng–Robinson for hydrocarbons and light gases. For component `i`:

\[
a_i=0.45724\frac{R^2T_{c,i}^2}{P_{c,i}}\alpha_i,
\qquad
b_i=0.07780\frac{RT_{c,i}}{P_{c,i}},
\]

\[
\alpha_i=\left[1+\kappa_i(1-\sqrt{T/T_{c,i}})\right]^2,
\]

\[
\kappa_i=0.37464+1.54226\omega_i-0.26992\omega_i^2.
\]

Classical mixing rules are sufficient initially:

\[
a=\sum_i\sum_jx_ix_j\sqrt{a_ia_j}(1-k_{ij}),
\qquad
b=\sum_ix_ib_i.
\]

Start a TP flash with Wilson K-values, solve Rachford–Rice, calculate fugacity coefficients from the cubic roots, and iterate until phase fugacities agree. Retain one vapour root and one hydrocarbon-liquid root. Zero hydrocarbon/hydrocarbon binary parameters are an acceptable `G` default; every non-zero `kij` is replaceable data.

### 4.3 Aqueous and associating liquids

Do not force water, glycols, and other strongly associating liquids through an uncalibrated hydrocarbon cubic liquid model. Version 0 should:

- keep hydrocarbon and aqueous liquids immiscible;
- use ideal activities in the aqueous liquid initially;
- distribute volatile water and light oxygenates with pure-component vapour-pressure correlations;
- keep heavy glycols effectively nonvolatile unless equipment explicitly models their evaporation.

A later activity model can replace ideal activities without changing conserved state. For a missing Antoine range, an intentionally limited estimate anchored at the normal boiling point is

\[
\ln\frac{P_i^{sat}}{101325}
=-\frac{\Delta H_{vap,b}}{R}
\left(\frac{1}{T}-\frac{1}{T_b}\right).
\]

Restrict it near the fitted operating range and below the critical point.

### 4.4 Dissolved neutral gases

Use one imported-data convention at runtime:

\[
f_i^V=x_i^L H_i^{px}(T,z_s)\Pi_i,
\qquad [H_i^{px}]={\rm Pa},
\]

where `f` is vapour fugacity, `x` is the dissolved neutral-solute mole fraction, and `z_s` is the **solute-free solvent composition**. For a Henry coefficient referenced at `P_ref`, the optional Poynting correction is

\[
\Pi_i=\exp\left[\frac{\bar v_i^\infty(P-P_{ref})}{RT}\right].
\]

Set `Pi = 1` unless a partial molar volume and a pressure range support it. Source data reported as `c/f`, Bunsen coefficients, or Ostwald coefficients are converted offline; the record still preserves the original convention and whether the experiment used fugacity or partial pressure. This avoids the many mutually reciprocal quantities called a “Henry constant” in the literature.

A compact temperature fit is

\[
\ln\frac{H_i^{px}(T)}{H_i^{px}(T_r)}
=B_i\left(\frac1T-\frac1{T_r}\right)
+C_i\ln\frac{T}{T_r}.
\]

Use `C = 0` over a modest band and retain it only when a wider measured range justifies the extra coefficient. For a binary mixed solvent, use endpoint data plus a Redlich–Kister correction:

\[
\ln\frac{H_{i,mix}^{px}}{H_*}
=\phi_1\ln\frac{H_{i,1}^{px}}{H_*}
+\phi_2\ln\frac{H_{i,2}^{px}}{H_*}
+\phi_1\phi_2\sum_{k=0}^{m}A_k(\phi_1-\phi_2)^k,
\]

where `phi` follows the source's solvent-composition basis and `H_*` is any common reference pressure. The form recovers both pure-solvent limits. Do not invent cross terms without mixture data.

For one dilute gas, the equilibrium target is

\[
x_i^*=\frac{f_i^V}{H_i^{px}\Pi_i},
\qquad
n_i^{L,*}=n_{solvent}\frac{x_i^*}{1-x_i^*}.
\]

For several gases, solve their common liquid denominator rather than applying the single-solute conversion independently. Clamp the phase transfer to available inventory, but report a range fault instead of using the clamp to hide a failed property model.

Each `GasSolubilityRecord` contains the solute, liquid-property-package ID, solvent basis, canonical and original conventions, coefficients, `T/P/composition/loading` bounds, optional `v̄∞`, source, fit error, confidence, and an explicit fallback policy. A strict Henry branch is admitted only inside the source range and its stated dilution limit. If the source gives none, `x_i^* <= 0.01` is a conservative game default, not a physical universal. There is no universal pressure cutoff: the predicted composition and the experiment's range decide.

Model selection is phase-specific:

- hydrocarbon liquid already handled by calibrated Peng–Robinson uses its gas–hydrocarbon `kij(T)` or a named empirical VLE override; do not add Henry uptake to the same phase;
- an aqueous/glycol liquid uses the Henry branch for neutral dilute H2, O2, or H2S;
- non-dilute or high-pressure pairs use a calibrated EOS, Krichevsky-type correction, or direct VLE table;
- aqueous H2S in the non-electrolyte package means molecular `H2S(aq)` only. It does not predict `HS-`, total sulfide, or pH.

The evaluated [IUPAC hydrogen volumes](https://iupac.github.io/SolubilityDataSeries/volumes/SDS-5-6.pdf), [oxygen volume](https://iupac.github.io/SolubilityDataSeries/volumes/SDS-7.pdf), and [hydrogen-sulfide volume](https://iupac.github.io/SolubilityDataSeries/volumes/SDS-32.pdf) provide a broad starting set. Useful direct anchors include:

| Pair family | Practical coverage and recommended use |
|---|---|
| H2 + hydrocarbons/common solvents | [Brunner](https://doi.org/10.1021/je00041a010) measured ten solvents at 298.15, 323.15, and 373.15 K, generally to 10 MPa. The [Sebastian–Lin–Chao correlation](https://doi.org/10.1002/aic.690270120) covers many hydrocarbons and five coal-liquid cuts over 310–700 K and to 30 MPa; use it offline for `C`-grade pseudocut fitting. |
| H2 + glycols | The evaluated hydrogen volumes contain direct MEG and higher-glycol measurements around 298–373 K at elevated pressure. Use direct pair fits; do not infer glycol behaviour from water. |
| O2 + hydrocarbons | [n-C6 through n-C16 at 298.15 K](https://doi.org/10.1021/je9502455) and [octane/toluene over 298–398 K to 10 MPa](https://doi.org/10.1006/jcht.2001.0837) are strong anchors for light cuts and aromatic corrections. |
| O2 + water/MEG | [Full water–MEG composition data at 298.15 K](https://doi.org/10.1021/je00015a033) show a non-monotone solubility minimum and supply a Redlich–Kister fit; endpoint interpolation is not acceptable. Pure-MEG temperature coverage away from 298 K remains a lower-confidence gap. |
| H2S + water/hydrocarbons | The evaluated H2S volume covers water across wide temperature and pressure ranges, C5–C16 and aromatic solvents, liquid paraffin, and an actual kerosene. Several hydrocarbon datasets reach non-dilute dissolved fractions at modest H2S pressure, so fit PR/VLE rather than extending strict Henry law. |
| H2S + glycols/common solvents | [Short–Sahgal–Hayduk](https://doi.org/10.1021/je00031a019) and [Shokouhi et al.](https://doi.org/10.1021/acs.jced.5b00680) provide EG and common-solvent data; the latter reports EG measurements over 303.15–353.15 K to about 1.5 MPa with Henry and finite-loading correlations. |

For the case-study water/MEG solvent, the measured O2 seed at 298.15 K is directly executable in its published Ostwald form:

\[
\ln L_{mix}=\phi_{EG}\ln L_{EG}+\phi_W\ln L_W
+\phi_{EG}\phi_W
\left[A+B\Delta\phi+C\Delta\phi^2+D\Delta\phi^3\right],
\]

\[
\Delta\phi=\phi_{EG}-\phi_W,
\quad
(A,B,C,D)=(-1.451859,-0.841821,0.219642,-0.434316).
\]

Use the source-defined solvent volume fractions. Convert the resulting `L = cL/cG` offline through `Hpx ≈ RT c_solvent/L` using the mixture molar density. The paper reports about 0.80% average deviation at that temperature; it does not justify extrapolating the same coefficients over the full hydration-reactor temperature range.

For petroleum pseudocuts, fit gas–cut `kij(T)` or a direct solubility curve offline from representative n-alkanes plus an aromatic correction based on the assay's PNA/aromatic fraction. Anchor H2 to measured petroleum-cut correlations and H2S to kerosene/dodecane-like data where applicable. Label the result `C`, include a broad uncertainty band, and never infer it from boiling point and specific gravity alone.

### 4.5 Reactive H2S absorption in aqueous amine

**Decision:** a general, rigorous amine model does not fit version 0, but a bounded empirical aqueous-MDEA/H2S package does fit the existing conserved-component and `H_mix^E` framework with one explicit rate-based phase-inventory extension.

Rigorous MDEA chemistry includes at least

\[
H_2S(aq)+MDEA\rightleftharpoons HS^-+MDEAH^+,
\]

plus water dissociation, electroneutrality, and ionic activity coefficients. CO2 adds bicarbonate/carbonate; MEA and DEA can add carbamate chemistry. The current ideal aqueous package cannot solve those quantities honestly. A later electrolyte package may use eNRTL, extended UNIQUAC, Pitzer, or a similarly validated activity model.

The game surrogate instead stores real `H2S`, `MDEA`, and `H2O` analytical component amounts and derives

\[
\alpha_{H_2S}=\frac{n_{H_2S,total}^{AQ}}{n_{MDEA}^{AQ}}.
\]

It does not expose the hidden ionic split. Fit a monotone inverse equilibrium surface on an explicitly declared gas-side basis, for example

\[
f_{H_2S}^{eq}=F(T,w_{MDEA},\alpha_{H_2S},\alpha_{CO_2})
\]

or `p_eq` when the source was fitted to partial pressure. Interpolate its logarithm without overshoot. `alpha_max` comes from the fitted data, not an assumed one-to-one capacity: physical dissolution can take total loading beyond the simple proton-transfer stoichiometry. The surface already represents physical dissolution **and** reactive enhancement, so never add a separate H2S–water Henry amount to it. At zero amine, switch explicitly to the nonreactive physical-solubility package.

The recommended version-0 analytic surface is the [Posey–Tapperson–Rochelle calculator model](https://doi.org/10.1016/0950-4214(96)00019-9). Define

\[
L_T=\frac{n_{H_2S}^{AQ}+n_{CO_2}^{AQ}}{n_{MDEA}^{AQ}},
\qquad
x_{Am}^{0}=\frac{n_{MDEA}^{AQ}}
{n_{MDEA}^{AQ}+n_{H_2O}^{AQ}},
\]

\[
x_i=\frac{n_i^{AQ}}
{n_{MDEA}^{AQ}+n_{H_2O}^{AQ}+n_{H_2S}^{AQ}+n_{CO_2}^{AQ}},
\]

then calculate

\[
P_i^*=K_i\,x_i\frac{L_T}{1-L_T},
\]

\[
\ln K_i=A_i+\frac{B_i}{T}
+C_iL_Tx_{Am}^{0}
+D_i\sqrt{L_Tx_{Am}^{0}}.
\]

`T` is in kelvin and the numerical values of `K` and `P*` are in kPa in the authored correlation; convert `P*` to Pa after evaluation. The MDEA parameters are:

| Acid gas | `A` | `B` (K) | `C` | `D` |
|---|---:|---:|---:|---:|
| H2S | 24.97 | -5554 | -16.8 | -1.8 |
| CO2 | 32.45 | -7440 | 33.0 | -18.5 |

The fit covers 35–50 mass-% MDEA, approximately 298–393 K, and `0.003 <= L_T <= 0.8`. It reported about 22% average absolute partial-pressure error for MDEA/H2S: suitable for this game, not for professional design or guaranteed ppm polishing. [Independent MDEA measurements](https://doi.org/10.1016/S0378-3812(00)00383-6) also document substantial low-pressure dataset discrepancies, so label the lean end coarse rather than promising a real sweet-gas specification. Define the physical limit `P_eq(0) = 0`; between zero and `L_min = 0.003`, use a separately labelled monotone low-loading bridge anchored to selected low-pressure data, or return `LEAN_LOADING_UNSUPPORTED`. For an H2S-only game bridge, `P_eq = P_min (L_T/L_min)^m` with `m` matched to the log-slope at `L_min` is cheap and continuous. Above `L_T = 0.8`, return a range fault rather than approaching the formula's singularity at one. The model was fitted to **partial pressure**, so compare it with `y_i P`. Do not substitute PR fugacity unless the coefficients are refitted on that basis. Its shared `L_T` term gives a cheap first representation of H2S/CO2 competition.

An assay's nonconserved “total sulfur” quality scalar cannot become absorbable H2S. An upstream hydrotreater or sour-gas source must first create explicit `H2S` from explicit sulfur-bearing components or conservative sulfur pseudocomponents in an element-balanced reaction. Only that real H2S inventory may transfer into the amine loop.

A sensible first data pack is one fixed 50 mass-% MDEA formulation. The [original single-gas measurements](https://doi.org/10.1021/i200019a001) cover 1.0, 2.0, and 4.28 kmol/m3 MDEA over roughly 38–121 °C and acid-gas partial pressures from 0.001 to 8600 kPa; the IUPAC H2S volume reproduces evaluated raw tables. [Jou, Otto, and Mather](https://doi.org/10.1002/cjce.5450750618) measured mixed H2S/CO2 loading in 50 mass-% aqueous MDEA at 40, 70, and 100 °C over acid-gas partial pressures from about 0.08 to 10 450 kPa. [Skylogianni et al.](https://doi.org/10.1016/j.fluid.2020.112498) add 50 and 70 mass-% MDEA measurements from 283 to 393 K and total pressures from 0.5 to 10 MPa, including methane. Restrict the shipped surface to the actual selected points and compositions, not merely the union of those headline ranges.

One absorber or stripper stage then performs this bounded scientific update:

1. evaluate the gas-side variable on the equilibrium fit's own basis—`y_i P` for the Posey model, or fugacity for a fugacity-fitted replacement—and derive the current analytical liquid loading from the phase inventories;
2. evaluate `Ψeq` (`p_eq` or `f_eq`) from the selected analytic or monotone loading surface and reject an out-of-domain state;
3. either solve the equilibrium phase transfer or integrate the single signed mass-transfer law in section 8, never both;
4. update vapour and liquid H2S equally and oppositely, retain total amine and water, and evaluate `H_abs^E` with no explicit reaction-heat source;
5. solve the stage's `H/P` or `U/V` state for its new temperature, then repeat with bounded internal substeps until the physical interval is covered;
6. obtain density and viscosity from the loaded-solution override when hydraulics are requested.

A counter-current column is only a short chain of these cells. Four to ten cells are adequate for gameplay; the same scientific package supports a cheap equilibrium-stage column or a more visibly rate-limited packed absorber.

For a first H2S-only absorber, either reject appreciable CO2 or fit a documented joint surface. Independent single-gas capacity curves are not a valid competition model. A later joint table can track both analytical loadings while still hiding ions. This surrogate can predict capture, lean/rich loading, temperature response, mass-transfer limitation, and thermal regeneration. It cannot predict pH, ionic speciation, corrosion, salt formation, degradation, arbitrary amine blends, or behaviour outside its fit domain.

### 4.6 Atmospheric crude-column fidelity target

The version-0 petroleum package is regressed first against the base/existing unit in Case Study 6.1 of [Chen's atmospheric-distillation study](https://pure.manchester.ac.uk/ws/portalfiles/portal/31440025/FULL_TEXT.PDF): 100,000 bbl/day of Tia Juana Light represented by 25 source pseudocomponents, with one physical main tower plus three side strippers represented as four thermally coupled equivalent simple columns totalling 59 equivalent stages, three pumparounds, and five overlapping products. This is a semi-rigorous shortcut literature case, not a full-MESH or plant-validation result. The detailed inputs, reduced assay, baseline reconstruction, source-versus-game comparison, target outputs, and limitations are recorded in [the benchmark report](./CRUDE_DISTILLATION_BENCHMARK.md).

The first executable milestone is the deliberately narrower, click-triggered [single-block crude-column calculator](./MILESTONE_1_CRUDE_DISTILLATION_POC.md). It solves one bare main equilibrium column with a total condenser, partial reboiler, one named 10–12-cut assay, one feed, fixed uniform 2.5 bar pressure, and direct liquid side draws. The player specifies feed rate/temperature/stage, main theoretical-stage count, reboiler duty, molar reflux ratio, and side-draw stages/rates; saturated reflux is the default and explicit reflux temperature is treated only as condensate subcooling. Top and bottom rates plus condenser duty are calculated. This PoC contains no side strippers, pumparounds, stripping steam, tray hydraulics, pressure drop, or physical tray dynamics, so it is not the reduced representation described below and must not be benchmarked as though those mechanisms were present.

The scientific game model uses 10–12 cut intervals regressed offline against a declared subset of that 25-cut literature case, then tested on held-out perturbations from a separately implemented 25-cut full-stage model oracle. Chen does not publish the complete HYSYS property export, so generate the oracle's missing properties with an assay-constrained characterisation method, record them as `C`-grade estimates, and constrain the result to bulk density and available mean-MW data. This is model-to-model validation, not independent experimental validation. Each cut requires an authored NBP interval and representative NBP, `MW`, specific gravity/density, `Tc`, `Pc`, acentric factor, caloric curve, and transport anchors. Optional PNA/aromaticity, sulfur, H:C, and quality attributes remain separate assay metadata and do not become conserved atoms unless backed by explicit pseudocomponents.

Represent the physical main tower plus its three side strippers as four connected equivalent section modules containing 10–16 virtual equilibrium cells in total, initially 2–4 cells per module. Cells within that total cover both lumped main-tower separation and the associated side-stripper behaviour; do not count separate stripper cells twice. This count is an unvalidated reduced-order design target, and internal cells are numerical state rather than world blocks. It is distinct from a four-node empirical recovery fallback. Add pumparounds as enthalpy-accounted liquid withdrawal/cooling/return recycles and the top as a vapour/hydrocarbon-liquid/aqueous flash. Use the source's fixed 2.5 bar pressure in the first regression. Stripping water shares the vapour phase so it lowers hydrocarbon partial pressures; condensed water may enter a separate aqueous phase. The version-0 immiscibility shortcut does not predict sour-water chemistry or water–oil mutual solubility.

Version 0 treats this column as a **quasi-steady algebraic separator**. A snapshot retains timestamped inlet/control regimes and the worker solves a steady internal equilibrium-cell state for each event-bounded segment, applying that segment's outlet fractions and heat/work ledgers over its own duration. Adjacent segments may be coalesced only under an authored state/control tolerance whose error is checked against the uncoalesced result; do not solve one average state across a nonlinear step change. It does not claim physical tray transients. Dynamic tray holdups, controller waves, and startup/shutdown propagation are a later model with different state and performance requirements.

A sharp-boundary twelve-cut baseline fitted to the five published product flows obtains 11.04 °C MAE and 37.2 °C maximum error over their fifteen `T5/T50/T95` values. This establishes only the resolution of that custom interval display: it does not reproduce Chen's discrete-pseudocomponent TBP method or predict the fitted flows and duties. Release accuracy must come from a staged PR/enthalpy solve validated on held-out flow, all three TBP quantiles, temperatures, and duties.

Use phase-specific, event-aligned caloric knots across the crude preheat and column ranges; 40–50 K is only an initial spacing that must pass the section 5.2 error limits, with explicit boundaries at phase changes. Do not use one constant `Cp` from 25 to 365 °C or across vaporisation. Treat heavy `>500 °C` pseudocut critical properties as extrapolated; impose an authored furnace-temperature limit and a visible coking/cracking warning rather than predicting those phenomena in version 0.

## 5. Caloric and density package

### 5.1 Enthalpy representation

A component declares exactly one thermochemical reference phase `q` at `Tr = 298.15 K`:

\[
h_i^q(T)=\Delta H_{f,i}^{\circ}(T_r,q)
+\int_{T_r}^{T}C_{p,i}^q(T')\,dT'.
\]

The other phase is established by one phase bridge at an anchor `Ta`. For a liquid-reference component,

\[
h_i^v(T)=h_i^l(T_a)+\Delta H_{vap,i}(T_a)
+\int_{T_a}^{T}C_{p,i}^v(T')\,dT'.
\]

For a gas-reference component, evaluate `hᵛ` directly from its gas formation enthalpy and construct the liquid branch in reverse:

\[
h_i^l(T)=h_i^v(T_a)-\Delta H_{vap,i}(T_a)
+\int_{T_a}^{T}C_{p,i}^l(T')\,dT'.
\]

This supports mixed database reference phases without inventing a metastable-liquid formation datum. It also guarantees `Cp = dh/dT` in each phase. Latent heat away from the anchor is the derived difference `ΔHvap(T)=hᵛ(T)-hˡ(T)`, not a second independently added correlation.

If an EOS supplies phase departures, add them once to the same ideal/reference branches and do not add a second latent term. A lightweight Watson estimate may generate or check the single bridge anchor:

\[
\Delta H_{vap}(T)=\Delta H_{vap}(T_b)
\left(\frac{1-T/T_c}{1-T_b/T_c}\right)^{0.38}.
\]

Do not combine independently fitted liquid Cp, vapour Cp, and Watson curves unless they satisfy the implied phase-enthalpy cycle within tolerance. For a petroleum-cut correlation `Cp = A + B(T-298.15)`, the integral is analytic:

\[
\Delta h=A(T_2-T_1)+\frac{B}{2}
\left[(T_2-298.15)^2-(T_1-298.15)^2\right].
\]

This costs only a few arithmetic operations, so one global constant Cp provides almost no performance advantage.

### 5.2 Constant-Cp acceptance rule

Constant Cp is acceptable only inside a declared, single-phase band. Use the interval average

\[
C_p^*=\frac{h(T_b)-h(T_a)}{T_b-T_a},
\]

which exactly reproduces the reference enthalpy at both endpoints. A gameplay band should satisfy:

- no phase boundary or near-critical region inside the band;
- maximum Cp deviation below roughly 5%;
- sensible-enthalpy error below roughly 2%;
- `h → T` inversion error below roughly 2 K.

Those are gameplay tolerances, not engineering standards. Split a failing band or use a polynomial/table. Recalculate mixture Cp from the current phase composition; never freeze one mixture Cp while reaction changes that composition.

Across a phase change,

\[
\Delta h=\int C_{p,l}\,dT+
\Delta\xi\,\Delta H_{vap}+
\int C_{p,v}\,dT.
\]

No constant Cp replaces the latent term. Constant-pressure equipment uses enthalpy; a closed rigid tank must conserve internal energy and use a consistent `u/Cv` formulation.

### 5.3 Runtime caloric forms

All components expose the conceptual operations `h(T)`, `Cp(T)`, `s(T,P)`, valid-range checking, and monotone `T(h)` inversion. Implementations may be:

| Material/range | Compact representation |
|---|---|
| Narrow single-phase band | Validated interval-average Cp |
| Moderate liquid range | Linear/polynomial Cp or enthalpy knots |
| Broad gas range | NIST/JANAF Shomate or NASA polynomial, possibly pretabulated |
| Water/steam | Small IAPWS-derived enthalpy table outside excluded critical region |
| Petroleum pseudocut | Linear Cp plus explicit phase enthalpy |

For hot gases, the Shomate form is

\[
C_p=A+Bt+Ct^2+Dt^3+E/t^2,
\qquad t=T/1000.
\]

Alternatively store enthalpy at 25–50 K knots and linearly interpolate. In either case the runtime work is trivial compared with a flash.

A cheap initial cut-density approximation is

\[
\rho(T)=\frac{999.016\,SG}
{1+7.0\times10^{-4}(T-288.7)}\;\mathrm{kg/m^3}.
\]

It is a clamped `G/C` model and is not valid near a critical point.

### 5.4 Entropy for compressors and turbines

Viscosity is not part of the primary compressor/turbine work equation and cannot replace its entropy model. For an ideal-gas component,

\[
s_i^{ig}(T,P)-s_i^{ig}(T_r,P_r)
=\int_{T_r}^{T}\frac{C_{p,i}(T')}{T'}\,dT'
-R\ln\frac{P}{P_r}.
\]

For a fixed-composition ideal-gas mixture, evaluate the mixture heat capacity and include ideal mixing consistently. Between two states of that same composition, the constant offsets and ideal-mixing term cancel. With constant `Cp`, the working expression is simply

\[
\Delta s=C_{p,m}\ln\frac{T_2}{T_1}
-R\ln\frac{P_2}{P_1}.
\]

A compressor or turbine first solves `s(T_{2s},P_2)=s(T_1,P_1)` for the ideal isentropic outlet and then applies its efficiency through enthalpy:

\[
h_2=h_1+\frac{h_{2s}-h_1}{\eta_c}
\quad\text{(compressor)},
\]

\[
h_2=h_1-\eta_t\left(h_1-h_{2s}\right)
\quad\text{(turbine)}.
\]

This ideal-gas entropy model is sufficient for the first gas-machine implementation. Caloric mode is atomic: `IDEAL_CALORIC` uses both `hᴿ = 0` and `sᴿ = 0`, while `PR_DEPARTURE` supplies both residual enthalpy and residual entropy from the same EOS implementation. Never enable only one. A simple liquid entropy model may use `ds=Cp\,dT/T` with pressure effects neglected initially. A coherent phase bridge satisfies `Δs_vap = Δh_vap/T_sat` at saturation.

Version 0 exposes `PS` only for a fixed-composition, single-vapour path that remains inside its declared gas range. If an isentropic trial crosses a dew point or otherwise changes phase, return an unsupported-state diagnostic rather than applying the ideal-gas expression through the phase change. A later full equilibrium-entropy implementation may lift that restriction.

Absolute standard entropies are unnecessary for a fixed-composition compressor or turbine because only differences are used. They become necessary if a later equilibrium reactor changes composition by minimizing Gibbs energy; a prescribed kinetic combustor can still advance composition from its rate law and close its temperature through the enthalpy balance.

## 6. Global viscosity model

### 6.1 Design rule and units

Use one phase-dispatched framework rather than one universal constant. Store dynamic viscosity in Pa·s. The flash identifies the phase and composition; the transport package then evaluates:

```text
phase state
    ├─ vapour: pure gas kernels → Wilke mixing
    └─ liquid: pure liquid kernels → logarithmic mixing → optional pressure correction
```

The same algebraic pure-component evaluator serves both branches. Coefficients are phase-specific and may be range-split.

### 6.2 Pure-component kernel

For phase `p` of component `i`, define

\[
\ell_i^{(p)}(T)=
\ln\left(\frac{\mu_i^{(p)}}{\mu_*}\right)
=a_{0,i}^{(p)}
+a_{1,i}^{(p)}\ln\left(\frac{T}{T_*}\right)
+a_{2,i}^{(p)}\frac{T_*}{T}
+a_{3,i}^{(p)}\left(\frac{T_*}{T}\right)^2,
\]

with `μ* = 1 Pa·s` and `T* = 300 K`, then

\[
\mu_i^{(p)}=\mu_*\exp(\ell_i^{(p)}).
\]

The normalized form keeps coefficients well scaled. It is equivalent in structure to the [NASA four-coefficient transport representation](https://www.grc.nasa.gov/www/winddocs/user/files.html). Each record stores `Tmin`, `Tmax`, optional `Pmax`, `a0…a3`, source, fit error, and confidence.

For gases, all four coefficients may be used, with at most two temperature ranges for ordinary gameplay. Sutherland data can be fitted into the same kernel. For liquids, normally set `a1 = 0`; the remaining inverse-temperature series follows the compact form used by the [NIST liquid-viscosity system](https://wtt-pro.nist.gov/wtt-pro/help/properties/viscosity_sat_liq.html).

If a liquid has only two anchors, also set `a3 = 0` and solve the two remaining coefficients. With three or more temperatures, fit `a0`, `a2`, and `a3`. The exponential guarantees positive viscosity but does not make extrapolation valid.

### 6.3 Gas-mixture rule

After evaluating pure-gas viscosities, use Wilke mixing:

\[
\mu_g=\sum_i
\frac{x_i\mu_i}
{\sum_jx_j\Phi_{ij}},
\]

\[
\Phi_{ij}=
\frac{\left[
1+\sqrt{\mu_i/\mu_j}
\left(M_j/M_i\right)^{1/4}
\right]^2}
{\sqrt{8(1+M_i/M_j)}}.
\]

Wilke's original model is documented in [DOI 10.1063/1.1747673](https://doi.org/10.1063/1.1747673), and NASA combines species Sutherland/fitted curves in this way in [Wind-US](https://www.grc.nasa.gov/www/winddocs/user/keywords/viscosity.html).

The cost is `O(n²)`. Twenty gas components require only 400 pair terms per evaluated phase, negligible on a one-to-twenty-second plant cadence. Evaluate viscosity once per hydraulic state, not per Minecraft block.

The fixed-efficiency thermodynamic core of a compressor or turbine does not need viscosity at all; it uses enthalpy, entropy, pressure, and efficiency. Viscosity enters connected pipe loss, heat-transfer correlations, and later Reynolds-dependent machine-map corrections. If a gas stays inside a validated narrow band with less than roughly 5% viscosity variation, represent that segment as a constant by setting `a1 = a2 = a3 = 0`. Across a large compressor temperature rise or an entire gas-turbine train, use the same inexpensive temperature kernel instead.

The base rule is a dilute/moderately compressed gas model. Version 0 has no generic dense-gas multiplier because a multicomponent phase has no unambiguous component owner for such coefficients. A later, explicitly named mixture-specific transport override may supply a small `T-P-composition` table with declared applicability bounds. Until then, near-critical CO2, water, or another dense working fluid returns an out-of-range fault.

### 6.4 Liquid-mixture rule

Use a sparse, temperature-dependent Grunberg–Nissan form:

\[
\ln\left(\frac{\mu_l}{\mu_*}\right)
=\sum_ix_i\ell_i^{(l)}
+\sum_{i<j}x_ix_jG_{ij}(T),
\]

\[
G_{ij}(T)=g_{0,ij}+g_{1,ij}\frac{T_*}{T}.
\]

The [original mixture law](https://doi.org/10.1038/164799b0) adds pair interactions to logarithmic mixing. Policies are:

- pairs inside a declared compatible hydrocarbon/pseudocut group may default to `Gij = 0` with a lower-confidence flag;
- water/glycol and other known associating pairs: fit `g0/g1` to mixture data;
- a missing pair declared `ASSOCIATING_REQUIRED` is a dataset/plant-context validation error whenever both components can exceed the transport trace threshold;
- only non-zero pairs are stored and evaluated.

This remains effectively `O(n + k)`, where `k` is the small number of active non-zero pairs. A higher-accuracy data pack may replace a difficult pair with a small `T × composition` correction table behind the same phase-viscosity interface.

For measured high-pressure liquid behaviour, optionally add a Barus-style term to the pure-component logarithm:

\[
\ell_i(T,P)=\ell_i(T,P_*)+\alpha_i(P-P_*).
\]

Set `α = 0` by default. Enable it only inside a range supported by pressure-dependent data.

A loaded aqueous-amine solvent is a named solution-level override, not an ideal Grunberg–Nissan mixture. Use a small `T × amine mass fraction × acid-gas loading` density/viscosity table, or a compact fit sampled from it. [Measurements for 46.78 mass-% aqueous MDEA](https://doi.org/10.1016/j.jct.2016.06.007) cover 313.15, 328.15, and 343.15 K, pressures to 1 MPa, and H2S loadings to about 1 mol/mol; they demonstrate that loading changes density and viscosity enough that an unloaded-solvent constant is not a sound hydraulic default. If the override is absent, absorption thermodynamics may still run, but viscosity-dependent pumping and column hydraulics return `TRANSPORT_UNSUPPORTED`.

### 6.5 Petroleum pseudocuts

Petroleum assays commonly report kinematic viscosity `ν`. Convert at the same temperature using

\[
\mu(T)=\rho(T)\nu(T).
\]

[ASTM D445](https://store.astm.org/standards/d445) defines the measurement and conversion. The minimum useful cut record has two viscosities bracketing the intended service range, density/API gravity, and provenance.

To preserve one runtime liquid kernel:

1. interpolate the two measured kinematic anchors offline with [ASTM D341](https://store.astm.org/standards/d341);
2. sample several temperatures inside the declared band;
3. convert each sample to dynamic viscosity using the cut density model;
4. fit `a0/a2/a3` of the liquid kernel;
5. ship only the compact coefficients, fit residual, and validity range.

If measured anchors are absent, use a Twu/corresponding-states estimate only as a `C` fallback. A [NIST petroleum-fraction study](https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=831618) found roughly 17.4% average absolute deviation from a corresponding-states method based only on API gravity and mean boiling point, with much larger class-specific errors.

### 6.6 Runtime limits and acceptance

The global formula is not permission to extrapolate. Each phase calculation must reject or visibly degrade when it crosses its coefficient range. Specific exclusions are:

- no viscosity interpolation through a phase boundary;
- no two-phase “effective viscosity” in version 0;
- no Newtonian treatment of waxy, emulsified, or yield-stress material below its configured transport temperature;
- no generic dense-gas correction in a critical enhancement region;
- no constant glycol or crude viscosity over a broad temperature range.

A material may use constant viscosity only when validation shows less than roughly 5% variation over a narrow operating band and the downstream hydraulic sensitivity remains acceptable. In most cases the fitted kernel is so cheap that this simplification is unnecessary.

## 7. Reaction enthalpy and energy convention

Use full chemical enthalpy globally:

\[
H=\sum_i n_i\left[
\Delta H_{f,i}^{\circ}(298.15)
+\Delta h_{sens,i}+\Delta h_{phase,i}
\right]+H^R_{EOS}+H^E_{mix}.
\]

`H^R` is the EOS departure enthalpy and `H^E` is an included excess/mixing contribution. Add each term once. The reaction enthalpy at a state is

\[
\Delta H_r(T,P,x)=\sum_i\nu_i\bar h_i(T,P,x),
\]

and on an ideal standard-state basis,

\[
\Delta H_r^\circ(T)=\Delta H_r^\circ(298.15)
+\int_{298.15}^{T}\sum_i\nu_iC_{p,i}^\circ(T')\,dT'.
\]

Changing composition while conserving `H`, or `U` in a rigid vessel, automatically generates the reaction exotherm/endotherm. Do **not** also add `-ΔHr rV`; that double-counts heat. A sensible-only convention with an explicit reaction source is mathematically valid, but the two conventions may never be mixed.

A dissolved Henry-law gas uses a solution standard state, not the pure condensed-fluid branch. At infinite dilution its absorption enthalpy relative to the ideal gas is constrained by the same equilibrium record:

\[
\Delta\bar h_{sol,i}^{\infty}
=\bar h_i^{L,\infty}-h_i^{V,ig}
=R\left(\frac{\partial\ln H_i^{px}}
{\partial(1/T)}\right)_{P,z_s}.
\]

Compile that partial-molar solution-enthalpy curve into `H_mix^E` for the dissolved inventory. Do not also apply a pure-liquid vaporization/condensation bridge to H2, O2, or H2S dissolved in water or glycol. A record without a usable temperature slope may solve isothermal phase partition, but an adiabatic absorber must return `CALORIC_UNSUPPORTED` rather than silently assigning zero heat of solution.

The amine loading surrogate uses the existing excess-enthalpy hook. For a single fixed MDEA formulation, define a state function

\[
H_{abs}^{E}(T,\alpha)=n_{MDEA}^{AQ}
\int_0^{\alpha}\Delta\bar h_{abs}(T,a)\,da,
\qquad H_{abs}^{E}(T,0)=0,
\]

and include it once inside `H_mix^E`. The analytical dissolved acid gas remains on its ideal-gas/reference branch; `H_abs^E` supplies the complete physical-dissolution plus acid–base-association enthalpy on the loading model's basis. [Direct H2S/MDEA calorimetry](https://doi.org/10.1016/0040-6031(90)80542-7), [acid-gas heat modelling for MEA/DEA/MDEA](https://doi.org/10.1021/ef0605706), and an [extended-UNIQUAC H2S/MDEA treatment](https://doi.org/10.1016/j.fluid.2015.01.024) provide data and cross-checks. For a joint H2S/CO2 fit, store a two-loading `H_abs^E` surface; independently integrated heats can become path-dependent and are not acceptable.

At fixed loading, the equilibrium surface supplies an isosteric consistency check:

\[
\Delta\bar h_{abs}≈R
\left(\frac{\partial\ln \Psi^{eq}}{\partial(1/T)}\right)_{\alpha,w},
\qquad \Psi\in\{p,f\}.
\]

For the unmodified Posey model, this gives `Δh̄abs,H2S = B_H2S R = -46.2 kJ/mol` and `Δh̄abs,CO2 = -61.9 kJ/mol`. Those constants are the internally matched version-0 choice. The MDEA calorimetry shows real temperature and concentration dependence; use it later only in a joint equilibrium/caloric refit, not as an unrelated second heat curve pasted onto the Posey surface.

With `H_abs^E` active, an adiabatic absorber heats and a regenerator needs duty through the ordinary `H/U` solve. Never also add `-Δh_abs ṅ` as equipment heat, and never combine the excess surface with a pseudo-loaded species carrying the same formation-energy change.

Distillation only redistributes existing components and must not transmute a generic “crude” identity into thermochemically unrelated cut identities. Lumped reactions require elemental balance and coherent formation enthalpies even when their rates are game-calibrated.

## 8. Dynamic equipment and hydraulics

For a well-mixed component balance,

\[
\frac{dn_i}{dt}=\sum_{in}\dot n_{i,in}
-\sum_{out}\dot n_{i,out}
+V\sum_r\nu_{ir}r_r.
\]

Finite-rate gas dissolution is an interphase transfer, not a source in the total component balance. For explicit vapour/liquid sub-holdups, use either a liquid-basis form

\[
\dot n_i^{V\rightarrow L}=k_LaV_L(c_i^*-c_i^L),
\]

or, for the empirical amine surface, a source-basis form

\[
\dot n_{H_2S}^{V\rightarrow L}
=K_\Psi aV_{contact}(\Psi_{H_2S}^{V}-\Psi_{H_2S}^{eq}),
\qquad \Psi\in\{p,f\}.
\]

Here `kLa` has units `s^-1`; `KΨa` has units `mol m^-3 s^-1 Pa^-1` when `p` or `f` is in Pa. Apply the signed transfer equally and oppositely to the two phase inventories. Use the same `p` or `f` basis as the equilibrium record and one fitted overall-coefficient convention: do not add separate film resistances or a chemical enhancement factor to a coefficient that already embeds them. For the first MDEA/H2S package, this equipment-level overall rate is preferable to inventing an Arrhenius proton-transfer rate; [50 mass-% MDEA absorption measurements](https://doi.org/10.1021/je970062d) model the H2S reaction as instantaneous and reversible, while [stirred-cell film modelling](https://doi.org/10.1016/0009-2509(91)80027-V) shows that gas-side resistance can matter. Clamp a substep to available gas, liquid capacity, and authored loading bounds, and use an implicit or exponential-relaxation update when the outer interval is long. The same equation reverses naturally in a stripper. An equilibrium-stage calculation instead solves the equilibrium partition directly and does **not** add finite-rate transfer afterward.

For an open constant-pressure enthalpy balance,

\[
\frac{dH}{dt}=\sum_{in}\dot n_{in}h_{in}
-\sum_{out}\dot n_{out}h_{out}
+\dot Q+\dot W_s.
\]

Take heat and shaft work positive into the fluid. A sealed rigid tank uses

\[
\frac{dU}{dt}=\dot Q+\dot W_s,
\qquad U=H-PV,
\]

while an open rigid tank also transports inlet/outlet stream enthalpy. PFRs integrate the same balances in residence-time or axial-volume coordinates. Heat exchangers may begin with `Q = UA ΔTlm` plus inventory and approach-temperature limits.

For a single-phase line,

\[
Re=\frac{\rho vD}{\mu},
\]

\[
\Delta P_{line}=\left(f_D\frac{L}{D}+\sum K\right)
\frac{\rho v^2}{2}+\rho g\Delta z.
\]

Use `fD = 64/Re` when laminar and a Colebrook/Haaland/Swamee–Jain approximation when turbulent. A liquid pump approximately obeys

\[
\Delta h_{s,mass}\simeq\frac{\Delta P}{\rho},
\qquad
\Delta \bar h_{actual}=\bar M
\frac{\Delta P}{\rho\eta_p},
\qquad
\dot W_s\simeq\frac{\Delta P\,Q}{\eta_p}.
\]

Here `Δh_mass` is J/kg, `Δh̄` is the molar rise in J/mol used by the stream API, and `M̄` is mixture kg/mol. Solve the outlet state from that molar enthalpy rise. An adiabatic pressure-loss element conserves stagnation enthalpy; do not invent an additional friction-heat source. Version 0 should reject or derate a pump with appreciable inlet vapour and model compressors separately through EOS enthalpy/entropy and efficiency.

## 9. Generating missing data

Use the following ladder:

1. evaluated pure-compound records;
2. measured petroleum assays and established correlations;
3. group contribution for critical/ideal-gas properties and activity estimates;
4. reproducible quantum/statistical thermochemistry offline, fitted to compact polynomials;
5. detailed kinetic mechanism simulation offline, reduced to a conservative lumped network with tools such as [Reaction Mechanism Generator](https://reactionmechanismgenerator.github.io/), [LLNL mechanisms](https://combustion.llnl.gov/mechanisms), or [CRECK Modeling](https://www.creckmodeling.polimi.it/kinetics-mechanisms/);
6. transparent game calibration when industrial catalyst data remain unavailable.

For a missing gas–solvent pair, first fit an adjacent measured homolog. Use PR/PSRK interaction fitting for hydrocarbons, and an associating method such as PC-SAFT/SAFT-γ or COSMO-RS offline for glycols and other polar solvents. Anchor every generated curve to at least one experimental point or pure-limit check, sample it over a bounded grid, then ship only the compact Henry/EOS/table record with a `C` label and model uncertainty. A reactive amine pair must be generated offline from a validated electrolyte model or fitted loading data; ordinary physical Henry prediction cannot create its reactive capacity.

Fit rates or outlet distributions over a domain, not one operating point. Keep an independent validation grid, preserve atoms exactly, constrain branch fractions, enforce reactant/catalyst limits, and never extrapolate an Arrhenius expression without a temperature guard.

## 10. Scientific acceptance tests

Before releasing a data pack:

- every reaction balances every element exactly;
- isolated operations close mass to relative error below `1e-9` in double precision;
- converged energy residual is below `1e-7` relative to the chosen scale;
- mole numbers, phase fractions, temperatures, heat capacities, densities, and viscosities remain finite and physically positive;
- flashes reproduce selected normal boiling points within the declared model tolerance;
- `T → h → T` round trips remain within 0.1 K for non-constant models;
- finite-difference `dh/dT` agrees with the active phase Cp, and every phase-bridge/Hess cycle closes;
- ideal-caloric and PR-departure modes enable residual enthalpy and entropy as matched pairs;
- version-0 `PS` rejects a dew-point/phase-crossing path deterministically;
- each constant-Cp band passes its Cp, enthalpy, and inversion limits;
- every viscosity curve reproduces its fit grid within its declared error and remains positive over its range;
- gas and liquid mixture rules recover every pure limit exactly;
- `μ = ρν` conversions reproduce petroleum anchors;
- viscosity is never evaluated for an unresolved two-phase phase-average;
- standard reaction heats recomputed from formation enthalpies match their curated reference values;
- a full-enthalpy adiabatic reactor matches a separate sensible-only/explicit-heat reference without double counting;
- vaporize/condense and balanced Hess cycles return to the initial energy;
- pump enthalpy rise equals admitted shaft work;
- pump mass-specific and molar formulas agree through mixture molecular weight;
- every virtual kinetic lump expands to stored components without changing elements or total reference enthalpy;
- an immobile coke deposit contributes mass and energy but never enters a fluid flash, viscosity rule, or fluid outlet;
- every Henry-data conversion round-trips to its original convention and all mixed-solvent fits recover their pure limits;
- each Henry temperature slope reproduces its compiled infinite-dilution solution enthalpy, and dissolved gases never receive a pure-condensed-fluid phase bridge;
- a gas is never dissolved twice by Henry law and an EOS/VLE rule in the same liquid phase;
- finite-rate gas transfer conserves total component moles exactly and cannot overshoot either phase inventory;
- an MDEA loading fit reproduces its forward/inverse source grid monotonically and rejects states outside its `T/P/composition/loading` domain;
- the Posey seed reproduces its published parameter grid, uses partial pressure rather than fugacity, reaches `P_eq(0) = 0` through its declared lean bridge, and rejects loading above `0.8`;
- an absorber/stripper cycle conserves H, S, N, O, and C, and returns the starting material and energy in the reversible test limit;
- isothermal absorption duty equals the `H_abs^E` state difference, while an adiabatic case has no explicit absorption-heat source;
- zero-amine H2S switches to the physical solvent model rather than dividing by zero or retaining reactive enhancement;
- the 25-cut atmospheric-column reference closes before reduction, and the reduced 10–12-cut model meets authored product-flow, `T5/T50/T95`, temperature, and major-duty tolerances on held-out operating perturbations rather than merely refitting one product slate;
- the Milestone-1 bare-column default has zero remaining degrees of freedom, calculates rather than prescribes top/bottom flow and condenser duty, and closes every component across top, direct side, and bottom streams;
- feasible Milestone-1 perturbations of feed temperature/stage, theoretical-stage count, reflux, reboiler duty, and side-draw stage/rate are deterministic and continuous within the declared numerical tolerance, while invalid or infeasible player specifications return typed failures rather than unconverged product numbers;
- Milestone-1 direct liquid side draws are never labelled or scored as stripped products;
- every crude-column solve records convergence and mass/energy residuals; an iteration-capped or deadline-capped state is a diagnostic failure, not a valid fast result;
- every crude benchmark report contains a side-by-side source-model versus in-game-model comparison and labels each fixture datum as input, calibration target, or held-out prediction;
- scientific unit results at 1/2/5/10/20-second outer updates converge toward a fine-step numerical reference;
- every datum records source, confidence, valid range, units, fit error, and dataset revision.

## 11. Main risks and model boundary

| Risk | Consequence | Practical mitigation |
|---|---|---|
| No unique crude composition | False precision and excessive species count | Versioned assays with 6–12 cuts; use 10–12 for an atmospheric tower |
| Ambiguous player column specification | An under- or over-specified solver that returns arbitrary products | Freeze the degree-of-freedom contract, stage numbering, phase/boundary conventions, ranges, and units before exposing a GUI field |
| Direct side draw mistaken for a side stripper | Unrealistically light side products and a false comparison with the literature case | Label the PoC topology explicitly; add steam/duty, pressure, stages, and vapour return before claiming stripper behaviour |
| Reduced crude column fitted only to product yields | Correct amounts but wrong overlaps and duties | Run held-out model-to-model tests against a separately implemented 25-cut full-stage oracle, then use external assays for physical plausibility |
| Column recycle or cold-start nonconvergence | Stale or numerically invalid plant state | Warm starts, bounded tear iteration, residual checks, and visible degraded state |
| Sparse catalyst/process kinetics | Unrealistic conversion/selectivity | Published scale plus labelled game calibration |
| Polar/nonpolar phase behaviour | Poor aqueous results from PR alone | Separate aqueous phase; NRTL upgrade later |
| Henry convention or validity mismatch | Orders-of-magnitude solubility error | Canonical `Hpx`, original-basis metadata, range guards, and source-grid tests |
| H2S physical dissolution confused with aqueous speciation | False total sulfide and pH | Neutral-H2S mode only, or the explicitly named amine-loading package |
| Empirical amine surface extrapolated or combined with separate uptake | False selectivity, capacity, and heat | Fixed formulation, joint H2S/CO2 data where needed, hard domain faults, one uptake model |
| Stiff kinetics over long outer updates | Negative inventories or missed runaway | Positivity-preserving internal substeps |
| Heavy-cut critical-property extrapolation | Bad extreme-state flashes | Validity guards and visible model faults |
| Pseudocut viscosity from boiling point/SG alone | Large hydraulic error | Require two measured anchors where possible |
| Waxy/residual material treated as Newtonian | Unrealistic cold pumping | Minimum transport temperature; rheology later |
| Thermochemical reference mismatch | Energy does not close | One curated formation-enthalpy/phase basis |
| Reaction heat added twice | False runaway/cooling | One full-enthalpy convention and Hess tests |
| Async cadence changes numerical history | Hardware-dependent trajectories | Internal error control and fixed-cadence test mode |

The broader version-0 scientific package should stop at this boundary. After the narrower [Milestone-1 crude-column calculator](./MILESTONE_1_CRUDE_DISTILLATION_POC.md), validate that composition, phase change, dilute gas dissolution, one fixed-formulation MDEA/H2S absorber–stripper, heat duty, viscosity-dependent transport, kinetics, and recycle are understandable and fun before adding rigorous electrolyte speciation/VLLE, detailed turbomachinery aerodynamics, catalyst deactivation, wax/asphaltene rheology, or a larger crude library.

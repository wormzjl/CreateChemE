# Worked implementation example: crude oil to ethylene glycol

**Target:** Minecraft 1.21.1, NeoForge<br>
**Purpose:** executable reference dataset and worked flowsheet, not professional process design<br>
**Status:** proposed version-0 example, 2026-08-18

This file parameterizes the reusable equations in [the scientific model](./SCIENTIFIC_MODEL.md). It does not redefine the EOS, caloric, viscosity, reaction-energy, or hydraulic packages. The NeoForge-side representation is described in [the implementation architecture](./ADAPTIVE_SIMULATION_ARCHITECTURE.md).

All `M/C/G` confidence labels have the meanings defined in the scientific model.

## 1. Route and calculation basis

The example is a deliberately compact teaching flowsheet:

```text
1,000 kg illustrative crude
        │ atmospheric/vacuum fractionation
        ├── 800 kg middle/heavy products
        └── 200 kg naphtha + 100 kg dilution steam
                    │ steam cracker
                    ├── coproducts and 60 kg unconverted naphtha
                    └── 44.046 kg ethylene
                              │ O2, Ag catalyst, recycle
                              ├── CO2 + water + 0.881 kg unconverted ethylene
                              └── 56.938 kg ethylene oxide
                                        │ 20 mol water / mol EO
                                        ├── recycle water + traces EO/DEG/TEG
                                        └── 72.030 kg monoethylene glycol
```

This is not a claim about the yield of a particular refinery. It is a complete, mass-balanced game scenario that joins distillation, cracking, catalytic oxidation, hydration, recycle, heat exchange, and separation.

## 2. Illustrative crude assay

The feed is a fictional medium-sour crude. `Formula` is an elemental bookkeeping formula for the average molecule, not a molecular identification. `Tb` is the assigned normal-boiling midpoint; the open-ended residue value is a modelling anchor. Cut heat capacity uses

\[
C_{p,l}(T)=A+B(T-298.15)
\]

in kJ/(kg·K). The table reports `1000 B`, so a displayed value of 4.0 means `B = 0.0040 kJ/(kg·K²)`. This linear form is still extremely cheap but is more defensible over a broad refinery temperature range than one 298 K constant.

| Cut ID | TBP range (°C) | kg | wt% | Average formula | MW (g/mol) | SG at 15.6°C | Tb (K) | Tc (K) | Pc (MPa) | ω | Cp `A` (kJ/kg/K) | `1000 B` | ΔHvap at Tb (kJ/kg) | S (wt%) | Class |
|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `LIGHT_ENDS` | <30 | 20 | 2 | C4H10 | 58.12 | 0.60 | 273 | 425.1 | 3.800 | 0.200 | 2.30 | 4.8 | 360 | 0.00 | G/C |
| `LIGHT_NAPHTHA` | 30–90 | 70.036 | 7.0036 | C6H14 | 86.18 | 0.68 | 333 | 506.2 | 3.319 | 0.248 | 2.25 | 4.4 | 335 | 0.05 | G/C |
| `HEAVY_NAPHTHA` | 90–180 | 129.964 | 12.9964 | C8H18 | 114.23 | 0.75 | 408 | 591.0 | 2.604 | 0.347 | 2.20 | 4.0 | 310 | 0.10 | G/C |
| `KEROSENE` | 180–240 | 100 | 10 | C11H22 | 154.30 | 0.80 | 483 | 668.0 | 2.048 | 0.461 | 2.15 | 3.6 | 285 | 0.30 | G/C |
| `LIGHT_DIESEL` | 240–300 | 110 | 11 | C14H26 | 194.36 | 0.84 | 543 | 728.3 | 1.749 | 0.554 | 2.10 | 3.3 | 265 | 0.70 | G/C |
| `HEAVY_DIESEL` | 300–360 | 110 | 11 | C17H30 | 234.42 | 0.87 | 603 | 784.5 | 1.489 | 0.662 | 2.05 | 3.1 | 250 | 1.00 | G/C |
| `AGO` | 360–420 | 120 | 12 | C21H36 | 288.52 | 0.90 | 663 | 839.7 | 1.294 | 0.779 | 2.00 | 2.9 | 235 | 1.50 | G/C |
| `LVGO` | 420–500 | 120 | 12 | C27H44 | 368.65 | 0.93 | 733 | 901.4 | 1.107 | 0.937 | 1.95 | 2.7 | 220 | 2.20 | G/C |
| `HVGO` | 500–565 | 80 | 8 | C34H52 | 460.83 | 0.96 | 806 | 964.1 | 0.957 | 1.130 | 1.90 | 2.5 | 205 | 3.00 | G/C |
| `RESIDUE` | 565+ | 140 | 14 | C45H64 | 605.0 | 0.99 | 893 | 1035.4 | 0.811 | 1.426 | 1.85 | 2.3 | 190 | 4.00 | G/C |

Total sulfur is approximately 1.48 wt%. In version 0 it is strictly a nonreactive grading scalar: fractionation conserves it, but “removing sulfur” would not remove mass or atoms. A later desulfurization unit must first replace that scalar with explicit sulfur-bearing pseudocomponents and their enthalpy data.

## 3. Route-specific property choices

This example uses the canonical phase, caloric, viscosity, and energy packages in [SCIENTIFIC_MODEL.md](./SCIENTIFIC_MODEL.md). The values below explain how the generic models are parameterized for this route.

### 3.1 Caloric choices

The cut heat-capacity equation and the pure-component caloric models are intentionally lightweight. Representative behaviour over the actual route ranges is:

| Property and band | Approximate Cp change | Example choice |
|---|---:|---|
| Liquid water, 358–388 K hydration | <1% | Constant band average, about 4.20 kJ/(kg·K) |
| Liquid MEG, 358–388 K hydration | about 5–6% | Linear Cp preferred; band average only at relaxed tolerance |
| Ethylene gas, 483–570 K EO reactor | about 12% | Shomate or linear band |
| EO gas, 483–570 K | about 14% | Shomate or linear band |
| Ethylene gas, 298–1123 K cracker heating | more than 100% | Shomate/NASA polynomial |
| Cracker gas already within 1050–1150 K | typically a few percent for small species | Validated local band average may be used |
| Petroleum liquid from ambient toward boiling | commonly tens of percent | Linear cut correlation plus explicit latent heat |

The ethylene and EO trends follow the NIST [ethylene](https://webbook.nist.gov/cgi/cbook.cgi?ID=C74851&Mask=1&Units=SI) and [ethylene-oxide](https://webbook.nist.gov/cgi/cbook.cgi?ID=C75218&Mask=1&Units=SI) records. Liquid MEG can be checked against the [NOAA/CHRIS property sheet](https://cameochemicals.noaa.gov/chris/EGL.pdf), and water against [IAPWS-95](https://www.iapws.org/relguide/IAPWS-95.html).

### 3.2 Viscosity coverage for this route

The global runtime equations are defined only once in the scientific model. This example supplies or fits their coefficients from the following sources:

| Material | Availability | Example data route | Limitation |
|---|---|---|---|
| Water and steam | Excellent | IAPWS viscosity data fitted over used bands | Never fit through saturation or the critical region |
| H2, N2, O2, CH4, C2H6, C2H4 and CO2 | Excellent to good in published ranges | NIST/REFPROP gas data fitted to one or two gas-kernel bands | Several C1–C3 correlations end below cracker temperature |
| Propylene and heavier cracker gas | Adequate | Predictive dilute-gas data fitted over the cracker band | Mark correlated rather than measured |
| Ethylene oxide gas | Adequate but weak | REFPROP/DIPPR estimate fitted over the EO-reactor band | Hot gas viscosity is chiefly model-derived |
| Compressed-liquid ethylene oxide | Sparse but required for mixed-EO transport | Fit a REFPROP/DIPPR liquid curve only over the pressurised hydration band | Do not substitute the gas curve |
| Pure MEG | Good | NIST reference correlation | Vapour data are much weaker |
| Pure DEG and TEG | Good measurements | NIST ThermoML/DIPPR fit | Local coefficient fit required |
| Water + MEG/DEG/TEG | Good over 358–388 K | Fit sparse Grunberg–Nissan interactions | Ideal logarithmic mixing alone is inadequate |
| EO + water/glycols | Sparse | Required interaction fit or explicit unsupported-transport state | Initial EO is about 4.8 mol%, so it is not a trace |
| Crude pseudocuts | Assay-dependent | Two kinematic anchors, ASTM D341 offline, then global liquid-kernel fit | Boiling point and SG do not determine viscosity |
| Resid/waxy crude | Poor without assay rheology | Minimum transport temperature | Newtonian model deliberately ends below that limit |

Useful sources include the [IAPWS water formulation](https://www.iapws.org/relguide/viscosity.html), [NIST REFPROP](https://www.nist.gov/srd/refprop), [DIPPR 801](https://www.aiche.org/dippr/events-products/801-database/thermophysical-properties), and the [NIST ThermoML glycol-mixture series](https://trc.nist.gov/ThermoML/10.1021/je025610o.html). An [industrial glycol handbook](https://www.indoramaventures.com/storage/downloads/worldwide/indorama-ventures-oxides-australia/australian-product-handbook.pdf) gives rough 20°C checks of 21, 38, and 49 mPa·s for MEG, DEG, and TEG.

This fictional crude assay must gain two viscosity anchors per transported cut before the hydraulic example is considered complete. The hydration mixture also requires the compressed-liquid EO curve and EO–water/glycol pair policy. Until those exist, the reactor kinetics may run, but viscosity-dependent transport of the reacting mixture must return `TRANSPORT_UNSUPPORTED`; it may not silently assume ideal mixing. A post-reactor trace may be ignored only below a declared transport-only threshold such as mole fraction `1e-3`, without changing conserved composition.

## 4. Pure-species starter anchors

The following tables are **starter anchors**, not a runnable property pack or a database replacement. They seed EOS and reaction-enthalpy records, but an executable pack still needs the phase-specific Cp/entropy models, vapour-pressure or phase-bridge records, viscosity coefficients, and required binary interactions listed elsewhere in this example.

### 4.1 Cracker and oxidation species

| ID | Formula | MW (g/mol) | Tb (K) | Tc (K) | Pc (MPa) | ω | ΔHf° gas at 298 K (kJ/mol) | Cp gas at 298 K (J/mol/K) | Class |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| `hydrogen` | H2 | 2.01588 | 20.37 | 33.15 | 1.296 | -0.219 | 0 | 28.84 | M |
| `methane` | CH4 | 16.0425 | 111.66 | 190.56 | 4.599 | 0.011 | -74.87 | 35.69 | M |
| `ethane` | C2H6 | 30.069 | 184.55 | 305.32 | 4.872 | 0.099 | -84.68 | 52.49 | M |
| `ethylene` | C2H4 | 28.0532 | 169.42 | 282.35 | 5.042 | 0.087 | 52.47 | 42.9 | M |
| `propylene` | C3H6 | 42.0797 | 225.46 | 364.21 | 4.555 | 0.142 | 20.41 | 64 | M/Cp rounded |
| `butadiene` | C4H6 | 54.0916 | 268.74 | 425.1 | 4.32 | 0.195 | 111.9 | 80 | M/C |
| `benzene_btx_lump` | C6H6 | 78.1118 | 353.24 | 562.02 | 4.907 | 0.211 | 82.93 | 82.4 | M identity, G lump |
| `c5_plus_lump` | C5H10 | 70.134 | 303 | 464.8 | 3.56 | 0.233 | -21.5 | 105 | C/G |
| `oxygen` | O2 | 31.9988 | 90.19 | 154.58 | 5.043 | 0.022 | 0 | 29.38 | M |
| `nitrogen` | N2 | 28.0134 | 77.36 | 126.19 | 3.396 | 0.037 | 0 | 29.12 | M |
| `carbon_dioxide` | CO2 | 44.0095 | 194.67* | 304.13 | 7.377 | 0.224 | -393.51 | 37.13 | M |
| `carbon_coke` | C(s) | 12.011 | — | — | — | — | 0 | 8.5 | M graphite reference/G coke phase |

`*` CO2 sublimes at one atmosphere; the number is an anchor, not a normal liquid boiling point.

`carbon_coke` is declared only as `IMMOBILE_DEPOSIT`. Its formation enthalpy and solid Cp participate in the equipment energy balance, but the missing `Tb/Tc/Pc/ω` fields are intentional: it is never passed to Peng–Robinson, viscosity mixing, or a fluid capability.

### 4.2 Water, ethylene oxide, and glycols

| ID | Formula | MW (g/mol) | Tb (K) | Tc (K) | Pc (MPa) | ω | ΔHf° liquid at 298 K (kJ/mol) | Cp liquid near 298 K (J/mol/K) | ρ at 298 K (kg/m³) | ΔHvap near Tb (kJ/mol) | Class |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `water` | H2O | 18.01528 | 373.12 | 647.096 | 22.064 | 0.344 | -285.83 | 75.3 | 997 | 40.66 | M |
| `ethylene_oxide` | C2H4O | 44.0526 | 283.7 | 468.9 | 7.305 | 0.210 | -78.15† | 86.9 | 863 | 25.53 | M/C† |
| `meg` | C2H6O2 | 62.0678 | 470.5 | ~720 | ~8.2 | ~0.50 | -455.6 | 149.3 | 1113 | ~50.5 | M anchors/C criticals |
| `deg` | C4H10O3 | 106.1204 | 518.6 | estimated | estimated | estimated | -628.5 | 243.9 | 1118 | ~59 | M anchors/C |
| `teg` | C6H14O4 | 150.173 | 558.2 | estimated | estimated | estimated | -804.2 | 327.6 | 1125 | ~67 | M anchors/C |

`†` NIST lists a preferred gas formation enthalpy of -52.64 kJ/mol and a measured vaporization enthalpy of 25.51 kJ/mol, alongside a substantially older liquid formation value that is not mutually consistent. This model derives `-52.64 - 25.51 = -78.15 kJ/mol` so that phase changes and reaction heats close. Store the derivation and source IDs rather than presenting it as a direct measurement.

The [NIST MEG record](https://webbook.nist.gov/cgi/cbook.cgi?Name=ethylene+glycol&cTC=on) reports several formation-enthalpy measurements and Cp values; the selected -455.6 and 149.3 values are explicit entries. Equivalent records exist for [DEG](https://webbook.nist.gov/cgi/cbook.cgi?ID=C111466&Mask=2&Units=SI), [TEG](https://webbook.nist.gov/cgi/cbook.cgi?ID=C112276&Mask=2&Units=SI), and [water](https://webbook.nist.gov/cgi/cbook.cgi?ID=C7732185&Mask=F&Units=SI). For the study dataset, select the most internally consistent evaluated series and retain the citation, reference state, temperature range, and revision.

## 5. Unit 1: crude fractionation

For the reference balance, an idealized TBP fractionator sends both naphtha cuts to the cracker. This route balance is intentionally simpler than the literature-backed [atmospheric crude-column benchmark](./CRUDE_DISTILLATION_BENCHMARK.md):

- light naphtha: 70.036 kg;
- heavy naphtha: 129.964 kg;
- naphtha total: 200 kg;
- all other products: 800 kg.

This first balance uses sharp cut recovery so that the downstream example is reproducible. It is a route fixture, not the [Milestone-1 crude-column calculator](./MILESTONE_1_CRUDE_DISTILLATION_POC.md) and not the final continuously operating column model. The route's fixed 200 kg naphtha recovery therefore must not be used as validation evidence for the milestone solver.

For the cracker only, the authored 70.036/129.964 kg split is exactly a 5:7 molar blend at the displayed molecular weights; 70/130 kg is only its convenient flowsheet rounding. Compile those twelve representative molecules into one conservative kinetic basis:

\[
5\,\mathrm{C_6H_{14}}+7\,\mathrm{C_8H_{18}}
\longrightarrow12\,\mathrm{C_{43/6}H_{49/3}}.
\]

| Virtual basis | Formula | MW (g/mol) | ΔHf° gas diagnostic (kJ/mol) | Stored/EOS species? |
|---|---|---:|---:|---|
| `F` | C(43/6)H(49/3) | 102.5425 | -191.2 | No; reaction-compiler macro only |

The diagnostic formation enthalpy is the same 5:7 molar blend of the selected NIST [n-hexane](https://webbook.nist.gov/cgi/cbook.cgi?ID=C110543&Mask=1&Units=SI) and [n-octane](https://webbook.nist.gov/cgi/cbook.cgi?ID=C111659&Mask=1&Units=SI) references. `F` has no `Tb/Tc/Pc/ω`, Cp curve, phase, or viscosity record; every actual thermodynamic state uses the two assay-cut records.

`F` is a virtual reaction basis, not a persisted material identity or a zero-cost conversion operation. During reaction compilation, every reactant `F` expands to `(5/12) LIGHT_NAPHTHA + (7/12) HEAVY_NAPHTHA`; the actual cut inventories and their full enthalpies are consumed directly. The rational C/H vector and blended formation enthalpy are retained for balance and diagnostic reaction-enthalpy checks. This avoids silently transmuting both feeds into thermochemically unrelated `C7H16`.

## 6. Unit 2: lumped steam cracking

### 6.1 Operating point

| Parameter | Value | Class |
|---|---:|---|
| Naphtha feed | 200 kg | worked basis |
| Dilution steam/feed | 0.50 kg/kg | G |
| Temperature | 1123 K (850°C) | G, plausible anchor |
| Pressure | 0.20 MPa | G |
| Residence time | 0.30 s | G |
| Naphtha conversion | 70% | G target |

### 6.2 Balanced reaction channels

Each channel is first order in the effective feed:

\[
r_j=k_jC_F,\qquad k_j=A_j\exp(-E_j/RT).
\]

The compiler evaluates the available virtual-feed concentration from the real stored cuts:

\[
C_F=\frac{1}{V}\min\left(\frac{12n_{LN}}{5},
\frac{12n_{HN}}{7}\right).
\]

At the authored 5:7 molar ratio this is simply the total naphtha-molecule concentration; an off-ratio feed leaves the excess real cut unreacted rather than creating virtual material.

| Channel | Balanced surrogate reaction | Branch fraction | Ea (kJ/mol) | A (s⁻¹) | k at 1123 K (s⁻¹) | ΔHr,298 (kJ/mol feed) | Class |
|---|---|---:|---:|---:|---:|---:|---|
| C1 | F → 2 C2H4 + C3H6 + 7/6 H2 + 1/6 C(s) | 0.45 | 210 | 1.058×10¹⁰ | 1.80596 | +316.6 | G |
| C2 | F → C2H4 + C4H6 + CH4 + 7/6 H2 + 1/6 C(s) | 0.25 | 220 | 1.715×10¹⁰ | 1.00331 | +280.7 | G |
| C3 | F → C6H6 + CH4 + 19/6 H2 + 1/6 C(s) | 0.20 | 235 | 6.839×10¹⁰ | 0.80265 | +199.3 | G |
| C4 | F → C2H6 + C5H10 + 1/6 H2 + 1/6 C(s) | 0.10 | 200 | 8.054×10⁸ | 0.40132 | +85.0 | G |

Here `F = C(43/6)H(49/3)`. The small explicit coke channel accounts for carbon that a `C7H16` shortcut would lose; it also gives a natural future furnace-fouling mechanic. Every row now balances C and H against the aggregate.

The total rate constant is 4.01324 s⁻¹, hence

\[
X=1-\exp(-k_{total}\tau)=0.700.
\]

The activation energies and branch fractions are not claimed as a published industrial furnace fit. They are a transparent, plausible thermal-cracking range calibrated to the stated conversion and yield. Published lumped naphtha models, such as this [six-lump study](https://doi.org/10.1016/j.fuel.2013.02.020), support the general reduction approach but are not interchangeable with this steam-cracker fit.

### 6.3 Cracker material and energy result

| Outlet | kg per 200 kg naphtha |
|---|---:|
| Unconverted naphtha | 60.000 |
| Ethylene | 44.046 |
| Propylene | 25.853 |
| 1,3-butadiene | 18.463 |
| Methane | 9.856 |
| Hydrogen | 4.037 |
| Benzene/BTX lump | 21.329 |
| Ethane | 4.105 |
| C5+ lump | 9.575 |
| Coke/fouling solid | 2.733 |
| Dilution steam, chemically unchanged and recovered | 100.000 |

The non-steam rows close on 200 kg naphtha within 0.004 kg from displayed molecular-weight rounding; including steam, both sides are 300 kg. The branch-weighted standard reaction enthalpy is about **+260.8 kJ/mol of converted aggregate feed**, or **+356 MJ** for this batch, before feed vaporization and sensible heating. This value is a diagnostic consequence of the formation-enthalpy ledger, not a second heat source to add to the reactor. The furnace supplies the resulting enthalpy deficit plus hot-feed duty; the quench removes sensible heat rapidly.

## 7. Unit 3: ethylene oxidation to ethylene oxide

### 7.1 Network and operating point

The desired and combustion paths are:

\[
\mathrm{C_2H_4+\tfrac12O_2\rightarrow C_2H_4O}
\qquad \Delta H_{r,298}\approx-105.1\;\mathrm{kJ/mol}
\]

\[
\mathrm{C_2H_4+3O_2\rightarrow2CO_2+2H_2O(g)}
\qquad \Delta H_{r,298}\approx-1323.1\;\mathrm{kJ/mol}.
\]

| Parameter | Value | Class |
|---|---:|---|
| Temperature | 520 K | G anchor |
| Pressure | 2.0 MPa | G anchor |
| Nominal residence time | 2.0 s | G |
| Per-pass ethylene conversion | 12% | G target |
| EO selectivity on converted ethylene | 84% | G target |
| Overall conversion with recycle | 98% | G target |

For game use:

\[
r_j=k_j(T)C_E f_{O_2},\qquad
f_{O_2}=\operatorname{clamp}(p_{O_2}/0.15\mathrm{\,MPa},0,2).
\]

| Path | Ea (kJ/mol) | A (s⁻¹) | k at 520 K (s⁻¹) | Source class |
|---|---:|---:|---:|---|
| Epoxidation | 60.7 | 6.717×10⁴ | 0.053690 | M Ea, G A |
| Combustion | 73.2 | 2.305×10⁵ | 0.010227 | M Ea, G A |

The activation energies come from Lafarga et al.'s [Ag–Cs/α-Al2O3 kinetic study](https://doi.org/10.1021/ie990939x), whose best fit was a dual-site Langmuir–Hinshelwood form and whose reported average errors were not zero. The much simpler expression above retains those activation-energy anchors, while its prefactors are game-calibrated to 12% conversion and 84% selectivity. It must be labelled as such and used only over a configured range such as 483–570 K. Oxygen and catalyst-capacity limiters are mandatory.

### 7.2 Oxidation result

From 44.046 kg (1.57008 kmol) ethylene, after recycle convergence:

| Quantity | Result |
|---|---:|
| Ethylene converted | 1.53868 kmol |
| Ethylene to EO | 1.29249 kmol |
| Ethylene combusted | 0.24619 kmol |
| EO product | 56.938 kg |
| Unconverted ethylene | 0.881 kg |
| O2 consumed | 44.312 kg |
| CO2 coproduct | 21.669 kg |
| Water coproduct | 8.870 kg |
| Formation-enthalpy diagnostic, released | 462 MJ |

The 12% is a reactor per-pass conversion, while 98% is a flowsheet result after recycle. Keeping those concepts separate prevents a common mass-balance error.

## 8. Unit 4: ethylene-oxide hydration

### 8.1 Published series-parallel kinetics

The liquid reactions are:

\[
\mathrm{EO+H_2O\xrightarrow{k_1}MEG}
\]

\[
\mathrm{EO+MEG\xrightarrow{k_2}DEG}
\]

\[
\mathrm{EO+DEG\xrightarrow{k_3}TEG}.
\]

For concentrations in mol/L and rates in mol/(L·min):

\[
r_1=k_1C_{H_2O}C_{EO},\quad
r_2=k_2C_{MEG}C_{EO},\quad
r_3=k_3C_{DEG}C_{EO}
\]

\[
k_1=\exp(13.62-8220/T)
\]

\[
k_2=\exp(15.57-8700/T)
\]

\[
k_3=\exp(16.06-8900/T).
\]

These are published uncatalysed correlations from Altiokka and Akyalçın, [DOI 10.1021/ie901037w](https://doi.org/10.1021/ie901037w); the accessible [US EPA HERO record](https://hero.epa.gov/reference/4719607/) reproduces the rate form, units, constants, and series-parallel interpretation.

Converted exactly to SI Arrhenius form, with rate constants in m³/(mol·s):

| Reaction | A (m³/mol/s) | Ea (kJ/mol) | k at 388 K (L/mol/min) |
|---|---:|---:|---:|
| Water → MEG | 13.7069 | 68.3449 | 0.000517984 |
| MEG → DEG | 96.3416 | 72.3358 | 0.00105662 |
| DEG → TEG | 157.260 | 73.9987 | 0.00103005 |

The same paper reports catalyst-present correlations for Amberjet 4200/HCO3− at 0.15 mol HCO3− equivalent/L:

\[
k_1=\exp(19.60-9580/T),\quad
k_2=\exp(20.19-10171/T),\quad
k_3=\exp(19.06-9743/T)
\]

in L/(mol·min). They are an optional catalyst upgrade, not multipliers to apply on top of the uncatalysed constants. Until the full experimental metadata are encoded, conservatively restrict either fit to 358–388 K and a pressurised liquid phase.

### 8.2 Worked reactor

Use 388 K (114.9°C), sufficient pressure to retain the liquid phase, an initial EO concentration of 1 mol/L, and 20 mol water per mol EO. Integrating the uncatalysed batch/PFR material balances to 99% EO conversion gives a residence time of approximately **427.9 min** for this kinetic model. That long time is a result of choosing an in-range uncatalysed reference; the catalyst-present fit is the faster gameplay upgrade.

Per initial mole of EO, the final molar amounts are:

| Species | mol | Comment |
|---|---:|---|
| EO | 0.009999 | 1% unconverted |
| Water | 19.056776 | mostly recovered and recycled |
| MEG | 0.897883 | desired product |
| DEG | 0.043907 | coproduct |
| TEG | 0.001435 | coproduct |

Among glycol molecules, selectivities are about 95.19% MEG, 4.65% DEG, and 0.15% TEG. Approximate standard liquid reaction heats from the curated formation enthalpies are -91.6, -94.8, and -97.6 kJ/mol respectively.

Applied to 1.29249 kmol EO from the oxidation unit:

| Feed or outlet | kg |
|---|---:|
| EO feed | 56.938 |
| Water feed | 465.692 |
| Water after reactor | 443.729 |
| Unreacted EO | 0.569 |
| MEG | 72.030 |
| DEG | 6.022 |
| TEG | 0.279 |
| Formation-enthalpy diagnostic, released | 112 MJ |

The water is a circulating inventory, not 465.7 kg of fresh water every cycle. Separation losses and purge are intentionally not applied, so the headline result is **about 72.0 kg MEG per 1,000 kg illustrative crude before plant-wide losses**.

### 8.3 Overall mass and reaction-energy closure

On a net basis, omit the internally recycled dilution steam and hydration water. Hydration consumes 21.963 kg water; the EO combustion section separately produces 8.870 kg water. If that water is recovered into hydration, external make-up falls to 13.093 kg.

| Net external input | kg |
|---|---:|
| Crude | 1000.000 |
| Oxygen consumed | 44.312 |
| Hydration water consumed | 21.963 |
| **Total** | **1066.275** |

| Net outlet | kg |
|---|---:|
| Non-naphtha crude products | 800.000 |
| Cracker coproducts + unconverted naphtha, excluding ethylene | 155.954 |
| Unconverted EO-loop ethylene | 0.881 |
| CO2 | 21.669 |
| EO-reactor water | 8.870 |
| Unreacted hydration EO | 0.569 |
| MEG | 72.030 |
| DEG | 6.022 |
| TEG | 0.279 |
| **Displayed-row total** | **1066.274** |

The one-gram difference from the 1,066.275 kg rounded input total is display rounding across several species; the unrounded element-basis calculation is the acceptance value.

Rounded reaction-energy ledger:

| Section | Reaction energy on stated reference basis |
|---|---:|
| Steam cracking | +356 MJ required |
| Ethylene oxidation/combustion | -462 MJ released |
| EO hydration | -112 MJ released |

These entries are diagnostics derived from the formation-enthalpy change and are useful when validating isothermal utility duty; equipment must not add them as a separate reaction source. They also cannot simply be netted to size utilities: the units operate at different temperatures, and cracker feed heating, vaporization, steam generation, column reboilers, condensers, and heat-exchanger approach temperatures remain separate enthalpy duties. The value of the ledger is that each unit can close its own energy balance.

## 9. Example-specific validation and remaining data work

The reusable scientific acceptance suite is defined in [SCIENTIFIC_MODEL.md](./SCIENTIFIC_MODEL.md). This route adds the following fixtures:

- regenerate every stream table from the exact component, assay, reaction, and kinetic records shipped in the data pack;
- close the unrounded element-basis net balance whose input rounds to 1,066.275 kg, including virtual-basis expansion and the coke ledger, with no persisted `F` inventory;
- recompute the +356/-462/-112 MJ diagnostic reaction-energy ledger from the selected formation-enthalpy branch without adding it as equipment heat;
- reproduce the 388 K hydration residence time and MEG/DEG/TEG selectivities from the cited rate equations;
- verify that every cracking channel balances C/H and that all oxidation/hydration channels balance C/H/O;
- run this complete route as a fixed-cadence regression at 1/2/5/10/20-second outer updates against a fine internal-step reference;
- preserve the EO gas/liquid reference-state derivation so that its known source inconsistency cannot silently re-enter the dataset;
- add two measured or deliberately game-calibrated viscosity anchors to every transported crude cut, then validate the fitted global liquid kernel against the ASTM D341 source curve;
- fit and validate gas-kernel bands through the actual cracker and EO-reactor temperature ranges;
- fit water/glycol Grunberg–Nissan interactions over 358–388 K before using viscosity-dependent hydration hydraulics;
- add a compressed-liquid EO viscosity curve plus required EO–water and EO–glycol interaction policy, or retain the explicit `TRANSPORT_UNSUPPORTED` result above the trace threshold;
- confirm that coke stays in the immobile fouling inventory, contributes Cp/enthalpy, and cannot enter a fluid capability or flash.

Until the viscosity fits and executable regeneration tests exist, the mass/kinetic example is complete as a paper calculation but is not yet a finished game data pack.

## 10. Recommendation

This scope is enough for a convincing vertical slice once the listed fits and executable regeneration tests are complete: one crude assay, a fractionator, a lumped cracker, an oxidation reactor with recycle, and an EO hydration/separation section. It demonstrates composition, phase change, heat duty, kinetics, selectivity, coproducts, and recycle without pretending to be Aspen/HYSYS.

The first route data pack should stop here. Validate whether these behaviours are fun and understandable before adding rigorous VLLE, activity models, catalyst deactivation, sulfur chemistry, or a larger crude library.

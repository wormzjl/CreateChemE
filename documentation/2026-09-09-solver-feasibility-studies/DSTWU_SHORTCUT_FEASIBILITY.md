# DSTWU-style shortcut calculation: feasibility for CreateChemE

Investigation date: 9 September 2026. Source inspection: HEAD `8547fea` plus the current working tree. This document combines local source and reference checks with the [completed ChatGPT investigation](https://chatgpt.com/g/g-p-6aa026927b5c819187b10da97168efd4-createcheme/c/6aa13fb1-1d04-83ec-8fb0-8f0a7c432664). It proposes a method; it does not implement a solver or establish a performance improvement.

## Recommendation

A shortcut distillation design calculator is feasible with the existing hydrocarbon property package. Its strongest initial role would be a separate design assistant for a single-feed, two-product separation. An optional seed adapter for the rigorous V3 solver is a second experiment. A direct replacement of the crude-column initializer is not justified by the shortcut equations alone.

The important difference is the direction of the calculation. A recovery-based shortcut asks what column could accomplish a specified split. V3 asks what split results from an authored column with fixed geometry and operating specifications. Converting between those questions requires explicit additional choices.

## What Aspen documents

Aspen identifies DSTWU as a Winn–Underwood–Gilliland shortcut for one feed and two principal products, with a partial or total condenser. Recoveries and either reflux or theoretical-stage count determine a preliminary design, including minimum stages/reflux, the other operating variable, feed location, and heat duties. These are documented capabilities, not a reconstruction of Aspen's source code. The public user guide examined is version 10.1, so it does not establish the exact numerical defaults of a current installation. [Aspen Plus User Guide, chapter 10](https://user.eng.umd.edu/~nsw/chbe446/AspenPlusV10UserGuide1.pdf)

Aspen's training material describes Winn as the minimum-stage method, Underwood as the minimum-reflux method, and Gilliland as the finite-stage/reflux relationship. Its worked example specifies both key recoveries into the distillate: 99% light key and 1% heavy key. Recovery is a fraction of that component's feed, not product purity. The training cautions against departures from constant relative volatility and constant molar overflow. [Aspen training, Dist-006, 2012, university-hosted copy](https://lms.nchu.edu.tw/sysdata/doc/2/2e7a44a2aa92e751/pdf.pdf)

The older unit-model manual also permits a condenser water-decant outlet. That does not make DSTWU a general wet, multi-draw tray model. Aspen distinguishes **Distl**, an Edmister shortcut rating model, from DSTWU design. Its **SCFrac** instead addresses multiple-product crude/vacuum columns with optional stripping steam, using sections and its own restrictive assumptions, including negligible liquid flow between sections. [Aspen Unit Operation Models, chapter 4, public mirror](https://studylib.net/doc/26271535/aspenplus-manul)

The exact current Aspen algorithms for non-key distribution, averaging K-values, feed location, scalar root selection, and numerical safeguards were not established from these sources. Do not present a generic FUG implementation as a bit-for-bit DSTWU clone, or label Kirkbride as Aspen's confirmed internal feed-stage method.

The 2013 input-language guide independently confirms that both recoveries are defined into distillate, and describes `NSTAGE` as including condenser and reboiler. That interface count must be distinguished from a theoretical equilibrium-contact count, where a total condenser contributes no additional separation. [Aspen V8.4 Input Language Guide, DSTWU, pp. 210–212](https://www.scribd.com/document/245842533/Aspen-2013-InputLanguageGuide)

## An independently implementable shortcut

The following is a proposed constant-volatility Fenske–Underwood–Gilliland baseline. It is deliberately distinguishable from a qualified Winn implementation and from proprietary Aspen behavior.

Let `f_i = F z_i`, `d_i` and `b_i` denote component feed, distillate and bottoms molar flows. Define `r_i = d_i/f_i`, so `d_i = r_i f_i` and `b_i = (1-r_i) f_i`. Require present, distinct keys and meaningful recovery targets; handle absent species outside these ratios.

1. Estimate product compositions, then evaluate representative top and bottom equilibrium ratios using the existing PR model. Use a declared averaging policy for `alpha_i = K_i/K_HK`, and record how much it changes across the column. Composition-dependent fugacities require a consistent liquid/vapor pair, not arbitrary identical compositions masquerading as a converged phase split.
2. Estimate non-key recoveries using an explicit distribution model, maintaining `d_i + b_i = f_i`. A smooth log-odds correlation anchored at both key recoveries is an available baseline. Iterate product states and volatility estimates with a work limit.
3. Compute the total-reflux minimum:

   `N_min = ln[(d_LK/b_LK)/(d_HK/b_HK)] / ln(alpha_LK)`.

4. Under the simple adjacent-key, single-pinch assumption, solve on a pole-free interval:

   `sum_i alpha_i z_i / (alpha_i - theta) = 1 - q`, with `alpha_HK < theta < alpha_LK`.

   Then calculate `R_min + 1 = sum_i alpha_i x_Di / (alpha_i - theta)`.

5. Relate `X = (R-R_min)/(R+1)` and `Y = (N-N_min)/(N+1)` using a named Gilliland fit. Preserve continuous stage estimates and apply documented rounding and stage-count conventions only at the interface.

BioSTEAM provides a public implementation of this FUG sequence, geometric volatility averaging, non-key recovery estimation, and a Gilliland fit. It is an independently inspectable reference, not evidence of Aspen's exact implementation. [BioSTEAM source](https://biosteam.readthedocs.io/en/latest/_modules/biosteam/units/distillation.html)

`q` expresses the feed's thermal effect on internal traffic. An enthalpy-based shortcut is `(h_V,ref-h_F)/(h_V,ref-h_L,ref)`, with consistently defined saturated reference states. It can exceed one for subcooled feed or become negative for superheated feed. Simply using `1 - flash vapor fraction` loses those thermal effects and is only a declared approximation. Feed-location estimates such as Kirkbride are an additional correlation; they do not solve a rigorous optimum. [AmsterCHEM shortcut implementation documentation](https://www.amsterchem.com/rust_cobia_doc/distillation_shortcut_unit/index.html)

Underwood has multiple roots and distributing-component cases. A root finder must not cross a volatility pole or silently choose an arbitrary interval. Adjacent keys make a first prototype easier to define. Nonadjacent keys and broad distributing regions require a more general treatment, not just a larger iteration budget. [Skogestad, Distillation Theory, Underwood discussion](https://skoge.folk.ntnu.no/publications/1999/old/DistillationTheory.pdf)

Winn generalizes the minimum-stage calculation through a fitted K-value relationship rather than only a single constant relative volatility. Qualifying that fit would be a separate phase after the transparent Fenske baseline. This improvement to one relation does not remove the assumptions in the rest of the shortcut. [Original research comparing total-reflux formulations](https://www.sciencedirect.com/science/article/abs/pii/S0263876219305982)

A transparent Winn-type reconstruction would fit `ln K_i = a_i + b_i ln K_HK`. Multiplication of the total-reflux equilibrium relations then gives `N_min = [ln(x_D,LK/x_B,LK) - b_LK ln(x_D,HK/x_B,HK)] / a_LK`, using consistent liquid endpoints and equilibrium-contact counting. Setting `b_LK = 1` and `a_LK = ln(alpha_LK)` recovers Fenske. This is a derivation under a declared power-law approximation, not Aspen's recovered fitting algorithm. Near-zero fit denominators, poor fit residuals and changing key order require explicit handling. Winn is distinct from the Wang–Henke-style staged refinement already present in V3.

For a simple total-condenser surrogate, the estimated internal totals are `L_R = R D`, `V_R = (R+1)D`, `L_S = L_R + qF`, and `V_S = V_R - (1-q)F`. Reject nonpositive required traffic. Once boundary states are defined, calculate condenser duty from its stream enthalpy balance and close the overall energy balance for reboiler duty. Those are shortcut estimates, not tray-by-tray energy closure. A partial condenser needs its own phase split and reflux definition.

## Small numerical sanity example

This is an independently calculated constant-volatility example, not Aspen output or a V3 benchmark. Take 100 mol/s equimolar binary feed, `alpha_LK = 2`, saturated liquid feed (`q = 1`), and distillate recoveries 0.95 for LK and 0.05 for HK.

| Quantity | Result |
|---|---:|
| Distillate / bottoms | 50 / 50 mol/s |
| LK fraction in distillate / bottoms | 0.95 / 0.05 |
| Fenske minimum stages | 8.4959 |
| Underwood root | 1.3333 |
| Minimum reflux ratio | 1.7000 |
| Selected reflux, 1.5 times minimum | 2.5500 |
| Gilliland estimate using the public BioSTEAM fit | 15.5923 equilibrium stages, rounded upward to 16 |

The calculation demonstrates the small algebraic problem involved. It establishes neither physical property accuracy nor a benefit for the crude preset. Hardware tray counts require explicit treatment of condenser and reboiler stages: under the convention that the 16 stages include one equilibrium reboiler and exclude the total condenser, this means 15 internal V3 trays.

## Fit with current V3

| Existing facility | Reuse or mismatch |
|---|---|
| `V3ThermoModel` | Provides TP flash, phase fugacity and enthalpy. A bounded bubble/dew endpoint service within the same V3 property model would still need qualification. |
| `V3ColumnInitializer` | Already builds component material-balanced flows and performs PR/bubble-point/energy refinement. Reuse this for reconstructing and correcting a shortcut seed. |
| `V3DryMeshState` | Suitable target for an internal seed adapter after topology, active-component and positivity rules are met. |
| `V3ColumnInput` and `V3ColumnSpecification` | Fix tray count, feed tray, reflux, condenser temperature and reboiler duty. They contain no key-recovery design specification. |
| `V3ColumnTopology` | Counts trays separately from condenser node 0 and reboiler node `trayCount+1`; shortcut stage counts cannot be copied directly. |
| Existing continuation and acceptance audit | Retain for correcting the seed and verifying the exact authored problem. |

Source paths are under [science/column/v3](../../src/main/java/com/wormzjl/createcheme/science/column/v3). The implemented reflux split uses `R/(1+R)`, corresponding to reflux divided by net organic liquid distillate. `V3ColumnStreamProperties` can publish both overhead vapor and liquid distillate, in addition to bottoms. A shortcut's single distillate stream must therefore have an explicit mapping to V3's overhead streams; it cannot silently mean liquid distillate alone. On the same dry hydrocarbon basis, if `beta_D = D_vapor/(D_liquid+D_vapor)`, algebra gives `R_total-overhead = R_V3 (1-beta_D)`. This conversion does not establish which convention Aspen uses in a particular release. The general `science/thermo` flash solvers are a separate API and must not be substituted merely because they expose a convenient endpoint calculation.

### Proposed first implementation boundary

Use separate immutable `ShortcutDesignInput`, `ShortcutDesignResult` and diagnostics types. Inputs should include the property package, feed state, pressure, explicitly selected keys, explicitly directed recoveries, total-condenser mode, and either reflux or theoretical stages. Do not add recovery equations to the already specified V3 problem without declaring which existing specifications become free.

Start with dry, single-feed, two-product hydrocarbon columns; adjacent keys; a total condenser; and a limited pressure range with valid property roots. Return typed outcomes for unsupported topology, unsuitable volatility behavior, missing brackets, infeasible shortcut targets, and budget exhaustion. A shortcut failure describes this approximation, not a proof that rigorous V3 has no solution.

Add a seed adapter only after the standalone method is validated. It should preserve the requested V3 geometry, feed location and operating specifications, map approximate temperatures/compositions onto those trays, reconstruct component flows through existing material solves, and run the normal corrector and audit. Keep the current seed as fallback. A shortcut-selected design can instead be offered as a *new proposed input*, whose changed specifications are visible to the user.

For an already fixed V3 request, choosing arbitrary 99/1 key recoveries can produce a seed for an entirely different duty or separation. A bounded outer search over surrogate recoveries/product split, assessed against the authored duty and temperature, is one possible adapter. It adds a nonlinear problem of its own and needs a cost comparison. It is not a free consequence of implementing FUG.

### Crude-column relevance

A wet crude tower with multiple side products and coolers does not match the first implementation boundary. It may benefit from a dry two-product *surrogate seed*, but success depends on the subsequent feature ramps. A later section-based shortcut, conceptually closer to SCFrac, would require cut definitions, section material/energy coupling, and steam handling. Do not assemble independent DSTWU sections and assume their shared flows and duties reconcile.

## Validation before production use

1. Check recovery versus purity, directed recovery conversion, component balances, and unit scaling on hand-computable binary cases, including the example above.
2. Check limiting behavior: `N` approaches `N_min` at increasing reflux; stage requirement grows near `R_min`; separation becomes difficult as key volatilities approach equality. Test recoveries near endpoints without log overflow.
3. Exercise subcooled, two-phase and superheated feed conventions, plus absent keys, reversed keys, intermediate volatility poles, missing phase roots and unsupported phase/topology cases.
4. Compare a fixed, documented FUG convention against an independent public implementation. A separate licensed Aspen comparison would be needed for any claim of DSTWU numerical parity; none was run for this investigation.
5. For initialization, compare current versus shortcut-assisted V3 on the same authored inputs and audits. Include initializer/property cost, all continuation attempts, convergence rate, failure cost and elapsed time. A successful shortcut calculation, smaller initial residual, or lower terminal iteration count is insufficient evidence of improvement.

Recommended next step: a standalone FUG design prototype and a small dry-column comparison, followed by a separate decision on seed integration. Broader Winn/partial-condenser and crude-section support should follow demonstrated need and validation.

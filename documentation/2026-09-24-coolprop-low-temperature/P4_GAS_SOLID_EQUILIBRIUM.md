# P4 stage 2a: gas-solid equilibrium with the CO2-I crystal in the equilibrium engine

Batch `2026-09-24-coolprop-low-temperature`, stage P4 stage 2a, 2026-09-25. Branch `claude/coolprop-multiphase-thermo-37f6b0`,
base `5cac6a0` (P4 stage 1 recorded). Engine only: nothing under `science.fluid` changed, and the network still requests
fluid-only competition. Commits: section 11.

## 0. Outcome

- **Contract.** A package qualifies the competition of the fluid phases with all of its usable, answer-graded crystals
  as one set (D8): `PhaseContract.forPackage` (the network keeps `forNetworkPackage`, fluid-only). A request names the
  crystals or asks for the package's (`PhaseCompetition.FLUID_AND_CRYSTALS`, resolved by the contract). A package
  without such crystals refuses it (`PHASE_COMPETITION_NOT_QUALIFIED`), and water with crystals is refused as water
  chemistry (hydrates). Fluid-only requests are unchanged bit for bit (60 TP and PH comparisons).
- **TP.** Each crystal is tested against the fluid answer by fugacity. Where it forms, the vapour-solid equilibrium is
  solved for the solid amount, and the reduced feed is re-flashed with the stability test. Classification `VAPOR_SOLID`;
  a pure feed deposits completely (`SOLID_PRESENT`); every answer that needs a crystal beside a liquid is the typed hold
  `SOLID_LIQUID` (P5). On the grid of (b): 108 deposition states, conservation defect at most 1.7e-16,
  `|mu_V - mu_s|/RT` at most 8.7e-10, at most 5 Newton steps.
- **A finding that shapes the rule (proposed D17).** Below CO2's triple point the fluid-only answer of a CO2-bearing
  gas often holds a subcooled CO2-rich liquid, which is metastable against the crystal: 92 of the 108 deposition states
  have such a liquid. The engine therefore decides by the fluid the crystal leaves behind, not by the fluid-only answer:
  a stable single vapour is the answer, and anything else is the hold. Refusing on the fluid-only liquid would have held
  85 % of the deposition states.
- **Onsets.** `depositionTemperature(P, z)` (the frost point) and `depositionPressure(T, z)`. The pure limit with a
  1e-6 N2 trace is within 1.6e-5 K of the pure sublimation temperature; this is the ideal dilution shift. The
  partial-pressure approximation, `p_sub,pure(T)/y_i`, is reported beside the answer. It overestimates the frost
  temperature by 0.1 to 0.2 K at 0.1 MPa, about 2 K at 1 MPa and 9 to 12 K at 5 MPa (mean 2.6 K).
- **Jäger and Span 2012 Figure 13.** Reproduced qualitatively and nearly quantitatively. The relative deviation from
  the ideal sublimation pressure is positive and grows with temperature and with N2: +0.45 %, +0.087 % and +0.0086 %
  at 216 K for 5 %, 1 % and 0.1 % N2, against the figure's roughly 0.35 %, 0.06 % and 0.01 %.
- **PH and UV** include the solid's enthalpy and volume.
  - Round trips (20 states): T to 7.9e-12, P to 1.4e-12, the solid amount to 8.7e-12 of the CO2 feed, H and U to
    7.1e-13 relative.
  - Reversibility: 200 K is recovered to 1e-14 (PH) and 7e-13 (UV).
  - Exhaustion: the solid decreases monotonically and vanishes exactly at the onset (PH at the frost enthalpy returns
    the frost temperature to 1e-9 K).
  - Pure CO2 sublimation step from the balance: the sublimation enthalpy is 25,200.6 J/mol at 194.47 K (+0.003 % of
    25.2 kJ/mol).
- **Holdouts (G4F1, CO2 frost points in methane-rich gas).**
  - Binary CH4 + CO2, 135 of 136 points scored: pooled MAD 1.354 K (P0 target 1.5 K), bias -0.36 K, worst 5.19 K. Per
    dataset: Le and Trebble 2.31 K, Zhang 0.67 K, Xiong 0.70 K.
  - Ternaries with N2 or C2H6: MAD 0.94 to 1.73 K.
  - The partial-pressure approximation scores 2.6 K MAD, worst 10.2 K.
  - Le and Trebble's 1 % CO2 points disagree with Xiong's at the same conditions; section 6.2 gives the details.
  - The CO2 + N2 three-phase points of Fandiño 2015 are never answered as vapour-solid. There the hold's onset
    temperature lies within 0.06 K of the measured three-phase temperature.
- **Cost (warm, reused workspace).**
  - A vapour-solid TP answer takes 18 µs (from one vapour) to 34 µs (when the fluid-only answer was vapour-liquid);
    either way that is 2 fluid equilibria.
  - A no-solid answer under the crystal competition takes 4.6 µs, against 2.7 µs fluid-only at the same state.
  - Onsets take 28 to 33 µs; PH at a deposit 213 µs (10 TP calls) and UV 418 µs (19 calls).
  - The anchor (g0, g1) takes 2.6 µs once per service; the crystal's chemical potential takes 72 ns per state.
- **Gates.** thermo + material: 232 tests (217 before). Full suite: 1,256 (1,241 before). `fluidScienceTest`: 219
  (unchanged). All green, and no pin moved (no record changed).

## 1. Contract change

| Item | Change |
|---|---|
| `PhaseCompetition` | Third component `packageCrystals`; `FLUID_AND_CRYSTALS` = the fluid phases and every crystal the package admits, unnamed. The two-argument constructor and `withCrystals`/`withHydrates` are unchanged. `crystalsCompete()`. `fluidOnly()` is false for the package request |
| `PhaseContract.forPackage(catalog, id)` | The network contract (same basis, identity, water participation, domain, fluid coverage), plus the whole set `withCrystals(admitted)` when the package admits any crystal |
| `PhaseContract.admittedCrystals` | The package's declared crystals whose records are usable and graded `qualified` or `estimated_declared_error` (the pilot: CO2-I r2). A research-only record is not admitted (tested by editing the grade) |
| `PhaseContract.crystalCompetition()`, `resolve()` | The largest qualified crystal set; `FLUID_AND_CRYSTALS` resolves to it, or stays unresolved and is refused |
| `forNetworkPackage` | Unchanged (fluid-only); its comment now points to `forPackage` |
| `FluidTpEquilibrium.forPackage(catalog, id)` | The contract of `forPackage`, the cubic evaluator, and one crystal evaluator per admitted crystal; no water model |
| New constructor `(contract, evaluator, settings, freeWater, crystals)` | Each crystal is checked: its species is in the basis; a qualified competition names it; its ideal gas is the evaluator's for that species (the chemical potentials compare). It is then anchored once. A qualified crystal without an evaluator keeps the P2 behaviour (`NOT_IMPLEMENTED` with "P4" in the detail; the existing ammonia test is unchanged) |
| Contract checks | Species, then water chemistry (new: crystals competing with water present are `WATER_CHEMISTRY_NOT_MODELLED`, hydrates), then competition (resolved), then domain, then implementation |
| Domain of a crystal competition | The contract domain with the crystal species' range replaced. Its temperature floor is the larger of the crystal's and the spine's (90 K for CO2), its ceiling the smaller of the crystal's and the fluid range's (300 K). Its pressure is the fluid range's bounds capped by the crystal's (100 Pa to 10 MPa). A fluid-only request keeps CO2's 216.592 K floor (tested: `OUT_OF_DOMAIN` at 150 K) |

Result types:

| Type | Change |
|---|---|
| `EquilibriumResult.Classification.VAPOR_SOLID` | New: one fluid phase that is not liquid-like (labelled `VAPOR`, or `FLUID` for a one-root fluid), then one crystal |
| `SOLID_PRESENT` | Now a crystal without a vapour beside it (a pure species deposited completely; the solid end of a pure sublimation balance). The constructor rule: a crystal among the phases exactly when the classification is one of the two |
| `EquilibriumResult.Deposition` | New record, carried by every engine answer with a crystal: crystal, species, the feed's driving force `(mu_i - mu_s)/RT`, the residual, solid amount, `complete`, the trace limit, Newton steps, fluid equilibria. The constructor accepts it only with a crystal among the phases. The P2 test double without one still builds |
| `EquilibriumResult.SOLID_LIQUID` | New detail prefix of the typed hold: `UNSUPPORTED`, `NOT_IMPLEMENTED`, no phases, with the fluid answer's diagnostics |
| `DepositionOnset` | New record of the onset functions: status, reason, detail, violation, crystal, T, P, the partial-pressure approximation, the residual, fluid equilibria, the fluid answer at the onset and the coverage |
| `SolidPhaseEvaluator` | `capabilities()` and `derivatives(...)` defaults (empty and unsupported); `CrystalPhaseEvaluator` implements both |
| `PhaseDomain` | `envelope()`, `range(i)`, `withRange(i, range)` (additive) |

## 2. Algorithm

### 2.1 TP

1. The fluid answer of the whole feed (the P3 dry path, unchanged). A failure is returned as it is.
2. For each competing crystal whose species `i` is carried, `D = (mu_i - mu_s)/(R T)`. Here `mu_i` is taken from the
   fluid answer's first phase: equal in every phase of an equilibrium, or a coexisting state's for a pure coexistence.
   `mu_s` comes from the anchored crystal. Fugacities only.
3. Decision:

| Situation | Answer |
|---|---|
| No crystal's species carried | The fluid answer, "solids assessed" |
| Pure feed of the crystal's species, one non-liquid phase, `|D| <= 1e-9 (Z_V - Z_s)` | `PURE_COEXISTENCE_UNDERDETERMINED` with the vapour and the crystal states (TP leaves the split undetermined) |
| `D <= 1e-9`, a liquid below the carried crystal species' fluid range | Hold `SOLID_LIQUID` (a liquid of CO2 below 216.592 K; whether it freezes is P5's liquidus) |
| `D <= 1e-9` otherwise | The fluid answer, solids assessed (the driving force in the detail) |
| Pure feed, `D > 1e-9`, a liquid at or above the triple point | Hold (melting) |
| Pure feed, `D > 1e-9` otherwise | Complete deposition: the crystal alone, `SOLID_PRESENT`. Below T_t the pure species' liquid is metastable against its crystal at every pressure |
| Two crystals with `D > 1e-9` | Hold (not reachable in the pilot) |
| Mixture, `D > 1e-9` | The vapour-solid solve (2.2) |

4. Coverage: the fluid answer's grade folded with each competing crystal's grade (the worse of them; the CO2-I record is
   `estimated_declared_error`). The evidence is the fluid evidence without "fluid-only: solid phases not assessed",
   then "solids assessed (competition)", then the crystal's record line. That line names D16's declared errors:
   sublimation pressure 3 % from 150 K to T_t, melting 0.13 K, sublimation enthalpy 0.25 %, volume 0.3 %, expansion
   down to -22 % near T_t. It is built once per competition outside the critical band. Inside the band the answer keeps
   the fluid's research-only grade.

### 2.2 The vapour-solid solve

- **Unknown.** `s = ln(r/z_i)` in `(-infinity, 0]`, where `r` is the species' amount left in the vapour. The vapour
  holds `z - (z_i - r) e_i`.
- **Residual.** `f(s) = (mu_i^V(T, P, y(s)) - mu_s)/(R T)` on the vapour root of the reduced feed, evaluated with the
  cubic evaluator's derivative call. `f` increases with `s`, and `f(0) > 0`.
- **Newton.** The slope is `f' = 1 - y_i + y_i Phi_ii`, with `Phi_ii` the kernel's per-mole `d ln phi_i / d n_i`.
- **Safeguards.** A bracket `f(hi) > 0 > f(lo)`, with bisection when a step leaves it. `ln y` is concave in `s`, so the
  first Newton step overshoots to the root's left, after which the steps converge from the left.
- **Stop.** `|f| <= 1e-9`, at most 60 steps. Measured: 2 to 5 steps.
- **Trace floor.** At `y_i = 1e-15`, with `f` still positive, the deposition is complete: the vapour is the feed
  without the species, and the onset condition has been checked at the trace limit. This is not reachable in the pilot
  domain: the lowest equilibrium CO2 mole fraction there is about 1e-10 (the sublimation pressure at 90 K over
  10 MPa). The complete deposition reachable in practice is the pure feed of 2.1.
- **Not supersaturated.** When the feed as one vapour is not supersaturated (`f(0) <= 1e-9`) although the fluid answer
  gave `D > 0` through its liquid, the crystal forms out of the liquid: the hold.
- **Re-flash.** The reduced feed is flashed again with the stability test. It must be one phase that is not
  liquid-like; if not, the hold. On its own root it must meet the tolerance; if not, `NOT_CONVERGED`, which the grid
  never met. Other competing crystals are re-tested against the reduced vapour.
- **Amounts.** The smaller of `-z_i expm1(s)` (solid) and `z_i exp(s)` (vapour) is computed directly, and the larger
  by difference: conserved to rounding (worst 1.7e-16).
- **Result.** `[vapour (the re-flash phase), crystal]`, `VAPOR_SOLID`, the deposition record. When the fluid-only answer
  held a liquid, the detail adds that it was metastable against the crystal.

### 2.3 PH and UV

PH and UV are outer searches over TP answers (WP6b), so the solid's enthalpy `h_s = g_s + T s_s` and volume `v_s` enter
through the answers. Changes (fluid-only paths bit for bit):

- **Solid slope.** A crystal alone takes its own `c_p`, expansion and compressibility as the search slope.
- **Pure sublimation step.** For the pure species of a competing crystal, the step of `H(T)` (PH) or `V(P)` (UV)
  between the crystal alone and one fluid phase is answered from the balance, like a pure fluid's coexistence:
  - Newton in `1/T` on `(mu_V - mu_s)/RT` with slope `(h_V - h_s)/R`, or in `ln P` with slope `Z_V - Z_s`, to 1e-14;
  - then `target = N[(1 - beta) x_s + beta x_V]`;
  - the answer is `VAPOR_SOLID` with `coexistenceFromBalance`.
  A step above the triple point is melting, and returns the hold.
- **Warm restart.** The ideal-gas start ignores the heat of deposition and can land where a crystal meets a liquid (at
  90 K the N2 is liquid). A first TP answer that is not converged then restarts the search from the window's warm end.
- **Step cap.** Unbracketed PH steps are capped at 25 % of T when crystals compete.
- **Holds.** An inner UV balance that returns the hold ends the pressure search with it.

### 2.4 Onsets

- **Search variable.** `D` of the fluid answer of the whole feed (crystals left out) is searched in `x = 1/T`
  (`depositionTemperature`) or `x = ln P` (`depositionPressure`); `D` increases with `x` in both.
- **Start.** The partial-pressure approximation, or else the window's warm (or dense) end.
- **Steps.**
  - The first slope is `(h_ig,i - h_s)/R`, or 1 for the pressure search; secants follow.
  - Before a bracket, steps are capped at 0.2 x, or 2 in `ln P`.
  - Bisection when a step leaves the bracket.
- **Stop.** `|D| <= 1e-9`, or a bracket 1e-13 wide.
- **Window.** The crystal competition's domain for the material, plus the ideal-gas ranges. An onset beyond it is
  `OUT_OF_DOMAIN`, with the violation one ulp outside. Below the triple point, when the fluid at that end holds a liquid
  (the gas condenses first), the result is the hold instead.
- **Fluid at the onset.** It must be one phase that is not liquid-like; otherwise the hold (a liquidus or three-phase
  point).
- **Measured cost.** 4 to 6 fluid equilibria.

### 2.5 The crystal: anchored view, derivatives, cache

- **Anchored view.** `CrystalPhaseEvaluator.anchored(anchor)` solves g0 and g1 once and returns an immutable view:
  one function evaluation per state (72 ns for the chemical potential). The engine anchors each crystal at
  construction to its own evaluator's pure liquid at the triple point. The service is built per contract identity, so
  the anchor is cached per (fluid family, package, crystal) identity and no flash re-solves the constants. The
  evaluator's own `evaluate` keeps the last view while the anchor is equal.
- **Derivatives.** `derivatives(...)` fills `PhaseDerivatives(1)`:
  - `d ln phi/dT = -(h_s - h_ig)/(R T^2)` and `d ln phi/dP = v_s/(R T) - 1/P`, which are `d mu/dT = -s` and
    `d mu/dP = v`;
  - `dv/dT = alpha v`, `dv/dP = -kappa v`, `c_p`, and `dh/dP = v (1 - alpha T)`.
  - Capabilities: fugacity-state, volumetric and caloric. No composition block; reading one throws.
  - Against central differences at 5 states from 100 K to 250 K and 0.1 to 100 MPa: worst relative 2.4e-7.

## 3. Verification on the pilot package (step 6)

Test classes: `science.thermo.phase.GasSolidEquilibriumTest` (11 tests) and
`science.thermo.qualification.G4F1CarbonDioxideFrostPointHoldoutTest` (4 tests). Per-point output of the Gradle run:
`research/.../p4-gas-solid/gradle-gate-output-2026-09-25.txt`.

### 3.1 (a) Pure limit

z_CO2 = 1 - 1e-6 with 1e-6 N2, `depositionTemperature` compared with the pure-CO2 path of
`SolidCarbonDioxideQualificationTest` (the family's own solid-vapour coexistence):

| P, MPa | Pure path, K | Onset with 1e-6 N2, K | Difference, K | Pure feed onset, K | Difference, K | Partial-pressure T, K |
|---|---|---|---|---|---|---|
| 0.1 | 194.466712376 | 194.466699900 | -1.25e-5 | 194.466712376 | +6.0e-13 | 194.466700149 |
| 0.3 | 208.825580613 | 208.825565947 | -1.47e-5 | 208.825580613 | +3.7e-13 | 208.825566694 |
| 0.5 | 216.128018773 | 216.128002846 | -1.59e-5 | 216.128018773 | +4.3e-13 | 216.128004119 |

The bound is 1e-3 K; the worst is 1.6e-5 K. The shift is the ideal dilution `-R T^2/dh_sub * 1e-6`, which is 1.25e-5 K
at 194.5 K.

### 3.2 (b) Conservation and equal chemical potentials

The grid is CO2 in N2 with y 0.001, 0.01, 0.05, 0.1, 0.2, 0.3, 0.5; T 130, 150, 170, 190, 210 K; and P 0.1, 0.5, 1, 2,
5 MPa: 175 states.

- **Outcomes.**
  - 108 `VAPOR_SOLID`.
  - 60 without solid (unsaturated).
  - 7 typed holds: all at 130 K and 5 MPa, where the fluid left behind is dense N2 labelled liquid-like (N2 at Tr 1.03,
    Pr 1.47).
  - Plus 5 complete depositions of pure CO2 (150 K and 180 K at 0.1 MPa, 190 K at 0.1 MPa, 200 K at 1 MPa, 120 K at
    5 MPa): the crystal alone, 2.5 mol, conservation exact.
- **Independent checks on each deposit, from the returned phases.**

| Check | Bound | Worst |
|---|---|---|
| Conservation defect | 1e-13 | 1.73e-16 |
| `|mu_CO2^V - g_s|/RT` | 1e-9 | 8.71e-10 |
| Newton steps | 8 | 5 |
| The vapour alone, flashed fluid-only on an open contract | one phase | one phase at every deposit |
| `(G - G_fluid-only)/RT` | < 0 | -7.7e-6 (the least gain, next to an onset) |
| Fluid-only answer of the feed held a liquid (metastable) | reported | 92 of 108 |

### 3.3 (c) PH and UV: round trips, reversibility, exhaustion

Round trips: TP deposit, then (H, U, V), then PH(P, H) and UV(U, V), at 20 states: y 0.001 to 0.5, 130 to 210 K,
0.1 to 5 MPa.

| Quantity | Bound | Worst |
|---|---|---|
| `|T/T0 - 1|` (PH and UV) | 1e-9 | 7.9e-12 |
| `|P/P0 - 1|` (UV) | 1e-9 | 1.4e-12 |
| `|n_s - n_s0| / z_CO2` | 1e-9 | 8.7e-12 |
| `|H - H0| / |H0|`, `|U - U0| / |U0|` | 1e-11 | 7.1e-13, 4.9e-13 |
| `|H - H0| / (R T)` | | 3.5e-11 |
| PH TP calls | | mean 8.8, max 13 |
| UV TP calls | | mean 16.3, max 30 |

Reversibility: 10 % CO2 in N2 at 1 MPa and 200 K (vapour; frost point 192.754773 K), with 3000 J removed.

- **Fixed P.** Cooled to 172.512279 K with 0.08539430 mol solid; warmed back by 3000 J to 200.000000000002 K, no solid.
- **Fixed V.** Cooled to 163.922352 K and 0.742346 MPa with 0.09265646 mol solid; warmed back to 199.999999999866 K and
  0.999999999999 MPa, no solid.

Exhaustion at 1 MPa (PH at `H_frost + dH`; `H_frost` is the vapour's enthalpy at the onset):

| dH, J | T, K | Solid, mol |
|---|---|---|
| -3000 | 169.058867 | 0.0898430641 |
| -2000 | 181.129860 | 0.0656909857 |
| -1000 | 188.080683 | 0.0344647543 |
| -500 | 190.615674 | 0.0175188956 |
| -250 | 191.727716 | 0.0088214403 |
| 0 | 192.754773 | 0 (exhausted) |
| +250 | 200.787938 | 0 |
| +1000 | 225.052813 | 0 |

T(H) increases and the solid decreases monotonically (17 points tested). PH at `H_frost` returns 192.754773274 K,
the onset to 1e-9 K. The latent effect shows in the slope: 250 J raises the vapour-solid mixture by about 1 K below the
frost point, and the vapour by 8 K above it.

Pure CO2 at 0.1 MPa (the sublimation step from the balance):
- T_sub is 194.466712376 K, equal to the pure path to 6e-13.
- The sublimation enthalpy `h_V - h_s` there is 25,200.6 J/mol, +0.003 % of 25.2 kJ/mol (D16 declares 0.25 %).
- PH at vapour fractions 0.001, 0.25, 0.5, 0.75 and 0.999 recovers the fraction to 1e-9 and H to 1e-9 relative, in 2
  to 5 TP calls.
- UV at the same (U, V) recovers T to 1e-9, P to 1e-8 and the fraction to 1e-8, in 35 to 46 TP calls.
- At 1 MPa, between the solid at 216 K and the liquid at 225 K, PH returns the melting hold. At 1 MPa across the
  liquid's boiling (233.26 K), PH answers the ordinary pure vapour-liquid coexistence.

### 3.4 (d) Partial-pressure approximation against the fugacity onset

These are the frost points (`depositionTemperature`) at the (y, P) of the grid of (b); approximation minus onset,
in K:

| y_CO2 | 0.1 MPa | 0.5 MPa | 1 MPa | 2 MPa | 5 MPa |
|---|---|---|---|---|---|
| 0.001 | +0.190 (135.881) | +0.960 (145.217) | +1.958 (149.073) | +4.094 (152.140) | +12.436 (151.286) |
| 0.01 | +0.186 (150.845) | +0.930 (162.792) | +1.877 (168.030) | +3.838 (172.765) | +10.339 (176.003) |
| 0.05 | +0.179 (163.543) | +0.889 (177.987) | +1.787 (184.555) | +3.625 (190.842) | +9.492 (196.821) |
| 0.1 | +0.171 (169.735) | +0.852 (185.490) | +1.712 (192.755) | +3.472 (199.836) | +9.096 (207.033) |
| 0.2 | +0.157 (176.445) | +0.781 (193.686) | +1.572 (201.737) | +3.201 (209.691) | hold |
| 0.3 | +0.142 (180.638) | +0.707 (198.843) | +1.426 (207.400) | n/a (215.900) | hold |
| 0.5 | +0.108 (186.234) | +0.540 (205.774) | +1.097 (215.031) | hold | hold |

The fugacity onset is in parentheses. Thirty onsets, mean |difference| 2.59 K, worst 12.44 K.

- **Sign.** The approximation always places the frost point too warm. The error grows about linearly with pressure: the
  CO2 fugacity coefficient in dense N2 falls well below one, and at 5 MPa the fugacity frost temperature of 0.1 % CO2
  is even lower than at 2 MPa.
- **n/a.** Where `y P` exceeds the triple pressure the pure species has no sublimation point, so there is no
  approximation.
- **Holds.** The four holds have their onset where the fluid is vapour-liquid (a CO2-rich liquid condenses first).

### 3.5 (e) Jäger and Span 2012 Figure 13

`100 (p - p_ideal)/p` with `p` the fugacity frost pressure (`depositionPressure`) and `p_ideal = p_sub,pure(T)/x_CO2`
on the same family:

| T, K | x_CO2 0.999 | 0.99 | 0.95 | p (x = 0.95), kPa |
|---|---|---|---|---|
| 150 | +0.00003 | +0.00033 | +0.00168 | 0.912 |
| 160 | +0.00010 | +0.00103 | +0.00531 | 3.376 |
| 170 | +0.00028 | +0.00281 | +0.01449 | 10.634 |
| 180 | +0.00067 | +0.00679 | +0.03497 | 29.331 |
| 190 | +0.00148 | +0.01487 | +0.07665 | 72.492 |
| 200 | +0.00301 | +0.03030 | +0.15617 | 163.781 |
| 205 | +0.00421 | +0.04240 | +0.21860 | 239.397 |
| 210 | +0.00584 | +0.05884 | +0.30347 | 344.504 |
| 214 | +0.00757 | +0.07623 | +0.39331 | 456.525 |
| 216 | +0.00862 | +0.08676 | +0.44772 | 524.083 |

The figure uses Span-Wagner CO2, Span et al. N2 and GERG-2004 mixing (page 596). Its deviations are positive, near
zero below 150 K, rising steeply toward the triple point, and ordered by N2 content. By eye they reach about 0.35 %
(5 % N2), 0.06 % (1 %) and 0.01 % (0.1 %) at the right edge.

The pilot family (PR78, E-PPR78 kij) has the same sign, the same monotonic growth with T and with N2 (asserted), and
the same order of magnitude (asserted below 1 %). It sits about 25 to 45 % above the figure at the triple point. The
figure's values are read off the plot, so this is a qualitative match.

### 3.6 (f) Refusals

| Case | Answer |
|---|---|
| 5 % CO2 in liquid methane, 130 K, 1 MPa (crystal forms, the reduced fluid is liquid) | `UNSUPPORTED`, `NOT_IMPLEMENTED`, `SOLID_LIQUID: the fluid left by the deposition ... is SINGLE_LIQUID`, no phases, kernel counts carried |
| 0.1 % CO2 in liquid methane, 130 K, 1 MPa (no crystal, a liquid below CO2's fluid range) | Hold (`a liquid carries a crystal's species below its fluid range`) |
| Pure CO2, 217 K, 8 MPa (liquid above the melting line) | Hold (melting) |
| Methane without CO2, 130 K, 1 MPa | `SINGLE_LIQUID`, solids assessed (no crystal's species carried) |
| Crystal request on `tjl20_methane_nitrogen` (no crystals), and on the pilot with its crystal record graded research-only | `PHASE_COMPETITION_NOT_QUALIFIED` |
| A crystal the package lacks (named) | `PHASE_COMPETITION_NOT_QUALIFIED` |
| 85 K, 310 K (CO2 carried), 12 MPa | `OUT_OF_DOMAIN`: `CarbonDioxide ... valid range 90..300 K`; `Nitrogen at 12000000 Pa above 100..10000000 Pa` |
| Onset at 12 MPa; frost point below 90 K (1e-9 CO2 at 0.1 MPa); frost pressure at 305 K; at 250 K with 50 % CO2 (above 10 MPa) | `OUT_OF_DOMAIN` (the last one `THERMO_DOMAIN_PRESSURE_ABOVE`) |
| Water with the crystal competition (TP and onset) | `WATER_CHEMISTRY_NOT_MODELLED`; fluid-only with water on this service stays `NOT_IMPLEMENTED` (no water model) |
| Onset with no crystal species carried | `IllegalArgumentException` (a malformed question) |

A reused workspace gives crystal answers bit for bit equal to fresh ones, in either order.

## 4. Holdouts (step 6 g): CO2 frost points in methane-rich gas (G4F1)

Data: `src/test/resources/science/thermo/solid-co2-holdouts/`. These are the NIST TRC ThermoML rows with their citation
headers; the research copies are in `sources/solid-co2-holdouts/`.

- **Le and Trebble 2007:** all 103 rows. In sets 2 and 3 the column labelled C2H6 or N2 holds the CH4 fraction, as the
  survey found; the third component is `1 - x_CO2 - value`.
- **Zhang 2011:** 17 rows.
- **Xiong 2015:** the 194 unique points (the binary once).
- **Souza 2020:** the 7 three-phase rows only.
- **Fandiño 2015:** the 4 rows.

Each point is scored by `depositionTemperature` at the measured pressure and composition against the measured
temperature. Xiong's are frost pressures at a given T and are scored the same way. A hold is counted apart.

### 4.1 Scores

| Dataset | Points scored | Holds | MAD, K | Bias, K | Max, K | Partial-pressure MAD, K | Its max, K |
|---|---|---|---|---|---|---|---|
| Le and Trebble 2007 set 1, CH4 + CO2 | 55 | 0 | 2.312 | -1.080 | 5.193 | 3.159 | 6.033 |
| Zhang 2011, CH4 + CO2 | 17 | 0 | 0.667 | +0.170 | 1.255 | 2.083 | 10.236 |
| Xiong 2015 set 3, CH4 + CO2 | 63 | 1 | 0.703 | +0.121 | 3.516 | 2.287 | 8.244 |
| **Binary pooled** | **135** | **1** | **1.354** | **-0.362** | **5.193** | 2.621 | 10.236 |
| Le and Trebble set 2, + 1 to 2 % C2H6 | 24 | 0 | 1.730 | -0.296 | 3.809 | 3.488 | 5.170 |
| Le and Trebble set 3, + 1 to 2 % N2 | 24 | 0 | 1.662 | -0.356 | 3.692 | 3.447 | 4.804 |
| Xiong set 1, + N2 up to 5.7 % | 77 | 0 | 0.955 | -0.373 | 5.444 | 1.860 | 5.429 |
| Xiong set 2, + C2H6 up to 5.9 % | 49 | 4 | 0.935 | +0.865 | 2.523 | 2.573 | 4.542 |

- **Reference values.** The P0 target is 1.5 K. The data's own uncertainty is TRC U95 1.2 to 1.6 K for Le and Trebble,
  0.1 to 0.9 K for Zhang, and 13 to 974 kPa for Xiong.
- **Test bounds.** They are set at what was measured, with a margin:
  - the pooled binary MAD is held to the P0 target itself (1.5 K; measured 1.354);
  - per binary dataset: MAD 2.5 K and per-point maximum 5.5 K;
  - per ternary dataset: MAD 2.0 K and maximum 6.0 K;
  - holds: at most 1 binary and 4 ternary points, as measured.
- **Per-point target.** Not met: the worst point is 5.19 K.
- **Frost pressures.** Xiong's frost pressures from `depositionPressure` at the measured T have a mean |dP/P| of 8.8 %
  (binary), 10.3 % (N2) and 9.8 % (C2H6); the partial-pressure approximation gives 19.2, 16.0 and 20.6 %. Fourteen
  Xiong points have no vapour-solid onset: the gas condenses on this family before the crystal forms, so they return the
  typed hold.

### 4.2 Reading

- **Le and Trebble 2007 against the others.**
  - At x_CO2 = 0.01, their frost points lie 1.1 to 5.2 K above the fugacity model. The deviation grows with pressure
    (to 2.46 MPa, methane near its dew point), yet the partial-pressure approximation matches them to within 1 K.
  - At 0.0191 and 0.0293 the deviations are +3.5 to -2.4 K, with a trend in pressure.
  - Xiong's binary points at the same conditions agree with the model within 0.7 K on average. The two sets are about
    1 K apart at 1 MPa (Xiong: 0.0108 at 953 kPa and 168.15 K; Le and Trebble: 0.01 at 962 kPa and 168.6 K) and 3 to
    5 K apart at 2 to 2.5 MPa. At 178.15 K Xiong measures 2.18 MPa for 1.61 % CO2 and 1.98 MPa for 1.54 %, a CO2 partial
    pressure of 31 to 35 kPa. Le and Trebble's 1 % frost point at 177.8 K is 2.46 MPa, a partial pressure of 24.6 kPa.
    A higher total pressure with a lower partial pressure runs against the fugacity trend (the CO2 fugacity coefficient
    falls with pressure).
  - The Le and Trebble 1 % set is therefore inconsistent with Xiong's in the direction of ideal behaviour. The survey
    noted Riva's per-dataset spread for these sets.
  - The pooled score is dominated by that set. Without it, the binary MAD is 0.69 K over 80 points.
- **Holds.** The model's dew point lies above its frost point at 5 of the 314 frost points (4 Xiong + C2H6 near the dew
  line at 168 to 188 K; 1 binary at 153.15 K and 0.1 % CO2, 93 % of methane's saturation pressure). There a liquid
  appears first on this family. These are P5 cases, not failures.
- **Souza 2020 three-phase line.** It runs from 203.96 K and 4858 kPa to 215.79 K and 964 kPa. All Zhang points lie
  below it (on the vapour side: at 205.3 K, 4446 kPa is below about 4790 kPa; at 210.3 K, 2943 kPa is below about
  3815 kPa). None of them was held.
- **Fandiño 2015 (CO2 + N2 three-phase line, u(T) 0.1 K).**
  - At the four (T, P) points, TP with x_CO2 0.99, 0.9 and 0.5 never returns vapour-solid. It returns the hold 9 times;
    at 13.018 MPa the answer is `OUT_OF_DOMAIN` (above 10 MPa).
  - The frost-point searches at those pressures end in the hold, at the family's own three-phase temperature: 214.819 K
    at 4.821 MPa and 214.818 K at 4.823 MPa (x_CO2 0.9 and 0.5 alike), and 213.844 K at 7.842 MPa for 0.5 (at 0.9 the
    fluid there is one dense phase).
  - Measured: 214.87, 214.86 and 213.90 K, so the family is 0.04 to 0.06 K low.
  - The driving force `D` at the measured points is -0.0012, about 0.05 K at the fusion-enthalpy slope.
  - This is P5 evidence, not a G4 claim: the three-phase line of this family matches Fandiño within the data's
    uncertainty.

### 4.3 What G4 can claim now

- **Onset.** Qualified at the declared-error grade on the open vapour-solid holdouts in methane-rich gas: binary MAD
  1.35 K, ternaries with up to 6 % N2 or C2H6 at most 1.73 K. The N2-rich frost points (Sonntag and Van Wylen, Smith et
  al.) remain unavailable (owner request).
- **Deposition in N2.** Consistent by construction and by the pure limit, the conservation checks and Figure 13, but
  not against CO2 + N2 frost data.
- **Finite inventory, heat and exhaustion.** Checked by conservation, reversibility and the sublimation enthalpy. No
  dataset measures them (survey).

## 5. Cost

`GasSolidCostProbe` (tool folder), JDK 21.0.11, 16 processors, 20 GB free, no game or dev client.

- A stray `sed` from another session held one core during both runs.
- Cold is the median of 21 first calls, each on a fresh service and workspace in a JVM where the path had already run.
  The first row also pays its own JIT.
- Warm is 2000 warm-up and 2000 timed calls on one workspace.

| Request | Answer | Cold, µs | Warm mean, µs | Warm p95, µs | Fluid equilibria / TP calls |
|---|---|---|---|---|---|
| TP 10 % CO2 in N2, 160 K, 1 MPa (fluid-only answer vapour-liquid) | VAPOR_SOLID | 345.8 | 34.41 | 50.20 | 2 |
| TP 1 % CO2 in N2, 150 K, 0.1 MPa (from one vapour) | VAPOR_SOLID | 47.5 | 17.88 | 22.80 | 2 |
| TP 10 % CO2 in N2, 200 K, 1 MPa | SINGLE_FLUID, no solid | 23.8 | 6.94 | 6.70 | 1 |
| TP pure CO2, 180 K, 0.1 MPa | SOLID_PRESENT (complete) | 16.0 | 2.31 | 2.30 | 1 |
| TP 10 % CO2 in N2, 250 K, 1 MPa, crystal competition | SINGLE_FLUID | 16.0 | 4.61 | 4.90 | 1 |
| The same, fluid-only (baseline) | SINGLE_FLUID | 15.0 | 2.68 | 2.70 | 1 |
| PH at the 160 K deposit | VAPOR_SOLID | 426.2 | 212.91 | 232.30 | 10 |
| UV at the 160 K deposit | VAPOR_SOLID | 523.5 | 417.91 | 522.30 | 19 |
| `depositionTemperature` 10 % CO2 in N2, 1 MPa | 192.7548 K | 44.7 | 32.53 | 42.30 | 4 |
| `depositionTemperature` 1 % CO2 in N2, 0.1 MPa | 150.8446 K | 35.3 | 32.91 | 43.10 | 4 |
| `depositionPressure` 10 % CO2 in N2, 180 K | 292,088 Pa | 40.3 | 27.88 | 28.80 | 4 |
| `depositionPressure` 1 % CO2 in CH4, 170 K | 1,384,458 Pa | 42.8 | 32.90 | 33.20 | 6 |

- Service construction (`forPackage`): median 1.03 ms, mostly the catalog-to-evaluator path.
- The anchored view (g0, g1): 2.6 µs, once per service.
- The anchored crystal's chemical potential: 72 ns per state.
- Before the per-competition coverage cache (`cost-probe-2026-09-25-before-coverage-cache.txt`) the rows were 41, 23,
  7.5, 2.8 and 4.9 µs.
- The remaining no-solid overhead, 1.9 µs at 250 K, is the re-labelled second result and its detail string. A pre-check
  that skips the crystal above its triple point at pressures below its melting line is a stage 2b option.

## 6. What stage 2b (network coupling) and P5 need

**Stage 2b: the network**

- **Contract and requests.** Pilot nodes request `FLUID_AND_CRYSTALS` (or the named set) where the package contract
  qualifies it. The network's own domain check (`FluidDomain` in `FluidThermodynamics`) must use the crystal
  competition's domain, where CO2 is admitted to 90 K; the fluid-only path stays at 216.592 K.
- **Inventory.** An immobile crystal inventory per node: moles per crystal, which never flows. It enters the node's
  conserved totals, and the engine's `Deposition` record is its source. Deposition and sublimation become engine events
  on the engine's schedule (AGENTS.md).
- **Checkpoint format.** Add the crystal amounts and round-trip them. This is a breaking format change: fresh world, no
  migration (standing rule).
- **Energy closure.** The node's energy includes `n_s h_s`. The network is on its sensible datum (D10), while the
  engine's enthalpies carry the spine's formation offsets. The solid's enthalpy must be moved to the network datum with
  the same per-species offset the fluid uses (`EnergyReference`), and the latent heat checked across the move.
- **Volume closure.** The solid occupies `n_s v_s` of the node's volume. The UV of a node with solid is the engine's UV
  (tested here). The network's own inventory refresh (`InventoryEquilibrium`) needs the solid term and a warm start:
  UV at a deposit costs 16 to 30 TP calls cold.
- **Holds.** `EquilibriumResult.SOLID_LIQUID` maps to a new `UnsupportedPhases.Kind.SOLID_LIQUID` (`science.fluid`, not
  edited here). `PURE_COEXISTENCE_UNDERDETERMINED` with a crystal state needs the same keep-the-previous-split
  treatment as the fluid coexistence.
- **Presentation.** The GUI shows solid amounts, through engine-owned buckets.
- **Cost.** A deposit is 18 to 34 µs per TP call. Mixture UV is about 0.4 ms from a cold start, and pure CO2 UV across
  the sublimation step 35 to 46 TP calls: warm starts are the item.

**P5: solid-liquid competition**

- Every hold of this stage is a P5 case:
  - a crystal beside a liquid: the SLV of CO2 + N2 near 213 to 215 K (Fandiño, reproduced within 0.06 K by the hold
    onsets) and CO2 + CH4 (Souza);
  - CO2 in liquid methane (the liquidus of Shen, Campestrini, Riva and Stringari);
  - the melting step of pure CO2 in PH and UV;
  - dense liquid-like N2 at 130 K and 5 MPa;
  - two crystals.
- The rule that a liquid carrying CO2 below 216.592 K is a hold (even without a crystal) is lifted only when the
  liquidus is qualified.
- The 14 Xiong points where the gas condenses before frosting are P5 test states.
- The fusion enthalpy (9019 J/mol, D16) enters the liquidus directly.

## 7. Tests, gates, pins

| Class | Tests | Checks |
|---|---|---|
| `science.thermo.phase.GasSolidEquilibriumTest` (new) | 11 | contract as a whole set; fluid-only unchanged bit for bit; (a) pure limit; (b) conservation and equal mu at 108 deposits + 5 complete; (d) approximation table; (e) Figure 13; (c) PH/UV round trips, reversibility, exhaustion; pure CO2 sublimation step; crystal derivatives and anchored view; (f) refusals; bitwise repeatability |
| `science.thermo.qualification.G4F1CarbonDioxideFrostPointHoldoutTest` (new) | 4 | (g) binary and ternary frost points, Xiong frost pressures, Fandiño three-phase points |
| Resources `src/test/resources/science/thermo/solid-co2-holdouts/` (new) | | 5 TSV files with citation headers |

Unchanged and green:
- the P2/P3 engine tests (`FluidTpEquilibriumTest`, `FluidTpEquilibriumPhUvTest`, `FluidTpEquilibriumP3Test`);
- `CrystalPhaseEvaluatorTest`, `SolidPhaseEvaluatorTest`, `SolidCarbonDioxideQualificationTest`;
- the G3 families.

Gradle runs, 2026-09-25, Git Bash in the worktree, one invocation at a time under `build/gradle.lock` (holder `p4b`),
no dev client, `JAVA_OPTS=-Xshare:off`, `--offline`, on the tree of `8144935`. Logs are in the main checkout's
`research/2026-09-24-coolprop-low-temperature/p4-gas-solid/`.

| Gate | Command | Result |
|---|---|---|
| 1 | `./gradlew test --tests "com.wormzjl.createcheme.science.thermo.*" --tests "com.wormzjl.createcheme.science.material.*" --offline` | BUILD SUCCESSFUL, 45 classes, 232 tests, 0 failures (43/217 before; +15) |
| 2 | `./gradlew test --offline` | BUILD SUCCESSFUL in 3 min 28 s, 255 classes, 1,256 tests, 0 failures (253/1,241 before) |
| 3 | `./gradlew fluidScienceTest --offline` | BUILD SUCCESSFUL, 58 classes, 219 tests, 0 failures (219 before) |

Pins: no record changed.
- The eight bundled package pins (`bundledPackageFingerprintsAreUnchanged`) are green.
- So are the pilot spine fingerprint `581c9098...`, the scientific revision and the physics fingerprint of
  `PilotCryogenicCatalogTest`.
- The network and column tests of the full suite are green. The network requests fluid-only competition, and its
  answers are unchanged bit for bit (the fluid-only comparison test; no network code touched).

## 8. Tool and research folders

- Tool: `tools/gas-solid-equilibrium-scans/` (README).
  - `run.sh`: javac of the phase package and the tests against the last Gradle compile.
  - `RunTests.java`.
  - `src/GasSolidScan.java`: exploratory scan.
  - `src/GasSolidCostProbe.java`: the cost figures of section 5, as a plain main.

  Nothing of it was ever in a tracked path, so there is no reattach patch.
- Research: `research/2026-09-24-coolprop-low-temperature/p4-gas-solid/` (README):
  - the three gate logs;
  - `gradle-gate-output-2026-09-25.txt`, the printed output of the two gate classes, per point;
  - the two cost probe runs.

## 9. Proposed decision (the lead numbers it D17)

**D17 (2026-09-25): gas-solid equilibrium in the engine, the crystal competition's domain and its hold.**

- **Question.** How does the engine answer a request with the pilot's CO2-I crystal competing, where does that
  competition apply, and what is refused?
- **Evidence.**
  - `P4_GAS_SOLID_EQUILIBRIUM.md`: 108 vapour-solid states with conservation 1.7e-16 and equal mu to 8.7e-10.
  - 92 of them have a fluid-only answer holding a subcooled CO2-rich liquid that the crystal makes metastable.
  - Round trips to 1e-11; the pure limit to 1.6e-5 K; Figure 13 of Jäger and Span reproduced in sign and trend.
  - Methane-gas frost points: binary MAD 1.35 K, ternaries at most 1.73 K.
  - The Fandiño three-phase points are never answered as vapour-solid (the hold onsets are 0.05 K from them).
- **Chosen (standing instruction; reversible at G4/G5).**
  1. The crystal competition is qualified per package as the whole set of its usable, answer-graded crystals
     (`PhaseContract.forPackage`); `FLUID_AND_CRYSTALS` requests it. The network keeps fluid-only until stage 2b.
  2. Under that competition the crystal's species is admitted below its fluid range, down to the crystal's and its
     spine's range (CO2: 90 to 300 K, with the package's 10 MPa). Fluid-only requests keep the fluid range.
  3. A crystal forms when `mu_s < mu_i` of the fluid answer (fugacity). The vapour-solid answer is decided by the fluid
     the deposition leaves: the reduced feed is re-flashed with the stability test and must be one stable phase that is
     not liquid-like. A liquid in the fluid-only answer of the feed is not by itself a refusal: below the triple point it
     is a metastable subcooled liquid of the crystal's species.
  4. Everything that needs a crystal together with a liquid is the typed `SOLID_LIQUID` hold (`UNSUPPORTED`,
     `NOT_IMPLEMENTED`, P5). This covers:
     - a reduced fluid that still holds a liquid, or is one liquid-like phase;
     - the pure species' melting;
     - an onset whose fluid holds a liquid;
     - a liquid carrying the crystal's species below that species' fluid range, even without a crystal;
     - two crystals.
     The network maps it to `UnsupportedPhases.Kind.SOLID_LIQUID` in stage 2b.
  5. The answer's grade folds the crystal's (`estimated_declared_error`, D16's declared errors in the evidence).
  6. The G4 onset gate is the pooled binary MAD against the P0 target (1.5 K) with per-dataset MAD and per-point caps
     at what was measured (2.5 K and 5.5 K binary; 2.0 K and 6.0 K ternary). This follows the survey's "average with a
     per-point cap".
  7. Complete deposition is declared below a vapour mole fraction of 1e-15, which is unreachable in the pilot domain;
     a pure feed deposits completely.
- **Rejected.**
  - Refusing whenever the fluid-only answer holds a liquid: it holds 85 % of the deposition states, all on a
    metastable liquid.
  - The partial-pressure onset as an answer: it is 2 to 12 K warm at 1 to 5 MPa.
  - A per-point 1.5 K frost-point bound: Le and Trebble's 1 % set is 1 to 5 K off and inconsistent with Xiong's.
- **Affected milestones.** P4 stage 2b (network), G4, P5 (every hold), G5.
- **Coverage changes.** The pilot answers CO2 deposition from N2, CH4 and C2H6 gas and the pure CO2 sublimation (TP,
  PH, UV, onsets) at the declared-error grade. CO2 + N2 frost points are not qualified against data (none open).
  Nothing changes for the network or any other package.

## 10. Known limits and open items

- **Mixture UV.** 16 to 30 TP calls from a cold start (mean 16.3), and pure CO2 across its sublimation step 35 to 46,
  against PH's 9. A warm start (the P3 open item) matters more with a solid.
- **Crystal-competition overhead.** 1.9 µs on a 2.7 µs fluid-only TP where no crystal can form; a cheap pre-check is
  possible.
- **Near-critical carrier.** Dense N2 at 130 K and 5 MPa is labelled liquid-like, so solid formation there is held (7
  grid states). The label comes from the phase identification parameter; near N2's critical point that is research
  grade anyway.
- **Onset search.** It brackets locally from the partial-pressure estimate. For vapour mixtures `D(T)` is monotone; with
  liquids the result is a hold anyway. A frost point with two roots in the vapour region was not seen.
- **Data.** The CO2 + N2 frost-point data (Sonntag and Van Wylen 1962; Smith et al. 1963/1964) and the Maltby 2025
  review stay owner requests; the G4 N2 case is not data-qualified.
- **Multiple crystals.** Only one crystal per competition was exercised (the pilot has one); two forming crystals are
  held.
- **Attribution.** The commits carry the session's model line (Claude Opus 5.5), as AGENTS.md asks and as in P4 stage 1
  and P3 WP11, not the brief's Fable 5.1 line.

## 11. Commits

| Commit | Content |
|---|---|
| `5f2bd7d` | `PhaseCompetition`, `PhaseContract`, `PhaseDomain`, `EquilibriumResult`, `FluidTpEquilibrium`, `SolidPhaseEvaluator`, `CrystalPhaseEvaluator` (the inherited anchored view and derivatives, finished), `DepositionOnset` (new); `GasSolidEquilibriumTest` (new) |
| `8144935` | `G4F1CarbonDioxideFrostPointHoldoutTest` and the holdout resources (new); `FluidTpEquilibrium`: the onset hold below the triple point, the per-competition coverage cache, the class comment |

`CHANGELOG.md`, `DECISION_LOG.md`, the unified plan and the INDEX files are not edited here. The proposed
`[Unreleased]` line and the INDEX rows are in the stage report to the lead.

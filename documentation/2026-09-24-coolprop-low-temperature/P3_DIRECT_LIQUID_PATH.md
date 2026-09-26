# P3 WP4: direct liquid path

Status: Implemented on `claude/coolprop-multiphase-thermo-37f6b0` at `959ea0d` (not merged), 2026-09-24. Batch `2026-09-24-coolprop-low-temperature`, plan [P3_PILOT_ENGINE_PLAN.md](P3_PILOT_ENGINE_PLAN.md) section 2 (all), section 10 (risks), WP4 row of section 9; decisions D9 and D12 of [DECISION_LOG.md](DECISION_LOG.md); evidence [P1_ALPHA_AND_LIQUID_PATH_STUDY.md](P1_ALPHA_AND_LIQUID_PATH_STUDY.md) section 2.
Base: `5100233` plus the parallel WP1, WP2/WP3, WP6a, WP8/WP10 commits that landed while WP4 ran (`6ec5c2c` to `f5ffcf8`); WP4 touches none of their files.
Measurement artifacts: `research/2026-09-24-coolprop-low-temperature/p3-direct-liquid-path/` (chain-100 per-node deviation CSV, regression counter reports before and after).

**Fresh world required.** The checkpoint format moves from 4 to 5 (island unit format 1 to 2): the global liquid compressibility left the package key, the package table and the island unit, and the thermodynamic revision of every network package moved (`fluid-shared-k-v1 ... k=0x1.12e0be826d695p-30` became `direct-liquid-v1`). A world saved before WP4 is refused at load with the existing "Create a fresh world for this development build" message, as any other format mismatch; there is no migration and no legacy test (AGENTS.md). The `liquidCompressibility` entry is gone from the server config; an old config file that still carries it is simply not read for it.

## 1. What changed

### 1.1 Files and removed symbols

| File | Change |
|---|---|
| `science/fluid/thermo/HydrocarbonModel.java` | Constructor `(catalog, packageId)`. Liquid = `TranslatedPengRobinson.evaluateValues(t, P_state, x, LIQUID, terms)` copied into `Phase` (one record, one `ln phi` clone). Anchors: `c_i = v_target - v_PR,L(T_anchor, P_anchor)` at the anchor's own pressure, with a construction-time refusal if the cubic has no liquid root there. Liquid-root rule (section 2). Revision tag `direct-liquid-v1`, no `k=`. `Phase` gains `liquidRootAbsent` and `isothermalCompressibility()`. New `LiquidRootAbsent` (an `IllegalArgumentException`, not a `ThermoDomainViolation`). **Removed:** `REFERENCE_PRESSURE`, the `liquidResponse` field, the `(catalog, packageId, compressibility)` constructor. |
| `science/fluid/thermo/GlobalLiquidResponse.java` and its test | **Deleted.** |
| `science/fluid/thermo/WaterRegion1.java` | Caller-owned `Workspace` (power tables kept per temperature, pressure terms per call, no allocation); `evaluateLiquid(scope, water, T, P, psat, workspace)` admits the metastable liquid below saturation within the declared bound (section 3), flagged; `requireTemperature`; `METASTABLE_MARGIN_PASCAL = 2 MPa`. The stable-liquid `evaluate(...)` entries keep their contract (refuse `P < psat`). |
| `science/fluid/thermo/FluidThermodynamics.java` | Water at the state pressure through the prepared temperature's Region 1 workspace; `Prepared.referenceWater` replaced by `Prepared.water()`; `pumpReferenceDensity` and the water enthalpy datum from Region 1 at 298.15 K and 101,325 Pa directly (997.05 kg/m3); acoustic bound `sum_phases V_phase kappa_phase` with the liquid's EOS compressibility and Region 1's; `State` gains `waterCompressibility`; `WaterLiquid` gains `volumePressureDerivative` and `metastable`; the Wilson flash returns the vapour alone when its converged liquid trial has no liquid root. **Removed:** the `liquidResponse` field, `waterLiquidRaw`, every constructor and `forNetwork` overload with the compressibility except two `@Deprecated` 3-argument shims (section 10, item 6). |
| `CreateChemE.java` | **Removed:** `FLUID_LIQUID_COMPRESSIBILITY`, config key `liquidCompressibility`, `FluidOptions.compressibility`. |
| `runtime/fluid/FluidCheckpointCodec.java` | `VERSION` 5, `UNIT_FORMAT` 2, manifest text `createcheme-fluid-checkpoint-5`. **Removed:** `IslandEntry.compressibility`, `PackageKey.compressibility`, `PackageRecord.compressibility`, the `Compressibility` package field and unit `f64`, `requireDouble`. `IslandEntry.key()` added. |
| `runtime/fluid/FluidWorldAuthority.java`, `FluidPropertyReloadGuard.java`, `FluidCheckpointStore.java` | The compressibility is no longer part of the model key, the reload guard or the saved package. Format comments in `FluidSavedData`, `FluidUnitIO` updated. |
| Solver files | None needed: `PassiveStepSolver` reads the compressibility only through `model.velocityLimit` and `pumpReferenceDensity` (section 4). |
| Tests | 54 existing test files and 7 GameTest files changed (mostly the dropped argument); new: `DirectLiquidContinuityTest`, `DirectLiquidDeterminismTest`, `DirectLiquidJacobianRowsTest`, `DirectLiquidChainDeviationProbe` (env-gated measurement probe, detach at WP11), `fluid/support/LegacyLiquidPath` (the retired path rebuilt for the comparisons); rewritten `HydrocarbonModelTest`, `HybridDerivativeTest` (water identities), extended `WaterRegion1Test`; updated pins in section 8. |

### 1.2 Where every liquid is now evaluated at the state pressure

`HydrocarbonModel.phase` / `differentiate`, `FluidThermodynamics.state` (so `adoptingState`, every Newton trial and every Jacobian column), `flashTP` / `splitHydrocarbon`, `initialNitrogenCharge` (through `flashTP`), the pump reference density, the acoustic bound, and liquid water everywhere. The `TranslatedPengRobinson.Derivatives` bundle now describes exactly the liquid the network evaluates; its Javadoc still carries the retired "must chain `GlobalLiquidResponse`" note (WP1's file, open item 8).

## 2. The liquid-root-absent rule

At the state pressure the cubic can have one physical root. In `HydrocarbonModel.phase(..., LIQUID, ..., vaporCoexists)`:

- one physical root and the phase identification parameter (Venkatarathnam and Oellrich 2011) at most one means the liquid root is absent (`Phase.liquidRootAbsent`);
- if the node also carries a hydrocarbon vapour (`FluidThermodynamics.state` passes `nv > 0`), the evaluation throws `HydrocarbonModel.LiquidRootAbsent` ("Liquid root absent: ..."): a two-phase trial collapsing onto the trivial solution. It is an `IllegalArgumentException`, so the line search and the Jacobian sweep refuse the trial as they refuse any unevaluable state; it is deliberately not a `ThermoDomainViolation` (the state is inside every declared range);
- in a liquid-only node the single root is the same fluid continuing and is accepted (flagged, never relabelled as a vapour);
- the Wilson flash, whose liquid trial is only a trial composition, returns the vapour alone when its converged split's liquid trial has no liquid root (the counterpart of its existing "vapour not on the vapour branch -> all liquid" rule), so `flashTP` never offers a split the state would refuse (pure N2 at 298 K and 1 atm: all vapour, tested).

**PIP convention.** `PIP > 1` is liquid-like, `PIP < 1` vapour-like (an ideal gas is exactly 1; a dilute real gas below twice its Boyle temperature is below 1; a dense liquid well above). The plan's parenthesis in section 2.2/6.3 "(> 1 vapour-like)" is reversed; the WP6a engine agent reached the same conclusion independently (`science/thermo/phase/PhaseIdentification`). WP4 computes the parameter inside `HydrocarbonModel` from the root's own `Z`, `dv/dP`, `dv/dT` and the mixture co-volume, because `TranslatedPengRobinson.Values` exposes no `a`, `da/dT`; WP7 should make `PhaseIdentification` the single implementation. Known limit: for a hot dilute supercritical gas (N2 above about 900 K) PR's PIP exceeds one, so a single root there reads liquid-like and the rule does not fire; the outer phase check (WP7) covers that corner.

## 3. Liquid water through IF97 Region 1 at the state pressure (D12)

**Citation.** IAPWS R7-97(2012), *Revised Release on the IAPWS Industrial Formulation 1997 for the Thermodynamic Properties of Water and Steam*, section 5.1 (range of validity of Eq. (7), region 1: `273.15 K <= T <= 623.15 K, ps(T) <= p <= 100 MPa`): "In addition to the properties in the stable single-phase liquid region, Eq. (7) also yields reasonable values in the metastable superheated-liquid region close to the saturated liquid line." (`research/2026-09-24-coolprop-low-temperature/sources/iapws/IF97-Rev.pdf`.) The release also states in its introduction that the basic equations for regions 1 and 3 "yield reasonable values for the metastable states close to the stable regions".

**Declared bound.** The release supports the metastable liquid only qualitatively ("close to"), with no pressure limit, so the admission carries a declared bound: liquid water is evaluated down to `max(envelope minimum, psat(T) - 2 MPa)` and flagged `metastable` below `psat(T)`; below the bound (and above 100 MPa, or outside the Region 1 temperature range) the evaluation is a `ThermoDomainViolation` naming water, as a pressure below saturation was on the stable path. 2 MPa is the span the retired path covered: it evaluated Region 1 at 2 MPa for every state and so admitted every envelope pressure wherever `psat(T) <= 2 MPa` (T up to 485.5 K). The bound admits exactly those states there (so no state the old path accepted is newly refused) and keeps the same distance from the saturation line above it. `psat` is the network's own (the water model's `V3WaterProperties`, the free-water rule's).

**Against IAPWS-95** (the Helmholtz oracle, `WaterRegion1Test.theMetastableLiquidIsAdmittedWithinTheDeclaredBoundAndMatchesIapws95`):

| T [K] | p [Pa] | psat [Pa] (network / IAPWS-95) | state | rho | dv/dP |
|---|---|---|---|---|---|
| 300 | 100,000 | 3,537 / 3,537 | stable | +0.0001 % | -0.215 % |
| 350 | 10,000 | 41,683 / 41,682 | metastable | +0.0014 % | -0.319 % |
| 350 | 100 | 41,683 / 41,682 | metastable | +0.0014 % | -0.319 % |
| 400 | 50,000 | 245,765 / 245,769 | metastable | -0.0002 % | -0.104 % |
| 450 | 300,000 | 932,203 / 932,204 | metastable | +0.0006 % | +0.026 % |
| 480 | 100 | 1,790,487 / 1,790,472 | metastable | +0.0009 % | +0.109 % |
| 480 | 1,000,000 | 1,790,487 / 1,790,472 | metastable | +0.0010 % | +0.094 % |
| 520 | 2,000,000 | 3,768,981 / 3,768,951 | metastable (bound 1.77 MPa) | -0.0005 % | +0.054 % |
| 560 | 6,000,000 | 7,106,232 / 7,106,250 | metastable (bound 5.11 MPa) | +0.0006 % | -0.114 % |

The admitted metastable liquid is as good as the stable one (density within 0.0015 %, `dv/dP` within 0.32 %). Refusal checked at 520 K / 1.5 MPa (below the 1.77 MPa bound), naming water and the bound. Water below 273.16 K stays out of domain (D8).

**Cost.** Region 1 is now evaluated per pressure trial (34 terms and 32 multiplications, allocation-free) instead of once per temperature; the temperature powers stay cached in the prepared temperature's workspace.

## 4. Node Jacobian (section 2.3)

The block sweep is a finite difference over the same rows, so nothing was hand-chained. `DirectLiquidJacobianRowsTest` differences `PhaseLayout.fixedInventory` (the rows the sweep assembles) in the `ln P` column and compares with the analytic EOS derivatives at the state pressure:

| Row, node | Swept | EOS / Region 1 analytic | Retired `k` |
|---|---|---|---|
| Volume closure, liquid-full crude + free water, 350 K, 1 MPa | -1.219076e-3 | -1.219076e-3 (`nL dvL/dP + nW dvW/dP`) | -1.000e-3 |
| Energy balance, same node | -1.109231e-3 | -1.109231e-3 (`nL dhL/dP + nW dhW/dP - V - P dV/dP`) | |
| 20 equilibrium rows, two-phase TJL + N2, 350 K, 150 kPa | | `P (vbar_L,i - vbar_V,i)/RT`, worst relative 3.4e-9 | |

Same sign and sparsity, as the plan stated; the volume column is 22 % larger than the retired one at that node (the crude liquid's own 1.24e-9 /Pa). The velocity limit's acoustic bound equals `V/sqrt(M (V_L kappa_L + V_W kappa_W))` to 1e-12, and `State.waterCompressibility` equals Region 1's `-(dv/dP)/v` to 1e-15.

## 5. Continuity (section 2.6)

1. **Isotherms into the new region** (`DirectLiquidContinuityTest.isothermsCrossTwoMegapascalsWithALiquidRootAndMeetTheTargets`, network translations, 41 pressures from 1.02 psat to 10 MPa). The liquid root exists at every point (three roots, or one liquid-like root). Worst density deviation against the Helmholtz oracle (D7: compressed liquid 3 % to Tr 0.85, declared 10 % above):

| Fluid | Tr 0.6 | Tr 0.7 | Tr 0.8 | Tr 0.9 |
|---|---|---|---|---|
| N2 (77, 90, 100, 110 K) | 0.19 % | 0.34 % | 1.15 % | 3.55 % (declared band) |
| CH4 (114.3 to 171.5 K) | 1.58 % | 1.61 % | 1.03 % | 4.72 % (declared band) |
| C2H6 (183.2 to 274.8 K) | 1.23 % | 1.57 % | 1.07 % | 4.87 % (declared band) |

   The P1 study's direct evaluation is reproduced: N2 77.36 K / 5 MPa -0.082 % (P1 -0.08), 77.36 K / 10 MPa -0.172 % (-0.17), 100 K / 10 MPa +0.336 % (+0.34). CO2 is not in the network package; its isotherms belong to the pilot package (WP5 wiring, WP9 fixture F1/F4). Isobars from the triple point to 1.2 Tc were not added (open item).
2. **No jump at 2 MPa** (`secondDifferencesShowNoJumpAtTwoMegapascals`): second differences of `v(P)` and `h(P)` on a 50 kPa grid from 1 to 3 MPa change by less than 2 % of their largest value, for a crude liquid at 300, 350, 375 K, liquid N2 at 77, 90, 100 K and liquid water at 300, 350, 375 K.
3. **Old region, old against new** (`belowTwoMegapascalsTheLiquidMovesOnlyByTheFirstOrderCorrection`, the `FluidPropertyCoverageTest` grid 293.15-375 K x 50 kPa-2 MPa, network package and six presets, 30 liquid states each): every state's difference to the retired path is inside the first-order bound (anchor term plus `|P - 2 MPa|` times the larger end difference of the pressure derivatives) for volume, enthalpy and every `ln phi_i`. Largest differences: `|dv/v|` 1.09e-3 (Dalia) to 2.22e-3 (WTI light export), `|dh|` 2.19 to 2.40 J/mol, `|d ln phi|` 1.99e-3.
4. **Network UV compression** (`aClosedLiquidNitrogenVesselCompressedThroughTwoMegapascalsFollowsTheAdiabat`): a closed 1 m3 vessel of liquid N2 at 90 K compressed reversibly from 0.5 to 8.02 MPa in 107 volume steps by `InventoryEquilibrium.solve` (the network's own UV rows), on a test catalog whose nitrogen and envelope ceilings are raised to 10 MPa (the bundled package keeps 2 MPa until WP7). Every state conserves the inventory (1e-10) and closes the volume (1e-9); the temperature rise is 3.00593 K against the EOS adiabat `int T (dv/dT)_P / c_p dP` 3.00593 K (agreement to the printed digits, target 1 %); the secant bulk modulus rises smoothly from 311.9 to 394.3 MPa and its increment changes by 2.4e-3 across 2 MPa (no kink). This is the WP4 slice of fixture F8; the pump-driven version belongs to WP9 after WP7 widens the envelope.

## 6. chain-100 in declared mode, deviation table and first-order check

`gradlew fluidSolverRegression` (declared mode; the three island fixtures are skipped, no `build/probe` snapshot) replays chain-100 (100 reservoirs, 350 K, 150 kPa +- 1 kPa, TJL methane assay + 0.1 N2 + 0.2 water, one 5 s interval) and compares against the pinned `src/test/resources/fluid/regression/chain-100.json`. **Not re-captured** (WP11 does, after WP7). Per-node values from `DirectLiquidChainDeviationProbe` (same fixture, same solve; CSV in the research folder):

| Quantity (per node, relative unless stated) | max | mean | declared gate | gate |
|---|---|---|---|---|
| pressure | 4.47e-7 | 1.81e-7 (abs) | 1e-6 | passes |
| temperature | 7.46e-6 K | 3.81e-6 K | 1e-4 K | passes |
| mass | 2.87e-5 | 2.84e-5 | 1e-6 | **fails** |
| component moles (all 21) | 2.87e-5 | 2.84e-5 | 1e-6 | **fails** |
| hydrocarbon liquid volume | +1.0256e-3 | +1.0250e-3 | fraction 1e-6 (4.39e-5 abs) | **fails** |
| vapour volume | -4.55e-5 | -4.51e-5 | fraction 1e-6 (4.36e-5 abs) | **fails** |
| free-water volume | -9.32e-4 | -9.31e-4 | fraction 1e-6 (about 4e-7 abs) | passes |
| vessel volume | about 1e-12 | | 1e-6 | passes |
| pipe flows | 2.04e-4 of flow (2.8e-6 kg/s) | | 1e-3 of flow | passes |
| substeps accepted/rejected | 37/1 | reference 37/1 | exact mode only | equal |

**Failing assertions.** Declared mode: on every one of the 100 nodes, `mass`, `moles[0..20]` and the `liquidVolume` and `vaporVolume` fractions (the harness stops listing at 40). Exact mode additionally fails on every other node field (temperature, pressure, volume, water volume) and every pipe flow, because the trajectory differs from the fixture's first flash on; the substep counts are equal. **Why:** the fixture rescales each node's inventory to fill 1 m3 at its pressure, and the liquid's molar volume and ln phi are now the EOS's at 150 kPa: the liquid is 0.10 % larger per mole, free water 0.10 % smaller, and the new liquid fugacities move a little of the light ends from gas to liquid, so 2.84e-5 more moles fit in each vessel. Pressure moves only 4.5e-7 because the fixture is rebuilt at the same pressures.

**First-order check** (plan section 2.5 step 3, per node at the node's own end state): predicted liquid-volume deviation `(1 + (kappa_EOS - k)(2 MPa - P) + sum x_i dc_i / v)(1 + dN/N) - 1`, with `kappa_EOS` the node liquid's own compressibility (1.2434e-9 to 1.2440e-9 /Pa against `k = 1e-9`), the pressure term 4.504e-4 to 4.512e-4, the anchor term (the cut translations anchored at their own 288.7 K, 101,325 Pa instead of through `exp(k (P - 2 MPa))`) 5.917e-4 to 5.925e-4, and the hydrocarbon-inventory term 2.84e-5. Prediction 1.0704e-3 to 1.0718e-3 against observed 1.0243e-3 to 1.0256e-3: **residual 4.50 % to 4.51 % of the deviation on every node, inside the 10 % criterion.** The residual is the second-order part of the pressure term (`kappa_EOS` falls between 150 kPa and 2 MPa): the exact formulation difference at each node's state (`v_direct / v_retired - 1`, 9.885e-4 to 9.891e-4) plus the inventory term explains the observation to 0.76 %. Free water: `(kappa_w - k)(2 MPa - P)` with Region 1's 4.566e-10 /Pa predicts -1.005e-3; with the inventory term the residual is 5.1 % of the observed -9.31e-4 (the free-water amount itself shifts with the steam split). The plan's estimate "about -0.19 %" had the anchor term's sign reversed: PR's own compressibility of the cuts at 288.7 K (about 0.69e-9 /Pa) is below 1e-9, so anchoring at the anchor's own pressure makes the liquid larger, not smaller; the measured deviation sits at the top of the plan's 1e-4 to 1e-3 range.

## 7. Counters and cost

chain-100, one interval, fresh JVM, back-to-back pair on a quiet machine (reports in the research folder):

| Counter | before (`5100233`) | after (WP4) |
|---|---|---|
| wall ms (not a gate; single cold interval) | 1394 | 1264 |
| allocated MB | 1146.5 | 1030.9 (-10.1 %) |
| accepted / rejected substeps | 37 / 1 | 37 / 1 |
| Newton iterations | 529 | 530 |
| Newton backtracks | 14 | 14 |
| Jacobian builds / block fallbacks | 24 / 0 | 24 / 0 |
| residual evaluations | 620 | 621 |
| state calls (in Jacobian) | 180,700 (96,000) | 180,800 (96,000) |
| temperature-terms calls | 74,350 | 73,872 |

Allocation falls because the liquid path no longer builds the 2 MPa `Phase` (two array clones), the response's input array and `State`, the `ln phi` clone, and per-temperature Region 1 arrays. The Region 1 pressure terms add compute per water-bearing trial; the wall pair shows no regression within its noise (an earlier after-run under load read 2225 ms with its pure LU timings doubled, so wall is not used as evidence). The fluid benchmark pair (60 s + 60 s) is left for WP11 as the plan schedules it.

**Determinism** (`DirectLiquidDeterminismTest`): the Region 1 workspace reused over nine (T, P) states forwards and backwards gives the same bits as fresh workspaces (and as the stable-liquid record entry); a four-phase node state (liquid, vapour, free water, steam) through one reused prepared temperature over 48 pressure and composition trials in both orders equals the standalone state bit for bit (volume, enthalpy, internal energy, water volume and compressibility, liquid `dv/dP` and `ln phi`, acoustic bound); the liquid phase on a reused Peng-Robinson workspace equals fresh ones. The evaluation order is fixed; the Region 1 workspace is thread-confined with the prepared temperature that holds it.

## 8. Test suites and moved pins

Gradle (each under `build/gradle.lock`, `--offline`, `JAVA_OPTS=-Xshare:off`, no dev client): `compileJava compileTestJava compileFluidGameTestJava` green; **`fluidScienceTest` 195 tests, 0 failures, 1 skipped** (the env-gated probe); **`fluidRuntimeTest` 226 tests, 2 failures** (below); `fluidSolverRegression` declared mode: chain-100 fails as described in section 6 (expected; no re-capture), island fixtures skipped; the probe run with `CREATECHEME_WP4_CHAIN_PROBE=1` passes. The base tree `5100233` (exported with `git archive`, built separately) passes `fluidSolverRegression` bitwise (zero deviation), which is where the "before" counters come from.

Pins updated with a stated reason (all in WP4's files):

- `HydrocarbonModelTest`: rewritten; it pinned `kappa = 1e-9` for every liquid. Now: liquid bitwise equal to the translated EOS at the state pressure, own compressibility, anchors reproduce their targets at their own state (1e-9), PIP separation, the liquid-root rule.
- `GlobalLiquidResponseTest`: deleted with the class.
- `HybridDerivativeTest`: the water `dv/dP` pin (`-1e-9 v`) replaced by Region 1's own (finite difference to 1e-6, kappa 3.5e-10 to 6e-10), Maxwell identity kept, metastable states added.
- `FluidNitrogenCryogenicTest`: unchanged assertions pass; measured numbers in its comment updated: saturated liquid density at 77.355 K and 101,325 Pa -0.069 % (was +0.34 %), Psat +1.206 % (was +1.23 %).
- `PumpRiseScalingTest`: reference density 997.05 kg/m3 (was 996.0; Region 1 directly); water's density ratio now checked against Region 1's 4.525e-10 /Pa. Gas transfer: rise at shutoff 573.2 Pa (limit 574.4 Pa; about 575 Pa before), about 0.1 % lower as the plan expected.
- `FluidThermoDomainHoldTest`: passes unchanged; the suction still cools 0.24 K (297.909 K) and the narrowed 298.05 K floor is still crossed (section 10's dependency holds).
- Domain messages at 2 MPa (`AmbientCrudePresetTest`, `ThermoDomainViolationTest`, `FluidDeviceSpecDomainTest`): unchanged and passing, because the network envelope's ceiling is still the records' `fluid_domain` (2 MPa) until WP7 widens N2 and CH4.
- `SolidChainTransportTest.clearChainSubstepCountsAreUnchanged`: the water chain's substep counts re-recorded (25/10, 32/14, 18/5 became 24/11, 26/9, 16/5): water is now stiffer (4.5e-10 against 1e-9 /Pa), which changes the step sequence; the pin's purpose (the solid projection never changes a clear island's steps) is unchanged.
- Checkpoint tests: format 5 / unit format 2, format 4 added to the refused formats, the changed-compressibility decode case removed (the property-change case covers it), `FluidFallbackQualificationTest`'s "property change" now a heat-capacity edit.

**Still failing in `fluidRuntimeTest` (left as they are, for a decision; not weakened):**

1. `FluidPumpedFillLineTest.aSixTankChainPassesTheStartThatHeldItForever`: "online 2400, committed 0, 11 jobs, 22.0 s of work, HELD: wall deadline". Diagnosed with a throw-away switch that restored the retired water compressibility alone: the first 0.05 s slice of this start costs 1,866,339 solver checkpoints with `k = 1e-9` water and 2,084,304 with Region 1 water (+11.7 %), against the rig's 2 s wall budget of 2,000,000 (1 us per checkpoint). Newton backtracks 827 to 975, Jacobian builds 175 to 186, state calls 27,534 to 30,709; the same rejection kinds, no liquid-root or domain refusals. The case already sat 7 % under the budget; real water compressibility (2.2 times stiffer liquid-full junctions) pushes it over, so the island would stay held in game. This is the plan's "liquid-full stiffness" risk (section 10) materialising; it needs a solver-side answer (WP7 or a budget decision), not a WP4 formulation change.
2. `ElevatedBlockLineIslandTest.elevatedLinesIntegrateFortyIntervalsLikeTheirFlatControl`: the pumped rising line settles at 860,290.7 Pa against the test's 860,883.9 +- 90 Pa (the passive lines still settle within 0.1 Pa). The pump closes with its head 658 Pa under its limit computed on the junction's water density; the tank node is a water and nitrogen mixture (880.8 kg/m3), and where inside that 3.4 kPa static-head ambiguity the pump's head-limit pass closes is trajectory-dependent: 23.6 Pa short with the retired water, 593 Pa now (and 879 Pa with the retired water but the new reference density). The limit itself moved only -80 Pa. The test's 1e-4 tolerance was set from one trajectory; the settle rule for a vertical pump into a mixed node is a solver question for the lead or WP7.

## 9. Known limits

- The network envelope is still 2 MPa (records' `fluid_domain`): above it the direct path is qualified by the tests of section 5 only through a test catalog. WP7 widens N2 and CH4 to 10 MPa.
- The liquid-root rule's PIP misreads hot dilute supercritical gases as liquid-like (section 2); the outer phase decision is still the Wilson flash with the `vaporBranch` heuristic (WP7).
- The metastable-water bound is a declaration (2 MPa below saturation), not a validity statement of the release.
- Isobars from the triple point to 1.2 Tc and the pilot-package isotherms (CH4, C2H6, CO2 on Tr 0.8 anchors) are not part of WP4's tests (WP5/WP9).
- Transport is still reference-pressure (`MixtureViscosity`), as the plan declares.

## 10. Open items

For WP7 / the lead:

1. The two failing runtime tests of section 8 (six-tank pumped start over the wall budget; the pumped vertical line's settle point).
2. Envelope widening (N2, CH4 to 10 MPa) and the outer phase check with the P2 engine (stability test, `PhaseIdentification` replacing both `vaporBranch` and WP4's in-model PIP).
3. The domain-message tests at 2 MPa move with the widening.
4. chain-100 re-capture after WP7, with this document's deviation table as the WP4 half.
5. The fluid benchmark pair (60 s + 60 s) for the Region 1 per-trial cost.
6. Remove the two `@Deprecated` 3-argument shims (`FluidThermodynamics(MaterialCatalog, String, double)` and `forNetwork(MaterialCatalog, String, double)`, argument ignored) once their last callers drop the argument: `science/material/MaterialPresetsTest` (data agent), `science/thermo/TangentPlaneStabilityTest` (engine agent), `science/fluid/thermo/TranslatedPengRobinsonDerivativesTest` (WP1). WP4 did not edit other agents' files; no overload `(catalog, package, maximumVelocity)` exists, so no old call can silently rebind to a velocity of 1e-9 m/s.
7. Pilot network path (WP5): `HydrocarbonModel` anchors from `volume_translation`; the construction-time "no liquid root at the anchor" refusal will reject a record anchored at standard conditions where the cubic has only a gas root (CO2 at 288 K, 1 atm), which is the intended behaviour.
8. Stale text outside WP4's files: `TranslatedPengRobinson.Derivatives` Javadoc (lines 226-228, `{@link GlobalLiquidResponse}`, WP1), the `CubicPhaseEvaluator` class comment (`HydrocarbonModel.REFERENCE_PRESSURE`, WP6a), the `fluid_domain` evidence strings of the property and package records that describe "PR78 at the 2 MPa liquid reference with the global compressibility response" and the N2 record's "+0.34 %" (data records; WP7 edits `nitrogen.json` and `tjl20_methane.json` anyway).
9. Detach at WP11: `DirectLiquidChainDeviationProbe` (measurement probe); `LegacyLiquidPath` stays only while `DirectLiquidContinuityTest` (a gate candidate) uses it.

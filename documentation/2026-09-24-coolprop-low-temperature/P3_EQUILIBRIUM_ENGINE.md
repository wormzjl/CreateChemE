# P3 equilibrium engine (WP6a: TP completion)

Date: 2026-09-24. Batch `2026-09-24-coolprop-low-temperature`, plan `P3_PILOT_ENGINE_PLAN.md` section 6 (and the WP6a
row of section 9), on top of the P2 engine of `P2_PHASE_CONTRACTS.md`. Branch `claude/coolprop-multiphase-thermo-37f6b0`,
started at `5100233`; other stage-1 agents committed WP1 (`43d1081`), WP2/WP3 (`6ec5c2c`, `50dfe31`) and WP8 (`eb1ed10`)
meanwhile, and WP4's direct liquid path was in the worktree uncommitted. Commit of this work package: `f5ffcf8`
(code and tests; this document and the tools are git-ignored).
Status: implemented with tests; since WP7 the fluid network decides its phases with this engine (section "WP7"). WP7
appended its network-integration sections to this document, WP7b and WP7c (the one-root label of a hot gas) their notes,
WP7d (the G3 defects, the band with D14's corner and D15's widened box 2, `LIQUID_LIQUID`) its note.

## 0. Summary

- **Newton finish.** The two-phase flash hands over from accelerated substitution to Newton on the vapour mole numbers
  minimising the Gibbs energy. All 122 states P2 left `NOT_CONVERGED` converge (Newton mean 5.2 steps, max 19); the P2
  scans have zero `NOT_CONVERGED` inside or outside the band (130,600 binary states and the 1,240-state network field).
- **Critical band.** Pure-fluid boxes of the plan; for mixtures a tie line `sum z_i (ln K_i)^2 < 0.1` or an incipient
  stationary phase within `sum (ln w_i - ln z_i)^2 < 0.1` of the feed. The thresholds are calibrated on the P2
  near-critical scan (the plan's 0.01 would have covered 45 of the 122 former failures, 0.1 covers all). Inside the band a
  converged answer is `RESEARCH_ONLY`; a failure is typed `CRITICAL_BAND`. Merging (tie line `< 1e-8` and molar volumes
  within 1 %) reports one `SINGLE_FLUID` labelled `FLUID`; no scanned split came that close.
- **Phase identification parameter.** `PIP = v [(d2P/dTdv)/(dP/dT)_v - (d2P/dv2)/(dP/dv)_T]` on the untranslated cubic;
  **`PIP > 1` is liquid-like** (the plan's parenthesis "(> 1 vapour-like)" has the sign reversed; the paper, the ideal-gas
  and dense-liquid limits and the test states all agree on liquid-like above 1). Single one-root phases are labelled by
  it; a pure component above Tc and Pc stays `SUPERCRITICAL_FLUID` labelled `FLUID`.
- **Free water.** The engine applies the network's free-water rule (`FluidThermodynamics.flashTP`) through a
  `FreeWaterModel`; `TangentPlaneStability` gained per-branch pressures (liquid at `P`, vapour at `pc`), bitwise identical
  on the one-pressure path. Phase counts equal the network's `flashTP` on 6,199 of 6,200 wet network states (the one
  difference is a dew-point hydrocarbon split with a 9.4e-10 mol liquid, decided by the flash, not the water rule; zero
  water-rule differences).

## 1. What changed

| File | Change |
|---|---|
| `science/thermo/phase/FluidTpEquilibrium.java` | Newton finish; Wilson-seed retry; critical band; merging; PIP labels; free-water rule (`solveWet`, the partial-pressure bisection, composite gas state, free-water state); per-branch hydrocarbon core; new `Settings` fields (the 6-argument P2 constructor kept); constructor taking a `FreeWaterModel` |
| `science/thermo/phase/PhaseIdentification.java` (new) | The PIP closed form on the PR78 cubic, `liquidLike`/`vapourLike` |
| `science/thermo/phase/FreeWaterModel.java` (new) | What the engine needs of the water model: `revision`, `saturationPressure`, `vaporPartialPressureLimit`, `liquid(T, P, out)`, `vapor()` (ideal steam as an `IdealGasFunction`) |
| `science/thermo/phase/EquilibriumResult.java` | `Classification.FREE_WATER`; record `FreeWater` (hydrocarbon classification, `p_sat`, `pc`, `p_w`, liquid water, steam) with a 13-argument constructor (the 12-argument one kept); `Diagnostics` gains `newtonIterations`, `tieLine`, `criticalBand`; constant `CRITICAL_BAND` |
| `science/thermo/phase/PhaseKind.java` | `FREE_WATER`; the label rule of P3 in the class comment |
| `science/thermo/phase/CubicPhaseEvaluator.java` | `translation(i)`, `translations()` accessors (additive) |
| `science/thermo/phase/PhaseContract.java` | Network coverage evidence: the band is now graded (wording only) |
| `science/thermo/TangentPlaneStability.java` | Per-branch pressures `test(T, pL, pV, [translations,] feed, ws)`; `Result.nearestStationaryDistance` (the 9-argument P1 constructor kept) |
| tests `science/thermo/phase/FluidTpEquilibriumP3Test.java` (new), `PhaseTestSupport.java` (network free-water fixture), `FluidTpEquilibriumCostTest.java` (four P3 rows), `science/thermo/TangentPlaneStabilityTest.java` (three per-branch tests; the P1 tests unchanged) | section 9 |

Not changed: the kernel (WP1's, used through its public API), `TranslatedPengRobinson`, the network (`FluidThermodynamics`,
WP4/WP7). The PH and UV stubs still refused (`NOT_IMPLEMENTED`) after WP6a; WP6b implemented them (section "WP6b: PH and UV").

## 2. Newton finish

**Switch.** Successive substitution runs as in P2. Newton takes over when substitution has run
`slowSubstitutionSteps = 10` steps with the latest dominant-eigenvalue estimate `lambda = (d_k . d_k)/(d_(k-1) . d_k)`
above `slowEigenvalue = 0.9`, after `substitutionLimit = 50` steps in any case, or when substitution fails the P2 way
(collapse onto the feed at `sum ln K_i^2 < 1e-8`, K-values no longer straddling 1, a non-finite step, or a converged
ratio set with `beta` outside (0, 1)). A split that converges by substitution within 10 steps (or with `lambda <= 0.9`) is
computed exactly as in P2, bit for bit (the P2 60-state test reproduces its P2 numbers: iterations mean 10.1 max 11,
worst fugacity residual 9.03e-12).

**Variables and equations.** Per mole of feed, vapour mole numbers `v_i` and liquid `l_i`, both carried (each updated
by the same step with opposite sign) so the smaller of the two keeps its relative precision; `V = sum v_i`,
`L = sum l_i`, `y = v/V`, `x = l/L`. With the feed's `d_i = ln z_i + ln phi_i(z) + s_i(feed branch)`:

- `Delta G/RT = sum_i v_i (ln y_i + ln phi_i^V + s_i - d_i) + l_i (ln x_i + ln phi_i^L - d_i)` (zero at the feed,
  negative for any split better than it; `s_i` is the vapour branch's shift, zero at one pressure),
- gradient `g_i = ln y_i + ln phi_i^V + s_i - ln x_i - ln phi_i^L = ln f_i^V - ln f_i^L`,
- Hessian `H_ij = (delta_ij/v_i - 1/V + Phi^V_ij/V) + (delta_ij/l_i - 1/L + Phi^L_ij/L)`, with the kernel's per-mole
  `Phi_ij = d ln phi_i/d n_j` of each phase at its normalised composition, symmetrised `(Phi_ij + Phi_ji)/2`.

**Step.** Scaled `s_i = sqrt(v_i l_i / (v_i + l_i))` so the ideal part of `S H S` is the identity; Cholesky of
`S H S + mu I` with `mu = 0`, then `1e-8, 1e-7, ..., 1e4` until positive definite (deterministic sequence); step
`dv = -S (S H S + mu I)^(-1) S g`, a descent direction. Fraction to the boundary: the length is cut so no `v_i` or `l_i`
falls below a tenth of itself. Line search: halvings (up to 40) until
`Delta G(new) <= Delta G(old) + 1e-4 alpha g.dv + 1e-15 M`, where `M` is the magnitude of the summands of `Delta G`
(roundoff allowance). Each phase is evaluated with derivatives on its lower-Gibbs root at one pressure, or on its fixed
branch with branch pressures.

**Start.** The finish needs a split with `Delta G < 0`, which it then keeps: descent cannot reach the trivial solution
(`Delta G = 0`), which is what defeated substitution on the 17 collapses. First candidate: the substitution iterate
(`v_i = z_i beta K_i/(1 + beta (K_i - 1))`, `l_i = z_i (1 - beta)/(1 + beta (K_i - 1))`) when `0 < beta < 1` and its
`Delta G < 0`. Otherwise the stability test's stationary phase `w` in the amount `eps w` (the rest `z - eps w`), `eps`
halved from `min(0.5, 0.5 min z_i/w_i)` until `Delta G < 0` (to first order `Delta G = eps D(w) < 0` whenever the test
proved `tm < 0`). With branch pressures a stationary phase on the feed's own branch cannot seed a liquid-at-`P`,
vapour-at-`pc` split and is skipped.

**Convergence and failure.** Converged at `max |g_i| < 1e-10` (the fugacity tolerance). Typed failures: no start, no
positive-definite shift, no decrease, a phase at root coalescence (the kernel's derivative refusal), 50 steps. After
the split, P2's product stability re-check runs unchanged. A failed first attempt is retried once from Wilson's K
(Raoult with the vapour at its own pressure); the first failure's reason is kept in the detail.

**Determinism and allocation.** Fixed order everywhere, no randomness; every array (the `n x n` Hessian and its factor,
the step, the trial amounts, two kernel derivative bundles) lives in the caller's `Workspace`; iterations allocate
nothing beyond the kernel's `RootSelection` record per call (P1/P2). Tested bitwise, fresh against reused workspaces
(section 9).

## 3. Critical band and merging

**Pure fluid** (one component at least 0.95 of the amount): the plan's boxes on its own Tc, Pc: Tr 0.95-1.1 x Pr
0.8-1.5, Tr 0.90-0.95 x Pr 0.58-0.74, Tr 1.05-1.2 x Pr 2-3 (closed intervals). Since WP7d also D14's corner Tr 1.0-1.1 x
Pr 1.5-2 and D15's widening of box 2 by Tr 0.95-0.97 x Pr 0.58-0.8 (section WP7d.2).

**Mixtures**, from the engine's own results (no weighted critical constants):

| Result | In the band when |
|---|---|
| two phases | `sum_i z_i (ln K_i)^2 < bandTieLine = 0.1` |
| one phase | the feed's stability test converged a trial to a stationary point other than the feed within `sum (ln W_i - ln z_i)^2 < bandStationaryDistance = 0.1` (`TangentPlaneStability.Result.nearestStationaryDistance`) |
| not converged | the last split's tie line `< 0.1`, or the stability seed within `0.1` of the feed |

Inside the band a converged answer's grade is `RESEARCH_ONLY` (evidence names the criterion); a failure's detail starts
with `CRITICAL_BAND (...)` and `diagnostics().criticalBand()` is true: the network's held-island path (WP7).

**Calibration** (P2 near-critical scan, 105,600 states; tool `BandCalibration`): the 122 former failures have tie lines
1.2e-3 to 7.1e-2 once converged. The plan's 0.01 contains 45 of them; 0.1 contains all 122.

| Tie-line threshold | Splits inside (of 8,544) | Single phases with `lambda_min(B) <` threshold (of 97,056) |
|---|---|---|
| 0.01 | 111 | 1 |
| 0.05 | 829 | 24 |
| 0.1 (chosen) | 2,022 | 63 |
| 0.3 | 6,370 | 610 |

`B = I + sqrt(z_i z_j) Phi_ij` is the feed's reduced Hessian, whose smallest eigenvalue vanishes on the spinodal (it
touches the phase boundary only at a critical point). The single-phase neighbours of the failures (184 states one grid
step away) are mostly not near-critical by either measure (`lambda_min` median 0.28; only 38 have a stationary point at
all), so the band's single-phase side is the stationary-distance criterion with the tie line's threshold; the
`lambda_min` measure is recorded as an alternative (open item). Coverage of the chosen band: binary field 230 of 2,547
splits, 11 mixture and 137 pure single phases; near-critical 2,022 of 8,544 splits, 127 mixture and 1,239 pure single
phases; network field none.

**Merging.** A converged split with tie line `< mergeTieLine = 1e-8` (the P2 collapse criterion) and molar volumes within
`mergeVolumeGap = 1e-2` of their mean is reported as one fluid: `SINGLE_FLUID`, label `FLUID`, in the band, the feed
verdict kept `UNSTABLE` in the diagnostics and the detail "merged near-critical split ...: the stability test found the
feed unstable, but its two phases coincide within the model's resolution" (no single phase is claimed stable). The
volume condition keeps a pure-like split with a trace (short tie line, liquid against vapour) apart. No scanned split
came within 1e-8, so the volume threshold is not calibrated by data; the rule is tested with widened thresholds.

## 4. Phase identification parameter

Venkatarathnam and Oellrich (2011, Fluid Phase Equilibria 301, 225-233):
`PIP = v [ (d2P/dT dv) / (dP/dT)_v - (d2P/dv2) / (dP/dv)_T ]`, i.e. `d ln[(dP/dT)_v / -(dP/dv)_T] / d ln v` at fixed T.
On PR78 (`D = v^2 + 2 b v - b^2`):

    dP/dv    = -R T/(v - b)^2 + 2 a (v + b)/D^2
    dP/dT    =  R/(v - b) - (da/dT)/D
    d2P/dv2  =  2 R T/(v - b)^3 + 2 a/D^2 - 8 a (v + b)^2/D^3
    d2P/dTdv = -R/(v - b)^2 + 2 (da/dT)(v + b)/D^2

(the expressions of `TranslatedPengRobinson.fill`). **Derivative needs:** the mixture's `a`, `b`, `da/dT` and the root's
untranslated volume `v = Z R T/P`, all on `PengRobinsonKernel.Evaluation`; no `d2a/dT2`, no composition derivative.
**Sign:** the ideal gas gives exactly 1; a dilute real gas `1 + (B - T dB/dT)/v < 1` (`B = b - a/RT`); a dense liquid
`v/(v - b) > 1`: liquid-like above 1. (WP7c: a hot gas has `B - T dB/dT > 0` and a parameter just above 1; the label rule
became `PIP > max(1, Z)`, section "WP7c".) Near the vapour spinodal (`dP/dv -> 0-`, `d2P/dv2 < 0`) it tends to minus
infinity, near the liquid spinodal to plus infinity; at a pure critical point it is undefined in practice (the band
covers that). Undefined (`NaN`) at a mechanically unstable root. It is evaluated on the untranslated cubic (the
translation shifts the volume axis and would change the `v` prefactor; the label belongs to the root).

**Labels (P3 rule).** Three roots: the stability root decides (`SINGLE_VAPOR`/`SINGLE_LIQUID`, as P2). One root, pure,
T > Tc and P > Pc: `SUPERCRITICAL_FLUID`, label `FLUID`. One root otherwise: `SINGLE_FLUID`, label `LIQUID` if
`PIP > 1` (since WP7c: `PIP > 1` and `PIP > Z`), else `VAPOR` (one extra kernel call). A merged near-critical split: `FLUID`. In a free-water result the
labels are the network's slots (gas `VAPOR`, hydrocarbon liquid `LIQUID`, `FREE_WATER`).

| State (test) | Classification, label | PIP |
|---|---|---|
| N2 77 K 0.5 MPa | `SINGLE_LIQUID`, `LIQUID` | 9.31 |
| N2 300 K 0.1 MPa | `SINGLE_FLUID`, `VAPOR` | 0.997 |
| N2 130 K 6 MPa | `SUPERCRITICAL_FLUID`, `FLUID` | 4.97 (liquid-like) |
| N2 150 K 10 MPa | `SUPERCRITICAL_FLUID`, `FLUID` | 2.71 (liquid-like) |
| CH4 250 K 10 MPa | `SUPERCRITICAL_FLUID`, `FLUID` | 0.717 (vapour-like) |
| CH4/N2 0.5/0.5 130 K 6 MPa | `SINGLE_FLUID`, `LIQUID` | 6.83 |
| CH4/N2 0.5/0.5 150 K 10 MPa | `SINGLE_FLUID`, `LIQUID` | 5.59 |
| CH4/N2 0.5/0.5 250 K 10 MPa | `SINGLE_FLUID`, `VAPOR` | 0.768 |
| CH4/N2 0.5/0.5 400 K 3 MPa | `SINGLE_FLUID`, `VAPOR` | 0.950 |

(NIST densities: N2 at 130 K 6 MPa about 480 kg/m3 and at 150 K 10 MPa about 440 kg/m3, above its 313 kg/m3 critical
density; CH4 at 250 K 10 MPa about 100 kg/m3, below its 162.)

## 5. Free water as encoded

**Rule** (restated from `FluidThermodynamics.flashTP` and `state`, read at `5100233`; WP4 did not change it, only the
liquid water's property evaluation; the constants are public on `FluidTpEquilibrium`):

1. No hydrocarbons: all liquid water if `P >= p_sat(T)`, all steam at `P` otherwise.
2. `P > p_sat + 1e-6 Pa` (`SATURATION_MARGIN`): split the hydrocarbons with the liquid at `P` and the vapour at
   `pc = P - p_sat`; the steam that saturates the gas is `p_sat V_gas/(R T)` with `V_gas = n_V v_V(pc)` (translated
   volume). If the water holds that much, the rest is free liquid water (none when there is no hydrocarbon vapour).
3. Otherwise all water is steam, and `pc` solves `pc + n_w R T/V_gas(pc) = P` by bisection on `[1e-6 Pa, P]`
   (`PARTIAL_PRESSURE_FLOOR`, 70 halvings `PARTIAL_PRESSURE_BISECTIONS`, stop at `1e-8 P`, accepted within `1e-6 P`),
   each halving a full hydrocarbon TP (stability test and split) at `(P, pc)`.
4. A steam partial pressure above `waterVaporPressureLimit(T)` is `OUT_OF_DOMAIN` naming `Water`, `PRESSURE` (the network
   throws on the same state). Water below 273.16 K stays the contract's `OUT_OF_DOMAIN` (D8).

**Hydrocarbons at two pressures.** `TangentPlaneStability.test(T, P, pc, translations, feed, ws)`: liquid-branch
evaluations (the `LIQUID` root) at `P`, vapour-branch (`VAPOR` root) at `pc`, the vapour branch shifted by
`s_i = ln(pc/P) + (pc - P) c_i/(R T)` (the constant translation no longer cancels between two pressures: the network's
phases are translated), both branches evaluated for every composition, a single root at `pc` admitted as vapour only when
vapour-like (`PIP <= 1`; a dense liquid at the lower pressure would otherwise be unstable against itself). The flash
fixes the branches (liquid on its root at `P`, vapour on its root at `pc`), with `ln K_i = ln phi_i^L - ln phi_i^V - s_i`,
and checks the converged vapour is vapour-like (the network's `vaporBranch` role). Seeding: when the stationary phase lies
on the feed's own branch (a second vapour-like phase, met at 700-740 K for the crude), Wilson's K first; any failed
attempt is retried from Wilson's K (this removed the 33 wet failures of the first run).

**Result.** `Classification.FREE_WATER` with `EquilibriumResult.FreeWater` (hydrocarbon classification, `p_sat`, `pc`,
`p_w`, liquid water, steam) and up to three phases, in this order:

- `VAPOR`, the gas: hydrocarbon vapour and steam; state over the **contract basis** at `P`: volume `n_V v_V(pc)` (or
  `n_w R T/P` for steam alone), enthalpy, entropy and Gibbs energy the sums of the hydrocarbon vapour at `pc` and ideal
  steam at `p_w` (`FreeWaterModel.vapor()`), `ln phi_i` from each component's chemical potential against the ideal gas
  at `P`;
- `LIQUID`, the hydrocarbon liquid at `P` (evaluator basis, as in P2);
- `FREE_WATER`, pure liquid water at `P` from `FreeWaterModel.liquid` (one-component basis `[Water]`, like a crystal's),
  with the rule's fugacity `p_sat`: `ln phi = ln(p_sat/P)`, `mu = g_ig(T, p_sat)`, so it equals the steam's `mu` exactly
  when `p_w = p_sat`.

**Model and identity.** `FluidTpEquilibrium(contract, evaluator, settings, freeWaterModel)`; the model's
`separate-free-water-v1:<revision>` must be the contract identity's water revision (refused otherwise). Without a model a
request with water stays `NOT_IMPLEMENTED` (the P2 behaviour; the P2 tests pass unchanged). The network's adapter is
WP7's; the test fixture `PhaseTestSupport.networkFreeWater(FluidThermodynamics)` is one over the network's public
methods (`saturationPressure`, `waterLiquid`, `vaporWaterEnthalpy`, `waterVaporPressureLimit`; steam entropy from the same
Shomate polynomial anchored at 188.835 J/(mol K)). Enthalpies must share the evaluator's ideal-gas datum (WP5/D10).

## 6. `TangentPlaneStability` per-branch support

Additive: `test(T, pL, pV, feed, ws)` and `test(T, pL, pV, translations, feed, ws)`; with `pL == pV` the same code path
as P1 (every branch-specific statement is guarded), so results are bitwise identical: digest of every result field over
131,251 one-pressure states (both P1 binary scans, the 620 network states, pure nitrogen around coexistence) equal to the
committed P1/P2 implementation's on the same kernel (`StabilityDigest`, before and after WP1's kernel commit).
`Result` gains `nearestStationaryDistance` (the P1 constructor kept). The class now imports
`science.thermo.phase.PhaseIdentification` (a package cycle; the formula's natural home is the kernel, open item).

## 7. Scans (`tools/phase-equilibrium-scans/`, javac outside Gradle, 2026-09-24 23:40)

Other agents' sources at the committed HEAD (`eb1ed10`: the P2 network flash, WP1's kernel); the phase package and
`TangentPlaneStability` from the worktree.

| Scan | States | P2 (`5100233`) | P3 WP6a |
|---|---|---|---|
| binary field (95-190 K, 0.1-5 MPa) | 25,000 | 10 `NOT_CONVERGED`; 2,537 two-phase; kernel calls mean 31.3 max 2,016; worst fugacity 1.27e-10 | 0 `NOT_CONVERGED`; 2,547 two-phase (Newton on 31, mean 6.1 max 19 steps); kernel calls mean 31.2 max 211; worst fugacity 8.8e-11; defect 1.98e-16 |
| near-critical (130-185 K, 2.5-6 MPa) | 105,600 | 112 `NOT_CONVERGED` (93 budget, 17 collapse, 1 lost, 1 beta); 8,432 two-phase; kernel calls mean 30.1 max 2,026; worst fugacity 1.53e-10 | 0 `NOT_CONVERGED`; 8,544 two-phase (Newton on 381, mean 5.2 max 29); kernel calls mean 29.0 max 224; worst fugacity 1.23e-10; defect 1.98e-16 |
| network (crude + N2, light gas; 300-900 K, 0.1-2 MPa), dry | 1,240 | 1,240/1,240 phase counts equal `flashTP` | unchanged: 1,240/1,240; kernel calls mean 55.6 max 198 |
| network wet: the same with water/hydrocarbon 0.001, 0.01, 0.1, 1, 10 | 6,200 | refused (`NOT_IMPLEMENTED`) | 6,199/6,200 phase counts equal `flashTP` (1,258 refused by both on the steam limit; 621 gas + liquid + free water, 3,734 gas + liquid, 587 gas); 0 water-rule differences; 1 hydrocarbon-split difference (light gas 820 K 1.3 MPa, water 1e-3: the engine's liquid 9.4e-10 mol at its dew point, the network's none); same counts with the network's own translations; engine 1.1 ms/state, `flashTP` 1.2 ms/state (both run the bisection) |

`NOT_CONVERGED` inside the band: 0; outside: 0. Band used: section 3 (pure boxes; tie line and stationary distance
0.1). Every one of the 122 former failures converges with Newton, lies in the band, and both its phases are stable by
the independent (dense-plan) stability test. The engine's products are stable by that test on all 11,091 splits.

The first wet run (before the Wilson-seed rule) had 33 engine failures at 700-740 K, 1.2-1.6 MPa, little water: the
per-branch test proved instability with a second vapour-like phase, which cannot seed the network's liquid-at-`P` split;
section 5.

## 8. Cost

`FluidTpEquilibriumCostTest` (P2's probe with four P3 rows), in the Gradle run of section 9 (2026-09-24 23:39), single
test JVM after the other tests: JDK 21.0.11, 16 processors; before the run no dev client (checked by command line; a
Codex `column-gui` dev client had run until about 23:38), no other Gradle build held the lock, the idle Steam client and
other agents' idle Gradle daemons were up, 22.7 GB of 47.6 GB free, CPU load 17 %. Same method as P2 (two warm-up
passes of 3,000 calls, 200 timed calls with a reused workspace, `getThreadAllocatedBytes`). (The printed header of this
run lacked the new Newton column; the rows are read with it, and the header is fixed in the source.)

| Components, state | Result | Flash iterations | Newton | Kernel calls (derivative) | Mean us P2 -> P3 | p95 us P3 | Bytes/call reused P2 -> P3 | Bytes/call new workspace P2 -> P3 |
|---|---|---|---|---|---|---|---|---|
| 2: 110 K 0.5 MPa x_N2 0.5 | VAPOR_LIQUID | 11 | 0 | 136 (15) | 17.30 -> 17.76 | 19.70 | 5,448 -> 5,552 | 11,784 -> 14,232 |
| 2: 120 K 2.5 MPa x_N2 0.3 | SINGLE_FLUID | 0 | 0 | 23 -> 24 (2) | 3.21 -> 3.08 | 3.10 | 1,272 -> 1,328 | 7,608 -> 10,008 |
| 2: 120 K 3 MPa x_N2 0.5 | SINGLE_FLUID | 0 | 0 | 23 -> 24 (3) | 3.02 -> 3.09 | 3.10 | 1,272 -> 1,328 | 7,608 -> 10,008 |
| 1 of 21: N2 77.355 K 90 kPa | SINGLE_VAPOR | 0 | 0 | 5 (0) | 2.55 -> 1.67 | 1.80 | 1,488 -> 1,512 | 41,240 -> 61,944 |
| 20 of 21: crude + N2 350 K 0.5 MPa | VAPOR_LIQUID | 7 | 0 | 51 (10) | 36.71 -> 38.04 | 39.20 | 4,776 -> 4,816 | 44,528 -> 65,248 |
| 20 of 21: crude + N2 600 K 2 MPa | VAPOR_LIQUID | 11 | 0 | 68 (11) | 42.88 -> 44.68 | 46.20 | 5,320 -> 5,360 | 45,072 -> 65,792 |
| 20 of 21: crude + N2 900 K 0.1 MPa | SINGLE_FLUID | 0 | 0 | 16 -> 17 (4) | 13.24 -> 13.96 | 15.70 | 1,776 -> 1,832 | 41,528 -> 62,264 |
| 2: 150 K 4.56 MPa x_N2 0.67 (P2: not converged, about 2,000 calls) | VAPOR_LIQUID | 10 | 4 | 143 (41) | -> 21.34 | 38.10 | -> 6,288 | -> 14,968 |
| 2: 165 K 4.98 MPa x_N2 0.47 (P2: not converged) | VAPOR_LIQUID | 10 | 8 | 178 (58) | -> 24.16 | 25.60 | -> 7,408 | -> 16,088 |
| 21: crude + N2 + 1 mol water, 350 K 1 MPa | FREE_WATER (gas, liquid, free water) | 7 | 0 | 79 (9) | -> 44.11 | 49.00 | -> 7,472 | -> 67,904 |
| 21: crude + N2 + 1e-3 mol water, 350 K 1 MPa | FREE_WATER (gas, liquid; all steam, bisection) | 189 | 0 | 2,133 (245) | -> 1,093.68 | 1,140.10 | -> 94,928 | -> 155,360 |

Reading:

- The P2 paths cost what they cost in P2 (within 5 %, the machine's run-to-run variation); a one-root single phase
  pays one more kernel call for its PIP label (0.1 us at 2 components, 0.15 us at 20). The two-phase budget of D7
  (p95 <= 50 us at 21 components) holds: 46.2 us at 600 K 2 MPa.
- A former P2 failure now costs 21 to 24 us (143 to 178 kernel calls, 41 to 58 of them derivative calls) instead of
  about 2,000 kernel calls ending `NOT_CONVERGED` (about 0.25 ms).
- A reused workspace allocates 30 to 100 bytes more per call (the larger `Diagnostics` record); a new workspace costs
  2.4 kB more for two components and 20 kB more for the 21-slot basis (the Newton finish's two `n x n` kernel derivative
  bundles, its Hessian and factor, the contract-basis gas state): the network must keep its workspaces (it does).
- Free water with enough water is a flash at `(P, P - p_sat)` plus the gas and water states: 44 us. Too little water to
  saturate the gas makes the rule's bisection run about 27 hydrocarbon TP calls: 1.1 ms and 95 kB per call. That is the
  network's own algorithm (its `flashTP` spends the same, section 7: 1.2 ms per wet state), fine for boundaries,
  inventory refresh and presentation; WP7 should not put it on a per-node path without a warm start (open item).

## 9. Tests

Gradle run 2026-09-24 23:39, from Git Bash, under `build/gradle.lock` (tag `wp6a`), no dev client:

    CREATECHEME_PHASE_COST=1 JAVA_OPTS=-Xshare:off ./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.*' --offline

**86 tests, 0 failures, 1 skipped** (P1's `TangentPlaneStabilityCostTest`, enabled only by `CREATECHEME_STABILITY_COST`):
`PairInteractionsTest` 7, `PengRobinson78Test` 7, `PengRobinsonKernelPairInteractionsTest` 4,
`PengRobinsonKernelRootPrecisionTest` 6, `TangentPlaneStabilityTest` 10 (the 7 P1 tests unchanged plus 3),
`CubicPhaseEvaluatorTest` 6, `FluidTpEquilibriumTest` 7 (P2, unchanged), `FluidTpEquilibriumP3Test` 8 (new),
`FluidTpEquilibriumCostTest` 1, `SolidPhaseEvaluatorTest` 2, `ThermoIdentityTest` 2, `HelmholtzReferenceParityTest` 12,
`HelmholtzReferenceTest` 13. The tree compiled was the worktree as it stood, including WP4's uncommitted direct liquid
path (so the free-water comparison test ran against WP4's `flashTP`; the scans of section 7 against the committed one).
The same classes were also run with javac and a reflective runner before (`tools/phase-equilibrium-scans/p3/RunTests`).

| Test | What it establishes |
|---|---|
| `FluidTpEquilibriumP3Test.theP2NearCriticalFailuresConvergeInsideTheCriticalBand` | the 122 P2 failures (fixture table in the class: T, P, x_N2, P2 reason; 103 budget, 17 collapse, 1 lost split, 1 beta) all `CONVERGED` `VAPOR_LIQUID` through the Newton finish (mean 5.2 steps, max 19, at most 211 kernel calls), conservation below 1e-13, fugacity residual below 1e-9 (worst 8.6e-11), chemical potentials equal within 1e-8 RT, both products `STABLE` by the independent dense-plan test, all in the band (tie lines 1.2e-3 to 7.1e-2) and `RESEARCH_ONLY` |
| `...aFailureInsideTheBandIsTypedCriticalBand` | with substitution and Newton limited to one step: the near-critical state fails as `CRITICAL_BAND` (detail prefix, diagnostics flag, no phases, `UNAVAILABLE`), an ordinary two-phase state fails without it; the default service converges both |
| `...theNewtonFinishAndFreeWaterAreBitwiseRepeatable` | 13 of the 122 states and 3 free-water states (three phases; all steam; the 700 K Wilson-retry state): fresh workspaces against one reused in reverse then forward order, bitwise equal amounts, v, h, s, `ln phi`, every counter, tie line, fugacity residual, free-water record |
| `...mergingNeedsBothTheTieLineAndTheVolumeThreshold` | widened thresholds merge a near-critical split into `SINGLE_FLUID`/`FLUID` in the band with the unstable verdict kept; either threshold unmet keeps two phases |
| `...singlePhasesAreLabelledByThePhaseIdentificationParameter` | the nine states of the section 4 table: classification, label, and PIP side |
| `...thePhaseIdentificationParameterIsTheCubicsOwnDerivativeRatio` | the closed form against central differences of `P(T, v)` with the kernel's own `a(T)` (10 root evaluations, 1e-4 relative), and the ideal-gas limit `1 - 1e-9 < PIP < 1` at 1 mPa |
| `...freeWaterFollowsTheNetworkRule` | crude + N2 + 1 mol water at 350 K 1 MPa: gas, liquid, free water; `pc = P - p_sat` exactly, `p_w = p_sat` to 1e-12; conservation of all 21 slots below 1e-13; water's `mu` equal in gas and free water (1e-9 RT), each hydrocarbon's equal in gas and liquid (1e-8 RT); aggregates are phase sums; free water's volume is the network's; 1e-3 mol water: all steam, `pc + p_w = P` within 1e-6 P; pure water liquid above and ideal steam below `p_sat` at 320 K; 450 K 2 MPa water-rich: `OUT_OF_DOMAIN` (Water, pressure), and `flashTP` refuses the same state; a model whose revision is not the contract's is refused |
| `...freeWaterPhaseCountEqualsTheNetworkFlash` | 672 states (crude + N2 and the light gas; 300 to 900 K by 40; 0.2 to 2 MPa by 0.3; water/hydrocarbon 0.01, 1, 10) against `FluidTestSupport.networkModel().flashTP`: 213 refused by both, 459 answered by both (76 with three phases), hydrocarbon phases equal on all, phase count, gas and free water equal on all 458 states pinned by the rule's margin (free water above 5 % of the water or steam below 0.95 `p_sat`, `P` more than 5 % from `p_sat`) |
| `TangentPlaneStabilityTest.equalBranchPressuresAreTheOnePressureTestBitwise` | the six determinism states: `test(T, P, P, ...)` with and without translations bitwise equal to `test(T, P, ...)`, including `nearestStationaryDistance` |
| `TangentPlaneStabilityTest.perBranchVerdictsMatchABruteForceMinimum` | the 60 CH4/N2 states with the vapour branch at 0.8 P and P1-sized translations: verdicts equal a brute-force minimum (3,999 compositions) of the per-branch tangent-plane distance with the same admission rule; both verdicts exercised |
| `TangentPlaneStabilityTest.aDenseLiquidIsNoVapourAtTheLowerPressure` | equimolar CH4/N2 at 120 K 3 MPa with the vapour branch at 2.9 MPa stays `STABLE` on the liquid branch, although its single root at 2.9 MPa has the lower fugacity (it is liquid-like, PIP > 1); at 400 K the gas takes the vapour branch |

Bitwise evidence for the unchanged P1/P2 paths: the 7 P1 stability tests and 7 P2 engine tests pass unchanged (the P2
binary test prints its P2 numbers exactly); the stability digest of section 6 over 131,251 states.

## 10. Known limits

- Mixture single phases near a critical point whose stability trials all end at the trivial solution carry no
  stationary point, so the band's single-phase side misses them (63 states of the near-critical scan have
  `lambda_min(B) < 0.1`); the grade there is the contract's.
- The merging volume threshold (1 %) is not calibrated by data (no split came within the 1e-8 tie line).
- Free water: the per-branch hydrocarbon split near a dew or bubble point can differ from the network's Wilson flash
  in whether a vanishing phase (1e-9 mol) exists; the engine's answer has the stability test behind it. A pure
  hydrocarbon at its own coexistence with water is not detected as `PURE_COEXISTENCE_UNDERDETERMINED` (the branch pair
  picks the lower branch). The all-steam branch costs about 30 hydrocarbon TP calls (the network's bisection). Liquid
  water's properties come from the model at `P` (WP4's metastable admission below `p_sat` is the model's business; the
  rule never asks for liquid water below `p_sat + 1e-6 Pa`).
- Three phases and liquid-liquid splits are still refused (a product that is not stable is `NOT_CONVERGED`).
- With branch pressures, a pair of phases both vapour-like or both liquid-like cannot be represented (the network's
  slots); such a stationary phase only seeds nothing, and Wilson's K decides.
- PIP near a critical point is not a label to trust (tends to plus or minus infinity next to the spinodals); the band
  covers it.
- `TangentPlaneStabilityTest` (P1) builds `new FluidThermodynamics(catalog, id, 1e-9)`, which WP4 keeps as a deprecated
  overload; if WP4 removes it, that P1 test (mine) needs `FluidTestSupport.networkModel()`.

## 11. Open items

For **WP6b** (PH, UV): the TP service now returns free-water results; PH/UV over TP need the free-water phases' `h`, `u`
and `v` (present on the states) and the rule's discontinuities in `h(T)` where liquid water appears or the gas vanishes;
the merged near-critical fluid and `PURE_COEXISTENCE_UNDERDETERMINED` are the undetermined cases.

For **WP7** (network integration):

- a production `FreeWaterModel` over `FluidThermodynamics` (the test fixture is the template) and the contract's
  identity check; the `flashTP` adapter maps `FreeWater` to `State(t, p, liquid, vapor, waterLiquid, waterVapor, pc)`;
- `PhaseIdentification` replaces `vaporBranch`; the network's slot for a one-root phase is the label; a
  `SUPERCRITICAL_FLUID` (label `FLUID`) needs `PhaseIdentification.parameter` on its evaluation;
- `CRITICAL_BAND` failures to the held-island path; merged results remove the equilibrium rows;
- the evaluator for the network contract must carry the network's translations (they enter the vapour branch shift and
  the gas volume of the water rule).

For the **lead / WP1**: move the PIP closed form next to the kernel (it is PR78 algebra; `TangentPlaneStability` imports
it from the phase package today); decide whether the band's single-phase side should use `lambda_min(B)`.

Tools (never tracked, to copy to the main checkout at merge): `tools/phase-equilibrium-scans/` (P2's `FlashScan`,
`run.sh`) with the P3 additions under `p3/`: `compile.sh`, `run.sh`, `baseline.sh`, `P3Scan`, `BandCalibration`,
`StabilityDigest`, `BaselineFailures` (+ `baseline-failures.txt`), `WetDebug`, `WetState`, `PipCheck`, `RunTests` (a
reflective JUnit runner for javac-only checks) and the result files. Measurement code to detach: the four P3 rows of
`FluidTpEquilibriumCostTest` go with it.

## WP6b: PH and UV

Date: 2026-09-25. Plan `P3_PILOT_ENGINE_PLAN.md` section 7 and the WP6b row of section 9. Branch
`claude/coolprop-multiphase-thermo-37f6b0` at `959ea0d` (WP6a `f5ffcf8` landed). A first WP6b agent was cut off by a
rate limit and left an uncommitted start (the search skeleton below, the `Diagnostics` fields, `PhaseDomain.window`); it
did not compile (a missing `converged` overload). Its design was sound and is kept; what this pass changed is listed in
W.1. Commit of this work package: see W.8 (code and tests; this section and the tools are git-ignored). WP5 worked in
parallel (`CubicPhaseEvaluator`, `PhaseContract`, `science/fluid/thermo/**`); nothing of theirs was edited.

### W.1 What changed

| File | Change |
|---|---|
| `science/thermo/phase/FluidTpEquilibrium.java` | `ph` and `uv` implemented (the P2 `NOT_IMPLEMENTED` stubs removed); the TP entry split into contract checks, `implementationChecks` and `evaluateTp` (same order of checks; the TP path computes exactly what it did); `Settings` gains `maximumOuterIterations` (100) and `outerTolerance` (1e-11), the 6- and 15-argument constructors kept, `withOuterSearch`; constants `SPECIFICATION_TOLERANCE` (1e-6), `SEARCH_MINIMUM_PRESSURE` (1 Pa), `SEARCH_MAXIMUM_PRESSURE` (1e9 Pa); the class comment describes PH and UV |
| `science/thermo/phase/EquilibriumResult.java` | `Diagnostics` gains `outerIterations` (TP equilibria of the search), `specificationResidual`, `coexistenceFromBalance`; the 13-argument (TP) constructor kept |
| `science/thermo/phase/PhaseDomain.java` | `window(amounts, out)`: the envelope intersected with every carried component's range (allocation-free) |
| `science/thermo/phase/EquilibriumRequest.java` | unchanged: P2's request already carries PH `(P, H)` and UV `(U, V)` (`EquilibriumRequest.ph`, `.uv`); TP untouched |
| tests: `FluidTpEquilibriumPhUvTest` (new, 7 tests), `FluidTpEquilibriumTest` (its PH/UV "not implemented" check is now free water without a water model), `FluidTpEquilibriumCostTest` (8 PH/UV rows, a TP-calls column) | W.4, W.5 |

Changes against the inherited start: `waterBoiling` clamped the steam fraction silently (a target outside the boiling
span came back `CONVERGED` with the wrong enthalpy); such a target is now typed `NOT_CONVERGED`. The pure-coexistence
straddle used the classification (three roots on both ends only); it now uses the single phases' labels, so a
one-root liquid or vapour end counts. A straddle that meets no equal-fugacity point, and a free-water step that does not
hold the target, continue the search instead of failing. The free-water step is detected as soon as a bracket
straddles it, not only once it has closed to 1e-11 (pure liquid water UV ran out of its 100-call pressure budget
before). A `Math.max` with a NaN start temperature (wet UV) is fixed. The bisection rule, the PH and UV slopes, the UV
start and the UV Newton budget are new (W.2). A TP failure of a pure fluid inside its band during a search is typed
`CRITICAL_BAND`.

### W.2 Algorithm, brackets and tolerances

**Window.** `PhaseDomain.window` of the carried material, the temperature narrowed to every carried component's
ideal-gas range (and the steam's with water), the pressure to [1 Pa, 1e9 Pa]. PH first checks the specified pressure
against the domain (at the window's middle temperature). A specification beyond the window is `OUT_OF_DOMAIN` with the
violation one ulp outside the window's end (the state itself is not computed); beyond an open domain's search range it
is `NOT_CONVERGED`.

**The one-variable search** (`Search`): the latest points with a negative and a positive residual and their TP
answers; the next point is the proposed Newton or secant step while it stays strictly inside the bracket, otherwise
the midpoint; bisection also after two steps in a row that fail to halve the smaller end residual as it stood before
the step. This replaced the inherited rule (the width must halve every second step), which interrupted converging
secants, and a first version that compared with the last residual only, which a search alternating across a kink
reset every other step (40 TP calls next to a bubble point, now 26). Before a bracket, the step is capped and kept in
the window.

**PH.** Temperature at the fixed pressure; `H(T)` increases for stable equilibria. Start: the ideal-gas temperature
of the enthalpy. Slope: `N c_p` of one dry phase (the evaluator's analytic derivative on the answer's root); else a
secant between answers with the same number of phases (across a bubble or dew point `H(T)` has a kink, and a secant
spanning it is poor); else the bracket's own secant; else the ideal-gas heat capacity. Converged when
`|H - H_spec| <= 1e-6 N R T` and the next step is `<= 1e-11 T`; or when the bracket is `<= 1e-11 T` wide and the
residual's jump across it `<= 1e-6 N R T` (the better end). A wider jump is a step of `H(T)` (W.3).

**UV.** Newton on `(ln T, ln P)` for `F = ((U - U_spec)/(N R T), ln(V/V_spec))`. Jacobian: analytic for one dry phase
(`dU/dT = N (dh/dT - P dv/dT)`, `dU/dP = N (dh/dP - v - P dv/dP)`), otherwise forward differences in `ln T` and `ln P`
(step 1e-7) once and Broyden's update after each step, refreshed by differences when a step fails. Step capped at 0.25
in `ln T` and 2 in `ln P` (direction kept) and inside the window; halving line search (4 halvings) on `|F|^2` with an
Armijo factor 1e-4. Converged at `|F_i| <= 1e-6` and a step `<= 1e-11`.

Start (dry material): the temperature at which the feed as **one phase of the cubic at the specified volume** has the
specified energy, kernel calls only, from the closed-form PR departure on the untranslated volume
`v_u = V/N - sum z_i c_i` (the constant translation cancels from `u = h - P v`),
`u^R = (a - T da/dT)/(2 sqrt2 b) ln[(v_u + (1 - sqrt2) b)/(v_u + (1 + sqrt2) b)]`, by a safeguarded secant on the
window; and the cubic's own pressure there, `P = R T/(v_u - b) - a/(v_u^2 + 2 b v_u - b^2)`, when positive and
mechanically stable. For a stable single phase that is the answer (one TP call). Otherwise (a split, or water) an
inexact volume solve (1e-3 in `ln V`) at the larger of that temperature and the ideal-gas one (both lower bounds of a
split's temperature: `u^R < 0`, and a split lies below the single phase's energy at the same temperature and volume)
gives the start. Newton stops after 30 TP calls unless its last accepted step cut `|F|^2` fourfold, and never beyond
`maximumOuterIterations`.

Then the nested search: `U(T)` at the specified volume, bracketed in `T` (unbracketed steps capped at 0.25 T), each
temperature solving `V(P) = V_spec` in `ln P` (`ln(V_spec/V)` increases with `ln P`). The pressure solve starts at the
cubic's pressure when the last answer was one phase, otherwise from the earlier answers extrapolated linearly in
`(1/T, ln P)` (a vapour-pressure line; Trouton's `-10 T` with one), takes secants only between answers with the same
phases, and is inexact (a hundredth of the relative energy residual, at most 1e-3) while the energy is far off, redone
exactly once the energy is within 100 times the specification. A pressure outside the window at some temperature moves
the temperature bracket (at fixed volume the pressure rises with temperature); a bracket closed there is
`OUT_OF_DOMAIN` in pressure.

**Result.** The last TP answer (or the balance of W.3) re-labelled with the PH/UV specification: its phases,
classification, grade and free-water record; `Diagnostics` with the last TP answer's flash fields, the whole search's
kernel and derivative counts, `outerIterations` and `specificationResidual`. Failures: `NOT_CONVERGED` with the search's
counts, prefixed `CRITICAL_BAND` when the search's last TP answer lies in the band. Bounded: PH and the UV Newton count
TP equilibria against `maximumOuterIterations`; the nested UV search counts its temperature steps and each pressure
solve's TP equilibria against it (a bound, not a tight one: W.6).

**Critical points.** No PH/UV step needs a derivative: where the kernel refuses one (root coalescence) the search
takes a secant or the bracket (plan section 7: a missing or unbounded slope never stops them). Answers inside the band
keep the TP answer's research-only grade; a search that fails there is `CRITICAL_BAND`; a TP failure of a pure fluid
inside its band met during a search is re-typed `CRITICAL_BAND` too (none of the tested states triggers that path: at
exactly N2's `(Tc, Pc)` the TP answer exists).

### W.3 Coexistence, free water, merged fluid

**Pure coexistence.** TP returns `PURE_COEXISTENCE_UNDERDETERMINED` only within 1e-9 of the equal-fugacity pressure;
PH and UV meet the coexistence as a jump of `H(T)` (at fixed P) or `V(P)` (at fixed T) between a liquid-labelled and
a vapour-labelled single phase. As soon as a bracket straddles such a pair (one present component, dry), the kernel's
equal-fugacity temperature (PH: Newton on `ln phi_L - ln phi_V` with `d/dT = (h^R_V - h^R_L)/(R T^2)`, bisection once
bracketed, sided by the phase identification parameter where one root) or pressure (UV: `d/d ln P = Z_L - Z_V`) is
solved to 1e-14, both saturated states are evaluated, and the vapour fraction follows from
`target = N [(1 - beta) x_L + beta x_V]` (enthalpy for PH; volume at the fixed temperature for UV, whose outer
temperature search then closes the energy). With `beta` in [0, 1] (within the specification tolerance) the answer is
`VAPOR_LIQUID`, vapour then liquid, the smaller amount computed directly and the larger by difference (conserved to
rounding), `coexistenceFromBalance`, and the detail "pure coexistence at the equal-fugacity point (T K, P Pa): vapour
fraction beta from the energy (volume) balance"; its grade is the contract's, research-only in the pure band. Outside
[0, 1] the jump's end on the target's side becomes a bracket point and the search continues.

**Free water: where `H(T)` and `V(P)` have steps.** Under the network's rule (section 5) the transitions the WP6a
open items named are continuous: liquid water appearing (rule 2 at `required = n_w` gives `p_w = p_sat`,
`pc = P - p_sat`, which is rule 3's solution there), and the gas vanishing (the required steam goes to zero with the
gas volume). The rule jumps where the water boils at the state pressure with no gas phase on the liquid side (pure
water, or hydrocarbons all liquid): all liquid water below, all steam above. A bracket whose ends are exactly those two
sides (`waterStep`) triggers the balance: the water model's saturation temperature at `P` (PH; bisection on `p_sat`)
or its saturation pressure at `T` (UV), inside the bracket; the hydrocarbons liquid at `(T, P)`, the water split into
steam at `p_w = P` (the rule's limit `p_w = p_sat`) and free liquid water from the model at `P`, the steam fraction from
the energy (PH) or volume (UV) balance; result `FREE_WATER`, gas then free water, `coexistenceFromBalance`, the
free-water record with both water amounts. A target outside that span (with hydrocarbons, the steam side also
evaporates some hydrocarbon) lies in a part of the step no state of the rule has: typed `NOT_CONVERGED` once the bracket
closes on it. Nothing is extrapolated across a step.

**Merged near-critical fluid.** A merged split is one `SINGLE_FLUID` phase on the feed's root (section 3); the searches
treat it as one dry phase (its root's derivatives); it lies in the band, so its answers are research-only.

### W.4 Tests

Gradle run 2026-09-25 02:35, Git Bash, lock `build/gradle.lock` tag `wp6b`, no dev client (checked by command line),
other agents' uncommitted work (WP5) in the compiled tree:

    CREATECHEME_PHASE_COST=1 JAVA_OPTS=-Xshare:off ./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.phase.*' --offline

**36 tests, 0 failures, 0 skipped:** `CubicPhaseEvaluatorTest` 6, `FluidTpEquilibriumCostTest` 1, `FluidTpEquilibriumP3Test`
8, `FluidTpEquilibriumPhUvTest` 7 (new), `FluidTpEquilibriumTest` 7, `SolidPhaseEvaluatorTest` 2,
`SpinePackageEvaluatorTest` 3 (WP5's, uncommitted), `ThermoIdentityTest` 2. The final run is in W.8.

| Test (`FluidTpEquilibriumPhUvTest`) | What it establishes | Numbers |
|---|---|---|
| `methaneNitrogenRoundTripsAtTheSixtyStates` | TP -> (H, U, V) -> PH and UV on the 60 P2 states (12 two-phase): converged, same classification and phase count, T (and P) to 1e-9, H and U to 1e-6 N R T, V to 1e-6, conservation below 1e-13 | worst `dT/T` 5.6e-12, `dP/P` 2.4e-11, conservation 0; PH TP calls mean 6.6 max 16; UV mean 14.1 max 102 (one phase 1-2, two phases mean 66) |
| `networkBasisRoundTrips` | the same on the 20-component basis at 350 K / 0.5 MPa (two phases) and 900 K / 0.1 MPa (one) | worst `dT/T` 3.1e-12, `dP/P` 1.0e-13, conservation 1.6e-16; PH 6 and 2, UV 15 and 1 TP calls |
| `pureNitrogenCoexistenceTakesItsVapourFractionFromTheBalance` | pure N2 at 0.5 MPa: PH with `h = (1 - beta) h_L + beta h_V` and UV with the same `u`, `v`, at the kernel's equal-fugacity temperature (independent bisection), beta 5e-4, 0.3, 0.7, 0.9995: `VAPOR_LIQUID`, `coexistenceFromBalance`, T to 1e-12, P to 1e-9, vapour amount to 1e-8, H to 1e-9; 5 J/mol beyond either saturated enthalpy: one phase on the right side | T errors at most 2.5e-13, P errors at most 1.8e-12; PH 2-3 TP calls, UV 15-50 |
| `enthalpyIsContinuousAcrossBubbleAndDewPoints` | CH4/N2 0.5/0.5 at 1 MPa, 90-170 K by 0.5 K: `H(T)` increasing; each boundary refined by 60 bisections on the phase count, then 41 points 1e-4 K apart across it: increasing, no step (the slope across lies between the two sides' slopes, within 1e-3 and the specification), the slope jumps up at the bubble and down at the dew point; PH on all 82 enthalpies: T(H) increasing, T to 1e-9, conserved | bubble 114.515 K, slopes 63.1 / 238.8 / 413.6 J/K; dew 135.286 K, 544.8 / 292.4 / 38.7 J/K; PH worst `dT/T` 4.5e-12, TP calls mean 14.9 max 26 |
| `freeWaterRoundTripsAndBoiling` | wet round trips (WP6a's fixture: crude + N2, water/hydrocarbon 0.01 and 1, 340/460/580 K x 0.5/1.4 MPa, states the steam limit refuses skipped; WP6a's two cost states; pure liquid water 320 K 1 MPa, steam 450 K 0.1 MPa): free-water amounts recovered to 1e-8; pure water boiling at 0.1 MPa with steam fractions 0.25 and 0.8: `FREE_WATER` gas and free water, T the model's saturation temperature to 1e-9, steam amount to 1e-7 | 13 round trips (4 with free water, 5 all steam, 4 fixed), worst `dT/T` 8.6e-12, `dP/P` 3.4e-11, conservation 1.6e-16; PH mean 5.8 max 8, UV mean 16.4 max 47; boiling PH 2 and UV 26-37 TP calls, errors at most 6e-15 |
| `refusalsAreTyped` | N2 at `T/Tc, P/Pc` = 1/1, 1.001/1, 0.999/1, 1/1.001, 1.01/1.2: PH and UV of the TP answers research-only (a failure would have to be `CRITICAL_BAND`); budgets 2 to 12 at the critical point: every failure `NOT_CONVERGED` within its budget, `CRITICAL_BAND` exactly when its diagnostics say so, at least one; methane below its 293.15 K minimum (`OUT_OF_DOMAIN`, temperature below, naming Methane), PH at 3 MPa (pressure above), UV at a fifth of the volume (pressure); crystals, hydrates, ammonia, water without a model: typed unsupported before any search; a 2-call budget: `NOT_CONVERGED` after 2 TP calls | at the critical point PH converges in 9-21 TP calls to 2e-14, UV in 1 |
| `searchesAreBitwiseRepeatable` | 13 PH/UV requests (binary one and two phases, 20 components, pure N2 coexistence, wet three phases): fresh workspaces against one reused in reverse then forward order, bitwise equal T, P, amounts, v, h, s, `ln phi`, every counter, `specificationResidual`, free-water record, detail | all equal |

The P2 and WP6a tests pass unchanged apart from `FluidTpEquilibriumTest.contractRefusalsAreTypedAndCarryNoPhases`,
whose PH/UV "not implemented before P3" check became "water without a `FreeWaterModel` is not implemented".

### W.5 Cost

`FluidTpEquilibriumCostTest` in the run of W.4 (JDK 21.0.11, 16 processors, WP6a's method; PH/UV rows 2 x 300 warm-up
calls and 100 timed). Rows at the `(H, U, V)` of the TP state named:

| Request | Result | TP calls | Kernel calls (derivative) | Mean us | p95 us | TP mean us of the state | Bytes/call reused | New workspace |
|---|---|---|---|---|---|---|---|---|
| PH 2: 110 K 0.5 MPa x_N2 0.5 | VAPOR_LIQUID | 9 | 878 (106) | 112.9 | 120.6 | 18.2 | 37,176 | 46,776 |
| UV same | VAPOR_LIQUID | 77 | 8,070 (831) | 1,079.8 | 1,111.0 | 18.2 | 337,496 | 347,096 |
| PH 2: 120 K 2.5 MPa x_N2 0.3 | SINGLE_FLUID | 5 | 124 (15) | 16.8 | 16.8 | 3.3 | 7,192 | 16,792 |
| UV same | SINGLE_FLUID | 1 | 33 (3) | 4.6 | 4.6 | 3.3 | 1,880 | 11,480 |
| PH 20: crude + N2 350 K 0.5 MPa | VAPOR_LIQUID | 6 | 301 (58) | 226.8 | 230.0 | 38.4 | 29,200 | 94,800 |
| UV same | VAPOR_LIQUID | 15 | 738 (147) | 560.4 | 579.5 | 38.4 | 72,200 | 137,800 |
| PH 20: crude + N2 900 K 0.1 MPa | SINGLE_FLUID | 2 | 36 (10) | 35.1 | 35.7 | 13.9 | 4,000 | 69,600 |
| UV same | SINGLE_FLUID | 1 | 36 (5) | 24.2 | 24.4 | 13.9 | 2,672 | 68,272 |

The TP rows of the same run reproduce WP6a's within the machine's variation (18.2 against 17.8 us, 45.2 against
44.7 us). The printed header of that run lacked the new "TP calls" column; the rows carry it, and the header is fixed
in the source.

Reading against plan section 10: the plan budgets TP (p95 at most 50 us at 21 components two-phase, met) and the
per-node stability test, not PH/UV, which never run inside a network residual (plan section 7). PH stays within a few
TP calls: 2 to 9 here, mean 6.6 on the 60 states, at most 26 next to a bubble point. UV of one phase is 1 TP call (the
single-phase start is exact). UV of two phases from a cold start is not "a few": 15 calls on the crude, 77 on the binary
state (mean 66 over the binary's 12 two-phase states, up to 102). A PH or UV call allocates the results of the TP
answers it evaluates (37 kB and 337 kB for the binary two-phase rows with a reused workspace).

### W.6 Known limits

- **Two-phase UV from a cold start.** The start temperature (the larger of the single-phase and ideal-gas temperatures,
  both lower bounds for a split) can lie far below the answer (at the binary's 50 K window floor for liquid-rich
  splits), and there `V(P)` at fixed temperature is a near-step at the bubble pressure (the specified volume is a tiny
  vapour fraction), so each far temperature's pressure solve costs 10 to 25 TP calls. A caller with a previous state
  (inventories) would start next to the answer: open item.
- The nested UV budget counts outer steps and each pressure solve separately, so its worst case is
  `maximumOuterIterations^2` TP calls: bounded, not tight (the tests never exceed 102 in all).
- PH/UV answers are TP answers: their precision is the flash's (fugacity 1e-10), seen in the round trips as 1e-11 to
  1e-12 in T and 1e-11 to 1e-13 in P; the specification is met to 1e-6 N R T and 1e-6 in `ln V` at least.
- Free water: the part of the boiling step where hydrocarbons would evaporate into the steam is typed `NOT_CONVERGED`
  (the rule has no state there); the boiling state has the hydrocarbons liquid at `P` and the steam at `p_w = P`. A pure
  hydrocarbon's own coexistence together with water is not solved from a balance (WP6a's limit carried: the branch pair
  picks the lower branch).
- The re-typing of a TP failure inside the pure band is exercised by construction only (no tested state makes the kernel
  refuse a TP answer); the budget test covers `CRITICAL_BAND` for search failures.
- Mixture PH/UV inside the mixture band carry the TP answer's research-only grade; nothing mixture-critical is computed.

### W.7 Open items for WP7

- A start hint for UV (and PH): `InventoryEquilibrium` knows an inventory's previous `(T, P)`; a hint in the request
  (P2's `EquilibriumRequest` has none; adding one is a request-contract change) would make two-phase UV a few TP calls
  instead of 15 to 100. The alternative is Michelsen's (1999) state-function flash with analytic equilibrium
  sensitivities.
- `InventoryEquilibrium.solve` (a UV Newton with `flashTP` as regime oracle) can call `uv` for its regime decision (plan
  section 7); the network's own UV/PH rows (`PhaseLayout.balanceRows`, `junctionRows`) stay direct phase evaluations:
  PH/UV never run inside a residual.
- The production `FreeWaterModel` over `FluidThermodynamics` (WP6a open item) serves PH/UV unchanged; its liquid-water
  enthalpy and volume at `P` and its steam ideal-gas function are what PH/UV balance.
- A coexistence answered from a balance says so (`coexistenceFromBalance`, the detail); a consumer showing phase
  fractions must not take it for a TP split.

### W.8 Commit and final run

Commit `f116a78` (on top of WP5's `e4d355f`, committed meanwhile; the WP6b files compile against it). Final run
2026-09-25 02:40, same conditions as W.4 without the cost flag (`JAVA_OPTS=-Xshare:off ./gradlew test --tests
'com.wormzjl.createcheme.science.thermo.phase.*' --offline`): **36 tests, 0 failures, 1 skipped** (the cost probe),
WP5's committed tree compiled with it.

Tools (never tracked): `tools/phase-equilibrium-scans/wp6b/` (`compile.sh`, `run.sh`, `UvDebug.java`, `PhDebug.java`),
described in the folder's README. Measurement code to detach before merge: the eight PH/UV rows of
`FluidTpEquilibriumCostTest` go with the class (already listed for detachment).

## WP7: network integration

Date: 2026-09-25. Plan `P3_PILOT_ENGINE_PLAN.md` sections 2.4, 2.5 (with the WP4 outcome and the diagnosis), 6 (with the
WP6a corrections), 8.1 and the WP7 row of section 9; `P3_WP4_RUNTIME_DIAGNOSIS.md`; `P3_DIRECT_LIQUID_PATH.md`;
`P3_PILOT_PACKAGE_AND_SPINE.md` sections 12 to 19; decisions D8 to D13. Branch `claude/coolprop-multiphase-thermo-37f6b0`
on `271d84a` (WP9a landed during WP7; WP6b `f116a78`, WP5 `e4d355f`, WP4 `959ea0d`). Commit: see WP7.9. The phase
package (WP6b's) was consumed through its public API and not edited. Scratch probes, record scripts, Gradle logs and the
engine patch of WP7.2: `tools/wp7-network-integration/` (git-ignored, README there).

### WP7.0 Summary

- **The network's phases are the engine's.** `FluidThermodynamics.flashTP` (placement and boundary charges, the solver's
  seeds, the outer active-set check, the inventory refresh, `initialNitrogenCharge`) is an adapter over
  `FluidTpEquilibrium` TP with the network's own free-water model; the Wilson-started successive substitution and the
  `vaporBranch` root heuristic are gone, and `PhaseIdentification` is the one liquid/vapour rule (the liquid-root rule,
  the outer check's vapour test, a one-root phase's slot). Residuals still evaluate phases directly; no flash enters a
  Newton trial.
- **The two diagnosed runtime failures are fixed** and both runtime tests pass: the pumped six-tank start commits its
  first 5 s slice in one job (was held forever), and a pump connection's static column is its suction's density (E2),
  so the pumped rising line settles on the model's own steady state, 860,949.26 Pa, held to 4 Pa.
- **Pure-component vessels cross their saturation line.** A vessel of one pure hydrocarbon whose converged single phase
  lies across its saturation line takes the engine's UV coexistence (WP6b) instead of the flipped slot, so a nitrogen
  tank condenses, boils and leaves its dome; before WP7 (on the Wilson flash alike) it held with an active-set cycle.
- **The bundled network reaches 10 MPa** for nitrogen and methane (records' `fluid_domain` and the envelope); the eight
  bundled pins are unchanged. A near-critical nitrogen island crosses the band with every interval converged.
- **Critical-band failures are typed** (`FluidThermodynamics.CriticalBandHold`, its own rejection key) and go to the
  existing hold path. `FORMULATION` is `direct-liquid-v1:tp-engine-v1`; the network revision moved (fresh world); the
  legacy digest is re-printed with its reason.
- **Suites:** `fluidScienceTest` 215 (1 skipped) and `fluidRuntimeTest` 227 green, P12/P31 green, 30/30 fluid GameTests;
  `fluidSolverRegression` (declared) fails on chain-100 exactly as at WP4 (not re-captured). **Four WP6b phase tests
  fail** because they use 3 MPa nitrogen/methane as the network's out-of-domain example (WP7.8, item 1).

### WP7.1 What changed

| File | Change |
|---|---|
| `science/fluid/thermo/NetworkPhaseEngine.java` (new) | The engine of one network model: `FluidTpEquilibrium` on `PhaseContract.forNetworkPackage`, with the network's exact EOS (`CubicPhaseEvaluator.forPackage` for a spine package; otherwise the package's PR78 constants and constant interactions with `HydrocarbonModel`'s own translations), the spine-less package's `shifted_polynomial_5` fits (and low segments) as `IdealGasFunction`s on the sensible datum, and the production `FreeWaterModel` over `FluidThermodynamics` (saturation pressure, liquid water at P, steam limit, Shomate steam anchored at 188.835 J/(mol K)); thread-local engine workspaces; `tp`, `uv` |
| `science/fluid/thermo/FluidThermodynamics.java` | `flashTP` is the adapter (WP7.3); `phaseCheck(node, converged, checkpoint)`; `shortTieLine`; `coexistence` (of a node, or of an inventory); `onePurePhase`; `CriticalBandHold`; `STEAM_LIMIT`. **Removed:** the Wilson `splitHydrocarbon` and the flash's own partial-pressure bisection |
| `science/fluid/thermo/HydrocarbonModel.java` | `FORMULATION` `direct-liquid-v1:tp-engine-v1`; the liquid-root rule and the vapour flag through `PhaseIdentification`; `Phase.vaporBranch` became `Phase.vaporLike` (PIP at most one). **Removed:** the in-model PIP recovery from `Z`, `dv/dP`, `dv/dT`, and the co-volume arrays |
| `science/fluid/thermo/TranslatedPengRobinson.java` | `Values.phaseIdentificationParameter()` (on demand, from the root's own `v`, `a`, `b`, `da/dT` through `PhaseIdentification.parameter`), and `Phase` carries it. **Removed:** `vaporBranch` (`a/(RTb) < 5.877 or v/b > 3.95`) |
| `science/fluid/network/PassiveStepSolver.java` | E2 (WP7.2); `phaseCorrection` on `phaseCheck`, the PIP vapour test, the merging tie line, the pure-vessel coexistence regime; a `CriticalBandHold` names its node in the seeds, the junction restatement and the outer check |
| `science/fluid/network/InventoryEquilibrium.java` | Regime tests through `phaseCheck`; the coexistence regime of a pure inventory inside its dome (on the converged and the failed path) |
| `science/fluid/network/PassiveIntervalSolver.java` | A `CriticalBandHold` rejection is counted under `CriticalBandHold.REASON_KEY` |
| `science/fluid/transport/MixtureViscosity.java` | A supercritical component in the liquid slot above its liquid table and below any dissolved curve takes its dilute vapour value; revision tag `supercritical-liquid-slot-dilute-v1` (WP7.4) |
| Records | `properties/nitrogen.json`, `properties/tjl20_methane.json`, `packages/tjl20_nitrogen.json`: 10 MPa; the six `crude_19` packages' envelopes to 10 MPa; stale "2 MPa liquid reference" evidence in 18 crude and light-end records and `tjl19.json`; the network package's stale `NITROGEN_CRYOGENIC` advisory numbers (WP7.4) |
| Tests | New `NetworkPhaseEngineTest` (5), `NearCriticalNitrogenIslandTest` (5); updated `LegacyNetworkPathPinTest`, `SpineNetworkPathTest`, `HydrocarbonModelTest`, `DirectLiquidContinuityTest`, `TranslatedPengRobinsonDerivativesTest`, `TranslatedPengRobinsonPairInteractionsTest`, `ThermoDomainViolationTest`, `FluidNitrogenCryogenicTest`, `MixtureViscosityTest`, `TraceTruncationLayoutTest`, `SharedSourceDepletionQualificationTest`, `ElevatedBlockLineIslandTest`, `FluidDeviceSpecDomainTest`, `FluidPacketCodecTest`; the benchmark GameTest's `INVALID` edit (`FluidServerBenchmark`) |

### WP7.2 The two diagnosed fixes

**(a) The trace-water flash.** The diagnosis located 99.2 % of the pumped six-tank start's 2,084,304 checkpoints in the
retired flash: each partial-pressure bisection step of an unsaturated gas restarted a cold Wilson split (about 31
iterations). WP7 retired that flash, so the fix lands where the partial-pressure solve now lives: the engine's
`solveWet` runs the same bisection, but each step is a stability test and one vapour evaluation (a single-phase gas
needs no split), 85 kernel evaluations for a whole trace-water flash. The fixed-point start the diagnosis proposes sits in
`FluidTpEquilibrium.solveWet`, WP6b's file, which WP7 does not edit; it is measured in a scratch copy and handed over as
a patch (`tools/wp7-network-integration/patches/`, open item WP7.8, item 2). Measured (probes of the diagnosis on the WP7
classes, same machine, JDK 21.0.11):

| six-tank first 0.05 s slice | `959ea0d` (diagnosis) | Wilson + fixed point (diagnosis) | WP7 (engine) | WP7 + engine fixed point (scratch) |
|---|---|---|---|---|
| checkpoints | 2,084,304 | 200,371 | 214,660 | 36,961 |
| warm wall per slice (12 runs, last 6) | 1,725 ms | 205 ms | 103 to 107 ms | 76 ms |
| substeps accepted/rejected, Newton iterations | 46/22, 1,509 | 46/22 | 46/22, 1,509 | 46/22 |
| first tank at the slice's end | 100,447.5 Pa, 288.147 K | same | same | same |

Per flash (warm, 2,000 calls): N2 with 1e-12 water 763.6 to 23.5 us (895 to 85 checkpoints), N2 with 1 % water 718.4
to 20.3 us, pure N2 30.5 to 2.4 us, wet crude + N2 at 350 K / 150 kPa 48.8 to 44.4 us, dry crude + N2 48.8 to 37.3 us;
the phase states agree with the Wilson flash's to the ten printed digits on all five. The test rig (1 us per checkpoint,
2 s wall budget): six-tank fill one job of 958,039 checkpoints, the whole first 100-tick slice committed at online tick
100 (was 11 jobs, 22.0 s, never committed); three-tank fill to shutoff 53 jobs, 0.3 s of work (was 58 jobs, 8.2 s);
vented chain 120 jobs, 0.3 s, 12,000 of 12,000 ticks committed (was 125 jobs, 8.2 s, 11,980); gas transfer 3 jobs.

**(b) E2.** `PassiveStepSolver.Equations.headDensities`: a pump connection's column is its suction's density, the density
every other pump expression reads; the column rule (which chose the discharge tank's lighter mixture once a closed pump
balanced it) no longer applies to pumps. Flat islands multiply a `dz` of zero, bit for bit. `ElevatedBlockLineIslandTest`
now expects the model's own shutoff state, `P_j + setting rho_j / rho_ref - rho_j g (LIFT - 1)` with
`P_j = P_g - rho_g g` (860,949.26 Pa by the test's own `flashTP` densities; the probe's tank 860,949.28 Pa), within
`1e-5 P_g` = 4 Pa, the passive lines' tolerance (was the generator's pressure plus the setting less four blocks, within
90 Pa, which ignored the limit's density scaling).

### WP7.3 The outer check on the engine

**Adapter.** `flashTP(t, p, overall)`: the domain check first (the `ThermoDomainViolation` it always was); pure water
keeps the rule's first line (no phase decision); otherwise one engine TP request (`FLUID_ONLY`, the contract basis),
stated as a network `State` by the network's own `state(...)` on the engine's amounts. Slots: the lighter phase of a
split is the vapour slot, the denser the liquid slot; a three-root phase its root's slot; a one-root phase the engine's
PIP label; a `FLUID`-labelled phase (pure supercritical, merged) the slot of its own PIP. `FREE_WATER`: the record's
liquid water, steam and `pc`. `PURE_COEXISTENCE_UNDERDETERMINED`: a standalone flash states the liquid (the retired
flash's choice at `K = 1`). Refusals: `OUT_OF_DOMAIN` is the violation, except the steam limit, which stays `state()`'s
own `STEAM_LIMIT` refusal (the network throws the same plain one in every residual); `NOT_CONVERGED` inside the band is
`CriticalBandHold`, otherwise a plain `IllegalArgumentException`. Checkpoints: one before the engine and one per kernel
evaluation it reports (the engine has none of its own; one call is bounded).

**The outer check** (`PassiveStepSolver.phaseCorrection`). A converged node is skipped when both hydrocarbon slots (or
none), both water slots (or none), its vapour is vapour-like (`Phase.vaporLike`, PIP at most one) and its split is not
below the engine's merging tie line; otherwise `phaseCheck`, which is `flashTP` of the node's totals at its (T, P),
except at a pure component's own coexistence: a converged node keeps its layout, a node of a failed pass keeps its
larger phase (the equilibrium row of a pure two-phase node pins it to the line, where a TP answer can never drop the
vanishing phase). A single-phase node's check is therefore the engine's stability test. The merging rule (P3 section
6.2) re-tests a converged two-phase node whose split has `sum z (ln K)^2 < mergeTieLine`; a single component never
qualifies (its `ln K` is zero at coexistence), and a component held by one phase only makes the split long.

**Pure-component vessels.** A pure component's TP answer is one phase on either side of its saturation line, so a vessel
whose inventory lies in its dome flipped between an all-liquid and an all-vapour layout at every pass: the active-set
cycle, reproduced on `f116a78` with the Wilson flash (a nitrogen tank filled from a 95 K generator held at the sixth
interval, as it did on WP7's engine path before this rule). Now a converged single-phase vessel of one pure hydrocarbon
(no water, no solids) whose TP answer is the other slot asks the engine's UV for its inventory (network energy carried to
the engine's datum by the spine formation enthalpies, D10) and, when that is a coexistence, takes the two-phase seed at the
equal-fugacity point; the network's own rows then close it. The inventory refresh does the same on its converged and
failed paths. Measured: the tank condenses at 98.5 K / 0.70 MPa, its vapour vanishes at the dome's liquid edge and it is
compressed to the generator's 1.9 MPa; a liquid tank vented to a void boils at 89.7 K / 0.353 MPa and cools as it boils;
a refresh from an all-liquid state of an inventory of 30 mol liquid and 10 mol vapour returns 29.995 / 10.005 mol at
99.99 K, 780.2 kPa.

**Critical band.** An engine `NOT_CONVERGED` with `criticalBand` is `CriticalBandHold` ("Critical band: the phases at T K, P
Pa (package ...) at node N did not converge inside the declared critical band; CRITICAL_BAND (...)"); the solver names
the node, the interval counts it under `critical-band: phases not converged inside the declared critical band`, and an
interval that cannot pass it is held on the existing numerical hold path (halved slice, deferred retries, the reason in
the island's status). The engine's UV in the coexistence regime is typed the same way.

**Determinism.** The engine's per-thread workspace, reused forwards then backwards over ten states, gives the same bits
as a fresh workspace and as a fresh model (`NetworkPhaseEngineTest`); the near-critical island is bitwise the same on a
model whose engine workspaces carry earlier islands and on a fresh model (30 intervals, `NearCriticalNitrogenIslandTest`).

**Answered.** The WP5 open case CH4/CO2 80/20 at 230 K and 2 MPa is a single vapour (the Wilson flash did not converge),
and 81 neighbouring states (222 to 238 K x 1.6 to 2.4 MPa) are all answered; the WP5 pilot grid has 500 states evaluated
(17 two-phase), 222 refused by the domain, 4 by the steam limit, none unconverged (was 499 and one Wilson failure).

### WP7.4 The envelope

| Record | `fluid_domain` pressure maximum | Evidence |
|---|---|---|
| `properties/nitrogen.json` (`fluid_nitrogen`) | 2 to 10 MPa | P1/WP4 direct liquid path: Psat +1.21 %, saturated liquid -0.07 % at 77.355 K; compressed liquid to 10 MPa within 0.19, 0.34, 1.15 % at Tr 0.6, 0.7, 0.8 (3.55 % at 0.9), -0.17 % at 77.36 K / 10 MPa; the band research-only; dense-state viscosity a reference-pressure value |
| `properties/tjl20_methane.json` | 2 to 10 MPa | a supercritical gas above its 293.15 K floor, outside every band box; +0.95 % density at 300 K / 10 MPa (P1) |
| `packages/tjl20_nitrogen.json` (network envelope) | 2 to 10 MPa | nitrogen and methane to 10 MPa, the other components their own 2 MPa |
| the six `crude_19` packages (`tjl20_methane`, `bonga`, `cold_lake_blend`, `dalia`, `upper_zakum`, `wti_light_export`) | 2 to 10 MPa | required, not chosen: they carry the methane record, and the loader refuses a component range outside its package's envelope; each cut keeps its own 2 MPa, so no crude state above 2 MPa becomes valid |
| 18 crude and light-end records, `tjl19.json` | unchanged (2 MPa) | the stale "PR78 at the 2 MPa liquid reference with the global compressibility response" replaced |

The network package's `NITROGEN_CRYOGENIC` advisory now reads WP4's numbers. Pins: `PilotCryogenicCatalogTest` (8 tests)
green after the edits, the eight bundled scientific revisions and physics fingerprints unchanged (printed before and after
by `Wp7PinProbe`); `fluid_domain` and evidence are hashed only into the fluid thermodynamic fingerprint, which moved
(`f5e0178e...` to `95cd8e6d...`). Domain-message tests: `ThermoDomainViolationTest` (a nitrogen override now 20 MPa to
exceed the envelope; a new test: N2 at 5 MPa and liquid at 110 K / 8 MPa and CH4 at 10 MPa evaluate, N2 at 12 MPa and a
cut at 3 MPa are refused with their messages, the envelope refuses 11 MPa), `FluidDeviceSpecDomainTest` (a new test: a
compressed-liquid nitrogen tank at 110 K / 8 MPa, a 12 MPa refusal with its message, a crude generator at 3 MPa naming its
cut), `FluidPacketCodecTest` (the out-of-domain control 3 to 12 MPa and its message), `FluidNitrogenCryogenicTest` (the
envelope's maximum); `FluidThermoDomainHoldTest` green unchanged.

**Transport in the widened range.** With the PIP slot, a dense supercritical nitrogen (for example 130 K / 6 MPa, PIP
4.97) is liquid-like and sits in the liquid slot, above nitrogen's liquid viscosity table (which ends at its 126.192 K
critical point) and below its dissolved-solute curve (from 273.16 K): the first near-critical islands held there with
"Viscosity state is outside the correlation's temperature/pressure domain". Such a component now takes its dilute vapour
viscosity, the vapour slot's value of the same root, so the PIP flip does not jump a pure fluid's viscosity
(`MixtureViscosityTest`); nothing below the critical point, inside the dissolved curves, or refused before as a pure
conditional solute changes. Declared per plan section 10 (dense-fluid transport unqualified above 2 MPa).

### WP7.5 Tests and Gradle runs

All from Git Bash, `JAVA_OPTS=-Xshare:off`, `--offline`, under `build/gradle.lock` (tag `wp7`), no dev client (checked by
command line before each run), 2026-09-25; logs in `tools/wp7-network-integration/out/`. Test classes were also run with
javac and a JUnit launcher before (`Wp7RunTests`).

| Run | Result |
|---|---|
| `fluidScienceTest` + `test --tests PilotCryogenicCatalogTest` (first) | 214 tests, 1 failure (`TraceTruncationLayoutTest`: the decoded water vapour 1 ulp from the seed's; now held to 4 ulps like the component totals), 1 skipped; pins 8/8 |
| `fluidRuntimeTest` (first) | 227 tests, 2 failures (`FluidPacketCodecTest`: the 3 MPa nitrogen control is valid now; moved to 12 MPa) |
| `fluidSolverRegression` (declared) | chain-100 fails as at WP4 (WP7.6); island fixtures skipped (no `build/probe`) |
| final: `fluidScienceTest fluidRuntimeTest test --tests PilotCryogenicCatalogTest --tests 'science.thermo.*'` | **`fluidScienceTest` 215 tests, 0 failures, 1 skipped** (the env-gated WP4 probe; `SpineNetworkPathTest` with WP9a's records green); **`fluidRuntimeTest` 227, 0 failures** (P12 `WorkerTrajectoryEquivalenceTest`, P31 `CadenceTrajectoryQualificationTest`, `FluidPumpedFillLineTest` 6, `ElevatedBlockLineIslandTest` 4, `FluidThermoDomainHoldTest` green); `test` 104 tests, 2 skipped, **4 failures in WP6b's phase tests** (WP7.8, item 1); `PilotCryogenicCatalogTest` 8/8 |
| `runFluidGameTestServer -PfluidGameTestRunId=wp7-p3-20260925` (fresh world) | **30 of 30 required GameTests passed** |

New tests: `NetworkPhaseEngineTest` (the adapter conserves and states the engine's amounts with the network's own
evaluations over ten states, free-water closure, PIP slots; reused against fresh workspace and fresh model bitwise; the
WP5 case and its neighbourhood; the typed critical-band failure with a limited engine on the P2 near-critical state
N2/CH4 150 K / 4.56 MPa and the plain refusal outside the band; pure coexistence and the merging rule) and
`NearCriticalNitrogenIslandTest` (below).

**Near-critical nitrogen island** (0.1 m3 tank, 1 m pipe, generator, 5 s intervals): (1) compressed liquid 118 K / 3 MPa
fed from 125 K / 7 MPa through 2 mm: 30 intervals, all converged, through the main box at 120.1 to 121.2 K x 4.36 to
5.08 MPa, out of it to 123.8 K / 7 MPa; (2) supercritical 140 K / 3.5 MPa fed from 122 K / 7 MPa through 4 mm: it cools
into the main box, condenses there (two phases at 123.1 to 125.3 K, 2.94 to 3.26 MPa), loses its vapour at 125.3 K,
leaves the main box at 5.4 MPa and settles at 135.8 K / 7 MPa (inside the band's third box, Tr 1.08 x Pr 2.06): 40
intervals, all converged, no hold of any kind; (3) the condensing and boiling vessels outside the band (WP7.3); (4) the
dome refresh; (5) bitwise repeatability. The test holds any hold to be typed and inside the band; none occurred.

### WP7.6 Regression deviations (chain-100, declared, not re-captured)

| Quantity | WP4 | WP7 | declared gate |
|---|---|---|---|
| moles, mass (relative) | 2.87e-5 | 2.872e-5 (node 95, `moles[17]`) | 1e-6: fails |
| temperature | 7.46e-6 K | 7.464e-6 K | 1e-4 K: passes |
| phase fraction (liquid volume) | 4.39e-5 | 4.393e-5 (node 100) | 1e-6: fails |
| pipe flow (relative) | 2.04e-4 | 2.039e-4 (pipe 62, 2.78e-6 kg/s) | 1e-3: passes |
| substeps accepted/rejected | 37/1 | 37/1 | |

The deviation is WP4's: the interval never flashes (`flashCalls` 0, every node two-phase and complete), so the engine
changes chain-100 only through the fixture's initial charges, below the printed digits. Counters: Newton iterations 530,
backtracks 14, residual evaluations 621, Jacobian builds 24, block fallbacks 0, state calls 180,800, allocated 1033.5 MB
(WP4 1030.9), wall 1285 ms (WP4 1264; one cold interval, not evidence). WP11 re-captures chain-100 once, with WP4's and
this table.

### WP7.7 Known limits

- A vessel filled from a generator to exactly the generator's pressure can stall on the approach to zero flow ("Newton
  iteration limit" at the same residual for every step size), for example nitrogen 1.5 to 2.0 MPa at 298.15 K; it
  reproduces on `f116a78` with the Wilson flash and depends on the exact pressures (1.9, 2.1, 2.5, 3.0 MPa pass). It met
  the first near-critical designs outside the band; the test islands fill through a narrow pipe instead.
- The coexistence regime covers one pure hydrocarbon without water in a vessel. A pure hydrocarbon at its own coexistence
  beside free water, and a zero-holdup junction of one pure component in its dome, keep the TP answer.
- Critical-band holds are reached by construction only (a limited engine); with the default engine no tested network
  state fails inside the band, as WP6a's scans predicted.
- Dense-fluid viscosity is a dilute value in the liquid slot above a component's critical point (WP7.4).
- `FluidTpEquilibrium`'s wet all-steam branch still bisects (about 27 hydrocarbon TPs per trace flash); WP7.2.
- The spine package's steam enthalpy in the free-water model is on the network's sensible datum, the evaluator's on the
  formation datum: TP phase decisions do not depend on it, a PH/UV of a wet spine state would (not called by the network).

### WP7.8 Open items

For the lead and the engine owner (WP6b's files):

1. Four phase tests pin the old 2 MPa network envelope and need the owner's update (verified: with the WP7 classes and the
   pre-WP7 records all 14 tests of the two classes pass): `FluidTpEquilibriumTest.contractRefusalsAreTypedAndCarryNoPhases`
   (methane at 350 K / 3 MPa as the out-of-domain example; above 10 MPa it still is) and
   `pureNitrogenIsSinglePhaseAwayFromCoexistenceAndUnderdeterminedAtIt` (pure nitrogen at 150 K / 6 MPa expected outside
   the network domain; it is inside now); `FluidTpEquilibriumPhUvTest.refusalsAreTyped` (PH of methane at 3 MPa, and UV at a
   fifth of the volume, expected pressure-above) and
   `pureNitrogenCoexistenceTakesItsVapourFractionFromTheBalance` (the UV search now brackets in the 10 MPa window and
   returns the coexistence temperature 7.6e-12 relative from the equal-fugacity point, against the test's 1e-12).
2. The fixed-point start in `FluidTpEquilibrium.solveWet` (patch and measurement in the tools folder): the six-tank start
   from 214,660 to 36,961 checkpoints.

For WP9 (G3): fixture F4/F10 on the network now to 10 MPa for N2 and CH4 (compressed liquid N2 and dense supercritical
states evaluate; transport declared); F8's closed-vessel compression can run on the bundled package; the coexistence
regime makes a nitrogen vessel crossing its saturation line a candidate fixture; the band's third box contains the
near-critical island's end state (research-only grade, converged).

For WP11: re-capture chain-100 (WP7.6); detach `tools/wp7-network-integration/` (never tracked; the two new test classes
are gates and stay); decide the generator-fill stall of WP7.7 (pre-existing); the benchmark pair (60 s + 60 s) was not
run: the runtime harness has no pumped-fill profile, so WP7 reports the checkpoint counters and warm wall of WP7.2.

### WP7.9 Commit

Commit `8e5274e` on `271d84a` (WP9a): 53 files (8 main sources, 28 records, 16 tests, 1 GameTest source), explicit
paths only; this document and `tools/wp7-network-integration/` are git-ignored. The commit carries the session's own
co-author line (Claude Opus 5.5), as WP5 did. The Gradle runs of WP7.5 ran on the committed tree (the working tree
was unchanged between them and the commit except this document). No push; no INDEX or CHANGELOG edit (WP11).

## WP7b: the engine's fixed-point start and the 10 MPa phase tests

Date: 2026-09-25. The two engine-side open items of WP7.8 (items 1 and 2). Branch
`claude/coolprop-multiphase-thermo-37f6b0` at `8e5274e` (WP7). Files: `FluidTpEquilibrium.java` (`solveWet`'s start, one
constant, the class comment's free-water sentence), `FluidTpEquilibriumTest`, `FluidTpEquilibriumPhUvTest`, and, outside
the brief's list, `LegacyNetworkPathPinTest` (WP7b.3). Probes, outputs and Gradle logs: `tools/wp7b-engine-fixed-point/`
(git-ignored, README there); the six-tank probes are WP7's, reused unchanged. Commits: WP7b.5.

### WP7b.1 The fixed-point start as applied

WP7's scratch patch without its switch, same arithmetic. When the saturated try at `pc0 = P - p_sat` shows the gas
unsaturated (`required > n_w`, `V_gas(pc0) > 0`), `solveWet` first tries up to `PARTIAL_PRESSURE_FIXED_POINT_STEPS = 3`
(new public constant) fixed-point steps: `pc = P / (1 + n_w R T / (V_gas(pc0) pc0))` (the saturated split's gas scaled
to the root as an ideal gas; always above `pc0`), then `pc = P - n_w R T / V_gas(pc)`. Each step is one hydrocarbon TP at
`(P, pc)` (stability test, split); a step is accepted only on the bisection's own stop test `|pc + p_w - P| < 1e-8 P`
(tighter than its `1e-6 P` acceptance), then the same steam-limit check and `wetResult` as the bisection. A failed
hydrocarbon TP, an answer without gas, or a step at or below the `1e-6 Pa` floor falls through to the unchanged
bisection. One guard is new against the scratch patch (the floor: a step can leave the bracket when `V_gas` collapses
across a dew point, where the scratch version would have evaluated a negative pressure); the six-tank slice reproduces
the scratch patch's checkpoint count and end states exactly. States with `P <= p_sat + 1e-6 Pa` (no saturated try) keep the bisection. No state crosses calls,
nothing is allocated (scalars only).

Measured on JDK 21.0.11 with javac/java outside Gradle (class snapshot of `8e5274e`'s build, the patched class first;
`DiagPumpedFillProbe` and `DiagRigProbe` of `tools/wp7-network-integration/`):

| six-tank chain `GUPRPRPRPRPRPR` | `8e5274e` | WP7b |
|---|---|---|
| first 0.05 s slice, checkpoints | 214,660 | **36,961** (WP7's scratch number) |
| same, warm wall per slice (12 runs, last 6; another agent's work on the machine, indicative) | 110.7 ms | 68.0 ms |
| substeps accepted/rejected; Newton iterations; implicit solves; flash calls | 46/22; 1,509; 207; 3,914 | 46/22; 1,507; 206; 3,895 |
| end states of the slice | first tank 100,447.5 Pa, 288.147 K | every node equal to the printed digits; slice-mean flows within 1.4e-5 relative |
| the test's rig (1 us per checkpoint): first 100-tick slice | 1 job, 958,039 checkpoints | **1 job, 177,479 checkpoints**, committed at online tick 100 |

`FluidPumpedFillLineTest` prints the rig's description only ("1 jobs, 0.2 s of work"), not the checkpoint count; the
rig row is the probe's.

### WP7b.2 The four phase tests (10 MPa domain)

| Test | Change and reason |
|---|---|
| `FluidTpEquilibriumTest.contractRefusalsAreTypedAndCarryNoPhases` | methane at 350 K / 3 MPa (the old refusal) is now asserted answered; the refusal example is 350 K / 12 MPa: `OUT_OF_DOMAIN`, `PRESSURE_ABOVE`, maximum 10 MPa (new assertion) |
| `FluidTpEquilibriumTest.pureNitrogenIsSinglePhaseAwayFromCoexistenceAndUnderdeterminedAtIt` | the network contract now answers pure N2 at 150 K / 6 MPa: asserted `SUPERCRITICAL_FLUID`, label `FLUID` (as the open contract); the refused state is 150 K / 12 MPa, answered `SUPERCRITICAL_FLUID` by the open contract and refused `PRESSURE_ABOVE` (maximum 10 MPa) by the network contract |
| `FluidTpEquilibriumPhUvTest.refusalsAreTyped` | PH of methane at 3 MPa and UV at a fifth of the volume (the old refusals) are now asserted `CONVERGED`; the refusals are PH at 12 MPa (`OUT_OF_DOMAIN`, `PRESSURE_ABOVE`) and UV at a twentieth of the volume (`OUT_OF_DOMAIN`, pressure) |
| `FluidTpEquilibriumPhUvTest.pureNitrogenCoexistenceTakesItsVapourFractionFromTheBalance` | UV's temperature tolerance 1e-12 to 1e-10 (PH keeps 1e-12; every other assertion unchanged), with the cause in a comment |

**The UV tolerance.** Traced with a debug copy of the engine (`tools/wp7b-engine-fixed-point/out/uv-trace-*.txt`): the
UV answer's temperature is the nested energy search's last TP-verified point; the search stops when the energy is
within `1e-6 N R T` and the next secant step is at most `outerTolerance T = 1e-11 T`, without taking that step. At
beta 5e-4 the 10 MPa window changes the path from the first temperature on (the inner pressure search at 98.07 K finds
a compressed liquid at 2.74 MPa instead of stopping at the old 2 MPa ceiling and bisecting the temperature bracket), and
it stops one 7.57e-12 T step short of the root (17 TP calls); the 2 MPa path's step before the last was 1.12e-11 T, so
it took one more and landed at 3e-16 (15 TP calls). The 1e-12 assertion was tighter than the search's own contract and
had passed by the path. A cheap tightening exists (take the final sub-tolerance step when the answer is a coexistence,
one more TP call) but it changes every coexistence UV answer, which the network's pure-vessel regime consumes (WP7.3),
for 1e-9 K; not done. The 1e-10 is ten times the search's step tolerance. Beta 0.3, 0.7, 0.9995 are unchanged
(2.5e-13, 1.4e-14, 1.1e-13).

### WP7b.3 The legacy digest pin (outside the listed files)

`LegacyNetworkPathPinTest` (in `fluidScienceTest`) hashes the free-water `flashTP` states of a fixed probe, so the fixed
point moves it: `b787934e...` to `f4e0b2a30b7c6ecaf211d0a6254fa8e5ad16121ca6911f482ee02dfc25fedf3b` (printed with
javac/java on JDK 21.0.11, JIT and `-Xint` equal, and by the Gradle run). Of the probe's 63 flash requests (31 answered,
32 refused), exactly one state moves, lean nitrogen at 350 K / 0.1 MPa (`pc` by 1.8e-4 Pa, 2e-9 relative; volume 2e-9);
every other hashed number and refusal keeps its bits (`Wp7bDigestStates`). The pin's own rule is to re-print it with
the reason for a deliberate formulation change, which was done (constant and javadoc only), in a separate commit so the
lead can drop it; without it `fluidScienceTest` fails on this one test.

### WP7b.4 Gradle runs

Git Bash, `JAVA_OPTS=-Xshare:off`, `--offline`, under `build/gradle.lock` (tag `wp7b`), no dev client (checked by command
line before each run), 2026-09-25; logs in `tools/wp7b-engine-fixed-point/out/`.

| Run | Result |
|---|---|
| `test --tests 'com.wormzjl.createcheme.science.thermo.*'` | **96 tests, 0 failures, 2 skipped** (the two env-gated cost probes); WP7: 96 with 4 failures |
| `fluidScienceTest` (before the pin re-print) | 215 tests, 1 failure (`LegacyNetworkPathPinTest`, WP7b.3), 1 skipped |
| `fluidScienceTest` (final) | **215 tests, 0 failures, 1 skipped** (the env-gated WP4 probe), as WP7 |
| `fluidRuntimeTest` | **227 tests, 0 failures** (P12, P31, `FluidPumpedFillLineTest` 6, `ElevatedBlockLineIslandTest` 4, `FluidThermoDomainHoldTest`), as WP7 |
| `test --tests 'com.wormzjl.createcheme.runtime.FluidPumpedFillLineTest'` | **6 tests, 0 failures**; six-tank fill "online 100, committed 100, 1 jobs, 0.2 s of work, FULL" (WP7: 1.0 s) |

The two phase test classes and `FluidTpEquilibriumP3Test`, `NetworkPhaseEngineTest` also ran with WP7's JUnit launcher
outside Gradle first (14/14 and 13/13).

### WP7b.5 Commits, limits, side observation

Commits on `8e5274e`, explicit paths only, co-author line of the brief (Claude Fable 5.1): `aed9a9b` (engine and the two
phase test classes) and `181302e` (the pin re-print, WP7b.3; `aed9a9b` alone fails that one test). The Gradle runs of
WP7b.4 ran on exactly the committed content of these files. This document and `tools/wp7b-engine-fixed-point/` are
git-ignored. No push; no INDEX or CHANGELOG edit.

Limits: the fixed point runs only after a saturated try (`P > p_sat + 1e-6 Pa`); an all-steam state at or below the
water's saturation pressure (hot wet gas) still bisects, about 27 hydrocarbon TPs. WP7.7's "the wet all-steam branch
still bisects" is therefore resolved for an unsaturated gas above the water's saturation pressure only.

Side observation (pre-existing at `8e5274e`, not changed here; for the lead and the WP6a owner): on the network kernel
the phase identification parameter of dilute nitrogen exceeds 1 above about 620 K (1.000079 at 700 K and 0.1 MPa;
methane stays below 1 to 900 K), because `B - T dB/dT` turns positive; the engine then labels dry nitrogen at 650 and
700 K, 0.1 and 0.5 MPa `SINGLE_FLUID` `LIQUID`, so the network's slot rule puts a hot nitrogen gas in the liquid slot,
and in the wet rule a liquid-like gas has no vapour volume: pure N2 with 0.1 mol water at 700 K and 0.1 MPa comes back
with `pc` at the 1e-6 Pa floor and `p_w = P`, and the lean N2 mixture at 700 K is refused `CRITICAL_BAND`
(`tools/wp7b-engine-fixed-point/out/hot-gas-pip.txt`, `digest-states-*.txt`). A PIP threshold is not a gas/liquid test
for a dilute supercritical gas; a density or `T > Tc` guard on the one-root label would be the fix to decide.
(Decided and done in WP7c.)

## WP7c: the one-root label of a hot gas

Date: 2026-09-25. The side observation of WP7b.5 (lead's decision: a `Z < 1` guard on the one-root label). Branch
`claude/coolprop-multiphase-thermo-37f6b0` at `181302e` (WP7b). Probes, outputs and Gradle logs:
`tools/wp7c-hot-gas-label/` (git-ignored, README there). Commit: `f2d5221` (WP7c.5).

### WP7c.1 The rule and its physics

**A one-root state is liquid-like when `PIP > 1` and `PIP > Z`, i.e. `PIP > max(1, Z)`**, with `Z = P v/(R T)` of the same
untranslated root; vapour-like otherwise (an undefined parameter is neither). The three-root case is unchanged (the
stability root decides).

On the cubic, `B = b - a/(R T)`. In the dilute limit `Z - 1 = B/v` and `PIP - 1 = (B - T dB/dT)/v`, so
`PIP - Z = -T (dB/dT)/v`. `B - T dB/dT = b - 2 a/(R T) + (da/dT)/R` turns positive at high temperature, and then a dilute
gas has a parameter just above one:

- nitrogen above about 620 K at 0.1 MPa (about 510 K at 10 MPa) on both kernels (1.000079 at 700 K and 0.1 MPa);
- methane on the pilot kernel above about 1000 K (on the network kernel it stays below one to 900 K);
- carbon dioxide on the pilot kernel above about 1150 K.

Such a gas has `B > T dB/dT > 0`: its `Z` is above one and above its parameter. `dB/dT > 0` holds wherever `a/T` falls,
which for PR78 means the alpha function is still decreasing (`sqrt(T/Tc) < 1 + 1/m`). For the bundled components that
holds to 1388 K (nitrogen is the lowest), above every fluid domain's 1200 K.

**Deviation from the brief, in the `Z >= 1` branch only.** The brief's rule was `PIP > 1 and Z < 1`, on the premise that
every liquid in the domain has `Z` well below one. The network package has a counterexample: its heaviest cut,
`crude_pc12`, at 293.15 to about 340 K and its 2 MPa maximum is a one-root compressed liquid with `Z` from 1.001 to 1.153,
`v/b` 1.01 to 1.02 and a parameter of 92 to 114. The engine labels it `LIQUID`, and the literal rule would have made it a
vapour: in the network, a liquid-root-absent refusal for a heavy liquid beside a gas. `crude_pc11` reaches `Z` 0.894 at
293.15 K and 2 MPa. `PIP > Z` keeps these states liquid. For `Z < 1` the implemented rule is the brief's exactly, and that
covers every state the brief cites (N2 at 130 K and 6 MPa: `Z` 0.31, `PIP` 4.97; N2 at 150 K and 10 MPa: `Z` 0.53,
`PIP` 2.71).

**Margins** (`Wp7cLabelGrid margins`). The probe scanned every one-root state with `PIP > 1` and `Z >= 1` on a 161 x 41
(T, ln P) grid of each composition's envelope, from 100 Pa: the 20 network components, the four pilot components, and
eight mixtures (the network and pilot equimolar mixtures, pc11+pc12, pc08 to pc12, the digest mixture, N2/CH4, CH4/CO2,
N2/CO2).

- States less dense than the cubic's critical volume (`v > 3.95 b`) have `PIP - Z` from -0.035 to -2e-8, never positive:
  14,249 states of N2, CH4, CO2 and their mixtures.
- The denser ones (crude_pc12 13 states, pc11+pc12 2 states) have `PIP - Z >= +91.5`.

### WP7c.2 Where the rule applies

| File | Change |
|---|---|
| `science/thermo/phase/PhaseIdentification.java` | `liquidLike(parameter, Z)`, `vapourLike(parameter, Z)` and their kernel-evaluation forms; the class comment states the rule and the physics above. The one-argument forms stay as "the parameter's own side of one" (not a label), because two tests outside this package's files use them (`TangentPlaneStabilityTest`, `HydrocarbonModelTest`). |
| `science/thermo/phase/FluidTpEquilibrium.java` | The one-root label (`singleLabel`), the one-root side of the pure coexistence searches (`coexistenceTemperature`, `coexistencePressure`), and the vapour-branch check of a split with branch pressures (`finishSplit`); comments. |
| `science/thermo/TangentPlaneStability.java` (outside the brief's list) | `admissible`, the one-root admission of the vapour branch at the partial pressure. **Required for the wet fix**: with the other four classes patched and this one not, pure N2 with water at 700 K still has `pc` at the 1e-6 Pa floor, and the lean gas is still `CRITICAL_BAND`. |
| `science/fluid/thermo/HydrocarbonModel.java` (outside the brief's list) | `liquidLike(values)` and the `Phase.vaporLike` flag. The adapter re-derives here the slot of a `FLUID`-labelled phase (`FluidThermodynamics.adopt`); the same method is the liquid-root rule and the anchor check, and the flag is the outer check's skip test. |
| `science/fluid/thermo/TranslatedPengRobinson.java` | Javadoc of `phaseIdentificationParameter()` only. |
| `NetworkPhaseEngine.java` | Unchanged: it does not re-derive the label. |

### WP7c.3 Results

**Grid** (`FluidTpEquilibriumP3Test.hotGasesAreVapourLikeOverTheGrid`): 0.1, 0.5, 1, 2, 5 and 10 MPa, every 50 K. Every state
converges to one phase. None is `LIQUID`-labelled (the "before" column counts `LIQUID` labels on `181302e`).

| Grid | States | `LIQUID` before | Largest PIP of a vapour-like state (its Z) | Liquid-like (`FLUID`) |
|---|---|---|---|---|
| network N2, 300 to 900 K | 78 | 24 (650 K and up, P < Pc) | 1.022429 (1.030299), 900 K, 10 MPa | none |
| network CH4, 300 to 900 K | 78 | 0 | 1.001976 (1.024367), 900 K, 10 MPa | none |
| pilot N2, 300 to 1200 K | 114 | 48 | 1.022836 (1.028011), 1000 K, 10 MPa | none |
| pilot CH4, 200 to 1200 K | 126 | 17 (1000 K and up) | 1.012300 (1.023087), 1200 K, 10 MPa | 200 K, 10 MPa: PIP 4.61, Z 0.360, v/b 2.23 |
| pilot C2H6, 350 to 1200 K | 108 | 0 | 0.999817 (1.000264), 1200 K, 0.1 MPa | 350 K, 10 MPa: PIP 1.81, Z 0.489, v/b 3.51 |
| pilot CO2, 350 to 1200 K | 108 | 6 (1150 and 1200 K) | 1.007315 (1.023417), 1200 K, 10 MPa | none |

154 of the 612 states have a parameter above one and are vapour-like, each with `Z` above its parameter. Vapour-like is
exactly "less dense than the cubic's critical volume" on this grid. The two liquid-like states are dense supercritical
fluids, denser than the critical density.

**The PIP table** of section 4 is unchanged (nine states, same classifications, labels and parameters). Three hot rows
were added: N2 at 700 K and 0.1 / 0.5 MPa is `SINGLE_FLUID` `VAPOR` (parameter 1.000079 / 1.000419, Z 1.000338 /
1.001691), and N2 at 700 K and 5 MPa is `SUPERCRITICAL_FLUID` `FLUID`, vapour-like.

**The rule on the cubic** (`theLabelRuleSeparatesAHotGasFromACompressedLiquid`):

- N2 at 700 K and 1 kPa: `PIP - Z = -2.600e-6 = -T B'/v`;
- `crude_pc12` at 293.15 K and 2 MPa: `Z` 1.1531, `PIP` 114.1, `v/b` 1.0125, `SINGLE_FLUID` `LIQUID`.

**Network, dry and wet** (`NetworkPhaseEngineTest.hotGasesTakeTheVapourSlotDryAndWet`; 600, 750, 900 K; with 0.1 mol water
per mol for the wet cases):

- **Compositions:** pure N2 and N2/CH4 90/10 to 10 MPa; the eight light components and the 20-component digest mixture to 2 MPa.
- **Answers:** 118 states answered; 2 refused, both typed by the steam approximation's limit.
- **Gases:** nothing in the liquid slot. The engine labels them `VAPOR` or `FLUID`, and the network flags are `vaporLike`
  and `liquidRootAbsent`.
- **Wet:** all steam, `pc + p_w = P`, `pc > P/2`. `p_w` is within 3.3 % of `y_w P` for the gases (their `Z` is within 4 % of one)
  and within 6 % for the heavy mixture.
- **1200 K:** every composition is refused as out of domain (the network package ends at 900 K), so the 1200 K network
  rows of the brief are that refusal.

**The wet 700 K cases, before and after** (0.1 mol water per mol, `Wp7bDigestStates`):

| State | `181302e` | WP7c |
|---|---|---|
| N2, 0.1 MPa | `pc` 1.0e-6 Pa, `p_w` 100,000 Pa | `pc` 90,912.15 Pa, `p_w` 9,087.85 Pa (0.1/1.1 of P to 3e-4) |
| N2, 0.5 MPa | `pc` 1.0e-6 Pa, `p_w` 500,000 Pa | `pc` 454,621.97 Pa, `p_w` 45,378.03 Pa |
| N2, 2 MPa | refused: steam limit (p_w = P) | `pc` 1,819,405.2 Pa, `p_w` 180,594.8 Pa |
| N2/CH4 90/10, 0.1 / 0.5 / 2 MPa | refused `CRITICAL_BAND` | `pc` 90,912.06 / 454,619.78 / 1,819,371.4 Pa |

Dry pure N2 at 650 to 900 K and 0.1 to 2 MPa moved from the liquid slot to the vapour slot. The 20-component mixture is a
real split at every one of these temperatures, and its liquid is unchanged.

### WP7c.4 Pins and Gradle runs

**Pins.** `LegacyNetworkPathPinTest` moved from `f4e0b2a3...` to
`778b87e5ff0613589a0010d39890640bebdafad2cacc9c2799887eec582d9fc6`. It was printed by `Wp7cPinDigest` on JDK 21.0.11; JIT
and `-Xint` agree, as does the Gradle run. Of the probe, only the 700 K states of pure N2 and of the lean N2/CH4 at 0.1, 0.5
and 2 MPa moved:

- 12 `HydrocarbonModel.phase` flag records: the vapour root is now `vaporLike`, the liquid root now `liquidRootAbsent`;
- their six free-water flash states (the table above).

The 450 K and cooler states, the digest mixture at 700 K, and every refusal text elsewhere keep their bits (the
flash-state listing diffs on those six lines only). The revision string is unchanged: the formulation tag is the same, and
this is a label fix on a fresh world. `SpineNetworkPathTest` did not move and was not edited.

**Gradle runs.** Each ran from Git Bash with `JAVA_OPTS=-Xshare:off --offline` under `build/gradle.lock` (tag `wp7c`), with
no dev client (checked by command line before each run), on 2026-09-25.

| Run | Result |
|---|---|
| `test --tests 'com.wormzjl.createcheme.science.thermo.*'` | **121 tests, 0 failures, 2 skipped** (the env-gated cost probes). This is 98 of this branch (WP7b's 96, plus the 2 new P3 tests) and 23 of WP9b's untracked `qualification/` tests, which were in the worktree and compiled with the suite. |
| `fluidScienceTest` | **216 tests, 0 failures, 1 skipped**: WP7b's 215 plus the new network test. `NearCriticalNitrogenIslandTest` 5/5, `LegacyNetworkPathPinTest` with the new digest, and `SpineNetworkPathTest` 7/7 all pass. |
| `fluidRuntimeTest` | **227 tests, 0 failures**, as WP7b: P12, P31, `FluidPumpedFillLineTest` 6, `ElevatedBlockLineIslandTest` 4, `FluidThermoDomainHoldTest`. |

Before Gradle, the changed and neighbouring classes ran with WP7's JUnit launcher outside Gradle: 17/17 and 48/48.

### WP7c.5 Commit and limits

The commit is on `181302e`, with explicit paths only and the brief's co-author line (Claude Fable 5.1). Its id is in the
report and in this document's last line. This document and `tools/wp7c-hot-gas-label/` are git-ignored. No push; no INDEX
or CHANGELOG edit.

Limits and open items for the lead:

1. The rule rests on `dB/dT > 0`, which holds while every carried component's alpha still decreases (to 1388 K for
   nitrogen). A package reaching past `(1 + 1/m)^2 Tc` of a component would need the guard revisited. The brief's `Z < 1`
   form has the same dilute-limit dependence, since `B < 0` with `B > T dB/dT` needs `dB/dT < 0`.
2. `TangentPlaneStabilityTest.bruteForceBranchMinimum` mirrors the vapour-branch admission with the one-argument
   `vapourLike`. Its states are cool, so both rules agree there. It was left as is, being outside the brief's files.
3. `HydrocarbonModelTest.thePhaseIdentificationParameterSeparatesLiquidAndVapourRoots` is unchanged and green. A hot-gas
   case there would duplicate `NetworkPhaseEngineTest`'s flag assertions.

Commit: `f2d5221` on `181302e`. It has 8 files (5 main, 3 tests), and the Gradle runs of WP7c.4 ran on exactly its content.

## WP7d: engine defects found by the G3 families, and the D15 bounds

Date: 2026-09-25. D15 of [DECISION_LOG.md](DECISION_LOG.md) (the four engine defects of
[G3_QUALIFICATION_REPORT.md](G3_QUALIFICATION_REPORT.md) section 7, the D15 bounds and box 2's widening). Branch
`claude/coolprop-multiphase-thermo-37f6b0` on `4eec712` (WP9b). A first agent did most of the work and was cut off by an
API rate limit with its edits uncommitted; a second agent reviewed them, fixed one test and one island assertion that the
band change moved, ran the suites and committed. Probes, outputs, the javac runner and the Gradle script:
`tools/wp7d-engine-defects/` (git-ignored, README there). Commit: `774cb82` (WP7d.6).

### WP7d.1 The defects: evidence, fix, test

**1. Untyped `NOT_CONVERGED` outside the band (F5): N2/CH4 0.67/0.33 at 150 K and 4.69 MPa.**

- Evidence (`out/f5-state-before.txt`, `out/f5-stability-trace-before.txt`): the state is one stable phase. A brute-force
  tangent-plane scan of `D(w)` on the lower of both roots over 19,999 compositions is nowhere negative and least at the
  feed. The feed has one root (Z 0.3304). The default stability test (100 iterations per trial) ends `UNRESOLVED` after
  220 iterations over four trials: the Wilson liquid-like trial crawls towards the trivial solution on the liquid root of
  compositions whose own Hessian is indefinite, so the test's Newton step is refused and substitution alone moves it; it
  reaches the trivial distance (1e-4) at its 186th iteration. With 300 iterations per trial the test resolves `STABLE`
  (501 iterations). The brief's preferred route, the second-order finish, does not apply: there is no split to finish.
- Fix (`FluidTpEquilibrium.UNRESOLVED_STABILITY_BUDGET_FACTOR = 10`): a feed test that ends `UNRESOLVED` is repeated once
  on ten times the per-trial budget (1,000); both tests are counted in the diagnostics, and a failure that remains names
  the longer budget. A feed the first test resolves is computed exactly as before (the pins did not move).
- Result: `CONVERGED`, `SINGLE_FLUID` labelled `LIQUID`, feed `STABLE`, outside the band by its rules (no stationary
  point other than the feed), 735 kernel calls (226 before, when it failed). WP9b's finer scan (150 K, x_N2 0.55 to 0.80,
  4.0 to 5.2 MPa, 3,146 states): 0 `NOT_CONVERGED` (1 before). A wider probe found seven more such feeds (155 K 4.86 MPa
  x_N2 0.61; 160 K 4.62, 4.92, 5.02 MPa; 165 K 5.04 MPa; 170 K 5.04 MPa; 180 K 4.98 MPa), all now single phases; the P2
  near-critical layout on the pilot evaluator (105,600 states): 7 `NOT_CONVERGED` outside the band before, 0 after
  (`out/scan-before.txt`, `out/scan-after.txt`).
- Test: `FluidTpEquilibriumP3Test.anUnresolvedStabilityTestIsRepeatedOnTheLongerBudget` (default test `UNRESOLVED`, the
  longer one `STABLE`, the engine's answer, the brute-force check with `D >= -1e-12` and its minimum at the feed to 1e-4,
  and the 3,146-state scan with no untyped failure).

**2. A reused workspace carried amounts of absent components (found in WP7d).**

- Evidence (`out/stale-amounts-before.txt`, `out/survey-failures-before.txt`): the Newton finish hands its vapour and
  liquid mole numbers to the kernel over the whole basis, and a component absent from the current feed kept the amount an
  earlier split had left in the workspace. After an N2/CH4 split at 150 K, CH4/CO2 at 250 K and 8 MPa ended "no
  convergence in 50 Newton steps"; after CH4/C2H6 at 220 K, N2/C2H6 at 100 K and 4 MPa found "no Gibbs-energy decrease".
  With a fresh workspace both converge (2 and 16 Newton steps). On a survey grid of the pilot network contract (the six
  binaries, 90 to 400 K by 5 K, 13 pressures 0.1 to 10 MPa, x 0.05 to 0.95 by 0.1: 49,140 states on one workspace) the
  classes of `4eec712` returned 152 `NOT_CONVERGED` (99 outside the band: 81 "no convergence in 50 Newton steps", 17 "no
  Gibbs-energy decrease", 1 unstable product; 53 typed `CRITICAL_BAND`); after WP7d, 1 (WP7d.5 item 3).
- Fix: `newtonFinish` zeroes `vaporMoles`, `liquidMoles`, `trialVapor` and `trialLiquid` before it starts.
- Test: `FluidTpEquilibriumP3Test.aReusedWorkspaceCarriesNoAmountsOfAbsentComponents` (seven feeds of four binaries,
  five splits and two single phases, forwards in the failing order and backwards on one workspace, bit for bit the fresh
  workspace's answers). The network's pins did not move, so none of their probes met the defect.

**3. A closed vessel past 10 MPa was `NOT_CONVERGED` (F8).**

- Evidence (`out/vessel-edge-before.txt`): the liquid-full CH4/C2H6 0.5/0.5 vessel (1.074e-4 m3/mol) heated at fixed
  volume on the pilot engine's UV: at 279.15 K and 9.508 MPa the next request (U = -86,748.28 J) came back
  `NOT_CONVERGED` "the specified internal energy lies in a step of U(T) at the specified volume between 282.2258025728906
  and 282.2258025754361 K" after 28 TP equilibria; later requests alternated between that and `OUT_OF_DOMAIN`, depending
  on the branch that saw the closed bracket. The nested search's temperature bracket had closed between an answered
  temperature (energy short of the specification) and a temperature known only by its isochore's pressure leaving the
  window (`Search.recordSide`); with one residual the closed-bracket branch reported a step.
- Fix (`nestedUv`): when the bracket closes with one end known only by the pressure window, the answered end is the answer
  if its residual is within the specification tolerance; otherwise the answer lies beyond the window's pressure bound:
  `OUT_OF_DOMAIN` in pressure with the component's violation at the bound ("Methane at 1.0000000000000002E7 Pa is above
  its valid range 100..10000000 Pa"). The search brackets against the domain, not the budget (100 TP equilibria).
- Tests: `FluidTpEquilibriumPhUvTest.aClosedVesselBeyondThePressureCeilingIsOutOfDomain` (the (U, V) of CH4/C2H6 0.5/0.5
  at 283 K 10.05 MPa, 285 K 10.3 MPa and 300 K 12 MPa, computed on an open research contract over the pilot evaluator,
  are refused on the pilot's network contract after 51, 55 and 49 TP equilibria, maximum 1e7 named; 283 K 9.95 MPa is
  answered and recovered to the round-trip tolerance); `G3F9DomainRefusalTest` (a closed-vessel row); `G3F8` (the
  vessel's first request past 10 MPa must be `OUT_OF_DOMAIN` naming 1e7).

**4. D14's corner missing from the engine's band, and D15's widening of box 2** (WP7d.2).

**5. A liquid-liquid split labelled `VAPOR_LIQUID` (F7).**

- Evidence (`out/liquid-liquid-before.txt`, `out/LiquidLiquid-after.txt`): CO2 in liquid methane at x_CO2 0.01 and 91 to
  100 K splits into a methane liquid (462 kg/m3, PIP 11.6, Z 0.02) and a CO2 liquid (1484 kg/m3, PIP 23.2); N2/C2H6 at
  95 K and 1 to 4 MPa (type III) into an ethane-rich (662 kg/m3, PIP 17.3) and a nitrogen-rich liquid (736 to 751 kg/m3,
  PIP 7.9). The engine labelled the lighter `VAPOR`, and the network put a liquid in its vapour slot.
- Fix: each product's WP7c label is taken from the root `lowerGibbsRoot` chose (no further kernel call); when both are
  liquid-like (`PIP > max(1, Z)`) and the split lies outside the band (pure-fluid box, or tie line `>= 0.1`), the result
  is the new `Classification.LIQUID_LIQUID` with both phases `LIQUID`, the lighter first. Inside the band the parameter is
  no label (section 4: the dense vapour of a near-critical N2/CH4 split is liquid-like by it), so such a split stays
  `VAPOR_LIQUID`. Splits with branch pressures (free water) are unchanged: their vapour branch is checked vapour-like.
  The adapter (`FluidThermodynamics.adopt`) refuses `LIQUID_LIQUID` before any slot is filled, with the new
  `FluidThermodynamics.UnsupportedPhases` (an `IllegalArgumentException` carrying
  `EquilibriumResult.UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED`, the package, T and P): a node carries one
  hydrocarbon liquid (D8: never answered with fewer phases). `PhaseAmounts`' Javadoc states the exception to the
  liquid/vapour labels.
- Survey (`out/label-survey-after.txt`): on the grid of item 2 plus the equimolar quaternary, `LIQUID_LIQUID` appears for
  N2/C2H6 (742 states), CH4/CO2 (2, 245 K 8 MPa) and the quaternary (2, 230 and 235 K 9 MPa); the pilot network over
  `SpineNetworkPathTest`'s grid (500 answered) and the bundled network over the WP6a field (3,237 answered) meet none.
- Tests: `FluidTpEquilibriumP3Test.twoLiquidLikePhasesOutsideTheBandAreLiquidLiquid` (the F7 states at 91, 95 and 100 K on
  a research contract; N2/C2H6 at 95 K and 2 MPa on the pilot network contract; a synthetic methane-like/ethane-like
  binary that splits with k_ij 0.3 and not with k_ij 0; the near-critical N2/CH4 split at 150 K and 4.64 MPa, both
  phases liquid-like by the parameter at tie line 0.010, still `VAPOR_LIQUID`);
  `NetworkPhaseEngineTest.aLiquidLiquidSplitIsRefusedTypedByTheNetwork` (the typed refusal through `flashTP` and
  `adopt`; the near-critical split keeps both slots); `G3F7` asserts `LIQUID_LIQUID` on its six splits.

### WP7d.2 The band as it now stands

A pure fluid (one component at least 0.95 of the amount), reduced by its own record constants, closed intervals
(`FluidTpEquilibrium.pureBandBox`, public; where boxes share an edge the lower number is reported):

| Box | Tr | Pr | Origin |
|---|---|---|---|
| 1 | 0.95 to 1.1 | 0.8 to 1.5 | plan section 3 |
| 2 | 0.90 to 0.95, and 0.95 to 0.97 | 0.58 to 0.74, and 0.58 to 0.8 | plan section 3; widened by D15 (the liquid between boxes 1 and 2 below Pr 0.8) |
| 3 | 1.05 to 1.2 | 2 to 3 | plan section 3 |
| 4 | 1.0 to 1.1 | 1.5 to 2 | D14's corner |

Pure nitrogen: box 4 is 126.2 to 138.8 K at 5.09 to 6.79 MPa, box 2's widening 119.9 to 122.4 K at 1.97 to 2.72 MPa.
Mixtures are unchanged: tie line `sum z (ln K)^2 < 0.1`, or a stationary point within 0.1 of the feed. Inside the band a
converged answer is research-only and a failure is typed `CRITICAL_BAND` (the network's `CriticalBandHold`).

Tests: `FluidTpEquilibriumP3Test.theBandCarriesD14sCornerAndD15sWidenedBoxTwo` (the boxes by reduced values and edges;
pure nitrogen of the pilot research-only inside, including the two G3 evidence states 131.19 K 5.10 MPa and 121.19 K
2.70 MPa, and estimated-with-declared-error just outside); `G3F5` (every corner state of its paths in the engine's band:
34 of 34 on each N2 isotherm above Tc); `G3Support.bandBox` carries the widened box 2 (the corner stays `d14Corner`,
scored at its 9 % declared density error by F1 and F4). `NearCriticalNitrogenIslandTest` encodes the four boxes; its
supercritical charge (140 K 3.5 MPa, fed from 122 K and 7 MPa) now runs from the main box through the corner (132.2 to
135.4 K, 5.40 to 6.80 MPa) into box 3 at 7 MPa (Tr 1.076, Pr 2.06), inside the band all the way, every interval
committed; its assertion "a converged state past the main box, outside the band" became "a converged state past the
main box, in D14's corner, and every state past the main box in the band and committed" (the only assertion the band
moved).

### WP7d.3 The D15 bounds in the qualification tests

Every "G3 proposal" label is replaced by the D15 bound with the D7 and D14 verdicts printed beside it; no held number and
no other assertion changed (apart from the defect assertions of WP7d.1 and the band of WP7d.2):

- `G3F1PureFluidOracleTest`: vapour cp at Z >= 0.9 12 % declared (the 2 % target kept at Z >= 0.98, printed per Z band
  as "2 % target: met / NOT met" beside the declared 12 %); compressed liquid density Tr 0.85 to 0.9 5 % declared next to
  saturation; supercritical cp 12 % declared next to box 1; ethane liquid cp below 150 K 12 % declared with D3's ethane
  Psat region.
- `G3F2GergBubblePointTest`: |dy| 0.025 declared for the CO2 pairs (D15: CO2-rich liquids within 5 K of 216.592 K); their
  clean rows outside that scope are printed against D7's 0.02 (CO2/CH4 9 rows, worst 0.0082; CO2/C2H6 36 rows, worst
  0.0139: met).
- `G3F10QualifiedRegionTest`, `G3F4HighPressureContinuityTest`, `G3Support` (density-rule labels): the D15 wording.

### WP7d.4 Gradle runs and pins

From Git Bash with `JAVA_OPTS=-Xshare:off --offline` under `build/gradle.lock` (tag `wp7d`, script
`tools/wp7d-engine-defects/gradle-run.sh`), no dev client (no `runMcpClient` or `fml.modFolders` command line), on
2026-09-25, on exactly the committed content:

| Run | Result |
|---|---|
| `test --tests 'com.wormzjl.createcheme.science.thermo.*'` (07:12) | **126 tests, 0 failures, 2 skipped** (the env-gated cost probes): WP7c's 121 plus four in `FluidTpEquilibriumP3Test` (now 14) and one in `FluidTpEquilibriumPhUvTest` (now 8); the ten G3 families 23 of 23 green |
| `fluidScienceTest` (07:13) | **217 tests, 0 failures, 1 skipped**: WP7c's 216 plus `NetworkPhaseEngineTest`'s new test (now 7); `NearCriticalNitrogenIslandTest` 5, `LegacyNetworkPathPinTest` 1, `SpineNetworkPathTest` 7 green |
| `fluidRuntimeTest` (07:14) | **227 tests, 0 failures** |

**Pins:** none moved. `LegacyNetworkPathPinTest` (`778b87e5...`) and `SpineNetworkPathTest` pass unedited. Before
Gradle the phase, qualification and network classes ran through the javac runner (`tools/wp7d-engine-defects/run.sh`):
22, 23 and 12 green.

### WP7d.5 Known limits and open items

1. The retry costs a second stability test only where the first ends unresolved (the F5 state: 735 kernel calls); no
   other feed pays for it.
2. `LIQUID_LIQUID` near the type III continuity: at 8 to 10 MPa the lighter product can be liquid-like by a small margin
   while less dense than the cubic's critical volume (N2/C2H6 at 195 K 10 MPa: PIP 1.048, Z 0.694, v/b 4.38; at 260 K
   10 MPa PIP 1.002; CH4/CO2 at 245 K 8 MPa PIP 1.203, v/b 3.96; the quaternary at 230 and 235 K 9 MPa;
   `out/lle-margins.txt`). The network refuses these as liquid-liquid, consistently with its one-root slot rule (WP7c),
   which would put that phase alone into the liquid slot. The lighter phase's v/b over all `LIQUID_LIQUID` answers of the
   survey runs continuously from 1.1 to 4.4 (no gap at the cubic's 3.95), so no threshold separates the two cases; a
   guard such as `v < 3.95 b` on both products would move the high-pressure ones back to `VAPOR_LIQUID`. Not applied:
   the rule is the brief's; the lead decides.
3. The one untyped failure left on the survey grid (`out/survey-failures-final.txt`): N2/C2H6 x_N2 0.95 at 125 K and
   3.0 MPa, "a product phase is not stable", a third fluid phase (the type III three-phase region), outside the band. P3
   has no three-fluid-phase answer; the network refuses it with a plain `IllegalArgumentException`. It lies outside the
   G3 families.
4. `UnsupportedPhases` is refused like any unevaluable state (a substep that meets it is rejected); the island is not held
   with a typed reason as for `CriticalBandHold`. Enough while no pilot network recipe reaches a liquid-liquid region
   (CO2 is refused below 216.592 K in production); P5 needs a liquid-liquid slot.

### WP7d.6 Commit

`774cb82` on `4eec712`: 17 files (4 main: `FluidTpEquilibrium`, `EquilibriumResult`, `PhaseAmounts`,
`FluidThermodynamics`; 13 tests: `FluidTpEquilibriumP3Test`, `FluidTpEquilibriumPhUvTest`, `NetworkPhaseEngineTest`,
`NearCriticalNitrogenIslandTest`, `G3Support`, `G3F1`, `G3F2`, `G3F4`, `G3F5`, `G3F7`, `G3F8`, `G3F9`, `G3F10`), added by
explicit path. This document, the report and `tools/wp7d-engine-defects/` are git-ignored. The commit carries the
session's model line (Claude Opus 5.5) as co-author, as AGENTS.md asks ("the attribution line given for the session"),
not the brief's Fable 5.1 line. No push; no INDEX or CHANGELOG edit.

## WP11: the WP7d.5 items

Date: 2026-09-25, commit `a3716ed`; details in `P3_PILOT_ENGINE_REVIEW.md` section 2. Item 2 (dense gas labelled liquid-liquid): both products must also have `v < 3.9513730355914 b` (`PhaseIdentification.VAPOUR_BRANCH_VOLUME_RATIO`, `denseLiquidLike`); N2/C2H6 at 195 K / 10 MPa and CH4/CO2 at 245 K / 8 MPa are `VAPOR_LIQUID`, the F7 and type III splits stay `LIQUID_LIQUID`. Item 3 (the untyped three-phase failure): a product proved `UNSTABLE` prefixes the detail with `EquilibriumResult.UNSTABLE_PRODUCT`; at x_N2 exactly 0.95 the state is inside the pure-fluid band rule (typed `CRITICAL_BAND` first), the survey's point was 0.9499999999999998. Item 4 (plain rejections): `UnsupportedPhases` (kinds `LIQUID_LIQUID`, `THREE_PHASES`) and `CriticalBandHold` are `FluidThermodynamics.PhaseHold`s, named at the solver's four catch sites and counted under their own keys, so the island holds with the reason (`UnsupportedPhasesIslandTest`). Suites: `science.thermo.*` 128 (2 skipped), `fluidScienceTest` 220 (1 skipped), `fluidRuntimeTest` 227; pins unmoved.

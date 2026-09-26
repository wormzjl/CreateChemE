# Free water on trays: what landed, what it measures, and the one gate that is still open

Date: 2026-09-08. Branch `claude/v3-literature-cdu-handoff-3179dc`, base `ce702bc`.
Implements `documentation/V3_FREE_WATER_TRAYS_PLAN.md`.

| WP | Commit | Subject |
|---|---|---|
| F1 | `64b634e` | Stop rejecting a converged column on the water dew point |
| F2 | `cc537ea` | Let water condense on a cold tray, fall and re-evaporate |
| F3 | `f6f3760` | Keep the halved top cooler and pin what the preset's water contract does |

Suite: **453 tests, 0 failures** (445 at `ce702bc`; 444 after F1, 453 after F2).

---

## 1. F1 — the typed verdict is gone

`V3SolverFailureCode.WATER_DEW_POINT` is removed, together with the `V3ColumnCalculator` branch that turned a
converged-but-dew-point-failing candidate into it and its listing among `V3TruncationFallback`'s terminal
admission failures. The `WATER_DEW_POINT` audit check keeps its name, its limit of one and its tray-naming
detail; F2 turns it into a regime-consistency check.

`V3TruncationFallbackTest` loses the one `@EnumSource` case the removed code contributed — that is the whole of
the 445 → 444 change. `V3LiteraturePresetTest` kept its geometry test and replaced the verdict test with a
placeholder documenting the F3 expectation; F2 and F3 replaced that placeholder in turn.

---

## 2. F2 — the wet-tray water phase

### 2.1 The water balance is local, and that is the whole design

Write the tray water balance with `W_n` the water vapour rising out of node `n`, `F_n` the aqueous liquid it
sheds downward, and `S_n` the authored steam fed there:

```
W_n = W_(n+1) + F_(n-1) + S_n - F_n
```

Substituting downward, every intermediate `F` telescopes away:

```
W_n = (steam fed at or below n) + F_(n-1) - F_R
```

with `R` the sump. **What condenses on a tray reaches the tray below and comes straight back up**, so it never
changes the water passing its own tray; the net water crossing any tray is the authored steam minus whatever
leaves the bottom. Three things follow, and they are what make the work tractable:

1. `V3ColumnProblem.waterVaporFlow(state, node)` needs no sweep — it is the existing authored upward profile
   plus the free water of the tray directly above (`F_0 = 0`, nothing above tray one sheds into it).
2. The condenser's arriving water is still `W_1` = the authored steam total. The regime (`NONE` /
   `FREE_WATER` / `ALL_VAPOR`), the slip coefficient and `waterCondenserSplit` are **bit-identical** to before,
   which is why `WATER_PROFILE`, `FREE_WATER_SPLIT` and `CONDENSER_ENERGY_BALANCE` needed no change.
3. Every new Jacobian coupling lands in the diagonal or the immediate lower block. A wet tray does not widen
   the band, so `V3BandedMatrix`, `V3BlockJacobian` and the off-band guard are untouched.

### 2.2 The unknown and the row

On a tray of the frozen wet set:

- **Unknown** `UnknownFamily.FREE_WATER_FLOW(node)`: `F_n`, strictly positive by definition, in the same
  scaled log-flow coordinate a component flow uses, scaled by the **total authored steam**
  (`V3ColumnProblem.freeWaterFlowScaleMolPerSecond()`, one on a dry column).
- **Equation** `EquationFamily.WATER_SATURATION(node)`, scale 1 like the VLE rows:

```
ln( P_n * W_n / ((V_hc,n + W_n) * P_sat(T_n)) ) = 0
```

Both are enumerated **last** in the node's ledger block, so `V3StageBlockLayout` gains exactly one unknown and
exactly one row per wet tray and stays contiguous by node.

Ledger references (`V3DegreeOfFreedomLedger`):

| Row | References added |
|---|---|
| `WATER_SATURATION(n)` | this node's vapour flows, `T_n`, and `FREE_WATER_FLOW(n-1)` — **not** its own `F_n`, which cancels out of `W_n` exactly |
| `VAPOR_LIQUID_EQUILIBRIUM(n, c)` | `FREE_WATER_FLOW(n-1)` (the water-dilution term reads `W_n`) |
| `ENERGY_BALANCE(n)` | `FREE_WATER_FLOW(n-1)`, `FREE_WATER_FLOW(n)`, `FREE_WATER_FLOW(n+1)` |

The structural-rank check stays honest: `F_n` is matched through the energy rows, `SAT(n)` through the node's
own vapour/temperature block. Full rank on the hand-built fixture is asserted.

### 2.3 Energy

Tray `n`, in watts, with the new terms in bold:

```
liquidIn + vaporIn + feed + **F_(n-1) h_l,water(T_(n-1))** + steamFeed + stageHeat
        - liquidOut - vaporOut - **F_n h_l,water(T_n)**  = 0
```

and every vapour term's water contribution now uses the state-dependent `W`. The latent heat released where
water condenses and absorbed where it re-evaporates therefore appears **without a term of its own**: admitting
one more mole of free water on tray `n` moves exactly one latent heat, `h_v(T_(n+1)) - h_l(T_n)`, out of the
tray below and into the wet tray. That identity is asserted directly
(`theTrayEnergyRowsCarryTheFreeWaterLatentHeatFromTheTrayBelowToTheWetTray`).

The immiscible aqueous phase is **not withdrawn by a side draw**: it falls with coefficient one while the
hydrocarbon liquid keeps its `1 - w` retention. That is why `freeWaterPhaseEnergy` is a separate field of
`LocalNodeTerms` rather than being folded into `liquidPhaseEnergy`.

`GLOBAL_ENERGY_BALANCE` needed **no new term**: summed over the trays the free-water column telescopes to
`F_0 h_l(T_0) - F_R h_l(T_R)`, and both ends are zero.

### 2.4 The Jacobian

Three of the four couplings a free-water column creates cross a node boundary, so the local thermodynamic
probe — which perturbs a node's own columns and reads that node's own terms — cannot see them. They are
written in closed form in `V3BlockJacobianAssembler.assembleExactFreeWaterCouplings`, with
`dF/dx = F` for the log coordinate:

| Row | Entry for column `F_m` |
|---|---|
| `VLE(m+1, c)` | `-F_m / (V_(m+1) + W_(m+1))` |
| `SAT(m+1)` | `F_m (1/W_(m+1) - 1/(V_(m+1) + W_(m+1)))` |
| `ENERGY(m+1)` | `-F_m h_v(T_(m+1))` |
| `ENERGY(m)` | `+F_m h_v(T_(m+1))` |

The fourth — `F_m h_l(T_m)` leaving tray `m` and arriving at tray `m+1` — is the probed
`freeWaterPhaseEnergy` term, attributed to `ENERGY(m)` with `-1` and to `ENERGY(m+1)` with `+1`.
`SAT(n)` itself is probed at node `n` exactly as the VLE rows are.

**Verified**, not argued: `localStageBlocksMatchTheWholeSystemFiniteDifferenceOracleOnWetTrays` holds the
whole assembled band against the uncoloured finite-difference oracle on a wet fixture, and on the real
literature column's own wet state the worst relative deviation between `assembleLocal` and the coloured
reference is **1.51e-9** (probe `WetLitProbe ... jacobian`).

### 2.5 The wet set and its refresh

`V3WetTraySet` is immutable, attempt-local and frozen through Newton, attached to the problem alongside the
truncation support by `V3ColumnProblemResolver.withTruncation(problem, support, wetTraySet)`.

Derivation is a single top-down pass carrying its own `F`, so a newly wet tray immediately raises the water
its neighbour below sees and a wet region can spread over several trays in one refresh:

- `W_n = S_(<=n) + carried`; ratio `= P_n W_n / ((V_hc,n + W_n) P_sat(T_n))`.
- Already wet → **stays** wet unless the solved `F_n < 1e-6 * total steam` (`DRY_EXIT_FRACTION`).
- Dry → becomes wet only when `ratio > 1 + 1e-6` (`WET_ENTRY_HYSTERESIS`).
- Not wet-eligible at all when `P_sat(T_n) >= P_n` or `T_n` is outside the water correlation envelope.
- A newly wet tray is seeded with the water it holds above saturation,
  `F_n = W_n - V_hc,n P_sat/(P_n - P_sat)`, floored at `1e-9 * total steam`; an already-wet tray keeps its
  solved flow so a refreshed attempt restarts where the previous one stopped.

**The sump is never wet** — a documented deviation from the plan, see section 6.

The refresh mirrors the floor-support loop in `solveSingleProblem` and shares it, with two differences:

1. It has **its own budget** of three (`MAXIMUM_WET_TRAY_REFRESHES`), so it never competes with the support
   refreshes for one, and a refresh that only changes the wet set does not consume a support refresh.
2. **Only a converged attempt spends one**, and the first attempt of every solve is prepared dry. This is the
   rule the energy-shift predictor already follows, and the measurement behind it is in section 5.1.

A refresh that adds a wet tray keeps the full Newton budget (it carries a degree of freedom the stalled solve
never had); one that only removes — points from the support, trays from the set — keeps the existing
`STALLED_DROP_REFRESH_ITERATIONS` polish budget.

Admitting a tray is an energy step of exactly the kind a heat rung makes, so `V3EnergyShiftPredictor` is
applied to the refreshed seed (`withWetEnergyShift`) and publishes one bounded event.

Events published: `wet trays: [1, 2, 3]; free water: 773.1/1263.3/621.8 kmol/h`, and
`free-water energy shift: largest 14.420 K, scaled energy 0.0338140 -> 0.000544089`. The same wet-tray line is
added to the audit's advisory evidence.

### 2.6 Audits

| Check | State |
|---|---|
| `WATER_PROFILE` | unchanged; still recomputes the authored steam profile and the condenser split independently, limit 1e-12 |
| `WATER_BALANCE` | **new**: node by node, `W_(n+1) + F_(n-1) + S_n` in against `W_n + F_n` out, and at the boundary `total steam` against `overhead vapour + decanted + bottoms free water`; both relative to the total steam, limit 1e-8 |
| `WATER_DEW_POINT` | **regime-consistent**: a dry node's ratio must not exceed one; a wet tray must sit on the line to the convergence closure **and** shed strictly positive water. Both are reported on one scale — a dry node's value is its ratio, a wet tray's is `abs(ratio - 1) / closure` — against a limit of one |
| `FREE_WATER_SPLIT` | unchanged (the condenser split is unchanged) |
| `CONDENSER_ENERGY_BALANCE` | unchanged formula, and now a genuine cross-check: the evaluator computes `W_1` from the state, the auditor from the authored steam total, and the telescoping identity says they must agree |
| `GLOBAL_ENERGY_BALANCE` | unchanged formula; the internal free-water column telescopes to zero |

Measured on the failing published-duty candidate: `WATER_BALANCE` 3.4e-16 and 0.0 against 1e-8,
`CONDENSER_ENERGY_BALANCE` 0.0 W against a 45 W limit. The water contract holds even where the solver does not
finish.

### 2.7 Labels and revisions

| | old | new |
|---|---|---|
| wet formulation, no heat | `v3-wet-mesh-r28-steam` | `v3-wet-mesh-r30-steam` |
| wet formulation, with heat | `v3-wet-mesh-r29-steam` | `v3-wet-mesh-r31-steam` |
| wet assumptions | `v3-wet-assumptions-r1` | `v3-wet-assumptions-r2` |

Dry inputs are untouched in every field. `V3InputDigest` does not read the ledger, so no dry digest moves, and
the whole dry half of the suite passes byte-identically.

---

## 3. Tests

New `V3FreeWaterTrayTest` (8), on a hand-built three-tray manufactured binary at 1.5 MPa with 10 mol/s of sump
steam — at the tray-one temperature of 410 K water saturates at 349 kPa, so 10 of 40 mol/s of water at 1.5 MPa
is supersaturated:

| Test | What it pins |
|---|---|
| `aWetTrayAddsExactlyOneUnknownAndOneRowToItsOwnStageBlock` | +1 unknown, +1 row, full structural rank, only the wet tray's block changes size, and `SAT(1)` references `T_1` and the node's vapour but **not** its own `F_1`, while `ENERGY(2)` does reference it |
| `theSaturationRowIsZeroWhenTheTrayVaporSitsExactlyOnTheWaterSaturationLine` | the row reads exactly zero at a constructed saturated state, at scale 1 |
| `theTrayEnergyRowsCarryTheFreeWaterLatentHeatFromTheTrayBelowToTheWetTray` | `+dF (h_v(T_2) - h_l(T_1))` on `ENERGY(1)`, the negative of it on `ENERGY(2)`, and exactly zero on `ENERGY(3)` |
| `theFreeWaterCoordinateRoundTripsThroughTheScaledLogFlowMap` | encode/decode round trip, `log(F / total steam)` as the coordinate, and an exact zero on every dry node |
| `aStateCannotCarryFreeWaterOnANodeThatIsNotAnEquilibriumTray` | the state rejects free water on the sump or the condenser |
| `theWetSetIsDerivedFromTheSaturationRatioAndCascadesDownward` | derivation from the ratio, the sump never wet, and that the shed water raises the next tray's load |
| `aWetTrayStaysWetUntilItsFreeWaterFallsBelowTheDryFloorAndADryTrayNeedsTheEntryHysteresis` | both hysteresis directions |
| `aColumnWithoutSteamHasNoWetTraysAndNoFreeWaterCoordinate` | a dry column carries no free-water unknown and a scale of one |

`V3BlockJacobianAssemblerTest.localStageBlocksMatchTheWholeSystemFiniteDifferenceOracleOnWetTrays` (new)
extends the existing oracle to a wet problem.

Every existing steam/wet regression case — `V3PumparoundSteamCalculatorTest`, `V3SteamFeedContractTest`,
`V3ConvergenceClosureTest` case C (wet TJL19), the wet cases of `V3ColumnCalculatorTest` — passes **unchanged
in outcome and in value**. None of them has a stage below the water dew point, so none acquires a wet tray,
and their first attempt is bit-identical to the pre-F2 one. No tolerance was loosened and no assertion widened.

---

## 4. Measurements

### 4.1 The shipped preset (top cooler 6.42 MW), against thesis Table 1.1

SUCCESS, 3 published Newton iterations, scaled residual 2.44e-14, 14.9 s, no wet trays (tray one's saturation
ratio is 0.78), condenser -48.83 MW, stage heat -35.51 MW.

| Stage | Thesis, C | This preset, C | Difference |
|---|---|---|---|
| 1 | 93.7 | 91.5 | -2.2 |
| 9 | 146.5 | 137.4 | -9.1 |
| 10 | 147.4 | 149.5 | +2.1 |
| 17 | 227.5 | 215.9 | -11.6 |
| 18 | 238.6 | 228.6 | -10.0 |
| 27 | 304.9 | 285.0 | -19.9 |
| 28 | 310.9 | 293.0 | -17.9 |
| 36 | 341.3 | 313.0 | -28.3 |
| 37 (feed) | 341.3 | 333.8 | -7.5 |
| 41 (bottom) | 335.1 | 305.3 | -29.8 |

Light naphtha 766.8 kmol/h against the source's 833. Free water 1,200.0 kmol/h decanted at 59 C — every mole
of the stripping steam. The column is still colder than the source from section 2 down, and for the reason
`V3_LITERATURE_TOP_TEMPERATURE_CHECK` established: the 18.1 MW of side-stripper reboiler heat is not modelled.
Modelling the side strippers remains the correct fix, and it is what should restore the published 12.84 MW top
cooler.

### 4.2 The published 12.84 MW top cooler

| | before F2 (`64b634e`) | after F2 |
|---|---|---|
| Outcome | `ACCEPTANCE_AUDIT_FAILURE` | `NONCONVERGENCE` |
| Residual | 5.02e-14 (converged, audit rejected it) | 7.81e-4 |
| Tray 1 | 83.07 C, saturation ratio 1.1615 | 86.29 C, ratio 1.0002 |
| Free water on tray 1 | — | 254.9 kmol/h |
| Failed checks | `WATER_DEW_POINT` 1.162 | `WATER_DEW_POINT`, `EQUILIBRIUM` 7.9e-5, `GLOBAL_ENERGY_BALANCE` |
| `WATER_BALANCE` | — | passes, 0.0 against 1e-8 |

**The tray-one physics is right and the constraint is satisfied**: the wet tray moves from 83.1 C to 86.3 C,
lands on its water saturation line to four decimal places, and does it by shedding 255 kmol/h of free water
that re-evaporates on tray two. What does not finish is the rest of the column — see section 5.

Sweep of the top-cooler factor (probe `WetLitProbe`, whole public calculator):

| Factor | Duty | Outcome | Tray 1 | Wet | Free water |
|---|---|---|---|---|---|
| 0.50 | 6.42 MW | SUCCESS 2.44e-14 | 91.5 C | — | — |
| 0.80 | 10.27 MW | SUCCESS | | — | — |
| 0.82 | 10.53 MW | SUCCESS | | — | — |
| 0.84 | 10.79 MW | SUCCESS | | — | — |
| 0.86 | 11.04 MW | NONCONVERGENCE | ratio 1.0000 | [1] | 302 kmol/h |
| 0.90 | 11.56 MW | NONCONVERGENCE | ratio 1.0605 | [1] | 601 kmol/h |
| 1.00 | 12.84 MW | NONCONVERGENCE | ratio 1.0002 | [1] | 255 kmol/h |

0.84 is the last dry factor and 0.86 the first wet one; **every wet case stalls and no dry case does**. There
is at present no converging wet-tray case on this column. A sweep of small steam-stripped columns
(`WetSmallProbe`: 8 and 12 trays, condenser 332/322/313 K, steam 150/300/600 kmol/h, 18 combinations) found no
wet tray at all — without a cooler near the top, a steam-stripped column's top stays above the water dew point.

---

## 5. The open gate: why the wet column stalls

`WetSolveProbe` isolates it — converge the literature column dry, attach the wet set to that converged state,
hand the pair straight to the simultaneous solver with a full 128-iteration budget.

```
derived wet set: V3WetTraySet[1]
  seed F(1)=212.5 kmol/h, T=83.07 C
seed: max scaled 0.149749   (WATER_SATURATION node 1 = ln 1.1615)
wet solve: Failure iterations=5 residual=0.000780827
           termination=no admissible Armijo-reducing Newton or descent step
final: ENERGY_BALANCE node 2 -7.8083e-04 (-5.7602e+04 W)
       ENERGY_BALANCE node 3 -7.8067e-04
       ENERGY_BALANCE node 4 -7.8062e-04   ... every tray, the same 57.6 kW
```

### 5.1 What was ruled out

- **The Jacobian.** `assembleLocal` against the coloured finite-difference reference **on that very state**:
  worst relative deviation 1.51e-9.
- **A missing coupling.** `WATER_BALANCE` 0.0, `CONDENSER_ENERGY_BALANCE` 0.0 W on the failing candidate; the
  energy latent-heat identity is asserted to 1e-6 W in the unit test.
- **The seed.** The first version derived the wet set from whatever state the attempt started on. On the
  stalled steam-ramp seed that put **773 kmol/h** of free water on tray one against a true 213, poisoned the
  top three energy rows by 10, 6 and 4 MW, and left Newton accepting every one of 32 steps while the residual
  moved from 0.8454 to 0.8448. Gating the derivation on a converged attempt fixed that outright: the seed
  became 212.5 kmol/h and the residual fell to 7.8e-4. **That gate is in the shipped code.**
- **The energy-shift predictor.** Applied to the refreshed seed it does its job (largest shift 14.4 K, scaled
  energy 0.0338 -> 0.00054) and the solve still ends at the same 7.806e-4 plateau.
- **A coarser difference scale.** `DifferenceScale.COARSE` makes no progress at all: 128 iterations, residual
  unchanged at 0.1497.
- **A stationary point of the merit.** It is not one: `max|J^T r| = 8.94e-5` against `||r|| = 4.6e-3`.

### 5.2 What it is

The linear system at the stalled state solves (backward error 1.3e-18, minimum pivot 0.0744) but returns a
direction of magnitude **1.49e6**, dominated by:

```
TEMPERATURE node 2          -1.4927e+06
FREE_WATER_FLOW node 1       4.1101e+05
TEMPERATURE node 3          -1.2081e+05
LIQUID_COMPONENT_FLOW node 1, every component, all equal to -4.7227e+04
VAPOR_COMPONENT_FLOW node 2  ...
```

Equal log-coordinate entries across every component of the tray-one liquid, with matching tray-two vapour
entries, is the signature of the **equilibrium-free exchange cycle** between adjacent trays: add `d_c` to both
`l(1,c)` and `v(2,c)` and both material rows read `+d_c - d_c = 0`. Riding with it are `F_1`, whose column
shifts every `VLE(2, .)` row by the same constant (`-F_1/(V_2 + W_2)`) — the same rank-one signature a uniform
scaling of the tray-two vapour has — and `T_2`, `T_3` absorbing the energy rows.

Walking that direction:

| step | merit ratio |
|---|---|
| 2^-16 | 4.5e+7 |
| 2^-18 | 7048 |
| 2^-20 | 13.75 |
| 2^-22 | 1.041 |

It is a descent direction (it must be, with an exact Jacobian), but the quadratic term dominates until
`alpha ~ 1e-12`, where the available reduction is about 1e-12 relative. The normalised gradient fallback is no
better: its first reducing step is `2^-20`, one halving past the solver's 20-step line search, and the
reduction there is **6e-6 relative**. Both directions are useless in practice: the system is singular to
roughly 1e-6 in that direction.

Physically this is a **water plateau**. Condensing water on tray one and evaporating it on tray two is a heat
pipe: it heats the tray that is pinned to its dew point and cools the tray that feeds it, and the feedback
gain of that loop is close to one for this column. The amount of water circulating around the 1-2 loop is then
nearly indeterminate, which is exactly a near-null direction. Tray two cannot join the plateau — its own
saturation ratio at the stalled state is 0.67 — so the wet zone is one tray deep and marginally determined.

### 5.3 What would probably fix it

Not attempted, in rough order of promise:

1. **Continuation in the free water.** Solve a sequence in which `F_n` is a *parameter* rather than an unknown
   (drop `SAT(n)`, which keeps the system square) and step it from zero to the value that zeroes `SAT(n)`.
   Each sub-problem is the well-conditioned dry formulation with an extra known water flow, and the scalar
   `SAT(n)(F_n)` is a one-dimensional root find. This is the closest analogue of what the heat and steam ramps
   already do.
2. **Eliminate the near-null pair.** `breakEquilibriumFreeCycles` in `V3TruncationSupport` exists for the same
   family of exchange cycles on *truncated* points; the wet zone reintroduces one on fully retained points.
3. **Widen the line search and add a trust region** on the Newton direction when `||J^-1 r||` is huge. This
   alone will not close it — the reduction available is 6e-6 per step — but it would let the solver keep
   walking instead of reporting `LINE_SEARCH_EXHAUSTED`.

---

## 6. Deviations from the plan

1. **The sump is never wet.** The plan asks for free water leaving with the bottoms, published as a stream and
   a ledger energy. `F_R` is the one term of the telescoped balance that *every* node above sees, so making it
   an unknown puts a dense column into an otherwise tri-block system and breaks the off-band guard of
   `V3BlockJacobianAssembler` and the banded solver — the whole reason the rest of this is cheap. A sump below
   its own water dew point is instead reported by `WATER_DEW_POINT` exactly as a dry tray is. In a crude column
   it cannot happen: the sump is the hottest node. `WATER_BALANCE` still carries the bottoms free-water term
   explicitly, so the accounting is ready if the restriction is ever lifted.
2. **The first attempt of a solve is dry**; the wet set enters at a refresh from a converged state, rather than
   being derived from the seed of every attempt. Measured, section 5.1.
3. **The preset keeps its halved 6.42 MW top cooler.** F3 was to restore the published 12.84 MW. Restoring it
   would make the input a fresh calculator starts on stop converging, which is a worse outcome for a player
   than a documented deviation from the source. The javadoc on `literatureCduInput()` now says so and points
   here.
4. **No GUI edit.** The wet-tray line is carried by the existing diagnostics events and the audit's advisory
   evidence, which the Convergence page already renders; nothing needed a new field, so nothing was touched
   and no in-game check was required.

## 7. Left undone

- The published-duty literature column does not converge with a wet tray (section 5). This is the one gate of
  the plan that is not met.
- No converging wet-tray case exists anywhere in the suite, so the free-water phase has unit-level and
  Jacobian-level verification but no end-to-end accepted result. `V3LiteraturePresetTest` pins the failing one
  honestly instead.
- Water in side products, water solubility in the hydrocarbon liquid and three-phase VLE remain out of scope,
  as the plan says.

## 8. Probes

Under `build/pkgcmp/`, all probe-only builds that never touch shipped code:

| File | What it does |
|---|---|
| `build-probe.sh` | copies the science sources, patches a terminal problem/state capture hook and a `probe.wetRefreshes` knob into `V3ColumnCalculator`, compiles the probes |
| `build-probe-ref.sh` | the same from a git ref, for before/after comparison |
| `WetLitProbe` | the literature CDU through the whole public calculator: outcome, audit, advisories, events, streams, ledger, the per-tray profile against Table 1.1, and (`jacobian` argument) `assembleLocal` against the coloured reference plus the banded-solve status |
| `WetSolveProbe` | isolates the wet solve from a converged dry state; `shift`, `blocks`, `coarse`, `walk` modes, the last printing the Newton and gradient line-search walks and the merit gradient |
| `WetSmallProbe` | sweeps small steam-stripped columns looking for a wet tray |
| `TrayBalanceProbe2` | extended with per-tray water vapour, free water and dew-point ratio columns, and a condenser-branch override |

Logs: `f1-lit-full.log`, `f2-lit-full.log`, `f2-lit-full2.log`, `f2-lit-shift.log`, `f2-preset.log`.

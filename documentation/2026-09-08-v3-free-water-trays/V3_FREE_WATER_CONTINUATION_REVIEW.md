# Free-water continuation: the wet solve does not stall, it has no root

Date: 2026-09-08. Branch `claude/v3-literature-cdu-handoff-3179dc`, base `f6f3760`.
Implements WP-W1 of the free-water follow-up (candidate 1 of `V3_FREE_WATER_TRAYS_REVIEW.md` section 5.3).

| WP | Commit | Subject |
|---|---|---|
| W1 | `24b18e8` | Place the free water by continuation, and refuse a tray it cannot saturate |
| W2 | *(this document + preset javadoc)* | see section 8 |

Suite: **455 tests, 0 failures** (453 at `f6f3760`; two added, one re-pinned).

---

## 0. The result in one paragraph

The continuation was built as asked: the wet trays' free water becomes a **parameter**, the dry-shaped system
is solved for it, and the saturation residuals are closed by a safeguarded one-dimensional root find on the
converged parametric solve. The parametric solves are exactly as well-behaved as predicted — 3 to 5 Newton
iterations, 0.2 to 0.3 s each, residual 1e-14 every time, over free-water flows from zero to 1 600 kmol/h. The
root find then finds that **there is no root**, and not because the solver is weak: on the topmost tray of a
wet block the saturation ratio is *invariant* under that tray's own free water, measured at about
`1.6e-9` per kmol/h against the `0.1497` that would have to be closed. That invariance is structural and is
derived and measured in section 3. Gate 1 as written is therefore unreachable and the preset was **not**
restored to the published duty (gates 4 and 5 depend on gate 1). What the work does deliver is that every wet
case now ends on a **solved, fully audited column** whose single failed check names the stage, instead of a
7.8e-4 stall on a near-null direction: sections 5 and 6.

---

## 1. What landed

### 1.1 The parametric wet set

`V3WetTraySet` gains a second mode. A **solved** set is the shipped formulation: each wet tray carries an
`UnknownFamily.FREE_WATER_FLOW` unknown and an `EquationFamily.WATER_SATURATION` row. A **parametric** set
(`V3WetTraySet.parametric(topology, wet, freeWaterMolPerSecond)`) holds each wet tray's `F_n` at a frozen
value and carries it through everything that reads it — the state's water profile, the equilibrium dilution
term, the free-water latent heat between trays, the audits — while contributing **neither an unknown nor a
row**. The resolved ledger of a parametric set is byte-identical to the dry one.

| Consumer | Reads |
|---|---|
| `V3DegreeOfFreedomLedger` unknown/row enumeration | `wetTraySet.hasFreeWaterUnknown(node)` (new) |
| `V3StageBlockLayout` block size and both validators | `problem.hasFreeWaterUnknown(node)` (new) |
| `V3BlockJacobianAssembler.assembleExactFreeWaterCouplings` | `problem.hasFreeWaterUnknown(source)` |
| `V3MeshResidualEvaluator.localTerms` saturation probe | `problem.hasFreeWaterUnknown(node)` |
| `V3ColumnProblem.freeWaterFlowMolPerSecond`, `waterVaporFlow`, the auditor | `isWetTray(node)` — **unchanged**, the physics is the same in both modes |

The one place that needed real care is `V3DryMeshCoordinateMap.decode`. A parametric flow is part of the
problem, not of the Newton vector, so `encode` produces no coordinate for it; `decode` now seeds its
free-water array from `problem.wetTraySet().parametricFreeWaterFlows()` instead of from zeros. Without that a
single Newton step would silently dry the tray out, and it is pinned by
`aParametricWetTrayCarriesItsFreeWaterThroughTheRowsWithoutAnUnknownOrASaturationRow`.

Nothing else moved: `sameSet` also compares the mode, so a parametric set is never mistaken for the solved one
the certificate is taken on, and `seed` writes the frozen profile straight into the candidate.

### 1.2 The continuation

`V3FreeWaterContinuation` (new, package-private, 300 lines). One entry point:

```
run(untruncated, support, wetTrays, seed, thermo, feedMolarEnthalpy, closureTolerance, control)
  -> Result(wetTrays, state, converged, event)
```

Algorithm as implemented:

1. Start with every wet tray's `F` at **zero** and solve the parametric problem. At zero water this problem
   *is* the dry one and the refresh's own converged state already solves it, so the first solve is a warm
   confirmation (measured: 0 iterations, 1.4e-14).
2. Sweep the wet trays **top down**, Gauss-Seidel, at most `MAXIMUM_SWEEPS = 4` sweeps. `F_(n-1)` enters
   `SAT(n)` directly through `W_n` while `F_(n+1)` reaches it only through the temperatures, so top down is
   the order in which one pass is nearly enough.
3. Each tray is a safeguarded root find on `h(F) = ln(ratio_n)` evaluated on the **converged parametric
   solve**, at most `MAXIMUM_SOLVES_PER_TRAY = 12` solves, each warm-started from the previous one:
   - `h` is positive on a supersaturated tray, so the bracket opens at the current flow and grows by
     `BRACKET_GROWTH = 2` (the first opening step is the water the tray holds above saturation — the right
     magnitude even though the mechanism is thermal) until the sign turns;
   - once bracketed, the step is a secant kept `BRACKET_MARGIN = 5 %` inside the bracket — the low/high secant
     first, then the last-two-points secant — and anything that leaves the bracket falls back to bisection;
   - a parametric solve that fails to converge is treated as a step that went too far, not as a sign: the
     trial becomes an upper search limit and the tray bisects back toward the last good flow;
   - closure is `|h| <= 0.25 * closureTolerance`, a quarter of the convergence closure rather than the whole
     of it, so the certifying solve starts strictly inside its own tolerance.
4. **Refusal.** Two supersaturated points already say where the root would be. When the secant through them
   puts it past `FREE_WATER_SEARCH_CAP_FACTOR = 8` times the total authored steam — or when `h` does not fall
   at all — the tray is reported `UNCONTROLLABLE` with the sensitivity it measured, rather than spending the
   remaining ten solves learning the same thing. This is what fires in production, and it costs **two**
   parametric solves.
5. A tray that closes lets the continuation look at the tray below it (`admitNextTray`): a parametric tray
   costs no ledger change, so a wet zone several trays deep can be discovered inside one continuation instead
   of one tray per refresh. Capped at `MAXIMUM_ADMITTED_TRAYS = 6`.
6. On success the caller rebuilds the attempt around the **solved** set (`asSolved`) seeded with the flows the
   continuation found, and the ordinary simultaneous solve certifies it. Because the parametric rows are
   converged and the saturation rows are inside a quarter of the closure, that solve starts below tolerance
   and finishes on a verified final Newton correction.

### 1.3 Where it is applied

In `solveSingleProblem`'s attempt loop, on the refresh that changes the wet set:

| Refresh | Path |
|---|---|
| set becomes empty (trays removed) | `withWetEnergyShift` as before — a removal is an energy step like a heat rung |
| set gains trays, continuation closes | attempt rebuilt on the solved set seeded from the continuation |
| set gains trays, continuation refuses, support unchanged | **break**: the converged candidate the refresh came from is the answer, and its `WATER_DEW_POINT` check names the stage |
| set gains trays, continuation refuses, support changed | support refresh kept, wet set dropped, and the wet budget is spent so the same set is not derived again |

`withWetEnergyShift` is no longer applied before a wet solve: the continuation re-solves every energy row
exactly at each step, which is strictly more than the predictor's linearisation does. It is retained for the
removal case, with its test.

The wet-tray events are published **even when the set was refused**, which they were not before — why a
supersaturated stage did not take a free-water phase is exactly what an operator reading a failed dew-point
check needs.

---

## 2. The parametric solve behaves exactly as the plan predicted

`FreeWaterSweepProbe` (new) converges the literature CDU dry, then solves the parametric problem at a
prescribed free-water vector, warm-starting each from the last. Literature CDU, published 12.84 MW top cooler,
tray 1 wet:

| `F_1` kmol/h | Newton iterations | residual | wall |
|---|---|---|---|
| 0 | 0 | 1.39e-14 | 0.4 s |
| 100 | 3 | 2.44e-14 | 0.3 s |
| 200 | 3 | 6.93e-14 | 0.2 s |
| 400 | 3 | 4.22e-14 | 0.2 s |
| 800 | 4 | 5.22e-14 | 0.3 s |
| 1 600 | 5 | 1.62e-14 | 0.3 s |

Trays 1 and 2 both wet, both parameters:

| `F_1`, `F_2` kmol/h | iterations | residual |
|---|---|---|
| 400, 400 | 4 | 4.42e-14 |
| 800, 800 | 4 | 2.75e-14 |
| 1 600, 1 600 | 5 | 8.19e-14 |
| 800, 1 600, 800 (three trays) | 5 | 3.64e-14 |

So the split does what it was designed to do: with `F` frozen the energy plateau that stalled the
simultaneous system is gone and Newton places the energy in one to five iterations, every time, over a
four-fold range of free water. **The sub-problem is not the difficulty.**

---

## 3. The finding: the top tray of a wet block cannot be saturated

### 3.1 The measurement

Same sweep, tray 1's own numbers (`FreeWaterSweepProbe 1.0 "0;100;200;400;800;1600"`):

| `F_1` kmol/h | `T_1` °C | `V_hc,1` kmol/h | `W_1` kmol/h | ratio | `ln(ratio)` |
|---|---|---|---|---|---|
| 0 | 83.0727199 | 3 615.8 | 1 200.0 | 1.1615 | 0.149743 |
| 100 | 83.0727... | 3 615.8 | 1 200.0 | 1.1615 | 0.149743 |
| 400 | 83.0727199 | 3 615.8 | 1 200.0 | 1.1615 | 0.149743 |
| 800 | 83.0729644 | 3 615.8 | 1 200.0 | 1.1615 | 0.149734 |
| 1 600 | 83.0742507 | 3 615.8 | 1 200.0 | 1.1615 | 0.149685 |

`ln(ratio_1)` moves by **5.8e-5 over 1 600 kmol/h** — a slope of `-3.6e-8` per kmol/h. Closing 0.1497 at that
slope needs about **four million kmol/h** of free water on a column fed 2 656 kmol/h of crude. The same
measurement at the other cooler factors, taken by the shipped continuation itself over its own two-point
opening step:

| top-cooler factor | `ln(ratio_1)` at `F_1 = 0` | opening `F_1` | slope, per kmol/h |
|---|---|---|---|
| 0.86 | 0.0168059 | 26 kmol/h | −1.01e-09 |
| 0.90 | 0.0525976 | 80 kmol/h | −1.17e-09 |
| 1.00 | 0.149749 | 212 kmol/h | −1.64e-09 |
| 1.20 | 0.383253 | 463 kmol/h | −2.95e-09 |

Eight to nine orders short, at every duty. Note also that `V_hc,1` and `T_1` are unchanged to seven figures
while tray 2 moves by 32 K and 1 800 kmol/h over the same sweep — the perturbation is absorbed entirely one
tray down.

### 3.2 Why, exactly

Two facts compose.

**(a) The water rising out of the topmost wet tray is a constant.** The tray water balance is
`W_n = W_(n+1) + F_(n-1) + S_n - F_n`, and substituting downward every intermediate `F` telescopes away:

```
W_n = (steam fed at or below n) + F_(n-1) - F_R
```

with `F_R = 0` because the sump is never wet. `F_n` is **not in its own tray's water flow**: what condenses on
tray `n` falls to tray `n+1` and comes straight back up. On the topmost tray of a wet block, `n-1` is dry, so
`F_(n-1) = 0` and `W_n` is the authored steam profile exactly — 1 200 kmol/h here, at every free-water flow
and for every wet set. That is arithmetic, not a measurement, and it is pinned by the new unit test
`aTraysOwnFreeWaterNeverChangesTheWaterRisingOutOfIt`.

**(b) Its temperature and hydrocarbon vapour are invariant along the exchange cycle.** With `W_1` fixed, the
saturation ratio `P W_1 / ((V_hc,1 + W_1) P_sat(T_1))` can only move through `T_1` and `V_hc,1`. Admitting
`dF_1` puts `dF_1 (h_v(T_2) - h_l(T_1))` of latent heat into tray 1 and takes it out of tray 2 — and tray 2
pays for it by sending up less hydrocarbon vapour, which removes the same heat from tray 1 again. Measured:
over `F_1 = 0 -> 1600` kmol/h the tray-2 vapour falls from 3 312 to 1 469 kmol/h and `T_2` from 102.7 to
70.7 °C, while tray 1 does not move at all. The loop gain is one to seven figures.

This is precisely the near-null direction `V3_FREE_WATER_TRAYS_REVIEW` section 5.2 measured from the other
side — the 1.5e6-long Newton direction dominated by `T_2`, `F_1` and equal shifts across the tray-one liquid
and tray-two vapour. It is the equilibrium-free exchange cycle `V3TruncationSupport.breakEquilibriumFreeCycles`
excludes on truncated points, reintroduced by the free-water unknown on fully retained ones. The simultaneous
system is not *nearly* singular in that direction; the row it is supposed to close does not depend on the
unknown at all.

### 3.3 What that means for the model

Every wet block's topmost tray has the same problem, because `F_(n-1) = 0` above it by definition of the
block. So the free-water phase **can never bring a tray onto its saturation line at the top of a block**, and
a block cannot start below a tray that is itself saturated. The only rows the free water *can* close are
`SAT(n+1), SAT(n+2), ...` — a wet tray's flow controls the tray **below** it, strongly and monotonically
(measured: `ln(ratio_2)` runs −0.5184 → −0.0115 → +0.4787 → +1.6278 over `F_1 = 0, 400, 800, 1600` kmol/h, a
clean root near 405 kmol/h).

Physically the reason is not a defect of the discretisation. In steady state the condenser is the only water
sink in the column, so every mole of stripping steam has to pass tray 1 as vapour; if tray 1 also carried an
aqueous phase its vapour would be saturated and would carry *less* than that, and the difference would have to
accumulate. **There is no steady state with a wet top tray while all the water leaves overhead.** The
reconstruction reaches that condition only because it is about 10 K colder at the top than the source, for the
documented reason — the 18.1 MW of side-stripper reboiler heat it does not model (thesis stage 1 is 93.7 °C,
where the ratio is 0.77 and the top is comfortably dry). Section 8 lists what would change it.

---

## 4. Gate 1 — the literature column at the published 12.84 MW

**Not met as written, and it cannot be: SUCCESS with tray 1 wet does not exist.** What the case does now:

| | before (`f6f3760`) | after (`24b18e8`) |
|---|---|---|
| Outcome | `NONCONVERGENCE` | `ACCEPTANCE_AUDIT_FAILURE` |
| Residual | 7.806e-4 | **5.024e-14**, "verified final Newton correction", 3 iterations |
| Wall | 19.5 s | 20.4 s |
| Wet trays | [1], 1 337.9 kmol/h | none — refused after 2 parametric solves |
| Failed checks | `WATER_DEW_POINT`, `EQUILIBRIUM` 7.9e-5, `GLOBAL_ENERGY_BALANCE` 2.08 MW | **`WATER_DEW_POINT` only**, 1.162 against 1.000 |
| `WATER_BALANCE` | pass, 0.0 | pass, 0.0 |
| `GLOBAL_ENERGY_BALANCE` | fail, 2.08e6 W | pass, **2.98e-8 W** against 101.5 W |
| Condenser duty | −45.48 MW (on a stalled iterate) | **−43.81 MW** |
| Light naphtha | — | 699.4 kmol/h (`V_1 / (1 + R)`), against the source's 833 |

Dew-point detail, verbatim: *"tray 1 at 83.1 C is below the water dew point 86.9 C (steam partial pressure
62.3 kPa) without a free-water phase; the free-water tray set did not admit it"*, and the event line:
*"free-water continuation declined after 2 parametric solves: tray 1 cannot be saturated by any free-water
flow, ln(ratio) 0.149749 -> 0.149748 over F 0 -> 212 kmol/h (d ln(ratio)/dF = -1.64e-09 per kmol/h)"*.

Profile against thesis Table 1.1 (`WetLitProbe 1.0`, terminal state):

| Stage | Thesis °C | This candidate °C | Difference |
|---|---|---|---|
| 1 | 93.7 | 83.07 | −10.6 |
| 9 | 146.5 | 131.48 | −15.0 |
| 10 | 147.4 | 142.79 | −4.6 |
| 17 | 227.5 | 198.06 | −29.4 |
| 18 | 238.6 | 216.23 | −22.4 |
| 27 | 304.9 | 268.34 | −36.6 |
| 28 | 310.9 | 276.20 | −34.7 |
| 36 | 341.3 | 310.67 | −30.6 |
| 37 (feed) | 341.3 | 329.43 | −11.9 |
| 41 (bottom) | 335.1 | 299.24 | −35.9 |

Free water on every tray: **zero**; the 1 200 kmol/h of steam decants whole in the drum at 59 °C. The column
is colder than the source throughout, which is the same missing 18.1 MW of stripper heat, made worse than the
shipped 6.42 MW preset by taking another 6.42 MW out at the top.

### 4.1 What the old wet "near-solution" actually was

Worth recording, because it looks convincing in the old advisory line. The baseline's stalled wet states reach
`ratio_1 = 1.0000...1.0003` — but they do it by **draining tray 1's liquid**, not by heating it:

| factor | baseline free water | `T_1` | `V_hc,1` | `L_1` | residual |
|---|---|---|---|---|---|
| 0.86 | 302.2 kmol/h | 86.27 °C | 3 732.5 | 2 394.6 | 9.54e-5 |
| 0.90 | 601.4 kmol/h | 86.28 °C | 3 731.3 | 2 060.4 | 2.92e-4 |
| 1.00 | 1 337.9 kmol/h | 86.29 °C | 3 728.4 | 1 217.8 | 7.81e-4 |
| 1.20 | 2 386.0 kmol/h | 86.32 °C | 3 722.3 | **2.7** | 1.76e-3 |

`T_1` and `V_hc,1` are the same in all four — they are the *dry* solution's values at the 0.84 factor, where
the ratio happens to be 1. The line search is walking the state toward a tray with no liquid on it, which is
where the exchange cycle can pretend to satisfy the saturation row, and the price is the energy rows: they
stay short by 57.6 kW each, 2.08 MW in total, and the residual gets steadily *worse* as the duty rises.
The old candidate was not a nearly-converged solution; it was a degenerate limit.

---

## 5. Gate 2 — the top-cooler sweep

`WetLitProbe <factor>` through the whole public calculator.

| Factor | Duty | before | after | `T_1` | ratio | wet | free water |
|---|---|---|---|---|---|---|---|
| 0.84 | 10.79 MW | SUCCESS 2.04e-14, 19.1 s | **SUCCESS 2.04e-14**, 19.2 s, same 4 iterations, same profile | 86.28 °C | 0.9995 | — | — |
| 0.86 | 11.04 MW | NONCONVERGENCE 9.54e-5, 21.5 s | **converged 7.57e-14**, audit fails on the dew point, 19.2 s | 85.91 °C | 1.0169 | refused | — |
| 0.90 | 11.56 MW | NONCONVERGENCE 2.92e-4, 21.9 s | **converged 4.11e-14**, 20.5 s | 85.15 °C | 1.0540 | refused | — |
| 1.00 | 12.84 MW | NONCONVERGENCE 7.81e-4, 19.5 s | **converged 5.02e-14**, 20.4 s | 83.07 °C | 1.1615 | refused | — |
| 1.20 | 15.41 MW | NONCONVERGENCE 1.76e-3, 21.1 s | **converged 6.17e-14**, 19.8 s | 78.09 °C | 1.4670 | refused | — |

No case is SUCCESS above 0.84, and none can be: the dew-point boundary of this arrangement lies between 0.84
and 0.86, and above it the specification has no steady state with the water leaving overhead. Every case above
it now returns a solved column with one honest failed check, in the same wall time, instead of a stall. The
0.84 case is bit-identical to the baseline in every field.

---

## 6. Gate 3 — the steam cases are unchanged

Full suite green. The wet regression cases — `V3PumparoundSteamCalculatorTest`, `V3SteamFeedContractTest`,
`V3ConvergenceClosureTest` case C (wet TJL19), the wet cases of `V3ColumnCalculatorTest` — pass **unchanged in
outcome and in value**, and none of them re-pinned anything. None has a stage below the water dew point, so
none derives a wet set, so none reaches the continuation at all: their first attempt is bit-identical.

No formulation label was bumped. The equations, unknowns and rows of the shipped (solved) formulation are
exactly what `f6f3760` had; only the path that seeds them changed, and a parametric set never reaches a
published result. `V3InputDigest` does not read the ledger, so no digest moved.

---

## 7. Gates 4 and 5 — re-pins

Gate 4 (restore the preset to −12.84e6 and assert SUCCESS) is **not done**, because gate 1 is unreachable: the
preset keeps its halved 6.42 MW top cooler, which is the deviation `f6f3760` already documented, and the
javadoc on `literatureCduInput()` was rewritten to give the structural reason and to point here instead of at
the old stall. `V3ConvergenceClosureTest` does not read the preset and did not move.

| Test | old | new | why |
|---|---|---|---|
| `V3LiteraturePresetTest.thePublishedTopCoolerDutyAdmitsAFreeWaterTrayAndClosesItsWaterBalanceWithoutConvergingYet` | `NONCONVERGENCE`, advisory `wet trays: [1`, `WATER_BALANCE` passes | renamed `...ConvergesWithTrayOneReportedBelowTheWaterDewPoint`: `ACCEPTANCE_AUDIT_FAILURE`, residual < 1e-10, **`WATER_DEW_POINT` is the only failed check**, its value 1.162 ± 0.02, its detail names tray 1, the continuation's refusal event is published, `WATER_BALANCE` passes | the case converges now; the pin is strictly stronger — it went from asserting one failure code to asserting a solved candidate with exactly one named failed check |

Tests added (both in `V3FreeWaterTrayTest`, on the existing hand-built three-tray fixture):

| Test | What it pins |
|---|---|
| `aParametricWetTrayCarriesItsFreeWaterThroughTheRowsWithoutAnUnknownOrASaturationRow` | a parametric set is wet for the physics and dry for the ledger: same unknown and equation counts as the dry problem, full structural rank, no `FREE_WATER_FLOW` unknown, no `WATER_SATURATION` row, every stage block the same size; `seed` writes the frozen flow and a coordinate encode/decode round trip preserves it; the tray below's energy row moves by it |
| `aTraysOwnFreeWaterNeverChangesTheWaterRisingOutOfIt` | the arithmetic of section 3.2(a) at five flows: `W_n` is exactly the authored steam whatever tray `n` sheds, and `W_(n+1)` is exactly that plus `F_n` |

No tolerance was loosened and no assertion widened.

---

## 8. What is left undone, and what would actually fix it

1. **Gate 1 is unreachable under the current contract**, for the reason in section 3. Nothing in the free-water
   formulation can be tuned to reach it.
2. **The right fix is the one `V3_LITERATURE_TOP_TEMPERATURE_CHECK` already named: model the side strippers.**
   The 18.1 MW of stripper reboiler heat the reconstruction omits is what keeps the source column's stage 1 at
   93.7 °C and its water ratio at 0.77. With the top at its published temperature the question does not arise.
3. **If a genuinely wet top is ever wanted, the column needs a second water sink.** The only reason `W_1` is
   pinned to the total steam is that the condenser is the only place water can leave. Letting the aqueous
   phase leave with a liquid side draw — which is what a real crude tower does when its top runs wet, and
   which the plan put out of scope — would make `W_1` smaller than the steam fed, and then the topmost wet
   tray's saturation row acquires the unknown it needs. That is a new work package: the wet block would have
   to be contiguous from tray 1 to the draw tray, `V3SideDraws` would have to carry an aqueous fraction, and
   `WATER_BALANCE` would need the withdrawn term (it already carries a bottoms term for the same reason).
4. **The continuation's closing branch is not exercised end to end.** In production the first tray of a block
   is always refused, so `closeTray`'s bracketing and secant, `admitNextTray`, and the rebuild of the attempt
   on the closed set are reached only if the topmost tray happens to sit inside a quarter of the closure at
   `F = 0` — a measure-zero condition on this column (the dry ratio jumps from 0.9995 at factor 0.84 to 1.0169
   at 0.86). They are written and reviewed but only the refusal path has an integration test. Keeping them is
   deliberate: they are the machinery item 3 needs, and they are what measures the refusal in the first place.
5. **`V3AcceptanceAuditor.waterDewPoint` still fails the candidate.** Whether a stage below the water dew point
   should be a failed check or an advisory is a contract decision for the user, not one to take inside this
   work package. The plan's own words — "a tray below the water dew point must not be a rejection" — argue for
   an advisory, and with section 3 established there is no longer any prospect of the check being satisfiable
   by a free-water phase; but changing a check's severity changes what the calculator ships, so it is left
   open here. **This is the one decision the user should make next.**
6. The energy-shift predictor is no longer applied ahead of a wet solve (section 1.3). Its removal path — a
   refresh that only takes trays away — keeps its behaviour and its test, but the "admit a tray, then predict"
   combination measured in `V3_FREE_WATER_TRAYS_REVIEW` section 2.5 is gone. That is intended: the
   continuation subsumes it.

---

## 9. Probes

Under `build/pkgcmp/`, probe-only builds that never touch shipped code.

| File | What it does |
|---|---|
| `FreeWaterSweepProbe` | **new**: converges the literature CDU dry, then solves the parametric problem at a prescribed free-water vector (`"0;100;400;800,800"` …), warm-starting each from the last, and prints `T`, `V_hc`, `L`, `W`, ratio and `ln(ratio)` for the top eight nodes. This is the probe behind sections 2 and 3.1. |
| `WetLitProbe` | unchanged; the literature CDU through the whole public calculator |
| `build-probe.sh` | extended to compile `FreeWaterSweepProbe` |
| `build-probe-ref.sh` | used at `f6f3760` (`classes-w1base`) for the before column of sections 4 and 5 |

Logs: `w1-base-1.0.log`, `w1-base-sweep.log` (baseline), `w1-sweep-f1.log` (the section 3.1 sweep),
`w1-try3-1.0.log`, `w1-sweep-factors.log` (after), `w1-suite-2.log`.

Reproduce the central measurement with:

```
java -Dprobe.wetRefreshes=0 -cp "build/pkgcmp/classes-probe;<gson>" \
  com.wormzjl.createcheme.science.column.v3.FreeWaterSweepProbe 1.0 "0;100;200;400;800;1600"
```

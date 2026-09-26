# V3 trace-balance remedies: local-throughput scaling and a floor-supported solve

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc`, base `286a799`.
Root cause: `documentation/V3_TJL19_WET_DRAW_STALL_ROOT_CAUSE.md`.

The stall was a mismatch between two definitions of "small". A component material balance was scaled by the
component's authored feed flow, so an imbalance below 1e-8 of that feed was invisible to Newton and to the
acceptance audit; `V3BandedPivotedSolver` equilibrates each row by its own maximum and therefore gave the same
balance full weight. A trace profile that was inconsistent but invisible produced an exactly rank-deficient
Jacobian out of a physically converged state, the LU returned `SINGULAR`, and the damped Gauss-Newton fallback
crawled to the iteration budget.

## Commits

The brief asked for the two remedies as two commits. They cannot be separated: each half alone leaves the
suite red, measured on this branch.

- Change B alone (local-throughput scaling without the floor support) fails `V3PumparoundCalculatorTest`
  preset-draws-plus-pumparound, `V3PumparoundSteamCalculatorTest` three-pumparounds-three-draws, and leaves
  the TJL19 target case at residual 1.6e-6.
- Change A alone (floor support with the old feed-scaled balances) fails
  `V3PumparoundSteamCalculatorTest.tjl19SolvesSumpSteamWithThreePumparounds` and
  `V3StageTraceCalculatorTest.zeroCutoffPreservesLegacyDigestStreamsAuditAndDiagnosticsExactly`, and does not
  move the stall it was meant to remove.

What could be split off cleanly is the shared plumbing, and that is the first commit: the frozen row-scale
vector carried through both Jacobians and the line search, which is numerically a no-op while the scales are
still constants. The second commit is the formulation change, both halves together.

Two changes, described in the order they matter.

## Change A: the relative flow floor is the solved support (remedy 4)

`V3TruncationSupport.TRACE_FLOOR_FRACTION = 1.0e-10`, multiplying the component's flow scale (its authored feed
flow, with the existing `F_total * 1e-12` guard for a negligible feed). A stage point whose flow is below that
floor in every present phase is not an unknown and carries no material or equilibrium row. The floor is always
on; it is not a policy toggle and it composes with the authored mole-fraction cutoff:
`retained = above floor AND passes the cutoff rule`.

### Why 1e-10 and not 1e-11

- The documented spike is 2.3e-11 mol/s of a 45.8 mol/s feed component, 5e-13 relative. 1e-10 clears it by a
  factor of 200; 1e-11 by a factor of 20. The position of such a spike moved by tens of orders of magnitude
  between JDK 21 and JDK 25 on the same input, so a factor of 20 is not margin.
- Omitted material is bounded by construction: after the support refresh below, no retained neighbour delivers
  more than `FLOOR_REINSERTION_FACTOR` (10) floors into a removed point, so the sink-edge defect is at most
  1e-9 of a component's feed per edge. Measured on the CDU17 three-draw case: 8.5e-14 of the feed against a
  constructed limit of 6.4e-11. Two to three orders below the 1e-8 residual tolerance either way.
- A flow at 1e-10 of a 45.8 mol/s feed component is 4.6e-9 mol/s, 1.6e-5 mol/h. Nothing physical is lost.

One correction to the root-cause document's remedy list is worth recording, because it decides which of the
two changes actually unblocks the case. `V3BandedPivotedSolver` equilibrates every row by its own maximum
before pivoting, so **the LU is invariant under any row scaling**: change B cannot make a singular Jacobian
non-singular, and the 1e-13 pivot tolerance sees only the physical structure. What removes the rank deficiency
is change A deleting the two points whose rows collapsed onto one unit vector. Change B is what makes the
inconsistency *visible* — to the convergence gate, to the acceptance audit and to the merit — so that a state
carrying such a spike cannot be published and Newton has an incentive to correct one that survives. Both are
needed; neither is sufficient. Measured separately on this branch: change B alone leaves the TJL19 case at
residual 1.6e-6 and breaks three other pumparound cases; change A alone does not move the stall.

### Refresh

Support is frozen for the length of one Newton solve and then re-derived from the solved state, at most three
times per attempt (`MAXIMUM_FLOOR_SUPPORT_REFRESHES`), each repeat seeded from the solved state:

- a retained point that fell below the floor is dropped (this is the direction that removes a spike);
- a removed point holds exact zeros and can never re-enter from the state alone, so
  `V3ColumnCalculator.liftFloorInflow` lifts every removed point whose inflow from retained neighbours reaches
  `FLOOR_REINSERTION_FACTOR * floor` back to a positive value, splitting the delivered material evenly over
  the point's present outlets so the reinserted point's own balance is already closed at the seed;
- the loop stops as soon as the retained set is unchanged (`V3TruncationSupport.sameRetention`).

The lift runs inside `prepareAttempt`, so it applies to the deciding seed of *every* attempt, not only to a
refresh. That matters because a removed point's zeros are carried into the next continuation grid and the next
ramp rung: without lifting at derivation the support decision of a coarse grid would freeze into every finer
one and could only crawl back one node per refresh.

Two configurations were measured and rejected. Refreshing only after a *converged* attempt (the cheap reading
of the brief's "converges (or fails)") costs nine cases on the draw and steam suites: a stall is often exactly
the state that has just dried a point out, and the repeat from the corrected support is what converges the
rung. Disabling the refresh altogether costs seven.

`FLOOR_REINSERTION_FACTOR = 10` is hysteresis, and it is load bearing. A point is removed when its own flows
are below one floor, so the material it was passing on is also of the order of one floor; reinserting at the
same threshold puts every boundary point into a remove/reinsert cycle. The first implementation did exactly
that and the CDU17 three-draw case went from 5.0 s converged to 54 s non-converged, hitting the refresh cap.

The reinsertion test is a material-inflow test only. The brief also lists a `K*x` equilibrium test for the
vapour of a removed point; it is subsumed, because at steady state a point's own flow in either phase is
bounded by the total material delivered into it, which is exactly the quantity tested.

`V3TruncationFallback`'s untruncated retry is not reachable from the floor: it only exists on the public
cutoff path, and the cutoff-zero retry still carries the floor, so the singular system cannot be reintroduced
that way. One path can still discard the floor — `V3ColumnCalculator.prepareAttempt` falls back to identity if
the reduced ledger fails structural validation. That is a pre-existing correctness guard (an invalid ledger
cannot be solved) and was left alone; it is listed under "left undone".

`V3AcceptanceAuditor`'s `TRUNCATION_MASS_DEFECT` limit is now the sum of two independent budgets: the authored
cutoff budget as before, plus `V3TruncationSupport.floorDefectBoundFraction()`, the per-sink-edge construction
bound of the floor (with a factor two for the withdrawal fraction and roundoff). The check now fires whenever
a removed point was carrying material.

## Change B: material balances are scaled by their own local throughput (remedy 1)

`V3MeshResidualEvaluator.materialBalance` returns both the imbalance and the largest absolute term of the same
balance at the current state — liquid in from above after side-draw withdrawal, vapour in from below, the feed
term on the feed tray, and the two outlets. `materialScale` is

```
max( min(local throughput, flow scale_i), TRACE_FLOOR_FRACTION * flow scale_i )
```

where `flow scale_i` is the component's authored feed flow with the existing `F_total * 1e-12` guard. The row
scale is a continuous function of the state, so it is evaluated inside `evaluate` on every call. Equilibrium
and energy row scales are unchanged.

**Deviation from the brief, and why.** The brief specifies `max(throughput, floor)`. That was implemented and
measured first, and it costs six otherwise-passing cases (`V3ColumnCommandTest`, two `V3ExactWarmStartSweep`
points, `V3SimultaneousColumnSolverTest`, `V3SideDrawCalculatorTest` large-draw and modest-draw, the wet
cold-condenser pilot). The reason is the direction nobody was looking at: internal traffic *exceeds* the feed
wherever the reflux concentrates a component, so `throughput > feed_i` on most bulk trays and the pure
throughput scale **relaxes** those rows by a factor of four to eight relative to the previous formulation.
Equilibrium and energy rows keep constant scales, so that weight moves out of the material balances, and the
Armijo line search starts accepting steps that leave a bulk material imbalance of 5 to 20 mol/s standing. Two
side-draw cases and the wet cold-condenser pilot stalled that way, with the dominant residual a
`COMPONENT_MATERIAL_BALANCE` of a bulk cut, not a trace one.

Bounding the denominator by the component's feed makes the change a *strict tightening*: `scale_new <=
scale_old` everywhere, so no row that used to be visible becomes invisible, bulk rows keep exactly their
former weight, and only locally depleted points get stricter. That is the whole intent of the remedy, and it
recovers all six cases. The visibility of the documented spike is unaffected: 2.3e-11 mol/s against a floor of
4.5e-9 still reads 5.1e-3.

The floor keeps a physically empty balance from becoming an order-one demand. Change A guarantees a retained
point is above it, so it binds only for the points the support is forced to retain regardless of flow (the
feed tray, the side-draw trays and the band between them) and for a point collapsing inside a rung.

### The Jacobian decision: the scale is frozen at the base state

Both Jacobians differentiate `r(x) / s(x0)`, not `r(x) / s(x)`.

- The material rows in `V3BlockJacobianAssembler.assembleLocal` are exact log-flow derivatives assembled as
  `coefficient * flow / row.scale()`. They stay exact only with the scale frozen; the alternative would add a
  `-r ds/dx / s` term to every material entry and would have to be differentiated through `V3SideDraws`.
- `V3FiniteDifferenceJacobian` now divides each probe's *physical* row value by the base state's scale vector
  (`V3MeshResidual.scales()`, new `frozen` helper) instead of using each probe's own `scaledValue()`. The
  existing `V3BlockJacobianAssemblerTest` / `V3FiniteDifferenceJacobianTest` oracle comparison therefore holds
  to its unchanged tolerance.
- The dropped term is zero at a solution and finite-difference noise away from one, and freezing keeps every
  row scaling a pure left preconditioner of one and the same Newton system.
- `V3SimultaneousColumnSolver` follows: `frozenSquaredNorm` measures every Armijo candidate, every damped
  Gauss-Newton candidate and the verified final correction with the base state's scales, so the
  sufficient-decrease test compares like with like on the system the Jacobian was built for. The convergence
  gate and the acceptance audit still use each state's own scales, which is what makes an accepted result mean
  relative closure of every retained balance.

### Why a state-dependent scale cannot be gamed

Documented in `materialScale`. The denominator is the largest single term of the row, so growing that term by
`d` moves the numerator by the same `d` unless the remaining terms absorb it — a balanced row stays balanced
only if the material actually goes somewhere. Multiplying every term of a row by a common factor leaves the
ratio unchanged, and that is the intended reading: the row is then closed to a relative precision, exactly as
the tolerance claims. The flows are not free to move in the first place — the same coordinates carry the
neighbouring balances, the equilibrium rows and the energy rows, all scaled by constants.

### Formulation revisions

Every family is bumped, because accepted trace profiles change: `r2 -> r9`, `r4 -> r10`, `r5 -> r11`,
`r6-side-draws -> r12`, `r6-stage-heat -> r13`, `r7 -> r14`, `r8 -> r15`. Assumption and dataset revisions are
unchanged: no physical assumption moved. Pinned digest literals updated in `V3FlashTruncationColumnTest`,
`V3PumparoundCalculatorTest` and `V3StageTraceCalculatorTest`.

## Tests

| Test | Change | Result |
|---|---|---|
| `V3TraceFloorSupportTest` (new, 2 tests) | Pins `src/test/resources/science/column/v3/tjl19-wet-three-pumparound-three-draw-stalled-state-java21.txt`, the stalled Java 21 state | pass |
| `V3PumparoundSteamCalculatorTest.tjl19WithSumpSteamAndSideDrawsConverges` | Was the typed-failure contract `tjl19WithSumpSteamAndSideDrawsStaysInsideTheTypedFailureContract`; now a success assertion with the rung path, the stage-heat total and the ledger closure | pass |
| `V3DryMeshCoordinateMapTest` material derivative | `18/30` becomes `1.0`: the condenser row's own largest term now gets a unit entry | pass |
| `V3TruncationSupportTest.zeroCutoffStillAppliesTheFlowFloorAndReusesIdentityWhenNothingIsBelowIt` | Was `...ReusesIdentityWithoutInspectingASeedOrRebuildingTheProblem`; a zero cutoff now inspects the seed, and still reuses the identity support and rebuilds nothing when no point is below the floor | pass |
| `V3TruncationAuditTest` defect limit | `0.08` becomes `0.08` within 1e-9: the floor's per-sink-edge bound (4.4e-13 here) is added to the cutoff budget | pass |
| `V3StageTraceCalculatorTest.hotCondenserPhasePathKeepsItsFrozenMaskInsideTheRecomputedMassBudget` | Was `...RetriesUntruncatedWhenTheFrozenMaskExceedsItsMassBudget`; support refresh keeps the mask inside its recomputed budget, so the retry no longer fires. The retry policy itself stays covered by `V3TruncationFallbackTest` | pass |
| `V3ExactWarmStartSweepTest.coldAttempt` | Adds production's material-closed cold strategy as the last fallback; on this two-tray point it is the strategy that reaches the accepted liquid-only state, in the calculator as well | pass |
| Pinned formulation strings (3 files) | Revision bump | pass |

Two production fixes were needed on the way and are in the same commit:

- `V3ColumnCalculator` published the dry, no-draw material-closed surrogate as the result of a draws-bearing
  input. The guard that sends steam- and heat-bearing inputs on to the feature ramp did not mention side
  draws, and did not check that the candidate itself was draw-free. Latent before this change because the
  primary path used to reach the requested rung on `V3ColumnCommandTest`'s two-stage column.
- The stage-trace diagnostic event is emitted whenever the published support is non-identity, not only when a
  cutoff was requested, so a floor-supported result reports its truncated count and defect.

The new fixture asserts both remedies on the same state: TJL_PC09 on trays 10, 11 and 12 is below the floor
and not an unknown, the tray-11 and tray-12 material rows read 5.1e-3 against their own throughput (they were
5e-13 against the feed, and they report the same value because they are the pair the old equilibration
collapsed onto one unit vector), and the requested-rung solve restarted from that state converges in four
iterations with a verified final Newton correction and a passing mass-defect audit.

## Timings

`V3PumparoundCalculatorTest` and `V3PumparoundSteamCalculatorTest`, same machine, Gradle JVM (JDK 21).

| Case | Before | After |
|---|---|---|
| dry base | 1.94 s SUCCESS iter=18 | 2.43 s SUCCESS |
| dry uniform 5 MW | 2.80 s | 3.11 s |
| dry return-tray 5 MW | 2.80 s | 3.19 s |
| dry minus 1 kW | 2.15 s | 2.44 s |
| dry tjl19 uniform 5 MW | 5.00 s | 4.61 s |
| dry return-tray 40 MW | 8.70 s NONCONVERGENCE | 11.89 s SUCCESS |
| dry preset draws + pumparound | 4.96 s | 7.62 s |
| wet baseline | 8.78 s | 6.34 s |
| wet uniform 5 MW | 10.32 s | 6.91 s |
| wet zero reboiler 5 MW | 10.46 s | 4.15 s |
| wet 3 PA + 3 draws (CDU17) | 14.59 s | 11.81 s |
| wet over-condenser bound | 8.81 s | 6.25 s |
| TJL19 wet 3 PA | 11.97 s | 12.91 s |
| **TJL19 wet 3 PA + 3 draws** | **20.40 s NONCONVERGENCE 3.8e-8** | **10.66 s SUCCESS** |
| `V3SideDrawCalculatorTest` large-draw typed failure | ~7 s | 15.2 s |

Outside the 25% band: `preset-draws-plus-pumparound` (4.96 to 7.62 s), `return-tray-40-MW` (8.70 to 11.89 s,
and it changed outcome from a typed failure to a success), and the large-draw typed failure of
`V3SideDrawCalculatorTest` (to 15.2 s). All three are the repeated solve of a support refresh. The large-draw
case was 41 s before `MAXIMUM_STALLED_FLOOR_SUPPORT_REFRESHES` capped the stalled-attempt refreshes at one,
which is inside its own 45 s deadline but was failing that deadline under full-suite load. The wet lane is
uniformly faster because the trace tail is no longer carried as unknowns.

Cross-JVM (`build/pkgcmp/NewtonStallProbe dump`, TJL19 wet three-pumparound three-draw): both JDK 21 and JDK
25 now report `Converged residual=1.78e-10 iterations=3` on the requested draw rung in 10.2 s, against a
NONCONVERGENCE at 3.8e-8 on JDK 21 and a success on JDK 25 before. TJL_PC09 is removed by the floor on nodes
0 to 12 in both dumps and monotone above; a spike scan over all 19 components and both phases finds no
interior maximum above 1e6 times its larger neighbour in either state; the two states agree to a worst
relative difference of 3.9e-8, on a 2e-20 mol/s trace flow.

## Left undone

- `V3ColumnCalculator.prepareAttempt` still falls back to the identity support when the reduced ledger fails
  structural validation, which would reintroduce a full-rank-deficient system. It was not observed on any
  case in the suite; a typed failure would be the better outcome.
- The forced product-path band (feed tray, side-draw trays and the trays between them) is retained regardless
  of flow, inherited from the cutoff work. Those points are the only ones where the floor in `materialScale`
  binds. Removing the band would make the floor invariant unconditional, but it needs the "a side-draw tray
  point cannot be removed" rule in `V3TruncationSupport.requireCompatible` to be relaxed first.
- `V3AcceptanceAuditor`'s `LOCAL_COMPONENT_BALANCE` limit is still 1.0; the value it reports is now a relative
  closure, but the gate that enforces 1e-8 on material rows remains the solver's convergence test.
- The support refresh is the whole cost regression. Three cases sit outside the 25% timing band because of
  it, and the large-draw typed failure needed a dedicated cap on stalled-attempt refreshes to stay inside its
  own deadline. A cheaper refresh would re-solve only the stages whose support changed rather than the whole
  column.
- `V3SideDrawCalculatorTest.originalLargeDrawCase` and `V3PumparoundCalculatorTest.aLargeSingleTrayDutyStays
  WithinTheTypedFailureContract` both still pass, but the 40 MW single-tray duty now *converges* rather than
  failing. That is a behaviour improvement the tests tolerate; it was not investigated, and nobody has
  checked the result against a reference.
- The `V3TruncationSupport` product-path band (feed tray, side-draw trays, the trays between them) is still
  retained regardless of flow. Those points are the only ones where the floor in `materialScale` binds.
  Removing the band would make the floor invariant unconditional, but it needs the "a side-draw tray point
  cannot be removed" rule in `requireCompatible` to be relaxed first.
- The `V3ExactWarmStartSweepTest` liquid-only point only reaches an accepted state through the
  material-closed strategy that this change added to the test's cold chain. The bubble-point and coarse
  finite-difference recoveries, which used to carry it, now leave a dry middle tray on that two-tray column
  with the reboiler duty at zero. Nothing was loosened to make it pass, but the case is fragile.

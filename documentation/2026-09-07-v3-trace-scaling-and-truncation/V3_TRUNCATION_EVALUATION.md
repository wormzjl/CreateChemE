# V3 truncation: how it works today and where it can improve

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc` at `3f71fb4`. Evaluation only; no production
code changed. Probe: `build/pkgcmp/RefreshProbe.java` run against an instrumented copy of the science package
(`build/pkgcmp/src-ab/`, compiled to `build/pkgcmp/classes-ab/`) that prints one line per Newton attempt inside
the support-refresh loop and reads `MAXIMUM_FLOOR_SUPPORT_REFRESHES` from `-Dv3.refreshes`. Logs:
`build/pkgcmp/refresh-{3,1,0}.{log,err}`, summary script `build/pkgcmp/refresh-summary.awk`.

## 1. What "truncation" is in production

Two mechanisms share the name; only one is live.

| Mechanism | Where | Status |
|---|---|---|
| Authored stage-trace cutoff (mole fraction, with a matching feed-flash truncation and an untruncated retry on failure) | `TruncationPolicy`, `V3TruncatedFlash`, `V3TruncationFallback` | Config `stageTraceCutoffMolPercent`, default 0, so **off in-game**. Only tests exercise it. |
| Always-on flow floor support (`TRACE_FLOOR_FRACTION = 1e-10` of each component's feed) | `V3TruncationSupport.derive`, `V3ColumnCalculator.prepareAttempt` / `refreshFloorSupport` / `liftFloorInflow` | **Live on every attempt** since `b42d85a`. |

The floor support is the remedy for the trace-spike singularity. Per attempt (every continuation stage and
every ramp rung):

1. `liftFloorInflow` reinserts removed points whose retained neighbours deliver at least 10 floors, splitting
   the delivered material evenly over the point's phases.
2. `V3TruncationSupport.derive` removes every point below the floor in all present phases, except the product
   path band (feed tray to the outermost side-draw tray inclusive), prunes points unreachable from the feed,
   and falls back to identity if a draw-tray point or a structural phase would be lost.
3. The reduced ledger and problem are built; the seed is projected (retained zero flows become the floor).
4. Newton runs on the reduced problem; the audit runs; the support is re-derived from the solved state and,
   if the retained set changed, steps 1 to 4 repeat from the solved state. At most 3 refreshes, and at most 1
   after a stalled attempt.
5. `TRUNCATION_MASS_DEFECT` audits the material flowing into removed points against a bound of
   2 × 10 × Σ(floor per sink edge) / feed.

## 2. Measured behaviour (JDK 21, five cases, 30 stages)

Cap = `MAXIMUM_FLOOR_SUPPORT_REFRESHES`. Current production value is 3.

| Case | cap 3 | cap 1 | cap 0 |
|---|---|---|---|
| A dry CDU17 base | SUCCESS 2.7 s | SUCCESS 2.7 s | SUCCESS 2.6 s (36 it, 1.7e-10, no polish) |
| B dry preset draws + 3 MW cooler | SUCCESS 6.3 s | SUCCESS 7.0 s | SUCCESS 5.3 s |
| C wet TJL19, 3 coolers, 3 draws | SUCCESS 9.8 s | LINEAR_SOLVE_FAILURE 17.1 s | LINEAR_SOLVE_FAILURE 14.7 s |
| D dry 40 MW return-tray cooler | SUCCESS 11.1 s | SUCCESS 11.3 s | NONCONVERGENCE 7.9 s |
| E dry CDU17, 3 draws | SUCCESS 3.8 s | SUCCESS 3.5 s | NONCONVERGENCE 10.4 s |

Cap 0 (support frozen at the seed, never refreshed) reproduces the singular LU in case C and loses two more
cases, so the refresh is load-bearing, not a polish. Cap 1 loses case C because reinsertion advances one tray
per refresh (see 3.2).

Per-attempt breakdown at cap 3:

| Case | attempts | first-solve time | refresh time | refresh share | drops | reinserts (all restart from residual > 1) |
|---|---|---|---|---|---|---|
| A | 8 | 2.19 s | 0.26 s | 11% | 4 | 0 |
| B | 25 | 4.85 s | 1.04 s | 18% | 6 | 3 |
| C | 23 | 7.88 s | 1.54 s | 16% | 6 | 3 |
| D | 23 | 6.86 s | 4.01 s | 37% | 10 | 0 |
| E | 15 | 2.77 s | 0.79 s | 22% | 4 | 3 |

Three distinct refresh behaviours show up in the attempt lines:

- **Drop after a converged attempt**: initial residual 1e-6 to 0.13, converges in 1 to 3 iterations. This is the
  cheap polish the design intended (case A: 4 refreshes, 0.26 s).
- **Reinsertion**: every reinsertion restarts from a scaled residual of 2.0 to 4.7 and needs 5 to 11
  iterations, as much as the rung itself. The lifted point's material balance is closed but its equilibrium row
  is not: the phases get half the inflow each regardless of K, and at flow ≈ floor the row scale is the floor,
  so the mismatch is order one.
- **Drop after a stalled attempt**: in case D all four stalled-attempt refreshes were drops of one point, and
  every one of them stalled again with a *worse* final residual (0.070 → 0.220, 0.006 → 0.108, 0.007 → 0.127,
  0.008 → 0.148), 40 iterations each. 2.7 s of the 11.1 s. The stall there is the heat rung at 0.375, which
  the ramp subdivides three times and finally jumps past; no support change can fix it. The one stalled-attempt
  refresh that did help (case C, steam rung 0.375) was a reinsertion (426 → 430 retained).

Reinsertion is also a front: `liftFloorInflow` reads the old state, so a removed point two trays from the
retained region cannot be lifted until its neighbour has been, and each step costs a full re-solve. Case C at
steam rung 0.375 needed three consecutive reinsertions (426 → 430 → 433 → 435) and used the whole cap. Under cap
1 the rung was accepted at 430; the next rung's `prepareAttempt` then lifted 63 points at once (430 → 493),
Newton fell into the fine finite-difference path (0.4 s per iteration instead of 0.06), stalled, and the
refresh re-solve hit the singular LU.

The audit bound in `floorDefectBoundFraction` assumes a refresh has confirmed that no retained neighbour
delivers more than 10 floors into a removed point. When the cap stops the loop, that confirmation has not
happened; the audit still recomputes the actual defect, so it is safe, but a rung can be rejected for a defect
that one more refresh would have absorbed.

## 3. Room for improvement, ranked by evidence

### 3.1 Skip the stalled-attempt refresh when the support change is drop-only

Rule today: one refresh after any stalled attempt. Evidence: drop-only refreshes after a stall never converged
(4 of 4 in case D) and cost 40 iterations each; the reinsertion refresh after a stall converged (1 of 1, case C).
Change: in the loop, after `refreshFloorSupport`, if the attempt did not converge and the refreshed support has
no more retained points than the previous one, break and hand the stalled state to the ramp as today. Expected
gain: about 25% on case D and on every case that stalls on a ramp rung, zero loss. One line plus a test on the
40 MW case's attempt count.

### 3.2 Reinsert along the flow direction in one pass, with an equilibrium-consistent split

Two changes to `liftFloorInflow`:

- Sweep instead of a single pass over the old state: for each component, walk liquid downward and vapour
  upward using the just-lifted values as inflow, so a re-entering profile is restored across all its trays in
  one refresh. Removes the one-tray-per-refresh front and the cap-1 failure mode.
- Split the delivered inflow by the local equilibrium instead of 50/50: with the tray's total L and V and the
  component's K at the current temperature, `l = I / (1 + K·V/L)`, `v = I − l`. The reinserted point then
  satisfies both its material and its equilibrium row at the seed, and the refresh becomes a 1 to 3 iteration
  polish like a drop. K needs one fugacity evaluation per lifted point, which the evaluator already knows how
  to do (`localTerms`).

Expected gain: reinsertion refreshes fall from 5 to 11 iterations to 1 to 3 (about 0.5 to 0.8 s on B, C, E) and
the refresh cap stops binding. This also makes the audit bound's precondition hold whenever the loop exits.

### 3.3 Retire the authored mole-fraction cutoff

It is off in production, its semantics (a fraction of the tray's phase total) are a weaker version of what the
floor already does, its fallback re-runs the entire condenser and continuation chain untruncated on any
failure, and it drags a parallel feed-flash truncation (`V3TruncatedFlash`, `V3FlashPhaseSupport`,
`V3FlashTruncationEvidence`) along. Keeping it costs a `TruncationPolicy` parameter on every solver entry point
and a second formulation label. Removing it does not change any zero-cutoff digest. If a knob is wanted, the
floor fraction is the meaningful one, and it should stay a constant unless a case shows otherwise.

### 3.4 Let the floor act inside the product-path band

The band (feed tray to the outermost draw tray, all components) is retained regardless of flow. On the preset
draws it forces about 45 below-floor points to stay unknowns (case A removes 130 of 480, case E only 85). Those
are the only points where the floor in `materialScale` binds, and they are exactly where the trace-spike
singularity can still form, because the floor cannot remove points there. The rule exists so that a specified
draw always has a retained path to the feed; the reachability pruning already guarantees that for retained
points, so the band could be reduced to "the draw tray's own points for components with a retained feed path"
after relaxing the side-draw-tray check in `requireCompatible`. Worth doing before the draw-rate wall is
re-screened, since the heavy-draw stall was this mechanism.

### 3.5 Lower priority

- The first attempt at each continuation stage still solves the full identity support (retained 90/90,
  145/150, 233/255, 393/480) and then drops 5 to 43 points; deriving support from the interpolated seed of the
  next stage would save one drop refresh per stage, about 0.1 to 0.3 s. Small.
- `prepareAttempt`'s identity fallback on a rejected reduced ledger is silent apart from a note. It did not
  fire in any of the five cases; making it a typed event is hygiene, not performance.
- Refresh re-solves rebuild evaluator, coordinate map and ledger from scratch. At 1 to 3 iterations per polish
  the rebuild is not the cost; the iterations are. No action.

## 4. What is not a truncation problem

Case D's cost is dominated by the heat rung at 0.375 that stalls four times before the ramp jumps to 1.0 and
succeeds with a condenser phase correction. That is a ramp-schedule question (the rung sits at a condenser
phase transition), not a support question; 3.1 removes the doubled cost but not the stall.

## 5. Residual trace flow in the accepted state, and phase-specific truncation

Probe: `build/pkgcmp/PhaseTraceProbe.java` (reflection into the continuation solve, same as `TrayBalanceProbe`),
log `build/pkgcmp/phase-trace.log`. Every retained point of the accepted 30-stage state is binned by
`min(l, v) / feed_i` and by which phase, if any, is below the floor.

| Case | retained / removed | flow unknowns | min(l,v) < 1e-6 feed_i | of which forced band points below floor in both phases | one phase below floor, other above (liquid-tiny / vapour-tiny) |
|---|---|---|---|---|---|
| A dry base | 350 / 130 | 692 | 32 | 0 | 0 / 7 |
| E dry 3 draws | 395 / 85 | 782 | 66 | 36 | 0 / 6 |
| C wet TJL19 3 PA 3 draws | 491 / 117 | 972 | 130 | 59 | 5 / 22 |
| D dry 40 MW | 326 / 154 | 652 | 44 | 0 | 0 / 15 |

Where trace flow still lives:

1. **Forced band points** (feed tray to outermost draw tray): 36 to 59 points with both phases below the floor,
   the single largest block of trace unknowns on any case with draws. Only a rule change removes them (3.4).
2. **Points between the floor and 1e-6 of feed**: 25 to 70 per case, mostly heavy pseudo-components in the
   trays above their condensation front and light ends below the feed. Raising the floor to 1e-8 would remove
   only 8 to 15 more points per case and would loosen the audit bound a hundredfold. Not worth it.
3. **Phase-asymmetric points**, one phase below the floor and the other above: 6 to 27 per case, 1 to 3% of
   flow unknowns. Dry cases show only the vapour-tiny direction (heavy components, worst l/v 2.6e8 to 7.9e10);
   the wet case adds five liquid-tiny points for light ends in the steam-stripped section and a worst l/v of
   1e23 for the heaviest cuts. All of them are numerically benign: the equilibrium row is in log composition,
   the material row is scaled by the component's throughput on that tray (the large phase), and none of them
   appears in the singular-pair mechanism, which needs both phases tiny on adjacent trays.
4. **First attempts of each continuation stage** run on the support carried from the previous grid, so the
   newly trace points of that grid (5 to 43) sit in the unknowns for the expensive cold solve and are dropped
   only by the refresh afterwards. The 4-stage start is the only attempt at full identity support.
5. The feed flash in production is the full flash; the phase-truncated flash exists only behind the authored
   cutoff. Water in the wet model is a separate vapour flow with its own condenser split and is not part of the
   support at all.

### Phase-specific truncation

The machinery is half there: `V3CondenserComponentPhases.hasLiquid` is already a per-(node, component) liquid
presence rule, used only at the condenser node, and `V3FlashPhaseSupport` has the LIQUID_ONLY / VAPOR_ONLY
vocabulary at the feed-flash level. Extending it means making vapour presence per (node, component) too, and
touching the ledger, coordinate map, evaluator (material references, equilibrium presence, energy sums),
auditor (phase-specific sink edges), support derivation, seed projection and the reinsertion lift. Six classes
and a formulation revision.

What it would buy on a CDU: 1 to 3% fewer flow unknowns. Per-iteration cost in the probe does not track the
retained count within a case (case D: 23 ms per iteration at 350 retained, 31 at 341), because the cost is the
per-node property evaluation and the banded factorization whose bandwidth is set by the component count, not
by which points are present. So the performance gain is a few percent at most, and there is no robustness gain
to be had, since the asymmetric points are not the ones that break the Jacobian.

What it would change physically: a vapour-only point means the component cannot condense on that tray, a
liquid-only point that it cannot evaporate. Both are exactly the approximations the truncated feed flash
already makes, and the omitted mass is bounded by the same floor argument, so the audit story carries over.

Verdict: not for the CDU. The case for it is a vacuum tower, where heavy cuts above their condensation front
and light ends in the flash zone would make the asymmetric set much larger than 3%. Revisit when the VDU work
starts, with this probe as the measurement.

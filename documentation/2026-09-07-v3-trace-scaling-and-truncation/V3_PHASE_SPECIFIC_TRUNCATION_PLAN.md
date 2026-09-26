# V3 phase-specific truncation and refresh hardening: implementation plan

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc` at `3f71fb4`. Follows
`documentation/V3_TRUNCATION_EVALUATION.md` (sections 3 and 5). Motivation from the user: the solver will
extend to a vacuum tower, where heavy cuts above their condensation front and light ends in the flash zone make
one-phase-absent points common; the CDU numbers (1 to 3% of unknowns) understate the VDU case.

## 0. Design in one paragraph

Today a stage point (node, component) is either fully present (liquid and vapour unknowns, material and
equilibrium rows) or fully removed (no unknowns, no rows, its inflow is a sink counted by the mass-defect audit).
The plan makes presence per phase: a point can be BOTH, LIQUID_ONLY, VAPOR_ONLY or ABSENT. A one-phase point
keeps its material row, which now conserves the component into the present phase alone, and loses its
equilibrium row; the absent phase flow is exactly zero. Removal of a phase is decided by the same flow floor as
today (`TRACE_FLOOR_FRACTION` of the component's feed); reinsertion of a phase is decided by the flow that
equilibrium would give it, so the refresh becomes a one-to-three-iteration polish instead of an order-one
restart. Mass is never lost at a one-phase point, so the sink-edge audit stays as it is and a new audit bounds
the equilibrium-implied flow of every absent phase. The condenser's existing per-component liquid rule is the
special case node 0 of the same mask.

## 1. Vocabulary and the single presence query

`V3TruncationSupport` gains a per-point phase mask:

```
enum PointPhases { ABSENT, BOTH, LIQUID_ONLY, VAPOR_ONLY }
boolean retainsLiquid(node, component)   // BOTH or LIQUID_ONLY
boolean retainsVapor(node, component)    // BOTH or VAPOR_ONLY
boolean retains(node, component)         // not ABSENT  (unchanged meaning)
```

`V3ColumnProblem` gets the only two presence queries the rest of the package may use:

```
boolean hasLiquidUnknown(node, c) = topology.hasLiquidPhase(node)
                                   && condenserComponentPhases().hasLiquid(topology, node, c)
                                   && truncationSupport().retainsLiquid(node, c)
boolean hasVaporUnknown(node, c)  = topology.hasVaporPhase(node) && truncationSupport().retainsVapor(node, c)
boolean hasEquilibriumRow(node, c) = hasLiquidUnknown && hasVaporUnknown
```

Every current call of `condenserComponentPhases().hasLiquid(...)`, `topology.hasVaporPhase(node)` used per
component, and `truncationSupport().retains(...)` in these classes is replaced by the problem queries:
`V3DegreeOfFreedomLedger` (unknown and equation enumeration, material references), `V3DryMeshCoordinateMap`
(unchanged if it only walks ledger unknowns; verify), `V3MeshResidualEvaluator` (material balance terms,
composition builder, `localTerms`), `V3AcceptanceAuditor` (`FINITE_TOPOLOGY`, sink edges), `V3TruncationSupport`
(`projectSeed`, `enumerateSinkEdges`, `reachableFromFeed`, `phasesNonempty`), `V3ColumnCalculator`
(`liftFloorInflow`), `V3BlockJacobianAssembler` and `V3StageBlockLayout` (check whether they assume both flows
per retained point; the local-block Jacobian must see the same unknown set as the ledger), `V3ColumnInitializer`
and `V3ColumnStreamProperties` (seed and publication run on identity support; confirm and leave alone).
`V3ColumnTopology.hasVaporPhase(node)` stays as the node-level structural rule.

## 2. Residuals for a one-phase point

- Material row (unchanged form): `liquidIn + vaporIn + feed − liquidOut − vaporOut`, where an absent outlet is
  zero and an inflow from a neighbour whose corresponding phase is absent is zero. Throughput scale unchanged.
- Equilibrium row: only when `hasEquilibriumRow`. Composition builder: an absent phase flow must be exactly
  zero (throw otherwise, as for removed points today) and contributes nothing to that phase's composition.
- Energy row: unchanged; phase enthalpy sums use the present flows.
- Sum consistency: `phasesNonempty` must hold per phase per node: every node with a structural liquid phase
  keeps at least one liquid unknown, likewise vapour.

Physical meaning: LIQUID_ONLY on a tray means the component does not evaporate there (all of it leaves in the
liquid); VAPOR_ONLY means it does not condense there. Both are the approximations `V3FlashPhaseSupport` already
makes for the feed flash.

## 3. Deriving the mask

`V3TruncationSupport.derive(problem, cutoff, decidingState)`:

```
for each (node, c) with a structural liquid and/or vapour phase:
  lAbove = hasLiquid && l >= floor_c ; vAbove = hasVapor && v >= floor_c
  if neither phase is present structurally: BOTH (untestable, as today)
  else if !lAbove && !vAbove: ABSENT      (today's rule)
  else if lAbove && vAbove:  BOTH
  else: LIQUID_ONLY / VAPOR_ONLY
authored cutoff (when > 0) composes as today on the point as a whole: it can only turn a point ABSENT.
product-path band: unchanged in this work package (forced BOTH); see WP-T4.
reachability pruning: a point is reachable if material can arrive by a present phase of a neighbour:
  liquid from node-1 requires retainsLiquid(node-1, c); vapour from node+1 requires retainsVapor(node+1, c).
  A LIQUID_ONLY point can still pass material downward, a VAPOR_ONLY point upward.
identity fallbacks unchanged.
```

`sameRetention` compares masks. `truncatedPointCount` counts ABSENT points; add `absentPhaseCount` for the
event line.

## 4. Seed projection and reinsertion (replaces `liftFloorInflow`)

`projectSeed`: a present phase with zero flow gets the floor (as today); an absent phase is zeroed.

Reinsertion happens in `prepareAttempt` before `derive`, from the solved state, and must be phase-aware and
equilibrium-consistent. New calculator helper `liftSupport(problem, state, thermo, workspace)`:

1. For every node compute the node properties once (temperature, both phase compositions from the present
   flows, fugacity coefficients for all public components; the evaluator's `nodeProperties` does this). Define
   for component c on node n: `K_c = exp(lnφ_L,c − lnφ_V,c)` and the phase totals `L_n`, `V_n` from the state.
2. Fully-removed points, sweep in flow direction per component: walk nodes top to bottom accumulating liquid
   inflow from the tray above using the *lifted* value, then bottom to top for vapour inflow; a point whose
   inflow `I` reaches `FLOOR_REINSERTION_FACTOR * floor_c` is lifted with the equilibrium split
   `l = I / (1 + K_c V_n / L_n)`, `v = I − l` (single-phase nodes: all of `I` into that phase). Continue the
   sweep with the lifted values so a re-entering profile is restored across all its trays in one pass.
3. One-phase points: compute the equilibrium-implied flow of the absent phase,
   `v* = K_c (V_n / L_n) l` for a LIQUID_ONLY point, `l* = v / (K_c V_n / L_n)` for VAPOR_ONLY. If it reaches
   `FLOOR_REINSERTION_FACTOR * floor_c`, lift the absent phase to `v*` (or `l*`); the point becomes BOTH at
   derive time. Lifting does not alter the present phase, so the material row starts with a residual of order
   `v*` against a throughput of order `l`: small by construction.
4. `derive` then runs on the lifted state and removes whatever is below the floor.

Expected effect (measured baseline in the evaluation): reinsertion restarts fall from a scaled residual of 2
to 5 and 5 to 11 iterations to well under 1 and 1 to 3 iterations; the one-tray-per-refresh front disappears.

## 5. Refresh loop (WP-T1, independent one-liner)

In `solveSingleProblem`'s loop: after `refreshFloorSupport`, if the attempt did not converge and the refreshed
support adds no phase and no point (only removals), break instead of re-solving. Measured: drop-only refreshes
after a stall never converged (4 of 4 on the 40 MW case) and cost 40 iterations each. Keep the existing caps.

## 6. Audit

- `TRUNCATION_MASS_DEFECT`: unchanged. Sink edges exist only into ABSENT points, so they must now check the
  source phase is present: liquid edge requires `retainsLiquid(source)`, vapour edge `retainsVapor(source)`.
- New `PHASE_TRUNCATION_DEFECT`: over all one-phase points, the largest equilibrium-implied absent-phase flow
  relative to the component's feed, computed freshly from the candidate state with the same K as section 4.
  Limit `FLOOR_DEFECT_SLACK * FLOOR_REINSERTION_FACTOR * TRACE_FLOOR_FRACTION` (2e-9). Present in every audit
  whose support has at least one one-phase point; absent otherwise so heat-free and truncation-free audits keep
  their check counts.
- `FINITE_TOPOLOGY`: present phase flows finite and positive, absent phase flows exactly zero.

## 7. Revisions, digests, events

- Accepted states change (one-phase points now carry their whole flow in one phase), so every formulation
  label bumps: r9..r15 become r16..r22 in `formulationRevision`. Golden digests and pinned residuals in tests are
  re-pinned from the new run; no tolerance is loosened. The legacy-digest test
  (`V3StageTraceCalculatorTest.zeroCutoffPreservesLegacyDigest...`) re-pins its expected digest.
- Event line: `stage-trace cutoff=…; truncated=A/N; one-phase=P; closure-pruned=…; defect/feed=…;
  phase-defect/feed=…`.
- No transport or GUI change: support is attempt-local and never serialized.

## 8. Work packages and gates

| WP | Content | Gate |
|---|---|---|
| T1 | Stalled drop-only refresh skip (section 5) | 40 MW case attempt count drops from 23 to ≤ 19; suite green |
| T2 | Phase mask, presence queries, ledger/coordinates/evaluator/auditor/support changes, per-phase derive, projection, `PHASE_TRUNCATION_DEFECT`, revision bump, tests | Suite green with re-pinned digests; `PhaseTraceProbe` shows zero one-phase-below-floor points retained as BOTH; all five evaluation cases SUCCESS at cap 3 |
| T3 | Equilibrium-consistent sweep reinsertion (section 4) | `RefreshProbe` at cap 3: no reinsertion attempt starts above scaled residual 0.5; case C succeeds at cap 1; timings within ±10% or better on A, B, D, E |
| T4 | Product-path band reduced to the draw trays' own points for components with a retained feed path (relax the side-draw-tray rule in `requireCompatible`) | Cases B, E and the wet C succeed; `V3SideDrawCalculatorTest` green; if any draw case regresses, revert T4 and record why |

The authored mole-fraction cutoff, its feed-flash truncation and `V3TruncationFallback` are left in place in
this plan; retiring them (evaluation 3.3) is a separate decision.

## 9. Measurement harness

`build/pkgcmp/RefreshProbe.java`, `build/pkgcmp/PhaseTraceProbe.java`, `build/pkgcmp/refresh-summary.awk`.
They compile against an instrumented copy of the science package in `build/pkgcmp/src-ab/` (copy of
`src/main/java/com/wormzjl/createcheme/science/**`, with `MAXIMUM_FLOOR_SUPPORT_REFRESHES` read from
`-Dv3.refreshes` and a `PROBE attempt …` line printed after each attempt's audit inside the refresh loop).
After changing production sources: re-copy, re-apply the two edits, recompile with
`"C:/Program Files/Java/jdk-21.0.11/bin/javac" --release 21 -nowarn -d build/pkgcmp/classes-ab -cp <gson jar> @build/pkgcmp/sources-ab-copy.txt build/pkgcmp/RefreshProbe.java build/pkgcmp/PhaseTraceProbe.java`,
copy `src/main/resources/{data,assets}` into `classes-ab`, run with JDK 21. Baseline numbers are in
`build/pkgcmp/refresh-3.{log,err}` and `build/pkgcmp/phase-trace.log`.

## 10. WP-T5: configurable convergence closure (user request 2026-09-07, clarified)

The user tolerates 0.05 to 0.1% mass imbalance **as the convergence criterion**, to make closure easier on hard
cases. This is the Newton stopping tolerance and the gates that certify a converged state, not the truncation
mass budget (section 6 stays as designed).

### What the tolerance means

Every residual row is already a relative quantity: a material row is the component imbalance divided by its
largest local flow term (bounded by the feed and the floor), an equilibrium row is a log-composition
difference, an energy row is the tray imbalance over `F_total × 1e5 W`. A closure tolerance `τ` therefore
reads as "every component balance on every tray closes to τ of its throughput, equilibrium holds to τ in K,
and each tray's energy closes to τ of a reference enthalpy flow". Today `τ = 1e-8`
(`V3ColumnCalculator.SCALED_RESIDUAL_TOLERANCE`).

### Knob

- Config next to the cutoff: `columnV3ConvergenceClosurePercent`, range [0, 0.1], default 0.
  `τ = max(1e-8, value / 100)`. Zero reproduces today bit for bit.
- Plumbing: `ProcessSolveServices.V3ColumnCommand` carries the fraction; `V3ColumnCalculator.calculate(input,
  control, cutoff, closure)`; the value travels with `TruncationPolicy` (rename to `SolvePolicy`) into every
  `solveSingleProblem`, ramp rung, continuation stage, recovery and phase-correction solve. No solve may keep
  a hard-coded `SCALED_RESIDUAL_TOLERANCE`.

### Gates that must move with τ

| Gate | Today | With τ |
|---|---|---|
| Newton stop `maximumResidual <= scaledTolerance` | 1e-8 | τ |
| `V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE` (final step) | 1e-8 | τ (a final step that moves flows by less than τ is consistent with a τ closure) |
| `MAXIMUM_LINEAR_BACKWARD_ERROR` | 1e-12 | unchanged (linear-solve quality, not closure) |
| `MAXIMUM_TEMPERATURE_STEP_RATIO` | 1.0 | unchanged |
| `verifyFinalNewtonCorrection` / `verifiedCandidate` | parameterised by `scaledTolerance` | receives τ |
| Audit `EQUILIBRIUM` | 1e-8 | max(1e-8, τ) |
| Audit `LOCAL_COMPONENT_BALANCE`, `ENERGY_BALANCE` | 1.0 | unchanged (already looser) |
| Audit `GLOBAL_ENERGY_BALANCE` | 1e-6 × largest term | max(that, nodeCount × τ × energy scale): the boundary closure is the sum of tray closures |
| Audit `CONDENSER_ENERGY_BALANCE` | 1e-6 × largest | max(that, τ × largest) |
| Audit `CONDENSER_PHASE` split | 1e-8 | max(1e-8, τ) |
| Calculator publication (`satisfiesGates() && audit.accepted()`) | | same rule with the τ-parameterised evidence |

`V3ConvergenceEvidence.satisfiesGates()` becomes `satisfiesGates(τ)`; the evidence record and the published
diagnostics carry τ so a result states the closure it was accepted at.

### Digest, labels, display

- `V3InputDigest` includes the closure bits when τ > 1e-8 (as for the cutoff); formulation label gets
  `-closure<exponent>`; `assumptionsRevision` unchanged.
- `V3ColumnDisplayResult` already carries `maximumScaledResidual`; add `closureTolerance` so the Convergence
  page can show "accepted at closure 1e-3" (the GUI line itself is a separate, in-game-verified change; the
  transport field is added here with NBT/wire version bumps 7 → 8 and codec tests).

### Tests and measurement

- τ = default: identical outcomes and digests on the five evaluation cases and the whole suite.
- τ = 1e-3 on cases A to E: SUCCESS, fewer Newton iterations and lower time (report), every published
  balance within τ.
- `V3SideDrawCalculatorTest.originalLargeDrawCase` (typed NONCONVERGENCE today) and the draw-rate wall cases
  from `documentation/V3_CDU_CONVERGENCE_RISK.md` at τ = 5e-4 and 1e-3: report which become SUCCESS and at what
  residual. This is the payoff measurement.
- `ProcessSolveServices` rejects values outside [0, 1e-3]; config default round-trips as 0.
- Interplay with truncation: the floor and the refresh are unchanged; a trace row at the floor scale closes
  to τ × floor, which is negligible against the audit bound.

### What τ will not fix

Stalls whose residual plateaus above τ (the 40 MW heat rung at 0.375 stalls at 0.006 to 0.07, the wet steam
rung at 0.096) are ramp problems and stay typed failures; τ helps the crawl cases that plateau between 1e-8
and τ, which is exactly the trace-pair and heavy-draw family recorded in the convergence-risk note.

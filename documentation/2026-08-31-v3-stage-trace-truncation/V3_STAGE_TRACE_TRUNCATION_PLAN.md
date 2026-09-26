# V3 In-Column Stage-Trace Truncation — Plan

- **Status: PLANNING ONLY. No implementation exists.** An implementation spike was briefly started on
  2026-08-31 and fully rolled back on the design owner's instruction (worktree and branch deleted, nothing
  committed). What that spike *verified about the existing code* is folded into this plan as checked facts;
  none of its code survives.
- **Provenance of this document**: written from direct source verification of the baseline in this session
  and from the design owner's confirmed decisions. It deliberately does not build on any earlier planning
  document. It is self-contained: everything needed to implement is in this file plus the source tree.
- **Baseline**: `codex/hybrid-solver` tip `6185117` (sits on `main` @ `2dcb0f8`). All class references are
  under `src/main/java/com/wormzjl/createcheme/science/column/v3/` unless noted.
- **Venue**: implement from a fresh worktree branched at the baseline, located outside `run/` and `.claude/`.

## 0. Intent and confirmed decisions (design owner, 2026-08-31)

Truncate materials **during the column's material-balance calculation**: while the MESH system is being
solved, a component whose presence at a stage has fallen below a threshold is treated as exactly zero *at
that stage* — it stops being tracked there, its physically meaningless profile tail (e.g. a residue
pseudocomponent at 3.2e-50 mol% in a 50 °C distillate, observed in-game) is not computed, and the equation
system is smaller. This is **not** a feed filter: the authored feed is untouched, and a component solves
normally wherever it genuinely exists.

Confirmed design axes:

1. **Unit: stage mole fraction.** A component is "absent at a stage" when its mole fraction there is below
   the threshold τ. Config expresses τ in mol%.
2. **Scope: every V3 solve.** A general config-gated feature; τ = 0 disables it exactly.
3. **Support frozen per solve attempt.** The truncated set is decided once per attempt from that attempt's
   incoming state; Newton then solves a fixed structure. No mid-attempt structural changes.

## 1. Verified architecture facts this design stands on

Each fact below was checked against the baseline source in this session.

- **F1 — Zero flows are unrepresentable in solver coordinates.** `V3DryMeshCoordinateMap.logFlow` throws for
  any flow ≤ 0; coordinates are `ln(flow / scale)`. Therefore "treat as zero during calculation" can only
  mean *structural absence* — the point's unknowns and equations must not exist. There is no softer option.
- **F2 — Structural absence already exists in two forms.** `V3ActiveComponentBasis` removes whole
  components with exact-zero feed; `V3CondenserComponentPhases`/`V3ColumnTopology` remove phases at the
  condenser by branch. The ledger, state validation, residual assembly, and stream publication all already
  handle "this slot does not exist and must be exactly 0.0". Stage-trace truncation generalizes the same
  idea from whole components / whole phases to individual component-stage points.
- **F3 — The ledger is the single existence authority, with exactly three consumers.**
  `V3DegreeOfFreedomLedger.create` enumerates unknowns (per-node per-component liquid/vapor flows where
  phases exist, per-node temperatures) and equations (material per point; VLE per two-phase point; energy
  per node) and proves squareness + structural rank by bipartite matching. Its output is consumed via
  `problem.degreeOfFreedomLedger()` by exactly three classes: `V3DryMeshCoordinateMap` (unknown list →
  coordinates), `V3MeshResidualEvaluator` (equation list → rows), `V3StageBlockLayout` (both → per-node
  blocks). Masking the ledger therefore masks the whole numerical system.
- **F4 — `V3ColumnProblem` has a single construction path.** Only `V3ColumnProblemResolver.resolve` builds
  it (package-private constructor). Its invariants (ledger topology/component-count/specs agreement) hold
  equally for a reduced ledger. A "masked problem" — same input, topology, basis, phases, pressures, but a
  reduced ledger — is a legal instance, and handing it to the solver stack masks everything ledger-driven
  with no consumer API changes.
- **F5 — Exact-zero states make the material and energy algebra transmitted-terms automatically.**
  `V3MeshResidualEvaluator.materialResidual` reads neighbor flows directly from the state
  (`state.liquidFlow(node-1, c)`, `state.vaporFlow(node+1, c)`, reflux share
  `R/(1+R) · state.liquidFlow(0, c)`); `energyResidual` sums phase enthalpies as `phaseTotal ×
  molarEnthalpy`. If truncated points are held at exactly 0.0 in every state the evaluator sees, inflows
  from truncated sources contribute exactly nothing and enthalpy sums skip them — the rows for retained
  points need **no code changes** to become transmitted-terms correct.
- **F6 — Exactly two numerical call sites break on zeros and need mask-aware skips.**
  (a) `V3MeshResidualEvaluator.normalizedPublicPhaseComposition` throws
  "active component flow must be positive for logarithmic VLE" on any zero flow except the condenser
  component-phase exclusion — a truncated point must be skipped there exactly like that existing exclusion
  (it contributes nothing to the composition the fugacity model sees; the phase renormalizes over retained
  components). (b) `localTerms` probes the VLE residual for every component with
  `hasVaporLiquidEquilibrium`; truncated points must be skipped or the probe computes `ln(0)`.
- **F7 — Decode produces mask-consistent states for free.** `V3DryMeshCoordinateMap.decode` allocates
  zero-filled arrays and writes only ledger unknowns; with a reduced ledger, truncated points remain exactly
  0.0 in every decoded state. No `V3DryMeshState` changes are needed for solver-produced states.
- **F8 — Encoding a seed requires strictly positive retained flows.** `encode` throws on any nonpositive
  flow at a coordinate. Seeds are produced *before* the mask exists (initializer / preconditioner /
  continuation interpolation), so a seed must be **projected** onto the mask before encoding: truncated
  points zeroed; any retained phase-present slot that is nonpositive floored to a small positive value
  (proposal: `flowScale(component) × 1e-10`, a deep but finite log coordinate Newton can lift).
- **F9 — `V3StageBlockLayout` would reject a reduced ledger as written.** Its per-node expected block size
  is computed from topology/condenser-phases over *all* components, and its per-component validation
  demands found-liquid/found-vapor equal phase presence for *every* component. Both must become mask-aware
  (expected size = Σ retained-point phases + temperature; per-component expectation consults the mask).
  This is a hard blocker discovered by verification, not speculation: the layout is constructed on every
  solve and feeds the banded-matrix block structure.
- **F10 — The reflux inflow edge can carry a zero coefficient.** Tray 1's liquid inflow is
  `R/(1+R) · L(0, c)`; at R = 0 the edge exists structurally but transmits nothing. Any inflow-support
  reasoning must treat the condenser→tray-1 edge as absent when R = 0.
- **F11 — Bandwidth and coloring may not shrink.** The banded system's bandwidth follows the fullest node
  block (the feed tray retains everything — see G1), and `V3FiniteDifferenceJacobian`'s distance-3 stage
  coloring shares perturbations by slot; fewer rows do not automatically mean fewer perturbation groups.
  Speedup expectations must be tempered accordingly (see §7).
- **F12 — The audit and digest are the correctness boundary.** `V3AcceptanceAuditor` independently
  re-derives residuals for every published candidate, and `V3InputDigest` hashes every input field plus
  three revision strings so a published result can never be mistaken for a different problem. A truncated
  solve deliberately does not close its material balance — the audit must therefore understand the defect
  exactly (else every truncated solve fails audit, or closure checking would have to be weakened — both
  unacceptable), and τ must enter the digest with a formulation-revision bump because the equation system
  changes.

Empirical evidence from this session (measured on this codebase, 30-stage TJL pilot, 400 K condenser,
R = 2, 8 MW, 250 kPa): removing ppm-band trace components from the solved system cut wall time from 13.1 s
to 5.3 s (15 → 12 components) and flipped one acceptance-audit failure into a success; conversely one
truncated attempt failed where the authored problem solved — so truncation needs an untruncated fallback in
*both* prudence directions. Product-surface drift from removing a trace measured ≈ 11× its feed-level
fraction. Separately: this operating point sits near the total-condensation boundary and the condenser
branch choice can flip between JVM runs on last-ulp `Math.exp/log` differences across interpreter/JIT tiers
(only `StrictMath` is tier-reproducible; tracked as separate follow-up) — evaluation must not read such
flips as truncation regressions.

## 2. Design

### 2.1 The mask: `V3TruncationSupport` (new, package-private)

Immutable per-attempt value derived from `(problem, τ, deciding state)`; the deciding state is the
attempt's seed *after* preconditioning. Holds: the retained-point matrix, τ, the sink-edge list (§2.3),
a closure-pruned count, and a bounded note. Factories `identity(topology, componentCount)` and
`derive(problem, τ, decidingState)`.

**Truncation rule.** Point `(n, i)` is truncated iff its mole fraction is strictly below τ in every phase
that is (a) structurally present at the point (liquid per `V3CondenserComponentPhases.hasLiquid`, vapor per
`topology.hasVaporPhase`) and (b) testable in the deciding state (positive phase total at the node). A
point with no testable phase is conservatively retained. The unit is the whole point, never one phase: by
F1 a zeroed phase cannot keep coordinates, and the VLE row couples both phases, so per-phase truncation
would leave either an undefined logarithm or an unmatched equation. Fractions are per-phase
(`flow / phaseTotal` at the node). Strictness at the boundary: exactly-τ is retained.

**Invariants, each forced by the code (F-references), each constructor-enforced and unit-tested:**

- **T1 (feed exemption).** Every point on the feed tray is retained. The feed injection `F_i` lands in that
  point's material row (F5); deleting the row would annihilate a component's entire feed unaccounted. Note
  every active component has positive feed by `V3ActiveComponentBasis` construction, so this is simply:
  the feed-tray row of the mask is all-true.
- **T2 (inflow-support closure).** Every retained non-feed point keeps ≥ 1 retained inflow source, else its
  material row forces strictly positive outflows (F1) to sum to zero — infeasible before Newton starts.
  Sources by node: condenser ← tray-1 vapor; tray n ← liquid of `(n−1, i)` (for n = 1: only when the
  condenser point holds liquid **and** R > 0, per F10) or vapor of `(n+1, i)`; reboiler ← tray-N liquid.
  Enforced by iterative pruning to a fixpoint (each pass only removes points, so it terminates; a point
  stranded by the rule was itself fed only by sub-τ traffic, so cascades are short). Pruned points are
  counted separately in provenance.
- **T3 (phase non-emptiness).** After pruning, every structurally present phase at every node retains ≥ 1
  component — phase totals appear as denominators and enthalpy carriers (F5/F6). With τ ≤ 0.01 the *rule*
  cannot empty a phase (some fraction is ≥ 1/64 > 0.01 under the 64-component axis cap), but closure
  pruning can; a derivation that empties a phase **falls back to the identity mask with a bounded note**
  rather than failing the solve.
- **T4 (identity).** τ = 0 derives the identity mask; the identity mask must produce the baseline problem
  object unchanged (same ledger instance, no new allocations on the hot path) so the disabled feature is
  bit-identical to today, provable across the whole existing suite.
- **T5 (τ cap).** τ ∈ [0, 0.01] (0–1 mol%), rejecting NaN/∞/negative/over-cap. The cap backs T3's rule
  half and bounds worst-case defect.

### 2.2 Structural threading (all consumers, by F3/F4/F9)

Per attempt: derive the mask from the deciding state → build the reduced ledger
(`V3DegreeOfFreedomLedger.create` gains a mask parameter: truncated points contribute no flow unknowns, no
material row, no VLE row; temperatures and energy rows are untouched) → wrap into a **masked
`V3ColumnProblem`** carrying the mask and reduced ledger (F4) → hand that problem to the evaluator,
coordinate map, layout, Jacobians, solver, and auditor exactly as today.

- Squareness is point-local: a retained point contributes as many unknowns (1 or 2 flows, by phases
  present) as equations (material, plus VLE iff two phases), so the reduced system stays square node by
  node; the structural-rank matching runs unchanged on the reduced sets. If the reduced ledger nonetheless
  fails `isValid()`, the attempt runs untruncated (availability over aggression — same posture as T3).
- `V3StageBlockLayout` is made mask-aware per F9 (expected sizes and per-component checks consult the
  mask). Its variable per-node block sizes are already the norm (condenser blocks differ today), so the
  banded solver and block-Jacobian carriers need no changes for *storage*; F11's caveat is about cost, not
  correctness.
- `V3DryMeshState` needs no change (F7); seeds are projected per F8 before encoding.
- `V3MeshResidualEvaluator` gets exactly the two F6 skips; everything else is automatic per F5.
- `V3FiniteDifferenceJacobian` / `V3BlockJacobianAssembler`: no intended behavior change — but the
  `assembleLocal` production shortcut and the stage coloring must be **re-verified on masked problems**
  against the existing full-difference `assemble()` oracle (off-band ≤ 1e-10 guard), because both reason
  about per-node slot layouts that now vary by mask. Any discrepancy fails toward the oracle path.

### 2.3 Sink edges and the audited defect

Where the untruncated topology would carry flow **into** a truncated point, that flow leaves the model: it
remains an outflow term of its retained source row (F5 makes this automatic), and the edge is recorded in
the mask: `LIQUID_TO_BELOW` (tray→tray, tray-N→reboiler), `VAPOR_TO_ABOVE` (tray→tray, tray-1→condenser,
reboiler→tray-N), `REFLUX_TO_TRAY_ONE` (the condenser's `R/(1+R)` share only — condenser vapor and the
distillate share are product exits, never sinks). A truncated point contributes zero inflow to every
neighbor.

- **Defect** ≙ Σ solved sink-edge flows. Identity to test: `authored feed − Σ published product streams ≡
  defect` for every accepted truncated solve.
- **Audit family `TRUNCATION_MASS_DEFECT`** (new in `V3AcceptanceAuditor`): recompute every sink-edge flow
  from the candidate state; gate `defect ≤ BUDGET × τ × totalFeed`. Budget proposal 8 (a small headroom
  multiple over the τ-scale flows each boundary edge can carry), tuned in §7 before any default ships.
  Exceeding it is a typed audit failure, never a silent publication. The auditor receives the mask as part
  of the candidate: it may fail it (defect gate, retained-set quantification of existing families) but
  never re-derives it. Existing audit families quantify over retained points; the condenser-outlet flash
  uses the retained composition.
- Known conservative case: a component confined below τ everywhere except its feed tray (retained only by
  T1) balances its feed entirely into sink edges; if that exceeds the budget, the audit fails and the
  fallback (§2.4) solves untruncated. Correct, one retry slower, expected rare at sensible τ.

### 2.4 Orchestration in `V3ColumnCalculator`

- **Per-attempt derivation**: inside the single-problem solve path, after the seed is final (post
  preconditioning/interpolation): derive mask → build masked problem → project seed → solve → audit.
  Continuation rungs and pressure legs each derive their own mask from their (high-quality, converged
  predecessor) deciding states — this is what "frozen per attempt" means operationally. The frozen support
  is a ratchet (a wrongly truncated point cannot return within the attempt); mitigations are deciding-state
  quality, the fallback below, and — only if evaluation data demands it — a phase-2 shrink-only
  post-convergence re-solve.
- **Untruncated fallback**: a truncated attempt chain that fails for any reason truncation could have
  caused retries once untruncated at the public boundary; admission verdicts truncation cannot change
  (`INVALID_INPUT`, `PROPERTY_OUT_OF_RANGE`) are terminal immediately. Both directions of need were
  measured (§1). Consequence: enabling truncation can never lose a solve the authored problem would
  publish.
- **API**: new public overload `calculate(input, control, double stageTraceCutoffMoleFraction)` validated
  `[0, 0.01]`; the existing entries stay exactly cutoff-free (API and test stability).
- **Evidence**: one bounded leading diagnostics event per truncated solve — τ, truncated/total points,
  closure-pruned count, solved defect as a fraction of feed — plus a second event when the fallback ran,
  and the T3/ledger-fallback note when derivation degraded to identity.
- **Provenance**: bump `FORMULATION_REVISION` (the equation system changed) and hash τ into
  `V3InputDigest` as a new named field (F12). The mask itself is deliberately **not** hashed: it is
  path-dependent (deciding states depend on the solve path), so it is published as diagnostics, while the
  digest identifies (authored input, τ, revisions).

### 2.5 Config and game wiring

New key `columnV3.stageTraceCutoffMolPercent`, range [0.0, 1.0] mol%, default per §7's decision rule;
comments must state the stage-level semantics, the audited defect, and 0 as the exact off switch. The name
must differ from any feed-level key so configs can never silently change meaning. Read on the server thread
at admission (`ProcessSolveServices.submitV3Column`), converted mol% → fraction, frozen into the immutable
worker command (config reloads cannot affect in-flight solves), revalidated in the command constructor.
Published streams need no GUI changes: truncated points surface as exact `0.0` fractions on the public
axis, a rendering path that already exists for structurally absent components; stream totals under-report
the authored feed by exactly the audited defect.

## 3. Testing matrix

- **Mask unit tests**: rule strictness at the τ boundary (exactly-τ retained); per-phase testability
  (single-phase nodes, zero phase totals); T1 under trace-level feed-tray fractions; T2 fixpoint cascades
  (including the R = 0 reflux rule and reboiler/condenser end rules); T3 degenerate fallback with bounded
  note; T4 identity object semantics; T5 rejection set; sink-edge taxonomy on hand-built states; the
  defect sum against hand-computed values; seed projection (truncated → exact zero, nonpositive retained →
  positive floor, temperatures and healthy values untouched).
- **Ledger/layout**: reduced ledgers stay square, full-rank, `isValid()` on masked fixtures; unknown and
  equation counts drop by exactly the truncated-point structure; layout accepts masked ledgers and rejects
  mask/ledger mismatches.
- **Residual semantics**: on a 3–4 stage fixture, transmitted-terms equality — the residual of every
  retained row under a masked problem with exact-zero states equals the hand-written transmitted form;
  `feed − Σ products ≡ defect`; composition renormalization over retained components; localTerms parity.
- **Jacobian oracle**: `assemble()` vs `assembleLocal()` on masked problems within the existing off-band
  guard; FINE/COARSE parity; coloring correctness with per-node slot variation.
- **Audit**: `TRUNCATION_MASS_DEFECT` pass and constructed-fail; auditor consumes the carried mask and
  never re-derives; retained-set quantification of existing families.
- **Golden regression (T4)**: with τ = 0 the entire existing suite (151 tests at baseline) is bit-identical
  — digests, streams, diagnostics.
- **End-to-end**: truncated 30-stage solves across both condenser branches; deterministic
  fallback-engagement tests (admission-invariant failures must not retry; a constructed truncation failure
  must retry once and disclose both events); event bounds (≤ 256 chars, ≤ 32 events, solvePath ≤ 128).

## 4. Phasing (each phase exits green before the next starts)

- **P0 — Structure.** Mask type; ledger mask parameter; layout mask-awareness (F9); masked-problem wrapper
  in the resolver; identity threading through the resolver. *Exit: full suite green and bit-identical;
  mask/ledger/layout unit tests pass; nothing derives a non-identity mask yet.*
- **P1 — Numerics.** The two F6 evaluator skips; seed projection (F8); sink-edge/defect computation;
  Jacobian oracle verification on masked fixtures. *Exit: a hand-masked small problem solves to
  convergence; §3 residual and Jacobian tests pass.*
- **P2 — Orchestration + audit.** Per-attempt derivation; fallback ladder; events; audit family; revision
  bump + τ in the digest. *Exit: end-to-end truncated solves publish with correct provenance; fallback and
  golden-regression suites pass.*
- **P3 — Config + wiring.** Config key, admission plumbing, in-game smoke run (GUI zero rendering, log
  events). *Exit: dev-client run clean with τ on and off.*
- **P4 — Evaluation (§7)** and the shipped default. **P5** — documentation updates.

Effort concentrates in P0–P2; the spike confirmed P0's touch set is small (F3/F4/F9 bound it) but P1's
Jacobian verification and P2's audit boundary carry the review load.

## 5. Risks and accepted trade-offs

- **Ratchet mis-truncation** (frozen support): mitigated by deciding-state quality, the fallback, and
  measured by drift/fallback columns in §7; shrink-only re-solve is the designated escalation, not built
  initially.
- **Cost may undershoot** (F11): rows shrink; bandwidth and perturbation-group count may not. The §7
  decision rule ships a weak result as opt-in rather than default-on.
- **Path-dependent masks**: two solves of one input may truncate slightly different sets, both passing
  audit; disclosed in diagnostics, deliberately outside the digest.
- **Auditor independence**: passing the mask narrows it; the P2 review must hold the line that the auditor
  can fail the mask but never derive one.
- **Tier-reproducibility noise** near total condensation (§1) will contaminate cell-level comparisons in §7;
  compare iteration counts (deterministic within a run) and treat branch flips as the known pre-existing
  issue.

## 6. Rollback and kill switches

τ = 0 (config) is an exact off switch at run time (T4). The formulation-revision bump plus τ-in-digest
means results from truncated and untruncated solves can never be confused in persistence. If the feature
must be pulled, reverting the calculator/audit orchestration while leaving the inert mask plumbing is safe
by T4's bit-identity guarantee.

## 7. Evaluation protocol (runs before any nonzero default ships)

Rebuild a sweep harness as a `benchmarks/` source-set `JavaExec` diagnostic (never CI timing assertions):

- **Grid**: τ ∈ {0, 1e-8, 1e-6, 1e-5, 1e-4} fraction (0–1e-2 mol%).
- **Scenarios**: stock TJL 30-stage (tails exist even with a clean feed — the case the design owner
  observed in-game); trace-contaminated variants (added ppm/ppb lights; a ppm-stripped downstream-style
  feed); and 3–4 operating points spanning condenser 323–420 K and varied duty from the existing
  operating-map fixtures, covering both condenser branches.
- **Metrics per cell**: status; retained/total points and closure-pruned count; iterations; wall seconds;
  solved defect; fallback engagement; drift vs. the same scenario at τ = 0 (worst relative stream
  molar/mass flow and temperature change, worst absolute mole-fraction change); failing audit family if
  any.
- **Method**: warmed JVM, no concurrent builds (a second Gradle daemon contaminates wall times — observed
  this session), iteration counts as the primary comparison.
- **Decision rule for the default**: if median speedup across operating-map scenarios at the
  accuracy-clean τ is **< 1.3×**, ship default **0** (opt-in; the feature still earns its keep as a
  convergence rescue and output-cleanliness tool). Otherwise default to the best τ that keeps ≥ 10× margin
  below visible drift (~1e-3 relative on bulk streams, given ~5 significant digits in the GUI). Working
  hypothesis: **1e-4 mol% (1e-6 fraction)** — deep enough to keep every genuine composition, high enough to
  kill e-20…e-50 tails — to be confirmed or replaced by the data.

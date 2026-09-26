# V3 stage side-draw plan

**Branch context:** `claude/v3-solver-stage-side-draws-a32e16` (worktree), source snapshot `54a4203`
**Status:** IMPLEMENTED on `codex/v3-stage-side-draws`; execution evidence and feasibility limits are in `V3_SIDE_DRAW_RESULTS.md`. This document preserves the original design and sequencing.
**Written:** 2026-08-31
**Companion reading:** `HYBRID_SOLVER_CODE_GUIDE.md` (same snapshot); `VDU_SIMULATION_RESEARCH.md` (recommends side-draws-on-CDU before the VDU work)

This plan adds liquid stage side draws to the V3 dry MESH solver with V1 feature parity: up to 3 draws, one per tray, each a **specified total molar rate**, liquid phase only. It follows the layer inventory in the code guide's "New user specification or side draw" row: input, specification, ledger, residual, coordinates/layout, initializer, audit, codecs, persistence, screen, tests.

---

## 1. Scope

**In scope (V1 parity target):**

| Property | V1 behavior (`ColumnSimulation`) | V3 target |
| --- | --- | --- |
| Draw phase | Liquid only (`MILESTONE_1.sideDrawPhase() = LIQUID`) | Liquid only |
| Specification | Total molar rate, mol/s (`SideDrawSpec(stage, molarFlowMolPerSecond)`) | Same |
| Count | ≤ 6 in V1 contract; GUI exposes 3 rows | ≤ 3 (`MAX_SIDE_DRAWS = 3`, matches existing V3 GUI stub rows) |
| Placement | One draw per tray, tray 1..N, feed tray allowed | Same (draws on condenser/reboiler are just distillate/bottoms — rejected) |
| Global feasibility | Σ draws < feed | Same, at input validation |

**Out of scope:** vapor side draws, side strippers, pumparounds, water/steam draws, draw-temperature or draw-purity specifications. These remain future contract extensions.

**A deliberate semantic difference from V1, stated up front:** V1 does not solve the energy balance; it *reshapes* internal traffic to make draws feasible (see `sideDrawDistillateFloor` in `CounterCurrentColumnSolver.columnTraffic`, which silently raises the distillate so upper draws never exceed reflux liquid). V3 solves the coupled MESH equations rigorously: if the user's three specifications plus draw rates admit no solution with positive internal flows, V3 must **fail honestly** with a diagnosable message, not bend the operating point. Players who copy a V1 setup may see V3 reject it. This is correct behavior and the plan treats its UX (failure detail naming the starving tray) as a first-class deliverable.

---

## 2. Chosen formulation

### 2.1 The equations

For a draw on tray `j` with specified rate `S_j` (mol/s), keep the existing state storage untouched and reuse the condenser-split precedent (`organicRefluxFraction() * liquidFlow(0, i)`):

- `l_{j,i} = state.liquidFlow(j, i)` continues to mean **total liquid leaving tray j** — now *including* the draw.
- Define `L_j = Σ_i l_{j,i}` and the withdrawal fraction `w_j = S_j / L_j`, evaluated from the current state at every residual evaluation.
- Liquid entering node `j+1` becomes `(1 − w_j) · l_{j,i}` (component-wise proportional split, so the draw has exactly the tray liquid composition — the standard rigorous treatment).
- The side product stream is `w_j · l_{j,i}` at tray temperature `T_j` and pressure `P_j`, extracted only at presentation time.

Residual changes are confined to the **rows of the node below each draw tray** (`j+1 ∈ [2, N+1]`; the reflux path into tray 1 can never be draw-affected because draws live on trays 1..N):

```text
Material (node j+1, component i):
    (1 − w_j)·l_{j,i} + v_{j+2,i} + feed_{j+1,i} − l_{j+1,i} − v_{j+1,i} = 0
Energy (node j+1):
    (1 − w_j)·E_L(j) + E_V(j+2) + feedH_{j+1} [+ Q_reb if reboiler] − E_L(j+1) − E_V(j+1) = 0
```

where `E_L(j) = L_j · h_L(T_j, x_j)` is the phase energy the evaluator already computes. **The draw tray's own material, energy, and equilibrium rows are unchanged**, and so are all residual scales.

### 2.2 Why this formulation

1. **Zero new unknowns, zero new equations.** A rate-specified draw is a parameter exactly like reboiler duty. Degree-of-freedom counts are untouched; `V3DegreeOfFreedomLedger` continues to balance without new families. V1's "each draw consumes one specification" bookkeeping is satisfied implicitly by the rate itself.
2. **Precedent-consistent.** The condenser already stores the total condensate and applies a split fraction where the stream feeds the next node (`V3MeshResidualEvaluator.materialResidual` node-1 branch, `V3BlockJacobianAssembler.assembleLocalThermodynamicColumn` `liquidInCoefficient`). Side draws use the identical pattern; the only difference is that `w_j` is state-dependent while the reflux fraction is a spec constant.
3. **Preserves the block-tridiagonal structure.** The new coupling (row `j+1` → all node-`j` liquid components through `L_j`) lives entirely in the existing lower block. `V3BandedPivotedSolver` bandwidth, `V3StageBlockLayout`, `V3DryMeshState`, `V3DryMeshCoordinateMap`, and the convergence-evidence gates are all unchanged. The finite-difference Jacobian's stage coloring (coupling distance ≤ 1 node) also survives unchanged.

### 2.3 Rejected alternatives (recorded so they are not re-litigated)

- **Post-draw storage** (store liquid-to-below; put `−S_j·l_{j,i}/L_j` in tray j's own row): equivalent nonlinearity, but breaks the "each tray's own balance closes on stored flows" invariant and diverges from the condenser precedent for no benefit.
- **Extra unknown per draw** (withdrawal fraction as unknown + one spec equation `w_j·L_j − S_j = 0`): keeps material derivatives sparse but introduces a new `UnknownFamily`, which ripples through every family switch — coordinate map encode/decode, FD step selection, `addLogFlowDerivative`, layout, evidence gates, state storage. Far larger blast radius than embedding one smooth nonlinear term.
- **Ratio specification instead of rate**: simplest numerically (constant coefficient, exactly like reflux) but not V1 parity; users specify kmol/h.

### 2.4 Feasibility semantics

At a solution, physicality requires `w_j < 1` (positive liquid continuing down). During iteration a transient `w_j ≥ 1` is harmless — the residual just grows and the Armijo line search backs off (log-flow coordinates keep every stored flow positive). But the closed equations do **not** by themselves exclude a converged state with `w_j > 1` (negative inter-tray stream compensated by vapor terms), so acceptance needs an explicit gate:

- **New audit check `SIDE_DRAW_SPLIT`** in `V3AcceptanceAuditor`: for every draw tray, recompute `w_j` from the candidate and require `w_j < 1` with finite `L_j` (practically: downflow `(1−w_j)·L_j` positive and finite). This is a physical-branch check in the same spirit as `CONDENSER_PHASE`.
- **Provable infeasibility at admission**: `Σ S_j ≥ total feed` → fail before solving (`INFEASIBLE_SPECIFICATION`). Per-tray feasibility is *not* provable a priori (internal liquid is an output), so it is diagnosed after the fact: when a draw-bearing solve fails with `NONCONVERGENCE` or the split audit fails, the failure detail names the tray with the largest `w_j` in the final iterate ("side draw on tray 8 requests 496 kmol/h but internal liquid reached only ~520 kmol/h").

---

## 3. Phase A — scientific core

Everything below `science/column/v3/`, solvable end-to-end through `V3ColumnCalculator.calculate` with tests, before any game integration. During Phase A the **local block Jacobian is gated off for draw-bearing problems** (one condition where the solver elects the local direction) so the whole-residual finite-difference Jacobian — which needs no changes at all — carries the solve. Phase C restores the fast path.

### A1. Input contract

- **New** `V3SideDrawSpec` record: `(int trayNumber, double molarFlowMolPerSecond)`. Constructor validates finite rate > 0 and tray ≥ 1. Trays are 1-based equilibrium trays = node numbers, same as `feedStageNumber`.
- **`V3ColumnInput`**: add `List<V3SideDrawSpec> sideDraws` with `MAX_SIDE_DRAWS = 3`. Canonicalize like `specifications`: defensive copy, sort by tray ascending, reject nulls, duplicates (same tray), and count > 3. Update `equals`/`hashCode`/`toString`. Delete the "side draws are intentionally absent" sentence from the class javadoc.
- **Schema policy (default: keep `SCHEMA_VERSION = 1`)**: an empty list is byte-for-byte and scientifically identical to today's contract, so this is an additive field following the stage-trace-cutoff precedent ("zero preserves the existing digest byte stream"). If review prefers an explicit bump to 2, the only extra work is a persisted-NBT migration clause; the plan works either way. **Decision point, default = keep 1.**
- **`V3InputDigest`**: after `feed-tray`, hash each draw (`side-draw-tray`, `side-draw-rate-bits`) **only when the list is non-empty**, exactly like the cutoff field, so every existing digest is preserved.

### A2. Problem resolution

- **`V3ColumnProblemResolver.validateInput`**: draw trays ≤ `stageCount` (1-based range against the *requested* geometry); `Σ S_j <` total authored feed. Violations throw `IllegalArgumentException` → existing `INVALID_INPUT` mapping; the Σ-check may instead surface as `INFEASIBLE_SPECIFICATION` (see A7).
- **`V3ColumnProblem`**: carry a compiled per-node rate array `double[] nodeSideDrawMolPerSecond` (zeros except draw trays) plus `boolean hasSideDraws()`, so the evaluator and assembler get O(1) access without re-walking the list.
- **`V3DegreeOfFreedomLedger`**: new parameter (draw-tray positions only — rates are irrelevant to structure; a `boolean[] sideDrawTray` or the compiled array). In `materialReferences`, when `node − 1` is a draw tray, reference **all retained** node-`(j)` liquid components, not just the same component (the `w_j = S_j/L_j` coupling). `energyReferences` already reference all neighbor components — unchanged. The public `create(topology, componentCount, specifications)` test overload defaults to no draws. Existing DOF counts must be provably unchanged (test).

### A3. Residual evaluator

`V3MeshResidualEvaluator`:

- Private helper `withdrawalFraction(state, j)` returning `S_j / L_j` (0 for non-draw trays); guard `L_j > 0` finite (it is, by the positive-flow invariant).
- `materialResidual`: for tray rows `2..N` and the reboiler row, multiply the `liquid in from above` term by `(1 − w_{node−1})` when `node − 1` is a draw tray.
- `energyResidual`: same `(1 − w_{node−1})` factor on the `liquidIn` phase-energy term for tray rows `2..N` and the reboiler row.
- Scales, `localTerms`, composition normalization, phase totals: unchanged.

### A4. Initializer and preconditioners

`V3ColumnInitializer`:

- `solveComponentMaterialBalance` (and the vapor-only-condenser variant): before the per-component TDMA, compute `w_est[j] = min(S_j / L_j_current, W_CAP)` from the incoming liquid grid totals (`W_CAP ≈ 0.95` keeps the cascade positive when the current iterate under-predicts liquid). Then `lower[tray] = (1 − w_est[tray−1])` for trays below draw trays and the reboiler row — the exact analog of the existing `refluxFraction` in `lower[1]`. Sweeps recompute `w_est` from each new iterate, converging the estimate Wang–Henke-style.
- The material-closed cold profile and traffic candidates: subtract cumulative draw rates from the initial liquid-total ladder below each draw stage (V1's `liquidFlows()` pattern) so the first TDMA sweep starts near-consistent. Deeper phase-aware traffic refinements only if seed-quality tests demand them.
- `projectMaterialBalancesAtFixedTemperature`, `V3BubblePointPreconditioner` (production-called for continuation projection/recovery): inherit the TDMA change automatically — verify by test, no separate edit expected.
- `V3SumRatesPreconditioner` and `V3HybridPreconditioner` (present but **unwired** per the code guide): return `NotApplicable("side draws")` for draw-bearing problems rather than silently computing with draw-blind energy corrections. Revisit only if they are ever wired.

### A5. Acceptance audit

`V3AcceptanceAuditor`: add the `SIDE_DRAW_SPLIT` check from §2.4, emitted only when draws are present (like `TRUNCATION_MASS_DEFECT`). The fresh full-residual recomputation picks up the new terms automatically because it reuses the evaluator.

### A6. Streams and result

`V3ColumnStreamProperties`:

- `MAX_STREAMS`: 3 → **6** (overhead vapor + distillate + ≤3 side draws + bottoms).
- `fromAccepted`: after the distillate, insert one liquid stream per draw tray in ascending-tray order: flows `w_j · l_{j,i}` (i.e., `flowScale = w_j` at node `j`), temperature `T_j`, pressure `P_j`, phase `LIQUID`. Stable ids `side_liquid_tray_<j>` (two-digit), display name `Side draw (tray <j>)`.
- `V3ColumnResult` / `V3ColumnOutcome`: no structural change (they carry the stream list).

### A7. Calculator orchestration

`V3ColumnCalculator`:

- **`withStageGeometry`** (line ~729): map each draw tray with the same proportional rule as the feed tray; clamp to `[1, stageCount']`; **merge collisions by summing rates** (two draws landing on one intermediate tray). Rates are kept absolute across rungs — internal traffic magnitude is set by feed/reflux/duty, which the rungs share. The condenser-branch probe (`preferredCondenserBranch` → `withStageGeometry(input, 4)`) and stage continuation inherit the mapping automatically. `withTopPressure` already carries the input fields through — add the draw list to its reconstruction (and to `withStageGeometry`'s).
- **Truncation policy**: draws + positive cutoff → force identity support for Phase A (use the existing `fallbackToIdentity(problem, reason)` note machinery so provenance records why). Lifted in Phase C.
- **Local-block gate**: skip the local block Jacobian direction when `problem.hasSideDraws()` (Phase A only; removed in Phase C).
- **Failure semantics**: `Σ draws ≥ feed` → `INFEASIBLE_SPECIFICATION`; malformed lists → `INVALID_INPUT`; on draw-bearing `NONCONVERGENCE`/audit failure, append the largest-`w_j` tray diagnostic (§2.4) to the failure detail.
- **Solve-path marker**: append `/draws-<n>` to `solvePath` strings for diagnosability.
- **Revision labels**: draws present → new formulation lane `v3-dry-mesh-r5-side-draws` (or `-r5-side-draws-flash-trace` when combined with a positive cutoff in Phase C); draws absent → labels unchanged, preserving all existing digests and provenance. Bump `ASSUMPTIONS_REVISION` to `v3-dry-assumptions-r4` once, since the assumptions text ("no side draws") changes. **Decision point, defaults as stated.**

### A8. Phase A tests

Mirror the existing test taxonomy:

| Layer | Tests |
| --- | --- |
| Contract | `V3SideDrawSpecTest` (validation); extend `V3ColumnInputTest` (canonical order, dup/tray/count/Σ rejection, equals/hash); digest golden test: no-draw digest byte-identical to current |
| Ledger | extend `V3DegreeOfFreedomLedgerTest`: counts unchanged with draws, full structural rank with widened references, draw-tray flag plumbing |
| Residual | extend `V3MeshResidualEvaluatorTest`: hand-computed material/energy rows around a draw tray on a tiny column with the test thermo model; draw-on-tray-N reboiler-row case; draw-on-feed-tray case |
| Jacobian | extend `V3FiniteDifferenceJacobianTest`: off-band guard still passes with draws (the `assemble` verification path needs no code change — this test proves it) |
| Independent oracle | extend `oracle/IndependentIdealMeshOracleTest` with a side-draw case — the strongest correctness evidence, because the oracle implements the equations independently |
| Solver | extend `V3SimultaneousColumnSolverTest`: small column + modest draw converges through the facade; near-capacity draw fails with the split audit or nonconvergence + tray diagnostic |
| Continuation | extend `V3DwsimStageContinuationTest`: 30-tray, 3-draw canonical case (GUI reference: draws 8/15/22 at 496/653/149 kmol/h); stage-mapping merge unit test |
| Audit | `SIDE_DRAW_SPLIT` pass/fail unit tests on constructed candidates |
| Sanity properties | side-draw temperatures increase with tray number; Σ(product streams) = feed within material tolerance; draw compositions intermediate between distillate and bottoms |

**Early feasibility spike (do this first, before polishing):** drive the canonical GUI reference case through the Phase A solver as soon as the residual/initializer changes compile. The three reference draws total ~50% of feed, and back-of-envelope traffic (`L_top ≈ R·D`) suggests `w` near 0.85 at the top draw — the case may be marginal or infeasible under the V3 default specs. Outcome decides whether (a) the GUI reference drafts get retuned to a comfortable operating point, and/or (b) the Phase C draw-ramp contingency gets promoted into Phase A.

---

## 4. Phase B — game integration

Independent of Phase C; do after Phase A.

- **`ColumnV3Network`**: `writeInput`/`readInput` append the draw list (count ≤ `V3ColumnInput.MAX_SIDE_DRAWS`, `varint stage + double rate`, bounded reads via the existing `readCount` helper). Extend `validateResolvedInput` with the same draw checks the resolver applies (server never trusts the client). Display-result codec already loops `V3ColumnStreamProperties.MAX_STREAMS` — the constant bump flows through; confirm the read-side cap picks up 6.
- **`ColumnCalculatorV3BlockEntity`**: persist a `SideDraws` `ListTag` (`Stage` int, `Rate` double) inside the input tag; absent tag → empty list (old saves load unchanged). Bump `DATA_VERSION` with a no-op migration clause. Display-result NBT is stream-list-shaped already.
- **`ColumnCalculatorV3Screen`**: enable the three existing stub rows (`SideDrawFields`, currently `setEditable(false)`); row semantics: **blank or zero rate ⇒ row disabled** (excluded from the input) so no-draw columns remain first-class — this intentionally differs from V1's always-3-draws GUI. Convert kmol/h → mol/s with the existing constant; client-side pre-validation mirrors the server (integer stage in range, distinct, positive rate, Σ < feed) with the existing draft-validation message pattern. Replace the "Reference side draws are display-only" notice. Streams page: verify layout at 6 streams (it iterates the list; check row spacing/paging at `MAX_PANEL_HEIGHT`).
- **`ProcessSolveServices` / `V3ColumnRequest` / `V3ColumnCommand`**: pass-through — the draws live inside `V3ColumnInput`. Verify with the existing runtime request/command tests only.
- **Phase B tests**: wire codec round-trip (0..3 draws), NBT round-trip + legacy-tag load, screen draft-validation unit tests where the harness allows, `V3ColumnDisplayResultTest` at 6 streams.

---

## 5. Phase C — performance and depth

1. **Exact local-block Jacobian terms** (`V3BlockJacobianAssembler`), then remove the Phase A local-block gate:
   - `assembleExactMaterialRows`, rows below a draw tray: same-component coefficient becomes `(1 − w_j)`, plus the dense analytic addition over retained node-`j` liquid components `∂/∂log l_{j,k} [(1−w_j) l_{j,i}] = (1−w_j)·l_{j,i}·δ_{ik} + w_j·l_{j,i}·l_{j,k}/L_j` (all into the existing lower block, scaled by the row scale).
   - `assembleLocalThermodynamicColumn`, energy row `j+1`: `liquidInCoefficient = (1 − w_j)` (analog of the condenser's reflux fraction), plus the exact correction `(S_j/L_j²)·l_{j,k}·E_L(j)` for node-`j` flow coordinates (zero for the temperature coordinate), with `E_L(j)` from the already-computed base terms.
   - Verification: the untouched colored-FD `assemble` path is the oracle — extend the existing FD-vs-local consistency test with draw cases.
2. **Truncation interplay** (lifts the Phase A identity gate):
   - Mask derivation force-retains draw trays (join the `node == feedTrayNumber` condition in `V3TruncationSupport`).
   - `LIQUID_TO_BELOW` sink edges originating at a draw tray scale by `(1 − w_j(state))` in both `massDefectMolPerSecond` and the auditor's fresh defect recomputation (the drawn fraction leaves as a real product, not a defect).
   - Widened ledger references must skip truncation-removed components (the existing `addIfActive` handles this — test it).
3. **Draw-rate ramp contingency** (only if the feasibility spike or canonical case demands it): when a draw-bearing rung fails, retry that rung at scaled rates (e.g. 0.5×, then 1×) as one extra continuation axis at the requested geometry, under the same audit gates. Not built speculatively.
4. **Benchmarks:** extend `v3TimeoutBenchmark` (or a small `v3SideDrawProbe`) with the canonical 3-draw case; verify the 45 s admission-to-deadline budget holds at 30 trays with pressure continuation, both FD-only (Phase A) and local-block (Phase C) paths.
5. **Documentation:** update `HYBRID_SOLVER_CODE_GUIDE.md` (snapshot header; Level 1 table; Level 4 input contract paragraph that currently says "no side draws"; Level 5 formulation labels; Level 6 change table) and the memory index.

---

## 6. What deliberately does not change

Worth stating because reviewers will look for it:

- `V3DryMeshState`, `V3DryMeshCoordinateMap`, `V3StageBlockLayout`, `V3BandedPivotedSolver`, `V3NormalEquations`, `V3ConvergenceEvidence` gates — no new unknown families, no bandwidth change, no new evidence classes.
- `V3FiniteDifferenceJacobian` — coupling distance stays ≤ 1 node; stage coloring and off-band guards hold as-is.
- Pressure continuation triggers/step ladder, condenser-branch selection, `V3CondenserPhaseTransition`, deadline/cancellation machinery, thermodynamic package and property bounds.
- The three user specifications (`V3ColumnSpecification` stays a sealed three-type interface); draw rates are a separate list, not a fourth control.

---

## 7. Risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| Canonical GUI reference case (draws ≈ 50% of feed) marginal/infeasible under default specs | High — it's the visible demo case | Feasibility spike first (§A8); retune GUI drafts and/or promote the draw ramp; honest `INFEASIBLE` diagnostics naming the starving tray |
| Near-dry trays below large draws stress Newton (tiny log-flows, stiff rows) | Medium | Log coordinates keep positivity; Armijo + existing fallback directions; ramp contingency; document the envelope rather than over-promising |
| Initializer seed quality with large draws | Medium | Per-sweep `w_est` recomputation with cap; draw-aware initial liquid ladder; seed-quality assertions in continuation tests |
| Intermediate continuation rungs infeasible after draw merging on tiny grids | Medium | Canonical mapping has no collisions (8/15/22 → 4/8/15-tray grids); merge test; ramp contingency if observed |
| `MAX_STREAMS` bump ripples (wire, NBT, screen layout) | Low | Single constant + bounded-read caps; Phase B round-trip tests; screen layout check at 6 streams |
| Digest/provenance churn | Low | Empty-list byte-stream preservation (cutoff precedent); conditional formulation label; one assumptions-revision bump |
| Truncation + draws interaction bugs | Low in A (gated off), Medium in C | Phase A identity gate with provenance note; Phase C force-retained draw trays + scaled sink edges with dedicated tests |

---

## 8. Open decisions (defaults chosen, flag disagreement before Phase A)

1. **Schema:** keep `SCHEMA_VERSION = 1` with additive optional list (default) vs bump to 2 + NBT migration.
2. **Labels:** `v3-dry-mesh-r5-side-draws` lane + `v3-dry-assumptions-r4` (default) vs other naming.
3. **Max draws:** 3 (default, matches GUI) vs V1's contract 6.
4. **GUI disable convention:** blank/zero rate row = disabled (default) vs an explicit per-row toggle.
5. **Phase C ordering:** exact-block performance work before truncation interplay (default; truncation matters more once the VDU detour resumes).

## 9. Suggested commit sequence

1. `A1–A2` contracts + resolver + ledger (+ tests) — solver still rejects nothing new at runtime because draws only enter via the new list.
2. `A3–A5` residual + initializer + audit (+ evaluator/oracle/solver tests) with the local-block and truncation gates.
3. `A6–A7` streams + calculator mapping + failure semantics (+ continuation tests, feasibility spike results).
4. Phase B integration (codec, NBT, screen) in one commit.
5. Phase C items as independent commits (exact blocks; truncation; benchmarks; guide update).

Each commit leaves `.\gradlew.bat test` green; draw-bearing behavior is exercised through the public facade (`V3ColumnCalculatorTest` pattern) from commit 3 onward.

# V3 55 kPa wall — action plan (mask refresh route)

**STATUS UPDATE (2026-08-31, later): P0 was executed on `codex/v3-55kpa-mask-refresh`
(probe-only, uncommitted in the `CreateChemE-stage-trace-truncation` worktree) and came back
NEGATIVE — `H1_NOT_QUALIFIED`. See §10 for the reviewed results, what they do and do not
establish, and the revised next step (P0b). P1/P2 remain gated off. Sections 1–9 are retained
as originally written; where §10 contradicts them, §10 governs.**

**PLANNING ONLY beyond the executed P0 probe. No production implementation exists.**
Date: 2026-08-31. Base commit: `54a4203` (`codex/hybrid-solver`, post stage-trace-truncation review).
Implementation venue when approved: a worktree **outside `run/` and `.claude/`** branched from `54a4203`
(the existing `D:\Minecraft\Modding\1.21\CreateChemE-stage-trace-truncation` worktree qualifies).
This plan is self-contained: every fact below was verified against source or committed benchmark
evidence at `54a4203` during the 2026-08-31 review. Companion review:
https://claude.ai/code/artifact/2733451f-425d-4bbd-896a-e6157308d523

---

## 1. Objective, scope, non-goals

**Objective.** Produce a fully audited V3 solution at 55 kPa and then 50 kPa top pressure for the
qualified operating point (30 stages, feed stage 24, TJL crude, 2610.7 kmol/h, 638.15 K feed,
323.15 K condenser, R=2, 8 MW, 750 Pa/stage), via the **positive-cutoff (truncated) lane**, without
weakening any acceptance gate.

**Scope.** The R1–R4 ladder from the review: a probe-first counterfactual (R1), productization as a
bounded mask-refresh (R2) plus a truncated-lane iteration allowance (R3), and reserve investigations
(R4) that run only if R1 falsifies the hypothesis.

**Non-goals / hard constraints (all phases):**

- **τ=0 is untouchable.** The untruncated lane's behavior, budgets, digests, and streams must remain
  bit-identical (the existing `zeroCutoffPreservesLegacyDigestStreamsAuditAndDiagnosticsExactly` test
  is the guard). A cutoff-off 50 kPa request will still fail after this work; that is accepted.
- No audit relaxation: `TRUNCATION_MASS_DEFECT ≤ 8·τ` (fraction-of-feed), `EQUILIBRIUM ≤ 1e-8`,
  condenser split `≤ 1e-8`, final-Newton gates, `SCALED_RESIDUAL_TOLERANCE = 1e-8` — all unchanged.
- No mask mutation inside a frozen Newton/Jacobian attempt. Every attempt keeps one immutable mask
  (the confirmed "frozen per solve attempt" axis). Refresh happens **between** attempts only.
- No property-value edits, no name-based component bans (no "drop PC12"), no service-deadline
  increase, no reapplication of the archived stagnation-recovery patch
  (`benchmarks/archives/v3-recovery-experiment.patch` is evidence, not a dependency).
- Cancellation/deadline ownership, admission-failure terminality, and the outer untruncated fallback
  (`V3TruncationFallback`) are preserved exactly.
- The Q1 rollout-gate question from the review is a separate decision, not part of this plan.

---

## 2. Established facts (evidence base)

### 2.1 The failure, as committed

| Fact | Value | Source |
| --- | --- | --- |
| Continuation health through 60 kPa | 4–6 iterations/rung, residuals ~5e-14 | `benchmarks/results/v3-flash-cold-final/50-off.json` events |
| 55 kPa rung, production budgets | predictor 0.161 @ 12 it; projected recovery 0.133 @ 24 it | same |
| 55 kPa, extended counterfactuals | 128 it, fresh FINE/COARSE Jacobians, exact material rows, no reuse → all stall ~0.13–0.16 | `benchmarks/results/v3-low-pressure/replay-counterfactuals.json`, `linear-directions.json` |
| Raw Newton directions near the wall | up to 75,860.87 K / 14,310.68 log-flow units; first accepted step 2⁻¹⁹ | `linear-directions.json` |
| Barrier location | accepted through **56.861328125 kPa**; 1 Pa steps do not cross | `adaptive-steps.json` |
| Profile motion at the wall | tray 18: 491.80 K @ 57 kPa → 510.12 K @ 56.875 kPa | `adaptive-steps.json` |
| Terminal dominant equation (full support) | **PC12 VLE @ node 18**, liquid 2.9439e-41, vapor 5.6881e-49 mol/s | `50-off.json` dominant-VLE-state event |
| Truncated system at 55 kPa (mask frozen from 60 kPa seed, 130/480 points removed) | **converges to 1.599e-14 in 104 iterations** | `benchmarks/results/v3-low-pressure/truncated-chain.json` |
| …but its defect | sink-edge defect/feed = **0.016185 (1.62%)** vs budget 8e-6 → correctly rejected | same |
| Reverted experiment, regularization on full support | residual 0.133 → **0.00242**, still 10⁶× over tolerance; dominant residual moves to **PC06 material @ node 8** | `benchmarks/results/v3-flash-cold-recovery-final/55-off.json` |
| Reverted experiment, expansion-only reactivation | 28 points restored, defect/feed 3.20e-6 (**passes budget**) — yet still no converged+audited truncated result | `55-on.json` stage-trace event |
| Failure tax of the current ladder | 50-off 9.99 s / 50-on 15.50 s (both fail at the 55 rung) | `v3-flash-cold-final` matrix |
| Not root-loss, not band-loss, not LU failure | 3 well-separated EOS roots at node 18 throughout; band conversion loses zero entries; LU backward errors ~1e-17 | `V3_LOW_PRESSURE_DIAGNOSIS.md` §Newton/line-search |

### 2.2 The shipped machinery this plan composes from (verified in source at `54a4203`)

- `V3TruncationSupport.derive(problem, τ, decidingState)` — freezes a mask from any deciding state:
  retains feed-tray points; retains a point if its liquid **or** vapor stage mole fraction ≥ τ;
  prunes to inflow-closure fixpoint (`pruneUnsupported`); reflux inflow edge inert at R=0;
  phase-emptiness → identity fallback with a bounded note. Package-private.
- `V3TruncationSupport.projectSeed` — floors retained-point seed flows at
  `max(Double.MIN_VALUE, flowScale(component)·1e-10)`; never floors solved flows.
- `V3ColumnProblemResolver.withTruncation(problem, support)` — rebuilds ledger/problem for a mask;
  invalid reduced ledger throws for the caller to fall back.
- `V3ColumnCalculator.prepareAttempt(original, seed, policy)` — per-attempt derivation inside
  `solveSingleProblem`; `originalProblem(pass)` re-resolves without carried support.
- Iteration constants: `MAXIMUM_NEWTON_ITERATIONS = 128`,
  `PRESSURE_CONTINUATION_CORRECTOR_MAXIMUM_ITERATIONS = 12`,
  `PRESSURE_CONTINUATION_RECOVERY_MAXIMUM_ITERATIONS = 24`,
  `CONDENSER_PHASE_CORRECTOR_MAXIMUM_ITERATIONS = 24`.
- `V3AcceptanceAuditor.truncationMassDefect` — recomputes the defect fresh from the candidate's sink
  edges; `V3TruncationFallback` — one untruncated retry at the outermost chain boundary.
- Benchmark probes live in package `com.wormzjl.createcheme.science.column.v3` (see `build.gradle`
  mainClass entries), so they have package-private access; `V3LowPressureProbe` already replays the
  truncated 55 kPa chain via `-Pv3TruncatedProbe=true` and probes refuse to overwrite reports.
- The reverted experiment used "the existing component-material TDMA calculation at the candidate
  temperatures" to seed restored profiles (`V3_RECOVERY_IMPLEMENTATION_RESULTS.md` §Stage-support
  reactivation) — i.e., a per-component linear material solve exists in the initializer machinery.
  Locating its exact entry point is implementation step P0-1, not an open design question.

### 2.3 Mechanism reading (what the plan bets on)

Below ≈56.86 kPa the profile reorganizes sharply and a set of component-stage points collapses to
physically-zero flows (1e-41 mol/s scale). In strictly-positive log-flow coordinates those points make
the **full-support** system degenerate: correct Newton directions for the collapsing coordinates are
enormous, and global damping that tolerates them destroys progress for the bulk profile. The
**reduced** system solves 55 kPa cleanly; what fails is **mask selection** — a mask frozen from the
60 kPa profile is wrong in both directions at 55 kPa (keeps newly-collapsed points, drops newly-grown
ones). Expansion-only reactivation failed because it restores the collapsed points it must not.

---

## 3. Hypothesis and falsifiable predictions

**H1.** A mask consistent with the actual 55 kPa profile — derived from the converged reduced 55 kPa
solution, allowing **removals and additions** — yields a truncated attempt that (a) converges within
≤128 iterations and (b) passes the unchanged 8·τ defect audit and every other gate.

Predictions that make H1 testable:

- P-a: the refreshed mask removes the PC12/PC11-tail region around nodes ~14–20 that the 60 kPa mask
  retained (the collapse set), and restores middle-cut points on the sink edges that carried the 1.62%.
- P-b: the refreshed-mask attempt's defect lands ≤ 8e-6 at τ=1e-6 (the expansion-only experiment
  already reached 3.2e-6, so the budget is attainable; H1 adds convergence).
- P-c: with an audited 55 kPa state as seed, the 50 kPa rung behaves like a normal rung (no wall
  between 55 and 50), or at worst yields to one more refresh.

H1 is **falsified** if the refreshed-mask attempt still stalls (residual > 1e-8 at 128 it) or
oscillates between masks without meeting the defect budget. Then P3 (reserves) activates.

---

## 4. P0 — R1 probe: remask counterfactual (no production changes)

Everything in P0 is benchmark-harness code only. Production sources must be hash-unchanged across the
phase (the probes already record before/after source SHA-256 maps — keep that).

**P0-1. Locate and wire the completion primitive.** Identify the existing per-component material
TDMA entry point the initializer uses (the one the reverted experiment reused). Deliverable: a probe
helper `completedState(reducedTerminalState)` that builds a **completion state C**: retained points
copy the reduced terminal values; truncated component-stage profiles are filled by the per-component
material solve at the terminal temperatures. No production edit; reflection/package access as the
probes already do.

**P0-2. New probe mode** `-Pv3RemaskProbe=true` on `V3LowPressureProbe` (new report directory
`benchmarks/results/v3-mask-refresh/`):

1. Reproduce the truncated 55 kPa chain exactly as `-Pv3TruncatedProbe=true` does; extend the
   terminal prepared attempt to 128 iterations → the known converged reduced state
   (1.6e-14 @ 104 it, defect 1.62%). Assert bit-agreement with `truncated-chain.json` first.
2. **Variant A (primary — composite completion):** build C per P0-1; derive a fresh mask
   `V3TruncationSupport.derive(originalProblem, τ, C)`; build the attempt via `withTruncation`;
   seed = `projectSeed(C)`; solve with a 128-iteration budget; run the full
   `V3AcceptanceAuditor` audit. Record: mask diff vs the old mask (added / removed / kept point
   lists per component), iterations, residual, every audit check, defect value.
3. **Variant B (fallback heuristic — only if A fails):** derive from the reduced terminal state
   directly (removal-only by construction), then re-add the top defect-contributing sink-edge target
   points plus closure, seeded from C. This is the two-sided cousin of the experiment's
   reactivation; record the same evidence.
4. **Refresh iteration:** if the first refreshed attempt converges but again fails only the defect
   audit, repeat the derive-from-its-own-completion step up to **2** more times; stop on mask
   fixpoint (identical mask ⇒ no progress possible on this route).
5. **50 kPa leg:** from an audited 55 kPa state (if achieved), run the 55→50 rung with the same
   remask-on-defect-failure policy and the production predictor/recovery structure.

**P0-3. Evidence census (cheap, same runs):** per pressure rung along the healthy path and at the
wall, record the count of points whose flows sit below `flowScale·1e-30` and the minimum log-flow
coordinate. This quantifies the collapse set and documents P-a directly.

**Exit gate / decision matrix for P0:**

| Outcome | Meaning | Next |
| --- | --- | --- |
| A (or B) converges + full audit passes at 55 **and** 50 | H1 confirmed | Proceed to P1 |
| Converges + defect passes at 55, but 50 still walls | Partial — wall moves | P1 for 55; P3 census on the 55→50 segment |
| Defect passes but never converges (collapse set returns via closure) | H1 falsified in its strong form | P3, starting with the σ_min census |
| Mask oscillates between refreshes | Fixpoint failure | P3; document the two attractor masks |

P0 deliverable: `benchmarks/V3_MASK_REFRESH_PROBE_RESULTS.md` + JSON reports, in the style of the
existing findings docs (typed scientific status in JSON; Gradle success is not a result).

---

## 5. P1 — R3 + R2 productization (only after P0 passes its gate)

Ordering note: **R3 is a precondition of R2.** Under production budgets (24), the truncated 55 kPa
attempt never reaches the converged-but-defect-failing state that triggers a refresh (it needed 104).

**P1-1 (R3) — truncated-lane iteration allowance.** For attempts whose `policy.attemptCutoff() > 0`,
raise the continuation **recovery** budget from 24 toward `MAXIMUM_NEWTON_ITERATIONS` (128), guarded
by monotone progress: continue past 24 only while the best residual improved over the trailing 16
iterations (exact window is an implementation constant; pick once, test, do not tune per case).
Predictor budget (12) and every τ=0 budget stay untouched. The caller-owned deadline still bounds
total cost.

**P1-2 (R2) — bounded defect-gated mask refresh.** Placement: inside `solveSingleProblem`
(where `prepareAttempt` already lives), so every truncated attempt gets it uniformly:

- Trigger: attempt is `Converged`, final-Newton gates pass, and **TRUNCATION_MASS_DEFECT is the only
  failing audit check**. (A simultaneous CONDENSER_PHASE or EQUILIBRIUM failure ⇒ no refresh; the
  existing paths own those.)
- Action: build completion C from the terminal state (P0-1 primitive, promoted into production with
  tests); `V3TruncationSupport.derive(original, τ, C)`; if the new mask equals the old ⇒ stop
  (fixpoint); else `withTruncation` + `projectSeed(C)` + re-solve with the same budget policy +
  fresh full audit.
- Bounds: at most **2 refreshes per `solveSingleProblem` call**; each refresh must strictly reduce
  the measured defect or the loop stops. Every stop reason falls through to today's behavior
  (rung failure → chain failure → outer untruncated retry). Nothing new is published without the
  complete audit passing.
- Frozen-axis compliance: each refreshed attempt is a **new** attempt with its own immutable mask;
  no support object is ever mutated.
- Events/provenance: extend the `stage-trace` event with `refreshes=N; defect-path=…`; masks remain
  provenance-only (not hashed); the deciding state for a refreshed mask is a solver state, which the
  existing "mask is path-dependent, provenance-only" stance already covers.
- **Formulation revision:** R2+R3 change positive-cutoff behavior ⇒ bump the positive-cutoff
  revision once (e.g. `v3-dry-mesh-r5-mask-refresh`); `v3-dry-mesh-r2` for τ=0 is untouched, and the
  τ=0 digest byte stream must remain identical.

**P1-3 — tests** (mirroring the existing suite's style):

- Unit: completion-state builder (material closure of C, temperature passthrough, absent-phase
  zeros); refresh trigger predicate (defect-only); fixpoint and strict-defect-decrease termination;
  bound of 2; event formatting.
- Integration: the 400 K hot-condenser case (`hotCondenserPhasePathRetriesUntruncatedWhenTheFrozenMaskExceedsItsMassBudget`)
  will likely change expectation — if refresh rescues it truncated, the test asserts the refreshed
  success and its defect; if not, it keeps asserting the untruncated fallback. Either way the
  full-feed closure assertion stays.
- Regression: τ=0 bit-identity test unchanged and passing; existing 289-test suite green;
  `V3StageTraceCalculatorTest` extended with a refresh-path case if a deterministic small fixture
  can reach the trigger.

**Exit gate P1:** 55 **and** 50 kPa cold cells succeed with cutoff 1e-6 under the production 45 s
deadline, all audits strict; τ=0 cells byte-identical to the `v3-flash-cold-final` baseline streams;
suite green.

---

## 6. P2 — qualification

- Full cold matrix rerun, fresh JVM per cell, new label, both cutoff settings:
  150 / 110 / 100 / 70 / **55** / **50** kPa (add the 55 cases to the active runner — the reverted
  `-Include55Kpa` switch shows how; re-add it to the *current* script rather than reapplying the
  archive patch).
- Off-path cells must reproduce the existing baseline product streams exactly; on-path digests
  change with the r5 revision (expected, documented).
- Measure the failure tax explicitly: a deliberately-failing case (e.g. 45 kPa if it walls) with
  cutoff on, to bound worst-case added work from R3's allowance + R2's refreshes under the deadline.
- Findings doc in `benchmarks/`, same evidentiary standard (source-hash manifests, single-sample
  caveats, no timing claims from instrumented runs).

---

## 7. P3 — R4 reserves (run only if P0 falsifies H1, or for the leftover segment)

1. **Conditioning census (first — cheapest attribution).** Extend `V3LowPressureLinearProbe`: at the
   saved states along the adaptive path (57 → 56.861 kPa) and at the wall, estimate σ_min /
   condition of the full-support Jacobian (e.g. inverse-power iterations using the existing banded
   LU) and pair it with the sub-representable-flow census from P0-3. Distinguishes "fold in
   pressure" (σ_min → 0 along the branch) from "trace-coordinate degeneracy" (σ_min collapse
   confined to collapse-set coordinates).
2. **Alternate continuation parameter (probe-only first).** From an accepted nearby anchor, hold
   pressure at 55 kPa and continue in reboiler duty (e.g. 6 MW → 8 MW) or condenser temperature;
   alternatively pseudo-arclength in pressure. Success criterion: an audited full-support 55 kPa
   state, which would also hand R2 a perfect deciding state. Production integration is a separate
   decision after the probe.
3. **Thermo-sensitivity attribution (qualification, regardless of outcome).** Perturb middle-cut
   (PC05–PC08) volatility parameters (Tc, Pc, ω, and the zero-BIP assumption) vs caloric parameters
   (Cp) by small factors in probe-only overrides; observe the barrier pressure's movement. This is
   **attribution only** — no shipped property value changes without the independent second-reader
   verification (W5) completing first. Rationale: the regularized stall was dominated by PC06
   material at node 8, not PC12, so mid-cut data quality is on the critical path.

---

## 8. Risks and kill switches

| Risk | Mitigation / kill switch |
| --- | --- |
| Mask oscillation between refreshes | Fixpoint check + strict-defect-decrease requirement + hard cap 2; on stop, today's fallback ladder is unchanged. |
| Completion TDMA unavailable/singular for some component | Fall back to floor seeding for that profile; if the attempt then fails, the ordinary untruncated retry still owns recovery. |
| R3 allowance inflates failure tax | Monotone-progress gating; deadline unchanged; P2 measures the tax explicitly and the allowance constant can be lowered without redesign. |
| Refresh rescues states with borderline defects "by construction" | It cannot: the budget stays 8·τ, the auditor recomputes from the candidate, and refreshes must strictly reduce the defect — the audit remains the sole arbiter. |
| τ=0 contamination | Every new branch is behind `policy.attemptCutoff() > 0`; the bit-identity test plus off-path byte-identical matrix cells gate the phase exits. |
| Scope creep back toward the reverted experiment | No stagnation heuristics, no regularization changes, no expansion-only monotonicity; anything needing those goes back to planning. |

Rollback: R2/R3 sit behind the positive-cutoff lane and one revision constant; reverting the commits
restores r4 behavior with no data or audit migration.

---

## 9. Deliverables checklist

- P0: probe mode + `benchmarks/V3_MASK_REFRESH_PROBE_RESULTS.md` + `results/v3-mask-refresh/` JSONs,
  production sources hash-unchanged.
- P1: production refresh + allowance behind positive cutoff, r5 revision bump, tests, suite green.
- P2: cold-matrix findings doc incl. 55/50, off-path byte-identity evidence, failure-tax bound.
- P3 (conditional): census/continuation/sensitivity findings docs.
- Memory + review artifact updated when phases land.

---

## 10. P0 executed — reviewed results and revised next step (2026-08-31, later)

P0 was implemented and run on `codex/v3-55kpa-mask-refresh` (probe-only; production sources verified
hash-unchanged inside the report itself; the committed 55 kPa baseline reproduced **bit-for-bit**,
including the 24-iteration production terminal and the 104-iteration extension; 289-test suite green,
uncached). The P3 conditioning census also ran. Evidence:
`benchmarks/V3_MASK_REFRESH_PROBE_RESULTS.md`, `benchmarks/V3_MASK_REFRESH_CONDITIONING_RESULTS.md`,
`benchmarks/results/v3-mask-refresh/{remask-p0,conditioning-p3-qualified}.json`. Review verdict on the
probe work: faithful to the fixed protocol, correctly gated, honestly reported.

### 10.1 Results

| Attempt (55 kPa) | Removed / 480 | Iterations | Max scaled residual | Defect/feed | Outcome |
| --- | ---: | ---: | ---: | ---: | --- |
| Extended baseline (60 kPa-seeded mask) | 130 | 104 | 1.599e-14 | 1.619e-2 | Converged+certified; defect alone fails |
| A: composite-completion remask | 115 | 5 | **3.70059e-4** | 1.838e-6 | Defect passes; `LINE_SEARCH_EXHAUSTED` |
| B: removal + ranked sink targets | 128 | 6 | 1.207e-10 | 1.605e-2 | Converged; defect barely moves |
| B refresh 2 (its own completion) | 114 | 5 | **3.70007e-4** | 1.158e-6 | Defect passes; `LINE_SEARCH_EXHAUSTED` |

Scientific status `H1_NOT_QUALIFIED`; the 50 kPa leg was correctly not run (no audited 55 anchor).

### 10.2 What the evidence establishes (review analysis)

1. **Prediction P-a was wrong.** The baseline mask already omits PC11/PC12 around node 18; every
   derived mask is addition-only in practice (a deciding state carries exact zeros at removed points,
   so re-derivation can only add what the completion predicts above cutoff). "Removal was the missing
   piece" is dead as stated.
2. **The stall is not the splice shock.** Variant A's trace: seed residual 0.51416 (exactly the
   composite's material inconsistency, dominant PC07@node 10 at 27.93 mol/s) → 0.182 → 0.0268 →
   1.02e-3 → 3.7e-4 in four iterations, then flat. The seed inconsistency is fully absorbed; the
   plateau is reached from well inside the basin.
3. **The plateau is a family property, not a mask accident.** A (115 removed) and B-refresh-2
   (114 removed, different added set) stall at residuals agreeing to ~1e-4 relative
   (3.70059e-4 vs 3.70007e-4), with near-identical audit values: ENERGY_BALANCE ≈ 3.70e-4
   (the dominant row family), material ≈ 9.39e-5, EQUILIBRIUM ≈ 2.59e-5, condenser split ≈ 3.06e-6.
   Every defect-passing mask tested re-imports enough near-cutoff tail stiffness to recreate a
   mini-wall — 350× shallower than the untruncated 0.133, but with the same
   no-acceptable-step-in-any-direction termination (Newton, damped GN ladder, gradient).
4. **The flow census falsifies the strong collapse story.** Accepted full-support states at
   110–60 kPa already carry 57–62 coordinates below `flowScale·1e-30` (log coordinates to −215),
   and the count does **not** increase at the failing 55 kPa rung. Ultra-trace degeneracy is a
   standing condition the solver tolerates at stationary points; the 55 kPa failure is about
   *traversing* the profile reorganization, not about new collapse appearing.
5. **The defect budget saturates just before the wall (new, independent finding).** Per-rung audited
   defects of the truncated lane grow monotonically as pressure falls: 1.42e-6 (140 kPa) → 2.17e-6
   (110) → 4.34e-6 (70) → 6.87e-6 (65) → **1.94e-5 at the 60 kPa predictor (over the 8e-6 budget;
   its re-derived recovery mask passes at 2.96e-6)**. At τ=1e-6 the seed-frozen per-rung mask policy
   is at the budget edge below ~65 kPa regardless of the numerical wall. Any qualified sub-65 kPa
   operation needs richer/refreshed masks — while §10.1 shows richer masks are exactly what stall.
   The two constraints squeeze from opposite sides as pressure falls.
6. **The conditioning census is honest but non-discriminating as specified.** The smallest-response
   direction is a perennial PC12-condenser trace pair (action norms ~1e-16–1e-17, present at every
   sampled pressure, non-monotone toward the wall); spectral extrema are unresolved at working
   precision (`CENSUS_COMPLETE_UNRESOLVED_SPECTRUM`). §7.1's raw σ_min tracking is a weak
   discriminator; only a trace-separated/bulk variant could say more.

### 10.3 Revised next step — P0b (probe-only; four cells, cheapest first)

The A-family plateau leaves one confound: *is the defect-passing reduced system unsolvable, or only
unreachable from the tested seeds/paths?* Four probe cells resolve it; none touches production code.

- **Cell 0 — production recovery ladder on the stalled state.** From the A plateau, apply the
  existing Wang–Henke bubble-point material/VLE projection (the production rung's own recovery
  primitive) and re-solve the same frozen A-mask problem, 128 iterations. The plateau's dominant
  row family is ENERGY; the projection is precisely a temperature/material re-coordination move.
  Cheapest cell, and it mirrors exactly what a production refresh would have available.
- **Cell 1 — material-closed projection seed.** Solve the A-mask problem seeded from the already
  computed full TDMA projection state (material closure 6.3e-15) instead of the composite. Nearly
  free (both states are already in the report pipeline).
- **Cell 2 — floor-seeded additions.** Seed = `projectSeed(A-problem, extended-baseline terminal)`:
  kept points keep converged values, added points start at the floor. Tests basin sensitivity from
  the opposite direction (additions enter with ~zero mass instead of TDMA mass).
- **Cell 3 — mask-consistent continuation (decisive).** Freeze the A-mask; solve it at 60 kPa
  (seeded from the accepted 60 kPa truncated state via `projectSeed`); if audited there, walk
  60 → 55 with the production predictor/recovery structure **holding the mask fixed** (no per-rung
  re-derivation). This asks whether the A-system has a connected branch into 55 kPa — entering
  along a branch instead of jumping onto it. If Cell 3 converges + audits at 55, run the 50 leg.

Decision rule: any cell producing a converged, fully audited 55 kPa state ⇒ H1 survives in modified
form; revise P1 so the production refresh adopts the working seed/continuation rule, then proceed
P1→P2 as written. All four cells negative ⇒ close the remask family for 55 kPa; §7's reserve 2
(alternate continuation parameter at fixed 55 kPa — duty or condenser temperature, or a separately
justified pseudo-arclength) becomes the primary route, with §7's reserve 3 (middle-cut
volatility-vs-caloric sensitivity attribution) as the parallel qualification exercise; and document
the currently qualified envelope as ≥ 60 kPa (with the §10.2-5 budget-squeeze caveat at 60–65 kPa).

### 10.4 Standing notes

- §5's P1 trigger ("converged, defect-only") is necessary but now known to be insufficient on its
  own at 55 kPa — do not productize R2/R3 from §5 as written until a P0b cell passes.
- §5's "material closure of C" test line was ill-posed, as the probe report argues: exact retention
  plus independent per-component completion cannot generally compose into a closed state. If P1 ever
  proceeds, specify completion acceptance as (i) retained bits unchanged, (ii) independent
  full-projection closure within tolerance, (iii) composite inconsistency reported as diagnostics —
  never asserted closed.
- Strategic context: the VDU workstream targets dry-tower operation far below 55 kPa. If P0b fails,
  the alternate-continuation investment is not optional polish; it is the only identified route to
  the vacuum-pressure envelope.

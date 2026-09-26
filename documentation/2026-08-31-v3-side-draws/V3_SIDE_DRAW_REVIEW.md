# V3 side-draw review: severity assessment and hardening proposals

**Reviewed target:** `codex/v3-stage-side-draws` at `dd43c98` (commits `bdd18a5` side draws, `dd43c98` truncation-path optimization), diffed against base `54a4203`
**Review date:** 2026-09-01 (max-effort pass, 8 findings reported)
**Status:** IMPLEMENTED AND VERIFIED in `321fd8a` on `codex/v3-stage-side-draws`
**Companion reading:** `V3_SIDE_DRAW_PLAN.md` (original design), `V3_SIDE_DRAW_RESULTS.md` (implementation evidence), `V3_TRUNCATION_OPTIMIZATION.md` (dd43c98 benchmarks)

Line references are into the `dd43c98` snapshot.

---

## 1. Verdict

**No finding can corrupt a published result.** The acceptance-audit gates are intact: the `(1−w)` residual factors, the exact block-Jacobian terms (verified analytically and against the colored-FD oracle), the TDMA coefficients, the `SIDE_DRAW_SPLIT` audit, the scaled truncation sink edges, and the empty-draw digest byte-compatibility all check out. A `V3ColumnOutcome.Success` remains trustworthy.

The risk class is **availability and diagnosis**: the solver can refuse requests that may be feasible, and can mislabel or mis-describe failures. Four of the eight findings (F1, F2, F3, F7) share one root cause, and that root cause sits directly on the feature's headline use case — large CDU-style draws — not on an edge case.

### What was verified correct (do not re-litigate)

- Residual `(1−w)` placement: tray rows 2..N and the reboiler row only; reflux path and each draw tray's own rows untouched.
- Exact material derivatives `(1−w)·l_i·δ_ik + w·l_i·l_k/L` and the energy split term `+ (w·l_k/L)·E_L`, confirmed against `V3BlockJacobianAssembler.assemble` to 1e-5 by test.
- Initializer TDMA `lower[tray] = 1 − w_est[tray−1]` with the 0.95 cap; material-closed seed closes to 1e-10 in test.
- DOF counts unchanged; widened ledger references preserve full structural rank.
- Digest: empty draw list is byte-identical to the pre-draw stream (golden test); draws hashed only when present.
- Identity-support compatibility (`requireCompatible` early-returns on identity), `CondenserAttempts.recordAttempt` idempotence, `V3CrudeFeed.moleFractions()` defensive copy — all candidate bugs here were refuted.

---

## 2. Severity under real use

| # | Finding (anchor) | Severity | Real-use rationale |
| --- | --- | --- | --- |
| F1 | Small-grid rung infeasibility aborts the request (`V3ColumnCalculator.java:672`) | **HIGH — breaks the target workload** | Real CDU operation draws most of the feed as side products; that is why the feature exists (see `VDU_SIMULATION_RESEARCH.md`). Shipped tests qualify only quarter rates (~12% of feed). Realistic kerosene/diesel/AGO rates die at the 4-tray rung, where three draws are compressed onto three of four trays; the requested 30-tray problem is **never attempted** (`attemptedRequestedProblem` stays false). The results doc concedes the rung failure "is not proof of full-geometry infeasibility". |
| F2 | Legal large draws can throw in the cold seed → `INVALID_INPUT`, no ramp (`V3ColumnCalculator.java:283`, `V3ColumnInitializer.java:100`) | **MEDIUM** | Players probing rates upward (drafts pass GUI validation, Σ < feed) can drive the material-closed profile's reboiler seed nonpositive; the initializer `IllegalArgumentException` unwinds to the generic catch and is labeled `INVALID_INPUT`. Confusing, mislabeled — but still an honest failure; no wrong physics. |
| F3 | Failure diagnostic names remapped/merged rung trays (`V3ColumnCalculator.java:698`) | **MEDIUM while F1 exists** | This is the message players see for the F1 failure: "side draw on tray 1 of 4" for draws authored at 8/15/22 (rates possibly merged). Sends users debugging a phantom draw. Drops to LOW once F1 is fixed, because draw diagnostics would then only originate at the requested geometry. |
| F7 | Pressure rungs have no draw-ramp recovery (`V3ColumnCalculator.java:335` — ramp wired into stage continuation only) | **MEDIUM, latent** | The 60–100 kPa sweep passed, but only at quarter rates. Lower pressure shifts traffic toward vapor, so marginal draws will fail at a pressure step first as players raise rates toward feasibility limits. No breakage observed yet. |
| F5 | Failed draw rung triggers up to 5 extra full solves + a fresh branch probe (`V3ColumnCalculator.java:649`) | **LOW-MEDIUM** | Bounded by the 45 s deadline, but `solver.workers` defaults to 1: one hopeless draw request monopolizes the worker for the whole deadline while other players queue. Pre-draw failure paths were already expensive; the ramp multiplies time-to-honest-failure ~6× per rung. |
| F4 | Full-rate ramp failure drops the prior pass's events/notes (`V3ColumnCalculator.java:670`) | LOW | Diagnostics loss only: `return pass` discards `failed.solverEvents()` and records no "ramp reached 1.0" event. |
| F6 | Withdrawal-fraction/tray-total logic implemented four ways (`V3AcceptanceAuditor.java:66`, plus problem/support/diagnostic) | LOW | Maintenance hazard; guard semantics already diverge (throw vs NaN vs report) though no current numerical disagreement. |
| F9 | Invalid-draft copy always blames draws (`ColumnCalculatorV3Screen.java:256`) | LOW | A bad feed temperature with zero draws configured still shows the draw checklist message. Cosmetic misdirection. |

---

## 3. Root cause of F1/F2/F3/F7

The implementation enforces **full draw rates at every continuation rung**. The gates only require full rates **where a result is published — the requested problem**. Intermediate rungs are seed providers; a reduced-rate (or draw-free) rung profile is a legitimate seed, and using one does not weaken any published claim as long as the final converge-and-audit happens at full rate on the requested geometry.

Because the current code conflates "seed" with "publishable", a 4-tray mapping artifact becomes a terminal request failure (F1), the diagnostic describes the artifact instead of the input (F3), the cold initializer meets full-rate draws it never needed to see (F2), and the one mechanism that relaxes rates exists only inside a single stage rung (F7).

---

## 4. Proposed fixes

### P0 — feasibility probe (do first, settles how hard to push P1)

Run the original 496/653/149 kmol/h case at the requested 30-tray geometry directly: seed from the quarter-rate converged solution and ramp 25% → 50% → 75% → 100% at fixed geometry, full audits each step. One benchmark run, no production code.

- If it converges: F1 is provably rejecting feasible requests → P1 is mandatory, and the GUI reference case can be restored.
- If it genuinely cannot: F1 drops to "misleading failure shape" and the diagnostic fixes (P1c, P3) carry more of the weight.

### P1 — seed-vs-publish separation on the stage ladder (fixes F1, most of F2, F3)

**P1a. Strip draws from intermediate stage rungs.** In `solveDwsimStageContinuation`, solve every grid below the requested stage count with an empty draw list (no more `withStageGeometry` draw mapping/merging for those rungs; keep the feed-stage mapping). A no-draw profile is an excellent seed — draws perturb traffic, not the composition ordering the ladder exists to build. At the requested geometry, attempt full rate directly (warm from the ladder); on failure, run the existing `recoverWithDrawRamp` — 0→25→50→75→100% warm-seeded at fixed geometry is exactly its design. Consequences:

- The merge-collision class disappears (nothing left to merge).
- Draw diagnostics can only name authored trays (F3 resolved structurally).
- The cold initializer never sees draws except for direct small-column requests (stage count ≤ 4), removing most F2 triggers.
- The condenser-branch probe (`preferredCondenserBranch` at 4 trays) also stops seeing draws — acceptable, since branch selection is ordering-only and independently audited.

**P1b. Contain initializer throws to the rung.** Wrap the per-rung `V3ColumnInitializer.initialize` call so an initializer failure becomes a failed rung (flowing into the existing recovery/ramp) rather than unwinding to `calculateBranch`'s catch-all. Introduce a typed exception (or a typed initializer result) so the terminal mapping can use the already-existing, currently-unused `INITIALIZATION_FAILURE` code — not message sniffing — with the tray diagnostic appended when draws are present. This covers the residual F2 path (small columns solved directly with draws).

**P1c. Diagnostic provenance.** With P1a, rung-level draw diagnostics vanish; keep a belt-and-braces wording change in `sideDrawDiagnostic` for any remaining non-requested-geometry caller: when `problem.topology().trayCount() != <requested>`, say "at the N-tray continuation grid" explicitly.

**Acceptance criteria for P1:** the original full-rate case either publishes a Success at 30 trays, or fails with `attemptedRequestedProblem == true` and a diagnostic naming an authored tray (8, 15, or 22). Existing quarter-rate CSV tests, the truncation-path tests, and the honest-failure test must stay green (the honest-failure test's asserted event text will need updating to the requested-geometry ramp path). No change to tolerances, budgets, audit gates, or published-result semantics.

### P2 — pressure-rung ramp, evidence-gated (F7)

Hold until a real failing case exists (the 60–100 kPa quarter-rate sweep passed 30/30). When justified: after the existing bubble-point recovery on a failed pressure step with draws, try fractions {0.5, 1.0} warm from the previous pressure state; full rate must be re-reached at that pressure before continuing downward (or, if P1's machinery generalizes cleanly, carry the reduced fraction and ramp at the requested pressure — same seed-vs-publish rule). Bound it to one ramp per pressure ladder to protect the deadline budget.

### P3 — small-fix batch (F4, F5, F6, F9)

| Finding | Fix |
| --- | --- |
| F4 | On full-rate ramp failure return `withPriorSupportNotes(failed, pass)` and append a "side-draw ramp reached 1.0 and failed" event, instead of bare `pass`. One line each; pure diagnostics gain. |
| F5 | Reuse the failed rung's condenser branch at fraction 0.0 instead of re-running `preferredCondenserBranch` (one line). Optionally shrink the ladder to {0, 0.5, 1.0} with refinement to 0.25/0.75 only when 0.5 fails. Largely moot after P1a (ramps then run only at the requested geometry, once). |
| F6 | One static helper (e.g., `V3SideDraws.withdrawalFraction(state, node, rate)`); `V3ColumnProblem`, `V3TruncationSupport`, the auditor, and the diagnostic delegate to it, keeping throw-vs-report behavior in thin wrappers. |
| F9 | Parse scalars and draws separately in `draftInput` (or capture the parse exception message) so the screen names the failing field instead of always showing the draw checklist. |

---

## 5. Sequencing

1. **P0** feasibility probe (one benchmark run; decides P1's urgency and whether the GUI reference drafts can be restored).
2. **P1a + P1b (+P1c)** in one PR with the acceptance test above.
3. **P3** batch (independent, low risk, can ride along or follow).
4. **P2** only when a failing pressure-rung draw case is demonstrated.
5. Documentation debt (from the review, not a finding): `HYBRID_SOLVER_CODE_GUIDE.md` still describes the input contract as draw-free and links the now-untracked `benchmarks/V3_RECOVERY_IMPLEMENTATION_RESULTS.md`; refresh per its own maintenance rule once P1 settles the final behavior.

---

## 6. Implemented resolution and convergence investigation

P0 found that the canonical quarter-rate case converges and audits at the requested 30-tray geometry, while the 50% canonical rate stalls after 128 iterations. This does not establish full-rate feasibility, but it confirms that an intermediate compressed-grid failure is not a valid substitute for attempting the authored geometry.

P1 is implemented as seed/publish separation. Intermediate stage grids and the condenser-ordering probe are draw-free; the accepted requested-grid no-draw state feeds a bounded 25/50/75/100% draw ramp. Full requested rates are always attempted. Initializer failures map through `INITIALIZATION_FAILURE`, and terminal diagnostics name authored trays. Intermediate ramp steps are exact even when a cutoff was requested; only the full-rate rung freezes reduced support. This prevents a low-rate truncated state from permanently hiding points needed by the final draw products. The qualified 100 kPa reduced path again removes 32/480 points and audits a `7.269315e-7` feed-relative defect without fallback.

The convergence investigation found two independent causes of long runs:

- Reprojecting every fixed-grid draw step moves a nearby accepted state outside Newton's local basin. At 150 kPa, a projected 25→50% handoff exhausted 128 iterations at residual `2.025e-5`; direct reuse of the accepted 25% state converged the same 50% problem in 36 iterations, followed by 75% in 9 and 100% in 23.
- Full-draw pressure jumps of 10 kPa caused repeated predictor/recovery work and stalled at 120 kPa. Retaining the qualified draw profile and using 5 kPa pressure steps made every 150→100 kPa rung converge in four iterations. The 100 kPa exact benchmark changed from a 36.2 s failure to a 10.8 s success.

P2 therefore uses evidence-qualified finer pressure continuation rather than trying to strip draws from an already draw-bearing pressure state; that counterfactual produced a worse no-draw residual (`0.0736`). P3 is implemented: ramp events survive, a terminal full-rate ramp failure suppresses redundant cold/alternate-branch replay, withdrawal arithmetic is centralized in `V3SideDraws`, and the GUI reports the invalid scalar or side-draw field separately. Suppressing the duplicate branch replay reduced the canonical large-rate honest failure from 43.2 s and 121 GB allocated to 22.7 s and 60 GB while preserving the full-rate attempt and authored-tray diagnostic.

Verification: 321 tests passed with zero failures, errors, or skips. The post-commit 60–100 kPa matrix ran one warmup plus three samples of all ten exact/cutoff cells; all 30 measured calls succeeded and no cutoff call used the cold untruncated fallback.

| Top pressure | Exact median | Cutoff median | Cutoff points | Maximum defect/feed |
| ---: | ---: | ---: | ---: | ---: |
| 100 kPa | 11.199 s | 9.081 s | 32/480 | 7.269315e-7 |
| 90 kPa | 11.270 s | 10.050 s | 33/480 | 2.122814e-6 |
| 80 kPa | 12.812 s | 10.587 s | 33/480 | 7.565589e-6 |
| 70 kPa | 13.972 s | 11.464 s | 31/480 | 2.215711e-6 |
| 60 kPa | 13.542 s | 11.746 s | 31/480 | 7.296706e-6 |

Compared with the pre-review matrix, exact 70 and 60 kPa medians improved by 61.4% and 64.9%. The finer pressure ladder adds work at 80–100 kPa, but removes the convergence cliff and keeps every measured call well below 45 seconds. Raw evidence is in `build/reports/benchmarks/v3-side-draw-review-final-60-100kpa.json`, its summary/comparison files, and `v3-side-draw-review-final-source-hashes.json`.

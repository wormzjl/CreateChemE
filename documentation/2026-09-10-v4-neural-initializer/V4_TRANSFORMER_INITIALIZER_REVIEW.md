# V4 Transformer initializer: precision and solver-time review

Date: 2026-09-13. Reviewed: `BRANCH_HANDOFF.md` on `codex/v4-transformer-investigation` (HEAD c2cab76, scientific snapshot 98d19cd), the linked campaign reports, the neural correction path in `V3ColumnCalculator.correctNeuralSeed`, the decoder in `V3FactorizedNeuralFeatures`, and a fresh read-only re-analysis of the sealed per-case journals (scripts under `%TEMP%\neural-analysis\`). No experiment was run; nothing in the Codex worktree was modified.

## 1. Verdict

1. **Solver time is already won where the initializer works.** On the 76 validation cases both paths solve, the neural seed is faster on every one: median 940 ms classical to 114 ms neural, geometric-mean ratio 0.126. Inference is under 1% of that. The saving is the skipped stage/steam continuation, not fewer Newton steps (2.6 vs 2.0 iterations).
2. **The handoff's latency regression is an artefact of the all-case mean.** On the 405-input validation set F0 FIRST and classical are 4019 vs 4010 ms all-case, and 91% of that total is 237 cases nothing solves. The 34 double-pay cases cost +97 ms on the mean.
3. **Coverage headroom for any initializer is 30 to 39 cases, not 237.** Ever-solved union across every archived arm is 198 (hash-verified) to 207. F0 FIRST already captures 81 to 85% of it. Of the 198 never-solved, 66 are typed `INFEASIBLE_SPECIFICATION`; the rest are NONCONVERGENCE concentrated at 2 to 3 side draws and 45+ stages, consistent with the liquid-shortage draw wall documented in `V3_WALL_AND_40MW_BALANCE_ANALYSIS.md`.
4. **Profile precision as measured is the wrong objective.** Neural failures are bimodal at exactly 0 or 16 Newton iterations; initial projected residual does not separate solvable from unsolvable; L4 lowered profile error and moved FIRST by +/-1. The corrector cares about basin membership and log-flow support, not RMSE.
5. **The neural seed has a clear regime signature that nobody is exploiting.** It doubles to triples classical success at 2+ pumparounds and loses at 0 pumparounds and 45+ stages. A two-feature routing rule is evaluable by replay from the existing journals with zero solver runs.

## 2. What the archive says (405 validation inputs, blocks identical)

| Quantity | Value | Source |
|---|---:|---|
| Classical strict | 110 | capacity `validation-case-evidence.jsonl`, `modes.current` |
| F0 ONLY strict | 134 | same |
| F0 FIRST strict | 168 = 110 + 58 gains | same; neural-owned FIRST set == ONLY set |
| Classical successes lost by ONLY | 34 | same |
| Typed infeasible in FIRST | 75 | `FIRST statuses` |
| Ever-solved union, hash-verified / with hybrids | 198 / 207 | 204 / 228 arms pooled |
| F0 FIRST share of union | 85% / 81% | |

Common-success paired timing, n = 76 (classical strict and ONLY strict in both blocks):

| ms | p25 | median | p75 | mean |
|---|---:|---:|---:|---:|
| classical | 491 | 940 | 1775 | 1529 |
| neural ONLY | 58 | 114 | 207 | 209 |
| ratio | 0.09 | 0.13 | 0.19 | 0.18 |

Geometric-mean ratio 0.126. 76/76 faster. CPU tracks wall. Inference: 0.7 to 0.9 ms median on the 252-set journals (`rawPrediction.ms`), 3.3% including anchor construction on the 405 set.

All-case mean decomposition, F0 FIRST block 1 (block 2 within 3%):

| Stratum | n | FIRST mean ms | classical mean ms | share of FIRST total |
|---|---:|---:|---:|---:|
| A common success | 76 | 217 | 1565 | 1.0% |
| B neural-only gain | 58 | 233 | 3180 | 0.8% |
| C double-pay | 34 | 3318 | 2161 | 6.8% |
| D nothing succeeds | 237 | 6362 | 5379 | 91.3% |

Failure taxonomy, F0 ONLY (271 non-strict): 137 at exactly 16 iterations (`iteration_limit`), 75 at the 2 s wall with 0 recorded Newton iterations (`neural_time_limit`), 35 unknown, 18 `no_admissible_step`, 6 advisory. 78% are budget walls, not divergence. ONLY never exceeds 2031 ms; FIRST failures have median 4.1 s and 8 hit the 30 s deadline.

Iterations on common successes: classical median 3 (max 5), neural median 2 (max 7). On the 34 classical-only cases the neural arm sits at 16 (the cap) while classical needs median 3 after its ramp. On the 58 neural-only gains, classical fails at mean 15 iterations, max 128.

Regime breakdown (ONLY vs classical strict):

| Heat loops | N | classical | ONLY | gain | loss |
|---:|---:|---:|---:|---:|---:|
| 0 | 81 | 44 (54%) | 33 (41%) | 3 | 14 |
| 1 | 87 | 30 (35%) | 28 (32%) | 6 | 8 |
| 2 | 78 | 15 (19%) | 27 (35%) | 18 | 6 |
| 3 | 76 | 12 (16%) | 25 (33%) | 16 | 3 |
| 4 | 83 | 9 (11%) | 21 (25%) | 15 | 3 |

| Stages | classical | ONLY | gain | loss |
|---:|---:|---:|---:|---:|
| 3 | 33% | 65% | 19 | 4 |
| 10 | 39% | 57% | 8 | 0 |
| 17 | 43% | 37% | 4 | 7 |
| 31 to 38 | 23 to 25% | 34 to 43% | 17 | 4 |
| 45 to 59 | 16 to 19% | 9 to 13% | 8 | 17 |

Steam-fed cases gain more than dry (+8.2 vs +3.6 points). The 65-case screening panel is twice as easy as the remaining 340 (classical 48% vs 23%), so checkpoint selection on it is biased toward easy cases.

Corrections to the handoff text:

- "Stable common-success costs are never reported": they exist in `unified-evaluation-summary.json` (-96 ms mean, n = 66) and `checkpoint-selection-summary.json` (seed 20260911: -253 ms mean, n = 110, no losses), but only as FIRST-minus-classical including double-pay. The neural-vs-classical ONLY comparison above was not in the archive.
- The "3.753 vs 3.437 s" regression is from the 252-input `g4fresh` campaign with model I under 1 worker. The 405 campaign with F0 under 10 workers is a wash.
- Case-ID schema `gd-s<stages>-w<steam>-p<heatLoops>-d<sideDraws>-r<rep>` is undocumented in `tools/neural/README.md`; recoverable from the `condition` block.
- `hybrid-diagnosis/v1/case-evidence-v2.jsonl` hashes disagree with the canonical hash on 405/405 ids; do not pool it by hash.

## 3. Why profile precision is the wrong lever

The corrector is a direct Newton at the requested column with no continuation, 16 iterations, inside a 2 s budget shared with inference and anchor construction (`V3InitializationOptions` defaults; `correctNeuralSeed` then `solveSingleProblem("lnn/requested-state")`). On failure LNN_FIRST runs one clean classical solve from the original input.

What decides success is therefore whether 16 Newton steps from the seed reach the certificate. Three mechanisms dominate, none of them RMSE:

1. **Support.** The decoder prunes any present component whose decoded flow falls below 1e-10 of its feed scale. F0 leaves 819 above-floor omissions across 168 reference profiles, about 5 per column; each costs a support-refresh sweep. The hybrid diagnosis shows most omissions occur when the presence head keeps the component but the continuous head undershoots.
2. **Log-flow plateau.** In log-flow coordinates a material row `r = I - a*e^z` gives exactly one e-fold per Newton step once `a*e^z` dominates (commit 1e388f5). A retained trace point seeded 10 e-folds high costs 10 of the 16 iterations. `capOversizedPoint` repairs this for continuation seeds and does run on neural seeds via `prepareAttempt`, but only lowers points more than 3x above their material row.
3. **Budget walls.** 75 ONLY failures spend the whole 2 s with 0 recorded iterations, and the neural arm is net-negative at 45+ stages where per-iteration cost is highest under the 10-worker load. Whether those 2 s go to preparation, the wet prepass, or a first iteration that never completes is not recoverable from the case-evidence file.

The training targets are already in centered log-composition space, so the loss is roughly e-fold-shaped. The gap is between the loss and the decoder, not the loss and the profile.

## 4. Recommendations, ranked by value per unit of work

**R1. Replay-evaluate a regime router before any new model.** Order classical-first for 0 pumparounds and for 45+ stages, neural-first otherwise. Every per-case outcome and time for both modes is already in the journals, so the expected FIRST count and time distribution can be computed offline in an afternoon. This cannot raise FIRST above 168 but removes most of the 34 double-pay cases (14 at p0, 17 at 45+ stages) and the 2 s neural spend on cases classical wins. The reward-pilot's learned selectors gained +1 to +3 net at 92 ms per query; a two-feature rule is free.

**R2. Reshape the neural budget from walls to progress rules.** First, a bounded diagnostic: record residual histories for the 137 iteration-capped and 75 time-capped ONLY failures (the 252 journals carry `residualEvaluations`, `linearSolves`, `finalStepNorm`; the 405 files do not). Then, if the capped trajectories are contracting, let the neural pass continue past 16 while the scaled residual halves within a fixed window and abort early when it does not, reusing the stall rule the steam rungs already have. Express the budget in iterations that fit at the column's size rather than wall ms. This is the only intervention that can both add coverage and cut failure latency, and it needs no retraining.

**R3. Decoder-side presence floor, tested on frozen F0.** When the presence head keeps a component but the continuous flow decodes below the floor, emit the floor times the reinsertion hysteresis instead of zero. Zero retraining, paired evaluation, directly targets the omission mechanism. Distinct from the failed T-arm, which was a training-time margin loss.

**R4. Change the validation metric used for checkpoint selection.** Replace mean profile RMSE with per-case e-folds of maximum log-flow error on retained points plus omission count, and select on a stratified panel weighted toward the loss regimes (p0, 45+ stages, 17 stages) instead of the current 65-case panel, which is twice as easy as the population.

**R5. Data toward the gaps, not capacity.** Stage counts 38 and 52 have zero labels; the never-solved mass is at 45+ stages and 2 to 3 draws. Two unused label sources: every intermediate rung of the classical stage continuation is a converged MESH state of a real smaller column and is currently discarded by design ("intermediate or parametric states are never exported"); auditing and exporting them multiplies labels several-fold at no solver cost. Second, warm-started homotopy sweeps from each certified solution, which is how nearest-profile transfer rescued 146 cases. Both must pass the strict audit and respect the TRAIN/validation boundary.

**R6. Extend typed-infeasible detection.** Not an initializer change, but the biggest latency lever in the table: 91% of all-case time is 237 never-solved cases, of which only 66 fail fast as typed infeasible. The liquid-shortage draw-wall analysis gives a mass-balance test that would type many of the remaining 107 NONCONVERGENCE cases before any solve.

Defer solver-in-the-loop training (Sambharya-style or reward-based). With 30 to 39 cases of headroom and failures that are budget walls rather than divergence, R1 to R4 should be exhausted first; differentiating through a Java Newton solver with discrete support changes is a large lift for a small ceiling.

## 5. Do not spend on

- More layers or width: measured dead end (L4 +/-1 FIRST, latency regressed).
- Loosening closure or acceptance: user's prior negative evidence; the robustness note already withdrew it.
- Learned seed selectors: +1 to +3 net on the sibling `codex/reward-initializer-training` branch at 92 ms inference; deferred there too.
- Chasing the all-case mean as a speed metric: it is a failure-cost statistic.
- Pooling the `g4fresh`, `g6fresh` and 405 populations, or the hybrid-diagnosis evidence file.

## 6. Sources

- `codex/v4-transformer-investigation:BRANCH_HANDOFF.md`, `tools/capacity-followup/results.md`, `tools/trace-followup/results.md`, `tools/hybrid-diagnosis/results.md`, `tools/capacity-followup/robustness-research.md`, `tools/neural/unified-evaluation-findings.md`
- `C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-capacity-followup/v1/validation-case-evidence.jsonl` (5,670 rows), `validation-analysis.json`, `build/neural-trace-followup/v1/validation-case-evidence.jsonl`, `tools/neural/unified-evaluation-summary.json`, `tools/neural/checkpoint-selection-summary.json`, `tools/neural/generation-comparison-case-map.jsonl`, `.neural-cache/unified-evaluation-v1/study.zip`
- `codex/reward-initializer-training:tools/neural/reward-pilot-results.md` (classical 110 at 4080 ms all-case on the same 405 set)
- `src/main/java/.../v3/V3ColumnCalculator.java` (`correctNeuralSeed`, `prepareAttempt`, `capOversizedPoint`), `V3FactorizedNeuralFeatures.java`, `V3InitializationOptions.java`, `V3SimultaneousColumnSolver.java`
- `research/convergence-time-optimization-2026-09-09.md` sections 3 and 8 (solver in the neural branch already contains db46e88)

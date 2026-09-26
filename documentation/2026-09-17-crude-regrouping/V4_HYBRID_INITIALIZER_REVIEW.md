# Combined mechanistic-neural initializer: an independent study

Date: 2026-09-18. Branch `codex/crude-regrouping` @ `4e84f0e`, worktree
`.claude/worktrees/crude-regrouping-plan-review-28f25a`. Requested by the user after the data-generation
review ("investigate a combined mechanistical-neuro initializer to potentially improve the seeding; there
were previous unsuccessful works on this aspect but you need to study this independently"). Evidence, scripts
and journals: `research/convergence-review/` (`neural-failure-mechanics/`, `eval/prep*`, `score_prep.js`,
`run_prep*.sh`, `seed-prep.patch`).

## 1. Verdict

A combined initializer that pays off exists, and it is not a second model: it is one mechanistic repair rule
applied to the decoded seed inside the solver's own preparation pass. **Lifting every retained trace point
that sits below the material its neighbours deliver, to the equilibrium split of that material** (the mirror
image of the existing `capOversizedPoint` rule, using the reinsertion's own split), takes the retrained
model `G-17023-160` on the fresh 312-request holdout from 117 to 142 neural-only strict solutions and from
147 to 155 neural-first, with 2 neural-only and 0 neural-first paired losses at a lift factor of 1.2, and
cuts the time of common successes by a third (416 to 284 ms) and the neural-first to classical time ratio
from 0.85 to 0.76. At factor 1.0 the count is 144 / 157 with 4 / 1 losses, identical on both blocks; with
the existing oversized cap tightened from 3x to 1.5x on top of it, **147 / 158 with 2 / 0 losses**, the best
arm of the study. The effect is deterministic (identical on the reversed block at factors 3 and 1.0) and
monotone in the factor (10: +2, 3: +10, 1.5: +17, 1.2: +25, 1.0: +27 neural-only).

Everything else tested is neutral or inapplicable: a Wang-Henke material-balance projection of the seed
(the idea behind the earlier "material completion") gains 2 where it applies and declines on 45 % of seeds;
replacing the seed's flows by the classical material-closed flows under the learned temperatures applies to
22 seeds and loses 2; gating the lift on the corrector's starting residual, by its maximum row or by its
merit, does not separate the few losses from the gains, because the losses are seeds whose *neighbours* are
wrong, not the lifted point itself.

The finding is a solver-side change, not a model change: it needs no retraining, applies to the packaged
model as it is, and is 30 lines in `liftFloorSupport`. It has been measured on one model and one consumed
holdout and must go through Codex's qualification (fresh holdout, both blocks, parity untouched, pins) before
promotion; the classical route is untouched by construction, and whether the same rule helps the classical
continuation seeds is a separate campaign.

## 2. What was tried before, and why this study is different

The `codex/v4-transformer-investigation` branch (2026-09-13 handoff) records four hybrid attempts, all
measured on the 20-component F0 basis:

| attempt | what it did | result |
| --- | --- | --- |
| residual-over-anchor networks (N, N+1) | the network predicts a correction to the classical `MATERIAL_CLOSED` seed | 159 / 155 FIRST of 405 against 161 for the plain network; on the fresh 252 test 91 / 89 against 92. The anchor's compositions equal the feed prior to 1e-14: it carried no separation information |
| material-completion wrapper | an exact material completion of the decoded seed, applied under an admission rule | +1 of 252 with no losses, but it prepared only 8 to 17 seeds per population (20 dry, no-draw TRAIN inputs in the feasibility study: 12 declined, 8 prepared; 2 gains, 0 losses, minus 504 ms) |
| full anchor as input feature (F0) | the classical seed enters the network as features, absolute outputs | the production model family; 168 of 405 FIRST |
| ramp handoff (`Recovery.RAMP_HANDOFF`) | a failed learned state seeds the classical feature ramp instead of a cold restart | +8 ONLY / +2 FIRST, 0 losses, +812 ms; opt-in by user decision |

Two things were never done: a partition of **why** seeds fail on a population with a working data design, and
a mechanistic repair that applies to every seed rather than to the few an admission rule lets through. This
study does both, on the regrouped 19-component basis with the corrected design of the data-generation review
(fresh full-cell holdout `holdout-v2`, 312 requests, classical 106) and the retrained model `G-17023-160`
(neural-only 118, neural-first 148 before this study).

## 3. Why the seeds fail

Read-only partition of the 194 non-strict neural-only outcomes of `G-17023-160` on the holdout, cross-checked
against four other models (`research/convergence-review/neural-failure-mechanics/summary.md`):

| mechanism | n | mean ms | classical solves it | strict answer demonstrated by any route of any model |
| --- | ---: | ---: | ---: | ---: |
| 2 s wall budget exhausted | 29 | 2,004 | 2 | 4 |
| no correction ran (support degenerated) | 3 | 1,002 | 0 | 0 |
| converged below 1e-6, not strict | 21 | 707 | 1 | 1 |
| every local-block direction rejected | 74 | 1,108 | 7 | 12 |
| component-material-balance crawl (final residual 0.5 to 1.5) | 36 | 838 | 12 | 20 |
| other component-material-balance dominant | 11 | 927 | 5 | 5 |
| vapour-liquid-equilibrium dominant | 13 | 556 | 1 | 4 |
| energy dominant | 7 | 1,191 | 2 | 2 |

Findings that shaped the experiments:

- **The realistic ceiling is 48 requests, not 194.** 146 of the failures are requests no route of any model
  ever solved strictly, including the classical route at a 30 s deadline; 91 of the 194 are liquid-depletion
  or heat-gated designs. Headroom on this holdout is 118 to about 166 neural-only.
- **The raw seed residual carries no information; the journal's "initial" residual is partly circular.**
  The raw seed residual (`rawPrediction.nativeResidual`) is about 6 for successes and failures alike (AUC
  0.53). The `scaled residual: initial=` event separates them with AUC 0.90 (78 % strict when at or below
  0.3, 3 % above), but this study found that the event belongs to the **last** attempt of the learned pass:
  `solveSingleProblem` creates a fresh telemetry per attempt, so after a floor-support refresh the "initial"
  residual is the residual of a state that has already been through one Newton attempt. On the baseline
  run 116 of the 117 strict successes carry at least one refresh; the single one without starts at 6.1. It
  is therefore a read-out of how the correction went, not a property of the prepared seed. Measured directly on the
  prepared seed (section 5, `liftSelect`), the true starting maximum is 5 to 17 for successes and failures
  alike; the maximum row is one the seed repairs never touch. What the solver's preparation
  (`prepareAttempt`: floor support, reinsertion, oversized cap) does decide is *which rows* start near 1.
- **The component-balance failures are trace-species defects.** 94 of 194 failures are dominated by a
  component material balance; in 63 the physical residual is below 1e-3 mol/s (median 8.5e-6) while the
  scaled residual sits near 1, and 22 end within 1 % of exactly 1.0, the signature of a balance whose flow
  term is missing: a retained point seeded far below what its neighbours deliver. At least 82 of the 94 are
  `crude_pc*` pseudo-cuts. These make progress and then park under 1 (median final/initial 0.28): a crawl at
  one e-fold per iteration, the mirror image of the oversized-point crawl `capOversizedPoint` already repairs.
- **The equilibrium failures are phase collapse**, not balance defects: the residual barely moves (median
  ratio 0.93), 22 of 50 have both phase flows of the offending component below 1e-3 mol/s. A fixed-temperature
  balance cannot fix them; only 6 of 50 have a demonstrated strict answer.
- **21 "failures" are not failures.** They converged with every audit check passing and were downgraded to
  `DRY_SUPERSATURATED` after the free-water continuation declined; 14 of 19 are supersaturated on the
  classical route too. This is the open three-phase decision and it hides 6 % of the holdout.
- **The 2 s budget is a per-iteration cost at large N**, not preparation: inference is 11 ms; the median
  wall time per Newton iteration rises from 25 ms (2 to 9 stages) to 209 ms (50 to 64 stages), so 2 s buys
  about 80 iterations on a small column and 9 on a tall one; 28 % of 50-to-64-stage requests time out.

## 4. Harness

`V3GeneralTrainingProbe compare` on `holdout-v2` (312 requests, 10 workers, 30 s deadline, 2 s learned
allowance, 16 base iterations with the `PROGRESS` rule), model `G-17023-160`, one experiment switch
(`-Dcreatecheme.experiment.seedPrep=<mode>`) read in `V3ColumnCalculator.correctNeuralSeed`; the classical
lane of the same run is untouched (thread-local gating). Each arm is one block of 312 x 3 lanes, about 8.5
minutes. The baseline arm reproduced the earlier run of the same model to within wall jitter (117 / 147
against 118 / 148, one loss). Two Opus analysis agents ran concurrently during the first batch, which raised
the classical mean time by 16 % and the number of 2 s budget walls (51 against 32); timing comparisons are
therefore made within a batch and on common successes.

Modes:

| mode | what happens to the decoded seed before the correction |
| --- | --- |
| `none` | nothing (production) |
| `project1` | one undamped Wang-Henke material-balance projection at the seed's temperatures, K-values and phase totals (`V3ColumnInitializer.solveMaterialBalances...`) |
| `project3` | three half-damped sweeps, temperatures fixed |
| `project3T` | the production recovery projection `projectMaterialBalancesAtFixedTemperature` (three sweeps with bubble-point temperature updates) |
| `project1c`, `project3c` | as `project1` / `project3`, but a trace flow that underflowed across the trays is floored at 1e-300 instead of declining the projection |
| `anchorFlows` | classical `MATERIAL_CLOSED` flows under the learned temperatures and free water, then `project1` |
| `liftUnder` | in `liftFloorSupport`, a fully retained point whose flows sum to less than 1/3 of the material its neighbours deliver is lifted to the equilibrium split of that inflow (the mirror of `capOversizedPoint`, using the reinsertion's own split); factor swept 1.5 / 3 / 10 |
| `liftSelect` | `liftUnder`, kept only if it lowers the corrector's starting residual (two extra residual evaluations inside the learned allowance) |

## 5. Results

All arms: `holdout-v2`, 312 requests, model `G-17023-160`, classical lane 106 strict in every run. "Applied"
= seeds the preparation changed; paired gains and losses are strict neural-only outcomes against the
baseline of the same block; "common ms" = mean neural-only time on requests both arms solved strictly.

| arm | block | applied / declined | neural-only | neural-first | paired neural-only gains / losses | paired neural-first gains / losses | neural-first / classical time | common ms (baseline -> arm) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `none` (baseline) | forward | - | 117 | 147 | - | - | 0.848 | 429 |
| `none` (repeat, same load) | forward | - | 117 | 147 | 0 / 0 | 0 / 0 | 0.852 | 428 |
| `none` | reversed | - | 117 | 147 | - | - | 0.859 | 411 |
| `project1` | forward | 76 / 185 | 116 | 148 | 2 / 3 | 1 / 0 | 0.850 | 421 -> 400 |
| `project3` | forward | 118 / 140 | 119 | 148 | 6 / 4 | 3 / 2 | 0.845 | 433 -> 420 |
| `project3T` (production recovery projection) | forward | 122 / 143 | 119 | 148 | 6 / 4 | 3 / 2 | 0.841 | 431 -> 417 |
| `project1c` (underflow floored) | forward | 76 / 189 | 116 | 148 | 2 / 3 | 1 / 0 | 0.853 | 421 -> 384 |
| `project3c` (underflow floored) | forward | 120 / 141 | 119 | 148 | 6 / 4 | 3 / 2 | 0.846 | 433 -> 413 |
| `anchorFlows` | forward | 22 / 244 | 115 | 146 | 1 / 3 | 1 / 2 | 0.866 | 433 -> 424 |
| `liftUnder`, factor 10 | forward | 258 / 0 | 119 | 148 | 10 / 8 | 3 / 2 | 0.830 | 393 -> 386 |
| `liftUnder`, factor 3 | forward | 259 / 0 | 127 | 147 | 19 / 9 | 5 / 5 | 0.805 | 415 -> 368 |
| `liftUnder`, factor 3 | reversed | 260 / 0 | 127 | 147 | 19 / 9 | 5 / 5 | 0.809 | 411 -> 376 |
| `liftUnder`, factor 1.5 | forward | 265 / 0 | 134 | 151 | 24 / 7 | 6 / 2 | 0.775 | 411 -> 305 |
| `liftUnder`, factor 1.0 | forward | 263 / 0 | **144** | **157** | 31 / 4 | 11 / 1 | **0.750** | 406 -> 297 |
| `liftSelect`, factor 3, gate on the maximum row | forward | 59 lifted / 202 raw | 120 | 148 | 5 / 2 | 1 / 0 | 0.853 | 419 -> 407 |
| `liftSelect`, factor 3, gate on the maximum row | reversed | - | 120 | 148 | 5 / 2 | 1 / 0 | 0.852 | - |
| `liftUnder`, factor 1.2 | forward | 263 / 0 | 142 | 155 | 27 / 2 | 8 / 0 | 0.760 | 416 -> 284 |
| `liftUnder`, factor 1.0 | reversed | 263 / 0 | 144 | 157 | 31 / 4 | 11 / 1 | 0.766 | 400 -> 278 |
| `liftUnder`, factor 1.0, oversized cap tightened 3 -> 1.5 | forward | 263 / 0 | **147** | **158** | 32 / 2 | 11 / 0 | 0.767 | 430 -> 282 |
| `liftUnder`, factor 1.0, oversized cap tightened 3 -> 1.0 | forward | 263 / 0 | 138 | 153 | 29 / 8 | 9 / 3 | 0.785 | 413 -> 255 |

Against the ungated factor-1.0 arm, the 1.5 cap gains 6 and loses 3 neural-only (2 / 1 neural-first) and
removes two of its four losses; the 1.0 cap loses 7 and gains 1, because a cap at exactly the inflow and a
lift at exactly the inflow chase each other across the sweeps (the reason the production cap sits at 3).
| `liftSelect`, factor 1.5, gate on the residual merit | forward | 256 lifted / 6 raw | 133 | 152 | 24 / 8 | 6 / 1 | 0.776 | 404 -> 294 |
| `liftSelect`, factor 1.5, merit gate | reversed | 263 lifted / 7 raw | 134 | 152 | 24 / 7 | 6 / 1 | 0.785 | 405 -> 282 |
| `liftSelect`, factor 1.0, merit gate | forward | 260 lifted / 7 raw | 144 | 157 | 31 / 4 | 11 / 1 | 0.754 | 406 -> 276 |

The two baselines of the forward block are identical request for request, and the reversed block reproduces
both baseline and factor-3 lift exactly: the harness is deterministic under this load, so paired differences
of one or two cases are real, not jitter.

## 6. Reading

**Why the projection does not help.** The tridiagonal component-balance solve is exact for the material
rows at the seed's K-values and phase totals, but it is declined on 139 to 142 seeds because a heavy cut's
liquid flow underflows to zero across a tall rectifying section (the solve is an M-matrix system whose
solution is positive in exact arithmetic and 1e-300 in floating point), and flooring those entries only
moves the decline to the log-coordinate feasibility check (the vapour of a floored point underflows next).
Where it applies, it raises the true starting maximum residual (the energy and equilibrium rows now see
flows that moved by orders of magnitude) and the corrector gains 2 and loses 4 to 6. The earlier
material-completion wrapper prepared 8 to 17 seeds per population for the same reason. A global re-solve of
the material rows is the wrong granularity: the defect is local.

**Why the lift works.** `liftFloorSupport` already sweeps every point in flow direction and applies two
rules: a removed point whose neighbours now deliver at least a floor is reinserted at the equilibrium split
of that inflow, and a retained point more than three times above its inflow is capped to it. It had no rule
for a retained point far *below* its inflow. That is exactly the state the decoder leaves the pseudo-cuts in:
the continuous head undershoots a trace flow by many e-folds while the presence head keeps it, and in
log-flow coordinates a material row `r = I - a e^z` with `a e^z << I` then costs one e-fold per Newton step
under the step cap, at a scaled residual of exactly 1 throughout (the "22 within 1 % of 1.0" of section 3).
Lifting the point to `I / (1 + K V/L)` closes its material row and its equilibrium row at the seed, so the
corrector starts from the few rows that are genuinely wrong. Of the 31 requests factor 1.0 gains, 18 were
component-balance crawls in the baseline, 5 were budget walls (the crawl no longer eats the 2 s), 4 had every
direction rejected, 2 were equilibrium and 2 energy dominated.

**Why the factor matters and where the losses come from.** The sweep is monotone because a point one e-fold
low still costs an iteration; factor 1.0 lifts every retained point up to its material row. The losses are
the cases where the inflow itself is wrong: a neighbour seeded too high delivers phantom material and the
lift propagates it (a 12-stage wet column with 264 lift events at factor 1.0; two tall wet columns whose raw
seed converged in 0 iterations and whose lifted seed starts at 0.8 to 1.4 on a component or equilibrium
row). The cap rule catches oversized points only above three times their inflow, so the phantom is below
its threshold; the pending tightened-cap arms test whether the symmetric rule removes these. The gates do
not help because the true starting maximum row (5 to 17 for successes and failures alike) and the merit both
move the same way for gains and losses; the seeds that will be lost are not distinguishable at the seed.

**The lift is inactive on solved states by construction**, as the cap is: at a converged state `l + v = I`
exactly, so a factor of 1.0 fires only on floating-point noise and moves nothing measurable; a production
rule should use `total < (1 - epsilon) x inflow` to make that explicit.

## 7. What a combined mechanistic-neural initializer should be

1. **The mechanistic half lives in the solver's seed preparation, not in the model.** The network predicts
   the profile; `prepareAttempt` decides support, reinsertion, caps and now lifts. The earlier attempts put
   the mechanistic part before or inside the network (anchor features, residual-over-anchor targets, a
   completion wrapper with an admission rule) and gained nothing; the rule that gains is the one that runs
   on every seed, locally, after decoding. Recommendation: add the undersized lift to `liftFloorSupport`
   for the learned route at factor 1.0 with the oversized cap at 1.5 (147 / 158, losses 2 / 0), or factor
   1.2 with the cap unchanged (142 / 155, losses 2 / 0) if the cap is to stay shared with the classical
   route, and qualify it through Codex's pipeline on a fresh holdout in both blocks. It changes no weights,
   no sidecar and no classical path.
2. **Measure the same rule on the classical continuation seeds separately.** The stage-continuation and
   feature ramps hand interpolated states to the same `prepareAttempt`; the cap rule was added for them.
   Whether the lift helps or hurts them is not known and must not be assumed from the learned route.
3. **Budget in iterations scaled by stage count, not a flat 2 s.** 29 seeds died on the wall with a median
   of 10 iterations done on tall columns; with the lift, 5 of them solve because the crawl is gone, and the
   rest need the iterations the wall denies. This is `V3InitializationOptions` policy, not solver code.
4. **Count `DRY_SUPERSATURATED` seeds as what they are.** 19 of 21 "converged, not strict" seeds were
   accepted with every audit check passing; 14 are supersaturated on the classical route as well. Report
   them as the open three-phase decision, not as initializer failures.
5. **The equilibrium-collapse family is a data and decoder problem**, not a repair problem: 50 failures
   where one phase of a heavy or light component vanished, only 6 solvable anywhere. The training-guide
   rules (global composition, zero-phase floor, first-model gates) are the right place for it.
6. **Do not spend on** a second projection variant, residual-over-anchor learning, or a residual gate on
   the seed; all three were measured here or before and do not move the count.

## 8. Caveats

- One model, one holdout, and `holdout-v2` is already consumed as a selection set by the data-generation
  review; a promotion decision needs a fresh full-cell holdout and Codex's parity, fixture and pin pipeline.
- All arms are single blocks except where a reversed block is listed; paired gains and losses of 2 to 4 are
  within the wall-jitter band (the two baselines differ by one case).
- The experiment code is not production code. It is reverted in the worktree; the diff is kept as
  `research/convergence-review/seed-prep.patch`. Nothing in `src/` differs from `4e84f0e` after this study.
- The liquid-depletion family (80 requests under the scoring definition of the data-generation review, Part
  3) is unsolvable by every lane and costs 10 to 12 s per request on every route; it moves the neural-first
  to classical ratio but no strict count.

## 9. Reproduction

```
research/convergence-review/compare_prep.sh <out-dir> design-v2/holdout-v2.jsonl models/G/seed-17023/epoch-160.sidecar.json <mode> [<factor>]
node research/convergence-review/score_prep.js eval/prep-none-G17023-holdoutv2 eval/<arm-dir> ...
node research/convergence-review/neural-failure-mechanics/analyze.js && node .../render.js
git apply research/convergence-review/seed-prep.patch     # re-instate the experiment switch
```

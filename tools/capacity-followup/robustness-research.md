# Hybrid initialization, solver robustness and numerical tolerance

Research date: 2026-09-12. Local code and results inspected at commit `6528c38`. This note covers both meanings requested by the user: convergence from difficult initial guesses and acceptance at looser numerical error limits. It proposes experiments; it does not claim they have run.

Correction from prior experimental history supplied by the user: loosening acceptance criteria did not help. The initial recommendation to repeat a tolerance sweep is withdrawn. Keep the existing strict criteria and prioritize correction trajectories, seed quality and recovery. That negative result alone does not identify which numerical mechanism causes the failures.

## What the completed study establishes

On each 405-input validation block, strict neural-first successes were 161 for incumbent I, 166 for continuation C at update 4,160, 168 for full-anchor F at 4,160, and 165 for compact-anchor K at 4,160. F's incremental advantage over matched continuation is two cases. Its seven-case advantage over I includes continued training. F gains 16 I neural-first cases and loses nine; neural-only gains/losses are 32/14. Both blocks reproduce these paired classifications. See [the sealed results](../trace-followup/results.md).

The I/F neural-first union is 177 cases; their neural-only union is 148. These are retrospective unions of separately budgeted runs, not achievable performance predictions for a selector or combined solver. They justify testing complementarity without claiming that a cheap confidence score can realize it.

The existing analysis records F neural-only outcomes of 134 strict, seven advisory and 264 failed in each block. Observed stop-phrase counts are 153/154 iteration-limit, 56/53 neural-time-limit, and 18/20 no-admissible-step for blocks 1/2. These are diagnostic phrases, not a verified disjoint taxonomy of terminal causes. They do not establish how many failures another iteration would rescue. Source: `build/neural-trace-followup/v1/validation-analysis.json`.

The failed trace treatment increased omissions from 778 to 842 and reduced fixed-endpoint FIRST success from 166 to 162. Compactness and matched old-example exposure also failed to improve their controls. Repeating those recipes is a lower priority than studying the correction trajectory.

## Research directions for a more robust hybrid

**1. Optimize useful warm starts, not just accurate profiles.** Sambharya et al. train a network followed by a fixed number of solver iterations, using downstream residual or solution-distance losses. Their generalization results apply to specified fixed-point operator classes, not automatically to this nonconvex MESH system with discrete support changes. A practical first step here is a TRAIN-only predictor of native correction success/cost from perturbed seeds; differentiating through the Java solver is not required for that first experiment. [JMLR 2024](https://jmlr.org/papers/v25/23-1174.html).

A newer, closely related lead is *Newton's Lantern* (May 2026 preprint). It fine-tunes AC power-flow warm starts using solver iteration counts and a learned reward model, motivated by the direction of initialization error and difficult Jacobian geometry. This supports examining solver-facing losses, but its reported power-grid performance is not evidence of a distillation improvement. Avoid a reward based only on iteration count: an immediate failure must score worse than an accepted solve. [Original preprint](https://arxiv.org/abs/2605.11102).

**2. Test a bounded I/F seed portfolio.** Use authored input features and a small, fixed number of correction steps to assess each candidate. Observe equilibrium and energy defects, accepted step sizes, support changes, and property-domain rejections. Choose or abandon candidates under one shared budget, reserving time for the classical fallback. Fit any learned selector on TRAIN only. Do not average two profiles with different phase/support states. The 177-case union is a diagnostic ceiling for those recorded separate trajectories, not a result of this proposed policy.

**3. Spend correction time only where measured progress justifies it.** The current learned path defaults to 16 Newton iterations per pass and 2,000 ms shared inference/correction time. More iterations and more time are different interventions. First measure residual histories on iteration-limited cases; the stop label does not establish that more work will help. Only if trajectories are still making useful progress should a separate budget experiment be considered. Include I and C controls and one common request deadline. A progress-based extension should follow a separately registered rule and count its entire cost.

**4. Improve globalization only where the existing recovery fails.** The code already has Armijo backtracking, rejected property-domain trials, damped Gauss-Newton recovery, gradient fallback and stage-local predictors. Simply adding damping is not a new method here. An actual trust-region policy, with radius changes based on predicted versus observed reduction, is a distinct candidate for the no-admissible-step group. PETSc documents trust-region Newton and several line-search alternatives; it does not establish which wins on this application. Check directional Jacobian accuracy and scaling before replacing recovery logic. [PETSc nonlinear solver manual](https://petsc.org/main/manual/snes/).

**5. Use continuation when a nearby feasible state exists.** IDAES homotopy advances fixed conditions in adaptive steps, reverting to the previous feasible point on failure. Here, a certified nearby operating point or previous accepted solution could seed continuation toward the exact requested conditions. This requires a real feasible start and branch-aware handling; an arbitrary neural prediction does not satisfy that prerequisite. Existing classical continuation paths should be reused and measured before creating another. [IDAES homotopy documentation](https://idaes-pse.readthedocs.io/en/stable/reference_guides/core/homotopy.html).

Chemical-process research also supports neural prediction followed by nonlinear reconciliation of physical constraints, including reactor-distillation integration. However, that concerns physical consistency and prediction quality; our old material-only completion experiment already showed why material closure alone cannot substitute for equilibrium, energy and final solver certification. A new reconciliation step would need a bounded coupled objective and full cost accounting. [Digital Chemical Engineering, 2025](https://www.sciencedirect.com/science/article/pii/S2772508125000407).

## How numerical tolerance actually works here

This section documents the existing implementation only. Previous tests did not support loosening the criteria, so these settings are not proposed experiments or recommended changes.

The existing COMMON config `createcheme-common.toml` has a `[columnV3]` setting named `columnV3ConvergenceClosurePercent`. It is in **percent**, whereas the solver receives a fraction:

`effectiveClosure = max(1e-8, configuredPercent / 100)`

| Config value | Effective closure | Interpretation |
|---:|---:|---|
| `0.0` | `1e-8` | Historical strict default |
| `0.0001` | `1e-6` | 100 times the default; reference conversion only |
| `0.001` | `1e-5` | Reference conversion only |
| `0.01` | `1e-4` | Reference conversion only |
| `0.1` | `1e-3` | Maximum currently admitted; not a recommended default |

A nondefault setting changes the scaled residual stop, log-flow correction gate, equilibrium and condenser-split limits, and specified energy-closure audit limits. Results carry the requested closure in their evidence, digest and formulation label. The local material/energy audit families and truncation-defect budgets retain their own limits.

The linear backward-error gate stays `1e-12`. The final temperature-step gate stays `1e-6 K + 1e-9 * T`. Therefore, changing closure alone need not remove a final-step bottleneck. Those quantities measure correction size and linear-solve quality, not absolute error in the physical solution. A closure of `1e-4` does not mean every product composition is accurate to 0.01%; conditioning, row scaling, traces and branch changes matter.

This distinction agrees with KINSOL's separate function-norm and step-length tolerances: a small step can also indicate stagnation. Relaxing the step test without checking residuals can turn a stalled iteration into an apparent success. [KINSOL documentation](https://sundials.readthedocs.io/en/latest/kinsol/Usage/index.html).

Local implementation references: [config definitions](../../src/main/java/com/wormzjl/createcheme/CreateChemE.java), [request conversion](../../src/main/java/com/wormzjl/createcheme/runtime/ProcessSolveServices.java), [closure policy](../../src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java), [final-step gates](../../src/main/java/com/wormzjl/createcheme/science/column/v3/V3ConvergenceEvidence.java), and [independent auditor](../../src/main/java/com/wormzjl/createcheme/science/column/v3/V3AcceptanceAuditor.java).

## Recommended next experiments

Start with a frozen-weight I/C/F trajectory comparison, so model changes do not confound solver behavior. Keep strict closure, all other acceptance gates, trace/support thresholds and budgets unchanged. Use the same inputs and ten-worker offline workload. These are prospective suggestions, not a registered or executed campaign.

1. **Paired trajectory diagnosis:** begin with the 14 neural-only cases lost by F to I and the 32 gained cases, with C as the matched continuation control. Compare residual families, accepted step lengths, support/phase changes, property-domain rejection and directional Jacobian consistency. Distinguish continued convergence from stagnation, changing support and failed search directions. These outcome-selected groups are diagnostic, not a population benchmark.
2. **One mechanism-specific intervention:** choose a bounded seed selector, a solver-facing training objective, or a recovery change based on that diagnosis. Test one at a time. Increase a correction budget only when the observed trajectories support that hypothesis; do not loosen acceptance criteria.
3. **Complete validation:** measure strict gains/losses, all-request mean and tail latency, CPU/property cost and fallback time across the registered population. Preserve reporting of every incumbent-only loss and the full matched C comparison.

The current follow-up probe calls `calculateWithAcceptedProfile`, whose overload fixes closure to the default. Changing the game config therefore does **not** change that offline benchmark. Preserve the sealed strict journals and the strict TRAIN boundary; the existing strict-data loader explicitly requires `closureTolerance == 1e-8`.

The earlier suggestion of loose initialization followed by strict polishing is also withdrawn from the immediate plan. There is no demonstrated benefit here that justifies another relaxation-based intervention.

The strongest immediate research priority is paired trajectory diagnosis under unchanged strict criteria, followed by a single evidence-directed intervention. Full-validation robustness across another training-order seed and new independent data remain unestablished. No new solver run, fit, production setting or archived study was changed for this research note.

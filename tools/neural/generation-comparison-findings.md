# Gen2, Gen3 and transformer under the same native evaluation

This comparison uses the published Gen2 MLP, the selected Gen3 factorized
network, and the retained transformer seed 20260911. All three are frozen
artifacts. Gen2 and Gen3 were rerun against current V3 on the same 405 validation
and 252 test inputs used by the transformer, with ten workers, all three
strategies, a 2-second neural budget and a 30-second request deadline.

The transformer's verified matching journals are reused. Its test outcomes
were already exposed, so this is a shared-cohort model comparison, not another
blind test or a new selection exercise. Earlier Gen2/Gen3 headline counts used
different cohorts or budgets and are retained as history rather than pooled
with these measurements.

**The transformer is strongest overall on this matched cohort.** Neural-first
qualifies 80/252 test cases, compared with 66 for Gen3 and 60 for Gen2, and has
the lowest observed average elapsed time of the three neural-first strategies.
It still costs more time on average than its classical control. Gen3 remains
useful on some short columns, and neither older model is dominated case by case.

## Complete shared test

| Model | Neural-only strict / 252 | Neural-first strict / 252 | FIRST gains / losses vs its classical control | FIRST mean elapsed, ms | FIRST sample SD, ms |
|---|---:|---:|---:|---:|---:|
| Gen2 | 29 | 60 | 9 / 0 | 6,200.48 | 6,790.55 |
| Gen3 | 32 | 66 | 15 / 0 | 6,104.11 | 6,754.14 |
| Transformer | 54 | 80 | 29 / 0 | 5,429.08 | 6,681.01 |

Every classical control qualifies the same 51/252 inputs, with four additional
advisory-only results and 197 failures. Neural-first yields 60 strict / 4 advisory
/ 188 failed for Gen2, 66 / 4 / 182 for Gen3, and 80 / 6 / 166 for the transformer.
No advisory is counted as a strict success, and all 252 cases contribute to timing.

Classical mean times are 5,199.80, 5,274.85 and 5,152.79 ms in the respective
campaigns. Mean paired neural-first overhead is 1,000.68 ms for Gen2, 829.26 ms
for Gen3 and 276.28 ms for the transformer; the corresponding sample SDs are
1,472.27, 2,366.11 and 3,046.23 ms. Reused transformer timing is not contemporaneous
with the older-model runs. Paired controls help interpretation but do not remove
contention, warmup or deadline effects.

Against transformer-first, Gen2 gains 4 cases and loses 24; Gen3 gains 6 and
loses 20. Their combined success union adds seven cases beyond the transformer,
for 87/252 across three separately executed strategies. No budgeted cascade or
router was implemented, selected or timed, so 87/252 is not a deployable result.

| Stages | Cases | Classical strict | Gen2 FIRST | Gen3 FIRST | Transformer FIRST |
|---|---:|---:|---:|---:|---:|
| 2-8 | 28 | 12 | 17 | 19 | 16 |
| 9-16 | 32 | 10 | 14 | 14 | 17 |
| 17-32 | 64 | 14 | 14 | 18 | 24 |
| 33-48 | 64 | 11 | 11 | 11 | 15 |
| 49-64 | 64 | 4 | 4 | 4 | 8 |

The transformer improves the larger-column groups, but 8/64 at 49-64 stages
remains weak. Gen3 has the strongest observed result on the 2-8-stage subset.
Steam-on neural-first results are 33/126, 36/126 and 44/126; steam-off results
are 27/126, 30/126 and 36/126, in Gen2/Gen3/transformer order.

All three models supply raw predictions on all 252 test inputs. Prediction
availability therefore does not explain this performance gap. Gen3 has one
unavailable diagnostic raw residual because the predicted state lies outside
the property domain; its residual statistics have n=251, while its outcome and
timing totals still have n=252. Native codes named `INFEASIBLE_SPECIFICATION`
include admission/path-bound rejections and do not prove physical infeasibility.

## Complete validation

| Frozen model | Classical strict / 405 | Neural-only strict / 405 | Neural-first strict / 405 | Neural-first mean elapsed, ms |
|---|---:|---:|---:|---:|
| Gen2 MLP | 110 | 61 | 136 | 5,065.78 |
| Gen3 factorized | 110 | 67 | 134 | 4,680.56 |
| Transformer, seed 20260911 | 110 | 116 | 161 | 4,071.37 |

All three neural-first campaigns preserve the same 110 classical successes.
They add 26, 24 and 51 strict qualifications, respectively. Gen3 qualifies more
neural-only cases than Gen2, but fewer additional cases beyond classical
initialization; this explains its slightly lower neural-first total.

The older models still solve some cases that the transformer misses. Against
transformer-first, Gen2 has 9 gains and 34 losses; Gen3 has 6 gains and 33 losses.
The union of the three separately measured neural-first success sets contains
174 cases, 13 beyond the transformer. That is potential complementarity, not
the coverage of an implemented or timed cascade.

Classical mean times are 4,008.51 ms in the Gen2 campaign, 3,869.02 ms in Gen3,
and 3,872.85 ms in the reused transformer campaign. Each model has a paired
classical control, but cross-campaign timings remain subject to execution and
load variation. A single campaign per model cannot establish repeated-run
timing uncertainty.

## Raw profiles on identical certified references

All three models supply predictions on all 405 validation inputs. These profile
errors use exactly the same 168 strictly certified validation references.
Values are means of per-column RMSEs, giving each column equal weight; they
are not pooled node errors or the previous study's temperature MAE. Complete
distributions and sample standard deviations are in the summary.

| Model | Temperature RMSE, K | Component-flow RMSE, mol/s | Phase-total RMSE / feed |
|---|---:|---:|---:|
| Gen2 | 22.36 | 208.92 | 1.111 |
| Gen3 | 21.31 | 138.91 | 0.314 |
| Transformer | 11.37 | 72.49 | 0.139 |

The transformer has better profile agreement on these common references.
However, the scalar maximum raw MESH residual does not rank correction success:
its median is 23.25 for Gen3 and 23.77 for the transformer, despite their large
native convergence difference. A single residual threshold is therefore not
established as a reliable routing or acceptance criterion by this study.

## What to do next

These are proposed experiments, not changes made or benefits demonstrated by
this study. The current comparison remains frozen at 16 iterations per pass.

1. **Test the iteration cap before adding more training work.** Keep the neural
   wall budget at 2 seconds and request budget at 30 seconds; compare 16, 32 and
   64 iterations per correction pass on validation. Of the transformer's 194
   failed neural-only test attempts, 103 explicitly report iteration-budget
   exhaustion and 52 of those finish below 1 second. Another 64 report neural
   time-budget exhaustion. These stop flags motivate the ablation; they do not
   establish that additional iterations will converge or improve average latency.
   Preserve the strict audit and classical fallback, and report every gained and
   lost case plus time cost.

2. **Add a narrowly scoped native material-balance training loss.** Compute
   stage/component balances using the same reflux, withdrawal and pumparound
   edges as V3, with fixed TRAIN-derived throughput scales. Verify the Python
   residual against native TRAIN fixtures, retain existing profile and trace
   supervision, and change one loss term at a time. Better profile RMSE and a
   lower scalar raw residual are insufficient proxies for correction success;
   use full native validation for the decision. Recent distillation work also
   embeds MESH equations into initialization training, supporting this direction
   as a hypothesis rather than a guarantee for this property package.
   [Zhao et al., 2026, publisher abstract](https://www.sciencedirect.com/science/article/pii/S0098135426001298).

3. **Acquire certified training columns where coverage is weak.** Prioritize
   33-64-stage configurations, including the existing N=38/52 gaps, and obtain
   actual wet-equilibrium labels. The current transformer training set has only
   79 columns at 49-64 stages and no fitted wet profiles; producing a prediction
   on an input does not imply it is accurate there. Select acquisition inputs
   from TRAIN-only designs and exclude every historical validation/test input.
   If label generation is the bottleneck, evaluate a distinct offline teacher
   initialization method. V3 already has several continuation paths; a
   pseudo-transient teacher experiment would need its own comparison and the
   unchanged final steady-state certificate. Recent complex-distillation work
   describes such an initialization approach in its abstract.
   [Gao et al., 2026, publisher abstract](https://www.sciencedirect.com/science/article/pii/S1004954126002983).

4. **Evaluate a shared-budget router or multi-start policy.** The seven extra
   older-model test successes and Gen3's short-column results show diversity worth
   investigating. Develop routing on TRAIN outcomes and tune only on validation;
   do not turn the exposed test's short-column pattern into a claimed validated
   rule. Charge every attempted correction to one shared neural budget and retain
   the original whole-request deadline. The separate-run union does not establish
   that a cascade can reach those cases within the same time limit.

For latency work, the measured raw prediction averages are approximately
1.08 ms, 1.18 ms and 3.13 ms for Gen2, Gen3 and the transformer, respectively,
while neural-only attempts average roughly 1.0-1.3 seconds. This points toward
correction, stopping policy and fallback costs as more consequential targets
than raw inference optimization; these timings are not a call-stack profile.

The [supplementary diagnostics](generation-comparison-supplementary.json)
record the post-run stop-flag counts and their script/journal hashes. Flags may
overlap and are descriptive, not causal diagnoses. Any future policy or training
change should be frozen using validation before a new independent test cohort;
the present 252 inputs are already exposed. No such change was executed here.

## Interpretation and reproduction

Neural-only means a neural guess followed by the native physical corrector.
Neural-first adds classical fallback within the original request deadline.
Raw guesses never count as solved cases, and accepted equilibrium advisories
remain separate from strict qualifications. All cases, including failures,
contribute to timing and CPU statistics.

The generations differ in training data and selection history as well as
architecture: Gen2 used 483 fitted columns, Gen3 579, and the transformer 805.
These results compare the retained models as complete initializers; they do
not isolate an architecture or training-data effect. Steam-on inputs also do
not establish generalized wet-tray capability: the fitted generalized profiles
contain no qualified free-water trays.

Use `native_generation_comparison.py` with commands `assets`, `preflight`,
`prepare`, `run`, `report`, and `seal` in order. Completed runs are validated
and reused; partial or changed evidence is rejected. The
[protocol](generation-comparison-protocol.md) and
[frozen registration](generation-comparison-plan.json) bind the candidate,
population, implementation, check and reference-journal hashes. The additional
Gradle init script registers only a TRAIN check; frozen solver sources and
`build.gradle` remain unchanged.

Both older models passed current Java/Python parity on their archived TRAIN
fixtures, including exact branch, wet and zero-flow masks. Each also passed
40 supported predictions across ten original TRAIN fixtures, with ten calls
synchronized inside inference, bit-identical serial/parallel outputs,
cancellation, unchanged later predictions and terminated owned workers.
Nine focused analysis/provenance tests passed, including exclusion of
uncertified references and rejection of mutated results.

The [full results](generation-comparison-results.md),
[summary](generation-comparison-summary.json) and
[case map](generation-comparison-case-map.jsonl) retain counts, paired IDs,
stage/steam/equipment strata, native failure codes, CPU and allocation data.
The [cache manifest](generation-comparison-cache-manifest.json) records the
verified archive outside Gradle clean. Retain its declared preceding-study
dependencies. No model fitting, reselection, decoder change or runtime-default
promotion is part of this comparison.

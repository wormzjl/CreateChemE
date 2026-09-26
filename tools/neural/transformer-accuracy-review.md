# ChatGPT review of transformer accuracy results

Reviewed on 2026-09-11 against commit `d02834c`. ChatGPT read the current
worktree's reports, protocol, training/evaluation implementation and coverage
evidence. This is an analysis and proposed experiment sequence; no proposed
training or native evaluation was executed.

[ChatGPT review conversation](https://chatgpt.com/g/g-p-6aa026927b5c819187b10da97168efd4-createcheme/c/6aa13fb1-1d04-83ec-8fb0-8f0a7c432664)
· [Study findings](transformer-accuracy-findings.md)
· [Complete results](transformer-accuracy-results.md)

## What the evidence supports

The regularization and decoded-flow ablations did not beat the baseline's
three-seed mean selection score: 1.111 versus 1.123–1.136. This does not establish
statistical superiority or show that regularization is generally ineffective.
The regularization bundle also changed several settings together.

The useful change was checkpoint selection. The chosen model still uses baseline
training. Its temperature MAE improved from 10.76 ± 6.80 K to 9.54 ± 6.44 K on
168 certified validation references. Those are the labeled subset of 405
validation inputs, and the SD is across columns. The accuracy study did not run
the selected candidate through a new full-405 native validation evaluation.

On the prospectively frozen 252-case native test, LNN_FIRST improved from 83 to
86 strictly qualified cases, with 10 gains and 7 losses. It preserved all 58
classical qualifications. Its mean elapsed time was 3.768 s, versus 3.486 s for
classical initialization: broader convergence coverage with an average fallback
overhead, not an overall speedup over classical. One execution per model does
not establish repeated-run timing uncertainty.

## Recommended experiment order

### 1. Select using all 405 native validation cases

Evaluate the three archived baseline-seed checkpoints on every validation input
under CURRENT_ONLY, LNN_ONLY and LNN_FIRST. Keep the profile metrics on the 168
certified references, but rank the shortlist using predeclared native outcome
and latency rules. This includes validation inputs that lack usable supervised
labels and directly tests the behavior the initializer is intended to improve.

Add a follow-up driver and versioned evaluation protocol. No new training is
needed. Compare against the current selected checkpoint under the same new
execution conditions. The proposed gate is more strict LNN_FIRST qualifications,
no lost classical qualifications, and no all-case mean-latency regression
against that selected checkpoint. Report case-level tradeoffs explicitly.

Freeze the choice using validation only. Use a fresh, input-only 252-case test
for a new prospective claim; reuse of the now-exposed test is regression
evidence. Do not promote a checkpoint based on test outcomes.

### 2. Test one native material-balance loss

Add a narrowly scoped differentiable hydrocarbon material-balance penalty in a
new trainer revision, keeping the architecture, existing supervision and
deployment decoder fixed. Phase-total normalization alone does not enforce
component balances, equilibrium or energy closure. Another absolute-flow
penalty can favor bulk flows while contributing little trace supervision.

Match native reflux, withdrawal, pumparound and boundary equations. Verify the
training residual against native fixtures from TRAIN only. Use fixed reference
throughput scales and pre-threshold flows so that shrinking support cannot
artificially eliminate the penalty. Native decoding and certification remain
the evaluation authority; a material-only loss does not establish VLE or energy
closure. A differentiable Peng–Robinson implementation is unnecessary for this
first test.

Compare baseline and the isolated added-loss arm across three GPU seeds. Require
improved material-residual tails without worse temperature or trace metrics,
then apply the same full native-validation and prospective-test gates. Expected
cost is a small additional training calculation plus native evaluations; no
accuracy or speed benefit is established yet.

### 3. Acquire qualified labels in missing regions

Use a bounded acquisition pool selected from inputs and training coverage,
emphasizing N=38/52 and sparse large-column/equipment combinations. The current
805 training columns include only 79 at 49–64 trays and none at N=38 or N=52.
All fitted profiles have no free-water trays, even when steam is present.

Keep the old dataset immutable. Require native certification and retain complete
provenance for new labels. Exclude validation/test inputs and define exclusions
for closely related input families before acquisition. Predeclare treatment of
disagreeing accepted roots; do not average incompatible profiles or assume every
failed solve proves infeasibility. Preserve naturally rare LIQUID_ONLY cases.

Compare unchanged baseline training on the original and expanded datasets,
using three GPU seeds. Require improved deficient-stratum results together
with improved full-population native performance. Label acquisition makes this
the highest-cost proposal of the three.

## Independent Codex checks

The stored validation records contain 167 TWO_PHASE references and one
LIQUID_ONLY reference. Both frozen models misclassify that lone LIQUID_ONLY
case (`gd-s17-w1-p4-d1-r00`). Thus 167/168 aggregate accuracy hides 0/1 observed
liquid-only recall; one example cannot establish rare-branch generalization.
Report branch confusion counts instead of relying on overall accuracy.

The stored native journals give these strictly qualified LNN_FIRST counts:

| Tray count | Test cases | Incumbent | Selected candidate |
|---|---:|---:|---:|
| 2–16 | 60 | 27 | 28 |
| 17–32 | 64 | 29 | 30 |
| 33–48 | 64 | 18 | 18 |
| 49–64 | 64 | 9 | 10 |

These retrospective strata support investigating the coverage gap but must not
be used to tune a model and then claim the same cases as a blind test.

## Execution constraints for the next study

Use **10 worker threads**, as requested. The current Java benchmark rejects
multiple workers and the Python runner/validator freeze one worker. A versioned
concurrent evaluation path is required; changing only the command flag will
fail. Preserve the archived serial protocol and results.

Keep 405 validation inputs, one frozen 252-case test per study, all three
strategies, the existing budgets, decoder parity, rigorous certification and
fallback. Report latency under concurrent load separately from historical
serial timings. Do not assume a tenfold speedup or unchanged deadline outcomes.

The recommended immediate next step is experiment 1. It tests the selection
mechanism that already showed value, uses existing checkpoints, and provides a
native validation basis for judging the two more expensive training changes.

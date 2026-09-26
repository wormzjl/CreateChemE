# Generation 3: rescued labels and candidate comparison

The generation 2 model, fitted data, original folds, journals and published metrics
are retained as an immutable baseline. This experiment adds physically qualified
rescued training cases and compares a small, declared set of initialization
methods. Thermodynamic equations, physical acceptance checks and native corrector
logic remain unchanged.

## What can become a new label

The generation 2 run rescued 147 previously failed matrix inputs according to
native acceptance. **141** also passed strict water equilibrium qualification:

| Original fold | Strictly qualified rescues | Allowed use |
| --- | ---: | --- |
| Train | 96 | Add to the existing 483 qualified training profiles: 579 total |
| Validation | 26 | Additional validation references; never fit |
| Test | 19 | Held-out references and previously reported regression evidence; never fit |

All original split assignments remain fixed. Accepted supersaturation advisories
are excluded from fitting: the other six native rescues are four train, one
validation and one test advisory. None of the 35 historical failures was rescued
by generation 2. The original 110 qualified validation references can therefore
be supplemented by 26 qualified validation rescues without changing the 405
validation inputs or placing their labels in training.

A rescued profile is a label only when its input and component axis exactly match
the authored request, the native solver succeeds, and its final water grade is
`DRY_EQUILIBRIUM` or `WET_EQUILIBRIUM`. A solver certificate is required; the raw
generation 2 prediction is never itself treated as a teacher. Replaying the 96
newly fitted rescue inputs measures fit/replay behavior, not generalization.

## Declared candidates and controls

| Candidate | Purpose |
| --- | --- |
| Frozen generation 2 stage MLP | Existing model/control; do not refit or alter its cached metrics |
| Generation 3 MLP, same architecture | Retrain with the 96 eligible rescues; control for the effect of added data under the existing representation |
| Factorized total/composition network | Predict phase totals and component distributions separately, targeting the large flow-profile errors seen previously |
| Nearest-profile transfer, k=1 | A nonparametric reference using only eligible training profiles |
| Nearest-profile transfer, k=3 | A small, predeclared local-transfer alternative; phase/geometry compatibility must be enforced |
| `CURRENT_ONLY` | Classical physical-solver baseline |

Nearest-profile transfer is described as a transfer initializer, not as a newly
trained neural network. The MLP control retains the original architecture,
training recipe and random seed wherever applicable; refitting training-only
normalization to the expanded data is part of the declared data update. The
factorized network and transfer candidates use the same eligible training inputs.
Neither candidate family can retrieve validation or test profiles at inference.

All candidate fitting, epochs, scaling, coverage limits, k choice, routing and
selection use the original 405 validation inputs only. The finite candidate set
is chosen before fresh-test outputs exist. The selection record must state its
rule and freeze selected artifact hashes and policies before running the new
comparison cohorts. The fresh test must not select a candidate after its results
are revealed. A candidate that disappoints there stays reported; it is not tuned
against that test and relabelled as the same experiment.

## Fresh test, frozen before fitting

`prepare_gen3_holdouts.py` freezes a new **252-input operating holdout**, with
exactly four inputs for every integer tray count 2–64. Each count has two steam-on
and two steam-off inputs, giving 126 of each; 100 inputs have 40–64 trays. The
component, thermal, pressure, reflux, equipment and feed bounds remain those of
the generalized design, including all twenty composition coordinates equally.

A new seed, `202609103`, constructs a complete 2,809-point strength-two OA-LHS
candidate pool using the existing 48-dimensional design method. Preflight again
retains 16 invalid two-tray structural requests in a separate exclusion journal.
The 252 inputs are selected from the admitted pool using **only input metadata**:
hash-ordered stage processing, two cases per steam state, preference for distinct
PA/side counts within each stage, then global PA/side/pair balance and fixed
SHA-256 tie priority. The selected subset is a stratified holdout; **it is not
claimed to retain the full candidate pool's OA property**.

The frozen subset has PA counts 0–4 distributed as 51/50/50/51/50 and side-draw
counts 0–3 as 63/63/64/62. The two-tray side-draw restriction explains the small
imbalance. Every new candidate was checked against all 2,793 original inputs and
35 prior-failure inputs, with no canonical input overlap. No fresh candidate or
selected test profile is permitted in fitting. The new holdout tests unseen
operating inputs across all geometry sizes; it does not create new species or
validate a different property package.

The original 395 geometry-test inputs remain excluded from fitting, but their
generation 2 outcomes were already published and informed the motivation for
this work. Their replay is therefore **previously reported regression evidence**,
not a newly blind test. The 252 fresh inputs are the primary prospective comparison.

## Recovery comparisons and full retry

The original 2,046 failed matrix requests minus 147 native-accepted generation 2
rescues leave **1,899** still-failed matrix inputs. Adding the 35 historical
failures gives **1,934** remaining requests. The six advisory-only rescues are
tracked separately; they are neither fitted labels nor silently reclassified as
native failures.

Before generation 3 fitting, freeze a **256-request recovery comparison**: all 35
historical failures and 221 of the 1,899 remaining matrix failures. The latter
are selected by stage-bucket/steam/PA strata with deterministic hash priority,
without any generation 3 output. This is an outcome-conditioned diagnostic
cohort, not a general operating success-rate denominator. It contains no newly
fitted rescue input. All candidate methods can be compared on it fairly, and the
validation-selected method can then retry the full 1,934-request remaining pool.

The comparison union contains 877 unique inputs: fresh 252, old geometry-test 395,
and recovery-comparison 256, with 26 overlaps between the latter two cohorts.
Coherent cohort tags are preserved. Do not add their counts as if all cases were
independent, and do not treat repeated executions of a shared case as new samples.

## Timing, allocation and memory

Use a fresh 30-second parent deadline for **each method's request**. Neural
correction remains limited to 16 iterations and uses the frozen 10-second budget
in parallel comparison runs and 2-second budget in serial runs. The budget must
not be silently shared among different candidates in one batch. Native physical
acceptance tolerances remain unchanged.

Independent cases may use ten solver workers. If several candidates share one
input task, execute their requests sequentially with a deterministic rotated
order, and reset each method's budget. Rotation reduces systematic warm-up/order
bias; it does not make concurrent wall times a serial latency benchmark. All
exceptions, deadlines, admissions and candidate coverage rejections remain in
the journals.

The primary serial benchmark is **64 fresh input-only cases**, selected before
fitting from the new 252 and covering all 50 occupied stage-bucket/steam/PA strata.
Every compared method receives the same 64 inputs. The historical generation 2
benchmark used a different old 64-case set and stays a separate cached historical
reference. Compare the selected method in `CURRENT_ONLY`, candidate-only and
candidate-first-with-classical-fallback modes on the new shared 64.

Report acceptance and strict qualification beside all-attempt latency. Compare
speed only on matched inputs accepted by both methods; fast rejection is not a
solve speedup. Thread allocated bytes are allocation volume, not retained RAM.
Use separate serial JVMs with the same input-only source for per-mode working-set
and private-memory comparisons; whole-process peaks include runtime, warm-up,
model/profile storage and inputs. Nearest-profile storage belongs in the model
memory accounting even though it has no trained weight matrix.

## Reproduction and reporting files

```powershell
python tools/neural/prepare_gen3_holdouts.py
```

The fresh design is frozen under `build/neural-gen3/fresh-design/`. All outputs
refuse to change existing bytes. `design.json` records seeds, source/output hashes,
cohort counts, balance, exclusions and overlap checks. The files are:

| File | Role |
| --- | --- |
| `candidate-pool.jsonl` | Every new candidate input; no training labels |
| `matrix.jsonl` | Primary 252-input fresh test |
| `exclusions.jsonl` | Sixteen preflight model-contract exclusions and evidence |
| `fresh-benchmark.jsonl` | Primary 64-input serial benchmark |
| `recovery-comparison.jsonl` | Predeclared 256-input recovery diagnostic |
| `remaining-gen2-failures.jsonl` | Full 1,934-input retry pool |
| `validation-replay.jsonl` | Original 405 validation requests, with rescued reference seeds separately identified |
| `old-test-replay.jsonl` | Previously reported 395-case geometry test |
| `train-rescue-replay.jsonl` | Ninety-six newly fitted cases, labelled explicitly as replay |
| `comparison-source.jsonl` | Deduplicated 877-case comparison union with overlapping cohort tags |

The generation 3 summary writes new artifacts only. Each candidate/run retains its
model hash, source hash, budgets, concurrency and completeness evidence. Report
fresh test, old geometry-test replay, recovery comparison, full remaining retry,
and trained-rescue replay separately. Include raw-versus-final temperature, flow,
water and branch errors; failure regions; supported coverage; native versus
strictly qualified rescues; paired serial latency/allocation; and whole-JVM memory.
Keep cached generation 2 historical metrics and new same-input control runs
distinct so an apparent data gain cannot be caused by changing the comparison set.

After the fresh classical run completes, attach references without modifying the
frozen design:

```powershell
python tools/neural/attach_gen3_references.py
```

The new `build/neural-gen3/evaluation-inputs/comparison-source.jsonl` and
`fresh-benchmark-source.jsonl` retain the exact877/64 inputs, IDs, folds and cohort
tags and add completed classical references for diagnostics. Candidate prediction
must not read these reference profiles. Isolated memory profiles still use the
original **input-only** `fresh-design/fresh-benchmark.jsonl`.

`evaluation-inputs/current-reference/` is an assembled outcome reference, not a
new877-case timing run:252 fresh classical results,590 original matrix classical
results,30 historical classical failures and5 historical wet-continuation
failures. Those five retain their original protocol rather than being renamed
`CURRENT_ONLY`; mixed historical timings are omitted. `attachment.json` records
all source/output hashes, and the helper rejects mismatched inputs, IDs, folds or
reference seeds.

The generation 3 summary compares each separate one-worker candidate profile
against isolated classical results by canonical input hash. Wall time, thread CPU
and allocation ratios and differences use only identical inputs strictly qualified
by both methods. Qualification counts remain beside the ratios, and source files
are checked for the same64 inputs with no teacher profiles loaded.

For full recovery, use labels `full-recovery:gen3-factorized` and
`full-recovery:nearest-k1`. A separate `fullRecoveryUnionDiagnostic` reports their
strict and native intersections/unions on the frozen1,934-input pool. It counts
each request once and never adds the256-case diagnostic sample. Partial runs show
only paired observations and explicitly count requests not yet paired.

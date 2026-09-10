# Generalized initialization experiment

This experiment expands the neural initializer to the existing twenty-component
property package, with every component fraction varied on the same basis. The
shared stage predictor accepts a variable number of nodes. The experiment covers
2–64 trays, steam off/on, zero to four pumparounds, zero to three side draws,
reflux ratios 0–10, and a full column pressure profile inside 100–300 kPa.
It changes initialization; the accepted result still comes from the native V3
equations, convergence certificate and acceptance audit.

The model ships as an **experimental opt-in**. Under `[columnV3]` in
`createcheme-common.toml`, set `initializerModel = "GENERALIZED_EXPERIMENTAL"` and retain
`initializerMode = "LNN_FIRST"` for classical fallback. `LNN_ONLY` reproduces the
standalone neural-correction experiments. The default `LOCAL_EXPERTS` retains the
previous dry/wet and legacy models. The selected immutable model is captured on
the server thread when a request is admitted.

## Observed coverage

The original initializer accepted 747 of 2,793 requests: 706 qualified dry states
and 41 dry-supersaturation advisories. Training used 483 qualified profiles;
110 other qualified profiles were available for validation. There were no wet
training profiles. Ten-worker generation completed in 1,310.8 seconds.

The selected 96-unit model had 63 accepted validation corrections (61 qualified)
out of 405 inputs. Increasing width to 192 yielded 50 (48 qualified); allowing
32 rather than 16 correction iterations yielded 56 (54 qualified). Selection
therefore kept the smaller model and 16-iteration policy. Its weights and
inference/correction code were frozen before held-out testing.

On all 395 held-out inputs, the fresh model accepted 43 (42 qualified), versus
116 (113 qualified) for the original initializer. It recovered 147 of the 2,046
failed matrix requests, of which 141 passed independent water qualification.
It recovered none of the 35 failures from the previous twenty-component
campaigns; thirteen of those prior requests were outside the new domain.
Three old nineteen-component failures were reported as an incompatible basis.

The largest weakness is the internal flow profile, especially in tall columns.
No held-out 42-, 56- or 63-tray case converged from this model. Raw guesses can
have large total-phase-flow errors despite accurate condenser-branch selection.
This is why the generalized model does not replace the default experts. The
final model card and case/zone maps contain the full denominators, raw-versus-
corrected errors, timing and memory evidence.

The [model card](generalized-model-card.json) retains all measurements and hashes.
The [compressed case map](generalized-case-map.jsonl.gz) archives every one of
the 2,809 finite-design inputs, all 48 latent coordinates, exclusions and observed
outcomes. Cases not sent to the neural model are explicitly marked unevaluated.
The uncompressed working maps are `build/neural-generalized/analysis/summary-cases.csv`
and `summary-zones.csv`; full profiles remain in the original journals.

Of the recovered matrix failures, 117 had the original
`INFEASIBLE_SPECIFICATION` status. Their corrected states passed the native
certificate. This demonstrates why the original initializer's heat-admission
and continuation-path bounds were retained as solver outcomes rather than
used as proofs for the physical exclusion filter.

## Measured correction cost

The separate 64-case serial benchmark used the frozen model, 16 iterations per
neural pass, a 2-second shared neural budget and a 30-second overall request
deadline. Each mode was warmed; execution order rotated by case ID.

| Initialization | Native accepted | Water-qualified | Median all-attempt time |
| --- | ---: | ---: | ---: |
| Current only | 26/64 | 25/64 | 912.73 ms |
| General neural only | 10/64 | 10/64 | 814.65 ms |
| General neural first, classical backup | 32/64 | 31/64 | 1,739.42 ms |

Neural-first retained all 26 classical successes and recovered six additional
cases in this sample. Its extra attempts increased median latency. Only four
cases qualified with both standalone initializers; their median paired
classical/neural time ratio was 5.68. That small accepted-pair result is not a
general speedup claim, and fast failed attempts are not successful solutions.

The isolated neural-only JVM peaked at 929.30 MiB sampled working set and
991.61 MiB private bytes. The isolated current-only JVM peaked at 833.61 and
890.57 MiB, respectively. Both ran the same 64 input-only requests with the same
heap settings. These include the numerical solver and JVM; they do not measure
retained bytes per request. The network's 28,078 weight/bias parameters occupy
224,624 bytes of primitive Java storage (219.36 KiB), excluding headers and
scratch arrays.

Across the 113 qualified classical held-out profiles, median raw-network
temperature RMSE was 18.48 K, component-flow RMSE 182.22 mol/s, and total-phase-
flow RMSE 0.911 times feed flow. Among the 42 qualified neural corrections,
raw-to-final medians were 20.47 K and 136.75 mol/s. The 23 cases where both
methods qualified agreed after correction to a maximum 2.93e-8 K and
2.87e-7 mol/s in the full profiles. These are distributions of case-level errors,
not a single pooled RMSE over all trays.

Verification passed 126 JUnit tests and 16 Python contract tests. A separate
stress check completed 25,000 queued tasks and observed every injected failure
and cancellation. Ten simultaneously entered native solves matched their serial
profiles, audits and closure evidence bit for bit. Exported Python/Java features
and real network predictions also passed independent parity checks. The mod JAR
includes the weights and runtime predictor; offline generators are excluded.

The exact finite design, composition constraints, operating ranges, exclusions,
and limits of completeness are documented in [generalized-design.md](generalized-design.md).
Methane has no dedicated sampling group, feature, loss weight, or held-out rule.
All twenty fractions stay within ±20% of their reference fractions after a
bounded-simplex projection. This does not cover new species, zero fractions,
pure-component feeds, or arbitrary crude assays.

## Data and ownership

The frozen strength-two OA-LHS has 2,809 rows and enumerates all 2,520 structural
cells. Sixteen rows violate two-tray layout contracts, leaving 2,793 requests for
the original initializer. The teacher uses ten platform workers, a bounded queue
of twenty entries, and at most twenty outstanding futures. Each worker owns its
input, deadline, observer, numerical session and scratch arrays. A single writer
records completed results. Every future is observed; unexpected worker errors
abort the campaign and cancel outstanding work instead of being relabelled as
physical failure. Each native solve has a 30-second wall deadline that begins
when its worker starts.

The journal preserves every attempted input and its native failure code. Only
accepted, independently water-qualified profiles are eligible for training.
Dry supersaturation advisories remain in the case map and are excluded from the
strict training labels. No failed state becomes a target. A wall timeout under
ten-worker contention is a resource-budget outcome, not proof of nonconvergence
or physical impossibility.

Native `INFEASIBLE_SPECIFICATION` outcomes include admission estimates and bounds
along the solver's continuation path. They are kept distinct from the design
tool's physical necessities, property limits and layout restrictions. In
particular, a failed solve or a cold-liquid feed-enthalpy estimate is not promoted
to a rigorous proof about all possible separated product states.

## Learned representation

There are two small, immutable CPU networks:

1. A global network predicts the condenser phase branch.
2. A branch-conditioned network applies shared weights to every column node.

For the twenty-component basis, the global vector has 74 coordinates and each
node vector has 99. Inputs include all component fractions, feed conditions,
stage count, pressure drop, condenser and reboiler specifications, reflux, sorted
equipment tuples, node position, local pressure, and local and cumulative heat,
steam and withdrawal terms. Thus changing a duty or moving a side draw changes
the prediction even when condenser temperature is unchanged.

Each node has 43 outputs: temperature, twenty liquid component flows, twenty
vapor component flows, free-water flow, and wet-state score. Component flows use
the same log transform relative to each component's feed; dilute components are
not discarded from the loss. Prescribed condenser temperature, absent terminal
phases, and absence of a water source are enforced during decoding. All other
predictions remain guesses and require the native corrector.

Normalization uses training columns only. Each column receives the same total
weight in the profile loss, independent of its tray count. Whole stage counts
are withheld for validation and testing; trays and repeated structural cells
never cross folds. Validation chooses training epochs. Frozen model evaluation
has a separate command and cannot retrain the weights. The production model
contains no dataset, nearest-profile lookup or training-time Python dependency.

This is supervised initialization followed by a rigorous numerical corrector.
It does not claim a physics-informed training loss or that raw network output
satisfies mass, energy or phase-equilibrium equations.

## Evaluation and measurement

Before training, `prepare_generalized_evaluation.py freeze` fixes all validation
and test requests and an outcome-independent 64-case test benchmark. The latter
covers stage-count buckets, steam state and every pumparound count. Finalization
attaches teacher outcomes without changing those selections. Matrix failures
and failures from the previous methane campaigns are separate retry cohorts;
old nineteen-component requests are explicitly marked incompatible with the
new twenty-component basis.

The Java probe records raw inference time and allocation, projected native MESH
residual, and raw-versus-final temperature, component-flow, total-phase-flow,
free-water, wet-mask and branch differences. It also compares raw output with
the original initializer's final profile wherever available. A raw profile is
never substituted for an accepted solver result.

Concurrent runs measure throughput and success under their stated resource
budgets. Serial measurements warm both initialization modes, alternate their
order by case ID, and include fresh inference in neural solve time. The raw
prediction diagnostic is measured separately. All attempted cases remain in
the timing denominator; accepted-pair comparisons are labelled separately.

Native diagnostic iteration counters describe the selected terminal pass, not
the accumulated continuation and support-repair work. Wall time, thread CPU and
allocation measurements cover the whole call. In the serial benchmark, raw
inference alone took a median 0.424 ms.

Memory quantities have different meanings:

* Thread allocated bytes measure allocation volume during one operation.
* Primitive parameter storage counts weight and bias doubles, excluding object
  headers and scratch space.
* Heap/pool measurements describe the experiment JVM. Separate pool peaks are
  not added and presented as a simultaneous heap peak.
* `watch_experiment_memory.ps1` records Windows process working set and private
  bytes. These include the runtime, loaded inputs, model and every worker.

The serial benchmark runs without concurrent data generation. Its process peak
is not a per-case retained-memory measurement or an in-game memory estimate.
Separate input-only benchmark JVMs also measure current and neural-only paths
individually. Raw inference is below the Windows thread-CPU timer's resolution
in many calls; zero CPU samples do not imply zero CPU work. Wall inference times
use the monotonic high-resolution timer.

## Reproduction

Use JDK 21 and a Python environment with NumPy. The design generator and report
tools otherwise use the standard library. Set BLAS worker counts to one for the
trainer so that its own scheduling is explicit.

```powershell
python tools/neural/generalized_design.py generate build/neural-generalized/design
python tools/neural/prepare_generalized_evaluation.py freeze
.\gradlew.bat generalNeuralExperiment `
  '-PgeneralSource=build/neural-generalized/design/matrix.jsonl' `
  '-PgeneralOutput=build/neural-generalized/v2' --offline
python tools/neural/prepare_generalized_evaluation.py finalize
python tools/neural/train_generalized.py build/neural-generalized/v2 `
  --design-bounds --design-file build/neural-generalized/design/design.json `
  --label-policy equilibrium-only --epochs 500
.\gradlew.bat generalNeuralParallelCheck `
  '-PgeneralSource=build/neural-generalized/v2/cases.jsonl' --offline
```

Generation and Java evaluation refuse to overwrite an existing journal. Choose
fresh output directories for a new run. Selection freezing permits identical
bytes but rejects changes to an existing frozen selection. Model revisions,
dataset hashes, run budgets and final performance belong in the accompanying
model card; numerical claims must be tied to those exact artifacts.

After running the evaluations and memory profiles described in
[generalized-evaluation.md](generalized-evaluation.md), regenerate the final
summary with `summarize_generalized.py` and package it with
`finalize_generalized_report.py`. The latter verifies complete journals, frozen
weights and passing checks before writing the model card and archived case map.
The original selection record is preserved in `generalized-selection.json`;
`generalized-deployment.json` records the separate opt-in packaging decision.

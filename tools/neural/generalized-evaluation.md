# Generalized evaluation and failure recovery

These two tools select the evaluation inputs and summarize the native teacher,
new initializer, corrections and memory observations. They use only Python's
standard library and never train a model.

## Freeze selections before model fitting

```powershell
python tools/neural/prepare_generalized_evaluation.py freeze
```

Defaults read the complete admitted design from
`build/neural-generalized/design/matrix.jsonl` and write
`build/neural-generalized/evaluation-inputs/`. Override paths using `--design`
and `--output`. The frozen files contain all 405 validation inputs, all 395 test
inputs, and a deterministic **64-case test-only serial benchmark**.

The benchmark stratifies by five tray-count buckets, steam enabled and PA count,
giving 50 occupied strata. Each receives one case before the next allocation
round. A fixed SHA-256 priority over case IDs determines within-stratum selection
and stratum ordering. The selection uses no teacher status, label, timing, model
output or success rate. All 64 IDs and file hashes are saved in
`design-freeze.json` before model fitting. A repeated freeze is permitted only
when every output byte is identical; changes require another output directory.

Historical failures are included from the prior dry journal (30) and wet
continuation journal (5). They preserve original failure text, split and IDs in
`design.evaluationOrigin`. Inputs are deduplicated by a canonical hash that sorts
authored equipment/specification collections and normalizes equivalent JSON
numbers, while preserving the component axis. The three failed legacy TJL19
inputs are saved separately as unsupported component bases. The 19-component
axis is never silently padded or reinterpreted as the new 20-component input.
The wet historical failures came from the previous parametric wet-teacher
protocol, which differs from a fresh `CURRENT_ONLY` request; the report retains
that journal provenance instead of pooling it into the new teacher denominator.

## Attach complete teacher outcomes, then run validation

```powershell
python tools/neural/prepare_generalized_evaluation.py finalize --teacher build/neural-generalized/v2/cases.jsonl
```

Finalization requires every admitted design ID exactly once. It verifies input
hashes, splits, frozen selections and prior-file hashes, then emits:

| File | Use |
| --- | --- |
| `validation-source.jsonl` | Model and policy selection; run this first |
| `test-source.jsonl` | Complete held-out test after the model freezes |
| `benchmark-source.jsonl` | Frozen 64-case serial timing comparison |
| `matrix-failures-source.jsonl` | Every `success=false` original matrix request |
| `failure-source.jsonl` | All matrix failures plus deduplicated historical failures |
| `evaluation-source.jsonl` | Combined replay, with overlapping inputs deduplicated |

Accepted teacher seeds accompany the selected inputs solely for prediction-error
comparisons. They do not change selection. Nonconverged and accepted advisory-only
states have no fitted labels; fitting is restricted to equilibrium-qualified
original **training** inputs. Finalization verifies that evaluation inputs do not
overlap qualified training hashes. Failure-only retries retain original split
labels and are not reclassified as new independent test cases.

Run only `validation-source.jsonl` while selecting epochs, coverage guards,
phase policies and other model settings. Freeze the resulting artifact hash and
all policies before running `test-source.jsonl`, `failure-source.jsonl` or the
serial benchmark. The combined replay file is provided for later reproducibility;
its existence is not a reason to inspect test performance during selection.

## Summarize partial and final experiments

```powershell
python tools/neural/summarize_generalized.py --teacher build/neural-generalized/v2/cases.jsonl --output build/neural-generalized/analysis-live/summary.json
python tools/neural/summarize_generalized.py --teacher build/neural-generalized/v2/cases.jsonl --evaluation build/neural-generalized/evaluation/evaluation.jsonl --benchmark build/neural-generalized/benchmark/evaluation.jsonl --output build/neural-generalized/analysis/summary.json --final
```

`--evaluation` can be repeated for separate validation, test and retry journals.
Each run remains separate with its own model hash, resource budgets, source hash
and completion evidence. Results from different models are not silently pooled.
The default partial mode tolerates an unfinished final JSONL write while a worker
is appending. It never skips malformed interior lines. `--final` requires complete
teacher IDs, evaluation source IDs and completed `run.json` metadata.

For a stronger memory comparison, run `profile-current` and `profile-neural` in
separate serial JVMs using the same frozen `benchmark-design.jsonl` input-only
file. The runner warms only the selected mode and loads no teacher profiles for
these runs. Pass each journal as `--isolated-profile <evaluation.jsonl>` to the
summary tool. `isolatedMemoryRuns` then compares whole-process measurements from
separate JVMs without mixing those repeated benchmark inputs into held-out
accuracy or failure-recovery totals. Each mode's accepted and qualified counts
remain visible alongside memory peaks.

Four artifacts are produced beside the requested output path: a JSON evidence
report, a Markdown report, a case CSV and a zone CSV. The case map preserves every
failure, original outcome, location, pressure, mixture bin and available profile
error. Zones include tray count, steam, PA and draw counts, pressure, pressure
drop, reflux, feed/condenser temperatures, reboiler duty, flow fractions, dominant
PA height and low/middle/high bins for **each of the twenty fractions equally**.
The stage/steam/PA joint bins show coupled structural weaknesses. Weak-zone tables
require at least ten observed test cases and remain descriptive: overlapping bins
are not independent validation experiments or proofs of cause.

The report distinguishes:

* Accepted states, strictly equilibrium-qualified states and dry supersaturation
  advisories, including qualified versus advisory-only failure rescues.
* Necessary-condition preflight exclusions, model-contract exclusions, numerical
  nonconvergence, deadlines and native solver admission/continuation-path bounds.
  A numerical failure is never relabelled as physically impossible.
* Raw prediction versus final native output, raw prediction versus teacher output,
  and final neural versus final teacher output where both exist. Temperature,
  component flows, phase-total flows relative to feed, water flow, wet-mask and
  condenser-branch errors are included. Qualified teacher comparisons are reported
  separately from comparisons to accepted warning states.
* Concurrent throughput versus serial warmed-JVM latency. All-attempt timing keeps
  failures in its denominator; a classical/neural speed ratio uses identical cases
  accepted by both methods. Fast rejection is never counted as a solve speedup.
* Thread CPU and cumulative allocated bytes versus whole-process heap, working set
  and private memory. `memory.json` from the process monitor is consumed beside
  every journal. A ten-worker process peak cannot be assigned to a single neural
  initializer, and asynchronous JVM pool peaks are not summed.

A single serial run per case can identify large differences but does not establish
confidence intervals. Small zones, unrepresented operating families and numerical
resource budgets must accompany any claim about coverage or rescue rates.

```powershell
python -m unittest discover -s tools/neural -p test_generalized_evaluation.py
```

The tests verify outcome-independent selection, all 50 benchmark strata,
canonical-input deduplication, safe partial-journal reading, strict water
qualification, matched-success timing ratios and independent profile-error math.

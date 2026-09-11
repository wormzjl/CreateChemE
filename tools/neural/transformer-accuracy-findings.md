# Transformer accuracy study

The six-arm, three-seed CUDA study selected the baseline training configuration.
Neither the regularization bundles nor the added decoded-flow loss improved the
predeclared selection score averaged across seeds. This is a negative ablation
result; the experiment does not establish that these techniques are ineffective
under other settings.

The selected candidate is baseline seed 20260911, chosen with the new decoded
validation criterion. The incumbent used the earlier checkpoint-selection
criterion. Improvements between these two frozen models therefore reflect a
different selected checkpoint, not a benefit from the added regularization or
flow loss. See the [registered protocol](transformer-accuracy-protocol.md) and
[complete results](transformer-accuracy-results.md).

On the same 168 certified references within the 405-case validation set:

| Profile metric | Incumbent | Selected candidate |
|---|---:|---:|
| Temperature MAE, K | 10.76 ± 6.80 | 9.54 ± 6.44 |
| Liquid total MAE / feed | 0.1205 ± 0.0757 | 0.1191 ± 0.0728 |
| Vapor total MAE / feed | 0.1215 ± 0.0748 | 0.1102 ± 0.0707 |
| Liquid component L1 / feed | 0.5197 ± 0.5858 | 0.5167 ± 0.5926 |
| Vapor component L1 / feed | 0.6180 ± 0.6103 | 0.5828 ± 0.6162 |
| Trace log10 MAE | 1.1414 ± 0.4679 | 1.0791 ± 0.4734 |

Values are column means ± sample SD across 168 columns, calculated with CPU
float32 inference after both models were frozen. The temperature mean is 11.4%
lower and the vapor-total mean is 9.3% lower. The liquid-total change is small
(1.2%). Both models classify 167/168 condenser branches correctly and produce
zero predictions outside the checked raw-output bounds. Individual columns can
regress; these validation comparisons do not establish prospective prediction
accuracy. Residual component and trace errors remain substantial.

The baseline arm's temperature MAE is 10.17 ± 0.56 K across its three seed-level
column means. This SD describes training-seed variation and is different from
the across-column SD in the table above. All six arms use the same criterion
for choosing epochs and comparing configurations. The regularization arms
jointly change dropout, weight decay and scheduling, so their separate
contributions cannot be inferred from this study.

The new 252-case test was frozen before fitting and excludes historical inputs
and candidate pools. It uses the [unified evaluation method](unified-evaluation.md)
on every case: serial CURRENT_ONLY, LNN_ONLY and LNN_FIRST, with a 2-second neural
budget, 30-second whole-request deadline and 16 iterations per correction pass.
The earlier 252-case unified test is historical and is not pooled with this one.
No test outcome selects or retunes the candidate.

Both models completed all 252 cases under all three strategies. Strictly
qualified results and all-case elapsed time are:

| Strategy | Incumbent qualified | Candidate qualified | Incumbent seconds | Candidate seconds |
|---|---:|---:|---:|---:|
| CURRENT_ONLY | 58/252 | 58/252 | 3.492 ± 5.62 | 3.486 ± 5.63 |
| LNN_ONLY | 59/252 | 63/252 | 0.721 ± 0.683 | 0.720 ± 0.677 |
| LNN_FIRST | 83/252 | 86/252 | 3.891 ± 5.82 | 3.768 ± 5.66 |

Time is mean ± sample SD across all 252 cases, including failures. LNN_FIRST
gained 10 cases relative to the incumbent and lost 7, for a net gain of 3; LNN_ONLY gained 19
and lost 15, for a net gain of 4. The classical qualified sets were identical
between runs. The candidate's LNN_FIRST gained 28 cases over its classical
control and lost none. Its 86 strictly qualified results exclude six additional
advisory-only results. This is a modest net convergence improvement with case
regressions, not uniform improvement.

The candidate's paired LNN_FIRST-minus-CURRENT_ONLY elapsed difference is
282 ± 2,206 ms across all cases, and −163 ± 1,166 ms on the 58 cases where both
qualify. The incumbent's corresponding differences are 399 ± 1,109 ms and
−110 ± 1,043 ms. These measurements come from one serial execution per model;
the SD describes case variation, not repeated-run timing uncertainty. The
[machine-readable summary](transformer-accuracy-summary.json) retains CPU,
allocation and diagnostic-iteration summaries as well as paired outcomes.

The exported model passed Java/PyTorch parity on 14 training fixtures, exact
branch/wet/zero masks, 32 identical concurrent predictions, cancellation and
malformed-artifact checks. Architecture, solver policy and production defaults
are unchanged.

Use 10 worker threads for the next native study, as requested after this serial
run had started. Register the new worker setting prospectively and report its
timings under concurrent load separately from these serial measurements.

Reproduction uses the project-local environment installed from
`requirements-transformer.txt`. The driver exposes separate immutable steps:

```powershell
.\.neural-venv\Scripts\python.exe tools/neural/transformer_accuracy_study.py prepare
.\.neural-venv\Scripts\python.exe tools/neural/transformer_accuracy_study.py fit
.\.neural-venv\Scripts\python.exe tools/neural/transformer_accuracy_study.py select
.\.neural-venv\Scripts\python.exe tools/neural/transformer_accuracy_study.py export
.\.neural-venv\Scripts\python.exe tools/neural/transformer_accuracy_study.py parity
.\.neural-venv\Scripts\python.exe tools/neural/transformer_accuracy_study.py profiles
.\.neural-venv\Scripts\python.exe tools/neural/transformer_accuracy_study.py native
.\.neural-venv\Scripts\python.exe tools/neural/transformer_accuracy_study.py report
.\.neural-venv\Scripts\python.exe tools/neural/transformer_accuracy_study.py seal
```

Existing outputs are protected from replacement. Inspect the archived run to
reproduce this result; a new experiment needs a new revision and output paths.
The [cache manifest](transformer-accuracy-cache-manifest.json) records archive
and per-entry hashes, including all fits, checkpoints, histories, validation
metrics, sampling inputs, native journals and source dependencies. Prior caches
remain available.

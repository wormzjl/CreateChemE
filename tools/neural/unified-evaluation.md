# Active evaluation workflow

Use two sets:

| Set | Cases | Purpose |
|---|---:|---|
| Validation | 405 | Model/checkpoint selection |
| Test | 252 | Convergence and runtime together |

The test uses the complete existing 252-case holdout. It does not select a
64-case timing subset. Each frozen model runs CURRENT_ONLY, LNN_ONLY and LNN_FIRST
on every test input, serially, with 2 seconds for inference/neural correction,
30 seconds for the whole request, and the existing 16-iteration-per-pass setting.
Acceptance, Newton iterations, wall time and CPU time are recorded together.

The existing 405-case validation selection was made with a 10-second neural
budget and remains frozen. Its counts are selection history, not measurements
under the unified test policy. The earlier full-cohort 10-second results and
64-case timings remain historical diagnostics; they are never pooled with this
test. No fitting, candidate selection or retuning uses test results.

The test inputs have already been released for the previous campaign. Running
them under the unified policy is a fixed-model follow-up, not a new blind test.

```powershell
.\.neural-venv\Scripts\python.exe tools/neural/unified_column_evaluation.py prepare
.\.neural-venv\Scripts\python.exe tools/neural/unified_column_evaluation.py run
```

Run this after any other native campaign finishes so concurrent workloads do not
contaminate the serial measurements. The new entry point does not stop or modify
an existing campaign. It reuses the existing Java harness and model artifacts.

The runner verifies frozen inputs, models, selection and inference-source hashes.
It writes one combined `build/neural-transformer/unified-v1/report.md` and
`summary.json`. Per-model journals contain all three strategies for the same 252
inputs. Complete validated runs may be resumed; partial journals are never
overwritten. The final verified archive goes to
`.neural-cache/unified-evaluation-v1/`, outside Gradle clean.

Use this entry point for the active test workflow. `run_transformer_native.ps1`
and its `fresh`/`benchmark` stages remain available to reproduce the historical
campaign. Their running processes, outputs and frozen protocol are preserved.

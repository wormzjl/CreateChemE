# Native-baseline residual Transformer experiments

This directory contains the offline hybrid framework, matched N/N+1 training and repeated native evaluation. It is tracked on `codex/v4-transformer-investigation`; `.gitignore` explicitly admits it. Production defaults are unchanged.

The [protocol](protocol.md) defines the architecture, data boundary, equal-update training, native budgets, repeated-block selection and prospective test. [Salvage results](../neural/salvage_results.md) explain the 101 added strict TRAIN profiles and preserved original folds. N has 805 certified columns; N+1 has 906.

## Components

- `V3HybridBaseline`: request-owned native MATERIAL_CLOSED anchor, with explicit zero-anchor fallback and cancellation propagation.
- `V3HybridResidualInitializer`: immutable 89,496-parameter CPU Transformer; predicted-branch baseline, continuous residual reconstruction and absolute water/wet/presence outputs.
- `V3HybridModels`: hash-bound pipeline manifest, including the exact frozen material-completion wrapper.
- `train_hybrid.py`: shared N-only normalization, paired initial seeds, exactly 4,160 updates and original 168-reference checkpoint selection.
- `export_hybrid.py` and `run_checks.py`: all six native exports, TRAIN-only numerical parity, exact masks and ten-worker repeatability.
- `benchmark.py`: complete 405-case validation, representative selection and locked 252-case test, with two blocks per pipeline.
- `report.py` and `archive.py`: recomputed paired results and archives outside Gradle clean.

`create_runtime.py` and `create_probe.py` document the one-time additive derivations from frozen predecessor sources. Their generated Java files are versioned. Regeneration or changes belong to a new registered study revision.

## Artifacts

The working artifact root is `build/neural-hybrid-learning/v1`. It holds both plans, input-only prospective test, native anchor banks, normalization, six fit histories/checkpoints, eight pipeline manifests, parity records, journals and selections. Every pipeline manifest binds the weight file and whether material completion is enabled; equal weight bytes do not imply equal inference procedures.

The [training cache manifest](training-cache-manifest.json) binds the verified `.neural-cache/hybrid-learning-v1/training.zip` snapshot, including source files and exact datasets. The salvage archive is separately bound by its [manifest](../neural/salvage_dataset_manifest.json). Archives retain byte-exact files independently of checkout newline conversion. Native results are sealed separately after completion.

## Execution

Use the recorded Java 21 toolchain and `.neural-venv` dependencies. When no evaluation is running, build the existing `neuralMvpClasses` first. The additive init script compiles hybrid sources to a separate class directory and deliberately does not rebuild predecessor classes itself.

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21.0.11'
./gradlew.bat neuralMvpClasses --console=plain
./gradlew.bat -I tools/hybrid-learning/hybrid.gradle compileHybridLearning --console=plain
```

The scripts preserve existing outputs. Preparation, fitting and export refuse to overwrite a frozen revision; the benchmark driver resumes only complete, verified per-pipeline runs. A partial journal remains evidence and is not silently retried. Fresh-test execution requires the complete frozen validation selection.

```powershell
.neural-venv/Scripts/python.exe tools/hybrid-learning/benchmark.py validation
.neural-venv/Scripts/python.exe tools/hybrid-learning/benchmark.py select
.neural-venv/Scripts/python.exe tools/hybrid-learning/run_test.py
.neural-venv/Scripts/python.exe tools/hybrid-learning/report.py
.neural-venv/Scripts/python.exe tools/hybrid-learning/archive.py results
```

The material anchor and completion remain numerical preparation. Strict qualification belongs exclusively to the unchanged native corrector and audit. N and N+1 contain no wet-qualified training profiles; steam-fed dry solutions do not establish wet-tray coverage.

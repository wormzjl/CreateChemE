# Hybrid diagnosis

[Findings and ranked improvements](results.md) explain the completed hybrid study's underperformance. The strongest association is erroneous removal of reference-present trace flows. The material anchor contributes little composition information. The controlled canonical-support parameterization experiment did not improve native outcomes.

The [protocol](protocol.md) separates retrospective evidence from the prospective two-fit diagnostic experiment. Production defaults, old source files and prior archives are unchanged. The original 252-input test is used only as retrospective evidence; new fits and panel screening use TRAIN and original validation inputs.

The artifact root is `build/neural-hybrid-diagnosis/v1`. `case-analysis-v2.json` and `training-analysis-v2.json` supersede the explicitly identified preliminary interpretations. Support, above-floor omissions and gradients are separate analyses. Both fit checkpoints, their final endpoints, exact minibatch counts, parity checks and all six 65-case panel runs are retained.

The [archive manifest](cache-manifest.json) binds `.neural-cache/hybrid-diagnosis-v1/study.zip` and the preceding immutable archives needed to restore its inputs. Sealing replays all seven retrospective analyses without writing their outputs, recomputes ablation/report results, revalidates all six native journals and checks exact published-report equality before creating the ZIP. It does not rerun solvers or training.

The registered experiment entry points are `ablation.py register`, `ablation.py train`, `ablation.py export`, and `run_ablation_native.py parity/native`. Outputs are immutable and these commands are not intended to overwrite a completed study. Native evaluation reuses the unchanged `tools/hybrid-learning/hybrid.gradle` and `V3HybridEvaluationProbe`, with ten workers and identical budgets.

To verify the saved archive:

```powershell
.neural-venv/Scripts/python.exe tools/hybrid-diagnosis/seal.py verify
```

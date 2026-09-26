# Transformer accuracy ablation results

Six predeclared arms used the same 805 training columns, 168 qualified references from the 405-case validation set, and three CUDA seeds. Architecture and deployment decoder were fixed. Validation SD below is across three seed-level column means.

| Arm | Selection score | Temperature MAE K | Liquid total MAE / feed | Vapor total MAE / feed | Trace log10 MAE |
|---|---:|---:|---:|---:|---:|
| baseline | 1.111 ± 0.0434 | 10.17 ± 0.563 | 0.1204 ± 0.00246 | 0.1164 ± 0.0053 | 1.091 ± 0.0174 |
| decoded | 1.123 ± 0.0159 | 10.49 ± 0.148 | 0.1294 ± 0.0037 | 0.124 ± 0.00283 | 1.254 ± 0.0699 |
| regularized-05 | 1.124 ± 0.0272 | 10.16 ± 0.191 | 0.1217 ± 0.00255 | 0.1177 ± 0.00557 | 1.272 ± 0.0124 |
| regularized-05-decoded | 1.131 ± 0.0462 | 10.55 ± 0.215 | 0.1262 ± 0.00548 | 0.1207 ± 0.0059 | 1.438 ± 0.0264 |
| regularized-10 | 1.132 ± 0.0359 | 10.25 ± 0.119 | 0.1215 ± 0.00179 | 0.1162 ± 0.00337 | 1.344 ± 0.0143 |
| regularized-10-decoded | 1.136 ± 0.0242 | 10.55 ± 0.11 | 0.1273 ± 0.00301 | 0.1213 ± 0.00415 | 1.513 ± 0.0188 |

Validation selected **baseline**, seed **20260911**, before fresh-test execution.

None of the added regularization/decoded-flow-loss configurations improved the mean selection score across seeds. The selected candidate is a baseline checkpoint selected with the new decoded criterion; any candidate/incumbent difference is not evidence that the added loss or regularization helped.

The following comparison uses the two frozen native models on the same 168 qualified validation references. SD here is across columns, not seeds. It is diagnostic and does not change selection.

| Frozen model | Temperature MAE K | Liquid total MAE / feed | Vapor total MAE / feed | Trace log10 MAE |
|---|---:|---:|---:|---:|
| candidate | 9.538 ± 6.44 | 0.1191 ± 0.0728 | 0.1102 ± 0.0707 | 1.079 ± 0.473 |
| incumbent | 10.76 ± 6.8 | 0.1205 ± 0.0757 | 0.1215 ± 0.0748 | 1.141 ± 0.468 |

The new 252-case test was frozen before fitting. Every input ran CURRENT_ONLY, LNN_ONLY and LNN_FIRST serially under the unified 2-second neural / 30-second whole-request budgets. No timing subset is used. The incumbent is the previously frozen native transformer; the baseline training arm uses the same new checkpoint-selection criterion as all ablations.

| Model | Strategy | Qualified / 252 | Advisory only | Failed | Elapsed ms, mean ± sample SD | CPU ms, mean ± sample SD |
|---|---|---:|---:|---:|---:|---:|
| incumbent | current | 58 | 3 | 191 | 3492 ± 5.62e+03 | 3469 ± 5.59e+03 |
| incumbent | neural | 59 | 2 | 191 | 721.2 ± 683 | 716.3 ± 676 |
| incumbent | neuralFirst | 83 | 4 | 165 | 3891 ± 5.82e+03 | 3873 ± 5.79e+03 |
| candidate | current | 58 | 3 | 191 | 3486 ± 5.63e+03 | 3469 ± 5.6e+03 |
| candidate | neural | 63 | 5 | 184 | 719.8 ± 677 | 717.2 ± 673 |
| candidate | neuralFirst | 86 | 6 | 160 | 3768 ± 5.66e+03 | 3749 ± 5.61e+03 |

Test SD describes variation across cases; all-case timing includes failures. Paired candidate gains/losses are in the summary. The repeated classical controls are not independent cases. Native diagnostic Newton counters are not sums of every attempted correction pass. Allocation volume is not retained RAM.

Hard support decisions have no training gradient; the original presence BCE and trace losses remain active. The additional flow term uses the deployment decoder within admissible raw output bounds. Invalid predictions receive an explicit selection penalty. No physical residual loss, synthetic labels, solver-policy changes or production-default promotion is part of this study.

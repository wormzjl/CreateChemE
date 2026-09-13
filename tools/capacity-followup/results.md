# Four-layer hybrid capacity results

The four-layer model does not meet the registered replicated depth-benefit rule.

This compares 156,440 parameters with 89,496 parameters at the same width. Both start with exactly the same full-anchor F0 predictions and receive matched minibatches and additional updates. Solver acceptance criteria, loss, decoder, support policy, data and request budgets remain unchanged.

F0 is the preceding full-anchor model; I is the original Transformer. FIRST means neural initialization with the existing classical fallback; ONLY is a separately timed neural-only request. All success counts below require the unchanged strict audit.

Candidates meeting the further-qualification gates against F0: none. Against I: F0, L4-20260913-s2080. Passing against I alone does not demonstrate improvement over F0. No production model was changed.

The campaign contains 19,740 measured corrected-column requests and 168 TRAIN warmups. The two full-validation blocks repeat the same 405 inputs; they are not 810 independent cases. The 65-case screen and all validation references are historical, with no fresh test set.

## Primary comparison: matched +2,080 updates

| Order seed | L2 FIRST blocks 1/2 | L4 FIRST blocks 1/2 | L2 all-case mean ms | L4 all-case mean ms | Preserves classical union | All gates pass |
| --- | --- | --- | --- | --- | --- | --- |
| 20260913 | 161/161 | 162/162 | 4052.02 | 4189.71 | True | False |
| 20260914 | 161/161 | 160/160 | 4241.35 | 4226.03 | True | False |

A replicated benefit requires strict FIRST improvement for both order seeds in both blocks, preservation of the contemporaneous classical-success union, and no pooled mean FIRST latency regression for either matched pair. Equal update counts do not imply equal compute.

## Complete validation counts and costs

| Pipeline | Block | Classical | ONLY | FIRST | FIRST advisory | FIRST failed | FIRST mean ms | FIRST p95 ms | FIRST CPU mean ms | FIRST allocation mean MiB | Fallback observed |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F0 | 1 | 110 | 134 | 168 | 9 | 228 | 4075.86 | 17080.07 | 3899.58 | 2595.21 | 257 |
| F0 | 2 | 110 | 134 | 168 | 9 | 228 | 3961.46 | 16003.55 | 3809.99 | 2604.07 | 257 |
| L2-20260913-s2080 | 1 | 110 | 120 | 161 | 11 | 233 | 3950.13 | 15948.76 | 3816.82 | 2691.96 | 269 |
| L2-20260913-s2080 | 2 | 110 | 120 | 161 | 9 | 235 | 4153.91 | 17247.42 | 4007.83 | 2667.36 | 271 |
| L4-20260913-s2080 | 1 | 110 | 123 | 162 | 7 | 236 | 4132.40 | 16941.29 | 4005.25 | 2661.17 | 269 |
| L4-20260913-s2080 | 2 | 110 | 123 | 162 | 7 | 236 | 4247.02 | 17230.54 | 4101.89 | 2627.09 | 269 |
| L2-20260914-s2080 | 1 | 110 | 125 | 161 | 9 | 235 | 4257.40 | 17356.23 | 4142.63 | 2630.27 | 266 |
| L2-20260914-s2080 | 2 | 110 | 125 | 161 | 9 | 235 | 4225.29 | 17042.18 | 4053.94 | 2648.53 | 266 |
| L4-20260914-s2080 | 1 | 110 | 129 | 160 | 9 | 236 | 4285.78 | 17655.12 | 4165.43 | 2619.26 | 260 |
| L4-20260914-s2080 | 2 | 110 | 129 | 160 | 9 | 236 | 4166.27 | 17037.93 | 4010.73 | 2632.41 | 260 |
| L4-20260914-s3120 | 1 | 110 | 118 | 159 | 6 | 240 | 4236.30 | 16823.50 | 4082.21 | 2754.36 | 275 |
| L4-20260914-s3120 | 2 | 110 | 118 | 159 | 6 | 240 | 4288.18 | 17061.53 | 4121.95 | 2728.63 | 275 |
| I | 1 | 110 | 116 | 161 | 9 | 235 | 4094.19 | 16506.89 | 3948.26 | 2740.44 | 274 |
| I | 2 | 110 | 116 | 161 | 9 | 235 | 4335.09 | 17258.06 | 4179.36 | 2705.55 | 274 |

All costs include failed and advisory requests. Native anchor construction, inference and failed neural attempts consume the two-second neural allowance; fallback uses the remaining thirty-second request budget. All native runs use ten owned workers, sixteen correction iterations and a 4 GiB heap.

## Paired gains and losses

| Candidate minus reference | Block | ONLY gains/losses | FIRST gains/losses |
| --- | --- | --- | --- |
| depth/20260913/2080 | 1 | 13/10 | 6/5 |
| depth/20260913/2080 | 2 | 13/10 | 6/5 |
| depth/20260914/2080 | 1 | 14/10 | 6/7 |
| depth/20260914/2080 | 2 | 14/10 | 6/7 |
| selected_depth/20260913 | 1 | 13/10 | 6/5 |
| selected_depth/20260913 | 2 | 13/10 | 6/5 |
| selected_depth/20260914 | 1 | 13/20 | 6/8 |
| selected_depth/20260914 | 2 | 13/20 | 6/8 |
| vs_F0/I | 1 | 14/32 | 9/16 |
| vs_F0/I | 2 | 14/32 | 9/16 |
| vs_F0/L2-20260913-s2080 | 1 | 7/21 | 4/11 |
| vs_F0/L2-20260913-s2080 | 2 | 7/21 | 4/11 |
| vs_F0/L2-20260914-s2080 | 1 | 5/14 | 3/10 |
| vs_F0/L2-20260914-s2080 | 2 | 5/14 | 3/10 |
| vs_F0/L4-20260913-s2080 | 1 | 8/19 | 4/10 |
| vs_F0/L4-20260913-s2080 | 2 | 8/19 | 4/10 |
| vs_F0/L4-20260914-s2080 | 1 | 9/14 | 2/10 |
| vs_F0/L4-20260914-s2080 | 2 | 9/14 | 2/10 |
| vs_F0/L4-20260914-s3120 | 1 | 11/27 | 6/15 |
| vs_F0/L4-20260914-s3120 | 2 | 11/27 | 6/15 |
| vs_I/F0 | 1 | 32/14 | 16/9 |
| vs_I/F0 | 2 | 32/14 | 16/9 |
| vs_I/L2-20260913-s2080 | 1 | 25/21 | 11/11 |
| vs_I/L2-20260913-s2080 | 2 | 25/21 | 11/11 |
| vs_I/L2-20260914-s2080 | 1 | 27/18 | 11/11 |
| vs_I/L2-20260914-s2080 | 2 | 27/18 | 11/11 |
| vs_I/L4-20260913-s2080 | 1 | 26/19 | 13/12 |
| vs_I/L4-20260913-s2080 | 2 | 26/19 | 13/12 |
| vs_I/L4-20260914-s2080 | 1 | 30/17 | 12/13 |
| vs_I/L4-20260914-s2080 | 2 | 30/17 | 12/13 |
| vs_I/L4-20260914-s3120 | 1 | 29/27 | 14/16 |
| vs_I/L4-20260914-s3120 | 2 | 29/27 | 14/16 |

These are paired case transitions, not just net counts. Fixed comparisons use equal exposure. Each selected comparison uses the independently screened checkpoint for that arm and seed and can compare different exposure. Case IDs for both-success, reference-only, candidate-only and neither, together with profile discrepancies and timing distributions, are preserved in validation-analysis.json.

## Costs on stable common successes

| Comparison | Strategy | Same cases successful in both blocks | Reference mean ms | Candidate mean ms | Paired mean change ms |
| --- | --- | --- | --- | --- | --- |
| depth/20260913/2080 | ONLY | 110 | 205.09 | 235.90 | 30.80 |
| depth/20260913/2080 | FIRST | 156 | 966.57 | 959.44 | -7.13 |
| depth/20260914/2080 | ONLY | 115 | 199.87 | 210.05 | 10.19 |
| depth/20260914/2080 | FIRST | 154 | 898.34 | 854.99 | -43.35 |
| selected_depth/20260913 | ONLY | 110 | 205.09 | 235.90 | 30.80 |
| selected_depth/20260913 | FIRST | 156 | 966.57 | 959.44 | -7.13 |
| selected_depth/20260914 | ONLY | 105 | 169.12 | 172.51 | 3.39 |
| selected_depth/20260914 | FIRST | 153 | 905.85 | 1096.44 | 190.59 |
| vs_F0/I | ONLY | 102 | 164.41 | 182.80 | 18.39 |
| vs_F0/I | FIRST | 152 | 866.28 | 1081.49 | 215.21 |
| vs_F0/L2-20260913-s2080 | ONLY | 113 | 202.84 | 206.57 | 3.73 |
| vs_F0/L2-20260913-s2080 | FIRST | 157 | 866.73 | 954.42 | 87.69 |
| vs_F0/L2-20260914-s2080 | ONLY | 120 | 182.76 | 185.63 | 2.87 |
| vs_F0/L2-20260914-s2080 | FIRST | 158 | 852.86 | 882.25 | 29.39 |
| vs_F0/L4-20260913-s2080 | ONLY | 115 | 192.71 | 223.42 | 30.71 |
| vs_F0/L4-20260913-s2080 | FIRST | 158 | 857.86 | 944.32 | 86.46 |
| vs_F0/L4-20260914-s2080 | ONLY | 120 | 205.07 | 198.95 | -6.12 |
| vs_F0/L4-20260914-s2080 | FIRST | 158 | 860.63 | 845.39 | -15.25 |
| vs_F0/L4-20260914-s3120 | ONLY | 107 | 155.73 | 181.62 | 25.88 |
| vs_F0/L4-20260914-s3120 | FIRST | 153 | 876.34 | 1103.35 | 227.01 |
| vs_I/F0 | ONLY | 102 | 182.80 | 164.41 | -18.39 |
| vs_I/F0 | FIRST | 152 | 1081.49 | 866.28 | -215.21 |
| vs_I/L2-20260913-s2080 | ONLY | 95 | 187.81 | 172.96 | -14.85 |
| vs_I/L2-20260913-s2080 | FIRST | 150 | 1094.62 | 974.14 | -120.48 |
| vs_I/L2-20260914-s2080 | ONLY | 98 | 199.80 | 178.29 | -21.51 |
| vs_I/L2-20260914-s2080 | FIRST | 150 | 1094.62 | 906.46 | -188.16 |
| vs_I/L4-20260913-s2080 | ONLY | 97 | 175.71 | 202.37 | 26.65 |
| vs_I/L4-20260913-s2080 | FIRST | 149 | 1093.69 | 972.52 | -121.17 |
| vs_I/L4-20260914-s2080 | ONLY | 99 | 196.38 | 184.63 | -11.75 |
| vs_I/L4-20260914-s2080 | FIRST | 148 | 1103.97 | 870.88 | -233.09 |
| vs_I/L4-20260914-s3120 | ONLY | 89 | 152.69 | 160.04 | 7.35 |
| vs_I/L4-20260914-s3120 | FIRST | 145 | 1120.86 | 1138.36 | 17.51 |

These populations succeed under both pipelines in both blocks. Their cost is reported separately because a lower all-case mean can result from earlier failures. Repeated timings are descriptive, not independent samples for confidence claims.

## Panel and remaining validation cases

| Pipeline | Block | Population | Cases | Classical | ONLY | FIRST | FIRST mean ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F0 | 1 | screening | 65 | 31 | 33 | 42 | 2886.55 |
| F0 | 1 | remaining | 340 | 79 | 101 | 126 | 4303.23 |
| F0 | 2 | screening | 65 | 31 | 33 | 42 | 2812.64 |
| F0 | 2 | remaining | 340 | 79 | 101 | 126 | 4181.08 |
| L2-20260913-s2080 | 1 | screening | 65 | 31 | 35 | 42 | 2788.38 |
| L2-20260913-s2080 | 1 | remaining | 340 | 79 | 85 | 119 | 4172.23 |
| L2-20260913-s2080 | 2 | screening | 65 | 31 | 35 | 42 | 2907.29 |
| L2-20260913-s2080 | 2 | remaining | 340 | 79 | 85 | 119 | 4392.24 |
| L4-20260913-s2080 | 1 | screening | 65 | 31 | 33 | 42 | 2875.28 |
| L4-20260913-s2080 | 1 | remaining | 340 | 79 | 90 | 120 | 4372.73 |
| L4-20260913-s2080 | 2 | screening | 65 | 31 | 33 | 42 | 2960.19 |
| L4-20260913-s2080 | 2 | remaining | 340 | 79 | 90 | 120 | 4493.03 |
| L2-20260914-s2080 | 1 | screening | 65 | 31 | 34 | 43 | 2962.80 |
| L2-20260914-s2080 | 1 | remaining | 340 | 79 | 91 | 118 | 4504.90 |
| L2-20260914-s2080 | 2 | screening | 65 | 31 | 34 | 43 | 2935.67 |
| L2-20260914-s2080 | 2 | remaining | 340 | 79 | 91 | 118 | 4471.84 |
| L4-20260914-s2080 | 1 | screening | 65 | 31 | 34 | 42 | 2950.41 |
| L4-20260914-s2080 | 1 | remaining | 340 | 79 | 95 | 118 | 4541.07 |
| L4-20260914-s2080 | 2 | screening | 65 | 31 | 34 | 42 | 2878.62 |
| L4-20260914-s2080 | 2 | remaining | 340 | 79 | 95 | 118 | 4412.44 |
| L4-20260914-s3120 | 1 | screening | 65 | 31 | 31 | 43 | 3061.04 |
| L4-20260914-s3120 | 1 | remaining | 340 | 79 | 87 | 116 | 4460.98 |
| L4-20260914-s3120 | 2 | screening | 65 | 31 | 31 | 43 | 3086.06 |
| L4-20260914-s3120 | 2 | remaining | 340 | 79 | 87 | 116 | 4518.00 |
| I | 1 | screening | 65 | 31 | 32 | 46 | 2625.58 |
| I | 1 | remaining | 340 | 79 | 84 | 115 | 4374.96 |
| I | 2 | screening | 65 | 31 | 32 | 46 | 2768.61 |
| I | 2 | remaining | 340 | 79 | 84 | 115 | 4634.57 |

The panel was selected from historical outcomes. The remaining 340 inputs are also historical validation, not a new holdout.

## Training and additional-block activity

| Fit | Additional updates | Train profile score | Validation profile score |
| --- | --- | --- | --- |
| L2-20260913 | 0 | 0.267454 | 1.077666 |
| L2-20260913 | 1040 | 0.258901 | 1.081280 |
| L2-20260913 | 2080 | 0.254266 | 1.076450 |
| L2-20260913 | 3120 | 0.246124 | 1.080049 |
| L4-20260913 | 0 | 0.267454 | 1.077666 |
| L4-20260913 | 1040 | 0.251944 | 1.073426 |
| L4-20260913 | 2080 | 0.244026 | 1.064117 |
| L4-20260913 | 3120 | 0.229811 | 1.063158 |
| L2-20260914 | 0 | 0.267454 | 1.077666 |
| L2-20260914 | 1040 | 0.258440 | 1.078758 |
| L2-20260914 | 2080 | 0.252820 | 1.085777 |
| L2-20260914 | 3120 | 0.243780 | 1.082741 |
| L4-20260914 | 0 | 0.267454 | 1.077666 |
| L4-20260914 | 1040 | 0.253931 | 1.071865 |
| L4-20260914 | 2080 | 0.240569 | 1.068295 |
| L4-20260914 | 3120 | 0.229195 | 1.062932 |

Both columns use the same per-column profile score; lower is better. They cover N804 and the same 168 certified validation references. Training loss is not treated as interchangeable with this score.

| Fit | Parameters | Training seconds including diagnostics | Peak allocated GPU MiB |
| --- | --- | --- | --- |
| L2-20260913 | 89496 | 25.34 | 219.86 |
| L4-20260913 | 156440 | 38.57 | 235.31 |
| L2-20260914 | 89496 | 24.91 | 219.86 |
| L4-20260914 | 156440 | 35.29 | 235.31 |


| Fit | Update | Added block index | Attention W norm | Attention b norm | FF W norm | FF b norm | Hidden change L2 | Hidden max change |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| L4-20260913 | 1040 | 2 | 0.147518 | 0.002541 | 0.318788 | 0.002363 | 8.805913 | 0.247279 |
| L4-20260913 | 1040 | 3 | 0.147971 | 0.002284 | 0.318460 | 0.002308 | 8.751012 | 0.243745 |
| L4-20260913 | 2080 | 2 | 0.267932 | 0.005001 | 0.543402 | 0.004290 | 17.084330 | 0.441343 |
| L4-20260913 | 2080 | 3 | 0.269454 | 0.004131 | 0.543140 | 0.004702 | 17.301367 | 0.458933 |
| L4-20260913 | 3120 | 2 | 0.366029 | 0.007260 | 0.714228 | 0.005982 | 23.264250 | 0.597343 |
| L4-20260913 | 3120 | 3 | 0.369117 | 0.005971 | 0.715625 | 0.007638 | 24.087908 | 0.628137 |
| L4-20260914 | 1040 | 2 | 0.151792 | 0.002685 | 0.321909 | 0.002497 | 8.786006 | 0.254712 |
| L4-20260914 | 1040 | 3 | 0.152310 | 0.002404 | 0.321434 | 0.002398 | 8.734191 | 0.261449 |
| L4-20260914 | 2080 | 2 | 0.269506 | 0.005029 | 0.541927 | 0.004338 | 16.747623 | 0.439390 |
| L4-20260914 | 2080 | 3 | 0.271300 | 0.004190 | 0.541784 | 0.004717 | 16.895479 | 0.456722 |
| L4-20260914 | 3120 | 2 | 0.372009 | 0.007365 | 0.718546 | 0.006009 | 23.419676 | 0.627540 |
| L4-20260914 | 3120 | 3 | 0.375237 | 0.006005 | 0.720293 | 0.007587 | 24.052277 | 0.629825 |

The new projection weights and biases start at zero. Activity is measured on the fixed first 32 TRAIN rows after each saved endpoint. The two added blocks copy the same trained basis initially; they are not independently random new layers. Fresh optimizer state, dropout zero, deterministic float32 CUDA and TF32 disabled apply to both arms.

## Frozen checkpoint selection

| Arm | Seed | Selected endpoint | Preserves screen classical union |
| --- | --- | --- | --- |
| L2 | 20260913 | L2-20260913-s2080 | True |
| L2 | 20260914 | L2-20260914-s2080 | True |
| L4 | 20260913 | L4-20260913-s2080 | True |
| L4 | 20260914 | L4-20260914-s3120 | True |


| Screen pipeline | ONLY / 65 | FIRST / 65 | FIRST mean ms |
| --- | --- | --- | --- |
| F0 | 33 | 42 | 2993.91 |
| I | 32 | 46 | 2775.77 |
| L2-20260913-s1040 | 32 | 41 | 3046.51 |
| L2-20260913-s2080 | 35 | 42 | 3000.62 |
| L2-20260913-s3120 | 35 | 41 | 3030.15 |
| L2-20260914-s1040 | 32 | 42 | 3100.98 |
| L2-20260914-s2080 | 34 | 43 | 2957.80 |
| L2-20260914-s3120 | 32 | 40 | 3038.42 |
| L4-20260913-s1040 | 29 | 41 | 3112.24 |
| L4-20260913-s2080 | 33 | 42 | 2948.14 |
| L4-20260913-s3120 | 31 | 42 | 3166.91 |
| L4-20260914-s1040 | 31 | 41 | 3133.48 |
| L4-20260914-s2080 | 34 | 42 | 2931.94 |
| L4-20260914-s3120 | 31 | 43 | 3209.31 |

## Failure, profile and runtime evidence

| Pipeline | Block | FIRST statuses | Observed stop phrases | Terminal owners | Available reference profiles | Above-floor omissions | Positive-phase omissions |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F0 | 1 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 177, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 75, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 132} | {"iteration_limit": 111, "neural_time_limit": 70, "no_admissible_step": 51, "unknown": 79, "whole_request_deadline": 8} | {"classical_backup": 257, "neural": 140, "unknown": 8} | 168 | 819 | 54 |
| F0 | 2 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 177, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 75, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 132} | {"iteration_limit": 111, "neural_time_limit": 69, "no_admissible_step": 51, "unknown": 79, "whole_request_deadline": 8} | {"classical_backup": 257, "neural": 140, "unknown": 8} | 168 | 819 | 54 |
| L2-20260913-s2080 | 1 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 172, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 77, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 135} | {"iteration_limit": 115, "neural_time_limit": 60, "no_admissible_step": 54, "unknown": 85, "whole_request_deadline": 8} | {"classical_backup": 269, "neural": 128, "unknown": 8} | 168 | 930 | 69 |
| L2-20260913-s2080 | 2 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 170, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 78, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 136} | {"iteration_limit": 116, "neural_time_limit": 76, "no_admissible_step": 54, "unknown": 79, "whole_request_deadline": 8} | {"classical_backup": 271, "neural": 126, "unknown": 8} | 168 | 930 | 69 |
| L4-20260913-s2080 | 1 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 169, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 79, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 136} | {"iteration_limit": 115, "neural_time_limit": 73, "no_admissible_step": 55, "unknown": 84, "whole_request_deadline": 8} | {"classical_backup": 269, "neural": 128, "unknown": 8} | 168 | 899 | 73 |
| L4-20260913-s2080 | 2 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 169, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 79, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 136} | {"iteration_limit": 115, "neural_time_limit": 78, "no_admissible_step": 55, "unknown": 82, "whole_request_deadline": 8} | {"classical_backup": 269, "neural": 128, "unknown": 8} | 168 | 899 | 73 |
| L2-20260914-s2080 | 1 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 170, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 76, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 138} | {"iteration_limit": 117, "neural_time_limit": 72, "no_admissible_step": 55, "unknown": 79, "whole_request_deadline": 8} | {"classical_backup": 266, "neural": 131, "unknown": 8} | 168 | 878 | 66 |
| L2-20260914-s2080 | 2 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 170, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 76, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 138} | {"iteration_limit": 117, "neural_time_limit": 69, "no_admissible_step": 55, "unknown": 80, "whole_request_deadline": 8} | {"classical_backup": 266, "neural": 131, "unknown": 8} | 168 | 878 | 66 |
| L4-20260914-s2080 | 1 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 169, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 78, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 137} | {"iteration_limit": 116, "neural_time_limit": 77, "no_admissible_step": 53, "unknown": 78, "whole_request_deadline": 8} | {"classical_backup": 260, "neural": 137, "unknown": 8} | 168 | 837 | 75 |
| L4-20260914-s2080 | 2 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 169, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 78, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 137} | {"iteration_limit": 116, "neural_time_limit": 74, "no_admissible_step": 53, "unknown": 80, "whole_request_deadline": 8} | {"classical_backup": 260, "neural": 137, "unknown": 8} | 168 | 837 | 75 |
| L4-20260914-s3120 | 1 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 165, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 79, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 140} | {"iteration_limit": 120, "neural_time_limit": 80, "no_admissible_step": 57, "unknown": 76, "whole_request_deadline": 8} | {"classical_backup": 275, "neural": 122, "unknown": 8} | 168 | 873 | 89 |
| L4-20260914-s3120 | 2 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 165, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 79, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 140} | {"iteration_limit": 120, "neural_time_limit": 85, "no_admissible_step": 57, "unknown": 76, "whole_request_deadline": 8} | {"classical_backup": 275, "neural": 122, "unknown": 8} | 168 | 873 | 89 |
| I | 1 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 170, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 79, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 135} | {"iteration_limit": 115, "neural_time_limit": 72, "no_admissible_step": 54, "unknown": 82, "whole_request_deadline": 8} | {"classical_backup": 274, "neural": 123, "unknown": 8} | 168 | 1146 | 60 |
| I | 2 | {"ACCEPTANCE_AUDIT_FAILURE": 4, "ACCEPTED": 170, "DEADLINE_EXCEEDED": 8, "INFEASIBLE_SPECIFICATION": 79, "LINEAR_SOLVE_FAILURE": 9, "NONCONVERGENCE": 135} | {"iteration_limit": 115, "neural_time_limit": 82, "no_admissible_step": 54, "unknown": 79, "whole_request_deadline": 8} | {"classical_backup": 274, "neural": 123, "unknown": 8} | 168 | 1146 | 60 |


| Pipeline | Block | Model load ms | Heap used at end MiB | Memory-pool peaks |
| --- | --- | --- | --- | --- |
| F0 | 1 | 102.08 | 2609.04 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3645952, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8914600, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13780096, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 907128, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2514485248, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1962023048, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 68874400, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11738112, "type": "NON_HEAP"}] |
| F0 | 2 | 89.69 | 2139.73 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3639040, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8897776, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13827200, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 904848, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2543845376, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1967091696, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 67944280, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11632256, "type": "NON_HEAP"}] |
| L2-20260913-s2080 | 1 | 94.61 | 1573.60 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3640064, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8907376, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13816832, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 905512, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2524971008, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1947604896, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 73400320, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11344896, "type": "NON_HEAP"}] |
| L2-20260913-s2080 | 2 | 91.16 | 1172.50 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3637376, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8898984, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13589248, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 910480, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2529165312, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1954992672, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 71069120, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11533952, "type": "NON_HEAP"}] |
| L4-20260913-s2080 | 1 | 121.54 | 1266.03 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3638016, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8890520, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 14052608, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 902864, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2573205504, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1945098552, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 71190712, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11208320, "type": "NON_HEAP"}] |
| L4-20260913-s2080 | 2 | 118.90 | 2793.59 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3633536, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8901824, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13790976, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 902896, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2573205504, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1960702160, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 72215904, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11438464, "type": "NON_HEAP"}] |
| L2-20260914-s2080 | 1 | 82.93 | 2442.35 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3635456, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8893752, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13846272, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 899024, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2535456768, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1977023808, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 69206016, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11354752, "type": "NON_HEAP"}] |
| L2-20260914-s2080 | 2 | 94.00 | 2098.71 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3636352, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8909944, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13662464, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 906568, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2543845376, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1959753648, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 69206016, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11474688, "type": "NON_HEAP"}] |
| L4-20260914-s2080 | 1 | 124.41 | 2283.86 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3641728, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8912032, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13824256, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 906064, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2531262464, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1947738808, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 72205696, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11514368, "type": "NON_HEAP"}] |
| L4-20260914-s2080 | 2 | 132.35 | 1798.64 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3669376, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8912800, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13916544, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 906576, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2527068160, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1991420080, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 71121336, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11940096, "type": "NON_HEAP"}] |
| L4-20260914-s3120 | 1 | 120.36 | 2634.56 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3639168, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8911128, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 14025088, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 905512, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2539651072, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1975676440, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 67863144, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11514624, "type": "NON_HEAP"}] |
| L4-20260914-s3120 | 2 | 126.10 | 2898.19 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3648384, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8893816, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13800832, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 902896, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2571108352, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1947424192, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 68043328, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11366144, "type": "NON_HEAP"}] |
| I | 1 | 102.26 | 1424.47 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3621248, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8906552, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13647232, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 897728, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2533359616, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1968979568, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 70059680, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11122176, "type": "NON_HEAP"}] |
| I | 2 | 103.75 | 2389.82 | [{"name": "CodeHeap 'non-nmethods'", "peakUsedBytes": 3658752, "type": "NON_HEAP"}, {"name": "Metaspace", "peakUsedBytes": 8915560, "type": "NON_HEAP"}, {"name": "CodeHeap 'profiled nmethods'", "peakUsedBytes": 13980800, "type": "NON_HEAP"}, {"name": "Compressed Class Space", "peakUsedBytes": 900520, "type": "NON_HEAP"}, {"name": "G1 Eden Space", "peakUsedBytes": 2550136832, "type": "HEAP"}, {"name": "G1 Old Gen", "peakUsedBytes": 1948043008, "type": "HEAP"}, {"name": "G1 Survivor Space", "peakUsedBytes": 73400320, "type": "HEAP"}, {"name": "CodeHeap 'non-profiled nmethods'", "peakUsedBytes": 11131904, "type": "NON_HEAP"}] |

Stop-phrase counts can overlap. Absent evidence remains unknown. FIRST terminal ownership is read from its own request; separately timed ONLY is not used to infer FIRST’s internal trajectory. Projected MESH maxima and terminal audit ratios depend on state and support and are not a single optimization objective.

Native seed profiles, reference support omissions, temperature/traffic/component errors, initial projected residual distributions, per-mode CPU/allocation distributions, case service and queue times, and stage/steam/side-draw/heat-loop breakdowns are in the analysis and case/profile evidence files. Reference discrepancies do not prove that only one root or support pattern is admissible.

## Verification and preservation

Before fitting, all 905 TRAIN cases had exactly matching Python initial predictions, all 804 optimization cases matched in train and eval modes, and independent Java F0/L2/L4 predictions matched completely on all 804. Eight malformed model manifests were rejected. Eleven initialization/schedule tests passed. All twelve exports passed the original native numerical tolerances, exact masks, cancellation and forty shared predictions across ten workers.

The native core was rebuilt from 113 archive-matched sources with only Gson on the compiler classpath. Recorded loaded-class fingerprints match the isolated runtime. Existing solver sources, prior model artifacts and the main checkout remain outside the experiment changes.

Preflight failures are retained: sandbox access to the Gson cache failed; an initial full-source rebuild included an unused Minecraft-dependent probe and its error display hit an encoding failure. The corrected dependency closure and UTF-8 logging were verified before registration. These were preflight failures, not discarded training or native-evaluation results.

The results sealer independently checks checkpoint/export tensors, optimizer endpoints, shuffled per-ID exposure, runtime and archive bindings, selection and validation locks, then exactly replays case/profile analyses and this report. Registration and all six predecessor archives are checked. No solver requests or new fits are created by analysis or sealing.

Artifacts: training-plan.json; fits/*/report.json and full checkpoints; models.json and native parity proofs; selection.json; validation-plan.json; screen/ and validation/ journals; screen-analysis.json and validation-analysis.json; per-case and per-profile JSONL evidence; verification.json. The registration and results ZIP files are under .neural-cache/capacity-followup-v1.

The historical quarantined target remains excluded from new training but its previous influence is inherited through F0. This experiment does not claim unlearning, independent test generalization, or that more width/depth would necessarily help.

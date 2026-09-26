# Trained hybrid comparison

N contains 805 unchanged certified training columns. The verified original-condition sweep appended 101 new strict TRAIN labels, giving N+1 906 columns. All historical nontraining records and the original 168 certified validation references were preserved. Newly acquired validation and test successes were withheld.

Six paired fits use the same 89,496-parameter native-baseline/residual Transformer, N-derived normalization and 4,160 optimizer updates. The branch classifier selects the native MATERIAL_CLOSED baseline in training and inference. Continuous coordinates are residual outputs; water, wet and presence coordinates are absolute. The existing factorized decoder and exact one-pass material wrapper feed the unchanged corrector/audit.

| Dataset | Seed | Selected update | Profile selection score | Training seconds |
|---|---:|---:|---:|---:|
| N | 20260910 | 3562 | 1.043704 | 26.28 |
| Nplus1 | 20260910 | 3510 | 1.037465 | 28.78 |
| N | 20260911 | 4134 | 1.020980 | 37.09 |
| Nplus1 | 20260911 | 3120 | 1.022649 | 39.00 |
| N | 20260912 | 3224 | 1.147201 | 30.73 |
| Nplus1 | 20260912 | 3458 | 1.065892 | 27.81 |

These scores describe predictions on the same 168 certified validation references. They are not success rates or measured native convergence. N versus N+1 holds initial weights, normalization and update count fixed; comparison to the incumbent additionally changes representation and training history.

## Complete native validation

Eight fixed pipelines were evaluated on all 405 original validation inputs, twice, with reversed pipeline order. Each pipeline receives contemporaneous CURRENT_ONLY, LNN_ONLY and LNN_FIRST requests. All runs use ten owned workers, Java 21, a 4 GiB heap, a 30-second request deadline, a 2-second neural budget and 16 correction iterations. Diagnostics and identical TRAIN warmup are outside reported request timing; actual baseline construction, prediction, decoding and completion are inside the neural budget.

| Pipeline | FIRST strict, blocks 1 / 2 | ONLY strict, blocks 1 / 2 | FIRST mean ms | FIRST sample SD ms |
|---|---:|---:|---:|---:|
| incumbent | 161 / 161 | 116 / 116 | 4114.39 | 5864.24 |
| N-20260910 | 157 / 158 | 116 / 116 | 4220.05 | 6076.14 |
| Nplus1-20260910 | 155 / 155 | 109 / 109 | 4145.88 | 5881.09 |
| incumbent-wrapper | 161 / 161 | 117 / 117 | 4023.13 | 5779.16 |
| Nplus1-20260911 | 153 / 153 | 108 / 108 | 4275.81 | 5944.69 |
| N-20260911 | 159 / 159 | 103 / 103 | 4243.82 | 5914.15 |
| N-20260912 | 158 / 158 | 106 / 106 | 4342.13 | 6002.25 |
| Nplus1-20260912 | 145 / 145 | 87 / 87 | 4377.99 | 5888.44 |

The frozen representatives are **N-20260911** and **Nplus1-20260910**. Selection maximizes the minimum strict FIRST count across the two blocks, then minimizes pooled all-case latency, then uses the seed as tie-breaker.

A replacement recommendation additionally requires qualification improvement in both validation blocks, preservation of the union of contemporaneous classical strict successes in both blocks, and no pooled mean-latency regression. The complete decision and case-level gains/losses are retained in selection.json.

## Prospective 252-input test

The input-only test was frozen before fitting: four columns for every stage count 2–64, with two steam-on and two steam-off columns per stage count, excluding 14,086 historical inputs and complete prior candidate pools. Selected artifacts and the validation decision were frozen before test execution.

| Pipeline | Mode | Strict, blocks 1 / 2 | Advisory, blocks 1 / 2 | Failed, blocks 1 / 2 | Mean ms | Sample SD ms | P95 ms |
|---|---|---:|---:|---:|---:|---:|---:|
| incumbent | current | 58 / 58 | 3 / 3 | 191 / 191 | 4411.37 | 5582.97 | 13611.21 |
| incumbent | neural | 68 / 68 | 4 / 4 | 180 / 180 | 962.22 | 777.67 | 2007.31 |
| incumbent | neuralFirst | 92 / 92 | 5 / 5 | 155 / 155 | 4662.02 | 5846.18 | 14363.14 |
| Nplus1-20260910 | current | 58 / 58 | 3 / 3 | 191 / 191 | 4364.82 | 5553.87 | 13499.89 |
| Nplus1-20260910 | neural | 62 / 62 | 4 / 4 | 186 / 186 | 921.07 | 784.58 | 2004.21 |
| Nplus1-20260910 | neuralFirst | 89 / 89 | 5 / 5 | 158 / 158 | 4736.75 | 5801.96 | 14238.13 |
| incumbent-wrapper | current | 58 / 58 | 3 / 3 | 191 / 191 | 4393.30 | 5568.74 | 13507.63 |
| incumbent-wrapper | neural | 69 / 69 | 4 / 4 | 179 / 179 | 958.97 | 780.12 | 2013.60 |
| incumbent-wrapper | neuralFirst | 93 / 93 | 5 / 5 | 154 / 154 | 4638.05 | 5836.69 | 14167.26 |
| N-20260911 | current | 58 / 58 | 3 / 3 | 191 / 191 | 4599.90 | 5743.08 | 13979.91 |
| N-20260911 | neural | 63 / 63 | 4 / 4 | 185 / 185 | 894.00 | 790.27 | 2008.15 |
| N-20260911 | neuralFirst | 91 / 91 | 5 / 5 | 156 / 156 | 4926.12 | 6046.03 | 14726.49 |

Both blocks contain the same 252 independent operating inputs; 504 request measurements are not 504 independent test columns. All-case latency includes failed requests and classical fallback. Sample SD describes request/case variability, not a confidence interval or proof of a causal speedup.

| Pipeline | FIRST gains / losses vs incumbent, block 1 | FIRST gains / losses vs incumbent, block 2 | Mean paired delta ms, block 1 / 2 |
|---|---:|---:|---:|
| incumbent | 0 / 0 | 0 / 0 | 0.00 / 0.00 |
| Nplus1-20260910 | 10 / 13 | 10 / 13 | 49.43 / 100.03 |
| incumbent-wrapper | 1 / 0 | 1 / 0 | -24.57 / -23.37 |
| N-20260911 | 12 / 13 | 12 / 13 | 389.06 / 139.14 |

## Interpretation and evidence limits

The summary JSON retains every block, strategy, case pairing, stage/steam/draw/pumparound stratum, raw-prediction availability, preparation status/cost, native residual diagnostic, fallback observation and classical-control disagreement. Preparation and anchor diagnostic timings are separately labelled and must not be substituted for full request latency. Cases with declined preparation or unavailable predictions remain in every denominator.

The MATERIAL_CLOSED anchor is a numerical guess. Capped seed withdrawals do not certify side-draw closure, and dry hydrocarbon initialization does not close steam/water or energy equations. Material completion is limited to dry inputs without side draws; lower material residual alone is not MESH qualification. All claimed strict successes pass the same native correction and audit.

N and N+1 contain no wet-qualified profiles. The 101 added profiles are all TWO_PHASE condensers; 53 have steam feeds and 48 do not. A steam-fed, dry-equilibrium result does not establish wet-tray generalization. Rare-branch and wet limits remain explicit.

No test result changed training, checkpoint/seed selection, decoder thresholds, budgets or runtime defaults. Previous sealed studies remain unchanged.

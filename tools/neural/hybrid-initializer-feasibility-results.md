# Frozen Transformer material-completion feasibility

Twenty fixed dry/no-side-draw TRAIN inputs; ten workers; one LNN_FIRST request per treatment.
Each request retains the 2-second neural / 30-second request budget and 16 correction iterations.
All cases contribute to timing. These are TRAIN observations, not held-out performance.

| Treatment | Strict / 20 | Advisory | Failed | Mean elapsed, ms | Sample SD, ms | Fallback observed |
|---|---:|---:|---:|---:|---:|---:|
| raw | 17 | 0 | 3 | 2681.51 | 4909.86 | 10 |
| materialCompletion | 19 | 0 | 1 | 2177.57 | 4651.06 | 7 |

Paired strict gains: 2; losses: 0.
Mean paired elapsed change: -503.95 ms (sample SD 678.11 ms).

Preparation statuses: `{"DECLINED": 12, "PREPARED": 8}`.

| Diagnostic | Raw mean | Prepared mean | Raw / prepared count |
|---|---:|---:|---:|
| maximumMaterialDefectOverInputComponentScale | 4.08939 | 2.81266 | 20 / 20 |

Native family residuals, unavailable reasons, support changes and paired IDs are retained in the summary.
Native scaled maxima use each state's support and row scales; they are descriptive, not a common acceptance objective.

| Case | Stages | Pumparounds | Raw strict | Completed strict | Raw ms | Completed ms |
|---|---:|---:|---|---|---:|---:|
| gd-s02-w0-p0-d0-r00 | 2 | 0 | True | True | 739.53 | 169.89 |
| gd-s04-w0-p1-d0-r00 | 4 | 1 | True | True | 46.94 | 592.55 |
| gd-s05-w0-p3-d0-r00 | 5 | 3 | True | True | 1394.74 | 524.82 |
| gd-s08-w0-p4-d0-r00 | 8 | 4 | False | True | 1171.52 | 66.10 |
| gd-s12-w0-p0-d0-r00 | 12 | 0 | True | True | 556.57 | 583.43 |
| gd-s13-w0-p3-d0-r01 | 13 | 3 | True | True | 670.64 | 109.42 |
| gd-s15-w0-p4-d0-r00 | 15 | 4 | False | True | 1709.53 | 36.13 |
| g3fresh-s19-w0-p1-d0-r00 | 19 | 1 | True | True | 604.33 | 215.17 |
| gd-s22-w0-p0-d0-r00 | 22 | 0 | True | True | 617.85 | 162.61 |
| gd-s25-w0-p0-d0-r00 | 25 | 0 | True | True | 2447.65 | 563.96 |
| g3fresh-s27-w0-p4-d0-r00 | 27 | 4 | True | True | 1147.27 | 589.84 |
| g3fresh-s30-w0-p1-d0-r00 | 30 | 1 | True | True | 248.85 | 259.70 |
| gd-s33-w0-p1-d0-r00 | 33 | 1 | True | True | 249.73 | 257.28 |
| gd-s37-w0-p0-d0-r00 | 37 | 0 | True | True | 442.48 | 374.24 |
| gd-s41-w0-p2-d0-r00 | 41 | 2 | True | True | 274.59 | 354.22 |
| gd-s44-w0-p1-d0-r00 | 44 | 1 | True | True | 2306.07 | 2728.59 |
| gd-s50-w0-p0-d0-r00 | 50 | 0 | True | True | 10727.05 | 9516.91 |
| gd-s54-w0-p0-d0-r00 | 54 | 0 | True | True | 2631.66 | 2756.45 |
| gd-s58-w0-p3-d0-r00 | 58 | 3 | False | False | 20875.75 | 19509.11 |
| gd-s64-w0-p0-d0-r00 | 64 | 0 | True | True | 4767.49 | 4180.87 |

| run | window | process CPU, cores | allocation MiB/s | GC count | tick ms p50 / p95 | engine ms p50 | solves in window | budget / numerical holds / deferred retries in window | HELD warnings in the whole log | kinds and statuses at the window end |
|---|---|---|---|---|---|---|---|---|---|---|
| srv-fill100-wp5-r01 | 60+60 s | 12.24 | 9106.6 | 620 | 0.377 / 1.411 | 0.1204 | 427 | n/a / n/a / n/a | 743 | {AWAKE=100} {FULL=30, HELD=58, SOLVING=12} |
| srv-fill100-wp5-r02 | 60+60 s | 12.30 | 9587.1 | 611 | 0.344 / 1.046 | 0.0696 | 437 | n/a / n/a / n/a | 754 | {AWAKE=100} {FULL=32, HELD=56, SOLVING=12} |
| srv-fill100-f1-r01 | 60+60 s | 12.17 | 10020.2 | 704 | 0.329 / 0.861 | 0.0298 | 458 | 252 / 100 / n/a | 712 | {AWAKE=100} {FULL=6, HELD=82, SOLVING=12} |
| srv-fill100-f1-r02 | 60+60 s | 0.35 | 32.9 | 2 | 0.137 / 0.413 | 0.0091 | 1400 | n/a / n/a / n/a | 0 | {AWAKE=100} {FULL=100} |
| srv-fill100-wp5-soak-r01 | 60+300 s | 6.87 | 4822.6 | 1372 | 0.251 / 0.561 | 0.0653 | 7557 | n/a / n/a / n/a | 2479 | {AWAKE=33, STEADY=67} {HELD=33, STEADY=67} |
| srv-fill100-f1-soak-r01 | 60+300 s | 0.32 | 199.2 | 77 | 0.095 / 0.267 | 0.0052 | 6300 | n/a / n/a / n/a | 1 | {STEADY=100} {STEADY=100} |
| srv-probe-base-r01 | 60+60 s | 2.03 | 1815.7 | 138 | 0.185 / 0.548 | 0.0130 | 133 | n/a / n/a / n/a | 146 | {AWAKE=12, REST=17, STEADY=2} {FULL=4, HELD=4, RESTING=17, SOLVING=4, STEADY=2} |
| srv-probe-f1-r01 | 60+60 s | 0.58 | 196.8 | 17 | 0.145 / 0.450 | 0.0106 | 181 | 2 / n/a / n/a | 42 | {AWAKE=12, REST=17, STEADY=2} {FULL=12, RESTING=17, STEADY=2} |
| srv-probe2-base-r01 | 60+60 s | 7.59 | 7459.4 | 532 | 0.303 / 1.029 | 0.1279 | 1241 | n/a / n/a / n/a | 634 | {AWAKE=44, REST=17, STEADY=2} {FULL=30, HELD=14, RESTING=17, STEADY=2} |
| srv-probe2-f1-r01 | 60+60 s | 0.89 | 478.8 | 38 | 0.165 / 0.541 | 0.0154 | 657 | n/a / n/a / n/a | 55 | {AWAKE=14, REST=17, STEADY=32} {FULL=14, RESTING=17, STEADY=32} |
| srv-probe3-base-r01 | 60+60 s | 2.62 | 2964.5 | 175 | 0.183 / 0.539 | 0.0113 | 248 | n/a / n/a / n/a | 408 | {AWAKE=24} {HELD=24} |
| srv-probe3-f1-r01 | 60+60 s | 2.23 | 2383.4 | 176 | 0.157 / 0.774 | 0.0103 | 184 | 8 / 160 / 88 | 312 | {AWAKE=24} {HELD=24} |
| srv-probe4-base-r01 | 60+60 s | 2.34 | 2216.0 | 131 | 0.212 / 0.611 | 0.0147 | 360 | n/a / n/a / n/a | 262 | {AWAKE=24} {FULL=13, HELD=10, SOLVING=1} |
| srv-probe4-f1-r01 | 60+60 s | 0.24 | 11.1 | 0 | 0.132 / 0.419 | 0.0093 | 316 | n/a / n/a / n/a | 34 | {AWAKE=24} {FULL=24} |

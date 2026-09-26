# Native checkpoint selection results

Selected seed **20260911**; reference **20260911**. No training was performed. All campaigns used ten workers, 2-second neural and 30-second request budgets, with 16 iterations per correction pass.

| Seed | FIRST qualified / 405 | FIRST ms, mean 卤 sample SD | More qualified | Covers classical union | No mean-latency regression | Eligible |
|---|---:|---:|---|---|---|---|
| 20260910 | 154 | 4883 卤 6.41e+03 | False | True | False | False |
| 20260911 | 161 | 4071 卤 5.8e+03 | False | True | True | False |
| 20260912 | 154 | 4518 卤 6.07e+03 | False | True | False | False |

No alternative passed all predeclared gates; the reference was retained. This does not establish that the reference covers the union of classical successes.

The rule protects classical qualifications; it can permit loss of individual reference-only neural successes. Exact gains/losses and classical-control disagreements are retained in the summary.

| Population | Seed | Strategy | Strict qualified | Advisory only | Failed | Elapsed ms, mean 卤 sample SD | CPU ms, mean 卤 sample SD |
|---|---|---|---:|---:|---:|---:|---:|
| validation / 405 | 20260910 | current | 110 | 5 | 290 | 4543 卤 6.22e+03 | 4061 卤 5.63e+03 |
| validation / 405 | 20260910 | neural | 99 | 6 | 300 | 969.3 卤 776 | 870 卤 701 |
| validation / 405 | 20260910 | neuralFirst | 154 | 10 | 241 | 4883 卤 6.41e+03 | 4394 卤 5.84e+03 |
| validation / 405 | 20260911 | current | 110 | 5 | 290 | 3873 卤 5.62e+03 | 3718 卤 5.41e+03 |
| validation / 405 | 20260911 | neural | 116 | 7 | 282 | 896.8 卤 767 | 858.3 卤 735 |
| validation / 405 | 20260911 | neuralFirst | 161 | 9 | 235 | 4071 卤 5.8e+03 | 3914 卤 5.6e+03 |
| validation / 405 | 20260912 | current | 110 | 5 | 290 | 4147 卤 5.89e+03 | 3983 卤 5.68e+03 |
| validation / 405 | 20260912 | neural | 103 | 3 | 299 | 952 卤 779 | 912.7 卤 748 |
| validation / 405 | 20260912 | neuralFirst | 154 | 8 | 243 | 4518 卤 6.07e+03 | 4333 卤 5.84e+03 |
| test / 252 | 20260911 | current | 51 | 4 | 197 | 5153 卤 6.53e+03 | 4947 卤 6.27e+03 |
| test / 252 | 20260911 | neural | 54 | 4 | 194 | 1027 卤 765 | 985.8 卤 734 |
| test / 252 | 20260911 | neuralFirst | 80 | 6 | 166 | 5429 卤 6.68e+03 | 5209 卤 6.43e+03 |

All-case times include failures and CPU contention. SD describes variation across cases, not repeated-run timing uncertainty. Queue wait, allocation volume, diagnostic Newton counters and per-case paired differences are in the summary. These concurrent measurements must not be pooled with archived serial timings.

The reference and selected roles share one test execution because their artifact hashes match.

The 168-reference profile metrics are retained from the archived fits for diagnosis; they did not select the winner. Test outcomes did not alter the winner, and no runtime default was promoted.

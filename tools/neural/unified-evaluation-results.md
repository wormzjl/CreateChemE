# Unified column test: convergence and runtime

One set of 252 cases supplies every convergence and runtime metric below. All strategies run serially with 2-second neural and 30-second whole-request budgets. The 405-case validation set remains separate for model selection.

| Model | Strategy | Qualified / 252 | Advisory only | Failed | Elapsed ms, mean ± sample SD | CPU ms, mean ± sample SD |
|---|---|---:|---:|---:|---:|---:|
| transformer | current | 66 | 3 | 183 | 3437 ± 5.68e+03 | 3426 ± 5.67e+03 |
| transformer | neural | 67 | 5 | 180 | 743 ± 691 | 740.9 ± 689 |
| transformer | neuralFirst | 98 | 5 | 149 | 3753 ± 5.98e+03 | 3742 ± 5.96e+03 |
| mlp | current | 66 | 3 | 183 | 3121 ± 5.51e+03 | 3112 ± 5.49e+03 |
| mlp | neural | 47 | 4 | 201 | 744.3 ± 641 | 741.9 ± 639 |
| mlp | neuralFirst | 89 | 6 | 157 | 3510 ± 5.38e+03 | 3503 ± 5.37e+03 |
| gen3-factorized | current | 66 | 3 | 183 | 3132 ± 5.5e+03 | 3125 ± 5.49e+03 |
| gen3-factorized | neural | 29 | 1 | 222 | 834.3 ± 644 | 832.5 ± 643 |
| gen3-factorized | neuralFirst | 79 | 4 | 169 | 3842 ± 5.6e+03 | 3833 ± 5.59e+03 |
| nearest-k1 | current | 66 | 3 | 183 | 3123 ± 5.51e+03 | 3115 ± 5.49e+03 |
| nearest-k1 | neural | 41 | 4 | 207 | 641.1 ± 602 | 639.7 ± 602 |
| nearest-k1 | neuralFirst | 81 | 6 | 165 | 3359 ± 5.31e+03 | 3350 ± 5.3e+03 |

All-case timing includes failures. Paired gains/losses, Newton iteration statistics and common-qualified timing differences are in summary.json. Each model has its own paired classical measurements; these repeated controls are not independent test cases.

The prior 10-second convergence campaign and 64-case timing subset are historical diagnostics and are not pooled into this report. The models remain frozen. These test inputs were already released for the prior campaign, so this is a fixed-model follow-up rather than a new blind test.

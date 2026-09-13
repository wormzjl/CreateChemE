# Neural correction budget results on the frozen F0 weights

**Status: phase one registered, nothing measured.** This file is the skeleton the phase-two run fills in.
Every heading below is a table or a claim the campaign must produce; none of them is written yet, and no
number in this document is a result until the run that produced it is named beside it. The protocol,
including every classification threshold and the rule that chooses between the two candidate interventions,
was registered before any of them ran: see [protocol.md](protocol.md).

## Phase one: what was registered

| Item | Value |
| --- | --- |
| Base commit | `55b41ef` (`claude/v4-r3-decoder-floor`) |
| Model | `F-20260911-s4160`, SHA-256 `7f909d02…4ddd1962`, frozen |
| Traced population | 216 cases: 141 iteration-capped, 80 time-capped, 34 classical-only losses |
| Trace configurations | production 16 iterations / 2,000 ms; diagnostic 48 iterations / 6,000 ms |
| Source change | in-flight correction evidence, the observation seam; outcomes, budgets and digests unchanged |

### The reporting artefact this study removes first

The archived campaigns record 75 neural-only failures as "neural budget exhausted" with zero Newton
iterations. That is a placeholder, not a measurement: the budget throws out of whatever the correction was
doing and the candidate loop had no failure of its own to publish. The published failure now carries the
interrupted attempt's completed iterations, its last maximum scaled residual, and its attempt and refresh
counts. No outcome, budget, tolerance or digest changes, which the archive parity gate measures on all 405
inputs in both blocks.

## Bounded diagnostic

<!-- budget_trace.py analyse; build/neural-budget/v1/trace-analysis.json -->

| Configuration | Cases | Strictly converged | Mean ms | Mean iterations |
| --- | ---: | ---: | ---: | ---: |

### Classification of the capped population

| Class | Cases | Share of capped |
| --- | ---: | ---: |
| crawling | | |
| stalled | | |
| hopeless | | |
| reinserting | | |
| short trajectory | | |

### What the three registered groups turned out to be

<!-- the 137 iteration-capped, the 75 time-capped and the 34 classical-only losses, separately -->

### Measured per-iteration correction cost

<!-- millisPerIteration overall and by stage count; the input to the declared net-time ladder -->

### Decision under the declared rules

<!-- which of A-extend, A-abort and B fired, at which ladder step A was registered, and why -->

## Campaign

<!-- budget_benchmark.py validation; budget_analysis.py; budget_report.py -->

### Harness parity

| Check | Result |
| --- | --- |
| Decoded seeds, all 405 inputs, versus both archived F0 journals | |
| Strict classical identity set versus archive | |
| Strict ONLY identity set versus archive | |
| Strict FIRST identity set versus archive | |

### Complete validation counts and costs

| Pipeline | Block | Classical | ONLY | FIRST | FIRST advisory | FIRST failed | FIRST mean ms | FIRST p95 ms | ONLY mean ms | Run s |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |

### Paired gains and losses against F0-baseline

| Candidate | Block | ONLY gains / losses | FIRST gains / losses | Sets reproduced in both blocks |
| --- | --- | ---: | ---: | --- |

### Costs on stable common successes

| Comparison | Strategy | Cases | Reference mean / median ms | Candidate mean / median ms | Paired mean / median ms |
| --- | --- | ---: | --- | --- | --- |

### Costs and iterations on stable common failures

| Comparison | Strategy | Cases | Reference mean ms | Candidate mean ms | Paired mean ms | Reference / candidate mean iterations |
| --- | --- | ---: | ---: | ---: | ---: | --- |

### Stop-phrase evidence

| Pipeline | ONLY iteration_limit | ONLY neural_time_limit | ONLY no_admissible_step | ONLY unknown | FIRST iteration_limit | FIRST neural_time_limit | FIRST whole_request_deadline |
| --- | --- | --- | --- | --- | --- | --- | --- |

### The registered budget-wall groups after the intervention

| Group | Baseline ONLY strict | Candidate ONLY strict | Recovered identities |
| --- | ---: | ---: | --- |

## Verdict

<!-- per candidate: strict FIRST gain in both blocks, classical union preserved, no pooled mean FIRST
     latency regression; and what the diagnostic showed about the 137 / 75 / 34 groups -->

## Evidence and limits

Historical validation, not a fresh holdout; the 168 certified references are a subset of the 405
evaluation inputs. The traced groups are outcome-selected diagnostics, not a population benchmark. Repeated
blocks reuse identical inputs, so their spread describes case and load variability, not a confidence
interval over independent campaigns. The predecessor campaign measured a 114 ms spread in the pooled
neural-first mean between two runs of an identical pipeline on identical seeds; differences below that are
concurrency noise. Advisory successes are excluded from every strict count and no failed input leaves the
denominator.

Per-case outcome, status, cost and stop evidence for every run, the reference profile evidence, the
per-case decoded-seed digests and the per-case correction trajectories are committed gzipped under
`evidence/`. The complete journals, decoded seeds and traced profiles stay under `build/neural-budget/v1`;
`cache-manifest.json` binds them by hash. The machine-readable verdict is `summary.json`.

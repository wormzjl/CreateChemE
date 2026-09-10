# Unified native evaluation findings

The transformer with classical fallback qualified 98 of 252 test cases (38.9%),
compared with 66 (26.2%) for classical initialization: 32 additional cases and no
losses among the classical successes. The matched MLP reached 89, nearest-profile
transfer 81 and factorized Gen3 79. This is an observed coverage improvement on
this fixed test population; the all-case runtime increased.

The [active method](unified-evaluation.md) uses the original 405 validation inputs
for frozen selection and all 252 test inputs for both convergence and timing.
Every model ran CURRENT_ONLY, LNN_ONLY and LNN_FIRST serially with a 2-second
shared neural budget, a 30-second whole-request deadline and the existing
16-iteration-per-correction-pass setting. There are 3,024 strategy evaluations
on 252 distinct test inputs. The repeated classical runs qualified the same 66
input IDs; they are paired controls, not additional independent cases.

| Frozen model | LNN_ONLY qualified | LNN_FIRST qualified | Gains / losses vs paired classical | LNN_FIRST elapsed seconds, mean ± sample SD | Paired extra elapsed ms, mean ± sample SD |
|---|---:|---:|---:|---:|---:|
| Transformer | 67 | 98 | 32 / 0 | 3.753 ± 5.977 | 316 ± 1,484 |
| Matched MLP | 47 | 89 | 23 / 0 | 3.510 ± 5.381 | 389 ± 2,165 |
| Factorized Gen3 | 29 | 79 | 13 / 0 | 3.842 ± 5.598 | 710 ± 866 |
| Nearest profile, k=1 | 41 | 81 | 15 / 0 | 3.359 ± 5.307 | 236 ± 2,364 |

All timing means above include all 252 cases, including failures. LNN_ONLY
timing includes native correction as well as inference. The transformer's
LNN_ONLY mean was 743 ± 691 ms, but its successful inputs differ from the
classical successes, so that difference is not a matched-solution speedup.

In the transformer run, classical elapsed time was 3.437 ± 5.679 seconds and
transformer-first was 3.753 ± 5.977 seconds, approximately 9.2% higher on average.
On the 66 inputs qualified by both strategies, the paired difference was
−96 ± 1,340 ms. This single run does not establish a reliable speed improvement.
Classical means in the other model runs were about 3.12 seconds; use each model's
paired difference rather than treating small cross-run latency differences as
an architectural ranking. The reported standard deviations describe variation
across cases, not uncertainty estimated from repeated benchmark campaigns.

Transformer-first returned 98 qualified, 5 advisory-only and 149 failed results.
Strict qualification verifies the native acceptance audit and final Newton
certificate, including closure, linear backward error and final step tolerances.
Advisory-only results are excluded from qualified counts. No failed or timed-out
test case was dropped.

The diagnostic Newton iteration summaries are also retained:

| Model | LNN_ONLY iterations, mean ± sample SD (n) | LNN_FIRST iterations, mean ± sample SD (n) |
|---|---:|---:|
| Transformer | 8.45 ± 7.36 (252) | 11.40 ± 22.17 (245) |
| Matched MLP | 10.47 ± 7.17 (252) | 12.16 ± 22.29 (246) |
| Factorized Gen3 | 12.17 ± 6.47 (252) | 12.47 ± 22.32 (245) |
| Nearest profile, k=1 | 12.27 ± 6.09 (252) | 12.06 ± 21.13 (246) |

Classical diagnostics were 12.75 ± 22.44 iterations on 245 inputs in every run.
These are the native result's diagnostic counters, not a sum of every attempted
neural pass and fallback solve. Missing counters remain missing; the smaller
iteration sample sizes do not remove cases from the outcome or timing totals.
CPU time, allocation volume and further distribution statistics are available
in the [complete summary](unified-evaluation-summary.json). Allocated bytes
measure cumulative allocation, not retained RAM.

Both new architectures use checkpoint seed 20260912, frozen before this test.
Java/PyTorch-double parity passed on 14 fitted-column fixtures, with maximum
decoded temperature differences of 1.88e-5 K for the transformer and 1.74e-5 K
for the MLP. Maximum flow differences divided by feed were 5.55e-7 and 7.33e-7.
Branch, wet and zero-flow masks matched exactly; shared-model concurrency,
cancellation and malformed-shape checks passed. Native inference is implemented
in the offline tools source set. The evaluated JVM was Java 21.0.11 with a
4 GiB maximum heap and one benchmark worker.

The earlier 10-second test campaign and separate 64-case timing subset are
historical diagnostics and contribute no measurements to these findings.
The 252 inputs had already been exposed in that campaign, so this is a frozen
model follow-up, not a new blind test. The original 405-case validation selection
used a 10-second neural budget and remains selection history. No test outcomes
were used to fit, select or retune these candidates.

The [generated report](unified-evaluation-results.md) and complete summary are
byte-identical copies of the runner outputs. The
[cache manifest](unified-evaluation-cache-manifest.json) records verified SHA-256
hashes for `.neural-cache/unified-evaluation-v1/study.zip` and `dependencies.zip`.
The first archive holds all unified journals and run metadata; the second holds
the frozen inputs, all four models, selection and parity evidence, implementation
sources and historical journals. The interrupted historical nearest-profile
timing journal is preserved and explicitly marked incomplete. Neither archive
is inside Gradle's build directory. Preserve both archives, together with the
linked GPU-pilot and Gen3 caches, when restoring this study.

To complete the dependency archive after a fresh unified run:

```powershell
.\.neural-venv\Scripts\python.exe tools/neural/seal_unified_evaluation.py
```

The sealer validates complete runs and archive entries before publishing the
tracked result copies. It creates new artifacts and refuses to overwrite an
existing published study.

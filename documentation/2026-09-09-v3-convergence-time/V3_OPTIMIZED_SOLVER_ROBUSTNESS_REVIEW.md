# V3 optimized solver: robustness review

Audit date: 2026-09-09. Read-only with respect to production code; no `src/main` or `src/test` file was
modified in either checkout. All numbers below come from runs executed for this review; nothing is quoted
from the optimization work's own measurements.

- **ORIGINAL** = `e8d8937` ("Mark a retained result on the Heat page instead of clearing its pill"), main at
  the start of the optimization work.
- **OPTIMIZED** = `1e388f5` (`claude/convergence-time-optimization-7f5b84`), nine commits later.

## Verdict

**Five outcome regressions, five outcome gains, zero changed physical answers, zero new crash modes.**

| Case set | Cases | Same success | Same failure | ORIGINAL-only success | OPTIMIZED-only success |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1. Cold-core DOE, default suite | 64 | 44 | 20 | **0** | **0** |
| 2. Regression fixtures (JUnit tests) | 50 | 50 | 0 | 0 | 0 |
| 3. Perturbation sweep (pre-declared) | 85 | 49 | 26 | **5** | **5** |
| 3b. Refinement sweep (biased, chosen around the flips) | 17 | 11 | 1 | 4 | 1 |
| 4. Adversarial / edge (subset of set 3) | 17 | 6 | 11 | 0 | 0 |

- Every regression is a **wet, pumparound-bearing literature-CDU perturbation**, and every one of them is
  introduced by a single commit, `5a50e61` "Double the steam ramp and stop its intermediate rungs from
  crawling" (P4/P5/P6). Bisected and confirmed by ablation (§7).
- No regression appears in the 64-case DOE, in the 50 regression fixtures, or in any dry column.
- Among the 44 + 49 shared successes the physical answers agree to a **median 1.8e-15 / 1.1e-13 relative**
  stream flow and a **median 2.3e-13 / 1.4e-12 K** stream temperature. The one shared success above 1e-9
  (1.1e-5) is explained and is the *more* exact of the two answers (§5.1).
- No `INTERNAL_ERROR`, no uncaught exception, no `RESOURCE_LIMIT`, no watchdog kill, on either revision, in
  any of the **446** calculator invocations logged by this audit. The only statuses observed are
  `SUCCESS_EXACT` 52, `SUCCESS_REDUCED` 52, `SUCCESS` 173, `NONCONVERGENCE` 127, `LINEAR_SOLVE_FAILURE` 25,
  `INVALID_INPUT` 8, `PROPERTY_OUT_OF_RANGE` 4, `DEADLINE_EXCEEDED` 3 (all on ORIGINAL),
  `INFEASIBLE_SPECIFICATION` 2.
- Both revisions are bit-reproducible run to run (§10).
- Speed, reported but not weighted: paired geometric mean OPTIMIZED/ORIGINAL elapsed time is **0.247** on the
  DOE shared successes and **0.189** on the perturbation shared successes; the fixture suite goes from
  266.4 s to 46.5 s. No case in any set is slower on OPTIMIZED.

## 1. Method

Both revisions were checked out as separate git worktrees and compiled with plain `javac --release 21`
(JDK 21.0.11) from `src/main/java/.../science` plus the existing test-source DOE worker and one new
review-only runner, so that a case could be executed in a fresh JVM without Gradle. This makes the science
package the only difference between the two classpaths.

Every case runs in **its own cold JVM**, one at a time, no other load on the host, with the DOE manifest's
own screen-worker flags `-Xms128m -Xmx1536m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC` and a 60 s deadline
enforced through the calculator's `V3SolveControl` checkpoint, plus an external watchdog. Because each JVM is
cold, the wall times below are 2–4x larger than warm in-process timings; both revisions pay that cost
identically, so the paired ratios are meaningful and the absolute values are not comparable to the
optimization work's warm figures.

Checkouts and classpaths:

| Label | Commit | Content | Checkout |
| --- | --- | --- | --- |
| R0 = ORIGINAL | `e8d8937` | baseline | `.claude/worktrees/robustness-original` |
| R1 | `2928e7f` | + P1 flat banded LU, + P2a decode-free stage-block probes | `.claude/worktrees/robustness-r1` |
| R2 | `83d9490` | + P4/P5/P6 doubling steam ramp and intermediate `RungBudget` | `.claude/worktrees/robustness-r2` |
| R3 | `746456e` | + P2 analytic PR78 derivatives | `.claude/worktrees/robustness-r3` |
| R4 = OPTIMIZED | `1e388f5` | + P3 oversized-seed cap and rectifying interpolation clamp | `.claude/worktrees/agent-acf7be2374b92fb29` |

## 2. Where a difference can come from

Read against the diff and then verified by run (§7):

| Change | Kind | Can move the answer? | Verified |
| --- | --- | --- | --- |
| P1 flat LAPACK-layout banded LU | representation of the same factorization | no | R1 stream fingerprints are **bit-identical** to R0 on all 9 successes retested |
| P2a decode-free stage-block probes | same probes, fewer decodes | no | same |
| P4/P5 doubling steam-ramp schedule | continuation policy | not the root, but the seed the requested rung gets | R2 flips 7 preset outcomes |
| P6 intermediate steam-rung `RungBudget` | continuation policy | same | R2, isolated by ablation |
| P2 analytic PR78 derivatives | Jacobian only, residual untouched | trajectory only | R3 changes dry-column fingerprints in the last 3–5 digits |
| P3 `capOversizedPoint` + rectifying clamp | continuation seed | trajectory only | R4 flips 4 outcomes |

The residual function itself is untouched: `V3MeshResidualEvaluator` and `V3PengRobinsonKernel` are
**purely additive** in the diff (no removed line other than the `V3PengRobinsonThermo` class declaration
gaining `implements V3ThermoDerivatives`). Every published answer is still the root of the same equations,
verified by the same acceptance audit and final-Newton certificate.

## 3. Case set 1 — cold-core DOE, 64-case default suite

Driven through the unmodified `V3ColdCoreBenchmarkWorker` JSONL protocol against
`src/test/resources/v3-benchmarks/v3-cold-core-v1.json`, one solve per fresh worker JVM, 60 s deadline.

| Panel | Cases | ORIGINAL accepted | OPTIMIZED accepted |
| --- | ---: | ---: | ---: |
| Factorial operating screen | 36 | 16 | 16 |
| Pressure boundary 99/100/101 kPa | 12 | 12 | 12 |
| Column size | 10 | 10 | 10 |
| Performance anchors | 6 | 6 | 6 |
| **Total** | **64** | **44** | **44** |

(The 2026-09-05 campaign accepted 26–27 of these on a much older baseline, `bdfeff1`. The 44/64 here is what
`e8d8937` reaches; it is not a claim about that campaign.)

**Outcome agreement: 44 same-success, 20 same-failure, 0 ORIGINAL-only, 0 OPTIMIZED-only.** All 20 shared
failures carry the *same* `V3SolverFailureCode` on both revisions (2 `INVALID_INPUT` pairs, 18
`NONCONVERGENCE` pairs); none is a deadline. The terminal residual of a shared failure agrees to ~1e-9
except `F02-wet-*` (0.6121 -> 0.4967) and `F06-wet-*` (0.2584 -> 0.2247), i.e. OPTIMIZED stops in a slightly
better place while still failing.

Shared-success agreement:

| Quantity | median | p90 | max |
| --- | ---: | ---: | ---: |
| relative stream molar-flow difference | 1.80e-15 | 4.79e-15 | 1.87e-10 (`A250-wet-slowdown-off`) |
| absolute stream temperature difference | 2.27e-13 K | — | 1.16e-8 K (`A250-wet-slowdown-on`) |

- All 44 shared successes carry the same `status` label (`SUCCESS_EXACT` / `SUCCESS_REDUCED`), so the
  truncation route is unchanged, and all pass the worker's external hydrocarbon and water closure checks on
  both revisions. No acceptance-audit check fails anywhere.
- Published Newton iteration counts are identical on 41 of 44; the three that differ are
  `P99-wet-on` 5 -> 6, `A250-wet-slowdown-off` 3 -> 6, `A250-wet-slowdown-on` 5 -> 3.
- One solve path differs: `F09-dry-on`, where OPTIMIZED takes a
  `material-vle-recovery-stage-30` recovery that ORIGINAL did not need and still publishes an accepted,
  closed result in 4.1 s against ORIGINAL's 6.7 s. A recovery entered and won is not a regression, but it is
  the only place in the DOE where the optimized trajectory needed more machinery than the original.
- Timing: paired geometric mean 0.2467 over the 44 shared successes; the whole 64-case suite takes 617.1 s of
  calculator time on ORIGINAL and 132.3 s on OPTIMIZED; **no case is slower**. Largest single win among the
  failures is `F06-wet-on` 21.2 s -> 1.8 s; among successes `N64-on` 19.5 s -> 1.4 s.

Raw: `build/robustness/doe-orig.jsonl`, `doe-opt.jsonl`, comparison `doe-compare.csv`.

## 4. Case set 2 — regression fixtures

`./gradlew.bat --offline --no-daemon test --tests …` for the ten named classes on each checkout.

| Suite | tests | ORIG fail/err | OPT fail/err | ORIG s | OPT s | stdout identical |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| HollandExample32BenchmarkTest | 2 | 0/0 | 0/0 | 3.4 | 1.0 | yes |
| V3ColdStartSweepTest | 1 | 0/0 | 0/0 | 1.1 | 1.1 | yes |
| V3ConvergenceClosureTest | 16 | 0/0 | 0/0 | 77.6 | 12.2 | yes |
| V3DwsimPathInitializerTest | 1 | 0/0 | 0/0 | 7.4 | 1.7 | no |
| V3DwsimRealCrudeOperatingMapTest | 1 | 0/0 | 0/0 | 5.9 | 3.4 | no |
| V3DwsimRealCrudeStageMapTest | 1 | 0/0 | 0/0 | 4.7 | 0.6 | no |
| V3PumparoundCalculatorTest | 13 | 0/0 | 0/0 | 58.6 | 10.6 | no |
| V3PumparoundSteamCalculatorTest | 7 | 0/0 | 0/0 | 56.4 | 6.3 | no |
| V3RealCrudeColdSweepTest | 1 | 0/0 | 0/0 | 3.9 | 0.4 | no |
| V3SideDrawCalculatorTest | 7 | 0/0 | 0/0 | 47.4 | 9.2 | no |
| **Total** | **50** | **0/0** | **0/0** | **266.4** | **46.5** | |

Every one of the 50 tests passes on both revisions and no test changes status. The Holland oracle benchmark
prints byte-identical output. Diffing the seven differing `system-out` blocks after normalising elapsed
times and residuals below 1e-13 leaves:

- `V3PumparoundSteamCalculatorTest` energy ledgers: every MW figure identical to six significant figures;
  only the *closure residual* differs, e.g. `closure=1.341e-07 W (1.5e-15 relative)` on ORIGINAL against
  `closure=-7.451e-09 W (-8.3e-17 relative)` on OPTIMIZED — the same balance, rounded differently.
- `V3DwsimRealCrudeOperatingMapTest` case `duty_0`: ORIGINAL returns `BUDGET_EXCEEDED` after the test's 5 s
  allowance; OPTIMIZED returns a typed `NONCONVERGENCE` after 31 iterations in 3.27 s. Neither produces an
  answer; the optimized run merely finishes inside the budget and says why it failed.
- Everything else is `elapsed_ms` and last-digit residual.

Raw: `build/robustness/fixtures-ORIGINAL/`, `fixtures-OPTIMIZED/` (JUnit XML), `fixtures-diff/` (the seven
extracted stdout pairs), `fixtures-ORIGINAL.log`, `fixtures-OPTIMIZED.log`.

## 5. Case set 3 — perturbation sweeps through the public API

85 cases built through `V3ColumnCalculator.calculate(input, control, cutoff[, closure])` around two anchors:
the literature CDU preset (40 stages, feed tray 37, 250 kPa, `dP` 0, condenser 332.15 K, R = 4.17, reboiler
duty 0, three side draws, 1200 kmol/h of 533.15 K sump steam, three uniform pumparounds at
-12.84/-17.89/-11.20 MW) and the plain 40-tray column (same feed, 8 MW reboiler, no draws/steam/heat). The
case list was written before any result was seen. Where a factor scales the feed, the draws and the steam
scale with it, so that the draw fraction is not perturbed as a side effect; every input digest matched
between revisions, so both sides solved literally the same problems.

| Factor group | cases | ORIG ok | OPT ok | ORIGINAL-only | OPTIMIZED-only |
| --- | ---: | ---: | ---: | --- | --- |
| anchors | 2 | 2 | 2 | – | – |
| condenser temperature ±5, ±10 K | 8 | 8 | 7 | `preset cond+5` | – |
| reflux ratio x0.7, x1.3, x2 | 6 | 3 | 3 | – | – |
| feed temperature ±15 K | 4 | 2 | 3 | – | `plain40 feedT+15` |
| feed rate x0.8, x1.25 | 4 | 2 | 2 | – | – |
| top pressure 150/200/300 kPa | 6 | 4 | 4 | `preset 200 kPa` | `plain40 200 kPa` |
| stage pressure drop 750 Pa | 2 | 1 | 2 | – | `preset dp750` |
| stage count 20, 30, 60 | 6 | 5 | 5 | – | – |
| cutoff 1e-6, closure 1e-6/1e-3 | 6 | 6 | 6 | – | – |
| steam rate x0.5, x1.5, x2 | 3 | **3** | **0** | `steamx0.5`, `steamx1.5`, `steamx2` | – |
| side-draw loading x0.5, x0.40, x1.25 | 3 | 2 | 2 | – | – |
| pumparound duty x0.5, x1.5 | 2 | 0 | 1 | – | `preset pax1.5` |
| 2-factor grid, reflux x condenser | 16 | 10 | 11 | – | `G-preset-r2-c10` |
| adversarial / edge | 17 | 6 | 6 | – | – |
| **Total** | **85** | **54** | **54** | **5** | **5** |

Shared-success agreement: relative stream-flow difference median 1.07e-13, p90 3.61e-11, max 1.10e-5;
temperature median 1.36e-12 K, max 1.16e-3 K. Published iteration counts identical on 46 of 49. One solve
path differs (`P-plain40-stages60`, where ORIGINAL needed a `material-vle-recovery-stage-60` that OPTIMIZED
did not). Paired geometric mean 0.1891; no case slower on OPTIMIZED.

### 5.1 The one shared success above 1e-9

`P-preset-cutoff1e-6` (the preset at stage-trace cutoff 1e-6) differs by 1.10e-5 relative in the distillate
flow and 1.16e-3 K. This is not a difference in the physical answer but in **which problem was published**:

- ORIGINAL solved the truncated problem (retained 512 of 798 points) and published it with an audited
  sink-edge defect of 2.72e-6 of feed against a limit of 8.00e-6.
- OPTIMIZED's truncated attempt NONCONVERGED, so the existing `stage-trace fallback` retried the untruncated
  problem and published *that*, with a defect of 6.81e-11 against a limit of 4.00e-10.

Both are accepted, both are inside their own budgets, and the 1.1e-5 gap is the size of ORIGINAL's own
audited truncation approximation. OPTIMIZED returns the more exact answer here and pays the untruncated
solve for it. The same cutoff on all 24 DOE `-on` cases keeps `SUCCESS_REDUCED` on both revisions, so this
fallback is specific to the pumparound-bearing preset.

### 5.2 Refinement sweep (biased)

17 extra points were run afterwards, deliberately placed around the three factors that flipped, to map the
shape of the change. Because they were chosen after seeing the flips, they must not be counted with the 85.

| case | ORIGINAL | OPTIMIZED |
| --- | --- | --- |
| preset steam x0.6 / x0.75 / x0.9 / x0.95 | SUCCESS | SUCCESS |
| preset steam **x1.05** | SUCCESS | FAILURE / LINEAR_SOLVE_FAILURE |
| preset steam x1.1 / x1.25 / x1.75 | SUCCESS | SUCCESS |
| preset cond +1 / +2 | SUCCESS | SUCCESS |
| preset cond **+3 / +4** | SUCCESS | FAILURE / LINEAR_SOLVE_FAILURE |
| preset cond +7.5 | SUCCESS | SUCCESS |
| preset top 175 kPa | DEADLINE_EXCEEDED | FAILURE / NONCONVERGENCE |
| preset top **210 kPa** | DEADLINE_EXCEEDED | **SUCCESS** |
| preset top 225 kPa | SUCCESS | SUCCESS |
| preset top **240 kPa** | SUCCESS | FAILURE / LINEAR_SOLVE_FAILURE |

The lost points are **isolated, not a contiguous band**: steam x1.05 fails between two neighbours that
succeed, condenser +3/+4 fail between +2 and +7.5 that succeed. This matters for how the regression should
be read — see §8.

To make sure the two ORIGINAL deadlines are not just slowness, `preset top 210 kPa`, `preset top 175 kPa`
and `preset reflux 1e4` were rerun on ORIGINAL with a **400 s** deadline: all three return
`NONCONVERGENCE` (after 142.5 s, 193.6 s and 63.0 s). So the OPTIMIZED-only success at 210 kPa is a genuinely
newly reachable input, not a deadline artefact.

Raw: `pert-orig.jsonl`, `pert-opt.jsonl`, `perturb-compare.csv`, `ref-orig.jsonl`, `ref-opt.jsonl`,
`refine-compare.csv`, `long-orig.jsonl`.

## 6. Case set 4 — adversarial and edge inputs

| case | ORIGINAL | OPTIMIZED | orig ms | opt ms |
| --- | --- | --- | ---: | ---: |
| preset, condenser 300 K | FAILURE/LINEAR_SOLVE_FAILURE | FAILURE/NONCONVERGENCE | 21851 | 3119 |
| preset, condenser 280 K | FAILURE/PROPERTY_OUT_OF_RANGE | FAILURE/PROPERTY_OUT_OF_RANGE | 101 | 114 |
| plain40, condenser 300 K | SUCCESS | SUCCESS | 5631 | 1096 |
| plain40, condenser 280 K | FAILURE/PROPERTY_OUT_OF_RANGE | FAILURE/PROPERTY_OUT_OF_RANGE | 82 | 96 |
| preset, reflux 50 | FAILURE/NONCONVERGENCE | FAILURE/NONCONVERGENCE | 49093 | 8568 |
| preset, reflux 1e4 | DEADLINE_EXCEEDED | FAILURE/NONCONVERGENCE | 60000 | 2737 |
| plain40, reflux 50 | SUCCESS | SUCCESS | 5161 | 1031 |
| plain40, reflux 1e4 | SUCCESS | SUCCESS | 13461 | 2348 |
| plain40, zero reboiler duty, no steam | FAILURE/NONCONVERGENCE | FAILURE/NONCONVERGENCE | 51909 | 7178 |
| plain, 4 trays, 8 MW | SUCCESS | SUCCESS | 366 | 254 |
| plain, 4 trays, 2 MW | SUCCESS | SUCCESS | 381 | 241 |
| plain, 64 trays, 8 MW | SUCCESS | SUCCESS | 10417 | 1537 |
| preset, 64 stages | FAILURE/NONCONVERGENCE | FAILURE/NONCONVERGENCE | 24546 | 3578 |
| plain40, single draw 0.50 x feed | FAILURE/NONCONVERGENCE | FAILURE/NONCONVERGENCE | 14666 | 2325 |
| plain40, single draw 0.95 x feed | FAILURE/NONCONVERGENCE | FAILURE/NONCONVERGENCE | 12118 | 1970 |
| plain40, single draw 1.50 x feed | FAILURE/INFEASIBLE_SPECIFICATION | FAILURE/INFEASIBLE_SPECIFICATION | 10 | 9 |
| plain40, sump steam, zero duty, no draws | FAILURE/NONCONVERGENCE | FAILURE/NONCONVERGENCE | 16348 | 2317 |

**17 of 17 identical outcomes.** The typed rejections that should be typed still are: the too-cold condenser
is `PROPERTY_OUT_OF_RANGE` in 0.1 s on both, the tray-emptying draw at 1.5 x feed is
`INFEASIBLE_SPECIFICATION` in 10 ms on both. The only differences are cosmetic: `preset cond 300 K` swaps
`LINEAR_SOLVE_FAILURE` for `NONCONVERGENCE` (both no answer), and `preset reflux 1e4` turns a 60 s deadline
into a typed 2.7 s failure, which is a strict improvement in behaviour under an absurd specification.

## 7. Root cause of every disagreement

### 7.1 Bisect

The ten disagreeing cases plus eleven controls were rerun on R1/R2/R3 (identical harness, identical inputs).
`OK` = SUCCESS, `NONCONV` = NONCONVERGENCE, `LINPIVOT` = LINEAR_SOLVE_FAILURE.

| case | R0 orig | R1 +P1/P2a | R2 +steam ramp | R3 +analytic | R4 +seeds |
| --- | --- | --- | --- | --- | --- |
| `P-preset-base` | OK | OK | OK | OK | OK |
| `P-plain40-base` | OK | OK | OK | OK | OK |
| `P-preset-cond+5` | OK | OK | **LINPIVOT** | LINPIVOT | LINPIVOT |
| `P-preset-top200kPa` | OK | OK | **NONCONV** | NONCONV | NONCONV |
| `P-preset-steamx0.5` | OK | OK | **LINPIVOT** | LINPIVOT | LINPIVOT |
| `P-preset-steamx1.5` | OK | OK | **NONCONV** | NONCONV | NONCONV |
| `P-preset-steamx2` | OK | OK | **LINPIVOT** | LINPIVOT | NONCONV |
| `P-preset-dp750` | NONCONV | NONCONV | **OK** | OK | OK |
| `P-preset-pax1.5` | NONCONV | NONCONV | **OK** | OK | OK |
| `P-plain40-feedT+15` | NONCONV | NONCONV | NONCONV | NONCONV | **OK** |
| `P-plain40-top200kPa` | NONCONV | NONCONV | NONCONV | NONCONV | **OK** |
| `G-preset-r2-c10` | NONCONV | NONCONV | NONCONV | NONCONV | **OK** |
| `G-preset-r2-c-10` | NONCONV | NONCONV | LINPIVOT | **OK** | LINPIVOT |
| `E-preset-reflux1e4` | DEADLINE | NONCONV | NONCONV | NONCONV | NONCONV |
| `P-plain40-stages60`, `P-preset-cutoff1e-6`, `P-preset-refluxx2`, `P-preset-feedT-15`, `P-preset-feedratex0.8`, `G-preset-r2-c0`, `E-preset-cond300` | see CSV | no outcome change at R1 | | | |

Two results fall out of this table directly:

1. **R1 is bit-identical to R0.** Every success retested at R1 has the same stream fingerprint as R0 to the
   last bit, and no outcome changes. P1 (flat banded LU) and P2a (decode-free probes) are confirmed as pure
   representation changes on real column solves, not only on the fixture.
2. **All five regressions appear at R2**, the merge that brings in `5a50e61`. Nothing later removes them, and
   the two later commits add three more successes (`plain40 feedT+15`, `plain40 top200kPa`,
   `G-preset-r2-c10`, all from P3) while `G-preset-r2-c-10` is gained at R3 and lost again at R4 — a case
   that ORIGINAL never solved either, so it is not a regression.

### 7.2 Ablation inside `5a50e61`

Two review-only ablation builds were made by copying the OPTIMIZED science sources into `build/` and patching
**the copies** (production sources untouched): `fullbudget` makes `rampRungBudget` always return
`RungBudget.DEFAULT` (undoes P6), `equalramp` restores the equal `k/N` steam schedule (undoes P4/P5),
`both` does both.

| case | R0 orig | R4 opt | R4 with P6 off | R4 with P4/P5 off | R4 with both off |
| --- | --- | --- | --- | --- | --- |
| `P-preset-base` | OK | OK | OK | **FAIL** | OK |
| `P-preset-cond+5` | OK | FAIL | FAIL | **OK** | **OK** |
| `P-preset-top200kPa` | OK | FAIL | **OK** | FAIL | **OK** |
| `P-preset-steamx0.5` | OK | FAIL | **OK** | **OK** | **OK** |
| `P-preset-steamx1.5` | OK | FAIL | **OK** | **OK** | **OK** |
| `P-preset-steamx2` | OK | FAIL | FAIL | FAIL | FAIL |
| `P-preset-dp750` | FAIL | OK | OK | OK | **FAIL** |
| `P-preset-pax1.5` | FAIL | OK | OK | OK | **FAIL** |

- The two halves of `5a50e61` are **coupled, not independent**: turning off the doubling schedule while
  keeping the reduced rung budget loses the flagship literature preset itself. Neither half alone is "the
  bug"; the pair was tuned together on that one case.
- Turning both off recovers four of the five regressions and gives back exactly the two gains that `5a50e61`
  produced — i.e. `5a50e61` is a net -5/+2 trade on this sample, with the remaining -1 elsewhere.
- `P-preset-steamx2` is the exception: it is broken **independently** by P3. With both ramp changes reverted
  it still fails, at the 30-stage rung of the `4-8-15-30-40` grid, with `iterations=0`, a maximum scaled
  residual of only 1.57e-3 and "no admissible Armijo-reducing Newton or descent step". P3's seed rules place
  that rung's start where the local block direction is rejected and no descent step exists.

### 7.3 Rung evidence for the five regressions

The published events show exactly the mechanism: an intermediate steam rung stops, the ramp keeps its last
state and skips ahead, and the requested rung is solved from that state.

| case | ORIGINAL: steam rung stops at | OPTIMIZED: steam rung stops at | OPTIMIZED requested rung |
| --- | --- | --- | --- |
| `cond+5` | 0.333, iteration budget exhausted, 40 iters, resid 5.80e-2 | 0.625, no admissible Armijo step, 6 iters, resid 1.57e-3 | zero/tiny LU pivot after 18 iters at resid 2.27e-3 |
| `top200kPa` | 0.125, iteration budget exhausted, 40 iters, resid 5.60e-2 | 0.125, **stall stop** (0.0479 at iter 1, 0.0291 now), 13 iters | iteration budget exhausted, 32 iters, resid 4.50e-3 |
| `steamx0.5` | 0.875, iteration budget exhausted, 40 iters, resid 3.51e-2 | 1.000, **stall stop** (0.1958 -> 0.1526), 12 iters | zero/tiny LU pivot after 31 iters at resid 1.22e-3 |
| `steamx1.5` | 0.250, no admissible Armijo step, 6 iters, resid 3.21e-4 | 0.292, **stall stop** (0.1960 -> 0.1937), 12 iters | iteration budget exhausted, 32 iters, resid 8.72e-3 |
| `steamx2` | 0.125, no admissible Armijo step, 6 iters, resid 3.03e-4 | fails earlier, at the 30-stage rung (P3, see §7.2) | — |

Three of the five are stopped by the new stall detector (`residual has not halved over 12 iterations while
above 1e-3`). It is worth being precise about *why* that hurts, because the naive reading is wrong: in
`top200kPa` the optimized rung actually stops at a **better** residual (0.029 after 13 iterations) than the
original rung reaches (0.056 after 40), and it still loses the case. The discriminator is not the residual
level of the retained state but whether that state lies in the requested rung's basin, which the stall rule
does not and cannot control. The commit's own reasoning — "a coarser schedule cannot lose a solution the
equal one found, it can only hand the final rung a different seed" — is correct about the mechanism and
wrong about the consequence: a different seed does lose solutions, and here it loses five and gains two.

## 8. Sensitivity: does the success region shrink?

Counted over the pre-declared sweeps only (DOE 64 + perturbation 85 = 149 cases), the success region is the
same size: 98 successes on ORIGINAL, 98 on OPTIMIZED. It is not the same *set*: five points leave and five
arrive, all of them on the wet, three-pumparound literature CDU.

Where it changes:

- **Steam rate is the one factor that is uniformly worse.** All three pre-declared off-nominal steam
  perturbations (x0.5, x1.5, x2) succeed on ORIGINAL and fail on OPTIMIZED. Nominal steam still succeeds on
  both, as do x0.6, x0.75, x0.9, x0.95, x1.1, x1.25 and x1.75 in the refinement sweep.
- **Pumparound duty and stage pressure drop are better** (`pax1.5`, `dp750`), and **feed temperature and
  low top pressure on the dry column are better** (`plain40 feedT+15`, `plain40 top200kPa`, confirmed
  against a 400 s ORIGINAL rerun).
- **Draw loading is unchanged**: x0.5, x1.25 and the known-invalid x0.40 wall behave identically on both.
- **Pressure is a wash**: the preset loses 200 kPa and 240 kPa, the plain column gains 200 kPa, and the
  preset gains 210 kPa.
- The lost and gained points are **isolated cells, not a contiguous region** (§5.2). This is the honest
  characterisation: on this wet three-pumparound topology the continuation's success set was already
  speckled on ORIGINAL — of the 29 pre-declared preset perturbations away from the anchor ORIGINAL already fails 12, including
  all three reflux points, feed T -15 K, both feed-rate points, 150 kPa, 60 stages, draw x1.25 and both
  pumparound-duty points — and the optimization re-speckles it rather than contracting it. Neither revision has a defensible boundary here; the underlying fragility is the wet
  ramp, which is a known open item, not something these commits created.

## 9. Failure taxonomy churn

Among cases that fail on both revisions, the *code* sometimes swaps between `NONCONVERGENCE` and
`LINEAR_SOLVE_FAILURE`: seven such swaps in the perturbation set (`refluxx2`, `feedT-15`, `feedratex0.8`,
`G-preset-r2-c-10`, `G-preset-r2-c0`, `E-preset-cond300`, and `E-preset-reflux1e4` from a deadline). This is
expected — a different trajectory reaches a different last state — and it never converts a success into a
failure or vice versa. It does mean a caller keying UI copy on the exact failure code will see different text
for the same unsolvable input.

## 10. Determinism

Six cases (`F01-dry-off`, `F09-dry-on`, `N64-on`, `A250-wet-slowdown-off`, `F02-wet-off`, `F06-wet-off`) were
run **twice on each revision** in independent cold JVMs. All twelve pairs are identical in status, stream
SHA-256 output fingerprint, published Newton iteration count, maximum scaled residual and solve path. Elapsed
time varies by up to 6 %. Both revisions are reproducible; nothing in the optimization introduces
nondeterminism.

## 11. Timing (secondary)

| Set | paired geometric mean OPT/ORIG | total ORIG | total OPT | any case slower |
| --- | ---: | ---: | ---: | --- |
| DOE shared successes (44) | 0.2467 | 617.1 s (all 64) | 132.3 s | none |
| Perturbation shared successes (49) | 0.1891 | 1305.3 s (all 85) | 202.4 s | none |
| Refinement shared successes (11) | 0.1595 | 407.4 s (all 17) | 58.9 s | none |
| Regression fixtures (50 tests) | — | 266.4 s | 46.5 s | none |

Failure latency also drops sharply (e.g. `F06-wet-on` 21.2 s -> 1.8 s, `E-plain40-zeroduty` 51.9 s ->
7.2 s), which matters for an in-game caller that must give up quickly.

## 12. Limits of this audit

- **Single host, single JDK, single repetition** for outcome (two for the six determinism cases). Timings
  are cold-JVM and are not statistically qualified; they are not comparable to warm in-process figures.
- The DOE ran the 64-case **default** suite only. The `trace_walls` (6) and `historical_replay` (27) panels
  were not run, and the campaign's warmup / parallel-screen / serial-confirmation / three-pair timing
  protocol was not reproduced — this is a one-shot serial screen with a 60 s deadline.
- Only the ten named regression fixture classes were run, **not the full 509-test suite** on either
  revision, and no NeoForge/in-game path was exercised. Persistence, wire format and GUI are untouched by
  these commits but were not tested here.
- Perturbations use the two anchors only. The `cdu17_tjl_acs2018` package appears only through the DOE
  manifest; `tjl19_dwsim` carries every perturbation case.
- Stream comparison uses molar flow, mass flow, temperature, pressure, vapour fraction and per-component
  mole fractions; tray profiles are not published by the API and were not compared.
- The ablation builds patch a **copy** of the optimized sources; they are evidence about which knob moves
  which case, not a proposal, and they were not tested beyond the eight cases in §7.2.
- `P-preset-steamx2`'s P3 failure was localised to the 30-stage rung but the seed itself was not dumped and
  compared point by point; that is the one root cause here that is one level shallower than the others.

## 13. Reproduction

```sh
B=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-acf7be2374b92fb29/build/robustness
# classpaths (one per revision; no Gradle)
sh $B/build.sh <checkout> $B/cp-<label>
# 64-case DOE, both revisions, serial, one cold JVM per case
sh $B/run-doe.sh          && node $B/compare-doe.js $B/doe-orig.jsonl $B/doe-opt.jsonl $B/doe-compare.csv
# determinism reruns + 85-case perturbation sweep
sh $B/run-rest.sh         && node $B/compare-perturb.js $B/pert-orig.jsonl $B/pert-opt.jsonl $B/perturb-compare.csv
# refinement sweep
sh $B/run-refine.sh       && node $B/compare-perturb.js $B/ref-orig.jsonl $B/ref-opt.jsonl $B/refine-compare.csv
# bisect and ablation
sh $B/build-bisect.sh && sh $B/run-bisect.sh
sh $B/build-variant.sh fullbudget && sh $B/build-variant.sh equalramp && sh $B/build-variant.sh both
sh $B/run-ablation.sh
# regression fixtures
sh $B/run-fixtures-both.sh && node $B/compare-fixtures.js $B/fixtures-ORIGINAL $B/fixtures-OPTIMIZED $B/fixtures-diff
```

### Raw result files

All under `D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-acf7be2374b92fb29/build/robustness/`:

| File | Content |
| --- | --- |
| `doe-orig.jsonl`, `doe-opt.jsonl` | 64 DOE samples per revision, full worker records (streams, closure, audit, support, metrics) |
| `doe-compare.csv` | per-case DOE comparison |
| `det-orig-1/2.jsonl`, `det-opt-1/2.jsonl` | determinism reruns, 6 cases x 2 x 2 |
| `perturb.json`, `perturb.json.ids` | the 85 pre-declared perturbation case definitions |
| `pert-orig.jsonl`, `pert-opt.jsonl`, `perturb-compare.csv` | perturbation results and comparison |
| `refine.json`, `ref-orig.jsonl`, `ref-opt.jsonl`, `refine-compare.csv` | refinement sweep |
| `long-orig.jsonl` | ORIGINAL at a 400 s deadline for the three deadline cases |
| `bis-r1.jsonl`, `bis-r2.jsonl`, `bis-r3.jsonl` | bisect runs at R1/R2/R3 |
| `abl-fullbudget.jsonl`, `abl-equalramp.jsonl`, `abl-both.jsonl` | ablation runs |
| `fixtures-ORIGINAL/`, `fixtures-OPTIMIZED/` | JUnit XML including `system-out` |
| `fixtures-diff/` | the seven differing stdout blocks, extracted per revision |
| `*.log` | per-run journals (`doe-run.log`, `rest-run.log`, `bisect-run.log`, `ablation-run.log`, `refine-run.log`, `fixtures-*.log`) |
| `runner/RobustRunner.java`, `doe.js`, `perturb.js`, `compare-*.js`, `*.sh` | the harness, review-only, outside `src/` |

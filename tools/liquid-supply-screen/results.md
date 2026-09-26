# R6 — the request-only liquid-supply screen, implemented and verified

Measured. The screen is compiled and shipped, the suite is green apart from one pre-existing unrelated
failure, and the 405-case ten-worker verification passes all three criteria (§4). The 252-case `g4fresh`
transfer check reproduces the analysis exactly (§5).

A side draw can only remove liquid that has already reached its tray. R6
(`tools/infeasible-screen/results.md` on `claude/v4-r6-infeasible-screen`) showed that the worst
cumulative-draw to liquid-supply ratio over the trays separates the solvable population from the unsolvable
one well enough to be worth typing, and that every term in it is readable from the authored request. This
study implements that screen, pins it with unit tests and measures it against the archived classical control.

Reproduce with

```
python tools/liquid-supply-screen/verify.py prepare
python tools/liquid-supply-screen/verify.py run     --label changed
python tools/liquid-supply-screen/verify.py compare --label changed
python tools/liquid-supply-screen/verify.py transfer
```

Every number below comes from `verification.json`, written by `compare`, or from
`build/liquid-supply-screen/{expected,transfer}.json`, written by `prepare` and `transfer` from recorded
journals and the frozen input population only.

## 1. The screen

`V3LiquidSupplyScreen.evaluate` computes, from the authored `V3ColumnInput` alone:

```
F = sum feed component flows ;  S = sum steam flows ;  R = organic reflux ratio
D_max = max(0, F + S - sum_d)                                  // bottoms cannot be negative
for t in 1..stageCount:                                        // tray 1 = top
    demand(t) = sum of draw rates on trays <= t
    Q_pa(t)   = sum over pumparounds of max(0, -trayDutyWatts(tau)) for tau in 1..t
    supply(t) = R*D_max + S + Q_pa(t)/30000.0 + (t >= feedStageNumber ? F : 0)
rho = max over t with demand(t) > 0 of demand(t)/supply(t)      // 0 when there are no draws
```

No flash, no property package, no solve state. `30000.0 J/mol` is
`V3LiquidSupplyScreen.PUMPAROUND_LATENT_HEAT_JOULES_PER_MOL`, the latent-heat credit that converts authored
cooling into extra liquid. Real tray heats of vaporisation in this package run 30–80 kJ/mol, so the constant
converts a given duty into the largest plausible amount of liquid; **smaller is safer**, because it credits
more liquid, raises the supply and fires less often.

Two tiers on the one statistic:

| tier | threshold | claim | detail wording |
| --- | --- | --- | --- |
| necessary | `rho >= 1` | the liquid balance cannot close; this is physics | `... no liquid balance closes at or above 1.000` |
| calibrated | `rho >= ratio`, default 0.30 | the request is outside the solver's measured envelope | `... the solver's demonstrated liquid-supply envelope is 0.3000` |

Both publish `INFEASIBLE_SPECIFICATION` naming the measured ratio, the limiting tray and the threshold, e.g.

```
V3 authored side draws withdraw 0.4780 of the liquid that reflux, feed and authored pumparound
condensation can deliver to tray 1; the solver's demonstrated liquid-supply envelope is 0.3000
```

Wording is deliberately different between the tiers: one states a closed balance, the other a measured
envelope, and a reader has to be able to tell which claim was made.

## 2. Where it runs, and what it is threaded through

| file | change |
| --- | --- |
| `V3LiquidSupplyScreen.java` | new; the statistic, the two tiers, the detail builder, the ratio validator |
| `V3ColumnCalculator.java:calculate(input, control, mode, policy, ratio)` | classical entry: the screen runs immediately after the existing `totalDraw >= totalFeed` gate, which it generalises from the column's total balance to each tray's own supply, and **before** `staticCoolingAdmission`, the first thing in the file that flashes |
| `V3ColumnCalculator.java:calculate(..., options, model, observer, ratio)` | learned entry: the screen runs after `validateInput` and before the neural candidate loop, so both routes publish the identical typed failure |
| `V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO` | 0.30; every pre-existing signature delegates to it |
| new public overloads | `calculate(input, control, cutoff, closure, ratio)` and `calculate(input, control, cutoff, closure, options, model, ratio)` |
| `CreateChemE.java` | `[columnV3] columnV3LiquidSupplyScreenRatio`, `ModConfigSpec` double in [0, 1], default 0.30, comment stating the calibration and that 0 disables |
| `ProcessSolveServices.java` | read at admission beside the closure, carried on the immutable `V3ColumnCommand` and revalidated there |

`0` disables the calibrated tier and leaves only `rho >= 1`. That switch is not decoration: the project's
goal is to learn to solve exactly these draw-wall specifications, and a research probe has to be able to hand
one to the raw solver.

Because the screen answers before any flash, a screened request publishes **0 Newton iterations, 0 residual
evaluations and 0 linear solves** on an `input/liquid-supply-N` admission path. `V3LiquidSupplyScreenTest`
pins that with a request naming a property package that was never registered: any thermodynamic evaluation on
the way to the answer would have produced a property failure instead.

Not changed: digests, formulation or assumptions revisions, closure, truncation support rules, ramp
schedules, the neural budget, acceptance tolerances, or any other gate.

## 3. Suite

`572 tests completed, 1 failed` — only the pre-existing, unrelated
`V3SideDrawCodecTest.malformedAndOversizedListsAreRejectedBeforeAllocationOrSilentMigration` (Netty
`DecoderException` where the test expects `IndexOutOfBoundsException`), which fails identically on the base
commit. The eight new tests in `V3LiquidSupplyScreenTest` cover the calibrated tier firing with its detail,
the necessary tier firing with the calibrated ratio disabled, a disabled ratio admitting a request between the
two thresholds, the verdict preceding every thermodynamic evaluation, pumparound cooling raising the supply,
the learned and classical routes agreeing verbatim, a drawless request never being screened, and out-of-range
ratios being rejected.

**Two existing fixtures moved, and neither is a false positive.** Both author deliberately over-specified
slates and both already failed before this change; what they pin is the typed-failure contract the solver
honours when it is made to attempt the geometry.

| test | what it authors | rho | resolution |
| --- | --- | ---: | --- |
| `V3SideDrawCalculatorTest.anOverSpecifiedProductSlateAttemptsRequestedGeometryAndNamesAnAuthoredTray` | 496/653/149 kmol/h from trays 8/15/22, 49.7 % of the feed moles | 0.4944 at tray 22 | asserts the screen's verdict on the default path, keeps its original contract at ratio 0 |
| `V3SideDrawCalculatorTest.legalNearFeedDrawNeverBecomesInvalidInputWhenTheDrawBlindSeedIsAvailable` | 99 of 100 mol/s on tray 1 | 0.9706 at tray 1 | same |

No pinned **success** fixture is affected. The two qualified draw lanes in the same class clear the threshold
comfortably: the 0.25x draws-only case sits at 0.071 (a 4.2x margin) and the 0.40x draws-plus-coolers case
clears it on its authored pumparound credit — which is the screen's physics working, since that case is the
measured demonstration that literature draw rates need their literature pumparounds.

## 4. Verification — 405 validation inputs, ten workers, all three criteria pass

`tools/path-dependent-infeasible/java/V3ClassicalOnlyProbe.java` is **reused unchanged**: a classical-only
(`CURRENT_ONLY`) terminal-status recorder under the campaign's ten workers, 30 s request deadline and 4 GiB
heap. `verify.py run` compiles the self-contained V3 package with Gson as the only classpath entry, so no
stale project bytecode can satisfy a dependency. Inputs are the frozen 405-case population copied out of the
sealed worktree into `build/liquid-supply-screen/inputs-405.jsonl`; the control is
`build/neural-capacity-followup/v1/validation-case-evidence.jsonl`, pipeline `F0`, `modes.current`, read-only,
block-averaged (the two blocks disagree on no status).

Executed with the machine otherwise idle: 405/405 cases on 10 distinct worker threads, **167.6 s wall**.

| status | archived control | after |
| --- | ---: | ---: |
| `ACCEPTED` | 115 | 115 |
| `NONCONVERGENCE` | 160 | 256 |
| `INFEASIBLE_SPECIFICATION` | 108 | 16 |
| `LINEAR_SOLVE_FAILURE` | 9 | 6 |
| `DEADLINE_EXCEEDED` | 9 | 8 |
| `ACCEPTANCE_AUDIT_FAILURE` | 4 | 4 |

### Criterion 1 — exactly the expected caught set, with the analysis's own numbers — **pass**

16 ids caught, 0 missing, 0 unexpected. Every one carries the liquid-supply detail, publishes 0 Newton
iterations, and the ratio and limiting tray it prints agree with the independent Python port of the screen to
the four significant figures the detail formats (`detailMismatches` empty). 14 of the 16 were not already
typed `INFEASIBLE_SPECIFICATION` by the control, reproducing R6's `caughtNotAlreadyTyped = 14`.

| id | rho | limiting tray | control status | archived ms | measured ms |
| --- | ---: | ---: | --- | ---: | ---: |
| gd-s24-w0-p3-d1-r00 | 0.5261 | 9 | LINEAR_SOLVE_FAILURE | 20247.0 | 0.0805 |
| gd-s45-w1-p1-d2-r00 | 0.3256 | 30 | NONCONVERGENCE | 17586.2 | 0.1975 |
| gd-s59-w0-p1-d1-r00 | 2.5063 | 17 | NONCONVERGENCE | 15705.5 | 0.1176 |
| gd-s10-w1-p1-d2-r00 | 0.4278 | 7 | NONCONVERGENCE | 10094.8 | 0.0802 |
| gd-s45-w1-p4-d3-r00 | 0.3528 | 36 | NONCONVERGENCE | 9498.0 | 0.0996 |
| gd-s24-w0-p1-d2-r00 | 0.3989 | 4 | NONCONVERGENCE | 8615.9 | 0.0359 |
| gd-s52-w0-p0-d3-r01 | 0.6791 | 42 | NONCONVERGENCE | 7908.6 | 0.0905 |
| gd-s24-w0-p1-d3-r00 | 0.3090 | 12 | NONCONVERGENCE | 7083.4 | 0.0718 |
| gd-s24-w1-p0-d3-r00 | 0.6276 | 17 | LINEAR_SOLVE_FAILURE | 6687.6 | 0.0548 |
| gd-s45-w0-p1-d3-r00 | 0.7165 | 22 | LINEAR_SOLVE_FAILURE | 5615.7 | 0.0669 |
| gd-s52-w1-p2-d2-r00 | 0.8391 | 7 | NONCONVERGENCE | 3836.6 | 0.0511 |
| gd-s24-w0-p3-d2-r00 | 0.5318 | 20 | NONCONVERGENCE | 3435.4 | 0.0981 |
| gd-s52-w0-p3-d2-r00 | 0.5895 | 25 | INFEASIBLE_SPECIFICATION | 3234.3 | 0.0363 |
| gd-s31-w0-p0-d3-r01 | 0.4518 | 13 | NONCONVERGENCE | 1648.2 | 0.0577 |
| gd-s10-w0-p4-d3-r00 | 0.4780 | 1 | INFEASIBLE_SPECIFICATION | 1605.2 | 0.0449 |
| gd-s03-w0-p0-d3-r00 | 0.6922 | 2 | NONCONVERGENCE | 469.5 | 0.1034 |

The lowest caught ratio is 0.3090 and the highest protected (ever-solved or advisory) ratio on this
population is **0.21759** — R6's published 0.2176 reproduces exactly, so the operating margin is 1.38x.

### Criterion 2 — zero false positives, nothing else moved — **pass**

* All **110** archived classical strict successes are still `ACCEPTED`, and all 110 ran the **identical
  number of Newton iterations** as the archived control (`newtonIterationDrift` empty). The campaign's
  strict/advisory split is an acceptance-audit value (`WATER_DEW_POINT` above its limit) that the reused probe
  does not record per case; matching the Newton trajectory length shows the same solve was performed, and the
  screen cannot influence an audit it never reaches — it either answers before the solve or does nothing.
* All 5 archived advisory acceptances are still `ACCEPTED`.
* `unexpectedTransitions` is empty. Every status change is either one of the 16 caught, or one of the 108 the
  base branch already retyped from `INFEASIBLE_SPECIFICATION` to `NONCONVERGENCE` (106 of the 108; the other
  2 are in the caught set and stay typed infeasible for a request-only reason instead of a path-dependent one).
* One case is reported separately rather than absorbed: `gd-s59-w1-p2-d1-r01` was `DEADLINE_EXCEEDED` at
  30002.8 ms in the archived control and finished here at 29463.5 ms, 539 ms inside the same 30 s deadline,
  publishing `NONCONVERGENCE` with the same draw-wall stop ("side draw on authored tray 53 requests
  502.047 kmol/h; final internal liquid 998.439 kmol/h"). Its rho is 0.0626, nowhere near the screen, and it
  was never screened. A wall-clock deadline is not deterministic, and this run had 123 s less work in the
  ten-worker pool than the control did, so a boundary case landing on the other side of it is the expected
  consequence of the screen rather than a defect in it.

### Criterion 3 — time reclaimed — **pass**

| quantity | value |
| --- | ---: |
| archived control time over the 16 caught ids | **123.272 s** |
| measured time over the same 16 | **0.0013 s** |
| reclaimed | **123.271 s** = 7.59 % of the 1623.99 s the control spent on all 405 |
| slowest caught case now | 0.1975 ms |

R6 predicted 123.3 s at this operating point; the measured archived total is 123.272 s. The 16 cases now cost
about 81 microseconds each, against the 19.5 ms floor R6 identified for a typed request-only failure — the
screen is well under it because it publishes before the failure-object machinery does any real work.

## 5. Transfer check — 252-case `g4fresh` holdout

Reachable. `.neural-cache/unified-evaluation-v1/dependencies.zip` in the sealed worktree carries
`fresh-current/cases.jsonl` plus `fresh-{transformer,mlp,nearest-k1,gen3-factorized}/evaluation.jsonl`;
`verify.py transfer` extracts only those members, only under `%TEMP%`, and deletes them afterwards. Ever-solved
is any `success` over those five runs.

| quantity | this study | R6 |
| --- | ---: | ---: |
| cases | 252 | 252 |
| ever solved / never solved | 119 / 133 | 119 / 133 |
| caught at 0.30 | **11** | 11 |
| falsely typed solved | **0** | 0 |
| worst solved ratio | 0.227078 | 0.2271 |
| margin at 0.30 | 1.321x | — |

| id | rho | limiting tray | classical status | classical ms |
| --- | ---: | ---: | --- | ---: |
| g4fresh-s03-w1-p1-d1-r01 | 0.3070 | 2 | NONCONVERGENCE | 7478.8 |
| g4fresh-s07-w0-p3-d2-r00 | 0.6122 | 3 | NONCONVERGENCE | 3859.1 |
| g4fresh-s08-w0-p4-d1-r00 | 0.4352 | 2 | INFEASIBLE_SPECIFICATION | 153.5 |
| g4fresh-s14-w0-p3-d2-r00 | 0.4645 | 3 | INFEASIBLE_SPECIFICATION | 226.5 |
| g4fresh-s19-w0-p1-d3-r00 | 0.6349 | 16 | INFEASIBLE_SPECIFICATION | 495.4 |
| g4fresh-s20-w0-p0-d3-r00 | 1.0990 | 15 | NONCONVERGENCE | 1813.3 |
| g4fresh-s23-w0-p0-d3-r00 | 0.8207 | 7 | NONCONVERGENCE | 2001.9 |
| g4fresh-s35-w0-p0-d3-r00 | 3.2257 | 17 | DEADLINE_EXCEEDED | 30000.6 |
| g4fresh-s41-w0-p2-d3-r00 | 0.3396 | 29 | NONCONVERGENCE | 4223.8 |
| g4fresh-s44-w0-p1-d2-r00 | 0.6495 | 8 | DEADLINE_EXCEEDED | 30001.5 |
| g4fresh-s56-w0-p0-d1-r01 | 2.1971 | 29 | NONCONVERGENCE | 6912.1 |

## 6. Deviations from the Python analysis

1. **None in the statistic.** On the 405-case population the Java screen's published ratio and limiting tray
   agree with the independent Python port on all 16 caught cases, to the four significant figures the detail
   string prints. The Java port accumulates draws tray by tray where the Python walks a sorted list, and
   iterates a pumparound's zone rather than closing over `max(0, min(hi, t) - lo + 1)`; both reduce to the same
   sum, and the measured agreement is the evidence.
2. **`g4fresh` control timings differ, and the source explains it.** This study reads the classical timings
   from `dependencies.zip` (`fresh-current/cases.jsonl`); R6 read them from `study.zip`. Those are two
   recorded runs of the same population, so the totals differ — 1115.6 s here against R6's 866.0 s over all
   252, and 87.2 s against 77.4 s over the caught 11. The *classification* is identical: same 11 ids, same
   119/133 split, same 0.2271 worst solved ratio.
3. **R6's `controlFastestCaseMillis = 19.5 ms` is not the floor for a screened request.** Measured, a
   screened case costs about 0.08 ms; the 19.5 ms figure is a whole campaign case, including harness work this
   path never reaches.

## 7. Caveats

1. `rho >= 0.30` is calibration, not proof, and the shipped code says so in its own detail string. The
   physical claim holds only at `rho >= 1`, which on the 405-case population catches exactly one id. Re-run
   `verify.py prepare` against any new validation population before trusting the threshold on it, and keep
   `columnV3LiquidSupplyScreenRatio = 0` in mind as the escape hatch for a request the screen gets wrong.
2. The margin is 1.38x on the 405 set and 1.32x on `g4fresh`. R6 measured the first false positive at 0.20 on
   both populations; do not lower the default below 0.2176 without re-measuring.
3. The latent-heat credit is a single hand-chosen constant rather than a per-case value from the property
   package. R6 measured the sensitivity at this threshold — 20 kJ/mol catches 14, 30 catches 16, 60 catches
   19, 100 catches 27, all with zero false positives — and the worst protected ratio does not move between 20
   and 60 kJ/mol. A per-case lambda would be defensible but the boundary does not need it.
4. The screen only sees draws. R6 found 3 never-solved cases with neither draws nor heat loops (45.3 s) that
   no request-only feature examined separates from the 17 solvable cases in the same cell; this screen cannot
   reach them, and no draw or heat screen can reach much beyond ~15 % of the total classical time.
5. The two moved `V3SideDrawCalculatorTest` cases are worth a second reader's eye. The judgement made here is
   that a fixture pinning *which* failure an over-specified request produces is not a zero-false-positive
   check, and that keeping its contract on the raw solver at ratio 0 preserves what it was qualifying. A
   reviewer who disagrees should say so before this branch merges.

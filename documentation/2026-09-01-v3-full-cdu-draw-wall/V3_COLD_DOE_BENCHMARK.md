# V3 Cold-Solve Definitive Screening Benchmark

Date: 2026-09-02
Compared revisions:

- Current branch: `cc1affb` (`Preserve dry side draws through pressure continuation`)
- Baseline: `main` at `bdfeff1`

## Purpose

This benchmark compares cold V3 column-solver convergence and calculation time between the current branch and `main` across pressure, condenser temperature, combined three-side-draw loading, and steam condition.

The benchmark specifically tests whether the branch's continuation/ramp work improves the previously fragile low-pressure dry side-draw region without introducing broader regressions.

## Fixed model settings

Each calculation uses the registered Tia Juana Light 30-tray CDU fixture:

- Feed: 2,610.7 kmol/h equivalent Tia Juana Light crude.
- Feed temperature: 638.15 K.
- Feed tray: 24 of 30 trays.
- Pressure drop: 750 Pa/tray.
- Reflux ratio: 2.0.
- Reboiler duty: 8 MW.
- Three side draws: authored trays 8, 15, and 22, allocated in the existing 496:653:149 proportion.
- Draw loading: the combined three-draw flow expressed as a fraction of authored feed.
- Steam condition: either dry or a fixed 8 mol/s, 450 K sump-steam feed at tray 31.

“Cold” means every measurement invokes the stateless calculator from a newly constructed input with no retained solver seed or warm numerical state. JVM classes and immutable property-package data remain loaded during repeated timing runs; process startup is deliberately excluded from calculation timing.

## Experimental design

The quantitative factors were screened with a three-factor, seven-run definitive screening design (DSD), blocked by steam condition. DSDs use three levels and screen main effects in the presence of second-order effects using one more than twice as many runs as factors. See Jones and Nachtsheim, [*A Class of Three-Level Designs for Definitive Screening in the Presence of Second-Order Effects*](https://asq.org/quality-resources/articles/a-class-of-three-level-designs-for-definitive-screening-in-the-presence-of-second-order-effects?id=f41587dd667a449a9523fc7e4411266c).

Quantitative factor levels:

| Factor | Low (-1) | Center (0) | High (+1) |
|---|---:|---:|---:|
| Top pressure | 60 kPa | 155 kPa | 250 kPa |
| Condenser temperature | 50 °C | 75 °C | 100 °C |
| Combined draw loading | 5% | 22.5% | 40% |

The coded DSD rows were:

| Run | Pressure | Temperature | Loading |
|---:|---:|---:|---:|
| 1 | 0 | +1 | -1 |
| 2 | 0 | -1 | +1 |
| 3 | +1 | 0 | -1 |
| 4 | -1 | 0 | +1 |
| 5 | -1 | +1 | 0 |
| 6 | +1 | -1 | 0 |
| 7 | 0 | 0 | 0 |

The DSD was run once dry and once with steam (14 cells). Two targeted augmentations were added:

1. A seven-point dry and steam pressure-knot sweep at 80, 90, 95, 99, 100, 101, and 105 kPa, with 75 °C and 22.5% draw loading (14 cells).
2. A 2×2 temperature/loading interaction augmentation at 100 kPa for dry and steam cases: 50/100 °C × 5/40% loading (8 cells).

This produced 36 paired cold-solve cells per revision. The screen ran in isolated worktrees and logical shards; up to three independent workers ran concurrently. Every case had a 60-second calculator-level ceiling. No case timed out.

Screen timings are not reported as performance evidence because the shards shared host CPU. Convergence outcomes are deterministic and are valid screening results.

## Convergence summary

| Block | Current branch | `main` |
|---|---:|---:|
| Dry DSD | 4/7 success | 1/7 success |
| Steam DSD | 3/7 | 3/7 |
| Dry pressure-knot sweep | 7/7 | 0/7 |
| Steam pressure-knot sweep | 0/7 | 0/7 |
| 100 kPa interaction augmentation | 3/8 | 3/8 |
| **All 36 cells** | **17 success / 19 failure** | **7 success / 29 failure** |

All ten changed outcomes favor the current branch. There were no branch-only failures.

### Branch-only convergence wins

The branch converged where `main` returned `NONCONVERGENCE` for:

- 155 kPa, 100 °C, 5% loading, dry.
- 250 kPa, 50 °C, 22.5% loading, dry.
- Every dry pressure-knot case from 80 through 105 kPa at 75 °C and 22.5% loading.

The pressure-knot result is the strongest evidence for the branch's dry side-draw continuation work: all seven near-threshold dry cases converge on the branch, while none converge on `main`.

### Factor interactions observed

- **Steam × branch interaction:** dry success rate rises from 3/18 on `main` to 13/18 on the branch. Steam success rate remains 4/18 on both revisions. The dry-side-draw correction is effective, but it does not solve the steam-bearing path.
- **Pressure × dry side draws:** at 75 °C and 22.5% loading, the branch is continuous across the 80–105 kPa knot. `main` fails at every sampled pressure in that interval.
- **Loading × temperature at 100 kPa:** 5% dry loading converges at both 50 °C and 100 °C on both revisions; 40% loading fails at both temperatures on both revisions.
- **Steam × condenser temperature:** with steam and 5% loading at 100 kPa, 50 °C converges but 100 °C fails. At 40% loading, all tested steam cases fail. This is the dominant unresolved interaction.
- **Low-pressure/high-loading limitation:** both revisions still fail in the 60 kPa high-loading DSD cells. The branch often expends its extended recovery budget before returning a typed `NONCONVERGENCE`.

## Paired outcome matrix

Legend: `S` = audited success; `F` = typed `NONCONVERGENCE`. No timeouts or other terminal categories occurred.

| ID | Design | kPa | °C | Draw | Steam | Branch | Main |
|---:|---|---:|---:|---:|:---:|:---:|:---:|
| 0 | DSD | 155 | 100 | 5% | no | S | F |
| 1 | DSD | 155 | 50 | 40% | no | F | F |
| 2 | DSD | 250 | 75 | 5% | no | S | S |
| 3 | DSD | 60 | 75 | 40% | no | F | F |
| 4 | DSD | 60 | 100 | 22.5% | no | F | F |
| 5 | DSD | 250 | 50 | 22.5% | no | S | F |
| 6 | DSD | 155 | 75 | 22.5% | no | S | F |
| 7 | DSD | 155 | 100 | 5% | yes | F | F |
| 8 | DSD | 155 | 50 | 40% | yes | F | F |
| 9 | DSD | 250 | 75 | 5% | yes | S | S |
| 10 | DSD | 60 | 75 | 40% | yes | F | F |
| 11 | DSD | 60 | 100 | 22.5% | yes | F | F |
| 12 | DSD | 250 | 50 | 22.5% | yes | S | S |
| 13 | DSD | 155 | 75 | 22.5% | yes | S | S |
| 14 | pressure knot | 80 | 75 | 22.5% | no | S | F |
| 15 | pressure knot | 90 | 75 | 22.5% | no | S | F |
| 16 | pressure knot | 95 | 75 | 22.5% | no | S | F |
| 17 | pressure knot | 99 | 75 | 22.5% | no | S | F |
| 18 | pressure knot | 100 | 75 | 22.5% | no | S | F |
| 19 | pressure knot | 101 | 75 | 22.5% | no | S | F |
| 20 | pressure knot | 105 | 75 | 22.5% | no | S | F |
| 21 | pressure knot | 80 | 75 | 22.5% | yes | F | F |
| 22 | pressure knot | 90 | 75 | 22.5% | yes | F | F |
| 23 | pressure knot | 95 | 75 | 22.5% | yes | F | F |
| 24 | pressure knot | 99 | 75 | 22.5% | yes | F | F |
| 25 | pressure knot | 100 | 75 | 22.5% | yes | F | F |
| 26 | pressure knot | 101 | 75 | 22.5% | yes | F | F |
| 27 | pressure knot | 105 | 75 | 22.5% | yes | F | F |
| 28 | 100 kPa interaction | 100 | 50 | 5% | no | S | S |
| 29 | 100 kPa interaction | 100 | 50 | 40% | no | F | F |
| 30 | 100 kPa interaction | 100 | 100 | 5% | no | S | S |
| 31 | 100 kPa interaction | 100 | 100 | 40% | no | F | F |
| 32 | 100 kPa interaction | 100 | 50 | 5% | yes | S | S |
| 33 | 100 kPa interaction | 100 | 50 | 40% | yes | F | F |
| 34 | 100 kPa interaction | 100 | 100 | 5% | yes | F | F |
| 35 | 100 kPa interaction | 100 | 100 | 40% | yes | F | F |

## Isolated performance confirmation

Only cells that converged on both revisions were timed for comparative performance. The benchmark executed serially with no competing solver workers. Each timing is calculation-only and a new cold solver call. Three repetitions were collected per shared-success cell, reversing cell order on the middle pass. All 42 of these confirmation calls succeeded.

| Cell | Branch samples (ms) | Branch median | Main samples (ms) | Main median | Branch delta |
|---|---|---:|---|---:|---:|
| 250 kPa, 75 °C, 5%, dry | 5617.4, 5466.8, 5425.4 | 5466.8 | 5228.0, 6416.9, 5671.5 | 5671.5 | -3.6% |
| 100 kPa, 50 °C, 5%, dry | 7973.2, 8958.1, 8904.8 | 8904.8 | 8165.0, 9681.6, 9627.4 | 9627.4 | -7.5% |
| 100 kPa, 100 °C, 5%, dry | 12980.7, 14194.4, 14140.3 | 14140.3 | 13199.7, 15003.4, 14587.8 | 14587.8 | -3.1% |
| 250 kPa, 75 °C, 5%, steam | 7723.3, 8551.2, 8417.8 | 8417.8 | 8669.6, 11206.4, 10046.1 | 10046.1 | -16.2% |
| 155 kPa, 75 °C, 22.5%, steam | 11499.0, 12995.1, 12617.5 | 12617.5 | 14424.5, 16084.9, 14742.4 | 14742.4 | -14.4% |
| 100 kPa, 50 °C, 5%, steam | 21003.2, 21083.3, 20244.8 | 21003.2 | 21117.2, 21618.1, 22070.8 | 21618.1 | -2.8% |

### Confirmed adverse steam interaction

The 250 kPa / 50 °C / 22.5% draw-loading steam case initially appeared slower on the branch. It was rerun for five additional serial repetitions to determine whether that was noise.

| Revision | Five samples (ms) | Median | Mean |
|---|---|---:|---:|
| Branch | 11657.0, 11174.5, 11298.0, 11262.9, 11081.7 | 11262.9 | 11294.8 |
| Main | 8263.4, 7792.8, 8493.2, 8277.1, 8578.7 | 8277.1 | 8281.0 |

The branch is **36.1% slower by median** in this cold, higher-loading steam case, despite converging in both revisions. The branch uses 23 Newton iterations versus 26 on `main`, so the time cost is not explained by iteration count alone; the newer continuation/recovery path has additional per-iteration or per-rung work.

## Conclusions

1. The branch materially improves dry side-draw convergence, especially across the 80–105 kPa pressure knot. This validates retaining the dry-side-draw pressure-continuation correction.
2. The branch has no observed convergence regression in the 36-cell DSD/augmentation screen.
3. Steam-bearing low-pressure and high-temperature cases remain the main convergence limitation. The dry-side-draw homotopy fix should not be presented as a general steam-path solution.
4. Performance is generally neutral to favorable for six common-success cells, but not universally. The confirmed 36.1% slowdown at 250 kPa / 50 °C / 22.5% loading with steam is a concrete optimization target.
5. Follow-up work should profile the steam-bearing feature-ramp/recovery path separately, with particular attention to the cold-condenser, moderate-draw region. It should not relax physical acceptance criteria to mask that cost.

## Reproducibility and limitations

- The benchmark used temporary isolated worktrees and disposable JUnit harnesses. They were removed after data collection; no benchmark harness or production source was added to the branch.
- All calculator results are from registered Peng–Robinson Tia Juana Light data and this fixed 30-tray topology. Results should not be generalized to other crude assays, draw allocations, tray counts, steam rates, or duties without a new screen.
- Parallel DSD timing is intentionally excluded from performance comparison due to shared CPU contention. Convergence outcomes remain valid.
- Serialized performance measurements use three repetitions per shared-success cell, except the adverse steam interaction, which received five additional repetitions. They demonstrate practical timing behavior, not a hardware-independent performance guarantee.

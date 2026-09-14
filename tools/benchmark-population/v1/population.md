# Cleaned benchmark populations and the statistics re-based on them

Every benchmark denominator in this project counted requests the shipped solver refuses by construction, and requests the retired state-dependent heat gates typed infeasible and nothing has ever solved. This study removes exactly those and re-bases the headline numbers. No campaign was run: every count below is an archived per-case outcome restricted to a smaller id set.

## 1. The rule

Applied in this order, recorded per id in `v1/<population>/exclusions.json`.

**`REQUEST_ONLY_TYPED`** — the promoted branch types the input `INFEASIBLE_SPECIFICATION` from the specification alone, at the production liquid-supply ratio 0.30. Measured by `tools/benchmark-population/java/V3RequestAdmissionProbe.java`, which calls the package-private `V3ColumnCalculator.requestOnlyAdmission(input, ratio)` — the one method the production `calculate` path calls for its three request-only gates (`totalDraw >= totalFeed`, `V3LiquidSupplyScreen`, `staticCoolingAdmission`). The probe runs no solve; the only thermodynamics it touches is the single feed flash the static cooling admission performs on its own. All 3,297 inputs take 1.7 s.

**`STATE_GATE_NEVER_SOLVED`** — the archived classical control typed the input `INFEASIBLE_SPECIFICATION` while the retired `condensationCappedTray` / `requireCoolingBelowBaseCondenserDuty` bounds still published that code, **and** no archived arm, in any mode or block, reached strict or advisory on it.

**Retained.** Advisory-only and never-converged cases stay in. They are the open set, listed per population in `v1/<population>/open-set.json`.

## 2. Populations

| Population | n | `REQUEST_ONLY_TYPED` | `STATE_GATE_NEVER_SOLVED` | cleaned | archived arms | strict ceiling | open set |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `validation` | 405 | 16 | 59 | **330** | 198 | 214 | 116 (101 never converged, 15 advisory) |
| `g4fresh` | 252 | 11 | 49 | **192** | 17 | 110 | 82 (73 never converged, 9 advisory) |
| `g6fresh` | 252 | 10 | 36 | **206** | 21 | 87 | 119 (113 never converged, 6 advisory) |
| `historical-test` | 395 | 20 | 48 | **327** | 12 | 186 | 141 (134 never converged, 7 advisory) |
| `train` | 1993 | 96 | 348 | **1549** | 14 | 813 | 736 (675 never converged, 61 advisory) |

Every `REQUEST_ONLY_TYPED` exclusion in all five populations is the liquid-supply screen. `totalDraw >= totalFeed` fires on none of the 3,297 inputs — the design generator already excludes that cell at preflight — and the static cooling admission fires on none either.

## 3. Promoted pipeline on the cleaned validation set

Denominator 405 -> **330**. Evidence: `tools/transformer-promotion/evidence/case-block{1,2}.jsonl.gz`.

| Route | Block | strict | of 405 | of 330 |
| --- | --- | ---: | ---: | ---: |
| `classical` | 1 | 110 | 27.2% | **33.3%** |
| `classical` | 2 | 110 | 27.2% | **33.3%** |
| `LNN_ONLY` | 1 | 162 | 40.0% | **49.1%** |
| `LNN_ONLY` | 2 | 162 | 40.0% | **49.1%** |
| `LNN_FIRST` | 1 | 180 | 44.4% | **54.5%** |
| `LNN_FIRST` | 2 | 180 | 44.4% | **54.5%** |

No strict case was removed by the cleaning, in any route or block, so the numerators are the archived numerators unchanged and the whole movement is in the denominator.

### Ceiling

Strict by at least one of 198 archived arm-mode-block combinations: 214 cases, 52.8% of 405 -> **64.8% of 330**.

### All-case request time

All-case timing includes failures, so removing requests that fail in microseconds (the screen) or spend a full budget failing (the state-gate set) moves it.

| Route | Block | mean of 405 | mean of 330 | median of 405 | median of 330 |
| --- | --- | ---: | ---: | ---: | ---: |
| `classical` | 1 | 3809.9 | **4516.9** | 1742.0 | **2555.3** |
| `classical` | 2 | 3840.8 | **4550.3** | 1708.7 | **2530.5** |
| `LNN_ONLY` | 1 | 877.4 | **853.4** | 595.1 | **508.0** |
| `LNN_ONLY` | 2 | 879.8 | **853.2** | 583.3 | **510.3** |
| `LNN_FIRST` | 1 | 3559.7 | **3985.6** | 1392.7 | **1296.8** |
| `LNN_FIRST` | 2 | 3578.2 | **3999.3** | 1404.7 | **1272.1** |

### Common-success paired timing

Unchanged by construction: a paired comparison runs on columns both routes solve strictly, and no strictly solved column is excluded. Verified rather than asserted — `unchangedByCleaning` is true for all four comparisons in `statistics.json`.

The differences reproduce the promotion campaign's `versusClassicalOnCommonStrict` exactly. The absolute means differ from `tools/transformer-promotion/results.md` §5 because that table pairs a classical mean taken over the *learned* route's own strict set (180 and 162 cases) with a difference taken over the common set; both columns here are on the common set.

| Comparison | cases | classical mean | learned mean | mean difference | median difference |
| --- | ---: | ---: | ---: | ---: | ---: |
| `LNN_FIRST:block1` | 110 | 1785.7 | 876.1 | -909.6 | -591.2 |
| `LNN_FIRST:block2` | 110 | 1798.1 | 863.6 | -934.5 | -563.0 |
| `LNN_ONLY:block1` | 92 | 1642.6 | 291.2 | -1351.4 | -739.3 |
| `LNN_ONLY:block2` | 92 | 1669.8 | 292.8 | -1377.0 | -746.4 |

## 4. The R2 budget campaign, re-based

Evidence: `tools/neural-budget/evidence/case-block{1,2}-F0-*.jsonl.gz`. Same restriction, same outcomes.

| Pipeline | LNN_ONLY b1/b2 | of 330 | LNN_FIRST b1/b2 | of 330 | classical b1/b2 | of 330 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `F0-baseline` | 134 / 134 | **40.6% / 40.6%** | 168 / 168 | **50.9% / 50.9%** | 110 / 110 | **33.3% / 33.3%** |
| `F0-progress` | 149 / 150 | **45.2% / 45.5%** | 176 / 176 | **53.3% / 53.3%** | 110 / 110 | **33.3% / 33.3%** |
| `F0-phase-floor` | 147 / 147 | **44.5% / 44.5%** | 171 / 171 | **51.8% / 51.8%** | 110 / 110 | **33.3% / 33.3%** |
| `F0-progress-phase-floor` | 163 / 164 | **49.4% / 49.7%** | 180 / 180 | **54.5% / 54.5%** | 110 / 110 | **33.3% / 33.3%** |

| Pipeline | FIRST all-case mean of 405, b1/b2 | of 330, b1/b2 |
| --- | ---: | ---: |
| `F0-baseline` | 3771.8 / 3549.6 | **3929.3 / 3704.3** |
| `F0-progress` | 3589.0 / 3355.9 | **3712.7 / 3478.8** |
| `F0-phase-floor` | 3735.4 / 3433.1 | **3829.9 / 3528.0** |
| `F0-progress-phase-floor` | 3590.0 / 3411.8 | **3644.2 / 3469.1** |

## 5. Against the published partition

`V4_LNN_ONLY_GAP_ANALYSIS.md` drew group D1 — typed by the classical control, never *strictly* solved — as 66 ids. This study reproduces that number exactly: `stateGateStrictOnlyTotalIncludingRequestOnly` is 66 for validation, and the ceiling of everything ever measured is the published 214 of 405. The two differ in what they do next:

- 2 of the 66 are also caught by the request-only liquid-supply screen, so they are recorded under `REQUEST_ONLY_TYPED` (which takes precedence, because that is the gate production actually applies) and the state-gate residue is 64;
- 5 of the remaining 64 reached **advisory** on some archived arm. The rule says strict *or* advisory, so those 5 are not excluded; they stay in the open set. They are `gd-s03-w1-p4-d0-r00`, `gd-s10-w1-p4-d2-r00`, `gd-s24-w1-p3-d3-r00`, `gd-s24-w1-p4-d2-r00` and `gd-s59-w1-p4-d2-r00`, with the arms that reached advisory on each recorded in `v1/validation/exclusions.json` under `advisoryRescueEvidence`.

Excluded: 16 + 59 = 75. Cleaned denominator **330**. Under the strict-only reading it would be 405 - 80 = 325.

## 6. Certified TRAIN labels

A certified label sitting on an excluded input would mean the network was fitted to a request production refuses to solve. `classify.py` asserts it rather than assuming it, and refuses to write a manifest if it ever happens.

| Certified set | labels | on an excluded TRAIN input |
| --- | ---: | ---: |
| `N` | 805 | **0** |
| `Nplus1` | 906 | **0** |

The archive names these N and N+1 with 805 and 906 labels (`certified-dataset-manifest.json`), not the 804 / 905 the handoff quoted.

## 7. Generating conditions that are feasible to begin with

`tools/neural/generalized_design.py --request-only-screen-ratio 0.30` rejects sampled conditions the request-only admission would type, at generation time. Off by default: `generate(baseline, 202609104)` still yields the frozen Gen4 pool's 2,809 candidates and 17 preflight exclusions. At 0.30 the base design matrix loses 132 further points, which is exactly the 16 + 20 + 96 requests excluded here from its validation, historical-test and train folds — the Python port of the screen and the Java admission agree case for case on all 3,297 archived requests (`tools/neural/test_generalized_design_request_only.py`).

## 8. Using a cleaned population

See `tools/benchmark-population/README.md`. Both campaign harnesses accept a population path — `python tools/neural-budget/budget_benchmark.py validation --population <path>` and `python tools/transformer-promotion/promotion_native.py block1 --population <path>` — defaulting to the archived 405, so every sealed study reproduces untouched. A requested population must be registered in `v1/manifest.json` by SHA-256, and a non-archived run writes under its own `build/<study>/<rev>/population/<label>/` directory.

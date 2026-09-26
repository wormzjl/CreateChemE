# LNN_ONLY coverage gap on the promoted Transformer initializer

Date: 2026-09-14. Read-only analysis by an Opus subagent over the promotion journals, the R2 trace journals and decode dumps, and the archived campaign journals. Population: the 405 frozen validation inputs (`tools/transformer-promotion/inputs/validation-inputs.jsonl`, SHA-256 `95d773b0...`, identical to the trace-followup inputs). Pooling was hash-verified on all 405 ids for every archive except the hybrid-learning case map (id join only). Primary evidence is the promotion run's own `evaluation.jsonl` per block (SHA-verified against `tools/transformer-promotion/evidence/cache-manifest.json`); where the R2 F0-progress-phase-floor campaign is used instead it is stated.

## 1. Partition (strict in both blocks)

| Group | Definition | n |
|---|---|---:|
| A | LNN_ONLY strict on the promoted path | 162 |
| B | LNN_FIRST strict, LNN_ONLY not: classical rescues | 18 |
| C | not LNN_FIRST strict, but strictly solved by at least one other archived arm (all in ONLY mode) | 34 |
| D | never strictly solved by anything across 33 archived arms | 191 |

Classical is strict on exactly 110 cases in every one of the 33 arms. The ceiling of everything ever measured on these inputs is 214 of 405; the promoted path reaches 180. B is 18 rather than the qualification's 17 because the promotion run read ONLY 162/162 where qualification read 163/164 (the two wall-jitter cases).

## 2. Group B: the 18 classical rescues

`st` stages, `w` steam, `p` heat loops, `d` draws. ONLY wall time and Newton iterations from the promotion journal (blocks 1/2); trace class from the R2 trace journals (16/2000 and 48/6000); `zp b/a` = decoded zero-phase count before/after the phase floor.

| id | st | w | p | d | ONLY ms b1/b2 | it | ONLY stop | trace class | conv. @48/6000 | zp b/a |
|---|--:|--:|--:|--:|--:|--:|---|---|---|--:|
| gd-s17-w0-p0-d0-r00 | 17 | 0 | 0 | 0 | 373 / 393 | 8 | early stall abort | stalled, hopeless | no | 0/0 |
| gd-s17-w0-p1-d1-r00 | 17 | 0 | 1 | 1 | 914 / 930 | 19 | banded-LU tiny pivot | stalled, hopeless, reinserting | no | 1/0 |
| gd-s31-w0-p0-d0-r00 | 31 | 0 | 0 | 0 | 2014 / 2000 | 3 | 2 s wall | reinserting | no | 0/0 |
| gd-s31-w0-p2-d0-r00 | 31 | 0 | 2 | 0 | 647 / 680 | 16 | base cap, extension refused | crawling | yes | 0/0 |
| gd-s31-w0-p2-d1-r00 | 31 | 0 | 2 | 1 | 435 / 443 | 16 | base cap, extension refused | crawling | yes | 0/0 |
| gd-s38-w0-p0-d2-r00 | 38 | 0 | 0 | 2 | 2000 / 2000 | 31/30 | 2 s wall | not traced | - | 0/0 |
| gd-s38-w1-p0-d0-r01 | 38 | 1 | 0 | 0 | 183 / 214 | 8 | early stall abort | reinserting | no | 0/0 |
| gd-s38-w1-p3-d2-r00 | 38 | 1 | 3 | 2 | 1148 / 1185 | 9 | early stall abort | not traced | - | 0/0 |
| gd-s45-w0-p3-d1-r00 | 45 | 0 | 3 | 1 | 2002 / 2012 | 7 | 2 s wall | reinserting | no | 2/0 |
| gd-s45-w1-p0-d0-r01 | 45 | 1 | 0 | 0 | 1863 / 1812 | 1 | no admissible step | stalled, reinserting | no | 1/0 |
| gd-s52-w0-p0-d0-r00 | 52 | 0 | 0 | 0 | 698 / 668 | 8 | early stall abort | stalled, hopeless, reinserting | no | 0/0 |
| gd-s52-w0-p0-d1-r00 | 52 | 0 | 0 | 1 | 363 / 345 | 8 | early stall abort | stalled, hopeless, reinserting | no | 12/0 |
| gd-s52-w0-p1-d0-r01 | 52 | 0 | 1 | 0 | 2000 / 2000 | 23/24 | 2 s wall | stalled, hopeless | no | 0/0 |
| gd-s52-w1-p0-d0-r00 | 52 | 1 | 0 | 0 | 1987 / 2006 | 9 | 2 s wall + no admissible step | stalled, hopeless, reinserting | no | 1/0 |
| gd-s59-w0-p0-d0-r00 | 59 | 0 | 0 | 0 | 2000 / 2000 | 5/6 | 2 s wall | stalled, hopeless, reinserting | no | 1/0 |
| gd-s59-w0-p1-d0-r00 | 59 | 0 | 1 | 0 | 2001 / 2007 | 23/24 | 2 s wall | not traced | - | 0/0 |
| gd-s59-w1-p2-d0-r00 | 59 | 1 | 2 | 0 | 1288 / 1307 | 8 | early stall abort | traced, unclassified | no | 0/0 |
| gd-s59-w1-p4-d1-r00 | 59 | 1 | 4 | 1 | 1454 / 1498 | 8 | early stall abort | stalled, hopeless, reinserting | no | 0/0 |

ONLY time on B: 23.4 s in block 1 (mean 1298 ms, median 1371 ms); 6 of 18 spend the full 2 s. The classical rescue that follows costs 38.5 s (mean 2137 ms, max 8774 ms).

Classical's own route to every B case is the cold stage continuation (4-8-15-30-N), never a plain solve, with a median of 3 terminal Newton iterations; three cases need 0 terminal iterations. Nine need a heat or draw ramp on top of the stage schedule, five need the wet ramp or a steam rung.

| id | classical solvePath | terminal it | ms |
|---|---|--:|--:|
| gd-s17-w0-p0-d0-r00 | cold/stage-continuation/4-8-15-17/fine-fd | 0 | 341 |
| gd-s17-w0-p1-d1-r00 | .../4-8-15-17/fine-fd/draw-ramp-1.0/draws-1/heat-1 | 2 | 660 |
| gd-s31-w0-p0-d0-r00 | .../4-8-15-30-31/fine-fd | 0 | 587 |
| gd-s31-w0-p2-d0-r00 | .../4-8-15-30-31/fine-fd/heat-ramp-1.0/heat-2 | 1 | 2341 |
| gd-s31-w0-p2-d1-r00 | .../4-8-15-30-31/fine-fd/draw-ramp-1.0/draws-1/heat-2 | 4 | 1251 |
| gd-s38-w0-p0-d2-r00 | .../4-8-15-30-38/fine-fd/draw-ramp-1.0/draws-2 | 3 | 2408 |
| gd-s38-w1-p0-d0-r01 | .../4-8-15-30-38/fine-fd/wet-ramp-1.0/steam-1 | 4 | 1550 |
| gd-s38-w1-p3-d2-r00 | .../4-8-15-30-38/fine-fd/draw-ramp-1.0/draws-2/steam-1/heat-3 | 3 | 3881 |
| gd-s45-w0-p3-d1-r00 | .../4-8-15-30-45/fine-fd/draw-ramp-1.0/draws-1/heat-3 | 9 | 3190 |
| gd-s45-w1-p0-d0-r01 | .../4-8-15-30-45/fine-fd/wet-ramp-1.0/steam-1 | 3 | 1120 |
| gd-s52-w0-p0-d0-r00 | .../4-8-15-30-52/fine-fd | 0 | 866 |
| gd-s52-w0-p0-d1-r00 | .../4-8-15-30-52/fine-fd/draw-ramp-1.0/draws-1 | 4 | 8774 |
| gd-s52-w0-p1-d0-r01 | .../4-8-15-30-52/fine-fd/heat-ramp-1.0/heat-1 | 4 | 1440 |
| gd-s52-w1-p0-d0-r00 | .../4-8-15-30-52/fine-fd/wet-ramp-1.0/steam-1 | 3 | 1182 |
| gd-s59-w0-p0-d0-r00 | .../4-8-15-30-59/fine-fd | 3 | 1022 |
| gd-s59-w0-p1-d0-r00 | .../4-8-15-30-59/fine-fd/heat-ramp-1.0/heat-1 | 0 | 1480 |
| gd-s59-w1-p2-d0-r00 | .../4-8-15-30-59/fine-fd/heat-ramp-1.0/steam-1/heat-2 | 4 | 1955 |
| gd-s59-w1-p4-d1-r00 | .../4-8-15-30-59/fine-fd/draw-ramp-1.0/draws-1/steam-1/heat-4 | 5 | 4413 |

Mechanism aggregate (15 of 18 traced; classes overlap): crawling 2, stalled 9, hopeless 8, reinserting 10, not traced 3. Disjoint by priority: crawling 2, hopeless 8, stalled 1, reinserting-only 3, traced-unclassified 1, not traced 3. By stop evidence: early stall abort 7, 2 s wall 6, base cap 2, no admissible step 1, LU pivot 1. Zero B cases exhausted the extended cap.

Three findings inside B:

1. The progress rule's contraction gate is what loses the two crawling cases. Both stop with the bare "iteration budget exhausted": the extension was never granted. Their 8-window residual ratios at iteration 16 are 0.768 and 0.9996, far above the registered 0.5. Under a flat 48-iteration attempt 1 they converge after a support refresh in 14 and 2 further iterations, at 1706 ms and 492 ms total, both inside the 2 s wall. Across all 20 crawling cases in the trace, only 4 would have passed the 0.5 gate.
2. The shipped rules cost coverage the same weights already had. Nine cases are ONLY-strict under at least one sibling F0 budget pipeline but not under the promoted run; three are in B. `gd-s38-w0-p0-d2-r00` is strict under all four sibling pipelines and lost purely at the clock; `gd-s59-w0-p1-d0-r00` is wall jitter; `gd-s38-w1-p3-d2-r00` is strict under baseline and phase-floor and lost under both progress arms, i.e. by the early stall abort.
3. After the phase floor no B case carries an empty decoded phase (zero-decoded phases across the 405 fall from 1245 to 1). What survives on the 18 B references is presence-head component drops: above-floor omissions 319 to 84, 58 of them on `gd-s52-w0-p0-d1-r00`.

Seed quality, median over certified references (F0-progress-phase-floor, block 1): temperature MAE A 6.48 K, B 10.15 K, C 9.42 K, D 12.27 K; max temperature error A 17.1, B 29.9, C 23.9, D 32.6 K. The initial projected max scaled residual does not separate the groups (medians A 20.9, B 24.4, C 18.0, D 17.5).

## 3. Group C: 34 cases some other seed solves

Every arm that solves a C case does so in ONLY mode. Promoted-path ONLY stops on C: early stall abort 22, 2 s wall 6, base cap 4, no admissible step 2. Trace classes over the 28 traced: stalled 18, hopeless 16, reinserting 12, crawling 5. ONLY time on C: 37.9 s in block 1.

| id | st w p d | arms solving it | promoted ONLY stop |
|---|---|--:|---|
| gd-s10-w0-p3-d1-r00 | 10 0 3 1 | 28 (incl. F0 sibling pipelines) | no admissible step |
| gd-s17-w0-p2-d3-r00 | 17 0 2 3 | 25 (incl. F0 siblings) | base cap |
| gd-s24-w0-p2-d0-r00 | 24 0 2 0 | 4 | early stall abort |
| gd-s24-w1-p2-d0-r00 | 24 1 2 0 | 4 | early stall abort |
| gd-s24-w1-p4-d0-r00 | 24 1 4 0 | 2 | early stall abort |
| gd-s31-w0-p0-d2-r00 | 31 0 0 2 | 1 (D-s4160) | 2 s wall |
| gd-s31-w0-p1-d1-r00 | 31 0 1 1 | 4 | no admissible step |
| gd-s31-w0-p3-d2-r00 | 31 0 3 2 | 14 | early stall abort |
| gd-s31-w0-p4-d2-r00 | 31 0 4 2 | 2 | base cap |
| gd-s31-w1-p0-d1-r00 | 31 1 0 1 | 7 | early stall abort |
| gd-s31-w1-p1-d1-r00 | 31 1 1 1 | 2 | early stall abort |
| gd-s31-w1-p1-d3-r00 | 31 1 1 3 | 5 | early stall abort |
| gd-s31-w1-p2-d0-r00 | 31 1 2 0 | 26 (incl. F0 siblings) | early stall abort |
| gd-s38-w0-p0-d3-r00 | 38 0 0 3 | 2 | early stall abort |
| gd-s38-w0-p1-d1-r00 | 38 0 1 1 | 14 (incl. F0 siblings) | early stall abort |
| gd-s38-w0-p2-d0-r00 | 38 0 2 0 | 3 | early stall abort |
| gd-s38-w1-p1-d0-r00 | 38 1 1 0 | 7 | early stall abort |
| gd-s38-w1-p3-d0-r00 | 38 1 3 0 | 12 | early stall abort |
| gd-s45-w0-p2-d1-r00 | 45 0 2 1 | 11 (incl. F0 phase-floor) | 2 s wall |
| gd-s45-w0-p2-d3-r00 | 45 0 2 3 | 1 (N-20260912) | early stall abort |
| gd-s45-w0-p4-d0-r00 | 45 0 4 0 | 2 | early stall abort |
| gd-s45-w1-p1-d1-r00 | 45 1 1 1 | 1 (L2-20260914) | 2 s wall |
| gd-s45-w1-p4-d2-r00 | 45 1 4 2 | 13 (incl. F0 siblings) | early stall abort |
| gd-s52-w0-p1-d1-r00 | 52 0 1 1 | 5 | early stall abort |
| gd-s52-w0-p1-d2-r00 | 52 0 1 2 | 4 | 2 s wall |
| gd-s52-w0-p1-d3-r00 | 52 0 1 3 | 1 (N+1-20260911) | early stall abort |
| gd-s52-w1-p3-d0-r00 | 52 1 3 0 | 1 (N+1-20260910) | early stall abort |
| gd-s52-w1-p3-d2-r01 | 52 1 3 2 | 3 | 2 s wall |
| gd-s59-w0-p0-d2-r00 | 59 0 0 2 | 3 | base cap |
| gd-s59-w0-p3-d1-r00 | 59 0 3 1 | 5 | base cap |
| gd-s59-w0-p4-d3-r00 | 59 0 4 3 | 4 | early stall abort |
| gd-s59-w1-p3-d1-r00 | 59 1 3 1 | 2 | early stall abort |
| gd-s59-w1-p4-d0-r00 | 59 1 4 0 | 3 | 2 s wall |
| gd-s59-w1-p4-d3-r00 | 59 1 4 3 | 1 (N-20260910) | early stall abort |

Greedy cover of C by additional seeds: H805 hybrid 13, then hybrid N-20260912 +8 (21), then I +4 (25), then D-s3120 +3 (28), then N+1-20260910 +2 (30), then four arms at +1 each. One extra candidate seed buys at most 13 of 34; two buy 21; the tail needs nine. Six C cases are solved by exactly one arm. Five C cases are solved by the F0 weights themselves under a different decode or correction rule (the sibling budget pipelines).

## 4. Group D: 191 never solved

| sub | definition | n |
|---|---|--:|
| D1 | typed INFEASIBLE_SPECIFICATION by classical | 66 |
| D2 | caught by the liquid-supply screen at 0.30, not D1 | 14 |
| D3 | advisory somewhere, never strict, not D1/D2 | 9 |
| D4 | remainder | 102 |

D4 classical statuses: NONCONVERGENCE 85, DEADLINE_EXCEEDED 7, LINEAR_SOLVE_FAILURE 6, ACCEPTANCE_AUDIT_FAILURE 4. Whole-D promoted ONLY stops: 2 s wall 69, early stall abort 61, liquid-supply gate 16, base cap 16, unattributed 11 (all converged then failed strict acceptance or were advisory; all steam cases), no admissible step 8, extended cap 5, property domain 4, LU pivot 1. D held 1184 of the 1245 pre-floor zero phases; the floor clears all but one and D still fails.

## 5. Regime coverage against certified TRAIN (N804)

Exposure ratio = TRAIN share of the cell / validation share (1.0 = matched design); cell = heat loops x stage band x draws x steam.

| group | n | TRAIN records in own cell, mean | median | in a zero-TRAIN cell | median exposure |
|---|--:|--:|--:|--:|--:|
| A | 162 | 5.12 | 4.0 | 3 | 1.01 |
| B | 18 | 7.00 | 5.0 | 0 | 1.01 |
| C | 34 | 4.06 | 2.5 | 4 | 0.50 |
| D | 191 | 3.28 | 3.0 | 24 | 0.50 |

B is not a coverage problem. C and D sit at half the design's exposure. Marginals: stages 49-64 are 9.8% of TRAIN vs 23.0% of validation; 4 heat loops 12.4% vs 20.5%; 3 draws 13.3% vs 25%. TRAIN density falls about 5x from the top-left (few stages, no loops) to the bottom-right (49-64 stages, 4 loops) corner while the validation design is flat; 14 of 200 validation cells have zero N804 records, holding 24 D and 4 B+C cases.

## 6. Budget sensitivity

Of the 20 crawling cases, 11 are in A (already recovered), 5 in C, 2 in B, 2 in D. Across all 20: 0 converge within 32 total iterations, 6 within 40, 11 within 48, 20 within 62. 16 of 20 converge inside 2000 ms, 17 inside 2500, 19 inside 4000. Raising the wall alone recovers one extra case and nothing in B; allowing attempt 1 to reach 48 iterations without the 0.5 gate is what the diagnostic measured. Per-iteration cost under ten workers: p05 6.3, median 41.2, p95 95.7, max 144.4 ms. No single-worker journal exists for the promoted weights; the only single-worker journals (a different model, 252-input population) show higher per-iteration cost, and CPU/wall over the traced requests is median 0.965, so a single worker on this hardware is not materially faster per iteration.

## 7. Not determined

1. Classical solve paths come from the trace-followup run (classical is byte-identical across campaigns, but times are that machine's).
2. Three B and six C cases were never traced (the trace population was selected off the F0-baseline outcomes); two of the untraced B cases stopped at 1880 and 2008 ms and are plausibly crawling.
3. The "would extend" ratios are recomputed over sampled residual arrays, not the solver's internal window; the outcome strings are exact.
4. Post-floor component support is measurable only on the 168 certified references.
5. The nearest-profile arm runs an older 877-case geometry with only 31 overlapping inputs and is excluded from C.
6. The hybrid map is id-joined; it contributes 30 of the 34 C cases.
7. Archived arms were read as "strict in either block, either mode", the most permissive reading; a both-blocks rule would shrink C.
8. The 0.5 gate was registered before the campaign; its recall against the crawling set was not part of the ladder.

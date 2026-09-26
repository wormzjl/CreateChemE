# V4 initializer follow-ups: implementation results

Dates: 2026-09-13 to 2026-09-14. Follows `V4_TRANSFORMER_INITIALIZER_REVIEW.md`. Six tracks were run by Opus subagents in isolated worktrees, each on its own branch descending from `codex/v4-transformer-investigation` @ c2cab76. All native campaigns used ten owned workers, the 2 s neural budget, 16 correction iterations and the 30 s request deadline, never overlapping another campaign or a Gradle run. Every strict count below requires the unchanged native audit and final Newton certificate. All 405-input results reproduce in two reversed-order blocks unless stated.

## 1. Outcome in one table

| Track | Branch @ sha | Result | Effect on the 405 validation set |
|---|---|---|---|
| R1 regime router (replay) | `claude/v4-r1-regime-router` @ 26238c5 | Negative | Coverage-safe rule saves 0.7% time; routing regimes to neural-only saves 22% but loses up to 11 cases on other models. No production router. |
| R3 component presence floor | `claude/v4-r3-decoder-floor` @ 55b41ef | Negative, with the key finding | 0 gains / 0 losses in all variants. F0's 819 reference omissions are 765 zeroed phase totals + 54 presence drops; zero are "kept but undershot". |
| R6 feasibility | `claude/v4-r6-infeasible-screen` @ a749276 | Positive | Request-only liquid-supply ratio at 0.30: 16 never-solved caught, 0 false positives on 405 and on 252 `g4fresh`. |
| Path-dependent infeasible fix | `claude/v3-path-dependent-infeasible` @ 4d14c38 | Done, verified | 108 state-dependent INFEASIBLE_SPECIFICATION verdicts (83 condensation cap, 25 base condenser duty) now NONCONVERGENCE + hint; 42 of them were solvable by another initializer. Latent 512/256-char event bug fixed. |
| R6 Java screen | `claude/v4-r6-liquid-supply-screen` @ 03693b5 | Done, verified | Exactly the 16 expected ids screened at ~0.08 ms each, 110/110 strict preserved with identical iteration counts, 123.3 s reclaimed = 7.6% of all-case classical time. |
| R2 budget diagnostic + reshape | `claude/v4-r2-neural-budget` @ ba5ea45 | Positive, all gates pass | F0 FIRST 168 to 180 (+17 / -5, no classical loss), ONLY 134 to 163/164; pooled all-case FIRST mean 3661 to 3501 ms; common-failure time down. |

## 2. R2 in detail

Diagnostic on 216 traced cases (141 iteration-capped, 80 time-capped, 34 classical-only losses; union) under production walls and under 48 iterations / 6 s:

| Group | n | crawling | stalled | hopeless | reinserting |
|---|---:|---:|---:|---:|---:|
| iteration-capped | 141 | 20 | 97 | 79 | 69 |
| time-capped | 80 | 0 | 46 | 37 | 60 |
| classical-only loss | 34 | 7 | 21 | 19 | 20 |

No time-capped case converges even with tripled walls, so the 2 s allowance is not their binding constraint. Per-iteration cost median 41 ms, p95 96 ms. The 75 archived "zero-iteration" time-limit failures were a reporting artefact: budget exhaustion discarded the in-flight attempt. That is fixed at the source (`NeuralProgress` recorder; failures now publish iterations, residual, refreshes).

Interventions, both opt-in with bit-identical defaults:

- Progress rule (`V3InitializationOptions.Correction`): base cap 16 kept, +8 iteration blocks up to 48 while the residual over the last 8 iterations contracts below 0.5; early stop when it fails to fall below 0.9 over 8 iterations above 1e-6. The 2 s wall is unchanged. Registered by a pre-declared net-time ladder: predicted +40 ms extension cost vs -431 ms abort saving per capped case.
- Phase-level floor (`DecodeOptions.zeroPhaseFloor(10)`): when the phase-total head decodes zero but the branch allows the phase and the presence head keeps at least one component, seed the phase at 10 support floors over the kept components. Reference omissions 819 to 249.

| Pipeline | ONLY b1 / b2 | FIRST b1 / b2 | FIRST gains / losses | pooled FIRST mean ms |
|---|---:|---:|---:|---:|
| F0-baseline | 134 / 134 | 168 / 168 | reference | 3660.7 |
| F0-progress | 149 / 150 | 176 / 176 | +10 / -2 | 3472.4 |
| F0-phase-floor | 147 / 147 | 171 / 171 | +6 / -3 | 3584.3 |
| F0-progress-phase-floor | 163 / 164 | 180 / 180 | +17 / -5 | 3500.9 |

Parity: baseline reproduces the archived decoded seeds 0/405 mismatched and the 110/134/168 identity sets exactly. No FIRST loss is a classical strict case. The two interventions compose because they act on disjoint regimes: progress at 45 to 59 stages (ONLY 6 to 14 at 52 stages), the floor at 3 to 24 stages. Common-success paired FIRST: -182 ms mean, +4 ms median for the combined arm. Common-failure ONLY with the progress rule: -51 ms, iterations 11.2 to 9.8. The baseline's own two blocks differ by 222 ms, so pooled means are gate arithmetic; paired numbers are the durable ones.

Accounting notes from the R2 report: the progress arm's STALLED stop text matches none of the four stop-phrase patterns in the shared read-only `analysis_common.mode_evidence`, so `iteration_limit` drops 150 to 32 and `unknown` rises 42 to 145 (103 early stops counted directly from journals). Eight derived statistics publish as null because the SD of one `SIDE_DRAW_SPLIT` audit value overflows a double on the floor arms; no strict outcome depends on them.

## 3. What production gets today, and what it does not

Everything in R2 is opt-in: `Correction.WALLS` and `DecodeOptions.NONE` are the defaults, so the shipped neural path is bit-identical. The qualified gains were measured on F0, the full-anchor Transformer, which exists only in the offline `tools/neural/` source set. The bundled production models are the Gen2/Gen3 dense, factorized and nearest-profile families in `V3NeuralModels`. Therefore:

- In-game today, the fix and the R6 screen apply (once merged): the 108 mislabelled verdicts become explained non-convergence, and unsuppliable side-draw slates fail in under a millisecond instead of 2 to 30 s.
- The 8x common-success speedup and the +70 FIRST cases over classical that the review measured, and the further +12 from R2, all belong to F0 and reach the game only if F0 is promoted to a bundled production model with the progress rule and phase floor as its defaults. That promotion is a product decision the handoff explicitly did not take. It is the single step that turns this work into in-game solver time.
- Enabling the progress rule or phase floor for the Gen3 production models without a paired campaign on them would be unqualified; the same harness (`tools/neural-budget/`) can run that campaign in about an hour.

## 4. Merge order and main

The branches stack: `claude/v3-path-dependent-infeasible` (fix) < `claude/v4-r6-liquid-supply-screen` (screen) and, separately, `claude/v4-r3-decoder-floor` < `claude/v4-r2-neural-budget`. The integration branch `claude/v4-initializer-integration` (merge 614300a of ba5ea45 + 03693b5, doc commit 6fa51a1) merges the screen branch into the R2 branch. Two conflicts, both resolved keeping both sides (`.gitignore` allowlist; the private learned entry that R2 widened with a trace and R6 with the screen ratio). One silent breakage git did not mark: R2's diagnostic seam passed its trace into the slot that became the screen ratio; fixed to pass the default ratio. Suite on the merged build: 580 tests, only the pre-existing codec failure. R6 re-verified on the merged build over all 405 inputs: same 16 ids screened, 110/110 strict on identical Newton trajectories. Details in `tools/integration/INTEGRATION.md` on that branch.

`main` (f01a28e) does not contain the neural stack (caa466d..ef05260 are on the Codex lineage only), so R2 cannot go to main at all. Neither the fix (4d14c38) nor the screen (7d08ef7) cherry-picks cleanly: every conflict is a hunk anchored in neural code absent from main, and for the screen the config plumbing in `CreateChemE` and `ProcessSolveServices` is interleaved with neural plumbing (`V3ColumnCommand` has 3 components on main, 5 on the Codex lineage, 6 with the screen). Landing the screen on main is a port: `compileJava` passes after conflict resolution, but 5 of 8 screen tests reach the learned entry and 2 need rewriting against a classical assertion. Measured on a throwaway branch, the ported main is 511 tests, 0 failures. Whether the neural stack goes to main is the same product decision as promoting F0.

The pre-existing failure `V3SideDrawCodecTest.malformedAndOversizedListsAreRejectedBeforeAllocationOrSilentMigration` is green on main and fails at the shared base c2cab76, so it entered with the neural stack, not with any branch here.

## 4a. Promotion to production (2026-09-14, user decision)

User decisions: the Transformer is the production default, only one neural model ships, and the neural lineage goes to main. Branch `claude/v4-transformer-promotion` @ 7c7d88e (base: integration 6fa51a1 + codec fix 4b62ba6).

Correction to the earlier sections: F0 is the anchor-augmented model (`featureRevision v3-anchor-augmented-1`, 185 inputs, 89,496 parameters, read by the offline `V3BudgetInitializer`), not the 96-input `v3-column-transformer-1` class. The promoted production class is `V3AnchorTransformerInitializer` with its native anchor (`V3NativeAnchor`).

What production now ships:
- One bundled model, `src/main/resources/data/createcheme/neural/v3-column-transformer-f0.json`, byte-identical to the registered weights (2,658,860 bytes, sha256 `7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962`), model card beside it.
- `V3NeuralModels.bundled()` loads it with `DecodeOptions.zeroPhaseFloor(10)`; `V3InitializationOptions.DEFAULT` and the config-built options carry `Correction.PROGRESS` = (8, 48, 8, 0.5, 8, 0.9, 1e-6), verified against the registered pipeline. `CURRENT`, the 16 base cap, 2 s budget, closure, screen and digests are untouched.
- The `Family` enum and `initializerModel` config key are removed (an old config's key is stripped by `ModConfigSpec` correction). The dense experts, generalized stage model and Gen3 factorized model with their five reader classes moved to `tools/neural/retired/` for study reproducibility. Six production test classes covering retired families were deleted (listed in `tools/transformer-promotion/results.md` §6). No GUI, wire or NBT schema change.

Production-path qualification (405 inputs, ten workers, two blocks, through `V3ColumnCalculator.calculate` with the bundled model and config-default options):

| Route | Block 1 / 2 | Qualification campaign | Identity sets |
|---|---:|---:|---|
| Classical | 110 / 110 | 110 | identical |
| LNN_ONLY | 162 / 162 | 163 / 164 | 1 and 2 short |
| LNN_FIRST | 180 / 180 | 180 | identical |

Decoded seeds 0/405 mismatched. The two missing ONLY cases are 2 s-wall timing: this run's untouched classical route cost 12.7% and 22.1% more than in the qualification on identical trajectories, and the only qualified ONLY successes above 1,500 ms slipped over the wall, including `gd-s59-w0-p1-d0-r00`, the case the R2 report itself named as non-reproducible. Both are recovered by fallback, so LNN_FIRST is intact. On the 110 columns classical also solves, LNN_FIRST is 910 to 935 ms faster on mean and 563 to 591 ms on median; the 70 columns classical never reaches solve at a 228 ms median.

Suite on the promotion branch: 545 tests, 0 failures (580 minus 39 deleted plus 4 new).

Main: `main` (f01a28e) is an ancestor of the final sha e66b374 on `claude/v4-lnn-gap-experiments` (49 ahead, 0 behind), which contains the promotion, the benchmark cleanup, the stall-stop fix, the adopted extension gate and all sealed studies. From the main checkout:

```
git merge --ff-only e66b374
```

Not done: an in-game check through the MCP GUI bridge that a column solve reports the transformer initializer in its result; the production-path campaign is the qualification evidence.

Loose ends left deliberately unedited in sealed studies (results.md §7): re-registering the decoder-floor study needs its `DECLARED_SOURCE_DELTA` widened, and five archived `tools/neural/` report scripts still point at the old resource paths.

## 4b. Benchmark population cleanup (2026-09-14, user instruction)

Branch `claude/v4-benchmark-cleanup` @ b191481 (base 7c7d88e). Rule: an input is excluded if the promoted request-only admission types it at ratio 0.30 (`REQUEST_ONLY_TYPED`), or if the retired state-dependent heat gates typed it and no archived arm ever reached strict or advisory on it (`STATE_GATE_NEVER_SOLVED`). Advisory-only and never-converged inputs stay as the open set. The request-only probe ran over all 3,297 inputs in 1.7 s with zero solves; every request-only exclusion is the liquid-supply screen, and neither the draw-versus-feed check nor the static cooling admission fired anywhere.

| Population | n | request-only | state-gate | cleaned | ceiling | open set |
|---|---:|---:|---:|---:|---:|---:|
| validation | 405 | 16 | 59 | 330 | 214 | 116 |
| g4fresh test | 252 | 11 | 49 | 192 | 110 | 82 |
| g6fresh test | 252 | 10 | 36 | 206 | 87 | 119 |
| historical test | 395 | 20 | 48 | 327 | 186 | 141 |
| TRAIN inputs | 1,993 | 96 | 348 | 1,549 | 813 | 736 |

Validation lost 75, not 80: two of the 66 state-gate cases are also screened (request-only takes precedence) and five reached advisory on eight archived arms each, so the rule keeps them. Zero certified labels sit on an excluded input (certified sets are N = 805 and N+1 = 906).

Re-based headline on the cleaned validation set, both blocks identical:

| Route | strict | of 405 | of 330 |
|---|---:|---:|---:|
| classical | 110 | 27.2% | 33.3% |
| LNN_ONLY | 162 | 40.0% | 49.1% |
| LNN_FIRST | 180 | 44.4% | 54.5% |
| ever-solved ceiling | 214 | 52.8% | 64.8% |

Numerators are unchanged; common-success paired timing is unchanged and now verified per comparison. All-case means rise because the excluded cases were cheap typed failures.

Also delivered: `V3ColumnCalculator.requestOnlyAdmission(input, ratio)` as the single seam `calculate()` uses, so the probe cannot drift; `generalized_design.py --request-only-screen-ratio` (off by default; at 0.30 the frozen design loses exactly the 132 points excluded here; Python port matched the Java verdict on all 3,297 inputs); a `--population` harness hook with registration by SHA-256; and a `.gitattributes` fix pinning `*.jsonl` and the neural resources as binary, because CRLF normalisation was corrupting the registered hashes of the bundled model and frozen inputs on this checkout. Caveats: the TRAIN and historical-test classical control is the older `v3-dry-mesh-r23` acquisition, and arm coverage there is thin, so never-solved verdicts are weaker evidence on those populations. Handoff: `documentation/V4_BENCHMARK_POPULATION_REVIEW.md`.

## 4c. LNN-only gap experiments (2026-09-14)

Branch `claude/v4-lnn-gap-experiments` @ de51568 (base promotion 7c7d88e + cleanup b191481). Frozen F0 weights, cleaned 330-input validation set, three modes, two reversed blocks, ten workers. Parity: the baseline reproduced the promotion identity sets exactly (110 / 162 / 180, zero differing ids) and every arm's first decoded seed matched the promotion digests 330/330. Latency band = the baseline's own between-block spread of the pooled FIRST mean, 119 ms in round one. Gates: strict FIRST gain in both blocks, classical union preserved, pooled FIRST mean within the band. Groups: B = 18 classical rescues, C = 34 other-seed-solvable, 20 crawling trajectories.

| Arm | Change | ONLY b1/b2 | FIRST b1/b2 | Paired ONLY | Paired FIRST | B / C recovered | All-case FIRST ms | Gates |
|---|---|--:|--:|---|---|---|--:|---|
| baseline | promoted defaults | 162/162 | 180/180 | ref | ref | 0 / 0 | 3976 | ref |
| E1a | contraction factor 0.5 to 1.0 | 164/164 | 182/182 | +3/-1 both | +2/-0 both | 1 / 2 | 3976 | pass |
| E1b | flat 48-iteration first attempt, no gate | 146/146 | 170/170 | +0/-16 | +0/-10 | 0 / 0 | 4162 | fail |
| E2a | early abort window 12, factor 0.95 | 163/161 | 183/183 | +7/-6, +6/-7 | +5/-2 both | 2 / 5 | 3816 | pass (not eligible on ONLY rule) |
| E2b | early abort off | see defect | | | | | | |
| E3 | ramp handoff, 8 s sub-wall | 170/171 | 182/182 | +8/-0, +9/-0 | +2/-0 both | 6-7 / 1 | 6023 | fail latency |
| E4 | second candidate, prune decode | 164/164 | 182/182 | +2/-0 both | +2/-0 both | 0 / 2 | 4069 | pass |
| E5 | E1a + E2a + E4 (round 2) | 166/166 | 184/184 | +5/-2, +5/-1 | +4/-0 both | 1 / 4 | 4081 | fail latency (+265 vs 186 band) |

Findings:
- E1a is free coverage: +2 neural-only and +2 neural-first in both blocks for +0.6 ms. Adopted as the production default in the follow-up.
- E1b is the clearest negative: more iterations without the gate spend the 2 s wall before the support-refresh pass that would have converged them.
- E2b exposed a solver defect: with the progress extension on and the stall stop off, the stall test compared each residual to itself and stopped every attempt at iteration 0. Reachable from the public API as `Correction(8,48,8,0.5,0,0,0)`; the shipped `PROGRESS` never hits it. Fixed at 9771377 with a regression test and re-measured in its own round (E2b fixed: ONLY 164/164, FIRST 183/183, +71 ms against a 62 ms band, fails latency narrowly).
- E3 recovers the most: neural-only 170 to 171 with zero losses and 6 to 7 of the 18 classical rescues, and all 7 recoveries with a known classical root land on that root (2e-13 to 5e-9 K per node). It fails the latency gate by about 2 s because refused handoffs burn the 8 s sub-wall (accepted mean 1260 ms, refused median 6885 ms). Kept opt-in; a follow-up round with 2.5 s and 4 s sub-walls is running.
- E4 picks up the two other-seed cases the prune decode solves, +93 ms, inside the band. Kept opt-in; combined with E1a it reaches FIRST 184 of 330 at +265 ms, a latency call.
- The registered E5 rule compared the combination against the best arm rather than the best eligible arm, so E3's latency failure vetoed it; under the intended reading E5 still fails the band.
- Round-two baselines each differed from the promotion by one neural-only case in block 1, both gained, both the documented 2 s-wall jitter cases.

Evidence sealed under `tools/lnn-gap/evidence/` (728 KB), raw journals bound by hash. Suite 556 tests, 0 failures.

Follow-ups (final sha e66b374, suite 557 tests, 0 failures, evidence 932 KB):

- E1a adopted as the production default at 6fe78cb: `Correction.PROGRESS` = (8, 48, 8, 1.0, 8, 0.9, 1e-6). The previous rule is kept as the named constant `PROMOTED_2026_09_14` and the archived promotion probe pins it, so re-running that study still measures the promotion. The handoff sub-wall became an option (`recoveryBudgetMilliseconds`, default 8 s).
- Round four, handoff sub-wall, against the new default as baseline (band 23 ms this round):

| Arm | ONLY b1/b2 | FIRST b1/b2 | paired ONLY | paired FIRST | B / C / crawling | all-case FIRST vs band |
|---|--:|--:|---|---|---|--:|
| baseline (new default) | 164/164 | 182/182 | ref | ref | 1 / 2 / 12 | ref |
| E3 at 2,500 ms | 172/172 | 184/184 | +8/-0 both | +2/-0 both | 6 / 3 / 14 | +812 ms |
| E3 at 4,000 ms | 172/172 | 184/184 | +8/-0 both | +2/-0 both | 6 / 3 / 14 | +1169 ms |
| E3 at 8,000 ms (round one) | 170/171 | 182/182 | +8/+9 | +2 | 6-7 / 1 / 13 | +2047 ms |

No accepted handoff ever exceeded 2,264 ms in any round, so the 2,500 ms wall cuts nothing it would have accepted: identical 18 accepted requests, 9 cases, 16 strict, for 40% of the 8 s arm's time. Both arms pass the FIRST-gain and classical-union gates and fail the latency band, so by the registered rule `Recovery.RAMP_HANDOFF` stays opt-in with `NONE` the default. If it is ever enabled, enable it at 2,500 ms. The remaining lever for its cost is gating: refused handoffs are mostly trajectories the trace classified hopeless, and arming only stalled or reinserting ones would cut refused spend without touching the accepted set. Not measured.

Shipped defaults after this work: contraction factor 1.0, `Recovery.NONE`, `CandidateRule.SINGLE`, phase floor 10, 16-iteration base cap, 2 s allowance.

## 5. Open items and caveats

1. The 0.30 screen threshold is calibrated on 474 solvable requests across two populations, not proven; the physically rigorous tier is 1.0. Re-run `tools/infeasible-screen/screen_feasibility.py` against any new validation population before changing it. Config `columnV3LiquidSupplyScreenRatio` (0 disables) exists so the draw-wall research can bypass it.
2. Two failure-mode fixtures in `V3SideDrawCalculatorTest` (ratios 0.49 and 0.97, both unsolvable) now assert the screen verdict on the default path and their original contract at ratio 0.
3. The codec test failure on the Codex lineage is FIXED on `claude/v4-codec-guard-fix` @ 4b62ba6 (580 tests, 0 failures). Bisect: introduced by 1a4a01d, which raised `MAX_PUMPAROUNDS` from 3 to 4. The test poked the trailing pumparound count while asserting about side draws and only passed on main because 4 exceeded the old bound. The side-draw guard was never bypassed, but the regression exposed a real gap: a count inside its bound on a truncated packet escaped `readInput` as `IndexOutOfBoundsException`. `readCount` now requires the minimum payload bytes per element before allocation and `readInput` backstops index errors as `DecoderException`; test sharpened to address each count by name. No wire bump (`WIRE_SCHEMA_VERSION` stays 8).
4. Case-id schema `gd-s<stages>-w<steam>-p<heatLoops>-d<draws>-r<rep>` is undocumented in `tools/neural/README.md`.
5. The ever-solved union labels in the review memory were transposed: hash-verified sources give 207 never-solved, 198 with the id-joined hybrids.
6. Subagent commits on the R2 branch carry a `Claude Opus 5` trailer; the others carry `Claude Fable 5.1`.
7. Agent worktrees under `.claude/worktrees/agent-*` may be cleaned; every result is committed on its branch, and raw 200 MB journals are bound by hash in each study's cache manifest but not committed.

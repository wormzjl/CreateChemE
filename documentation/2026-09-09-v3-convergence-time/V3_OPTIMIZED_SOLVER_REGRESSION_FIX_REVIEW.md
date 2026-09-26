# V3 optimized solver: recovering the five lost perturbations

Date: 2026-09-09. Follows `V3_OPTIMIZED_SOLVER_ROBUSTNESS_REVIEW.md`, which found that the optimized solver
loses five wet literature-CDU perturbations and gains five others against the pre-optimization baseline.

- **ORIGINAL** = `e8d8937`, main before the optimization work.
- **BASE** = `1e388f5` (`claude/convergence-time-optimization-7f5b84`), the audited optimized revision.
- **FIX** = BASE plus this change, one production file (`V3ColumnCalculator`, +134/-8).

All numbers below are runs of the audit's own harness
(`.claude/worktrees/agent-acf7be2374b92fb29/build/robustness/`, `build.sh` / `perturb.js` / `doe.js` /
`compare-*.js`) against a classpath built from this worktree, one cold JVM per case, serial, same flags and
same 60 s deadline. The stored `pert-orig.jsonl`, `pert-opt.jsonl`, `doe-orig.jsonl` and `doe-opt.jsonl` are
reused for ORIGINAL and BASE; before trusting the setup, twelve key cases were rerun on an unmodified build
of `1e388f5` and reproduced BASE's status on all twelve.

## Verdict

**Four of the five losses recovered, all five gains kept, three further cases gained, no regression against
either revision, and the flagship preset bit-identical.**

| Set | Cases | vs ORIGINAL: same OK / same fail / ORIG-only / FIX-only | vs BASE: same OK / same fail / BASE-only / FIX-only |
| --- | ---: | --- | --- |
| Perturbation sweep | 85 | 53 / 23 / **1** / **8** | 54 / 24 / **0** / **7** |
| Cold-core DOE, default suite | 64 | 44 / 20 / 0 / 0 | 44 / 20 / 0 / 0 |

Total perturbation successes: ORIGINAL 54, BASE 54, **FIX 61**.

The DOE is **bit-identical to BASE on all 64 cases** — same stream fingerprints, same solve paths, same
status labels, `maxRelFlowDelta` exactly 0. Nothing in the DOE enters the new code.

## What shipped

One mechanism, in `recoverWithDrawRamp`.

**A bounded second sweep of the steam schedule, at the full rung budget, bought only after the requested
rung fails.** When an intermediate steam rung stops under `INTERMEDIATE_RUNG_BUDGET`, the ramp records the
rung and the last state the schedule accepted, then behaves exactly as before: it skips ahead and solves the
requested input from the stopped state. Only if *that* fails does it rewind to the recorded rung, set every
remaining rung to `RungBudget.DEFAULT` (full damped cascade, gradient fallback, no stall stop), and replay.
Rungs solved in the second sweep carry `/full-budget/` in their solve path. Inside the second sweep only, a
steam rung that stops again is halved, at most `MAXIMUM_STEAM_SUBDIVISIONS` = 2 times per ramp.

The sweep is entered at most once per ramp, so the cost is bounded by one extra pass over the steam and
draw rungs.

### Why it is gated on the requested rung rather than on the stop

The obvious form of the fix — retry a stopped steam rung at the full budget immediately, before skipping
ahead — was implemented and measured first. It recovers three of the five losses, but it costs the
literature preset itself, whose own schedule stall-stops at 0.625 and then skips ahead to an accepted
answer:

| Variant | preset (LitTiming, warm) | digest | perturbation losses recovered |
| --- | ---: | --- | ---: |
| BASE | 1.75 / 1.51 s | `2e924d3a10e43a6a` | – |
| eager retry at every stopped rung | **3.02 s** | `2e924d3a10e43a6a` | 3 of 5 |
| second sweep after the requested rung fails (shipped) | 1.82 / 1.60 s | `2e924d3a10e43a6a` | 4 of 5 |

The eager form pays 1.3 s to drive the preset's 0.625 rung from a residual of 0.482 down to 1.17e-3 — and
the rung fails either way, and the requested rung publishes the same answer either way. On the shipped form
the preset never enters the second sweep at all: its LitTiming counters are *identical* to BASE
(`attempts=21 resid=6582 fdJac=24 localJac=177 lu=222 newtonIt=204 finalVerify=18`), as are `plain40`
(`attempts=10 ... newtonIt=103`) and `plain30`.

### Why the halving is second-sweep only

The existing Javadoc records that halving a stopped rung on the *first* sweep is a measured loss: the
0.625 -> 0.458 midpoint stalls for another 1.2 s and the requested rung then costs 3.6 s instead of 2.5 s
for the same accepted answer. That comparison assumes the skip-ahead is going to succeed. Once the
requested rung has already failed from the skipped-ahead state, the smaller increment is the only
unexplored seed left, and it is what recovers `cond+5`: its 0.625 rung is stuck at 1.5745e-3 under the
reduced budget and at 1.5744e-3 under the full one — the full budget buys nothing there — but 0.458 and
then 0.375 walk it into the basin that publishes.

## Per-case status, every case that moves

| case | ORIGINAL | BASE | FIX |
| --- | --- | --- | --- |
| `P-preset-cond+5` | OK | LINEAR_SOLVE_FAILURE | **OK** |
| `P-preset-top200kPa` | OK | NONCONVERGENCE | **OK** |
| `P-preset-steamx0.5` | OK | LINEAR_SOLVE_FAILURE | **OK** |
| `P-preset-steamx1.5` | OK | NONCONVERGENCE | **OK** |
| `P-preset-steamx2` | OK | NONCONVERGENCE | NONCONVERGENCE |
| `P-preset-dp750` | NONCONVERGENCE | OK | OK |
| `P-preset-pax1.5` | NONCONVERGENCE | OK | OK |
| `P-plain40-feedT+15` | NONCONVERGENCE | OK | OK |
| `P-plain40-top200kPa` | NONCONVERGENCE | OK | OK |
| `G-preset-r2-c10` | NONCONVERGENCE | OK | OK |
| `P-preset-feedratex0.8` | NONCONVERGENCE | LINEAR_SOLVE_FAILURE | **OK** |
| `P-preset-drawx1.25` | NONCONVERGENCE | NONCONVERGENCE | **OK** |
| `G-preset-r2-c-10` | NONCONVERGENCE | LINEAR_SOLVE_FAILURE | **OK** |

The last three are new: cases neither revision solved, which the second sweep reaches. `drawx1.25` is
notable because it is a *draw*-loading perturbation, not a steam one; its steam schedule stall-stops at
0.625 and the second sweep is what lets the draw ramp start from a usable state.

## Agreement of shared successes

| pair | shared successes | bit-identical fingerprints | max relative stream flow | max abs stream T |
| --- | ---: | ---: | ---: | ---: |
| BASE vs FIX (perturbation) | 54 | **53** | 1.10e-5 (`P-preset-cutoff1e-6` only) | 1.16e-3 K |
| BASE vs FIX (DOE) | 44 | **44** | 0.00e+0 | 0.00e+0 K |
| ORIGINAL vs FIX (perturbation) | 53 | 0 | 1.18e-10 (`P-preset-steamx0.5`) | 2.77e-8 K |
| ORIGINAL vs FIX (DOE) | 44 | 0 | 1.87e-10 (`A250-wet-slowdown-off`) | 1.16e-8 K |

ORIGINAL never agrees bit for bit with anything on this branch — the analytic PR78 derivatives change every
trajectory's last digits — but the physical answers agree to 1e-10.

The one shared success where FIX differs from BASE by more than rounding is `P-preset-cutoff1e-6`, and it is
a change of *which problem is published*, exactly as the audit's section 5.1 described in the other
direction:

- ORIGINAL published the truncated solve (retained 512/798, audited sink-edge defect 2.72e-6 of feed
  against a limit of 8.00e-6).
- BASE's truncated attempt nonconverged, so the `stage-trace fallback` retried untruncated and published
  that, at a defect of 6.81e-11.
- FIX's truncated attempt now converges — through the second sweep — so it publishes the truncated solve
  again, at ORIGINAL's own defect of 2.72e-6, and agrees with ORIGINAL to 1e-10.

So FIX returns the *less* exact of the two accepted answers here and BASE returns the more exact one. Both
are inside their own audited budgets; this is the truncation approximation the caller asked for by setting a
cutoff, and FIX restores ORIGINAL's behaviour rather than inventing a third one.

## Cost

- Preset and plain-40: unchanged, counters identical (above).
- Perturbation shared successes vs BASE: paired ratio at most 1.18 on every one of the 54, and the only case
  above 1.09 is `P-preset-cutoff1e-6` at 4.54 s -> 5.36 s, which is the truncated solve replacing the
  untruncated retry. Sweep total 202.4 s -> 249.3 s over all 85 cases.
- **Failure latency roughly doubles on wet preset failures** — this is the real price. A wet case that is
  going to fail now pays a second sweep before it says so: `E-preset-cond300` 5.5 s vs 3.1 s,
  `P-preset-refluxx2` 5.3 s vs 2.9 s, `P-preset-feedratex1.25` 7.3 s vs 3.9 s. All remain far inside the
  60 s deadline and all remain 3-10x faster than ORIGINAL, which took 21.9 s, 19.0 s and 23.9 s on the same
  three.
- Paired geometric mean vs ORIGINAL: 0.197 on the perturbation shared successes, 0.248 on the DOE. Total
  perturbation calculator time 1305.3 s (ORIGINAL) -> 249.3 s (FIX).

## `P-preset-steamx2`: not recovered, and why forcing it costs more

`steamx2` is the one loss that does not come from the steam ramp at all. It dies earlier, in the stage
continuation, at the 30-stage rung of the `4-8-15-30-40` grid, with `iterations=0`, a maximum scaled
residual of only 1.5652e-3 and "no admissible Armijo-reducing Newton or descent step" — so the ramp code
this change touches is never reached. Four variants were built and run to localise it:

| variant | `steamx2` | `top200kPa` | `plain40-feedT+15` | `G-preset-r2-c10` |
| --- | --- | --- | --- | --- |
| FIX as shipped | fails at stage 30 | OK | OK | OK |
| `OVERSIZED_SEED_FACTOR` 3 -> 1000 (cap effectively off) | fails at stage 30 | **lost** | OK | OK |
| cap restricted to points below 1e-3 of their flow scale | fails at stage 30 | **lost** | OK | OK |
| rectifying clamp in `sourcePosition` disabled | reaches stage 40 | **lost** | **lost** | **lost** |

So the P3 rule responsible is the **rectifying clamp**, not `capOversizedPoint`. On the 15 -> 30 map the
clamp binds on a single node — the tray immediately above the target feed tray, which reads source node 13
exactly instead of blending 13 and 14 at 40 % — and that one node is enough to leave the 30-stage seed with
no admissible descent direction. Removing the clamp costs three of the five gains plus `top200kPa`: a
net -4 / +1 trade, strictly worse than keeping `steamx2` lost.

And removing it is not sufficient either. With both P3 rules disabled, `steamx2` reaches the 40-stage grid,
the second sweep fires, the resumed 0.125 rung lands on exactly ORIGINAL's stopping state (3.0322e-4 against
ORIGINAL's 3.0322e-4), the halving then moves it to 0.10417 where the budget is exhausted, and the requested
rung fails from *that* state. ORIGINAL succeeded by skipping ahead from the 3.0322e-4 state directly. That
suggests a possible third ordering — in the second sweep, try the skip-ahead once from the improved state
*before* halving — which was not implemented, because with the clamp in place `steamx2` never reaches the
ramp and no other case in the 85 needs it.

## Separate finding: `capOversizedPoint` fires far outside its documented population

Not acted on, because every relaxation tested loses `top200kPa`, but it should be recorded.

The rule's Javadoc justifies the factor of 3 by saying the flows it catches are "eight to eighteen e-folds
too high rather than two". Instrumenting it on `P-preset-steamx2` (145 firings in one solve) shows
otherwise:

- **median cut factor 14x**, not the 3000x-6.5e7x that 8-18 e-folds means.
- 47 of the 145 capped points are above 1e-3 of their own component's flow scale, i.e. not trace points.
- The largest is the condenser's component 12 at 218.47 mol/s — **4.79 times the component's whole flow
  scale**, a bulk flow — cut 5.51x down to 39.65 mol/s.
- The guard the robustness review's follow-up suggested (require the inflow to be well above the floor, or
  stop the cap dropping a point below the reinsertion value) would be a **no-op** here: all 145 firings have
  `inflow` at 100 floors or more, and none is dragged to the floor.

The rule also caps in place, in the same two sweeps as the reinsertion, so a capped node lowers its
neighbour's delivered inflow and can cascade. Whether that matters is untested.

## Test suite

`./gradlew.bat --offline test --no-daemon`, whole suite. The A-E pins in `V3ConvergenceClosureTest` are
untouched; no tolerance and no pinned expectation was changed anywhere.

Three focused tests were added to `V3DwsimStageContinuationTest`:

- `aResumedSteamRungHalvingOnlyEverQueuesAFractionStrictlyInsideItsInterval` — the halving's boundedness,
  including the one-ulp interval that must be refused rather than queued as a degenerate rung.
- `theWetPresetAtHalfSteamIsRecoveredByOneFullBudgetResumeOfItsSteamSchedule` — the recovered case end to
  end: it publishes, its path carries `/full-budget/`, and the resume event appears exactly once with its
  rung and its budget named.
- `theWetLiteraturePresetItselfNeverEntersTheFullBudgetSweep` — the speed contract: the preset must publish
  with no full-budget rung and no resume event.

`onlyAnIntermediateSteamRungGivesUpItsFallbacksAndItsCertificateCascade` gained a case asserting that
`rampRungBudget(steamRung, requested, true)` is `RungBudget.DEFAULT` for all four flag combinations.

## Not verified

- Single host, single JDK (21.0.11), one repetition per case. Cold-JVM timings; not statistically qualified.
- The DOE `trace_walls` and `historical_replay` panels were not run, matching the audit's scope.
- The refinement sweep of the audit's section 5.2 was not rerun; it is a biased sample chosen around BASE's
  flips and would not be comparable.
- No NeoForge or in-game path was exercised. Persistence, wire format and GUI are untouched by this change.
- The event-list bound added to `EnergyShiftLog.merged` is defensive: the second sweep writes more event
  lines than the first, and the worst case was reasoned to about 23 of the 32 the diagnostics contract
  allows rather than being driven to the limit by a test.
- The "skip ahead once before halving" ordering described above was measured on one case in a
  both-P3-rules-disabled build only; it is not a proposal that has been screened.

## Reproduction

```sh
B=D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-acf7be2374b92fb29/build/robustness
sh $B/build.sh <this-worktree> $B/cp-mine
node $B/perturb.js cp-mine MINE $B/perturb.json $B/perturb.json.ids pert-mine.jsonl
node $B/doe.js     cp-mine MINE doe-mine.jsonl
node $B/compare-perturb.js $B/pert-orig.jsonl pert-mine.jsonl perturb-orig-vs-mine.csv
node $B/compare-perturb.js $B/pert-opt.jsonl  pert-mine.jsonl perturb-opt-vs-mine.csv
node $B/compare-doe.js     $B/doe-orig.jsonl  doe-mine.jsonl  doe-orig-vs-mine.csv
node $B/compare-doe.js     $B/doe-opt.jsonl   doe-mine.jsonl  doe-opt-vs-mine.csv
# warm timing and counters
sh build/pkgcmp/build-probe.sh   # ROOT set to this worktree
java -cp "build/pkgcmp/classes-probe;<gson>" com.wormzjl.createcheme.science.column.v3.LitTiming all 2
```

Raw results, comparison CSVs and the two LitTiming logs are under this worktree's `build/rob/`. Note that
`build-probe.sh`'s `probe.fullJacobianRamp` patch no longer matches, because this change rewrote the rung
solve call it keyed on; the other 35 probe patches all still apply and the flag is unused here.

# F1: start-up wall-budget retry nondeterminism (Problem 3) - the cause, the fix, the bitwise proof

Date: 2026-09-24. Batch `2026-09-23-fluid-followups`, package F1. Branch `claude/fluid-followups` (worktree `agent-ae139e4fc1b184b36`) over `main` @ `23beadd`; fix `5b3d903`, cold-start slice `7f933ff`, head `111d805`. Numbers in `f1-tables.md` sections 6 and 7; reports in `f1-logs/paced/reports/`, comparisons `f1-logs/paced/diverge-*.txt`, `cmp-*.txt` (tools `fluid-scheduler/wp2-logs/wp2diverge.js`, `wp2cmp.js`).

**Window: paced `transient100` and `stress100`, 60 s warm-up + 60 s measured, 12 automatic workers, one run at a time after the machine gate.**

## 0. Summary

* **Cause:** a held island was retried with the `RetainedSolver` its held attempt had used. A cancelled job leaves the step solver's last flows, heads, modes and factorizations of the point where the wall budget stopped it, and the retry warm-starts from them; how far the job got depends on wall time, so the retry's trajectory did too (WP2 section 4: 4 or 5 islands per run part from their first retried slice).
* **Fix (`5b3d903`):** every hold installs a fresh `RetainedSolver`, so a retry is the same computation as a first attempt on the same committed state and slice. One cold start per hold. It is part of the hold policy in `FLUID_PUMPED_FILL_REVIEW.md` section 3, where the same stale warm start also held pumped chains forever.
* **Proof:** two quiet `transient100` runs on `7f933ff` agree bit for bit on every one of the 100 islands on every common publication, the 5 held ones included, and on all 95 islands exact on the reference grid; a second pair on `1dac4bd` and a cross-build pair agree the same way. WP5's three runs of 0.3.0 each parted on the same 5 islands at tick 50.
* **Why the first slices exceed 2 s:** the first round dispatches 12 islands at once onto a cold JIT; the jobs run at a CPU/wall of 0.55 to 0.81 and the five heaviest pass the 2 s budget. The same five in every quiet run here and in WP5, but the held *set* is still a wall-time outcome: in `stress100` two runs held 2 and 3 islands, one of them in one run only.
* **First dispatch:** not spread. Islands a topology change creates (every in-game placement and edit) now start on a one-tick slice (`7f933ff`), which removed every start-up hold of 100 placed pumped fills in game; islands registered from a save or a checkpoint (P12, P31, the paced benchmarks) keep their first slice at the cadence, so their fingerprints and references do not move.
* **Tolerance default (owner decision 1):** `stress100` at the new default 1e-7 in section 5.

## 1. The nondeterminism

WP2 (`FLUID_SCHEDULER_WP2_REVIEW.md` sections 4 and 8) found that the islands parting between otherwise identical runs are those a start-up wall-deadline hold shifted: both runs hold the same islands, both retry the slice `[0, 50]`, and the retries differ from the first retried interval on (44 accepted / 11 rejected substeps against 44 / 12). WP5 measured it again on 0.3.0 (`1401cab`): `transient100-wp5-on-r01`, `-r02`, `-r03` pairwise, 95 of 100 islands identical on every common publication, the 5 others (10905, 10907, 10910, 10912, 10915) parting at tick 50.

The mechanism is `RetainedSolver`'s purpose: it keeps the sparsity pattern, the ordering, the last factorization (the modified-Newton preconditioner) and, inside `PassiveStepSolver`, the previous point's flows, heads and device modes (`previousFlows`, `previousHeads`, `previousModes`, `lastSolve`), so that the next interval continues warm. A job the wall budget cancels (`FluidWallDeadline`) returns nothing but leaves all of that at wherever its last pass or step ended. The coordinator then retried the same committed state with the same handle, so the retry started from a point inside the slice the cancelled job had reached - a point decided by how many checkpoints fit in 2 s of wall time. The same carried state is what held the vented pumped chains forever (their retry's rate solve started from 0.45 and -0.22 kg/s on tanks at rest).

## 2. The fix

`IslandCoordinator.hold` (`5b3d903`): a held attempt - no committed candidate, no refused material transaction, same revision - replaces `island.retained` with a fresh `RetainedSolver` (the cancelled job, if still running, keeps its own instance, so the ownership latch never sees two jobs on one solver). The next attempt starts from the committed state with no carried flows, modes or factorizations and from the cold substep (`COLD_START_SECONDS`), exactly like the island's first attempt ever. Given the same committed state and slice, the retry is therefore the same computation in every run.

Cost: one cold start per hold (a Jacobian structure, an ordering, a factorization, and the substep controller's ramp from 0.05 s). The alternative the brief named ("install a fresh RetainedSolver for the retry, one cold start per hold") is what was done; a variant that kept only the structural caches (pattern, colouring, ordering) and dropped the numeric state would save the symbolic work but keep `previousModes` and the factorization choice in play, and was not pursued.

The rest of the hold policy (halved slices, deferred retries at one tick, full-solve retry after a refused fallback) is in `FLUID_PUMPED_FILL_REVIEW.md` section 3; for `transient100`'s start-up holds it changes nothing observable: they are wall holds at 100 ticks, halved to 50 as before, and accepted there.

## 3. The bitwise proof

| pair | build | fingerprints: islands identical on every common publication | held at start-up | reference grid tick 1900 |
|---|---|---|---|---|
| WP5 on-r01, r02, r03 (pairwise) | `1401cab` (0.3.0) | 95 of 100; the 5 held part at tick 50 | the same 5 | 95 exact in both, 0 differ |
| `transient100-f1-r01` vs `-r02` | `1dac4bd` | **100 of 100** | the same 5 in both | 95 exact, 0 differ; ledger and conservation 0 |
| `transient100-f1-r03` vs `-r04` | `7f933ff` | **100 of 100** | the same 5 in both | 95 exact, 0 differ; ledger and conservation 0 |
| `transient100-f1-r01` vs `-r03` | across builds | 100 of 100 | same | - |

`wp2diverge.js` compares, per island, every publication both runs made for the same end tick (the publication fingerprints of `e924eae`); the held islands' common publications, from their retried slice `[0, 50]` on (24 of them in the WP5 comparison), are all bit for bit equal. They sit off the reference grid (their grid is shifted by 50 ticks, so tick 1900 falls between two of their publications), which is why the grid comparison covers 95 islands and the fingerprints are the proof for the other 5. The cross-build pair also shows that the velocity-cap change (`55ba5a0`) and the cold-start slice (`7f933ff`) leave `transient100` untouched: its islands are registered from a checkpoint and never meet a passive clamp with a disagreeing sign. Latency of r03 / r04: ready-to-publication p50 19.51 / 19.98 ms, p95 67.77 / 65.88 ms, engine 0.0068 ms per tick at p50, no held interval in either window, integrity passed.

**Limit of the proof.** It shows that a retry is deterministic given the holds. Which islands are held at start-up is decided by wall time: here the same five in all seven quiet runs of `transient100` (three in WP5, four in F1), but `stress100` held 2 and 4 islands in the `1dac4bd` pair and 2 and 3 in the `7f933ff` pair (one or two held in one run only). An island held in one run and not the other has a different slice partition and, from then on, a different trajectory. That is inherent to a wall budget and was not removed.

## 4. Why 100 islands due at once exceed the budget, and the first dispatch

`transient100-f1-r01`, first slices: the first round dispatches 12 islands at online tick 100 (one per worker) onto a JIT that has compiled nothing. The seven that finish take 1.05 to 1.55 s of wall at a CPU/wall of 0.55 to 0.81 (12 workers on 8 cores / 16 threads, with the JIT's compiler threads and the collector competing); the five heaviest pass 2 s and are cut. Islands dispatched from tick 121 on, with the hot methods compiled, take 0.13 to 1.24 s; the five retries of 50 ticks take 0.46 to 0.64 s at a CPU/wall of about 1. The first round is a barrier too: the seven finished islands publish only when the round closes at 2 s.

Spreading the first dispatch over time would lower the contention of the first round but not the cold JIT, would still leave the outcome to wall time, and would change nothing an island computes; it was not done. Shortening the first slice removes the holds instead of making them less likely, and costs a few short solves. It is done where the holds hurt and the fingerprints are not pinned: an island a topology change creates starts on a one-tick slice and doubles back to the cadence (`7f933ff`). In game that is every placement and every edit; for 100 pumped fills placed at once it took the window from 60 budget holds per 10 s and 12.2 cores to no hold and 0.35 cores (`FLUID_PUMPED_FILL_REVIEW.md` section 6). Islands registered from a save or a checkpoint keep their first slice at the cadence: applying the rule there would change every trajectory of P12, P31 and the paced benchmarks, and the brief pins P12 and P31. Whether to start registered islands cold too - which would also make `transient100`'s start-up hold-free - is left to the owner.

## 5. Tolerance default (owner decision 1)

`certificateStationaryTolerance` defaults to 1e-7 (`1dac4bd`: `CertificatePolicy.defaults`, the `[fluid]` config default and comment; key and range 0 to 1e-6 unchanged). Tests: the flush test runs at 1e-9 and at the default with the allowed remainder scaled by eps_s (a flush decays exponentially, so what is left when an interval first changes by less than eps_s scales with it); the quiet-pipe test reads the default; the never-certifies bitwise test and P12 stay pinned to an explicit 1e-9 policy; the policy-variation cases of `FluidCheckpointFormatTest` vary one value from the new defaults. P12 and P31 fingerprints are byte-identical.

`stress100` on `7f933ff`, 60 s + 60 s, `stress100-f1-off-r02` against `stress100-f1-on-r02` (default 1e-7):

| measure | off | on |
|---|---|---|
| full solves in the window | 1200 | 1028 (-14.3 %) |
| certificates issued / STEADY at the end / most at once | 0 | 40 / 17 / 19 |
| replayed intervals / seconds | 0 | 58 / 938 s |
| reference grid, 97 islands exact in both: inventory / energy deviation | | 6.76e-9 / 9.86e-9 against the 1e-6 budget |
| ledger against the island's own inventory / conservation | | 8.24e-10 / 1.2e-15 (energy 3.1e-12) |
| balance units, integrity | 3.42e-7 / 4.15e-10, passed | 3.43e-7 / 3.06e-10, passed |

The `1dac4bd` pair gave the same figures (1030 full solves, 40 issued, 17 STEADY). WP2 measured 1e-7 over a 120 s window: 92 issued, full solves -16.6 %, inventory 1.0e-8, energy 1.45e-8. At the 60 s window the saving is 14 % and every deviation stays under 1e-8, a hundred times inside the budget, with the ledger conserving to 1e-15.

## 6. Open

* The held set at start-up remains a wall-time outcome for islands registered at their cadence (section 3); starting them cold is an owner decision (section 4).
* Revalidation against the certified interval or its extrapolation (owner decision 2) was deferred by the owner and not touched: at 1e-7 every `stress100` horizon wake still drops and requalifies (31 wakes, no renewal).

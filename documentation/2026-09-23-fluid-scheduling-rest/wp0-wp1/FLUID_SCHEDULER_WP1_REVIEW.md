# Fluid scheduler WP1: deadline scheduler, review of the implementation

Date: 2026-09-23. Branch `claude/fluid-scheduler` (worktree `agent-ae139e4fc1b184b36`), not pushed, not merged.

Commit ids after the rebase onto `main` @ `42fdf41` (2026-09-23; ids before the rebase in parentheses, the measurements below were taken on those):

| Commit | Content |
|---|---|
| `eb28fc5` (`0176080`) | WP0: counters, benchmark profiles, P12/P31 fingerprints (see `FLUID_SCHEDULER_WP0_BASELINE.md`) |
| `a0a791a` (`b12ba0f`) | WP1: deadline scheduler, epoch clocks, event index, recovery gating, module deadlines, drain continuation, unit tests |
| `b697adc` (`0bef3f7`) | WP1: continuation GameTest; benchmark counts a tick with a current deadline as busy |
| `cadd182` (`f3ccbae`) | WP1: a pump leaves no request behind; the world tick pumps after events only when it tried one |
| `5f866a9` | after acceptance: the benchmark summary script's default jar follows `mod_version` (was `0.1.0`) |
| `74b62e3` | after acceptance: each tick checks the wall budget of the open rounds (former deviation 2, see section 4) |
| `5cd7801` | `CHANGELOG.md` entries under `[Unreleased]` |

After the rebase `fluidRuntimeTest` passed 148/148 with P12 and P31 byte-identical to WP0; after `74b62e3`, 149/149 with the same fingerprints.

Plan: `FLUID_ISLAND_REST_PLAN.md` revision 3, section 3.1, section 7 row WP1, section 5 items 1, 3 and 4 as they concern scheduling. Certificates, presentation and persistence are not touched.

## 1. Design as built

### 1.1 Shared epoch (`IslandClock`)

`onlineTick = epoch - base`. The epoch is the world's online tick (`WorldTopologyLedger::onlineTick`), incremented once per server tick in `FluidWorldAuthority.tick`. Restoring a snapshot sets `base = epoch.now() - saved.onlineTick`, so the restored online tick, the committed tick and therefore the debt are exactly the saved ones, and time only moves while the epoch moves, which only happens on a running server: no offline progress. The public API, the `Snapshot` record and every invariant are unchanged. Additions: a constructor taking the epoch, `readyAtTick(fence, span)` (the exact online tick at which `nextSlice` becomes present through time alone, `Long.MAX_VALUE` while only a completion or input change can make it present), `retryAtTick()`, `epochTickAt(online)`. A clock built without an epoch (the old constructor, `fresh()`) reads a constant epoch and moves only through `accrueOnlineTicks`, which now shifts the base; it is no longer called on any tick path, and the existing `IslandClockTest` cases still use it unchanged.

### 1.2 Deadline heap (`IslandScheduler`)

`PriorityQueue<Deadline(tick, sequence, kind, id, generation)>` ordered by tick, then scheduling order. `nextDue()` is a peek. Kinds: `SLICE_DUE`, `RETRY`, `ROUND_TIMEOUT`, `ALLOCATOR_SHRINK`, `MODULE_HORIZON`, `RECOVERY_RETRY`, with `CERTIFICATE_HORIZON` and `PRESENTATION_BUCKET` reserved. Invalidation is lazy; the owner drops a popped entry whose generation no longer matches. Generations come from one coordinator-wide counter, so a replacement island that reuses an identity can never match an entry of its predecessor. The heap is compacted only if its size exceeds four times the live population (islands plus open rounds plus a constant).

### 1.3 Coordinator (`IslandCoordinator`)

Each island is in exactly one of three states:

1. **ready**: its next slice can be dispatched now; it is in the fair queue's ready index.
2. **scheduled**: it holds one `SLICE_DUE` or `RETRY` deadline at `epochTickAt(readyAtTick(fence, span))`.
3. **waiting**: an attempt owns it (running, or closed but not yet terminally drained), or a fence sits at its committed tick, or the coordinator is suspended or stopped. Only an event re-examines it.

"Ready" is exactly the old eligibility test (`no pending attempt && clock.nextSlice(...) present`). `reconsider(island)` re-derives the state of one island and is called at every change of anything the test reads: registration, dispatch, round closure (commit or hold, cadence adaptation, retry span), a straggler's terminal drain, fence install, release and resolve, suspension, resume, topology replacement, and when its deadline fires. Nothing else visits an island.

`tick()` (called once per server tick after the epoch advanced, or advancing a coordinator's own epoch in tests): reset the per-tick dispatch budget, pop due deadlines, then `pumpIfUseful()`. `pumpIfUseful()` is the O(1) test the hooks make: pump only if an owner became ready since the last pump, a completion arrived, a round or allocator deadline fell due, or ready owners meet free capacity (`min(availableWorkers, 64 - dispatchedThisTick, 64 - pending)`, the unchanged capacity rule). `pump()` itself is the old pump: close all-terminal and timed-out rounds, report demand with the ready count (which equals the old eligible count), compute capacity, serve ready owners through the fair queue, one round per pump, unchanged 64-per-tick limit and staging-slot rule.

`FairIslandQueue` keeps the old service order exactly. The old deque rotates every owner it inspects, which is a pointer moving around one fixed cycle; the new queue stores that cycle as ordinal keys plus the service position, indexes only ready owners, and serves the first ready owner after the position (newcomers first, in registration order). A randomized test replays 300 × 600 operations of register, remove, readiness changes, vetoes and service against a copy of the old deque and requires identical service sequences, including a forced key re-numbering.

Round timeout: a `ROUND_TIMEOUT` deadline per round at the tick where the 2 s budget would expire at the nominal 50 ms tick; if it has not expired by wall clock (fast ticks) it re-arms at the measured remainder; if it has, the next pump closes the round. Since `74b62e3` every `tick()` also checks the wall budget of each open round, O(open rounds) and nothing per island, so on a slow server an expired round closes within one tick of its wall deadline (tested with 100 ms ticks: closed at tick 20, the budget ran out at tick 20; the deadline alone would have waited to tick 40). Held retry: the `RETRY` deadline is the retry tick. Allocator shrink: after every demand observation the coordinator asks the dispatcher for `demandShrinkDelay()` (new; `WorkerAllocation.Demand.shrinkTick()` minus the server tick) and books one `ALLOCATOR_SHRINK` deadline, whose pump re-observes demand exactly when the 200-tick hysteresis would apply.

Self-verification: with `-Dcreatecheme.fluid.scheduler.verify=true` (set in `build.gradle` for `test`, `fluidRuntimeTest`, `fluidScienceTest` and the `fluidGameTestServer` run, not for benchmarks) every pump and every tick re-derives every island's readiness, deadline and pending count from scratch and throws on any difference from the incremental state; the fence index is checked the same way. All unit suites and all GameTests ran with it.

### 1.4 World tick (`FluidWorldAuthority`)

```
refreshProperties();            // O(1) by catalog identity
topology.tick();                // epoch increment: every island clock advances
runtime.tick();                 // budget reset, due deadlines, pump if useful
if(topology.hasPendingEvents()){applyPending();runtime.coordinator().pumpIfUseful();}
view refresh queue (unchanged); data.setDirty() (unchanged)
```

* The two module advances per tick are gone. `advanceModules()` runs at startup, on a publication that `dependsOnAny` bound island or follows an owners-map replacement (rebind only then, by identity of the map), when an attempt on a bound island drains after its round closed (the coordinator's new `onReleased` listener books an immediate module deadline), and at `MODULE_HORIZON`: the module host's new `nextHorizon` (active cycle ends via each feed island's clock, pending-input due ticks via the receiver's clock). Module decisions depend on committed state, fences, attempts and the ledger, not on the online tick, so every advance that can change an outcome is reached; the `CausalModuleCoordinatorTest` comparison in section 2.1 shows it.
* Events: `nextReadyEvent` takes owners from the coordinator's new fence index (`fencedIslands(event)`) plus the owners of touched identities, and tests fences with `hasFence`; `applyPending` reads the live ledger (`active()`, `events()`) instead of constructing a snapshot, and `WorldTopologyLedger.readyEvents` looks up only the identities the queued events name instead of copying every registration's position.
* Recoveries: `deliverRecoveries` reads the new O(1) `recoveries()`/`hasRecoveries()` accessors, runs when a recovery is queued (after `applyPending` queued one, and in `recoverFilter` as before) and on a `RECOVERY_RETRY` deadline, 20 ticks later while any recovery is undeliverable (offline player, full inventory, unloaded chunk), 1 tick later when the 64-per-attempt cap was hit, and 1 tick after startup for saved recoveries. Exactly-once delivery and the item path are unchanged.

### 1.5 Completions (`ProcessSolveServices`, `MinecraftFluidRuntime`)

The readiness pump installed after every completion drain is now `coordinator::pumpIfUseful`; a drain that routed fluid completions sets `completionsSincePump`, so the old post-drain pump still runs whenever there was anything to route. `drainCompletions` owes exactly one continuation when it stops at the 64-per-tick budget with completions left: one `server.tell(new TickTask(1, ...))` per exhausted tick. The continuation never drains in the exhausted tick. Calculator/V3 routing and shutdown routing are untouched.

## 2. Gates

All on the final commit `f3ccbae` unless stated; logs and reports in `documentation/fluid-scheduler/wp1-logs/`. One Gradle invocation at a time throughout; no dev client was started.

| Gate | Result | Evidence |
|---|---|---|
| `fluidScienceTest` | **161 tests, 0 failures** | `final-science.log` |
| `fluidRuntimeTest` | **148 tests, 0 failures** (129 existing + `FluidRuntimeDiagnosticsTest` 1 + WP1 additions 18: `IslandSchedulerTest` 5, `IslandClockTest` +5, `IslandCoordinatorTest` +4, `FairIslandQueueTest` +2, `CausalModuleCoordinatorTest` +2), forced re-execution with `--rerun`, scheduler self-verification on | `final-runtime.log` |
| `fluidSolverRegression -PfluidRegressionMode=exact` | **chain-100: max deviation 0.000e+00** in state/moles, temperature, phase fraction and flow; references under `src/test/resources/fluid/regression` untouched (no diff since `2a7bfdd`) | `final-regression.log`, `solver-optimization.json` |
| `fluidNetworkBenchmark` | **30/9, 19/14, 37/3** accepted/rejected substeps for 2/10/100 reservoirs, unchanged | `TEST-...FluidNetworkBenchmarkTest.xml` |
| `runFluidGameTestServer -PfluidGameTestRunId=wp1-r01` | **All 21 required tests passed** (the 20 existing plus the new continuation test), scheduler self-verification on (`-Dcreatecheme.fluid.scheduler.verify=true` confirmed in the run's VM arguments) | `gate-gametest-r01.log` |
| P12 `WorkerTrajectoryEquivalenceTest` | **bitwise unchanged**: `P12-worker-trajectories.json` byte-identical to WP0 (SHA-256 `56332b64...96be57`; all three worker modes `219f9d25...`) | `wp1-logs/P12-worker-trajectories.json` vs `wp0-reference/` |
| P31 `CadenceTrajectoryQualificationTest` | **bitwise unchanged**: `P31-cadence-trajectories.json` byte-identical to the WP0 run (SHA-256 `4dcb80a4...41c645`), including digests over every published interval and, for the adaptive runs, every publication clock (online, committed, retry, cadence), so dispatch timing is also identical | same |

The science, regression and network gates also passed on `0bef3f7` before the last small commit; the GameTest run and the runtime suite ran on the `f3ccbae` content.

### 2.1 New tests

| Test | What it proves |
|---|---|
| `IslandSchedulerTest.deadlinesComeOutByTickThenInSchedulingOrderAndNextDueIsAPeek` | Ordering by tick then scheduling order; `nextDue` is a peek (1,000 calls, size unchanged; head of a 100,000-entry heap); compaction keeps order. |
| `...aRevisionBumpLeavesOnlyStaleEntriesThatFireNothingUntilTheResumeReschedules` | A property suspension (revision bump) invalidates queued deadlines: 150 ticks, 0 deadlines fired, 0 visits, 0 pumps; resume re-derives and dispatches. |
| `...repartitionAndRemovalInvalidateTheReplacedIslandsDeadlines` | After a merge and a removal only the replacement's deadline fires (1 fired, 1 dispatch); the fence index holds nothing for released events. |
| `...stopClearsEveryDeadlineAndLaterTicksDoNothing` | Stop empties the heap; 500 ticks do nothing. |
| `...idleIslandsCostNothingPerTickAtOneHundredOrAThousand` | 1, 100 and 1,000 idle islands (fenced at their committed tick, the only no-work state before certificates): over 10,000 ticks 0 island visits, pumps, dispatches, deadlines, snapshots, module scans and topology snapshots; online time still accrues to 10,000. A transient island beside them, over 2,000 ticks (20 solves), costs exactly the same number of island visits whether it has 1, 100 or 1,000 idle neighbours. |
| `IslandClockTest` (+5) | Epoch-derived online time; restart on another epoch keeps saved debt exactly and adds no offline time; a fence at the committed tick has no time deadline; the retry span is the exact ready tick; `readyAtTick` agrees with `nextSlice` for 20,000 random clock states at 5 times each. |
| `IslandCoordinatorTest` (+4) | Round timeout closes from its deadline alone (re-arming while the wall budget remains); a held owner is not visited before its retry deadline and is dispatched exactly at it; the per-tick dispatch budget resumes on the next tick without polling, in registration order; the allocator shrink is observed exactly at its deadline with no other activity (no demand observation for 199 ticks, shrink at tick 200). |
| `FairIslandQueueTest` (+2) | Service order equals a copy of the old rotating deque under 180,000 random operations; a forced key re-numbering keeps the order. |
| `CausalModuleCoordinatorTest` (+2) | Deadline-driven advance reproduces the polled host publication for publication (island clocks, fences, inventories bitwise, module cycles, holdups, pending products) in the coupled, empty-cycle and recycle scenarios and across a buffer removal (stranding); module scans fall from 964/946/946 (polled) to 31/28/28. |
| `FluidCompletionBudgetGameTests.anExhaustedBudgetOwesOneContinuationThatNeverReentersItsTick` | 65 chained jobs, then nothing: 64 routed in the exhausted tick, exactly one continuation owed, the continuation runs inside that tick and defers without draining, the 65th is routed on one later tick, no ownership leaked. |

## 3. Benchmarks: before and after

Same settings both sides: `-PfluidBenchmarkWorkers=0` (12 automatic workers on 16 logical processors), 60 s warm-up, 120 s measurement, JFR profile on. Baseline = WP0 commit `0176080` (artifact `80fc302e...`), WP1 = `f3ccbae` (artifact `7da26c6f...`). All runtime audits PASS with 0 runtime errors, every run 0 held and 0 approximate intervals, integrity passed. Reports: `build/reports/fluid/M9/<profile>-{baseline,wp1}-r01/report.json` (plus `transient100-*-r02/r03`).

### 3.1 Counters on idle ticks (mean per idle tick, baseline → WP1)

| counter | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| islandVisits | 700 → **0** | 700 → **0** | 700 → **0** | 700 → **0** |
| moduleScans | 0 → **0** | 0 → **0** | 0 → **0** | 0 → **0** |
| topologySnapshots | 1.00 → **0** | 1.00 → **0** | 1.00 → **0** | 1.00 → **0** |
| readinessPumps | 3.00 → **0** | 3.00 → **0** | 3.00 → **0** | 3.00 → **0** |
| islandSnapshots, viewBuilds, menuPackets, dispatches | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 |
| idle ticks / ticks | 2280 / 2400 → 2280 / 2400 | 2279 / 2399 → 2280 / 2400 | 2280 / 2400 → 2279 / 2399 | 2280 / 2400 → 2280 / 2400 |
| idle ticks with any island visit, topology snapshot or pump | 2280 → **0** | 2279 → **0** | 2280 → **0** | 2280 → **0** |

### 3.2 Counters over all ticks (per wall second, baseline → WP1)

| counter | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| islandVisits | 15942 → 80.0 | 16156 → 80.0 | 15475 → 80.0 | 16130 → 80.0 |
| topologySnapshots | 20.0 → 0 | 20.0 → 0 | 20.0 → 0 | 20.0 → 0 |
| readinessPumps | 76.1 → 18.5 | 78.9 → 19.2 | 72.7 → 17.7 | 78.2 → 19.1 |
| deadlinesFired | n/a → 20.0 | n/a → 20.0 | n/a → 20.0 | n/a → 20.0 |
| solvesDispatched, completionsRouted, islandsPublished, islandSnapshots | 20.0 → 20.0 | 20.0 → 20.0 | 20.0 → 20.0 | 20.0 → 20.0 |
| moduleScans, viewBuilds, menuPackets, drainContinuations | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 |

The remaining work is proportional to solves: 4 island visits per interval (deadline, fair-queue pick, admission, round closure) and one deadline per interval. No fixture has modules, open menus or loaded devices, so module scans, view builds and menu packets stay zero here; module scans are measured in the unit comparison (964 → 31).

### 3.3 Throughput and latency, with run-to-run noise (`transient100`, three runs per side)

| metric | baseline r01 / r02 / r03 | WP1 r01 / r02 / r03 | baseline range | WP1 range |
|---|---|---|---|---|
| accepted intervals / s | 20.000 / 19.992 / 19.999 | 19.992 / 19.992 / 20.000 | 19.992 to 20.000 | 19.992 to 20.000 |
| ready-to-publication p50 ms | 21.26 / 20.97 / 23.80 | 20.16 / 21.97 / 23.57 | 20.97 to 23.80 | 20.16 to 23.57 |
| ready-to-publication p95 ms | 65.59 / 67.11 / 73.23 | 65.02 / 68.16 / 70.94 | 65.59 to 73.23 | 65.02 to 70.94 |
| ready-to-publication max ms | 79.38 / 74.84 / 87.71 | 72.56 / 76.99 / 85.79 | 74.84 to 87.71 | 72.56 to 85.79 |
| dispatch-to-publication p50 ms | 5.38 / 5.47 / 6.57 | 5.57 / 5.93 / 5.67 | 5.38 to 6.57 | 5.57 to 5.93 |
| dispatch-to-publication p95 ms | 15.92 / 16.25 / 17.39 | 15.22 / 16.15 / 17.58 | 15.92 to 17.39 | 15.22 to 17.58 |
| worker p50 ms | 4.04 / 4.01 / 4.66 | 3.93 / 4.20 / 4.27 | 4.01 to 4.66 | 3.93 to 4.27 |
| worker p95 ms | 10.33 / 11.01 / 9.50 | 9.95 / 12.18 / 11.49 | 9.50 to 11.01 | 9.95 to 12.18 |
| engine server ms per tick p50 | 0.903 / 0.978 / 1.179 | 0.0235 / 0.0237 / 0.0262 | 0.90 to 1.18 | 0.024 to 0.026 |
| engine server ms per tick p95 | 1.222 / 1.256 / 1.824 | 0.0995 / 0.1179 / 0.0995 | 1.22 to 1.82 | 0.10 to 0.12 |
| whole tick ms p50 | 1.151 / 1.218 / 1.546 | 0.264 / 0.281 / 0.346 | 1.15 to 1.55 | 0.26 to 0.35 |
| fill time constant s | 3934 / 3934 / 3936 | 3934 / 3934 / 3934 | | |

Noise observed on the baseline side alone: ready-to-publication p50 ±7 % around its mean (21.0 to 23.8 ms), p95 ±6 % (65.6 to 73.2 ms), dispatch-to-publication p50 ±10 %, worker p95 ±7 %. Every WP1 throughput and latency value lies inside, or within a few percent of, the baseline range, and the ranges overlap for every metric: throughput and latency are unchanged within noise. No speedup is claimed for them. The fixture does not stress capacity (100 islands at a 5 s cadence need 20 solves/s; worker CPU occupancy is under 1 %), so the latency distribution is set by the cohort (all 100 islands fall due in one tick and the 64-per-tick dispatch limit carries 36 into the next tick), which WP1 deliberately preserves.

What did change, with disjoint ranges over three runs each, is the server-thread engine time (`FluidRuntimeMeter`, the tick hooks plus mailbox completion work): p50 0.90 to 1.18 ms per tick before, 0.024 to 0.026 ms after, and whole-tick p50 1.15 to 1.55 ms before, 0.26 to 0.35 ms after. That is the per-tick scanning removed; it is reported as measured, not as a throughput gain.

Single runs of the other profiles (baseline → WP1): `stress100` ready-to-publication p50/p95 9.8/57.2 → 12.6/58.5 ms, engine p50 1.235 → 0.023 ms; `rest100` 9.4/57.9 → 7.9/56.6 ms, engine 1.145 → 0.024 ms; `mixed100` 16.2/59.6 → 15.3/60.2 ms, engine 0.855 → 0.023 ms; the 50 transient ladders inside `mixed100` 17.4/59.7 → 16.7/60.7 ms. All are within the `transient100` noise band.

## 4. Deviations from the plan and why

1. **Pump call sites.** The plan removes "the two redundant per-tick invocations". They are gone as unconditional pumps; in their place are O(1) `pumpIfUseful` checks (one after the coordinator's due deadlines in `tick()`, one after `applyPending` on ticks with queued events) and the post-drain readiness pump became the same check. A check costs a few flag reads and, only while a ready owner is waiting, one `availableWorkers()` call. This keeps the exact dispatch tick of every case the old unconditional pumps covered: a ready owner released by the per-tick budget reset, by an event applied in the same tick, or by capacity freed between ticks.
2. **Round timeout under server lag: resolved in `74b62e3`.** As first built, a timed-out round on a server slower than 20 TPS with no other activity closed at the deadline's nominal tick, up to the lag factor late. The coordinator now also checks every open round's wall budget in `tick()` (O(open rounds), no island work), so the old wall-clock semantics hold again: closure within one tick of the wall deadline, tested with 100 ms ticks.
3. **Drain continuation.** `new TickTask(1, ...)` does not delay a task in Minecraft 1.21.1 (`shouldRun` is `tick + 3 < tickCount || haveTime()`, and the idle phase always has time), so a continuation posted in the exhausted tick runs in that tick. It therefore checks the tick itself and does not drain there (the GameTest counts one such deferral); the owed drain is done by the next tick's completion hook, which `AGENTS.md` permits and which also carries the solve service's once-per-tick expiry and dispatch duty. If the task instead runs in a later tick (a blocked mailbox), it drains itself. It cannot spin (one task per exhausted tick) and nothing strands.
4. **Recovery triggers.** Plan 3.1 lists player login and chunk load as delivery triggers; the brief asks for the bounded retry deadline. Implemented: attempt when queued, and a 20-tick `RECOVERY_RETRY` while any recovery is undeliverable (1 tick when the 64-per-attempt cap was hit; 1 tick after startup for saved recoveries). A logging-in player or loading chunk is therefore served within 1 s instead of on the next tick; no login or chunk-load hook was added.
5. **Straggler wake for modules.** Not in the plan: an attempt that drains after its round was closed (a cancelled job) changes `aligned()` for its island without any publication. The old per-tick advance saw that; the coordinator now reports it (`onReleased`) and the authority books an immediate `MODULE_HORIZON` for a bound island, so the module sees it at the same point in the next tick as the old first advance did.
6. **Module horizons are a safety net in WP1.** Module decisions do not depend on the online tick, and every cycle end and input due tick is reached through a publication of the island concerned, which already advances the host. The `MODULE_HORIZON` deadline is scheduled as the plan asks and will matter once certified islands advance without publishing (WP2).
7. **Allocator observation after calculator-only completions.** The allocator's shrink timing is exact (deadline, tested). But a drain that routed only V3 calculator completions no longer triggers a fluid demand observation unless a ready fluid owner can use the freed worker; the observation happens at the next fluid pump. This affects only the admitted worker count, never dispatch order or physics.
8. **Existing clock tests.** `IslandClock` tests that drive a clock directly keep the detached (constant-epoch) path through `accrueOnlineTicks`; the new tests inject an epoch. Both are exercised.
9. **P31 report format** changed in WP0 (ordered maps and digests) because the original `Map.of` output was not byte-stable across JVMs; "identical to the WP0 run" refers to that WP0 report.
10. **Benchmark idle definition** also excludes ticks on which a current scheduler deadline fired. WP0 builds have no such counter, so their idle ticks are defined identically.
11. **`workers = 0`** (12 automatic workers) follows the brief and the earlier `stress100-baseline-auto-r01`; `AGENTS.md` asks for 8 to 10 workers in large solver campaigns.
12. **Commit attribution.** The commits end with the session's attribution line, `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`, as `AGENTS.md` asks; the coordinator confirmed this.

## 5. Open items

* Plan section 5 item 1 mentions a real-executor idle-scaling variant in `WorkerTrajectoryEquivalenceTest` style; the idle-scaling test uses a fake dispatcher (the executor plays no part when nothing is due).
* Plan section 5 item 4 lists shared V3 contention; the `contention` benchmark profile was not run in this package. Cancellation, stop and property-hold ownership are covered by the existing and new unit tests.
* No fixture exercises modules, loaded devices or open menus at scale; `viewers` and a module profile belong to WP3 and WP5.
* For WP2: `transient100` cannot certify (its per-interval change moves by about 9e-8 per interval, 90 times `eps_s`), while the `stress100` through-flow ladders are the intended STEADY case. Heap compaction (stale entries over four times the live count) is a memory bound only and is not instrumented, so whether it ever ran in the benchmarks is not known; it is covered by the scheduler unit test.
* Nothing is pushed or merged.

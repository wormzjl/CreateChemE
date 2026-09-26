# Fluid scheduling, rest, and steady-flow plan review

Date: 2026-09-23  
Status: REVIEW AND USER RESPONSES ONLY. No implementation, merge, build, benchmark, or game run performed.

Reviewed [FLUID_ISLAND_REST_PLAN.md](FLUID_ISLAND_REST_PLAN.md) and the Java source in this worktree at `1403eeb` (the candidate named by the plan). The main workspace was at `3c27271`. Findings below refer to the candidate, not the older main-workspace source. Source line numbers are those inspected at review time.

## Outcome and user decisions

The proposed sleep optimization is useful but is not sufficient for the updated requirement. The design must remove recurring island/module scans, separate simulation work from Minecraft presentation updates, and save calculation effort for qualifying steady-flow networks as well as zero-flow networks.

| Decision | User response | Required interpretation |
|---|---|---|
| 1. Add a clock operation for advancing a resting island without solving | Yes | Approved in principle. Preserve event ordering, fence boundaries, online-time limits, and outstanding-worker ownership. |
| 2. Persist resting state | Yes | Avoid restart bursts and validate the saved sleep decision against current physics/settings. Old-save compatibility is not required. |
| 3. Default periodic rest rechecks to zero | Yes | Record `restRecheckSeconds = 0` as the accepted default. This does not validate the current tiny-flow argument. Establish when permanent sleep is justified; use dependency invalidation and separately bounded approximations for microscopic drift. |
| 4. Advance sleeping clocks during a property-data hold | Needs a real case | Not approved yet. A real existing reload test and recommendation appear below. |
| 5. Leave steady flow outside the optimization | User rejects a blanket exclusion | Save calculation effort when flow rate and contents remain constant or vary only microscopically. Continue correct material and energy accounting. |
| 6. Start before or after the previous work merges | Merge previous work first | Future sequence: merge the previous candidate, then branch from updated main. Do not merge during this review-only task. |

Additional user requirement: the mod must not calculate its process state on Minecraft's per-tick loop. Minecraft tile entities/block entities should receive routine updates roughly every five seconds.

Follow-up scope decision: no save compatibility work is required. Remove old-format migration, absent-field normalization, and legacy-save test gates from the implementation plan. New saves must still round-trip valid clocks, material, pending events, and sleep state.

Recommended precise interpretation: process work is driven by changed inputs, due simulation deadlines, and worker completions; loaded presentation receives coalesced snapshots on an approximately five-second schedule. Minecraft thread ownership still applies to world access and publication. Keeping a Minecraft-compatible time coordinate is different from recalculating or scanning every island on every Minecraft tick.

Timing assumption for the eventual implementation: five seconds means 100 online ticks at nominal 20 TPS, retaining the current no-offline-progress policy. This is a review recommendation, not an instruction from the user to change simulation to wall-clock time. Under server lag, 100 ticks takes longer than five wall seconds.

## Confirmed code behavior

The fluid block entity is already a presentation binding. It has no fluid solver ticker. A sweep of `src/main/java` found the central server post-tick listener and the fluid menu broadcast callback, but no custom `getTicker`/`BlockEntityTicker` implementation. The relevant recurring work is in the central runtime.

Current path:

```text
ServerTickEvent.Post
  -> drain completed worker calculations
       -> refresh property compatibility
       -> pump island readiness
  -> FluidWorldAuthority.tick
       -> refresh properties; advance world clock
       -> advance every module
       -> IslandCoordinator.tick
            -> advance every island clock
            -> pump island readiness
       -> apply pending topology; check recoveries
       -> advance every module again
       -> pump island readiness again
       -> refresh up to 64 queued device views
       -> mark saved data dirty

Worker completion
  -> coalesced server-thread mailbox notification
  -> bounded completion drain
  -> pump island readiness

Each open fluid menu
  -> broadcastChanges
  -> every 10 ticks: construct and send a fresh complete menu state
```

Evidence: [server hook](../../src/main/java/com/wormzjl/createcheme/CreateChemE.java#L264), [completion router](../../src/main/java/com/wormzjl/createcheme/network/ProcessSolveCoordinator.java#L25), [world tick](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidWorldAuthority.java#L153), [island tick/pump](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/IslandCoordinator.java#L146), [menu](../../src/main/java/com/wormzjl/createcheme/world/inventory/FluidDeviceMenu.java#L41).

This is repeated scheduling work, not a claim that a full fluid solve currently runs every tick. A fresh island normally requests 100-tick intervals; adaptation can change that.

## Findings that must be addressed before implementation

Priorities here describe risk to the proposed implementation and the updated requirements. They do not imply that all these are existing production correctness defects.

### P1 — Adding sleep flags still leaves all-island work on every tick

**Confirmed:** `IslandCoordinator.tick` visits every island to accrue one tick. `pump` scans all islands for eligibility, and the round-robin readiness queue scans owners again. The world path invokes pump multiple times per ordinary tick, in addition to completion-driven invocations.

**Plan gap:** excluding sleeping islands from eligibility still requires visiting them; the planned per-tick fast-forward and `drives` query add more per-sleeper work. This does not meet the user's objection to “checked each tick.”

**Required revision:** maintain due work and dependency indexes. Sleeping islands should have no recurring readiness entry unless they have an actual scheduled event or an enabled recheck deadline. Derive elapsed online time from a shared epoch and materialize a sleeping island's effective clock on relevant events, save, or requested presentation. Do not rewrite every sleeping island clock each tick.

Maintain fairness among ready owners and invalidate stale scheduled entries after revisions, repartition, removal, or shutdown. Do not create a new perpetual timer per island.

Evidence: [IslandCoordinator.java:146](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/IslandCoordinator.java#L146), [eligibility scan:161](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/IslandCoordinator.java#L161), [FairIslandQueue.java:16](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FairIslandQueue.java#L16).

### P1 — Removing the tick pump without replacing its duties can strand work

**Confirmed:** completion wakeups already exist, but the completion drain is capped per server tick. If that budget is exhausted, `drainCompletions` returns without draining remaining messages. The normal tick callback currently supplies another opportunity. Round deadlines, held-owner retry times, dispatch-budget resets, and worker-pool shrink observations also depend on future pump calls.

**Failure scenario for a naive rewrite:** several completion callbacks exhaust the current drain budget, later notifications arrive in the same tick, and then no more workers finish. Removing the normal tick callback without scheduling a continuation leaves completed work queued indefinitely. Conversely, immediate self-rescheduling within the same exhausted budget can spin.

**Required revision:** schedule a bounded continuation when budget remains exhausted, and explicit wakeups for solve cadence, retry, round timeout, and allocator shrink deadlines. Preserve the shared service's calculator/V3 jobs and shutdown routing. A five-second UI refresh must not become the only opportunity to retire jobs or apply due events.

Keep existing server-thread authority and worker isolation. Worker/timer threads may signal the server mailbox; they must not mutate world, clocks, module ledgers, or block entities directly. If the platform adapter requires a constant-time due-deadline check, identify it explicitly; it must not conceal per-island work.

Evidence: [ProcessSolveServices.java:73](../../src/main/java/com/wormzjl/createcheme/runtime/ProcessSolveServices.java#L73), [drain budget:198](../../src/main/java/com/wormzjl/createcheme/runtime/ProcessSolveServices.java#L198), [CompletionWakeup.java:17](../../src/main/java/com/wormzjl/createcheme/runtime/CompletionWakeup.java#L17), [round closure:156](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/IslandCoordinator.java#L156), [WorkerAllocation.java:53](../../src/main/java/com/wormzjl/createcheme/runtime/WorkerAllocation.java#L53).

### P1 — Sleep validity must include more than the existing save property revision

**Confirmed:** the save's property revision uses `thermodynamicRevision`. Maximum velocity, trace cutoff, and solid transport settings appear in the broader `ApproximationAnchor.revision`, not that saved thermodynamic identity. A save can therefore be readable after a relevant numerical/transport setting changes without its previous sleep decision still being valid.

**Required revision:** persist or reconstruct a sleep-validity signature covering relevant physics, transport settings, sleep policy, and topology/control state. Preserve inventory when a sleep certificate becomes invalid, but wake the island. Check pending module inputs/withdrawals before trusting restored sleep. `restDetection=false` must override a saved resting flag and really restore the normal scheduling path.

Do not claim that waking on live property resume alone covers restart invalidation.

Validate the new sleep fields themselves: the awake sentinel, a nonnegative sleep start no later than committed time, valid fences, and a qualified state. This is new-format correctness, not a requirement to read old saves.

Evidence: [encode/decode revision:103](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidCheckpointCodec.java#L103), [load validation:141](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidCheckpointCodec.java#L141), [revision separation:13](../../src/main/java/com/wormzjl/createcheme/science/fluid/network/ApproximationAnchor.java#L13).

### P1 — The rest cutoff does not justify permanent freezing

**Plan defect:** the threshold is `1e-9 kg/s + 1e-12 /s * combined endpoint mass`. The absolute term invalidates the “longer than 30,000 years” statement for small inventories. For 1 gram combined inventory, the threshold is approximately `1e-9 kg/s`, enough to transport that inventory in approximately 1,000,000 seconds, or 11.6 days.

Also, observing small flows for two intervals does not prove that future flows remain small. The claimed bound `floor * duration` assumes a future flow bound that the criterion has not established; even that linear bound grows with duration.

**Required revision:** separate validated equilibrium from bounded slow evolution. Qualify conserved component quantities, internal energy, phases, control modes, solid closures, filter loading/energy, and external drives as applicable. Keep the gross-forward/reverse test; average zero alone is insufficient. Define physical absolute and relative error budgets and how small inventories are handled. Do not turn an arbitrary near-zero signal into an unlimited-duration identity map.

The approved no-periodic-recheck default stands for valid rest. A network with genuine microscopic evolution needs a bounded accelerated path or a predicted deadline when its accumulated error/state change requires recalculation.

Evidence: [plan rest criterion](FLUID_ISLAND_REST_PLAN.md#3-rest-criterion), [plan claimed error bound](FLUID_ISLAND_REST_PLAN.md#5-what-this-does-and-does-not-change).

### P1 — The new steady-flow requirement needs its own conservative fast path

**Scope change:** the plan explicitly excludes steady-flow replay. The user now requests savings for constant or microscopically varying flow and contents, so that exclusion is superseded.

A valid optimization must reduce repeated nonlinear work while accounting for elapsed material and energy transport. It must not repeatedly publish the old inventory/result as though a new interval had been solved.

| Real situation | Appropriate treatment |
|---|---|
| Settled isolated tank or closed, balanced tank pair | Sleep when equilibrium is qualified; no transport accounting is needed while unchanged. |
| Fixed generator to fixed void, settled fluid state and no evolving filter/solid state | Candidate for cached transfer rates or an analytically qualified steady-state path. Account for boundary transport over elapsed time. |
| Tank with matched inlet/outlet mass flow | Qualify each component and energy as well as total mass. Equal mass flow can still replace contents or change temperature. |
| Nearly constant flow through a finite tank | Bound depletion/filling, composition drift, energy drift, and the next physical transition. Stable flow alone does not mean stable inventory. |
| Slurry through a filter | Cake mass and energy accumulate; resistance/capacity may change. Predict a valid interval to the next transition or keep full integration. |
| Pumped recirculation loop | Pump work can raise internal energy even with nearly constant circulation. A flow-only criterion is insufficient. |

**Required revision:** specify entry evidence, cached rates/coefficient validity, conservation equations, error accumulation, event boundaries, and invalidation. Recompute before depletion, overflow, phase/mode change, filter blockage, or an external input change can invalidate the cached solution. Scale any interval-integrated transport by actual elapsed duration; a fence can shorten an interval.

The existing retained solver already reuses working structures and step hints, and approximate fallback has its own bounded-degradation contract. Neither is a general unlimited steady-state replay facility. Do not silently repurpose fallback allowance or mark unverified replay as FULL. The original promise that all science code stays untouched may need revisiting once this path is designed.

Evidence: [RetainedSolver.java:42](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/RetainedSolver.java#L42), [approximation guard:41](../../src/main/java/com/wormzjl/createcheme/science/fluid/network/ApproximationAnchor.java#L41), [filter transport and pump work:245](../../src/main/java/com/wormzjl/createcheme/science/fluid/network/ConservativeTransport.java#L245).

### P1 — Batched clock advancement needs a full-window dependency check

**Plan gap:** the fast-forward target is `min(onlineTick, fence)`, but the proposed drive query only covers `committedTick` through `committedTick + 1`. Those intervals can differ when the island enters rest with debt or scheduling becomes batched.

Existing input fences provide protection for announced deliveries; the review does not assert an already-demonstrated lost delivery. The design still needs a precise contract for every drive type and for boundaries installed during module progression.

**Required revision:** determine the earliest relevant event over the entire proposed advance, stop there, apply it once, and reconsider the state. Positive withdrawal intervals must not be skipped; known-zero cycles must still resolve their horizons. The new clock method itself has no fence argument, so its sample implementation cannot independently enforce the plan's “never passes a fence” test. Put the guard at a clearly specified layer and test that layer.

Also reconcile normal two-interval qualification with the proposal that one successful periodic recheck immediately restores rest; that requires an explicit state transition.

Evidence: [clock eligibility:38](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/IslandClock.java#L38), [fences/alignment:252](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/IslandCoordinator.java#L252), [withdrawal continuity:113](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FixedSplitModule.java#L113), [plan mechanism](FLUID_ISLAND_REST_PLAN.md#41-coordinator-state-machine).

### P2 — Empty recovery checks rebuild the entire topology every tick

**Confirmed:** `deliverRecoveries` calls `topology.snapshot().recoveries()` unconditionally from the world tick. After the online clock advances, `snapshot()` constructs a new snapshot. Its constructor traverses registrations, validates component arrays, and builds a position set, even when the recovery map is empty.

This is more than the “trivial snapshot record” described in the plan: it is world-size validation/allocation on the idle path.

**Required revision:** inspect recovery availability without materializing the whole topology. Process recovery records only on actual work/dependency changes, with bounded retry scheduling for offline players, full inventories, or unloaded chunks. Preserve exactly-once item delivery.

Evidence: [recovery loop:160](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidWorldAuthority.java#L160), [snapshot construction/validation:49](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/WorldTopologyLedger.java#L49), [snapshot():81](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/WorldTopologyLedger.java#L81).

### P2 — Module polling would undermine event-driven sleep

**Confirmed:** world tick calls `moduleHost.advance()` twice, and publication calls it again. `advance` visits all modules and pending transfers. `published` also calls `rebind(owners)` even when the publication is an ordinary solve with no ownership change.

A per-island `drives` implementation copied directly from command assembly would add repeated scans over modules/transfers. Calling `withdrawals` casually is also unsafe as a generic query: it validates exact start/end horizon and receipt continuity.

**Required revision:** index dependencies by island/buffer, register the next due module horizon/input, and notify affected islands on changed capacity, receipts, cycles, or topology. Rebind on actual ownership changes. Use a side-effect-free drive/deadline query with a specified range contract. Park blocked/stranded modules until a relevant dependency changes, while retaining their owned material and promises.

Evidence: [world tick:153](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidWorldAuthority.java#L153), [publication:350](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidWorldAuthority.java#L350), [module advance:88](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/CausalModuleCoordinator.java#L88), [command assembly:127](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/CausalModuleCoordinator.java#L127).

### P2 — Presentation refresh is neither five-second nor consistently coalesced

**Confirmed:** open fluid menus send every 10 ticks, approximately 0.5 seconds. Each send rebuilds `world.view`, controls and material names and includes components/presets. Publications enqueue every member device, then up to 64 are refreshed each tick. Adaptive solver cadence ranges from 20 to 400 ticks; presentation currently follows that variable publication rate.

The block entity's `acceptFluidView` only assigns a local `lastView`; it does not itself send a client packet. Its update tag contains identity only. Menu state packets are the separate live-data path. These must not be conflated.

**Required revision:** decouple presentation frequency from numerical cadence. Coalesce the latest view per dirty loaded device/open subscription and serve routine updates around every 100 ticks, preferably staggered to avoid a large five-second burst. Build expensive views only for actual consumers. Reuse static menu metadata until its revision changes.

Recommended exceptions are immediate identity binding, an initial menu snapshot, and edit acknowledgement/error feedback; these are discrete events, not recurring process calculations. Confirm those exceptions in the revised specification. Avoid duplicate refresh work between block-entity onLoad, queued chunk-load updates, and identity binding.

Evidence: [menu:41](../../src/main/java/com/wormzjl/createcheme/world/inventory/FluidDeviceMenu.java#L41), [packet construction:103](../../src/main/java/com/wormzjl/createcheme/network/FluidNetwork.java#L103), [block entity:23](../../src/main/java/com/wormzjl/createcheme/world/level/block/entity/FluidDeviceBlockEntity.java#L23), [view construction:271](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidWorldAuthority.java#L271), [refresh queue:157](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidWorldAuthority.java#L157), [adaptive cadence:57](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/IslandClock.java#L57).

### P2 — Save cost and retained memory remain after dropping solver caches

**Confirmed:** every world tick marks SavedData dirty. On save, the live capture calls `replace`, which clears both encoded caches and re-encodes the full checkpoint/topology. Dropping `retained` also leaves authoritative inventory, graph, fences, last result, approximation anchor, physical mappings, and the compiled topology representation.

**Required revision:** separate material/topology changes from clock representation and cache unchanged serialized payloads where appropriate. Preserve online clocks and debt at save boundaries; simply removing `setDirty` can lose elapsed-time state. Do not key reusable sleeping-state payloads solely by a committed tick that changes on every materialization.

Drop solver handles only after accepted terminal completion, and never clear a cache still owned by an old worker. Retain the current cancellation rule that ownership ends on actual terminal draining. Measure retained heap after GC and report released solver memory separately from necessary saved state.

The worker allocator has a minimum admitted parallelism of one. Zero active fluid workers and zero fluid demand are meaningful targets; zero configured workers is not what the current pool guarantees, and other job families share it.

Evidence: [dirty tick:158](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidWorldAuthority.java#L158), [save:43](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidSavedData.java#L43), [island state:55](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/IslandCoordinator.java#L55), [worker minimum:58](../../src/main/java/com/wormzjl/createcheme/runtime/WorkerAllocation.java#L58).

### P2 — Benchmark assumptions no longer match the expanded optimization

**Confirmed:** only the literal `stress100` profile takes the existing timed stress path. Other ordinary profiles wait for accepted solve samples per island. Merely adding sleeping fixtures risks waiting forever for samples that correctly stop arriving. The current stress fixture also deliberately leaves its chunks unloaded, so it cannot measure TE/menu synchronization overhead.

**Required revision:** give rest/mixed/steady profiles explicit elapsed-window termination and state assertions. Report actual full solves, accelerated transport intervals, identity-advanced time, and retained debt separately. A steady-flow fast path may validly reduce `stress100` solver count/cost; “unchanged cost” is no longer the correct acceptance requirement. Preserve physical trajectories/conservation and add a deliberately changing, non-static fixture that cannot qualify for reuse.

Add loaded-device and open-menu cases, many sleeping islands, mixed sleeping/transient/steady islands, module-coupled networks, and save/reload measurements. Count coordinator visits, module scans, topology-snapshot construction, view builds, packets, and nonlinear solves as well as CPU/heap. Measure baseline and candidate under identical observation overhead. One baseline run is diagnostic evidence, not a robust noise estimate.

Evidence: [stress selection:45](../../src/fluidGameTest/java/com/wormzjl/createcheme/fluid/gametest/FluidServerBenchmark.java#L45), [termination:87](../../src/fluidGameTest/java/com/wormzjl/createcheme/fluid/gametest/FluidServerBenchmark.java#L87), [unloaded fixture:202](../../src/fluidGameTest/java/com/wormzjl/createcheme/fluid/gametest/FluidServerBenchmark.java#L202), [current interval metrics:289](../../src/fluidGameTest/java/com/wormzjl/createcheme/fluid/gametest/FluidServerBenchmark.java#L289).

## Decision 4: a real property-suspension case

The repository already has a concrete test, [betweenTickRoutingHoldsChangedScienceAndRestoringItResumesConservedState](../../src/fluidGameTest/java/com/wormzjl/createcheme/fluid/gametest/FluidPropertyReloadGameTests.java#L21).

1. A reservoir has completed a FULL interval under the startup material definitions.
2. The test changes `data/createcheme/materials/properties/crude_pc07.json`, setting molecular weight to `0.31 kg/mol`, and publishes that catalog.
3. It drains worker completions before the next ordinary world tick. The runtime must enter a property-data hold before an old result can commit.
4. It waits ten ticks. Committed simulation time and inventory must remain unchanged; online time must increase so the debt is preserved.
5. It restores the original catalog. The held runtime resumes full solving using its original qualified model.

The saved checkpoint is also checked to reject loading under the changed thermodynamic basis. This is a compatibility hold, not a supported hot-swap to new physics.

**Correction to the original plan and earlier explanation:** `Island.model` is final; `resumeQualifiedProperties` does not install a new model. The guard permits resume after compatible original scientific definitions are restored. Cosmetic name changes do not require the scientific hold. See [FluidPropertyReloadGuard.java:18](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidPropertyReloadGuard.java#L18) and [coordinator hold/resume:125](../../src/main/java/com/wormzjl/createcheme/runtime/fluid/IslandCoordinator.java#L125).

**Recommendation:** do not fast-forward committed time during this hold in the first implementation. Stop scientific clock commits, retain elapsed online time lazily, and invalidate/requalify sleep on compatible resume. This preserves an existing tested invariant and need not create per-tick island work. An isolated tank may happen to have identical contents either way, but advancing its committed clock during a hold would still deliberately change the contract.

If advancing certified isolated sleepers during a hold is desired later, specify which dependencies and queued events make that safe, how save/restart represents the hold, and why the existing no-commit invariant should change. Decision 4 remains unresolved by the user; this document does not record a yes on their behalf.

## Proposed revised design boundaries

| Concern | Proposed approach |
|---|---|
| Authoritative state | Server-owned immutable snapshots and atomic material/clock publication remain the authority. TEs are views. |
| Simulation time | Retain online simulated time; derive elapsed time without touching every island each tick. No new offline or wall-time advancement policy. |
| Transient islands | Schedule due intervals and causal boundaries; reuse existing solver caches while useful. |
| Certified rest | Remove from recurring readiness work; release heavy solver caches; materialize identity time only when needed. |
| Qualified steady flow | Use conservative cached/analytic rates or larger validated intervals; account for transported components and energy. |
| Microscopic evolution | Use cumulative physical error budgets and a validity horizon; do not freeze unlimited drift. |
| Wakeups | Index topology/control/property changes, deliveries, withdrawals, capacity changes, and relevant future physical processes. |
| Minecraft interface | Routine coalesced TE/menu updates around five seconds; discrete lifecycle/interaction events handled explicitly. |
| Ownership/lifecycle | Preserve revision checks, bounded admission, terminal-drain ownership, property checks before publication, and shutdown. |
| Persistence | Save clocks, material, dependencies, and validated optimization state. No old-save migration or compatibility requirement. |

A purely runtime-only static-sleep change may still be one implementation phase, but it must not be presented as completing the user's expanded request. Replacing broad polling and qualifying stable-flow reuse are now substantive design work. The original estimate of approximately 150 runtime lines does not cover this scope.

## Verification required for the eventual implementation

No checks below were executed in this review.

1. **Idle scaling:** after rest, run 1, 100, and 1,000 islands with no viewers/events. Show no per-tick island/module scans or topology validation; solve counters stay flat. Distinguish global platform overhead from per-island overhead.
2. **Presentation:** loaded and unloaded devices, zero/one/many open menus, steady and transient islands. Count routine view builds and packets around the five-second target and prove UI cadence does not control scientific progress.
3. **Exact causality:** install an event between presentation updates; cover multiple due events in a single batched advance, fences already at committed time, positive withdrawals, known-zero cycles, partial inputs, and stranded buffers. Preserve exact timestamps and exactly-once transfers.
4. **Scheduler liveness:** completion-budget exhaustion with no subsequent worker completions, retries without other activity, round timeout, worker shrink timer, shared V3 contention, cancellation, and shutdown. No lost wakeups or immediate retry spin.
5. **Rest qualification:** small inventories near the absolute cutoff, nonzero gross flow with zero average, slow monotonic evolution, phase/solid/filter transitions, dead-headed pump, and real equilibrium. Validate error budgets over long durations.
6. **Steady reuse:** generator-to-void, matched tank throughput, composition replacement at equal mass flow, pump heating, filter accumulation, finite depletion, changed controls, and shortened fence intervals. Compare conserved components/energy and trajectories with an unoptimized reference.
7. **Persistence:** new-format round trips, invalid sleep metadata, disabled rest setting, relevant setting changes, thousands of valid sleepers, and property-hold save/restart. A readable save must never imply an automatically valid optimization certificate. Do not add legacy-save compatibility tests.
8. **Property hold:** extend the existing reload test with sleeping and module-coupled islands. Preserve its current debt/commit contract unless decision 4 explicitly changes it.
9. **Measurement:** use both genuinely changing networks and stable-flow optimization candidates. Run timed rest/mixed profiles without requiring solve completions from sleeping islands. Keep raw baseline/candidate results, repeats, memory observations, and separate accounting for fast paths.

## Execution order recorded, not performed

1. Merge the previous approved candidate work first, as requested; then rebase the revised plan and verify source anchors on updated main.
2. Revise the plan to include the scheduling, presentation, steady-flow, persistence, and numerical requirements above. Resolve decision 4 using the concrete reload case.
3. Establish baselines and implement the revised phases only in a later execution task.

This task performed source inspection and wrote this review only. No Java/configuration files were changed, no branches were merged or committed, and no tests, benchmarks, or dev clients were run. Performance savings and numerical equivalence remain unmeasured.

# Fluid scheduling, rest and steady-flow plan (revision 2)

Revision 3, 2026-09-23. Revision 2 absorbed [FLUID_ISLAND_REST_PLAN_REVIEW.md](FLUID_ISLAND_REST_PLAN_REVIEW.md); revision 3 records your answers to the six revision-2 decisions (section 0b) and changes the presentation, hold and configuration sections accordingly. Source basis: merge candidate `claude/dead-headed-line` @ `1403eeb`; every line anchor must be re-verified on `main` after that candidate is merged (your decision 6). Status: **PLAN, approved for execution in the order of section 7 once the candidate is merged; nothing implemented, nothing merged, nothing run.**

## 0. Review response and recorded decisions

Your answers to the six revision-1 decisions, as recorded by the review, and the new requirements:

| Item | Your answer | Effect on this revision |
|---|---|---|
| 1 Clock advance without solving | Yes | Kept, with the fence passed into the clock call (section 3.2). |
| 2 Persist rest state | Yes, no old-save compatibility | Checkpoint format 3 with certificates and a validity signature; no migration, no legacy tests (section 3.5). |
| 3 No periodic recheck | Yes | Exact-zero rest is unbounded. Nonzero microscopic drift gets a physical validity horizon derived from an error budget, which is not a periodic recheck (section 4). |
| 4 Advance during property hold | Needs a real case | Decided in revision 3: no commits during the hold, requalification after resume (section 3.6). |
| 5 Steady flow | Blanket exclusion rejected | Steady-flow certificate with scaled replay of the last full interval and exact accounting (section 3.3). |
| 6 Merge order | Merge the previous candidate first | Nothing merged here. Branch from `main` after your merge. |
| New | No process calculation on the per-tick loop; block entities refreshed about every five seconds | Deadline scheduler with an O(1) per-tick check (section 3.1); coalesced presentation at 100 ticks (section 3.4). |

Disposition of the review findings. Every one was checked against the source before acceptance.

| Finding | Verified | Disposition |
|---|---|---|
| P1 all-island work every tick | Yes. `pump` runs three times per ordinary tick (`ProcessSolveCoordinator.drainCompletedCalculations` -> `pumpReady`, `IslandCoordinator.tick`, and the explicit call in `FluidWorldAuthority.tick`) plus once per completion batch, and each run scans every island twice. | Accepted. Deadline scheduler, lazy clocks, no entry for certified islands (3.1). |
| P1 stranded work without the tick pump | Yes. `drainCompletions` returns empty once the 64-per-tick budget is spent; the next ordinary tick is the only retry today. | Accepted. Continuation task on budget exhaustion; explicit deadlines for cadence, retry, round timeout and allocator shrink (3.1). |
| P1 sleep validity beyond the saved thermodynamic revision | Yes. The checkpoint stores `ApproximationAnchor.thermodynamicRevision`; velocity clamp, trace cutoff and solid settings live only in the full revision. | Accepted. Certificate signature covers the full revision, policy values and graph identity; `restDetection=false` discards saved certificates (3.5). |
| P1 rest cutoff does not justify permanent freezing | Yes. The absolute 1e-9 kg/s term was wrong for small inventories. | Accepted. The absolute floor is removed. Only an exactly zero interval map is unbounded; any nonzero drift gets a horizon from a relative budget (4). |
| P1 steady flow needs its own path | Scope change by you. | Accepted. Scaled replay of the last full interval's inventory deltas and boundary transfers, entered on a stationarity test, bounded by the same budget (3.3). Science code stays untouched because the replay is arithmetic on committed records. |
| P1 batched advance needs a full-window check | Yes. | Accepted. Materialisation target is the minimum of online tick, nearest fence, hold tick, certificate horizon and the earliest drive in the window; the fence is an argument of the clock call (3.2, 3.3). |
| P2 recovery check rebuilds the topology every tick | Yes, and worse than revision 1 said: `WorldTopologyLedger.snapshot()` constructs a new `Snapshot` every tick after the first, and its constructor copies the active map, validates every registration's composition length and builds a position set over all devices. | Accepted. Recovery delivery becomes event-driven with a bounded retry deadline; no snapshot construction on the idle path (3.1). |
| P2 module polling | Yes. `advance` visits every module and every pending transfer twice per tick and again per publication; `published` calls `rebind` every time. | Accepted. Module deadlines and a per-island drive index; rebind only on ownership change (3.1, 3.3). |
| P2 presentation cadence | Yes. Menus rebuild and send the complete state every 10 ticks including components, presets and names; block-entity views follow publication rate. | Accepted. Split static and live payloads; 100-tick coalesced, staggered refresh; discrete exceptions listed (3.4). |
| P2 save cost and retained memory | Yes. | Accepted. Format 3 with per-island cached payloads and the online clock outside them; solver handles dropped only after terminal completion (3.5). |
| P2 benchmark assumptions | Yes. Only the literal `stress100` profile uses elapsed-window termination, and all paced profiles are near-stationary through-flow fixtures that will certify as steady. | Accepted. A non-certifiable transient profile becomes the performance reference; rest, steady, mixed, viewer and module profiles get elapsed-window termination and separate accounting (6). |

One correction from the review is adopted: `IslandCoordinator.Island.model` is final and `resumeQualifiedProperties` restores the identical model object. Revision 1's claim that a resume could bring a different equilibrium was wrong.

### 0b. Revision-2 decisions, answered

| Decision | Your answer | Where applied |
|---|---|---|
| 1 Property hold | Require requalification after resume. Committed time frozen during the hold, elapsed online time preserved. A one-off verification after this exceptional event is consistent with no periodic recheck. | 3.6, tests 7 and 8 |
| 2 Shared clock | Yes. Preserve event timestamps, saved debt and the no-offline-progress behaviour. | 3.1 |
| 3 Tolerances and replay duration | Accepted for now; every value configurable. | 4 |
| 4 Immediate presentation updates | No. The engine decides when to update; player inputs and network changes are queued. This is the project's gold standard. | R2, 3.4, `AGENTS.md` |
| 5 Format 3, discard compatibility | Approved. Compatibility is useless at this stage; breaking changes are tested on a new world. Standing rule in `AGENTS.md` unless you state otherwise. | 3.5, `AGENTS.md` |
| 6 Scheduler first | Yes, after merging the previous work. Verify scheduling first, then add the numerical optimisation. | 7 |

## 1. What the runtime does today, per tick, with nothing happening

| Work | Where | Cost class |
|---|---|---|
| Readiness pump, three times | `ProcessSolveCoordinator.drainCompletedCalculations`, `IslandCoordinator.tick`, `FluidWorldAuthority.tick` | O(islands) per run: eligibility scan plus round-robin scan |
| Clock accrual | `IslandCoordinator.tick` | O(islands) |
| Module advance, twice | `FluidWorldAuthority.tick` -> `CausalModuleCoordinator.advance` | O(modules + pending transfers) |
| Topology snapshot rebuild | `FluidWorldAuthority.deliverRecoveries` -> `WorldTopologyLedger.snapshot()` | O(devices) allocation and validation |
| Pending-event scan while any event is queued | `FluidWorldAuthority.applyPending` -> `nextReadyEvent` | O(islands) snapshot constructions per queued event |
| Menu state | `FluidDeviceMenu.broadcastChanges` every 10 ticks | full view, controls, components, presets, names per open menu |
| Saved-data dirty mark | `FluidWorldAuthority.tick` | forces full re-encode at every autosave |
| Per island per cadence | full interval solve, publication, view fan-out | the actual process work, regardless of state |

Per-island retained memory: the `RetainedSolver` handle (up to 4 Newton workspaces and 4 structures with LU factors, up to 8 endpoint-rate caches, previous flows, heads and modes), the last result, the anchor when degraded, and the initial compile graph kept in `FluidWorldAuthority.compiled`.

## 2. Requirements, restated precisely

- **R1 No per-tick process work.** The server tick hook may do an O(1) "is anything due" check, drain the worker mailbox, and flush presentation buckets. Every island, module, event and recovery visit must be caused by a deadline, a dependency change, or a worker completion. Simulated time stays online ticks at nominal 20 TPS; no wall-clock or offline advancement.
- **R2 Presentation.** The engine owns the update schedule. Loaded devices and open menus receive coalesced updates on the engine's presentation buckets, about every 100 online ticks, staggered. There are no immediate pushes: a menu that opens shows the last delivered view and waits for the next bucket; an edit is queued as a ledger event and its acknowledgement or refusal arrives with the bucket that follows; status changes arrive the same way. Identity binding on chunk load carries no view data and is not an update. Presentation cadence never drives scientific progress. This is the project's standard for every player-facing update from now on.
- **R3 Rest.** An island whose interval map is exactly the identity costs no worker time, no server-thread time beyond deadline handling, and releases its solver caches. Its committed time advances by materialisation, never by solving.
- **R4 Steady flow.** An island whose interval map is stationary within a microscopic tolerance is advanced by scaled replay of its last full interval: inventories change by the recorded per-interval deltas, boundary ledgers by the recorded transfers, pump work by the recorded work, all scaled by elapsed duration. Accounting is exact by construction. Replay is bounded by a validity horizon derived from a relative error budget and by the next known event.
- **R5 Persistence.** New format, no compatibility with older saves. Saved clocks, material, fences, pending events, certificates and their validity signatures round-trip and are validated on load.
- **R6 Determinism.** Rest, steady replay and wake depend only on committed results, fences, deadlines and simulated ticks. The fence contract keeps every invariant: no commit past a fence, no commit with an outstanding job, no commit past the online clock.
- **R7 Measurement.** Performance is measured on a fixture that cannot certify; savings are measured on fixtures that do; both against a baseline with identical observation overhead.

## 3. Design

### 3.1 Deadline scheduler

A single server-thread `IslandScheduler` owned by `IslandCoordinator` replaces the per-tick scans.

- **Shared epoch.** The world online tick (`WorldTopologyLedger.onlineTick`) is the epoch. `IslandClock` keeps its API, snapshot format and invariants, but stores `base` instead of counting: `onlineTick() = epoch.now() - base`. `accrueOnlineTicks` disappears from the tick path; tests that drive a clock directly get an injected epoch. The clock is your contract code; this is a representation change with identical observable behaviour and it needs your explicit go-ahead alongside decision 1.
- **Deadline heap.** `PriorityQueue<Deadline(tick, sequence, kind, id, generation)>` with lazy invalidation: an entry whose `generation` no longer matches the island's (revision bump, repartition, removal, stop) is dropped when popped. Kinds: `SLICE_DUE` (committed + cadence, or the shorter retry span), `RETRY` (`retryAtTick`), `ROUND_TIMEOUT` (round start + hard budget), `ALLOCATOR_SHRINK` (200 ticks after demand fell), `MODULE_HORIZON` (cycle end, input due tick), `CERTIFICATE_HORIZON`, `PRESENTATION_BUCKET`, `RECOVERY_RETRY`. `nextDue()` is O(1).
- **Per-tick hook.** `FluidWorldAuthority.tick` becomes: property check (already O(1) by catalog identity), epoch increment, `if (scheduler.nextDue() <= now) scheduler.run(now)`, `if (topology.hasPendingEvents()) applyPending()`, `if (recoveries pending) deliverRecoveries()` without constructing a snapshot, presentation flush for the current bucket, dirty mark. `scheduler.run` pops only due entries; due islands go to the existing `FairIslandQueue`, which now holds ready owners only, so fairness among ready owners is unchanged. Dispatch capacity, the 64-per-tick limit and the round barrier are unchanged.
- **Completions.** `CompletionWakeup` already coalesces worker signals into one mailbox task. When a drain hits the per-tick budget, the drain schedules one continuation `TickTask(1)`; it never re-enters in the same tick, so it cannot spin, and completed work cannot strand. Calculator and V3 routing and shutdown routing are untouched.
- **Rounds, retries, allocator.** Round closure by hard budget, held-owner retry, and `WorkerAllocation.Demand.observe` shrink are driven by their heap entries instead of being noticed by the next scan. The allocator keeps its minimum of one admitted worker; the target is zero fluid demand and zero active fluid workers, not zero configured workers.
- **Events.** `nextReadyEvent` stops iterating all island snapshots: affected islands come from `owners` of the touched ids plus an event-to-islands index maintained when fences are installed. Alignment for certified islands is answered by materialisation to the fence (3.2).
- **Recoveries.** Delivery is attempted when a recovery is queued, when its player logs in, when its chunk loads, and on a bounded `RECOVERY_RETRY` deadline; exactly-once delivery and the item path are unchanged.

WP1 lands this with no certificates yet. Acceptance: every existing runtime test and GameTest passes, and the P12 worker-equivalence and P31 cadence trajectories are bitwise unchanged, because due ticks, dispatch order among ready owners, and budgets are identical.

### 3.2 Materialised time

For an island holding a certificate, committed time is derived, not stored per tick:

```
target(now) = min(now, nearestFence, holdTick, certificate.horizonTick, earliestDrive(committed, now))
```

`IslandClock.rest(long toTick, long causalFenceTick)` performs the identity or replay commit: refuses an outstanding slice, a target below the committed tick, above the online tick, or above the fence, then sets `committedTick = toTick` and clears the retry. The guard lives in the clock and in the coordinator's `target` computation, and both are tested. Materialisation happens on demand only: at a fence or event, at a module horizon, when a view is built for a loaded device or open menu, at save, at the certificate horizon, and on wake. A certified island with no viewers, no events and no horizon within reach has no heap entry at all except its horizon.

Status while certified: `RESTING: no flow since <t> s` or `STEADY: replaying <q> kg/s since <t> s, next check at <t> s`. `IslandCoordinator.Snapshot` reports the materialised committed tick.

### 3.3 Certificates

One record covers both cases:

```
Certificate(kind = REST | STEADY, baseTick, baseGraph,
            deltaPerSecond[node][component], energyDeltaPerSecond[node], solidDeltaPerSecond[node],
            boundaryRates, pipeTransferRates, pumpWorkRate, flows[], modes[],
            horizonTick, signature)
```

**Entry evidence** (checked on the server thread in `closeRound` from the two most recent accepted FULL intervals of equal duration `D`, no degraded episode, no scheduled transfers):

1. Stationarity of the interval map. For every finite node and component, `|delta2 - delta1| <= eps_s * inventory` (per node, per component; energy and solid mass alike); for every pipe, `|q2 - q1| <= eps_s * max(|q2|, q_ref)` with `q_ref` the largest flow in the island; modes equal; blocked masks equal; filter cakes and energies equal; fixed-node states equal; no transition entry in `rejectionReasons`.
2. No drive in the coming cadence (3.1 module index; 3.3 wake index).
3. For `STEADY`: no solids in transport (`pipeTransfers` solids empty) and no filter on the island in the first implementation; `REST` has no such restriction.
4. Kind: `REST` when every `delta2` and every `q2` is exactly zero; otherwise `STEADY`.

**Materialisation** from `baseTick` to `t`, elapsed `s = (t - baseTick) / 20`: inventory `= base + s * deltaPerSecond` per node, boundary ledgers `+= s * boundaryRates`, pump work `+= s * pumpWorkRate`, pipe history `= s * pipeTransferRates`. States stay the certificate's states; the next full solve refreshes state from inventory through `InventoryEquilibrium.refresh` exactly as every interval does today. Conservation is exact because the recorded interval conserved and scaling is linear. Solid deltas use `SolidInventory.plus` and `scale`.

**Horizon** (section 4 for the numbers): `REST` has none. `STEADY` has `horizonTick = baseTick + 20 * D * min(K_max, floor(delta_budget / d))` where `d` is the largest relative per-interval change over nodes and components, further limited so that no component can be extrapolated below zero and no fixed-node ledger changes sign. At the horizon one ordinary slice is dispatched from the materialised state. If the solved interval's deltas agree with the certificate within `eps_s` the certificate is renewed from the solved state; otherwise the island stays awake and needs two fresh stationary intervals before it can certify again. This is the explicit transition the review asked for.

**Invalidation and wake.** Topology and configuration events replace the island (new object, no certificate). Module drives (3.1 index) wake at the drive tick. A property hold caps materialisation at the hold tick and discards the certificate at resume (3.6). A signature mismatch on load discards the certificate. `retained` is set to null only when the certificate is created, which is after the terminal completion that produced the qualifying interval; an old worker can never own it.

**Drive index.** `CausalModuleCoordinator` keeps, per island, a sorted set of drive ticks: due ticks of pending inputs with remaining mass for that island's buffers, and start ticks of announced cycles with a positive withdrawal target on its feed buffers. `earliestDrive(island, from, to)` is a range query with no side effects; it replaces the revision-1 idea of calling `withdrawals` as a query, which validates horizons and must not be used that way. Known-zero cycles register no drive but keep their horizons as `MODULE_HORIZON` deadlines, which certified feed islands answer by materialisation.

### 3.4 Presentation

- Menu protocol `fluid-4`: a static payload (components, presets, material names, kind) and a live payload (view, controls, message). Both are delivered only on the owning island's presentation bucket, about every 100 ticks; the static payload is resent only when the registration revision changed. `broadcastChanges` no longer sends on a 10-tick timer, and `sendState` is no longer called from the edit or recovery packet handlers.
- Queued inputs: an edit or recovery packet validates its revision and bounds as today, queues the ledger event, and returns without a reply. The reply, `Queued for simulation event at tick N`, `Applied`, or `Not applied: <reason>`, is composed by the engine and delivered with the next bucket. Until then the menu shows `Waiting for the engine`.
- Loaded block entities: `published` marks the island's loaded member devices dirty instead of queueing a refresh; the scheduler's presentation bucket for tick `t` flushes devices whose `identity % 100 == t % 100`, materialising certified islands on demand. Views are built only for devices that are loaded or have an open menu. `onLoad`, `loadedChunk` and `bindIdentity` only set the dirty flag; they push nothing.
- Verified in the dev client through the MCP bridge: RESTING and STEADY status lines, the five-second refresh, and a queued edit acknowledged on the following bucket rather than immediately.

### 3.5 Persistence, format 3

- Envelope: `FluidFormat = 3`, world epoch, ledger, modules, and a list of island compounds. Each island compound holds `id`, `revision`, `base`, materialised `committedTick`, the certificate if any, and a payload byte array containing graph, anchor, history, allowance, fences and status. Payloads are cached per island keyed on `(revision, payloadGeneration)`, where the generation changes on every accepted solve and every certificate creation but not on materialisation; a certified island's payload is therefore reused across saves, and load materialises from the saved base to the saved committed tick.
- Certificate signature: `ApproximationAnchor.revision(model)`, the certificate policy values, and a digest of graph identity (node ids, kinds, volumes, elevations, fixed-node inventories and states, pipe identities including blocked masks and filter settings). On load a mismatch discards the certificate and keeps inventory; `restDetection=false` discards every saved certificate.
- Load validation of the new fields: awake sentinel or a base tick no later than the committed tick, horizon no earlier than the committed tick, deltas finite, fences no earlier than the committed tick.
- No migration from format 2 and no legacy-save tests, per your decision. Development worlds with format 2 saves are reset. Standing rule, now in `AGENTS.md`: breaking changes to saves, checkpoints, wire protocols or material data are tested on a fresh world; no compatibility work at this stage unless you ask for it.
- Dirty marking stays per tick so online time is never lost; the save itself becomes cheap because unchanged payloads are copied, not re-encoded.

### 3.6 Property hold (decision 4, decided)

The existing test `FluidPropertyReloadGameTests.betweenTickRoutingHoldsChangedScienceAndRestoringItResumesConservedState` fixes the contract: during a hold, committed time does not move, online time does, inventory is unchanged, and resume continues with the original model. Your decision keeps all of that and adds requalification: a hold installs `holdTick` and materialisation is capped there; online time keeps accruing so debt is preserved; at resume every certificate is discarded, the island wakes, solves its debt from the materialised state, and must qualify again over `restConfirmIntervals` before it can certify. This one-off verification after an exceptional event is consistent with never rechecking periodically. The test is extended with a certified island and a module-coupled pair.

### 3.7 Worker pool

Unchanged. Idle workers park on the service queue; the allocator shrinks to one admitted worker after 200 ticks of lower demand. With every island certified, fluid demand is zero and active fluid workers are zero.

## 4. Numerical contract

Accepted for now and every value configurable (your decision 3). All keys sit in the `[fluid]` block and are captured at server start like the existing fluid options.

| Symbol | Config key | Default | Range | Meaning |
|---|---|---|---|---|
| on/off | `restDetection` | `true` | bool | Off disables certificates entirely and discards saved ones. |
| `eps_s` | `certificateStationaryTolerance` | 1e-9 | 0 to 1e-6 | Per-interval relative change of any conserved quantity, state variable or flow that still counts as stationary ("microscopic"). 0 admits only exact-zero maps. |
| `delta_budget` | `certificateInventoryBudget` | 1e-6 | 1e-12 to 1e-3 | Maximum relative inventory change any finite node may accumulate by replay within one certificate window. |
| `K_max` | `certificateMaximumIntervals` | 17280 | 1 to 1,000,000 | Maximum intervals per window: one game day at the 5 s cadence. |
| confirm | `restConfirmIntervals` | 2 | 1 to 10 | Consecutive stationary intervals before certification, and again after a property hold. |
| recheck | `restRecheckSeconds` | 0 | 0 to 86400 | Periodic recheck of exact-zero rest; 0 means never, per your decision 3. |

Bounds that follow:

- An exact-zero interval map is the identity of the model: there is no ambient heat exchange, reaction, leak or velocity-independent solid process in the equations, so nothing evolves without flow. It is replayed without bound. A metastable configuration at exact rest stays at rest in the solver too, because Newton started at the rest point returns it.
- A nonzero microscopic drift `d` is replayed for at most `min(K_max, delta_budget / d)` intervals, so the extrapolated inventory error within a window is at most `delta_budget` relative per node. Linear extrapolation of a smoothly decaying flow has a second-order error `K^2 d^2 <= delta_budget * d`, negligible against `delta_budget`. Windows do not compound beyond a linear sum because each revalidation solves from the materialised inventory; over a game year of daily windows the sum is below `4e-4` relative, under the integrator's own per-interval tolerance of `1e-3`.
- Small inventories are handled by the relative form: a 1 g inventory with a 1e-9 kg/s drift has `d = 5e-6` per 5 s interval, fails `eps_s`, and never certifies.
- Depletion, overflow, phase, mode, filter and control changes cannot occur inside a window because the certificate requires stationarity of every one of them and the horizon stops before any extrapolated component reaches zero. A change caused from outside arrives as an event, a drive or a hold, all of which stop materialisation at their tick.

What this does not claim: that replay reproduces the solver bit for bit for `STEADY` (it reproduces it to within `delta_budget`), or that any performance figure is known before WP0.

## 5. Tests

The review's nine verification items map to these suites.

1. **Idle scaling** (new `IslandSchedulerTest`, real executor variant in `WorkerTrajectoryEquivalenceTest` style): 1, 100 and 1,000 certified islands with no viewers and no events: counters for island visits, module scans, topology snapshot constructions, view builds and nonlinear solves stay flat over 10,000 ticks; the per-tick hook cost does not grow with island count.
2. **Presentation** (`FluidPacketCodecTest`, new `PresentationBucketTest`, GameTest): loaded and unloaded devices, zero, one and many open menus, steady and transient islands; routine packets counted at about one per 100 ticks per consumer; scientific progress identical with and without viewers.
3. **Exact causality** (`IslandCoordinatorTest`, `CausalModuleCoordinatorTest`): an event installed between presentation updates; several due events inside one materialisation window; a fence already at the committed tick; positive withdrawals; known-zero cycles; partial inputs; stranded buffers; exact timestamps and exactly-once transfers.
4. **Scheduler liveness** (`ProcessSolveServicesRequestTest`, `FluidCompletionBudgetGameTests`, `IslandCoordinatorTest`): budget exhaustion followed by no further completions; held retry with no other activity; round timeout; allocator shrink; shared V3 contention; cancellation; shutdown; no lost wakeup and no same-tick re-entry.
5. **Rest and steady qualification** (`IslandCoordinatorTest`, `RetainedSolverTest`): small inventory near the relative cutoff; nonzero gross flow with zero average; slow monotonic evolution fails stationarity; solid, phase and filter transitions reset qualification; dead-headed pump certifies `REST`; a settled closed pair certifies `STEADY` with a long horizon; horizon revalidation renews or drops.
6. **Steady reuse against an unoptimised reference** (`CadenceTrajectoryQualificationTest` extension): generator-to-void, matched tank throughput, composition replacement at equal mass flow (must not certify), pump heating (must not certify), finite depletion, changed controls, fence-shortened windows; conserved components and energy exact, trajectories within `delta_budget`. The existing P31 rows run with certificates disabled and stay unchanged.
7. **Persistence** (`FluidCheckpointCodecTest`, `FluidSaveCompatibilityTest` replaced by format-3 validation cases): round trips, invalid certificate fields refused, `restDetection=false` discards certificates, signature mismatch discards certificates, thousands of certified islands load without a solve burst, hold save and restart. No legacy-save cases.
8. **Property hold** (`FluidPropertyReloadGameTests` extension): a certified island and a module-coupled pair through hold and resume: committed time frozen, debt preserved, certificate discarded at resume, requalification over `restConfirmIntervals` before the island certifies again.
9. **Measurement**: section 6.

## 6. Benchmarks

| Profile | Fixture | Termination | Role |
|---|---|---|---|
| `transient100` (new) | 100 ladders whose generator fills 1000 m3 reservoirs through 100 m of pipe; fill time constant of order 4500 s, so per-interval change stays above `eps_s` for the whole run | elapsed window | Performance reference that cannot certify. Ordinary gates apply. |
| `stress100` (existing) | 100 through-flow ladders | elapsed window | Now measures the steady path: solves fall to revalidations after certification; trajectories and balances must match the certificate-disabled run within `delta_budget`. "Unchanged cost" is no longer the gate. |
| `rest100` (new) | 100 closed ladders | elapsed window | Every island certifies `REST`; worker CPU, demand and active workers reach zero; after-GC heap shows the released solver memory. |
| `mixed100` (new) | 50 transient, 25 steady, 25 closed | elapsed window | Demand tracks the transient half; transient latency unchanged. |
| `viewers` (new) | `mixed100` with fixture chunks loaded, 16 open menus and scripted edits driven by the harness | elapsed window | Presentation packets and view builds near one per 100 ticks per consumer; edits acknowledged on the following bucket, never immediately; scientific progress unaffected. |
| `module` (existing) | module-coupled islands | interval count | Drive index correctness under certification. |

Report additions: full solves, replayed intervals, identity-advanced seconds, certified island count per second, per-tick counters for island visits, module scans, snapshot constructions, view builds and packets, retained debt, and save time. Baseline is the same artifact with `restDetection=false`; both sides carry the same observation overhead; three repeats per profile before any number is quoted.

## 7. Work packages

Each is one opus agent in its own worktree branched from `main` after your merge, one Gradle invocation at a time on the machine, never a suite while a dev client is up, no bare `git stash`, commit messages ending with the session attribution line. I review each result from artifacts before the next starts.

| WP | Content | Gate |
|---|---|---|
| 0 | Counters and profiles: `SolverDiagnostics`-style server-thread counters for the section 1 items; `transient100`, `rest100`, `mixed100`, `viewers` fixtures; baseline runs on the unchanged code. | Reports under `build/reports/fluid/M9/<run-id>/`; numbers in the review doc. |
| 1 | Deadline scheduler, lazy clocks, completion continuation, event index, recovery gating, module deadlines; behaviour-preserving. | All existing suites green; P12 and P31 trajectories bitwise unchanged; WP0 counters show no per-tick island or module visits. |
| 2 | Certificates: entry, `REST` and `STEADY`, materialisation, horizon and revalidation, drive index, retained release, status strings; tests 3, 5, 6. | `fluidRuntimeTest` green; `fluidScienceTest` 161; `fluidSolverRegression` exact zero. |
| 3 | Presentation: `fluid-4` protocol, buckets, dirty devices; tests 2; MCP dev-client screenshots. | GameTests green; screenshots in the review doc. |
| 4 | Persistence format 3 with cached payloads, signatures, validation; tests 7 and 8. | GameTests green; hold and restart cases pass. |
| 5 | Benchmarks per section 6, `FLUID_NETWORK_STRESS_TEST.md` and `FLUID_NETWORK_ACCEPTANCE.md` sections, `documentation/FLUID_ISLAND_REST_REVIEW.md`. | Baseline versus candidate with repeats; `transient100` within noise; `rest100` at zero fluid demand. |

Revision 1's "about 150 runtime lines" estimate is withdrawn. WP1 and WP2 are each a few hundred lines of runtime code plus tests; WP3 and WP4 change a wire protocol and a save format.

Order confirmed by your decision 6: WP0 and WP1 first, and WP1's gate (counters flat, P12 and P31 bitwise) is verified before WP2 starts. The standing rules from decisions 4 and 5 are on branch `claude/agents-md-standing-rules`, one commit on top of the candidate, so they reach `main` with the same fast-forward sequence.

## 8. Precondition

All six revision-2 decisions are answered (section 0b). The only step left before WP0 starts is the merge you reserved for yourself, run from the main checkout:

```bash
git -C D:/Minecraft/Modding/1.21/CreateChemE merge --ff-only claude/dead-headed-line
```

```bash
git -C D:/Minecraft/Modding/1.21/CreateChemE merge --ff-only claude/agents-md-standing-rules
```

After that, WP0 and WP1 branch from `main` and every line anchor in this document is re-verified there.

## 9. Follow-ups noticed, not in scope

- `FluidWorldAuthority.compiled` keeps the initial compile graph of every island next to the live graph.
- `STEADY` with solids in transport or filters is excluded from the first implementation; cake accumulation needs a predicted transition horizon.
- Steady replay for islands with scheduled transfers is excluded; the module planner remains the path for buffered intervals.

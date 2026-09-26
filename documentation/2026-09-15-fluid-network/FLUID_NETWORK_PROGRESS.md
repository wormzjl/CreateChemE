# Fluid network implementation progress

Branch: `codex/simulation-fluid-network`  
Starting revision: `e6600974e6066bc19083815f7c6776c5a56c687b`  
Work order: `design/SIMULATION_ENGINE_AND_FLUID_NETWORK_WORK_ORDER.md` (local reference copy)

## Latest verification — 2026-09-16

### RAM profiling and allocation reduction (Sol implementation and analysis)

Committed the consolidated baseline as `a5dedf6`, then profiled the 100-network fixture with identical 4 GiB heaps. Reusing immutable temperature coefficients and local defensive snapshots reduces sampled allocation per accepted simulated second by **55.05%** and total GC pause by **62.24%**. Peak sampled process resident memory falls only **0.67%** at fixed heap. A separate successful 3 GiB probe lowers peak resident memory from 4,632.66 to 3,576.94 MiB, with lower useful throughput. Host load differs across these single runs; no general speedup or loaded-world capacity guarantee is claimed.

**Verified:** 790 unit tests, 14 GameTests, build and 13 Python checks pass. All 60 P31 cadence rows and the canonical report remain exactly unchanged. Current production JAR SHA-256: `0e61828aebbaaa1251c804014856e05fb2719c0ae93a151a4ae7c3b590d895be`. Each RAM run advances all 100 networks, preserves conservation/unloaded-chunk operation, has zero approximation/runtime errors/JFR data loss, and retains nonzero holds. Ordinary M9 qualification, startup recovery, aggregate-load cadence and near-depletion gaps remain open.

The consolidated benchmark CLI now includes `memory`; optional JVM/MXBean instrumentation and heap controls are confined to the benchmark. Normal game heap defaults and numerical gates are unchanged. Full protocol, identities, metrics and limitations: [CPU and RAM stress results](FLUID_NETWORK_STRESS_TEST.md). Java regression log: `build/fluid-memory-regression.log`.

### Class and tool consolidation (Sol review)

- Shared worker sizing, demand hysteresis and CPU sampling now live in `runtime/WorkerAllocation.java`, replacing three small utility files. Configuration defaults/bounds use that same policy; the twelve-worker automatic ceiling and 200-tick shrink delay are unchanged. Existing worker-policy test methods are grouped in `WorkerAllocationTest`.
- `FluidThermodynamics` owns the conserved component names and molecular-weight mapping used by transport, TR-BDF2, module transfers, world views and persistence. Consumers no longer reconstruct the same water-last basis independently. Numerical equations, component order and saved energy-reference rules are unchanged.
- Shared fixtures, independent mass/finite-ledger calculations, the deterministic clock and immutable reservoir fixture live in one `FluidTestSupport` file. `ConservationAssertions` remains the independent BAL assertion implementation. Named physics cases, error gates and test method counts are retained.
- Three benchmark scripts are replaced by `examples/Fluid-Benchmarks.py` with `audit`, `summarize` and `stress` subcommands. It shares runtime-error scanning and percentile calculations; obsolete wrappers were removed. Twelve Python checks pass and the preserved stress summary compares exactly before candidate-artifact changes.

**Verified:** 789 unit tests (176 classes), zero failures/errors/skips; all 14 required GameTests; successful build (`build/fluid-consolidation-regression.log`, 1m17s). All 60 P31 cadence rows and the complete canonical JSON report are exactly unchanged from before consolidation. The JAR contains the new `WorkerAllocation`/`Demand`/`CpuTime` types and none of the obsolete top-level worker utilities. Twelve Python checks pass; preserved stress-summary output remains exactly equivalent.

New artifact SHA-256: `dd99bfd044c0e268ff6c69d5985c7fa20f6a8eb6aa01325f81889abe97946eeb`. The measurements below keep their original artifact hashes and are not automatically performance qualification of the consolidated build. No new timed benchmarks were run as part of this consolidation.

### Luna testing and parallel correctness execution

The full Java regression now passes **789 tests, zero failures/errors/skips, in 52 seconds with two concurrent test JVMs** (`build/fluid-luna-parallel-unit.log`). The log records overlapping executors 32/33, and summed JUnit class durations are 86.606 s; this demonstrates actual overlapping test execution, not a controlled engine-performance speedup. Optional `-PtestForks=2` controls correctness-test forks (1–8, default 1), independently of production solver workers. The production JAR hash is unchanged (`8dae23fe...`), preserving the 14/14 GameTest and current benchmark evidence. Nine standalone Python evidence-gate regressions also pass; raw reports/logs and configuration/fixture identities are now checked before grouping repetitions.

Luna added and analyzed physical shared-source/pump-suction tests while another Luna subagent owned paced benchmark execution. Shared-source branch orders match exactly through 0.11 s, then fail atomically with 55.9% stock remaining. The inadequate pump request also fails atomically; an adequate control transfers with positive work and conservation. These add safety evidence but do not close the near-empty/insufficient-suction physical qualification gaps. See `FLUID_LUNA_PHYSICAL_TEST_REVIEW.md`.

The current fixed-two-worker module group has three passing fresh-process replicates: `reload-budget-module-2w-r01`, `luna-module-2w-r02` and `luna-module-2w-r03`. Each advances 200 complete five-second FULL intervals per island after warmup. Measured holds/approximation/runtime errors are zero; warmup holds are retained as 0/0/2. Pooled queue-inclusive median/p95/max: 560.20/1,192.31/1,522.27 ms; engine median/p95/max: 0.0489/0.2053/1.8994 ms/tick. Configuration/fixture hashes match. This closes only that repetition group; other groups and the soak remain. Execution order and concrete pass criteria are recorded in `FLUID_TEST_EXECUTION_ORDER.md`; Luna's independent analysis is `FLUID_LUNA_TEST_REVIEW.md`.

The current-artifact 100-network rerun preserves conservation, unloaded-chunk operation, bounded twelve-worker ownership and a clean runtime log, but exposes a startup/recovery weakness: 291 warmup / 273 measured holds, including 28 in the final 1,200-tick window. That window's queue-inclusive p95 is 113.975 s despite final maximum debt recovering to 4.5 s. The last 400 ticks are clean, which does not qualify sustained ordinary performance. Exact profiling and host-comparability limits are recorded in the stress document and Luna review; no speculative production tuning was applied.

### Property reload and independent Sol testing

Current artifact `8dae23fe56e0139ec0b4d00c0931fddea208bd9708c896553695057578338d3a` passes **787 unit tests and all 14 dedicated GameTests**. The integrated build/GameTests pass in `build/fluid-reload-budget-gametest-r03.log`; after the unit-only cadence test addition, the full regression passes in `build/fluid-sol-final-unit.log` with the production JAR unchanged. An earlier failed server run is retained: the new reload fixture incorrectly treated a positive placement timestamp as proof of a first FULL result, and now waits for the result/anchor explicitly.

Production property reload checks run before completion routing, including between-tick wakeups. Scientific changes hold the pinned model, discard stale proposals and invalidate fallback eligibility while retaining material, energy, debt, fences and allowance. Metadata-only replacements continue; restoration resumes a full solve. Incompatible saved-model adoption still needs explicit migration/qualification.

The requested **Sol subagent** implemented and ran real-executor worker-count trajectory comparisons: one, two and demand-based twelve workers produce identical gas/water/full-basis wet-crude state, phase, flow and substep histories at matching timestamps, with conservation.

Sol also caught a completion-budget defect in the first contention attempt: after 64 callbacks in one tick, the router requested a zero-sized drain and caused 20 server-task exceptions despite a nominal green numerical report. The production guard now retains queued completions until the next tick. Sol's new actual-server test confirms completion 65 remains queued and all 70 callbacks arrive within the per-tick budget. Benchmark log auditing now rejects these hidden runtime failures; the failed run remains preserved.

The corrected current-artifact contention rerun `reload-budget-contention-2w-r02` passes with zero runtime errors, conservation, two 45-second CPU kernels, final debt 45 ticks, and p95 engine work 0.1199 ms/tick.

Full module replicate `reload-budget-module-2w-r01` also passes: 120 s warmup, 200 contiguous five-second FULL intervals per island (1,000 simulated seconds each), zero warmup/measured holds or approximation, and zero runtime-audit errors. Queue-inclusive completion p95/max: 1,236.44/1,522.27 ms; engine p95/max: 0.2059/1.761 ms/tick. Component/energy balances pass.

P31's fixed 1/5/20-second and controlled adaptive cadence trajectories now pass refined temporal references for nitrogen, water and multiphase wet crude, including all three phase fractions and closed component/energy balances. Adaptive cadence grows from 100 to 125 ticks and recovers to 112; CPU samples are injected, so aggregate CPU-load classification remains open. Maximum pressure error is 0.0119%; maximum temperature error 0.000177 K. Sol independently interprets the evidence in `FLUID_SOL_TEST_REVIEW.md`. This remains one module replicate; no remaining M9 pass is inferred from prior artifacts.

### 100-network stress request

**782 unit tests and all 12 GameTests pass; build succeeds.** Production scheduler/worker changes implement demand-based allocation up to the user's **12-worker ceiling**, overlapping independent publication groups with bounded ownership, and shorter complete retries after wall deadlines. Scientific error-based substep selection retains all acceptance gates. Immutable transport revisions are cached once, and ticks with no topology edit avoid full-registry validation.

The deterministic stress fixture has **100 isolated networks, 10–30 reservoirs each (1,993 total), 9,110 physical pipes and 22 components**, mixing series and parallel connections. In the matched 30 s warmup / 60 s measurement comparison, useful throughput rose **2.964 -> 28.027 five-second-equivalent intervals/s**; all networks advanced, component/energy balances passed, final maximum debt was 4.35 s, and server engine p95 was 1.604 ms/tick. Startup deadline holds are retained. Details, raw-run IDs and limitations: [stress design and results](FLUID_NETWORK_STRESS_TEST.md).

Current artifact: `e4675976a69d6d40a2e24e2da70d501d275c7ccbb6e357b52e8321e71c635f67`. The longer final-artifact run also passes integrity/recovery checks: 60 s warmup + 120 s measurement, 22.321 useful equivalent intervals/s, all 100 networks advanced, maximum debt 4.65 s, and at most 12 allocated/outstanding workers/jobs. It retains 248 warmup holds and 13 measured holds. The last observed 1,200 ticks contain zero holds/approximation, p95 completion 132.48 ms and p95 server engine cost 0.8632 ms/tick. Whole-window engine p95 is 1.0647 ms/tick. Prior M9 ordinary/module/contention reports below are historical and cannot qualify the changed engine.

### Prior checkpoints

**776 unit tests pass**, zero failures/errors/skips, with a successful build (`build/fluid-reference-full.log`). The production classes remain unchanged from the 12-GameTest checkpoint. The current-artifact two-worker contention run `clamp-qualification-contention-2w-r01` **passed**: both shared workers occupied by 45-second CPU kernels, debt 943 -> 41 ticks after recovery, at most two outstanding jobs, no holds/approximation, and component/energy balance passing. P95 engine work was 0.1338 ms/tick (maximum 16.3611 ms/tick, retained in the raw report).

Current measured artifact: `11872e234daa7ed2931999a20dc559332f432aba201a6dbf63a4ffc14f9f4647`. The new [property compatibility record](FLUID_PROPERTY_COMPATIBILITY.md) separates approved approximations, sampled coverage, retained reference errors and remaining qualification. All 123 new nitrogen-containing property samples and four independent hydraulic reference tests pass. The latter cover 112 fixed-boundary cases, 25 geometry cases, 12 transition-boundary cases and two finite-vessel directions (`build/fluid-reference-qualification.log`).

**Full module replicate passed:** `clamp-qualification-module-2w-r01`, 200 measured intervals per island after 120 s warmup. P95 queue-inclusive completion **1,719.26 ms**, engine **0.2786 ms/tick**; zero measured holds or approximation, passing component/energy balances. Two warmup holds are retained in the report. The production artifact was unchanged while the additional test sources were prepared. M9 still requires the other repetitions, worker counts, contention and soak evidence.

**Follow-up checkpoint:** 771 unit tests and all 12 GameTests pass. Physical event dependencies now allow unrelated edits to proceed past a blocked island without changing event timestamps or permitting same-position replacement to overtake removal. Canonical-empty isolated vessels advance without EOS evaluation or invented stock; connected unsupported vacuum filling rejects explicitly. The gas/liquid hydraulic matrix adds rest, unequal parallel branches, hydrostatic/reversed elevation and six seeded graph permutations.

After the initial post-clamp module pilot failed, cap thresholds were cached per immutable donor/run and linear-solver scratch arrays were confined/reused by their owning worker. No equation, conservation or backward-error tolerance was relaxed. `clamp-module-pilot-02` passes its measured phase: p95 readiness-to-publication 1,588.48 ms and engine work 0.2424 ms/tick, zero measured holds/approximation. It records 12 warmup holds. The ensuing full replicate result is recorded above.

**Current change verified:** user-approved velocity saturation replaces sonic-flow rejection. The default is 100 m/s, limited further by the existing fluid acoustic bound. It is enforced in the coupled pressure/flow equations, with normal component/enthalpy balances and unchanged EOS/flash temperature/phase behavior. Final scientific regression: **767 unit tests**, zero failures/errors/skips, and **11 required GameTests**; build succeeds (`build/fluid-clamp-trace-final-unit.log`, `build/fluid-clamp-trace-final-gametest.log`). The final cache-revision update also passed the runtime suite and build (`build/fluid-clamp-revision-check.log`). Near-liquid-full trace scaling was strengthened after MCP exposed a later reconstruction failure; its exact checkpoint now passes a refined-reference comparison and 1,000 simulated seconds of continuation without deleting nitrogen.

Final MCP confirmation: the original tank is **FULL**, approximately 150.66 kPa / 298.16 K / 996.05 kg, with lag reduced from over 900 s to **0.9 s**. A separate nitrogen generator at 2 MPa feeding a 50 mm pipe and a 101325 Pa void shows **FULL / VELOCITY_LIMITED**, **4.46 kg/s**. Evidence: `M8/reservoir-clamp-caught-up.png`, `M8/pipe-velocity-limited-nitrogen.png`, and `build/fluid-mcp-clamp-verified.log`. World saved and client closed.

Artifact SHA-256: `de67f7d8b62071d11027bf41644019dfca058908823c7c591424c9db0dfc5205`. The earlier ordinary/one-worker-contention results are pre-clamp references; the two-worker contention run was stopped at the user's request before changing the model. M9 qualification of this artifact remains open; no new pass may be inferred from old results.

The earlier pre-clamp build passed **754 unit tests** with zero failures/errors/skips and **11 dedicated GameTests** (`build/fluid-full-regression-lifecycle.log`, `build/fluid-m8-lifecycle.log`). The current 767-test result above supersedes these historical counts. M8 uses Minecraft MCP; no manual play-test approval is required before proceeding with M9.

MCP now verifies normal pipe removal and replacement, full/held readouts, and a corrected read-only probe: per-run net flow, selected forward/reverse gross flow, endpoint coordinates, interval duration/quality, and matching phase history. See `build/reports/fluid/M8/pipe-normal-removal.png`, `pipe-normal-replacement-full.png`, `probe-forward-endpoints.png`, and `probe-reverse-zero.png`. Dedicated tests additionally exercise Create/vanilla movement rejection and menu chunk unloading/replacement identity.

New physical restart tests preserve the same trajectory with partial module feed holdup and pending products. Stranded targets preserve their products/holdup across restart while other receivers can accept already-produced material. Removal of an unused zero-fraction outlet no longer stops the module. Module type/scientific revision is explicit and validated in persistence; older implicit fixed-split payloads remain readable.

The two-minute-warmup module pilot passed without held or approximate intervals: p95 readiness-to-publication **1,476.91 ms**, engine work **0.1607 ms/tick**. The first full paced replicate, `qualification-one-2w-r01`, also **passed**: 200 measured intervals, no holds/approximation, p95 queue-inclusive latency **169.87 ms** and engine work **0.1506 ms/tick**. All conservation gates pass. The revised harness records artifact, fixture, configuration, and JVM metadata. Remaining repeats and the soak are not passed; see the acceptance record for maxima and precise scope.

The consolidated [acceptance audit](FLUID_NETWORK_ACCEPTANCE.md) records P01–P52 evidence and specific open gates. **M8 and M9 are not declared passed.** The user chose velocity clamping to resolve the demonstrated sonic refusal. No repair-at-frozen-time control was added; current timestamp semantics are unchanged.

## M0 — Baseline and test infrastructure

- M0.1: PASSED — clean Java 21 baseline: 588 unit tests and build succeeded.
- M0.2: PASSED — conservation/identity assertions, immutable SI fixtures, controlled clock, named science/runtime tasks. Reserved benchmark tasks fail explicitly until M1/M2/M9 supply fixtures.
- M0.3: PASSED — sequential sparse-LU adapter, pivot/singular/scaling/ownership tests, all three EJML runtime jars and license bundled, isolated packaged-classloader solve passed.
- M0.4: PASSED — dedicated test mod discovered and passed one GameTest; deliberate failure propagated exit code 1. Test mod is excluded from production jars.

Final regression: **600 unit tests passed**, with zero failures/errors/skips; build succeeded. The 10 science and 2 runtime harness tests are included in that total. `build/reports/fluid/M0-checkpoints.json` and `M0-baseline.md` record evidence and environment. The deliberate failure and missing-fixture checks are expected negative tests, not unresolved regressions.

No later milestone is passed. The new worktree starts from committed main; unrelated material-quality edits in the source checkout are not included.

Generated evidence belongs under `build/reports/fluid/`. Do not mark a checkpoint passed without its actual report.

## Commands

Use Java 21, then:

```powershell
.\gradlew.bat test build fluidScienceTest fluidRuntimeTest
.\gradlew.bat runFluidGameTestServer
```

## Current automated gameplay evidence — 2026-09-16

Minecraft MCP has exercised normal placement and menus for the reservoir, pipe, pump, valve, generator, void, and probe in the isolated `Fluid M8 MCP` world. Verified readings include initial nitrogen, water filling to 200 kPa, a 0.005 m³/s pump target, 4.98 kg/s pipe history, crude presets, custom three-phase composition, and closed/fully-open/regulating valve states. The saved world reopened successfully. Detailed evidence and screenshots are in `build/reports/fluid/M8-gameplay.md` and `build/reports/fluid/M8/`.

The in-game short-pipe filling case exposed a timestep-refinement limit. The solver now permits 20 consecutive subdivisions while retaining the same accuracy tolerance, total-attempt cap, and wall deadline. Its exact regression agrees with separately refined step doubling; the original saved network recovered. GUI corrections remove misleading dead-leg lag and fictitious actuator storage, show source/sink flow and actuator pressure change, and keep old server messages from hiding new validation errors.

All **8 dedicated GameTests** pass, including real-server malformed/stale/distant/read-only/rate-limited control packets, actual placement/removal, unloaded endpoints, and atomic persistence. Science/runtime suites passed after these fixes. These focused suites overlap the full unit suite; do not add their counts together. A later capacity-reservation extension is under separate verification.

M8 remains in progress: some state-family/identity/contraption cases and normal MCP removal remain unverified. A sonic-domain refusal is correctly held, but the strict timestamp policy prevents a later repair edit from fixing an earlier unintegrable interval. The user has been asked whether to add an explicit logged repair-at-frozen-time action; no such exception has been implemented. M6's complete causal test-module integration and M9's paced repetitions/soak remain outstanding. There is no manual-testing handoff or permission gate before M9.

### Subsequent module integration

The fixed-split module now announces causal horizons, reserves product capacity before withdrawal, owns only committed multi-feed receipts, and emits conserved pending products at 5/15/30 s cadence. Known zero production advances the next possible delivery horizon without a circular wait; known deferred products no longer hold an unresolved-production fence. Module holdup, planned capacity, clocks, definitions, and reservoir bindings have optional versioned save extensions; older saves still load without inventing material.

Deterministic tests run the actual coupled hydraulic solver through multi-feed and two-module cyclic scenarios with conservation checked after every commit. **All 9 dedicated GameTests pass**, including a real shared-worker module run and SavedData round-trip (`build/fluid-module-gametest.log`). Persisted modules are connected to world authority. Removed buffer targets retain stranded pending/owned material and are not redirected to replacement identities; broader topology/restart qualification for this new integration remains necessary.

M9 preparation now includes a configurable fixed/adaptive cadence switch and opt-in main-thread timing that counts completion wakeups between ticks. Per-island diagnostics separate worker wall/CPU time, dispatch/publication latency, and queue debt. These diagnostics are not performance results; the paced harness and required repetitions/soak are still outstanding.

Negative harness check (expected nonzero exit):

```powershell
.\gradlew.bat runFluidGameTestServer -PfluidGameTestDeliberateFailure=true
```

The M0 run deliberately checked missing benchmark fixtures. Property and network screening fixtures now exist; the server benchmark remains unimplemented. Screening results below are not M9 performance qualification.

## Next

M1–M8 are IN PROGRESS. The persistent world authority, all seven gameplay objects, native menus, bounded server-validated packets, and unloaded-chunk simulation now exist. Seven dedicated GameTests passed, including actual placement/removal and simulation with intermediate and endpoint chunks unloaded. Minecraft MCP gameplay testing has started in the isolated `Fluid M8 MCP` world. The causal test-module coordinator, consolidated qualification reports, and remaining acceptance cases are incomplete. M9 has not passed or begun timing qualification. No post-M0 milestone has formally passed. Changes remain uncommitted in this worktree. Later updates supersede the historical development notes below.

## User decisions and M1 audit — 2026-09-15

- Preserve the full planned multiphase gameplay scope.
- Apply one configurable liquid compressibility to all liquids: initial `1e-9 Pa^-1`, including free water. This supersedes material-specific compression accuracy, not conservation or phase/solver gates.
- Retain material-dependent viscosity. The liquid log-mixing adapter uses independently exported DWSIM conditional-solute factors for dissolved light gases outside pure-liquid table ranges, only with a supported carrier. Pure-liquid validation remains strict.
- Independent NIST screening found unmodified translated-PR compressibility errors of 37.657%, 28.373%, and 10.494% for butane, pentane, and decane. These failures motivated the authorized model change; they are not reported as passing physical-compression qualification.
- New phase initialization tests exercise pure liquid water/steam, methane, liquid pentane, subsaturated water vapor, and TJL20/WTI/Cold Lake three-phase mixtures. Each verifies component and U/H/PV closure. They do not yet qualify transient piping or gameplay.
- The isolated M1 test reports are under `build/test-results/fluidScienceTest`; final checkpoint reports remain pending.

## Nitrogen initialization decision and subsequent work

The user retained fluid-only energy and explicitly rejected wall heat capacity, then requested an initial nitrogen fill. New reservoirs will receive nitrogen once at placement, using configurable prototype defaults of 298.15 K and 101325 Pa. Reloading, restarting, or depleting a reservoir must never regenerate that charge. Unsupported physical states remain explicit rejections.

A private fluid catalog extension now adds nitrogen without altering the active V3 package. NIST Shomate Cp fit maximum error is 0.022%; viscosity and cryogenic liquid calibration data retain their primary-source URLs and raw references. Nitrogen/hydrocarbon PR binary interactions are initially estimated zero. The standalone initialization and coupled U/V test for a 10 g water charge conserve nitrogen, water, and fluid energy without a wall-energy term. Gameplay placement/persistence remains unimplemented.

M2 development includes immutable scientific graphs, deterministic pipe-run compilation/debug mapping, dead-leg and pump-loop diagnostics, C1 Darcy/Poiseuille resistance, sparse colored finite-difference Newton, direct coupled reservoir phase/U/V equations, zero-holdup junctions, conservative bulk transfers including elevation energy, and adaptive internal substeps. Focused tests cover gas/liquid-full equalization, order reversal, branching, three-phase crude transfer, and a newly arriving gas component. One gas time-refinement screening case passes against 400 fixed reference steps; this is not full TIME or performance qualification.

Initial M3 actuator equations and active sets now cover pump suction-flow targets, maximum added head, natural-flow limiting, shutoff, pump energy, and pressure-valve closure/regulation. Focused pump/valve tests pass. Sources/sinks, phase appearance/disappearance, automatic regime recovery, bounded fallback, runtime integration, persistence, and all seven gameplay objects remain outstanding. This is **not yet an in-game test build**.

Evidence: `M1-wet-grid.json`, `M1-property-cost.json`, `M2-gas-time-screening.json`, and `M2-empty-water-feasibility.json` under `build/reports/fluid/`, plus named JUnit reports. The original evacuated-water audit intentionally records an unsupported state; it is not a passing filling test.

## TR-BDF2 decision and current qualification — 2026-09-16

The user approved TR-BDF2 while retaining all physics and accuracy gates. The earlier BE time-screening statement above is superseded: adding pipe-transfer error control exposed excessive substeps and a failing refinement case. Corrected BE screening took about 29.5 s for 100 reservoirs, 22 components and 5 simulated seconds.

The initial TR-BDF2 implementation passes the science/runtime suites. Its gas screening uses eight accepted substeps, with transferred-mass error 0.0133%, pressure error 0.0146% and temperature error 0.0375 K against 400 BE reference steps. Initial 100-reservoir screening takes 8.6–8.7 s with 56 accepted substeps: improved, but still above the 2 s performance target. This is unpaced screening, not M9 qualification. Further optimization and transient/device qualification are in progress.

M5 shared-worker fluid command routing, coordinator-only completion callbacks, island clocks, fairness and fallback allowance primitives now exist; M6 immutable parcel and revisioned partial-transfer primitives exist. These milestones are partial, not passed. Approximate solving, full world coordination, persistence, actual buffers/modules, gameplay blocks and GUIs remain outstanding. No in-game test build is ready yet.

### Subsequent TR-BDF2 verification

Full regression completed with 675 unit tests, 72 science tests and 15 runtime tests (the named suites overlap the unit total), plus both dedicated-server tests and build. Subsequent focused coverage reached 79 passing science tests, including tiny headspace, water and hydrocarbon phase disappearance, mixed-feed junctions, tank recycle, valve bypass, cancellation rollback and an explicit sonic-flow refusal. These counts are development evidence, not completed milestone gates.

Current cold-start 100-reservoir screening remains about 5.2–5.6 s with filtered embedded error control. Ordinary paced M9 performance is still unqualified. The user explicitly reconfirmed enabled pressure drop on 2026-09-16; all these measurements already include Darcy losses.

Work now includes bounded completion wakeups so short jobs can refill idle shared workers between server ticks. This change is under verification; persistent world coordination and gameplay remain outstanding.

### Automated gameplay testing decision

The user requested Minecraft MCP for M8 and direct progression to M9 without manual testing. The work order now specifies automated MCP gameplay/GUI evidence plus GameTest/packet checks, followed by M9 without a manual handoff. The connector is available and supports 1.21.1 with NeoForge; it currently reports no running or connected game. Gameplay has not yet been implemented or tested through MCP.

### Runtime and history implementation update — 2026-09-16

- Latest complete regression: **704 unit tests and all four dedicated GameTests passed**. Later buffer tests are recorded separately in the named runtime suite; the next full regression must include them.
- The shared worker configuration now defaults to automatic sizing (0); explicit 1–8 overrides remain available. Existing explicit values are preserved. Worker results carry optional CPU time as well as wall time. Cadence adaptation follows the planned 25% increase / 10% decrease with hysteresis.
- Approximate-mode qualification now checks the 0.01 bulk mole-fraction drift bound, including perturbations on both sides. Time-error control uses conservative step doubling across phase/device regime changes. The full physics suites pass.
- Per-pipe history accumulates gross forward and reverse component/phase transport separately with the physical TR-BDF2 quadrature. Storage is bounded to one history record per compiled pipe. Tests reconcile the history with inventory changes and preserve distinct compositions on reversal.
- The owner-thread island coordinator implements nonblocking publication barriers, retained debt, bounded dispatch, completion/stale-result handling, event fences, and conservative aligned merge/split transactions. A server bridge runs nine catch-up intervals through the existing shared pool in GameTest. It is not yet wired to persistent gameplay authority.
- Testing exposed a worker-return handoff race. The executor now has a bounded handoff queue of at most the worker count; admitted jobs and ready backlog remain bounded by the existing service. A controlled server-mailbox test completes 32 jobs without another game tick, then verifies all 70 completions and the 64-per-tick drain cap. All four GameTests passed in about 505 ms of test execution; this is not a paced benchmark.
- Immutable buffer transaction snapshots implement complete capacity reservation, 20/80 kg partial delivery, zero-acceptance deferral, stale-proposal refusal, and default 20 kg stop/resume hysteresis. Connecting these proposals to hydraulic inventory commits and the causal fixed-split module remains outstanding.

### Persistence, block topology and scheduled transfers

- A versioned core checkpoint codec preserves canonical inventories, energy-reference metadata, phase guesses, pipe history, clocks/fences, fallback anchors/allowances, and partial pending inputs/reservations. Missing fields, fractional integer stamps, unsupported versions and unapproved property/reference changes are rejected.
- Core `FluidSavedData` writes a checksummed byte payload and refuses to replace an unreadable existing authority with a fresh empty state. The actual NeoForge SavedData writer uses atomic file replacement. All five current GameTests passed (about 525 ms of test execution), including a depleted nitrogen charge, retained debt, corrupted payload refusal and an injected failed write preserving the previous file. This is not yet a full server-restart/chunk-unload gameplay test.
- `PhysicalFluidTopology` compiles registered block positions without reading chunks. It preserves half-section geometry, exact physical pipe length, directional pump/valve connections and debug mappings. An invalid zero-storage pump bypass disables that pump while passive equalization remains possible; a focused coupled-solver test passes.
- Scheduled injection/withdrawal terms now enter the coupled reservoir balances and TR-BDF2 stages. Bulk withdrawals use candidate composition and stream enthalpy; input energy includes the gravitational datum. Focused 5/15/30 s delivery tests conserve material and energy. The worker-local transfer planner passes 20/80 kg delivery, physical overfill reduction, unrelated feasible input continuation, actual withdrawal ownership and cancellation tests. The persistent causal module coordinator is still pending.

### Minecraft MCP test-client setup

The MCP launcher installation failed twice with `unexpected end of file`; it lists a partial Minecraft 1.21.1 / NeoForge 21.1.172 installation. The project requires NeoForge 21.1.219, so M8 will use the project's isolated `runMcpClient` Gradle run with MCP's official control mod. This retains Minecraft MCP for interaction and evidence collection.

Pinned control mod: [Minecraft MCP v0.3.0](https://github.com/langyo/minecraft-mod-mcp/releases/tag/v0.3.0), asset `minecraft-mcp-1.21.1-neoforge-v0.3.0.jar`, SHA-256 `c6cc12c960490e72a83fd5c2f8169294cd1adda4d838ad69d188e8b7d3b73695`. The verified download is in `run/mcp-client-mods/`; `prepareMcpControlMod` checks its hash before copying it to the disposable client's mods directory. It is excluded from the production artifact. No gameplay client has been launched or M8 check claimed yet.

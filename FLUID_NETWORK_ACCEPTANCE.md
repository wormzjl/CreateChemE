# Fluid network acceptance record

Updated 2026-09-16. Branch: `codex/simulation-fluid-network`.

**Release qualification is in progress.** Minecraft MCP replaces the manual M8 play-test handoff. Continue directly with automated M9 work; this does not waive unrun checks. Pressure loss is enabled in this build, including length, diameter, roughness, fittings, and elevation. The production integrator is TR-BDF2.

## Verified build

### Current class/tool consolidation checkpoint

Artifact `dd99bfd044c0e268ff6c69d5985c7fa20f6a8eb6aa01325f81889abe97946eeb` passes **789 unit tests, all 14 GameTests, and build** (`build/fluid-consolidation-regression.log`). The consolidated Python benchmark tool passes **12 tests**. Sol subagents consolidated shared fixtures and benchmark analysis while the parent consolidated production worker utilities and conserved-component mapping.

`WorkerAllocation` now groups sizing, demand hysteresis and CPU timing; `FluidThermodynamics` supplies the common conserved basis; test support uses two cohesive files; and `examples/Fluid-Benchmarks.py` replaces three audit/summary scripts with subcommands. Old worker classes are absent from the packaged JAR. All 60 pre/post cadence rows and the canonical report are exactly identical; numerical gates, configuration values and save-reference semantics are retained.

**Performance results below belong to the pre-consolidation artifacts named in each record.** This checkpoint ran correctness/integration validation only and does not transfer prior benchmark qualification to the new JAR. The existing numerical-depletion, aggregate-load, stress-recovery and M9 gaps remain open.

### Luna parallel-correctness checkpoint

The current production artifact remains `8dae23fe56e0139ec0b4d00c0931fddea208bd9708c896553695057578338d3a`. **789 Java tests pass with zero failures/errors/skips using two concurrent test JVMs** (`build/fluid-luna-parallel-unit.log`, 52 s Gradle elapsed). The earlier 14/14 GameTests remain on this unchanged production/fixture build. Java correctness parallelism is now selectable with `-PtestForks=2` (range 1–8; default 1), independently of the game engine's worker ceiling. Timed benchmarks remain isolated.

Nine portable Python evidence-gate regressions pass (`python -B -m unittest discover -s examples -p test_fluid_benchmark_summary.py -v`). The aggregator now verifies the preserved runtime log hash and error scan, recalculates raw timing/conservation/duration gates, requires complete per-island five-second histories and a full tick-cost history, and separates configuration/fixture revisions. It retains per-replicate and per-island results so pooled statistics do not conceal different trajectories.

Luna's new `SharedSourceDepletionQualificationTest` verifies independent BAL accounting, branch-order equivalence, atomically discarded failed intervals and a positive pump-work control. Both branch orders accept 11 intervals (0.11 s), retain 55.894% of source stock, reach 277.131 K / 176.804 kPa, then refuse numerically. Permutation errors are zero; component/energy residual maxima are `5.378e-8` / `1.845e-10` tolerance units. The excessive suction interval also refuses atomically; the adequate control advances 0.1 s at `9.7531e-5 kg/s` with `0.60894 J` pump work. These are safety/control passes, **not successful near-empty qualification**; the physical cause of the numerical refusals is not established. Exact messages and limits: `FLUID_LUNA_PHYSICAL_TEST_REVIEW.md`, `build/reports/fluid/P08-shared-source-depletion.json`, and `P24-finite-pump-suction.json`.

**The fixed-two-worker module repetition group now has three passing fresh-JVM runs:** `reload-budget-module-2w-r01`, `luna-module-2w-r02`, and `luna-module-2w-r03`, on identical production/science/configuration/fixture revisions. Each contains 200 contiguous five-second FULL intervals per island, from ticks 2400 through 22400. Measured holds/approximation and runtime-audit errors are zero throughout. Warmup holds are **0, 0, 2**, retained explicitly.

Pooled across 1,200 measured interval records: queue-inclusive completion median/p95/max **560.20/1,192.31/1,522.27 ms**; worker median/p95/max **137.93/1,139.08/1,521.28 ms**. Across 60,003 measured server ticks: engine median/p95/max **0.0489/0.2053/1.8994 ms per tick**. Component/energy balance errors remain <=`2.435e-7` / `7.581e-10` tolerance units. All three end with module committed tick 22200, zero pending transfers and one planned capacity record; this is not a quiescent soak result. Other profiles/worker groups and the soak remain open. Luna's independent review is `FLUID_LUNA_TEST_REVIEW.md`.

**Current-artifact 100-network stress integrity passes, but performance recovery is not clean.** `luna-stress100-12cap-r01` advances all 100 networks / 1,993 reservoirs with unloaded chunks, balances within tolerance, maximum 12 workers/outstanding jobs, and a clean runtime audit. It records 291 warmup and 273 measured holds; the final 1,200-tick window still has 28 holds and queue-inclusive p95 latency 113.975 s. Final maximum debt is 4.5 s, so it catches up by the end, but that does not erase prior delays. Whole-measurement engine p95/max: 1.9872/18.3643 ms/tick. The final 400-tick window is clean; no steady-state or ordinary-load pass is inferred from it. Host/JFR comparability caveats and all details are in `FLUID_NETWORK_STRESS_TEST.md` and Luna's review. Startup/recovery diagnosis remains open.

### Current reload, worker-equivalence and completion-budget checkpoint

Artifact `8dae23fe56e0139ec0b4d00c0931fddea208bd9708c896553695057578338d3a`: **787 unit tests and 14/14 GameTests pass; build succeeds**. The integrated build/GameTests are recorded in `build/fluid-reload-budget-gametest-r03.log`; the final unit-only cadence addition and full 787-test regression are recorded in `build/fluid-sol-final-unit.log`, with the same production JAR hash. Sol independently implemented/executed the real-executor worker-equivalence test and the actual-server completion-budget regression; see `FLUID_SOL_TEST_REVIEW.md` for independent interpretation. The earlier reload checkpoint's new fixture initially mistook a placement timestamp for a completed solve; that failed run is retained, and the fixture now waits for an actual FULL result and anchor.

Live reload now compares relevant scientific revisions before normal ticks and between-tick completion routing. Metadata-only changes continue. Changed/missing physics or transport data cancels old proposals, suspends fluid/module publication, retains committed inventory/energy/debt/fences and pending material, and invalidates fallback eligibility without renewing its allowance. Restoring qualified science resumes with a full solve. Arbitrary replacement EOS/data are not silently applied to saved energy: explicit qualification/migration remains required, and a restart with incompatible data refuses the saved checkpoint.

P12 now compares identical gas, water and wet-crude/N2/water trajectories at ticks 20/40/60 through real bounded executors configured for one, two and demand-based twelve workers. State, component amounts, energy, phase volumes, flow and substep histories agree; this is numerical equivalence evidence, not a throughput benchmark. Maximum outstanding ownership is not proof of simultaneous kernel execution.

The first contention attempt (`reload-qualification-contention-2w-r01`, earlier `0a006d83...` artifact) produced a nominal numerical pass but **20 server-task exceptions**. Sol traced these to a second completion drain after the per-tick allowance of 64 had been exhausted. The production routing boundary now returns an empty batch when its remaining allowance is zero, retaining excess terminal messages for later ticks. The new server regression verifies that completion 65 stays queued during repeated same-tick drains and all 70 callbacks eventually arrive without exceeding 64 per tick.

**Current-artifact contention passes:** `reload-budget-contention-2w-r02`, with zero runtime-log errors and a matching artifact/report audit. Both real CPU kernels ran 45.001 s (44.89/44.94 CPU-s). Maximum/final debt: 945/45 ticks; maximum outstanding/ready jobs: 2/0. No holds or approximation; component and energy balances pass. Engine p95/max: **0.1199/4.2363 ms per tick**.

Runtime logs are now a separate qualification gate. `fluidServerBenchmark` writes a hash-bound `runtime-audit.json` and fails on server-task, drain, unexpected-server, or failed-shutdown errors. The offline aggregator excludes missing/failed/mismatched audits and checks that ordinary samples really contain at least 200 contiguous five-second intervals per island. Raw reports remain preserved even when their nominal numerical pass is rejected.

**Full current-artifact module replicate passes:** `reload-budget-module-2w-r01`. After 120 s warmup, each of two islands has 200 accepted FULL intervals, each exactly 100 ticks, continuously covering simulation ticks 2400–22400 (1,000 s per island). Sol checked the raw histories for gaps, overlap and duplicates. Zero warmup/measured holds, zero approximation, zero runtime-audit errors. P95 worker/queue-inclusive completion/engine work: **1,146.95 ms / 1,236.44 ms / 0.2059 ms per tick**; completion/engine maxima: **1,522.27 ms / 1.761 ms per tick**. Component/energy errors: `2.435e-7` / `2.021e-10` tolerance units. This is one module replicate, not the completed M9 repetition matrix or soak.

**P31 physical cadence subset passes:** `CadenceTrajectoryQualificationTest` compares fixed 1/5/20-second updates and the production adaptive clock at matching 20/40/60/80-second timestamps for closed nitrogen, water and 22-component wet-crude/N2/water fixtures. Reference step ceilings of 0.5/0.25 s with step-doubling establish <=0.1% refinement; candidates satisfy <=0.5% pressure/inventory/integrated-mass and phase-fraction gates, <=0.5 K temperature, and independent closed component/energy balances. Observed adaptive cadences are 100, 125 and 112 ticks. Maximum pressure relative error is `1.1862e-4` (0.0119%); maximum temperature error `1.7635e-4 K`; maximum phase-volume-fraction error `9.7073e-11`. Evidence: `build/reports/fluid/P31-cadence-trajectories.json`. CPU observations are injected to exercise clock policy; aggregate CPU-load classification remains open. These are refined temporal references using the same EOS/integrator, not independent validation of the EOS itself.

Results from older artifacts below cannot qualify this checkpoint.

### Earlier stress-engine revision

User-requested 100-network stress case and production improvements are documented in `FLUID_NETWORK_STRESS_TEST.md`. The engine now allocates shared workers by demand up to **12**, pipelines independent bounded publication groups, retries shorter complete intervals after deadlines, and avoids repeated immutable-property hashing and idle topology validation. **782 unit tests and all 12 GameTests pass**, with a successful build (`build/fluid-stress-final-regression.log`).

Current artifact: `e4675976a69d6d40a2e24e2da70d501d275c7ccbb6e357b52e8321e71c635f67`. The matched 100-network stress run advances all 1,993 reservoirs with conservation, 28.027 useful equivalent intervals/s versus 2.964 before, final maximum debt 4.35 s, and p95 server engine cost 1.604 ms/tick. Cold-start holds remain recorded. This is stress evidence, not completion of M9. **The ordinary/module/contention qualification results below belong to older artifacts and must be rerun before qualifying this revision.**

Final-artifact longer stress run `stress100-final-12cap-long-r01` passes integrity/recovery: 60 s warmup + 120 s measurement, 22.321 useful equivalent intervals/s, final maximum debt 4.65 s, p95 engine work 1.0647 ms/tick, and at most 12 workers/outstanding jobs. It records 248 warmup holds and 13 measured holds; the last observed 1,200 ticks have zero holds/approximation, p95 completion 132.48 ms and p95 engine work 0.8632 ms/tick. Component/energy balances pass. This does not close ordinary zero-hold M9 qualification or the soak.

### Earlier velocity-clamp qualification

Earlier measured artifact SHA-256: `11872e234daa7ed2931999a20dc559332f432aba201a6dbf63a4ffc14f9f4647`. The property compatibility record is `FLUID_PROPERTY_COMPATIBILITY.md`; all 123 new 22-component grid/interior samples and the four independent hydraulic reference tests pass. Hashes and counts in this subsection are historical checkpoints.

Latest complete regression: **776 unit tests, zero failures/errors/skips, successful build**, `build/fluid-reference-full.log`. The additional five test methods change no production classes; the 12 dedicated GameTests at the previous implementation checkpoint remain applicable.

**Current-artifact two-worker contention passed:** `clamp-qualification-contention-2w-r01`. Both workers ran real CPU kernels for 45.002 s (44.89/44.88 s CPU). Maximum debt was 943 ticks (47.15 s); after the recovery window it was 41 ticks (2.05 s). Outstanding jobs never exceeded two; ready jobs never exceeded zero. No measured/warmup holds or approximate results. P95 engine work **0.1338 ms/tick**, maximum **16.3611 ms/tick**; the acceptance gate is p95, so the maximum is retained explicitly. Component and energy balance errors were `4.218e-7` and `1.624e-9` tolerance units. This separate contention case does not use the ordinary queue-latency gate.

**Full current-artifact module replicate passed:** `clamp-qualification-module-2w-r01`, 120 s warmup plus 1,000 s measurement, 200 intervals on each of two islands. No measured holds or approximate results. P95 queue-inclusive completion **1,719.26 ms**, worker **1,669.37 ms**, engine **0.2786 ms/tick**; maxima **1,895.23 ms**, **1,892.51 ms**, **2.8939 ms/tick**. Component and energy balance errors are `3.155e-7` and `1.394e-10` tolerance units (pass is <=1). **Two warmup holds** remain recorded. This is one replicate, not the completed M9 gate.

The next implementation checkpoint passes **771 unit tests and all 12 dedicated GameTests** (`build/fluid-independent-empty-full.log`, `build/fluid-independent-empty-gametest.log`). Independent physical edits can bypass an unrelated blocked event while shared identities, island dependencies and removal/replacement positions remain ordered at their original timestamps. Isolated canonical-empty vessels can advance without inventing gas; their UI omits temperature. Connected unsupported vacuum filling explicitly refuses before the numerical guess can supply material.

The first post-clamp module pilot failed its receiver deadline. Bounded per-edge cap caching and worker-owned reusable linear-solver scratch space retain the same equations, backward-error checks, 40 accepted substeps and 16 accuracy rejections in the saved replay. The second two-minute-warmup pilot **passed** with zero held/approximate measured intervals: p95 queue-inclusive completion **1,588.48 ms**, p95 engine work **0.2424 ms/tick**. Its **12 warmup holds** are explicitly retained in the raw report. Pilot evidence is not full qualification; the subsequent full replicate is recorded above.

**Velocity-clamp revision verified:** default 100 m/s plus the lower existing acoustic bound; component/energy balances and EOS/flash temperature/phase rules remain unchanged. **767 unit tests and all 11 GameTests pass**, followed by the final runtime/cache-revision suite and build. MCP confirms the formerly frozen tank has caught up and is FULL (0.9 s lag), and a separate high-pressure nitrogen pipe shows FULL / VELOCITY_LIMITED at 4.46 kg/s. The exact near-liquid-full regression also continues for 1,000 simulated seconds while retaining nitrogen. Current artifact SHA-256: `de67f7d8b62071d11027bf41644019dfca058908823c7c591424c9db0dfc5205`.

All performance numbers below predate this change and must not qualify the new artifact. The two-worker contention run was stopped on the user's instruction to implement the cap before further testing; it is not a pass. Current full-suite logs: `build/fluid-clamp-trace-final-unit.log`, `build/fluid-clamp-trace-final-gametest.log`; final revision validation: `build/fluid-clamp-revision-check.log`.

- Earlier pre-clamp full regression: **754 tests; zero failures, errors, or skips**, `build/fluid-full-regression-lifecycle.log`. The current 767-test result above supersedes this checkpoint.
- Dedicated Minecraft integration: **11 required GameTests passed**, `build/fluid-m8-lifecycle.log`.
- MCP: placement, removal/replacement, saved-world reopening, nitrogen initialization, water filling, pump target, generator presets/custom three-phase mixture, valve closed/open/regulating, and read-only forward/reverse pipe history. Screenshots and detailed observations: `build/reports/fluid/M8-gameplay.md`.
- Module restart continues the same physical trajectory with partial feed holdup or pending products. Removed active outlets retain stranded material; an unused zero-fraction outlet does not stop production. Tests reconcile component and energy ownership after each commit.
- The module performance pilot passed after a two-minute warmup, with no held/approximate intervals. Its p95 readiness-to-publication time was 1,476.91 ms and server engine work 0.1607 ms/tick. **Six measured samples are pilot evidence only.** See `build/reports/fluid/M9/pilot-module-warm-02/report.json`.
- The first full ordinary-load replicate **passed**: `qualification-one-2w-r01`, 200 measured five-second intervals after two minutes of warmup, 100 reservoirs / 1,000 physical pipes / 22 components / two shared workers. P95 readiness-to-publication **169.87 ms**, p95 engine server work **0.1506 ms/tick**; maxima **271.24 ms** and **3.0465 ms/tick**. Zero held or approximate intervals, including zero held warmup intervals. Component and energy balances pass. Artifact SHA-256: `2a36ec958729b7e13e1a2d4f0e94e3ad86456245eda4cfc824063f8daff7313f`. This is one replicate, not the completed M9 gate.

## Matrix audit

`Covered` means the cited automated assertion covers the stated row at its implemented fixtures; it is not a declaration that the entire milestone passed. `Partial` identifies a concrete remaining part. `Not run` has no acceptance result. The common qualification suite still needs the specified phase/topology combinations and seeded randomized coverage; a unit test name alone cannot establish those combinations.

Test class names below refer to `src/test/java/com/wormzjl/createcheme/`; GameTests refer to `src/fluidGameTest/`. Detailed numerical reports live in `build/reports/fluid/`.

| ID | Evidence | Status / remaining work |
|---|---|---|
| P01 | `NitrogenInitializationTest`, `FluidInventoryTest`, `EmptyNetworkTest`, world GameTests | Covered: one-time charge and canonical-empty isolated interval; unsupported connected vacuum filling cannot consume a numerical guess. |
| P02 | `HydraulicMatrixQualificationTest`, `PipeResistanceTest` | Covered: equal-state gas and liquid networks remain at rest. |
| P03 | `HydraulicReferenceQualificationTest`, `M2-laminar-compliance-reference.json` | Covered at the dilute-nitrogen two-vessel limiting fixture, both initial pressure directions, REF/BAL; broader transient qualification remains P13/P17. |
| P04 | `HydraulicReferenceQualificationTest`, offline decimal friction table, `M2-turbulent-reference.json`, `M2-transition-reference.json` | Covered: 112 water/nitrogen fixed-boundary cases across all regimes and both directions; one-sided C1 checks at both boundaries. |
| P05 | `HydraulicReferenceQualificationTest`, `M2-geometry-reference.json`, turbulent roughness sweep, section tests | Covered at independently evaluated length/diameter/roughness/fitting and zero/near-zero fixtures. |
| P06 | `PhysicalFluidTopologyTest`, `PipeSectionAggregationTest`, MCP probe images | Partial: local pressure-loss debug readout is not yet exposed. |
| P07 | `HydraulicMatrixQualificationTest`, `PassiveStepSolverTest`, topology tests | Covered at equal/unequal laminar branches in gas and liquid; independent Poiseuille flow-ratio reference. |
| P08 | `PassiveStepSolverTest`, `ConservativeTransportTest`, `SharedSourceDepletionQualificationTest` | Partial: two unequal branches and input permutations preserve BAL and atomic refusal, but the new source stops numerically at 55.9% stock remaining. Successful near-empty behavior is not qualified. |
| P09 | `HydraulicMatrixQualificationTest`, `PassiveStepSolverTest` | Covered: hydrostatic rest and reversed elevation with total energy accounting. |
| P10 | `NetworkRegimeTest`, `PhysicalFluidTopologyTest`, `ConservativeTransportTest` | Partial: consolidated in-interval reversal/history fixture with dead ends. |
| P11 | `IslandCoordinatorTest`, `WorldTopologyLedgerTest`, `FluidTopologyGameTests` | Covered: an unresolved causal horizon holds its dependent edit while an unrelated placement/removal commits; original event time and stock ownership are retained. |
| P12 | `HydraulicMatrixQualificationTest`, `WorkerAllocationTest`, Sol's `WorkerTrajectoryEquivalenceTest` | Covered at six seeded permutation graphs plus real-executor 1/2/demand-based-12 trajectory comparisons for gas, water and 22-component wet crude at identical timestamps, including conservation and phase/flow history. |
| P13 | `M3-analytic-gas-limit.json`, `M3-transient-screening.json` | Partial: complete analytic/general blowdown qualification mapping. |
| P14 | `GlobalLiquidResponseTest`, `M3-transient-screening.json` | Covered for shared compressibility and liquid-full compression fixtures. |
| P15 | `FLUID_PROPERTY_COMPATIBILITY.md`, property/compression tests, `M1-network-property-grid.json` | Partial: compatibility report now records 75 old-basis plus 123 gameplay-basis samples and retained native-PR compression errors; independent continuous-domain accuracy/adaptive phase-boundary coverage remains. |
| P16 | `FluidThermodynamicsTest`, `BoundaryAndPhaseTest`, MCP three-phase source | Covered at named two/three-phase fixtures; domain-grid consolidation remains part of M1. |
| P17 | `NetworkRegimeTest`, `M3-phase-time-screening.json` | Partial: qualify both directions of all required phase transitions against refined trajectories. |
| P18 | `ConservativeTransportTest`, `BoundaryAndPhaseTest`, reservoir GUI | Partial: actual block-face invariance scenario. |
| P19 | `NetworkRegimeTest` | Covered: different-temperature/composition feeds and reversed feed with component/enthalpy closure. |
| P20 | property refusal tests, `VelocityClampTest`, `McpGameplayRegressionTest`, MCP cap/catch-up screenshots | Covered for configured/acoustic limits, both directions, device/multiphase transport, release/reactivation, preserved EOS/energy behavior, and former sonic-refusal recovery. Thermodynamic-domain errors still reject. |
| P21 | `McpGameplayRegressionTest`, `BoundaryAndPhaseTest`, `EmptyWaterFillingAuditTest`, `SharedSourceDepletionQualificationTest` | Partial: shared-source drawdown/atomic refusal is tested; deep depletion and broader explicit empty-network behavior remain. |
| P22 | `FlowControlTest`, MCP pump target | Partial: consolidated target/head-limited/shutoff/reverse scientific and UI sweep. |
| P23 | `NetworkRegimeTest` | Covered: 10 bar suction with 5 bar added pressure, cases on either side of shutoff. |
| P24 | `FlowControlTest`, `ConservativeTransportTest`, `SharedSourceDepletionQualificationTest` | Partial: excessive finite-suction request refuses atomically and adequate-suction control transfers with pump-work/BAL closure. Refusal is numerical; its physical-domain cause is not proven and a bounded feasible low-stock sweep remains open. |
| P25 | `TopologyCompilerTest`, `PhysicalFluidTopologyTest` | Covered: pump bypass/cycle checks preserve permitted passive flow. |
| P26 | `NetworkRegimeTest`, `CausalModuleCoordinatorTest` | Covered: finite-tank recycle and material-owning module cycle. |
| P27 | `FlowControlTest`, `NetworkRegimeTest`, MCP valve images | Covered at closed/regulating/open and reverse-blocking fixtures. |
| P28 | `NetworkRegimeTest`, topology tests | Covered: bypass can defeat regulation; serial valves are permitted. |
| P29 | `BoundaryAndPhaseTest`, `ConservativeTransportTest`, server benchmark accounting | Covered per interval; cumulative persistent external-boundary accounting across restart remains to be qualified. |
| P30 | `NetworkRegimeTest`, `M3-transient-screening.json`, `McpGameplayRegressionTest` | Covered at recorded step-rejection, compression, headspace, and filling fixtures. |
| P31 | `IslandClockTest`, `CadenceTrajectoryQualificationTest`, coordinator adaptation logic | Partial: fixed 1/5/20 s and controlled adaptive trajectories pass refined references and conservation for gas, water and wet crude. Aggregate-load classification/performance and broader transition coverage remain open. |
| P32 | `FluidFallbackQualificationTest`, `M4-fallback-qualification.json` | Covered: accepted fallback for nitrogen, wet crude, and wet crude with pump, against full trajectories. |
| P33 | `FluidIslandCommandTest`, `ApproximationAnchor` tests | Covered at revision/regime/trust refusal fixtures; live data reload remains P47. |
| P34 | `FallbackAllowanceTest`, `FluidIslandCommandTest`, checkpoint tests | Covered for interval/duration caps, short slices, restart, and full recovery fixtures. |
| P35 | `IslandCoordinatorTest`, packet GameTest | Partial: real worker completion racing block replacement. |
| P36 | `IslandCoordinatorTest`, `CompletionWakeupTest`, runtime GameTests | Covered for bounded immediate catch-up, fairness, and completion wakeup. |
| P37 | shared executor tests, `IslandCoordinatorTest`, `FluidCompletionBudgetGameTests`, `reload-budget-contention-2w-r02` | Covered at current-artifact two-worker contention, overlapping-group deadline/bounded-owner fixtures and actual-server 64/65 callback deferral. Runtime-log audit passes; other worker-count qualification remains in the M9 protocol. |
| P38 | `CausalModuleCoordinatorTest`, `FluidModuleGameTests` | Covered: physical multi-feed withdrawal, product ownership, and shared-worker integration. |
| P39 | `BufferedTransfersTest`, `FluidCheckpointCodecTest` | Covered: 20 kg delivered, 80 kg retained under the same pending identity. |
| P40 | `ModuleTransferPlannerTest`, `BufferedCommitTest` | Covered for zero acceptance with feasible ordinary/input work. |
| P41 | `BufferedCommitTest`, `FluidCheckpointCodecTest`, `CausalModuleCoordinatorTest` | Covered: rejected staging and resumed physical trajectory. |
| P42 | `FixedSplitModuleTest`, `ProductionCapacityTest`, coordinator tests | Covered: capacity reservations, hysteresis, and actual multi-feed ownership. |
| P43 | `CausalModuleCoordinatorTest` | Covered: unknown horizons, known deferred inputs, empty and material-owning cycles. |
| P44 | `FluidHarnessGameTests`, `FluidPacketGameTests` | Partial: unloaded operation/identity verified; paired loaded-reference trajectory comparison remains. |
| P45 | checkpoint/save tests, `CausalModuleCoordinatorTest`, MCP restart | Covered for exercised debt/holdup/pending/reference/allowance persistence; full process restart with pending worker remains to be consolidated. |
| P46 | MCP removal/replacement, world and menu GameTests, module stranding tests | Partial: missing/replaced loaded bindings remain deliberately UNBOUND with stock retained; complete discovery/reconciliation report remains. |
| P47 | `FluidPropertyReloadGuardTest`, `PropertyReloadCoordinatorTest`, `FluidPropertyReloadGameTests`, `EnergyReferenceMigrationTest` | Covered for conservative reload invalidation/hold, stale/late proposal rejection, metadata-only compatibility, retained debt/allowance, restart incompatibility refusal, qualified-data restoration, and explicit datum migration. Arbitrary hot adoption of a replacement EOS is deliberately not automatic. |
| P48 | MCP images and packet schema tests | Partial: forward/reverse endpoints, quality/duration, full and held history verified; approximate/waiting GUI families and all device combinations remain. |
| P49 | `FluidPacketGameTests` | Covered: invalid/stale/distant/read-only/flooded edits, unloaded menus, and replacement identity. Debug chat rate-limit integration remains M8.4. |
| P50 | paced one/many pilots; full `qualification-one-2w-r01` passed | In progress: three fresh-JVM repetitions per profile, worker-count repeats, and pooled report remain. |
| P51 | `reload-budget-module-2w-r01`, `luna-module-2w-r02`, `luna-module-2w-r03`, module ownership suites | Partial: three matching current-artifact fixed-two-worker module replicates pass. Other worker counts and controlled temporary-product-lag performance remain. |
| P52 | no 30-minute paced soak result | Not run: topology/fallback/unload/recovery soak and bounded quiescence report required. |

## M9 execution protocol

```powershell
.\gradlew.bat fluidServerBenchmark -PfluidBenchmarkRunId=qualification-one-2w-r01 -PfluidBenchmarkProfile=one
```

Use a new run ID for every invocation; an existing benchmark world is rejected. Ordinary profiles are `one`, `many`, and `module`. The separate `contention` profile occupies all configured shared workers with real 45-second CPU kernels, records debt/queues, and verifies recovery; its expected queue delay is not judged against ordinary-load latency. Default is two shared workers; specify `-PfluidBenchmarkWorkers=1` or `0` for single-worker or automatic sizing. `-PfluidBenchmarkPilot=true` is a short diagnostic run and can never report a qualification pass.

Full runs use actual 20-TPS pacing, a two-minute warmup, and at least 200 measured intervals **per island**. Each report includes raw interval/server tick samples, actual full/approximate result quality, queue-inclusive completion latency, component/energy reconciliation, JVM arguments, production artifact SHA-256, fixture class hashes, and configuration hash. Three repetitions and all additional contention/soak gates are still required. Do not run another CPU benchmark or recompile the measured classes during a run.

## Frozen-network recovery decision

The user chose velocity clamping to address the demonstrated sonic-refusal deadlock. Its exact saved state now advances with conservation and EOS/flash unchanged. No repair-at-frozen-time action has been authorized or implemented, and timestamp rules are unchanged. Actual unsupported thermodynamic states can still hold; the cap is not permission to extrapolate properties or force temperature.

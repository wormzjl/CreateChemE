# Fluid batches: tooling cleanup before the merge (test classes, harnesses, probes and scripts)

Date: 2026-09-24. Batches `2026-09-23-fluid-scheduling-rest` (WP0 to WP5, released as 0.3.0) and `2026-09-23-fluid-followups` (F1 to F4, 0.4.0). Branch `claude/fluid-followups` in worktree `agent-ae139e4fc1b184b36`, over `88df883` (the owner's rule "Test classes and tools after a batch", `AGENTS.md`, recorded 2026-09-24). Not merged, not pushed.

Commits over `88df883`:

- `c1b8464` Detach the in-game rig's measurement log and run switches into tools
- `68d8877` Detach the F3 payload hook, retire the profile replays, drop unread save timing
- `f9d6be1` Record the fluid tooling cleanup in the changelog

The logs, suite XML, patch checks and the manifest of every stored file are in `cleanup-logs/`. The tool folders are under the worktree's `tools/`, for the orchestrator to copy into the main checkout's `tools/`. The rows for `tools/INDEX.md` are in `tools/INDEX-ROWS.md`.

## 0. Summary

- **Inventory (section 1).** 71 rows cover every test class, harness, probe, script, patch, data file, Gradle task and switch that the two batches created, or that serves only them, plus the pre-existing fluid tooling they used. The sources are `git diff --name-status 42fdf41..88df883`, `git log --diff-filter=A`, `build.gradle`, a search of `src/` for instrument names and system properties, a check of each batch-added method a probe calls, and every script and code file under both batch folders and the worktree's `build/`.
- **Removed from the code: 175 lines in `c1b8464` and 122 lines (10 added back) in `68d8877`, none of it run by a gate.**
  - Detached (`c1b8464`): the WP5 in-game rig's `FluidInGameDiagnostics` and `FluidInGameClientDiagnostics`, their registration in `CreateChemE`, and the `fluidRigDiagnosticTicks`, `fluidRigHeapMiB` and `fluidClientQuickPlay` switches with the `fluidRig` closure. `build.gradle` is now byte-identical to its pre-rig state `3b6f206`.
  - Detached (`68d8877`): the per-section byte hook of `FluidCheckpointCodec.islandUnit`, used only by F3's payload breakdown probe.
  - Deleted (`68d8877`): `FluidCheckpointStore.lastWriteNanos()` and `lastWrittenBytes()`, written for the benchmark and read by nothing.
  - Retired as dead (`68d8877`): the `fluidStressProfile` and `fluidModuleProfile` tasks, `-PfluidProfileSnapshot`, `FluidStressProfileTest` and `FluidModuleProfileTest`. Their saved worlds exist nowhere on this machine and predate the only readable checkpoint format.
- **Kept:** every test the five gates run, the product code (including `FluidRuntimeDiagnostics` and the `createcheme.fluid.scheduler.verify` self-check), and the paced harness `FluidServerBenchmark` with its tasks and properties, which plan section 6's protocol and every WP and F evidence run used.
- **Consolidated:** 185 files under `tools/`, 6.7 MB, in five folders:
  - `fluid-in-game-rig/` (5.8 MB, 83 files);
  - `fluid-probes/` (796 KB, 77 files);
  - `fluid-paced-analysis/` (60 KB, 9 files);
  - `mcp-gui-helpers/` (42 KB, 11 files);
  - `retired/` (28 KB, 4 files).

  Of these, 155 are byte-identical to their source, 11 were edited so they run from `tools/` (each edit recorded in a diff), 3 come from git (the stored sources and patches), 2 are generated diffs and 14 are READMEs (`cleanup-logs/tools-manifest.tsv`). A `TOOLS.md` pointer is in both batch folders.
- **Re-attach checks (section 3):** 29 of 29 pass (`cleanup-logs/patch-checks.txt`):
  - Each of the three patches applies to its removing commit and to the head, and reverse-applies to the commit before.
  - Applying each on its removing commit gives exactly the parent's blobs.
  - The stored sources equal `git show <parent>:<path>`.
  - The patches apply together, and in the CRLF working copy.
- **Gates on `f9d6be1` (section 5), identical to F4's final run on `d02b1d9`:**
  - science 182/0 and runtime 226/0, with the same test cases, class for class;
  - network 30/9, 19/14, 37/3;
  - regression exactly zero;
  - 30 GameTests, the same 28 batches;
  - P12 and P31 byte-identical to `fluid-scheduler/wp2-logs`;
  - `tasks --all` configures; `compileJava`, `compileTestJava` and `compileFluidGameTestJava` succeed.
- **`CHANGELOG.md`:** one `### Removed` line under the existing `## [0.4.0] - 2026-09-24` heading (`f9d6be1`).

## 1. Inventory

Batch keys: **SR** is `2026-09-23-fluid-scheduling-rest`, **FU** is `2026-09-23-fluid-followups`, **pre** means created before these batches (origin commit given). "Runs it" means what ran the item at `88df883`, before the cleanup. `M:` is `src/main/java/com/wormzjl/createcheme/`, `T:` is `src/test/java/com/wormzjl/createcheme/`, and `G:` is `src/fluidGameTest/java/com/wormzjl/createcheme/fluid/gametest/`. Evidence commands are in section 2.

### 1.1 Tracked code acted on

| # | path | origin | runs it | decision | destination |
|---|---|---|---|---|---|
| 1 | `M:runtime/fluid/FluidInGameDiagnostics.java` | `36caef6`, SR WP5 | Nothing but the rig. It registers only when `createcheme.fluid.diagnostics.logTicks` > 0, and only the rig's switch sets that; no test, GameTest or benchmark references it. | DETACH | `tools/fluid-in-game-rig/mod-side/` + `reattach.patch` |
| 2 | `M:runtime/fluid/FluidInGameClientDiagnostics.java` | `39aabac`, SR WP5 | Nothing but the rig (client only, under the same condition) | DETACH | same |
| 3 | `M:CreateChemE.java`: the 4 registration lines of 1 and 2 | `36caef6`, `39aabac` | mod construction; registers nothing without the property | DETACH | `reattach.patch` |
| 4 | `build.gradle`: the `fluidRig` closure and its calls on the `client` and `server` runs | `36caef6` | the rig drivers only (`run-server.js`, `run-client.js`) | DETACH | `reattach.patch` |
| 5 | `build.gradle`: switch `fluidRigDiagnosticTicks` | `36caef6` | the rig drivers only | DETACH | `reattach.patch` |
| 6 | `build.gradle`: switch `fluidRigHeapMiB` | `36caef6` | the rig drivers only | DETACH | `reattach.patch` |
| 7 | `build.gradle`: switch `fluidClientQuickPlay` | `36caef6` | the client driver only | DETACH | `reattach.patch` |
| 8 | system property `createcheme.fluid.diagnostics.logTicks` | `36caef6` | read only by 1 | DETACH (with 1) | `mod-side/FluidInGameDiagnostics.java` |
| 9 | `M:runtime/fluid/FluidCheckpointCodec.java`: the `islandUnit(..., Map<String,Integer> sections)` overload, its `mark`/`section` lambda and 13 `section.accept` calls | `ed4dfa4`, FU F3 | Only the uncommitted probe `F3PayloadBreakdownAfter` (F3 review section 2). No tracked caller passes a map: both callers use the 3-argument form. | DETACH | `tools/fluid-probes/f3/instrumentation/payload-breakdown-hook.patch` |
| 10 | `M:runtime/fluid/FluidCheckpointStore.java`: `lastWriteNanos()`, `lastWrittenBytes()`, their fields, and the `started`/`bytes` bookkeeping in `write` | `ed4dfa4`, FU F3 | Nothing. Javadoc: "for the benchmark's save report"; `git grep` finds no reader. The benchmark uses `FluidSavedData.SaveTiming.writeNanos`. | DELETE (dead, no tool value; git history keeps it) | none |
| 11 | task `fluidStressProfile` and `T:fluid/benchmark/FluidStressProfileTest.java` | `a5dedf6` (pre, 2026-09-16); adapted to format 4 in `ed4dfa4` | Nothing: no gate, no review of either batch. The default snapshot `run/fluid-benchmark/stress100-baseline-auto-r01/.../createcheme_fluid_core.dat` exists in no checkout on this machine and predates format 4. | DEAD, retired (the benchmark property `-PfluidStressProfile` with the same name is a different thing, row 25, and stays) | `tools/retired/fluid-profile-replays/` + `reattach.patch` |
| 12 | task `fluidModuleProfile` and `T:fluid/benchmark/FluidModuleProfileTest.java` | `a5dedf6` (pre); `ed4dfa4` | Nothing. The default `run/fluid-benchmark/pilot-module-warm-01/...` is absent and pre-format-4 (already noted absent in the solid-phase batch's `FLUID_SOLVER_REGRESSION_RERECORD.md`). | DEAD, retired | same |
| 13 | property `fluidProfileSnapshot` (system property `fluid.profile.snapshot`) | `a5dedf6` (pre) | only 11 and 12 | DEAD, retired | same (inside `reattach.patch`) |

### 1.2 Tracked code kept

| # | path | origin | runs it | decision |
|---|---|---|---|---|
| 14 | `M:runtime/fluid/FluidRuntimeDiagnostics.java` (opt-in counters) | `eb28fc5` (SR WP0), extended in `a0a791a`, `3075004`, `9d58e95`, `ff2fba1`, `5b3d903`, `924deaa`, `8781443` | Product recording sites. Tests assert its counters (`IslandCoordinatorTest`, `IslandSchedulerTest`, `CausalModuleCoordinatorTest`, `PhysicalRegistryTest`, `PresentationBucketTest`, `FluidCheckpoint*Test`, `IslandCertificateTest`, `FluidCompletionBudgetGameTests`), and `FluidServerBenchmark` reports all 35 counters through `names()`/`sample()`. | KEEP |
| 15 | `T:runtime/fluid/FluidRuntimeDiagnosticsTest.java` | `eb28fc5` | `fluidRuntimeTest` | KEEP |
| 16 | `createcheme.fluid.scheduler.verify`: readers `IslandCoordinator.VERIFY` (`a0a791a`) and `FluidCheckpointCodec.VERIFY` (`4f39701`); set in `build.gradle` on `fluidScienceTest`/`fluidRuntimeTest`, `test` and `fluidGameTestServer` (`a0a791a`) | SR | every gate (`final-fluidGameTestServerRunVmArgs.txt`: `-Dcreatecheme.fluid.scheduler.verify=true`) | KEEP |
| 17 | `FluidWorldAuthority.diagnosticSnapshots()` | `3075004`, SR WP2 | GameTests (`FluidCheckpoint`, `FluidPlacement`, `FluidPresentation`, `FluidPropertyReload`) and `FluidServerBenchmark` | KEEP |
| 18 | `IslandCertificate` evidence and refusal diagnostics | `4f1686e`, SR WP2 | `FluidServerBenchmark` reports | KEEP |
| 19 | `FluidPresentation` diagnostics (`deliveries`, `subscribed`, `dirty`, `stats`) | `9d58e95`, SR WP3 | `PresentationBucketTest`, `FluidPresentationGameTests`, `FluidWorldAuthority.viewDirty` | KEEP |
| 20 | `FluidCheckpointCodec` in-memory helpers (`Image`, `encode`/`decode`, `reseal`, `row`, `ledgerJson`, `withLedger`, `unitBytes`, `withIsland`, `withUnit`) and `FluidCheckpointStore.pack` | `4f39701`, `ed4dfa4` | `FluidCheckpointFormatTest`, `FluidCheckpointCodecTest`, `FluidCheckpointStoreTest` | KEEP |
| 21 | `M:runtime/fluid/FluidRuntimeMeter.java` | `a5dedf6` (pre) | product scopes in `CreateChemE` and `ProcessSolveCoordinator`; `FluidServerBenchmark`; formerly also 1 | KEEP |
| 22 | `G:FluidServerBenchmark.java` (profiles `one`, `many`, `module`, `contention`, `stress100`, `transient100`, `rest100`, `mixed100`, `viewers`) | `a5dedf6` (pre); 13 batch commits `eb28fc5` .. `3f30155` | the documented tasks `prepareFluidBenchmark`, `runFluidBenchmarkServer` and `fluidServerBenchmark`; plan section 6's protocol; every WP and F paced run | KEEP |
| 23 | `G:FluidContentionProbe.java` | `a5dedf6` (pre), unchanged | `FluidServerBenchmark`, profile `contention` | KEEP |
| 24 | `G:FluidTestMod.java` | pre | the GameTest mod; registers the benchmark hooks | KEEP |
| 25 | `build.gradle`: the three paced tasks and properties `fluidBenchmarkProfile`, `fluidBenchmarkRunId`, `fluidBenchmarkWorkers`, `fluidStressWarmupSeconds`, `fluidStressMeasurementSeconds`, `fluidStressProfile`, `fluidBenchmarkPilot`, `fluidBenchmarkPilotWarmupSeconds`, `fluidBenchmarkTestFailure` (`a5dedf6`), `fluidBenchmarkMemory`, `fluidBenchmarkHeapMiB` (`154007d`), `fluidBenchmarkDiagnostics` (`dba6179`) | pre | the paced protocol (plan section 6; e.g. F3's `paced.sh` used `fluidBenchmarkMemory`) | KEEP |
| 26 | `build.gradle`: `fluidRestDetection`, `fluidCertificateTolerance` | `82568c6`, SR WP2 | `prepareFluidBenchmark` (certificates off and on; the WP2 sensitivity run) | KEEP |
| 27 | `build.gradle`: audit pattern `fluid_presentation device=` in `fluidServerBenchmark` | `f081342`, SR WP3 | `fluidServerBenchmark` runtime audit | KEEP |
| 28 | `build.gradle`: tasks `fluidScienceTest` and `fluidRuntimeTest` (patterns `science.fluid.*`; `runtime.fluid.*` and `runtime.Fluid*Test`) | `a5dedf6` (pre) | gates | KEEP |
| 29 | GameTests added: `G:FluidCheckpointGameTests` (`86d3332`), `G:FluidPresentationGameTests` (`f081342`), `G:FluidPumpedFillGameTests` (`d17e76a`), `G:FluidPlacementGameTests` (`cfbb02d`) | SR, FU | `runFluidGameTestServer` (namespace `createcheme_fluid_test`) | KEEP |
| 30 | GameTests modified: `FluidCompletionBudgetGameTests`, `FluidHarnessGameTests`, `FluidModuleGameTests`, `FluidPropertyReloadGameTests`, `SolidPhaseGameTests` | pre; SR and FU edits | `runFluidGameTestServer` (with 29 and the untouched `FluidPacket`, `FluidPumpStartup`, `FluidTopology`, `MaterialReload`: 13 classes, 30 tests) | KEEP |
| 31 | Tests added in `T:runtime/fluid/`: `IslandSchedulerTest` (`a0a791a`), `IslandCertificateTest` (`3075004`), `PresentationBucketTest` (`9d58e95`), `FluidCheckpointFormatTest` (`4f39701`), `FluidCheckpointStoreTest` (`bf3e907`), `PhysicalRegistryTest` (`525aa8d`), `FluidDeviceSpecDomainTest` (`c26d162`), `IslandCertificateDomainTest` (`8781443`) | SR, FU | `fluidRuntimeTest` | KEEP |
| 32 | Tests added in `T:runtime/`: `FluidPumpedFillLineTest` (`e945e7f`, the F1 probe made permanent), `FluidThermoDomainHoldTest` (`8781443`) | FU | `fluidRuntimeTest` (`runtime.Fluid*Test`) | KEEP |
| 33 | Tests added in `T:science/fluid/`: `PumpRiseScalingTest` (`d02b1d9`), `FluidNitrogenCryogenicTest`, `ThermoDomainViolationTest` (`c26d162`) | FU | `fluidScienceTest` | KEEP |
| 34 | Tests modified in `T:runtime/fluid/` and `T:science/fluid/` (`CausalModuleCoordinatorTest`, `FairIslandQueueTest`, `FixedSplitModuleTest`, `FluidBasisTest`, `FluidCheckpointCodecTest`, `FluidPacketCodecTest`, `IslandClockTest`, `IslandCoordinatorTest`, `McpGameplayRegressionTest`, `ProductionCapacityTest`, `SolidRuntimeTest`, `WorldTopologyLedgerTest`, `FlowControlTest`, `NetworkRegimeTest`, `SharedSourceDepletionQualificationTest`, `TrBdf2Test`, `AmbientCrudePresetTest`) | pre; SR and FU edits | `fluidRuntimeTest`, `fluidScienceTest` | KEEP |
| 35 | P12 and P31 fingerprint writers `WorkerTrajectoryEquivalenceTest`, `CadenceTrajectoryQualificationTest` (ordered reports and digests since `eb28fc5`) | pre; SR | `fluidRuntimeTest`, writing `build/reports/fluid/P12-*.json` and `P31-*.json` | KEEP |
| 36 | `T:science/material/ViscosityTest.java` | `e660097` (pre); `c26d162` | `test` (column, material and thermo set) | KEEP |
| 37 | `T:fluid/benchmark/SolverRegressionHarness.java` + `FluidSolverRegressionTest`, task `fluidSolverRegression` and its `fluidRegression*` properties | `15403b9` (pre); harness adapted in `ed4dfa4` | `fluidSolverRegression` gate | KEEP |
| 38 | `T:fluid/benchmark/FluidNetworkBenchmarkTest.java`, task `fluidNetworkBenchmark`, switch `fluidProfile` (JFR) | `a5dedf6` (pre) | `fluidNetworkBenchmark` gate; `fluidProfile` now serves only this task | KEEP |
| 39 | `T:fluid/benchmark/FluidPropertyBenchmarkTest.java`, task `fluidPropertyBenchmark` | `a5dedf6` (pre); not touched or used by either batch | The documented task. It is not dead: its fixture is the bundled catalog (`MaterialCatalog.bundled()`), and the task description "fails until that milestone supplies an executable fixture" is stale. Not run here (no benchmark runs in this task). | KEEP, no action (section 7) |
| 40 | `build.gradle` `client` and `server` runs; `mcpClient` with `prepareMcpControlMod` (the MCP bridge jar copy) | `ce822d3`, pre | dev runs | KEEP: the non-rig parts are unchanged (server `--nogui`, the GameTest namespace; `git diff 3b6f206 f9d6be1 -- build.gradle` shows only rows 11 and 12) |
| 41 | `examples/Fluid-Benchmarks.py` (default jar from `mod_version` since `5f866a9`, SR) and `examples/test_fluid_benchmark_summary.py` | `a5dedf6` (pre) | run by hand for audits and summaries of paced reports; `examples/` stays per `AGENTS.md` | KEEP; the `tools/INDEX.md` note is stale (`INDEX-ROWS.md` item 4) |
| 42 | `examples/material-override/data/createcheme/materials/properties/tjl20_methane.json` | `e660097` (pre); `c26d162` | `MaterialPresetsTest` fixture | KEEP |
| 43 | The batches' product changes: scheduler, certificates, presentation and `fluid-4`, formats 3 and 4, placement batching, thermo domain, pump P1, material data (`M:`, `src/main/resources/data/.../materials/`) | SR, FU | product | KEEP (not instruments) |
| 44 | `T:runtime/fluid/EnergyReferenceMigrationTest.java`, `FluidSaveCompatibilityTest.java` | `a5dedf6` (pre); deleted in `4f39701` (SR WP4) | nothing: already deleted as legacy-save tests under the no-compatibility rule | already out, no action |

### 1.3 Outside the code: probes, scripts, patches, data

All of these were already outside the tracked code, in the batch folders or the worktree's `build/`. The batch copies stay where they are as evidence. The canonical copies are now in `tools/`, byte-identical unless the row says "edited" (`cleanup-logs/tools-manifest.tsv`).

| # | path (batch copy) | origin | what ran it | decision | destination |
|---|---|---|---|---|---|
| 45 | `fluid-scheduler/wp0-reference/TransientFixtureProbe.java.txt` | SR WP0 (`0176080`) | one-off probe (WP0 section 3.1) | CONSOLIDATE | `tools/fluid-probes/wp0/probes/` |
| 46 | `fluid-scheduler/wp2-probes/StationarityProbe.java.txt`, `ScratchWp2SettlingProbeTest.java.txt` | SR WP2 | one-off probes (WP2 section 3.3) | CONSOLIDATE | `tools/fluid-probes/wp2/probes/` |
| 47 | `fluid-scheduler/wp2-logs/campaign.sh`, `gates.sh` | SR WP2 | WP2 campaign and gates | CONSOLIDATE | `tools/fluid-probes/wp2/run-scripts/` |
| 48 | `fluid-scheduler/wp2-logs/winlen.js`, `wp2cmp.js`, `wp2diverge.js`, `wp2evidence.js`, `wp2tables.js` | SR WP2 | analysis of paced reports (WP2 to WP5, reused by F1) | CONSOLIDATE | `tools/fluid-paced-analysis/` |
| 49 | `fluid-scheduler/wp3-logs/wp3tables.js`, `wp3viewers.js` (`wp3cmp.js`, `wp3diverge.js` are identical to the WP2 scripts) | SR WP3 | WP3 analysis | CONSOLIDATE (duplicates not copied) | `tools/fluid-paced-analysis/` |
| 50 | `fluid-scheduler/wp3-logs/harness2.js`, `harness2-run.java.txt`, `harness2-presentation.java.txt` | SR WP3 | one-off source edit (the result is `04632e9`) | CONSOLIDATE (record) | `tools/fluid-probes/wp3/dev-edits/` |
| 51 | `fluid-scheduler/wp3-logs/campaign.sh`, `gates.sh` | SR WP3 | WP3 campaign and gates | CONSOLIDATE | `tools/fluid-probes/wp3/run-scripts/` |
| 52 | `fluid-scheduler/wp4-logs/wp4savetime.js` | SR WP4 | WP4 save-time table | CONSOLIDATE | `tools/fluid-paced-analysis/` |
| 53 | `fluid-scheduler/wp4-logs/campaign.sh`, `gates.sh` | SR WP4 | WP4 campaign and gates | CONSOLIDATE | `tools/fluid-probes/wp4/run-scripts/` |
| 54 | worktree `build/wp4tools/bench-edit.js` | SR WP4 | one-off source edit (the result is `4dcfa01`) | CONSOLIDATE (record) | `tools/fluid-probes/wp4/dev-edits/` |
| 55 | worktree `build/wp3mcp/Field.java`, `field.ps1`, `Flatten.java`, `Stack.java` (+ `.class`), `shot.sh`, `shot4.sh` | SR WP3, WP4 | dev-client GUI checks (WP3 section 5, WP4 section 5) | CONSOLIDATE (`field.ps1` edited) | `tools/mcp-gui-helpers/` |
| 56 | `fluid-scheduler/wp5-rig/` (drivers, datapack generator, template world, JFR settings, sampler, analysis, tables, `tools/`) | SR WP5 | the WP5 in-game campaign | CONSOLIDATE (base: the F2 copy) | `tools/fluid-in-game-rig/` |
| 57 | `fluid-followups/f1-rig/` (WP5 copy + `FILL6`, `f1-campaign.sh`, `f1-table.js`) | FU F1 | F1 in-game runs | CONSOLIDATE (F1-only files added) | `tools/fluid-in-game-rig/` (`f1-table.js`, `campaigns/f1/`) |
| 58 | `fluid-followups/f2-rig/` (F1 copy + `rest400`, marginal functions and phase, `f2-analyze.js`, `f2-campaign.sh`, `f2-pairs.sh`, `edits-gen.js`, `edits-run.js`) | FU F2 | F2 in-game pairs | CONSOLIDATE (base; 10 files edited for `RIG_LOGS`, `RIG_WORKTREE`, `RIG_JDK`) | `tools/fluid-in-game-rig/`, `campaigns/f2/` |
| 59 | `*/baseline-rig/` (`tracked-files.patch`, two baseline classes, `baseline-diagnostics-copy.sh`; three identical copies) | SR WP5 | the `eb28fc5` baseline worktree | CONSOLIDATE | `tools/fluid-in-game-rig/mod-side/baseline-eb28fc5/` |
| 60 | `fluid-followups/probes/PumpedFillProbeTest.java`, `FluidPumpedFillCoordinatedProbeTest.java`, `FluidPumpedFillRealCoordinatorProbeTest.java`, `DeadHeadProbeTest.java` | FU F1 | one-off probes (F1 sections 1 to 4) | CONSOLIDATE | `tools/fluid-probes/f1/probes/` |
| 61 | `fluid-followups/f1-logs/failures.js`, `limits.js`, `profile.js`, `xmlout.js` | FU F1 | trace and XML analysis | CONSOLIDATE | `tools/fluid-probes/f1/scripts/` |
| 62 | `fluid-followups/f1-logs/wip-with-trace-instrumentation.patch`, `wip-2-with-cap-and-trace.patch` | FU F1 (never committed) | the trace sink (F1 section 2) | CONSOLIDATE | `tools/fluid-probes/f1/instrumentation/` |
| 63 | `fluid-followups/f1-logs/gates.sh`, `paced.sh` | FU F1 | F1 gates and paced runs | CONSOLIDATE | `tools/fluid-probes/f1/run-scripts/` |
| 64 | `fluid-followups/f2-logs/probes/PlacementProfileProbe-before.java`, `PlacementProfileProbe.java`, `instrumentation-before.patch` | FU F2 | one-off probe and timers (F2 section 1.1) | CONSOLIDATE | `tools/fluid-probes/f2/probes/`, `instrumentation/` |
| 65 | `fluid-followups/f2-logs/scripts/` (`apply-edits.js`, `edits-*.js`, `extract_authority.js`, `extract_pairs.json`, `ledger_body.java.txt`, `instrument_before.js`) | FU F2 | one-off source edits and instrumentation | CONSOLIDATE (record) | `tools/fluid-probes/f2/scripts/` |
| 66 | `fluid-followups/f2-logs/gates.sh` | FU F2 | F2 gates | CONSOLIDATE | `tools/fluid-probes/f2/run-scripts/` |
| 67 | `fluid-followups/f3-logs/probes/` (`F3Fixtures`, `F3PayloadBreakdownProbe-before`, `F3PayloadBreakdownAfter`, `F3SaveTimingBefore`, `F3SaveTimingAfter`, `AtomicWriteProbe`, `breakdown-table.js`) | FU F3 | one-off probes (F3 sections 1, 2 and 5) | CONSOLIDATE | `tools/fluid-probes/f3/probes/` (+ row 9's patch in `instrumentation/`) |
| 68 | `fluid-followups/f3-logs/gates.sh`, `paced.sh`, `probe.sh`, `f3-harness.init.gradle` | FU F3 | F3 gates, paced run and probe runner | CONSOLIDATE | `tools/fluid-probes/f3/run-scripts/` |
| 69 | `fluid-followups/f4-logs/probes/` (`FingerprintProbe`, `NitrogenCryogenicProbe`, `PumpLineProbe`, `fit-nitrogen-cp-low.js`, `build-nitrogen-viscosity-tables.js`, `write-nitrogen-record.js`, `sci.sh`) | FU F4 | off-line probes and the scripts that wrote the nitrogen record (`c26d162`) | CONSOLIDATE | `tools/fluid-probes/f4/probes/` |
| 70 | `fluid-followups/f4-logs/nist/*.tsv` (7 files) | FU F4 | input data of 69 | CONSOLIDATE | `tools/fluid-probes/f4/nist/` |
| 71 | `fluid-followups/f4-logs/gates.sh` | FU F4 | F4 gates | CONSOLIDATE | `tools/fluid-probes/f4/run-scripts/` |

Not consolidated, because they are data or scratch rather than code:

- the rig's run outputs and the logs, reports, XML, JFR files and screenshots in the batch folders;
- the review fragments `*-section.md` in the rig copies;
- worktree `build/f1-trace/` (15 trace outputs);
- worktree `build/wp4tools/*.full.java`, `*.head.java` and `msg1.txt` (copies of tracked sources and a commit message draft).

**Counts by row:**

- KEEP: 31 rows (14 to 44; row 44 was already deleted by WP4).
- DETACH: 9 rows (1 to 9).
- DELETE: 1 row (10).
- Retired as dead: 3 rows (11 to 13), detached to `tools/retired/`.
- CONSOLIDATE: 27 rows (45 to 71).
- Left as data: 1 group.

## 2. Decisions and the rules behind them

The owner's rule (`AGENTS.md`, "Test classes and tools after a batch") and the brief's T2 decide every row:

- **KEEP: tests a gate runs.** Rows 15, 28 to 38. Every class that the `fluidScienceTest` and `fluidRuntimeTest` patterns match, `FluidNetworkBenchmarkTest`, `FluidSolverRegressionTest` with `SolverRegressionHarness`, the 13 GameTest classes that `runFluidGameTestServer` discovers, and the column, material and thermo tests under `test`. Section 5 shows that the same test cases ran before and after.
- **KEEP: product code.** Rows 14, 16 to 21 and 43, including the opt-in counters that tests assert and the scheduler self-check that the gates enable.
- **KEEP: harnesses that a documented Gradle task runs and that the batches' protocol relies on.** Rows 22 to 27: `FluidServerBenchmark` with `prepareFluidBenchmark`, `runFluidBenchmarkServer` and `fluidServerBenchmark`, and their properties. `fluidPropertyBenchmark` (row 39) is kept because it is not dead; the batches never used it.
- **DETACH: the in-game rig's mod side** (rows 1 to 8). Evidence:
  - `git grep -n -E 'FluidInGameDiagnostics|FluidInGameClientDiagnostics|diagnostics\.logTicks|fluidRig|fluidClientQuickPlay' 88df883 -- src build.gradle` finds only the two classes, their registration in `CreateChemE` and the `build.gradle` block.
  - Nothing sets `createcheme.fluid.diagnostics.logTicks` except that block.
  - What the classes read stays, because other code uses it: `FluidRuntimeDiagnostics.sample()`/`pause()`/`resume()` (the benchmark and tests), `FluidRuntimeMeter.enable`/`totalNanos` (the benchmark), `FluidWorldAuthority.diagnosticSnapshots()` (GameTests and the benchmark) and `ProcessSolveServices.diagnostics` (GameTests, benchmark, runtime). No test depended on the detached code.
- **DETACH: code only a review's one-off probe used** (row 9).
  - Method: list the methods the batches added to `src/main` (408 names) and intersect them with every call in the batches' probe sources (388 names). This leaves 83 shared names; exactly five of them have no caller in `src/test` or `src/fluidGameTest`: `islandUnit`, `envelope`, `total`, `verifying` and `toByteArray`.
  - Four of the five have product callers in `src/main`. `islandUnit`'s 4-argument overload has none: both tracked callers use the 3-argument form, and only `F3PayloadBreakdownAfter` passed a map.
- **DELETE** (row 10). A dead measurement accessor pair: nothing reads it (`git grep -n 'lastWriteNanos\|lastWrittenBytes' 88df883 -- src` shows only the declarations and the assignment), and it has no value as a tool, because the benchmark measures the write through `SaveTiming`.
- **DEAD, retired** (rows 11 to 13), under the brief's rule for `fluidStressProfile` and `fluidModuleProfile`: the default fixtures are gone. Evidence:
  - The two snapshot folders exist neither in the main checkout's `run/` nor in any of the 15 worktrees under `.claude/worktrees/` (checked 2026-09-24).
  - Any world they came from is format 1 or 2 (the fluid-network batch's M9 runs, before format 3 on 2026-09-23). `FluidCheckpointCodec` refuses every format but 4 ("Fluid checkpoint format N cannot be read"), and F4 changed the network package's thermodynamic revision, so no fluid world saved on this machine before F4 loads.
  - Each test needs a preserved failure state that a fresh paced run does not leave: an island never advanced past tick 0, or a pending input due at its receiver's committed tick.
  - No gate runs them (`test` excludes `**/fluid/benchmark/**`).
- **CONSOLIDATE** (rows 45 to 71): one folder per tool, per the brief's proposal, with two additions and one move (section 6).
- **No gate test was deleted or narrowed.** Every removed test class was outside every gate (rows 11 and 12), and the same test cases ran before and after (section 5).

## 3. What was detached and how to re-attach it

| detached | removed in | stored in | re-attach (repository root, at the removing commit or later) |
|---|---|---|---|
| `FluidInGameDiagnostics`, `FluidInGameClientDiagnostics`, their registration, the `fluidRig` closure and the three switches (175 lines) | `c1b8464` | `tools/fluid-in-game-rig/mod-side/` (`FluidInGame*.java` = `git show 88df883:src/main/java/com/wormzjl/createcheme/runtime/fluid/<name>`) | `git apply tools/fluid-in-game-rig/mod-side/reattach.patch` (`= git diff c1b8464 c1b8464~1`) |
| the `sections` overload of `FluidCheckpointCodec.islandUnit` (19 lines out, 9 in) | `68d8877` | `tools/fluid-probes/f3/instrumentation/payload-breakdown-hook.patch` (`= git diff 68d8877 68d8877~1 -- .../FluidCheckpointCodec.java`) | `git apply tools/fluid-probes/f3/instrumentation/payload-breakdown-hook.patch` |
| `fluidStressProfile`, `fluidModuleProfile`, `fluidProfileSnapshot`, `FluidStressProfileTest`, `FluidModuleProfileTest` (96 lines) | `68d8877` | `tools/retired/fluid-profile-replays/` (the two tests = `git show c1b8464:src/test/java/com/wormzjl/createcheme/fluid/benchmark/<name>`) | `git apply tools/retired/fluid-profile-replays/reattach.patch` |

**The check** (`cleanup-logs/patch-checks.txt`, run on the final tree `f9d6be1`) touches no working tree and uses no second worktree. For each patch it loads the removing commit into a throw-away index (`GIT_INDEX_FILE=$(mktemp) git read-tree <sha>`) and runs `git apply --cached --check`:

- Each patch applies forward on its removing commit and on `f9d6be1`, and reverse-applies on the commit before (`--reverse`, on `88df883` and `c1b8464`).
- Applying each patch on its removing commit gives blobs identical to the parent commit's, for every path it touches. For the rig: `build.gradle` 517b9c8, `CreateChemE.java` fb12810, and the two classes 9f1eb75 and 84bef60, which are exactly `88df883`'s.
- The stored sources equal `git show <parent>:<path>` (`cmp`).
- The three patches apply together on `f9d6be1`.
- `git apply --check` of each patch passes in the worktree's CRLF working copy (`core.autocrlf=true`), as a re-attaching agent would run it.

29 of 29 lines are `OK` or `SAME`. The same file also checks the historical patches kept with the tools against the commits they were made on: F1's two trace patches on `23beadd`, F2's timers on `41de055`, and the WP5 baseline patch on `eb28fc5`.

A re-attached rig registers nothing unless `-PfluidRigDiagnosticTicks` is given, so it does not change the gates. It is still local tooling: take it off before any commit (`git apply -R`).

## 4. The tool folders

| folder | size, files | contents | README |
|---|---|---|---|
| `tools/fluid-in-game-rig/` | 5.8 MB, 83 | The canonical rig: `f2-rig` as the base, plus F1's `f1-table.js` and campaign; campaign drivers and F2's edit scripts under `campaigns/`; the detached mod side and its patch under `mod-side/`; WP5's baseline patch under `mod-side/baseline-eb28fc5/`. Ten files are edited so the rig runs from `tools/` (`RIG_LOGS`, `RIG_WORKTREE`, `RIG_BASE_WORKTREE`, `RIG_JDK`; `changes-from-f2-rig.diff`). | purpose, batch, the differences between the three copies, prerequisites (Node, JDK, RCON, bridge jar, `run/eula.txt`), machine rules, re-attach, server and client runs and campaigns, scenarios (`rest1000`, `fill100`, `mixed100`, `view`, the marginal edits and the rest), the fields of `summary.json`, pitfalls, and the checks made |
| `tools/fluid-probes/` | 796 KB, 77 | `wp0/`, `wp2/`, `wp3/`, `wp4/`, `f1/`, `f2/`, `f3/`, `f4/`: probes, instrumentation, one-off edit scripts, NIST data, run scripts | a top-level README (layout, how a probe test is run, and the warning that a probe placed in `src/test` widens `fluidRuntimeTest`) and one README per package: what each probe measured, the review section that cites it, how it was compiled and run (`--tests` with `probe.sh` and the init script, or `sci.sh` with `javac`), and against which commit |
| `tools/fluid-paced-analysis/` | 60 KB, 9 | `wp2cmp.js`, `wp2diverge.js`, `wp2evidence.js`, `wp2tables.js`, `wp3tables.js`, `wp3viewers.js`, `wp4savetime.js`, `winlen.js` | usage, output and citing section per script; how to produce reports with the kept harness |
| `tools/mcp-gui-helpers/` | 42 KB, 11 | `Field` + `field.ps1` (real key events into an `EditBox`), `Flatten`, `Stack`, the WP3 and WP4 flatten one-liners | usage; `field.ps1` edited to read its classes from its own folder |
| `tools/retired/` | 28 KB, 4 | `fluid-profile-replays/` (two tests and `reattach.patch`) and a `README.md` covering it and the existing `EquipmentType.java` | removing commits, why retired, how to re-attach |

Checks made on the consolidated copies, without a game or Gradle run:

- `node gen-datapack.js` into an empty folder reproduces the template's datapack byte for byte.
- `analyze.js` and `f2-analyze.js` from `tools/`, run on a copy of `f2-logs/rig/srv-rest1000-f2after-r01`, reproduce that run's stored `summary.json` and `f2-summary.json` exactly (including the JFR parsing through `jfr.exe`).
- `node --check` passes on every rig script.
- `level.dat` of all three template copies carries `allowCommands 1`.

`tools/INDEX-ROWS.md` holds the four new rows, the replacement `retired/` row, the new "Last updated" line and the correction of the stale `examples/Fluid-Benchmarks.py` note. `documentation/fluid-scheduler/TOOLS.md` and `documentation/fluid-followups/TOOLS.md` name the folders.

## 5. Gates

All gates ran on the final tree `f9d6be1`, one Gradle invocation at a time, with `JAVA_OPTS=-Xshare:off`, a daemon with `-Xshare:off`, and no game running (27.1 to 27.4 GB free). The sequence is in `cleanup-logs/gates.log` (START/END lines with the commit and machine state), the logs are in `cleanup-logs/gate-final-*.log`, the suite XML is in `cleanup-logs/final-test-results/`, and the script is `cleanup-logs/gates.sh`. Before = F4's final run on `d02b1d9` (`f4-logs/gate-final-*.log`); `d02b1d9` to `88df883` changed only the changelog, `gradle.properties` and `AGENTS.md`.

| gate | before (F4, `d02b1d9`) | after (`f9d6be1`) | evidence |
|---|---|---|---|
| `compileJava compileTestJava compileFluidGameTestJava` | compiles | compiles (dev run `dev1` compiled the `68d8877` sources at 13:26; `final` found every task up to date with the same inputs) | `gate-dev1-compile.log`, `gate-final-compile.log` |
| `tasks --all` (the build script configures without the removed switches) | | configures; `fluidStressProfile` and `fluidModuleProfile` are absent, every other fluid task is listed | `gate-final-tasks.log` |
| `fluidScienceTest --rerun` | 182 / 0 (50 classes) | **182 / 0** (50 classes) | `gate-final-science.log`, XML |
| `fluidRuntimeTest --rerun` | 226 / 0 (44 classes) | **226 / 0** (44 classes) | `gate-final-runtime.log`, XML |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 | **30/9, 19/14, 37/3** (2, 10, 100 reservoirs) | XML, `final-M2-network-scaling-screening.json` |
| `fluidSolverRegression -PfluidRegressionMode=exact --rerun` | 0.000e+00 | **chain-100: 0.000e+00** in state/moles, temperature, phase fraction and flow | `gate-final-regression.log` |
| `runFluidGameTestServer -PfluidGameTestRunId=cleanup-final` | 30 passed | **All 30 required tests passed**; the same 28 batches as F4's run (`30 tests are now running`), scheduler self-verification on | `gate-final-gametest.log`, `final-fluidGameTestServerRunVmArgs.txt` |
| P12 / P31 SHA-256 | `56332b64ea3f3bde...` / `4dcb80a40266...` | **byte-identical** to `fluid-scheduler/wp2-logs` (`56332b64ea3f3bde9f23486ec71f708ad25b42044d655003c7982bcf9296be57`, `4dcb80a40266689328916775227f10a3b77f47b31e199ef2e2bd1d2d3441c645`) | `gates.log`, `final-P12-*.json`, `final-P31-*.json` |
| column, material, thermo (`test --tests ...`) | 568 / 0 | **not run**: nothing it shares was touched. No test in `science/column`, `science/material` or `science/thermo` and no class under `science/` references `CreateChemE`, `FluidCheckpointCodec` or `FluidCheckpointStore` (`git grep`, empty); the `test` task's configuration is unchanged; the removed tests were excluded from `test`. | |

**Neither widened nor narrowed.** `cleanup-logs/gate-comparison-with-f4.txt` compares every test case of the four JUnit gates between F4's XML and this run's: 0 cases only before and 0 only after, for each of `fluidScienceTest`, `fluidRuntimeTest`, `fluidNetworkBenchmark` and `fluidSolverRegression`. The GameTest batch lists of the two runs are identical.

## 6. Deviations from the brief

1. **Two tool folders added.**
   - `tools/fluid-paced-analysis/` holds `winlen.js`, `wp2cmp.js` and the other report scripts, which the brief placed under `fluid-probes/wp2/`. They analyse the kept harness's reports, WP3 to WP5 and F1 reused them, and they are not one package's probe, so an agent looking for "compare two paced runs" finds them from `tools/INDEX.md`.
   - `tools/mcp-gui-helpers/` holds the WP3 and WP4 dev-client helpers. They lived only in the worktree's `build/wp3mcp/`, which the rule does not allow to remain the only copy, and they are reusable GUI tooling rather than fluid probes.
   - `fluid-probes/wp2/` therefore holds WP2's probes and run scripts only, and `wp3/` and `wp4/` hold their one-off edit scripts and run scripts.
2. **Ten rig files and `field.ps1` were edited, not copied verbatim.** Otherwise the rig in `tools/` would write its logs to `tools/f2-logs/...` and drive a hard-coded worktree. The edits read `RIG_LOGS`, `RIG_WORKTREE`, `RIG_BASE_WORKTREE` and `RIG_JDK` and change nothing else. They are recorded as `changes-from-f2-rig.diff` and `changes-from-build-wp3mcp.diff`, and checked by the analysis reproduction and `node --check`. The campaign shell scripts under `campaigns/` stay verbatim as records.
3. **`level-allow-commands.js` is neither wired in nor removed.** It is kept as a documented template tool: the stored template already has `allowCommands 1` (checked in all three copies), so wiring it into `run-client.js` would change nothing, while a regenerated template needs it. I found no record in `wp5-logs/` of a client run stuck because of it. The recorded client failure is the first pilot, whose `execute_command` calls were refused; the driver has typed into chat since. The README documents the cheats pitfall either way.
4. **Beyond the rig, two more items left the code** under the brief's "anything else only a probe used" and DELETE rules: the F3 byte hook (detached) and the unread save-timing accessors (deleted). Both touch product classes. The unit bytes are unchanged (the hook only read the writer's size), and the checkpoint tests and GameTests pass.
5. **The profile replays were retired** (the brief anticipated this for `fluidPropertyBenchmark`, which turned out alive, section 1 row 39).
6. **The column, material and thermo gate was not run** (the brief allows skipping it; evidence in section 5). `compileJava compileTestJava compileFluidGameTestJava` was up to date in the final run, because the `dev1` run had compiled the same sources.
7. **Session start.** The session began in another worktree. The code edits, commits and gates ran in `agent-ae139e4fc1b184b36` through the shell from the start. When a write hook refused a direct file write, the session switched into this worktree with `EnterWorktree`, as the brief allowed. Scratch files were written only to the user's temporary folder. No other worktree and nothing in the main checkout was modified. The main checkout's `tools/INDEX.md` and documentation were only read.

## 7. Open items

1. **`fluidPropertyBenchmark` and `fluidNetworkBenchmark` descriptions** still say "fails until that milestone supplies an executable fixture"; both have fixtures. `FluidPropertyBenchmarkTest` is outside every gate and was not run here. Its hard-coded `Arrays.copyOf(..., 21)` component vector has not been checked against the current basis; one run of `fluidPropertyBenchmark` would settle whether it still passes. This is pre-existing tooling, so no action was taken.
2. **`examples/Fluid-Benchmarks.py`**: the default jar has been read from `mod_version` since `5f866a9`, but the main checkout's `tools/INDEX.md` still calls it stale (`INDEX-ROWS.md` item 4). `examples/test_fluid_benchmark_summary.py` was not run.
3. **Product leftovers the rule does not cover, not acted on:**
   - `IslandCertificate.intervalTicks()` (`3075004`) is an unreferenced public accessor on a product value class.
   - `FluidWorldAuthority.legacyUnbound` (the WP4 open item) is reachable only from test fixtures that save without a topology.
4. **Stale regression references** `src/test/resources/fluid/regression/quiet-11312.json`, `quiet-11324.json` and `cold-11312.json` are pre-existing, from `154007d`. Deleting them was asked of the maintainer in the solid-phase batch's `FLUID_SOLVER_REGRESSION_RERECORD.md`, and they are gate resources, so they were left.
5. **Data in the worktree's `build/`, not consolidated.** `build/f1-trace/` holds 15 trace outputs, 12 of which are not in `f1-logs/trace/`. Copy them into the batch's `f1-logs/trace/` before the worktree is removed if they should survive. `build/wp4tools/` holds scratch copies of tracked sources.
6. **The rig has not been run from `tools/`** (no game runs in this task). Its first in-game use after the path edits should be one short server run of `rest100`. The client driver's `EXPECTED` table still lists only the seven WP5 scenarios, and `build-tables.js` looks for WP5's `gates-section.md` next to itself (it prints "(not yet run)" without it). Both limitations are older than this cleanup.
7. **Copy-over.** The main checkout keeps these batches as `documentation/2026-09-23-fluid-scheduling-rest/` and `documentation/2026-09-23-fluid-followups/`, with per-package subfolders. This review, `cleanup-logs/` and the two `TOOLS.md` files are written for the batch roots. The `tools/` folders go to the main checkout's `tools/`. `tools/retired/README.md` is new there and covers `EquipmentType.java` too.

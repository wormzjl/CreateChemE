# Work order: Simulation engine and custom fluid networks

**Issued:** 2026-09-15  
**Status:** READY FOR IMPLEMENTATION — implementation milestones have not started  
**Specification:** [Final implementation plan and piping test matrix](SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN.md)  
**Matrix:** [P01–P52](SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN.md#6-piping-system-testing-matrix)

## 1. Delivery contract

Deliver the scientific/runtime foundation and seven v1 objects: reservoir, pipe, pump, pressure-sustaining valve, generator, void, and debug tool. Preserve the existing V3 calculator. Equipment-transfer integration is proven with a test-only fixed-split module; do not turn V3 into a processing machine or optimize its solver in this work order.

Implement the finalized choices: coupled implicit physics, calibrated liquid volumes and physical compression, separate-water hybrid, maximum-added-pressure pumps, reservoir-buffered recycle paths, partial/deferred deliveries, built-in equipment buffers, shared CPU workers, time-triggered approximate fallback with a three-interval cap, retained debt, and unloaded-chunk operation. No other-mod fluid bridge, phase-selective ports, offline simulation, pipe inventory, or moving contraptions.

This work order defines execution and evidence requirements; it does not claim any test or benchmark has already passed. Dates/durations are not estimated before the numerical feasibility gates. Milestone status advances only from recorded evidence.

### Milestone sequence

| Milestone | Deliverable | Prerequisites for passing | Current status |
|---|---|---|---|
| M0 | Baseline, portable test harness, linear-algebra adapter | None | NOT STARTED |
| M1 | Qualified reservoir, water, and transport properties | M0 | NOT STARTED |
| M2 | Compiled graph and passive coupled fluid solver | M1 | NOT STARTED |
| M3 | Multiphase regimes, pumps, valves, sources, sinks | M2 | NOT STARTED |
| M4 | Qualified bounded approximate-flow fallback | M3 | NOT STARTED |
| M5 | Shared scheduling, commits, deadlines, clocks, catch-up | M0, M4 | NOT STARTED |
| M6 | Built-in buffer contract and transactional module transfers | M3, M5 | NOT STARTED |
| M7 | Persistence, lifecycle, and unloaded-chunk authority | M5, M6 | NOT STARTED |
| M8 | Seven gameplay objects, GUIs, networking, debug tool | M3, M7 | NOT STARTED |
| M9 | Complete test matrix, paced benchmarks, soak, release candidate | M0–M8 | NOT STARTED |

Recommended integration order is M0 → M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8 → M9. M5's pure coordinator tests may be developed with fake kernels after M0, but its integration gate still requires M4. Do not let early UI work conceal a failed scientific gate.

### Checkpoint completion rule

For each checkpoint, record its ID, implementation revision, relevant existing worktree changes, command, test count, matrix IDs, fixture/scientific revisions, expected and actual values, residual/error maxima, elapsed time, and report locations. States are NOT STARTED, IN PROGRESS, BLOCKED, or PASSED. A zero-test discovery run, an unexecuted manual check, or a held ordinary operating case is not PASS.

Keep unrelated user edits intact. Record the starting commit and worktree manifest before implementation; scope changes to this work order. Do not reset existing material-data work. If it affects the inputs, include its exact revision in qualification evidence.

Ship scientific tests, fixture provenance, required benchmark harnesses, and build configuration as tracked project sources. Generated reports belong under `build/reports/fluid/`. Required checks must run without ignored local `tools/`, `research/`, or `benchmarks/` scripts. These two planning documents currently live in the repository's ignored `design/` folder; implementation evidence and harness portability must not depend on that folder being present in a fresh clone.

## 2. Shared validation and initial settings

The design's BAL, EQ, REF, TIME, APPROX, STATE, and PERF profiles are the acceptance contract. Conservation and supported-state validity are mandatory even during fallback. Approximate trajectories need not match full trajectories bit for bit.

### Settings to make explicit in code and reports

| Setting | Initial implementation value / selection rule |
|---|---|
| Hydraulic cadence | 5 simulated seconds; adaptive limits 1–20 s as specified in the design |
| Worker sizing | `max(1, min(8, availableProcessors - 2))`; explicit override permitted; qualification baseline uses exactly 2 shared workers |
| Module test cadence | Configurable; default 15 s; test at 5, 15, and 30 s against the 5 s hydraulic cadence |
| Numerical initial substep | `min(1 s, remaining interval)`; halve on rejection; increase at most twofold after accepted error/convergence checks; stop at timestamped events |
| Numerical effort guards | Initially 20 Newton iterations per active-set pass, 16 active-set passes per substep, 10 consecutive step halvings, and 1,024 attempted substeps per interval; hard deadline always wins |
| Island wall budget | Initially 2 s hard budget from dispatch, distinct from queue waiting and game time; 75% soft budget for full solve and remaining time for fallback/validation |
| V3 deadline | Keep its existing configurable behavior; do not apply the island's 2 s default to GUI column jobs |
| Fallback lifetime | At most 3 consecutive update intervals and 3 times the cadence captured at episode start; neither cadence changes nor restart extend it |
| Initial fallback trust envelope | Unchanged topology, basis, phase/device regimes; compared with last full anchor, pressure change <= 5%, temperature change <= 5 K, any bulk mole fraction change <= 0.01, phase-volume fraction change <= 0.02. Also require calibrated online residual checks and the APPROX gate. |
| Buffer working capacity | Configurable mass in kg, occupied + reserved; distinct from physical volume. The test module defaults to 100 kg per buffer, stop at exhausted capacity/empty feed, restart at 20 kg available feed and at least 20 kg free product working capacity. |
| Scientific enabled range | Only the package/domain intersection recorded by M1; never infer pressure limits from viscosity reference pressure or from a pure-water equation's full domain |

These numerical effort/budget values are explicit starting settings. M1–M4 may tighten trust/domain limits and tune numerical effort/soft-budget allocation based on recorded qualification, then freeze the selected settings in a versioned configuration profile. They may not loosen the published acceptance error tolerances to make a test pass. A required representative case that cannot pass blocks its milestone and gets a reproducible failure report; it is not silently relabeled unsupported. A domain restriction that removes a required material or gameplay feature requires an explicit scope revision.

### Property qualification gates

- Separate implementation accuracy from source/model accuracy. Compare formula implementations against independent published tabulations or independently generated reference fixtures; do not use the production implementation to generate its own expected answers.
- For analytic derivatives, require agreement with independently step-refined numerical derivatives to `1e-5` relative plus a dimensionally declared absolute floor away from regime boundaries. Test one-sided/domain-boundary behavior separately.
- Constant hydrocarbon volume translation must match its calibration density within 0.5%. Require held-out liquid-density error <= 5% on qualified real-fluid references and liquid isothermal-compressibility error <= 20% for at least three representative real hydrocarbons, including a heavier liquid. Record pseudocomponent uncertainty separately; do not claim measured accuracy from estimated characterization alone.
- Check IF97 liquid-water implementation against official reference points for density, u/h, and pressure/temperature derivatives. Align caloric references explicitly with the hybrid vapor model; test that alignment separately from the IF97 numerical values.
- Water vapor uses the ideal partial-pressure relation from the design. Qualify its density/compression error against a real-water reference at <= 2% over the declared steam subdomain. Publish finite maximum water partial pressure as a function of the enabled temperature range, plus the independently qualified total-mixture pressure limit. A pressure cap is a result of this comparison, not an invented universal number.
- Produce a package compatibility table with explicit lower/upper T and P, water-partial-pressure limit, supported phases, property data sources, revisions, estimated fields, and reasons for unsupported regions. Sample domain corners, phase boundaries, calibration holdouts, and an adaptive interior grid; record the grid and observed maxima. Do not advertise continuous-domain guarantees beyond the evidence.
- Minimum functionality to qualify: pure-water liquid/steam cases, a hydrocarbon gas, hydrocarbon liquid, a water/hydrocarbon/vapor fixture, the TJL20 basis, and at least two existing different-crude presets on that basis. Name the enabled presets and their common test range in the report. Missing property coverage for that minimum is a blocker, not a passing empty compatibility list.

## 3. Milestone work packages

### M0 — Baseline and test infrastructure

**Deliver:** a reproducible baseline; shared SI fixture builders and balance/error assertions; portable test/benchmark entry points; injectable time and controlled worker fakes; a sparse solver adapter.

| Checkpoint | Work and testable exit condition |
|---|---|
| M0.1 | Record commit/worktree, Java 21, NeoForge version, hardware, and existing regression results. Run the existing unit suite and build. Reproduce any pre-existing failure and distinguish it from new failures; no unexplained failure is accepted as a clean baseline. |
| M0.2 | Add named Gradle tasks listed in section 4 and verify nonzero test discovery with harness self-checks. Demonstrate that BAL detects intentional mass/energy corruption and STATE detects duplicate identity consumption. |
| M0.3 | Introduce a pure-Java general sparse-LU adapter using pinned `org.ejml:ejml-dsparse:0.44.0` and its required runtime dependencies. Verify Java 21 compilation, packaged-mod class availability, license inclusion, pivoted/singular/ill-scaled matrix tests, and no internal parallel worker pool. Use dense independent solves only as small-test references. |
| M0.4 | Configure a dedicated disposable fluid GameTest run with the test classes/templates actually attached to its test mod/source set. Demonstrate a named discovered test and a deliberate failing run that exits nonzero. Do not assume the existing main-only GameTest run sees ordinary `src/test` classes. |

EJML's official module list and sparse interface document the selected functionality: [modules](https://ejml.org/wiki/index.php?title=Download), [sparse solver API](https://ejml.org/javadoc/org/ejml/interfaces/linsol/class-use/LinearSolverSparse.html). Pinning 0.44.0 is a reproducibility choice, not a claim that it is the latest release. Reuse topology/assembly structure; reuse factorization analysis only where the actual backend supports it safely. Do not assume symbolic locking survives numeric pivot changes.

**Evidence:** `M0-baseline.md`, JUnit XML, discovery report, sparse-adapter report, and packaged-class smoke result under `build/reports/fluid/`.  
**Gate:** all four checkpoints pass. No network solver work is claimed complete here.

### M1 — Reservoir and transport science

**Deliver:** immutable component/energy state; constant volume translation; calibrated compression; hybrid water closure; residual/derivative APIs; energy-reference metadata; complete transport support for the required v1 qualification set.

| Checkpoint | Work and testable exit condition |
|---|---|
| M1.1 | Implement immutable state and versioned reference offsets without changing V3's published values. Test defensive copies, unavailable formation data, aliases/basis compatibility, and U-reference migration algebra. |
| M1.2 | Calibrate volume translation and pass the property qualification gates above. Show that constant translation leaves fixed-composition `dV/dP` unchanged while corrected compressibility is explicitly recomputed. Matrix P14–P15, P47 science portion. |
| M1.3 | Implement one shared vapor volume, ideal water partial pressure, IF97 Region 1 liquid properties, and accepted free-water/subsaturated regime state. Verify limiting dry/water-only states and reference-aligned energy. Matrix P16–P17 property portion. |
| M1.4 | Add missing required liquid/vapor viscosity data, including water vapor; validate mixture rules and reference-pressure approximation labels. Missing/out-of-domain data rejects explicitly. Matrix P20. |
| M1.5 | Run the property microbenchmark and publish the finite qualification envelope, compatibility table, reference fixtures, derivative checks, and per-phase evaluation costs. Report TP/PH/UV initialization separately from direct residual/property evaluation. |

**Evidence:** `M1-properties.json`, `M1-compatibility.md`, `M1-derivatives.json`, and `M1-property-cost.json`, including source provenance and independent reference revisions.  
**Gate:** the required material/phase set passes all science gates. Freeze enabled property domains before M2. No timing or density promise may be based on an unmeasured estimate.

### M2 — Graph compilation and passive coupled solve

**Deliver:** persistent-ID graph representation independent of Minecraft; compiled pipe runs; the simultaneous backward-Euler residual system with sparse Newton; passive conservative transfers.

| Checkpoint | Work and testable exit condition |
|---|---|
| M2.1 | Compile series runs while preserving lengths, fittings, elevations, and debug mapping. Test components, branches, multigraph parallel edges, and pump bridge diagnostics on the reservoir-split graph. P06–P07, P25–P26 topology portions. |
| M2.2 | Implement laminar/transition/turbulent pressure-loss laws with C1 matching and finite nonzero zero-flow slope. Pass independent relation/derivative tests P02–P05. |
| M2.3 | Assemble accumulation, U/V, equilibrium, junction, and hydraulic rows together. Enforce ledger conservation; no sequential hydraulic/black-box-flash coupling loop. Pass P01–P10, P13–P14, P21 under REF/EQ/BAL. |
| M2.4 | Verify order invariance in fixed mode and uncompressed versus compressed graphs; seeded small-graph tests cannot lose material. P12 and matrix coverage rules. Publish equation/unknown counts, residual scaling, sparsity, and linear-solve diagnostics. |

**Evidence:** `M2-passive-matrix.json` and `M2-solver-structure.md`.  
**Gate:** required supported passive cases converge and pass REF/EQ/BAL. Holding every difficult network is a failure of this gate.

### M3 — Phase/device regimes and full numerical qualification

**Deliver:** pump and pressure-valve active sets, source/sink boundaries, bulk multiphase transport, event-aware substeps, full-solver accuracy qualification.

| Checkpoint | Work and testable exit condition |
|---|---|
| M3.1 | Implement bounded deterministic active-set changes for phase/water/pump/valve modes. Reject cycles and restore accepted history on rollback. Pass P16–P19, P27–P28, P30. |
| M3.2 | Implement flow target, maximum added pressure, natural-flow limiter, suction availability, shutoff, and pump-work accounting. Pass P22–P24 without an absolute outlet-pressure trip. |
| M3.3 | Implement generators and voids with one-way boundary behavior and explicit external ledgers; apply revised loop diagnostics. Pass P25–P29, including bulk water/hydrocarbon/vapor transfers. |
| M3.4 | Pass TIME against independently refined full trajectories for blowdown, small gas volume, liquid-full compression, mixing, reversal, and phase transitions. A forced rejection matches a fresh smaller-step run. Finalize scaling, iteration/active-set guards, and step-control settings. |

**Evidence:** `M3-devices-and-phases.json`, `M3-time-refinement.json`, and a versioned full-solver settings profile.  
**Gate:** all full-physics matrix rows assigned to M3 meet EQ/BAL/TIME or the specified safe unsupported outcome. This is the prerequisite for approximate-flow work.

### M4 — Bounded time-based approximate flow

**Deliver:** approximate-acceptance mode of the same solver, online qualification checks, budget partition, full/approximate/held quality states, and bounded recovery.

| Checkpoint | Work and testable exit condition |
|---|---|
| M4.1 | Inject a monotonic clock to force the wall-time soft-budget path. Reuse accepted regimes/local derivatives and the existing assembly/ledger code; reconstruct and recheck the candidate rather than automatically accepting one Newton iterate. P32. |
| M4.2 | Demonstrate a useful accepted fallback for smooth-state misses and pass BAL/APPROX over the full grace period. Calibrate direct residual/trust checks against full-reference runs and near-limit perturbations; freeze the resulting profile. |
| M4.3 | Reject phase/topology/domain/regime-invalid candidates and candidates that overdraw inventory. Enforce three intervals and captured-duration bounds, including smaller catch-up slices and cadence changes. P20, P33–P34. |
| M4.4 | Recover from current approximate inventories without replay or reversal of committed transfers. Show that a slower simulated clock may choose fallback while a faster clock completes fully, both respecting their different accuracy contracts. |

**Evidence:** `M4-fallback-qualification.json`, `M4-trust-profile.json`, and a full-versus-approximate trajectory comparison.  
**Gate:** at least one meaningful fallback succeeds and all rejection/lifetime cases pass. An always-reject stub or weakened balance tolerance fails. If no useful case qualifies within the wall budget, report a blocked feasibility gate rather than silently omit the requested fallback.

### M5 — Runtime orchestration and clocks

**Deliver:** generalized immutable commands/results, coordinator-owned readiness, nonblocking cohort publication, owner isolation, bounded cancellation, shared-pool scheduling, retained debt, and work-conserving catch-up.

| Checkpoint | Work and testable exit condition |
|---|---|
| M5.1 | Integrate island jobs and V3 GUI commands in the one existing CPU service. Validate thread confinement, one outstanding job per owner, fair readiness, bounded memory/admission, and no worker waiting on its own pool. P11–P12 runtime portions. |
| M5.2 | Full/approximate/held outcomes have one terminal disposition; hard cancellation and stale stamps cannot publish late. Worker capacity remains occupied until actual completion/drain. Exercise saturation and shutdown with controlled latches. P35. |
| M5.3 | Keep simulation time, update cadence, and wall deadlines separate; preserve event timestamps and debt. Test nine-interval idle catch-up, fairness when another owner becomes ready, split/merge alignment, and visible wait reasons. P31, P36. |
| M5.4 | One-worker and all-workers-busy cases expose expected waits; fallback is never run on the server thread. Use fake clock/latches for unit tests and a real bounded long-job run at M9. P37. |

**Evidence:** `M5-runtime.json`, deterministic event traces, and pool/queue/shutdown bounds.  
**Gate:** all runtime STATE assertions pass and accepted approximate intervals are absent from debt/replay. No reserved worker is introduced as an unreviewed workaround.

### M6 — Built-in buffers and module transfers

**Deliver:** buffer identities/capacity accounting, pending-input ledger, partial delivery, causal horizons, replaceable equipment behavior, and a parameterized fixed-split test module.

| Checkpoint | Work and testable exit condition |
|---|---|
| M6.1 | Implement kg working-capacity reservations distinct from physical volume; default stop/resume hysteresis; per-equipment backpressure policy interface. The module consumes only material actually withdrawn into its ownership. P38, P42. |
| M6.2 | Deliver a 100 kg record in a 20 kg portion and later 80 kg portion with one identity and monotonically increasing delivery revision. Fractional energy/composition and reservation-to-occupancy transitions pass BAL/STATE. P39. |
| M6.3 | A due but infeasible input defers, including zero acceptance, while valid receiving flow and other feasible inputs advance. Forced failure after staging cannot consume any staged portion. P40–P41. |
| M6.4 | Configure 5/15/30 s module cadences; test delayed feed/output, multiple products, multiple feed ownership, unknown delivery fences, known deferred records, and a buffered module cycle. No future reads, retroactive inputs, or circular bootstrap waits. P26 module portion, P43. |

**Evidence:** `M6-transfers.json` with global inventory/pending/holdup and energy reconciliation at every commit.  
**Gate:** no duplicate/lost material, no unlimited hidden capacity, and a blocked delivery alone cannot freeze an otherwise valid receiver. Production V3 remains untouched apart from its existing runtime adapter.

### M7 — Persistent world authority and unloaded chunks

**Deliver:** versioned SavedData, authoritative topology/inventories, pending/remainder ownership, module/fallback state, identity reconciliation, and loaded-only TE binding.

| Checkpoint | Work and testable exit condition |
|---|---|
| M7.1 | Round-trip and restart committed state containing pending portions, reservations, debt, phase/device modes, energy references, and fallback allowance. Inject failures at transaction boundaries; reload the previous or next complete snapshot, never a mixed ledger. P41, P45, P47. |
| M7.2 | In a disposable integration world, unload intermediate chunks and all endpoint chunks; verify continued simulation without chunk tickets or world reads from workers. Reload and compare with a loaded reference. P44. |
| M7.3 | Break/replace blocks and reconcile missing/unregistered identities at discovery time. Preserve explicit destruction/stranded-transfer ownership, including pending products aimed at removed buffers. P46. |
| M7.4 | Stop/restart the server with work pending; preserve debt accrued online and add none for downtime. Test format/version rejection and explicit energy-reference migration, not silent reinterpretation. P34 restart portion, P45, P47. |

**Evidence:** `M7-persistence.json`, named GameTest results, before/after saved-state manifests, and no-force-load assertions.  
**Gate:** lifecycle checks run in an actual server harness, not serialization unit tests alone. Exact ownership and allowance persistence pass.

### M8 — Blocks, menus, packets, and debug tool

**Deliver:** the seven registered gameplay objects, server-validated controls, subscribed result views, debug interaction, and coherent loaded TE presentation.

Prototype defaults: reservoir volume 1 m³, pipe internal diameter 0.05 m, one block = one metre of pipe length/elevation, steel-like roughness 0.000045 m, pump target 0.01 m³/s at suction and maximum added pressure 500,000 Pa, ideal v1 pump efficiency 1.0, valve target 200,000 Pa absolute, void pressure 101,325 Pa absolute, and generator pure water at 298.15 K / 101,325 Pa when this state passes M1. Store defaults as validated data/configuration, not scattered constants. Invalid settings or unsupported generator presets cannot be applied. These are prototype setup values, not survival balance or industrial calibration.

| Checkpoint | Work and testable exit condition |
|---|---|
| M8.1 | Register all seven objects and their persistent identities, connection rules, and menus. Placement/removal updates graph events exactly once; movement on contraptions is disabled. P46 gameplay portion. |
| M8.2 | Server-authoritative controls validate finite values, property ranges, permissions/proximity, menu identity, and revision. Stale/invalid requests cannot mutate science state. Subscribers and TE updates remain bounded. P49. |
| M8.3 | Inspect pressure/temperature, volume and phase fractions/compositions, bulk-withdrawal notice, pump differential limit, valve saturation, generator/void flow, and full/approximate/held/waiting statuses. Verify pipe-run debug mapping and historical-rate labels. P06, P18, P48. |
| M8.4 | Run dedicated and integrated-server scenarios, menu-open chunk unload/reload, and debug chat rate limiting. Record visual checks/screenshots and automated packet/state assertions. Verify V3 still acts only as its calculator. |

**Evidence:** `M8-gameplay.md`, screenshots for each GUI/state family, named GameTests, and packet validation results.  
**Gate:** all seven objects work through normal gameplay interaction, not only test commands; no force-loaded chunks, duplicate TE inventory, or out-of-scope external fluid bridge.

### M9 — Acceptance and release candidate

**Deliver:** completed matrix, reproducible performance/soak evidence, qualified configuration/material manifests, portable build, and release limitations.

| Checkpoint | Work and testable exit condition |
|---|---|
| M9.1 | Run every P01–P52 case and list its milestone evidence. Run randomized graph coverage with recorded seeds and the fixed-settings versus adaptive/fallback comparisons. No unexplained NOT RUN row. |
| M9.2 | On the recorded Ryzen 7 9700X environment, use exactly two shared workers and the qualified revisions. Run 100 R / 1,000 pipe / up to 24 components as one network, as many networks, and with a test-module boundary. Meet ordinary PERF gates without hiding queue time. P50–P51. |
| M9.3 | Repeat with one worker and automatic worker count; inject a real bounded 45 s occupied-worker case and phase/domain refusals. Verify expected lag, causal waits, bounded memory, and catch-up. Contention is reported separately from ordinary latency gates. P31, P37. |
| M9.4 | Run a 30-minute paced 20-TPS soak with the P52 actions and periodic BAL/accounting snapshots. Verify jobs, reservations, subscriptions, and derived caches return to bounded quiescent levels after workload removal/recovery. |
| M9.5 | Run final unit/build/GameTest checks in a reproducible checkout without optional ignored local tooling. Verify packaged dependency loading, save/restart, existing V3 regressions, and all listed non-goals/limitations in user-facing documentation. |

Benchmark protocol: fixed fixture seeds/settings; a fresh JVM per replicate; 2 minutes of warmup followed by at least 200 measured intervals per steady scenario; three replicates; report each replicate and pooled median/p95/max with raw per-interval data. Keep actual game-tick pacing for server timing. The separate 30-minute soak is not a substitute for these ordinary-load samples. Property microbenchmarks, unpaced loops, and successful worker-only timing cannot establish the server-thread PERF gate.

**Evidence:** `M9-matrix.csv`, `M9-benchmark.json`, raw interval samples, `M9-soak.json`, final JUnit/GameTest reports, and `M9-release-readiness.md`.  
**Gate:** all required science/correctness/lifecycle checks pass; ordinary PERF targets pass; contention behaves as specified; residual risks are limited to the declared model/domain limitations. A failed numerical or timing gate is reported honestly and blocks release readiness rather than being renamed a success.

## 4. Verification entry points

The following existing commands can be used now during implementation baseline/final verification:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

M0 creates these **planned** named entry points in tracked build configuration; they do not exist merely because this work order names them:

| Command | Purpose / first substantive gate |
|---|---|
| `.\gradlew.bat fluidScienceTest` | Pure graph, thermo, transport, coupled solver, and fallback tests; M1–M4 |
| `.\gradlew.bat fluidRuntimeTest` | Scheduling, clocks, ownership, transfer-ledger, and persistence codec tests; M5–M7 |
| `.\gradlew.bat runFluidGameTestServer` | Dedicated test-mod server with discovered named integration tests; M7–M8 |
| `.\gradlew.bat fluidPropertyBenchmark` | Reproducible phase-property/derivative/initialization cost report; M1 |
| `.\gradlew.bat fluidNetworkBenchmark` | Pure island scaling and numerical work accounting; M2–M4 and M9 |
| `.\gradlew.bat fluidServerBenchmark` | Paced server baseline, module-boundary, contention, and soak scenarios selected by explicit fixture/profile parameters; M9 |

Dedicated benchmark tasks must record all settings, write machine-readable output, and fail when a required case has no result. Ordinary unit tests should not have flaky two-second assertions on arbitrary CI hardware; hardware-specific performance gates belong to the recorded benchmark profile. Test deadlines/cancellation deterministically with injectable clocks, then confirm real scheduling separately.

## 5. Final handoff and completion record

After each milestone, append a report with:

```text
Milestone/checkpoint:
Status:
Implementation revision and baseline:
Commands and test counts:
Matrix IDs exercised:
Fixture/property/configuration revisions:
Expected versus measured results:
Evidence paths:
Known failures or limitations:
Next unblocked checkpoint:
```

The final release handoff includes the completed P01–P52 matrix; enabled-material/domain table (including water partial-pressure limits); frozen full/fallback settings; save/protocol versions; ordinary and contention benchmark results; restart/unloaded evidence; and remaining declared limitations. Preserve a failed scenario and its seed whenever a checkpoint blocks, so the next implementer can reproduce it immediately.

This work order is complete as an arrangement of work. **All M0–M9 implementation checkpoints are still NOT STARTED.** Only the plan, work order, and matrix have been prepared in this task.

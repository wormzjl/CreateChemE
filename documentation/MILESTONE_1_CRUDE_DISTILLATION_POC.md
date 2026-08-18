# Milestone 1: crude-distillation calculator PoC

**Target:** Minecraft 1.21.1, NeoForge, Java 21<br>
**Dependencies:** Create, JEI, KubeJS<br>
**Status:** implementation plan, 2026-08-19

This milestone implements the first executable vertical slice of CreateChemE: one placeholder block with a GUI that solves a steady crude-distillation column only when the player presses **Calculate**. It is a scientific calculator embedded in Minecraft, not yet a continuously operating process machine.

The reusable scientific basis remains in [SCIENTIFIC_MODEL.md](./SCIENTIFIC_MODEL.md), the source-case comparison remains in [CRUDE_DISTILLATION_BENCHMARK.md](./CRUDE_DISTILLATION_BENCHMARK.md), and the future continuous/asynchronous plant architecture remains in [ADAPTIVE_SIMULATION_ARCHITECTURE.md](./ADAPTIVE_SIMULATION_ARCHITECTURE.md).

## 1. Milestone outcome

The completed PoC must provide:

- one registered placeholder block, item, block entity, menu, and screen;
- an editable main-column case with a named crude assay and bounded operating inputs;
- a server-authoritative, steady equilibrium-stage calculation;
- top, direct side-draw, and bottom product results in the GUI;
- the same calculation result in the server console through structured logging;
- persistence of the last accepted input and last valid result;
- explicit convergence, validation, and failure diagnostics;
- client and dedicated-server compatibility with the pinned Create, JEI, and KubeJS versions.

No calculation runs per tick. Editing a field does not change the displayed result. Only an accepted **Calculate** request may start a solve, and only a successfully committed result may replace the displayed product table.

## 2. Scientific contract

### 2.1 Player inputs

| Input | PoC definition |
|---|---|
| Crude assay | Named, versioned 10–12-pseudocut preset; initially one Tia Juana Light reduction |
| Feed molar rate | Positive `kmol/h`, converted once to SI internally |
| Feed temperature | `°C` in the GUI, converted to `K`; feed pressure equals column pressure |
| Feed stage | Main-column equilibrium stage receiving the feed |
| Stage count | Number of theoretical main-column equilibrium trays, numbered top-to-bottom |
| Reboiler duty | Positive admitted heat in `MW`; partial equilibrium reboiler |
| Reflux ratio | Molar `L_reflux / D` |
| Reflux condition | Saturated liquid by default; an optional temperature means explicit condensate subcooling |
| Side draws | Zero or more rows containing stage and positive liquid molar rate; the server assigns stable `SIDE_n` stream IDs |

The total condenser and partial reboiler are not included in the entered main-column stage count. All side draws in this milestone are direct liquid withdrawals. Top and bottom product rates and condenser duty are calculated outputs and must not also be fixed by the player.

### 2.2 Fixed PoC assumptions

- uniform column pressure of 2.5 bar, displayed read-only;
- one feed, adiabatic theoretical equilibrium trays, no pressure drop, and an implicit 100% equilibrium-stage efficiency;
- Peng–Robinson hydrocarbon vapour/liquid equilibrium;
- phase-consistent piecewise caloric data and enthalpy balances;
- total condenser and partial equilibrium reboiler;
- no side strippers, pumparounds, stripping steam, water boot, hydraulics, heat loss, tray holdup, startup, shutdown, or control dynamics;
- bounded stage, component, and side-draw counts; provisional limits are 64 main stages, 12 pseudocuts, and 6 side draws until profiling justifies a change.

Reflux temperature is not an independent equilibrium specification when saturated reflux is selected. If the optional field is enabled, it is the fully liquid condensate outlet temperature after subcooling; condenser-plus-subcooler duty is then calculated, and a non-liquid requested state is rejected.

### 2.3 Required outputs

For the top product, each side draw, and the bottom product, return:

- server-generated stream ID, optional sanitized display label, and whether its rate was specified or calculated;
- molar and mass flow;
- temperature, pressure, and phase;
- mole fractions and mass fractions for every pseudocut;
- boiling-range labels and derived `T5`, `T50`, and `T95`.

The deterministic scientific result also contains stage temperature, liquid-flow, and vapour-flow profiles; calculated condenser duty; iteration and property-evaluation counts; component-material, overall-material, energy, equilibrium, and composition-summation residuals; model and dataset revisions; warnings; and a result digest.

Request ID, queue time, wall time, worker CPU time, host/executor details, and log-suppression counters belong to a separate immutable execution-telemetry envelope. They may be shown beside the scientific result but are excluded from its deterministic digest and are not persisted as scientific state.

The normal GUI may place profiles and residuals behind a diagnostics tab, but they remain part of the authoritative result.

## 3. Console-output contract

Use the mod's SLF4J logger rather than `System.out.println`. Logging consumes an immutable terminal `SolveOutcome`. A successful outcome is logged only after its server-thread commit, so its GUI and console products cannot differ; rejected, failed, timed-out, and stale outcomes are logged after server-side resolution without pretending that they committed a scientific result.

During the PoC, every admitted request produces a bounded terminal report:

1. exactly one terminal summary containing opaque request/column IDs, status, model and dataset revisions, problem size/degree-of-freedom information available at that phase, timings, and warning/fault codes; unavailable fields use a stable `na` representation rather than disappearing unpredictably;
2. a successful committed summary uses `INFO` and additionally includes initialization mode, iterations/evaluations, every convergence-residual family, top and bottom flows, calculated condenser duty, stage-temperature range, and result digest, followed by one bounded product row per stream containing flow, temperature, `T5/T50/T95`, and the complete 10–12-cut composition vector;
3. an ordinary input rejection uses a rate-limited `INFO` fault without a stack trace; expected nonconvergence, infeasibility after valid degree-of-freedom closure, property-range failure, deadline, queue expiry, or stale discard uses one `WARN`; field/stage/component context is included when one specific offender exists, otherwise include the limiting residual family, maximum residual, iterations, and deadline state;
4. an unexpected invariant violation uses one `ERROR` terminal record with the request/model IDs and a rate-limited stack trace.

Product rows default to `INFO` in the Milestone-1 release and acceptance configuration so that the requested results are visibly printed to the console. An administrator may configure them as `INFO`, `DEBUG`, or `OFF`; a later production release is expected to default to `DEBUG`. Acceptance of the console-composition feature is tested with `INFO` enabled. Iteration histories, stage arrays, K-values, fugacity coefficients, cubic roots, Jacobians, and line-search traces are never printed at `INFO` and require an explicitly enabled, bounded `TRACE` diagnostic.

Illustrative schema with synthetic values, not a reproduction of the paper:

```text
[CreateChemE] column_calc request=7f31 column=42 status=CONVERGED model=mesh-v0 data=tiajuana-12@a81c comps=12 stages=30 feed_stage=24 sides=3 dof=0 init=warm iter=11 evals=824 queue_ms=2.1 wall_ms=34.2 cpu_ms=29.8 comp_mb_max=2.1e-10 overall_mb_rel=1.3e-10 eb_rel=4.7e-8 vle_max=8.0e-8 sum_max=2.0e-12 top_kmol_h=300.0 bottom_kmol_h=400.0 qcond_MW=-30.0 Tmin_K=341.2 Tmax_K=648.5 result=91bf warnings=[]
[CreateChemE] column_product request=7f31 stream=TOP rate_kmol_h=300.0 T_K=350.0 T5_C=10.0 T50_C=80.0 T95_C=140.0 x={cut01:0.410,...,cut12:0.000} x_sum=1.000000
```

Log startup records for the scientific schema/dataset fingerprint and counts, solver version, exact dependency versions, and executor worker/queue/deadline configuration. Log a bounded shutdown summary with submitted, committed, failed, stale, rejected, and timed-out totals.

Never log raw packets or NBT, player IP/UUID, world seed, player-entered control characters, exact base coordinates at `INFO`, or unrestricted custom-assay contents. Stream IDs are server generated. Any optional display label is length-bounded and control-character-sanitized for GUI, packet, persistence, and logging use; it is not part of scientific identity. Click throttling, one active request per block, and repeated-fault suppression must prevent console flooding.

## 4. Paper model versus PoC

Every benchmark report must contain the comparison rather than presenting fitted output as validation.

| Aspect | Chen Case 6.1 source model | Milestone-1 in-game model | Consequence |
|---|---|---|---|
| Property package | Peng–Robinson in HYSYS | Peng–Robinson in the Java scientific core | Same EOS family, independently implemented data and numerics |
| Feed representation | 25 HYSYS pseudocomponents | 10–12 generated/regressed pseudocuts | Product tails and overlap lose resolution |
| Numerical topology | Four coupled equivalent columns, 59 stages across main/stripper sections | One bare main column with player-selected theoretical stages | It is not a topology reproduction |
| Side strippers | Three | None; direct liquid draws only | Side products retain more light material |
| Pumparounds | Three | None | Temperature profile and duties will differ |
| Stripping steam | Present | None | Hydrocarbon partial pressures and light-end removal differ |
| Pressure | 2.5 bar | Fixed 2.5 bar | Directly comparable assumption |
| Product draw rates | Source-case specifications/results | Side rates are inputs; top/bottom rates are predictions | Specified side rates are not validation results |

Each benchmark datum is labelled `INPUT`, `CALIBRATION`, or `HELD_OUT_PREDICTION`. The first PoC is accepted on equation closure, robustness, and transparent comparison; it is not accepted merely because a fitted product slate resembles the paper.

## 5. Architecture and request lifecycle

The numerical code has no Minecraft, NeoForge, Create, JEI, or KubeJS imports. Its public entry point is a synchronous pure function for tests:

```text
ColumnResult solve(ColumnInput input, ScientificDataset dataset, InitialGuess guess)
```

Minecraft invokes that function through this lifecycle:

```text
client draft
    → Calculate C2S payload
    → server menu/block/range/schema/DoF validation
    → immutable canonical-SI snapshot
    → one bounded server-owned worker
    → immutable terminal SolveOutcome
    → server-thread revision check and atomic commit
    → console report + result S2C payload
```

Only the server may calculate or commit an authoritative result. One block may have at most one in-flight request. Milestone 1 uses one FIFO worker and an `ArrayBlockingQueue` with configurable capacity, initially eight jobs per server. Queue overflow is rejected immediately as `BUSY_QUEUE_FULL`; jobs are never run on the caller thread, and a queued request exceeding its configured wait limit expires visibly. The worker never reads a `Level`, block entity, capability, registry, menu, packet, or Create object. A result commits only if the block identity, input revision, dataset revision, and job token still match. A rejected, timed-out, failed, unloaded, destroyed, replaced, or stale job leaves the previous valid result intact and visibly stale.

The block entity persists the last accepted canonical input, input hash/revision, dataset fingerprint, solver version, last successful deterministic scientific result, and result-input hash. It does not persist request IDs, host timings, executor telemetry, futures, worker state, dense internal indices, or `CALCULATING`; interrupted work becomes idle after reload.

## 6. Dependency baseline and integration scope

The initial compatibility candidate is Java 21 and NeoForge 21.1.219 with the following exact development artifacts:

- Create `com.simibubi.create:create-1.21.1:6.0.10-280:slim` with `transitive = false`, corresponding to production Create 6.0.10;
- Ponder `net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1`;
- Flywheel compile API `dev.engine-room.flywheel:flywheel-neoforge-api-1.21.1:1.0.6` and runtime `dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6`;
- Registrate `com.tterrag.registrate:Registrate:MC1.21-1.3.0+67`;
- JEI common/NeoForge compile APIs and local client runtime at 19.25.0.322, without publishing the full JEI jar transitively;
- KubeJS `dev.latvian.mods:kubejs-neoforge:2101.7.2-build.368`; its POM supplies Rhino `2101.2.7-build.81` and runtime helpers transitively.

NeoForge 21.1.219 is a compatibility-test candidate, not a claimed universal minimum for every future dependency version. This set becomes a lock only after client, data-generation, and dedicated-server smoke tests pass together.

Production metadata treats Create as required on both sides with range `[6.0.10,6.1.0)`. KubeJS is required on both sides while scripted preset authoring remains part of this milestone contract. JEI is included in the development/test pack but remains an optional client-side production integration because neither the scientific solve nor server gameplay requires a recipe viewer.

- **Create:** required compatibility and optional screen styling; the stateful calculator block is non-movable. Kinetic power, tanks, pipes, and process operation are outside this milestone.
- **JEI:** show the calculator block and named case/feed presets. Arbitrary continuous calculations are not authored JEI recipes.
- **KubeJS:** add or replace validated named feed/case presets through a narrow data contract. Scripts cannot inject arbitrary runtime equations in this milestone.

All three adapters remain outside the scientific core. JEI and screen classes are client-safe, and the dedicated server must start without loading them.

## 7. Dependency-ordered work plan

### Gate 0 — Freeze the executable contract

- reconcile this plan with the scientific, benchmark, and architecture documents;
- define units, ranges, stage numbering, condenser/reboiler conventions, error codes, schema versions, and benchmark labels;
- define immutable `ColumnInput`, `SideDrawSpec`, `ProductStream`, `ColumnResult`, and diagnostics records.

**Exit:** one serialized case describes every accepted GUI submission without hidden degrees of freedom.

### Gate 1 — Bootstrap and dependency lock

- create the Java 21 NeoForge 1.21.1 Gradle project;
- pin the candidate Create/JEI/KubeJS artifacts and required supporting libraries;
- establish common, client, data-generation, game-test, and dedicated-server runs;
- add license, formatting, unit-test, and CI foundations.

**Exit:** clean checkout builds and launches client plus dedicated server with the complete dependency set.

### Gate 2 — Scientific dataset

- compile the 10–12-cut Tia Juana assay with provenance and validity metadata;
- generate and validate missing `MW`, density/SG, `Tc`, `Pc`, acentric factor, caloric, phase, and binary-interaction records;
- implement deterministic JSON/codecs, normalization, fingerprints, and rejection reports.

**Exit:** the dataset passes schema, range, continuity, bulk-assay, and deterministic-fingerprint tests.

### Gate 3 — Thermodynamic property kernel

- implement Peng–Robinson roots, mixing, fugacity, stability/phase selection, and TP/PH flashes;
- implement phase-consistent enthalpy using piecewise caloric data and EOS departure terms;
- preallocate hot-path workspaces and return typed property faults.

**Exit:** pure-component, binary, multipseudocut flash, enthalpy, and conservation fixtures pass independently of Minecraft.

### Gate 4 — Steady column solver

- implement MESH component-material, equilibrium, summation, and energy equations;
- add feed, total-condenser, saturated/subcooled reflux, partial-reboiler, and direct-liquid-side-draw boundaries;
- add deterministic initialization, warm start, damping, positivity guards, iteration/deadline caps, and scaled residuals;
- calculate top/bottom flows and condenser duty rather than accepting them as extra specifications.

**Exit:** simple columns and the default crude case converge conservatively; impossible specifications fail diagnostically without publishing partial output.

### Gate 5 — Scientific validation and performance baseline

- verify component, total material, energy, equilibrium, and summation closure;
- run deterministic repeats and feasible parameter sweeps over feed temperature/stage, tray count, reflux, duty, and side draws;
- build a separately implemented 25-cut **bare-main-column oracle with the same condenser/reboiler/direct-draw topology** to isolate pseudocut and numerical error, and record the mandatory paper-versus-game topology comparison separately;
- measure cold/warm p50/p95/p99 wall time, CPU time, allocation, iteration count, and failure envelope.

**Exit:** authored tolerances and provisional latency/deadline limits are justified by archived results rather than the property-kernel microbenchmark. A future second oracle may reproduce the source-like side-stripper/pumparound topology, but its difference from the bare PoC is reported as topology error rather than pseudocut-reduction error.

### Gate 6 — Placeholder block and GUI

- register the block, item, block entity, menu, screen, translations, model, and texture;
- implement validated numeric editors, assay selection, feed-stage control, and a bounded side-draw table;
- implement product composition, flow, TBP, profile, diagnostic, stale-result, and failure views using a dummy result before solver integration.

**Exit:** the complete client interaction works without any calculation running per tick.

### Gate 7 — Server calculation, persistence, and console output

- implement bounded C2S/S2C payloads and server-side authority checks;
- add the single bounded executor, one-job-per-block rule, deadlines, cancellation checks, and revision-checked commit;
- persist inputs and the last valid result with migration/version fields;
- implement the structured summary and full per-product console reporter defined in section 3;
- test unload, destruction, replacement, save/reload, queue-full, timeout, stale result, malformed payload, NaN, and click-spam cases.

**Exit:** one click creates exactly one safe server job, one terminal console report, and at most one authoritative GUI update.

### Gate 8 — Create, JEI, and KubeJS adapters

- mark the calculator as non-movable by Create and complete compatibility/styling work;
- add a JEI information/case-preset view without treating calculated results as fixed recipes;
- expose the narrow KubeJS case-preset authoring path with validation and reload tests;
- run client, integrated-server, and dedicated-server dependency smoke tests.

**Exit:** all three integrations work through isolated adapters and cannot change the solver's scientific authority.

### Gate 9 — PoC release gate

- complete the scientific and game acceptance suites below;
- package reproducible artifacts and record exact dependency/dataset/solver revisions;
- update the benchmark report with measured PoC results and known deviations;
- document controls, units, failure messages, and the explicit absence of side strippers and pumparounds.

**Exit:** a fresh world can place the block, calculate the default and edited cases, inspect matching GUI/console results, survive reload, and reproduce the documented benchmark within the declared model limits.

Gates 2–5 form the scientific critical path. The dummy GUI in Gate 6 may proceed after Gate 0 while the scientific path is being implemented. Gate 7 joins them; Gate 8 must not delay the headless solver.

## 8. Acceptance criteria

Scientific acceptance:

- every successful flow and composition is finite and nonnegative;
- every product composition sums to one within the authored tolerance;
- scaled component-material, overall-material, energy, equilibrium, and summation residuals pass declared limits;
- repeated solves of the same model/dataset/input are deterministic within the declared numerical policy;
- invalid, over-specified, under-specified, phase-incompatible, property-out-of-range, infeasible, and nonconvergent cases return stable diagnostics rather than plausible-looking output;
- every benchmark includes the paper-versus-PoC table and labels inputs, calibration targets, and held-out predictions.

Game acceptance:

- placing or ticking the block performs no solve;
- editing fields performs no solve;
- one Calculate click admits no more than one server job;
- forged, remote, oversized, stale, NaN, infinite, and out-of-range requests are rejected;
- the client never supplies authoritative results;
- unload, destruction, replacement, data revision, and concurrent editing cannot produce a stale commit;
- input and last-valid-result persistence round-trip without reinterpretation;
- GUI and console carry the same request ID and, for a committed success, the same result digest, flows, and compositions;
- every admitted request has exactly one terminal summary; with the Milestone-1 acceptance configuration enabled, successful requests also print every bounded product composition;
- click spam and repeated failures cannot flood the worker queue or server console;
- client, data generation, integrated server, and dedicated server pass with the pinned dependencies.

## 9. Explicitly deferred work

This milestone does not implement a fluid capability, physical feed/output inventory, Create kinetic power, a multiblock tower, continuous updates, adaptive cadence, tray dynamics, control loops, pumparounds, side strippers, steam/water handling, pressure drop, tray hydraulics, configurable/nonideal stage efficiency, startup/shutdown, reactions, crude-to-glycol processing, or general refinery networks.

The headless property and column solver created here is intentionally reusable. The next fidelity step is side strippers and pumparounds; the next gameplay step is replacing numeric stream fields with the mixture-fluid and process-equipment boundary once the calculator is scientifically validated.

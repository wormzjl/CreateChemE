# Fluid network implementation review: solver time, RAM, trace truncation

Reviewed: 2026-09-16. Snapshot: `154007dc1f68574634cc0ef64ba66f132d83e2e3` on `codex/simulation-fluid-network`
(worktree `run/codex-worktrees/simulation-fluid-network`). Handoff: `FLUID_NETWORK_REVIEW_HANDOFF.md` in that worktree.
Design and design review: gitignored `design/SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN.md`,
`documentation/SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN_REVIEW.md`.

Scope requested: (1) review the implementation result, (2) find methods to reduce solver wall time and RAM,
(3) decide whether a phase-specific trace truncation can be implemented, compatible with the column solver's
input calculation and its thermo engine (consolidation welcome).

Evidence produced for this review (no production code changed):

- `build/reports/fluid/analysis-claude/CPU_ALLOC_PROFILE.md` (Codex worktree): JFR CPU and allocation analysis of
  the preserved stress runs (`memory-candidate-4g-r01`, 12 workers, 100 islands; cross-checked on
  `stress100-final-12cap-long-r01`) plus `report.json` statistics for all profiles. Scripts beside it.
- `.claude/worktrees/fluid-perf-probe/build/probe/PROBE_RESULTS.md` (throwaway worktree, branch
  `claude/fluid-perf-probe` from 154007d, `// PROBE` edits guarded by `ProbeStats.ENABLED=false`, uncommitted;
  113 science.fluid tests green with probes off): instrumented single-thread replays of saved islands
  (quiescent 30-reservoir / 45-pipe island 11312 from the RAM run, the same island cold from
  `stress100-baseline-auto-r01`, and the 100-reservoir chain of `FluidNetworkBenchmarkTest`), a step-control
  experiment, unit costs, a trace census, fill and reuse counts.

## 1. Verdict

The implementation is sound as an engine (conservation, ownership, scheduling, persistence hold up in the code
read and in the evidence), and the design gate is met on its own fixture: the quiet 100-reservoir / 1000-pipe
island solves a 5 s interval in 112 ms median, 167 ms p95, on two workers. What is expensive is the work per
interval and the transient tail, and the two measurements agree on what to change even though they disagree on
the split:

| Where worker time goes | JFR, stress run, 12 workers | In-process timers, one thread |
|---|---:|---:|
| Sparse LU factorization + triangular solves + RCM | 66.5% | 18-28% |
| Property evaluation (PR78, water, viscosity) + assembly | 28% | 60-67% (node `state()` alone 30-39%) |

The single-thread numbers are exact counters; the JFR split is what the production pool actually experiences
with 12 workers on 8 cores, and the most likely reason for the gap is memory-hierarchy contention on the
latency-bound sparse kernels (each factorization walks ~200k nonzeros plus workspaces per worker, the property
kernels work on 47-element vectors). That has to be settled by one run of the server benchmark with the probe
counters enabled next to JFR (section 3.5); the ranking below is chosen to be right under both readings.

What the counters establish beyond doubt:

- A quiet 5 s interval on a 30-reservoir island costs 52 ms single-threaded, of which one colored-difference
  Jacobian build (191 residual evaluations + one 6.4 ms factorization) is 55-60%. Every interval rebuilds it,
  because every job constructs a new solver and every interval restarts the step size at 1 s (three substeps,
  ten coupled nonlinear solves, seven Newton iterations in total).
- Inside a residual evaluation the single most expensive primitive is the **free-water liquid property**:
  `waterLiquid(T,P)` (IF97 Region 1 through a thread-local context) is 2.34 us of a 4.4 us node `state()`, more
  than both PR78 evaluations together (1.5 us).
- The transient tail (islands >= 21 reservoirs escalating to 28-35 substeps and hitting the 2 s wall, the
  `module` profile at 12-15 substeps) is entirely the pipe flow-error term of the step controller. Removing it
  takes the cold island from 51 substeps / 1760 ms to 3 / 178 ms, but it guards the flow integral the ledger
  consumes (average mass flows move by up to 153% on a pipe) while the states move by 1.6e-5 K. It must be
  made cheap, not removed.
- Allocation is 316 KB per residual evaluation and 62-104 MB per 5 s interval (2.9 GB/s across the pool); the
  live set after a full collection is 392 MB. The RAM problem is garbage throughput, not retained state.
- Phase-specific trace truncation is implementable exactly on V3's support model and is worth 13-19% of the
  node block (47 -> 41 unknowns at a 1e-6 cutoff, 38 at 1e-4); modest, and third-tier after the items above.

Recommended order (section 7): keep the solver and its step size per island across intervals and stop doing a
nonlinear solve for the error estimate (A1-A4; about 6x on quiet intervals), trim the always-passing backward
error check (B1), fix the water property path and the copy churn (C1-C2), then make transients cheap (A5), then
the linear algebra and the kernel consolidation, then truncation.

## 2. Anatomy of one island solve (what the code actually does)

All paths under `src/main/java/com/wormzjl/createcheme/`.

- **Job.** `ProcessSolveServices.FluidIslandCommand.solve` constructs a **new** `PassiveIntervalSolver(model)` per job
  (`runtime/ProcessSolveServices.java:365`). That object owns a new `TrBdf2StepSolver`, which owns two new
  `PassiveStepSolver`s, each with empty `workspaces`/`structures` caches and an empty `endpointRates` cache.
- **Interval.** `PassiveIntervalSolver.integrate` starts with `InventoryEquilibrium.refresh`, then adaptive
  substeps: **initial step 1 s** (every interval), maximum 20 s, relative tolerance 1e-3, growth factor at most 2
  per accepted step, embedded error = max(state error, pipe flow error, boundary error). A quiet 5 s interval
  therefore always takes steps of 1, 2, 2 s: three substeps.
- **Substep.** `TrBdf2StepSolver.integrate` runs, per substep: one algebraic rate solve on the port graph (cached by
  endpoint identity, but the cache dies with the job), implicit stage 1, implicit stage 2, and, for the embedded
  estimate, a **third full nonlinear implicit solve** (`corrected`) that filters the order-3 companion defect.
  Measured: 10 implicit solves per quiet interval (3 substeps), 199 per cold interval (66 attempts).
- **Implicit solve.** `PassiveStepSolver.solve` runs an outer active-set loop (phase regime per node, device mode
  per edge, boundary closure). Each pass builds a fresh `Equations` object (new `PhaseLayout` per node, a new
  sparsity pattern via `BitSet`s, a `WorkspaceKey` from lists and strings) and calls `SparseNewton.solve`.
- **Unknowns.** Per reservoir (`PhaseLayout`): for every component present in the island mask, 2 unknowns when both
  hydrocarbon phases are active (total amount and ln(v/l)), plus water liquid, water vapor, ln T, ln P, ln Pc:
  47 for the 22-component gameplay basis, measured identical on all 48 census nodes (no component is ever
  exactly zero in a phase, so the mask path never shrinks it). The Jacobian has a dense 47 x 47 block per
  reservoir, dense donor-to-receiver blocks along every flowing pipe, one mass-flow unknown per pipe and one
  actuator unknown per pump or valve; n = 1455 for the 30-reservoir island, 4799 for the 100-chain.
- **Residual.** `Equations.residual` decodes every node whose slice changed (`states(x)` caches by
  `Arrays.mismatch`; 9.5 nodes per colored evaluation, all 30 for a full one), each decode calling
  `FluidThermodynamics.state` (liquid PR at the 2 MPa reference + `GlobalLiquidResponse`, vapor PR at Pc, IF97
  Region 1 free water, Wagner saturation, Shomate vapor enthalpy) and then a `Transport` record (liquid, vapor and
  water viscosities, acoustic velocity bound). One residual evaluation: 110 us average, 285 us when all nodes
  decode; 316 KB allocated.
- **Jacobian.** `SparseNewton.differentiate` uses colored one-sided finite differences over the whole island:
  **191 colors on both the 20-node and the 30-node island** (set by the 47-unknown block times one plus the node
  degree, not by island size), so 191 residual evaluations per build, 18.5-22 ms, then a numeric LU. Modified
  Newton reuse is good: `forkPreconditioner` carries the factorization across the TR-BDF2 stages and substeps
  (0.10 factorizations per implicit solve quiet, 0.27 cold), and the line search never backtracked.
- **Linear algebra.** `SparseLuSolver.Factorization` builds a new EJML `DMatrixSparseCSC` and a new
  `LinearSolverSparse` per factorization (EJML `LuUpLooking_DSCC`: per-column DFS reach + sparse column solve;
  6.4 ms at n = 1455, 8.7 ms at n = 4799, fill 2.9x on the ladder and 1.6x on the chain, i.e. 1.1e7-2.4e7
  nnz/s), with a hand-written reverse Cuthill-McKee ordering (EJML 0.44 ships no fill-reducing ordering:
  `FillReducing` is `NONE`/`RANDOM`/`IDENTITY`), row equilibration, a full residual check (`checkResidual`, one
  sparse mat-vec) after **every** solve and up to four refinement steps (never triggered in 1990 checks).
- **After Newton.** `ConservativeTransport.reconstruct` (13 calls per quiet interval, each with its own fresh RCM
  ordering and factorization), a full residual re-evaluation at the reconstructed point, `checkConservation`,
  `PipeTransfer.sample`.
- **Fallback.** On the soft budget an `APPROXIMATE` solve reruns the same integrator with tolerance 1e-6 and a
  trust probe (`checkApproximation` = `estimateCorrection`, one more Jacobian). It never fired in the RAM runs.
- **Flash.** `flashTP` is 0.0% of CPU, 0 bytes and 0 calls in every measured interval; `phaseCorrection` ran 20
  times per quiet interval and short-circuited every time. Its 87 us cost is latent (phase appearance, Newton
  failure), not paid.

## 3. Where the time and the memory go (measured)

### 3.1 Quiet 5 s interval, 30-reservoir island, one thread (mean of 5 measured intervals)

| Metric | Value | Metric | Value |
|---|---:|---|---:|
| wall ms (uninstrumented / instrumented) | 51.2 / 52.7 | node `state()` calls / ms | 2748 / 15.7 |
| substeps accepted / rejected | 3 / 0 (h = 1, 2, 2 s) | node decodes | 2528 |
| implicit solves / active-set passes | 10 / 10 | `temperatureTerms` calls / ms | 822 / 1.8 |
| Newton solves / iterations | 10 / 7 | `flashTP` calls | 0 |
| residual evaluations total / inside Jacobian | 246 / 229 | `reconstruct` calls / ms | 13 / 3.2 |
| residual ms total | 27.0 | LU factorizations (Newton) / ms | 1.2 / 9.1 |
| Jacobian builds / ms excluding LU | 1.2 / 22.2 | LU solves (Newton) / ms | 7 / 2.1 |
| colors per Jacobian | 191 | RCM orderings / ms | 14 / 1.5-6.6 |
| n / nnz(A) / nnz(L+U) | 1455 / 70.6k / 208k | allocated MB per interval | 74 (62-104) |
| dominant error term per attempt | state (2e-10..5e-10); pipe term exactly 0 | KB allocated per residual evaluation | 316 |

Cost model (counts x unit costs, 83% accounted): residual evaluation 51% (of which `state()` 30%, viscosity
built with the decode ~13%, `temperatureTerms` 3%), LU factorization 17%, `reconstruct` 6%, LU solves 4%,
orderings 3%, `Equations` construction and sparsity 2%.

### 3.2 Unit costs (median, one thread, after warm-up)

| Kernel | ns | Kernel | ns |
|---|---:|---|---:|
| `state(...)` with prepared terms / without | 4400 / 5600 | **`waterLiquid(T,P)`** (IF97 Region 1 via `MaterialRuntime.with`) | **2338** |
| `hydrocarbon.phase` LIQUID (PR at 2 MPa + liquid response) | 800 | `saturationPressure(T)` | 450 |
| `hydrocarbon.phase` VAPOR (PR at Pc) | 700 | `vaporWaterEnthalpy(T)` | 200 |
| `temperatureTerms(T)` | 1400 | `viscosity.liquid` / `.vapor` / `.waterLiquid` | 900 / 1700 / 75 |
| `PipeResistance.evaluate` | 64 | `flashTP` | 86,700 |
| full `Equations.residual` (island) | 110,000 | one Jacobian build (191 evaluations) | 18,500,000 |
| LU factorization n = 1455 / 4799 | 6,400,000 / 8,720,000 | one RHS solve incl. check, n = 1455 / 4799 | 148,000 / 393,000 |

Four primitives explain 92% of a `state()` call, and `waterLiquid` alone is 53% of it. The viscosity `Transport`
record adds another 2.7 us per decoded node outside the `state()` timer.

### 3.3 Transient intervals (one thread)

| Case | wall ms | attempts acc/rej | implicit solves | Newton iterations | Jacobian builds | residual evals | linear algebra share | residual share |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| cold 30-reservoir island, 5 s | 2097 | 51 / 15 | 199 | 857 | 54 | 11,391 | 27.8% | 60.1% (`state()` 29.5%) |
| 100-reservoir chain, 5 s (cos-pressure start) | 3900 | 47 / 31 | | | | | 18.1% | 67.4% (`state()` 38.6%) |

100% of rejections at every size are "Embedded pipe error". The design-gate fixture (`one` profile: gentle
pressure gradient, 3 substeps, 112 ms) and the benchmark-test chain (1000 Pa cosine pressure swing, 47/31
substeps, 3.8 s) are the same size; the gate passes because its fixture is quiet.

### 3.4 Step-control experiment, cold island, one 5 s interval from the same state

| Variant | acc / rej | warm ms | max dP/P | max dT (K) | max dm/m | max d(phase vol. frac.) | max d(avg flow)/max(q, 1e-6) |
|---|---:|---:|---:|---:|---:|---:|---:|
| (i) defaults (rtol 1e-3) | 51 / 15 | 1760 | | | | | |
| (ii) rtol 1e-2 | 32 / 16 | 1500 | 2.7e-7 | 3.7e-6 | 3.9e-7 | 1.7e-8 | 5.1e-4 |
| (iii) pipe term excluded | 3 / 0 | 178 | 1.2e-6 | 1.6e-5 | 1.7e-6 | 7.5e-8 | **1.527** |
| (iv) STEP_DOUBLING | 68 / 14 | 2937 | 2.5e-8 | 3.5e-7 | 3.6e-8 | 1.6e-9 | 3.3e-4 |

The pipe term is the whole step budget on transients, and it is not a near-zero-rung artefact: of 34
pipe-dominated attempts the argmax edge is spread over at least 8 pipes carrying 0.05% to 80% of the maximum
flow (the most frequent offender carries 37%), on a real mesh (cyclomatic number 14, two bridges). The
criterion already normalises by each edge's own gross throughput and subtracts an absolute floor. What it
protects is the flow integral (`averageMassFlows`, consumed by the transport/module ledger and the GUI), not
the state.

### 3.5 CPU under the production pool (JFR, stress run, 12 workers, 8836 worker samples)

| Share | Exclusive bucket (first match walking the stack top-down) |
|---:|---|
| 42.3% | `SparseLuSolver$Factorization.<init>` (EJML numeric LU: `searchNzRowsInX_DFS` 17.5%, `solveColB` 16.2%, `performLU` 2.6%) |
| 21.3% | `Factorization.solveMultiple` / `solve` / `checkResidual` (`solveU` 13.5%, `checkResidual` 5.4%, `solveL` 1.9%) |
| 8.2% | `TranslatedPengRobinson.evaluate` / `normalize` |
| 7.4% | `WaterRegion1` / `V3WaterProperties` / `MaterialRuntime` (`ImmutableCollections$MapN.probe` alone 3.1%) |
| 3.8% | `MixtureViscosity` / `ViscosityCorrelation` |
| 3.2% | `SparseNewton.differentiate` + `Pattern` own frames |
| 2.9% | RCM ordering |
| 1.9% + 1.6% + 1.5% + 1.4% | `Equations` assembly, `PhaseLayout`, `java.util.stream`, `PipeResistance` |
| 0.0% | `flashTP` |

Inclusive: `differentiate` 65.5% (= 42.2 pp LU + 18.1 pp finite-difference residuals + 5.3 pp own),
`Equations.residual` 24.7%, `FluidThermodynamics.state` 16.2%. Same shape on the older artifact (LU 43.7%,
solves 19.2%). Worker CPU was 2.92 cores for 16239 accepted simulated seconds in 120 s: 21.6 CPU-ms per
simulated second of a 20-reservoir island.

The in-process counters (3.1, 3.3) charge 18-28% to all linear algebra and 60-67% to residual evaluation on
the same code. Candidate explanations, none settled: (a) memory-hierarchy contention with 12 workers on 8
physical cores (a factorization touches 200k+ nonzeros plus EJML workspaces of a few MB; the property kernels
stay in L1; SMT pairs halve the throughput of latency-bound DFS/indirect-index code far more than of dense
arithmetic); (b) the profiled islands refreshing Jacobians more often than the replays (the replays never
backtracked); (c) top-frame attribution (unlikely: the bucketing was exclusive over whole stacks). The decisive
measurement is one server-benchmark run with the probe counters ported and enabled, compared against JFR on the
same run. Until then, items that reduce the *number* of factorizations and solves (group A) are certain wins
under both readings, and the choice between block linear algebra (B3) and residual work (group C) should follow
that measurement.

### 3.6 Interval statistics (`report.json`, stress run)

| Metric | Value |
|---|---|
| Worker ms per accepted 5 s interval, p50 / p95 / p99 / max | 55.4 / 119.1 / 258.5 / 1980 |
| Substeps per accepted interval, p50 / p95 / p99 / max | 3 / 3 / 8 / 35 |
| Worker ms p50, 10 vs 30 reservoirs | 30.4 vs 77.4 |
| Islands that hit the 2 s wall budget | every size >= 21 reservoirs; those intervals ran 28-35 substeps |
| Outcomes | 3443 FULL, 50 HELD round deadline, 1 HELD wall deadline, 0 APPROXIMATE |
| `one` profile gate (100 reservoirs / 1000 pipes, 2 workers, 200 intervals) | p50 112 ms, p95 167 ms, max 206 ms, 3 substeps, 0 rejections |
| `module` profile (2 x 50 reservoirs with buffered transfers, 2 workers) | p95 1.12-1.67 s, 12-15 substeps, 14-17% rejected substeps |
| ready -> publication p95 | 57 s (queueing behind the tail jobs, not solver time) |

### 3.7 RAM: allocation rate, not retained state

| Quantity | Value |
|---|---:|
| Sampled allocation weight, pool | 2.93 GB/s, 102 MB per accepted 5 s interval, 99.7% on workers |
| Allocation per worker, single-thread replay | 1.3-1.6 GB/s; 316 KB per residual evaluation against an 11.6 KB state vector |
| Heap used after GC: min / median / p95 / max | 392 MB / 1000 MB / 2857 MB / 3132 MB |
| GC pause total in 120 s | 1179 ms (482 pauses, p95 6.2 ms); GC CPU 3.2% of worker CPU |
| Collections triggered by `G1 Humongous Allocation` | 258 of 363 |

The after-GC minimum is the live set of 100 islands plus the server; the median and p95 are old-generation
garbage G1 leaves between mixed collections at this allocation rate, and 71% of the collections are forced by
humongous arrays (per-Jacobian CSC arrays, EJML L/U storage, dense transport right-hand sides at n >= 1455).

| Share of allocation | Category |
|---:|---|
| 16.3% | defensive record clones: `FluidThermodynamics$State.<init>/liquid()/vapor()`, `HydrocarbonModel$Phase.logFugacity()/<init>`, `TranslatedPengRobinson$Phase.*`, `GlobalLiquidResponse` |
| 14.3% | `Equations.residual` / `states` (f, targets, incoming arrays per evaluation) |
| 13.4% | `TranslatedPengRobinson.temperatureTerms` (three n x n matrices per distinct temperature; 822 calls per quiet interval) |
| 12.4% | EJML internals, of which `DMatrixSparseCSC.growMaxLength` 11.9% (10.3 pp from `LuUpLooking_DSCC.initialize`: L/U storage regrown on every decompose) |
| 7.3% | `SparseNewton.differentiate` / `evaluate` / `solve` (trial clones, derivative arrays) |
| 6.9% | `PhaseLayout.decode` / `residual` / `totalAmounts` / `encode` |
| 5.2% | `PipeTransfer` / `ConservativeTransport` / `PipeResistance` records |
| 4.7% | `TranslatedPengRobinson.evaluate` (normalize, rows, logPhi, partial volumes) |
| 4.0% | `SparseMatrix.<init>` (re-clones the three CSC arrays `numericMatrix` just built) |
| 3.8% | `SparseNewton$Pattern` (numericMatrix, symbolicMatrix, coloring) |
| 3.4% | `java.util.stream` objects from `Arrays.stream(...).sum()` |
| 1.2% | string building (`ApproximationAnchor.thermodynamicRevision` once per job: 1.0%) |
| 0.9% | `MaterialRuntime$Context` / `ThreadLocal` entries |

Types: 74% `double[]`, 10.5% `int[]`. Nothing here is retained. A `jcmd <pid> GC.class_histogram` after a
forced GC at the end of the stress fixture would pin the live set exactly and should be added to the benchmark.

## 4. Findings and optimization methods

Grouped by mechanism; within a group ordered by gain per effort. Effects refer to the measured interval
anatomy above.

### Group A. Do less work per interval

**A1. Carry the accepted step size (and the controller state) across intervals.** `Settings.initialStep = 1`
restarts every interval at h = 1 s with growth capped at 2 per step, so a quiet 5 s interval is always three
substeps (1, 2, 2 s) and ten implicit solves; on a transient it also pays the initial rejections again at every
interval boundary. Persist the last accepted h per island (bounded by the interval and `maximumStep`; reset on
topology/property revision, and start small, about 0.05 s, after a topology event or a hold). The error
controller keeps its authority; the P31 fixed-5/20 s references already show those step sizes inside tolerance
(max temperature error 1.8e-4 K at 20 s). Quiet interval: 10 implicit solves -> 4. Where:
`PassiveIntervalSolver.integrate` (accept a starting h), `IslandCoordinator.Island` (store it).

**A2. Retain the solver per island instead of per job.** `runtime/ProcessSolveServices.java:365` builds a new
`PassiveIntervalSolver` per job, discarding `PassiveStepSolver.workspaces`/`structures` (pattern, 191-colour
coloring, RCM ordering, the last factorization used as modified-Newton preconditioner) and
`TrBdf2StepSolver.endpointRates` (the rate at the previous endpoint, which is exactly the rate at the next
start). In a quiet interval the Jacobian build plus factorization is 55-60% of the wall time and the states
barely move between intervals, so with the previous factorization as preconditioner the chord iteration
converges without a rebuild. Keep one solver per island in the coordinator's `Island` and pass it through
`FluidIslandCommand`; the one-job-per-owner invariant already guarantees exclusive use. Replace the `Thread owner`
fields (`PassiveStepSolver`, `TrBdf2StepSolver`, `SparseNewton.Workspace`, `SparseLuSolver.Factorization`) with an
acquire/release latch set by the job and released in `finally`; invalidate on `revision` changes. Trajectory
identical. Expected quiet interval after A1+A2: ~10 ms instead of 52 ms.

**A3. Keep one factorization per substep (already largely true; protect it).** `forkPreconditioner` carries the
factorization across the two TR-BDF2 stages and the companion (0.10 factorizations per implicit solve quiet,
0.27 cold, 0.13 on the chain), because the stages share the diagonal coefficient. Two things erode it under
transients: the 0.8 contraction threshold in `SparseNewton.solve` and active-set passes that change the
`WorkspaceKey` (phase signature, modes). Measure the refresh reasons on the cold island before touching the
threshold; a rank-one (Broyden) update between refreshes is the standard next step if refreshes are frequent.

**A4. Make the embedded error estimate a linear filter.** `TrBdf2StepSolver.java:582-609` runs a complete
nonlinear `implicit.solve` on a perturbed inventory to filter the order-3 companion defect: one of the three
nonlinear solves per substep, with its own active-set pass and `reconstruct`. The standard TR-BDF2 estimate
applies `(I - gamma*h*J)^-1` once, as a triangular solve with the stage-2 factorization. Quiet interval after
A1+A2+A4: 3 implicit solves. The estimate changes character, so P31 references are re-qualified on tolerance;
nothing committed changes. (STEP_DOUBLING is not an alternative: measured 1.7x slower than the embedded
estimator for a 3.5e-7 K difference.)

**A5. Transients: make each attempt cheap and stop re-paying the start; do not drop the pipe term.** The pipe
flow-error term is the entire step budget on transients (section 3.4) and it guards the flow integral the
ledger consumes, so removing or loosening it trades ledger accuracy for speed (rtol 1e-2 buys only 15%). The
levers that keep the contract: A1 (no restart per interval, small start after events: the 15 rejections per
cold interval are the controller finding its step from h = 1 s), A2/A4 (fewer solves per attempt), C1-C3
(cheaper residuals), B1/B3 (cheaper linear algebra). Together they should take the cold island's 2.1 s toward
0.5 s and keep every island under the 2 s wall. Only if that is not enough: judge the flow error by its ledger
impact (transported mass h x dq relative to the endpoint inventory it moves, which is the quantity
`checkConservation` bounds) so that flow errors that cannot move any inventory by more than the BAL tolerance do
not force refinement; validate against the module-transfer references and the P10 reversal history before
adopting.

### Group B. Make each factorization and solve cheap

**B1. Trim the backward-error check and the refinement machinery.** `Factorization.solveMultiple` runs
`checkResidual` (a full sparse mat-vec) after every solve and refines on failure: 0 refinement passes in 1990
checks, while the check is 15% (quiet) to 57% (100-chain) of solve time and 5.4% of pool CPU. Check once per
accepted Newton step, or only when the Newton residual fails to contract, and drop the check for merit-probe
solves. Trajectory identical (the check never changed a result).

**B2. Stop regrowing and re-allocating the factorization.** Each `Factorization` allocates a new EJML solver whose
L/U storage grows through `growMaxLength` (11.9% of all allocation and the source of the humongous allocations
behind 71% of collections), plus `scaledValues`, `rowNorm`, workspaces and a re-cloned `SparseMatrix` (4%). Keep
the EJML solver and CSC buffers in the (per-island, after A2) workspace, pre-size L/U to the last nonzero count,
give `SparseMatrix` a trusted constructor, and use `setStructureLocked(true)` (present on `LinearSolverSparse` in
0.44) when the pattern is kept invariant, which requires `Pattern.numericMatrix` to keep the full structural
pattern with explicit zeros instead of dropping exact zeros. Also cache the `ConservativeTransport` matrix
ordering and structure per island (13 fresh RCM orderings and factorizations per quiet interval, 1.5-6.6 ms).

**B3. Block-structured factorization.** The matrix is dense node blocks (47 x 47 today, 38-41 after truncation)
coupled along the pipe graph; EJML's general up-looking LU spends its time in per-column DFS reach and sparse
column solves at an effective 1.1e7-2.4e7 nnz/s (6.4 ms for 70k nonzeros), where a supernodal/block LU with
dense block kernels would do the same arithmetic in well under a millisecond and would not suffer the
cache-miss profile that (hypothesis 3.5a) makes it 42% of pool CPU. Fill is already low (2.9x ladder, 1.6x
chain), so a better scalar ordering alone will not pay; the gain is in the kernels. Route: block LU over the
node graph with pivoting inside blocks, row equilibration and a single conditional refinement. Decide its
priority after the 3.5 measurement: first tier if the pool profile is confirmed, otherwise after group C.

**B4. Analytic node blocks instead of coloured finite differences.** After A1-A4 the remaining Jacobian builds
are 191 residual evaluations each (18-22 ms, 51% of a quiet interval today). `TranslatedPengRobinson.evaluate`
already computes partial molar volumes, dv/dT, dv/dP, cp and dh/dP; `V3PengRobinsonKernel.evaluateDerivatives`
computes d ln phi_i/dn_j and d ln phi_i/dT (the bundle V3 uses for its own analytic Jacobian). Staged: (i) block
finite differences (perturb one node's unknowns and re-evaluate only that node's rows and its incident edge
rows), which removes the whole-island assembly per colour and the colour count's dependence on node degree;
(ii) analytic node blocks through the consolidated kernel (section 6), keeping finite differences for the water
rows and as a test oracle. Verify each block against FD in unit tests, as V3 did.

### Group C. Make the residual cheap

**C1. The free-water liquid property is the most expensive primitive in the solver.** `waterLiquid(T,P)` is
2.34 us of a 4.4 us node `state()` (53%): `WaterRegion1.evaluate` computes 34 IF97 terms with up to six
`Math.pow` each, always at the fixed reference pressure (a function of T only), and it is reached through
`MaterialRuntime.with` (a `Context` allocation, a `ThreadLocal` set/remove) while `V3WaterProperties.data()`
resolves `MaterialRuntime.water()` (a `ThreadLocal.get` plus two immutable-map probes) on every coefficient
access, eight times per saturation call: `MapN.probe` is 3.1% of pool CPU by itself. Fix: pass the
`MaterialCatalog.Water` record into the fluid model once and add explicit-argument overloads in
`V3WaterProperties`; evaluate IF97 with incremental powers; keep the reference-pressure Region 1 result in the
per-node prepared-temperature state next to the PR terms (it is invariant across the composition and pressure
columns of the Jacobian). Expected: `state()` roughly halves; residual share drops by a quarter.

**C2. Defensive copies and streams inside the residual (16.3% + 6.9% + 3.4% of allocation).**
`FluidThermodynamics.State` clones on construction and on every `liquid()`/`vapor()` read;
`TranslatedPengRobinson.Phase` and `HydrocarbonModel.Phase` clone on construction and on `logFugacity()`;
`PhaseLayout.residual`/`equilibriumResidual`/`encode`/`totalAmounts` call those accessors repeatedly and use
`Arrays.stream(x).sum()`; `GlobalLiquidResponse.logFugacity` clones; `PipeResistance.Loss` records per section
per edge per evaluation; `Equations.residual` allocates f, targets and incoming per evaluation. Keep the public
copying API for callers outside the solver, add package-private no-copy views, build records with trusted
constructors from freshly allocated arrays, replace streams with loops, reuse the residual scratch in the
workspace. No numerical change; this is where the 316 KB per residual evaluation goes.

**C3. PR mixing: O(n^2) allocated matrices per temperature when the package supports an exact O(n) form.**
`temperatureTerms(T)` builds three n x n arrays per distinct temperature (13.4% of allocation, 822 calls per
quiet interval, recomputed per node whenever the node's ln T column is perturbed or the iterate moves T). With
classical mixing a_ij = sqrt(a_i a_j)(1 - k_ij) and the package `createcheme:tjl20_methane` has exactly 11
nonzero k_ij pairs (ethane to pentanes, |k| <= 0.06, `materials/interactions/tjl20.json`; nitrogen appended with
zero interactions), so with q_i = x_i sqrt(a_i): a = (sum q)^2 - 2 sum_pairs k_ij q_i q_j and the row sums
S_i = sqrt(a_i)(sum q - sum_{j: k_ij != 0} k_ij q_j); temperature derivatives from per-component
d sqrt(a_i)/dT and d^2 sqrt(a_i)/dT^2. Exact, allocation-free, O(n + pairs) per evaluation, 3n scalars per
temperature. This is the V3 kernel's `rankOneMixing` generalised by a sparse-pair correction (section 6).

**C4. Viscosity (2.7 us per decoded node, ~13% of a quiet interval).** `MixtureViscosity.vapor` runs the Wilke
double loop over all present components on every decode; the pure-component viscosities and Wilke weights at a
given T can be prepared once per temperature with the PR terms, and truncation shrinks the vapour basis.

**C5. Small items.** `Equations.buildSparsity`/`Pattern.<init>` use `BitSet`s and streams (once per structure
after A2); `PassiveStepSolver.solve` builds a string key per active-set pass;
`ApproximationAnchor.thermodynamicRevision` string building is 1% of allocation once per job and should be
cached per model.

### Group D. Shrink the system: phase-specific trace truncation (section 5)

Measured prize: 47 -> 41 unknowns per node at a 1e-6 cutoff (-12.8%), 38 at 1e-4 (-19.1%); colours, Jacobian
evaluations and n shrink in proportion, LU superlinearly. On the quiet island that is roughly 3.5 ms of 52.7 ms
at 1e-4 directly plus the smaller factorization; a second-tier gain, whose main value is consistency with the
column and better conditioning (the ln(v/l) unknowns at K = 3e-53 are the worst-scaled in the system).

## 5. Phase-specific trace truncation for the network: implementable, mirror V3's support model

### 5.1 Where the decision lives (not in the flash, not in the residual)

The network solve has no flash in its hot path (0 calls in every measured interval); "truncation during flash"
buys nothing here. The place is the Newton unknown set, and the rule is V3's: **support is derived once from a
full-basis reference state and frozen through Newton** (`V3FlashPhaseSupport.derive`,
`V3TruncationSupport.derive`), never from an in-flight iterate.

- Derive per node, per implicit solve, from the seed state's phase compositions: component i is `LIQUID_ONLY`
  when y_i < cutoff and x_i >= cutoff, `VAPOR_ONLY` symmetric, `BOTH` otherwise, `ABSENT` as today. The census
  says the omitted entries will be vapour-side heavy ends (8 components with y_i < 1e-4, 1 with x_i < 1e-4,
  none below 1e-6 on the liquid side).
- `PhaseLayout` takes the support array: a one-phase component gets one unknown (its amount in the present phase)
  and no equilibrium row; `decode` writes zero to the omitted phase; the support mask joins `phaseSignature` and
  the `WorkspaceKey`. `TranslatedPengRobinson.evaluate` already skips x_j = 0 columns.
- **Reactivation** is checked in `phaseCorrection` after convergence with V3's `omittedStillTrace` inequality:
  ln y_i^eq = ln x_i + ln phi_i^L - ln phi_i^V + ln(P/Pc) from the converged fugacity coefficients, which
  `evaluate` already fills for every i (infinite dilution for absent ones). If y_i^eq >= 10 x cutoff (V3's
  `FLOOR_REINSERTION_FACTOR` hysteresis) the component is promoted to `BOTH` with seed v_i = y_i^eq n_V and the
  active-set loop repeats (cycle guard exists). Demotion only at the next solve's seed.
- `flashTP` stays full basis; `V3TruncatedFlash` is not needed on this cold path.

### 5.2 Conservation, energy and volume bounds

- Material: exact; the omitted phase amount is identically zero. Transport, `PipeTransfer` and
  `ConservativeTransport` need no change (bulk withdrawal is per component total).
- Energy: bounded by sum_omitted n_i |h_i^V - h_i^L| <= cutoff x n_phase x max |delta h_vap|; at 1e-6 well inside
  the 1e-4 + 1e-6 x scale energy gate, at 1e-4 marginal. Cap the network cutoff at 1e-5 (V3 allows 1e-2 with its
  own explicit budgets), default 1e-6, 0 = exact off switch (identical numerical path, as V3's `OFF`).
- Volume: at most y_i of the vapour volume per omitted entry. Water unaffected (separate regime state).

### 5.3 Compatibility with the column solver input

- **Basis.** The network uses the catalog package's component order plus nitrogen plus water (water last); V3
  uses `V3ComponentBasis` in the package's declared order. Nitrogen exists only through the fluid-private
  `FluidMaterialCatalog.withNitrogen` extension; the column's registered package does not have it. Any
  network-to-column stream needs nitrogen registered in the shared catalog first (independent of truncation).
- **Policy type and semantics.** Reuse `V3TraceTruncationPolicy` (cutoff as mole fraction, 0 = off) and the
  `PointPhases` meaning of `V3TruncationSupport` (`LIQUID_ONLY` = "does not evaporate here", keeps its material
  balance, loses its equilibrium row). Move the policy record to `science/thermo` so neither package imports the
  other's internals.
- **Boundary content.** The module contract carries full-basis totals and energy; phase split is not part of the
  contract, so a truncated network state never feeds a reduced basis to `V3FeedFlash`; the column derives its
  own support from its own full-basis flash.
- **Reactivation criterion.** The same K inequality as V3, so the two engines' support decisions are consistent
  at their respective states.

### 5.4 Rollout

Cutoff 0 by default until groups A-C land and the P17/P31 references are re-baselined on tolerance; then measure
on the stress fixture at 1e-6; keep the exact off switch permanently.

## 6. Thermo engine consolidation

| Class | Used by | Allocation | Derivatives | Mixing |
|---|---|---|---|---|
| `science/thermo/PengRobinson78` | legacy TP/PH flash, `HydrocarbonModel` calibration | per call | none | dense O(n^2) |
| `science/column/v3/thermo/V3PengRobinsonKernel` (package-private) | V3 column | none (workspace) | d ln phi/dT, d ln phi/dn, H^R derivatives, refined roots | dense, rank-one when all k_ij = 0 |
| `science/fluid/thermo/TranslatedPengRobinson` | fluid network | per call + per-T matrices | dv/dT, dv/dP, d^2v/dT^2, cp, dh/dP, partial molar volumes | dense O(n^2) |

Proposal, each step independently testable:

1. Promote the V3 kernel to `science/thermo/PengRobinsonKernel` (public; pure move plus visibility; V3's façade
   unchanged) and add the sparse-pair mixing (C3) there, so V3 gets it too (the TJL packages have the same 11
   pairs).
2. Rebuild `TranslatedPengRobinson.evaluate` on the kernel: the kernel returns Z, ln phi_i, H^R, a, b, da/dT and
   the derivative bundle (dZ/dA, dZ/dB, d^2a/dT^2 pieces, S_i); the volumetric block (dP/dV, dP/dT, d^2v/dT^2,
   partial molar volumes) is the explicit algebra already in `evaluate`, applied to the kernel's terms; then the
   constant translation and `GlobalLiquidResponse` as today. `TemperatureTerms` disappears; the per-island
   workspace holds the prepared temperature (and, per C1, the prepared water state).
3. Expose d ln phi/dn and d ln phi/dT to `PhaseLayout` for the analytic node Jacobian (B4 ii).
4. Keep `PengRobinson78` as the test oracle only.

K-value consistency between the engines is a modelling difference, not a kernel difference: the network
evaluates the liquid at the 2 MPa reference and corrects by the exponential compressibility model (Poynting
with translated partial molar volumes), while V3 evaluates the liquid at P. The translation itself cancels in K
except for a c_i P_water/(R T) term (both phases carry P c_i/(R T): the liquid referenced at P_ref and corrected
by the integral to P, the vapor at P_c = P - P_water), below 1e-3 in ln K at these conditions. After
consolidation the engines share code and differ only where the design says they should. The ideal-gas enthalpy
datum (298.15 K, catalog cp polynomials) is already shared; water saturation and vapor enthalpy already come
from `V3WaterProperties`.

Regression policy: the handoff uses exact equality of the P31 canonical JSON as regression evidence. A1, A3
(threshold changes), A4, A5, C1, C3, B4, section 5 and section 6 change summation order or the accepted step
sequence, so the gate must become a tolerance gate (proposed: 1e-9 relative on states and flows for the pure
refactors C2/C3/B2, the existing accuracy references for A1/A4/A5/section 5) plus the unchanged conservation
audit. A2, B1 and B2 are trajectory-identical and keep the exact gate.

## 7. Recommended order and expected effect

| Step | Items | Expected effect | Trajectory |
|---|---|---|---|
| 1 | A1 step carry-over, A2 per-island solver retention, B1 check trimming | quiet 30-reservoir interval 52 ms -> ~10 ms (Jacobian rebuild gone, 10 -> 4 implicit solves); cold interval loses its 15 restart rejections; allocation per quiet interval drops with the Jacobian | A2/B1 identical; A1 tolerance |
| 2 | A4 linear error filter, B2 EJML reuse + structure lock + transport ordering cache | 4 -> 3 implicit solves per substep; humongous collections stop; -12% allocation | tolerance |
| 3 | C1 water path, C2 clones/streams/scratch, C5 | `state()` about halves, residual -25-30%, allocation per residual from 316 KB toward tens of KB | identical up to roundoff (C1 power reorganisation) |
| 4 | A5 transient handling (small start after events; optional ledger-impact criterion) | cold island 2.1 s -> ~0.5 s with steps 1-3; every island under the 2 s wall; module profile toward its median | tolerance, reference-tested |
| 5 | 3.5 decisive measurement, then B3 block LU (first if the pool profile is confirmed) | LU share reduced several-fold; scales to larger islands | identical up to roundoff |
| 6 | Section 6 kernel consolidation + C3, then B4 analytic blocks | remaining Jacobian builds from 191 residual evaluations to about one; `temperatureTerms` allocation gone | tolerance |
| 7 | Section 5 trace truncation (default 1e-6, cap 1e-5) | node block 47 -> 41; colours, n, vapour loops shrink in proportion | tolerance, bounded by cutoff |

Steps 1-3 need no new numerical method and no new science; they are the first order of magnitude on quiet
intervals and roughly 3x on transients.

## 8. Review of the handoff's RAM change (a5dedf6..154007d) on its own

Acceptable as an independent change. The volatile `TemperatureTerms` hint in `PhaseLayout.fixedInventory` is
read once into a local and compared on exact temperature, so a concurrent recompute cannot hand a caller a
mismatched set; `flashTP` computes the terms lazily once per constant-temperature flash; `State.componentCount()`
is a scalar read. Exact P31 equality holds by construction (no arithmetic changed). The measured 55% allocation
reduction is real, but the hint reuse does not reach the coupled solve, where the terms are still rebuilt per
node per temperature (822 calls per quiet interval, 13.4% of allocation), and the remaining sites are structural
(section 3.7).

## 9. Other observations from the code read (no action requested)

- `TrBdf2StepSolver.endpointRates` is keyed by `PassiveNetwork` record equality; `Reservoir` holds a `State`
  record whose `double[]` fields compare by reference, so the cache hits only when the exact `State` instances are
  reused. That is the intended case and works, but any path that rebuilds states (e.g.
  `InventoryEquilibrium.refresh` after a basis change) silently defeats it.
- `IslandCoordinator.closeRound` samples load per job (`performanceSample(cpuBound && wall >= soft, accept &&
  wall < soft/4)`); the handoff's own top open item (many short jobs read as low load while the pool is
  saturated) stands and does not change per-solve cost.
- Worker CPU/wall ratio p50 0.91: the solve is single-threaded and spends about 9% of its wall time
  descheduled; nothing inside the solver blocks.
- The `M2-network-scaling-screening.json` reference in the Codex worktree was produced before commit 154007d
  (different substep counts on the same fixture); it should be regenerated before being cited.

## 10. Open questions for the user

- Confirm that nitrogen should be registered in the shared material catalog (needed for any network-to-column
  stream, independent of truncation).
- Confirm the regression policy change from bit-identical P31 JSON to a tolerance gate; without it none of the
  arithmetic-changing items can land.
- Confirm the network trace cutoff cap (proposed 1e-5, default 1e-6, 0 = off).
- Whether to run the decisive pool-vs-single-thread measurement (3.5) before or after steps 1-3; it decides only
  the priority of B3, not the content of steps 1-3.

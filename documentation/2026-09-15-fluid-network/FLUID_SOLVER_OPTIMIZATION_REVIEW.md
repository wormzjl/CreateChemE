# Fluid-network solver optimization: implementation review and handoff

Branch `claude/fluid-solver-optimization` (worktree `.claude/worktrees/fluid-solver-opt`), based on the Codex
snapshot `154007dc1f68574634cc0ef64ba66f132d83e2e3` of `codex/simulation-fluid-network`. Nothing pushed.
Final HEAD: `af65243` (32 commits, 78 files, +6631/-917 lines). Implemented by sequential Opus work packages under Claude Fable 5.1
orchestration on 2026-09-16/17, one Gradle invocation at a time; every commit carries its own gate result and a
measurement row in the tracked progress log `FLUID_SOLVER_OPTIMIZATION_PROGRESS.md` at the branch root.

Source review that defined the plan: `documentation/FLUID_NETWORK_IMPLEMENTATION_REVIEW.md` (sections 4-7).
Production measurement: `documentation/FLUID_POOL_MEASUREMENT.md`.

## 1. Outcome

### 1.1 Production pool (stress100: 100 islands, 1993 reservoirs, 12 workers, 60 s warm-up + 120 s measured, 4 GiB heap)

| quantity | 154007d (same-session rerun) | after WP1-WP3 (c2580ed) |
|---|---:|---:|
| worker ms p50 / p95 / max | 56.6 / 122.5 / 2000.8 | 3.28 / 5.88 / 15.6 |
| held intervals / 2 s wall hits | 86 / 12 | 0 / 0 |
| substeps per accepted interval p50 / max | 3 / 35 | 1 / 1 |
| allocation GB/s (MiB per interval) | 3.22 (122.8) | 0.087 (4.47) |
| GC pauses in window / humongous-forced | 483 / 214 | 6 / 0 |
| CPU: numeric LU / triangular solves / residual+properties | 42.4% / 20.9% / 27.3% | 0.6% / 50.5% / 37.2% |
| `one` gate (100 reservoirs, 2 workers) p50 / p95 / max ms | 112 / 167 / 206 (review) | 8.96 / 10.20 / 15.94 |

The stored baseline run (`memory-candidate-4g-r01`) reproduces within noise (p50 55.4, p95 119.6), so there is no
host drift. The 331 worker samples of the new window make the CPU buckets +/-5 pp; the conclusions rest on
differences far larger. Windows' 15.6 ms thread-clock quantum makes `workerCpuNanos` and `jdk.ThreadCPULoad`
unusable as absolute CPU at 3 ms jobs; only `nanoTime` regions and sample fractions are used. WP6a-WP7 were not
re-measured on the pool; their single-thread effect is on transients (below), which the steady state does not run.

### 1.2 Saved-island harness (single thread, counters exact, wall +/-25% on the quiet fixtures)

| fixture | quantity | 154007d | after WP3 (c2580ed) | after WP6a (4988efd) | final af65243 (cutoff 1e-6) |
|---|---|---:|---:|---:|---:|
| quiet 11312 (30 res / 45 pipes), mean of 5 intervals | wall ms | 81.9 | 37.5 | 28-37 | 30.0 |
| | substeps acc/rej (5 intervals) | 15/0 | 11/0 | 11/0 | 11/0 |
| | allocated MB | 88.8 | 29.0 | 18.9 | 17.6 |
| quiet 11324 (18 / 27), mean of 5 | wall ms | 41.4 | 17.1 | 12.7-13.9 | 13.2 |
| | allocated MB | 53.2 | 20.6 | 14.8 | 12.8 |
| cold 11312, one interval | wall ms | 2036 | 1115 | 809-922 | 767 |
| | substeps acc/rej | 51/15 | 52/15 | 52/15 | 52/15 |
| | Jacobian builds / residual evaluations | 54 / 11391 | 41 / 8731 | 41 / 8731 | 41 / 899 |
| | allocated MB | 2897 | 1135 | 735 | 558 |
| 100-reservoir chain, one interval | wall ms | 3945 | 1901 | 1410-1434 | 1160 |
| | substeps acc/rej | 47/31 | 45/22 | 45/22 | 45/22 |
| | allocated MB | 6991 | 3230 | 1817 | 1640 |

Unknowns per node 47 -> 41 at the default cutoff (the review's forecast). Unit costs on the implementation host:
node `state()` with a prepared temperature 7900 -> 3800 (WP3) -> 1800 ns (WP6a); `waterLiquid(T,P)` 2338 -> 200 ns;
transport row 2.9 -> 1.4 us; standalone `flashTP` 148 -> 78 us.

## 2. What landed, in order

| WP | commit | change | gate |
|---|---|---|---|
| 0 | 15403b9 | `SolverDiagnostics` counters (off by default, one volatile read per hook) and the saved-island regression harness (`fluidSolverRegression`, EXACT/DECLARED modes, capture mode, references at 154007d in `src/test/resources/fluid/regression/`) | none needed |
| 1-A2 | 0901c8e | interval solver retained per island (`RetainedSolver`, `SolverOwnership` latch), replaced on revision bump | DECLARED (1e-9 rel: preconditioner path) |
| 1-B1 | 1a8d3ff | sparse backward-error check only where it can fail (`Verification.UNTIL_VERIFIED`, stalled line search) | EXACT |
| 1-A1 | cad3474 | accepted step carried across intervals; `COLD_START_SECONDS = 0.05` after events | DECLARED |
| 1 | 3b281cb | retained solver shared with module transfer trials (trials start from the cold step) | DECLARED |
| 2-B2 | cb5a881 | factorization storage in the workspace fork family; superseded factorizations treated as missing | EXACT |
| 2-A4 | bc14098 | TR-BDF2 companion error estimate as a linear filter through the stage-2 factorization; nonlinear fallback | DECLARED |
| 2-C5 | cffdcff | no per-job string keys | EXACT |
| 3-A4b | 16f9cfd | companion defect below the Newton tolerance = zero correction; chord self-check (perturbed residual within max(tolerance, defect)) else nonlinear fallback | DECLARED |
| 3-C1 | d91e13d | water properties with explicit `Water` record (no ThreadLocal), incremental IF97 powers, `FluidThermodynamics.Prepared` per node temperature | DECLARED (8e-14) |
| 3-C2 | e604043 | no clones/streams in the residual (view accessors, Kahan-compatible `PhaseLayout.sum`) | EXACT |
| 3-C4 | f814bc0 | pure-component viscosities prepared per node temperature | EXACT |
| 5 | dba6179 | pool-safe diagnostics (`ThreadLocal` markers), `-PfluidBenchmarkDiagnostics=true` writes `solverDiagnostics` into `report.json` | EXACT |
| 6a-B2b | dff77da | `ConservativeTransport` ordering/structure/factorization storage reused across intervals | EXACT |
| 6a-1 | 7d26793 | `V3PengRobinsonKernel` promoted to `science/thermo/PengRobinsonKernel`; V3 facade delegates | V3 bitwise |
| 6a-C3 | ac4f120 | `TranslatedPengRobinson.evaluate` on the shared kernel with sparse-pair mixing (fluid path only); `TemperatureTerms` removed; legacy oracle test max 9.2e-13 | DECLARED (<= 6.9e-12) |
| 6a-B4 | 4988efd | allocation-free phase derivative bundle (`differentiate`, `hydrocarbonDerivatives`); FD agreement <= 7.6e-8 | EXACT |
| 6b-1 | 1687e2d | Jacobian built from per-node block perturbations (`nodeAccumulate`/`edgeRows`/`nodeRows`, `differentiateEntries` hook); step-0 attribution counters | EXACT |
| 6b-3 | a3593ed, 8bfa7ef | refresh-policy pricing and structural-invariance record (no policy change landed) | docs |
| 7-A | a56662b | nitrogen as shared catalog data: `components/nitrogen.json`, `properties/nitrogen.json` (id `createcheme:fluid_nitrogen` kept: persisted through `Package.fingerprint`), package `createcheme:tjl20_methane_nitrogen` = the column's 20 components in order + Nitrogen, tjl20 interactions with `missing_interactions: zero`; `FluidMaterialCatalog.withNitrogen` removed; old package id migrates on model resolution | EXACT |
| 7-B1 | 40ba7a8 | `TraceTruncationPolicy` in `science/thermo` (V3 imports it); per-component support (`BOTH`/`LIQUID_ONLY`/`VAPOR_ONLY`/`ABSENT`) in `PhaseLayout`, in `phaseSignature`/`WorkspaceKey`; config `fluidTraceCutoffMoleFraction` [0, 1e-5] default 1e-6 carried on `FluidThermodynamics` (model identity replaces retained solvers; `ApproximationAnchor` revision gains `:trace=`) | EXACT at cutoff 0 |
| 7-B2 | 7360aa2 | support derived from the seed's full-basis compositions per implicit solve, reactivation in `phaseCorrection` by the `omittedStillTrace` K inequality with 10x hysteresis, counters, 13 tests | EXACT at 0; DECLARED at 1e-6 |
| 7-B3 | af65243 | cutoff measurement 0 / 1e-6 / 1e-5 (1e-5 rejected as default: no faster, deviations 30x larger, flow 2.7x the controller allowance) | docs |

## 3. Regression policy actually applied

- Trajectory-identical items were verified bitwise (EXACT harness mode on temperature, pressure, mass, volume, phase
  volumes, component totals, average flows and substep counts) against a fresh capture of the previous commit.
- Everything else runs under the DECLARED gate against the 154007d references: 1e-6 relative on state and moles,
  1e-4 K, 1e-6 phase fraction, flows within max(1e-3 max(|q_ref|, floor), floor) with the engine's own floor
  1e-9 + 2e-9 (m_first + m_second) / duration. Measured worst case at the final HEAD (cutoff 1e-6): state 1.30e-8
  relative, 2.29e-6 K, phase fraction 1.5e-9, flow 7.1e-9 kg/s against a 3.18e-8 kg/s allowance. Each such commit
  also reports its own roundoff against the previous capture (e.g. C1 8e-14, C3 6.9e-12).
- The full gate after every commit: JUnit `test` (793 -> 808 tests) and `runFluidGameTestServer` (14 GameTests).
- The column is untouched numerically (USER RULE): the promoted kernel keeps V3's arithmetic on V3's path; the
  sparse-pair mixing is selected only by the fluid network; 483 tests across 86 `science.column.v3` classes and the
  pinned digests pass unchanged. The only column edits are the kernel and policy moves and their imports.
- `CausalModuleCoordinatorTest`'s restart assertions were re-expressed relatively (1e-9) after the retained solver
  moved the module path off the bitwise trajectory (absolute 1e-4 J on 2.25e8 J tripped at 1.5e-11 relative).

## 4. Measurement findings that changed the plan

- **Pool-vs-single-thread discrepancy resolved.** The baseline pool profile (42% LU) was the transient regime: HEAD's
  own warm-up window reproduces the shape (39% LU, `differentiate` inclusive 67%), while the measured steady state
  builds no Jacobian at all. Contention was real but secondary (80.6 ms pool vs 51.2 ms single-thread per job at
  154007d).
- **B3 (block / fill-aware LU) dropped.** 0 factorizations across 2400 steady-state intervals; a perfect B3 would move
  p50 3.28 -> 1.6-2.4 ms. It still pays only on cold transients (warm-up p95 1000 -> ~740 ms).
- **A5 ledger-impact flow criterion not needed.** Zero rejected substeps in production; the pipe term dominated 15 of
  2400 attempts and rejected none. `COLD_START_SECONDS` covers the event path. A genuine cold solve still runs 59
  substeps / 18 rejections / 1.97 s in the pool warm-up, seven warm-up jobs within 30 ms of the 2 s wall; the
  single-thread cold island is 2036 -> 767 ms after WP6-WP7.
- **A4 linear filter needed a self-check.** The carried chord (`forkPreconditioner`) under-damps as an implicit
  operator; the tolerance floor alone did not restore the quiet islands. Requiring the solve's own factorization was
  measured and rejected (+7% cold, +19% chain).
- **`setStructureLocked(true)` throws in EJML 0.44** (numeric pivoting), so structure locking was replaced by storage
  reuse in the fork family.
- **`Arrays.stream(x).sum()` is Kahan-compensated**; bitwise removal of streams needed `PhaseLayout.sum` to reproduce
  `Collectors.sumWithCompensation`.
- **`IdentityHashMap$KeyIterator` (2% of allocation) is the JVM's `TerminatingThreadLocal` on thread exit** of
  non-solver threads, one 203 MB sample; nothing to remove.
- **The kernel repairs the Cardano cancellation** at sub-kilopascal liquid roots where `PengRobinson78` differs by up
  to 4e-4; production never reaches those states (liquid evaluated at the 2 MPa reference).

## 5. WP6b: what the measurement said about the analytic Jacobian

- **Attribution on the cold island** (residual evaluations close exactly as newtonSolves + inJacobian + iterations +
  backtracks): colouring 7831 of 8731 (89.7%), opening 135, line search 765 (724 on a chord); 5.64 Newton iterations
  per implicit solve; 133 of 135 solves open on an inherited chord and 94.6% of iterations use one. LU 41 = 325 ms of
  935 ms; 816 triangular solves = 89 ms; 98 070 node decodes ~ 310 ms.
- **The block sweep removes assembly, not properties.** The coloured sweep already decoded each node once per own
  column (1440 per build = 30 x (47 + 1 restore)); the block sweep's floor is 1410. Landed bitwise (residual
  evaluations cold 8731 -> 900, decodes -1.3%, wall -2..-5% on the transients, allocation -16% cold / -5..-10%).
- **Analytic self-blocks not landed, by measurement:** prize 20-24% of a transient interval and zero in production
  (no Jacobian builds over 2400 steady-state intervals); the self-block alone buys nothing because a neighbour's
  inflow rows need the donor's moles, mass and enthalpy and the hydraulic row its density, viscosity and velocity cap,
  so all 47 decodes per node stay unless the whole node interface is analytic; and `GlobalLiquidResponse` adds
  v_i I(P)/(RT) to each liquid ln phi_i, whose composition sub-block needs d v_i / d n_j (= RT d/dP [d ln phi_i / d n_j],
  closed-form in the kernel but absent from the WP6a bundle). Routes are recorded in the progress log; the
  `differentiateEntries` hook is the seam a continuation plugs into.
- **Refresh policy: nothing beats the current one on both transients.** A fresh factorization costs ~100x a residual;
  refresh-every-iteration cuts iterations 2.3x and is 2.9x / 4.2x slower; dropping the age limit is -25..30% on the
  cold island but +32..40% on the chain. Follow-up: a rule keyed on the measured factorization-to-iteration ratio.

## 6. WP7: trace truncation and nitrogen

| fixture | cutoff | ms | MB | unknowns/node | system size | substeps | implicit solves | Jacobians | residuals | decodes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| quiet 11312 (5 x 5 s) | 0 | 163.9 | 83.9 | 47 | 1455 | 11/0 | 25 | 3 | 86 | 8820 |
| | 1e-6 | 149.8 | 87.8 | 41 | 1275 | 11/0 | 29 | 3 | 120 | 9420 |
| quiet 11324 (5 x 5 s) | 0 | 71.4 | 64.8 | 47 | 873 | 11/0 | 29 | 4 | 95 | 6372 |
| | 1e-6 | 66.0 | 64.1 | 41 | 765 | 11/0 | 28 | 4 | 123 | 6426 |
| cold 11312 | 0 | 894.5 | 588.9 | 47 | 1455 | 52/15 | 135 | 41 | 900 | 96 840 |
| | 1e-6 | 767.3 | 557.6 | 41 | 1275 | 52/15 | 135 | 41 | 899 | 89 430 |
| 100-chain | 0 | 1406.8 | 1647.5 | 47 | 4799 | 45/22 | 135 | 22 | 1003 | 243 800 |
| | 1e-6 | 1160.3 | 1639.5 | 41 | 4199 | 45/22 | 135 | 23 | 990 | 233 400 |

- Cutoff 0 is bitwise the pre-truncation path (EXACT after each of the three commits). At the shipped default 1e-6
  the transients gain -14% / -19% wall; the quiescent islands sit inside their spread (they build almost no Jacobian).
  Reactivation fired 0 times on every fixture; it is exercised by `TraceTruncationLayoutTest` and
  `TraceTruncationStepTest` (a complete implicit step on a displaced-methane island). 1e-5 was measured and rejected
  as a default (no faster anywhere; the cold island trades the unknown for 2 more rejected substeps).
- `InventoryEquilibrium` stays on the full support at interval boundaries (a truncated component's equilibrium row
  is identically zero there).
- The cutoff lives on `FluidThermodynamics` rather than `PassiveIntervalSolver.Settings` (settings are `defaults()`
  at every call site and passed per solve); model identity is what `RetainedSolver.acquire` compares, so a changed
  cutoff replaces every island's solver on server start, and saved fallback anchors from another cutoff are refused.
- Nitrogen: the network package reproduces the old private extension's `scientificRevision()` exactly
  (`NitrogenInitializationTest` rebuilds the extension from the unextended resources and asserts equality), so saved
  worlds load; island entries naming the column's package migrate on model resolution and the next save writes the
  registered id. The package carries the Tia Juana assay on the network basis with nitrogen at zero; the crude presets
  are still computed on the column's 20-component packages and padded. The column's own package keeps its 20
  components, so a network -> column stream maps by component id and the column's basis is a separate decision.

## 7. Open items and follow-ups

- **Merge-conflict risk with the user's uncommitted material-quality edits** in the main checkout
  (`MaterialCatalog.java`, assay JSONs, `MATERIALS.md`, `MaterialQuality.java`): WP7 touches two `MaterialCatalog.java`
  hunks, both in the assay-appearance map, now keyed by package id and assay id (`parse`'s assay loop and
  `assayAppearance(packageId, assayId)`), because two packages now declare the same composition on different bases.
  No assay or package JSON the workstream edits was touched. `MATERIALS.md` does not yet document the nitrogen package.
- Remaining allocation on the fixtures: `PipeTransfer`/`ConservativeTransport` records (9.4% of the pool census),
  `PhaseLayout` (6.5%), `PassiveNetwork$Inventory.moles` defensive clone (1.9%), `GlobalLiquidResponse.evaluate`'s
  8-element validation array (~200 KB per quiet interval).
- Transient cost is now the only cost: the cold island is dominated by LU (325 of ~900 ms) and Newton iterations on
  stale chords; candidates are the ratio-keyed refresh rule (section 5), B3 on the cold path only, and the analytic
  node interface once the second composition derivative exists.
- Two kernel tests (`V3PengRobinsonKernelTest`, `V3PengRobinsonDerivativesTest`) stay in the V3 test package because
  they build on the package-private `V3Cdu17TiaJuanaPackage` fixture; they exercise the promoted class through
  `V3PengRobinsonSession.kernelFor`.
- WP6a-B4 costs ~2% allocation (two n-vectors per prepared temperature) for `evaluate`/`differentiate` sharing a body.
- Test edits forced by nitrogen becoming catalog-wide: `FluidPropertyCoverageTest` builds one model on the network
  package; `ViscosityTest` skips a property without a DWSIM record (nitrogen's curves are NIST isobar tables);
  `MixtureViscosityTest`/`NitrogenInitializationTest` name the registered package.
- `process-memory.csv` (RSS) is not collected by the benchmark; only heap figures are reported. No in-game GUI check
  was made beyond the 14 GameTests. The pool was not re-measured after WP6a-WP7.

## 8. How to reproduce

```bash
# saved-island regression harness (needs build/probe/core.dat and core-fallback.dat)
gradlew fluidSolverRegression -PfluidRegressionMode=relative
gradlew fluidSolverRegression -PfluidRegressionMode=exact -PfluidRegressionReferences=build/probe/<capture-dir>
gradlew fluidSolverRegression -PfluidRegressionCapture=true -PfluidRegressionReferences=build/probe/<capture-dir>
# production pool
gradlew fluidServerBenchmark -PfluidBenchmarkRunId=<fresh-id> -PfluidBenchmarkProfile=stress100 -PfluidBenchmarkHeapMiB=4096 -PfluidBenchmarkMemory=true -PfluidStressProfile=true -PfluidBenchmarkDiagnostics=true
```

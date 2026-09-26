
### 8.7 Cost per solve (runs 91+)

Run 2026-09-26 in the same worktree at 9674bf1 by Claude (Opus 5.5), on the owner's statement that the cost measured in 8.6 is still too high, CPU and memory both.
- *Code.* `holdup-prototype-be.patch` plus the levers below and their counters; the full diff is `tools/junction-holdup-prototype/holdup-prototype-be-fast.patch`, thirteen tracked files (the nine of the be patch plus `thermo/FluidThermodynamics`, `HydrocarbonModel`, `TranslatedPengRobinson`, `GlobalLiquidResponse`). It contains every earlier patch; apply it alone. `--check --reverse` on the patched tree and `--check` on the restored base pass; `git status --short` shows nothing tracked.
- *Conditions.* Gradle calls one after another, no dev client, nothing committed, no tracked test modified, `checkConservation` and the reconstruction gate unchanged at defaults.
- *Configuration.* Every run starts from run 87b's set: `BASE FINAL '-PbeStateCap=0.05'` (8.6), with `-PsolverDiag=true` on probe runs. The probe runner `edit-scripts/run-probe.ps1` writes the exact command as the first line of each log.
- *Timing caveats.* Probe `ms` carries the diagnostics' own overhead and about 5 % run-to-run noise (run 96e repeats 91a at 1023 against 1082 ms). The counters are exact and are the evidence; `ms` only ranks.

#### (a) Cadence: what an island costs in the product's slices (runs 91a-e)

The probe bounded every step at its 0.1 s interval. `-PjunctionInterval` now cuts the same physical schedule (10 s rest, 3 s injection, 3 s settle) into 0.1, 1 or 5 s intervals (5 s: 5, 5, 3, 3). `-PstartStep=hint` starts each interval at the last interval's `nextStepEstimate()`, as `RetainedSolver` does; the default starts it at `Settings.initialStep` (1 s, capped by the interval).

Twelve transient cases, 16 s simulated each:

| interval | start | nonconvergence | ms | accepted / rejected | rejected by | Newton solves | iter/solve | Jacobians | alloc MB |
|---|---|---|---|---|---|---|---|---|---|
| 0.1 s (91a = 87b line for line) | initial | 0 | 1082 | 1922 / 2 | newton 2 | 2158 | 5.11 | 560 | 1213 |
| 1 s (91b) | initial | 0 | 431 | 262 / 40 | be-state 30, newton 10 | 355 | 8.19 | 442 | 365 |
| 5 s (91c) | initial | 0 | 363 | 227 / 19 | be-state 15, newton 4 | 281 | 8.50 | 381 | 317 |
| 1 s (91d) | hint | 0 | 437 | 318 / 10 | newton 5, be-state 5 | 379 | 6.93 | 361 | 371 |
| 5 s (91e) | hint | 0 | 351 | 232 / 8 | newton 3, be-state 5 | 272 | 8.43 | 307 | 309 |

- *Zero nonconvergence at every cadence*: 12/12 transients and 32/32 static in all five runs. The Newton rejections are recovered by halving.
- *The cold start is now half of a 5 s-cadence island.* The balanced seed (248 flashes) and the cold rate solve cost 157-165 ms of the twelve cases in every cadence (runs 92, 96, 97): about 13.6 ms per cold start, 45-50 % of the 5 s total.
- *Warm cost at 5 s:* (349 - 163)/12 = 15.5 ms per case for 16 s simulated, about 1 ms per simulated second of a flowing five-node island. At rest it is zero (certificate).

Accuracy against the 0.1 s run (`run91-trajectory-summary.txt`; largest tank-pressure difference over the cases of a bore, and the 0.1 s run's remaining gauge range at that time). No interval of the 1 s and 5 s runs ends at 1.5 s, so the 1.5 s comparison of the brief cannot be made there; 1.0 s is the nearest shared time.

| run | bore | @ 1 s | @ 5 s | @ 10 s | @ 13 s | @ 16 s |
|---|---|---|---|---|---|---|
| 1 s | 50 mm | 184 Pa [gauge 57-15561] | 0.6 | 0.6 | 0.2 | 0.1 |
| 1 s | 20 mm | 121 Pa | 509 Pa [196-21297] | **858 Pa [19-834]** | 87 Pa [33-557] | 0.1 |
| 5 s | 50 mm | - | 0.2 | 0.2 | 3.8 | 0.1 |
| 5 s | 20 mm | - | 484 Pa | **845 Pa** | 131 Pa | 0.9 |
| 5 s hint | 50 mm | - | 0.7 | 0.7 | **2800 Pa [-1-6]** | 3.2 |
| 5 s hint | 20 mm | - | 358 Pa | 799 Pa | 117 Pa | 12.7 |

- *Slow decays lag by about their whole remaining gauge.* The 20 mm blowdown at 10 s is 816-858 Pa behind the 0.1 s run in both slice lengths, on 650-830 Pa of remaining gauge; the 0.1 s run itself is +100 Pa behind TR-BDF2 (8.6 (e)). The 0.05 state cap is relative to absolute pressure, so a decay of a few kPa takes 1-5 s steps.
- *A new defect of long slices: injection bottling (91e).* In 0.05:4:true at 5 s with the hint, the first injection interval (10-13 s) is one 3 s step from rest. `closeIllegalStarts` (`voidStart=history2`) closes the void edges at the start point, whose driving pressure is zero, and the 2.8 % tank change it produces is inside the cap. Both tanks end 2.8 kPa high and vent in the next interval. The initial-start run (91c) takes a 1 s first step and is 3.7 Pa off. This is a step-size question for the start-point closure, not a solver failure.

#### (b) Where the cost is (runs 91a/91k, 92)

New counters: flashes by call site, cold-seed and cold-rate timers, and ThreadMXBean allocation around the Newton, the Jacobian build, the reconstruction, `checkConservation` and the phase checks. Allocation sites come from JFR `jdk.ObjectAllocationSample` (`-Pjfr`, `jfr-sites.js`). JFR execution sampling did not take effect (66-77 CPU samples per run), so the CPU split comes from the timers.

| run 91a, 0.1 s | ms | share | MB | share |
|---|---|---|---|---|
| phase-check and trace-seed flashes (12222 + 1676) | 466 | 43 % | 532 (phase checks) + 77 (trace seeds, JFR) | 50 % |
| cold start (2976 seed flashes) | about 160 (runs 92/96e) | 15 % | 128 (JFR) | 11 % |
| Newton iterations (13712 residual evaluations; LU 8 ms) | rest | | 213 | 18 % |
| Jacobian builds (560) | 35 | 3 % | 22 | 2 % |
| reconstruction (2158) | 39 | 4 % | 49 | 4 % |
| `PipeTransfer.sample`/accumulate | - | | 67 (JFR) | 6 % |

The phase check is `phaseCorrection`'s TP flash of every single-phase node, run twice per accepted pass. `PassiveStepSolver.java:455` (patched) checks the converged Newton point and `:530` the reconstructed one. It costs 33-35 us and 44.6 KB per flash. Before the change, 204 + 180 MB of its bytes were the two array copies in `TranslatedPengRobinson$Values.phase:161-162`, 78 MB `GlobalLiquidResponse.logFugacity:49`, and 66 MB the `HydrocarbonModel$Phase` records.

#### (c) The levers, one at a time (5 s probe, run 92 = 91c repeated, and the static matrix)

| lever (run) | nonconv. | ms | accepted / rejected (newton) | solves | iter/solve | Jacobians | opened preconditioned | residual evals | alloc MB | static: solves, iter/solve, ms |
|---|---|---|---|---|---|---|---|---|---|---|
| none (92) | 0 | 349 | 227 / 19 (4) | 281 | 8.50 | 381 | 194 | 3112 | 317 | 72, 10.47, 654 |
| A `predictor=linear` (92c) | 0 | 446 | 243 / 38 (23) | 317 | 9.71 | 741 | 201 | 4662 | 366 | 72, 10.57, 639 |
| B `jacobianReuse=on` (92d) | 0 | 360 | 227 / 19 (4) | 281 | 8.59 | 374 | 207 | 3156 | 317 | 72, 10.47, 635 |
| C `rowForm=amount`, 1e-9 (92e) | 0 | 366 | 234 / 28 (14) | 290 | 8.61 | 414 | 198 | 3617 | 329 | 68, 10.54, 642 |
| C, 1e-8 (92f) | 0 | 384 | 230 / 24 (10) | 283 | 8.18 | 389 | 193 | 3435 | 324 | 68, 10.18, 610 |
| C, 1e-7, gate 1e-7 (92g) | 0 | 410 | 238 / 31 (17) | 299 | 7.69 | 366 | 205 | 3440 | 374 | 84, 10.21, 749 |
| rate rows, 1e-8 (92h, control) | 0 | 535 | 424 / 217 (201) | 680 | 5.46 | 356 | 585 | 5023 | 494 | 72, 10.14, 702 |
| D `phaseCheck=once` (92a) | 0 | 333 | as 92 | 281 | 8.50 | 381 | 194 | 3112 | 283 | as 92, 633 |
| E `lowAlloc=on` (96a, final form) | 0 | 352 | as 92 | 281 | 8.50 | 381 | 194 | 3112 | **140** | as 92, 624; 139 MB |

- **A, predictor: worse, rejected.**
  - Linear extrapolation over steps that grow by up to 2x on exponential decays overshoots. Iterations +14 %, Jacobians x1.9, Newton rejections 4 -> 23, ms +28 %. 251 of 281 solves were predicted.
  - At rest it also moves the start point off the previous state. The converged points then wobble within the Newton tolerance, and `IslandCertificateTest`'s closed ladder stops certifying (stationarity 1.59e-9 against eps_s 1e-9, run 99e).
- **B, Jacobian reuse: already taken; the switch is neutral.**
  - The dt-keyed workspace misses on almost every 5 s-cadence step (259 builds for 281 solves in 91c). The new workspace is then `forkPreconditioner()` of the structure's latest one (`PassiveStepSolver.java:397`), so 194 of 281 solves already open on the previous step's factorization.
  - The other 87 are the cold start, the solves after a Newton failure (`invalidate`) and 13 factorizations a sibling superseded.
  - The cost is that the chord built at another dt contracts poorly. 191 of the 381 Jacobian builds are stall refreshes (`reduction > 0.8`, `SparseNewton.java:188`) and 147 are age refreshes. Opening on the structure's latest workspace raises the preconditioned opens to 207 and changes nothing else measurable.
  - At 0.1 s, where dt is constant, the chord is reused 1842/2158 and still takes 5.11 iterations per solve.
- **C, amount-form junction rows.**
  - The rows divided by dt are the owned junction's mixing and enthalpy rows. Their flux-form weight is `m_J/dt + Q` (`junctionInflow`, patched `:2513`). Every vessel row is already in amount form: `(n - n_old - dt sum q x)/componentScale` and `(U - U_old - ...)/energyScale`. The net-mass row is a rate in kg/s, but its terms are flows, so its floor (eps q) does not grow as dt shrinks, and it is left alone. The junction's solid rows are ratios.
  - `rowForm=amount` multiplies the two rows by `dt/m_J`. The weight becomes `1 + dt Q/m_J`, and the rounding floor goes from `eps (m_J/dt + Q)` to `eps (1 + dt Q/m_J)`.
  - *Tolerance in amount units.* A junction row at tolerance tol now bounds the step's species mass error to `tol m_J` kg, and the enthalpy error to `tol m_J max(1, E_scale/m)` J. In rate form the bound was `tol dt` kg.
    - With m_J = 8.1e-3 kg (50 mm) and 1.3e-3 kg (20 mm), the amount form is tighter by m_J/dt: 12x and 77x at dt = 0.1 s, 620x and 3800x at 5 s. That is why it costs slightly more at 1e-9 (8.61 against 8.50 iterations; 14 Newton rejections, iteration limits and line-search stalls at the largest steps).
  - *Ledger:* closes to 3.8e-16-7.5e-16 in every run, and the probes pass.
  - *1e-8:* 8.18 iterations at 5 s (-4 %) and 4.34 at 0.1 s (-15 %, run 93g). The gate stays at 1e-8, equal to the tolerance and not moved.
    - The amount form removes the gate failures the rate rows have at 1e-8 (92h: 199 "Conservative reconstruction fails equation gate").
    - At 0.1 s, 48 steps still fail the gate with no margin left. Dump 93k shows the junction enthalpy row at 9.72e-9 after the Newton and 1.14e-8 after the pinned reconstruction.
  - *1e-7:* the gate moves to 1e-7 under the switch only. 7.69 iterations, but 11 "Velocity constraint did not close" (the 2e-8 relative cap check of 8.6 (d)) and 2 gate failures. Static solves go 72 -> 84.
  - *The rest certificate:* at 1e-8, with either row form, `IslandCertificateTest` fails both certificates. The closed pair ends REST instead of STEADY, and the closed ladder's stationarity is 1.66e-9 against eps_s 1e-9 (runs 99f, 99i).
  - **Result: the tolerance lever buys 4-15 % of the iterations and no wall time here.** It is not usable without a gate margin above the tolerance and a certificate threshold above the Newton noise, and both are owner decisions. The amount form itself is safe at 1e-9, and it removes the class-4 floor analytically. That floor was not re-measured at micro-steps.
- **D, flash accounting.**
  - A residual evaluation and a Jacobian build flash nothing. They decode through state calls: 11.8 per block Jacobian build (6635/560, one decode per node column; `differentiateEntries` decodes only the perturbed node), and the residual's decode cache skips unchanged nodes.
  - The flashes are all outside the Newton, at 0.1 s cadence:
    - the Newton-point phase check, 3.0 per solve;
    - the reconstructed-point check, 2.7 per solve;
    - the trace seed of `initialPhaseSeeds`, 0.87 per implicit solve, for a tank that lacks a reachable component (`:964`);
    - 248 per cold seed.
  - *The reconstruction re-flashes states the Newton already has.* The check at `:455` asks the same regime question of the Newton point that `:530` asks of the reconstructed point, which agrees with it to the Newton tolerance. `phaseCheck=once` drops the first check.
    - Every probe line is unchanged (92a, 96d, 97a/b/g/h), and the fluid suites are unchanged message for message (99c/99j against 99a).
    - Flashes 17012 -> 10568 and flash time 444 -> 231 ms at 0.1 s; 5023 -> 4216 at 5 s.
- **E1, allocation (`lowAlloc=on`, arithmetic unchanged, every probe line and every suite message equal).** Per unit of work, from `run97-alloc-units.txt`:

  | | alloc MB (12 cases) | per accepted step KB | per Newton solve KB | per Newton iteration KB | per Jacobian build KB | per reconstruction KB | per phase-check flash KB | MB per simulated s |
  |---|---|---|---|---|---|---|---|---|
  | 0.1 s base (96e) | 1213 | 646 | 576 | 19.7 | 39.7 | 26.1 | 44.6 | 6.32 |
  | 0.1 s D+E (97a) | 530 | 282 | 251 | 18.7 | 36.4 | 25.3 | 7.1 | 2.76 |
  | 5 s base (92) | 317 | 1430 | 1155 | 20.6 | 39.9 | 26.3 | 44.6 | 1.65 |
  | 5 s D+E (97b) | 133 | 598 | 483 | 19.8 | 38.8 | 25.5 | 7.2 | 0.69 |
  | static base / D+E | 416 / 138 | | 5916 / 1967 | 13.4 / 13.2 | 18.8 / 18.3 | 20.1 / 20.0 | 40.7 / 7.9 | |

  JFR, solver phases only (the JVM start and the material catalog, 142-161 MB per recording, are left out):

  | phase | 0.1 s before (91k) | after (96j) | 5 s before (91j) | after (96k) |
  |---|---|---|---|---|
  | phase-check flashes | 461.6 MB | 45.7 | 59.3 | 6.4 |
  | Newton (residuals, line search, LU) | 208.5 | 186.2 | 48.2 | 41.2 |
  | cold seed | 128.2 | 40.6 | 125.0 | 38.2 |
  | trace-seed flashes | 76.6 | 26.0 | 14.6 | 5.5 |
  | PipeTransfer sample/accumulate | 66.9 | 69.5 (before the shared empty stream) | 6.0 | 6.5 |
  | reconstruction | 44.4 | 45.5 | 4.5 | 7.0 |
  | equations/layout build | 30.5 | 24.3 | 4.0 | 3.5 |
  | Jacobian build | 18.5 | 13.5 | 11.0 | 12.5 |

  - *What was fixed.*
    - The flash's equilibrium iteration built two phase records per iteration. It now writes ln phi into two buffers (`HydrocarbonModel.logFugacityInto`, `TranslatedPengRobinson.evaluateValues`, `GlobalLiquidResponse.logFugacityInto`).
    - A phase-check flash reuses the node's prepared Peng-Robinson workspace.
    - `HydrocarbonModel.phase` copies only the coefficients its record keeps.
    - `PipeTransfer.sample` shares one empty stream.
  - *Suspects cleared by the numbers.*
    - The `differentiateEntries` clones: the whole Jacobian build is 1.5-2 % of the bytes, 36-40 KB per build.
    - `x.clone()`/`candidate` in the line search: `SparseNewton.solve:157/160`, 3.5-4.5 MB.
    - `checkConservation`: 3.4-4.4 KB per step.
    - `WorkspaceKey`/`supports`/`startPoint` churn: inside "equations/layout build" and "step solve other", 2-4 %.
  - *What is left.* 19-21 KB per Newton iteration is the state evaluation of each changed node, about 2.9 KB per state call. Its sources:
    - a new `Prepared` per temperature change, with its Peng-Robinson workspace (`TranslatedPengRobinson$Workspace.<init>:122-123`, `PengRobinsonKernel$Workspace.<init>`) and viscosity terms (`MixtureViscosity$Prepared.<init>:63-64`);
    - the `State` and `Phase` records;
    - the `GlobalLiquidResponse.evaluate:24` input array;
    - `PengRobinsonKernel.selectRoot:536`.

    Removing it needs mutable per-node decode buffers, which is not a contained change. The instrumentation's own ThreadMXBean reads are about 1 % (10-12 MB).
- **E2, retained memory per island** (`JunctionHoldupMemoryProbe`: N islands solved through two 1 s intervals and held, live heap after repeated GC, `jcmd GC.class_histogram` diff):

  | configuration (run) | 5-node island | of which graph / solver | 50-node gas chain | of which graph / solver |
  |---|---|---|---|---|
  | 87b set (95a, repeat 95e) | 80.8 KB (81.3) | 14.9 / 65.8 | 856 KB (851) | 103 / 753 |
  | `jacobianReuse=on` (95c) | 75.4 | 14.9 / 60.5 | 861 | 103 / 758 |
  | `lowAlloc=on`, decode cache and cold graphs released (95b) | 64.0 | 14.9 / 49.0 | 569 | 103 / 466 |
  | `lowAlloc=on`, final: + the last solve released (95f) | **50.4** | 14.9 / 35.4 | **432** | 103 / 329 |
  | final `lowAlloc` + `jacobianReuse` (95g) | 44.6 | 14.9 / 29.7 | 436 | 103 / 333 |

  - *Retention the probe found* (`run95-retained-classes.txt`):
    - The step solver's last solve (an `Equations` whose decode cache holds a `State`, `Prepared`, Peng-Robinson workspace and transport row per node) is read only by TR-BDF2's companion.
    - The interval solver kept the cold-start graph and its seeded copy for the island's life. The chain held 150 `Reservoir`s per island for 50 nodes; 50 after.
  - Both are released under `lowAlloc` after each accepted backward-Euler step (`PassiveIntervalSolver.java:357`). `BlockJacobianEquivalenceTest`, which reads `acceptedEquations()` from a direct `PassiveStepSolver`, still passes.
  - *The largest retained structure now* is the Newton workspace's sparse pattern and LU: `[D` + `[I` = 359 KB of the chain's 432 KB (about 7 KB per node), then the accepted graph (103 KB, 2 KB per node).
  - Of the brief's candidates:
    - "one workspace per structure key" is lever B, which changes the Newton path, so it is not under `lowAlloc`; it saves another 5.8 KB on the 5-node island and nothing on the chain.
    - Releasing the LU of non-current workspaces was not done: it would give up the preconditioner the reuse measurement in (c) shows is taken.

#### (d) Combined configurations

| configuration | cadence | nonconvergence | ms | accepted / rejected (newton) | solves | iter/solve | Jacobians | alloc MB | against run 87b (0.1 s: 1114 ms, 2158 solves, 1197 MB) |
|---|---|---|---|---|---|---|---|---|---|
| A+B+C(1e-8)+D+E (97e) | 0.1 s | 0 (static 32/32) | 900 | 1970 / 55 (55: 32 line-search stalls, 14 iteration limits, 10 gate, run 93b) | 2370 | 4.57 | 1030 | 598 | -19 % ms, +10 % solves, -50 % alloc |
| A+B+C(1e-8)+D+E (97d) | 5 s | 0 (32/32) | 381 | 247 / 41 (27) | 329 | 9.57 | 743 | 176 | |
| **D+E (97a/97g)** | 0.1 s | 0 (32/32) | 832-837 | 1922 / 2 (2) | 2158 | 5.11 | 560 | 530 | **-25 % ms, same solves, -56 % alloc; every probe line equal** |
| **D+E (97b/97h)** | 5 s | 0 (32/32) | 330 | 227 / 19 (4) | 281 | 8.50 | 381 | 133 | against 5 s base (92): -5 % ms, -58 % alloc |
| **D+E (97c)** | 5 s, hint | 0 (32/32) | 342 | 232 / 8 (3) | 272 | 8.43 | 307 | 126 | against 91e: -3 % ms, -59 % alloc |
| **D+E (97f)** | 1 s | 0 (32/32) | 383 | 262 / 40 (10) | 355 | 8.19 | 442 | 161 | against 91b: -11 % ms, -56 % alloc |

- The brief's combination A+B+C(1e-8) passes every probe, but it is dominated by D+E: A adds Jacobians and Newton rejections, and C at 1e-8 adds gate rejections at 0.1 s.
- **Recommended: D+E**, zero nonconvergence at every cadence, with the numerics of the 87b set.
  - Wall time -23 to -25 % at the 0.1 s probe cadence (against 91a and 87b) and -3 to -11 % at 1-5 s slices, where the cold seed (13.6 ms per cold start) now dominates.
  - Allocation -56 to -59 % at every cadence: 0.69 MB per simulated second of a flowing five-node island at 5 s slices, cold start included.
  - Retained memory -38 % (5-node) and -50 % (50-node chain).

#### (e) Gates

| run | set | 7 gate classes | fluid suites (399 + 3 probe wrappers) | against the no-lever reference |
|---|---|---|---|---|
| 98a / 99a | 87b set, no lever (reference) | 36/38 | 386/402 | - |
| 98c-d / 99c, 99j | + D+E | 36/38 | 386/402 | **equal, message for message** |
| 98b / 99b | + A+B+C(1e-8)+D+E | 36/38 | 384/402 | +2 failures |
| 99d | defaults, no init script | - | **399/399** (91 classes, 0 skipped) | - |

- *The two NetworkRegime gate failures* are the intended holdup change of 7.11 (h): run 81 values; under A-E the expected value moves in its 11th digit.
- *The reference against run 89c's list (385/401, cap 0.02).* The cap 0.05 of the 87b set, not a lever, moves three tests:
  - `FilterBlockLineIslandTest.aFilterBlockCostsAPumpedLine...` now **passes**.
  - `ElevatedBlockLineIslandTest` still fails, now at 848734.80 against 860918.31. It was 861061.96 at cap 0.02, so the pumped landing is 12.2 kPa low at cap 0.05.
  - `McpGameplayRegressionTest.belowSeaLevelNitrogenTankAcceptsWaterAfterAnIdlePeriodAndPressureEdit` **fails new**: tank mass 203.164 against a refined 203.419 kg at 0.1 % tolerance. **Accuracy class** (cap 0.05's longer steps against a refined trajectory).
  - The other fourteen failures (NetworkRegime x2 included) are those of 8.6 (f), with the same classes; the 0.02 -> 0.05 cap moves only their numbers.
- *The two new A-E failures, attributed by single-lever runs 99e-i:*
  - `IslandCertificateTest.aSettledBenchmarkClosedLadderCertifiesOnceItsQuietPipesAreExempt`: "the relative flow test alone would have refused", stationarity 1.47e-9 against eps_s 1e-9. Caused by the predictor alone (99e: 1.59e-9) and by tolerance 1e-8 alone, with either row form (99f, 99i: 1.66e-9).
  - `IslandCertificateTest.aSettledClosedPairCertifiesWithALongHorizon`: "horizon of 606 intervals" under A-E, "expected STEADY but was REST" under 1e-8 alone. Caused by tolerance 1e-8.
  - **Class: defects of levers A and C(1e-8)**. Both leave the rest state moving at the Newton noise, above the certificate's stationarity threshold. `rowForm=amount` at 1e-9 and `jacobianReuse` (99g, 99h) do not fail them.

#### (f) Open

1. *Cold start.* 248 flashes and about 13.6 ms per balanced seed. It is the largest CPU item left at the product's 5 s cadence (47 % of the transient probe) and 76-77 % of the static matrix. Its allocation fell to a third under `lowAlloc`; its flash count is untouched.
2. *Per-iteration allocation* of the state evaluation: 19-21 KB, from a `Prepared`, Peng-Robinson and viscosity workspace per temperature change plus the State/Phase records. It needs mutable per-node decode buffers.
3. *Chord contraction across dt changes.* 8.5 iterations per solve at 5 s against 5.1 at 0.1 s; half the Jacobian builds are stall refreshes of a chord built at another dt. Refreshing when the dt ratio exceeds a bound, or rescaling the chord's dt-dependent entries, is untested.
4. *Accuracy of long slices*, now measured. It remains the owner's decision of 8.6 (g) item 5.
   - The 20 mm decay at 10 s is 816-858 Pa behind at 1-5 s slices, on 650-830 Pa of gauge.
   - The injection-start bottling of (a) (2.8 kPa at 13 s) needs either a first-step bound after a boundary change or the start-point closure re-evaluated after a long step.
5. *The tolerance lever* needs a gate margin above the Newton tolerance and a certificate threshold above the Newton noise before 1e-8 can be used. Both are owner decisions; the amount-form rows are a prerequisite.
6. *Class-4 floor under the amount form:* removed analytically, not re-measured at micro-steps (the 88e line).
7. 8.6 (g) items 1-4 and 8-11 stand. With cap 0.05, item 1's FilterBlock landing passes and Elevated's landing is 12.2 kPa low.
8. *Prototype shortcuts added here:*
   - the predictor's retained start/end states per step solver;
   - `releaseLastSolve` called by the interval solver;
   - the flash's use of a node's prepared workspace, which relies on single-threaded use of a `Prepared` (already the documented contract);
   - the static `ZERO` stream map.
9. *Documentation index.* The main checkout's `documentation/INDEX.md` and this batch's copy there are not updated (no main-checkout edits in this brief).

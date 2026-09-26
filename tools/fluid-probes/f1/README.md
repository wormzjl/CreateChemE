# F1 probes: pumped fills held for minutes or forever

Batch `2026-09-23-fluid-followups`, package F1 (root cause, fix and hold policy). Reviews: `FLUID_PUMPED_FILL_REVIEW.md`, `FLUID_STARTUP_HOLD_REVIEW.md`; numbers in `f1-tables.md`. Every probe ran against the F1 working tree over `23beadd` (0.3.0) while the fixes `ee050d4`, `55ba5a0`, `5b3d903` and `7f933ff` were being written. The outputs (`probe-01.out`, `coordinated-01.out`, `real-0N.out`, `deadhead-0N.log`, `trace/`, ...) stay in the batch's `f1-logs/`.

## Probes (JUnit; copy into `src/test/java` at the package path, run with `--tests`, delete afterwards)

| file | package | tests | what it measured | cited in |
|---|---|---|---|---|
| `probes/PumpedFillProbeTest.java` | `runtime.fluid` | `probe`, `counters` | WP5's in-game layouts rebuilt off-line with the placement defaults and driven as consecutive 5 s intervals on one retained solver, retrying a failure from the cold step. It reproduced the in-game rejection texts, several of them bit for bit. | review section 1; `f1-tables.md` section 2 (`probe-01.out`, `counters-01.out`) |
| `probes/FluidPumpedFillCoordinatedProbeTest.java` | `runtime` | `calibrate`, `coordinated`, `coordinatedFresh`, `tracedAttempts` | The worker command with the coordinator's slice and hold rules, on a clock that charges a fixed cost per solver checkpoint (`calibrate` measured 1.03 us per checkpoint on a warm JIT; the rig uses 1 us). | review sections 1 and 2.4; `coordinated-01.out`, `proto-*-coordinated.out` |
| `probes/FluidPumpedFillRealCoordinatorProbeTest.java` | `runtime` | `realCoordinator`, `sixTank` | The real `IslandCoordinator` with a synchronous one-worker dispatcher on the same clock. It became the gate test `FluidPumpedFillLineTest` (`e945e7f`). | review sections 1 and 4; `real-0N.out` |
| `probes/DeadHeadProbeTest.java` | `runtime.fluid` | `trace` | `IslandCertificateTest`'s dead-headed pump, traced. It showed that the driving-pressure velocity cap sends a pump down a head-limit path it does not converge on, so devices and filters keep the flow's donor (`55ba5a0`). | review section 3 item 2; `deadhead-0N.log` |

Command, one Gradle invocation at a time: `JAVA_OPTS=-Xshare:off ./gradlew.bat fluidRuntimeTest --tests "*PumpedFillProbeTest.probe" --rerun --offline --console=plain`. Each probe prints into the JUnit XML's system-out; `scripts/xmlout.js` extracts it. Placed in `src/test`, these classes match `fluidRuntimeTest` (the `runtime.fluid.*` and `runtime.Fluid*Test` patterns), so never leave one there for a gate run.

## Trace instrumentation and its classifiers

| file | what |
|---|---|
| `instrumentation/wip-with-trace-instrumentation.patch` | The temporary trace sink: per attempt the time, step and outcome; per active-set pass the modes, closures, phases and start flows; per Newton iteration the norm, worst row, step length, backtracks and the column that limited the step. It lives in `SolverDiagnostics`, `SparseNewton`, `PassiveStepSolver`, `PassiveIntervalSolver`, `IslandCoordinator`, `IslandClock`, `ProcessSolveServices` and `FluidRuntimeDiagnostics`, and includes the fix candidates of that moment. It was never committed (review section 2). It applies to `23beadd` (checked: `cleanup-logs/patch-checks.txt`). |
| `instrumentation/wip-2-with-cap-and-trace.patch` | The same with the velocity-cap candidate in `PassiveStepSolver`. It applies to `23beadd`. |
| `scripts/profile.js` | `node profile.js <trace>`: a histogram of attempts over simulated time, with rejection reasons. |
| `scripts/failures.js` | `node failures.js <trace>`: classifies Newton pass failures as pinned by the nonnegativity limiter, by domain failures, or neither. |
| `scripts/limits.js` | `node limits.js <trace>`: maps every small-alpha step limit to its node and local column. |
| `scripts/xmlout.js` | `node xmlout.js <TEST-*.xml> [out]`: extracts a JUnit report's system-out. |

To reuse the trace sink, apply the patch to `23beadd` in a scratch checkout (the fixes changed the same code since), run a probe, and feed the `trace/*.txt` it writes to the classifiers.

## Run scripts

- `run-scripts/gates.sh <tag> [science runtime network regression gametest]`: F1's gate runner (logs `f1-logs/gate-<tag>-*.log`; P12/P31 compared with `fluid-scheduler/wp2-logs`).
- `run-scripts/paced.sh`: F1's paced runs (`transient100` determinism pairs and `stress100` at the 1e-7 default, 60 s + 60 s), with the machine check and crash handling (`FLUID_STARTUP_HOLD_REVIEW.md` sections 3 and 5).
- The in-game runs used the rig (`../../fluid-in-game-rig/`, campaign `campaigns/f1/f1-campaign.sh`).

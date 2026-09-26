# wp4-runtime-diagnosis

Purpose: off-line probes that located the causes of the two `fluidRuntimeTest` failures left by P3 WP4 (direct liquid path) and measured the candidate fixes. Batch `2026-09-24-coolprop-low-temperature`; report `documentation/2026-09-24-coolprop-low-temperature/P3_WP4_RUNTIME_DIAGNOSIS.md`. Base commit `959ea0d`. Nothing here was ever in a tracked path; nothing needs re-attaching.

## Contents

- `src/com/wormzjl/createcheme/runtime/DiagPumpedFillProbe.java`: the six-tank chain's first 0.05 s slice from a cold solver (the test's own graph). Modes: `count` (checkpoints, `SolverDiagnostics` counters, flash-branch counters), `attempts` (plus the accuracy attempts), `attr` (every checkpoint attributed to its call site and its solver call chain with a `StackWalker`; slow), `warm` (12 repetitions in one JVM, warm wall time).
- `src/com/wormzjl/createcheme/runtime/DiagRigProbe.java`: `FluidPumpedFillLineTest`'s `Rig` (real `IslandCoordinator`, one synchronous worker, 1 us per checkpoint) with the wall budget as an argument; prints every job. Args: `layout ticks stop(committed|certificate|none) [wallNanos]` (the soft budget is 75 % of the wall budget, as in the world config).
- `src/com/wormzjl/createcheme/runtime/fluid/DiagElevatedProbe.java`: `ElevatedBlockLineIslandTest`'s pumped rising line, 40 intervals of 5 s on one retained solver, printing modes, heads, pressures and the pump margin on both columns per interval.
- `patches/FluidThermodynamics.diag.patch`, `patches/PassiveStepSolver.diag.patch`: diffs of the scratch copies against `959ea0d` (also reproducible with the `patch-*.js` node scripts in the order ft, ft2, ft3, pss, applied to `git show 959ea0d:<path>`). Switches, all off by default (the patched classes then behave as `959ea0d`, bit for bit):
  - `-Ddiag.water=legacy` (Region 1 at 2 MPa carried by `exp(-1e-9 (P - 2 MPa))`, the retired water, with the new pump reference density) or `-Ddiag.water=kappa -Ddiag.waterKappa=<k>` (Region 1 at 101,325 Pa carried by `exp(-k (P - P0))`: changes the compressibility only);
  - `-Ddiag.flashFix=true`: the trace-water flash fix (fixed-point start for `pc` before the bisection in `flashTP`);
  - `-Ddiag.pumpColumn=suction`: fix E2 (a pump connection's static column is its suction's density);
  - `-Ddiag.pumpDemand=column`: fix E1 (the pump's mode test reads the connection's frozen column);
  - `-Ddiag.pumpTrace=true`: prints every pump mode transition with its margin, column and pressures.
- `patches/cp.gradle`: the init script that printed the test runtime classpath (`gradlew -I cp.gradle diagPrintTestClasspath`).
- `out/`: every run quoted in the report.

## How to run

1. Compiled classes of the base commit (`build/classes/java/main`, `build/classes/java/test`, `build/resources/main`, `build/resources/test`); the diagnosis copied them to `build/diagnosis/snap/` so that other agents' edits could not change them.
2. Class path: the test runtime class path printed by `cp.gradle` with the four `build/` entries replaced by the snapshot, plus `gson-2.10.1.jar` from the Gradle cache (the printed class path lacks it).
3. Compile the two patched classes into `patch-classes/` and the probes into `probe-classes/` with JDK 21 `javac -proc:none`; run with `java -Xshare:off -cp "probe-classes;patch-classes;<classpath>" <probe> <args>`.

Examples: `DiagPumpedFillProbe attr GUPRPRPRPRPRPR 0.05`; `DiagRigProbe GUPRPRPRPRPRPR 2400 committed` (reproduces the test failure: 11 jobs, 22.0 s, held); `-Ddiag.flashFix=true DiagRigProbe GUPRPRPRPRPRPR 2400 committed` (one job, 1.0 s); `-Ddiag.pumpColumn=suction DiagElevatedProbe 40` (tank at 860,949.28 Pa).

No Gradle suite and no dev client is needed; each probe run takes 1 to 20 s.

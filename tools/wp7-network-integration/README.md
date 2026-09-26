# wp7-network-integration

Purpose: off-line probes, the record-edit scripts and the Gradle logs of P3 WP7 (network integration of the equilibrium
engine). Batch `2026-09-24-coolprop-low-temperature`; report `documentation/2026-09-24-coolprop-low-temperature/P3_EQUILIBRIUM_ENGINE.md`,
sections "WP7". Nothing here was ever in a tracked path; nothing needs re-attaching.

## Contents

- `src/Wp7FlashProbe.java`: the network flash on a few states (wet and dry crude + N2, N2 with trace and humid water,
  pure N2) and the WP5 pilot grid; run against the HEAD classes (`build/wp7/old-main`, compiled from `git show HEAD:<path>`)
  or the WP7 classes to compare phase states and warm per-flash cost.
- `src/com/wormzjl/createcheme/runtime/DiagPumpedFillProbe.java`, `DiagRigProbe.java`,
  `src/com/wormzjl/createcheme/runtime/fluid/DiagElevatedProbe.java`: the WP4 runtime-diagnosis probes
  (`tools/wp4-runtime-diagnosis/`) with their scratch-copy counters removed, run on the WP7 classes (six-tank first slice
  checkpoints and warm wall, the test rig's jobs and work, the pumped rising line).
- `src/Wp7BandProbe.java`, `Wp7StepProbe.java`, `Wp7FillProbe.java`: the design probes of the near-critical nitrogen
  island (`NearCriticalNitrogenIslandTest`): a generator path through the band, one generator-to-tank step attempt by
  attempt, and a tank filled through a narrow pipe (`-Dkind=VOID` vents it instead).
- `src/com/wormzjl/createcheme/science/fluid/thermo/Wp7PinProbe.java`: prints every bundled package's scientific revision,
  physics fingerprint and fluid envelope, the network revision, and (with an argument) `LegacyNetworkPathPinTest.digest`.
- `src/Wp7RunTests.java`: a JUnit 5 launcher for test classes (or `Class#method`) outside Gradle.
- `records.js` (with `records-edit.js`): the `fluid_domain` and evidence edits of the bundled records (idempotent, keeps
  line endings). `splice.js`: block replacement used for the `FluidThermodynamics` adapter.
- `out/`: the WP7 Gradle logs (science + pins, runtime, regression, final, GameTest).

## How to run

Compile with the toolchain JDK (21.0.11) against the test runtime class path (`build/diagnosis/testcp.txt` of the
diagnosis plus `gson-2.10.1.jar`; the scratch copy is `build/wp7/cp.txt`), the WP7 classes first:

    javac -proc:none -d build/wp7/probe-classes -cp "build/wp7/main;<cp>" tools/wp7-network-integration/src/Wp7FlashProbe.java
    java -Xshare:off -cp "build/wp7/probe-classes;build/wp7/main;src/main/resources;<cp>" Wp7FlashProbe

`src/main/resources` before the class path's `build/resources/main` gives the edited records without a Gradle run. The
legacy digest must be printed on JDK 21.0.11 (the pin is JDK-specific; WP5 section 17). Each probe runs in 1 to 20 s.

## The engine's fixed-point start (open item for the engine owner)

`patches/FluidTpEquilibrium-fixed-point-start.patch` is a diff against `FluidTpEquilibrium.java` at `f116a78` (WP6b's
file, not edited by WP7): the WP4 diagnosis's fixed-point start of the unsaturated-gas partial-pressure solve, placed
before `solveWet`'s bisection and accepted on the bisection's own tests, behind the scratch switch `-Dwp7.fixedPoint=true`
(a production version drops the switch). Measured with `DiagPumpedFillProbe` on the WP7 classes (the six-tank first
0.05 s slice): 214,660 to 36,961 checkpoints, warm wall 103 to 76 ms per slice, same 46/22 substeps and end states
(first tank 100,447.5 Pa, 288.147 K). Apply: compile the patched file into a class directory placed before the WP7
classes, or apply the patch to the tracked file once the engine owner agrees.

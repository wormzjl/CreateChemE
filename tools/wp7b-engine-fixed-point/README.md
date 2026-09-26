# wp7b-engine-fixed-point

Purpose: off-line probes and logs of P3 WP7b (engine side): the fixed-point start of `FluidTpEquilibrium.solveWet`'s
unsaturated-gas partial-pressure solve, and the four phase tests moved to the 10 MPa network domain. Batch
`2026-09-24-coolprop-low-temperature`; report `documentation/2026-09-24-coolprop-low-temperature/P3_EQUILIBRIUM_ENGINE.md`,
section "WP7b". Nothing here was ever in a tracked path; nothing needs re-attaching. The six-tank probe itself is WP7's
(`tools/wp7-network-integration/src/com/wormzjl/createcheme/runtime/DiagPumpedFillProbe.java`), reused unchanged.

## Contents

- `src/Wp7bUvProbe.java` (package `science.thermo.phase`): the pure-nitrogen coexistence UV and PH of
  `FluidTpEquilibriumPhUvTest.pureNitrogenCoexistenceTakesItsVapourFractionFromTheBalance` at 0.5 MPa, printing each
  beta's temperature and pressure errors against the kernel's equal-fugacity temperature, the TP calls and the detail.
  With `build/wp7/old-res` (the pre-WP7 records, 2 MPa) first on the class path it shows the 2 MPa window's path.
- `src/Wp7bDigestStates.java` (package `science.fluid.thermo`): the 63 free-water `flashTP` requests of
  `LegacyNetworkPathPinTest.digest`, one line each with a hash of the raw bits of every number the digest reads, so two
  class paths can be diffed.
- `src/Wp7bHotGasPip.java` (package `science.thermo.phase`): the phase identification parameter of dilute nitrogen and
  methane on the network kernel, 300 to 900 K (side observation, see the report).
- `debug-uv-trace.awk`: inserts four trace prints (UV start, Newton iterate, nested temperature step, inner pressure
  step) into a copy of `FluidTpEquilibrium.java`, switched on by `-Dwp7b.debug=true`; line numbers are those of the
  WP7b file before commit (re-derive them with grep for another revision).
- `gradle-run.sh`: one Gradle invocation under `build/gradle.lock` (tag `wp7b`), log under `build/wp7b/`.
- `out/`: every run quoted in the report (probe outputs, UV traces, Gradle logs).

## How to run

Class path: `build/wp7b/cp.txt` = snapshots of `build/classes/java/{main,test}` and `build/resources/{main,test}` taken
at `8e5274e` (WP7's final Gradle build) plus the dependency jars of `build/wp7/cp.txt`. The patched engine alone is
compiled into `build/wp7b/after` and put first:

    J="/c/Program Files/Java/jdk-21.0.11/bin"
    "$J/javac" -proc:none -d build/wp7b/after -cp "$(cat build/wp7b/cp.txt)" src/main/java/com/wormzjl/createcheme/science/thermo/phase/FluidTpEquilibrium.java
    "$J/javac" -proc:none -d build/wp7b/probe-classes -cp "build/wp7b/after;$(cat build/wp7b/cp.txt)" <probe sources>
    "$J/java" -Xshare:off -cp "build/wp7b/probe-classes;build/wp7b/after;$(cat build/wp7b/cp.txt)" com.wormzjl.createcheme.runtime.DiagPumpedFillProbe count GUPRPRPRPRPRPR 0.05

Leave `build/wp7b/after` out for the `8e5274e` engine. `warm` instead of `count` gives the warm wall (12 runs, last 6).
The legacy digest: `Wp7PinProbe digest` of `tools/wp7-network-integration/`. Each run takes 1 to 20 s.

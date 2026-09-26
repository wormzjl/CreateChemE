# wp7c-hot-gas-label

Purpose: off-line probes and logs of P3 WP7c (engine side): the one-root liquid/vapour label of a hot gas, whose phase
identification parameter is just above one. Batch `2026-09-24-coolprop-low-temperature`; report
`documentation/2026-09-24-coolprop-low-temperature/P3_EQUILIBRIUM_ENGINE.md`, section "WP7c". Nothing here was ever in a
tracked path; nothing needs re-attaching.

## Contents

- `src/Wp7cLabelGrid.java` (package `science.thermo.phase`): the engine's classification, label, PIP, Z and v/b of pure
  nitrogen and methane on the network contract (300 to 900 K) and of nitrogen, methane, ethane and carbon dioxide on the
  pilot contract (to 1200 K), 0.1 to 10 MPa; a survey of every one-root `PIP > 1` state of each package component over
  its fluid domain (largest `Z` of a liquid-like one, `PIP > 1` with `Z >= 1`); and, with the argument `margins`, the
  sign of `PIP - Z` over one-root `PIP > 1`, `Z >= 1` states of pure components and mixtures against the density `v/b`.
- `src/Wp7cWetProbe.java` (package `science.fluid.thermo`): hot dry and wet states (0.1 mol water per mol) of pure N2,
  the lean N2/CH4 90/10, an eight-component light gas and the legacy digest's 20-component mixture through the network's
  `flashTP`, 600 to 900 K (and 1200 K, refused) at 0.1 to 2 MPa: slots, `pc`, `p_w`, `p_w/(y_w P)`.
- `src/Wp7cPinDigest.java` (package `science.fluid.thermo`): `LegacyNetworkPathPinTest.digest`, and the probe's
  `HydrocarbonModel.phase` flags that differ from the bare `PIP > 1` rule.
- `src/Wp7bDigestStates.java`: WP7b's free-water flash-state listing of the legacy digest probe, copied unchanged.
- `gradle-run.sh`: one Gradle invocation under `build/gradle.lock` (tag `wp7c`), log under `build/wp7c/`.
- `out/`: every run quoted in the report: `*-before.txt` on the committed `181302e` classes, `*-after.txt` on the WP7c
  classes, `tests-outside-gradle-*.txt` (WP7's JUnit launcher), `gradle-*.log` (copies of `build/wp7c/*.log`).

## How to run

Class path `build/wp7c/cp.txt`: snapshots of `build/classes/java/{main,test}` and `build/resources/{main,test}` taken
at `181302e` (WP7b's final Gradle build) in `build/wp7c/snap/`, plus the dependency jars of `build/wp7b/cp.txt`. The five
WP7c main classes are compiled into `build/wp7c/after` and put first for the after runs:

    J="/c/Program Files/Java/jdk-21.0.11/bin"
    M=src/main/java/com/wormzjl/createcheme/science
    "$J/javac" -proc:none -d build/wp7c/after -cp "$(cat build/wp7c/cp.txt)" $M/thermo/phase/PhaseIdentification.java \
        $M/thermo/phase/FluidTpEquilibrium.java $M/thermo/TangentPlaneStability.java $M/fluid/thermo/HydrocarbonModel.java \
        $M/fluid/thermo/TranslatedPengRobinson.java
    "$J/javac" -proc:none -d build/wp7c/probe-after -cp "build/wp7c/after;$(cat build/wp7c/cp.txt)" tools/wp7c-hot-gas-label/src/*.java
    "$J/java" -Xshare:off -cp "build/wp7c/probe-after;build/wp7c/after;$(cat build/wp7c/cp.txt)" \
        com.wormzjl.createcheme.science.thermo.phase.Wp7cLabelGrid [margins]

Leave `build/wp7c/after` out (and compile the probes into `build/wp7c/probe-before`) for the committed engine. Each run
takes a few seconds.

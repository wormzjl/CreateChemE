# gas-solid-equilibrium-scans

Purpose: offline scans, the cost probe and a Gradle-free test runner of P4 stage 2a (gas-solid equilibrium with the
CO2-I crystal in the equilibrium engine), batch `2026-09-24-coolprop-low-temperature`. Stage document:
`documentation/2026-09-24-coolprop-low-temperature/P4_GAS_SOLID_EQUILIBRIUM.md`. Created 2026-09-25 in worktree
`claude/coolprop-multiphase-thermo-37f6b0` (commits `5f2bd7d`, `8144935`), copied to the main checkout's `tools/`.

Nothing here was ever in a tracked path: no reattach patch is needed. The gate tests of the stage are tracked
(`GasSolidEquilibriumTest`, `G4F1CarbonDioxideFrostPointHoldoutTest`) and run with Gradle.

## Contents

| File | What it does |
|---|---|
| `run.sh` | javac of the whole `science.thermo.phase` package, the two P4 material classes, the phase and qualification test sources and `src/*.java` against the worktree's last Gradle compile (`build/classes/java/main` and `test`), the worktree's `src/main` and `src/test` resources first on the classpath; then the gate tests through `RunTests`, or any main |
| `RunTests.java` | the reflective JUnit 5 runner of `tools/solid-co2-qualification/` (PASS/FAIL per `@Test` method, `-Dmethod=name` filter) |
| `src/GasSolidScan.java` | exploratory scan: TP answers of CO2 in N2, onsets, one PH/UV round trip, the pure CO2 sublimation step (prints only) |
| `src/GasSolidCostProbe.java` | the cost figures of the stage document section 7: service construction, the anchored view, the crystal's chemical potential, TP with and without a deposit, PH, UV, the onset functions; cold (first call on a fresh service and workspace, median of 21) and warm (reused workspace, 2000 warm-up and 2000 timed calls, mean and p95) |

## How to run

From Git Bash in a worktree root that has a Gradle compile (`./gradlew compileTestJava` or any test run), with the
folder at `tools/gas-solid-equilibrium-scans/` of that worktree (it is git-ignored; copy it there from the main
checkout):

    bash tools/gas-solid-equilibrium-scans/run.sh compile
    bash tools/gas-solid-equilibrium-scans/run.sh tests                       # GasSolidEquilibriumTest
    bash tools/gas-solid-equilibrium-scans/run.sh tests com.wormzjl.createcheme.science.thermo.qualification.G4F1CarbonDioxideFrostPointHoldoutTest
    bash tools/gas-solid-equilibrium-scans/run.sh main com.wormzjl.createcheme.science.thermo.phase.GasSolidScan
    bash tools/gas-solid-equilibrium-scans/run.sh main com.wormzjl.createcheme.science.thermo.phase.GasSolidCostProbe

The JDK defaults to `C:/Program Files/Java/jdk-21.0.11/bin` (`JDK=...` overrides); `JAVA_PROPS` passes system
properties. For timings: no game or dev client running, one Gradle invocation or campaign at most elsewhere, at
least 20 GB free.

## Outputs

`research/2026-09-24-coolprop-low-temperature/p4-gas-solid/` in the main checkout: the Gradle gate logs, the printed
output of the two gate classes from the Gradle XML reports, and the cost probe runs.

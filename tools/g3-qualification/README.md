# g3-qualification

Purpose: iterate on the G3 qualification fixtures without Gradle. Batch `2026-09-24-coolprop-low-temperature`, stage P3,
work package WP9b (G3 qualification fixtures and report, `documentation/2026-09-24-coolprop-low-temperature/G3_QUALIFICATION_REPORT.md`).

The fixtures themselves are tracked gate tests in `src/test/java/com/wormzjl/createcheme/science/thermo/qualification/`
(`G3Support` and `G3F1...` to `G3F10...`); nothing of this folder is in a tracked path and nothing needs re-attaching.

## Contents

| File | Content |
|---|---|
| `run.sh` | javac of the qualification package against the last Gradle compile of the worktree (`build/classes/java/{main,test}`, `build/resources`, gson, JUnit API, EJML from the Gradle cache), then `RunTests` on the named classes. `-Dmethod=<name>` runs one method; `EXTRA=<sources> MAIN=<class>` compiles scratch probes into the same package and runs one instead |
| `RunTests.java` | a reflective runner of JUnit 5 `@Test` methods (PASS/FAIL per method, the first stack frames of a failure) |
| `out/` | printed output of the standalone runs and the Gradle run logs quoted by the report |

## How to run

From Git Bash in the worktree root, after at least one Gradle compile of the tree (the runner reads `build/classes`):

    bash tools/g3-qualification/run.sh G3F1PureFluidOracleTest G3F2GergBubblePointTest
    bash tools/g3-qualification/run.sh G3F8ConservationAndDerivativeTest -Dmethod=analyticDerivativesAgainstCentralDifferences

The standalone runs use whatever main classes the last Gradle compile left in `build/classes` (another agent's uncommitted
work included); the gate is the Gradle run in the report:

    JAVA_OPTS=-Xshare:off ./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.qualification.*' --offline

Detachment at WP11: copy this folder to the main checkout's `tools/g3-qualification/` with a `tools/INDEX.md` row (one-off
iteration aid, not a harness any Gradle task runs).

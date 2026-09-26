# wp7d-engine-defects

Batch `2026-09-24-coolprop-low-temperature`, stage P3, work package WP7d (the engine defects the G3 families found, and
the D15 bounds). Written 2026-09-25 on branch `claude/coolprop-multiphase-thermo-37f6b0`; the code landed in `774cb82`.
Notes: `documentation/2026-09-24-coolprop-low-temperature/P3_EQUILIBRIUM_ENGINE.md` section "WP7d" and
`G3_QUALIFICATION_REPORT.md` section 11. Nothing here is tracked or referenced by the build.

## Contents

- `run.sh`: compiles the WP7d main sources (`science/thermo/phase/*`, `FluidThermodynamics`) and the phase, qualification
  and network test sources with javac against the last Gradle compile (`build/classes`, `build/resources`) into
  `build/wp7d-classes`, then runs test classes through the reflective runner of `tools/g3-qualification/RunTests.java`, or
  a probe with `MAIN=<class>` (probe sources passed with `EXTRA=<files>`). No Gradle.
  Example: `bash tools/wp7d-engine-defects/run.sh com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibriumP3Test -Dmethod=anUnresolvedStabilityTestIsRepeatedOnTheLongerBudget`;
  `EXTRA=tools/wp7d-engine-defects/src/Wp7dFailures.java MAIN=com.wormzjl.createcheme.science.thermo.phase.Wp7dFailures bash tools/wp7d-engine-defects/run.sh`.
- `gradle-run.sh <log> <gradle args>`: one Gradle invocation under `build/gradle.lock` (tag `wp7d`), refused while a dev
  client runs, log in `build/wp7d/`.
- `src/`: probes (package `science.thermo.phase`): `Wp7dF5State`, `Wp7dF5Trace`, `Wp7dTraceStability` (the F5 state, its
  stability trace, a brute-force tangent-plane scan), `Wp7dScan` (WP9b's finer scan, a 150-180 K sweep, the P2
  near-critical layout), `Wp7dStaleAmounts`, `Wp7dReuse`, `Wp7dReuseStates` (the reused-workspace defect),
  `Wp7dFailures`, `Wp7dSurveyRepro`, `Wp7dNewtonBudget` (the survey grid's failures), `Wp7dVesselEdge` (the closed vessel
  past 10 MPa), `Wp7dLiquidLiquid`, `Wp7dLabelSurvey`, `Wp7dLleMargins`, `Wp7dSynthetic` (the liquid-liquid label).
- `out/`: probe outputs before and after the fixes (`*-before.txt`, `*-after.txt`, `survey-failures-final.txt`), the
  javac runs of the tests (`tests-outside-gradle-phase.txt`, `qualification-outside-gradle.txt`), the three Gradle logs
  of the final runs (`thermo.log`, `fluidScience.log`, `fluidRuntime.log`) and the families' printed output of the final
  Gradle run (`gradle-774cb82-g3-system-out.txt`).

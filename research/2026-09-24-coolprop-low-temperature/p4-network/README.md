# p4-network

Batch `2026-09-24-coolprop-low-temperature`, P4 stage 2b (the fluid network on the crystal competition), 2026-09-25.
Stage document: `documentation/2026-09-24-coolprop-low-temperature/P4_NETWORK_COUPLING.md`. Branch
`claude/coolprop-multiphase-thermo-37f6b0`, commits `0ea358a` to `58f32e9`.

## Contents

- `output-*.txt`: the printed output of the stage's test classes in the final Gradle runs (per point for G4F2; the island
  traces of the crystal tests; the pilot flash grid; the G3 F7/F9 tables).
  - `output-science.fluid.network.CrystalDepositionIslandTest.txt`: (a) closed vessel, (b) cool/warm reversibility,
    (c) pipe chain, (d) typed hold, the bulk withdrawal, the datum's sublimation enthalpy.
  - `output-runtime.fluid.CrystalIslandRuntimeTest.txt`: menu and block-entity deliveries per bucket; the held island.
  - `output-science.thermo.qualification.G4F2CarbonDioxideNitrogenFrostPointHoldoutTest.txt`: Sonntag 1960 and Smith
    1963 frost points, one line per point (onset, deviation, partial-pressure approximation), holds, points above 10 MPa,
    per-isotherm MAD and bias.
  - `output-science.fluid.thermo.SpineNetworkPathTest.txt`: the WP5 pilot flash grid under the crystal competition.
- `logs/`: the Gradle logs of the stage, one invocation at a time under `build/gradle.lock` (holder `p4c`),
  `JAVA_OPTS=-Xshare:off`, `--offline`, no dev client:
  - `fluidScienceTest-1.log` (1 failure: the grid's typed holds, test fixed), `-2.log`, `-final.log` (226 tests, green);
  - `fluidRuntimeTest-1.log` (2 failures: the archived captures' inventory shape, fixed), `-2.log`, `-final.log` (230, green);
  - `test-full-1.log` (1266, 2 failures: G3 F7/F9 CO2 boundary under the crystal competition, tests fixed),
    `test-full-final.log` (1267, green);
  - `fluidSolverRegression-exact.log` and `solver-regression-report-exact.json`: chain-100 exact replay, 0.000e+00 on all
    four quantities;
  - `fluidGameTest-1.log`: `runFluidGameTestServer -PfluidGameTestRunId=p4c-20260925` on a fresh world, all 30 required
    GameTests passed.

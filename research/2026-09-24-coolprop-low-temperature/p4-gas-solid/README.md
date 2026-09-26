# p4-gas-solid

Artifacts of P4 stage 2a (gas-solid equilibrium with the CO2-I crystal in the equilibrium engine), batch
`2026-09-24-coolprop-low-temperature`, 2026-09-25, worktree `claude/coolprop-multiphase-thermo-37f6b0` (commits
`5f2bd7d`, `8144935`). Stage document: `documentation/2026-09-24-coolprop-low-temperature/P4_GAS_SOLID_EQUILIBRIUM.md`.
Tool: `tools/gas-solid-equilibrium-scans/`.

| File | Content |
|---|---|
| `gate1-material-thermo.log` | `./gradlew test --tests "com.wormzjl.createcheme.science.thermo.*" --tests "com.wormzjl.createcheme.science.material.*" --offline`: 45 classes, 232 tests, 0 failures |
| `gate2-full-test.log` | `./gradlew test --offline`: 255 classes, 1,256 tests, 0 failures |
| `gate3-fluidScienceTest.log` | `./gradlew fluidScienceTest --offline`: 58 classes, 219 tests, 0 failures |
| `gradle-gate-output-2026-09-25.txt` | the printed output of `GasSolidEquilibriumTest` and `G4F1CarbonDioxideFrostPointHoldoutTest` from the Gradle XML reports of gate 1: every table of the stage document, per point |
| `cost-probe-2026-09-25.txt` | `GasSolidCostProbe` on the committed tree (section 7 of the document) |
| `cost-probe-2026-09-25-before-coverage-cache.txt` | the same probe before the per-competition coverage cache of `8144935` |

The holdout rows the gate reads are tracked in `src/test/resources/science/thermo/solid-co2-holdouts/` (copied from
`sources/solid-co2-holdouts/` with their citation headers).

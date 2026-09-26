# p5-solid-liquid-scans

Purpose: the measurement probes, exploratory scans and the data builder behind P5 (solid-liquid and three-phase
competition with the CO2-I crystal) of batch `2026-09-24-coolprop-low-temperature`. Stage document:
`documentation/2026-09-24-coolprop-low-temperature/P5_SOLID_LIQUID_EQUILIBRIUM.md`. Printed outputs and Gradle logs:
`research/2026-09-24-coolprop-low-temperature/p5-solid-liquid/`.

Nothing here is a gate. The G5 gate families (`G5F1` to `G5F6`, `G5Support`) and the P5 network tests stay in the code.

## Contents

| File | What it is | Tracked? |
|---|---|---|
| `src/P5FusionEnthalpyProbe.java` | The D16 revisit (the stage document's section 3): every CO2-I family scored with the crystal record's anchor fusion enthalpy at 9019 J/mol (the record) and 8875 J/mol (Jäger-Span, Maltby 2025): the pure sublimation line against Span-Wagner 3.12 and Fray-Schmitt, the sublimation enthalpy against Giauque-Egan's 25,236 J/mol, pure melting, G4F1, G4F2 (vapour and dense carriers), G5F2, G5F3 (line and compositions), G5F4, G5F5, and a SUMMARY table. A JUnit class in package `science.thermo.qualification` (it reads `G5Support`, `G4F1...`, `G4F2...`) | Never committed (run on `12cf449` plus the working tree); re-attachable by the patch |
| `src/P5LiquidFullVesselProbe.java` | Liquid-full pilot vessels (1 L) cooled through the network's energy input (-200 W or -50 W per 5 s interval, a methane trace): pure CH4; CH4 with 3 % CO2 compressed (freezes while liquid-full, then fails at its bubble point); CH4 with 2 % CO2, 0.5 % CO2, 2 % C2H6 or 2 % N2 (each fails at the bubble point with the network's active-set cycle; pure CH4 passes). A JUnit class in package `science.fluid.network` | Never committed; re-attachable by the patch |
| `p5-probes-against-1cfb1fc.patch` | Adds the two JUnit probes above at their test-source paths; `git apply --check` verified against `1cfb1fc` (new files: any later commit that keeps those packages takes it) | |
| `co2_liquid_viscosity_below_triple_point.py`, `co2-liquid-viscosity-90K.json` | Builder of the 32 subcooled-liquid viscosity nodes of CO2 from 90 K to the triple point (CoolProp 8.0.0, Laesecke-Muzny 2017 on the Span-Wagner liquid branch at the table's reference pressure) behind the `pilot_carbon_dioxide` liquid viscosity r2 (`0a8e544`); the record's source text names this folder | Script never tracked; its output is in the record |
| `run.sh`, `RunTests.java` | The javac runner of the P5 engine work (no Gradle): compiles `science.thermo.phase`, the crystal material classes, optionally the network thermo classes, the qualification tests and `src/*.java` against the last Gradle compile, then runs `@Test` methods reflectively (`RunTests`) or a probe's `main` | Never tracked |
| `src/P5Measure.java` | The first fusion-enthalpy measurement (plain main, G5 families only); superseded by `P5FusionEnthalpyProbe`, whose G5F2 numbers agree with it | Never tracked |
| `src/P5CostProbe.java` | Warm per-call times of the crystal answers beside a liquid (plain main); not run for the stage document | Never tracked |
| `src/P5Probe.java`, `P5ThreePhaseProbe.java`, `P5TripleProbe.java`, `P5ScanProbe.java`, `P5StepDebug.java`, `P5ThreeDebug.java`, `P5BisectDebug.java`, `P5IsochoreDebug.java`, `P5BinaryUvProbe.java`, `P5UvReplay.java`, `P5VesselProbe.java`, `P5DProbe.java` | Exploratory mains used while writing the engine (TP, onset, PH and UV scans, the binary three-phase step, the family triple point, the rigid-vessel fixtures of `CrystalDepositionIslandTest` (d)) | Never tracked |

## How to run

The JUnit probes, through Gradle (one invocation on the machine, `JAVA_OPTS=-Xshare:off`, `--offline`, no dev client),
from the worktree root:

```
git apply D:/Minecraft/Modding/1.21/CreateChemE/tools/p5-solid-liquid-scans/p5-probes-against-1cfb1fc.patch
./gradlew test --offline --tests 'com.wormzjl.createcheme.science.thermo.qualification.P5FusionEnthalpyProbe'
./gradlew test --offline --tests 'com.wormzjl.createcheme.science.fluid.network.P5LiquidFullVesselProbe'
git apply -R D:/Minecraft/Modding/1.21/CreateChemE/tools/p5-solid-liquid-scans/p5-probes-against-1cfb1fc.patch
```

The output is the system-out of `build/test-results/test/TEST-*.xml`. The fusion probe takes about 3 s, the
liquid-full probe about 20 s (its failing intervals exhaust the substep budget).

The plain mains, without Gradle, from Git Bash in the worktree root after a Gradle compile (copy this folder to the
worktree's `tools/p5-solid-liquid-scans/`; `run.sh` finds the repository root with `git rev-parse`):

```
bash tools/p5-solid-liquid-scans/run.sh compile
bash tools/p5-solid-liquid-scans/run.sh main P5Probe
SETS=network bash tools/p5-solid-liquid-scans/run.sh main P5VesselProbe 0 0.98 0 0.02 172 2.45e6 -150 4
```

The viscosity builder needs the CoolProp 8.0.0 Python wheel (revision `ae81610`), as `tools/pilot-transport-tables`:

```
"$TEMP/coolprop-probe-venv/Scripts/python.exe" co2_liquid_viscosity_below_triple_point.py > co2-liquid-viscosity-90K.json
```

## Batch

`2026-09-24-coolprop-low-temperature`, P5 (2026-09-25). Nothing left a tracked path: the probes were never committed,
so there is no removal commit; the patch re-attaches them.

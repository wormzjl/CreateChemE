# p6b-runtime-failures

Purpose: the probes, the javac runner and the gate script of P6b (diagnosis of the four runtime failures of the P6 pilot
world) of batch `2026-09-24-coolprop-low-temperature`. Stage document:
`documentation/2026-09-24-coolprop-low-temperature/P6B_RUNTIME_FAILURE_DIAGNOSIS.md`. Printed outputs and Gradle logs:
`research/2026-09-24-coolprop-low-temperature/p6b-runtime-failures/` (main checkout). The saved checkpoint the probes
read is the P6 copy in `research/2026-09-24-coolprop-low-temperature/p6-pilot-acceptance/world-final/data` (never the
world in `run/`).

Nothing here is a gate and nothing here was ever committed. The P6b gate tests stay in the code:
`WaterIntoCryogenicMethaneTest`, `StagnantJunctionTest`, `CrystalFlushBudgetTest` (`science.fluid.network`).

## Contents

| File | What it is |
|---|---|
| `run.sh` | The javac runner (no Gradle): compiles every `science.*` and `runtime/fluid` main source of the worktree over the last Gradle compile, the `science.fluid.network` tests, `PilotCryogenicTestCatalog` and `src/*.java` (with `COUNTERS=1` also `src-counters/*.java`) against `build/classes`, the NeoForge merged jar and the game-test legacy classpath (net.minecraft.nbt reads the saved checkpoint), through javac/java argument files; then a probe's `main` or JUnit `@Test` methods (the P6 `RunTests.java` from `tools/p6-pilot-acceptance/`) |
| `gates.sh` | The gate runner: one Gradle invocation at a time under `build/gradle.lock` (holder `p6b`, removed after each run), `JAVA_OPTS=-Xshare:off`, `--offline`, refuses while a dev client runs; `test`, `science`, `runtime`, `regression` (exact), `gametest` (fresh world `run/fluid-gametest-p6b-<suffix>`), `tests=<pattern>` |
| `src/P6bWorldProbe.java` | Reads the saved checkpoint (format 6) with the pilot model, prints every island's topology (nodes, phases, inventories, pipes, transfers) and times consecutive 5 s intervals through `RetainedSolver`; prints the typed domain cause of a refusal; `trace` runs single TR-BDF2 steps of decreasing size and prints the first exception's stack (found failure 3); `-Dp6b.strip=<index>` removes the non-CO2 traces of one vessel (the option A proxy of failure 1) |
| `src/P6bNearPureUvProbe.java` | Failure 1: the engine's crystal UV of row A's failing specification cold and warm, then the nitrogen and methane trace scanned from 0 to 1 % at the same energy and volume |
| `src/P6bNearPureMapProbe.java` | Failure 1: TP answers along the pure sublimation isotherm (the numerical step of the thin vapour-solid region) and the UV failure map over trace fraction and crystal share |
| `src/P6bUvDiagnosis.java` | Failure 2 helper: one vessel's crystal UV cold and warm (kernel evaluations, outer iterations) |
| `src/P6ChainProbe.java` | Failure 4: the P6 pilot chain (copy of `tools/p6-pilot-acceptance/src/P6ChainProbe.java`; `-Dp6b.intervals=`, `-Dp6b.traceInterval=`) |
| `src/P6bLiquidusKijProbe.java` | The liquidus finding: the engine's CO2 solubility at Shen 2012's 150.40 K point, the model's sublimation pressure against Span-Wagner 1996, the engine's CH4/CO2 k_ij there and the solubility under a perturbed k_ij |
| `src-counters/P6bRowEFlushProbe.java` | Failure 2: row E replayed from the reservoir's initial nitrogen, per interval wall time, checkpoints, substeps, crystal UV calls, kernel evaluations, warm hits, TP flashes; `compare` also runs the approximate fallback from the same start; `-Dp6b.uv=true` prints each start state's cold and warm UV. Needs `p6b-probe-counters.patch` |
| `p6b-probe-counters.patch` | Measurement-only instrumentation against `4c21104` (`git apply --check` verified): LongAdder counters in `FluidThermodynamics` (crystal UV and TP calls, kernel evaluations, failures) and `FluidTpEquilibrium` (warm attempts and hits); `-Dp6b.approximateCrystals=true` lifts D18's approximate refusal; `-Dp6b.crystalsAtEnd=true` equilibrates crystals only at the interval's end; `-Dp6b.warmBudget=<n>` overrides the warm budget (the "before" of failure 2 is `-Dp6b.warmBudget=8` on `a2f48d4`'s `FluidThermodynamics`). Apply, run with `COUNTERS=1`, reverse |

## How to run

From Git Bash in the worktree root, after a Gradle compile, with this folder copied to the worktree's
`tools/p6b-runtime-failures/` (and `tools/p6-pilot-acceptance/RunTests.java` present):

```
bash tools/p6b-runtime-failures/run.sh compile
bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.runtime.fluid.P6bWorldProbe all 1           # every saved island, one interval
bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.runtime.fluid.P6bWorldProbe 46 1 trace     # row B, step stacks
JAVA_PROPS=-Dp6b.strip=1 bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.runtime.fluid.P6bWorldProbe 47 4
bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.science.fluid.thermo.P6bNearPureUvProbe
bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.science.fluid.thermo.P6bNearPureMapProbe
JAVA_PROPS=-Dp6b.intervals=40 bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.science.fluid.network.P6ChainProbe
bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.science.fluid.thermo.P6bLiquidusKijProbe
bash tools/p6b-runtime-failures/run.sh tests com.wormzjl.createcheme.science.fluid.network.StagnantJunctionTest

git apply tools/p6b-runtime-failures/p6b-probe-counters.patch
COUNTERS=1 bash tools/p6b-runtime-failures/run.sh compile
bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.runtime.fluid.P6bRowEFlushProbe 20
JAVA_PROPS=-Dp6b.approximateCrystals=true bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.runtime.fluid.P6bRowEFlushProbe 8 compare
JAVA_PROPS=-Dp6b.crystalsAtEnd=true bash tools/p6b-runtime-failures/run.sh main com.wormzjl.createcheme.runtime.fluid.P6bRowEFlushProbe 8
git apply -R tools/p6b-runtime-failures/p6b-probe-counters.patch
```

The "before" numbers of each fix were taken by checking out the parent's version of the one changed source file,
recompiling with `run.sh compile` and running the same probe or test, then restoring the file (no stash).

Gates (one Gradle at a time, no dev client): `bash tools/p6b-runtime-failures/gates.sh <suffix> test science runtime regression gametest`.

## Batch

`2026-09-24-coolprop-low-temperature`, P6b (2026-09-26). Nothing left a tracked path: the probes and the instrumentation
patch were never committed.

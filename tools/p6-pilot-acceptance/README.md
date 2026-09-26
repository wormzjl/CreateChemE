# p6-pilot-acceptance

Purpose: the measurement probes, the dev-world datapack, the MCP bridge helpers and the gate script of P6 (integrate and
qualify the few-compound pilot, gate G6) of batch `2026-09-24-coolprop-low-temperature`. Stage document:
`documentation/2026-09-24-coolprop-low-temperature/P6_PILOT_ACCEPTANCE.md`. Printed outputs, screenshots and Gradle logs:
`research/2026-09-24-coolprop-low-temperature/p6-pilot-acceptance/` (main checkout).

Nothing here is a gate, and nothing here was ever committed: the probes ran from this folder (plain mains through
`run.sh`) or were copied into the test tree for one Gradle run and removed again (the two runtime probes; the patch
re-attaches them). The P6 gate tests stay in the code: `LiquidFullBubblePointTest`, `CrystalWarmStartTest`,
`CrystalMorphologyPolicyTest` (`science.fluid.network`) and `CrystalPackageConsumersTest` (`science.column.v3`).

## Contents

| File | What it is | Tracked? |
|---|---|---|
| `run.sh`, `RunTests.java` | The javac runner of P6 (no Gradle): compiles every `science.*` main source of the worktree, the listed test sources (default: `science.fluid.network` and `PilotCryogenicTestCatalog`) and `src/*.java` against the last Gradle compile, then runs `@Test` methods reflectively (`RunTests`, `-Dmethod=` through `JAVA_PROPS`) or a probe's `main`. `RunTests.java` is the P5 copy | never |
| `src/P6CrystalUvCostProbe.java` | Cost of a pilot vessel's crystal UV answer: three equilibrated vessels built through the network (vapour-solid, vapour-liquid-solid, liquid-solid), the engine's UV cold (first call, median of 21) and warm (mean and p95 of 2000), its TP-equilibrium count, and the network's equilibration at rest and after -100 J with its checkpoint count. Run before and after the warm start (`research/.../cost-probe-{before,after}-warm-start.txt`) | never |
| `src/P6BubblePointDiagnosis.java` | One liquid-full pilot vessel cooled through its bubble point, interval by interval; the diagnosis ran it with a temporary `PassiveStepSolver.DEBUG` pass trace (removed; the trace is quoted in the stage document section 3) | never |
| `src/P5LiquidFullVesselProbe.java` | P5's probe (from `tools/p5-solid-liquid-scans/`), run here before and after the fix | never |
| `src/P6ChainProbe.java` | CrystalDepositionIslandTest (c)'s pilot chain for twelve intervals; run at HEAD and on the sources of `4ff68da` (identical output: the zero-flow junction-donor cycle predates P6). `-Dp6.trace=true` needs the same temporary trace in `PassiveStepSolver` | never |
| `src-runtime/P6RuntimeBudgetProbe.java` | The coordinator rig of `CrystalIslandRuntimeTest` with certificates on: a pilot island against a bundled island of the same shape (closed vessel; pipe chain), 1,200 online ticks, scheduler counters, checkpoints and worker time per slice | never; `p6-runtime-probes-against-4d26a45.patch` |
| `src-runtime/P6WorldIslandProbe.java` | Reads the dev world's fluid checkpoint (format 6) with the pilot model, prints every island's vessels and times one 5 s interval of each through `RetainedSolver` | never; same patch |
| `p6-runtime-probes-against-4d26a45.patch` | Adds the two runtime probes at `src/test/java/com/wormzjl/createcheme/runtime/fluid/`; `git apply --check` verified against `4d26a45` | |
| `gates.sh` | The gate runner (one Gradle invocation at a time under `build/gradle.lock`, holder `p6`, `JAVA_OPTS=-Xshare:off`, `--offline`): `test`, `science`, `runtime`, `regression` (exact), `gametest` (fresh world `run/fluid-gametest-p6-<suffix>`) | never |
| `datapack/p6_pilot/` | The dev datapack of the GUI check: overrides `data/createcheme/materials/networks/default.json` to select `createcheme:pilot_cryogenic` and adds the methane, ethane and carbon dioxide fluid presets (the test override's files); no bundled record is changed | never |
| `mcp/` | Copies of the minecraft-mcp skill's `bridge.js`, `robot.ps1`, helper Java sources and references (branch `claude/minecraft-mcp-skill-c3f0f0`), and `gen.js`, which configures one fluid generator through the `runMcpClient` lane (tp, open, preset clicks, typed pressure and temperature with select-all and waits, Apply, screenshots) | never |

## How to run

The plain mains, without Gradle, from Git Bash in the worktree root after a Gradle compile (copy this folder to the
worktree's `tools/p6-pilot-acceptance/`):

```
bash tools/p6-pilot-acceptance/run.sh compile
bash tools/p6-pilot-acceptance/run.sh main com.wormzjl.createcheme.science.fluid.thermo.P6CrystalUvCostProbe
bash tools/p6-pilot-acceptance/run.sh main com.wormzjl.createcheme.science.fluid.network.P6BubblePointDiagnosis 0 .98 .02 0 172 5e6 -200 6
bash tools/p6-pilot-acceptance/run.sh tests com.wormzjl.createcheme.science.fluid.network.P5LiquidFullVesselProbe
bash tools/p6-pilot-acceptance/run.sh main com.wormzjl.createcheme.science.fluid.network.P6ChainProbe
```

The runtime probes through Gradle (no dev client, one invocation, lock):

```
git apply tools/p6-pilot-acceptance/p6-runtime-probes-against-4d26a45.patch
JAVA_OPTS=-Xshare:off ./gradlew test --offline --tests 'com.wormzjl.createcheme.runtime.fluid.P6RuntimeBudgetProbe'
JAVA_OPTS=-Xshare:off ./gradlew test --offline --tests 'com.wormzjl.createcheme.runtime.fluid.P6WorldIslandProbe'   # -Dp6.world is read by the test JVM only if passed through
git apply -R tools/p6-pilot-acceptance/p6-runtime-probes-against-4d26a45.patch
```

The output is the system-out of `build/test-results/test/TEST-*.xml`.

The GUI check (stage document section 5): copy `tools/fluid-in-game-rig/template/bench-template` to
`run/mcp-client/saves/<name>/`, add `datapack/p6_pilot` to its `datapacks/`, write `run/mcp-client/options.txt`
(`pauseOnLostFocus:false`, `onboardAccessibility:false`, `guiScale:2`), copy the bridge jar (SHA-256 `c6cc12c9...`) to
`run/mcp-client-mods/`, take the Gradle lock and start `./gradlew runMcpClient --offline`; then `node mcp/bridge.js
enter_control_mode`, Singleplayer, select the world, Play. Commands through `bridge.js chat` need `MSYS_NO_PATHCONV=1` in
Git Bash (otherwise `/gamerule` becomes `C:/Program Files/Git/gamerule`). The client log rolls over at midnight; the
Gradle stdout (`build/mcp-client.log`) keeps everything.

## Batch

`2026-09-24-coolprop-low-temperature`, P6 (2026-09-25 to 2026-09-26). Nothing left a tracked path; the runtime probes are
re-attachable by the patch.

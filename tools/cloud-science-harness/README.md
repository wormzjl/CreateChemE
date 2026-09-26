# cloud-science-harness

A Minecraft-free runner for the fluid gates: javac and the JUnit console launcher, no Gradle. It was built on
2026-09-26 for a cloud container whose network blocked the NeoForge/Create Maven hosts, so Gradle could not resolve
Minecraft. It stands in for the Gradle gates only; **the Gradle gates remain the gates of record.** The owner opened
the network later that day and Gradle now resolves in the container, so this tool is a fallback: it is for a
container without Minecraft, or for a quick check that does not need the Gradle lane.

Batch: `2026-09-26-phase-ports-and-compressor` (the WP1 gates in `HANDOFF.md` section 5 and `PHASE_PORTS_PLAN.md`
section 7). Built at commit `6e1c5b6` on branch `claude/cloud-harness-wip`.

## How to run

```
tools/cloud-science-harness/harness.sh fetch        # once: the five jars from Maven Central into ./lib (sha1-checked)
tools/cloud-science-harness/harness.sh all          # compile + science + runtime + adjacent + regression
tools/cloud-science-harness/harness.sh runtime --select-class=com.wormzjl.createcheme.runtime.fluid.IslandClockTest
REPO=/home/user/CreateChemE tools/cloud-science-harness/harness.sh all   # against another working tree
```

| Command | Gradle gate it stands in for | Selection |
|---|---|---|
| `compile` | `compileJava`/`compileTestJava` of the pieces below | see "What is compiled" |
| `science` | `fluidScienceTest` | package `com.wormzjl.createcheme.science.fluid`, minus `PackagedSparseLuTest` |
| `runtime` | `fluidRuntimeTest` | package `com.wormzjl.createcheme.runtime.fluid` plus every compiled `com.wormzjl.createcheme.runtime.Fluid*Test`; then the MIXED_GAS/LIQUID_JUNCTION lines are compared with `reference/junction-lines.txt` |
| `adjacent` | `test --tests *PassiveStepSolverTest ... *PipePresentationTest` | the 7 classes of the 38-test selection (warns if the simple-name patterns match more classes) |
| `regression` | `fluidSolverRegression -PfluidRegressionMode=exact` | the real `FluidSolverRegressionTest` with `-Dfluid.regression.mode=exact` (chain-100 against `src/test/resources/fluid/regression/chain-100.json`) |
| `select` | any | whatever JUnit selectors follow |
| `all` | all of the above | exits nonzero if any failed |

Every test command prints the JUnit summary, exits nonzero on failure and passes extra arguments to the console
launcher (`--select-class=...`, `--select-method=...`, `--include-tag`, ...); with extra arguments `runtime` skips the
junction-line comparison. The tests run as Gradle runs them: working directory = `REPO`, system property
`createcheme.fluid.scheduler.verify=true`, classpath test classes, test resources, main classes, main and generated
resources, gson, EJML. Test stdout is not captured, so the `MIXED_GAS_*`/`LIQUID_JUNCTION` lines and the regression
table appear in the console; each run also writes `console.txt` and the launcher's XML to `$OUT/reports/<command>/`.

Environment: `REPO` (repository root, default the checkout holding this folder), `OUT` (default
`$REPO/build/cloud-harness`, git-ignored), `LIB` (default `./lib`, git-ignored), `JAVA_HOME` (optional),
`HARNESS_JVM_OPTS` (default `-Xmx2g`). `JAVA_TOOL_OPTIONS` is unset for javac/java.

### Running against a modified working tree

`REPO=<tree> harness.sh all` compiles that tree's `src/main/java` and `src/test/java` fresh on every `compile` (the
test commands compile only when `$OUT` holds no classes yet, so run `compile` or `all` after editing). The header of
each run prints the commit and "+ uncommitted src changes" when `src/` differs from HEAD. Before compiling, the
harness checks that every line copied verbatim into a stand-in (between the `VERBATIM-BEGIN/END` markers) is still a
line of the real file, and that every real `CreateChemE` line naming `FluidOptions`, `fluidOptions` or a config
`define...` is in the stand-in; on drift it stops and names the line. A new test that needs a Minecraft class fails
the test compile: add it to `EXCLUDED_TEST_SOURCES` in `harness.sh` (and to the table below) or extend a stand-in.
The junction comparison ignores wall ms, bytes and allocatedMB; a change in any ledger number or Newton solve count
fails `runtime`, which is exactly the WP1 criterion (identical to base, all BULK).

## What is compiled

- Main: all of `science/` (154 files), all of `runtime/` (5 files) and `runtime/fluid/` (all 38 files, including
  `ProcessSolveServices`, `FluidCheckpointCodec`, `FluidCheckpointStore`, `FluidSavedData`, `FluidWorldAuthority`,
  `MinecraftFluidRuntime`, `FluidRuntimeMeter`), and the Minecraft-free `world/level/block/entity/ColumnInputPreset`,
  all as they are in `REPO`, plus the 57 stand-ins of `stubs/`.
- Tests: every file under `science/fluid/`, `runtime/` and `fluid/` except the two in `EXCLUDED_TEST_SOURCES`; helpers
  from other test packages are compiled through `-sourcepath`. 106 of 108 files at `6e1c5b6`.

## Stand-ins (`stubs/`)

No project class under test is replaced: the real `ProcessSolveServices`, `FluidCheckpointCodec` and every other
`runtime/fluid` file compile against the stand-ins.

Minecraft and NeoForge (compile-time only unless stated; a compile-only method throws `UnsupportedOperationException`):

- `net.minecraft.nbt` (19 files) - **functional**: `Tag`, the numeric tags, `StringTag`, the three array tags,
  `ListTag`, `CompoundTag`, `EndTag`, `NbtIo`, `NbtAccounter`, `NbtUtils`, written to Minecraft 1.21.1's semantics
  (HashMap-backed compound; lenient getters returning 0, "", empty arrays, a new compound or list; typed
  `contains(key, type)` with `TAG_ANY_NUMERIC`; array getters return the backing array; list element-type rule and
  its `UnsupportedOperationException`; `merge`; binary NBT with a named root and gzip for the compressed forms;
  `DataVersion` 3955). The checkpoint codec, store and saved-data tests run on this stand-in, not on Minecraft's NBT.
- functional values: `BlockPos`/`Vec3i` (coordinates, value equality), `ResourceLocation` (parse, character rules),
  `ResourceKey` (interned like Minecraft's), `Registries.DIMENSION`, `Level.OVERWORLD/NETHER/END`, `ChunkPos`,
  `TickTask`, `LevelResource.ROOT`, `SavedData` (real dirty flag; `Factory` record).
- compile-only: `MinecraftServer`, `ServerLevel`, `ServerPlayer`, `Player`, `Inventory`, `PlayerList`, `Level`'s world
  access, `BlockState`, `Block`, `BlockEntity`, `Entity`, `ItemEntity`, `ItemStack`, `AbstractContainerMenu`,
  `Component`, `HolderLookup`, `Registry`, `DimensionDataStorage`, NeoForge `IOUtilities`.
- `net.neoforged.neoforge.common.ModConfigSpec`: a builder whose values read as their declared defaults (no config
  file is loaded); it range-checks the default like the real builder.

Project classes (stubbed because the real ones are Minecraft classes: mod entry point, packets, block, block entity,
menu, item; none is under test):

- `CreateChemE`: the config fields, their whole static builder block, `calculationLoggingEnabled`, the `FluidOptions`
  record, `fluidOptions()` and the `columnV3*` accessors are **copied verbatim** from the real file (so the defaults,
  e.g. the 1 m3 reservoir volume and the 100 m/s maximum velocity, are the real ones, built on the `ModConfigSpec`
  stand-in); `LOGGER` is a stand-in with `info/warn/error(String, Object...)` printing `[harness-log LEVEL]` to stdout.
  The constructor and event handlers are not there.
- `world.level.block.entity.ColumnCalculatorV3BlockEntity`: the `V3Operation` record and `methaneCduInput()`, copied
  verbatim.
- compile-only: `network.FluidNetwork` (`MenuData(FluidView view)`, `snapshot`, `deliver`), `network.ColumnV3Network`,
  `network.ProcessSolveCoordinator`, `world.inventory.FluidDeviceMenu`, `world.item.RecoveredSolidsItem`,
  `world.level.block.FluidDeviceBlock`, `world.level.block.entity.FluidDeviceBlockEntity`.

`-verbose:class` on the junction transient test loads no stand-in class; no included test reaches a compile-only
method (all pass).

## Coverage at 6e1c5b6

Reference = Gradle run 156 of `documentation/2026-09-24-mixed-gas-junction/HANDOFF_REVIEW.md` 9.5 (d): 406 tests in
94 classes (`--tests science.fluid.* --tests runtime.fluid.*`; XML in `tools/junction-holdup-prototype/run156-gates`,
commit `b5ed407`, whose `science/` and `runtime/` sources are identical to `6e1c5b6`).

| Set | Gradle (run 156) | Harness | Not covered |
|---|---|---|---|
| `science.fluid.*` | 182 | 181 pass | `PackagedSparseLuTest` (1): tests the built mod jar's jarJar packaging (`fluid.modJar`), not science |
| `runtime.fluid.*` (44 files, 224 methods) | 224 | 210 pass | `FluidPacketCodecTest` (12): netty `ByteBuf`, `RegistryFriendlyByteBuf` and the real `network.FluidNetwork` packet codecs; `FluidDeviceSpecDomainTest` (2): `network.FluidNetwork.Controls` (packet class) |
| `runtime.Fluid*Test` (in `fluidRuntimeTest`, not in the 406) | - | 17 pass (4 classes) | - |
| 38 adjacent | 38 | 38 pass | - |
| chain-100 exact | 0.000e+00 | 0.000e+00 | - |

All 391 covered test names of the 406 are identical to run 156's. `runtime` = 227 tests (210 + 17).

## Reference results at 6e1c5b6 (this container: OpenJDK 21.0.10+7-Ubuntu, 4 cores)

`harness.sh all`: science 181/181, runtime 227/227, adjacent 38/38, regression 1/1; about 90 s.

```
MIXED_GAS_COST interval=5 cases=12 ms=527 newtonSolves=286 allocatedMB=134.7
LIQUID_JUNCTION interval=5 generators:0.05 m_J=9.774 kg Q(1s)=54.00 kg/s m_J/Q=0.1810 s mismatch(1s)=-0.000389 mismatch(end)=5.52e-10 perSlice=[-0.000389,-4.11e-07,-4.33e-10,5.52e-10] ms=13 newtonSolves=13
LIQUID_JUNCTION interval=5 generators:0.02 m_J=1.564 kg Q(1s)=4.829 kg/s m_J/Q=0.3238 s mismatch(1s)=-0.00172 mismatch(end)=9.16e-09 perSlice=[-0.00172,-8.21e-06,-3.90e-08,9.16e-09] ms=11 newtonSolves=13
LIQUID_JUNCTION interval=5 tanks:0.05 m_J=9.774 kg Q(1s)=0.01503 kg/s m_J/Q=650.2 s mismatch(1s)=-0.356 mismatch(end)=-0.472 perSlice=[-0.356,-0.472,-0.472,-0.472] ms=12 newtonSolves=17
LIQUID_JUNCTION interval=5 tanks:0.02 m_J=1.564 kg Q(1s)=0.01503 kg/s m_J/Q=104.0 s mismatch(1s)=-0.342 mismatch(end)=-0.0806 perSlice=[-0.342,-0.0806,-0.0806,-0.0806] ms=11 newtonSolves=17
LIQUID_JUNCTION interval=0.1 generators:0.05 m_J=9.774 kg Q(1s)=54.00 kg/s m_J/Q=0.1810 s tauFit(1.0-1.1s)=0.1809 s mismatch(1s)=-0.00288 mismatch(end)=4.10e-09 ms=45
LIQUID_JUNCTION interval=0.1 generators:0.02 m_J=1.564 kg Q(1s)=4.829 kg/s m_J/Q=0.3238 s tauFit(1.0-1.1s)=0.3239 s mismatch(1s)=-0.0189 mismatch(end)=5.42e-09 ms=47
LIQUID_JUNCTION interval=0.1 tanks:0.05 m_J=9.774 kg Q(1s)=2.740e-11 kg/s m_J/Q=3.567e+11 s tauFit(1.0-1.1s)=Infinity s mismatch(1s)=-0.427 mismatch(end)=-0.427 ms=43
LIQUID_JUNCTION interval=0.1 tanks:0.02 m_J=1.564 kg Q(1s)=3.103e-12 kg/s m_J/Q=5.040e+11 s tauFit(1.0-1.1s)=1.003e+12 s mismatch(1s)=-0.983 mismatch(end)=NaN ms=38
chain-100 vs reference: max deviation: state/moles 0.000e+00 relative (-), temperature 0.000e+00 K (-), phase fraction 0.000e+00 (-), flow 0.000e+00 relative (-)
```

chain-100: 3 accepted / 0 rejected steps, 4 Newton solves, 29 iterations, attempts 1.0/2.0/2.0 s, as run 157. The
three island fixtures print their SKIP lines (no `build/probe` snapshot), as in Gradle.

All 33 junction lines are in `reference/junction-lines.txt`. Against run 156 (`reference/junction-lines.gradle-run156.txt`)
every Newton solve count (286 at 5 s; 13/13/17/17 liquid) and every ledger value is bitwise equal except one:
`MIXED_GAS_TRANSIENT interval=0.1 key=0.02:5:false` has worstMoles 9.995189311191963E-16 / worstEnergy
2.063091102161465E-15 here against 8.616542509648244E-16 / 2.5280130406767248E-15 in Gradle (both far below the 1e-12
gate). Investigated: the test is pure science (no stand-in class loads under `-verbose:class`); the value is identical
across runs and under `-Xint`, C1-only, C2-only, `-XX:-UseFMA`, `-XX:UseAVX=0`, `-XX:UseSSE=2`; the ledger values are
libm-sensitive (disabling HotSpot's libm intrinsics changes all 12 interval-0.1 lines, disabling `exp`, `log` or `pow`
alone changes 11-12, `cos` none; a correctly rounded `cbrt` changes all 12). The Gradle numbers were measured on
JDK 21.0.11 on Windows, the harness on OpenJDK 21.0.10 on Linux, with identical sources: the difference is the
toolchain, not a stand-in. The harness therefore compares with its own capture; its header records the JDK and the
harness prints a note when the JDK differs.

## Files

- `harness.sh` - the runner.
- `stubs/` - the stand-ins (57 files).
- `reference/junction-lines.txt` - the harness's junction lines at 6e1c5b6 (compared by `runtime`);
  `reference/junction-lines.gradle-run156.txt` - Gradle run 156's, for information.
- `lib/` (git-ignored) - the jars `fetch` downloads: gson 2.10.1 (Minecraft 1.21.1's), ejml-core/ddense/dsparse 0.44.0,
  junit-platform-console-standalone 1.11.4 (JUnit 5.11.4, the build's `junit_version`).

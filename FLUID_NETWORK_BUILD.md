# Experimental fluid-network build

Minecraft 1.21.1 / NeoForge 21.1.219 / Java 21. The build remains experimental: see [acceptance status](FLUID_NETWORK_ACCEPTANCE.md) for passed checks and open release gates. Automated Minecraft MCP testing replaces the manual approval handoff.

## Build and launch

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

Correctness tests can use two parallel JVMs:

```powershell
.\gradlew.bat test -PtestForks=2 --max-workers=2 --offline --console=plain
```

`testForks` accepts 1–8 and defaults to 1. It controls test processes only; the game's solver-worker configuration is separate. Run correctness suites between timed performance benchmarks.

The production artifact is `build/libs/createcheme-0.1.0.jar`. It bundles the sequential EJML dependencies used by the sparse hydraulic solver. Minecraft MCP and the MCP compatibility adapter are separate test tooling and are excluded from this jar. The ordinary client task does not require the MCP jar.

### CPU allocation

The scheduler runs in the production game engine. In `config/createcheme-common.toml`, use:

```toml
[solver]
workers = 0
automaticWorkerLimit = 12
```

These are the current defaults. Automatic mode grows the shared solver pool's admitted parallelism when independent work is ready and reduces it after sustained lower demand. The ceiling also respects available processors minus two (minimum one). A positive `workers` value is a fixed override; existing explicit settings remain in effect. Configuration is captured when the server starts.

Free workers can process independent networks while another publication group waits. After a two-second wall-budget failure, the engine can retry a shorter complete interval, preserving all unadvanced simulation time. It does not commit a partial failed result or change EOS temperature/phase rules. See [100-network stress design and results](FLUID_NETWORK_STRESS_TEST.md).

### Material-data reloads

Reloading names/metadata with unchanged science can continue normally. Reloading different fluid physics or viscosity data puts fluid calculations on hold and cancels old proposals; inventory, energy, debt and pending transfers are retained. Restore the qualified data to resume with a full solve. Applying a replacement scientific model or energy reference to an existing save requires explicit qualification/migration. Restarting with incompatible data refuses the checkpoint rather than reinterpreting its energy.

For a fresh disposable integration-test world, use `runFluidGameTestServer -PfluidGameTestRunId=<unique-name>`; prior failed fixtures remain available for diagnosis.

### Automated qualification

Use a unique run ID for each paced benchmark, for example:

```powershell
.\gradlew.bat fluidServerBenchmark -PfluidBenchmarkRunId=module-2w-r01 -PfluidBenchmarkProfile=module --offline --console=plain
```

Keep other builds, tests and CPU-heavy applications idle during measurement. Independent correctness suites and offline analysis can run concurrently between timed benchmarks. The task records the numerical report and a separate `runtime-audit.json` under `build/reports/fluid/M9/<run-id>/`. Runtime task/drain/server/shutdown errors fail the task even when the numerical report says pass. Preserve `run/fluid-benchmark/<run-id>/logs/latest.log` with the report: `python examples/Fluid-Benchmarks.py summarize` verifies its hash and error markers, recalculates raw performance/conservation/duration gates, and checks full five-second per-island histories. Configuration or fixture-build differences form separate replicate groups. Three fresh-process replicates per profile/worker configuration, contention, and the soak remain separate gates; one successful run cannot complete M9.

Benchmark analysis is grouped in one standard-library Python tool:

```powershell
python examples/Fluid-Benchmarks.py audit <run-id>
python examples/Fluid-Benchmarks.py summarize
python examples/Fluid-Benchmarks.py stress
python examples/Fluid-Benchmarks.py memory --help
```

Use `--help` on the tool or a subcommand for paths/options. `audit` returns a failing exit code for runtime errors; stress summaries always retain `qualification: false`. Asset generation and scientific reference-data preparation remain separate tools because they have different inputs and responsibilities.

For RAM profiling, add `-PfluidBenchmarkMemory=true -PfluidBenchmarkHeapMiB=4096 -PfluidStressProfile=true` to the `stress100` benchmark. The optional heap setting fixes both initial and maximum heap (512–16,384 MiB); it affects the benchmark JVM only. One-second JVM observations and measurement timestamps are written into its report. The `memory` subcommand combines those observations with streamed JFR allocation/GC/CPU events and an optional process-RAM CSV. See [RAM protocol, commands and results](FLUID_NETWORK_STRESS_TEST.md). Normal gameplay heap defaults are unchanged; the successful unloaded-network 3 GiB probe is not a loaded-world sizing recommendation.

The existing isolated MCP test world is `run/mcp-client/saves/Fluid M8 MCP`. Launch it with `runMcpClient`; this requires the pinned MCP mod described in the progress record. It contains the former sonic-flow refusal reproduction as well as working pump/valve fixtures. Its saved pre-clamp state is retained as the portable regression fixture `src/test/resources/fluid/mcp-held-drain-checkpoint.json`.

## Available objects

| Object | Item ID | Controls / display |
|---|---|---|
| Reservoir | `createcheme:fluid_reservoir` | Pressure, temperature, volume, mass, phase volume fractions, per-phase mole composition. |
| Pipe | `createcheme:fluid_pipe` | Internal diameter in metres and roughness in metres. |
| Pump | `createcheme:fluid_pump` | Suction volumetric flow in m³/s and maximum added pressure in Pa. Direction follows block orientation. |
| Pressure-sustaining valve | `createcheme:pressure_control_valve` | Target upstream absolute pressure in Pa. Closed, regulating, or fully open state. |
| Generator | `createcheme:fluid_generator` | Composition/preset, temperature in K, absolute pressure in Pa; last-interval flow. |
| Void | `createcheme:fluid_void` | Fixed sink absolute pressure in Pa; last-interval flow. |
| Debugger | `createcheme:fluid_debugger` | Right-click a pipe for read-only net/gross directional flow, endpoint coordinates, phase history, interval quality and duration, committed time, and lag. |

Reservoirs start with a **one-time real nitrogen charge**, default 1 m³ at 298.15 K and 101325 Pa absolute. Reloading or emptying a reservoir does not refill it. The fluid is adiabatic; tank-wall thermal storage is not included. Withdrawal takes the bulk multiphase mixture. Production column/reaction equipment and selective-phase ports are outside this first implementation; V3 remains its GUI calculator.

## Known working fixture values

- **Water filling:** water generator at 298.15 K / 200000 Pa → one default pipe → nitrogen-initialized reservoir. The MCP fixture converged to approximately 200 kPa and 484.54 kg total mass; its exact regression compares against a refined trajectory.
- **Pump:** water generator → east-facing pump with 0.005 m³/s target and 500000 Pa maximum rise → default pipe → void at 101325 Pa. MCP measured approximately 4.98 kg/s.
- **Three-phase valve:** wet Tia Juana Light at 350 K / 101400 Pa → correctly oriented valve at 101375 Pa → void at 101325 Pa. MCP observed regulation near 101.38 kPa upstream. Raising the target above supply closes the valve; lowering it enough produces fully open saturation.

**Pressure drop is enabled.** Length, diameter, roughness, fitting loss, fluid properties, and elevation affect flow. A straight physical pipe chain is compressed for calculation while retaining its physical inspection mapping. The multiphase model assumes homogeneous bulk transport; it does not model phase slip, slugging, or a compressible choking solution.

**Excess velocity is capped, not rejected.** Server setting `fluid.maximumVelocityMetresPerSecond` defaults to **100 m/s** and is also limited by the current donor fluid's acoustic bound. Mass-flow capacity is density × minimum pipe area × this velocity. The coupled solver applies the same rate to every component and energy transfer. `VELOCITY_LIMITED` diagnostics identify saturation. Temperature and phase changes still follow the energy balance and existing EOS/flash. The setting is captured at server start; old inventory, energy, clocks, and historical results remain intact, while incompatible fallback anchors cannot be reused.

The server configuration starts at five seconds per hydraulic interval and can adapt between one and twenty seconds when enabled. UI rates are averages over the **last completed interval**, not instantaneous readings. `HELD` preserves the last accepted state and its time debt; it must not be read as current flow. Actual thermodynamic-domain errors can still hold a network. The former sonic-refusal fixture now advances under the velocity cap without changing event timestamps or assigning a temperature.

Registered networks continue to simulate across unloaded chunks while the server runs. Only loaded block entities receive presentation updates. Offline wall time does not create simulation debt. Fluid blocks cannot move on Create contraptions or vanilla pistons.

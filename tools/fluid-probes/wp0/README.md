# WP0 probe: the `transient100` fixture

Batch `2026-09-23-fluid-scheduling-rest`, package WP0 (counters, fixtures and baseline).

| file | what it measured | cited in | run | against |
|---|---|---|---|---|
| `probes/TransientFixtureProbe.java.txt` (class `ScratchTransientFixtureTest`, package `com.wormzjl.createcheme.runtime.fluid`) | Direct `PassiveIntervalSolver` intervals on one 20-reservoir ladder, for each generator pressure and pipe bore tried: the fill time constant (tau = gap / (dP/dt)) and the relative inventory change per 5 s interval. It chose 200 kPa and a 0.30 m bore (tau about 4,930 s), a fixture that cannot certify at eps_s 1e-9. | `FLUID_SCHEDULER_WP0_BASELINE.md` section 3.1; output `wp0-reference/transient-fixture-probe.xml` | save as `src/test/java/com/wormzjl/createcheme/runtime/fluid/ScratchTransientFixtureTest.java`, run `fluidRuntimeTest --tests "*ScratchTransientFixtureTest"`, then delete it | WP0 working tree, commit `0176080` (rebased as `eb28fc5`), 2026-09-23 |

The fixture it chose is in the tracked `FluidServerBenchmark` (profile `transient100`).

On `1401cab` (the branch head: WP5's `36caef6` and `39aabac` plus the changelog), after the whole campaign, one Gradle invocation at a time, `JAVA_OPTS=-Xshare:off`, daemon with `-Xshare:off`, no game running (26.0 to 26.6 GB free); sequence in `wp5-logs/gates.log`, logs `wp5-logs/final-gate-*.log`, script `wp5-rig/gates.sh`. Counts from the test reports (all written 2026-09-23 23:15 to 23:16 local).

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 tests, 0 failures, 0 errors |
| `fluidRuntimeTest --rerun` | 193 tests, 0 failures, 0 errors |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 accepted/rejected substeps (2, 10, 100 reservoirs) |
| `fluidSolverRegression -PfluidRegressionMode=exact --rerun` | chain-100 max deviation 0.000e+00 in state/moles, temperature, phase fraction and flow |
| `runFluidGameTestServer -PfluidGameTestRunId=wp5-final-r01` | All 28 required tests passed (scheduler self-verification on) |
| P12 / P31 fingerprints | byte-identical to the WP4 copies: SHA-256 `56332b64ea3f3bde...` and `4dcb80a40266...` (the WP0 values) |

WP5 changes no runtime behaviour: its two classes register nothing unless `createcheme.fluid.diagnostics.logTicks` is set, which no test, GameTest or benchmark sets, and its `build.gradle` switches add nothing when absent.

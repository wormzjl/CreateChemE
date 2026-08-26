# Legacy column baseline harness

Run `legacyColumnBenchmark` to measure the legacy numerical core, scientific facade, and bounded-service
queue-to-commit scopes. The Gradle task always writes an atomic JSON artifact at
`build/reports/benchmarks/legacy-column-report.json`; override it with
`-PbenchmarkReport=<path>`. Add `-PbenchmarkJfr` to capture the matching JFR profile and record its path in
the JSON report.

Examples:

```powershell
.\gradlew.bat legacyColumnBenchmark --no-daemon --console=plain
.\gradlew.bat legacyColumnBenchmark -PbenchmarkStages=64 -PbenchmarkSamples=7 -PbenchmarkWarmup=1
.\gradlew.bat legacyColumnBenchmarkHarnessTest --no-daemon --console=plain
```

`NO_CONVERGENCE` is not converted into a success. The 64-stage legacy fixture may legitimately reach the
legacy cascade's 400-sweep limit. In that case the report has `caseStatus` set to
`NO_RESULT_NO_CONVERGENCE`, `acceptedResult: false`, the public `column.solve.no_convergence` diagnostic,
and the numerical-core sweep/residual/TP-call values. Its top-level status is
`COMPLETE_WITH_UNACCEPTED_SOLVER_OUTCOMES`, meaning the measurement finished but did not manufacture a solver
result. `executionStatus: SUCCESS` means only that the bounded
worker returned a diagnostic outcome; read `outcome.solverStatus` and `outcome.acceptedResult` for solver
acceptance.

The report retains a canonical legacy-input digest even when the facade emits no result. When a result is
available, the harness verifies that this fingerprint matches the legacy projected input digest. It also keeps
raw samples, p50/p95/max timing, queue/worker time, allocation observations, TP-call counts, runtime metadata,
and any worker terminal failure rather than discarding them with transient console output.

The fixed comparison fixture submits the side draws in ascending stage order (`8`, `15`, `22`), preserving the
known legacy ordering limitation as a controlled baseline rather than concealing it.

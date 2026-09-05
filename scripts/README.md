# V3 cold core benchmark

The Java JSONL worker lives in `src/test/java/com/wormzjl/createcheme/science/column/v3/V3ColdCoreBenchmarkWorker.java`. The supervisor runs independent, bounded JVM processes; the analyzer uses only the Python standard library. Benchmark code does not alter production acceptance limits.

The completed run's frozen source trees, classpaths, manifest, and versioned harness live under `build/v3-cold-core-run-20260905-01`. To repeat those exact numerical revisions into a new output directory from PowerShell:

```powershell
$frozenRun = Join-Path $PWD 'build/v3-cold-core-run-20260905-01'
$benchmarkCp = Get-Content (Join-Path $frozenRun 'classpaths.json') -Raw | ConvertFrom-Json
$pythonExe = 'C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
& $pythonExe scripts/v3_cold_core_benchmark.py `
  --manifest (Join-Path $frozenRun 'manifest.json') `
  --run-dir (Join-Path $PWD 'build/v3-cold-core-rerun') `
  --java 'C:/Program Files/Zulu/zulu-25/bin/java.exe' `
  --baseline-classpath $benchmarkCp.baseline `
  --candidate-classpath $benchmarkCp.candidate `
  --workers 12 --phase all
& $pythonExe scripts/analyze_v3_cold_core_benchmark.py build/v3-cold-core-rerun
```

The same run directory resumes completed logical requests; use a new directory for an independent rerun. `--workers` is the combined baseline/candidate screening cap, further limited by available memory. Timing always has only one active solve. Run isolated timing and confirmations without other numerical workloads. To compare new production edits, create fresh immutable source/resource copies, hash them, compile separate revision classpaths, and record that provenance before measurement.

The recorded first execution used the versioned supervisor and analyzer copies in its `harness-source` directory. The project supervisor also includes the subsequently verified adaptive confirmation scheduling loop, so a single `--phase all` invocation finishes any newly required serial pairs before selecting timing cases.

Lightweight harness checks:

```powershell
& $pythonExe -m unittest discover -s scripts -p 'test_v3_cold_core_benchmark.py'
& $pythonExe scripts/analyze_v3_cold_core_benchmark.py --self-test
```

After the primary phases finish, capture separate JFR profiles with a fresh output directory:

```powershell
& $pythonExe scripts/v3_cold_core_jfr_profile.py `
  --benchmark-run-dir $frozenRun `
  --profile-run-dir (Join-Path $PWD 'build/v3-cold-core-profile-rerun')
& $pythonExe scripts/analyze_v3_cold_core_jfr.py build/v3-cold-core-profile-rerun
```

The profile runner uses workspace-owned JFR repository/temp directories and keeps its journal separate from primary measurements. JFR sample weights are not exact per-call allocation totals; use the worker's allocation deltas for that comparison and read the profile interpretation limits.

See `documentation/V3_COLD_CORE_BENCHMARK_PLAN.md` for the predeclared method and `documentation/V3_COLD_CORE_BENCHMARK_RESULTS.md` for observations and limitations. Those reports and the build artifacts follow this repository's existing local-output ignore rules.

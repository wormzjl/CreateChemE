# Luna benchmark review

Updated 2026-09-16 on `codex/simulation-fluid-network`. This review records two fresh module replicates and one current-artifact stress characterization. It does not declare M9 complete.

## Artifact, configuration, and commands

All runs used the production artifact `build/libs/createcheme-0.1.0.jar`, SHA-256
`8dae23fe56e0139ec0b4d00c0931fddea208bd9708c896553695057578338d3a`, Java 21.0.11, and the current property revision:

```text
fluid-trbdf2-r1:fluid-nitrogen-r1:7781afaaad17de4926015ebf321c0b106766dd5c131668f54962a8825ed25bd5:fluid-shared-k-v1:nist-liquid-calibration-20260915:k=0x1.12e0be826d695p-30:cb6773ed32b52aefb268f9c5610e288c8cecdbc6c682522b54d0be703c92b533:log-liquid-wilke-v1:dwsim-conditional-solute-v1:velocity-clamp-v1:trace-relative-v1:max=0x1.9p6
```

The module fixture has 100 reservoirs, 1,000 physical pipes, 22 components, and two islands. Its fixture-class hash is `6a92c1a64b50f2fb120c87e346169271acff567d038cb08f2d43458c7a1ba848`; its fixed-two-worker config hash is `c3c392ed7de9e94043a5643be7a7f406d2b1c15a0ee0c5e355927ce79e4cf9f9`.

Each fresh module replicate used:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat fluidServerBenchmark -PfluidBenchmarkRunId=<run-id> -PfluidBenchmarkProfile=module -PfluidBenchmarkWorkers=2 --offline --console=plain
```

The equivalent command for pooling these historical reports, after benchmark-tool consolidation, is:

```powershell
python examples\Fluid-Benchmarks.py summarize --root build\reports\fluid\M9 --output build\reports\fluid\M9-benchmark-luna.json --candidate-artifact build\libs\createcheme-0.1.0.jar
```

## Module replicates

All three runs passed `REPLICATE_PASSED`, had two islands with exactly 200 accepted `FULL` samples each, and covered ticks 2,400 through 22,400 as 200 contiguous, unique 100-tick slices per island. Each island therefore advanced exactly 20,000 ticks, or 1,000.0 simulated seconds. Measured holds and approximate intervals were zero in every run. Component and energy values below are the report's tolerance-unit values and are each below 1.

| Run | Process ID | Measured / elapsed seconds | Warmup holds | Component / energy | Worker p50 / p95 / max ms | Queue-inclusive readiness p50 / p95 / max ms | Engine p50 / p95 / max ms/tick | Module end state |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `reload-budget-module-2w-r01` | 38320 | 1000.1256311 / 1120.0795948 | 0 | 2.43466691528263e-7 / 2.0207032981933996e-10 | 108.5294 / 1146.9505 / 1521.2776 | 553.6430 / 1236.4364 / 1522.2655 | 0.0499 / 0.2059 / 1.7610 | committed 22,200; pending 0; planned capacity 1 |
| `luna-module-2w-r02` | 30380 | 1000.1157122 / 1120.0699057 | 0 | 2.43466691528263e-7 / 2.0207032981933996e-10 | 137.9349 / 1122.2193 / 1363.8734 | 548.4407 / 1142.8265 / 1365.3031 | 0.0490 / 0.2034 / 1.5231 | committed 22,200; pending 0; planned capacity 1 |
| `luna-module-2w-r03` | 44312 | 1000.2213535 / 1120.1749448 | 2 | 2.2281186887556673e-7 / 7.580587865080692e-10 | 133.9557 / 1151.9735 / 1480.3183 | 580.5944 / 1192.3058 / 1481.3749 | 0.0469 / 0.2111 / 1.8994 | committed 22,200; pending 0; planned capacity 1 |

The r03 warmup holds are retained as evidence; no measured interval was held. All runtime audits are `PASS` with zero matched server-task, drain, unexpected-server, crash, or stopped-with-fault patterns. The fresh process IDs are distinct. The captured Gradle logs are `build/fluid-reload-module-2w-r01.log`, `build/luna-module-2w-r02.log`, and `build/luna-module-2w-r03.log`.

Report and runtime-audit hashes:

| Run | Report SHA-256 | Runtime log SHA-256 | Captured build log SHA-256 |
|---|---|---|---|
| `reload-budget-module-2w-r01` | `0fa167ab9d30d034cb4043e6ef09a06a063e28ef6e149b809d9541bf4bc66fc3` | `84b2f3035576199ef990c7b827dc8aa4ae0bdd02acbd0a5e885f81bfdf0fbe1f` | `32a4f49b6f238452cde6eb103770bd2b6792f7a36f697b13102e4745121c2842` |
| `luna-module-2w-r02` | `09a5e405fd245b458e695ad4a350f47ef1a6fd93c338daa4066464da4080fc3d` | `88270d1ed71b260ab5aa614b250d4868fd0519e7b56f18d3e0b259f2554a79dc` | `2af523c803b3f9b21ea6a34dc71ec7cd58dcce6e509a7dff0f62f57032857406` |
| `luna-module-2w-r03` | `74c08fe3c901ed6ac85725cac1f8868346610bfd7a8d9259afe9c048631724e9` | `b0fcb1710d2a21ffa695cd2a77647fb068805385b32cdab38220778525c1dc7e` | `9d5503e442f69f0a175f2afeb20c88347111b3b40bf9ba3d8e361f4bdce6586b` |

The audit `reportSha256` and `logSha256` fields match the corresponding raw files for every row. The aggregate artifact is `build/reports/fluid/M9-benchmark-luna.json`, SHA-256 `3ec1943a6326f2a5c63b00ad4841e12796ab2226464c1229c705a358bb71f50b`. It reports one matching module group with three replicates and three distinct processes. Pooled metrics over 1,200 completion samples and 60,003 raw engine samples are:

```text
worker milliseconds:                 p50 137.9349, p95 1139.0817, max 1521.2776
queue-inclusive readiness millis:    p50 560.1992, p95 1192.3058, max 1522.2655
engine milliseconds per tick:        p50 0.0489,   p95 0.2053,    max 1.8994
```

The module group satisfies its three fresh-process repetitions for the fixed-two-worker/module cell. The aggregate status is still `INCOMPLETE`: one/many profiles, one-worker and automatic-worker groups, contention repetitions, soak, and other M9 gates remain absent. Planned capacity remains 1 and pending transfer 0 at the end of each module run; this is not a quiescent P52 soak result.

## Current-artifact stress characterization

Run `luna-stress100-12cap-r01` used:

```powershell
.\gradlew.bat fluidServerBenchmark -PfluidBenchmarkRunId=luna-stress100-12cap-r01 -PfluidBenchmarkProfile=stress100 -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=120 -PfluidStressProfile=true --offline --console=plain
```

The stress manifest config hash is `b402ffed27993f9aba7efcff3e54f73adc5a965a2af3cbd02c36b61889c0b16e`; the fixture hash and artifact hash match the module runs. Process ID was 41564. The run completed in 180.3460124 wall seconds with 1,200 warmup ticks and 120.0009519 measured seconds. It resolved automatic sizing to 12 workers on a 16-processor host, reached a maximum of 12 allocated/active workers and 12 outstanding jobs, advanced all 100 islands and 1,993 reservoirs, and ended with zero loaded fixture chunks. `stressIntegrityPassed=true`, runtime audit `PASS`, and `gatesPassed=false` because the ordinary report gates capture the actual holds and latency/engine timing conditions in this run; stress integrity is a separate characterization result. The stress report SHA-256 is `4833ed2f44ca557719a2e01455de1f8fd86467ac15dceaf00698114c37d79fd9`; its runtime log SHA-256 is `2874e678007e21625dcdf8bbb3be8f9525bedad56221d764372a5c16ac740586`, and the captured build log SHA-256 is `844c2fefd220af8ee4ad76598dfeacb5bbc259792261940e1e16830324216925`. The runtime audit records those report/log hashes and zero error matches.

Stress results:

```text
warmup holds / measured holds / approximate intervals: 291 / 273 / 0
component / energy tolerance units:                  3.974425307567616e-7 / 1.8585763165019154e-9
completed intervals per second:                       31.133086370125703
equivalent five-second intervals per second:          28.71877218833962
aggregate realtime ratio:                              1.4359386094169808
final debt seconds:                                    median 2.0, p95 4.5, max 4.5
engine work milliseconds per tick:                     p50 0.3773, p95 1.9872, max 18.3643
```

The 273 measured holds are distributed by dispatch tick as 146 in 1,200–1,800, 99 in 1,800–2,400, 28 in 2,400–3,000, and zero in 3,000–3,600. The stress summary (now generated by `python examples/Fluid-Benchmarks.py stress`) is `build/reports/fluid/stress100-summary.json`, SHA-256 `877416bc1f5131052f4ba499988732f3a9be4e0a0c00fe886bb4604a0ba26cbd`; it includes this `luna-stress100-12cap-r01` row and seven total stress reports. This recorded hash belongs to the original analysis artifact; rerunning with a later candidate JAR changes candidate-match metadata.

The explicitly observed final 1,200-tick tail ended at tick 3,640. It contained 28 holds and zero approximations. Among tail samples with a readiness value, p95/max queue-inclusive readiness were 113,975.0778 / 155,476.8012 ms. The last 1,200 raw engine samples had p95/max 1.2688 / 5.6292 ms/tick. Thus the tail shows recovery of debt and engine cost while retaining residual holds and very large queue-inclusive latency; it does not establish steady zero-hold behavior.

The final hold in that 1,200-tick window published at tick 3,018. Secondary windows are clean but are reported only as context: the last 400 ticks contain 400 records, zero holds, readiness p95/max 298.2031/341.7619 ms, and engine p95/max 1.2137/5.6292 ms; the last 200 contain 200 records, zero holds, readiness p95/max 300.5289/341.7619 ms, and engine p95/max 1.3909/5.6292 ms. These short windows cannot replace the whole-run or required 1,200-tick tail evidence.

The JFR is `build/reports/fluid/M9/luna-stress100-12cap-r01/stress.jfr`, 71,799,472 bytes, SHA-256 `cf104fd357c6d6f8a80d89698058b8334292dce10cbdbf5e2c74972a8f87f05`. Its 158 `jdk.CPULoad` samples show mean JVM user CPU 42.6332%, mean JVM system CPU 1.8207%, mean machine total 78.9905%, and machine-total maximum 100%. The gap between mean machine total and JVM user-plus-system load indicates host activity outside this JVM and is a comparability confound. The recording contains 401 `jdk.GarbageCollection` events and 395 `jdk.G1GarbageCollection` events; event counts alone do not establish that GC caused the hold/timing regression. The report's mean worker CPU occupancy, 0.204729278762774, is retained as a worker-accounting lower bound; it is not a direct process CPU-seconds measurement.

For context, `stress100-final-12cap-long-r01` used the older artifact `e4675976a69d6d40a2e24e2da70d501d275c7ccbb6e357b52e8321e71c635f67`, also used stress profiling/JFR and approximately 120 measured seconds. It recorded 248 warmup and 13 measured holds, equivalent throughput 22.321388878019402 intervals/s, engine p95/max 1.064699/5.438698 ms/tick, mean active workers 5.0333, worker occupancy lower bound 0.1255811, and final debt p95/max 4.65/4.65 s. Both runs used JFR, so the increased current-artifact hold count cannot be attributed to JFR from these records alone. The current run has higher throughput and active-worker occupancy but materially higher startup/recovery holds and engine spikes; no causal explanation is inferred from this comparison.

Stress evidence is separate from ordinary M9 qualification and does not qualify a zero-hold run, a quiescent soak, or M9 completion.

## Remaining gaps

- Ordinary M9 repetitions remain missing for `one` and `many` profiles at two workers, all one-worker cells, and all automatic-worker cells.
- Contention repetitions, aggregate-load cadence classification, the 30-minute soak, and remaining acceptance matrix rows remain open.
- P08/P21 finite-source evidence remains unresolved: the focused test stops after approximately 0.11 s at 55.9% stock because the numerical state is refused. This is not a qualified near-empty operation.
- The stress run demonstrates automatic twelve-worker admission, recovery, and integrity under load, but its 291 warmup and 273 measured holds and its tail latency spikes must remain visible in any release review.

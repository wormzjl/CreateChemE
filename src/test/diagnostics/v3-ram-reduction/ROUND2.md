# Further allocation reduction: default and perturbations

This pass on `codex/solver-ram-reduction` compares against the **already optimized
branch before this pass**, including the earlier Jacobian-copy and structural
matching improvements. It does not compare against the original `main` baseline.

## Measured results

Across both JVM pairs, all thirteen cases allocate **13.4–14.9% less per solve**.
Paired median runtimes are **0.8–9.2% lower**, with no case-median regression in
either pair. This is a measured range, not a guarantee for every individual solve.

For the default, allocation falls from **1953.6–1974.6 MiB to 1681.1–1682.0 MiB**,
saving 272–293 MiB of temporary allocations per solve. Runtime improves from
1257.8 to 1159.6 ms in pair one, and from 1275.7 to 1265.0 ms in pair two. The
second candidate JVM is slower than the first despite similar allocation; retain
that run-to-run variability when interpreting the timing result. Five warmups
do not eliminate all JVM or host variability. The second default pair's CPU-time
medians are equal at the timer's resolution.

The full second pair is shown below; arrows mean pre-pass baseline to candidate.
Both pairs' per-case runtime, CPU-time and allocation medians are retained in
`round2-comparison.json`.

| Case | Allocation MiB/solve | Less allocation | Runtime ms/solve |
|---|---:|---:|---:|
| Default | 1974.6 → 1682.0 | 14.8% | 1275.7 → 1265.0 |
| Feed -2% | 2363.5 → 2018.8 | 14.6% | 1510.4 → 1381.1 |
| Feed +2% | 2096.7 → 1785.6 | 14.8% | 1323.4 → 1285.6 |
| Feed temperature -2 K | 2028.7 → 1727.2 | 14.9% | 1277.3 → 1242.1 |
| Feed temperature +2 K | 2379.6 → 2036.4 | 14.4% | 1534.2 → 1491.0 |
| Pressure -2% | 2269.9 → 1937.7 | 14.6% | 1439.5 → 1400.5 |
| Pressure +2% | 1976.3 → 1682.8 | 14.9% | 1245.5 → 1214.4 |
| Reflux -2% | 2489.9 → 2129.9 | 14.5% | 1597.2 → 1564.9 |
| Reflux +2% | 2099.8 → 1787.6 | 14.9% | 1318.5 → 1268.5 |
| Steam -2% | 2211.5 → 1890.0 | 14.5% | 1413.7 → 1371.4 |
| Steam +2% | 2330.1 → 1994.7 | 14.4% | 1576.4 → 1470.9 |
| Cooling duties -2% | 2102.1 → 1790.4 | 14.8% | 1406.2 → 1283.4 |
| Cooling duties +2% | 2026.1 → 1726.1 | 14.8% | 1281.0 → 1237.9 |

All **364 measured timing outcomes** agree exactly across variants and repetitions.
The complete regression suite passes **499 tests**, with zero failures, errors or
skips. The two added state-ownership tests check constructor isolation, unchanged
bits including signed zero, independence of later perturbations, and rejection of
malformed, non-finite, negative and absent-phase flows on both construction paths.
Existing Jacobian-oracle, phase-support and wet-tray tests remain passing.

### Heap and resident memory

All **30 heap-capped measurements** also match the timing baseline's streams,
diagnostics and convergence certificates exactly, with passed audits. The total
for this pass is **394 successful measured outcomes**.

| Case / heap cap | Sampled heap peak MiB | Sampled resident peak during measurements MiB | Lifetime resident peak MiB |
|---|---:|---:|---:|
| Default / 128 MiB | 126.4 → 126.4 | 246.5 → 253.2 | 261.2 → 253.5 |
| Default / 512 MiB | 173.7 → 154.3 | 350.4 → 293.1 | 367.1 → 302.7 |
| Reflux -2% / 128 MiB | 125.9 → 126.9 | 243.5 → 247.6 | 258.5 → 255.0 |
| Reflux -2% / 512 MiB | 207.2 → 180.7 | 353.2 → 328.2 | 377.3 → 457.0 |
| Reflux -2% / 512 MiB, repeat | 184.7 → 145.6 | 306.4 → 269.1 | 455.5 → 491.3 |

Used heap after full collection stays near **18.6 MiB** in both variants. At the
512 MiB cap the candidate has lower sampled heap and resident peaks during the
measured loops. At 128 MiB, heap peaks are essentially cap-limited and measured
resident use is slightly higher. These runs do not establish a smaller minimum
viable heap.

**Resident-process peaks are not uniformly reduced.** The larger lower-reflux
candidate lifetime peaks recur in the confirmation pair. In both pairs those
peaks were already recorded before the measured loop, after the three warmup
solves. They remain real process peaks and are not discarded. Native-memory
summaries taken after collection do not identify their cause; attributing the
whole difference to JIT compilation would go beyond the measurements. This pass
therefore demonstrates lower allocation and lower sampled heap at the larger cap,
with unchanged numerical behavior and no measured case-median slowdown, but does
not promise lower worst-case Windows resident RAM.

## Changes

* The coordinate decoder transfers its freshly allocated arrays into a fully
  validated state, avoiding another copy of every flow row, temperature and
  free-water vector. Ordinary constructors still copy caller arrays. The state
  exposes scalar reads only, and later perturbations retain independent values.
* Fugacity validation uses a primitive loop. Residual construction relies on
  `List.copyOf` for the existing null-element rejection. Stage-layout validation
  uses indexed scans instead of repeatedly creating sublists, streams and lambdas.
  All finite-value, shape, support and phase checks remain.
* Local Jacobian assembly resolves thermodynamic equation indices once per
  assembly, then uses integer indices inside the component-by-coordinate loop.
  Duplicate-ID and missing-energy-row checks remain, as do absent VLE/water rows.

Arithmetic expressions, differentiation steps, pivoting, continuation policy,
convergence tolerances, final correction checks and public snapshots are unchanged.
This pass does not introduce retained LU buffers or primitive residual storage.

## Benchmark design

The reference is the production fresh-block preset returned by
`ColumnCalculatorV3BlockEntity.literatureCduInput()`: 40 trays, feed on tray 37,
365 C, 250 kPa, reflux ratio 4.17, three side draws, sump steam and three
pumparound cooling duties.

Thirteen cases are benchmarked. Twelve perturbations change **one factor at a
time**, in both directions:

| Factor | Perturbation |
|---|---|
| All feed component rates | -2%, +2%; composition unchanged |
| Feed temperature | -2 K, +2 K |
| Top pressure | -2%, +2%; tray pressure drop unchanged |
| Organic reflux ratio | -2%, +2% |
| Steam feed rate | -2%, +2%; steam temperature unchanged |
| All pumparound duties | -2%, +2%; locations/splits unchanged |

The benchmark calls the production calculator serially, outside a game world.
Trace truncation is off, closure tolerance is `1e-8`, and each call has the default
45-second deadline. Each fresh timing JVM uses `-Xms512m -Xmx2g`, five warmups
then seven measured calls **per case**. Profiling and native-memory tracking are
disabled for timing. Four JVMs run serially in baseline/candidate/baseline/candidate
order; the full regression suite runs between the first baseline and candidate,
never concurrently with measured solves.

The baseline's six affected classes were frozen before their edits and placed
first on its runtime classpath. The reproducible source generator checks the
snapshot against commit `8547feaeffbda5daf2ee9569bbd37d566d46e447` plus the two
earlier assembler ownership-factory changes. The remaining earlier RAM changes
are shared by both variants. A source-hash manifest accompanies the frozen files.

`compare-round2.cjs` compares every recorded input, stream, duty, diagnostic,
audit check and convergence certificate exactly, without tolerance-based matching.
It also requires successful outcomes, passed audits, a fresh final Newton step,
linear backward error at most `1e-12`, log-flow change at most `1e-8` and an
accepted temperature correction. It retains the preset's advisory water-dew-point
behavior rather than suppressing it.

Separate heap-capped JVMs use G1 and native-memory tracking with three warmups and
three measurements, for both the default and lower-reflux perturbation at 128 and
512 MiB. A second lower-reflux/512 MiB pair checks the resident-peak anomaly.
Allocation is cumulative temporary allocation per solve; sampled used
heap, resident-process peak and heap after full collection are different metrics.

## Reproduce

From the repository root in PowerShell, using the existing Java/Gradle setup:

```powershell
.\src\test\diagnostics\v3-ram-reduction\prepare-round2-baseline.ps1
.\gradlew.bat test
.\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/round2.init.gradle v3RamRound2 '-PramVariant=baseline' '-PramReport=build/reports/v3-ram-reduction/round2-baseline-1.json' --no-configuration-cache
.\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/round2.init.gradle v3RamRound2 '-PramVariant=candidate' '-PramReport=build/reports/v3-ram-reduction/round2-candidate-1.json' --no-configuration-cache
```

Repeat both variants with report suffix `-2`. Then run
`node src/test/diagnostics/v3-ram-reduction/compare-round2.cjs`.

For the memory runs use task `v3RamRound2Memory`, `ramCase=Default` or
`DefaultRefluxLow`, `ramHeap=128m` or `512m`, and both variants. Name reports
`round2-memory-<case>-<heap>-<variant>.json` in the same report directory. Validate
with `node src/test/diagnostics/v3-ram-reduction/compare-round2-memory.cjs`, after
also repeating the lower-reflux/512 MiB pair with suffix `-repeat` before `.json`.

Raw timing, memory and comparison JSON files are under
`build/reports/v3-ram-reduction/round2-*`; logs are `build/ram-round2-*.log`.

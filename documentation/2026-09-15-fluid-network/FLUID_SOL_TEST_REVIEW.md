# Sol fluid-network test review

Updated 2026-09-16 on `codex/simulation-fluid-network`. This is a testing note, not a release-qualification record. The acceptance record remains authoritative for M9 scope.

## P12 worker-count trajectory equivalence

`WorkerTrajectoryEquivalenceTest` runs the production `BoundedCpuSolveService` with fixed one worker, fixed two workers, and demand-based admission with a configurable ceiling of twelve. Twelve independent owners are outstanding before owner-thread draining in the automatic case. This is bounded admission/ownership evidence; it does not claim that twelve kernels overlapped in wall time.

The fixture contains closed nitrogen, water, and the existing 22-component TJL wet-crude + nitrogen + water mixture. The wet-crude state is asserted to be multiphase. Every run commits at the same simulation timestamps (20, 40, and 60 ticks), records canonical inventory and derived thermodynamic state, average flow, and accepted/rejected substep counts, and compares them bit-for-bit across worker configurations. Component and gravity-inclusive energy balances use the shared BAL tolerances.

Focused command:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test --tests 'com.wormzjl.createcheme.runtime.fluid.WorkerTrajectoryEquivalenceTest' --offline --no-daemon --console=plain
```

Result: **PASS**, one test, zero failures/errors, 0.459 s in the JUnit report. The test source is `src/test/java/com/wormzjl/createcheme/runtime/fluid/WorkerTrajectoryEquivalenceTest.java`.

## Property-reload validation

The initial reload regression passed **786 tests**. A fresh disposable GameTest world (`reload-qualification-r02`) then passed **all 13 required GameTests** in 3.008 s. The new live-world case observes the real between-tick completion-routing entry point: incompatible scientific data holds before old completion routing, committed stock/time remain unchanged while online debt accrues, saved data cannot be decoded under the incompatible model, the fallback anchor remains ineligible, and restoring the exact qualified science resumes with a FULL five-second interval.

Evidence log: `build/fluid-reload-gametest-r02.log` (`BUILD SUCCESSFUL`, 13/13 required GameTests).

The current suspension path preserves the existing maximum retry span: `IslandCoordinator.closeRound` applies deadline shortening only when `suspension == null`. An earlier review concern about administrative cancellation shortening the next interval was based on a stale source read and is retracted.

## Completion-budget defect and regression

Run `reload-qualification-contention-2w-r01` produced a nominal green report on artifact SHA-256 `0a006d83d027ac8545282287dcfacf44ae0cd35d1075503254d13546a1faffce`:

- two real CPU kernels: 45.0009 s wall each, 44.83 / 44.86 CPU-s;
- maximum/final debt: 935 / 35 ticks;
- maximum outstanding/ready jobs: 2 / 0;
- measured and warmup holds: 0 / 0; approximate intervals: 0;
- engine p95/max: 0.1165 / 3.2699 ms per tick;
- component/energy errors: `4.218e-7` / `1.624e-9` tolerance units.

This run is **rejected as qualification evidence**. Its log contains 20 owner-thread task failures with `IllegalArgumentException: maximum must be positive`. After 64 completions had drained in a server tick, `ProcessSolveServices.drainCompletions` passed a zero remaining budget to `BoundedCpuSolveService.drainCompletions`, whose public contract requires a positive maximum. The JSON gate does not count router task exceptions, so `CONTENTION_PASSED` / `gatesPassed=true` is insufficient here.

Preserved evidence:

- `build/fluid-reload-contention-2w-r01.log`
- `build/reports/fluid/M9/reload-qualification-contention-2w-r01/report.json`

The fix validates the public maximum, returns without calling the bounded service when the per-tick remainder is zero, and leaves terminals queued for the next tick. `FluidCompletionBudgetGameTests` exercises the actual server and public `ProcessSolveServices` boundary: exactly 64 callbacks route in one held-open tick, terminal 65 remains outstanding/pending through three further same-tick public drain calls, and all 70 jobs finish on later ticks without exceeding 64 callbacks in any tick or leaking ownership.

Final fixed-artifact validation passed **787 unit tests** with zero failures/errors/skips and **all 14 required GameTests**. The fresh-world GameTest log contains no server-task or zero-budget exception. Evidence: `build/fluid-reload-budget-gametest-r03.log` and `build/fluid-sol-final-unit.log`.

## Fixed-artifact two-worker contention

Fresh run `reload-budget-contention-2w-r02` passed on artifact SHA-256 `8dae23fe56e0139ec0b4d00c0931fddea208bd9708c896553695057578338d3a`:

- runtime audit `PASS`, zero matched server errors, and report/artifact hashes agree;
- two real CPU kernels: 45.001 s wall each, 44.89 / 44.94 CPU-s;
- maximum/final debt: 945 / 45 ticks;
- maximum outstanding/ready jobs: 2 / 0; bounded queues pass;
- measured and warmup holds: 0 / 0; approximate intervals: 0;
- engine p95/max: 0.1199 / 4.2363 ms per tick;
- component/energy errors: `4.218e-7` / `1.624e-9` tolerance units.

Evidence: `build/fluid-reload-budget-contention-2w-r02.log`, `build/reports/fluid/M9/reload-budget-contention-2w-r02/report.json`, and its sibling `runtime-audit.json`.

## Fixed-artifact module replicate

Full run `reload-budget-module-2w-r01` passed on the same `8dae23fe...` artifact after 2,400 warmup ticks and 1,000.126 measured seconds:

- runtime audit `PASS`, zero server-task/lifecycle errors, report/artifact hashes agree;
- both islands record exactly 200 measured samples, all accepted and `FULL`;
- every measured slice is exactly 100 ticks; each island is contiguous from tick 2,400 to 22,400 with no gap, overlap or duplicate and sums to 20,000 simulated ticks;
- zero measured/warmup holds and zero approximations;
- p95 worker / readiness-to-publication / engine work: 1,146.95 / 1,236.44 / 0.2059 ms; maxima 1,521.28 / 1,522.27 / 1.761 ms;
- component/energy errors: `2.435e-7` / `2.021e-10` tolerance units;
- module committed tick 22,200; no pending transfer and one planned-capacity record remain.

The offline aggregate validator accepts the real report. A copy with the first slice shortened by one tick is rejected with `Ordinary sample is not a complete five-second interval`. Evidence: `build/fluid-reload-module-2w-r01.log`, `build/reports/fluid/M9/reload-budget-module-2w-r01/report.json`, its sibling `runtime-audit.json`, and `build/reports/fluid/M9-benchmark-sol.json`.

This is one fresh-JVM module replicate. It does not satisfy the three-repetition or other worker-count groups required by M9.

## P31 cadence trajectory subset

`CadenceTrajectoryQualificationTest` passed for closed nitrogen, water, and multiphase 22-component wet crude. It compares fixed 1/5/20-second intervals and a production `IslandClock` path with controlled injected CPU observations against TR-BDF2 step-doubling references with 0.5/0.25-second ceilings and `1e-5` tolerance. Observed adaptive cadences were 100, 112, and 125 ticks.

Across all named cases and 20/40/60/80-second checkpoints, maxima were:

- pressure relative error: `1.186154446931e-4`;
- temperature absolute error: `1.763470327205e-4 K`;
- phase-volume-fraction error across vapor, hydrocarbon liquid, and free water: `9.707268322501e-11`;
- integrated pipe-mass absolute error: `2.801388324597e-5 kg`.

Every interval also checks component/energy conservation and nonnegative stock. Evidence: `build/reports/fluid/P31-cadence-trajectories.json`. This qualifies the physical fixed/adaptive subset under injected CPU samples; it does not qualify the aggregate-load CPU classifier or performance behavior.

## Stress-evidence interpretation

The saved final stress artifact `e4675976...` is older than the current reload artifact. Its long run supports stress integrity/recovery: all networks advanced, the worker/outstanding ceiling stayed at twelve, useful throughput was 22.321 five-second-equivalent intervals/s, aggregate real-time ratio was 1.116, balances passed, and the recorded tail recovered to zero holds/approximations. It does not qualify the current artifact or ordinary/module M9.

Two interpretation points matter when citing the matched baseline comparison:

- Baseline and final reports share config hash, seed, topology description, reservoir/pipe counts, property revision, and compressibility. I decoded the gzip NBT `world/data/createcheme_fluid_core.dat` from `stress100-baseline-auto-r01` and `stress100-final-12cap-matched-r01`, parsed the UTF-8 JSON in `data.Topology`, and canonicalized the `active` and `constructed` values as compact JSON with recursively sorted object keys. The saved physical registries are exactly equal: 11,303 active registrations with canonical SHA-256 `fa440a8830e4dd9947d07bbbf8e08483ca737f473ec0877ce8e2607766c5055a`; constructed ledgers match at `12dfbe6f9943f14770d10f8ecef194a35b040cf4893bba3f64323a8ec9e12914`. This establishes the same canonical physical fixture for the prior combined-engine comparison even though `fixtureClassesSha256` differs because the diagnostic harness bytecode evolved. It is not stress qualification for the newer `8dae23fe...` artifact.
- The long-run one-second samples record `workerLimit == 12` throughout measurement. They verify the ceiling and activity/recovery at that ceiling, not the grow/shrink transition. Growth/shrinkage remains deterministic unit evidence.

## Remaining limits

- M9 ordinary/module qualification still needs the specified repetition and worker-count matrix; the module result above is one replicate.
- P31 still needs aggregate-load/classifier evidence; injected CPU observations only exercise the clock policy deterministically.
- The 30-minute soak, remaining phase/topology matrix items, and other partial acceptance rows remain open as recorded in `FLUID_NETWORK_ACCEPTANCE.md`.
- No result here weakens EOS/flash, 100 m/s plus acoustic velocity clamp, nitrogen initialization, shared compressibility/material viscosity, conservation, or full-only recovery gates.

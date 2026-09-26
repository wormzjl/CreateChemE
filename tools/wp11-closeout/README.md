# wp11-closeout

**Purpose.** The scripts, probe and outputs of P3 WP11 (close-out of stage P3: the engine follow-ups of WP7d, the
chain-100 re-capture, the full gates, the benchmark pair, the tooling cleanup). Nothing here is tracked or referenced
by the build. Report: `documentation/2026-09-24-coolprop-low-temperature/P3_PILOT_ENGINE_REVIEW.md`.

**Batch.** `2026-09-24-coolprop-low-temperature`, stage P3, WP11. Commits `a3716ed` (engine follow-ups), `ac6ebc1`
(chain-100 reference), `8a10bfd` (detachment), `7afa990` (changelog).

## Contents

- `gradle-run.sh <log> <gradle args>`: one Gradle invocation under `build/gradle.lock` (tag `wp11`), from Git Bash with
  `JAVA_OPTS=-Xshare:off --offline`; it waits, polling every 60 s, while a dev client runs (a `java.exe` command line
  with `runMcpClient` or `fml.modFolders`) or another agent holds the lock, and deletes the lock afterwards, also on
  failure. Logs go to `build/wp11/`; the ones of this package are copied to `out/gradle-logs/`.
- `run.sh <test classes>`: javac of the WP11 main sources (phase package, `FluidThermodynamics`, `PassiveStepSolver`,
  `PassiveIntervalSolver`) and the phase, qualification and network test sources against the last Gradle compile, and
  the reflective runner of `tools/g3-qualification/RunTests.java`; `MAIN=<class> EXTRA=<source>` runs a probe. No Gradle.
- `src/Wp11ThreePhaseProbe.java` (package `science.thermo.phase`): N2/C2H6 at 115 to 130 K, 2 to 3.5 MPa, x_N2 0.85 to
  0.95 on the pilot network contract; found that the brief's three-phase state at x_N2 exactly 0.95 is inside the band's
  pure-fluid rule (typed `CRITICAL_BAND`) and that x_N2 0.93 to 0.9499 at 125 K and 3 MPa is the `UNSTABLE_PRODUCT` (three-phase) case outside it,
  and that 115 to 125 K is a wide `LIQUID_LIQUID` region. Output `out/three-phase-probe.txt`. Run:
  `EXTRA=tools/wp11-closeout/src/Wp11ThreePhaseProbe.java MAIN=com.wormzjl.createcheme.science.thermo.phase.Wp11ThreePhaseProbe bash tools/wp11-closeout/run.sh`.
- `results.js <task> ...`: sums `build/test-results/<task>/*.xml` (classes, tests, failures, errors, skipped) and lists
  failing and skipped cases.
- `chain-deviation.js <old.json> <new.json>`: per-quantity max and mean deviation between two solver-regression
  references with the harness's metrics; output `out/chain-100-deviation.md` (old `out/chain-100-before-wp11.json` =
  `git show 3c84036:src/test/resources/fluid/regression/chain-100.json`, new `out/chain-100-wp11.json` = `ac6ebc1`'s).
- `bench-compare.js <a/report.json> <b/report.json> [labels]`: side by side of two paced `FluidServerBenchmark` reports;
  output `out/bench/compare-f1-vs-head.md` (F1's `stress100-f1-on-r02` report from the main checkout's
  `documentation/2026-09-23-fluid-followups/f1/f1-logs/paced/reports/`).
- `jfr-window.js <dir> <from> <to> <cores>`: process CPU in cores, collections and heap after GC over a window, from
  `jfr print --json` exports of the benchmark's `stress.jfr`; output `out/bench/jfr-window.txt`.
- `out/`: the Gradle logs of every WP11 run (`gradle-logs/`), the test-result summaries before and after the cleanup,
  the javac run of the phase and qualification tests, the chain-100 references and deviation table, P12/P31
  fingerprints (from the full `test` run and the targeted run; equal), and the benchmark run
  (`bench/stress100-p3-wp11-r01/` with `report.json`, `runtime-audit.json`, `stress.jfr`; the console log; the JFR
  exports and the comparison).

## Order of the Gradle runs (2026-09-25, all on this worktree, no dev client, one at a time)

`step0-suites` (science.thermo.*, fluidScienceTest, fluidRuntimeTest on the follow-ups), `regression-1-declared-old`,
`regression-2-capture`, `regression-3-exact-new`, `regression-4-declared-new`, `gate-1-test-full`,
`gate-2-regression-exact`, `gate-3-gametest` (fresh world `run/fluid-gametest-wp11-p3-20260925`), `gate-4-p12-p31`,
`bench-stress100-p3-wp11-r01` (fluidServerBenchmark stress100, 60 s + 60 s), then after the detachment
`after-1-suites` (test, fluidScienceTest, fluidRuntimeTest), `after-2-regression-exact`, `after-3-gametest` (fresh world
`run/fluid-gametest-wp11-p3-after-cleanup-20260925`).

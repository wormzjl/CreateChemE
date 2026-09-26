# `fluidSolverRegression` re-recorded on the current fluid basis

Date 2026-09-22. Branch `claude/fluid-regression-rerecord` from `main` @ `3c27271`. Worktree
`.claude/worktrees/solid-phase-fluid-system-plan-4369b0`.

## Why it failed

`gradlew fluidSolverRegression` failed on `main` and on every branch with

```
java.lang.IllegalArgumentException: Fluid basis mismatch
    at FluidThermodynamics.flashTP
    at SolverRegressionHarness.cosineChain(SolverRegressionHarness.java:126)
    at FluidSolverRegressionTest.fixtures(FluidSolverRegressionTest.java:89)
```

`SolverRegressionHarness.cosineChain` built the chain-100 composition as
`Arrays.copyOf(<tjl20_methane feed>, 22)` with `[20]=0.1` and `[21]=0.2` — the literal width of the
basis when the harness and its references were recorded at `154007d`. The configured network
package (`FluidPresetCatalog.NETWORK_PACKAGE` = `createcheme:tjl20_methane_nitrogen`) has since
changed width: the model now has **21** conserved components (20 hydrocarbons + water), so
`flashTP` refused the 22-wide vector before any fixture was built. The reference file itself was
also 22 wide. No solver code is involved; the harness was pinned to a basis size.

`FluidNetworkBenchmarkTest` on `main` still builds its chain against the `createcheme:tjl20_methane`
package with the same literal, which is why the benchmark kept working while the harness did not.
(The solids branch `claude/solid-phase-gui` already rewrote the benchmark by component name; the
harness change below is the same form.)

## What changed

- `src/test/java/com/wormzjl/createcheme/fluid/benchmark/SolverRegressionHarness.java` —
  `cosineChain` now allocates `model.componentCount()` entries and places the Tia Juana Light +
  methane assay by component name (`model.components().indexOf(crude.componentBasis().componentId(c))`),
  then sets `Nitrogen` to 0.1 and `Water` to 0.2 mole. Nothing depends on the basis width any more.
  Class javadoc updated with the capture command.
- `src/test/resources/fluid/regression/chain-100.json` — re-captured on the current basis with
  `gradlew fluidSolverRegression -PfluidRegressionCapture=true` (this is the recording procedure:
  the Gradle property maps to the `fluid.regression.capture` system property the test reads).
  One 5 s interval, 100 nodes, 21-wide mole vectors, 37 accepted / 1 rejected substeps.

## What could not be re-recorded

The three island fixtures (`quiet-11312`, `quiet-11324`, `cold-11312`) replay islands 11312 and
11324 of a stress-world snapshot expected at `build/probe/core.dat` and `build/probe/core-fallback.dat`
(or `-PfluidRegressionSnapshot=<dir>`). No such snapshot on the current basis exists on this
machine: the only copies (`.claude/worktrees/fluid-perf-probe/build/probe/`) date from `154007d` and
are on the old 22-wide basis, and the GameTest worlds do not contain those island ids. The test
skips these fixtures when the snapshot is absent (`Fluid solver regression SKIP …`), so the task
passes without them.

Their reference files `quiet-11312.json`, `quiet-11324.json`, `cold-11312.json` are still the
`154007d` captures (22-wide) and can never match a current-basis snapshot; if one is ever supplied
they will fail with `component count 22 -> 21`. **They should be deleted** so that a future snapshot
produces the explicit `missing reference … (capture with -Dfluid.regression.capture=true)` failure
instead; the deletion was not performed in this session (blocked by the tool policy on deleting
tracked files) and is left for the maintainer:

```bash
git rm src/test/resources/fluid/regression/quiet-11312.json src/test/resources/fluid/regression/quiet-11324.json src/test/resources/fluid/regression/cold-11312.json
```

Re-recording them needs a current-basis stress world with those islands (the M9 pilot world
`run/fluid-benchmark/pilot-module-warm-01`, which is also absent here).

## Meanwhile

Until this landed, reviewers used `SolidChainTransportTest.clearChainSubstepCountsAreUnchanged`
(25/10, 32/14, 18/5) and the `fluidNetworkBenchmark` substep counts (30/11, 19/14, 37/3) as
bit-identity substitutes. The re-recorded chain-100 fixture restores the real gate:
`-PfluidRegressionMode=exact` against this reference is bitwise.

## Verification (one Gradle invocation at a time, nothing else running)

| Run | Command | Result |
| --- | --- | --- |
| 0 | `gradlew fluidSolverRegression` at `3c27271`, before the change | FAILED, `Fluid basis mismatch` at `FluidSolverRegressionTest.java:89` |
| 1 | `gradlew fluidSolverRegression -PfluidRegressionCapture=true` | BUILD SUCCESSFUL, `CAPTURED src\test\resources\fluid\regression\chain-100.json`; island fixtures SKIP |
| 2 | `gradlew fluidSolverRegression` (declared gate) | BUILD SUCCESSFUL, `chain-100 vs reference: max deviation: state/moles 0.000e+00, temperature 0.000e+00 K, phase fraction 0.000e+00, flow 0.000e+00` |
| 3 | `gradlew fluidSolverRegression -PfluidRegressionMode=exact` (bitwise gate) | BUILD SUCCESSFUL, same zero deviation, substep counts identical |

`build/test-results/fluidSolverRegression/TEST-*.xml`: tests=1, failures=0, errors=0. Logs kept in
`build/regression-run-{0,1-capture,2-declared,3-exact}.log`.

## Bonus: the gate applied to the solid-phase branch

Branch `claude/solid-phase-gui-regression` = `claude/solid-phase-gui` @ `bfb22ef` (all solid-phase
fixes A–H and the GUI commits) + this commit cherry-picked as `fca6a15`. Run 4,
`gradlew fluidSolverRegression -PfluidRegressionMode=exact` there against the reference recorded on
`main`: **BUILD SUCCESSFUL, zero deviation, 37/1 substeps** — the clear-fluid chain trajectory is
bitwise identical across every solid-phase change. This replaces the substep-count substitute in
the solid-phase implementation review.

# CDU17 retirement and TJL19 pressure floor

Date: 2026-09-08. Base commit: `e8d8937eacc1d58692f1a4f289f0633ec25b4cd1`; changes are uncommitted.

## Runtime changes

- Deleted `V3Cdu17TiaJuanaPackage` and its `cdu17-tjl-kl1976-r2.properties` metadata. The V3 registry now contains only `createcheme:tjl19_dwsim`.
- Set TJL19's inclusive minimum pressure to **1,000 Pa** and advanced its dataset revision to `tjl19-dwsim-10.2.3-r2`. The component properties, assay, binary interactions, temperature range, and maximum pressure are unchanged.
- The preset action used when leaving Holland now returns the same TJL19 literature CDU preset as a fresh calculator. Removed the old pilot constants, preset builders, and superseded default-input migration.
- On loading a supported saved-data version with the retired package ID, the calculator retains its fresh TJL19 literature input, drops the old presentation result, resets revisions, and reports that recalculation is required. Detection happens before decoding the incompatible component axis. No 16-to-19-component reinterpretation or registry alias is used.
- The one retired-ID literal in production is intentional save recognition. The other Java occurrences are tests for save recognition and registry rejection.

The requested pressure floor changes admission, not the solver or the property correlations. It does not by itself establish physical qualification of every vacuum-column operating point.

## Regression migration

All executable fixtures that selected CDU17 now select TJL19. Dataset-specific CDU17 property assertions were replaced with TJL19 registry, full-axis, defensive-copy, interaction-matrix, and pressure-boundary checks. Both general quadratic mixing and the zero-interaction optimization still have an independent Peng–Robinson reference test; the latter uses a test-only zero matrix with TJL19 component data.

The fixtures needed more than an ID substitution:

- Dynamic 19-component arrays and current component IDs replace assumptions about 16 entries and an absent methane feed. The initializer's zero-feed test explicitly omits ethane because the TJL19 assay has no zero entries.
- Binary numerical rejection and identity-support cases use TJL_PC02/TJL_PC10. The forced two-phase numerical solution must still fail the independent condenser-phase gate; exact warm starts do not override that rejection.
- Ethane/TJL_PC11 at 500 K qualifies a trace-vapor omission; at 638.15 K it retains the heavy component. Conservation, reference-error budgets, cancellation, and workspace reuse remain checked.
- The 110 kPa condenser-transition fixture now uses 333.15 K, where TJL19 actually transitions from a liquid-only coarse-grid seed to a two-phase requested grid. At the old 323.15 K, TJL19 correctly publishes a liquid-only condenser.
- Dry side-draw tests have a dedicated TJL19 operating fixture rather than depending on the server's wet 40-tray literature preset.
- At an 80 C condenser, 120 kmol/h of steam remains vapor-water limited, while 180 kmol/h produces vapor water and decanted liquid. The split-water case still checks the entire authored water flow to 1e-8 mol/s.
- The wet condenser-duty admission test uses eight stages, where the heat-free wet rung converges before the gate is evaluated. The 30-stage extreme-cooling version reached numerical nonconvergence before that gate under TJL19.
- Closure-case baselines A/B/C/D/E have 0/3/3/3/2 final Newton iterations under TJL19. The concentrated 40 MW case stays liquid-only. Its measured work is 1,125,955 cooperative checkpoints, with a 1,250,000 ceiling. These replace measurements made with the retired dataset; scientific residual and acceptance tolerances were not changed.
- The 5 MW wet pumparound reduces condenser cooling by about 5.074 MW because the product enthalpy also changes. Its independently reconstructed whole-column energy closure remains required.

## Artifacts and historical evidence

`documentation/current_crude_thermo_parameters.json` was refreshed from compiled runtime objects. It contains only TJL19, revision r2, the 1,000 Pa floor, and source hashes. Every exported number was checked for exact floating-point round-trip fidelity.

The September 5 CDU17 benchmark report was moved unchanged to `src/test/resources/v3-benchmarks/retired-cdu17/v3-cold-core-v1.json`. Its old component vectors, revisions, and measured results remain historical evidence and were not relabeled as TJL19 results. The local Java diagnostic that selected the old package was updated.

## Validation

The focused thermodynamic, save-recognition, preset, binary, phase-transition, truncation, closure, and wet-admission checks passed. The final offline `build` passed in 6 minutes 10 seconds: **463 tests across 89 suites, zero failures, zero errors, zero skipped tests**. A temporary Gradle init script selected four test JVMs; it made no changes to test exclusions or acceptance thresholds.

The assembled `createcheme-0.1.0.jar` contains TJL19 and has no CDU17 class or resource entries. `git diff --check` passes.

The separate optional `compileBenchmarkJava` task cannot compile three existing local probes because `V3TimeoutBenchmark` is absent. The errors are in `V3SideDrawRequestedGeometryProbe`, `V3SideDrawSeedProbe`, and `V3TruncationPathProbe`; none concerns a retired property-package symbol. This optional task is separate from the normal mod build.

# V3 W1/W10 Exact Dry-Target Re-solve

Date: 2026-09-02. Branch: `codex/v3-cdu-ramp-hardening`.

## Purpose and scope

This is a test-only continuation experiment following the current-branch W10 dry-state
reproduction. It answers one narrow question: can the exact failed 100 kPa W10 terminal state
be projected to the existing vapor-only tray formulation and then solved with a fresh full
finite-difference Newton solve?

It does not alter `V3ColumnCalculator`, `V3SimultaneousColumnSolver`, any production topology
selection, or result publication. The target solve is invoked only after the exact source-state
and target-ledger preconditions below pass.

The executable task is `v3W10DryTargetReSolveProbe`; the full structured output is
`build/reports/benchmarks/v3-w10-dry-target-reresolve.json`.

## Exact source-state contract

The shared test-only `V3W10ManualRouteSupport` replays the existing manual route unchanged:

1. 150 kPa no-draw 4-8-15-30 tray ladder;
2. draw attachment at 0.25, 0.50, 0.75, and 1.00 of a 22.5% combined loading; and
3. 5 kPa pressure legs down from 145 kPa.

The target probe retains the failed `V3SimultaneousColumnSolver.Attempt.Failure` directly from
the first 100 kPa pressure leg. It does not reinitialize, reproject with a bubble-point solve,
retry a condenser branch, or use a nearby continuation state before calling
`V3DryTrayTransition.assess`.

All source gates passed in the observed run:

| Gate | Observation |
|---|---:|
| Pressure / condenser branch | 100 kPa / `TWO_PHASE` |
| Exact source failure | `MAX_ITERATIONS`, 128 iterations, residual `5.8717185022531246e-5` |
| Trace support | identity |
| Source vapor-only trays | none |
| W1 floor | `7.251944444444443e-4 mol/s` |
| Dry tray set | `[23]` only |
| Tray 23 liquid | `3.1751215722210556e-215 mol/s` |
| Authored draw on tray 23 | none |

The refactored report-only `v3W10DryStateProbe` reproduced the same source result after support
extraction: 100 kPa / `MAX_ITERATIONS` / 128 iterations / residual
`5.8717185022531246e-5`, with tray 23 as the sole dry candidate.

## Projected target and solver contract

`V3DryTrayTransition.assess(sourceProblem, sourceFailure.state(), sourceLegInput, control)`
returned `Prepared([23])`. Its target keeps the 100 kPa `TWO_PHASE` condenser branch and removes
only tray 23's liquid degrees of freedom and VLE rows:

| Ledger surface | Wet source | Vapor-only target | Delta |
|---|---:|---:|---:|
| Unknowns | 991 | 976 | -15 |
| Equations | 991 | 976 | -15 |
| Coordinates | 991 | 976 | -15 |

The 15 removed slots match the active component count. The projected seed has exact zero liquid
flow for all 15 tray-23 components and passes the target topology compatibility check.

The target is solved through the direct package-private
`V3SimultaneousColumnSolver.solve(... DifferenceScale.FINE, ..., V3NewtonTrace.NONE)` overload.
That path starts from the projected seed and permits zero local-block attempts and zero frozen
Jacobian steps; every Newton Jacobian is therefore fresh fine full FD. The solver's ordinary
fresh final-Newton certificate remains required.

After the solve, the probe separately recomputes feed enthalpy, creates a fresh
`V3AcceptanceAuditor`, and audits the returned state with a fresh thermodynamic workspace. An
audited success requires all three conditions: solver convergence, unchanged final-Newton gates,
and a passing independent audit including an explicit passing `DRY_TRAY` check.

## Result: structurally valid dry target, not an admitted solution

| Measure | Source terminal | Fresh vapor-only target |
|---|---:|---:|
| Outcome | `MAX_ITERATIONS` | `LINE_SEARCH_EXHAUSTED` |
| Iterations | 128 | 8 |
| Maximum scaled residual | `5.8717185022531246e-5` | `5.842978184325778e-5` |
| Final Newton evidence | unavailable | unavailable |
| Independent audit | not admitted source state | rejected |

The small residual change (`2.8740317927346775e-7`, about 0.49%) is not a convergence or
performance gain: the target still lacks an accepted Newton step and has no final certificate.
It is therefore not a candidate for production activation.

The independent target audit is useful separation evidence:

| Audit family | Result | Measured value |
|---|---|---:|
| `FINITE_TOPOLOGY` | pass | `0` |
| `DRY_TRAY` | pass | `0` (all tray-23 liquid components exactly zero) |
| `LOCAL_COMPONENT_BALANCE` | pass | `2.0037875019365472e-5` |
| `ENERGY_BALANCE` | pass | `5.842978184325778e-5` |
| `SIDE_DRAW_SPLIT` | fail | `1.0149006190745224` |
| `EQUILIBRIUM` | fail | `3.759755246246499e-6` vs `1e-8` |
| `CONDENSER_PHASE` | fail | `7.949319154321177e-7` vs `1e-8` |

Thus the tray-23 vapor-only formulation itself is correctly constructed and independently
auditable. It does not, by itself, repair this target candidate: its tray-22 side draw exceeds
available liquid downflow (`SIDE_DRAW_SPLIT > 1`), and the projected state cannot find an
admissible Armijo step. Because both the source and target states are nonconverged, this is not a
proof that the authored input is infeasible. It rules out direct one-shot W10 dry projection as
the current branch's convergence fix; it does not claim that a bounded dry-branch continuation
from an earlier feasible state has been tested.

## Reproduction

```powershell
$env:JAVA_HOME='C:\Users\wormz\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.20+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\Users\wormz\.gradle'
& 'C:\Users\wormz\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat' `
    v3W10DryTargetReSolveProbe --offline --no-daemon --console=plain
```

Focused verification also passed:

```powershell
& 'C:\Users\wormz\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat' `
    test --offline --no-daemon --console=plain `
    --tests 'com.wormzjl.createcheme.science.column.v3.V3W10DryStateProbeTest' `
    --tests 'com.wormzjl.createcheme.science.column.v3.V3W10DryTargetReSolveProbeTest'
```

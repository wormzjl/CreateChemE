# V3 W1/W10 Manual Dry-State Reproduction

Date: 2026-09-02.  Branch: `codex/v3-cdu-ramp-hardening`.

## Purpose and scope

This is a current-branch reproduction of the historical W10 route from
`claude/wall-probes`.  It is a test-only measurement, not a solver change:

- no production calculator or solver code was changed;
- no runtime probe-property overrides were added;
- no `V3DryTrayTransition` target topology was constructed or solved; and
- a tray crossing the W1 dry floor is reported as a candidate only.

The executable diagnostic is `v3W10DryStateProbe`.  Its full structured output is
`build/reports/benchmarks/v3-w10-dry-state-probe.json`.

## Reproduced route

Fixture: Tia Juana Light; 30 trays; feed tray 24; 750 Pa/tray; 90 C condenser;
RR 2.0; 8 MW reboiler; no steam; combined three-side-draw loading 22.5%.

1. Solve a no-draw bare ladder at 150 kPa: 4, 8, 15, then 30 trays.
2. Attach draws manually at 150 kPa with lambda = 0.25, 0.50, 0.75, and 1.00.
3. Continue the fully attached state down in 5 kPa legs from 145 to 60 kPa.
4. Record the terminal state of every solver attempt: convergence evidence, all available
   tray liquid totals, the W1 dry floor, candidate dry trays, and explicit tray-23 eligibility.

Each manual solve uses the existing in-package
`V3SimultaneousColumnSolver.solveWithTerminalLocalBlockFullNewtonRecovery` path.  The
cooperative case ceiling is eight minutes; the observed run finished in 19.557 s.

## Current-branch result

The bare ladder converged at 8, 14, 21, and 15 iterations.  All four attach points
converged (41, 13, 27, and 45 iterations).  Fully attached pressure legs 145 through
105 kPa all converged (4, 4, 4, 5, 6, 5, 4, 4, and 4 iterations).

The first and only failed leg was 100 kPa:

| Field | Observation |
|---|---:|
| Outcome | `FAILURE / MAX_ITERATIONS` |
| Solver iterations | 128 |
| Maximum scaled residual | `5.8717185022531246e-5` |
| W1 liquid floor | `7.251944444444443e-4 mol/s` |
| Tray 22 liquid | `18.484488448478558 mol/s` (`25489.010002881532` x floor) |
| Tray 23 liquid | `3.1751215722210556e-215 mol/s` (`4.378303772932854e-212` x floor) |
| Other tray below the W1 floor | none |
| Authored liquid draw on tray 23 | no |
| Tray 23 vapor-only candidate | yes, reported only |

This reproduces W10's core dry-state evidence on the current branch: the 100 kPa leg fails
only after the tray immediately below the deepest side draw has collapsed far below the W1
floor.  The report contains every tray rather than inferring the conclusion from tray 23 alone.

## Reproduction commands

```powershell
$env:JAVA_HOME='C:\Users\wormz\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.20+8'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME='C:\Users\wormz\.gradle'
& 'C:\Users\wormz\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat' `
    v3W10DryStateProbe --offline --no-daemon --console=plain
```

The ordinary focused test command verifies the route and tray-23 eligibility contracts without
running the expensive route:

```powershell
& 'C:\Users\wormz\.gradle\wrapper\dists\gradle-9.2.1-bin\2t0n5ozlw9xmuyvbp7dnzaxug\gradle-9.2.1\bin\gradle.bat' `
    test --offline --no-daemon --console=plain `
    --tests 'com.wormzjl.createcheme.science.column.v3.V3W10DryStateProbeTest'
```

# V3 Trace-Pair Anchoring Benchmark — Rejected

Date: 2026-09-02

## Decision

The W-2/W-3 trace-pair Tikhonov candidate was **rolled back**.  It did not produce an
audited success in either required 60 kPa leg and materially worsened the two near-certificate
residuals.  Faster wall-clock exits are not treated as a performance gain when the solver gives
up earlier and with a worse residual.

## Candidate under test

- Baseline solver: `9d18bb9` (A0 complete), exercised through probe commit `b657245`.
- Candidate: `c42c397` (`Anchor two-phase trace-pair corrections`).
- Detector: local liquid *and* vapor phase fraction at most `1e-3`.
- Conditioner: add one original-normal-diagonal Tikhonov weight only to the selected
  correction columns in both damped-normal paths (ordinary recovery and `FINAL_CERTIFICATE`).
- Untouched: direct Newton solve, residual equations, acceptance audit, formulation revision,
  and convergence gates.

The candidate's focused coordinate-map/normal-equation tests passed before the screen.  The
go/no-go criterion was not unit-test correctness; it was the plan's required convergence outcome.

## Reproduced W-5 screen

Fixture: Tia Juana Light, 30 trays, 60 kPa top pressure, 22.5% combined three-side-draw loading,
dry, RR 2.0, 8 MW reboiler duty.  The probe uses the normal production 24-iteration pressure-leg
budget.

| Case | Baseline (`9d18bb9`) | Candidate (`c42c397`) | Result |
|---|---|---|---|
| 85 C | `NONCONVERGENCE`, 90 kPa leg, residual `2.5189e-8`, 77.6 s | `NONCONVERGENCE`, 100 kPa leg, residual `2.0274e-5`, 61.8 s | Regressed: stalls 10 kPa earlier and residual is ~805x worse. |
| 90 C | `NONCONVERGENCE`, 110 kPa leg, residual `2.5982e-9`, 71.4 s | `NONCONVERGENCE`, 110 kPa leg, residual `1.4194e-5`, 63.1 s | Regressed: residual is ~5,463x worse. |
| 95 C control | `NONCONVERGENCE`, 120 kPa leg, residual `1.5707e-2`, 42.2 s | `NONCONVERGENCE`, 120 kPa leg, residual `1.5707e-2`, 42.4 s | No material change. |

The baseline 85/90 C legs are the planned near-certificate specimens.  The candidate neither
flipped them to audited success nor retained their near-solution residuals; therefore it cannot
be accepted as W-2/W-3.

## Verification commands

Focused candidate tests, before the comparison:

```powershell
.\gradlew.bat test --offline --no-daemon --console=plain --rerun-tasks \
  --tests 'com.wormzjl.createcheme.science.column.v3.V3DryMeshCoordinateMapTest' \
  --tests 'com.wormzjl.createcheme.science.column.v3.V3NormalEquationsTest'
```

Comparison harness, run in isolated disposable worktrees:

```powershell
.\gradlew.bat test --offline --no-daemon --console=plain --rerun-tasks \
  --tests 'com.wormzjl.createcheme.science.column.v3.V3WallProbeTest.familyW5_condenserTemperatureWallLocation'
```

## Repository state

`c42c397` was immediately reverted by `232a053`.  The isolated benchmark worktree was removed.
The working solver returns to A0 behavior, apart from the separate user-owned deletion of
`V3_COLD_DOE_BENCHMARK.md`.

## Follow-up

Do not retune this anchor in place.  The negative result shows that broadly anchoring local
two-phase trace pairs changes the pressure-leg correction direction before the final certificate
is reached.  Proceed with the plan's independent W-4 honest-starvation gate and the formulation-
level W-1 dry-tray branch; revisit W-2 only with a narrower, state-autopsied null-direction
criterion and a fresh benchmark gate.

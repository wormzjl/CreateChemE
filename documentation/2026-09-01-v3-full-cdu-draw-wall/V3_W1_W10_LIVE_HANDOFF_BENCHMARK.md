# V3 W1 / W10 Live Dry-Tray Handoff Benchmark

Date: 2026-09-02.

Status: **rejected as a production activation**. The test-local live handoff reaches the first
valid dry-tray state on the continuous wet trajectory, but the resulting vapor-only target is
neither solved nor independently audited. The diagnostic probe is retained only to make that
conclusion reproducible; it changes no production continuation or topology policy.

## Question

The earlier W10 terminal projection tested a dry target only after the wet pressure leg had
finished. A one-iteration external-restart experiment was not equivalent: restarting the direct
solver at every iteration stalled around a scaled residual of `5.7e-2` and left tray 23 above its
W1 floor. That restart control is deliberately not retained as a live-handoff mechanism.

This screen instead asks the meaningful formulation question: if a normal continuous wet 100 kPa
solve crosses W1's dry floor, does an immediate vapor-only handoff at the **first** crossing
produce an audited target?

## Test design

- Reproduce the existing W10 fixture through the accepted wet 105 kPa state, then solve the
  identical 100 kPa wet problem on one continuous direct fine-FD trajectory.
- A request-local `V3NewtonTrace.sampledState` calls `V3DryTrayTransition.assess` at every
  sampled wet state. At the first exact `Prepared([23])` it throws a private test signal; the
  signal is caught outside the solver. No production code or solver behavior changes.
- Before solving the target, require all of the following: exact `[23]` prepared topology,
  source-to-target unknown/equation/coordinate deltas of `-15`, all 15 tray-23 liquid component
  flows projected to exact zero, and target-seed topology compatibility.
- Re-solve only that target using the direct, fine-FD solver with a bounded 128-iteration budget
  (no local blocks or frozen Jacobians), then run an independent `V3AcceptanceAuditor` audit.
- Control: the same continuous direct wet solve with no trace abort, projection, or target solve.
- Admission bar: `AUDITED_SUCCESS` requires final-Newton evidence, a passing independent audit,
  and a passing `DRY_TRAY` exact-zero audit. Reaching a dry target is never success by itself.

Command:

```powershell
gradle v3W10LiveDryTrayHandoffProbe `
  -Pv3W10LiveDryTrayHandoffReport=build/reports/benchmarks/v3-w10-live-dry-tray-handoff.json `
  --offline --no-daemon --console=plain
```

## Cold result

The warm-start route reproduced the accepted 105 kPa wet state in 4 iterations at scaled residual
`2.80811016524894e-9`. The 100 kPa wet source has 991 unknowns, 991 equations, and 991
coordinates.

| Path | Result | Key state / evidence |
|---|---|---|
| Wet one-shot control | `SOURCE_SOLVER_MAX_ITERATIONS` | 128 iterations; scaled residual `5.87173411340411e-5`; tray-22 `D/L=1.01331189165609`, `L-D=-0.24606236702374 mol/s`; tray 23 `2.24409067736519e-292 mol/s` (`3.09446755219469e-289` × W1 floor). |
| Trace-captured first dry state | `Prepared([23])` at wet iteration **110** | scaled residual `5.65543096352918e-2`; tray-22 `D/L=1.02351330276963`, `L-D=-0.430297385124291 mol/s`; tray 23 `6.76655736419459e-4 mol/s`, or **0.933068009005268 ×** the W1 floor `7.25194444444444e-4 mol/s`. |
| Structural handoff checks | all pass | Target is `[23]` vapor-only: 976 unknowns, equations, and coordinates (each exactly 15 below the wet source); all 15 tray-23 liquid components are exactly zero and the projected seed is compatible. |
| Fresh vapor-only target | `TARGET_SOLVER_LINE_SEARCH_EXHAUSTED` | Fails after 11 target iterations at scaled residual `5.84300996828172e-5`; no target publication. |

The independent target audit verifies `FINITE_TOPOLOGY` and `DRY_TRAY`, but rejects the candidate:

- `SIDE_DRAW_SPLIT = 1.0077878866477974 > 1.0`;
- `EQUILIBRIUM = 3.7597775770503716e-6 > 1e-8`;
- `CONDENSER_PHASE = 7.949363448611635e-7 > 1e-8`.

Thus, the first-live transition does not repair this candidate-state/certificate failure, which
remains at the deeply withdrawing tray. It makes the dry topology representable, but not
admissible for this W10 specimen. These nonconverged candidates do not by themselves prove the
authored input infeasible.

## Decision

Do not activate W1 dry-tray handoff in production from this result. It produced **zero audited
successes** versus the wet control, so it fails the measured-gain gate. No parameter sweep is
warranted: the direct first-crossing experiment reaches the correct target topology, satisfies
every structural projection condition, and still fails both solve and independent admission.

The retained probe is a bounded, test-only regression diagnostic for future W1 formulation work.
It documents that future candidates must improve target solvability and auditability, not merely
detect or project dry trays earlier.

Artifacts:

- `build/reports/benchmarks/v3-w10-live-dry-tray-handoff.json`
- `src/test/java/com/wormzjl/createcheme/science/column/v3/V3W10LiveDryTrayHandoffProbeTest.java`

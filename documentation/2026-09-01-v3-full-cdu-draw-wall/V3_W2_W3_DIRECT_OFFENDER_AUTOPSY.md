# V3 W-2/W-3 Direct-Correction Offender Autopsy

Date: 2026-09-02  
Branch: `codex/v3-cdu-ramp-hardening`  
Status: diagnostic checkpoint; no constrained solver path enabled.

## Question

At the exact W5b 110 kPa residual-gated state, what actually makes the raw terminal Newton
correction leave the finite positive log-flow domain? The earlier report proved a common
trace-pair near-null geometry, but did not identify the raw correction's offending coordinate.

## Method

The property-gated test reuses the exact production pressure continuation, builds a fresh fine FD
Jacobian, and reflectively calls the existing private terminal band-matrix builder. It solves the
same raw `J * delta = -r` system before decode, then tries each flow coordinate in isolation so
all domain offenders are reported. It also recomputes the independent acceptance audit on the
source state.

```powershell
gradle v3W2W3DirectOffenderProbe --offline --no-daemon --console=plain --rerun-tasks `
  '-Pv3W2W3DirectOffenderReport=build/reports/benchmarks/v3-w5b-direct-correction-offenders.json'
```

## Source state audit

The 110 kPa source state is independently accepted by every current audit family:

| Family | Value | Limit |
|---|---:|---:|
| `FINITE_TOPOLOGY` | 0 | 0 |
| `SIDE_DRAW_SPLIT` | `0.5060962762` | 1 |
| `LOCAL_COMPONENT_BALANCE` | `2.5982087724832363e-9` | 1 |
| `EQUILIBRIUM` | `8.397726958e-13` | `1e-8` |
| `ENERGY_BALANCE` | `1.369676244e-11` | 1 |
| `CONDENSER_PHASE` | `5.683786775e-13` | `1e-8` |

It is still not publishable: it has no valid final Newton convergence evidence. This observation
does not relax any audit or material admission threshold.

## Raw direct correction

The raw direct solve is numerically factorized, with a `5.207723085889438e-20` backward error,
minimum pivot `1.73529272875274e-9`, maximum pivot `9.396528793157211`, and 465 pivot swaps.
Its largest coordinate correction is `5.77920292050021e8`.

There are exactly two isolated domain offenders:

| Node | Component | Coordinate | Base log flow | Raw correction | Failure |
|---:|---|---|---:|---:|---|
| 1 | PC11 | liquid | `-166.2454989` | `+5.77920292050021e8` | upper overflow |
| 1 | PC11 | vapor | `-180.0022292` | `+5.77920292050021e8` | upper overflow |

For that pair:

```text
delta_q = (delta_L + delta_V) / sqrt(2) = +8.173027149877597e8
delta_a = (delta_L - delta_V) / sqrt(2) = 0
```

The PC11 pair is trace in both phases (liquid fraction `7.7758e-74`, vapor fraction
`4.7316e-80`), its common Jacobian mode ratio is `4.5323e-73`, and its anti-mode ratio is
`1.414213562373095`. The direct failure is therefore an offender-local equal-sign common shift,
not a VLE-ratio correction or a global physical residual defect.

## Consequence

Finite rank-one conditioning of all trace pairs was rejected because it did not recover a fresh
raw certificate. A final, narrower test-only experiment is justified: impose
`delta_L + delta_V = 0` **only for PC11/node 1** while generating one temporary correction,
preserve every original residual row and physical coordinate, and require a fresh unconstrained
raw direct certificate and unchanged independent audit before publication. If W5b does not become
an audited success, that experiment must also be removed.

Complete report: `build/reports/benchmarks/v3-w5b-direct-correction-offenders.json`.

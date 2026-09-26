# V3 W-3 Terminal Domain-Rescue Benchmark

Date: 2026-09-02  
Branch: `codex/v3-cdu-ramp-hardening`  
Decision: rejected and rolled back.

## Question

Can a one-shot damped-normal search move a residual-gated pressure-leg state into a point that
passes a fresh, unregularized final Newton certificate, when the current raw certificate correction
decodes outside the finite-positive-flow domain?

## Specimen and controls

The tested specimen was the cold no-steam W5b probe:

```text
60 kPa product pressure, 90 C condenser, 22.5% combined side-draw loading
Tia Juana Light, 30 trays, feed tray 24, 750 Pa/tray, RR 2.0, Qr 8 MW
```

The baseline report is
`build/reports/benchmarks/v3-w5b-certificate-unavailable.json`; the temporary candidate report
is `build/reports/benchmarks/v3-w5b-terminal-rescue-candidate.json`.

## Safety protocol tested

The candidate was deliberately narrow:

1. Trigger only when the raw direct terminal linear solve succeeded but its decoded correction
   hit the exact log-flow-domain error.
2. Permit one solve-local, unanchored damped-normal Armijo search only.
3. Require the temporary state to remain under the physical residual gate.
4. Discard the regularized system's backward error and all of its convergence evidence.
5. Rebuild the original FD Jacobian and require a fresh direct-only final certificate.
6. Never recurse after the rescue; a failed fresh certificate is a failure.

## Result

| Metric | Baseline | Candidate |
|---|---:|---:|
| Outcome | F / `NONCONVERGENCE` | F / `NONCONVERGENCE` |
| Failed pressure leg | 110 kPa | 110 kPa |
| Maximum scaled residual | `2.5982087724832363e-9` | `2.5982087724832363e-9` |
| Newton iterations | 24 | 24 |
| Wall time | 57.0715 s | 60.7225 s |
| Direct-domain certificate failures | 20 | 21 |
| Damped-normal rescue attempts | 0 | 1 |
| Residual-gated temporary search states | 0 | 1 |
| Fresh direct certification after rescue | n/a | failed the same domain check |

The temporary point was physically residual-gated, but its freshly generated direct correction
again decoded outside the finite-positive-flow domain. No regularized correction was reported as
converged, and no audited success resulted.

## Rollback and verification

The implementation was removed rather than retained as dormant behavior. The post-rollback
focused suite passed:

```text
V3SimultaneousColumnSolverTest
V3NormalEquationsTest
V3DwsimStageContinuationTest
```

Passive terminal-certificate instrumentation remains in commit `8cde759`; it is diagnostic only.
The rejected rescue itself has no commit.

## Consequence

This experiment rules out a simple terminal regularized-search escape for W5b. It leaves the
fundamental current failure mode well localized: at a nearly solved state, raw certificate
corrections leave the positive log-flow coordinate domain. A future W-2 effort must be
evidence-gated by a local `Jq` autopsy of the proposed trace-pair common mode, followed by the
same fresh-unregularized-certificate requirement.

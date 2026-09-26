# V3 W-3 Hard Common-Mode Constraint Experiment — Rejected

Date: 2026-09-02  
Branch: `codex/v3-cdu-ramp-hardening`  
Decision: removed after the W5b screen; no candidate source, test, or Gradle task remains.

## Scope and safeguards

This was a single terminal-only, default-off experiment for the W5b residual-gated direct-certificate
failure (60 kPa request, 90 C condenser, 22.5% combined side draws, no steam). It was eligible only
after the exact raw direct log-flow-domain message. It dynamically declined unless:

- the full raw invalid-flow set was exactly two coordinates, one liquid and one vapor;
- both were upper overflows for the same trace pair;
- the raw correction was overwhelmingly the pair common mode `q=(eL+eV)/sqrt(2)`;
- `Jq` was near-null while the anti/VLE-ratio direction remained non-null; and
- coordinate/Jacobian identities and order matched exactly.

For a qualified pair, it formed one damped normal system `A=H+lambda D`, solved `delta0=A^-1 g`
and `W=A^-1 q`, then used the hard Schur projection:

```text
delta = delta0 - W * (qT delta0) / (qT W)
qT delta = 0
```

It rejected an unresolved positive denominator and an ineffective numerical constraint residual.
The resulting state had to pass both the residual gate and Armijo. It could not publish from that
regularized direction: it rebuilt a fresh unmodified FD Jacobian and raw direct certificate, and
the calculator's unchanged independent acceptance audit remained required for any published outcome.

## W5b result

| Run | Outcome | Iterations | Maximum scaled residual | Wall time | Terminal evidence |
|---|---|---:|---:|---:|---|
| Hard-constraint candidate | `F / NONCONVERGENCE` | 24 | `2.5982087724832363e-9` | 66.2850 s | 20 exact triggers; 19 qualified; 19 normal/delta0/W/Armijo states; 0/19 fresh raw certificates |
| Fresh default-off parity | `F / NONCONVERGENCE` | 24 | `2.5982087724832363e-9` | 61.8698 s | baseline terminal path restored; no experiment event |

The candidate neither changed the failure point nor obtained an audited success, and added work.
Under the benchmark/rollback gate it was rejected and completely removed.

Reports:

- `build/reports/benchmarks/v3-w5b-hard-common-mode-constraint-final-candidate.json`
- `build/reports/benchmarks/v3-w5b-hard-constraint-default-parity.json`


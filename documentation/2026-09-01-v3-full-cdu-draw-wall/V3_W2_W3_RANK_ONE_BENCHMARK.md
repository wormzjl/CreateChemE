# V3 W-2/W-3 Rank-One Conditioner Benchmark — Rejected

Date: 2026-09-02  
Branch: `codex/v3-cdu-ramp-hardening`  
Decision: removed; no candidate code committed.

## Candidate

The W5b autopsy measured a trace-pair common-mode null direction. The experimental conditioner
therefore added only:

```text
H' = H + rho * q * q^T,  q = (eL + eV) / sqrt(2)
```

For each qualified pair this adds `rho/2` to LL, LV, VL, and VV, preserving the VLE-ratio
anti-mode. It was disabled by default, triggered only after the exact raw direct-certificate
log-flow-domain failure, and was never used in normal Newton or ordinary damped recovery. A
conditioned correction could only become a temporary state after both Armijo and residual gates;
publication still required a freshly rebuilt **unregularized** direct certificate and independent
audit.

## W5b alpha screen

Fixture: Tia Juana Light, 60 kPa, 90 C condenser, 22.5% combined side draws, no steam.
Baseline: F / `NONCONVERGENCE`, 110 kPa recovery, 24 iterations,
`2.5982087724832363e-9` maximum scaled residual.

| Alpha | Outcome | Time | Residual | Fresh raw certificates |
|---:|---|---:|---:|---:|
| `1e-8` | F / `NONCONVERGENCE` | 61.1069 s | `2.5982087724832363e-9` | 0 |
| `1e-6` | F / `NONCONVERGENCE` | 61.8091 s | `2.5982087724832363e-9` | 0 |
| `1e-2` | F / `NONCONVERGENCE` | 61.1733 s | `2.5982087724832363e-9` | 0 |
| `1e-1` | F / `NONCONVERGENCE` | 64.9307 s | `2.5982087724832363e-9` | 0 |

Every run recorded 20 exact domain-triggered terminal searches, 1,460 qualified trace pairs in
aggregate, 20 factorized conditioned corrections, and 20 residual-gated Armijo states. The fresh
raw certificate failed after every one. The outcome and residual did not move, so the result is
not an observable convergence or performance gain. The `1e-1` timing was collected alongside a
parallel screen and is not used for a timing claim; it agrees with the functional rejection.

Reports:

- `build/reports/benchmarks/v3-w5b-rank-one-alpha-1e-8.json`
- `build/reports/benchmarks/v3-w5b-rank-one-alpha-1e-6.json`
- `build/reports/benchmarks/v3-w5b-rank-one-alpha-1e-2.json`
- `build/reports/benchmarks/v3-w5b-rank-one-alpha-1e-1.json`

## Rollback verification

The experiment was removed with `apply_patch`, not retained behind a runtime flag. The post-rollback
focused solver suite passed, and a fresh W5b baseline probe returned the original outcome,
residual, iterations, solve path, and events:

```text
F / NONCONVERGENCE; 110 kPa; 24 iterations; 2.5982087724832363e-9; 59.6083 s
```

Report: `build/reports/benchmarks/v3-w5b-post-rank-one-rollback.json`.

## Consequence

The autopsy's null geometry remains valid, but rank-one terminal conditioning does not make the
fresh raw correction representable. Do not re-enable this path without a new mechanism that
changes the formulation or exposes a distinct, measured failure state. The retained branch
contains the diagnostic autopsy checkpoint only (`e886b55`).

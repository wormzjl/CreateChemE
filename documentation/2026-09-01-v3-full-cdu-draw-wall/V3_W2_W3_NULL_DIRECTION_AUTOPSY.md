# V3 W-2/W-3 W5b Null-Direction Autopsy

Date: 2026-09-02  
Branch: `codex/v3-cdu-ramp-hardening`  
Status: diagnostic checkpoint; no conditioner enabled.

## Why this probe exists

The W5b pressure leg reaches a residual-gated state but fails its final Newton certificate when
the raw correction decodes outside the finite positive log-flow domain. The previous diagonal
anchor trial was rejected because it penalized both the unwanted common trace-pair mode and the
physically useful VLE-ratio mode. This probe measures the actual current-branch state before any
new conditioner is written.

## Reproduction

```powershell
gradle v3W2W3NullDirectionProbe --offline --no-daemon --console=plain --rerun-tasks `
  '-Pv3W2W3AutopsyReport=build/reports/benchmarks/v3-w2-w3-null-direction-autopsy.json'
```

The property-gated test invokes the existing private production pressure-continuation driver from
test code, so it uses the exact 150 kPa anchor, adaptive draw ramp, secant predictor, and 110 kPa
Wang-Henke recovery path without introducing a production diagnostic hook.

## Captured baseline state

Fixture: Tia Juana Light, 30 trays, feed tray 24, 60 kPa request, 90 C condenser, 22.5% combined
side draws, no steam, RR 2.0, Qr 8 MW.

| Quantity | Measured value |
|---|---:|
| Failed recovery leg | 110 kPa |
| Recovery iterations | 24 |
| Terminal maximum scaled residual | `2.5982087724832363e-9` |
| Fresh certificate replay | one raw direct candidate exits the log-flow domain |
| Fresh FD rows | 991 |
| Trace pairs below `1e-3` in both phases | 98 |

## Measured null geometry

For each same-node/component pair, the probe uses:

```text
q = (eL + eV) / sqrt(2)        common trace-pair mode
a = (eL - eV) / sqrt(2)        VLE-ratio / anti-mode
eta = ||Jq||2 / max(||JeL||2, ||JeV||2)
```

Representative production-state pairs:

| Node | Component | xL | yV | eta (common) | anti-mode ratio |
|---:|---|---:|---:|---:|---:|
| 19 | PC12 | `1.693e-19` | `1.757e-24` | `3.754e-19` | `1.414` |
| 20 | PC12 | `1.693e-15` | `1.825e-20` | `3.711e-15` | `1.414` |
| 21 | PC12 | `1.648e-11` | `1.806e-16` | `6.370e-9` | `1.414` |
| 22 | PC12 | `1.441e-7` | `1.667e-12` | `9.561e-7` | `1.414` |

The common mode is therefore directly measured as near-null at the exact residual-gated failure
state; the anti-mode is not suppressed. This explains why independent diagonal anchoring was both
too strong and geometrically wrong.

## Next candidate gate

The only justified implementation experiment is a rank-one term per qualified pair:

```text
H' = H + rho * q * q^T
```

which adds `rho/2` to LL, LV, VL, and VV only. It must run only after an unmodified terminal
certificate fails, serve only as a temporary search direction, and publish only if a fresh
unmodified direct certificate plus independent audit succeeds. Benchmark W5b first; roll back if
it remains failure or harms the successful controls.

## Outcome of that candidate

The prescribed rank-one experiment was run at alphas `1e-8`, `1e-6`, `1e-2`, and `1e-1`. All
twenty residual-gated temporary states per screen still failed their fresh raw certificate; W5b
remained the same 110 kPa `NONCONVERGENCE` at `2.5982087724832363e-9`. The implementation was
therefore removed. See `V3_W2_W3_RANK_ONE_BENCHMARK.md` for the reports and rollback check.

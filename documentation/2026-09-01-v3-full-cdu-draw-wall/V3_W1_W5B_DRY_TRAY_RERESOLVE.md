# V3 W-1 W5b Dry-Tray Re-Resolve Probe

Date: 2026-09-02  
Branch: `codex/v3-cdu-ramp-hardening`  
Status: diagnostic checkpoint; no live dry-tray activation added.

## Question

Does the exact W5b 110 kPa pressure-recovery terminal state qualify for the existing vapor-only
dry-tray formulation, and can that formulation turn the failure into an audited success?

The source state is not approximated: the property-gated probe invokes the current production
pressure continuation, captures its identity-support `MAX_ITERATIONS` terminal state, and then
calls `V3DryTrayTransition.assess` with the authored W5b request.

## Reproduction

```powershell
gradle v3W1DryTrayReResolveProbe --offline --no-daemon --console=plain --rerun-tasks `
  '-Pv3W1DryTrayReResolveReport=build/reports/benchmarks/v3-w5b-dry-tray-reresolve.json'
```

## Result

The probe reproduced the normal W5b terminal evidence:

```text
110 kPa recovery; 24 iterations; 2.5982087724832363e-9 maximum scaled residual
raw direct final certificate exits the finite positive log-flow domain
```

The dry-tray assessment returned `UNCHANGED`.

| Quantity | Value |
|---|---:|
| Active feed | `725.1944444444443 mol/s` |
| Dry threshold (`1e-6 * F`) | `7.251944444444443e-4 mol/s` |
| Minimum tray liquid | tray 23: `3.9031809783484093 mol/s` |
| Minimum tray / feed | `5.3822543846685856e-3` |
| Margin over threshold | approximately 5,382x |

All 30 tray liquid totals exceeded the structural threshold. There was therefore no `Prepared`
vapor-only topology, no draw-on-dry incompatibility, and deliberately no target re-solve/audit.

## Consequence

This distinguishes two related mechanisms cleanly:

- The W-1 dry-tray branch remains valid infrastructure for genuinely collapsed total-liquid trays.
- The current W5b terminal certificate specimen is not one of them. It is a trace-pair null/certificate
  failure inside a thin but materially nonzero tray, and the W-2/W-3 rank-one search has already
  been benchmarked and removed because it could not restore a fresh raw certificate.

The complete JSON evidence is `build/reports/benchmarks/v3-w5b-dry-tray-reresolve.json`.

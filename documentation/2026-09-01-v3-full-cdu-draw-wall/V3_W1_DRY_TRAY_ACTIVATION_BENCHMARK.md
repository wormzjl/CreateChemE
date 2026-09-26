# V3 W-1 Dry-Tray Activation Benchmark

Date: 2026-09-02  
Branch: `codex/v3-cdu-ramp-hardening`  
Baseline commit: `5bc4c65` (`Prepare dry-tray transition topology`)

## Decision rule

The live dry-tray correction is retained only if it produces at least one fresh audited success,
with a `DRY_TRAY` audit pass and a retained dry-transition event. A faster failure is not a gain.

The experimental activation was bounded to full-grid feature rungs and same-grid pressure legs.
It projected a tray only when its total liquid was below `1e-6 * active feed`, then re-solved and
kept the dry candidate only when it audited successfully or improved an already failed residual.

## Native three-cell comparison

Fixture: Tia Juana Light, 30 trays, feed tray 24, 750 Pa/tray, RR 2.0, 8 MW reboiler, no steam.
Each screen was serial (`workers=1`) with the native 60 s calculation ceiling.

| DOE cell | Condition (kPa / °C / draw) | Baseline | Candidate | Result |
|---:|---|---|---|---|
| 1 | 155 / 50 / 40% | F, 22.051 s, residual 2.22736e-2 | F, 21.876 s, residual 2.22736e-2 | identical solve path; no dry event |
| 4 | 60 / 100 / 22.5% | F, 18.316 s, residual 3.85160e-3 | F, 18.038 s, residual 3.85160e-3 | identical solve path; no dry event |
| 29 | 100 / 50 / 40% | F, 23.756 s, residual 2.64690e-2 | F, 23.404 s, residual 2.64690e-2 | identical solve path; no dry event |

All three failed as `NONCONVERGENCE`; 0/3 became an audited success.

## W1/W5 current-candidate screen

This serial, property-gated probe used a 90 s calculation ceiling and the same dry/no-steam
fixture. It is a coverage check for the production activation rather than a timing comparison
against historic W5 probes.

| Case | Condition (kPa / °C / draw) | Outcome | Failure point / residual | Dry event |
|---|---|---|---|---|
| W1 | 155 / 50 / 40% | F | draw ramp lambda 0.75; 2.22736e-2 | none |
| W1c | 100 / 50 / 40% | F | 150 kPa anchor draw ramp; 2.64690e-2 | none |
| W5a | 60 / 85 / 22.5% | F | 85 kPa; 1.10430e-5 | none |
| W5b | 60 / 90 / 22.5% | F | 110 kPa; 3.06547e-7 | none |
| W5c | 60 / 95 / 22.5% | F | 120 kPa; 1.74843e-2 | none |
| W5d | 60 / 100 / 22.5% | F | 150 kPa anchor draw ramp; 3.85160e-3 | none |

All six failed as `NONCONVERGENCE`; 0/6 became an audited success. No result emitted a
`dry-tray transition` event, so the live candidate never changed the formulation.

## Interpretation and rollback

The structural dry-tray threshold was not reached by production states before their failures.
W5c's dominant residual includes a thin PC12 liquid component at tray 23, but that is a
component-level trace/VLE degeneracy; it is not proof that the **total** tray liquid is dry.

Therefore the activation was removed. The retained work is limited to inert infrastructure:
topology/ledger support, exact-zero state compatibility, an independent `DRY_TRAY` audit,
same-grid topology-preservation plumbing, and the property-gated benchmark probe. No formulation
or assumptions revision was bumped because no vapor-only topology can publish from production.

A post-rollback rerun of native cells 1, 4, and 29 matched the detached `5bc4c65` baseline
exactly on outcome, failure code, iteration count, maximum residual, solve path, and events.
Only ordinary wall-clock variation remained (22.11 vs 22.05 s, 18.18 vs 18.32 s, and 23.61 vs
23.76 s respectively).

## Reproduction

Native comparison:

```powershell
gradle v3ColdDoeScreen --offline --no-daemon --console=plain `
  '-Pv3ColdDoeCellIds=1,4,29' '-Pv3ColdDoeWorkers=1' `
  '-Pv3ColdDoeReport=build/reports/benchmarks/v3-w1-candidate.json'
```

W1/W5 screen:

```powershell
gradle v3W1W5BenchmarkProbe --offline --no-daemon --console=plain `
  '-Pv3W1W5Report=build/reports/benchmarks/v3-w1-w5-candidate.json'
```

Use the workspace's configured Java 17 and offline Gradle cache for both commands.

## Exact W5b terminal-state re-resolve

The later W5b certificate autopsy made it possible to test the dry branch at the exact failed
110 kPa state rather than only at accepted feature rungs. The result was `UNCHANGED`:

| Quantity | Measured value |
|---|---:|
| Active-feed-scaled dry floor | `7.251944444444443e-4 mol/s` |
| Thinnest whole tray | tray 23, below the deepest draw |
| Tray-23 liquid total | `3.9031809783484093 mol/s` |
| Tray-23 / active feed | `5.3822544e-3` |
| Margin above dry floor | about 5,382x |

Every tray remained above the structural dry threshold, so `V3DryTrayTransition.assess` correctly
created no vapor-only topology and no re-solve was attempted. This does not refute dry-tray
formulation support for more extreme wall families, but it establishes that **W5b's 110 kPa
certificate failure is not eligible for W-1**. Its current mechanism is thin trace-pair/certificate
geometry inside a materially nonzero liquid tray. Full report: `V3_W1_W5B_DRY_TRAY_RERESOLVE.md`.

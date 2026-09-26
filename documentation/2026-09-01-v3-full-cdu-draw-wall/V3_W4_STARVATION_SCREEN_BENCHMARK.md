# V3 W-4 Starvation Screen Benchmark — Rejected

Date: 2026-09-02

## Candidate

The W-4 candidate added a pre-feature-ramp guard based on:

```text
effective liquid ceiling = min(L_bare, V_bare * RR / (1 + RR))
guard = 0.95 * effective liquid ceiling
```

It also produced detailed requested/ceiling/physical-margin/guard-margin diagnostics and ran
the check before selecting dry-first or steam-first continuation. Unit tests covered the strict
boundary, a reflux-limited draw, a liquid-limited draw, zero supply, and multiple-draw selection.

## Measured screen

The intended hot high-loading candidates were rerun as independent cold calculator calls in
parallel (timing is therefore screening-only, not a serial performance claim):

| DOE cell | Baseline `232a053` | W-4 candidate | Result |
|---|---|---|---|
| 31: 100 kPa, 100 C, 40%, dry | `NONCONVERGENCE`, 34.722 s, residual `2.961236e-2` | Identical `NONCONVERGENCE`, 32.379 s, same residual/48 iterations | No starvation preflight; no observable functional gain. |
| 35: 100 kPa, 100 C, 40%, steam | `NONCONVERGENCE`, 34.722 s, residual `2.927126e-2` | Identical `NONCONVERGENCE`, 32.522 s, same residual/48 iterations | No starvation preflight; no observable functional gain. |

The small timing deltas are not evidence: both cases consumed the same ramp work and ran under
two concurrent workers. More importantly, neither request crossed the conservative bare-spine
supply guard. They remain the dry-tray/near-null convergence wall, not a demonstrably impossible
bare-liquid request.

## Decision

Per the benchmark-and-rollback rule, the candidate was removed without committing it. The existing
post-accepted-rung 95% withdrawal guard remains unchanged. W-4 should be revisited only after a
real production specimen is measured beyond the bare liquid/reflux ceiling; unit-only proof of the
formula is not enough to add a production early-exit policy.

## Reproduction

```powershell
.\gradlew.bat v3ColdDoeScreen --offline --no-daemon --console=plain --rerun-tasks \
  '-Pv3ColdDoeCellIds=31,35' '-Pv3ColdDoeWorkers=2' \
  '-Pv3ColdDoeReport=build/reports/benchmarks/v3-w4-baseline.json'
```

The same command with `v3-w4-candidate.json` recorded the candidate result before rollback.

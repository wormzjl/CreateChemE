# Review — Phase A0 completion: wet-lane symmetry + direct-basin selection

Date: 2026-09-02.
Scope: `bb659c8..9d18bb9` on `codex/v3-cdu-ramp-hardening` (5 commits: 1ef3b88 wet hardening,
1efbced bounded fallback, 41e83df/7ba414a basin selection + band limit, 9d18bb9 high-pressure
band). Companion evidence: `V3_COLD_DOE_A0_RERUN.md`; cause analysis this implements:
`V3_COLD_DOE_FAILURE_ANALYSIS.md`.

## Verdict

**Sound. Recommend merging to main.** The retained policy is exactly the measured-basin version
of the analysis doc's fix #1 (lane symmetry, steam ramped last over anchor-attached draws), the
rollback decisions (pacing re-expansion, near-miss leg, truncated intermediates) all match
measurement, and the publication gates are untouched — this is path-only policy, correctly
requiring no formulation/assumptions revision bump. The re-screen's claims cross-check against my
independent probes (cells 7/21–27/34 flips match F1/F3/F4/E4-family predictions; the two
basin-band cells 9/12 match the G-family cross-validation that both orders reach identical
products). Suite: see the run line at the bottom. Findings below are all minor
(robustness/diagnostics/test-quality); none blocks the merge.

## What the change does (verified against code)

1. **Pressure lane (≤100 kPa)**: `continuationInput = withoutSteamWithSurrogateDuty(input)` —
   draws stay attached for wet inputs; they qualify dry at the 150 kPa anchor (in-ladder feature
   ramp), ride every 5 kPa leg, and only steam ramps at target
   (`FeatureRampRunner` skips its draw phase when the seed already carries the requested draws,
   then runs the steam phase at `drawFraction = 1.0` with the surrogate duty ramping out).
   Dry inputs are byte-equivalent to `cc1affb` (`withoutSteamWithSurrogateDuty` is identity).
2. **Direct lane (>100 kPa) wet**: draws-first is the default order; two measured bands select
   steam-first up front — `[150, 155] kPa ∧ loading ≥ 20%` (preserves DOE cell 13's basin) and
   `≥ 200 kPa` (preserves cells 9/12's timing; 7ba414a's out-of-band draws-first had cost +40%
   there). Out-of-band draws-first failures get one bounded steam-first retry from the original
   dry seed (fresh rung budget, fresh branch attempts, events retained).
3. **Precondition relaxed correctly**: the runner now accepts a seed whose resolved input carries
   exactly the requested draw list (`sideDraws().equals(...)` — same list instance in the lane,
   so exact-equality is safe); steam-bearing seeds still rejected.
4. **`boundedSolvePath`**: solvePath is compressed (pressure-ladder segment → `p`, then an
   80+"/.../"+43 clamp) at the single shared diagnostics-assembly site (covers Success and both
   Failure constructions; `terminalFailure` sites use short literals). This is load-bearing, not
   cosmetic — see finding F5.
5. **New tests**: six basin/lane-pinning cold-solve tests + the near-miss canary
   (100 kPa/60 °C/22.5% steam — the analysis doc's E1/G2 cell) in `V3SideDrawCalculatorTest`,
   plus the property-gated 36-cell `V3ColdDoeScreenTest` + `v3ColdDoeScreen` gradle task
   (skipped in the normal suite; JSON report; worker cap 3; per-cell serial recheck flag).

## Cross-checks against the re-screen doc

- 26/36 with steam knot 7/7 and cells 7/34 flipped matches the analysis doc's predicted ~13/18
  steam block exactly; no `cc1affb` success lost (verified row-by-row against the old matrix).
- Cell 34's flip is the predicted §6/§8-item-5 effect: the steam lane's legs now descend from the
  draw-bearing anchor state, which happens to clear the old bare-leg 135 kPa stall for that cell.
  The two 60 kPa steam cells (10/11) remain failures — consistent, since their dry twins fail at
  the anchor/wall (D2/C-family mechanism), not in the steam machinery.
- Rollback decisions match measurement: pacing re-expansion mattered only for cells the
  reordering already fixes (G1/G2 vs F3 equivalence), and truncated intermediates were
  byte-identical (family H).
- The remaining ten failures are exactly the collapsed-trace-column wall families (40% loading;
  60 kPa hot/high-load via the anchor wall) — correctly carried as explicit limits, no admission
  relaxation used.

## Findings

| # | Severity | Where | Finding |
|---|----------|-------|---------|
| F1 | low (robustness) | `prefersSteamFirstDirectAttach` / `run()` | **In-band steam-first has no draws-first fallback.** Out-of-band direct wet inputs try both orders; in-band inputs try steam-first only. On the measured TJL fixture this loses nothing (the in-band failures' draw phases fail on the loading wall in either order), but the bands are fixture-derived (TJL 30-tray, RR 2.0, 8 MW, 8 mol/s steam): a different assay/duty whose in-band steam-first stalls (B2-style) but whose draws-first would pass returns NONCONVERGENCE without trying the order the rest of the lane treats as primary. A symmetric bounded retry (mirror of the existing one) turns the bands from a correctness gate into a pure performance heuristic — the right altitude for fixture-derived constants. |
| F2 | low (test coverage) | `boundedSolvePath` | **The compression branch is untested in the normal suite.** All new lane tests run at 100 kPa (paths ≤ 128); only the gated DOE screen reaches 60 kPa wet paths (140 chars) and dry liquid-only 60 kPa draw paths (135 chars). If the compression regresses, those solves flip to mislabeled `INVALID_INPUT` (the pre-existing hazard it fixes — see F5). A tiny package-private unit test of `boundedSolvePath` (length-140 input → ≤128, ladder segment compressed, prefix/suffix preserved) would pin it. |
| F3 | nit (diagnostics) | `run()` event text | The selection event always says "selected steam-first for the **moderate-loading** basin", including selections via the ≥200 kPa band at 5% loading (a new test even asserts this text at light loading). Say which band actually fired. |
| F4 | nit (reuse) | `V3SideDrawCalculatorTest` | Seven new tests hand-roll the same ~20-line wet-input builder with only pressure/T_cond/loading varying. A `wetInput(pressureKpa, condenserKelvin, loading)` helper would cut ~120 lines and make the band boundaries the visible parameters. |
| F5 | positive (record) | `boundedSolvePath` + `V3SolverDiagnostics` | This silently fixes a **real, user-reachable bug that exists on main today**: `V3SolverDiagnostics` throws for solvePath > 128 chars and `calculateBranch` catches `IllegalArgumentException` as `INVALID_INPUT` — so on main a *converged, audit-passing* dry 60 kPa / cold-condenser (liquid-only) / side-draw solve (path 135 chars) is discarded and mislabeled as invalid input. Worth a line in the merge notes; F2's unit test is the regression guard. |
| F6 | observation (perf) | retry path | A doubly-failing out-of-band direct wet cell now burns up to 2×24 rungs (≈40–60 s) before its typed failure. Bounded by the checkpoint deadline and explained by the retained events; if in-game failure latency matters later, the retry could run with a reduced rung budget. No action needed now. |

## Suite verification (repo CLAUDE.md mandate)

Full `.\gradlew.bat test` at `9d18bb9` in a clean detached worktree: **BUILD SUCCESSFUL in
6m 18s**, 0 failures / 0 errors across the aggregated test-result XMLs (363 test rows over 75
classes; the count includes one stale probe-class XML left from this worktree's earlier analysis
runs — the executed suite itself is fully green, and `V3ColdDoeScreenTest` is correctly skipped
without its gating property). The six new basin/lane tests and the near-miss canary all pass,
which pins the retained policy's observable behavior (anchor attach, steam-last paths, band
selection events) against regression.

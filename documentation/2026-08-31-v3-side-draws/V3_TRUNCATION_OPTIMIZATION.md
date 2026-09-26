# V3 side-draw truncation: diagnosis and optimization

2026-09-01. Branch `codex/v3-stage-side-draws`; baseline commit `bdd18a5`, optimization commit `dd43c98`. All 317 regression tests pass (0 skipped), with the final full run completing in 2m 8s.

## Why enabling truncation was slower

The 100 kPa, 30-tray case with draws at trays 8/15/22 (124/163.25/37.25 kmol/h) did not previously finish as a truncated solve. It ran a failed truncated continuation chain, then the public fallback restarted the entire problem with truncation disabled. The requested cutoff and formulation remained in provenance, so checking the option alone did not show that the returned state was untruncated.

The first-chain diagnostic isolated the failure at the eight-tray grid:

- The mask forced every active component to remain on each draw tray, but could remove the intervening components supplying it from the feed.
- A draw point with no retained inflow caused the support builder to expand to identity. The support report showed **0/150** points removed at this failed grid.
- The expanded problem stalled on a heavy-component material row: scaled residual **1.18376e-6** after **128 Newton iterations**, with 255 rejected local-block directions and 128 fresh finite-difference Jacobians.
- The first chain took **13.8 seconds** before the full cold restart. The optional draw ramp also failed its truncation mass audit; it did not rescue the chain.
- Thus the dominant overhead was failed nonlinear work plus a cold restart, rather than mask construction or feed flash. The feed flash reported no truncation candidates in this case.

Raw diagnosis: `build/reports/benchmarks/v3-truncation-first-chain.json` and `build/truncation-first-chain.log`. The ignored `V3TruncationPathProbe` calls the existing private chain boundary reflectively for diagnosis only; it is not production code.

## The change

`V3TruncationSupport.derive` now retains all active components along the contiguous tray range connecting the feed and every specified draw. For a draw above the feed this preserves its vapor supply path; below the feed it preserves the liquid supply path. Trace pruning continues outside that range.

This is a conservative mask correction. It retains more of the original equations instead of relaxing the physical model. No Newton limits, convergence tolerances, audit limits, draw rates, pressure ladder, cancellation behavior, or cold fallback rules changed. No persistent numerical cache or cross-request state was introduced.

With no side draws, the retained range is exactly the feed tray, preserving the old mask derivation. Cutoff zero still returns before deriving any mask. Matched no-cutoff samples returned identical digests and streams before and after the change.

The optimized case finishes with **32/480 component-stage points removed**, without a cold untruncated retry. Its independently audited missing-mass fraction is **7.269315e-7** (0.727 ppm of feed), below the unchanged **8e-6** budget. Against the exact-off result, the maximum observed stream differences were 0.000557 mol/s in total flow, 0.000317 K in temperature, and 1.192e-5 in component mole fraction.

## Measurements

Identical 30-tray inputs, heap settings (512 MB initial / 2 GB maximum), one 150 kPa warmup, three serial samples per case, alternating paired order, and the unchanged 45-second per-call deadline. Times include the public facade and final deadline checkpoint, but exclude Minecraft queue time.

| Run | Cutoff 0 median | Cutoff 1e-6 median | Returned removed points with cutoff |
| --- | --- | --- | --- |
| Baseline `bdd18a5` | 9.854 s | 22.379 s | 0 (cold fallback) |
| Initial optimized repeat | 10.228 s | 5.974 s | 32 |
| Final post-test, post-commit rerun | 9.328 s | 5.580 s | 32 |

The initial matched repeat reduces cutoff-enabled time by 73.3% (3.75 times faster) and cumulative allocation by about 75% (64.85 GB to 16.26 GB allocated across the solve, not peak live memory). The optimization is structural; it does not promise a speedup for every input or eliminate legitimate audit-triggered fallback.

The requested final rerun was performed after the full tests and commit, with three samples of every original side-draw case. Cutoff-enabled times were 5.548/5.580/5.808 s. The 150 kPa qualified exact-off case passed at a 2.562 s median; the original full-rate reference cases still failed honestly at 150/250 kPa (3.697/1.548 s median). Evidence: `build/reports/benchmarks/v3-truncation-final.json`.

The requested follow-up sweep covers 60, 70, 80, 90, and 100 kPa, with cutoff 0 and 1e-6, the same quarter-rate draws, three serial samples per cell, and a 45-second deadline. **All 30 runs succeeded. Every cutoff-enabled run remained reduced, with no cold untruncated fallback.**

| Pressure (kPa) | Cutoff off median | Cutoff on median | Off / on speedup | Removed points | Maximum defect (ppm of feed; limit 8) |
| --- | --- | --- | --- | --- | --- |
| 100 | 10.368 s | 5.911 s | 1.75x | 32/480 | 0.727 |
| 90 | 10.223 s | 6.244 s | 1.64x | 33/480 | 2.123 |
| 80 | 10.856 s | 6.966 s | 1.56x | 33/480 | 7.566 |
| 70 | 36.178 s | 10.121 s | 3.57x | 31/480 | 2.216 |
| 60 | 38.575 s | 11.856 s | 3.25x | 31/480 | 7.297 |

The slowest exact-off 60 kPa sample took 41.507 s, leaving little admission/queue headroom in the game's 45-second budget; this facade benchmark excludes queue time. Truncated samples at 60 kPa ranged from 11.135 to 11.955 s. At 80 and 60 kPa, the missing-mass defect is close to the unchanged 8 ppm budget, so success here does not justify relaxing that budget for other conditions.

Raw sweep and summary: `build/reports/benchmarks/v3-truncation-60-100kpa.json` and `v3-truncation-60-100kpa-summary.json`. Source identities are in `v3-truncation-60-100kpa-source-hashes.json`. The complete serial sweep took 7m 29s.

### Post-review side-draw continuation rerun (`321fd8a`)

After applying `V3_SIDE_DRAW_REVIEW.md`, the same 60–100 kPa matrix was rerun post-commit with one warmup and three samples per exact/cutoff cell. All 30 calls succeeded; no cutoff call fell back to an untruncated chain. Exact medians were 11.199/11.270/12.812/13.972/13.542 s from 100→60 kPa. Cutoff medians were 9.081/10.050/10.587/11.464/11.746 s. Exact 70 and 60 kPa improved by 61.4% and 64.9% because draw-bearing pressure continuation now stays inside the local basin with 5 kPa steps. The cutoff masks removed 32/33/33/31/31 of 480 stage-component points; their maximum feed-relative defects were 7.269315e-7, 2.122814e-6, 7.565589e-6, 2.215711e-6, and 7.296706e-6, all below the unchanged 8e-6 limit.

Raw evidence: `build/reports/benchmarks/v3-side-draw-review-final-60-100kpa.json`, `v3-side-draw-review-final-60-100kpa-summary.json`, `v3-side-draw-review-final-comparison.json`, and `v3-side-draw-review-final-source-hashes.json`.

```powershell
.\gradlew.bat v3TimeoutBenchmark '-Pv3Cases=draws-100-off,draws-100-on,draws-90-off,draws-90-on,draws-80-off,draws-80-on,draws-70-off,draws-70-on,draws-60-off,draws-60-on' -Pv3Warmup=1 -Pv3Samples=3 -Pv3DeadlineSeconds=45 '-Pv3Report=build/reports/benchmarks/v3-truncation-60-100kpa.json'
```

## Regression coverage

- Retained supply paths for draws above and below the feed, while still pruning trace points outside the required range.
- Valid structural ledger after the mask change and positive seed projection for restored transport points.
- The public 100 kPa side-draw case must actually finish reduced, with no cold fallback, rather than merely return success after retrying.
- All six products account for the independent truncation mass defect.
- Existing no-draw mask, Jacobian, conservation, audit, cutoff-zero, provenance, and cancellation tests remain in the full suite.

Before/after evidence: `build/reports/benchmarks/v3-truncation-before.json`, `v3-truncation-after.json`, and `v3-truncation-optimization-source-hashes.json`. Benchmark sources and generated reports remain ignored local artifacts.

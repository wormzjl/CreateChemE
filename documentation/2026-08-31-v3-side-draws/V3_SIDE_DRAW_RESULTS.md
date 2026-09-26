# V3 liquid side-draw implementation evidence

Branch: `codex/v3-stage-side-draws`, based on `codex/hybrid-solver` at `54a4203`. Implemented 2026-08-31.

Commits: `03e3223` untracks benchmarks without removing local files; `bdd18a5` implements and tests side draws. The full Gradle suite passed in 2m 31s; the final targeted failure-diagnostic and six-stream codec checks also passed. Working tree is clean (documentation and benchmarks are ignored).

**2026-09-01 update:** the measurements below describe the initial implementation. [The subsequent truncation optimization](V3_TRUNCATION_OPTIMIZATION.md) preserves feed-to-draw material paths and eliminates the cold retry in the qualified 100 kPa case; it now finishes as an audited reduced solve. Consult that report for current timings.

## Delivered

- Up to three immutable, sorted, positive liquid draw rates, one per equilibrium tray; feed tray and last tray supported. Total draws must be below feed.
- Proportional splitting of the stored total liquid flow into a product and liquid to the next node, including the reboiler inlet. No additional unknowns, equations, specification types, or matrix bandwidth.
- Widened structural references; draw-aware cold traffic, energy recurrence, and TDMA withdrawal estimates. The estimate cap is seed-only; physical residuals and audits use the uncapped ratio.
- Independent positive-downflow audit, intermediate-tray failure diagnostics, absolute-rate stage mapping with collision merging, pressure preservation, and bounded draw-rate recovery.
- Six product streams with stable tray IDs and SI properties. Editable GUI rows use kmol/h; blank/zero disables a row. The default remains no draws. Streams have horizontal pagination with bounded column text.
- Input schema 1 retained; wire schema 4 and block data version 5. Legacy saves without the list load unchanged. A fixed-revision golden test proves no-draw digest byte preservation. The assumptions revision bump to r4 intentionally changes current calculation provenance.
- Exact local block derivatives verified against whole-system finite differences, including the dense split term. Truncation force-retains draw trays, counts only downflow at liquid sink edges, and falls back to identity for an isolated forced point.
- The unwired sum-rates and hybrid preconditioners explicitly decline draw-bearing inputs.

## Feasibility and timing

All cases below use 30 trays, feed tray 24, 2610.7 kmol/h Tia Juana Light at 638.15 K, a 400 K condenser, reflux ratio 2, duty 8 MW, and 750 Pa/tray pressure drop. Draw trays are 8/15/22.

The original 496/653/149 kmol/h rates did **not** pass the 4-tray continuation rung at 150 or 250 kPa. Rate continuation reaches an equation-closed candidate that fails `SIDE_DRAW_SPLIT` at 50% (150 kPa) or 75% (250 kPa). The requested-rate failure is retained; no smaller-rate result is published. Failure on an intermediate grid is not proof that the full 30-tray physical problem is infeasible. The operating controls are never silently adjusted.

A qualified example uses **124/163.25/37.25 kmol/h** (one quarter of those reference rates). Its six product streams close component balances and draw temperatures increase with tray number.

| Case | Cutoff | Outcome | Wall time |
| --- | --- | --- | --- |
| Qualified, 100 kPa | 0 | Accepted | 10.194 s |
| Qualified, 100 kPa | 1e-6 | Accepted | 24.111 s |
| Qualified, 150 kPa | 0 | Accepted | 2.682 s |
| Original reference, 150 kPa | 0 | Typed failure; tray diagnostic | 3.862 s |
| Original reference, 250 kPa | 0 | Typed failure; tray diagnostic | 1.711 s |

These are single serial public-facade measurements in one JVM, with no explicit warmup, 512 MB initial heap / 2 GB maximum heap, and a 45-second cooperative deadline. The first case starts cold; later cases benefit from JVM warming. They exclude Minecraft admission/queue latency and are not an SLA or a formal speedup comparison. The existing game deadline is unchanged.

Before enabling the local-block path, the FD-only tests accepted the qualified case at 150/100/250 kPa in 5.600/8.637/6.137 seconds respectively. These were JUnit runs with different warmup/heap conditions, so they verify budget feasibility rather than relative performance. The final local-block tests additionally cover 250 kPa and positive-cutoff pressure continuation.

The high-rate failure justified implementing the plan's contingency: after an ordinary draw-bearing rung fails, retry at 0/25/50/75/100 percent at the same geometry. Every rung needs full convergence evidence and a fresh audit, under the same caller deadline. This does not rescue the original reference case and is not invoked speculatively on successful rungs.

## Verification and reproduction

Regression coverage includes contract/defensive-copy validation, golden digest, unchanged DOF counts, stage collision merging, hand-computed material/energy rows, independent ideal MESH oracle solve, full-vs-local Jacobian comparisons (including truncation), strict split audit, scaled sink defects, forced draw retention and identity fallback, public facade solves/failures, runtime command pass-through, screen draft parsing, actual wire/NBT round-trips for 0..3 draws, legacy NBT, malformed list bounds, and six-stream display certificates.

Run the complete suite with `.\gradlew.bat test`. The test classpath now includes the existing Minecraft runtime so codec tests exercise real buffers and tags, without launching the game.

The ignored local benchmark was extended with `draws-100-off`, `draws-100-on`, `draws-150-off`, `draws-canonical-150`, and `draws-canonical-250`:

```powershell
.\gradlew.bat v3TimeoutBenchmark '-Pv3Cases=draws-100-off,draws-100-on,draws-150-off,draws-canonical-150,draws-canonical-250' -Pv3Warmup=0 -Pv3DeadlineSeconds=45 '-Pv3Report=build/reports/benchmarks/v3-side-draws.json'
```

Raw evidence: `build/reports/benchmarks/v3-side-draws.json`, `build/side-draw-benchmark.log`, and JUnit XML under `build/test-results/test/`. Benchmarks and documentation remain ignored local artifacts, as requested. The source regression tests remain tracked.

The GUI layout and parsing were reviewed and compiled; an interactive Minecraft play-test was not performed. The 100 kPa truncated example may use the existing untruncated fallback and should not be described as proof of reduced-mask convergence on every rung.

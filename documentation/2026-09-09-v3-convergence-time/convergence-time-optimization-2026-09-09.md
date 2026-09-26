# Convergence-time optimization: measured cost structure and individual proposals

Date: 9 September 2026. Base: `main` at `e8d8937` (worktree `recursing-lamarr-6cb268`). Follows the [Plan 2 report](../2026-09-08-v3-newton-alternatives/plan-2-experiment-results-2026-09-09.md), which recorded what was tried (DF-SANE, map contracts); this document measures where the time of the current production path actually goes and proposes optimization methods one by one. No production source was changed. All measurements come from an instrumented *copy* of the science sources (`build/pkgcmp/` in the worktree, see §6), run through the public `V3ColumnCalculator.calculate` entry point on the literature CDU preset (`ColumnCalculatorV3BlockEntity.literatureCduInput()`: TJL19 package, 40 trays, feed 37, 250 kPa, three draws, sump steam 1,200 kmol/h, three coolers) and on the plain 40-tray column.

The maintainer's constraints for this round: propose methods individually, and a minor loss of precision is acceptable.

## 1. Headline

The literature preset takes **13.0 s** (solo run, Zulu 25, `-Xmx2g`, after a warm-up solve). Of that, **12.6 s is inside the 16 Newton rungs**; audits, seeds and bookkeeping are under 0.5 s. Inside the rungs two things dominate, and neither is thermodynamics:

| Cost centre | Share of run | Evidence |
|---|---:|---|
| Banded LU (`V3BandedPivotedSolver`, `TreeMap` sparse rows) | 44 % by timer, 57 % of JFR samples | 405 solves, 14 ms each at n≈850; `TreeMap.put/getEntry/successor` alone are 47 % of all samples |
| Stage-local block Jacobian (`V3BlockJacobianAssembler.assembleLocal`) | 41 % by timer, 25 % of samples | 319 assemblies, 17.8 ms each; 536,515 full-state `decode` calls (19 % of samples by themselves) |
| Final-correction verification + damped Gauss–Newton cascade | 14 % of samples | 25 verifications, each a fresh coloured FD Jacobian + LU + up to 8 doubled-bandwidth LU |
| Coloured FD Jacobian | 7 % | 44 builds, 20 ms each |
| Residual evaluation | 6 % | 11,982 evaluations, 60 µs each |
| PR78 kernel (`evaluatePrepared`) | **5 %** | 1.95 M fugacity calls, ≈0.3 µs each |
| DOF ledger / resolver rebuilds per attempt | 5 % | `V3DegreeOfFreedomLedger.augment` |
| GC | 1.2 % | 136 pauses, 159 ms |

The other lever is the iteration count: **352 Newton iterations** for 16 rungs, of which **91 (26 %) are a plateau at scaled residual exactly 1.00** where one retained trace flow is shrunk by exactly one e-fold per step (§3), **47** are a steam rung that stalls and fails, and **41** are the first attempt of the final rung that stalls until a support refresh.

Two micro-benchmarks set the ceiling: a flat-array banded LU solves the same 874-row Jacobian **13.8× faster** with an identical solution (§2.1), and the coloured FD Jacobian and the local block Jacobian produce the *same* Newton trajectory at the *same* cost (§2.2), so the block assembler buys nothing today and an analytic block Jacobian is the real target.

## 2. Measurements

### 2.1 Per-rung cost of the preset

Solo run with counters (`LitTiming preset`, JFR on; the JFR overhead is a few percent). `it` is the iteration count of the rung's last attempt; `newtonIt` the total over all attempts of the rung (a rung is one initial attempt plus support/wet-set refreshes).

| Rung | ms | outcome | attempts | newtonIt | LU (ms) | local Jac (ms) | FD Jac | notes |
|---|---:|---|---:|---:|---:|---:|---:|---|
| stage 4 | 63 | conv | 2 | 11 | 32 | 12 | 2 | |
| stage 8 | 398 | conv | 2 | 42 | 257 | 114 | 2 | 19 plateau iterations |
| stage 15 | 934 | conv | 2 | 45 | 601 | 285 | 2 | 19 plateau |
| stage 30 | 1,447 | conv | 2 | 47 | 635 | 737 | 2 | 21 plateau |
| stage 40 | 1,554 | conv | 2 | 42 | 567 | 896 | 2 | 21 plateau |
| steam 1/24 … 9/24 | 176–455 each (2,256 total) | conv | 1–2 | 5–11 | | | | 5 iterations each |
| steam 10/24 | 2,557 | LINE_SEARCH_EXHAUSTED | 2 | 47 | 1,343 | 1,017 | 4 | 41 stalled iterations, 786 line-search trials, 4 damped cascades |
| requested input (steam 1, heat 1, draws 1) | 3,372 | conv | 5 | 66 | 1,381 | 1,359 | 19 | first attempt 41 iterations stalled; refresh converges in 13 |
| **total** | **12,583** | | 28 | 352 | 5,751 | 5,311 | 44 | |

After the steam rung fails, the existing ramp logic skips every remaining intermediate rung (heat 1/4 … 4/4, draws 1/8 … 8/8) and solves the requested input directly from the failed 42 %-steam state, and that converges. So the whole heat and draw ramp is never executed on this preset; its cost is the stage grid (35 %), nine steam rungs (18 %), one failed steam rung (20 %) and the final solve (27 %).

### 2.2 The two Jacobian paths cost the same and give the same trajectory

Running the stage and ramp rungs with the coloured finite-difference Jacobian instead of the local block assembler (`-Dprobe.fullJacobianStages -Dprobe.fullJacobianRamp`) gives the same iteration counts, the same plateau, the same 0.368 contraction ratio in the tail, and the same result digest:

| | local block Jacobian | coloured FD Jacobian |
|---|---:|---:|
| builds / mean cost | 319 / 17.8 ms | 343 / 19.9 ms |
| preset time (concurrent batch) | 13.9 s | 16.0 s |
| fugacity calls | 1.95 M | 5.09 M |
| full-state decodes | 536,515 | 79,227 |

The block assembler uses 3× fewer property calls but pays for them with two full-state `decode` calls per column (one `exp` per coordinate and three array allocations each) and a `LocalNodeTerms` allocation per probe. The local Jacobian is accurate (the trajectories coincide), so the ~40 iterations per stage rung are not a Jacobian-quality problem (§3).

### 2.3 LU micro-benchmark

One Jacobian was dumped from the 40-stage rung (n = 874, lower bandwidth 76, upper 40, 15,065 nonzeros = 17 per row) and solved 40 times by the production solver and by a plain LAPACK-`dgbtrf`-style dense banded LU with row/column equilibration and partial pivoting (`build/pkgcmp/LuBench.java`):

| solver | min | mean | relative residual |
|---|---:|---:|---:|
| production `V3BandedPivotedSolver` (TreeMap sparse rows) | 11.83 ms | 14.13 ms | 8.5e-16 |
| dense banded flat-array LU | 0.85 ms | 1.64 ms | 8.5e-16 |

Speed-up 13.8× (min/min); max solution difference 0.0 at |x| ≤ 58.8. The production factorization does little arithmetic; its time is boxed `TreeMap` traffic (`put` 22 %, `getEntry` 14 %, `successor` 8 % of all run samples).

### 2.4 Iteration trace of a stage rung

The 40-stage rung of the plain column (n = 1,039), with the largest coordinate step per iteration:

```
it=0  max=3.44   step +18.5 K  TEMPERATURE/2
it=1  max=1.00   step  -9.1 K  TEMPERATURE/36
it=2  max=1.00   step  +3.0 K  TEMPERATURE/35
it=3  max=1.00   step -1.000  LIQUID_COMPONENT_FLOW/36/18  (ln flow  -4.5 -> -5.5)
 ...  max=1.00   step -1.000  same coordinate, every iteration
it=21 max=1.00   step -1.000                                (ln flow -22.5 -> -23.5)
it=22 max=0.612  step -1.000
it=23 max=0.225  step -1.000
it=24 max=0.0829 ...  ratio 0.368 = 1/e per iteration
it=40 max=9.3e-9                                            (ln flow -36.5)
```

Component 18 is the heaviest pseudo-component (TJL_PC13); tray 36 is the tray above the feed. The seed put its liquid flow at 1 % of the component's feed flow (linear interpolation of the 30-stage profile smears the feed tray into the tray above), the converged value is 1e-16 of it, and Newton in log coordinates moves such a coordinate by exactly −1 per full step: for a residual `r = inflow − a·e^z` with `a·e^z ≫ inflow`, the Newton step is `Δz = −1 + inflow/(a·e^z)`. The row reads exactly 1.00 (its imbalance equals its own largest term) until the outflow falls below the inflow, then decays by 1/e per iteration until it is below 1e-8 of the row scale. The row scale is floored at 1e-10 of the component scale (`TRACE_FLOOR_FRACTION`), so a point that physically sits at 1e-16 needs ~18 e-folds below the floor before its row closes.

The same signature appears on every stage rung with a different point each time: 8-stage rung `VAPOR/4/15` from ln −27.4, 15-stage `VAPOR/7/13` from −25.4, 30-stage `VAPOR/26/16` from −21.9. Those start at or near ln(1e-10) = −23.0: they are points the support floor reinserted at the floor value (`V3TruncationSupport.projectSeed`, `liftFloorSupport`) although the physics puts them 5–15 e-folds lower. Plateau lengths: 19, 19, 21, 21 iterations on the four stage rungs and 11 in the first attempt of the requested rung; 91 of 352 iterations in the preset.

### 2.5 Experiments (concurrent batch, so absolute times are 5–15 % above solo; compare within the batch)

| experiment | time | outcome | reading |
|---|---:|---|---|
| baseline | 13.9 s | success, digest `2e924d3a…` | reference for this batch |
| direct jump from the dry 40-stage solution to the requested input (no ramp) | 7.6 s | **NONCONVERGENCE**, residual 15 | the ramp is needed; the predictor's 40 K cap is hit |
| steam ramp 12 rungs instead of 24 | 11.3 s | success, same digest | fails at 5/12 (42 %) then jumps and converges |
| steam ramp 6 rungs | 11.9 s | success, same digest | fails at 3/6 (50 %) then jumps; the failed rung costs 3.8 s |
| authored closure 1e-3 (existing knob) | 22.4 s | success, final residual 3e-9, digest changes | 37 verifications, 502 LU: the verification cascade fires at every iteration below the loose gate |
| loose tolerance 1e-3 on intermediate rungs only | 22.2 s | success, same digest | same mechanism |
| loose tolerance 1e-5 on intermediate rungs | 15.7 s | success, same digest | +13 % |
| stage-trace cutoff 1e-6 (existing feature) | 17.7 s | success, digest `b47c503f…` | 34 attempts: refresh churn outweighs the smaller system |
| log-space interpolation of the stage seed | 13.5 s | success, same digest, same iteration counts | the floor lift re-seeds the point afterwards |
| log interpolation + no bubble-point projection | 16.1 s | success, same digest, same plateau | as above |
| coloured FD Jacobian on all rungs | 16.0 s | success, same digest, identical trajectory | §2.2 |
| merit stall detector (window 8, factor 0.8) | 10.7 s | **failure**: final rung killed at it=8 | catches the failed steam rung (2.65 → 0.64 s) but the plateau's merit also barely moves |
| e-fold step multiplier ×6 on coordinates stepping −1 twice | 30 s plain / 63 s preset | 40-stage rung 41 → 21 iterations, 8-stage 40 → 21, but 15- and 30-stage rungs 42 → 250 iterations with 124 damped cascades; preset fails | boosted trials pass Armijo and land in a bad basin; a fallback to the plain direction does not help because the boosted step is accepted |

## 3. Why the iteration count is what it is

1. **E-fold plateau (26 % of iterations).** One retained trace point per rung is seeded orders of magnitude too high, either by linear interpolation across the feed tray or by floor reinsertion at 1e-10 of the component scale, and the log-flow formulation can shrink it only one e-fold per Newton step. The Jacobian is exact for this row; the rate is structural. Step manipulation was measured to be unsafe (§2.5); the fix belongs to the seed and to the row's closure allowance (§4, P3).
2. **A physical barrier in the steam ramp at 42–50 % steam.** The dominant residual is the ethane equilibrium row of the sump; the rung stalls whatever the step size (24, 12 or 6 rungs). The existing skip-ahead then solves the requested input from the failed state. The ramp's value on this preset is therefore the seed it leaves behind at ~40 % steam, not the schedule.
3. **A failed rung is expensive by construction.** 41 stalled iterations at the cap, 20 backtracks per iteration once Armijo fails, then up to 8 damped normal-equation solves at doubled bandwidth (4× an LU each) and a gradient fallback: 2.6–3.8 s per failure.
4. **The final-correction certificate is paid per attempt.** Every converged attempt (25 in the preset, two per rung) needs a fresh coloured FD Jacobian and LU; when the residual gate is met before the step gate the damped cascade runs too. This is why every "looser tolerance" knob is *slower* today.

## 4. Proposals, individually

Each proposal is independent; expected gains are against the 13.0 s solo baseline and are multiplicative when combined. "Precision" states what the published result would lose.

### P1. Replace the TreeMap sparse LU by a flat-array banded LU
- **What:** implement `V3BandedPivotedSolver.solve` on contiguous `double[]` band storage (LAPACK `dgbtrf`/`dgbtrs` layout, upper fill `kl+ku`), keeping the existing row/column equilibration, pivot tolerance, ill-conditioning ratio, backward-error check and `Result` types. `V3BandedMatrix` is already dense band storage, so this is confined to one class.
- **Evidence:** §2.3, 13.8× on the real Jacobian with an identical solution; LU is 44–57 % of the run and the doubled-bandwidth normal-equation solves in the fallback and verification paths are LU too.
- **Expected gain:** −40 to −50 % of total time (LU share → ~4 %); failed rungs and verifications shrink proportionally more.
- **Precision:** none. **Effort:** ~300 lines + the existing linalg tests. **Risk:** low; the current solver's diagnostics can be recomputed from the dense factors.
- **Further step (optional):** the band is only 17 nonzeros per row, so a symbolic sparse LU with a fixed elimination pattern, or a block-tridiagonal LU on the 39×39 stage blocks, could reach ~0.2–0.3 ms; not needed until P2 makes the Jacobian cheaper than the LU.

### P2. Analytic (or forward-AD) thermodynamic derivatives for the stage block Jacobian
- **What:** make the PR kernel return `∂ln φ_i/∂T`, `∂ln φ_i/∂n_j`, `∂h/∂T`, `∂h/∂n_j` (closed form for PR78 with the quadratic mixing rule, Michelsen–Mollerup) and assemble the VLE, energy and water-saturation blocks from one evaluation per node and phase. Material rows are already exact; side-draw and free-water couplings already have closed forms in `V3BlockJacobianAssembler`.
- **Evidence:** §2.2: the block assembler spends 17.8 ms per Jacobian on 78 probes per node, each with a full-state `decode` (19 % of all samples) and allocations; the kernel itself is 5 % of the run. The existing coloured FD Jacobian is a ready-made oracle for the derivative tests (`V3BlockJacobianAssemblerTest` already compares blocks at 1e-5).
- **Expected gain:** Jacobian 17.8 ms → ≈1 ms; −35 to −40 % of the current run, and with P1 the per-iteration cost falls from ~37 ms to ~4–5 ms (≈8×).
- **Precision:** none; exact derivatives are more accurate than one-sided probes at domain boundaries. **Effort:** high (kernel math, ~600 lines with tests). **Risk:** moderate, contained by the FD oracle.
- **P2a, interim:** keep the probes but perturb one node's flows in place instead of decoding the whole state, and reuse per-node scratch arrays. Removes the 536k decodes and the `V3DryMeshState` copies: −15 to −20 % now, ~50 lines.

### P3. Seed repair for oversized trace points (the e-fold plateau)
- **What:** stop seeding retained trace points orders of magnitude above their balance value. Three concrete options, cheapest first:
  1. interpolate stage profiles section-wise (rectifying section and stripping section mapped separately, feed tray to feed tray) so the feed-tray discontinuity is not smeared into the tray above;
  2. in `liftFloorSupport`/`projectSeed`, cap a retained point at the equilibrium-split value of its actual inflow (the `reinsertRemovedPoint` split) instead of leaving it at the floor or at the interpolated value when it exceeds its inflow by more than a factor;
  3. *precision trade:* let a material row whose scale is floored close at an absolute allowance (for instance 1e-2 of the floor, i.e. 1e-12 of the component's feed flow per tray) instead of 1e-8 of the floor. This removes ~13 of the ~19 plateau iterations by itself.
- **Evidence:** §2.4; 91 of 352 iterations, 19–21 per stage rung, always one coordinate stepping exactly −1. Negative controls in §2.5 show interpolation alone and the projection are not the mechanism, and that step multiplication is unsafe.
- **Expected gain:** −20 to −25 % of iterations, i.e. −20 % of time today and proportionally more once P1/P2 make iterations cheap.
- **Precision:** none for options 1–2; option 3 changes closure of trace rows below 1e-10 of the component scale, invisible in published streams. **Effort:** low for 1, moderate for 2 (must keep the audited sink-edge defect bounded), low for 3. **Risk:** the support/refresh logic is delicate; verify with the 465-test suite and the digests of the A–E cases.

### P4. Adaptive steam ramp with early skip-ahead
- **What:** replace the fixed 4–24 equal steam rungs by a doubling schedule (1/24, 3/24, 7/24, 15/24, 1) with halving on failure, and keep the existing rule that after an intermediate failure the requested input is attempted directly.
- **Evidence:** §2.5: 12 rungs −19 %, 6 rungs −15 %; the failure at 42–50 % is independent of step size, and the skip-ahead converges. The 24-rung count was introduced for a small-reboiler-duty case that diverged at 12, so the schedule must stay adaptive rather than fixed.
- **Expected gain:** −15 to −20 % on steam-bearing inputs; nothing on dry ones.
- **Precision:** none. **Effort:** low. **Risk:** moderate; needs the pumparound/steam regression cases (`V3PumparoundSteamCalculatorTest`, 6 cases currently 8–20 s each) as the gate.

### P5. Fail fast on intermediate rungs
- **What:** on ramp rungs only, (a) cap the damped Gauss–Newton cascade at two damping levels and skip the gradient fallback, (b) stop an attempt when the max scaled residual has not halved over the last 12 iterations while it is still above 1e-3. Do **not** apply (b) to stage rungs or to the requested rung: the plateau of §2.4 has an almost flat merit and is productive (the (8, 0.8) merit detector in §2.5 killed the final rung).
- **Evidence:** the failed steam rung: 41 stalled iterations, 786 line-search trials, 4 cascades, 61 LU (1.34 s) in 2.6 s; the stall detector recovered 2.0 s of it before it broke the final rung.
- **Expected gain:** −15 to −20 % on inputs with a failed rung (this preset); zero otherwise.
- **Precision:** none. **Effort:** low. **Risk:** low if restricted to ramp rungs.

### P6. Verify the final correction once, with the block Jacobian
- **What:** `verifyFinalNewtonCorrection` currently rebuilds a coloured FD Jacobian and may run the doubled-bandwidth damped cascade at *every* iteration whose residual is below tolerance but whose step evidence is unavailable (always, on the local-block path). Verify with the last block Jacobian (or reuse the last factorization), run the damped cascade only on the requested rung, and only once per attempt.
- **Evidence:** 25 verifications, 14 % of samples; the closure knob and loose intermediate tolerances are slower *because* of this cascade (§2.5).
- **Expected gain:** −10 % today; it is also the prerequisite for P8.
- **Precision:** the certificate's backward error would come from the block Jacobian rather than the FD oracle; the fresh acceptance audit is unchanged. **Effort:** low. **Risk:** low.

### P7. Coarser component slate for the in-game default (precision trade, authored)
- **What:** a stage block is 2C+1 unknowns; LU cost scales with C³ and Jacobian assembly with C². A 12-component version of the TJL19 package (6 real + 6 pseudo) would cut the LU ~4× and the Jacobian ~2.5× at the price of product-slate detail; the DWSIM pipeline that generated the 13 pseudo-components can regenerate a coarser one.
- **Evidence:** structural (n = 848 average, band 129 at C = 19); not measured.
- **Expected gain:** −50 to −60 % before P1/P2, less after. **Precision:** visible in product compositions and cut points; a modelling decision for the maintainer, not a solver change.

### P8. Loose closure on intermediate rungs (precision trade, only after P6)
- **What:** solve stage and ramp rungs to 1e-5 or 1e-3 and only the requested rung to 1e-8; the intermediate states are seeds, not results.
- **Evidence:** measured *slower* today (+13 % at 1e-5, +60 % at 1e-3) because of the verification cascade; after P6 the last 3–4 iterations of the linear tail of every attempt (≈10 % of iterations) would be saved.
- **Precision:** none on the published rung.

### P9. Small overheads (each ≤ 5 %)
- DOF ledger and resolver are rebuilt for every attempt and refresh (`V3DegreeOfFreedomLedger.augment` 5 % self time): cache per (topology, support, wet set).
- `V3FugacityResult` clones the log-fugacity array on each of the 1.95 M calls; `normalizedPublicPhaseComposition` allocates per node per residual; `V3MeshResidual` builds 850 row records per evaluation; `Jacobian` copies its dense n×n values twice. Worth doing after P1/P2, not before.

### P10. Measured or reasoned dead ends
- DF-SANE and Anderson on the current seed maps (Plan 2 report): no raw root, no measured gain.
- Direct jump without a ramp: NONCONVERGENCE (§2.5).
- Blind e-fold step multiplier: unsafe (§2.5).
- Merit-based stall detection on stage rungs: kills productive plateaus.
- The existing closure knob and the stage-trace cutoff, as they stand: slower.
- Faster thermodynamics: the kernel is 5 % of the run.

## 5. Combined outlook and suggested order

| step | per-iteration cost | iterations (preset) | est. preset time |
|---|---:|---:|---:|
| today | ~37 ms | 352 | 13.0 s |
| + P1 (flat LU) | ~22 ms | 352 | ~7 s |
| + P2a (decode-free probes) | ~18 ms | 352 | ~6 s |
| + P2 (analytic block Jacobian) | ~4–5 ms | 352 | ~2 s |
| + P3 + P4 + P5 + P6 (seed, ramp, fail-fast, single verification) | ~4–5 ms | ~180 | ~1 s |

Suggested order by gain per effort: P1, P5, P6, P4, P2a, P3, P2, then P8 and P7 as authored precision trades. The verification gate for every step is the full suite (465 tests at the merge) plus the result digests of the literature preset (`2e924d3a10e43a6a` at `e8d8937`), the plain 40-tray column (`f8217d32ae6d6621`), and the pumparound/steam cases, which are the slowest tests today (8–20 s each) and would shrink with the same changes.

## 6. Reproduction

Everything lives in the worktree's gitignored `build/pkgcmp/`:

- `build-probe.sh` copies `src/main/java/.../science` to `src-probe/`, patches the copy with `perl` (counters, per-rung timing, and the experiment switches below), compiles with `javac --release 21` against gson, and copies the resources. Production sources are untouched.
- `LitTiming.java` (package `…column.v3`): `java -cp "build/pkgcmp/classes-probe;<gson.jar>" com.wormzjl.createcheme.science.column.v3.LitTiming preset|plain40|plain30|all [repeats]`, with `-Dprobe.jfr=<file>` for a JFR recording after the warm-up, `-Dprobe.traceIterations=true` for the per-iteration/per-step trace, `-Dprobe.dumpMatrix=<file>` to dump the first Jacobian with n ≥ 800.
- Experiment switches: `probe.directJump`, `probe.steamRampSteps`, `probe.intermediateTolerance`, `probe.closure`, `probe.cutoff`, `probe.logInterpolate`, `probe.noProjection`, `probe.fullJacobianStages`, `probe.fullJacobianRamp`, `probe.stallWindow`/`probe.stallFactor`, `probe.traceBoost`.
- `LuBench.java` (package `…v3.linalg`): `… LuBench build/pkgcmp/matrix.txt 40`.
- `jfr-aggregate.pl`: `jfr print --events jdk.ExecutionSample --stack-depth 64 x.jfr | perl jfr-aggregate.pl`.
- Logs of every run quoted above: `preset-run.log`, `preset-trace.log`, `preset-samples.txt`, `exp-*.log`.

## 7. Implementation status (same day, branch `claude/convergence-time-optimization-7f5b84`)

Implemented by Opus subagents in isolated worktrees, each gated by the full suite and the counter harness, then merged here.

| package | commit | result |
|---|---|---|
| P1 flat-array banded LU | `6a5ab33` | bit-identical to the `TreeMap` solver (reference copy kept in test sources; bit-identity test over 680 random bands, every failure path and the real 874-row Jacobian, now shipped as `src/test/resources/column/v3/literature-cdu-jacobian.txt`); LU on the preset 5.75 s → 1.0 s |
| P2a decode-free stage-block probes | `9797731` | bit-identical (1,982 probes of the real-crude fixture compared bitwise); assembler 5.3 s → 1.2 s, decodes 536,515 → 11,576 |
| merged wave 1 | `1257745` | literature preset **13.0 s → 4.4 s**, identical counters (352 iterations, 405 LU, 44 FD Jacobians) and digest `2e924d3a10e43a6a`; full suite 473 tests, 0 failures, 2 m 37 s (was ~5 min) |
| P4/P5/P6 rung policy | `5a50e61` | doubling steam ramp (1/24, 3/24, 7/24, 15/24, 1) with immediate skip-ahead; intermediate steam rungs get a `RungBudget` (2 dampings, no gradient fallback, stall stop after 12 iterations without halving the residual above 1e-3) — measured drops: budget on heat/draw rungs (case D fails, B 2→4 iterations), reduced verification on stage rungs (+23 %: the certificate needs the damped correction), steam midpoint retry (+1.2 s), loose intermediate closure (no win); A–E pins hold, digest unchanged; 10 rungs / 21 attempts / 290 iterations / 295 LU / 24 FD Jacobians instead of 16 / 28 / 352 / 405 / 44 |
| merged wave 1+2 | `83d9490` | literature preset **13.0 s → 2.5 s**, same digest and published iterations; full suite 479 tests, 0 failures, 1 m 55 s; `V3PumparoundSteamCalculatorTest` 64 s → 8.8 s, `V3ConvergenceClosureTest` 84 s → 14 s |
| P2 analytic PR78 derivatives | `f5cb1b2` | new optional `V3ThermoDerivatives` capability on `V3PengRobinsonThermo` (∂ln φ_i/∂T, ∂ln φ_i/∂n_j, dh/dT, partial molar enthalpies; water-term derivatives), row derivatives next to the rows in `V3MeshResidualEvaluator.localDerivatives`, the assembler uses them per node with the FD probe as fallback; kernel derivatives agree with central differences to ≤2.6e-8 relative, blocks agree with the coloured FD oracle to ≤3.3e-8 of each row maximum, 32/32 nodes analytic on every real-crude fixture; one existing test moved its FD oracle from the fine to the coarse step because the oracle itself quantises on 1e12 W energy rows (no tolerance loosened, a tighter row-relative assertion added); fugacity calls 1.95 M → 0.93 M, block Jacobian 4.2 → 1.5 ms; digest, published iterations and pins unchanged |
| merged wave 1+2+P2 | `746456e` | literature preset ≈ 2.1–2.5 s (concurrent load), 504 tests, 0 failures |
| P3 trace-seed repair | `1e388f5` | `capOversizedPoint` in the floor lift (a retained point more than 3× above the material its neighbours deliver is reset to the equilibrium split of that inflow, swept in flow direction; feed tray excluded; only ever lowers a flow) plus one clamp on the existing seed map (a rectifying-section node never reads the source feed tray or below); the whole-column section-wise map of the first attempt was measured to break the cold total-condenser case (2.9 s success → 100–180 s non-convergence) and was dropped, as were a both-side clamp (breaks two cold pilots) and geometric interpolation (+3 iterations); plain-40 stage rungs 11/42/45/47/42 → 11/21/28/29/14, plateau 75 → 6 iterations; preset 290 → 204 iterations; digests, paths, pins and published results unchanged (streams move ≤5.5e-12 relative); 509 tests, 0 failures |

(see §8 for the final numbers)

## 8. Final result (HEAD `1e388f5`, quiet machine, serial back-to-back runs of the original and the final probe image)

| case | `e8d8937` (start) | `1e388f5` (final) | speed-up |
|---|---:|---:|---:|
| literature CDU preset | 12.2–13.3 s, 16 rungs, 28 attempts, 352 Newton iterations, 405 LU, 44 FD Jacobians, 1.95 M fugacity calls | **1.5–1.8 s**, 10 rungs, 21 attempts, 204 iterations, 222 LU, 24 FD Jacobians, 0.47 M fugacity calls | **≈7.5×** |
| plain 40-tray TJL19 column | 4.16 s, 187 iterations | **0.55–0.60 s**, 103 iterations | **≈7.3×** |
| full test suite | ≈5 min (455–465 tests) | 1 m 37 s (509 tests, 0 failures, 0 skipped) | ≈3× |

Digests (`2e924d3a10e43a6a`, `f8217d32ae6d6621`), solve paths, published Newton iteration counts and every pinned A–E expectation are unchanged; published streams and duties agree to better than 1e-11 relative. P1 and P2a are bit-identical; P2 is exact rather than finite-difference (kernel derivatives verified to ≤2.6e-8 against central differences); P4–P6 and P3 change only intermediate seeds and rung policy.

Where the remaining 1.66 s goes on the preset: LU 0.47 s (222 solves, 2 ms each), coloured FD Jacobians for the final-correction certificate 0.42 s (24 builds), residual evaluations 0.37 s (6,582), analytic stage-block Jacobians 0.19 s (177), audits 11 ms, the rest (ledger/resolver rebuilds, seeds, bookkeeping) ≈0.2 s.

Follow-ups not done, in order of value: (1) build the final-correction certificate from the analytic block Jacobian instead of a fresh coloured FD Jacobian (0.4 s of 1.66 s; the wave-2 measurement showed the damped correction is needed for the certificate, so keep the damped loop, only swap the Jacobian); (2) the 6 surviving plateau iterations on the 30-stage rung (`VAPOR/26/16`), which would need the absolute closure allowance (option 3 of P3) or a better reinsertion split; (3) residual-evaluation overhead (row records, composition arrays) and per-attempt ledger rebuilds; (4) a symbolic sparse or block-tridiagonal LU if the LU share grows again on larger columns.

Branch state: `claude/convergence-time-optimization-7f5b84` at `1e388f5`, nine commits on top of `e8d8937`, nothing pushed. The main checkout currently carries uncommitted TJL19-migration edits (including `V3ColumnCalculator.java`), so merging this branch into `main` needs those to be committed or set aside first.

## 9. Robustness audit and regression fix (HEAD `db46e88`)

An independent robustness audit (`documentation/V3_OPTIMIZED_SOLVER_ROBUSTNESS_REVIEW.md`) compared `1e388f5` with `e8d8937`: 64-case cold DOE identical (44 successes each), 50 fixture tests identical, deterministic reruns, no crashes; an 85-case perturbation sweep around the literature preset and the plain column showed 5 lost and 5 gained wet-preset perturbations, all traced to the steam-ramp commit `5a50e61` (basin membership after a stalled steam rung), one additionally to the P3 seed clamp.

Fix `db46e88` (`documentation/V3_OPTIMIZED_SOLVER_REGRESSION_FIX_REVIEW.md`): after the requested rung fails, the steam schedule is replayed once at the full rung budget with at most two halvings of a stopped rung (`/full-budget/` in the path). An eager retry at every stopped rung was rejected (preset 1.5 → 3.0 s). Result on the audit's harness: perturbation sweep 61/85 successes (original 54, `1e388f5` 54), 1 case still lost (`steam ×2`, which fails in the stage continuation at the 30-stage rung and is not recoverable by the ramp; root-caused to the rectifying clamp, whose removal costs four other cases), 8 gains over the original; DOE bit-identical to `1e388f5`; preset counters identical, 1.5–1.8 s; wet-preset failure latency roughly doubles (still 3–10× faster than the original); 512 tests, 0 failures.

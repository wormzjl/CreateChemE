# V3 Cold DOE Benchmark — Failure-Cause Analysis and Fix Plan

Date: 2026-09-02.
Analyzed revision: `codex/v3-cdu-ramp-hardening` @ `bb659c8` (analysis worktree branch
`claude/a0-failure-analysis`, which adds a probe harness + three probe-only property gates on top).
Companion docs: `V3_COLD_DOE_BENCHMARK.md` (the screen this analyzes),
`V3_CDU_CONVERGENCE_RISK.md` (the pre-hardening measurement), `V3_FULL_CDU_PLAN.md` (Phase A0).

## Executive summary

The 19 remaining branch failures in the 36-cell DOE screen decompose into **four distinct causes**,
none of which is "the column has no solution there". Probes converged 6 previously-failing cells by
changing only solve-path mechanics (ramp order, rung budget), and identified one shared numerical
mechanism under both the wet and dry stalls.

| # | Cause | DOE cells affected | Fixability | Probe proof |
|---|-------|--------------------|-----------|-------------|
| 1 | **Wet ramp order is backwards**: steam ramps first, then draws must attach onto the wet column — the hardest possible attach. Draws-first converges the same cells easily. | All 7 steam pressure-knot cells (21–27), steam DSD cell 7, interaction cell 34 (partially) | **Structural fix, proven** | F1/F3/F4 converge cells that hard-stall in production order |
| 2 | **`V3AdaptiveRamp` pacing arithmetic**: the increment never re-expands after acceptance, so one early rejection forces floor-step crawling; 24 rungs × 1/32 floor cannot even reach λ=1 (ceiling ≈ 0.75). Also: rungs are rejected on near-misses (e.g. residual 3.1e-8 vs 1e-8 tolerance). | The "expends its extended recovery budget" class: every `RAMP_RUNG_CAP` failure | **Driver fix, proven** | G1 converges B2's cell with cap 96, identical products to the F3 route |
| 3 | **Shared draw-attach stall mechanism**: a collapsed trace-liquid column (PC6, component index 8 — trace in liquid, major in vapor) on the hot tray below a draw makes the linearization near-singular exactly where the withdrawal coupling routes; Newton parks with a stuck material residual at (draw tray)+1. Positions both the dry loading wall and the wet λ-wall. | Dry 40% cells (1, 29, 31), 60 kPa cells (3, 4, 10, 11 via anchor/wall), residual wet walls | Mitigated by #1/#2; truncation lever **measured inert** (family H, byte-identical failures) — root treatment is a linear-layer/formulation design pass (§5) | Identical `COMPONENT_MATERIAL_BALANCE node=(draw+1), component=8` signature across every dry AND wet stall |
| 4 | **Bare-lane hot-condenser leg stall**: with a 100 °C condenser the *bare* (no draws, no steam, surrogate-duty) 145→135 kPa pressure leg stalls at residual 0.093 in its 24-iteration budget — before any ramp runs. | Cells 34/35 (100 kPa, 100 °C, steam), F5/E4 repro | Separate small fix (leg budget/predictor), untested | E4/F5 fail identically at `failed-top-135kpa` regardless of ramp order |
| — | **Refuted**: the condenser water-split `min()` kink as the wet-stall cause; "60 kPa is a solvability frontier" for these cells; "steam-bearing path is fundamentally harder thermodynamically". | — | — | E2/E3 (steam 2 and 16 mol/s) stall identically → steam-rate-independent; D1 converges 60 kPa/75 °C/22.5% dry cleanly |

**Bottom line**: the branch's remaining failure surface is dominated by *solve-path policy*, not
physics or linear algebra. Fix #1 + #2 are small, self-contained, and probe-proven; together they
should flip most of the 19 failures. The one measured true wall (dry ~30% loading at 50 °C — C3
fails only at the final λ=1.0 rung) moves with condenser temperature and is the same mechanism-3
signature, mitigated but not eliminated by pacing fixes.

## 1. Probe method

All probes are cold `V3ColumnCalculator.calculate()` calls on the registered TJL 30-tray fixture
(same settings as the DOE: feed 2610.7 kmol/h @ 638.15 K, tray 24 of 30, ΔP 750 Pa/tray, RR 2.0,
Q_R 8 MW, draws 8/15/22 in 496:653:149 proportion scaled to the stated loading, steam 8 mol/s @
450 K at stage 31 unless stated, cutoff 0 = in-game default). Harness:
`src/test/java/.../V3ColdDoeProbeTest.java` on the analysis branch; each cell appends a CSV row +
full solver-event dump (`scratchpad doe-probe.csv` / `doe-probe-events.log`).

Three probe-only levers were added behind system properties (never merge enabled):

- `createcheme.v3.probe.drawsFirst` — `FeatureRampRunner.runDrawsFirst()`: draw phase first (dry,
  surrogate duty), steam phase second.
- `createcheme.v3.probe.rungCap` — overrides `V3AdaptiveRamp.MAXIMUM_RUNG_COUNT` (24 → 96).
- `createcheme.v3.probe.truncateIntermediates` — lets intermediate ramp rungs use their own
  resolved trace support instead of forced `TruncationPolicy.OFF`.

## 2. Probe results (all families)

| ID | Cell | Variant | Outcome | Failure shape |
|----|------|---------|---------|---------------|
| A1 | 100 kPa, 75 °C, 0% draws, steam | production | **S** 10.4 s | — (steam ramp alone is healthy) |
| A2 | 100, 75, 5%, steam | production | F 25.2 s | `RAMP_MINIMUM_STEP` λ=0.156, plateau 5.6e-3 |
| A3 | 100, 75, 22.5%, steam (= DOE 25-ish) | production | F 24.0 s | `RAMP_MINIMUM_STEP` λ=0.031, plateau 3.4e-3 |
| B1 | 155, 100, 0%, steam | production | **S** 7.3 s | — (hot condenser steam alone is healthy) |
| B2 | 155, 100, 5%, steam (= DOE 7) | production | F 35.9 s | `RAMP_RUNG_CAP` at accepted λ=0.656 |
| B3 | 105, 75, 22.5%, steam (= DOE 27) | production | F 22.4 s | `RAMP_MINIMUM_STEP` λ=0.0625 |
| C1 | 155, 50, 40%, dry (= DOE 1) | production | F 28.2 s | `RAMP_MINIMUM_STEP` λ=0.75, plateau 0.022 |
| C2 | 100, 50, 40%, dry (= DOE 29) | production | F 31.0 s | anchor-attach fails at λ=0.75 (150 kPa) |
| C3 | 155, 50, 30%, dry | production | F 29.8 s | `RAMP_MINIMUM_STEP` **at λ=1.0** (wall ≈ 30% loading) |
| D1 | 60, 75, 22.5%, dry | production | **S** 32.0 s | — (60 kPa is NOT a frontier at 75 °C) |
| D2 | 60, 100, 22.5%, dry (= DOE 4) | production | F 23.9 s | **anchor**-attach fails at λ=0.97 — hot-condenser wall < 22.5%, not a 60 kPa effect |
| D3 | 60, 75, 5%, dry | production | **S** 43.7 s | — |
| E1 | 100, 60, 22.5%, steam | production | F 45.8 s | `RAMP_RUNG_CAP` λ=0.625; **first rung rejected at residual 3.1e-8 vs 1e-8 tolerance**, then floor-crawl |
| E2 | 100, 75, 22.5%, steam **2 mol/s** | production | F 24.7 s | `RAMP_MINIMUM_STEP` λ=0.0625 — same stall as 8 mol/s |
| E3 | 100, 75, 22.5%, steam **16 mol/s** | production | F 25.8 s | `RAMP_MINIMUM_STEP` λ=0.031 — same stall |
| E4 | 100, 100, 5%, steam (= DOE 34) | production | F 18.2 s | **bare pressure leg 145→135 kPa stalls (residual 0.093), pre-ramp** |
| E5 | 155, 100, 5%, steam, **single draw @ tray 8** | production | F 24.7 s | `RAMP_MINIMUM_STEP` λ=0.469 — wall exists with one draw |
| F1 | = A3 cell | **draws-first** | **S** 29.9 s | steam ramp over drawn column: 4 iterations |
| F2 | = A2 cell | draws-first | F 37.2 s | `RAMP_RUNG_CAP` λ=0.875 — dry attach at 100 kPa target still pacing-limited |
| F3 | = B2 cell | draws-first | **S** 25.8 s | — |
| F4 | = B3 cell | draws-first | **S** 16.3 s | — |
| F5 | = E4 cell | draws-first | F 17.6 s | identical bare-leg stall (order-independent, as expected) |
| G1 | = B2 cell | **rung cap 96**, production order | **S** 40.0 s | same products as F3 to 4 digits — cross-validates both routes |
| G2 | = E1 cell | rung cap 96 | **S** 52.8 s | full authored draws attach on the wet 60 °C column given budget |
| H1c/H2c/H3c | A3 / C1 / B3 cells, cutoff 1e-6 | production (intermediates OFF) | F | byte-identical residuals to the cutoff-0 runs — requested-rung truncation is inert here |
| H1/H2/H3 | same cells, cutoff 1e-6 | **truncation-ON intermediates** | F | **byte-identical to controls** (plateaus 0.0034/0.0223/0.0054 to 16 digits) — the lever does not touch the stall |

## 3. Cause 1 — the wet ramp order is backwards (structural asymmetry)

**Code fact** (`V3ColumnCalculator`): the dry lane, since `cc1affb`, qualifies side draws at the
150 kPa anchor / at requested pressure and lets them ride every pressure leg
(`continuationInput = input` when `steamFeeds().isEmpty()`), which produced the dry knot's 7/7.
The steam lane still strips **both** features
(`withoutSideDraws(withoutSteamWithSurrogateDuty(input))`, `solveDwsimPressureContinuation`), runs
every ladder rung and pressure leg bare, then runs `FeatureRampRunner` at the final pressure:
**steam phase first, draw phase second**. So every steam-bearing input performs its draw attach on
the *wet* column at the *lowest* pressure of the whole solve — the exact configuration cc1affb was
written to avoid for dry inputs.

**Measurements**:

- Steam alone is never the problem: A1/B1 (0% draws) converge in 7–10 s, 4 Newton iterations.
- The draw phase on the wet column is always where the knot/hot cells die (paths all end
  `draw-ramp-λ/.../steam-1`).
- Flipping the order (F family): the dry draw attach behaves like the dry lane, and the subsequent
  steam ramp over the fully-drawn column converges in **4 iterations** (F1/F3/F4). Three
  previously-failing DOE-representative cells flip to success with **no formulation change**.
- The wet attach difficulty is *not* thermodynamic: E2/E3 show the stall is unchanged from 2 to
  16 mol/s of steam — the wet column state, not water physics, is what makes the attach hard
  (less internal liquid at the draw trays → mechanism 3 bites at smaller λ).

**Fix (recommended, Phase A0)**: make the steam lane symmetric with the dry lane.

1. In `solveDwsimPressureContinuation`: `continuationInput = withoutSteamWithSurrogateDuty(input)`
   (keep draws). Draws then attach at the 150 kPa anchor (via the existing in-ladder ramp) and ride
   the legs — the configuration proven by the dry knot 7/7 and by D1 down to 60 kPa.
2. In `FeatureRampRunner.run()`: draw phase before steam phase (the probe's `runDrawsFirst()` is a
   working reference). In the pressure lane the runner then only ever sees the steam phase, because
   draws already arrived at full rate with the seed; in the direct lane (>100 kPa) it performs the
   dry attach first at the requested pressure.
3. Relax the runner's precondition ("requires an accepted dry no-draw seed") to accept a
   draw-bearing dry seed, and let `drawRampFeasibilityDiagnostic` keep gating on the bare state.

Provenance note: this is solve-path policy, not formulation — published equations, audits, and
digests are untouched, so **no revision bump** is needed (the `v3-wet-assumptions-r2-ramp` /
`r7-steam-ramp` labels the branch already minted cover the ramp-mechanics change generically).

Expected DOE delta: steam knot 0/7 → 7/7 or near (F4 proves 105 direct; F1 proves 100 via lane;
anchor-attach makes the remaining knot pressures strictly easier than what F1 already passed);
DSD cell 7 → S (F3); cells 8/10/33/35 become bounded by the dry wall position instead of failing
early (cell 8's dry twin C1 still fails — see cause 3; cell 33's dry twin C2 likewise).

## 4. Cause 2 — `V3AdaptiveRamp` pacing arithmetic (driver defect)

Three compounding defects in the adaptive driver, all visible in the event logs:

1. **No increment re-expansion.** `accept()` keeps the bisected increment forever ("Accepted rungs
   retain that smaller step"). After the common pattern "first 0.25-rung rejected → bisect to the
   1/32 floor", *every* later rung is a floor step even when the terrain is easy.
2. **Arithmetic ceiling.** 24 rungs × 1/32 floor = λ ≤ 0.75 reachable from λ=0 even if every rung
   succeeds. E1 (3 rejects + 20 straight floor *accepts* → cap at λ=0.625) and B2 (3 rejects + 21
   accepts → cap at 0.656) died of pure arithmetic, not difficulty: G1 re-ran B2's cell with cap 96
   and converged — in production order — to the same audited solution F3 reached by reordering, and
   G2 likewise converged E1's cell (52.8 s, full authored draws on the wet 60 °C column).
3. **Near-miss rejection.** E1's first rung ended its 48-iteration budget at scaled residual
   **3.1e-8** against the 1e-8 tolerance — a rung that a handful more iterations (or a modest
   tolerance-proximity acceptance rule for *intermediate* rungs, with the final rung still exact)
   would have accepted. That single rejection is what doomed the whole cell to floor-crawling.
   Per-rung budget is `FEATURE_RAMP_MAXIMUM_ITERATIONS = 24` + a 24-iteration terminal full-FD
   recovery; wet rungs routinely use all 48 while dry rungs need 4–6.

**Fix (recommended, Phase A0)**: in `V3AdaptiveRamp`, double the increment after each accepted rung
(capped at the phase's initial increment); keep the floor and the global cap. Optionally: when a
rung's terminal residual lands within ~10× tolerance and is still decreasing, extend rather than
reject (bounded, e.g. one extra 24-iteration leg). Either alone would have converged E1; both
together cost nothing when the terrain is easy.

Cost note: each wet floor rung burns ~1.5–2 s (48 iterations × full-FD). The pacing fix is also a
performance fix — it is the branch's confirmed 36% slowdown case in miniature: G1 needed 40 s in
production order versus 25.8 s draws-first for the same answer.

## 5. Cause 3 — the shared stall mechanism under both walls

Every hard stall in every family — dry high-loading (C), hot-condenser anchor (D2), wet knot
(A2/A3/B3/E2/E3), wet single-draw (E5) — parks with the **same dominant residual**:

```
COMPONENT_MATERIAL_BALANCE, node = (draw tray) + 1, component = 8 (PC6)
```

- 3-draw cells: node 16 (below the largest draw, tray 15), physical −0.21…−1.78 mol/s.
- Single-draw / tray-8-dominant dry cells: node 9 (below tray 8), −0.22…−1.69 mol/s.
- The stall survives 24 iterations of *fresh full finite-difference Jacobians every iteration*
  plus damped normal-equation recovery — the step direction, not the derivative quality, is what's
  broken.

Interpretation (consistent with `V3_CDU_CONVERGENCE_RISK.md` §2's diagnosis): PC6 is a light
pseudo-component that is essentially pure vapor on the ~500–560 K trays adjacent to the draws; its
liquid flows there are collapsed (1e-25…1e-60 mol/s in log coordinates). The withdrawal coupling
`(1 − f_j(state))·l_j,c` routes the draw's perturbation through exactly those collapsed columns,
and the linearization is near-singular in the direction Newton needs to rebalance the vapor
profile below the draw (the −0.3 mol/s that remains stuck is a *vapor*-side imbalance, since
`v_17 − v_16` dominates that row when `l` is trace). Wetness and hot condensers do not create the
mechanism — they shrink internal liquid, which moves the λ/loading at which it bites:

- 50 °C dry wall ≈ 30% loading (C3 fails only at the final λ=1.0 rung, plateau 0.02).
- 100 °C dry wall < 22.5% (D2's 150 kPa **anchor** attach dies at λ=0.97 — the DOE's "60 kPa"
  failures are actually this, since the anchor solve happens at 150 kPa regardless of target).
- Wet at 75 °C/100 kPa: bites at λ ≈ 0.03–0.16 of even 5% loading (A2) in production order —
  and not at all when the attach happens dry (F1).

Truncation interaction — **measured, negative** (family H): intermediate rungs force
`TruncationPolicy.OFF` in production, and the in-game default cutoff is 0, so the DOE ran
untruncated everywhere. Allowing intermediate rungs their own resolved trace support at cutoff
1e-6 changes *nothing*: H1/H2/H3 fail with residual plateaus byte-identical to their
production-policy controls. The explanation is structural: stage-trace truncation removes a
component at a stage only when it is trace in **every** testable phase, and PC6 at these trays is
trace in liquid but *major* in vapor — the exact phase-asymmetric configuration that creates the
singular direction is the one truncation is designed to retain. This also corrects
`V3_FULL_CDU_PLAN.md` §9's earlier assumption that truncation-on-intermediates was a required
hardening element: it is inert for this wall (harmless, but not a fix), and the earlier
risk-doc observation that truncation-OFF "is implicated" is superseded — the stall reproduces
identically with truncation available.

**Fix posture**: causes 1+2 sidestep most of this class (the dry attach at anchor pressure stays
inside the wall for every DOE loading except 40%). For the wall itself, in order of preference:
(a) treat the phase-asymmetric collapsed column in the linear layer — the pivot/damping
escalation the branch added helps Newton *survive* it, not move through it; candidate treatments
are bounded column scaling for trace-liquid unknowns or an elastic/relaxed VLE row for
phase-asymmetric components near draws (formulation-adjacent, needs its own design pass);
(b) accept the wall and surface it honestly (the 0.95 D/L gate never fires on these — worst D/L
at the stall is well under 0.95; a "stuck material residual at draw+1, component PC6" heuristic
could name the mechanism in the failure summary instead of a generic `RAMP_MINIMUM_STEP`).

## 6. Cause 4 — bare-lane hot-condenser leg stall (E4/F5)

At 100 kPa / 100 °C with steam authored, the failure happens **before any ramp**: the bare
surrogate-duty column stalls on the 145→135 kPa pressure leg (24-iteration corrector budget,
residual 0.093, `failed-top-135kpa`). Ramp order is irrelevant (F5 ≡ E4). The dry twin (DOE cell
30, authored duty, draws attached) passes the same legs — the differences are the surrogate duty
(+~0.3 MW) and the absence of draws. Small family (DOE cells 34/35 at most), not further probed;
candidate follow-ups: give legs the same terminal full-FD recovery rungs got, or start the
steam-lane legs from the draw-bearing anchor state once cause-1's fix lands (which changes this
lane's seed entirely — re-measure after fix 1).

## 7. Refuted hypotheses (kept for the record)

- **Condenser water-split kink** (`min(arriving, slip·V_hc)` in `V3ColumnProblem.waterCondenserSplit`):
  predicted steam-rate-dependent stalls (kink at V_hc = steam/slip). E2 (2 mol/s) and E3 (16 mol/s)
  stall identically to 8 mol/s → refuted as the driver. The kink remains real and worth smoothing
  eventually, but it is not what kills these cells.
- **"60 kPa frontier"**: D1/D3 converge 60 kPa dry at 75 °C (22.5% and 5%). The DOE's 60 kPa
  failures are the condenser-temperature-positioned wall (D2 dies at the 150 kPa anchor) plus the
  40% wall (cell 3), not deep-vacuum physics. (The 55 kPa energy-plateau frontier from earlier
  research is a separate, deeper-vacuum phenomenon, untouched by this screen.)
- **"The steam path is fundamentally harder"**: the steam *ramp* is the easiest phase in every
  successful cell (4 iterations). What was harder was the draw attach the steam lane forces onto
  the wet column — an ordering artifact.

## 8. Recommended fix order for Codex (Phase A0 completion)

1. **Ramp order + lane symmetry** (§3, three small edits in `V3ColumnCalculator`) — flips the
   steam knot and most steam DSD failures. Probe-proven (F1/F3/F4). No revision bump.
2. **`V3AdaptiveRamp` re-expansion + near-miss handling** (§4) — flips the `RAMP_RUNG_CAP` class
   (B2/E1-style) in whatever order cells still run steam-first, halves worst-case ramp cost.
   Probe-proven (G1). Unit-testable in isolation (`V3AdaptiveRampTest` pattern exists).
3. **Re-run the 36-cell DOE screen** after 1+2 (same harness discipline) — expected: dry block
   unchanged (12/18 → same or better), steam block from 4/18 to ≥12/18; confirm no regressions on
   the six timed common-success cells (the 36% slowdown case should improve — G1 40 s vs F3 26 s).
4. ~~Family-H decision~~ **Resolved negative**: trace support on intermediates does not move the
   cause-3 wall (byte-identical failures) — do NOT implement it as a hardening element. Cause 3's
   root needs a linear-layer/formulation design pass (§5 fix posture); until then the wall is a
   measured, honestly-diagnosable limit, and causes 1+2 keep the DOE grid clear of it everywhere
   except 40% loading and hot-condenser+high-loading corners.
5. **Leg-stall follow-up** (§6) after 1 lands, since the steam lane's leg seeding changes.
6. Fold outcomes into `V3_FULL_CDU_PLAN.md` §9/§16: the CDU attach machinery (pumparounds,
   strippers) must ramp **each feature over a column that already carries the previously attached
   features in their easiest order** — the measured order sensitivity here (draws before steam) is
   direct evidence for the plan's staged-attach requirement, and the pacing fixes are prerequisites
   for the plan's adaptive-λ machinery.

## Appendix — reproducing the probes

Analysis branch `claude/a0-failure-analysis` @ `ad64786` (worktree-only; based on `bb659c8`).
Harness: `src/test/java/.../V3ColdDoeProbeTest.java` — families A–H as JUnit methods, each cell
appending to `doe-probe.csv` + `doe-probe-events.log` in the session scratchpad. Property gates
(all default-off; `familyG` must run in its own JVM because the rung cap is read at class init):

```
gradlew test --tests "...V3ColdDoeProbeTest.familyA_steamKnotDecomposition"   # etc.
-  createcheme.v3.probe.drawsFirst            (FeatureRampRunner.runDrawsFirst)
-  createcheme.v3.probe.rungCap               (V3AdaptiveRamp.MAXIMUM_RUNG_COUNT)
-  createcheme.v3.probe.truncateIntermediates (per-rung resolved trace support)
```

Determinism note: repeated cells reproduced residual plateaus to 16 digits across runs and
configurations, so single-shot outcomes are trustworthy screening results (matching the DOE's own
determinism claim).

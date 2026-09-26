# Convergence-risk evaluation: high-rate draws today → full CDU plan

Date: 2026-09-01. Trigger: user report that the current solver struggles at higher side-draw
rates; question: what does that imply for the full-CDU implementation
(`documentation/V3_FULL_CDU_PLAN.md` — side strippers + pumparounds)?
Method: code-path analysis of the committed solver (7bccf7b, no steam changes) plus an
empirical draw-rate stress sweep run against it (probe test, results below; raw CSV in the
session scratchpad, numbers reproduced here in full).

## Verdict

The struggle is real, measurable, and **worse than "high rates"**: on the 30-tray Tia Juana
Light case at the easy 150 kPa anchor, the three literature-proportioned draws converge at
0.25× the published rates (12% of feed) and **fail from 0.40× (20% of feed) upward**. The
failures split into two regimes with different fixes:

1. **Ramp stall with physical margin (0.40×–0.75×)** — budget exhaustion while the worst tray
   still holds 1.4–3.5× the requested liquid. Not physics, and — per the discrimination probe —
   **not the budget either**: at the 128-iteration cap the same cases die in a singular damped
   normal-equations factorization ("banded LU zero/tiny pivot"). The fixed 4-rung ramp walks
   iterates into rank-losing states (collapsing trace-component flows) with truncation forcibly
   disabled on intermediate rungs.
2. **True starvation (0.90×–1.00×)** — the stalled iterates cross withdrawal fraction 1 at
   tray 22 (AGO): the dry column with these specs genuinely cannot supply that draw. Physics,
   not solver: no ramp hardening fixes it.

For the CDU plan the net assessment: **HIGH risk as currently specified — the capstone
(Sotelo full CDU, draws ≈ 50% of feed) sits deep inside today's failing region — but the risk
is addressable, and partly self-addressing**: pumparounds add internal liquid exactly where
regime 2 starves, and the plan's continuation section must be upgraded from "same 4-rung ramp,
more attachments" to an adaptive, staged ramp (required changes in §5). Without those changes
the CDU work would compound a known-fragile mechanism 3–6×.

## 1. Where the wall is today (measured)

Probe: committed solver @ 7bccf7b, `V3ColumnCalculator.calculate(input, checkpoint)` (production
truncation default), 30-tray TJL crude (`createcheme:cdu17_tjl_acs2018` /
`createcheme:tia_juana_light`), feed 2610.7 kmol/h at 638.15 K, stage 24, condenser 400 K,
reflux ratio 2.0, Q_R 8 MW, draws at trays 8/15/22 scaled from the literature rates
496/653/149 kmol/h (Sotelo proportions; scale 1.0 ⇒ Σdraws = 49.7% of feed). 40 s budget/case.

### Sweep A — three draws, 150 kPa (anchor pressure, no pressure legs)

| scale | Σdraws/feed | outcome | wall time | worst tray | final withdrawal fraction D/L |
|------:|------------:|---------|----------:|-----------:|------------------------------:|
| 0.25 | 12.4% | SUCCESS (audited, gates pass) | 7.4 s | — | converged |
| 0.40 | 19.9% | FAIL — iteration budget | 23.2 s | 15 | 0.29 |
| 0.50 | 24.9% | FAIL — iteration budget | 29.7 s | 15 | 0.49 |
| 0.60 | 29.8% | FAIL — iteration budget | 24.1 s | 15 | 0.53 |
| 0.75 | 37.3% | FAIL — iteration budget | 23.1 s | 15 | 0.72 |
| 0.90 | 44.7% | FAIL — iteration budget | 21.1 s | 22 | **1.26** |
| 1.00 | 49.7% | FAIL — iteration budget | 23.7 s | 22 | **3.39** |

Readings:

- The success/failure edge is between 12% and 20% of feed withdrawn — **the qualified lane
  (`DRY_QUALIFICATION_SCALE = 0.25`) is already at the edge of capability**, which the test
  suite acknowledges: the full-rate test (`V3SideDrawCalculatorTest.originalLargeDrawCase…`)
  is written to accept failure as long as it is legible.
- In the 0.40–0.75 band the final iterate holds ample liquid at the worst tray (fraction
  0.29–0.72): the solve is **not** near the physical boundary when it gives up. This is a
  convergence-radius/budget stall.
- At 0.90–1.00 the worst tray moves to 22 and the iterate is past the boundary (fraction
  1.26 → 3.39, internal liquid 106 → 44 kmol/h vs 134/149 requested): the authored spec is
  (near-)infeasible for a *dry* column at RR 2.0 / Q_R 8 MW. This matches CDU physics — in the
  real column that liquid is supplied by pumparound return and stripper reflux effects that dry
  V3 does not model.
- Every failure costs 21–30 s of compute before the typed diagnostic lands — the in-game
  "struggling" experience is mostly this: long burns ending in NONCONVERGENCE.

### Sweep B — single draw at tray 15, 150 kPa (per-tray wall, ramp-rung anatomy)

| draw/feed | outcome | wall time | ramp anatomy (from events) | final worst D/L |
|----------:|---------|----------:|----------------------------|----------------:|
| 15% | FAIL | 7.7 s | intermediates (λ·15% ≤ 11.25%) all passed; **λ=1.0 rung died at its 32-iteration budget, residual 1.9e-2** | 0.43 |
| 25% | FAIL | 22.9 s | **first rung (6.25% draw) stalled at 40 iterations with residual 1.0e-4** (audit fails: EQUILIBRIUM); skip-to-1.0 then failed at residual 0.82 | 0.56 |
| 35% | FAIL | 24.1 s | first rung stalled at 1.7e-4 (EQUILIBRIUM + CONDENSER_PHASE); 1.0 failed at 1.0 | 0.94 |
| 45% | FAIL | 22.9 s | first rung stalled at 1.6e-3; 1.0 failed at 0.73 | 1.22 |

Readings:

- **The no-draw → draw handoff (first rung) is the hardest step.** At 25% authored draw, the
  first rung asks for only a 6.25% draw yet stalls — while sweep A's 0.25× case converges 4.7%
  per-tray draws and sweep B's 15% case passes an 11.25% *later* rung (warm-started from an
  accepted draw state). The material-projection seed from the no-draw column has a much smaller
  effective radius than rung-to-rung continuation.
- **The stalls are slow convergence, not divergence**: residual 1.0e-4 after 40 iterations and
  still improving is a crawl toward the root that the budget truncates; the acceptance audit
  then correctly rejects (EQUILIBRIUM family) because the gate is tighter. The skip-to-λ=1.0
  jump from that unconverged iterate is what turns a near-miss into a catastrophic residual
  (0.7–1.0).
- Per-tray heuristic from A+B combined: worst-tray withdrawal fraction ≲ 0.3 converges today;
  ≳ 0.4 stalls. (CDU stripper draws with PA support will sit around 0.3–0.5 — at or past
  today's edge.)
- The budget inversion is visible in the data: the hardest rung (λ=1.0, largest step from a
  possibly-stalled seed) has the *smallest* budget (32 vs 40).

### Sweep C — three draws, 100 kPa (committed pressure lane, full draws on the legs)

| scale | outcome | wall time | where it ended |
|------:|---------|----------:|----------------|
| 0.25 | SUCCESS (audited, gates pass) | 11.9 s | full lane: anchor ramp at 150 kPa, then 11 × 5 kPa legs each carrying full draws; healthy leg diagnostics (predictor accepted, FD reuse) |
| 0.50 | FAIL | 30.2 s | **anchor-failed** — identical ramp stall as sweep A; the pressure lane never started |
| 0.75 | FAIL | 23.9 s | anchor-failed, same signature |

Readings: the ≤100 kPa lane adds cost (+60% vs 150 kPa at 0.25×) but **no new failure mode at
these scales** — the wall is the anchor-side attach ramp in every failing case. Fine 5 kPa legs
carry full qualified-rate draws without drama, which also derisks the plan's
bare-legs-then-ramp-at-target ordering (the leg physics is not the problem; the attach step is).

### Discrimination probe — is it just the budget? (No.)

Hypothesis test: raise the ramp budgets from 40 (intermediate) / 32 (requested) to the solver's
hard cap 128/128 (`MAXIMUM_NEWTON_ITERATIONS`; CSV family label says "budget160" — the first
attempt at 160 was rejected by the limit validation, the recorded run used 128/128) and rerun
the cleanest stalls:

| case | 40/32 budget outcome | 128/128 outcome |
|------|----------------------|------------------|
| single draw 25% of feed | rung 1 stalls at residual 1.0e-4 / 40 iters | **still running at 120 s wall-clock — probe killed it** |
| three draws, 0.50× | budget exhausted, residual 1.2e-3 | **LINEAR_SOLVE_FAILURE: "V3 banded LU encountered a zero or tiny pivot"** at iteration 89 (rung 0.5, residual 1.3e-3) and again at iteration 53 of the λ=1.0 attempt |
| three draws, 0.75× | budget exhausted, residual 2.5e-4 | **LINEAR_SOLVE_FAILURE (tiny pivot)** at iteration 97 (rung 0.25, residual 2.0e-4); λ=1.0 attempt pivot-failed at iteration 104 with tray 22 at withdrawal 1.23 |

**Conclusion: bigger budgets buy nothing but longer burns (66–120 s vs 23–30 s).** The crawl is
a damping plateau that terminates in a numerically singular damped-normal-equations factorization
— not slow convergence that a budget would finish. This reclassifies the regime-1 mechanism:

- The plateau residuals (1e-4…1e-3) and the eventual zero/tiny pivot are the signature of
  **columns of the Jacobian collapsing**: as a rung's draw starves trays, trace-component flows
  crash toward the log floor, their material-row derivatives (∝ the flow itself in log
  coordinates) vanish, and JᵀJ loses rank faster than the damping heuristic compensates. This is
  the same solvability-frontier family as the documented 55 kPa "shared 3.7e-4 energy plateau"
  from the trace-truncation workstream.
- The ramp *disables* the one mechanism built to fix exactly this: intermediate rungs run with
  `TruncationPolicy.OFF` (`recoverWithDrawRamp`), so collapsing components cannot be masked out
  of the unknown set, guaranteeing the singular columns stay in the system. Truncation is
  re-enabled only at λ=1.0 — after the intermediates have already stalled.
- A tiny pivot inside a rung is currently terminal for that rung; it is not treated as a
  "raise damping and retry" event.

### Post-measurement note (main @ 56771fc, same day)

The steam-findings fix commit restructured the ramp into staged per-feature phases (`RampStep`:
steam rungs sized ≤4 mol/s, then four draw rungs) — a step toward this document's staging
recommendation. The measured failure mechanisms are untouched: the draw phase still uses 4 fixed
fractions, intermediate failure still jumps to the full request (now skipping BOTH phases), and
intermediate rungs still run truncation OFF. The sweeps above (run at 7bccf7b, draws-only) remain
representative of the draw phase; Phase A0's scope is unchanged.

## 2. Why it fails there (mechanism, from the committed code)

- **Draws never ride the stage ladder.** For draw-bearing inputs every ladder rung (4-8-15-N)
  is built with `List.of()` draws (`V3ColumnCalculator.withStageGeometry`), and the draws enter
  solely through `recoverWithDrawRamp` at requested geometry: fixed fractions
  {0.25, 0.5, 0.75, 1.0} of the authored rates, warm-started from the accepted no-draw state.
- **The ramp is brittle by construction.** Intermediate rungs get 40 iterations
  (`DRAW_RAMP_INTERMEDIATE_MAXIMUM_ITERATIONS`), the full-rate rung 32; on the first
  intermediate failure the ramp **skips the remaining intermediates and jumps straight to
  λ=1.0** from the stalled iterate ("a failed intermediate fraction is still a finite
  fixed-geometry seed"). That is the opposite of adaptive refinement: precisely when the
  homotopy needed smaller steps it takes the largest possible one. The sweep's failure signature
  (budget exhausted at the requested rung, margin still present) is what this design produces
  when the per-rung state change exceeds the damped Newton's practical radius.
- **Step size in physical terms**: at scale 0.25 each rung moves total withdrawal by ~3% of
  feed and converges; at scale 0.40+ each rung moves ~5%+ and stalls. The practical radius of
  one rung at this operating point is roughly 3–5% of feed per step — reaching CDU-scale 50%
  needs ~10–16 adaptive steps, not 4 fixed ones.
- **No barrier at the feasibility boundary.** `V3SideDraws.withdrawal` computes fraction = D/L
  with no clamp; an iterate with L < D writes negative downstream liquid into the residual
  while log coordinates keep the unknowns positive. The system stays evaluable but
  inconsistent, the damped normal-equations step shrinks, and the solver crawls until the
  budget dies — which is exactly the 21–30 s burn observed. The diagnostic
  (`sideDrawDiagnostic`) then reports D/L honestly (good), but only after the burn.
- **Pressure lane compounds exposure** (committed behavior): below the 150 kPa trigger the
  ramp runs at the anchor, then every pressure leg re-solves with **full draws** under a
  12-iteration corrector (24 for the Wang–Henke recovery). The steam branch already moved to
  bare legs + ramp-at-target, which the CDU plan adopts — sweep C measures the committed
  arrangement.

## 3. Mapping to the CDU plan's load

The CDU plan attaches, through the same single λ-axis the draw ramp uses today:
up to 3 stripper draws (CDU-realistic total ≈ 40–50% of feed — the capstone's kero+diesel+AGO
is 39% by volume), up to 3 pumparound recycles (individually often 50–150% of feed rate,
though net-zero material), all steam, and the surrogate-duty swap. Risk register:

| # | Risk | Likelihood | Impact | Grounding |
|---|------|-----------|--------|-----------|
| R1 | Attach-ramp nonconvergence at CDU-realistic stripper-draw rates | **High** (today's baseline fails at 40% of those rates with plain draws, on the easy lane) | Blocks Phase B/D; capstone unreachable | Sweep A regimes 1+2 |
| R2 | Authored infeasible specs (draw > supplyable liquid) burning 30 s before an opaque failure | High (users will author them; regime 2 shows even literature rates are dry-infeasible) | UX + support burden | Sweep A 0.9–1.0 |
| R3 | Pumparound quench collapsing vapor above the return tray during ramp (vapor-side twin of starvation) | Medium | Ramp stalls attributed to wrong cause | Mechanism §2 (no vapor barrier either) |
| R4 | Wider normal-equations band + near-boundary iterates → conditioning-driven crawl even when feasible | Medium | Slow solves in-game | Regime 1 is already this signature |
| R5 | Pressure-lane compounding for ≤100 kPa CDU operation | Medium (plan already adopts bare-legs/ramp-at-target) | Longer solves, more failure surface | Sweep C |

Two genuine mitigating factors, also grounded in the data:

- **Pumparounds fight regime 2.** The starving tray (22, above the feed) is exactly where a
  diesel/AGO pumparound returns cooled liquid; at full PA rates the wet column holds far more
  internal liquid at the draw trays than the dry analog the sweep measured. The capstone's
  full-rate draws are likely *feasible* in the complete CDU configuration — the danger is the
  **path**, not the destination.
- **Stripper draws are partially self-relieving**: a stripper returns ~5–10% of its draw as
  vapor, and its steam raises vapor (hence condensation → internal liquid) above the return.
  Small, but the sign is favorable.

## 4. Conclusion for the plan

As written, the plan's §9 ("one joint attach-ramp, λ ∈ {0.25, 0.5, 0.75, 1.0}") inherits and
multiplies today's weakest mechanism. The measured baseline makes this the plan's dominant
risk — above anything in the linear-algebra or audit layers. The continuation design must be
upgraded from a probe ("P-C1: try λ=0.1 if 0.25 fails") to hard requirements.

## 5. Required plan changes (folded into V3_FULL_CDU_PLAN.md)

1. **Adaptive λ refinement replaces fixed rungs** (Phase A deliverable, used by plain draws
   too): on rung failure, bisect the interval from the last accepted λ (floor Δλ ≈ 1/32, global
   rung cap ~24); never jump to λ=1.0 from a stalled iterate. Expected cost at CDU scale:
   10–16 accepted rungs × 1–3 s — well under one of today's failure burns.
2. **Staged attachment order — pumparounds first**: ramp PAs to full (they *add* liquid and
   remove no material), then strippers + side draws + steam jointly. Grounded by regime 2:
   draws should ramp into a column that already has PA liquid support at the draw trays.
3. **Rung predictor**: secant extrapolation of the state in λ (safe in log coordinates)
   before each rung's Newton; roughly doubles the per-rung radius for one cheap vector op.
4. **Truncation stays enabled on intermediate rungs** (today it is forced OFF below λ=1) with
   the same 8τ defect-budget accounting — masking collapsed trace components out of the unknown
   set is the designed cure for the rank loss the discrimination probe exposed. Pair with
   **tiny-pivot → damping-escalation retry** inside the Newton loop (a pivot failure becomes
   "raise λ_LM and refactor", terminal only after escalation exhausts). Do **not** lead with
   bigger iteration budgets: the 128/128 probe shows they only lengthen the burn (keep modest
   raises as a backstop once steps are adaptive and the singularity fixes are in).
5. **Fast-fail feasibility screen + boundary-aware diagnostics**: before ramping, compare each
   authored draw against the bare-spine internal liquid at its tray (screen catches only
   guaranteed-infeasible specs; regime 1 shows margin does not imply convergence — do not
   oversell it); during the ramp, if any tray's withdrawal fraction exceeds ~0.95 on an
   accepted rung, name that tray and stop with a starvation diagnostic instead of crawling
   the full budget. Vapor-side twin for PA quench (fraction of tray vapor condensed by the
   return).
6. **Diagnosis probe P-R1 — executed as part of this evaluation** (the budget-discrimination
   run above): outcome = plateau + singular linearization, not radius-only. Residual follow-up
   worth one session during A0: per-rung `V3NewtonTrace` at 0.50× with truncation enabled on
   intermediates, to confirm the collapsed-column diagnosis and measure how much of the wall
   items 1–4 recover before boundary effects (regime 2) take over.

## 6. Follow-ups outside the plan

- The existing side-draw feature would benefit from fixes 1/4/5 *independently of the CDU* —
  today's users hit this wall with plain draws (this report's trigger).
- The 0.25× qualification scale in `V3Sotelo2019SideDrawCase` should gain a comment citing
  this measurement so nobody "cleans up" the scale factor into the failing region.
- Revisit `DRAW_RAMP_REQUESTED_MAXIMUM_ITERATIONS = 32` < intermediate 40: the hardest rung
  currently has the smallest budget.

---

## Appendix — sweeps B and C (raw rows)

Sweep B (single draw, tray 15, 150 kPa) — isolates per-tray fraction from multi-draw
compounding. Sweep C (three draws, 100 kPa) — committed pressure lane with full draws on the
legs. Rows appended verbatim from the probe CSV:

(pending — appended below when the runs complete)

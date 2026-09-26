# V3 Full-Convergence Plan — Breaking the Draw-Attach Wall

Date: 2026-09-02.
Trigger: user requirement — **a fully converging column before Phase A** (pumparounds/strippers).
Baseline: `codex/v3-cdu-ramp-hardening` @ `9d18bb9` (A0 complete, 26/36 DOE; the 10 remaining
failures are all the collapsed-column / draw-attach wall families).
Probe branch: `claude/wall-probes` (9d18bb9 + `V3WallProbeTest` + three probe-only property
gates). Evidence: `scratchpad wall-probe.csv` / `wall-probe-events.log`; probe families W1–W9.
Companions: `V3_COLD_DOE_FAILURE_ANALYSIS.md` (mechanism), `V3_COLD_DOE_A0_RERUN.md` (baseline).

## 0. What "fully converging" should mean (proposed bar)

Every 36-cell DOE input terminates as either an **audited success** or a **fast, honest
`INFEASIBLE_SPECIFICATION`** carrying the measured margin (requested draw vs supplyable liquid) —
no generic `NONCONVERGENCE` burns. The probes below show a subset of the remaining 10 cells is
starvation-adjacent by construction (drum-condensation-capped liquid at hot condensers); those
must be *typed*, not forced. Everything with real margin must converge.

## 1. Measured wall characterization (families W1/W4/W5/W6/W7)

**The wall is HARD with respect to effort.** W1 (rung cap 96, per-rung budget 96+96 iterations,
λ-floor 1/128 — 4× everything): the 155 kPa/50 °C/40% wall moved from λ≈0.727 to λ≈0.734
(+0.7%), with 192-iteration fresh-full-FD rungs plateauing at residual 3.6e-3–2.2e-2 on
1/128-λ steps (≈3 kmol/h of draw increment). Same at the 100 kPa anchor (W1c). Budgets, pacing,
and step size are not the binding constraint — the Newton iteration's cost explodes near the
wall.

**Position: a liquid-margin curve, slid by reflux and nothing else.**

| Probe | RR | Attached loading at stall (155 kPa/50 °C, 40% authored) |
|---|---:|---:|
| production | 2.0 | 28.8% (λ=0.72) |
| W6a | 2.5 | 32.4% |
| W6b | 3.0 | 36.5% |
| W7b | 3.25 | 37.5% |
| W7a | 3.5 | 38.75% |
| W6c | 4.0 | **converges (40%)** — audited, 23 s |

- **Pressure-invariant** (W4): identical stalls at 155/200/250 kPa; 50% loading stalls at
  λ=0.625 even at 250 kPa. Raising/relocating the anchor is not a fix.
- **Duty-invariant** (W6d): Q_R 12 MW leaves the wall exactly at λ=0.72 — the draws sit above
  the feed tray, where internal liquid is reflux-driven; reboiler duty adds liquid only below.
- **The RR dose–response flattens** — consistent with the total-reflux ceiling: at fixed duty
  the rectifying liquid is bounded by `L ≈ V·RR/(1+RR)`, so the attachable loading asymptotes
  (≈43–48% for this fixture at 8 MW). CDU-scale loadings beyond that need the liquid the real
  configuration itself brings (pumparound returns, stripper vapor) — consistent with the risk
  doc's "the danger is the path, not the destination".
- **Hot corner is condensation-capped** (W7c): at 100 kPa/100 °C even RR 4.0 stalls at 31%
  attached — the drum at 373 K condenses only so much liquid regardless of the reflux-ratio
  spec, so the 40%-loading hot cells (DOE 31/35) are starvation-adjacent by construction.
  These are prime candidates for honest fast infeasibility typing rather than forced
  convergence.
- **High-withdrawal *points* are solvable** (W1b): with brute ramp budgets the
  60 kPa/100 °C/22.5% anchor attach **converged and audited at tray-22 withdrawal ≈0.87** —
  the wall is a stepping-cost explosion *near* high-withdrawal states, not point-insolvability.
  (The subsequent 145 kPa leg then stalled at withdrawal 0.886 under production leg budgets.)

**Where the wall bites, mechanically** (from `V3_COLD_DOE_FAILURE_ANALYSIS.md`, unchanged): the
stuck residual is always `COMPONENT_MATERIAL_BALANCE` at the node below a draw tray, component
PC6 (trace-liquid/major-vapor there). As withdrawal grows, the `(1−f)` liquid-passthrough
weakens, trays below heavy draws run near-dry, and the compound linearization develops the
tiny-pivot/damped-crawl signature.

## 2. Pressure-leg near-misses are CERTIFICATE failures, not budget deaths (W5 + W2)

The 60 kPa hot/moderate cells die in the pressure legs with terminal residuals **2.5e-8,
2.6e-9 (below the 1e-8 tolerance!), 3.1e-6** — dominant physical residuals down to 1e-7 mol/s.
W2 (leg budgets doubled to 48/48 in an isolated JVM — the first attempt was invalidated by JVM
class-init ordering and rerun): **doubling the budget flips nothing.** The same legs die at
"48 Newton iterations" with residuals 2.0e-8 / **1.9e-9 (still below tolerance, still failing)**
/ 2.2e-6 / 1.5e-2 — marginal improvement, identical stall positions.

Mechanism (from `V3SimultaneousColumnSolver.verifiedCandidate` + the trace counters): at these
near-wall states the solver reaches the solution but cannot produce the **verified final Newton
certificate**. The certificate requires a full-step correction that (a) keeps the residual under
tolerance, (b) does not increase the merit, and (c) passes the step-size/backward-error
convergence gates. With a near-null subspace in J (the collapsed trace-liquid columns), the
*undamped* correction picks up an enormous component along the null direction (fails the
step-size gates or overshoots the residual), while a *damped* correction fails the
backward-error gate — the certificate is structurally unproducible no matter the budget. Two
measured textures of the same thing: W2b's leg factors cleanly (no pivot failures) yet spins at
1.9e-9 forever; W2c logs **47 consecutive tiny-pivot damped recoveries, all accepted at minimum
damping**, crawling at 2.2e-6. The wall and the near-misses are one phenomenon at two
intensities.

## 3. Refuted fix candidates (do not spend on these)

| Candidate | Refuted by |
|---|---|
| Bigger ramp budgets / finer λ-floor / more rungs | W1: 4× everything → +0.7% λ |
| Higher/relocated pressure anchor | W4: wall pressure-invariant |
| Reboiler-duty surplus | W6d: wall unchanged at 12 MW |
| Truncation-on-intermediates | Family H (failure-analysis doc): byte-identical — PC6 is trace-liquid/major-vapor, which stage-trace truncation retains by design |
| Condenser water-split kink smoothing | E2/E3 (failure-analysis doc): steam-rate-independent |

## 4. The decisive experiments: W9 descent + W10 autopsy

**W9A (manual RR-descent at full 40% draws, 155 kPa/50 °C — in-package harness driving
`V3SimultaneousColumnSolver` directly)**: bare ladder at RR 4.0 → λ draw-attach converges
cleanly (λ=1.0 at 56 iterations, worst D/L 0.448 @ tray 22) → adaptive descent:

| RR step | outcome | worst withdrawal (tray 22) |
|---|---|---|
| 3.875 | S, 6 iters | 0.574 |
| 3.78125 | S, 8 iters | **0.802** |
| 3.75 / 3.6875 / 3.640625 | F @ 128 iters (residuals 0.015–0.054) | — |

**`RR_STALLED at 3.764`** — matching the ascent curve's λ=1 crossing (3.6–3.8). The wall is a
**point property**: no continuation direction in (RR, λ, P, T) space crosses it. Combined with
W1 (budget-hard) this eliminates every purely path-based fix for the cells beyond it.
(W9B: the 100 kPa/100 °C/40% corner cannot even attach at RR 5.0 — λ-attach stalls at 0.125–0.94
depending on the bare basin — reinforcing that the hot corner is condensation-capped.)

**W10 (null-direction autopsy at a leg-stall specimen, 100 kPa leg of 60 kPa/90 °C/22.5%,
residual 5.9e-5)** — the damped-Newton direction's dominant unknowns, by damping:

- At damping 1e-8 (≈raw Newton): **liquid+vapor PAIRS of trace components** — PC12/PC11 at
  nodes 19–23, PC10 at 11, PC9 at 7–8 — moving *together* (|δ| 0.007–0.02 while everything else
  is ≤1e-5). A component that is trace in **both** phases at a node has a VLE row that lets the
  pair slide jointly at almost no residual cost: a measured near-null subspace, one dimension
  per trace pair.
- The collapsed-flow neighborhood: **node 23 (the tray below the deepest draw) has
  L_total = 3.2e-215** — bone dry at the iterate, all 15 liquid unknowns at log-flow ≈ −500,
  a dead block in the middle of the matrix. Tray 22's withdrawal at that iterate is **1.014**
  (draw 18.73 mol/s vs liquid 18.48): locally starved, starving the tray below.

## 5. Root cause — final statement

**The draw-attach wall is the dry-tray degeneracy of the two-phase-everywhere formulation.**
When the deepest draw's withdrawal fraction approaches ≈0.8–0.9, the tray below it loses its
liquid (nothing flows down; nothing condenses there between draw and feed), its liquid unknowns
collapse toward log-zero, and — together with the trace-pair joint-rescaling subspace — the
linearization carries a near-null block that (a) explodes the cost of every Newton step nearby,
(b) makes the verified final-Newton certificate structurally unproducible (undamped correction
huge along the null directions; damped correction fails the backward-error gate — §2), and
(c) trips the banded LU into tiny-pivot damping crawls. Every observed failure family —
the λ-wall, the RR-descent stall, the leg "near-misses", the anchor deaths — is this one
degeneracy at different intensities. High-withdrawal points *just inside* the boundary are
solvable and auditable (W1b: withdrawal 0.87 certified; W9A: 0.80 in 8 iterations); the
formulation simply has no representable continuation into the dry-tray regime, and several DOE
corners (hot condenser + 40%) sit at or beyond the *physical* liquid ceiling
(`L_max ≈ V·RR/(1+RR)` at fixed duty; drum-condensation-capped when hot).

## 6. Fix plan (Phase W — "the wall", before Phase A)

The bar from §0: every input terminates as an audited success or a fast, margin-carrying
`INFEASIBLE_SPECIFICATION`. Work packages in order:

- **W-1 — Dry-tray branch (the core fix; formulation-level).** Give trays the same phase-branch
  treatment the condenser already has: a tray whose liquid collapses below a floor
  (e.g. `L_j < 1e-6·F`) re-resolves as a **vapor-only pass-through node** — liquid unknowns and
  VLE rows removed, material rows keep the vapor path and the exact zero liquid downflow, the
  energy row keeps vapor terms, the known-profile steam water passes through unchanged.
  Detection and re-resolve mirror `correctCondenserPhase` / `V3CondenserPhaseTransition`
  (bounded branch attempts inside ramps and legs); the ledger/truncation machinery already
  supports per-node-per-component structural absence, and `V3StageBlockLayout` already handles
  variable block sizes. A draw authored **on** a tray that dries out is typed
  `INFEASIBLE_SPECIFICATION` (you cannot draw liquid from a dry tray); a draw **above** a dry
  tray becomes representable — which is exactly the CDU-relevant regime (a stripper's return
  section and a total-draw tray both run dry in real columns). This is a formulation extension:
  **formulation/assumptions revision bump required**, audits extended with a DRY_TRAY family
  (tray liquid below floor ⇒ zero liquid outflow, independently re-derived), FD-vs-local and
  degenerate oracles per the usual ladder (a dry-tray column must equal the same column with the
  tray removed from the liquid graph).
- **W-2 — Trace-pair anchoring (solver-level, complements W-1).** The joint-rescaling null
  directions (trace-in-both-phases pairs) are independent of dry trays and survive cutoff 1e-6
  (measured: family H byte-identical; at node 22, x_PC12 ≈ 1.4e-4 stays above any sane trace
  cutoff). In the damped-normal layer, detect pair-degenerate columns (both phase flows of a
  component at a node below a relative floor) and add per-column Tikhonov anchoring (damping
  only those columns) before factorization — a pure linear-layer conditioner: no formulation
  change, no revision bump, exact solutions unaffected (anchored columns get zero correction,
  which is where they already are). Expected to restore certificate producibility at thin
  states (§2's W2b specimen) even before W-1 lands.
- **W-3 — Certificate acceptance at anchored states.** With W-2, the FINAL_CERTIFICATE path
  should verify (the anchored solve's correction is small and its backward error is measured on
  the anchored system). Re-run the W2/W5 leg specimens as the acceptance test: the 60 kPa
  85/90 °C cells must flip to audited success.
- **W-4 — Fast honest starvation typing.** Before any ramp: bare-spine liquid ceiling per draw
  tray (already computed for the pre-gate) plus the total-reflux bound `V·RR/(1+RR)` ⇒ requests
  beyond ~0.95 of the ceiling fail immediately as `INFEASIBLE_SPECIFICATION` with the margin
  numbers (the improved W5-style diagnostics already exist). During ramps, a sustained
  `withdrawal > 0.95` at an accepted rung already stops (starvation gate) — route it to the same
  typed result. The hot 40% corners (DOE 31/35, and likely 33) are expected to land here
  legitimately.
- **W-5 — Re-screen.** The 36-cell DOE plus the W-series wall cells
  (155/50/40, 100/50/40, 60/100/22.5, 60/75/40, RR-descent to 2.0 at 40%): target = zero
  `NONCONVERGENCE` outcomes; every cell either audited-S or typed-infeasible with margins; the
  known-feasible wall cells (50–75 °C, ≤40% within the ceiling) must converge. Keep the A0
  lane policy as-is (it is measured-good); do not resurrect the refuted avenues in §3.

Sizing guidance: W-2+W-3 are contained (linear layer + certificate, no wire/GUI impact) and
independently valuable — do them first; W-1 is the substantial piece (topology/ledger/audit
surface, revision bump) and is also **CDU-critical infrastructure** (stripper sections and
total-draw trays run dry in every real CDU configuration, so Phase A/B inherit it directly);
W-4 is small and immediately user-visible.

## 7. Probe reproduction

Branch `claude/wall-probes` (based on 9d18bb9): `V3WallProbeTest` families W1/W2/W4–W10 (W3
unused), three probe-only property gates (`rungCap`, `rungIterations`, `rampFloor`,
`legIterations`, `legRecoveryIterations` — class-init constants, so budget families need their
own JVM/invocation), and the W9/W10 in-package harness (production-faithful bare ladder =
`SEQUENTIAL_MATERIAL_VLE` initializer + proportional geometry expansion + bubble-point
projection; adaptive spec-descent with `V3CondenserPhaseTransition` branch handoff; autopsy =
FD Jacobian → `V3NormalEquations` → damped `V3BandedPivotedSolver` solves → top-|δ| unknown
dump). Raw rows: scratchpad `wall-probe.csv` / `wall-probe-events.log`.

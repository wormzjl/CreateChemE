# V3 Full CDU Plan — Side Strippers + Pumparounds

Status: PLAN (2026-09-01). Base: **`main` @ 56771fc** — steam stripping landed (1057efa) with
all 11 review findings fixed (335fa0b, verified 2026-09-01, suite green; see the fix-verification
addendum in `V3_STEAM_STRIPPING_REVIEW.md`). The steam-findings prerequisite in §16 is
**satisfied**; Phase A0 (ramp hardening) remains the open gate. Builds directly on: side draws
(dd43c98 + 321fd8a), steam stripping (W1 known-profile water), Holland benchmark harness.

Goal: turn the single-shell V3 column into a full crude distillation unit (CDU):

- **Side strippers** (up to 3 — kerosene / diesel / AGO pattern): liquid drawn from a main-column
  tray feeds the top of a small steam-stripped side column (2–4 stages); stripper overhead vapor
  returns to the main column; stripper bottoms is a new product stream.
- **Pumparounds** (up to 3): liquid drawn from a tray, cooled externally, returned to a tray above
  at a **specified return temperature** (user decision 2026-09-01; duty becomes a published output).

With `Q_R = 0` + sump steam (steam branch) + strippers + pumparounds, the topology matches a real
atmospheric CDU: no reboiler, bottom steam, flash-zone feed, product side-columns, PA heat removal.

Out of scope: per-tray pressure drop (column stays uniform-P), multiple main feeds, reboiled side
strippers (steam-stripped only), three-phase trays, PA vapor draws, in-world fluid port/multiblock
changes (calculator + display + wire only).

---

## 1. The headline architecture result (read this first)

The production linear step is **not** a structural block-tridiagonal factorization. It is a damped
normal-equations solve: `V3NormalEquations.prepare` forms JᵀJ, measures the actual scalar
bandwidth, and `dampedMatrix` builds a runtime-bandwidth `V3BandedMatrix` that the existing banded
LU factors (`V3NormalEquations.java:49,65`). **The solver already supports arbitrary local
bandwidth.** What forbids a CDU today is not the factorization — it is three *guards* that assert
"no coupling beyond node distance 1 (Jacobian) / 2 (normal product)":

1. `V3BlockJacobianAssembler.addGlobal` — throws "off-band coupling" (`V3BlockJacobianAssembler.java:367`).
2. `V3BlockJacobianAssembler.addEnergyDerivative` — throws if `|energyNode − columnNode| > 1` (`:345`).
3. `V3BlockJacobianAssembler.assemble` (FD verification oracle) — throws if any off-band block
   exceeds 1e-10 (`:36`), and `V3NormalEquations.bandwidthAndValidate` — throws if the normal
   product couples nodes at distance > 2 (`V3NormalEquations.java:151`).

**Therefore the CDU needs no new linear algebra.** It needs:

- a **network topology** that declares which node pairs may couple (graph edges), with a node
  *ordering* that keeps every declared coupling index-local so the measured bandwidth stays small;
- the three guards generalized from "distance ≤ 1/2" to "declared by the topology" (undeclared
  couplings still throw — the invariant survives, pattern-aware);
- new residual terms (PA return, stripper feed/return, steam-on-tree) and their Jacobian routing
  through the *existing* probe machinery;
- continuation, seeding, audits, provenance, streams, GUI.

Everything else in this plan is the disciplined execution of that list.

---

## 2. Facts about the current code this plan relies on

- Node model: condenser 0 (T spec'd, no energy equation, no T unknown —
  `V3ColumnTopology.hasTemperatureUnknown`), trays 1..N, reboiler N+1. `nodeCount = N+2`.
- Unknowns/equations per node: log liquid component flows (where `hasLiquid`), log vapor component
  flows (where `hasVapor`), T (except condenser); C material + C VLE (where two-phase) + 1 energy
  (except condenser). `V3StageBlockLayout` validates per-node contiguity and sizes from topology
  predicates + `truncationSupport.retains(node, component)`.
- `assembleLocal` (production Jacobian): material rows **analytic** with a hard-coded
  node−1/node/node+1 stencil that already includes side-draw withdrawal-fraction terms
  (`V3BlockJacobianAssembler.java:128-217`); VLE + energy derivatives come from FD probes of
  `localTerms(state, node)` — perturb a node's coordinate, recompute that node's `LocalNodeTerms`,
  route `liquidPhaseEnergy` to rows node/node+1 and `vaporPhaseEnergy` to rows node/node−1.
- The full-matrix FD assembler (`assemble`) is the independent verification oracle for the local
  assembler and must remain so for the network.
- Side draws: `problem.nodeSideDrawMolPerSecond(tray)` + `problem.liquidWithdrawalFraction(state,
  tray)` — the *fraction of tray liquid withdrawn* enters both material and energy stencils.
- Steam (W1): water is inert and immiscible; the water vapor profile is **input-determined**
  (`V3SteamFeeds.upwardVaporProfile`), enters only as the VLE dilution term
  `ln(V_hc/(V_hc+w_j))` and vapor-phase water enthalpy + steam source terms. Condenser regimes
  NONE / FREE_WATER (slip `w₀ = s·V_hc,0`) / ALL_VAPOR.
- Continuation (as of main @ 56771fc): stage ladder [4, 8, 15, requested] dry; pressure legs dry;
  authored features restored by a **staged per-feature ramp** (`RampStep` record in
  `V3ColumnCalculator`): steam phase first (rung count sized by physical increment, ≤4 mol/s per
  rung, 4–12 rungs) then a 4-rung draw phase at full steam; surrogate duty
  `Q_R + Σf·ΔHvap_w(450 K)` ramps out with the steam fraction; the final rung is bitwise the
  authored input. Known weaknesses the CDU work inherits (reported in the fix-verification
  review): intermediate failure skips the rest of BOTH phases and jumps to the full request, and
  intermediate rungs run truncation OFF — the measured stall mechanisms.
- Publication is gated solely by a fresh `V3AcceptanceAudit`; failures are typed, partial results
  are never published.

---

## 3. Network topology and node ordering

New `V3NetworkTopology` (wrapping, not replacing, `V3ColumnTopology` for the spine):

- **Node kinds**: SPINE_CONDENSER, SPINE_TRAY, SPINE_REBOILER, STRIPPER_TRAY.
- **Global ordering (the bandwidth decision)**: stripper chains are **interleaved**, not appended.
  For a stripper drawing at spine tray d with stages s1..sS, the global order is
  `..., d−1, d, s1, s2, ..., sS, d+1, ...`. Index distances: spine edge (d, d+1) stretches to
  S+1; feed edge (d, s1) = 1; return edge (r, s1) = 2 + (d − 1 − r) for return tray r. With
  S ≤ 4 and return span ≤ 3 every declared coupling has index distance ≤ 6, so the normal
  matrix's measured bandwidth grows only locally and the banded LU cost stays trivial at game
  scale. (Appending chains at the end would put couplings at index distance ~N and collapse the
  banded solve to dense — do not do it.)
- **Zero-attachment invariant**: with no strippers and no pumparounds the ordering is 0..N+1,
  `V3NetworkTopology` delegates every predicate to the spine topology, and every code path,
  digest, and published byte is identical to today. This is the same kill-switch discipline the
  steam branch pinned with the dry-digest byte test — add the analogous pin here.
- **Graph API** consumed by evaluator/assembler/layout (this is what makes the whole design
  uniform): `liquidDownstreamOf(node)` / `vaporUpstreamOf(node)` (chain edges — for spine nodes
  these are the physical neighbors *even where the index stretched across an inserted chain*),
  `couplings()` — the set of declared extra (rowNode, colNode) pairs: PA return rows ← draw-node
  columns, stripper-feed rows ← spine-draw columns, spine-return rows ← stripper-top columns —
  plus `spineOf(globalNode)` / `globalOf(spineNode)` maps used by feeds, draws, steam, traces,
  display.
- Stripper chain internals: s1 (top) receives the feed liquid from spine tray d and sends its
  vapor to spine tray r; si liquid flows to si+1; sS (bottom) receives that chain's steam
  (known-profile) and its liquid **leaves the network as the stripper product**. Stripper nodes
  are ordinary two-phase nodes: liquid + vapor unknowns, T unknown, C material + C VLE + 1 energy.
  No condenser, no reboiler, no duty term.

`V3ColumnTopology`'s predicates (`hasLiquidPhase`, `hasVaporPhase`, `hasTemperatureUnknown`,
`hasEnergyEquation`) move behind the network topology; stripper nodes answer true/true/true/true.
`V3StageBlockLayout` already validates against these predicates generically — it needs only the
network node count and the retention map extended (§10).

---

## 4. Pumparound formulation

Spec: `V3PumparoundSpec(drawStage p_d, returnStage p_r, molarFlowMolPerSecond R, returnTemperatureKelvin T_ret)`
with `1 ≤ p_r < p_d ≤ N`. **Return-temperature specification** (user decision): the return stream
enthalpy is evaluated directly at the spec'd T_ret — no enthalpy inversion anywhere in the
residual. Duty is derived, published output:

```
Q_pa = R · [ h_L(T_pd, x_pd) − h_L(T_ret, x_pd) ]      (W, ≥ 0 when cooling)
```

Residual terms (all per-component flows; x_pd,i = ℓ_pd,i / L_pd):

- **Draw (tray p_d)**: the PA rate joins the existing liquid-withdrawal machinery — the total
  withdrawal at a tray becomes `sideDraw + Σ PA rates + Σ stripper draw rates` feeding the same
  `liquidWithdrawalFraction`. Material and energy stencils at p_d and p_d+1 are already written
  in terms of that fraction (`V3BlockJacobianAssembler.java:145-155,183-188,313-322`) — they pick
  the PA draw up with **no new stencil code** at the draw side.
- **Return (tray p_r)** — the genuinely new, off-band terms:
  - Material row (p_r, i): `+ R · ℓ_pd,i / L_pd` — analytic, same algebraic shape as the existing
    side-draw fraction terms; mirror them into `assembleExactMaterialRows` targeted at the
    declared coupling (p_r ← p_d).
  - Energy row (p_r): `+ R · h_L(T_ret, x_pd)`. Implemented as a **new local term evaluated at the
    draw node**: `LocalNodeTerms` gains `pumparoundReturnEnergy` — the liquid-mixture enthalpy of
    node p_d's composition at the *constant* T_ret, times R. Because it is a function of node
    p_d's unknowns only, the existing probe machinery differentiates it for free when it perturbs
    node p_d; the assembler routes it to row p_r via the declared coupling (a fourth routing
    target alongside the existing node−1/node/node+1 energy pushes).

No new unknowns, no new equations, no water interaction (trays hold no liquid water — the
WATER_DEW_POINT audit family guarantees the PA liquid is dry hydrocarbon).

Failure/physics guards live in audits (§11), not the residual: PA_COOLING (T_ret ≤ T_pd),
PA_RETURN_LIQUID (returned stream is subcooled/saturated at column P: Σ K_i(T_ret, P)·x_pd,i ≤ 1+tol).

## 5. Side-stripper formulation

Spec: `V3SideStripperSpec(drawStage d, drawRateMolPerSecond D, stageCount S ∈ [2,4],
returnStage r (optional, default d−1, validated 1 ≤ r < d, d − r ≤ 3),
steamRateMolPerSecond f, steamTemperatureKelvin T_steam)`.

- **Draw**: rate-specified liquid draw at spine tray d — joins the same withdrawal-fraction
  machinery as §4. The stripper *feed* component flows are `D · ℓ_d,i / L_d` (functions of spine
  unknowns).
- **Chain**: S ordinary two-phase nodes (§3). Adds `S · (2C+1)` unknowns and exactly matching
  equations (C material + C VLE + 1 energy per stripper tray, truncation-adjusted) — the DOF
  ledger stays square by construction; assert it in tests the way `V3SteamFeedContractTest`
  asserts DOF equality today.
- **Top node s1 material**: `feed_i − ℓ_s1,i − v_s1,i + v_s2,i = 0` where `feed_i` depends on
  spine node d (declared coupling s1 ← d). Energy row likewise carries the feed enthalpy
  `D/L_d · liquidPhaseEnergy(d)` — routable from node d's existing probed `liquidPhaseEnergy`
  with coefficient `D/L_d` plus the same split-derivative correction shape the side-draw energy
  terms already use.
- **Return into spine tray r**: material `+ v_s1,i`, energy `+ vaporPhaseEnergy(s1)` (declared
  coupling r ← s1; vapor energy is already a probed local term — new routing target only).
- **Bottom node sS**: steam source enters exactly as spine steam does today (known-profile W1
  terms, §6); its liquid `ℓ_sS,i` appears in no downstream equation — it is the **product**,
  published as a new stream.
- **Product rate is an outcome** (≈ D minus stripped lights), not a spec — specifying the product
  rate instead would add an unknown and a spec equation; note as a possible later extension.

## 6. Water and steam on the network

The W1 invariant survives the tree because vapor paths are still cycle-free and water is inert:
every node's water vapor flow is the sum of steam injected at-or-below it *along its vapor path*.

- Stripper chain k with steam f_k: `w(s_i) = f_k` for every stage of that chain.
- Spine: `w_j = Σ spine steam with injection ≥ j` (existing) `+ Σ_{strippers with r_k ≥ j} f_k`.
- Condenser: total water arriving = spine steam + all stripper steam; regimes and slip math
  unchanged (`V3WaterCondenserRegime` untouched).

Implementation: generalize `V3SteamFeeds.upwardVaporProfile` into a network traversal
(`V3NetworkTopology`-driven), keep the result as the same per-node known array on
`V3ColumnProblem`. Dilution terms `ln(V_hc/(V_hc+w))` and vapor-phase water enthalpy apply on
stripper nodes exactly as on spine nodes — same code, more nodes. Pumparounds carry no water.

Validation reuses the steam branch's rules per injection point: f > 0, T_steam ≥ Tsat(P)+5 K,
Σ all steam ≤ feed. WATER_DEW_POINT audits extend over stripper nodes (this is what proves the
stripper *product* is water-free — free water exists only at the condenser drum).

## 7. Guard generalization and Jacobian routing (the precise code contract)

1. `V3NetworkTopology.allowsCoupling(rowNode, colNode)` = chain-adjacent in the *graph* ∪ declared
   couplings ∪ same node. `addGlobal` keeps throwing for anything else
   (`V3BlockJacobianAssembler.java:367` becomes pattern-aware, not permissive).
2. `addEnergyDerivative`'s distance check (`:345`) likewise consults the topology.
3. Block storage: the lower/diagonal/upper triple becomes a block store keyed by declared edge
   (graph edges + couplings). The FD verification `assemble` extracts exactly the declared blocks
   and still throws when any *undeclared* position exceeds 1e-10 — the oracle keeps its teeth.
4. `V3NormalEquations`: the guard at distance > 2 (`:151`) becomes "outside the squared declared
   pattern"; `exactlyStageBanded`/`stageBandedProduct` generalize to iterate declared coupled
   column ranges (fall back to `denseProduct` correctness path if simpler for bring-up — but
   measure, n ≈ 900 makes dense products noticeable). Measured bandwidth + `V3BandedMatrix`
   already handle the rest.
5. Material stencil (`assembleExactMaterialRows`) rewritten against
   `liquidDownstreamOf`/`vaporUpstreamOf` instead of node±1 literals, plus the two new mirrored
   term groups (PA return §4, stripper feed/return §5). For a bare column the graph edges *are*
   node±1, so the rewrite is behavior-identical there — cover with the FD-vs-local test.
6. Probe routing: unchanged probe loop; `assembleLocalThermodynamicColumn` gains routing targets
   from `couplings()` — PA return energy from the draw node's new `pumparoundReturnEnergy` term,
   stripper feed energy from the draw node's `liquidPhaseEnergy`, stripper return energy from
   s1's `vaporPhaseEnergy`.

## 8. Input schema, validation, caps

`V3ColumnInput` gains `List<V3PumparoundSpec> pumparounds` and `List<V3SideStripperSpec>
sideStrippers` (canonicalized: sorted by draw stage, one attachment of each kind per stage;
legacy constructors preserved, MAX_PUMPAROUNDS = 3, MAX_SIDE_STRIPPERS = 3).

Static validation (resolver):
- PA: `1 ≤ p_r < p_d ≤ N`, span cap `p_d − p_r ≤ 6` (bandwidth sanity), R > 0 finite, T_ret
  inside the fluid package's property envelope. Distinct PA draw stages.
- Stripper: `2 ≤ d ≤ N` (so a return tray exists), distinct stripper draw stages, S ∈ [2,4],
  `1 ≤ r < d`, `d − r ≤ 3`, D > 0, steam per §6. A stripper draw stage may coexist with a plain
  side draw and/or a PA draw on the same tray (the withdrawal fraction sums); keep that legal
  but covered by an explicit test.
- Rates: `Σ(side draws) + Σ(stripper draws) < feed` (extends the existing rule; PA rates are
  recycles and excluded from this cap).
- Total network node cap: spine MAX_STAGE_COUNT + 12 (3 strippers × 4).

Dynamic feasibility (over-withdrawal of an internal liquid, PA heating instead of cooling,
flashing PA return) is the solver's and the audits' job, surfaced as typed failures — the log
coordinates make negative flows unrepresentable, so an infeasible spec fails to converge rather
than producing garbage.

## 9. Continuation, seeding, ramp

- **Bare-spine first**: the stage ladder and all pressure legs run with *no attachments and no
  steam* exactly as today (plus the steam surrogate duty, which now also covers stripper steam:
  `Q_surrogate = Q_R + Σ_all steam · ΔHvap_w(450 K)`).
- **Staged, adaptive attach-ramp after the last pressure leg** (REQUIRED — upgraded from the
  original fixed-rung design by the measured baseline in
  `documentation/V3_CDU_CONVERGENCE_RISK.md`: today's fixed {0.25, 0.5, 0.75, 1.0} draw ramp
  already fails from ~20% of feed withdrawn, far below CDU rates):
  - **Stage 1 — pumparounds to full** (they add internal liquid and remove no material),
    **Stage 2 — strippers + side draws + steam jointly**, so draws ramp into a column that
    already has PA liquid support at the draw trays.
  - **Adaptive λ per stage**: on rung failure, bisect from the last accepted λ (floor
    Δλ = 1/32, global rung cap ~24) — never jump to λ=1 from a stalled iterate (the committed
    ramp's skip-to-1.0 behavior is the mechanism behind today's failures). Secant-extrapolate
    the state in λ as each rung's predictor (safe in log coordinates).
  - **Truncation enabled on intermediate rungs** (today forced OFF below λ=1) with the 8τ
    defect budget, plus **tiny-pivot → damping-escalation retry** in the Newton loop: the
    measured stalls end in a singular damped-normal-equations factorization driven by
    collapsed trace-component columns, which masking (truncation) is designed to remove.
    Budget raises alone were tested at the 128 cap and do not help — keep budgets modest.
  - **Boundary-aware stopping**: if an accepted rung shows any tray's liquid-withdrawal
    fraction > ~0.95 (or a PA return condensing > ~0.95 of the return tray's vapor), stop with
    a named starvation/quench diagnostic instead of crawling the budget.
  - λ = 1 must remain bitwise the authored input (keep the IEEE-identity discipline).
- **The λ=0.25 rung changes the problem shape** (stripper nodes appear). Warm start: spine values
  copied from the converged bare spine (identity mapping — spine numbering is preserved by §3);
  stripper interiors seeded from the draw tray: `ℓ_s,i = λD · ℓ_d,i / L_d` on every stripper
  stage, `T_s = T_d` (flat), HC vapor from one K-value evaluation at (T_d, P) against that liquid
  with a small total (e.g. 10% of the stage liquid), floored to the log-coordinate minimum.
  Exact recipe is implementer's choice **subject to**: seeds are never published, the fresh
  acceptance audit on the final state remains the only gate (side-draw F1 lesson).
- Partial-λ results are internal; failure publishes a typed failure naming the λ reached and the
  failing rung — never the λ<1 state (steam-branch surrogate-publication lesson; the guard that
  plugs it must extend to attachment ramps).
- Diagnostics: extend the ramp path strings properly this time — dry runs must not carry "wet
  ramp" labels (review finding 11).

## 10. Truncation and traces

- Trace-feed truncation stays spine-driven. Stripper nodes **inherit the retention set of their
  draw tray** (`retains(stripperNode, c) := retains(drawTray, c)`) — the stripper sees only what
  the draw carries plus steam. The 8τ defect budget accounting extends over stripper product
  streams (a truncated-away component's contribution to the stripper product is bounded by its
  contribution to the draw, which the existing budget already bounds).
- `V3NewtonTrace`/diagnostics arrays size to network nodeCount; display maps stripper nodes via
  `spineOf`-style labels ("stripper 2 stage 3"), never bare global indices.

## 11. Audits (independence is the point)

The steam review's central lesson (findings 3, 4, 6): **an audit that recomputes both sides with
the same helper is a tautology and is worse than no audit.** Every audit below must state its
independent basis; the fresh full-network residual audit (auditor reuses the evaluator) remains
the base family, wet + network automatically.

New/extended checks — each `Check` built pass-or-fail with **non-negative finite values only**
(the `V3AcceptanceAudit.Check` constructor throws otherwise — finding 4's trap):

1. **NETWORK_CLOSURE** (per component + total): feed + all steam = distillate + bottoms + side
   draws + stripper products + free water + overhead water, computed **from the published stream
   objects**, not from evaluator internals.
2. **STRIPPER_MATERIAL** (per stripper, per component): draw = product + returned vapor, from
   published streams + an independently recomputed draw split.
3. **PA_COOLING**: `T_ret ≤ T_pd + tol`, and published `Q_pa ≥ 0`, recomputed via direct thermo
   enthalpy calls on the accepted state.
4. **PA_RETURN_LIQUID**: `Σ K_i(T_ret, P) · x_pd,i ≤ 1 + tol` via direct K-value calls.
5. **WATER_PROFILE (network)**: recompute the water profile by an **independent graph traversal
   written in the auditor** (not by calling `V3SteamFeeds`) and compare node-by-node against the
   problem's arrays — this replaces the tautological form flagged in finding 6.
6. **WATER_DEW_POINT** over all nodes including stripper stages (existing math, more nodes).
7. **CONDENSER_PHASE (wet)**: the independent flash must come back (finding 3's fix) and covers
   the CDU condenser unchanged.
8. **STRIPPER_STRIPPING** (physics sanity, warn-level if the maintainer prefers): each stripper
   product's light-key mole fraction ≤ its draw's, computed from published streams.

Audit failures surface as failed audits with named checks — the calculator's
audit-exception→UNAVAILABLE wrapper must not be the path by which these report (that was finding 4's
failure mode; fix lands with the prerequisite).

## 12. Provenance, versions, kill switch

- Digest: hash PA + stripper fields (all spec numbers, counts, water-data revision already
  covered) **only when the lists are non-empty** — the bare-column digest stays byte-identical;
  extend the pinned-hex test alongside the existing dry pin (`V3SideDrawContractTest:60-63`
  pattern).
- `formulationRevision`: bump the wet family, e.g. `v3-network-mesh-r7` with the existing
  suffix discipline (`-steam`, `-side-draws`, `-flash-trace`, now `-cdu`); exact naming is
  maintainer question Q4.
- `WET_ASSUMPTIONS_REVISION` bumps (r1 → r2: adds "PA return is dry subcooled liquid at spec'd
  T_ret", "stripper product carries no water", "stripper vapor returns saturated at stripper-top
  conditions").
- Wire: `WIRE_SCHEMA_VERSION` 6→7, `DATA_VERSION` 6→7 with NBT migration (absent lists → empty),
  `SCHEMA_VERSION` stays 1. BE `loadAdditional` must catch the *documented* exception set from
  `readInput` — fix finding 7's `NoSuchElementException` gap as part of the prerequisite, and do
  not add new `orElseThrow` paths in the new readers.
- `MAX_STREAMS` 7 → 10 (three stripper bottoms), `MAX_COMPONENTS` unchanged.

## 13. Streams and displayed quantities

- New streams: `stripper_1_bottoms` .. `stripper_3_bottoms` (generic ids; GUI labels them by
  draw stage — product naming like "kerosene" is a fluid-package/GUI concern, Q3).
  Stripper products are hydrocarbon-only liquids (§6 guarantees it; audit 6 proves it).
- Stripper return vapor and PA streams are internal — not published as streams.
- New quantities: per-PA duty `Q_pa` (the user-facing payoff of the T_ret spec — the panel shows
  the heat each pumparound rejects; future hook for in-game heat integration), per-stripper
  product rate + light-key purity if the display has room.
- `V3ColumnDisplayResult`/result screen: a compact per-attachment summary block; stripper stage
  profiles behind the existing per-node diagnostics view with `spineOf` labeling (§10).

## 14. GUI — dedicated CDU page (user decision 2026-09-01)

The input screen becomes paged; page 2 "CDU attachments" hosts:

- **Pumparounds group** (3 rows): draw stage, flow rate (kmol/h), return stage, return T (°C).
- **Side strippers group** (3 rows): draw stage, draw rate (kmol/h), stage count (2–4),
  return stage (blank = draw−1), steam rate (kmol/h), steam T (°C).

Unit and parsing conventions copy the steam fields exactly (kmol/h ÷ 3.6 → mol/s, °C + 273.15;
blank/zero row disables). **Prefill discipline: every attachment field defaults to blank/disabled
— no authored defaults on the server state** (review finding 5's lesson: prefills silently turn
the stock run into a different problem).

Page mechanics: a page toggle button + per-page widget groups is enough (Minecraft screens are
cheap to page; scrolling containers are not needed). Draft NBT (`V3SteamFeedDraft` pattern) grows
matching PA/stripper draft records with the same reset-on-invalid semantics — after the
prerequisite fixes the reset path so invalid drafts don't silently wipe (finding 1's BE
consequence). Sump steam stays on page 1 (it is core operation, pairs with Q_R).

Validation feedback: the existing inline message area must name the offending attachment row
("Stripper 2: return stage must be above draw stage").

## 15. Verification ladder

1. **Contract tests** (resolver-level, no solve): canonicalization, DOF squareness with strippers
   (ledger unknown/equation counts), water-profile-on-tree goldens, digest wet≠bare + bare pin,
   validation rejections, wire/NBT round-trip at v7 + migration from v6.
2. **FD-vs-local Jacobian on networks**: the pattern-aware verification `assemble` vs
   `assembleLocal` on (a) PA-only, (b) one stripper, (c) 2 PA + 2 strippers overlapping spans —
   manufactured states, tolerance matching the existing dry test. This closes the class of gap
   the steam review flagged as finding 9 *at network level from day one*.
3. **Degenerate-equivalence oracles** (the strongest cheap checks):
   - PA with R → ε: published state matches the bare column within tight tolerance.
   - Stripper with f → ε and S=2: overhead return ≈ 0, product ≈ draw; matches the *existing
     side-draw column* result at the same draw within loose tolerance.
   - T_ret = T_pd (audit-tolerance edge): Q_pa ≈ 0 and the state matches an
     adiabatic-recycle column.
4. **End-to-end wet CDU test in the registered test package** — `calculate()` with Q_R=0 + sump
   steam + 1 stripper + 1 PA, asserting convergence, audits all pass, streams close, Q_pa > 0,
   stripping monotonicity (raise f → lighter product gets lighter). Non-negotiable and cheap
   compared to its value; the steam review (finding 2) showed what "suite green, feature dark"
   looks like — do not repeat it.
5. **Physics monotonicity probes** (test or documented notebook): PA duty ↑ ⇒ internal vapor flow
   above p_r ↓; stripper steam ↑ ⇒ product light-key ↓.
6. **Independent oracle extension**: the Holland-style dense mesh oracle
   (`IndependentHollandMeshOracle` pattern) generalized to one small fixed network (N=6, S=2,
   C=3) — independent residual assembly, compare converged states to 1e-10.
7. **Capstone: Sotelo 2019 full CDU** — steam + side strippers + pumparounds as published (the
   dry emulation's documented nonconvergence at tray 22 was the motivation for this whole arc).
   Reproduce published product yields/temps within a documented tolerance band; ship as an
   in-game preset like the Holland one. Verify the paper's exact attachment configuration when
   implementing (documentation from the side-draw workstream has the case data).

## 16. Phases and prerequisite

**Prerequisite — SATISFIED 2026-09-01**: steam landed on main (56771fc) with all 11 review
findings fixed and verified (suite green; addendum in `V3_STEAM_STRIPPING_REVIEW.md`). The
fix-verification review filed 5 minor follow-ups, none blocking: fold N2 (combined-ramp progress
labels) and N3 (skip-to-full jump) into Phase A0, and land N1 (**bump the wet formulation or
assumptions revision** — the fix changed the wet condenser math under an unchanged
`v3-wet-mesh-r6-steam` label) before or with this plan's §12 provenance work, since the CDU's
revision discipline builds on that label. The extension pattern for the staged ramp already
exists on main (`RampStep`); the CDU adds a pumparound phase and the A0 hardening.

- **Phase A0 — ramp hardening (benefits plain side draws immediately)**: adaptive λ bisection +
  secant predictor + truncation-enabled intermediate rungs + tiny-pivot damping escalation +
  boundary-aware stopping (§9), retrofitted onto the existing draw ramp and verified by
  re-running the stress sweep in `documentation/V3_CDU_CONVERGENCE_RISK.md` — target: the
  150 kPa three-draw case converges well past today's 0.25× wall (0.75× is the acceptance bar;
  1.0× may be genuinely dry-infeasible per regime 2). The budget-only fix is already refuted by
  the discrimination probe (LINEAR_SOLVE_FAILURE at the 128 cap); the P-R1 trace follow-up
  (0.50× with truncation on) rides along with A0.
  **STATUS COMPLETE 2026-09-02: A0 closes at `9d18bb9`; the exact final screen and timing
  evidence are filed in `documentation/V3_COLD_DOE_A0_RERUN.md`. The retained path attaches
  wet-lane draws dry at the anchor and ramps steam last through the low-pressure lane; direct wet
  cases use the measured draw-first or steam-first basin with a bounded compatibility retry. The
  final 36-cell screen is 26/36 audited successes versus `cc1affb`'s 17/36 and main's 7/36,
  including dry knot 7/7 and steam knot 7/7 with no `cc1affb` success lost. Its direct low-pressure
  steam timing improves 48.4% at the 100 kPa / 50 C / 5% control, while high-pressure controls
  stay within +7.4% of `cc1affb`.

  `V3AdaptiveRamp` increment re-expansion was tested and rolled back: after wet-lane symmetry it
  had no material independent benefit. A near-miss extra full-FD leg was not implemented for the
  same measured reason; final publication remains exact. Truncation-enabled intermediate rungs
  are also retracted from A0: family H was byte-identical because the stalled PC6 point is
  trace-liquid but major-vapor. The residual collapsed-column wall therefore remains a separate
  linear-layer design pass, carried into Phase A as an explicit limit rather than a hidden
  admission policy.**
- **Phase A — network spine + pumparounds (dry-capable)**: `V3NetworkTopology` (zero-attachment
  invariant + pin test), graph-driven material stencil, pattern-aware guards, PA residual terms +
  probe routing, attach-ramp for PA only, FD-vs-local (2a), degenerate oracle (3a), dry PA
  end-to-end. PA needs no steam — this de-risks the entire linear/topology layer before any
  chain nodes exist.
- **Phase B — stripper chains**: interleaved ordering, chain nodes + seeding, stripper terms,
  steam-on-tree profiles, joint ramp, tests 1/2b/2c/3b, the wet end-to-end test (4).
- **Phase C — productization**: audits (§11), digest/wire/NBT/versions (§12), streams + display
  (§13), GUI page (§14), remaining validation UX.
- **Phase D — capstone**: independent oracle network case (6), Sotelo full CDU (7) + preset.

Codex implements phase-by-phase; run `.\gradlew.bat test` after every phase (repo CLAUDE.md
rule — never trust the self-report).

## 17. Risks and probes

- **Attach-ramp nonconvergence at CDU-realistic rates — the plan's dominant risk, now
  measured**: see `documentation/V3_CDU_CONVERGENCE_RISK.md` (2026-09-01). Today's committed
  solver converges the three-draw TJL case at 150 kPa only up to ~12% of feed withdrawn and
  fails from ~20% upward (iteration-budget stalls with physical margin), with true tray-22
  starvation (withdrawal fraction 1.3–3.4) at 90–100% of literature rates. The CDU's stripper
  draws total 40–50% of feed. Mitigations are folded in as requirements: §9 staged adaptive
  ramp (PA-first, bisection, predictor, boundary-aware stopping) and Phase A0 (ramp hardening
  before any CDU machinery). Residual risk after A0: authored specs that are genuinely
  dry-path-infeasible mid-ramp even with PA support — surfaced by the boundary diagnostics
  rather than budget burns.
- **Normal-equations conditioning with wider band**: damping already adapts; watch iteration
  counts in Phase A perf notes. If `stageBandedProduct`'s generalization is deferred and
  `denseProduct` used, budget it (n³ ≈ 7e8 at full size — acceptable once per iteration on
  desktop but measure in-game tick budget).
- **Seeding quality for chains**: flat-T draw-composition seeds may sit far from the stripped
  profile at high steam; the λ ramp is the mitigation; keep the Holland cold-start probe's
  lessons in view (still open).
- **PA/condenser spec conflict**: heavy PA cooling can starve the condenser (T spec infeasible)
  — expect typed nonconvergence, verify the failure is legible (names the PA) rather than a bare
  STATE_DOMAIN.
- **Bandwidth regressions from adventurous specs**: span caps (§8) bound it; the topology should
  expose `maxCouplingIndexDistance()` and a debug assert against the measured bandwidth.

## 18. Open maintainer questions

1. Caps: 3 strippers / 3 pumparounds acceptable? (GUI page fits 6 rows.)
2. Stripper return stage: keep the optional field (default d−1, span ≤ 3), or fix at d−1 for v1?
3. Stream naming: generic `stripper_k_bottoms` + GUI labels, or fluid-package-driven product
   names (kerosene/diesel/AGO)?
4. Revision naming: `v3-network-mesh-r7-cdu` vs keeping `v3-wet-mesh-r7-…`.
5. Crude-assay fluid package: is there (or should there be) a registered 8–12 pseudo-component
   crude package so CDU products are meaningful in-game, and which light-key marker should the
   stripping audits/display use?
6. Should PA duty (`Q_pa`) be exposed to other mod systems (heat network) now or display-only?

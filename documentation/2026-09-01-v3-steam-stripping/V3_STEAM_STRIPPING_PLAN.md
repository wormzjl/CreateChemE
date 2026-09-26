# V3 steam stripping — implementation plan (free-water contract)

**PLAN ONLY. No implementation exists on any branch as of this writing.**
Date: 2026-09-01. Code surveyed at `codex/hybrid-solver` tip `7bccf7b` (side draws + Holland
benchmark landed). Companion documents: `documentation/V3_SIDE_DRAW_PLAN.md` (the pattern this plan
extends), `documentation/VDU_SIMULATION_RESEARCH.md` (§1.3/D1 named the wet tower a "structural
cliff" and deferred it — this is that deferred workstream, scoped to stay off the cliff),
`documentation/V3_SIDE_DRAW_REVIEW.md` (the seed-vs-publish lesson this plan designs around).

Requested contract (maintainer, 2026-09-01):

1. Water becomes a valid species in the V3 contract, **completely immiscible with hydrocarbon**
   (free-water phase; no dissolved water, no water in hydrocarbon liquid).
2. The V3 input GUI gains a steam-injection section: which stage receives steam, plus the required
   process parameters.
3. This plan lives in `documentation/`.

---

## 1. Goal and scope

Add rate-and-temperature-specified **stripping steam feeds** to the V3 dry MESH solver so a column
can run with reduced or zero reboiler duty, the way a real CDU main tower does (bottom stripping
steam below the last tray, no reboiler). Water is treated under the industry-standard **free-water
assumption**: it never dissolves in hydrocarbon liquid, never condenses on trays (audited), travels
up the column as an inert-but-diluting vapor, and condenses only at the overhead drum where it is
decanted as a separate pure-water product.

**In scope**

- Up to `MAX_STEAM_FEEDS` steam injections: one at the **sump** (the reboiler node — the realistic
  CDU configuration) and/or on equilibrium trays.
- Zero-reboiler-duty operation when sump steam is present (`ReboilerDuty` already permits 0 W —
  `V3ColumnSpecification.java:33-42`).
- The hydrocarbon-VLE dilution effect of steam (the entire physics of stripping), water enthalpy in
  tray energy balances, a free-water product stream, water-saturation slip into the overhead vapor
  product, and per-node water dew-point audits.
- GUI section, wire protocol, NBT persistence, digest/provenance, and the continuation strategy.

**Out of scope (explicitly, so nobody discovers it mid-review)**

- Three-phase trays (liquid water on stages). The model *forbids* it and audits the assumption;
  a violated dew point is a rejected operating point, not a modeled regime.
- Water in the hydrocarbon feed vector, sour-water chemistry, steam-side pressure drop, and any
  water–hydrocarbon mutual solubility.
- Water in the PR78 EOS mixture (see §4.3 for why, and the probe that bounds the error).
- Pumparounds, side strippers, VDU ejector trains (own workstreams per the VDU research doc).
- Steam supply as a Create-mechanics resource (gameplay coupling comes after the solver contract).

**Process-reality anchors** (for parameter sanity, not for gates): CDU main-tower stripping steam is
typically 5–15 kg per m³ of residue product (≈1–3 wt% of feed; in *molar* terms ~5–20 % of a crude
feed because water's molar mass is ~10× lighter than a crude average), injected as low-pressure
superheated steam (~2.5–4.5 bar, 150–260 °C). Overhead drums run 30–60 °C with a water boot;
overhead corrosion control is exactly the dew-point question this plan turns into an audit.

---

## 2. The formulation (W1: known-profile water, zero new unknowns)

### 2.1 Why the state vector must not grow

V3 solves strictly-positive log-flow coordinates (`V3DryMeshState` + `V3DryMeshCoordinateMap`).
A water component in the state would need `ln(l_water) = ln(0)` on every stage under complete
immiscibility — unrepresentable, and the trace-truncation machinery exists precisely because
structural zeros must leave the state. Meanwhile complete immiscibility makes the water vapor
profile **algebraically determined by the input alone**:

```
f_j   = authored steam feed rate into node j            (mol/s, constant)
w_j   = Σ_{t ≥ j} f_t   for nodes j = 1 .. N+1          (water vapor leaving node j upward)
w_0   = overhead slip (condenser regime, §2.4)
```

Water flows up from each injection point, through every node above it, into the condenser. No
equilibrium equation, no material unknown, no Jacobian row. Water enters the Newton system only
through two well-localized terms:

### 2.2 Equilibrium residual: one additive dilution term per node

Current residual (`V3MeshResidualEvaluator.java:109-115`):
`r = ln y_i + lnφV_i − ln x_i − lnφL_i` with `y_i = v_i / V_hc` normalized over hydrocarbon vapor.

With water in the vapor, the physical vapor mole fraction is `y_i = v_i / (V_hc,j + w_j)`. Because
`w_j` is constant, this is exactly the current residual **plus a per-node scalar**:

```
r_wet(i,j) = r_dry(i,j) + ln( V_hc,j / (V_hc,j + w_j) )        [= ln(1 − y_w,j)]
```

- The added term is the same for every component on the node (it shifts every K by the factor
  `1/(1−y_w)` — the entire stripping effect; at `y_w = 10 %` that is `ln 0.9 ≈ −0.105`, i.e. ~11 %
  more effective volatility at the same temperature).
- It depends only on the node's own vapor flows → **band structure unchanged**, and the ledger's
  equilibrium-row reference set already contains every vapor flow of the node (the `ln y`
  normalization already couples them) → **`V3DegreeOfFreedomLedger` needs no change at all**
  (verified: draws enter the ledger only through material-row widening,
  `V3DegreeOfFreedomLedger.java:212-224`; steam adds no reference).
- Scale stays 1.0 (`scale()`, `V3MeshResidualEvaluator.java:199-206`); the term is O(0.1).

### 2.3 Energy balance: water enthalpy terms with known flows

Current tray balance (`V3MeshResidualEvaluator.java:117-130`) and phase energy
(`:136-141`, `E_phase = totalFlow × H_molar`). Additions, all with **constant flows** and
temperature-dependent molar enthalpies from the new water module (§4):

```
tray j:      + w_{j+1}·Hv_w(T_{j+1})  − w_j·Hv_w(T_j)  + f_j·Hv_w(T_steam,f)      (f_j = 0 off feed nodes)
reboiler:    + f_B·Hv_w(T_steam,B)    − w_B·Hv_w(T_B)          (w_B = f_B; enters the existing Q_R row)
condenser:   nothing — node 0 has no energy equation (T spec'd, `V3ColumnTopology.java:75-82`),
             and Q_C is not a published quantity today (`V3CalculatedQuantity.CONDENSER_DUTY` is
             declared but never computed in the production display path).
```

Implementation shape: fold `w_j·Hv_w(T_j)` into the **vapor phase energy** of node j (extend
`phaseEnergy(state, node, liquid=false, …)` with `problem.waterVaporFlow(node) × Hv_w(T_node)`), and
add the constant steam-inlet enthalpy rate `f_j·Hv_w(T_steam,f)` as a source term in
`energyResidual`. Doing it inside `phaseEnergy` makes the *neighbor coupling automatic and correct*:
the assembler propagates a node's vapor-energy derivative to the energy row above with coefficient
1.0 (`V3BlockJacobianAssembler.java:323-326`), which is exactly the `vaporIn` term including water.

### 2.4 Condenser: per-branch water regimes (no new equations)

The condenser branch machinery (`V3CondenserPhaseBranch`, chosen and corrected by the calculator)
gives three clean regimes. `T_cond` is a specification, so every water quantity below is a constant
or a linear function of solved hydrocarbon flows — nothing iterative:

| Branch | Water regime | Overhead slip `w_0` | Free water `F_w` | Dilution term at node 0 |
| --- | --- | --- | --- | --- |
| `TWO_PHASE` | **Saturated drum** (free water present, boot pinned at `p_w = Psat_w(T_cond)`) | `w_0 = s·V_hc,0` with `s = y_sat/(1−y_sat)`, `y_sat = Psat_w(T_cond)/P_0` | `w_1 − w_0` | constant `ln(1 − y_sat)` |
| `LIQUID_ONLY` | Total condensation; all water decants | 0 (no vapor product) | `w_1` | none (no VLE rows at node 0) |
| `VAPOR_ONLY` | No liquid at all; water stays in the vapor product | `w_1` | 0 | flow-dependent, same formula as a tray |

The `TWO_PHASE` saturated-drum assumption is the physical CDU case (that is why drums have boots).
Its validity is **audited, not assumed**: `FREE_WATER_SPLIT` (§7) requires `F_w > 0`; a drum too hot
or steam too scarce fails the audit with a clear diagnostic instead of publishing a wrong split.
Note the constant-`y_sat` dilution at node 0 also means the condenser VLE rows get a pure constant
shift — no Jacobian contribution at all there.

### 2.5 Jacobian: zero new derivative code

`assembleLocal` computes **all** VLE and energy derivatives by one-sided finite-difference probes of
`localTerms` (`V3BlockJacobianAssembler.java:50-95, 242-327`); only material rows are analytic —
and steam does not touch a single material row (water is not in the state; the hydrocarbon balances
`V3MeshResidualEvaluator.java:89-107` are unchanged). Therefore:

- Putting the dilution term inside `equilibriumResidual` and the water enthalpy inside
  `phaseEnergy`/`energyResidual` makes every Jacobian entry correct **automatically**, in both the
  production local-probe assembler and the full-FD verification assembler (`assemble`, `:15-41`),
  which remains a valid independent oracle with no modification.
- The off-band guard (`OFF_BAND_TOLERANCE`, `:11`) re-verifies band structure for free.
- The only reason to ever hand-write a water derivative is performance, and there is none: the
  probes already re-evaluate the node's residuals.

### 2.6 Alternatives considered and rejected

- **W2 — water as a state component with structural truncation masks.** Uniform indexing, but it
  drags water through `V3ActiveComponentBasis`, the flash paths, mask derivation, the audit's
  finiteness invariants, and the PR composition arrays — dozens of touch points to represent
  something the input already determines in closed form. Rejected; W1's bookkeeping *is* the
  physics under complete immiscibility.
- **Three-phase MESH** (liquid water on trays): a different solver class; explicitly out of scope.
- **Steam as a pseudo-reboiler duty only** (no water in equilibrium): misses the entire point —
  the partial-pressure dilution *is* stripping; duty-only reproduces none of the yield shifts.

---

## 3. Input contract

### 3.1 New spec record

```java
/** Steam injected as superheated vapor into one node; stage stageCount+1 addresses the sump (reboiler node). */
public record V3SteamFeedSpec(int stageNumber, double molarFlowMolPerSecond, double temperatureKelvin)
```

- `stageNumber ∈ [1, stageCount+1]`; `stageCount+1` = sump/reboiler node (the realistic CDU
  injection point). Constructor validates positivity/finiteness like `V3SideDrawSpec.java:4-10`.
- `V3ColumnInput` gains `List<V3SteamFeedSpec> steamFeeds` (canonicalized: sorted by stage, one
  feed per stage, `MAX_STEAM_FEEDS = 2`), a legacy constructor without the parameter (digest/equals
  precedent `V3ColumnInput.java:33-42`), and equals/hashCode/toString extensions.

### 3.2 Validation rules (in `V3ColumnProblemResolver.validateInput`, `:61-88`, mirrored by the GUI draft parser and the calculator's early gate)

| Rule | Failure text (shape) | Why |
| --- | --- | --- |
| `stageNumber ≤ stageCount + 1` | steam stage outside column | geometry |
| one feed per stage, ≤ `MAX_STEAM_FEEDS` | duplicate/too many steam feeds | contract bound |
| `Σ f ≤ totalFeed` (molar) | steam rate cap | numerical hygiene: keeps energy-row scaling (`max(1, F·1e5)`) and `y_w` in a qualified band; generous vs the 5–20 mol% practice range |
| `T_steam ≥ Tsat_w(P_inj) + 5 K` | steam must be superheated vapor at the injection pressure | injecting liquid water is out of contract; needs the pressure profile, so it lives at resolver level, not in the record |
| `T_steam` within the water module envelope (§4.4) | property range | provenance |
| `ReboilerDuty == 0` ⟹ a sump steam feed exists | zero-duty column needs sump steam (or duty) | with neither, `v_{N+1} → 0` is unrepresentable in log coordinates; steam on a tray above the sump does not vaporize the sump |

### 3.3 Resolved problem (`V3ColumnProblem`)

Mirror the draw arrays (`V3ColumnProblem.java:25-28, 84-93`):

- `nodeSteamFeedMolPerSecond[]`, `nodeSteamFeedEnthalpyWatts[]` (rate × `Hv_w(T_steam)`, precomputed
  constants), and the derived cumulative `waterVaporFlowMolPerSecond[]` per §2.1 — all built in the
  constructor from `input.steamFeeds()`.
- Accessors `hasSteamFeeds()`, `waterVaporFlow(node)`, `steamFeedEnthalpyWatts(node)`, plus the
  condenser-regime constants (`y_sat`, slip coefficient `s`) resolved once from the
  `CondenserOutletTemperature` spec and `P_0`.
- A small `V3SteamFeeds` utility (mirror of `V3SideDraws`) owns profile derivation and sump
  addressing so the evaluator, auditor, initializer guard, and stream builder share one definition.
- `V3DegreeOfFreedomLedger.create(...)` call sites: **unchanged** (§2.2).

---

## 4. Water property module (`V3WaterProperties`)

A standalone, dependency-free class in `science/column/v3/thermo/` — *not* a registered property
package, not part of `V3ThermoModel` (whose javadoc "water is intentionally excluded" gets updated
to point here). Pure static functions over pinned constants.

### 4.1 Required functions

| Function | Use | Correlation (pinned in code with golden values) |
| --- | --- | --- |
| `saturationPressurePascal(T)` | dew-point audits, drum `y_sat`, superheat validation | IAPWS-95 auxiliary (Wagner–Pruß) 6-term saturation equation, `Tc = 647.096 K`, `Pc = 22.064 MPa` |
| `saturationTemperatureKelvin(P)` | superheat validation | bounded Newton/bisection inverse of the above |
| `vaporMolarEnthalpy(T)` | tray energy terms, steam inlet enthalpy | ideal-gas Shomate (NIST WebBook coefficients), reference-consistent with `liquidMolarEnthalpy` |
| `liquidMolarEnthalpy(T)` | free-water stream reporting; future Q_C | `vaporMolarEnthalpy(T) − vaporizationEnthalpy(T)` |
| `vaporizationEnthalpy(T)` | above | Watson/DIPPR-106 form anchored at `ΔHvap(373.15 K) = 40.66 kJ/mol` |
| `MOLAR_MASS_KG_PER_MOL = 0.01801528` | streams, GUI kg/h hint | CODATA |

Only enthalpy **differences** of the same function enter tray residuals (§2.3), so the absolute
reference cancels inside the column; the reference matters only where vapor and liquid meet (drum
bookkeeping), which `Hl = Hv − ΔHvap` keeps exactly consistent.

### 4.2 Accuracy contract (the W5 lesson applies)

Golden-value tests against NIST steam tables, in-repo fixtures, **second-reader verification of the
transcribed coefficients before merge**: `Psat` at {313.15, 373.15, 423.15, 473.15, 523.15} K to
±0.3 %; `Hv(T2) − Hv(T1)` over {300→500, 500→700} K to ±1 %; `ΔHvap(373.15)` to ±0.2 %. Ideal-gas
vapor enthalpy (no pressure correction) is a declared assumption: at 1–4.5 bar superheated the
real-gas departure is small against the 40.7 kJ/mol scale of the terms it joins; the assumptions
revision (§8) records it.

### 4.3 φ-treatment decision (T1): water dilutes `ln y`, φ stays hydrocarbon-basis

The residual keeps `lnφV`/`lnφL` evaluated on the water-free normalized compositions exactly as
today. Water's effect enters only through the exact `ln(1−y_w)` dilution. Justification: at 1–4.5
bar the vapor is near-ideal (`|lnφV|` small) and the *composition sensitivity* of φV to an inert
diluent is second-order; putting water inside PR78 would require water Tc/Pc/ω plus water–HC kij
values that are notoriously unreliable, to correct a term smaller than the kij uncertainty — while
contradicting the immiscibility assumption on the liquid side. **Bounding probe (Phase A, cheap):**
evaluate PR78 φV for a representative tray composition with and without a water pseudo-component
(kij = 0) across 350–650 K / 100–450 kPa and record the max `|Δlnφ|`; expected ≪ the 1e-8
equilibrium gate's meaning at solution scale, i.e. a model-form note, not a solver-accuracy issue.

### 4.4 Envelope

Declared: `Psat` valid 273.16–647.096 K; enthalpies 273.16–900 K. Dew-point checks auto-pass for
nodes with `T ≥ 640 K` (no liquid water can exist near/above critical). Admission (steam inputs
inside envelope) is validated per §3.2; node temperatures are solved quantities, handled by the
audit's near-critical bypass rather than admission.

---

## 5. Touch-point inventory (file by file at `7bccf7b`)

Solver core:

| File | Change |
| --- | --- |
| `V3SteamFeedSpec` (new), `V3SteamFeeds` (new), `V3WaterProperties` (new) | §3.1, §3.3, §4 |
| `V3ColumnInput` | `steamFeeds` field + legacy ctor + canonicalization + `MAX_STEAM_FEEDS` |
| `V3ColumnProblemResolver` | validation rules §3.2; problem construction passes steam arrays |
| `V3ColumnProblem` | steam/water arrays + accessors + condenser regime constants |
| `V3MeshResidualEvaluator` | dilution term in `equilibriumResidual`; water vapor energy in `phaseEnergy`; steam source terms in `energyResidual`; `localTerms` unchanged in signature (its equilibrium/energy values pick the terms up) |
| `V3BlockJacobianAssembler` | **no change** (§2.5) |
| `V3DegreeOfFreedomLedger` | **no change** (§2.2) |
| `V3TruncationSupport` | **no change**; per-attempt mask re-derivation (`prepareAttempt`, `V3ColumnCalculator.java:792-803`) already re-derives support from each ramp leg's seed, so steam-shifted trace supports are covered; the 8τ defect audit stays the arbiter |
| `V3ColumnInitializer` | steam-blind by design (§6.3): `MATERIAL_CLOSED` is already pure HC bookkeeping; add a defensive guard so `SEQUENTIAL_MATERIAL_VLE`'s energy recurrence (`:582-637`, which reads `ReboilerDuty`) is never invoked on a steam-bearing problem under the Phase B design |
| `V3AcceptanceAuditor` | §7 |
| `V3ColumnCalculator` | §6: early INFEASIBLE gates, surrogate-duty rung inputs, wet ramp, revision labels, solve-path/diagnostic strings |
| `V3InputDigest` | steam fields + water-data revision hashed **only when steam present** (precedent: cutoff bits `V3InputDigest.java:38-40`, draws `:51-54`) |
| `V3ColumnResult` / `V3ColumnStreamProperties` | free-water stream, water slip in overhead vapor stream, `MAX_STREAMS 6 → 7` |
| `V3ColumnDisplayResult` | wet assumptions revision branch (§8) |
| `V3OperatingDomainValidator` | optional: reject `T_cond < 273.16 K` with steam (ice at the boot is out of contract) |

Game surface:

| File | Change |
| --- | --- |
| `ColumnV3Network` | `writeInput`/`readInput` steam list (stage varint, rate double, temperature double); `WIRE_SCHEMA_VERSION 5 → 6` |
| `ColumnCalculatorV3BlockEntity` | NBT `SteamFeeds` list; `DATA_VERSION 5 → 6` ("version 6 adds optional SteamFeeds; version 5 inputs migrate unchanged with an empty list" — the exact §229 migration pattern) |
| `ColumnCalculatorV3Screen` + `V3SteamFeedDraft` (new) | §9 |
| `HYBRID_SOLVER_CODE_GUIDE.md` | refresh (input contract, thermo boundary note, audit families) — Phase C exit criterion |

---

## 6. Continuation and orchestration (the seed-vs-publish design)

The side-draw review's root lesson: intermediate rungs are **seed providers**; only the authored
problem must satisfy the authored contract. Steam adds a rung-failure mode draws never had — the
`WATER_DEW_POINT` audit could fail transiently on a rung whose endpoint is fine — so the design
keeps every existing continuation lane **bone dry**:

### 6.1 Surrogate reboiler duty on dry rungs

`withStageGeometry` (`V3ColumnCalculator.java:872-880`) already strips draws from coarse-grid
inputs; extend it (and the pressure-anchor input builder) to also strip steam feeds and substitute

```
Q_rung = Q_R,authored + Q_surrogate,      Q_surrogate = Σ_f  f · ΔHvap_w(T_ref ≈ 450 K)  (≈ 37 kJ/mol·rate)
```

so every rung is a plain dry column with an equivalent boilup load — solvable by all existing
machinery with **zero new interactions** (stage ladder, pressure legs, phase corrections, coarse-FD
recovery, truncation lanes, `preferredCondenserBranch` — which already probes on a stripped input).
The surrogate constant is a seed-quality knob, not physics; it is recorded in the solve events.

### 6.2 One wet ramp at the authored problem

Where `recoverWithDrawRamp` runs today (`:657-719`), a steam-bearing input instead runs a joint
**wet ramp** at the authored geometry and pressure, rungs `λ ∈ {0.25, 0.5, 0.75, 1.0}`:

```
steam rates = λ·authored      draws = λ·authored      Q_R = Q_R,authored + (1−λ)·Q_surrogate
```

- λ = 1 is **exactly the authored input** (digest integrity; the published problem is never a
  modified one).
- Reuses the existing ramp skeleton verbatim: first-leg material projection then state-carry,
  intermediate-failure tolerance with skip-to-1.0, `TruncationPolicy.OFF` on intermediate rungs,
  per-rung condenser phase correction, bounded iteration budgets, event strings
  (`"wet ramp stopped at λ …; failed checks=…"`).
- When steam is absent the existing `recoverWithDrawRamp` path runs **unchanged** (kill-switch).
- Intermediate rungs may fail `WATER_DEW_POINT` or `FREE_WATER_SPLIT`; that is tolerated exactly
  like intermediate draw-split failures — only λ = 1 gates publication.

Ordering consequence: with steam present, the *pressure* legs also run dry+surrogate and the wet
ramp moves to **after** the final pressure leg (today draws ramp at the anchor and ride the legs
wet). This divergence is deliberate — it is what keeps dew-point transients out of the qualified
pressure lane — and it is cheap: the ramp is 4 bounded solves at the final operating point.
**Phase C probe (P-W1)** races the alternative (steam through the pressure legs, like draws) on the
qualification matrix; if it never trips a rung audit and saves time, it can be adopted then, with
evidence.

### 6.3 Initializer and seeding

Under §6.1/6.2 the cold initializer only ever sees dry problems, so it stays steam-blind; a guard
makes that an invariant instead of an accident. The wet ramp's first leg uses the existing
material-projection handoff (`continuationSeed` → `V3BubblePointPreconditioner`); dry bubble-point
temperatures over-estimate wet tray temperatures, which Newton then relaxes — acceptable for a
λ=0.25 leg. **Phase B refinement (optional, probe-gated):** effective-pressure bubble points
(`P_eff,j = P_j·(1 − y_w,j)` using the known `w` profile and seed vapor totals) inside the
preconditioner, applied only when the problem has steam. The `MATERIAL_CLOSED` fresh-fallback lane
(`:174-197`) is automatically steam-safe: its seed construction (`:70-102`) is pure hydrocarbon
bookkeeping that never reads duty or enthalpy.

### 6.4 Early infeasibility gates (mirror `calculate`'s draw gate, `:93-98`)

`Σ steam > totalFeed`, superheat violation, and `Q_R = 0` without sump steam return typed
`INFEASIBLE_SPECIFICATION` / `INVALID_INPUT` failures before any solve, with GUI-legible text
(`"V3 zero reboiler duty requires sump steam (stage N+1) or positive duty"`). A steam diagnostic
(mirror `sideDrawDiagnostic`, `:750-773`) reports the governing dew-point margin on failures.

---

## 7. Acceptance audit (families and exact definitions)

The auditor recomputes residuals through the same evaluator (`V3AcceptanceAuditor.java:41-42`), so
`LOCAL_COMPONENT_BALANCE` / `EQUILIBRIUM` / `ENERGY_BALANCE` become water-aware **symmetrically and
automatically** — the audited equations are the wet equations. New/extended checks, all recomputed
fresh from the candidate + authored input (never from solver caches):

| Check | Definition | Limit |
| --- | --- | --- |
| `WATER_PROFILE` (new) | Re-derive `w_j` from `input.steamFeeds()` independently of `V3ColumnProblem`'s cached arrays; compare exactly; verify the published free-water/slip split satisfies `w_1 = F_w + w_0` to machine precision | exact (1e-12 relative) |
| `WATER_DEW_POINT` (new) | `max over water-bearing nodes j ≥ 1 of  p_w,j / Psat_w(T_j)` where `p_w,j = P_j · w_j/(V_hc,j + w_j)`; nodes with `T_j ≥ 640 K` pass trivially (§4.4); condenser excluded (regime-pinned) | ≤ 1.0 (hard; a violated node means liquid water on a tray — the model's core assumption is false there and the operating point is **rejected**, matching how a real tower treats it: a corrosion/salting incident). The minimum approach `T_j − Tdew_w(p_w,j)` is reported as a diagnostic event for gameplay/telemetry. |
| `FREE_WATER_SPLIT` (new, `TWO_PHASE` + steam only) | `F_w = w_1 − s·V_hc,0 > 0` | > 0 with the value reported as `F_w/w_1` |
| `CONDENSER_PHASE` (extended) | The independent drum flash (`:131-189`) is wrong under water dilution. With steam + `TWO_PHASE`: replace `thermo.flashTP` with an auditor-local two-phase split solve using `thermo.fugacity` and the **same constant shift** (`lnφL` effectively shifted by `−ln(1−y_sat)`, i.e. every K scaled by `1/(1−y_sat)`), bounded successive-substitution + Rachford–Rice, same 1e-8 comparison. With steam + `LIQUID_ONLY`: the existing liquid-phase flash check runs at unchanged (T, P) but its "no vapor" claim is about hydrocarbon only — keep, and note water is fully decanted by regime. Zero steam: **byte-identical existing path.** | 1e-8 |
| `FINITE_TOPOLOGY`, `SIDE_DRAW_SPLIT`, `TRUNCATION_MASS_DEFECT` | unchanged (water is not in the state; sink edges are HC-only) | — |

No audit is weakened anywhere; rung tolerance is a *calculator* policy (§6.2), never an audit edit.

---

## 8. Provenance, versioning, kill-switch guarantees

- **Digest** (`V3InputDigest.of`): when `steamFeeds` non-empty, hash per feed
  (`steam-stage`, `steam-rate-bits`, `steam-temperature-bits`) plus one
  `water-data-revision` field (e.g. `water-iapws-shomate-r1`). Empty list ⇒ **byte-identical
  digest stream** (the draws precedent).
- **Formulation revision**: steam present ⇒ `v3-wet-mesh-r6-steam` (+ `-side-draws` when draws
  present, + `-flash-trace` when cutoff > 0); dry inputs keep `v3-dry-mesh-r2/r4/r5-…` untouched
  (`formulationRevision`, `V3ColumnCalculator.java:739-748`).
- **Assumptions revision**: steam present ⇒ `v3-wet-assumptions-r1` (a new assumptions doc section
  pinning: complete immiscibility, ideal-gas water vapor, T1 φ-treatment, saturated-drum regime,
  dew-point hard gate, water-data revision reference); dry ⇒ existing `v3-dry-assumptions-r4`.
  `V3ColumnDisplayResult.fromAccepted` (`:38-50`) branches accordingly.
- **Wire** `WIRE_SCHEMA_VERSION 5 → 6`; **NBT** `DATA_VERSION 5 → 6` with empty-list migration;
  **`SCHEMA_VERSION` stays 1** (list-typed field addition with a legacy constructor, same judgment
  as draws).
- **Kill-switch checklist** (all must hold with the feature merged and no steam authored):
  digest bytes identical; formulation/assumption labels identical; stream list identical
  (no water stream); audit check list identical (new families only appear with steam);
  solver numerical path identical (dilution term is exactly 0.0 only if guarded — implement as
  `if (hasSteamFeeds())` around the additions so dry arithmetic is *not even perturbed by ±0.0
  rounding*); wire round-trip of legacy inputs unchanged apart from the version constant.

---

## 9. GUI (`ColumnCalculatorV3Screen`) — the maintainer-requested section

Layout (Inputs page currently: 3×3 scalar grid at `CONTENT_TOP+13/51/89`, side-draw row labels at
`+114`, fields at `+128`, notices at `+159/173/187`; panel 620×360):

- New label row `"Steam  sump kmol/h / °C   |   tray / kmol/h / °C"` at `CONTENT_TOP+152`, fields at
  `+166`, notices shift to `+197/211/225` (fits: 283 < panel budget 331).
- **Group 1 — Sump steam** (the headline use case, no stage field): `[rate kmol/h][T °C]`.
  Produces `stageNumber = stageCount + 1`.
- **Group 2 — Tray steam**: `[stage][rate kmol/h][T °C]`.
- Blank or zero rate disables a group (side-draw idiom); both filled = two feeds.
- Units follow the screen's conventions (`kmol/h` × 1000/3600 → mol/s; °C + 273.15 → K,
  `ColumnCalculatorV3Screen.java:37-39`). The validation line shows the kg/h equivalent
  (`rate × 18.015`) so players used to steam-in-kg see the magnitude.
- `V3SteamFeedDraft` (new, mirror of `V3SideDrawDraft.java:12-37`): pure parser, unit conversion,
  range checks, duplicate-stage rejection, `"sump"` handling via the fixed group; client-side
  mirrors of §3.2 rules that don't need the pressure profile (superheat is server-checked; the
  client shows the server rejection text).
- `loadInput` round-trips steam feeds into the fields; `refreshControls` disables the section for
  the Holland preset like every other editor; `draftInput` wires the list into the constructed
  `V3ColumnInput` with a `"Steam: …"`-prefixed validation detail on parse failure.
- Notice text (dry input): `"Steam strips with reduced or zero reboiler duty; sump steam enters
  below the bottom tray."` / (steam authored): dew-point-margin diagnostic surfaced after failures.
- **Streams page**: needs no layout work — the free-water stream (`free_water`, "Free water (drum)",
  phase LIQUID, pure `H2O` composition row) and the water fraction inside the overhead-vapor
  stream ride the existing paged stream cards; `MAX_STREAMS 7` keeps the pager correct.

---

## 10. Verification plan

1. **Water module goldens** (§4.2) + envelope rejection tests + second-reader coefficient check
   recorded in the PR description (W5 discipline).
2. **Residual arithmetic**: `V3MeshResidualEvaluatorTest` cases on a hand-built 2-tray problem with
   sump steam — dilution term equals `ln(V/(V+w))` exactly; energy rows shift by hand-computed
   water terms; zero-steam problems produce bitwise-identical residual vectors pre/post change.
3. **Jacobian equivalence**: existing `assemble` (full FD) vs `assembleLocal` comparison test
   extended with a steam problem — this is the independent check that §2.5's "automatic" claim
   holds, including the off-band guard.
4. **Contract tests**: every §3.2 rule; canonicalization; digest stability (dry digest byte-equal
   across the merge — a pinned regression test; wet digest sensitive to each new field); wire and
   NBT round-trips incl. v5 migration.
5. **Audit tests** (mirror `V3SideDrawAuditTest`): fabricated states violating dew point /
   water profile / free-water split fail the right family; the water-aware condenser split check
   agrees with the diluted-K analytical solution on a 2-component synthetic.
6. **Calculator integration**: TJL package, ~10 trays, 150 kPa, `Q_R = 0`, sump steam ~8 mol/s at
   450 K — converges through the surrogate + wet-ramp path, audits pass, free-water stream
   published, overall water and energy closure exact; a second case with steam + the existing
   literature draw set.
7. **Independent oracle**: extend the test-side `IndependentIdealMeshOracle` with the same W1 water
   model (independent reimplementation of §2.2–2.4 from this document, not shared code) and require
   V3 ≡ oracle at ≤1e-10 on the synthetic steam stripper — the Holland two-implementation pattern
   at unit-test cost. This is the main defense against a shared-formulation blind spot given that
   the auditor reuses the evaluator.
8. **Probes** (results recorded before Phase C exit): P-W1 pressure-leg policy race (§6.2);
   φ-magnitude probe (§4.3); cold-start-with-steam probe (does `SEQUENTIAL_MATERIAL_VLE` with the
   §6.3 recurrence extension beat surrogate+ramp? informational only).
9. **Literature target (Phase C+)**: re-run the Sotelo 2019 CDU **full-load** case *with* its
   stripping steam — the published column is steam-stripped, and the current dry emulation is the
   documented nonconvergence (`V3_SIDE_DRAW_LITERATURE_CASE.md`: draw exceeding internal liquid at
   tray 22). Steam changes the internal liquid profile the draws depend on; this is the first
   physically-faithful shot at the known open case. Treat as a probe with hedged expectations, not
   a gate: pumparounds are still absent and remain a plausible blocker.

---

## 11. Phases

- **Phase A — solver contract (no game surface).** `V3WaterProperties` + goldens; input/spec/
  resolver/problem; evaluator terms; digest/labels/audit families; unit + contract + Jacobian
  tests. Exit: dry kill-switch checklist green; wet 2-tray arithmetic verified; oracle extension
  green on the synthetic.
- **Phase B — orchestration.** Calculator gates, surrogate rungs, wet ramp, diagnostics, stream
  publication (free water + slip), initializer guard, `MAX_STREAMS`; integration cases; P-W1 probe.
  Exit: synthetic steam stripper and steam+draws cases accepted end-to-end; 317-test suite green;
  dry digests still byte-identical.
- **Phase C — game surface + evidence.** GUI section + drafts + network/NBT versions; probes
  written up; Sotelo-with-steam probe; `HYBRID_SOLVER_CODE_GUIDE.md` refresh. Exit: in-game wet run
  publishes the free-water stream; guide accurate; probe findings filed.

Each phase lands behind the empty-list kill-switch; there is no partial-wet state visible to a dry
user at any commit.

---

## 12. Open questions for the maintainer

1. **`MAX_STEAM_FEEDS = 2`** (sump + one tray) — enough? Side strippers would be the reason for
   more, and they are out of scope; 2 keeps the GUI row clean.
2. **GUI rate unit**: kmol/h (consistent with draws; recommended, with a kg/h hint) or kg/h
   (industry-native)?
3. **Overhead water slip**: model it (recommended; §2.4 saturated drum, costs one constant) or
   Phase-A-simplify to "all water condenses" with slip deferred? The slip is what makes the
   overhead vapor stream honest at warm drums.
4. **Free water as a registered Minecraft fluid** now, or calculator-display-only (recommended,
   matching V3's current stance; the fluid decision belongs with the future steam-supply gameplay
   coupling)?
5. **Revision strings**: accept `v3-wet-mesh-r6-steam` / `v3-wet-assumptions-r1` /
   `water-iapws-shomate-r1`, or keep the `dry` family name with a `-steam` suffix?

---

## 13. Sources

- Free-water assumption and decanter practice: standard commercial-simulator free-water flash
  treatment (Aspen HYSYS/Plus documentation of "free water" vs "dirty water" options); Watkins,
  *Petroleum Refinery Distillation* (CDU overhead drum water boots, stripping-steam ranges).
- Stripping steam rates: 5–15 kg/m³ product (≈1–3 wt% feed), GPSA/Watkins ranges; superheat
  practice 150–260 °C at 2.5–4.5 bar.
- Water property correlations: IAPWS-95 auxiliary saturation equation (Wagner & Pruß 2002);
  NIST WebBook Shomate ideal-gas cp for H₂O; `ΔHvap(373.15 K) = 40.66 kJ/mol` (NIST).
- Dew-point/corrosion reality of steam-stripped overheads: API RP 932-B context (overhead systems).
- Internal: all file/line references at `codex/hybrid-solver` `7bccf7b`, surveyed 2026-09-01:
  `V3MeshResidualEvaluator`, `V3BlockJacobianAssembler`, `V3ColumnCalculator`, `V3ColumnInitializer`,
  `V3AcceptanceAuditor`, `V3ColumnProblem(+Resolver)`, `V3DegreeOfFreedomLedger`, `V3InputDigest`,
  `V3ColumnStreamProperties`, `ColumnV3Network`, `ColumnCalculatorV3Screen`,
  `ColumnCalculatorV3BlockEntity`; `documentation/VDU_SIMULATION_RESEARCH.md` §1.3/D1;
  `documentation/V3_SIDE_DRAW_REVIEW.md` (seed-vs-publish).

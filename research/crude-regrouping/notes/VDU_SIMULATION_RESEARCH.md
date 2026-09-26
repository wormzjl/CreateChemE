# VDU simulation research — process reality, codebase gaps, and a candidate path

**RESEARCH ONLY. No implementation exists and nothing below is approved.**
Date: 2026-08-31. Code surveyed at branch `claude/vdu-simulation-research-7d963f` (worktree of
`54a4203`, the same base as the 55 kPa action plan). Companion documents:
`documentation/V3_55KPA_WALL_ACTION_PLAN.md`, `benchmarks/V3_LOW_PRESSURE_DIAGNOSIS.md`.

Purpose: establish what a vacuum distillation unit (VDU) actually is, what the mod's V3 machinery
already provides, what is missing, and a defensible phased route — so a future planning doc (in the
style of the 55 kPa plan) can be written against verified facts rather than assumptions.

---

## 1. What a VDU is (process reality)

The VDU is the refinery unit directly downstream of the crude/atmospheric column (CDU). It takes the
CDU's bottoms — atmospheric residue, nominally the 343 °C+ (650 °F+) material — reheats it in a fired
heater, and flashes it into a vacuum tower. Vacuum exists for exactly one reason: at atmospheric
pressure the temperatures needed to vaporize gas-oil-range material (~400–560 °C true boiling point)
would thermally crack it. Dropping the flash pressure to a few kPa lets the same separation happen at
heater outlet temperatures the feed can survive.

### 1.1 Operating envelope (literature-verified)

| Quantity | Typical value | Notes |
| --- | --- | --- |
| Column top pressure, **dry** tower | 10–15 mmHg ≈ **1.3–2.0 kPa** | No steam anywhere; deepest vacuum; booster ejector required |
| Column top pressure, **wet** tower | 40–60 mmHg ≈ **5.3–8.0 kPa** | Steam to heater coils + stripping steam at bottom |
| Deep-cut designs, top | 3–5 mmHg ≈ 0.4–0.7 kPa | Aggressive revamps |
| Flash zone pressure | 25–50 mmHg ≈ **3.3–6.7 kPa** | Feed-entry stage; some units to 30–50 |
| Heater outlet temperature | 385–400 °C common; 418–432 °C aggressive | Bounded by cracking/coking, not by equilibrium |
| Flash zone temperature | ~390–420 °C (≈ 750 °F cited as typical) | After transfer-line pressure drop and flash cooling |
| Total tower pressure drop | ~2–15 mmHg across packed beds | Structured packing/grid; ~0.1–0.3 kPa per theoretical stage |
| Theoretical stages, whole tower | **~8–15** | Coarse fractionation by design |
| Stripping steam (wet only) | bottom of tower + heater coil injection | Lowers hydrocarbon partial pressure |

Anchor configuration from the standard Aspen HYSYS vacuum-tower example: **9 theoretical stages,
11 kPa top / 13 kPa bottom, LVGO draw at stage 2, HVGO draw at stage 6, pumparounds on both draws** —
i.e. a wet-pressure-range tower with ~0.25 kPa/stage drop. This is a good first target shape because
it is the configuration every published VDU simulation tutorial converges.

### 1.2 Structure — why a VDU is *not* the current V3 column

Top to bottom, a conventional fuels-type VDU:

1. **Overhead**: no condenser at all. Top-stage vapor (non-condensable cracked gas + traces, plus
   steam in wet units) goes to a 2–3 stage steam-ejector train with inter-condensers. The ejector
   train *sets* the top pressure; in-column, the top is just a vapor exit.
2. **LVGO section**: packed bed; LVGO liquid side-draw; **LVGO pumparound** (draw, external cooling,
   return above) does the reflux-generation job a condenser would do.
3. **HVGO section**: same pattern — side-draw + pumparound. Pumparounds remove nearly all the
   condensing heat of the tower.
4. **Wash section**: a short grid bed irrigated by HVGO-derived wash oil. Everything that leaves the
   flash zone as entrained/heavy vapor is washed back down; the net downward liquid leaving the wash
   bed is **overflash / slop wax** (typically specified as a small percentage of feed). If the wash
   rate is too low the bed dries and **cokes** — a hard operability constraint, and simulation-wise
   the reason overflash must be an explicit, audited quantity (wetting-rate floors around
   0.2 gpm/ft² are the cited bed-life threshold).
5. **Flash zone**: the feed enters here, already partially vaporized by the heater + transfer line.
   Feed vapor fraction at flash conditions is the single dominant driver of product yields.
6. **Boot / stripping zone**: wet towers add 2–4 stripping stages with steam; dry towers often have
   essentially none. Vacuum residue is drawn from the bottom, usually with a quench circuit.
   **There is no reboiler.** All heat enters with the feed (and steam, if wet).

Products: overhead gas (small, partly from thermal cracking — a non-equilibrium yield), LVGO, HVGO,
optional slop-wax draw, vacuum residue.

So relative to the current V3 CDU model, a VDU replaces the condenser with a plain vapor exit,
replaces the reboiler with a feed-enthalpy-driven flash zone near the bottom, and moves the entire
heat-removal duty into side equipment (pumparounds) that the current contract does not represent.

### 1.3 Thermodynamics at 1–13 kPa

- The vapor phase is nearly ideal (Z → 1, φ_V → 1). K-value accuracy is dominated by **liquid-side
  vapor-pressure extrapolation** for heavy pseudo-cuts at reduced temperatures ~0.4–0.6.
- Classical industry practice for vacuum columns is **BK10 (Braun K-10) / Maxwell-Bonnell**
  vapor-pressure-based K-values, which were built from sub-atmospheric (10 mmHg) petroleum data;
  Grayson-Streed and Peng-Robinson are the other methods commercial guidance lists for vacuum
  units. PR is usable — its cubic form has no floor pressure — but its α-function at low T_r for
  heavy cuts is an extrapolation whose error vs Maxwell-Bonnell should be *measured, not assumed*.
- Water/steam (wet tower) adds a second, nearly immiscible liquid phase and three-phase condensation
  in the ejector train — machinery the "dry V3" contract deliberately excludes today.

---

## 2. Where the codebase stands (verified against source at `54a4203`)

### 2.1 Already anticipates a VDU

- `V3OperatingDomainValidator` rejects any resolved profile below the package floor with detail
  string **`VDU_REQUIRED: resolved minimum pressure … is below this dry package's minimum`** —
  the boundary is named after this exact follow-on unit.
- `V3ColumnInput` javadoc: *"Side draws and water/steam feeds are intentionally absent from this
  M0/M1 contract and will require their own reviewed specification and degree-of-freedom
  extension."*
- `ColumnCalculatorV3Screen` already renders side-draw input fields, explicitly labeled
  *"display-only: the current V3 dry MESH contract has no side-draw equations."*

### 2.2 Hard constraints in the current contract

| Area | Current state | VDU requirement |
| --- | --- | --- |
| Package envelope | `V3Cdu17TiaJuanaPackage.minimumPressurePascal() = 50 000 Pa` | ~500–2 000 Pa floor |
| Component slate | C1, C2, C3, C4-lump, PC01–PC12; only **PC10 (NBP 709 K), PC11 (823 K), PC12 (1036 K, MW 0.65 surrogate)** cover the VGO/resid range | ~8–12 cuts across 340–620 °C + resid surrogate; LVGO/HVGO/resid products need cut resolution at their boundaries |
| Topology | `V3ColumnTopology` hardwires condenser node 0 (no T unknown, spec'd temperature), trays 1..N, reboiler node N+1; single feed; exactly 2–3 products | No condenser node; no reboiler node; 2+ liquid side draws; 1–2 pumparounds; feed near bottom; 5 products |
| Specifications | `V3ColumnSpecification` permits only CondenserOutletTemperature, OrganicRefluxRatio, ReboilerDuty | Side-draw rates, pumparound duty + return temperature, top pressure (ejector suction); overflash as calculated/audited quantity |
| Numerics | Full-support Newton stalls below ≈56.86 kPa on the CDU configuration (documented continuation barrier, not a proven physical limit); truncated lane converges at 55 kPa but a frozen mask fails the mass-defect audit | Every VDU operating point sits 4–40× deeper than the wall; sub-representable trace flows (resid components above the wash bed) are the *default*, not the exception |
| Jacobian structure | `V3BandedMatrix`/`V3BandedPivotedSolver`: adjacent-stage banded coupling | A pumparound couples non-adjacent stages (draw j → return i), breaking the band; needs a bordered-band/Schur-complement treatment of a few coupling variables |
| Water | "Dry" contract everywhere (dry assay, dry MESH) | Dry VDU: fine. Wet VDU: new phase machinery |

### 2.3 What transfers essentially unchanged

The expensive, hard-won machinery is topology-agnostic or nearly so and is exactly what a VDU solve
needs: the PR78 session/workspace stack with declared envelopes and advisory evidence;
`V3FeedFlash`/`V3TruncatedFlash` (transfer-line flash of the feed *is* a TP flash);
the trace-truncation lane (`V3TruncationSupport` mask derivation, `projectSeed`, the 8·τ mass-defect
audit, `V3TruncationFallback`) — at VDU pressures this stops being an optimization and becomes the
enabling mechanism; the DOF ledger's unknown/equation enumeration with bipartite structural-rank
checking (built to be re-enumerated for new topologies); the acceptance-audit / digest / formulation-
revision / bounded-service / server-authoritative pattern; and the probe-first benchmark culture
(`benchmarks/` harness with package-private access and refuse-to-overwrite reports).

### 2.4 Evidence: what the CDU actually hands a VDU today

From the committed accepted 100 kPa cell (`benchmarks/results/v3-flash-cold-final/100-off.json`),
the bottoms liquid is, in mole fraction: PC05 0.010, **PC06 0.098, PC07 0.169, PC08 0.146,
PC09 0.124** (kerosene/diesel/AGO-range material, NBP 504–651 K), PC10 0.140, PC11 0.217, PC12 0.094.

Two consequences:

1. Because the current CDU has no side draws, it runs as a **topping column** — its "residue" still
   carries ~55 mol% of middle distillates that a real CDU would have drawn off as kero/diesel/AGO.
   Feeding that to a VDU model would be physically wrong at the flowsheet level, not just imprecise.
2. Therefore the **side-draw + pumparound topology extension is shared infrastructure**: the CDU
   needs it (at friendly, already-converged pressures ≥100 kPa) for its own realism, and the VDU
   cannot exist without it. This creates a natural, low-risk proving ground: qualify side draws and
   pumparounds on the CDU at 100–150 kPa first, where the solver is healthy and every existing gate
   applies, before combining them with deep vacuum.

---

## 3. Design questions and recommendations

**D1 — Dry vs wet tower: build the dry VDU first.** Dry vacuum towers are real, current technology
(deepest vacuum, no steam), and they are the only variant that fits the existing dry-hydrocarbon
contract. Wet operation (stripping steam, three-phase ejector condensing) should be a separate,
later workstream gated on a reviewed water-phase design. The irony that the *dry* tower needs the
*deeper* vacuum (1.3–2 kPa top vs 5.3–8 wet) is acceptable: pressure difficulty is continuous, and
the wet tower's extra phase is a structural cliff. A defensible intermediate is to model a
"damp-pressure-range" dry tower first (HYSYS-example pressures, 11–13 kPa, no steam) as the
qualification point, explicitly documenting that its real-world counterpart would be damp/wet.

**D2 — Same solver core, new problem class; never touch the CDU lanes.** Recommend a `V3`-family
extension (new topology class + ledger enumeration + spec types + package), not a fork and not a
generalization that rewrites the CDU path. The τ=0 CDU digest stream must remain bit-identical
(same discipline the 55 kPa plan enforces); a VDU formulation gets its own revision string and its
own qualification matrix. The condenser/reboiler assumptions run through
`V3StageBlockLayout`, `V3MeshResidualEvaluator`, initializers, and preconditioners — this is the
bulk of the engineering cost and should be sized honestly in the eventual plan.

**D3 — New property package: re-cut the residue, drop the light ends.** A
`vdu##_tjl` package should re-characterize the *atmospheric residue portion* of the same Tia Juana
Light assay into ~8–12 cuts spanning ~340–620 °C plus one (better: two) vacuum-residue surrogates,
using the same Kesler-Lee 1976 correlation family and closure discipline as
`cdu17-tjl-kl1976-r2`, with a declared envelope of roughly 500 Pa–200 kPa and the same
advisory-evidence pattern for extrapolated heavies. Dropping C1–C4 and the naphtha cuts from the
slate is both physically right (they are gone before the VDU) and numerically merciful: it removes
the most extreme K-value spreads from the system. This is data work that can proceed in parallel
with solver work, but it must inherit the property-revision contract (golden K/L values,
second-reader verification — the still-open W5 lesson).

**D4 — Slate handoff contract (CDU bottoms → VDU feed).** The VDU feed should be *derived from* an
accepted CDU bottoms stream, which requires a documented mapping from the 17-component slate onto
the VDU slate (TBP-consistent redistribution, mass-conserving, with the middle-distillate content
that a draw-equipped CDU leaves behind). This deserves its own small reviewed contract — it is the
first inter-unit stream handoff in the mod, and the pattern (assay-anchored re-cutting of an
accepted stream) will recur for every downstream unit (FCC, hydrotreater, coker).

**D5 — K-value method: keep PR, measure it against Maxwell-Bonnell, gate on the deviation.**
Recommend PR78 as the single production model (consistency with the CDU, no second thermo stack),
with a probe that tabulates K-values for every VDU cut across 0.5–15 kPa / 550–700 K against a
bench-only Maxwell-Bonnell/BK10 reference implementation. If deviations for the cuts that control
LVGO/HVGO splits exceed an agreed band, that becomes a dataset question (α-function or vapor-pressure
tuning inside the *new* package's revision contract — never edits to the CDU package). The vapor
phase at these pressures is near-ideal, so this cleanly isolates the liquid-reference uncertainty.

**D6 — Numerics: assume the truncation lane is load-bearing, and probe the continuation strategy
before committing.** Facts to design around: the 56.86 kPa wall was located on the *CDU*
configuration; its mechanism (collapsing trace flows degenerate the full-support system in
strictly-positive log-flow coordinates) is generic and will be *structural* at VDU pressures —
resid components simply do not exist above the wash bed. Mitigating structure: the VDU system is
much smaller (~10–15 stages × ~10–12 components vs 32 nodes × 17), its fractionation is deliberately
sloppy (smoother profiles), and a matched slate removes the worst trace extremes. Three continuation
candidates, to be raced probe-first exactly like R1: (a) **cold start at target pressure** from a
feed-flash-anchored initializer — commercial simulators converge 9-stage VDUs from crude estimates,
and shallow columns are forgiving; (b) **log-pressure continuation** from ~100 kPa with adaptive
rungs and per-rung mask refresh; (c) continuation in **feed enthalpy/duty at fixed vacuum**. The
55 kPa plan's R2 (bounded defect-gated mask refresh between attempts) is prerequisite infrastructure
for (b) and probably for (a)'s retry ladder; its P0 probe verdict directly informs VDU feasibility.

**D7 — Pumparound coupling: bordered band, not a wider band.** Each pumparound adds a handful of
scalar unknowns (draw flow, return enthalpy/temperature) coupling two non-adjacent stages. The
banded LU should stay; the standard treatment is a bordered system (band + low-rank coupling rows/
columns) solved by block elimination/Schur complement on the few coupling variables, preserving the
existing backward-error evidence pattern. Side draws, by contrast, are band-local (one extra
unknown + one spec equation per draw) and cheap.

**D8 — Ejectors, cracking, and coking are boundary conditions and gameplay, not MESH equations.**
The ejector train reduces to "top pressure is what the ejector achieves," with motive-steam demand
as a utility-cost curve (steeper as suction pressure drops) — chart-based, no VLE. Thermal cracking
in the heater reduces to a small correlation-based cracked-gas yield (the AFPM panel literature
treats it exactly that way) plus a hard heater-outlet-temperature limit; coking reduces to an
overflash/wetting floor with equipment-damage consequences. All three map onto Create-native
mechanics (steam supply, heat, maintenance) without touching the solver.

---

## 4. Candidate phased route (for a future plan doc; not a commitment)

- **P0 — Prerequisites already in flight.** The 55 kPa mask-refresh plan (R1→R2/R3) must land or
  fail first; its outcome changes D6. Its P0 probe verdict is the single most informative input.
- **P1 — VDU property package + handoff mapping (data lane, parallelizable).** Re-cut slate,
  KL1976 provenance, PR-vs-Maxwell-Bonnell deviation probe (D5), envelope declaration, golden
  values, second-reader gate. Deliverables mirror the existing characterization contract.
- **P2 — Topology/DOF extension, qualified on the CDU at ≥100 kPa.** Side draws + pumparounds +
  bordered-band solver + new spec types + ledger enumeration + structural-rank tests, proven where
  the solver is healthy: a draw-equipped CDU (kero/AGO draws) whose τ=0 no-draw digests remain
  bit-identical. This fixes the topping-column realism problem (§2.4) as a side effect and de-risks
  the VDU structurally before any vacuum numerics.
- **P3 — Absorber-style ends.** Remove condenser/reboiler roles behind the new topology class
  (vapor-exit top, feed-driven bottom), still at benign pressures on synthetic cases; then the
  9-stage/11–13 kPa reference case on the VDU package, probe-first continuation race (D6).
- **P4 — Vacuum qualification.** Cold matrix at e.g. 13 / 8 / 5 / 2 kPa top with both cutoff
  settings, audits unweakened, failure-tax measurement, findings doc — same evidentiary standard as
  `v3-flash-cold-final`.
- **P5 — Game surface.** VDU calculator block (or a mode of a generalized column calculator),
  5-stream results UI, heater/ejector/coking gameplay couplings (D8), JEI/KubeJS exposure.

Kill-switch philosophy carries over unchanged: every new behavior behind its own formulation
revision; CDU lanes bit-identical; audits are the sole arbiter; probes before productization.

---

## 5. Open questions for the maintainer

1. **Scope of "VDU" for gameplay**: full LVGO/HVGO/resid product slate (recommended, it is the point
   of the unit), or a minimal 2-product vacuum flash tower as an interim toy?
2. **Sequencing**: accept the P2 detour (side draws on the CDU first)? It delays vacuum work but
   converts the riskiest structural change into a change qualified on healthy ground — and the CDU
   needs it anyway.
3. **Wet tower timeline**: is a water/steam phase workstream on the roadmap at all, or is dry-only
   an acceptable long-term stance for the mod?
4. **Pressure ambition**: is the 11–13 kPa reference band an acceptable v1 target (recommended),
   with 1.3–2 kPa dry-tower depth as a stretch goal gated on P4 evidence?
5. **Product fluids**: do LVGO/HVGO/vacuum residue become registered mod fluids now (forcing the
   inter-unit handoff contract, D4), or do VDU results stay calculator-display-only like V3 today?

---

## 6. Sources

Process parameters and practice (accessed 2026-08-31):

- Eng-Tips, "Vacuum Tower Pressure" — top 3–5 mmHg / flash 25–30 mmHg deep-cut figures;
  dry 10–15 mmHg / wet 40–60 mmHg classification: https://www.eng-tips.com/threads/vacuum-tower-pressure.95390/
- AONG, "What is Vacuum Distillation?" — dry/damp/wet tower taxonomy: https://www.arab-oil-naturalgas.com/what-is-vaccum-distillation/
- Digital Refining, "A balanced approach to vacuum tower flash zone/wash section design": https://www.digitalrefining.com/article/1000973/a-balanced-approach-to-vacuum-tower-flash-zone-wash-section-design
- SlideShare, "Crude tower simulation HYSYS v10" — 9-stage, 11/13 kPa, draws at stages 2 and 6: https://www.slideshare.net/slideshow/crude-tower-simulationhysysv10/175829561
- Sudan Univ. of Sci. & Tech. thesis, "Simulation of VDU and improving its productivity using Aspen HYSYS": https://repository.sustech.edu/bitstream/handle/123456789/11850/SIMULATION%20OF%20VACUUM%20....pdf
- Smart Process Design, "HYSYS Fluid Package notes" — BK10/GS/PR guidance for vacuum units: https://smartprocessdesign.com/aspentechs-hysys-fluid-package-thermodynamics-notes/
- Smart Process Design, "Cracked gas from vacuum unit heaters": https://smartprocessdesign.com/cracked-gas-vacuum-unit-heaters/
- AFPM Q&A 47 — cracked-gas-make correlations vs heater outlet T: https://www.afpm.org/data-reports/technical-papers/qa-search/question-47-what-correlations-do-you-use-predict-cracked
- AFPM Q&A 65 — wash-bed life, wetting-rate floor ~0.2 gpm/ft²: https://www.afpm.org/data-reports/technical-papers/qa-search/question-65-our-vacuum-column-wash-bed-has-lasted-seven
- Oil & Gas Journal, "Simple methods solve vacuum column problems using plant data" — overflash
  measurement vs calculation: https://www.ogj.com/home/article/17219784/simple-methods-solve-vacuum-column-problems-using-plant-data
- Cheresources forum, "Need help on vacuum distillation overflash": https://www.cheresources.com/invision/topic/5274-need-help-on-vacuum-distillation-overflash/
- ResearchGate, "Improve Vacuum Heater Reliability" — heater outlet 725–810 °F practice band: https://www.researchgate.net/publication/260516737_Improve_Vacuum_Heater_Reliability

Internal evidence: `V3OperatingDomainValidator.java` (VDU_REQUIRED), `V3ColumnInput.java` (M0/M1
exclusions), `V3Cdu17TiaJuanaPackage.java` (envelope + slate), `V3ColumnTopology.java` /
`V3ColumnSpecification.java` / `V3DegreeOfFreedomLedger.java` (structure),
`ColumnCalculatorV3Screen.java` (display-only side draws),
`benchmarks/V3_LOW_PRESSURE_DIAGNOSIS.md` + `documentation/V3_55KPA_WALL_ACTION_PLAN.md` (wall),
`benchmarks/results/v3-flash-cold-final/100-off.json` (bottoms composition).

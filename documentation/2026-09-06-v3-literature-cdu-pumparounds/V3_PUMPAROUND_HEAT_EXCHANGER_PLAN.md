# V3 pumparounds as internal heat exchangers: implementation plan

Date: 2026-09-06. Base: main `6c7d446` plus `6d629db` (TJL19 package) on branch
`claude/v3-literature-cdu-handoff-3179dc`. Written from the code alone; no earlier plan or guide was
used. Line numbers refer to that commit.

## 1. What is being modelled

A refinery pumparound withdraws liquid from tray `d`, cools it in an exchanger and returns it to a
higher tray `r`. This plan models only the heat effect: a prescribed duty removed from the column
between trays `r` and `d`, with no circulating stream. The column therefore gets one new physical
input, a signed heat duty per tray, and the pumparound is the user-facing way to author it.

Consequences of the simplification, to be stated in the assumptions revision:

- Internal liquid traffic between `r` and `d` is not increased by the circulation. Fractionation in
  that zone is slightly better than with a real pumparound (no back-mixing of a large recycle).
- The return temperature is not an input. The duty is prescribed, so no exchanger model is needed.
- The heat can be placed on the return tray only, or spread over the zone. Both are offered because
  a single-tray sink is what a reference simulator's per-stage heat duty reproduces exactly, while
  the spread is closer to where the heat is actually absorbed.

## 2. Contract changes

### 2.1 Input

New record `V3PumparoundSpec(int returnTray, int drawTray, double dutyWatts, Split split)` in
`science/column/v3`:

- `1 <= returnTray <= drawTray <= stageCount` (validated against geometry in
  `V3ColumnProblemResolver.validateInput`, like side draws). Trays only: node 0 is the condenser,
  whose duty is an output, and node N+1 already has `ReboilerDuty`.
- `dutyWatts` finite and nonzero. Sign convention is the existing one: positive adds heat to the
  column (`ReboilerDuty` semantics). A pumparound is negative. Positive values are accepted so the
  same primitive can be a side heater later, but nothing in this plan tunes for them.
- `Split` is an enum `{RETURN_TRAY, UNIFORM}`. `RETURN_TRAY` puts the whole duty on `returnTray`;
  `UNIFORM` divides it equally over trays `returnTray..drawTray`. Zones may overlap; per-tray duties
  add.
- `V3ColumnInput` gains a fourth list `pumparounds`, `MAX_PUMPAROUNDS = 3`, canonicalised by
  `(returnTray, drawTray)`, one entry per `(returnTray, drawTray)` pair. Both legacy constructors
  pass `List.of()`, so every existing call site compiles unchanged. `equals`, `hashCode`,
  `toString` include the list.

The expansion to per-tray duties lives in one small class `V3Pumparounds.nodeDutyWatts(input,
topology)` mirroring `V3SteamFeeds.nodeFeedFlows` (`V3ColumnProblem` lines 34-40), and is the
only place that knows the split rule.

### 2.2 Problem and residual

- `V3ColumnProblem` stores `nodeHeatDutyWatts[]` and exposes `hasPumparounds()` and
  `stageHeatWatts(node)`.
- `V3MeshResidualEvaluator.energyResidual` (line 119): the tray branch adds
  `+ problem.stageHeatWatts(node)` next to `problem.steamFeedEnthalpyWatts(node)`. The reboiler
  branch is unchanged. No new unknown or equation, so `V3DegreeOfFreedomLedger`,
  `V3DryMeshCoordinateMap`, `V3StageBlockLayout` and both Jacobians are untouched: a constant term
  has no derivative. The energy row scale `max(1, F * 1e5)` (line 222) stays; at CDU scale it is
  about 7e7 W, so the 1e-8 tolerance is below 1 W.
- `V3SumRatesPreconditioner` evaluates the same residual, so it sees the duty automatically. This
  matters only if a heat-bearing problem is ever cold-initialised; the plan never does that
  (section 3), but the preconditioner must not be made heat-blind by accident.

### 2.3 Digest and revisions

- `V3InputDigest`: after the steam block, only when the list is nonempty, hash
  `pumparound-return`, `pumparound-draw`, `pumparound-duty-bits`, `pumparound-split` and one
  `pumparound-split-rule-revision` string. Heat-free inputs keep their byte stream; a test pins a
  known digest.
- `V3ColumnCalculator.formulationRevision(input, cutoff)` (line 833): append `-stage-heat` when
  pumparounds are present, for both the dry and wet families, and bump the numeric revision of
  the heat-bearing variants only (heat-free strings unchanged).
- `assumptionsRevision(input)` (line 843): new constant `HEAT_ASSUMPTIONS_REVISION =
  "v3-heat-assumptions-r1"` composed with the dry/wet value when pumparounds are present. The
  string records: prescribed duty, no circulation, split rule, positive-adds-heat sign.

### 2.4 Outputs

New bounded record `V3ColumnDutyLedger(condenserWatts, reboilerWatts, stageHeatTotalWatts,
List<StageDuty> stageDuties, feedEnthalpyWatts, steamEnthalpyWatts)` with at most 16 stage
entries, attached to `V3ColumnResult` and `V3ColumnDisplayResult`. The condenser duty is computed
for every result, heat or not, from the condenser node: enthalpy of tray-1 vapor (plus water vapor
when wet) minus enthalpy of total condensate, overhead vapor, water vapor slip and free water at
the specified outlet temperature. `V3WaterProperties.liquidMolarEnthalpy` (line 73) already
supplies the free-water term.

## 3. Solver integration

The rule: heat is never present in a cold seed. It is introduced by continuation from an accepted
no-feature solution at the requested geometry, exactly as draws and steam are today.

1. `withoutPumparounds(input)` joins `withoutSideDraws` and `withoutSteamWithSurrogateDuty` at
   every stripping site: `preferredCondenserBranch` (line 125), the stage-continuation input
   (line 331), the pressure-continuation seed (line 397). `withStageGeometry` (line 986) already
   drops all feature lists through the legacy constructor.
2. `featureRampRequired` (line 330) and the equivalent pressure-continuation flag include
   pumparounds. `recoverWithDrawRamp` guard (line 715) also rejects a seed with pumparounds.
3. `RampStep` gains `heatFraction`; `requested()` and `progress()` include it. Rung order is
   steam, then heat, then draws. Cooling above a draw tray increases liquid arriving at that tray,
   which is what the draws need, so heat precedes draws. Steam stays first because the surrogate
   boilup must be removed before anything else is changed.
4. Heat rungs: four by default (0.25 steps). On an intermediate heat-rung failure, insert the
   midpoint between the last accepted fraction and the failed one, at most two subdivisions per
   rung and four in total, before falling through to the existing behaviour (attempt the
   requested rung, then return the typed diagnostic). Draw rungs keep their current behaviour.
5. Ramp input construction (line 740) scales every `dutyWatts` by `heatFraction`; the split is
   preserved. Truncation policy at intermediate heat rungs is `OFF`, as for other intermediates.
6. Solve path labels: `heat-ramp-<fraction>` alongside `steam-ramp`/`draw-ramp`; the final path
   appends `/heat-<count>` next to `/draws-` and `/steam-` (line 258).

### 3.1 Feasibility gates (typed failures instead of nonconvergence)

- Static admission, in `calculate` before any solve (line 95 area), one feed flash and one liquid
  enthalpy call: total cooling `sum(min(duty, 0))` must not exceed `H_feed(T_feed, P_feed) -
  H_feed_liquid(T_cond, P_top) + Q_reb + sum(steam enthalpy above liquid water at T_cond)`.
  This is a necessary condition, not sufficient. Violation returns
  `INFEASIBLE_SPECIFICATION` with both numbers in the summary.
- Dynamic bound, after the accepted no-feature rung at requested geometry: compute the base
  condenser duty `Q_cond0` from that state. If total cooling magnitude is at least `|Q_cond0|`,
  the overhead would vanish; return `INFEASIBLE_SPECIFICATION` naming `Q_cond0`.
- Per-tray condensation cap, evaluated from the last accepted rung before each heat rung: the
  next duty on tray `k` must not exceed the enthalpy that condensing all vapor arriving from
  `k+1` could release. Exceeding it triggers subdivision; if the smallest allowed increment still
  exceeds the cap, return `INFEASIBLE_SPECIFICATION` naming the tray, the cap and the duty.

### 3.2 Acceptance audit

- New check `GLOBAL_ENERGY_BALANCE` in `V3AcceptanceAuditor`: recompute every boundary
  enthalpy flow with a fresh property session at the accepted state (feed, steam, reboiler duty,
  stage duties, condenser duty from the ledger, all product streams) and require closure within
  `max(1 W, 1e-6 * largest absolute term)`. This is the independent guard for the sign of the new
  term; the row-residual `ENERGY_BALANCE` check cannot catch a wrong sign because it would be
  consistently wrong on both sides.
- Advisory event, not a check: on every cooled tray report vapor leaving over vapor entering;
  below 1e-3 the tray is condensation-capped and the event says so.

## 4. Transport and persistence

- `ColumnCalculatorV3BlockEntity`: `DATA_VERSION` 6 to 7; write a `Pumparounds` list tag
  (`Return`, `Draw`, `DutyWatts`, `Split`); a missing tag reads as an empty list without
  dirtying the result. The persisted display result gains the duty ledger; an older result without
  it is kept and shown with the ledger absent.
- `ColumnV3Network`: `WIRE_SCHEMA_VERSION` 6 to 7; the input codec appends the pumparound list
  after steam (line 592 area) with the same bounded-count reader; the display-result codec
  appends the ledger.
- Default input (`defaultInput`, line 349) keeps an empty pumparound list; a second preset with
  three pumparounds is added only after section 6 qualifies it.

## 5. GUI

The Inputs page is full (core editors, three side-draw rows at +128, steam at +178). Add a fourth
tab `Heat` (`Page.HEAT`) with three rows: return tray, draw tray, cooling MW, and a split toggle
button. The cooling field is a positive number in MW and maps to a negative `dutyWatts`; the
mapping and its inverse are covered by a codec test so the sign cannot drift. Below the rows,
when a result exists, show condenser duty, reboiler duty and total stage heat from the ledger.
Drafts are preserved across `init` like `sideStageDrafts`; validation messages follow
`validateDraft` (line 317).

## 6. Tests and validation

Unit:

- `V3PumparoundSpecTest`: bounds, sign, split expansion (single tray, uniform, overlap adds),
  canonical order, max count, geometry rejection.
- `V3InputDigestTest` additions: heat-free digest pinned; each pumparound field changes the digest.
- `V3MeshResidualEvaluatorTest` addition: with a manufactured problem, the residual vector with a
  duty differs from the no-duty vector only on that tray's energy row, by exactly `duty / scale`.
- `V3FiniteDifferenceJacobianTest` and `V3BlockJacobianAssemblerTest` on a heat-bearing input:
  analytic blocks match finite differences to the existing tolerance.
- `V3DegreeOfFreedomLedgerTest`: counts unchanged with pumparounds.
- Codec and NBT round trips with one, two and three pumparounds; version-6 tag reads as empty.

Calculator:

- Small-duty equivalence: a pumparound of -1 kW on the default case reproduces the base streams
  within the acceptance tolerances.
- 30-stage CDU17 default (`defaultInput`) plus one uniform pumparound of -5 MW over trays 8-12:
  converges, `GLOBAL_ENERGY_BALANCE` passes, condenser duty drops by about 5 MW (assert within
  10% of the duty; the rest moves into product enthalpies).
- Same with `RETURN_TRAY` split: converges; condenser duty within 2% of the uniform case.
- Three pumparounds plus the three default draws plus sump steam: exercises the full rung order.
- Infeasible cooling (-200 MW): `INFEASIBLE_SPECIFICATION` from the static gate, no solve started.
- Cooling above the base condenser duty: `INFEASIBLE_SPECIFICATION` from the dynamic gate, with
  `Q_cond0` in the summary.
- TJL19 package, 30 stages, one pumparound: converges; records timing.

External reference: one DWSIM 10.2.3 column with an energy stream on a single stage, the same
feed and specifications, and a thermodynamically consistent enthalpy method (the native PR78
default enthalpy is inconsistent with its fugacities, as measured earlier this month). Compare
stage temperatures, product rates and condenser duty for the `RETURN_TRAY` case. This is the only
check that tests the physics rather than the code, and it gates the in-game preset.

Performance: each heat rung is a warm solve at requested geometry. Today's cold 30-stage solve
costs 1.8 s (CDU17) to 3.1 s (TJL19); four heat rungs should add under 5 s. The
`V3ColdCoreBenchmarkWorker` gets one heat case so the cost is tracked.

## 7. Work packages and gates

| WP | Content | Gate | Estimate |
|---|---|---|---|
| 1 | Spec record, input list, expansion, problem array, residual term, digest, revisions, resolver validation, unit tests | all unit tests green; heat-free digests and results byte-identical | 1 day |
| 2 | Stripping sites, ramp step with heat, subdivision, three feasibility gates, global energy audit, duty ledger, calculator tests | calculator tests green; full suite green | 2 days |
| 3 | NBT v7, wire v7, ledger codec | round-trip tests green | 0.5 day |
| 4 | Heat tab, sign mapping, ledger display | manual in-game check on the default case | 1 day |
| 5 | DWSIM single-stage reference, three-pumparound CDU case, benchmark entry, milestone note | reference within stated tolerances; preset added | 1-2 days |

WP1 and WP3 can proceed in parallel; WP2 depends on WP1; WP4 on WP3; WP5 on WP2.

## 8. Risks and open decisions

- Condensation-capped trays are the expected failure mode for large single-tray duties. The
  uniform split is the default for that reason; the per-tray cap gate turns the failure into a
  typed message.
- Large cooling reduces overhead vapor and can flip the condenser branch (liquid-only versus
  two-phase). The existing `correctCondenserPhase` runs on every rung; a test with a heavy top
  pumparound checks the flip is handled.
- The static admission bound uses the feed composition as a stand-in for products at the
  condenser temperature. It is deliberately loose; it only stops obviously impossible inputs.
- Whether to expose positive duties (side heaters) in the GUI is deferred; the core accepts them.
- A parallel, uncommitted per-tray heat implementation exists on `codex/v3-literature-cdu`. This
  plan is main-based and self-contained; whichever lands first should absorb the other's tests
  rather than both being merged.

## 9. Out of scope

Circulating pumparound streams and their return-temperature specifications, side strippers,
exchanger models, and any heat-integration between pumparounds and feed preheat.

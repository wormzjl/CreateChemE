# Review: Solid phases, slurry transport, and in-line filtration plan

Reviewed 2026-09-18. Subject: `docs/solid-phase-fluid-system-plan.md` (draft, no implementation).
Code baseline: `4e84f0e` (main). Paths below are relative to `src/main/java/com/wormzjl/createcheme/`
unless they start with `src/` or `build.gradle`.

No code was run for this review. Every finding is a reading of the plan against the current fluid
code; findings that predict runtime behaviour are marked **predicted** and carry the code path the
prediction rests on.

## Verdict

The physical model is sound and the scope is well chosen (inert solids, all-or-nothing suspension,
100 % capture, no particle evolution). The formulas are correct as written. The plan is **not ready
to hand to an implementer** because five design decisions that the current solver architecture
forces are missing or contradictory:

1. three of the new closure rules are the first *discontinuous* flow laws in the network, and the
   adaptive step controller cannot accept a step that contains one (F1);
2. the plan does not say what the solid unknowns of the coupled Newton system are, and the literal
   reading (up to 64 populations per node) multiplies the finite-difference Jacobian cost (F2);
3. "include solid properties and transport settings in revision checks" contradicts "migrate
   existing saves": today those revision checks refuse the world on load (F3);
4. a reservoir holding dry particles or an immobile phase becomes hermetically sealed, including
   against pressure relief (F4);
5. conserved populations never dilute to zero, so a femtogram tail blocks lines forever and the
   64-population bound never recovers (F5).

Each has a concrete resolution below. None requires changing the physics the plan chose.

## What was checked and holds

| Plan statement | Check | Result |
|---|---|---|
| Krieger–Dougherty, φ_max 0.62, exponent 1.55 | 2.5 × 0.62 = 1.55 | Correct |
| Ferguson–Church constant 0.3 | 0.75 × C2 with C2 = 0.4 (smooth spheres), C1 = 18 | Correct |
| "existing volume-weighted convention" | `science/fluid/network/PassiveStepSolver.java:488-495` volume-weights hydrocarbon liquid, free water and vapor | Exists. Note the hydrocarbon liquid itself is log-mixed (`transport/MixtureViscosity.java:127`); the volume weighting is across phases only |
| "existing homogeneous pressure-loss calculation" | `network/PipeResistance.java`, one upwind ρ and μ per run (`PassiveNetwork.java:64-76`) | Exists |
| "solid specific enthalpy includes the pressure-volume term" | Inventories hold U (`PassiveNetwork.Inventory`), edges move H (`PassiveStepSolver.java:696-702`, `ConservativeTransport.java:178`) | Correct and necessary |
| "existing energy reference temperature" | `state/EnergyReference.java:29` sensible 298.15 K | Exists (see F13 for identity) |
| Pipes strand nothing when blocked | `PipeTransfer` is history, "never owned stock" | Holds; only reservoirs and the new filter own material |
| Strict packet fields instead of reusing phase-mole arrays | `network/FluidNetwork.java:54-60` enforces a 3 × components axis | Right call |
| Codec can default new fields for old saves | precedent: tree patching before `strictShape`, `runtime/fluid/FluidCheckpointCodec.java:120-128` | Mechanism exists, version gate does not (F3) |
| `fluidScienceTest`, `fluidRuntimeTest` | `build.gradle:267-268` | Exist |

Magnitudes for the shipped preset (water carrier, ρ_s 2500 kg/m³): 100 µm gives w = 7.3 mm/s and
v_dep = 0.073 m/s; 1 mm gives w = 0.174 m/s and v_dep = 1.74 m/s. Against a Durand-type estimate
for the default 0.05 m pipe (`runtime/fluid/FluidWorldAuthority.java:105`) the 1 mm value is
comparable and the 100 µm value is roughly ten times lower. The plan already labels this a gameplay
rule, so this is information, not a defect: with the preset the check binds only below 0.14 L/s.

## Must resolve before implementation

### F1. Discontinuous closures cannot pass the adaptive error controller (predicted)

Every device transition in the current solver is continuous in flow at the switching point: valves
open from zero flow, a head-limited pump closes at zero flow, boundary closure happens at a sign
change, and the velocity cap is a saturated law (`PassiveStepSolver.java:721-734`). The plan adds
three laws where flow jumps from a finite value to zero:

- deposition: flow drops from ρ·A·v_dep to 0;
- filter at L = 1: flow drops from Δp/(100·R_0·μ) to 0;
- the 100 Pa·s cutoff: small but finite laminar flow drops to 0.

The interval controller compares a full step with two half steps
(`network/PassiveIntervalSolver.java:96-104`). For any step that contains the switching time, the
full step reports either q or 0 and the half steps report (q, 0), so the relative pipe error is
about 0.5 regardless of step size. `flowError` scales by throughput, not by step
(`PassiveIntervalSolver.java:157-170`), so halving does not reduce it; a non-smooth regime also
removes the Richardson divisor (`:101`). The loop ends at `consecutiveRejects>=20`
("Substep refinement exhausted", `:118`) or, for light flows, only when the mass-scaled numerical
floor overtakes the jump after about 17 halvings, each costing three step solves.

The plan's own sentence "Locate the filling event within the integration interval" also has no
counterpart in the code: there is no event location, only reject-and-shrink.

Resolution, per closure:

- **Filter capacity: use a saturated law, not an event.** Limit the inlet flow so end-of-step
  retained volume cannot exceed capacity, exactly as the velocity cap limits flow today. The final
  step then carries whatever fills the filter exactly. The full step and the two half steps
  transfer the same total (the remaining capacity), so their mean flows agree and the error
  estimator stays quiet. Capacity is met exactly with no event search.
- **State-based refusals (immobile phase, φ ≥ φ_max, no liquid carrier, particle ≥ bore):** these
  depend only on the donor state, not on flow. Evaluate them on the *accepted* state at step start
  and express them as a direction-dependent closure, which is the existing
  `boundaryAllowed`/`boundaryClosed` mechanism (`PassiveStepSolver.java:119-121, 463-467`). Flow
  into the stuck reservoir stays allowed; flow out of it closes at a zero crossing, which is
  continuous. No persistent latch is needed because the flag is a pure function of accepted state.
- **Deposition velocity:** this one is genuinely discontinuous and needs a decision. The workable
  option is to decide it once per controller attempt from the accepted state plus an open probe,
  and hold that decision for the full step and both half steps, accepting an O(h) error in closure
  time. That lets one accepted step end slightly below v_dep, which conflicts with the plan's
  strict "every section, always" wording; the plan must choose which of the two it keeps.

Whatever is chosen, make the first work package a spike: two tanks draining through one slurry pipe
until the line blocks, asserting accepted/rejected substep counts. It fails fast if the prediction
above is right.

### F2. The plan does not define the solid unknowns; the literal reading is too expensive

"Extend coupled conservation equations … with particle balances" plus "64 distinct material/diameter
pairs per inventory" reads as up to 64 extra unknowns per node. The Jacobian is built by finite
differences one local column at a time, each with a full node decode and property evaluation
(`PassiveStepSolver.java:764-806`). Sixty-four extra columns per node would multiply the dominant
cost of every Newton pass, against a pool median that was just brought down to 3.3 ms.

Because v1 moves all populations together with the bulk (suspension is all-or-nothing, the filter
captures all of them), the hydraulics need only three scalars per node, each linear in the
population masses: total solid mass Σmᵢ, total solid volume Σmᵢ/ρᵢ, and total solid heat capacity
Σmᵢcᵢ. Carry those three as Newton unknowns. Reconstruct the individual populations afterwards in
`ConservativeTransport`, which already solves one linear upwind system per component with the flows
held fixed (`network/ConservativeTransport.java:117-147`); each population is one more right-hand
side against the same factorization. The lumped values and the per-population values are then
consistent by construction, because both follow the same linear operator.

Two details to write down with it:

- The transport closure test `|Σw − 1| ≤ 1e-8` (`ConservativeTransport.java:153`) must include the
  solid mass fractions, and `state.mass()`, `state.volume()` and `state.enthalpy()`, which every
  transport term reads, must mean slurry totals (see F6).
- v_dep takes a maximum over the populations *present* in the donor. Keep that in the outer check,
  never in a Newton residual. A future size-selective filter breaks the three-scalar reduction;
  say so as a known limit.

### F3. Revision checks versus save migration: the plan contradicts itself

The plan says both "Version the checkpoint format and migrate existing saves with empty solid
inventories" and "Include solid properties and transport settings in revision checks". Today a
revision mismatch is not an invalidation, it is a refusal to load:

- `FluidCheckpointCodec.java:130` rejects any envelope whose version is not exactly 1;
- `FluidCheckpointCodec.java:137` compares the saved `propertyRevision` with
  `ApproximationAnchor.thermodynamicRevision(model)` and throws "use a fresh development world";
- `runtime/fluid/FluidBasis.java:12,19` pins the fingerprint format to two 64-hex hashes and
  `requireCurrent` throws on any difference (`FluidWorldAuthority.java:57`).

If solid data is folded into either string, every existing world fails on first load after the
update, and later any server-config change (the v_dep multiplier, R_0, filter capacity, all declared
configurable) bricks the save again.

Resolution: keep `FluidBasis` and the thermodynamic revision untouched. Add a separate, optional
solids revision whose absence means "no solids" and loads cleanly. Split its uses: solid *material
data* gates inventories that actually contain that material; *transport settings* only invalidate
approximation anchors (which the plan disables for solid intervals anyway). Add a v1 reader next to
the v2 writer, following the tree-patching precedent at `FluidCheckpointCodec.java:120-128`. This
matches the standing direction that save migration be additive.

### F4. Dry particles or an immobile phase seal a reservoir completely

Outlets stay bulk-mixed in v1, a failed check closes the connection "to all requested transport",
and gas-only withdrawal is deferred. Consequences:

- a reservoir with particles and no liquid (the carrier evaporated, or a dusty gas tank) can never
  release gas, because particulate transport without liquid is refused;
- a reservoir whose hydrocarbon liquid is immobile blocks every outlet, including a
  `PressureValve`, so a sealed tank that keeps receiving material or pump work has no relief path
  and runs toward the model's pressure bound and a solver failure rather than a gameplay state;
- there is no heater in `runtime/fluid`, so "reversible when temperature changes" can only be
  reached by mixing in hot or light fluid through an inlet.

The plan needs a stated escape for each stuck state. The smallest one is to let a mixed outlet
degrade to gas-only when the donor has no mobile liquid, leaving solids and the immobile phase
behind. That is the deferred gas-only contract, so the alternative is to keep it deferred and
document the sealed tank as intended, with a player-facing way out (breaking the block already
exists for the filter; the reservoir has none defined for solids).

### F5. Conserved populations never reach zero

Flushing a slurry tank with clean liquid dilutes each population exponentially; the mass never
becomes exactly zero and the plan forbids discarding or merging. Then:

- a tank that once held 1 mm particles keeps requiring 1.74 m/s on every outlet forever, because
  v_dep is a maximum over populations present;
- the 64-population bound only ever fills, and once full every mixing inflow is refused for good;
- the bore check and the no-liquid check fire on the same residue.

The project already solves this for fluid components with a trace policy
(`science/thermo/TraceTruncationPolicy`). Define the analogue: below a stated solid volume fraction a
population stays in the conserved inventory and the audits but is ignored by deposition, bore,
carrier and population-count rules. State where sub-threshold mass goes if the count limit is
reached (the only conservative choice is to keep refusing, so the threshold must apply to the count
as well).

## Should resolve

### F6. Slurry density is not mentioned anywhere

The plan specifies slurry viscosity only. Density enters the friction law, the static head
(`PassiveStepSolver.java:719`), pump shaft work (`:705`, `ConservativeTransport.java:182`), the
pump's volume-flow target (`:737`), the velocity cap (`:436`) and the initial flow estimate
(`:428-433`), always as `state.mass()/state.volume()`. A 10 vol % slurry of the demo material is
15 % denser than water. State that these use slurry mass and slurry volume, and that the hydraulic
unknown remains total mass flow including solids.

### F7. Filter compilation details

- The mass flow differs across the filter. With the lumped unknowns of F2 the outlet flux is
  algebraic, ṁ_out = ṁ_in·(1 − w_s,donor), so no new unknown is needed, but the edge residual and
  both node accumulations must use different flows on the two sides.
- A run currently assumes one ρ and μ for all sections and merges equal cross-sections regardless
  of position (`PassiveNetwork.java:55-63`). Sections upstream of the filter carry slurry and need
  the deposition check; sections downstream carry filtrate. The run must split at the filter and
  must not coalesce across it, and which side is "upstream" flips with flow direction.
- "μ_in" is ambiguous. Cake and medium resistance scale with the *carrier* viscosity; using the
  Krieger–Dougherty value double-counts the solids that the L term already represents. Say which.
  Also define Q_filtrate when vapor is present (bulk volumetric flow of the mobile phases is the
  natural reading).
- Captured solids should be booked at donor enthalpy, m·(c·(T_d − T_ref) + P_d/ρ_s); say that the
  filter ledger stores that number so the audit closes.

### F8. Probing blocked connections: cost, order and uniqueness

"Probe an open candidate when reconsidering a blocked connection" means every step on an island
with a blocked line is solved at least twice (open, then closed), and the active set changes one
edge per pass (`PassiveStepSolver.java:119-141`), so k blocked lines cost k + 1 Newton passes per
stage. With parallel slurry branches the answer is not unique: both open may put each below v_dep
while either alone passes. Specify a deterministic rule (for example, close the lowest-velocity
failing edge first, by pipe id on ties, never reopen within a solve so the cycle key at `:87-88`
stays monotone), and a cheap gate before probing, such as the single-edge bisection estimate that
already exists at `:424-434`.

### F9. "Reject solid-bearing feeds before withdrawal" is not decidable before the interval

A module fixes its cycle from observations at t0 (`runtime/fluid/FixedSplitModule.java`, `begin`)
and then withdraws bulk mass over 100 to 600 ticks through `ScheduledTransfer.Withdrawal`, whose
composition is the coupled candidate's (`network/ScheduledTransfer.java:8-11`). Solids can reach the
buffer reservoir mid-cycle, inside the very interval that performs the withdrawal. Define the rule
on the accepted candidate instead: if the candidate's withdrawal would carry solids, the planner
retries with zero withdrawal for that interval, which the existing halving loop in
`ModuleTransferPlanner.plan` and the partial-receipt allowance (`owned ≤ targetKg`) already
tolerate. Also state what `MaterialParcel.split` does with solids (refuse), since it splits per
fluid component.

### F10. The 100 Pa·s cutoff is live for shipped crude data, and its wording is unclear

- `crude_pc10`, `pc11` and `pc12` share one liquid viscosity table that starts at 6.8 × 10⁴ Pa·s at
  293.15 K and crosses 100 Pa·s at about 334 K
  (`src/main/resources/data/createcheme/materials/properties/crude_pc12.json:37-43`). Residue-rich
  liquid below roughly 60 °C will become immobile. That is a behaviour change for clear-fluid
  worlds with no solids in them, so "clear-fluid viscosity is unchanged" is true but not sufficient;
  the regression fixtures need a scan for any liquid state above the cutoff.
- The same tables start at 293.15 K while devices accept 273.16 K
  (`runtime/fluid/FluidDeviceSpec.java:12`). Between those, heavy liquid raises
  `PROPERTY_UNAVAILABLE` (`MixtureViscosity.java:92-101`) instead of becoming immobile. The plan
  keeps that ("remains a property error"); it should say so knowingly, because cold residue is the
  one case where players will meet the cutoff.
- "mark that phase as immobile with effective particle size zero" reads as a particle that never
  settles, the opposite of immobile. If it is a placeholder for the later phase-outlet interface,
  say that; otherwise drop it.

### F11. "Keep the existing 22-component fluid basis" is stale

The basis is captured from the network package at runtime (`FluidBasis.capture`), bounded by
`MaterialAxis.MAX_CONSERVED_COMPONENTS`, and no fluid code holds a literal 22. Reword to "the
conserved fluid axis is unchanged; solids live on a separate axis", in line with the direction to
keep the framework open to new compounds.

## Minor

- **F12. Diameter as an identity key.** Matching populations by floating-point equality will split
  100 µm from 100.0000001 µm. Quantize the key (integer nanometres) at every entry point, including
  the generator rows.
- **F13. Inventory and reference invariants.** `PassiveNetwork.Inventory` rejects nonzero energy
  with zero moles (`PassiveNetwork.java:44`) and `MaterialParcel` does the same; `EnergyReference`
  identity is its component list, which parcels compare on merge. Decide whether solids join that
  identity or are declared sensible-only with no offset.
- **F14. Binding section.** With one bulk flow per run, "every pipe section" reduces to the
  largest-area section; `Pipe` has `minimumArea()` only.
- **F15. Filter life.** With the default 0.05 m pipe and the 10 vol % preset, 10 L of capacity fills
  in about 50 s at 1 m/s and about 12 min at the minimum transport flow. Check that against the
  intended play loop before fixing the defaults.
- **F16. Near-packing stiffness.** At φ = 0.60 the viscosity multiplier is about 200 and its slope is
  steep; a Newton trial point can land at φ ≥ 0.62. The residual must return a finite capped value
  there, not throw, or the block Jacobian falls back to the coloured sweep on every pass
  (`PassiveStepSolver.java:826-830`). Refusing transport somewhat below φ_max (0.55 to 0.58) avoids
  the region.
- **F17. No fallback for solid intervals.** State what the island does when a full solve misses its
  budget and the approximate path is disabled (presumably the same hold as an exhausted allowance).

## Acceptance tests to add

1. Substep accounting across each closure (F1): a drain that blocks, a filter that fills, a liquid
   cooled through the cutoff, each with a bound on rejected substeps.
2. Flush-out: a tank diluted below the presence threshold unblocks, and the population count
   recovers (F5).
3. A tank holding gas and dry solids, and a tank with an immobile phase behind a pressure valve
   (F4), with whichever behaviour the plan decides.
4. A version-1 checkpoint fixture loads with empty solids, and changing a solids config value does
   not refuse the world (F3).
5. Step cost with 1, 8 and 64 populations on the existing benchmark island (F2).
6. Two parallel slurry branches give the same blocked set on repeated runs and after reload (F8).
7. Solids arriving at a module feed buffer mid-cycle (F9).
8. A run with different diameters on both sides of a filter, in both flow directions (F7).

## Open questions for the plan author

1. Deposition: strict "never below v_dep" or step-boundary decisions with an O(h) closure-time error?
2. Sealed tanks (F4): intended, or should mixed outlets degrade to gas-only?
3. Presence threshold for populations (F5): what value, and does it also free a population slot?
4. Filter μ_in: carrier or slurry viscosity?
5. Is a size-selective filter planned soon enough to rule out the three-scalar reduction in F2?

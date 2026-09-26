# Solid phases, slurry transport, and in-line filtration

Status: Revised draft for review. User decisions recorded on 2026-09-22; no implementation changes are included.

## Review decisions

The user selected option A for all six follow-up questions:

| Question | Decision |
|---|---|
| Blockage timing | Stop at the threshold crossing, within numerical tolerance. Do not deliberately continue below the threshold until the next simulation step. |
| Gas escape | Keep mixed outlets blocked. Explicit gas-only outlets remain a later feature; no automatic gas-only fallback. |
| Microscopic residues | Below a configurable population volume fraction of **10⁻⁸**, ignore that population for blockage while conserving its mass and energy. |
| Filter viscosity | Use carrier-fluid viscosity for the filter medium; account for retained solids through the loading-dependent resistance. |
| Size-selective filtering | Capture all particles in v1. Use aggregate solid properties in the main flow calculation and preserve individual populations separately. |
| Population storage | Keep exact sizes and the **64-population limit**. Refuse additions that exceed it; do not merge different sizes or discard traces. |

The trace rule does **not** free population slots. A tank can become hydraulically clear after flushing while still having no room for another distinct population. This is an accepted consequence of retaining exact populations with bounded storage.

The review's solver-failure and performance predictions are hypotheses to verify with targeted tests, not measured failures. The implementation starts with the solver feasibility checks below.

## Summary

Add conserved particulate solids to the fluid network, with particle sizes carried through transport and mixing. Particles require a liquid carrier and sufficient pipeline velocity.

Liquid phases above **100 Pa·s** become immobile bulk solids for transport purposes. Filters capture particles internally, develop increasing resistance, and eventually stop flow when full.

Location-specific phase outlets remain a later feature; this addition prepares their transport interface.

## Material and phase model

- Add data-driven solid definitions containing stable material ID, density in kg/m³, and constant specific heat capacity in J/(kg·K). Solids are inert and incompressible in v1.
- Represent particles as immutable populations identified by **material ID + diameter**, with conserved mass. Mixing merges matching populations and preserves different diameters; do not average sizes. Normalize entry units using a canonical decimal diameter in metres, preserve that identity in saves and packets, and convert to floating point for physical calculations. Do not quantize distinct entered sizes into shared bins.
- Leave the conserved fluid axis unchanged and obtain it from the runtime material package. Solids live on a separate axis. Add solid inventories to thermodynamic states, reservoir inventories, material parcels, scheduled transfers, and transport histories; do not hard-code a fluid component count.
- Include solid mass, displaced volume, and sensible energy in reservoir balances. Solids have a separate, sensible-only energy reference at **298.15 K**, with zero formation-energy offset. Their specific internal energy is c × (T − 298.15 K), and specific enthalpy adds P / ρ. Parcel compatibility checks include solid material/reference identity; fluid-reference rebasing leaves solid energy unchanged.
- A solid-only inventory or parcel is valid. Require zero energy only when both fluid and solid inventories are empty. Account for the solid volume when determining the space available to fluid.
- Evaluate each liquid phase's viscosity before slurry thickening. **Above**, not at, 100 Pa·s, mark that phase as immobile. Effective particle size zero is a marker for this bulk-solid transport state, never an input to the settling correlation. Preserve its composition and thermodynamic properties without introducing freezing equilibrium or latent heat.
- Add phase-selection and mobility interfaces for mixed, organic-liquid, water, and gas withdrawal. Existing outlets remain mixed and block when they request an immobile phase. The future gas-only contract excludes particles and immobile liquid phases; it is not selected automatically in v1.
- A blocked donor does not automatically reject incoming mobile material. Hot or lighter incoming fluid may restore mobility; an outgoing pressure valve does not bypass the blockage. A tank can therefore remain unable to vent through its current mixed outlets. Preserve existing pressure-domain errors and explain the blockage in the UI rather than inventing a gas outlet or silently losing material.
- Make classification reversible when temperature or composition changes. Missing viscosity data remains a property error. The cutoff also affects existing residue-rich liquids without particles; heavy-component data below its minimum supported temperature must not be extrapolated or silently classified as solid.

## Slurry hydraulics and blockage

### Effective viscosity

Combine mobile liquid phases using the existing volume-weighted convention. With liquid-carrier viscosity μ_L and particle volume fraction φ = V_s / (V_L + V_s), use:

```text
μ_slurry = μ_L × (1 − φ / 0.62)^(−1.55)
```

This is the Krieger–Dougherty form with a maximum packing fraction of 0.62. At or above that fraction, refuse transport rather than evaluating the singular expression. For streams containing vapor, volume-average slurry and vapor viscosities for the existing homogeneous pressure-loss calculation. [Model reference](https://doc.comsol.com/6.4/doc/com.comsol.help.cfd/cfd_ug_fluidflow_multi.09.171.html)

The 100 Pa·s phase cutoff does **not** apply to this slurry-corrected viscosity.

Use slurry total mass and occupied volume for density, static head, pump work, volumetric pump targets, velocity limits, and initial flow estimates. The hydraulic flow variable remains **total mass flow including solids**. Carrier density and viscosity used in the settling calculation exclude particles and vapor.

Near the packing limit, use a finite numerical continuation for out-of-domain Newton trial points so a derivative evaluation does not throw at φ ≥ 0.62. This is only a solver aid: a flowing accepted state must satisfy the physical packing rule, and all accepted transport properties must be checked against the uncapped correlation. Do not lower the physical packing threshold to avoid numerical work.

### Minimum transport velocity

For each population, calculate settling speed using the Ferguson–Church equation:

```text
R_i = |ρ_i / ρ_L − 1|
ν_L = μ_L / ρ_L
w_i = R_i × g × d_i² / (18 × ν_L + sqrt(0.3 × R_i × g × d_i³))
v_dep = 10 × max_i(w_i) over populations active for blockage
```

Here d_i is particle diameter, ρ_i is particle density, ρ_L is liquid-carrier density, ν_L is liquid-carrier kinematic viscosity, and g is gravitational acceleration. Use SI units throughout.

The multiplier is configurable; applying the magnitude of density contrast also covers buoyant particles as a gameplay approximation. This is a simplified suspension rule, not a calibrated pipeline deposition correlation. [Settling equation](https://geoweb.uwyo.edu/geol5330/FergusonChurch_GrainSettling_JSR04.pdf)

- Require liquid superficial velocity Q_L / A > v_dep in **every pipe section**, using the actual upstream composition. Vapor contributes no suspension velocity. Within a homogeneous run, the largest-area section sets the suspension-speed constraint; the smallest bore sets the particle-fit constraint.
- Refuse non-trace particulate transport without liquid, at packing capacity, or when an active particle diameter reaches the pipe bore.
- Failed checks close the affected connection to all requested transport and display **“blocked with solid”**, with a reason and measured/required velocities. Other network branches continue operating.
- Integrate closure into the solver's outer active-set checks. Probe an open candidate when reconsidering a blocked connection; its committed zero flow must not prevent restart. Population-dependent maxima belong in the outer checks, not in the Newton residual.
- Apply the same checks during reversals and interval acceptance. Never clip transferred solids after solving or leave a newly failed check open for the remainder of a simulation step.
- Pumps retain their existing targets, pressure limits, and efficiency. They accept slurry without additional wear or solids limits.

### Trace populations and exact storage

- Define a population's presence fraction as its solid volume divided by the donor's total occupied mixture volume, including vapor. Below **10⁻⁸** it is inactive for deposition, bore-fit, and carrier-required checks; at the threshold it is active. Setting the configured threshold to zero disables this exception.
- Trace populations still contribute to conserved mass, volume, heat capacity, energy, density, and applicable slurry viscosity. They travel with the accepted bulk stream and are captured by filters. The exception does not apply to an immobile liquid phase.
- If there is no liquid and all particles are traces, permit the gas stream using gas viscosity while retaining particle mass and energy accounting. A non-trace population still blocks that mixed outlet.
- Keep trace populations in the exact inventory and count them against the 64-entry limit. Remove a record only when its conserved mass is actually zero. Refuse an operation that introduces a 65th distinct population, with no partial mutation or material loss.

### Strict transitions and deterministic reopening

- Add explicit threshold-event handling to the interval controller. Bracket and localize the first crossing within a candidate interval, advance the valid part, switch the transport regime, then integrate the remainder. Full-step and refined estimates must compare matching regimes instead of repeatedly shrinking across an unhandled jump.
- Cover deposition, liquid immobility, packing, loss of carrier, changes in active particle presence, and filter capacity. Reconstructed individual populations participate in event validation; a new coarse population arriving during an interval must not bypass the checks.
- Stop at the crossing within a declared numerical tolerance, preserving existing mass/energy error budgets. Do not substitute step-start-only decisions, endpoint-only validation, or a deliberately delayed closure. If localization cannot be completed within the work budget, hold the interval without committing partial transport.
- For parallel branches, resolve simultaneous failures deterministically: close the edge with the smallest suspension-speed ratio first, breaking ties by persistent edge ID. Use monotone closures within a fixed-regime solve; reconsider reopening when progressing to a new state or event. A cheap pressure/flow estimate may reject an impossible reopening, but cannot authorize it without the coupled feasibility check.
- The first implementation work package is a bounded solver spike for a draining slurry line, a filling filter, and a viscosity-threshold crossing. It must demonstrate accurate event timing and bounded solver work before the feature is expanded. Record and test the event tolerance; increasing rejection limits is not a substitute for event handling.

## Filter and gameplay

- Add a two-port, bidirectional filter. Capture 100% of incoming particles; preserve all mobile fluid components. Reverse flow also filters and never releases captured solids.
- Compile the filter as a dedicated transfer element with separate inlet/outlet fluxes and owned retained-solids inventory. It must not be collapsed into an ordinary pipe or junction.
- Split hydraulic runs at the filter. Upstream sections use slurry properties and downstream sections use filtrate properties; never coalesce sections across the filter. Reverse flow swaps these roles.
- Use the donor's solid mass fraction to obtain the two fluxes: |ṁ_out| = |ṁ_in| × (1 − w_s). Accumulate the difference in filter inventory and use the appropriate flux at each endpoint.
- Conserve captured population masses and carried energy. Book each captured population at donor specific enthalpy c × (T_d − 298.15 K) + P_d / ρ_s, plus the existing gravitational-energy datum. The filter ledger stores this carried energy; it is not a newly equilibrated reservoir internal energy. Downstream transport contains the remaining fluid and its corresponding energy. Retained solids have no separate thermal exchange model in v1.
- Apply deposition checks to connecting pipes; the filter medium itself captures particles without requiring suspension velocity.

Use retained solid volume to define loading L = V_retained / V_capacity. For L < 1:

```text
Δp = R_0 × (μ_carrier / (10^−3 Pa·s)) × (1 + 99 × L²) × Q_filtrate
```

μ_carrier is the viscosity of the mobile fluid after excluding particles, using the existing volume-weighted phase convention when multiple fluid phases coexist. Do not apply the slurry viscosity multiplier to the filter medium. Q_filtrate is the signed bulk volumetric flow of all passing fluid phases, including vapor, evaluated on the donor side; the signed pressure drop opposes flow.

Default capacity: **0.01 m³ of solid material**. Default clean resistance: **R_0 = 10^6 Pa·s/m³**. Both are server configuration settings.

- At L = 1, close the filter completely and display **“filter clogged”**. Locate the filling event within the integration interval and enforce remaining capacity inside the transfer equations so capacity cannot be exceeded. An endpoint flow cap alone is not proof that transition timing or the adaptive error estimate is correct.
- Add a **Recover solids** GUI action returning one non-stackable contents-bearing item. Preserve material IDs, diameters, masses, energy, and property revision. If the player cannot receive it, leave the filter unchanged.
- Recovery and filter removal must transfer ownership exactly once. Ordinary block breaking drops retained contents in the same item format.
- Display loading, captured populations, pressure drop, flow, and blockage reason.
- Ship an explicitly fictional inert demonstration material: density **2,500 kg/m³**, heat capacity **800 J/(kg·K)**.
- Extend generator controls with solids volume fraction and population rows containing material, diameter, and relative mass share. Supply a water preset with **10% solids by slurry volume and 100 μm particles**.
- Keep the proposed capacity as a v1 default, and report simulated time to clog in the gameplay fixture. Distinguish this result from a constant-flow estimate because growing resistance and deposition blockage may stop the line before the filter fills.
- Reintroducing recovered items into machines, automated extraction, reactions, dissolution, and particle-size evolution are outside v1.

## Integration and verification

### Runtime and compatibility

- Extend coupled conservation equations with three aggregate solid quantities per node: mass Σm_i, volume Σ(m_i / ρ_i), and heat capacity Σ(m_i c_i). These provide the solid contribution to density, occupied volume, and sensible energy without one nonlinear unknown per particle size.
- Reconstruct individual conserved populations using the accepted transfer operator, with multiple right-hand sides sharing a factorization. Include filter capture and scheduled boundary transfers in that operator. Audit that reconstructed populations reproduce all three aggregates and include solid mass fractions in the transport-continuity check. Cover both integration stages and endpoint checks.
- This reduction relies on particles sharing the same motion and the filter capturing every population. Size-selective separation is outside v1 and requires revisiting the transfer reduction before it is added.
- Keep canonical ownership in the world/island snapshots. Capture, recovery, and topology edits use the existing revision-checked commit mechanism.
- Preserve solids through buffering and parcel operations. Validate module withdrawals against the complete candidate interval, because solids can arrive after the module cycle began. If an unsupported module would receive any solids, retry with its withdrawal set to zero for that interval, retaining unrelated feasible transfers; do not commit the rejected withdrawal. `MaterialParcel.split` refuses solid-bearing parcels until a solid-splitting contract exists. Trace status does not exempt these compatibility checks.
- Bound exact populations to 64 distinct material/diameter pairs per inventory or parcel. Refuse an operation exceeding that limit without discarding or merging unlike populations.
- Add a version-2 checkpoint writer and an explicit version-1 reader/migration path that supplies empty solid inventories. Preserve the existing fluid-axis identity and fluid thermodynamic fingerprint; a world without solids must not become incompatible merely because solid definitions were added.
- Store solid material/reference revisions separately and validate the definitions actually used by saved solid inventories, parcels, and recovered items. Missing or changed used definitions require an explicit migration or restoration; unrelated solid definitions do not invalidate a world.
- Changes to transport settings invalidate solver state/anchors without refusing saved material. Preserve captured stock when filter settings change; if capacity is reduced below retained volume, mark the filter full and keep all material recoverable. Never truncate it to the new capacity.
- Extend packet validation and presentation with explicit solid fields rather than inserting mass-based particles into existing phase-mole arrays.
- Disable approximate fallback for intervals involving particles, immobile phases, filters, or candidate transitions into those states in v1. If the full solve misses its budget or cannot validate the interval, retain the last committed inventories and report the existing held/deferred status; do not commit partial transport. Ordinary physical blockage remains a valid committed zero-flow state.

### Acceptance tests

- Population mixing, supported parcel operations, displaced volume, thermal balance, and save/load preserve every material/size population. Solid-only parcels are valid, while unsupported component-only splitting refuses them without mutation.
- Clear-fluid viscosity is unchanged; scan existing heavy-crude fixtures for the intended new immobility cutoff. Slurry viscosity increases with loading and blocks at packing capacity; out-of-domain trial points stay numerically finite without permitting packed slurry transport.
- Test below, equal to, and above deposition velocity; mixed pipe diameters; reversal; no liquid carrier; and restart after increased driving pressure.
- Test viscosity classification below, at, and above 100 Pa·s. Mixed/liquid withdrawal blocks appropriately; the gas-only interface excludes immobile phases.
- Verify strict transition timing against a refined reference for deposition, filter filling, and liquid immobility, with declared bounds on rejected substeps and event-probe work. Repeat with a coarse population arriving mid-interval.
- Flushing below the trace threshold removes its blockage effect while preserving mass, energy, and occupied population slots. The 65th distinct population is still refused; equal canonical sizes merge, nearby different sizes do not.
- Verify the chosen sealed-outlet behavior for gas with dry non-trace solids and for an immobile phase behind a pressure valve. Incoming compatible fluid remains possible; no automatic gas-only fallback occurs.
- Verify bidirectional filtration, increasing resistance, exact capacity closure, recovery, full player inventory, and prevention of duplicate recovery.
- Test different pipe diameters on both sides of a filter in both directions, plus vapor-bearing filtrate. Check carrier viscosity, distinct inlet/outlet mass flows, and captured enthalpy accounting.
- Audit mass and energy across reservoirs, filters, boundary transfers, buffered parcels, and recovered items.
- Exercise reloads, chunk unload/reload, topology changes, legacy saves, and stale GUI actions.
- Load a version-1 fixture with empty solids, change transport configuration without refusing the world, and reduce filter capacity without losing captured contents. Reject incompatible used solid properties explicitly.
- Benchmark 1, 8, and 64 populations and verify that the main nonlinear unknown count does not grow with particle-size count. Verify reconstructed aggregates and exact population conservation.
- Repeated parallel-branch runs and reloads choose the same blocked connections. A full-solve budget expiry leaves the committed world unchanged.
- Solids arriving at an unsupported module's feed during its cycle result in zero withdrawal for the affected interval, without losing material or cancelling unrelated valid transfers.
- Run existing `fluidScienceTest`, `fluidRuntimeTest`, packet/material tests, and the fluid GameTest server, adding an end-to-end generator → pump → filter → reservoir scenario. Recheck clear-fluid benchmarks for regressions.

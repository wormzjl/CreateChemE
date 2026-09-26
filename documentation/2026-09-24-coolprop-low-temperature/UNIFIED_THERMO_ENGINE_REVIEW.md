# Unified thermodynamic engine assessment

Status: Concluded 2026-09-24 (architecture feasibility; no implementation).
Scope: combine the preceding fluid-data, solid-liquid and solid-gas work into one engine for CreateChemE. This assesses a design; it does not authorize or claim a production rewrite or universal material coverage.

## Verdict

Yes. One public engine can own a common material catalog, reference conventions, phase models, stability/equilibrium calculations, caloric properties and their derivatives. Its internals should support multiple qualified property packages rather than forcing one equation of state onto all fluids, solids and mixtures. Unified architecture is feasible; complete arbitrary-compound data coverage is not provided by that architecture.

A shared engine can handle vaporization/condensation, freezing/melting, sublimation/deposition, liquid-liquid separation and solid-solid transformations where the required candidate phases and data exist. Supercritical states must be supported without an artificial vapor/liquid boundary. Hydrates and solid solutions require their own phase models. Chemical reactions are optional and explicitly enabled; changing phases must not silently enable unrestricted chemical equilibrium.

## Suggested architecture

1. Versioned material/property catalog: identities, composition/stoichiometry, molecular masses, pure phase models, mixture interaction sets, reference enthalpy/entropy, supported crystal forms, validity bounds and experimental provenance/uncertainty. Phase-boundary measurements and latent heats constrain and validate the thermodynamic model; they are not unrelated thresholds that independently override it.
2. Phase evaluation: a common contract for vapor, liquid and solid models. Return chemical potentials, h, u, s, v and required derivatives, with phase identity and validity information. A model may be expressed in Helmholtz or Gibbs energy or an equivalent internally consistent set of properties. For a molar Gibbs model, s=-(dg/dT)_P,x, v=(dg/dP)_T,x, h=g+Ts, u=h-Pv; chemical potentials are derivatives of total G with respect to species amounts. Mixture composition derivatives matter.
3. Phase stability and equilibrium: determine which candidate phases are stable and solve their amounts/compositions under the requested constraints. Include absent-phase stability, not only equal fugacity among an assumed present set. Distinct liquid phases and multiple crystals must be representable.
4. State queries: TP, PH and UV formulations behind one interface. Reuse the same phase models in direct coupled network residuals, so the current network solver need not invoke a complete nested flash for every Newton evaluation.
5. Result: temperature, pressure, a list of phases with identities, compositions and amounts, aggregate energy/volume, derivatives where defined, model revision, domain status and diagnostic evidence. No single solid boolean or one fixed liquid slot as the canonical general representation.
6. Transport and finite-rate models: viscosity, conductivity, interfacial/surface properties, nucleation, growth and deposition morphology can share catalogs and phase results, but use separate validated models. Thermodynamic free energies do not determine viscosity or crystallization rates. A single user-facing engine can provide these services without conflating their physics.

## Common equilibrium foundation

For species able to exchange between coexisting phases, chemical potentials agree; absent candidate phases must not reduce the appropriate thermodynamic potential. At fixed T,P and conserved composition the equilibrium problem minimizes total Gibbs energy. At fixed U,V and composition, equilibrium maximizes entropy; this can be solved through compatible flash constraints or a coupled formulation. Minimizing Gibbs energy at arbitrary fixed U,V is not the correct general formulation.

For nonreacting phase changes conserve each chemical component across phases. If hydrates/compound solids are explicitly admitted, use their stoichiometry in a conserved component matrix. A later reactive package may conserve elements/charge and allow a specified set of reactions, but that is additional scope.

TP at a pure coexistence point does not uniquely fix phase fractions. A robust interface must request an additional constraint such as energy or quality, or return a coexistence/underdetermined result rather than an arbitrary split. Do not promise smooth derivatives across phase appearance, first-order transitions or critical singularities.

Latent heats follow from differences between compatible phase enthalpies. At the same T,P and reference convention, h_v-h_s = (h_v-h_l)+(h_l-h_s). At the pure triple point all three phase chemical potentials must agree. This prevents an independently fitted boiling/freezing/sublimation subsystem from introducing inconsistent transition loops.

Use common per-species reference conventions across phases. A common arbitrary shift for a conserved species can cancel in nonreacting equilibrium; independently shifting its solid, liquid and gas models does not. Reactive calculations impose additional reference consistency across species. Reference matching at one point alone does not establish derivative or phase-boundary consistency over an entire domain.

## Current CreateChemE reuse and gaps

Inspected checkout HEAD during the preceding assessment: f9d6be10de8f73a0a56ece3effe2cd572803b485. Current sources were read again for the interface assessment.

- `V3ThermoModel` already separates component basis, workspace, phase fugacity, molar enthalpy and TP flash. It is a useful existing boundary, currently hydrocarbon liquid/vapor focused with separate free-water handling.
- `V3PropertyPackage` already groups data revisions, components, binary interactions and validity. Extend the catalog/package concept rather than adding per-device tables.
- `HydrocarbonModel` and its translated/compressed liquid response provide reusable evaluations and derivatives, subject to qualification against the unified thermodynamic contract.
- `FluidThermodynamics.State` has explicit liquid/vapor and water slots plus inert SolidInventory. A general canonical state needs a list of chemically identified phases and cross-phase component conservation.
- `SolidMaterial` and aggregate solid moments have sensible heat but lack fusion/reference offsets and chemical potentials. Those moments are insufficient to represent equilibrium inventories of independently crystallizing species.
- `PhaseLayout` currently has fluid equilibrium equations and conserved solid transport moments. A unified engine requires solid stability, solid amounts and latent-energy coupling. Existing inert particulate transport can remain a distinct material class.
- The existing water enthalpy offset is a useful precedent for reference alignment, not evidence that solid chemical potentials and entropy references already agree.

Columns, tanks and heat exchangers can consume the same phase evaluator and caloric conventions while retaining device-specific constraints and solvers. Do not force solids through column algorithms whose equations only support vapor/liquid operation: such consumers must explicitly support the returned phases or reject the configuration. Preserving an internal caller boundary during a staged refactor is not a legacy-save migration.

## Data acquisition and performance

CoolProp, NIST/IAPWS and source literature can feed one catalog. Use CoolProp as an offline reference/data provider or a qualified backend; a native runtime dependency is optional and should be measured before adoption. Prefer a coherent reference model or consistent fitted potentials over unrelated interpolated Cp, h, density and phase-boundary tables. Any fitted/tabulated acceleration must preserve thermodynamic identities and declare interpolation/extrapolation limits.

Domain checks should distinguish supported phases and qualified mixture states from advertised pure-fluid limits. Mixtures can remain liquid below individual pure melting points; gas branches may also extend below liquid limits. Neither situation justifies removing validation. Report missing candidate-solid data as incomplete equilibrium coverage, not a proof that no solid can form.

For runtime cost: reuse the prior stable phase set and estimates, cache immutable package data, and reevaluate omitted-phase stability after relevant state changes. Keep fast qualified single-phase and vapor-liquid paths under the shared contract. Do not run an unrestricted global multi-phase search for every pipe tick. Scheduling stays driven by due simulation deadlines, dependency changes and worker completions, with online-tick simulation time and engine-scheduled presentation.

No measured performance estimate is available from this assessment. Stability searches and phase appearance are likely more expensive than local phase evaluation; benchmark representative component counts and near-transition cases before choosing latency budgets.

## Suggested rollout and evidence gates

A. Establish the shared contract and phase/data/reference representation using the existing vapor-liquid package. Verify property identities and current required gates; a common API alone is not a science validation.
B. Add one qualified pure solid model and CO2-in-nitrogen gas-solid onset and finite-inventory equilibrium. Verify reversible transfer, phase disappearance, conservation and experimental boundaries.
C. Add consistent liquid competition and pure triple-point checks, then methane/ethane solid-liquid onset and quantities, including validated sub-pure-melting liquid states.
D. Expand only with data to variable air mixtures, solid solutions, ammonia hydrate systems, or characterized wax. Add transport/morphology as separate tested capabilities.

Testing should include chemical inventory and energy/volume conservation, reference and derivative consistency, phase stability, coexistence degeneracy, all transition directions, independent experimental boundaries and out-of-domain refusal. Changed state/checkpoint formats use fresh worlds and must round-trip clocks, material, pending events and optimization state; no migration or old-world gates. Integration work preserves one Gradle invocation at a time and dev-client verification for any GUI change.

## Sources and verification

- Cantera demonstrates the modular separation of phase thermodynamics and multiphase equilibrium: https://www.cantera.org/stable/python/thermo.html . This is an architectural precedent, not a recommendation to adopt it as a complete cryogenic/wax database.
- NIST/TDE describes consistency constraints across vapor/sublimation pressures, fusion/vaporization/sublimation enthalpies and heat capacities: https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=927746 . These directly motivate shared reference and consistency checks.
- CoolProp phase-state property interface: https://coolprop.org/_static/doxygen/html/class_cool_prop_1_1_abstract_state.html . Multiple backends and extensive fluid properties do not establish universal solid equilibrium coverage.
- Companion reviews in this batch contain the material-specific data evidence and limitations.

Sources checked 2026-09-24. No product source changes, prototype, Gradle invocation, dev-client run or numerical performance benchmark. Only documentation and the batch index changed. No tooling was created or detached.

Supercritical follow-up (2026-09-24): see [SUPERCRITICAL_SUPPORT_REVIEW.md](SUPERCRITICAL_SUPPORT_REVIEW.md) for fluid-state classification, mixture stability, critical-region qualification and the current liquid/vapor-path limitations.

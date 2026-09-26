# Mixture freezing feasibility review

Status: Concluded 2026-09-24 (research and read-only code assessment; no implementation).
Scope: feasibility of equilibrium freezing of air components, ammonia and hydrocarbon mixtures in CreateChemE. Pressure and desired accuracy are not specified, so conclusions are scoped and no universal operating envelope is claimed.
Checkout inspected: f9d6be10de8f73a0a56ece3effe2cd572803b485. Findings describe the files read in this checkout, not other worktrees or unmerged work.

## Decision

Feasible in stages for selected mixtures with qualified data. CoolProp can supply fluid properties, pure-fluid melting curves and useful comparisons, but does not supply the complete arbitrary-mixture solid-equilibrium model and solid caloric data required here. A reliable general freezing feature is a thermodynamics and solver batch, not a material-table update.

Distinguish three deliverables: (1) onset of first solid at a given pressure and fluid composition (liquidus), (2) equilibrium amounts and compositions of solid/liquid/vapor as cooling proceeds, (3) nucleation, crystal size, deposition and blockage. These have progressively different data and implementation needs. SLE alone cannot predict crystal size or supercooling kinetics.

## Feasibility by family

| Scope | Assessment | Conditions |
|---|---|---|
| Fixed-composition standard dry air, onset only | High feasibility as an explicitly estimated correlation | CoolProp Air has a freezing-line correlation; source describes an estimate. Does not predict solid composition, latent heat or fractionated air. |
| Variable N2/O2/Ar composition | Feasible, substantial qualification | Fit/validate binary boundaries and test ternary mixtures; choose solid phase topology from evidence, allowing solid solutions when needed. |
| Selected light-hydrocarbon binaries | Best first general SLE demonstration | Methane/ethane has published eutectic measurements; begin with separate pure crystals only where justified. |
| Pure ammonia | Feasible with external solid data | No HEOS melting curve in the prior runtime survey; obtain fusion/solid properties from evaluated literature. |
| Ammonia mixtures | Separate mixture-specific project | Partner matters. Ammonia/water requires nonideal liquid behavior and hydrate phases over relevant composition ranges. |
| Defined paraffin/wax mixtures | Feasible with a dedicated data/model package | Literature has multi-solid and solid-solution approaches; choose and validate against measured onset and solid fractions. |
| Current crude pseudocomponents | Not defensible with current data alone | Broad boiling cuts do not identify crystallizable n-paraffin distributions or fusion properties. Need wax characterization and experiments. |

The standard dry-air exception narrows the earlier statement that every mixture task requires building a general SLE solver: a validated or explicitly estimated fixed-composition liquidus correlation can answer an onset-only question. It cannot answer general mixture freezing.

## Evidence and central temperature-domain problem

Lemmon et al. (2000), section 3.2, explicitly describe the standard-air freezing line as estimated from component melting curves because mixture experimental information was lacking. Its reference solidification temperature is 59.75 K along the bubble line, not a universal atmospheric freezing temperature [1].

A 2022 methane/ethane experiment reported its lowest measured freezing temperature as 73.88 K at methane mole fraction 0.680. The fitted modified SRK model predicted 72.71 K at 0.668 [2]. Both pure-fluid CoolProp Tmin values in the preceding survey are approximately 90 K. Consequently, imposing every pure component's Tmin on a mixture would reject physically relevant liquid mixtures. Mixture-stabilized liquid states require qualified liquid chemical potentials, including hypothetical/metastable pure-liquid reference states where the formulation uses them. Simply disabling checks or forcing a CoolProp liquid phase is not validation. The paper's SRK binary coefficient must not be copied into PR78 without refitting and checking thermodynamic consistency.

Published work addresses N2/O2 and N2/Ar solid phase diagrams with models allowing different solid miscibility behavior [3]. A 2025 experimental N2/Ar study also provides a concrete recent binary validation source [4]; full tables and parameters were not obtained in this review. Binary fits alone do not certify an arbitrary ternary model.

Ammonia/water has multiple stoichiometric hydrate solids [5]. A model containing only pure water ice and pure ammonia crystals will miss parts of its phase diagram. Existing liquid/vapor thermodynamic formulations do not by themselves supply these solids.

## Minimum model

For a first model assuming separate pure crystals and a single liquid, solve equality of chemical potentials for each present crystal species. A screening approximation neglecting pressure corrections, heat-capacity differences and solid transitions is:

ln(x_i * gamma_i) = -(DeltaH_fus,i / R) * (1/T - 1/T_m,i).

Here x_i is the LIQUID composition (not the overall feed when vapor exists), gamma_i is referenced to the pure liquid, DeltaH_fus is molar, and T_m is the pure melting temperature at the reference pressure. Ideal-liquid gamma=1 is only an explicitly qualified first approximation. A fugacity formulation can use the existing EOS more naturally than adding NRTL, provided the solid reference is consistent with that same fluid model. NRTL is not mandatory and is not a universal cure.

At onset, inspect every candidate solid and locate the first stability crossing while following the fluid equilibrium on cooling. For finite solids enforce component conservation, nonnegative phase amounts, and absence-phase stability. For separate pure crystals, n_s,i >= 0, D_i = mu_s,i - mu_l,i >= 0 and n_s,i*D_i = 0 express this condition. Solid solutions require composition-dependent chemical potentials and phase stability searches, not independent pure-solid tests. Include vapor-solid deposition where liquid is absent rather than applying a liquid-only equation to a gas.

Required solid data: chemical identity/stoichiometry, reference melting temperature AND pressure, fusion enthalpy, solid and liquid heat capacities (or a consistent chemical-potential correlation), solid volume/density, relevant solid-solid transitions, and uncertainty/domain/provenance. Pressure corrections require solid-liquid volume differences. Melting temperature alone does not determine latent heat or solid chemical potential away from coexistence.

## Current repository gap

- `science/material/SolidMaterial.java`: inert incompressible solid with density and constant heat capacity; zero sensible-energy reference at 298.15 K and no fusion/reference offset, entropy or chemical identity link to a fluid component.
- `science/fluid/state/SolidInventory.java`: material/particle populations plus aggregate mass, volume and heat capacity. These support inert transport, but the aggregate moments cannot identify individual crystallizing chemical species or their distinct reference energy offsets.
- `science/fluid/solver/PhaseLayout.java`: solid rows conserve three transport moments; the equilibrium residual contains fluid liquid/vapor and water saturation equations, not solid chemical equilibrium or fluid-to-solid conversion.
- `science/fluid/thermo/HydrocarbonModel.java`: fluid fugacity calculations and derivatives are reusable foundations. Must verify the new solid model against the actual translated/compressed liquid model, not an unrelated EOS.
- `science/fluid/thermo/FluidDomain.java`: explicit component and package validity checks. Need qualified mixture/reference-state domains; retain rejection of unsupported states.
- Bundled methane and ethane property JSON records have fluid-domain floors of 293.15 K, so this checkout needs cryogenic caloric and transport qualification even before reaching the approximately 90 K pure melting region. Nitrogen has a dedicated low-temperature segment and a 63.151 K floor. Existing nitrogen validation does not qualify all air components or mixtures.
- `MATERIALS.md`: PR78 is implemented; NRTL parameter loading exists but NRTL calculation is unavailable. Crude wax characterization is deferred.

For physical freezing, add coupled chemical inventories across all phases, consistent latent/reference energy, phase birth/disappearance and remelting. A tank requires conservation of internal energy and volume while T/P and phase amounts change; it cannot freeze a calculated fraction then repair temperature afterward. Equilibrium crystal amount also cannot set particle sizes for the current mobility model without an explicit morphology assumption or kinetic model.

## Recommended sequence and decision gates

1. Data/standalone onset study: methane/ethane first, plus fixed dry-air correlation as a separately labelled shortcut. Obtain fusion/solid data, full binary experimental tables and a bounded pressure range. Qualify low-temperature fluid chemical potentials and caloric data. Keep study tooling under tools/, datasets under research/, indexed if created.
2. Compare ideal-liquid screening with an EOS-based SLE model. Validate pure endpoints, composition-dependent liquidus and eutectic location using independent data; do not score fitted points as independent validation. Set numeric error targets after assessing experimental uncertainty; sub-kelvin general accuracy is not established here.
3. Only after successful onset validation: standalone equilibrium phase-fraction and energy/volume solver, including cooling/remelting, latent heat, nonnegative phase amounts, solid stability and vapor coupling. Verify conservation separately from experimental thermodynamic accuracy.
4. Integrate with existing deadline/dependency/worker scheduling, fresh-world persistence and engine-owned presentation. No process-state calculations on Minecraft ticks; online tick time only. Test clocks, pending events and optimization-state round trips for changed formats, without migrations or legacy-world gates.
5. Expand to air binaries/ternary, then ammonia-specific systems or characterized wax as separate qualified packages. Do not promise arbitrary combinations or crystalline phases without data.

Current result: go for a bounded onset feasibility prototype; defer a general production freezing solver until those data and validation gates pass. Algorithmic cost is plausibly manageable for small component sets, but this review ran no solver performance benchmark and makes no latency promise.

## Sources and verification limits

[1] Lemmon et al., Thermodynamic Properties of Air and Mixtures of Nitrogen, Argon, and Oxygen (2000), especially section 3.2: https://trc.nist.gov/refprop/Documents/Air.pdf
[2] Experimental measurement and model prediction of solid-liquid equilibrium for methane-ethane binary system (2022): https://doi.org/10.1016/j.jct.2022.106829
[3] Solid-liquid equilibrium prediction for binary mixtures of Ar, O2, N2, Kr, Xe, and CH4 using the LJ-SLV-EoS (2014): https://www.sciencedirect.com/science/article/pii/S0378381214004051
[4] Investigation on Solid-Liquid Equilibrium for Nitrogen + Argon and Nitrogen + Tetrafluoromethane Binary Systems (2025): https://doi.org/10.1021/acs.jced.5c00213
[5] Fortes, experimental/theoretical ammonia hydrate research and phase diagram: https://discovery.ucl.ac.uk/83315/1/83315.pdf
[6] Lira-Galeana et al., Thermodynamics of wax precipitation in petroleum mixtures (1996): https://doi.org/10.1002/aic.690420120
[7] CoolProp mixture documentation: https://coolprop.org/fluid_properties/Mixtures.html

Sources checked 2026-09-24. Some publisher full-text pages were inaccessible; conclusions use accessible abstracts/excerpts and explicitly do not claim extracted full experimental datasets or fitted parameters. Code was inspected read-only. No product change, numerical SLE prototype, Gradle suite, GUI run or benchmark was performed. No tools were detached or created.

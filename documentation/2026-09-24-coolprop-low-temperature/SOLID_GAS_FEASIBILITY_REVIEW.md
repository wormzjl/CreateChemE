# Solid-gas transition feasibility

Status: Concluded 2026-09-24 (research and API inspection; no implementation).
Scope: sublimation (solid to gas), deposition (gas to solid), and possible integration with the preceding mixture-freezing proposal.

## Decision

Feasible. For a dilute gas depositing a separate pure solid, equilibrium onset is generally a simpler first prototype than general liquid-mixture freezing. This is an engineering assessment for that restricted case, not a claim that all solid-gas mixtures are simpler. Solid solutions, hydrates, multiple crystal forms, high pressure and competing liquid phases restore much of the general phase-equilibrium complexity.

Recommend a bounded CO2-in-nitrogen deposition/sublimation study, with water frost as another strong reference case. This adds a parallel starting option to the methane/ethane liquidus study, rather than validating or replacing it. Use one consistent solid thermodynamic model for subsequent solid-liquid and solid-gas transitions.

## Equilibrium condition and scope

For species i forming a pure solid, equilibrium is mu_i^gas(T,P,y) = mu_i^solid(T,P). For a low-density ideal gas with negligible solid-pressure correction this reduces to:

  y_i P = p_sub,i(T)

where y_i is gas mole fraction, P is TOTAL gas pressure and p_sub is equilibrium vapor pressure over the specified solid. Above this partial-pressure threshold deposition is thermodynamically favored; below it existing solid tends to sublime. Equality defines the frost/deposition point for the specified composition and pressure. This does not set a nucleation rate or guarantee immediate deposition.

For a nonideal gas use f_i^gas = y_i phi_i P and a consistent solid fugacity. A pure-solid reference can be anchored to the pure saturated vapor at p_sub(T) and corrected to the mixture pressure:

  f_i^solid(T,P) = phi_i^pure,v(T,p_sub) p_sub * exp[integral(p_sub to P) v_i^solid(T,p) dp / (R T)].

This expression assumes the relevant pure crystal phase and a gas fugacity convention consistent on both sides. It does not cover solid solutions without an activity model. Total pressure still matters through gas nonideality, solid pressure corrections and competing phase stability; partial pressure is not an exact general replacement for pressure.

Do not apply a rule that total pressure must be below the pure species triple pressure for deposition in a mixture. Frost can form in a carrier gas above that total pressure. Conversely, a pure-fluid cooling path above its triple pressure can encounter condensation/freezing rather than direct deposition. Compare all supported phases.

## Data and sources

| Family | Available evidence | Remaining qualification |
|---|---|---|
| Water/ice | IAPWS sublimation-pressure correlation, stated 50..273.16 K range [1]; CoolProp humid-air model incorporates saturation over ice [2] | Solid energy/heat capacity and the actual humid-gas model range; pure correlation range is not the humid-air module range |
| Carbon dioxide | NIST WebBook solid vapor-pressure fit over 154.26..195.89 K and sublimation-enthalpy entries [3]; broader reference formulations [4] | Adopt a stated domain and compatible solid caloric model; do not extend the short Antoine fit outside its range |
| Nitrogen, oxygen, argon, neon, krypton, xenon | Published experimental sublimation enthalpies for quenched films [5] | Obtain vapor-pressure curves and equilibrium crystal data over the chosen domain; assess film morphology and solid-solid transitions before adoption |
| Ammonia | NIST references sublimation enthalpy over 177..195 K and an original solid/liquid calorimetry study [6] | Obtain pressure correlation and solid caloric data; hydrate/reactive mixtures require additional models |
| Hydrocarbons | Compound-specific literature needed | No blanket data coverage claim; broad crude pseudocomponents cannot be assigned a unique sublimation curve from boiling ranges alone |

A single sublimation enthalpy is insufficient to determine a pressure curve: a reference pressure/temperature is also needed, and heat-capacity differences and phase transitions matter across wide temperature intervals. An approximate constant-latent-heat Clausius-Clapeyron expression can be used only over a qualified narrow interval. Obtain solid density, heat capacity and reference energy for finite-inventory evolution. At a common temperature, pressure and reference convention, h_v-h_s = (h_v-h_l)+(h_l-h_s); values tabulated at different temperatures cannot simply be added.

## CoolProp capability check

Executed introspection against the temporary CoolProp 8.0.0 Python installation. AbstractState exposed has_melting_line and melting_line but no method containing sublimation or solid; the CoolProp.CoolProp module exposed no function name containing subl. This is a check of that public Python API, not proof that every backend or internal routine lacks sublimation information.

The documented humid-air module explicitly uses saturation pressure over water or ice and ice properties [2], providing a useful special-purpose comparator. Generic PropsSI calls with Q=0/1 describe liquid-vapor saturation [7], not a generic solid-vapor boundary. Do not extrapolate them below the triple point as a sublimation curve. Fluid EOS evaluation below advertised pure-fluid Tmin requires explicit validation of the gas branch in the chosen domain.

## CreateChemE integration

The prior read-only audit found gas fugacity calculations in HydrocarbonModel, inert solid inventories, and no fluid-to-solid chemical-equilibrium rows. The same gaps apply here: identify each solid with conserved chemical species, add solid chemical potentials and compatible energy offsets, add solid phase birth/disappearance and inventory transfer, and qualify low-temperature gas/transport data. Reuse the existing gas model only where validated.

For finite deposition, solve gas composition and pressure after removing material into the solid; do not hold initial partial pressures fixed. At fixed vessel volume and total internal energy, temperature changes with released deposition heat (and absorbed sublimation heat). Solid volume must participate in volume closure. At fixed imposed temperature/pressure the external heat and material exchanges are different constraints. At equilibrium a present pure solid pins its vapor fugacity; if it is exhausted, gas may remain undersaturated.

A unified solid model prevents contradictions between melting and sublimation curves around the triple point. Pure triple-point consistency requires solid, liquid and vapor chemical potentials to agree. A liquid phase must be allowed when stable, rather than forcing a gas-solid calculation everywhere.

Equilibrium predicts onset and final stable phase quantities. It does not predict how fast frost accumulates, its porosity, whether it coats a wall or forms suspended particles, or when a pipe blocks. Those need heat/mass transfer, surface temperature and morphology rules. Do not map all newly formed solid automatically to the current inert-particle mobility model without an explicit physical assumption.

## Suggested validation sequence

1. Offline bounded onset calculation using a published p_sub curve and initially ideal carrier gas; use f_i where gas nonideality is significant. Check pure-fluid and dilute limits, units, composition dependence and out-of-domain refusal.
2. Equilibrium finite inventory with reversible deposition/sublimation; check species, volume and energy conservation, latent heat direction and complete solid exhaustion. Compare independently measured phase boundary data rather than only reproducing the fitted input curve.
3. Add liquid competition and solid-liquid consistency before broad operating ranges; reject unsupported crystal phases/compositions. No claim of quantified accuracy without independent data.
4. Integrate on the engine's deadline/dependency/worker schedule, with online ticks and engine-owned presentation. Changed formats are tested on fresh worlds with required state round trips, not legacy migrations. Surface growth/blockage is a separate model and validation scope.

No solver prototype, state-grid calculation, Gradle suite, GUI run or performance benchmark was performed. No source files were changed and no tooling was created or detached. Only documentation and the batch index were updated.

## References

[1] IAPWS, Revised Release on the Pressure along the Melting and Sublimation Curves of Ordinary Water Substance (2011): https://mail.iapws.org/relguide/MeltSub2011.pdf
[2] CoolProp humid-air documentation: https://coolprop.org/fluid_properties/HumidAir.html
[3] NIST WebBook, carbon dioxide phase-change data: https://webbook.nist.gov/cgi/cbook.cgi?ID=C124389&Mask=224
[4] Span and Wagner, carbon-dioxide reference formulation: https://www.nist.gov/system/files/documents/srd/jpcrd516.pdf
[5] NIST, Precise Measurement of Enthalpy of Sublimation of Ne, N2, O2, Ar, CO2, Kr, Xe, and H2O using an Internally Consistent Sensing Platform (2017): https://www.nist.gov/publications/precise-measurement-enthalpy-sublimation-ne-n2-o2-ar-co2-kr-xe-and-h2o-using-internally
[6] NIST WebBook, ammonia: https://webbook.nist.gov/cgi/cbook.cgi?ID=C7664417&Mask=26
[7] CoolProp high-level interface: https://coolprop.org/coolprop/HighLevelAPI.html
[8] NIST frost-point chemical-potential formulation: https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=921756

Sources checked 2026-09-24. Literature identification is not a completed data qualification or fit.

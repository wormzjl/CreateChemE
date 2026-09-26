# Thermodynamic software comparison

Status: Concluded 2026-09-24 (official-documentation survey; no software benchmark or procurement).
Scope: products comparable to the proposed CreateChemE engine for fluid/solid equilibrium, caloric properties and supercritical states. Findings below distinguish documented capabilities from suitability judgments. No product has been demonstrated here to cover every requested air/ammonia/hydrocarbon mixture and transition.

## Commercial systems

| Product | Documented scope | Assessment for this project |
|---|---|---|
| Aspen Plus / Aspen Properties | Broad process simulation and property packages; solid, liquid, gas and electrolyte systems; crystallization models | Closest broad chemical-process platform. Exact cryogenic freezing/sublimation coverage depends on package, components and solid data; handling solid particles is not alone evidence of predicting their formation. |
| KBC Multiflash | Integrated fluid PVT and phase behavior, hydrate/wax/asphaltene/scale models, Python and integration interfaces | Particularly close to the hydrocarbon fluid-plus-solids engine concept. Validate pure cryogenic crystals and sublimation separately from wax/hydrate capabilities. |
| OLI Studio / OLI Engine interfaces | Electrolyte/non-electrolyte chemistry, vapor/aqueous/organic phases and multiple precipitated solids; API equilibrium outputs | Strong candidate for aqueous chemistry, salts, scaling and chemical speciation. Not evidence of complete low-temperature N2/O2/Ar or hydrocarbon-crystal coverage. |
| FactSage | Gibbs-energy minimization with compound and solution phases; gases, liquids, pure solids, solid solutions, slags and alloys | Strong example of genuinely broad multiphase thermochemistry. Target databases and pressure-dependent fluid models must match the problem; do not assume refinery/cryogenic fluid accuracy from metallurgical phase coverage. |
| Thermo-Calc | Thermodynamic phase equilibria, liquidus/solidus, solidification paths, latent heat and material-property models; SDKs and specialized databases | Especially relevant for crystal phases, alloys and solid solutions. Less direct fit for the initial hydrocarbon/cryogenic-fluid focus. |
| NIST REFPROP | Evaluated liquid, gas and supercritical fluid thermodynamic/transport properties and mixture calculations | Strong fluid-property reference. Official documentation explicitly says the program does not know the solid-liquid interface for a mixture; not the universal solid-fluid equilibrium solution. |

## Open-source/library systems

| Product | Documented scope | Assessment for this project |
|---|---|---|
| NeqSim | Java fluid-property/process library with SRK/PR/CPA and other models; flashes, phase envelopes, transport properties; documented solid, hydrate and wax phase classes | First integration candidate to investigate because CreateChemE is Java and the subject matter overlaps. Its supported classes do not prove validated cryogenic solid caloric data, general sublimation, all solid solutions or acceptable network runtime performance. |
| DWSIM | Open-source process simulator with property packages and flash algorithms, some supporting additional liquids/solids; standalone .NET thermodynamic library | Useful architecture/reference comparator. A Java integration needs a suitable bridge or isolated service. Historical DTL packaging information must be checked against the actual release before adoption. |
| Cantera | Modular phase models, multiphase equilibrium and chemical kinetics | Useful architecture and equilibrium-solver reference; not a ready-made complete cryogenic/hydrocarbon-solids database. |
| Reaktoro | C++/Python reactive-system framework for equilibrium and kinetics, including water/gas/rock applications | Relevant to future aqueous/mineral chemistry and precipitation. Data/model suitability for this project's cryogenic focus remains unestablished. |

## Suggested shortlist

This is a recommendation for evaluation, not an adoption decision:

1. NeqSim for possible Java reuse and implementation comparison.
2. Multiflash and Aspen Properties/Plus as industrial fluid/process comparators; obtain demonstrations for the actual solid-forming species rather than generic product feature lists.
3. REFPROP/CoolProp as independently qualified fluid-property references.
4. FactSage or Thermo-Calc if scope expands to inorganic solids, alloys and detailed crystal equilibria; OLI or Reaktoro for aqueous reactive chemistry.

The proposed universal interface is common in mature software, but its scientific coverage is delivered through curated property packages and databases. A phase name being present in an API does not establish coverage or accuracy for every material. Supercritical fluid behavior is an EOS capability; a separate fourth phase label is not the relevant procurement criterion.

## Evaluation cases before reuse

Check a small, explicit matrix: nitrogen/oxygen/argon fluid properties and mixture phase stability; methane/ethane freezing onset below pure melting temperatures; CO2 sublimation/deposition with a carrier gas; water ice and ammonia solid data; liquid-vapor continuity around a qualified critical region; finite solid inventories with consistent energy and volume; deterministic repeated calls and representative runtime cost. Inspect behavior at missing data and validity boundaries. Request sources and uncertainty evidence rather than treating mutual agreement between related EOS implementations as independent validation.

Direct in-mod use, offline reference calculations, generated data redistribution and custom model development are different deployment choices. No prices, contractual permissions or redistribution rights were assessed; no purchase or installation is proposed by this survey. Published API existence does not establish an embeddable redistribution arrangement.

## Primary sources checked 2026-09-24

- Aspen Properties: https://www.aspentech.com/en/products/engineering/aspen-properties
- Aspen Plus: https://home.aspentech.com/en/products/engineering/aspen-plus
- Aspen solids/electrolyte coverage: https://home.aspentech.com/en/products/pages/aspen-plus-dynamics
- Multiflash: https://www.kbc.global/process-optimization/technology/simulation-software/multiflash-simulation-software/
- Multiflash hydrate model: https://www.kbc.global/resources/whitepapers/gas-hydrates-modeling-with-cpa/
- OLI Studio: https://olisystems.com/software/oli-studio/
- OLI API outputs: https://devdocs.olisystems.com/stream-output-json
- FactSage Equilib: https://www.factsage.com/fs_equilib.php
- Thermo-Calc: https://thermocalc.com/products/thermo-calc/
- REFPROP overview: https://www.nist.gov/programs-projects/reference-fluid-thermodynamic-and-transport-properties-database-refprop
- REFPROP limitations/FAQ: https://pages.nist.gov/REFPROP-docs/
- NeqSim official repository: https://github.com/equinor/neqsim
- NeqSim phase documentation: https://equinor.github.io/neqsim/thermo/phase/README.html
- DWSIM flash algorithms: https://dwsim.org/docs/crossplatform/help/running.htm
- DWSIM standalone library: https://dwsim.org/wiki/index.php?title=DTL
- Cantera: https://www.cantera.org/stable/python/thermo.html
- Reaktoro: https://reaktoro.org/

No product source changes, installations, paid access, numerical campaigns, Gradle checks or dev-client runs. No tools created or detached. Only this review and the batch index were updated.

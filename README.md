# CreateChemE

## Intent

CreateChemE is a Minecraft 1.21.1 NeoForge addon for Create that aims to turn chemical and petroleum processing into playable factory systems. Its scientific model is intended to be lightweight enough for real-time game simulation while retaining consistent component, mass, element, phase, and energy balances.

The planned material system combines real chemical species with petroleum pseudocomponents, phase equilibrium, heat transfer, fluid transport, reaction kinetics, catalyst state, and conservative coupling between reaction and separation processes. Public thermodynamic and process data will provide the scientific basis, with clearly identified gameplay approximations where industrial data are unavailable.

Planned equipment includes storage drums, pumps, compressors, heat exchangers, boilers, furnaces, generic reactors, gas-liquid separators, three-phase separators, air coolers, distillation columns, pressure-swing adsorption units, and stirred-tank reactors. Create will provide the physical factory and power systems, while JEI and KubeJS will support discovery and configurable content.

## Current status

The mod is in an early proof-of-concept stage. The NeoForge 1.21.1 project integrates with Create, JEI, and KubeJS and provides the experimental V3 column calculator (`createcheme:column_calculator_v3`).

The calculator provides editable column inputs, literature presets, side draws, steam feeds, pumparounds, and stream/composition results. A simultaneous MESH solver with stage and pressure continuation runs through one bounded server-owned solve service. Results must pass convergence and conservation checks before publication; server-authoritative admission and revision checks protect against stale results.

V3 has its own Minecraft-independent thermodynamics implementation under `science.column.v3.thermo`. The reusable foundation under `science.thermo` and the single equilibrium-stage solver remain covered by thermodynamic-identity, flash, and conservation tests. Property reconstructions and model limitations are documented separately; see [TJL19 provenance](docs/tjl19-property-provenance.md).

The custom multicomponent fluid system, connected plant simulation, reaction models, continuous equipment operation, and final multiblock structures are not implemented yet.

## V1 removal and compatibility

The original V1 calculator, its solver, packets, and `createcheme:column_calculator` block/item/block-entity/menu registrations have been removed. This is a breaking change for worlds and inventories containing V1 calculators: there is no remapping or conversion of their saved inputs/results. Replace any V1 calculators in the previous version before upgrading a world that needs them. Existing V3 registration IDs, saved input schema, property-package IDs, and dataset revisions are preserved.

Client and server must both run the refactored version; the network protocol version changed to reject the old packet set. The [removal record](docs/v1-calculator-removal-plan.md) describes scope and validation.

## Verification

Run the unit suite with:

```text
./gradlew test
```

The standalone numerical benchmarks live in `benchmarks/`. They are intentionally separate from the production source set so they cannot become runtime dependencies.

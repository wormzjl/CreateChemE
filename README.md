# CreateChemE

## Intent

CreateChemE is a Minecraft 1.21.1 NeoForge addon for Create that aims to turn chemical and petroleum processing into playable factory systems. Its scientific model is intended to be lightweight enough for real-time game simulation while retaining consistent component, mass, element, phase, and energy balances.

The planned material system combines real chemical species with petroleum pseudocomponents, phase equilibrium, heat transfer, fluid transport, reaction kinetics, catalyst state, and conservative coupling between reaction and separation processes. Public thermodynamic and process data will provide the scientific basis, with clearly identified gameplay approximations where industrial data are unavailable.

Planned equipment includes storage drums, pumps, compressors, heat exchangers, boilers, furnaces, generic reactors, gas-liquid separators, three-phase separators, air coolers, distillation columns, pressure-swing adsorption units, and stirred-tank reactors. Create will provide the physical factory and power systems, while JEI and KubeJS will support discovery and configurable content.

## Current status

The mod is in an early proof-of-concept stage. The NeoForge 1.21.1 project loads with Create, JEI, and KubeJS, and currently provides one placeholder crude-distillation calculator block.

The calculator has a pre-filled GUI for column inputs, server-authoritative calculation requests, tabular stream and composition results, console reporting, bounded asynchronous execution, and stale-result protection. Its current column calculation is an explicitly labelled deterministic placeholder that conserves the twelve pseudocut component flows; it is not yet a MESH distillation solver.

A Minecraft-independent thermodynamics foundation is now present under `science.thermo`. It includes a Peng-Robinson 1978 cubic equation of state, liquid/vapor root selection, fugacity coefficients, Wilson initialization, and a successive-substitution TP flash. This kernel is covered by numerical and conservation tests but is not connected to the calculator yet. Phase-stability analysis, enthalpy, pseudocomponent characterization, and the stagewise column solver remain to be implemented before replacing the placeholder result.

The custom multicomponent fluid system, connected plant simulation, reaction models, continuous equipment operation, and final multiblock structures are not implemented yet.

## Verification

Run the unit suite with:

```text
./gradlew test
```

The standalone numerical benchmarks live in `benchmarks/`. They are intentionally separate from the production source set so they cannot become runtime dependencies.

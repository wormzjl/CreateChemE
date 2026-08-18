# CreateChemE

CreateChemE is a planned Minecraft 1.21.1 NeoForge mod for gameplay-oriented chemical-process simulation. It targets lightweight but internally conservative thermodynamics, multicomponent and multiphase fluids, process equipment, and eventual integration with Create, JEI, and KubeJS.

The project is currently in the scientific-design and proof-of-concept planning stage; there is not yet a NeoForge source project or playable build.

## Current milestone

The first executable milestone is a [single-block crude-distillation calculator](./documentation/MILESTONE_1_CRUDE_DISTILLATION_POC.md). A player enters a named crude feed and column operating conditions, presses **Calculate**, and receives server-authoritative product compositions in both the GUI and server console. It is an on-demand steady solver, not yet a continuously operating multiblock.

## Design documents

- [Gameplay thermodynamics and transport model](./documentation/SCIENTIFIC_MODEL.md)
- [NeoForge and adaptive simulation architecture](./documentation/ADAPTIVE_SIMULATION_ARCHITECTURE.md)
- [Atmospheric crude-distillation benchmark](./documentation/CRUDE_DISTILLATION_BENCHMARK.md)
- [Crude-to-ethylene-glycol worked example](./documentation/CRUDE_TO_ETHYLENE_GLYCOL_EXAMPLE.md)
- [Milestone-1 crude-distillation calculator plan](./documentation/MILESTONE_1_CRUDE_DISTILLATION_POC.md)

The dependency-free Java file under [`benchmarks`](./benchmarks/) is a property-kernel microbenchmark, not a complete column solver or mod implementation.

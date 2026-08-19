# CreateChemE

CreateChemE is a Minecraft 1.21.1 NeoForge mod for gameplay-oriented chemical-process simulation. It targets lightweight but internally conservative thermodynamics, multicomponent and multiphase fluids, process equipment, and integration with Create, JEI, and KubeJS.

The NeoForge project and the first calculator-block vertical slice now compile and launch. Its current numerical result is an explicitly labelled, component-conservative placeholder used to exercise the GUI, validation, networking, persistence, and logging path; it is not yet the Peng-Robinson/MESH crude-column solver.

## Current milestone

The first executable milestone is a [single-block crude-distillation calculator](./documentation/MILESTONE_1_CRUDE_DISTILLATION_POC.md). A player enters a named crude feed and column operating conditions, presses **Calculate**, and receives server-authoritative product compositions in both the GUI and server console. It is an on-demand steady solver, not yet a continuously operating multiblock.

A fresh calculator screen is pre-filled with a Tia Juana Light test case (2610.7 kmol/h, 365 °C, 30 theoretical stages, feed stage 24, 8 MW reboiler duty, reflux ratio 4.17, and three direct side draws), so the request/result path can be exercised immediately.

Calculation logging is enabled by default. Set `enableCalculationLogging = false` in `run/config/createcheme-common.toml` during development, or in the instance's `config/createcheme-common.toml`, to suppress routine input/result/composition records. Unexpected internal errors are always logged.

## Development

Java 21 is required. The tested dependency set is pinned in `gradle.properties`.

```text
gradlew.bat test
gradlew.bat build
gradlew.bat runClient
```

## Design documents

- [Equipment code architecture and delivery roadmap](./documentation/CODE_ARCHITECTURE_AND_ROADMAP.md)
- [Gameplay thermodynamics and transport model](./documentation/SCIENTIFIC_MODEL.md)
- [NeoForge and adaptive simulation architecture](./documentation/ADAPTIVE_SIMULATION_ARCHITECTURE.md)
- [Atmospheric crude-distillation benchmark](./documentation/CRUDE_DISTILLATION_BENCHMARK.md)
- [Crude-to-ethylene-glycol worked example](./documentation/CRUDE_TO_ETHYLENE_GLYCOL_EXAMPLE.md)
- [Milestone-1 crude-distillation calculator plan](./documentation/MILESTONE_1_CRUDE_DISTILLATION_POC.md)

The dependency-free Java file under [`benchmarks`](./benchmarks/) is a property-kernel microbenchmark, not a complete column solver or mod implementation.

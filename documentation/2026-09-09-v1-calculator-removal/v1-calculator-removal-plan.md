# V1 calculator removal record

Date: 2026-09-09  
Branch: `codex/refactor-calculator-cleanup`

## Scope and current status

The original `createcheme:column_calculator` feature has been removed on this branch. The V3 calculator and the bounded process-solve service remain. The implementation also replaces branded V3 algorithm names and removes obsolete exploratory tests.

The removed V1 dependency chain was:

`ColumnCalculatorScreen -> ColumnNetwork -> ProcessSolveServices -> ColumnSimulation -> CounterCurrentColumnSolver`

The deleted block entity owned V1 inputs, calculation tickets, saved results, and its menu. `ProcessSolveCoordinator` now routes only V3 completions. Registrations, packets, persistence, and runtime dispatch were removed together.

## Executed removal scope

### 1. Define the saved-world transition

The old block, item, block-entity, and menu registrations all use `column_calculator`; V3 uses `column_calculator_v3`. V1 has a twelve-cut property package and a different input/result contract from V3. A registry rename alone cannot translate its saved calculation.

The user explicitly selected full removal on 2026-09-09. The old registrations are removed with no transition block, registry remap, or conversion of V1 saved inputs/results. This is an intentional breaking change for worlds and inventories containing V1 calculators. Replace V1 calculators using the previous version before upgrading worlds that need them. No existing world was loaded or modified during the refactor. V3 registry IDs and persisted schemas are unchanged.

### 2. Remove V1 game integration as a unit

Deleted these classes under `src/main/java/com/wormzjl/createcheme/`:

- `world/level/block/ColumnCalculatorBlock.java`
- `world/level/block/entity/ColumnCalculatorBlockEntity.java`
- `world/inventory/ColumnCalculatorMenu.java`
- `client/gui/screens/inventory/ColumnCalculatorScreen.java`
- `network/ColumnNetwork.java`

Removed the corresponding `COLUMN_CALCULATOR` entries in `ModBlocks`, `ModItems`, `ModBlockEntities`, and `ModMenus`, the V1 creative-tab entry in `CreateChemE`, and the V1 screen/result consumer in `CreateChemEClient`. All V3 entries remain. `ColumnV3Network` now registers directly on the mod event bus, retaining main-thread handlers; protocol version 5 rejects the old packet set while the V3 wire schema remains at version 8.

Removed V1 resources under `src/main/resources/`:

- `assets/createcheme/blockstates/column_calculator.json`
- `assets/createcheme/models/block/column_calculator.json`
- `assets/createcheme/models/item/column_calculator.json`
- `data/createcheme/loot_table/blocks/column_calculator.json`
- V1 translations in `assets/createcheme/lang/en_us.json`

The V1 block model used vanilla textures, so there was no dedicated texture to delete. The `data/create/tags/block/non_movable.json` and `data/minecraft/tags/block/mineable/pickaxe.json` tags now name the V3 calculator. The README describes the current V3 implementation and the intentional save/network incompatibility.

### 3. Remove V1 runtime adapters without changing service ownership

Removed V1 submission/admission adapters, `CalculationTicket` imports, the legacy dataset lookup, `ColumnRequest`/`ColumnCompletion`, `LegacyColumnCommand`/`LegacyColumnSolveResult`, and their sealed-interface permits entries from `ProcessSolveServices`. Removed V1 routing branches/imports from `ProcessSolveCoordinator` and the now-unused legacy timing helpers.

The single server-owned bounded service, per-target admission, cooperative V3 cancellation checkpoints, main-thread result delivery, completion drain limit, and shutdown/abandonment handling remain unchanged. The coordinator still registers once per lifecycle edge. The Java concurrency review checked that immutable worker inputs and server-thread request metadata remain separated; executor construction and queue ownership were not changed.

### 4. Retire the V1 science facade and its dedicated benchmarks

Deleted `science/column/ColumnSimulation.java`, `CounterCurrentColumnSolver.java`, and `TiaJuanaLight12PropertyPackage.java`, together with `ColumnSimulationTest` and `CounterCurrentColumnSolverTest` (18 tests). Removed the `legacyColumnBenchmark` / `legacyColumnBenchmarkHarnessTest` Gradle tasks. Their `LegacyColumnBenchmark` source was already absent from this checkout.

Retained `science/column/EquilibriumStageSolver.java`: it is a reusable single-stage solver directly exercised by `science/thermo/EquilibriumStageSolverTest`. Retained `science/thermo` and its physical-identity, flash, and conservation tests, along with V3's independent property packages and science code.

### 5. Verification and limits

- `./gradlew build` passed; all 494 remaining JUnit tests passed with no failures, errors, or skips. This includes bounded-service lifecycle tests, V3 command/request tests, codecs, and numerical regressions.
- Source/resource/build-configuration searches found no remaining references to the deleted V1 classes or exact V1 registry ID. The remaining `V1=V0` comment in the initializer denotes vapor flow by stage, not calculator version 1.
- `./gradlew benchmarkClasses` was attempted. Three ignored local diagnostics (`V3SideDrawRequestedGeometryProbe`, `V3SideDrawSeedProbe`, `V3TruncationPathProbe`) fail to compile because their pre-existing `V3TimeoutBenchmark` dependency is absent. They have no V1 imports and were not altered; this does not affect the production build.
- Interactive client, dedicated-server startup, and saved-world smoke tests were not run. Before release, check V3 placement/menu/presets and startup/shutdown in a fresh world; test any old-world upgrade only on a copy because V1 IDs are intentionally unsupported.

### 6. Git tracking cleanup

At the user's request, `documentation/`, `benchmarks/`, `scripts/`, `design/`, and `research/` are ignored and contain no tracked files. Six previously tracked files under `scripts/` were removed from the index with `git rm --cached` and verified to remain on disk. The removal record and provenance notes are in the separate `docs/` directory so they can travel with the refactor.

## Cleanup implemented on this branch

Solver methods now describe stage continuation, pressure continuation, and material/VLE recovery. Diagnostic paths use `stage-continuation`, `pressure-continuation`, and `material-closed-fallback`. The TJL19 class is `V3Tjl19PropertyPackage`; the retained continuation regression suite is `V3StageContinuationTest`. Numerical algorithms, property values, schedules, and acceptance gates are unchanged.

The serialized package ID `createcheme:tjl19_dwsim` and dataset revision `tjl19-dwsim-10.2.3-r1` are deliberately preserved, including callers/tests that use them. They participate in saved inputs and result provenance/digests. Fully removing those legacy strings requires a separate persistence migration. External-source attribution is recorded in [TJL19 property provenance](../../research/crude-regrouping/notes/tjl19-property-provenance.md); historical reports retain their original names.

Removed tests and resources:

| Removed item | Reason | Coverage retained |
| --- | --- | --- |
| `V3DwsimPathInitializerTest` | Prints candidate comparisons and asserts only report length; budget expiry still passes. | Initializer, material/VLE preconditioner, and accepted continuation regressions. |
| `V3DwsimRealCrudeStageMapTest` | Exploratory stage sweep accepts budget expiry with no outcome. | `V3StageContinuationTest` and `V3ColumnCalculatorTest` require accepted solutions. |
| `V3DwsimRealCrudeOperatingMapTest` | Exploratory operating map accepts budget expiry; it is not a numerical acceptance gate. | Low-pressure cold solves, convergence closure, and conservation/phase audits. |
| `V3ColumnInputTest.fixtureSchemaIsVersionedAndExplicitAboutReferenceAuthority` and `dwsim-reference-fixture.schema.json` | The schema has no tracked exporter, validator, or fixture consumer; the test checks literal substrings only. | Input immutability/digests, acceptance contracts, and independent manufactured/literature oracle tests. |

The removed operating maps were exploratory diagnostics, not exact substitutes for the retained regressions. They remain recoverable from Git if their parameter sweeps are needed for future research. The V1 tests were subsequently removed with their production code.

The preparatory naming cleanup passed all 512 then-existing tests. Full V1 removal subsequently retired its 18 dedicated tests; the remaining 494 pass.

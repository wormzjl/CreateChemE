# NeoForge implementation and adaptive simulation architecture

**Target:** Minecraft 1.21.1, NeoForge<br>
**Purpose:** in-game data, runtime, persistence, interoperability, and performance design<br>
**Status:** proposed architecture, updated 2026-08-19

This document implements the reusable equations in [SCIENTIFIC_MODEL.md](./SCIENTIFIC_MODEL.md). The active first implementation milestone is the on-demand [crude-distillation calculator PoC](./MILESTONE_1_CRUDE_DISTILLATION_POC.md). The large route fixture is kept separately in [CRUDE_TO_ETHYLENE_GLYCOL_EXAMPLE.md](./CRUDE_TO_ETHYLENE_GLYCOL_EXAMPLE.md).

## 1. Recommendation in one sentence

Run each formed process plant as a server-authoritative graph, take an immutable snapshot on the server thread, solve it on a small bounded CPU worker pool, and atomically commit the result on the server thread; choose the next full-update interval from measured solve cost, global server load, freshness deadlines, and fairness.

This directly supports “faster when the server is quiet, slower when busy.” It is feasible. However, **immediately launching a new solve whenever the previous one finishes should not be the normal loop**: it consumes every available core, lets expensive plants monopolise workers, and makes the simulated behaviour strongly hardware-dependent.

There will still be a tiny coordinator check on a server tick. The expensive thermodynamic and kinetic update does not run every tick, and individual tanks/pipes do not need tickers.

## 2. Scientific model implementation map

### 2.1 Module boundary

Keep the numerical engine independent of Minecraft and NeoForge:

```text
authored datapack records
        │ decode, resolve, validate, compile
        ▼
immutable scientific dataset
        │ select dense component set
        ▼
immutable plant context + snapshot
        │
        ▼
pure plant solver ──► immutable result
        │                  │
        └──── no world access
                           ▼
                 server-thread validation/commit
```

The scientific package contains only SI values, primitive arrays, immutable definitions, property packages, equipment equations, and diagnostics. It must not import `Level`, `BlockEntity`, `Holder`, capabilities, packets, rendering, or Create classes. Minecraft owns identities, persistence, scheduling, interaction, and presentation.

A future project layout can follow these dependency directions:

```text
science/             equations, flashes, transport, reactions, equipment
data/                codecs, validation, immutable dataset compiler
plant/               topology, snapshots, results, ledgers, coordinator
game/                blocks, block entities, SavedData, capabilities, payloads
compat/create/       the only package allowed to import Create APIs
client/              menus, summaries, rendering
```

This is an architectural target, not a request to create all packages before the first vertical slice.

### 2.2 Data-driven definitions

Use namespaced IDs in authored files, saves, commands, and UI. Compile them to stable dense integer indices and primitive arrays inside each immutable plant context.

Suggested custom datapack registries are:

| Registry | Contents |
|---|---|
| `chemical_component` | identity, elemental formula, MW, EOS, caloric, entropy, phase-change, density, viscosity, validity, confidence |
| `binary_interaction` | sparse PR `kij`, liquid-viscosity `g0/g1`, and later activity-model parameters |
| `reaction` | sparse stoichiometry, rate-law type and parameters, catalyst requirement, validity domain |
| `equipment_model` | scientific model type plus immutable tunable/gameplay parameters |

NeoForge 1.21.1 provides `DataPackRegistryEvent.NewRegistry`; each datapack registry has a disk `Codec` and may have a reduced network codec. Runtime lookup goes through `RegistryAccess`. See the versioned [registry documentation](https://docs.neoforged.net/docs/1.21.1/concepts/registries/) and [codec documentation](https://docs.neoforged.net/docs/1.21.1/datastorage/codecs/).

Use [NeoForge data maps](https://docs.neoforged.net/docs/1.21.1/resources/server/datamaps/) for reloadable interoperability mappings such as:

- external NeoForge/Create fluid → fixed feed composition template;
- sufficiently pure/product-grade mixture → export fluid;
- item/tag → catalyst definition;
- block/item → optional equipment or material metadata.

A dataset compile transaction performs:

1. decode all records with contextual error paths;
2. resolve IDs, units, phases, and references;
3. validate finite values, ordered ranges, atoms, segment continuity, and required phase coverage;
4. construct immutable coefficient arrays, sparse interaction layouts, and stoichiometric matrices;
5. stage the immutable candidate without changing the active catalog;
6. only at a verified whole-resource-reload success hook, publish it, increment `datasetRevision`, invalidate older in-flight results, and synchronize the new digest.

Treat preparation, listener apply, and global publication as separate stages. A listener's successful apply does not prove that the whole reload succeeded, because a later listener may still fail. Keep the candidate staged until a verified all-player/whole-reload success callback, such as the applicable form of `OnDatapackSyncEvent` on the pinned build, then swap once on the server thread. A failed reload discards the candidate and leaves the prior catalog, revision, and client digest untouched. If the exact NeoForge build offers no reliable global success boundary, disable live coefficient publication and require a world reload rather than claiming atomic hot reload. Tag/registry-aware decoding must use the lookup supplied for the staged reload, not an arbitrarily captured live `RegistryAccess`; compile-test that lookup path against the pinned NeoForge 21.1 [`AddReloadListenerEvent`](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/AddReloadListenerEvent.java).

Do not assume that live membership changes in a custom datapack registry behave identically across all NeoForge 21.1 builds. Test `/reload` against the pinned build. The conservative version-0 policy is: component/reaction identity changes require a world reload, while coefficient/interoperability overlays may use data maps or a codec-backed reload listener.

### 2.3 Component and interaction schema

Each compiled component needs:

| Group | Required information |
|---|---|
| Identity | namespaced ID, schema version, optional aliases and display family |
| Material role | permitted fluid phases, or `IMMOBILE_DEPOSIT` for the narrow coke/fouling exception |
| Conservation | molecular weight in kg/mol and elemental vector; integral for true compounds, exact rational/normalized for pseudocomponents |
| EOS | `Tc`, `Pc`, acentric factor, optional volume translation; required only for participating fluid phases |
| Caloric | formation-enthalpy basis and phase-specific `h(T)/Cp(T)` model |
| Entropy | ideal reference/temperature model and EOS departure support where needed |
| Phase change | vapour-pressure or EOS route and one coherent phase bridge when more than one fluid phase is permitted |
| Density | EOS or phase-specific compact model |
| Transport | gas/liquid viscosity segments for permitted transported phases; absent for an immobile deposit |
| Validity | permitted phases plus `T/P/composition` limits |
| Provenance | source, confidence, fit error, and revision |
| Gameplay | colour/product family; never used by scientific equations |

Pseudocomponents compile to the same runtime record as real compounds. Authored pseudocut metadata may additionally retain boiling range, SG, assay ID, viscosity anchors, and generation method, but equipment code does not branch on “real versus pseudo.”

A virtual kinetic basis, such as the example's combined naphtha feed, is not a storable component. The reaction compiler expands it into exact rational coefficients of real stored cuts before constructing the sparse stoichiometric matrix, then verifies elements and reference enthalpy again.

Binary interactions are separate sparse records. A declared compatible hydrocarbon group may intentionally default a missing viscosity interaction to zero. A missing pair marked `ASSOCIATING_REQUIRED`, such as water/glycol, is a validation error whenever both species can exceed the transport trace threshold; it is not silently downgraded to ideal mixing.

### 2.4 Runtime scientific objects

Canonical persisted equipment state is minimal:

```text
EquipmentInventory
  fluid component IDs plus amounts
  optional immobile-deposit IDs plus amounts
  explicit state specification and energy basis
  total U plus volume, or total H plus pressure, including deposit caloric energy
  quality-scalar ledgers
  schema/dataset revision
```

The compiled plant context maps IDs to one dense ordering. A snapshot references that immutable context rather than copying the complete database.

A converged property result conceptually contains:

```text
EquilibriumState
  T, P, bulk H/U checks, diagnostics
  PhaseState[]

PhaseState
  phase kind
  phase amount and composition
  density, Z, h, u, s, Cp
  optional viscosity/transport result
  validity diagnostics
```

`PhaseState[]` contains only equilibrium fluid phases. An optional `SolidDepositState` reports immobile amount, sensible/chemical energy, and fouling fraction. During `PH` or `UV` inversion, the outer temperature residual includes `Σ nsolid hsolid(T)` or `usolid(T)` before calling the fluid flash. Deposits have no EOS, viscosity, or fluid outlet and can leave only through an explicit cleaning/item ledger.

Temperature, pressure when volume-constrained, phase split, density, viscosity, and render state are derived. Persisting the last result as a warm-start/cache is allowed, but it is never authoritative.

The property package needs four state operations:

- `TP`: ordinary equilibrium;
- `PH`: heaters, coolers, valves, pumps, compressors, and turbines;
- `PS`: version-0 fixed-composition, single-vapour compressor/turbine reference outlet; reject phase crossing;
- `UV`: closed rigid tanks.

Compressors and turbines make entropy support mandatory. An ideal-gas first version obtains entropy differences from `Cp(T)` and the pressure term. Property modes are atomic: an ideal-caloric mode omits both residual enthalpy and entropy, while a Peng–Robinson departure mode supplies both from the same implementation.

Viscosity is completed **after** the phase equilibrium has converged, and only when requested by a pipe, pump, heat-transfer correlation, or Reynolds correction. Do not evaluate it during every fugacity iteration.

### 2.5 Equipment-facing contract

Separate immutable equipment definition, canonical holdup, current controls, and current solution guess. Scientific equipment falls into three groups:

1. **Holdup units:** tanks and reactors integrate component and energy inventories over physical `Δt`.
2. **Algebraic stream units:** heater, cooler, valve, compressor, turbine, separator, and simple exchanger calculate outlet streams and duties.
3. **Hydraulic elements:** pipe, valve, pump, and later compressor maps contribute pressure/flow residuals to a plant network.

A unit solve consumes only snapshot values: definition, state, inlet streams, sampled controls, admitted heat/shaft limits, physical interval, property package, and previous guess. It returns conserved deltas/new inventory, outlet offers, heat/shaft ledgers, hydraulic contributions, and diagnostics. It never directly drains a Minecraft tank or changes a Create kinetic network.

Make quantity bases explicit at this boundary:

| Quantity | Runtime basis |
|---|---|
| Stream composition/flow | mol and mol/s |
| Stream enthalpy | molar `h` in J/mol; enthalpy flow in J/s |
| Boundary ledger | interval-integrated component amounts in mol and energy/work in J |
| Holdup result | new fluid/deposit component totals in mol and total `U` or `H` in J, matching the serialized state specification |
| Heat/shaft availability | power in W in the sampled control input; integrated exactly once over substeps |
| Hydraulic residual | Pa, with mol/s or m³/s as the selected network flow unknown |
| Shaft bus | W; positive into a fluid machine, negative when a turbine exports power |

Do not expose one untyped `energy` number. Conversion from power to interval work belongs in one solver layer only, and every result reports the physical interval it actually integrated.

For the later **continuously operating** fractionator, use a small fixed number of equilibrium cells or a cheaper separation-factor model. The [crude-distillation benchmark](./CRUDE_DISTILLATION_BENCHMARK.md) proposes—without yet validating—four equivalent section modules containing 10–16 total virtual cells and 10–12 petroleum cuts for a five-product atmospheric tower. Those cells cover the lumped main-tower and side-stripper work together; do not schedule three additional stripper chains. A practical staged version evaluates K-values through the flash package and lets reflux, steam, pumparounds, side draws, and admitted reboiler duty control separation quality. Internal cells belong to one plant solve; they are not individual block entities. Keep the four-node sharp/recovery calculation only as an initial guess, test fixture, and degraded fallback. This is not the full-stage bare-main-column topology of the click-triggered [Milestone-1 calculator](./MILESTONE_1_CRUDE_DISTILLATION_POC.md).

Classify the version-0 fractionator as an **algebraic quasi-steady unit**, not a tray-holdup integrator. A snapshot supplies the timestamped boundary-ledger segments accumulated since the last commit. The worker solves one steady internal cell state for each event-bounded inlet/control regime and returns duration-integrated outlet allocations and duties. Coalesce adjacent regimes only under a configured state/control tolerance validated against the uncoalesced result; never replace a material nonlinear step with one average state. Startup waves, tray liquid/vapour holdups, and dynamic level/control responses are a separate later equipment mode and require their own benchmark.

Turbomachinery should initially use:

- compressor: `PS` ideal outlet → fixed isentropic efficiency → `PH` actual outlet;
- turbine: reverse enthalpy relation with fixed efficiency;
- gas turbine: separate compressor, combustor, and turbine nodes joined by a shaft-work ledger;
- viscosity: connected-line loss and optional Reynolds correction, not the primary work equation;
- later only: corrected-speed maps, surge, choke, staging, cooling, and leakage.

### 2.6 Persistence ownership

Milestone 1 has only one bounded calculator block. Its block entity owns the last accepted canonical input, input hash/revision, dataset fingerprint, solver version, last successful deterministic scientific result, and result-input hash; it does not need `SavedData`. Request IDs, queue/wall/CPU timing, executor statistics, futures, and `CALCULATING` are ephemeral and are not persisted. The broader ownership model below applies once a process spans blocks or chunks.

Use three distinct stores:

| Store | Owns |
|---|---|
| Controller/port block entities | local orientation/configuration, controller ID, small displayed state |
| Per-dimension `SavedData` | canonical formed plant graph, inventories, boundary ledger, revisions, controller locations |
| Runtime `PlantManager` | active objects, queues, futures, EWMA costs, capability caches; never persisted |

For the first single-tank prototype, the complete state may live in its controller block entity. Move canonical state to `SavedData` before a plant spans chunks. NeoForge requires block entities to call `setChanged()` and SavedData to call `setDirty()` after mutations; see [block entities](https://docs.neoforged.net/docs/1.21.1/blockentities/) and [SavedData](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/).

Dirty marking is disk persistence, not client synchronization. For small committed display-state changes, implement the block entity's update tag and update packet and call `Level#sendBlockUpdated`; use the custom summary/details payloads for larger or on-demand state. Never assume `setChanged()` updates a client.

Use [data attachments](https://docs.neoforged.net/docs/1.21.1/datastorage/attachments/) only for small metadata attached to foreign objects. They are not a substitute for canonical plant ownership and need explicit dirty marking/client synchronization when mutated indirectly.

Persist `schemaVersion`, fluid and deposit component IDs/amounts, explicit state specification, energy basis and total energy, constraint kind/value, topology/state revisions, unconsumed ledger, and dataset fingerprint. Dense indices, futures, thread state, derived phases, and timing estimates are never persisted. An unresolved old component ID quarantines the plant for migration; it must not be silently discarded.

### 2.7 Mixture capability and ordinary-fluid boundary

Do not register a NeoForge `Fluid` for every temperature or composition. Internally expose a phase-aware `IMixtureHandler` through a custom sided `BlockCapability`; keep arbitrary mixtures lossless inside the process network.

Ordinary `IFluidHandler` compatibility exists only at explicit ports:

- an inbound registered fluid maps through a data map to a fixed composition/energy template;
- an outbound parcel maps only when it meets a supported purity or named product-grade rule;
- unsupported arbitrary mixtures are rejected rather than stripped of composition and energy.

NeoForge capabilities are intended for this interoperability boundary and support cached lookups through `BlockCapabilityCache`. Its invalidation callback should mark topology dirty for a later server-thread rebuild; workers never query the cache. See the 1.21.1 [capability documentation](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/).

Declare the capability once and register every provider on the mod bus through `RegisterCapabilitiesEvent`. If a port changes side, mode, availability, or handler identity, call `level.invalidateCapabilities(pos)`. Construct neighbour `BlockCapabilityCache` objects only after load with a validity predicate, and discard them with the owning level/manager.

Ordinary fluid calls are transactions. `FluidAction.SIMULATE` must never append to a boundary ledger or mutate a mixture; `EXECUTE` records exactly the amount that `fill` or `drain` actually returns. This rule prevents capability probes by Create or another pipe mod from duplicating matter or energy.

A carrier `FluidStack` with data components is possible for portable containers, but it should not be the primary plant representation: continuously different compositions become different stack identities and generic tanks may refuse to merge them.

### 2.8 Networking and client view

Register payloads with `RegisterPayloadHandlersEvent`, `PayloadRegistrar`, and bounded `StreamCodec`s. NeoForge documents this in [networking](https://docs.neoforged.net/docs/1.21.1/networking/) and [stream codecs](https://docs.neoforged.net/docs/1.21.1/networking/streamcodecs/).

Useful payload boundaries are:

- `PlantSummaryS2C`: revision, T, P, phase fractions, flow/load, fault bits;
- `PlantDetailsRequestC2S`: controller/plant identity;
- `PlantDetailsS2C`: capped top-component/equipment diagnostics for an open UI;
- `ControllerActionC2S`: start/stop, valve, and setpoint commands;
- `CatalogDigestS2C`: schema/network version and dataset fingerprint.

Send only small render state with ordinary block-entity synchronization. Detailed composition is sent on demand to the player viewing the controller. Validate distance, menu ownership, permissions, finiteness, and bounds for every client command on the server.

Use [`OnDatapackSyncEvent`](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/OnDatapackSyncEvent.java) in two distinct ways after verifying the pinned build's semantics: its whole-reload/all-player form publishes a staged catalog once and resends the new fingerprint; its player-specific join form sends only the already-active digest to that player. The failed-reload path publishes and sends nothing.

### 2.9 Create integration boundary

Create is now a required, pinned development/runtime dependency, but integration still belongs behind one adapter so the scientific engine and most game code do not depend on Create internals. The equipment taxonomy and target code boundaries are maintained in [the equipment code architecture](./CODE_ARCHITECTURE_AND_ROADMAP.md).

Create's 1.21.1 branch exposes NeoForge fluid capabilities in its tank code, making the capability boundary preferable to direct tank manipulation. See Create's [SmartFluidTankBehaviour](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/foundation/blockEntity/behaviour/fluid/SmartFluidTankBehaviour.java).

For rotating machinery:

- sample actual speed, theoretical speed, rotation direction, stress-unit state, and heat inputs on the server thread;
- copy those values into the plant snapshot;
- let the worker compute requested/produced shaft power;
- apply the committed operating fraction/output and explicitly refresh the kinetic network's cached stress/capacity on the server thread; generator changes also refresh generated rotation;
- never query or mutate Create from a worker.

Create stress units are gameplay units, not watts and not “remaining network power.” Define one configurable/calibrated bridge from SU and RPM to admitted W/J, including what happens when actual speed is zero because a network is overstressed or frozen. [`BlockStressValues`](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/api/stress/BlockStressValues.java) provides block-level base coefficients; per-instance dynamic load/generation generally requires adapter calls into Create's public-but-internal kinetic classes.

Prefer Create's stable public `com.simibubi.create.api` surface. [`GeneratingKineticBlockEntity`](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/kinetics/base/GeneratingKineticBlockEntity.java) itself is public, but it is outside that stable API namespace; isolate the dependency, pin one exact Create release, and protect it with an integration test. If machines directly extend Create classes, declare Create required. If Create is optional, place integration in a separate module/JAR so absent Create classes cannot be loaded.

Until an atomic relocation/migration protocol exists, tag controllers, ports, and process equipment as `create:non_movable` or reject them through Create's [movement-check API](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/api/contraption/BlockMovementChecks.java). Otherwise a contraption can move a block entity while leaving its position-based SavedData membership behind, orphaning or duplicating a plant.

Do not express continuous thermodynamics, inventory, recycle, or variable-step dynamics as Create processing recipes. Those remain suitable only for discrete compatibility/viewer recipes.

### 2.10 Minimal vertical slice

The selected first milestone is a single-block, on-demand crude-column calculator rather than a continuously ticking tank/pipe plant. Build and test its scientific kernel synchronously, then invoke the unchanged pure call through one bounded server-owned worker when the player presses **Calculate**:

```text
client draft
    → Calculate request
    → server validation and immutable snapshot
    → steady crude-column solve
    → revision-checked server commit
    → GUI result and server-console report
```

The current executable fixture exercises the pre-filled GUI, bounded inputs/results, server authority, validation, deterministic component-conservative dummy allocation, result tables, configurable console reporting, and a small persistence shell. It does **not yet** exercise petroleum property loading, Peng–Robinson, phase enthalpy, MESH equations, the bounded worker, full canonical persistence, unload cancellation, or stale-result rejection. Those remain acceptance gates before the dummy can be replaced. The target calculator has no physical inventory, fluid transfer, per-tick solve, automatic cadence, Create kinetic demand, side strippers, pumparounds, or hydraulics. Its complete degrees of freedom, GUI/output contract, logging policy, gates, and acceptance criteria are fixed in [the milestone plan](./MILESTONE_1_CRUDE_DISTILLATION_POC.md).

The earlier liquid and compressor fixtures remain valuable after this calculator establishes the scientific and lifecycle core. A later continuous-process foundation should use:

```text
vented water/MEG tank → pump → pipe → receiver
N2/ethylene buffer → compressor → gas pipe → receiver
```

Those fixtures add mixture capabilities, boundary ledgers, `PS/PH/UV`, viscosity, pressure loss, pump/compressor work, continuous cadence, and Create kinetic coupling. They are no longer prerequisites for the click-triggered crude calculator, but they are prerequisites for turning that calculator into a continuously operating refinery unit.

### 2.11 Compilation and cache policy

Use three cache lifetimes, each with one clear owner:

- **Dataset lifetime:** precompute EOS constants, caloric segment bounds/integrals, Wilke molecular-weight factors, sparse binary-interaction rows, balanced stoichiometric vectors, and validity metadata. Replace this immutable object only on confirmed whole-reload success.
- **Plant-context lifetime:** compile the union of components and reactions used by one plant into dense indices, primitive coefficient arrays, and sparse graph layouts. Rebuild it only when topology or the dataset revision changes.
- **Job lifetime:** allocate solver scratch arrays, cubic roots, Jacobian workspaces, and warm-start guesses inside the job or a worker-confined reusable workspace. They never escape into authoritative state.

Do not create a mutable global flash-result cache. Its keys would need to include composition, phase basis, property revision, tolerance, and initial-guess policy; contention and invalidation would cost more than the usual gameplay-sized solve. Cache compiled coefficients and plant layouts instead, and retain only the last converged state per plant as a versioned warm start.

## 3. Architectural unit: a plant, not a block

Treat a connected and formed process as one `Plant`:

```text
Minecraft world / Create machinery
        │ input-output boundary ledgers
        ▼
server-owned PlantController
        ├── topology graph: units, streams, controls
        ├── canonical conserved state
        ├── scheduler metadata
        └── immutable snapshot ──► pure worker solve
                                      │ immutable result
                                      ▼
                              version-checked commit
```

The graph contains coarse nodes such as tanks, flash drums, reactor zones, equilibrium stages, heat exchangers, pumps, valves, and stream junctions. Blocks provide construction, rendering, interaction, and ports. The controller provides simulation identity and state.

Benefits:

- one scheduling decision replaces hundreds of block ticks;
- internal streams need no world capability calls during a solve;
- mass and energy can be allocated deterministically across the entire plant;
- only boundary transfers cross into Minecraft/Create fluid or item systems;
- a large refinery can be solved as one sparse graph or a few coupled subgraphs.

For a Create addon, rotation speed, stress, heat sources, and pipe connections should be sampled into the boundary ledger/snapshot. A worker must never query a kinetic network, block entity, level, chunk, capability, or Create object directly.

## 4. Thread-safety boundary

### 4.1 Server thread owns mutable game state

Only the logical server thread may:

- read or write the `Level` and chunks;
- inspect or mutate block entities/capabilities;
- form or split plant topology;
- allocate inputs from a shared source;
- mutate canonical plant state;
- mark persistence dirty;
- send packets;
- change scheduler state.

NeoForge distinguishes logical client/server and physical sides; its [side documentation](https://docs.neoforged.net/docs/1.21.1/concepts/sides/) is the relevant authority. The [block-entity documentation](https://docs.neoforged.net/docs/1.21.1/blockentities/) also makes clear why indiscriminate per-block ticking scales badly.

### 4.2 Worker is a pure function

The worker receives an immutable value snapshot. Job-owned mutable arrays are deeply copied or freshly allocated; the compiled dataset and plant context are shared safely by immutable reference:

```text
PlantSnapshot
  plant UUID
  server epoch
  chunk/load epoch
  topology revision
  state revision
  dataset revision
  job sequence
  [intervalStartTick, intervalEndTick]
  unit definitions and stable ordering
  immutable compiled plant-context reference
  conserved inventories
  boundary-ledger copy through intervalEndTick
  controls, rotation/heat/power samples
```

It returns only an immutable `SolveResult`: new conserved state, outputs to offer at boundaries, convergence diagnostics, residuals, actual interval integrated, worker CPU time, wall time, and the same revision keys.

The server thread commits only if all keys still match and the result is finite, nonnegative within tolerance, converged, and conservative. Otherwise it discards the result without consuming the canonical input ledger.

This snapshot → solve → result pattern is the central safety property. `CompletableFuture` does not make world access safe; its non-async continuation may execute on the completing thread, while unspecified async continuations use the common pool. See the official [`CompletableFuture` contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html).

## 5. What “adaptive based on calculation time” should mean

### 5.1 Separate three clocks

Do not confuse:

1. **Server ticks:** how much loaded, active simulation time has elapsed.
2. **Worker time:** how expensive a numerical result was to calculate.
3. **Wall latency:** how stale the visible result may become.

Worker time chooses resource allocation. Server ticks choose physical `Δt`. A 400 ms calculation covering 5 s of game time advances the process by 5 s, not 5.4 s. If the server pauses, unloaded plants do not silently progress unless offline processing is an explicit feature.

### 5.2 Why completion-driven reruns alone fail

| Policy | Advantage | Failure mode |
|---|---|---|
| Rerun immediately on completion | Lowest possible latency | Saturates cores; no fairness; physics cadence varies with hardware |
| `period = constant × last runtime` | Simple local CPU-share estimate | No global capacity bound; noisy timing causes oscillation |
| Fixed 5 s | Predictable and deterministic | Wastes headroom on cheap plants; overloads on expensive plants |
| Global token bucket only | Hard compute limit | Can starve expensive plants without deadlines/fairness |
| **Cost + load + tokens + deadlines** | Adaptive, bounded, fair, observable | More scheduler metadata; recommended |

Completion can place an already-overdue plant back in the ready set, but allow at most one catch-up solve and let other overdue plants go first.

## 6. Recommended hybrid scheduler

All scheduler state remains server-thread-confined. Workers merely enqueue results.

### 6.1 Cadence tiers and defaults

Use discrete periods to avoid timing jitter:

```text
20, 40, 100, 200, 400 executed server ticks
 1,  2,   5,  10,  20 nominal seconds
```

Suggested defaults:

| Setting | Default |
|---|---:|
| CPU worker threads | 1; 2 only after dedicated-server benchmark |
| Global target per worker | 35% average CPU; configurable to 50% after benchmark |
| Desired per-plant maximum | 5% of one worker core |
| Cost EWMA coefficient | 0.2 |
| Cold-start predicted cost | 50 ms |
| Solver wall deadline | 500 ms |
| Policy recomputation | every 20 ticks |
| Slowdown hysteresis | 2 overloaded policy windows |
| Speed-up hysteresis | 10 healthy policy windows |
| Main-thread snapshot budget | 1 ms/tick |
| Main-thread commit budget | 1 ms/tick |
| Hard normal staleness | 400 active ticks (20 s) |

Each plant also has a quality class:

- simple stirred tank: minimum/preferred 20–40 ticks;
- small flash or heat-exchanger train: 40–100 ticks;
- refinery graph: preferred 100 ticks, with a 20–400 tick allowed envelope;
- inactive equilibrium plant: event-driven plus a slow validation update.

These are defaults, not promises. A plant can earn faster updates only when its measured cost and global capacity allow them.

### 6.2 Conservative cost estimator

Measure worker CPU time when supported and wall time as a fallback. For each plant:

\[
\mu'_i=(1-\alpha)\mu_i+\alpha c_i
\]

\[
d'_i=(1-\alpha)d_i+\alpha|c_i-\mu'_i|
\]

\[
\hat C_i=\mu'_i+2d'_i.
\]

`Ĉi` is a high-side predicted cost in milliseconds. Charge a timeout as at least the full timeout budget so a repeatedly failing plant never appears “cheap.” Reject obvious GC/outlier contamination only for reporting; capacity admission should stay conservative.

### 6.3 Cost-safe local period

Let `ui` be a plant's permitted fraction of one worker and one tick be 50 ms nominally:

\[
P_{cost,i}=\frac{\hat C_i}{50u_i}.
\]

Round upward to an allowed tier and respect the configured minimum:

\[
P_{0,i}=Q_{up}\left(\max(P_{min,i},P_{cost,i})\right).
\]

At `ui = 0.05`:

| Predicted solve cost | Cost-safe result | Chosen tier with 20-tick minimum |
|---:|---:|---:|
| 8 ms | 3.2 ticks | 20 ticks / 1 s |
| 80 ms | 32 ticks | 40 ticks / 2 s |
| 250 ms | 100 ticks | 100 ticks / 5 s |
| 500 ms | 200 ticks | 200 ticks / 10 s |

This gives the requested behaviour even before looking at global load: cheap calculations may run frequently, while costly ones automatically spread out.

### 6.4 Global server-load factor

Track a roughly five-second EWMA of server milliseconds per tick, `M`. A conservative availability factor is:

\[
g(M)=
\begin{cases}
1,&M\le35\\
(48-M)/13,&35<M<48\\
0,&M\ge48.
\end{cases}
\]

For `W` worker threads and target utilisation `U0`:

\[
A=U_0Wg(M)
\]

is the available average worker capacity. If the server tick reports no spare time, do not start a normal snapshot on that tick. NeoForge exposes this admission hint on [`ServerTickEvent`](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/tick/ServerTickEvent.java), but `hasTime()` is not an elapsed-tick measurement. Measure `M` explicitly by timestamping matching `ServerTickEvent.Pre` and `.Post` callbacks with a monotonic clock, or use a verified 1.21.1 server timing accessor. Keep the measurement on the server thread and exclude pauses/restarts from the EWMA.

This factor intentionally responds to the whole modded server, not just this mod. It keeps thermodynamics from worsening an already overloaded tick loop, even when worker cores themselves look idle.

### 6.5 Global dilation

Predicted demand is:

\[
D=\sum_i\frac{\hat C_i}{50P_i}.
\]

When `D > A`, find the smallest dilation `S ≥ 1` for which demand fits:

\[
P_i(S)=\min\left(P_{max,i},Q_{up}\left[P_{0,i}
\left(1+\frac{S-1}{w_i}\right)\right]\right)
\]

with QoS weights such as 2 for a player-designated priority plant, 1 normal, and 0.5 background. Higher weight slows less. Apply slowdown after two bad windows and speed-up only after ten healthy windows; this prevents cadence flapping.

If all plants at their maximum periods still exceed capacity, the workload is infeasible. Show an explicit *simulation saturated* state and degrade/pause low-priority plants. Never hide the overload in an ever-growing queue.

### 6.6 Worker-millisecond token bucket

Prediction is imperfect, so enforce the real budget with tokens measured in worker-ms:

\[
T\leftarrow\min(T_{max},T+50A)
\]

per executed tick. Before dispatch reserve `Ĉi`; on completion reconcile the reservation with actual CPU cost:

\[
T\leftarrow T-\hat C_i,
\qquad
T\leftarrow T+\hat C_i-C_{actual,i}.
\]

Suggested capacity is `500 × W` worker-ms with an urgent debt floor of `-250 × W` worker-ms. Normal work requires tokens. A plant approaching its hard staleness deadline may borrow within the debt bound, after which normal dispatch pauses until repayment.

### 6.7 Fair dispatch

Keep the ready queue in the server-owned scheduler, not inside the executor:

1. Promote jobs whose predicted completion would miss their hard deadline.
2. Choose promoted jobs by earliest hard deadline.
3. Otherwise choose the smallest weighted virtual-finish value, charging `estimatedCost / weight`.
4. Break ties by stable plant UUID.

This prevents a cheap plant swarm from starving a large refinery and prevents one refinery from occupying every dispatch.

## 7. Executor design and multithreading feasibility

Thermodynamic flashes, ODE integrations, sparse flow balances, and controller calculations are CPU-bound pure arithmetic, so they are good candidates for platform worker threads. The main constraints are total core count and memory bandwidth, not Java's ability to run them concurrently.

Use one explicitly owned bounded `ThreadPoolExecutor` per server, not one executor per plant:

- fixed `W = 1` by default, optionally 2;
- daemon threads named for diagnostics;
- no hidden/unbounded work queue;
- submit only when a worker slot is known free;
- abort/reject if a scheduler invariant is violated;
- cooperative interruption and a solver deadline;
- orderly shutdown on server stop.

Java's convenience fixed pools use an unbounded shared queue, as documented in [`Executors`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html). A direct [`ThreadPoolExecutor`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html) exposes the bounds needed here.

Do not use:

- `ForkJoinPool.commonPool()`;
- an unbounded executor queue;
- one operating-system thread per plant;
- `CallerRunsPolicy`, which could unexpectedly run a full solve on the server tick;
- parallel streams or unordered floating-point reductions inside a plant;
- virtual threads for the numerical solver.

Virtual threads improve scale for tasks that spend time blocked on I/O; they do not make long CPU-bound calculations faster. Oracle's [virtual-thread guidance](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) explicitly frames them as a throughput tool rather than “faster threads.”

Two workers can help on a many-core dedicated server when multiple plants are due. More workers can easily compete with chunk generation, networking, garbage collection, and other mods. Configuration should be constrained by benchmarks, not `availableProcessors()` alone.

## 8. Coordinator state machine

The following state machine belongs to the later continuously scheduled plant manager. The Milestone-1 calculator uses the smaller server-thread state `IDLE|DIRTY → CALCULATING → SUCCESS|FAILED`, with `STALE` as a presentation flag when inputs no longer match the last success. It dispatches only after Calculate, permits one in-flight job per block, and never automatically returns to `DUE`.

```text
PARKED ── load/form ──► IDLE ── due ──► DUE
   ▲                                  │
   │                         slot + budget + snapshot
   │                                  ▼
   └── unload/stop ◄────────── DISPATCHED
                                      │ result message
                                      ▼
                               RESULT_PENDING
                                 │          │
                       valid commit       stale/fail
                                 │          │
                                 ▼          ▼
                               IDLE     DUE/DEGRADED
                                             │ repeated
                                             ▼
                                           FAULT
```

Rules:

- one in-flight job per plant;
- only the server thread changes this state;
- `DISPATCHED` covers executor submission and execution;
- a stale result never consumes inputs;
- one timeout/non-convergence retains the last valid state and selects a slower tier;
- three consecutive failures or exceeded hard staleness closes process transfers and enters a visible fault;
- unload, disassembly, confirmed dataset publication, or server stop increments an epoch/revision so old results cannot commit.

## 9. Tick-level flow (pseudocode, not implementation)

```text
onServerPostTick(tick, hasTime):
    update MSPT EWMA and accrue worker-ms tokens

    drain immutable completion messages within commit budget
    sort by (intervalEndTick, plantUUID, jobSequence)
    for each completion:
        reconcile estimated and actual worker cost
        reject if epoch/revisions/job sequence changed
        reject if convergence, finiteness or conservation checks fail
        atomically:
            apply conserved result
            consume canonical boundary ledger through intervalEndTick
            advance lastIntegratedActiveTick
            increment state revision and mark dirty
        update cost/latency estimates and schedule next due tier

    every policy window:
        calculate load factor, cost-safe periods and global dilation
        apply slowdown/recovery hysteresis

    mark elapsed due times

    while a worker slot and snapshot budget remain:
        choose urgent EDF job, otherwise weighted-fair job
        check hasTime, token/debt and one-in-flight rules
        reserve predicted worker-ms
        capture an immutable, versioned snapshot
        mark DISPATCHED and submit pure solve
```

Completion callbacks do nothing except place an immutable result into a bounded multi-producer/single-consumer queue. They do not mutate a plant, schedule another solve, or touch the world.

## 10. Variable interval and numerical correctness

Adaptive scheduling makes full updates irregular. The physics layer must therefore accept an explicit interval and integrate it safely.

### 10.1 Boundary ledger

Between full updates, process ports append timestamped, canonical increments:

```text
tick 101: +2.0 mol ethylene, +0.5 mol oxygen, +12 kJ shaft work
tick 102: +2.0 mol ethylene, valve command 65%
...
```

The worker receives a copy through a fixed `intervalEndTick`. Valid commit consumes exactly that prefix. A stale/failed result consumes none. Entries with identical control regimes can be coalesced to bound memory, but amounts and energy must remain exact.

### 10.2 Internal substeps

A 20-second scheduling interval must not become one explicit Euler step. The solver should substep based on numerical need:

- exact/analytic first-order update where available;
- implicit or positivity-preserving method for stiff reaction networks;
- adaptive error-controlled ODE integration for reactors;
- damped Newton flash iterations with iteration and wall limits;
- staged flow allocation at event/control boundaries;
- maximum internal `Δt` per equipment model.

The scheduler interval controls observation/commit frequency; internal substeps control accuracy and stability.

### 10.3 Determinism

Adaptive cadence based on measured CPU/wall time cannot produce identical histories on different computers. It can still avoid race-induced nondeterminism:

- stable component, unit, stream, and reaction ordering;
- stable tie-breaking by UUID/sequence;
- deterministic shared-feed allocation before snapshots;
- one in-flight result per plant;
- immutable datasets and snapshots;
- no parallel reductions inside a solve;
- commit in deterministic interval/order sequence.

Provide a fixed-cadence mode for automated tests, competitive servers, and recorded replays. “Adaptive to hardware” and “identical tick-by-tick history across hardware” are mutually conflicting requirements.

## 11. Interaction between plants

### 11.1 Cross-plant mixture transfer

The internal mixture representation and ordinary-fluid compatibility boundary are defined in section 2.7. Between two plants, transfer a canonical parcel containing namespaced component amounts, energy, quality scalars, and dataset revision into a finite server-owned port buffer. The receiving plant snapshots only committed buffer inventory. Never reduce a cross-plant parcel to an ordinary `FluidStack` unless an explicit product-grade mapping accepts that loss of detail.

Pipes integrate molar flow into boundary ledgers; they do not create world-fluid blocks for each internal composition. Persist IDs and compile them to a receiving plant's dense local ordering only after the parcel is accepted.

### 11.2 Shared feeds and safety

Two plants must not independently consume the same feed based on stale snapshots. Use one of these boundaries:

1. Put strongly coupled units in one plant graph; preferred.
2. Allocate all shared boundary flows on the server thread before dispatch.
3. Treat ports as finite buffers: upstream commits material to a tank, downstream snapshots only committed inventory.

Never have two workers negotiate a shared pipe or mutate the same tank. This is both a race and a conservation error.

Safety interlocks are separate from full solves. Cheap conservative checks for maximum stored energy, pressure envelope, temperature envelope, invalid state, and emergency shutoff remain synchronous. They should not wait up to 20 seconds for thermodynamic detail.

### 11.3 Future hydraulic and pump layer

Do not simulate pipe fluid per block. Treat pipes, fittings, valves, pumps, and vessels as a small hydraulic graph inside the same plant snapshot. Geometry and settings are inputs; phase state, density, viscosity, vapour pressure, line pressure loss, flow, and pump power are derived results.

The phase-specific transport equations, validity rules, and petroleum anchors are defined in [the scientific viscosity model](./SCIENTIFIC_MODEL.md#6-global-viscosity-model); their compiled game representation is covered in sections 2.3–2.4. This section concerns how those resolved properties drive the hydraulic graph. Version 0 should reject or derate a two-phase pump inlet rather than fabricate one blended viscosity.

A practical progression is:

1. Given a requested flow, calculate line pressure drop and required pump head/power; cap the flow when the installed pump is insufficient.
2. Add a fixed-efficiency gas compressor and single gas line using prescribed inlet flow and outlet pressure.
3. Add pump curves, valves, NPSH/cavitation, compressor maps, and a nodal pressure/flow solve.
4. Add selected two-phase or non-Newtonian mechanics only after the single-phase graph is stable.

Hydraulic calculations fit the existing asynchronous design. They are pure snapshot-to-result work and usually cheap compared with flash/recycle convergence. Valve changes, pump trips, invalid pressure, and emergency shutoff still update synchronous server-owned controls; the next admitted plant job integrates the physical response from the committed boundary ledger. A maximum-state-age rule prevents a busy server from leaving an actively changing pump network stale indefinitely.

Energy must close across this layer. A pump adds admitted shaft work to the fluid enthalpy; an adiabatic valve or frictional line does not receive an extra “friction heat” source. Reaction enthalpy likewise belongs to the [full species-enthalpy convention](./SCIENTIFIC_MODEL.md#7-reaction-enthalpy-and-energy-convention) and must not be added again by equipment code.

## 12. Persistence and lifecycle

For Milestone 1, persist only the block-owned deterministic input/result fields defined in section 2.6. On unload, replacement, data revision, or server stop, increment/invalidate the job token and allow no later commit. An interrupted request reloads as idle, while the older success remains visible and stale. The plant-level fields below apply to the later continuous multi-block architecture.

Persist:

- canonical conserved fluid/deposit inventories, explicit energy basis/state specification, energy total, and constraint;
- formed topology identity or reconstructable membership;
- unconsumed boundary ledger;
- last integrated active tick;
- period tier and visible degraded/fault state;
- component/dataset version needed for migration.

Do not persist futures, result queues, thread state, token balance, EWMA server load, or host timing estimates. Reset performance estimates conservatively after restart or host migration.

Construct one `PlantManager` and executor per `MinecraftServer`, including the integrated server, during server start or first server-owned access. Do not store one JVM-global manager across world restarts.

Version 0 treats a plant as active only while its controller and every topology-owning equipment/port chunk are loaded; otherwise it is `PARKED` and boundary transfers close. Reactivation begins from block-entity `onLoad` or deferred main-thread work after the chunk is fully usable. Do not query the world directly from [`ChunkEvent.Load`](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/level/ChunkEvent.java), which may fire before a chunk reaches `FULL`. Clean level-scoped caches and park affected plants on [`LevelEvent.Unload`](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/level/LevelEvent.java).

On required-member unload or disassembly:

- stop accepting transfers;
- increment plant epoch;
- request cancellation without waiting;
- leave canonical state/ledger safe for save;
- discard any eventual old-epoch result.

After confirmed whole-reload success, publish the candidate and increment dataset revision; a failed reload changes nothing. On [`ServerStoppingEvent`](https://github.com/neoforged/NeoForge/blob/1.21.1/src/main/java/net/neoforged/neoforge/event/server/ServerStoppingEvent.java), halt admission, increment server epoch, reject later completions, interrupt workers, and use a bounded termination wait. On `ServerStoppedEvent`, discard the executor, queues, managers, and all references to that server so a later integrated-server start begins cleanly.

## 13. Performance feasibility

The design is practical because solver demand scales with **active plant graphs / update period**, not blocks / tick.

There is not yet a production plant solver, so the earlier `80 ms column` and `500 ms refinery` figures are scheduling examples, not measurements. The standalone [crude-column kernel benchmark](../benchmarks/CrudeColumnKernelBenchmark.java) now supplies a narrower measured lower bound. It performs staged Peng–Robinson flashes, fugacity iterations, Rachford–Rice splits, caloric sums, and composition sweeps with preallocated arrays; it uses synthetic pseudocut-like inputs and does not close full MESH energy residuals or column recycles.

On Java 21.0.11 and one Ryzen 7 9700X worker thread, six quick fresh-JVM runs measured:

| Kernel case | p50 range | p95 range | p99 range | Numeric workspace |
|---|---:|---:|---:|---:|
| 12 components, 10 cells, 6 sweeps | 0.1707–0.1716 ms | 0.1730–0.1749 ms | 0.1803–0.1934 ms | about 5.8 KiB |
| 12 components, 16 cells, 10 sweeps | 0.6151–0.6182 ms | 0.6996–0.7058 ms | 0.7275–0.8114 ms | about 8.6 KiB |
| 16 components, 61 stages, 15 sweeps | 6.9887–7.0464 ms | 7.6443–7.7840 ms | 7.8196–7.8666 ms | about 40.1 KiB |

These are property-kernel timings, not solved-column latency. For provisional admission planning only, a 10× hypothesis gives approximately 1.7, 6.2, and 70.0 ms/update respectively. At a five-second cadence, 100 gameplay-cell columns at 1.7 ms would request about 34 worker-ms/s; 20 reduced CDUs at 6.2 ms would request 24.8 ms/s; ten deliberately full-stage cases at 70.0 ms would request 140 ms/s. Those are respectively 9.7%, 7.1%, and 40.0% of a healthy one-worker 350 ms/s scheduler budget. These arithmetic examples exclude snapshot/commit cost, other mods, GC, contention, and pathological convergence.

The next benchmark for Milestone 1 must time a genuine cold- and warm-started **bare-main-column** MESH solve with material and energy residuals, total condenser, partial reboiler, direct liquid side draws, immutable result/outcome construction, CPU time, convergence assertions, allocation profiling, and integrated-server load. It must not require pumparounds or side strippers that the milestone does not implement. A later reduced continuous-column benchmark adds the assigned side-stripper behaviour and pumparound recycle tears, then uses its conservative p95—not the property-kernel median—to seed scheduler cost. Cold starts that fail the authored iteration or wall deadline must fault visibly rather than merely being run less often.

Likely bottlenecks, in order, are:

1. snapshot/commit work on the server thread;
2. repeated phase flashes inside column/recycle convergence;
3. allocation and garbage collection from immutable snapshots;
4. stiff kinetics or non-converging recycle loops;
5. too many separately scheduled tiny plants;
6. packets/UI synchronisation, if full composition is sent frequently.

Mitigations:

- reuse immutable property tables and compact primitive arrays;
- allocate one dense local component index per plant;
- cache temperature-independent EOS terms and sparsity structure;
- pretabulate enthalpy functions when useful, but do not expect constant Cp to materially change plant cost—the flashes, recycle iterations, and ODE solves dominate;
- prefit viscosity curves or small tables offline; a phase-specific lookup and a 10–20-component gas-mixing rule are normally negligible beside a flash;
- warm-start flashes and recycle convergence from last committed state;
- send UI deltas/summary values, not every internal array;
- merge adjacent equipment into one plant graph;
- cap flash, Newton, recycle, and ODE work with explicit diagnostics;
- profile on a dedicated server with other mods active.

## 14. Telemetry and acceptance targets

Expose enough information to tell scientific slowness from scheduler overload:

- server MSPT EWMA, load factor, dilation, tokens/debt;
- active/due/in-flight/degraded plant counts;
- configured versus actual periods and maximum state age;
- snapshot, queue, wall, CPU, commit, and end-to-end times;
- estimated/actual cost ratio and variance;
- solver iterations, residuals, rejected/stale result reasons;
- deadline misses, urgent borrowing, timeouts, tier distribution;
- executor thread count and queue depth;
- pending-ledger age and size.

For the Milestone-1 calculator, the console is also a required result surface. Use the mod's structured SLF4J logger, not `System.out`: one bounded terminal summary per admitted Calculate request and, while the PoC console-composition option is enabled, one bounded product row per successful stream. The summary includes opaque correlation IDs, status, model/dataset revisions, problem size, degree-of-freedom result, initialization, iterations, queue/wall/CPU time, all convergence-residual families, compact flow/duty/temperature results, a result digest, and warning codes. Product rows include flow, temperature, `T5/T50/T95`, and the stable-ID-ordered pseudocut composition vector. Expected validation/numerical failures use typed, rate-limited records without stack traces; unexpected invariant failures use `ERROR`. Never log per tick or emit unrestricted packet, NBT, player, world, solver-array, or iteration data. The exact fields, levels, privacy limits, and console acceptance tests are defined in [the milestone plan](./MILESTONE_1_CRUDE_DISTILLATION_POC.md#3-console-output-contract).

Acceptance suite:

1. Fake-clock unit tests with scripted costs and exact expected cadence/token changes.
2. Property tests: at most one in-flight job, bounded outstanding work, no ledger consumption before valid commit.
3. Cheap-versus-expensive fairness: observed service shares within about 10% of weights.
4. Step load: slow within two policy windows, no executor backlog, recover only after ten healthy windows.
5. Under supported load, deadline misses below 0.1%; otherwise an explicit degraded state appears.
6. Inject unload, disassembly, data reload, save, and shutdown in every state; zero stale commits or lost mass.
7. As a scheduler-integration test, compare fixed 1/2/5/10/20-second and adaptive schedules with a fine-step scientific reference.
8. Benchmark integrated and dedicated servers on 2-, 4-, and 8-core systems with forced GC and competing mods.
9. Multi-hour soak: stable heap, tokens, queue depth, ledger size, and thread count.
10. Main-thread scheduler overhead p99 below 2 ms/tick, with the snapshot and commit sub-budgets each near 1 ms.
11. Hydraulic regressions: positive viscosity, laminar/turbulent pressure-drop checks, mass-balanced junctions, pump work/enthalpy closure, and deterministic cavitation/derating faults.
12. Persistence round trip: energy basis, state specification, constraint, IDs, amounts, and ledger survive save/load without reinterpretation.
13. Capability transactions: repeated `SIMULATE` calls are side-effect free; `EXECUTE` records exactly the returned amount and invalidation rebuilds topology once.
14. Create adapter: changes refresh cached stress/capacity and generated rotation on the server thread; SU/RPM-to-W calibration and overstress behaviour are reproducible.
15. Lifecycle/sync: a failed reload retains the current catalog/digest; chunk or level unload clears only affected level caches and parks plants while preserving per-server catalog/executor state; full server stop clears all server-wide objects; join/reload synchronization exposes exactly the active digest.
16. Crude-column regression: reproduce the literature 25-cut shortcut case offline, calibrate the 10–12-cut game reduction on a declared subset, and test held-out operating perturbations against a separately implemented 25-cut full-stage model oracle with documented generated properties. Compare product flows, `T5/T50/T95`, temperatures, duties, and residuals; treat this as model-to-model validation and use external plant data only for physical plausibility. Every benchmark report must include a source-model versus in-game-model table before warm/cold server timing is accepted.

## 15. Suggested implementation sequence

The active dependency-ordered sequence is maintained in [Milestone 1: crude-distillation calculator PoC](./MILESTONE_1_CRUDE_DISTILLATION_POC.md#7-dependency-ordered-work-plan):

1. freeze the executable scientific, GUI, result, and diagnostic contract;
2. bootstrap NeoForge and lock the Create/JEI/KubeJS compatibility set;
3. complete and validate the 10–12-cut scientific dataset;
4. implement and test the headless Peng–Robinson/enthalpy property kernel;
5. implement the steady MESH main-column solver and direct liquid side draws;
6. validate conservation, robustness, paper-versus-PoC differences, and real solver cost;
7. build the placeholder block and dummy GUI in parallel after the contracts stabilize;
8. join them through bounded payloads, one server-owned worker, version-checked commits, persistence, and console reporting;
9. add narrow Create, JEI, and KubeJS adapters and pass the integrated release gate.

This click-triggered path intentionally omits the cadence scheduler and plant boundary ledger. After it passes, the water/MEG and gas-compressor fixtures introduce physical mixture transfer, pressure networks, Create kinetic coupling, and continuous adaptive updates before the solver is promoted to a real operating column.

## 16. Final coding assessment

Multithreaded asynchronous full updates are feasible for the property kernel and are the right architecture for this mod. Full refinery capacity remains a benchmark hypothesis until the equilibrium-cell/recycle solver is profiled in a modded dedicated server. The robust formulation is not “run whenever a core is free”; it is “run as often as quality permits within a measured, bounded compute budget.” With plant-level granularity, one or two workers, immutable snapshots, server-thread commits, internal numerical substeps, and visible degradation, a one-to-twenty-second adaptive cadence is a credible implementation target.

Milestone 1 is deliberately event-driven: it submits one bounded job only after a valid Calculate request and therefore needs no adaptive scheduler. Its full-stage solve and console-visible diagnostics provide the measurement and correctness foundation from which the later reduced, continuously updated column can be designed.

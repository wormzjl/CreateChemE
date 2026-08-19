# Equipment code architecture and delivery roadmap

## 1. Decision

CreateChemE will remain a **single Gradle module with enforced package boundaries** until the scientific API is stable. The scientific engine stays plain Java and knows nothing about Minecraft, NeoForge, Create, screens, NBT, or packets. Minecraft code samples the world into immutable requests and presents committed results; it does not contain thermodynamic equations.

The thirteen planned equipment identities are fixed in `science.equipment.EquipmentType`. Their serialized names are append-only and never depend on enum order. This is the initial catalog, not thirteen empty implementations.

The codebase should minimize files by sharing cohesive infrastructure, not by merging unrelated responsibilities. In practice:

- one public file for each stable cross-package contract;
- family-level implementation files may contain small private or package-private helpers;
- do not make a block, block entity, menu, packet, and screen class for every machine by default;
- split a file when it has separate reasons to change or becomes difficult to test in isolation;
- do not repeat flash, enthalpy, vessel-balance, heat-transfer, or nonlinear-solver code in equipment classes.

The current crude-column calculator remains a temporary vertical slice. It is not the final distillation-column structure.

## 2. Dependency direction

Dependencies point only downward:

```text
client / compatibility adapters
             |
game content, networking, persistence, server lifecycle
             |
plant graph, immutable snapshots, ledgers, solve coordination
             |
scientific equipment models and numerical algorithms
             |
material, property, transport, and reaction contracts
```

The target package layout is:

```text
com.wormzjl.createcheme
|- science/                         # JDK-only; no game imports
|  |- identity/                     # stable component/model/port identifiers
|  |- diagnostics/                  # typed status and fault contracts
|  |- material/                     # streams, holdups, phases, conserved energy
|  |- property/
|  |  |- api/                       # TP, PH, PS and UV operations
|  |  |- eos/                       # Peng-Robinson and later packages
|  |  |- flash/                     # two- and three-phase equilibrium kernels
|  |  |- caloric/                   # enthalpy, internal energy, entropy, Cp
|  |  `- transport/                 # viscosity, density and hydraulics
|  |- reaction/                     # stoichiometry, kinetics and integrators
|  |- numerics/                     # roots, nonlinear solves, convergence
|  `- equipment/
|     |- api/                       # common immutable solve contract
|     |- storage/
|     |- rotating/
|     |- thermal/
|     |- separation/distillation/
|     |- reaction/
|     `- adsorption/
|- data/                            # JSON/KubeJS drafts -> validated science data
|- plant/                           # graph, snapshot, ledger and recycle solve
|- game/
|  |- calculator/column/            # temporary Milestone-1 feature
|  |- equipment/                    # shared structures, controllers and ports
|  |- plant/                        # server-owned managers/executor lifecycle
|  |- network/                      # bounded DTOs and codecs
|  `- persistence/
|- registry/
|- client/
`- compat/{create,jei,kubejs}/
```

This is a target dependency map, not a request to create every directory immediately. A package is introduced with its first real contract or implementation. A later `science-core`/`neoforge` Gradle split is useful only after those contracts stabilize.

## 3. Scientific entry points

The important algorithms must be discoverable without navigating game code:

| Need | Stable entry point | Implementation location |
|---|---|---|
| Evaluate or flash a mixture | `PropertyPackage` | `science.property.*` |
| Evaluate viscosity/density | `TransportPackage` | `science.property.transport` |
| Advance reactions | `ReactionModel` | `science.reaction` |
| Solve one unit operation | `EquipmentModel` | `science.equipment.<family>` |
| Solve connected equipment/recycles | `PlantSolver` | `plant.solve` |
| Submit game work safely | `SimulationCoordinator` | `game.plant` |

The common equipment request is conceptually:

```text
EquipmentSolveRequest
  = immutable definition
  + immutable prior state
  + ordered inlet streams
  + immutable controls/boundaries
  + property/reaction context
  + deadline and numerical limits

EquipmentModel.solve(request)
  -> EquipmentSolveResult
     (new state, outlet streams, heat/shaft/boundary ledgers, diagnostics)
```

Definitions, controls, state, and results are immutable value objects with explicit SI units in field names. Untrusted GUI/JSON/network drafts are not scientific state: adapters validate and compile them first. Store component molar amounts as the authoritative composition; derive mole and mass fractions for presentation. Results are published only after invariant validation.

Use stateless final strategy implementations and composition, not a mutable `BaseMachine` inheritance tree. An immutable registry maps a stable model ID to its strategy and rejects duplicate IDs at bootstrap. Datapacks and KubeJS select registered algorithms and tune validated parameters; they cannot inject arbitrary Java equations.

## 4. Equipment catalog and shared kernels

The plain **reactor** is defined initially as a tubular/plug-flow reactor because a stirred-tank reactor is separately required. A future batch mode can be another definition or model ID without changing that identity.

| Final structure | Family | Initial scientific model | Reused kernels |
|---|---|---|---|
| Storage drum | Storage | Dynamic holdup; rigid `UV` or pressure-constrained state | Vessel balance, flash |
| Pump | Rotating | Liquid pressure rise, efficiency and `PH` outlet; reject/derate vapour inlet | Transport, hydraulics, enthalpy |
| Compressor | Rotating | Vapour `PS` ideal outlet followed by efficiency and `PH` actual outlet | EOS, entropy, enthalpy |
| Heat exchanger | Thermal | Two coupled streams; effectiveness or `UA`, duty and approach limits | Heat-transfer and enthalpy solve |
| Boiler | Thermal | Admitted duty or outlet-quality target through `PH` flash | Thermal boundary, flash |
| Furnace | Thermal | Admitted heat or outlet-temperature target with coking/temperature limits | Thermal boundary, reaction package later |
| Reactor | Reaction | Segmented tubular/PFR material and energy balances | Reaction integrator, property package |
| Gas-liquid separator | Separation | Vapour/hydrocarbon-liquid equilibrium and two outlets | Flash, vessel balance |
| Three-phase separator | Separation | Vapour/hydrocarbon-liquid/aqueous split | Three-phase flash, vessel balance |
| Air cooler | Thermal | Ambient-dependent `UA`, fan work and minimum approach | Heat-transfer kernel |
| Distillation column | Separation | Steady MESH first; reduced quasi-steady cells later | Flash, enthalpy, nonlinear solver |
| Pressure-swing adsorber (PSA) | Adsorption | Multi-bed cyclic state and loading equilibrium | Cycle scheduler, adsorption package |
| Stirred-tank reactor | Reaction | Dynamic well-mixed CSTR component and energy balances | Vessel balance, reaction integrator |

The shared kernels are deliberately smaller than complete machines: vessel balances, phase equilibrium, heat transfer, hydraulics, reaction integration, and numerical convergence. Equipment models compose them and own equipment-specific boundary conditions.

## 5. Minecraft structure strategy

Equipment identity and scientific identity are related but not the same object. A data-driven `equipment_structure` definition should eventually specify its block pattern, controller position, allowed ports, rendering metadata, and referenced scientific definition.

Use shared game infrastructure where behavior is genuinely common:

- one general process-equipment controller block entity for common inventory, state, revision, and solve lifecycle;
- one port implementation parameterized as material, energy, shaft, control, or exhaust;
- one structure matcher and common diagnostics/menu components;
- distinct registered blocks/items and models so each machine remains recognizable to players;
- specialized controllers or screens only for materially different state, such as a tray column or cyclic PSA.

Create integration is an adapter. It samples speed/stress/heat on the logical server thread into the request and applies a committed operating fraction back on that thread. A worker never queries a level, block entity, capability, kinetic network, menu, or packet context.

## 6. Calculation execution and logging

CPU-bound property, reactor, and column work uses one server-owned coordinator and an owned platform-thread executor. The coordinator owns an `ArrayBlockingQueue` of eight admitted jobs; the executor has one named worker initially and direct handoff with no second hidden scheduling backlog. Admission fails fast when the ready queue is full, and each equipment or plant has at most one in-flight job. Two workers are allowed only after dedicated-server profiling. Do not use the common pool, virtual threads, `CallerRunsPolicy`, an unbounded queue, or one executor per block.

The lifecycle is:

1. On the logical server thread, authorize the request, validate bounds, and deep-snapshot immutable inputs with server epoch, block/plant identity, input revision, topology revision, dataset revision, and job token.
2. On a worker, run pure arithmetic only. Iterative kernels check a deadline/interruption flag at bounded intervals.
3. Return an immutable candidate result to a bounded completion path.
4. On the logical server thread, validate scientific invariants and commit atomically only if every identity/revision/token still matches.
5. On unload, replacement, reload, or stop, invalidate the token. On server stop, stop admission, cancel jobs, interrupt owned workers, wait for a bounded interval, and discard server references.

Every admitted calculation produces structured console evidence while `enableCalculationLogging` is true (default). Reuse the current configuration switch for all equipment during development:

- `INFO`: one bounded terminal record with request/equipment/model/data IDs, status, revisions, problem size, timing, residuals, result digest and warnings;
- `INFO` during the PoC: bounded product/output rows needed to check results;
- `WARN`: expected numerical failure, queue rejection, timeout or stale discard without an exception dump;
- `ERROR`: unexpected invariant or lifecycle failure, even if routine logging is disabled;
- no per-tick, unbounded iteration, raw NBT, player identity, world seed, or unrestricted composition logging.

Log the worker CPU/wall time separately from queue wait and server-thread snapshot/commit time. A delivery failure after a valid commit is a transport error; it must not rewrite authoritative equipment state as a failed calculation.

## 7. Review of the current PoC

This review used the installed Effective Java core and concurrency guidance. The good foundation is worth preserving: `science` currently has no Minecraft imports; scientific inputs/results are mostly immutable and defensively copied; the dummy is clearly labelled; requests are server-authoritative; payload/result sizes are bounded; logging is configurable and enabled by default; and tests cover validation, conservation, determinism, immutability, and finite boundary cases.

| Priority | Current mismatch | Decision |
|---|---|---|
| Fixed now | `RefluxMode.ordinal()` was sent on the wire | Protocol 3 uses an explicit append-only serialized name and rejects unknown names |
| Fixed now | A reply exception after success could mark the committed block failed | Delivery is isolated after commit; a delivery error is logged without changing committed state |
| High, before real solver | `ColumnNetwork` calculates synchronously in the payload handler | Replace with the bounded per-server coordinator above before connecting PR/MESH |
| High, before real solver | Block persistence stores only status/revisions/short digest | Persist canonical accepted input and the full last valid bounded result; reload an interrupted job as idle/dirty |
| High | `ColumnSimulation` combines assumptions, assay, validation, dummy allocation, digest and all DTOs | Extract behavior-preservingly as the real property/column APIs land; retain a thin compatibility facade during migration |
| High | `ColumnNetwork` combines authority, solve orchestration, mapping, logging, payloads and codecs | First extract calculation service/coordinator; then view mapper/reporter when another equipment UI reuses them |
| High | Public result constructors admit inconsistent/non-finite scientific states | Add solver-owned factories and one invariant validator before publication/commit |
| Medium | Several records have 10–24 same-primitive parameters | Group feed, geometry, operating, thermal and stream values into named immutable records |
| Medium | Result transport converts closed phase/status/rate values to arbitrary strings | Add small typed view enums with stable IDs when the generic result envelope is introduced |
| Medium | Result digest includes presentation labels/detail | Introduce a versioned canonical digest over stable IDs, numeric scientific state, diagnostic codes, and model/data revisions |
| Medium | Model, payload and GUI limits are duplicated | Keep separate protocol safety caps but assert model limits fit them; expose model capabilities to the UI |
| Deliberate temporary state | The numerical result is a conservative dummy and ignores genuine stage physics | Keep the placeholder badge and logs until the production property/MESH implementation passes headless benchmarks |

The two large files should not be split merely to move lines. Use a behavior-preserving seam: first define validated common stream/property/equipment contracts, then move the validator, dummy solver, digest, view mapper and reporter behind those contracts. Avoid scaffolding thirteen empty solver classes.

## 8. Verification gates

Every implementation stage ends in this order:

1. plain-Java unit and invariant tests;
2. numerical conservation/energy/regression tests where applicable;
3. malformed-input, boundary and deterministic-repeat tests;
4. concurrency tests with latches/futures and bounded timeouts, including queue-full, cancellation, stale commit and shutdown; never rely on sleeps for correctness;
5. `clean build`;
6. dedicated-server smoke test when common/server code changed;
7. `runClient`, left open for manual testing.

For every crude-distillation benchmark, report the paper model and in-game model side by side: pseudocomponents, main/stripper stages, side strippers, pumparounds, steam, pressure, specifications, predicted flows, product `T5/T50/T95`, duties, residuals, and runtime. A fast model is not accepted if this comparison is absent.

## 9. Dependency-ordered path forward

### Gate A — stabilize the working calculator

- Preserve the current pre-filled, table-based GUI and structured logs.
- Complete canonical input/result persistence.
- Move Calculate behind the bounded server-owned worker with revision-checked commit.
- Add codec compatibility, malformed packet, queue-full, cancellation, unload and stale-result tests.

### Gate B — common scientific foundation

- Compile component/pseudocut data into immutable dense contexts.
- Implement and test `TP`, `PH`, `PS`, and `UV`, Peng–Robinson, caloric curves, phase equilibrium, viscosity and density.
- Define validated material stream/holdup, diagnostics, equipment request/result, and energy/shaft/boundary ledgers.
- Benchmark production kernels rather than the separate synthetic benchmark.

### Gate C — real Milestone-1 crude column

- Reproduce the published 25-pseudocomponent reference offline.
- Regress the 10–12-cut game package and implement the bare-column MESH solver with total condenser, partial reboiler and direct liquid side draws.
- Compare paper versus in-game results and profile cold/warm p95 cost in an integrated and dedicated server.
- Replace the dummy only after closure, robustness, benchmark and timeout gates pass.

### Gate D — continuous material and rotating equipment

- Implement storage drum -> pump -> receiver, then compressor -> gas line -> receiver.
- Add mixture storage/capability transactions, boundary ledger, pressure loss, work/enthalpy closure and Create kinetic adapter.
- Introduce adaptive cadence only after event-driven worker lifecycle is proven.

### Gate E — thermal and equilibrium separation families

- Heat exchanger, boiler, air cooler and furnace.
- Gas-liquid and three-phase separators.
- Reuse the same property, flash, heat and ledger contracts; build a headless vertical slice before each Minecraft structure.

### Gate F — reactions

- Tubular/PFR reactor, then stirred-tank reactor.
- Add compiled stoichiometry/kinetics, stiff-safe integration where needed, and reaction-energy double-count tests.

### Gate G — continuous column and PSA

- Promote the validated column kernel into a virtual-cell, continuously operating structure with side strippers, pumparounds and controls.
- Implement PSA last: it needs adsorption data, multi-bed cyclic state, valve sequencing, scheduling and a separate benchmark suite.

At each gate, add only the packages and files that contain real, tested behavior. This keeps the codebase small while preserving clear scientific and game boundaries.

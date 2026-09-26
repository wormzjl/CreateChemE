# Implementation plan: Simulation synchronization engine and fluid networks

Finalized 2026-09-15 after discussing both revisions of the [design review](SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN_REVIEW.md). This is the v1 scope and behavior baseline. The [implementation work order](SIMULATION_ENGINE_AND_FLUID_NETWORK_WORK_ORDER.md) defines dependencies, testable checkpoints, and release gates. Finalizing the plan does not imply that code, scientific qualification, or benchmarks have passed: those are explicit milestone deliverables. Later user decisions recorded here take precedence over the historical review recommendations.

## 1. Summary and agreed behavior

Build a persistent scientific simulation independent of Minecraft block entities (TEs). Minecraft supplies configuration and topology changes; the simulation produces validated snapshots that loaded TEs display.

Extend the existing bounded async runtime instead of replacing it. It already provides immutable worker inputs, bounded admission, cancellation, server lifecycle management, and stale-result protection.

The first release will use:

- A configurable **5-second initial update interval**, preserving process speed when that interval changes.
- Smaller numerical time steps inside each update when needed for stability.
- Retained simulation-time debt for intervals that neither the full solver nor an accepted fallback advances, with bounded catch-up work.
- Simulation of registered networks across unloaded chunks while the server runs; no offline catch-up.
- Homogeneous multiphase transport, including pipe friction and elevation.
- Bulk withdrawal of all reservoir phases together.
- Immiscible free water, hydrocarbon liquid, and a shared vapor phase using the separate-water hybrid model.
- Corrected hydrocarbon liquid volumes and physical liquid compressibility; liquid-full vessels may increase in pressure.
- Self-powered pumps with a flow target and a maximum **added** pressure, including zero-flow shutoff against excessive opposing pressure.
- Recycle paths through reservoirs are allowed. Reject pumps in cycles made only of elements without stored fluid; pressure valves have no structural bypass prohibition.
- Equipment separates hydraulic networks through built-in feed/product buffers and timestamped transfer ledgers.
- A fully coupled implicit solver for flow, pressure, temperature, composition, and phase changes.
- A conservative approximate-flow fallback for at most three update intervals, with earlier shutdown if its validity checks fail.
- Fallback remains wall-time-triggered; hardware-independent trajectories are not promised in adaptive/degraded operation. Fixed-settings tests isolate reproducibility from this intentional behavior.
- Pending equipment products can be delivered partially; blocked delivery alone does not freeze the receiving hydraulic network.
- Shared worker access without a reserved fluid worker or strict fluid-first priority in v1.
- Correctness and performance validation at **100 reservoirs** before committing to larger factory targets.

The existing V3 column remains a **GUI calculator only** through the shared execution service. Applying its last result to actual feed, automatic re-solving, column-solver optimization, and reaction kinetics are out of scope. A test-only fixed-split module exercises equipment transfers without invoking V3.

Other v1 non-goals: pipe inventory and filling delay, phase slip, choking/sonic flow, detailed cavitation, dissolved water or hydrocarbons in the opposite liquid phase, salts/emulsions, phase-selective outlets, Create contraptions, offline catch-up, and a Create/vanilla/other-mod fluid capability bridge. All transfer uses the custom fluid system.

## 2. Design A — Synchronization engine

### Ownership and execution

Introduce three layers:

| Layer | Responsibility |
|---|---|
| Minecraft adapter | Accept configuration/topology events, manage persistence, update loaded TEs and clients |
| Simulation coordinator | Track dependencies, simulation time, caches, scheduling, deadlines, and commits |
| Scientific modules | Calculate from immutable inputs without accessing Minecraft objects |

The server thread owns authoritative simulation state. Workers own temporary numerical workspaces and return immutable proposals. Only the coordinator may commit proposals.

Reuse `BoundedCpuSolveService`; generalize the command/result protocol in `ProcessSolveServices` beyond columns.

Use one bounded pool of platform threads. Different networks, columns, and independent module jobs may run concurrently. Do not allocate a thread per machine or a pool per module.

Default worker sizing should reserve two logical processors where possible, use at least one worker, and cap automatic sizing at eight. Preserve an explicit server override. Increase the existing configurable worker cap accordingly. This sizing is separate from worker allocation: **all calculation families share the pool**, with no dedicated fluid worker and no strict fluid-first policy. Long column jobs can delay fluid work; warm starts and fallback must be measured before promising otherwise.

The coordinator owns a bounded ready-list of island/module identities and feeds the execution service only when worker capacity is available. Coalesce repeated readiness for the same owner; retain time debt and pending events in authoritative state. Use one island per job owner so cancellation and deadlines remain independent. Do not submit every island into the service's small ready queue each round, and do not batch unrelated islands into an uncancellable combined job. Dispatch fairly using ready age and round-robin service across families. Queue waiting is reported separately from numerical nonconvergence.

Workers must never block waiting for child jobs on the same pool. Independent calculations inside a large module can later become coordinator-scheduled batches; small flashes remain local calls to avoid scheduling overhead.

### Dependencies and the commit boundary

A **simulation island** is a group of calculations whose state changes must commit together to conserve material or energy.

An island is a **connected hydraulic component**. Equipment modules are **separators**, not connections that join their feed and product networks into one hydraulic solve. Built-in buffers are ordinary finite-volume reservoir nodes on their respective networks; a module transfers between them at its own cadence.

Module dependencies form an execution graph:

- Independent branches execute concurrently.
- Within an island, dependent branches use the same candidate interval and all coupled conservation, equilibrium, and hydraulic equations are solved together.
- Across equipment boundaries, dependencies are timestamped buffer withdrawals and product deliveries; they do not create one global Newton solve.
- Reservoir and junction property evaluations are part of the coupled residual calculation, not repeated independent black-box flashes in a sequential coupling loop.

An island's inventory, energy, hydraulic device state, and flow history commit atomically. A failed full solve may be replaced only by a separately validated fallback proposal for the same interval. If neither succeeds, the entire island retains its committed state.

### Equipment buffers and transfer ownership

Future equipment has built-in feed/product tanks identified by `(equipment identity, buffer role)`. Each belongs to the hydraulic network connected to that port. Capacity, working-inventory limits, start/stop thresholds, and contents have a home in the equipment definition and GUI; buffers are not extra placeable block types in v1.

Use working mass in kilograms as the v1 buffer reservation basis, separate from the physical vessel volume. Define occupied mass plus reserved pending mass against a configurable working-mass limit. Finite output storage is an equipment operating limit; it does not impose the rejected rule that every liquid-full reservoir automatically blocks inflow. Delivery converts reserved mass into occupied mass rather than freeing the same capacity twice. The receiving solve still checks pressure and phase feasibility. Standalone reservoirs have physical volume; this extra operating limit belongs to equipment buffers.

Default equipment behavior is to stop processing when feed is unavailable or output capacity cannot be reserved, then resume using start/stop hysteresis. This is a replaceable per-equipment policy. Future equipment may throttle, transmit inlet pressure to an outlet, or behave differently, but those policies must provide explicit flow, energy, and availability contracts; they cannot create an undeclared hydraulic connection across islands.

The module boundary uses the following transaction:

1. Read only committed, timestamped feed state and obtain output-capacity reservations for the proposed module interval.
2. Propose a time-distributed feed withdrawal over `[t0, t1]`, with component and energy rates and an availability constraint. Integrate it with the feed island; do not remove the whole amount retrospectively at `t1`.
3. On successful feed commit, atomically record the actual withdrawal, advance module state, and create uniquely identified pending product inputs with delivery times. If the feed interval fails, none of those transfers commit. Multiple feed islands first transfer their accepted withdrawals into a module-owned input ledger; processing consumes only material actually owned there.
4. A product island accepts the feasible portion of each due input in its own successful interval. That portion moves from the pending ledger to the buffer exactly once; the remainder stays pending under the original transfer identity with an incremented delivery revision. A failed interval commits no accepted portion and leaves the prior remaining amount unchanged.
5. Persist all ownership changes, delivery identities, reservations, and module state with the same committed save snapshot. Do not count a pending transfer both as module holdup and as pending inventory.

For a nonreactive module, global component accounting is `island inventories + pending-transfer inventories + module-owned holdup`, reconciled against external sources, sinks, and destruction. Apply the equivalent energy ledger, including work, heat, and gravitational potential energy. Reservations alone are not material. A zero-internal-holdup steady-state module deposits the component amounts it withdraws; a future reactor may have explicit holdup and reaction source terms.

Pending input records are bounded both by storage reservations and by record count. Capacity exhaustion stops the default module. Exhausted event/ledger capacity produces an explicit paused/error state; never drop material or create unlimited hidden storage.

### Partial delivery and deferral

Due means eligible for delivery, not an unconditional injection. Solve a bounded delivery fraction with the receiving interval's availability and physical constraints. Scale the record's component amounts and energy by the same accepted fraction; subtract that exact ledger entry from the remaining amounts. Do not selectively deliver a convenient phase or recompute the remainder from an already rounded fraction.

For example, a 100 kg record accepted in a 20 kg portion leaves 80 kg pending with its matching composition and energy. Persist the original identity, remaining amounts, delivery revision, and reservation after every committed portion. Retries and restart cannot deliver a portion twice.

If no portion is feasible, defer the input and continue the receiving island with zero delivery if its other equations are valid. A blocked product delivery is not itself numerical nonconvergence. If a trial injection invalidates the solve, reduce its delivery bound, including a zero-delivery attempt, before classifying the island as failed. Being liquid-full alone is not infeasibility: pressure may rise through qualified physical compression.

Order pending inputs by original eligibility time and stable transfer identity. Bound delivery work per interval, preserve order for competing feasible records, and allow an individually blocked record to remain pending while other feasible inputs and normal flows progress. Once a delivery record's amount is known, its deferral does not retain an unresolved-production time fence: later portions enter only current/future receiving intervals, never a previously committed interval.

### Module time alignment

A module may not read future feed state or deposit into an interval the receiver has already committed. Hold the relevant feed boundary until its source has reached `t0`. Install delivery-event fences before a receiver advances past a planned delivery time; release or resolve the fence when the producing transaction succeeds or is cancelled.

A lagging output island need not participate in the feed solve: products can wait in its pending-input ledger within reserved capacity. A module that explicitly reads output pressure or other feedback must also wait for that input's required timestamp. Thus buffering separates numerical solves without removing causal dependencies. At an unresolved promised-delivery time, an upstream stall can propagate through dependent modules; independent branches continue. Show waiting-for-feed, waiting-for-delivery, and output-blocked states, including the responsible upstream island/module and its lag.

Cadence is an explicit module parameter. The test module uses a configurable cadence, with 15 seconds as its default against the hydraulic 5-second default. Cyclic module networks must start from committed buffer snapshots, announce their next delivery/zero-delivery horizon before receiver advancement, and resolve an unavailable-feed interval as zero production. Test this bootstrap so time fences cannot create a circular wait for products that have not yet been scheduled.

Buffer sizing is both a gameplay and numerical parameter. Start with working residence time of roughly 3–5 module cadences as a sizing heuristic, then validate it. Product response is sampled at the module cadence; small buffers can cause stop/start cycling and control hunting. Test loops with small storage rather than treating this heuristic as a stability guarantee.

### Complete update workflow

```text
Collect timestamped changes
    → identify affected islands and dependencies
    → compare input/state/property revisions
    → classify required work
    → reuse valid cached calculations and warm starts
    → schedule ready calculations
    → solve coupled island physics / prepare module transfers
    → if the full solve fails, try the bounded approximate fallback
    → validate the selected proposal's accuracy policy, conservation, and revisions
    → close the round when jobs finish or reach their deadlines
    → commit successful islands and associated transfer-ledger transactions
    → publish snapshots to loaded TEs
```

Diff classification distinguishes:

- **Presentation changes:** update the UI only.
- **Control changes:** invalidate affected device behavior and downstream dependencies.
- **Topology changes:** rebuild affected graph regions and bypass diagnostics.
- **Inventory or energy changes:** invalidate dependent thermodynamic and hydraulic results.
- **Property changes:** invalidate only calculations consuming those property revisions.
- **Time advancement:** schedule active dynamics even when configuration is unchanged.

An unchanged GUI is not evidence that a running plant needs no calculation. Flow, heat exchange, or reactions continue changing its state.

### Cache policy

Maintain separate caches for:

1. Compiled topology and sparse matrix structure.
2. Thermodynamic results.
3. Transport properties.
4. Numerical warm starts.
5. Last committed results for display and recovery.

Keys include component basis, relevant scientific fingerprints, controls, topology revision, and physical inputs. Time-dependent results also include their starting state and interval.

Use exact validated inputs for result reuse. Nearby previous states may provide initial guesses, but must not silently substitute for a new solution.

For dynamic networks, expect few exact-key hits. Prioritize warm starts, accepted phase/device states, property derivatives, and compiled matrix structure. Store the last fully converged solution separately from the latest accepted approximate state. A useful intermediate Newton iterate can seed another attempt but is never publishable merely because it looks plausible.

Bound caches by memory and evict derived entries. Authoritative inventory and energy are never evictable cache entries.

A network can sleep when it has no driving forces, external inputs, or pending dynamics. Wake it on dependency changes. A steady-flow network can reuse coefficients, but must still account for transported material and energy.

### Deadlines and publication

“Wait for all calculations” means a nonblocking coordinator barrier. Minecraft ticks continue normally.

At the round deadline:

- Commit completed, validated full or approximate island proposals, with their quality status.
- Retain the previous committed state of islands with neither an accepted full solve nor an accepted fallback.
- Cancel obsolete work cooperatively.
- Keep only the unadvanced duration as time debt; never replay accepted approximate transfers.
- Discard late results belonging to a closed attempt.
- Log the island, module, simulated interval, elapsed time, and failure reason.

Debug chat should be opt-in and rate-limited, with repeated failures summarized.

A timeout does not prove that a worker stopped. Retain its execution slot until termination is observed; never create replacement threads without bounds.

### Failed-solve recovery and approximate flow

First warm-start the full solve from the last good converged solution, refreshed against the latest committed inventories and the controls effective for this interval. If it cannot converge within its wall-clock soft budget, enter a bounded approximate-acceptance mode of the same solver, using the last converged phase regime and local property values/derivatives. Share residual assembly, material/energy ledgers, device constraints, and validation; do not build an unrelated second model. Recompute flows and transfers for current inputs; do not copy old flow rates. Hold the last accepted result only when neither mode can produce a valid proposal.

The reduced calculation must preserve exact component and energy accounting to the same balance tolerances as the full solver, including module transfer ledgers. Its pressure, temperature, and equilibrium predictions may be approximate. Solve current continuity and availability constraints together; independently clipping each outgoing pipe is insufficient.

A first damped Newton iterate is only a candidate. Reconstruct its conservative inventory/energy update, then recheck the candidate's physical and approximation residuals. Acceptance is conditional, not an unconditional accept-first-iterate shortcut. Qualify fallback on smooth-state budget misses; refusal during phase changes or strongly nonlinear failures is expected behavior, not grounds to remove those safeguards.

Fallback acceptance requires nonnegative inventories, finite positive physical properties, supported thermodynamic conditions, feasible device states, and bounded error against direct property/residual checks around the last full solution. Refuse fallback when the topology or material basis changes, a phase appears/disappears, a device mode would need an unsupported switch, or the candidate leaves the approximation's qualified range. A full solver failure alone is not permission to relax conservation or extrapolate outside property limits.

Allow at most **three consecutive update intervals** of approximate operation. Capture the configured update duration when degradation begins and cap the total approximate simulated duration at three times that value; changing cadence or retrying cannot extend the grace period. Smaller catch-up intervals do not reset either bound. Only a successful full solve ends the degraded episode. Stop earlier if any validity check fails; after expiry, hold state, retain remaining debt, and keep bounded full-solver retries scheduled.

Each fallback interval commits atomically, advances simulation time, and becomes the starting inventory for later work. Full-solver recovery validates the current state; it does not replay approximate intervals or revert their material transfers. Persist quality status, the last full-solve anchor, and remaining fallback allowance across restart.

Reserve part of an island job's wall-clock budget for fallback using a cooperative soft deadline for the full solver beneath the existing hard cancellation deadline. Both calculations run in the shared CPU pool. If hard cancellation or pool congestion leaves no execution opportunity, retain state and debt; never run fallback on the Minecraft thread to hide saturation. Measure fallback latency and error before claiming uninterrupted flow.

Keep wall-time-based triggering as the chosen v1 policy. A slower or busier host may commit an approximate result where another host commits a full result. Reproducibility tests disable cadence adaptation and fallback, use the same numerical step schedule, and avoid wall-clock cancellation. Production adaptive/fallback trajectories are tested against qualified accuracy bounds rather than bitwise equality. Inject a clock in deadline tests so triggering is testable without timing races.

### Three separate clocks

| Quantity | Meaning |
|---|---|
| Update interval | How often the coordinator requests fresh results |
| Numerical step | How far one stable integration step advances physics |
| Wall-clock deadline | How much execution time an attempt may consume |

Changing a 5-second update interval to 10 seconds advances 10 seconds of physics per update. It must not double plant throughput.

Use elapsed server ticks as the simulation clock: 20 ticks represent one simulation second. Server pause stops advancement.

Adapt the update interval using smoothed worker utilization, queue age, execution time, deadline misses, and server tick cost:

- Start at 5 seconds; default adaptation range 1–20 seconds.
- Increase by 25% after three consecutive overloaded rounds.
- Decrease by 10% after ten comfortably under-budget rounds.
- Do not increase it solely because a numerical solver fails to converge.
- Measure CPU time where available; distinguish calculation cost from queue waiting.

Larger batches reduce scheduling and repeated setup costs. They cannot eliminate the numerical work required by a rapidly changing system. Validate adaptation on both benign and stiff networks; do not claim it solves CPU overload or silently widen physical-error tolerances.

### Time debt and event ordering

Track committed simulation time per island. Retain debt as a duration, not a queue containing one job for every missed interval.

Catch-up runs under the same bounded pool and fairness rules. Serve other ready owners before a second attempt for the same indebted island. If no other owner is ready, use available worker capacity for consecutive debt intervals: close completed catch-up cohorts and dispatch again without waiting for the normal 5-second cadence. Keep the existing nonoverlap, commit, cancellation, and per-tick coordination bounds. Becoming ready must let another owner join at the next scheduling opportunity.

Do not cap debt by discarding elapsed simulation time. Debt is a duration; execution work, event history, and pending-transfer records have separate bounds. If required history cannot be retained, expose the bounded-resource paused/error state without silently rebasing clocks or writing away lost time. With fixed steps and events, debt changes when work runs, not the simulated result.

Configuration changes and topology changes carry simulation timestamps. Integration stops at event boundaries so new settings are not applied retroactively.

For a merge:

1. Record the connection event time.
2. Advance both old islands to that time using their prior topology.
3. Hold an island that reaches the boundary early.
4. Activate the merged graph once their clocks align.

Splits inherit the parent’s state and clock at the split event.

Expose merge/event waiting in the GUI and debug tool so a new connection awaiting clock alignment does not look silently broken. Equipment transfers use the separate module-time and pending-input contract above rather than merging hydraulic islands.

Persist pending events needed for catch-up. Coalesce repeated settings only when no simulated interval depends on the intermediate value. If retained history reaches its configured bound, report an explicit paused/error state rather than discard debt silently.

## 3. Design B — Fluid state, equipment, and numerical solver

### Reservoir state and thermodynamics

The conserved reservoir state is:

- Fixed volume \(V\).
- Component mole inventories \(n_i\), including water.
- Internal energy \(U\).

Pressure, temperature, phase amounts, and phase compositions are derived quantities.

For a rigid adiabatic vessel, enforce **UV equilibrium**: equilibrium at fixed internal energy, volume, and inventory. Inside a network step, these equations are rows of the simultaneous solve, not a separate black-box UV flash nested inside every hydraulic iteration.

The repo’s current TP/PH flashes are useful foundations, but do not provide this closure. Add a reusable thermodynamic boundary supporting:

- `flashTP`: source initialization and fixed-pressure/fixed-temperature boundaries.
- `flashPH`: transported-stream and junction states.
- `flashUV`: finite reservoir state.
- Equilibrium and caloric/volume residuals with temperature, pressure, and composition derivatives for the coupled solver.
- Phase volume, density, enthalpy, internal energy, and transport properties.
- Cancellation and structured convergence/domain diagnostics.

Require consistent \(u=h-Pv\) relationships. A liquid density model without pressure dependence is insufficient to determine pressure in a completely liquid-filled rigid vessel.

For v1:

- Use PR78 with a calibrated liquid-volume correction and consistent volumetric/caloric properties for hydrocarbons.
- Model free water as an immiscible pure liquid.
- Use the V3-style **separate-water hybrid**, not water as a newly mixed PR78 component requiring invented water–hydrocarbon interaction parameters. When free water is present, its vapor partial pressure follows saturation within the qualified hybrid-model range. When no free water remains, water inventory determines a subsaturated vapor contribution.
- Model water vapor as an ideal gas at its partial pressure: `P_water * V_vapor = n_water_vapor * R * T`. Use one shared vapor volume and `P_total = P_hydrocarbon + P_water`. The hydrocarbon/water closure must supply consistent joint residuals; no second gas volume and no independent pure-water PR vapor root are introduced.
- Track free-water-present versus subsaturated state explicitly. Hold that regime fixed within a Newton solve and change it only through the bounded active-set procedure. The shared vapor has one volume and a joint pressure closure; do not count water and hydrocarbon gas volumes twice.
- Neglect dissolved water, dissolved hydrocarbons in water, salts, and emulsions.
- Implement the required compressed-liquid water properties using IAPWS IF97 Region 1, together with the existing saturation relation and a reference-aligned water-vapor caloric model. Qualify the intersecting temperature/pressure domain; full IF97 coverage is not a v1 requirement, and hotter free-water states outside Region 1 are unsupported. Do not equate the broad IF97 validity range with the narrower validity of this hybrid mixture approximation. [IAPWS formulation](https://iapws.org/technical-guidance/release/IF97-Rev)

The new reservoir model must have its own scientific revision. Do not silently change V3’s established water or thermodynamic behavior.

A newly placed empty reservoir is an explicit empty state. Its temperature is undefined until filled; it cannot supply outflow. Zero inventory must not be passed through logarithmic EOS calculations.

### Liquid volume and full-vessel pressure

Calibrate a constant per-component PR volume translation against available reference liquid densities, with composition-weighted mixture translation. Keep the translation independent of temperature and pressure in v1, preserving the thermodynamic identities and derivatives consumed by the solver. The correction belongs in the physical property model, not just in the displayed fill level. Record calibration data, uncertainty, and a scientific revision; verify corrected liquid volumes, energy identities, and phase behavior against reference cases. Do not treat the review's approximate density-error range as a measured error for every supported material.

At fixed composition the constant shift does not change the PR derivative `dV/dP`. Isothermal compressibility also contains the corrected volume as a divisor, so it must be evaluated and checked explicitly; density calibration is not an independent calibration of pressure response. Check positive, plausible compression against reference values for at least three representative supported hydrocarbons, including a heavy liquid, over the intended domain. Water uses its separate IAPWS derivative tests. A future temperature-dependent translation would require re-derived caloric derivatives and new qualification; it is not a drop-in change.

Use the resulting physical liquid compressibility (and pressure-dependent IAPWS properties for water). A liquid-full reservoir can accept further material by becoming denser and increasing in pressure. Do not automatically vent, clamp pressure, or block flow solely because the vessel is liquid-full. The hydraulic solution determines whether the available pressure can drive additional inflow.

Handle stiff liquid-full equations using scaled residuals/unknowns, physical pressure/volume derivatives, safeguarded Newton steps, and adaptive time steps. Do not add an arbitrary artificial gas pocket or soften the liquid merely to make the solve converge. Nonpositive compressibility or a state outside the qualified property domain is a model/solve failure, not a reason to manufacture capacity.

The pump's maximum added pressure limits what that pump can drive; it is not a vessel pressure rating or an absolute outlet cutoff. Heating and other pressure sources can still raise vessel pressure. If no full or valid fallback solution exists within the property domain, hold the committed island state, retain debt, and report the limitation. Relief devices and structural tank failure are not added in v1.

### Energy references and future reactions

Reserve optional per-component chemical-energy offsets and an explicit energy-reference identifier/revision in the material schema. Preserve today's sensible-enthalpy convention for v1 and mark missing formation-energy data as **unavailable**, not a physical zero. Reaction models remain disabled until their required data is qualified.

Persist the reference identity used by every stored internal energy and energy-bearing transfer. A later reference change requires an explicit migration, including pending inputs and module holdup: for unchanged component identities, `U_new = U_old + sum(n_i * (offset_new_i - offset_old_i))`. Never reinterpret saved U under a replacement reference silently. Reserve and version these fields now without making complete formation-enthalpy sourcing a prerequisite for fluid networks.

### Transport properties

Use the approved reference-pressure viscosity approximation through a new transport adapter; do not relax validation in the existing catalog API.

For v1:

- Evaluate available pure-phase curves at their declared reference pressure.
- Respect their temperature limits.
- Use mole-fraction logarithmic mixing for hydrocarbon liquid viscosity.
- Use Wilke mixing for vapor viscosity.
- Use phase-volume weighting for the homogeneous mixture viscosity.
- Derive mixture density from phase masses and volumes.

These are documented modeling approximations. Missing data produces `PROPERTY_UNAVAILABLE`; it never means zero viscosity. GUI diagnostics identify estimated/reference-pressure properties.

Only qualified packages and crude presets are enabled for network use. Publish an explicit compatibility table covering density/volume calibration, liquid and vapor viscosities, water/steam support, and the intersecting validity range. The current catalog's partial coverage is not blanket network qualification. In particular, obtain and test water-vapor viscosity before enabling shared vapor containing water; its existing liquid-only curve cannot be used as vapor data.

### Graph model

Represent the plant using:

- Reservoir and boundary nodes.
- Zero-volume mixing junctions.
- Pipe, pump, and valve edges.
- Stable equipment identities and dimension/position bindings.

Retain every physical pipe in persistent topology, but compile compatible, unbranched pipe runs into hydraulic edges. Preserve length, elevation, diameter changes, fittings, and the mapping back to physical pipe positions.

Pipe blocks have no stored fluid inventory in v1. Their transport is quasi-steady within each numerical step. This omits filling delay, line packing, water hammer, and phase slip.

Disconnected graphs solve independently. Rebuild only affected graph regions after topology edits.

### Pipe pressure loss

Include pressure loss in v1:

\[
P_a-P_b+\Delta P_{\rm pump}
=
\rho g(z_b-z_a)
+
\left(f\frac{L}{D}+\sum K\right)
\frac{\dot m|\dot m|}{2\rho A^2}
+
\Delta P_{\rm valve}
\]

Use Darcy–Weisbach friction, a laminar relation at low Reynolds number, an explicit turbulent correlation, and a continuously differentiable (C1) transition. The laminar zero-flow limit must supply a finite, nonzero pressure-loss derivative; using only the turbulent `m * abs(m)` relation gives a zero derivative at rest and a poorly posed startup Jacobian. Evaluate the limit without dividing by Reynolds number at zero flow. EPA's network model and algorithms provide useful single-phase references; the multiphase extension requires separate validation. [Network model](https://usepa.github.io/EPANET2.2/3_network_model.html), [Hydraulic algorithms](https://usepa.github.io/EPANET2.2/12_analysis_algorithms.html)

Phases share velocity under the selected homogeneous approximation. Update density and viscosity as pressure, temperature, and composition change. Long runs may use property-evaluation segments without adding stored pipe inventory.

Flow follows hydraulic potential, not pressure alone. With elevation, pumps, and fixed-pressure boundaries, connected reservoirs need not converge to equal pressures.

**Performance assessment:** friction adds inexpensive edge calculations relative to iterative flashes. Graph compression, property reuse, and sparse solution are the main optimizations. Confirm the cost through benchmarks rather than promise a negligible overhead.

V1 excludes choking, sonic flow, and detailed cavitation. Out-of-domain operation must produce a visible limitation, not an unlimited flow prediction.

### Conservative integration

Do not redistribute inventory using sequential pairwise equalization: its result depends on traversal order and handles loops and controllers poorly.

Use **fully implicit backward Euler with one coupled Newton system per substep**, surrounded by a bounded active-set loop for phase and device regimes:

1. Start from the accepted state and warm-start continuous unknowns, flows, and discrete phase/device states.
2. Fix the active regimes for this Newton attempt. Unknowns include reservoir component inventories, temperature, pressure and phase allocations, edge flows, and junction mixture states; eliminate dependent variables consistently rather than imposing duplicate equations.
3. Assemble component accumulation, internal-energy accumulation, vessel-volume closure, equilibrium, junction conservation, and hydraulic/device residuals together. For example, a component row is `n_new - n_old - dt * net_component_rate(new_state) = 0`.
4. Evaluate phase properties and derivatives directly. Do not alternate independent hydraulic solves with repeated bracketed UV/PH flashes. Standalone flashes remain useful for initialization and reference tests.
5. Solve the scaled system with damped Newton and a general sparse linear solver. Reuse symbolic structure for unchanged topology/regimes. Use deterministic graph ordering; RCM ordering may reduce bandwidth, but arbitrary network graphs must not be assumed to fit the V3 column band solver efficiently.
6. Check the converged candidate against active-regime feasibility. Change at most one violated device/phase state per outer pass in deterministic order, and repeat within a bounded pass count. Detect cycling. A phase absent from the candidate must also satisfy its appearance/stability condition.
7. Accept only after conservation, equation residuals, feasibility, and property-domain checks pass. If contraction is poor, regimes cycle, or constraints fail, reduce the substep and retry from the same accepted state.

Step-size control uses nonlinear convergence and a time-integration error check, not convergence alone. Use step refinement on qualification cases to calibrate the error control. Warm-start circulation loops from accepted flows; use numerical continuation of pump targets from rest when needed, with the final accepted solution satisfying the actual target unless the device is physically head-limited.

Every trial is reconstructed from the same accepted starting state. Iterations must not repeatedly accumulate the same transfer.

Maintain an edge transfer ledger:

\[
\Delta n_{i,a}=-\Delta n_{i,b}
\]

for each internal component transfer, with corresponding energy entries. Solve junction component and enthalpy balances consistently when streams mix or reverse.

Reservoir balances use stream enthalpy:

\[
\frac{dU}{dt}
=
\sum_{\rm in}\dot n h
-
\sum_{\rm out}\dot n h
+\dot Q+\dot W
\]

Use `E = U + M*g*z` for total stored energy at the reservoir reference elevation and account for `h + specific_gravitational_energy` in transported energy. The U equation above is the thermal/internal-energy part; the implemented full ledger must include the corresponding elevation terms. Pipe and junction transfers conserve the same total-energy convention. Gravitational energy is required by the stated balance tolerance, not an optional display correction. Friction and valve losses must not be subtracted again as disappearing energy.

Reject negative inventory and unsupported thermodynamic states. Reduce the step or activate availability constraints; do not repair the solution by independently clipping outgoing flows.

Accepted substeps remain private until the whole requested island interval succeeds under either the full-solve or fallback acceptance contract. A failed full attempt is rolled back before trying a fallback for that interval; never apply both ledgers. If both fail, publish none of their trial transfers.

### Equipment contracts

| Device | First-release behavior |
|---|---|
| Reservoir | Fixed volume, conserved inventory and energy, adiabatic, bulk withdrawal, corrected liquid volumes and physical compression |
| Pipe | Bidirectional homogeneous transport with diameter, length, roughness, fittings, and elevation |
| Pump | Directional flow target at suction conditions; maximum added pressure and zero-flow shutoff against excessive opposing pressure |
| Pressure valve | Directional upstream pressure-sustaining control |
| Generator | Infinite one-way source at configured composition, pressure, and temperature |
| Void | One-way sink at configured pressure; never supplies material |
| Debug tool | Reports the last committed interval’s flow and transported composition |

The pump supplies the pressure rise needed to meet the requested flow when possible. At maximum head it becomes head-limited and delivers the feasible lower flow. When the opposing pressure difference reaches its maximum added pressure and no forward-flow solution remains, forward flow is zero; reverse flow is blocked. This is **differential-pressure shutoff**, not a separate outlet-pressure trip. For example, a 5-bar maximum rise with a 10-bar inlet permits up to a 15-bar outlet at zero flow. Include an ideal flow limiter when natural pressure would otherwise exceed the target.

Track pump work in the energy ledger using a documented v1 hydraulic-work approximation and configured efficiency. Keep the hydraulic relation replaceable by a future pump curve.

The valve has three feasible states:

- **Closed:** no forward discharge is needed or possible.
- **Regulating:** adjust resistance to maintain the upstream setpoint.
- **Fully open:** maximum opening is insufficient to maintain the setpoint.

Use the active-set method specified above for both pump and valve states: fixed modes during Newton, feasibility checks afterward, and bounded deterministic changes between attempts. Persist only accepted states; rejected substeps cannot advance hysteresis history. Any switching deadband belongs to the accepted state transition policy, not an uncontrolled toggle inside Newton. A valve cannot create pressure or guarantee a target when upstream supply and downstream capacity make it infeasible.

A generator’s phase state is calculated automatically. Its mass and energy contributions are recorded as external inputs. A void records external removals. Neither silently participates in reverse flow.

### Pump loops and bypass warnings

Replace the original strict bypass rule with a **pump-only test for cycles without stored fluid**:

1. Compile a separate undirected topology for this diagnostic.
2. Split every finite-volume reservoir into disconnected per-port terminals, so this test cannot traverse the reservoir. Apply the same boundary treatment to generators and voids; they are fixed-pressure boundaries, not literal finite inventories.
3. Retain enabled pipes, junctions, pumps, and valves. Do not let automatic closure or an existing error hide a connection during validation; explicit user-disabled connections are omitted.
4. Require every enabled pump edge to be a graph bridge: removing it must disconnect its terminals. Preserve edge identities when testing parallel edges.

A pump failing this test lies on a zero-storage cycle and is disabled with an error. A loop through a tank or equipment buffer is allowed and solved dynamically. Passive parallel pipes are allowed. **Pressure valves have no structural bypass prohibition.**

A device-free parallel route between the same reservoirs may reduce or defeat a device's control authority; report a warning and solve the actual flow. Do not classify every such arrangement as physically useless or reject it categorically. A valve with an unattainable target reports its actual saturated operating state.

## 4. Persistence, interfaces, and Minecraft presentation

### Persistent authority

Store registered topology, equipment state, inventories, energy-reference identities, clocks, pending events/transfers, capacity reservations, accepted phase/device regimes, fallback status, and scientific revisions in world-level `SavedData`, anchored in the Overworld with dimension-qualified identities. Reserve a versioned module-state payload with its own module type and scientific revision even though production columns and reactors are deferred. NeoForge supports this storage independently of chunk-bound TEs. [NeoForge Saved Data](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)

TEs store their simulation identity and display data. They do not contain a second authoritative inventory.

- Chunk unload detaches the presentation binding.
- Chunk load reconciles identity and displays the latest committed state.
- If a registered block is missing, or an unregistered block exists after WorldEdit/another mod's changes, reconcile it as a timestamped topology event at discovery time. Never overwrite valid simulation inventory from stale TE data or guess the historical edit time.
- Break/place events create timestamped topology changes.
- Position reuse gets a new identity, preventing an old result from reaching a replacement block.
- Save only committed scientific state and required event history.
- Restart rebuilds derived graphs and caches, retaining persisted converged warm-start anchors and degraded allowance where valid. Restart must not grant another three fallback intervals.
- Preserve debt accrued while running; add no debt for server downtime.

Only registered topology can simulate unloaded. Do not scan or force-load chunks to discover unknown pipes.

For v1, disallow moving these blocks on Create contraptions. Breaking a reservoir removes its remaining inventory through an explicit destruction ledger entry; dropped items do not duplicate that inventory.

Equipment removal reconciles every built-in buffer and its ownership records. A pending delivery whose target disappeared becomes visibly blocked and remains accounted for until an explicit disposal/recovery transaction resolves it; never silently delete orphan transfers or redirect them to a replacement block at the same position.

### Internal interfaces

Add immutable contracts for:

- `SimulationModule`: timestamped buffer inputs, cadence, required calculations, transfer proposals with per-island ledger entries, availability constraints, and replaceable backpressure policy.
- `SimulationSnapshot`: interval, revisions, controls, and conserved starting state.
- `SimulationProposal`: candidate state, transfer ledger, full/approximate quality status, accepted regimes, diagnostics, and warm starts.
- `ModuleTransferProposal` / `PendingInput`: unique transfer identity, owner/source/target buffer, timestamp or withdrawal interval, component amounts, energy/reference identity, and reservation linkage.
- `ThermodynamicModel`: standalone TP/PH/UV helpers plus equilibrium/volume/energy residuals and derivatives for simultaneous solves, calibrated phase properties, and validity reporting.
- `HydraulicDeviceModel`: edge relation, operating state, and energy contribution.
- Versioned module state and per-component energy-reference metadata, with unavailable formation data represented explicitly.

Use SI units internally, absolute pressure, stable component identifiers, and defensive copies. Restrict connected materials to a compatible thermodynamic basis; different crude presets sharing that basis may mix.

### GUIs and debugging

Reservoir GUI:

- Pressure, temperature, total inventory, and vessel volume.
- Phase volume fractions by default.
- Phase mass fractions and per-phase mole composition.
- Explicit notice that every outlet withdraws all phases together; outlet height does not select liquid or vapor in v1.
- Last committed simulation time, lag, full/approximate/held quality, fallback allowance, and diagnostic status.

Pump GUI: suction-volume flow target, maximum added pressure, actual flow/pressure rise, and flow-controlled/head-limited/shutoff/error status. Label the limit as an inlet-to-outlet difference, not an absolute outlet pressure.

Valve GUI: upstream pressure target, actual upstream pressure, flow, and control state.

Generator GUI: compatible material package, composition editor, crude presets, pressure, temperature, computed phases, and actual outlet flow.

Void GUI: sink pressure and removal rate.

The debug tool reports the selected pipe segment's signed average mass flow over the last committed interval, direction, transported component composition, and phase breakdown. Show freshness, approximate-result status, clock-alignment waits, and zero-flow status; label interval averages rather than claiming tick-by-tick measurements. If a network is held, label the retained rate as historical rather than implying that fluid is still moving.

The equipment GUI contract includes built-in buffer volumes, working capacity, occupied/reserved/pending amounts, start/stop thresholds, and the reason for waiting or stopping. Exercise this contract through the test module; it does not add a processing column to v1.

Validate configuration changes on the server. Publish coalesced updates only to loaded TEs and subscribed clients, with bounded per-tick publication work.

Future extension points cover phase-selective ports, environmental heat exchange, Create power, pump curves, and stored pipe inventory.

## 5. Delivery sequence and acceptance

### Implementation sequence

1. Add scientific state/interfaces, energy-reference metadata, calibrated liquid volumes/compressibility, hybrid water properties, and transport coverage. Measure property/derivative costs and establish reference cases before setting numerical work budgets.
2. Build the Minecraft-independent graph, simultaneous backward-Euler/Newton solver, active-set handling, and bounded approximate fallback. Qualify fallback error limits against full solves; do not ship an unchecked reuse of old flows.
3. Extend the coordinator with island scheduling, shared worker access, warm starts, soft/hard budgets, time debt, and full/approximate/held result states. Add built-in buffer identities, reservations, pending-transfer ownership, time fences, and versioned module persistence.
4. Add persistent topology and all seven gameplay objects. Add a test-only fixed-split module across independent networks to verify the module boundary without changing the column solver or enabling production column transfers.
5. Add GUIs, diagnostics, unloaded-chunk integration, performance adaptation, and benchmark the normal, degraded, and shared-pool contention paths.

Keep V3 regression behavior and existing save identifiers intact. Version new persistence and packet formats explicitly.

### Required correctness tests

- Closed adiabatic networks conserve every component and total energy including gravitational potential, under both full and fallback updates.
- Hydrostatic equilibrium respects elevation.
- Series and parallel pipe networks reproduce reference hydraulic solutions.
- Results remain consistent within the numerical tolerances under graph reordering and different worker counts with adaptation/fallback disabled, identical step schedules, and nonbinding wall deadlines. Adaptive and time-triggered fallback runs have separate trajectory-accuracy tests.
- Reservoirs handle empty states, corrected heavy-liquid volumes, complete liquid filling with finite physical compression, phase appearance/disappearance, and free-water/subsaturated switching.
- Liquid-full tanks accept physically possible compression, build pressure, and reduce pump flow to shutoff at the configured maximum added pressure, without an artificial pressure clamp or vent.
- Two-vessel gas transfer/depressurization agrees with a suitable analytic reference in its simplifying limit and a refined-step reference for the general case; stiff liquid-full and small-gas-volume cases converge.
- Rejected substeps reproduce a run started directly with the accepted smaller step, without advancing device-state history or duplicating transfers.
- Multiple simultaneous outlets cannot overdraw inventory.
- Pump head limitation, differential-pressure shutoff, reverse-flow blocking, and startup from zero flow behave predictably. Zero-flow and transition derivatives match the chosen friction law.
- Permit pump loops through tanks, equipment buffers, and fixed-pressure boundaries; reject a pump in a zero-storage cycle. Permit passive parallel pipes and issue warnings for device bypasses between reservoirs instead of rejecting valves structurally.
- Valves transition between closed, regulating, and fully open states, including competing or infeasible setpoints. Bound active-set cycling and restore accepted states on rejection.
- Generator and void transfers exactly match external ledger entries.
- Timeouts publish no partial island state; accepted fallback commits exactly once and late full results cannot overwrite it.
- Fallback recomputes against current inventory, respects competing outflows, keeps component/energy balances, and marks approximate properties. Phase/topology/domain changes reject unsupported fallback; invalid intermediate iterates cannot be published.
- Fallback stops after three intervals or the captured duration bound, stops earlier on failed checks, and cannot renew its allowance by changing cadence, retrying, or restarting. Full recovery begins from the current committed approximate inventory without replay.
- Catch-up preserves event ordering; unequal-time network merges wait for alignment.
- A fixed-split test module conserves `island inventory + pending inputs + module-owned holdup` across independent feed/product clocks, multiple products, and restart. A 100 kg record delivered as 20 kg plus 80 kg preserves identity, reservation accounting, component amounts, and energy. Failure after a staged portion commits no part of that attempt.
- An infeasible due input is deferred while an otherwise valid receiving network advances. Feasible other inputs are not starved by that record. A now-known but deferred delivery does not keep an unresolved-production fence in place.
- Catch-up uses idle capacity for successive intervals without dropping debt and yields fairly when another owner becomes ready. Cyclic test modules bootstrap without a fence deadlock and report the actual waiting dependency when stalled.
- A module waits for required feed state; time-distributed withdrawal respects a feed buffer running dry mid-interval. Occupied plus reserved output capacity causes default backpressure, and capacity is released exactly once after delivery/cancellation.
- Delivery fences prevent late insertion into committed intervals; failed source attempts publish no products. Removed buffers and stranded transfers retain explicit ownership/disposition.
- Chunk unload/reload and restart preserve inventories and clocks without loading chunks.
- Missing/replaced blocks and unregistered blocks discovered at load are reconciled once at the discovery timestamp, without restoring stale TE inventory.
- Energy-reference changes require explicit migration of reservoir U, pending energy, and module holdup; unavailable formation data cannot silently enable reactions.
- Changing the update interval preserves average process throughput within numerical tolerance.

Use scaled conservation residuals: an initial acceptance target of \(10^{-8}\) relative component balance and \(10^{-6}\) relative energy balance, with explicit absolute tolerances near zero. Compare time integration against refined-step reference runs.

### Prototype performance gate

Benchmark 100 reservoirs and 1,000 physical pipe blocks with up to 24 components, both as one coupled network and as many independent networks. Include a fixed-split test module with built-in buffers across at least two islands, using different hydraulic/module cadences and an intentionally lagging product island. No production column solve or column optimization is required for this boundary benchmark.

Pin the baseline to the recorded Ryzen 7 9700X reference environment with **two shared solver workers**, and report runs with one worker and the automatic worker count separately. Use bounded test jobs to reproduce long equipment-job contention without changing V3. Publish scenarios, data revisions, JVM settings, and worker counts with every result.

Measure:

- Median and p95 solve time.
- Phase-property and derivative evaluations, standalone initialization flashes, Newton iterations, active-set passes, and rejected/accepted substeps.
- Worker CPU time, queue age, memory, and cache hit rate.
- Server-thread coordination/publication cost.
- Lag growth and recovery after temporary overload.
- Fallback latency, qualifying residual/error bounds, accumulated trajectory error versus a full reference run, grace-period exhaustion, and recovery after approximate commits.
- Shared-pool starvation/queue waiting and whether warm starts/fallback actually help when a worker becomes available; no reserved capacity is assumed.
- Module reservation/pending-input overhead, waiting time, and retained-history size.

Release performance gates are p95 end-to-end interval completion below two wall-clock seconds for a five-second simulation interval at the specified baseline, and p95 combined **server-thread fluid-system work below two milliseconds per tick** for the steady benchmark workload. These are targets, not measured claims. Report queue, solve, commit, and publication time separately, along with maxima and topology-edit bursts. Worker milliseconds are separate from server-thread milliseconds. The earlier pipe study's example 5 ms/tick allocation is not adopted as an additional allowance. Contention cases explicitly report missed targets and lag rather than excluding queue waits.

The single-worker/45-second occupied-worker scenario is an intentional contention test, outside the ordinary two-worker latency gate. Expect fluid admission to wait for that worker, followed by catch-up and any dependent delivery waits. Assert finite queueing, no lost state/debt, and fair recovery; record actual lag instead of declaring an exact one-minute delay. With all workers occupied, fallback cannot provide uninterrupted physical progress.

Stage 1 must supply a cost model: property and derivative counts scale with the sum of evaluated phases across reservoirs/junctions, Newton iterations, substeps, and active-set retries, plus sparse factorization and assembly costs. Report those counts and per-operation timings alongside wall time. There must be no unmeasured multiplier from nested UV/PH flash loops in the coupled step. The review's illustrative 30,000-flash estimate and suggested speedup are hypotheses, not benchmark results. Establish scenario-specific iteration/work budgets from measurements before claiming the target is met.

Numerical qualification is implemented through the mandatory gates in the work order: **reservoir/transport data coverage and validity limits, calibrated volume/energy derivatives, solver/active-set settings, fallback trust/error limits and budget allocation, reference multiphase cases, and measured scaling**. A failed gate blocks dependent delivery; it does not authorize silently weakening conservation, dropping fallback, skipping debt, or reducing required functionality. The plan is finalized; its implementation and qualification remain pending until evidence is recorded.

## 6. Piping-system testing matrix

### Test conventions and acceptance profiles

Every case below is required unless explicitly marked as an unsupported-condition test. Test IDs are stable and map to the work-order milestones. `R` means a finite reservoir, `J` a zero-volume junction, `G` a generator, `V` a void, `P` a pump, and `C` a pressure-sustaining valve. Arrows indicate the intended test flow, not permission to ignore reverse flow in ordinary pipes. All fixtures specify volume, elevation, diameter, length, component inventories, energy, controls, property revisions, and step schedule in SI units.

Use these profiles as testable acceptance requirements, not claims of industrial model accuracy:

| Profile | Pass condition |
|---|---|
| BAL — accounting | For every component, absolute ledger residual <= `1e-10 mol + 1e-8 * component_scale`; total energy residual <= `1e-4 J + 1e-6 * energy_scale`. Scales use the larger of initial/final accounted amounts and absolute external turnover; energy includes potential energy. Report absolute residuals as well. No negative inventories or unaccounted clamping. |
| EQ — full-solve equations | Maximum nondimensional equilibrium, volume, hydraulic, and accumulation residual <= `1e-8` after documented scaling. Active-regime feasibility and property-domain checks also pass. A small step change alone is not convergence. |
| REF — analytic/reference hydraulic fixtures | Pressure error <= `1 Pa + 1e-4 * abs(reference pressure)` and flow error <= `1e-10 kg/s + 1e-4 * abs(reference flow)`, using exactly specified analytic fixture properties. These fixtures isolate equations from empirical property uncertainty. |
| TIME — integration accuracy | Against a reference run whose halved step changes each monitored trajectory by < 0.1%, full production steps/adaptive cadence keep pressure, inventory, and cumulative throughput within 0.5% (with 1 Pa / BAL near-zero floors), and temperature within 0.5 K. Assess trajectories at matching simulation timestamps, including transients. |
| APPROX — fallback | BAL always passes. Against a full reference trajectory over the complete grace period, pressure error <= `1 Pa + 2% * abs(reference pressure)`, temperature error <= 2 K, each phase-volume fraction error <= 0.02 absolute, and cumulative component throughput error <= 2% with BAL near-zero floors. No unsupported phase/device transitions. Online residual/trust checks must be calibrated to this profile; tests deliberately exercise their rejection boundary. |
| STATE — transactions/lifecycle | Exact expected identities, revisions, ownership changes, event ordering, and terminal states. No duplicate transfer, missing completion, unexpected chunk load, stale publication, or allowance reset. Use injected clocks and controlled worker latches instead of sleep-dependent assertions. |
| PERF — ordinary load | The section 5 two-worker baseline meets p95 interval latency < 2 s and p95 server-thread fluid work < 2 ms/tick. Report maxima, rejected steps, queue time, and lag as well as p95. Contention and unsupported states use their explicitly different expected outcomes. |

For EQ, record the normalization of every residual family in the qualification report and test the scaling near empty and liquid-full states. For TIME/APPROX, compare significant quantities and retain explicit absolute floors for traces rather than dividing by zero. BAL applies to full and approximate commits, including deferred/partial deliveries. Full-solver equation tolerances must not be silently imposed on the intentionally approximate thermodynamic state, but fallback never receives weaker accounting tolerances.

### A. Topology and passive hydraulics

| ID | Setup / action | Expected result and oracle | Gate |
|---|---|---|---|
| P01 | Single isolated R, first empty then filled; no source, heat, or work | No flow; inventory/energy unchanged; empty state has no fabricated temperature or phantom gas; BAL/STATE. | M2 |
| P02 | Two equal-state R joined by a horizontal pipe, initially zero flow | Stable rest; finite nonzero laminar Jacobian at zero; no spurious transfer; REF/EQ/BAL. | M2 |
| P03 | Two R at different pressures, laminar flow; repeat with reversed initial pressures | Flow has the correct sign and agrees with independent Poiseuille/linear-compliance fixture; REF/BAL. | M2 |
| P04 | Fixed-pressure boundaries with turbulent flow; sweep through the laminar-transition-turbulent range | Darcy relation and independently evaluated friction reference agree; pressure-loss value and first derivative are continuous at transition boundaries; REF/EQ. | M2 |
| P05 | Vary pipe length, diameter, roughness, and fitting losses individually | Match the reference relation for each parameter, including near-zero flow; do not use implementation-only snapshots as the oracle. | M2 |
| P06 | Several pipes in series; compare the physical graph and its compressed hydraulic representation | Same endpoint flow and pressure losses within REF; per-pipe debug mapping preserves direction and local loss. | M2, M8 |
| P07 | Two equal parallel branches, then unequal diameters/lengths between J nodes | Symmetric branches split equally; asymmetric split follows resistance; combined mass/energy balance closes; passive cycles are permitted. | M2 |
| P08 | Branched network with several simultaneous reservoir outlets | Junction component/energy balances close; shared reservoir cannot be overdrawn by independent branch decisions; BAL/EQ. | M2 |
| P09 | Elevated connected reservoirs at hydrostatic equilibrium; then reverse the elevation advantage | Correct hydrostatic pressure and flow response, including gravity in the energy ledger; REF/BAL. | M2 |
| P10 | Pressure reversal during an interval, dead-end branch, and a closed connection | Correct upwind composition on reversal; dead end/closed edge carries zero net flow; no invented junction inventory; BAL/EQ. | M2 |
| P11 | Two disconnected networks; change or fail one | Independent scheduling/state; the untouched network retains its valid operation and graph; STATE. | M5 |
| P12 | Permute node/edge insertion order and use 1, 2, and automatic worker counts | With fixed step schedule, no fallback/adaptation, and nonbinding deadlines, trajectories agree within EQ/TIME tolerances. No demand for bitwise identity across sparse orderings. | M2, M5 |

### B. Reservoir properties and multiphase transport

| ID | Setup / action | Expected result and oracle | Gate |
|---|---|---|---|
| P13 | Gas-only two-vessel transfer and blowdown | Match analytic limiting fixture and independently refined numerical reference for the general case; TIME/BAL. | M2 |
| P14 | Liquid-full R under increasing upstream pressure; shut inlet after compression | Finite physically modeled pressure/density response; no artificial gas pocket, vent, volume creation, or automatic full-tank clamp; compression reference tests and EQ/BAL. | M1, M2 |
| P15 | Heavy-hydrocarbon single-phase and mixed-composition reservoirs | Corrected density matches calibration data; validate liquid compression separately on at least three representative components and include a heavy liquid; property qualification profile and BAL. | M1 |
| P16 | Vapor + hydrocarbon liquid; then hydrocarbon liquid + free water + vapor | One shared vapor volume, normalized phase compositions, phase amounts sum to total inventory, volume/energy closure; EQ/BAL. | M1, M3 |
| P17 | Deplete or condense free water; hydrocarbon phase appears/disappears | Correct accepted regime change without chatter, negative phase amounts, or duplicate transfer; rejected attempts restore prior regimes; EQ/BAL. | M3 |
| P18 | Withdraw from a gas-capped reservoir through different block faces | All faces withdraw the same bulk mixture policy, not height-selected liquid or vapor. Phase/component transfer totals agree with the declared bulk proportions; BAL. | M3, M8 |
| P19 | Two feeds of different composition/temperature mix at a J; reverse one feed | Correct component and enthalpy mixing with zero junction holdup; compare independent ledger calculation and equilibrium fixture; EQ/BAL. | M3 |
| P20 | Missing viscosity, invalid mixture basis, out-of-domain water partial pressure or temperature, unsupported sonic regime | Explicit unsupported/property error, no zero-viscosity substitution or extrapolation, no invalid commit/fallback; STATE. | M1, M3, M4 |
| P21 | Nearly empty reservoir feeding several branches; empty reservoir begins filling | Available inventory bounds total withdrawal; no division by zero or disappearance of trace components; EQ/BAL. | M2, M3 |

### C. Pumps, pressure valves, sources, and sinks

| ID | Setup / action | Expected result and oracle | Gate |
|---|---|---|---|
| P22 | `G → P → R/V`, progressively increase opposing pressure | Flow target when feasible, head-limited flow below target, zero forward flow when opposition exhausts maximum added pressure; reverse flow blocked; REF/EQ/BAL. | M3 |
| P23 | Pump inlet at 10 bar absolute, maximum rise 5 bar, zero-flow endpoint | Shutoff relation uses 15 bar outlet in the horizontal reference fixture, not a 5-bar absolute outlet cutoff. Include source pressure and gravitational terms when applicable. | M3 |
| P24 | Natural forward pressure would exceed pump flow target; insufficient suction supply | Declared flow-limiting behavior and supply constraint hold, without consuming absent material; pump-work ledger matches transferred energy; EQ/BAL. | M3 |
| P25 | Pump cycle entirely through pipes/J/devices; parallel edge variant | Pump bridge diagnostic rejects the zero-storage cycle consistently, including multigraph edges; STATE. | M2, M3 |
| P26 | Recycle through R, equipment buffer, G/V terminal boundaries; passive parallel pipes | Reservoir/boundary-split diagnostic permits the intended topology. Dynamic tank recycle solves; no blanket cycle ban. | M2, M3, M6 |
| P27 | `G/R → C → V/R`, sweep source pressure, demand, and valve target | Closed/regulating/fully-open states and reverse-flow blocking satisfy their feasibility conditions. Infeasible targets report saturation, not invented pressure; EQ/BAL. | M3 |
| P28 | Add a device-free bypass around a valve between reservoirs; add serial competing valves | No structural valve rejection; warning plus actual control behavior. Bound active-set cycling, with rollback on nonconvergence. | M3 |
| P29 | Generator supplies mixed fluid indefinitely; void receives it at fixed pressure | Boundary inventory is not depleted; source/void cannot reverse roles. Integrated external component/energy ledger exactly explains island change; BAL. | M3 |

### D. Time integration, failure, and fallback

| ID | Setup / action | Expected result and oracle | Gate |
|---|---|---|---|
| P30 | Small gas volume, liquid-full network, rapid control change; force one substep rejection | Full coupled solve meets TIME/EQ; retry from smaller step agrees with a run started with that step. No retained trial transfer or hysteresis change. | M3 |
| P31 | Same scenario at 1, 5, and 20 s update cadence and with adaptive cadence | Match physical elapsed time and TIME bounds at shared timestamps. Report changed step counts; no cadence-dependent throughput multiplier. | M5, M9 |
| P32 | Smooth state with last converged anchor; inject full-solve soft timeout | Same solver enters approximate mode; current transfers pass BAL/APPROX and are marked approximate. Demonstrate at least one useful accepted fallback, not an always-reject implementation. | M4 |
| P33 | Force fallback near phase change, invalid topology/data revision, or outside trust region | Reject approximate candidate, hold committed state, preserve debt, and expose the reason; STATE/BAL. | M4, M5 |
| P34 | Repeated approximate intervals, changed cadence, short debt slices, and restart | Three-interval/captured-duration cap cannot be extended; hold after exhaustion; successful full recovery resumes from committed approximate state without replay. | M4, M7 |
| P35 | Late full result arrives after fallback/timeout; edit controls or replace equipment during execution | Stale epoch/revision/attempt cannot overwrite accepted state or replacement block; each attempt has one terminal disposition; STATE. | M5, M8 |
| P36 | Island nine intervals behind, otherwise idle workers; then make another island ready | Immediate bounded catch-up without normal-cadence gaps, fair service at next opportunity, no overlapping owner jobs or discarded debt; STATE. | M5 |
| P37 | One worker occupied by a controlled long job, including a 45 s acceptance run | Expected fluid wait, bounded queues, no server-thread solve, no lost debt, and recovery once worker returns. No false promise that fallback runs without a worker. | M5, M9 |

### E. Equipment boundaries and persistence

| ID | Setup / action | Expected result and oracle | Gate |
|---|---|---|---|
| P38 | Fixed-split test module, 5 s hydraulic and 15 s module cadence, separate feed/product islands | Integrated withdrawal respects available feed; pending products conserve global components/energy; no V3 solve; BAL/STATE. | M6 |
| P39 | Pending 100 kg delivery; only 20 kg is currently admissible | Deliver 20 kg with proportional component/energy amounts; 80 kg stays pending under same identity. Reservation becomes occupied storage only for delivered portion. Receiver advances. | M6 |
| P40 | Due record admits zero; another input and an ordinary through-flow are feasible | Blocked record defers without failing the receiving network or starving feasible work. Pending mass stays bounded/reserved; BAL/STATE. | M6 |
| P41 | Fail after staging a partial delivery; restart after a committed partial delivery | Failure commits zero staged portion; restart preserves exact remainder and revision, without duplicate consumption; BAL/STATE. | M6, M7 |
| P42 | Empty feed buffer, full working-capacity output, multiple feed islands | Default stop/resume policy obeys availability; staged multi-feed ownership remains accounted for. No negative feed or unlimited hidden product storage. | M6 |
| P43 | Lagging upstream module with delivery fence; cycle of two buffered test modules | Receiver waits only at unresolved dependency horizon and names upstream lag; known deferred inputs release production fences. Cycle bootstraps or reports actual lack of material, not scheduling deadlock. | M6 |
| P44 | Unload intermediate pipe chunks, then all reservoir chunks while server runs; reload | Simulation continues with no chunk ticket/load; loaded TEs reattach to current state. Compare with the equivalent loaded reference; TIME/STATE. | M7, M8 |
| P45 | Save/restart with debt, pending portions, reservations, fallback allowance, and regime state | Restore one coherent committed snapshot and reference identities; no offline debt, lost material, replay, or renewed grace period; BAL/STATE. | M7 |
| P46 | Break/replace block, missing registered block, new identity-less block, removed transfer target | Timestamp discovery/edit correctly; reject old bindings; explicit destruction/stranded ownership; no silent reassignment to replacement; BAL/STATE. | M7, M8 |
| P47 | Reload property data or migrate an energy-reference offset with pending transfers | Invalidate appropriate cached science; explicit U and transfer-energy rebase preserves physical state; unavailable formation data cannot activate reactions; STATE/EQ. | M1, M7 |

### F. GUI, scale, and sustained operation

| ID | Setup / action | Expected result and oracle | Gate |
|---|---|---|---|
| P48 | Inspect every device and a compressed pipe segment while full, approximate, and held | Correct SI conversion, phase ratios, bulk-withdrawal notice, flow direction, interval timestamp, quality/lag/status, and historical-rate labeling. Held results do not claim current movement. | M8 |
| P49 | Edit device settings from valid/invalid/stale client requests; reload chunk while menu is open | Server validation, revision protection, bounded subscriptions, and current TE identity; no double authoritative inventory or unexpected chunk load; STATE. | M8 |
| P50 | 100 R / 1,000 pipe blocks / up to 24 components as one island and as 100 islands | Two-worker baseline meets PERF; report compressed edge count and numerical work as well as physical pipe count. | M9 |
| P51 | Baseline with test-module boundary, partial deliveries, differing cadences, and temporary product lag | PERF on steady feasible portions; explain delayed portions separately. No unbounded pending-record growth or accounting drift; BAL/STATE. | M9 |
| P52 | 30-minute paced server soak with topology edits, fallback injection, unload/reload, and recovery | No leaked jobs, reservations, subscriptions, or unbounded derived caches; BAL at each checkpoint; no cumulative grace/debt manipulation. Report memory after warmup and post-recovery quiescence. | M9 |

### Coverage and evidence rules

- Run the passive topology family in liquid-only and gas-only modes; run branching, mixing, reversal, device, and lifecycle cases with qualified hydrocarbon/water/vapor mixtures as specified. Do not require every possible Cartesian combination: cover interactions through the named cases and a bounded seeded randomized suite.
- For randomized small connected graphs, record seeds and use a qualified phase-stable fluid. Assert BAL, positivity, graph-order consistency in fixed mode, and agreement with the uncompressed graph. Never promote randomized snapshots into an independent physical oracle.
- Separate unsupported-domain tests from convergence tests. An intentionally unsupported state passes by rejecting safely; a supported ordinary fixture cannot pass merely by holding forever.
- Every checkpoint report lists matrix IDs run, fixture/property revisions, settings, expected/actual values, maximum residuals/errors, and artifact paths. An unexecuted case is NOT RUN, never PASS.
- The work order defines qualification artifacts and how numerical settings become fixed before downstream integration. Runtime, chunk, and GUI tests cannot substitute for scientific reference validation, and property microbenchmarks cannot substitute for a paced Minecraft performance run.

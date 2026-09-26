# Review: design/SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN.md

Reviewed 2026-09-15 against main at e660097 (runtime, science.thermo, science.column.v3.thermo,
science.material, data packs) and research/fluid-pipes-1.21.1 + benchmarks/fluid-pipes-1.21.1.
Revised the same day after three user decisions (bulk withdrawal accepted; equipment as island
separators with buffers; column solver optimization out of scope).

## Verdict

The concurrency, commit, clock and persistence model is sound and matches what BoundedCpuSolveService
already does (epoch, input revision, cooperative deadline, capacity held until drained). The numerical
core is under-specified in the places that will decide whether the system converges and how much it
costs: the reservoir closure, the coupling loop, device state selection, and the performance gate.

Must-fix before this becomes a plan: findings 1-5 (numerical core, all still open and undiscussed) and
the module boundary contract (section "Opened by the decisions", item A), which the equipment-as-
separator decision made a first-class part of the design. Findings 7 and 8 are resolved by decision.
Finding 6 is narrowed but still needs a choice.
SUPERSEDED by the plan check below: the doc revision "Updated 2026-09-15" addresses all of the above.

## Plan check (design doc revision "Updated 2026-09-15", 540 lines)

Addressed and sound: findings 1 (one coupled backward-Euler Newton per substep, active-set outer loop,
general sparse solver, RCM optional), 2 (stage-1 cost model required, nested-flash multiplier forbidden,
30,000 estimate correctly labelled a hypothesis), 3 (by decision: calibrated volume translation + physical
compressibility, no vent/clamp/gas pocket; pump differential shutoff is the natural limiter and v1 pressure
is bounded by pump rise + generator pressure), 4 (separate-water hybrid, explicit regime state held fixed
in Newton, IF97 Region 1 only, joint vapor volume), 5 (active-set method for pumps and valves, accepted-
state-only hysteresis, C1 friction with finite laminar derivative at rest), 6 (by decision: shared pool,
no reserved worker; coordinator-owned ready-list, one island per owner, cap raised, contention benchmarked),
7 and 8 (pump-only bridge test on the reservoir-split graph with generator/void split too; valves free;
parallel pipes allowed; device bypass = warning). Opened items A-I are all present: pending-input ledger
with unique identities, reservations against working capacity, module-owned input ledger, global
accounting formula, bounded records; time fences and feed-boundary holds; buffers built into equipment;
energy-reference offsets + migration formula U_new = U_old + sum n_i (offset_new - offset_old) + persisted
reference identity; versioned module-state payload; island = connected hydraulic component, modules are
separators; V3 stays GUI-only with a test-only fixed-split module; tests and perf gate include the stub
module and a pinned Ryzen 7 9700X / two-worker baseline. All smaller notes are in (gravity in the ledger,
warm starts over exact-key caches, adaptation hedged, load-time reconciliation, merge waits visible,
compatibility table with water-vapor viscosity gate, interop non-goal, 5 ms/tick not adopted, non-goals
collected, plus equipment-removal orphan-transfer handling which I had not asked for).

New content not previously reviewed: the bounded approximate-flow fallback (soft budget, last regime +
local derivatives, exact ledgers, approximate T/P, 3-interval and 3x-duration cap, no replay, persisted
allowance) and the pump "maximum added pressure" semantics. Both are internally consistent.

Remaining items (residual or introduced by the update):

R1. Determinism. The fallback triggers on a wall-clock soft budget, so the sim-time trajectory depends on
    hardware and load. Interval adaptation (1-20 s from utilization) also moves substep boundaries with
    load, and backward Euler results depend on step size. Both conflict with the test "results remain
    consistent under graph reordering and different worker counts". Fix: trigger fallback on numerical
    criteria (iteration budget, contraction rate), keep wall-clock only as hard cancellation; state that
    the consistency test runs with adaptation off and no fallback, and that adaptive cadence changes
    results only within the integration error tolerance. Note the contrast with time debt, which changes
    WHEN an interval is computed but not its result.
R2. A due pending input that the receiving solve cannot physically accept (buffer liquid-full, pressure
    infeasible) must NOT fail the receiving island's interval; otherwise one full product drum stalls its
    whole network. Specify deferral of that input while the island advances, and whether partial delivery
    is allowed (recommend partial with the remainder re-recorded under the same identity, or one record
    per receiving substep).
R3. Fence consequence. A product island may lag its upstream module but may never lead its feed island
    past a planned delivery time, so one stalled island stalls everything downstream through modules.
    Correct for causality and determinism; state the consequence, and have "waiting-for-delivery" name
    the upstream island and its lag.
R4. A constant Peneloux translation calibrates density, not compressibility: dV/dP stays PR78-native. Say
    so, keep c constant (a T-dependent c breaks the Cp / dH/dT identity the solver consumes), and add a
    sanity test of PR78 liquid isothermal compressibility against reference values for 2-3 components so
    "physical compressibility" is a bounded claim.
R5. State the water-vapor volumetric model inside the joint pressure closure (ideal gas at partial
    pressure, or pure-water PR78) and its pressure limit; the hybrid range qualification depends on it.
R6. Fallback implementation risk. As specified it is the first damped-Newton iterate with the transfer
    ledger applied exactly and T/P/phase left approximate. Implement it as a mode of the same solver
    (accept-first-iterate under the fallback acceptance checks) rather than a second reduced-model code
    path. Consider making it conditional on measured failure modes at the gate: its own refusal rules
    (regime change, domain exit, phase appearance) mean it only rescues smooth-state budget misses, which
    a good warm start also rescues.
R7. Catch-up must be work-conserving: an island may run consecutive debt intervals within a round when
    no other island is ready; otherwise a 9-interval stall needs >= 9 further rounds on an idle server.
    Debt duration itself has no cap (history and pending records do); specify one with a visible
    time-lost ledger entry.
R8. Module cadence is a module property; the test module needs it as a parameter since the perf gate
    assumes differing hydraulic/module cadences.
R9. Expectation to write down: with no reserved worker and one worker on a small host, one 45 s GUI
    column job costs the plant about a minute of lag plus downstream fence waits. Already accepted by
    decision; the contention benchmark should state that number as the expected outcome, not a miss.

None of R1-R9 reopens a decision. R1 and R2 change behavior and should go into the doc before stage 3;
R3-R9 are wording, a test, and an implementation choice.

## Ranked findings

1. Coupling loop is sequential-modular despite the doc's own principle. Steps 3-7 of "Conservative
   integration" run a hydraulic Newton, then transport, then black-box UV flashes, then PH flashes,
   and repeat. That is successive substitution between hydraulics and thermodynamics. It is slow or
   oscillatory wherever pressure is strongly coupled to inventory (small gas-filled vessels,
   liquid-full vessels, blowdown). Recommend fully implicit backward Euler with all unknowns in one
   sparse Newton per substep: per reservoir (T, P, phase split), edge flows, junction states; residuals
   = component balances, U balance, V closure, equilibrium, hydraulic relations. The V3 kernel already
   exposes fugacity derivatives and dH/dT; dV/dT, dV/dP, dH/dP come from the same cubic. Do not model
   the UV flash on PhFlashSolver's bracketed pattern (up to 80 TP flashes per call). OPEN.

2. No cost model behind the 2 s p95 target. Nested-flash count at the gate: 100 reservoirs x ~5 Newton
   iterations x ~3 substeps x ~20 TP flashes per UV flash = ~30,000 24-component flashes with stability
   tests per 5 s interval, before hydraulics. Whether that is 0.3 s or 30 s depends on per-flash cost,
   measurable today from V3. Stage 1 should report it, and the gate should be stated in flash-count and
   Newton-iteration terms as well as wall time. The simultaneous form in (1) cuts this by roughly an
   order of magnitude: one property evaluation per phase per reservoir per Newton iteration. OPEN.

3. Liquid-full vessel and PR78 liquid volumes. (a) The repo has no Peneloux volume translation; PR78
   liquid densities for heavy cuts are off by roughly 5-15%, so displayed phase volume fractions and
   fill levels will be visibly wrong. (b) In a liquid-full rigid vessel dP/dn is enormous; the network
   Newton becomes stiff and substeps collapse. Design an explicit liquid-full regime (overpressure ->
   flow rejection or relief) rather than letting the "complete liquid filling" test discover it. OPEN.

4. Water model scope and regime switch. "Water vapor in the shared vapor phase" is ambiguous: water as a
   PR78 component (needs water-HC kij, which V3 deliberately avoided) or the V3-style hybrid where free
   water fixes P_w = Psat(T). Recommend the hybrid, but make the free-water-present / subsaturated regime
   an explicit discrete state in the accepted reservoir state, switched by the active-set logic, not
   discovered inside an inner iteration; V3's wet-tray history shows this is where solvers die. IF97 in
   full is far larger than needed: the reservoir needs compressed-liquid density, u, h over ~273-620 K,
   i.e. IF97 Region 1 plus the saturation line already in water.json. OPEN.

5. Device state selection needs a named method. Flow-target pump switching to dP-limited plus a
   three-state valve is a complementarity problem. Hysteresis inside a substep-rejecting Newton chatters.
   Recommend an active-set outer loop: fix device states, Newton, check feasibility, switch at most one
   device per pass, bounded passes, device state persisted in the accepted state. Also: Darcy-Weisbach
   m|m| has zero derivative at rest; the laminar branch must supply the nonzero Jacobian at zero flow and
   the blend must be C1. Every idle network starts at zero flow. OPEN.

6. Pool sharing with V3 starves the fluid network at current defaults. The service is one job per
   owner, ready queue 8, workers default 1 / max 2, V3 deadline 45 s. "Schedule fairly across module
   families" cannot be done by the current admission model.
   NARROWED 2026-09-15: with equipment as island separators, a column solve no longer sits inside a
   hydraulic round; buffers absorb the cadence mismatch. Still open and required for the network design:
   (a) coordinator owns the island ready-list and feeds the service at most `workers` jobs at a time,
   service unchanged; (b) worker default raised to >= 2 and the cap raised, with islands scheduled before
   modules; (c) the choice between islands-as-owners (QUEUE_FULL every round at 100 islands) and batched
   islands (loses per-island cancellation). The column's own re-solve policy is deferred (see the
   out-of-scope section) and does not block (a)-(c).

7. Bulk withdrawal drains vapor with liquid. A gas-capped tank with a bottom outlet sends gas down the
   pipe in volume proportion; a gas-liquid separator is impossible in v1.
   USER DECISION 2026-09-15: intentional v1 scope; phase-selective ports are a later implementation.
   RESOLVED. Remaining ask: state the behavior in the reservoir GUI and the limitations list.

8. Strict bypass rule forbids every closed loop containing a pump. Undirected connectivity with
   reservoirs and other devices counting as paths rejects the basic circulation loop (tank, pump, tank,
   valve, back to the first tank), recycle lines and pumparound-style loops.
   USER DIRECTION 2026-09-15: recycle loops are expected in a full refinery but stress the solver;
   equipment (reactors, columns) become network separators with input/output buffers. RESOLVED as:
   - The harmful loop is the algebraic one: a cycle through zero-holdup elements only (junctions, pipes,
     devices) that a pump can drive. Composition at a junction fed by its own outlet is degenerate.
     Loops through a finite-volume node are damped by the accumulation term and are well-posed under
     implicit Euler; dynamic simulators handle recycle this way, with no tear streams.
   - Replacement rule (precise form): delete every finite-volume node, splitting each reservoir into
     per-port terminals; every PUMP edge must be a bridge of the remaining graph. Generator and void
     count as finite-volume nodes for this test (fixed-pressure boundaries are infinite reservoirs).
     Same bridge computation as the doc plans, different graph. Correction to the earlier discussion
     note: the invariant is NOT "every cycle contains a holdup"; that would reject legitimate parallel
     pipe pairs between two junctions, which are passive and cannot circulate. Only pumps need the
     bridge test. Valves need no structural rule.
   - A device with a device-free parallel path between the same two reservoirs is well-posed and merely
     useless: warning, not error.
   - Equipment modules (column, reactor, exchanger) own feed/product buffers that are ordinary reservoirs
     in the network. A module is an island SEPARATOR, not a joiner; this replaces the doc sentence
     "future equipment ... can join multiple networks into a larger island". A steady-state module has
     zero internal holdup so in = out per commit. A CSTR is itself a holdup with a source term.
   - Costs to state in the doc: product composition lags feed by one module cadence; buffer residence
     time >= 3-5x module cadence; smallest loop buffer sets the useful substep; control loops closed
     across a module boundary are sampled-data systems and can hunt if buffers are small; a feed buffer
     running dry mid-interval is the module's availability constraint, never a negative inventory.
   - Solver side for the loops that remain: substep from Newton contraction rate, warm-started device
     states and flows, ramp pump targets from rest on the first interval, RCM ordering.

## Opened by the 2026-09-15 decisions (not yet in the design doc)

A. Module boundary commit contract across islands. MUST-FIX. The doc's atomic-island commit assumes a
   self-contained island. A module touches >= 2 islands (feed buffer island; one island per product
   buffer, five or more for a CDU). If a product island's interval fails while the feed island's
   succeeded, the module has withdrawn but cannot deposit. Making the module commit atomic across all
   touched islands re-joins them, contradicting separation. Recommended: module deposits become
   timestamped PENDING EXTERNAL INPUTS for the target island, persisted like events, applied when that
   island's interval succeeds; the module ledger holds in-flight material. Global conservation check =
   sum of island inventories + pending inputs + module holdup. The doc needs: pending-input persistence,
   ordering, the check formula, and a bound with an explicit paused/error state (same rule as event
   history).

B. Module / island time alignment. A module reads its feed buffer at sim-time t0 and commits at t1.
   Adjacent islands may sit at different sim-times because of debt. The module cannot read a state that
   has not been simulated, so its cadence is bounded by the slowest adjacent island; its withdrawal must
   be applied to the feed island's interval as a time-distributed external removal over [t0, t1], not a
   lump at t1, or the buffer can go negative mid-interval. The doc's merge/hold rule covers islands only;
   write the module analogue.

C. What a buffer physically is. Separate placeable blocks (feed drum, product drums) or implicit in the
   equipment multiblock? Affects the block list, persistence identity, GUI, and graph compile (a buffer
   is a reservoir whose one port faces the module). Buffer volume is a numerical knob (residence time)
   as well as a gameplay one, so vessel volume must be a configurable block property, and the level
   hysteresis marks (start/stop) need a home in the module contract. DECISION NEEDED.

D. Enthalpy datum for persisted U. The reservoir state is internal energy. Today's ideal-gas enthalpy
   datum is zero at 298.15 K per component (MATERIALS.md). A reactor as a reservoir with a source term
   (the CSTR direction from finding 8) needs a formation-enthalpy basis so heat of reaction appears in U.
   Changing the datum later shifts every persisted U: scientific revision bump plus world migration.
   Decide the datum now (elemental reference with dHf, or reserve a per-component offset field in the
   property record) even though reactions are not in v1. Persisted U is the thing that cannot be
   re-based cheaply.

E. Module-state persistence slot. Column re-solve policy is deferred, but the SavedData schema should
   reserve a versioned module-state slot with its own scientific revision now, so adding it later is not
   a format break. Cheap to reserve.

F. Island definition and SimulationModule contract in the doc. Section 2 "For v1, each connected fluid
   network is an island. Future equipment ... can join" must become "connected hydraulic component;
   equipment modules are separators". The SimulationModule interface should express: reads a buffer
   snapshot, returns a transfer proposal with per-island ledger entries, its cadence, and its
   availability constraint.

G. What the column does in the v1 network. Not a solver question. Options: nothing (buffers only, the
   column block is still the GUI calculator), or a fixed split from the last GUI-run result applied to
   arriving feed with no re-solve trigger. The second is not an optimization; it is the minimal boundary
   implementation and lets the network work proceed without touching the solver. DECISION NEEDED.

H. Tests opened by the decisions: pump-bridge rule (allowed loop through a tank; rejected pump on a
   zero-holdup cycle; parallel plain pipe across a device = warning; boundary nodes count as holdup);
   stub module with a fixed recovery table across two islands (no V3 solve) proving conservation with
   island + pending inputs + module holdup; failed product-island interval leaves a pending input that
   applies on retry; module waits for a lagging feed island; feed buffer runs dry mid-interval and
   becomes the availability constraint.

I. Performance gate should include at least one stub-module boundary so the two-cadence commit path is
   measured. A fixed recovery table is enough; no column solve, so it respects the scope rule.

## Column module integration (discussion 2026-09-15) — OUT OF SCOPE FOR THIS WORKSCOPE

USER DECISION 2026-09-15: do not optimize the column solver in this workscope. Warm re-solve, drift
tolerance, sensitivity surrogate and the timing experiment below are recorded for a later decision only.
Nothing in this section is a deliverable of the fluid-network design or its first stages. The only
thing the network design must keep is the boundary: the column is an island separator with input/output
buffers and commits ledger entries at its own cadence; how it re-solves is decided later. The runtime
items formerly listed here (worker default >= 2, islands scheduled before modules) are network
deliverables and now live in finding 6.

User framed three options for evolving the calculator into an in-world column: (1) fixed steady-state
equipment gated on input-buffer availability; (2) re-solve every round with whatever arrives, likely a
bottleneck; (3) fix a spec set (rate, condenser T, reflux ratio, reboiler duty), let feed composition
vary, re-solve with warm start and optimizations to fit the round.

Assessment: (1), (2b) and (3) are one mechanism with a re-solve tolerance knob. (1) is tolerance =
infinity, (2b) is tolerance = 0. A true dynamic tray-holdup column (2a) is a rewrite (~2,000 stiff DAE
states per 40-tray column) and is not worth it while buffers decouple the column from the 5 s round.

Recorded mechanism for the later decision:
- Steady-state V3 solve with the existing DOF ledger as the fixed spec set. Prefer ratio/intensive
  specs (reflux ratio, product-to-feed fractions, condenser T, boilup ratio) over absolute duties so
  turndown is a near-scale-invariant re-solve. Duty is an OUTPUT to the heat system in v1.
- Feed withdrawn from the input buffer at the spec rate, gated by availability with level hysteresis
  to avoid 5 s on/off bang-bang when supply is marginal. Under-supply either stops the column or
  re-solves at the reduced rate; pick one and state it.
- Re-solve triggers: spec change, topology change, or feed drift beyond a tolerance. Warm start from the
  last converged profile. Per-round budget; on miss keep the committed solution and retry with debt.
- Between re-solves: stale-split response. Stored per-component recoveries applied to arriving feed
  (mass exact by construction); products at stored temperatures; duties by energy-balance closure at
  the boundary (energy exact). Temperatures and purities approximate; label stale in the GUI.
- Optional: first-order sensitivity from the factored band LU, one back-solve per feed component.
- Persist converged profile + solved-for feed + spec set as module state so warm starts survive restart.

Deferred experiment: perturb a converged literature preset's feed by 1-5% composition and +-20% rate,
seed from the converged profile, time the re-solve. That number sets columns-per-worker.

## Smaller notes

- Gravity vs energy tolerance: g*dz at 50 m is ~500 J/kg; stream enthalpies relative to datum are
  ~1e5 J/kg. Dropping PE violates the 1e-6 relative energy target by three orders. The tolerance forces
  PE in; say so.
- Exact-key thermo/transport result caches (layers 2-3) will almost never hit for time-dependent states.
  Warm starts (layer 4) are the cache that matters; V3's trace-seed and initializer work confirms it.
- Interval adaptation does not reduce numerical work for stiff systems, only fixed per-round overhead.
  Consider adapting the wall deadline and island priority instead, or add a test showing adaptation
  helps in a stiff case.
- Persistence reconciliation: state what happens when a chunk loads and a registered block is missing,
  or a block exists with no identity (WorldEdit, other mods' explosions). Treat as a timestamped topology
  change at load time.
- Merge wait on an indebted island makes a new connection look dead for the debt duration; acceptable
  only if the paused state is visible on the block.
- Linear algebra: the repo has only V3BandedPivotedSolver. General island graphs are not banded; either
  add a general sparse LU or RCM-reorder to band them, which is cheap for pipe networks and reuses the
  existing solver.
- Data gap: viscosity data exists for 23 of 36 property records; water has liquid viscosity at 0.1 MPa
  only and no vapor viscosity. Wilke mixing for the shared vapor needs water-vapor viscosity. "Qualified
  packages" will be a strict subset; list them.
- Missing tests (original): two-vessel gas blowdown against an analytic reference; substep rejection
  reproduces a run that started with the smaller step; the liquid-full case explicitly; a stiff coupled
  case. Plus the decision-opened tests in item H.
- Create/vanilla fluid interoperability is not mentioned. If v1 has no capability bridge, list it as a
  non-goal. The pipe study's implications raised FluidStack component handling.
- Reconcile the 2 ms server-thread target with the study's proposed 5 ms/tick fluid allocation, and
  state the worker count the 2 s p95 assumes.
- Collect non-goals in section 1: no pipe inventory, no choking, no dissolved water, no contraptions,
  no offline catch-up, no capability bridge, no phase-selective ports (decision), no column-solver
  optimization (decision).

## What is right and should stay

Three clocks and the throughput-invariance test; island atomic commit with retained prior state and
discarded late results (maps directly onto the existing epoch/revision/deadline handling, so "extend not
replace" holds); time debt as a duration; empty reservoir as an explicit state (PR78 throws on zero
composition today); PROPERTY_UNAVAILABLE never zero; friction not double-counted in the energy ledger;
rejecting pairwise equalization; a separate scientific revision for the reservoir model; equipment as
island separators (decision).

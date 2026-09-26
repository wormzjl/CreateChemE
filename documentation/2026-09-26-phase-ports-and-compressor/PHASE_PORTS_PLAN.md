# Phase-selective tank outlets, a liquid-only pump and a gas compressor: plan

- Batch: `2026-09-26-phase-ports-and-compressor`. Status: **In progress** since 2026-09-26 (planned 2026-09-26; WP0 and WP1 done, see section 7).
- Base: `main` at 9674bf1 (0.5.0). Every source line cited below is a line of that commit, read with `git show HEAD:<path>`.
  - During this session (about 15:03 local) the worktree gained uncommitted edits to 16 tracked files. They appear to be the mixed-gas prototype patch, applied by another process; this task did not touch them. Every cited line was re-checked against `HEAD`.
- Author: Claude (Opus 5.5), planning only: no source changed, no Gradle run.
- Owner decisions this plan implements: `DECISION_LOG.md` next to this file (D1-D5, 2026-09-26).
- Depends on: the backward-Euler basis plan (a separate plan of the same date, not present in this worktree when this was written). This plan assumes the basis of `documentation/2026-09-24-mixed-gas-junction/HANDOFF_REVIEW.md` 8.6-8.9: backward-Euler steps inside 5 s slices, the state cap, `boundaryReopen`, `pumpReopenStart`, `pumpColumn=suction` and `replayDeterministic`. It plans nothing for TR-BDF2.
- This is the "later" of the 2026-09-15 decision "bulk withdrawal = intentional v1 scope, phase-selective ports later" (`documentation/2026-09-15-fluid-network/SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN_REVIEW.md` item 7; design non-goal "phase-selective outlets", `SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN.md` line 33, extension point line 499).

Labels: *read* = seen in the code at the cited line; *inferred* = my reading of how the code will behave, not run; *proposed* = a design choice of this plan.

## Contents

0. Summary
1. Scope and non-scope
2. What the base does today
3. Model design
   - 3.1 The pipe-end port
   - 3.2 The per-end transported stream
   - 3.3 Vessel balance, flash, junctions and the conservative reconstruction
   - 3.4 The "outlet phase absent" state
   - 3.5 Level head at a bottom outlet (optional)
   - 3.6 Liquid-only pump
   - 3.7 Gas compressor
   - 3.8 Solids, filters and the event integrator on phase ports
4. Runtime mapping
5. Backward-Euler basis and the delay policy
6. Tests and gates
7. Work packages
8. Risks
9. Decision points for the owner
10. Files read and cited
- Appendix A: every place the base reads a donor's bulk properties
- Appendix B: proposed constants

---

## 0. Summary

A tank connection draws what its face says: the top face draws vapour, the sides draw the bulk as today, the bottom face draws the condensed phases (hydrocarbon liquid, free water and mobile solids). Inflow through any face is delivered to the vessel as today. In the solver this is one new attribute per pipe end (`Port`: BULK, VAPOR, LIQUID) and one change of what the donor term of a connection reads: the stream of the end it leaves from, not the node's bulk. No new unknown and no new row; the vessel's own flash rows already decide the phase split the stream is read from.

A phase outlet whose phase is absent is closed for outflow (a one-way closure, like a generator or void end) until the phase comes back, with a stateless band: it opens above 1 % of the vessel volume, and an outflow throttle lands the phase at 0.5 %, so a port that drained cannot reopen until the phase has regrown by the gap.

The centrifugal pump becomes liquid-only: at each slice start it reads the stream its suction line will deliver; a gas or two-phase supply puts it in a new carried mode `INLET_WRONG_PHASE` (acts as CLOSED, reason shown in the GUI), and the rest of the island keeps running. Every pump quantity (target, rise limit, column, work) reads the suction stream's density only. A new compressor is the pump's mirror for gas, with a pressure-ratio limit and its shaft work booked as heat into the discharge.

Ten agent-runs in seven work packages (section 7), after the backward-Euler basis lands.

## 1. Scope and non-scope

In scope (owner decisions D1-D5, `DECISION_LOG.md`):
1. Tank outlets with a specified phase: top = gas, middle = mixed (today's bulk withdrawal), bottom = liquid. Inputs are not distinguished.
2. The centrifugal pump is liquid-only (solids allowed within the existing solid-mobility rules). A gas or two-phase inlet is an error, shown with a typed reason. Proposed behaviour: the pump goes CLOSED and the island keeps running (decision point O1).
3. A new gas compressor: gas-only inlet, same error rule for a liquid or two-phase inlet.
4. Pump calculations and their verification use the suction density only; the rise limit keeps `riseLimit = maximumAddedPressure * rho_suction / pumpReferenceDensity` (`PassiveStepSolver.java:1051-1053`).
5. Every new decision tolerates being seen one 5 s slice late; nothing needs sub-slice event timing.

Non-scope:
- Separation of the two liquids at a bottom outlet (water decant, oil skimming). The bottom outlet draws hydrocarbon liquid and free water together in proportion to their volumes.
- Phase-selective module withdrawals: `ScheduledTransfer.Withdrawal` stays bulk (`ScheduledTransfer.java:9-12`).
- Phase selection at generators (a generator supplies its specified state, bulk), voids and junctions (a junction's outflow is always its mixture).
- Cavitation / NPSH modelling (section 3.6 explains why the pump check reads the source, not the inlet junction).
- Pump or compressor curves, variable speed, surge, anti-surge recycle.
- Multi-block tanks (none exist; section 4.1 gives the rule a later multi-block tank should follow).
- Save, checkpoint, wire or material compatibility: a fresh world tests the new formats (AGENTS.md, save rule).
- Crystals of the CO2 pilot branch (unmerged): if it merges, crystals stay in the vessel as its P4 rule says, and no port carries them.

## 2. What the base does today

Facts the design builds on (*read*, 9674bf1):

- **A connection has no ends with attributes.** `PassiveNetwork.Pipe(id, first, second, sections, control, blockedDirections, filter)` (`PassiveNetwork.java:61`); `blockedDirections` is a two-bit mask, 1 forbids first-to-second, 2 the reverse (`:53-60`, `:85`); `Pipe.Identity` is what keys workspaces (`:75-82`). Node kinds RESERVOIR, JUNCTION, GENERATOR, VOID, PORT (`:11`).
- **The donor term is the node's bulk.** Per node the Newton caches one `Transport(moles, density, viscosity, specificEnthalpy, velocityLimit)` built from the whole state: `PhaseLayout.totalAmounts(state)`, `mass/volume`, the volume-averaged viscosity, `enthalpy/mass`, `model.velocityLimit(state)` (`PassiveStepSolver.java:1699`, record `:1704`; the Jacobian sweep rebuilds it per perturbed node, `:1916-1917`). `nodeAccumulate` books every connection at `moving*amounts[c]/upstream.mass()` with `upstream` the donor's whole state (`:1748-1760`), solid moments likewise (`:1757-1758`), energy at `transport.specificEnthalpy` (`:1759-1760`); a junction mixes arriving `carried.moles/source.mass()` (`:1764-1775`). `edgeRows` reads the loss on the donor's `density`/`viscosity` (`:1789-1793`) and caps a passive connection on the driving pressure's end, a device on its flow's donor (`:1830-1856`).
- **The static column** of each connection is chosen per pass from the two ends' bulk densities (`headDensities`, `:1453`, `:1530-1592`), and the hydraulic row is `P_a - P_b - rho_head g dz + signedHead - loss` (`:1807-1813`).
- **Structural zeros assume bulk withdrawal.** `buildSparsity` removes every non-total-amount unknown of a node (phase allocation, T, P) from every material row, because "phase allocation/T/P cannot change bulk component flow at fixed total amounts and kg/s" (`:1618-1627`).
- **The reconstruction assumes bulk withdrawal.** `ConservativeTransport.reconstruct` solves one linear system for each node's end mass fractions `w`: a vessel row is `(endMass + dt*outgoing) w - sum_in dt|q| w_donor = old` (`ConservativeTransport.java:182`, `:188-194`), so a donor always loses its own end composition. A fixed donor (generator) goes to the right-hand side with its stored fractions (`:193`, `storedFractions` `:121-128`). One factorization serves all components (`:203-210`). Energy books `upstream.enthalpy()/upstream.mass()` of the reconstructed donor (`:250`); pump work books `dt q v_suction head / eff` on the suction node's bulk (`:257`); negative or non-closing fractions throw (`:216-218`).
- **Boundaries are one-way by kind.** `boundaryAllowed` forbids flow out of a void and into a generator, plus the blocked mask (`PassiveStepSolver.java:1233-1237`). `closeDeadHeads` closes, before the first pass, a passive run between non-junction ends that has no admissible direction, asking each direction against its own column (`:1128-1232`). The pass loop closes a connection that converged in a forbidden direction, device first (`:296-345`); a closure is never cleared within a solve.
- **Pumps.** `FlowControl.Pump(targetVolumeFlow, maximumAddedPressure, efficiency)`; modes PASSIVE, PUMP_TARGET, PUMP_HEAD_LIMIT, VALVE_*, CLOSED and the presentation-only *_VELOCITY_LIMIT (`FlowControl.java:4-19`). Initial mode from the start state (`PassiveStepSolver.java:146-153`), carry of HEAD_LIMIT/CLOSED from the previous solve with the reopening offer taken on the start state (`:158-172`), pass-loop transitions (`:302-312`), target row `flow/rho_a - Q` and head-limit row `head - riseLimit(tr[a].density)` (`:1858-1868`), rise limit on suction density (`:1051-1056`), work `max(0,q)/rho_a * max(0,head)/eff` into the discharge node (`:1780-1784`). The suction of a physical pump is the pump block's own junction, which since basis WP1 owns a holdup `m_J` (`PassiveNetwork.sizeJunctionHoldups` :64-76 at c32acac) *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)*: the pump is a retained node compiled to a JUNCTION (`PhysicalFluidTopology.java:96-99`) and the run on its outlet side carries the pump control with `first` = that junction (`:106`, `:117`).
- **No inlet-phase concept anywhere** (`HANDOFF_REVIEW.md` 8.9 (e)); the only phase-aware outlet hook is `SolidMobility.Outlet {MIXED, ORGANIC_LIQUID, WATER, GAS}`, and every caller passes MIXED (`SolidMobility.java:7-9`, `SolidEventIntegrator.java:87`).
- **Tank blocks.** A reservoir is one `FluidDeviceBlock` of kind RESERVOIR with a volume setting of up to 1000 m3 (`FluidDeviceSpec.java:16`); it connects on all six faces (`FluidDeviceBlock.java:55-60`), actuators and filters only along their facing axis (`PhysicalFluidTopology.java:28-29`). The compiler records which blocks a link joins but not the face (`PhysicalFluidTopology.java:56-68`); the face is lost after compile.
- **Velocity limit.** `velocityLimit(state) = min(maximumVelocity, isothermal acoustic bound)` of the whole state (`FluidThermodynamics.java:164-176`); the configured maximum is 100 m/s by default (`CreateChemE.java:96`). A two-phase bulk has a low mixture acoustic bound, so a gas draw from a wet tank is capped far below what its vapour alone allows (*inferred* from `:166-176`).

## 3. Model design

### 3.1 The pipe-end port

*Proposed.* `PassiveNetwork.PhasePort { BULK, VAPOR, LIQUID }` (named `PhasePort`, not `Port`: `NodeKind.PORT` is the rate-solve vessel copy; WP0, C.4) *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)* and two fields on `Pipe`: `firstPort`, `secondPort`. They enter `Pipe.Identity` (they change equations) and the certificate signature (`IslandCertificate.java:341-354`). Every existing constructor passes BULK, so every graph built in code today, the exact-regression chain among them (`SolverRegressionHarness.java:128-139`), is unchanged. A non-BULK port is valid only on an end whose node is RESERVOIR or PORT (the rate-solve copy of a reservoir, `SolidEventIntegrator.java:68-72`, which reuses `graph.pipes()` and so keeps the ports); the constructor refuses it on a junction, generator or void.

A port's *stream* acts only on **outflow** from its end. Inflow through any port is delivered to the vessel exactly as today (D3: inputs are not distinguished). The port's *driving pressure* (the node's pressure plus a per-end offset, zero until the level head D9) acts in both directions (`LEVEL_HEAD_REVIEW.md` section 9, amendment 1).

### 3.2 The per-end transported stream The port's **driving pressure** (the vessel pressure today; plus a level head if option B of `LEVEL_HEAD_REVIEW.md` is adopted) applies in both directions. Amended 2026-09-26 after the level-head evaluation.

*Proposed.* The Newton's per-node `Transport` becomes a per-(node, port) stream, built from the decoded state with no extra flash or EOS root (every term below is already computed inside `FluidThermodynamics.state`, `:213-241`, or is arithmetic on the state record `:406-435`). A node builds only the streams its ends use; BULK is exactly today's `Transport`.

| field | BULK (today) | VAPOR (top) | LIQUID (bottom) |
|---|---|---|---|
| moles | `totalAmounts(state)` | `vaporView()` + water vapour | `liquidView()` + free water |
| mass (the normaliser, new field) | `state.mass()` | sum of vapour moles x MW | liquid + free-water moles x MW + solid moments' mass |
| density | `mass/volume` | vapour mass / `vaporVolume` | that mass / (`liquidVolume` + `waterVolume` + solid volume) |
| viscosity | volume average (`PassiveStepSolver.java:1281-1289`) | the vapour term of that average | the liquid and slurry terms of that average |
| specific enthalpy | `enthalpy/mass` | (n_v h_v + n_wv h_wv(T)) / mass: `vaporProperties().molarEnthalpy()`, `prepared.vaporEnthalpy()` | (n_l h_l + n_wl h_wl(T,P) + H_solids) / mass: `liquidProperties().molarEnthalpy()`, `waterLiquid(t,p,prepared)`, `solidMoments().enthalpy` |
| velocity limit | `velocityLimit(state)` | min(vmax, sqrt(k_v/rho_v)), k_v the vapour stiffness term of `isothermalAcousticBound` (`:168-171`) | min(vmax, sqrt(1/(kappa rho_l))) from the liquid compliance term (`:167`) |
| solid moments | all, over `state.mass()` | none | all, over the stream mass |
| phase volumes (presentation) | all three | vapour only | liquid and water |

Every reader of the donor switches from the node to the stream of the end the flow leaves: `nodeAccumulate` divides by `transport.mass` instead of `upstream.mass()` (bitwise the same double for BULK, which is what keeps the exact regression bitwise), the junction mixing rows read the stream of the edge's live upwind donor end (they read the live donor, not a frozen one: `mixed=donor`, PSS :1840-1846 at c32acac) *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)*, `edgeRows`' loss and cap read the stream of the donor end (a passive connection's cap keeps choosing its end by the driving pressure, `:1830-1832`), and the per-pass static column reads the stream density of each end (`headDensities`, `:1533-1534`; `closeDeadHeads`' two columns, `:1228-1229`). Appendix A lists every place. A stream whose phase the vessel does not hold (a top port on a liquid-full vessel, a bottom port on a gas-only one) reads the bulk density in every column and sign test (decision A8; plan gap C.2); WP1 defines the rest of the absent stream (`PHASE_PORTS_REVIEW.md`, WP1). *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)*

On a pump or compressor edge the column is the suction stream's density (the `pumpColumn=suction` rule of 8.9 (e), adopted here whether or not the basis plan adopts it, because owner decision D4 fixes it).

### 3.3 Vessel balance, flash, junctions and the conservative reconstruction

**What already works (*inferred* from the code read).**
- *The vessel's own rows need nothing new.* A phase-selective withdrawal enters a vessel only through its backward-Euler targets (`nodeAccumulate`); the node's component, energy, volume and equilibrium rows are the same rows, and the stream is read off the step's end state, so the vessel loses "what its phase holds at the end of the step", the implicit form backward Euler already gives the bulk. A phase that appears or disappears is handled by the existing outer `phaseCorrection` loop (`PassiveStepSolver.java:1105-1121`): the phase layout is frozen per pass, so within one Newton the drawn phase exists or does not.
- *No new column or row-to-column coupling.* The donor's columns already reach every receiver row (`buildSparsity`, `:1598-1606`).
- *Reachability stays a superset.* `reachableComponents` lets every component a vessel holds reach through any connection (`:595-629`); a vapour draw carries every component at some level, so the superset is safe.

**What needs changing.**
1. *Structural zeros* (`:1618-1627`): for a node with any non-BULK port, the phase-allocation, temperature and pressure unknowns do move the component flow it donates, so the `andNot(materialRows)` suppression must skip that node's columns. Nodes whose ends are all BULK keep it, bitwise.
2. *The reconstruction.* The linear system of `ConservativeTransport.reconstruct` makes a donor lose its own end mass fractions, which is only true for BULK. Proposed: a phase-port outflow is booked like a fixed donor, with its stream fractions frozen from the converged candidate: the donor's row loses the edge from its diagonal `outgoing` term (`:152`, `:182`) and gains `-dt|q| w_stream` on its right-hand side; the receiver gains `+dt|q| w_stream` on its right-hand side (the `:193` branch), a junction receiver included: since basis WP1 a junction owns holdup and takes the vessel row (`CT` :169-177 at c32acac). The booked flows are the pinned flows (`pinnedFlows`, `CT` :136, :327-350), not the solved ones, so the frozen stream is booked on the pinned flow. *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)* The matrix stays one matrix for every component, so one factorization is kept. Mass continuity closes because the stream fractions sum to 1 (`:218` holds unchanged). The Newton converged on the same candidate fractions in its targets, so the reconstructed inventories equal the Newton's targets to its tolerance and the equation gate (`PassiveStepSolver.java:363-369`) sees an error of that order (*inferred*; measured in WP1).
   - Rejected alternative: per-component partition ratios `s_c = w_stream,c / w_bulk,c` keep the implicit form but make the diagonal component-specific, one factorization per component (21+).
   - Energy (`ConservativeTransport.java:250`) books the stream's specific enthalpy of the reconstructed donor; solids (`:252`) the stream's solid share. Conservation stays exact by construction: whatever number is booked is subtracted from the donor and added to the receiver, and `checkConservation` (`PassiveStepSolver.java:1238-1264`) is unchanged.
   - The negative-fraction check (`:216-217`) can now fire when a component is more concentrated in the drawn phase than the donor holds of it; that is a rejected step, halved like any other. The throttle of 3.4 makes it rare (risk R3).
3. *The junction holdup of the basis plan* (owned holdup `m_J`, `HANDOFF_REVIEW.md` 7.6-7.10) needs nothing extra: a junction's own outflow is always its mixture; what arrives from a phase port enters its mixing and holdup rows with the stream's composition and enthalpy, as a generator's stream does.
4. *`PipeTransfer.sample`* (`PipeTransfer.java:30-44`) samples the stream, so pipe inspection and the debugger show the drawn phase.

### 3.4 The "outlet phase absent" state

A top port on a liquid-full vessel, or a bottom port on a vessel with no liquid, has no stream to draw. Proposed rule: the port is **closed for outflow** until the phase exists, and open for inflow throughout.

**Availability, stateless, on the step's start state.** With `phi_P` the phase's share of the vessel volume (VAPOR: `vaporVolume/V`; LIQUID: `(liquidVolume + waterVolume)/V`) on the accepted state the step starts from:
- the port may carry outflow iff `phi_P >= phi_open` (proposed 0.01);
- while it is open, an **outflow throttle** caps its outflow at `(phi_P,start - phi_reserve) V rho_stream,start / (n dt)`, with `phi_reserve` = 0.005 and `n` the number of open ports of the same phase on that vessel (equal share; the true constraint couples them, risk R3). It enters `edgeRows` beside the velocity cap as `min(cap, throttle)` for the direction leaving the port, exactly as the filter's room limit does (`PassiveStepSolver.java:1837-1848`): a constant for the solve, a saturated law, exempt in rate solves. So a draining port lands its phase at the reserve on a step boundary instead of past it. *Measured in WP2: only where one step spans the band. Under the state-change controller's short steps a draining port closes at the first step start below `phi_open` and keeps its phase in [`phi_reserve` - drift, `phi_open`) (0.99 % on the drained water tank); the throttle is a per-step floor. `dt` is the solve's step (the backward-Euler step attempted, never the slice); `n` counts the vessel's ends of that phase whose connection may carry outflow by the static rule; the throttle is also off while a device holds `PUMP_TARGET` (as the filter room limit), and the pass's start flows are clamped to it (decisions A14, A15).* *(Amended 2026-09-26 after WP2, `PHASE_PORTS_REVIEW.md` WP2.)*

The gap between `phi_reserve` and `phi_open` is the **band**: a port that drained to the reserve cannot reopen until its phase has regrown by half a percent of the vessel, so it cannot open and close on alternate steps by itself. It needs no carried state, which keeps certified replay free of a new history input (8.9 (b)). *WP2: since the port usually closes just below `phi_open`, a port fed while it drains duty-cycles about `phi_open` (measured: 45 transitions in 300 s at 0.1 s slices, 9 at 5 s, no failure; R1). Hysteresis in the committed endpoint mode is the owner's option (review WP2, section 10).* *(Amended 2026-09-26 after WP2, `PHASE_PORTS_REVIEW.md` WP2.)*

**Where in the pass loop.** Availability is one more reason for `boundaryAllowed` (`:1233-1237`) to refuse a direction: flow leaving an end whose port is unavailable. `boundaryAllowed` is static and state-free (PSS :1303 at c32acac) while the reopen test evaluates on the step's end states, so WP2 passes an availability mask taken from the step's start state to both (C.3 caveat). *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)* Every existing reader then follows without new code paths:
- `closeDeadHeads` (`:1181-1232`) closes a run between non-junction ends that has no admissible direction at the start point, each direction on its own column;
- the pass loop closes a connection that converges in the refused direction, device first (`:340-345`);
- `reachableComponents` (`:611-612`), `startPoint`'s junction propagation (`:991`) and the approximation probe (`:568`) stop sending material out of an unavailable port.
- *also the two other `boundaryAllowed` readers, `initialPhaseSeeds` and the balanced junction seed, and the boundary-reopen test `reopenable`, all on the one start-state mask (`PassiveStepSolver.closedPorts`).* *(Amended 2026-09-26 after WP2, `PHASE_PORTS_REVIEW.md` WP2.)*

A vessel end whose flow is inflow is never affected. The availability mask is part of the workspace and cycle key (`WorkspaceKey`, `:239-241`), like `boundaryClosed`.

**Opening part-way through a step.**
- *Inflow* into a vessel through a port that `closeDeadHeads` held shut at the start is the BROKEN class of 8.8 (c) (a one-way closure held through a long step); the basis plan's `boundaryReopen` rule covers it, and it must treat the port refusal as one more per-link allowance (its "every link allows by more than max(1 Pa, 1e-6 P)" test).
- *Outflow* from a port whose phase appears during the step opens at the next step start: DELAYED by one step, never bottled, because the phase accumulates in the vessel meanwhile.

**Phase vanishing inside a step.** The throttle holds the draw to what the start state had; if the flash still removes the phase (a depressurising tank boiling its liquid), the next pass's layout lacks it, the port's stream is absent and its outflow cap is zero, so the converged flow is zero with no illegal direction. The next step start finds `phi_P < phi_open` and closes it.

**Interaction with the velocity cap and a device downstream.**
- The port's cap is its own stream's: a vapour draw from a wet tank is capped by the vapour's acoustic bound, not the mixture's.
- A pump or compressor fed by a port: see 3.6 (the `INLET_EMPTY` start closure and the target-above-supply start in HEAD_LIMIT).

**Liquid-full vessels.** A vessel whose only outlets are top ports and which is fed liquid has no outflow once it is liquid-full, so its pressure rises with the liquid's compressibility, the "liquid-full vessels may increase in pressure" behaviour of the v1 design. The tank screen says so (section 4.3); a side port is the overflow.

### 3.5 Level head at a bottom outlet (optional)

*Needs:* a level, that is a liquid volume over a cross-section, so a tank height or footprint the model does not have. A reservoir is one block holding up to 1000 m3 (`FluidDeviceSpec.java:16`); its node has one elevation, the block's y (`FluidDeviceSpec.java:62`). Two readings are possible:
- the block's own metre: head `rho_l g phi_l x 1 m`, at most about 9.8 kPa for water;
- a new `height` setting per tank: head `rho_l g phi_l H`, plus a new derivative of the port's driving pressure on the phase volume unknowns.

*Recommendation: not in v1.* The dominant hydrostatic effect across elevations is already carried by the connection's column, and a bottom port's column is now the liquid's density (3.2), which is the correct fluid for the pipe below a drain. The level head would add a GUI setting, a row term and a new chatter mode (a bottom port's driving pressure falling as it drains) for a single-digit-kPa effect in a one-block tank. Decision point O2. Evaluated 2026-09-26 in `LEVEL_HEAD_REVIEW.md`: no new unknowns or rows, about 0-2 % wall on islands with liquid at a bottom port, bitwise on gas-only islands; recommendation there = option B (bottom port only, fixed 1 m height, after phase-port WP2), pending the owner. *Decided (D9, option B) and implemented 2026-09-26 after WP2: the head is `g m_c H/V` at a LIQUID port, H = 1 m, read by every driving-pressure site through `PassiveStepSolver.portHead`; `PHASE_PORTS_REVIEW.md` D9.* *(Amended 2026-09-26 after D9.)*

### 3.6 Liquid-only pump

**Suction stream and density (D4).** Every pump quantity reads the stream of the pump edge's first end (its suction):
- target row `flow/rho_s - Q` (`:1859`); rise limit `riseLimit(rho_s)` (`:1051-1056`, unchanged formula); head-limit row (`:1863`);
- `demand` and its column (`:1073-1076`); carry offer (`:169-170`); start flows (`:941`, `:947-949`, `:1003`, `:1026-1033`);
- initial and pass-loop velocity tests (`:150`, `:307`); work in the Newton (`:1782`) and in the reconstruction (`ConservativeTransport.java:257`);
- the GUI's "limit on this fluid" (`FluidWorldAuthority.java:294`).

For a physical pump the suction is its own junction, so `rho_s` is what arrives at the pump inlet. No discharge-side density enters any pump calculation.

**Inlet phase check: on the supply, not the inlet junction.** *Proposed.*
- *What is checked.* The check reads the stream the suction line will deliver: starting from the pump edge's first node, if that node is a vessel or generator, the stream of that end (a generator supplies its state as BULK); if it is a junction, walk its other connection through degree-two unactuated junctions (the run `closeDeadHeads` walks, `:1203-1215`) to the first node that is not one, and take the stream of that end. A branch junction (degree three or more) is the end of the walk and supplies its start-state mixture.
- *The measure.* The vapour share of the supply's fluid volume, `alpha_v = V_vapour / (V_vapour + V_liquid + V_water)`.
- *Why not the inlet junction.* Reading the pump's own junction would make a saturated liquid refused as soon as friction flashes it at the inlet, and the refusal would stop the flow that caused the flash: a two-slice limit cycle on every pump drawing from a separator bottom (risk R2). Reading the source is flow-independent. The flashing that does happen at the inlet still lowers `rho_s` and so the rise limit, a soft derate, not an error.

**The error behaviour (recommended; decision point O1).**
- *Mode.* A new carried mode `INLET_WRONG_PHASE` (`FlowControl.Mode`, persisted in the saved interval's endpoint modes, `FluidCheckpointCodec.java:400`, `:450`).
- *In the solver.* It is CLOSED: the actuator row pins the flow to zero (`:1866`), and the pass loop may not reopen it within the solve (it is excluded from the CLOSED-to-HEAD_LIMIT offer at `:311` and from the carry offer at `:158-172`).
- *When decided.* Once per interval (slice) at its start, on the committed state:
  - refuse when `alpha_v > alpha_refuse` (proposed 0.02);
  - a pump whose committed endpoint mode is already `INLET_WRONG_PHASE` stays refused until `alpha_v < alpha_resume` (0.005).
- *Replay.* The hysteresis input is the committed endpoint mode, never solver-local history, so a woken certified island decides exactly as one that solved every interval (8.9 (d)).
- *The rest of the island.* It keeps integrating; the other lines are untouched.
- *Reason.* The view shows the reason next slice: "ERROR: pump inlet not liquid (vapour 34 % by volume, from <source device>)", computed read-only from the committed graph with the same walk (a pure function of graph and states, shared with the solver).

*Alternative:* a typed island hold (the `ThermoDomainViolation` hold-and-wait path). It stops every line on the island for one misconnected pump and needs an input change to wake. Not recommended.

**Solids.** Allowed at the inlet. Their mobility is judged by the existing guard (`SolidEventIntegrator`, `SolidMobility.check`); no new rule.

**Suction runs dry mid-slice.** The pump draws from a bottom port through the throttle of 3.4, which lands the tank's liquid at the reserve on a step boundary. At the next step start the port is unavailable, and so is the pump's supply: the pump starts that step in a second carried mode `INLET_EMPTY` (acts as CLOSED, no hysteresis of its own: it mirrors the port, which has its band). It is decided per step because a pump running against a suction that cannot deliver is infeasible (next paragraph). The reason ("no liquid at the suction: <tank> bottom outlet") shows with the next slice.

**Target above what the suction can deliver.** A pump on PUMP_TARGET whose suction link is capped below its target has no solution: the target row fixes the pump's flow, the cap fixes the suction flow, and the junction balance equates them (*inferred* from `:1855`, `:1859`; the base may already fail this way with a narrow suction line; WP3 tests it). The throttle makes this common. A partial rule already exists (PSS :294 at c32acac starts HEAD_LIMIT when the target exceeds the pump edge's own cap, and :293 whenever any pipe of the island is blocked) *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)*. Proposed: the initial-mode test (`:148-150`) also compares `Q rho_s` with the smallest start-state cap on the suction walk (each link's velocity cap on its own donor stream, and the port throttle) and starts the pump on PUMP_HEAD_LIMIT when the target exceeds it. On HEAD_LIMIT the flow is free and the junction pressure absorbs the difference; the existing pass-loop transitions then decide as today.

### 3.7 Gas compressor

*Proposed.* `FlowControl.Compressor(targetVolumeFlow, maximumPressureRatio, efficiency)`. Pump and compressor share a sealed `FlowControl.Mover` interface (`targetVolumeFlow`, `efficiency`, `riseLimit(suction stream)`, `admits(supply)`, `work(...)`), so every `instanceof FlowControl.Pump` in the solver becomes `instanceof Mover` and uses the pump's modes: PUMP_TARGET, PUMP_HEAD_LIMIT, CLOSED, PUMP_VELOCITY_LIMIT, the two new inlet modes, carry and shutoff band. The names stay PUMP_* in the code; the GUI names the device.

**Limit form (decision point O3).**
- *Recommended: pressure ratio.* `riseLimit = (r_max - 1) P_suction`, where P_suction is the pump edge's first-node pressure at the trial. It is the same row shape as the pump's density-scaled limit (the pump's already reads a trial property of the suction node, `:1860-1863`), so the head-limit row, the shutoff margin `riseLimit - demand`, the carry offer and the band are reused unchanged. It is what a compressor does, and it stays meaningful from 1 bar to 100 bar: a 3:1 machine adds 2 bar at 1 bar suction and 20 bar at 10 bar.
- *Alternative: fixed added pressure,* the pump's shape without the density scaling. Simpler to explain, but a setting that suits 1 bar suction is either useless or absurd at 20 bar.
- GUI bounds proposed: `1.01 <= r_max <= 10`.

**Target and velocity.** The target is a suction volume flow (m3/s at inlet conditions), converted with the suction stream's density, so the mass flow falls with the suction pressure, as it should. The velocity limit is the gas stream's own (min of the configured 100 m/s and the vapour's acoustic bound, 3.2); the initial and pass-loop tests (`:150`, `:307`) apply unchanged.

**Inlet check.** The same walk as the pump; refused (`INLET_WRONG_PHASE`) when the supply's condensed share by mass (liquid, free water and solids over the stream mass) exceeds 0.01; resumes below 0.002. Any solid population above the trace volume fraction (`SlurryTransport.DEFAULT_TRACE_VOLUME_FRACTION` = 1e-8) refuses too. `INLET_EMPTY` applies to a top port with no vapour.

**Shaft work (decision point O4).**
- *Recommended: book it as heat into the discharge,* as the pump's work is booked (`PassiveStepSolver.java:1780-1784`, `ConservativeTransport.java:257`). The formula is the ideal isothermal work on suction properties only, `W = max(0,q) (P_s/rho_s) ln(1 + max(0,head)/P_s) / eff`, efficiency 1 in v1 like the pump's default (`FluidWorldAuthority.java:160`).
  - It is smooth in the head unknown and uses suction data only (D4's rule carried over).
  - Hand estimate for nitrogen as an ideal gas at 298 K and ratio 3 (not measured): the discharge heats about 93 K, against about 110 K for ideal adiabatic compression and 170 K for the pump's `q v_s head` form. The pump form grows linearly in the ratio and would push the 900 K domain ceiling near ratio 10; the log form grows as ln r.
  - The ledger stays first-law exact (`pumpWork` in `checkConservation`, `:1263`).
- *Alternative: ignore the work* ("an ideally intercooled compressor"). The discharge carries the suction's specific enthalpy, the ledger shows no shaft work, and the pressure rise is free of energy. Cheaper, but a first-law hole a player can see in the energy view.

**Topology.** `TopologyCompiler.Kind.COMPRESSOR`, an actuator like the pump:
- connects only along its facing axis (`FluidDeviceBlock.java:55-57`, `PhysicalFluidTopology.java:28-29`);
- included in the zero-storage cycle rule (`TopologyCompiler.java:75-91`) and in the zero-target substitution of an invalid pump (`PhysicalFluidTopology.java:154-157`).

### 3.8 Solids, filters and the event integrator on phase ports

- Solids leave through BULK (as today) and LIQUID ports (at their share of the condensed stream), never through VAPOR.
- `SolidMobility.check` gets the end's outlet: BULK and LIQUID map to MIXED, VAPOR to GAS (always mobile, `SolidMobility.java:77`, `:89`), at `SolidEventIntegrator.java:87`. Its liquid velocity `|q|/donor.mass() * liquidVolume/area` (`SolidMobility.java:93`) must use the stream's mass.
- A filter's capture fraction reads the stream's solid share (`PassiveStepSolver.java:1752`, `:1767`, `:1777`; `ConservativeTransport.java:151`, `:253`).

## 4. Runtime mapping

### 4.1 Which face is which (world)

*Proposed rule for the single-block tank:* the **UP** face is the top outlet (VAPOR), the **DOWN** face the bottom outlet (LIQUID), and the four horizontal faces the middle outlet (BULK). This is where a vent, a drain and a side nozzle sit on a real vessel. It needs no configuration, and a player reads it off the block.
- The compiler must remember the face. `PhysicalFluidTopology.compile` creates each link in a loop over directions (`:56-68`); for a link with a RESERVOIR end it records that end's face (direction from the tank to the neighbour). When a run becomes a `Pipe` (`:101-121`), the run's first and last segment links give the ports of its two ends, following the `fromEnd ? b : a` swap at `:117`.
- An actuator or filter attached directly to a tank face takes the same rule (the link is the boundary link at `:60-61`).
- A later multi-block tank would use the port's vertical position on the structure: top layer VAPOR, bottom layer LIQUID, the rest BULK. Not needed now.
- *Alternative:* a per-face setting in the tank's screen. More flexible, but one more configuration event and ledger field per tank. Rejected for v1.

### 4.2 Blocks and registration

- New block `fluid_compressor` (`ModBlocks.java:14-21` pattern), with its blockstate, model, item, loot and lang entries.
- Its default control is `Compressor(0.05 m3/s, 3.0, 1)` in `FluidWorldAuthority.place` (`:158-165`).
- Validity switch in `PhysicalFluidTopology.Device` (`:24`); `actuator()` and `face()` include it.

### 4.3 GUI (`FluidDeviceScreen`), engine-owned presentation

- **Pump.** The status line (`FluidDeviceScreen.java:289`) already shows the device mode and "(limit N Pa on this fluid)" (`FluidWorldAuthority.java:293-294`). It gains the typed reason for `INLET_WRONG_PHASE` / `INLET_EMPTY` in the warning colour the screen already uses for a status containing "ERROR" (`:350`, `:354`), and the limit is computed on the suction stream.
- **Compressor.** Its own case beside PUMP (`:140`): fields "Suction flow (m3/s)" (reuse `flow_input`) and "Max pressure ratio (out/in)" (new `ratio_input`). Rail: last flow, pressure change and discharge/suction ratio. The same status reasons ("ERROR: compressor inlet not gas ...").
- **Tank.** A line per connected face on the tank page: "Top: gas outlet (open | closed: no vapour)", "Side: mixed outlet", "Bottom: liquid outlet (open | closed: no liquid)", with the last-interval flow of that connection. The rows are derived read-only from the committed graph: pipes whose first or second end is the tank and their ports, availability recomputed from the committed state by the same stateless rule, flows from the last result, as `view` already sums them (`FluidWorldAuthority.java:286-288`).
- **Wording.** "Bulk withdrawal" wording where it exists becomes "Side outlets draw all phases together".
- **Cadence.** Presentation is unchanged: the new strings are built in `view()` from cached committed data (no solver, no thermo call beyond arithmetic on committed states), published on the engine's schedule, sent only to subscribed clients (AGENTS.md, player-facing updates).

### 4.4 Checkpoint, NBT and wire (no compatibility work)

- `FluidCheckpointCodec.VERSION` 5 to 6 (`:51`; the BE batch's WP2, commit 5b708fa, already took 5 for the certificate merge). Amended 2026-09-26.
  - Topology writes two port bytes per pipe (`:370-382`) and reads them back (`:492-507`).
  - Registration and topology write control tag 3 for the compressor (`:218-224`, `:230-236`, `:377-381`); the JSON graph decoder gains "compressor" and the ports (`:831-849`).
  - The new modes are persisted through the existing endpoint-mode ordinals (`:400`, `:450`).
- `FluidNetwork.Controls` gains `maximumPressureRatio` (`FluidNetwork.java:37-45`, control built at `:213`).
- `IslandCertificate` digests ports and the compressor (`:341-354`).
- A world saved by 0.5.x is refused with the existing "format N cannot be read" message. WP6 tests on a fresh world. No migration, no absent-field default, no legacy test (AGENTS.md).

## 5. Backward-Euler basis and the delay policy

The rule of 8.8 (f): a mode is decided on a step's state and applied to the whole step; an opening decision is safe, a closing or reseating decision needs the audit the pump needed (its start point, and whether its closed re-solve's own end state would reopen it). The new decisions:

| decision | kind | taken on | applies to | audit |
|---|---|---|---|---|
| port available (phase present >= `phi_open`) | opening | step start state | the step | Safe. A phase appearing mid-step opens one step late (DELAYED); nothing is bottled, since the phase stays in the vessel |
| port closed (phase below `phi_open`) | closing | step start state | the step, outflow only | The throttle keeps every step's draw above the reserve (it lands the phase on the reserve only where one step spans the band; otherwise the port closes at the first step start below `phi_open`; WP2). While closed, only physics regrows the phase, and the next step start re-decides. No wrong-closed fixed point: closing does not change the quantity it is decided on |
| inflow through a closed port / a one-way run shut by `closeDeadHeads` | reopening | step end state | the step, via the basis plan's `boundaryReopen` retry | The BROKEN class 3/3' of 8.8 (c). This plan requires `boundaryReopen` to count the port refusal as a link allowance. Fixture: the "inflow through a closed bottom port" case of section 6 |
| phase vanished within the step | closing (implicit) | the pass whose layout lost the phase | the rest of the solve | The cap goes to zero, so there is no illegal direction and no active-set change. The next step start closes the port |
| pump / compressor `INLET_WRONG_PHASE` | closing, with hysteresis | slice start, committed state and committed mode | the slice | The supply walk reads the source, not the pump's own junction, so closing the device does not change its own input unless the source is a branch junction (risk R2). DELAYED by at most one slice both ways (D5) |
| `INLET_EMPTY` | closing | step start, from the port's availability | the step | Mirrors the port, which has its band. Reopens with the port |
| start on PUMP_HEAD_LIMIT when the target exceeds the suction supply | opening of a free flow | step start | the step's first pass | Taken from the start state; the pass loop may move it to TARGET or CLOSED as today |
| compressor TARGET / HEAD_LIMIT / CLOSED | as the pump | as the pump | as the pump | The pump's audit (8.8 (c), `pumpReopenStart`, `pumpColumn=suction`) carries over unchanged: same rows, same margin, suction-only columns |

**What an accepted slice end guarantees for the new devices** (the 8.8 (f) list, extended; to be measured in WP2-WP4):
- Species, solids and energy conservation exactly (reconstruction plus `checkConservation`, unchanged). Compressor work enters the ledger as pump work does.
- A phase port drew only its phase, at the end-state composition of that phase, and never below its reserve by more than the flash moved the interface within the step. A closed port carried zero outflow for the whole step; inflow may be nonzero.
- A pump or compressor in `INLET_WRONG_PHASE` or `INLET_EMPTY` carried exactly zero for the whole step or slice.
- A pump's discharge stays at or below its suction-density shutoff, and a compressor's at or below `r_max P_suction` plus the column, within the shutoff band. Every endpoint flow stays within its own stream's velocity cap to 2e-8.

**What it does not guarantee:**
- that a pump never carried a two-phase stream: for up to one slice after its source turns two-phase it may, as all pumps do today;
- the time a phase appeared or vanished, which is seen at the next step or slice start;
- that a draining port stopped exactly at the reserve, when the flash moved the interface.

## 6. Tests and gates

**New unit fixtures** (science, `com.wormzjl.createcheme.science.fluid.network`, run by `fluidScienceTest`):
1. *Phase outlet on a two-phase tank.* A 1 m3 tank of water under nitrogen (and one with a light hydrocarbon liquid under methane), three connections to three voids: VAPOR, BULK and LIQUID ports. Assert that each connection's transferred stream (`PipeTransfer`) has the phase split of its port:
   - the VAPOR stream has no hydrocarbon liquid or free water;
   - the LIQUID stream has no vapour-phase moles and carries the solids;
   - the BULK stream is today's bulk to the last bit (compared against a BULK-only run).
   
   Component and energy ledger to 1e-10 / 1e-4 + 1e-6 scale, at 0.1 s and at 5 s slices.
2. *Outlet runs dry.* A LIQUID port drains a two-phase tank to a void: the liquid lands within [`phi_reserve`, `phi_reserve` + flash drift] of the vessel volume *(WP2: within [`phi_reserve` - flash drift, `phi_open`); on the reserve only where one step spans the band, tested separately at step level)* *(Amended 2026-09-26 after WP2, `PHASE_PORTS_REVIEW.md` WP2.)*, the port closes at the next step, and stays closed over twenty 5 s slices. At most one open-to-closed transition. No nonconvergence, no held slice. The same with a VAPOR port on a tank being filled liquid-full.
3. *Inflow through a closed port.* A LIQUID port on a gas-only tank, fed from a pressurised water generator: water enters (inputs not distinguished); once `phi_l >= phi_open` the same port drains when the generator is lowered below the tank. Run at 5 s slices, with the basis plan's `boundaryReopen`. *WP2: a generator refuses inflow, so the draining half runs the port into a junction fed by the generator and drained to a void; the reopen half is a generator line dead-headed at the step start (the tank above the generator, venting below it within the step), and a tank-to-tank run the port alone refuses.* *(Amended 2026-09-26 after WP2, `PHASE_PORTS_REVIEW.md` WP2.)*
4. *Liquid pump on gas: CLOSED with reason.* A pump between two nitrogen tanks, and a pump on a two-phase generator: endpoint mode `INLET_WRONG_PHASE`, flow exactly zero, and the interval FULL, not held.
   - A second, independent water line on the same island integrates bit for bit as it does without the refused pump's line.
   - Hysteresis: a supply swept from 0 % to 5 % and back refuses at 2 % and resumes at 0.5 %.
5. *Liquid pump drains a tank.* A pump from a LIQUID port runs on target, then on HEAD_LIMIT as the throttle binds, then `INLET_EMPTY`; no hold.
6. *Compressor on gas.* Target (flow = Q rho_s), limit (discharge = `r_max P_s` within the band), CLOSED at shutoff. Energy ledger with the work formula. The same case at 5 s and 0.1 s slices lands within the pump's landing tolerance.
7. *Compressor on liquid / two-phase:* `INLET_WRONG_PHASE`, zero flow, island integrating.
8. *Replay:* a certified island with a refused pump and a closed port reproduces the every-interval run bitwise (the `CausalModuleCoordinatorTest` harness shape, 8.9 (a)).

**Existing suites.**
- *Exact regression* (`fluidSolverRegression`): all-BULK graphs must stay bitwise (`chain-100` exact 0); that is WP1's gate.
- *Pump and withdrawal suites.* `PumpJunctionStartupTest`, `DeadHeadedLineIslandTest`, `ElevatedBlockLineIslandTest`, `PumpRiseScalingTest`, `FullTankSolidsEventTest`, `SolidRuntimeTest` and the F1 pumped-fill tests (`FluidPumpedFillLineTest`, GameTest `FluidPumpedFillGameTests`) must stay green, or move to a recorded, intended change. WP0 classifies them. Already visible (*read* unless marked):
  - `PumpRiseScalingTest`: two tests pump nitrogen (:42, :75; `:22-27` is the fixture helper) *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)*, so they become compressor tests or refusal tests. The domain-refusal test needs a compressor to cool the suction instead.
  - `FluidPumpedFillLineTest.aGasTransferClosesAtThePumpsScaledLimitAndCertifies` ("RUPR", nitrogen, `:166-176`) becomes a compressor case.
  - `FlowControlTest` (`:23`, a pump on gas with `gasSetting`) and `NetworkRegimeTest` (`:62-67`, nitrogen at 350 K): to classify.
  - `PumpJunctionStartupTest` crude case and GameTest `heatedCrudePumpStartsIntoNitrogenReservoir` (`FluidPumpStartupGameTests.java:18-31`): a methane-bearing crude at 1 atm and 298/350 K is two-phase (*inferred*), so the pump would refuse. Proposed: raise the generator pressure above the crude's bubble point, keeping the tests' purpose (a zero-storage pump inlet start).
  - Physical-topology fixtures with vertical tank connections: `ElevatedBlockLineIslandTest` reaches its tank from below (rising lines, now a LIQUID port) and from above (falling line, now VAPOR). Inflow is unchanged. No fixture carries flow out of a vertical face, so nothing changes under A1 and no flat control is needed; the only effect on them is the D9 level head (C.2). *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)*
- *Fluid suites* (`fluidScienceTest`, `fluidRuntimeTest`): the count at the basis plan's merge, with every change classified as intended or a defect.
- *GameTests* (`fluidGameTestServer`, 34 `@GameTest` methods in `src/` at 9674bf1 and at c32acac) *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)*. Add one per device: a tank with top/side/bottom outlets, a pump refused on gas, a compressor transfer.
- *Timing* per the benchmark rule: 60 s warm-up plus 60 s measurement, one pair per claim, only if a cost claim is made.

**In-game check** (AGENTS.md GUI rule, langyo/minecraft-mod-mcp bridge, jar in `<worktree>/run/mods`, `.mcp.json` in the worktree root), one scenario per device, fresh world:
1. a water/nitrogen tank with a pipe on top, side and bottom to three voids, the tank screen's outlet lines and each pipe's inspection;
2. a pump on a tank's side (refused, reason shown), moved to the bottom face (runs, then shows "no liquid at the suction" when drained);
3. a compressor between two nitrogen tanks (target, ratio limit, closed), then fed water (refused).

**Cleanup rule.** Probes, rigs and scripts written for measurement are detached before merge into main `tools/<folder>/` with a README and an INDEX row. The batch review names the folders; gates are re-run after cleanup and recorded.

## 7. Work packages

Order and gates. Estimates are agent-runs (one agent session of a normal brief). All Gradle runs one at a time; no campaign needs more than one worker.

| WP | Content | Acceptance gate | Estimate |
|---|---|---|---|
| WP0 | Classification: every test and GameTest with a pump (19 unit-test files construct `FlowControl.Pump(`, 4 GameTest classes place a pump block) by suction phase; every physical-topology fixture with a vertical tank link; target expectations for each (unchanged / compressor / re-baselined). Appendix to this plan. **WP0 done 2026-09-26 (Appendix C).** | The table exists and the owner has seen the re-baselines it proposes | 0.5 |
| WP1 | Ports and per-end streams, science only (3.1-3.3, 3.8): `PhasePort` on `Pipe`, stream builder in `FluidThermodynamics`, every Appendix A reader, the structural-zero exemption, the reconstruction's frozen stream booking, `PipeTransfer.sample`, `SolidMobility` outlet mapping. Amendment 2026-09-26: every static driving pressure of a vessel end (the 11 restatements listed in `LEVEL_HEAD_REVIEW.md`) is routed through one per-end helper with a zero offset, so a level head becomes a one-line change plus tests. **WP1 done 2026-09-26** on branch `claude/phase-ports-compressor`, not merged: commit `35e354d` (`WIP phase-ports WP1: ports, per-end streams, driving-pressure helper`); all four gates passed (fluid suites 413/413 with the junction lines bitwise, exact regression 0.000e+00, 38/38 adjacent, GameTest and mcpCompat sources compile); open base defect and options in the review, section 8. | Exact regression bitwise; fluid suites at defaults identical to base (all BULK); fixture 1 green at 0.1 s and 5 s | 2 |
| WP2 | Phase-absent state (3.4): availability in `boundaryAllowed`, throttle, key, `boundaryReopen` link allowance. **WP2 done 2026-09-26** on branch `claude/phase-ports-compressor`, not merged: commit `d836cf2`: `PhasePortClosureTest` 15/15 (fixtures 2 and 3 at 5 s and 0.1 s, the throttle, the vanishing phase), fluid suites 428/428 with the junction lines bitwise, exact regression 0.000e+00, 38/38 adjacent, GameTest and mcpCompat sources compile; the band lands just below `phi_open` (section 3.4 amendment, owner options in the review). | Fixtures 2 and 3; no nonconvergence in them at 5 s and 0.1 s; fluid suites unchanged | 1.5 |
| D9 | Level head at the bottom port (owner decision D9, option B of `LEVEL_HEAD_REVIEW.md`): `portHead` = `g m_c H/V` at a LIQUID port of a vessel, H = 1 m fixed, through the WP1 helper at all eleven driving-pressure sites; no unknown, row or structural entry. **D9 done 2026-09-26** on branch `claude/phase-ports-compressor`, not merged: commit in `PHASE_PORTS_REVIEW.md` D9 section 10; `LevelHeadTest` 8/8 (drain on the head alone, dead-headed against the head, reopen by the head, two-tank equalisation, manometer, pump into a bottom port, bitwise replay), fluid suites 448/448 with the junction lines bitwise, exact regression 0.000e+00, 38/38 adjacent, GameTest and mcpCompat sources compile; four `PhasePortClosureTest` assertions re-baselined to the bottom-port pressure (A19); the physical-topology re-baselines wait for WP5. | Fixtures 1-5 of the review's section 5; gas-only islands bitwise; fluid suites green | 1-1.25 |
| WP3 | Liquid-only pump (3.6): supply walk, `INLET_WRONG_PHASE` / `INLET_EMPTY`, suction-stream densities everywhere, target-above-supply start, reason text; migrate the WP0 gas-pump tests. | Fixtures 4, 5, 8; the classified pump suites green or re-baselined as agreed | 1.5 |
| WP4 | Compressor (3.7): `Mover`, `Compressor`, ratio limit, work, inlet check, topology kind, invalid-cycle rule. | Fixtures 6, 7; migrated gas-transfer tests green; energy ledger | 1.5 |
| WP5 | Runtime (section 4): face-to-port compile, block and assets, codec format 6, controls and wire, certificate digest, GUI for pump, compressor and tank. **Re-baselines due with the face-to-port compile (D9, `PHASE_PORTS_REVIEW.md` D9 section 4):** once DOWN faces compile to LIQUID ports the level head reaches the physical-topology fixtures fed through a tank's bottom, and four assertions move: `ElevatedBlockLineIslandTest` :216, :220, :229 (rising, rising filter, rising pump lines) and `DeadHeadedLineIslandTest` :243 (C.2's :234), each re-stated to `P_tank + g m_w H/V = P_generator - rho g LIFT` (headspace about 7 kPa lower by `LEVEL_HEAD_REVIEW.md`'s estimate, 0.72 m of water; the code-built equivalent measured -5.61 kPa for a tank charged at 150 kPa); plus the level-head review's fixture 6 (face-to-port and head on a compiled stack) and the optional tank-page head line. | `PhysicalFluidTopologyTest` face cases, codec round trip (ports, compressor, new modes), fluid runtime suite, the three new GameTests | 2 |
| WP6 | Gates and close: fluid suites, exact regression, GameTests, MCP in-game scenarios, tooling cleanup, review `PHASE_PORTS_REVIEW.md`, `CHANGELOG.md` `[Unreleased]` line, INDEX rows | All gates recorded in the review | 1 |

Total about 10 agent-runs. WP1 can start before the basis plan merges (all-BULK exactness is basis-independent); WP2-WP6 gates are stated at 5 s backward-Euler slices and need the basis on `main`.

## 8. Risks

- **R1 Chattering at the interface.** A port near its threshold opening and closing on alternate steps. Mitigated by the stateless band (open at 1 %, throttle lands at 0.5 %). The residual risk is a vessel whose flash moves the phase across the band every step, for example a boiling bottom port under a pressure swing: then the port duty-cycles, but it does not fail. Fixture 2 counts transitions. *WP2 measured: fixture 2 has exactly one transition; but since a draining port closes just below 1 % rather than at 0.5 %, a port fed while it drains duty-cycles about `phi_open` (scratch probe: 45 transitions in 300 s at 0.1 s slices, 9 at 5 s, 5 at 1 s steps; no nonconvergence, no held slice).* *(Amended 2026-09-26 after WP2, `PHASE_PORTS_REVIEW.md` WP2.)*
- **R2 A pump whose suction alternates phase every slice.** Only when the supply walk ends at a branch junction whose mixture depends on the pump's own flow. Mitigated by reading the source (not the inlet junction), the slice-level decision and the 2 % / 0.5 % hysteresis. Worst case, a two-slice limit cycle with the reason shown; no solver failure.
- **R3 Conservation with a phase-selective withdrawal while the flash moves the interface.** Conservation itself is exact by construction. The risk is rejected steps:
  - "Negative transport reconstruction" when a component is richer in the drawn phase than in the vessel;
  - overdraw by several ports of one phase sharing a throttle split evenly;
  - a Zeno-like approach if the throttle is too loose (the domain-floor pattern of 8.8 (a)).
  
  WP2 measures rejections per slice in fixtures 2 and 5. If they are not rare, the throttle becomes a per-vessel constraint row (one new row per vessel with phase ports, no longer "no new row"). *WP2 measured (review WP2 section 4): no rejection came from the throttle or an overdraw; the rejections are state-change halvings, the BROKEN 3' reopen cost of water entering a dry gas tank, and 2 equation-gate halvings with a VAPOR vent that the WP1 tree shows too (4). The constraint row is not needed.* *(Amended 2026-09-26 after WP2, `PHASE_PORTS_REVIEW.md` WP2.)*
- **R4 Test churn.** The pump becoming liquid-only changes every gas-pump fixture, and vertical tank faces change physical-topology fixtures (WP0 sizes it). The accuracy-class failures of the basis plan come on top and must not be confused with these.
- **R5 GUI scope.** Three screens change and a block is added. The tank's outlet lines need connection-level data the tank view does not collect today. Kept to status text and one line per face, with no new page.
- **R6 Liquid-full vessels with only top ports** rise in pressure on liquid inflow (by design, 3.4). A player may read it as a bug; the tank screen names it.
- **R7 Dependency on the basis plan. Closed.** `boundaryReopen`, `pumpColumn=suction` and `replayDeterministic` are unconditional code on `main` (`reopenable` PSS :112 with PIS :190-197; PSS :1908; `replayStart` PIS :311, PSS :221 at c32acac) *(Amended 2026-09-26 after WP0, Appendix C "Where c32acac contradicts the plan".)*. (Original text: they were prototype switches, not code on `main`, and inflow through a closed port could bottle for a step if `boundaryReopen` were dropped, 8.8 (b) class 3.)

## 9. Decision points for the owner

| # | Decision | Recommendation | Alternative |
|---|---|---|---|
| O1 | A pump or compressor with the wrong inlet phase | CLOSED with a typed reason (`INLET_WRONG_PHASE`), island keeps running, decided per slice with hysteresis | Island hold with a typed reason (freezes every line on the island until an input changes) |
| O2 | Level head at a bottom outlet in v1 | No: the column already uses the liquid's density; the level term needs a tank height the model lacks, for at most about 10 kPa in a one-block tank | Yes, with a per-tank height setting |
| O3 | Compressor limit form | Pressure ratio `P_out/P_in <= r_max`, rise limit `(r_max - 1) P_suction`, same modes and rows as the pump | Fixed added pressure (the pump's shape without density scaling) |
| O4 | Compressor work | Book it as heat into the discharge, ideal isothermal work on suction properties over efficiency (efficiency 1 in v1) | Ignore it (an "ideally intercooled" compressor, no ledger work) |

Further defaults this plan takes unless the owner objects (reversible, `DECISION_LOG.md` A1-A7):
- A1: the face rule (UP = gas, DOWN = liquid, sides = mixed);
- A2: the bottom outlet draws both liquids and the solids together;
- A3: the inlet check reads the source stream, so there is no cavitation error;
- A4: the thresholds of Appendix B;
- A5: the inlet decision at slice start;
- A6: module withdrawals and generators stay bulk;
- A7: the compressor refuses solids.

## 10. Files read and cited

Documentation:
- `documentation/2026-09-24-mixed-gas-junction/HANDOFF_REVIEW.md` 8.1-8.9, in particular 8.4, 8.6 (b)-(g), 8.8 (a)-(g), 8.9 (b), (d), (e);
- main checkout `documentation/2026-09-15-fluid-network/SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN.md` (lines 18, 33, 403-409, 499) and `..._DESIGN_REVIEW.md` (item 7);
- `documentation/2026-09-24-coolprop-low-temperature/P4_NETWORK_COUPLING.md` (the crystal rule, grep only).

Sources at 9674bf1 (`src/main/java/com/wormzjl/createcheme/...`):
- `science/fluid/network/`: `PassiveNetwork.java`, `FlowControl.java`, `PassiveStepSolver.java` (lines as cited), `ConservativeTransport.java`, `PipeTransfer.java`, `ScheduledTransfer.java`, `SolidEventIntegrator.java` (40-150);
- `science/fluid/thermo/FluidThermodynamics.java` (95-244, 400-436);
- `science/fluid/transport/SolidMobility.java`, `SlurryTransport.java`;
- `science/fluid/topology/TopologyCompiler.java`;
- `runtime/fluid/`: `PhysicalFluidTopology.java`, `FluidDeviceSpec.java`, `FluidView.java`, `PipePresentation.java`, `FluidWorldAuthority.java` (150-170, 270-320), `FluidCheckpointCodec.java` (grep and 210-240, 360-400, 440-456), `IslandCertificate.java` (335-356), `RetainedSolver.java` (grep);
- `world/level/block/FluidDeviceBlock.java`, `client/gui/screens/inventory/FluidDeviceScreen.java` (125-160, 289-330), `network/FluidNetwork.java` (grep), `registry/ModBlocks.java` (grep), `CreateChemE.java:96`.

Tests at 9674bf1:
- read: `PumpRiseScalingTest`, `PumpJunctionStartupTest`, `ElevatedBlockLineIslandTest` (1-176), `FluidPumpedFillLineTest` (grep, 160-185), `FluidPumpStartupGameTests`, `SolverRegressionHarness` (grep), `FluidSolverRegressionTest` (grep);
- test names only: `DeadHeadedLineIslandTest`, `FullTankSolidsEventTest`, `SolidRuntimeTest`;
- `build.gradle` gate tasks (262-386).

*Inferred, not run:* the reconstruction's gate error under frozen stream fractions (3.3); the target-above-supply infeasibility (3.6); the two-phase state of the crude start fixtures (section 6); the heating estimates (3.7); all "no new row" statements hold only while risk R3 stays small.

---

## Appendix A: every place the base reads a donor's bulk properties

Each becomes the stream of the end the flow leaves (or, for a device, its suction stream). Lines are `PassiveStepSolver.java` unless noted.

| where | what it reads today |
|---|---|
| `:148-150` initial pump mode | `velocityLimit(first node state)` |
| `:169-170` carry offer | `riseLimit(pump, a.state())`, bulk column of `a` |
| `:302-312` pass loop | `rho = up.mass()/up.volume()`, `velocityLimit(up)`, `massFlowLimit(pipe, up)` |
| `:359-362` velocity closure | `massFlowLimit(pipe, upstream state)` |
| `:373-380` accepted-mode clamp | `massFlowLimit(pipe, projection state)` |
| `:564-587` approximation probe | `riseLimit(pump, up)`, `massFlowLimit(pipe, donor)` |
| `:941`, `:947-949` start point | `Q * a.mass/a.volume`, `riseLimit(pump, a.state())` |
| `:1000-1010` `initialMassFlow` | upstream bulk density and viscosity, `massFlowLimit` |
| `:1026-1034` `headLimitMassFlow` | suction bulk density and viscosity |
| `:1051-1059` `riseLimit`, `massFlowLimit` | bulk `mass/volume`, `velocityLimit(state)` |
| `:1073-1076` `demand` | the density passed in (callers pass suction bulk) |
| `:1228-1229` `closeDeadHeads` | the two ends' bulk densities |
| `:1533-1534` `headDensities` | the two ends' bulk seed densities |
| `:1618-1627` structural zeros | the bulk-withdrawal assumption |
| `:1699`, `:1916-1917` `Transport` | node bulk |
| `:1736-1740` scheduled withdrawal | node bulk (stays bulk, non-scope) |
| `:1748-1760` edge targets | `transport.moles / upstream.mass()`, solid moments, `specificEnthalpy`, filter capture `:1752` |
| `:1764-1775` junction mixing | `carried.moles / source.mass()`, `source` solids and enthalpy |
| `:1776-1779` filter capture energy | donor solids over donor mass |
| `:1780-1784` pump work | `st[a]` bulk density |
| `:1789-1856` `edgeRows` | donor `Transport` for loss and cap; `st[donor]` for filter terms |
| `:1859` target row | `st[a]` bulk density |
| `ConservativeTransport.java:151-153`, `:188-194`, `:249-262` | donor bulk fractions (implicit), bulk enthalpy, bulk solids, suction bulk for pump work |
| `PipeTransfer.java:30-44` | donor bulk phase split |
| `SolidEventIntegrator.java:87`, `SolidMobility.java:93` | `Outlet.MIXED`, donor bulk mass |
| `FluidWorldAuthority.java:294` | suction bulk density for the displayed limit |

## Appendix B: proposed constants

| constant | value | meaning |
|---|---|---|
| `phi_open` | 0.01 | a phase port opens when its phase fills at least 1 % of the vessel volume |
| `phi_reserve` | 0.005 | the outflow throttle lands the phase at 0.5 % (the band is the gap) |
| `alpha_refuse` / `alpha_resume` (pump) | 0.02 / 0.005 | vapour share of the supply's fluid volume |
| condensed share refuse / resume (compressor) | 0.01 / 0.002 | liquid + water + solids over the supply's mass |
| compressor solid refusal | 1e-8 | solid volume fraction (`SlurryTransport.DEFAULT_TRACE_VOLUME_FRACTION`) |
| `r_max` bounds | 1.01-10 | compressor pressure ratio setting |
| compressor default | 0.05 m3/s, ratio 3, efficiency 1 | placement default |

## Appendix C: WP0 classification (c32acac)

- Written 2026-09-26 by Claude (Opus 5.5), read-only: no source or test edited, no Gradle run. Every line is a line of `c32acac` (main 0.6.0, the backward-Euler basis); `PSS` = `science/fluid/network/PassiveStepSolver.java`, `PIS` = `PassiveIntervalSolver.java`, `CT` = `ConservativeTransport.java` (same package).
- `PSS` is byte-identical between `618ea63` and `c32acac` (`git diff --quiet`), so the line numbers of `LEVEL_HEAD_REVIEW.md` still hold; the 9674bf1 lines of sections 2-4 and Appendix A moved with basis WP1 and are re-resolved in C.4.
- Suction phase decided from the fixture's fluid, T, P and source device. "Liquid" = pure water at 298.15 K, at or above 1 atm; "gas" = pure nitrogen or methane at 298-350 K and 0.1-1.5 MPa. Crude cases are decided from the assay (C.1 note a).
- Target classes: **U** unchanged; **C** becomes a compressor test; **R** becomes an `INLET_WRONG_PHASE` refusal test; **B** re-baselined (owner sees it before WP3); **n/a** no flow is solved.

### C.1 Tests that construct or place a pump

Unit tests, 19 files constructing `FlowControl.Pump(` (count confirmed at 9674bf1 and c32acac), plus `TopologyCompilerTest` (topology kind only):

| test (file:line of the method) | suction supply | phase | target | note |
|---|---|---|---|---|
| `FlowControlTest.pumpMeetsItsSuctionFlowTarget...` :22 | methane reservoir, 350 K, 101325 Pa | gas | C | target row and work on a compressor |
| `FlowControlTest.naturalFlowIsLimited...` :27 | methane, 350 K, 300 kPa (natural) and 101 kPa against 800 kPa (shutoff) | gas | C | shutoff: any `r_max` < 7.9 keeps CLOSED |
| `NetworkRegimeTest.pumpShutoffUsesAddedPressure...` :74 | N2 generator 350 K 1 MPa; recycle N2 tanks 150 kPa | gas | C | `r_max` = 1.5 keeps the 1.49/1.51 MPa bracket exactly (suction is the generator) |
| `NetworkRegimeTest.cancellationCannotLeave...` :114 (sonic part :121-125) | N2 generator 350 K 1 atm | gas | C | `PUMP_VELOCITY_LIMIT` stays the mover's mode |
| `PumpJunctionStartupTest.waterAndAmbientCrude...` :13 | generator 298.15 K 1 atm: water; Tia Juana Light preset | water liquid; crude two-phase (a) | U (water) / **B** (crude) | crude: raise the generator above its bubble point (plan section 6); flagged (a) |
| `PumpRiseScalingTest.aGasTransferBetweenClosedTanks...` :42 | N2 tanks 298.15 K 1 atm (`transfer` :22-27) | gas | C, re-stated | the 575 Pa density-scaled shutoff, "closes within a second" and "a fraction of a kelvin cooler" become ratio-limit assertions |
| `PumpRiseScalingTest.aDomainRefusalIsItsOwnRejectionKey...` :75 | same | gas | C | suction still cools past 298.05 K (sooner: the ratio limit lets it expand further) |
| `BackwardEulerStepTest.pumpWorkIncludesAllStages...` :52 | N2 tank 298.15 K 1 atm | gas | C | ledger identity holds for the O4 work formula |
| `SharedSourceDepletionQualificationTest.excessivePumpRequest...` :183 (`pumpGraph` :274-286) | N2 tank 350 K, 300 kPa / 1 atm | gas | C | P24 report keys follow |
| `TransientQualificationTest.liquidCompression...AndPump...` :66, case "pump against pressure" :72-76 | N2 tank 350 K 1 atm | gas | C | the other four cases have no pump |
| `VelocityClampTest.threePhaseClamping...` :36 (pump arm :38) | wet crude (assay + 0.2 water) 350 K 200 kPa, PORT nodes | three-phase (the test itself asserts all three phase volumes > 0) | R | pump arm asserts `INLET_WRONG_PHASE` and zero flow; Passive and valve arms unchanged |
| `PhysicalFluidTopologyTest.pumpControlUsesItsOutletDirection...` :27 | N2 tanks 298.15 K 1 atm | gas | C | orientation test on `Kind.COMPRESSOR`; WP5 may add a water-tank pump twin |
| `PhysicalFluidTopologyTest.dimensionsDeadLegsAndUnsupportedPumpPorts...` :37 | compile only | - | n/a (U) | |
| `PhysicalFluidTopologyTest.zeroStoragePumpBypass...` :46 | N2 tanks 150/149 kPa, invalid pump (target 0) | gas | U | flow 0 either way; mode (CLOSED or `INLET_WRONG_PHASE`) not asserted |
| `IslandCertificateTest.deadHeadedLinesCertifyRest...` :151, fixture "dead-headed pump" (`deadHeadedPump` :65-68) | N2 1 atm against N2 8 bar | gas | C | `r_max` 3 keeps it dead-headed; a refused pump would also certify (weaker) |
| `IslandCertificateTest.aPumpHeatingItsLoopDoesNotCertify` :288 (`pumpedLoop` :92-97) | N2 loop 1 atm | gas | C | |
| `IslandCertificateTest.anIslandThatNeverCertifies...` :793 ("pumped loop") | same | gas | C | bitwise on/off comparison unaffected by the device kind |
| `FilterBlockLineIslandTest.aFilterBlockCostsAPumpedLine...` :359 | water generator 298.15 K 1 atm | liquid | U | |
| `ElevatedBlockLineIslandTest.elevatedLinesIntegrate...` :201, rising pump line (`risingPumpLine` :101-107) | water generator 400 kPa, pump directly above it | liquid | U in WP3; **B** in the D9 level-head WP (C.2) | |
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiver...` :83 | water + demo slurry, 298.15 K 1 atm | liquid + solids | U | |
| `FluidCheckpointCodecTest.aTopologyRoundTripsEveryField...` :87 | codec only | - | n/a (U) | WP5 adds compressor, ports, modes |
| `PhysicalRegistryTest` (helper `record` :64-70 only; no method places a pump) | - | - | n/a (U) | |
| `WorldTopologyLedgerTest.pendingPhysicalEventsRoundTrip...` :40 | ledger only | - | n/a (U) | |
| `FluidFallbackQualificationTest.threeApproximateIntervals...` :38, case "wet crude pump" (`warm` :24-32) | wet crude + 0.1 N2 + 0.2 water, 350 K 149 kPa generator | three-phase (the same composition at 350 K 150 kPa is 95.7 % vapour by volume in `chain-100.json`) | **B** | a refusal makes the approximation case trivial; give the pump a liquid supply (water, or crude above its bubble point) |
| `FluidPumpedFillLineTest` :97, :112, :122, :135, :152 (layouts `GUPR...`, `line` :36-50) | water generator 298.15 K 1 atm | liquid | U (5 methods) | tanks joined side to side (BULK) |
| `FluidPumpedFillLineTest.aGasTransferCloses...` :166 ("RUPR") | N2 tank 1 atm | gas | C | as planned (section 6) |
| `FluidThermoDomainHoldTest.aReproducibleDomainFailureHolds...` :51 ("RUPR" :30-44) | N2 tanks 1 atm, domain narrowed to 298.05 K | gas | C | status text names "compressor at 1, 64, 0" |
| `TopologyCompilerTest.pumpOnlyBypassIsRejected...` :23 | topology kind only | - | n/a (U) | WP4 adds a COMPRESSOR case |

GameTests: 4 classes place `FLUID_PUMP` (34 `@GameTest` methods in `src/` at both 9674bf1 and c32acac):

| GameTest | supply | phase | target |
|---|---|---|---|
| `FluidPumpStartupGameTests.defaultWaterPumpStartsIntoNitrogenReservoir` :17 | default generator: water 298.15 K 1 atm (`FluidDeviceSpec.java:29`, `FluidWorldAuthority.java:162`) | liquid | U |
| `FluidPumpStartupGameTests.heatedCrudePumpStartsIntoNitrogenReservoir` :19 (edit :27-31) | crude preset, 350 K 101325 Pa | two-phase (a) | **B**: generator pressure above the bubble point (estimate about 0.3 MPa; use 1 MPa after one flash) |
| `FluidPumpedFillGameTests.aPlacedPumpedThreeTankChainFills` :21 | default water generator | liquid | U |
| `SolidPhaseGameTests.filterCaptureRecovery...` :28 | water + demo slurry 298.15 K 1 atm | liquid + solids | U |
| `FluidPacketGameTests.createAndVanillaMovementRejectEveryFluidBlock` :50 | no fluid | - | n/a (U); WP5 adds the compressor block to its list |

Exact-regression harness: `FluidSolverRegressionTest.replaySavedIslandsAgainstCapturedReferences` :42 runs `chain-100` (`SolverRegressionHarness.cosineChain` :128-139): 100 code-built reservoirs, passive pipes, no pump, no face, all BULK. **U, bitwise (WP1 gate).** The snapshot fixtures `quiet-11312`, `quiet-11324`, `cold-11312` need `build/probe/core.dat` (absent here, so skipped) and are format-5 saves a format-6 build refuses: n/a.

(a) *Crude at 1 atm, decided by estimate, not by a fixture assertion.* The preset `createcheme:tia_juana_light_methane` (`materials/assays/tjl20.json` :3-31) holds 0.5 mol% methane, 0.12 % ethane, 1.06 % propane, 2.88 % butanes and 4.12 % pentanes. With a Henry constant of methane in light oil of roughly 200 bar at 298 K, methane alone gives `x K` of about 1.0 at 1 atm, and with the light ends the bubble pressure is about 1.2 atm at 298.15 K (hand estimate) and about 3 bar at 350 K. So both crude starts are two-phase at 1 atm; the vapour's volume share is far above `alpha_refuse` = 2 % even at a few tenths of a mol% vaporised. The 350 K case is safe; **the 298.15 K case is marginal and is the one undecidable-by-reading case**: WP3 confirms it with one `flashTP` before re-baselining.

### C.2 Physical-topology fixtures with a vertical tank link

Under A1 a RESERVOIR end's face decides its port. Code-built graphs (every science fixture, `McpGameplayRegressionTest`, the regression chain) have no faces and stay BULK.

| fixture (builder) | used by | tank face | what flows through it | A1 effect | D9 level head (option B) |
|---|---|---|---|---|---|
| `ElevatedBlockLineIslandTest.risingLine` :75-82 | `aVerticalPipeStack...` :177 (compile only), `elevatedLines...` :201, `aLineChargedToItsOwnHydrostaticBalance...` :240, `aDeadHeadedElevatedLine...` :268 | DOWN, LIQUID port | water in from the generator (:201); zero flow on a N2-only tank (:240, :268) | U: inflow only; outflow is already refused by the generator end (`boundaryAllowed` PSS :1303-1306) | :216 assertion **B** (about -7 kPa); :240/:268 N2-only, head 0, U |
| `.risingFilterLine` :92-97 | :201, :268 | DOWN, LIQUID | water in; zero flow on N2 | U | :220 assertion **B** |
| `.risingPumpLine` :101-107 | :201 | DOWN, LIQUID | pumped water in | U | :229 assertion **B** |
| `.fallingLine` :84-90 | :201, :268 | UP, VAPOR | water in from above | U (inflow; the tank end's column only enters the reverse, forbidden, direction) | U (no head at VAPOR) |
| `DeadHeadedLineIslandTest.risingBlocks` :68-74 | `aDeadHeadedElevatedLine...` :200, `raisingTheGenerator...` :234 | DOWN, LIQUID | zero flow on N2 (:200); water fills the tank to > 700 kg at 400 kPa, then rests after lowering (:234) | U | :200 U; **:234 `expected=400000-column(400000)` at 1e-5 moves by about 7 kPa: B (not in `LEVEL_HEAD_REVIEW.md` section 5)** |
| `DeadHeadedLineIslandTest.risingFilterBlocks` :81-85 | `aDeadHeadedFilterLine...` :215 | DOWN, LIQUID | zero flow on N2 | U | U |

Vertical links that are not tank faces (A1 does not apply): `MixedGasJunctionStaticTest` :49-53 and `PipePresentationTest` :26-31 (generators and voids above and below a pipe), `MixedGasJunctionTransientTest` :72-79 (ports 5-6 are voids; the two tanks are at `{±1,0,0}`), `LiquidJunctionTransientTest` :58 (horizontal only). GameTests: none; `FluidPlacementGameTests.lineStart` :29 stacks levels two blocks apart, so no vertical adjacency; `FluidServerBenchmark` places everything at y = 80 (:329). Regression harness: none.

No fixture ever draws outflow through a vertical face, so no fixture becomes an "outlet phase absent" closure in its outcome and no flat control is needed. **Plan gap found:** the N2-only rising tanks (:240, :268, DeadHeaded :200, :215) have a LIQUID port whose phase is absent, and a liquid-full tank has an absent VAPOR stream. `headDensities` (PSS :1661-1667), `closeDeadHeads` (:1240-1241) and `illegalWithEitherDensity` (:1300) read each end's density in sign tests; plan 3.2 routes them to the end's stream, which does not exist there. WP1/WP2 must define the absent stream's column (recommended: the bulk density, which keeps these four tests bitwise) before the tests can be called unchanged.

### C.3 Driving-pressure restatements: WP1 per-end helper checklist (c32acac)

| # | site (PSS, c32acac) | expression | decides |
|---|---|---|---|
| 1 | `reopenable` :112-166, drive :162-164 (caller PIS :190-197, `be-reopen`) | `difference - rho_end g dz` against `reopenBand` :96 | the boundary-reopen retry of a closed run |
| 2 | carry offer :310-315 | `riseLimit - (P_b - P_a + rho_a g dz)` | CLOSED pump offered its head limit at a step start |
| 3 | `demand` :1080-1083 (pass loop :433) | `P_b - P_a + rho g dz` | HEAD_LIMIT/CLOSED margin |
| 4 | chain start flows `initialMassFlows` :849-905, drive :892 | `P_a - P_b - rho g dz` | start flows of passive runs |
| 5 | valve start head :960-962 | same, minus loss | regulating valve start point |
| 6 | `initialMassFlow` :1003-1016, drive :1012 (also the balanced junction seed :784, :793; `closeIllegalStarts` :1281) | same | start flow, cold-start junction seed, start closure direction |
| 7 | `headLimitMassFlow` :1033-1041, drive :1036 (the pump reopen start :941-953 calls it when `reopened`) | same + `riseLimit` | reopen start of a pump (8.8 (c)) |
| 8 | `closeDeadHeads` :1193-1245, drives :1240-1241 (called :332) | forward on `rho_a`, reverse on `rho_b` | start closure of one-way runs |
| 9 | `illegalWithEitherDensity` :1297-1302 | either end's density | start closure of illegal directions |
| 10 | `headDensities` :1606-1668, drives :1617-1618, :1661 | first/second column | which column a pass states |
| 11 | `edgeRows` :1886-1977, row :1909 (capped branch reuses it :1966-1967) | `P_a - P_b - column g dz + signedHead` | the hydraulic row |

New since 618ea63: none; `PSS` did not change and basis WP2-WP5 added no static head elsewhere (`GRAVITY` in `PIS`, `SolidEventIntegrator`, `IslandCertificate`: none; `CT` :236-287 and `CausalModuleCoordinator` :145 book potential energy, not a driving pressure). Two neighbours read densities only and follow the helper without a change: the approximation probe :610-634 (residual plus `riseLimit(pump, up)` :624) and the pump start at :938 and :949-952. **Availability caveat for WP2:** `boundaryAllowed` :1303 is static and state-free, while site 1 evaluates on the step's end states; the availability mask must be passed in and must be the start state's in both. *Resolved in WP2: `PassiveStepSolver.closedPorts(graph)` on the step's start graph, read by every `boundaryAllowed` caller including `reopenable`; the mask enters `WorkspaceKey`.* *(Amended 2026-09-26 after WP2, `PHASE_PORTS_REVIEW.md` WP2.)*

### C.4 Basis facts WP1-WP4 rely on (c32acac)

**Identity, keys, certificate.** `PassiveNetwork.Pipe` record :112 (constructors :113-114, :137-138, validation :139); `Pipe.Identity` :126-127 built by `identity()` :130-133 (id, ends, sections, control, blocked mask, filter settings); `NodeKind` :11 (RESERVOIR, JUNCTION, GENERATOR, VOID, PORT). `WorkspaceKey` PSS :514, built :366-368 from pipe identities, modes, `boundaryClosed` and `junctionDonorFirst`. Certificate `Signature` `IslandCertificate.java` :311-327 (`of` :318-320) over `graphIdentity` :336-360 (pipe control switch :350-354, exhaustive over the sealed `FlowControl`, so a `Compressor` will not compile until it gets a case). Ports enter both through `Identity` and one `d.integer` per end in `graphIdentity`. Naming hazard: plan 3.1's `PassiveNetwork.Port` would sit beside `NodeKind.PORT` (the rate-solve vessel copy); a distinct name (e.g. `Outlet`) is advised.

**Checkpoint codec** (`FluidCheckpointCodec.java`): `VERSION=5` :52 (refusal message :612), so plan 4.4's 5 to 6 holds. Endpoint modes are one `u8` ordinal each: `MODES` :68, write `modes` :400 (interval :344), read :541-545 with an ordinal range check (interval :450, length check :453). So the new modes append to `FlowControl.Mode` and persist with no codec change. Control tags 0/1/2: registration write :219-224 (pump :222), read :235; topology write :370-382 (pump :379), read :490-506 (control :503; `PORT` refused :496); JSON `decodeGraph` :831-849 (kinds :846). The two port bytes go after the control in `topology` (:376-381 / :503).

**Modes and pump sites.** `FlowControl` sealed :4, `Pump` :7-12, `Mode` :17-18 = PASSIVE, PUMP_TARGET, PUMP_HEAD_LIMIT, VALVE_REGULATING, VALVE_OPEN, CLOSED, VELOCITY_LIMITED, PUMP_VELOCITY_LIMIT, VALVE_VELOCITY_LIMIT (9). `instanceof FlowControl.Pump` in main: **17** = PSS 11 (:304, :432, :619, :657, :949, :1009, :1618, :1858, :1900, :1908, :2168), `CT` :264, `PhysicalFluidTopology` :24, :155, `FluidWorldAuthority` :294, `FluidNetwork` :50, :51. Also `case FlowControl.Pump` 5 (PSS :292, :495; codec :222, :379; `IslandCertificate` :353), casts 3 (PSS :939, :1970, :1974), typed signatures 3 (PSS :1033, :1058, :1061) and constructions 6 (`FluidNetwork` :213, `FluidWorldAuthority` :160, `PhysicalFluidTopology` :155, codec :235, :503, :846). The committed-endpoint-mode carry that A5's hysteresis needs exists: `PIS.replayStart` :311-316 to PSS `replayStart` :221-239 restates `previousModes`, read by the carry at :301-315 (today only HEAD_LIMIT and CLOSED are carried).

**Appendix A readers re-resolved** (PSS unless noted):

| Appendix A (9674bf1) | c32acac | change |
|---|---|---|
| :148-150 initial mode | :290-296 (cap :294) | new rule :293: any blocked pipe on the island starts every pump on HEAD_LIMIT |
| :169-170 carry offer | :310-315 | |
| :302-312 pass loop | :425-449 (rho :428, cap :436, margin :433, work :441) | |
| :359-362 velocity closure | :475-478 | |
| :373-380 accepted-mode clamp | :489-497 | |
| :564-587 approximation probe | :610-634 | |
| :941, :947-949 start point | :938 (target), :946-953 (reopen start) | reopen start is basis WP1 (`pumpReopenStart` landed) |
| :1000-1010 `initialMassFlow` | :1003-1016; chain :876-905 | |
| :1026-1034 `headLimitMassFlow` | :1033-1041 | |
| :1051-1059 `riseLimit`, `massFlowLimit` | :1058-1066 | |
| :1073-1076 `demand` | :1080-1083 | |
| :1228-1229 `closeDeadHeads` | :1240-1241 | |
| :1533-1534 `headDensities` | :1606-1610, :1661-1667 | |
| :1618-1627 structural zeros | :1693-1704 | |
| :1699, :1916-1917 `Transport` | :1778, record :1783, block sweep :2027-2028 | |
| :1736-1740 scheduled withdrawal | :1815-1819 | stays bulk |
| :1748-1760 edge targets | :1827-1839 | |
| :1764-1775 junction mixing | :1840-1853 | reads the **live** upwind donor (`mixed=donor` :1842), not a frozen one; `junctionDonorFirst` only shapes `carries` :2164-2168 and the workspace key |
| :1776-1779 filter capture energy | :1854-1857 | |
| :1780-1784 pump work | :1858-1862 | |
| :1789-1856 `edgeRows` | :1886-1977 (pump column :1908, target row :1970, head-limit row :1974) | `pumpColumn=suction` landed (:1908) |
| `CT` :151-153, :188-194, :249-262 | `CT` :161, :182-188 (fixed donor :187), :254-264 (energy :257, pump work :264); `storedFractions` :122-128 | new: `pinnedFlows` :327-350 books scaled flows (filter share on bulk donor :340); junctions take the vessel row :169-177; frozen rate booking :148-153 |
| `PipeTransfer.java` :30-44 | `sample` :34-47 | |
| `SolidEventIntegrator.java` :87, `SolidMobility.java` :93 | :87 (rate copy `rate` :68-72), :93 | |
| `FluidWorldAuthority.java` :294 | :294 | |
| (new) | `PIS.portRate` :275-282, `coldRateSeed` :289-297 | second PORT-copy site (reuses `graph.pipes()`, so ports carry over; covered by plan 3.1's RESERVOIR-or-PORT rule) |
| (new) | balanced junction seed :775-800 (enthalpy :799), startPoint junction propagation :983-995 (filter share :988, :993) | bulk donor reads; route through the stream |

### C.5 Summary

| class | unit-test methods | GameTests | harness |
|---|---|---|---|
| U, unchanged | 14 + the water half of `PumpJunctionStartupTest` (incl. 5 n/a: no flow solved) | 4 (1 n/a) | 1 (`chain-100`) |
| C, becomes a compressor test | 15 | 0 | 0 |
| R, `INLET_WRONG_PHASE` refusal | 1 (`VelocityClampTest` pump arm) | 0 | 0 |
| B, re-baselined in WP3 | 2 (`PumpJunctionStartupTest` crude half; `FluidFallbackQualificationTest` "wet crude pump") | 1 (`heatedCrudePump...`) | 0 |
| vertical tank links (C.2) | 6 builders, 7 methods: all U under A1 | none | none |
| B in the D9 level-head WP | 4 assertions: `ElevatedBlockLineIslandTest` :216, :220, :229; `DeadHeadedLineIslandTest` :234 | 0 | 0 |

**Re-baselines the owner must see before WP3 (and D9) edits them:**
1. `PumpJunctionStartupTest` :13 crude case: generator 298.15 K 1 atm to a pressure above the crude's bubble point (after one confirming flash).
2. GameTest `heatedCrudePumpStartsIntoNitrogenReservoir` :19: generator 350 K to about 1 MPa (bubble point about 0.3 MPa, estimate).
3. `FluidFallbackQualificationTest` :38 "wet crude pump": the pump gets a liquid supply (or the case is dropped); a refusal would leave nothing to approximate.
4. The 15 C tests change their device, and five of them change assertions, not just the constructor: `PumpRiseScalingTest` :42 (575 Pa shutoff, one-second closure, sub-kelvin cooling), `FluidPumpedFillLineTest` :166 ("scaled limit"), `NetworkRegimeTest` :74 (bracket via `r_max` = 1.5), `FluidThermoDomainHoldTest` :51 (status names a compressor), `IslandCertificateTest` :151 ("dead-headed pump" becomes a dead-headed compressor).
5. D9 package: the three `ElevatedBlockLineIslandTest` rising assertions (already in `LEVEL_HEAD_REVIEW.md`) and **`DeadHeadedLineIslandTest` :234, missed by that review**.

**Undecided by reading:** only the 298.15 K crude supply (note a), two-phase by estimate.

**Where c32acac contradicts the plan:**
- Section 6 "85 annotated tests at 9674bf1": `src/` has 34 `@GameTest` methods at 9674bf1 and at c32acac.
- Section 6 "`PumpRiseScalingTest`: three tests pump nitrogen": two do (:42, :75); :22-27 is the fixture helper.
- Section 6 "`ElevatedBlockLineIslandTest` ... reverse flow out of those tanks changes; give each fixture a flat control": no fixture carries flow out of a vertical face, so nothing changes under A1; the only effect is D9's head.
- 3.2 "junction mixing rows read the stream of the edge's frozen donor end": they read the live upwind donor (PSS :1840-1846).
- 3.3 item 2 "for a junction receiver, `(|q|/incoming) w_stream`": since basis WP1 a junction owns holdup and takes the vessel row (`CT` :169-177), so a phase-port inflow to a junction goes to the right-hand side as for any receiver; and the reconstruction books `pinnedFlows` (`CT` :136, :327-350), not the solved flows, so the frozen stream booking must use the pinned flow.
- 8 R7 "`boundaryReopen`, `pumpColumn=suction` and `replayDeterministic` are prototype switches, not code on `main`": all three are unconditional code on main (`reopenable` PSS :112 with PIS :190-197; PSS :1908; `replayStart` PIS :311, PSS :221). R7 is closed.
- Section 2 "the pump block's own zero-holdup junction": it now owns holdup `m_J` (`PassiveNetwork.sizeJunctionHoldups` :64-76).
- 3.6 "target above supply" start: a partial rule already exists (PSS :294 starts HEAD_LIMIT when the target exceeds the pump edge's own cap, and :293 whenever any pipe of the island is blocked); WP3 extends it to the suction walk.
- 3.2 has no density for an absent stream (C.2 gap), and `boundaryAllowed` has no state argument for the availability mask (C.3 caveat).

# Phase ports and compressor: review

- Batch: `2026-09-26-phase-ports-and-compressor`. Plan: `PHASE_PORTS_PLAN.md`; decisions: `DECISION_LOG.md` (this folder).
- One section per work package. Numbers are measured unless labelled *inferred*.

## WP1: ports, per-end streams, driving-pressure helper (2026-09-26)

- Author: Claude (Opus 5.5), worktree `/home/user/CreateChemE` (cloud container), branch `claude/phase-ports-compressor`.
- Base: `b83537a` = `6e1c5b6` (main 0.6.0 `c32acac` plus the tracked documentation/tools tree) plus the cloud harness commit; `git diff --stat c32acac 6e1c5b6 -- src/` and `git diff --stat 6e1c5b6 b83537a -- src/` are both empty, so the code edited is c32acac's and every line number of plan Appendix C.4 held when the work started.
- Commit: `35e354d` (`WIP phase-ports WP1: ports, per-end streams, driving-pressure helper`), on `b83537a`; not merged, not pushed.
- Scope: plan 3.1-3.3 and 3.8, science only, plus the amendment of the per-end driving-pressure helper. No runtime, GUI, codec or checkpoint change (the one runtime file touched is `IslandCertificate.graphIdentity`, as the handoff asks). WP2 (availability closure, throttle), the level head (D9), the pump inlet rules (WP3), the compressor (WP4) and the face-to-port compile (WP5) are not in it.

### 1. What changed, per file

| file | change |
|---|---|
| `science/fluid/network/PassiveNetwork.java` | `enum PhasePort { BULK, VAPOR, LIQUID }`; `Pipe` gains `firstPort`, `secondPort` (canonical 9-argument constructor); the old 7-argument constructor becomes a delegating one passing `BULK, BULK`, and every other constructor still reaches it, so no call site changed meaning; `Pipe.Identity` gains both ports; `withBlockedDirections`/`withFilter` keep them; new `withPorts`, `portAt(node)`, `drawPort(flow)`, `bulk()`. The graph constructor refuses a non-BULK port on an end whose node is not RESERVOIR or PORT. |
| `runtime/fluid/IslandCertificate.java` | `graphIdentity` digests one integer per end (the port ordinal) after the blocked mask; header text `...-graph-identity-2` (A12). |
| `science/fluid/thermo/FluidThermodynamics.java` | `PhaseStream` record and its builders `vaporStream`/`liquidStream` (null for an absent phase), plus the parts they are made of, each usable alone: `holdsVapor`/`holdsLiquid`, `vaporMass`/`liquidMass`, `liquidStreamVolume`, `vaporDensity`/`liquidDensity`, `vaporSpecificEnthalpy`/`liquidSpecificEnthalpy`, `vaporVelocityLimit`/`liquidVelocityLimit`, `vaporViscosity`/`liquidViscosity`, `liquidCarrierViscosity`. All read the state's own phase amounts, volumes and molar properties (plan table 3.2): no flash, no extra EOS root. The two streams partition the state (moles, mass, volume, enthalpy add up to the state's). |
| `science/fluid/network/PassiveStepSolver.java` | Per-end helpers `endPressure`/`portHead` (the driving-pressure helper, zero offset), `endDensity`, `endVelocityLimit`, `endViscosity`, `solidShare`, `endMoles`/`endMass`/`endSpecificEnthalpy`, `suctionMassFlow`, `massFlowLimit(pipe, donor, port)`, `carrierViscosity(state, port)`. The Newton's per-node `Transport` gains `mass`, `volume`, `solids` and its phase streams (`vapor`, `liquid`, built only for a node an open end draws; `port(p)` selects). Every Appendix A reader (C.4 re-resolution) reads the stream of the end the flow leaves, or the suction end's stream for a pump. Structural-zero drop skipped for a node with an open phase port. `reopenable`, `closeDeadHeads`, `illegalWithEitherDensity` became instance methods (they need the model for a stream density). The unused `riseLimit(pump, State)` and `massFlowLimit(pipe, State)` were removed. |
| `science/fluid/network/ConservativeTransport.java` | Phase-port outflow booked as a fixed donor with frozen stream fractions (section 4); `pinnedFlows` and the delivered rate read the drawn stream's solid share; energy, solids, filter capture, pump work, boundaries and the frozen-rate tallies read the drawn stream. |
| `science/fluid/network/PipeTransfer.java` | `sample(model, graph, states, rates, duration)` samples the drawn stream's phase split; the old 4-argument form (no model) stays for all-BULK graphs. |
| `science/fluid/network/SolidEventIntegrator.java` | Outlet mapping at the mobility check: a VAPOR port (phase present) asks `Outlet.GAS`, everything else `MIXED`; the liquid velocity uses the drawn stream's mass. |
| `science/fluid/transport/SolidMobility.java` | `check(prepared, donor, pipe, flow, outlet, drawnMass)`; the old form passes `donor.mass()`. |
| `science/fluid/network/PassiveIntervalSolver.java` | calls `implicit.reopenable(...)` (instance). |
| `src/test/.../science/fluid/network/PhasePortTest.java` | new, 7 tests (fixture 1 and four supporting checks, section 7). |

Construction sites read through, including the ones this container compiles only with Gradle: `src/main` (`FluidNetwork` 10, `PhysicalFluidTopology` 6, `FluidCheckpointCodec` 2, `FluidWorldAuthority` 2, `FluidDeviceBlockEntity` 2, the science package), `src/test` (every `new PassiveNetwork.Pipe(` / `new Pipe(` / `withBlockedDirections` / `withFilter` / `.identity()` site, 45 files; `FluidCheckpointFormatTest.withLongerSections` rebuilds pipes with the 7-argument form, BULK), `src/fluidGameTest` (`FluidHarnessGameTests` :174, 4-argument form; `FluidPresentationGameTests` :197 is a menu identity, not a pipe), `src/mcpCompat` (no site). All use unchanged constructor arities, so all still compile (gate 4 below confirms it with Gradle); runtime graphs stay all BULK until WP5 compiles faces to ports. `FluidWorldAuthority.java:294` (displayed pump limit on the suction's bulk density) is a runtime reader left for WP5 by the scope rule.

### 2. The per-end driving-pressure helper

`endPressure(node, port, state) = state.pressure() + portHead(node, port, state)`, `portHead` returning 0 in WP1: the level head (D9, option B) is the body of `portHead`. `P + 0.0` is `P` for every positive P, and every site keeps its operation order, so all-BULK doubles are unchanged (measured, section 6). Routed through it (PassiveStepSolver after WP1; C.3 numbering):

| # (C.3) | site | through the helper |
|---|---|---|
| 1 | `reopenable` :112 | both run ends' pressures, the band `reopenBand(pa, pb)` and each direction's column (end stream density) |
| 2 | carry offer :317 | `endPressure` of both ends, suction stream density for the column and the rise limit |
| 3 | `demand` :1187 (pass loop :437) | both ends |
| 4 | `initialMassFlows` chain start :855 | both run ends; the upstream choice, column, viscosity and cap read the end streams |
| 5 | valve start head :969-971 | both ends; suction stream density and viscosity |
| 6 | `initialMassFlow` :1011-1026 (and the balanced junction seed :745-822, `closeIllegalStarts` via the estimate) | callers pass end pressures; column, viscosity and cap read the end streams |
| 7 | `headLimitMassFlow` :1042 (the pump reopen start calls it) | both ends; suction stream density, viscosity, cap |
| 8 | `closeDeadHeads` :1301 | both run ends, each direction on its own end stream's density |
| 9 | `illegalWithEitherDensity` :1407 | both ends, each end on its own port's density |
| 10 | `headDensities` :1752 | both ends' seed pressures; each end's column is its port's density |
| 11 | `edgeRows` :2091 | the hydraulic row (the capped branch reuses it) |

The pump reopen start (:957-962) and the target start read the suction stream's density through `riseLimit(pump, endDensity(...))` and `suctionMassFlow` (which keeps the bulk operation order `Q*mass/volume`).

### 3. The absent stream (decision A9, extending A8)

A phase port whose phase the vessel does not hold (VAPOR: no gas volume; LIQUID: no hydrocarbon liquid and no free water; solids alone do not flow as a liquid) reads as **the node's bulk stream with a zero velocity limit**, for every reader:

| reader | absent stream |
|---|---|
| columns and sign tests (`headDensities`, `closeDeadHeads`, `illegalWithEitherDensity`, `reopenable`, start flows, carry offer) | bulk density (A8) |
| velocity cap (`edgeRows` cap, `massFlowLimit`, the velocity closure, the accepted-mode clamp, the approximation probe, the initial pump mode) | zero: a capped connection carries nothing out through the port |
| loss, filter terms | bulk viscosity, bulk carrier viscosity (finite; the loss at zero flow is zero anyway) |
| Newton targets, junction mixing, energy | bulk composition, mass and enthalpy: an uncapped iterate that moves mass through the port stays conservative |
| reconstruction | booked as a bulk outflow (implicit, the donor's end composition), consistent with the Newton |
| `PipeTransfer` | bulk phase split |
| solid mobility | a bulk end (`Outlet.MIXED`, the donor's mass) |

WP2's availability closure (`boundaryAllowed` with a start-state mask, the throttle) then keeps such a port shut for outflow; A9 only makes the stream well defined until it does. The Newton decides "absent" on its iterate's state and the reconstruction on the converged candidate, which is the state the Newton converged on, so the two agree.

### 4. The reconstruction's booking

`ConservativeTransport.reconstruct` (plan 3.3 item 2, amended by WP0):

- An edge *draws a phase* when its donor end has a VAPOR or LIQUID port and the donor's candidate state holds that phase. Its stream fractions `w_stream` (fluid components, then solid populations) are frozen from the converged candidate: the vapour's (no solid) or the condensed phases' with every solid, each over the stream's own mass; the solid split across populations is the stored inventory's scaled to the candidate's aggregate solid mass, exactly as `storedFractions` splits a fixed node's.
- It is booked on the **pinned** flow `q` (the junction mass pin applies unchanged; its filter share reads the drawn stream's solid share): the edge does not enter its donor's diagonal `outgoing`; the donor's right-hand side gets `-dt|q| w_stream` (solids included, whether or not a filter then captures them) and the receiver's `+dt|q| w_stream` (solid entries only without a filter). A junction receiver takes the vessel row like any receiver. A fixed donor (a PORT copy) uses the same `w_stream` in place of its stored fractions. The matrix is still one matrix for every component, so **one factorization** serves all components as before (two, fluid and solid, only when the island has populations and a filter, also as before).
- The pipe loop books the same `w_stream` for moles (frozen-rate tallies, fixed-node boundaries), builds the moved solids from it, books energy at the drawn stream's specific enthalpy **on the reconstructed donor** (`vaporSpecificEnthalpy`/`liquidSpecificEnthalpy`), the filter capture energy at the stream's solid enthalpy over the stream's mass, and pump work on the suction stream's specific volume. Whatever number is booked leaves the donor and reaches the receiver, so the ledger closes exactly by construction; `checkConservation` is unchanged.
- The gate error the plan inferred is measured in section 7: at step level the reconstruction's boundary booking and the stream sampled on the reconstructed state agree to 5.0e-16 (water/nitrogen) and 8.2e-15 (pentane/methane) relative, and no step of fixture 1 failed the equation gate.

### 5. Structural zeros and closed connections

- `buildSparsity` keeps every entry of a node's non-amount columns (phase allocation, T, P) when an open end draws a phase from it (plan 3.3 item 1): what a phase port carries is the phase split itself. All-BULK nodes keep the drop, bitwise.
- A passive connection the boundary rule has closed (`boundaryClosed`, row `f = q`) carries exactly zero at every iterate, so it neither builds its node's phase stream nor lifts the drop (A10). The measured consequence: closed phase ports are bitwise inert (section 7, `closedPhasePortsLeaveTheBulkLineBitwise`).

### 6. Gates

JDK in this container: OpenJDK 21.0.10 (the owner's runs used JDK 21.0.11 on Windows; one runtime-suite ledger roundoff value differs between the two JDKs at the base, as the harness agent found, so every "identical to base" comparison below is against a base captured on this machine).

| # | command (from `/home/user/CreateChemE` unless noted) | result | base on this machine |
|---|---|---|---|
| G0a | javac harness (scratchpad `science-build.sh test`, science package; log `07-javac-science-wp1.log`) | 181 found, 180 pass (the base's 173 plus the 7 new), 1 fails as at base (`PackagedSparseLuTest`: needs the built mod jar) | 174 found, 173 pass (`baseline-fluid-science.log`) |
| G0b | scratch bitwise probe (`BitwiseProbe`: chain-100 replay plus 14 all-BULK scenarios, every double in hex: mixed-gas junctions at 5 s and 0.1 s, rising/falling water lines, a pumped line, a wet-crude drain, a slurry filter, a valve) | `chain-100.json` and `scenarios.txt` byte-identical before/after; `chain-100.json` byte-identical to `src/test/resources/fluid/regression/chain-100.json` | captured on HEAD before editing |
| G0c | compile of the 32 Minecraft-free runtime sources (`rt-sources.txt`), also with `-XDshould-stop.ifError=FLOW` | the same 21 error lines as HEAD, all in `CausalModuleCoordinator`, `IslandCoordinator`, `SolidCompatibility` (Minecraft); an error planted in `IslandCertificate` is reported in both modes, so that file is type-checked | 21 lines |
| G0d | `REPO=/home/user/CreateChemE bash tools/cloud-science-harness/harness.sh all` (log `01-harness-all-wp1.log`, 67 s) | science 188/188, runtime 227/227, the 33 junction lines identical to its 6e1c5b6 reference, adjacent 38/38, regression chain-100 0.000e+00 | its reference is 6e1c5b6 |
| G1 | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (log `03-gradle-fluid-suite-wp1.log`, XML `03-xml-wp1`) | **413/413** in 95 classes, 0 skipped (the base's 406 test names plus the 7 of `PhasePortTest`); the 33 MIXED_GAS_STATIC/TRANSIENT/COST and LIQUID_JUNCTION lines (read from the XML system-out) equal to the base's character for character apart from wall ms, bytes and allocatedMB: every ledger value and Newton solve count bitwise, MIXED_GAS_COST 286 Newton solves (542 ms, 135.2 MB; base 527 ms, 134.6 MB, single runs, no cost claim); 54 s | `02-gradle-fluid-suite-base-6e1c5b6.log` (run in the clean worktree `scratchpad/wt-harness`, src = 6e1c5b6): 406/406 in 94 classes, 33 MIXED_GAS/LIQUID_JUNCTION lines, MIXED_GAS_COST 286 Newton solves, 1 min 31 s |
| G2 | `./gradlew --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` (log `04-gradle-regression-exact-wp1.log`) | **0.000e+00** on state/moles, temperature, phase fraction and flow; 3 accepted / 0 rejected, 4 Newton solves, 29 iterations; the three snapshot fixtures skip (no `build/probe`) | review 9.5 run 157: 0.000e+00, 3/0, 4 solves, 29 iterations |
| G3 | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests *PassiveStepSolverTest --tests *PumpJunctionStartupTest --tests *FilterBlockLineIslandTest --tests *DeadHeadedLineIslandTest --tests *NetworkRegimeTest --tests *PhysicalFluidTopologyTest --tests *PipePresentationTest --console=plain --continue` (log `05-gradle-adjacent-wp1.log`) | **38/38** in 7 classes | review 9.5 run 158: 38/38 |
| G4 | `./gradlew --no-configuration-cache compileFluidGameTestJava compileMcpCompatJava --console=plain` (log `06-gradle-compile-gametest-mcpcompat-wp1.log`) | **BUILD SUCCESSFUL**, both tasks executed; `compileJava` and `compileTestJava` of the whole tree (Minecraft sources included) had already run in G1. `./gradlew tasks --all` fails on an unrelated `tools/development.gradle` property (`columnEvaluationOutput`), so the standard source-set task names were used | |

Gradle ran one invocation at a time with `JAVA_TOOL_OPTIONS` as the environment sets it (proxy), no dev client. Logs: the session scratchpad (`/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad/`): `01-harness-all-wp1.log`, `02-gradle-fluid-suite-base-6e1c5b6.log` (+ `02-xml-base/`, `02-junction-lines-base.txt`), `03-gradle-fluid-suite-wp1.log` (+ `03-xml-wp1/`, `03-junction-lines-wp1.txt`), `04-gradle-regression-exact-wp1.log`, `05-gradle-adjacent-wp1.log` (+ `05-xml-wp1/`), `06-gradle-compile-gametest-mcpcompat-wp1.log`; the base worktree `scratchpad/wt-harness` was only built and tested, not edited. Every WP1 gate passes; no existing test changed an assertion.

### 7. Fixture 1 and the supporting tests (`PhasePortTest`, measured)

Vessels: 1 m3 at 298.15 K, (a) half water, half nitrogen at 200 kPa, (b) 0.4 m3 n-pentane under 0.6 m3 methane at 300 kPa, both two-phase after the flash; three lines (10 m, 10 mm) to nitrogen voids at 1 atm: VAPOR port, LIQUID port, BULK end.

| test | measured |
|---|---|
| `aStepCarriesExactlyEachPortsPhase` (one 0.5 s step) | flows (VAPOR, LIQUID, BULK) (a) 0.009303, 0.1903, 0.1361 kg/s; (b) 0.01723, 0.2235, 0.1739 kg/s. Vapour line: hydrocarbon liquid and free water exactly 0; liquid line: vapour exactly 0; each line's composition proportional to its phase's in the end state to 1.7e-16 (a), 0 (b); mass = moved mass; the bulk line bitwise the bulk sampling of the same donor state; reconstruction booking vs sampled stream 5.0e-16 (a), 8.2e-15 (b); vessel ledger closes to 1e-12 |
| `phasePortLinesIntegrate...AtTenthSecondSlices` (100 x 0.1 s) | (a) 100 accepted, 0 rejected, component ledger 1.6e-15, energy ledger 7.0e-16; (b) 100 / 0, 1.8e-15, 8.8e-16; line composition vs vessel phase 2.2e-16 |
| `phasePortLinesIntegrate...AtFiveSecondSlices` (2 x 5 s) | (a) 6 accepted, 0 rejected, 3.9e-16, 8.9e-17; (b) 6 / 0, 4.7e-16, 1.7e-16. A slice's line carries the average of its steps' end compositions: gap to the slice's end composition 2.3e-4 (a) and 1.3e-3 (b), never more than the vessel's own composition drift over the slice (asserted to 1e-12) |
| `closedPhasePortsLeaveTheBulkLineBitwise` | with the VAPOR and LIQUID lines closed both ways, every double (states, inventories, flows, boundaries, transfers; a zero of either sign as +0) equals the all-BULK graph's: both vessels, 5 x 1 s and 10 x 0.1 s steps; the water vessel also 2 x 5 s and 20 x 0.1 s interval-solver slices |
| `phasePortsFeedJunctionsAndCloseTheLedger` (water vessel, each port through an owned-holdup junction to a void; 20 x 0.1 s, 2 x 5 s) | ledger 3.2e-16 / 4.1e-16 (0.1 s), 1.7e-16 / 1.5e-16 (5 s); the gas junction's outlet 98.4 % nitrogen, the liquid junction's 100 % water |
| `solidsLeaveThroughTheLiquidPortOnly` (water + 100 kg demo particle, LIQUID line through a filter, VAPOR line plain; 4 x 1 s) | vapour line solids empty every slice; filter captured 9.716 kg, vessel 90.284 kg, solid ledger closes to 1e-10; component 1e-12, energy (vessel plus cake) 1e-10 |
| `onlyAVesselEndMayCarryAPhasePort...` | non-BULK refused on JUNCTION, GENERATOR, VOID; accepted on RESERVOIR, PORT; legacy constructors BULK; a port changes the identity |

Why the pentane vessel's closed-line comparison runs on the step solver: an island holding a hydrocarbon liquid is integrated by `SolidEventIntegrator` (`SolidMobility.monitored`), whose `masks(graph, blocked)` rebuilds every pipe's `blockedDirections` from its own closure set at each interval start, so a caller's both-way block is reopened. That is existing behaviour, not a WP1 change.

### 8. Open defect found (base, not WP1): unsaturated water vapour into a junction seeded dry

*Fixed 2026-09-26 by decision D10 (owner: option 1, a water trace in the junction seed), a separate commit after WP2; see the section "D10" below.*

- *Measured.* A 1 m3 nitrogen vessel with 0.5 % or 1 % water (no liquid water: unsaturated) drawn through a **BULK** end into a junction seeded as dry nitrogen, then to a void, fails every interval at 0.1 s and 5 s: "Substep refinement exhausted: Conservative reconstruction fails equation gate: 1.3058e-8" (0.5 %) and 2.6151e-8 (1 %), 20 rejections each. Identical on HEAD 6e1c5b6 and on WP1 (scratch driver `hprobe/HumidJunctionProbe.java`, both builds).
- *Mechanism (instrumented scratch copy of the gate).* A junction's `PhaseLayout` carries water only if its seed holds water (`PhaseLayout` :69). The Newton ignores the arriving water, the reconstruction books it into the junction, and the gate sees the junction's amount-normalisation row off by the booked water fraction (row local 2 of a 3-row junction block, residual proportional to dt: -4.149e-8 at dt 1.9e-7 s). `phaseCorrection` does not reseed the junction, because unsaturated water vapour does not change the junction's phase code. A saturated or bulk-liquid feed does change it, which is why ordinary wet lines work.
- *Why it matters now.* A VAPOR port on a wet tank always delivers water vapour, which is unsaturated at a lower-pressure junction, so any compiled junction seeded dry downstream of a top outlet hits it (WP5 compiles faces to ports; junction seeds come from the island's first boundary state). Fixture 1's junction test seeds the gas junction with the humid gas to exercise the port booking instead.
- *Options for the owner (not implemented: each rests on a choice no decision fixes):*
  1. Seed a water trace (like `initialPhaseSeeds`' 1e-12 entry trace for vessels) into every junction whose reachable set holds water and whose seed does not. Changes the Newton unknowns of such junctions in all-BULK islands, so it is not bitwise on existing fixtures; cost small.
  2. Let the converged-point `phaseCorrection` reseed a junction whose reconstructed state holds a component outside its layout basis (water or a masked hydrocarbon). Bitwise wherever the gate passes today, except where a passing reconstruction already carries such a trace below the gate; an extra pass only where it fires.
  3. Accept it for WP1 and fix it with WP5, when faces compile to ports and junction seeds can take the delivered stream.
  Recommended: 2 (smallest blast radius, fixes the BULK case too); to be measured on the fluid suites first.

### 9. What was not run here, and why

- Fluid GameTests (`fluidGameTestServer`) and the in-game (MCP bridge) scenarios: not in WP1's gates (plan section 7: GameTest source set compiles, G4); WP1 changes no runtime path and runtime graphs carry no port until WP5.
- The owner's Windows JDK 21.0.11 lane: not available; every comparison is against a base captured on this machine (section 6).
- No timing claim is made (no benchmark rule run).

### 10. Plan text amended

Where WP0 (Appendix C, "Where c32acac contradicts the plan") already said the plan was wrong, the plan text now says so in place, each amendment tagged "Amended 2026-09-26 after WP0": section 2 (the pump block's junction owns holdup), 3.1 (`PhasePort`, not `Port`; the stream acts on outflow, the driving pressure both ways), 3.2 (junction mixing reads the live upwind donor; the absent-stream density is A8), 3.3 item 2 (a junction receiver takes the vessel row; booking on the pinned flow), 3.4 (`boundaryAllowed` needs a start-state availability mask in WP2), 3.6 (the partial target-above-supply rule at PSS :293-294), section 6 (two, not three, nitrogen `PumpRiseScalingTest` tests; no flat control needed under A1; 34 GameTests, not 85), 8 R7 (closed).

### 11. Scratch material

Measurement drivers written for this package live in the session scratchpad, not in the repository: `probe/BitwiseProbe.java` (G0b), `jprobe/JunctionProbe.java` and `instr/src/PassiveStepSolver.java` (the gate instrumentation, section 8), `hprobe/HumidJunctionProbe.java` (the base defect). They are candidates for a `tools/` folder at WP6's cleanup (`tools/` is owned by another agent in this session, so none was added here).

## WP2: the "outlet phase absent" state (2026-09-26)

- Author: Claude (Opus 5.5), worktree `/home/user/CreateChemE` (cloud container), branch `claude/phase-ports-compressor`.
- Base: `3dbd9b8` (WP1 `35e354d`, its review `3097e60`, the WP1 tools commit). Commit: `d836cf2` (`WIP phase-ports WP2: absent-phase closure, throttle, reopen allowance`), on `3dbd9b8`; not merged, not pushed. Tools and this line: the following commit.
- Scope: plan 3.4 exactly: availability on the step's start state through `boundaryAllowed` with a start-state mask, the outflow throttle in `edgeRows`, the mask in the workspace and cycle key, the port refusal as a per-link allowance of the boundary-reopen retry, and the phase-vanishing case verified. Not in it: the level head (D9), the pump (WP3), the compressor (WP4), runtime (WP5). The base defect of WP1 section 8 is untouched (owner decision; since taken as D10, a separate commit after this one).

### 1. What changed, per file

| file | change |
|---|---|
| `science/fluid/network/PassiveStepSolver.java` | Constants `PHASE_PORT_OPEN = 0.01`, `PHASE_PORT_RESERVE = 0.005` (A4); `portPhaseShare(state, port)`, `portAvailable(state, port)`; `closedPorts(graph)`, the start-state availability mask (per node, bit 1 a VAPOR port some connection has there is closed, bit 2 a LIQUID one); `boundaryAllowed(graph, pipe, flow, closedPorts)` (the static rule, then: flow leaving an end whose phase port is closed in the mask is refused). Every reader passes the solve's mask: `reopenable` (built from the start graph it is handed), the pass loop's forbidden-direction closure, `closeDeadHeads`, `closeIllegalStarts`, `reachableComponents`, `startPoint`'s junction propagation, the approximation probe, and also `initialPhaseSeeds` and the balanced junction seed (`seedBoundaryJunctions`/`balancedBoundarySeed`), the two other `boundaryAllowed` readers. `WorkspaceKey` gains the mask (equals and hash). `Equations` gains `closedPorts` and `throttles` (built once per pass from the start states, `null` for a rate solve, while a device holds `PUMP_TARGET`, and for a graph with no open phase port); `edgeRows`' cap branch takes `min(limit, throttle)` for the direction leaving the port, beside the filter's room limit; `buildInitial` clamps the pass's start flows to the throttle. |
| `src/test/.../science/fluid/network/PhasePortClosureTest.java` | new, 15 tests: fixtures 2 and 3 at 5 s and 0.1 s slices plus the throttle and the vanishing phase (section 5). |

`PassiveIntervalSolver` needed no change: its be-reopen retry calls `reopenable(start, result)` with the step's start graph, from which `reopenable` builds the same mask the solve used. `PhasePortTest` (fixture 1) is unchanged and green with every number of WP1 section 7 identical (its phases stay far above 1 % and its throttles above the velocity caps, so nothing binds).

### 2. The rules as implemented

- **Availability** (stateless, start state). For a VAPOR port `phi = vaporVolume / V`, for a LIQUID port `phi = (liquidVolume + waterVolume) / V`, `V = volume` of the node's state in the graph the step starts from (the accepted state; the junction seed and the cold-start rate seed change junction states only). The port may carry outflow iff `phi >= 0.01`. A closed port refuses the direction leaving its end in `boundaryAllowed`; inflow through it is never refused (D3). Only ports some connection has enter the mask, so the mask of an all-BULK graph is all zero whatever its states and every reader decides exactly as before (gate G1: bitwise).
- **One mask per step** (plan C.3 caveat). `reopenable` evaluates the run's drive on the step's end states but the allowances on the start state's mask, the one the solve refused on. Measured consequence (fixture `thePortAlone...`, section 5): with the reopen test reading no mask, every step in which the port was the only refusal was refused once and re-solved (20 reopens in 24 slices at 5 s, 322 in 600 at 0.1 s); with the start mask, one reopen each, at the step where the inflow actually starts.
- **Throttle.** For each end whose port is open on the start state and whose connection may carry outflow from it:

  `throttle = (phi_start - 0.005) * V_start * rho_stream,start / (n * dt)`

  `rho_stream,start` = the drawn stream's density (`vaporDensity` / `liquidDensity` of the start state; the liquid stream's volume includes the solids, as in WP1); `n` = the number of ends of that vessel with the same phase port whose connection may carry outflow from it by `boundaryAllowed` with the mask (static: blocked masks, generator ends; start-of-solve closures do not change it, so it is a constant of the solve); **`dt` = the solve's step**: the backward-Euler step the interval solver attempts (its estimate truncated to the rest of the interval, halved on every retry), never the slice. Over a step the ports of one phase therefore draw at most `(phi_start - 0.005) V rho_start` together, the phase above the reserve at the start density. It enters the saturated law of `edgeRows` as `limit = min(velocity cap [, filter room], throttle)` for the direction leaving the port (direction index 0 = leaving the first end, 1 = the second), a constant for the solve; absent in a rate solve (no step) and while any device of the island holds `PUMP_TARGET` (the filter's `prescribedFlow` exemption: a prescribed flow and a saturated inlet are two equations for one edge; WP3 revisits the pump fed from a port). A throttled connection's accepted mode stays PASSIVE (as for the filter room limit; presentation only).
- **Start point.** The pass's start flows are clamped to the throttle (A15). Found necessary, not assumed: a 5 s step from rest on a 2 MPa tank whose throttle is 1.5 % of the velocity cap failed its first Newton at the iteration limit (residual 0.076, pass 0) when it started at the cap; clamped, it converges and lands on the reserve.
- **Phase vanishing inside a step.** Nothing new: the pass whose layout has lost the phase reads the port's stream as absent (A9, zero velocity limit), so the cap is `min(0, throttle) = 0` and the converged flow is zero with no illegal direction; the next step start finds `phi = 0 < 0.01` and closes the port. Measured in section 5 (flow -1.5e-39 kg/s, mode PASSIVE, next step CLOSED).

### 3. What the band does, measured (plan 3.4 amended)

The plan expected a draining port to land its phase on the reserve and the band `[0.005, 0.01)` to keep it from reopening. Measured: **the throttle binds only where one step spans the band.** Under the state-change controller (5 % of a vessel's mass or pressure per step) a draining port's steps are short near the end, so the port closes at the first step start below `phi_open`, holding its phase in `[phi_reserve - drift, phi_open)`: 0.9934 % (5 s) and 0.9901 % (0.1 s) on the drained water tank, 0.9865 % on the squeezed gas cap (0.1 s). Where a step does span it (a 2 MPa tank, a 5 s step), the throttle lands the phase on the reserve exactly: 0.49998 % (drift -1.9e-7). The guarantee kept is the floor: no step draws a phase below the reserve by more than the flash drift.

Consequence (risk R1): a port fed while it drains duty-cycles about `phi_open`. Scratch probe `PhasePortDutyCycleProbe` (a 1 m3 nitrogen tank at 150 kPa, 2 % water, fed 0.011 kg/s of water through a 4 mm line, drained through a LIQUID port that carries 1.5 kg/s when open): 45 open/closed transitions in 300 s at 0.1 s slices (86 open slices, 0 rejections), 9 at 5 s (6 open slices, 23 state-change rejections), 5 at fixed 1 s steps; the water share stays in [0.9850 %, 1.0001 %] (0.1 s) and [0.9657 %, 1.0018 %] (5 s) after the first 100 s; no nonconvergence, no held slice, ledgers closed. It is an overflow at 1 %, stepped. Options in section 10.

### 4. Controller rejections and reopens per slice (risk R3)

| fixture (slices) | cadence | accepted / rejected | rejections by slice and reason | reopens |
|---|---|---|---|---|
| dry, LIQUID drain (40 / 1450) | 5 s | 154 / 4 | slices 6, 7: 1; 8: 2 (state change, the vessel's mass shrinking) | 0 |
| | 0.1 s | 1450 / 0 | none | 0 |
| squeeze, VAPOR vent (40 / 900) | 5 s | 151 / 8 | 0: 1 reopen (the vent at rest at the start, BROKEN class 3, base); 3, 8: 1 equation gate each (below); 12: 5 state change (the cushion compressed from 101.6 to 299 kPa) | 1 |
| | 0.1 s | 900 / 1 | 0: 1 reopen (same) | 1 |
| inflow through a closed port (12 / 600) | 5 s | 65 / 27 | 6: 25 (11 reopens, 13 Newton line-search stalls, 1 iteration limit); 7: 2 state change | 11 |
| | 0.1 s | 612 / 19 | 342: 19 (7 reopens, 12 line-search stalls) | 7 |
| the port alone refuses (24 / 600) | 5 s | 72 / 1 | 6: 1 reopen | 1 |
| | 0.1 s | 600 / 1 | 321: 1 reopen | 1 |
| same port drains via a junction (40 / 1000) | 5 s | 217 / 11 | 0-3: 7, 2, 1, 1 state change (water filling a 1 atm gas tank) | 0 |
| | 0.1 s | 1028 / 20 | 0-14: 1-2 each, state change (same) | 0 |
| closed port refuses to drain into a junction (120) | 0.1 s | 148 / 20 | 0-14: 1-2 each, state change (same fill) | 0 |
| dissolving cap (24 / 300) | 5 s | 129 / 17 | 0: 17 state change (200 kPa to 1 MPa) | 0 |
| | 0.1 s | 347 / 10 | 18: 10 state change | 0 |

- The throttle bound no step in these interval fixtures (section 3), so none of the rejections is the throttle's; the "negative transport reconstruction" and several-ports overdraw of R3 did not occur. The per-vessel constraint row R3 held in reserve is not needed on this evidence.
- The reopens of the inflow fixture are the BROKEN class 3' cost review 8.8 (c) measured for water entering a dry gas tank (reopened solves stall from a zero start flow across the liquid/gas friction kink before halving converges); the retries succeed, no slice ends bottled.
- **Equation-gate rejections with a VAPOR vent** (squeeze at 5 s: 2, residual 4.45e-8 and 2.06e-7, the tank's local row 4, dt 2 s, Newton converged to 9.2e-10 and 2.6e-10). Classified as WP1 behaviour, not WP2: the same fixture on the WP1 tree (`3dbd9b8`, no mask, no throttle) gives 4 gate rejections in the first 12 slices where WP2 gives 2, and the same fixture with BULK ends gives none. Halving recovers them; no held slice. Options in section 10.

### 5. Fixtures (`PhasePortClosureTest`, measured; Gradle run G1, identical to the harness run)

Every slice of every interval fixture commits whole (FULL, advanced = interval) and closes the component ledger (worst 2.3e-13 relative) and the energy ledger (worst 6.3e-14).

| test | measured |
|---|---|
| `aLiquidPortDrainsDryAndStaysClosed...` (0.1 m3 water under N2 at 200 kPa, LIQUID port 10 m 25 mm to a void) | 5 s: open for 8 slices (out 10.9 to 4.6 kg per slice), closes inside slice 8, slices 9-39 start closed (31 x 5 s): out 0, mode CLOSED, water share 0.009934488833801991 constant; one open-to-closed transition. 0.1 s: closes at slice 423, 1027 slices closed (102.7 s), water share 0.009901226157602 constant. |
| `aVaporPortIsSqueezedShutByLiquid...` (0.8 m3 water under N2 at 1 atm, filled by a 300 kPa water generator through the LIQUID port, VAPOR vent 10 m 25 mm to a void) | 5 s: vent open 12 slices (18 g per slice), closes inside slice 12, the cushion is compressed to 299.1 kPa in that slice and to 300000.00004 Pa after; final gas share 0.19 %. 0.1 s: closes at slice 617 with the gas at 0.9865 % and 101641.8 Pa, final 300000.0009 Pa, gas 0.327 %. One transition each. |
| `inflowOpensAClosedLiquidPort...` (N2 tank at 150 kPa, LIQUID port facing a 130 kPa water generator, BULK vent 10 mm) | dead-headed (CLOSED) while the tank stands above 130 kPa; water enters in slice 6 (5 s: 0.157 kg, the tank at 128.1 kPa at the slice end; 11 reopens) and slice 342 (0.1 s, 7 reopens); no slice ends bottled (below 130 kPa minus the band with nothing entering); nothing leaves through the port. |
| `thePortAloneRefusesOutflow...` (N2 tanks A 150 kPa / B 120 kPa joined at A's LIQUID port, B fed by a 200 kPa N2 generator) | A holds 150000.0 Pa, run CLOSED, 0 reopens until B passes A; B's gas enters A from slice 6 (5 s) / 321 (0.1 s) after one reopen; A never loses gas through the port. Counterfactual (reopen test with no mask): 20 / 322 reopens. |
| `theSamePortDrainsOnceItsLiquidIsThere...` (N2 tank at 1 atm, LIQUID port to a junction fed by a 300 kPa water generator and drained to a void; generator lowered to 110 kPa after 40 s) | water enters through the closed port (10.5 kg per 5 s slice), 8.31 % of the tank at 113.6 kPa when the generator is lowered; the same port then drains (0.31 kg in the first 5 s slice through the 10 mm line) and the generator's line closes. |
| `aPortBelowItsOpeningShareRefusesToDrainIntoAJunction` (same, generator lowered to 100 kPa after 2 s) | water share 0.42 % when lowered; for 100 slices the tank stands above the junction (103641 against 101325 Pa) and keeps its water, out 0. |
| `theThrottleLandsAnOpenPortAtItsReserveInOneStep` (2 MPa tank, 2 % water, one and two LIQUID lines 2 m 50 mm, one 5 s step) | flows equal the throttle to the printed digit: 2.996802216998662 kg/s (one line), 1.498401108499331 each (two, n = 2); water lands at 0.49998 %; the next step starts CLOSED with flow 0. |
| `aPhaseThatVanishesInsideAStep...` (n-pentane with a 1.5 % methane cap at 200 kPa, fed pentane at 1 MPa, VAPOR vent 10 mm; one 5 s step) | the cap dissolves inside the step (888 kPa at its end); vent flow -1.47e-39 kg/s, accepted mode PASSIVE (no illegal direction); next step: flow 0, CLOSED. |
| `aDissolvingCapIntegrates...` (same vessel, interval solver) | the port closes once (5 s: slice 1; 0.1 s: slice 7), stays closed, the liquid-full vessel stands at 1000000.2 Pa (5 s) / 1000000.00000003 Pa (0.1 s). |

### 6. Gates

| # | command (from `/home/user/CreateChemE`) | result |
|---|---|---|
| G0 | `LIB=<scratch>/lib OUT=<scratch>/harness-wp2 REPO=/home/user/CreateChemE bash tools/cloud-science-harness/harness.sh all` (log `wp2-01-harness-all.log`, 77 s) | science 203/203 (188 + 15), runtime 227/227, the 33 junction lines identical to its `6e1c5b6` reference, adjacent 38/38, chain-100 0.000e+00 |
| G1 | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (log `wp2-02-gradle-fluid-suite.log`, XML `wp2-02-xml/`, 51 s) | **428/428** in 96 classes, 0 skipped (406 base + 7 WP1 + 15 WP2; names `wp2-02-test-names.txt`); the 33 MIXED_GAS_*/LIQUID_JUNCTION lines (`wp2-02-junction-lines.txt`) character-identical to `tools/phase-ports-probes/wp1/logs/02-junction-lines-base.txt` with ms/bytes/allocatedMB masked (`diff` empty); MIXED_GAS_COST 286 Newton solves (612 ms, 135.2 MB, single run, no cost claim) |
| G2 | `./gradlew --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` (log `wp2-03-gradle-regression-exact.log`) | **0.000e+00** on state/moles, temperature, phase fraction and flow; 3 accepted / 0 rejected, 4 Newton solves, 29 iterations |
| G3 | the 38 adjacent (WP1 G3 command; log `wp2-04-gradle-adjacent.log`, XML `wp2-04-xml/`) | **38/38** in 7 classes |
| G4 | `./gradlew --no-configuration-cache compileFluidGameTestJava compileMcpCompatJava --console=plain`, then again with `--rerun` on both tasks (log `wp2-05-gradle-compile-gametest-mcpcompat.log`) | **BUILD SUCCESSFUL**, both tasks executed on the second run |

One Gradle invocation at a time, `JAVA_TOOL_OPTIONS` as the environment sets it, no dev client. Logs in the session scratchpad and copied to `tools/phase-ports-probes/wp2/logs/`. No existing test changed; fixture 1 unchanged. Section 11 records the re-run after the last (comment-only) edit.

### 7. What was not run, and why

- Fluid GameTests and the in-game scenarios: WP2 changes no runtime path, and runtime graphs carry no port until WP5.
- The owner's Windows JDK 21.0.11 lane: not available here.
- No timing claim.

### 8. Plan text amended (tagged "Amended 2026-09-26 after WP2")

3.4 (the throttle's landing and the band, the readers list), section 5 (the "port closed" audit), section 6 fixtures 2 and 3 (landing interval; a generator refuses inflow, so "the same port drains" goes through a junction to a void), section 7 WP2 row, section 8 R1 and R3 (measured), Appendix C.3 caveat (resolved).

### 9. Decisions recorded

`DECISION_LOG.md`, "WP2 defaults": A13 (the availability mask), A14 (the throttle's `dt`, `n`, density and exemptions), A15 (start point inside the throttle), A16 (the landing interval under the stateless rule).

### 10. Options for the owner

1. **The band under short steps (section 3).** (a) Accept: the port closes at the first step start below 1 %, the throttle is a floor, a port fed while it drains duty-cycles about 1 % (measured benign: no failure, no held slice). (b) Hysteresis in the committed endpoint mode: a port that drew in the previous accepted step stays open down to the reserve, a closed one opens at 1 %. It lands phases on the reserve and removes the duty cycle, but is carried state: it would ride on the pump's existing carry (`previousModes`, restated from the committed endpoint modes by `replayStart`, review 8.9 (d)), which is replay-safe in the same way, not a new history input of 8.9 (b)'s kind. Recommended: (a) for v1, (b) only if in-game use shows the duty cycle (it is visible as a line flickering open/closed in the GUI at the 5 s cadence at most). Not implemented: the plan and the brief require the band stateless.
2. **Pump fed from a port (WP3).** The throttle is off while any device holds `PUMP_TARGET` (as the filter room limit). WP3's `INLET_EMPTY` and target-above-supply start decide whether to keep that exemption or to throttle the pump edge itself.
3. **Gate rejections with a VAPOR vent (section 4, WP1 behaviour).** (i) Accept (2-4 halvings in 12 slices, recovered); (ii) investigate the frozen-stream booking against the Newton's stream on a filling tank (row 4 of the tank block) in WP6. Recommended (i) now, (ii) with WP6's gates.

### 11. Re-run after the last edit

The `PHASE_PORT_RESERVE` javadoc was corrected to the measured behaviour (section 3) after G1-G4 (comment only). Re-run on the final tree: G1 (log `wp2-06-gradle-fluid-suite-final.log`) 428/428 in 96 classes, the same test names, the 33 junction lines again identical to the base (`wp2-06-junction-lines.txt`); G2 (log `wp2-07-gradle-regression-exact-final.log`) 0.000e+00.

### 12. Scratch material and commit

Code commit `d836cf2`. Drivers and logs: `tools/phase-ports-probes/wp2/` (`src/PhasePortDutyCycleProbe.java` the R1 probe, `src/SqueezeGateProbe.java` the gate classification probe, `logs/` the gate logs above). The counterfactual runs (reopen test with no mask; the gate instrumentation) were temporary edits of `PassiveStepSolver.java`, reverted; their outputs are in `logs/`.

## D10: water trace seed in junctions with water reachable (2026-09-26)

- Author: Claude (Opus 5.5), same worktree and branch. Base: `9c723e0` (WP2 `d836cf2` plus its tools commit). Commit: `07e7426` (`WIP phase-ports D10: water trace seed in junctions with water reachable`), on `9c723e0`; not merged, not pushed.
- Decision: D10 (owner, 2026-09-26): the base defect of WP1 section 8 is fixed by option 1, a water trace seeded into junctions, chosen over the recommended option 2.

### 1. What changed

| file | change |
|---|---|
| `science/fluid/network/PassiveStepSolver.java` | `initialPhaseSeeds`: a junction whose reachable set holds water (`reachable[node][water]`, from `reachableComponents` on the connections the start closures leave open) while its own state holds none (`waterLiquid + waterVapor == 0`) is seeded with `waterTraceSeed(state)`: its state's total amounts plus a water entry of `1e-12` of their sum, flashed at the junction's own temperature and pressure (`flashTP`), solids carried over; if that flash leaves the property domain the junction keeps its state. Every other node is seeded exactly as before. |
| `src/test/.../science/fluid/network/JunctionWaterTraceTest.java` | new, 2 tests: a humid (unsaturated, 0.5 % and 1 % water) nitrogen vessel on a BULK line, and a wet two-phase vessel's VAPOR port, each into a junction seeded as dry nitrogen and on to a void, at 0.1 s (40 slices) and 5 s (4 slices). |

### 2. The mechanism (why this reaches the layout)

A node's `PhaseLayout` carries water - the water unknown(s), the water balance row, the junction's water mixing row and the vapour's water partial-pressure row - exactly when its **seed** holds water (`PhaseLayout` constructor: `water=seed.waterLiquid()>0||seed.waterVapor()>0`); hydrocarbons reach a layout through the reachable mask, water has no mask entry. A vessel lacking a reachable component already gets a `1e-12` entry trace in `initialPhaseSeeds`; a junction was seeded with its own state only. The trace is therefore planted in the same place and the same size, and it reaches the junction's layout through its seed: flashed at the junction's (T, P), `1e-12` of water is unsaturated vapour, so the seed holds `waterVapor > 0` and the layout gains the water vapour unknown and its rows. Once the first step is accepted the junction's state carries the water it received (or its retained trace), so the trace fires only on a junction's first dry solve (measured: once per case of the new test; see 4). The junction's owned inventory is untouched - the seed is a starting point and a basis, the accumulation rows start from the stock - so the ledgers are unchanged by construction (the new test checks the junction starts with zero water and closes the ledger over vessel plus holdup).

### 3. Verification

- `tools/phase-ports-probes/wp1/src/HumidJunctionProbe.java` (the WP1 reproduction), on the D10 tree: humidity 0, 0.5 %, 1 % at 0.1 s (20 slices) and 5 s (2 slices): all six integrate, 20 / 6 accepted, 0 rejected. On the WP2 tree (`d836cf2`), unchanged: 0.5 % and 1 % fail every interval ("Substep refinement exhausted: Conservative reconstruction fails equation gate: 1.3058e-8" / 1.3058e-7 at 5 s; 2.6151e-8 / 2.6151e-7), as WP1 recorded. Logs `tools/phase-ports-probes/d10/logs/06-humid-probe-*.txt`.
- `JunctionWaterTraceTest` (measured, Gradle run): 0.5 % BULK: 40/0 accepted/rejected at 0.1 s, 12/0 at 5 s, component ledger 8.4e-16 / 4.2e-16, energy 2.6e-16 / 1.3e-16, junction water fraction 0.005, water to the void 0.0043 / 0.0207 mol; 1 % BULK: 40/0, 12/0, 1.1e-15 / 4.6e-16, 2.4e-16 / 1.2e-16, fraction 0.010; VAPOR port of the half-water tank: 40/0, 12/0, 4.3e-16 / 2.8e-16, 2.6e-16 / 2.4e-16, junction water fraction 0.0162 / 0.0175 (the headspace's humidity, diluted by the dry holdup), water to the void 0.0136 / 0.0674 mol.

### 4. Gates, and the re-baseline (none needed)

The brief allowed a recorded re-baseline where a junction gains the trace. Measured: **no existing fixture's junction gains it**, so nothing moved.
- Where the trace fires (temporary print in `waterTraceSeed`, harness science + runtime + adjacent, reverted): only `JunctionWaterTraceTest` (6 times, once per case and cadence). Every other junction in the suites either has no water reachable (the mixed-gas methane/nitrogen matrices) or is seeded wet (liquid lines and filter blocks seed their junctions from a water boundary; `PhasePortTest`'s junction test seeds the gas junction humid on purpose, a comment there still names the WP1 workaround). chain-100 has no junction.
- WP1's bitwise probe (`tools/phase-ports-probes/wp1/src/BitwiseProbe.java`: chain-100 plus 14 all-BULK scenarios in hex, among them mixed-gas junctions at 5 s and 0.1 s, water lines, a pumped line, the wet-crude drain, a slurry filter) on the WP2 and D10 trees: `scenarios.txt` and `chain-100.json` byte-identical, and `chain-100.json` byte-identical to `src/test/resources/fluid/regression/chain-100.json`.

| # | command | result |
|---|---|---|
| G0 | `harness.sh all` (log `d10-01-harness-all.log`, 84 s) | science 205/205, runtime 227/227 with the 33 junction lines identical to its reference, adjacent 38/38, chain-100 0.000e+00 |
| G1 | the fluid suites (WP2 G1 command; log `d10-02-gradle-fluid-suite.log`, XML `d10-02-xml/`, 52 s) | **430/430** in 97 classes, 0 skipped (WP2's 428 plus the 2 new); the 33 MIXED_GAS_*/LIQUID_JUNCTION lines character-identical to `tools/phase-ports-probes/wp1/logs/02-junction-lines-base.txt` (ms/bytes/allocatedMB masked): every ledger value and Newton solve count unchanged, MIXED_GAS_COST 286 Newton solves (580 ms, 135.3 MB) |
| G2 | exact regression (log `d10-03-gradle-regression-exact.log`) | **0.000e+00**, 3 / 0, 4 Newton solves, 29 iterations |
| G3 | the 38 adjacent (log `d10-04-gradle-adjacent.log`) | **38/38** in 7 classes |
| G4 | GameTest and mcpCompat compile, both with `--rerun` (log `d10-05-...`) | **BUILD SUCCESSFUL**, both executed |

**Base for WP3:** the junction lines of this commit are captured in `tools/phase-ports-probes/d10/logs/02-junction-lines-d10.txt` (identical to the WP1 base capture, so WP3 may compare against either); the test names in `02-test-names-d10.txt` (430).

### 5. Not run

GameTests and in-game scenarios (no runtime change; runtime graphs carry no phase port until WP5); the Windows lane.

## D9: level head at the bottom port (option B, H = 1 m) (2026-09-26)

- Author: Claude (Opus 5.5), same worktree and branch. Base: `3338663` (D10 `07e7426`, its tools commits, and the merge of the extreme-topology test branch, which adds `ExtremeTopologyIslandTest` and touches no `src/main`). Commit: see section 10; not merged, not pushed.
- Decision: D9 (owner, 2026-09-26), option B of `LEVEL_HEAD_REVIEW.md`: a level head at the LIQUID (bottom) port only, fixed H = 1 m, no setting. Design: that review, sections 2-5 and 8.

### 1. What changed, per file

| file | change |
|---|---|
| `science/fluid/network/PassiveStepSolver.java` | `LEVEL_HEAD_HEIGHT = 1` (m). `portHead(node, port, state)` (the WP1 helper, zero until now) returns `GRAVITY * model.liquidMass(state) * LEVEL_HEAD_HEIGHT / node.inventory().volume()` at a LIQUID port of a RESERVOIR or PORT node when that condensed mass is positive, and `0` everywhere else. `endPressure` (both overloads), `portHead` and `demand` became instance methods (they need the model for the condensed mass); no other line changed. Javadoc of `endPressure`/`portHead` restated. |
| `science/fluid/network/PassiveNetwork.java` | `PhasePort` javadoc: the port's driving pressure is the node's plus the level head at a LIQUID port (comment only). |
| `src/test/.../science/fluid/network/LevelHeadTest.java` | new, 8 tests (section 5). |
| `src/test/.../science/fluid/network/PhasePortClosureTest.java` | four assertions re-baselined to the bottom port's pressure (section 4): `Slice` gains `headEnd`, the watched vessel's level head at the slice end; the squeeze and dissolving-cap endings assert `P + h = P_generator` instead of `P = P_generator`, same tolerance (1 Pa); javadoc records the old values. |

`IslandCertificate` is unchanged: under option B, H is a constant and V is already the vessel's identity in `graphIdentity`, and the LIQUID port is in the digest since WP1 (A12), so a saved certificate cannot outlive anything the head depends on. No runtime, codec, wire or GUI change.

### 2. The formula and where it enters

`h = g * m_c * H / V`, `g` = `GRAVITY` (9.80665), `m_c` = `FluidThermodynamics.liquidMass(state)` = hydrocarbon liquid + free water + every solid of the state the caller is stating a pressure for, `H` = 1 m, `V` = the vessel's inventory volume. `endPressure(node, port, state) = state.pressure() + portHead(node, port, state)`, so every site the WP1 helper routed (section "WP1", 2; current line numbers of `PassiveStepSolver` at this commit) states the head on its own state, in both directions:

| # | site | state the head is read on |
|---|---|---|
| 1 | `reopenable` :222 | the step's end states (the band `reopenBand(pa, pb)` on the end pressures) |
| 2 | carry offer :379 | the step's start graph |
| 3 | `demand` :1293 (pass loop :499) | the pass's converged states |
| 4 | `initialMassFlows` :982 | the graph the solve starts from |
| 5 | valve start head :1054 (`startPoint`) | the start graph |
| 6 | `initialMassFlow` :1097 and the balanced junction seed :845, :873, :882 | the start graph / the seed trial graph |
| 7 | `headLimitMassFlow` :1129 | the start graph |
| 8 | `closeDeadHeads` :1455 | the start graph |
| 9 | `illegalWithEitherDensity` :1515 | the start graph |
| 10 | `headDensities` :1892 | the pass seeds |
| 11 | `edgeRows` :2259 | the Newton's trial state `st[node]` |

Nothing else states a driving pressure: `grep` of `.pressure()` in the solver leaves the valve's own set-point tests (a vessel pressure, not a driving pressure), pressure scales and retained pressures; `GRAVITY` elsewhere in `src/main` books potential energy only. The head does not enter the WP2 availability mask or the throttle (they read `portPhaseShare`, a volume share), nor the state-change controller (`PassiveIntervalSolver.stateChange`, vessel pressure and mass): WP2's rules are unchanged.

**Jacobian (review 2.2, verified by reading, no entry added).** `buildSparsity` (:1970) declares every column of both end nodes in every edge row, and the structural-zero drop removes columns only from material rows (`columns[c].andNot(materialRows)`), never from an edge row. The block sweep `differentiateEntries` (:2355) re-decodes the perturbed node into `st[node]` and re-evaluates every incident `edgeRows` on it, so the head's derivative (with respect to the node's amount, phase-split, T and P columns) is written into entries that already exist; the flow and actuator columns re-evaluate `edgeRows` with the node states unchanged, where the head is constant, as it should be. The coloured fallback evaluates the whole residual and needs nothing either. **No structural entry was added and `differentiateEntries` is unchanged.**

**Bitwise where it must be.** A VAPOR or BULK end, a non-vessel node and a state with no condensed mass return the integer `0`, so `P + 0.0` is `P`: gas-only and all-BULK islands are the doubles they were (section 6, G0b/G1/G2).

### 3. Checks of the eleven sites (mutation runs, measured)

Each site is covered by a fixture that fails when that site alone omits the head (scratch mutations, `tools/phase-ports-probes/d9/src/mutations/*.patch`, logs `09-*`; applied to a copy of the tree, never committed):

| mutation (the head removed from one site) | `LevelHeadTest` result |
|---|---|
| `closeDeadHeads` | 3 of 8 fail: the drain is dead-headed at every step start and opened by the reopen retry every step (479 reopens at 0.1 s, 84 at 5 s, where the correct tree has 0); the generator held off by the head is opened at the start and moves the tank (`against-5.0`) |
| `reopenable` | 1 of 8 fails: the reopen retry opens the dead-headed generator line against the head (`against-5.0`: the tank moves by 7e-5 Pa) |
| `edgeRows` (residual only) | 7 of 8 fail: bottoms not level (407 Pa, 5860 Pa), the pump closes at the headspace, a bottled drain at a 5 s slice end (bottom 101430 Pa above the void with the drain shut), two nonconvergences |

### 4. Existing tests that moved, and why (classification)

The brief expected nothing to move because "code-built graphs are all BULK". That is not so since WP1/WP2: `PhasePortTest`, `PhasePortClosureTest` and `JunctionWaterTraceTest` build code graphs with phase ports. Measured on the harness science run before any test edit: 205 found, **4 failed**, all in `PhasePortClosureTest`, all the same assertion kind:

| test | assertion | before (WP2 + D10) | with the head | classification |
|---|---|---|---|---|
| `aVaporPortIsSqueezedShutByLiquidAtFiveSecondSlices` | the closed vessel rises to the generator's 300000 Pa (+-1) | 300000.00004 | headspace 290254.82 + head 9745.19 = 300000.01 | forced by D9: the vessel is fed through its LIQUID port, so its bottom, not its headspace, stands at the generator's pressure |
| `...AtTenthSecondSlices` | same | 300000.00085 | 290264.05 + 9735.95 = 300000.00 | same |
| `aDissolvingCapIntegratesAtFiveSecondSlices` | the liquid-full vessel stands at the generator's 1000000 Pa (+-1) | 1000000.21 | 993908.25 + 6091.75 = 1000000.00 | same (a liquid-full 1 m3 pentane vessel: 621 kg over 1 m2) |
| `...AtTenthSecondSlices` | same | 1000000.00000003 | 993908.27 + 6091.73 = 1000000.00 | same |

Re-baselined in place to `P + h = P_generator` with the same tolerance (the javadoc keeps the old values). This is a change of four existing assertions, recorded here and in `DECISION_LOG.md` (A19) for the owner; the alternative that leaves the assertions literal is to feed those two fixtures through a BULK end, which would change what they test (filling through the bottom port). Every other assertion of the three classes passed unchanged; their printed numbers moved where a LIQUID port sees liquid (`logs/07-*` WP2 tree against `logs/06-*`): the WP1 fixture-1 LIQUID line 0.1903 -> 0.1951 kg/s (water) and 0.2235 -> 0.2256 kg/s (pentane); the drained water tank closes at 0.9747 % (5 s, was 0.9934 %) and 0.9854 % (0.1 s, was 0.9901 %); the squeezed vent closes at slice 13 with 0.44 % gas at 162.8 kPa (5 s, was 0.19 % at 299.1 kPa) and at slice 631 (0.1 s, was 617); the vanishing-cap step ends at 882.1 kPa (was 888.1 kPa) with the vent's zero-capped flow reported VELOCITY_LIMITED instead of PASSIVE (the test asserts only "not CLOSED"); the squeeze at 5 s loses its two equation-gate rejections (8 -> 10 rejections, now all state change). The throttle and refusal fixtures print identical numbers. The gas-only `inflow`/`refusal` fixtures differ only where water has entered.

**What waits for WP5.** `PhysicalFluidTopology` still compiles every face to BULK (WP5 is the face-to-port compile), so the four physical-topology assertions of plan Appendix C.5 item 5 do **not** move yet and were not edited: `ElevatedBlockLineIslandTest` :216, :220, :229 (rising, rising filter, rising pump lines, fed through the tank's DOWN face) and `DeadHeadedLineIslandTest` :243 (was :234 in C.2; the raised generator's rest). They come due with WP5, re-baselined to `P_tank + g m_w H/V = P_generator - rho g LIFT`: about 7 kPa lower headspace by the level-head review's estimate (0.72 m of water for a tank charged at 1 atm). For scale, the D9 bitwise probe's rising line (the same line code-built with a LIQUID tank end, tank charged at 150 kPa, 0.574 m3 of water at rest) rests at 355309.09 Pa against 360918.31 Pa without the head: -5.61 kPa, the head of its water (`results/probe/out-d9/liquid-ports.txt`). The review's fixture 6 (face-to-port plus head on a compiled stack) is WP5's too. A note is in the plan's WP5 row.

### 5. Fixtures (`LevelHeadTest`, measured; Gradle run G1)

Every slice commits whole and closes the component ledger to 1e-12 and the energy ledger to 1e-10 (pump work included). Vessels are 1 m3 at 298.15 K, water under nitrogen; lines level unless stated.

| test | fixture | measured |
|---|---|---|
| `aTankAtTheVoidsPressureDrainsOnItsHeadAlone...` (5 s, 40 slices; 0.1 s, 1500) | 0.1 m3 of water, headspace 101275 Pa (50 Pa below the 1 atm void), vented through its VAPOR port from a 1 atm N2 generator, LIQUID drain 2 m 50 mm to the void | drains from the first slice on the head alone (950 Pa at the start; 13.56 kg in the first 5 s slice, 0.283 kg in the first 0.1 s slice); **0 reopens**; closes as WP2 closes a port, at the first step start below 1 %: slice 10 (50 s) at 0.99300 % (5 s) and slice 479 (47.9 s) at 0.99716 % (0.1 s); one transition; the closed vessel keeps its water to 3.4e-12 of the vessel volume. BULK control (no head): 3.3e-6 / 1.7e-6 kg out in total (the vent lifts the headspace to 1 atm, roundoff) |
| `aGeneratorBelowTheBottomPressureStaysDeadHeadedWithoutReopens` (5 s, 12; 0.1 s, 100) | closed tank, 0.3 m3 of water, headspace 100000 Pa, bottom 102867 Pa; water generator at 101325 Pa on its LIQUID port | dead-headed (CLOSED) every slice, flow exactly 0, the tank's pressure bitwise constant, **0 reopens** (`closeDeadHeads` and `reopenable` read the same bottom pressure) |
| `aDrainTheHeadLiftsAboveTheVoidInsideAStepIsReopenedAndNeverBottled` (5 s, 24; 0.1 s, 600) | closed tank, 0.3 m3 of water at 98 kPa (bottom 100.9 kPa, below the void), LIQUID drain to a 1 atm void, filled through a BULK end from a 200 kPa water generator (10 m, 10 mm) | drain closed until the bottom passes the void, opened inside slice 3 by 1 reopen (5 s) and at the start of slice 163 with no reopen (0.1 s); no slice ends bottled (drain shut with the bottom above the void by more than the band); steady state with the headspace below the void (98435.26 Pa) and the bottom above it (101332.43 Pa): only the head drains it |
| `twoTanksAtOnePressureEqualiseTheirBottomsMonotonically` (5 s, 60) | two closed tanks at 101325 Pa, 0.8 and 0.2 m3 of water, LIQUID to LIQUID 10 m 20 mm | bottom difference 5859.8 Pa at the start, monotone (0 increases, no sign change beyond -3.6e-6 Pa), 0.042 Pa after 20 slices, **-3.6e-6 Pa at rest** (Newton tolerance x scale = 1e-4 Pa); 9.73 kg moved (the cushions take most of the imbalance: 96862.28 / 102531.26 Pa at rest); flow exactly 0 from slice 30, states repeating. Certification itself is runtime-package code (`IslandCertificate`, `IslandCoordinator` package-private) and not reachable from this package, so stationarity is asserted instead. BULK control: never moves (flow 0) |
| `cushionsHeldApartStandAtALevelDifferenceOfDpOverRhoG` (5 s, 60) | A and B, 0.3 m3 of water each, LIQUID to LIQUID 2 m 50 mm; A held at 104325 Pa by a N2 generator on a BULK end, B closed at 101325 Pa | rest: P_A 104325.00001, P_B 103965.46, dP 359.54 Pa (< rho g H); level difference 0.0368098 m against dP/(rho g) 0.0368094 m (1e-5 relative); bottoms level to 8.9e-7 Pa. BULK control: headspaces equal (104325.00001 both) with 11.9 kg more water in B, a level difference nothing holds |
| `aPumpIntoABottomPortClosesTheLevelHeadEarlier` (5 s, 30) | water generator 1 atm, junction, pump 0.01 m3/s 300 kPa, N2 tank 1 atm; pump discharge on the tank's BULK or LIQUID end | both pumps CLOSED at the end; side-fed headspace 401324.09 Pa, bottom-fed 394085.62 Pa + head 7239.19 Pa = 401324.81 Pa: the bottom-fed tank closes 7.24 kPa (its water's head, 738 kg) earlier |
| `aFreshSolverReplaysASlowHeadDrainBitwise` (5 s, 12) | the drain fixture through 10 m of 10 mm line | 1.15 kg drained over 60 s (head 950 -> 939 Pa); a fresh solver resumed from the committed interval at slices 1, 4 and 8 reproduces every flow, mode, state pressure and temperature, inventory and boundary of the continuous run bit for bit |

Plan fixture 3's wording ("with the head off all the liquid moves until the source port closes") assumed B vented. Measured: a VAPOR vent on a tank filled through its LIQUID port fails the reconstruction's equation gate at every step size (1.008e-8, interval substep limit exhausted in the first or second 5 s slice), **on the WP2 + D10 tree as on this one** (`logs/08-vapor-vent-gate-classification.txt`, scratch `src/ManometerProbe.java`: `base LIQUID VAPOR VAPOR` fails at slice 3 without any head; a BULK vent integrates but carries B's water out). It is the WP2 open item "gate rejections with a VAPOR vent" (WP2 section 4, option 3), here severe enough to exhaust the substep limit. Not a D9 defect; the fixture closes B instead. Recommended: take it into WP6's gate investigation (WP2 option 3 (ii)) with this fixture as the reproduction.

### 6. Counters (review section 8, measured; no timing claim)

Gas-only guard: the 33 MIXED_GAS_STATIC/TRANSIENT/COST and LIQUID_JUNCTION lines of G1 are character-identical to `d10/logs/02-junction-lines-d10.txt` with ms/bytes/allocatedMB masked, so `MixedGasJunctionTransientTest`'s ledgers and Newton solve counts are unchanged (MIXED_GAS_COST 286 Newton solves). The WP1 bitwise probe extended with gas-only phase-port islands (G0b) is byte-identical.

Liquid fixtures (`LEVEL_HEAD_RUN` lines, `logs/06-*`; SolverDiagnostics over each run):

| fixture | cadence | accepted / rejected | rejections by reason | Newton solves | iterations per solve | Jacobian builds |
|---|---|---|---|---|---|---|
| drain | 5 s | 168 / 21 | 18 equation gate, 3 state change | 190 | 3.48 | 70 |
| drain | 0.1 s | 1500 / 0 | - | 1501 | 0.65 | 485 |
| against the head | 5 s / 0.1 s | 36 / 0, 100 / 0 | - | 36, 100 | 0 | 0 |
| reopen by the head | 5 s | 72 / 1 | 1 boundary reopen | 73 | 1.52 | 28 |
| reopen by the head | 0.1 s | 600 / 0 | - | 600 | 1.06 | 601 |
| equalisation | 5 s | 180 / 0 | - | 180 | 2.21 | 47 |
| manometer (head) / BULK control | 5 s | 182 / 3, 180 / 1 | 1 reopen + 2 equation gate; 1 reopen | 185, 181 | 0.88, 0.45 | 62, 36 |
| pump into bottom / side port | 5 s | 238 / 13, 238 / 13 | 13 state change each | 297, 297 | **5.29 / 5.60** | 159 / 146 |
| slow drain (replay) | 5 s | 48 / 12 | 12 equation gate | 60 | 1.68 | 16 |

- The one head-on/head-off pair with the same work (the pump into a bottom against a side port) has the same solve count and rejections and 5.5 % fewer iterations per solve with the head: no cost measured.
- No new rejection reason. The equation-gate rejections on the drain and the slow drain are the VAPOR-port class above (the vent's inflow into the draining tank): the same drain on the WP2 tree, the head emulated by a void 950 Pa lower, has 8 in 6 draining slices (`logs/08-drain-gate-classification.txt`). The reopens are the expected ones (a vent line at rest at the start; the head's reopen in the reopen fixture).
- Zero nonconvergence; reopen retries 0 in the drain fixture (`LEVEL_HEAD_REVIEW.md` section 8, item 4).

### 7. Gates

JDK OpenJDK 21.0.10 (container); `JAVA_TOOL_OPTIONS` as the environment sets it; one Gradle invocation at a time; no dev client. Logs `tools/phase-ports-probes/d9/logs/`.

| # | command (from `/home/user/CreateChemE`) | result |
|---|---|---|
| G0a | `REPO=/home/user/CreateChemE bash tools/cloud-science-harness/harness.sh all` (log `01-harness-all-d9.log`, 94 s) | science 213/213 (205 + 8), runtime 237/237 (227 + the 10 `ExtremeTopologyIslandTest` cases) with the 33 junction lines identical to its reference, adjacent 38/38, chain-100 0.000e+00 |
| G0b | WP1 bitwise probe extended (`src/BitwiseProbe.java`: chain-100, the 14 all-BULK scenarios, and 8 gas-only phase-port scenarios: a N2 generator into a LIQUID port at 5 s and 0.1 s, a VAPOR-to-LIQUID tank pair at 5 s and 0.1 s, a methane/nitrogen junction fed through VAPOR and LIQUID ports at 5 s and 0.1 s, a dead-headed rising LIQUID port, a dry LIQUID drain), on `0cf4ec9` (before the edit) and on this tree | `chain-100.json`, `scenarios.txt`, `gas-ports.txt` **byte-identical** (sha256 in `results/probe/sha256.txt`), `chain-100.json` identical to `src/test/resources/fluid/regression/chain-100.json`; `liquid-ports.txt` (a rising line and a drain whose LIQUID port sees water) differs, as intended |
| G1 | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (log `02-gradle-fluid-suite-d9.log`, XML `02-xml-d9/`, 70 s) | **448/448** in 99 classes, 0 skipped: D10's 430 names plus the 8 of `LevelHeadTest` plus the 10 of `ExtremeTopologyIslandTest` (merged before this package; names `02-test-names-d9.txt`, no name of the 430 missing); the 33 junction lines (`02-junction-lines-d9.txt`) character-identical to `d10/logs/02-junction-lines-d10.txt` with ms/bytes/allocatedMB masked; MIXED_GAS_COST 286 Newton solves (555 ms, 135.1 MB, single run, no cost claim) |
| G2 | `./gradlew --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` (log `03-...`) | **0.000e+00** on state/moles, temperature, phase fraction and flow; 3 accepted / 0 rejected, 4 Newton solves, 29 iterations |
| G3 | the 38 adjacent (WP1 G3 command; log `04-...`) | **38/38** in 7 classes |
| G4 | `./gradlew --no-configuration-cache compileFluidGameTestJava compileMcpCompatJava --console=plain`, then with `--rerun` (log `05-...`) | **BUILD SUCCESSFUL**, both tasks executed |

Section 11 records the re-run after the last (comment-only) edit.

### 8. Decisions recorded

`DECISION_LOG.md`, "D9 defaults": A17 (what `m_c` and `V` are; solids count without a liquid), A18 (no digest change), A19 (the four `PhasePortClosureTest` re-baselines).

### 9. Not run

GameTests and in-game scenarios (no runtime path changes; runtime graphs carry no phase port until WP5); the Windows lane; no timing claim.

### 10. Commits and material

Code commit `WIP phase-ports D9: level head at the bottom port (option B, H = 1 m)`; tools commit after it. Material: `tools/phase-ports-probes/d9/` (`src/BitwiseProbe.java` the extended probe, `src/DrainProbe.java` and `src/ManometerProbe.java` the gate-classification drivers, `src/mutations/` the three single-site mutation patches, `results/probe/` the probe outputs on both trees, `logs/` the gate logs and captures).

### 11. Re-run after the last edit

The javadoc of `PassiveNetwork.PhasePort` and `PassiveStepSolver.endPressure` was re-wrapped after G1-G4 (comment only). Re-run on the final tree: G1 (log `10-gradle-fluid-suite-final-d9.log`) 448/448 in 99 classes, the same test names, the 33 junction lines again identical to D10's (`10-junction-lines-final-d9.txt`); G2 (log `11-gradle-regression-exact-final-d9.log`) 0.000e+00.

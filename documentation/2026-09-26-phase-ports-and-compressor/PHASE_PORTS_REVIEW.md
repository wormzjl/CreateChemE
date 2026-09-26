# Phase ports and compressor: review

- Batch: `2026-09-26-phase-ports-and-compressor`. Plan: `PHASE_PORTS_PLAN.md`; decisions: `DECISION_LOG.md` (this folder).
- One section per work package. Numbers are measured unless labelled *inferred*.
- Sections, in commit order: WP1, WP2 (superseded by D11), D10, D9, Vent gate defect, D11, D12, D13, D11+D12+D13 merge, WP3 + WP4, WP5, **WP6: gates and close** (the batch's close: residue, removals, final gates, open owner items, what the merge needs).

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

- Author: Claude (Opus 5.5), same worktree and branch. Base: `3338663` (D10 `07e7426`, its tools commits, and the merge of the extreme-topology test branch, which adds `ExtremeTopologyIslandTest` and touches no `src/main`). Commit: `fdf3574` (`WIP phase-ports D9: level head at the bottom port (option B, H = 1 m)`), on `3338663`; not merged, not pushed. Tools and this line: the following commit.
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
| G1 | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (log `02-gradle-fluid-suite-d9.log`, 70 s; the XML reports stayed in the session scratchpad, `d9/02-xml-d9/`) | **448/448** in 99 classes, 0 skipped: D10's 430 names plus the 8 of `LevelHeadTest` plus the 10 of `ExtremeTopologyIslandTest` (merged before this package; names `02-test-names-d9.txt`, no name of the 430 missing); the 33 junction lines (`02-junction-lines-d9.txt`) character-identical to `d10/logs/02-junction-lines-d10.txt` with ms/bytes/allocatedMB masked; MIXED_GAS_COST 286 Newton solves (555 ms, 135.1 MB, single run, no cost claim) |
| G2 | `./gradlew --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` (log `03-...`) | **0.000e+00** on state/moles, temperature, phase fraction and flow; 3 accepted / 0 rejected, 4 Newton solves, 29 iterations |
| G3 | the 38 adjacent (WP1 G3 command; log `04-...`) | **38/38** in 7 classes |
| G4 | `./gradlew --no-configuration-cache compileFluidGameTestJava compileMcpCompatJava --console=plain`, then with `--rerun` (log `05-...`) | **BUILD SUCCESSFUL**, both tasks executed |

Section 11 records the re-run after the last (comment-only) edit.

### 8. Decisions recorded

`DECISION_LOG.md`, "D9 defaults": A17 (what `m_c` and `V` are; solids count without a liquid), A18 (no digest change), A19 (the four `PhasePortClosureTest` re-baselines).

### 9. Not run

GameTests and in-game scenarios (no runtime path changes; runtime graphs carry no phase port until WP5); the Windows lane; no timing claim.

### 10. Commits and material

Code commit `fdf3574` (`WIP phase-ports D9: level head at the bottom port (option B, H = 1 m)`); tools commit after it. Material: `tools/phase-ports-probes/d9/` (`src/BitwiseProbe.java` the extended probe, `src/DrainProbe.java` and `src/ManometerProbe.java` the gate-classification drivers, `src/mutations/` the three single-site mutation patches, `results/probe/` the probe outputs on both trees, `logs/` the gate logs and captures).

### 11. Re-run after the last edit

The javadoc of `PassiveNetwork.PhasePort` and `PassiveStepSolver.endPressure` was re-wrapped after G1-G4 (comment only). Re-run on the final tree: G1 (log `10-gradle-fluid-suite-final-d9.log`) 448/448 in 99 classes, the same test names, the 33 junction lines again identical to D10's (`10-junction-lines-final-d9.txt`); G2 (log `11-gradle-regression-exact-final-d9.log`) 0.000e+00.

## Vent gate defect (2026-09-26): investigation, no code change

- Author: Claude (Opus 5.5), same worktree and branch, base `fdc7571` (D9 plus its tools commit), clean tree. No tracked source changed: the booking the brief would have fixed is already exact, and every change that closes the defect is a solver-policy choice (section 5), so the options are for the owner. Material: `tools/phase-ports-probes/vent/` (instrumentation and prototype patches against `fdc7571`, logs numbered as cited below).
- The defect: the D9 open item "VAPOR vent failure" (D9 section 5; WP2 section 4 and option 3): two tanks joined LIQUID to LIQUID, A fed by a nitrogen generator, B vented through its VAPOR port (`d9/src/ManometerProbe.java 5 4 LIQUID 104325 101325 0.3 0.3 0.05 VAPOR VAPOR`).

### 1. Reproduction on `fdc7571` (measured)

- 5 s slices: slice 0 commits after 497 accepted and 503 rejected substeps (502 equation gate, 1 reopen); slice 1 fails, "Interval substep limit ... advanced=4.2256 of 5.0 s, accepted=509, rejected=515, last=... equation gate: 1.0083460016084983E-8" (`logs/01`), bit for bit the D9 capture.
- 0.1 s slices: 300 slices integrate, 300 accepted, 1 rejected (the vent's reopen at rest) (`logs/07`). The defect needs steps of order a second.

### 2. Which row fails, and why, by numbers (instrumented scratch copy, `src/instr-gate-probe.patch`, `logs/02`)

First failing step: dt 1.894 s, Newton converged to 3.689e-10 after 5 chord iterations. Layout of each tank block: local 0 N2 balance, 1 water balance, 2 energy, 3 volume, 4 water saturation `ln(p_w/Psat)`, 5 partial-pressure closure.

| row | Newton's residual at its point | gate at the reconstructed point |
|---|---|---|
| A local 3 (volume) | -3.8e-11 | **-3.132e-8** |
| A local 4 (saturation) | 1.8e-10 | **4.306e-8** (the gate's maximum) |
| A local 5 | 5.4e-12 | 1.3e-9 |
| B local 3, 4 | 1.9e-11, 1.4e-10 | -1.197e-8, 1.771e-8 |
| balance rows N2, water, energy of A and B | <= 1.8e-10 | <= 1.7e-11 |
| edge rows | 3.7e-10, 1.9e-10 | unchanged |

- **The booking is exact.** Vent edge (B's VAPOR port to the void): the Newton's target booking `dt q n_c/m` and the reconstruction's `dt |q| w_stream / M_c` are the same doubles, N2 0.36169952116860066 mol, water 0.011676887918495993 mol (difference 0; 2.8e-17 at the second failure); the LIQUID link books 504.76023509407260 mol of water in both. The reconstructed amounts equal the Newton's targets exactly: A's N2 29.716298498875040 mol, B's 27.112096349239750 mol (difference 0; at most 3.6e-15 mol in later failures). The plan's inference (3.3 item 2, "the reconstructed inventories equal the Newton's targets to its tolerance") is therefore wrong in its premise: they are equal bit for bit.
- **What is off by the tolerance is the Newton's own state.** Its amounts differ from its own targets: A's N2 by +1.276e-6 mol (+4.29e-8 relative), B's by +4.75e-7 mol (+1.75e-8); water by 5.6e-11 relative. The balance row states that difference over the node's total amount (`PhaseLayout.componentScales` = `amountScale` when the headspace is at least 1 % of the vessel): 15066 mol, of which 15036 water and 29.7 N2, so A's N2 row reads 8.2e-11, well inside the 1e-9 Newton tolerance.
- **The reconstruction restates that difference at fixed (T, P).** `ConservativeTransport.reconstruct` books the exact amounts and keeps the candidate's temperature, pressure and phase ratios (`repartition` -> `adoptingState`, no flash). The volume row then sees the corrected gas amount at the old pressure: 1.276e-6 mol x RT/P (0.02376 m3/mol) = 3.03e-8 of the 1 m3 vessel (measured -3.13e-8); the saturation row sees the water mole fraction move by the relative N2 change: 4.29e-8 (measured 4.31e-8).
- **Amplification.** Saturation row over N2 balance row = n_total / n_N2 = 507; volume row = that times the gas volume fraction 0.73 = 370. The 1e-8 gate thus asks the N2 balance row of such a vessel to close to about 2e-11, 50 times below the Newton's tolerance (the solver's own comment keeps "two orders of margin" to the gate, which holds only for amplification below 10).

### 3. Why the Newton stops there (chord iteration; `logs/04`, `logs/05`)

- A's N2 row per iteration at dt 0.947 s: -1.24e-5, -1.22e-7, -5.1e-10, +6.0e-10, +2.4e-10, +8.5e-11: it contracts by 0.35-0.4 per iteration while the norm reaches 1.5e-10, so the Newton exits with that row carrying a large share of its residual. The row is linear in (n_N2 of A, generator flow) with the coefficient `dt w / s`; the iterations ran on a preconditioner inherited from another step (iteration 0 has `refresh=false`: `WorkspaceKey` carries dt and a new dt forks the previous structure's factorization, `forkPreconditioner`), so that coefficient is stale.
- With a Jacobian built at the start of every solve (this step's dt and state, chord afterwards), the same row is 6.8e-16 after one iteration and 0 after two, and the manometer integrates at 5 s: 4 accepted / 0 rejected per slice after slice 0's reopen; with a fresh Jacobian at every iteration, likewise over 6 slices (`logs/05`).
- In the BULK-link layout the same row ends at 1.3e-13 (`logs/04`) and nothing fails (`logs/03`: LIQUID link + VAPOR vent 1017 failures in 2 slices; LIQUID + BULK vent 1; BULK + VAPOR 0; BULK + BULK 0). The phase ports change which chord mode converges slowest; they do not create the amplification.

### 4. Why it exhausts the substep limit, and that it is not phase-port specific

- Near the threshold the accepted substeps commit reconstructed states whose volume row sits just under the gate (e.g. accepted at dt 2.61e-3 with gate 9.38e-9, volume row -7.35e-9); the next step starts from that inconsistency and its gate hovers at 1.0e-8 at every dt (fail 1.0008e-8 at 5.22e-3 s, pass 9.38e-9 at 2.61e-3 s, fail 1.13e-8 at 1.04e-2 s): the controller alternates between three step sizes until the 1000-substep limit (`logs/03`, tail of the LIQUID/VAPOR file). That is the "1.008e-8 at every substep".
- All-BULK fixtures meet the same row class today and recover it by halving (instrumented runs, `logs/11`): `DeadHeadedLineIslandTest` and `FilterBlockLineIslandTest` fail the gate at the tank's local row 4 at 3.25e-7 and 3.62e-7 (N2 0.10-0.11 % of the tank's amounts, relative N2 error 3.2e-7 / 3.6e-7, gas 28 / 34 % of the volume, volume row -9.1e-8 / -1.2e-7), and `LevelHeadTest`'s BULK drain control at 0.1 s has one (section 5, A). The D9 drain (`d9/src/DrainProbe.java`, the head drain at 5 s: 18 gate rejections in 12 slices) is the same: tank rows 3/4, relative N2 error up to 5.7e-8 (`logs/09`).

Candidates ruled out: the stream composition the Newton used versus the one the reconstruction reads (the candidate is the Newton's last iterate; bookings identical, above); water vapour to the void or the vessels' water rows (water rows at the reconstructed point 0 and 1.3e-14; water booked identically); a stiff gas cap (0.7 m3 of nitrogen at 1 atm is not stiff; the factor is n_total/n_gas); the throttle or the D9 head on one side only (the WP2 + D10 tree fails without a head, D9 `logs/08`; the vent runs PASSIVE, never at its cap or throttle); the solid share or the energy booking (no solids; energy rows 5e-12 and 1.7e-11 at the reconstructed point).

### 5. Classification and options for the owner (not implemented)

A tolerance-level mismatch, but not the booking mismatch the plan inferred: the brief's forced fix (the reconstruction books what the Newton booked) already holds bit for bit. What closes it is a change to when the Newton stops, how it scales a minor component's balance, or how the reconstruction restates a state; each has its own blast radius, so it is the owner's choice.

- **A. Polish before refusing (recommended; taken by the owner as D13, see the section "D13").** When the FULL gate fails, re-solve once from the converged point on a fresh workspace (a Jacobian of this step's own dt and state) at the tolerance times 1e-2, reconstruct, re-check `boundaryAllowed`, the velocity constraint and `phaseCorrection`, and gate that point; refuse as today if any of that fails. Runs only where the gate has already refused, so **every step the gate accepts today is bitwise unchanged**. Prototype `src/vent-gate-polish-prototype.patch` (33 lines in `PassiveStepSolver`), measured: manometer at 5 s, 8 slices, 3-4 accepted / 0 rejected each (slice 0 one reopen), 13 firings, each one iteration, gate 2.2e-8..1.6e-7 -> 7.7e-14..3.0e-12 (`logs/06`); at 0.1 s byte-identical to HEAD, never fires; the BULK-vent control 1 gate rejection per slice -> 0 (`logs/08`); the D9 drain 18 -> 0 (`logs/09`). Cloud harness `all` on the prototype: science 213/213, runtime 237/237 with the 33 junction lines identical to the reference, adjacent 38/38, chain-100 0.000e+00 (`logs/10`); it fires 64 times across the suites (`LevelHeadTest` 40, `FilterBlockLineIslandTest` 18, `DeadHeadedLineIslandTest` 4, `ElevatedBlockLineIslandTest` 2), every assertion holds, and the printed `LEVEL_HEAD_RUN` counters move (`logs/12`): drain 5 s 168/21 -> 152/3 (Newton solves 190 -> 159, iterations 661 -> 379, Jacobian builds 70 -> 54), manometer 182/3 -> 181/1, replay 48/12 -> 36/0, the drain's 0.1 s BULK control output 1.67e-6 -> 8.8e-7 kg. Cost where it fires: one Jacobian build, one or two iterations and one reconstruction, against a halving and its re-solves today.
- **B. Do not fork a preconditioner across dt.** Build the Jacobian at every new dt. Fixes the manometer (measured with a Jacobian at every solve start, section 3); not bitwise wherever the step size changes (*inferred*), and it gives up what the fork saves.
- **C. Per-component balance scales.** State every component's balance over its own amount, as `resolveTraces` already does below a 1 % headspace. Tightens the Newton on minor gases everywhere; not bitwise; the solver's comment records a 1e-11 stall on caloric cancellation near liquid-full water (*inferred risk*).
- **D. Restate (T, P) in the reconstruction.** A UV flash (or a local T, P, split solve) at the booked amounts, energy and volume, so the gate sees only the transport. Not bitwise; one flash per node per step; reverses the "reconstruction does not flash" design (*inferred*).
- **E. Accept.** Recovered by halving in most layouts, but the manometer shows it can lock the controller and fail the interval at 5 s, which a compiled vented tank (WP5) would meet in game.

If A is taken: port the prototype without the trace line, add the regression test the brief describes (a tank filled through its LIQUID port with a VAPOR vent, 5 s and 0.1 s, no "equation gate" key in the rejection reasons of any slice; `SolverDiagnostics` counts verification residuals but has no gate-rejection counter, so the reasons map is the assertion), switch `LevelHeadTest`'s manometer to vent B through its VAPOR port as D9 section 5 intended, and run the usual gates; on this evidence no existing assertion changes.

### 6. Material

`tools/phase-ports-probes/vent/`: `src/instr-gate-probe.patch` (the instrumented gate and Newton, against `fdc7571`; never commit), `src/vent-gate-polish-prototype.patch` (option A, against `fdc7571`), `src/run-probe.sh` (runs the d9 probes against a compiled tree), `logs/01`-`12` as cited. No Gradle run: no tracked source changed.

## D11: priority streams at ports (replaces the "outlet phase absent" closure) (2026-09-26)

- Author: Claude (Opus 5.5), same worktree and branch. Base: `0795215` (the vent investigation's tools commit; the owner's decision-log commit `99f5312`, D13, landed on the branch during the work and touches only `DECISION_LOG.md`). Code commit: `548a9bf` (`WIP phase-ports D11: priority streams at ports (replaces the absent-phase closure)`), on `99f5312`; tools commit after it. Not merged, not pushed.
- Decision: D11 (owner, 2026-09-26): every non-BULK port draws its vessel's phases by priority, each up to what the vessel held of it at the step start, the next phase with the rest ("priority stream"); no port closes for want of a phase. It supersedes WP2 (availability mask, band, port refusal, reopen allowance, throttle as a landing device), A9 (the absent stream), A2 (no decant) and plan 3.4's "liquid-full vessels may increase in pressure".
- Not touched: the vent-gate defect (D13, another package), the cold-start path beyond one mechanical signature change listed in section 11, the D9 head, WP1's reconstruction booking scheme (extended, not replaced), inflow through ports (D3), the structural-zero exemption, all-BULK islands (bitwise, section 8), D10.

### 1. What changed, per file

| file | change |
|---|---|
| `science/fluid/network/PhaseDraw.java` | new, package-private: the priority `order(model, state, port)` (LIQUID: heavier liquid, lighter liquid, gas; VAPOR: gas, lighter, heavier), `leading(...)` (the first phase of the order a state holds: the stream at a vanishing flow), `held(state)`, and `Segment` (order, per-phase capacity, pinned phases, marginal phase) with `decide`, `code`, `samePhases`, `pinnedTotal`, `marginalAt`, `rates`. |
| `science/fluid/thermo/FluidThermodynamics.java` | phase ids `GAS, OIL, WATER`; `holdsPhase`, `phaseSolidShare`, `liquidPhaseDensity`, `heavierLiquid`, `phaseMass`, `phaseVolume`, `phaseMoles`, `phaseSolidMoments`, `phaseSpecificEnthalpy`, `phaseVelocityLimit`, `phaseViscosity`, `phaseCarrierViscosity`, `phaseStream` (null for a phase not held; `vaporStream` for the gas). The hydrocarbon-liquid and free-water streams partition WP1's condensed stream; with one liquid held its stream is the condensed stream to the bit (share exactly 1.0). `PhaseStream` javadoc restated. |
| `science/fluid/network/PassiveStepSolver.java` | Removed: `PHASE_PORT_OPEN`, `PHASE_PORT_RESERVE`, `portPhaseShare`, `portAvailable`, `portBit`, `closedPorts`, the four-argument `boundaryAllowed`, `Equations.closedPorts`, `Equations.throttles` and its builder, the throttle term of `edgeRows`' cap, the throttle clamp of `buildInitial`, `carrierViscosity(state, port)`, `Transport.port`/`absent`. `WorkspaceKey` loses the mask; new `PassKey` (structure plus segment codes) is the cycle key. `Equations` gains `drawsPhase` (was `drawsVapor`/`drawsLiquid`), `capacities`, `seedHeld`, `startPhases`, `segments` and `startInsideCaps`, `capacities()`, `decideSegments`, `segmentCodes`, `segmentsMoved`, `drainedSeeds`, `drawn`, `capLimit` (two overloads), `drawnCarrierViscosity`, `draws`, `transportAt`; `Transport` carries `phases[3]`; `transport`, `nodeAccumulate`, `edgeRows` read `drawn`/`capLimit`. `solve`'s pass loop: pump and valve tests read the drawn stream, the segment re-decision, the drained-seed fallback after a failed Newton, the velocity check, `reconstruct` and `PipeTransfer.sample` with the draws, the accepted-mode clamp; new `velocityCap` (also used by `checkApproximation`). The per-end static helpers (`endDensity`, `endVelocityLimit`, `endViscosity`, `solidShare`, `suctionMassFlow`, `endMoles`, `endMass`, `endSpecificEnthalpy`) read the leading phase. The mask parameter left `reopenable`, `closeDeadHeads`, `closeIllegalStarts`, `reachableComponents`, `initialPhaseSeeds`, `seedBoundaryJunctions`, `balancedBoundarySeed`, `startPoint` (signature and `boundaryAllowed` arguments only). Javadoc of `boundaryAllowed`, `portHead`, `massFlowLimit` and two column comments restated. |
| `science/fluid/network/ConservativeTransport.java` | `reconstruct(..., frozenJunctions, draws)` (the old overloads pass no draws = every outflow bulk); `drawsPhase` removed; `drawnPhases`, `weight`, `at`, `drawnSolidShare`, `phaseFractions`, `streamFractions` read a six-entry draw (end-state rates, start-state rates); the unbacked-trace rule (section 6); the pipe loop's energy, solids, filter capture and pump work, and `pinnedFlows`, read the draw. |
| `science/fluid/network/PipeTransfer.java` | `sample(model, graph, states, rates, duration, draws)`: each drawn phase sampled on its own state (end-state phases on the states, pinned ones on the graph's start state); the bulk branch unchanged. |
| `science/fluid/network/SolidEventIntegrator.java` | `failed`: a phase port is checked on its leading phase: `GAS`, `ORGANIC_LIQUID` or `WATER` outlet, the drawn mass per whole-liquid content `m_phase / share`; a vessel holding none draws as a bulk end. |
| `science/fluid/transport/SolidMobility.java`, `science/fluid/network/PassiveNetwork.java` | javadoc only (the outlets now requested; `PhasePort` under D11). |
| `src/test/.../PhasePortClosureTest.java` | deleted (WP2's 15 tests). |
| `src/test/.../PhasePortPriorityTest.java` | new, 10 tests (section 7). |
| `src/test/.../LevelHeadTest.java` | the drain fixture restated (section 9); `OPEN`/`RESERVE` removed, `liquidShare` computed locally; a per-slice print. |

### 2. The rule, as implemented

- **Order.** On the step's start state (a constant of the solve): LIQUID `[heavier, lighter, GAS]`, VAPOR `[GAS, lighter, heavier]`, the heavier liquid by the liquids' own densities (`liquidPhaseDensity`, no solids), water when not both are held (the order among absent phases does not matter: they have no capacity). A BULK end draws the bulk.
- **Capacity.** Per vessel and phase, `c_phi = m_phi,start / (N dt)`: `m_phi,start` the phase stream's mass on the start state, its solid share included; `N` the vessel's phase-port ends whose connection may carry outflow from it by the static rule (not blocked, not into a generator; A21); `dt` the solve's step (WP2's throttle's `dt`). A rate solve (no step): every capacity infinite.
- **Priority stream.** For an outflow `q` through a phase-port end: walk the order over the phases with capacity; a phase whose capacity the remaining flow exceeds is **pinned** at `c_phi` (provided a later phase of the order the seed holds can take the rest); the first phase the seed holds that covers the remainder is **marginal**, drawing `q - sum c_pinned`. The last phase the vessel holds is never capped (A22): a port's total flow is limited by the hydraulics and the velocity cap only. So a slowly fed drain draws its water at exactly the feed and gas with the rest (section 7, fixture 2), and a vent on a liquid-full tank overflows the lighter liquid (fixtures 3, 4).
- **Pinned at the start state, marginal at the end state (A23).** A pinned phase is drawn at the start state's composition, enthalpy and density (`startPhases`): over the step it takes exactly its share of what the vessel held of it, which exists whatever the end state holds. A marginal phase is drawn at the end state, the implicit form of WP1's streams; with nothing pinned the port's stream is exactly its phase stream (so every single-phase draw is WP1's). Measured need: with the pinned phase drawn at the end state, a step that draws a phase to zero ends on that phase's boundary, where its end-state composition is undefined; the capacity fixture's pass stalled, the phase correction dropped the phase, the next pass (not drawing it) found it again: an active-set cycle at every step size.
- **Mixture.** The stream is the mass-flow-weighted mixture of its phase streams: composition, solids and specific enthalpy mass-weighted, density the no-slip mixture's (`q / sum(r_phi / rho_phi)`, the mass-weighted specific volume), viscosity the phases' volume-flow average (the solver's bulk rule), velocity limit the smallest of the segment's phases (A24). In the Newton it is a `Transport` of rates (`mass = |q|`, every extensive field a rate), so every donor term reads it unchanged. Below the pinned total (an iterate the segment does not describe) the pinned phases take the flow in proportion to their capacities, continuous at the pinned total and at zero flow.
- **Velocity cap of a mixture (A24).** A volume flow `A v_min`: `limit = C + rho_m (A v_min - sum c/rho)`, or, where the pinned phases alone exceed it, `C A v_min / sum c/rho` (continuous). BULK and single-phase ends keep `rho A v` to the bit.
- **A vessel holding none of the three phases** draws the bulk (A31, replacing A9's zero-velocity bulk). Static readers (columns, sign tests, start flows, the mobility check) read the **leading** phase, the priority stream at a vanishing flow (A28).

### 3. The per-pass segment

- **Frozen per pass** (`Equations.segments`), decided in the constructor from the pass's start flows (`initialPoint`) and seeds, like `junctionDonorFirst` and `headDensities`: each Newton residual is smooth in its unknowns.
- **Re-decided by the pass loop** (`segmentsMoved`): after a converged pass whose modes stand, the segments the converged flows land in are compared with the frozen ones; a difference is one more active-set change (seeds = converged states, next pass from these flows). The segments are in the **cycle key** (`PassKey`) but not in the **workspace key**: a segment changes values, not structure (a node with a phase port keeps every column entry whatever it draws), and keeping the workspace across a re-decision keeps the preconditioner (the churn `headDensities`' comment measured for a key component that changes values only).
- **Start inside the mixture's cap (A26)** (`startInsideCaps`): a start flow beyond the segment's velocity cap is moved to it and the segments are decided again (at most three rounds). Measured need: a 5 s step of a 2 MPa tank with 2 % water started at 137.7 kg/s (the water-only start estimate) against a mixture cap of 8.4 kg/s: the Newton stalled. BULK ends and ends drawing the phase the start estimate read keep their flow (it already starts inside that cap, to the bit).
- **Drained seeds (A27)** (`drainedSeeds`, only after a failed Newton whose phase correction changes nothing): a vessel whose pinned segments draw a phase's whole start content (`sum c dt >= m_start`) is reseeded with its seed's amounts less that content, flashed at the seed's T and P, if that changes its phase regime. Measured need: the last 0.0047 kg of nitrogen vented from a tank filled with water (nitrogen does not dissolve in free water): the pass whose seed keeps the gas has no root, the failed iterate still holds gas, and halving the step doubles the capacity so the pinned draw is still the whole gas: the step was refused at every size (overflow fixture, slice 13, substep limit). With the fallback the next pass (liquid-full) converges and the vent overflows water. Bitwise wherever a pass converges today.

### 4. Removals (D11 list)

The availability mask and its readers, the 1 %/0.5 % band and constants, the port refusal in `boundaryAllowed`, its reopen allowance in `reopenable` (the reopen test now reads the static rule only), the throttle in `edgeRows` and the start clamp to it, A9's absent stream; `INLET_EMPTY` was never coded. `closeDeadHeads`, `closeIllegalStarts`, `reachableComponents`, the start point's junction propagation, the approximation probe, `initialPhaseSeeds` and the balanced junction seed read the static rule.

### 5. What is kept

The D9 head (`portHead`, condensed mass at LIQUID ports; its javadoc now says it does not enter the capacities); the reconstruction's frozen-fraction booking on pinned flows, whose fractions are now the draw's (end-state fractions frozen from the converged candidate, pinned ones from the start state); inflow undistinguished (D3); the structural-zero exemption for nodes with ports; WP1's all-BULK path bitwise; D10.

### 6. A WP1 defect D11 exposed: unbacked traces in a frozen stream (fixed)

- *Measured.* The D9 bitwise probe's "mixed gas junction with LIQUID ports" (a methane tank's VAPOR port and a nitrogen tank's LIQUID port into a junction, drained to a void) failed every interval on the D11 tree: "Substep refinement exhausted: Negative transport reconstruction". The WP1-D9 tree fails the same island with the nitrogen tank on a BULK or a VAPOR end (`logs/07-junction-port-probe-head-vs-d11.txt`: VAPOR-BULK and VAPOR-VAPOR fail on HEAD at 5 s and 0.1 s); it passed only because WP2's mask shut the gas-only tank's LIQUID port, isolating the nitrogen (flow 0.0).
- *Mechanism (instrumented copy).* Node 0 (the methane tank) has no nitrogen in its inventory, but its Newton seed carries the 1e-12 entry trace of every reachable component and the candidate keeps 5.2e-25 mol; the frozen stream booked it out of an inventory of zero: `w = -5.7e-28`.
- *Fix (forced by conservation).* A donor that neither holds a component nor receives anything in the step cannot deliver it: its frozen stream books none of it (`ConservativeTransport`, "unbacked traces"; A30). The implicit bulk booking already lands such a component at exactly zero. Donor and receiver book the same fractions, so the ledger stays exact. The probe then integrates every combination on the D11 tree (`logs/07`).

### 7. Fixtures (`PhasePortPriorityTest`, measured; Gradle run G1, identical to the harness run)

Every slice commits whole and closes the component ledger (worst 2.5e-14 relative) and the energy ledger (worst 1.1e-13).

| test | fixture | measured |
|---|---|---|
| (1) `aBottomPortDrainsItsWaterToZeroThenVentsTheGas...` 5 s x 40, 0.1 s x 2000 | 0.1 m3 water under N2 at 120 kPa, LIQUID port 10 m 25 mm to a 1 atm void | water only until the gas breaks through in slice 26 (135 s; 0.1 s: slice 1302, 130.3 s), in one step: that slice draws the last 0.350 kg of water (0.1 s: 0.053 kg) at its capacity and 0.054 kg of gas; the tank then holds only the condensate of its expanding, cooling headspace (share 1.5e-7, then less), drawn in traces (5.0e-4 kg in all; 0.1 s: 1.7e-3 kg), water exactly 0 at slice 30 (0.1 s: 1406); the port vents the headspace to 101325.00 Pa; never CLOSED while the tank stands above the void, 0 reopens. Rejections: 7 state change (5 s); 2 phase-correction cycles at the saturation boundary (0.1 s, slices 1372 and 1406, recovered by halving) |
| (2) `aSlowlyFedDrainBreaksThroughSteadily...` 5 s x 60, 0.1 s x 1500 | N2 tank 150 kPa with 2 L water, headspace fed 99.9 %-humid N2 by a 150 kPa generator (BULK), water injected at 0.002 kg/s, LIQUID port 10 m 10 mm to 1 atm | after settling, water and gas leave together in every slice; liquid per slice 0.0099999 kg against 0.01 fed (worst 1.05e-5 relative; 0.1 s: 9.3e-6); the port's liquid fraction 0.296519 against feed/total 0.296522; slice-to-slice variation of the liquid 3.9e-8 (0.1 s: 1.2e-9); tank at 298.147 K, 149997.54 Pa. With dry nitrogen the tank cools towards a wet-bulb temperature (298 to 292.6 K in 300 s) and never rests, hence the humid supply |
| (3) `aTopVentOverflowsTheLiquidOfATankFilledFull...` 5 s x 40, 0.1 s x 900 | 0.8 m3 water under N2 at 1 atm, 300 kPa water generator into the LIQUID port, VAPOR vent 10 m 25 mm to 1 atm | the vent carries gas until slice 13 (0.1 s: 663), where it vents the last 0.0047 kg of gas with 7.83 kg of water, then overflows water; the tank ends liquid-full at 195782.75 Pa (+ head 9768.39 Pa), its bottom never above the generator; at rest the overflow equals the feed (10.76015 against 10.76012 kg per slice, 2.4e-6). Rejections recovered: 12 state change, 2 "hydrocarbon vapour partial pressure below the numerical floor", 1 negative reconstruction, 1 reopen (5 s) |
| (4) `pentaneOverWaterIsDrawnInEachPortsPriorityOrder` 5 s | bottom: 0.05 + 0.05 m3 pentane over water under N2 at 120 kPa, LIQUID port to 1 atm; top: 0.3 + 0.3 m3 at 1 atm, 300 kPa water generator into the LIQUID port, VAPOR vent | bottom: water (21.56 kg) until slice 4, pentane (11.53 kg) until slice 7, then gas; after pentane began, water only as condensate (at most 6.7e-4 kg per slice). Top: gas (1.61 kg) until slice 43, pentane (101.2 kg) until slice 56, then water; the tank never above the generator |
| (5) `aGasOnlyTanksBottomPortVentsGasFromTheFirstStep` | N2 at 200 kPa, LIQUID port 10 m 20 mm to 1 atm | 5 s: 0.255 kg of gas in the first slice (5 steps, 0 rejected); 0.1 s: 5.9 g in one step; nothing but gas; never CLOSED |
| (6) `closedPhaseLinesOnAThreePhaseVesselLeaveTheBulkLineBitwise` | pentane + water + N2 vessel, VAPOR and LIQUID lines closed both ways, BULK line open, 5 x 1 s steps | every double equal to the all-BULK graph's |
| (7) `twoBottomPortsShareEachPhasesCapacityEqually` | N2 tank 200 kPa with 0.5 L water, one or two LIQUID lines 10 m 10 mm, one 5 s step | one line: water 0.0935724 kg/s = `m_w/dt` to the printed digit (flow 0.09428, gas 7.0e-4); two lines: 0.0467862 kg/s each = `m_w/(2 dt)` (flow 0.04846, gas 1.68e-3 each); the tank ends with 9.6e-5 / 3.7e-4 kg of free water, the condensate of its expanded headspace (it held 0.023 kg as vapour) |

Bitwise (6) also, by the D9 probe: see section 8.

### 8. Bitwise checks

- **D9 bitwise probe** (`tools/phase-ports-probes/d9/src/BitwiseProbe.java`, run on HEAD `0795215` before editing and on the final tree; outputs and sums in `d11/results/probe/`): `chain-100.json` and `scenarios.txt` (the 14 all-BULK scenarios) **byte-identical**, `chain-100.json` identical to `src/test/resources/fluid/regression/chain-100.json`. `gas-ports.txt`: 3 of 8 scenarios identical (the N2 generator into a LIQUID port at 5 s and 0.1 s, the dead-headed rising LIQUID port: no gas-only tank's LIQUID port has outflow there); the other 5 are exactly those in which WP2's mask shut a gas-only tank's LIQUID port and D11 makes it vent gas (fixture 5): the tank pair (B's LIQUID port to the void: 0 -> 0.00425 kg/s), the methane/nitrogen junction at 5 s and 0.1 s (the nitrogen tank now takes part; first interval identical, then the junction's flows change), the dry LIQUID drain (0 -> 0.0299 kg/s). `liquid-ports.txt`: the water drain identical; the rising water line differs from its first interval at 1e-12 relative (its tank starts gas-only, and its LIQUID end's column now reads the gas stream's density where A8 read the bulk's; final pressure 355309.0899989 Pa either way). Per-scenario table `results/probe/scenario-comparison.txt`, flows `differing-gas-ports.txt`.
- **Closed lines** (PhasePortTest's WP1 test, unchanged and green, plus fixture 6 on a three-phase vessel): a closed phase line draws nothing, decides no segment and costs its node no structural zero.
- **Gas-only guard:** the 33 MIXED_GAS/LIQUID_JUNCTION lines character-identical to D10's (G1).

### 9. Restated assertions (LevelHeadTest)

Only the drain fixture `drainOnTheHead` (both cadences) asserted WP2's landing; every level-head physics assertion is kept.

| old assertion (D9 tree) | old value | new assertion | new value |
|---|---|---|---|
| open slices (water share >= 1 % at the slice start) drain; closed ones carry exactly 0 with mode CLOSED | closed from slice 10 (5 s) / 479 (0.1 s) | every slice whose bottom stands above the void (by the reopen band) drains water and is not CLOSED | drains every such slice |
| the port closes (`closedAt > 0`) with exactly one open-to-closed transition | 1 transition | the water falls below 1e-6 of the vessel (`dryAt > 0`) and stays there | slice 16, 85 s (5 s) / slice 771, 77.2 s (0.1 s); final water share 0.0 |
| landed water share in [0.5 % - drift, 1 %) | 0.99300 % (5 s) / 0.99716 % (0.1 s) | after `dryAt`, the drain passes no more than the trace of water the tank held at the slice start, plus 1e-6 kg/s of condensate | holds; last slice 2.2e-12 kg (5 s), 0 (0.1 s) |
| the closed vessel keeps its water (final share = landed +- 1e-5) | kept | the drained tank stays below 1e-6 | holds |
| kept: drains in the first slice; 0 reopens; BULK control < 1e-4 kg | 13.56 kg / 0.283 kg; 0; 3.3e-6 / 1.7e-6 kg | unchanged | 13.556 kg / 0.2832 kg; 0; 3.28e-6 / 1.67e-6 kg |

Counters of the restated drain (the drain now goes on to the trace, so the steps near the end are the controller's): 5 s 213 accepted / 34 rejected (9 state change, 11 equation gate, 6 Newton limit, 5 line-search stall, 3 phase-correction cycle; D9: 168 / 21), 0.1 s 1500 / 0. The other seven `LevelHeadTest` cases print the numbers they printed on D9 (manometer, pump, equalisation, reopen, dead-headed, replay). No other existing assertion changed; `PhasePortTest` (7) and `JunctionWaterTraceTest` (2) pass unchanged, their printed numbers equal to D9's to the printed digit except the solids fixture's filter capture (9.92631678707979 -> 9.926316787079783 kg, carrier-viscosity roundoff).

### 10. Gates

JDK OpenJDK 21.0.10 (container); `JAVA_TOOL_OPTIONS` as set; one Gradle invocation at a time; no dev client. Logs `tools/phase-ports-probes/d11/logs/`.

| # | command (from `/home/user/CreateChemE`) | result |
|---|---|---|
| G0a | `REPO=/home/user/CreateChemE bash tools/cloud-science-harness/harness.sh all` (log `01-harness-all-d11.log`, 94 s) | science 208/208 (213 - 15 + 10), runtime 237/237 with the 33 junction lines identical to its reference, adjacent 38/38, chain-100 0.000e+00. No exclusion-list change: `PhasePortClosureTest` was never excluded and `PhasePortPriorityTest` needs no Minecraft class |
| G0b | D9 bitwise probe, HEAD and final tree (section 8) | chain-100 and all-BULK scenarios byte-identical; gas-port scenarios as listed |
| G1 | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (log `02-...`, 82 s) | **443/443** in 99 classes, 0 skipped: D9's 448 less the 15 `PhasePortClosureTest` plus the 10 `PhasePortPriorityTest` (names `02-test-names-d11.txt`); the 33 junction lines (`02-junction-lines-d11.txt`) identical to `d10/logs/02-junction-lines-d10.txt` with ms/bytes/allocatedMB masked; MIXED_GAS_COST 286 Newton solves (653 ms, 135.0 MB, single run, no cost claim) |
| G2 | `./gradlew --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` (log `03-...`) | **0.000e+00** on state/moles, temperature, phase fraction and flow; 3 accepted / 0 rejected, 4 Newton solves, 29 iterations |
| G3 | the 38 adjacent (WP1 G3 command; log `04-...`) | **38/38** in 7 classes |
| G4 | `./gradlew --no-configuration-cache compileFluidGameTestJava compileMcpCompatJava --console=plain`, then with `--rerun` (log `05-...`) | **BUILD SUCCESSFUL**, both tasks executed |

### 11. Methods touched (for the merge with the D12 cold-start work)

- `PassiveStepSolver`: `reopenable` (mask removed), `solve` (mask removed; cycle key; pass loop as section 1), `WorkspaceKey`, `PassKey` (new), `checkApproximation`, `reachableComponents` (both), `initialPhaseSeeds` (signature and one `boundaryAllowed` call), `seedBoundaryJunctions`, `balancedBoundarySeed`, `startPoint` (signature and one `boundaryAllowed` call), `velocityCap` (new), `massFlowLimit` (javadoc), `portHead` (javadoc), `endDensity`, `endVelocityLimit`, `endViscosity`, `solidShare`, `suctionMassFlow`, `endMoles`, `endMass`, `endSpecificEnthalpy`, `closeDeadHeads` (signature and two calls), **`closeIllegalStarts` (signature: the mask parameter; its `startPoint` and `boundaryAllowed` calls lose the mask argument; nothing else)**, `illegalWithEitherDensity` (a comment), `boundaryAllowed` (javadoc; the four-argument overload removed), `carrierViscosity(state, port)` (removed), and in `Equations`: the fields and constructor, `startInsideCaps`, `capacities`, `decideSegments`, `segmentCodes`, `segmentsMoved`, `drainedSeeds`, `drawn`, `capLimit`, `drawnCarrierViscosity`, `draws`, `transportAt` (all new), `buildSparsity` (the exemption's flag), `buildInitial` (throttle clamp removed), `Transport`, `transport`, `nodeAccumulate`, `edgeRows`. **Not touched:** `initialMassFlows`, `initialMassFlow`, `headLimitMassFlow`, the interval solver's cold rate seed, `PassiveIntervalSolver`. `initialMassFlows` and `initialMassFlow` call `endDensity`/`endViscosity`/`massFlowLimit`, which now read the leading phase (for a BULK end the same doubles).
- `ConservativeTransport`: `reconstruct` overloads, `reconstruct0`, `pinnedFlows`, the draw helpers. `PipeTransfer.sample`. `SolidEventIntegrator.failed`. `FluidThermodynamics` (additions only).

### 12. Decisions recorded, open items

`DECISION_LOG.md`, "D11 defaults": A20-A32. Open items for the owner:

1. **Adiabatic humid venting below the water domain (base, exposed by D11).** A humid tank vented from 180 kPa or more to 1 atm cools below 273.16 K (the vessel is adiabatic) and every step is refused on `thermo-domain: Water temperature < 273.16 K`; the WP1-D9 tree fails the same with all-BULK ends (`logs/08-humid-vent-probe-head-vs-d11.txt`: 200 and 180 kPa fail at 5 s and 0.1 s, 130 kPa passes at 277.7 K). Under WP2 a drained tank closed its port at 1 % and never vented; under D11 it does (the D11 200 kPa drain fails at 60 s, `logs/09`). Fixtures 1 and 4 run at 120 kPa. Options: (a) accept (a player's tank venting a humid gas from above about 1.35 bar holds); (b) vessel wall heat exchange with an ambient; (c) a frozen-water (ice) region in the water properties; (d) clamp the water property evaluation below the triple point. Not decided by D11.
2. **Recovered rejections at phase boundaries.** A pinned draw that removes the last of a phase leaves the end state on that phase's boundary; halving recovers the phase-correction cycles (fixture 1 at 0.1 s: 2 in 2002 steps; the head drain at 5 s: 3) and the Newton limits (fed drain at 5 s: 10 in 212, all in its first three slices, where the initial water breaks through; none at rest). No slice was held. A per-step hysteresis on the phase correction is the lever if in-game cost shows.
3. **Condensate after a phase is drawn to zero** is physical (the headspace expands and cools): it is drawn in traces by the next steps (fixtures 1, 4). Tests treat 1e-6 of the vessel as "gone".
4. **The mobility check reads the leading phase**, not the step's mixture (its `StageGuard` sees states and flows, no draw); a liquid pinned beside gas is checked at the liquid's velocity for the whole flow (conservative for blockage, optimistic for deposition). Carrying the draws into the guard is the option.
5. **Unbacked traces** (section 6) are dropped only for a donor that receives nothing in the step; a donor that also receives, with a seeded trace its inflows do not bring, could still overdraw it (none measured). Options: drop per component when no inflow could carry it (a reachability test at booking), or cap each frozen fraction at what the donor can deliver.
6. **WP3/WP4.** `INLET_EMPTY` is gone (D11); a pump fed from a phase port reads the priority stream's density in its target and head rows, so WP3's `INLET_WRONG_PHASE` decision (D1) reads the leading phase or the draw.
7. **Vent gate (D13).** The equation-gate rejections still present (head drain 5 s: 11; replay: 12; manometer: 2) are the vent-gate class; D13's polish is another package. *Closed by the D13 merge: 0 in every suite (section "D11+D12+D13 merge", 2).*
8. **WP1 condensed-stream builders** (`liquidStream`, `liquidCarrierViscosity`, and the parts only it uses) are no longer read by the solver; kept for WP6's cleanup.

### 13. Material

`tools/phase-ports-probes/d11/`: `src/` (`JunctionPortProbe`, `ColdVentProbe`, `HumidVentProbe` the classification probes; `DrainTraceProbe`, `OverflowTraceProbe` pass tracers needing `pass-trace.patch`; `compile-main.sh`), `results/probe/` (the D9 bitwise probe on HEAD and on D11, sums, comparisons), `logs/` (gates 01-05, fixture lines 06, probes 07-09).

## D12: one-way generators from the cold start (2026-09-26)

- Author: Claude (Opus 5.5), worktree `wt-coldstart` (session scratchpad), branch `claude/cold-start-generators-wip`. Base: `7051391` (D9 `fdf3574`, its tools commit, the decision-log commit with D11/D12). Code commit: `418ca7e` (`WIP phase-ports D12: one-way generators from the cold start`); not merged, not pushed.
- Decision: D12 (owner, 2026-09-26): a generator only pushes when its pressure allows and never receives, from the cold start's first pass; fix order O2, then O1, O3-O5 only as options (`EXTREME_TOPOLOGY_TESTS.md` section 6). D11 is not implemented here.
- **Gates: the Minecraft-free harness only. The Gradle gates were not run in this worktree (another agent held the single Gradle lane) and must be run after the merge** (the fluid suites with the junction lines, the exact regression, the 38 adjacent, GameTest and mcpCompat compile).

### 1. The mechanism, confirmed by numbers

`EXTREME_TOPOLOGY_TESTS.md` section 5 read the defect from the solver. Measured here with scratch drivers (`tools/phase-ports-probes/d12/src/`):

- **The cold rate seed fails first.** `PassiveIntervalSolver.coldRateSeed` solves the port graph once at a cold start; on the three full fixtures that rate solve fails in pass 0 (GRID "Newton iteration limit at residual 0.5402; active-set pass=0", ALTERNATING and BOTH_SIDES 2.5263), so `coldRateSeed` returns the graph unchanged and the first steps start from the compiler's junction guess (every junction at 110 kPa, the first boundary's state) with no accepted structure, so neither `closeDeadHeads` (runs ending at a junction) nor `closeIllegalStarts` (no accepted solve) reaches a generator edge: pass 0 of every step has ten two-way generators, and 20 halvings exhaust the interval (21 Newton solves = the failed rate solve + 20 steps).
- **One-way generators are necessary but, from the guess, not sufficient.** Rate solves of the GRID port graph with generator edges blocked below a threshold: from the 110 kPa guess, closing below 135, 145, 155, 165 or 175 kPa all fail in pass 0; from a uniform junction pressure of 150/170/180 kPa, closing below 165 kPa (exactly the generators the converged solution closes, 110-160 kPa) converges at every seed, closing below 145 kPa converges only from 170 kPa, below 175 kPa only from 170 kPa; with the generators two-way no seed converges. So the defect is the active set (any receiving generator in pass 0 is fatal here) and pass 0 also needs junction pressures near the solution.
- **The physical active set** (the converged D12 rate seed, tanks held): GRID junctions 164.8-170.2 kPa, generators 170, 180, 190, 200 kPa supply (190, 200, 180 on the cap), the six at 110-160 kPa closed; ALTERNATING junctions 140.1-188.2 kPa, generators 190, 200, 180 supply, seven closed; BOTH_SIDES the same by symmetry.

### 2. What changed

| file | method | change |
|---|---|---|
| `science/fluid/network/PassiveStepSolver.java` | `solve` | one line after `closeIllegalStarts`: `graph=closeColdReceivingGenerators(graph,boundaryClosed,keep,closedPorts,checkpoint)` |
| | `closeColdReceivingGenerators` (new) | acts only on a **rate solve with no accepted solve of its structure** (the cold rate seed); skips an island with no generator, with solids, or with any actuator or filter; collects every open passive generator run (through degree-two junctions) that ends at a junction of degree three or more; asks `oneWayPressures`; closes (`boundaryClosed`, for this solve) each run whose far junction the estimate puts above the generator's end pressure (with its column) by more than `reopenBand`; if any run closed, returns the graph with every estimated junction re-flashed at the estimate's pressure, its own temperature and composition (inventory untouched; a domain refusal keeps the guess); otherwise returns the graph unchanged |
| | `oneWayPressures` (new), class `OneWay` (new: `residual`, `sweep`, `flow`, `scale`, `runFlow`), `maximum`, `oneWayResult`, `solveBanded` (new) | the one-way pressure estimate: unknowns = junctions a held node reaches through open connections, numbered breadth-first from the held nodes; each connection carries `initialMassFlow`'s law in the column of the donor end the direction draws from (the run's loss inverted by a safeguarded Newton, capped at the donor's velocity limit), nothing in a direction `boundaryAllowed` refuses (so a generator below its junction carries nothing) or between the two columns; a gas-only junction donor has its density and cap scaled with its pressure (ideal gas); a damped Newton on the junction pressures (per-connection difference derivatives, banded elimination, steps held to half the held-pressure hull widened by the largest static column, Armijo on the squared residual), with one Gauss-Seidel sweep (60-step bisection per junction) wherever the Newton step does not descend; converged at max net inflow <= 1e-9 x max(1, largest cap) kg/s; null (nothing closed) if not converged in 200 iterations or 100 sweeps |
| `src/test/.../runtime/fluid/ExtremeTopologyIslandTest.java` | class javadoc, `FULL_CASES_OPEN_DEFECT`, `theFullCasesHoldOnTheirFirstIntervalOpenDefect` javadoc | `FULL_CASES_OPEN_DEFECT = false`, so the flagged test runs `assertIntegrates` (every oracle of the passing cases) on the three full fixtures at the default cap: it is now the D12 regression; the defect section and the method javadoc keep the history (the reproduction branch is kept; set true on this tree it fails "expected to hold on its first interval ... it now integrates 40 intervals", measured). No assertion changed. |

`PassiveIntervalSolver`, `ConservativeTransport`, the reconstruction, `closeDeadHeads`, `closeIllegalStarts`, `startPoint`, `initialMassFlows`, the balanced seed and the pass loop are untouched.

### 3. O2, and why O1 was not needed

- **O2 is the fix**: the cold-start rate seed now starts with one-way generators. Its closures bind the rate solve only; the rate seed converges (all three fixtures), its structure then counts as accepted, and the first step's existing `closeIllegalStarts` closes the generators the seed shows receiving (start flow zero, `initialMassFlows` on the seeded states illegal, `illegalWithEitherDensity`) before that step's pass 0, with the seed's junction states and flows as the start. So the first pass of every step starts from admissible directions, which is what D12 asks, without a new rule in the step solve.
- **O1 not needed, and alone not sufficient.** Measured with the closure applied to any first solve and the cold rate seed disabled (a variant, not kept; `logs/05-o1-first-step-only.txt`): GRID and ALTERNATING at 0.1 s integrate, the three 5 s runs and BOTH_SIDES at 0.1 s still fail interval 1 in pass 0 (4 of 6; the interval solver reopened a closed run in 3 of them, "Backward-Euler boundary reopened=1", after which pass 0 fails as before). The final code therefore gives the closure to the rate seed only (A33). O3-O5 were not needed.
- **Design steps measured on the way** (drivers kept): a Gauss-Seidel estimate alone needs 80-488 sweeps to 1 Pa (0.4-5 s on the grid) and, cut to 5-40 sweeps, misses closures and the rate solve fails (`D12Estimate.java`); a pure Newton estimate with the start densities put the grid junctions about 10 kPa high (the guessed junction state is at 110 kPa) and wrongly closed the 170 kPa generator, whence the gas scaling; a pure Newton stalls at the cap and one-way kinks (grid: no descent at iteration 10), whence the Gauss-Seidel fallback (one sweep on the grid, none on the rows). The final estimate matches the converged rate seed to a few Pa (grid junction 11: 164792 against 164796 Pa) in 24 iterations on the grid (one of them a Gauss-Seidel sweep) and 27 on the rows.

### 4. Per-fixture numbers, before (`7051391`) and after

Full fixtures at the default 100 m/s cap (`theFullCasesHoldOnTheirFirstIntervalOpenDefect`):

| Fixture | Slice | Before | After: intervals | Newton solves | Accepted / rejected | Worst moles | Worst energy | Monotone tanks |
|---|---|---|---|---|---|---|---|---|
| GRID | 5 s | fails interval 1 (21 solves, residual 0.5274) | 40/40 | 149 | 140 / 4 | 3.93e-16 | 1.24e-15 | 10/10 |
| GRID | 0.1 s | fails interval 1 (21, 0.6011) | 400/400 | 404 | 400 / 0 | 1.96e-14 | 2.18e-15 | 6/10 |
| ALTERNATING | 5 s | fails interval 1 (21, 2.5106) | 40/40 | 153 | 141 / 6 | 2.87e-15 | 5.55e-16 | 10/10 |
| ALTERNATING | 0.1 s | fails interval 1 (21, 2.5247) | 400/400 | 405 | 400 / 1 | 1.58e-14 | 6.87e-15 | 8/10 |
| BOTH_SIDES | 5 s | fails interval 1 (21, 2.4947) | 40/40 | 162 | 144 / 7 | 4.44e-15 | 1.47e-15 | 10/10 |
| BOTH_SIDES | 0.1 s | fails interval 1 (21, 2.5231) | 400/400 | 430 | 409 / 10 | 5.28e-15 | 1.62e-15 | 8/10 |

Every oracle passes: no generator receives, ledgers < 1e-12 / 1e-10, no tank above `P_max`, the lowest tank nondecreasing, directions from interval 2 on (351/3746, 351/3780, 702/7342 statements judged), the highest generator supplies and the lowest tank receives in the first 0.1 s, rest at `P_max` within 1e-6 with every other generator CLOSED. 5 s against 0.1 s (largest relative differences at t = 5..40 s): GRID pressure 3.71e-3, temperature 0.346 K, moles 3.08e-3, supplied 1.16e-2, generator split 1.2 %; ALTERNATING 2.88e-3, 0.298 K, 2.33e-3, 9.77e-3, 0.9 %; BOTH_SIDES 2.50e-5, 0.017 K, 5.24e-5, 4.97e-5, 0.5 % (bound 2 % and 0.5 K).

Reduced cases (spread 0.2, default cap) and the uncapped full cases (100000 m/s) moved because their cold rate seeds also see receiving generators; every oracle still passes, the cadence lines are unchanged except the printed generator split (ALTERNATING 0.0199 to 0.0198 reduced, 0.00385 to 0.00386 uncapped):

| Case | Slice | Newton solves before -> after | Accepted / rejected before -> after | Worst moles before -> after | Worst energy before -> after |
|---|---|---|---|---|---|
| GRID @0.2 | 5 s | 142 -> 139 | 127/3 -> 127/3 | 2.53e-15 -> 2.69e-15 | 1.55e-15 -> 1.74e-15 |
| GRID @0.2 | 0.1 s | 410 -> 407 | 400/0 -> 400/0 | 4.35e-15 -> 4.10e-15 | 3.50e-15 -> 4.28e-15 |
| ALTERNATING @0.2 | 5 s | 141 -> 142 | 127/3 -> 127/3 | 8.55e-16 -> 8.83e-16 | 1.89e-15 -> 1.67e-15 |
| ALTERNATING @0.2 | 0.1 s | 413 -> 409 | 401/1 -> 400/0 | 3.88e-15 -> 7.33e-15 | 7.61e-15 -> 1.78e-15 |
| BOTH_SIDES @0.2 | 5 s | 153 -> 155 | 128/4 -> 128/4 | 2.80e-15 -> 2.88e-15 | 4.70e-15 -> 4.86e-15 |
| BOTH_SIDES @0.2 | 0.1 s | 433 -> 425 | 402/2 -> 402/2 | 7.57e-16 -> 1.63e-15 | 3.37e-15 -> 3.17e-15 |
| GRID uncapped | 5 s | 160 -> 154 | 143/5 -> 143/5 | 4.90e-16 -> 1.03e-15 | 2.22e-16 -> 8.88e-17 |
| GRID uncapped | 0.1 s | 434 -> 430 | 412/8 -> 412/8 | 4.68e-15 -> 6.75e-15 | 1.91e-15 -> 1.73e-15 |
| ALTERNATING uncapped | 5 s | 182 -> 158 | 144/5 -> 144/5 | 1.86e-15 -> 1.59e-15 | 2.25e-15 -> 1.28e-15 |
| ALTERNATING uncapped | 0.1 s | 443 -> 426 | 413/9 -> 412/8 | 3.06e-15 -> 2.62e-15 | 2.96e-15 -> 1.37e-15 |
| BOTH_SIDES uncapped | 5 s | 216 -> 165 | 145/6 -> 145/6 | 1.37e-15 -> 9.98e-16 | 2.42e-15 -> 2.00e-15 |
| BOTH_SIDES uncapped | 0.1 s | 463 -> 433 | 414/8 -> 414/8 | 1.40e-14 -> 3.83e-15 | 3.07e-15 -> 3.34e-15 |

The pass/fail scan of `EXTREME_TOPOLOGY_TESTS.md` 4.4 (`D12Scan.java`: grid columns and pairs 1-10 at full spread, spreads 0.01-0.75 at ten, 60 cases x 2 slices): base `7051391` fails the first interval in 71 of 120 runs; D12 integrates all 120 runs over 40 x 5 s and 400 x 0.1 s, no generator receipt, worst component ledger 2.42e-14 (`logs/06-scan-*.txt`).

### 5. Where the new path runs in the existing suites (bitwise)

Temporary print in `closeColdReceivingGenerators` over the harness science, runtime, adjacent and regression runs (`src/sites-instrumentation.patch`, `logs/04-sites.txt`): it closes something only in `ExtremeTopologyIslandTest` (18 cold rate seeds) and in 2 of the 32 `MixedGasJunctionStaticTest` cases; it runs and closes nothing in the other 30 static cases, `PipePresentationTest` (6), `LiquidJunctionTransientTest` (4), `PhasePortClosureTest` (3) and `PhysicalRegistryTest` (1), and returns before the estimate everywhere else (no generator, an actuator, a filter or solids, or no generator run onto a junction: the mixed-gas transients have tanks and voids only; the pumped cold start has a pump; chain-100 has no junction). Where it closes nothing it changes no state, so those runs are bitwise:

- the 33 MIXED_GAS/LIQUID_JUNCTION lines of `harness.sh runtime` identical to the reference (the LIQUID_JUNCTION generator cases run the estimate and close nothing);
- the WP1/D9 bitwise probe (`tools/phase-ports-probes/d9/src/BitwiseProbe.java`: chain-100, 14 all-BULK scenarios, 8 gas phase-port scenarios, 2 liquid-port scenarios) on `7051391` and on D12: `chain-100.json`, `scenarios.txt`, `gas-ports.txt`, `liquid-ports.txt` byte-identical (`logs/07-bitwise-probe-sha256.txt`);
- the 32 static cases, every result double printed (`D12StaticCompare.java`, `logs/08-static-*.txt`): 30 byte-identical; the two that close (swap=false and swap=true, unequal, 150 kPa, 3 ports: the 145 kPa generator below the junction at about 148-149 kPa) end in the same modes (the low generator CLOSED) with flows equal to 1.6e-11 kg/s and junction pressures to 1.3e-7 Pa, in 2 Newton solves instead of 3 and 5 (the second: 1 accepted / 0 rejected instead of 2 / 1). The test's assertions pass unchanged.

Cost: the estimate took 20.7 / 8.8 / 16.4 ms on the GRID / ALTERNATING / BOTH_SIDES cold seeds (first call in a fresh JVM) and 0.07-0.09 ms on the static cases' 4-7 node islands; it runs once per cold structure with a generator run onto a junction.

### 6. Gates (harness; Gradle not run)

JDK OpenJDK 21.0.10 (container). Log `tools/phase-ports-probes/d12/logs/01-harness-all-d12.log`.

| # | command | result |
|---|---|---|
| H1 | `REPO=<worktree> LIB=<jars> bash tools/cloud-science-harness/harness.sh all` on the final tree | science 213/213, runtime 237/237 (incl. the 10 `ExtremeTopologyIslandTest` cases, the flagged test now running every oracle) with **the 33 junction lines identical to the reference**, adjacent 38/38, regression 1/1 with **chain-100 0.000e+00** |
| H2 | WP1/D9 bitwise probe, base against D12 | 4 outputs byte-identical |
| H3 | `ExtremeTopologyIslandTest` with `FULL_CASES_OPEN_DEFECT = true` on the D12 tree | the reproduction fails as designed (3 of 10: "it now integrates 40 intervals") |

**Not run: the Gradle gates (G1 fluid suites and junction lines, G2 exact regression, G3 38 adjacent, G4 GameTest and mcpCompat compile). They must be run after the merge.** No GameTest or in-game scenario (no runtime path changed).

### 7. Decisions recorded

`DECISION_LOG.md`, "D12 defaults": A33 (the closure belongs to the cold rate seed, not the step), A34 (islands with an actuator, a filter or solids are left to the pass loop), A35 (the closure margin is the reopen band), A36 (the estimate's law and when it reseeds the junctions).

### 8. For the merge

- Touched: `PassiveStepSolver.solve` (one added line after `closeIllegalStarts`) and new private members inserted between `closeIllegalStarts` and `illegalWithEitherDensity` (`closeColdReceivingGenerators`, `oneWayPressures`, `maximum`, `oneWayResult`, `OneWay`, `solveBanded`); the test file's javadoc and flag. No change in `ConservativeTransport`, the reconstruction booking, `PassiveIntervalSolver` or the step path.
- D11 (priority streams) removes the availability mask: `closeColdReceivingGenerators` and `oneWayPressures` read `closedPorts` only through `boundaryAllowed`, so they follow whatever `boundaryAllowed` becomes.
- `CHANGELOG.md` `[Unreleased]` is still empty on this branch; the batch's line is written at its close (WP6).

### 9. Commits and material

Code commit `418ca7e` (`WIP phase-ports D12: one-way generators from the cold start`, on `7051391`); tools commit after it (this line). Material: `tools/phase-ports-probes/d12/` (`src/` the drivers `D12Probe`, `D12Estimate`, `D12StaticCompare`, `D12Scan` with `run.sh`, the site-instrumentation and O1-variant patches; `logs/` the harness run, the class outputs on both trees, the scans, the static comparison, the bitwise-probe hashes, the rate-seed outputs). H1 was re-run on the committed tree `418ca7e` (`logs/01-harness-all-d12.log`): the same results.

## D13: polish on a fresh Jacobian before a gate refusal (2026-09-26)

- Author: Claude (Opus 5.5), in the separate worktree `scratchpad/wt-vent` (branch `claude/vent-gate-polish-wip`, from `99f5312` = the vent investigation plus the D13 decision entry; src identical to `fdc7571`), because the D11 agent edits `PassiveStepSolver`/`ConservativeTransport` in the main checkout and holds the Gradle lane. Code commit `4a71629` (`WIP phase-ports D13: polish on a fresh Jacobian before a gate refusal`); tools and this section: the following commit. Not merged, not pushed.
- Decision: D13 (owner, 2026-09-26), option A of the section "Vent gate defect".

### 1. What changed (every method touched)

| file | method | change |
|---|---|---|
| `science/fluid/network/PassiveStepSolver.java` | `solve(PassiveNetwork, double, Runnable, Acceptance, boolean)` | the gate lines only: the threshold becomes a local `gate` (1e-8 FULL, 1e-6 APPROXIMATE, as before); a refusal calls `polishRefusedPoint` and, if it returns a point, the step continues with that point's Newton result, variables, states, flows, projection and reconstructed encoding; a remaining refusal counts `equationGateRejections` and throws exactly the old message |
| same | `polishRefusedPoint(...)` (new, private) and `record Polished` (new, private) | one `SparseNewton.solve` from the refused converged point on a new, never retained `SparseNewton.Workspace` (a fresh Jacobian of this step's own dt and state) at `tolerance * 1e-2`; flows zeroed on closed edges as the accepted path does; refuses (returns null) on a Newton failure, a direction `boundaryAllowed` forbids, a broken velocity constraint, a `phaseCorrection` that would reseed, or a gate still above threshold; else returns the point |
| `science/fluid/diagnostics/SolverDiagnostics.java` | fields and `counters()` | `equationGateRejections`, `equationGatePolishes`, `equationGatePolishesAccepted` (appended after `verificationResiduals`; counters record only while `ENABLED`, and only named counters feed the junction lines) |
| `src/test/.../science/fluid/network/VentGatePolishTest.java` | new, 3 tests | the vented manometer (A fed at 104325 Pa, B filled through its LIQUID port and vented through its VAPOR port to a 1 atm void) at 5 s (8 slices) and 0.1 s (100 slices), and the BULK-vent control at 5 s; every slice whole, ledgers 1e-12 / 1e-10, no gate key in the rejection reasons, `equationGateRejections` 0, and at 5 s at least one accepted polish |
| `src/test/.../science/fluid/network/LevelHeadTest.java` | `manometer(PhasePort)` -> `manometer(PhasePort, boolean vented)`, its two calls in `cushionsHeldApartStandAtALevelDifferenceOfDpOverRhoG` | the head fixture vents B through its VAPOR port to a 1 atm void (the level-head review's fixture 3); the BULK control keeps B closed (a vent would hold B below A, and the control's point is that the headspaces equalise); javadoc restated; **no assertion changed** |

Nothing in the port stream code, the WP2 closure, the cold start or `ConservativeTransport` was touched. The polish applies to both acceptances (FULL gate 1e-8, APPROXIMATE gate 1e-6), as D13 states it.

### 2. Bitwise proof

Only a refused point reaches the new code and the fresh workspace is local, so no accepted step and no later solve's preconditioner changes. Measured:
- d9 `BitwiseProbe` (chain-100, the 14 all-BULK scenarios, the 8 gas-only phase-port islands, the liquid-port islands) on `99f5312` and on `4a71629`: `chain-100.json`, `scenarios.txt`, `gas-ports.txt`, `liquid-ports.txt` byte-identical, the same hashes as the d9 capture (`d9/results/probe/out-d9/`), `chain-100.json` identical to the regression reference (`tools/phase-ports-probes/vent/results/bitwise-probe/sha256.txt`).
- Cloud harness `all` on `4a71629` (`logs/13`, 115 s): science **216/216** (213 + the 3 of `VentGatePolishTest`), runtime **237/237** with the **33 junction lines identical** to the reference, adjacent **38/38**, chain-100 **0.000e+00** on state/moles, temperature, phase fraction and flow.

### 3. Where it fires (a traced copy of `4a71629`, `logs/14`)

| suite | polishes (all accepted) |
|---|---|
| science | 75: `LevelHeadTest` 55 (per fixture not attributed; the fixtures whose lines moved are the drain, its 0.1 s BULK control, the replay and the vented manometer), `VentGatePolishTest` 20 (13 VAPOR vent 5 s, 7 BULK vent 5 s, 0 at 0.1 s) |
| runtime | 13: `FilterBlockLineIslandTest` 9, `DeadHeadedLineIslandTest` 2, `ElevatedBlockLineIslandTest` 2 |
| adjacent | 11: `FilterBlockLineIslandTest` 9, `DeadHeadedLineIslandTest` 2 |
| regression | 0 |

Every polish passed the gate (99 of 99); no gate refusal is left in any suite. Printed counters that moved (`logs/15`; assertions all unchanged): `LevelHeadTest` drain 5 s 168/21 -> 152/3 (18 gate rejections -> 0; Newton solves 190 -> 159, iterations 661 -> 379), replay 48/12 -> 36/0, the drain's 0.1 s BULK control output 1.67e-6 -> 8.8e-7 kg; the manometer line is new (vented): P_B 101324.99999 Pa, dP 3000.00003 Pa, level difference 0.3071415 m against dP/(rho g) 0.3071399 m (5e-6 relative), bottoms level to -1.0e-7 Pa, 187 accepted / 2 rejected (1 reopen, 1 Newton iteration limit, recovered); `VENT_GATE_RUN`: VAPOR vent 5 s 30/1 (the reopen), 13 polishes; 0.1 s 100/1, none; BULK vent 5 s 32/1, 7 polishes (before D13: the VAPOR vent failed slice 1 on the substep limit; the BULK vent refused one substep per slice).

### 4. Gates not run here

No Gradle run: the Gradle lane belongs to the D11 agent. The Gradle gates of record (fluid suites with the 33 junction lines against `d10/logs/02-junction-lines-d10.txt`, exact regression, the 38 adjacent, GameTest and mcpCompat compile) run after this branch merges into `claude/phase-ports-compressor`. The harness is a Minecraft-free stand-in and does not compile the GameTest and mcpCompat source sets; the only non-test main change besides `PassiveStepSolver` is three public counters in `SolverDiagnostics`, whose one GameTest reader (`FluidServerBenchmark`) iterates `names()`.

## D11+D12+D13 merge (2026-09-26)

- Author: Claude (Opus 5.5), on branch `claude/phase-ports-compressor` in `/home/user/CreateChemE` (D11 tools commit `be8e226`). Merge commits: `aaa6624` (`claude/cold-start-generators-wip`, D12: `418ca7e` + `bb16308`) and `334ca78` (`claude/vent-gate-polish-wip`, D13: `4a71629`, `38debce`, `ce6b09c`); tools and this section in the commit after them. Not merged to `main`, not pushed. Material: `tools/phase-ports-probes/merge/logs/`.

### 1. Reconciliation

- **D12 under D11 (`aaa6624`).** One Java conflict. D12's `closeColdReceivingGenerators` and `oneWayPressures` took WP2's `closedPorts` mask, which D11 removed. They now take none, and their edge test is D11's static `boundaryAllowed(graph,pipe,flow)`: `solve` calls `closeColdReceivingGenerators(graph,boundaryClosed,keep,checkpoint)` after `closeIllegalStarts`. D12's defaults A20-A23 collided with D11's A20-A32. They are renumbered A33-A36 in `DECISION_LOG.md` and at every reference in this review and the plan. Documents: every section and row from both sides, kept in order.
- **D13 under D11 (`334ca78`).** `polishRefusedPoint` came from the WP2 tree. It checked the WP2 mask in `boundaryAllowed`, capped every flow with `massFlowLimit` at the draw port, reconstructed without phase draws, and reused the refused point's heads. It never asked the other questions the pass loop asks before it accepts a point. Merged, the polished point is checked exactly as the D11 pass loop checks an accepted point, in the same order:
  1. A trace reactivation that is due refuses the polish. The check uses copies of the `promoted` flags, so no pass state moves.
  2. Flows and heads are recomputed from the polished variables, with closed edges zeroed.
  3. Static `boundaryAllowed`.
  4. No device mode change. The pump and valve transitions were factored out of the pass loop into `nextMode(...)` with the same arithmetic, so the pass loop and the polish read one statement of them.
  5. `segmentsMoved` against the segments the pass froze.
  6. The D11 velocity cap: `massFlowLimit` at a BULK end, `capLimit` at a phase port.
  7. `draws(flows,states)` and the reconstruction with those six-entry draws.
  8. `phaseCorrection`, then the gate.
  
  `Polished` now carries the polished flows, heads and draws, and the caller installs all of them. `PipeTransfer.sample`, the accepted modes' `velocityCap` and the retained last solve therefore read the polished point, not the refused one. `polishRefusedPoint` never ran on the D11 fixtures, and every step the gate accepts is untouched, so all D11 output is bitwise as before, apart from the four lines below.
- `LevelHeadTest` auto-merged: D11's restated `aTankAtTheVoidsPressureDrainsOnItsHeadAlone*` (A32) and D13's vented `manometer(PhasePort, boolean vented)`. Both hold, and no assertion changed in the merge.

### 2. D13 counters on the D11 fixtures (D11 open item 7)

Measured from the `LEVEL_HEAD_RUN` / `VENT_GATE_RUN` / `PHASE_PORT_PRIORITY_*` lines of the harness run (`logs/06`), and from a traced copy of `334ca78` that prints each polish entry, each acceptance and each gate refusal left standing, attributed to the @Test method (`logs/07`).

| fixture | D11 (`548a9bf`) | merged (`334ca78`) |
|---|---|---|
| head drain 5 s | 213 / 34, 11 equation gate | 203 / 22, **0** equation gate (9 state change, 5 Newton limit, 4 line-search stall, 4 phase-correction cycle); 4 polishes, 4 accepted; `lastSliceOut` 2.2e-12 -> 0.0 kg |
| head drain 0.1 s | 1500 / 0 | 1500 / 0; 1 polish (its BULK control), accepted; BULK control output 1.67e-6 -> 8.8e-7 kg (D13's own figure) |
| slow drain (replay) | 48 / 12, 12 equation gate | 36 / 0, **0**; 35 polishes, 35 accepted; the replay still bitwise |
| manometer | 182 / 3, 2 equation gate (B closed, the D9 form) | vented through B's VAPOR port (D13): 187 / 2, **0** equation gate (1 reopen, 1 Newton limit); 16 polishes, 16 accepted. The line is bit-identical to D13's own capture (`vent/logs/15`) |
| `PhasePortPriorityTest` (all 10) | no equation gate | no polish fires; every `PHASE_PORT_PRIORITY_*` line bit-identical to `d11/logs/06` |
| `VentGatePolishTest` | (not on D11) | VAPOR vent 5 s 30 / 1, 13 polishes; 0.1 s 100 / 1, none; BULK vent 5 s 32 / 1, 7 polishes; `gateRejections=0` in all three. Bit-identical to D13's capture |

Open item 7 is closed: no equation-gate refusal is left in any suite. Across `all`, the polish fired 100 times and was accepted every time: science 76 (`LevelHeadTest` 56, `VentGatePolishTest` 20), runtime 13, adjacent 11, regression 0. Polished residuals were 3.9e-15 to 5.2e-10 against the 1e-8 gate. D13 alone fired 99 times (`LevelHeadTest` 55). The extra firing is in the restated D11 drain.

### 3. Gates (on `334ca78`; logs `tools/phase-ports-probes/merge/logs/`)

| # | command (from `/home/user/CreateChemE`) | result |
|---|---|---|
| (a) | `LIB=<jars> OUT=<dir> REPO=/home/user/CreateChemE bash tools/cloud-science-harness/harness.sh all` (`01-harness-all-merge.log`, 103 s) | science 211/211 (D11's 208 + `VentGatePolishTest` 3), runtime 237/237 with the 33 junction lines identical to its reference, adjacent 38/38, chain-100 0.000e+00 |
| (b) | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (`02-gradle-fluid-suite-merge.log`, 79 s) | **446/446** in 100 classes, 0 skipped: every one of D11's 443 names plus `VentGatePolishTest`'s 3, none missing (`02-test-names-merge.txt`). The 33 junction lines (`02-junction-lines-merge.txt`) are identical to `d10/logs/02-junction-lines-d10.txt` apart from ms/bytes/allocatedMB. MIXED_GAS_COST 286 Newton solves (624 ms, 135.0 MB, single run). `ExtremeTopologyIslandTest` 10/10 with `FULL_CASES_OPEN_DEFECT = false`: the three full fixtures run 40/40 x 5 s and 400/400 x 0.1 s at the default 100 m/s cap and at the configuration's largest (100000 m/s), every oracle |
| (c) | `./gradlew --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` (`03-...`) | **0.000e+00** on state/moles, temperature, phase fraction and flow; 3 accepted / 0 rejected, 4 Newton solves, 29 iterations |
| (d) | the 38 adjacent (WP1 G3 command; `04-...`) | **38/38** in 7 classes |
| (e) | `./gradlew --no-configuration-cache compileFluidGameTestJava compileMcpCompatJava --rerun --console=plain` (`05-...`) | **BUILD SUCCESSFUL**, both tasks executed |

No gate failed. No assertion and no tolerance changed in either merge.

## WP3 + WP4: liquid-only pump, gas compressor, inlet-phase refusal (2026-09-26)

- Author: Claude (Opus 5.5), `/home/user/CreateChemE` (cloud container), branch `claude/phase-ports-compressor`, base `85c492f` (the D11+D12+D13 merge plus the D14 decision-log entry). Code commit `fc9574d` (`WIP phase-ports WP3+WP4: liquid-only pump, compressor, inlet-phase refusal`); tools and documents in the commit after it. Not merged, not pushed.
- Scope: plan 3.6 under D11 and plan 3.7 (decisions D1, D2, D4, D6, D7, D8), science only, plus the runtime touch points that must compile: `IslandCertificate.graphIdentity`, `PhysicalFluidTopology.Device`, `FluidCheckpointCodec`'s two exhaustive control switches, `TopologyCompiler.Kind`. The block, GUI, codec format, placement defaults and face-to-port compile are WP5's (section 12). Done together because WP3 alone leaves the 15 gas-pump tests failing until WP4 turns them into compressor tests.
- Not touched: the thermo package (D14), the D13 polish (`polishRefusedPoint` reads the new `nextMode` unchanged), D11's port draw, the D9 head, D12's cold-start closure (it already skips islands with any actuator, a compressor included: section 5).

### 1. What changed, per file

| file | change |
|---|---|
| `science/fluid/network/FlowControl.java` | `FlowControl` permits `Passive`, `Mover`, `PressureValve`; new sealed `Mover` (permits `Pump`, `Compressor`): `targetVolumeFlow()`, `efficiency()`, `riseLimit(suctionDensity, suctionPressure, referenceDensity)`, `power(massFlow, head, suctionDensity, suctionPressure)` (W, booked as heat), `refuses(InletPhase.Supply, refusedBefore)`, `deviceName()`. `Pump` implements it with the density-scaled limit and `q v_s head / eff` in the same operation order as before (bitwise), `VAPOUR_REFUSE` 0.02 / `VAPOUR_RESUME` 0.005. New `Compressor(targetVolumeFlow, maximumPressureRatio, efficiency)` (ratio > 1): limit `(r_max - 1) P_s`, power `max(0,q) (P_s/rho_s) ln(1 + max(0,head)/P_s) / eff` (`Math.log1p`), `CONDENSED_REFUSE` 0.01 / `CONDENSED_RESUME` 0.002, any solid population above `SlurryTransport.DEFAULT_TRACE_VOLUME_FRACTION` refuses. `Mode.INLET_WRONG_PHASE` appended (ordinal 9; endpoint modes persist as ordinals, the codec's range check reads `values()`). |
| `science/fluid/network/InletPhase.java` | new, public: `Supply` record (walk end node, its id, the port, vapour share by volume, condensed share by mass, largest solid volume fraction), `walk` (package-private), `supply(model, graph, edge, Q, duration)`, `decide(model, graph, committedModes, duration)`, `reason(mover, supply)` and `reason(model, graph, edge, duration)` (section 2). |
| `science/fluid/network/PassiveStepSolver.java` | Every pump site reads `FlowControl.Mover` (section 4). `riseLimit(mover, rho, P_s)`. The dead `work` accumulation of the pass loop removed (never read). New `sliceRefusals` / `refuseInlets(Set<Long>)` / `refusedInlets(...)`: a refused mover is `boundaryClosed` with mode `INLET_WRONG_PHASE` from the start of every solve of the slice, after the both-way-blocked closure (section 3). New `suctionCapped` in the initial mode (section 6). `edgeRows`' actuator switch: `case CLOSED, INLET_WRONG_PHASE -> flow`. `reopenable` counts a refused mover as not open. `checkApproximation` skips its pump feasibility test for a refused mover. The energy row books `mover.power(...)` with the first node's trial pressure. `boundaryAllowed` package-private (read by `InletPhase`). |
| `science/fluid/network/PassiveIntervalSolver.java` | `decideInlets` at the start of `solve` (before the solid event integrator's dispatch) and `solveApproximate`; `inletModes`/`inletKey`, restated by `replayStart` from the committed interval, advanced to each returned interval (`committedInlets`). |
| `science/fluid/network/ConservativeTransport.java` | Mover work: the pump's two expressions kept to the bit, the compressor's `dt * power(q, head, 1/v_s, P_s)` on the drawn stream's specific volume and the candidate first node's pressure. |
| `science/fluid/topology/TopologyCompiler.java` | `Kind.COMPRESSOR` appended (ordinal 7); `invalidPumps` holds a compressor to the pump's zero-storage cycle rule. |
| `runtime/fluid/PhysicalFluidTopology.java` | `Device`: `COMPRESSOR` requires a `Compressor` control; `actuator()` includes it (so it connects only along its facing axis and owns the outlet run); an invalid compressor gets a zero target like an invalid pump. |
| `runtime/fluid/IslandCertificate.java` | `graphIdentity` case `"compressor"` (target, ratio, efficiency); header unchanged (no existing digest changes). |
| `runtime/fluid/FluidCheckpointCodec.java` | Both control switches: a `Compressor` throws `IllegalArgumentException("Compressor controls are not persisted before checkpoint format 6")`; control tag 3 and the format bump are WP5's. No runtime path builds a compressor before WP5. |
| tests | new `InletPhaseTest` (6), `CompressorTest` (5), `TopologyCompilerTest.compressorOnlyBypass...`, `PhysicalFluidTopologyTest.aCompressorConnectsAlongItsAxisOnly...`; the migration of section 9. GameTest `FluidPumpStartupGameTests` edited (section 9). |

### 2. The supply walk and the measures (plan 3.6, "Inlet phase check")

- **Walk** (`InletPhase.walk`). From the device edge's first node. The device's own junction is crossed if it has exactly one other connection and that connection is passive; each following junction is crossed while it has two connections and no actuator touches it (the run `closeDeadHeads` walks; a filter edge is a passive link). The walk ends at the first node that is not such a junction:
  - a vessel, generator, void or port there supplies the stream of the last link's end (a generator or void as BULK);
  - a junction (a branch, a dead end, or one an actuator touches, e.g. a valve upstream) supplies its start-state mixture (its bulk);
  - a device drawing straight from a vessel walks nothing and its own first end supplies.
- **What a phase port supplies (A37, answers D11 open item 6).** The D11 priority draw of the device's target volume over the slice, `Q T`, on the committed state: the port's order, each phase up to its share of the vessel `V_phase / N` (`N` the vessel's phase-port ends that may carry outflow by the static rule, D11's capacity over the slice), the last held phase with the rest. So a dry bottom port reads gas, a bottom port holding less water than one slice of the target reads the gas it will break through to, and a condensate trace left after a drain does not read as liquid. With no target volume (`Q = 0`, or a rate solve with no slice) it is the leading phase (A28). The alternative (the leading phase always) reads a 1.5e-7 condensate trace as liquid and lets a pump suck gas for the whole next slice; not taken.
- **Measures** (`InletPhase.Supply`): pump `alpha_v = V_v / (V_v + V_l + V_w)` (fluid volumes; solids excluded, they move within the mobility rules, D1); compressor condensed share `(m_l + m_w + m_s) / m` and the largest population's volume over the supply's volume (fluid and solids). For a draw the volumes and masses are the drawn phase streams' (each liquid with its D11 solid share).
- **Reason** (`InletPhase.reason`, pure, for WP5 to show as is): `ERROR: pump inlet not liquid (vapour 34.0 % by volume, from node 12)`, `ERROR: compressor inlet not gas (condensed 2.5 % by mass, from node 12)`, `ERROR: compressor inlet not gas (solids 4.0e-07 by volume, from node 3)`; `node` is the id of the node the walk ended at (WP5 maps it to a device name). Measured texts: section 7.

### 3. The mode and where it is decided (decision D6, plan 3.6 "The error behaviour")

- **Decision** (`PassiveIntervalSolver.decideInlets`), once per slice, at the start of `solve`/`solveApproximate` (before the solid event integrator's dispatch, so every segment of a slice shares it), on the slice's input graph (the committed state) and the committed endpoint modes: `refused = mover.refuses(supply over the slice duration, committedMode == INLET_WRONG_PHASE)`; pump: `alpha > 0.02 || before && !(alpha < 0.005)`; compressor: solids above the trace, or condensed `> 0.01 || before && !(c < 0.002)`.
- **The committed endpoint mode**: `replayStart` restates it from the committed interval (null for none or another structure); a returned interval advances it. A job that solves several trials from one committed state (the module planner) decides the same each time: the rule is idempotent on one state (`f(a, f(a, r)) = f(a, r)`). The hysteresis is never solver-local, so a certified island woken with a fresh solver decides as the island that solved every interval (fixture 8).
- **In the solver**: the step solver holds the slice's refusals (`refuseInlets`, keyed by pipe id, so the solid event integrator's re-masked pipes keep them) for every step, the cold rate seed and the start-of-step guard's rate solve. A refused mover is `boundaryClosed` with mode `INLET_WRONG_PHASE` from the start of each solve (after the both-way-blocked closure, so it is reported even where a solid closure also blocks the pipe): its actuator row pins the flow to exactly zero, the pass loop never reopens it (`boundaryClosed` skips `nextMode`, so no CLOSED-to-HEAD_LIMIT offer), the carry never restates it (only HEAD_LIMIT and CLOSED are carried), nothing reaches its discharge through it (`reachableComponents`), and it keeps the mode in the accepted and endpoint modes. It is exactly the existing closed-device path: a refused pump's island equals, double for double, the island with a compressor closed both ways by `blockedDirections = 3` in its place (measured, section 7 fixture 4). The rest of the island integrates.
- **A step solver no interval drives** (a bare `PassiveStepSolver.solve`, as `VelocityClampTest` uses it) decides at each solve on its input states with its carried modes, the step as the slice (a rate solve: the leading phase).

### 4. Suction-stream quantities everywhere (D4), site by site

Every mover quantity reads the first (suction) end only; no discharge density or pressure enters any of them:

| site | reads |
|---|---|
| initial mode (`solve`) | `endVelocityLimit(first state, firstPort)` (the suction end's leading stream); `suctionCapped`: `suctionMassFlow(Q, first state, firstPort)` against each walk link's cap on its donor end |
| carry offer | `endDensity(first state, firstPort)` for the column and the limit; `P_s` = first node's pressure |
| pass loop `nextMode` (and the D13 polish through it) | `equations.drawn(i, 0, first node, q)` (the priority stream at a phase port): density for the target test `q/rho`, the limit, `demand`'s column; its velocity limit; `P_s` = first node's converged pressure |
| approximation probe | `endDensity(corrected first state, firstPort)`, its pressure |
| start point (target flow, head-limit start, reopen start) | `suctionMassFlow`, `endDensity`/`endViscosity`/`massFlowLimit` on the first end, its pressure |
| `initialMassFlow` | `suctionMassFlow`, `massFlowLimit` on the first end |
| `edgeRows` | column `suction.density`; target row `q / suction.density`; head-limit row `riseLimit(suction.density, P_a)` at the trial |
| Newton energy row | `drawn(edge, 0, first node, q).density`, first node's trial pressure |
| reconstruction work (`ConservativeTransport`) | the drawn stream's specific volume at the first node (start-state phases for pinned ones), the candidate first node's pressure |
| `FluidWorldAuthority.java:294` (displayed pump limit) | the first node's bulk density (the pump's own junction, i.e. what arrives at its inlet); pump only; the compressor's display is WP5's |

`P_s` is the first node's pressure, not its port's driving pressure: the level head (D9) is nonzero only at a LIQUID port holding condensed mass, which a compressor's inlet check refuses (A41).

### 5. D12's cold-start closure and the compressor

`closeColdReceivingGenerators` returns the graph unchanged for an island with any non-passive connection (`!(control instanceof Passive)`, A34), so a compressor counts as a pump there without a change (the decision D12 asked for).

### 6. Target above what the suction can deliver (plan 3.6)

The initial-mode test now also starts a mover on PUMP_HEAD_LIMIT when its target mass flow on the start state `Q rho_s` exceeds the velocity cap of any link of its suction walk (each on the stream its donor end draws, `massFlowLimit`) or a walk link may not carry flow towards the device (`boundaryAllowed`). The D11 capacities are no limit here: a port's last phase is uncapped (A22), so a port never bounds a draw's total; a port that would run into another phase is the inlet check's (A42). Bitwise where it does not fire: no existing fixture's walk is capped (the 33 junction lines, chain-100 and every existing test unchanged, section 10).

### 7. Fixtures (measured; Gradle G1, identical to the harness run)

Every slice commits whole (FULL, no hold) and closes the component ledger to 1e-12 and the energy ledger (with the mover's work) to 1e-10.

| test | fixture | measured |
|---|---|---|
| `InletPhaseTest.theSupplyWalkReadsTheSourceThroughDegreeTwoJunctionsAndAPortsDraw` | vessel (0.5 m3 water under N2, 150 kPa) - J1 - J2 - pump - void; with a void branching off J1; a valve upstream; direct pumps on a bottom, top, dry bottom and short bottom port (4 L of water against a 5 L slice draw) | reaches the vessel (its bulk share to 1e-15); a branch ends the walk at J1 (liquid seed: 0); a valve ends it at the pump's junction; bottom 0, top 1, dry bottom 1 (A37: a dry bottom port reads gas), short bottom 0.2204 = `(0.005 - V_w)/0.005` (the draw breaks through) while its leading phase reads 0 |
| `InletPhaseTest.thePumpsThresholdsAndReason` | the decision rule on the measure | 1.99 % runs, 2.01 % refused; refused stays at 0.51 %, resumes at 0.49 %; text `ERROR: pump inlet not liquid (vapour 34.0 % by volume, from node 7)` |
| fixture 4, `refusedPumpsCarryNothingAndTheIslandsWaterLineIntegratesBitwise` | one graph: a 300 kPa water generator filling a N2 tank (10 m, 20 mm), a pump between N2 tanks at 200 kPa and 1 atm, a pump on a two-phase generator (water + N2, 30.0 % vapour by volume, 150 kPa) into a N2 tank; 6 x 5 s and 60 x 0.1 s | both pumps `INLET_WRONG_PHASE` every slice, flow exactly 0.0, work 0.0, the suction tank's nitrogen unchanged; reasons `vapour 100.0 % ... from node 3`, `vapour 30.0 % ... from node 5`. The water line is **bitwise** the water line of the same island with the nitrogen line's device a compressor closed both ways (mode CLOSED), at both cadences, every slice. Against the water line alone (no device lines): bitwise at 5 s (every double, 6 slices); at 0.1 s 40 of 60 slices differ in the last bits of the flow (worst 10 ulp, 1e-13 asserted), and so do the closed-compressor island's, identically (`results/fixture4-bitwise-variants-0.1s.txt`: refused = closed compressor bitwise; a passive both-way-blocked line = alone bitwise): a closed device's head unknown and its two rows change the Newton's roundoff, as they did before WP3 |
| fixture 4, `aSweptSupplyRefusesAboveTwoPercentAndResumesBelowHalfAPercent` | generator swept 0, 1, 1.9, 2.1, 3, 5, 3, 1, 0.6, 0.4, 0 % vapour (bisection to the share), 150 kPa, pump 0.5 L/s into a water/N2 tank, one 5 s job per step | refused `[F,F,F,T,T,T,T,T,T,F,F]`: first at 2.1 %, held at 1 % and 0.6 %, resumed first at 0.4 %; flow 0.0 exactly while refused, > 0 otherwise |
| fixture 5, `aPumpOnABottomPortRunsThenMeetsGasAndIsRefusedAtTheNextSlice` | 1 m3 N2 tank at 120 kPa holding exactly 2.99 slices of the target (29.9 L), LIQUID port, 5 m 25 mm, pump 2 L/s (500 kPa) into a void at 150 kPa; 8 x 5 s | modes `[PUMP_TARGET, PUMP_TARGET, PUMP_HEAD_LIMIT, INLET_WRONG_PHASE x5]`: 9.9603 kg in slices 1 and 2 (on target), 9.8601 kg in slice 3 (admitted: its draw over the slice is 1 % gas; the port's water runs out within it, the drawn stream turns to gas, its density and so the pump's density-scaled limit collapse, HEAD_LIMIT), 0 from slice 4 (the supply is gas); tank water 9.2e-10 kg at the end; 88 accepted, 4 rejected (state change), no hold |
| fixture 8, `aFreshSolverReplaysARefusedPumpBitwise` | vessel 97 % water / 3 % N2 at 1 atm, filled by a 400 kPa water generator (10 m, 10 mm), a pump from it to a void; 12 x 5 s | vapour share 2.87 % -> 0.754 %, in the band from slice 6 (1.81 %); refused every slice (hysteresis from slice 6); a fresh solver from the committed interval of slice 1 and of slice 6 reproduces every flow, pressure, temperature, inventory and energy bit for bit and the modes; a fresh solver from slice 6's graph *without* the committed interval admits the pump (the input the replay needs) |
| fixture 6, `CompressorTest.onItsTargetTheFlowIsTheSuctionVolumeFlowAndTheWorkTheIsothermalWork` | two 1 m3 N2 tanks at 1 atm, compressor 0.02 m3/s ratio 1.5, one 0.5 s step | PUMP_TARGET, `q = Q rho_s` to 1e-10 (0.0226803 kg/s at 1.13401 kg/m3), head 2871.51 Pa, work 28.310290721080722 J = the formula to the printed digit |
| fixture 6, `aTransferRunsOnTargetThenOnItsRatioLimitThenClosesAtShutoffAtEitherCadence` | the same transfer, 12 x 5 s and 600 x 0.1 s | 5 s: TARGET then CLOSED, suction 81582.46 Pa / 280.444 K, discharge 122373.68 Pa / 314.745 K, ratio 1.49999988, landing -0.010 Pa, work 3254.2 J; 0.1 s: TARGET, HEAD_LIMIT, CLOSED, 81521.23 Pa / 280.203 K, 122281.65 Pa / 314.535 K, landing -0.19 Pa, work 2871.3 J; both land within 3 Pa (the pump's landing tolerance), 5 s against 0.1 s 0.075 % in pressure and 0.24 K (the extreme-topology bounds, 2 % and 0.5 K); 39 and 600 accepted, 0 rejected. The 5 s run books 13 % more work: backward Euler states each step's work at its end-state head, which over the ramp is above the average (first order, D5; section 11 item 4) |
| heating (plan 3.7 hand check), `theDischargeOfARatioThreeCompressionOfNitrogenIsAboutNinetyKelvinWarmer` | N2 generator 298.15 K 1 atm - compressor 0.02 m3/s ratio 4 - junction - void at 3 atm; 8 x 5 s | head 202672 Pa (ratio 3.0002), work 97196.4 J/kg; the junction (the discharge at steady flow) at 391.62 K: **+93.47 K**, h_out - h_in = the work per kg to 1e-3 |
| fixture 7, `aCompressorOnLiquidOrTwoPhaseOrSolidsIsRefusedAndTheIslandIntegrates` | one graph: a half-water tank (150 kPa) with a compressor on its side (BULK), on its bottom (LIQUID) and on its top (VAPOR); a two-phase generator; a N2 tank carrying 1 g of demo particle; 4 x 5 s and 40 x 0.1 s | side, bottom, generator and dusty tank `INLET_WRONG_PHASE`, 0.0 flow; the top port's compressor admitted (its draw is the vapour) and running (1.62 / 1.67 g/s); texts `condensed 99.8 % by mass, from node 1`, `... 99.8 % ... node 2`, `solids 4.0e-07 by volume, from node 3`, `condensed 100.0 % ... node 1`; 12 / 40 accepted, 0 rejected; the dusty tank's pipe is also blocked by the solid event integrator (`NO_LIQUID_CARRIER`, one declared transition per slice), and the mode still reads `INLET_WRONG_PHASE` |
| `theCompressorsThresholds` | the rule on the measure | 0.99 % runs, 1.01 % refused; refused stays at 0.21 %, resumes at 0.19 %; solids 2e-8 refused, 1e-8 not; ratio 1 refused by the constructor |
| `TopologyCompilerTest.compressorOnlyBypass...`, `PhysicalFluidTopologyTest.aCompressorConnectsAlongItsAxisOnly...` | topology | compressor-only bypass rejected, tank-buffered recycle kept, a compressor-pump ring rejects both; a side pipe does not connect (one port: `ERROR`), a zero-storage bypass gives the compressor a zero target (ratio kept) while passive equalisation flows; a COMPRESSOR device refuses a pump's controls |

### 8. The compressor's rows and work (plan 3.7), and the hand check

- **Rows** (the pump's, unchanged in shape): target `(q / rho_s - Q) / 0.01`; head limit `(head - (r_max - 1) P_a) / pressureScale` with `P_a` the first node's trial pressure (its derivative enters through the block Jacobian's re-decoded first node, already a column of the actuator row); shutoff margin `riseLimit - demand` against the same band; carry offer and reopen start as the pump's. Velocity limit: the drawn stream's own (the gas's acoustic bound or the configured 100 m/s).
- **Work**: `W = max(0,q) (P_s/rho_s) ln(1 + max(0,head)/P_s) / eff` in the Newton's energy rows (receiver `stored` and junction `heat`) and in the reconstruction; `checkConservation` is unchanged and closes (every fixture 1e-10; `BackwardEulerStepTest` 1e-5 J).
- **Hand check of plan 3.7's estimate.** Ideal gas: `W/m = (R T / M) ln r`; heating the same gas, `dT = W / (m c_p) = T (R / (M c_p)) ln r = T ((gamma - 1)/gamma) ln r`. Nitrogen, `T = 298.15 K`, `gamma = 1.4`, `r = 3`: `298.15 x 0.2857 x 1.0986 = 93.6 K`; work `8.314 x 298.15 / 0.028013 x 1.0986 = 97.2 kJ/kg`. Ideal adiabatic: `298.15 (3^0.2857 - 1) = 110 K`. The pump form `q v_s head`: `v_s head = (RT/(MP)) 2P = 2RT/M`, `dT = 2 T (gamma - 1)/gamma = 170 K`. **Measured** (heating fixture): 97.196 kJ/kg and +93.47 K. The plan's 93 / 110 / 170 K hold.

### 9. Migrated and re-baselined tests (plan Appendix C.1 and C.5), old -> new

Old values measured on `85c492f` (scratch worktree, `tools/phase-ports-probes/wp3-wp4/results/migration-values-head-85c492f.txt`, `reports-old/`), new on `fc9574d`. Test method names are kept (the gate reads every existing name).

| test | class | change | old | new |
|---|---|---|---|---|
| `FlowControlTest.pumpMeetsItsSuctionFlowTarget...` | C | pump with the water setting scaled to 500 kPa on methane -> `Compressor(0.001, 3, 1)`; asserts the work formula too | TARGET, q 5.5917822e-4 kg/s, head 98704.6 Pa, work 9.870 J (`q v head`) | TARGET, same q, head 98703.8 Pa, work 6.891 J (isothermal, = formula to 1e-6) |
| `FlowControlTest.naturalFlowIsLimited...` | C | `Pump(0.001, 500 kPa)`, `Pump(0.001, 100 kPa)` -> `Compressor(0.001, 3, 1)` both | natural TARGET, head -198596 Pa, work 0; shutoff CLOSED, head 698675 Pa | identical doubles (natural, shutoff) |
| `NetworkRegimeTest.pumpShutoffUsesAddedPressure...` | C (adjacent) | scaled pump -> `Compressor(0.001, 1.5, 1)` (1.5 x 1 MPa = the 500 kPa bracket), recycle `Compressor(0.001, 1.5, 1)` | 1.49 MPa: TARGET, q 0.00961700339 kg/s; 1.51 MPa: CLOSED, 0; recycle HEAD_LIMIT, q 9.66e-4 / 4.36e-4, work 0.485 J | the bracket's doubles identical; recycle TARGET, q 1.443e-3 / 6.51e-4, work 1.740 J |
| `NetworkRegimeTest.cancellationCannotLeave...` (sonic part) | C (adjacent) | scaled pump -> `Compressor(1, 6, 1)` | PUMP_VELOCITY_LIMIT, q 0.00766011915 kg/s, head 500000 Pa | PUMP_VELOCITY_LIMIT, same q, head 506625 Pa (the ratio limit, 5 x 101325) |
| `PumpRiseScalingTest.aGasTransferBetweenClosedTanksClosesWithinASecond` | C, restated | `Pump(0.01, 500 kPa)` -> `Compressor(0.1, 1.05, 1)`; the 575 Pa scaled-limit assertion becomes the ratio limit (3 Pa, the old tolerance), discharge = r_max P_s (3 Pa), the held difference (0.05 Pa, as before), "a fraction of a kelvin" -> "a few kelvin" cooler, the discharge warmer, work > 0 | closed at 0.30 s, suction 297.909 K 101038.28 Pa, discharge 298.391 K 101612.02 Pa, rise 573.74 Pa against 574.97 | closed at 0.35 s, suction 296.069 K 98866.03 Pa, discharge 300.233 K 103809.29 Pa, rise 4943.253 against 4943.302 Pa, work 63.1 J |
| `PumpRiseScalingTest.aDomainRefusalIsItsOwnRejectionKey...` | C | same compressor; domain narrowed to 298.05 K | fails on Nitrogen < 298.05 K at node 1 | same (the suction cools past the floor sooner) |
| `BackwardEulerStepTest.pumpWorkIncludesAllStagesAndElevationEnergy` | C | scaled pump (eff 0.8) -> `Compressor(0.001, 3, 0.8)` | work 24.71 J, ledger exact | work 17.25 J, ledger to 1e-5 J (same assertion) |
| `SharedSourceDepletionQualificationTest.excessivePumpRequest...` | C | scaled pump -> `Compressor(target, 3, 0.8)` | P24: adequate 9.7530691e-5 kg/s, work 0.609 J; insufficient: 7 steps, 14.29 % left, 247.97 K, 30348 Pa | adequate same flow, work 0.497 J; insufficient: 6 steps, 15.51 % left, 255.10 K, 33903 Pa (both "bounded-feasible-transfer") |
| `TransientQualificationTest...` "pump against pressure" | C | `Pump(0.001, 500 kPa)` (575 Pa on N2: dead-headed, zero flow) -> `Compressor(0.001, 3, 0.8)` (runs against the 2 bar tank) | M3: mass error 0.0, pressure 2.9e-10, 0.0000001 K | M3: mass error 2.49e-4 (bound 0.075), pressure 2.4e-7, 4.2e-5 K: the case now transfers |
| `PhysicalFluidTopologyTest.pumpControlUsesItsOutletDirection...` | C (adjacent) | `Kind.PUMP` -> `Kind.COMPRESSOR` (`Compressor(1e-5, 3, 1)`) | flow > 0 both orientations | flow > 0 both orientations |
| `IslandCertificateTest.deadHeadedLinesCertifyRest...` ("dead-headed pump") | C | `Pump(0.001, 100 kPa)` -> `Compressor(0.001, 3, 1)` (304 kPa against 800 kPa); asserts the device CLOSED (not refused) | certified at tick 300, drift 0 | certified at tick 300, drift 0, mode CLOSED |
| `IslandCertificateTest.aPumpHeatingItsLoopDoesNotCertify` | C | `Pump(0.005, 500 kPa)` -> `Compressor(0.005, 3, 1)` | work 0.63389 J per interval, not certified | work 0.63381 J per interval, not certified |
| `IslandCertificateTest.anIslandThatNeverCertifies...` ("pumped loop") | C | same fixture | bitwise on/off | bitwise on/off |
| `FluidPumpedFillLineTest.aGasTransferClosesAtThePumpsScaledLimitAndCertifies` | C, restated | "RUPR" -> "RCPR", `Compressor(0.01, 1.01, 1)` (the lowest ratio of plan Appendix B, 1013 Pa at 1 atm, near the pump's 575 Pa); the scaled-limit assertion becomes discharge = 1.01 P_s within the old 3 Pa | [101039.1 Pa 297.91 K] [101611.6 Pa 298.39 K], 3 jobs, certified | [100823.3 Pa 297.73 K] [101828.7 Pa 298.57 K], 3 jobs, certified; landing -2.8 Pa (**inside the 3 Pa bound by 0.2 Pa**, section 11 item 5) |
| `FluidThermoDomainHoldTest.aReproducibleDomainFailureHolds...` | C | "RUPR" -> "RCPR", `Compressor(0.01, 1.05, 1)`, node name "compressor at 1, 64, 0" | HELD, Nitrogen 298.04999999999995 K in reservoir at 0, 64, 0 | the same status text |
| `VelocityClampTest.threePhaseClamping...` pump arm | R | asserts `INLET_WRONG_PHASE`, flow 0, work 0, supply share above 2 %; the passive and valve arms unchanged | PUMP_VELOCITY_LIMIT, q 0.00869550 kg/s, work 0.437 J (a pump moving 34.2 % vapour) | INLET_WRONG_PHASE, 0.0, 0.0 |
| `PumpJunctionStartupTest` crude half | B (adjacent) | one flashTP confirmed the crude two-phase at 298.15 K / 1 atm (**11.15 % vapour by volume**; bubble point **117.8 kPa**; WP0's estimate 1.2 atm); generator and receiver to 150 kPa, and asserted liquid | 1 atm, q 0.0765058 kg/s (a pump moving 11 % vapour) | 150 kPa, q 0.0860874 kg/s, TARGET; water half unchanged (q 9.96000062 kg/s, work 178.167 J, identical) |
| `FluidFallbackQualificationTest` "wet crude pump" | B | the pump's generator gives water (liquid at 350 K, 149 kPa) into the three-phase wet-crude tank; target 1e-4 -> 2e-6 m3/s: water is not the tank's composition and 15 s at 1e-4 m3/s (about 80 mol into about 300) leaves the anchor's 1 % composition trust region (measured: "Outside the last full solution's trust region" 153 times, then HELD) | M4: pressure 9.0e-12, 4.7e-10 K | M4: pressure 3.0e-7, 1.6e-5 K, PASS |
| GameTest `FluidPumpStartupGameTests.heatedCrudePumpStartsIntoNitrogenReservoir` | B (source edit, not run here) | generator 350 K 101325 Pa -> 350 K **1 MPa** (the crude is 76.4 % vapour by volume at 350 K / 1 atm, bubble point **226.0 kPa** by flash; WP0's estimate 0.3 MPa) | - | compiles (G4); to be run on the owner's lane |

Unchanged (U) by the migration and green: `PhysicalFluidTopologyTest.dimensionsDeadLegs...` and `.zeroStoragePumpBypass...`, `FilterBlockLineIslandTest`, `ElevatedBlockLineIslandTest`, `SolidRuntimeTest`, the five water `FluidPumpedFillLineTest` methods, `FluidCheckpointCodecTest`, `PhysicalRegistryTest`, `WorldTopologyLedgerTest`, `TopologyCompilerTest.pumpOnlyBypass...`, `LevelHeadTest`'s pump fixtures, and the water half of `PumpJunctionStartupTest` (identical doubles).

### 10. Gates

JDK OpenJDK 21.0.10 (container); `JAVA_TOOL_OPTIONS` as set; one Gradle invocation at a time; no dev client. Logs `tools/phase-ports-probes/wp3-wp4/logs/`.

| # | command (from `/home/user/CreateChemE`) | result |
|---|---|---|
| (a) | `LIB=<jars> OUT=<dir> REPO=/home/user/CreateChemE bash tools/cloud-science-harness/harness.sh all` (`01-harness-all-wp34.log`) | science 223/223 (211 + 12 new), runtime 238/238 (237 + 1) with **the 33 junction lines identical** to its reference, adjacent 39/39 (38 + the new `PhysicalFluidTopologyTest` method, which the adjacent patterns select), chain-100 0.000e+00. No exclusion-list change (the new tests need no Minecraft class) and no stub change (`network.FluidNetwork` is a compile-only stand-in there) |
| G1 | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (`02-...`, 79 s) | **459/459** in 102 classes, 0 skipped: every one of the 446 names at `85c492f` plus 13 new, none missing (`02-test-names-wp34.txt`); the 33 junction lines (`02-junction-lines-wp34.txt`) identical to `d10/logs/02-junction-lines-d10.txt` apart from ms/bytes/allocatedMB; **MIXED_GAS_COST 286** Newton solves (569 ms, 135.1 MB, single run) |
| G2 | `./gradlew --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` (`03-...`) | **0.000e+00** on state/moles, temperature, phase fraction and flow; 3 accepted / 0 rejected, 4 Newton solves, 29 iterations |
| G3 | the adjacent selection (WP1 G3 command; `04-...`) | **39/39** in 7 classes: the 38 plus `PhysicalFluidTopologyTest.aCompressorConnectsAlongItsAxisOnly...`; the moves in it are `NetworkRegimeTest` (two C migrations), `PumpJunctionStartupTest` (crude re-baseline) and `PhysicalFluidTopologyTest` (orientation test on a compressor), section 9 |
| G3' | `./gradlew ... test --rerun --tests com.wormzjl.createcheme.runtime.Fluid*` (`07-...`; these classes are in `fluidRuntimeTest` but not in G1's selection) | 17/17 in 4 classes (`FluidPumpedFillLineTest`, `FluidThermoDomainHoldTest`, `FluidFallbackQualificationTest`, `FluidIslandCommandTest`) |
| G4 | `./gradlew --no-configuration-cache compileFluidGameTestJava compileMcpCompatJava --rerun --console=plain` (`05-...`) | **BUILD SUCCESSFUL**, both tasks executed (the edited crude GameTest compiles) |

Fixture lines of G1: `06-fixture-lines-wp34.txt`. The harness runs of the migrated classes and of the new fixtures while they were written: `08-*`, `09-*`.

### 11. Decisions recorded, findings, options

`DECISION_LOG.md` "WP3/WP4 defaults": A37-A45. Findings and options for the owner:

1. **A37 (the port supply at the target over the slice)** answers D11 open item 6 by the draw, not the leading phase. It refuses a pump whose bottom port holds less liquid than 98 % of one slice of its target, leaving up to that much liquid in the tank (fixture 5 at 2.99 slices drains to 9e-10 kg only because the third slice's draw was 99 % water). Options: (a) keep; (b) the leading phase (drains to the last drop, then sucks gas for a slice; a condensate trace reads liquid); (c) the draw over a fixed reference time instead of the slice.
2. **The refusal uses the slice duration** in the draw, so a 0.1 s cadence and a 5 s cadence decide on different draws near the end of a tank (both correct for their slice); the GUI's reason (WP5) must pass the committed interval's `advancedSeconds()` to reproduce the solver's text.
3. **Fixture 4, "bit for bit as without the refused line":** bitwise against the closed-device island at both cadences and against the line alone at 5 s; at 0.1 s the line alone differs by up to 10 ulp because a closed device's head unknown enters the same Newton, exactly as a pre-existing closed compressor does. Options: (a) accept (the refusal adds nothing of its own); (b) drop a refused or closed device's head unknown and rows from the Newton (a structural change to the closed-device path, not bitwise for existing closed devices).
4. **Compressor work at long steps**: the 5 s transfer books 3254 J against 2871 J at 0.1 s (+13 %): backward Euler states each step's power at its end-state head, above the ramp's average. The ledger is exact either way (the booked work is the energy the discharge receives); the discharge ends 0.21 K warmer. The pump's `q v head` has the same first-order bias. Options: (a) accept (D5); (b) book the work at the mean of the start and end heads (not first-law inconsistent, but a change to the pump's booking too).
5. **The RCPR landing is 2.8 Pa against a 3 Pa bound** (the pump's old tolerance, kept): the mover closes on the first step whose head-limit pass lands inside the band and the closed re-solve returns to that step's start state (the pump's 8.8 (c) fixed point); at 5 s slices the landing is the start state's margin. Stable on this JDK; a different JDK's roundoff moves it by far less than 0.2 Pa. Options: accept, or state the bound relative to the rise.
6. **The two C tests that were trivially dead-headed** (`TransientQualificationTest` "pump against pressure": the pump's 575 Pa could not open against 2 bar) now transfer (ratio 3): a stronger test, recorded as the migration's choice; `r_max = 1.5` would keep the dead-headed case.
7. **FluidFallbackQualificationTest "wet crude pump"** now pumps water at 2 mL/s into the wet-crude tank (section 9). A liquid crude at 350 K needs more than 226 kPa, above the case's 150 kPa tank, so the pump would no longer push against pressure; option: a separate case with a crude generator at 300 kPa.

### 12. Open items for WP5

- **Runtime sites** (compile-level only here): `FluidWorldAuthority.java:160` (placement control: `COMPRESSOR` would get `Passive` and the device constructor refuses it; plan Appendix B default `Compressor(0.05, 3, 1)`), `:294` (the displayed limit: pump only, the compressor's `(r_max - 1) P_s` to add; the `INLET_WRONG_PHASE` endpoint mode is appended to the status as a mode name today), `network/FluidNetwork.java:50-51` (`Controls.from`: a compressor falls to the defaults; the ratio field), `:211-221` (edit switch: no `COMPRESSOR` case), `FluidDeviceScreen` (`case PUMP` fields; a ratio field with the plan's 1.01-10 bounds), `FluidDeviceBlock.java:56` (axis-only connection for the compressor block), `ModBlocks` (no compressor block yet), `FluidPacketGameTests` (the block list).
- **Reason strings**: `InletPhase.reason(model, committedGraph, edge, committed.advancedSeconds())` for an edge whose endpoint mode is `INLET_WRONG_PHASE`; map the node id to the device's name.
- **Codec**: control tag 3 for `Compressor` (target, ratio, efficiency) in the registration and topology writers/readers and the JSON `decodeGraph` ("compressor"), with the format 5 -> 6 bump; the `INLET_WRONG_PHASE` ordinal (9) and `Kind.COMPRESSOR` (7) already round-trip by ordinal.
- The certificate digest has its compressor case; its header stays `-graph-identity-2`.

### 13. Material

`tools/phase-ports-probes/wp3-wp4/`: `src/CrudeFlashProbe.java` (the crude's vapour share and bubble point, `results/crude-flash.txt`), `src/Wp34Probe.java` (fixture 8 fill rates, `results/fixture8-fill-rates.txt`; fixture 4 against the line alone, a passive closed line and a closed compressor, `results/fixture4-bitwise-variants-0.1s.txt`), `src/MigrationValueProbe.java` (section 9's old/new values; the HEAD variant is the same file with `NEW?new FlowControl.Compressor(...):` removed, `sed -E 's/NEW\?new FlowControl\.Compressor\([^)]*\):/ /g'`), `results/` (values, the P24/M3/M4 reports old and new), `logs/` (gates 01-05 and 07, fixture lines 06, harness runs 08-09).

## WP5: runtime mapping - faces to ports, compressor block, format 6, GUI (2026-09-26)

- Author: Claude (Opus 5.5), `/home/user/CreateChemE` (cloud container), branch `claude/phase-ports-compressor`, base `5a375ea` (WP3+WP4 and their tools commit). Code commit `9744a45` (`WIP phase-ports WP5: faces to ports, compressor block, format 6, GUI`); tools and documents in the commit after it. Not merged, not pushed.
- Scope: plan section 4 (4.1-4.4) as amended by D11 and WP3/WP4 (the "WP5 items" of section "WP3 + WP4", 12), the three GameTests of plan section 6, the four physical-topology re-baselines of Appendix C.5 item 5, the level-head review's fixture 6.
- Science not touched, except one visibility change: `PassiveStepSolver.LEVEL_HEAD_HEIGHT` is `public` (was package-private) so the tank page and the re-baselined runtime tests state the D9 head with the solver's constant. The tank lines need no new result field: the committed result already exposes what each connection drew, phase by phase, in its pipe transfers (`PipeTransfer.sample` samples every drawn phase since D11), computed by the solver.

### 1. What changed, per file

| file | change |
|---|---|
| `runtime/fluid/PhysicalFluidTopology.java` | `compile` records, for every link with a RESERVOIR end, the face as the direction from the tank to its neighbour (`tankFaces`, keyed by link id); `port(device, face)` maps UP -> VAPOR, DOWN -> LIQUID, any horizontal face -> BULK, and every non-tank end to BULK; each run's first and last segment links give its two ends' ports, carried through the `fromEnd ? b : a` swap (`withPorts(fromEnd ? endPort : startPort, fromEnd ? startPort : endPort)`); the two halves of a doubly actuated run get their outer end's port and a BULK middle; the filter's positive-side remap rebuilds a pipe with its ports (it used the 7-argument constructor, which would have reset them to BULK). |
| `runtime/fluid/FluidWorldAuthority.java` | `place`: `COMPRESSOR -> Compressor(0.05, 3.0, 1)` (plan Appendix B). `view`: a mover's status line gains, when its endpoint mode is `INLET_WRONG_PHASE`, the typed reason of `InletPhase` with the walk's end node named as a device (`inletReason`, below), and otherwise its limit on the suction stream through `Mover.riseLimit` (pump: unchanged text and value; compressor: "(limit N Pa at this suction)" = `(r_max - 1) P_s`); a tank's view carries its outlet lines (`TankOutlets.of`). |
| `runtime/fluid/TankOutlets.java` | new: a tank's outlets from the committed graph and the last interval's result (section 3). |
| `runtime/fluid/FluidView.java` | new component `outlets` (a list of `Outlet(pipeId, port, massFlow, moleFlow, drawn, head)`, at most 6, validated), `withOutlets`; every existing constructor passes an empty list. |
| `network/FluidNetwork.java` | `PROTOCOL` fluid-6 -> **fluid-7**; `Controls` gains `maximumPressureRatio` as its last component (bounds 1.01-10, `MINIMUM/MAXIMUM/DEFAULT_PRESSURE_RATIO`; the 8- and 7-argument constructors pass the default 3.0); `Controls.from` reads a mover's target (pump or compressor) and a compressor's ratio; the edit switch builds `Compressor(volumeFlow, ratio, 1)` for a COMPRESSOR (efficiency 1, as the pump's edit sets it); `MenuData` refuses outlets on a device that is not a tank. |
| `client/gui/screens/inventory/FluidDeviceScreen.java` | COMPRESSOR case beside PUMP: "Suction flow (m3/s)" (`flow_input`) and "Max pressure ratio (out/in)" (`ratio_input`); `apply` sends the ratio; the compressor's rail shows last flow, pressure change and "Discharge / suction pressure" (the suction junction's pressure plus the head, over it: the quantity the ratio limits); a status containing "ERROR" or "HELD" is drawn in the warning colour (the pipe rail's existing rule, now on every device's status line); the tank rail lists its connections by face (section 3). |
| `runtime/fluid/FluidCheckpointCodec.java` | `VERSION` 5 -> **6**; control tag 3 for `Compressor` (target, ratio, efficiency) in the registration and topology writers and readers; two port bytes per pipe after its control; JSON graph: pipes require `firstPort`/`secondPort`, a `"compressor"` control reads `target`, `limit` (the ratio) and `efficiency`; javadoc. `FluidCheckpointStore`, `FluidSavedData`: javadoc "format 6". |
| `world/level/block/FluidDeviceBlock.java` | `face`: a COMPRESSOR connects along its facing axis only, like the pump (`:56`). |
| `registry/ModBlocks.java`, `ModItems.java`, `ModBlockEntities.java`, `CreateChemE.java` | `fluid_compressor` block (the `fluid` factory: metal, no occlusion, piston-proof), its block item, the device block entity's valid blocks, the creative tab entry after the pump. |
| resources | `blockstates/fluid_compressor.json` (the pump's six facings), `models/block/fluid_compressor.json` (parent `createcheme:block/fluid_pump`, textures overridden with vanilla `light_gray_concrete` body and particle and `oxidized_copper` motor: no mod texture exists, the pump itself uses vanilla textures), `models/item/fluid_compressor.json`, `loot_table/blocks/fluid_compressor.json`, the pickaxe and Create `non_movable` tags, lang (block name "Process Gas Compressor" and the GUI keys of section 3). |
| tests | `PhysicalFluidTopologyTest` +6 (section 7), new `TankOutletsTest` (2), `FluidCheckpointCodecTest` +3, `FluidPacketCodecTest` +2 (protocol assertion fluid-7, test name kept), `FluidCheckpointFormatTest` (format 6 expectation), `McpGameplayRegressionTest` (the archived capture predates ports: the test adds BULK ports as it already adds blocked masks and filters), the four re-baselines (section 6). GameTests: new `FluidPhasePortGameTests` (3), `FluidPacketGameTests` block list gains the compressor. |

The cloud harness needed no stub or exclusion-list change: its `FluidNetwork` and `FluidDeviceBlock` stand-ins only carry what `FluidWorldAuthority` names (`MenuData(view)`, `snapshot`, `deliver`; `kind()`), which did not change, and every new test is Minecraft-free except the two `FluidPacketCodecTest` methods, whose class is already excluded.

### 2. The face rule as compiled (plan 4.1, A1)

- Only a tank (RESERVOIR) end carries a port; generators, voids, junctions, actuator and filter nodes stay BULK whatever face they are reached by (asserted: a generator above a tank's top pipe, voids below and beside).
- The face is the direction from the tank to its neighbour on the link the compiler creates at `:60-61` (a boundary link, so an actuator or filter directly on a tank face takes the same rule: asserted for a pump on the DOWN face in both orientations and both identity orders, and a filter on the UP face in both orientations).
- A pipe's ports are its run's end ports, swapped with the ends when the run is stated from its end (an actuator driving into the tank from the run's second end): the pump facing UP into a tank's bottom compiles to `pump junction -> tank` with the LIQUID port at the second end, whether the tank's identity is lower (swap branch) or higher (no swap).
- Two tanks touching never connect (unchanged); a pipe block between two stacked tanks joins the upper tank's LIQUID port to the lower tank's VAPOR port.
- The rate-solve PORT copy keeps the ports (WP1 rule, `PassiveIntervalSolver.portRate`/`coldRateSeed` reuse `graph.pipes()`); the certificate digest carries them (section 5).

### 3. GUI (plan 4.3, engine-owned presentation)

Everything below is built in `FluidWorldAuthority.view()` from the island's cached committed snapshot (graph and last result) on the engine's presentation schedule and sent only to subscribed menus; no solver, no flash: `InletPhase.supply` and `TankOutlets` read stored phase amounts (masses, volumes, molecular weights) and the solver's pipe transfers. Nothing is computed in a packet handler or on a menu open (an open replays the last published snapshot, unchanged).

**Mover status.** `... / INLET_WRONG_PHASE / ERROR: pump inlet not liquid (vapour 100.0 % by volume, from reservoir at -1015848, -59, -3401253)` (measured, GameTest 2); the compressor's text `ERROR: compressor inlet not gas (condensed ... % by mass | solids ... by volume, from <device>)`. The reason is `InletPhase.reason` on the committed graph (the state the next slice decides on) with the last interval's `advancedSeconds()` as the slice, and its "node N" replaced by the device label the status lines already use (`deviceLabel`: "<kind> at x, y, z", "pipe junction N" for a compiled junction). A refused mover shows no limit. Otherwise: pump `(limit N Pa on this fluid)` (the same value as before WP5: its setting scaled by the suction junction's density), compressor `(limit N Pa at this suction)` (measured `FULL / CLOSED (limit 105318 Pa at this suction)` at a 52659 Pa suction, ratio 3). Warning colour for any status containing "ERROR" or "HELD".

**Compressor page.** Fields "Suction flow (m³/s)" (`flow_input`, existing) and "Max pressure ratio (out/in)" (`ratio_input`, new; 1.01-10: outside it the screen's own `Controls` construction fails and it shows its existing "Check numeric inputs ..." message; a hand-made packet is refused by the server with "The pressure ratio must lie between 1.01 and 10.0"); rail: temperature and pressure of its suction junction, "Last flow", "Pressure change" (the head), "Discharge / suction pressure" (new key `pressure_ratio`).

**Tank page, "Connections by face"** (rail, below volume and mass; hint on hover). One entry per connection with an end at the tank, two lines each:

| port | first line | second line (last interval) |
|---|---|---|
| VAPOR (UP) | "Top: gas outlet" | "drawing gas · N kg/h" or "drawing liquid (overflow) · N kg/h" |
| BULK (sides) | "Side: mixed outlet" | "drawing all phases together · N kg/h" |
| LIQUID (DOWN) | "Bottom: liquid outlet (head X kPa)" (the head when the tank holds condensed mass, else "Bottom: liquid outlet") | "drawing water · N kg/h", "drawing oil · N kg/h" or "drawing gas · N kg/h" |
| any, net inflow | as above | "receiving · N kg/h" |
| any, no flow | as above | "no flow" |

Flows follow the screen's mass/mole toggle (kg/h or kmol/h). "The phase drawn" is the phase that carried the most mass in what the connection moved out of the tank over the last interval (`TankOutlets.leading`, on the solver's pipe transfer: phase moles times molecular weights); the flows are the transfer's net out of the tank over `advancedSeconds` (equal to the result's mean mass flow with the tank end's sign, asserted to 1e-9); the head is `g m_c H / V` on the committed state (D9). The hint (`outlets.hint`) carries the D11 rule and the plan's wording: "Top outlets draw gas first, then the lighter and the heavier liquid. Bottom outlets draw the heaviest liquid first, then the lighter liquid, then gas. Side outlets draw all phases together. Inflow through any face is not distinguished." No player-facing string said "bulk withdrawal" before WP5 (grep of `src/main`, lang included), so nothing was renamed; the phrase exists only in this hint.

New lang keys: `block.createcheme.fluid_compressor`, `gui.createcheme.fluid.ratio_input`, `.pressure_ratio`, `.outlets`, `.outlets.hint`, `.outlet.top`, `.outlet.side`, `.outlet.bottom`, `.outlet.bottom_head`, `.outlet.drawing_gas`, `.outlet.drawing_overflow`, `.outlet.drawing_water`, `.outlet.drawing_oil`, `.outlet.drawing_all`, `.outlet.receiving`, `.outlet.idle`, `.outlet.more`.

### 4. Checkpoint format 6 and wire fluid-7 (plan 4.4)

- Core record `FluidFormat = 6`; any other format is refused with the existing "Fluid checkpoint format N cannot be read ... Create a fresh world for this development build." No migration, no absent-field default, no legacy test.
- Controls, in the world ledger's registration and in an island unit's topology: tag 0 passive, 1 pump (target, max added pressure, efficiency), 2 valve (target pressure), **3 compressor (target suction volume flow, max pressure ratio, efficiency)**, each `f64`; an unknown tag is refused ("unknown control" / "Unknown saved control").
- Island topology, per pipe: id `i64`, first and second node `varint`, section count and sections, the control, then **the first end's and the second end's `PhasePort` ordinal, one `u8` each** (0 BULK, 1 VAPOR, 2 LIQUID; out of range refused "unknown pipe port"). A port on a node that is not a tank is refused by the `PassiveNetwork` constructor. The new endpoint mode (`INLET_WRONG_PHASE`, ordinal 9) and `Kind.COMPRESSOR` (ordinal 7) persist through the existing ordinals (A43).
- JSON graph (archived captures): each pipe requires `"firstPort"` and `"secondPort"` (names); `"compressor"` reads `target`, `limit` (= the ratio) and `efficiency`.
- Wire: protocol `fluid-7`. `Controls` carries `maximumPressureRatio` (required: a payload without it deserialises to 0 and is refused); the live view carries `outlets` (required: a live payload without the list is refused).
- Measured round trips: an island with every port kind and a refused compressor after one interval (graph pipes, ports, controls, endpoint modes, flows, anchor; re-encoded byte for byte); a world-ledger compressor registration; the JSON shape with ports and a compressor, a pipe without ports refused, a LIQUID port on a generator end refused.

### 5. Certificate digest (A12, WP4): verified, no change

`IslandCertificate.graphIdentity` digests, per pipe, the control (`"compressor"`, target, ratio, efficiency, since WP4) and one integer per end for the port ordinal (since WP1) under the header `createcheme-certificate-graph-identity-2`. Compiled islands with a vertical tank link now digest non-BULK ports, so their certificates differ from a 0.6.x world's; no compatibility work (a stale certificate is discarded on load, A12).

### 6. Physical-topology re-baselines (Appendix C.5 item 5) and the moves classified

The four assertions now state the tank's **bottom-port** pressure, `P + g m_c H / V`, against the same expectation and tolerance (forced by D9 with A1: the line enters through the tank's DOWN face):

| assertion | old headspace (5a375ea) | new headspace | level head | new bottom `P + h` | expectation |
|---|---|---|---|---|---|
| `ElevatedBlockLineIslandTest` rising line (`:234`, was `:216`) | 360918.3081 Pa | 353969.4046 Pa | 6948.90 Pa (708.59 kg) | 360918.3068 Pa | 400000 - rho g 4 = 360918.3068 Pa, 1e-5 P |
| rising filter line (`:240`, was `:220`) | 360918.3787 Pa | 353969.7255 Pa | 6948.90 Pa | 360918.6286 Pa | same |
| rising pump line (`:251`, was `:229`) | 861062.9819 Pa | 852452.8285 Pa | 8610.21 Pa (878.0 kg) | 861063.0384 Pa | P + 500 kPa rho_s/rho_ref - rho g 4, 1e-4 (P + head) |
| `DeadHeadedLineIslandTest` raised generator (`:252`, was `:243`) | 360918.3081 Pa | 353969.4046 Pa | 6948.90 Pa | 360918.3068 Pa | 400000 - rho g 4, 1e-5 P |

The headspace rests 6.95 kPa (8.61 kPa pumped) lower: `LEVEL_HEAD_REVIEW.md` estimated about 7 kPa; the D9 review's code-built equivalent (charged at 150 kPa) measured -5.61 kPa. The lowered generator's rest (`DeadHeadedLineIslandTest`, not asserted on pressure) moves with it (353969.4046 Pa).

**Other moves (classified before touching anything).** A full diff of every printed line of the cloud harness on `5a375ea` against WP5 (`logs/00` against `logs/01`) shows, besides the four lines above, the new tests' lines, timings and one reordered `HashMap` print: the two **falling lines** (tank reached through its UP face, a VAPOR port, inflow only) land at 439081.69755 Pa instead of 439081.69316 Pa (`ElevatedBlockLineIslandTest`, +4.4 mPa, 1.0e-8 relative) and 140395.02218 Pa instead of 140395.02229 Pa (`DeadHeadedLineIslandTest` "falling plain", -0.11 mPa). Their assertions (1e-5 P) pass unchanged. Cause measured with a one-line probe (`src/up-face-bulk-probe.patch`, `-Dprobe.upBulk=true` compiles UP faces to BULK): both return to the old doubles exactly (`logs/04`). So it is the VAPOR port, not another WP5 change: at the tank end the static readers (column choice, the sign tests of the start closures) read the port's leading phase, the gas, where a BULK end read the bulk (A28, D11), and the rest the line lands on moves at the Newton's landing resolution. WP0's "inflow only, nothing changes" held for the outcome, not to the last digits. Not touched.

### 7. New unit fixtures (measured; Gradle G1)

| test | fixture | measured |
|---|---|---|
| `PhysicalFluidTopologyTest.everyFaceOfATankCompilesToItsPortAndOnlyTheTankEndCarriesOne` | a tank with a pipe on each of its six faces to a void (a generator above) | UP VAPOR, DOWN LIQUID, four BULK; every far end BULK; the island solves 0.05 s |
| `.aStackOfTwoTanksJoinsTheUpperTanksBottomToTheLowerTanksTop` | tank - pipe - tank vertically, both identity orders; two touching tanks | LIQUID at the upper end, VAPOR at the lower; touching tanks: no pipe |
| `.aPumpOnATanksBottomFaceTakesTheBottomPortInEitherOrientationAndIdentityOrder` | pump under a tank facing DOWN (suction) and UP (discharge), tank id below and above the pump's | suction run LIQUID at the tank; the discharge connection `pump junction -> tank` with LIQUID second port in both identity orders |
| `.aFilterOnATanksTopFaceKeepsTheTopPortInBothOrientations` | tank - filter (UP / DOWN) - pipe - void | VAPOR at the tank in both (the DOWN-facing filter's remapped pipe keeps it), filter edge BULK, the tank vents |
| `.aCompiledStackPassesTheUpperTanksWaterDownAndItsGasUp` | 0.3 m3 water under N2 at 1 atm over a N2 tank at 1 atm, one pipe block, 40 x 5 s | first slice: the bottom outlet draws water only (vapour and oil moles exactly 0), the lower top receives, tank lines "drawing water" / "receiving" with flows equal to the result's; every component conserved to 1e-10; end: all 292.43 kg of water in the lower tank, headspaces 104648.26 / 104671.22 Pa (one gas column apart). The water goes down and the gas up the same pipe (the lower tank's top outlet draws gas back into the upper tank's bottom, inflow undistinguished, D3), so a head-only stack of two tanks does not exist: the gas path opens first |
| `.aTankOverAVoidDrainsOnItsLevelHeadAloneAndRests` (level-head review fixture 6) | the same tank on a pipe block over a void held at `P + rho g 2 + h/2` = 122293.98 Pa (column 19535.01 Pa, head 2867.95 Pa), 40 x 5 s | the column alone cannot lift the headspace to the void; the head drains 9.73 kg (first slice 1.888 kg/s, water only); rest CLOSED with the tank still holding water (head 2772.57 Pa, headspace 99986.42 Pa) at `P + h + rho g 2 - P_void` = -2.2e-8 Pa |
| `TankOutletsTest.aTankWithTopSideAndBottomOutletsListsWhatEachDrew` | plan section 6 scenario 1 from blocks: 0.5 m3 water under N2 at 120 kPa, top / east / bottom pipes to 1 atm voids, one 5 s slice | top VAPOR 0.0175 kg/s drawing gas (no condensed moles), side BULK 0.597 kg/s (both phases), bottom LIQUID 17.94 kg/s drawing water (no vapour moles), head 3912.65 Pa = `g m_c H/V` exactly; flows = the result's mean flows to 1e-9; no interval: all "no flow" |
| `TankOutletsTest.theLeadingPhaseIsTheOneThatCarriedTheMostMass` | synthetic transfers | 0.028 kg gas + 0.18 kg water -> water; + 0.018 kg water -> gas; nothing -> none |
| `FluidCheckpointCodecTest` (3), `FluidPacketCodecTest` (2) | section 4 | as stated there; ratio 1.009, 10.01, NaN, 0 refused, 1.01/3/10 accepted |

### 8. GameTests (plan section 6; `FluidPhasePortGameTests`, run here)

`./gradlew --no-configuration-cache runFluidGameTestServer -PfluidGameTestRunId=wp5-20260926 --console=plain` (fresh world, `logs/06`) and, after adding the measurement prints, again with `-PfluidGameTestRunId=wp5-20260926-final` (fresh world, `logs/10`, summary `logs/12`): **All 33 required tests passed** both times (the 30 existing plus the 3 new; BUILD SUCCESSFUL, 41 s the second time). The NeoForge server runtime set up with the container's network; the only environment messages are the usual missing `server.properties` and the refused Yggdrasil key fetch (`api.minecraftservices.com` not on the allowlist), neither of which a GameTest needs.

| GameTest | measured (final run) |
|---|---|
| `aTanksTopSideAndBottomOutletsEachCarryTheirPhase` | tank filled from a 130 kPa water generator on its west side, generator removed, top/east/bottom outlets placed; first interval with flow: tank 124302.5 Pa, 176.1 kg; top VAPOR 0.273 kg/s drawing gas (pipe carries gas only), side BULK 8.95 kg/s (both phases), bottom LIQUID 29.41 kg/s drawing water (pipe carries water only), head 1715.3 Pa; the menu snapshot carries the same lines |
| `aPumpOnAGasTankIsRefusedWithItsReasonOnItsStatusLine` | `SOLVING / INLET_WRONG_PHASE / ERROR: pump inlet not liquid (vapour 100.0 % by volume, from reservoir at -1015848, -59, -3401253)`, flow 0, no limit shown, the menu snapshot carries it |
| `aCompressorMovesNitrogenToItsRatioLimitAndCloses` | default `Compressor(0.05, 3, 1)` placed; modes seen `PUMP_TARGET` then `CLOSED`; closed at suction 52659.0 Pa / 247.85 K, discharge 157976.3 Pa / 338.04 K, ratio 2.99999 (asserted within 3e-3); status `FULL / CLOSED (limit 105318 Pa at this suction)` |
| `FluidPacketGameTests.createAndVanillaMovementRejectEveryFluidBlock` | the compressor block is refused by Create's movement checks and by pistons (tags and `pushReaction` hold) |

The in-game MCP client check cannot run in this container (no display): it is the owner's item (section 11). Client-only resources (blockstate, block and item models, lang) are not parsed by a server; `build -x test` validated the resource pipeline (`processResources`, `jar`, `jarJar`, `verifyMaterialIndex`), and every new JSON file parses.

### 9. Gates

JDK OpenJDK 21.0.10 (container); `JAVA_TOOL_OPTIONS` as set; one Gradle invocation at a time; no dev client. Logs `tools/phase-ports-probes/wp5/logs/`.

| # | command (from `/home/user/CreateChemE`) | result |
|---|---|---|
| (pre) | `LIB=<jars> OUT=<dir> REPO=/home/user/CreateChemE bash tools/cloud-science-harness/harness.sh all` on `5a375ea` (`00-...`) | science 223/223, runtime 238/238 with the 33 junction lines identical, adjacent 39/39, chain-100 0.000e+00 |
| (a) | the same on the WP5 tree (`01-...`) | science 223/223, runtime **249/249** (238 + 11 new), adjacent 45/45, chain-100 0.000e+00, the 33 junction lines identical; no stub or exclusion change |
| G1 | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (`02-...`, 78 s) | **472/472** in 103 classes, 0 skipped: all 459 names of WP3/WP4 plus 13 new, none missing (`02-test-names-wp5.txt`), the four re-baselined assertions green; the 33 junction lines (`02-junction-lines-wp5.txt`) identical to `d10/logs/02-junction-lines-d10.txt` apart from ms/bytes/allocatedMB; **MIXED_GAS_COST 286**; the codec and packet classes of gate (2) (`FluidCheckpointCodecTest`, `FluidCheckpointFormatTest`, `FluidCheckpointStoreTest`, `FluidPacketCodecTest`, `FluidDeviceSpecDomainTest`) are in this selection and green |
| G1' | `./gradlew ... test --rerun --tests com.wormzjl.createcheme.runtime.Fluid*` (`05-...`) | 17/17 in 4 classes |
| G2 | `./gradlew --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` (`07-...`) | **0.000e+00** on state/moles, temperature, phase fraction and flow |
| G3 | the adjacent selection (WP1 G3 command; `08-...`) | **45/45** in 7 classes: the 39 plus the 6 new `PhysicalFluidTopologyTest` methods, which the adjacent patterns select |
| G4 | `./gradlew --no-configuration-cache compileFluidGameTestJava compileMcpCompatJava --rerun --console=plain` (`09-...`) | BUILD SUCCESSFUL, both tasks executed |
| G5 | the GameTest runs of section 8 (`06-...`, `10-...`, `12-...`) | **33/33** twice on fresh worlds |
| G6 | `./gradlew --no-configuration-cache build -x test --console=plain` (`11-...`) | BUILD SUCCESSFUL (`verifyMaterialIndex`, `processResources`, `jar`, `jarJar`, `sourcesJar`, `assemble`, `check`) |

Printed fixture lines of G1: `03-fixture-lines-wp5.txt`. The falling-line probe: `04-up-face-bulk-probe.txt`.

### 10. Decisions recorded, findings, options

`DECISION_LOG.md` "WP5 defaults": A46-A54. For the owner:

1. **What "drawing X" means** (A47): the phase that carried the most mass over the last interval. A top vent's overflow slice (fixture 3 of D11: 0.0047 kg gas with 7.83 kg water) reads "drawing liquid (overflow)". Alternatives: the first phase of the port's order that the draw contains (that slice would read "drawing gas"), or every drawn phase listed ("drawing gas and water"), which needs a second line or a longer one.
2. **The reason is stated on the committed state** (A48), which is the input of the next slice, not the state the last slice decided on (the snapshot does not keep the slice's start graph): its percentage can differ from the solver's own text by the slice's change; the refusal itself is the committed endpoint mode. Option: carry the refusal text in the interval result (a solver-side presentation field).
3. **Compressor efficiency** is reset to 1 by a GUI edit, as the pump's is (A50); a player cannot set it. Option: an efficiency field on both pages.
4. **A head-only stack of two tanks does not exist** (section 7): the lower tank's top outlet sends its gas up the same pipe the water comes down. Fixture 6 is therefore a tank over a void. The two-tank stack drains completely (water down, gas up), which is physical for a single connection (a bottle glugging), with no slug or counter-current limit in the model.
5. **Falling lines** move at the landing resolution (section 6); nothing to decide, recorded.

### 11. The owner's in-game check (plan section 6; langyo/minecraft-mod-mcp bridge, jar in `run/mods`, `.mcp.json` in the worktree root, fresh world)

Not runnable here (no display). On a fresh world:
1. **Tank outlets.** A tank filled with water from a generator (it is placed as a nitrogen charge), then a pipe on its top, one side and its bottom, each to a void: the tank page's "Connections by face" shows "Top: gas outlet / drawing gas", "Side: mixed outlet / drawing all phases together", "Bottom: liquid outlet (head X kPa) / drawing water", with flows; each pipe's inspection shows the phase ratio of what it carried (gas only at the top, water only at the bottom while water remains). Check the hint tooltip, the kg/h and kmol/h toggle, and that the compressor block and item render (light-grey body, oxidised-copper motor) with the name "Process Gas Compressor".
2. **Pump.** A pump on a tank's side facing away from it, into a pipe and a void: status in the warning colour, "INLET_WRONG_PHASE / ERROR: pump inlet not liquid (vapour 100.0 % by volume, from reservoir at x, y, z)". Move it to the tank's bottom face (the pump below the tank facing down) with water in the tank: it runs ("PUMP_TARGET (limit N Pa on this fluid)"), and when the water runs out it is refused with "vapour ... %" (D11: a dry bottom port feeds it gas).
3. **Compressor.** Two nitrogen tanks with a compressor between them facing the second: the page shows the two fields (0.05 m³/s, ratio 3); it runs on PUMP_TARGET, the rail's "Discharge / suction pressure" rises to 3.00 and it closes (CLOSED, "(limit N Pa at this suction)"); set the ratio to 1.5, then to 10.5 (not sent: "Check numeric inputs and composition; ..."). Then feed it water (a water generator on its suction side): refused, "ERROR: compressor inlet not gas (condensed ... % by mass, from ...)".

### 12. Open items for WP6

- The owner's in-game check (section 11) and the MCP screenshots; the Windows lane for the gates and GameTests.
- Options of section 10.
- WP6's own list: tooling cleanup per AGENTS.md (the probe patch here is a measurement tool, stored under `tools/phase-ports-probes/wp5/src/`, never committed to `src/`), `CHANGELOG.md` `[Unreleased]` line, INDEX rows, the D11 open items (humid venting below the water domain D14, recovered rejections at phase boundaries, WP1 condensed-stream builders no longer read), WP3/WP4 items 1-7.

### 13. Material

`tools/phase-ports-probes/wp5/`: `src/up-face-bulk-probe.patch` (against `9744a45`: `-Dprobe.upBulk=true` compiles UP faces to BULK; apply with `git apply`, run the harness `select` on `ElevatedBlockLineIslandTest` and `DeadHeadedLineIslandTest` with `HARNESS_JVM_OPTS="-Xmx2g -Dprobe.upBulk=true"`, `git checkout` the file; never commit it), `logs/` (gates `00`-`02`, `05`, `07`-`11`; fixture lines `03`; probe `04`; GameTest runs `06`, `10` and summary `12`).

## WP6: gates and close (2026-09-26)

- Author: Claude (Opus 5.5), `/home/user/CreateChemE` (cloud container, OpenJDK 21.0.10), branch `claude/phase-ports-compressor`, base `5f38474` (WP5 and its tools commit), tree clean at the start. Code commit `cb41860` (`WIP phase-ports WP6: cleanup and final gates`); tools, documents, `CHANGELOG.md` and this section in the commit after it (`Docs: phase-ports batch close, changelog entry, handoff`). Not merged, not pushed; `mod_version` not bumped (merge-time, the owner's).
- Scope: plan section 7 WP6 and section 6 "Cleanup rule"; AGENTS.md "Test classes and tools after a batch". Science not touched except the removal of code proven unreferenced ((b)); no tolerance and no assertion changed.
- Section order of this review: the sections are in commit order (D10 `07e7426` was committed before D9 `fdf3574`, the merge of D11, D12 and D13 before WP3), which is why D10 precedes D9; left as it is.

### (a) Prototype residue (tooling rule)

Checked `src/main`, `src/test`, `src/fluidGameTest`, `src/mcpCompat` (the batch's diff `c32acac..5f38474`, 67 files), `build.gradle`, `gradle.properties`:

- `git diff c32acac HEAD -- build.gradle gradle.properties` is **empty**: no Gradle switch or property served the batch's probes (every probe ran through the cloud harness or a scratch copy of the tree).
- Added lines of the batch under `src/main`: no `System.getProperty`, `Boolean.getBoolean`, `Integer.getInteger`, `System.getenv`, `System.out`/`System.err` or trace switch (the grep's only hits are `System.arraycopy` and the word "trace" in the D10 water trace and the solid trace fraction). Every instrumentation of the batch (the gate/Newton probe, the pass tracer, the site prints, the mutations, the up-face probe) stayed a patch in `tools/phase-ports-probes/` and was never committed.
- Test and GameTest prints: the new gate tests print one summary line per fixture (`PHASE_PORT_*`, `LEVEL_HEAD_*`, `JUNCTION_WATER_TRACE`, `VENT_GATE_RUN`, `INLET_PHASE_RUN`, `COMPRESSOR_RUN`, `EXTREME_TOPOLOGY*`, `TANK_OUTLET`, `STACK_*`), and `FluidPhasePortGameTests` prints one `PHASE_PORT_GAMETEST` line per device. **Kept**: they are the measured output of tests a gate runs (the convention of `MIXED_GAS_*` and `LIQUID_JUNCTION`), read by this review's tables; they are unconditional and switch nothing.
- **`ExtremeTopologyIslandTest.FULL_CASES_OPEN_DEFECT`** (false since D12, `418ca7e`): the reproduction branch it guarded was dead code. **Detached** (the recommended option): the flag, the branch (which asserted that the three full fixtures hold on their first interval in pass 0) and `capDrop` (used only by it) left the class; the test keeps its success branch, every oracle on the full fixtures at the default cap, and is renamed from `theFullCasesHoldOnTheirFirstIntervalOpenDefect` to `theFullCasesIntegrateAtTheDefaultCap` (the JUnit XML names of its three parameterised cases, `[1] GRID`, `[2] ALTERNATING`, `[3] ALTERNATING_BOTH_SIDES`, do not carry the method name, so the gate's test-name list is unchanged, (d)); the class and method javadoc keep the defect's history and point to the tools folder. The file as of `5f38474` and `reattach-open-defect-reproduction-against-cb41860.patch` are in `tools/phase-ports-probes/extreme/src/`.
- Nothing else of the kind: no other test flag, no `@Disabled`, no probe class in a tracked path (grep for `Probe`, `Trace`, `OPEN_DEFECT`, `@Disabled` in the batch's test files).

### (b) Dead code and stale text

| Item | Action |
|---|---|
| WP1 condensed-stream builders in `FluidThermodynamics` (D11 section 12 item 8): `liquidStream`, `liquidViscosity`, `liquidCarrierViscosity`, `liquidSpecificEnthalpy`, `liquidVelocityLimit` | **removed** (56 lines removed, 19 added: the javadoc restated): no reader in `src/main`, `src/test`, `src/fluidGameTest`, `src/mcpCompat` or `tools/cloud-science-harness/stubs` (each name grepped); the only other reader is the WP1 instrumented copy `tools/phase-ports-probes/wp1/src/instr/PassiveStepSolver.java`, which builds against the WP1 tree only. `liquidMass`, `liquidStreamVolume`, `liquidDensity`, `holdsLiquid` stay (the level head, `LevelHeadTest` and `PhasePortTest` read them). `PhaseStream`'s javadoc and the D11 phase block comment restated to the per-phase streams. Removed source and `reattach-condensed-stream-builders-against-cb41860.patch`: `tools/phase-ports-probes/wp6/removed/` |
| `PassiveStepSolver`: the capacity javadoc's "(WP2's throttle's `dt`)"; the structural-zero comment "Only for a bulk withdrawal"; `riseLimit`'s javadoc describing the pump only | corrected ("the solve's step"; "Only for a BULK end"; the mover's limit, a compressor's `(r_max - 1) P_s`, then the pump's text) |
| `InletPhase`: "a view (WP5) formats", "for WP5's view to show" (future tense after WP5) | restated to the device view (`FluidWorldAuthority`) that shows it |
| `PhasePortTest.phasePortsFeedJunctionsAndCloseTheLedger` javadoc: the dry-seeded junction defect "reported ... (WP1, open defect)" | restated: fixed by D10 (`JunctionWaterTraceTest`), the humid seeding kept as written |
| `LevelHeadTest` class javadoc: fixture 6 "waits for WP5's face-to-port compile" | points to `PhysicalFluidTopologyTest`'s tank over a void |
| grep of every source set for `INLET_EMPTY`, "bulk withdrawal", availability mask, `closedPorts`, `PHASE_PORT_OPEN`/`RESERVE`, `phi_open`, throttle-as-landing, "port closes", A9 | nothing else stale: `ScheduledTransfer`'s "Bulk withdrawal" is the module withdrawal (A6, still bulk); the "throttled" law in `edgeRows` is the velocity cap (pre-existing); test javadoc that names WP2's closure as superseded history (`PhasePortPriorityTest`, `LevelHeadTest` drain) is history and stays |
| `SolverDiagnostics` counters added by D13 | all three recorded by main code; nothing removed |

Main sources: 3 files; test sources: 3 files (`ExtremeTopologyIslandTest`, `PhasePortTest`, `LevelHeadTest`), comments and the detachment only.

### (c) Tool folders holding the batch's detached material

- `tools/phase-ports-probes/` (one subfolder per package: `wp1`, `wp2`, `d10`, `d9`, `extreme`, `vent`, `d11`, `d12`, `merge`, `wp3-wp4`, `wp5`, `wp6`). Every subfolder's README section states its purpose, the package it served, how to run it and, for code removed from a tracked path, the removing commit and the reattach patch (`extreme/`, `wp6/removed/`; WP2's closure removed by D11 `548a9bf` and TR-BDF2-era material are in git history). Added at WP6 from the session scratchpad: `wp1/src/xmlsummary.py` (the G1 summary every package used) and `cmp.py`, `wp2/src/scripts/` (WP2's summary and edit scripts), `d10/src/edit-d10.py`, `d9/src/run-base.sh`/`run-new.sh`, `d11/src/sel.sh` and `d11/src/instr/ConservativeTransport.java` (the unbacked-trace instrumentation), `wp3-wp4/src/values-old/` and `wp3-wp4/src/editing/`; scratch copies of whole trees, of `PassiveStepSolver` versions already in git and of harness builds were not copied.
- `tools/cloud-science-harness/` (the Minecraft-free runner, merged at `b83537a`): README updated with its use through the batch and the close numbers, and `investigation/libm-cbrt/` (the correctly rounded `cbrt` experiment behind the JDK-roundoff finding) added.
- `tools/INDEX.md`: both rows updated (phase-ports-probes Concluded 2026-09-26; cloud-science-harness in use as a fallback).
- Everything is tracked on the branch (the owner's "Cloud prep" `ea16150` made `documentation/` and `tools/` tracked; `logs/` folders force-added), so the merge itself carries them to `main`.

### (d) Gates after the cleanup

JDK OpenJDK 21.0.10 (container); `JAVA_TOOL_OPTIONS` as the environment sets it (proxy); one Gradle invocation at a time (an idle daemon only), no dev client. Logs `tools/phase-ports-probes/wp6/logs/`.

| # | command (from `/home/user/CreateChemE`; log) | result after the cleanup | before (WP5, `9744a45`/`5f38474`) |
|---|---|---|---|
| (a) | `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (`01-gradle-fluid-suite-wp6.log`, 1 min 18 s) | **472/472** in 103 classes, 0 skipped; the test-name list (`01-test-names-wp6.txt`) identical to `wp5/logs/02-test-names-wp5.txt` (normalised: the renamed extreme test's XML names are its parameter names); the 33 junction lines (`01-junction-lines-wp6.txt`) **identical** to `d10/logs/02-junction-lines-d10.txt` apart from ms/bytes/allocatedMB; **MIXED_GAS_COST 286** Newton solves (575 ms, 135.1 MB, single run) | 472/472 in 103 classes, junction lines identical, 286 |
| (b) | `./gradlew --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` (`02-...`) | **0.000e+00** on state/moles, temperature, phase fraction and flow; 3 accepted / 0 rejected, 4 Newton solves, 29 iterations; the three island fixtures SKIP as before | 0.000e+00, 3/0, 4, 29 |
| (c) | the adjacent selection: `./gradlew --no-configuration-cache --no-build-cache test --rerun --tests *PassiveStepSolverTest --tests *PumpJunctionStartupTest --tests *FilterBlockLineIslandTest --tests *DeadHeadedLineIslandTest --tests *NetworkRegimeTest --tests *PhysicalFluidTopologyTest --tests *PipePresentationTest --console=plain --continue` (`03-...`) | **45/45** in 7 classes | 45/45 |
| (d) | `./gradlew --no-configuration-cache runFluidGameTestServer -PfluidGameTestRunId=wp6-final --console=plain` (`04-gametest-wp6-final.log`, fresh world `run/fluid-gametest-wp6-final`, 42 s) | **All 33 required tests passed**; `PHASE_PORT_GAMETEST` lines: pump `INLET_WRONG_PHASE / ERROR: pump inlet not liquid (vapour 100.0 % by volume, from reservoir at ...)`; compressor seen `[PUMP_TARGET, CLOSED]`, suction 52659.01872613692 Pa / 247.854 K, discharge 157976.31408416174 Pa / 338.042 K, ratio 2.99999, `FULL / CLOSED (limit 105318 Pa at this suction)` (bit-identical to WP5's final run); outlets at committed tick 3400: tank 124419.4 Pa / 176.86 kg, bottom LIQUID 29.456 kg/s drawing water, head 1723.2 Pa (WP5's run sampled committed tick 3468: 124302.5 Pa, 29.415 kg/s, 1715.3 Pa; the test samples the first interval with flow after the generator's removal, whose tick depends on the batch schedule, so the fill state differs, not the physics) | 33/33 twice (WP5) |
| (e) | the **full** unit suite: `./gradlew --no-configuration-cache --no-build-cache test --rerun --console=plain --continue` (`05-gradle-full-test-wp6.log`, 7 min 15 s; summary `05-full-suite-summary.txt`) | **1166 tests in 237 classes, 1165 passed, 1 failed**: `science.column.v3.thermo.RegroupedCrudeTest.previouslyDifficultCrudesPublishAuditedClassicalSolutionsWithoutLearnedSeeds`, `CancellationException: Regrouped preset deadline` (the test's own 45 s wall-clock deadline per preset). **Pre-existing, not this batch, not fixed**: the batch changes nothing under `science/column`, `science/thermo` or their tests, and column code calls no fluid code; alone it passes on both trees (WP6 50.55 s, `08-regrouped-crude-wp6.log`; base `6e1c5b6` 49.46 s, `08-regrouped-crude-base-6e1c5b6.log`); the full `test` task on the clean base worktree `scratchpad/wt-harness` (src `6e1c5b6` = `c32acac`'s) fails the same test the same way (1100 tests, 1 failed, `09-gradle-full-test-base-6e1c5b6.log`): a load-sensitive wall-clock deadline in the 4-core container. The full suite's 33 junction lines are identical to D10's too. The 66 tests over the base's 1100 are the batch's fluid tests (472 - 406) | not run in this batch before (the brief's "about 1,250" is not what this container counts: the base has 1100) |
| (f) | `./gradlew --no-configuration-cache build -x test --console=plain` (`06-...`); then `compileFluidGameTestJava compileMcpCompatJava --rerun` (`06b-...`) | **BUILD SUCCESSFUL** (`verifyMaterialIndex`, `jar`, `jarJar`, `sourcesJar`, `assemble`, `check`); both source sets compile | BUILD SUCCESSFUL |
| (g) | `LIB=<jars> OUT=<dir> REPO=/home/user/CreateChemE bash tools/cloud-science-harness/harness.sh all` (`07-harness-all-wp6.log`, 100 s) | science **223/223**, runtime **249/249** with the 33 junction lines identical to its reference, adjacent **45/45**, chain-100 **0.000e+00**; every printed fixture line equal to WP5's harness run apart from wall times and `HashMap` print order (`07-harness-diff-vs-wp5.txt`) | 223 + 249 + 45 + 1 |

No gate changed its count or any conservation, ledger or solve-count number; no assertion and no tolerance changed. A cleanup neither widened nor narrowed a gate suite: no test left (the extreme test kept its name list and its success assertions).

### (e) Open owner decisions and options (collected from every section)

Closed during the batch, for the record: WP1 section 8 (dry-seeded junction) by D10; the vent gate defect (WP2 option 3, D9 section 5, D11 item 7) by D13; WP2's options 1-2 and A2/A9/A13-A16 superseded by D11; D11 items 6 (by A37) and 8 (by WP6 (b)); the extreme-topology first-interval defect by D12.

Open, none blocking the merge:

1. **Re-baselines to confirm** (made, recorded old and new): A32 (`LevelHeadTest` drain restated under D11), A44 (the 15 compressor migrations, `TransientQualificationTest` now transferring, the three B re-baselines by flash: `PumpJunctionStartupTest` crude at 150 kPa, the crude GameTest at 1 MPa, `FluidFallbackQualificationTest` pumping water), A54 (the four physical-topology rising lines at the bottom-port pressure, headspace 6.95 kPa / 8.61 kPa lower). A19 is moot (its test class was deleted by D11). The reversible defaults A20-A54 stand unless the owner objects.
2. **D14 (accepted for this batch)**: adiabatic humid venting from about 180 kPa and above cools below 273.16 K and every step is refused (all-BULK too). Options for a later batch: (b) vessel wall heat exchange with an ambient, (c) an ice region in the water properties, (d) clamped water properties below the triple point.
3. **D11 section 12**: item 2 recovered rejections at phase boundaries (lever: per-step hysteresis on the phase correction, only if in-game cost shows); item 4 the mobility check reads the leading phase, not the step's mixture (option: carry the draws into the stage guard); item 5 unbacked traces for a donor that also receives (options: per-component reachability at booking, or cap each frozen fraction at what the donor can deliver; none measured). Item 3 (condensate after a drain) is physical, no decision.
4. **WP3 + WP4 section 11**: (1) A37 the port supply at the target over the slice: keep / the leading phase / a fixed reference time; (2) the refusal uses the slice duration (informational; the GUI passes `advancedSeconds()`); (3) a closed device's head unknown moves the Newton's roundoff by up to 10 ulp at 0.1 s: accept / drop a closed device's head unknown and rows (not bitwise for existing closed devices); (4) compressor work at long steps +13 % at 5 s: accept (D5) / book at the mean of start and end heads (the pump's booking too); (5) the RCPR landing 2.8 Pa against the kept 3 Pa bound: accept / state the bound relative to the rise; (6) the formerly dead-headed C tests now transfer: keep / `r_max = 1.5` to keep them dead-headed; (7) `FluidFallbackQualificationTest` "wet crude pump" pumps water: keep / add a crude case with a 300 kPa generator.
5. **WP5 section 10**: (1) "drawing X" = the phase that carried the most mass (A47): keep / first phase of the order present / list every drawn phase; (2) the refusal reason is stated on the committed state (A48): keep / carry the solver's text in the interval result; (3) compressor (and pump) efficiency reset to 1 by a GUI edit: keep / an efficiency field on both pages. Items 4 (no head-only two-tank stack) and 5 (falling lines at the landing resolution) need no decision.
6. **Extreme-topology water variant** (`EXTREME_TOPOLOGY_TESTS.md` section 7): water generators into nitrogen tanks through the same meshes hold at the default cap and at 100000 m/s; not a test; a separate water-fill-through-a-mesh campaign if the owner wants it (the scan runner is in `tools/phase-ports-probes/extreme/`).
7. Carried from the mixed-gas batch, unchanged: the `fluid-trbdf2-r1` anchor label, the three island exact-regression fixtures skipping (no current-basis stress snapshot).

### (f) What the merge needs

1. `CHANGELOG.md`: this batch's `[Unreleased]` `### Changed` line moves under `## [0.7.0] - <merge date>`.
2. `gradle.properties`: `mod_version` 0.6.0 -> **0.7.0** (minor: solver model change, new block, checkpoint format 6).
3. `documentation/INDEX.md` row of this batch -> "Implemented <merge date>"; `tools/INDEX.md` rows `phase-ports-probes/` and `cloud-science-harness/` keep their status with the merge date noted.
4. **Fresh world**: checkpoint format 6 refuses every 0.6.x fluid world ("Create a fresh world for this development build"); wire protocol fluid-7 (client and server from the same build).
5. **The owner's in-game check** (MCP bridge, `runMcpClient` lane, fresh world), the three scenarios of plan section 6 as listed in WP5 section 11: tank outlets (top/side/bottom lines, head, hint, kg/h-kmol/h toggle, compressor block render and name); pump refused on a tank's side and running from its bottom, refused again when the water runs out; compressor to its ratio limit, ratio edits 1.5 and 10.5 (refused by the screen), then refused on water.
6. **The Windows JDK 21.0.11 lane**: fluid suites (expect the one known `MIXED_GAS_TRANSIENT interval=0.1 key=0.02:5:false` ledger roundoff difference against this container's captures; compare the junction lines with the Windows run 160 capture instead), exact regression 0.000e+00, the adjacent 45, `runFluidGameTestServer` on a fresh run id.
7. Git: `origin/main` is `ea16150` ("Cloud prep", on `c32acac`) and an ancestor of this branch, so `main` fast-forwards. The Windows main checkout is still at `c32acac` with `documentation/`, `research/` and `tools/` untracked there; pulling `ea16150` or this branch writes tracked files over those folders (git refuses while untracked files would be overwritten), so move the local copies aside first and compare them after (the tracked copies are the canonical ones from here on).

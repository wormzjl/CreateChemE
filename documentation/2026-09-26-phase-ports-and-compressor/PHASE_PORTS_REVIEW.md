# Phase ports and compressor: review

- Batch: `2026-09-26-phase-ports-and-compressor`. Plan: `PHASE_PORTS_PLAN.md`; decisions: `DECISION_LOG.md` (this folder).
- One section per work package. Numbers are measured unless labelled *inferred*.

## WP1: ports, per-end streams, driving-pressure helper (2026-09-26)

- Author: Claude (Opus 5.5), worktree `/home/user/CreateChemE` (cloud container), branch `claude/phase-ports-compressor`.
- Base: `b83537a` = `6e1c5b6` (main 0.6.0 `c32acac` plus the tracked documentation/tools tree) plus the cloud harness commit; `git diff --stat c32acac 6e1c5b6 -- src/` and `git diff --stat 6e1c5b6 b83537a -- src/` are both empty, so the code edited is c32acac's and every line number of plan Appendix C.4 held when the work started.
- Commit: see the WP1 row of plan section 7 (the commit that adds this section).
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

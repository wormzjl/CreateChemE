# P4 stage 2b: the fluid network on the crystal competition

Batch `2026-09-24-coolprop-low-temperature`, P4 stage 2b, 2026-09-25. Branch `claude/coolprop-multiphase-thermo-37f6b0`,
base `0198148` (P4 stage 2a recorded). Commits: section 10. The engine (`science.thermo.phase`) was consumed through its
public API and not edited.

## 0. Outcome

- **Contract.** A network package that admits crystals (`PhaseContract.admittedCrystals`, the pilot's CO2-I) now has its
  engine built on `PhaseContract.forPackage` with one anchored crystal evaluator per crystal. A dry request carrying a
  crystal's species at or below the crystals' ceiling (300 K) asks for `FLUID_AND_CRYSTALS`; every other request, and
  every package without crystals (all bundled network packages), keeps the fluid-only request bit for bit.
- **Domain.** A gas carries a crystal's species down to the competition's floor (CO2 to 90 K); a liquid keeps the fluid
  floor (216.592 K), as the engine holds a liquid carrying CO2 below it.
- **Crystal inventory.** An immobile inventory per vessel (moles per crystal): part of the species balance, its enthalpy
  on the network's sensible datum in the energy closure, its volume in the volume closure. Never transported; junctions
  and pipes hold none; a bulk withdrawal takes the fluid only (v1 rule).
- **Deposition and sublimation.** The engine's UV answer for the vessel's species totals, energy and volume, at the start
  of every interval and after every accepted full substep, then the network's own closure. Frozen inside a step. Nothing
  runs per tick and no partial pressure is held.
- **Hold.** The engine's `SOLID_LIQUID` detail is `UnsupportedPhases.Kind.SOLID_LIQUID` (reason `NOT_IMPLEMENTED`), a
  typed hold of the island naming its node, counted under its own key; the held island commits nothing and takes no
  events.
- **Persistence.** Checkpoint format 6, island unit format 3; format 5 and older refused (fresh world).
- **Presentation.** The crystal reaches block entities and menus at the island's presentation bucket only.
- **G4.** CO2 frost points in nitrogen (Sonntag 1960, Smith 1963, owner-provided) now scored as G4F2: pooled MAD
  **0.420 K** over 51 points inside 10 MPa (P0 target 1.5 K), worst 1.99 K. Finite inventory, heat and reversibility are
  checked by conservation and consistency (no dataset measures them).
- **Gates.** `test` 1,267 (1,256), `fluidScienceTest` 226 (219), `fluidRuntimeTest` 230 (227), all green;
  `fluidSolverRegression` exact chain-100 0.000e+00; 30 of 30 fluid GameTests on a fresh world. The fluid-only path is
  unchanged (digests, regression, pins), so no benchmark pair was run.

## 1. Contract in the network

| Item | Change |
|---|---|
| `FluidThermodynamics` | `crystalsCompete()`, `crystals()` (admitted, declaration order), `crystalComponent(id)`; a spine package only (the crystal is anchored to the spine family and moved to the network datum by the spine's formation enthalpies) |
| Request rule `crystalsCompete(t, overall)` | the package admits crystals, the request carries a crystal's species, no water, and `t` at or below the lowest crystal ceiling (CO2-I: 300 K). Otherwise `FLUID_ONLY`, the pre-P4 request |
| `NetworkPhaseEngine` | with crystals: `PhaseContract.forPackage`, the contract's crystal competition checked equal to the network's set, `FluidTpEquilibrium(contract, evaluator, settings, freeWater, crystals)`, and each crystal anchored once to the evaluator (the engine anchors its own copy identically); `tp`/`uv` take the competition; without crystals the four-argument engine on `forNetworkPackage`, unchanged |
| `adopt` (the TP adapter) | `VAPOR_SOLID` and `SOLID_PRESENT`: the amounts in the vapour slot (see 2.3); `PURE_COEXISTENCE_UNDERDETERMINED` with a crystal state: the node's own layout when it has one (the fluid coexistence's rule), else the vapour slot; `UNSUPPORTED` with the `SOLID_LIQUID` detail: `UnsupportedPhases(SOLID_LIQUID)` |
| `FluidDomain` | vapour floors: for an admitted crystal's species `max(crystal record floor, spine floor)` (CO2: 90 K), used by `check` for the vapour slot, by `checkTotals` for a dry overall, and by `HydrocarbonModel.phase` for a vapour root; `null` without crystals, so every check is the fluid-only one bit for bit. `range(i)` (read by `PhaseContract.forNetworkPackage`) is unchanged |
| `UnsupportedPhases.Kind.SOLID_LIQUID` | new kind, key `unsupported-phases: a crystal beside a liquid the network does not carry`, reason `NOT_IMPLEMENTED`; the two existing kinds keep `PHASE_COMPETITION_NOT_QUALIFIED` |

Why wet and hot requests stay fluid-only:
- Water with crystals is refused by the engine as water chemistry (D8, hydrates).
- Water below 273.16 K is out of domain.
- CO2-I is not stable above its melting line, about 218 K at 10 MPa (0.13 K per MPa). So within the 10 MPa envelope no
  crystal competes where water is in its domain.
- Above the crystal record's 300 K the crystal competition's domain refuses CO2, and no crystal is stable there.

## 2. Inventory, closures and the equilibration

### 2.1 Data

| Type | Content |
|---|---|
| `science.fluid.state.CrystalInventory` (new) | canonical list of `Stock(crystal, component, moles)`: sorted by id, one per id, positive finite amounts (a zero is no stock), at most 16; `speciesTotals(fluid)`, `mass(weights)`, `with(...)` |
| `science.fluid.state.CrystalStock` (new) | the inventory evaluated at the node's (T, P): volume, enthalpy (network datum), internal energy `H - P V`, mass |
| `PassiveNetwork.Inventory` | fifth component `crystals`; `speciesMoles()` = fluid + crystals; the hash is the pre-crystal one without crystals; a junction refuses crystals |
| `FluidThermodynamics.State` | component `crystals` (`CrystalStock.NONE` by default); `withCrystalStock`, `nodeVolume()`, `nodeInternalEnergy()`, `nodeMass()`. `volume`, `enthalpy`, `internalEnergy`, `mass` stay the mobile quantities (fluid and inert particles), which every transport term reads |

### 2.2 Closures

- **Energy.** `h_s(T, P)` is the anchored Jaeger-Span crystal's enthalpy on the spine's formation datum, minus the
  species' formation enthalpy `DeltafH_i` (D10), exactly as the fluid phases of a spine package. The node's energy row
  compares `U_mobile(T, P) + n_s (h_s - P v_s)` with the inventory energy, which includes the crystal.
- **Latent heat across the move.** On the network datum `h_V - h_s` of pure CO2 at 194.466712376 K and 0.1 MPa is
  **25,200.6415 J/mol**, the engine's sublimation step of stage 2a (25,200.6 J/mol; Giauque and Egan 25.2 kJ/mol).
- **Volume.** The volume row compares `V_mobile + n_s v_s(T, P)` with the vessel volume. The crystal's molar volume is
  2.800e-5 m3/mol at 194.5 K.
- **Newton.** The Jacobian is by differences, so the crystal's heat capacity and expansion enter without new code.
- **Inside a step.** The crystal is frozen: no unknown, no row. `PhaseLayout` takes the vessel inventory's crystals
  (seven-argument constructor) and states them on every decoded trial. `ConservativeTransport`, `TrBdf2StepSolver` and
  the stage inventories carry them unchanged, and `checkConservation` asserts it.

### 2.3 The equilibration

`FluidThermodynamics.equilibrateCrystals` with `InventoryEquilibrium.crystals` / `equilibrateCrystals`:

1. **When.** Called by `InventoryEquilibrium.refresh` (the start of every full interval) and by `PassiveIntervalSolver`
   after every accepted full substep (both error-control branches). A model without crystals returns the graph itself.
2. **Which nodes.** Vessels only (`RESERVOIR`, not empty). A vessel without crystals is asked by TP at its own (T, P)
   first (one fluid equilibrium, 5 to 35 us). Nothing follows when no crystal forms. When a crystal would form beside a
   liquid, the TP answer is the typed hold.
3. **The UV answer.** A vessel that could deposit, or holds crystals, takes the engine's UV answer:
   - the species totals (fluid plus crystals);
   - the inventory energy carried to the formation datum (`+ sum n_i DeltafH_i`, D10);
   - the vessel volume.

   Inert particles, if any, are taken out: their volume, and their sensible energy by a fixed point on the answer's
   temperature (at most 30 steps).
4. **Fallbacks.** With water, the fluid-only UV answers and every crystal sublimes. When the crystal UV is refused above
   the ceiling, the fluid-only UV answers likewise.
5. **The new split.**
   - The crystal amount is the answer's solid phase at its species, and the fluid keeps the difference (conserved to
     rounding).
   - The fluid's slots come from the TP adapter at the answer's (T, P).
   - The network then closes its own rows on the new inventory (`InventoryEquilibrium.solve`, crystals frozen).
   - Volume, energy and particles are the inventory's own and never move.
6. **Deadband.** A split within `CRYSTAL_DEADBAND = 1e-10` of the species total of the vessel's own stands. That is ten
   times the engine's UV round-trip accuracy on the solid amount (8.7e-12). An equilibrated vessel is then a fixed point
   bit for bit, so a resting island certifies REST (seen at tick 300 in the runtime test).
7. **Refusals.** A UV answer with no gas beside the crystal (`SOLID_PRESENT`) is refused: a network node carries a
   crystal only beside a gas.
8. **The TP adapter moves nothing.** A supersaturated gas inside a step is stated as a gas with all of its amounts,
   because the engine says the fluid left by a deposition is one non-liquid phase. The crystal is moved only by the UV
   answer. The outer phase check therefore keeps the node's "vapour" regime, and the step's regime trace stays smooth.

**Design choice (for D18).** An operator split: transport and heat at fixed crystal inside a substep, then the
equilibrium. The error is first order in the substep (1 to 20 s, under the controller's own error control); conservation
is exact. The alternative, the crystal as a Newton unknown with an equilibrium row per vessel, needs a new layout block,
its active set and derivative rows. It was not needed to meet the stage's tests.

### 2.4 The v1 rules

- Bulk withdrawal (a module's scheduled withdrawal, a pipe's flow) moves the mobile phases only, at their composition;
  the crystal stays until the engine's answer sublimes it. Tested: the stream's y_CO2 is 0.0815, the gas's, against the
  vessel's 0.1 with its crystal. The crystal grew, 1.481 to 1.573 mol, as the gas left behind expanded and cooled.
- Deposition only in finite vessels: junctions (zero holdup) and pipes carry a supersaturated gas through.
- Approximate (fallback) intervals are refused for a crystal model (`ApproximationRejected`); a crystal island always
  takes full solves.
- A certificate needs the crystals unchanged over its interval (a deposition or sublimation is a node-solid change), and
  replay keeps them.

## 3. Persistence: format 6 (fresh world)

- **Format.** `FluidCheckpointCodec.VERSION` 5 to 6, `UNIT_FORMAT` 2 to 3. Each node's crystal inventory follows its
  particle populations: the count, then per crystal its id, its species' index in the conserved basis and its moles.
- **Validation on read.**
  - The crystal must be one the island's model admits, at its own species index, inside the basis.
  - Its amount must be finite and positive.
  - The order must be canonical, and a junction may hold no crystal.
  - The inventory's own checks apply on top.
  - The crystals' volume and energy are not stored; they are re-evaluated from the stored state.
- **Round trip (test).** A pilot vessel that deposited at its first interval (`aCrystalInventoryRoundTrips...`):
  - inventory (fluid, energy, particles, crystals) and crystal stock bit for bit;
  - clock, fences (pending events), fallback allowance, status, revision, the approximation anchor's revision and the
    last interval;
  - re-encoded to the same bytes.
- **Old saves.** `formatFiveAndOlderAreRefused...` refuses formats 1 to 5 with the instruction to create a fresh world
  and leaves the tag untouched. No migration, no absent-field normalisation, no legacy test.
- **Archived captures.** The read-only archived gameplay captures of `McpGameplayRegressionTest` (format-3 JSON graphs,
  not world saves) name their own four-field inventory shape, as they were written: they predate crystals and hold none.
- **Ledger.** `WorldTopologyLedger` counts a node's crystals in its species totals (and their mass in its potential
  energy), so constructed and destroyed material still balance.

**Fresh-world statement.** Format 6 is a breaking change: worlds saved before this branch's commits `2802e5e` onwards are
refused on load with the fresh-world instruction; the dev and GameTest worlds of this stage were fresh
(`run/fluid-gametest-p4c-20260925`). No existing world was loaded or touched.

## 4. Presentation

- **Data path.**
  - `FluidView.State` gains `crystals` (the inventory) and `crystalVolume`, filled by `FluidView.State.from` from the
    node's state in the island's presentation snapshot.
  - The live menu payload is JSON of the view (a Gson round trip of the crystal view is tested).
  - The reservoir screen shows "Crystals x mol, y L (stay)" when a vessel holds any.
- **Test** (`CrystalIslandRuntimeTest`, coordinator and `FluidPresentation` with a menu and a loaded block entity):
  - subscription and load deliver nothing;
  - deliveries only at the island's bucket (tick % 100 = 1);
  - tick 1 shows the charged gas with no crystal (committed 0);
  - tick 101 shows 1.4813777 mol (committed 100), the committed inventory's amount, in the menu and the block entity
    alike;
  - after REST certification the block entity is no longer presented (no publication).
- **G6.** The in-game GUI check through the MCP bridge was not done: selecting the pilot needs a datapack override of
  `networks/default.json` in a dev world, which is not quick. It is a G6 item.

## 5. Tests and results

Test classes (new unless noted). Outputs: `research/2026-09-24-coolprop-low-temperature/p4-network/output-*.txt`.

| Test | Result |
|---|---|
| `CrystalDepositionIslandTest.theCrystalCompetitionIsThePilots...` | the pilot competes (CO2-I at species 3), gas floor 90 K, liquid floor 216.592 K (a liquid slot of CO2 at 180 K refused, the same gas evaluated); the bundled network package does not compete and its domain is not widened |
| `...theCrystalsEnthalpyOnTheNetworkDatum...` | sublimation enthalpy 25,200.6415 J/mol (engine 25,200.6), U = H - PV, one mole 0.0440098 kg, v_s 2.800e-5 m3/mol |
| (a) `aClosedVesselChargedBelowItsFrostPoint...` | 10 % CO2 in N2, 0.1 m3, charged at 170 K and 1 MPa (frost point 192.75 K): the first solve deposits 1.481377745 of 7.522070360 mol CO2 (19.69 %) at 191.506866 K and 1,124,071.5 Pa, gas y_CO2 0.0819. CO2 conserved to 1e-12; N2 bits, energy and volume unchanged; energy and volume closures to 1e-8 and 1e-9. An independent engine service's UV answer for the same inventory gives the same crystal (1e-9 of the CO2), T and P (1e-9). The next interval changes nothing (rest) |
| (b) `theVesselCooledAndWarmedBack...` | one mole of the same gas at 200 K and 1 MPa; a module injection (a 1e-12 mol/s N2 trace carrying -600 W, then +600 W, 5 s each: the network has no heater). Cooled by 3000 J: 163.922352 K, 742,345.7 Pa, 0.09265646 mol crystal (3 substeps, 0 rejected), the engine's fixed-volume answer of stage 2a to its printed digits. Warmed back: the crystal sublimes completely, 200.000000002241 K (1.1e-11), 1,000,000.000022 Pa (2.2e-11), CO2 conserved to 1e-12. Bound 1e-9 |
| (c) `aPipeChain...` | warm vessel (250 K, 1.3 MPa, 10 % CO2), junction, cold vessel (140 K, 1 MPa N2), 4 mm pipes, six 5 s intervals: the cold vessel deposits 0.095 to 0.307 mol (144 to 154 K, gas y_CO2 5e-4 to 1.7e-3); the warm vessel and the junction hold none; the island's CO2 (vessels and crystals) conserved to 1e-10 every interval |
| (d) `aCo2RichCharge...Hold` | 70 % CO2 in ethane at 200 K and 2 MPa: the fluid the crystal would leave is an ethane-rich liquid, so `flashTP` is `UnsupportedPhases(SOLID_LIQUID, NOT_IMPLEMENTED)`, and the island's interval throws it at node 1 with key `unsupported-phases: a crystal beside a liquid ...`, not a domain hold. CO2 in N2 below the triple point deposits beside a gas instead ((a), (b)), so the CO2-rich hold case needs a condensable partner |
| `aBulkWithdrawal...` | section 2.4 |
| `CrystalIslandRuntimeTest` (2) | presentation data path (section 4). Held island: status `HELD: Unsupported phases: NOT_IMPLEMENTED (SOLID_LIQUID) ... at node 101 ...`, 3 attempts in the first 300 ticks on the existing hold ladder, committed tick 0, a queued fence stays pending, the inventory kept |
| `FluidCheckpointCodecTest` (extended, +1), `FluidCheckpointFormatTest` (format 5 refused, the format pin 6) | section 3 |
| `G4F2CarbonDioxideNitrogenFrostPointHoldoutTest` (new, `science.thermo.qualification`) | section 6 |
| Updated: `PilotCryogenicCatalogTest` | CO2's vapour table from 90 K (record r2); the scientific revision, physics and spine pins unchanged |
| Updated: `SpineNetworkPathTest` | the WP5 pilot flash grid of 726 states: 551 evaluated (500 at WP7), 17 two-phase, 102 domain refusals (222 at WP7), 4 steam; typed holds `SOLID_LIQUID` 65 and `THREE_PHASES` 4 (CO2 in liquid methane at 100 K, whose fluid answer has a third phase). All asserted to lie only where the crystal competition newly admits CO2; none unconverged |
| Updated: `G3F7CarbonDioxideInLiquidMethaneTest` | the pilot network refuses CO2 in liquid methane at 91 to 120 K with the typed hold (SOLID_LIQUID or THREE_PHASES) instead of a domain violation; the fluid-only network contract still refuses below 216.592 K |
| Updated: `G3F9DomainRefusalTest` | the pilot network's CO2 boundary is the competition's 90 K (90.1 K answered, 89.9 K refused naming CarbonDioxide); was 216.592 K |

The fluid-only path, unchanged:
- `LegacyNetworkPathPinTest` (the free-water `flashTP` digest) is green;
- so are P12 (`WorkerTrajectoryEquivalenceTest`) and P31 (`CadenceTrajectoryQualificationTest`);
- the chain-100 exact replay is at zero;
- all 30 fluid GameTests passed on a fresh world;
- the eight bundled pins (`bundledPackageFingerprintsAreUnchanged`) are green.

The engine's per-solve path for fluid-only islands did not change: every new branch is guarded by the package's crystal
set, the domain's `null` floors, or an empty crystal inventory, so no benchmark pair was run (the owner's rule).

**Transport data (a record change on the pilot only).** The network reads every node's vapour viscosity, and the pilot
CO2 table started at 216.592 K. So a CO2-laden gas below the triple point could not flow ("Viscosity state is outside the
correlation's temperature/pressure domain" in (b) and (c) before the change).
- `pilot_carbon_dioxide` vapour viscosity r2 adds the CoolProp 8.0.0 dilute-gas function from 90 K: 19 nodes below
  216.592 K within 0.043 %, built with `tools/p4-network-rigs`.
- The r1 nodes from 216.592 K up are unchanged.
- Below the triple point no measurement exists: it is an extrapolation of the Laesecke and Muzny 2017 dilute-gas term,
  fitted to ab initio values, and the record's source text says so.
- The pilot's pins did not move (the viscosity table is outside the three pinned fingerprints).

## 6. G4 status

- **G4F1** (stage 2a, CO2 frost points in methane-rich gas): binary pooled MAD 1.354 K, ternaries at most 1.73 K.
- **G4F2** (new, CO2 + N2, the plan's own case):
  - **Data.** Sonntag 1960 Table VIII (64 points) and Smith 1963 Table XIII (his own 69: the 67 `smith` and 2
    `smith_avg` rows). The 48 rows he reprints from Sonntag and the 6 agreement points are Sonntag's and are not counted
    twice; the 2 plotted values are excluded. Copied to `src/test/resources/science/thermo/solid-co2-holdouts/` with
    their citation headers.
  - **Scoring.** The frost temperature at the printed P and y, against the isotherm.

| Dataset | Scored | Holds | Above 10 MPa | MAD, K | Bias, K | Max, K | Partial-pressure MAD, K |
|---|---|---|---|---|---|---|---|
| Sonntag 1960 | 49 | 10 | 5 | 0.403 | -0.154 | 1.991 | 8.52 |
| Smith 1963 (own) | 2 | 4 | 63 | 0.836 | -0.836 | 0.985 | 13.5 |
| **Pooled** | **51** | **14** | **68** | **0.420** | **-0.181** | **1.991** | 8.71 |

- **By isotherm (Sonntag).** 140 K 0.60, 150 K 0.62, 160 K 0.31, 170 K 0.17, 180 K 0.44 (bias -0.44), 190 K 0.38 K
  (bias -0.38). Smith attributes a warm bias of about 1 K at 190 K to Sonntag's apparatus; the family's negative bias there
  is consistent with it.
- **Context.** Maltby et al. 2025 report about 0.5 to 0.7 K for PR with a fitted kij. The pilot's predictive E-PPR78
  kij(T) scores 0.42 K on the points inside its domain.
- **Holds (14).** At 140 to 160 K and 6 to 9.1 MPa the onset lies where the fluid is dense supercritical N2 labelled
  liquid-like (`SINGLE_FLUID`): the stage 2a limit "near-critical carrier". These are P5 and label items, not failures.
- **Above 10 MPa (68).** Out of the pilot's domain, counted, not failures. Most of Smith's own data (100 to 200 atm)
  lies there, so Smith contributes only 2 scored points.
- **Test bounds (D17's rule).** Pooled MAD at most 1.5 K (the P0 target); per dataset MAD at most 1.0 K and per point
  at most 2.5 K; at most 14 holds; exactly 68 points above 10 MPa.

What G4 can claim now:
- **Allowlist.** The CO2-I crystal of `createcheme:pilot_cryogenic` in dry gas of N2, CH4 and C2H6, 90 to 300 K, 100 Pa
  to 10 MPa, in finite network vessels.
- **Onset.** Qualified against independent data at the declared-error grade: in methane-rich gas (G4F1) and in N2
  (G4F2, 0.42 K pooled).
- **Finite inventory, heat, exhaustion, reversibility.** Checked end to end in the network by conservation (1e-12),
  reversibility (1e-11), the engine's own UV answer (1e-9) and the datum's sublimation enthalpy (25,200.64 J/mol).
  No dataset measures deposited amounts or heat (survey), so these are consistency checks, not data qualification.
- **Not qualified.**
  - CO2 + N2 above 10 MPa (out of domain).
  - Every crystal-beside-liquid state (typed holds, P5).
  - Dense supercritical N2 below 160 K labelled liquid-like (holds).
  - Deposition rates (equilibrium only by design).
  - The CO2 gas viscosity below the triple point (an extrapolated dilute-gas function).

The G4 gate text asks for independently validated onset and finite-inventory cases. The onset is met. Finite inventory
is met by consistency only, which the plan foresees ("if only onset data can be qualified, retain ..."). The lead
should record which reading G4 takes (D18 item 8).

## 6a. Hold behaviour

- A `SOLID_LIQUID` answer anywhere in a solve (the refresh, a substep's equilibration, a seed, the outer phase check)
  is the typed `UnsupportedPhases`. It names its node, counts under its own key, and fails the interval on the existing
  hold path (the island's status carries it).
- The retry ladder is the numerical one: halved slices, then deferred retries at cadence 2^k.
- The held island commits nothing, and a queued event (fence) stays pending (the existing rule: a held island cannot
  reach its fence tick).
- An input change retries it. Nothing is fabricated: no split, and no fluid-only answer in place of the crystal
  competition.

## 7. Tool folder and research artifacts

- **Tool** (main checkout): `tools/p4-network-rigs/` (README, `co2_vapour_viscosity_below_triple_point.py`,
  `co2-vapour-viscosity-90K.json`).
  - Nothing was ever in a tracked path and no probe, rig or Gradle switch left the code, so there is no reattach patch.
  - The stage added only gate tests and product code to tracked paths.
- **Research** (main checkout): `research/2026-09-24-coolprop-low-temperature/p4-network/` (README): the test outputs
  and every Gradle log of the stage (`logs/`).

## 8. Gradle runs

Git Bash in the worktree. One invocation at a time under `build/gradle.lock` (holder `p4c`), `JAVA_OPTS=-Xshare:off`,
`--offline`, no dev client (checked by process list), 2026-09-25. Logs in `research/.../p4-network/logs/`.

| Gate | Command | Before (stage 2a) | After |
|---|---|---|---|
| Full suite | `./gradlew test --offline` | 1,256 tests (255 classes), green | **1,267 tests (258 classes), 0 failures** (`test-full-final.log`, 3 min 37 s) |
| Fluid science | `./gradlew fluidScienceTest --offline` | 219 | **226, 0 failures** (`fluidScienceTest-final.log`) |
| Fluid runtime | `./gradlew fluidRuntimeTest --offline` | 227 | **230, 0 failures** (`fluidRuntimeTest-final.log`) |
| Regression | `./gradlew fluidSolverRegression -PfluidRegressionMode=exact --offline` | chain-100 0.000e+00 | **chain-100 0.000e+00** on moles, temperature, phase fraction and flow; the three island fixtures skipped as before (no `build/probe`) |
| GameTests | `./gradlew runFluidGameTestServer -PfluidGameTestRunId=p4c-20260925 --offline` | 30 of 30 | **All 30 required tests passed** (fresh world) |

Intermediate failures, each fixed (in the logs):
- the WP5 grid's new typed holds (test updated);
- the archived captures' inventory shape (the archive's own shape);
- G3 F7/F9's CO2 boundary under the crystal competition (tests updated).

The last commit `c284f59` changes one Javadoc comment after the gates (compiled).

## 9. Proposed decision (the lead numbers it D18)

**D18 (2026-09-25): the fluid network on the crystal competition.**

- **Question.** How does the network carry the pilot's CO2-I crystal: the request, the domain, the inventory, the
  closures, deposition and sublimation, holds, persistence and presentation?
- **Evidence.** This document (commits `0ea358a` to `c284f59`):
  - (a) a closed vessel deposits at its first solve (1.48 mol of 7.52 mol CO2), conserving CO2 to 1e-12, and reproduces
    an independent engine UV answer to 1e-9;
  - (b) cooling and warming by 3000 J through the network's energy input reproduces the engine's fixed-volume answer
    and returns to the start to 1.1e-11;
  - (c) a pipe chain deposits only in the cold vessel;
  - (d) the typed hold;
  - the latent heat on the network datum (25,200.64 J/mol);
  - format 6 round trip;
  - bucket-only presentation;
  - G4F2 pooled MAD 0.42 K on CO2 + N2;
  - the fluid-only path unchanged (exact regression 0, pins, 30 GameTests).
- **Chosen (standing instruction; reversible at G4/G5/G6).**
  1. **Request.** A package that admits crystals requests `FLUID_AND_CRYSTALS` for dry requests carrying a crystal's
     species at or below the crystals' ceiling. Wet and hotter requests stay fluid-only (water with crystals is water
     chemistry, D8; no CO2-I is stable where water is in domain or above 300 K within 10 MPa).
  2. **Domain.** A gas carries a crystal's species to the competition's floor (CO2 90 K); a liquid keeps the fluid floor.
  3. **Inventory.** The crystal is an immobile inventory of a finite vessel, in its species balance, energy (spine h_s
     minus DeltafH_i, D10) and volume.
     - Never transported: bulk withdrawal takes the fluid only (v1 rule).
     - Junctions and pipes hold none.
  4. **Deposition and sublimation.** The engine's UV answer for the vessel's whole inventory, at every interval start
     and after every accepted full substep (an operator split, first order in the substep, exact conservation), then
     the network's closure.
     - Deadband 1e-10 of the species total.
     - Frozen inside a step.
     - Approximate intervals refused for crystal models.
  5. **Holds.** The engine's `SOLID_LIQUID` is `UnsupportedPhases.Kind.SOLID_LIQUID` (reason `NOT_IMPLEMENTED`), a
     typed island hold naming the node. A held island takes no events.
  6. **Persistence.** Checkpoint format 6 and unit format 3 (fresh world); certificates need unchanged crystals.
  7. **Transport.** The pilot CO2 dilute-gas viscosity extended to 90 K (record r2), declared an extrapolation below the
     triple point.
  8. **G4.** Met at the declared-error grade with the allowlist of section 6: onset data-qualified (G4F1, G4F2); finite
     inventory qualified by consistency (no data exist).

     G4F2 bounds:
     - pooled MAD 1.5 K;
     - per dataset MAD 1.0 K;
     - per point 2.5 K;
     - at most 14 holds.
- **Rejected.**
  - The crystal as a Newton unknown with an equilibrium row inside the step: a new layout block and active set, not
    needed for the tests.
  - A TP-decided deposition inside the step: it holds the node's T and P while material changes phase; the UV answer
    couples energy and volume.
  - A network-side viscosity extrapolation without data: the CoolProp dilute-gas function is the pilot's source.
- **Affected milestones.** G4, P5 (every hold), P6/G6 (GUI check, morphology policy, warm start), G5.
- **Coverage changes.**
  - Pilot network vessels deposit and sublime CO2-I from N2, CH4 and C2H6 gas at the declared-error grade.
  - A pilot network state that needs a crystal beside a liquid is held typed where it was a domain refusal before.
  - The bundled network is unchanged.

## 10. Commits

| Commit | Content |
|---|---|
| `0ea358a` | `CrystalInventory`, `CrystalStock` (new); `PassiveNetwork`, `FluidThermodynamics`, `NetworkPhaseEngine`, `FluidDomain`, `HydrocarbonModel`, `PhaseLayout`, `PassiveStepSolver`, `ConservativeTransport`, `TrBdf2StepSolver`, `InventoryEquilibrium`, `PassiveIntervalSolver`; `pilot_carbon_dioxide.json` (vapour viscosity r2); `CrystalDepositionIslandTest` (new); `PilotCryogenicCatalogTest` |
| `2802e5e` | `FluidCheckpointCodec` (format 6, unit format 3), `FluidCheckpointStore`, `FluidSavedData` (comments), `IslandCertificate`, `WorldTopologyLedger`, `FluidView`, `FluidDeviceScreen`, `FluidCheckpointGameTests` (comment); `CrystalIslandRuntimeTest` (new); `FluidCheckpointCodecTest`, `FluidCheckpointFormatTest`, `FluidCheckpointStoreTest` |
| `818fdbb` | `G4F2CarbonDioxideNitrogenFrostPointHoldoutTest` (new) and the two holdout tables |
| `1dc438b` | `SpineNetworkPathTest` (the grid's typed holds) |
| `3723569` | `FluidCheckpointCodec`: the archived captures' own inventory shape |
| `1437a67` | `G3F7CarbonDioxideInLiquidMethaneTest`, `G3F9DomainRefusalTest` |
| `58f32e9` | `CrystalDepositionIslandTest`: the bulk withdrawal |
| `c284f59` | `FluidThermodynamics.flashTP` comment |

Commits carry the session's own attribution line (Claude Opus 5.5), as AGENTS.md asks ("the attribution line given for
the session") and as P4 stage 2a did, not the brief's Fable 5.1 line. `CHANGELOG.md`, `DECISION_LOG.md`, the unified
plan and the INDEX files are not edited; the proposed lines are in the report to the lead.

## 11. Open items

**P5**
- Every `SOLID_LIQUID` hold:
  - 65 on the WP5 grid;
  - CO2 in liquid methane (G3 F7);
  - 70 % CO2 in ethane at 200 K;
  - the 14 G4F2 onsets in dense N2.
- The grid's `THREE_PHASES` at 100 K (CO2 in liquid methane, fluid answer with a third phase).

**G6 and P6**
- In-game GUI check through the MCP bridge with a pilot datapack override:
  - the reservoir screen's crystal line;
  - "Bulk withdrawal: all phases together" should read "fluid phases" when a vessel holds crystals.
- A morphology policy before crystals ever enter suspension, filters or wall deposits (v1: an immobile vessel
  inventory only).
- A warm start for the crystal UV: each equilibration of a vessel with crystals is a cold UV of about 0.4 ms (16 to 30
  TP calls). It runs per accepted substep for pilot vessels only.

**Untested paths** (code present, no test):
- a vessel with inert particles and crystals (the fixed point on the particles' temperature);
- a wet vessel holding a crystal (fluid-only sublimation);
- the crystal UV refused above the ceiling (fluid-only fallback).

**Label and data**
- Dense supercritical N2 at 140 to 160 K and 6 to 9 MPa is labelled liquid-like, so frost points there are held: a PIP
  label question for supercritical carriers.
- The pilot package's advisory `CO2_CRYSTAL_JAEGER_SPAN` still says "no consumer reads it yet". Editing it is a record
  change for P6's pin review.
- The CO2 gas viscosity below the triple point is an extrapolation (declared in the record).

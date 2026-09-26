# Level head at tank outlets: cost evaluation (decision point O2)

- Batch: `2026-09-26-phase-ports-and-compressor`. Evaluation for the owner's open point O2 (`DECISION_LOG.md`), written 2026-09-26 by Claude (Opus 5.5).
- Read-only: no source edited, no Gradle run. Every source line is a line of commit `618ea63` (WP1 stage 4 of the backward-Euler basis), read with `git show 618ea63:<path>`; `PassiveStepSolver` means `src/main/java/com/wormzjl/createcheme/science/fluid/network/PassiveStepSolver.java`. Where the later WP2 commit `5b708fa` changes a cited fact it is said so.
- Basis assumed: `documentation/2026-09-24-mixed-gas-junction/BE_INTEGRATOR_PLAN.md` (backward Euler, state-change controller cap 0.05, 5 s slices, owned junction holdup, exact conservation by reconstruction) and `PHASE_PORTS_PLAN.md` of this folder (per-end `Port` BULK / VAPOR / LIQUID, per-end streams, availability band and throttle).
- Labels: *read* = in the code at the cited line; *inferred* = my reading of behaviour, not run; *estimate* = hand arithmetic, to be measured (section 8).

## 0. Verdict

A level head at the bottom (LIQUID) port is **cheap in the solver** and needs **no rework of the port model**. It adds no unknown, no row and no structural nonzero: every edge row already carries every column of both end nodes, and the block Jacobian already re-evaluates the edge rows for each perturbed column of a node. Written as the weight of the condensed phases over the tank's footprint, `dP = g m_c H / V`, it is linear in the phase amounts, exactly zero with no liquid, and independent of T and P. Its cost is in engineering consistency: eleven places outside the residual state a connection's static driving pressure, and each must read the same port pressure, or the start-of-solve closures reproduce the BROKEN classes of review 8.8 (c).

The one-block tank's default volume is **1 m3** (`CreateChemE.java:97`, config `reservoirVolumeCubicMetres`, default 1.0, range 0.001-1000). So "H = 1 m, the block" is the tank's real geometry at the default; the 1000 m3 block is only the config maximum.

Recommendation: **option B**, a head on the LIQUID port only, with H = 1 m fixed and no setting. About 1 to 1.25 agent-runs, placed after the phase-port WP2. Flat and gas-only islands stay bitwise; three assertions of one existing test move. Defer a height setting (C) until a multi-block tank makes H a geometric fact. Section 7 has the table.

## 1. What "hydraulic head" means in the model today (*read*)

**Node elevation.**
- Every node has one elevation, `PassiveNetwork.Reservoir(id, elevation, state, kind, inventory)` (`PassiveNetwork.java:80`).
- The compiler gives a device node its block's y (`PhysicalFluidTopology.java:48`), a pipe-pipe midpoint node the mean y (`:63-64`), and a split run's middle junction the mean of its ends (`:111`).
- A reservoir's owned state is built with `device.position().y()` (`FluidDeviceSpec.java:62`), and the compiler refuses a boundary state whose elevation is not the block's y (`PhysicalFluidTopology.java:52`).
- A tank is therefore a point at its block's y. Nothing in the model knows its height, its footprint or where on it a pipe is attached; the face is lost at compile (`PhysicalFluidTopology.java:56-68`, plan 4.1).

**The connection's column.**
- The hydraulic row is `driving = P_a - P_b - column*g*dz + signedHead`, over `pressureScale` (`PassiveStepSolver:1909`, `:1914-1915`). `dz` is the difference of the two node elevations (`:1889`), `GRAVITY` = 9.80665 (`:16`), and `pressureScale = max(1e5, P_a, P_b)` of the pass seeds (`:1584-1588`).
- The column density:
  - on a pump edge, the suction's transported density at the trial, `tr[a].density` (`:1908`; owner decision D6 of the mixed-gas batch, D4 of this batch);
  - otherwise `headDensities[edge]`, frozen per pass from the seeds: the column that is its own donor, the one nearest its rest point when neither is admissible, the start flow's sign when both are (`:1606-1668`, rationale `:1474-1526`).
- The capped branch reuses the same `driving` (`:1934`, `:1966`).
- Under the plan's ports, the per-end stream density replaces the bulk density in `headDensities` (plan 3.2).

**The column outside the residual.** The same static expression `P_a - P_b - rho g dz` is restated at eleven sites that decide closures, modes and start flows (the full list is in 2.3). That is the surface a level head must reach.

**Energy.** Potential energy is booked at the node elevation:
- the donor's `h + g (z_donor - z_node)` in the Newton targets (`:1838-1839`, `:1849`);
- the reconstruction (`ConservativeTransport.java:256-262`);
- the audit `U + m g z` (`PassiveStepSolver:1317`).

**What a vessel's state exposes.**
- The unknowns per vessel (`PhaseLayout.java:76-84`), in log or ratio form: per component a total amount plus a split `ln(v/l)` when two-phase, liquid and vapour water amounts, ln T, ln P, the water partial pressure, and three solid moments.
- **Volumes are not unknowns.** `liquidVolume`, `waterVolume` and `vaporVolume` are EOS outputs of the decoded state (`FluidThermodynamics.java:415-418`, `PhaseLayout.decode` `:183-212`).
- A vessel's own volume is closed by the row `(state.volume() - V)/V` (`PhaseLayout.java:236`), with V the inventory volume. That V is the vessel's identity (`IslandCertificate.java:334-335`): 1 m3 by default, at most 1000 (`FluidDeviceSpec.java:16`).

## 2. The model addition

### 2.1 Formulation (*proposed*)

- Take the vessel's flash pressure `P` as the pressure at the gas-liquid interface (the headspace).
- A prismatic tank of height H has footprint `A = V/H`.
- The pressure at the bottom exceeds `P` by the weight of the condensed phases over the footprint:

  `h_bottom = g (m_liquid + m_water + m_solids) / A = g m_c H / V`.

This is the task's `rho_l g L` with `L = V_c/A`, written without the density: `rho_l V_c = m_c`. Consequences:
- it is **linear in the decoded condensed amounts**, `m_c = sum_i l_i MW_i + wl MW_w + m_s` (`PhaseLayout.java:184-211`), and depends on T and P only through the phase split;
- it is **exactly 0.0 when no condensed phase exists**, so `P + 0.0` is the same double: a gas-only vessel is bitwise unchanged;
- it is **continuous where a liquid phase appears**: L = 0 there.

The three port positions:
- **Top (VAPOR) port:** `P` (the vapour column over at most H is at most about 12 Pa per metre for nitrogen at 1 atm; ignored).
- **Side (BULK) port:** `P + rho_l g max(0, L - h_port)` in general. Options A to C keep it at `P`, today's value. Only option D adds this term (section 7).
- **Magnitude.** At most `rho_l g H`: 9.77 kPa for 1 m of water at 298 K (996 kg/m3), about 1.5 to 2 % of the default pump rise (500 kPa, `FluidWorldAuthority.java:160`), about 0.10 of `pressureScale` at 1 atm, 0.024 at 4 bar.

### 2.2 Where it enters the residual

The edge row becomes `driving = (P_a + h_a) - (P_b + h_b) - column*g*dz + signedHead`, with `h_end` the head of the port at that end (zero unless the end is a LIQUID port of a vessel). It applies to flow in **both directions**, because it is a pressure, not a withdrawal rule. Inflow through a bottom port works against the head. See section 9: this is a one-sentence amendment to plan 3.1.

**Derivatives, and why nothing new is needed in the Jacobian.**
- `buildSparsity` declares every column of both end nodes in every edge row (`PassiveStepSolver:1676-1682`).
- The structural-zero drop removes non-amount columns only from the node material rows (`materialRows`, `:1694-1703`), never from an edge row.
- The Jacobian is the block sweep: for each local column of a node it re-decodes the node (`:2025-2028`) and re-evaluates every incident `edgeRows` (`:2033-2034`), writing the same entries the coloured sweep of `SparseNewton` would (`SparseNewton.java:31-43`). A head read from the decoded `st[node]` inside `edgeRows` is picked up automatically.
- **No new sparsity entry, no new code in `differentiateEntries`.**
- Plan 3.3's structural-zero exemption (for nodes with non-BULK ports) concerns material rows. The head needs nothing from it, and nothing extra beyond it.

**Cost per residual evaluation.** One sum of condensed mass per LIQUID end. Plan 3.2 already builds the LIQUID stream's mass, so the head is one multiply-add on it. The decode (a PR78 evaluation per node) dominates by orders of magnitude.

### 2.3 Where it enters outside the residual (the real work)

Each of these states `P_a - P_b - rho g dz` from states, not from the residual. Each must use `P_end + h_end`. One helper, `endPressure(graph, pipe, end, state)`, replaces the eleven inline expressions:

| # | site (`PassiveStepSolver`, 618ea63) | what it decides |
|---|---|---|
| 1 | `reopenable` `:161-164` | the boundary-reopen retry of a closed run |
| 2 | carry offer `:312-315` | whether a CLOSED pump is offered its head limit |
| 3 | `demand` `:1080-1083` (pass loop `:433`) | pump HEAD_LIMIT/CLOSED margin |
| 4 | chain start flows `:889-892` | `initialMassFlows` of passive runs |
| 5 | valve start head `:960-962` | regulating valve start point |
| 6 | `initialMassFlow` `:1007-1016` (also the balanced seed `:784`, `:793`) | start flow and cold-start junction seed |
| 7 | `headLimitMassFlow` `:1033-1040` | the reopen start of a pump (8.8 (c) fix) |
| 8 | `closeDeadHeads` `:1238-1241` | start-of-solve closure of one-way runs |
| 9 | `illegalWithEitherDensity` `:1297-1301` | start closure of illegal directions |
| 10 | `headDensities` `:1617-1618`, `:1661` | which column a pass states |
| 11 | `edgeRows` `:1909` | the row itself (section 2.2) |

The approximation probe (`:606-634`) re-evaluates the residual, so it follows. The pump's own rows (`:1970`, `:1974`) and rise limit (`:1058-1063`) read densities, not pressures, and are unchanged.

*Why consistency is mandatory (inferred from review 8.8 (c)).* Take a bottom drain from a tank whose headspace is a few kPa below a void's pressure, while the head exceeds the gap. If site 8 omitted the head, the run would be closed at the start of every step and never drain. Site 1 would not reopen it either, since it would read the same expression. That is the bottling class 3/3' of 8.8 (c), made permanent.

`closeDeadHeads`' argument that a vessel's pressure moves monotonically against the flow delivering it (`:1170-1175`) still holds for `P + g m_c H/V`: receiving liquid raises both terms. So the closure rule stays sound with the head in it.

## 3. Solver cost estimate

**Stiffness.** The head's derivative with respect to condensed volume is `rho g/A` (9.77 kPa/m3 for 1 m3 of water at H = 1 m). Compare it with the vessel pressure's own derivative (*estimate*):

| vessel state (1 m3, water, 298 K) | dP/dV_c of the vessel | head dP/dV_c | ratio |
|---|---|---|---|
| half full, nitrogen cushion at 1 atm | P/V_gas = 2.0e5 Pa/m3 | 9.8e3 | 0.048 |
| ElevatedBlockLine rising tank at rest (0.29 m3 cushion, 354 kPa) | 1.2e6 | 9.8e3 | 0.008 |
| liquid-full (kappa ~ 4.5e-10 1/Pa) | 1/(kappa V) ~ 2e9 | 9.8e3 | 5e-6 |
| no condensed phase | cushion only | 0 (exactly) | 0 |

- The head is a small, linear, smooth addition to a coupling the Newton already resolves.
- It is not stiff against the 5 % state cap either. The cap measures vessel pressure and mass (`PassiveIntervalSolver.java:328-337`), and the head is neither. A draining tank is limited by its mass change, which binds today with or without the head.
- **Draining 1 m3 of water through the default 50 mm block line (*estimate*).** About 5 kg/s at 9.8 kPa of head plus the drain pipe's own column. The 5 % mass cap allows about 50 kg per step, so one or two steps per 5 s slice.
- **Two tanks equalising their levels through a bottom line.**
  - With vents or equal cushions, the end of the approach is laminar. Linearised, it has a time constant `R A/(2 rho g)`, with `R = 128 mu L/(pi D^4)` = 6.5e3 Pa s/m3: about 0.33 s for 1 m of 50 mm pipe.
  - That is stiff against 5 s steps, but backward Euler is L-stable and lands on the rest point monotonically (no U-tube oscillation: the pipes have no inertia term).
  - Today's gas-cushion pressure equalisation between closed tanks is stiffer and already passes the 12 transients at 5 s (8.7 (a), 9.1).

**Interactions** (*inferred* from the cited code and plan sections):

- **Step controller.** No new rejection kind. The head is not in `stateChange` (`PassiveIntervalSolver.java:328-337`). A falling head lowers the drain flow smoothly, so a draining tank's per-step mass change falls. It adds no be-state rejections; it removes some.
- **Throttle and availability band (plan 3.4).**
  - Availability is decided on the phase's volume share; the head does not enter it.
  - The throttle is a constant cap per solve. The head only lowers the flow the cap competes with, and at the reserve (`phi_reserve` 0.005) it is `0.005 rho g H` = 49 Pa for H = 1 m.
  - So the head cannot open or close a port, and it makes a draining port approach the reserve more gently. No chatter mode is added.
  - The plan's own chatter risk R1 is unchanged.
- **Pump suction density, start on HEAD_LIMIT (plan 3.6).**
  - A physical pump's edge starts at its own junction (`PhysicalFluidTopology.java:106`, `:117`), so a tank's bottom head reaches the suction through the passive inlet run (sites 4, 6, 11). This raises the suction junction's pressure: a real NPSH-like effect that suppresses inlet flashing of a saturated liquid, and so the density derate.
  - A pump discharging into a bottom port sees the head in `demand`, the carry offer and `headLimitMassFlow` (sites 2, 3, 7). It closes up to `rho g H` earlier, monotonically as the tank fills: no chatter.
  - The start-on-HEAD_LIMIT comparison uses caps (densities times velocity), not pressures, so it is unaffected.
- **REST/STEADY certificate.**
  - `graphAt` never evaluates the head. It replays inventories linearly and keeps the base states (`IslandCertificate.java:432-445`).
  - A drain's head drift shows up as flow and component drift between consecutive intervals, which `stationarity` already tests (`:196-225`). The horizon is at most `budget/d` intervals (`:282-285`), over which the head moves by at most that fraction.
  - The head is a pure function of state, H and V, with no history. The determinism of `replayStart` (review 8.9 (d)) is kept.
  - Under option C, H must enter `graphIdentity` (`:330-340`) so that a saved certificate cannot outlive a height change. At `5b708fa`, REST and STEADY are one kind; nothing here changes.
- **Energy ledger.** It stays exact by construction, because both sides book the same donor enthalpy. The head's mechanical energy (`g L` per kg, about 10 J/kg per metre, 2.3 mK for water) is not booked. It is of the same order as the tank's internal potential energy, which the model already places at the node elevation.

**Estimate per vessel with n_L bottom ports** (baseline: run 115 / run 137-138: 281-286 Newton solves, 8.5 iterations per solve, 344 ms, 134 MB for 12 cases x 16 s at 5 s; about 1 ms per simulated second of a flowing 5-node island; 50 KB retained per island):

| quantity | today | option B | option C | option D |
|---|---|---|---|---|
| unknowns | layout size (`PhaseLayout.java:76-84`) | +0 | +0 | +0 |
| rows | components + energy + volume + equilibrium + solids | +0 | +0 | +0 |
| structural nonzeros | edge row: both nodes' columns (`:1676-1683`) | +0 | +0 | +0 |
| numeric work per residual | decode-dominated | + one multiply-add per LIQUID end | same | + one EOS-volume term per side port |
| Jacobian evaluations | block sweep | +0 | +0 | +0 |
| Newton iterations per solve | 8.5 | 8.5 +- 0.2 (*estimate*) | same; larger H means larger heads, still linear | + one pass when a level crosses a side port (a kink to freeze per pass, as `headDensities` does) |
| controller rejections | be-state 15 per 12 cases | unchanged | unchanged | unchanged |
| wall | 1 ms per flowing island-second | +0 to 2 % on islands with liquid at a bottom port; exactly 0 on gas-only and flat islands | same | + flat islands with a submerged side port |
| physics-driven work | - | islands that would REST now equalise levels for a transient, then REST | more of them (higher heads) | more still |
| retained bytes | 50 KB per island | +0 (H is a constant) | +8 B per vessel (node field) | +8 B per vessel-port |

The only line that is not near zero is "physics-driven work". A pair of tanks that today rests at equal pressure with unequal levels becomes a flowing transient. It costs as any flowing island does until it settles, then certifies REST again.

## 4. Runtime and GUI cost

| item | B (H = 1 m constant) | C (per-tank H setting) |
|---|---|---|
| where H lives | a solver constant: `h = g m_c * 1 m / V` with V the node's inventory volume. No field: the LIQUID port (plan 3.1, already in `Pipe.Identity` and the certificate digest) selects the ends | a vessel field. On `FluidDeviceSpec` a change re-initializes the stock (`PhysicalRegistry.java:236-238` destroys and re-mints a reservoir on any spec change; `FluidNetwork.java:217` refuses reservoir edits for that reason). So H must live on `PhysicalFluidTopology.Device` (an edit recompiles and keeps the stock, as a pipe diameter does) and on `PassiveNetwork.Reservoir` (16 main-source rebuild sites, which must copy it; 154 test call sites in 47 files keep a default of H = 0 through overloads) |
| default | 1 m, the default block of 1 m3 | 1 m; H = 0 means no head |
| checkpoint / NBT | none | registration field (`FluidCheckpointCodec.java:218-240`) and topology node field (`:372`, reader `:494-497`, `:516`); rides the phase-port format bump (5 at `5b708fa`, so 5 to 6; plan 4.4's "4 to 5" is stale). Fresh world, no migration (AGENTS.md) |
| wire | none (optional one presentation double, below) | `FluidNetwork.Controls` gains `height` with bounds (`:37-45`); a RESERVOIR case in the apply switch (`:210-222`) |
| GUI | optional read-only line on the tank page, inside plan 4.3's outlet lines: "Bottom outlet: liquid, head 7.0 kPa (level 0.72 m)". Computed in `view()` from the committed state (arithmetic only), published on the engine schedule | + an editable "Height (m)" field and an Apply button for the tank. The screen offers none for RESERVOIR today (`FluidDeviceScreen.java:133`, `:138-144`); validation and ledger event |
| certificate | none (the LIQUID port already enters the digest per plan 3.1) | `graphIdentity` gains the height (`IslandCertificate.java:333-335`); the structure checks `IslandCoordinator.java:683` and `ApproximationAnchor.java:49` compare it |
| multi-block tanks | none exist (plan 4.1). When one exists, H = its layer count and A = its footprint, the same formula with no setting | the setting becomes redundant for multi-block tanks |

Option C's structural problem is not code. In a one-block tank, H > 1 m is a fiction the world contradicts: the top neighbour is still at y + 1, and the side ports sit in a 1 m block. The head would claim up to `rho g H` for a vessel whose other ports see 1 m of geometry.

## 5. Test cost

**Head on only at LIQUID ports (B, C).**
- Flat islands have no bottom ports (tank neighbours at the same y are horizontal faces). Gas-only vessels have `h = 0.0` exactly. Code-built science graphs are all BULK (plan 3.1).
- So the exact regression (`SolverRegressionHarness`, code-built), the science suites, `MixedGasJunctionStatic/TransientTest` (gas), `McpGameplayRegressionTest` (flat code-built graph, `:71-76`) and every flat GameTest are bitwise unchanged.
  - GameTests: placements by `east()`, e.g. `FluidPumpStartupGameTests.java:22`, `FluidTopologyGameTests.java:20`, `FluidPumpedFillGameTests.java:23`. `FluidServerBenchmark` has 17 reservoir placements to classify in WP0.

Existing tests that move:

| test | effect | size |
|---|---|---|
| `ElevatedBlockLineIslandTest.elevatedLinesIntegrateFortyIntervalsLikeTheirFlatControl`: the "rising" line, the rising filter line and the rising pump line (the tank is fed through its DOWN face, now a LIQUID port) | the tank rests with `P_tank + g m_w/A = P_gen - rho g LIFT`. About 0.72 m of water, so the headspace is **about 7 kPa lower**: 354 kPa against 360.9 kPa (*estimate*) | 3 assertions, tolerance 4 Pa, re-baselined to the model's rest point; the flat control and the falling line (fed through the UP face, VAPOR) are unchanged |
| `ElevatedBlockLineIslandTest.aLineChargedToItsOwnHydrostaticBalance...`, `aDeadHeadedElevatedLine...` | the tank holds nitrogen only, so the head is 0 | unchanged (verify) |
| `DeadHeadedLineIslandTest` rising cases | the tank stays dry at dead head | unchanged (verify) |
| the plan's own new fixtures 2, 3, 5 (drain, inflow through a closed bottom port, pump from a bottom port) | written after the head, or re-stated with it | part of the plan's WP2/WP3 |

**Head on at side ports (D)** also moves every flat fill whose level passes `h_port`. For example, the pumped fill's landing, stated against the shutoff 601325 Pa (review 8.8 (b) 2b), falls by up to `rho g (1 - h_port)`. That affects `FluidPumpedFillLineTest`, `FilterBlockLineIslandTest`, `FullTankSolidsEventTest`, `FluidPumpedFillGameTests`, and every flat island loses bitwise identity.

**Head off by default** (H = 0 unless set: option C with default 0) churns nothing, but ships a physics nobody sees.

**New tests, B or C** (science fixtures in `fluidScienceTest`, one physical-topology fixture):
1. *Drain to a void with head:* a 1 m3 water/nitrogen tank at `P_void`, bottom port. It drains on the head alone; the level lands at the reserve; the ledger holds to roundoff; at 0.1 s and at 5 s.
2. *Two-tank equalisation:* two closed tanks at equal pressure, levels 0.8 and 0.2 m, joined bottom to bottom at the same y. They rest at `P_1 + g m_1/A = P_2 + g m_2/A` within the Newton tolerance; the approach is monotone (no overshoot at 5 s); then REST.
3. *U-tube / manometer:* the same with the cushions held apart by `dP < rho g H`. The level difference is `dP/(rho g)`. Without the head, all the liquid moves until the source port closes (compare with the head off).
4. *Pump into a bottom port:* it closes `rho g L` earlier than into a side port.
5. *Certified replay bitwise with a slow drain:* the `CausalModuleCoordinatorTest` shape (8.9 (a)).
6. *Physical topology:* face-to-port plus the head on a compiled stack.

**Gas-only guard:** `MixedGasJunctionTransientTest` counters identical to the head-off run.

## 6. Physics value

What the head buys:
- **Level-driven equalisation.** Two tanks at the same headspace pressure and elevation, joined at their bottoms, exchange liquid until their bottom pressures match. Without the head their rest point is equal headspace pressure whatever their levels, so a pair charged at equal pressure never moves. This is the one behaviour that is absent, not just approximate, without it.
- **Manometer behaviour.** A cushion pressure difference below `rho g H` gives a finite level difference instead of an all-or-nothing transfer to the lower-pressure tank until its source port closes. With H = 1 m the range is about 9.8 kPa of water.
- **Gravity drain from a tank.** This already works through the connection's column: a DOWN-face line always descends at least one block, `rho_l g >= 9.8 kPa` with the liquid column of plan 3.2. The level adds up to one more block of head for H = 1 m. It matters where a drain line runs horizontally after its first block.
- **NPSH-like suction head.** Up to `rho g H` more pressure at a pump fed from a tank bottom delays inlet flashing of a boiling liquid. With H = 1 m it is about 10 kPa: marginal but correct in sign. The dominant suction head in a build is the elevation of the tank above the pump, which the column already carries.

**Which H for the one-block tank.**
- At the default 1 m3 the block is a 1 m cube, so H = 1 m, A = 1 m2 is exact.
- At the config maximum of 1000 m3 in one block, no H is honest:
  - H = 1 m gives a 1000 m2 "pancake" whose head never exceeds 9.8 kPa, consistent with the block's height and with the other ports' 1 m geometry;
  - `H = V^(1/3)` = 10 m gives 98 kPa at full, which the world geometry contradicts (section 4).
- Recommended: **H = 1 m for every one-block tank**. Real tall vessels wait for multi-block tanks, where H is the structure's height.

## 7. Options

| | what | engineering (agent-runs) | solver cost | test churn | GUI scope | risks |
|---|---|---|---|---|---|---|
| **A** | no head (plan v1) | 0 | 0 | 0 | 0 | level equalisation and manometer behaviour absent; a player reads "two half-full tanks never balance" as a bug |
| **B** | head at the LIQUID (DOWN-face) port, `g m_c H/V`, H = 1 m fixed | **1-1.25**: one run for the helper at the 11 sites plus fixtures 1-6 and the three re-baselines; 0.25 for the tank-page line inside plan WP5 | 0 unknowns, rows or nonzeros; iterations +-0.2 (*estimate*); +0-2 % wall on islands with a bottom-port liquid, 0 elsewhere | 3 assertions (`ElevatedBlockLineIslandTest`) + 6 new tests | one read-only status line | the 11-site consistency (a miss recreates 8.8 (c) bottling); the 1000 m3 pancake |
| **C** | B + per-tank height setting | **2.5-3**: B + Device/Reservoir field, codec, wire, edit path without re-initialization, screen field, digest, round-trip tests | as B; larger heads mean more transients (not per-solve cost) | B + codec round trip, edit-event and physical-topology tests | tank Apply button and field (new for tanks) | the world contradicts H > 1 m in one block; the first live-vessel edit path (stock must survive, `PhysicalRegistry.java:236-238`); every Reservoir rebuild site must copy H |
| **D** | C + per-side-port height | **4-5**: C + side head with a per-pass frozen submergence switch (a kink at L = h_port), per-face configuration (rejected in plan 4.1), flat re-baselines | + one pass per crossing; flat islands no longer bitwise | every flat fill test and several GameTests | per-face screen | chatter at the submergence line; largest churn; per-face setting |

**Recommendation.**
- **B**, as a small work package after the phase-port WP2 (the head needs the LIQUID port and sits beside the throttle).
- Build WP1's per-end quantities so that every Appendix A site reads a per-end driving pressure through one helper, with a zero offset in WP1. Then B is the offset `g m_c H/V` on LIQUID ends plus the fixtures, and no site is visited twice.
- Not C or D now. C's setting buys heads the one-block world cannot show. D's per-face setting was already rejected in plan 4.1.
- Revisit when a multi-block tank exists: its H is the structure's height and needs no setting. B's formula is the same with A = footprint.

## 8. The measurement that settles the solver-cost question

Run after the WP2/WP3 agent has finished and Gradle is free: one invocation at a time, no dev client, no campaign.

1. **Prototype** on a throwaway branch from the then-current WP head. Put the head offset in the eleven sites of 2.3 behind a test-only constant. Before phase ports exist, apply it to every end of a RESERVOIR node whose neighbour is lower (`dz < 0`), as a stand-in for the DOWN face: that is exactly the future LIQUID set in compiled worlds. The switch is detached before any merge (AGENTS.md cleanup rule; stored under main `tools/level-head-prototype/`).
2. **Runs.** Each with `SolverDiagnostics` on and ThreadMXBean allocation, as run 137/138:
   - `MixedGasJunctionTransientTest` (12 cases x 16 s at 5 s and 0.1 s). **Guard:** counters and the ledger bitwise identical head on against head off (gas-only, `h = 0.0`).
   - `ElevatedBlockLineIslandTest` (40 intervals x 4 lines), head on against off.
   - `DeadHeadedLineIslandTest` and `FluidPumpedFillLineTest` (unchanged in B).
   - The new fixtures 1-3 (drain, equalisation, manometer) at 5 s and 0.1 s.
3. **Counters to compare:**
   - Newton solves; iterations per solve; Jacobian builds; active-set passes per solve;
   - accepted/rejected steps by reason (be-state, newton, be-reopen);
   - reopen retries (the 2.3 consistency check: fixture 1 must show zero reopens, since a bottled drain shows up as reopens);
   - allocated MB; wall ms (ranking only, 5 % noise, 8.7);
   - ledger residual; landing error against the analytic rest points of fixtures 2-3.
4. **Acceptance for "cheap":**
   - gas-only counters identical;
   - on liquid fixtures, iterations per solve within +3 % and no new rejection reason;
   - zero nonconvergence; reopen retries 0 in the drain fixture;
   - certified replay bitwise (fixture 5).
   
   Expected cost: 0.5 agent-run.

## 9. What the phase-port plan needs to accept the head later

No structural rework. The head is one per-end pressure term, and the plan's port model already makes the end the unit of everything else. Three small amendments, cheapest if made before WP1 is coded:
1. **Plan 3.1**, "a port acts only on outflow", should read: the port's *stream* acts on outflow; the port's *driving pressure* (today equal to the node's) acts in both directions. Otherwise a later head would contradict the plan's inflow rule.
2. **Plan 3.2 and Appendix A:** route every site that reads a donor's bulk density for a static column also through a per-end pressure, `P_node + offset(end)`, with the offset 0 in v1. The site list is the one in 2.3 of this review; it overlaps Appendix A's density list, plus `reopenable` `:161-164` and the valve start `:960-962`.
3. **Plan 4.4:** the format bump is now 5 to 6 (WP2 took 5 at `5b708fa`). If C is ever chosen, its node field belongs in the same bump.

## 10. Files read

- Documentation: `PHASE_PORTS_PLAN.md` (all), `DECISION_LOG.md` (this folder); `documentation/2026-09-24-mixed-gas-junction/BE_INTEGRATOR_PLAN.md`; `HANDOFF_REVIEW.md` 8.7 (a)-(b), 8.8 (a)-(c), 8.9 (a)-(f), 9.1.
- Sources at `618ea63` (`src/main/java/com/wormzjl/createcheme/...`):
  - `science/fluid/network/`: `PassiveStepSolver.java` (16, 89-166, 280-345, 425-445, 606-634, 750-800, 876-968, 1000-1106, 1160-1320, 1440-1740, 1760-2080), `PassiveNetwork.java`, `PassiveIntervalSolver.java` (grep, 22-26, 198-211, 328-337);
  - `science/fluid/solver/`: `PhaseLayout.java` (1-260), `SparseNewton.java` (grep);
  - `science/fluid/thermo/FluidThermodynamics.java` (160-180, 405-445);
  - `runtime/fluid/`: `FluidDeviceSpec.java`, `PhysicalFluidTopology.java`, `IslandCertificate.java` (14-30, 150-290, 326-366, 428-465), `PhysicalRegistry.java` (grep, 225-250), `FluidCheckpointCodec.java` (grep, 215-240, 372, 494-516), `FluidWorldAuthority.java` (55-70, 150-170, 294), `IslandCoordinator.java:680-685`;
  - `science/fluid/network/ApproximationAnchor.java:47-50`, `network/FluidNetwork.java` (grep), `client/gui/screens/inventory/FluidDeviceScreen.java` (grep, 130-160), `CreateChemE.java:96-97`.
- Tests at `618ea63`: `ElevatedBlockLineIslandTest` (36-62, 196-230), `McpGameplayRegressionTest` (60-80), `DeadHeadedLineIslandTest`, `FluidPumpedFillLineTest`, `FilterBlockLineIslandTest`, `FullTankSolidsEventTest`, `SolidRuntimeTest` (placement greps), and the `src/fluidGameTest` placements (grep).
- `5b708fa`: commit message and `FluidCheckpointCodec.VERSION` only.

# P6: integration and qualification of the few-compound pilot (gate G6)

Batch `2026-09-24-coolprop-low-temperature`, stage P6 of [UNIFIED_MULTIPHASE_THERMO_PLAN.md](UNIFIED_MULTIPHASE_THERMO_PLAN.md)
(section 5, P6 and gate G6). Written 2026-09-26 on branch `claude/coolprop-multiphase-thermo-37f6b0`, base `4ff68da` (P5
recorded), P6 commits `dd3da29`, `7bf2284`, `ff153fa`, `4d26a45` (section 12). Decisions D1 to D19 of
[DECISION_LOG.md](DECISION_LOG.md); D20 proposed here (section 7). ASCII only. Tools: `tools/p6-pilot-acceptance/`;
research artifacts: `research/2026-09-24-coolprop-low-temperature/p6-pilot-acceptance/` (both in the main checkout).

## 0. Outcome

- **Consumers (section 2).** The V3 column refuses a package that admits crystals with a new typed
  `CrystalPhasesUnavailable` (checked before the spine refusal); the neural binding is classical for such a package and a
  projected inference view refuses it instead of dropping its crystals; the loader refuses crystals declared without a
  reference spine (the network could otherwise have run them fluid-only). Presets cannot reach the column with the pilot
  (no assay) and a pilot network refuses crude-assay presets. The eight bundled packages admit no crystal: every pin is
  unmoved (full suite green).
- **Morphology (section 7, D20 proposed).** An equilibrium crystal is an immobile inventory of its vessel, never
  suspension, filter cake or wall deposit; the inert particles are a separate inventory. The five untested paths now have
  tests; the particle path found a divergent fixed point (heavy particles) and it is now a bracketed search.
- **Bubble-point limit (section 3).** Diagnosed in about 20 minutes and fixed by a pure simplification: the two-phase
  layout of a dry mixture vessel whose converged single phase the check splits is seeded from the engine's UV answer for
  the vessel's own inventory instead of the TP split at the overshot (T, P) (7.6 vessel volumes of vapour). All eight P5
  probe cases now cross (4 to 6 substeps); regression test `LiquidFullBubblePointTest`.
- **Crystal UV warm start (section 4).** Implemented (contained): kernel evaluations of a resting vessel's equilibration
  1,630 -> 229 (vapour-solid) and 494 -> 163 (liquid-solid); warm time 361 -> 53 us and 127 -> 44 us; warm and cold answers
  agree to 1e-10; not applied beside both a vapour and a liquid (measured: no gain on a binary's three-phase line).
- **Runtime budgets (section 4).** Unit rigs through the real coordinator: a pilot closed vessel certifies REST after 3
  slices like its bundled twin (2 slices); a pilot pipe chain costs 5,469 checkpoints per slice on average (bundled 657)
  and meets one numerical hold (a pre-existing zero-flow junction-donor cycle, identical on the P5 sources), recovered by a
  halved slice. In game, crystal vessels under kg/s through-flows ran on soft-budget holds (crystal models have no
  approximate fallback, D18) and one near-pure CO2 vessel was held on the round deadline (section 9).
- **Fresh world and GUI (section 5).** Done through the MCP bridge (`runMcpClient` lane) on a fresh world with the pilot
  selected by a dev datapack: CO2 deposited from nitrogen (1.69 then 12.48 mol) and froze out of liquid methane
  (262.50 mol beside a liquid holding x_CO2 0.10 %), shown on the engine's buckets with the new wording "Bulk withdrawal:
  fluid phases together" and "Fluid volume"; held islands show their reasons; two save/reload round trips carried the
  clock, the islands, a pending configuration event and the crystal inventories.
- **Record text (section 6).** Corrected; text is not hashed (pins green).
- **Gates (section 11).** `test` 1,299 (1,282 before), `fluidScienceTest` 242 (229), `fluidRuntimeTest` 231 (231),
  `fluidSolverRegression` exact chain-100 0.000e+00, 30 of 30 GameTests on a fresh world; P12 and P31 fingerprints
  identical to P3 WP11's.
- **G6 proposal (section 13).** Met at the declared-error grade for the science, conservation, consumer, fresh-world and
  GUI criteria; the runtime criterion is met in the unit rigs and conditionally in game, with four named runtime limits
  for the lead to accept as P7/P8 items or to make blocking.

## 1. The pilot as built

**Engine.** `science.thermo.phase.FluidTpEquilibrium`: translated PR78 with the Soave alpha (D3), constant volume
translation anchored at each component's Tr = 0.8 saturated liquid (D14), E-PPR78 kij(T) from Table S4 in the pilot
package, the tangent-plane stability test deciding phase count, supercritical classification and phase birth, the
declared critical band (four boxes, research-only), liquids and free water (IF97 Region 1, D12) at the state pressure,
TP, PH and UV (bracketed searches over TP), reference spines on the network's sensible datum (D10). The CO2-I crystal is
the Jaeger-Span 2012 Gibbs function anchored at the triple point to this family's liquid with 9019 J/mol (D16, D19).

**Admitted transition models (D17 to D19).** Vapour-liquid, liquid-liquid (typed hold in the network), supercritical as a
fluid regime, deposition and sublimation of CO2-I from and into N2, CH4 and C2H6 gas (`VAPOR_SOLID`), freezing and
dissolution beside a methane-rich liquid or a vapour-liquid split (`LIQUID_SOLID`, `VAPOR_LIQUID_SOLID`), pure CO2
sublimation, melting and triple point, a binary's three-phase step, frost and freezing onsets. Typed holds: two liquids or
three fluid phases beside a crystal, two crystals (`CRYSTAL_PHASES`); the critical band; liquid-liquid and three fluid
phases in the network.

**Active compound set.** N2, CH4, C2H6, CO2 (plus separate free water) in `createcheme:pilot_cryogenic`, selected only by a
test or dev datapack override of `networks/default.json`; the bundled network package is unchanged.

**Achieved current-package T/P expansion (G3, `G3_QUALIFICATION_REPORT.md` section 1, D14/D15).**

| Package, species, phase | Before | Achieved (grade EDE = estimated with declared error) | Exclusions |
|---|---|---|---|
| pilot: N2, CH4, C2H6 liquid and VLE | N2 63.151 to about 115 K at 2 MPa; CH4, C2H6 from 293.15 K | EDE from each triple point to the band edge (N2 119.9, CH4 181.0, C2H6 290.1 K), 0.1 to 10 MPa: Psat <= 1.27 % (Tr 0.6-0.95), saturated liquid worst 2.26 %, VLE K-values at D7 | band boxes (RO); ethane Psat and liquid cp below 150 K declared; vapour cp at Z >= 0.9 declared 12 % |
| bundled `tjl20_methane_nitrogen`: N2 liquid | 63.151-115 K, 2 MPa | EDE 63.151-119.9 K to 10 MPa; no jump at 2 MPa | band |
| pilot: dense and supercritical N2, CH4, C2H6, CO2 | none above 2 MPa | EDE to 10 MPa outside the band (density <= 5.93 %, cp <= 6.60 % except 12 % next to box 1) | band boxes and the D14 corner (RO) |
| bundled: N2, CH4 gas | 2 MPa | to 10 MPa (refused at 10.05 MPa naming the component) | ethane and the cuts keep 2 MPa |
| pilot: N2, CH4, C2H6, CO2 hot gas | 900 K | ideal gas qualified to 1200 K (Cp <= 0.133 %), residual <= 0.024 % at 0.15-0.25 MPa | methane above 1200 K refused typed |
| pilot: CO2 below its triple point | refused | gas to 90 K and methane-rich liquid to 90.69 K under the crystal competition (G4, G5) | see allowlists below |
| bundled: crude cuts (CDU/VDU/coking/hydrotreating) | 293.15-900 K, 2 MPa | unchanged, probes only (F6; D5 VDU bias) | no claim |

**Allowlists and declared errors.** G4 (D18): the CO2-I crystal in dry N2, CH4 and C2H6 gas, 90 to 300 K, 100 Pa to
10 MPa, in finite network vessels; onset G4F1 1.35 K (methane-rich gas), G4F2 0.42 K (N2), inventory and heat by
consistency. G5 (D19): CO2-I beside methane-rich liquids and vapour-liquid splits (solvent at least 90 % CH4, rest N2 or
C2H6) and pure CO2 melting, 90.69 to 216.6 K, to 10 MPa, in finite network vessels; liquidus dataset MAD declared 2.2 K
(per point 4 K), pure melting 0.125 K, three-phase line 1.64 to 2.50 % in pressure. Declared errors of the fluid side:
D14/D15 (vapour density 2.5 %, vapour cp 12 % at Z >= 0.9, compressed liquid 5 %, supercritical cp 12 % next to box 1,
ethane cold liquid cp 12 %, CO2-rich |dy| 0.025); the crystal: D16 (sublimation pressure 3 % from 150 K, melting 0.13 K,
sublimation enthalpy 0.25 %).

**Conservation.** Engine: 1.7e-16 (G5F6), 1.85e-16 (G3 F8); network: CO2 to 1e-12 through deposition, freezing and
back, the engine's UV answer reproduced to 1e-9 (P4, P5 and the P6 tests below); P6's new tests assert species to 1e-12
and energy and volume closures to 1e-8 and 1e-9 on every new path.

## 2. Consumers explicit about phases (deliverable 1)

| Consumer | Before P6 | P6 | Evidence |
|---|---|---|---|
| V3 column (`V3CatalogPropertyPackage`) | refused spine packages (`IdealGasFitUnavailable`); a crystal package without a spine would have run fluid-only | refuses any package with crystals first, typed `CrystalPhasesUnavailable(packageId, crystals, "the V3 column")` | `CrystalPackageConsumersTest.theColumnRefusesAPackageWithCrystalsTyped` (bundled catalog and the pilot override); `SpineNetworkPathTest.theColumnRefusesASpinePackageTyped` now uses the pilot without its crystal list |
| Loader (`MaterialCatalog`) | crystals accepted on a package without a spine; the network ignores them there (`FluidThermodynamics` admits crystals on spine packages only) | refused: "crystals: package X declares crystals without a reference spine, which anchors them" | `...theLoaderRefusesCrystalsWithoutTheirSpine` |
| Neural binding (`V3NeuralRegistry.bind`) and projection (`MaterialCatalog.inferenceView`) | bound by physics fingerprint (a crystal package never matched, by fingerprint only); a projection dropped spines and crystals | binding returns `UNAVAILABLE` for a crystal package (classical initialization); a projection of a crystal package is refused typed | `...theNeuralBindingIsClassicalForAPackageWithCrystalsAndItsProjectionIsRefused` |
| Presets (`MaterialPresets`) | column presets need an assay of their package; network fluid presets are components or physics-sharing assays | unchanged: the pilot has no assay (no column preset can name it) and a pilot network refuses a crude-assay preset ("Fluid preset physics differ") | `...aPilotNetworkCarriesNoCrudeAssayPreset` |
| Material index (`materials-index.json`, `verifyMaterialIndex`) | a list of resource paths | unchanged (lists the crystal record; no physics) | build |
| Network and engine | answer the crystal competition (P4/P5) | unchanged in contract; see sections 3, 4, 7 | P4/P5 tests, section 7 |
| Menus and views (`FluidView`, `FluidDeviceScreen`) | crystal line "(stay)"; "Bulk withdrawal: all phases together"; "Volume"/"Mass" of the mobile phases | "fluid phases together" and "Fluid volume"/"Fluid mass" when the vessel holds crystals | GUI, section 5 |
| Captures | the solver-regression harness is bound to the bundled network package; archived gameplay captures predate crystals (read-only) | unchanged: a pilot checkpoint loaded with the bundled model is refused by the codec (crystal not admitted); no capture path zero-pads or drops crystals | code reading (`SolverRegressionHarness.model`, `FluidCheckpointCodec`) |

No new species is zero-padded anywhere: the only projection that could have (the neural view) now refuses crystal
packages. The eight bundled packages' fingerprints, the neural binding and the presets did not move: the pin tests
(`bundledPackageFingerprintsAreUnchanged`, `LegacyNetworkPathPinTest`, `PilotCryogenicCatalogTest`, the neural registry
tests) are green in the full suite.

## 3. The liquid-full bubble-point limit (deliverable 3)

**Diagnosis** (a temporary pass trace in `PassiveStepSolver`, removed; probe `P6BubblePointDiagnosis`, CH4 + 2 % C2H6,
1 L at 172 K and 5 MPa, -200 W). Interval 4's first substep converges as one liquid at 166.368 K and 1.8989 MPa, below
the bubble pressure (about 2.005 MPa). The outer check splits it, and the seed of the two-phase layout was the TP answer at
that (T, P): liquid 2.94e-4 m3 and vapour 7.32e-3 m3, 7.6 times the 1 L vessel. The two-phase Newton then stopped at its
20-iteration limit (residual 3.6e-5) with its last iterate near the true state (166.483 K, 2.0049 MPa, vapour 1.03e-6 m3);
at small substeps that iterate's vapour is a trace the check reads as one liquid, so the next pass repeats the first
pass's active set: "Phase/device active-set cycle", or the Newton limit, at every refinement (about 500 accepted and 530
rejected substeps, no commit). Pure methane passed because a pure vessel takes the engine's UV coexistence
(`FluidThermodynamics.coexistence`).

**Fix, a pure simplification** (`dd3da29`). `PassiveStepSolver.phaseCorrection`: when a converged vessel in one
hydrocarbon phase is split by the check into a vapour and a liquid, the seed is `FluidThermodynamics.vesselSplit`, the
engine's UV answer for the node's own mobile inventory (amounts, internal energy on the engine's datum, volume), the
mixture counterpart of the pure vessel's coexistence; under a crystal competition the node's frozen crystals are held out
through the new `FluidTpEquilibrium.uvCrystalsHeldOut` (the UV of the fluid answers under the competition's domain,
labelled fluid-only). Dry vessels only (water or inert particles: the TP seed as before); a non-converged or non-VL UV
answer leaves the TP seed. With the fix the seed is 166.483408 K, 2.0049115 MPa, vapour 9.86e-7 m3, and the pass converges.

| P5 probe case (1 L, -200 W) | Before | After |
|---|---|---|
| pure CH4 at 150 K and 172 K | crosses | unchanged numbers |
| CH4 + 3 % CO2, 9 MPa (freezes first) | fails at 158.9 K, 1.89 MPa | crosses; interval 12: 158.08 K, 1.457 MPa, crystal 0.296 mol, vapour 2.3e-3 mol, 5 substeps |
| CH4 + 2 % CO2, 5 MPa | fails in interval 4 | crosses in 4 substeps; crystal from interval 12 (160.48 K) |
| CH4 + 2 % C2H6 | fails in interval 4 (522 cycles) | crosses in 4 substeps, 0 rejected |
| CH4 + 2 % N2 | fails in interval 4 | crosses in 4 substeps |
| CH4 + 0.5 % CO2 | fails in interval 4 | crosses in 6 substeps, 3 rejected |
| CH4 + 2 % CO2, -50 W | fails in interval 14 | crosses; 40 intervals |

A gas mixture cooled through its dew point (CH4 + 10 % C2H6 from 225 K, 3 MPa) passes as before. Regression test
`LiquidFullBubblePointTest` (4 tests): the C2H6, N2 and CO2 cases each commit six intervals with at most 12 substeps, the
vessel crossing inside the run, species conserved to 1e-12; the C2H6 vessel matches an independent engine's fluid-only UV
answer to 1e-9 in T, P and vapour amount; the held-out UV is fluid-only, refused for a fluid-only request, and the
fluid-only contract itself refuses that CO2 below its triple point. Remaining: a wet liquid-full mixture keeps the TP seed
(untested); the step's cost at the flip is one engine UV (15 to 100 TP calls, WP6b).

## 4. Runtime: the crystal UV cost, its warm start, and the scheduler budgets (deliverable 4)

**Warm start** (`dd3da29`): `FluidTpEquilibrium.uv(request, workspace, hintT, hintP)` starts the UV Newton phase at the
hint with at most `WARM_UV_EVALUATIONS` = 8 TP equilibria, then the unchanged cold search; `equilibrateCrystals` passes the
vessel's own (T, P) when it already holds crystals and is dry, except beside both a vapour and a liquid (measured: the
warm attempt only added its budget there, 5,399 -> 7,529 kernel evaluations). Probe `P6CrystalUvCostProbe` (JDK 21.0.11,
16 processors, no game running; cold = first call on a fresh service or model, median of 21; warm = mean of 2000 or 500):

| Vessel (network fixture) | Engine UV cold / warm, us (TP equilibria) | Equilibration at rest before: cold / warm us, kernel evaluations | after | After -100 J before: warm us, kernel evaluations | after |
|---|---|---|---|---|---|
| VS: 10 % CO2 in N2, 0.1 m3, 191.51 K, 1.124 MPa, 1.481 mol crystal | 2229 / 354 (17) | 447 / 361, 1,630 | 98 / 53, 229 | 351, 1,630 | 92, 381 |
| VLS: 2 % CO2 in CH4, 156.41 K, 1.362 MPa, 0.0066 mol | 1647 / 1298 (21) | 1715 / 1202, 5,399 | 1408 / 1231, 5,399 (no hint) | 1287, 5,469 | 1294, 5,469 |
| LS: 3 % CO2 in liquid CH4, 1 L, 166.25 K, 6.54 MPa, 0.039 mol | 158 / 134 (9) | 181 / 127, 494 | 73 / 44, 163 | 138, 494 | 124, 379 |

`CrystalWarmStartTest` (3 tests): warm and cold answers agree in classification, T, P and crystal to 1e-10 at the resting
energy and at +-100 J; the warm start costs less than half the cold kernel evaluations on the VS vessel (228 against 1,629;
380 against 1,629 at +-100 J); a NaN hint is the cold search to the printed digits; a resting vessel stays a fixed point.
Every P4/P5 island test prints its earlier numbers unchanged (for example (b) 163.922352 K, 0.09265646 mol, back to
200.000000002241 K).

**Scheduler counters** (`P6RuntimeBudgetProbe`: the coordinator rig of `CrystalIslandRuntimeTest`, certificates on,
1,200 online ticks; `runtime-budget-probe.txt`):

| Island | Slices | Checkpoints per slice first / mean / max | Worker ms mean / max | Deadlines fired | Holds | Certificate |
|---|---|---|---|---|---|---|
| pilot closed vessel, 10 % CO2 in N2, 170 K, 1 MPa | 3 | 3,403 / 1,444 / 3,403 | 11.7 / 33.0 | 3 | 0 | REST issued (committed 1,200) |
| bundled closed vessel, N2, 170 K, 1 MPa | 2 | 17 / 11 / 17 | 0.22 / 0.34 | 2 | 0 | REST issued |
| pilot chain (warm 10 % CO2 vessel, junction, cold N2 vessel) | 13 | 3,796 / 5,469 / 27,300 | 15.5 / 65.0 | 12 | 1 numerical (tick 700), retried at a halved slice | none; committed 1,150 of 1,200 |
| bundled chain, N2 only | 9 | 820 / 657 / 3,730 | 3.5 / 17.3 | 9 | 0 | STEADY at 45 s |

The pilot chain's hold is "Phase/device active-set cycle": when the two vessels reach equal pressure (1,165,597 Pa) the
junction's frozen donors flip on every pass at zero flow (trace in `chain-probe-trace-head.txt`). `P6ChainProbe` gives the
same interval-by-interval output on the P5 sources of `4ff68da` and at HEAD (interval 8 refused): a pre-existing junction
limit met because the two sides differ in composition, not a crystal or P6 change. The bundled chain of one composition
certifies STEADY instead.

**No benchmark pair.** The fluid-only per-solve path did not change: the new step branch runs only when a converged dry
vessel flips from one phase to vapour-liquid (never on the stress100 fixture, which is wet), the warm start and the
particle search only on crystal packages. Digest evidence: chain-100 exact 0.000e+00, P12 `cfcd4d62b51c0bbd...` and P31
`7ba50d90eb99e585...` equal to P3 WP11's, 30 of 30 GameTests.

## 5. Fresh-world integration and the GUI check (deliverable 5)

**Setup.** Lane `./gradlew runMcpClient --offline` (game dir `run/mcp-client/`, bridge jar SHA-256 `c6cc12c9...` from
another worktree's `run/mods`, the tracked `createcheme_mcp_compat` mixins), Gradle lock held for the client's life, no
suite running. World: a copy of `tools/fluid-in-game-rig/template/bench-template` (void superflat, creative, commands,
no fluid data) as `run/mcp-client/saves/p6-pilot-20260925/`, with the dev datapack `p6_pilot`
(`tools/p6-pilot-acceptance/datapack/`: `networks/default.json` naming `createcheme:pilot_cryogenic`, and the methane,
ethane and carbon dioxide presets; no bundled record touched). Log at load: "Found new data pack file/p6_pilot, loading it
automatically"; "fluid_world status=LOADED format=6 online_tick=0 islands=0". First use of the `runMcpClient` lane by a
Claude agent: clicks focus EditBoxes and typing works, but each typed text lands asynchronously (wait about 1 s) and
select-all is `hotkey {"keys":"ctrl,a"}`, not `ctrl+a` (the compat mixin splits on commas); commands through `bridge.js
chat` need `MSYS_NO_PATHCONV=1` in Git Bash. Reservoirs have no editable temperature or pressure (they start as N2 at
298.15 K and 101.325 kPa, 1000 L), so each scenario flushes a vessel from generators into a fixed-pressure void.

| Sub-check | Evidence (screenshots in `research/.../p6-pilot-acceptance/screenshots/`) | Observed | Verdict |
|---|---|---|---|
| Views arrive on the engine's schedule | 09, 20, 26 (first open), 15-18, 22 | First open: "Waiting for the engine"; reopen: "Last delivered view. Waiting for the engine"; view times advance in bucket steps (572.25 -> 587.25 s; 1028.90 -> 1043.90 -> 1058.90 s) | PASS |
| CO2 deposits from nitrogen (row A: N2 generator 150 K, 300 kPa; CO2 generator 210 K, 250 then 400 kPa; void) | 15, 18 | "Pressure 287.34 kPa", "Temperature 147.79 K", "Fluid volume 999.95 L", "Fluid mass 6.69 kg", "Crystals 1.69 mol, 0.05 L (stay)", vapour N2 99.76 %; with 400 kPa CO2: 212.21 K, 381.85 kPa, "Crystals 12.48 mol, 0.35 L (stay)" | PASS |
| CO2 freezes out of liquid methane (row E: CH4 generator 115 K, 200 kPa; CO2 generator 250 K, 250 kPa; void) | 26, 28 | 123.48 K, 248.48 kPa, "L 11 W 0 V 89 S 0 %", "Crystals 262.50 mol, 7.03 L (stay)"; liquid page 2 "Carbon dioxide 0.10 %" at 123.59 K beside "Crystals 384.62 mol, 10.30 L (stay)" | PASS |
| Withdrawal wording with crystals | 15, 18, 26, 28; 10 and the pre-deposit view of 18 without crystals | "Bulk withdrawal: fluid phases together" and "Fluid volume" / "Fluid mass" with crystals; "all phases together", "Volume", "Mass" without | PASS |
| Crystals are not inert particles (D20) | 27 | the Solids page of the crystal vessel: "Solid material / size / mass" with no rows | PASS |
| A held island shows its reason | 13, 14, 19, 21, 23, 26 | "HELD: soft budget; approximate fallback refused: Crystal equilibria require a full solve" (row E, row A after reload); "HELD: round deadline or worker failure" (row A); "WAITING: configuration event / HELD: Substep refinement exhausted: Negative/nonfinite inventory" (rows B, D) | PASS for display; the reasons are section 9's limits |
| Typed refusals of an input | 06, 12 | "Not applied: Thermo domain: CarbonDioxide at 210210.00 K is above its valid range 90..1200 K (package createcheme:pilot_cryogenic)" (a typing slip); "Not applied: Thermo domain: Water at 150.00 K is below its valid range 273.16..900 K" | PASS |
| Save and reload 1 | log lines 245-269 of `logs/mcp-client-gradle.log`; 19, 22 | "fluid_world status=LOADED format=6 online_tick=14272 islands=3 certificates_saved=1 certificates_restored=1 awake=2"; row B's committed time 480.95 s before and after (View - Lag: 697.30 - 216.35 and 792.30 - 311.35), its configuration event still "WAITING: configuration event"; row A resumed from committed tick 14,255 (its first status line after the load) with its crystal inventory (19.86 mol shown at 14,275 after two short slices) | PASS |
| Save and reload 2 | line 406; 30 | "online_tick=21534 islands=5 certificates_saved=1 certificates_restored=1 awake=4"; row E FULL after reload, 410.06 mol crystal | PASS |

The bit-for-bit round trip of clocks, material (fluid, energy, particles, crystals), fences, fallback allowance, anchor
and last interval is the checkpoint tests' (`FluidCheckpointCodecTest`, P4/P5); the world reloads prove the path in game.
Not exercised in game: a typed phase hold on screen (the planned liquid-liquid row C was dropped after rows B and D held
numerically; `UnsupportedPhasesIslandTest` and `CrystalIslandRuntimeTest` cover the typed statuses through the
coordinator). The dev world stays in `run/mcp-client/saves/p6-pilot-20260925/` (git-ignored); its final checkpoint is
copied to `research/.../p6-pilot-acceptance/world-final/`.

**Manual steps for the owner** (to repeat or extend): as the setup above; place `fluid_generator[facing=east]`, pipe,
`fluid_reservoir`, pipe, `fluid_void` on the platform at y = 101 near (64, 100, 80); configure each generator while it is
still isolated (a held island takes no events); open with `tp @s x.5 102 z.5 0 89` and `use_item`; preset button cycles
Water slurry, Water, Nitrogen, Methane, Ethane, Carbon dioxide from a fresh generator.

## 6. Record texts (deliverable 6)

`4d26a45`: `packages/pilot_cryogenic.json` advisory `CO2_CRYSTAL_JAEGER_SPAN` ("...; read by the engine and the pilot
network (G4, G5); the V3 column refuses it", 245 characters, was "...; no consumer reads it yet"); its `fluid_domain`
evidence names the crystal competition's 90 K floor and drops "in P3"; `properties/pilot_carbon_dioxide.json`
`fluid_domain` evidence no longer calls CO2 below its triple point a research contract. Text is not hashed (package
fingerprint, physics fingerprint and `fluidThermoFingerprint` read numbers only): `PilotCryogenicCatalogTest`, the bundled
pins and `SpineNetworkPathTest` are green in the full suite. The crystal record's texts are current and unchanged.

## 7. Morphology policy (deliverable 2; proposed D20)

**D20 (proposed, 2026-09-26): the morphology of equilibrium crystals in v1.**
- **Question.** May the pilot's equilibrium crystals be mapped into suspension, filters or wall deposits (plan P6)?
- **Evidence.** `CrystalMorphologyPolicyTest` (`ff153fa`, below); the in-game Solids page lists no crystal (section 5);
  no dataset measures CO2-I particle sizes, deposit porosity or deposition rates (P4 survey); plan section 4
  ("equilibrium does not predict nucleation delay, particle size, deposit porosity or blockage rate").
- **Chosen (standing instruction; reversible at P7/P8 with transport evidence).** (1) An equilibrium crystal is an
  immobile inventory of the finite vessel it formed in (D18), in its species, energy and volume balances; no pipe,
  junction, pump, valve, filter or withdrawal carries it; a bulk withdrawal takes the fluid phases only. (2) It is never
  mapped into the inert-particle system (no suspension, filter cake, settled bed, wall deposit or particle size), and inert
  particles never become a crystal; both inventories share the vessel's one temperature (a bracketed search on it). (3)
  With free water or above the crystals' 300 K ceiling the fluid-only answer applies and the crystal sublimes or dissolves;
  water reaching a cold vessel holding a crystal is the typed domain hold of the wet node's fluid-only request. (4) Two
  crystals forming together are the typed `CRYSTAL_PHASES` hold. (5) The reservoir screen shows "Crystals x mol, y L
  (stay)", "Bulk withdrawal: fluid phases together" and the fluid's volume and mass as such.
- **Rejected.** Mapping crystals to suspended or filterable particles (it would fabricate a size and a transport law);
  proportional withdrawal of crystals (no transport model); leaving the rules implicit.
- **Affected milestones.** G6; P7 (any crystal transport or wall-frost gameplay needs its own evidence and decision); P8.
- **Coverage changes.** None: D18's v1 rule made explicit and tested.

`CrystalMorphologyPolicyTest` (6 tests; values from `test-output/CrystalMorphologyPolicyTest.txt`):

| Path | Result |
|---|---|
| Inert particles beside a crystal: 10 % CO2 in N2 at 170 K, 1 MPa, 0.1 m3, plus 10 kg of `demo_particle` (100 um) | deposits 4.820401 of 7.522 mol CO2 at 181.808 K (1.481 mol without particles: the particles absorb latent heat); populations bit for bit; CO2 to 1e-12; energy and volume closures 1e-8, 1e-9; the engine's UV answer for the rest (particles' sensible energy and volume taken out) to 1e-9; rest a fixed point. **Found and fixed:** the plain fixed point on the particles' temperature diverged (the answer went below the 90 K window) once the particles' heat capacity (8 kJ/K) exceeded the fluid's and crystal's; `equilibrateCrystals` now brackets the root of T_UV(E - E_p(T)) - T between the node's temperature and its first answer (false position with the Illinois halving, bisection past the window) |
| Free water: the same deposited vessel plus 40 mol of liquid water at 370 K | fluid-only answer, the crystal sublimed: 299.02 K, 1.866 MPa, 39.87 mol free water; CO2 to 1e-12, energy and volume exact |
| Water into a cold vessel holding a crystal beside a liquid (3 % CO2 in liquid CH4 frozen at 166.25 K; 1e-3 mol/s water) | typed domain hold naming node 1: the wet node's request is fluid-only (D18 item 1), so its liquid's CO2 is refused below 216.592 K ("CarbonDioxide at 166.25 K is below its valid range 216.592..1200 K"); the committed crystal inventory kept |
| Bulk withdrawal from the liquid-solid vessel (2e-4 kg/s for 5 s) | the stream's x_CO2 0.028069 against the liquid's 0.028226 and the vessel's overall 0.030000; the crystal stays (0.039017 -> 0.049717 mol as the expanding liquid cools); CO2 of vessel, crystal and stream to 1e-12; still liquid-full |
| Two crystals: the pilot with a synthetic second CO2 record (CO2-I's Gibbs function anchored with 8875 J/mol, less stable) | the loader admits it; at 170 K both would form: `UnsupportedPhases(CRYSTAL_PHASES)` "two crystals form" naming node 1 (the TP adapter holds too); 0.03 K below CO2-I's onset (192.7548 K) only CO2-I forms, and the vessel carries it alone, equal to the one-crystal pilot's UV answer to 1e-9 |
| Crystal UV refused above the 300 K ceiling (the deposited vessel plus 300 kJ) | fluid-only UV answer: 354.43 K, 2.212 MPa, every crystal sublimed, CO2 to 1e-12; equal to an independent fluid-only UV to 1e-9; the crystal competition's own UV is OUT_OF_DOMAIN there |

## 8. Known limits

| Limit | Status | For |
|---|---|---|
| Liquid-full mixture vessels at their bubble point | fixed for dry vessels (section 3); wet liquid-full mixtures keep the TP seed, untested | P8 |
| Methane-rich liquidus about 2 K cold (solubility about 25 % high) | declared 2.2 K (D19); source the CH4/CO2 kij(T) in cold liquid methane | P7 |
| Dense supercritical N2 carrier (140 to 170 K, 6 to 9 MPa) | answered at a declared 6 K, outside the G4 allowlist | P7 |
| ppm-level CO2 in LNG (liquidus below about 200 ppm) | not admitted (Sampson -4.4 to -6.4 K) | P7 |
| N2-rich solvents, CO2 in ethane liquids, three-phase compositions beyond 12 % / 21 % | not admitted (D19) | P7 |
| Near-pure CO2 vessel with crystals at its sublimation coexistence (other species about 1e-7) | the crystal UV lies in an unresolved step of U(T) (P5 answers exactly pure feeds and pseudo-binaries with two majors); section 9 | P7/P8 |
| Crystal islands have no approximate fallback (D18) | a slow full solve is a budget hold; section 9 | P8 decision |
| Zero-flow junction-donor cycle when the two sides of a junction differ in composition | pre-existing (identical at `4ff68da`); one numerical hold per event, recovered | fluid-network batch |
| Warm water-slurry stream meeting cryogenic liquid methane in a vessel | untyped numerical hold "Negative/nonfinite inventory"; section 9 | fluid-network batch / P8 |
| Dense-fluid transport above 2 MPa, the CO2 viscosities below the triple point | reference-pressure approximations, extrapolated (records r2) | P7/P8 |
| Held islands take no events | the owner's open decision (fluid follow-ups) | owner |

## 9. In-game runtime findings (for the lead)

- **Budget holds of crystal vessels under kg/s flows.** Row E (liquid methane at 200 kPa and CO2 at 250 kPa through a
  1000 L vessel to a void) ran on "HELD: soft budget; approximate fallback refused: Crystal equilibria require a full
  solve", advancing about 50 ticks per 8 s of wall time (committed 20,097 -> 20,397 in 45 s), then FULL after a reload
  (the liquid had drained). Offline, one interval of the saved row E island: 93.7 ms, 174,418 checkpoints, 8 substeps
  (`world-island-probe.txt`); the world's budget is 2 s per interval with 75 % (1.5 s) for the full solve
  (`wallBudgetMilliseconds` 2000), so the holds came from the larger, faster-changing states of the flush.
- **Near-pure CO2 at its sublimation line.** Row A after the N2 was flushed (N2 1.02e-05 %, 229.3 mol CO2 gas and
  19.86 mol crystal at 212.22 K, 381.7 kPa, a 2 kg/s CO2 feed): "HELD: round deadline or worker failure" every 3 s,
  committed time frozen. Offline one interval is refused after 2,736 ms and 7,984,032 checkpoints: 489 accepted and 535
  rejected substeps advance 0.020 s, the rejections "Crystal equilibrium (UV) did not converge: the specified internal
  energy lies in a step of U(T) at the specified volume". A pseudo-pure crystal step (P5's pseudo-binary rule applied to
  one major species with traces) or a fast typed hold for this non-convergence would resolve or type it; neither is done.
- **Water slurry plus cryogenic methane.** Rows B and D (a default water-slurry generator at 298 K left on the same vessel
  as a 115 to 120 K liquid methane generator): "HELD: Substep refinement exhausted: Negative/nonfinite inventory",
  reproduced offline from the saved checkpoint (refused after 26 to 64 ms). Not a crystal path (no CO2 had arrived); the
  bundled network cannot reach it (its methane starts at 293.15 K). Not diagnosed.

## 10. Tooling state

P4: `tools/p4-network-rigs/` (nothing detached). P5: `tools/p5-solid-liquid-scans/` (two JUnit probes never committed,
patch; javac runner and mains). P6: `tools/p6-pilot-acceptance/` (README; the javac runner; `P6CrystalUvCostProbe`,
`P6BubblePointDiagnosis`, `P6ChainProbe` and a copy of P5's liquid-full probe as plain mains; `P6RuntimeBudgetProbe` and
`P6WorldIslandProbe` JUnit, never committed, re-attached by `p6-runtime-probes-against-4d26a45.patch` (`git apply
--check` verified); the gate script; the dev datapack; the bridge helpers with `gen.js`). Staged in the worktree's `tools/`
and copied to the main checkout. The temporary `PassiveStepSolver` traces were removed before any commit
(`git diff HEAD` empty). Nothing left a tracked path in P6, so no removal commit and no CHANGELOG line for detached code.
Every gate-run test of P6 stays: `LiquidFullBubblePointTest`, `CrystalWarmStartTest`, `CrystalMorphologyPolicyTest`,
`CrystalPackageConsumersTest`.

## 11. Gates

Git Bash in the worktree, one Gradle invocation at a time under `build/gradle.lock` (holder `p6`),
`JAVA_OPTS=-Xshare:off`, `--offline`, no dev client (checked); the client ran afterwards, alone, under the lock. Logs:
`research/2026-09-24-coolprop-low-temperature/p6-pilot-acceptance/logs/`, counts in `gate-counts.txt`.

| Gate | Before (P5, `4929e0f`) | After (content of `4d26a45`) |
|---|---|---|
| `./gradlew test` | 1,282 tests, 264 classes | **1,299 tests, 268 classes, 0 failures, 0 errors, 0 skipped** (`test-full-1.log`, 2 min 50 s) |
| `fluidScienceTest` | 229 | **242, 0 failures** (`science-20260925a.log`) |
| `fluidRuntimeTest` | 231 | **231, 0 failures** (`runtime-20260925a.log`) |
| `fluidSolverRegression -PfluidRegressionMode=exact` | chain-100 0 | **chain-100 0.000e+00** on moles, temperature, phase fraction and flow (`regression-20260925a.log`) |
| `runFluidGameTestServer` | 30 of 30 | **All 30 required tests passed**, fresh world `run/fluid-gametest-p6-20260925a` (`gametest-20260925a.log`) |
| P12, P31 fingerprint files | `cfcd4d62b51c0bbd...`, `7ba50d90eb99e585...` (P3 WP11) | identical (`test-output/`) |

+17 tests in `test` (4 + 3 + 6 + 4), +13 in `fluidScienceTest`. The V3 column suites run inside `test` (no separate task
exists); the consumer change touches only the package refusal, and `CrystalPackageConsumersTest` builds a column package.

## 12. Commits

| Commit | Content |
|---|---|
| `dd3da29` | `FluidTpEquilibrium.uvCrystalsHeldOut` and the warm-started `uv`; `NetworkPhaseEngine`; `FluidThermodynamics.vesselSplit` and the warm start in `equilibrateCrystals`; the `PassiveStepSolver.phaseCorrection` branch; `LiquidFullBubblePointTest`, `CrystalWarmStartTest`; the P5 test's outdated comment |
| `7bf2284` | `CrystalPhasesUnavailable`; `V3CatalogPropertyPackage`, `MaterialCatalog` (loader rule, projection refusal), `V3NeuralRegistry`; `CrystalPackageConsumersTest`; `SpineNetworkPathTest` |
| `ff153fa` | The bracketed inert-particle temperature (`crystalUv`, `inertResidual`); `CrystalMorphologyPolicyTest` |
| `4d26a45` | Pilot record texts; the reservoir screen's crystal wording |

Commit attribution: the session line the brief names (`Claude Fable 5.1`), as P5 did.

**Proposed `CHANGELOG.md` `[Unreleased]` line (under Changed):**

- P6, pilot integration and gate G6: liquid-full mixture vessels cross their bubble point (the two-phase layout of a dry
  vessel is seeded from the engine's UV answer for its own inventory, `FluidThermodynamics.vesselSplit`, the crystals of a
  crystal competition held out through `FluidTpEquilibrium.uvCrystalsHeldOut`); a vessel's crystal UV is warm-started from
  its own state (kernel evaluations at rest 1,630 -> 229 vapour-solid, 494 -> 163 liquid-solid); the inert-particle
  temperature of a crystal vessel is a bracketed search (the fixed point diverged with heavy particles); a package that
  admits crystals is refused typed by the V3 column and a neural projection (`CrystalPhasesUnavailable`), bound to no
  neural model, and refused by the loader without a spine; the reservoir screen reads "fluid phases together" and "Fluid
  volume/mass" beside crystals; pilot record texts corrected (text only). Tests `LiquidFullBubblePointTest`,
  `CrystalWarmStartTest`, `CrystalMorphologyPolicyTest`, `CrystalPackageConsumersTest`; the bundled network unchanged
  (exact regression zero, P12/P31 digests, 30 of 30 GameTests). Commits `dd3da29` to `4d26a45`; batch
  `2026-09-24-coolprop-low-temperature`, `P6_PILOT_ACCEPTANCE.md`.

## 13. G6 verdict proposal

| G6 criterion | Status |
|---|---|
| Built engine, admitted transition models and active set pass independent scientific tests | Met at the declared-error grade (G3 F1-F10, G4F1/F2, G5F1-F6; D14 to D19) |
| Conservation | Met (engine 1.7e-16; network 1e-12 in species, 1e-8/1e-9 closures; every P6 path) |
| Current-package and consumer checks | Met (section 2; pins unmoved; bundled network bit-identical in the gates) |
| Cold/warm runtime budgets | Met in the unit rigs (section 4: REST after 3 slices; chain within the wall budget with one recovered hold); conditional in game (section 9: budget holds under kg/s flows, the near-pure CO2 round-deadline hold) |
| Fresh-world integration, GUI verification, round trips | Met (section 5) except a typed phase hold on screen (covered by coordinator tests) |
| Achieved T/P expansion table, exclusions, reproducible report | Met (section 1; this document, the tool folder and the research artifacts) |

**Proposal: G6 met at the declared-error grade, with section 9's runtime limits recorded as P7/P8 items** (the lead may
instead make the near-pure CO2 hold blocking: its fix is a pseudo-pure crystal step or a fast typed hold). D20 is proposed
for the lead's log.

## 14. Open items

**P7:** the CH4/CO2 kij(T) in cold liquid methane (the 2 K liquidus); N2-rich solvents; ppm CO2; the dense N2 carrier;
any crystal transport or wall frost (D20 point 1 stands until evidence); the pseudo-pure crystal step.

**P8 or a fluid-network batch:** crystal islands without an approximate fallback under heavy flows (budget holds); the
zero-flow junction-donor cycle; the water-slurry plus cryogenic-methane numerical hold; wet liquid-full mixtures at their
bubble point; views after a reload arriving only after the island's first bucket (about 20 s while its rounds ran at the
wall budget); held islands taking no events (owner decision); the column's switch to the spine and the neural re-pin
(D9, P8).

**Not done here:** a typed phase hold shown in game (section 5); a benchmark pair (not needed, section 4); the merge,
the version bump, `CHANGELOG.md`, `DECISION_LOG.md`, the plan and the INDEX rows (the lead's).

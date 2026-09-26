# P3 pilot engine: close-out review (WP11)

Batch `2026-09-24-coolprop-low-temperature`, stage P3 of [UNIFIED_MULTIPHASE_THERMO_PLAN.md](UNIFIED_MULTIPHASE_THERMO_PLAN.md),
work package WP11 of [P3_PILOT_ENGINE_PLAN.md](P3_PILOT_ENGINE_PLAN.md) (section 9, WP11 row and the measurement-code list).
Written 2026-09-25 on branch `claude/coolprop-multiphase-thermo-37f6b0` (HEAD `7afa990`, 28 commits over `main` `f9d6be1`;
not merged, not pushed, no `mod_version` bump). Decisions D9 to D15 of [DECISION_LOG.md](DECISION_LOG.md). ASCII only.
Tool folder of this package: `tools/wp11-closeout/` (scripts, probe, every Gradle log, the benchmark report).

## 0. Status

- **P3 is complete on the branch.** Gate G3 was met on 2026-09-25 at the estimated-with-declared-error grade
  ([G3_QUALIFICATION_REPORT.md](G3_QUALIFICATION_REPORT.md) section 11); WP11 adds the two engine follow-ups WP7d left
  (section 2), re-captures chain-100 (section 3), runs every gate (section 4), the benchmark pair (section 5) and
  detaches the measurement code (section 6). Every gate is green before and after the cleanup, with the same executed
  tests.
- **Fresh world required** (section 8): checkpoint format 5, unit format 2, and the network's thermodynamic revision
  moved during P3. No migration, no legacy test (AGENTS.md).
- **Not done here:** the merge, the version bump and the INDEX rows (the owner's and the lead's); no GUI check (no GUI
  code changed in P3; the new hold reasons appear in the island status text the existing menus already show).

## 1. What P3 delivered

| WP | Commits | Delivered | Document |
|---|---|---|---|
| WP1 kernel kij(T) | `43d1081` | `PairInteractions` and the kernel plan `TEMPERATURE_DEPENDENT_PAIRS` (E-PPR78 `k_ij(T)` with first and second temperature derivatives, allocation-free per temperature); `TranslatedPengRobinson` constructors taking the model; the CLASSICAL, rank-one and SPARSE_PAIRS plans bitwise unchanged | [P3_EPPR78_KIJ.md](P3_EPPR78_KIJ.md) |
| WP2 E-PPR78 data | `6ec5c2c` | Record kind `group_interactions` (`eppr78_2022.json`, 40 groups, 355 pairs from the Clapeyron.jl transcription), the interactions `rule`, loader refusals, fingerprints of the pilot only | [P3_PILOT_PACKAGE_AND_SPINE.md](P3_PILOT_PACKAGE_AND_SPINE.md) sections 1-11 |
| WP3 pilot package | `50dfe31` | Bundled `createcheme:pilot_cryogenic` (N2, CH4, C2H6, CO2 records without Cp fits, Tr = 0.8 `volume_translation` anchors from the oracle, spines with CO2 extended to 90 K, the research-only CO2-I crystal, transport tables); `ideal_gas_cp` optional under D6's package rules | same |
| WP8 GERG-2008 fixture | `eb1ed10` | `pilot-binaries.json`: 171 bubble and 190 dew points of the six pilot binaries, the parameter comparison with `GERG2008.cpp` | [P3_REFERENCES_WP8_WP10.md](P3_REFERENCES_WP8_WP10.md) part A |
| WP10 methane ideal gas | none (research; D13) | ExoMol line-list reference Cp; GERG-2008 ideal part chosen above 425 K | same, part B |
| WP6a engine TP | `f5ffcf8` | Newton finish, declared critical band, merging, PIP labels, free water in the engine, per-branch stability; the P2 near-critical failures converge | [P3_EQUILIBRIUM_ENGINE.md](P3_EQUILIBRIUM_ENGINE.md) sections 0-11 |
| WP4 direct liquid path | `959ea0d` | Liquids and liquid water (IF97 Region 1, metastable admission to psat - 2 MPa, D12) evaluated at the state pressure; `REFERENCE_PRESSURE`, `GlobalLiquidResponse` and the `liquidCompressibility` option removed; checkpoint format 5, unit format 2 | [P3_DIRECT_LIQUID_PATH.md](P3_DIRECT_LIQUID_PATH.md), [P3_WP4_RUNTIME_DIAGNOSIS.md](P3_WP4_RUNTIME_DIAGNOSIS.md) |
| WP5 spine wiring | `e4d355f` | The pilot's network model on its spines (sensible datum with formation offsets, D10), E-PPR78 pairs and anchors; `ThermoIdentity` as the network revision of spine packages; the column refuses spine packages; the legacy path bitwise | P3_PILOT_PACKAGE_AND_SPINE sections 12-19 |
| WP6b PH and UV | `f116a78` | Bracketed PH and UV over TP answers with the pure-coexistence and free-water branches | P3_EQUILIBRIUM_ENGINE "WP6b" |
| WP9a spine r2 | `271d84a` | CEA gas constant 8.31451 in the CEA segments; methane's GERG-2008 ideal-gas segment 425-1200 K (D13) | P3_PILOT_PACKAGE_AND_SPINE sections 20-27 |
| WP7 network on the engine | `8e5274e` | `flashTP` an adapter over the engine (`NetworkPhaseEngine`), `vaporBranch` gone, `CriticalBandHold`, pure-vessel coexistence through UV, 10 MPa for N2 and CH4 in the bundled network, the two WP4 runtime fixes (trace-water flash in the engine, pump column from the suction density) | P3_EQUILIBRIUM_ENGINE "WP7" |
| WP7b | `aed9a9b`, `181302e` | Fixed-point start of an unsaturated gas's partial pressure (six-tank first slice 214,660 to 36,961 checkpoints); the 10 MPa phase tests; legacy digest re-printed | "WP7b" |
| WP7c | `f2d5221` | One-root label liquid-like only when PIP > max(1, Z) (hot dilute gases back in the vapour slot) | "WP7c" |
| WP9b G3 families | `4eec712` | F1 to F10 as gate tests (`science.thermo.qualification`, 30 tests) | [G3_QUALIFICATION_REPORT.md](G3_QUALIFICATION_REPORT.md) |
| WP7d | `774cb82` | Four engine defects fixed (unresolved stability retried on ten times the budget, reused-workspace amounts zeroed, UV past 10 MPa typed out of domain, `LIQUID_LIQUID`), band boxes 2 (widened, D15) and 4 (D14 corner), D15 bounds in the fixtures | "WP7d"; G3 report section 11 |
| WP11 | `a3716ed`, `ac6ebc1`, `8a10bfd`, `7afa990` | Liquid-liquid volume guard and typed phase holds (section 2); chain-100 re-capture (3); gates (4); benchmark (5); detachment (6); this review; CHANGELOG | this document |

## 2. WP11 engine follow-ups (`a3716ed`)

**(a) Liquid-liquid only below the cubic's critical volume.** A split is `LIQUID_LIQUID` when both products are
liquid-like by PIP > max(1, Z) **and** both have `v < 3.9513730355914 b` on their own untranslated roots:
`PhaseIdentification.VAPOUR_BRANCH_VOLUME_RATIO` (PR78's `Z_c / Omega_b`, the constant of the retired `vaporBranch`
heuristic and of the P1 probe's single-root branch test), `denserThanCriticalVolume` and `denseLiquidLike`, used by
`FluidTpEquilibrium.finishSplit`. Tests (`FluidTpEquilibriumP3Test.aDenseGasBesideALiquidIsVapourLiquid`): N2/C2H6 at
195 K and 10 MPa (x_N2 0.35, 0.55, 0.85) and CH4/CO2 at 245 K and 8 MPa (x_CH4 0.45, 0.55) are now `VAPOR_LIQUID`
outside the band, each case asserting its premise (the lighter phase liquid-like by the parameter and above the volume
ratio), so the guard is what decides; the F7 CO2-in-methane splits (91, 95, 100 K), N2/C2H6 at 95 K and 2 MPa and the
synthetic `k_ij = 0.3` binary stay `LIQUID_LIQUID`, now with both phases asserted below the ratio (their lighter liquid
has v/b about 1.1); `G3F7` unchanged and green. Limit: the ratio is a rule, not a physical gap (WP7d measured the
lighter phase's v/b continuous from 1.1 to 4.4 over the survey's liquid-liquid answers); splits with the lighter phase at
v/b 3.1 to 3.8 at 145 to 160 K and 6 to 9 MPa (N2/C2H6) stay liquid-liquid and are held.

**(b) Typed holds for unsupported phases.** `FluidThermodynamics.PhaseHold` is the common base of `CriticalBandHold` and
`UnsupportedPhases`: the solver names the node at the same four catch sites that named the critical band's
(`PassiveStepSolver` seeds, junction restatement, outer check, pure-vessel coexistence), and `PassiveIntervalSolver`
counts the rejection under the hold's own `reasonKey()`, so an interval that cannot pass it is held on the existing hold
path with the reason in the island's status (before WP11 a liquid-liquid answer was a plain rejection under a
numbers-stripped message key). `UnsupportedPhases` has two kinds, each with its key: `LIQUID_LIQUID`
(`unsupported-phases: a liquid-liquid split the network does not carry`) and `THREE_PHASES`
(`unsupported-phases: a third fluid phase the network does not carry`). The engine now prefixes the detail of a split
whose product the stability re-check proved `UNSTABLE` with `EquilibriumResult.UNSTABLE_PRODUCT`; the adapter turns that
into `THREE_PHASES` (inside the band `CRITICAL_BAND` comes first and the band's hold applies).

The brief's three-phase state, N2/C2H6 at x_N2 0.95, 125 K and 3 MPa, is at exactly the band's pure-fluid threshold
(one component at least 0.95: nitrogen at Tr 0.99, Pr 0.88, box 1), so the engine types it `CRITICAL_BAND` and the
network holds it as the band's. WP7d's survey found it outside the band because its composition grid accumulated
x = 0.9499999999999998. The same failure outside the band is x_N2 0.93 to 0.9499 at 125 K and 3 MPa
(`tools/wp11-closeout/out/three-phase-probe.txt`); the tests use 0.94. Tests: `FluidTpEquilibriumP3Test.anUnstableProductIsTheTypedThreePhaseIndication`
(0.93 and 0.94 prefixed `UNSTABLE_PRODUCT`, negative product tangent-plane distance; 0.95 `CRITICAL_BAND` first);
`NetworkPhaseEngineTest.aLiquidLiquidSplitIsRefusedTypedByTheNetwork` (kinds, keys, `at(node)`, the 0.95 state a
`CriticalBandHold`, the dense gas at 195 K and 10 MPa in both slots); the new `UnsupportedPhasesIslandTest`: two closed
0.1 m3 tanks on the pilot network charged as one liquid, held with the `LIQUID_LIQUID` key (95 K, 2 MPa), the
`THREE_PHASES` key (x_N2 0.94) and the critical band's key (x_N2 0.95), each message naming node 11; the control, the
CH4/CO2 dense gas beside a liquid at 245 K and 8 MPa, commits its 5 s interval with both slots.

Runs on `a3716ed`'s content: `science.thermo.*` 128 tests (2 skipped probes), `fluidScienceTest` 220 (1 skipped),
`fluidRuntimeTest` 227, all green; no pin moved (`LegacyNetworkPathPinTest`, `SpineNetworkPathTest` unedited); no
revision string moved (a label and refusal change on a fresh world, as WP7c and WP7d).

## 3. chain-100 re-capture (`ac6ebc1`)

The reference was last captured at `3c84036` (2026-09-23; `fca6a15` before it moved the fixture to the configured
basis); the harness's switch is `-PfluidRegressionCapture=true` (`fluid.regression.capture`). Sequence, each a separate
Gradle run: declared mode against the old reference (fails as expected, the deviations below), capture, exact mode and
declared mode against the new reference (both 0.000e+00 in state/moles, temperature, phase fraction and flow). The
island fixtures `quiet-11312`, `quiet-11324`, `cold-11312` are skipped (no `build/probe` snapshot); their stale `154007d`
references are untouched.

Deviation of the new reference against the old one (100 nodes, 99 pipes, one 5 s interval; max with the harness's
metrics, mean from `tools/wp11-closeout/chain-deviation.js` over the two files):

| Quantity | max | mean | where | declared gate |
|---|---|---|---|---|
| component moles (relative, 2,100 entries) | 2.872e-5 | 2.844e-5 | node 95, moles[17] | 1e-6: exceeded |
| mass (relative) | 2.872e-5 | 2.844e-5 | node 95 | 1e-6: exceeded |
| pressure (relative) | 4.470e-7 | 1.806e-7 | node 99 | 1e-6 |
| temperature (K) | 7.464e-6 | 3.813e-6 | node 99 | 1e-4 K |
| liquid volume fraction (absolute) | 4.393e-5 | 4.352e-5 | node 100 | 1e-6: exceeded |
| vapour volume fraction (absolute) | 4.356e-5 | 4.316e-5 | node 1 | 1e-6: exceeded |
| water volume fraction (absolute) | 3.680e-7 | 3.611e-7 | node 100 | 1e-6 |
| liquid volume (relative) | 1.026e-3 | 1.025e-3 | node 95 | - |
| vapour volume (relative) | 4.553e-5 | 4.509e-5 | node 100 | - |
| water volume (relative) | 9.323e-4 | 9.306e-4 | node 100 | - |
| vessel volume (relative) | 5.996e-12 | 1.690e-13 | node 9 | 1e-6 |
| pipe flow (relative to max(q, controller floor)) | 2.039e-4 | 1.002e-4 | pipe 62 (2.78e-6 kg/s) | 1e-3 |
| substeps accepted / rejected | 37 / 1 | | | equal |

These are WP4's numbers (P3_DIRECT_LIQUID_PATH.md section 6) and WP7's (P3_EQUILIBRIUM_ENGINE WP7.6) to the printed
digits: chain-100's interval never calls the flash, so nothing after WP4 moves it. **Explanation:** WP4's first-order
check (P3_DIRECT_LIQUID_PATH.md section 6): the liquid volume deviation is `(kappa_EOS - k)(2 MPa - P)` plus the anchor
term (cut translations anchored at their own 288.7 K and 101,325 Pa) plus the inventory term 2.84e-5 (the fixture fills
each 1 m3 vessel, and the liquid is now the EOS's at 150 kPa), predicted within 4.50 to 4.51 % of the observed deviation
on every node (criterion 10 %); free water by Region 1's own compressibility within 5.1 %. Counters of the final
declared run: Newton iterations 530 (pre-P3 `5100233`: 529), backtracks 14 (14), residual evaluations 621 (620),
Jacobian builds 24 (24), block fallbacks 0, allocated 1033.5 MB (1146.5 MB, -9.9 %; budget +10 %), wall 1194 to
1221 ms (one cold interval, not evidence).

## 4. Gates

All from Git Bash with `JAVA_OPTS=-Xshare:off --offline`, one at a time under `build/gradle.lock` (tag `wp11`), no dev
client (checked before each run), 2026-09-25 07:37 to 07:54 local; logs in `tools/wp11-closeout/out/gradle-logs/`.

| Gate | Before the cleanup (`ac6ebc1`) | After the cleanup (`8a10bfd`) |
|---|---|---|
| `test` (whole suite) | 253 classes, 1,229 tests, 0 failures, 0 errors, 3 skipped (the three env-gated probes) | 250 classes, 1,226 tests, 0 failures, 0 errors, 0 skipped |
| of which `science.thermo.*` | 128, 2 skipped (after the follow-ups, `a3716ed`) | 126, 0 skipped |
| `fluidScienceTest` | 220, 1 skipped (`a3716ed`) | 219, 0 skipped |
| `fluidRuntimeTest` | 227, 0 failures (`a3716ed`) | 227, 0 failures |
| `fluidSolverRegression` (exact) | chain-100 0.000e+00 on all four quantities; islands skipped | same |
| `fluidSolverRegression` (declared) | chain-100 0.000e+00 | (exact implies it) |
| fluid GameTests, fresh world | `runFluidGameTestServer -PfluidGameTestRunId=wp11-p3-20260925`: All 30 required tests passed | `-PfluidGameTestRunId=wp11-p3-after-cleanup-20260925`: All 30 required tests passed |
| P12 `WorkerTrajectoryEquivalenceTest`, P31 `CadenceTrajectoryQualificationTest` | green (in `test` and in a targeted `fluidRuntimeTest` run); fingerprint files SHA-256 `cfcd4d62b51c0bbd...` (P12; FIXED_ONE, FIXED_TWO and AUTOMATIC_TWELVE all `dfe6e1dd...`) and `7ba50d90eb99e585...` (P31), equal in both runs | green; the same two SHA-256 |

The executed tests are the same before and after: 1,226 in `test`, 219 in `fluidScienceTest`, 227 in
`fluidRuntimeTest`; only the three skipped probes left (they never ran in a gate). Nothing outside this batch failed.
The P12/P31 hashes differ from those recorded at the 0.4.0 merge (F3: `56332b64...`, `4dcb80a4...`) because P3 changed
the network's thermodynamics; they are reproducible on this branch (three runs, same bits).

## 5. Benchmark pair (one run, 60 s warm-up + 60 s window)

`fluidServerBenchmark` has no pumped-fill scenario, and the `rest1000` numbers the brief cites (0.12 cores, engine
0.011 ms per tick, no GC) come from the in-game rig (`tools/fluid-in-game-rig/`, 1,000 resting lines on a dedicated
server, scheduling batch WP5), not from this harness: that scenario is not comparable. The comparable run is the
fluid-followups batch's paced `stress100` (the harness's through-flow ladder profile, F1, `stress100-f1-on-r02` at
`7f933ff`, 60 s + 60 s, 12 automatic workers, `-PfluidStressProfile=true`, rest detection on with eps_s 1e-7). The same
command at HEAD:

    fluidServerBenchmark -PfluidBenchmarkRunId=stress100-p3-wp11-r01 -PfluidBenchmarkProfile=stress100 -PfluidBenchmarkWorkers=0
      -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60 -PfluidStressProfile=true

**Window:** run `stress100-p3-wp11-r01`, 2026-09-25, on `ac6ebc1` (the engine of `a3716ed`), fresh world
(`fluid_world status=LOADED format=5`), test started 07:46:15 local, warm-up 60 s, window about 07:47:15 to 07:48:15
local (measured 60.05 s); runtime audit PASS, integrity passed, 0 held intervals in the window (3 at start-up, as F1).
Machine: 22.2 GB free, no `Endfield.exe`, no dev client, idle CPU about 2 % before the run; JDK 21.0.11.

| measure (window) | F1 `stress100-f1-on-r02` (0.3.0 + F1) | HEAD `stress100-p3-wp11-r01` |
|---|---|---|
| full solves / certificates issued / replayed intervals | 1028 / 40 / 58 | 1029 / 44 / 62 |
| worker ms p50 / p95 / max | 2.51 / 6.46 / 61.3 | 2.49 / 7.24 / 52.9 |
| ready to publication ms p50 / p95 | 15.7 / 58.6 | 14.9 / 58.2 |
| engine ms per tick p50 / p95 / max | 0.0069 / 0.0448 / 16.9 | 0.0096 / 0.0604 / 9.88 |
| whole tick ms p50 / p95 / max | 0.145 / 0.327 / 3.30 | 0.199 / 0.389 / 3.05 |
| substeps per interval p50 / p95 / max | 1 / 1 / 7 | 1 / 1 / 7 |
| component / energy balance units | 3.43e-7 / 3.06e-10 | 3.26e-7 / 5.40e-10 |
| realtime ratio (incl. certified) | 0.857 (1.013) | 0.857 (1.016) |
| process CPU, GC (JFR) | not kept | 0.138 cores (user 0.106, system 0.032); 3 young collections (8.8, 8.6, 11.1 ms pauses) |

Reading: the solver side (worker time per solve, solves, substeps, certificates) is unchanged within one run's noise by
P3's direct liquid path and engine outer check on this wet TJL + nitrogen fixture. The server-thread engine share and
the whole tick are higher at HEAD (p50 +0.003 ms and +0.05 ms); the two builds differ by F2 to F4 (topology event
batches, checkpoint format 4 and 5 storage, the domain hold) as well as P3, and one run each is not evidence of a cause
(no repeats, benchmark rule). The pumped-fill cost of P3 is measured off-line (WP7/WP7b: the six-tank first slice
2,084,304 to 36,961 checkpoints) because the harness has no such profile. Report, JFR and comparison:
`tools/wp11-closeout/out/bench/`.

## 6. Tooling cleanup (`8a10bfd`)

Detached (removed from the tracked tree; each folder holds the source as of `8a10bfd^`, byte for byte equal to
`git show 8a10bfd^:<path>`, a `reattach.patch` that `git apply --check` accepts at `8a10bfd`, and a README with purpose,
batch, removing commit and how to run):

| Folder | Removed path | Gate status before |
|---|---|---|
| `tools/stability-cost-probe/` | `src/test/java/.../science/thermo/TangentPlaneStabilityCostTest.java` (P1, `CREATECHEME_STABILITY_COST`) | skipped in every gate |
| `tools/phase-cost-probe/` | `src/test/java/.../science/thermo/phase/FluidTpEquilibriumCostTest.java` (P2, P3 WP6a/WP6b rows, `CREATECHEME_PHASE_COST`) | skipped |
| `tools/wp4-chain-deviation-probe/` | `src/test/java/.../science/fluid/network/DirectLiquidChainDeviationProbe.java` (P3 WP4, `CREATECHEME_WP4_CHAIN_PROBE`; to reproduce WP4 it needs the `3c84036` reference back, README) | skipped |

`grep -rn EnabledIfEnvironmentVariable src/test` finds nothing after the removal; the batch added no Gradle task,
property or run configuration. Kept (gate tests, fixtures, harnesses): the qualification families F1 to F10, the pin
tests (`LegacyNetworkPathPinTest`, `SpineNetworkPathTest`, `PilotCryogenicCatalogTest`), the oracle
(`science.thermo.reference`) and its CoolProp fixtures, the GERG-2008 fixture, `LegacyLiquidPath` (used by the gate
`DirectLiquidContinuityTest`), `NearCriticalNitrogenIslandTest`, `UnsupportedPhasesIslandTest`, and the solver
regression harness.

Never-tracked material of the batch (git-ignored in the worktree's `tools/`, for the lead to copy to the main checkout
with `tools/INDEX.md` rows): `coolprop-parity/` (P1 oracle parity fixtures), `tangent-plane-stability-scans/` (P1),
`spine-join-measurement/` (P2, WP9a), `phase-equilibrium-scans/` (P2, WP6a), `eppr78-transcription-check/` (WP2),
`pilot-volume-anchors/`, `pilot-transport-tables/` (WP3), `gerg-bubble-points/` (WP8), `methane-ideal-gas/` (WP10),
`wp4-runtime-diagnosis/` (WP4), `wp5-legacy-digest/` (WP5), `wp7-network-integration/` (WP7),
`wp7b-engine-fixed-point/` (WP7b), `wp7c-hot-gas-label/` (WP7c), `g3-qualification/` (WP9b), `wp7d-engine-defects/`
(WP7d), `wp11-closeout/` (WP11). Together with the three detached folders above, these twenty are every `tools/*`
folder of the batch; each has a README.

## 7. Decisions D9 to D15

| Id | Date | Question | Chosen | Affected |
|---|---|---|---|---|
| D9 | 2026-09-24 | P3 package strategy | A separate bundled pilot package `createcheme:pilot_cryogenic` (own records, anchors, spines, crystal, E-PPR78, CO2), selected by a test or dev datapack override; formulation changes shared by every network package; the bundled network extended through `fluid_domain` only (N2 and CH4 to 10 MPa) | WP3, WP4, WP7; P6 projection, P8 re-pin |
| D10 | 2026-09-24 | Energy datum of the network | Sensible datum kept; spine packages carry formation offsets in their `EnergyReference` | WP5 |
| D11 | 2026-09-24 | Hydrogen pairs and the Xu et al. 2015 cut rule | Moved to P7; the cuts keep constant kij | P3, P7 |
| D12 | 2026-09-24 | Liquid water on the direct path | IF97 Region 1 at the state pressure, metastable liquid admitted to psat - 2 MPa and flagged (IAPWS R7-97 section 5.1 qualitative only) | WP4, WP7 |
| D13 | 2026-09-24 | Methane ideal-gas source above the CoolProp segment | GERG-2008 ideal part (Jaeschke and Schley 1995) from 425 to 1200 K, ExoMol-derived reference only as a holdout; CEA rejected | WP9a, G3 |
| D14 | 2026-09-25 | Four D7 rows the translated cubic misses | Declared errors: vapour density Z >= 0.8 2.5 %, vapour cp near saturation 10 %, liquid Tr 0.90-0.95 6 %, corner Tr 1.0-1.1 x Pr 1.5-2 into the band (9 %) | WP9b, G3, G6 |
| D15 | 2026-09-25 | Six rows over their bounds after the fixture families | Declared errors: vapour cp 12 % (2 % kept at Z >= 0.98), compressed liquid Tr 0.85-0.9 5 %, supercritical cp next to box 1 12 %, ethane liquid cp below 150 K 12 %, CO2-rich K-value dy 0.025; band box 2 widened to Tr 0.97 below Pr 0.8; the four engine defects fixed first | G3 (met after WP7d), G6 |

WP11 took no new decision. It applied the lead's WP7d follow-ups as briefed; the one reading of the brief it had to make
is recorded in section 2 (the x_N2 0.95 state lies at the band's threshold; the three-phase case is tested at 0.94).

## 8. Fresh-world statement

A world saved before P3 is refused at load with the existing "Create a fresh world for this development build" message:
the checkpoint format moved from 4 to 5 and the island unit format from 1 to 2 (WP4: the global liquid compressibility
left the package key, the package table and the unit), and the network's thermodynamic revision moved (WP4 tag
`direct-liquid-v1` replacing `fluid-shared-k-v1 ... k=`; WP7 `FORMULATION` `direct-liquid-v1:tp-engine-v1`; WP7 fluid
thermodynamic fingerprint of the bundled network `f5e0178e...` to `95cd8e6d...` through the 10 MPa `fluid_domain`;
the pilot's spine fingerprint moved once in WP9a). The `liquidCompressibility` server-config entry is gone. No migration
and no legacy test exist (AGENTS.md). The eight bundled column packages' physics fingerprints and scientific revisions,
the neural binding and the column pins did not move (D9). Both GameTest runs and the benchmark ran on fresh worlds
(`format=5` in the benchmark log).

## 9. Known limits and open items, by the stage that takes them

**P4 (solid-gas):** the CO2-I crystal is research-only and refuses evaluation (Jaeger and Span 2012 or Giauque and Egan
calorimetry needed); CO2 below 216.592 K is refused in production and evaluated only on a research contract.

**P5 (solid-liquid, full phase competition):** three phases. The network carries one hydrocarbon liquid and one vapour;
a liquid-liquid answer and a three-phase indication now hold the island with a typed reason (section 2) but are not
answered. The engine has no three-fluid-phase split and no liquid-liquid slot in the network. The volume guard's 3.9514 b
is a rule on a continuum (section 2). F7's CO2-in-methane fluid states and fugacities are the fluid side of P5's
solid-liquid condition (research-only); the GERG-2008 comparison of CO2 in liquid methane below 216.6 K was not done.

**P6 (integration and G6):**
- PH/UV warm start: a two-phase UV from a cold start costs 15 to 102 TP calls (WP6b); a warm-start hint in the request
  or analytic equilibrium sensitivities are needed before UV serves the inventory refresh per node (today only the
  pure-vessel coexistence regime calls it).
- Water spine: free water stays on the network's sensible reference; the formation-datum basis covers the package
  components only, and a wet spine state's PH/UV would mix data (the network never calls it); a reaction step with water
  needs a water spine.
- Dense-fluid transport: viscosity is a reference-pressure approximation; above 2 MPa dense or supercritical states take
  dilute or saturated-liquid values, and a supercritical component in the liquid slot takes its dilute vapour value
  (WP7, revision tag `supercritical-liquid-slot-dilute-v1`); declared unqualified (G6 question).
- The pre-existing generator-fill stall: a vessel filled from a generator to exactly the generator's pressure can stall
  near zero flow ("Newton iteration limit", N2 at 1.5 to 2.0 MPa and 298.15 K); it reproduces on `f116a78` with the
  Wilson flash, so it is not a P3 regression; the test islands fill through a narrow pipe.
- The pilot's network behaviour on the bundled network path (D9: column-to-network projection or the column switch),
  the gameplay selection of network configurations (`MaterialPresets` allows one), the GUI check through the MCP bridge.
- The hot wet gas at or below water's saturation pressure still bisects its partial pressure (about 27 hydrocarbon TPs;
  WP7b's fixed point runs only above it).
- Critical-band holds are reached by construction only; no tested default-engine network state fails inside the band.

**P7 (rollout):** Table S4 of Jaubert et al. 2022 (the owner's browser fetch) for the E-PPR78 provenance (today a
third-party transcription validated functionally against GERG-2008); the Xu et al. 2015 cut rule and the hydrogen pairs
(D11); methane's ideal gas is unavailable above 1200 K (typed refusal; the line list is uncertain beyond); the D5 VDU
K-value bias of the cuts (declared, VDU-WP0); n-butane, n-pentane and n-decane isotherms to 10 MPa need a reference
above 1.1 MPa (F6 reported, no claim).

**P8 (release):** the neural and column re-pin: the shared records did not move in P3 (D6, D9), so the column's switch
to the spine, the fixture re-recording and the neural eligibility decision are P8's; the stale island fixtures of
`fluidSolverRegression` (`quiet-11312`, `quiet-11324`, `cold-11312`, `154007d` references) need a current-basis stress
snapshot or deletion; the benchmark's engine-per-tick difference of section 5 can be attributed only by a pair on one
code base.

**Carried notes:** the WP7c label rule rests on `dB/dT > 0`, which holds for the bundled components to 1388 K (above
every domain's 1200 K); `TangentPlaneStabilityTest.bruteForceBranchMinimum` uses the one-argument vapour test (cool
states, both rules agree); UV coexistence answers stop within 1e-11 T of the root (tolerance 1e-10 in the test).

## 10. WP11 commits

| Commit | Content |
|---|---|
| `a3716ed` | Liquid-liquid volume guard; `UNSTABLE_PRODUCT`; `PhaseHold`, `UnsupportedPhases` kinds and keys; solver and interval routing; `UnsupportedPhasesIslandTest`, the new engine and network tests (9 files) |
| `ac6ebc1` | `src/test/resources/fluid/regression/chain-100.json` re-captured |
| `8a10bfd` | The three probes removed (detached to `tools/`) |
| `7afa990` | `CHANGELOG.md` `[Unreleased]`: the P3 packages and WP11 (sections Added, Changed, Removed) |

Commit attribution: the session's model line (Claude Opus 5.5) as AGENTS.md asks, not the brief's Fable 5.1 line, as
WP5, WP7, WP9a, WP9b and WP7d did. This review, the tool folders and the other batch documents are git-ignored; the lead
copies them to the main checkout.

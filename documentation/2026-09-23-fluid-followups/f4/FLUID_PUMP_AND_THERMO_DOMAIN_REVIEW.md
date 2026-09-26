# F4: the thermodynamic domain as data, a dedicated domain error, pump option P1

Date: 2026-09-24. Batch `2026-09-23-fluid-followups`, package F4. Branch `claude/fluid-followups` in worktree `agent-ae139e4fc1b184b36`, over F3's `e837ada`: `c26d162` (domain from data, cryogenic nitrogen), `8781443` (dedicated error, rejection key, hold policy), `d02b1d9` (pump P1), `dea8a7e` (changelog). Not merged, not pushed. Every number is in `f4-tables.md`; raw material in `f4-logs/` (NIST data `nist/`, probe sources `probes/`, probe outputs, gate logs, suite XML). The agent-facing reference for the new error is `THERMO_DOMAIN_ERROR.md`.

**No in-game or paced run** (section 8). Probes are off-line (`f4-logs/probes/sci.sh`: javac on the science sources, seconds, no Gradle).

## 0. Summary

**"Extending the thermo package limit as we'll perform cryogenic separation of air later."** Done, as data. The fluid network carries no temperature or pressure bound of its own any more: every property record declares a `fluid_domain` (range and evidence), every package a `fluid_domain` envelope, water keeps its triple point from its own record, and `FluidDomain` enforces one rule - *the package range is the outer envelope; each component's range lies inside it (checked when the catalog loads); a state is valid only inside the range of every component it carries.* Nitrogen is valid from its 63.151 K triple point, not merely allowed: a zero-pressure Cp segment below 273.16 K fitted to NIST (worst 0.018 %, enthalpy and Cp continuous at the joint to round-off), NIST liquid and vapour viscosity tables down to the triple point, and against NIST at 77.355 K a saturation pressure +1.23 % and a saturated liquid density +0.34 %, vapour Cp at 1 atm -0.99 % at 100 K and +0.03 % at 200 K. The network package's envelope is 63.151..900 K and 100 Pa..2 MPa; a pure nitrogen stream goes down to 63.151 K while a stream carrying any crude fraction is still refused below the fractions' 293.15 K. Oxygen and argon are data only (section 1.5).

**"Thermo package issue should be reported by a dedicated error and made clear to user/agents."** Done. `ThermoDomainViolation` (package, component, property, value, range, stable code, node) replaces the anonymous `Fluid state outside domain`; the solver counts it under its own rejection key, a failed interval fails on it, the island's status reads `HELD (thermo domain): Nitrogen at 60.12 K is below 63.151 K in reservoir at 3, 64, 0 (valid 63.151..900 K, package ...)`, the server logs one rate-limited WARN per island per hold with every field and what to do, device edits and the configured reservoir charge are refused with the same text, and `THERMO_DOMAIN_ERROR.md` documents it for agents.

**Pump option P1 (owner's choice).** Done: a pump's limit is its setting times the suction's bulk density over water's at 298.15 K and 1 atm. The three-tank water fill still shuts off at 601,325 Pa (off-line +0.034 Pa: 601,325.024 to 601,325.058); a pump moving nitrogen between two closed 1 m3 tanks reaches its 575 Pa limit and closes within 0.25 s, the suction tank 0.24 K cooler, and the island certifies - where it used to cool the suction tank to the model's floor in 21.9 s and stay held. Its closure exposed a solver defect (a junction-donor cycle at a flow of -1.4e-48 kg/s), fixed.

**Hold policy.** A domain failure is retried once on a fresh solver; if the retry fails on the same component, property, side and node, the island waits with no retry deadline until an input changes. A player gets out by changing the inputs (an edit, a module drive, a property resume); the old gas-transfer case no longer reaches the domain at all.

**Gates on `d02b1d9`:** science 182/0 (161 + 21 new), runtime 226/0 (220 + 6 new, one test replaced), network 30/9, 19/14, 37/3 (unchanged), exact regression 0.000e+00 (reference untouched), GameTests 30, P12 and P31 byte-identical, column/material/thermo tests 568/0.

**Breaking, no migration (a fresh world, per `AGENTS.md`):** the network's thermodynamic revision changed (the domain and nitrogen's data are hashed into it), so fluid worlds saved before F4 are refused; a pump's "Max pressure rise" now means the rise for water.

## 1. T1: the domain from data

### 1.1 Every hard-coded bound, and what became of it

`grep -rn "273.16\|\b600\b" src/main/java src/main/resources` before F4, plus the pressure floors (`p<100`, `pressure<100`, `2e6`) and the 293.15 K rule:

| where | before | now |
|---|---|---|
| `FluidThermodynamics.state` | `t<273.16\|\|t>600\|\|p<100\|\|p>2e6` -> `Fluid state outside domain` | non-finite / wrong basis: plain refusal; then `FluidDomain.check` (data) -> `ThermoDomainViolation` |
| `FluidThermodynamics.solidState` | same bounds | the package envelope (`checkEnvelope`) |
| `FluidThermodynamics.flashTP` | none of its own (failed later, or on a ratio overflow) | domain checked first (`checkTotals`) |
| `FluidThermodynamics.saturationPressure`, `vaporWaterEnthalpy` | reached V3's correlations, plain refusal below 273.16 K | the water model's own range from `water.json` (`triple_point`, `max_enthalpy_temperature`), typed |
| `HydrocarbonModel.AMBIENT_MINIMUM_TEMPERATURE` 293.15 and the "exact bundled record" rule; `MINIMUM_PRESSURE` 100; `min(REFERENCE_PRESSURE, package max)` | code | removed; the components' `fluid_domain` (293.15 K for the crude and light records, now data) and the envelope; `REFERENCE_PRESSURE` stays as the liquid's formulation reference, not a bound |
| `HydrocarbonModel` vapour partial pressure `>= 1e-6 Pa` | code | kept as `VAPOR_PARTIAL_PRESSURE_FLOOR`: a numerical guard on a *partial* pressure (a trace hydrocarbon in steam), not a state bound |
| `WaterRegion1.evaluate` | `273.16`, `623.15`, `100e6`, `p < psat` -> plain refusal | the water record's `triple_point`; 623.15 K and 100 MPa kept as IF97 Region 1's own boundaries (named constants); all typed, naming Water |
| `WaterRegion1.diluteVaporViscosity` | 273.16..900 K | kept: the IAPWS 2008 dilute-gas correlation's own envelope, a transport limit reached only by water vapour, whose thermodynamic range (the triple point) is checked first |
| `FluidDeviceSpec` | `273.16..600 K`, `100 Pa..2 MPa` | structural only (positive, finite); `validate(model, kind)` checks the charge it will hold against the domain, called by `initialize`, the edit handler and the world's config check |
| `FluidNetwork.Controls` (client and server) | same bounds | positive and finite; the server's edit handler validates generator, void and valve settings against the domain before queueing (the reply reads `Not applied: Thermo domain: ...`) |
| `CreateChemE` config `initialNitrogenTemperatureKelvin` 273.16..600, `initialNitrogenPressurePascal` 100..2e6 | code range | 1..10,000 K and 1 Pa..1 GPa (fixed before any data pack loads); checked against the network package at world start, which stops with the domain error if outside |
| `FluidThermodynamics.waterVaporPressureLimit` (600 K a breakpoint) | qualification table of the water-vapour partial pressure | unchanged (a table, not a domain bound) |
| flash `+-600` | a numerical range on ln K | unchanged for components the mixture holds; see 1.4 for absent ones |
| resources: `nitrogen.json` vapour table from 273.16 K, `dissolved_viscosity.json` nitrogen curve from 273.16 K, `water.json` `triple_point` 273.16 | data | the nitrogen tables now start at 63.151 K; the solute curve is unchanged (nitrogen dissolved in a crude liquid is never below the crude's 293.15 K); the triple point is the water range's source |

`grep` of the final tree finds no temperature or pressure domain constant left in the fluid path beyond the named formulation limits above.

### 1.2 The design, and where it departs from the brief's suggestion

The brief suggested the per-component range in each component's data file and the package's declared range as the envelope. That is the design, with one structural choice the code forced: the ranges live in a new `fluid_domain` object of each property and package record, beside - not replacing - the records' existing `temperature_min_kelvin` and friends, and they are kept outside the `Property` record's serialized form. Measured reasons:

* The existing ranges are the column's. `MaterialCatalog.physicsFingerprint` hashes every property record and the package range, and the V3 neural registry (`V3NeuralRegistry`) and the fluid presets' shared-physics check pin that fingerprint. Changing the crude records' or the column packages' ranges would have silently disabled the neural initializer for every column package. With `fluid_domain` held beside the records, no column fingerprint moves - measured at `e837ada` and at the F4 tree (`f4-logs/probes/FingerprintProbe.java`, `fingerprints-before.txt`, `fingerprints-after.txt`): the seven column packages' package and physics fingerprints are identical, only the network package's moved (nitrogen's record changed), and every package's `fluidThermoFingerprint` (the network's revision) moved because it now hashes the domains - and the 568 column, material and thermo tests pass.
* The fluid network has always accepted crude down to 293.15 K (the "ambient continuation", a code rule in `HydrocarbonModel`), and the fluid tests build models on the column packages at 293.15 K and at water-only states down to 611 Pa (`EmptyWaterFillingAuditTest`, `FluidPropertyCoverageTest`). Enforcing the column packages' declared 298.15 K and 50 kPa would have removed that behaviour; the fluid domains of the bundled data reproduce it exactly (crude 293.15 K, envelope pressure 100 Pa).
* **Pressure floor.** The brief asked to enforce the package's 50 kPa unless a test or the vacuum work depends on 100 Pa. Tests do (`EmptyWaterFillingAuditTest` samples steam-saturated vessels from 611 Pa; the network has always run to 100 Pa), and 50 kPa is the crude column packages' operating floor copied into the network package, never enforced by the network. The network envelope therefore declares 100 Pa, the network's own validated floor, as data. The VDU work (13 kPa) is column-side and untouched.
* **Ceiling.** The network's 600 K was its own constant; every correlation it reaches is fitted or sampled to 900 K (the Cp fits, the crude and nitrogen viscosity tables, the water vapour Shomate and dilute viscosity, the water-vapour partial-pressure table), and the rule requires the envelope to contain the components' declared 900 K. So the fluid ceiling is now 900 K. Liquid water stays bounded by Region 1 (623.15 K and its saturation pressure; in practice about 485 K at the 2 MPa reference) and, as before, by its viscosity table (383.15 K, a transport refusal, open item 3).
* A record that overrides a fit declares its own range: the old rule silently narrowed any non-bundled crude record to 298.15 K. `AmbientCrudePresetTest` was restated accordingly (section 7).

### 1.3 "Carried" means any amount: the threshold

The brief asked for a non-negligible-amount threshold (for instance the 1e-6 trace cutoff). F4 uses zero: any positive amount of a component, in either phase, subjects the state to that component's range. Reasons, measured or read:

* Every carried amount is evaluated by every correlation of its phase: the translated PR78 with its ideal-gas Cp polynomial, the pure-component viscosity tables (which refuse outside their range: a crude trace in a nitrogen vapour at 200 K would fail its 293.15 K vapour table), the water correlations (Region 1 and the saturation curve do not exist below the triple point). An exemption for traces needs an extrapolation rule in each of them, none of which is validated.
* The numbers are not benign: the heaviest crude fraction in liquid nitrogen at 80 K has ln(phi_L/phi_V) of about -950, beyond the flash's +-600 range (and the flash refused a pure nitrogen state at 63 K for exactly that reason until F4, section 1.4).
* It is the rule `HydrocarbonModel` already applied to hydrocarbons (`amounts[i]>0`), so crude islands behave as before.
* It costs an air-separation island nothing: the solver seeds traces only of components present in the island and reachable through open connections (`componentMask`, `reachableComponents`), so a nitrogen/oxygen/argon island carries no crude trace. The cost falls on a mixed island whose cold vessel can receive a component that is out of range there, and that island is refused with a message naming the component.

When several carried components are out of range the error names the one with the largest amount, temperature before pressure, then the envelope.

### 1.4 Nitrogen is valid, not merely allowed

Numbers in `f4-tables.md` section 1. The Cp segment below 273.16 K is a cubic fitted to 43 NIST zero-pressure points (0.1 and 1 kPa isobars extrapolated to zero pressure) with its Cp equal to the main fit's at the joint; the enthalpy is continued from the main fit's value there by construction (`TranslatedPengRobinson`: `H = H_main(Tj) + G(T) - G(Tj)`), so H(273.16 K-) and H(273.16 K) differ by Cp x ulp (1.7e-12 J/mol) and the heat capacities by 3.6e-15. Above the joint the arithmetic is the old polynomial to the bit (asserted exactly), which is why the exact regression and P12/P31 did not move. The PR78 constants (CoolProp) and the translation (anchored at NIST 90 K, 2 MPa) were validated as they are: saturation +4.5 % at the triple point falling to +0.3..0.6 % from 90 K, saturated liquid density within 0.8 % to 104 K and -4.5 % at 114 K near the 2 MPa end of the liquid; a cubic equation of state's known near-critical weakness, recorded rather than hidden. The test tolerances are 1.5 % (Psat), 0.5 % (density) and the brief's 1 % (vapour Cp; measured -0.988 % at 100 K: PR78's residual heat capacity near saturation is 0.3 J/mol/K low, the ideal-gas part matches NIST to 0.01 %).

The viscosity tables were part of "actually valid": the network evaluates the vapour viscosity of every vapour, and the old nitrogen vapour table started at 273.16 K (a cryogenic nitrogen gas would have been refused as a transport error). New: a 40-node saturated-liquid table from the triple point to the critical point (within 0.09 % below 116 K) and 42 near-dilute vapour nodes (10 kPa isobar) below 273.16 K, the old 100 kPa nodes above unchanged. The catalog now refuses a `fluid_domain` its vapour viscosity data does not cover.

Found by the tests and fixed: the flash refused a pure nitrogen state below about 70 K because the equilibrium ratios of crude fractions the state does not hold passed e^600. An absent component's ratio is now only kept finite; a ratio inside the range is updated exactly as before (bit-identical wherever the flash used to succeed).

### 1.5 Oxygen and argon: data only

A later task adds, per component: `components/<id>.json`; `properties/<id>.json` with PR78 constants, the ideal-gas Cp (with a `below` segment if its main fit starts above its range), `temperature_min_kelvin`, liquid and vapour viscosity tables covering its range and its `fluid_domain`; optionally a liquid volume reference in `transport/liquid_calibration.json` and a conditional-solute curve in `transport/dissolved_viscosity.json`; the id in `bases/network.json`; the paths in `materials-index.json`; and, for oxygen (triple point 54.36 K, below nitrogen's), the lower minimum in the network package's `fluid_domain`. No code change: the domain, the Cp segment, the tables, the interactions (`missing_interactions: zero`) and the presets are all read from data, and a synthetic added component already passes the catalog (`MaterialPresetsTest.addingAQualifiedFixtureComponent...`). Exact recipe and validation steps: `THERMO_DOMAIN_ERROR.md`.

## 2. T2: the dedicated error

`science/fluid/thermo/ThermoDomainViolation` extends `IllegalArgumentException` (every existing refusal path still treats it as a state outside the property domain) and carries the package, component (`package` for the envelope), property, value, range, a stable `Code` (`THERMO_DOMAIN_TEMPERATURE_BELOW` and so on) and the node. Message: `Thermo domain: Nitrogen at 50.00 K is below its valid range 63.151..900 K (package createcheme:tjl20_methane_nitrogen); the state cannot be evaluated`; a value within rounding of its bound prints the digits that show it crossed. Where it surfaces (full table in `THERMO_DOMAIN_ERROR.md`):

* **Solver.** `Equations.decode`, the phase correction, the seeds, the junction restatement, the inventory refresh and the conservative reconstruction attach the node (`violation.at(id)`). `SparseNewton` remembers a violation its failing iteration's line search was refused by and throws `Nonconvergence(message, x, violation)`; the step solver passes it on. `PassiveIntervalSolver` counts such rejections under `violation.reasonKey()` - one per component, property and side, e.g. `thermo-domain: Nitrogen temperature < 63.151 K`, exempt from the eight-key cap so never folded into `Other` - and a failed interval carries the violation when its last rejection, or more than half, were domain ones. The key reaches the checkpoint (the rejection map is persisted), the views and the certificate: `IslandCertificate.transitionFree` refuses an interval with a domain key.
* **Island.** The worker result carries it (`FluidIslandSolveResult.domain`, detail `HELD (thermo domain): Thermo domain: ...`); the coordinator's status is `HELD (thermo domain): <component> at <value> is below <bound> in <device> (valid <range>, package <id>)`, the device named by the world (`reservoir at x, y, z`, `pump at ...`, or `pipe junction <id>`). It rides the ordinary presentation buckets; no new push path.
* **Log.** `FluidWorldAuthority.domainHeld`: one WARN per island per hold with island, dimension, node, device, code, package, component, property, value, range, whether it waits, the message and the instruction to extend a range only with validated data (pointing at this review and the reference). Repeats of the same island and violation are rate-limited to one per 6,000 online ticks; the generic `status=HELD` line is not written for these holds.
* **Measured end to end** (`FluidThermoDomainHoldTest`, nitrogen narrowed to 298.05 K so the transfer's 0.24 K of cooling crosses it): both attempts fail carrying the violation, the status reads `HELD (thermo domain): Nitrogen at 298.04999999999995 K is below 298.05 K in pump at 1, 64, 0 (valid 298.05..900 K, package createcheme:tjl20_methane_nitrogen); the retry failed the same way, so the island waits for a change to its network`, one WARN.

## 3. T3: pump option P1

`rise_max = setting x rho_suction / rho_ref`. `rho_ref` is computed once from the model's own water at 298.15 K and 101,325 Pa (`FluidThermodynamics.pumpReferenceDensity`, 996.008 kg/m3; NIST 997.0, the global 1e-9 1/Pa compressibility carrying Region 1 from 2 MPa). `rho_suction` is the suction node's bulk density, mass over volume of what it holds, because the pump withdraws the node's inventory homogeneously - the density its target row converts the volume flow with and the velocity clamp caps that end with (`Transport.density` = `state.mass()/state.volume()`).

Every read of the limit takes it at the suction's state: the head-limit row (`edgeRows`, at the trial's decoded state), the target-to-head-limit and head-limit-to-closed tests and the closed reopening test (at the pass's converged state), the head-limit warm start and the carried shutoff corner (at the step's start state), the approximation probe (at the probe's state). The Jacobian sees the dependency: the block sweep re-evaluates an edge's rows for every perturbed column of its endpoints and the sparsity already declared the actuator row on both endpoints' columns; the coloured sweep evaluates whole residuals. No mode, band or switch was added.

**What changed, measured** (`f4-tables.md` section 2): the gas transfer closes in 0.20..0.25 s with the suction 0.241 K cooler and certifies REST in the real coordinator after 3 jobs (before: held at 273.16 K after 21.9 s, 23 jobs per 20 minutes); the water fill's shutoff moves 0.034 Pa (the suction of these lines is the water generator's own state, the reference state, so the ratio is 1); every existing exact-value assertion passes without a new tolerance or value.

**A defect the closure exposed.** With 0.02 s intervals the closure failed on `Phase/device active-set cycle`: behind the closed pump, the suction pipe into the pump's zero-holdup junction converged to -1.37e-48 kg/s, `donorsTurned` read the sign and turned the junction's donor, the next pass converged to exactly 0.0, which the sign test reads as forward, and it turned back (`probe-gas-transfer-p1-trace.log`, 28 cycles). A converged flow inside the transport deadband (1e-14 kg/s, the one `junctionsTurned` already uses) now turns no donor. Before settling on this I tried reading the density at the step's start instead of the trial (a constant limit per step, to rule out a mode cycle from a moving limit): it failed identically, so the density read was not the cause and the brief's trial-state density was kept.

**GUI text.** The field label reads `Max pressure rise, water (Pa)` (was `Max pressure rise (Pa)`), and a pump's status line, after its mode, adds `(limit N Pa on this fluid)` from its suction's density. No dev-client check (section 8).

**Tests that pumped gas.** Four science tests pushed nitrogen or methane against a bar or more with a 500 kPa setting (limit now 280..830 Pa on those gases). They now give the pump the water setting that is 500 kPa on their gas (`500000 x rho_ref / rho_gas`), which keeps their physics and their assertions (`f4-tables.md` 2.4). `IslandCertificateTest`'s pumped nitrogen loop and the other pump tests needed no change.

**P12 / P31:** unchanged, byte for byte (`56332b64...`, `4dcb80a4...`); neither pumps gas.

## 4. T4: the hold policy for deterministic domain violations

`IslandCoordinator.hold`: a failure carrying a violation is held like any failure - a fresh `RetainedSolver`, the halved slice, the retry a cadence later - and remembered. If the retry fails on the same component, property, side and node (`ThermoDomainViolation.sameAs`), the failure is the model's: the island's clock gets no retry tick at all (`IslandClock.holdUntilInputsChange`, `retryAtTick = Long.MAX_VALUE`, so the scheduler holds no deadline), the status gains `; the retry failed the same way, so the island waits for a change to its network`, counter `domainHolds`. It is re-solved only when its inputs change: `inputsChanged` (a fence, a released fence, a resolved delivery, a property resume) clears the wait and starts a new episode; a topology or control edit replaces the island. The wait is the clock's, so it survives a save and load (asserted). Every other failure keeps the ladder, and a retry that fails on a different node or component continues it (asserted).

**How a player gets out:** change what the island solves against - the device settings (a warmer generator, a different composition, a pump setting), the topology, or the property data - or remove the device. The case that motivated the brief, a pump moving gas between closed tanks, no longer reaches the domain at all under P1.

## 5. Tests

| test | suite | what it holds |
|---|---|---|
| `FluidNitrogenCryogenicTest` (8, new) | science | Psat and liquid density at 77.355 K, vapour Cp at 100 and 200 K, ideal-gas Cp 63..273 K against NIST, H and Cp continuity at 273.16 K, the fit above the joint bit-exact, the declared 63.151 K minimum, liquid nitrogen as a whole network state with its NIST viscosity |
| `ThermoDomainViolationTest` (8, new) | science | fields and message, the rejection key, the value-near-bound print, 200 K nitrogen accepted and 1 % crude_pc03 refused naming it, the largest carried component named, free water at 270 K refused naming Water (state, saturation, liquid, flash, Region 1), pressure and envelope, malformed input stays plain, the domain read from data and validated at catalog load |
| `PumpRiseScalingTest` (5, new) | science | the reference density, the gas transfer closing within a second at the scaled limit without reaching the domain, water's limit within its compressibility, the domain rejection key and the failed interval's violation, the dominant-reason rule |
| `IslandCoordinatorTest` (+2) | runtime | the domain hold policy: dedicated status, one retry on a fresh halved slice, the wait with no deadline for 1,000 ticks, restored waiting after a snapshot is re-registered, one WARN, counters, the fence wake, a new episode not re-logged, a different violation keeps the ladder |
| `FluidThermoDomainHoldTest` (1, new) | runtime | the same end to end through the real solver and coordinator on a placed transfer |
| `FluidDeviceSpecDomainTest` (2, new) | runtime | a 200 K nitrogen reservoir valid and initialized, 50 K refused with the dedicated text; generator checks by composition (ice, crude below its range, 700 K accepted); controls structural; valve pressure against the envelope |
| `IslandCertificateDomainTest` (1, new) | runtime | a domain key keeps an interval from certifying |
| `FluidPumpedFillLineTest` (1 replaced) | runtime | `aGasTransferStopsAtTheModelsTemperatureFloorAndItsRetriesBackOff` asserted the old floor and hold; `aGasTransferClosesAtThePumpsScaledLimitAndCertifies` asserts the P1 end state |
| changed | | `AmbientCrudePresetTest` (the override now declares its range), `FluidPacketCodecTest` (out-of-domain controls are refused by the server's domain check, the reply text is the violation's), `FlowControlTest`, `NetworkRegimeTest`, `TrBdf2Test`, `SharedSourceDepletionQualificationTest` (gas-equivalent settings), `ViscosityTest` (its manufactured 250..700 K vapour fit comes with a matching `fluid_domain`) |
| data fixture | | `examples/material-override/.../tjl20_methane.json` (a whole-record override) carries the new `fluid_domain` |

No test was removed; one was replaced by its P1 counterpart.

## 6. Gates

On `d02b1d9`, one at a time, `f4-logs/gates.log` (every run NOGAME, 26.9..28.4 GB free), logs `gate-final-*.log`, XML `final-test-results/`:

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 182 passed, 0 failed |
| `fluidRuntimeTest --rerun` | 226 passed, 0 failed |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 (unchanged) |
| `fluidSolverRegression -PfluidRegressionMode=exact --rerun` | chain-100 0.000e+00 in every quantity; reference untouched |
| `runFluidGameTestServer -PfluidGameTestRunId=f4-final` | 30 of 30 required tests passed |
| P12 / P31 sha256 | `56332b64ea3f3bde...` / `4dcb80a40266...`, identical to the `wp2-logs` copies |
| column, material and thermo tests (`test --tests science.column.* --tests science.material.* --tests science.thermo.*`) | 568 passed, 0 failed (run because `MaterialCatalog` is shared; no column source changed) |
| each commit's own tree | compiles (`gate-c1-compile.log`, `c2`, `c3`); the suites ran on the final tree |

## 7. Deviations from the brief

1. **`fluid_domain` beside the records** rather than in their existing range fields (section 1.2): the existing fields are the column's and are pinned by the neural registry's physics fingerprints.
2. **Threshold zero** for "carried" (section 1.3), argued from the correlations and a measured ratio.
3. **Pressure floor 100 Pa, not 50 kPa** (section 1.2): tests depend on the network's 100 Pa, and the 50 kPa was the column packages' operating floor.
4. **Ceiling 900 K** (the components' declared maximum) instead of the network's old 600 K.
5. **The "exact bundled record" rule is gone:** an override that changes a crude fit now carries its own `fluid_domain`; `AmbientCrudePresetTest`'s second half was restated to assert that an override declaring 298.15 K is refused at 298 K with the dedicated error (before, the code narrowed any non-bundled record to 298.15 K silently).
6. **The WARN is emitted at the first domain hold of an episode** (not at the reproduced retry), so a transient domain failure that the halved retry passes is still logged once; the reproduced retry is not logged again.
7. **Beyond the brief, both measured and both needed by it:** the flash's refusal of absent components' ratios (a pure nitrogen flash at 63 K failed) and the junction-donor deadband (the gas pump's closure cycled).
8. **Pump status text and label.** The brief offered a label change or a tooltip; the screen has no tooltip for fields, so the label changed and the status line names the limit.
9. **Catalog fixtures.** Two column-subset tests failed on the first run because their fixtures lacked the new field (`MaterialPresetsTest` via `examples/material-override`, `ViscosityTest`); the fixtures were updated, not the rule.
10. **Worktree.** The session started in another worktree; it switched into `agent-ae139e4fc1b184b36` with `EnterWorktree` after a write hook refused a cross-worktree edit. The NIST files and the first probes were written by shell into this worktree before the switch.

## 8. Skipped under the benchmark rule

No dev-client run, no dedicated-server rig run, no paced benchmark. Every claim is held by a unit test, the runtime suite's coordinator rigs or an off-line probe of seconds. Not verified in game: the pump label and status text on the screen (the brief required no GUI run), and the WARN line through Minecraft's logger (its content is built from the same violation the coordinator tests assert).

## 9. Open items

1. **Mixed cryogenic islands.** A vessel that can receive a crude or light component is refused below 293.15 K even if it holds only the solver's entry trace of it. That is the rule (section 1.3); an owner decision is whether to qualify traces (a validated extrapolation for each correlation) later.
2. **Near-critical nitrogen.** PR78's saturated liquid density is 4.5 % low at 114 K (the 2 MPa end of the liquid). A volume-translation fitted over the whole liquid range, or a second anchor, would lower it; not done (it changes the translated volume's calibration point, which is data but needs its own validation).
3. **Liquid water above 383.15 K** is still refused by its viscosity table as a transport error (`Viscosity state is outside ...`), not as a thermo-domain error; unchanged from before F4. Making transport ranges part of the domain would name it.
4. **Oxygen and argon** are not added (by the brief). When they are, the network envelope's minimum moves to oxygen's 54.36 K, and binary interactions (N2-O2-Ar) should be fitted rather than left zero.
5. **Solid materials** at cryogenic temperatures: dry-solid states are checked against the package envelope only (63.151 K now); the solid materials' own heat-capacity data carry no range.
6. **In-game check** of the new label, the pump status suffix and a held island's status line on the screen, at the owner's convenience.

## 10. Documents

This review; `f4-tables.md`; `THERMO_DOMAIN_ERROR.md`; `f4-logs/` (gates, probes, NIST data, suite XML). `MATERIALS.md` (tracked) documents `fluid_domain` and the Cp segment. Per `AGENTS.md` the coordinator copies these to the main checkout's `documentation/2026-09-23-fluid-followups/f4/` and updates the batch row of `documentation/INDEX.md`; nothing was copied from here.

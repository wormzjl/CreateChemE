# F4 tables - every number of the pump and thermo-domain package

Batch `2026-09-23-fluid-followups`, package F4, 2026-09-24. Branch `claude/fluid-followups` over `e837ada` (F3's last commit): `c26d162` (domain from data, nitrogen), `8781443` (dedicated error, rejection key, hold), `d02b1d9` (pump P1, donor deadband), `dea8a7e` (changelog). Raw material in `f4-logs/`: NIST data `nist/`, probe sources `probes/`, probe outputs `probe-*.log`, gate logs `gate-*.log` with `gates.log`, suite XML `final-test-results/`. Off-line probes run with `probes/sci.sh` (the science sources and three Minecraft-free runtime classes compiled with javac, seconds, no Gradle; `REF=<commit>` runs against that commit's sources for a "before").

## 1. Nitrogen against NIST (T1.3)

NIST WebBook, Span et al. 2000 equation of state and Lemmon and Jacobsen 2004 transport, fetched as tab-separated text into `f4-logs/nist/` (`sat-triple-to-critical.tsv`, `isotherm-77.355K.tsv`, `isobar-101.325kPa-100-200K.tsv`, `isobar-0.1kPa-63-303K.tsv`, `isobar-1kPa-63-303K.tsv`, `isobar-10kPa-63-273K.tsv`; zero-pressure Cp derived in `cp0-zero-pressure-63-303K.tsv`). Model: the network's own (`FluidThermodynamics.forNetwork`, 1e-9 1/Pa).

### 1.1 The test's points (`FluidNitrogenCryogenicTest`, final science suite output)

| quantity | model | NIST | deviation | test tolerance |
|---|---|---|---|---|
| saturation pressure, 77.355 K (network liquid: PR78 at 2 MPa + global compressibility) | 102,568.995 Pa | 101,325.059 Pa | +1.228 % | 1.5 % |
| same, pure PR78 liquid at P (probe) | 102,546.892 Pa | | +1.206 % | - |
| saturated liquid density, 77.355 K at NIST Psat | 808.8393 kg/m3 | 806.0844 kg/m3 | +0.342 % | 0.5 % |
| same, pure translated PR78 (probe) | 805.5242 kg/m3 | | -0.069 % | - |
| vapour Cp, 100 K, 101,325 Pa (enthalpy of the network state, central difference) | 29.72842 J/mol/K | 30.02493 | -0.988 % | 1 % |
| vapour Cp, 200 K, 101,325 Pa | 29.24122 J/mol/K | 29.23213 | +0.031 % | 1 % |
| H(273.16 K) - H(273.16 K - 1 ulp), vapour at 1 kPa | 1.705e-12 J/mol | | Cp x ulp = 1.6e-12 | 1e-9 J/mol |
| Cp(273.16 K) - Cp(273.16 K - 1 ulp) | 3.553e-15 J/mol/K | | round-off | 1e-9 |
| ideal-gas Cp above the joint | the 298.15..900 K polynomial to the last bit at 273.16, 298.15, 300, 450, 600, 900 K | | exact | 0 |
| liquid viscosity, 77.355 K (saturated-liquid table) | within 0.5 % of 160.661 uPa.s | | | 0.5 % |
| declared minimum (package envelope, component, record) | 63.151 K | triple point 63.1510 K | | exact |

The vapour Cp at 100 K sits 0.012 % inside the brief's 1 % tolerance; the ideal-gas part is within 0.01 % of NIST (29.1034 against 29.1037 J/mol/K), the rest is PR78's residual heat capacity near saturation (0.625 J/mol/K against NIST's 0.921). Before F4 the same point read 29.27994 (-2.48 %) because the 298.15..900 K polynomial extrapolated to 28.64 J/mol/K at 100 K (`probes/NitrogenCryogenicProbe.java`, first run).

### 1.2 Saturation sweep (`probe-nitrogen-validation.log`)

| T (K) | Psat NIST (Pa) | Psat model (Pa) | deviation | liquid density NIST (kg/m3) | model at NIST Psat | deviation |
|---|---|---|---|---|---|---|
| 63.151 | 12,519.8 | 13,081.6 | +4.49 % | 867.222 | 860.636 | -0.76 % |
| 68.212 | 29,436.2 | 30,291.5 | +2.91 % | 846.135 | 843.537 | -0.31 % |
| 73.571 | 63,269.0 | 64,392.6 | +1.78 % | 822.999 | 823.851 | +0.10 % |
| 78.794 | 119,657.0 | 120,928.6 | +1.06 % | 799.511 | 802.862 | +0.42 % |
| 83.836 | 204,239.0 | 205,545.2 | +0.64 % | 775.780 | 780.615 | +0.62 % |
| 88.922 | 328,292.6 | 329,634.1 | +0.41 % | 750.564 | 755.808 | +0.70 % |
| 93.930 | 497,456.6 | 499,064.2 | +0.32 % | 724.150 | 728.510 | +0.60 % |
| 98.948 | 723,008.6 | 725,446.4 | +0.34 % | 695.645 | 697.495 | +0.27 % |
| 103.997 | 1,016,226.3 | 1,020,417.4 | +0.41 % | 664.191 | 661.268 | -0.44 % |
| 109.002 | 1,382,748.3 | 1,389,722.8 | +0.50 % | 629.082 | 617.930 | -1.77 % |
| 114.044 | 1,839,491.1 | 1,849,875.2 | +0.56 % | 587.585 | 560.961 | -4.53 % |
| 119.089 | 2,397,732.3 | above the 2 MPa domain | - | 534.932 | 462.105 | -13.6 % (outside the domain) |

Liquid nitrogen at or below 2 MPa exists up to 115.6 K; near that end PR78's liquid density is 4.5 % low, the known near-critical weakness of a cubic equation of state.

### 1.3 The new data

| item | value | source |
|---|---|---|
| Cp segment below 273.16 K | `[29.111444804262373, -2.8737987468627715e-7, -4.878679661531989e-7, -1.4261309982075894e-9, 0, 0]` in powers of (T - 298.15 K) | `probes/fit-nitrogen-cp-low.js`: cubic in (T - 273.16)/100 fitted to 43 NIST zero-pressure points 63.16..273.16 K, Cp constrained to the main fit's 29.111169568859378 at the joint |
| its worst deviation | -0.0177 %, at 273.16 K (where the main fit is itself 0.018 % below NIST) | same |
| liquid viscosity table | 40 nodes, saturated liquid 63.151..126.192 K, >= 1.5 K apart; log-linear within 0.09 % of every NIST row below 116 K, 1.1 % near the critical point | `probes/build-nitrogen-viscosity-tables.js` |
| vapour viscosity table | 42 nodes from the 10 kPa isobar, 63.151..268.151 K, then the unchanged 27 nodes of the 100 kPa isobar from 273.16 K | same |
| nitrogen `fluid_domain` | 63.151..900 K, 100 Pa..2 MPa | `properties/nitrogen.json` |
| network package envelope | 63.151..900 K, 100 Pa..2 MPa | `packages/tjl20_nitrogen.json` |
| crude and light records | 293.15..900 K, 100 Pa..2 MPa (the former `AMBIENT_MINIMUM_TEMPERATURE` code rule, now data) | the 19 other property records |
| crude package envelopes | 273.16..900 K, 100 Pa..2 MPa (water's triple point is the lowest component minimum) | the seven other package records |

## 2. Pump option P1 (T3)

### 2.1 Gas transfer between two closed 1 m3 nitrogen tanks (placement defaults, pump 0.01 m3/s, setting 500 kPa)

| | before (`e837ada`) | after (`d02b1d9`) |
|---|---|---|
| off-line, 5 s intervals (`probe-gas-transfer-before.log`, `probe-gas-transfer-p1.log`) | pump at target, suction 292.24 K at 5 s, 280.8 K at 15 s, 275.2 K / 76,561 Pa at 20 s, then `Newton iteration limit` at the 273.16 K floor | CLOSED within the first interval |
| off-line, 0.02 s intervals | - | head limit reached at 0.18..0.20 s, CLOSED at 0.20..0.22 s |
| suction tank at the end | 273.16 K, 74.6 kPa, held for good (F1) | 297.908659 K (-0.241 K), 101,038.225 Pa |
| discharge tank at the end | 321.15 K, 130.6 kPa | 298.391089 K, 101,612.021..101,612.022 Pa (0.02 s and 5 s intervals) |
| rise at shutoff | 52 kPa (never reached 500 kPa) | 573.797 Pa |
| limit at the start (500 kPa x 1.14535 kg/m3 / 996.008 kg/m3) | - | 574.97 Pa |
| science test, direct pump edge (`PumpRiseScalingTest`) | - | closed at 0.25 s, suction 297.908655 K 101,038.220 Pa, discharge 298.391073 K 101,612.020 Pa, rise 573.7997 Pa |
| runtime test through the real coordinator (`FluidPumpedFillLineTest`) | HELD at 273.16 K, 23 jobs in 20 min, 9.49 s test | RESTING (certified REST) at 15 s, 3 jobs, 0.059 s test |
| domain reached | yes (the model's floor) | never (no `thermo-domain:` key) |

Reference density: water at 298.15 K and 101,325 Pa from the model's own water, 996.008 kg/m3 (NIST 997.0; the global 1e-9 1/Pa compressibility carries IF97 Region 1 from its 2 MPa reference).

### 2.2 Water fill shutoff, three-tank chain `GUPRPRPR` (off-line, cold start then 5 s intervals; `probe-fill-before.log`, `probe-fill-after.log`)

| | before | after | change |
|---|---|---|---|
| pump closes | t = 251.35 s | t = 251.35 s | - |
| tank pressures at shutoff (all three) | 601,325.023980 Pa | 601,325.058216 Pa | +0.034 Pa |
| pump head at shutoff | 500,000.023977 Pa | 500,000.058192 Pa | +0.034 Pa |
| suction density / reference | 996.008376 / 996.008376 | same | ratio 1 |

F1 expected up to about 100 Pa; the suction of these lines is the water generator's own state, which is the reference state, so the ratio is 1 and the shutoff moves by 0.034 Pa, inside the shutoff band's decision (the pump closes where its margin falls below the band). The exact-value assertions (`FluidPumpedFillLineTest` 601,325 +- 1 Pa, `FilterBlockLineIslandTest` 601,325 +- 0.6 Pa) pass unchanged; no tolerance was widened and none needed a new value. Through the real coordinator the three-tank fill still ends at 601,325.0 Pa in every tank, 58 jobs, 8.2 s of work, certified STEADY at online tick 5,280 as before; its certificate replays 6.48e-8 kg/s (F3: 1.46e-9 kg/s; horizon 21,180 against 278,180 ticks) - the tanks' residual thermal settling after the closure, which the 0.034 Pa moves.

### 2.3 The closure's active-set cycle (fixed in `d02b1d9`)

| run | result |
|---|---|
| P1, 0.02 s intervals, before the donor fix (`probe-gas-transfer-p1-before-donor-fix.log`) | `Substep refinement exhausted: Phase/device active-set cycle` at 0.20..0.22 s |
| same with pass tracing (`probe-gas-transfer-p1-trace.log`, 28 cycles) | pass 1 CLOSED, suction pipe flow -1.37e-48 kg/s, donor turned; pass 2 flow exactly 0.0, read as forward, donor turned back; pass 3 repeats pass 1 |
| P1 with the limit frozen at the step's start state instead of the trial (tried, reverted) | the same cycle at the same interval: the density read was not the cause |
| P1 (trial density) with the donor deadband, 0.02 / 0.05 / 5 s intervals | CLOSED at 0.22 / 0.25 / 5 s, no failure |

### 2.4 Tests that pumped gas against a bar or more

| test | before | now |
|---|---|---|
| `FlowControlTest.pumpMeetsItsSuctionFlowTarget...` | methane at 1 atm into 2 bar, setting 500 kPa | setting 500 kPa x 996.0 / rho(methane, 350 K, 1 atm): the same 500 kPa on the gas |
| `NetworkRegimeTest.pumpShutoffUsesAddedPressure...` (shutoff between 1.49 and 1.51 MPa from a 1 MPa nitrogen generator) and the sonic velocity clamp | 500 kPa | the setting that is 500 kPa on the generator's nitrogen |
| `TrBdf2Test.pumpWorkIncludesAllStages...` | 500 kPa, nitrogen 1 atm into 2 bar 10 m up | same conversion |
| `SharedSourceDepletionQualificationTest.excessivePumpRequest...` | 500 kPa, nitrogen into 1.5 bar | same conversion |

Before the conversion these failed as the owner's rule predicts: a 500 kPa water setting is 280..830 Pa on those gases (science suite `gate-dev2-science.log`, 5 failures).

## 3. The dedicated error and the hold (T2, T4)

| item | measured |
|---|---|
| forced violation off-line (nitrogen narrowed to 298.05 K, `probe-domain-violation.log`) | second 0.05 s interval fails: `Interval substep limit ... rejected=532, reasons={thermo-domain: Nitrogen temperature < 298.05 K=532}`; `domainViolation()`: Nitrogen, THERMO_DOMAIN_TEMPERATURE_BELOW, node 2 (the pump's junction) |
| same through the real coordinator (`FluidThermoDomainHoldTest`) | two failures (first attempt, fresh-solver retry), both carrying the violation; then waits: `retryAtTick` = Long.MAX_VALUE, no job for 1,000 ticks, one WARN; a fence dispatches it at once |
| status line | `HELD (thermo domain): Nitrogen at 298.04999999999995 K is below 298.05 K in pump at 1, 64, 0 (valid 298.05..900 K, package createcheme:tjl20_methane_nitrogen); the retry failed the same way, so the island waits for a change to its network` |
| policy with canned results (`IslandCoordinatorTest`) | first hold: dedicated status, retry 100 ticks later on a 50-tick slice and a fresh solver; reproduced: waits, no deadline, no dispatch for 1,000 ticks, still waiting after a snapshot is re-registered; `numericalHolds` 2, `domainHolds` 1, `retriesDeferred` 0; one WARN; a fence wakes it; the same violation after the wake is a new episode and is not logged again within 6,000 ticks; a different node on the retry keeps the ladder |

## 4. Gates on the final tree (`d02b1d9`), `gates.log`, `gate-final-*.log`, XML in `final-test-results/`

| gate | F3 baseline (`e837ada`) | F4 final | note |
|---|---|---|---|
| `fluidScienceTest --rerun` | 161 / 0 failed | **182 / 0** | +21 new (FluidNitrogenCryogenicTest 8, ThermoDomainViolationTest 8, PumpRiseScalingTest 5); none removed |
| `fluidRuntimeTest --rerun` | 220 / 0 | **226 / 0** | +6 new (IslandCoordinatorTest 2, FluidDeviceSpecDomainTest 2, IslandCertificateDomainTest 1, FluidThermoDomainHoldTest 1); `aGasTransferStopsAtTheModelsTemperatureFloor...` replaced by `aGasTransferClosesAtThePumpsScaledLimitAndCertifies` |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 | **30/9, 19/14, 37/3** | unchanged |
| `fluidSolverRegression -PfluidRegressionMode=exact --rerun` | chain-100 0.000e+00 | **0.000e+00** in every quantity | reference not touched |
| `runFluidGameTestServer -PfluidGameTestRunId=f4-final` | 30 | **30 passed** | |
| P12 `P12-worker-trajectories.json` sha256 | `56332b64ea3f3bde9f23486ec71f708ad25b42044d655003c7982bcf9296be57` | **identical** | |
| P31 `P31-cadence-trajectories.json` sha256 | `4dcb80a40266689328916775227f10a3b77f47b31e199ef2e2bd1d2d3441c645` | **identical** | |
| column, material and thermo tests (`test --tests science.column.* science.material.* science.thermo.*`) | - | **568 / 0** (105 classes) | run because `MaterialCatalog` is shared; first run `gate-dev4-column.log` 2 failures, both catalog fixtures without the new `fluid_domain`, fixed (section 7 of the review) |
| compile of each commit's own tree | - | `c26d162`, `8781443`, `d02b1d9` each compile (`gate-c1/c2/c3-compile.log`) | gates ran on the final tree only |

Every run: no `Endfield.exe`, 26.9..28.4 GB free (`gates.log`). No dev client, no dedicated server, no paced benchmark was run.

## 5. Fingerprints before and after (`probes/FingerprintProbe.java`, `fingerprints-before.txt` at `e837ada`, `fingerprints-after.txt` at the F4 tree)

| package | package fingerprint | physics fingerprint (column, neural pins) | fluid thermodynamic fingerprint (network revision) |
|---|---|---|---|
| bonga_tjl20, cold_lake_blend_tjl20, dalia_tjl20, tjl19_dwsim, tjl20_methane, upper_zakum_tjl20, wti_light_export_tjl20 | same | same | moved (domains hashed) |
| tjl20_methane_nitrogen (the network) | moved (nitrogen's record) | moved | moved |

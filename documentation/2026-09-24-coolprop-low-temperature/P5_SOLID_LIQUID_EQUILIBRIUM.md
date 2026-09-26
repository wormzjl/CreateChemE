# P5: solid-liquid and three-phase competition with the CO2-I crystal

Batch `2026-09-24-coolprop-low-temperature`, branch `claude/coolprop-multiphase-thermo-37f6b0`, 2026-09-25. Commits
`33b4ebe`, `0a8e544`, `d4d7e82`, `2bcf431`, `12cf449` (the first P5 agent, stopped by a rate limit) and `1cfb1fc`,
`4929e0f` (this session). Plan: `UNIFIED_MULTIPHASE_THERMO_PLAN.md` P5 and gate G5; decisions D16 to D18 in
`DECISION_LOG.md`; inputs `P4_SOLID_CO2_MODEL.md` sections 5 to 7, `P4_GAS_SOLID_EQUILIBRIUM.md`,
`P4_NETWORK_COUPLING.md` section 11, `P4_P5_HOLDOUT_DATA_SURVEY.md` sections 4.0, 5 and 9.

## 0. Outcome

- **Engine.** The crystal solve of P4 is generalised: the fluid the crystal leaves may be one vapour, one liquid, a
  vapour-liquid split, or one dense one-root fluid labelled liquid-like. The answers are `VAPOR_SOLID`,
  `LIQUID_SOLID`, `VAPOR_LIQUID_SOLID` and `SOLID_PRESENT` (a pure feed frozen or deposited completely). Pure CO2
  melts and freezes, a binary's three-phase step and a pure species' triple point are answered from the balances, and
  `freezingTemperature(P, z)` gives the liquidus. What stays held is typed `CRYSTAL_PHASES`: two liquids or three fluid
  phases beside a crystal, two crystals. Non-convergence is typed `NOT_CONVERGED` with its diagnostics.
- **Network.** Stage 2b's crystal inventory now sits beside a liquid too. `UnsupportedPhases.Kind.SOLID_LIQUID` is
  renamed `CRYSTAL_PHASES` and holds only the phase sets above.
- **Fusion enthalpy (D16 revisit).** Both values were measured on every family. **Keep 9019 J/mol** (proposal D19,
  section 10). 8875 J/mol is better only near CO2's triple point (Souza's three-phase line, pure melting) and in
  N2-rich solvents. It is worse on every methane-rich liquidus set and on the sublimation line, where it fails the P0
  5 % target again. The record `crystal-carbon_dioxide-i-r2` is unchanged, so no pin moved.
- **G5 families.** G5F1 to G5F6 are green.
  - Pure melting: 0.125 K against Span-Wagner (D16 declared 0.13 K).
  - Liquidus (dataset MAD against the 2 K target): Shen 2.04 K (missed by 0.04 K), Davis 1.90 K (met); pooled 2.02 K.
  - Three-phase line: Davis 1.64 % in pressure (0.32 K equivalent); Souza 2.50 % (0.44 K).
  - N2 ternaries: Campestrini 0.56 K (met); Riva-Stringari 2.64 K (missed; a set the others contradict).
  - Report-only sets are printed.
  - Consistency: conservation 1.7e-16, equal chemical potentials 2.6e-11 RT, PH/UV round trips 1.7e-10.
- **Former holds.**
  - All 69 grid holds of `SpineNetworkPathTest` are answered, and so is every G3 F7 state.
  - (d) is a liquid-solid island answer at 0.5 MPa; at 2 MPa it is a domain hold above 10 MPa.
  - The 14 dense-N2 points of G4F2 are scored: MAD 3.50 K, max 5.90 K. They get their own bound, and the original caps
    stay on the 51 vapour-carrier points; all 65 points together score 1.08 K. The four THREE_PHASES grid states are
    answered.
- **Network tests.**
  - A vapour-liquid methane vessel with 2 % CO2 freezes onto the binary's three-phase line and dissolves back
    (temperature 7.3e-11, pressure 5.2e-10).
  - A new liquid-full test: compressed methane with 3 % CO2 freezes beside the liquid alone and dissolves back
    (temperature 4.6e-12, pressure 5.7e-11).
  - In both, CO2 is conserved to 1e-12 and the engine's UV answer is reproduced to 1e-9.
  - The liquid-solid checkpoint round-trips bit for bit.
- **Gates.**

  | Gate | Before P5 | After |
  |---|---|---|
  | `test` | 1,267 | 1,282 tests (264 classes), 0 failures |
  | `fluidScienceTest` | 226 | 229 |
  | `fluidRuntimeTest` | 230 | 231 |

  `fluidSolverRegression` exact is zero and 30 of 30 fluid GameTests pass on a fresh world. The bundled network path
  and the pins are unchanged.
- **G5 status (proposal).** Met at the declared-error grade for the allowlist of section 7, with the liquidus target
  declared at 2.2 K dataset MAD (Shen misses 2 K by 0.04 K). The not-qualified list is in section 7. The lead decides.

## 1. What the inherited commits deliver, and what this session added

| Commit | Content | Checked here |
|---|---|---|
| `33b4ebe` | Engine: the general crystal solve (`depositFromFluid`), `LIQUID_SOLID` and `VAPOR_LIQUID_SOLID`, pure freezing and melting in TP/PH/UV, the binary three-phase step (`binaryThreePhase`) and the pure triple point in UV (`pureTriplePoint`), the family triple point per crystal, `freezingTemperature`, `tpCrystalsHeldOut`, the `CRYSTAL_PHASES` hold; G4F1/G4F2 former holds reported apart | Sound; every test green; one detail wording fixed (`1cfb1fc`) |
| `0a8e544` | Network: the TP adapter's new slots, the outer check of a liquid-bearing node on the crystals-held-out split, crystal floors for a liquid, `Kind.CRYSTAL_PHASES`, the pilot CO2 liquid viscosity r2 to 90 K, the (d) freeze, the (h) vapour-liquid vessel, the two-liquid hold, the liquid-solid checkpoint, G3 F7 answered | Sound; the (h) bubble-point comment was wrong (section 6.3) and is corrected |
| `d4d7e82` | `frostTemperature` (a gas scored as one vapour), band-typed splits steer the crystal solve, pseudo-binaries take the three-phase step, strict volumes in the nested UV under a crystal competition | Sound |
| `2bcf431` | G5F1 to G5F6, `G5Support`, the holdout copies with citation headers, `SpineNetworkPathTest`: 69 holds answered | All families ran green on arrival; the numbers in their Javadoc match the runs |
| `12cf449` | Liquid-solid checkpoint fixture charged at 0.5 MPa | Green |
| `1cfb1fc` (this session) | Liquid-full island test (section 6.2); (h) heat carried by a methane trace, with its comment corrected by measurement; engine detail "freezes ... out of the dense one-root fluid" (was "deposits ... from the vapour"); G5F6 names the four grid states correctly (100 to 150 K, not all at 100 K) | |
| `4929e0f` (this session) | G5F6: PH/UV round trips and the onset at the four grid states | |

Not found missing: every item of the P5 brief's engine and network list is implemented. The "two crystals" hold cannot
be exercised on the pilot (one crystal) and is covered by the code path only.

## 2. Algorithm and what remains held

**TP under `FLUID_AND_CRYSTALS`** (`FluidTpEquilibrium.solveWithCrystals`):

1. **Fluid answer and the drive.** The fluid-only answer of the whole feed comes first. If it is a split whose product
   the stability re-check proved unstable (a third fluid phase indicated), its split chemical potentials still steer
   the crystal test. Each crystal whose species is carried is tested by fugacity: D = (mu_i - mu_s)/RT.
2. **Pure feed.**
   - D > 1e-9: the crystal alone (`SOLID_PRESENT`: complete deposition, or since P5 complete freezing).
   - Within the coexistence tolerance of its sublimation or melting point: `PURE_COEXISTENCE_UNDERDETERMINED`.
3. **Mixture.** The solid amount is the one unknown, s = ln(r/z_i) with r the species left in the fluid.
   - P4's vapour Newton runs first, unchanged, so vapour-solid answers are bit-identical to P4. It accepts a
     one-phase fluid on its root; a dense one-root fluid labelled liquid-like gives `LIQUID_SOLID`.
   - Otherwise `depositFromFluid` runs. Every iterate re-flashes the reduced feed with the stability test, and
     f(s) = (mu_i - mu_s)/RT of that fluid answer is nondecreasing in s. The step is Newton on a one-phase root and a
     secant otherwise, bracketed, with bisection when a step leaves the bracket or stalls. It converges at
     |f| <= 1e-9 with every fluid phase checked, within at most `CRYSTAL_ITERATIONS` re-flashes.
   - The answer is `VAPOR_SOLID`, `LIQUID_SOLID` or `VAPOR_LIQUID_SOLID` by the fluid's phases. The solid is
     computed as -z_i expm1(s), so the species is conserved to rounding.
4. **Onsets** (the `DepositionOnset` search in 1/T or ln P):
   - `freezingTemperature` scores the feed as one liquid (the cubic's liquid root). It is started from the ideal
     solubility 1/T = 1/T_t - R ln x / dH_fus.
   - `frostTemperature` scores the feed as one vapour.
   - `depositionTemperature`/`depositionPressure` use the whole feed's fluid answer (P4's functions), now through a
     liquid too.

**PH and UV.**

- A pure species' crystal step (melting or sublimation) is answered from the energy (and volume) balance by
  `pureCrystalStep`.
- A binary's (or pseudo-binary's: two majors and traces) three-phase step is answered by `binaryThreePhase`: Newton
  on the three-phase conditions with the species and energy (or volume) balances.
- A pure species at its family's triple point in UV is answered by `pureTriplePoint`, with vapour, liquid and crystal
  from the mole, energy and volume balances.
- Under a crystal competition the nested UV solves each volume exactly. A crystal beside a liquid is nearly
  incompressible, so an inexact volume closed the bracket on a false step (`d4d7e82`).

**Typed holds (`CRYSTAL_PHASES`, UNSUPPORTED, NOT_IMPLEMENTED):**

- two liquids beside the crystal (for example N2 + C2H6 with 5 % CO2 at 95 K and 2 MPa);
- three fluid phases beside it (the bracket closes on a split whose product is unstable);
- two crystals forming.

A solve that does not converge is `NOT_CONVERGED`, carrying its residual and the fluid's classification in the detail
and the workspace diagnostics.

**Network** (`FluidThermodynamics`, `FluidDomain`):

- **TP adapter slots.**
  - A liquid beside the crystal goes in the liquid slot, carrying the crystal's share as a supersaturated liquid
    until the UV answer moves it.
  - A vapour and a liquid go in their slots, the liquid taken by difference so that amounts are conserved exactly.
- **Outer check.** The outer check of a node carrying a liquid takes the node's own crystals-held-out split
  (`tpCrystalsHeldOut`). The crystal is frozen over a step, and this keeps a node on a binary's three-phase line in
  its vapour-liquid regime.
- **Crystal floors.** The competition's floors (CO2 to 90 K) apply to a liquid as to a gas.
- **Equilibration.** Deposition, freezing, sublimation and dissolution remain the engine's UV answer for the vessel's
  whole inventory at interval starts and after accepted substeps (D18 item 4), unchanged.
- **Holds.** `Kind.CRYSTAL_PHASES` is the typed island hold naming its node.
- **Fluid-only path.** It gains only guards that return at once when a package has no crystal (`w.activeCount == 0`,
  `binaryCrystal(w) == null`, the domain's null floors). This is confirmed bit for bit by `LegacyNetworkPathPinTest`,
  P12, P31, exact chain-100 zero and the pins; no benchmark pair was run (the owner's rule).

## 3. Fusion enthalpy: 9019 against 8875 J/mol (D16 revisit)

**Method.** Probe `P5FusionEnthalpyProbe` (`tools/p5-solid-liquid-scans/`; output
`research/.../p5-solid-liquid/output-probe-P5FusionEnthalpyProbe.txt`).

- The same service with the crystal record's `anchor.fusion_enthalpy_j_per_mol` set to each value
  (`G5Support.withFusionEnthalpy`; the 9019 path reproduces the bundled service bit for bit).
- Every family is scored exactly as its test scores it.
- The sublimation enthalpy is h_V - h_s at the model's own sublimation pressure at 194.67 K.

| Quantity (target) | 9019 J/mol | 8875 J/mol | Better |
|---|---|---|---|
| Sublimation pressure vs Span-Wagner 3.12 at 150 K (P0 5 %) | +2.84 % | +6.56 % (fails) | 9019 |
| Sublimation enthalpy at 194.67 K vs Giauque-Egan 25,236 J/mol (2 %) | -0.16 % | -0.74 % | 9019 |
| Pure melting, worst to 10 MPa (D16 0.13 K) | 0.125 K | 0.094 K | 8875 |
| G4F1 binary frost points, pooled MAD (1.5 K) | 1.354 K | 1.339 K | equal |
| G4F1 ternaries, pooled MAD | 1.154 K | 1.120 K | equal |
| G4F2 vapour carrier (51), MAD / max | 0.420 / 1.99 K | 0.560 / 1.44 K | 9019 (MAD) |
| G4F2 dense carrier (14), MAD | 3.50 K | 2.97 K | 8875 |
| G5F2 Shen 2012 liquidus MAD (2 K) | **2.039 K** | 2.594 K | 9019 |
| G5F2 Davis 1962 crystal points MAD (2 K) | **1.902 K** | 2.148 K | 9019 |
| G5F3 Davis line, pressure MAD / T-equivalent MAD | 1.64 % / 0.324 K | 1.67 % / 0.330 K | equal |
| G5F3 Souza line (204 to 216 K), pressure MAD / T-equivalent MAD | 2.50 % / 0.444 K | 1.52 % / 0.063 K | 8875 |
| G5F3 Davis liquid x on the line, ln MAD | 11.9 % | 14.2 % | 9019 |
| G5F3 Davis vapour y on the line, ln MAD | 21.2 % | 20.2 % | equal |
| G5F4 Campestrini 2022 MAD (2 K) | 0.560 K | 0.832 K | 9019 |
| G5F4 Riva-Stringari 2018 MAD | 2.64 K | 2.99 K | 9019 |
| G5F5 Gao binary / 10 % N2 (report) | 2.58 / 0.77 K | 3.14 / 1.02 K | 9019 |
| G5F5 Gao 30 / 50 / 70 % N2 (report) | 2.80 / 4.13 / 6.13 K | 2.21 / 3.53 / 5.50 K | 8875 |
| G5F5 Sampson (report) | 5.52 K | 6.11 K | 9019 |

**Reading.**

- **Methane-rich liquids.** The liquidus there lies about 2 K cold with 9019 (solubility about 25 % high, bias
  -2.04 K on Shen). A smaller fusion enthalpy raises the ideal solubility and makes it colder still.
- **N2-rich solvents.** The family freezes warm there, so 8875 helps.
- **Near the triple point.** The three-phase line near CO2's triple point is dominated by the crystal against nearly
  pure CO2 liquid, where the reference-equation value 8875 is right for the reference fluid. On the pilot family the
  cubic's 0.5 % low vaporization enthalpy moves the effective value (P4 stage 1, section 5.3).
- **Sublimation side.** It is unchanged in kind: 8875 fails the P0 sublimation-pressure target.

**Proposal: keep 9019 J/mol.** Nothing moves: no record revision, no pilot spine fingerprint, no bundled pin, no stage
re-run.

**Where the liquidus bias comes from.** The 2 K cold bias of the liquidus is not a fusion-enthalpy question. The
crystal's side is right: G4F1 bias -0.36 K in methane gas, and the three-phase pressure within 1 to 2 %. The liquid's
CO2 solubility is too high (Davis x on the line +6 % biased, the liquidus 25 % in x). This is the cubic's CO2
fugacity in cold liquid methane with the E-PPR78 CH4/CO2 kij(T) at 110 to 170 K; Maltby et al. 2025 reach 0.59 % of T
on Shen with a regressed kij of 0.123. Tuning the fusion enthalpy to it would be fitting to holdouts; the kij belongs
to P3/P7 (not changed here).

## 4. G5 families (targets, measurements, bounds)

Scoring per D17: a dataset's MAD against the P0 target, with per-dataset and per-point caps at what is measured plus
a margin. Holds and out-of-domain points are counted apart. Temperatures are scored at the printed composition and
pressure: a liquid by `freezingTemperature`, a vapour by `frostTemperature`. Printed outputs:
`research/.../p5-solid-liquid/output-science.thermo.qualification.G5F*.txt`.

### 4.1 G5F1 pure CO2 melting (`G5F1PureCarbonDioxideMeltingTest`)

| P, MPa | 0.6 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| T_m model - Span-Wagner 3.10, K | -0.0009 | -0.0053 | -0.0166 | -0.0285 | -0.0408 | -0.0537 | -0.0670 | -0.0808 | -0.0951 | -0.1098 | -0.1249 |

- **Melting temperature.** Worst 0.1249 K against the D16 declared 0.13 K (bound 0.13 K). Below T_m - 0.05 K TP
  gives the crystal alone; above T_m + 0.05 K the liquid.
- **Melting step in PH from the energy balance.** h_L - h_s at T_m is 9014.97 J/mol at 1 MPa, 8983.71 J/mol at
  5 MPa and 8949.58 J/mol at 10 MPa (bound 8000 to 10000 J/mol).
- **Family triple point.** 216.591614 K and 516,047.8 Pa (Span-Wagner 216.592 K and 517,950 Pa: the cubic's
  saturation pressure is 0.37 % low there, P4 stage 1). The melting and sublimation lines meet there within 1.5e-5 K.
- **Pure CO2 in UV inside the triple triangle** (1 mol, 3e-4 m3, U -413,370 J): `VAPOR_LIQUID_SOLID` with vapour
  0.0831 mol, liquid 0.6469 mol and crystal 0.2700 mol, from the balances (98 TP equilibria).

### 4.2 G5F2 liquidus in liquid methane (`G5F2CarbonDioxideLiquidusTest`)

Target 2 K (dataset MAD). Bounds: Shen MAD 2.2 K, Davis MAD 2.1 K, per point 4.0 K; no holds or failures.

| Dataset, group | Points | MAD, K | Bias, K | Max, K |
|---|---|---|---|---|
| Shen 2012, CH4 | 9 | 2.365 | -2.365 | 2.925 |
| Shen, CH4 + N2 (0.90 / 0.95 / 0.98 CH4) | 9 each | 1.660 / 2.020 / 2.176 | same sign | 3.368 / 3.667 / 2.945 |
| Shen, CH4 + C2H6 (0.90 / 0.95 / 0.98 CH4) | 9 each | 2.012 / 1.936 / 2.103 | same sign | 2.833 / 2.438 / 2.666 |
| **Shen 2012 (dataset)** | 63 | **2.039** (target missed by 0.039 K) | -2.039 | 3.667 |
| **Davis 1962 Table 4 (dataset)** | 11 | **1.902** (met) | -0.659 | 2.786 |
| Pooled | 74 | 2.018 | -1.834 | 3.667 |

### 4.3 G5F3 CO2 + CH4 three-phase line and its compositions (`G5F3ThreePhaseLineTest`)

- **Method.** The model's three-phase pressure at each measured temperature comes from a bisection on the engine's
  own TP answers of a 95 % CO2 feed: crystal beside vapour below, crystal beside liquid above.
- **Units.** Scored in relative pressure. The temperature equivalent -ln(P_model/P)/(d ln P/dT) is used where the line
  is not flat, |d ln P/dT| > 0.01 (Davis 30 of 31 points, Souza 6 of 7).
- **Target.** 2 K in the equivalent temperature.

| Dataset | Points | |dP/P| MAD | Max | T-equivalent MAD | Max | Bounds |
|---|---|---|---|---|---|---|
| Davis 1962 Table 1 (97.5 to 211.7 K) | 31 | 1.64 % | 5.61 % (97.5 K, methane's own vapour pressure there) | 0.324 K | 0.849 K | 2.0 % / 6.5 %; 2 K / 2.5 K |
| Souza 2020 (204 to 216 K) | 7 | 2.50 % | 4.60 % | 0.444 K | 2.032 K (207.07 K, next to the line's pressure maximum) | 3.0 % / 5.5 %; 2 K / 2.5 K |
| Davis Table 3 vapour y on the line | 8 | ln MAD 21.2 % | 104.9 % (140.9 K: data 0.0012, model 0.00042) | | | 25 % / 115 % |
| Davis Table 4 liquid x on the line | 11 | ln MAD 11.9 % | 19.9 % | | | 14 % / 23 % |

### 4.4 G5F4 N2 ternaries (`G5F4TernarySolidLiquidVapourTest`)

Target 2 K. Series 3 and 4 of Riva-Stringari carry O2 and are skipped.

| Dataset, group | Points | MAD, K | Max, K |
|---|---|---|---|
| Campestrini 2022 SVE 0 / 5 / 10 % N2 | 12 / 8 / 12 | 0.499 / 0.517 / 0.522 | 0.684 / 0.836 / 0.696 |
| Campestrini SLE 0 / 5 / 10 % N2 | 6 / 6 / 5 | 0.842 / 1.111 / 0.907 | 1.765 / 1.506 / 1.108 |
| Campestrini SLVE vapour / liquid | 12 / 6 | 0.186 / 0.439 | 0.375 / 0.520 |
| **Campestrini 2022 (dataset)** | 67 | **0.560** (met; bound 0.8, max bound 2.2) | 1.765 |
| Riva-Stringari 2018 N2 liquids / vapours | 6 / 6 | 3.690 / 1.593 | 6.731 / 2.971 |
| **Riva-Stringari 2018 (dataset)** | 12 | **2.642** (missed; bound 3.0, max bound 7.5) | 6.731 |

Riva's series-2 liquids (about 9 % N2) at 124.5 and 132.1 K are 6.7 K warmer than this family's liquidus. The family's
solubility at that solvent agrees with Shen and Gao, so this is an inconsistent set, bounded at what it measures
(D17's rule).

### 4.5 G5F5 report only (`G5F5ReportOnlyLiquidusTest`)

No bound on deviations. Only the counts are checked, and every point inside the domain must be answered.

| Dataset, group | Scored | Out of domain | MAD, K | Bias, K | Max, K |
|---|---|---|---|---|---|
| Gao 2012 CH4 (Davis locus pressure) | 9 | 0 | 2.580 | -2.580 | 4.813 (172 ppm) |
| Gao 10 % N2 | 6 | 0 | 0.769 | -0.401 | 1.270 |
| Gao 30 % N2 | 8 | 0 | 2.797 | +2.797 | 6.031 |
| Gao 50 % N2 | 8 | 1 (83.15 K, below methane's 90.69 K) | 4.125 | +4.125 | 5.335 |
| Gao 70 % N2 | 9 | 0 | 6.134 | +6.134 | 9.684 |
| Sampson 2023 (52 to 500 ppm, 7 to 10 MPa) | 3 | 3 (above 10 MPa) | 5.517 | -5.517 | 6.444 |

Kurata-Im Table VI is printed as data against model (x, y and P on the line). Example at 190.21 K: P 3.978 against
3.995 MPa, x 0.0970 against 0.0953, y 0.0413 against 0.0416.

### 4.6 G5F6 consistency (`G5F6ConsistencyTest`)

**Twenty liquids 3 K below their freezing points.** They cover binaries, N2 and C2H6 ternaries, ethane-rich liquids
and dense N2, at 1 to 9 MPa.

| Check | Measured | Bound |
|---|---|---|
| Conservation | 1.7e-16 | 1e-13 |
| Every fluid phase at the crystal's chemical potential, max \|mu - mu_s\|/RT | 2.6e-11 | 1e-9 |
| PH and UV round trips (T, P, crystal) | 1.7e-10 | 1e-9 |
| Crystal just below the onset | at most 1e-3 of the feed's CO2 at T_f - 1e-4 K | |
| Reversal (above the onset and back) | 0 | 1e-12 |

- The reversal check is a determinism check: the engine carries no state between calls. The path reversibility is
  the network's, section 6.
- Classifications at T_f - 3 K: 18 are `LIQUID_SOLID` and 2 (dense N2 at 7 and 9 MPa) are `VAPOR_SOLID`.

**The four WP5 grid states P4 held as THREE_PHASES.** These are N2/CH4/C2H6/CO2 5/80/10/5 at 100 K 0.1 MPa, 120 K
0.1 and 0.3 MPa, and 150 K 0.3 MPa.

- All four are answered `VAPOR_LIQUID_SOLID`, with conservation 1e-13 and equal chemical potentials 1e-9 (for
  example 5.95e-11 RT).
- PH and UV round trips are within 1.3e-12.
- There is no crystal above the whole feed's onset (163.53 K at 0.1 MPa, 173.18 K at 0.3 MPa, a frost point of the
  vapour), and the crystal vanishes continuously just below it.

**Engine three-phase point against the bisection** (3 % CO2 in CH4 at 1, 2 and 3 MPa). The engine's T3 from PH is
149.209, 166.513 and 179.112 K. At that T3 the bisection's pressure agrees to 1.3e-8 to 2.1e-8, and x and y to 1e-6.

## 5. The previous holds re-run

| Former hold | Now |
|---|---|
| `SpineNetworkPathTest` WP5 grid: 69 typed holds (65 `SOLID_LIQUID`, 4 `THREE_PHASES`) | All answered: 620 states evaluated (551 before), 102 domain refusals and 4 steam as before, 0 holds, 0 non-convergence (asserted) |
| `G3F7` CO2 in liquid methane, 91 to 120 K (typed holds in P4) | 30 of 30 answered on the crystal competition, 26 `LIQUID_SOLID`, 4 one liquid (x 1e-4 at 110 and 120 K). Worst \|mu - mu_s\|/RT 5.2e-10, conservation 1.7e-16, the liquid on the engine's own liquidus within 4.8e-9 K. The pilot network adopts every state (liquid slot). Solubility 1.30 x Shen at 112 K and 1.19 x Gao at 123.2 K (own bound: factor 1.6). The fluid-only network contract still refuses below 216.592 K |
| `CrystalDepositionIslandTest` (d): 70 % CO2 in ethane at 200 K | Charged at 0.5 MPa (1 L, 16.42 mol CO2 in 23.46 mol) the vessel freezes 1.36296 mol CO2 beside the ethane-rich dense fluid at 211.418 K and 9.733 MPa (`LIQUID_SOLID`). The engine's UV answer is reproduced to 1e-9, and the next interval is a fixed point. Charged at 2 MPa the rigid vessel's answer lies above 10 MPa (the latent heat warms a nearly incompressible liquid): a typed domain hold naming node 1 |
| G4F2: 14 onsets in dense supercritical N2 labelled liquid-like (140 to 170 K, 6.1 to 9.1 MPa) | Scored as the dense carrier: MAD 3.505 K, bias +3.505 K, max 5.900 K (Sonntag 140 K, 7.09 MPa), 4 of them research-only (the N2 critical band). The deviation grows smoothly with pressure along each isotherm across the label change (140 K: -0.06, +0.33, +1.99 K at 3 to 5 MPa as vapour, then +4.92, +5.90 K): the model's CO2 solubility in near-critical N2, not a label artefact. Original caps unchanged on the 51 vapour-carrier points (MAD 0.420 K, max 1.99 K); the dense group has its own bound (MAD 4.0 K, max 6.5 K); all 65 scored points MAD 1.084 K against the 1.5 K target. The original per-point 2.5 K cap is not applied to the dense carrier: it is kept outside the G4 allowlist (section 7) rather than loosening the caps |
| G4F1: 1 binary and 4 ternary onsets where the gas condenses first on this family | Answered beside a liquid, reported apart. Binary pooled MAD 1.354 K unchanged. Xiong's frost pressures: 0 holds, 14 with no onset below the ceiling (the liquid does not freeze below 10 MPa) |
| `GasSolidEquilibriumTest` grid: crystal beside a liquid | 7 former holds answered; typed holds 0. The remaining two kinds are demonstrated (two liquids; three fluid phases) |
| The four `THREE_PHASES` grid states | Answered `VAPOR_LIQUID_SOLID` (section 4.6) |

## 6. Network tests (`CrystalDepositionIslandTest`, `FluidCheckpointCodecTest`, `CrystalIslandRuntimeTest`)

### 6.1 (h) a vapour-liquid methane vessel with dissolved CO2

- **Setup.** One mole of 2 % CO2 in CH4, charged at 172 K and 2.45 MPa: 19 % vapour, no crystal, and nothing freezes
  in the first interval.
- **Cooling.** The vessel is cooled by 1500 J through the network's energy input: -150 W over two 5 s intervals,
  carried by a 1e-12 mol/s methane trace.
- **Cooled state.** 156.409233 K and 1.3618 MPa, with crystal 0.006615872 mol, liquid x_CO2 0.014724 and vapour y
  2.22e-3 (3 substeps, 0 rejected).
  - The engine's UV answer for the inventory is the binary's three-phase point, `VAPOR_LIQUID_SOLID`.
  - It is reproduced to 1e-9 in crystal, T and P.
  - CO2 is conserved to 1e-12 and the energy to 1e-9 of 1500 J.
- **Warmed back by 1500 J.** The crystal dissolves completely. The inventory's energy returns exactly (0 J) and CO2 to
  1e-12. The stated state returns to T +7.27e-11 and P +5.23e-10 (bound 1e-9).
- **Why not 1e-11.** The brief's 1e-11 (P4's vapour case, 1.1e-11) is not met on this vessel's state, although the
  inventory returns exactly. The residual is the network's closure of the node state to its inventory: a vessel
  holding a liquid turns the same energy closure into a larger pressure (dP/dU at fixed volume). The liquid-full case
  below meets 1e-11 in temperature.

### 6.2 (h, new) a liquid-full vessel freezes below its liquidus

- **Setup.** One litre of compressed liquid methane with 3 % CO2 (21.37 mol) charged at 170 K and 9 MPa, no vapour.
  It is cooled by 3000 J: -200 W over three intervals, carried by a methane trace.
- **Cooled state.** The rigid liquid cools and its pressure falls by about 1 MPa per interval. It passes its liquidus
  while liquid-full and freezes CO2 beside the liquid alone: 166.247834 K and 6.5386 MPa, crystal 0.039016947 of
  0.641206114 mol CO2, liquid x_CO2 0.028226, no vapour.
  - The engine's UV answer is `LIQUID_SOLID`, reproduced to 1e-9.
  - CO2 is conserved to 1e-12.
- **Warmed back by 3000 J.** The crystal dissolves and the vessel returns to T +4.59e-12 and P -5.66e-11, with the
  energy returned exactly.

### 6.3 The liquid-full bubble-point limit (measured, probe `P5LiquidFullVesselProbe`)

The (h) test's comment said that a liquid-full vessel "meets the network's active-set cycle with pure methane as
well". The probe shows otherwise:

- **Pure methane passes.** Liquid-full at 150 K and at 172 K from 5 MPa, it crosses its bubble point and goes on
  (vapour appears, 3 to 4 substeps per interval).
- **Mixtures fail at the bubble point.** Every mixture tested fails there with the island's "Phase/device active-set
  cycle" or "Newton iteration limit ... active-set pass" (about 500 accepted and 530 rejected substeps, no partial
  commit):
  - CH4 with 2 % C2H6 at 2.29 MPa;
  - CH4 with 2 % N2 at 1.98 MPa;
  - CH4 with 0.5 % or 2 % CO2 near 2.3 MPa (at -50 W near 2.07 MPa), with no crystal present;
  - the 3 % CO2 vessel of 6.2, after freezing, in the interval after 158.9 K and 1.89 MPa ("substep refinement exhausted").
- **Reading.** This is a pre-existing limit of the network's step for liquid-full mixtures at their bubble point, not a
  crystal limit (the C2H6 and N2 cases involve no crystal and no P5 code). The comment is corrected, and it is listed
  for P6.

### 6.4 Holds, checkpoint, presentation

- **Remaining hold.** It is typed: N2 + C2H6 (type III) with 5 % CO2 at 95 K and 2 MPa, two liquids beside the crystal,
  gives `UnsupportedPhases(CRYSTAL_PHASES, NOT_IMPLEMENTED)` at node 1 with the key "unsupported-phases: a crystal phase
  set ...". It is not a domain hold. `CrystalIslandRuntimeTest`'s held island reports the same status, and the
  presentation reaches the views at the bucket ticks only.
- **Checkpoint.** `FluidCheckpointCodecTest`: a liquid-solid vessel (70 % CO2 in ethane charged at 200 K and 0.5 MPa,
  frozen at its first solve) round-trips with its island, clocks, fences, allowance, anchor and last result. The
  inventory, the crystals' volume and energy, the liquid slot and the temperature are equal, and re-encoding gives the
  same bytes.
- **Earlier tests.** P4's (a), (b), (c) and the withdrawal test keep their numbers.

## 7. G5 status (proposal for the lead)

**Qualified against independent data** (declared-error grade):

- **Pure CO2 melting** to 10 MPa: 0.125 K.
- **CO2 liquidus in methane-rich liquids**, the solvent at least 90 % CH4 with N2 or C2H6 as the rest, 0.02 to 20.5 %
  CO2, 112 to 201 K:
  - Shen 2.04 K, Davis 1.90 K, Campestrini SLE 0.84 to 1.11 K, Gao binary 2.58 K (report).
  - A consistent 2 K cold bias.
  - **Target 2 K missed by 0.04 K on Shen:** proposed declared error 2.2 K dataset MAD, 4 K per point.
- **CO2 + CH4 three-phase line**, 97.5 to 215.8 K: pressure 1.6 to 2.5 %, within 0.45 K in temperature (2.0 K worst
  next to the pressure maximum).
- **Its phase compositions:** x 11.9 %, y 21 % in ln.
- **N2 ternary frost, liquidus and SLVE** with up to 10 % N2: Campestrini 0.56 K.

**Qualified by consistency only** (no dataset measures them): phase amounts, heats, PH/UV, reversibility, and the
finite network vessels (conservation 1e-12, the engine's UV answer 1e-9, reversibility 4.6e-12 to 7.3e-11 in
temperature).

**Allowlist.** The CO2-I crystal of `createcheme:pilot_cryogenic`:

- beside a liquid or a vapour-liquid split whose liquid is methane-rich (the solvent at least 90 % CH4, the rest N2 or
  C2H6);
- pure CO2 melting and freezing;
- from methane's 90.69 K floor to 216.6 K and to 10 MPa;
- in finite network vessels.

G4's gas-solid allowlist continues.

**Not qualified:**

- N2-rich solvents (30 to 70 % N2: Gao +2.8 to +6.1 K warm; Riva's series 2 at about 9 % N2 is contradicted);
- the LNG trace liquidus below about 200 ppm (Sampson -4.4 to -6.4 K; Gao 172 ppm -4.8 K; every published model is 3 %
  of T or worse there);
- ethane-rich and CO2-rich liquids (CO2 + C2H6: consistency only, no data scored);
- the dense supercritical N2 carrier of G4F2 (140 to 170 K, 6 to 9 MPa; declared 6 K, answered, outside the G4
  allowlist);
- phase sets beside a crystal with two liquids or three fluid phases, and two crystals (typed holds);
- above 10 MPa;
- liquid-full mixture vessels crossing their bubble point (network limit, section 6.3);
- the CO2 liquid viscosity below the triple point (extrapolated, record r2).

## 8. Tool folder and research artifacts

- **Tool folder** (main checkout, staged identically in the worktree): `tools/p5-solid-liquid-scans/`.
  - `README.md`.
  - The two JUnit probes of this session (`P5FusionEnthalpyProbe`, `P5LiquidFullVesselProbe`) with
    `p5-probes-against-1cfb1fc.patch`, which re-attaches them (verified with `git apply --check`). They were never
    committed, so there is no removal commit.
  - The first P5 agent's javac runner (`run.sh`, `RunTests.java`) and 14 exploratory mains, including `P5Measure`
    and `P5CostProbe`.
  - The CO2 subcooled-liquid viscosity builder (`co2_liquid_viscosity_below_triple_point.py` with its JSON) behind
    `pilot_carbon_dioxide` liquid viscosity r2.
- **Research** (main checkout): `research/2026-09-24-coolprop-low-temperature/p5-solid-liquid/`.
  - The printed output of each G5 family, G4F1, G4F2, G3F7, `SpineNetworkPathTest`, `GasSolidEquilibriumTest`,
    `CrystalDepositionIslandTest`, `FluidCheckpointCodecTest` and `CrystalIslandRuntimeTest` from the final full run.
  - The two probe outputs.
  - `logs/`: every Gradle log of the stage, `gate-counts.txt` and the exact regression report.
- **Gate test classes.** No test class of this stage is a probe: the G5 families and network tests are gate tests.

## 9. Gradle runs

- **How they were run.**
  - Git Bash in the worktree, one invocation at a time under `build/gradle.lock` (holder `p5b`), with
    `JAVA_OPTS=-Xshare:off` and `--offline`.
  - No dev client was running (checked by `jps -lvm`).
  - Logs are in `research/.../p5-solid-liquid/logs/`.
- **Before P5.** P4 stage 2b's final gates, in `research/.../p4-network/logs/`.
- **Inherited.** The state as inherited (`12cf449`) was run once: `test` 1,281 tests, 264 classes, green
  (`test-full-inherited.log`).

| Gate | Command | Before P5 (P4 stage 2b) | After (`4929e0f`) |
|---|---|---|---|
| Full suite | `./gradlew test --offline` | 1,267 tests (258 classes) | **1,282 tests (264 classes), 0 failures** (`test-full-final.log`, 2 min 46 s) |
| Fluid science | `./gradlew fluidScienceTest --offline` | 226 | **229, 0 failures** (`fluidScienceTest-final.log`) |
| Fluid runtime | `./gradlew fluidRuntimeTest --offline` | 230 | **231, 0 failures** (`fluidRuntimeTest-final.log`) |
| Regression | `./gradlew fluidSolverRegression -PfluidRegressionMode=exact --offline` | chain-100 0 | **chain-100 0.000e+00** on moles, temperature, phase fraction and flow; the three island fixtures skipped as before (`fluidSolverRegression-exact.log`, `solver-regression-report-exact.json`) |
| GameTests | `./gradlew runFluidGameTestServer -PfluidGameTestRunId=p5b-20260925 --offline` | 30 of 30 | **All 30 required tests passed**, fresh world `run/fluid-gametest-p5b-20260925` (`fluidGameTest-final.log`) |

- **Test-count changes.** +15 in the full suite:
  - G5 families, 11 tests: F1 3, F2 1, F3 2, F4 1, F5 1, F6 3;
  - `CrystalDepositionIslandTest`, net +3: P4's (d) hold test became the (d) freeze, and (h), the two-liquid hold and
    the liquid-full test are new;
  - `FluidCheckpointCodecTest`, +1.
- **Other runs.** The focused runs and the two probe runs are also in `logs/`.
- **Pins.** `LegacyNetworkPathPinTest`, `WorkerTrajectoryEquivalenceTest` (P12), `CadenceTrajectoryQualificationTest`
  (P31), `bundledPackageFingerprintsAreUnchanged` (eight pins) and `PilotCryogenicCatalogTest` (pilot pins) are all in
  the green full suite.
- **No benchmark.** The fluid-only per-solve path did not change (section 2).

## 10. Proposed decision (the lead numbers it D19)

**D19 (2026-09-25): the fusion enthalpy after P5, and the reading of gate G5.**

- **Question.** Does the P5 evidence favour 8875 J/mol (Jäger-Span 2012, Maltby et al. 2025) over the pilot's effective
  9019 J/mol (D16)? What does G5 admit, now that the engine answers a crystal beside a liquid?
- **Evidence.** `P5_SOLID_LIQUID_EQUILIBRIUM.md` sections 3 to 6 (`33b4ebe` to `4929e0f`), comparing 9019 with 8875
  J/mol:
  - liquidus: Shen 2.04 against 2.59 K, Davis 1.90 against 2.15 K, Campestrini 0.56 against 0.83 K, Riva-Stringari
    2.64 against 2.99 K, Gao binary 2.58 against 3.14 K;
  - three-phase line: Davis 1.64 against 1.67 % (0.32 against 0.33 K), Souza 2.50 against 1.52 % (0.44 against
    0.06 K);
  - line compositions: x 11.9 against 14.2 %, y 21.2 against 20.2 %;
  - sublimation pressure at 150 K: 2.84 against 6.56 % (P0 5 %);
  - sublimation enthalpy at 194.67 K: -0.16 against -0.74 % of the calorimetric 25,236 J/mol;
  - frost points: G4F1 1.354 against 1.339 K, G4F2 0.420 against 0.560 K;
  - pure melting: 0.125 against 0.094 K.

  The methane-rich liquidus lies about 2 K cold with 9019 (solubility about 25 % high), which a smaller fusion enthalpy
  worsens; its source is the liquid's CO2 fugacity (the crystal side scores -0.36 K bias in methane gas). The engine
  answers every former `SOLID_LIQUID` hold (the 69 grid states, G3 F7, the (d) charge, the four THREE_PHASES states);
  consistency holds to 1.7e-16 (conservation), 2.6e-11 RT and 1.7e-10 (round trips); the network conserves CO2 to
  1e-12, reproduces the engine's UV answer to 1e-9 and returns within 4.6e-12 to 7.3e-11 in temperature. The 14 dense-N2
  G4F2 onsets score 3.50 K (max 5.90 K); all 65 scored points score 1.08 K.
- **Chosen (standing instruction; reversible at G6).**
  1. **Fusion enthalpy.** The pilot's anchor stays 9019 J/mol (`crystal-carbon_dioxide-i-r2` unchanged, no pin moves).
  2. **Classifications.** Under `FLUID_AND_CRYSTALS` the engine answers a crystal beside a vapour, a liquid, a
     vapour-liquid split or a dense one-root fluid (`VAPOR_SOLID`, `LIQUID_SOLID`, `VAPOR_LIQUID_SOLID`), a pure
     species frozen completely, a pure melting step, a binary's three-phase step and a pure triple point from the
     balances, and `freezingTemperature` (the liquidus of a liquid as one phase).
  3. **Holds.** The typed hold `CRYSTAL_PHASES` (engine) and `UnsupportedPhases.Kind.CRYSTAL_PHASES` (network)
     replace `SOLID_LIQUID` and cover only two liquids or three fluid phases beside a crystal and two crystals;
     non-convergence stays `NOT_CONVERGED` with diagnostics.
  4. **Network.** A network vessel carries its crystal inventory beside a liquid or both fluid phases, moved by the
     engine's UV answer as in D18.
  5. **Liquidus target.** The G5 liquidus target is read as the dataset MAD with a declared error of 2.2 K for
     methane-rich liquids (Shen 2.04 K; the 2 K P0 target is met by Davis and pooled within 0.02 K), per point 4 K.
  6. **Gate G5** is met at the declared-error grade with the allowlist of section 7: the CO2-I crystal of
     `createcheme:pilot_cryogenic` beside methane-rich liquids and vapour-liquid splits (solvent at least 90 % CH4,
     the rest N2 or C2H6) and pure CO2 melting, 90.69 to 216.6 K, to 10 MPa, in finite network vessels. Phase amounts
     and heats are qualified by consistency (no dataset measures them).
  7. **Dense N2 carrier.** It is answered at a declared 6 K and kept outside the G4 allowlist; G4F2's caps on the
     vapour carrier are unchanged.
- **Rejected.**
  - 8875 J/mol: it fails the P0 sublimation target again and worsens every methane-rich liquidus set by 0.25 to
    0.56 K.
  - A fitted fusion enthalpy: tuning to holdouts, and the liquidus bias sits in the liquid.
  - Loosening G4F2's per-point cap to absorb the dense carrier.
  - Holding liquid-solid states for the sake of the 0.04 K liquidus miss.
- **Affected milestones.** G5 (met, if the reading is taken), P6/G6 (section 12), P7 (the CH4/CO2 kij at 110 to 170 K,
  N2-rich solvents).
- **Coverage changes.** Pilot network vessels freeze and dissolve CO2 beside methane-rich liquids at the declared-error
  grade. The former `SOLID_LIQUID` holds are answers. The bundled network is unchanged.

## 11. Commits

| Commit | Message |
|---|---|
| `33b4ebe` | Crystal beside a liquid in the equilibrium engine (P5) |
| `0a8e544` | The fluid network carries a crystal beside a liquid (P5) |
| `d4d7e82` | Crystal solve: frost onset of a gas as one phase, band-typed splits steer, strict UV (P5) |
| `2bcf431` | G5 families: CO2 beside a liquid against the holdouts (P5) |
| `12cf449` | Liquid-solid checkpoint fixture charged at 0.5 MPa (P5) |
| `1cfb1fc` | A liquid-full vessel freezes below its liquidus and dissolves back (P5) |
| `4929e0f` | G5F6: PH/UV round trips and the onset at the four grid states (P5) |

Proposed `CHANGELOG.md` `[Unreleased]` line (under Changed):

- P5, solid-liquid and three-phase competition with the CO2-I crystal. **Breaking for pilot-package worlds (fresh
  world):**
  - **Engine.** Under `FLUID_AND_CRYSTALS` the engine answers a crystal beside a liquid, a vapour-liquid split or a
    dense one-root fluid (`LIQUID_SOLID`, `VAPOR_LIQUID_SOLID` beside `VAPOR_SOLID`: one general solve on the solid
    amount with the reduced feed re-flashed), pure CO2 freezing and melting in TP/PH/UV, a binary's three-phase step
    and the pure triple point from the balances, and `freezingTemperature` (liquidus) and `frostTemperature`.
  - **Holds.** The typed hold is `CRYSTAL_PHASES` (two liquids or three fluid phases beside a crystal, two crystals),
    and the network's `UnsupportedPhases.Kind.SOLID_LIQUID` becomes `CRYSTAL_PHASES`.
  - **Network.** Pilot vessels freeze and dissolve CO2 beside a liquid through the engine's UV answer.
  - **Record.** The pilot CO2 liquid viscosity extends to 90 K (record r2, extrapolated).
  - **Gate families.** `G5F1` to `G5F6` hold pure melting (0.125 K), the liquidus (Shen 2.04 K, Davis 1.90 K), the
    CO2 + CH4 three-phase line (Davis 1.64 %, Souza 2.50 %), the N2 ternaries (Campestrini 0.56 K), report-only sets
    and consistency (1.7e-16, 2.6e-11 RT, 1.7e-10).
  - **Former holds.** The 69 WP5 grid holds, G3 F7 and the 14 dense-N2 G4F2 onsets are answers.
  - **Unchanged.** The fusion enthalpy stays 9019 J/mol (D19). The bundled network is unchanged (exact regression
    zero, pins, 30 of 30 GameTests).
  - **Commits and batch.** `33b4ebe` to `4929e0f`; batch `2026-09-24-coolprop-low-temperature`,
    `P5_SOLID_LIQUID_EQUILIBRIUM.md`.

## 12. Open items (P6/G6 and later)

- **Network.**
  - Liquid-full mixture vessels at their bubble point: the network's active-set cycle (section 6.3). This is
    pre-existing for any mixture, and it limits a liquid-full crystal vessel from crossing its bubble point.
  - Bulk withdrawal from a liquid-solid vessel (the v1 rule, fluid phases only) is untested with a liquid.
  - A crystal UV warm start (cost; P4 open item) now also applies to liquid-solid vessels.
  - The GUI check through the MCP bridge (the reservoir screen with a crystal beside a liquid; the withdrawal
    wording).
- **Model.**
  - The methane-rich liquidus's 2 K cold bias: the E-PPR78 CH4/CO2 kij at 110 to 170 K (P3/P7 decision).
  - N2-rich solvents freeze warm.
  - The trace (ppm) liquidus.
  - Dense supercritical N2 carrier.
  - Label question for supercritical carriers.
- **Data.** The CO2 + C2H6 liquidus has no holdout scored. The survey's request item 13 (Agrawal-Laverman, He 2022,
  Liu 2022, Donnelly-Katz, Brewer-Kurata) was not received.
- **Code paths without a test.**
  - Two crystals (one crystal on the pilot).
  - A wet vessel with a crystal beside a liquid.
  - The three-phase step of a pseudo-binary with more than trace third species.
- **Record text.** The advisory `CO2_CRYSTAL_JAEGER_SPAN` of the pilot package still says "no consumer reads it yet"
  (P4 item; a record change for P6's pin review).

# P0: baseline inventory, coverage, acceptance targets and source manifest

**Status.** P0 deliverables for gate G0 (2026-09-24). This is a research document: no Gradle run, no product code or data change, no commit. The targets marked "proposed for G0" await the owner's freeze at G0.

**Batch.** `2026-09-24-coolprop-low-temperature`, plan [UNIFIED_MULTIPHASE_THERMO_PLAN.md](UNIFIED_MULTIPHASE_THERMO_PLAN.md) revision 3, section 5 P0.

**Baseline code.** Worktree `claude/coolprop-multiphase-thermo-37f6b0` at `f9d6be10de8f73a0a56ece3effe2cd572803b485` (main, `mod_version` 0.4.0). Material records were read from `src/main/resources/data/createcheme/materials/`.

**Inputs.**
- The plan (sections 2, 3, 5 P0 / G0 and 7).
- [DECISION_LOG.md](DECISION_LOG.md).
- The plan review ([UNIFIED_MULTIPHASE_THERMO_PLAN_REVIEW.md](UNIFIED_MULTIPHASE_THERMO_PLAN_REVIEW.md), sections 3 and 5, F10 and F12).
- The VDU thermo document (`documentation/2026-08-31-v3-vdu/V3_VDU_LITERATURE_AND_THERMO_DATA.md`, section 2).
- `MATERIALS.md` and `FluidDomain.java`.

**Computations run.** Everything was run with Node 22 on local files:
- the CEA species extraction (`research/.../sources/nasa-cea/`);
- the CoolProp summary (`research/.../sources/coolprop/`);
- the comparison of the bundled ideal-gas Cp fits with CEA (section 2.4);
- the PR78 against Maxwell-Bonnell check (`research/2026-09-24-coolprop-low-temperature/vdu-k-policy/`).

**Web research.** Two opus subagents did web research on process anchors and on the uncertainties of the reference equations. An Explore subagent inventoried the tests.

**Fetched sources.** Listed with hashes in `research/2026-09-24-coolprop-low-temperature/sources/MANIFEST.md`.

## 0. Headline findings

1. **The widening is narrow before any code changes.** Every package's fluid-network envelope is capped at 2 MPa and 900 K. Only nitrogen is admitted below 273.16 K. Water starts at its 273.16 K triple point, and every hydrocarbon species, from methane to PC12, is refused below 293.15 K.
   - As a consequence, pure liquid methane and pure liquid ethane cannot exist in the network today.
   - Liquid nitrogen exists only up to Tsat(2 MPa), about 115 K.
   - No dense supercritical state of any pilot fluid is reachable.
   - The column packages are narrower: 298.15–900 K and 50 kPa–2 MPa, with states below 50 kPa refused as `VDU_REQUIRED`.
2. **The ideal-gas spine is not a drop-in.**
   - CEA's gas polynomials for ethane, propane, the butanes, the pentanes, propylene and H2S start at 300 K, and none starts below 200 K. Cryogenic ideal-gas parts need a second bounded source (the CoolProp alpha0 functions).
   - The CoolProp 8.0.0 build (`ae81610e`) does not carry the ATcT formation enthalpies the plan cites. They arrived on master in commit `9b35f538` (2026-08-22), with EOS blocks unchanged.
   - CEA and ATcT formation enthalpies differ by 1 to 2.4 standard uncertainties for most hydrocarbons and by 13 for NH3.
   - Methane's ideal-gas Cp differs by up to 2.2 % inside today's qualified 298–900 K range between CEA (Gurvich 1991) and the NIST/JANAF Shomate fit that the bundled record follows. Choosing the spine is therefore a scientific correction inside the existing domain, not only an extension beyond 900 K.
3. **The E-PPR78 group table is not in the chapter the review cites.** The IntechOpen chapter points to the supplementary material of Xu et al. 2017 (IECR 56, 8143). That supplement is behind a bot check and was not fetched. The current 40-group matrix is Table S4 of the Supporting Information of Jaubert et al. 2022 (FPE 560, 113456); its author manuscript was fetched, the supplement was not. The Mohammadi 2021 hydrogen compilation publishes only its source list, not its 919 data points.
4. **D5: the VDU bias survives the crude regrouping.** On the current slate, PR78 runs 4.2 to 9.8 K colder than Maxwell-Bonnell at 13 kPa for PC06–PC12, and 6.9 to 12.9 K colder at 1 kPa.
   - A two-point anchoring removes the bias at the anchors, but it moves ω by +0.03 to +0.37 and Tc by −4.5 to −14.8 K for all twelve cuts.
   - It shifts PR78 saturation temperatures at the literature CDU's 250 kPa by −0.7 to −4.9 K.
   - It invalidates the pinned neural physics fingerprint and several bitwise or tight CDU pins, on every one of the eight packages.
   - Maxwell-Bonnell itself has no stated accuracy, and third-party checks report large low-pressure errors for heavy cuts.
   - Recommendation (section 6): confirm the provisional one. Keep PR78 as characterized, with the bias declared, through the pilot. Take the anchoring as a separate joint CDU/VDU data revision in the VDU batch.
5. **Process anchors: most confirmed, four corrected** (section 5).
   - **Confirmed at lecture, government or review level:**
     - steam-cracking coil outlet up to about 1143–1148 K;
     - coil pressure 170–250 kPa (basis unstated), dilution steam and residence time;
     - coker drum feed 739–761 K and drum 0.20–0.34 MPa abs;
     - VDU flash zone 2.7–13.3 kPa abs and top 1.3–2.0 kPa abs;
     - CDU flash zone 616–644 K;
     - ASU high-pressure column 0.56–0.6 MPa abs.
   - **Not found or patent only (corrected):**
     - The review's coil outlet 819–884 °C: US 5,990,370 gives 823–838 °C only.
     - The TLE outlet 576–592 °C is patent only. Open sources give 470–920 K depending on feed and exchanger stage.
     - The ASU low-pressure column is 1.4–1.5 bar abs, not 1.2–1.3.
     - The VGO hydrotreating 573–713 K wording was not found; open sources give about 589–700 K, with H2 partial pressure up to 10.4 MPa abs. Severe gas-oil service can therefore exceed the provisional 10 MPa ceiling.
   - **Basis still unstated:** Shell's diesel example is vendor only. The 36 bar compressor discharge is confirmed as a number but its basis is unstated.

## 1. Coverage table: before, target and achieved

### 1.1 How "before" is enforced today

**Fluid network.** Package `createcheme:tjl20_methane_nitrogen` (basis `createcheme:network` = `crude_19` + Nitrogen, plus the separate water model). `FluidDomain.of` builds the domain from data:
- the package `fluid_domain` is the envelope;
- each property record's `fluid_domain` is that component's range;
- water runs from its record's `triple_point` to `max_enthalpy_temperature`, with the envelope's pressure range.

A state is valid only inside the envelope and inside the range of every component it carries, traces included. Otherwise it throws `ThermoDomainViolation`, naming the violating component with the largest amount.

The catalog refuses a component `fluid_domain` that is wider than its vapour viscosity table (`MaterialCatalog` line 369). The network's liquid path evaluates every liquid at the 2 MPa reference and carries it to the state pressure with the global compressibility (1e-9 /Pa). The pressure ceiling is therefore a model limit, not only a declaration (review F1).

**V3 column.** Six column packages: `tjl19_dwsim` (basis `crude_18`, the literature preset), and `tjl20_methane` plus the five crude packages (basis `crude_19`).

- `PengRobinsonKernel` refuses any evaluation outside the package's `temperature_min/max_kelvin` and `pressure_min/max_pascal`: 298.15–900 K and 50 kPa–2 MPa for all six packages.
- `V3OperatingDomainValidator` admits a pressure profile only inside 50 kPa–2 MPa. Below 100 kPa it uses the `LOW_PRESSURE_HYBRID` lane; below 50 kPa it refuses with `BELOW_PACKAGE_PRESSURE` ("VDU_REQUIRED").
- The column evaluates the main Cp fit only; nitrogen's `below` segment is fluid-network only.
- Column water is the separate `iapws_shomate_watson` model: IAPWS auxiliary saturation down to 611 Pa, enthalpy 273–900 K.

**Shared records.** All eight packages share one record per crude cut and per light end (`crude_pc01`–`crude_pc12`, `tjl19_*`, `tjl20_methane`, `fluid_nitrogen`), at revision `crude-regrouped-r1`.
- The five crude packages hold methane at zero (C1/C2 allocated to ethane).
- The package `temperature_min/max` and `pressure_min/max` fields enter `physicsFingerprint`.
- The `fluid_domain` fields enter only `fluidThermoFingerprint`.

**Record ranges and transport tables** (as read; T in K):

| Record | Column T range | `fluid_domain` | Ideal-gas Cp | Liquid viscosity table | Vapour viscosity table |
|---|---|---|---|---|---|
| `fluid_nitrogen` | 63.151–900 | 63.151–900 K, 100 Pa–2 MPa | Fit 298.15–900; `below` segment 63.16–273.16 (NIST, 0.018 %) | Saturation line 63.151–126.192 (12.5 kPa–3.396 MPa), NIST | 63.151–900; 10 kPa isobar below 268 K, 100 kPa above |
| `tjl20_methane` | 298.15–900 | 293.15–900 K, 100 Pa–2 MPa | Fit 298.15–900 (NIST Shomate) | 95.28–181.03 at 0.1 MPa (DWSIM) | 293.15–900 at 0.1 MPa |
| `tjl19_ethane` | 298.15–900 | same | Fit 298.15–900 (DWSIM) | 152.66–290.05 at 0.1 MPa | 293.15–900 |
| `tjl19_propane`, `_isobutane`, `_n_butane`, `_isopentane`, `_n_pentane` | 298.15–900 | same | Fit 298.15–900 (DWSIM) | 184.9–351.3, 203.9–387.5, 212.6–403.9, 230.2–437.4, 234.9–446.2 | 293.15–900 |
| `crude_pc01`–`pc07` | 298.15–900 | same | Fit 298.15–900 | Dalia family 293.15–900 at 0.1 MPa, estimated | DWSIM 293.15–900, estimated |
| `crude_pc08`–`pc12` (estimated heavy residue) | 298.15–900 | same | Fit 298.15–900 | same | Reconstructed 293.15–900, estimated |
| Water (`water-iapws-shomate-r1`) | Triple point 273.16, max enthalpy T 900; critical 647.096 K / 22.064 MPa | Envelope pressure | Shomate vapour 273–900 | IAPWS SR6-08 253.15–383.15 at 0.1 MPa | IAPWS 2008 dilute gas 273.16–900 (`WaterRegion1`) |

Network liquid water is IF97 region 1 at the 2 MPa reference; region 1 ends at 623.15 K.

**Dissolved-solute viscosity.** The conditional dissolved-solute viscosity curves (`transport/dissolved_viscosity.json`) start at 293.15 K.

**Liquid volume calibration.** `transport/liquid_calibration.json` holds one point per light species, all at 2 MPa: N2 at 90 K, CH4 150 K, C2H6 240 K, C3H8 295 K, and 300 K for the C4 and C5 species.

### 1.2 Targets and exclusion bands used in the table

**Process anchors** (plan section 3; confirmation in section 5):

| Anchor | Value |
|---|---|
| Cryogenic floor | N2 63.151 K, CH4 90.694 K, C2H6 90.368 K (CoolProp triple points) |
| CO2 | Triple point 216.592 K; solid below it |
| Pressure ceiling | 10 MPa (provisional), set by the diesel hydrotreating example at about 9.1 MPa absolute |
| High temperature, light species | 1143 K (steam-cracking coil outlet); 1200 K provisional |
| VDU (crude cuts) | 1–13 kPa |
| Coking | 739–783 K (drum feed 739–761 K; heater outlet 755–783 K) at 0.20–0.52 MPa abs (section 5) |
| Hydrotreating | 589–713 K at 9.1 MPa (diesel 623–663 K at 6.1–9.1 MPa abs; gas-oil H2 partial pressure up to 10.4 MPa abs, section 5); H2 comes later (P7) |

**Declared critical exclusion band** (research-only; Tr 0.95–1.1 together with Pr 0.8–1.5):

| Fluid | Temperature | Pressure |
|---|---|---|
| N2 | 119.9–138.8 K | 2.72–5.09 MPa |
| CH4 | 181.0–209.6 K | 3.68–6.90 MPa |
| C2H6 | 290.1–335.9 K | 3.90–7.31 MPa |
| CO2 | 288.9–334.5 K | 5.90–11.07 MPa |
| C3H8 | 351.4–406.9 K | 3.40–6.38 MPa |

The 10 MPa ceiling lies inside CO2's band pressure window (Pr 1.36).

**Stage codes** used in the table:

| Code | Stage |
|---|---|
| P1 | Measurement study (oracle parity, alpha, direct liquid evaluation, compact cut fixture) |
| P3/G3 | Pilot engine |
| P4/G4 | CO2 deposition in N2 |
| P5/G5 | CO2 freezing out of liquid CH4 |
| P6/G6 | Network integration and pilot acceptance |
| P7/G7 | Staged rollout |
| VDU-WP0 | The VDU batch's first work package (`2026-08-31-v3-vdu`) |

"Achieved" is left blank: it is filled at G3/G6/G7.

### 1.3 Coverage table

**Fluid-network consumer** (package `tjl20_methane_nitrogen`, the only package the network is built on; crude fluid presets are projected onto it and must match its records)

| # | Family | Phase | Before | Target | Achieved | Establishing stage |
|---|---|---|---|---|---|---|
| N1 | Nitrogen | Vapour | 63.151–900 K, 100 Pa–2 MPa. Cp segment below 273.16 K. Vapour viscosity table 63.151–900 K at a 10 kPa / 100 kPa reference isobar | 63.151–1143 K (1200 K provisional), 100 Pa–10 MPa. Bounded ideal-gas records: CEA N2 200–1000–6000 K, plus the CoolProp alpha0 or the existing segment below 200 K | | P3/G3 (ideal gas above 900 K, 10 MPa vapour isotherms); P6/G6 |
| N2 | Nitrogen | Liquid | 63.151 K to Tsat(2 MPa) ≈ 115 K. Liquid at the 2 MPa reference plus a global k. Liquid viscosity along saturation to 126.192 K | 63.151 K to the band edge 119.9 K, compressed liquid to 10 MPa, evaluated at state pressure (F1) | | P1 item 4 (direct liquid), P3/G3, P6/G6 |
| N3 | Nitrogen | Supercritical | None (P ≤ 2 MPa < Pc 3.396 MPa) | T > 126.2 K up to 10 MPa, band 119.9–138.8 K × 2.72–5.09 MPa research-only | | P3/G3 (N2 critical-path fixtures), P6/G6 |
| N4 | Nitrogen | Solid | Unavailable: refused below 63.151 K | Unavailable (declared). N2 solids are outside the pilot (D2); the N2/Ar liquidus is research-only | | P7 or research |
| N5 | Methane | Vapour | 293.15–900 K, 100 Pa–2 MPa. Vapour viscosity from 293.15 K | 90.694–1143 K (1200 K provisional), to 10 MPa | | P3/G3, P6/G6 |
| N6 | Methane | Liquid | Unreachable: floor 293.15 K > Tc 190.6 K; the 95–181 K liquid viscosity table is unused. Present only as a dissolved species in a hydrocarbon liquid | 90.694 K to the band edge 181.0 K, to 10 MPa. It is the solvent of the CO2 freezing case | | P3/G3, P5/G5 (as solvent), P6/G6 |
| N7 | Methane | Supercritical | Gas-like only (P ≤ 2 MPa < Pc 4.6 MPa) | To 10 MPa (Pr 2.17), band 181.0–209.6 K × 3.68–6.90 MPa excluded | | P3/G3 |
| N8 | Methane | Solid | Unavailable | Unavailable (declared); the CH4/C2H6 liquidus is research-only (D2) | | Research only |
| N9 | Ethane | Vapour | 293.15–900 K, 100 Pa–2 MPa | 90.368–1143 K (1200 K provisional), to 10 MPa. CEA ethane starts at 300 K, so below 300 K a second bounded source is needed | | P3/G3 |
| N10 | Ethane | Liquid | Unreachable as a pure liquid: Psat(293.15 K) ≈ 3.8 MPa > 2 MPa. Dissolved only | 90.368 K to the band edge 290.1 K, to 10 MPa. Psat below about 150 K carries the declared Soave error (+10 % at 120 K, +29 % at 90 K) pending D3 | | P1 item 2 (alpha), P3/G3 |
| N11 | Ethane | Supercritical | None | To 10 MPa, band 290.1–335.9 K × 3.90–7.31 MPa excluded | | P3/G3 |
| N12 | Ethane | Solid | Unavailable | Unavailable (declared). The II→I transition at 89.81 K blocks a single-crystal model (D2) | | Research only |
| N13 | C3–C5 (propane, n-/i-butane, n-/i-pentane) | Vapour and liquid | 293.15–900 K, 100 Pa–2 MPa. Liquid viscosity tables start at about 185–235 K but the floor is 293.15 K. Ideal-gas fits deviate from CEA by −0.02 to +2.6 % at 300 K and by up to −5.8 % at 1200 K (section 2.4) | Unchanged in the pilot (not pilot species). After G6: steam-cracker feed and cold-section temperatures, to 10 MPa; CEA from 300 K | | P7/G7 |
| N14 | C3–C5 | Supercritical | None at ≤ 2 MPa | To 10 MPa; bands per species (for example propane 351–407 K × 3.4–6.4 MPa) | | P7/G7 |
| N15 | C3–C5 | Solid | Unavailable | Unavailable | | Not planned |
| N16 | Crude cuts PC01–PC12 | Vapour and liquid | 293.15–900 K, 100 Pa–2 MPa. Liquid viscosity from the Dalia family (estimated); PC08–PC12 are estimated heavy residue. Cp degree-five fits 298.15–900 K. The network already admits VDU pressures (100 Pa), but the cut K-values carry the PR78 vacuum bias (section 6) | Keep the 293.15 K floor (no wax). VDU 1–13 kPa with a declared K bias. Coking 739–783 K at 0.20–0.52 MPa. Hydrotreating 589–713 K at 9.1 MPa (10 MPa). Ceiling 900 K stays for cuts unless a Laštovka-Shaw refit is adopted | | P1 item 5 and P3/G3 (compact existing-cut fixture: probes only, no qualification claim); P7/G7 (joint CDU/VDU/coking/hydrotreating qualification) |
| N17 | Crude cuts | Supercritical | Not reachable at ≤ 2 MPa: Pc 0.57–3.6 MPa, and PC01/PC02 have Tc 509/570 K | At 9.1 MPa every cut is above Pc, and PC01–PC02 are above Tc at 589–713 K. This is fluid-regime handling through the stability test, not a new phase | | P7/G7 (with H2) |
| N18 | Crude cuts | Solid | Unavailable (no wax, gel or coke) | Unavailable; coke is chemistry, not freezing | | Not planned |
| N19 | Water | Vapour | 273.16–900 K, 100 Pa–2 MPa. Shomate enthalpy, IAPWS dilute-gas viscosity to 900 K | Steam to 1143 K (1200 K provisional; IF97 region 2 to 1073.15 K, region 5 above), to 10 MPa. Low partial pressures at VDU conditions (1–5 kPa). Check the current vapour path against region 2 (F13) | | P3/G3 (steam-cracking reference probe); P7/G7 |
| N20 | Water | Liquid | 273.16 K to Tsat(2 MPa) = 485.5 K. IF97 region 1 at 2 MPa plus global k. Liquid viscosity only 253.15–383.15 K at 0.1 MPa | 273.16 K to region 1's 623.15 K at state pressure, to 10 MPa. The viscosity range must be extended or declared | | P3/G3 (only if water rides on the direct-liquid change), else P7 |
| N21 | Water | Supercritical | None (Pc 22.064 MPa) | None at ≤ 10 MPa (Pr 0.45); above 647 K water is steam | | n/a |
| N22 | Water | Solid (ice Ih) | Unavailable: refused below 273.16 K | Unavailable in the pilot. Ice Ih (IAPWS R10-06) and sublimation (R14-08) are available later | | Later research |
| N23 | CO2 (planned new record) | Vapour | Absent | From the CO2 frost point in N2 at the P4 holdout compositions up to 1143 K, to 10 MPa. The frost point is 194.7 K for pure CO2 at 1 atm; about 150 K at 1 mol % and 1 atm (Clausius-Clapeyron estimate with 25.2 kJ/mol; the P4 datasets set the actual bound). CEA CO2 covers 200–6000 K; below 200 K the CoolProp alpha0 applies | | P3/G3 (fluid), P4/G4 (onset) |
| N24 | CO2 | Liquid | Absent | 216.592 K to the band edge 288.9 K, to 10 MPa. As a CO2-lean liquid in CH4 far below its own triple point (mixture-stabilized) | | P3/G3, P5/G5 |
| N25 | CO2 | Supercritical | Absent | T > 304.1 K to 10 MPa. 10 MPa lies inside the band window, so 288.9–334.5 K at 5.90–10 MPa is research-only | | P3/G3 |
| N26 | CO2 | Solid | Absent | Pure-solid Gibbs (Jäger and Span 2012, 80–300 K), anchored at the triple point: deposition in N2 (P4) and freezing out of liquid CH4 (P5) | | P4/G4, P5/G5, P6/G6 |

**V3 column consumer** (packages `tjl19_dwsim`, and `tjl20_methane` with `bonga_tjl20`, `cold_lake_blend_tjl20`, `dalia_tjl20`, `upper_zakum_tjl20`, `wti_light_export_tjl20`, which carry identical records; the network package has no column preset)

| # | Package(s) | Family | Phase | Before | Target | Achieved | Establishing stage |
|---|---|---|---|---|---|---|---|
| C1 | All six | Methane (tjl20 group only), ethane, C3–C5 | Vapour and liquid | 298.15–900 K, 50 kPa–2 MPa. The kernel refuses outside; the hybrid lane runs below 100 kPa. Qualified CDU cases at 250 kPa top, 638.15 K feed, 332.15 K condenser, 533.15 K steam | Unchanged in the pilot (the pilot's cryogenic states are not column states). VDU pressure floor on the order of 1 kPa per the VDU batch | | VDU-WP0; P6/G6 re-runs the CDU cases unchanged |
| C2 | All six | PC01–PC12 | Vapour and liquid | 298.15–900 K, 50 kPa–2 MPa. VDU Case A (13.1 kPa flash, about 10.6 kPa top) is refused. PR78 runs 4–13 K colder than Maxwell-Bonnell at 1–13 kPa | VDU 1–13 kPa (Case A 13.1 kPa; deep vacuum 4.0–5.3 kPa) under the D5 policy. Coking fractionator and hydrotreater separators are later process families (P7) | | P1/P3 compact existing-cut fixture (pilot evidence); VDU-WP0 (column floor, D5); P7/G7 |
| C3 | All six | Water (free-water model) | Vapour and liquid | IAPWS saturation from the triple point (611 Pa), enthalpy 273–900 K. Steam at VDU partial pressures (1–5 kPa) is covered | Unchanged | | VDU-WP0 re-check |
| C4 | All six | Any | Supercritical, solid | Not representable: the column is a VLE-only MESH solver | Explicit unsupported-phase result; crystals are never fed to the VLE column (plan P6) | | P6/G6 |
| C5 | All six | N2, CO2 | Any | Not in any column package | None in the pilot | | n/a |

### 1.4 Headline coverage gaps

1. **Pressure.** Nothing above 2 MPa, anywhere.
   - In the network, 2 MPa is a model limit: the liquid reference state disappears where Psat > 2 MPa (F1).
   - In the column it is a package declaration.
   - Every supercritical and dense-fluid target row (N3, N7, N11, N14, N17, N25) starts from "none".
2. **Low temperature.** Only nitrogen goes below 293.15 K. Pure liquid methane and pure liquid ethane are unreachable, and ethane's low-Tr Psat error (D3) will be exposed as soon as N10 opens.
3. **High temperature.** Every record stops at 900 K. The bundled fits diverge beyond it (methane −8.9 % against CEA at 1143 K). No ideal-gas record type with a declared range exists. The water vapour path is unchecked above 900 K.
4. **Vacuum, column.** The column refuses below 50 kPa, so no VDU column case can run. The network admits 100 Pa but inherits the same cut K bias.
5. **Solids.** None, apart from the inert demo particle. CO2 is absent altogether.
6. **Transport.**
   - Liquid viscosity tables sit at a 0.1 MPa isobar or along saturation, and have no pressure dependence.
   - No thermal conductivity exists anywhere.
   - Water liquid viscosity ends at 383.15 K.
   - Every cut's viscosity is an estimated surrogate.
7. **Ideal-gas references below 200–300 K and between sources** (section 2.4). CEA does not reach the cryogenic floors. Methane's references disagree by about 2 % at 900 K.

## 2. Baseline case manifest

### 2.1 Gate tests and fixtures that pin current behaviour

Task mapping from `build.gradle`:

| Task | What it runs |
|---|---|
| `test` | Everything except `**/fluid/benchmark/**` |
| `fluidScienceTest` | `science.fluid.*` |
| `fluidRuntimeTest` | `runtime.fluid.*` and `runtime.Fluid*Test` |
| `fluidSolverRegression` | Only `FluidSolverRegressionTest` |
| `v3HollandBenchmark` | Only `HollandExample32BenchmarkTest` |
| `verifyMaterialIndex` | Index completeness; no numbers |

In the table, "pins" gives the pinned values and tolerances as read from the source.

| Class (package `com.wormzjl.createcheme.`) | Task | Package | Pins |
|---|---|---|---|
| `science.fluid.thermo.FluidNitrogenCryogenicTest` | test, fluidScienceTest | network | Psat(77.355 K) within 1.5 % of NIST 101 325.06 Pa (measured +1.23 %). Saturated liquid ρ within 0.5 % of 806.08 kg/m³ (+0.34 %). Vapour Cp at 1 atm, 100 and 200 K, within 1 % (the 100 K margin is small: −0.99 %). Ideal-gas Cp at 7 points 63.16–273.16 K within 5e-4. H/Cp continuity at 273.16 K within 1e-9, slope within 1e-7. Domain edges: 63.2 K accepted, 63.1 K refused. Liquid viscosity at 77.355 K within 0.5 % |
| `fluid.benchmark.FluidSolverRegressionTest` + `SolverRegressionHarness` | fluidSolverRegression | network | Fixtures `quiet-11312` and `quiet-11324` need `build/probe/core.dat` and `cold-11312` needs `core-fallback.dat`; these snapshots are not committed and the fixtures are skipped when absent. `chain-100` is synthetic: 100 reservoirs at 350 K and 150 kPa, TJL methane assay + 0.1 N2 + 0.2 water. References in `src/test/resources/fluid/regression/*.json`. The three island references were last committed in `15403b9` (2026-09-16, the stale pre-basis captures). chain-100 was re-captured in `fca6a15` and `3c84036` (2026-09-22/23) and is the only live numeric pin. Exact mode is bitwise. Declared mode (default) is `Relative(1e-6, 1e-4 K, 1e-6, 1e-3)` |
| `science.column.v3.V3LiteraturePresetTest` | test | `tjl19_dwsim` | Ledezma-Martínez 40-stage CDU: 250 kPa top, three pumparounds (−12.84/−17.89/−11.20 MW), steam 333.3 mol/s. Success with scaled residual < 1e-10. `WATER_DEW_POINT` 1.162 ± 0.02 (one warning). Free water 333.3 mol/s ± 1e-9. Distillate 697.7238 ± 1.0 kmol/h |
| `science.column.v3.HollandExample32BenchmarkTest` | test, v3HollandBenchmark | `test:holland_b12` (its own K/enthalpy model, not PR78) | Oracle residual ≤ 1e-10. V3 against the oracle: ΔT ≤ 1e-6 K, flow ≤ 1e-7. Against the published tables: ΔT ≤ 0.3 K, Q_C ≤ 1 %. Unaffected by PR78 or catalog changes |
| `science.column.v3.V3OperatingDomainValidatorTest` | test | cdu17 (test only) | Lane and refusal logic at 50 kPa, 100 kPa, 1 kPa (`VDU_REQUIRED`) and 2.028 MPa. The only VDU-related test; no VDU column case exists |
| `science.column.v3.thermo.RegroupedCrudeTest` | test | all 8 | Cut edges. TJL mass and volume within 1e-9 / 1e-12. Cp/H moments against the frozen package. Dalia viscosity mean ≤ 10 %, max ≤ 20 %. **Solved-column draw volumes of five presets within 1e-5 relative** (`column-draw-volume-targets.json`). BONGA and COLD_LAKE converge classically within 45 s |
| `science.column.v3.thermo.V3CatalogParityTest` | test | tjl19, tjl20, cdu17 | **Exact equality** of the retained components (light ends and PC01–PC07: MW, Tb, ρ, Tc, Pc, ω, Cp A–F, kij, Cp/H at 4 T) against the frozen Java tables |
| `science.column.v3.thermo.V3FeedFlashEquivalenceTest` | test | cdu17, `tjl19_dwsim` | **Bitwise** flash equality against the frozen db46e88 reference on a 5 T × 3 P grid (298.15–900 K, 50–267 kPa) |
| `science.column.v3.thermo.V3Tjl20MethanePropertyPackageTest` | test | `tjl20_methane` | Methane Tc/Pc/ω. Cp against NIST Shomate within 0.05 J/mol/K and H within 0.5 J/mol over 298.15–900 K. Flash at 638.15 K / 250 kPa |
| `science.column.v3.V3BundledRegroupedModelTest` | test | bonga, upper_zakum | The neural model must bind (physics fingerprint equal to the sidecar's) and reproduce fixture predictions |
| `science.column.v3.V3TrayHydraulicsColumnTest` | test | `tjl19_dwsim` | Literature preset with diameter: mean tray ΔP 565 ± 60 Pa, total 22 000 ± 2 500 Pa, flood 0.69 ± 0.08 |
| `science.column.v3.thermo.V3PengRobinsonRootPrecisionTest` | test | cdu17 | Vapour fraction 0.7855276025697608 ± 1e-9 at 638.15 K / 137.25 kPa |
| `science.fluid.thermo.FluidPropertyCoverageTest` | test, fluidScienceTest | network + 6 crude/TJL presets | Grid 293.15–375 K × 50 kPa–2 MPa. Closure 1e-8, volume sum 1e-12. Water vapour ideal-gas volume within 2 %, viscosity within 5 % |
| `science.fluid.thermo.LiquidCompressionQualificationTest` | test, fluidScienceTest | network | NIST n-butane, n-pentane and n-decane 300 K isotherms, density within 5 % |
| `science.fluid.thermo.GlobalLiquidResponseTest`, `HydrocarbonModelTest` | test, fluidScienceTest | `tjl20_methane` | Compressibility 1e-9; μ–V identity 1e-10. Both pin the formulation F1 removes |
| `science.fluid.thermo.TranslatedPengRobinsonTest` | test, fluidScienceTest | synthetic | Analytic derivatives against finite differences within 1e-5 relative |
| `science.fluid.thermo.WaterRegion1Test` | test, fluidScienceTest | — | IF97 region 1 checkpoints: v within 5e-12, h and u within 1e-3, cp within 1e-4 |
| `science.fluid.thermo.AmbientCrudePresetTest`, `ThermoDomainViolationTest`, `runtime.fluid.FluidDeviceSpecDomainTest` | test, fluid tasks | network | Exact domain messages and edges: 63.151 K, 293.15 K, 273.16 K, 100 Pa, 900 K; N2 at 200 K accepted |
| `runtime.FluidThermoDomainHoldTest` | test, fluidRuntimeTest | network | The HELD (thermo domain) path relies on about 0.24 K of suction cooling of N2, which is EOS-sensitive |
| `science.material.MaterialCatalogTest` | test | `tjl20_methane` | A numeric edit changes the fingerprint without a revision bump; a `crude_pc07` MW edit makes the neural prediction empty |
| GameTests (`src/fluidGameTest`) | runFluid/ColumnGameTestServer | — | Every column preset except HOLLAND reaches SUCCESS within 180 s. Module balance 1e-9 × max(1, x), energy 1e-6 × max(1, \|E\|). N2 inventory within 1e-8 |

**Fixtures no test reads:**
- `src/test/resources/v3-benchmarks/v3-cold-core-v1.json`
- `materials/legacy-baseline.json`
- the `fluid/reference/steam-*.tsv` and `nitrogen-*.tsv` files
- the untracked `src/test/resources/science/thermo/coolprop/*.json`, which are byte-identical to the fetched 8.0.0 files

### 2.2 Neural and fingerprint pins

The bundled registry model is `regrouped-seed-17041-epoch-160`:

| Field | Value |
|---|---|
| `pipelineSha256` | `609be438951126053b6c7b37e88016c462fcbe76014a640c3558aab472a8e86a` |
| Sidecar `packageId` | `createcheme:tjl20_methane` |
| `physicsFingerprint` | `b1b9f1eb4d77f7868982c4eec28fabd5b4b9fd36b91ca8a5869b7fbcbea6ceb8` |
| `propertyRevision` | `crude-regrouped-r1:dc4be6bf…` |

**What the fingerprint covers.** `physicsFingerprint` (`MaterialCatalog.java` lines 188–198) hashes the ordered 19-component axis's property records without id, the kij sub-matrix, NRTL pairs, the water record without revision, and the package's column T/P limits.

**Which packages it covers.** The same axis with identical records exists in `tjl20_methane`, the five crude packages and the network package. The model therefore serves all of them where their axis projects.

**What a change does.**
- Any numeric change to a cut, light-end, methane or water record, to the kij matrix, or to the package column T/P limits disables the model. `LNN_FIRST` then falls back to classical initialization.
- `V3BundledRegroupedModelTest` fails.
- `fluid_domain` edits change only `fluidThermoFingerprint`, which means a fresh world and nothing else.

### 2.3 Review probe states: the P0 numeric baseline

The P0 numeric baseline is the plan review's section 3 tables, produced by `research/2026-09-24-coolprop-low-temperature/pr78-vs-coolprop/` in the main checkout: the mod's PR78 formulation for a pure component against CoolProp 8.0.0 HEOS. Files as of 2026-09-24:

| File | Bytes | sha256 |
|---|---|---|
| `probe.py` | 9137 | `21993f3c0ddd22307310c3781d8f28b5cce32a4d65e8d76f01337926d99a8ca3` |
| `probe-output.txt` | 9604 | `d57eda42c8e6a42cf69563842ff08f1133a8f1957943b4ac0ab8807905d81fb4` |
| `cp_extrapolation.js` | 1400 | `d2a5bd2f4e512f3415ccb67324ab7aa9176b1cba8bebe1a8534317c8cc8eff8d` |
| `cp_ethane.js` | 650 | `4bb1d8482cd86c2eb9743c0cc0bb3892c68af5dbbe89e86aef518fdfd1b31a9a` |
| `cp-fit-extrapolation-output.txt` | 1428 | `bc63fec09ccd74ab8a97c0704bb208de26303e3b9f0a857c23f347b8dc899f6f` |
| `README.md` | 3255 | `cf7f0fc17ed89eab6366a46025c1203ee5c942dd146f7ab5add2e006a5db1320` |

**States frozen as the pilot baseline (review section 3):**
- **Saturation:** N2 at 63.15, 77.36, 100, 110 and 120 K. CH4 at 90.7, 111.67, 150, 170 and 185 K. C2H6 at 90.4, 120, 184.55, 250 and 280 K. CO2 at 216.6, 250, 270 and 290 K.
- **Dense and supercritical:** 19 states (N2 six, CO2 six, CH4 three, C2H6 three, H2 three), from 77 K / 10 MPa to 700 K / 10 MPa.
- **Ideal-gas fits against JANAF / Gurvich:** 1073, 1143 and 1200 K.

Any state added later is run through the same `probe.py` with CoolProp 8.0.0 in a throw-away `uv venv`, and appended with the reason.

### 2.4 Additional baseline measured in P0

**Bundled ideal-gas Cp fits against the CEA polynomials.** Percent deviation, fit/CEA − 1. CEA is evaluated inside its intervals; for the 300 K species, 300 K is the interval edge.

| Record | 300 K | 500 K | 700 K | 900 K | 1000 K | 1073 K | 1143 K | 1200 K |
|---|---|---|---|---|---|---|---|---|
| `fluid_nitrogen` | 0.00 | −0.02 | 0.02 | 0.00 | 0.24 | 0.96 | 2.58 | 4.99 |
| `tjl20_methane` | −0.24 | −0.50 | −1.29 | −2.16 | −3.26 | −5.20 | −8.85 | −13.81 |
| `tjl19_ethane` | 0.61 | −0.09 | −0.26 | −0.07 | −0.18 | −0.71 | −1.91 | −3.69 |
| `tjl19_propane` | 1.74 | −0.14 | −0.58 | −0.19 | −0.07 | −0.24 | −0.82 | −1.72 |
| `tjl19_n_butane` | 2.62 | −0.65 | −1.14 | −0.28 | 0.16 | 0.36 | 0.39 | 0.26 |
| `tjl19_isobutane` | 1.95 | −0.59 | −0.96 | −0.23 | 0.09 | 0.15 | −0.03 | −0.40 |
| `tjl19_n_pentane` | 0.17 | −0.22 | −0.10 | 0.07 | −0.34 | −1.24 | −3.10 | −5.80 |
| `tjl19_isopentane` | −0.02 | −0.08 | −0.23 | 0.00 | −0.12 | −0.49 | −1.40 | −2.77 |

**Reading the table:**

- **Nitrogen** agrees with CEA inside 298–900 K, as the review found against JANAF.
- **Methane: three references disagree.** Methane follows the NIST WebBook Shomate (Chase 1998) to within 0.03 % over 298–900 K, which is what `V3Tjl20MethanePropertyPackageTest` pins. But CEA (Gurvich 1991) is 0.1 % higher at 298 K, 1.3 % at 600 K, 2.2 % at 900 K and 2.6 % at 1000 K. CoolProp's Setzmann-Wagner alpha0 lies in between (52.49 J/mol/K at 600 K, 68.38 at 900 K, against CEA 52.69 / 69.07 and Shomate 52.23 / 67.60).
  - The review's "within 0.12 % over 298 to 900 K" holds only against the Shomate.
  - The choice of spine therefore moves methane's caloric properties inside the qualified column range by up to about 2 %. The plan treats that as a separately documented scientific correction.
  - P1 should settle it with an independent holdout (a modern partition-function evaluation) before the spine is frozen.
- **C3–C5 (DWSIM light-end fits)** are 1.7–2.6 % high at 300 K for propane and the butanes, a low-temperature edge effect of the degree-five fits.

**Formation enthalpy at 298.15 K: CEA `thermo.inp` against ATcT 1.220** (CoolProp master `9b35f538`). Values in J/mol.

| Species | CEA | ATcT ± u | CEA − ATcT | \|Δ\|/u |
|---|---|---|---|---|
| CO2 | −393 510 | −393 477 ± 15 | −33 | 2.2 |
| CH4 | −74 600 | −74 513 ± 43 | −87 | 2.0 |
| C2H6 | −83 851.5 | −84 020 ± 120 | +168 | 1.4 |
| C2H4 | 52 500 | 52 390 ± 110 | +110 | 1.0 |
| C3H6 | 20 000 | 20 060 ± 180 | −60 | 0.3 |
| C3H8 | −104 680 | −105 000 ± 150 | +320 | 2.1 |
| n-C4H10 | −125 790 | −125 550 ± 180 | −240 | 1.3 |
| i-C4H10 | −134 990 | −134 480 ± 270 | −510 | 1.9 |
| n-C5H12 | −146 760 | −146 050 ± 290 | −710 | 2.4 |
| i-C5H12 | −153 700 | −152 960 ± 390 | −740 | 1.9 |
| H2O | −241 826 | −241 808 ± 22 | −18 | 0.8 |
| NH3 | −45 940 | −45 554 ± 29 | −386 | 13.3 |
| H2S | −20 600 | −20 300 ± 180 | −300 | 1.7 |
| CO | −110 535.2 | −110 519 ± 25 | −16 | 0.6 |

N2, O2, Ar and H2 are zero in both.

The review's statement that the two sets are consistent within the stated uncertainties is not correct. The spine needs one declared source for ΔfH° per species. The plan already chose ATcT, with S°298 from CEA. The ATcT values require the CoolProp master revision, or ATcT itself, not 8.0.0.

**CEA polynomial continuity.** At the 1000 K interval joint, Cp is continuous to within 5e-9 relative for N2, CH4, C2H6, CO2, H2, H2O, C2H4 and C3H8, and H and S to within 0.01 J/mol and 1e-4 J/mol/K. A joint-continuity gate of 1e-8 relative is therefore attainable for a `nasa9` record.

## 3. Acceptance targets, proposed for G0

### 3.1 Numerical conservation and consistency: existing gates, unchanged

These are contracts of the solvers, not model accuracy. The plan (section 7, P8) keeps them unless a separately documented scientific change requires otherwise.

| Dimension | Tolerance (source) |
|---|---|
| Fluid network component balance per step | 1e-10 + 1e-8 · scale (`PassiveStepSolver.java` line 1262; `ConservationAssertions` line 20) |
| Fluid network solid balance | 1e-10 + 1e-8 · scale (`PassiveStepSolver` line 1260) |
| Fluid network energy balance | 1e-4 J + 1e-6 · scale (`PassiveStepSolver` line 1263; `ConservationAssertions` line 31) |
| Fluid Newton / reconstruction | 1e-9 (1e-10 small headspace, 1e-6 approximate); reconstruction gate 1e-8 FULL (lines 269, 369) |
| Rest/steady certificates | ε_s 1e-7, inventory budget 1e-6 (`CertificatePolicy` line 25) |
| Trace cutoff | 1e-6 (`FluidThermodynamics` line 30) |
| Fluid solver regression, unchanged physics | Bitwise (exact mode) |
| Fluid solver regression, intentional change | Declared `Relative(1e-6, 1e-4 K, 1e-6, 1e-3)`, re-recorded only after independent scientific validation (plan P8) |
| GameTest module balances | Components 1e-9 · max(1, x); energy 1e-6 · max(1, \|E\|) |
| Column convergence | Scaled residual 1e-8 (`V3ColumnCalculator` line 50); equilibrium 1e-8; water balance 1e-8; `GLOBAL_ENERGY_BALANCE` max(1 W, 1e-6 · largest, nodeCount · τ · energyScale) (`V3AcceptanceAuditor`) |
| Oracle parity (D4) | 1e-10 relative on ρ, h, s, cp and ln f against CoolProp 8.0.0 at the probe states |
| Derivatives | Analytic against central finite differences within 1e-5 relative (`TranslatedPengRobinsonTest` convention); dH/dT = Cp within 1e-6 |
| Record joints | H and Cp continuity at a segment joint within 1e-9 relative (`FluidNitrogenCryogenicTest` convention). For `nasa9` interval joints, 1e-8 relative (measured 5e-9, section 2.4) |
| Triple-point consistency (solids, P4/P5) | Solid, liquid and vapour chemical potentials equal at the triple point within 1e-9 · RT (by construction of the anchor); proposed |

### 3.2 Model accuracy per property, proposed for G0

**Grade and basis.** The grade is "estimated with declared error" (D1).

- **References:**
  - the Java Helmholtz oracle (CoolProp 8.0.0) for N2, CH4, C2H6, CO2 and H2 inside each EOS's published range;
  - CEA polynomials (with JANAF or Gurvich as holdouts) for the ideal gas;
  - the independent experimental holdouts of section 3.3.
- **Exclusions:** the critical band of section 1.2 is research-only and is not scored.

**Absolute floors** are given where a property tends to zero, so that a relative target does not become meaningless.

**Measured basis.**
- The measured basis is the review's section 3 (current formulation, translated PR evaluated directly), not the network path the plan removes.
- The EOS uncertainties are those stated by the source papers (section 3.4).
- The targets are 5 to 100 times looser than the reference uncertainty, as intended for a cubic.

| Property | Region | Target (proposed for G0) | Floor | Measured basis now |
|---|---|---|---|---|
| Psat (pure) | Tr 0.6–0.95 | \|ΔP/P\| ≤ 1.5 % | If Psat < 1 kPa: \|ΔTsat\| ≤ 0.3 K instead | Max +1.2 % (N2 at NBP); others ≤ 0.8 % |
| Psat (pure) | Tr 0.45–0.6 | ≤ 5 % | same | N2 +4.5 % at the triple point (Tr 0.50); CH4 +1.4 % (Tr 0.48) |
| Psat (pure) | Tr < 0.45 (ethane below about 137 K) | Declared error, not gated, until D3 is revisited: +10 % at 120 K, +29 % at 90 K. With the Twu alpha, the target is the Tr 0.45–0.6 row | \|ΔTsat\| reported | C2H6 +28.7 % / +10.2 % |
| Saturated liquid density | Tr ≤ 0.85 | ≤ 3 % (≤ 2 % after the Tr 0.8 re-anchoring of P3) | — | Max \|−2.7 %\| (C2H6 at the triple point) |
| Saturated liquid density | 0.85 < Tr ≤ 0.95 | ≤ 10 % declared | — | N2 −3.6 % at Tr 0.87; CH4 −4.2 % and CO2 −3.0 % at 0.89; C2H6 −6.1 % at 0.92; N2 and CO2 −8.9 % at 0.95 |
| Compressed / liquid-like density | Tr ≤ 0.9, P to 10 MPa, at state pressure | ≤ 3 % | — | N2 −0.2 / +0.3 (77 and 100 K), CH4 +1.2 (150 K), all at 10 MPa |
| Liquid-like density near Tc | 0.9 < Tr < 0.95, or Pr > 1.5 at Tr ≈ 1 | ≤ 5 % | — | CO2 −1.9 % at 280 K / 10 MPa (Tr 0.92); C2H6 −3.4 % at 300 K / 10 MPa (Tr 0.98, Pr 2.05) |
| Vapour-like density | Z ≥ 0.8 | ≤ 1 % | Z floor 1e-3 absolute | H2 at 623–700 K: 0.5–0.6 %; N2 300 K: 0.0 % |
| Supercritical density outside the band | Tr > 1.1 or Pr > 1.5 | ≤ 7 % | — | N2 130 K / 6 MPa −6.5 % (Pr 1.77); C2H6 320 K / 10 MPa −6.3 % (Pr 2.05); N2 150 K / 10 MPa −4.8 %; the other states outside the band ≤ 1.0 %. The review's "about 5 %" is exceeded just outside the band at two states; the in-band CO2 310 K / 10 MPa (−10.6 %) and CH4 200 K / 6 MPa (−5.4 %) are not scored |
| Ideal-gas Cp, h, s | Inside each record's declared range, 63 K to 1200 K | Cp ≤ 0.2 % against the chosen reference; h(T) − h(298.15) ≤ 0.1 % of the change or 20 J/mol; S°298 within the source uncertainty | 20 J/mol | Current fits within 0.12 % against JANAF 298–900 K. Diverge above 900 K (F2). Methane's reference disagreement is 2 % (section 2.4) |
| Real-gas vapour cp | Z ≥ 0.9 | ≤ 2 % | 0.05 R | N2 at 100 K / 1 atm −0.99 % (gated at 1 %) |
| Liquid cp | Tr ≤ 0.8 | ≤ 10 % | — | CO2 −8.9 %, C2H6 −8.8 %, N2 −8.2 % at the triple points; CH4 +6.6 % at Tr 0.79 |
| Liquid cp | 0.8 < Tr ≤ 0.9 | ≤ 20 % declared | — | C2H6 +6.0 % (0.82), CO2 +11.0 % (0.89), N2 +12.3 % (0.87), CH4 +16.4 % (0.89) |
| Liquid cp | Tr > 0.9 | Unavailable / estimated, flagged | — | C2H6 +17.7 % (0.92); N2, CO2, CH4 +28 % to +35 % (0.95–0.97) |
| Supercritical cp outside the band | Tr > 1.1 or Pr > 1.5 | ≤ 10 % | — | N2 −3.8 / +7.1; CO2 −3.6 / 0.0; CH4 −4.6; C2H6 +4.6 / −2.0 |
| hvap | Tr ≤ 0.9 | ≤ 3.5 % | 0.2 kJ/mol | Max −3.1 % (C2H6 at the triple point); CH4 −2.2 % at Tr 0.89 |
| Fugacity / K (pure) | As Psat | Implied by the Psat rows (ln f equality) | — | — |
| K-values, pilot binaries (N2/CH4, N2/C2H6, CH4/C2H6, CO2/N2, CO2/CH4) | Outside the mixture critical region | Bubble-point pressure AAD ≤ 10 %, per-point ≤ 20 %; vapour composition \|Δy\| ≤ 0.02; ln K ≤ 0.15 for species with x or y > 1e-3 | Species below the 1e-6 trace cutoff not scored | Unmeasured; E-PPR78's own deviations are in section 3.4 |
| K-values, crude cuts at 1–13 kPa | VDU | Declared bias, not gated (D5): PR78/MB Psat ratio 1.13–1.24 at 13 kPa for PC06–PC12, ΔT −4.2 to −9.8 K | \|ΔTsat\| reported | Section 6 |
| Viscosity, vapour | Dilute gas, inside table range | ≤ 3 % against the reference correlation; table interpolation ≤ 0.1 % | — | N2 NIST tables; others DWSIM (not independently qualified) |
| Viscosity, liquid | Tr ≤ 0.9 along saturation or at the table isobar | ≤ 5 % against the reference; ≤ 20 % declared for estimated cuts (Dalia family) | — | N2 0.1 % (NIST); Dalia mean ≤ 10 %, max ≤ 20 % |
| Thermal conductivity | — | Unavailable (no consumer); stays unavailable until a consumer needs it | — | — |
| Solid CO2 (P4/P5) | 150–216.6 K | Sublimation pressure ≤ 5 % against the Fray-Schmitt / Giauque holdouts (section 3.4). Frost-point T in N2 within 1.5 K of Sonntag/Smith. CO2 liquidus in CH4 within 2 K of Davis / Zhang / Le and Trebble. Sublimation enthalpy within 2 % of 25.2 kJ/mol at 195 K | \|ΔT\| as the primary metric below 1 Pa | None yet |

**Runtime and allocation budgets** are a separate dimension, not set here. P1 item 3 measures the stability-test cost first. The G0 rule proposed is:
- pilot-network cold and warm island costs within the owner's existing benchmark practice (60 s warm-up plus 60 s measurement);
- no new per-tick work (AGENTS.md).

### 3.3 Independent holdouts (not used in fitting)

| Area | Holdouts |
|---|---|
| Pure fluids | CoolProp oracle states at 1 kPa and 10 MPa isobars; NIST WebBook values already in tests (N2 77.355 K; liquid-calibration points; n-butane, n-pentane and n-decane 300 K) |
| Ideal gas | JANAF / Gurvich tables for CH4, N2 and C2H6 at 1000–1200 K; a modern partition-function source for methane (open item, section 2.4) |
| Mixtures | GERG-2008 (`GERG2008.cpp` test values, CoolProp pairs) for the N2/CH4/C2H6/CO2 binaries as an oracle; experimental VLE only as holdout sets once acquired |
| Solids | Plan sections P4/P5 (Sonntag and Van Wylen 1962; Smith et al. 1963/1964; Davis et al. 1962; Zhang et al. 2011; Le and Trebble 2007; Fray and Schmitt 2009; Giauque and Egan 1937) |
| Cuts | Maxwell-Bonnell and Lee-Kesler (API TDB) at 1–13 kPa (bias declared); CRC n-C16 / n-C20 / n-C24 vapour pressures (the VDU document's validation) |
| Hydrogen (P7) | Lin, Sebastian, Chao 1980; the Mohammadi 2021 source list |

### 3.4 Source uncertainties behind the targets

**How this was gathered.** An opus subagent ran the web research on 2026-09-24, reading abstracts and open copies. "k=2" is coverage factor 2. The status column says where the figure comes from, or that it could not be found. Publisher full texts (pubs.aip.org, ScienceDirect, NTRS) returned 403.

| Reference | Stated uncertainty (region) | Validity | Status |
|---|---|---|---|
| Span et al. 2000, N2 EOS | Density 0.02 % from the triple point to 523 K below 12 MPa (0.01 % at 270–350 K); Psat, ρ′ and ρ″ 0.02 %; cp 0.3 % (gas-like) to 0.8 % (liquid) below 30 MPa; speed of sound 0.005–0.1 % gas, 0.5–1.5 % liquid (95 %) | 63.151–1000 K, 2200 MPa | Paper, section 6 (NIST open copy) |
| Setzmann and Wagner 1991, CH4 EOS | Density ±0.03 % below 12 MPa and 350 K, up to ±0.15 % higher; speed of sound ±0.03–0.3 %; heat capacities about ±1 % | Melting line to 625 K, 1000 MPa | Abstract; Psat and ρ′ not found |
| Bücker and Wagner 2006, C2H6 EOS | Density 0.02–0.03 % (k=2) from the melting line to 520 K up to 30 MPa | Melting line to 675 K, 900 MPa | Abstract; Psat and cp not found |
| Span and Wagner 1996, CO2 EOS | Density ±0.03–0.05 %; speed of sound ±0.03–1 %; cp ±0.15–1.5 % (to 30 MPa and 523 K) | Triple point to 1100 K, 800 MPa | Abstract; Psat not found |
| Leachman et al. 2009, H2 EOS | Density 0.04 % (250–450 K, to 300 MPa); Psat and ρ′ 0.1–0.2 %; heat capacities within 1 %; speed of sound 0.5 % below 100 MPa (k=2) | To 1000 K, 2000 MPa | Abstract |
| Lemmon and Jacobsen 2004, N2/O2/Ar/air transport | Viscosity and conductivity about 2 % for N2 and Ar, 5 % for O2 and air, higher near the critical point | Liquid and vapour states | NIST abstract. The dilute-gas 0.5 % figure is unverified |
| Laesecke and Muzny 2017, CO2 viscosity | Dilute gas 0.2 % (200–700 K, < 0.5 MPa), 1 % to 2000 K; vapour 1 %; liquid 4 %; supercritical 3 %; critical region 2 % | Gas 100–2000 K | Paper body (OSTI copy) |
| Huber et al. 2016, CO2 conductivity | 1 % below 0.1 MPa (300–700 K), rising to 5 % at high pressure (95 %) | Triple point to 1100 K, 200 MPa | Abstract |
| Friend et al. 1989 (CH4) and 1991 (C2H6), transport | Ranges only: CH4 viscosity 91–400 K; C2H6 viscosity 90–500 K | as stated | Numeric uncertainty not found |
| Jäger and Span 2012, solid CO2 | Qualitative only: data reproduced "within experimental uncertainty". Figures attributed to it by search engines could not be verified; do not use them | 80–300 K | Abstract; numbers not found |
| Trusler 2011, solid CO2 | Molar volume 0.02 % on the sublimation curve, 1.5 % compressed; cp 5 % (2 K) to 0.5 % (195 K) | 0–800 K, 12 GPa | Abstract |
| Fray and Schmitt 2009, sublimation | No numeric accuracy stated | 27 species | Not found |
| Kunz and Wagner 2012, GERG-2008 | Gas density and speed of sound 0.1 %; gas enthalpy differences 0.2–0.5 %; heat capacities 1–2 %; liquid density 0.1–0.5 %; two-phase vapour pressures 1–3 % | Normal 90–450 K, 35 MPa; extended 60–700 K, 70 MPa | Secondary page (Bochum); abstract gives ranges only |
| E-PPR78 (Jaubert et al. 2022) | VLE deviation 8.6 % (fitted to VLE only) and 8.8 % (with hM and cPM), over 131 207 VLE points in 1301 binaries, 40 groups. Whether the metric is bubble pressure or composition is unconfirmed | 40 groups | Abstract |
| McBride, Zehe, Gordon 2002 (CEA polynomials) | Not retrievable (NTRS 403) | — | Not found |
| Maxwell-Bonnell / API TDB 5A1.18 | No own accuracy statement. Third-party: < 5 % at 50 kPa on gasoline fractions (Oil Shale 2020); > 150 % errors at low pressure for heavy cuts, 41 % AAD on light cuts, and advice against use below 50 kPa (a Calgary heavy-oil thesis) | — | Own statement not found; third-party only |

**Reading.**

- **Pure fluids.** The reference EOS for the pilot fluids are 0.02–0.05 % in density and 0.1–1.5 % in cp. The proposed G0 targets of section 3.2 (1.5–10 %) are therefore 30 to 100 times looser than the reference uncertainty. That is the intended estimated-with-declared-error grade, and the oracle's own error is negligible in the scoring.
- **Mixture targets.** The E-PPR78 VLE deviation of about 9 % and GERG-2008's two-phase 1–3 % bracket the mixture target: bubble pressure ≤ 10 % AAD is E-PPR78's own level, not better.
- **Transport targets.** The ≤ 3 % vapour and ≤ 5 % liquid viscosity targets sit at the reference correlations' own 2–5 %. They can be met only for species with reference correlations: N2, CO2 and H2, not the DWSIM-sampled light ends or the cuts.
- **Solids.** No stated uncertainty exists for the Fray-Schmitt fits or the Jäger-Span sublimation line. The solid CO2 sublimation target is therefore set on physical grounds rather than on a quoted source uncertainty: ≤ 5 % in pressure, equivalent to about 0.4 K at 195 K. The ≤ 3 % in section 3.2 is replaced by ≤ 5 % (proposed for G0).
- **Maxwell-Bonnell** has no stated accuracy, and third-party checks show large errors for heavy cuts at low pressure. It is a second correlation, not a reference; this feeds the D5 recommendation.

## 4. Source manifest

The full manifest, with URLs, revisions, licences, sha256 hashes and sizes, is `research/2026-09-24-coolprop-low-temperature/sources/MANIFEST.md`. In summary:

| Item | Fetched | Revision / date | Licence | Size |
|---|---|---|---|---|
| (a) NASA CEA `data/thermo.inp`, plus `LICENSE.txt`, `NOTICE.txt` and the species extract | Yes | nasa/cea `3f4441d2` (2026-09-21) | Apache-2.0 | 1.23 MB (+ 23 kB extract) |
| (b) CoolProp: 18 fluid files, `mixture_binary_pairs.json`, `mixture_departure_functions.json`, `LICENSE` | Yes, all names resolved | `ae81610e` (8.0.0, 2026-06-27) | MIT | 2.40 MB fluids + 218 kB mixtures |
| (b') ATcT ΔfH° overlay (`INFO.STANDARD_STATE`) | Extracted | CoolProp master `9b35f538` (2026-08-22) | MIT | 8.5 kB |
| (c) NIST AGA8: `GERG2008.cpp`, `.h`, test file, `LICENSE` notice | Yes | usnistgov/AGA8 `3bdb9ab8` (2025-02-20) | NIST notice | 101 kB |
| (d) E-PPR78: IntechOpen chapter 71837 PDF; Jaubert et al. 2022 HAL manuscript | Chapter and manuscript yes; **group table no** | 2020 (CC BY 3.0); HAL hal-03679277 (CC BY-NC-ND 4.0) | as stated | 0.66 + 2.89 MB |
| (e) Fray and Schmitt 2009 coefficients, via arXiv 2009.02277 | Yes (PDF; not transcribed) | v1 | arXiv non-exclusive licence | 4.43 MB |
| (f) IAPWS R7-97(2012) IF97, R14-08(2011), R10-06(2009) | Yes | as released | Attribution to IAPWS | 0.41 + 0.17 + 0.27 MB |
| (g) Mohammadi et al. 2021, article and SI docx | Yes (**databank not published**) | Sci. Rep. 11, 17911 | CC BY 4.0 | 2.62 + 0.94 MB |

Total about 16 MB.

**Corrections to the review's section 5 source table**, found while fetching:

1. **E-PPR78 table.** The Akl/Bkl values are not in the IntechOpen chapter.
   - They are in Table S1 of the supplement of Xu et al. 2017 (IECR 56, 8143), which returns HTTP 403 behind a bot check.
   - The current 40-group set is Table S4 of the Supporting Information of Jaubert et al. 2022.
   - Route: a browser download of the Elsevier supplementary file.
2. **Xu et al. 2015 is not open on HAL.** hal-01267209 has no file.
3. **The ATcT values need a newer CoolProp revision.** They are on CoolProp master (`9b35f538`), not in 8.0.0.
4. **The mixture file is larger than stated.** It holds 888 pair entries, not 194 GERG pairs; CO2/N2 and the air pairs come from Gernert 2013 (EOS-CG). NH3 has no pairs.
5. **Transport models at 8.0.0 differ from the review's list.**
   - Methane viscosity is Quiñones-Cisneros 2006 and its conductivity Friend 1989, not Sotiriadou 2025.
   - Ethylene and CO have no transport model at all.
   - H2S has viscosity only.
6. **CoolProp's `T_max` values are not validity ranges.** CO2 2000 K, N2 2000 K, Ar 2000 K and water 2000 K are extrapolation limits. The review's CO2 1100 K and N2 1000 K are the published ranges, and those should be used.
7. **The Mohammadi 2021 data points are not available.** The 919 points are only a figure; the article's Table 1 lists the 48 primary sources.

## 5. Process anchors

**Method.** An opus subagent checked each review number (plan section 5 P0, review F10) against open sources on 2026-09-24. Where a source does not say gauge or absolute, the basis is recorded as "basis unstated". Conversions use 101.325 kPa/atm, 6.895 kPa/psi and 0.1333 kPa/mmHg.

**Confidence words:**

| Word | Meaning |
|---|---|
| Confirmed | An open textbook-grade, lecture, government or review source states it |
| Vendor or patent only | Only a vendor page or a patent states it |
| Unverified | Nothing open found |
| Not found | The cited source does not contain the number |

Ullmann's "Ethylene", Kirk-Othmer, Sadrameli 2015, US 4,797,197, Sawarkar 2007 (full text) and Ren, Patel, Blok 2006 (full text; the abstract states none of the anchors) could not be opened.

| Family | Quantity | Value found | Pressure basis → absolute | Source | Confidence | Versus the review / plan |
|---|---|---|---|---|---|---|
| Steam cracking | Coil outlet pressure | 170–250 kPa | Basis unstated | Gholami et al. 2021, Energies 14, 8190 (citing Zimmermann and Walzl) | Confirmed (basis open) | Matches; treat as absolute pending Ullmann's |
| | | 1.50–2.75 bar (model 1.70) | Basis unstated | Marcos 2016, MSc thesis, IST Lisbon | Confirmed (basis open) | Wider |
| | | Plant data: ethane 2.12, propane 2.00, naphtha 1.55 bara | Absolute: 212 / 200 / 155 kPa | Moreira 2015, IST extended abstract | Confirmed | Supports about 1.5–2.5 bar absolute |
| | | 0.25–0.75 barg | 126–176 kPa abs | US 5,990,370 | Patent only | — |
| | Dilution steam | Ethane 0.3 kg/kg; gas feeds 0.25–0.4; liquids 0.5–1.0 (naphtha example 0.6–0.7; thesis 0.40–0.55) | — | US DOE/EERE 2000 chemical industry profile, ch. 2; Gholami 2021; Marcos 2016 | Confirmed | Ethane 0.3 matches; naphtha 0.5 is the low end |
| | Residence time | 0.1–0.5 s (modern coils 0.08–0.25 s) | — | Gholami 2021; Moreira 2015 (naphtha 0.4 s) | Confirmed | Matches |
| | Coil outlet temperature | 760–870 °C (1033–1143 K); 816–871 °C (1089–1144 K); 775–875 °C (1048–1148 K); 800–850 / 800–860 °C | "Slightly above atmospheric" (OSHA) | DOE 2000; OSHA Technical Manual Sec. IV ch. 2; Gholami 2021; Moreira 2015; Marcos 2016 | Confirmed | Supports the 1143 K target. **The review's 819–884 °C was not found**: US 5,990,370 gives only 823–838 °C, so the review figure looks mis-cited. Linde's 800–870 °C is vendor only |
| | Transfer-line exchanger outlet | 576–592 °C | — | US 5,990,370 | Patent only | Open sources: 400–500 °C (Gholami 2021, not feed-specific); 550–650 °C (IST theses, liquid feeds); gas feeds about 300 → 200 °C in secondary TLEs (Moreira); 250–400 °C (Borsig, vendor). **Do not freeze 576–592 °C** |
| Cracker downstream | Charge-gas compressor discharge | About 36 bar (Linde); about 35 bar (Moreira, citing Ullmann's and Kirk-Othmer; Marcos) | Basis unstated: 3.60 MPa if absolute, 3.70 MPa if gauge | Linde steam-cracking page (vendor); IST theses | Confirmed as a number, basis unconfirmed | As the review said: compressor discharge, not furnace pressure |
| | Demethanizer | About 32 bar, top about −100 °C (173 K); −114 °C (159 K) for demethanization | Basis unstated | Borralho 2013, IST (citing Chauvel and Lefebvre 1989); Gholami 2021 | Confirmed (basis open) | New anchor: the cold section at about 159–173 K and 3.2–3.3 MPa lies inside the pilot's cryogenic CH4/C2H6 target |
| Delayed coking | Drum feed temperature | 870–910 °F (739–761 K) | — | OSHA SHIB 08-29-03 / EPA 550-F-03-001 | Confirmed | Matches the plan [R2] |
| | Heater outlet | 900–950 °F (755–783 K) at "25–30 psi" | Basis unstated: 274–308 kPa abs if gauge | OSHA Technical Manual | Confirmed (pressure basis open) | Review 769–783 K lies inside |
| | | About 500 °C (930 °F, 773 K) at 4 bar (60 psig) | 515 kPa abs | Ellis and Paul 1998, delayed-coking tutorial | Confirmed | — |
| | | 40–60 psig | 377–515 kPa abs | Patent snippet only (US 4,797,197 not opened) | Unverified | Keep 0.27–0.52 MPa as the heater-outlet band |
| | Drum pressure | 15–35 psig | 205–343 kPa abs | OSHA SHIB | Confirmed | Review 20–30 psig (239–308 kPa abs) lies inside |
| | | 1–5.9 bar, typically 2–3 bar | Basis unstated | Ellis and Paul 1998 | Confirmed (basis open) | — |
| Hydrotreating | Diesel example | 350–390 °C (623–663 K), 60–90 barg | 6.10–9.10 MPa abs | Shell hydrotreating page | Vendor only | Plan [R3] unchanged |
| | VGO H2 partial pressure | 300 / 1200 / up to 1500 psig | 2.17 / 8.38 / 10.44 MPa abs | Jechura 2019, Colorado School of Mines lecture notes (hydroprocessing) | Confirmed | Supports the 10 MPa ceiling; up to 10.4 MPa H2 partial pressure means a total pressure above 10 MPa for severe gas-oil service |
| | Temperatures | Heater 600–800 °F (589–700 K), pressure up to 1000 psi; distillate reactors typically 800 °F (700 K) | Basis unstated | OSHA Technical Manual; Jechura 2019 | Confirmed | **573–713 K was not found as stated**; open sources support about 589–700 K |
| VDU | Dry vacuum unit | Flash zone 20–25 mmHg (2.7–3.3 kPa) at 750–770 °F (672–683 K); top 10 mmHg (1.33 kPa) | Absolute | Jechura 2019 (crude units) | Confirmed | — |
| | Deep-cut with steam | Flash zone 30 mmHg (4.0 kPa); top 15 mmHg (2.0 kPa) | Absolute | Jechura 2019 | Confirmed | — |
| | Typical | About 395 °C (668 K); 10 mmHg desired, 25–100 mmHg (3.3–13.3 kPa) more common | Absolute | Ellis and Paul 1998 | Confirmed | Case A's 13.1 kPa is at the high end of common practice |
| | | 392–422 °C at 30–40 mmHg (4.0–5.3 kPa) | Absolute | US 10,407,630 B2 | Patent only | Review figure; the lecture-level values above replace it |
| | Wet VDU top about 10 kPa | — | — | — | Unverified | Jechura's steam case top is 2.0 kPa; keep 1–13 kPa as the VDU band |
| CDU | Flash zone | 650–700 °F (616–644 K), "slightly above atmospheric" | — | OSHA Technical Manual | Confirmed | The literature preset's 638.15 K feed lies inside |
| | Condenser | 0.5–20 psig (105–239 kPa abs), plus 6–16 psi (41–110 kPa) across the column | Gauge → absolute | Jechura 2019 | Confirmed | The literature preset's 250 kPa top is at the upper end |
| | | 328–374 °C at 1.35–1.70 barg (236–271 kPa abs) | Gauge | US 10,407,630 B2 | Patent only | — |
| Air separation | High-pressure column | About 6 bar; 5.6 bar | Linde value read from a vapour-pressure chart as absolute (560 kPa) | Bucsa et al. 2022, Entropy 24, 272; Linde ASU brochure 2019 | Confirmed | Review 5–6 bar matches |
| | Low-pressure column | About 1.4 bar; 1.5 bar (150 kPa abs) | as above | Bucsa 2022; Linde brochure | Confirmed | **The review's 1.2–1.3 bar is not supported**; use 1.4–1.5 bar absolute |
| | Temperatures | LP sump about 93 K (−180 °C); air feed about 103 K; O2 boils at 94.1 K and N2 condenses at 95.5 K in the main condenser; air condenses at 81.5 K (1 bar) and 101 K (6 bar) | — | Bucsa 2022; Linde brochure | Confirmed | Pilot N2 targets cover these; O2 and Ar are P7 |

**Anchors proposed for freezing at G0**, replacing the review's values where they differ:

| Anchor | Value | Status |
|---|---|---|
| Steam-cracking coil outlet | 1033–1148 K at about 150–250 kPa (absolute assumed; confirm the basis in Ullmann's before numeric gating) | Established |
| Dilution steam | 0.3 (ethane) to 0.5–1.0 (naphtha) kg/kg | Established |
| Residence time | 0.1–0.5 s | Established |
| Quench outlet | 470–920 K band, not one value (gas feeds about 470–770 K) | To be selected per case |
| Charge-gas compressor | About 3.5–3.7 MPa | Basis open |
| Cold section / demethanizer | 159–173 K at about 3.2 MPa | Basis open |
| Delayed coking | Heater outlet 755–783 K at 0.27–0.52 MPa abs; drum feed 739–761 K; drum 0.20–0.34 MPa abs | Established |
| Hydrotreating | Diesel 623–663 K at 6.1–9.1 MPa abs (vendor); gas oil 589–713 K with H2 partial pressure 2.2–10.4 MPa abs. The provisional 10 MPa ceiling covers the diesel case, not severe gas-oil service | Established |
| VDU | Flash zone 2.7–13.3 kPa abs at 668–683 K; top 1.3–2.0 kPa abs (dry or deep-cut) | Established; Case A's 13.1 kPa and assumed about 10.6 kPa top stay the local benchmark |
| CDU | Flash zone 616–644 K at about 105–350 kPa abs | Established |
| Air separation | HP column 0.56–0.6 MPa abs, LP column 0.14–0.15 MPa abs, 78–103 K | Established |

## 6. D5: VDU K-value policy

### 6.1 Evidence, re-measured on the current slate

**Why a re-measurement was needed.** The bias table in section 2.4 of the VDU thermo document was computed on 2026-09-08 for the pre-regrouping slate `tjl19-dwsim-10.2.3-r1`, which had cuts PC06–PC13. The crude regrouping (merged 2026-09-18, revision `crude-regrouped-r1`) kept PC01–PC07 and replaced the heavy end. The new PC08–PC12 have:
- NBPs of 687, 766, 868, 965 and 1095 K;
- Riazi-Daubert Tc/Pc from DWSIM;
- a PR78 saturation-fitted ω.

The old script (`build/pkgcmp/vdu-lit/psat-check.mjs`) no longer exists.

**The new harness.** `research/2026-09-24-coolprop-low-temperature/vdu-k-policy/psat-mb-check.mjs` re-implements the comparison. It pits the mod's PR78 (Soave-form κ with the 0.491 split) against Maxwell-Bonnell (API TDB 5A1.18, with the Watson-K correction; SG from the record's 60 °F standard density) on the bundled records. Output: `psat-mb-check-output.txt`.

**Reproduction check.**
- For the unchanged cuts PC06 and PC07 it gives −6.9 / −5.4 / −4.2 K and −8.4 / −6.7 / −5.3 K at 1 / 5 / 13 kPa, against the old table's −7.2 / −5.6 / −4.3 K and −8.5 / −6.8 / −5.4 K.
- The ratios at 13 kPa are 1.13 and 1.16, against the old 1.14 and 1.16.
- PR78 reproduces every cut's NBP within 0.01 K.

**PR78 against Maxwell-Bonnell on the current slate, with the two-point anchoring.** The anchoring fits ω and Tc to the NBP at 101.325 kPa and to the MB point at 10 mmHg (1.333 kPa), holding Pc.

| Cut | NBP K | Kw | PR − MB at 1 / 5 / 13 kPa, K | P_PR/P_MB at 13 kPa | Anchored ΔTc K | Anchored Δω | Anchored PR − MB at 1 / 5 / 13 kPa | Anchored − current PR Tsat at 250 kPa, K | PR − MB at 250 kPa, current / anchored |
|---|---|---|---|---|---|---|---|---|---|
| PC01 | 331.4 | 11.82 | −1.6 / −0.8 / −0.3 | 1.02 | −4.5 | +0.031 | −0.1 / +0.4 / +0.6 | −0.7 | −0.1 / −0.7 |
| PC03 | 431.8 | 11.61 | −3.1 / −2.1 / −1.5 | 1.06 | −7.4 | +0.057 | −0.1 / +0.3 / +0.4 | −1.3 | +1.0 / −0.3 |
| PC05 | 529.1 | 11.61 | −5.4 / −4.1 / −3.1 | 1.10 | −10.5 | +0.092 | 0.0 / +0.1 / +0.1 | −2.2 | +2.6 / +0.4 |
| PC06 | 577.9 | 11.64 | −6.9 / −5.4 / −4.2 | 1.13 | −12.2 | +0.117 | 0.0 / 0.0 / −0.1 | −2.8 | +3.5 / +0.7 |
| PC07 | 626.7 | 11.68 | −8.4 / −6.7 / −5.3 | 1.16 | −13.6 | +0.143 | 0.0 / −0.2 / −0.3 | −3.4 | +4.4 / +1.0 |
| PC08 | 687.0 | 11.75 | −10.1 / −8.2 / −6.5 | 1.18 | −14.6 | +0.175 | +0.1 / −0.3 / −0.5 | −4.0 | +5.4 / +1.4 |
| PC09 | 766.0 | 11.83 | −11.9 / −9.9 / −7.9 | 1.21 | −14.8 | +0.217 | +0.1 / −0.6 / −0.9 | −4.7 | +6.6 / +2.0 |
| PC10 | 868.2 | 11.94 | −12.8 / −10.9 / −8.9 | 1.22 | −13.2 | +0.259 | +0.2 / −0.9 / −1.3 | −4.9 | +7.6 / +2.7 |
| PC11 | 965.0 | 11.97 | −12.6 / −11.1 / −9.2 | 1.23 | −11.2 | +0.291 | +0.3 / −1.3 / −1.8 | −4.7 | +7.9 / +3.3 |
| PC12 | 1095.1 | 11.93 | −12.9 / −11.7 / −9.8 | 1.24 | −9.8 | +0.365 | +0.4 / −1.7 / −2.3 | −4.6 | +8.6 / +3.9 |

PC02 and PC04 are in the output file.

**Reading the table:**

- **The bias survives the regrouping.** It is monotonic in NBP and of the same size as before: 4.2 to 9.8 K at 13 kPa for PC06–PC12, and 6.9 to 12.9 K at 1 kPa. The corresponding K-values are 13 to 24 % high at 13 kPa. This is a systematic cut-point bias of the VDU, not a convergence issue, as the VDU document concluded.
- **The anchoring is not CDU-neutral.** It keeps the NBP exactly, but it steepens the vapour-pressure curve.
  - At the literature CDU's 250 kPa, PR78 saturation temperatures move by −0.7 K (PC01) to −2.8 K (PC06) for the cuts that distribute in the CDU, and by up to −4.9 K for the heavy ones.
  - At 1 MPa they move by up to −14.7 K.
  - Maxwell-Bonnell's own high-pressure branch also sits 3.5 to 8.6 K below the current PR78 at 250 kPa, and the anchoring halves that gap. So the anchored model is not worse above NBP by this measure; it is different.
  - The VDU document's "all methods agree within 0.5 K at 100–250 kPa" holds at 101 kPa, where both methods pass through the NBP by construction, and not at 250 kPa.
- **The anchoring fits exactly only at its two points.** Its residual against MB grows to −2.3 K at 13 kPa for PC12, because the curve shapes differ.
- **The ω of the heavy cuts ends up large.** It reaches 1.54–2.62 for PC10–PC12, which is beyond the range the PR78 κ polynomial was fitted for (about 2). The VDU document already flagged this for the old PC12/PC13.

### 6.2 Consequences of the two-point anchoring for the shared packages

1. **Scope.** The twelve cut records are shared by all eight packages: `tjl19_dwsim`, `tjl20_methane`, `tjl20_methane_nitrogen` and the five crude packages. The plan's rule is one canonical property revision per identically defined pseudo-cut across CDU, VDU and downstream units; unit names must not select tuned values. So an anchoring is a new revision of the shared records (for example `crude-regrouped-r2`) for every package, the CDU included. A VDU-only variant would need distinct cut identities with a conservative mapping, which the plan allows only for materially refined cuts.
2. **Fingerprints and neural eligibility.** `physicsFingerprint` hashes each record's Tc and ω (section 2.2), so every package's fingerprint and `scientificRevision` change.
   - The bundled transformer's pinned fingerprint `b1b9f1eb…` then matches no package. `LNN_FIRST` falls back to classical initialization for `tjl20_methane`, the five crude packages and the network projection.
   - `V3BundledRegroupedModelTest` fails until a retrained and requalified model is registered. Retraining is a separate owner decision (plan P6).
   - Completed column results go stale.
   - The fluid thermo fingerprint changes, so a fresh world is required; that is allowed under the no-compatibility rule.
3. **Qualified CDU cases and pins that would move or fail.** Expected from the mechanisms; not run, because this step had no Gradle.
   - **Literature Tia Juana CDU** (`tjl19_dwsim`, `V3LiteraturePresetTest`): distillate 697.72 ± 1.0 kmol/h and `WATER_DEW_POINT` 1.162 ± 0.02. Cut saturation temperatures shift 1 to 5 K at 250 kPa, so both are expected to move.
   - **Five gameplay presets** (`RegroupedCrudeTest`): solved draw volumes pinned at 1e-5 relative; these will fail and need re-recording after validation. Classical convergence of BONGA and COLD_LAKE within 45 s must be re-checked.
   - **`V3CatalogParityTest`:** exact equality of the retained PC01–PC07 against frozen tables; fails if any retained cut is anchored.
   - **`V3FeedFlashEquivalenceTest`:** the bitwise `tjl19_dwsim` flash grid fails.
   - **`V3TrayHydraulicsColumnTest`** (±60 Pa, ±0.08 flood) and the column GameTests (SUCCESS within 180 s): re-check.
   - **`fluidSolverRegression` chain-100** (TJL assay on the network, 1e-6 relative in declared mode): expected to fail. Re-record only after independent validation (plan P8).
   - **Not affected:** Holland Example 3-2 (its own thermo), the cdu17 test-only package, and `V3OperatingDomainValidatorTest`. The column still refuses below 50 kPa.
4. **Other physics that moves with Tc and ω.** Residual enthalpy (hence hvap and the heat balances of pumparounds and steam stripping), liquid residual cp, and the untranslated liquid volume. The ideal-gas Cp and the viscosity records do not move.
5. **Benefit today.** None in the product: no VDU consumer exists. The column refuses below 50 kPa, and the network carries no VDU case. The benefit arrives only with the VDU batch's column domain (VDU-WP0).

### 6.3 Recommendation for the decision log

The reviewer's provisional recommendation is confirmed. Proposed text for D5:

> D5 (settled in P0, 2026-09-24): keep PR78 as characterized for the pilot stage.
>
> The crude-cut records stay at `crude-regrouped-r1`. The fluid network, the pilot's compact existing-cut fixture (P1 item 5) and any VDU-pressure probe carry the PR78 vacuum bias as a declared error of the cut K-values. Re-measured on the regrouped slate (`research/2026-09-24-coolprop-low-temperature/vdu-k-policy/`), PR78 saturation temperatures of PC06–PC12 lie 4.2–9.8 K below Maxwell-Bonnell at 13 kPa (vapour-pressure ratio 1.13–1.24) and 6.9–12.9 K below at 1 kPa.
>
> The two-point anchoring (ω and Tc per cut to the NBP at 101.325 kPa and the Maxwell-Bonnell point at 10 mmHg, Pc held) becomes a separate data revision of the shared cut records, owned by the VDU batch's WP0 and not by this batch. The reasons:
>
> - It moves ω by +0.03 to +0.37 and Tc by −4.5 to −14.8 K for all twelve cuts, and PR78 saturation temperatures at the literature CDU's 250 kPa by −0.7 to −4.9 K.
> - Under the one-canonical-revision rule it is therefore a joint CDU/VDU requalification: a new revision; a changed physics fingerprint for all eight packages (the bundled transformer becomes ineligible, with classical initialization until a retraining decision); and re-recorded CDU pins after independent validation (literature preset distillate and dew point, five solved draw-volume targets, the retained-component parity tables, the bitwise feed-flash reference, the chain-100 fluid regression).
>
> Maxwell-Bonnell is itself a correlation without a stated accuracy, and third-party checks report large low-pressure errors for heavy cuts. So the anchor target needs an independent check, for example the CRC n-alkane vacuum points the VDU document used, on which all three methods agreed within 3 K.
>
> Before adopting it, the VDU batch should:
>
> - check the anchored curve at CDU pressures against a second vacuum method;
> - decide whether the anchoring covers all cuts or only PC05 and heavier;
> - bound the heavy cuts' ω, which reaches 2.6, beyond the κ polynomial's fitted range.
>
> Rejected: anchoring inside the pilot, which would mix a crude-data revision into the engine pilot and move the CDU baseline the pilot must preserve; and deciding it implicitly by extending a package pressure envelope.
>
> Affected milestones: P1 item 5 and G3 (declared error only); VDU-WP0.
>
> Coverage change: the cut K-values at 1–13 kPa are estimated with the declared bias above.

## 7. Open items handed to the parent

- **Index rows.** `research/INDEX.md` and `documentation/INDEX.md` in the main checkout need the batch row updated for the new `sources/` and `vdu-k-policy/` folders and this report. This agent's brief allowed writing only in the worktree.
- **Alternative routes for the unfetched items.** The E-PPR78 Table S4 supplementary file and the paywalled holdouts (Lin et al. 1980; Jäger and Span 2012; frost-point and SLE sets) need the owner or a browser session.
- **Methane ideal-gas reference choice.** Decide it in P1 before the spine is frozen (section 2.4).
- **Oracle fluid files.** During this step the worktree gained commits `6ded7c7` and `952774e` from the P1 oracle work (not this agent's). They track `src/test/resources/science/thermo/coolprop/`, whose six fluid files match the pinned 8.0.0 files of the manifest byte for byte. The P0 baseline of section 2 was read at `f9d6be1` and does not include that oracle.

## Sources

**Local, read in this step.**
- The plan, the decision log and the plan review in this batch folder.
- `documentation/2026-08-31-v3-vdu/V3_VDU_LITERATURE_AND_THERMO_DATA.md`.
- `AGENTS.md` and `MATERIALS.md`.
- The records under `src/main/resources/data/createcheme/materials/{packages,properties,interactions,water,transport,bases,networks,presets,assays}/`.
- Code:
  - `science/fluid/thermo/FluidDomain.java`
  - `science/material/MaterialCatalog.java` (`physicsFingerprint`, `fluidThermoFingerprint`, `fluid_domain` validation)
  - `science/column/v3/V3OperatingDomainValidator.java`
  - `science/column/v3/thermo/V3PengRobinsonSession.java`
  - `science/thermo/PengRobinsonKernel.java`
  - `science/fluid/thermo/WaterRegion1.java`
  - `science/fluid/transport/MixtureViscosity.java`
- `build.gradle` (test tasks).
- The test classes named in section 2.1.
- The neural registry and sidecar under `src/main/resources/data/createcheme/neural/`.

**Fetched.** See `research/2026-09-24-coolprop-low-temperature/sources/MANIFEST.md`:
- NASA CEA `thermo.inp` (nasa/cea `3f4441d2`, Apache-2.0; McBride, Zehe, Gordon, NASA TP-2002-211556).
- CoolProp `dev/fluids` and `dev/mixtures` at `ae81610e` (8.0.0, MIT), and `INFO.STANDARD_STATE` at `9b35f538` (ATcT 1.220).
- NIST AGA8 `GERG2008.cpp` (usnistgov/AGA8 `3bdb9ab8`, NIST notice).
- Lasala, Piña-Martinez, Jaubert 2020, IntechOpen, doi:10.5772/intechopen.92173.
- Jaubert, Qian, Lasala, Privat 2022, FPE 560, 113456 (HAL hal-03679277).
- Lisse et al. 2020, arXiv:2009.02277 (appendix reproducing Fray and Schmitt 2009, P&SS 57, 2053).
- IAPWS R7-97(2012), R14-08(2011) and R10-06(2009).
- Mohammadi et al. 2021, Sci. Rep. 11, 17911, doi:10.1038/s41598-021-97131-8, with its Supplementary Information.

**Process anchors, section 5.** Read by the web-research subagent on 2026-09-24, not stored:
- Gholami, Gholami, Nasiri, Vaziri et al. 2021, Energies 14, 8190: https://www.mdpi.com/1996-1073/14/23/8190
- Marcos 2016, MSc thesis, IST Lisbon: https://fenix.tecnico.ulisboa.pt/downloadFile/563345090415270/Thesis_Joao_Marcos_73026.pdf
- Moreira 2015, IST extended abstract: https://fenix.tecnico.ulisboa.pt/downloadFile/1126295043834327/JVM_ExtendedAbstract.pdf
- Borralho 2013, IST dissertation: https://fenix.tecnico.ulisboa.pt/downloadFile/395145831373/dissertacao.pdf
- US DOE/EERE 2000, chemical industry profile, ch. 2: https://www1.eere.energy.gov/manufacturing/resources/chemicals/pdfs/profile_chap2.pdf
- OSHA Technical Manual Sec. IV ch. 2, Wayback copy: http://web.archive.org/web/2019id_/https://www.osha.gov/dts/osta/otm/otm_iv/otm_iv_2.html
- OSHA SHIB 08-29-03 / EPA 550-F-03-001: https://www.epa.gov/sites/default/files/2013-11/documents/delayed_coker.pdf
- Jechura 2019, Colorado School of Mines CBEN409 lecture notes (crude units, coking, hydroprocessing), under https://people.mines.edu/jjechura/wp-content/uploads/sites/120/2019/02/
- Ellis and Paul 1998, "Tutorial: Delayed Coking Fundamentals", DECOKTUT.pdf at the same location
- Bucsa et al. 2022, Entropy 24, 272: https://pmc.ncbi.nlm.nih.gov/articles/PMC8870991/
- Linde, "Air separation plants: history and technological progress" (2019 brochure)
- Linde steam-cracking and Shell hydrotreating pages (plan [R3], [R4]); Borsig transfer-line exchangers page (vendor)
- Patents US 5,990,370 and US 10,407,630 B2 (patent-only values)

**Source uncertainties, section 3.4.** Read from abstracts or open copies:
- NIST publication 907386 (Span et al. 2000)
- the DOIs of Setzmann and Wagner 1991, Bücker and Wagner 2006, Span and Wagner 1996, Leachman et al. 2009, Laesecke and Muzny 2017 (OSTI 1463125), Huber et al. 2016, Trusler 2011, Jäger and Span 2012, Kunz and Wagner 2012 and Jaubert et al. 2022
- the NIST page for Lemmon and Jacobsen 2004
- the Ruhr-Universität Bochum GERG-2008 page
- Oil Shale 37 (2020) 288–303
- a University of Calgary heavy-oil thesis (Maxwell-Bonnell third-party checks)

**Referenced, not fetched.**
- Xu, Jaubert, Privat, Arpentinier 2017, IECR 56, 8143, doi:10.1021/acs.iecr.7b01586 (supplementary material: HTTP 403 bot check).
- Xu et al. 2015, IECR 54, 2816.
- API Technical Data Book procedure 5A1.18 (Maxwell and Bonnell 1957, IEC 49, 1187), whose equations are implemented in the D5 harness.
- NIST WebBook methane Shomate coefficients (Chase 1998), as implemented in the bundled record and its test.

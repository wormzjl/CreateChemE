# G3 qualification report (P3 WP9b)

Batch `2026-09-24-coolprop-low-temperature`, stage P3 of `UNIFIED_MULTIPHASE_THERMO_PLAN.md`, work package WP9b of
[P3_PILOT_ENGINE_PLAN.md](P3_PILOT_ENGINE_PLAN.md) (section 8: the domain table 8.1 and the fixture families F1 to F10 of
8.2). Written 2026-09-25 on branch `claude/coolprop-multiphase-thermo-37f6b0`. Decisions used: D3, D5, D6, D7, D8, D13,
D14 of [DECISION_LOG.md](DECISION_LOG.md). ASCII only.

## 0. Status

**G3 is not passed yet.** The ten fixture families exist as gate tests and run green at `f2d5221` (46 tests with the
existing gates, section 8); the two hot-gas label checks that failed before WP7c pass since WP7c (section 3). Measured against the D7 targets with the D14 revisions, the pilot meets most rows, but
six rows miss a D7 or D14 bound (or have none) and four engine findings were made (section 7); each is encoded as a held bound labelled "G3
proposal" or as a known-defect list, printed next to the D7 verdict, so that nothing is revised silently. The decisions
are the lead's (a D15 entry, section 6); the engine defects go to the engine owner (section 7).

**Update 2026-09-25 (WP7d, `774cb82`, section 11):** D15 took the six proposals as declared errors and widened box 2;
WP7d fixed the four engine defects (and a fifth it found), put D14's corner and the widened box 2 into the engine's band,
and replaced the "G3 proposal" labels by the D15 bounds. F1 to F10 re-run green; every G3 criterion is met or declared
(section 11.4). Sections 0 to 10 below are the WP9b record as written.

| Item | State |
|---|---|
| Fixture families F1 to F10 | Implemented: 10 test classes plus a support class in `src/test/java/com/wormzjl/createcheme/science/thermo/qualification/` (section 9) |
| F1, F2, F4, F8, F9 | Run green; F1, F2 and F4 hold G3 proposals on the rows D7/D14 miss (section 6) |
| F3, F10 | Green; their hot-gas label tests failed at `8e5274e` and pass since WP7c (`f2d5221`) |
| F5 | Green with one known engine defect (an untyped `NOT_CONVERGED` outside the band) |
| F6, F7 | Reported (probes and research-only), green |
| Domain table 8.1 | Filled (section 1) |
| Cost measurements of plan section 10 | Not measured here (WP11's cost pair); the per-call budgets were met in WP6a (section 5, criterion 11) |
| Commit | Test sources only (the report and `tools/g3-qualification/` are git-ignored); section 10 |

## 1. Process-indexed domain table (plan 8.1, P0 1.3)

"Before" is P0 section 1.3; "Achieved" is what the G3 fixtures measured. Packages: `pilot` = `createcheme:pilot_cryogenic`,
`net` = `createcheme:tjl20_methane_nitrogen`. Grades: EDE = estimated with declared error, RO = research-only. The band
is the declared critical band as the engine encodes it (box 1 Tr 0.95-1.1 x Pr 0.8-1.5, box 2 Tr 0.90-0.95 x Pr
0.58-0.74, box 3 Tr 1.05-1.2 x Pr 2-3) plus D14's corner (Tr 1.0-1.1 x Pr 1.5-2), which the engine does not encode yet.

| Process anchor | Package | Species / phase | Before | P3 target | Fixtures | Achieved (grade, worst deviation, exclusions) |
|---|---|---|---|---|---|---|
| Air separation 78-103 K, 0.14-0.6 MPa; cracker cold section 159-173 K, about 3.2 MPa | pilot | N2, CH4, C2H6 liquid and VLE | N2 63.151 to about 115 K at <= 2 MPa; CH4, C2H6 >= 293.15 K | Triple point to band edge, to 10 MPa; ethane Psat below 150 K declared (D3) | F1, F2, F4 | EDE from each triple point (N2 63.151, CH4 90.694, C2H6 90.368 K) to the band edge (N2 119.9, CH4 181.0, C2H6 290.1 K), 0.1 to 10 MPa below the melting line. Psat <= 1.27 % (Tr 0.6-0.95), <= 4.48 % (0.45-0.6); saturated liquid density mean 0.69-1.65 % per species, worst 2.26 %; compressed liquid <= 2.33 % (Tr <= 0.85), -4.41 % at Tr 0.85-0.9 next to saturation (G3 proposal 5 %); hvap <= 3.12 %; VLE N2/CH4, N2/C2H6, CH4/C2H6 meet the D7 K-value row (AAD 0.71, 5.08, 1.33 %). Declared: ethane Psat below 150 K (+28.7 % at the triple point), ethane liquid cp below 150 K (-10.6 %, G3 proposal 12 %), vapour cp at Z >= 0.9 (to -11.6 %, G3 proposal 12 %). Excluded: the band boxes (RO); the demethanizer top of pure methane (173 K, 3.2 MPa: Tr 0.91, Pr 0.70) lies in box 2 |
| same | net | N2 liquid | 63.151-115 K, <= 2 MPa | 63.151 K to band edge 119.9 K, compressed to 10 MPa | F1, F4, F10 | EDE 63.151-119.9 K to 10 MPa (anchor at 90 K, 2 MPa): saturated liquid -0.07 to -3.58 % at the P0 states, compressed isotherms 77-100 K <= 1.15 %, 110 K -3.55 % next to saturation (Tr 0.87: D7 3 % not met, G3 proposal 5 %); no jump at 2 MPa (fourth differences <= 3.0e-4 of the second) |
| Dense and supercritical, hydrotreating pressure 6.1-9.1 MPa (10 MPa ceiling) | pilot | N2, CH4, C2H6, CO2 supercritical, dense liquid | none above 2 MPa | to 10 MPa outside the widened band; band research-only | F1, F4, F5 | EDE to 10 MPa outside the band: supercritical density <= 5.93 % (C2H6 320 K 10 MPa), cp <= 6.60 % except N2 -10.59 % next to box 1 (G3 proposal 12 %); vapour-like density Z >= 0.8 <= 2.09 % (F1; D14 2.5 %); D14 corner -8.02 % (N2 131.19 K 5.1 MPa, F5; WP5 measured CH4 200 K 7 MPa -8.42 %; D14 9 %). RO: the three boxes (worst density -13.8 %, box 1) and the corner. Not covered by any box: the liquid at Tr 0.95-0.97 below Pr 0.8 (N2 121 K 2.7 MPa -9.06 %, G3 proposal: declared 10 % or widen box 2). CO2's supercritical states near ambient to 10 MPa lie mostly in box 1 |
| same | net | N2, CH4 | <= 2 MPa | to 10 MPa (fluid_domain), band research-only | F4, F9, F10 | N2 and CH4 accepted to 10.0 MPa, refused at 10.05 MPa naming the component; N2 supercritical 130 K 6 MPa -6.46 % (anchor a, corner), 300 K 10 MPa +0.02 %; CH4 gas isotherms 300 and 400 K to 10 MPa <= 1.21 %, 293.15-600 K at <= 2 MPa <= 0.64 %; ethane and the cuts keep 2 MPa (refused at 2.01 MPa) |
| Steam-cracking coil outlet 1033-1148 K, 0.15-0.25 MPa (frozen composition) | pilot | N2, CH4, C2H6, CO2 gas | <= 900 K | to 1143 K qualified, 1200 K provisional | F3, F8, F9 | Ideal gas qualified to 1200 K: Cp <= 0.133 % (CH4 at 1200 K against the line list), h - h(298.15) <= 9.6 J/mol, S <= 0.009 J/(mol K); residual enthalpy <= 0.024 % of the sensible change at 0.15-0.25 MPa (EDE; against the oracle within 4.3 J/mol at each EOS's upper range); a closed N2 vessel heated 300 -> 1143 K conserves at the network gates. Excluded: methane (and the pilot envelope) above 1200 K, refused typed. The hot gas takes the vapour slot since WP7c (45 of 45 states; 21 of 45 at `8e5274e`) |
| Mixture-stabilized liquid (CO2 in liquid CH4, 110-190 K) | pilot (research contract) | CO2-lean liquid | none | fluid part research-only until P5 | F7, F9 | RO on an open research contract at 91-120 K, 0.5-2 MPa, x_CO2 1e-4 to 1e-2 (the CO2 spine extension below 216.592 K graded EDE); the production paths refuse typed (CarbonDioxide below 216.592 K); the fluid-only model splits off a subcooled CO2-rich liquid at x_CO2 1e-2 and 91-100 K (fluid-only miscibility 0.33-0.76 %), not a solubility (solid CO2, P5) |
| CDU flash zone 616-644 K at 0.105-0.35 MPa; VDU 2.7-13.3 kPa at 668-683 K (Case A 655.15 K, 13.1 kPa); coker 739-783 K at 0.20-0.52 MPa; hydrotreater 623-663 K at 6.1-9.1 MPa | net (records unchanged) | cuts and light ends | 293.15-900 K, 100 Pa-2 MPa; VDU bias declared (D5) | probes only, no claim (P0 N16/C2) | F6 | Unchanged domain; probes recorded: PR78 pure-cut NBP reproduced to 0.002 K; Tsat at 1/5/13 kPa and 250 kPa recorded beside the D5 bias (PR - MB -0.3 to -9.8 K at 13 kPa); VDU Case A and 4 kPa K-values of PC06-PC12 recorded; CDU and coking flashes two-phase, feed verdict = phase count; hydrotreating refused by `net` (crude_pc01 above 2 MPa), on a research contract a single liquid-like fluid (no H2, P7) |

Coverage rows of P0 1.3 that G3 establishes (Achieved column), in short: N1/N2/N3 nitrogen vapour, liquid, supercritical
as rows 1-4 and 5 above; N5/N6/N7 methane vapour to 1200 K, liquid 90.694-181 K, supercritical to 10 MPa (pilot); N9/N10/N11
ethane likewise (liquid to 290.1 K, Psat and liquid cp below 150 K declared); N16/N17 cuts unchanged (probes, F6); N19/N20
water unchanged in G3 (Region 1 at the state pressure since WP4; refused below 273.16 K and above 623.15 K, F9); N23/N24/N25
CO2 vapour 216.592-1200 K, liquid 216.592-288.9 K (band edge), supercritical to 10 MPa outside box 1 (pilot; the fluid
below 216.592 K only on a research contract, F7). N4/N8/N12/N22/N26 solids: unavailable (the CO2-I record refuses; P4/P5).

## 2. Family results

Every table below is quoted from the printed output of the fixture (Gradle test XML `system-out`, and the standalone
runs in `tools/g3-qualification/out/`). Deviations are model minus reference, in percent unless stated. "Held" is the
bound the test asserts.

### F1: pure fluids against the oracle (`G3F1PureFluidOracleTest`)

Grid: the P1 grid. Saturation sweep 30 temperatures per species from the triple point to Tr 0.95 (11 states in the band
not scored); isobars 0.1, 0.5, 1, 2, 5, 10 MPa x 24 temperatures to 1.2 Tc plus Tsat(P) +- 0.5 K: 542 states scored, 22
solid-stable states dropped (above the reference melting line, as P1 dropped them), band boxes 1/2/3: 20/8/14 not scored
(worst density -13.77 % box 1, -7.93 % box 2, -7.04 % box 3), 0 branch mismatches. The per-isobar means reproduce P1
section 2.3 (for example N2 0.1 MPa: density 0.30 / +1.51 %, cp 1.86 / -7.6 %). Reduced variables use the oracle's
critical constants. The Tr 0.8 saturated liquid is the one fitted point; everything else is held out.

Worst per region over all four species (D7 verdict next to the D14 bound):

| Region | Worst (species, state) | D7 | D7 verdict | D14 | D14 verdict | Held |
|---|---|---|---|---|---|---|
| Psat Tr 0.6-0.95 | +1.27 (N2 76.84 K) | 1.5 % | met | - | - | 1.5 % |
| Psat Tr 0.45-0.6 | +4.48 (N2 63.15 K) | 5 % | met | - | - | 5 % |
| Psat ethane below 150 K | +28.74 (C2H6 90.37 K, dTsat +0.96 K) | declared (D3) | - | - | - | report |
| Saturated liquid density Tr <= 0.85, mean | 1.65 (CH4) | 2 % mean | met (all four) | - | - | 2 % mean |
| Saturated liquid density Tr <= 0.85, worst | +2.26 (CH4 112.50 K) | 3 % | met | - | - | 3 % |
| Saturated liquid density Tr 0.85-0.95 | -9.04 (C2H6 290.06 K) | 10 % declared | met | - | - | 10 % |
| Compressed liquid Tr <= 0.85 | +2.33 (CH4 126.69 K 10 MPa) | 3 % | met | - | - | 3 % |
| Compressed liquid Tr 0.85-0.9 | -3.34 (N2 113.05 K 2 MPa) | 3 % | NOT met | - | - | 5 % (G3 proposal) |
| Liquid density Tr 0.90-0.95 | -4.90 (CH4 180.68 K 5 MPa) | 5 % | met | 6 % | met | 6 % |
| Liquid density Tr 0.95-1, Pr > 1.5 | -2.18 (CH4 186.68 K 10 MPa) | 5 % | met | - | - | 5 % |
| Vapour density Z >= 0.8 | +2.09 (N2 132.24 K 2 MPa, Z 0.805) | 1 % | NOT met | 2.5 % | met | 2.5 % |
| Vapour cp Z >= 0.9 within 0.05 Tr of saturation (isobars) | -9.72 (CO2 216.59 K 0.5 MPa) | 2 % | NOT met | 10 % | met | 12 % (G3 proposal) |
| Vapour cp, saturated vapour Z >= 0.9 | -11.58 (CO2 224.07 K, Z 0.900) | 2 % | NOT met | 10 % | NOT met | 12 % (G3 proposal) |
| Vapour cp Z >= 0.9 beyond 0.05 Tr from saturation | -8.45 (CO2 248.84 K 1 MPa, Z 0.902) | 2 % | NOT met | 2 % | NOT met | 12 % (G3 proposal) |
| Liquid cp Tr <= 0.8 | -9.08 (CO2 223.04 K 10 MPa) | 10 % | met | - | - | 10 % |
| Liquid cp ethane below 150 K | -10.63 (C2H6 90.37 K) | 10 % | NOT met | - | - | 12 % (G3 proposal, with D3) |
| Liquid cp Tr 0.8-0.9 | +15.60 (CH4 168.57 K) | 20 % declared | met | - | - | 20 % |
| Liquid cp Tr > 0.9 | +27.07 (C2H6 290.06 K) | unavailable, flagged | - | - | - | report |
| Supercritical density | -5.69 (C2H6 318.38 K 10 MPa) | 7 % | met | - | - | 7 % |
| Supercritical cp | -10.59 (N2 139.92 K 5 MPa, Tr 1.109, Pr 1.47) | 10 % | NOT met | - | - | 12 % (G3 proposal) |
| hvap Tr <= 0.9 | -3.12 (C2H6 90.37 K) | 3.5 % | met | - | - | 3.5 % |

Vapour cp by the reference Z, all vapour states of the grid (the evidence behind the vapour cp proposal):

| Species | Z 0.90-0.95 | Z 0.95-0.98 | Z >= 0.98 |
|---|---|---|---|
| N2 | -4.25 (105.37 K 0.5 MPa); saturated -7.36 | -3.78 (77.74 K 0.1 MPa) | -0.89; saturated -2.10 |
| CH4 | -5.15 (144.69 K 0.5 MPa); saturated -7.68 | -3.87 (112.01 K 0.1 MPa) | -1.07; saturated -2.32 |
| C2H6 | -4.55 (234.38 K 0.5 MPa); saturated -7.06 | -2.53 (184.83 K 0.1 MPa) | -0.90; saturated -1.56 |
| CO2 | -9.72 (216.59 K 0.5 MPa); saturated -11.58 | -3.95 (248.84 K 0.5 MPa) | -1.84 (216.59 K 0.1 MPa) |

Reading: the cubic's vapour cp error scales with the non-ideality 1 - Z rather than with the distance from saturation;
it falls below 2 % only at Z >= 0.98 (and just exceeds it for the saturated vapour there: CH4 -2.32 % at 100 K).

### F2: GERG-2008 bubble points (`G3F2GergBubblePointTest`)

The WP8 fixture's 144 clean bubble rows (no flag), per pair, E-PPR78 against the zero-kij baseline (the rule-pair constants
of the pilot package are 0.0, so `CubicPhaseEvaluator.fromPackage` on them is the zero-kij model). D7: AAD 10 %, point 20 %,
|dy| 0.02, |d ln K| 0.15 for x or y above 1e-3.

| Pair | Model | Rows | AAD % | Worst dP % | Worst dy | Worst d ln K | D7 verdict | Held |
|---|---|---|---|---|---|---|---|---|
| N2/CH4 | E-PPR78 | 36 | 0.71 | -3.22 (95 K x1 0.05) | -0.0091 | +0.057 | met | D7 |
| N2/CH4 | zero kij | 36 | 8.08 | -25.46 | -0.0860 | -0.340 | NOT met | baseline |
| N2/C2H6 | E-PPR78 | 14 | 5.08 | -12.40 (150 K x1 0.05) | -0.0173 | +0.114 | met | D7 |
| N2/C2H6 | zero kij | 14 | 19.75 | -38.63 | -0.0372 | +0.384 | NOT met | baseline |
| CH4/C2H6 | E-PPR78 | 34 | 1.33 | -3.19 (150 K x1 0.05) | -0.0091 | +0.063 | met | D7 |
| CH4/C2H6 | zero kij | 34 | 2.67 | -8.12 | -0.0183 | +0.114 | met | baseline |
| CO2/N2 | E-PPR78 | 7 | 4.63 | -8.85 (220 K x1 0.95) | +0.0127 | +0.058 | met | D7 |
| CO2/N2 | zero kij | 7 | 2.46 | -7.24 | +0.0103 | +0.047 | met | baseline |
| CO2/CH4 | E-PPR78 | 13 | 2.22 | -8.14 (220 K x1 0.95) | +0.0222 (220 K x1 0.95) | +0.059 | NOT met: dy | D7, dy 0.025 (G3 proposal) |
| CO2/CH4 | zero kij | 9 (4 not converged) | 23.71 | -41.41 | +0.1681 | +0.376 | NOT met | baseline |
| CO2/C2H6 | E-PPR78 | 40 | 1.04 | +2.96 (220 K x1 0.30) | -0.0212 (220 K x1 0.90) | +0.134 | NOT met: dy | D7, dy 0.025 (G3 proposal) |
| CO2/C2H6 | zero kij | 37 (3 not converged) | 15.32 | -26.57 | -0.1309 | -1.022 | NOT met | baseline |

E-PPR78 meets the pressure and ln K criteria on every pair and beats the zero-kij baseline on five of six (CO2/N2 is the
exception: its kij is near zero at 220-290 K, and the zero-kij AAD is 2.46 % against 4.63 %). The two misses are |dy| at
220 K on the CO2-rich side (x_CO2 0.9-0.95), 1.2 and 2.2 thousandths beyond 0.02, next to CO2's triple point.

Reported, not gated: the flagged rows (near_critical: rhoL/rhoV below 3) N2/CH4 3 rows AAD 0.30 %, N2/C2H6 8 rows AAD
5.35 % (worst +11.94 %), CH4/C2H6 3 rows (2 not converged by the fixture's own bubble solver next to the critical end),
CO2/N2 5 rows (1 not converged) AAD 5.56 % (worst |dy| 0.0267), CO2/CH4 5 rows AAD 0.77 %; the six `above_10_MPa`-flagged
rows carry the near_critical flag too. The dew rows (normal branch): AAD 0.60 to 1.47 %, worst dP -7.63 % (CO2/CH4 220 K
y1 0.20), worst dx 0.035, worst d ln K 0.200 (C2H6/N2 150 K and CO2/C2H6 220 K), 1 not converged of 190.

### F3: high-temperature ideal gas and residual (`G3F3HighTemperatureIdealGasTest`)

Holdouts: NIST-JANAF N-023 and C-095 text tables (read 2026-09-25), the Gurvich ethane Cp of the NIST WebBook (as
`ReferenceSpineTest`), the ExoMol MM line-list reference of WP10 for methane (`methane_cp_table.json`: `cp_reference`,
`dh_reference`). D7: Cp 0.2 %, h(T) - h(298.15) 0.1 % or 20 J/mol, S within 0.02 J/(mol K).

| Species | T K | dCp % | dh J/mol | dS J/(mol K) | Holdout |
|---|---|---|---|---|---|
| N2 | 1000 / 1100 / 1200 | -0.002 / +0.003 / +0.004 | -0.7 / -0.3 / -0.4 | +0.0008 / +0.0002 / +0.0008 | JANAF N-023 |
| CO2 | 1000 / 1100 / 1200 | +0.001 / +0.020 / +0.009 | +7.8 / +8.9 / +9.6 | +0.0068 / +0.0077 / +0.0088 | JANAF C-095 |
| C2H6 | 1000 / 1100 / 1200 | -0.008 / -0.009 / -0.002 | - | - | Gurvich (NIST WebBook) |
| CH4 | 1000 / 1073 / 1100 / 1143 / 1200 | +0.009 / +0.039 / +0.053 / +0.082 / +0.133 | -7.3 / -6.0 / -5.0 / -2.9 / +1.9 | - | ExoMol MM line list |

Worst: Cp 0.133 %, h 9.6 J/mol, S 0.0088 J/(mol K): **met**. The residual enthalpy of the network's gas at 1073-1200 K and
0.15-0.25 MPa: worst |h^R| / (h(T) - h(298.15)) 0.024 % (N2 1073 K 0.25 MPa; D7 0.1 %: **met**); the frozen coil-outlet gas
N2/CH4/C2H6 0.1/0.4/0.5 at 1123 K and 0.2 MPa through the network's own flash: 0.0005 %, and its network enthalpy equals
the spines plus the residual to 1e-9. Against the oracle at each reference equation's upper range (0.2 MPa): h^R differs
by +0.36 (N2 1000 K), -1.11 (CH4 625 K), -4.27 (C2H6 675 K), +2.07 J/mol (CO2 1100 K). Above those ranges the residual has
no reference (declared, plan section 5).

Label check `coilOutletGasTakesTheVapourSlot`: **met at `f2d5221`** (45 of 45 states in the vapour slot). At `8e5274e`
the hot dilute gas took the network's liquid slot: N2 at 1073-1200 K, CH4 at 1073-1200 K, CO2 at 1200 K and the
coil-outlet mixture at 1200 K (24 of 45 states), because the phase identification parameter creeps just above one
above a gas's Boyle temperature (N2 1.0004 at 1073 K, Z 1.0006). Section 3.

### F4: pressures to 10 MPa and continuity through 2 MPa (`G3F4HighPressureContinuityTest`)

The P1 dense and supercritical probe states (P1 section 2.5, H2 omitted): 26 scored, 16 in the band (reported); the pilot
reproduces P1's anchor (b) density to 0.02 % on every scored row (and to the printed digits on the band rows); every scored state within its held bound; examples: N2 130 K
6 MPa -5.60 % (corner, D14 9 %), C2H6 320 K 10 MPa -5.93 %, CO2 280 K 8 MPa -3.24 % (Tr 0.92, D14 6 %). The bundled
network's rows (anchor a): N2 77.36 K 10 MPa -0.17 %, 130 K 6 MPa -6.46 % (corner), 300 K 10 MPa +0.02 %; CH4 300 K 10 MPa
+0.83 %.

Isotherms 0.1 MPa (or 1.02 Psat) to 10 MPa, 41 points each, 1128 points scored against the oracle, every point within its
held bound; D7 met at every point except the near-saturation liquid at Tr 0.9 (N2 33 of 35, CH4 29 of 34, C2H6 27 of 34,
CO2 22 of 41: worst -4.41 % C2H6 274.79 K at 1.02 Psat) and the vapour-like points at Z 0.8-0.9 of the Tr 1.3 isotherms
(D7 1 %, D14 2.5 % met). The bundled network's own domain-checked `phase` accepts nitrogen liquid and methane gas at every
point to 10.0 MPa.

Continuity through 2 MPa: on 41 points around 2 MPa (1-3 MPa, narrowed to start at 1.05 Psat where needed) the largest
fourth difference relative to the largest second difference of v(P) (liquids) or P v(P) (gases) and of h(P) is at most
6.9e-4 (N2 Tr 1.3); for the liquids 1.0e-5 to 3.0e-4. A jump or kink at 2 MPa would give a value of order one: **no jump**.
No liquid window exists where Psat is at or above 1.9 MPa (Tr 0.9 isotherms, net N2 at 110 K narrowed to 1.54-2.46 MPa).

### F5: critical-region paths (`G3F5CriticalRegionPathTest`)

The pilot engine (`FluidTpEquilibrium` on `PhaseContract.forNetworkPackage` and `CubicPhaseEvaluator.forPackage`).

| Path | States | Converged (in band) | Typed CRITICAL_BAND | NOT_CONVERGED outside | Grade errors | Derivatives present / refused | D14 corner states (in engine band) | Scored density, worst % |
|---|---|---|---|---|---|---|---|---|
| N2 Tc-5 K, 2-10 MPa | 161 | 161 (47) | 0 | 0 | 0 | 161 / 0 | 0 | -9.06 (121.19 K 2.70 MPa, liquid Tr 0.96 Pr 0.795: band gap) |
| N2 Tc-2 K | 161 | 161 (47) | 0 | 0 | 0 | 161 / 0 | 0 | -4.56 (124.19 K 5.10 MPa) |
| N2 Tc | 161 | 161 (47) | 0 | 0 | 0 | 161 / 0 | 34 (0) | -5.56 (corner) |
| N2 Tc+2 K | 161 | 161 (47) | 0 | 0 | 0 | 161 / 0 | 34 (0) | -6.62 (corner) |
| N2 Tc+5 K | 161 | 161 (47) | 0 | 0 | 0 | 161 / 0 | 34 (0) | -8.02 (131.19 K 5.10 MPa, corner) |
| CO2 Tc-5 ... Tc+5 K | 5 x 161 | 805 (410) | 0 | 0 | 0 | 805 / 0 | 0 | +1.70 (309.13 K 3.90 MPa, vapour) |
| N2/CH4 0.67/0.33, 150 K, 3-5.5 MPa | 251 | 250 (47) | 0 | **1** | 0 | 338 / 0 | - | - |

Every answer inside the band is converged and graded research-only; outside it every answer but one is converged and
graded estimated-with-declared-error. The exception: **N2/CH4 0.67/0.33 at 150 K and 4.69 MPa is `NOT_CONVERGED` ("the
feed's stability test is unresolved") outside the band and not typed `CRITICAL_BAND`** (one state of 3,146 on a finer
x 0.55-0.80 x 4.0-5.2 MPa scan; the WP6a known limit: a single phase whose trials end at the trivial solution carries no
stationary point, so the band's single-phase side misses it). In the network this is a plain refusal, not a
`CriticalBandHold`. It is held as a known-defect list (the test fails on any other such state and reports this one as
fixed when it converges or becomes typed). The D14 corner (34 states per N2 isotherm above Tc) is graded
estimated-with-declared-error by the engine, not research-only as D14 says (the engine's pure band has the three boxes
only). No derivative refusal occurred (none of these states sits at root coalescence). The network-level reference is
WP7's `NearCriticalNitrogenIslandTest` (run in the G3 Gradle invocation, section 8).

### F6: current-cut probes (`G3F6CurrentCutProbeTest`), probes only, no qualification claim

PR78 pure-cut saturation temperatures on `net` (K), beside the D5 bias recorded in P0 section 6.1 (not recomputed):

| Cut | NBP (record) | PR Tsat(101.325 kPa) - NBP | PR Tsat 1 / 5 / 13 kPa | D5 PR - MB 1 / 5 / 13 kPa | Implied MB Tsat 13 kPa | PR Tsat 250 kPa | PR - MB 250 kPa |
|---|---|---|---|---|---|---|---|
| crude_pc01 | 331.4 | +0.001 | 233.8 / 259.6 / 278.4 | -1.6 / -0.8 / -0.3 | 278.7 | 362.9 | -0.06 |
| crude_pc03 | 431.8 | +0.002 | 308.8 / 341.4 / 365.0 | -3.1 / -2.1 / -1.5 | 366.5 | 471.3 | +1.04 |
| crude_pc06 | 577.9 | +0.000 | 422.0 / 463.5 / 493.5 | -6.9 / -5.4 / -4.2 | 497.7 | 627.5 | +3.47 |
| crude_pc09 | 766.0 | +0.000 | 578.1 / 628.7 / 664.9 | -11.9 / -9.9 / -7.9 | 672.8 | 824.6 | +6.63 |
| crude_pc12 | 1095.1 | +0.000 | 889.1 / 946.2 / 986.3 | -12.9 / -11.7 / -9.8 | 996.1 | 1156.1 | +8.56 |

(all twelve cuts in the test output). Process flashes of the Tia Juana light assay through the network (vapour fraction,
engine feed verdict = phase count on every probe):

| Probe | Feed | T K | P kPa | Vapour fraction | K-values PC06..PC12 |
|---|---|---|---|---|---|
| VDU Case A | PC06-PC12 | 655.15 | 13.1 | 0.766 | 26.0 12.1 4.19 0.798 0.0477 0.00117 9.3e-7 |
| VDU deep cut | PC06-PC12 | 673 | 4.0 | 0.895 | 110 53.5 19.6 4.11 0.290 0.00869 9.6e-6 |
| CDU flash zone | whole assay | 638.15 | 250 | 0.799 | 1.17 0.535 0.181 0.0329 0.00183 4.1e-5 2.8e-8 |
| Coking drum feed | whole assay | 739 | 200 | 0.944 | 4.59 2.61 1.19 0.352 0.0456 0.00313 1.8e-5 |
| Coking drum | whole assay | 761 | 340 | 0.940 | 3.36 2.00 0.980 0.324 0.0507 0.00448 4.1e-5 |
| Coking heater outlet | whole assay | 783 | 520 | 0.939 | 2.70 1.69 0.883 0.324 0.0610 0.00686 9.9e-5 |

The VDU K-values carry the D5 bias (PR/MB vapour pressure 1.13-1.24 at 13 kPa for PC06-PC12, K 13-24 % high).
Hydrotreating: `net` refuses the assay at 623 K and 9.1 MPa ("crude_pc01 at 9100000 Pa is above its valid range
100..2000000 Pa"); on an open research contract the assay is one liquid-like fluid (feed STABLE) at 589, 623, 663, 713 K
and 6.1 and 9.1 MPa (no H2 until P7). Cut and light-end liquids at 300 K compress by 3.58 % (n-butane) down to 0.09 %
(PC12) from 0.1 to 10 MPa with a liquid-like root at every pressure (not qualified above 2 MPa; no reference above the
NIST 1.1 MPa rows of `LiquidCompressionQualificationTest`).

### F7: CO2 in liquid methane (`G3F7CarbonDioxideInLiquidMethaneTest`), research-only

30 fluid states at 91, 95, 100, 110, 120 K x 0.5, 2 MPa x x_CO2 1e-4, 1e-3, 1e-2 on an open research contract over the
pilot evaluator: every answer converged and research-only, fluid-only (solids not assessed). The production paths refuse
every state typed: the pilot network and the pilot contract name CarbonDioxide below 216.592 K. CO2's ideal gas there is
the spine's extended alpha0 segment, graded estimated-with-declared-error. One-phase examples (2 MPa, x_CO2 1e-3):
91 K rho 461.1 kg/m3, ln phi_CO2 -10.096, f_CO2 0.083 Pa; 120 K rho 422.0 kg/m3, ln phi_CO2 -5.734, f_CO2 6.47 Pa. At
x_CO2 1e-2 and 91-100 K the fluid-only model splits off a subcooled CO2-rich liquid (x_CO2 0.994-0.997, about 1480 kg/m3)
beside the methane liquid (x_CO2 0.0033 at 91 K, 0.0049 at 95 K, 0.0076 at 100 K): a fluid-only miscibility limit, not
the solubility (solid CO2 is the stable phase there, P5). The engine labels that liquid-liquid split `VAPOR_LIQUID` with
the methane-rich liquid (462 kg/m3) as `VAPOR` (the network's lighter-phase slot rule).

### F8: conservation and derivatives (`G3F8ConservationAndDerivativeTest`)

- TP, PH and UV round trips on the pilot engine over 190 TP answers (6 feeds, binary and quaternary, 100-600 K, 0.1-8 MPa;
  18 two-phase): worst conservation defect **1.85e-16** (plan 2e-16: met), T and P recovered to 9.6e-12 and 2.9e-11 (gate
  1e-9); on average 4.5 TP calls per PH and 2.8 per UV.
- Analytic derivatives of the pilot evaluator (E-PPR78 kij(T), spines, translations) against central differences:
  `d ln phi/dT`, `d ln phi/dP`, `d ln phi/dn`, `dv/dT`, `dv/dP`, `cp` on 24 phases (12 states x both roots): worst
  **1.1e-7** of scale (plan 1e-6: met). A step of 1e-4 T leaves 5e-6 on a CO2-rich liquid near its spinodal (truncation);
  the test uses 1e-5.
- Closed CH4/C2H6 0.5/0.5 vessels heated at fixed volume on the engine's UV (120 J per step): the liquid-rich vessel
  (1.074e-4 m3/mol) crosses to one phase at 263.28 K, 6.947 MPa (near the mixture critical density: the vapour fraction
  rises to 0.249 and falls to 0.168 before the phases merge), the vapour-rich one (5.0e-2 m3/mol) at its dew point 154.40 K,
  0.025 MPa; conservation defect 0, specification residual <= 3.1e-10, T and P monotone. **Finding:** the liquid-full
  vessel reaches the 10 MPa envelope at 282.2 K, and the next UV request (its pressure would exceed 10 MPa) comes back
  `NOT_CONVERGED` "the specified internal energy lies in a step of U(T) at the specified volume" instead of
  `OUT_OF_DOMAIN` in pressure (engine owner, section 7).
- A closed N2 vessel heated from 300 K, 0.2 MPa to 1142.6 K, 0.764 MPa at fixed volume through the network's own
  `InventoryEquilibrium` (40 steps): moles exact (0.0), energy residual at most 4.7e-3 of the gate 1e-4 J + 1e-6 |U|,
  the engine's UV at the same (U, V) within 3.5e-10 in T: the network gates **met** (independent of the WP7c slot label).
- The closed liquid-nitrogen compression 0.5-8 MPa at 90 K is WP4's `DirectLiquidContinuityTest` (run in the G3 Gradle
  invocation, section 8).

### F9: refusal just outside each new boundary (`G3F9DomainRefusalTest`)

Every refusal is a typed `ThermoDomainViolation` (network paths) or `OUT_OF_DOMAIN`/`UNSUPPORTED` (engine contract) naming
the component; the state just inside is answered.

| Boundary | Path | Just outside | Refusal |
|---|---|---|---|
| 10 MPa | pilot network (N2, CO2), bundled network (N2, CH4) | 10.05 MPa | the component, PRESSURE_ABOVE |
| 2 MPa kept for ethane | bundled network | 350 K, 2.01 MPa | Ethane, PRESSURE_ABOVE |
| 63.151 K | pilot and bundled network | 63.1 K | Nitrogen, TEMPERATURE_BELOW |
| 90.694 K, 90.368 K | pilot network | 90.69 K, 90.36 K | Methane, Ethane, TEMPERATURE_BELOW |
| 216.592 K, CO2 in net-style use | pilot network, pilot engine contract | 216.5 K | CarbonDioxide, TEMPERATURE_BELOW / OUT_OF_DOMAIN |
| 293.15 K, CH4 | bundled network | 293.1 K | Methane, TEMPERATURE_BELOW |
| 1200 K (pilot envelope, methane's ideal gas, D13) | pilot network; pilot evaluator (methane spine) | 1200.5 K | Nitrogen or Methane, TEMPERATURE_ABOVE (N2's spine evaluates at 1500 K; only the envelope refuses it) |
| 900 K | bundled network | 900.5 K | Nitrogen, TEMPERATURE_ABOVE |
| Spine floors | pilot evaluator | CH4 90.69 K, CO2 89.9 K | Methane, CarbonDioxide, TEMPERATURE_BELOW |
| Water 273.16 K | pilot and bundled network | 273.1 K | Water, TEMPERATURE_BELOW |
| Region 1 623.15 K | `waterLiquid` | 623.2 K, 20 MPa | Water, TEMPERATURE_ABOVE |
| CO2-I crystal (research-only record) | pilot engine contract | competition with CO2-I | UNSUPPORTED, PHASE_COMPETITION_NOT_QUALIFIED |
| Hydrates (D8) | pilot engine contract | competition with hydrates | UNSUPPORTED, WATER_CHEMISTRY_NOT_MODELLED |
| 10 MPa | pilot engine contract | 300 K, 10.05 MPa | OUT_OF_DOMAIN, Nitrogen, PRESSURE_ABOVE |

### F10: the old qualified region (`G3F10QualifiedRegionTest`)

P0 section 2.3 baseline saturation states at the D7 targets (bundled network inside its previous 2 MPa domain; pilot):

| Package | Species | T K | Psat % | sat. liquid rho % | liquid cp % | hvap % | D7 |
|---|---|---|---|---|---|---|---|
| net | N2 | 63.151 / 77.36 / 100 / 110 | +4.48 / +1.21 / +0.30 / +0.49 | -0.88 / -0.07 / -1.16 / -3.58 | -8.24 / -5.08 / +4.48 / +12.41 | -1.85 / -0.80 / -0.13 / -1.25 | all met |
| pilot | N2 | 63.151 / 77.36 / 100 / 110 / 120 | +4.48 / +1.21 / +0.30 / +0.49 / +0.54 | +0.80 / +1.52 / +0.17 / -2.44 / -8.17 | -8.24 / -5.08 / +4.48 / +12.41 / +29.36 | -1.85 / ... / -6.46 | met (120 K cp declared) |
| pilot | CH4 | 90.7 / 111.67 / 150 / 170 (185 K in box 1) | +1.42 / +0.74 / +0.68 / +0.83 | +1.79 / +2.26 / +0.33 / -3.84 | -1.25 / -0.24 / +6.64 / +16.68 | <= 2.22 | met |
| pilot | C2H6 | 90.4 / 120 / 184.55 / 250 / 280 | +28.71 / +10.20 (declared) / +0.75 / +0.30 / +0.63 | -1.93 / -0.22 / +1.86 / -0.56 / -5.76 | -10.62 (NOT met, 12 % held) / -7.73 / -3.84 / +6.03 / +18.00 | <= 3.11 | met except cp at 90.4 K |
| pilot | CO2 | 216.6 / 250 / 270 / 290 | -0.37 / -0.79 / -0.31 / +0.21 | +1.09 / -0.57 / -3.40 / -9.26 | -8.92 / +1.40 / +10.98 / +29.32 | <= 3.37 | met (290 K cp declared) |

The bundled network's gas inside its previous domain (0.1-2 MPa): N2 150-900 K (28 states) density worst +1.47 % (150 K
2 MPa; D7 1 % not met, D14 2.5 % met), cp -0.93 % (D7 2 % met); CH4 293.15-600 K (16 states) density +0.64 %, cp -0.48 %
(met). Its `shifted_polynomial_5` ideal gas against the pilot spines at 300 / 600 / 900 K: N2 -0.00 / -0.01 / +0.00 %,
CH4 -0.29 / -0.49 / -1.18 % (the D6 methane correction deferred to P8, declared). The ideal-gas states of P0 section 2.3
at 1073-1200 K lie above the bundled network's 900 K (refused, F9).

Label check `hotGasOfTheOldDomainTakesTheVapourSlot`: **met at `f2d5221`** (63 of 63). At `8e5274e` it was a regression
of the old qualified region (the retired root heuristic put these states in the vapour slot): N2 at 650-900 K and
0.1-2 MPa and N2/CH4 0.5/0.5 at 800-900 K were in the liquid slot (16 of 63 states).

The existing gates of this region (`FluidPropertyCoverageTest`, `FluidNitrogenCryogenicTest`,
`LiquidCompressionQualificationTest`) and WP5's bitwise `LegacyNetworkPathPinTest` run in the G3 Gradle invocation
(section 8); chain-100 (re-captured at WP11), the fluid GameTests and the column suite are WP11's.

## 3. The hot-gas label (WP7c)

Two label tests, `G3F3HighTemperatureIdealGasTest.coilOutletGasTakesTheVapourSlot` and
`G3F10QualifiedRegionTest.hotGasOfTheOldDomainTakesTheVapourSlot`, assert the correct physics (a hot dilute gas is
vapour-like and takes the network's vapour slot). They failed on the classes of `8e5274e` (24 of 45 and 16 of 63 states in
the liquid slot; standalone run `tools/g3-qualification/out/standalone-8e5274e-classes.txt`) and pass at `f2d5221` (WP7c:
a one-root state is liquid-like when PIP > 1 and PIP > Z). The only other change of any printed number between the two
trees is the N2 vessel's engine-UV agreement (3.5e-10 to 4.0e-10 in T).

## 4. Holdouts used

| Family | Holdout (not used in fitting) |
|---|---|
| F1, F4, F5, F10 | Java Helmholtz oracle (CoolProp `ae81610e` reference equations: Span et al. 2000 N2, Setzmann and Wagner 1991 CH4, Buecker and Wagner 2006 C2H6, Span and Wagner 1996 CO2); only the Tr 0.8 saturated liquid is fitted (the anchor) |
| F2 | GERG-2008 (CoolProp 8.0.0 mixing on the reference pure fluids), WP8 fixture `pilot-binaries.json`; model against model, declared |
| F3 | NIST-JANAF N-023, C-095 (Chase 1998); Gurvich ethane Cp (NIST WebBook); ExoMol MM line-list methane reference (WP10) |
| F6 | P0 section 6.1 Maxwell-Bonnell comparison (recorded bias, D5) |
| F8 | internal consistency (central differences, round trips, conservation) |

## 5. G3 criteria (plan section 8.2)

| # | Criterion | Verdict |
|---|---|---|
| 1 | F1 meets its targets outside the declared exclusions | **Not met** on five rows: vapour cp at Z >= 0.9 beyond D14 (to -11.58 % saturated, -8.45 % away from saturation), compressed liquid Tr 0.85-0.9 (-3.34 %), supercritical cp next to box 1 (-10.59 %), ethane liquid cp below 150 K (-10.63 %). Proposals in section 6 |
| 2 | F2 meets the K-value row | **Not met** on |dy| for CO2/CH4 and CO2/C2H6 at 220 K (0.0222, 0.0212 against 0.02); every other criterion and pair met. Proposal in section 6 |
| 3 | F3 meets its targets | **Met** (Cp 0.133 %, h 9.6 J/mol, S 0.0088, h^R 0.024 %; the hot gas in the vapour slot since WP7c) |
| 4 | F4 meets its targets | **Met** with the D14 bounds and the compressed-liquid proposal of criterion 1; no jump at 2 MPa |
| 5 | F5: typed research-only inside, no NOT_CONVERGED outside the band, derivatives present or refused | **Not met**: one untyped `NOT_CONVERGED` outside the band (N2/CH4 150 K 4.69 MPa); the D14 corner not graded research-only by the engine; the band gap below box 1 (-9.06 %) has no row |
| 6 | F6 reported | **Declared** (probes recorded, no claim) |
| 7 | F7 reported | **Declared** (research-only states recorded) |
| 8 | F8 conservation 2e-16 and derivatives 1e-6 | **Met** (1.85e-16, 1.1e-7; network gates met); the UV refusal at the 10 MPa edge is mis-typed (finding) |
| 9 | F9 typed refusals at every new boundary | **Met** |
| 10 | F10 existing gates and D7 at the old region | **Met** at the D7 targets (ethane cp at 90.4 K as criterion 1); the existing gates green (section 8); the hot-gas slot regression of `8e5274e` fixed by WP7c |
| 11 | Domain table filled with exact achieved ranges and exclusions | **Met** (section 1) |
| 12 | Cost measurements of section 10 within budget | **Declared**, not measured by WP9b: WP6a measured the stability test and the TP p95 (46.2 us at 21 components, budget 50 us); chain-100 wall/allocation and the benchmark pair are WP11's |

## 6. Proposals for the lead (a D15 entry; none is applied as a revision here)

Each row is held in the tests at the value below and labelled "G3 proposal", with the D7 (and D14) verdict printed
beside it.

| Row | D7 / D14 | Measured worst | Proposal | Evidence |
|---|---|---|---|---|
| Real-gas vapour cp, Z >= 0.9 | 2 % / 10 % near saturation, 2 % away | -11.58 % (CO2 saturated vapour 224 K), -8.45 % away (CO2 1 MPa 249 K) | 12 % declared at Z >= 0.9; the 2 % target only at Z >= 0.98 (measured <= 1.84 % off saturation, <= 2.32 % on it) | F1 vapour cp by Z table: the error scales with 1 - Z, not with the distance from saturation |
| Compressed liquid density Tr 0.85-0.9 | 3 % (the D7 saturated-liquid row declares 10 % at 0.85-0.95) | -4.41 % (C2H6 274.8 K, 1.02 Psat) | 5 % declared | F1 grid, F4 isotherms; only next to saturation |
| Supercritical cp next to box 1 | 10 % | -10.59 % (N2 139.92 K 5 MPa, Tr 1.109, Pr 1.47) | 12 % declared (or box 1 to Tr 1.12) | F1 grid |
| Liquid cp, ethane below 150 K | 10 % | -10.63 % (triple point) | declared with D3's ethane Psat region (12 %) | F1, F10; P1 section 1 already reported -10.6 % with polished roots |
| Liquid at Tr 0.95-0.97 below Pr 0.8 (between boxes 1 and 2) | no row | -9.06 % (N2 121.19 K 2.70 MPa) | widen box 2 to Tr 0.97 (research-only) or declare 10 % | F5 N2 Tc-5 K path |
| K-value |dy|, CO2 pairs at 220 K | 0.02 | 0.0222 (CO2/CH4), 0.0212 (CO2/C2H6), x_CO2 0.9-0.95 | 0.025 declared for CO2-rich liquids within 5 K of CO2's triple point | F2 |

## 7. Known limits and open items

For the engine owner (WP7b/WP7c, WP11):

1. **Hot dilute gas in the liquid slot**: fixed by WP7c (`f2d5221`); the F3 and F10 label tests now guard it.
2. **Untyped `NOT_CONVERGED` outside the band**: N2/CH4 0.67/0.33 at 150 K and 4.69 MPa ("the feed's stability test is
   unresolved"). The network refuses it with a plain exception, not a `CriticalBandHold`. Either type the unresolved
   stability test near a mixture critical point as `CRITICAL_BAND` or resolve it (the Newton finish from the Wilson seed).
3. **UV at the pressure ceiling**: a closed vessel whose pressure would pass 10 MPa gets `NOT_CONVERGED` ("a step of U(T)")
   instead of `OUT_OF_DOMAIN` in pressure.
4. **D14 corner not in the engine's band**: `FluidTpEquilibrium`'s pure band has boxes 1-3; D14 puts Tr 1.0-1.1 x Pr 1.5-2
   into the band (research-only). The engine grades those states estimated-with-declared-error.
5. **Liquid-liquid split labelled VAPOR_LIQUID** (F7): a CO2-rich liquid beside liquid methane is reported with the methane
   liquid as `VAPOR`. Harmless while CO2 is refused in production below 216.592 K; P5 needs a liquid-liquid label.

For WP11: copy `tools/g3-qualification/` to the main checkout with a
`tools/INDEX.md` row; the D15 entry from section 6; chain-100 re-capture, the fluid GameTests and the cost pair (not G3
fixtures).

For P4/P5/P6: F7's fluid states and the CO2 fugacity coefficients are the fluid side of the P5 solid-liquid condition (the
GERG-2008 comparison of CO2 in liquid methane below 216.6 K was not done: no fixture); the demethanizer top of pure
methane lies in box 2 (P6 gameplay); dense-fluid transport above 2 MPa stays unqualified (G6 question, plan section 10);
the n-butane/n-pentane/n-decane liquid isotherms to 10 MPa (plan F6) need a reference above 1.1 MPa (NIST or the CoolProp
files of those fluids, not bundled); VDU cut K-values keep the D5 bias (VDU-WP0).

Limits of the fixtures: F1 and F4 use the oracle's critical constants for reduced variables (the engine uses the record
constants; ethane differs by 2 mK and 0.2 kPa); the F2 bubble solver starts from the GERG state (a start, not a result:
`sum x K = 1` to 1e-12 with distinct phases), and failed on 3 flagged and 1 dew row next to critical ends; the F6 VDU bias
is recorded from P0, not recomputed; the melting line of the reference files decides the solid-stable drops.

## 8. Gradle run

From Git Bash, 2026-09-25 04:37 (+0800), on `f2d5221` (WP7c committed; the working tree clean apart from the new
qualification package), no dev client running (no `runMcpClient` or `fml.modFolders` command line), under
`build/gradle.lock` with the tag `wp9b` (script `tools/g3-qualification/gradle-run.sh`, which deletes the lock on failure
too):

    JAVA_OPTS=-Xshare:off ./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.qualification.*' \
      --tests 'com.wormzjl.createcheme.science.fluid.network.NearCriticalNitrogenIslandTest' \
      --tests 'com.wormzjl.createcheme.science.fluid.thermo.FluidPropertyCoverageTest' \
      --tests 'com.wormzjl.createcheme.science.fluid.thermo.FluidNitrogenCryogenicTest' \
      --tests 'com.wormzjl.createcheme.science.fluid.thermo.LiquidCompressionQualificationTest' \
      --tests 'com.wormzjl.createcheme.science.fluid.thermo.LegacyNetworkPathPinTest' \
      --tests 'com.wormzjl.createcheme.science.fluid.thermo.DirectLiquidContinuityTest' --offline

**BUILD SUCCESSFUL, 46 tests, 0 failures, 0 errors, 0 skipped** (`verifyMaterialIndex` passed):

| Class | Tests | Result |
|---|---|---|
| `G3F1PureFluidOracleTest` | 2 | green |
| `G3F2GergBubblePointTest` | 1 | green (the two recorded dy misses held at the G3 proposal) |
| `G3F3HighTemperatureIdealGasTest` | 4 | green (hot-gas label since WP7c) |
| `G3F4HighPressureContinuityTest` | 2 | green |
| `G3F5CriticalRegionPathTest` | 1 | green (the one known untyped state reported NOT MET) |
| `G3F6CurrentCutProbeTest` | 4 | green |
| `G3F7CarbonDioxideInLiquidMethaneTest` | 1 | green |
| `G3F8ConservationAndDerivativeTest` | 4 | green (the UV edge finding printed) |
| `G3F9DomainRefusalTest` | 1 | green |
| `G3F10QualifiedRegionTest` | 3 | green (hot-gas label since WP7c) |
| Existing gates: `NearCriticalNitrogenIslandTest` 5, `DirectLiquidContinuityTest` 4, `FluidNitrogenCryogenicTest` 8, `FluidPropertyCoverageTest` 4, `LegacyNetworkPathPinTest` 1, `LiquidCompressionQualificationTest` 1 | 23 | green |

The 30 qualification tests take under 1 s together. An earlier invocation at 04:36 on the same commit (the test sources
already compiled by the WP7c agent's run) gave the same 46 green; the final run recompiled the tests after the WP7c wording
edits. The printed tables of the final run are in `tools/g3-qualification/out/gradle-f2d5221-system-out.txt`, the log in
`out/gradle-20260925-043710.log`. Before WP7c the families were iterated with javac against the `8e5274e` classes
(`tools/g3-qualification/run.sh`, output `out/standalone-8e5274e-classes.txt`): 21 of 23 methods passed, the two hot-gas
label tests failed (section 3); every other printed number is the same on both trees except the N2 vessel's engine-UV
agreement (3.5e-10 and 4.0e-10).

## 9. Fixture and tool inventory

| Path | Tracked | Content |
|---|---|---|
| `src/test/java/com/wormzjl/createcheme/science/thermo/qualification/G3Support.java` | yes | shared helpers: the pilot and bundled network models, pure-fluid evaluation on the network's EOS, model Psat and oracle Tsat solvers, band boxes and D14 corner, the density rule, reference melting lines, research-contract engines, the table printer |
| `.../G3F1PureFluidOracleTest.java` | yes | F1 (2 tests) |
| `.../G3F2GergBubblePointTest.java` | yes | F2 (1 test), reads `src/test/resources/science/thermo/gerg2008/pilot-binaries.json` (WP8) |
| `.../G3F3HighTemperatureIdealGasTest.java` | yes | F3 (4 tests; the hot-gas label guards the WP7c fix) |
| `.../G3F4HighPressureContinuityTest.java` | yes | F4 (2 tests) |
| `.../G3F5CriticalRegionPathTest.java` | yes | F5 (1 test, known-defect list of one state) |
| `.../G3F6CurrentCutProbeTest.java` | yes | F6 (4 tests, probes) |
| `.../G3F7CarbonDioxideInLiquidMethaneTest.java` | yes | F7 (1 test, research-only) |
| `.../G3F8ConservationAndDerivativeTest.java` | yes | F8 (4 tests) |
| `.../G3F9DomainRefusalTest.java` | yes | F9 (1 test) |
| `.../G3F10QualifiedRegionTest.java` | yes | F10 (3 tests; the hot-gas label guards the WP7c fix) |
| `tools/g3-qualification/` | no (git-ignored) | `run.sh` and `RunTests.java` (javac + reflective runner for iteration without Gradle), `out/` (standalone outputs and Gradle logs), README |

No new test resource was needed (the JANAF, Gurvich and line-list holdouts are test constants with their sources). The
fixtures are gate tests (they stay); nothing measurement-only is in a tracked path.

## 10. Commit

Commit `4eec712` on `f2d5221` (WP7c): the eleven test sources of `src/test/java/com/wormzjl/createcheme/science/thermo/qualification/`, added by explicit path; nothing else is tracked by this work package (the report, `tools/g3-qualification/` and its outputs are git-ignored). Not pushed; no INDEX or CHANGELOG edit (WP11). The commit carries the session's actual model line (Claude Opus 5.5) as co-author rather than the brief's Fable 5.1 line, as WP5 and WP9a did. The WP7c wording in the F3, F5 and F10 Javadoc was edited before the final Gradle run, so the run of section 8 compiled exactly the committed sources.

## 11. WP7d re-run (2026-09-25, `774cb82`)

WP7d (`P3_EQUILIBRIUM_ENGINE.md` section "WP7d") fixed the four engine defects of section 7 (items 2 to 5), found and
fixed a fifth (a reused engine workspace carried amounts of absent components), encoded D14's corner and D15's widened
box 2 in the engine's band, and replaced every "G3 proposal" label of the fixtures by the D15 bound with the D7 and D14
verdicts printed beside it (no held number changed). The ten families were re-run in the Gradle invocation
`test --tests 'com.wormzjl.createcheme.science.thermo.*'` (126 tests, 0 failures, 2 skipped; the families 23 of 23),
with `fluidScienceTest` 217 (1 skipped) and `fluidRuntimeTest` 227 green, on exactly the committed content. Printed
output: `tools/wp7d-engine-defects/out/gradle-774cb82-g3-system-out.txt`.

### 11.1 F1 after WP7d

Every row and number of section 2 F1 is unchanged except where box 2's widening now takes states out of the scored set:
the saturation sweep has 12 band states (11 before), the isobar grid 540 scored states with band boxes 1/2/3 at
20/10/14 (542 and 20/8/14 before). No worst value moved. The held bounds are D7's, D14's or D15's; the D15 rows:

| Row | Worst | D7 / D14 verdict | Held (D15) |
|---|---|---|---|
| Vapour cp Z >= 0.9, within 0.05 Tr of saturation | -11.58 (CO2 224.07 K saturated vapour, Z 0.900) | 2 % NOT met / 10 % NOT met | 12 % declared at Z >= 0.9: met |
| Vapour cp Z >= 0.9, beyond 0.05 Tr | -8.45 (CO2 248.84 K 1 MPa, Z 0.902) | 2 % NOT met / 2 % NOT met | 12 % declared: met |
| Vapour cp Z >= 0.98 (the kept 2 % target, reported per Z band) | grid -1.84 (CO2 216.59 K 0.1 MPa); saturated vapour -2.32 (CH4 100.04 K), -2.10 (N2 69.02 K) | - | 2 % target met on the grid, missed by 0.3 and 0.1 points on two saturated vapours; inside the 12 % declared |
| Compressed liquid density Tr 0.85-0.9 | -3.34 (N2 113.05 K 2 MPa); F4 -4.41 (C2H6 274.79 K, 1.02 Psat) | 3 % NOT met | 5 % declared next to saturation: met |
| Supercritical cp | -10.59 (N2 139.92 K 5 MPa, Tr 1.109, Pr 1.47) | 10 % NOT met | 12 % declared next to box 1: met |
| Liquid cp, ethane below 150 K | -10.63 (C2H6 90.37 K) | 10 % NOT met | 12 % declared with D3: met |
| Liquid at Tr 0.95-0.97 below Pr 0.8 | -9.06 (N2 121.19 K 2.70 MPa), no longer scored | no row | in box 2 since D15 (research-only) |

### 11.2 F2 after WP7d

Unchanged numbers (the fixture's own bubble solver on the pilot kernel). E-PPR78 meets D7 on every pair except |dy| of
CO2/CH4 (0.0222) and CO2/C2H6 (0.0212), both at 220 K and x_CO2 0.90 to 0.95, inside D15's scope (CO2-rich liquids
within 5 K of 216.592 K) and within its 0.025 declared. Outside that scope the same pairs meet D7's 0.02: CO2/CH4 9 clean
rows, worst |dy| 0.0082 (235 K x1 0.95); CO2/C2H6 36 rows, worst 0.0139 (220 K x1 0.10). The held column reads "D7, dy
0.025 (D15 declared)".

### 11.3 F5 after WP7d

| Path | States | Converged (in band) | Typed CRITICAL_BAND | NOT_CONVERGED outside | Derivatives present / refused | D14 corner states (in engine band) | Scored density, worst % |
|---|---|---|---|---|---|---|---|
| N2 Tc-5 K, 2-10 MPa | 161 | 161 (62) | 0 | 0 | 161 / 0 | 0 | -3.26 (121.19 K 5.10 MPa, liquid Pr > 1.5); the -9.06 % state now in box 2 |
| N2 Tc-2 K | 161 | 161 (47) | 0 | 0 | 161 / 0 | 0 | -4.56 (124.19 K 5.10 MPa) |
| N2 Tc | 161 | 161 (81) | 0 | 0 | 161 / 0 | 34 (34) | -3.16 (126.19 K 6.80 MPa, supercritical) |
| N2 Tc+2 K | 161 | 161 (81) | 0 | 0 | 161 / 0 | 34 (34) | -3.79 (128.19 K 6.80 MPa) |
| N2 Tc+5 K | 161 | 161 (81) | 0 | 0 | 161 / 0 | 34 (34) | -4.77 (131.19 K 6.80 MPa) |
| CO2 Tc-5 ... Tc+5 K | 5 x 161 | 805 (410) | 0 | 0 | 805 / 0 | 0 | +1.70 (309.13 K 3.90 MPa, vapour) |
| N2/CH4 0.67/0.33, 150 K, 3-5.5 MPa | 251 | 251 (47) | 0 | **0** | 339 / 0 | - | - |

The corner's 34 states per isotherm above Tc are graded research-only by the engine (the "Scored density" column now
scores only states outside the engine's band, so the corner's -8.02 % of section 2 is no longer the worst scored value;
F1 and F4 still score the corner at D14's 9 %). N2/CH4 at 150 K and 4.69 MPa is a converged single phase (the stability
test repeated on ten times the budget; a brute-force tangent-plane check confirms one phase), the known-defect list is
empty, and WP9b's 3,146-state finer scan has no `NOT_CONVERGED`. `NearCriticalNitrogenIslandTest` (5 green): only typed
holds inside the band (none occurred); the supercritical charge now crosses the corner inside the band.

F7: its six fluid-only splits are `LIQUID_LIQUID` with both phases `LIQUID` (asserted). F8: the liquid-full vessel's
first request past 10 MPa is `OUT_OF_DOMAIN` naming the 1e7 Pa ceiling (asserted). F9: one more row, the closed CH4/C2H6
vessel whose (U, V) lies above 10 MPa, `OUT_OF_DOMAIN`, Methane, PRESSURE_ABOVE after 51 TP equilibria (the one at
9.95 MPa answered).

### 11.4 The G3 checklist re-stated (plan section 8.2, D15)

| # | Criterion | Verdict after WP7d |
|---|---|---|
| 1 | F1 meets its targets outside the declared exclusions | **Met**, with D15's declared rows (vapour cp 12 % at Z >= 0.9, compressed liquid Tr 0.85-0.9 5 %, supercritical cp next to box 1 12 %, ethane liquid cp below 150 K 12 %) and box 2's widening; the 2 % vapour cp target at Z >= 0.98 is reported (missed on two saturated vapours by at most 0.32 points, inside the declared 12 %) |
| 2 | F2 meets the K-value row | **Met**, with D15's |dy| 0.025 declared for the CO2 pairs within 5 K of CO2's triple point; D7 met outside that scope |
| 3 | F3 meets its targets | **Met** |
| 4 | F4 meets its targets | **Met** (D14 and D15 bounds; no jump at 2 MPa) |
| 5 | F5: typed research-only inside, no NOT_CONVERGED outside the band, derivatives present or refused | **Met**: no `NOT_CONVERGED` inside or outside the band (the defect fixed), the D14 corner research-only in the engine's band, the band gap closed by D15, derivatives present on every state |
| 6 | F6 reported | **Declared** (probes, no claim) |
| 7 | F7 reported | **Declared** (research-only; the splits now `LIQUID_LIQUID`) |
| 8 | F8 conservation 2e-16 and derivatives 1e-6 | **Met** (1.85e-16, 1.1e-7; the UV refusal at the 10 MPa ceiling typed) |
| 9 | F9 typed refusals at every new boundary | **Met** (with the closed-vessel UV row) |
| 10 | F10 existing gates and D7 at the old region | **Met** (ethane cp at 90.4 K under D15's 12 %) |
| 11 | Domain table filled | **Met** (section 1; the band there now includes the corner in the engine, and box 2 reaches Tr 0.97 below Pr 0.8) |
| 12 | Cost measurements within budget | **Declared** (WP11's cost pair; the WP7d retry only runs where the first stability test is unresolved) |

D15's condition for declaring G3 met ("WP7d's re-run of F1 to F10 is green at the D15 bounds and the four defects are
typed or fixed") is satisfied: four defects fixed (none merely typed), F1 to F10 green. The declaration is the lead's.

Left open (engine note WP7d.5): `LIQUID_LIQUID` also labels high-pressure splits whose lighter phase is liquid-like by a
small margin (N2/C2H6 at 150 to 275 K and 8 to 10 MPa, CH4/CO2 at 245 K and 8 MPa), which the network refuses; one
three-phase state of N2/C2H6 (125 K, 3 MPa, x_N2 0.95) stays an untyped `NOT_CONVERGED` outside the families.

Resolved in WP11 (2026-09-25, `a3716ed`, `P3_PILOT_ENGINE_REVIEW.md` section 2): the dense-gas splits are vapour-liquid under the `v < 3.9514 b` guard; liquid-liquid answers and the three-phase indication (`UNSTABLE_PRODUCT`) hold the island typed. The three-phase state at x_N2 exactly 0.95 is inside the band's pure-fluid rule (the survey's grid reached 0.9499999999999998); outside the band the failure is at x_N2 0.93 to 0.9499. F1 to F10 re-ran green in the WP11 gates.

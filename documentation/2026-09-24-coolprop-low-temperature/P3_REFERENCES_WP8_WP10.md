# P3 WP8 and WP10: GERG-2008 bubble-point references and the methane ideal-gas source

Status: WP8 done, fixture committed (`eb1ed10`, not pushed); WP10 done, decision D13 proposed (not recorded in the decision log; the lead records it). Written 2026-09-24.
Batch `2026-09-24-coolprop-low-temperature`, stage P3 (`P3_PILOT_ENGINE_PLAN.md` sections 4.5, 5, 8.2, 9 rows WP8 and WP10, 11, appendices A and B; decisions D6 and D7 of `DECISION_LOG.md`).
Base: worktree `claude/coolprop-multiphase-thermo-37f6b0`, started at `5100233`; the fixture commit sits on `50dfe31` (other agents' P3 commits in between). Offline work: no Gradle run, no Java change; the only tracked file added is the fixture.

Files of this work:

| Path | Tracked | Content |
|---|---|---|
| `src/test/resources/science/thermo/gerg2008/pilot-binaries.json` | yes, `eb1ed10` | WP8 fixture: 171 bubble and 190 dew points, failures with reasons, parameter comparison (118 295 bytes, sha256 `61d8b234969e77195964d6cf862aacb439537dfaf269ec39798a345639d21548`) |
| `tools/gerg-bubble-points/` | no | Builder script and README (schema, reproduction) |
| `research/2026-09-24-coolprop-low-temperature/gerg-bubble-points/` | no | GERG TM15 monograph (uncertainty statements), README with sha256 |
| `tools/methane-ideal-gas/` | no | WP10 study script and README |
| `research/2026-09-24-coolprop-low-temperature/methane-ideal-gas/` | no | ExoMol, HITRAN, JANAF and literature downloads with sha256 and licences; outputs; README |

---

## Part A. WP8: GERG-2008 bubble points of the six pilot binaries

### A.1 Model

CoolProp 8.0.0 (`HEOS` backend, git revision `ae81610e7d23efc57f9d051c8e70a4d66e87537f`) with the GERG-2008 reducing functions and departure functions of `dev/mixtures` (Kunz and Wagner 2012), evaluated on CoolProp's reference pure-fluid equations (Span et al. 2000 N2, Setzmann and Wagner 1991 CH4, Buecker and Wagner 2006 C2H6, Span and Wagner 1996 CO2). This is "GERG-2008 mixing on reference pure fluids", not GERG-2008 itself: GERG-2008 uses its own shorter pure-fluid equations. The difference is in the pure-component vapour pressures and densities, which the reference equations reproduce better; it is not quantified here (no GERG-2008 pure-fluid saturation code is available offline; open item A.9). Against the D7 K-value row (10 % AAD) the effect is expected to be small.

The fixture is a model reference, compared model against model (plan section 8.2, F2): experimental VLE of these pairs is E-PPR78's own fitting data.

### A.2 Grid

x1 is the mole fraction of the first-named component of the pair (for the CO2 pairs, CO2), on {0.05, 0.1, 0.2, 0.3, 0.5, 0.7, 0.9, 0.95}. Temperatures start near Tr = 0.5 of the heavier component (at 220 K for the CO2 pairs, whose CO2 triple point is 216.59 K) and stop where the isotherm's two-phase range has shrunk to the critical region; for five pairs the upper isotherms end at their mixture critical point inside the grid (CO2/C2H6 stays below its critical line, about 291 K, and spans the whole grid), and the grid points beyond an isotherm's end are listed as absent, not dropped silently.

| Pair | Temperatures, K | Heavier component, Tr at the lowest T | Traced from |
|---|---|---|---|
| N2/CH4 | 95, 110, 125, 140, 155, 170 | CH4, 0.50 | pure CH4 |
| N2/C2H6 | 150, 180, 210, 240, 270 | C2H6, 0.49 | pure C2H6 |
| CH4/C2H6 | 150, 175, 200, 225, 250, 275 | C2H6, 0.49 | pure C2H6 |
| CO2/N2 | 220, 240, 260, 280, 290 | CO2, 0.72 (triple point) | pure CO2 |
| CO2/CH4 | 220, 235, 250, 265, 280 | CO2, 0.72 | pure CO2 |
| CO2/C2H6 | 220, 235, 250, 265, 280 | C2H6, 0.72 | pure C2H6 (CO2 is the more volatile below the azeotrope region) |

The plan's section 4.5 ranges (N2/CH4 95-185 K, N2/C2H6 120-280 K, CH4/C2H6 130-280 K, CO2 pairs 220-290 K) were narrowed at the low end to Tr 0.5 of the heavier component as briefed; that also keeps N2/C2H6 above its low-temperature liquid-liquid-vapour region (no liquid on the grid fails the spinodal test, A.4).

### A.3 Parameter comparison with `GERG2008.cpp` (the WP8 gate)

CoolProp's run-time values (`get_mixture_binary_pair_data`) against the literal assignments in `SetupGERG()` of `sources/nist-aga8/GERG2008.cpp` (usnistgov/AGA8 `3bdb9ab8`). When CoolProp stores a pair in the opposite order, beta is compared as 1/beta (beta_ji = 1/beta_ij; gamma and F are symmetric).

| Pair (GERG order) | CoolProp order, cited source | betaT | gammaT | betaV | gammaV | F | Departure function | Result |
|---|---|---|---|---|---|---|---|---|
| CH4-N2 | same, Kunz-JCED-2012 | 0.99809883 | 0.979273013 | 0.998721377 | 1.013950311 | 1 | "Methane-Nitrogen", 9 terms (2 power) | identical |
| CH4-CO2 | reversed, Kunz-JCED-2012 | 1.02262449 | 0.975665369 | 0.999518072 | 1.002806594 | 1 | "Methane-CarbonDioxide", 6 terms (3 power) | identical (1/beta to 2e-16) |
| CH4-C2H6 | same, Kunz-JCED-2012 | 0.996336508 | 1.049707697 | 0.997547866 | 1.006617867 | 1 | "Methane-Ethane", 12 terms (2 power) | identical |
| N2-CO2 | reversed, **Gernert-Thesis-2013** | 1.005894529 | 1.107654104 | 0.977794634 | 1.047578256 | 1 | "Nitrogen-CarbonDioxide", 6 terms (2 power) | identical in value: CoolProp stores 0.994140013 and 1.022709642, the reciprocals rounded to 9 decimals (1.4e-10 and 8.8e-11 relative) |
| N2-C2H6 | reversed, Kunz-JCED-2012 | 1.007671428 | 1.098650964 | 0.978880168 | 1.042352891 | 1 | "Nitrogen-Ethane", 6 terms (3 power) | identical (1/beta to 1e-16) |
| CO2-C2H6 | same, Kunz-JCED-2012 | 1.013871147 | 0.90094953 | 1.002525718 | 1.032876701 | 0 | none in either | identical |

Every departure-function coefficient (n, d, t, eta, epsilon, beta, gamma) of the five pairs that have one equals `GERG2008.cpp` exactly (0 relative difference), and `SetupGERG()` folds the exponent as `-c(delta - e)^2 - b(delta - g)`, CoolProp's `GERG-2008` departure form. The one label difference: CoolProp cites Gernert's 2013 thesis (EOS-CG) for CO2/N2, but the numbers it ships are GERG-2008's. The ten reducing parameters also occur in GERG Technical Monograph 15 (GERG-2004), so GERG-2008 kept the GERG-2004 binary equations of these pairs. The comparison is stored per pair in the fixture (`parameter_comparison`).

Result of the gate: **CoolProp's binary parameters and departure functions are identical to `GERG2008.cpp` for all six pilot pairs.**

### A.4 Method

- **Isothermal traces.** For each pair, temperature and kind (bubble: liquid composition given; dew: vapour composition given), a continuation in the mole fraction of the more volatile component from 0.01 to 0.99 in 0.01 steps plus every grid point, each point seeded by the previous one (`update_with_guesses`, `QT_INPUTS`); the first point uses CoolProp's own guess (dew traces fall back on the first bubble point of the isotherm and up to four later start points); a failed step is halved down to 1/64; the trace ends at the first composition it cannot reach. A first attempt with CoolProp's flash per grid point (Wilson guesses, and with a pre-built phase envelope) failed at many interior points and returned trivial solutions near critical; the traces fixed both. CoolProp's phase envelopes of the type III pairs (N2/C2H6, CO2/N2) run off into the liquid-liquid region and are not used.
- **Validation of every row.** Pressure and ln f of each phase recomputed from (T, rho, composition) with the phase imposed: agreement within 1e-6 required; achieved at most 1.3e-7 in pressure and 1.0e-7 in ln f (per pair in `validation_at_full_precision`). rhoL/rhoV >= 1.01 along the trace (no trivial solutions). A binary spinodal test of the liquid (d ln f1/d x1 > 0 at fixed T and P) flags an unstable liquid: none found.
- **Failure classes.** `beyond_isotherm_end`: a bubble trace ended with rhoL/rhoV < 1.5, at the isotherm's mixture critical point, so grid compositions beyond it have no bubble point; or a dew composition is richer in the light component than any equilibrium vapour on the bubble trace of that isotherm (plus 0.005). `coolprop_error`: the trace stopped where a point is expected. `invalid_solution`: reached but failed validation (none).
- **Flags.** `near_critical`: rhoL/rhoV < 3 (roughly pure-fluid Tr 0.95 to 0.96), the proposed exclusion "outside the mixture critical region" of the D7 row. `above_10_MPa`: above the pilot's pressure ceiling.
- Dew rows are on the lower-pressure (normal) dew branch; the retrograde upper branch is not traced.
- Output: 10 significant digits. Recomputing P from a row's rounded (T, rhoL, x1) reproduces it only to about 2e-5, because liquid pressure is stiff in density; the gate compares P, y and K, it does not re-derive P.

### A.5 Results

| Pair | Grid points | Bubble rows | of which near-critical / above 10 MPa | Clean bubble rows | Dew rows | Absent (beyond isotherm end), bubble + dew | CoolProp failures | P range of bubble rows, MPa |
|---|---|---|---|---|---|---|---|---|
| N2/CH4 | 48 | 39 | 3 / 0 | 36 | 39 | 9 + 9 | 0 | 0.072-4.86 |
| N2/C2H6 | 40 | 22 | 8 / 3 | 14 | 32 | 18 + 8 | 0 | 1.11-13.33 |
| CH4/C2H6 | 48 | 39 | 5 / 0 | 34 | 39 | 9 + 9 | 0 | 0.073-6.30 |
| CO2/N2 | 40 | 13 | 6 / 3 | 7 | 19 | 27 + 20 | 1 (dew) | 4.17-16.58 |
| CO2/CH4 | 40 | 18 | 5 / 0 | 13 | 21 | 22 + 19 | 0 | 1.85-8.57 |
| CO2/C2H6 | 40 | 40 | 0 / 0 | 40 | 40 | 0 + 0 | 0 | 0.55-4.66 |
| Total | 256 | 171 | 27 / 6 | 144 | 190 | 85 + 65 | 1 | |

The single CoolProp failure: CO2/N2 dew at 280 K, y_CO2 = 0.7 (y_N2 = 0.3), where the richest traced equilibrium vapour has y_N2 = 0.2996; it is 0.0004 beyond, inside the 0.005 margin, so it is recorded as a failure although the point probably does not exist.

Where the bubble isotherms end (last traced light-component fraction, pressure, rhoL/rhoV): N2/CH4 140 K at x_N2 0.80 (4.18 MPa), 155 K at 0.59 (4.82 MPa), 170 K at 0.36 (5.06 MPa); N2/C2H6 150 K at 0.38 (7.58 MPa, ratio 1.35), 180 K at 0.48 (11.7 MPa), 210 K at 0.53 (13.8 MPa), 240 K at 0.51 (13.4 MPa), 270 K at 0.38 (10.3 MPa); CH4/C2H6 200 K at x_CH4 0.95, 225 K at 0.80, 250 K at 0.61, 275 K at 0.37 (5.2-6.9 MPa); CO2/N2 x_N2 0.34 at 220 K (18.0 MPa), 0.40 at 240 K (16.8 MPa), 0.38 at 260 K, 0.23 at 280 K, 0.14 at 290 K (9.6 MPa); CO2/CH4 x_CH4 0.74 at 220 K down to 0.27 at 280 K (6.6-9.0 MPa). All other isotherms span the whole grid. The N2/C2H6 and CO2/N2 ends at the lower temperatures are the high-pressure critical line of these type III systems (rhoL/rhoV 1.1-1.5 at the last point); the rest end at rhoL/rhoV about 1.01.

Coverage consequence for the gate: the CO2/N2 pair has only 7 clean bubble points (CO2-rich liquid, x_CO2 >= 0.7, mostly at 4-9 MPa); N2-rich liquids with CO2 do not exist at these temperatures, and CO2-rich liquids with N2 need high pressure.

### A.6 D7 targets these references check

From P0 section 3.2 and D7 (fixture F2, plan section 8.2), stored in the fixture's `d7_targets`:

- bubble-point pressure: AAD over the pair <= 10 %, per point <= 20 %;
- vapour composition: |y_model - y_ref| <= 0.02;
- |ln K_model - ln K_ref| <= 0.15 for species with x or y > 1e-3;
- scope: outside the mixture critical region. Proposed reading for WP9: score the bubble rows without flags (144 rows); report the `near_critical` and `above_10_MPa` rows and the dew rows without gating them; print the zero-kij result beside the E-PPR78 result (plan section 4.5 item 4).

### A.7 GERG-2008 stated uncertainties for these pairs

Source: Kunz, Klimeck, Wagner and Jaeschke 2007, GERG Technical Monograph 15 (the GERG-2004 monograph; GERG-2008 kept these binary equations, A.3), Table 7.19 (printed page 191), which the authors call conservative estimates of the binary equations' uncertainty:

| Property | Binary-specific departure function (CH4/N2, CH4/CO2, CH4/C2H6, N2/CO2, N2/C2H6) | Reducing functions only (CO2/C2H6) |
|---|---|---|
| Vapour pressure (bubble-point pressure) | 1 to 3 % | 5 % (the text adds that for pairs of similar components such as CO2/ethane the uncertainty is often within the binary-specific range) |
| Saturated liquid density, 100-140 K | 0.1 to 0.2 % | 0.5 to 1 % |
| Liquid density, T/Tr <= 0.7, 0-40 MPa | 0.1 to 0.3 % | 0.5 to 1 % |
| Heat capacities, homogeneous regions | 1 to 2 % (footnote) | 1 to 2 % |

Qualifications stated in the monograph (paraphrased): the estimates can be exceeded for strongly non-ideal pairs, indicated by pure-component critical temperatures more than 150 K apart, and the vapour pressure is the property most affected (N2/C2H6: 179 K apart; CO2/N2: 178 K apart; the other four pairs are below 150 K); for CH4/N2 the table applies roughly over the whole composition range, and the most reliable CH4/N2 vapour pressures are represented within 1 to 2 % (section 8, pTxy discussion). Vapour-composition uncertainties are not tabulated. Normal range of the equation: 90-450 K, up to 35 MPa (Kunz and Wagner 2012; P0 section 3.4).

Reading: the reference's 1-5 % bubble-pressure uncertainty is 2 to 10 times inside the 10 % AAD target and 4 to 20 times inside the 20 % per-point target, so it is negligible for the gate except near the critical line of the two type III pairs.

### A.8 How to regenerate

`tools/gerg-bubble-points/README.md`: one command in the throw-away CoolProp venv, about 3 s; two runs gave byte-identical files.

### A.9 Open items (WP8)

1. GERG-2008's own pure-fluid equations are not used (A.1). A port of `GERG2008.cpp`'s pure-fluid part (or the Java oracle once it has GERG pure fluids) would quantify the pure-fluid effect on the bubble pressures; expected well below the 10 % target.
2. The `near_critical` threshold (rhoL/rhoV < 3) is a proposal; WP9 may choose another, since every row carries rhoL and rhoV and `isotherm_ends` gives the critical ends.
3. The CO2/N2 dew failure at 280 K (A.5) is marginal.
4. MANIFEST correction to carry when the batch documents are copied: `sources/MANIFEST.md` section 1.2 says CO2/N2 carries Gernert 2013 parameters; the values are GERG-2008's (A.3).

---

## Part B. WP10: methane ideal-gas Cp above 375 K

### B.1 Sources obtained

| Source | What it gives | Licence | Used as |
|---|---|---|---|
| ExoMol MM line list of 12CH4 (Yurchenko, Owens, Kefala and Tennyson 2024, MNRAS 528, 3719; states file version 20240113) | 9 155 208 rovibrational levels (energy, total degeneracy with nuclear-spin weights, J <= 60), complete below 18 000 cm-1; its partition function 1-5000 K | CC BY-SA 4.0 (data); the paper CC BY 4.0 | **The independent source**: Cp by direct summation over the levels |
| ExoMol YT10to10 (2014) and YT34to10 (2017) `.pf` and `.cp` files | Older line lists' partition function and ExoMol's own specific-heat tables | CC BY-SA 4.0 | For the record |
| Wenger, Champion and Boudon 2008, JQSRT 109, 2697 (HAL author version) | Partition sum 100-3000 K from an effective Hamiltonian of the lower polyads plus a Morse model of the upper polyads to dissociation, stated uncertainty < 0.1 % to 1300 K | local research copy | Cross-check of Q and an upper estimate of the missing high-lying levels |
| HITRAN TIPS `q32.txt` (served file of 2025-12-11) | Partition sum 1-2500 K | HITRAN terms (research use, citation) | Cross-check |
| NIST-JANAF C-067 (Chase 1998), downloaded table | Cp at 100 K steps | NIST SRD | Holdout |
| GERG-2008 ideal part (`GERG2008.cpp`), CoolProp Setzmann-Wagner alpha0 and NASA CEA / Gurvich 1991 (the r1 spine record) | Cp | as in the P0 manifest | Compared |

URLs, sizes and sha256 of every file: `research/2026-09-24-coolprop-low-temperature/methane-ideal-gas/README.md`.

### B.2 Method and its numerical error

- Cp from the levels directly: Q = sum g exp(-c2 E/T); Cp = 5/2 R + R (c2/T)^2 var(E), var(E) the variance of the level energy under the Boltzmann weights (float64 two-pass sums over 9.16 million levels), c2 = 1.438776877 cm K, R = 8.314462618 J/(mol K). No numerical differentiation, so no differentiation error; the rounding error of the sums is below 1e-9 relative even in the worst case (9.2 million positive float64 terms).
- The route the brief anticipated, numerical differentiation of ln Q of the `.pf` file (1 K steps, 4 decimals), was run as a check: a least-squares degree-6 polynomial of ln Q over +-50 K gives Cp within 2.5e-6 of the direct sum at every table temperature (degree 4 over +-25 K: 7.0e-6). The direct sum reproduces the `.pf` file's Q to 2.2e-6, so the `.pf` is the same level set.
- Nuclear spin: at T >= 298 K the equilibrium of the spin modifications makes the spin weights a constant factor (16) of Q at every temperature that matters, so Cp and h are unaffected; for S the factor is removed (thermochemical convention).

### B.3 Completeness and the declared error of the reference

The file stops at 18 000 cm-1. Its level density follows a power law, (E + 4250 cm-1)^7.77, from 10 000 to 15 000 cm-1 (rms 0.8 % per 100 cm-1 bin), and falls below the law from about 15 500 cm-1 (0.94 of it at 16 550, 0.80 at 17 550 cm-1): the file is thinner at its top. Three estimates of what is missing:

| T, K | Cp, direct sum | Cp with the file cut at 16 000 cm-1 | Power-law completion (deficit above 15 000 plus the law to 36 100 cm-1, D0) | Missing levels implied by Wenger et al.'s larger Q (5 fit windows, 1300-2000 K) |
|---|---|---|---|---|
| 1000 | 72.793 | 72.785 | +0.003 | +0.011 to +0.038 |
| 1073 | 75.700 | 75.672 | +0.010 | +0.049 to +0.114 |
| 1143 | 78.240 | 78.167 | +0.031 | +0.168 to +0.284 |
| 1200 | 80.120 | 79.977 | +0.068 | +0.409 to +0.550 |
| 1300 | 82.976 | 82.595 | +0.221 | +1.30 to +1.60 |
| 1500 | 86.653 | 85.161 | +1.265 | +6.7 to +13.7 (not credible) |

Partition sums against the direct sum: ExoMol `.pf` -1.6e-6 at 1000 K; TIPS +2e-5 at 300-600 K, -3.4e-5 at 1000 K, -5.4e-4 at 1200 K, -4.6 % at 2000 K; Wenger -8.8e-5 to +1.8e-4 from 300 to 1200 K (their stated uncertainty), then +5.4e-4 (1300 K), +6.1e-3 (1500 K), +2.5e-2 (1700 K), +10 % (2000 K). MM and Wenger agree to 2e-4 up to 1200 K and diverge above; TIPS agrees with MM to 3.4e-5 up to 1000 K and falls below from there; the MM authors estimate their partition function complete to about 2000 K (MM paper section 5.2, against TIPS 2021), which the Wenger comparison does not support above 1300 K. The current TIPS file lies below MM from 1000 K up and gives a Cp that falls with temperature above 1300 K (it is not the TIPS 2021 curve the MM paper plots); it is not used above 1000 K.

**Reference adopted:** direct sum plus the power-law completion. **Declared error of the reference:** from the plain direct sum (a strict lower bound: adding levels above the mean energy can only raise Cp) to the larger of the power-law and Wenger-implied completions, plus 1.8e-4 relative for level-structure differences (the Cp implied by a smooth fit of ln(Q_Wenger/Q_MM) at 300-1200 K):

| T, K | 1000 | 1073 | 1100 | 1143 | 1200 | 1300 |
|---|---|---|---|---|---|---|
| Reference Cp, J/(mol K) | 72.796 | 75.710 | 76.725 | 78.271 | 80.188 | 83.196 |
| Declared error, % | -0.02 / +0.07 | -0.03 / +0.15 | -0.04 / +0.21 | -0.06 / +0.34 | -0.10 / +0.62 | -0.28 / +1.67 |

Below 1000 K the declared error is 0.02 % (the floor). The resulting h(T) - h(298.15 K) uncertainty at 1143 K is about 20 J/mol (0.04 %), at the D7 floor (0.1 % or 20 J/mol).

### B.4 Methane ideal-gas Cp, J/(mol K), all sources

Reference = ExoMol MM direct sum plus power-law completion. JANAF: the C-067 table (at 375 K the NIST Shomate fit of the same data). GERG: the GERG-2008 ideal part as `GERG2008.cpp` computes it. SW: CoolProp's Setzmann-Wagner alpha0 (the spine's segment 1, evaluated beyond its 375 K end). CEA: the r1 spine's Gurvich 1991 polynomials. RRHO: rigid rotor plus harmonic oscillators at the observed fundamentals, a sanity bound only. TIPS and YT10to10: Cp derived from the TIPS partition sum (degree-6 fit of ln Q over +-50 K) and ExoMol's own `.cp` file of the 2014 line list.

| T, K | Reference | MM direct | JANAF | GERG-2008 | SW | CEA (r1) | RRHO | TIPS | YT10to10 |
|---|---|---|---|---|---|---|---|---|---|
| 300 | 35.767 | 35.767 | 35.708 | 35.777 | 35.778 | 35.760 | 35.680 | 35.767 | 35.805 |
| 375 | 39.233 | 39.233 | 39.131 | 39.240 | 39.242 | 39.243 | 39.100 | 39.231 | 39.288 |
| 500 | 46.519 | 46.519 | 46.342 | 46.514 | 46.507 | 46.585 | 46.286 | 46.520 | 46.582 |
| 600 | 52.512 | 52.512 | 52.227 | 52.493 | 52.492 | 52.691 | 52.171 | 52.514 | 52.573 |
| 700 | 58.222 | 58.222 | 57.794 | 58.196 | 58.200 | 58.543 | 57.742 | 58.225 | 58.273 |
| 800 | 63.532 | 63.532 | 62.932 | 63.509 | 63.510 | 64.013 | 62.884 | 63.521 | 63.558 |
| 900 | 68.397 | 68.396 | 67.601 | 68.384 | 68.381 | 69.066 | 67.559 | 68.370 | 68.366 |
| 1000 | 72.796 | 72.793 | 71.795 | 72.802 | 72.805 | 73.676 | 71.758 | 72.706 | 72.655 |
| 1073 | 75.710 | 75.700 | 74.569 | 75.740 | 75.757 | 76.761 | 74.530 | 75.560 | 75.439 |
| 1100 | 76.725 | 76.709 | 75.529 | 76.766 | 76.793 | 77.849 | 75.496 | 76.373 | 76.392 |
| 1143 | 78.271 | 78.240 | 77.008 | 78.335 | 78.379 | 79.522 | 76.969 | 77.766 | 77.820 |
| 1200 | 80.188 | 80.120 | 78.833 | 80.295 | 80.370 | 81.628 | 78.803 | 79.448 | 79.541 |

Deviation from the reference, %:

| T, K | JANAF | GERG-2008 | SW | CEA (r1) | RRHO | TIPS | YT10to10 | Declared error of the reference |
|---|---|---|---|---|---|---|---|---|
| 300 | -0.16 | +0.03 | +0.03 | -0.02 | -0.24 | 0.00 | +0.11 | +-0.02 |
| 375 | -0.26 | +0.02 | +0.02 | +0.03 | -0.34 | -0.01 | +0.14 | +-0.02 |
| 500 | -0.38 | -0.01 | -0.03 | +0.14 | -0.50 | 0.00 | +0.14 | +-0.02 |
| 600 | -0.54 | -0.04 | -0.04 | +0.34 | -0.65 | 0.00 | +0.12 | +-0.02 |
| 700 | -0.74 | -0.05 | -0.04 | +0.55 | -0.82 | +0.01 | +0.09 | +-0.02 |
| 800 | -0.94 | -0.04 | -0.03 | +0.76 | -1.02 | -0.02 | +0.04 | +-0.02 |
| 900 | -1.16 | -0.02 | -0.02 | +0.98 | -1.23 | -0.04 | -0.05 | -0.02 / +0.03 |
| 1000 | -1.38 | +0.01 | +0.01 | +1.21 | -1.43 | -0.12 | -0.19 | -0.02 / +0.07 |
| 1100 | -1.56 | +0.05 | +0.09 | +1.47 | -1.60 | -0.46 | -0.43 | -0.04 / +0.21 |
| 1200 | -1.69 | +0.13 | +0.23 | +1.80 | -1.73 | -0.92 | -0.81 | -0.10 / +0.62 |

At the steam-cracking temperatures: 1073 K JANAF -1.51 %, GERG +0.04 %, SW +0.06 %, CEA +1.39 %; 1143 K JANAF -1.61 %, GERG +0.08 %, SW +0.14 %, CEA +1.60 %.

Plan appendix B checked: its GERG values (72.802, 76.766, 80.295 at 1000, 1100, 1200 K) and CEA values (73.68, 77.85, 81.63) are reproduced.

### B.5 Findings

1. **The line list settles the conflict against both standard tables.** From 300 to 1000 K the independent reference, the GERG-2008 ideal part (Jaeschke and Schley 1995) and CoolProp's Setzmann-Wagner terms agree within 0.05 % (at 1100 K GERG +0.05 %, Setzmann-Wagner +0.09 %). JANAF lies 0.16 % low at 300 K and 1.4 to 1.7 % low at 1000 to 1200 K, and within 0.11 % of the rigid-rotor harmonic-oscillator values above 600 K (0.05 % above 1000 K): its methane table behaves as an RRHO table without the anharmonic and rovibrational contributions. CEA (Gurvich 1991) lies 0.1 % high at 500 K and 1.2 to 1.8 % high at 1000 to 1200 K: it overcorrects. The P2 statement "Setzmann-Wagner sides with JANAF below about 600 K" does not hold against the line list; Setzmann-Wagner and GERG side with the line list throughout.
2. **Consequence for the r1 spine.** Its CEA segment above 375 K is 0.3 % high at 600 K and 1.4 to 1.6 % high at 1073 to 1143 K; h(T) - h(298.15) is 16 J/mol high at 600 K, 216 J/mol (0.56 %) at 1000 K, 286 J/mol at 1073 K and 366 J/mol (0.74 %) at 1143 K.
3. **Standard entropy.** The direct sum gives S(298.15 K, 1 bar) = 186.3717 J/(mol K) with the spine's molar mass (186.3628 with the 12CH4 mass), nuclear-spin factor 16 removed. The spine's CEA value 186.3702 agrees to 0.0015; JANAF's 186.251 is 0.12 low. H(298.15) - H(0) = 10 016.6 J/mol (JANAF 10 024).
4. **Older line lists.** ExoMol's own `.cp` files of the 2014 and 2017 line lists run 0.2 % (1000 K) to 0.8 % (1200 K) below the MM reference and fall with temperature above 1500 K: they are less complete; the YT10to10 and YT34to10 `.pf` files are byte-identical.

### B.6 Decision proposal D13: methane's ideal-gas source above 375 K

**Options.**

| Option | Cp deviation from the reference at 1073 / 1143 / 1200 K | Spine change | Assessment |
|---|---|---|---|
| A. CEA as is (r1) | +1.39 / +1.60 / +1.80 % | none | Outside the declared reference error by 1.2 to 1.5 points; rejected |
| B. GERG-2008 ideal part | +0.04 / +0.08 / +0.13 % | new segment | Inside the reference error, but its `ln cosh` terms are not in the loader's term families (`log_tau`, `power`, `planck_einstein`, `planck_einstein_function_t`): needs a new term type (code) or a fit of a fit |
| C. **NASA 9 segment fitted to the line-list reference (recommended)** | 0.000 (fit 8.5e-6 max over 425-1300 K) | segment 1 to 425 K, new `nasa9` 425-1300 K, CEA segments removed | Follows the independent source; the plan's own fallback route (section 5); declared error = the reference's |
| C'. As C to 1500 K | same below 1300 K (fit 1.0e-5) | new segment 425-1500 K | Above 1300 K the reference itself is uncertain by +1.7 to +14 % (B.3); not recommended |
| D. Extend the Setzmann-Wagner segment to 1300 K | +0.06 / +0.14 / +0.23 % | segment 1 to 1300 K, CEA removed | No new coefficients and no join; inside the declared error, but 0.23 % at 1200 K misses the plan's 0.2 % test against the reference, and SW is used 675 K beyond its published range |

**Recommendation: option C.** Methane spine revision `spine-methane-r2`:

1. Segment 1 (`helmholtz_ideal_terms`, Setzmann-Wagner, unchanged terms): `temperature_max_kelvin` 375 -> **425**. The join moves to 425 K by P2's rule (smallest Cp step on the 25 K grid): the step there is -6.6e-6 (fit over SW), against 2.2e-4 at 375 K.
2. Segments 2 and 3 (CEA 375-1000 and 1000-6000 K) are replaced by one segment:

```json
{
  "type": "nasa9",
  "temperature_min_kelvin": 425.0,
  "temperature_max_kelvin": 1300.0,
  "source": "NASA 9 fit to the ideal-gas Cp of 12CH4 from the ExoMol MM line list (Yurchenko, Owens, Kefala and Tennyson 2024, MNRAS 528, 3719; states file version 20240113, CC BY-SA 4.0) by direct summation plus a power-law density-of-states tail; tools/methane-ideal-gas/methane_cp.py (batch 2026-09-24-coolprop-low-temperature, WP10); b1 and b2 continue h and s of the Setzmann-Wagner segment at the join and are not used by the loader",
  "revision": "exomol-mm-20240113-methane-nasa9-r1",
  "gas_constant_j_per_mol_kelvin": 8.314462618,
  "coefficients": [234850.8155589512, -1290.3273664488147, 4.314915140659516, 0.00528659584430866, 2.303518032604627e-06, -2.671429465748859e-09, 5.772225542200282e-13, -2360.4329741734255, -6.836255894659672]
}
```

   (file `research/.../methane-ideal-gas/outputs/spine-methane-r2-segment-425-1300.json`; the 1500 K variant is under `outputs/fit-1500/`). Cp/R of the segment reproduces the reference to 8.5e-6 max and 3.2e-6 rms over 425-1300 K; Cp is positive and smooth over the range; the gas constant is CODATA 2018 (the loader's 1e-5 check passes by construction).
3. The spine ends at 1300 K: methane is refused above it (typed, P2's rule "nothing is extrapolated"), 100 K above the pilot's provisional 1200 K ceiling. The Cp step to CEA's 1000-6000 K polynomial at 1300 K is +2.2 % (+3.4 % at 1500 K), far above the 0.005 join limit, so CEA cannot follow the new segment; no process anchor needs methane above 1148 K.
4. `coverage`: grade `qualified` up to 1300 K, evidence "Cp within 1e-5 of the ExoMol MM direct-sum reference (425-1300 K); reference declared error -0.03/+0.15 % at 1073 K, -0.06/+0.34 % at 1143 K, -0.10/+0.62 % at 1200 K (P3_REFERENCES_WP8_WP10.md B.3)". The record carries one grade; the growth of the declared error above 1143 K is stated in the evidence.
5. `standard_entropy`: value unchanged (CEA 186.3702, confirmed by the line list to 0.0015, B.5 item 3); proposed: replace the disagreement estimate 0.1192 (|CEA - JANAF|) by 0.0015 (|CEA - MM|) with that source text. `formation_enthalpy` unchanged (ATcT).
6. `source` text: name the MM segment instead of CEA; the record's `revision` moves to `spine-methane-r2`, which moves the pilot package's spine fingerprint (no gameplay pin depends on it in P3).

**Declared error at the steam-cracking reference (1073-1143 K), option C:** Cp -0.03 to -0.06 % / +0.15 to +0.34 % (one-sided in effect: the reference may be low, not high); h(T) - h(298.15) about 20 J/mol (0.04 %). Before: 3.5 % declared (CEA vs JANAF disagreement). The plan's test ("`ReferenceSpineTest` holds the methane spine to the chosen source within 0.2 % at 600, 800, 1000, 1100 and 1200 K") would hold it to the reference values 52.512, 63.532, 72.796, 76.725 and 80.188 J/(mol K); the fit meets them to 1e-5.

**Effect on the spine's h.** h(T) - h(298.15) of r2 against r1: -15 J/mol at 600 K, -215 J/mol at 1000 K, -285 J/mol at 1073 K, -365 J/mol at 1143 K, -442 J/mol at 1200 K (r2 minus the reference: +1 J/mol throughout).

**Licence note for the owner.** The fitted coefficients derive from CC BY-SA 4.0 data. Bundling them in the mod's resources is "adapted material" under a strict reading of the share-alike clause (attribution plus the same licence for the adaptation). The owner has accepted non-commercial terms for sources; share-alike is a different obligation and should be decided when the record is bundled (WP3 or WP9). Option D (Setzmann-Wagner, MIT via CoolProp) avoids it at the cost of 0.23 % at 1200 K.

### B.7 Open items (WP10)

1. **D13 decision** (lead / owner): record C (recommended), C', or D in `DECISION_LOG.md`; WP3 or WP9 applies the record change (B.6) and updates `ReferenceSpineTest` (the r1 methane test holds the CEA-vs-JANAF deviation at 2.5-3.6 %; with r2 it would hold the spine to the B.6 reference values at 0.2 % and could hold JANAF at -1.5 to -1.7 % as a documented disagreement).
2. The upper end of the reference error rests on Wenger et al.'s partition sum read as missing levels with one activation temperature; an independent level density above 18 000 cm-1 (Nikitin et al. 2015, JQSRT 167, 53, not open; or the Wenger online code) would narrow it at 1143-1200 K.
3. The share-alike question of B.6.
4. The main-checkout copy of the 193 MB states file is optional (URL and sha256 recorded).
5. `research/INDEX.md` and `tools/INDEX.md` rows for `methane-ideal-gas/`, `gerg-bubble-points/` (both folders) are for the lead to add when copying (not edited here, as briefed).

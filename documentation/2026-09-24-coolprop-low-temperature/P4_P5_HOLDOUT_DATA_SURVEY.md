# P4 and P5 holdout data survey: solid CO2 in nitrogen and in methane

Status: Concluded 2026-09-25 (research only: no code, no Gradle, no tracked file changed). Written in the worktree copy of the batch folder; the lead copies it to the main checkout.
**Updated 2026-09-25 (later the same day) with the owner-provided papers.**
- Items 1 to 12 of the request list (section 5) were received and read at medium depth; section 9 summarises them.
- Section 4 now states what G4 and G5 can be qualified with. The G4 frost-point criterion is now testable.
- Headline items 1, 2, 3 and 6 below, and the "cannot be qualified" lists, predate the papers; section 9 supersedes them where they differ.
Batch `2026-09-24-coolprop-low-temperature`, preparation for P4 (selected solid-gas support, gate G4) and P5 (solid-liquid and full phase competition, gate G5). It follows these sources:

- plan sections 2, P4, P5 and 7 (`UNIFIED_MULTIPHASE_THERMO_PLAN.md`);
- the solid CO2 row of section 3.2 and the holdouts of section 3.3 in `P0_BASELINE_COVERAGE_AND_SOURCES.md`;
- `MIXTURE_FREEZING_FEASIBILITY_REVIEW.md` and `SOLID_GAS_FEASIBILITY_REVIEW.md`, where the holdouts were first named;
- decision D2 in `DECISION_LOG.md`.

Every dataset here is a holdout (validation data), not a model input.

Files of this work (all git-ignored, main checkout):

| Path | Content |
|---|---|
| `research/2026-09-24-coolprop-low-temperature/sources/solid-co2-holdouts/` | 14 TSV transcriptions with citation headers, 5 NIST ThermoML files, the ThermoML flattener, 8 fetched documents and a WebBook snapshot; `README.md` |
| `research/2026-09-24-coolprop-low-temperature/sources/MANIFEST.md` section 6 | Sizes, SHA-256, URLs, retrieval date 2026-09-25, licences, fetch findings, items not fetched |
| `research/2026-09-24-coolprop-low-temperature/sources/solid-co2-holdouts/owner-provided/` | 13 owner-provided PDFs, 9 TSV transcriptions and 3 notes (section 9) |
| `research/2026-09-24-coolprop-low-temperature/sources/MANIFEST.md` section 7 | Sizes, SHA-256, citations and licences of the owner-provided papers and the derived files |

## 0. Headline findings

1. **Maltby et al. 2025 exists.**
   - **Reference.** Tage W. Maltby, Ailo Aasen, Morten Hammer, Øivind Wilhelmsen, "Review of Experimental Data and Evaluation of Equations of State for Modeling Formation of Solid CO2 in CCS and Natural Gas Applications", Ind. Eng. Chem. Res. 64(50), 24253-24263, published 2025-12-05, doi:10.1021/acs.iecr.5c04028 (NTNU and SINTEF).
   - **What it is.** A review of the equilibrium data for solid CO2 in CCS and natural-gas mixtures, plus an assessment of solid and fluid EOS combinations. Its reference list includes Jäger-Span, Trusler and the Trusler erratum, Siah et al. 2025, GERG-2008 and EOS-CG.
   - **Result (abstract, quoted, not checked).** Fluid-EOS choice dominates. For CCS the best is Peng-Robinson, at an MAPD of 1.4 %; for natural gas the most recent multiparameter EOS, below 1 %.
   - **Access.** It is open access under CC BY 4.0, but pubs.acs.org answers automated clients with a Cloudflare 403. It was not read, and it goes on the owner request list. The plan's phrase "selected through the Maltby et al. 2025 review" can stand, but the selection itself has not been done.
2. **The named G4 frost-point holdouts are not open.** These are Sonntag and Van Wylen 1962, and Smith, Sonntag and Van Wylen 1963 and 1964 (CO2 + N2).
   - All three are Springer chapters of Advances in Cryogenic Engineering 7, 8 and 9.
   - The only open route found is R. E. Sonntag's 1960 University of Michigan dissertation (Industry Program report IP-469) on Deep Blue, hdl 2027.42/7707, which Cloudflare blocks for automated clients. It goes on the owner list.
   - No open tabulation of CO2 + N2 binary frost points was found in any thesis, NIST publication or ThermoML file.
   - **Consequence: G4's frost-point criterion cannot be qualified today.**
3. **The P5 liquidus can be qualified today, but not with the plan's anchor set.** Open or NIST-archived data cover the CO2 liquidus in liquid CH4 from 112 to 190 K:
   - Shen et al. 2012 via NIST ThermoML: 9 binary SLVE points, x_CO2 2.1e-4 to 0.029.
   - Campestrini et al. 2022 via HAL: 6 binary SLE points at 4 MPa.
   - Souza et al. 2020 via ThermoML: the three-phase line next to the CO2 triple point.
   - Several N2 and C2H6 ternaries.

   Davis, Rodewald and Kurata 1962, the plan's anchor SLV locus with liquid and vapour compositions down to 97 K, is paywalled (Wiley).
4. **Two of the named "freezing out of liquid methane" holdouts are frost-point data.**
   - Zhang et al. 2011 and Le and Trebble 2007 are solid-vapour frost points in methane gas, not liquidus data. Both are open through NIST ThermoML, as is Xiong et al. 2015 with its N2 and C2H6 ternaries.
   - They test P5's vapour-side phase competition, not the liquidus. The P0 target row mixes the two (section 6).
5. **The pure CO2 holdouts are local and transcribed.**
   - Span and Wagner 1996: sublimation equation (3.12) and melting equation (3.10) with their stated uncertainties, from the NIST reprint.
   - Fray and Schmitt 2009: the CO2 coefficients from the Lisse et al. 2020 appendix, with the unit (bar) and branch ranges inferred numerically because the reproduction omits them.
   - NIST WebBook: the Antoine fit to Giauque and Egan's data and the 25.2 kJ/mol compilation value.
   - Trusler 2011: auxiliary equations, secondary.
   - The CoolProp `CarbonDioxide.json` (local and in the worktree test resources) carries the Span-Wagner melting line but **no sublimation line**.
6. **Trusler 2011 has an erratum.** J. Phys. Chem. Ref. Data 41, 039901 (2012), doi:10.1063/1.4745598, published September 2012 and registered in Crossref as the correction of doi:10.1063/1.3664915.
   - Its abstract says three corrections were needed, two of them pointed out by Andreas Jäger (Ruhr-Universität Bochum), and that a software implementation is available from the author.
   - What it corrects could not be read: the AIP full text is paywalled, and the Semantic Scholar abstract is elided by the publisher.
   - The local `sources/solid-co2/trusler-2011-jpcrd-40-043105.pdf` is the uncorrected original (AIP download of 16 March 2013). No Trusler coefficient should be adopted before the erratum is read.
   - No errata are registered for Jäger and Span 2012, Span and Wagner 1996, Fray and Schmitt 2009 or Giauque and Egan 1937.
7. **The frost-point and liquidus targets are at the data's own uncertainty.**
   - The 1.5 K and 2 K targets proposed in P0 section 3.2 sit at the level of the data. TRC assesses Le and Trebble's frost temperatures at U95 = 1.2 to 1.6 K.
   - They also sit at the level of published models. Riva 2016 (Table 4.12) finds Peng-Robinson with literature kij at 25 % and 38 % AAD in y_CO2 on the Sonntag and Smith sets, and GERG-2008 at 24 % and 61 %. This is roughly 2 to 4 K in frost temperature for Peng-Robinson (section 4.3).
   - The targets should be stated as an AAD with a per-point cap, not per point.
8. **P4 stage 1's Span-Wagner transcription is confirmed.** Its sublimation equation (3.12), transcribed without web access, matches the journal page exactly, including the uncertainty bands (section 7).

## 1. Method and access

- **Sources read.** The plan and P0 documents above; the local `sources/solid-co2/` papers (Jäger and Span 2012, Trusler 2011); Crossref and OpenAlex metadata for every DOI; the HAL API; the NIST TRC ThermoML archive; NIST SRD and WebBook pages; Imperial College Spiral.
- **Fetch policy.** Publisher, HAL, arXiv, NIST and university repositories only. HAL serves a proof-of-work "not a bot" page to browser user agents; the files were fetched with curl's default user agent, which HAL serves directly, and no challenge was solved. Cloudflare 403s were accepted without retry:
  - Deep Blue;
  - pubs.acs.org (Maltby 2025);
  - onlinelibrary.wiley.com (Sampson 2023);
  - pubs.aip.org (the Trusler erratum);
  - figshare's ACS supplements.
- **ThermoML.** NIST TRC's ThermoML Archive (doi:10.18434/mds2-2422) holds TRC compilations of the data tables of JCED, J. Chem. Thermodyn., FPE, Thermochim. Acta and IJT papers from about 2003. It returned files for Le and Trebble 2007, Zhang 2011, Xiong 2015, Shen 2012 and Souza 2020. It returned 404 for He et al. 2022, Campestrini et al. 2022, Kurata and Im 1971 and Liu et al. 2022. The data of paywalled JCED/FPE papers are open this way. The TRC `U95` column is the compiler's assessment.
- **Transcription.** Tables were transcribed from 140 to 200 dpi PyMuPDF renders, or from the text layer where a render check agreed. The Span-Wagner reprint is a scan with no text layer and was read from renders only. Digits are verbatim; units and uncertainties are in each TSV header.

## 2. Gate G4: CO2 deposition from nitrogen

### 2.1 Pure CO2 sublimation pressure and enthalpy

| Dataset | Reference, DOI | Availability | Local file | Coverage | Stated uncertainty |
|---|---|---|---|---|---|
| Span-Wagner sublimation equation (3.12) | Span, Wagner, JPCRD 25, 1509 (1996), doi:10.1063/1.555991, p. 1521 | Open (NIST SRD reprint), fetched | `span-wagner-1996-co2-melting-sublimation-equations.tsv` | Fitted to group-1 data above 154 K up to T_t = 216.592 K, p_t = 0.51795 MPa. The group-1 data are Ambrose 1955 (16 points), Bilkadi et al. 1974 (132), Bedford et al. 1984 (1) and Fernandez-Fassnacht and del Rio 1984 (21); see `span-wagner-1996-table6-co2-sublimation-datasets.tsv`. Normal sublimation point 194.6855 K | ±250 Pa (185 K to T_t), ±100 Pa (170 to 185 K), ±50 Pa (below 170 K) |
| Fray and Schmitt CO2-1 and CO2-2 | Fray, Schmitt, P&SS 57, 2053 (2009), doi:10.1016/j.pss.2009.09.011; reproduced in Lisse et al. 2020, arXiv:2009.02277, SOM Table 2, p. 35 | Coefficients local (arXiv); the paper itself paywalled | `fray-schmitt-2009-co2-sublimation-via-lisse-2020.tsv` | ln P = A0 + Σ Ai/T^i, P in bar (inferred). Against eq. (3.12), CO2-1 is within 0.52 % from 145 to 195 K, -0.93 % at 200 K and -3.9 % at T_t; CO2-2 is within 0.62 % from 195 K to T_t and +19 % at 160 K. Exact ranges are not in the reproduction | None stated |
| Giauque and Egan vapour pressures (12 points, 154 to 196 K) | Giauque, Egan, J. Chem. Phys. 5, 45 (1937), doi:10.1063/1.1749929 | Paper paywalled. Open only as the NIST WebBook Antoine fit "calculated by NIST from author's data", 154.26 to 195.89 K | `nist-webbook-co2-sublimation-giauque-egan-derived.tsv` | log10(P/bar) = 6.81228 - 1301.679/(T - 3.494) | Span-Wagner Table 6 lists ±1 mK and ±3 Pa; Trusler Table 2 lists u(T) 0.02 K and u(p) 3 Pa |
| Sublimation enthalpy near 195 K | Giauque and Egan 1937 (calorimetric) | Paywalled; the WebBook's 25.2 kJ/mol at 195 K is a compilation value "based on data from 154 to 196 K", i.e. from vapour pressures, not the calorimetric number | same file | Also 25.9 (Ambrose, 188 K), 26.3 (Stull, 167 K), 26.1 (207 K), 27.2 ± 0.4 kJ/mol (Bryson, 70 to 102 K) | Calorimetric value: u_r 0.08 % (Trusler Table 2) |
| Trusler auxiliary sublimation equation (48) | Trusler, JPCRD 40, 043105 (2011), doi:10.1063/1.3664915 | Local (owner copy, pre-erratum) | `trusler-2011-co2-auxiliary-equations-and-table2.tsv` | 150 to 216 K; a fit to Trusler's solid EOS combined with Span-Wagner, **not independent** | 0.04 % against its own EOS |

The primary sublimation measurements (Ambrose 1955, Bilkadi et al. 1974, Fernandez-Fassnacht and del Rio 1984, Bryson et al. 1974) are paywalled and were not pursued: the correlations above carry them with stated uncertainty. Independence: Jäger and Span 2012 state that their solid equation was not fitted to sublimation pressures (p. 596). The Span-Wagner correlation is therefore an independent check of a Jäger-Span solid combined with the Span-Wagner fluid. For a triple-point-anchored model built from Giauque and Egan's calorimetry (the plan's alternative), the Giauque and Egan vapour-pressure fit is from the same paper but a different measurement.

### 2.2 CO2 frost points in nitrogen (solid-vapour equilibrium of CO2 + N2)

| Dataset | Reference, DOI | Availability | Local file | Coverage | Uncertainty |
|---|---|---|---|---|---|
| Sonntag and Van Wylen 1962 | "The Solid-Vapor Equilibrium of Carbon Dioxide-Nitrogen", Adv. Cryog. Eng. 7, 99-105, doi:10.1007/978-1-4757-0531-7_12 | Paywalled (Springer). **Open route: Sonntag, R. E., "The Equilibrium of Solid Carbon Dioxide with its Vapor in the Presence of Nitrogen", PhD dissertation, University of Michigan, October 1960 (IP-469), Deep Blue hdl 2027.42/7707**, blocked for automated clients | none | 64 SVE points (T, p, y_CO2), 140 to 190 K, 0.5 to 10.1 MPa, y_CO2 190 to 78 800 ppm (Riva 2016 Table 3.4; Stringari-Riva 2018 Table 1; Campestrini 2023 Table 1 prints 0.51 to 10.1 MPa) | Unknown until read |
| Smith, Sonntag and Van Wylen 1963 | "Analysis of the Solid-Vapor Equilibrium System Carbon Dioxide-Nitrogen", Adv. Cryog. Eng. 8, 162-173, doi:10.1007/978-1-4757-0528-7_19 | Paywalled (Springer) | none | An analysis paper (models against the data); whether it adds points is unknown | Unknown |
| Smith, Sonntag and Van Wylen 1964 | "Solid-Vapor Equilibrium of the Carbon Dioxide-Nitrogen System at Pressures to 200 Atmospheres", Adv. Cryog. Eng. 9, 197-206, doi:10.1007/978-1-4757-0525-6_24 | Paywalled (Springer) | none | 72 SVE points (Riva uses 64), 140 to 190 K, 5.1 to 20.3 MPa, y_CO2 1300 to 54 800 ppm | Unknown |
| Fandiño, Trusler, Vega-Maza 2015, Table 8 | IJGGC 36, 78, doi:10.1016/j.ijggc.2015.02.018 | Open (Spiral accepted manuscript, CC BY-NC-ND 4.0), fetched | `fandino-2015-ijggc-36-78-table8.tsv` | CO2 + N2 three-phase S+L+V line: 4 (T, p) points, 212.70 to 214.87 K, 4.821 to 13.018 MPa; no compositions | u(T) = 0.1 K |
| N2 as a minor component in CH4 (indirect) | Le and Trebble 2007 set 3; Xiong 2015 set 1; Campestrini 2022 Tables 7 and 9 | Open (ThermoML, HAL) | see section 3 | 24 points at y_N2 1 to 2 %; 77 points at y_N2 up to 5.7 %; 20 points at 5 and 10 % N2 in the solvent, 4 MPa | see section 3 |
| Liquid-N2 SLE (not P4) | Fedorova 1940, Yakimenko et al. 1975 (Russ. J. Phys. Chem. 49, 116), Rest et al. 1990 (Chem. Eng. J. 43, 25, doi:10.1016/0300-9467(90)80041-A) | Paywalled or not digitised | none | CO2 solubility in liquid N2, 67 to 115 K, ppm range | Belongs to the P7 air rollout |

No dataset in the literature measures the amount of CO2 deposited, the heat released or the reversibility at equilibrium. G4's finite-inventory, heat and exhaustion cases have no experimental holdout. They can be qualified only by conservation and consistency tests (component, energy and volume balances; the pure-CO2 sublimation enthalpy) and by onset agreement at the gas composition left after deposition.

## 3. Gate G5: CO2 freezing out of liquid methane

### 3.1 Pure CO2 melting (the first P5 step)

| Dataset | Reference | Availability | Local file | Coverage | Uncertainty |
|---|---|---|---|---|---|
| Span-Wagner melting equation (3.10) | Span, Wagner 1996, p. 1520 | Open (NIST reprint); also the `melting_line` ancillary of CoolProp `CarbonDioxide.json` (identical coefficients 1955.5390 and 2055.4593; local and in `src/test/resources/science/thermo/coolprop/`) | `span-wagner-1996-co2-melting-sublimation-equations.tsv` | T_t to 270 K (data of Michels et al. 1942 to 284 MPa and Clusius et al. 1960) | ±1.5 % (T_t to 225 K), ±0.5 % (225 to 270 K) |
| Trusler auxiliary melting equation (47) | Trusler 2011 | Local, pre-erratum | `trusler-2011-...tsv` | to 800 K, D_AAD 2.5 % | Secondary |
| Enthalpy of fusion at the triple point | Trusler 2011 Table 2 discussion | Three publications, spread 65 % about the mean (group 2) | none | No reliable primary value | Derive it from consistent sublimation and vaporisation enthalpies at T_t |

### 3.2 CO2 + CH4 (and ternaries): liquidus, three-phase line, frost points

| Dataset | Reference, DOI | Availability | Local file | Kind, coverage, points | Uncertainty |
|---|---|---|---|---|---|
| Davis, Rodewald, Kurata 1962 | "Solid-liquid-vapor phase behavior of the methane-carbon dioxide system", AIChE J. 8(4), 537-539, doi:10.1002/aic.690080423 | Paywalled (Wiley) | none | SLV locus p(T) from the CO2 triple point to 97 K (-284 °F); vapour 0.1 to 12 % CO2; liquid from crystal points of 11 mixtures, 0.16 to 20 % CO2. 42 points (Riva 2016 Table 3.2) or 48 (Campestrini 2023 Table 1), 0.03 to 4.87 MPa | Unknown until read |
| Shen, Gao, Lin, Gu 2012 | JCED 57(8), 2296-2303, doi:10.1021/je3002859 | Open via NIST ThermoML (paper paywalled) | `shen-2012-jced-57-2296.tsv` | SLVE liquid x_CO2 and pressure. CH4 + CO2: 9 points, 112.0 to 169.9 K, x_CO2 2.13e-4 to 0.02896, 93 to 2315 kPa. CH4 + N2 solvent: 27. CH4 + C2H6 solvent: 27 | TRC U95: x 1.3e-5 to 7.4e-4; p 14 to 26 kPa |
| Campestrini et al. 2022 | FPE 553, 113292, doi:10.1016/j.fluid.2021.113292 | Open (HAL hal-03519082, CC BY-NC 4.0), fetched | `campestrini-2022-fpe-553-113292.tsv` | Binary CH4 + CO2 at about 4 MPa: 6 SLE (176.6 to 189.6 K, x_CO2 0.0449 to 0.0941) and 12 SVE (190.4 to 205.4 K, y_CO2 0.0414 to 0.1056). With 5 and 10 % N2 solvent: 20 SVE, 11 SLE, 12 SLVE (x and y, 180.0 to 183.6 K) | U(T) 0.004 K, U(p) 0.002 MPa, U(x) and U(y) per point, 0.0006 to 0.0077 |
| Souza, Al Ghafri, Fandiño, Trusler 2020 | FPE 522, 112762, doi:10.1016/j.fluid.2020.112762 | Open via ThermoML (paper paywalled) | `souza-2020-fpe-522-112762.tsv` | SVLE line of CO2 + CH4: 7 points, 203.96 to 215.79 K, 964 to 4858 kPa. Plus VLE (83 points, 218 to 303 K) as context | TRC U95 70 to 71 kPa (SVLE) |
| Riva and Stringari 2018 (the plan's "Riva and Stringari 2018") | Riva, Stringari, IECR 57(11), 4124-4131, doi:10.1021/acs.iecr.7b05224 | Open (HAL hal-01855741 manuscript), fetched | `stringari-riva-2018-iecr-57-4124-table4.tsv` | SLVE x and y of N2-CH4-CO2 (6 points) and N2-O2-CH4-CO2 (6), 124.5 to 146.5 K, 0.52 to 2.11 MPa, x_CO2 356 to 6948 ppm | U(T) 0.2 K, U(p) 0.003 MPa, U(x) per point (k = 2) |
| Riva, Campestrini, Toubassy, Clodic, Stringari 2014 | "Solid-Liquid-Vapor Equilibrium Models for Cryogenic Biogas Upgrading", IECR 53, 17506-17514, doi:10.1021/ie502957x (not JCED) | Open (HAL), fetched | `raw/` only | Modelling paper, no new data; per-dataset AAD tables for CH4 + CO2 | n/a |
| Zhang, Burgass, Chapoy, Tohidi, Solbraa 2011 | JCED 56(6), 2971-2975, doi:10.1021/je200261a | Open via ThermoML | `zhang-2011-jced-56-2971.tsv` | **SVE** frost points in CH4 gas: 17 points, y_CO2 0.108 to 0.542, 191.1 to 210.3 K, 0.293 to 4.446 MPa | TRC U95 0.1 to 0.9 K |
| Le and Trebble 2007 | JCED 52(3), 683-686, doi:10.1021/je060194j | Open via ThermoML | `le-trebble-2007-jced-52-683.tsv` | **SVE** frost points: CH4 + CO2 55 points, y_CO2 0.01 to 0.0293, 168.6 to 187.7 K, 0.962 to 3.008 MPa; + C2H6 24; + N2 24 (TRC label defect, see the TSV header) | TRC U95 1.2 to 1.6 K |
| Xiong, Lin, Jia, Song, Gu 2015 (ternaries with N2 or ethane) | JCED 60(11), 3077-3086, doi:10.1021/acs.jced.5b00059 | Open via ThermoML | `xiong-2015-jced-60-3077.tsv` | **SVE** frost pressures at 153.15 to 193.15 K: 64 binary (y_CO2 0.001 to 0.34), 77 with N2 (up to 5.7 %), 53 with C2H6 (up to 5.9 %); 194 unique points in 322 rows | TRC U95 13 to 974 kPa |
| Sampson et al. 2023 | "Experimental solid-liquid equilibria and solid formation kinetics for carbon dioxide in methane for LNG processing", AIChE J. 69(4), e18001, doi:10.1002/aic.18001 | Open access (CC BY 4.0), Wiley Cloudflare 403; the UWA repository page has no file | none | SLE of CO2 in liquid CH4 at 52 to 500 ppm, LNG temperatures (about 112 K) | Unknown |
| Gao, Shen, Lin et al. 2012 | IECR 51, 9403-9408, doi:10.1021/ie3002815 | Paywalled (ACS; IECR is not a ThermoML journal) | none | CO2 solubility in liquid CH4 and CH4 + N2 (10 to 70 % N2), 83 to 123 K; 9 binary, 31 with N2 | Unknown |
| Kurata and Im 1971 | JCED 16, 295-299, doi:10.1021/je60050a018 | Paywalled (pre-ThermoML) | none | SLVE CH4 + CO2 with x and y: 10 points, 165 to 210 K, 1.90 to 4.85 MPa | Unknown |
| Older sets | Donnelly and Katz 1954 (I&EC 46, 511, doi:10.1021/ie50531a036); Brewer and Kurata 1958 (AIChE J. 4, 317); Sterner 1961 (Adv. Cryog. Eng. 6, 467); Pikaar 1959 (PhD thesis, Imperial College); Agrawal and Laverman 1974 (Adv. Cryog. Eng. 19, 327, doi:10.1007/978-1-4613-9847-9_40); Cheung and Zander 1968; Streich 1970; Preston et al. 1971 (J. Phys. Chem. 75, 2345, doi:10.1021/j100684a020); Voss 1975; Boyle 1987; Kurata 1974 (GPA RR-10) | Paywalled or not digitised | none | Counts and ranges in Riva 2016 Table 3.2. Donnelly and Katz are inconsistent with the other SLVE data (Riva 2014, Langé 2015) | n/a |
| He et al. 2022 (CH4 + C2H6 liquids) | JCED 67, 3222, doi:10.1021/acs.jced.2c00237 | Paywalled; not in ThermoML | none | CO2 solubility in liquid CH4 + C2H6, 148 to 203 K, 0 to 100 % ethane | Unknown |

### 3.3 Research-only cases (not gated)

| Dataset | Reference | Availability | Note |
|---|---|---|---|
| Methane/ethane liquidus | Liu et al. 2022, J. Chem. Thermodyn. 106829, doi:10.1016/j.jct.2022.106829 | Paywalled; not in ThermoML | Eutectic 73.88 K at x_CH4 = 0.680 (plan) |
| Methane/ethane liquidus | Engle et al. 2021, Planet. Sci. J., doi:10.3847/PSJ/abf7d0, arXiv:2103.15978 | Open (CC BY 4.0), not fetched | Eutectic 71.15 ± 0.5 K at x_CH4 = 0.644 ± 0.018. **It disagrees with Liu 2022 by 2.7 K**, so the research-only case has two inconsistent references |
| Ethane solid II to I | Atake and Chihara 1976, Chem. Lett. 5, 683, doi:10.1246/cl.1976.683 | Abstract only (publisher abstract via Crossref) | `atake-chihara-1976-ethane-phase-changes-abstract.tsv`: 89.813 K, 2282 J/mol; triple point 90.341 K; fusion 583 J/mol |

## 4. What G4 and G5 can and cannot be qualified with today

### 4.0 Updated statement, 2026-09-25, after the owner-provided papers

**G4 (CO2 deposition from nitrogen): the frost-point criterion can now be qualified.**
- **Frost-point data.**
  - Sonntag 1960 Table VIII: 64 SVE points, 140 to 190 K, 5 to 100 atm.
  - Smith 1963 Table XIII: 125 values to 200 atm, of which 75 are Smith's own measurements or agree with his.
  - Together they are the only CO2 + N2 binary frost-point set, and the one Maltby 2025 scores (125 points).
- **Realistic target.**
  - Peng-Robinson with a regressed kij (0.012066) reaches 0.36 % MAPD in T on this set (Maltby Table 3), about 0.5 to 0.7 K. tc-PR* reaches 0.38 %, EoS-CG and GERG-2008 0.93 %.
  - A "within 1.5 K AAD" target is therefore met with margin by a PR-class model with a good CO2/N2 kij. The engine's E-PPR78 kij has not been scored yet.
  - Score in T at the given (p, y), as Maltby does.
  - Exclude Smith's two "read from plot" values at 190 K. Prefer Smith over Sonntag where they overlap (140 and 150 K at 80 atm and above, 190 K at 60 atm and above); Smith attributes the difference to a warm bias of about 1 K in Sonntag's apparatus at 190 K.
- **Pure CO2.**
  - The calorimetric sublimation enthalpy is now in hand: 6030 ± 5 cal/mol = 25 236 ± 21 J/mol at 194.67 K and 1 atm (Giauque-Egan Table VII). It replaces the WebBook's vapour-pressure-derived 25.2 kJ/mol as the G4 2 % enthalpy target.
  - Giauque-Egan's 12 vapour pressures (154 to 196 K) and 41 crystal Cp points (15.5 to 189.8 K) are also in hand, as are the Fray-Schmitt ranges and accuracies.
- **Still not qualifiable.** Deposited amount, heat released and exhaustion have no data; they remain consistency-only.

**G5 (CO2 freezing out of liquid methane): the plan's anchor is now in hand.**
- **Davis 1962.**
  - 31 SLV (T, p) points from 211.7 to 97.5 K.
  - 8 vapour compositions along the locus.
  - 11 crystal-point liquid compositions, 0.16 to 20.5 %.
- **Binary x-y pairs.** Kurata-Im Table VI gives 10 of them, interpolated from Davis rather than measured.
- **Low-temperature liquidus.** Gao 2012: 9 binary points (no pressure printed) and 32 points with 10 to 70 % N2 in the solvent, 83 to 123 K. Sampson 2023: 6 SLE points, 52 to 500 ppm, 106 to 123 K, 7 to 10 MPa.
- **Temperature gaps closed.** The 170-177 K and 190-204 K gaps of section 4.2 are covered by Davis (Tables 1, 3 and 4).
- **Realistic targets (Maltby Table 3, PR with regressed kij).**
  - Shen liquidus 0.59 % in T.
  - Davis/Kurata crystal points 0.85 %; Maltby assumed a constant 5 MPa, but Davis measured them on the SLV locus.
  - Pikaar 1.00 %; Gao ternary 1.00 %.
  - Sampson ppm points 5.12 %, where every EoS is at 3 % or worse.
  - A 2 K liquidus target is realistic above about 1000 ppm. At LNG trace levels (Sampson) it is not, for any published model.
- **Still not qualifiable.** Mixture energy.

The original statements of 4.1 and 4.2 follow unchanged. Their "cannot be qualified" items on Sonntag, Smith, Davis, Kurata-Im, Sampson and the Giauque-Egan calorimetry are resolved by 4.0.

### 4.1 G4 (CO2 deposition from nitrogen)

- **Can be qualified.**
  - Pure CO2 sublimation pressure from about 154 K to the triple point, against Span-Wagner eq. (3.12) with its stated ±50 to ±250 Pa, and against Fray-Schmitt and the Giauque-Egan Antoine fit as second and third checks.
  - The P0 target of 5 % (about 0.4 K) is well above the reference uncertainty from 160 K up: ±50 Pa is 1.6 % of p_sub at 160 K and 0.5 % at 170 K.
  - Below about 155 K the reference's own ±50 Pa reaches 3 to 6 % (5.9 % at 150 K), and eq. (3.12) is extrapolated below 154 K. There the gate should use the temperature metric (P0's |ΔT| floor), with Fray-Schmitt CO2-1 (within 0.5 % of eq. 3.12 down to 145 K) as the comparison.
  - The competition with liquid at the CO2-rich end of CO2 + N2: the Fandiño 2015 three-phase line, 4 points, u(T) 0.1 K.
  - Conservation, energy and exhaustion cases: by construction and consistency only (section 2.2).
- **Weakly qualified.**
  - The sublimation-enthalpy target (within 2 % of 25.2 kJ/mol at 195 K) exists only as the WebBook compilation value, which is derived from vapour pressures. The calorimetric Giauque-Egan value (u_r 0.08 %) needs the paper.
  - The Clapeyron slope of eq. (3.12) is an independent consistency check of the model's sublimation enthalpy, not of the calorimetry.
- **Cannot be qualified.**
  - The frost-point criterion ("within 1.5 K of Sonntag/Smith"), for lack of any open CO2 + N2 binary SVE data.
  - The CH4-rich data with 1 to 10 % N2 test the N2 interaction weakly but do not qualify deposition from an N2 carrier.
  - Under the plan's own G4 rule ("if only onset data can be qualified, retain a research/diagnostic result"), not even onset in N2 can be qualified until the Sonntag dissertation or the Springer chapters are obtained.

### 4.2 G5 (CO2 freezing out of liquid methane)

- **Can be qualified.**
  - Pure CO2 melting: Span-Wagner eq. (3.10).
  - The binary liquidus x_CO2(T) in liquid CH4: Shen 2012 (9 SLVE points, 112 to 170 K, down to 213 ppm) and Campestrini 2022 (6 SLE points at 4 MPa, 177 to 190 K). Together they span the mixture-stabilised liquid far below CO2's triple point.
  - The three-phase line p(T): Shen 2012 at 112 to 170 K and Souza 2020 at 204 to 216 K.
  - The vapour-side competition (frost points in CH4 gas): Zhang 2011, Le and Trebble 2007, Xiong 2015 (64 binary points) and Campestrini 2022 (12 SVE points).
  - Composition-domain narrowing with N2 and C2H6 ternaries: Shen, Xiong, Campestrini 2022, Riva-Stringari 2018. The plan's ternaries with N2 or ethane (Xiong 2015) are open through ThermoML.
- **Cannot be qualified.**
  - The plan's anchor set: Davis 1962, the full SLV locus to 97 K with liquid and vapour compositions.
  - Binary SLV liquid and vapour compositions together (Davis 1962, Kurata-Im 1971). Only ternary SLVE x-y pairs are open (Campestrini Table 12, Riva-Stringari). The finite phase-amount check along the binary three-phase line therefore has no open binary composition pair.
  - The LNG-temperature trace liquidus below 213 ppm (Sampson 2023 is open access but bot-blocked).
  - Mixture energy: no calorimetric data exist in any set. Latent energy is qualifiable only through the pure CO2 enthalpies and consistency.
- **Temperature gaps.** Between 170 and 177 K and between 190 and 204 K the binary liquidus has no open points; Davis 1962 fills both.

### 4.3 Metric and target notes for the gate definitions

- **Isothermal data sets.** Several are isothermal: Xiong gives the frost pressure at T and y; Shen gives x at T. The gate needs to score them either as a temperature at given (p, composition) or as ln p / ln x at given T.
- **Conversion.** Near the frost line, d ln y_CO2/dT ≈ ΔH_sub/(R T²) ≈ 0.13 K⁻¹ at 150 K, 0.10 K⁻¹ at 170 K and 0.08 K⁻¹ at 190 K. A 1.5 K frost-point target therefore corresponds to about 12 to 20 % in y_CO2 or in the frost pressure.
- **Published models.** Riva 2016 (Table 4.12) reports AAD in y_CO2 on the Sonntag and Smith sets:

  | Model | Sonntag | Smith |
  |---|---|---|
  | Peng-Robinson, literature kij | 25 % | 38 % |
  | Peng-Robinson, regressed kij | 16 % | 12 % |
  | GERG-2008 | 24 % | 61 % |

  With literature kij, 25 to 38 % is about 2 to 4 K, depending on temperature. On CH4 + CO2 frost points (Table 4.10) the same models give 5 to 34 % AAD by dataset, Le and Trebble the worst at 29 to 34 %.
- **The engine's own performance is unknown.** Its E-PPR78 kij for CO2/N2 (A 113.9, B 212.4 MPa) has not been scored against any of these data.
- **Recommendation for G0/G4/G5.** Keep 1.5 K and 2 K as AAD targets per dataset, with a per-point cap near 2 × U95. Do not score Le and Trebble per point at 1.5 K: their own U95 is 1.2 to 1.6 K. Score composition data in ln x or ln y where the temperature conversion is steep.

## 5. Owner request list

In priority order. "Browser" means the item is open but blocks automated clients; everything else needs subscription access. The last column records what the owner provided on 2026-09-25 (section 9).

| # | Item | DOI / location | Journal / publisher | What is needed | Why (gate) | Received 2026-09-25 |
|---|---|---|---|---|---|---|
| 1 | Sonntag, R. E. (1960), "The Equilibrium of Solid Carbon Dioxide with its Vapor in the Presence of Nitrogen", PhD dissertation, University of Michigan (IP-469) | https://hdl.handle.net/2027.42/7707 (Deep Blue) | Open, **browser** | The PDF (and the OCR text). All CO2 + N2 solid-vapour tables: T, p, y_CO2 and any repeat measurements | G4 frost points: the primary and only possibly open route | **Received** (scan, no text layer); Table VIII transcribed |
| 2 | Smith, Sonntag, Van Wylen (1964), "Solid-Vapor Equilibrium of the Carbon Dioxide-Nitrogen System at Pressures to 200 Atmospheres" | 10.1007/978-1-4757-0525-6_24 | Adv. Cryog. Eng. 9, 197-206, Springer | The data table (about 72 points, 140 to 190 K, 5 to 20 MPa) | G4 frost points at high pressure | **Received as the data source**: Smith's 1963 dissertation (in the file named `sonntag-van-wylen-1962-...pdf`), Table XIII transcribed. The Springer chapter itself was not provided |
| 3 | Maltby, Aasen, Hammer, Wilhelmsen (2025), review of solid CO2 data and EOS | 10.1021/acs.iecr.5c04028 | IECR 64, 24253, ACS, CC BY 4.0, **browser** | The article PDF and the Supporting Information: the data inventory with acceptance or rejection per dataset, any tabulated or digitised data, and the deviation results per solid and fluid EOS | G4 and G5 holdout selection (the plan names this review as the selector); realistic targets | **Received** (article and SI); summary and Tables 2-3 transcribed |
| 4 | Davis, Rodewald, Kurata (1962) | 10.1002/aic.690080423 | AIChE J. 8, 537-539, Wiley | The SLV table: T, p, x_CO2, y_CO2 and the crystal-point liquid compositions | G5 anchor: liquidus, three-phase line and binary phase compositions down to 97 K | **Received**; Tables 1 to 4 transcribed |
| 5 | Trusler (2012), erratum | 10.1063/1.4745598 | J. Phys. Chem. Ref. Data 41, 039901, AIP (one page) | The three corrections (which equations, parameters or tables) | Before any Trusler coefficient is used (P4 solid-model alternative; Fandiño 2015 and Maltby 2025 use the corrected form) | **Received**; note written (eq. 38, eq. 49 signs, Table 6 b1) |
| 6 | Giauque, Egan (1937) | 10.1063/1.1749929 | J. Chem. Phys. 5, 45-54, AIP | The solid Cp table (15 to 195 K), the vapour-pressure table (154 to 196 K) and the calorimetric heat of sublimation at the normal sublimation point | G4 sublimation-enthalpy target (the 2 % row), vapour-pressure holdout, and the P2 crystal record's missing Cp above 189.78 K | **Received**; Tables I, IV, V, VII transcribed. Cp stops at 189.78 K (Table IV) and 190 K (Table V smoothed), so the gap above 189.78 K stays |
| 7 | Sampson et al. (2023) | 10.1002/aic.18001 | AIChE J. 69(4), e18001, Wiley, CC BY 4.0, **browser** | The article and SI: the SLE table (52 to 500 ppm CO2 in liquid CH4) | G5 trace liquidus at LNG temperatures | **Received** (article; no SI); Table 2 transcribed |
| 8 | Fray, Schmitt (2009) | 10.1016/j.pss.2009.09.011 | Planet. Space Sci. 57, 2053-2080, Elsevier | Table 3 (or equivalent): the CO2-1 and CO2-2 temperature ranges, the source data, and any accuracy statement | G4 sublimation holdout ranges; confirms the inferred unit | **Received**; Tables 4-5 CO2 rows transcribed |
| 9 | Smith, Sonntag, Van Wylen (1963) | 10.1007/978-1-4757-0528-7_19 | Adv. Cryog. Eng. 8, 162-173, Springer | Any data table beyond the 1962 set | G4 (lower priority) | **Received** (whole vol. 8); analysis only, no new data |
| 10 | Kurata, Im (1971) | 10.1021/je60050a018 | JCED 16, 295-299, ACS | The SLVE table with x and y (10 points) | G5 binary phase compositions (the phase-amount check) | **Received**; Table VI transcribed (derived from Davis) |
| 11 | Gao, Shen, Lin et al. (2012) | 10.1021/ie3002815 | IECR 51, 9403-9408, ACS | The CO2 solubility tables in liquid CH4 and CH4 + N2, 83 to 123 K | G5 low-temperature liquidus and the N2 domain | **Received**; Tables 2 and 4 transcribed |
| 12 | Siah, Campestrini, Stringari (2025), new solid CO2 Gibbs EOS | 10.1021/acs.jced.5c00260 | JCED 70(7), 2890-2905, ACS | The EOS form and parameters and the solubility comparisons in CH4 and N2 | P4 solid-model option and cross-check (not a holdout) | **Received**; short note only (by instruction) |
| 13 | Lower priority | Agrawal and Laverman 1974 (10.1007/978-1-4613-9847-9_40); He et al. 2022 (10.1021/acs.jced.2c00237); Liu et al. 2022 (10.1016/j.jct.2022.106829); Donnelly and Katz 1954 (10.1021/ie50531a036); Brewer and Kurata 1958 (AIChE J. 4, 317) | Springer, ACS, Elsevier, Wiley | Data tables | G5 breadth; the research-only liquidus | Not received |

The papers behind the ThermoML files (Le and Trebble, Zhang, Xiong, Shen, Souza) are needed only if the authors' own uncertainty statements must replace TRC's assessment. Obtaining 1 or 2 makes the G4 frost-point criterion testable; obtaining 4 completes G5's anchor.

## 6. Corrections to the plan's and P0's source statements

- **Zhang et al. 2011 and Le and Trebble 2007 are solid-vapour frost points in methane gas.** They are listed as holdouts for "CO2 freezing out of liquid methane" and for the P0 target "CO2 liquidus in CH4 within 2 K of Davis / Zhang / Le and Trebble". In a liquidus gate they belong to the vapour-side competition, not to the liquidus. Liquidus holdouts: Davis 1962 (paywalled), Shen 2012, Campestrini 2022, Souza 2020 (three-phase line) and Sampson 2023.
- **Le and Trebble 2007 has no CO2 + N2 binary.** Its N2 set is 1 to 2 % N2 in CH4.
- **"Smith, Sonntag and Van Wylen 1963 and 1964".** The 1963 chapter is an analysis paper; the 200-atmosphere data are the 1964 chapter. Compilations cite Sonntag and Van Wylen as 1961 (the conference) or 1962 (the volume).
- **"Riva and Stringari 2018"** is Riva, Stringari, IECR 57, 4124 (2018), ternary and quaternary SLVE with N2 and O2; HAL lists the authors as Stringari, Riva. The 2014 Riva, Campestrini, Toubassy, Clodic, Stringari paper is IECR 53, 17506, not J. Chem. Eng. Data, and contains no new data.
- **Xiong et al. 2015** is JCED 60, 3077, doi:10.1021/acs.jced.5b00059.
- **Davis 1962 point count.** It is counted as 42 (Riva 2016, Stringari-Riva 2018) or 48 (Campestrini 2023) in compilations; check against the paper.
- **Earlier statements now outdated.**
  - P0 section 3.4 said no numbers were found for Jäger-Span.
  - `P2_DATA_SPINE.md` 4.3 said Trusler and Jäger-Span are behind publisher walls.
  - Both papers are now local (`sources/solid-co2/`, owner-provided). That folder has no `MANIFEST.md` entry yet (manifest section 6.4).

## 7. Cross-check with P4 stage 1 (`P4_SOLID_CO2_MODEL.md`)

P4 stage 1 ran in parallel without web access. Its open source questions are answered as follows.

- **Span-Wagner eq. (3.12) is confirmed.** The test helper `science.thermo.phase.SolidCarbonDioxideReferences` carries the coefficients -14.740846, 2.4327015 and -5.3061778 with exponents 1, 1.9 and 2.9. It also carries T_t = 216.592 K, p_t = 517 950 Pa and the ±50, ±100 and ±250 Pa bands at the 170 and 185 K breaks. All of these agree with the journal page (p. 1521 of the NIST reprint) as transcribed here. The item "check against the paper when acquired" can be closed.
- **The Fray-Schmitt inferences agree.** Its use (bar; CO2-1 below 194.7 K, CO2-2 above) agrees with the numerical inference here. The exact ranges still need the original (request 8).
- **The Trusler erratum exists.** It makes three corrections (section 0, item 6). P4 stage 1 suspects the printed eq. (49), the molar volume on the melting curve, of being inverted. Whether eq. (49) is among the three corrections is **not confirmed**; request 5 settles it. Eqs. (47), (48) and (50) as used in the helper match the local (pre-erratum) PDF.
- **The fusion-enthalpy choice needs liquidus data.** P4 stage 1 defers the choice between 9019 and 8875 J/mol to "the P5 holdouts (Davis 1962, Zhang 2011, Le and Trebble 2007)". Of these, only Davis is liquidus data, and it is paywalled; Zhang and Le and Trebble are frost points. The open liquidus sets that can discriminate the fusion enthalpy through the liquid path are Shen 2012 (9 binary SLVE points, 112 to 170 K) and Campestrini 2022 (6 binary SLE points, 177 to 190 K).

## 8. Sources

**Local, read.**
- The plan, P0, P2 and decision-log documents of this batch.
- The two feasibility reviews in the main checkout.
- `sources/MANIFEST.md`.
- `sources/solid-co2/` (Jäger and Span 2012; Trusler 2011).
- `sources/fray-schmitt-2009/arxiv-2009.02277.pdf`.
- `sources/coolprop/fluids/CarbonDioxide.json`.
- The worktree test resource of the same name, and `pilot_carbon_dioxide.json`.

**Fetched (manifest section 6):**
- Span and Wagner 1996, NIST SRD reprint (https://www.nist.gov/system/files/documents/srd/jpcrd516.pdf).
- NIST TRC ThermoML files for doi:10.1021/je060194j, 10.1021/je200261a, 10.1021/acs.jced.5b00059, 10.1021/je3002859 and 10.1016/j.fluid.2020.112762 (https://trc.nist.gov/ThermoML/).
- HAL: hal-01855741, hal-03519082, hal-01085354, hal-04016509, tel-03510271, tel-01139406.
- Imperial College Spiral hdl 10044/1/23572.
- NIST WebBook CO2 phase-change page (https://webbook.nist.gov/cgi/cbook.cgi?ID=C124389&Units=SI&Mask=4).

**Metadata only (Crossref, OpenAlex, Semantic Scholar, publisher abstract pages):**
- Maltby et al. 2025.
- The Trusler erratum.
- Sonntag and Van Wylen 1962; Smith et al. 1963 and 1964; Davis et al. 1962.
- Sampson et al. 2023; Siah et al. 2025; Gao et al. 2012.
- Atake and Chihara 1976.
- Engle et al. 2021 (arXiv abstract page).
- The Deep Blue record of Sonntag's dissertation (search-engine index; the repository blocked direct access).

## 9. Owner-provided papers (2026-09-25)

**What arrived.** The owner provided items 1 to 12 of the section 5 request list as thirteen PDFs, in `research/2026-09-24-coolprop-low-temperature/sources/solid-co2-holdouts/owner-provided/`. Sizes, SHA-256 and licences are in `MANIFEST.md` section 7; the owner-given SHA-256 values match.

**Method.**
- Medium depth, by the owner's instruction: "not looking for 100 % correct data".
- Key tables were transcribed as printed, from the text layer or from 170 to 220 dpi renders, with one sanity check per table and no digit-by-digit re-verification.
- There was no cross-source reconciliation, except where a check fell out of the sanity check.
- Every TSV header states its table, page, units and uncertainty.

### 9.1 What each file covers

| File | Source, table | Content | Points, ranges | Stated uncertainty |
|---|---|---|---|---|
| `sonntag-1960-dissertation-table8-co2-n2-sve.tsv` | Sonntag 1960 dissertation, Table VIII, p. 100 (scan) | CO2 + N2 SVE: y_CO2(T, p), solid assumed pure CO2 | 64 points: 6 isotherms 140-190 K × 5-100 atm (0.51-10.1 MPa), y 0.00018-0.0778 | Per-point analyzer limits about ±0.00002 in y near 0.002 (Table IX, not transcribed) |
| `smith-1963-dissertation-table13-co2-n2-sve.tsv` | Smith 1963 dissertation, Table XIII, p. 143 (in the file named `sonntag-van-wylen-1962-...pdf`) | CO2 + N2 SVE, consolidated Smith + Sonntag, with an `origin` column (sonntag / both / smith / smith_avg / plotted) | 125 values: 6 isotherms 140-190 K × 5-200 atm (to 20.3 MPa), y 0.00018-0.0778. Smith's own: 69 + 6 agreement points; 2 "read from plot" values at 190 K (exclude) | T ±0.01 K measured, ±0.05 K control; p ±0.2 atm; analyzer ±1 % of full scale (per-point limits in Table XIV) |
| `trusler-2012-erratum-note.md` | Trusler 2012 erratum, one page | Three corrections, with their effect on eq. 49 (section 9.2) | n/a | n/a |
| `giauque-egan-1937-jcp-5-45.tsv` | Giauque-Egan 1937, Tables I, IV, V, VII | Vapour pressure (P in int. cm Hg, with their eq. 1); crystal Cp observed and smoothed; calorimetric ΔH_sub | VP 12 points, 154.196-195.831 K; Cp 41 observed (15.52-189.78 K) + 22 smoothed (15-190 K); ΔH_sub 3 runs, mean 6030 ± 5 cal/mol at 194.67 K, 1 atm (25 236 J/mol with 4.185 J/cal) | Cp ±0.2 % (35-195 K), ±1 % (20 K), ±3 % (15 K); T "several hundredths of a degree"; normal sublimation point 194.67 ± 0.05 K |
| `davis-rodewald-kurata-1962-aiche-8-537.tsv` | Davis 1962, Tables 1-4 | CH4 + CO2 SLV locus p(T); the same with 0.56 % N2 in CH4; vapour y along the locus; crystal-point liquid x | SLV p(T) 31 points, -78.6 to -284.1 °F (211.7-97.5 K), 4.1-706 psia (0.028-4.87 MPa); 8 N2 rows; y 8 points (0.12-11.73 %); x 11 points (0.16-20.50 %, 129.7-201.3 K) | T control 0.02 °C; SLV pressure ±2 psi; crystal points 0.1-0.3 °C (0.4-0.8 °C below 0.5 % CO2) |
| `kurata-im-1971-jced-16-295-table6.tsv` | Kurata-Im 1971, Table VI | CH4 + CO2 SLV with x, y and K, "based on data of Davis" (interpolated pairing, not new measurements) | 10 points, 165.2-210.2 K, 1.90-4.85 MPa, x_CO2 0.0183-0.74 | Tables I-V: T ±0.3 °F, p ±0.5 psia, composition ±3.2 % relative; two rows do not close to 1 as printed |
| `gao-2012-iecr-51-9403.tsv` | Gao 2012, Tables 2 and 4 | CO2 solubility in liquid CH4 (no pressure printed) and in CH4/N2 with 10/30/50/70 % N2, with their PR x_cal | Binary 9 points, 113.2-169.9 K, 172-28 960 ppm; ternary 32 points, 83.15-123.15 K, 124-2023 kPa, 3-900 ppm | T ±0.05 °C, p 0.1 %, GC 1.1-1.6 % relative, repeatability 3 % |
| `sampson-2023-aiche-aic18001-table2.tsv` | Sampson 2023, Table 2 (+ Table 1) | CO2 + CH4 SLE (synthetic, compressed liquid, no vapour), solvent pure CH4 | 6 points, 52.2-500 ppm, 106.3-122.8 K, 6.98-10.10 MPa | u(T) 1.7-2.4 K (k = 1), u(p) 0.08 MPa, u(x) 1 % |
| `fray-schmitt-2009-pss-57-2053-co2-tables4-5.tsv` | Fray-Schmitt 2009, Tables 4-5, CO2 rows | Ranges, point counts and accuracies of CO2-1 and CO2-2; coefficients | CO2-1: 40-194.7 K, 161 points, degree 5, SD 14.5 %, max 63 % (±9 % for 120-194.7 K). CO2-2: 194.7-216.58 K, 46 points, degree 2, SD 0.33 %, max 0.72 %. P in bar confirmed; coefficients equal the Lisse 2020 reproduction | As in the previous column |
| `maltby-2025-summary.md`, `maltby-2025-iecr-64-24253-tables2-3.tsv` | Maltby 2025 + SI | Data inventory (Table 2, 47 rows) and MAPD in T per dataset and fluid EoS (Table 3, 37 rows + 4 averages); kij | See 9.3 | Data "typically" 1-25 kPa and 0.1 K |
| `siah-campestrini-stringari-2025-note.md` | Siah 2025 | Short note: the new Gibbs solid EoS and its deviations | n/a | n/a |

The vol. 8 chapter (Smith, Sonntag, Van Wylen 1963, "Analysis of ...", pp. 162-173) is an analysis paper. Its Table I "EXP" enhancement factors are Sonntag's data; check: 0.00042 × 5 atm / 0.00184 atm = 1.141 against a printed 1.1414 at 140 K and 5 atm. It was not transcribed. The real Sonntag-Van Wylen 1962 chapter was not provided; its data are the Sonntag dissertation's.

### 9.2 Trusler erratum finding

The erratum (J. Phys. Chem. Ref. Data 41, 039901) makes three corrections:
1. **Eq. (38)**, the anharmonic Helmholtz term, lacked a factor θ_D,0.
2. **Eq. (49)**, the molar volume on the melting curve, had its parameter signs printed wrong. Corrected: d7 = -0.160433, d8 = +0.018643, d9 = -0.001582.
3. **Table 6**: b1 = 1.151, not 1.15, and several symbol typos; the table is reprinted in full.

Consequences:
- **Equation 49 is among the corrections.** The fault is the signs, not a reciprocal. P4 stage 1's reciprocal reading agrees with the corrected equation within 0.15 % up to 270 K, the range it compared, but differs by 1.5 % at 400 K and 10.7 % at 800 K.
- Equations (47), (48) and (50) are unchanged.
- The local `solid-co2/trusler-2011-jpcrd-40-043105.pdf` carries the uncorrected eq. (38) and b1 = 1.15.

### 9.3 Maltby 2025 headline numbers

MAPD is taken in equilibrium temperature at the measured p and composition. All kij are regressed on VLE, with PR CO2/N2 0.012066 and CH4/CO2 0.123. The solid is Trusler with the erratum; h_melt,tr = 8875 J/mol.

**Frost points.**
- CO2/N2, Sonntag + Smith (125 points): PR 0.36 %, SRK 0.36 %, tc-PR* 0.38 %, EoS-CG and GERG 0.93 %, the SAFT variants 1.5-1.9 %.
- CO2/CH4 by dataset: PR 0.30-1.29 %. Le-Trebble is the worst at 1.29 %.
- SVE average: PR 0.60 %.

**Liquidus.**
- CO2/CH4: PR 0.59 % (Shen), 0.85 % (Davis crystal points, pressure assumed 5 MPa), 1.00 % (Pikaar) and 5.12 % (Sampson ppm; EoS-CG best there at 2.97 %).
- CO2/CH4/N2 (Gao): 1.00 %.
- CO2/N2 in liquid N2 (Yakimenko): 0.57 %.
- SLE average: PR 1.35 %.

**Overall.** Table 3 gives PR 1.09 %, the lowest of all models. The abstract's "PR ... 1.4%" matches PR's SLE average, not Table 3's overall row; this is noted, not resolved.

**Observations from this reading, not reconciled.**
- Maltby's "Kurata 1974 GPA" SLE row is numerically Davis Table 4.
- Their 125-point CO2/N2 set equals Smith's full Table XIII, so it probably includes Smith's two "read from plot" values.
- Gao's "Davis" comparison values are Davis Table 4. Gao prints 29.0 × 10^3 ppm where Davis prints 2.94 %.

### 9.4 Open after this step

- **Smith 1964 Springer chapter.** Not in hand. Smith's dissertation carries its data, so there is no need to request it.
- **Siah 2025.** Read for comparison only, as instructed; its parameters were not transcribed.
- **Kurata-Im Tables I to V** (ternaries with ethane, propane and n-butane): not transcribed (outside the CO2 + CH4 and CO2 + CH4 + N2 scope).
- **Request item 13.** Not received.
- **G4/G5 wiring.** None of these files is wired into a test yet. A G4 gate would read the Smith TSV: exclude `plotted`; prefer `smith`/`both`/`smith_avg` over `sonntag` where both exist, or use Sonntag's own TSV for the low-pressure points.

# Maltby et al. 2025: summary for G4 and G5

**Source.** T. W. Maltby, A. Aasen, M. Hammer, Ø. Wilhelmsen, "Review of Experimental Data and Evaluation of Equations of State for Modeling Formation of Solid CO2 in CCS and Natural Gas Applications", Ind. Eng. Chem. Res. 64(50), 24253-24263, published 2025-12-05, doi:10.1021/acs.iecr.5c04028. CC BY 4.0, as stated on the first page. Supporting Information in `maltby-2025-iecr-64-24253-si.pdf`.

**Method of this summary.** Read from the text layer on 2026-09-25. Numbers are as printed, with one sanity check, and were not re-verified (the owner is not looking for 100 % correct data). Tables 2 and 3 are transcribed in full in `maltby-2025-iecr-64-24253-tables2-3.tsv`.

## What they did

- **Solid CO2 model.** Two solid EoS were tried: Trusler 2011 with the 2012 erratum, and Jäger-Span 2012. They give "nearly identical results"; Trusler gave slightly lower deviations and is used throughout.
- **Anchoring the solid to each fluid EoS.**
  - The solid's Gibbs energy is shifted to match each fluid EoS at T_tr = 216.592 K (eqs. 3 to 5).
  - The melting entropy is set to h_melt,tr / T_tr with **h_melt,tr = 8875 J/mol**. This bears on the P4 choice between 9019 and 8875 J/mol.
  - Ideal-gas parts come from EoS-CG for every fluid EoS.
- **Fluid EoS compared.** SRK, PR, tc-PR* (Twu alpha refitted with sublimation data, Table 1: L 1.1241, M 0.9999, N 0.6074, c -1.5738 cm3/mol), PC-SAFT, PCP-SAFT, SAFT-VR Mie, GERG-2008 and EoS-CG.
- **Binary parameters.** All kij were regressed on the same VLE data (SI Table SI.1):
  - PR/tc-PR: CH4/CO2 0.123000 and CO2/N2 0.012066.
  - SRK: 0.121000 and -0.002072.
- **Metric.** MAPD of the equilibrium **temperature**, eq. 8: T is predicted at the measured composition and pressure. They state that the composition and pressure MAPDs give "very similar information".
- **Stated data quality.** Measurement uncertainty is "typically" 1 to 25 kPa and within 0.1 K, "with some notable exceptions".

## Datasets for CO2 + N2 and CO2 + CH4 (their Table 2)

There is no per-dataset accept or reject flag. Every Table 2 set is scored in Table 3 except the SVLE-locus sets without compositions (plotted only). Exclusions and caveats:

- **Excluded.**
  - Solubility studies that report no pressure: refs 69-75 (Cheung-Zander 1968, Streich 1970, Brady GPA RR-62 1982, Amamchyan 1973, Fedorova 1940, Preston 1971, Rest 1990).
  - Xu et al. 2021 (CO2 in liquid CH4/H2), for "unreasonable disagreement with all of the model combinations".
- **Le and Trebble 2007.** Kept in Table 3, but left out of the SI residual plots as "inconsistent" per Riva et al. 2014.
- **Assumed pressures.** (a) marks a pressure assumed constant and (b) one assumed equal to the SVLE curve.
- **Possible mislabelled Davis data.** The "SLE56 (a) Kurata 1974 GPA RR-10" row (11 points, 0.16 to 20.5 %, 129.7 to 201.3 K, pressure assumed constant at 5 MPa) matches Davis, Rodewald and Kurata 1962 Table 4 exactly: the same 11 crystal points, -226.3 °F = 129.7 K and -97.4 °F = 201.3 K. Davis describes these as bubble-point liquids on the SLV locus, not at 5 MPa. Flagged here, not reconciled.

| Mixture | Type | Source (ref) | Points | x_CO2 % | T / K | p / MPa |
|---|---|---|---|---|---|---|
| CO2/N2 | SVE | Smith, Sonntag, Van Wylen 1963 + Sonntag, Van Wylen 1962 (54, 55) | 125 | 0.018-7.78 | 140-190 | 0.5066-20.265 |
| CO2/N2 | SLE | Yakimenko 1975 (57) | 29 | 0.0002-0.0214 | 78-115 | 3.6477-9.1192 |
| CO2/N2 | SVLE locus | Fandiño 2015 (68) | 4 | NA | 212.7-214.9 | 4.823-13.018 |
| CO2/CH4 | SVE | Zhang 2012 thesis (48) | 17 | 10.8-54.2 | 191.1-210.3 | 0.293-4.446 |
| CO2/CH4 | SVE | Le, Trebble 2007 (47) | 55 | 1.0-2.93 | 168.6-187.7 | 0.9621-3.0082 |
| CO2/CH4 | SVE | Pikaar 1959 (41), set 1 | 38 | 1.0-20.0 | 158.2-210.5 | 0.1966-4.8322 |
| CO2/CH4 | SVE | Pikaar 1959 (41), set 2 | 66 | 0.0265-58.0 | 132.6-210.1 | 0.156-4.7896 |
| CO2/CH4 | SVE | GPSA 1998 + ZareNezhad 2006 (52, 53) | 43 | 2.0-16.0 | 170.1-199.8 | 0.6893-2.7571 |
| CO2/CH4 | SVE | Agrawal, Laverman 1974 (46) | 42 | 0.12-10.67 | 137.5-198.1 | 0.1724-2.7855 |
| CO2/CH4 | SVE | Xiong 2015 (51) | 64 | 0.1-34.07 | 153.2-193.2 | 0.219-3.038 |
| CO2/CH4 | SLE | Sampson 2023 (4) | 6 | 0.0052-0.05 | 106.3-122.8 | 6.98-10.1 |
| CO2/CH4 | SLE (a) | Kurata 1974 GPA (56) | 11 | 0.16-20.5 | 129.7-201.3 | 5.0 (assumed) |
| CO2/CH4 | SLE | Shen 2012 (49) | 9 | 0.0213-2.896 | 112.0-169.9 | 0.093-2.315 |
| CO2/CH4 | SLE | Pikaar 1959 (41) | 23 | 0.032-20.0 | 113.5-201.3 | 1.0132-9.7272 |
| CO2/CH4/N2 | SLE | Gao 2012 (50) | 32 | 0.0003-0.09 | 83.1-123.1 | 0.124-2.023 |
| CO2/CH4/N2 | SLE | Shen 2012 (49) | 27 | 0.0225-2.77 | 112.0-169.9 | 0.155-3.15 |
| CO2/CH4 | SVLE locus | Souza 2020 (65); Langé 2015 (66); Davis 1962 (43); Donnelly-Katz 1954 (42); Sterner 1961 (44) | 7; 15; 30; 20; 5 | NA | 97.5-216.0 overall | 0.0283-5.0314 overall |

The ternaries with N2 in CH4 are Le-Trebble (24 points), Agrawal-Laverman (19), Xiong (77), Riva-Stringari 2018 SVLE (6) and Shen/Gao (above); their ranges are in the TSV.

## Reported deviations (Table 3, MAPD in % of T, as printed)

| Data set | EoS-CG | GERG | tc-PR* | PR | SRK | SAFT-VR Mie | PC-SAFT | PCP-SAFT |
|---|---|---|---|---|---|---|---|---|
| **CO2/N2 SVE, Sonntag + Smith (54, 55)** | 0.93 | 0.93 | 0.38 | **0.36** | 0.36 | 1.52 | 1.89 | 1.67 |
| **CO2/N2 SLE, Yakimenko (57)** | 2.45 | 2.45 | 1.23 | **0.57** | 1.87 | 1.74 | 2.68 | 6.22 |
| CO2/CH4 SVE, Zhang (48) | 0.34 | 0.34 | 0.36 | 0.34 | 0.37 | 0.32 | 0.77 | 0.33 |
| CO2/CH4 SVE, Le-Trebble (47) | 1.38 | 1.38 | 1.26 | 1.29 | 1.25 | 1.33 | 1.93 | 1.40 |
| CO2/CH4 SVE, Xiong (51) | 0.48 | 0.48 | 0.48 | 0.45 | 0.48 | 0.43 | 1.26 | 0.48 |
| CO2/CH4 SVE, Agrawal-Laverman (46) | 0.99 | 0.99 | 1.04 | 0.97 | 1.06 | 0.95 | 1.04 | 0.85 |
| **CO2/CH4 SLE, Sampson (4)** | 2.97 | 2.98 | 5.75 | **5.12** | 5.72 | 10.32 | 8.53 | 3.67 |
| **CO2/CH4 SLE, Shen (49)** | 0.45 | 0.45 | 0.81 | **0.59** | 0.94 | 3.79 | 4.01 | 0.90 |
| CO2/CH4 SLE (a), Kurata GPA = Davis Table 4 (56) | 0.50 | 0.50 | 0.88 | 0.85 | 0.94 | 2.08 | 2.67 | 0.67 |
| CO2/CH4 SLE, Pikaar (41) | 0.70 | 0.70 | 1.03 | 1.00 | 1.00 | 1.63 | 2.22 | 0.79 |
| CO2/CH4/N2 SLE, Gao (50) | 1.22 | 1.21 | 1.42 | 1.00 | 1.60 | 4.38 | 4.65 | 1.73 |
| Average SVE | 0.65 | 0.76 | 0.64 | 0.60 | 0.65 | 0.68 | 1.13 | 0.67 |
| Average SLE | 2.63 | 1.95 | 1.61 | 1.35 | 1.96 | 6.30 | 6.81 | 2.44 |
| Average SVLE | 1.30 | 1.59 | 1.78 | 1.44 | 1.54 | 2.58 | 4.58 | 1.79 |
| Average, all | 1.59 | 1.42 | 1.28 | 1.09 | 1.38 | 3.37 | 4.18 | 1.63 |

**Headline for a Peng-Robinson-type engine (E-PPR78 is one), with regressed kij:**

- **CO2 + N2 frost points.** 0.36 % of T, about 0.5 to 0.7 K at 140 to 190 K. Figure 2: "PR gives the best agreement ... at pressures exceeding 5 MPa".
- **CO2 + CH4 frost points.** 0.3 to 1.3 % by dataset.
- **CO2 + CH4 liquidus.** 0.59 % for Shen (about 0.7 to 1 K), 0.85 to 1.00 % for the older sets, and 5.12 % (about 6 K) for Sampson's ppm-level LNG points. There every fluid EoS is at 3 % or worse, EoS-CG best at 2.97 %.
- **CO2 + N2 liquidus (Yakimenko, liquid N2).** 0.57 %.

**Internal inconsistency, as printed and not resolved.** The abstract and conclusion give standard PR an "overall MAPD of 1.4%"; Table 3 gives PR 1.09 overall and 1.35 for SLE. The text's "PR gives an even lower MAPD of 1.4% for SLE" matches the SLE average. The 1.4 % in the abstract is probably the rounded SLE figure or a CCS-subset figure. Use the Table 3 numbers.

**Other findings.**
- **Pure CO2.** They compare against Trusler's auxiliary equations ("melting curve within 2.5 %, sublimation curve below 1-2 %").
  - Plain PR and SRK miss the melting pressure by more than 20 % without a volume shift, but the melting temperature by less than 1 % up to 20 MPa (SI Fig. SI.4).
  - Original tc-PR misses the sublimation pressure by about 15 % at 140 K; the refit brings it within experimental uncertainty.
- **CO2/O2 SLE.** EoS-CG is unreliable (errors above 10 K).
- **ppm-level mixtures.** Every EoS exceeds 2 % MAPD for ppm mixtures (Riva-Stringari 2018), which they attribute to measurement uncertainty.

**SI content.** Table SI.1 (kij), Table SI.2 (tc-PR saturation MAPD before and after the refit) and figures. There is **no consolidated data table**, so nothing was transcribed from the SI beyond the two kij quoted above.

**Note on the CO2/N2 SVE set.** Its 125 points and 0.018 to 7.78 % range equal the full Table XIII of Smith's 1963 dissertation (`smith-1963-dissertation-table13-co2-n2-sve.tsv`). That table includes Sonntag's 48 lower-pressure values and the two 190 K values Smith marks as "read from plot ... not determined experimentally". Maltby's CO2/N2 score therefore probably includes those two non-measurements (inferred from the counts, not stated by Maltby).

# Source manifest: unified multiphase thermo, P0

Batch `2026-09-24-coolprop-low-temperature`, work package P0. Fetched 2026-09-24 by the P0 agent in worktree `claude/coolprop-multiphase-thermo-37f6b0`. Research material, git-ignored. The P0 report is `documentation/2026-09-24-coolprop-low-temperature/P0_BASELINE_COVERAGE_AND_SOURCES.md`.

Every hash is `sha256sum` of the stored file. The GitHub downloads were also checked against the upstream Git blob hashes: `git hash-object` of `thermo.inp`, `Methane.json` and `GERG2008.cpp` equals the blob SHA the GitHub contents API reports at the pinned commit.

Licences. The owner has stated that the project is non-commercial, so non-commercial terms are admissible. They are still recorded here so a later distribution decision can be made per file. None of these files is on the mod's classpath.

## 1. Fetched files

### 1.1 NASA CEA thermodynamic database

- **Source:** https://github.com/nasa/cea at commit `3f4441d28a02fccbb140e1a028d9902390981389` (2026-09-21, default branch `main`).
- **Raw URL pattern:** `https://raw.githubusercontent.com/nasa/cea/<commit>/<path>`.
- **Licence:** Apache-2.0 (`LICENSE.txt`). `NOTICE.txt` names NASA TP-2002-211556 as the source of `data/thermo.inp`.

| File | Bytes | sha256 | Purpose |
|---|---|---|---|
| `nasa-cea/thermo.inp` | 1234323 | `fa7746572952d74e249e818a82a35c113829742fb421a308e167185528884363` | NASA 9-coefficient ideal-gas polynomials and formation enthalpies; the ideal-gas spine of plan section 4 (D1) and the bounded `nasa9` record type of P2 |
| `nasa-cea/LICENSE.txt` | 9170 | `a60026be9384468d074c19425e552f1bb4a6f507f805e55a96fad586716168ed` | Apache-2.0 text |
| `nasa-cea/NOTICE.txt` | 713 | `03b64364559d540d97615c31675a69e8eb1965e30de8859a2f8c5c9a38ecb55e` | Data provenance notice |
| `nasa-cea/extract-species.mjs` | 5224 | `b588f4738d782c48bec3931119aa071bb64d8e872f8e97f72a3bb1e5495086d0` | Extraction script (Node 22). Run `node extract-species.mjs > cea-species-extract.txt` in this folder |
| `nasa-cea/cea-species-extract.txt` | 22987 | `5a7760f890cb895d7392c7b89e0842c75248c004c81f79d4660d1857f3a54e4e` | Derived output. Covers N2, O2, Ar, CO2, NH3, H2, H2O, CH4, C2H6, C2H4, C3H6, C3H8, n-/i-C4H10, n-/i-C5H12, H2S and CO, plus the condensed H2O(cr), H2O(L) and (L) records. Each species has its summary line (temperature intervals, stored ΔfH°(298.15 K), H298−H0, and Cp, S° and H at 298.15 K evaluated from the polynomial) and its verbatim record |

Findings recorded at extraction:

- **Lower bound of the gas polynomials.** The gas polynomials of C2H6, C3H6, C3H8, n-C4H10, i-C4H10, n-C5H12, i-C5H12 and H2S start at 300 K, not 200 K. Every other listed gas starts at 200 K. None of them reaches the cryogenic pilot states (N2 63 K, CH4 90.7 K, C2H6 90.4 K). Below 200 K (300 K for ethane), the ideal-gas part therefore needs another bounded source: the CoolProp alpha0 of the same fluid, or the existing nitrogen `below` segment.
- **298.15 K for the 300 K species.** For these species the extract's Cp, S° and H at 298.15 K are extrapolated 1.85 K below the first interval, and are marked as such.
- **Check against the stored value.** The polynomial H(298.15 K) reproduces the stored ΔfH° within 2.2 J/mol for every species.

### 1.2 CoolProp fluid and mixture files

- **Source:** https://github.com/CoolProp/CoolProp at revision `ae81610e7d23efc57f9d051c8e70a4d66e87537f` (2026-06-27, "release: bump version to 8.0.0"). This is the build the review probe used.
- **Raw URL pattern:** `https://raw.githubusercontent.com/CoolProp/CoolProp/ae81610e7d23efc57f9d051c8e70a4d66e87537f/dev/fluids/<Name>.json`.
- **Licence:** MIT (`coolprop/LICENSE`, 1103 bytes, `9bf835333ef602af4cb19338b9f9d43671e174fa029b00280e9bdba6ea4719b2`).
- **Purpose:** the reference EOS coefficients for the Java oracle port (P1 item 1, D4), plus the critical and triple constants, melting lines, ancillaries and transport models.
- **Name check:** every requested name resolved (HTTP 200); no directory listing was needed.
- **Test copies of the oracle:** the six copies under `src/test/resources/science/thermo/coolprop/` (Nitrogen, Methane, Ethane, CarbonDioxide, Hydrogen, Water; tracked since the P1 oracle commit `6ded7c7`) are byte-identical to these files.

| File | Bytes | sha256 |
|---|---|---|
| `coolprop/fluids/Nitrogen.json` | 125567 | `791432d1685eafcc75b43e776e8fe4e30f7cf73a2a73b67de0c1a94c57a2b9aa` |
| `coolprop/fluids/Oxygen.json` | 129870 | `2296d3164338a9fadeb2e7a04c98fb5c403a07851c3b59bb7634887043c44fc3` |
| `coolprop/fluids/Argon.json` | 122025 | `3dd2932a954084990add53fe583d48ff2d5ab99f9efa57b7f997377d7ea5322a` |
| `coolprop/fluids/CarbonDioxide.json` | 125311 | `08e27e1a5a6029e508976496b9d1fd4f7f0caa2c46fd35c84b7bf520260a5217` |
| `coolprop/fluids/Ammonia.json` | 137902 | `4d451fadbe6eb1c2441169c599890cf7cd6edc818918f23959e2afb20a881e14` |
| `coolprop/fluids/Hydrogen.json` | 117963 | `515ef2140273fe8f8012e202158af11b5710c02c6af1caac2193669447b1ca25` |
| `coolprop/fluids/Water.json` | 138004 | `85021d3803d2b02edb1d3e90bd60f4eee4a70380e06ed6b7b44e57716b4c2266` |
| `coolprop/fluids/Methane.json` | 124532 | `534b6a8906be63dd491972ddb29f2856c565c2c209320044858e67b90cb7a171` |
| `coolprop/fluids/Ethane.json` | 137250 | `ad4199d0665196aa99203515feb33db04c3ce6a3271c9536de6efb13a4f8803e` |
| `coolprop/fluids/Ethylene.json` | 132190 | `e7cb6603155fa25cbea699c7a9e2c095fbc2db26ebe6dbb640b6f1e86d469846` |
| `coolprop/fluids/Propylene.json` | 143435 | `d2005a1cf7468cd2c5b8dd2c4d0eadfe75a4ec8030d416862af40817f73f62b2` |
| `coolprop/fluids/n-Propane.json` | 145810 | `9a21895b593b610ac075b35c0b49b02dabdbb6cc3fda55052aafdc27f19164b1` |
| `coolprop/fluids/n-Butane.json` | 134970 | `0f2e3357b545dea1eb5f53281ccf418566ffa9a4af5a4d8142539ea47f8538c6` |
| `coolprop/fluids/IsoButane.json` | 140972 | `6c504539fc2bc3b4630730d55e6896770645f42b51ba875aaf162c5cfd68bb1f` |
| `coolprop/fluids/n-Pentane.json` | 143088 | `6e8632aa2c3c2ac2a91a9352788643928d42481162402871e854b2d4e1352917` |
| `coolprop/fluids/Isopentane.json` | 144753 | `159027325ffb1ab9a505a12ff39e8bd39b6d847adb9ebb6d3e90061f19a21fae` |
| `coolprop/fluids/HydrogenSulfide.json` | 117092 | `0eb50cff8df5b273bbda4c649f716342479166c359f09244f7706236ef0ade66` |
| `coolprop/fluids/CarbonMonoxide.json` | 118472 | `fef360d64882a7bee03f3ed956ae4b1e04cd86cfd77ad2886ab6449931443bc6` |
| `coolprop/mixtures/mixture_binary_pairs.json` | 207579 | `9670c9440cd83cd68bb4ace3c655dfb52293ba727ed75bd1f82810d93239be57` |
| `coolprop/mixtures/mixture_departure_functions.json` | 10377 | `af00484c631a84e04f92cf8baf8f3468ce75dfc401e85d5c8108345c21090f35` |

The two mixture files are the GERG-2008 / EOS-CG reducing parameters and departure functions (oracle mixtures, later option).

Derived files:

| File | Bytes | sha256 | Content |
|---|---|---|---|
| `coolprop/summarize.mjs` | 2058 | `005202e9ff15976b0804b359fbeda9d83c6a03cd682247a09751b2e8a4792a14` | Summary script. Run `node summarize.mjs > coolprop-fluid-summary.txt` |
| `coolprop/coolprop-fluid-summary.txt` | 9859 | `94185663b22ed0eae3baecce03b2656f9b173d6fd77355074904779f41a95a05` | Per fluid: EOS reference, Ttriple, T_max, p_max, critical and triple states, alpha0 and alphar term families, melting line, pS ancillary, transport models |
| `coolprop/hformation-atct-9b35f538.json` | 8547 | `3a01b2c0e2755cdd5d2b11320e401ae1edf508574ebd69ee5b134870683fc219` | `INFO.STANDARD_STATE` (ATcT 1.220 ΔfH°(298.15 K) with uncertainty) of the 18 fluids, read at commit `9b35f538c64df96031553cfbf57a284ce475e8aa`, plus that commit's sha256 of each source file |

Findings recorded at fetch time:

- **ATcT enthalpies of formation are not in 8.0.0.** The ATcT 1.220 values (the plan's "as carried in CoolProp's fluid files") are absent from `ae81610e`. They were added on master by commit `9b35f538c6` (2026-08-22, "standard enthalpy of formation from ATcT (HFORMATION)", PR #3309). The `EOS` blocks of all 18 fluids at that commit are identical to 8.0.0, so the ATcT values can be adopted as an overlay without moving the oracle's parity target.
- **The file holds 888 pair entries, not 194 GERG pairs.** Among them are refrigerant and estimated pairs, and 28 departure functions.
- **Pilot pairs are present.** All six pilot binaries carry GERG or EOS-CG parameters:
  - Kunz 2012: N2/CH4, N2/C2H6, CH4/C2H6 and CO2/CH4 with departure functions; CO2/C2H6 without one.
  - CO2/N2 is labelled "Gernert-Thesis-2013" in the CoolProp file, but the values it ships are GERG-2008's (WP8 check of 2026-09-24: betaT, gammaT, betaV, gammaV, F and every departure-function coefficient agree with `nist-aga8/GERG2008.cpp` to 1.4e-10, rounding), with a departure function.
- **No NH3 pairs.**
- **CoolProp `T_max` is an extrapolation limit, not the published validity range.** Examples: CO2 2000 K (Span and Wagner valid to 1100 K) and N2 2000 K (Span et al. valid to 1000 K).
- **Transport at 8.0.0 differs from the review's list.** Methane viscosity is Quiñones-Cisneros 2006 and conductivity Friend 1989 (hardcoded), not Sotiriadou 2025. Ethylene and CO have no transport model, and H2S has viscosity only.

### 1.3 NIST AGA8 / GERG-2008

- **Source:** https://github.com/usnistgov/AGA8 at commit `3bdb9ab8ff317c618b0b59d1b704c2c86ddc5fce` (2025-02-20, branch `master`).
- **Raw URL pattern:** `https://raw.githubusercontent.com/usnistgov/AGA8/<commit>/<path>`.
- **Licence:** NIST notice (`LICENSE`). Works of NIST employees are not subject to US copyright. Use, copying, modification and distribution are permitted provided the notice and the warranty disclaimer appear in all copies.

| File | Bytes | sha256 | Purpose |
|---|---|---|---|
| `nist-aga8/GERG2008.cpp` | 93682 | `901c03cd98263acae8d10480f9ada67ea9bbdf1a5a1b2f1b4a862a493266dec1` | GERG-2008 reference implementation (`SetupGERG()` constants) for a later mixture oracle |
| `nist-aga8/GERG2008.h` | 689 | `c418414a953d4188976d3b8257fef993fd36ff6c27e83c54ff4326f60cdb4faf` | Header |
| `nist-aga8/GERG2008_test_01.cpp` | 5047 | `d736b3ea8d9bf1349196abb4c9a969145c3b5e60893bf0654efdbf2fadb9720c` | Reference test values |
| `nist-aga8/LICENSE` | 1629 | `2b48e300fe3d28ee4fc748c3572ac857ada60bbb2ed18a20241b1f2c8667e307` | Repository notice |
| `nist-aga8/README.md` | 234 | `b01f58573e6f38c910622ff721e64b0f9aee4de74158e658fbb4c2c7c4fa97c1` | Repository readme (contact) |

### 1.4 E-PPR78 group-contribution kij

| File | Bytes | sha256 | Source, licence, purpose |
|---|---|---|---|
| `e-ppr78/lasala-2020-intechopen-71837.pdf` | 664379 | `df7f7b81334d02146ecd86136538d887c58161d5dc8db8a9c0f567e276669258` | Lasala, Piña-Martinez, Jaubert 2020, "A Predictive Equation of State to Perform an Extending Screening of Working Fluids for Power and Refrigeration Cycles", IntechOpen, doi:10.5772/intechopen.92173. Fetched from https://www.intechopen.com/citation-pdf-url/71837 (redirects to https://api.intechopen.com/chapter/pdf-download/71837.pdf). Licence CC BY 3.0. Gives the E-PPR78 kij(T) formula and the group decomposition |
| `e-ppr78/jaubert-2022-fpe-560-113456-hal-03679277.pdf` | 2892459 | `7c43d85dc50e79dc8914520c8ca954acb552e9bde3831d50f62a27914aea8842` | Jaubert, Qian, Lasala, Privat 2022, FPE 560, 113456, author manuscript at https://hal.univ-lorraine.fr/hal-03679277/document. Licence CC BY-NC-ND 4.0 (HAL record). The current 40-group E-PPR78 definition and its error statistics; it states that the Akl/Bkl values are in Table S4 of its Supporting Information |

**Failure: the Table S1 group table.** The review's route does not hold: the chapter itself contains no parameter table. It says the Akl/Bkl values "are reported in Table S1 of Supplementary Material of [39]". Reference 39 is Xu, Jaubert, Privat, Arpentinier 2017, IECR 56, 8143, doi:10.1021/acs.iecr.7b01586. Three attempts were made:

1. The chapter page and chapter PDF: no table.
2. The ACS supporting information, `https://pubs.acs.org/doi/suppl/10.1021/acs.iecr.7b01586/suppl_file/ie7b01586_si_001.pdf`: HTTP 403 behind a Cloudflare bot check. It was not bypassed.
3. HAL record hal-01703369: metadata only, no file.

Alternatives, not fetched:

- The Supporting Information of Jaubert et al. 2022 (Elsevier `mmc` file for doi:10.1016/j.fluid.2022.113456), whose Table S4 holds the current 40-group Akl/Bkl matrix. ScienceDirect supplementary files are normally free to download in a browser; the owner or a browser session should fetch it. (Resolved 2026-09-25: the owner provided it, section 5.)
- The ACS SI of Xu et al. 2017, through a browser.

A related gap: the review lists Xu et al. 2015 (IECR 54, 2816; pseudo-component kij) as "HAL hal-01267209, open". The HAL record has no file; that paper is not open through HAL either.

### 1.5 Fray and Schmitt 2009 sublimation coefficients

| File | Bytes | sha256 | Source, licence, purpose |
|---|---|---|---|
| `fray-schmitt-2009/arxiv-2009.02277.pdf` | 4426039 | `a5d1b01a00c9126ae48fe64f9dbe2b27cc3f9286b8b222c1a2c872091c4641ea` | Lisse et al., "On the Origin & Thermal Stability of Arrokoth's and Pluto's Ices", arXiv:2009.02277 v1, https://arxiv.org/pdf/2009.02277 (Icarus 2020, doi:10.1016/j.icarus.2020.114072). Licence: arXiv non-exclusive distribution licence 1.0; keep as a local research copy only |

Its SOM Table 2 (appendix) reproduces the Fray and Schmitt 2009 (P&SS 57, 2053) ln P = A0 + ΣAi/T^i coefficients, and the appendix gives the Feistel and Wagner water-ice form. These are holdouts for P4, not inputs; no numbers were transcribed, as instructed. Text extraction of the appendix shows at least one garbled coefficient (an `-s1.210x10+8` token). Any later transcription must be checked against the original P&SS table, and species ranges read from the table itself.

### 1.6 IAPWS releases

- **Source:** https://iapws.org/technical-guidance/release/<name>, downloaded through the `.download` link, which resolves to `https://iapws.org/public/documents/...`.
- **Licence:** "Publication in whole or in part is allowed in all countries provided that attribution is given to IAPWS".

| File | Bytes | sha256 | Document |
|---|---|---|---|
| `iapws/IF97-Rev.pdf` | 406950 | `c92f887e989cbf074af1fa982083dc54195d57691eab4fbc950ef6098d4cf1f4` | IAPWS R7-97(2012), Revised Release on IAPWS-IF97. Range 273.15–1073.15 K to 100 MPa, and 1073.15–2273.15 K to 50 MPa (region 5). Uncertainty is given only as figures (section 12, Figs. 3–6) |
| `iapws/MeltSub.pdf` | 170418 | `706b17a9c646979aa77c589db145a6a707e7b19527aefbdb70dc230898cfc839` | IAPWS R14-08(2011), melting and sublimation pressures of ordinary water |
| `iapws/Ice-2009.pdf` | 273288 | `d1cc6886c423b257576fbd5ef546cae9896b62230915e17ff26fa059cfe4f127` | IAPWS R10-06(2009), Equation of State 2006 for H2O Ice Ih |

Purpose: steam above 900 K for the steam-cracking reference (F13), the water triple point and sublimation line, and the ice Gibbs function (deferred).

### 1.7 Mohammadi et al. 2021, hydrogen solubility in hydrocarbons

- **Article:** Mohammadi et al., "Modeling hydrogen solubility in hydrocarbons using extreme gradient boosting and equations of state", Sci. Rep. 11, 17911 (2021), doi:10.1038/s41598-021-97131-8.
- **Licence:** CC BY 4.0.

| File | Bytes | sha256 | Source |
|---|---|---|---|
| `mohammadi-2021-h2/mohammadi-2021-scirep-11-17911.pdf` | 2621984 | `c0698c5b149f75f085e54cd33665a286ef5651f8f8d851f8868a956c8ea0cb68` | https://www.nature.com/articles/s41598-021-97131-8.pdf |
| `mohammadi-2021-h2/41598_2021_97131_MOESM1_ESM.docx` | 935222 | `0a1cfb13c64d0b00bad14df79e3921f00b3fa91a5e471747a9da3f96eee158b2` | Supplementary Information 1, https://media.springernature.com/original/springer-static/esm/art%3A10.1038%2Fs41598-021-97131-8/MediaObjects/41598_2021_97131_MOESM1_ESM.docx |

**Limitation: the data points are not published.** The 919-point databank (26 hydrocarbons, 213–623 K, 0.1–25.5 MPa) is not published as data. The supplement holds solvent properties (Table S1), transfer functions, EOS forms, PC-SAFT parameters and fitted kij (Tables S2 to S6), and shows the points only as a figure. The article's Table 1 lists the 48 source datasets with their T/P ranges and uncertainties. It is therefore a route to primary datasets, not a holdout file.

Only a few of those sources reach the hydrotreating window, 573–713 K at 6–9 MPa: 1-octene, naphthalene and the rows at 453–623 K. Lin, Sebastian and Chao 1980 (H2 + n-C16, 463–664 K) remains the primary hydrotreating-window set and was not fetched (paywalled JCED).

## 2. Other research artifacts of this P0 step

| File | Bytes | sha256 | Content |
|---|---|---|---|
| `../vdu-k-policy/psat-mb-check.mjs` | 6423 | `feb4387c070d5fd64174d70464e6c705fc481b6cfaa6992514c20cd47575a74a` | D5 study harness: PR78 (mod formulation) against Maxwell-Bonnell (API TDB 5A1.18) on the current regrouped crude slate, with the two-point anchoring of omega and Tc. Run from the worktree root |
| `../vdu-k-policy/psat-mb-check-output.txt` | 2612 | `69b034a9591a6189ac8017cb29eb640e0d40352721606602c8dd52d81be3a251` | Its output of 2026-09-24 |

The review's probe states `../pr78-vs-coolprop/` stay the P0 numeric baseline; their hashes are in the P0 report, section 2.

## 3. Items not fetched, and why

| Item | Status | Alternative |
|---|---|---|
| E-PPR78 Akl/Bkl table (Xu et al. 2017 SI Table S1; Jaubert et al. 2022 SI Table S4) | RESOLVED 2026-09-25: the owner provided the Jaubert 2022 Supporting Information and article PDF; Table S4 is extracted and bundled (section 5). Before that: ACS SI HTTP 403 (bot check, not bypassed), HAL metadata only | The ACS SI of Xu et al. 2017 is no longer needed for P3 |
| Xu et al. 2015 pseudo-component kij (IECR 54, 2816) | HAL hal-01267209 has no file | Publisher copy through a library; the formula is in the IntechOpen chapter and in Jaubert 2022 |
| Mohammadi 2021 databank values | Not published (figure only) | Primary sources listed in its Table 1 |
| Lin, Sebastian, Chao 1980; Jäger and Span 2012; Giauque and Egan 1937; frost-point and SLE sets | Not attempted in P0 (paywalled journals) | Owner or library access before P4/P5; these are holdouts, not pilot inputs |


## 4. Added 2026-09-24 (P2): E-PPR78 group matrix, Clapeyron.jl transcription

The Akl/Bkl matrix that section 3 could not fetch from the publishers exists as an open transcription in the Clapeyron.jl database (MIT licence, copyright 2020 Hon Wa Yew and Pierre Walker). Fetched from `https://raw.githubusercontent.com/ClapeyronThermo/Clapeyron.jl/0778184abbe0de50791b6338cffe3444d4017508/database/cubic/EPPR78/<file>` (master on 2026-09-23); `git hash-object` of the two CSV files equals the GitHub blob SHA (`2e2b497e6194fdd37ebcbba9d79743b8095cd0db`, `2045f7ff04cdcd7b790b2c4a426088f0d86840f9`).

| File | Bytes | SHA-256 | Content |
|---|---|---|---|
| `e-ppr78/clapeyron/EPPR78_unlike.csv` | 11961 | `f20474d2e958447d5e3dc69d7ef20274bd9e3f98abc26a8d1ce913f78fb73e18` | 355 group pairs over 40 groups, columns `species1,species2,A,B` (Akl and Bkl in MPa, the E-PPR78 convention). Every pilot pair is present: CH4/N2 30.88/37.06, CH4/CO2 136.6/214.8, CH4/C2H6 9.951/13.73, C2H6/N2 61.59/84.92, C2H6/CO2 136.2/235.7, CO2/N2 113.9/212.4, plus the H2 and H2O pairs of every pilot species |
| `e-ppr78/clapeyron/EPPR78_groups.csv` | 1259 | `dc785106ac3cf04544fb623ba571f29725a51d61224f9db54f5a4af3d361ea8e` | Clapeyron's group decompositions of 28 species; note it decomposes ethane as 2 CH3 although the matrix carries a dedicated `C2H6` group, so P3 must take the decomposition rule from Jaubert et al. 2022 (ethane = C2H6 group), not from this file |
| `e-ppr78/clapeyron/LICENSE.md` | 1085 | `3466df8006d451512b8f4e2d0d021c42b154829de1fceaffa1e4edb721b340d9` | MIT text |

Provenance grade (corrected 2026-09-25, P3 WP2b): a third-party transcription of the published tables (Jaubert, Privat and co-workers 2004 to 2022), not the publisher's Table S4, and no longer the bundled data. Since WP2b the record `createcheme:eppr78_2022` carries Table S4 of the publisher's Supporting Information (revision `eppr78-2022-si-table-s4-r2`, 356 pairs, section 5); this transcription was its revision `eppr78-2022-clapeyron-0778184-r1` and is kept only for the row-by-row comparison (`tools/eppr78-transcription-check/check_pilot_kij.py`): 274 of its 355 pairs equal Table S4 and 83 differ (75 with A = 0 in place of the printed A, 3 with A = 0 and another B, 2 with another B, 2 pairs missing, 1 pair NA in Table S4). The six pilot pairs are identical, so the pilot package's values, and the WP8 GERG-2008 bubble-point check made with them, are unchanged. Neither local open paper prints the pilot-pair values (the Lasala 2020 chapter refers to Table S1 of Xu et al. 2017 and the Jaubert 2022 manuscript to its Table S4).


## 5. Added 2026-09-25: owner-provided sources

The owner provided the publisher's copies of Jaubert, Qian, Lasala, Privat 2022, "The impressive impact of including enthalpy and heat capacity of mixing data when parameterising equations of state. Application to the development of the E-PPR78 (Enhanced-Predictive-Peng-Robinson-78) model", Fluid Phase Equilibria 560 (2022) 113456, doi:10.1016/j.fluid.2022.113456: the article PDF and its Supporting Information (Elsevier file `1-s2.0-S0378381222000814-mmc1.docx`). The lead extracted Table S4 (the 40-group Akl/Bkl matrix, MPa) from the docx's MathType objects with `tools/eppr78-table-s4-extraction` (its README describes the method). P3 WP2b built the bundled record `createcheme:eppr78_2022`, revision `eppr78-2022-si-table-s4-r2`, from the TSV (`tools/eppr78-transcription-check`, section 4 for the transcription it replaced). The comparison `e-ppr78/jaubert-2022-si-table-s4-vs-record-r1.txt` is the lead's first diff against revision r1; `tools/eppr78-transcription-check/pilot-kij-output.txt` is the checked row-by-row comparison.

| File | Bytes | SHA-256 | Content, licence |
|---|---|---|---|
| `e-ppr78/jaubert-2022-fpe-560-113456-publisher.pdf` | 3921337 | `f63f0da9d5ddf3a641714fb2343a4f1bc8c866dd185c463e4ee2ac6c4b817618` | Publisher's version of record of the article. Its first page states "© 2022 The Author(s). Published by Elsevier B.V.", open access under CC BY 4.0. Owner-provided; kept for non-commercial research use and not redistributed from this folder |
| `e-ppr78/jaubert-2022-si-mmc1.docx` | 2837469 | `bd068feb989e5d47d02f90960f08ef75a05e82a9a1bb9fe93554c9ec9f4613c1` | Supporting Information, Elsevier file `1-s2.0-S0378381222000814-mmc1.docx`; Table S4 is the Akl/Bkl matrix (lower triangle, values as MathType objects). The file states no licence of its own (supplementary material of the CC BY article above; its terms not confirmed). Publisher copyright assumed; owner-provided for non-commercial research use; not redistributable |
| `e-ppr78/jaubert-2022-si-table-s4.tsv` | 24001 | `bd347cdaa070c14b14e3c8c46e00bd8af77079e4ae996b5c055c247f7c417682` | Extraction of Table S4, columns `k, l, group_k, group_l, Akl_MPa, Bkl_MPa`, the lower triangle as printed: 820 cells (40 diagonal zeros, 356 numeric off-diagonal pairs, 424 `NA`), decimals verbatim. Derived from the Supporting Information: same terms as the docx, not redistributable; the bundled record carries the values with the citation |
| `solid-co2/jaeger-span-2012-jced-57-590.pdf` | 798332 | `d52376688d9dcd83b42703178d313a2428824e758a444ae9e58bede21f9ce32d` | Jäger and Span 2012, "Equation of State for Solid Carbon Dioxide Based on the Gibbs Free Energy", J. Chem. Eng. Data 57, 590-597, doi:10.1021/je2011677 (publisher's PDF, ACS copyright). Owner-provided for non-commercial research use, not redistributable. Equation 8 and Table 1 are the P4 crystal model (`P4_SOLID_CO2_MODEL.md`, D16); text extractions `.txt`/`.layout.txt` (equations garbled) and rendered pages `pages/jaeger-p01..p08.png` beside it |
| `solid-co2/trusler-2011-jpcrd-40-043105.pdf` | 1247002 | `692eb94231f8df00795d97e8b0ef8bfa52ddd238d7518aaddf33c4da68231586` | Trusler 2011, "Equation of State for Solid Phase I of Carbon Dioxide Valid for Temperatures up to 800 K and Pressures up to 12 GPa", J. Phys. Chem. Ref. Data 40, 043105, doi:10.1063/1.3664915 (publisher's PDF, AIP copyright; the pre-erratum original, see the erratum doi:10.1063/1.4745598 in section 6). Owner-provided for non-commercial research use, not redistributable. Used as the P4 cross-check of the crystal volume, expansion and heat capacity, not as the model; text extractions and rendered pages `pages/trusler-p01..p19.png` beside it |

## 6. Added 2026-09-25: P4/P5 solid CO2 holdout survey

Fetched 2026-09-25 by the holdout survey agent (worktree `claude/coolprop-multiphase-thermo-37f6b0`, research only). Folder `solid-co2-holdouts/`, described by its `README.md`; the survey report is `documentation/2026-09-24-coolprop-low-temperature/P4_P5_HOLDOUT_DATA_SURVEY.md`. Everything here is validation data for G4 and G5, not a model input. Retrieval date 2026-09-25 for every file.

Fetch notes:

- The HAL server challenges browser user agents (a proof-of-work "not a bot" page). The files were fetched with curl's default user agent, which it serves directly; no challenge was solved.
- Deep Blue (University of Michigan), pubs.acs.org, onlinelibrary.wiley.com and pubs.aip.org answered HTTP 403 behind Cloudflare. Nothing was bypassed; those items are in the survey's owner request list.

### 6.1 Fetched documents (`solid-co2-holdouts/raw/`)

| File | Bytes | SHA-256 | URL | Licence | Content |
|---|---|---|---|---|---|
| `span-wagner-1996-jpcrd-25-1509-nist-jpcrd516.pdf` | 8071383 | `85d732b0be26dc4736e8a768903e49e49e8ae6386447a93fd58bff6381544d34` | https://www.nist.gov/system/files/documents/srd/jpcrd516.pdf | NIST SRD reprint, journal copyright; research copy | Span and Wagner 1996, JPCRD 25, 1509, doi:10.1063/1.555991. Scanned, no text layer |
| `stringari-riva-2018-iecr-hal-01855741.pdf` | 1244987 | `415ea160f575c418de8419af8d6f2f8cf17b4a819bbb8cc71943ebcfc0910599` | https://minesparis-psl.hal.science/hal-01855741/document | HAL deposit licence (hal-authorisation-v1) | Riva and Stringari 2018, IECR 57, 4124, doi:10.1021/acs.iecr.7b05224, author manuscript |
| `campestrini-2022-fpe-hal-03519082.pdf` | 930179 | `8414018cb7455ad9761773e43f2bc7088681fa0602f7299a3beaf8ab1560ccc8` | https://hal.science/hal-03519082/document | CC BY-NC 4.0 | Campestrini et al. 2022, FPE 553, 113292, doi:10.1016/j.fluid.2021.113292, author manuscript |
| `fandino-2015-ijggc-36-78-spiral-accepted.pdf` | 985219 | `605b47429c02405438f30f0237ab4a4627ed39bbda2fff5bf534a517c1ba43c9` | https://spiral.imperial.ac.uk/server/api/core/bitstreams/a5443499-2719-461b-bae6-d1583b1f67a5/content (hdl 10044/1/23572) | CC BY-NC-ND 4.0 (repository record) | Fandiño, Trusler, Vega-Maza 2015, IJGGC 36, 78, doi:10.1016/j.ijggc.2015.02.018, accepted manuscript; MD5 equals the repository checksum `e17156bc610601109ef7e191bcf803a8` |
| `riva-2016-thesis-tel-03510271.pdf` | 6164248 | `5709c6f01635497262d0dbe6207b5a0783faacc9da959020aefe3cfa3949cb71` | https://pastel.hal.science/tel-03510271/document | HAL deposit licence | Riva 2016, PhD thesis, MINES ParisTech: literature data maps for CH4 + CO2, N2 + CO2 and N2 + CH4 + CO2 (Tables 3.2 and 3.11 and the N2 + CO2 table of section 3.4), model deviations per data set (Tables 4.9 to 4.11 and the SVE tables around them) |
| `campestrini-2014-thesis-tel-01139406.pdf` | 14579816 | `8c82b85fe7478a98224225641ce67a68a3f048572cb17b07f3010d8b7ac41352` | https://pastel.hal.science/tel-01139406/document | HAL deposit licence | Campestrini 2014, PhD thesis, MINES ParisTech: appendix A literature tables for the N2, O2 and Ar binaries, including N2 + CO2 (Table A.13); for the P7 air rollout |
| `riva-2014-iecr-hal-01085354.pdf` | 888295 | `fb8aadfbd78595207497826cd57f70e5708382bff7264164aac89cae765d5181` | https://minesparis-psl.hal.science/hal-01085354/document | HAL deposit licence | Riva et al. 2014, IECR 53, 17506, doi:10.1021/ie502957x: CH4 + CO2 SLVE models with per-dataset AAD tables; no new data |
| `campestrini-2023-fpe-hal-04016509.pdf` | 1207092 | `a7032a9d068239fc74367dcb1c14c408019b9b0fe0171ff0f97aaa9e14043006` | https://minesparis-psl.hal.science/hal-04016509/document | HAL deposit licence | Campestrini et al. 2023, FPE 570, 113774, doi:10.1016/j.fluid.2023.113774: its Table 1 lists the SVE, SLE and SLVE sets used for N2 + CO2 and CH4 + CO2; no new data |
| `nist-webbook-co2-phase-change-mask4-2026-09-25.html` | 50037 | `9331f535a5eb0ef53b94d09838a34598756636379d10b0e24b804f4c170f9bb2` | https://webbook.nist.gov/cgi/cbook.cgi?ID=C124389&Units=SI&Mask=4 | NIST SRD 69 (compilation copyright, U.S. Secretary of Commerce) | Page snapshot: CO2 phase-change data (sublimation enthalpies, Antoine fit to Giauque and Egan) |

### 6.2 NIST TRC ThermoML files (`solid-co2-holdouts/thermoml/`)

Source: the NIST TRC ThermoML Archive, `https://trc.nist.gov/ThermoML/<DOI>.xml` (archive record doi:10.18434/mds2-2422). Terms: no warranty from the publisher, the editors or NIST/TRC; TRC asks users to cite. Requests for 10.1021/acs.jced.2c00237, 10.1016/j.fluid.2021.113292, 10.1021/je60050a018 and 10.1016/j.jct.2022.106829 returned 404 (not in the archive).

| File | Bytes | SHA-256 | Paper |
|---|---|---|---|
| `le-trebble-2007-jced-52-683-je060194j.xml` | 85804 | `451eea612430b0660acad39f1057be6ac578e5ec958ed71077dfb1dcd7efd9d9` | Le and Trebble 2007, JCED 52, 683, doi:10.1021/je060194j |
| `zhang-2011-jced-56-2971-je200261a.xml` | 17117 | `f6c1c07069ab6a680628433700f9795fc47d3dca0b1928ac08dfaeacc72999fa` | Zhang et al. 2011, JCED 56, 2971, doi:10.1021/je200261a |
| `xiong-2015-jced-60-3077-acs.jced.5b00059.xml` | 268667 | `00876ad7d95f570aab9a063986f21fdbbd2e976da0b23046279465c400481e6a` | Xiong et al. 2015, JCED 60, 3077, doi:10.1021/acs.jced.5b00059 |
| `shen-2012-jced-57-2296-je3002859.xml` | 101325 | `ffb9840ad4a7c7817aa567d4a34ab1efdbb544c48e829549a3417997eac08cc8` | Shen et al. 2012, JCED 57, 2296, doi:10.1021/je3002859 |
| `souza-2020-fpe-522-112762.xml` | 130982 | `92157292381b190a8eeb25d9f8ad0f7bbf8fc041f06de03f2497df3e077216b2` | Souza et al. 2020, FPE 522, 112762, doi:10.1016/j.fluid.2020.112762 |

### 6.3 Derived files (`solid-co2-holdouts/`)

TSV transcriptions with `#` citation headers, digits verbatim. The ThermoML TSVs are the output of `thermoml_to_tsv.py` behind a hand-written header. The others were transcribed from rendered pages: 170 to 200 dpi PyMuPDF renders, or the text layer where it was checked against a render.

| File | Bytes | SHA-256 | From |
|---|---|---|---|
| `README.md` | 5135 | `75012bcb557918cc2ad829f1c21bf9ca458730b8c224eab85eaba677b7a185a3` | Folder description |
| `thermoml_to_tsv.py` | 5988 | `d876656b991946114c45c1f69baf8c6306dc1ced23360f1e30f9d4bb249b71a1` | ThermoML flattener (Python standard library) |
| `le-trebble-2007-jced-52-683.tsv` | 15438 | `3cbe74ff74624aefa1a12c21b65eac011c8cfa0fab1c477dc04d64069fc7f763` | ThermoML; 103 frost points |
| `zhang-2011-jced-56-2971.tsv` | 3237 | `72349c15bea1457d38dac32992ddf4d0d0045d2ecfc178b2f5aa8ce2191fe018` | ThermoML; 17 frost points |
| `xiong-2015-jced-60-3077.tsv` | 43769 | `d403c532881aa6a037a7c2f21b3ad37f9ef881ec4a0840b29a6d9b456442a7eb` | ThermoML; 322 rows, 194 unique points |
| `shen-2012-jced-57-2296.tsv` | 16496 | `5a4dcaccfa06a91e9872eaaaef68f39dbc929f80c7fd7499c611ee7e48dc3a6d` | ThermoML; 63 SLVE points, as pressure and composition rows |
| `souza-2020-fpe-522-112762.tsv` | 20268 | `d9f58b61be51708faf7a443b0ebb639f0f0aab56c39bb0698fbed73fb9146b58` | ThermoML; 7 SVLE points plus VLE |
| `campestrini-2022-fpe-553-113292.tsv` | 6414 | `663061a8a00deb674a97754085eb0ea90f6a188342beada7367f35d971c71773` | HAL manuscript, Tables 4, 6 to 10 and 12 |
| `stringari-riva-2018-iecr-57-4124-table4.tsv` | 2253 | `dde3209fb4170fb64c68b73c70d99deb5226cfba5801f2211109b813b1941010` | HAL manuscript, Table 4 |
| `fandino-2015-ijggc-36-78-table8.tsv` | 1112 | `2d657cdf211a74f02907497d7504796235089285a43711d07bbf9f7b36c4ad00` | Spiral manuscript, Table 8 (CO2 + N2 column) |
| `span-wagner-1996-co2-melting-sublimation-equations.tsv` | 2513 | `d702e7e550a749981bbd8723f8c27e064146d1101d448b421a5c23b946a48a90` | NIST reprint, eqs. (3.10) and (3.12), pp. 1520 and 1521 |
| `span-wagner-1996-table6-co2-sublimation-datasets.tsv` | 1353 | `260b11509128131d30596db63ca7ce7c484f1f0f85e348f052dee94c864d1a38` | NIST reprint, Table 6, p. 1521 |
| `fray-schmitt-2009-co2-sublimation-via-lisse-2020.tsv` | 1992 | `b9ef79e0d4bd44f3a8de7cba9a14d65052be289232a800329e95e04723bdba9b` | `fray-schmitt-2009/arxiv-2009.02277.pdf`, SOM Table 2, p. 35 (CO2-1, CO2-2) |
| `trusler-2011-co2-auxiliary-equations-and-table2.tsv` | 2575 | `75370b8df937a7973f9d5c57e6b8e4d5b31392971631e15a3c387879c60c70a8` | `solid-co2/trusler-2011-jpcrd-40-043105.pdf`, eqs. (47) and (48), Table 2 (pre-erratum copy) |
| `nist-webbook-co2-sublimation-giauque-egan-derived.tsv` | 1663 | `c6efd5fa52a416657c22ba99e70ad5ab1ca066f33fc0e6968f7b4d6014399d48` | The WebBook snapshot above |
| `atake-chihara-1976-ethane-phase-changes-abstract.tsv` | 876 | `70eb7525c3cefe13dcdb9fff0b6fc6b766e4ce489530577807137a1abda06f79` | Publisher abstract via Crossref (doi:10.1246/cl.1976.683) |

### 6.4 Findings recorded at fetch time

- **The Fray and Schmitt reproduction omits the unit and ranges.** The section 1.5 reproduction prints neither the pressure unit nor the temperature ranges of CO2-1 and CO2-2. Numerically: Psat is in bar (CO2-1 gives 1.008 bar at 194.6855 K); CO2-1 is the branch below about 195 K and CO2-2 the branch up to the triple point (comparison with Span-Wagner eq. 3.12). The garbled token noted in section 1.5 is krypton's A6, not a CO2 coefficient.
- **CoolProp carries the melting line only.** The CoolProp `CarbonDioxide.json` (this folder and the worktree test resource, identical) carries the Span-Wagner melting line (eq. 3.10) and no sublimation line.
- **Trusler 2011 has an erratum.** It is J. Phys. Chem. Ref. Data 41, 039901 (2012), doi:10.1063/1.4745598 (the Crossref "correction" of 10.1063/1.3664915). It makes three corrections, two pointed out by A. Jäger. Its text is paywalled and was not read. `solid-co2/trusler-2011-jpcrd-40-043105.pdf` is the uncorrected 2011 article (downloaded 2013-03-16 per its footer). No errata are registered with Crossref for Jäger and Span 2012, Span and Wagner 1996, Fray and Schmitt 2009 or Giauque and Egan 1937.
- **Le and Trebble 2007, TRC file defect.** Sets 2 and 3 label the CH4 fraction as the C2H6 or N2 fraction (values 0.96 to 0.97).
- **Xiong 2015 repeats its binary points.** The 64 binary CH4 + CO2 points appear three times (once per set).
- **No manifest entry for `solid-co2/`.** That folder (Jäger and Span 2012, Trusler 2011, text extracts, page renders) has no entry in sections 1 to 5. The `research/INDEX.md` row attributes it to section 5, and `P4_SOLID_CO2_MODEL.md` records the SHA-256 of the two PDFs. The manifest should carry them too.

### 6.5 Not fetched

The survey report's owner request list carries the reasons and the alternatives. In short:

- **Blocked by bot checks, although open access.** Maltby et al. 2025 (IECR, CC BY 4.0) and Sampson et al. 2023 (AIChE J., CC BY 4.0) are behind publisher Cloudflare checks. Sonntag 1960 (University of Michigan dissertation, Deep Blue hdl 2027.42/7707) is behind a Deep Blue Cloudflare check.
- **Paywalled:**
  - Sonntag and Van Wylen 1962, and Smith, Sonntag and Van Wylen 1963 and 1964 (Springer, Adv. Cryog. Eng. 7, 8 and 9).
  - Davis, Rodewald and Kurata 1962 (Wiley).
  - Giauque and Egan 1937 (AIP).
  - The Trusler 2012 erratum (AIP).
  - Fray and Schmitt 2009 (Elsevier).
  - Gao et al. 2012 (ACS).
  - Kurata and Im 1971 (ACS).
  - Agrawal and Laverman 1974 (Springer).
  - Siah, Campestrini and Stringari 2025 (ACS).
- **Research-only case, open, not fetched:** Engle et al. 2021 (Planet. Sci. J., arXiv:2103.15978, CC BY 4.0), the methane/ethane liquidus.


## 7. Added 2026-09-25: owner-provided papers

The owner provided thirteen papers from the holdout survey's request list (section 6.5; survey report `documentation/2026-09-24-coolprop-low-temperature/P4_P5_HOLDOUT_DATA_SURVEY.md`, section 5). Folder `solid-co2-holdouts/owner-provided/`; file names as provided. SHA-256 values were given by the owner and recomputed on 2026-09-25; all thirteen match. Each paper is under the publisher's copyright unless it states CC BY. All are owner-provided for non-commercial research use and are not redistributable from this folder.

### 7.1 Papers (`solid-co2-holdouts/owner-provided/`)

| File | Bytes | SHA-256 | Citation | Licence |
|---|---|---|---|---|
| `sonntag-1960-dissertation-umich-bad2222.pdf` | 13641885 | `3b2709380b9bd87ce5a545fad1976a948b12093c77e461022c488c4992d7bd7c` | R. E. Sonntag, "The Equilibrium of Solid Carbon Dioxide with its Vapor in the Presence of Nitrogen", PhD dissertation, University of Michigan 1960 (IP-469), hdl 2027.42/7707. Scan, no text layer | University of Michigan dissertation (Deep Blue); owner-provided, non-commercial research use, not redistributable |
| `sonntag-van-wylen-1962-solid-vapor-equilibrium-co2-n2.pdf` | 8518842 | `2c23234e6cccfb84a818590b794977c3aa74de15ba23dc3839352daae92b76bd` | **Content differs from the file name:** G. E. Smith, "Solid-Vapor Equilibrium of the Carbon Dioxide-Nitrogen System at Pressures to 200 Atmospheres", PhD dissertation, University of Michigan 1963 (University Microfilms 64-6752), 219 pages, OCR text layer. Its data are those of Smith, Sonntag, Van Wylen, Adv. Cryog. Eng. 9, 197 (1964), doi:10.1007/978-1-4757-0525-6_24. The 1962 Sonntag-Van Wylen chapter itself (Adv. Cryog. Eng. 7, 99) was not provided | ProQuest/UMI reproduction; owner-provided, non-commercial research use, not redistributable |
| `advances-in-cryogenic-engineering-vol-8-1963-9781475705287.pdf` | 64451100 | `9b9aabf9eaeee2e6bf94d5b8fd13a060a558c988b1f10b9303750bd5321fa205` | Advances in Cryogenic Engineering vol. 8 (1963), Springer, ISBN 978-1-4757-0528-7. Used chapter C-6: Smith, Sonntag, Van Wylen, "Analysis of the Solid-Vapor Equilibrium System Carbon Dioxide-Nitrogen", pp. 162-173, doi:10.1007/978-1-4757-0528-7_19. It is an analysis paper, not the "pressures to 200 atmospheres" data chapter (that is vol. 9) | Springer copyright; owner-provided, non-commercial research use, not redistributable |
| `maltby-2025-iecr-64-24253.pdf` | 2915788 | `744c2ba3bab6a828ce233f1cb2be23101f0a61af84ad80182aace81baa9874e9` | Maltby, Aasen, Hammer, Wilhelmsen, Ind. Eng. Chem. Res. 64, 24253 (2025), doi:10.1021/acs.iecr.5c04028 | CC BY 4.0 (stated on p. 1); owner-provided copy kept for non-commercial research use |
| `maltby-2025-iecr-64-24253-si.pdf` | 1214919 | `8fcf9ecfd3fe7231c2cf48b82e3eb84fb19412ea38fe7c5a6cdc0abd74fc6e4b` | Supporting Information of the above | SI of a CC BY article, own terms not stated; treated as publisher copyright; owner-provided, not redistributable |
| `davis-rodewald-kurata-1962-aiche-8-537.pdf` | 357810 | `31bc668615e9bb6855087810655d6222283ec7658366ae460d7ed11b53afab30` | Davis, Rodewald, Kurata, AIChE J. 8, 537 (1962), doi:10.1002/aic.690080423 | Wiley copyright; owner-provided, non-commercial research use, not redistributable |
| `trusler-2012-jpcrd-41-039901-erratum.pdf` | 51970 | `b726a52eb37adf36f71a30815ca678a3fba8e0be795a226a16e2ba4c6db81f7d` | Trusler, erratum, J. Phys. Chem. Ref. Data 41, 039901 (2012), doi:10.1063/1.4745598 | AIP copyright; owner-provided, non-commercial research use, not redistributable |
| `giauque-egan-1937-jcp-5-45.pdf` | 867130 | `7320b9e7c12eaf3065060cd8698d841bd52f7d728b1a007ee57b6caebd76bfc0` | Giauque, Egan, J. Chem. Phys. 5, 45 (1937), doi:10.1063/1.1749929 | AIP copyright; owner-provided, non-commercial research use, not redistributable |
| `fray-schmitt-2009-pss-57-2053.pdf` | 3236419 | `2870cea9fadfa715474dec31eb51bd0df57dbf6ef9e6c83ccb9b64d5e5c18462` | Fray, Schmitt, Planet. Space Sci. 57, 2053 (2009), doi:10.1016/j.pss.2009.09.011 | Elsevier copyright; owner-provided, non-commercial research use, not redistributable |
| `sampson-2023-aiche-aic18001.pdf` | 2083525 | `514139926c4abbb7b8afde409d492aac50d999f47eb1a7353053d4f78abb0c2a` | Sampson et al., AIChE J. 69(4), e18001 (2023), doi:10.1002/aic.18001 | Creative Commons Attribution License (stated on p. 1); owner-provided copy kept for non-commercial research use |
| `kurata-im-1971-jced-16-295.pdf` | 581157 | `ab8a75e7a27b19a446b41811e8383cc79558c978d0ab171c2cbc9fa385dde146` | Kurata, Im, J. Chem. Eng. Data 16, 295 (1971), doi:10.1021/je60050a018 | ACS copyright; owner-provided, non-commercial research use, not redistributable |
| `gao-2012-iecr-51-9403.pdf` | 699579 | `44fc3130894ccd82f690f712e66c841398d3ed3a162fff73abe3be0df73d4c3f` | Gao, Shen, Lin, Gu, Ju, Ind. Eng. Chem. Res. 51, 9403 (2012), doi:10.1021/ie3002815 | ACS copyright; owner-provided, non-commercial research use, not redistributable |
| `siah-campestrini-stringari-2025-jced-je5c00260.pdf` | 2844139 | `0103f979e2feb3f61f15944d922ffc6501dbf3c3709439dfb22585179496d72e` | Siah, Campestrini, Stringari, J. Chem. Eng. Data 70(7), 2890 (2025), doi:10.1021/acs.jced.5c00260 | Publisher copyright; owner-provided, non-commercial research use, not redistributable |

### 7.2 Derived files (same folder)

Every TSV has `#` header lines giving the citation, table, page, units, uncertainty and the statement "digits as printed, one sanity check, not re-verified (owner: not looking for 100 % correct data)". Derived files carry the terms of their source. Numbers are transcribed with citation for research use.

| File | Bytes | SHA-256 | From |
|---|---|---|---|
| `sonntag-1960-dissertation-table8-co2-n2-sve.tsv` | 3799 | `971fc4f1ad653ae94a71c69d84ddbbbc7bc967852b3788ccb6ef0c9463dc130d` | Sonntag 1960, Table VIII (p. 100), 200 dpi render; 64 SVE points |
| `smith-1963-dissertation-table13-co2-n2-sve.tsv` | 6929 | `acff8148153f17e0487ecc1004acd686b58fc40226788311265d11856756b51d` | Smith 1963 dissertation, Table XIII (p. 143), 170 dpi render; 125 values with an origin column |
| `trusler-2012-erratum-note.md` | 3673 | `d17836932b6fef6eaf3c26f5845f96f2bec61144efd8334547805a5bfee444a8` | Trusler erratum: the three corrections and their effect on eq. 49 |
| `giauque-egan-1937-jcp-5-45.tsv` | 7611 | `0fdf0fc07c566b071edc62d54d272789c2af2f5c2546fcd0b2cc30b6d362b2c5` | Giauque-Egan Tables I, IV, V, VII |
| `davis-rodewald-kurata-1962-aiche-8-537.tsv` | 5698 | `6f5440cd6a5003fd0e2ebfbfb670723e29a062b243c5a44e45350af76cff3161` | Davis 1962 Tables 1 to 4 |
| `maltby-2025-summary.md` | 8816 | `cdefa6474f950a60f4cd9c6c7812c90aa46ee3040c61d0498ecb828225b88118` | Maltby 2025 plus SI: data inventory and deviations |
| `maltby-2025-iecr-64-24253-tables2-3.tsv` | 9009 | `c1686529ad7b268bd5cac2503a649fe7cf08e08d4d09c645a5130cbffca978c7` | Maltby 2025 Tables 2 and 3 (text layer) |
| `kurata-im-1971-jced-16-295-table6.tsv` | 2736 | `fc83d1de075188897a9fc7a46584216d90fa0b18382483c5ac065dd4cd5f4dc0` | Kurata-Im 1971 Table VI (CO2 + CH4, derived from Davis) |
| `gao-2012-iecr-51-9403.tsv` | 4315 | `3d7fdf660e81a369ea4ebfd514b2f4ee68418d3ceaa02bb1244bae3efb928c1f` | Gao 2012 Tables 2 and 4 |
| `sampson-2023-aiche-aic18001-table2.tsv` | 2343 | `a95c5b6cd720f88a2fdc74a9d62660c00dd2593a8cca66c09e7ee23fbfb394d9` | Sampson 2023 Table 2 (+ Table 1 uncertainties) |
| `fray-schmitt-2009-pss-57-2053-co2-tables4-5.tsv` | 2478 | `56b0e5b8ada29286c9ce6ae2815b36d01a319f3196a09bf9794db9d5b7e48b37` | Fray-Schmitt Tables 4 and 5, CO2 rows |
| `siah-campestrini-stringari-2025-note.md` | 2341 | `135139f9aec756a4c1c4a0a822c3d018d3c67bbc19224f86f2bbda84a96c5978` | Siah 2025: what the model is and its reported deviations |

### 7.3 Findings recorded at receipt

- **Misnamed file.** `sonntag-van-wylen-1962-...pdf` holds Smith's 1963 dissertation, not the 1962 chapter. Smith's Table XIII consolidates Sonntag's data below the agreement points with his own up to 200 atm, so the 1962 chapter's data are covered by `sonntag-1960-...table8.tsv`.
- **Vol. 8 chapter.** The Adv. Cryog. Eng. vol. 8 chapter is the analysis paper. Its Table I EXP column is Sonntag's data as enhancement factors: at 140 K and 5 atm, 0.00042 × 5 / 0.00184 = 1.141 against a printed 1.1414. Not transcribed.
- **Fray and Schmitt coefficients confirmed.** Their Table 5 CO2 coefficients equal the Lisse 2020 reproduction digit for digit (section 6.3 file). The pressure unit is bar, and the ranges and accuracies are now known.
- **Trusler erratum.** The three corrections are eq. (38) (a missing θ_D,0), the signs of d7, d8 and d9 in eq. (49), and b1 = 1.151 in Table 6. The local `solid-co2/trusler-2011-...pdf` has the uncorrected forms.

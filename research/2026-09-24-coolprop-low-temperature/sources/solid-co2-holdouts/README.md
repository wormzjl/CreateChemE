# Solid CO2 holdouts for P4 and P5

Batch `2026-09-24-coolprop-low-temperature` (unified multiphase thermodynamics plan, P4 selected solid-gas support and P5 solid-liquid competition). Collected 2026-09-25 by the holdout survey in worktree `claude/coolprop-multiphase-thermo-37f6b0`. Research material, git-ignored, not on the mod's classpath.

The survey report is `documentation/2026-09-24-coolprop-low-temperature/P4_P5_HOLDOUT_DATA_SURVEY.md`; file hashes, URLs and licences are in `../MANIFEST.md` section 6.

## Purpose

These files are validation data (holdouts) for gates G4 (CO2 frost points in nitrogen, pure CO2 sublimation) and G5 (CO2 freezing out of liquid methane: liquidus, three-phase line, phase compositions). None of them is a model input. The solid CO2 model itself (Jäger and Span 2012, Trusler 2011) lives in `../solid-co2/`.

## Contents

TSV transcriptions, one per source table, each with a `#` header giving the citation, DOI, table, page, units and stated uncertainties. Digits are verbatim.

| File | Source | Gate |
|---|---|---|
| `span-wagner-1996-co2-melting-sublimation-equations.tsv` | Span and Wagner 1996, eqs. (3.10) and (3.12), with the local CoolProp melting-line cross-check | G4 sublimation, G5 melting |
| `span-wagner-1996-table6-co2-sublimation-datasets.tsv` | Span and Wagner 1996, Table 6 (map of the primary sublimation data) | G4 |
| `fray-schmitt-2009-co2-sublimation-via-lisse-2020.tsv` | Fray and Schmitt 2009 CO2 coefficients, as reproduced by Lisse et al. 2020 (arXiv) | G4 |
| `trusler-2011-co2-auxiliary-equations-and-table2.tsv` | Trusler 2011 eqs. (47), (48) and Table 2; erratum not read | G4, G5 (secondary) |
| `nist-webbook-co2-sublimation-giauque-egan-derived.tsv` | NIST WebBook: sublimation enthalpies and the Antoine fit to Giauque and Egan's data | G4 |
| `le-trebble-2007-jced-52-683.tsv` | Le and Trebble 2007 via NIST ThermoML (frost points in CH4, + C2H6, + N2) | G5 context, G4 context |
| `zhang-2011-jced-56-2971.tsv` | Zhang et al. 2011 via NIST ThermoML (frost points in CH4) | G5 context |
| `xiong-2015-jced-60-3077.tsv` | Xiong et al. 2015 via NIST ThermoML (frost pressures in CH4, + N2, + C2H6) | G5 context, G4 context |
| `shen-2012-jced-57-2296.tsv` | Shen et al. 2012 via NIST ThermoML (SLVE liquid composition in CH4, + N2, + C2H6) | G5 liquidus |
| `souza-2020-fpe-522-112762.tsv` | Souza et al. 2020 via NIST ThermoML (SVLE line of CO2 + CH4, plus VLE) | G5 three-phase line |
| `campestrini-2022-fpe-553-113292.tsv` | Campestrini et al. 2022, HAL manuscript, Tables 4, 6 to 10 and 12 (SVE, SLE, SLVE at 4 MPa, with 0, 5 and 10 % N2) | G5 liquidus, G4 context |
| `stringari-riva-2018-iecr-57-4124-table4.tsv` | Riva and Stringari 2018, HAL manuscript, Table 4 (SLVE of N2-(O2)-CH4-CO2) | G5 (ternary) |
| `fandino-2015-ijggc-36-78-table8.tsv` | Fandiño et al. 2015, Spiral manuscript, Table 8 (SLV line of CO2 + N2) | G4 liquid competition |
| `atake-chihara-1976-ethane-phase-changes-abstract.tsv` | Atake and Chihara 1976, abstract values | research-only CH4/C2H6 liquidus |

Other files:

- `thermoml/`: the five NIST TRC ThermoML XML files the ThermoML TSVs were flattened from.
- `thermoml_to_tsv.py`: the flattener (Python standard library). Run from this folder: `python thermoml_to_tsv.py thermoml/<file>.xml`. The TSVs are its output behind a hand-written header.
- `raw/`: the fetched documents (HAL and Spiral manuscripts, the NIST reprint of Span and Wagner 1996, the Riva 2016 and Campestrini 2014 theses, a WebBook page snapshot).

## Provenance and caveats

- ThermoML files are TRC's compilation of each paper's tables; their `U95` column is the TRC compiler's expanded uncertainty, not always the authors'. The Le and Trebble 2007 file mislabels the third component of its sets 2 and 3 (the value is the CH4 fraction); see that TSV's header.
- The Xiong 2015 file repeats the 64 binary points inside both ternary sets (194 unique points, not 322).
- The Fray and Schmitt coefficients come from a reproduction that prints neither the pressure unit nor the temperature ranges. The unit (bar) and which branch applies where were inferred numerically; the ranges need the original paper.
- The local Trusler 2011 PDF predates its erratum (J. Phys. Chem. Ref. Data 41, 039901, 2012), which was not read.
- No open tabulation of the CO2 + N2 binary frost points (Sonntag and Van Wylen; Smith, Sonntag and Van Wylen) was found. The owner request list in the survey report gives the routes.
- The HAL and Spiral manuscripts are author versions. The published versions may differ in typesetting; Table 12 of the Campestrini manuscript lacks its x1 and y1 columns.

## Licences

The owner has stated the project is non-commercial. Per file:

- NIST ThermoML: no-warranty terms, citation requested.
- NIST SRD reprint of Span and Wagner 1996: journal copyright.
- HAL: the deposit licence, except Campestrini 2022, which is CC BY-NC 4.0.
- Spiral (Fandiño 2015): CC BY-NC-ND 4.0.
- arXiv reproduction (in `../fray-schmitt-2009/`): the arXiv licence.

All of it is kept as a local research copy. Nothing here is redistributed or bundled.

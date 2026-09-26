# Crude-specific elemental profiles on the unchanged 20-component basis

Research only: these are candidate cut-chemistry profiles in research/crude-assays, not data loaded by the mod. No Java integration or production assay changes are included. Component IDs, feed amounts, MW, density, PR parameters, heat-capacity coefficients and viscosity curves remain unchanged.

## Evidence and estimation

The source PDFs report hydrogen, sulfur and nitrogen for several cuts, and selected residue metal and analytical-quality values. The PDFs do not identify every value as experimentally measured, so the transcription calls them reported values. Blanks remain null and printed zeros retain their original meaning; neither is a detection limit.

The raw transcription is [source-quality.json](../../crude-assays/source-quality.json), with source PDF hashes and the original twelve disjoint cut positions. Whole-crude summary values take precedence where the cut table rounds them more coarsely. The overlapping 370°C+ aggregate is retained for reference, never added as another cut.

Each modeled pseudocomponent value is a mass-weighted rebin of the source cuts using the existing conversion allocation matrix. Exact light chemicals retain formula-based C/H and zero heteroatoms/metals. Pure pentane contributions are subtracted from C5–65°C before assigning the residual cut chemistry.

For S, N, Ni, V and Fe, missing source-cut concentrations are explicitly assumed zero, then the reported cut pattern is scaled to match the whole-crude elemental inventory. This is a modeling assumption, not a new measurement. Source values are preserved separately. At very low metal concentrations, rounding produces substantial relative reconciliation factors; WTI nickel requires about 1.328×. These profiles should not be treated as high-precision trace-metal partition measurements.

Every 550°C+ source hydrogen entry is missing. It is estimated from H_residue = (H_whole − sum of lighter-cut hydrogen masses) / residue mass, including pure light ends. The same 550°C+ elemental concentration applies throughout the extrapolated tail; no unsupported enrichment curve is introduced.

## Hydrogen estimate sensitivity

The half-width below propagates only ±0.05 wt% rounding in each reported hydrogen concentration with fixed cut masses. It excludes cut-yield uncertainty, analytical uncertainty and model error; it is not a confidence interval.

| Crude | Reported whole H, wt% | Estimated 550°C+ H, wt% | Rounding-only half-width, wt% |
|---|---:|---:|---:|
| WTI Light - Export | 14.4 | 14.341 | ±2.052 |
| Upper Zakum | 13.2 | 11.818 | ±0.448 |
| Bonga | 12.6 | 11.557 | ±1.071 |
| Dalia | 12.6 | 12.255 | ±0.346 |
| Cold Lake Blend | 12.1 | 11.066 | ±0.224 |

WTI's small residue fraction makes its inferred hydrogen concentration particularly weakly constrained. The resulting value is not forced to decrease with boiling point.

## Unknowns and interpretation

Pseudocomponent carbon and oxygen are unresolved. The unassigned mass is largely carbon plus oxygen and untracked material; it must not be silently labeled carbon. Carbon by difference would require an explicit oxygen/other-element assumption. Sodium, mercury and arsenic have reported whole-crude totals but insufficient cut allocation here, so their pseudocomponent fields are absent, not zero.

C7 asphaltenes and micro carbon residue are carried as separate analytical indicators only when all contributing source cuts report them. They are not extra feed mass, elemental carbon, a full SARA composition, or guaranteed reactor coke yields. In particular, WTI's rounded whole-crude asphaltene zero is not used to erase its positive reported residue value.

Adding these profiles can distinguish future reactor feeds chemically; it does not make the unchanged TJL thermodynamic properties reproduce each real crude's density, VLE, viscosity or heat behavior. Full elemental reaction closure still requires the missing C/O information; kinetics and reaction heats require process-specific datasets.

## Modeled cut compositions

Values are fractions of each component's mass, not of whole crude. All petroleum pseudocomponent values are estimated mappings; exact chemical values follow their formulas. Full precision, methods, indicators and reconciliation data are in [cut-quality-profiles.json](../../crude-assays/cut-quality-profiles.json).

### H (wt%)

| Component | WTI Light - Export | Upper Zakum | Bonga | Dalia | Cold Lake Blend |
|---|---:|---:|---:|---:|---:|
| Methane | 25.1325 | 25.1325 | 25.1325 | 25.1325 | 25.1325 |
| Ethane | 20.1131 | 20.1131 | 20.1131 | 20.1131 | 20.1131 |
| Propane | 18.2870 | 18.2870 | 18.2870 | 18.2870 | 18.2870 |
| Isobutane | 17.3422 | 17.3422 | 17.3422 | 17.3422 | 17.3422 |
| N-butane | 17.3422 | 17.3422 | 17.3422 | 17.3422 | 17.3422 |
| Isopentane | 16.7648 | 16.7648 | 16.7648 | 16.7648 | 16.7648 |
| N-pentane | 16.7648 | 16.7648 | 16.7648 | 16.7648 | 16.7648 |
| tjl19_pc01 | 15.6025 | 16.0540 | 15.3126 | 15.1682 | 15.6720 |
| tjl19_pc02 | 14.4379 | 14.6909 | 14.0049 | 14.3402 | 14.2922 |
| tjl19_pc03 | 14.2965 | 14.2299 | 13.7000 | 13.9798 | 14.0733 |
| tjl19_pc04 | 14.3365 | 14.0075 | 13.2022 | 13.3427 | 13.5897 |
| tjl19_pc05 | 14.1762 | 13.6482 | 12.9353 | 13.0344 | 13.0295 |
| tjl19_pc06 | 14.0417 | 13.2577 | 12.5988 | 12.8144 | 12.4614 |
| tjl19_pc07 | 13.8572 | 12.8204 | 12.1904 | 12.4681 | 11.8445 |
| tjl19_pc08 | 13.6000 | 12.4000 | 11.8000 | 12.3000 | 11.6000 |
| tjl19_pc09 | 13.6000 | 12.1101 | 11.6053 | 12.2192 | 11.5829 |
| tjl19_pc10 | 13.9979 | 11.8112 | 11.6681 | 12.2341 | 11.2095 |
| tjl19_pc11 | 14.3414 | 11.8179 | 11.5570 | 12.2551 | 11.0661 |
| tjl19_pc12 | 14.3414 | 11.8179 | 11.5570 | 12.2551 | 11.0661 |
| tjl19_pc13 | 14.3414 | 11.8179 | 11.5570 | 12.2551 | 11.0661 |

### S (wt%)

| Component | WTI Light - Export | Upper Zakum | Bonga | Dalia | Cold Lake Blend |
|---|---:|---:|---:|---:|---:|
| Methane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Ethane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Propane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isobutane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-butane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isopentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-pentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc01 | 0.0043 | 0.0276 | 0.0013 | 0.0136 | 0.0554 |
| tjl19_pc02 | 0.0058 | 0.0412 | 0.0084 | 0.0184 | 0.0630 |
| tjl19_pc03 | 0.0142 | 0.0830 | 0.0288 | 0.0343 | 0.2607 |
| tjl19_pc04 | 0.0187 | 0.1942 | 0.0701 | 0.0641 | 0.6465 |
| tjl19_pc05 | 0.0240 | 0.5565 | 0.1436 | 0.1265 | 1.1958 |
| tjl19_pc06 | 0.0673 | 1.1556 | 0.2356 | 0.2624 | 1.8851 |
| tjl19_pc07 | 0.1219 | 1.7430 | 0.2980 | 0.4048 | 2.5996 |
| tjl19_pc08 | 0.1341 | 2.2754 | 0.3221 | 0.5034 | 3.1402 |
| tjl19_pc09 | 0.1693 | 2.9200 | 0.4053 | 0.5950 | 3.5213 |
| tjl19_pc10 | 0.3480 | 4.7567 | 0.5977 | 0.8539 | 5.8777 |
| tjl19_pc11 | 0.4375 | 5.4490 | 0.6697 | 0.9502 | 6.7405 |
| tjl19_pc12 | 0.4375 | 5.4490 | 0.6697 | 0.9502 | 6.7405 |
| tjl19_pc13 | 0.4375 | 5.4490 | 0.6697 | 0.9502 | 6.7405 |

### N (ppmw)

| Component | WTI Light - Export | Upper Zakum | Bonga | Dalia | Cold Lake Blend |
|---|---:|---:|---:|---:|---:|
| Methane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Ethane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Propane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isobutane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-butane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isopentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-pentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc01 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc02 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc03 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc04 | 0.6347 | 0.0000 | 14.9391 | 2.0897 | 11.5400 |
| tjl19_pc05 | 2.8556 | 4.4059 | 52.0642 | 12.8387 | 33.9413 |
| tjl19_pc06 | 22.6385 | 67.5701 | 167.2714 | 62.5328 | 95.3617 |
| tjl19_pc07 | 82.1483 | 278.2295 | 490.1711 | 264.8934 | 364.6040 |
| tjl19_pc08 | 185.8740 | 652.0239 | 1270.5288 | 789.9815 | 1039.1056 |
| tjl19_pc09 | 337.8167 | 1086.2140 | 2656.1984 | 1665.7131 | 1977.0315 |
| tjl19_pc10 | 867.6349 | 2432.2422 | 6540.8079 | 5368.0483 | 5509.1453 |
| tjl19_pc11 | 1198.1877 | 3004.1099 | 8536.5530 | 7124.8335 | 6925.3685 |
| tjl19_pc12 | 1198.1877 | 3004.1099 | 8536.5530 | 7124.8335 | 6925.3685 |
| tjl19_pc13 | 1198.1877 | 3004.1099 | 8536.5530 | 7124.8335 | 6925.3685 |

### Ni (ppmw)

| Component | WTI Light - Export | Upper Zakum | Bonga | Dalia | Cold Lake Blend |
|---|---:|---:|---:|---:|---:|
| Methane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Ethane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Propane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isobutane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-butane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isopentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-pentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc01 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc02 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc03 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc04 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc05 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc06 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc07 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc08 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc09 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc10 | 1.1404 | 31.7585 | 21.3816 | 49.8032 | 103.1603 |
| tjl19_pc11 | 2.1247 | 50.8121 | 39.3938 | 80.4584 | 154.0951 |
| tjl19_pc12 | 2.1247 | 50.8121 | 39.3938 | 80.4584 | 154.0951 |
| tjl19_pc13 | 2.1247 | 50.8121 | 39.3938 | 80.4584 | 154.0951 |

### V (ppmw)

| Component | WTI Light - Export | Upper Zakum | Bonga | Dalia | Cold Lake Blend |
|---|---:|---:|---:|---:|---:|
| Methane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Ethane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Propane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isobutane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-butane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isopentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-pentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc01 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc02 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc03 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc04 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc05 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc06 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc07 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc08 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc09 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc10 | 1.1404 | 35.2174 | 1.8327 | 20.6082 | 268.4387 |
| tjl19_pc11 | 2.1247 | 56.3461 | 3.3766 | 33.2931 | 400.9787 |
| tjl19_pc12 | 2.1247 | 56.3461 | 3.3766 | 33.2931 | 400.9787 |
| tjl19_pc13 | 2.1247 | 56.3461 | 3.3766 | 33.2931 | 400.9787 |

### Fe (ppmw)

| Component | WTI Light - Export | Upper Zakum | Bonga | Dalia | Cold Lake Blend |
|---|---:|---:|---:|---:|---:|
| Methane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Ethane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Propane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isobutane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-butane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Isopentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| N-pentane | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc01 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc02 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc03 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc04 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc05 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc06 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc07 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc08 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc09 | 0.0000 | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| tjl19_pc10 | 11.4040 | 0.9433 | 45.2068 | 15.4562 | 16.2690 |
| tjl19_pc11 | 21.2473 | 1.5093 | 83.2897 | 24.9699 | 24.3017 |
| tjl19_pc12 | 21.2473 | 1.5093 | 83.2897 | 24.9699 | 24.3017 |
| tjl19_pc13 | 21.2473 | 1.5093 | 83.2897 | 24.9699 | 24.3017 |

## Proposed database use and research reproducibility

A future implementation could attach profiles to assay/component identities with explicit unknown elements and a separate quality fingerprint. This is a proposal only: MaterialCatalog has no cut-quality API and the research files are not consumed by the simulation.

Future stream handling must transport component-resolved elemental mass, not repeatedly look up the original assay after blending or reaction. For two unreacted lots of the same component, q_mix = (m_A q_A + m_B q_B)/(m_A+m_B); unknown contributions must remain unknown. Downstream reaction caches must include both thermodynamic and quality/model fingerprints.

Re-extract with `python research/crude-assays/extract_quality.py <source-PDF-directory>` (pdfplumber required). Regenerate research profiles and this report with `python research/crude-assays/build_quality.py`. Run `python research/crude-assays/test_quality.py`. These tools do not write to src/ and are not called by the production composition converter.

## Producer sources

- [WTI Light - Export — WTLEX26Y](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/wti_light.pdf)
- [Upper Zakum — UPZAK26B](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/upper_zakum.pdf)
- [Bonga — BONGA25Y](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/2025/bonga.pdf)
- [Dalia — DALIA26Y](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/dalia.pdf)
- [Cold Lake Blend — CLKBL23B](https://corporate.exxonmobil.com/-/media/global/files/crude-oils/pdf/2024/cold_lake_blend.pdf)

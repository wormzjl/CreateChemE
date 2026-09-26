# GERG-2008 bubble-point references: literature (P3 WP8)

Batch `2026-09-24-coolprop-low-temperature`, stage P3, work package WP8. Report: `documentation/2026-09-24-coolprop-low-temperature/P3_REFERENCES_WP8_WP10.md` part A. Builder: `tools/gerg-bubble-points/` (README there); its output is the tracked fixture `src/test/resources/science/thermo/gerg2008/pilot-binaries.json`. The other inputs (`GERG2008.cpp`, CoolProp mixture files) are in `../sources/` and listed in `../sources/MANIFEST.md`.

| File | Bytes | sha256 | Source, licence, purpose |
|---|---|---|---|
| `literature/gerg-tm15-2007-kunz-klimeck-wagner-jaeschke.pdf` | 8510478 | `eed3a0ded9d7fbd298c2a72c3ab1de8fde69da89407ce1034246b84f0e93dfde` | O. Kunz, R. Klimeck, W. Wagner, M. Jaeschke, "The GERG-2004 Wide-Range Equation of State for Natural Gases and Other Mixtures", GERG Technical Monograph 15, Fortschritt-Berichte VDI 6/557 (2007). https://www.gerg.eu/wp-content/uploads/2019/10/TM15.pdf, fetched 2026-09-24. Free download from GERG; copyright GERG/VDI; local research copy only. Used for the stated uncertainties (Table 7.19, printed page 191, and section 8, methane-nitrogen pTxy discussion) |

Check made: the ten reducing parameters of the pilot pairs in `GERG2008.cpp` (betaT, gammaT, betaV, gammaV of CH4-N2, CH4-CO2, CH4-C2H6, N2-CO2, N2-C2H6, CO2-C2H6; spot values 0.998721377, 0.99809883, 1.02262449, 0.996336508, 0.977794634, 1.005894529, 1.007671428, 1.013871147, 0.90094953, 1.049707697) each occur in the monograph's text, so GERG-2008 kept the GERG-2004 binary equations of these pairs and the monograph's uncertainty table applies to them.

# VDU: literature cases and thermo-data limits (research only)

Date: 2026-09-08. Branch `claude/v3-literature-cdu-handoff-3179dc` at `530ee05`. No solver was run; the only
computation is a pure-component vapour-pressure comparison (`build/pkgcmp/vdu-lit/psat-check.mjs`,
`psat-validate.mjs`). Extracted source texts are in `build/pkgcmp/vdu-lit/*.txt` (gitignored).

Companion: `documentation/VDU_SIMULATION_RESEARCH.md` (main checkout, 2026-08-31) covers the process
description, the topology gaps and the phased route. This document answers three narrower questions:

1. Which published VDU cases carry enough data to be a benchmark, and which one to adopt.
2. What the current `createcheme:tjl19_dwsim` data can and cannot do at 1–13 kPa.
3. What, if anything, must be pulled from the local DWSIM 10.2.3 installation.

---

## 1. Literature cases

Summary table. "Spec-complete" means every input a MESH solver needs is published (feed, stages, pressures,
draws, pumparound duties/temperatures, steam, top boundary). "Assay" means the feed can be re-characterized.

| # | Source | Column | Spec-complete | Assay | Thermo | Verdict |
|---|---|---|---|---|---|---|
| A | Ji & Bagajewicz, IECR 41 (2002) 6094 (Part I) and 6100 (Part II) | 7 trays, flash zone 1.90 psia (13.1 kPa), flash 382 °C, overhead 127 °C, 2 pumparounds with published duties and temperatures, overflash 0.02, bottom steam 2.74 lb/bbl residue | Yes, except top pressure and PA stage numbers | Yes: light crude 36 API, TBP to 90 % + light ends; the same crude's CDU is in Bagajewicz & Ji 2001 | PRO/II, method not stated | **Primary candidate** |
| B | Aspen HYSYS refining tutorial vacuum tower (Slideshare "Crude tower simulation HYSYS v10"; replicated by the Sudan and Baboo theses) | 14 trays with Murphree efficiencies, 50/62 mmHg, feed tray 12 at 760 °F, 20,000 lb/h coil steam, LVGO PA 4→1 22,300 bpd 42 MMBtu/h, HVGO PA 8→5 50,000 bpd cooled 150 °F, slop wax tray 11 1,000 bpd, wash 3,000 bpd, LVGO 5,000 bpd D1160 T95 915 °F, HVGO 21,000 bpd T95 1050 °F | Inputs yes, results no | 3-crude blend, wt % TBP with cut densities (tables only partly extractable from slides) | HYSYS PR | Second candidate; efficiencies and D1160 specs are not V3 concepts |
| C | Atta et al., Energies 17 (2024) 3806 (Attock refinery) | 12 stages, top 10.2 kPa, flash 13.4 kPa, bottom 25.5 kPa, flash 406 °C, steam 10 t/h at 162 °C, LVGO tray 1, HVGO tray 12; product masses LVGO 58.40 / HVGO 88.57 / gas 26.77 / VR 178.77 t/h from 352.51 t/h AR | No: no pumparound data, T profile only as a figure (120–370 °C) | Yes: whole-crude TBP (11 points) + light ends, 29 API | HYSYS PR | Assay usable; column under-specified |
| D | Jin et al., Processes 10 (2022) 359 (Chinese refinery, deep-cut) | Rigorous HYSYS V11 model: top 90 °C / 10.13 kPa, tray 10 407 °C / 13.33 kPa, tray 11 395 °C / 25.33 kPa; VDO 17,490, LVGO 69,870, HVGO 185,700, VR 240,768, gas 14,764 kg/h; steam 11 t/h 160 °C; three middle cycles 100,000 / 220,000 / 858,600 kg/h with ΔT 62 / 55 / 55 °C and duties −1.25e7 / −2.83e7 / −1.24e8 kJ/h (3.5 / 7.9 / 34.4 MW) | Yes (plant data reconciled) | No: AR distillation only as a figure | BK10 (chosen over PR/GS for the vacuum column) | Not reproducible without the assay; use as a sanity band (duty split, temperatures) |
| E | Sudan Univ. thesis (Khartoum residue, HYSYS) | Tutorial-B workflow on its own residue; PA duties 15.7 / 19.0 MW, heater 24 MW, feed 352→404 °C; mass balance 311 t/h | Column table is an image | No | HYSYS | Weak |
| F | Liu/Chang/Pashikanti 2018 (Wiley) ch. 3, Kaes 2000, Watkins 1979 ch. on vacuum towers | Textbook plant cases | Unknown | Unknown | — | Paywalled / not online; not used |
| G | Ledezma-Martínez 2019 thesis (our CDU source) | — | — | — | — | **No VDU**; listed only as future work. No same-assay literature VDU exists in what was found. |

### 1.1 Case A in detail (Ji & Bagajewicz 2002)

Vacuum tower specifications, Part I Table 1 (verbatim values):

| Item | Value |
|---|---|
| Total number of trays | 7 |
| Flash zone pressure | 1.90 psia = 13.1 kPa |
| LVGO D86 95 % temperature | 410 °C (equivalent to 30 vol % of total VGO) |
| Flash zone temperature | 382 °C |
| Overflash ratio | 0.02 |
| Overhead temperature | 127 °C |
| Bottom steam / vacuum residue | 2–3 lb/bbl (2.74 used) |

Vacuum streams, Part II Table 1 (conventional design, light crude): vacuum PA1 232.2 → 93.3 °C, 7.20 MW;
vacuum PA2 312.8 → 176.7 °C, 7.33 MW; LVGO drawn at 232.2 °C; HVGO at 312.8 °C; vacuum residue at 371.1 °C.
The quench specification in the text is "circulate partially cooled bottoms to quench the liquid to 365 °C".

Yields, Part I Table 5 (conventional): LVGO 22.92, HVGO 81.43, vacuum residue 105.53, vacuum overhead 0.202 m³/h
on 795 m³/h crude. So the atmospheric residue fed to the VDU is 210.1 m³/h = 26.4 vol % of crude, i.e. the TBP tail
above about 73.6 vol %. Stripping steam = 2.74 lb/bbl × 663.8 bbl/h residue ≈ 825 kg/h ≈ 45.8 kmol/h (small).
Crude (Appendix): 845 kg/m³ (36.0 API); TBP °C at 5/10/30/50/70/90 vol % = 45/82/186/281/382/552; light ends
vol % propane 0.78, isobutane 0.49, n-butane 1.36, isopentane 1.05, n-pentane 1.30 (ethane 0.13 in the course notes).

What is not published: top pressure (typical 10 kPa; must be assumed), PA draw/return stage numbers (the
temperatures imply PA1 at the LVGO draw and PA2 at the HVGO draw), the property method in PRO/II, and the TBP
above 90 %. The atmospheric column for the same crude is fully specified in Bagajewicz & Ji 2001 (34 trays, feed
tray 29, draws at 1/9/16/25, overflash 0.03; OU course notes Table 4-4), so a full literature plant on one crude
is possible later.

V3 gaps this case exposes (unchanged from the VDU research doc): no condenser node (top is a vapour exit with
PA reflux and a top-temperature spec), no reboiler (feed enters the flash zone above 1–2 stripping stages),
duty-specified pumparounds exist (WP1–WP4) but return-temperature specification does not, and overflash must be
an audited quantity. The column is small: 7 trays × ~14 components.

### 1.2 Why not the others

- **B** is the most-replicated tutorial, but its inputs are Murphree efficiencies and D1160 T95 specs. V3 has
  neither; converting them to rate specs needs the tutorial's results, which are not published.
- **C** publishes the assay but no pumparound data, so the heat removal that sets the internal reflux is unknown.
- **D** is the best operating-data set found (reconciled plant data, BK10, three cycles with duties), but its
  feed exists only as a figure. It remains useful as a check on duty split and tray temperatures of any VDU model.

---

## 2. Limits of the current thermo data at vacuum

Package `createcheme:tjl19_dwsim`, revision `tjl19-dwsim-10.2.3-r1`, PR78 with DWSIM-reconstructed
pseudo-components (Riazi-Daubert Tc/Pc, Lee-Kesler ω re-fitted so PR78 reproduces each cut's NBP at 1 atm).

### 2.1 Envelope declarations

| Item | Current | VDU need | Assessment |
|---|---|---|---|
| Pressure floor | 50 kPa (`minimumPressurePascal`) | 0.5–1 kPa top, 13 kPa flash zone | A declaration, not a data limit; PR78 has no floor. A VDU package must declare its own floor. |
| Temperature | 298.15–900 K (Cp fit validated) | Top 60–130 °C, flash zone 380–420 °C (653–693 K), heater outlet ≤ 705 K | Covered |
| Water | IAPWS saturation to the triple point (611 Pa); enthalpy 273–900 K | Steam partial pressure at a VDU top 1–5 kPa → dew point 7–33 °C | Covered; no in-column free water is expected, the wet-tray machinery is not needed for a VDU |

### 2.2 Slate resolution across the VGO range

Cuts covering the VDU range in the 19-component package (NBP °C): PC06 305, PC07 354, PC08 402, PC09 469,
PC10 558, PC11 661, PC12 800, PC13 950. The HVGO cut point of a fuels VDU is 538–566 °C TBP (1000–1050 °F), i.e.
exactly PC10's NBP, with a 103 °C hole to PC11. The HVGO/residue split and the overflash would be carried by one
component boundary. This is the single most important data limitation for products.

The unmerged 31-component DWSIM dataset (`run/codex-worktrees/v3-literature-cdu/output/cdu-characterization/
tjl-dwsim-10.2.3/components.json`, already characterized) has NBP_538 / 581 / 625 / 685 / 771 / 858 / 950 in that
region (43–87 °C spacing). A TJL VDU basis of NBP_366 … NBP_950 (12 pseudos, light ends and naphtha dropped) can
be generated from existing files with `generate-v3-tjl19.py` logic; **no new DWSIM pull is required for TJL**.

### 2.3 Acentric factors of the residue cuts

PC12 ω = 2.11, PC13 ω = 3.61 (unmerged: NBP_771 2.02, NBP_858 2.27, NBP_950 3.61). The PR78 κ polynomial was fitted
for ω up to about 2; beyond that both PR78 and Lee-Kesler are extrapolations (they disagree by 4–14 K in saturation
temperature, §2.4). At VDU temperatures these cuts have K ≈ 1e-4 to 1e-7, so they do not move products; they only
feed the trace-flow machinery (truncation, support refresh) that the CDU work already hardened.

### 2.4 PR78 against the vacuum-industry vapour-pressure methods

Saturation temperature of each cut at 1 / 5 / 13 kPa from three methods: PR78 as in `V3PengRobinsonKernel`
(production model), Lee-Kesler 1975 (API 5A1.15, the correlation the ω values were estimated from), and
Maxwell-Bonnell (API 5A1.18 with the K_w correction; the basis of BK10, the method commercial guidance and
case D use for vacuum columns). Differences are PR78 minus reference, in K; the ratio is PR78 vapour pressure over
the reference's at the reference saturation temperature.

| Cut | NBP K | ω | K_w | 1 kPa: PR−LK / PR−MB | 5 kPa | 13 kPa | P_PR/P_MB at 13 kPa |
|---|---|---|---|---|---|---|---|
| PC06 | 577.9 | 0.580 | 11.64 | −1.6 / −7.2 | −1.0 / −5.6 | −0.6 / −4.3 | 1.14 |
| PC07 | 626.7 | 0.666 | 11.68 | −1.2 / −8.5 | −0.7 / −6.8 | −0.3 / −5.4 | 1.16 |
| PC08 | 675.4 | 0.760 | 11.73 | −0.7 / −9.8 | −0.3 / −8.0 | −0.1 / −6.3 | 1.18 |
| PC09 | 742.5 | 0.911 | 11.81 | 0.0 / −11.4 | 0.0 / −9.4 | 0.0 / −7.6 | 1.20 |
| PC10 | 831.0 | 1.155 | 11.90 | +0.7 / −12.7 | +0.1 / −10.7 | −0.4 / −8.7 | 1.22 |
| PC11 | 934.3 | 1.534 | 11.99 | +0.7 / −12.3 | −1.0 / −10.8 | −2.1 / −8.9 | 1.22 |
| PC12 | 1073.1 | 2.114 | 11.91 | −1.3 / −13.7 | −4.2 / −12.2 | −5.9 / −10.2 | 1.25 |
| PC13 | 1222.9 | 3.612 | 12.09 | −8.2 / −0.1 | −12.4 / −2.5 | −14.1 / −3.1 | 1.08 |

Validation of the three implementations on pure n-alkanes with published vapour-pressure points (CRC): n-C16 at
1 / 10 / 100 mmHg, n-C20 at 1 / 10 mmHg, n-C24 at 1 mmHg. All three land within 3 K of the data (PR78 −2.3 … +2.1 K,
LK −1.4 … +2.1 K, MB −2.8 … +0.6 K). So the 4–13 K spread on the pseudo-cuts is not a coding error; it comes from
the pseudo-component parameters: PR78 and LK share the estimated Tc/Pc/ω and agree with each other (PC06–PC11
within 2 K), while Maxwell-Bonnell uses only NBP and K_w and was built from sub-atmospheric petroleum-fraction data.

Consequences:

- At a given tray temperature the solver's K-values for the VGO cuts are 14–25 % above what a BK10/MB simulator
  would use (at 13 kPa; 35–52 % at 1 kPa). Equivalent temperature bias: the same separation happens 4–13 K colder
  in V3. That is a systematic bias in cut points and yields, not a convergence issue.
- Case A was solved in PRO/II with an unstated method, case D explicitly in BK10. A benchmark against either must
  budget this bias, or the VDU package must be anchored differently. Two-parameter anchoring (ω and Tc per cut fitted
  to both the NBP at 760 mmHg and the MB point at 10 mmHg) is a pure data revision inside the package contract and
  needs no DWSIM; it should be a decision, not a default.
- This does not affect the CDU: at 100–250 kPa all methods agree within 0.5 K (table, 101 kPa column of the script
  output).

### 2.5 Other observations

- Cracked-gas make and methane are not in the slate; VDU overhead non-condensables come from thermal cracking, not
  equilibrium, so this stays a boundary-condition yield (D8 of the research doc).
- The binary-interaction matrix is zero among pseudo-components; fine for a near-ideal vapour at 1–13 kPa.
- The ideal-gas Cp fit is exercised up to 900 K only; a heater-outlet stream at 705 K is inside.

---

## 3. What to pull from DWSIM, and when

| Need | Trigger | Route | Status |
|---|---|---|---|
| Light-crude (36 API) characterization for case A | Adopting case A | Write `source-assay.json` from Part I Tables A.2/A.3 (TBP to 90 %, tail extrapolated to a declared end point), run the existing `CharacterizeTjl.cs` pipeline (copy `scripts/dwsim/*` from the codex worktree into this worktree; `csc.exe` + DWSIM 10.2.3 API). Residue-only basis: cut at ~73.6 vol % | Not started; the TBP tail extrapolation is a documented assumption |
| TJL VDU basis | Adopting a non-literature TJL VDU | Reuse the 31-component `components.json` heavy tail | Data exists; no pull |
| Second vacuum-listed K-method (Grayson-Streed) for the VGO cuts at 1–13 kPa | Optional, to arbitrate the PR–MB spread | Small C# helper against `DWSIM.Thermodynamics` (GS package, `DW_CalcKvalue`) | Optional |
| Maxwell-Bonnell / BK10 | — | DWSIM has neither; the reference is the API equations already implemented in `psat-check.mjs` | Done |

DWSIM's own pseudo-component vapour pressure inside PR is the EOS itself; it offers no independent low-pressure
data. The independent references for this work are the API correlations, not DWSIM.

---

## 4. Recommendation and open decisions

Recommendation: adopt **case A (Ji & Bagajewicz 2002)** as the VDU literature benchmark. It is the only case that
is both specification-complete and re-characterizable, it is small (7 trays), and its atmospheric column is also
published, so the whole plant can later run on one literature crude. Its shortcomings (top pressure, PA stages,
thermo method, TBP tail) are declarable assumptions of the same kind the Ledezma reconstruction already carries.

Decisions for the maintainer:

1. **Case**: A (recommended), B, or a non-literature TJL VDU fed by the V3 CDU bottoms.
2. **K-value policy for the VDU package**: PR78 as characterized (document the 4–13 K bias against Maxwell-Bonnell),
   or a two-point MB-anchored refit of ω/Tc per cut (new revision string, golden K-values at 13 kPa and 760 mmHg).
3. **Feed basis for case A**: residue-only re-cut of the TBP tail (recommended; matches the VDU research doc D3),
   or whole-crude characterization plus a literature CDU (Bagajewicz & Ji 2001) to derive the residue.

Nothing in this document changes code, data or tests.

---

## 5. Sources

- Ji, S.; Bagajewicz, M. "Design of Crude Distillation Plants with Vacuum Units. I. Targeting", Ind. Eng. Chem. Res.
  2002, 41, 6094–6099 — PDF: http://www.ou.edu/class/che-design/pub-papers/Design%20of%20Crude%20Distillation%20Plants%20with%20vacuum%20units-Targeting%20I(Ji-Bagajewicz)-02.pdf
- Ji, S.; Bagajewicz, M. "… II. Heat Exchanger Network Design", ibid. 6100–6106 — PDF: http://www.ou.edu/class/che-design/pub-papers/Design%20of%20Crude%20Distillation%20Plants%20with%20vacuum%20units-HEN-II(Ji-Bagajewicz)-02.pdf
- Bagajewicz course notes "Petroleum Fractionation — Design Procedure" (light-crude CDU tables, light ends):
  https://www.ou.edu/class/che-design/che5480-07/Petroleum%20Fractionation-Design%20Procedure.pdf
- Aspen HYSYS refining tutorial, "Crude tower simulation HYSYS v10": https://www.slideshare.net/slideshow/crude-tower-simulationhysysv10/175829561
- Atta, Khan, Ali et al., "Simulation of Vacuum Distillation Unit in Oil Refinery: Operational Strategies for Optimal
  Yield Efficiency", Energies 2024, 17, 3806, https://doi.org/10.3390/en17153806 (mirror used: karlancer.com)
- Jin, Q. et al., "Optimization Study on Enhancing Deep-Cut Effect of the Vacuum Distillation Unit (VDU)", Processes
  2022, 10, 359, https://doi.org/10.3390/pr10020359 (mirror used: psecommunity.org LAPSE-2023.3026)
- Sudan Univ. of Sci. & Tech., "Simulation of VDU and improving its productivity using Aspen HYSYS":
  https://repository.sustech.edu/bitstream/handle/123456789/11850/SIMULATION%20OF%20VACUUM%20....pdf
- API Technical Data Book procedures 5A1.15 (Lee-Kesler) and 5A1.18 (Maxwell-Bonnell); CRC Handbook vapour-pressure
  points for n-C16/C20/C24 used for validation.
- Internal: `V3Tjl19DwsimPackage.java`, `V3PengRobinsonKernel.kappa`, `V3WaterProperties.java`,
  `V3OperatingDomainValidator.java`; `run/codex-worktrees/v3-literature-cdu/scripts/dwsim/*` and
  `output/cdu-characterization/tjl-dwsim-10.2.3*/` (read only).

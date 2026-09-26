# P4 stage 1: the solid CO2 model (Jäger and Span 2012) on the P2 crystal contract

Batch `2026-09-24-coolprop-low-temperature`, stage P4 stage 1, 2026-09-25. Branch `claude/coolprop-multiphase-thermo-37f6b0`,
base `f0d4cf8` (P3 WP2b). Commits: see section 12.

## 0. Outcome

- The CO2-I crystal record is now the Gibbs energy equation of Jäger and Span 2012 (JS2012), a second model kind of the
  `crystals` record (`gibbs_function`, `type: jaeger_span_2012`), revision `crystal-carbon_dioxide-i-r2`, grade
  `estimated_declared_error`. The P2 segment model stays for synthetic and future records; its tests are unchanged and green.
- Port: all twelve Table 5 check values reproduce to the printed ten digits (worst 0.48 of a unit in the last digit); the
  analytic derivatives of Tables 3 and 4 agree with central differences to 6.5e-8 relative over 80 to 300 K and 1 kPa to
  499 MPa; evaluation outside 80 to 300 K or (0, 500 MPa] is refused.
- Anchoring (P2 contract): g0 and g1 are solved at runtime from the fluid family's pure liquid at the triple point
  (g_s = mu_L, s_s = (h_L - mu_L)/T_t - dH_fus/T_t). `SolidPhaseEvaluator.TriplePointAnchor` gained the liquid enthalpy;
  `CrystalPhaseEvaluator` implements the evaluator on the record and computes the anchor from any `PhaseEvaluator`.
- Validation independent of reference states: anchored to the Span-Wagner oracle's liquid with 8875 J/mol, the solver
  recovers the paper's own g0 and g1 (differences 3.6e-5 and 9.7e-7) and reproduces its Figures 9 and 11: sublimation
  within the Span-Wagner uncertainty except from 170 to 185 K, where it reaches 1.50 times the 100 Pa band as the paper's
  own line does; melting within the uncertainty to 270 K (worst 0.93 of the band).
- Pilot family (`createcheme:pilot_cryogenic`, translated PR78, Soave, spine), fusion enthalpy 9019 J/mol: sublimation
  pressure within 2.94 % of Span-Wagner and Fray-Schmitt from 150 K to the triple point (P0 target 5 %), 3.93 % of
  Fray-Schmitt at 130 K; melting temperature within 0.125 K of Span-Wagner up to the family's 10 MPa ceiling; sublimation
  enthalpy at 195 K 25 185 J/mol, -0.06 % from 25.2 kJ/mol (P0 target 2 %). With JS2012's 8875 J/mol the sublimation
  target fails (6.66 % at 150 K). Decision: 9019 J/mol, a family-specific effective value (section 5, proposed D16).
- Pins: the pilot spine fingerprint moved once (`1b33b193...` to `581c9098...`), the eight bundled package pins and the
  pilot's scientific revision and physics fingerprint did not. Nothing in the network reads a crystal.

## 1. Sources

| Source | Local copy (main checkout `research/2026-09-24-coolprop-low-temperature/sources/solid-co2/`) | Used for |
|---|---|---|
| Jäger and Span 2012, J. Chem. Eng. Data 57, 590-597, doi:10.1021/je2011677 | `jaeger-span-2012-jced-57-590.pdf`, SHA-256 `d52376688d9dcd83b42703178d313a2428824e758a444ae9e58bede21f9ce32d`; pages 1 to 8 rendered to `pages/jaeger-p01.png` .. `p08.png` | Equation 8, 9, 10, 19 to 22; Table 1 (every constant); Tables 2 to 4 (property relations, derivatives); Table 5 (check values); Figures 9 and 11 (sublimation and melting against Span-Wagner); validity 80 to 300 K, 0 to 500 MPa |
| Trusler 2011, J. Phys. Chem. Ref. Data 40, 043105, doi:10.1063/1.3664915 | `trusler-2011-jpcrd-40-043105.pdf`, SHA-256 `692eb94231f8df00795d97e8b0ef8bfa52ddd238d7518aaddf33c4da68231586`; `pages/trusler-p01.png` .. `p19.png` | Independent cross-check only (not implemented): equations 47 to 50 (section 9), Table 6 (V00), section 7.1 (fusion enthalpy 8870 J/mol from the two equations; the three calorimetric values 7950, 8050, 8340 J/mol), Figure 8 (sublimation enthalpy) |
| Span and Wagner 1996, J. Phys. Chem. Ref. Data 25, 1509 | not held; melting line from the bundled CoolProp `CarbonDioxide.json` (`ANCILLARIES.melting_line`, BibTeX `Span-JPCRD-1996`, a1 1955.539, a2 2055.4593, T_0 216.592 K, p_0 517 950 Pa); sublimation equation 3.12 transcribed (below) | The correlations of Figures 9 and 11 and their stated uncertainties (as JS2012 quotes them) |
| Fray and Schmitt 2009, Planet. Space Sci. 57, 2053 | reproduced in Table 2 of the supplementary appendix of Lisse et al. 2020, `research/.../sources/fray-schmitt-2009/arxiv-2009.02277.pdf` page 35 | Sublimation holdout (P0) |

Transcription notes.

- Table 1 digits were read from a 400 dpi render of page 591 and checked against the PDF text layer, which carries every
  mantissa correctly but drops the minus signs and exponent signs; signs and exponents come from the image.
- The Span-Wagner sublimation equation 3.12 is not in the CoolProp file and the paper is not held locally. The record
  carries ln(p/p_t) = (T_t/T)[a1 (1 - T/T_t) + a2 (1 - T/T_t)^1.9 + a3 (1 - T/T_t)^2.9] with a1 = -14.740846,
  a2 = 2.4327015, a3 = -5.3061778, transcribed from the published form. It is confirmed numerically: it gives
  101 325.31 Pa at Span and Wagner's normal sublimation temperature 194.6855 K (the value the pilot property record
  carries), 3.1e-6 from 1 atm, and agrees with Trusler's equation 48 (his solid with the Span-Wagner fluid, 150 to
  216 K) within 0.12 % from 185 K to T_t and 0.36 % from 150 K. The lead may want the paper itself to close this.
- The Fray-Schmitt reproduction gives four significant digits and no units or ranges. Units are bar (both CO2 fits give
  1.01 to 1.02 bar at 194.7 K); CO2-1 is used below 194.7 K and CO2-2 above. The two fits differ by 1.2 % at 194.7 K,
  which shows in every comparison as a step at that temperature.
- Trusler 2011 may have a published erratum (J. Phys. Chem. Ref. Data 41, 2012, or similar). It was not searched for
  (no web search, as instructed) and is unverified. One finding points at such an erratum: equation 49 (molar volume on
  the melting curve) as printed, V = V_m,t [1 + d7 x + d8 x^2 + d9 x^3] with x = T/T_t - 1, rises from 28.56 cm3/mol at
  T_t to 37.9 cm3/mol at 800 K (about 7 GPa), which contradicts his Figure 17(a) (melt line near 21 cm3/mol at high
  pressure) and compression. The reciprocal reading V = V_m,t / [1 + ...] gives 21.5 cm3/mol at 800 K and agrees with
  JS2012 within 0.2 % from T_t to 270 K. Equation 49 is therefore not used as a gate.

## 2. The model and its record

### 2.1 Equation

g/(R T0) = g0 + g1 dt + g2 dt^2
 + g3 { ln[(t^2 + g4^2)/(1 + g4^2)] - (2t/g4) [atan(t/g4) - atan(1/g4)] }
 + g5 { ln[(t^2 + g6^2)/(1 + g6^2)] - (2t/g6) [atan(t/g6) - atan(1/g6)] }
 + g7 dp [exp(f_a(t)) + K(t) g8] + g9 K(t) [(p + g10)^((n-1)/n) - (1 + g10)^((n-1)/n)]

with t = T/T0, dt = t - 1, p = P/p0, dp = p - 1 (equations 8 and 9), T0 = 150 K, p0 = 101 325 Pa, R = 8.314472 J/(mol K)
(the model's own constant, kept), n = 7,

f_a(t) = ga0 (t^2 - 1) + ga1 ln[(t^2 - ga2 t + ga3)/(1 - ga2 + ga3)] + ga4 ln[(t^2 + ga2 t + ga3)/(1 + ga2 + ga3)]
 + ga5 [atan((t - ga6)/ga7) - atan((1 - ga6)/ga7)] + ga8 [atan((t + ga6)/ga7) - atan((1 + ga6)/ga7)]   (21)

K(t) = gk0 t^2 + gk1 t + gk2   (22)

| Constant | Value | Constant | Value |
|---|---|---|---|
| g0 (published) | -2.6385478 | ga0 | 3.9993365e-2 |
| g1 (published) | 4.5088732 | ga1 | 2.3945101e-3 |
| g2 | -2.0109135 | ga2 | 3.2839467e-1 |
| g3 | -2.7976237 | ga3 | 5.7918471e-2 |
| g4 | 2.6427834e-1 | ga4 | 2.3945101e-3 |
| g5 | 3.8259935 | ga5 | -2.6531689e-3 |
| g6 | 3.1711996e-1 | ga6 | 1.6419734e-1 |
| g7 | 2.2087195e-3 | ga7 | 1.7594802e-1 |
| g8 | -1.1289668 | ga8 | 2.6531689e-3 |
| g9 | 9.2923982e-3 | gk0 | 2.2690751e-1 |
| g10 | 3.3914617e3 | gk1 | -7.5019750e-2 |
| n | 7 | gk2 | 2.6442913e-1 |

Derivatives (Tables 3 and 4, implemented analytically): dg/dT = R G_t, dg/dP = (R T0/p0) G_p, d2g/dT2 = (R/T0) G_tt,
d2g/dTdP = (R/p0) G_tp, d2g/dP2 = (R T0/p0^2) G_pp. Properties (Table 2): v = dg/dP, s = -dg/dT, h = g - T dg/dT,
u = h - P v, f = g - P v, c_p = -T d2g/dT2, alpha = (d2g/dTdP)/(dg/dP), kappa = -(d2g/dP2)/(dg/dP).

### 2.2 Record kind `gibbs_function`

`crystals/carbon_dioxide_i.json`, revision `crystal-carbon_dioxide-i-r2`: a record carries either the P2 segment model
(`heat_capacity`, `molar_volume`, `transitions`) or a `gibbs_function`, never both (refused, naming the field).

| Field of `gibbs_function` | Rule |
|---|---|
| `type` | exactly `jaeger_span_2012` |
| `reference_temperature_kelvin`, `reference_pressure_pascal` | T0, p0, positive |
| `reference_molar_volume_m3_per_mol` | v0 of equation 10, positive; not an input of equation 8, carried for the check v(T0, p0) = v0 |
| `gas_constant_j_per_mol_kelvin` | the model's R, positive |
| `n` | finite, above 1 |
| `g2_to_g10` | exactly 9 numbers; g4, g6 nonzero; g10 positive |
| `g_alpha_0_to_8` | exactly 9 numbers; ga7 nonzero; the f_a logarithms defined at T0 |
| `g_kappa_0_to_2` | exactly 3 numbers |
| `published_g0`, `published_g1` | the paper's integration constants; used only by the Table 5 check, never at runtime |
| `temperature_min_kelvin`, `temperature_max_kelvin`, `pressure_min_pascal` (may be 0), `pressure_max_pascal` | validity 80..300 K, 0..5e8 Pa; evaluation needs T in the closed range and 0 < P <= max |
| `source` | text |

Load checks: volume, heat capacity and compressibility positive at the corners and midpoints of the range. The `anchor`
object is unchanged (`triple_point`, 216.592 K, 517 950 Pa, `fusion_enthalpy_j_per_mol` 9019). A record whose function
range does not contain its anchor is incomplete (research_only or unavailable only; every evaluation refused).

Spine fingerprint content of a Gibbs-function crystal: [component, crystal text, [`jaeger_span_2012`, T0, p0, v0, R, n,
g2..g10, ga0..ga8, gk0..gk2, [published g0, g1], [T and P range]], [anchor T, P, fusion enthalpy]]. A segment record
hashes exactly as in P2 (the list layout is kept byte-identical).

### 2.3 Java

| Class | Role |
|---|---|
| `science.material.JaegerSpanGibbsFunction` | Equation 8 with its analytic derivatives: `evaluate(T, P, g0, g1)` returns a `State` (g and the five derivatives) with the Table 2 properties; `integrationConstants(T, P, g, s)` solves g0 and g1; `covers`, the range, the published constants, v0 |
| `science.material.CrystalReference` | Now two kinds behind one private `Model` interface (`Kind.SEGMENTS`, `Kind.JAEGER_SPAN_2012`). Kept API: `heatCapacity`, `molarVolume`, `enthalpyChange`, `entropyChange`, `gibbsChange`, `anchorEntropy`, `gibbs(T, P, mu_L, h_L)`. New: `expansion`, `compressibility`, `minimumPressure`, `maximumPressure`, `kind`, `gibbsFunction`, `integrationConstants(mu_L, h_L)`, `state(T, P, mu_L, h_L)` (g, h, s, v, c_p, alpha, kappa in one call; one function evaluation for the Gibbs kind) |
| `science.thermo.phase.SolidPhaseEvaluator` | `TriplePointAnchor(component, T, P, chemicalPotential, liquidEnthalpy)`: the liquid enthalpy is new (section 3) |
| `science.thermo.phase.CrystalPhaseEvaluator` | The evaluator on a crystal record: `of(record, idealGas)`, `forPackage(catalog, packageId, crystalId)` (the package's spine of the species as ideal gas), `anchor(PhaseEvaluator fluid)` (the family's pure liquid at the record's triple point), `evaluate(T, P, anchor, out)` (one-component `SOLID` state), `properties(T, P, anchor)`. Family id `crystal_jaeger_span_2012_triple_point_anchored_v1` (`crystal_segments_...` for a segment record) |

## 3. Anchoring mathematics

The P2 contract fixes the crystal at the triple point (T_t, P_t) of its record against the fluid family's pure liquid
there: g_s(T_t, P_t) = mu_L and h_s(T_t, P_t) = h_L - dH_fus (JS2012 equations 19 and 20 with the fluid family in place of
Span-Wagner). Equivalently s_s(T_t, P_t) = s_L - dH_fus/T_t with s_L = (h_L - mu_L)/T_t, which is why the anchor now
carries h_L: with the chemical potential alone the solid's absolute entropy at the anchor, and so its Gibbs energy away
from the anchor, is undetermined on the spine's reference.

Equation 8 is linear in g0 and g1. Write G = g0 + g1 dt + r(t, p), where r is the rest of equation 8. Then
s = -R (g1 + r_t) and g = R T0 (g0 + g1 dt + r), so at the anchor

  g1 = -s_s/R - r_t(t_t, p_t),   g0 = mu_L/(R T0) - g1 dt_t - r(t_t, p_t).

g0 and g1 change no volume, heat capacity, expansion or compressibility (tested bitwise). On the spine's reference they
are large (pilot family: g0 = -347.2027, g1 = -6.7245) because mu_L carries the formation enthalpy and the absolute
entropy of CO2; nothing of JS2012's reference state (Span-Wagner's) is used at runtime. The crystal therefore follows
whichever fluid family evaluates its anchor.

State filled by `CrystalPhaseEvaluator.evaluate`: kind `SOLID`, crystal = record id, root null, one component,
v = dg/dP, h and s split against the species' spine as h = h_ig(T) + (h - h_ig) and s = s_ig(T, P) + (s - s_ig),
ln phi = (g_s - g_ig(T, P))/(R T) with the kernel's gas constant 8.31446261815324, so `chemicalPotential(0)` equals g_s
and compares directly with a fluid phase's mu. The anchor must name the record's species and its triple point exactly
(refused otherwise); states outside 80 to 300 K and (0, 500 MPa] are refused by the function, below 90 K by the CO2 spine.

## 4. Validation of the port and of the anchoring (Span-Wagner oracle)

### 4.1 Table 5 (published g0 and g1)

| State | Property | Printed | Computed | Fraction of the last digit |
|---|---|---|---|---|
| 216.592 K, 0.51795 MPa | g, J/mol | -1.447007522e3 | -1.447007521518e3 | 0.48 |
| | v, m3/mol | 2.848595255e-5 | 2.848595254683e-5 | 0.32 |
| | s, J/(mol K) | -1.803247012e1 | -1.803247012357e1 | 0.36 |
| | c_p, J/(mol K) | 5.913420271e1 | 5.913420271324e1 | 0.32 |
| | alpha, 1/K | 8.127788321e-4 | 8.127788321061e-4 | 0.06 |
| | kappa, 1/Pa | 2.813585169e-10 | 2.813585169014e-10 | 0.01 |
| 100 K, 100 MPa | g | -2.961795962e3 | -2.961795962212e3 | 0.21 |
| | v | 2.614596591e-5 | 2.614596590611e-5 | 0.39 |
| | s | -5.623154438e1 | -5.623154437761e1 | 0.24 |
| | c_p | 3.911045710e1 | 3.911045710292e1 | 0.29 |
| | alpha | 3.843376525e-4 | 3.843376524847e-4 | 0.15 |
| | kappa | 1.149061787e-10 | 1.149061786540e-10 | 0.46 |

Every value rounds to the printed one. v(T0, p0) = 2.7186286578e-5 against the printed v0 2.7186286e-5 (2.1e-8).
Analytic derivatives against central differences at 63 states (80.01 to 299.99 K, 1 kPa to 499 MPa): worst 6.5e-8
relative (gate 1e-6). Table 2 relations exact; v, c_p, alpha, kappa positive everywhere tested.

### 4.2 Anchored to the oracle liquid with 8875 J/mol

The Java Helmholtz oracle (CoolProp's Span-Wagner CO2 file) gives, at 216.592 K and 517 950 Pa, liquid mu = -1446.962169
J/mol, h = 3522.347318 J/mol, s = 22.94318113 J/(mol K), v = 3.734510e-5 m3/mol. Solved constants: g0 = -2.638511866
(published -2.6385478, difference 3.6e-5), g1 = 4.508874168 (published 4.5088732, 9.7e-7). The g0 difference times R T0
is 0.0448 J/mol, the oracle's own vapour-liquid chemical-potential mismatch at 517 950 Pa (-0.0453 J/mol; the equation's
triple pressure is 517 964 Pa): JS2012 evidently fixed g0 on the vapour (equation 19 allows either). The anchored solid's
entropy at the triple point is Table 5's to 8e-6 J/(mol K).

Sublimation (the paper's Figure 9, dp = p_SW(3.12) - p_calc, Span-Wagner's uncertainty 50/100/250 Pa):

| T, K | p_calc, Pa | p_SW, Pa | dp, Pa | band, Pa | vs Trusler 48 | vs Fray-Schmitt |
|---|---|---|---|---|---|---|
| 80 | 5.665e-6 | 2.169e-6 | -0.000 | 50 | | +2.92 % |
| 100 | 1.933e-2 | 1.341e-2 | -0.006 | 50 | | +1.91 % |
| 120 | 4.200 | 3.736 | -0.46 | 50 | | +1.57 % |
| 140 | 188.70 | 183.56 | -5.1 | 50 | | +1.45 % |
| 150 | 852.84 | 842.65 | -10.2 | 50 | +0.85 % | +1.31 % |
| 160 | 3166.1 | 3147.5 | -18.6 | 50 | +0.62 % | +1.10 % |
| 170 | 10 003.3 | 9960.4 | -42.9 | 100 | +0.48 % | +0.84 % |
| 180 | 27 666.8 | 27 557.1 | -109.7 | 100 | +0.35 % | +0.62 % |
| 184 | 40 253.6 | 40 103.7 | -149.9 | 100 | +0.28 % | +0.58 % |
| 190 | 68 551.0 | 68 342.4 | -208.6 | 250 | +0.19 % | +0.60 % |
| 194 | 95 989.1 | 95 762.4 | -226.6 | 250 | +0.13 % | +0.70 % |
| 200 | 155 202.7 | 155 031.3 | -171.4 | 250 | +0.06 % | -0.29 % |
| 204 | 210 689.0 | 210 634.5 | -54.6 | 250 | +0.03 % | -0.35 % |
| 208 | 283 042.6 | 283 155.6 | +113.0 | 250 | +0.03 % | -0.46 % |
| 212 | 376 728.7 | 376 965.2 | +236.5 | 250 | +0.05 % | -0.55 % |
| 216 | 497 396.6 | 497 457.7 | +61.1 | 250 | +0.03 % | -0.55 % |
| 216.592 | 517 964.3 | 517 950.0 | -14.3 | 250 | +0.00 % | -0.54 % |

The line has the figure's shape: minimum -226.6 Pa near 194 K, maximum +236.5 Pa near 212 K, inside the 250 Pa band from
185 K to T_t, inside the 50 Pa band below 170 K, and 1.10 to 1.50 times the 100 Pa band from 180 to 185 K, as the paper's
line also leaves that band ("some calculated values deviate slightly more than the uncertainty"). Below 140 K the
correlation 3.12 is outside its data (its 50 Pa band exceeds the pressure itself); Fray-Schmitt agrees within 1.3 to 2.9 %
down to 80 K. Sublimation enthalpy at 194.67 K: 25 213.8 J/mol (+0.055 % from 25.2 kJ/mol).

Melting (Figure 11, (p_SW(3.10) - p_calc)/p_SW; uncertainty 1.5 % to 225 K, 0.5 % from 225 to 270 K):

| T, K | p_calc, MPa | p_SW, MPa | deviation | band | vs Trusler 47 |
|---|---|---|---|---|---|
| 216.6 | 0.5550 | 0.5554 | +0.07 % | 1.5 % | -0.26 % |
| 217.5 | 4.742 | 4.783 | +0.85 % | 1.5 % | -3.18 % |
| 220 | 16.618 | 16.719 | +0.61 % | 1.5 % | -2.92 % |
| 225 | 41.387 | 41.441 | +0.13 % | 1.5 % | -2.09 % |
| 230 | 67.421 | 67.299 | -0.18 % | 0.5 % | -1.39 % |
| 240 | 122.973 | 122.418 | -0.45 % | 0.5 % | -0.39 % |
| 245 | 152.387 | 151.680 | -0.47 % | 0.5 % | -0.04 % |
| 260 | 246.804 | 246.273 | -0.22 % | 0.5 % | +0.60 % |
| 270 | 314.698 | 315.008 | +0.10 % | 0.5 % | +0.79 % |

Worst 0.93 of the band, the figure's shape (+0.85 % near 217.5 K, -0.47 % near 245 K, back through zero near 265 K).
Trusler's equation 47 is a fit to the melting data with 2.5 % average deviation, not a representation of his model; it is
printed, not gated.

With 9019 J/mol on the same oracle both figures break (sublimation up to 4.4 times the band at 184 K, 936 Pa at 212 K;
melting up to 5.2 times the band, -2.6 % at 245 K). With the Span-Wagner fluid, 8875 J/mol is the consistent value, as
JS2012 fitted it and Trusler's 8870 J/mol confirms.

## 5. Pilot family and the fusion enthalpy

### 5.1 The cubic at the triple point

At (216.592 K, 517 950 Pa) the pilot's translated PR78 has three roots. Against the oracle: vaporization enthalpy
15 342.1 J/mol against 15 420.2 (-0.51 %); liquid volume 3.694084e-5 against 3.734510e-5 m3/mol (-1.08 %); mu_V - mu_L =
+6.04 J/mol (oracle -0.05), so the cubic's own saturation pressure at T_t is 0.34 % below 517 950 Pa. These set the
pilot's errors: the sublimation pressure at T_t is -0.36 % whatever the fusion enthalpy; the volume of fusion is 8.455
against 8.859 cm3/mol (-4.6 %); and the sublimation enthalpy dh_vap + dH_fus is 78 J/mol short with 8875 J/mol at T_t.

### 5.2 Measurements with both fusion enthalpies

(a) Sublimation pressure (percent; P0 target 5 % from 150 K to T_t):

| T, K | 9019: vs SW 3.12 | vs Fray-Schmitt | vs Trusler 48 | vs oracle-anchored JS2012 | T_SW(p) - T, K | 8875: vs SW | vs Fray-Schmitt |
|---|---|---|---|---|---|---|---|
| 130 | +8.65 | +3.93 | | +2.38 | 0.43 | +14.60 | +9.61 |
| 140 | +4.82 | +3.44 | | +1.97 | 0.29 | +9.51 | +8.07 |
| 150 | +2.84 | +2.94 | +2.47 | +1.61 | 0.20 | +6.56 | +6.66 |
| 160 | +1.88 | +2.40 | +1.92 | +1.28 | 0.15 | +4.81 | +5.34 |
| 170 | +1.41 | +1.82 | +1.47 | +0.98 | 0.13 | +3.67 | +4.09 |
| 180 | +1.08 | +1.30 | +1.03 | +0.68 | 0.11 | +2.75 | +2.97 |
| 190 | +0.69 | +0.99 | +0.57 | +0.39 | 0.08 | +1.84 | +2.14 |
| 194.6855 | +0.47 | +0.98 | +0.37 | +0.25 | 0.06 | +1.40 | +1.91 |
| 200 | +0.20 | -0.20 | +0.15 | +0.09 | 0.03 | +0.89 | +0.48 |
| 210 | -0.25 | -0.70 | -0.15 | -0.19 | -0.03 | +0.02 | -0.43 |
| 216 | -0.36 | -0.90 | -0.32 | -0.35 | -0.05 | -0.34 | -0.88 |
| 216.592 | -0.36 | -0.90 | -0.36 | -0.37 | | -0.36 | -0.90 |
| worst 150 K to T_t | 2.94 | | | | | 6.66 | |

(b) Melting to 10 MPa (the melting line rises 4.7 MPa/K, so the family's ceiling is 1.9 K above T_t): with 9019 J/mol
the melting temperature is within 0.125 K of Span-Wagner's 3.10 at every pressure (218.4752 K against 218.6001 K at
10 MPa); the slope (p - p_t)/(T - T_t) is 5.3 % (at T_t) to 6.6 % (10 MPa) steeper than 3.10, 6.5 % steeper than the
oracle-anchored JS2012 at T_t, which is the Clapeyron ratio (9019/8875) x (8.859/8.455) = 1.065. With 8875 J/mol:
0.094 K at 10 MPa, slope 3.6 to 4.9 %. The Span-Wagner 1.5 % pressure band is not met in pressure terms by either
(4.6 % and 6.3 % at 218.4 K); there is no P0 row for pure melting, and 0.13 K is small against the 2 K liquidus target of
P5.

(c) Sublimation enthalpy (P0 target 2 % of 25.2 kJ/mol at 195 K):

| T, K | 9019 | 8875 | oracle-anchored JS2012 (8875) |
|---|---|---|---|
| 140 | 26 352.8 | 26 208.8 | 26 412.3 |
| 170 | 25 799.1 | 25 654.9 | 25 851.0 |
| 194.67 | 25 194.7 (-0.021 %) | 25 049.9 (-0.596 %) | 25 213.8 (+0.055 %) |
| 195 | 25 185.0 (-0.060 %) | 25 040.2 (-0.634 %) | 25 203.3 (+0.013 %) |

(d) The crystal's own properties (independent of g0 and g1, the same on every family) against Trusler 2011, at the
Span-Wagner sublimation pressure:

| T, K | v JS2012, cm3/mol | v Trusler 50 | | alpha JS2012, 1/K | d ln V/dT Trusler 50 | | kappa JS2012, 1/Pa | c_p JS2012 |
|---|---|---|---|---|---|---|---|---|
| 80 | 26.2744 | 26.2151 | +0.23 % | 3.854e-4 | 3.977e-4 | -3.1 % | 1.403e-10 | 35.94 |
| 100 | 26.4927 | 26.4391 | +0.20 % | 4.423e-4 | 4.508e-4 | -1.9 % | 1.518e-10 | 39.81 |
| 120 | 26.7438 | 26.6911 | +0.20 % | 5.015e-4 | 4.979e-4 | +0.7 % | 1.668e-10 | 43.05 |
| 140 | 27.0299 | 26.9722 | +0.21 % | 5.631e-4 | 5.522e-4 | +2.0 % | 1.851e-10 | 46.18 |
| 160 | 27.3533 | 27.2908 | +0.23 % | 6.267e-4 | 6.264e-4 | +0.1 % | 2.065e-10 | 49.38 |
| 180 | 27.7162 | 27.6624 | +0.19 % | 6.918e-4 | 7.320e-4 | -5.5 % | 2.307e-10 | 52.71 |
| 194.67 | 28.0083 | 27.9814 | +0.10 % | 7.401e-4 | 8.353e-4 | -11.4 % | 2.501e-10 | 55.24 |
| 210 | 28.3373 | 28.3705 | -0.12 % | 7.909e-4 | 9.709e-4 | -18.5 % | 2.717e-10 | 57.95 |
| 216.592 | 28.4860 | 28.5589 | -0.26 % | 8.128e-4 | 1.0387e-3 | -21.8 % | 2.814e-10 | 59.13 |

c_p against the two Giauque and Egan points quoted by the P2 r1 record (secondary): 47.20 against 47.11 J/(mol K) at
146.48 K (+0.20 %), 54.39 against 54.55 at 189.78 K (-0.30 %), inside JS2012's own 0.4 % scatter against those data.
d ln V/dT along the sublimation curve is alpha - kappa dp_sub/dT; the kappa term is 1.2 % of alpha at T_t (9.9e-6 1/K),
0.3 % at 195 K and less below, so the JS2012 value comparable with Trusler's is up to 1.2 % lower still near T_t (the
table compares alpha itself; the -22 % becomes -23 %). The two reference equations
disagree near the triple point: Trusler's anharmonic term raises alpha there (his Figure 17c), JS2012 fitted alpha to
Manzhelii's data below 140 K and its molar volume is 0.26 % below Trusler's at T_t. Trusler gives kappa only as a
figure; not compared. The melting-curve volume (equation 49) is discussed in section 1.

### 5.3 Decision

9019 J/mol for the pilot anchor. It meets (a) with 2.94 % (8875: 6.66 %, fails the 5 % target) and improves (c) from
-0.63 % to -0.06 %; (b) is slightly worse (0.125 K against 0.094 K at 10 MPa), both far inside anything P5 needs. The
mechanism is a compensation, not a better fusion enthalpy: the cubic's vaporization enthalpy at T_t is 78 J/mol short,
and the larger fusion enthalpy restores the sublimation enthalpy (dh_vap + dH_fus) that sets the slope of ln p_sub. On
the reference fluid 8875 is right and 9019 breaks both of the paper's figures (section 4.2). The "measured" value is
itself uncertain: 9019 J/mol is a secondary (Air Liquide via Wikipedia) value, while Trusler cites calorimetric values of
7950, 8050 and 8340 J/mol and derives 8870 J/mol from the two reference equations. A fitted value (roughly 9100 J/mol
would move the 150 K error toward zero) was not tried: it would tune the anchor to the holdouts.

Consequence: the record's fusion enthalpy is an effective value of this fluid family. The P2 parameter rule is one
parameter set per (component, evaluator family); a second family that evaluates this crystal (a Helmholtz family, a Twu
alpha) needs its own fusion enthalpy, by its own record or a per-family anchor value (stage 2 item).

## 6. Proposed decision text (the lead numbers it D16)

**D16 (2026-09-25): solid CO2 model and its anchor on the pilot family.**

- **Question.** Which solid CO2 function and which fusion enthalpy anchor the CO2-I crystal to the pilot family?
- **Evidence.** `P4_SOLID_CO2_MODEL.md`: Jäger and Span 2012 ported and checked (Table 5 to the printed ten digits,
  derivatives 6.5e-8); anchored to the Span-Wagner oracle with 8875 J/mol it recovers the paper's g0 and g1 and
  reproduces its Figures 9 and 11. On the pilot family the translated PR78 has a 0.51 % small vaporization enthalpy and a
  1.08 % small liquid volume at the triple point; with 8875 J/mol the sublimation pressure is 6.66 % high at 150 K (P0
  target 5 %) and the sublimation enthalpy at 195 K -0.63 %; with 9019 J/mol 2.94 % and -0.06 %, the melting temperature
  within 0.125 K to 10 MPa.
- **Chosen (standing instruction; reversible at G4/G5).** The crystal is JS2012's Gibbs function as the `gibbs_function`
  kind of the crystal record (revision r2), g0 and g1 solved at runtime from the fluid family's liquid (mu_L, h_L) at the
  triple point, `TriplePointAnchor` carrying h_L; the pilot's anchor fusion enthalpy is 9019 J/mol, declared as the
  family's effective value; grade estimated_declared_error with declared errors: sublimation pressure 3 % from 150 K to
  T_t and 4 % (Fray-Schmitt) from 130 to 150 K, melting temperature 0.13 K to 10 MPa, sublimation enthalpy 0.25 % at
  195 K; crystal volume 0.3 % and expansion coefficient up to -22 % near T_t against Trusler 2011 (the two reference
  equations disagree there).
- **Rejected.** 8875 J/mol on the pilot (fails the P0 sublimation target); a fitted fusion enthalpy (tuned to holdouts);
  Trusler's Helmholtz equation as the model (T-V form, needs a volume solve per state; kept as cross-check); the
  Giauque-Egan segment record (P2 r1 lacks expansion, compressibility and Cp above 189.78 K).
- **Affected milestones.** P4 stage 2 (gas-solid equilibrium), P5 (solid-liquid), G4, G5.
- **Coverage changes.** The CO2-I crystal becomes usable; no phase competition admits it yet (PhaseContract qualifies no
  crystal set), so no answer changes.

## 7. What P4 stage 2 and P5 need from this model

- **Evaluator use.** `CrystalPhaseEvaluator.forPackage(catalog, pilot, crystal)` and one `anchor(fluid)` per
  (family, package) identity; the anchor is part of the thermodynamic identity through the fluid family and the spine
  fingerprint. Cache the anchor (and the solved constants) per identity: `evaluate` currently solves g0 and g1 on every
  call (two function evaluations per state, allocation of a `State` record); stage 2 should hold an anchored view.
- **Derivatives.** The crystal is a pure phase: d mu/dT = -s, d mu/dP = v, c_p, alpha, kappa are available from
  `properties()`; no composition derivatives. `PhaseDerivatives` for a solid state is not filled yet; the TP gas-solid
  solve needs only mu_s(T, P) against the gas fugacity, the PH/UV forms need h_s, c_p, v, alpha and kappa.
- **Domain.** 80 to 300 K and (0, 500 MPa] by the function, 90 K by the CO2 spine; the pilot's fluid_domain is 10 MPa. The
  solid's validity above T_t (metastable or compressed solid to 300 K) is JS2012's; the pilot's melting line ends at
  218.48 K at 10 MPa. Below the triple point the pure CO2 fluid in the pilot is a research contract (P3 review section 9).
- **Onset.** Frost point in N2: compare mu_s(T, P) with mu_CO2 in the vapour mixture from the cubic (fugacity, not partial
  pressure). The pure sublimation pressure is 2 to 3 % high from 150 to 180 K on this family, which moves a frost point by
  about 0.1 to 0.2 K; the G4 frost-point target is 1.5 K.
- **P5.** The melting slope is 6.5 % steep against the reference equations near T_t (volume of fusion -4.6 %); the CO2
  liquidus in CH4 runs far below T_t, where the cubic's CO2 fugacity in a methane-rich liquid, not the pure melting line,
  decides the answer. The fusion enthalpy 9019 J/mol enters the liquidus directly (ideal-solubility slope); if the P5
  holdouts (Davis 1962, Zhang 2011, Le and Trebble 2007) favour 8875, the choice is revisited there with this record's
  revision.
- **Second family.** A per-family fusion enthalpy (section 5.3) before any other family evaluates the crystal.
- **Erratum.** The Trusler 2011 erratum question stays open (equation 49); the Span-Wagner 3.12 transcription should be
  checked against the paper when it is acquired.

## 8. Tests (gates)

| Class | Tests | Checks |
|---|---|---|
| `science.material.JaegerSpanGibbsFunctionTest` (new) | 4 | Table 5 to the printed digits and v0; derivatives against central differences at 63 states (1e-6, worst printed); Table 2 relations; integration constants (round trip of the published pair, arbitrary anchors, no effect on v, c_p, alpha, kappa); refusal outside 80..300 K and (0, 500 MPa], non-finite constants |
| `science.material.CrystalReferenceTest` (changed, 4 to 5) | 5 | The CO2-I record is the JS2012 function (kind, revision, grade, anchor 9019 J/mol, range; generic accessors equal the function; `state` and `gibbs` paths agree over 80..300 K, 1 Pa..400 MPa); Gibbs-kind record rules refused (mixed kinds, type, array lengths, g10, n, range, missing published g0, anchor outside range only as research_only); the synthetic segment record unchanged, plus its `state()` and kind |
| `science.thermo.phase.CrystalPhaseEvaluatorTest` (new) | 5 | Anchor on the pilot liquid (g = mu_L, h = h_L - dH_fus, s, family id, ln phi against the spine, dg/dT = -s and dg/dP = v at 5 states); refusals (other species, other anchor state, crystal and spine ranges, basis size, package without the crystal, unusable record); the oracle anchoring recovers g0 and g1; Figure 9 (bands, extremes, Trusler 48 within 0.85 %, sublimation enthalpy 0.1 %); Figure 11 (bands, extremes) |
| `science.thermo.phase.SolidCarbonDioxideQualificationTest` (new) | 5 | Pilot (a) sublimation 3 % / 4 % / 2.5 % against the oracle-anchored crystal / 0.45 K; (b) melting 0.13 K and slope 7 % to 10 MPa; (c) sublimation enthalpy 0.25 %; (d) Trusler volume 0.3 %, expansion 3.5 % to 160 K and 25 % to T_t, c_p 0.4 %; the fusion enthalpy choice (only 9019 meets the P0 sublimation target, and has the better enthalpy) |
| `science.thermo.phase.SolidCarbonDioxideReferences` (new helper) | | Span-Wagner 3.10 (from the CoolProp file) and 3.12, their uncertainties, Trusler 47 to 50, Fray-Schmitt, the coexistence solve (Newton on ln p) |
| `science.thermo.phase.SolidPhaseEvaluatorTest` (changed) | 2 | The test double with the five-field anchor |
| `science.material.PilotCryogenicCatalogTest` (changed) | 8 | Pilot spine-fingerprint pin moved with r2; the crystal is usable |

Gradle results: section 11.

## 9. Pins

| Pin | Before | After |
|---|---|---|
| Pilot spine fingerprint (`PilotCryogenicCatalogTest.PILOT_SPINE_FINGERPRINT`) | `1b33b193853cadea354a86779e88ddaa36aa4a9d8f937e6f7d492f0e9ccd323f` | `581c9098c10bef8de71cd8fb029221ffae849c65ebc3d01a2e62ba715645df08` (CO2-I r2) |
| Pilot scientific revision | `pilot-cryogenic-p3-r1:37a33a61...7c64` | unchanged |
| Pilot physics fingerprint | `7378e199...30c0` | unchanged |
| Eight bundled package pins (16 values, two catalogs) | | unchanged (`bundledPackageFingerprintsAreUnchanged` green) |

The pilot package's advisory line `CO2_CRYSTAL_RESEARCH_ONLY` became `CO2_CRYSTAL_JAEGER_SPAN` (advisory text is not hashed).

## 10. Tool and research folders

- Tool: `tools/solid-co2-qualification/` (staged in the worktree, copied to the main checkout): `run.sh` (javac of the P4
  sources against the last Gradle compile, `probe` or `tests`), `RunTests.java`, the probe `SolidCo2QualificationProbe`
  (every table of this document), `README.md`. Nothing of it is in a tracked path.
- Research: `research/2026-09-24-coolprop-low-temperature/p4-solid-co2/` in the main checkout: the probe output, the
  printed output of the Gradle gate classes, and the rendered source pages added for this stage
  (`sources/solid-co2/pages/jaeger-p01.png`, `p05.png` to `p08.png`).

## 11. Gates

Run 2026-09-25 in the worktree from Git Bash, one Gradle invocation at a time under `build/gradle.lock` (holder `p4a`),
no dev client running, `JAVA_OPTS=-Xshare:off`, `--offline`, on the tree of `edb9f7f` (base `f0d4cf8` plus this stage;
the parallel WP2b agent's uncommitted `CHANGELOG.md` edit was present and does not enter any test). Logs in the main
checkout's `research/2026-09-24-coolprop-low-temperature/p4-solid-co2/` (`gate1-material-thermo.log`,
`gate2-full-test.log`, `gate3-fluidScienceTest.log`, and `gradle-gate-output-2026-09-25.txt` with the printed output of
the six crystal test classes from the Gradle XML reports).

| Gate | Command | Result |
|---|---|---|
| 1 | `./gradlew test --tests "com.wormzjl.createcheme.science.material.*" --tests "com.wormzjl.createcheme.science.thermo.*" --offline` | BUILD SUCCESSFUL, 43 classes, 217 tests, 0 failures, 0 errors, 0 skipped |
| 2 | `./gradlew test --offline` | BUILD SUCCESSFUL in 2 min 57 s, 253 classes, 1,241 tests, 0 failures, 0 errors, 0 skipped (1,226 at `7afa990`; +15: `JaegerSpanGibbsFunctionTest` 4, `CrystalPhaseEvaluatorTest` 5, `SolidCarbonDioxideQualificationTest` 5, `CrystalReferenceTest` 4 to 5; WP2b's `f0d4cf8` added none) |
| 3 | `./gradlew fluidScienceTest --offline` | BUILD SUCCESSFUL, 58 classes, 219 tests, 0 failures (219 before) |

Nothing in the network or the column reads a crystal (`PhaseContract` qualifies no crystal set; `crystals()` has no
consumer outside `material` and the new evaluator), so the network and column pins are unchanged: the 16 bundled pins of
`PilotCryogenicCatalogTest.bundledPackageFingerprintsAreUnchanged`, the pilot's scientific revision and physics
fingerprint, and every network and column test of the full suite (1,241 green) and of `fluidScienceTest` (219 green).
The standalone runner (`tools/solid-co2-qualification/run.sh tests`) gave the same 21 crystal-class results before the
Gradle runs.

## 12. Commits

| Commit | Content |
|---|---|
| `edb9f7f` | `JaegerSpanGibbsFunction`, `CrystalReference` (two model kinds), `SolidPhaseEvaluator.TriplePointAnchor` (liquid enthalpy), `CrystalPhaseEvaluator`; `crystals/carbon_dioxide_i.json` r2; the pilot package's advisory line; `JaegerSpanGibbsFunctionTest`, `CrystalPhaseEvaluatorTest`, `SolidCarbonDioxideQualificationTest`, `SolidCarbonDioxideReferences` (new), `CrystalReferenceTest`, `SolidPhaseEvaluatorTest`, `PilotCryogenicCatalogTest` (changed) |
| `bab0cdc` | Comments only in `SolidCarbonDioxideReferences` (two measured numbers corrected: 3.1e-6 at 194.6855 K, the kappa dp_sub/dT term); made after the Gradle gates, recompiled and the 21 crystal-class tests re-run with the standalone runner (21 passed) |

Commit attribution: the session's model line (Claude Opus 5.5), as AGENTS.md asks ("the attribution line given for the
session"), not the brief's Fable 5.1 line, as in P3 WP11. `CHANGELOG.md`, `DECISION_LOG.md`, the unified plan and the
INDEX files are not edited here; the proposed `[Unreleased]` line and INDEX rows are in the stage report to the lead.

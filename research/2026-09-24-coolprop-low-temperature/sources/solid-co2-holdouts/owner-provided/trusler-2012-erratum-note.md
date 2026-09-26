# Trusler 2012 erratum: what it corrects

Source: J. P. M. Trusler, "Erratum: Equation of State for Solid Phase I of Carbon Dioxide Valid for Temperatures up to 800 K and Pressures up to 12 GPa [J. Phys. Chem. Ref. Data 40, 043105 (2011)]", J. Phys. Chem. Ref. Data 41, 039901 (2012), doi:10.1063/1.4745598. Received 29 July 2012, published online 18 September 2012. One page. Local copy `trusler-2012-jpcrd-41-039901-erratum.pdf` (SHA-256 `b726a52eb37adf36f71a30815ca678a3fba8e0be795a226a16e2ba4c6db81f7d`), AIP copyright, owner-provided. Read from the text layer and a 200 dpi render, 2026-09-25. Digits as printed, one sanity check, not re-verified (owner: not looking for 100 % correct data).

The erratum states that three corrections are required (Andreas Jäger found two of them):

1. **Equation (38)** lacked a factor θ_D,0. Corrected form: A_A = b1 R θ_D,0 [(T/θ_D,0)^4 / {1 + b2 (T/θ_D,0)^2}] exp[b3 {(V_m − V00)/V00}]. This is the anharmonic term of the Helmholtz energy, so it changes the equation of state itself, not only an auxiliary fit.
2. **Equation (49)**, the molar volume on the melting curve: the parameters in the paragraph after it were printed with the wrong signs. Corrected: **d7 = −0.160433, d8 = 0.018643, d9 = −0.001582** (the original printed +0.160433, −0.018643 and +0.001582). V_m,t = 28.5589 cm3/mol is not changed. **Equation 49 is therefore among the corrections.**
3. **Table 6**: b1 was printed as 1.15; the correct value is **1.151**. The symbols in the table also had typographical errors, and the erratum reprints the whole table:

| Parameter | Value | Parameter | Value |
|---|---|---|---|
| V00 / (cm3 mol−1) | 25.800 | b1 | 1.151 |
| c1 / MPa | 9000 | b2 | 55.428 |
| c2 / MPa | 32315 | b3 | 8.8 |
| c3 / MPa | 941 | θ_D,0 / K | 148.9 |
| θ_1,0 / K | 92.3 | a1 | 0.5 |
| θ_2,0 / K | 179.4 | a2 | 1.5 |
| θ_3,0 / K | 1945.9 | a3 | 1 |
| θ_4,0 / K | 942.7 | a4 | 2 |
| θ_5,0 / K | 3379.2 | a5 | 1 |
| γ_D,0 | 2.6163 | q_D | −0.21 |
| γ_1,0 | 4.0463 | q1 | −0.21 |
| γ_2,0 | 1.1259 | q2 | −0.21 |
| γ_3,0 | 0.0633 | q3 | 0 |
| γ_4,0 | −0.0521 | q4 | 0 |
| γ_5,0 | 0.0515 | q5 | 0 |

Equations (47), (48) and (50) are **not** corrected, so the transcriptions of (47) and (48) in `../trusler-2011-co2-auxiliary-equations-and-table2.tsv` and the use of (47), (48) and (50) in `P4_SOLID_CO2_MODEL.md` stand.

## Consequence for P4 stage 1

`P4_SOLID_CO2_MODEL.md` section 1 found that equation 49 as printed makes V rise from 28.56 to 37.9 cm3/mol at 800 K. It read the equation as a reciprocal, V = V_m,t / [1 + ...], instead. The erratum's explanation is a sign error, not a reciprocal. The direct form with the corrected signs and the P4 reciprocal reading agree near the triple point and diverge at high temperature (this note's check, V_m,t = 28.5589 cm3/mol, T_t = 216.592 K):

| T / K | Corrected eq. 49 (cm3/mol) | P4 reciprocal reading (cm3/mol) | Difference |
|---|---|---|---|
| 230 | 28.277 | 28.280 | −0.01 % |
| 250 | 27.865 | 27.881 | −0.06 % |
| 270 | 27.461 | 27.501 | −0.15 % |
| 400 | 25.033 | 25.421 | −1.5 % |
| 800 | 19.197 | 21.509 | −10.7 % |

From T_t to 270 K, the range where P4 compared it with Jäger-Span within 0.2 %, the difference is at most 0.15 %. The P4 conclusion holds there. Above 300 K the corrected equation 49 is the one to use. Sanity check: with the corrected signs, V falls monotonically along the melting curve, as compression requires.

Also note: the local `../../solid-co2/trusler-2011-jpcrd-40-043105.pdf` has b1 = 1.15 in Table 6 and lacks θ_D,0 in equation (38). A Trusler solid EOS built from it would be wrong in the anharmonic term.

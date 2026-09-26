# P1 items 2 and 4: alpha function and direct liquid evaluation

Status: Concluded (research only), 2026-09-24. Batch `2026-09-24-coolprop-low-temperature`, plan [UNIFIED_MULTIPHASE_THERMO_PLAN.md](UNIFIED_MULTIPHASE_THERMO_PLAN.md) section 5, P1 items 2 and 4; decisions D1 and D3 of [DECISION_LOG.md](DECISION_LOG.md). No product source, data, test or dependency changed; no Gradle run.

## 1. Purpose and method

- **Item 4 (review F1).** Translated PR78 (Soave alpha, mod formulation) evaluated directly at the state (T, P), with no 2 MPa reference path and no global compressibility, against CoolProp 8.0.0 HEOS. Fluids N2, CH4, C2H6, CO2, H2. Isobars 0.1, 0.5, 1, 2, 5, 10 MPa, 24 temperatures from the triple point to 1.2 Tc plus Tsat(P) ± 0.5 K where subcritical; the probe's saturation states (saturated liquid and vapour at Psat_ref; H2 states added); the probe's dense and supercritical states. 833 states, 38 in the critical band, 24 isobar points refused by CoolProp as below the melting line (listed in `item4-output.txt`).
- **Root choice.** CoolProp phase `liquid` or `supercritical_liquid` -> liquid root (liquid-like); `gas` or `supercritical_gas` -> vapour root (vapour-like); `supercritical` (T > Tc and P > Pc) -> lowest-Gibbs root. No state had its only real root on the other branch (0 mismatches for both anchors).
- **Properties.** Density; cp (CoolProp cp0 + PR residual cp); h as h - h_ig(298.15 K, 0.1 MPa) in both models, so the ideal-gas parts and references cancel and the deviation is h_res,PR + P c - h_res,ref, reported in J/mol (a percentage of a quantity that crosses zero near 298 K is not meaningful); ln phi, reported as the absolute difference (x100 is about the percent error on phi). The constant translation adds P c to h and P c/(RT) to ln phi and nothing to cp, as `TranslatedPengRobinson` does.
- **Anchors.** (a) current calibration: v_ref(T_a, 2 MPa) at N2 90 K, CH4 150 K, C2H6 240 K, CO2 250 K; H2 untranslated. (b) D1: saturated-liquid volume at Tr = 0.8 from CoolProp, matched by the translated PR evaluated at (0.8 Tc, Psat_ref).
- **Critical band.** Tr 0.95 to 1.1 with Pr 0.8 to 1.5, flagged and excluded from every mean.
- **Item 2 (D3).** Twu 1991 alpha, alpha = Tr^(N(M-1)) exp[L(1 - Tr^(NM))], in the same PR78 (a_c, b unchanged), translation re-anchored per alpha by rule (b). Saturation properties at the model's own Psat (as the probe). Consistency (Le Guennec et al. 2016): alpha > 0, alpha' < 0, alpha'' > 0, alpha''' < 0 on a 20,001-point log grid Tr 0.2 to 20 (0.2 to 25 for H2, whose process states reach Tr 21).
- **Baseline correction.** The probe's cubic roots lose about 1e-3 relative accuracy on the liquid root at pressures of a few pascal; its finite-difference cpL at ethane 90.4 K (-8.8 %) is -10.6 % with Newton-polished roots. All other probe values reproduce.

## 2. Item 4: direct evaluation, anchors (a) and (b)

### 2.1 Anchors

| Fluid | Tc [K] | Pc [MPa] | omega | T triple [K] | c (a) [cm3/mol] | c (b) [cm3/mol] | 0.8 Tc [K] |
|---|---|---|---|---|---|---|---|
| N2 | 126.192 | 3.3958 | 0.0372 | 63.151 | 4.063 | 3.519 | 100.95 |
| CH4 | 190.564 | 4.5992 | 0.0114 | 90.694 | 3.644 | 3.389 | 152.45 |
| C2H6 | 305.322 | 4.8722 | 0.0990 | 90.368 | 3.834 | 3.454 | 244.26 |
| CO2 | 304.128 | 7.3773 | 0.2239 | 216.592 | 0.946 | 1.170 | 243.30 |
| H2 | 33.144 | 1.2964 | -0.2190 | 13.957 | 0 | 4.298 | 26.52 |

### 2.2 Per fluid and phase (isobars and saturation states; band excluded)

Density and cp in %, mean |dev| / worst signed; h in J/mol (anchor b; anchor a differs by P times the change in c, at most 6 J/mol, H2 43 J/mol); nB = band points excluded.

| Fluid | Phase | n | nB | rho (a) | rho (b) | cp | h (b) mean / worst | ln phi (a) / (b) |
|---|---|---|---|---|---|---|---|---|
| N2 | liquid-like | 74 | 2 | 0.79 / -9.02 | 1.41 / -8.17 | 5.28 / +29.4 | 60 / 221 | 0.0115 / 0.0113 |
| N2 | vapour-like | 69 | 0 | 0.63 / +2.09 | 0.67 / +2.24 | 3.72 / -23.8 | 15 / 68 | 0.0064 / 0.0068 |
| N2 | supercritical | 11 | 3 | 2.86 / -4.83 | 2.36 / -4.05 | 4.04 / -10.6 | 56 / 78 | 0.0190 / 0.0228 |
| CH4 | liquid-like | 72 | 2 | 1.40 / -5.32 | 1.87 / -4.90 | 3.04 / +16.7 | 42 / 228 | 0.0067 / 0.0064 |
| CH4 | vapour-like | 71 | 1 | 0.48 / +1.56 | 0.49 / +1.60 | 3.11 / -19.0 | 20 / 104 | 0.0049 / 0.0050 |
| CH4 | supercritical | 11 | 3 | 4.05 / -6.29 | 3.88 / -5.98 | 5.09 / -10.1 | 126 / 209 | 0.0237 / 0.0249 |
| C2H6 | liquid-like | 87 | 2 | 1.13 / -6.18 | 1.32 / -5.76 | 5.45 / +18.0 | 201 / 557 | 0.0406 / 0.0402 |
| C2H6 | vapour-like | 60 | 1 | 0.42 / +1.43 | 0.43 / +1.46 | 2.75 / -18.5 | 37 / 197 | 0.0043 / 0.0044 |
| C2H6 | supercritical | 9 | 3 | 4.21 / -7.36 | 4.04 / -7.04 | 4.46 / -9.3 | 191 / 359 | 0.0210 / 0.0222 |
| CO2 | liquid-like | 36 | 3 | 1.85 / -8.92 | 1.70 / -9.26 | 6.65 / +29.3 | 115 / 499 | 0.0077 / 0.0072 |
| CO2 | vapour-like | 105 | 1 | 0.49 / +2.18 | 0.48 / +2.12 | 4.10 / -26.7 | 48 / 263 | 0.0050 / 0.0049 |
| CO2 | supercritical | 5 | 5 | 0.93 / +1.02 | 0.81 / +0.91 | 3.58 / -5.7 | 171 / -203 | 0.0239 / 0.0232 |
| H2 | liquid-like | 97 | 0 | 21.87 / +31.86 | 3.73 / +8.40 | 48.8 / +82.9 | 47 / -122 | 0.1161 / 0.0454 |
| H2 | vapour-like | 45 | 0 | 1.84 / +5.42 | 1.02 / +3.15 | 5.09 / -25.5 | 5 / 29 | 0.0158 / 0.0097 |
| H2 | supercritical | 18 | 0 | 10.08 / +15.16 | 3.17 / -6.43 | 10.2 / -22.0 | 44 / 72 | 0.1054 / 0.0259 |

Sub-ranges (anchor b): liquid-like at Tr <= 0.9, mean / worst density: N2 1.31 / -3.34, CH4 1.84 / -3.84, C2H6 1.24 / -2.63, CO2 0.94 / -3.40, H2 3.79 / +8.40; liquid-like at Tr > 0.9 outside the band: N2 2.63 / -8.17, CH4 2.31 / -4.90, C2H6 3.08 / -5.76, CO2 4.42 / -9.26, H2 3.29 / -7.82, with cp to +29 % (H2 +37 %). Liquid cp to Tr 0.85: mean 4.5 % (N2), 1.8 % (CH4), 5.1 % (C2H6), 4.6 % (CO2), 55 % (H2).

### 2.3 Per isobar (isobar grid only; band excluded)

| Fluid | P [MPa] | n (nB) | rho (a) mean | rho (b) mean / worst | cp mean / worst | h (b) mean | ln phi (b) |
|---|---|---|---|---|---|---|---|
| N2 | 0.1 | 25 | 0.13 | 0.30 / +1.51 | 1.86 / -7.6 | 16 | 0.0044 |
| N2 | 0.5 | 24 | 0.36 | 0.75 / +1.57 | 3.08 / -9.0 | 27 | 0.0074 |
| N2 | 1 | 25 | 0.71 | 1.01 / +1.59 | 4.29 / -12.9 | 35 | 0.0096 |
| N2 | 2 | 25 | 1.48 | 1.73 / -4.66 | 6.30 / +18.5 | 50 | 0.0135 |
| N2 | 5 | 18 (5) | 0.75 | 1.25 / -1.85 | 4.88 / -10.6 | 49 | 0.0138 |
| N2 | 10 | 23 | 1.54 | 1.81 / -4.05 | 3.66 / -8.5 | 49 | 0.0120 |
| CH4 | 0.1 | 25 | 0.31 | 0.42 / +2.26 | 0.82 / -3.9 | 8 | 0.0026 |
| CH4 | 0.5 | 25 | 0.66 | 0.88 / +2.27 | 1.94 / -8.3 | 17 | 0.0050 |
| CH4 | 1 | 25 | 0.81 | 1.08 / +2.27 | 3.03 / -11.6 | 26 | 0.0069 |
| CH4 | 2 | 25 | 1.34 | 1.55 / -2.49 | 4.65 / -16.5 | 43 | 0.0095 |
| CH4 | 5 | 19 (4) | 1.83 | 2.13 / -4.90 | 3.89 / +14.6 | 60 | 0.0099 |
| CH4 | 10 | 23 | 2.38 | 2.57 / -5.98 | 3.75 / -10.1 | 75 | 0.0095 |
| C2H6 | 0.1 | 25 | 0.35 | 0.41 / +1.86 | 2.54 / -8.6 | 94 | 0.0206 |
| C2H6 | 0.5 | 25 | 0.58 | 0.72 / +1.90 | 3.43 / -8.6 | 110 | 0.0220 |
| C2H6 | 1 | 25 | 0.70 | 0.86 / +1.92 | 3.96 / -10.4 | 120 | 0.0232 |
| C2H6 | 2 | 25 | 1.12 | 1.23 / -2.63 | 5.09 / -14.3 | 141 | 0.0255 |
| C2H6 | 5 | 19 (4) | 1.39 | 1.54 / -3.78 | 4.79 / +10.9 | 178 | 0.0311 |
| C2H6 | 10 | 23 | 2.22 | 2.30 / -7.04 | 5.09 / -9.3 | 180 | 0.0283 |
| CO2 | 0.1 | 23 | 0.04 | 0.04 / -0.05 | 0.42 / -1.5 | 4 | 0.0004 |
| CO2 | 0.5 | 23 | 0.19 | 0.19 / -0.38 | 2.15 / -8.2 | 23 | 0.0018 |
| CO2 | 1 | 25 | 0.50 | 0.42 / +0.99 | 4.11 / -13.3 | 54 | 0.0034 |
| CO2 | 2 | 25 | 0.75 | 0.68 / +1.06 | 5.18 / -17.9 | 72 | 0.0066 |
| CO2 | 5 | 24 | 1.98 | 1.94 / -7.93 | 7.37 / +24.9 | 116 | 0.0131 |
| CO2 | 10 | 16 (7) | 1.36 | 1.20 / -3.80 | 4.65 / +9.8 | 91 | 0.0124 |
| H2 | 0.1 | 25 | 8.25 | 2.08 / +8.37 | 19.1 / +79.5 | 19 | 0.0180 |
| H2 | 0.5 | 25 | 13.52 | 2.91 / +8.14 | 29.9 / +79.7 | 24 | 0.0287 |
| H2 | 1 | 26 | 14.93 | 3.89 / +7.86 | 34.1 / +79.9 | 34 | 0.0322 |
| H2 | 2 | 24 | 16.98 | 3.97 / +7.34 | 36.1 / +80.4 | 43 | 0.0382 |
| H2 | 5 | 24 | 18.74 | 2.77 / +5.91 | 35.7 / +81.5 | 46 | 0.0400 |
| H2 | 10 | 24 | 19.59 | 1.73 / +3.92 | 39.2 / +82.9 | 45 | 0.0462 |

The 10 MPa worst densities are dense supercritical states just above the band's Pr limit (N2 147.6 K, Tr 1.17, Pr 2.9: -4.0 %; CH4 210.7 K, Tr 1.11, Pr 2.2: -6.0 %; C2H6 330.4 K, Tr 1.08, Pr 2.1: -7.0 %).

### 2.4 Saturated liquid at the probe states (at T, Psat_ref)

The network-path column of the probe is gone; the direct evaluation replaces it. Band = Tr 0.95 to 1.1 with Pr 0.8 to 1.5. For a liquid, the ln phi deviation equals the model's relative Psat error (ethane 90.4 K: 0.252, against Psat +28.7 %).

| Fluid | T [K] | Tr | Pr | band | rho (a) / (b) % | cpL % | h (b) J/mol | ln phi (b) |
|---|---|---|---|---|---|---|---|---|
| N2 | 63.15 | 0.500 | 0.004 |  | -0.88 / +0.80 | -8.24 | 116 | 0.0448 |
| N2 | 70.00 | 0.555 | 0.011 |  | -0.40 / +1.24 | -6.95 | 87 | 0.0259 |
| N2 | 77.36 | 0.613 | 0.030 |  | -0.07 / +1.52 | -5.08 | 62 | 0.0137 |
| N2 | 90.00 | 0.713 | 0.106 |  | -0.16 / +1.31 | -0.62 | 39 | 0.0030 |
| N2 | 100.00 | 0.792 | 0.229 |  | -1.16 / +0.17 | +4.48 | 49 | -0.0027 |
| N2 | 110.00 | 0.872 | 0.432 |  | -3.58 / -2.44 | +12.41 | 96 | -0.0097 |
| N2 | 120.00 | 0.951 | 0.739 |  | -9.02 / -8.17 | +29.36 | 221 | -0.0201 |
| CH4 | 90.70 | 0.476 | 0.003 |  | +1.05 / +1.79 | -1.25 | 26 | 0.0155 |
| CH4 | 100.00 | 0.525 | 0.007 |  | +1.36 / +2.08 | -0.93 | 21 | 0.0126 |
| CH4 | 111.67 | 0.586 | 0.022 |  | +1.56 / +2.26 | -0.24 | 17 | 0.0102 |
| CH4 | 130.00 | 0.682 | 0.080 |  | +1.30 / +1.95 | +1.99 | 24 | 0.0072 |
| CH4 | 150.00 | 0.787 | 0.226 |  | -0.23 / +0.33 | +6.64 | 71 | 0.0018 |
| CH4 | 170.00 | 0.892 | 0.506 |  | -4.30 / -3.84 | +16.68 | 202 | -0.0091 |
| CH4 | 185.00 | 0.971 | 0.840 | yes | -11.66 / -11.35 | +39.06 | 460 | -0.0219 |
| C2H6 | 90.40 | 0.296 | 0.000 |  | -2.71 / -1.93 | -10.62 | 557 | 0.2523 |
| C2H6 | 120.00 | 0.393 | 0.000 |  | -0.99 / -0.22 | -7.73 | 381 | 0.0972 |
| C2H6 | 150.00 | 0.491 | 0.002 |  | +0.33 / +1.08 | -6.51 | 232 | 0.0349 |
| C2H6 | 184.55 | 0.604 | 0.021 |  | +1.15 / +1.86 | -3.84 | 101 | 0.0099 |
| C2H6 | 220.00 | 0.721 | 0.101 |  | +0.81 / +1.45 | +0.38 | 51 | 0.0025 |
| C2H6 | 250.00 | 0.819 | 0.267 |  | -1.11 / -0.56 | +6.03 | 117 | -0.0023 |
| C2H6 | 280.00 | 0.917 | 0.576 |  | -6.18 / -5.76 | +17.99 | 385 | -0.0122 |
| C2H6 | 295.00 | 0.966 | 0.804 | yes | -11.57 / -11.24 | +34.25 | 711 | -0.0201 |
| CO2 | 216.60 | 0.712 | 0.070 |  | +1.71 / +1.09 | -8.92 | 204 | 0.0014 |
| CO2 | 230.00 | 0.756 | 0.121 |  | +1.34 / +0.75 | -5.21 | 120 | -0.0039 |
| CO2 | 250.00 | 0.822 | 0.242 |  | -0.04 / -0.57 | +1.40 | 77 | -0.0077 |
| CO2 | 270.00 | 0.888 | 0.434 |  | -2.95 / -3.40 | +10.98 | 165 | -0.0109 |
| CO2 | 290.00 | 0.954 | 0.721 |  | -8.92 / -9.26 | +29.32 | 499 | -0.0170 |
| CO2 | 300.00 | 0.986 | 0.910 | yes | -14.85 / -15.10 | +49.09 | 911 | -0.0217 |
| H2 | 14.00 | 0.422 | 0.006 |  | +31.86 / +8.40 | +79.28 | -98 | -0.1674 |
| H2 | 17.00 | 0.513 | 0.024 |  | +28.90 / +7.03 | +64.67 | -66 | -0.0421 |
| H2 | 20.37 | 0.615 | 0.078 |  | +25.05 / +5.18 | +50.73 | -31 | 0.0148 |
| H2 | 24.00 | 0.724 | 0.199 |  | +19.90 / +2.54 | +39.96 | 4 | 0.0269 |
| H2 | 28.00 | 0.845 | 0.442 |  | +11.93 / -1.95 | +33.82 | 43 | 0.0109 |
| H2 | 31.00 | 0.935 | 0.726 |  | +2.54 / -7.82 | +37.17 | 79 | -0.0111 |

### 2.5 Probe dense and supercritical states (direct evaluation)

| Fluid | T [K] | P [MPa] | Reference phase | band | rho (a) / (b) % | cp % | h (b) J/mol | ln phi (b) |
|---|---|---|---|---|---|---|---|---|
| N2 | 77.36 | 5 | liquid-like |  | -0.08 / +1.53 | -6.07 | 64 | 0.0098 |
| N2 | 77.36 | 10 | liquid-like |  | -0.17 / +1.46 | -6.79 | 69 | 0.0059 |
| N2 | 100 | 10 | liquid-like |  | +0.34 / +1.79 | -1.53 | 15 | -0.0077 |
| N2 | 126.2 | 3.4 | supercritical | yes | -14.90 / -14.39 | +89.2 | 392 | -0.0279 |
| N2 | 130 | 4 | supercritical | yes | -4.57 / -4.03 | -27.6 | 133 | -0.0301 |
| N2 | 130 | 6 | supercritical |  | -6.46 / -5.60 | +7.05 | 139 | -0.0203 |
| N2 | 130 | 10 | supercritical |  | -2.62 / -1.55 | +4.14 | 52 | -0.0138 |
| N2 | 150 | 10 | supercritical |  | -4.80 / -4.05 | -3.75 | 68 | -0.0224 |
| N2 | 300 | 10 | supercritical |  | +0.02 / +0.24 | +1.00 | -51 | -0.0061 |
| CH4 | 110 | 10 | liquid-like |  | +1.43 / +2.14 | -1.39 | 18 | 0.0018 |
| CH4 | 150 | 10 | liquid-like |  | +1.15 / +1.76 | +2.10 | 20 | -0.0019 |
| CH4 | 190.6 | 4.6 | supercritical | yes | -2.67 / -2.47 | -26.7 | 263 | -0.0271 |
| CH4 | 200 | 6 | supercritical | yes | -5.43 / -5.20 | -22.9 | 225 | -0.0304 |
| CH4 | 200 | 10 | supercritical |  | -5.07 / -4.69 | +4.95 | 194 | -0.0181 |
| CH4 | 250 | 10 | supercritical |  | -0.06 / +0.11 | -4.56 | -70 | -0.0301 |
| CH4 | 300 | 10 | supercritical |  | +0.83 / +0.95 | -0.17 | -121 | -0.0217 |
| C2H6 | 200 | 10 | liquid-like |  | +1.50 / +2.20 | -3.63 | 62 | -0.0011 |
| C2H6 | 300 | 10 | liquid-like |  | -3.37 / -2.91 | +6.25 | 192 | -0.0098 |
| C2H6 | 305.4 | 4.9 | supercritical | yes | -16.19 / -15.96 | +10.8 | 1045 | -0.0259 |
| C2H6 | 320 | 6 | supercritical | yes | -0.51 / -0.31 | -13.9 | 119 | -0.0295 |
| C2H6 | 320 | 10 | supercritical |  | -6.29 / -5.92 | +4.59 | 326 | -0.0163 |
| C2H6 | 400 | 10 | supercritical |  | -0.15 / +0.01 | -2.04 | -158 | -0.0250 |
| CO2 | 280 | 8 | liquid-like |  | -2.80 / -3.24 | +9.22 | 118 | -0.0097 |
| CO2 | 280 | 10 | liquid-like |  | -1.93 / -2.38 | +6.80 | 64 | -0.0086 |
| CO2 | 300 | 8 | liquid-like | yes | -9.75 / -10.06 | +22.6 | 518 | -0.0174 |
| CO2 | 304.5 | 7.4 | supercritical | yes | -1.18 / -1.36 | -19.9 | 332 | -0.0239 |
| CO2 | 310 | 8 | supercritical | yes | +0.34 / +0.17 | -12.8 | 142 | -0.0253 |
| CO2 | 310 | 10 | supercritical | yes | -10.58 / -10.86 | +11.5 | 537 | -0.0187 |
| CO2 | 320 | 8 | supercritical | yes | +2.11 / +1.99 | -7.07 | -52 | -0.0255 |
| CO2 | 320 | 10 | supercritical | yes | -6.40 / -6.60 | -20.8 | 312 | -0.0250 |
| CO2 | 350 | 10 | supercritical |  | +0.99 / +0.87 | -3.61 | -171 | -0.0236 |
| CO2 | 400 | 10 | supercritical |  | +0.67 / +0.58 | +0.01 | -223 | -0.0147 |
| H2 | 300 | 10 | supercritical |  | +2.41 / +0.73 | +0.22 | -61 | -0.0106 |
| H2 | 623 | 6 | supercritical |  | +0.46 / -0.03 | +0.13 | -26 | 0.0001 |
| H2 | 650 | 9 | supercritical |  | +0.61 / -0.09 | +0.18 | -35 | 0.0006 |
| H2 | 663 | 9.1 | supercritical |  | +0.59 / -0.10 | +0.18 | -35 | 0.0007 |
| H2 | 700 | 10 | supercritical |  | +0.57 / -0.15 | +0.18 | -36 | 0.0012 |

The probe's network-path failures (N2 120 K -22.1 %, CH4 185 K -89.7 %, C2H6 300 K / 10 MPa -92.3 %, CO2 290 K -94.8 %) become -8.2, -11.4 (band), -2.9 and -9.3 % under direct evaluation with anchor (b); what remains is the cubic's own near-critical error.

### 2.6 Anchor scan (liquid-like density, band excluded, mean |dev| %)

Constant translation anchored on the CoolProp saturated liquid at the stated Tr (the same rule as (b) at another temperature); the anchor moves only density (and h, ln phi by P c).

| Fluid | Anchor | c [cm3/mol] | Tr < 0.8 | Tr >= 0.8 | all liquid | worst liquid | vapour + supercritical |
|---|---|---|---|---|---|---|---|
| N2 | current (a) | 4.063 | 0.29 | 2.06 | 0.79 | -9.02 | 0.94 |
| N2 | Tr 0.7 | 4.032 | 0.27 | 2.00 | 0.76 | -8.97 | 0.93 |
| N2 | Tr 0.8 (b) | 3.519 | 1.35 | 1.55 | 1.41 | -8.17 | 0.91 |
| CH4 | current (a) | 3.644 | 1.27 | 1.91 | 1.40 | -5.32 | 0.96 |
| CH4 | Tr 0.7 | 4.114 | 0.28 | 2.68 | 0.78 | -6.08 | 0.98 |
| CH4 | Tr 0.8 (b) | 3.389 | 1.93 | 1.66 | 1.87 | -4.90 | 0.94 |
| C2H6 | current (a) | 3.834 | 0.99 | 1.85 | 1.13 | -6.18 | 0.91 |
| C2H6 | Tr 0.75 | 4.107 | 0.93 | 2.08 | 1.12 | -6.49 | 0.92 |
| C2H6 | Tr 0.8 (b) | 3.454 | 1.26 | 1.62 | 1.32 | -5.76 | 0.90 |
| CO2 | current (a) | 0.946 | 1.52 | 2.15 | 1.85 | -8.92 | 0.51 |
| CO2 | Tr 0.75 | 1.485 | 0.27 | 2.93 | 1.68 | -9.73 | 0.47 |
| CO2 | Tr 0.8 (b) | 1.170 | 0.93 | 2.39 | 1.70 | -9.26 | 0.50 |
| H2 | current (a), none | 0 | 25.05 | 13.19 | 21.87 | +31.86 | 4.20 |
| H2 | Tr 0.7 | 5.229 | 1.84 | 4.63 | 2.59 | -9.79 | 2.06 |
| H2 | Tr 0.8 (b) | 4.298 | 4.36 | 2.00 | 3.73 | +8.40 | 1.63 |

Tr 0.6, 0.65 and 0.85 are in `item4-output.txt`; Tr 0.7 is below the CO2 triple point.

### 2.7 Critical band and the near-critical states outside it

| Fluid | Band points | rho (a) / (b) mean | worst (b) | cp mean / worst | Worst liquid-like state outside the band (b) |
|---|---|---|---|---|---|
| N2 | 7 | 7.26 / 6.54 | -14.39 | 24.1 / +89.2 | 120 K sat., Tr 0.951, Pr 0.739: rho -8.17, cp +29.4 |
| CH4 | 8 | 5.80 / 5.69 | -11.77 | 20.0 / +39.1 | 180.7 K / 5 MPa, Tr 0.948, Pr 1.09: rho -4.90, cp +14.6 |
| C2H6 | 8 | 6.99 / 6.87 | -15.96 | 17.2 / +34.3 | 280 K sat., Tr 0.917, Pr 0.576: rho -5.76, cp +18.0 |
| CO2 | 15 | 5.76 / 5.93 | -15.10 | 17.4 / +49.1 | 290 K sat., Tr 0.954, Pr 0.721: rho -9.26, cp +29.3 |
| H2 | 0 | - | - | - | 31 K sat., Tr 0.935, Pr 0.726: rho -7.82, cp +37.2 |

The band as defined (Pr 0.8 to 1.5) misses the saturated and compressed liquid at Tr 0.9 to 0.95 with Pr 0.58 to 0.74 (density to -9.3 %, cp to +30 %) and the dense supercritical fluid at Tr 1.05 to 1.2 with Pr 2 to 3 (density to -7.0 %). No H2 state falls in the band (the 1 and 2 MPa isobars are at Pr 0.77 and 1.54).

### 2.8 Conclusion on item 4 and the anchor

- Direct evaluation removes F1: every state has a root on the branch CoolProp names, and outside the band the density is within 2.6 % mean on every isobar to 10 MPa for N2, CH4, C2H6 and CO2 (worst -7.0 %, dense supercritical ethane at Tr 1.08, Pr 2.1). The -20 to -95 % network-path rows are gone. Vapour-like density is within 2.2 % everywhere (H2 3.2 % with anchor b); ln phi is within 0.05 except ethane liquid below 150 K (to 0.25, the Soave Psat error of item 2) and liquid H2 near its triple point (to 0.17).
- Anchor (a) is better on the cold liquid (Tr < 0.8): N2 0.29 against 1.35 %, CH4 1.27 against 1.93 %, C2H6 0.99 against 1.26 %. Anchor (b) is better on the liquid at Tr >= 0.8 for N2, CH4 and C2H6 (by 0.2 to 0.5 points, with a 0.4 to 0.9 points smaller worst case). CO2 is the reverse, because its current anchor (250 K) already sits at Tr 0.82: (b) is better below Tr 0.8 (0.93 against 1.52 %), (a) above (2.15 against 2.39 %). Anchor (b) is decisively better on H2 (liquid 21.9 to 3.7 %; 300 K / 10 MPa +2.41 to +0.73 %; the 623 to 700 K / 6 to 10 MPa states +0.46 to +0.61 % to -0.03 to -0.15 %). Vapour-like and supercritical states differ by at most 0.5 points between anchors except H2; cp does not depend on the anchor; h differs by P times the change in c, at most 6 J/mol at 10 MPa (H2 43 J/mol).
- Mixed anchor: keeping the current points for N2, CH4 and C2H6 and using Tr 0.8 for CO2 and H2 would gain 0.2 to 0.6 points of all-liquid mean density (N2 1.41 to 0.79 %, CH4 1.87 to 1.40 %, C2H6 1.32 to 1.13 %) and lose 0.4 to 0.9 points on the worst case near Tr 0.9. The best single anchor over the whole liquid range is Tr 0.7 for N2, CH4 and H2 (0.76, 0.78, 2.59 %) and Tr 0.75 for C2H6 and CO2 (1.12, 1.68 %). The single D1 rule (Tr = 0.8) costs 0.3 to 1.1 points of cold-liquid mean against the current points (N2 0.29 to 1.35 %), keeps every liquid state below Tr 0.9 within 3.8 % (H2 8.4 %), gives the smallest worst case, is the tc-PR convention (so it carries unchanged to a Twu alpha, section 3) and is the only rule that gives hydrogen a translation. No anchor changes the picture that the liquid is within about 2 % mean below Tr 0.9 (H2 about 4 %).
- For the plan's declared error: the band should either extend down the saturation line to Tr 0.9 (liquid density to -9.3 %, cp to +30 %) and up in pressure at Tr 1.0 to 1.2 (density to -7 % at Pr 2 to 3), or those regions should carry their own declared error.

## 3. Item 2: Soave versus Twu alpha

### 3.1 Parameters, provenance and consistency

| Fluid | Route used | L | M | N | Twu consistent (Tr 0.2 to 20) | Soave m | Soave consistent (first violation) |
|---|---|---|---|---|---|---|---|
| N2 | 1: tc-PR table, secondary copy | 0.124272851 | 0.889814700 | 2.012851287 | yes | 0.4316 | no: alpha' >= 0 from Tr 11.0 (1388 K) |
| CH4 | 1: tc-PR table, secondary copy | 0.147384887 | 0.907477449 | 1.824108902 | yes | 0.3922 | no: from Tr 12.6 (2401 K) |
| C2H6 | 1: tc-PR table, secondary copy | 0.305327516 | 0.869266115 | 1.329661697 | yes | 0.5247 | no: from Tr 8.45 (2579 K) |
| CO2 | 1: tc-PR table, secondary copy | 0.178351313 | 0.859029469 | 2.410736483 | yes | 0.7065 | no: from Tr 5.84 (1775 K) |
| H2 | 3: own fit, degenerate (not literature) | 0.8667 | 0.8667 | 3.4e-14 | numerically yes (alpha = 1 to 1e-13) | 0.0239 | yes to Tr 25 (minimum at Tr 1830) |

- **Route 1.** The Pina-Martinez et al. 2018 supporting information (`je8b00640_si_001.pdf`, also via DOI `10.1021/acs.jced.8b00640.s001`) returned HTTP 403 twice. The values are the tc-PR table carried by Clapeyron.jl (MIT), `database/cubic/tcPR/tcPR_single.csv` at master `0778184abbe0de50791b6338cffe3444d4017508` (2026-09-23); its tcPR model cites Le Guennec et al. 2016, Pina-Martinez et al. 2018 (J. Chem. Eng. Data 63, 3980) and Pina-Martinez et al. 2022 (AIChE J. 68, e17518). Which of the two tables (2018 or 2022) the file carries is not stated; the values were not checked against the publisher's file. The table lists its own Tc, Pc (DIPPR: N2 126.2 K / 3.40 MPa, CO2 304.21 K / 7.383 MPa), within 0.13 % of CoolProp's; the study uses CoolProp's constants with the table's L, M, N. Hydrogen has no L, M, N in that table.
- **Route 2.** The reference named in the brief, Pina-Martinez, Privat, Jaubert and Peng 2019 (Fluid Phase Equilib. 485, 264), is the updated generalized *Soave* m(omega), not a generalized Twu alpha; its HAL copy refused access. The omega-generalized tc-PR Twu correlations (N = 2) documented in Clapeyron.jl (2018 version L = 0.0925 w^2 + 0.6693 w + 0.0728, M = 0.1695 w^2 - 0.2258 w + 0.8788; 2022 version L = 0.0297 w^2 + 0.7536 w + 0.0544, M = 0.1401 w^2 - 0.1785 w + 0.8678) give for H2 (omega = -0.219) L = -0.069 / -0.109 and fail the consistency check (alpha increases above Tr 0.99 / 0.92). Route 2 is not usable for H2.
- **Route 3 for H2.** The fit to CoolProp Psat, hvap and saturated-liquid cp over Tr 0.42 (triple point) to 0.95, rejecting inconsistent sets, converges to the boundary N -> 0, where alpha is identically 1; with N fixed at 1 or 2 it converges to L -> 0, M -> 1, the same alpha = 1. The data want an alpha that rises with temperature below Tc, which the consistency conditions forbid. The "Twu" column for H2 below is therefore alpha = 1, a fitted and degenerate value with no literature provenance.
- **Fitted sets for the other fluids** (route 3, not literature, a sensitivity only; all consistent): N2 L 0.12875 M 0.89228 N 1.98499; CH4 0.15357 / 0.91050 / 1.79404; C2H6 0.42296 / 0.88022 / 1.06095; CO2 0.13994 / 0.85035 / 2.54370. Fit-grid rms (Psat, hvap, cpL, %): Soave / tc-PR table / fitted = N2 6.50 / 5.83 / 5.76, CH4 6.34 / 5.86 / 5.80, C2H6 7.82 / 5.32 / 5.17, CO2 6.89 / 6.28 / 6.06, H2 29.61 / - / 27.39. The fit gains at most 0.2 points on the literature table because the objective is dominated by cpL at Tr 0.85 to 0.95, which no alpha fixes; the fitted sets are worse than the table on low-Tr Psat of N2, CH4 and CO2, so the table is used as "Twu" below.
- Soave's spurious minimum at Tr = (1 + 1/m)^2 lies at 1388 K (N2), 1775 K (CO2), 2401 K (CH4) and 2579 K (C2H6), all above the provisional 1200 K ceiling. For H2 the minimum is at Tr 1830 with CoolProp's omega (-0.219); D3's figure of about 1270 corresponds to omega = -0.216.
- Translation re-anchored at Tr 0.8, Soave / Twu [cm3/mol]: N2 3.519 / 3.564, CH4 3.389 / 3.471, C2H6 3.454 / 3.475, CO2 1.170 / 1.107, H2 4.298 / 4.106.

### 3.2 Saturation states (probe list, H2 added): deviation %, Soave / Twu

At the model's own Psat; translation (b) per alpha.

| Fluid | T [K] | Tr | Psat | rhoL | cpL | hvap |
|---|---|---|---|---|---|---|
| N2 | 63.15 | 0.500 | +4.48 / +1.12 | +0.80 / +0.74 | -8.24 / +2.33 | -1.85 / -0.36 |
| N2 | 70.00 | 0.555 | +2.46 / +0.51 | +1.24 / +1.17 | -6.95 / +0.10 | -1.33 / -0.36 |
| N2 | 77.36 | 0.613 | +1.21 / -0.01 | +1.52 / +1.45 | -5.08 / -0.88 | -0.81 / -0.20 |
| N2 | 90.00 | 0.713 | +0.34 / -0.40 | +1.31 / +1.26 | -0.63 / +0.33 | -0.15 / +0.18 |
| N2 | 100.00 | 0.792 | +0.30 / -0.28 | +0.18 / +0.16 | +4.47 / +4.05 | -0.13 / +0.23 |
| N2 | 110.00 | 0.872 | +0.49 / +0.07 | -2.42 / -2.38 | +12.32 / +11.48 | -1.25 / -0.70 |
| N2 | 120.00 | 0.951 | +0.54 / +0.35 | -8.00 / -7.93 | +27.96 / +27.82 | -6.46 / -5.62 |
| CH4 | 90.70 | 0.476 | +1.42 / +1.42 | +1.79 / +1.55 | -1.25 / +2.61 | -0.21 / -0.29 |
| CH4 | 100.00 | 0.525 | +1.04 / +0.86 | +2.08 / +1.86 | -0.93 / +0.71 | -0.07 / -0.31 |
| CH4 | 111.67 | 0.586 | +0.74 / +0.25 | +2.26 / +2.06 | -0.24 / -0.56 | +0.13 / -0.17 |
| CH4 | 130.00 | 0.682 | +0.57 / -0.27 | +1.95 / +1.81 | +1.99 / -0.10 | +0.34 / +0.23 |
| CH4 | 150.00 | 0.787 | +0.68 / -0.20 | +0.34 / +0.31 | +6.62 / +4.06 | +0.04 / +0.41 |
| CH4 | 170.00 | 0.892 | +0.83 / +0.23 | -3.78 / -3.69 | +16.40 / +14.81 | -2.22 / -1.19 |
| CH4 | 185.00 | 0.971 | +0.53 / +0.34 | -10.94 / -10.83 | +34.55 / +35.04 | -11.01 / -9.50 |
| **C2H6** | **90.40** | 0.296 | **+28.69 / +1.39** | -1.93 / -1.87 | -10.62 / +9.45 | -3.11 / +0.48 |
| **C2H6** | **120.00** | 0.393 | **+10.19 / +1.91** | -0.22 / -0.18 | -7.73 / +2.99 | -2.25 / -0.25 |
| **C2H6** | **150.00** | 0.491 | **+3.47 / +0.68** | +1.08 / +1.10 | -6.51 / -0.90 | -1.40 / -0.30 |
| C2H6 | 184.55 | 0.604 | +0.75 / -0.13 | +1.86 / +1.87 | -3.85 / -1.31 | -0.42 / +0.12 |
| C2H6 | 220.00 | 0.721 | +0.10 / -0.21 | +1.45 / +1.45 | +0.38 / +1.36 | +0.40 / +0.67 |
| C2H6 | 250.00 | 0.819 | +0.30 / +0.16 | -0.55 / -0.55 | +6.02 / +6.36 | +0.28 / +0.45 |
| C2H6 | 280.00 | 0.917 | +0.63 / +0.57 | -5.69 / -5.68 | +17.68 / +17.78 | -2.56 / -2.40 |
| C2H6 | 295.00 | 0.966 | +0.53 / +0.50 | -10.96 / -10.96 | +31.73 / +31.83 | -8.68 / -8.53 |
| CO2 | 216.60 | 0.712 | -0.37 / +1.71 | +1.09 / +1.09 | -8.92 / -9.42 | -0.50 / -1.83 |
| CO2 | 230.00 | 0.756 | -0.81 / +0.62 | +0.75 / +0.74 | -5.21 / -6.97 | +0.31 / -1.02 |
| CO2 | 250.00 | 0.822 | -0.79 / -0.09 | -0.58 / -0.57 | +1.43 / -1.44 | +1.09 / -0.05 |
| CO2 | 270.00 | 0.888 | -0.31 / -0.07 | -3.41 / -3.36 | +11.03 / +8.03 | +0.77 / +0.01 |
| CO2 | 290.00 | 0.954 | +0.21 / +0.23 | -9.20 / -9.12 | +28.90 / +27.00 | -3.37 / -3.59 |
| CO2 | 300.00 | 0.986 | +0.21 / +0.20 | -14.74 / -14.66 | +43.45 / +42.88 | -13.49 / -13.42 |
| H2 | 14.00 | 0.422 | -15.78 / -4.32 | +8.40 / +8.90 | +79.28 / +73.87 | +10.94 / +7.49 |
| H2 | 17.00 | 0.513 | -4.63 / +3.68 | +7.03 / +7.42 | +64.68 / +60.06 | +7.49 / +4.26 |
| H2 | 20.37 | 0.615 | +1.27 / +6.75 | +5.19 / +5.45 | +50.72 / +46.78 | +4.07 / +1.01 |
| H2 | 24.00 | 0.724 | +3.38 / +6.63 | +2.57 / +2.71 | +39.83 / +36.40 | +0.53 / -2.40 |
| H2 | 28.00 | 0.845 | +3.11 / +4.64 | -1.79 / -1.76 | +32.86 / +29.83 | -4.29 / -7.08 |
| H2 | 31.00 | 0.935 | +1.79 / +2.35 | -7.22 / -7.22 | +31.65 / +28.94 | -11.21 / -13.82 |

### 3.3 Saturation sweep: mean |dev| %, Soave / Twu (30 temperatures from the triple point to Tr 0.95)

| Fluid | Tr range | Psat | rhoL | cpL | hvap |
|---|---|---|---|---|---|
| N2 | 0.50 to 0.85 | 1.21 / 0.37 | 1.11 / 1.06 | 4.69 / 1.90 | 0.65 / 0.21 |
| N2 | 0.85 to 0.95 | 0.53 / 0.20 | 4.45 / 4.40 | 17.97 / 17.35 | 2.94 / 2.28 |
| CH4 | 0.48 to 0.85 | 0.77 / 0.40 | 1.64 / 1.51 | 2.90 / 1.84 | 0.22 / 0.26 |
| CH4 | 0.85 to 0.95 | 0.80 / 0.25 | 4.66 / 4.57 | 18.68 / 17.36 | 3.18 / 2.09 |
| C2H6 | 0.30 to 0.50 | 12.14 / 1.55 | 0.86 / 0.85 | 7.93 / 3.74 | 2.21 / 0.27 |
| C2H6 | 0.30 to 0.85 | 5.23 / 0.73 | 1.17 / 1.16 | 5.31 / 2.90 | 1.15 / 0.34 |
| C2H6 | 0.85 to 0.95 | 0.57 / 0.50 | 5.14 / 5.13 | 16.61 / 16.75 | 2.43 / 2.28 |
| CO2 | 0.71 to 0.85 | 0.74 / 0.52 | 0.69 / 0.68 | 4.13 / 5.10 | 0.64 / 0.75 |
| CO2 | 0.85 to 0.95 | 0.27 / 0.11 | 4.61 / 4.55 | 14.69 / 11.93 | 1.03 / 0.80 |
| H2 | 0.42 to 0.85 | 4.37 / 5.19 | 4.56 / 4.80 | 51.64 / 47.65 | 4.61 / 3.58 |
| H2 | 0.85 to 0.95 | 2.29 / 3.17 | 5.22 / 5.22 | 31.85 / 29.03 | 8.59 / 11.27 |

Per-band detail (Tr below 0.5, 0.5 to 0.7, 0.7 to 0.85) and the fitted-set column are in `item2-output.txt`.

### 3.4 Dense, supercritical and hydrogen process states (outside the band): deviation %, Soave / Twu

| Fluid | T [K] | P [MPa] | Tr | rho | cp |
|---|---|---|---|---|---|
| N2 | 77.36 | 5 | 0.61 | +1.53 / +1.45 | -6.07 / -1.74 |
| N2 | 77.36 | 10 | 0.61 | +1.46 / +1.37 | -6.79 / -2.35 |
| N2 | 100 | 10 | 0.79 | +1.79 / +1.73 | -1.53 / -1.96 |
| N2 | 130 | 6 | 1.03 | -5.60 / -5.76 | +7.05 / +7.53 |
| N2 | 150 | 10 | 1.19 | -4.05 / -4.65 | -3.75 / -3.25 |
| N2 | 300 | 10 | 2.38 | +0.24 / -1.08 | +1.00 / +2.03 |
| CH4 | 150 | 10 | 0.79 | +1.76 / +1.67 | +2.10 / -0.69 |
| CH4 | 200 | 10 | 1.05 | -4.69 / -5.00 | +4.95 / +5.59 |
| CH4 | 250 | 10 | 1.31 | +0.11 / -1.17 | -4.56 / -4.56 |
| CH4 | 300 | 10 | 1.57 | +0.95 / -0.33 | -0.17 / +0.06 |
| C2H6 | 200 | 10 | 0.66 | +2.20 / +2.19 | -3.63 / -1.85 |
| C2H6 | 320 | 10 | 1.05 | -5.92 / -5.96 | +4.59 / +4.71 |
| C2H6 | 400 | 10 | 1.31 | +0.01 / -0.09 | -2.04 / -2.03 |
| CO2 | 280 | 8 | 0.92 | -3.24 / -3.15 | +9.22 / +6.19 |
| CO2 | 280 | 10 | 0.92 | -2.38 / -2.29 | +6.80 / +3.63 |
| CO2 | 350 | 10 | 1.15 | +0.87 / +0.62 | -3.61 / -4.09 |
| CO2 | 400 | 10 | 1.32 | +0.58 / +0.16 | +0.01 / -0.25 |
| H2 | 300 | 10 | 9.05 | +0.73 / +1.13 | +0.22 / +0.22 |
| H2 | 623 | 6 | 18.8 | -0.03 / +0.08 | +0.13 / +0.14 |
| H2 | 650 | 9 | 19.6 | -0.09 / +0.06 | +0.18 / +0.19 |
| H2 | 663 | 9.1 | 20.0 | -0.10 / +0.04 | +0.18 / +0.19 |
| H2 | 700 | 10 | 21.1 | -0.15 / -0.00 | +0.18 / +0.19 |

H2 "Twu" is the degenerate alpha = 1 (section 3.1). An unattributed Twu set for hydrogen in Clapeyron.jl's `alpha/Twu/Twu_like.csv` (L 0.022437, M 0.99999983, N 0.999673, no source given) is consistent and gives fit-grid rms 28.18 % and densities +0.50, -0.12, -0.22, -0.23, -0.28 % at the five process states; it is recorded, not used.

### 3.5 Conclusion on item 2: does Twu earn its kij revalidation cost for the pilot?

Gains per property (mean |dev| to Tr 0.85, Soave to Twu):

| Fluid | Psat | rhoL | cpL | hvap | Other |
|---|---|---|---|---|---|
| N2 | 1.21 to 0.37 | 1.11 to 1.06 | 4.69 to 1.90 | 0.65 to 0.21 | compressed liquid cp at 77 K and 5 / 10 MPa -6.1 / -6.8 to -1.7 / -2.4; gas-like 300 K / 10 MPa density +0.24 to -1.08 |
| CH4 | 0.77 to 0.40 | 1.64 to 1.51 | 2.90 to 1.84 | 0.22 to 0.26 | 250 K / 10 MPa density +0.11 to -1.17; 90 to 112 K (the P5 solvent) Psat 1.4 / 1.0 / 0.7 to 1.4 / 0.9 / 0.3, cpL within 2.6 % either way |
| C2H6 | 5.23 to 0.73 (below Tr 0.5: 12.1 to 1.6) | 1.17 to 1.16 | 5.31 to 2.90 | 1.15 to 0.34 | 90.4 / 120 / 150 K Psat +28.7 / +10.2 / +3.5 to +1.4 / +1.9 / +0.7 |
| CO2 | 0.74 to 0.52 | 0.69 to 0.68 | 4.13 to 5.10 (worse) | 0.64 to 0.75 (worse) | triple point Psat -0.4 to +1.7, hvap -0.5 to -1.8 |
| H2 | 4.37 to 5.19 (worse) | 4.56 to 4.80 | 51.6 to 47.7 | 4.61 to 3.58 | no consistent literature set; the fit degenerates to alpha = 1; process states within 0.15 % (density) and 0.2 % (cp) under both |

- The alpha does not touch the near-critical liquid (Tr 0.85 to 0.95: cpL 12 to 19 % and rhoL 4.4 to 5.2 % under both) nor the liquid density (the translation carries it).
- The only large gain is ethane below Tr 0.5 (below 150 K): Psat, hvap, and cpL by about half. In the pilot that range appears only in the research-only methane/ethane liquidus (D2) and as a minor component in P5's ternary holdouts (Xiong et al. 2015). For the pilot's qualified cases the gain is small or negative: liquid methane at 90 to 112 K (the CO2 freezing solvent) gains at most 0.5 points on Psat; nitrogen as the P4 carrier gas gains nothing, and at 10 MPa Twu shifts gas-like density by -1.1 to -1.3 points (N2 300 K +0.24 to -1.08 %, CH4 250 K +0.11 to -1.17 %); CO2 is worse on cpL and hvap; H2 has no usable Twu set.
- **On these numbers, Twu does not earn the kij revalidation cost for the pilot.** D3 (Soave for the pilot) stands; ethane below about 150 K keeps its declared Psat error (+3.5 % at 150 K to +29 % at the triple point). Twu becomes worth revisiting if an ethane-rich cold liquid below 150 K or liquid cp below Tr 0.7 becomes a qualified target.
- For the record (D3's condition, not resolved here): the E-PPR78 group-contribution kij were regressed with the Soave alpha, and no group-contribution kij exists for tc-PR; adopting Twu requires revalidating, and possibly re-regressing, the kij of every admitted pair (for the pilot N2/CH4, N2/C2H6, CH4/C2H6, CO2/N2, CO2/CH4, CO2/C2H6; H2 pairs at P7) against binary VLE data.

## 4. Files

- `research/2026-09-24-coolprop-low-temperature/p1-alpha-and-liquid-path/README.md`: purpose, batch, how to run, conventions.
- `research/2026-09-24-coolprop-low-temperature/p1-alpha-and-liquid-path/pr78.py`: the shared model (probe formulation with pluggable alpha, both anchors, Newton saturation and root polish).
- `research/2026-09-24-coolprop-low-temperature/p1-alpha-and-liquid-path/item4_direct_liquid.py`, `item4-output.txt`, `item4-points.csv`: item 4.
- `research/2026-09-24-coolprop-low-temperature/p1-alpha-and-liquid-path/item2_twu_alpha.py`, `item2-output.txt`: item 2.
- Run with the throw-away `uv venv` (`$TEMP/coolprop-probe-venv`, CoolProp 8.0.0, numpy 2.5.3, scipy 1.18.1).

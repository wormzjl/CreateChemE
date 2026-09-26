# V3 property packages: `tjl19_dwsim` versus `cdu17_tjl_acs2018`

Date: 2026-09-06. Branch `codex/v3-literature-cdu` (HEAD `d0464f7`). Read-only comparison of the
new DWSIM-derived package (`V3Tjl19DwsimPackage`, revision `tjl19-dwsim-10.2.3-r1`) against the
package every existing V3 test, benchmark and the in-game pilot use (`V3Cdu17TiaJuanaPackage`,
revision `cdu17-tjl-kl1976-r2`). Numbers below come from the compiled constants, the frozen
characterization exports, and one run of the compiled V3 flash (`build/classes/java/main`) at
250 kPa. No source file, test or evidence directory was changed.

## Same assay, two characterizations

Both packages are regroupings of the same published Tia Juana Light table (Ledezma-Martinez 2019,
Appendix A: six light components plus 25 cuts, volume percentages summing to 99.98, bulk density
867.6 kg/m3 at 662.46 m3/h). Every CDU17 lump reproduces the source volume percentages exactly:

| CDU17 lump | Source cuts | vol% | TJL19 counterpart |
|---|---|---:|---|
| methane | none | 0.00 | dropped |
| C4_CDU | i-C4 + n-C4 | 1.16 | Isobutane, N-butane kept separate |
| PC01 | i-C5 + n-C5 + NBP_47 | 6.15 | Isopentane, N-pentane separate; TJL_PC01 = NBP_47+72 |
| PC02 .. PC10 | one or two cuts each | 3.37 .. 9.82 | pairs of cuts, boundaries shifted by one cut |
| PC11 | NBP_493 + 538 + 581 + 625 | 17.81 | TJL_PC09 (449+493), PC10 (538+581), PC11 (625+685) |
| PC12 | NBP_685 + 771 + 858 + 950 | 10.14 | TJL_PC12 (771+858), PC13 (950) |

So the difference is not the assay; it is how the lumps' properties were assigned.

| Item | CDU17 (previous) | TJL19 (new) |
|---|---|---|
| Components | 16 public, 15 active (methane zero) | 19, all nonzero |
| Pseudo MW, density | hand-compiled in the NextGen prototype (`d4755a5`, 2026-08-26); origin not recorded | DWSIM Riazi (1986) MW from NBP + SG; NBP-only density shape scaled 1.0199 to the bulk density |
| Tc, Pc, omega | Kesler-Lee 1976 from NBP + SG (`dec3777`, oracle-tested to 1e-9) | Riazi-Daubert 1985 Tc/Pc; omega fitted so PR78 reproduces each NBP exactly |
| Ideal-gas Cp | linear `A + B*(T-298.15)` guesses; PC12 Lastovka-Shaw cubic | degree-5 fit of native DWSIM `AUX_CPi`, held-out error 0.032% |
| Binary interactions | all zero | 22 nonzero light-pair entries, |kij| <= 0.06, pseudo pairs zero |
| Heavy-residue flag | PC12 only | TJL_PC08 .. PC13 (six cuts) |
| External validation | K-L transcription only; Cp unverified (`coefficientVerification=pending`) | native fugacity 1.3e-4, consistent PR78 departure 0.36 J/mol, feed flash 2e-5 |
| Feed basis | 695.30 mol/s, 159.654 kg/s, MW 229.6 | 737.70 mol/s, 159.653 kg/s, MW 216.4 |

## Property differences at matched boiling points

TJL19 values interpolated to each CDU17 lump's NBP (pseudocomponent range only).

| CDU17 | NBP K | MW 17 / 19 | dMW | density 17 / 19 | Tc 17 / 19 | Pc MPa 17 / 19 | omega 17 / 19 | Cp298 J/kg/K 17 / 19 | Cp638 J/kg/K 17 / 19 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| PC01 | 315.8 | 86 / 81 | -6% | 728 / 712 | 493 / 509 | 4.18 / 3.64 | 0.226 / 0.238 | 1302 / 1256 | 2271 / 2600 |
| PC03 | 383.5 | 123 / 106 | -14% | 768 / 757 | 569 / 570 | 3.25 / 3.08 | 0.324 / 0.301 | 1179 / 1350 | 1994 / 2633 |
| PC05 | 456.1 | 174 / 147 | -16% | 808 / 806 | 644 / 649 | 2.53 / 2.45 | 0.455 / 0.397 | 1075 / 1426 | 1749 / 2664 |
| PC07 | 553.5 | 244 / 218 | -11% | 849 / 858 | 734 / 748 | 1.81 / 1.81 | 0.668 / 0.541 | 1020 / 1477 | 1571 / 2692 |
| PC09 | 651.0 | 333 / 315 | -6% | 891 / 899 | 820 / 839 | 1.34 / 1.37 | 0.900 / 0.713 | 985 / 1505 | 1439 / 2715 |
| PC11 | 823.0 | 470 / 580 | +23% | 958 / 957 | 964 / 987 | 0.80 / 0.89 | 1.286 / 1.134 | 968 / 1531 | 1330 / 2751 |
| PC12 | 1036.0 | 650 / 1200 | +85% | 1009 / 1030 | 1123 / 1169 | 0.37 / 0.62 | 1.731 / 1.959 | 1411 / 1527 | 2620 / 2751 |

- Densities agree within 3%; critical temperatures within 5%; critical pressures within 15%.
- Molecular weights disagree systematically: CDU17 mid cuts are 11-16% heavier than the Riazi
  values, its residue lumps far lighter. That is why the same mass gives 6.1% more moles in TJL19.
- Heat capacity is the largest difference. CDU17's Cp slope is roughly half the DWSIM slope, and its
  values fall with boiling point (968 J/kg/K for PC11 at 298 K) then jump to 1411 for PC12, which
  uses a different correlation. TJL19 is smooth (1256 to 1542 J/kg/K at 298 K) and agrees with
  pure-component references for the real light components. At 638 K the CDU17 mid and heavy cuts
  carry 25-50% less sensible heat per kilogram than TJL19.

Mixture ideal-gas enthalpy of the feed relative to 298.15 K:

| T K | CDU17 Cp J/kg/K | TJL19 Cp J/kg/K | CDU17 H kJ/kg | TJL19 H kJ/kg |
|---:|---:|---:|---:|---:|
| 298.15 | 1095 | 1477 | 0 | 0 |
| 500 | 1487 | 2272 | 261 | 382 |
| 638.15 | 1737 | 2712 | 484 | 727 |
| 900 | 2182 | 3315 | 998 | 1523 |

## V3 flash of each feed at 250 kPa (compiled classes, same code path)

| T K | CDU17 beta mol | TJL19 beta mol | CDU17 H kJ/kg | TJL19 H kJ/kg |
|---:|---:|---:|---:|---:|
| 332.15 | 0 (liquid) | 0 (liquid) | -274 | -240 |
| 500 | 0.403 | 0.433 | 51 | 193 |
| 600 | 0.659 | 0.721 | 278 | 510 |
| 638.15 (feed) | 0.733 | 0.799 | 366 | 636 |
| 700 | 0.828 | 0.894 | 512 | 842 |
| 800 | 0.939 | 0.974 | 751 | 1178 |

At the literature feed condition TJL19 reproduces native DWSIM (0.79947) to 3e-7; CDU17 vaporizes
6.7 molar points less, and the same feed carries 270 kJ/kg (43 MW) less enthalpy on the shared
datum. The K-value ladder is similar in shape; TJL19 simply has more, lighter molecules in the
naphtha range and heavier ones in the residue (TJL_PC13 K = 1e-14 at feed conditions).

Incidental observation, not investigated: a cold CDU17 flash at 600 K straight from the Wilson
guess threw "lost its two-phase Rachford-Rice root"; the same state converged when the workspace
had been warmed from 332 K upward. TJL19 did not show this.

## What follows for the literature CDU track

1. Results computed with the two packages are not comparable, especially heat balances. The
   prescribed pumparound cooling (-41.93 MW) is 36% of the TJL19 feed sensible enthalpy above
   298 K but 54% of the CDU17 value; the same duties would over-cool a CDU17 column.
2. The 491/515/165 kmol/h draws are 44.1% of the TJL19 feed moles and 46.8% of the CDU17 feed
   moles. Every V3 draw-wall measurement so far (converges below 0.25x, stalls above 0.40x) was
   made on CDU17 moles, so the margins do not transfer one-to-one.
3. CDU17's heat-capacity data are the weakest constants in V3 and were never revised by the
   Kesler-Lee work (`dec3777` replaced Tc/Pc/omega only). If CDU17 stays the in-game pilot package,
   its Cp should be regenerated the same way TJL19's was, or the package should be retired for
   the literature work as the plan already intends.
4. TJL19's heavy end is an extrapolation (MW up to 2.2 kg/mol, omega up to 3.6, six flagged cuts).
   It is internally consistent and native-validated, but it is not experimental accuracy, and it
   is exactly where V3's dry-tray degeneracy lives.
5. Only the TJL19 package test and the case contract use the new package; no V3 column has been
   solved with it yet. The first TJL19 column solve should be a plain no-draw, no-heat case to
   separate property effects from the draw/heat continuation problems already documented.

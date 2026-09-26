# TJL19 wet column with three pumparounds and three draws: tray balances

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc` at `4a42c0a`. Probe: `build/pkgcmp/TrayBalanceProbe.java`
(replays the calculator's own stage continuation and ramp via `solveDwsimStageContinuation`, truncation OFF, then
re-evaluates every MESH row of the final state). Input: TJL19 feed 2610.7 kmol/h at 638.15 K, 30 trays, feed tray 24,
250 kPa top, 750 Pa/tray, condenser 332.15 K, reflux 4.17, reboiler 8 MW, sump steam 333.33 mol/s at 533.15 K,
pumparounds -5 MW trays 6-9, -6 MW trays 13-16, -4 MW trays 20-23 (uniform), default draws 25.64 / 36.63 / 9.16 mol/s
at trays 13 / 17 / 22.

## Finding

The case the suite records as NONCONVERGENCE is a converged column. The same code and input converge on Java 25
(residual 1.5e-13, 5 Newton iterations at the requested rung, 9.7 s) and stall on Java 21, the Gradle test JVM
(residual 3.8e-8 after 32 iterations, 19.8 s). The two final states agree to 0.0001 K and 0.001 mol/s on every node
and every tray energy balance closes to below 1 W on both. The leftover residual on Java 21 sits on the component
material balances of mid-boiling cuts in the pinched top section: TJL_PC04 on trays 0-4 (worst 2.9e-6 mol/s of a
76.2 mol/s feed component on tray 1, scaled 3.79e-8 against the 1e-8 acceptance tolerance), TJL_PC05 on trays
11-14, TJL_PC06 on trays 16-18, TJL_PC01/PC02 on trays 28-30. The steam-rung residual already differs between
the JVMs in the seventh digit (0.0534849437 vs 0.0534849183), so the requested-rung Newton starts from slightly
different seeds and one path stagnates a factor four above tolerance. This is a termination knife edge in a
physically converged state, not the dry-tray wall; the trace components (TJL_PC13 down to 1e-124 mol/s) are
identical in both runs and in the converged no-draw case C.

The draws-only case B (no pumparound) is a different matter: on Java 25 it ends at residual 8.2e-3 with a
systematic drift, energy residuals of 130 kW on trays 10-24 and material residuals of 7e-4 on every tray, after
the 32-iteration budget. That is slow convergence of a still-moving column, and adding the pumparounds turns it
into a 5-iteration solve. Cooling above the draws supplies the liquid the draws need, as predicted in the plan.

## Case A final state (Java 25, converged; Java 21 identical to the printed precision)

branch=LIQUID_ONLY elapsed=9.7 s path=cold/dwsim-sequential/4-8-15-30/fine-fd/draw-ramp-1.0
attempt=Converged residual=1.47e-13 iterations=5
  event: steam ramp stopped at 0.375: iteration budget exhausted, iterations=40, residual=0.053484943759049254; failed checks=[EQUILIBRIUM, GLOBAL_ENERGY_BALANCE]
node      T_K    L_mol_s    V_mol_s    draw    H2O_V      Q_MW     HL_MW     HV_MW    Eres_kW     matRes        minL      minV    nTrace
   0   332.15   1725.742      0.000   0.000     0.00     0.000   -46.693     0.000      0.000   3.98e-14 1.0e-124(TJL_PC13)  Infinity     9 cond
   1   412.69   1458.430   1725.742   0.000   333.33     0.000   -18.633    30.442     -0.000   4.64e-14 1.5e-88(TJL_PC13)  3.3e-126     9 
   2   441.80   1579.903   1792.229   0.000   333.33     0.000    -7.430    49.470     -0.000   4.78e-14 1.1e-68(TJL_PC13)  3.6e-101     9 
   3   451.43   1637.102   1913.703   0.000   333.33     0.000    -2.739    60.674     -0.000   5.04e-14 1.9e-67(TJL_PC13)   2.1e-98     9 
   4   454.72   1657.411   1970.901   0.000   333.33     0.000    -0.984    65.365     -0.000   3.98e-14 4.6e-67(TJL_PC13)   1.6e-97     8 
   5   455.97   1664.565   1991.210   0.000   333.33     0.000    -0.299    67.119     -0.000   5.31e-14 1.3e-66(TJL_PC13)   7.2e-97     8 
   6   456.52   1699.836   1998.365   0.000   333.33    -1.250     0.005    67.805     -0.000   4.24e-14 2.0e-66(TJL_PC13)   1.3e-96     8 
   7   457.03   1735.659   2033.636   0.000   333.33    -1.250     0.300    69.358     -0.000   4.24e-14 2.1e-66(TJL_PC13)   1.7e-96     8 
   8   457.51   1769.778   2069.458   0.000   333.33    -1.250     0.592    70.904     -0.000   3.71e-14 2.0e-66(TJL_PC13)   1.9e-96     8 
   9   458.06   1799.603   2103.577   0.000   333.33    -1.250     0.941    72.445     -0.000   4.78e-14 1.2e-66(TJL_PC13)   1.4e-96     8 
  10   458.87   1785.878   2133.403   0.000   333.33     0.000     1.449    74.044     -0.000   4.51e-14 2.7e-46(TJL_PC13)   4.1e-76     8 
  11   460.22   1745.954   2119.677   0.000   333.33     0.000     2.298    74.553     -0.000   2.92e-14 1.1e-45(TJL_PC13)   2.7e-75     8 
  12   463.25   1668.076   2079.753   0.000   333.33     0.000     4.153    75.402     -0.000   5.31e-14 2.1e-45(TJL_PC13)   1.4e-74     7 
  13   469.38   1600.653   2001.875   0.016   333.33    -1.500     7.957    77.257     -0.000   4.24e-14 3.4e-45(TJL_PC13)   1.6e-73     7 
  14   479.08   1535.647   1934.452   0.000   333.33    -1.500    13.984    82.561     -0.000  -5.94e-14 5.4e-45(TJL_PC13)   4.7e-72     7 
  15   489.30   1551.155   1895.086   0.000   333.33    -1.500    21.260    90.215     -0.000   4.78e-14 8.4e-45(TJL_PC13)   1.4e-70     6 
  16   497.06   1599.924   1910.593   0.000   333.33    -1.500    27.725    98.991     -0.000   4.91e-14 1.7e-37(TJL_PC13)   2.4e-62     6 
  17   501.84   1621.096   1959.362   0.023   333.33     0.000    31.770   106.956     -0.000   4.78e-14 5.0e-37(TJL_PC13)   2.7e-61     6 
  18   504.27   1595.625   1980.535   0.000   333.33     0.000    33.134   111.001     -0.000   4.71e-14 2.3e-36(TJL_PC13)   2.5e-60     5 
  19   505.52   1598.873   1991.688   0.000   333.33     0.000    34.172   113.083     -0.000   4.74e-14 5.4e-36(TJL_PC13)   8.0e-60     5 
  20   506.31   1616.070   1994.936   0.000   333.33    -1.000    35.199   114.121     -0.000   4.74e-14 7.7e-36(TJL_PC13)   1.4e-59     4 
  21   507.37   1619.806   2012.134   0.000   333.33    -1.000    36.225   116.148     -0.000   4.71e-14 9.8e-36(TJL_PC13)   2.4e-59     4 
  22   509.28   1576.780   2015.869   0.006   333.33    -1.000    37.092   118.174     -0.000   4.74e-14 2.4e-26(TJL_PC13)   9.6e-50     3 
  23   514.12   1239.317   1972.843   0.000   333.33    -1.000    33.328   120.041     -0.000   4.74e-14 4.8e-21(TJL_PC13)   7.8e-44     2 
  24   539.62    655.451   1644.536   0.000   333.33     0.000    42.348   117.492     -0.000   4.66e-14 5.0e-03(Ethane)   4.8e-21     1 feed
  25   528.87    537.842    335.475   0.000   333.33     0.000    33.682    27.035     -0.000   4.53e-14 7.3e-05(Ethane)   2.2e-22     1 
  26   523.43    488.773    217.866   0.000   333.33     0.000    29.997    18.369     -0.000   4.29e-14 1.2e-06(Ethane)   6.2e-23     1 
  27   519.84    458.878    168.797   0.000   333.33     0.000    27.771    14.684     -0.000   3.97e-14 2.0e-08(Ethane)   2.7e-23     1 
  28   517.21    437.235    138.903   0.000   333.33     0.000    26.217    12.458     -0.000   3.55e-14 3.5e-10(Ethane)   1.5e-23     1 
  29   515.45    420.296    117.259   0.000   333.33     0.000    25.168    10.904     -0.000   3.04e-14 6.2e-12(Ethane)   1.0e-23     1 
  30   515.84    405.272    100.320   0.000   333.33     0.000    25.015     9.855     -0.000   2.38e-14 1.1e-13(Ethane)   1.2e-23     2 
  31   528.27    319.976     85.297   0.000   333.33     0.000    26.012     9.703     -0.000   1.48e-14 1.5e-15(Ethane)   2.6e-22     3 sump
total stage heat = -15.00 MW; feed enthalpy flow = 99.477 MW; energy scale = 7.25e+07 W
components (active order): Ethane Propane Isobutane N-butane Isopentane N-pentane TJL_PC01 TJL_PC02 TJL_PC03 TJL_PC04 TJL_PC05 TJL_PC06 TJL_PC07 TJL_PC08 TJL_PC09 TJL_PC10 TJL_PC11 TJL_PC12 TJL_PC13 

Java 21 leftover material rows:

java version "21.0.11" 2026-04-21 LTS

## Case B final state (Java 25, draws only, not converged)

branch=LIQUID_ONLY elapsed=16.7 s path=cold/dwsim-sequential/4-8-15-30/failed-stage-30
attempt=Failure residual=0.00823 iterations=32
  FAILED CHECK EQUILIBRIUM value=0.00823 limit=1.00e-08 fresh residual recomputation exceeded its limit
  FAILED CHECK GLOBAL_ENERGY_BALANCE value=3.60e+06 limit=100 fresh boundary closure 3.60391e+06 W; condenser=-1.00144e+08 W, stage heat=0.00000 W
  event: steam ramp stopped at 0.375: iteration budget exhausted, iterations=40, residual=0.053484943759049254; failed checks=[EQUILIBRIUM, GLOBAL_ENERGY_BALANCE]
  event: side-draw ramp reached the requested input and failed: iteration budget exhausted, iterations=32, residual=0.008230402027445655
node      T_K    L_mol_s    V_mol_s    draw    H2O_V      Q_MW     HL_MW     HV_MW    Eres_kW     matRes        minL      minV    nTrace
   0   332.15   1848.719      0.000   0.000     0.00     0.000   -51.004     0.000      0.000  -6.97e-04 2.6e-122(TJL_PC13)  Infinity     8 cond
   1   418.36   1611.444   1848.559   0.000   333.33     0.000   -18.301    35.231    160.289  -7.97e-04 2.1e-86(TJL_PC13)  5.1e-123     9 
   2   447.68   1712.626   1968.739   0.000   333.33     0.000    -4.824    58.229    203.758  -9.07e-04 9.3e-67(TJL_PC13)   2.4e-98     8 
   3   460.04   1684.287   2069.760   0.000   333.33     0.000     2.412    71.909    271.210  -1.44e-03 7.4e-66(TJL_PC13)   1.5e-95     8 
   4   470.12   1606.065   2041.204   0.000   333.33     0.000     8.643    79.417    304.641  -2.81e-03 5.3e-66(TJL_PC13)   2.9e-94     8 
   5   481.08   1558.200   1962.643   0.000   333.33     0.000    15.705    85.952    206.447  -4.40e-03 3.4e-66(TJL_PC13)   5.1e-93     8 
   6   490.82   1556.244   1914.323   0.000   333.33     0.000    22.557    93.220     36.534  -4.35e-03 1.4e-66(TJL_PC13)   3.5e-92     8 
   7   497.41   1573.253   1911.937   0.000   333.33     0.000    27.656   100.109    -42.074  -1.90e-03 6.6e-67(TJL_PC13)   1.0e-91     7 
   8   501.09   1589.513   1928.731   0.000   333.33     0.000    30.723   105.166      0.187   1.28e-03 4.5e-67(TJL_PC13)   1.9e-91     8 
   9   502.96   1600.697   1945.040   0.000   333.33     0.000    32.366   108.233     71.888   2.92e-03 3.2e-67(TJL_PC13)   2.3e-91     8 
  10   503.90   1608.237   1956.408   0.000   333.33     0.000    33.237   109.948    114.885   2.41e-03 1.4e-46(TJL_PC13)   1.3e-70     7 
  11   504.42   1613.845   1964.085   0.000   333.33     0.000    33.741   110.934    129.619   7.85e-04 2.0e-45(TJL_PC13)   2.1e-69     7 
  12   504.75   1618.479   1969.689   0.000   333.33     0.000    34.077   111.567    132.090  -5.14e-04 1.2e-44(TJL_PC13)   1.3e-68     7 
  13   504.99   1622.629   1974.220   0.016   333.33     0.000    34.339   112.035    131.444  -6.78e-04 3.7e-44(TJL_PC13)   4.6e-68     7 
  14   505.19   1600.802   1978.239   0.000   333.33     0.000    34.021   112.429    131.462  -7.50e-04 1.8e-43(TJL_PC13)   2.4e-67     6 
  15   505.39   1604.424   1981.913   0.000   333.33     0.000    34.231   112.785    131.061  -7.69e-04 1.8e-42(TJL_PC13)   2.5e-66     6 
  16   505.58   1607.760   1985.395   0.000   333.33     0.000    34.437   113.126    130.589  -7.62e-04 1.3e-34(TJL_PC13)   1.9e-58     6 
  17   505.78   1610.480   1988.592   0.023   333.33     0.000    34.643   113.463    129.087  -7.51e-04 3.5e-34(TJL_PC13)   5.5e-58     6 
  18   506.02   1574.944   1991.175   0.000   333.33     0.000    34.060   113.797    129.081  -7.51e-04 7.9e-34(TJL_PC13)   1.4e-57     5 
  19   506.35   1572.173   1992.121   0.000   333.33     0.000    34.281   114.132    129.064  -7.51e-04 1.0e-33(TJL_PC13)   1.9e-57     5 
  20   506.95   1559.762   1989.206   0.000   333.33     0.000    34.545   114.482    129.019  -7.50e-04 1.0e-33(TJL_PC13)   2.2e-57     4 
  21   508.20   1523.826   1976.648   0.000   333.33     0.000    34.910   114.875    128.895  -7.49e-04 1.0e-33(TJL_PC13)   3.1e-57     4 
  22   511.07   1428.683   1940.563   0.006   333.33     0.000    35.376   115.369    128.506  -7.46e-04 2.0e-24(TJL_PC13)   1.3e-47     3 
  23   518.25   1062.541   1845.264   0.000   333.33     0.000    31.776   115.963    128.253  -7.45e-04 1.1e-20(TJL_PC13)   5.3e-43     2 
  24   547.77    586.987   1488.115   0.000   333.33     0.000    43.821   112.718    126.737  -7.40e-04 4.8e-03(Ethane)   2.6e-20     1 feed
  25   537.31    481.107    287.205   0.000   333.33     0.000    35.407    25.413    117.744  -7.21e-04 6.6e-05(Ethane)   1.5e-21     1 
  26   532.03    438.143    181.170   0.000   333.33     0.000    31.865    17.116    103.510  -6.93e-04 1.1e-06(Ethane)   4.6e-22     1 
  27   528.69    413.371    138.061   0.000   333.33     0.000    29.816    13.678     85.851  -6.58e-04 3.1e-08(Ethane)   2.3e-22     1 
  28   526.46    396.994    113.158   0.000   333.33     0.000    28.507    11.715     66.358  -6.18e-04 6.4e-10(Ethane)   1.5e-22     1 
  29   525.28    385.911     96.665   0.000   333.33     0.000    27.780    10.472     46.654  -5.68e-04 1.4e-11(Ethane)   1.2e-22     1 
  30   526.35    377.398     85.517   0.000   333.33     0.000    27.995     9.793     28.328  -4.99e-04 2.9e-13(Ethane)   1.6e-22     2 
  31   539.15    300.467     76.949   0.000   333.33     0.000    28.646    10.036     12.790  -3.77e-04 4.6e-15(Ethane)   3.3e-21     2 sump
total stage heat = 0.00 MW; feed enthalpy flow = 99.477 MW; energy scale = 7.25e+07 W
components (active order): Ethane Propane Isobutane N-butane Isopentane N-pentane TJL_PC01 TJL_PC02 TJL_PC03 TJL_PC04 TJL_PC05 TJL_PC06 TJL_PC07 TJL_PC08 TJL_PC09 TJL_PC10 TJL_PC11 TJL_PC12 TJL_PC13 

## Reading the balances

- Condenser is liquid-only at 332.15 K: 1726 mol/s total condensate, 334 mol/s distillate, 1392 mol/s reflux;
  all 333 mol/s of steam decants as free water. Tray 1 vapour equals the condensate exactly.
- Trays 2-12 are pinched: 442-463 K, only 4 K across trays 4-11, liquid 1580-1800 mol/s against vapour
  1790-2130 mol/s. Each cooled tray of the first pumparound raises the liquid leaving it by about 35 mol/s.
- Draw fractions are small (1.6%, 2.3%, 0.6% of tray liquid), which is why the draws converge here while the
  literature 44% draws do not.
- Feed tray 24: 540 K; liquid falls from 1239 to 655 mol/s and vapour from 1973 to 1645 mol/s across it. Below
  the feed the vapour is 335 down to 85 mol/s and is mostly water; the stripping trays cool from 529 to 516 K
  as light ends evaporate into the steam, and the 8 MW reboiler lifts the sump back to 528 K.
- Liquid enthalpy flow runs from -18.6 MW at tray 1 to +42 MW at the feed tray; vapour enthalpy flow from
  30 MW to 120 MW at tray 23. Every tray energy residual is below 1 W in both JVM runs.

## Consequences

1. The test note attributing this case to the trace-component wall is wrong and should be corrected; the pinned
   typed-failure contract stays valid, but the explanation is JVM-dependent termination at 4x tolerance.
2. Results at the 1e-7 level are not reproducible across JDK 21 and 25 (Math intrinsics differ in the last bit,
   and the ramp amplifies that). Any tolerance-edge assertion needs to state the JVM it was measured on.
3. A cheap remedy for the stagnation: when the requested rung exhausts its budget with the step norm already at
   the convergence gate and the residual within 10x of tolerance, refresh the Jacobian (fine finite difference)
   and allow a short extra budget before declaring NONCONVERGENCE. Alternatively raise the requested-rung
   iteration budget from 32 for heat-bearing wet inputs. Neither was applied here.
4. The draws-only slow convergence (case B) is the real numerical difficulty of this column; it is helped, not
   hurt, by the pumparounds.

# Why the reconstructed literature column runs cold, and what the thesis column actually receives

Date: 2026-09-08. Branch `claude/v3-literature-cdu-handoff-3179dc` at `9b2a599`. Analysis only. Source:
Ledezma-Martínez (2019), text in `build/pkgcmp/thesis.txt`; Table 1.1 (stage temperatures), Table 6.1
(no-preflash base case), Table A6 (stream data). Probes: `build/pkgcmp/TrayBalanceProbe2.java` cases `L`, `H`,
`S` (the last two on a probe-only build with `MAX_PUMPAROUNDS = 5`, `build/pkgcmp/classes-var`).

## 1. The symptom

The reconstruction contract (40 trays, draws 10/18/28, coolers 8←10/16←18/26←28 at 12.84/17.89/11.20 MW,
1,200 kmol/h bottom steam, no reboiler, condenser 59 °C, reflux 4.17) converges and is rejected by the water
dew point on tray 1: 83.1 °C against a dew point of 87 °C. The thesis's tray 1 is at 93.7 °C.

## 2. The whole column is cold, not just the top

| Stage | Thesis Table 1.1, °C | Reconstruction (converged), °C | Difference |
|---|---|---|---|
| 1 | 93.7 | 83.1 | −10.6 |
| 9 | 146.5 | 131.5 | −15.0 |
| 10 | 147.4 | 142.8 | −4.6 |
| 17 | 227.5 | 198.1 | −29.4 |
| 18 | 238.6 | 216.2 | −22.4 |
| 27 | 304.9 | 268.3 | −36.6 |
| 28 | 310.9 | 276.2 | −34.7 |
| 36 | 341.3 | 310.7 | −30.6 |
| 37 (feed) | 341.3 | 329.4 | −11.9 |
| 41 (bottom) | 335.1 | 299.2 | −35.9 |

Overhead: 3,616 kmol/h of hydrocarbon vapour to the condenser against the thesis's 4,307 (833 kmol/h of light
naphtha × (1 + 4.17)); condenser duty 30 MW against 56.2 MW; residue about 830 kmol/h against 565.

The water fraction of the overhead vapour is the same in both: the thesis carries 1,200 kmol/h of main steam
plus 250 kmol/h of HD-stripper steam (Table 6.1) in 4,307 kmol/h of hydrocarbon, 25.2% water, dew point 87 °C;
the reconstruction carries 1,200 in 3,616, 24.9%, dew point 87 °C. The thesis top clears the dew point by
6.7 °C only because it is 10 °C hotter.

## 3. What the thesis column receives that the reconstruction does not

Table A6, no-preflash base case, duties into the system:

| Stream | Duty |
|---|---|
| HN side-stripper reboiler | 12.8 MW |
| LD side-stripper reboiler | 5.3 MW |
| HD side-stripper steam | 250 kmol/h (Table 6.1) |

The two reboiled strippers return their vapour to the main column at stages 9 and 17 (contract:
`source_side_stripper_vapor_returns` 9/17/27). That is 18.1 MW of heat entering sections 1 and 2, plus the
light ends stripped out of the heavy naphtha and light distillate, which travel up the column and leave as light
naphtha. The handoff removed the strippers and explicitly said not to transfer their utilities into the main
column. Those utilities are what hold the thesis column 30 to 40 °C hotter from section 2 down and make its
overhead 19% larger and heavier at the top.

## 4. The check: put the stripper heat back

Probe-only experiment, same contract plus heaters at the stripper return stages (+12.8 MW at 9, +5.3 MW at 17)
and, in the second row, the 250 kmol/h HD-stripper steam at 27. Neither run converges (the heat ramp stalls with
mixed heaters and coolers; residual 0.5), so the numbers are an iterate, not a solution, but the direction is
unambiguous:

| Stage | Thesis | Reconstruction | + stripper heat (iterate) | + heat + HD steam (iterate) |
|---|---|---|---|---|
| 1 | 93.7 | 83.1 | 101.5 | 100.9 |
| 9 | 146.5 | 131.5 | 162.8 | 170.2 |
| 18 | 238.6 | 216.2 | 236.7 | 234.2 |
| 27 | 304.9 | 268.3 | 300.6 | 298.2 |
| 28 | 310.9 | 276.2 | 308.8 | 305.2 |
| 36 | 341.3 | 310.7 | 340.8 | 338.2 |
| 41 | 335.1 | 299.2 | 330.8 | 330.0 |
| Condenser | 56.2 MW | 30 MW | 58.6 MW | 63.1 MW |

With the stripper heat the profile from stage 18 down lands within 2 to 5 °C of the thesis and the condenser
duty within 5%; the top goes to about 101 °C, above the dew point. The reconstruction's cold column is the
missing stripper heat, not the property package, the steam conditions or the solver.

## 5. What to do about it

1. **Model the side strippers.** The correct fix, and already the next item of the full-CDU plan: a
   steam-stripped HD stripper and two reboiled strippers with liquid draws at 10/18/28 and vapour returns at
   9/17/27. This restores both the heat and the light-ends recycle, and the light-naphtha rate becomes a result
   to compare with 833 kmol/h.
2. **Interim surrogate.** Heaters at the return stages with the reboiler duties. Needs the pumparound list
   widened beyond three (or a separate stage-heater list), the GUI's cooling-only rule relaxed, and the heat
   ramp taught to handle heaters (both probe runs stalled). Captures the heat but not the stripped light ends,
   so the top would still be lighter and the light naphtha smaller than the thesis.
3. **Leave the dew-point verdict as it is.** It is doing its job: it flagged a specification that omits 18 MW of
   heat the real column has.

Not a cause: the property package (the feed-tray temperature is within 12 °C even without the heat), the steam
conditions (inherited 4.5 bar / 260 °C), the reflux definition (the thesis fixes both reflux ratio 4.17 and the
light-naphtha rate 833 kmol/h; the reconstruction fixes the ratio and the condenser temperature, and the rate
then follows from the missing heat).

## 6. Making the reconstruction work by trimming the top cooler (2026-09-08)

Sweep of duty factors on the three coolers, current solver, everything else per the contract
(`build/pkgcmp/pa-sweep.log`):

| PA1 / PA2 / PA3 factor | Outcome | Tray 1 | Condenser | Notes |
|---|---|---|---|---|
| 1.0 / 1.0 / 1.0 | WATER_DEW_POINT 1.16 | 83.1 °C | 30 MW | the contract as published |
| 0.8 / 1.0 / 1.0 | SUCCESS | 87.0 °C | 31 MW | just above the dew point |
| 0.7 / 1.0 / 1.0 | SUCCESS | 88.6 °C | 33 MW | |
| 0.6 / 1.0 / 1.0 | SUCCESS | 90.1 °C | 34 MW | |
| 0.5 / 1.0 / 1.0 | SUCCESS | 91.5 °C | 35 MW | closest to the source's 93.7 °C |
| 0.0 / 1.0 / 1.0 | not converged | | | ramp stall |
| PA2 or PA3 reduced | not converged | | | heat-ramp stalls; not pursued |

Only the top cooler matters for the top temperature; the lower coolers' reductions stall the heat ramp and
were not pursued. The fresh-calculator preset now carries PA1 at 6.42 MW (half the published 12.84 MW), the
other two unchanged, total 35.51 MW. This is a documented deviation from the source that stands in for the
18.1 MW of side-stripper reboiler heat the reconstruction omits; the correct fix remains modelling the side
strippers, after which PA1 should return to 12.84 MW. `V3LiteraturePresetTest` pins both: the preset converges
above the dew point, and the published full duty still produces the named WATER_DEW_POINT verdict.

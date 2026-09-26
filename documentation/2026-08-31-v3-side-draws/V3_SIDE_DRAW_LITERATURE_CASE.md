# V3 literature-anchored side-draw case

## Source

D. Sotelo et al., “Dynamic Simulation of a Crude Oil Distillation Plant Using Aspen-HYSYS,”
*International Journal of Simulation Modelling* 18(2), 229–241 (2019),
doi:[10.2507/IJSIMM18(2)465](https://doi.org/10.2507/IJSIMM18(2)465). The publisher PDF is
[available here](https://www.ijsimm.com/Full_Papers/Fulltext2019/text18-2_229-241.pdf).

The atmospheric column case reports:

| Quantity | Published value |
| --- | ---: |
| Crude feed | 99,000 bbl/day |
| Trays | 29 |
| Top pressure | 104 kPa |
| Bottom pressure | 198.54 kPa |
| Atmospheric feed temperature | 338.4 °C |
| Kerosene draw | stage 13, 14,000 bbl/day |
| Diesel draw | stage 17, 20,000 bbl/day |
| AGO draw | stage 22, 5,000 bbl/day |

The three product draws are 14.1414%, 20.2020%, and 5.0505% of source crude volume, or 39.3939%
combined. The source column also contains three steam side strippers and four pumparounds. V3 currently
models none of those, so this is a flow-fraction analog rather than a reproduction of the published HYSYS
model.

## Tracked test fixture

`V3Sotelo2019SideDrawCase` transfers the published volume fractions to the existing 2610.7 kmol/h Tia
Juana Light molar feed. This dimensional mapping intentionally preserves product load fractions; it does not
claim that barrel fraction equals molar fraction for the Mexican crude blend.

Two inputs are defined:

1. `sourceGeometryAnalog(1.0)` preserves the published tray count, draw stages, feed temperature, top and
   bottom pressure profile, and full draw fractions. Feed stage 24, reflux ratio 2, and 8 MW reboiler duty are
   explicit V3 closure assumptions.
2. `dryQualificationInput()` preserves the 29 trays, stages 13/17/22, and 14:20:5 product ratio at 25% of
   the published load, on V3's qualified dry-TJL thermal lane (638.15 K feed, 150 kPa top, 0.75 kPa/tray,
   400 K condenser, reflux 2, duty 8 MW).

The dry qualification rates are:

| Product | Rate | Fraction of model feed |
| --- | ---: | ---: |
| Kerosene | 92.2975 kmol/h | 3.5354% |
| Diesel | 131.8535 kmol/h | 5.0505% |
| AGO | 32.9634 kmol/h | 1.2626% |
| **Total** | **257.1144 kmol/h** | **9.8485%** |

The tracked test checks the source geometry/fractions exactly and requires the dry qualification to converge,
pass fresh convergence/audit gates, and publish all three side-draw streams at their authored rates.

## Measured behavior at `321fd8a` plus the new fixture

- Dry qualification: **SUCCESS** in 5.575 s; final requested rung took 6 Newton iterations; maximum scaled
  residual 3.464e-14; largest withdrawal fraction 0.11984.
- Full source-load/source-geometry analog: typed **NONCONVERGENCE** in 24.075 s. The 50% ramp stalled and
  the full-rate terminal state exhausted tray-22 liquid (`w = 1.0353`). This is retained as a benchmark
  boundary, not asserted as a unit-test success or used to claim the published refinery case is infeasible.

Raw reports:

- `build/reports/benchmarks/v3-sotelo-2019-dry-qualification.json`
- `build/reports/benchmarks/v3-sotelo-2019-full-load-boundary.json`

The full-load gap is expected to remain until V3 represents at least pumparound heat removal and side-stripper
returns. Scaling the source rates down silently would not be a valid substitute for those missing operations;
the 25% qualification scale is named and tested explicitly.

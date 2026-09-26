# Fluid property compatibility and qualification

Updated 2026-09-16. This records the implemented model and the scope of its evidence. **M1 is not yet fully qualified.** Passing a finite grid does not establish accuracy throughout a continuous domain.

## Decisions implemented

| Quantity | Current treatment | Qualification meaning |
|---|---|---|
| Liquid compression | Shared configurable isothermal compressibility, default `1e-9 Pa^-1`, for hydrocarbon liquid and free water | User-approved approximation. Test the configured response and thermodynamic identities; do not claim material-specific compressibility accuracy. |
| Liquid reference density | Calibrated translated PR hydrocarbon reference; water reference from Region 1 | Retains material differences in density. A calibration point is not an independent validation point. |
| Liquid viscosity | Material-dependent logarithmic mixture rule; conditional dissolved-gas factors require a supported liquid carrier | A conditional methane/ethane/nitrogen factor is not a pure-liquid property claim at room temperature. Unsupported states refuse. |
| Gas viscosity | Wilke mixture rule, with water-vapor contribution | Reference-pressure approximation; property-data ranges still apply. |
| Equilibrium and temperature | Existing coupled component/U/V equilibrium equations and EOS/flash | No temperature setting is introduced by velocity saturation. |
| Maximum pipe velocity | `min(configured maximum, existing acoustic bound)`, default configured maximum `100 m/s` | Limits transported mass inside the coupled equations; the resulting transport still carries component amounts and enthalpy. |
| Empty vessel | One-time nitrogen initialization at placement; no wall heat capacity | Canonical-empty isolated vessels retain zero stock without a fictitious temperature. Unsupported connected vacuum filling refuses. |

## Model bounds versus tested samples

The top-level state guard accepts **273.16–600 K** and **100 Pa–2 MPa**. Each present component's property limits, the package limits, phase feasibility and viscosity limits can narrow this range. These outer guards are not an advertised rectangle in which every composition must work.

Water vapor has an additional partial-pressure ceiling:

| Temperature | Maximum water partial pressure |
|---|---:|
| Below 400 K | 125 kPa |
| 400 K to below 450 K | 150 kPa |
| 450 K to below 500 K | 250 kPa |
| 500 K to below 600 K | 400 kPa |
| 600 K | 800 kPa |

The helper contains higher-temperature entries, but the top-level 600 K guard prevents using them. Nitrogen-only states can use their own lower temperature range; the tested crude grid starts at 298.15 K.

### Existing 21-component wet-crude grid

`FluidPropertyCoverageTest` writes `build/reports/fluid/M1-wet-grid.json`:

- Three packages: TJL20 methane, WTI light export TJL20, Cold Lake blend TJL20.
- Each package's bundled assay, with 0.2 mol water per mole of crude feed.
- Temperatures: 298.15, 300, 325, 350, 375 K.
- Pressures: 50, 101.325, 500, 1,000, 2,000 kPa.
- **75 sampled evaluations pass** in the 771-test checkpoint. This grid has no nitrogen.

### Canonical gameplay basis

The new `gameplayBasisWithNitrogenHasFinitePropertiesAtGridAndInteriorSamples` test uses all 22 components, adding 0.1 mol nitrogen and 0.2 mol water per mole of crude. It checks the same 25 grid points plus 16 cell-center samples per package (arithmetic temperature midpoint and geometric pressure midpoint): **123 evaluations**. It records phase fractions, density, phase viscosities, conditional-solute use and component closure in `M1-network-property-grid.json`.

**All 123 evaluations pass**, with component closure and finite phase properties (`build/fluid-reference-qualification.log`). These samples establish operability and closure, not independent density/viscosity accuracy or adaptive coverage of every phase boundary.

## Independent references and retained failures

The raw NIST liquid tables and provenance are under `src/test/resources/fluid/reference/`. `LiquidCompressionQualificationTest` retains the original translated-PR audit at 300 K, with calibration at 1 MPa and independent checks at 0.9/1.1 MPa:

| Material | Native translated-PR compressibility error | Largest calibrated density error over the three points |
|---|---:|---:|
| n-butane | 37.6575% | 0.01155% |
| n-pentane | 28.3732% | 0.00607% |
| n-decane | 10.4943% | 0.001185% |

The first two compression results did not meet the original 20% material-specific gate. The user then selected a shared liquid compressibility. These failures remain in `M1-liquid-compression.json`; the shared response does not retroactively make native PR compression accurate. `GlobalLiquidResponseTest` verifies the shared derivative, positive volume and energy/pressure identities.

Steam screening compares sampled dilute-vapor volume and compression against independent references at a 2% bound, and viscosity at the test's 5% bound. The unsupported 385 K / 150 kPa point remains recorded as rejected. Twelve accepted compression samples are tested. These sparse screens do not replace continuous-domain qualification.

## Hydraulic reference coverage

`examples/Generate-Hydraulic-References.py` creates a checked-in 60-digit decimal table without importing or executing production Java. The chosen explicit turbulent relation is documented in [EPA EPANET 2.2, analysis algorithms](https://usepa.github.io/EPANET2.2/12_analysis_algorithms.html). The table also records independently bisected implicit Colebrook factors as correlation comparisons. Differences between the two empirical correlations must not be substituted for the strict REF numerical tolerance.

`HydraulicReferenceQualificationTest` adds:

- Fixed-pressure water/nitrogen boundaries, both directions, 28 Reynolds/roughness combinations spanning all three regimes, below the velocity cap.
- Separate length, diameter, roughness and fitting changes, including zero and near-zero flow.
- One-sided value/derivative continuity at Reynolds 2,000 and 4,000 and positive differential resistance through the transition.
- Two finite dilute-nitrogen vessels compared with analytic small-signal Poiseuille relaxation, reversed initial pressures, component and energy closure.

**All four reference tests pass** (`build/fluid-reference-qualification.log`). All 112 fixed-boundary cases pass the specified REF tolerance; the largest relative flow error is `2.182e-11`. The 25 geometry cases and 12 one-sided transition cases pass. Both finite-vessel directions pass REF/BAL; the largest absolute average-flow discrepancy against the ideal-gas limiting reference is `3.880e-11 kg/s`, and the largest pressure-difference discrepancy is `1.099e-4 Pa`. These finite-vessel values include the difference between the real EOS and its dilute ideal-gas limit.

Raw records: `M2-turbulent-reference.json` (all three regimes), `M2-geometry-reference.json`, `M2-transition-reference.json`, and `M2-laminar-compliance-reference.json`, under `build/reports/fluid/`. The project uses its own smooth transition blend; the tests do not claim equivalence to EPANET's transition interpolation. Multiphase homogeneous transport needs its separate existing and remaining phase-trajectory qualification.

## Remaining qualification work

1. Retain the new 22-component and hydraulic reports with the final qualification artifact.
2. Extend independent property checks to the declared material/domain combinations and refine sampling around phase boundaries. Separate empirical approximations from numerical error.
3. Complete both directions of the required phase-transition trajectories against independently refined references.
4. Live property-reload invalidation is now covered by the conservative hold/restore policy (P47). Metadata-only reloads continue; changed physics/transport invalidates old jobs and fallback eligibility without changing stock, energy, debt or grace. Restoring qualified data resumes full calculations. Arbitrary new EOS/data adoption still requires explicit qualification/migration; it is not automatic.
5. Keep property microbenchmarks separate from paced network latency. The old `M1-property-cost.json` is historical and does not describe current UV recovery capability.

See `FLUID_NETWORK_ACCEPTANCE.md` for the full P01–P52 matrix and performance gates.

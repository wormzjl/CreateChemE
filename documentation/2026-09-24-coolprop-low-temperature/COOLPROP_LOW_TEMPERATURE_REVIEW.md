# CoolProp low-temperature coverage review

Status: Concluded 2026-09-24 (runtime metadata survey).

Queried CoolProp 8.0.0, revision ae81610e7d23efc57f9d051c8e70a4d66e87537f, using the HEOS backend. A temporary Python installation was used; no product data or sources changed.

For each fluid, evaluated AbstractState('HEOS', fluid).Tmin(), .Ttriple(), and .Tmax(). Values below are advertised model bounds, not normal boiling points, and not proof that every pressure or phase is valid throughout the interval. Some gas evaluations below Tmin can be possible; they need separate validation against the underlying EOS literature. Liquid validity requires a pressure-dependent melting check. Helium Tmin is associated with the lambda transition, not an ordinary solid-liquid-gas triple point. Air is a predefined pseudo-pure model; its bounds do not establish mixture validity for arbitrary compositions.

| Fluid | Tmin K | Tmin degC | Tmax K |
|---|---:|---:|---:|
| Air | 59.75000 | -213.40000 | 2000.00 |
| Nitrogen | 63.15100 | -209.99900 | 2000.00 |
| Oxygen | 54.36100 | -218.78900 | 2000.00 |
| Argon | 83.80600 | -189.34400 | 2000.00 |
| CarbonDioxide | 216.59200 | -56.55800 | 2000.00 |
| Water | 273.16000 | 0.01000 | 2000.00 |
| Neon | 24.56000 | -248.59000 | 725.00 |
| Helium | 2.17680 | -270.97320 | 2000.00 |
| Krypton | 115.77000 | -157.38000 | 750.00 |
| Xenon | 161.40000 | -111.75000 | 750.00 |
| Hydrogen | 13.95700 | -259.19300 | 1000.00 |
| Ammonia | 195.49500 | -77.65500 | 725.00 |
| Methane | 90.69410 | -182.45590 | 625.00 |
| Ethane | 90.36800 | -182.78200 | 675.00 |
| Propane | 85.52500 | -187.62500 | 650.00 |
| n-Butane | 134.89500 | -138.25500 | 575.00 |
| IsoButane | 113.73000 | -159.42000 | 575.00 |
| n-Pentane | 143.47000 | -129.68000 | 650.00 |
| Isopentane | 112.65000 | -160.50000 | 500.00 |
| n-Hexane | 177.83000 | -95.32000 | 600.00 |
| n-Heptane | 182.55000 | -90.60000 | 600.00 |
| n-Octane | 216.37000 | -56.78000 | 730.00 |
| n-Nonane | 219.70000 | -53.45000 | 600.00 |
| n-Decane | 243.50000 | -29.65000 | 675.00 |
| Ethylene | 103.98900 | -169.16100 | 450.00 |
| Propylene | 87.95300 | -185.19700 | 575.00 |
| Benzene | 278.67400 | 5.52400 | 725.00 |
| Toluene | 178.00000 | -95.15000 | 700.00 |

Ttriple() equalled Tmin() for these runtime queries; this is API metadata, not a claim that every value is a physical triple point.

Thermodynamic outputs include density, heat capacities, enthalpy, entropy, saturation pressure, and vaporization enthalpy derived from the vapor-liquid enthalpy difference. Transport models have their own validity limits. Solid-phase thermodynamics need separate sources. Align reference states before combining enthalpy/entropy with project data; raw fluid enthalpy is not formation enthalpy.

Sources:
- https://coolprop.org/coolprop/HighLevelAPI.html
- https://coolprop.org/fluid_properties/PurePseudoPure.html
- https://coolprop.org/fluid_properties/fluids/Air.html
- https://coolprop.org/fluid_properties/fluids/Ammonia.html
- https://github.com/CoolProp/CoolProp/tree/ae81610e7d23efc57f9d051c8e70a4d66e87537f/dev/fluids

No state-grid extraction or material-data integration requested in the clarified scope. No Gradle checks or detached tooling needed.

## Freezing-point follow-up (2026-09-24)

For pure substances, equilibrium freezing temperature is the solid-liquid melting temperature at the chosen pressure. Query has_melting_line(), check melting_line(iP_min, iT, 0) and melting_line(iP_max, iT, 0), then evaluate melting_line(iT, iP, pressure_Pa). Do not substitute Tmin or Ttriple for a general pressure-dependent freezing point.

The table uses 101325 Pa only as a reference pressure. Values are model evaluations, not experimental accuracy claims. Air is a pseudo-pure approximation and does not establish the onset of freezing for an arbitrary air mixture.

| Fluid | Melting curve | Result at 101325 Pa |
|---|---|---|
| Air | Yes | 59.767163 K (-213.382837 degC) |
| Nitrogen | Yes | 63.170549 K (-209.979451 degC) |
| Oxygen | Yes | 54.370687 K (-218.779313 degC) |
| Argon | Yes | 83.813939 K (-189.336061 degC) |
| CarbonDioxide | Yes | Outside stated curve range (517950 to 8.22736e+08 Pa); no accepted value |
| Water | Yes | 273.152519 K (0.002519 degC) |
| Neon | Yes | 24.565190 K (-248.584810 degC) |
| Helium | Yes | Outside stated curve range (2.21436e+06 to 4.55494e+10 Pa); no accepted value |
| Krypton | Yes | Outside stated curve range (150094 to 2.04912e+08 Pa); no accepted value |
| Xenon | No | No curve; external source needed |
| Hydrogen | Yes | Outside stated curve range (2.36062e+07 to 2.39143e+10 Pa); no accepted value |
| Ammonia | No | No curve; external source needed |
| Methane | Yes | 90.717113 K (-182.432887 degC) |
| Ethane | Yes | 90.384322 K (-182.765678 degC) |
| Propane | Yes | 85.534407 K (-187.615593 degC) |
| n-Butane | Yes | 134.911986 K (-138.238014 degC) |
| IsoButane | Yes | 113.773967 K (-159.376033 degC) |
| n-Pentane | Yes | 143.483357 K (-129.666643 degC) |
| Isopentane | Yes | Outside stated curve range (1.23336e+06 to 1.003e+09 Pa); no accepted value |
| n-Hexane | No | No curve; external source needed |
| n-Heptane | No | No curve; external source needed |
| n-Octane | No | No curve; external source needed |
| n-Nonane | No | No curve; external source needed |
| n-Decane | No | No curve; external source needed |
| Ethylene | Yes | 104.003237 K (-169.146763 degC) |
| Propylene | Yes | 87.962884 K (-185.187116 degC) |
| Benzene | No | No curve; external source needed |
| Toluene | No | No curve; external source needed |

An initial unguarded probe returned finite out-of-range values for helium, hydrogen, krypton and isopentane. Those values were rejected; the guarded table above is the accepted result. CO2 rejected the out-of-range call itself. A successful API call alone does not establish validity.

For missing curves, use measured fusion temperatures with their reference pressure and uncertainty from NIST/TRC or the original experimental paper. For pressure dependence, obtain a published melting correlation; the Clapeyron relation dT/dP = T(V_liquid - V_solid)/DeltaH_fusion requires additional solid data. Mixtures need solid-liquid equilibrium data/models, not averages of pure-component freezing temperatures.

Additional sources:
- https://coolprop.org/apidoc/CoolProp.CoolProp.html
- https://coolprop.org/_static/doxygen/html/_ancillaries_8cpp_source.html
- https://webbook.nist.gov/cgi/cbook.cgi?ID=C7664417&Mask=4 (ammonia fusion-data entry; assess original reference and uncertainty before adoption)

No product changes, tests or detached tooling.

# Methane feed qualification with the current initializer

The fresh V3 calculator now uses `methaneCduInput()`: a synthetic methane blend at the original
40-tray column conditions. Methane is **0.5 mol% of the hydrocarbon feed**, or **3.6884981665 mol/s**;
the total remains **737.6996333001 mol/s**. The original 19 hydrocarbons retain their relative
proportions and each contributes 99.5% of its original molar flow. Steam is separate from this basis.
Feed temperature, pressures, stage count, feed location, reflux, condenser temperature, side draws,
steam and pumparound duties are unchanged. This addition is an authored test blend, not a revised
measurement of the original literature crude.

## Properties and provenance

The registered package is `createcheme:tjl20_methane`, revision `tjl20-methane-nist-r1`, assay
`createcheme:tia_juana_light_methane`. Its public axis is Methane followed by the unchanged TJL19
axis. The old package ID, revision, component values, interactions and assay remain available for
saved inputs and reproduction of the 19-component neural pilot.

- Methane critical temperature **190.564 K**, critical pressure **4,599,200 Pa**, acentric factor
  **0.01142**, molecular weight **0.0160428 kg/mol**: [CoolProp methane data, Setzmann-Wagner source](https://raw.githubusercontent.com/CoolProp/CoolProp/master/dev/fluids/Methane.json),
  inspected 2026-09-10. Only these constants enter the existing PR78 model; this does not import
  the CoolProp equation of state or its validity claims.
- Normal boiling point **111.66 K** retains the existing CDU methane value and agrees with the
  [NIST methane phase-change compilation](https://webbook.nist.gov/cgi/cbook.cgi?ID=C74828&Mask=4).
- Ideal-gas heat capacity uses a degree-five fit in `T - 298.15` to the
  [NIST Shomate correlation](https://webbook.nist.gov/cgi/cbook.cgi?ID=C74828&Mask=1&Type=JANAFG&Table=on).
  Enthalpy is its analytic integral with zero at 298.15 K, consistent with the existing package datum.
  `fit_methane.py` reproduces the fit using 1,201 uniform points over 298.15..900 K. On 10,001 check
  points the maximum Cp error is **0.04270 J/(mol K)** and the maximum enthalpy error is
  **0.44686 J/mol**. Tests compare against the original rational correlation and verify `dH/dT = Cp`.
- Methane binary interactions are explicitly **assumed zero**, including the petroleum fractions;
  these are not fitted methane/crude VLE data. All existing TJL19 interactions are preserved.
- The required density metadata retains the older CDU methane hypothetical-liquid convention,
  **356.07 kg/m3**. Methane has no physical pure liquid at the 60 F reporting temperature; this
  metadata is unused by this molar assay, stream mass calculation, and PR equilibrium/enthalpy code.
  It must not be treated as a measured ambient liquid density.

## Qualification result

`V3MethaneColumnTest` calls the explicit `CURRENT_ONLY` strategy with a model whose methods throw
if accessed. It starts from the requested input with no stored profile. The existing cold
stage-continuation path (`4-8-15-30-40`) converges without solver changes or relaxed tolerances.

- Final maximum scaled residual: **7.6328e-14**.
- All existing acceptance and final convergence gates pass; methane product balance closes within
  **1e-7 mol/s** in the regression.
- Overhead vapor: **27.72525 mol/s**, **12.3594 mol% methane**.
- Distillate liquid: **177.59751 mol/s**, **0.14303 mol% methane**.
- One local qualification run took approximately **4.55 s**; this is not a performance benchmark.
- `LNN_FIRST` also converges through `CURRENT_BACKUP`. The bundled 19-component model declines this
  package. No methane labels have been used for neural training.

The existing **water-dew-point advisory remains**: tray 1 is about 87.2 C versus an 88.1 C water
dew point, saturation ratio 1.03419, with no admitted free-water tray. Passing the current solver's
gates does not qualify a fully equilibrated aqueous phase. Methane binary interactions and wet-tray
physics remain limitations for later qualification.

## Reproduction and saved inputs

```powershell
python tools/neural/fit_methane.py
.\gradlew.bat test --tests '*V3Tjl20MethanePropertyPackageTest' --tests '*V3MethaneColumnTest' --offline
```

The column test writes the exact input, complete outcome, and local elapsed time to
`build/methane-qualification/`. The committed `methane-qualification.json` is a compact record of
the observed run. Timing includes the requested solve and evidence serialization, not Gradle startup.

Newly placed calculators use the methane preset. Existing saved column inputs keep their recorded
package and composition; place a new calculator to start with the methane feed. The unchanged
`literatureCduInput()` remains the original reference and the bundled model's test fixture.
The saved-state schema is unchanged because its component axis and flow arrays already support
20 components. Broader model work is deferred until after this qualification.

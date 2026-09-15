# Material data packs

Production material values are JSON resources. Java implements equations and validation, not component tables.
The existing CDU17, TJL19, methane-extended TJL20, and water data are preserved. PR78 is the available equilibrium
solver. NRTL parameter records can be loaded and validated; selecting an NRTL package for calculation reports
`Thermodynamic solver unavailable: nrtl`.

## Installing and editing

Copy `examples/material-override` into a world's `datapacks` directory, enable it with `/datapack enable`, and run
`/reload`. The example replaces methane's hypothetical standard-liquid density with an illustrative value;
it is an editing example, not a measured reference dataset. All other fields are copied explicitly.

Records live under `data/<namespace>/materials/<kind>/<filename>.json`. Minecraft chooses the highest-priority
resource at each path. A replacement is a **whole record**, not a field merge. Different resource paths declaring
the same ID within one kind are errors. Assay IDs are unique within a package, so both historical crude packages
can continue using `createcheme:tia_juana_light`.

Every record requires integer `schema_version: 1`. File/resource identifiers follow Minecraft's lowercase
resource-location rules. Component IDs are separate case-sensitive identifiers, up to 64 characters matching
`[A-Za-z][A-Za-z0-9_.:-]*`. Exact chemicals use plain names, for example `Ethane`, `N-butane`, and `Water`.
Resource IDs for datasets, packages, interaction sets, and water models are normally namespaced.

## Record schema and units

The bundled files are complete examples of all production record kinds. Numeric data is finite and expressed in
SI units. There are no user-defined executable expressions. A new equation form requires a registered Java
implementation and corresponding validation; arbitrary equation-type strings fail loading.

### `components`

Required: `id`, `kind`, `translation_key`, `fallback`. Kinds are `chemical`, `petroleum_fraction`, and `lump`.
For a chemical, the English fallback must exactly equal its internal ID. Lumps such as `cdu17_c4` have independent
descriptive labels. Component identity does not imply that every source dataset has identical property estimates.

Petroleum fractions additionally require `cut`:

```json
{
  "estimated": true,
  "derivation": "adjacent_nbp_midpoints",
  "series": "tjl19",
  "lower_kelvin": 602.2990231445796,
  "upper_kelvin": 651.0249814474507
}
```

At least one bound is required. Omit the lower bound for the first open-ended cut, or the upper bound for the last.
For the migrated data, internal boundaries are midpoints of adjacent representative NBPs **within the same
characterization series**. Exact light-end chemicals are not used as neighboring cuts. These are estimated
labels, not recovered assay boundaries. Sourced boundaries should use `estimated: false` and identify their source
in `derivation`. Bounds affect presentation only; they never replace the model's representative NBP.

### `properties`

Required identity/provenance fields: `id`, `component`, `revision`, `source`, `estimated_heavy_residue`.
Physical fields:

| Field | Unit |
|---|---|
| `molecular_weight_kg_per_mol` | kg/mol |
| `normal_boiling_point_kelvin` | K |
| `standard_liquid_density_kg_per_m3` | kg/m³ |
| `standard_temperature_kelvin` | K, density reference |
| `standard_pressure_pascal` | Pa, density reference |
| `temperature_min_kelvin`, `temperature_max_kelvin` | K, valid calculation interval |

`ideal_gas_cp` requires `type: "shifted_polynomial_5"`, `reference_kelvin: 298.15`, and exactly six
`coefficients` `[A,B,C,D,E,F]`. With ΔT = T − 298.15 K:

`Cp = A + B ΔT + C ΔT² + D ΔT³ + E ΔT⁴ + F ΔT⁵`, in J/(mol·K).

Coefficient n has units J/(mol·K^(n+1)). Ideal-gas enthalpy is its analytic integral from 298.15 K, in J/mol,
with zero enthalpy at that datum. Existing cubic fits retain their original evaluation order when E and F are zero.

`models` may contain `pr78`, with `critical_temperature_kelvin`, `critical_pressure_pascal`, and dimensionless
`acentric_factor`. A PR78 package requires those fields for each selected property dataset. NRTL is a mixture
activity model: its binary parameters belong in an interaction set, not in this pure-component record.

Separate datasets may reference the same component. For example, CDU17 and TJL19 preserve their slightly different
Ethane properties rather than silently replacing one reconstruction with the other. TJL20 references TJL19's
existing property records and adds its own Methane record.

### `interactions`

Required: `id`, `model`, `source`, `pairs`. Each pair names `first` and `second`. Self-pairs and duplicate unordered
pairs are errors. A PR78 pair supplies dimensionless `kij`; the evaluator uses the same value in both directions.

An NRTL pair supplies all of:

```json
{
  "first": "Ethane", "second": "Propane",
  "a12": 1.0, "b12_kelvin": 100.0,
  "a21": 2.0, "b21_kelvin": 200.0,
  "alpha": 0.3,
  "temperature_min_kelvin": 298.15,
  "temperature_max_kelvin": 900.0
}
```

These numbers are a manufactured schema example, not fitted chemical data. The convention is dimensionless
τ12 = a12 + b12/T and τ21 = a21 + b21/T, with T and b in kelvin. `alpha` is constant, dimensionless, symmetric,
and must be in [0,1]. Direction is determined by the declared `first` and `second`, never lexical sorting.
Package temperature limits must fit within every selected pair's validity interval. Missing coefficients are errors.

### `packages`

Required: `id`, `revision`, `model`, ordered `components`, parallel ordered `properties` references, `interactions`,
`missing_interactions`, `water_model`, `aliases`, `advisory_evidence`, `temperature_min_kelvin`,
`temperature_max_kelvin`, `pressure_min_pascal`, and `pressure_max_pascal`. There must be 1–64 unique components.
Each property record must describe the component at the same index. Package ranges must fit selected property ranges.

`missing_interactions` is explicitly `zero` or `error`. The migrated PR packages use `zero`, preserving their original
assumptions. NRTL requires `error` and a complete pair set; absent pair data never means ideal behavior.
`aliases` maps historical component IDs to current IDs within this package. Multiple old spellings can refer to one
identity, but an input containing both spellings is ambiguous and rejected. An alias cannot remap a different current ID.

### `assays`

Required: `id`, `package`, ordered `components`, `basis`, and parallel nonnegative `amounts` with a finite positive
sum. Assay order must exactly match its package. `basis` is `mole`, `mass`, or `standard_liquid_volume`.

- Mole amounts are normalized directly.
- Mass amounts are divided by selected molecular weights, then normalized.
- Standard-liquid-volume amounts are multiplied by selected densities and divided by molecular weights, then normalized.

Volume assays also require `standard_temperature_kelvin` and `standard_pressure_pascal`, matching the selected
density reference conditions. Optional positive `volume_scale` and `amount_total` default to 1; volume is evaluated
as `volume_scale * amount / amount_total`. CDU17 retains its historical scale and arithmetic exactly. TJL19 retains
its original molar amounts, and TJL20 retains the synthetic 0.5 mol% methane blend.

### `water`

The registered type is `iapws_shomate_watson`. Required: `id`, `revision`, `source`, `type`, `molar_mass` (kg/mol),
`triple_point`, `critical_temperature`, `max_enthalpy_temperature`, `reference_boiling_temperature` (K),
`critical_pressure` (Pa), `reference_vaporization_enthalpy` (J/mol), positive dimensionless `watson_exponent`,
six dimensionless `saturation_coefficients`, and seven `shomate_coefficients`.

Saturation coefficient order corresponds to reduced-temperature exponents [1, 1.5, 3, 3.5, 4, 7.5] in the
Wagner–Pruss auxiliary equation. Shomate order is [A,B,C,D,E,F,−H] using t = T/1000: vapor enthalpy is
1000 × (At + Bt²/2 + Ct³/3 + Dt⁴/4 − E/t + F − H) J/mol. The final array entry already contains −H.
The existing NIST fit's lower-temperature continuation and reference-consistent Watson liquid enthalpy are preserved.

## Viscosity

Property datasets and water-model records may contain an optional `viscosity` object, keyed by `liquid` and/or
`vapor`. Each entry is a pure-phase dynamic-viscosity correlation. Missing entries mean unavailable, never zero;
no viscosity mixing rule or crude-fraction estimation is applied automatically.

Required fields per entry are `type`, `temperature_min_kelvin`, `temperature_max_kelvin`, `pressure_min_pascal`,
`pressure_max_pascal`, `reference_temperature_kelvin`, `coefficients`, `revision`, `source`, and `estimated`.
Temperature limits are inclusive and increasing. Pressure limits are inclusive and may be equal for an isobar.
Queries outside the limits throw an explicit domain error. Pressure is a validity constraint, not a correction term.
The caller chooses the phase; the correlation does not perform a flash or decide phase stability.

Supported equations, using T in kelvin and dynamic viscosity μ in Pa·s:

| `type` | Phase | Equation and coefficient units |
|---|---|---|
| `andrade` | liquid | μ = A exp(B/T); coefficients [A (Pa·s), B (K)] |
| `sutherland` | vapor | μ = μref (T/Tref)^1.5 (Tref + S)/(T + S); [μref (Pa·s), S (K)], S ≥ 0 |
| `power_sum` | either | μ = Σ ai (T/Tref)^bi; positive `coefficients` ai in Pa·s and matching dimensionless `exponents` bi |
| `log_table` | either | Linear interpolation of ln(μ) in T between `temperatures_kelvin`; parallel `coefficients` are μ in Pa·s |

Andrade/Sutherland/table entries omit `exponents`. Power sums allow 1–16 terms. Tables allow 2–2048 strictly
increasing temperature nodes, spanning exactly the declared temperature interval; node values are returned exactly.
`reference_temperature_kelvin` is metadata only for tables. Coefficients, domains, sources, and
estimation flags remain in JSON. Fits are checked for finite positive scales and endpoint values at load time,
and every evaluation must return a finite positive value. No extrapolation is performed.

The bundled water model includes the [IAPWS SR6-08(2011) liquid-water correlation](https://iapws.org/technical-guidance/release/LiquidWater.download),
equation 7/table 5, with coefficients converted from μPa·s to Pa·s. It is restricted to its 0.1 MPa reference isobar
and 253.15–383.15 K. This interval includes metastable liquid below freezing and above boiling; it is not a vapor
correlation. The verification tests use independent table 8 values at 260, 298.15, and 375 K.
The current methane/TJL feed's 20 components have both liquid and vapor curves exported through the installed
DWSIM 10.2.5.0 API. They use the original `tjl-ledezma2019-dwsim1023-13pc-r1` characterization and native chemical
data (including ChemSep correlations). The 13 petroleum fractions retain their native viscosity reference values
and are flagged `estimated: true`. There are 46 curves across 23 property records, including the older CDU package's
three matching exact chemicals. Its C4 lump and 12 differently characterized fractions have no matching source
and remain unavailable. No new viscosity estimates were fabricated for those unmatched materials.

Liquid curves sample `AUX_LIQVISCi(name,T,100000 Pa)`; vapor curves sample the dilute-gas auxiliary
`AUX_VAPVISCi(component,T)`. Both are restricted to the 0.1 MPa isobar in the catalog. These are conditional phase
properties, not phase-stability predictions; a specified liquid or vapor can be metastable at the query state.
Exact-chemical liquid sampling starts at max(fusion temperature + 1 K, 0.5 Tc); petroleum liquids start at 298.15 K.
Liquid upper bounds are min(900 K, 0.95 Tc), avoiding unphysical API extrapolation beyond the critical point.
Gas sampling intersects the available API correlation interval with 298.15–900 K (the latter interval is also used
for petroleum gas estimates without database bounds). These are sampling limits, not independently qualified
physical validity ranges. Heavy-residue estimates retain the source characterization's limitations.

Adaptive subdivision checks quarter, midpoint, and three-quarter temperatures until log interpolation agrees with
the API within 0.05% at those check points. This measures interpolation error only, not physical model accuracy.
The independent API values and characterization metadata are committed in `src/test/resources/materials/dwsim-viscosity-api.json`.
Reproduce with `examples/Export-DwsimViscosity.ps1 -Characterization <original-source-feed.dwxml>`, then
`python examples/import_dwsim_viscosity.py build/dwsim-viscosity-export.json`. The original characterization file
is an offline input; neither it nor the DWSIM runtime is required by Minecraft. The exporter uses isolated in-memory
flowsheets and never saves the supplied source simulation.

Use `catalog.viscosity(packageId, componentId, Phase.LIQUID)` to retrieve an `Optional<ViscosityCorrelation>`;
package aliases and the package's selected water model are respected. `MaterialRuntime.viscosity(...)` uses the
calling calculation's pinned catalog. Call `dynamicViscosityPascalSeconds(T, P)` to evaluate μ.
`kinematicViscositySquareMetresPerSecond(T, P, density)` returns ν = μ/ρ in m²/s and requires the density at the
same state; the standard-liquid density is never substituted. For display, 1 mPa·s = 10⁻³ Pa·s and 1 cSt = 10⁻⁶ m²/s.

Viscosity is a transport property and is not consumed by the current equilibrium/enthalpy solver. A separate
`catalog.viscosityFingerprint(packageId)` tracks selected viscosity parameters, phase assignments, limits, and
estimation flags; transport caches must use it. Viscosity-only edits preserve PR scientific revisions and neural
eligibility, while reloads still validate and atomically publish the complete catalog. Source-text-only edits do
not change the transport fingerprint. Mixture viscosity, pressure corrections, and stream UI reporting are not provided.

## Fluid color and transparency

Component and assay records can contain an optional `appearance` object:

```json
"appearance": {
  "color": "#70501D",
  "transparency": 0.12,
  "estimated": true
}
```

`color` is a six-digit RGB hex string (`#RRGGBB`); alpha is specified separately. `transparency` is a finite
dimensionless value from 0 (fully opaque) to 1 (fully transparent). All three fields are required when the
object is present. Missing objects use an estimated, opaque white fallback for backward compatibility.
Whole-assay appearance is independent of component appearance; no mixture-color rule is assumed.

These are liquid visual settings stored for future use. The bundled colors/transparencies are an artistic,
estimated palette, not sourced optical measurements or temperature/path-length-dependent transmittance.
They do not currently affect in-game rendering, calculator UI, or multiplayer payloads. Appearance overrides
reload atomically with the database but do not invalidate thermodynamic results, neural seeds, or viscosity caches.

Use `catalog.componentAppearance(packageId, componentId)` (supports package aliases and Water) or
`catalog.assayAppearance(packageId, assayId)` for immutable values. Use the calling calculation's captured
catalog when snapshot consistency matters. `FluidAppearance.argb()` supplies a packed AARRGGBB value for future
consumers. Put an appearance object in a whole-record override using the usual data-pack replacement rules.
The five crude conversion presets retain their visual settings in `examples/crude-assays/appearances.json`,
separate from scientific source transcriptions, so rerunning the converter preserves them.

## Localization and saved data

Put translations in resource packs at `assets/<namespace>/lang/<locale>.json`. Exact chemicals fall back to their
plain IDs. Petroleum labels compose the component's translated base name with these translatable templates:

- `material.createcheme.nbp_range`: `%s, NBP %s–%s°C`
- `material.createcheme.nbp_below`: `%s, NBP below %s°C`
- `material.createcheme.nbp_above`: `%s, NBP above %s°C`
- `material.createcheme.estimated`: ` (estimated)`

The first argument is the base name; remaining arguments are Celsius bounds displayed to one decimal place.
The server sends bounded naming descriptors, so clients do not need the server's scientific data pack.
Composition cells show localized labels; hovering shows the full label and internal ID. Missing translations use
English fallbacks. Package and assay IDs remain stable. Saved legacy axes are migrated by identity and amounts
are reordered with their components. Legacy saved results remain presentation-only and require recalculation.
The network protocol is now version 7 with wire schema 10; client and server must run compatible mod versions.

## Reloads, scientific identity, and verification

One reload listener resolves and validates the entire winning resource set before atomically publishing it.
Invalid initial catalogs fail world loading; invalid subsequent reloads leave the last valid material snapshot intact.
Background requests capture a snapshot at admission. A scoped, finally-restored calculation context carries it through
legacy static PR/water entry points, including continuation and reporting. No running solve reads a replacement catalog.

Results and neural seeds carry a scientific revision with a SHA-256 fingerprint of resolved data. Changing numerical
data invalidates reuse even if an author forgets to update `revision`. Names, cut-label metadata, and translations do
not change the scientific fingerprint. Completed results preserve their original identity and become stale when it
differs from the live package. Open calculators refresh after publication; closed ones check when reopened.
The bundled neural manifest pins its verified property fingerprint. Changed data disables that model; the normal
LNN_FIRST mode uses numerical fallback (explicit LNN_ONLY retains its existing failure semantics).

`MaterialCatalog.bundled()` uses the same parser with `materials-index.json` for standalone tools/tests. Add new bundled
files to that index; Minecraft itself discovers data-pack resources dynamically and does not use the index. Keep
catalog snapshots immutable and keep all numeric arrays and model workspaces local to their owner.

Run `gradlew.bat test` for numerical parity, migration, codec, concurrency, neural, and solver regressions.
Run `gradlew.bat -I examples/material-gametest.gradle runGameTestServer` for an actual Minecraft reload test in
`build/material-gametest-server`. It applies a higher-priority override, rejects an invalid replacement without
publishing it, and restores the original selection. The three former hardcoded package classes are test-only
fixtures. Their independent numeric tables and the archived neural seed oracle remain regression references.

## Five additional crude feeds

WTI Light - Export, Upper Zakum, Bonga, Dalia, and Cold Lake Blend have separate packages and mass-basis assays
on the existing TJL20 component/property basis. See [CRUDE_ASSAY_CONVERSION.md](CRUDE_ASSAY_CONVERSION.md)
for mole/mass tables, source links, the reproducible converter, and limitations. These are approximate feed mappings;
they do not fit new crude-specific physical or viscosity properties. Each unmeasured tail above 590°C is marked
estimated and constrained by that crude's published residue mean boiling point. Whole-crude nitrogen, sulfur,
and metals remain provenance metadata, without contaminant speciation. The original TJL feeds are unchanged.

In the column calculator, open **Inputs → Input presets** and choose a crude. Loading replaces the entire draft
and clears its previous result; it does not start a solve. The five new crude inputs use the current Tia Juana
column's flow rate, 40 trays, feed tray, temperature, pressure, steam, product draws, and pumparound duties as
editable starting conditions. These conditions are not separately fitted or qualified for each crude, so users
may need to adjust them. The original default Tia Juana input, legacy pilot, and Holland benchmark remain selectable.
Preset requests contain only an allowlisted ID and expected input revision; the server resolves its own catalog
composition, rejects stale/busy changes, and persists the selected package, assay, and full input. No new fluid
rendering or appearance synchronization is introduced by this selector.

Additional transport properties, fitted NRTL datasets/calculations, and an in-game editor are outside this release.

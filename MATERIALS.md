# Material data packs

Production material values are JSON resources. Java implements equations and validation, not component tables.
The active crude family has 12 pseudocomponents (`crude_pc01`–`crude_pc12`): 19 hydrocarbons with methane, 18 without, and 20 non-water species in the nitrogen network extension. CDU17 is a test-only reference. Exact chemicals and water retain their data. PR78 is the available equilibrium
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
For a chemical, the English fallback must exactly equal its internal ID. Ordinary lumps have independent
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
For PC01–PC07, internal boundaries retain the original adjacent-NBP midpoints. PC08–PC12 use the estimated 377.8749814474507/450/550/650/750°C grid with an open upper tail, derived by constrained mass-CDF reconstruction. Exact light-end chemicals are not used as neighboring cuts. These are estimated
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

Separate datasets may reference the same component. The bundled crude packages share their selected property records; the methane-free package omits Methane and the network package adds Nitrogen.

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
as `volume_scale * amount / amount_total`. The regrouped Tia Juana assay conserves the original mass, standard liquid volume and ideal caloric moments; its pseudomole total changes. The methane assay retains the synthetic 0.5 mol% convention.

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
Exact-chemical transport retains the installed DWSIM API curves and independent check points. The 12 active petroleum cuts use the shared Dalia liquid family described below. The five regrouped heavy vapor curves were re-evaluated through `AUX_VAPVISCi` with their reconstructed properties; all are estimated. The retired CDU17 data is available only to independent tests.

Liquid curves sample `AUX_LIQVISCi(name,T,100000 Pa)`; vapor curves sample the dilute-gas auxiliary
`AUX_VAPVISCi(component,T)`. Both are restricted to the 0.1 MPa isobar in the catalog. These are conditional phase
properties, not phase-stability predictions; a specified liquid or vapor can be metastable at the query state.
Exact-chemical liquid sampling starts at max(fusion temperature + 1 K, 0.5 Tc); petroleum liquids start at 298.15 K.
Liquid upper bounds are min(900 K, 0.95 Tc), avoiding unphysical API extrapolation beyond the critical point.
The original gas sampling intersects the available API correlation interval with 298.15–900 K (the latter interval
is also used for petroleum gas estimates without database bounds). These are sampling limits, not independently
qualified physical validity ranges. Heavy-residue estimates retain the source characterization's limitations.

An additional DWSIM API export now prepends 293.15–298.15 K to the applicable liquid and vapor tables,
preserving every original knot at/above 298.15 K. The fluid dissolved-solute reference factors have the same
ambient extension. Run `Export-DwsimViscosity.ps1 -Ambient -Characterization <source-feed.dwxml>` and the
`Export-DissolvedViscosity.cs` tool with `--ambient`, then `extend_ambient_viscosity.py` with the two reports
(`--dissolved-report` selects the second). Independent ambient checkpoints are included in tests.

Fluid generators preserve temperature/pressure when selecting a composition. The fluid-specific model supports
293.15 K (20°C) for the bundled crude properties through a five-kelvin continuation of their existing
caloric fits; this is a near-ambient approximation, not a new laboratory qualification or a wax/gel model.
Changed property records retain their declared limits. The column property domains remain unchanged; the regrouped PR/Cp values are versioned, while fluid-model and transport revisions invalidate incompatible fluid caches. Ambient generation
is tested at 293.15, 298, 298.15 and 300 K; colder crude operation remains outside this extension.
Old fluid property fingerprints are refused; the previous ambient-extension compatibility exception has been removed.

Adaptive subdivision checks quarter, midpoint, and three-quarter temperatures until log interpolation agrees with
the API within 0.05% at those check points. This measures interpolation error only, not physical model accuracy.
The independent API values and characterization metadata are committed in `src/test/resources/materials/dwsim-viscosity-api.json`.
The following regeneration tools and source reports are local research under the Git-ignored `research/` directory;
they are not required to build or test a clean checkout.
Reproduce with `research/viscosity-tools/Export-DwsimViscosity.ps1 -Characterization <original-source-feed.dwxml>`, then
`python research/viscosity-tools/import_dwsim_viscosity.py research/viscosity-tools/raw/dwsim-viscosity-export.json`. The original characterization file
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
The five crude conversion presets retain their visual settings in `research/crude-assays/appearances.json`,
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
English fallbacks. Package and assay IDs remain stable. Retired saved axes are rejected. Current-format saved results remain presentation-only and require recalculation.
The column protocol is version 8 (wire schema 11), and the fluid protocol is `fluid-2`; client and server must run compatible mod versions.

## Reloads, scientific identity, and verification

One reload listener resolves and validates the entire winning resource set before atomically publishing it.
Invalid initial catalogs fail world loading; invalid subsequent reloads leave the last valid material snapshot intact.
Background requests capture a snapshot at admission. A scoped, finally-restored calculation context carries it through
legacy static PR/water entry points, including continuation and reporting. No running solve reads a replacement catalog.

Results and neural seeds carry a scientific revision with a SHA-256 fingerprint of resolved data. Changing numerical
data invalidates reuse even if an author forgets to update `revision`. Names, cut-label metadata, and translations do
not change the scientific fingerprint. Completed results preserve their original identity and become stale when it
differs from the live package. Open calculators refresh after publication; closed ones check when reopened.
The bundled neural registry pins its verified ordered physics fingerprint. Changed physical data disables that model; the normal
LNN_FIRST mode uses numerical fallback (explicit LNN_ONLY retains its existing failure semantics).

`MaterialCatalog.bundled()` uses the same parser with `materials-index.json` for standalone tools/tests. Add new bundled
files to that index; Minecraft itself discovers data-pack resources dynamically and does not use the index. Keep
catalog snapshots immutable and keep all numeric arrays and model workspaces local to their owner.

Run `gradlew.bat test fluidScienceTest fluidRuntimeTest` for numerical parity, codec, concurrency, neural, and solver regressions.
Run `gradlew.bat runFluidGameTestServer -PfluidGameTestRunId=<fresh-id>` for actual Minecraft fluid and reload tests, and
`gradlew.bat runColumnGameTestServer -PcolumnGameTestRunId=<fresh-id>` separately for calculator service tests. It applies a higher-priority override, rejects an invalid replacement without
publishing it, and restores the original selection. The three former hardcoded package classes are test-only
fixtures. Their independent numeric tables and the archived neural seed oracle remain regression references.

## Five additional crude feeds

WTI Light - Export, Upper Zakum, Bonga, Dalia, and Cold Lake Blend have separate packages and mass-basis assays
on the existing TJL20 component/property basis. See the local research report `research/crude-regrouping/notes/CRUDE_ASSAY_CONVERSION.md`
for mole/mass tables, source links, the reproducible converter, and limitations. These are approximate feed mappings;
they do not fit new crude-specific physical or viscosity properties. Each unmeasured tail above 590°C is marked
estimated and constrained by that crude's published residue mean boiling point. Whole-crude nitrogen, sulfur,
and metals remain provenance metadata, without contaminant speciation. The regrouped TJL feeds preserve physical feed volume; their pseudomole amounts change with characterization.

In the column calculator, open **Inputs → Input presets** and choose a crude. Loading replaces the entire draft
and clears its previous result; it does not start a solve. The five new crude inputs use the current Tia Juana
column's 40 trays, feed tray, temperature, pressure, steam, and pumparound duties as editable starting conditions.
Each crude retains its captured physical feed volume and the qualified draw settings. These are starting points, not
optimized refinery operations. The default Tia Juana input and independent Holland benchmark remain selectable; the old pilot is retired.
Preset requests contain only an allowlisted ID and expected input revision; the server resolves its own catalog
composition, rejects stale/busy changes, and persists the selected package, assay, and full input. No new fluid
rendering or appearance synchronization is introduced by this selector.

Additional transport properties, fitted NRTL datasets/calculations, and an in-game editor are outside this release.


## Regrouped crude transport and development saves

PC01–PC12 use one Dalia source-cut liquid-viscosity family over 293.15–900 K. The three 550°C+ cuts share the same unresolved pure-liquid curve. This is an estimated transport surrogate for every crude, not a claim that their measured viscosity profiles agree. The first three unmeasured source intervals retain light-cut estimates. Liquid tables outside measured temperatures are continuations, not phase-stability predictions. No freezing, wax, gel, elemental or reaction state is introduced.

A property may carry `liquid_mixture_descriptor` with `participation` and `residue_retention` in [0,1], and a finite nonnegative `activation_slope`. Absent descriptors mean zero participation, retention and slope. `activation_slope` is dimensionless: Δln(μ)/(1000 K × Δ(1/T)) between 313.15 and 323.15 K. Retention describes an ideal 370°C+ split. Nonparticipating gases and exact chemicals still dilute participating mole fractions.

Packages may select `liquid_mixture` with `type: symmetric_pair_groups_v1`, four finite `coefficients`, positive `reference_kelvin` and `slope_cap_kelvin >= reference_kelvin`. For normalized amounts x, p=participation and h=retention, let a=xp and b=ah. The correction to Σx ln(μ) is:

```
F(a) = (Σa)² − Σa²
E(a) = (Σa)(Σa activation_slope) − Σ(a² activation_slope)
q = 1000 K × (1/min(T, slope_cap_kelvin) − 1/reference_kelvin)
Δln(μ) = c0(F(a)−F(b)) + c1 F(b) + q[c2(E(a)−E(b)) + c3 E(b)]
```

All pair terms vanish for a pure component. The implementation evaluates factored sums in O(component count). These coefficients and descriptors participate in the transport fingerprint, while scientific PR/caloric fingerprints remain independent. This release fits Dalia whole crude at 20/50°C and 370°C+ residue at 50/100°C, reserving whole 40°C and residue 60°C for verification. Other crudes' discrepancies are accepted and reported as a global approximation.

Column operating values reside in `presets` records. `operating.feedStandardVolumeCubicMetresPerSecond` is the positive feed volume at the selected property density reference; molar throughput is derived from the current assay and molecular weights. `operating.sideDraws` contains explicit tray numbers and mol/s rates. Captured pre-regrouping physical throughputs are retained, and gameplay draws are recalibrated against accepted pre-regrouping volume targets. The hidden literature preset retains its published molar rates. Cold Lake has no accepted old yield targets and retains its authored rates.

This development change breaks the old component basis. Production packages have no old pseudocomponent aliases, saved column data uses version 9, and old fluid fingerprints are refused. Recreate inputs in a fresh development world or explicitly reset obsolete data; no automatic material deletion or migration is provided. Independent old numerical oracles exist only in test scope. Obsolete learned weights are retired; an unavailable qualified model leaves the classical initializer usable.


## Shared bases, sparse assays, and catalog presets

`bases` records contain ordered `components` and selected `properties`. Optional `extends` names one base whose entries precede the new entries. Missing references, cycles, duplicates, invalid unused bases and more than 64 non-water components fail validation. A package selects `basis` or supplies both inline lists; mixing these forms is rejected. Expansion happens before validation and publication. A higher-priority pack still replaces the whole record; extension is explicit composition, not field merging.

An assay can use `amounts_by_component` instead of positional `components`/`amounts`. Missing IDs become zero on the selected package axis. Unknown IDs, including unknown zero entries, fail. Sparse and positional forms cannot coexist. Amounts keep the assay's declared mole, mass or standard-liquid-volume basis and reference conditions. `verifyMaterialIndex` checks bundled index completeness and uniqueness during resource processing; datapacks remain dynamically discovered.

`presets` have `schema_version`, stable `id`, `kind`, and `label`. Column presets additionally declare `translation_key`, integer `order`, boolean `visible`, `package`, `assay`, and an `operating` object. See the bundled `column_tia_juana.json` for the complete SI fields: standard-volume feed rate, feed temperature, stage/feed-stage numbers, top pressure/drop, condenser temperature, reflux ratio, reboiler watts, side draws (mol/s), steam feeds (mol/s, K), and pumparounds (watts and explicit split rule). Numerical definitions and stage geometry are validated before publication. The independent Holland example remains a Java numerical reference. At most 31 catalog column presets plus Holland are displayed, with pagination. Server descriptors include ID, label, translation key and package/assay identity; clients need no local copy of the scientific pack.

Fluid presets select either a pure `component` or a `package` and `assay`. Exactly one optional `networks` record selects the global `package`, `default_column_preset` and ordered `fluid_presets`. Pure column-only standalone catalogs need no network configuration. Gameplay requires PR78, Nitrogen, separate Water, and nitrogen transport. Crude presets are projected by exact ID; their selected physical properties, interactions, water model and viscosity/correction data must agree with the corresponding network sub-basis. Reordering or extending a network is a breaking development-world change, not a save migration. The bundled fluid menu still exposes Water, Nitrogen, Tia Juana, WTI and Cold Lake.

## Reloadable fluid reference records

`transport` records have identity, revision and source. `type: liquid_volume_reference_points` contains `points` with `component`, `temperatureKelvin`, `pressurePascal`, `molarVolumeCubicMetres` (m³/mol), and source. These are EOS volume-calibration references. `type: conditional_solute_log_tables` contains `reference_pressure_pascal` and component `curves` with increasing `temperatures_kelvin` and positive `viscosities_pascal_seconds`. Tables use the registered log-viscosity interpolation implementation. They describe the existing conditional dissolved-gas contribution, not a stable pure liquid above its critical temperature.

A fluid model captures these immutable records with its catalog. Volume references contribute to the fluid thermodynamic identity; conditional curves, molecular weights, pure curves and mixture corrections contribute to transport identity. Editing transport does not invalidate column PR/caloric seeds. No hardcoded classpath-only calibration or dissolved-viscosity loader remains.

## Bundled neural registry

`data/createcheme/neural/registry.json` is a bounded classpath registry, not a datapack weight loader. Schema 1 permits up to 16 entries with `priority`, `modelId`, `payload`, `sidecar` and `pipelineSha256`. Highest priority wins; equal priorities are resolved by model ID. Duplicate IDs, unsafe resource paths, wrong hashes, malformed shapes or unsupported policies reject the registry. An empty or unavailable registry preserves classical fallback.

The trained payload uses schema 1 and contains immutable weights, normalization, component axis and encoding/anchor revisions. Sidecar schema 2 contains `physicsFingerprint`, reference package, model ID, formulation/branch coverage, composition blend segments, numerical bounds, decoder/candidate/correction policy, and explicit `allowExtraZeroComponents` / `allowMissingZeroComponents` flags. The pipeline hash is SHA-256 of the ASCII string `payloadSha256:sidecarSha256`. Altering metadata does not rewrite weights, but requires whole-pipeline requalification.

Physics eligibility includes ordered component properties, EOS/interactions, water/caloric data and admitted domains. It excludes assay identity/composition, labels, provenance text, transport and declared revisions. Actual feed composition must still belong to the model's qualified composition domain. Every nonzero feed species must be represented. Reordering is by exact ID; discarding extra zero species and filling missing zero species require the respective explicit qualification flag. A missing species draws properties and cross-interactions only from the named reference package, and the complete projected fingerprint must match. No missing interaction is inferred as zero. The deployed regrouped model keeps both padding flags disabled; methane-free and nitrogen-extended requests use classical initialization.

Selection binds one model and one immutable catalog when a job is admitted. Native anchors run against that captured view, predictions scatter back to the request axis, and returned seeds retain the original request identity. Reloads cannot change running jobs. This projection is only an inference operation, never an old-save conversion. Result freshness remains tied to the original full scientific dataset.

## Adding a compound

1. Add a component ID and localized descriptor, sourced or explicitly estimated physical/PR/caloric data, supported transport curves and reference conditions. New correlations require tested Java implementations; no executable expressions are accepted.
2. Add it to a shared basis and qualify all selected interaction and water treatment. Explicitly update sparse assays, operating presets, network selection and conditional/calibration records where scientifically applicable. Unknown sparse IDs and incompatible preset/network physics must fail validation.
3. Update the bundled index and run catalog/property/flash/derivative, current-format serialization, packet, column and fluid tests. Axes are bounded at 64 non-water species plus water, and packet validation uses the server-provided axis rather than the client's local catalog. A structural fixture adds a synthetic nitrogen-like component and moves nitrogen without array-width edits; it does not establish experimental properties for a new real substance.
4. Qualify native column/pipeline behavior and fresh-world reload behavior. Old worlds are intentionally unsupported after an axis/property change; explicit reset or a fresh world is required.
5. Existing models remain eligible only for their exact qualified physics and composition domain. Train additional specialists from qualified labels, generate independent parity fixtures, run native selection/holdout gates, then add a registry entry with explicit priority and hashes. Never expand a sidecar's eligibility merely to bypass missing scientific qualification.

Elemental/SARA/reactivity/wax append data, reaction models, crude-specific rheology, and NRTL calculations remain deferred.

# P3 WP2, WP3, WP5 and WP9a: E-PPR78 group-interaction data, the bundled pilot package, its spine wiring and spine r2

Batch `2026-09-24-coolprop-low-temperature`, stage P3 of `UNIFIED_MULTIPHASE_THERMO_PLAN.md`, work packages WP2 and WP3 of [P3_PILOT_ENGINE_PLAN.md](P3_PILOT_ENGINE_PLAN.md) (sections 1.3, 3, 4.1, 4.3 to 4.5, 5 and 9; decisions D6 to D12). Written 2026-09-24 on branch `claude/coolprop-multiphase-thermo-37f6b0`, base `5100233`. WP5 appends its sections to this document.

Status: WP2 and WP3 implemented (commits in section 10), `science.material.*` green. WP5 (spine wiring, energy datum, network thermodynamic identity) implemented 2026-09-25 at `e4d355f` (sections 12 to 19), targeted suites and `fluidScienceTest` green. WP9a (spine r2: CEA gas constant 8.314510, methane GERG-2008 segment to 1200 K) implemented 2026-09-25 at `271d84a` (sections 20 to 27), `science.material.*` green. WP2b (the E-PPR78 matrix rebuilt from the publisher's Table S4) implemented 2026-09-25 at `f0d4cf8` (sections 28 to 33), full suite and `fluidScienceTest` green. Not pushed; the batch's INDEX and CHANGELOG rows are the lead's (WP11).

## 1. What exists

| Path (under `src/`) | Content |
|---|---|
| `main/java/com/wormzjl/createcheme/science/material/GroupInteractionMatrix.java` | Record kind `group_interactions`: loader and validation of an A_kl/B_kl matrix (MPa) |
| `.../science/material/GroupContributionInteractions.java` | Per-package data object: the rule-resolved temperature-dependent pairs with their (A, B, weight) terms; the API WP5 hands to the kernel (section 6) |
| `.../science/material/VolumeTranslation.java` | The `volume_translation` field of a property record and its shift on the untranslated kernel |
| `.../science/material/MaterialCatalog.java` | New kind; interactions `rule`; `Package.groupContributions`; `Property.volumeTranslation`; `ideal_gas_cp` optional with the D6 package rules; `groupInteractions()`, `volumeTranslation(package, component)`; `fluidThermoFingerprint` anchor precedence |
| `.../science/material/ReferenceSpine.java` | Optional per-segment `coverage` and `coverageAt(T)` |
| `.../science/material/Eppr78Groups.java` | Names of the freon (22-27) and alkyne (38-40) groups, from the transcription |
| `main/resources/data/createcheme/materials/group_interactions/eppr78_2022.json` | The 40-group matrix, 355 pairs |
| `main/resources/data/createcheme/materials/{components,properties,spine,crystals,interactions,packages}/…` | The pilot package (section 3), moved from the test resources where P2 had it, and extended |
| `main/resources/materials-index.json`, `main/resources/assets/createcheme/lang/en_us.json` | 13 new index entries; `material.createcheme.carbon_dioxide` |
| `test/resources/materials/pilot-cryogenic/` | The network override and three component fluid presets that select the pilot in tests (section 8) |
| `test/java/com/wormzjl/createcheme/science/material/{GroupContributionInteractionsTest,PilotVolumeAnchorTest}.java` | New tests; `PilotCryogenicCatalogTest`, `PilotCryogenicTestCatalog`, `ReferenceSpineTest` updated (section 9) |
| `test/java/com/wormzjl/createcheme/science/column/v3/thermo/RegroupedCrudeTest.java` | Package count 8 -> 9 (the one edit outside the WP2/WP3 file list, section 11) |
| `tools/eppr78-transcription-check/`, `tools/pilot-volume-anchors/`, `tools/pilot-transport-tables/` (git-ignored) | The offline builders and checks, each with a README; to be copied to the main checkout's `tools/` with `tools/INDEX.md` rows at WP11 |

## 2. Record schema additions

### 2.1 Kind `group_interactions` (`materials/group_interactions/<id>.json`)

| Field | Rule |
|---|---|
| `id`, `source` | as every record; `revision` a stable identifier of at most 63 characters |
| `provenance` | required object of texts (not interpreted) |
| `scheme` | stable identifier; for `eppr78-2022` the `groups` list must equal `Eppr78Groups` number by number and name by name |
| `units` | exactly `MPa` (A_kl and B_kl as the E-PPR78 papers print them; the data object converts to Pa) |
| `groups` | 1 to 64 `{number, name, transcription_label?}`, numbered 1..n in order, unique names |
| `pairs` | `{first, second, a_mpa, b_mpa}` of two different listed groups, finite, each unordered pair at most once |

Nothing of this record is hashed by itself: only the values a package uses enter that package's fingerprints (2.2).

### 2.2 Interactions `rule`

An interactions record (model `pr78`) may carry `"rule": {"type": "eppr78", "group_interactions": "<record id>"}`. At package load every component pair without an explicit constant `kij` in `pairs` is resolved from the matrix and the group counts of the two components' spines (`groups.counts`, Jaubert et al. 2022 decomposition: ethane is the `C2H6` group). Refusals, each naming the package pair:

- `rule.type` other than `eppr78`; `rule.group_interactions` unresolved;
- the package declares no `spine`;
- scheme mismatch: a spine's `groups.scheme` differs from the matrix's `scheme`;
- unknown group: a spine group the matrix does not list (reachable only with a non-`eppr78-2022` matrix; the spine loader already refuses names outside `Eppr78Groups`); a matrix pair naming an unlisted group is refused at the record;
- unresolved group pair: a group pair with a nonzero weight absent from the matrix;
- undefined term: A_kl = 0 with B_kl != 0 (the transcription has 79 such rows, for example CH3/CH_cyclic, and one with A = B = 0; how E-PPR78 treats them is not documented locally); A_kl = B_kl = 0 is a zero term and is dropped.

With `missing_interactions: error` a pair neither listed nor resolvable is refused as before. For a rule pair the constant matrix entry (`Package.interactions()`) stays 0.0 and is not its kij.

Fingerprint: `Package.fingerprint` and `physicsFingerprint(package, axis)` append `[rule, scheme, [[component i, component j, [[group k, group l, A MPa, B MPa, weight], …]], …]]` (for the pairs inside the axis) only when the package has a rule, so every other package hashes exactly the list it hashed before. Record ids and revisions are not hashed (the existing convention).

### 2.3 Property records

- `ideal_gas_cp` is optional. `Property.cp()` is then empty and `referenceTemperature()` 298.15. Package rules (D6, one ideal-gas source per species): a package without a `spine` refuses a property without `ideal_gas_cp` ("Property … has no ideal_gas_cp: a package without a reference spine needs a shifted_polynomial_5 fit"); a package with a `spine` refuses a property that carries one ("… carries ideal_gas_cp in a package with a reference spine").
- `volume_translation` (optional) = `{type: saturated_liquid_reduced_temperature, reduced_temperature, temperature_kelvin, pressure_pascal, molar_volume_m3_per_mol, reference, oracle, source}`. Rules: reduced temperature in (0, 1); `temperature_kelvin` = reduced temperature x the record's own PR78 Tc within 1e-9 relative; positive pressure and volume; the three texts required; the record needs `models.pr78`; the shift must exist (the kernel has a separate liquid root there). `Property.volumeTranslation()` carries the four numbers (hashed into the package's physics fingerprint through the property record; `null` when absent, which Gson omits, so no existing serialized form moves); the texts are validated and dropped. `VolumeTranslation.shift(pr)` = `v_ref - v_PR,L(T, Psat_ref)` on a one-component `PengRobinsonKernel` (Soave PR78, the kernel's constants, liquid root).
- One anchor per species per package, by precedence rather than refusal: the plan's literal rule ("refuse a record with both `volume_translation` and a `liquid_calibration.json` point") would refuse the pilot, because `liquid_calibration.json` is global per component and already holds N2, CH4 and C2H6 points for the network package. Instead a component whose property record has a `volume_translation` is anchored by it in that package: `fluidThermoFingerprint` leaves that component's calibration point out (tested: editing the methane calibration point moves the network package's fluid identity, not the pilot's), and `MaterialCatalog.volumeTranslation(package, component)` returns the package's anchor. WP5 must read it instead of `fluidData().volumeReferences()` for such components.

### 2.4 Spine segments

A segment may carry its own `coverage` (`grade`, `evidence`); `SegmentInfo.coverage()` returns it (else `null`) and `ReferenceSpine.coverageAt(T)` the grade that applies at T (the segment's own, else the record's; at a join the lower segment's, as for evaluation). Not hashed, like every grade.

## 3. The pilot records

All in `src/main/resources/data/createcheme/materials/`.

| File | Id, revision | Content and sources |
|---|---|---|
| `components/carbon_dioxide.json` | `CarbonDioxide` | identity; lang key `material.createcheme.carbon_dioxide` = "Carbon dioxide" |
| `properties/pilot_nitrogen.json` | `createcheme:pilot_nitrogen`, `pilot-nitrogen-p3-r1` | PR78 126.192 K, 3.3958 MPa, 0.0372, M, NBP and standard density as `fluid_nitrogen`; no Cp fit; Tr 0.8 anchor; `fluid_domain` 63.151-1200 K x 100 Pa-10 MPa; CoolProp 8.0.0 viscosity tables |
| `properties/pilot_methane.json` | `createcheme:pilot_methane`, `pilot-methane-p3-r1` | as `tjl20_methane` (190.564 K, 4.5992 MPa, 0.01142); 90.6941-1200 K |
| `properties/pilot_ethane.json` | `createcheme:pilot_ethane`, `pilot-ethane-p3-r1` | as `tjl19_ethane` (305.32 K, 4.872 MPa, 0.099); 90.368-1200 K; ethane Psat below 150 K declared (D3) |
| `properties/pilot_carbon_dioxide.json` | `createcheme:pilot_carbon_dioxide`, `pilot-carbon-dioxide-p3-r1` | PR78 304.1282 K, 7.3773 MPa, 0.22394 (Span and Wagner via CoolProp, P2); NBP field carries the normal sublimation temperature 194.6855 K; standard density 1046.88 kg/m3 at 250 K, 2 MPa (P2); 216.592-1200 K; the P2 placeholder Cp fit is gone |
| `spine/{nitrogen,methane,ethane}.json` | r1 (P2), unchanged, moved | CoolProp alpha0 + NASA CEA; ATcT and CEA anchors (P2_DATA_SPINE.md) |
| `spine/carbon_dioxide.json` | `spine-carbon_dioxide-r2` | P2 r1 plus a first segment 90-216.592 K with the same Span-Wagner alpha0 terms, graded `estimated_declared_error` on its own (section 5) |
| `crystals/carbon_dioxide_i.json` | r1 (P2), unchanged, moved | research-only, refuses evaluation |
| `interactions/pilot_cryogenic.json` | `createcheme:pilot_cryogenic` | no constant pair; `rule` eppr78 on `createcheme:eppr78_2022` |
| `packages/pilot_cryogenic.json` | `createcheme:pilot_cryogenic`, `pilot-cryogenic-p3-r1` | model pr78, `missing_interactions` error, `water_model` createcheme:water, `spine`, `crystals`; column-style range 216.592-1200 K x 100 Pa-10 MPa (the intersection of the property ranges); `fluid_domain` envelope 63.151-1200 K x 100 Pa-10 MPa |
| `group_interactions/eppr78_2022.json` | `createcheme:eppr78_2022`, `eppr78-2022-clapeyron-0778184-r1` | Clapeyron.jl `EPPR78_unlike.csv` at commit `0778184` (git blob `2045f7ff…`, SHA-256 `f20474d2…`), 40 groups, 355 pairs verbatim, MPa |

Provenance of the matrix: a third-party transcription (Clapeyron.jl, MIT). The MANIFEST claim that the Lasala 2020 chapter prints the pilot rows is wrong (P3 design section 4.5); Table S4 of Jaubert et al. 2022 is still to be fetched through a browser. `EPPR78_groups.csv` (blob `2e2b497e…`) is not used: it splits ethane into 2 CH3.

## 4. Volume-translation anchors

From the Java Helmholtz oracle (`science.thermo.reference.HelmholtzFluid`, commit `6ded7c7`; CoolProp `dev/fluids` files at `ae81610e`), printed by `tools/pilot-volume-anchors` and held by `PilotVolumeAnchorTest` to 1e-12 relative. c is the shift on the untranslated kernel; "old c" the anchor these species had through the 2 MPa formulation (NIST `liquid_calibration.json` points; for CO2 the P2 standard density at 250 K, 2 MPa), evaluated with the same kernel at its own pressure.

| Record | Tc (record) K | T = 0.8 Tc, K | Psat, Pa | v_L, m3/mol | c, cm3/mol | P1 rule (b) | old c, cm3/mol |
|---|---|---|---|---|---|---|---|
| pilot_nitrogen | 126.192 | 100.9536 | 830 956.907 | 4.098312e-5 | 3.518832 | 3.519 | 4.063490 (90 K) |
| pilot_methane | 190.564 | 152.4512 | 1 159 979.541 | 4.546081e-5 | 3.389108 | 3.389 | 3.643535 (150 K) |
| pilot_ethane | 305.32 | 244.256 | 1 100 180.172 | 6.563308e-5 | 3.451672 | 3.454 | 3.831907 (240 K) |
| pilot_carbon_dioxide | 304.1282 | 243.30256 | 1 435 091.827 | 4.093602e-5 | 1.169707 | 1.170 | 0.945916 (250 K) |

N2, CH4 and CO2 reproduce P1 within 1e-3 cm3/mol. Ethane reproduces P1 (3.454215) only with the CoolProp constants P1 used (305.322 K, 4.8722 MPa, 0.0990); the pilot record keeps the bundled ethane constants, which moves T by 1.6 mK and c by 0.0023 cm3/mol (the test holds both). Full-precision values are in `tools/pilot-volume-anchors/anchors.json` and the records.

## 5. CO2 below its triple point

The CO2 spine's CoolProp segment is continued from 216.592 K down to 90 K as a separate segment with the identical Span-Wagner alpha0 terms (the join at 216.592 K has a Cp step of exactly 0). What the function is valid for: the terms are a sum of Planck-Einstein (harmonic-oscillator) contributions on the rigid linear-rotor limit Cp0 = 3.5 R, analytic below the triple point, but the Span-Wagner equation of state states its range from the triple point, so nothing in the source qualifies it below. Grade of the segment: `estimated_declared_error` (declared 0.2 % in Cp); the record grade `qualified` applies from the triple point up.

Against NIST-JANAF C-095 (Chase 1998, read 2026-09-24; JANAF has no 150 K row):

| T, K | spine Cp | JANAF Cp | dev | spine h - h(298.15), J/mol | JANAF | spine s(T, 1 bar) | JANAF |
|---|---|---|---|---|---|---|---|
| 100 | 29.20555 | 29.208 | -0.008 % | -6456.42 | -6456 | 178.9964 | 179.009 |
| 200 | 32.36493 | 32.359 | +0.018 % | -3413.43 | -3414 | 199.9641 | 199.975 |

`ReferenceSpineTest.carbonDioxideBelowTheTriplePointAgainstJanaf` holds Cp to 0.2 %, h to 0.1 % or 20 J/mol, s to 0.02 J/(mol K) (the spine's S° is CEA's, 0.0086 below JANAF's), and the grades. The ATcT/CEA anchors of P2 (`anchorsAreExact`) and the CEA holdouts at 1000-1200 K (`ceaSegmentAgainstTheHoldouts`) are unchanged and pass.

## 6. The E-PPR78 data object (API for WP1/WP5)

`MaterialCatalog.Package.groupContributions()` (nullable; `temperatureDependentInteractions()` returns it as an `Optional`) is a `GroupContributionInteractions`:

- `rule()` "eppr78", `scheme()` "eppr78-2022", `matrixId()`, `matrixRevision()` (not hashed);
- `pairs()`: the rule-resolved pairs ordered by (first, second), `first < second` package component indices; `pair(i, j)` (either order) or empty for a constant pair;
- each `Pair`: `terms()` (`Term(firstGroup, secondGroup, aMegapascal, bMegapascal, weight)`, `aPascal()`, `bPascal()`, `exponent() = B/A - 1` from the record's MPa values), array views `weights()`, `aPascal()`, `exponents()` (copies), and a reference evaluation `energy(T)` = E_ij in Pa, `energyDerivative(T)`, `energySecondDerivative(T)`;
- `GroupContributionInteractions.kij(E, a_i, b_i, a_j, b_j)`: the formula's second line, a reference helper for tests and tools.

The term arrays are exactly what `science.thermo.PairInteractions.Builder.groupTerms(i, j, weights, aPascal, exponents)` (WP1) takes. WP5 wiring, in package component order:

    int n = pkg.components().size();
    var builder = PairInteractions.builder(n);
    var rule = pkg.groupContributions();                       // null for a package without a rule
    for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
        var pair = rule == null ? null : rule.pair(i, j).orElse(null);
        if (pair != null) builder.groupTerms(i, j, pair.weights(), pair.aPascal(), pair.exponents());
        else if (pkg.interactions().get(i).get(j) != 0.0) builder.constant(i, j, pkg.interactions().get(i).get(j));
    }

Weights: `-(alpha_ik - alpha_jk)(alpha_il - alpha_jl)` summed over both orders of each unordered group pair k < l, the groups in the matrix's number order; every pilot pair is one term of weight 1 (`E_ij = A f(T)`). `severalGroupsGiveTheDoubleSumWeights` checks a two-group decomposition against the double sum written out.

## 7. Appendix A reproduction

`tools/eppr78-transcription-check/check_pilot_kij.py` (from the CSV) and `GroupContributionInteractionsTest.reproducesAppendixA` (from the loaded record and the kernel constants) give the same table. With the constants appendix A actually used (CoolProp ethane 305.322 K, 4.8722 MPa, 0.0990) all 48 cells equal the printed four decimals (largest |difference| 4.9e-5):

| Pair | A / B (MPa) | 100 K | 150 K | 200 K | 250 K | 300 K | 400 K | 600 K | 900 K |
|---|---|---|---|---|---|---|---|---|---|
| CH4/C2H6 | 9.951 / 13.73 | 0.0072 | 0.0063 | 0.0059 | 0.0057 | 0.0057 | 0.0060 | 0.0075 | 0.0121 |
| CH4/CO2 | 136.6 / 214.8 | 0.1213 | 0.1074 | 0.1040 | 0.1057 | 0.1104 | 0.1259 | 0.1747 | 0.3010 |
| CH4/N2 | 30.88 / 37.06 | 0.0337 | 0.0315 | 0.0295 | 0.0276 | 0.0256 | 0.0205 | 0.0030 | -0.0729 |
| C2H6/CO2 | 136.2 / 235.7 | 0.1734 | 0.1453 | 0.1331 | 0.1279 | 0.1267 | 0.1308 | 0.1543 | 0.2184 |
| C2H6/N2 | 61.59 / 84.92 | 0.0610 | 0.0492 | 0.0405 | 0.0332 | 0.0262 | 0.0122 | -0.0244 | -0.1472 |
| CO2/N2 | 113.9 / 212.4 | 0.0955 | 0.0371 | 0.0050 | -0.0158 | -0.0306 | -0.0505 | -0.0712 | -0.0675 |

With the pilot record's ethane constants (the bundled 305.32 K, 4.872 MPa, 0.099) 46 of 48 cells match; C2H6/N2 is 0.04056 at 200 K and 0.02625 at 300 K (one unit of the fourth decimal). The appendix caption ("as in the bundled records") is therefore inexact for ethane. The transcription check itself: the record carries all 355 CSV rows with identical decimal text and nothing else.

## 8. Test selection of the pilot network

`MaterialPresets` allows one network configuration, so tests select the pilot by replacing the resource key `data/createcheme/materials/networks/default.json` in a test catalog (`PilotCryogenicTestCatalog`, override files under `src/test/resources/materials/pilot-cryogenic/`, outside `data/` so the bundled catalog of other tests is not shadowed on the class path). The override names `createcheme:pilot_cryogenic`, keeps `default_column_preset` tia_juana, and lists the fluid presets water, nitrogen and three new component presets (`methane`, `ethane`, `carbon_dioxide`). The bundled network stays `tjl20_methane_nitrogen`. A dev datapack with the same `networks/default.json` would do the same in game; nothing in P3 needs one.

Consequence for WP4/WP5 (not a WP3 defect): until WP5's spine path exists, a network model built on the pilot (`HydrocarbonModel` reads `Property.cp()`) fails on the empty Cp list, and it would take the rule pairs' 0.0 constant entries as kij. The column adapter (`V3CatalogPropertyPackage`, `cp.get(0)`) likewise fails untyped on a spine package; the plan's typed column refusal is WP5's.

## 9. Tests

Gradle, targeted (2026-09-24), from Git Bash with `build/gradle.lock` held and no dev client running:

    JAVA_OPTS=-Xshare:off ./gradlew test --tests 'com.wormzjl.createcheme.science.material.*' --offline

(`verifyMaterialIndex` runs as a dependency of `processResources` in the same invocation.)

Runs:

- 2026-09-24 23:05 (WP2, before the move): `science.material.*`, BUILD SUCCESSFUL, 64 tests, 0 failures (57 existing + 7 of `GroupContributionInteractionsTest`).
- 2026-09-24 23:26 (WP2 + WP3), with `--tests 'com.wormzjl.createcheme.science.column.v3.thermo.RegroupedCrudeTest'` added: BUILD SUCCESSFUL, `verifyMaterialIndex` passed, 75 tests, 0 failures, 0 errors, 0 skipped: the 70 of `science.material.*` (`AmbientViscosityTest` 1, `Cdu17TestCatalogTest` 3, `CrudeAssayConversionTest` 1, `CrystalReferenceTest` 4, `FluidAppearanceTest` 5, `GroupContributionInteractionsTest` 7, `LiquidMixtureCorrectionTest` 4, `MaterialAuthoringTest` 4, `MaterialCatalogTest` 8, `MaterialFluidDataTest` 2, `MaterialPresetsTest` 4, `PilotCryogenicCatalogTest` 8, `PilotVolumeAnchorTest` 2, `ReferenceSpineTest` 10, `ViscosityTest` 7) and `RegroupedCrudeTest` 5. The first attempt at 23:24 stopped in `compileTestJava` on another agent's in-progress `DirectLiquidJacobianRowsTest` (WP4), fixed by its owner two minutes later.

The full suite was not run (WP11's gate). Bundling the pilot adds a ninth package to `MaterialCatalog.bundled()`; the only other test found counting packages is `RegroupedCrudeTest` (updated); `FluidAppearanceTest`, `ViscosityTest` and `AmbientViscosityTest` iterate every package and pass.

| Class | Tests | Checks |
|---|---|---|
| `GroupContributionInteractionsTest` (new) | 7 | bundled matrix (40 groups equal `Eppr78Groups`, 355 pairs, sample values); pilot pairs resolved from spine counts (one term, weight 1, A/B values, zero constant entry, E(T) against a direct evaluation 1e-12 over 60-1500 K, dE/dT 1e-7 and d2E/dT2 1e-5 against central differences); appendix A (48/48 and 46/48, section 7); two-group decompositions against the written-out double sum 1e-12; an explicit constant keeps its pair out of the rule; refusals (unresolved group pair, unknown group in the record, scheme name mismatch, scheme mismatch, units, duplicate and self pairs, undefined A = 0 term, rule type, unknown matrix, rule without spine, missing interaction without rule); fingerprints (A and B edits of a used pair move `fingerprint` and `physicsFingerprint`, an edit outside a sub-axis leaves the sub-axis fingerprint, an unused pair or a text moves nothing, the eight bundled pins hold in every edited catalog, inference projection refused) |
| `PilotVolumeAnchorTest` (new) | 2 | every anchor recomputed from the oracle (T, Psat, v_L at 1e-12); shifts against an independent cubic (1e-12) and P1's translations (1e-3 cm3/mol; ethane with P1's constants, and within 3e-3 with the record's) |
| `PilotCryogenicCatalogTest` (updated) | 8 | eight bundled pins in the bundled and the pilot-network catalogs; nine packages, only the pilot has a spine; pilot pins (scientific revision, physics, spine fingerprint); the pilot loads from the main resources (properties without Cp, anchors, spines, domains, viscosity ranges, six rule pairs, grades, lang key); the network override; spine fingerprint determinism and what moves it (spine, crystal, anchor numbers; not texts); spine/crystal package rules; D6 rules; `volume_translation` rules; coverage and component rules |
| `ReferenceSpineTest` (updated) | 10 | P2's nine, with every Helmholtz segment against the oracle (CO2 from 90 K), the CO2 joins at 216.592 K (step 0) and 975 K, the two-segment removal for the 298.15 K rule, a segment-coverage refusal; new `carbonDioxideBelowTheTriplePointAgainstJanaf` (section 5) |

Pins. The eight bundled pins (`BUNDLED_PINS`, P2 section 6) are byte-identical. The pilot package's pins as committed: scientific revision `pilot-cryogenic-p3-r1:37a33a6190bd9fba7433c494c63ae4bfab24995b6902cf58c59381033c3a7c64`, physics fingerprint `7378e1991b885b5a129a7e74eae0ee5d0ebd5f1d6d9be298182bc163519230c0`, spine fingerprint `e750073dc6b43d41f7341bd6fa1c4255d0bb529baecf389325f838c7808b99ec` (P2's `440ecc7f…` pinned the r1 CO2 spine), fluid thermodynamic fingerprint `b0197b034342c2a9ddce19883b7d69a81f41100c3a99a0c891e17272cab8e4f8` (printed, not pinned: WP4/WP5 change the network formulation around it).

## 10. Commits

- `6ec5c2c` WP2: group-interaction kind, the E-PPR78 rule, `GroupContributionInteractions`, the matrix record, `GroupContributionInteractionsTest` (at that point on the test-only pilot set).
- `50dfe31` WP3: the pilot package bundled (records moved and extended, CO2 spine r2, four `pilot_*` property records, index, lang), `VolumeTranslation`, the optional `ideal_gas_cp` with the D6 rules, per-segment coverage, the anchor precedence in `fluidThermoFingerprint`, the test network override, `PilotVolumeAnchorTest` and the updated tests, `RegroupedCrudeTest` 8 -> 9.

Both commits contain only WP2/WP3 files (explicit paths). Not pushed; no INDEX or CHANGELOG edit (WP11).

## 11. Known limits and open items

- **Table S4** of Jaubert et al. 2022 is not acquired; the matrix is a third-party transcription validated only by the appendix reproduction and, in WP8/WP9, functionally against GERG-2008 bubble points. When S4 arrives, extend `check_pilot_kij.py` to compare row by row; a changed value is a new record revision and moves the pilot fingerprint (tested behaviour).
- **Freon and alkyne numbering** (groups 22-27, 38-40) follows the transcription's column order; the names are the transcription's labels. No bundled species uses them.
- **A = 0 rows**: 79 transcription pairs have A = 0 and B != 0 (one more has A = B = 0); the loader refuses a species pair that needs one. How E-PPR78 (or Clapeyron) evaluates them should be settled from S4 or the Clapeyron source before a species with such groups is added.
- **Anchor rule deviation**: section 2.3, precedence instead of the plan's refusal (the global calibration points make the literal rule unsatisfiable for the pilot). WP5 must read `volumeTranslation(package, component)` first.
- **Ethane constants**: the pilot keeps the bundled ethane PR78 constants (305.32 K, 4.872 MPa); P1 and appendix A used CoolProp's (305.322 K, 4.8722 MPa). Effects: c 3.4517 vs 3.4542 cm3/mol, two appendix cells one unit apart. Switching to CoolProp's constants is a record revision if wanted.
- **CEA gas constant (plan section 5, P2 open item)**: the nasa/cea source at `3f4441d2` defines `gas_constant = 8314.5100d+00 ! J/kmol-K` (`source/param.f90.in` line 32), i.e. 8.314510 J/(mol K), not the 8.314472 the spine records carry for their CEA segments (and used for the CEA standard entropies). The plan's expected confirmation of 8.314472 fails. Effect 4.6e-6 relative on the CEA segments' Cp, h and s (below every target). Not corrected here: it needs the P2 spine builder re-run (S° from the CEA polynomial, four spine revisions, the pilot spine pin); recommended for WP9 or a spine r2/r3 pass.
- **CO2 at 150 K**: JANAF has no row; the sub-triple segment is held at 100 and 200 K only.
- **Viscosity**: saturated-liquid tables end at each critical temperature (a liquid-slot species above Tc gets `PROPERTY_UNAVAILABLE` from `MixtureViscosity`, as N2 does today); vapour tables are zero-density values; methane and ethane vapour tables are extrapolated above 625 and 675 K (`estimated: true`); the last millikelvin below Tc interpolates within 0.3-1.1 %. Dense-fluid viscosity above 2 MPa is unqualified (P3 design section 10).
- **Methane above 375 K** keeps the CEA/JANAF conflict of P2 (WP10).
- **CO2 crystal** unchanged: research-only, refuses evaluation (P4 needs Trusler 2011 or Jaeger and Span 2012).
- **Pilot package column range** 216.592-1200 K is the intersection of the property ranges; nothing column-side uses the pilot.
- **Edit outside the file list**: `RegroupedCrudeTest` (column suite, no WP owner) asserted eight bundled packages; bundling the pilot makes nine, so the literal changed (one line, commented). No other agent's file was touched.
- **Detachment**: the three tool folders hold only offline builders and checks; nothing measurement-only is in a tracked path. `GroupContributionInteractionsTest`, `PilotVolumeAnchorTest` and the updated classes are gate tests of WP2/WP3 and stay.

---

# WP5: spine wiring, energy datum, network thermodynamic identity

Work package WP5 of [P3_PILOT_ENGINE_PLAN.md](P3_PILOT_ENGINE_PLAN.md) (section 5, WP5 row of section 9; decisions D6, D9, D10). Written 2026-09-25 on `claude/coolprop-multiphase-thermo-37f6b0`, commit `e4d355f` on top of WP4 `959ea0d`. A first WP5 agent was cut off by a rate limit after writing the legacy pin test; this pass kept and finished it (section 17). Paths below are relative to `src/main/java/com/wormzjl/createcheme/` unless stated.

## 12. What exists

| Path | Change |
|---|---|
| `science/fluid/thermo/SpinePackageData.java` (new) | The thermodynamic inputs of a spine package, resolved once from the catalog: PR78 components in package order, the `PairInteractions` (WP3 section 6 wiring: rule pairs as `groupTerms`, other nonzero constants as `constant`), one anchor per species (`volume_translation` via `MaterialCatalog.volumeTranslation`, else the global calibration point, else the standard density, each on the untranslated PR78 at the anchor's own state; `Anchor` records the source), the `ReferenceSpine` per component, the formation enthalpies and the formation `EnergyReference`. A package without a spine is refused. Both the network and the evaluator build from it. |
| `science/fluid/thermo/TranslatedPengRobinson.java` | Additive spine variant `TranslatedPengRobinson(sourceId, components, PairInteractions, List<IdealGasFunction>, datumOffsets, translations)`: `prepare(T)` fills `c_p,i = f_i.heatCapacity(T)` and `h_i = f_i.enthalpy(T) - offset_i` where `T` lies in `f_i`'s declared range and NaN elsewhere; `fill` refuses a carried species outside its range with a `ThermoDomainViolation` (package, component, the declared range; the carried one with the largest amount) and skips absent species; `Derivatives.partialMolarEnthalpyView()` is NaN for an absent species outside its range. Accessors `spineVariant()`, `idealGas(i)`, `datumOffset(i)`, `translation(i)`. The coefficient path is a separate branch with the old statements (pinned, section 17). Stale Javadoc of `Derivatives` (`GlobalLiquidResponse`, 2 MPa reference) replaced. |
| `science/fluid/thermo/HydrocarbonModel.java` | Spine path for a package whose `spine()` is non-empty: builds the spine variant from `SpinePackageData`, checks a liquid root at every anchor (the coefficient path's rule), revision = its `ThermoIdentity` (section 14). New: `SPINE_DATUM`, `NETWORK_FAMILY`, `thermoIdentity()`, `spine()`, `formationReference()` (all empty on the coefficient path). The coefficient path's code is unchanged. |
| `science/fluid/thermo/FluidThermodynamics.java` | The two `@Deprecated` three-argument shims (`FluidThermodynamics(MaterialCatalog, String, double)`, `forNetwork(MaterialCatalog, String, double)`) removed. |
| `science/thermo/phase/CubicPhaseEvaluator.java` | Additive `forPackage(catalog, packageId)` (whole package basis, from `SpinePackageData`) and a private constructor on `PairInteractions`; stale class comment (`HydrocarbonModel.REFERENCE_PRESSURE`) replaced. |
| `science/thermo/phase/PhaseContract.java` | Additive `forNetworkPackage(catalog, packageId)`: the P2 form with the package's own `spineFingerprint()` (empty maps to `ThermoIdentity.NO_SPINE`). |
| `science/material/IdealGasFitUnavailable.java` (new) | Typed refusal (`IllegalArgumentException` subtype with `packageId()`, `consumer()`) of a consumer that reads the `shifted_polynomial_5` fits. |
| `science/column/v3/thermo/V3CatalogPropertyPackage.java` | One guard: a spine package is refused with `IdealGasFitUnavailable(id, "the V3 column")` before any `cp.get(0)`. Column packages have no spine, so nothing else changes. |
| Tests | New `fluid/thermo/LegacyNetworkPathPinTest` (1), `fluid/thermo/SpineNetworkPathTest` (7), `thermo/phase/SpinePackageEvaluatorTest` (3). `MaterialPresetsTest`, `TangentPlaneStabilityTest`, `TranslatedPengRobinsonDerivativesTest`: the ignored compressibility argument dropped (so the shims could go). |

`ideal_gas_cp` is now optional end to end for a spine package: the loader already allowed it (WP3); the network's spine path reads no `Property.cp()`; the column refuses the package typed; the only other reader (`test/.../fluid/support/LegacyLiquidPath`) serves spine-less packages.

## 13. Energy datum (D10) as implemented

- The network stays on its sensible datum. For species `i` the spine path passes `offset_i = spine_i.formationEnthalpy().value()` as the datum offset, so the network's ideal-gas enthalpy is `h_ig,i(T) - DeltafH_i`; at 298.15 K it is exactly 0.0 for all four pilot species (the spine's `h(298.15)` equals its formation enthalpy bit for bit, P2). The phase enthalpy is therefore `h_network = h_evaluator - sum_i x_i DeltafH_i`, with the same volume, `ln phi`, heat capacity and volumetric derivatives.
- `SpinePackageData.formationReference()` (also `HydrocarbonModel.formationReference()`) is an `EnergyReference` over the package components with offsets `DeltafH_i` (N2 0, CH4 -74,513, C2H6 -84,020, CO2 -393,477 J/mol), `formationDataQualified = true`, revision `createcheme:formation-298.15K-spine-v1:<spineFingerprint>`. The network's own datum is `EnergyReference.sensible(components)`; `sensible.rebase(E, n, formationReference)` gives the engine's formation-datum energy (tested). The runtime (`CausalModuleCoordinator`, `FluidCheckpointCodec`, `FluidCheckpointStore`, `ModuleTransferPlanner`) keeps `EnergyReference.sensible(model.components())` unchanged.
- Free water keeps the network's sensible free-water reference (IF97 Region 1 plus the V3 water correlations, as WP4 left it); it is not in the formation reference's basis because no water spine exists. The pilot network's pump reference density and liquid-water enthalpy equal the bundled network's bit for bit (same water record).
- Checks (`SpinePackageEvaluatorTest`, 1,380 phases: 11 temperatures 100 to 1200 K, 6 pressures 0.1 to 10 MPa, 13 pure and mixed compositions, both roots; 336 more refused by the network domain because they carry CO2 below 216.592 K): `v`, every `ln phi_i`, `dv/dT`, `dv/dP`, `d ln phi_i/dT` bit for bit between `HydrocarbonModel` and `CubicPhaseEvaluator.forPackage`; `|h_eval - sum x DeltafH - h_net|` at most 2.9e-16 of the larger magnitude (gate 1e-15); the rebase at most 2.2e-16; `c_p` at most 4.1e-16 relative (gate 1e-13). Editing methane's formation enthalpy by +1000 J/mol moves the identity and the formation reference but not the network's enthalpies (`SpineNetworkPathTest`).

## 14. Thermodynamic identity and revision layout

- **Spine package:** `HydrocarbonModel.revision()` = `ThermoIdentity.of(catalog, id, NETWORK_FAMILY, package.spineFingerprint(), SEPARATE_FREE_WATER).revision()`:

      thermo-identity-v1;<len>:<fluidThermoFingerprint>;<len>:<family>;<len>:<spineFingerprint>;<len>:separate-free-water-v1:<water record revision>

  with `NETWORK_FAMILY = pr78_translated_v1:direct-liquid-v1:spine-sensible-datum-v1` (the engine's residual family, the network's direct liquid path, the datum and anchor rule). The pilot's string:

      thermo-identity-v1;64:b0197b034342c2a9ddce19883b7d69a81f41100c3a99a0c891e17272cab8e4f8;59:pr78_translated_v1:direct-liquid-v1:spine-sensible-datum-v1;64:e750073dc6b43d41f7341bd6fa1c4255d0bb529baecf389325f838c7808b99ec;45:separate-free-water-v1:water-iapws-shomate-r1

  (the fluid thermodynamic fingerprint `b0197b03...` is the one WP3 printed; the spine fingerprint `e750073d...` is WP3's pin). `ApproximationAnchor` and `ProcessSolveServices` carry the revision as before, so a saved island or a cached solve of another identity is discarded.
- **Engine contract:** `PhaseContract.forNetworkPackage(catalog, id)` has the same package, spine and water parts with family `pr78_translated_v1` (the evaluator's own, formation datum). The two identities differ only by the family: they describe the same thermodynamics on two energy datums, so a number cached from one must not be reused for the other.
- **Package without a spine:** unchanged, `<fluidThermoFingerprint>:direct-liquid-v1:fluid-domain-data-v1:cp-segments-v1:catalog-volume-reference-v1` (the bundled network: `f5e0178e...:direct-liquid-v1:...`, pinned).
- **Moves (tested):** a spine numeric edit (methane standard entropy +1e-4) moves only the spine part; an interaction edit (CH4/N2 group pair A 30.88 to 30.89 MPa) moves only the package part; a spine text edit moves nothing; a formation-enthalpy edit moves the spine part.

## 15. Pilot evaluation against the oracle

All numbers are the model's minus the Java Helmholtz oracle's (`science.thermo.reference.HelmholtzFluid`), pure species, the network's spine path (`SpineNetworkPathTest`, printed in the Gradle output). h is the departure `h - h_ig(T)` in both models (the P1 metric; the ideal parts agree to rounding below each spine's join, P2).

**P1 reproduction.** All 32 dense and supercritical states of P1 section 2.5 and all 28 saturated liquids of section 2.4 (anchor b) reproduce the printed density, `c_p` and h to half a unit of the printed digit (plus 1e-3 %, 0.05 J/mol). Exceptions, by design: ethane within 0.02 % and 1 J/mol (P1 used CoolProp's 305.322 K / 4.8722 MPa, the record keeps the bundled 305.32 K / 4.872 MPa); `c_p` inside the declared band within 0.02 % (CO2 300 K saturated: 49.08 against 49.09 %); `c_p` at the four critical points (N2 126.2 K / 3.4 MPa, CH4 190.6 K / 4.6 MPa, C2H6 305.4 K / 4.9 MPa, CO2 304.5 K / 7.4 MPa) not compared: it diverges there and the two oracles' states differ in the last digits of density (N2: -2.3 % here against P1's +89.2 %; density and h do reproduce). Examples: N2 77.36 K / 5 MPa +1.531 % / -6.07 % / +64.1 J/mol (P1 +1.53, -6.07, 64); CO2 280 K / 8 MPa -3.237 % / +9.22 % / +117.9 J/mol (P1 -3.24, +9.22, 118).

**Anchors.** The four translations equal WP3's to the printed digits (3.518832, 3.389108, 3.451672, 1.169707 cm3/mol, bit for bit with `VolumeTranslation.shift`), and each pure liquid at its anchor (0.8 Tc, Psat_ref) reproduces the oracle's saturated-liquid volume to 1e-12.

**Isotherm set** (`pilotIsothermsAgainstTheOracleAtTheD7Targets`): 9 isobars 0.1 to 10 MPa x 11 to 13 temperatures per species (N2 70 to 1000 K, CH4 95 to 600 K, C2H6 100 to 650 K, CO2 220 to 1100 K, inside each reference equation's range), 418 states scored, 14 in the declared band (D7 widened band) not scored. Worst deviation per region (state in brackets):

| Species | Region | n | density % | `c_p` % | h departure J/mol |
|---|---|---|---|---|---|
| N2 | liquid Tr <= 0.8 | 30 | +1.88 (90 K, 10 MPa) | -8.09 (70 K, 10 MPa) | +99 |
| N2 | liquid 0.8-0.9 | 5 | -1.94 (110 K, 2 MPa) | +10.47 | +84 |
| N2 | liquid > 0.9 (outside band) | 4 | -3.07 (115 K, 3 MPa) | +12.29 | +103 |
| N2 | vapour Z >= 0.9 | 49 | +1.07 (130 K, 1 MPa) | -3.88 (77.36 K, 0.1 MPa) | -50 |
| N2 | vapour 0.8 <= Z < 0.9 | 5 | +2.16 (150 K, 3 MPa) | -7.85 (110 K, 1 MPa) | -52 |
| N2 | vapour Z < 0.8 | 1 | +2.15 (130 K, 2 MPa) | -4.36 | -19 |
| N2 | supercritical | 18 | -4.13 (130 K, 7 MPa) | +6.28 | +106 |
| CH4 | liquid Tr <= 0.8 | 29 | +2.31 (130 K, 10 MPa) | +5.83 (150 K, 2 MPa) | +62 |
| CH4 | liquid 0.8-0.9 | 5 | -2.37 (165 K, 2 MPa) | +13.04 | +155 |
| CH4 | liquid > 0.9 | 3 | -2.97 (175 K, 5 MPa) | +11.58 | +167 |
| CH4 | vapour Z >= 0.9 | 40 | +1.41 (250 K, 3 MPa) | -4.08 (150 K, 0.5 MPa) | -62 |
| CH4 | vapour 0.8 <= Z < 0.9 | 4 | +1.53 (200 K, 2 MPa) | -11.26 (150 K, 1 MPa) | +73 |
| CH4 | vapour Z < 0.8 | 2 | +2.28 (200 K, 3 MPa) | -8.64 | +36 |
| CH4 | supercritical | 13 | -4.69 (200 K, 10 MPa) | +4.95 | +194 |
| CH4 | supercritical, band corner | 1 | -8.42 (200 K, 7 MPa) | -1.58 | +313 |
| C2H6 | liquid Tr <= 0.8 | 33 | +2.21 (220 K, 10 MPa) | -9.13 (100 K, 10 MPa) | +528 |
| C2H6 | liquid 0.8-0.9 | 9 | -2.82 (270 K, 3 MPa) | +10.50 | +222 |
| C2H6 | liquid > 0.9 | 3 | -5.78 (290 K, 5 MPa) | +14.20 | +371 |
| C2H6 | vapour Z >= 0.9 | 40 | +1.17 (350 K, 2 MPa) | -2.78 (250 K, 0.5 MPa) | -101 |
| C2H6 | vapour 0.8 <= Z < 0.9 | 5 | +1.70 (350 K, 3 MPa) | -7.19 (250 K, 1 MPa) | +94 |
| C2H6 | vapour Z < 0.8 | 2 | +2.08 (320 K, 3 MPa) | -11.68 | +135 |
| C2H6 | supercritical | 12 | -5.93 (320 K, 10 MPa) | +4.59 | +326 |
| CO2 | liquid Tr <= 0.8 | 11 | +1.48 (220 K, 10 MPa) | -9.77 (220 K, 10 MPa) | +178 |
| CO2 | liquid 0.8-0.9 | 12 | -2.57 (270 K, 5 MPa) | +8.05 | +112 |
| CO2 | liquid > 0.9 | 2 | -5.07 (285 K, 7 MPa) | +14.14 | +221 |
| CO2 | vapour Z >= 0.9 | 64 | +1.00 (350 K, 3 MPa) | -8.88 (220 K, 0.5 MPa) | -138 |
| CO2 | vapour 0.8 <= Z < 0.9 | 7 | +1.51 (350 K, 5 MPa) | -14.45 (260 K, 2 MPa) | -181 |
| CO2 | vapour Z < 0.8 | 4 | +1.99 (320 K, 5 MPa) | -19.17 (270 K, 3 MPa) | +201 |
| CO2 | supercritical | 5 | +0.87 (350 K, 10 MPa) | -3.61 | -223 |

Per species over all scored states: N2 density 4.13 %, `c_p` 12.3 %, h 106 J/mol; CH4 8.42 % (band corner; 4.69 % elsewhere), 13.0 %, 313 J/mol; C2H6 5.93 %, 14.2 %, 528 J/mol (the cold liquid, the Soave Psat error of D3); CO2 5.07 %, 19.2 %, 223 J/mol. Enthalpy of vaporization at Tr 0.5 to 0.9: worst 2.26 % (CH4 at Tr 0.9; D7 3.5 %).

**D7 verdict** (P0 section 3.2 with the P1 amendments; the test holds the D7 target where it is met and a declared bound where it is not):

| Region | D7 target | Met? | Bound held |
|---|---|---|---|
| Liquid density Tr <= 0.9 | 3 % | yes (2.82 %) | 3 % |
| Liquid `c_p` Tr <= 0.8 / 0.8-0.9 | 10 % / 20 % | yes (9.77 / 13.04 %) | 10 / 20 % |
| Liquid density 0.9 < Tr outside the band | 5 % (near-Tc row) | **no**: C2H6 -5.78 % (290 K, 5 MPa, Tr 0.95, Pr 1.03), CO2 -5.07 % | 10 % (the declared saturated-liquid row) |
| Vapour density Z >= 0.8 | 1 % | **no**: N2 +2.16 %, CH4 +1.53 %, C2H6 +1.70 %, CO2 +1.51 %; at Z >= 0.9 N2 1.07, CH4 1.41, C2H6 1.17, CO2 1.00 % | 1.5 % (Z >= 0.9), 2.5 % (0.8-0.9) |
| Real-gas vapour `c_p` Z >= 0.9 | 2 % | **no** near saturation: CO2 -8.88 % (220 K, 0.5 MPa), CH4 -4.08, N2 -3.88, C2H6 -2.78 % | 10 % |
| Supercritical density / `c_p` outside the band | 7 % / 10 % | yes (5.93 / 6.28 %) except the band corner | 7 / 10 % |
| Supercritical density, Tr 1.0-1.1 x Pr 1.5-2 (between the band's boxes) | 7 % | **no**: CH4 -8.42 % (200 K, 7 MPa, Tr 1.05, Pr 1.52) | 10 % |
| hvap Tr <= 0.9 | 3.5 % | yes (2.26 %) | 3.5 % |

The misses are the cubic's (P1 section 2.2 shows the same vapour-like worst cases, +1.5 to +2.2 % with anchor b; about a third to a half of the vapour density excess is the Tr 0.8 translation itself, which shifts a gas's volume by `c/v`, 0.5 % for methane at 250 K and 3 MPa). They are WP9's to adjudicate at G3 (section 19).

**Mixtures through the network.** `pureAndMixedStatesEvaluateAcrossTheEnvelope`: the network's own `flashTP` (Wilson successive substitution, the initializer until WP7) and `state` on 11 feeds (pure, binary, quaternary, one with water) x 11 temperatures 80 to 1100 K x 6 pressures 0.1 to 10 MPa: 499 states evaluated (17 two-phase), every species conserved to 1e-12, finite enthalpy and internal energy and a positive velocity bound; 222 refused by the fluid domain (CO2 below 216.592 K, CH4 and C2H6 at 80 K), 4 by the free-water rule's steam partial-pressure limit, and 1 Wilson non-convergence (CH4/CO2 80/20 at 230 K and 2 MPa, next to its dew point).

**Refusal outside a spine range** (`aSpeciesOutsideItsSpineIsRefusedTypedAndAnAbsentOneIsNotEvaluated`): methane at 85 K is refused with `ThermoDomainViolation(createcheme:pilot_cryogenic, Methane, TEMPERATURE_BELOW, 90.6941..6000 K)`, also through `differentiate`; N2/CH4/CO2 60/10/30 at 80 K names CarbonDioxide (the largest carried amount outside its spine); pure nitrogen at 70 K evaluates (the other spines are not evaluated) and its absent species' partial molar enthalpies are NaN; the evaluator refuses the same way (`SpinePackageEvaluatorTest`). The network's `phase` refuses such a state earlier through the fluid domain, with the same type.

## 16. Column refusal

`V3PengRobinsonThermo.fromRegisteredPackage("createcheme:pilot_cryogenic")` throws `IdealGasFitUnavailable` ("Package createcheme:pilot_cryogenic takes its ideal gas from a reference spine and carries no shifted_polynomial_5 fits; the V3 column reads the fits and cannot use it") instead of the WP3 `IndexOutOfBoundsException`; `createcheme:tjl20_methane` still resolves (`theColumnRefusesASpinePackageTyped`). The 16 column thermo classes (95 tests, among them `V3CatalogParityTest` exact and `V3FeedFlashEquivalenceTest` bitwise) pass unchanged.

## 17. The legacy path, bit for bit

`LegacyNetworkPathPinTest` hashes (SHA-256 of raw bits) every number the network reads from its hydrocarbon model on a fixed probe of the bundled `createcheme:tjl20_methane_nitrogen`: `HydrocarbonModel.phase` on both roots, the whole analytic derivative bundle, `flashTP` with water and its velocity bound, the pump reference density, over 7 temperatures (77 to 700 K) x 3 pressures x 3 compositions, including the text of every refusal met. The digest was printed before any WP5 source edit by compiling the `959ea0d` sources (`git archive`) with the test and a printer (`tools/wp5-legacy-digest/`, README there): `d034d519c1daa46085eacb6cd7784f0b6dae54ff360733601cf029e234ca6cf1` on JDK 21.0.11 (the Gradle toolchain), identical with `-Xint`. After WP5 the Gradle run reproduces it, and the revision string is WP4's. The pin is JDK-specific: JDK 25.0.4 prints `00f203a2...` on the same `959ea0d` sources (its `Math` intrinsics differ), so it holds on the toolchain JDK only. `TranslatedPengRobinsonPairInteractionsTest` (constant reduction on the bundled package, 27,984 doubles bit for bit) and every existing fluid-thermo test also pass unchanged.

## 18. Tests and Gradle runs

Each run from Git Bash with `JAVA_OPTS=-Xshare:off`, `--offline`, under `build/gradle.lock` (tag `wp5`), no dev client running (checked before each run), 2026-09-25:

1. `./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.phase.*' --tests 'com.wormzjl.createcheme.science.fluid.thermo.*' --tests 'com.wormzjl.createcheme.science.material.*' --tests 'com.wormzjl.createcheme.science.thermo.TangentPlaneStabilityTest'`: BUILD SUCCESSFUL, 42 classes, 180 tests, 0 failures, 0 errors, 1 skipped (the env-gated `FluidTpEquilibriumCostTest`). `verifyMaterialIndex` passed. The tree held WP6b's uncommitted engine work (`FluidTpEquilibriumPhUvTest`, 7 tests among the 180, green).
2. `./gradlew fluidScienceTest`: BUILD SUCCESSFUL, 56 classes, 203 tests (WP4's 195 plus the 8 new fluid-thermo tests), 0 failures, 1 skipped.
3. `./gradlew test --tests 'com.wormzjl.createcheme.science.column.v3.thermo.*'` (the column adapter's guard): BUILD SUCCESSFUL, 16 classes, 95 tests, 0 failures.

New tests (11):

| Class | Tests | Holds |
|---|---|---|
| `fluid.thermo.LegacyNetworkPathPinTest` | 1 | the bundled network package bit for bit as at WP4 (digest and revision), no spine |
| `fluid.thermo.SpineNetworkPathTest` | 7 | the pilot model (spine variant, six E-PPR78 pairs, anchors reproduced, sensible zero, formation reference, water unchanged, the override selects it); the flash grid; P1 reproduction (60 rows); isotherms at the D7 targets and hvap; spine-range refusal; identity layout and moves, the formation-enthalpy datum check, the legacy revision; column refusal |
| `thermo.phase.SpinePackageEvaluatorTest` | 3 | network = evaluator (bit for bit and `sum x DeltafH`, rebase, `c_p`, derivatives) over 1,380 phases; `forPackage` refuses a spine-less package and a species outside its spine; `PhaseContract.forNetworkPackage(catalog, id)` (spine, water, fluid-only competition, family, the network identity differing by family only, the spine-less overload equal to `NO_SPINE`) |

## 19. Known limits and open items

Known limits:

- **D7 misses** (section 15): vapour density at Z >= 0.8 (to +2.2 %, target 1 %), vapour `c_p` near saturation (to -8.9 %, target 2 %), liquid density at Tr 0.9 to 0.95 just outside the band (-5.8 %, target 5 %), and the supercritical corner Tr 1.0-1.1 x Pr 1.5-2 that the widened band's two boxes leave out (-8.4 %, target 7 %). The test holds declared bounds there; nothing is qualified by WP5.
- **Formation reference basis**: the package components only; free water keeps the network's sensible reference, so a full-basis formation datum (a reaction step with water) needs a water spine (P6 or later).
- **Partial molar enthalpies** of an absent species outside its spine's range are NaN in the analytic derivative bundle (the value is undefined there); a consumer that assembles an analytic Jacobian must skip absent species. The network's Jacobian is still a finite-difference sweep.
- **`CubicPhaseEvaluator.forPackage`** takes the whole package basis; a sub-basis still goes through `fromPackage` with the caller's translations and constant interactions.
- **`PhaseContract.forNetworkPackage(catalog, id)`** qualifies fluid-only competition; the pilot's CO2-I crystal is research-only (P4/P5).
- **Legacy pin** is specific to the toolchain's JDK (section 17).
- **Cost** of the spine path is not measured: per distinct temperature four spine evaluations (Cp and h) and six `pow` for the E-PPR78 pairs (WP1: about 150 ns at four components), per evaluation the sparse plan; only the pilot takes it, the bundled network's path is unchanged.
- **Stale 2 MPa wording**: the pilot records carry none (their only 2 MPa note is the correct transport one, "Viscosity above 2 MPa for dense states is a reference-pressure value"); the WP4-listed "PR78 at the 2 MPa liquid reference with the global compressibility response" evidence is in the bundled crude and network records (`packages/tjl*.json`, the `*_tjl20` packages, `properties/crude_pc*.json`, `nitrogen.json`), outside WP5's files, left for WP7 (which edits `nitrogen.json` and `tjl20_methane.json` anyway).
- **Commit attribution**: the WP5 brief asked for a Fable 5.1 co-author line; the commit carries the session's actual model line (Opus 5.5), which is the accurate one.

Open items for WP7:

1. The Wilson flash does not converge for CH4/CO2 80/20 at 230 K and 2 MPa (next to the dew point); the engine as the outer check (plan section 6.2) replaces it.
2. The liquid-root rule and `vaporBranch` on the spine path are the WP4 rules; `PhaseIdentification` as the one implementation applies to both paths.
3. The network revision of a spine package includes `HydrocarbonModel.FORMULATION`; WP7's outer phase check that changes the network formulation should bump `FORMULATION` (moving both paths' revisions; `LegacyNetworkPathPinTest` then re-prints its digest with the reason).
4. The stale evidence text of the bundled records (above).

Open items for WP9:

1. Adjudicate the four D7 misses of section 15 at G3 (declared errors, a band extension across Tr 1.0-1.1 x Pr 1.5-2, or a vapour-side anchor rule).
2. D13 (methane spine r2, GERG-2008 ideal part above 425 K) and the CEA gas constant 8.314510: both move `spineFingerprint`, so the pilot's network revision and engine identity move once, as designed (`SpineNetworkPathTest` checks the layout and the moves, not the fingerprint values; `PilotCryogenicCatalogTest` pins the spine fingerprint).
3. The pilot isotherm set here is a WP5 wiring check; the F1 to F10 fixtures, the mixture K-value targets against the GERG-2008 fixture and the high-temperature `|h^R|` bound are WP9's.

Detached material: `tools/wp5-legacy-digest/` (the `959ea0d` source export, the printer and its README; one-off, not a harness), to be copied to the main checkout's `tools/` with a `tools/INDEX.md` row at WP11. The measurement scratch programs used while writing the tests lived outside the repository and are superseded by the tests' printed output. Every new test is a gate test and stays.

---

# WP9a: spine r2

The spine-record half of WP9 of [P3_PILOT_ENGINE_PLAN.md](P3_PILOT_ENGINE_PLAN.md) (decisions D6 with its P2 note and correction, D7, D13): the NASA CEA gas-constant correction and methane's GERG-2008 ideal-gas segment. Written 2026-09-25 on `claude/coolprop-multiphase-thermo-37f6b0` on top of WP6b `f116a78`. The G3 fixture families F1 to F10 and the qualification report are WP9b (after WP7). Paths are relative to `src/main/java/com/wormzjl/createcheme/science/material/` unless stated.

## 20. What changed

| Path | Change |
|---|---|
| `ReferenceSpine.java` | `CEA_GAS_CONSTANT = 8.31451`; a `nasa9`/`nasa7` segment without `gas_constant_j_per_mol_kelvin` takes it, a segment whose revision starts with `cea-` must carry exactly it; new Helmholtz term `planck_einstein_cosh` {`n`, `t` > 0}; Javadoc of the closed forms and of the sinh mapping |
| `src/main/resources/data/createcheme/materials/spine/{nitrogen,ethane}.json` | r1 -> r2: CEA segments and S° with R = 8.314510 |
| `.../spine/carbon_dioxide.json` | r2 -> r3: the same (the WP3 sub-triple segment unchanged) |
| `.../spine/methane.json` | r1 -> r2: Setzmann-Wagner 90.6941-425 K, GERG-2008 ideal part 425-1200 K, no segment above 1200 K, grade `qualified`, S° with R = 8.314510 |
| `src/test/java/.../science/material/MethaneSpineTest.java` (new) | 5 tests (section 26) |
| `src/test/java/.../science/material/ReferenceSpineTest.java` | CEA holdouts without methane, gas-constant checks, the NASA default, methane join 425 K and ceiling 1200 K, cosh-term and CEA refusals |
| `src/test/java/.../science/material/PilotCryogenicCatalogTest.java` | pilot spine pin moved once (section 25), methane `maximumTemperature` 1200 K and grade `qualified` |
| `tools/spine-join-measurement/` (git-ignored) | `build-spine-records.mjs` extended (writes the main resources, parses GERG2008.cpp, prints the methane holdout); `cea-holdouts.mjs` R = 8.314510; P2 versions kept in `previous/`; README updated |

No other file changed; `MaterialCatalog` (the spine loader call) and the material index (file names only) needed no edit. Commit `271d84a` (the eight tracked files above, explicit paths; not pushed; no INDEX or CHANGELOG edit, WP11). The commit carries the session's actual model line (Opus 5.5) as co-author rather than the brief's Fable 5.1 line, as WP5 did.

## 21. The gas-constant rule

Every segment scales Cp, h and s with the gas constant its source defined (`gas_constant_j_per_mol_kelvin`, already a P2 field, checked within 1e-5 of CODATA 2018). New in WP9a:

- NASA CEA defines `gas_constant = 8314.5100d+00 ! J/kmol-K` (nasa/cea `3f4441d2`, `source/param.f90.in` line 32, confirmed by WP3), so every CEA segment of the four records now carries 8.31451 (was 8.314472, the value the P0 extraction script attributed to NASA/TP-2002-211556). The effect is +4.57e-6 relative on the CEA segments' Cp, h and s.
- Default: a `nasa9` or `nasa7` segment that omits the field takes `CEA_GAS_CONSTANT` (tested: bit for bit the same spine as the record stating it). A Helmholtz segment must state its constant (refused otherwise).
- Validation: a segment whose `revision` starts with `cea-` (the builder's CEA naming) must carry exactly 8.31451; P2's 8.314472 is refused with a message naming the segment and `param.f90.in`.
- The CEA standard entropies are recomputed from the CEA polynomial at 298.15 K and 1e5 Pa with 8.314510 (rounded to 1e-4 as in P2), their |CEA - JANAF| disagreement estimates with them.

## 22. GERG-2008 ideal part: term mapping and coefficients

Source: NIST AGA8 `GERG2008.cpp` (research copy `research/2026-09-24-coolprop-low-temperature/sources/nist-aga8/`), which codes the cp0 equation of Jaeschke and Schley 1995 as the GERG-2008 ideal part (Kunz and Wagner 2012):

| Line | Content | Used |
|---|---|---|
| 665 | `RGERG = 8.314472;` | only in the transcription test |
| 666 | `Rs = 8.31451;` | the segment's gas constant R* |
| 743 | `Tc[1] = 190.564;` | not needed (see below) |
| 1256 | `n0i[1][3] = 4.00088; n0i[1][4] = 0.76315; n0i[1][5] = 0.0046; n0i[1][6] = 8.74432; n0i[1][7] = -4.46921;` (and `n0i[1][1] = 29.83843397; n0i[1][2] = -15999.69151;`) | B0 and the four amplitudes; n0i[1][1..2] only fix h and s at GERG's reference state and are not carried |
| 1277 | `th0i[1][4] = 820.659; th0i[1][5] = 178.41; th0i[1][6] = 1062.82; th0i[1][7] = 1090.53;` | the characteristic temperatures, K |
| 402-458 | `Alpha0GERG`: j = 4, 6 are ln sinh terms, j = 5, 7 are -ln cosh terms, evaluated as th0i/T | the mapping |
| 1536-1546 | ideal-gas set-up: n0i[i][3] - 1, all terms times Rsr = Rs/RGERG | the reducing/reference form |

Form. Jaeschke and Schley: cp0/R* = B0 + C0 (D0/T / sinh(D0/T))² + E0 (F0/T / cosh(F0/T))² + G0 (H0/T / sinh(H0/T))² + I0 (J0/T / cosh(J0/T))², R* = 8.314510. GERG-2008 writes the same terms in tau = T_c/T with dimensionless theta = th0i/T_c; GERG2008.cpp keeps th0i in K and evaluates th0i/T. The record therefore uses `reducing_temperature_kelvin` 1 K so every `t` is the file's number in K, verbatim.

Mapping onto the loader's terms (the GERG segment of `methane.json`):

| GERG term | Record term | Numbers |
|---|---|---|
| B0 = n0i[1][3] | `log_tau` a = B0 - 1 (the loader's Cp/R = 1 + a + ...) | a = 3.00088 |
| n ln\|sinh(theta tau)\|, j = 4, 6 | `planck_einstein` with t = 2 theta | n = [0.76315, 8.74432], t = [1641.318, 2125.64] K |
| -n ln cosh(theta tau), j = 5, 7 | new `planck_einstein_cosh` with t = theta | n = [0.0046, -4.46921], t = [178.41, 1090.53] K |

Sinh mapping: ln sinh y = ln(1 - e^-2y) + y - ln 2. The first part is CoolProp's Planck-Einstein term at 2y (so theta doubles; doubling is exact in binary); the dropped y is linear in tau, contributes nothing to Cp or s and the constant n R theta T_r to h, which the loader's offsets absorb. Check: Cp of the loader form against the written-out sinh form to 1e-13 relative, h and s differences to 1e-12 (`sinhTermsArePlanckEinsteinTermsWithDoubledTheta`; the builder prints 7.8e-16 over 100-3000 K).

Closed forms of `planck_einstein_cosh` (alpha = -n ln cosh y, y = t tau): tau alpha_tau = -n y tanh y, tau² alpha_tautau = -n y²/cosh² y, so the term adds

- Cp/R: + n y²/cosh² y (evaluated as 4 n y² e^-2y/(1 + e^-2y)², no overflow),
- H/(RT): - n y tanh y,
- S/R: + n (ln cosh y - y tanh y) (ln cosh y = y + ln(1 + e^-2y) - ln 2).

Tested on a synthetic two-term record (100-3000 K, tau-scaled with T_r = 190.564 K) to 1e-10 against numerical calculus: dh/dT and ds/dT by a five-point stencil equal Cp and Cp/T; h and s differences equal the Gauss-Legendre integrals of Cp and Cp/T; h and s equal their definitions from alpha with alpha_tau by a five-point stencil in tau (`coshTermClosedFormsAgainstNumericalCalculus`).

Gas constant. GERG2008.cpp carries the ln delta part of alpha0 with RGERG = 8.314472 and the rest with Rs (Rsr = Rs/RGERG, lines 666-667 and 1542), so its ideal-gas Cp0 = RGERG + Rs (B0 - 1 + sum). The record uses the Jaeschke-Schley form with one constant, Cp0 = Rs (B0 + sum), R = 8.314510 as the WP9 brief states. The two differ by exactly Rs - RGERG = 3.8e-5 J/(mol K) in Cp (at most 8.9e-7 relative over 425-1200 K), (Rs - RGERG)(T - T1) in h and (Rs - RGERG) ln(T/T1) in s; `gergSegmentEqualsTheGerg2008CppTranscription` transcribes the set-up and `Alpha0GERG` and holds exactly these differences to rounding (1e-11 J/(mol K) in Cp).

## 23. The methane segment

`spine-methane-r2`: segment 1 CoolProp `Methane.json` alpha0 (Setzmann and Wagner 1991, unchanged terms) 90.6941-425 K; segment 2 GERG-2008 425-1200 K (`gerg2008-aga8-methane-ideal-jaeschke-schley-1995`); the spine ends at 1200 K and methane's ideal gas is refused above it (nothing is extrapolated). S° 186.3711 J/(mol K) (CEA polynomial, R = 8.314510; the polynomial is no segment of the record any more), ATcT formation enthalpy unchanged. Grade `qualified` with the evidence text of the record (D13 numbers).

Join. Cp at 425 K: Setzmann-Wagner 42.035685, GERG 42.038133 J/(mol K), step +5.82e-5 (+0.0058 %), 86 times below the 0.005 limit; h and s are continuous (1e-9, as every join). The SW-to-GERG step on the 25 K grid (builder output, %): 375 -0.0057, 400 -0.0002, 425 +0.0058, 450 +0.0109, 500 +0.0148, 600 +0.0028, 700 -0.0070, 800 -0.0020, 900 +0.0049, 1000 -0.0029. The smallest step on the grid is at 400 K (-2.2e-6), not 425 K: D13's "425 K (the smallest Cp step)" was measured for WP10's fitted `nasa9` option, not for GERG. The join follows D13 and the brief (425 K); the difference between the two joins is below 1e-4 in Cp everywhere between them (open item 1).

Holdout (`cpAndEnthalpyAgainstTheLineListReference`), against the ExoMol MM line-list reference of `P3_REFERENCES_WP8_WP10.md` B.3/B.4 (test constants; Cp to three decimals, h(T) - h(298.15) from the WP10 output `methane_cp_table.json` to 0.1 J/mol; nothing derived from it is bundled):

| T, K | Segment | Spine Cp, J/(mol K) | Line list | Reference declared error, % | Cp deviation, % | Spine h - h(298.15), J/mol | Line list | h deviation, J/mol |
|---|---|---|---|---|---|---|---|---|
| 300 | SW | 35.7775 | 35.767 | +-0.02 | +0.029 | 66.1 | 66.1 | +0.0 |
| 375 | SW | 39.2420 | 39.233 | +-0.02 | +0.023 | 2872.1 | 2871.2 | +0.9 |
| 500 | GERG | 46.5137 | 46.519 | +-0.02 | -0.011 | 8222.7 | 8221.7 | +1.0 |
| 600 | GERG | 52.4934 | 52.512 | +-0.02 | -0.035 | 13174.5 | 13174.7 | -0.2 |
| 700 | GERG | 58.1960 | 58.222 | +-0.02 | -0.045 | 18711.9 | 18714.4 | -2.5 |
| 800 | GERG | 63.5089 | 63.532 | +-0.02 | -0.036 | 24800.6 | 24805.7 | -5.1 |
| 900 | GERG | 68.3844 | 68.397 | -0.02/+0.03 | -0.018 | 31399.1 | 31405.9 | -6.8 |
| 1000 | GERG | 72.8025 | 72.796 | -0.02/+0.07 | +0.009 | 38462.2 | 38469.5 | -7.3 |
| 1100 | GERG | 76.7658 | 76.725 | -0.04/+0.21 | +0.053 | 45944.4 | 45949.4 | -5.0 |
| 1200 | GERG | 80.2948 | 80.188 | -0.10/+0.62 | +0.133 | 53800.9 | 53799.0 | +1.9 |

Worst Cp deviation 0.133 % (1200 K, inside the reference's own declared error there), D7 target 0.2 % met at every point; worst h deviation 7.3 J/mol (1000 K), target 0.1 % or 20 J/mol. At 300 to 375 K (Setzmann-Wagner) and 600 to 800 K (GERG) the spine lies 0.023 to 0.045 % from the reference, just outside its 0.02 % floor: Setzmann-Wagner and GERG agree with each other there within 0.015 %, so this is the shared residual of both equations, not the join. The methane spine's h(1000) - h(298.15) is 38 462.2 J/mol (r1: 38 685.2, the CEA excess of 223 J/mol removed).

JANAF C-067 lies 1.40, 1.64 and 1.85 % below the spine at 1000, 1100 and 1200 K (asserted 1.2 to 2.0 %, the documented disagreement: JANAF's methane behaves as a rigid-rotor harmonic-oscillator table, B.5). Against r1 the spine's Cp above 425 K drops by 0.1 % (500 K) to 1.6 % (1200 K) and h(T) - h(298.15) by about 15 J/mol at 600 K and 440 J/mol at 1200 K (WP10's B.6 figures for the same move).

## 24. The four records

| Record | Revision | S°(298.15 K, 1 bar), J/(mol K) (was) | Uncertainty given | Joins: T, Cp below -> above J/(mol K), step (P2 step) |
|---|---|---|---|---|
| nitrogen | `spine-nitrogen-r2` | 191.6097 (191.6088) | 0.0007 (\|CEA - JANAF\|) | 600 K 30.109102 -> 30.109230, 4.23e-6 (3.4e-7); 1000 K 32.696441, 2.2e-9 |
| methane | `spine-methane-r2` | 186.3711 (186.3702) | 0.1201 | 425 K 42.035685 -> 42.038133, 5.82e-5 (r1: 375 K to CEA, 2.1e-5) |
| ethane | `spine-ethane-r2` | 229.2211 (229.2201) | none stated | 500 K 77.915830 -> 77.916342, 6.57e-6 (2.0e-6); 1000 K 122.540066, 1.6e-9 |
| carbon_dioxide | `spine-carbon_dioxide-r3` | 213.7874 (213.7864) | 0.0076 | 216.592 K 33.176897, 0 (WP3); 975 K 54.006559 -> 54.002870, 6.83e-5 (7.3e-5); 1000 K 54.308733, 3.8e-9 |

The CoolProp segments and their joins are unchanged; only the CEA side of each join moved (+4.57e-6). The P2 joins are kept: re-running P2's rule (smallest step on the 25 K grid) with R = 8.314510 would move nitrogen to 425 K (1.25e-6) and ethane to 525 K (8.5e-7), steps that differ from the kept ones by less than 1e-5 and are all at least 73 times below the limit; CO2 stays at 975 K. The anchors stay bitwise (h(298.15) = ATcT, s(298.15, 1e5) = the record S°; every anchor lies in a CoolProp segment). The CEA holdouts of N2, C2H6 and CO2 (0.2 %) pass as before (section 26 output).

## 25. Pins

- Pilot spine fingerprint (`PilotCryogenicCatalogTest.PILOT_SPINE_FINGERPRINT`): `e750073dc6b43d41f7341bd6fa1c4255d0bb529baecf389325f838c7808b99ec` -> `1b33b193853cadea354a86779e88ddaa36aa4a9d8f937e6f7d492f0e9ccd323f`, one move for both decided changes, reason in the test's Javadoc. It moves the pilot's network revision and engine identity through `ThermoIdentity` (WP5 section 14), as designed; `SpineNetworkPathTest` and `SpinePackageEvaluatorTest` check the layout, not the value, and pass.
- Pilot scientific revision (`pilot-cryogenic-p3-r1:37a33a61...`) and physics fingerprint (`7378e199...`): unchanged (they do not see the spine), asserted.
- The eight bundled package pins (16 values, bundled and pilot-network catalogs): unchanged, asserted by `bundledPackageFingerprintsAreUnchanged`.

## 26. Tests and Gradle run

From Git Bash with `JAVA_OPTS=-Xshare:off`, `--offline`, under `build/gradle.lock` (tag `wp9a`), no dev client running (checked before each run), 2026-09-25:

    ./gradlew test --tests 'com.wormzjl.createcheme.science.material.*' --tests 'com.wormzjl.createcheme.science.fluid.thermo.SpineNetworkPathTest' --tests 'com.wormzjl.createcheme.science.thermo.phase.SpinePackageEvaluatorTest' --offline

(`verifyMaterialIndex` runs with `processResources`; the index lists file names only and did not change.)

1. 02:58: stopped in `compileJava` on WP7's in-progress `FluidThermodynamics` and `PassiveStepSolver` edits (not WP9a files).
2. 03:00: `compileJava` passed, stopped in `compileTestJava` on WP7's fluid-thermo tests, not yet updated to its `HydrocarbonModel` change.
3. 03:04: compiled; **86 tests, 85 passed, 1 failed, 0 skipped**: `science.material.*` 76 tests in 16 classes, all green (`AmbientViscosityTest` 1, `Cdu17TestCatalogTest` 3, `CrudeAssayConversionTest` 1, `CrystalReferenceTest` 4, `FluidAppearanceTest` 5, `GroupContributionInteractionsTest` 7, `LiquidMixtureCorrectionTest` 4, `MaterialAuthoringTest` 4, `MaterialCatalogTest` 8, `MaterialFluidDataTest` 2, `MaterialPresetsTest` 4, `MethaneSpineTest` 5 (new), `PilotCryogenicCatalogTest` 8, `PilotVolumeAnchorTest` 2, `ReferenceSpineTest` 11, `ViscosityTest` 7); `SpinePackageEvaluatorTest` 3 of 3; `SpineNetworkPathTest` 6 of 7. The one failure is `theNetworkRevisionIsTheThermodynamicIdentityAndMovesWithTheSpineAndTheInteractions` at its literal `assertEquals("pr78_translated_v1:direct-liquid-v1:spine-sensible-datum-v1", identity.evaluatorFamily())`: WP7's uncommitted `HydrocarbonModel` edit makes the family `pr78_translated_v1:direct-liquid-v1:tp-engine-v1:spine-sensible-datum-v1` (the FORMULATION bump WP5 section 19 asked WP7 for). It does not involve the spine; the other six `SpineNetworkPathTest` tests (flash grid, P1 reproduction, D7 isotherms, spine-range refusal, column refusal, the pilot model) pass on the r2 spines. WP7 owns the literal.
4. 03:26, after WP7 updated that literal (and moved the network's Wilson initializer to the engine): again **86 tests, 85 passed, 1 failed**, all 76 of `science.material.*` and 3 of `SpinePackageEvaluatorTest` green, `SpineNetworkPathTest` 6 of 7. The same test now passes every spine assertion (the identity carries the pilot spine fingerprint `1b33b193...`, printed `thermo-identity-v1;64:b0197b03...;72:pr78_translated_v1:direct-liquid-v1:tp-engine-v1:spine-sensible-datum-v1;64:1b33b193...;45:separate-free-water-v1:water-iapws-shomate-r1`; a methane S° edit moves only the spine part, a spine text edit moves nothing, the formation-enthalpy datum check holds) and fails later, at line 473, on the bundled spine-less network's revision literal (`f5e0178e...:direct-liquid-v1:...` expected, WP7's `95cd8e6d...:direct-liquid-v1:tp-engine-v1:...` found): WP7's in-progress formulation and fluid-domain edits, not WP9a. The flash grid printed 500 states, 17 two-phase, 0 non-convergence. The WP9a files were committed as `271d84a` between runs 3 and 4 and are unchanged since.

New and changed tests:

| Class | Tests | Holds |
|---|---|---|
| `MethaneSpineTest` (new) | 5 | record shape, grade, revision, join 425 K with its step pinned to 5.7e-5..5.9e-5, S° 186.3711; the line-list holdout (Cp 0.2 % at 10 temperatures 300-1200 K, h 0.1 % or 20 J/mol) and the JANAF disagreement (1.2-2.0 % at 1000-1200 K); the GERG segment against the GERG2008.cpp transcription (differences exactly Rs - RGERG); the cosh term against numerical calculus (1e-10); the sinh terms as `planck_einstein` with doubled theta (1e-13 / 1e-12) |
| `ReferenceSpineTest` (11, was 10) | +1 | `nasaSegmentsDefaultToTheCeaGasConstant` (new); `ceaSegmentAgainstTheHoldouts` without methane and asserting every CEA segment's 8.31451; the oracle test restricted to the `coolprop-` segments; joins with methane at 425 K; methane refused above 1200 K; refusals of a CEA segment with 8.314472 and of bad cosh terms |
| `PilotCryogenicCatalogTest` (8) | 0 | spine pin moved with its reason; methane `maximumTemperature` 1200 K; methane grade `qualified`; the eight bundled pins unchanged |

Also run standalone before the Gradle gate (javac and the JUnit launcher on the three classes against the Gradle-compiled main classes, while another agent's test sources did not compile): 24 tests, 0 failures, same printed numbers.

## 27. Known limits and open items

- **1200 K ceiling**: methane's ideal gas is unavailable above 1200 K (typed refusal); the pilot package's range and fluid domain end at 1200 K too, so nothing inside the package reaches it. A future extension needs a source that is right above 1200 K (the line-list reference itself is uncertain by -0.28/+1.67 % at 1300 K).
- **Upper-end uncertainty of the holdout**: the ExoMol reference's declared error grows to -0.10/+0.62 % at 1200 K (missing levels above 18,000 1/cm, B.3); GERG's +0.13 % at 1200 K is inside it, but the test's 0.2 % margin there is set by the reference, not by GERG. Narrowing needs an independent level density above 18,000 1/cm (WP10 open item 2).
- **Join temperature** (open item 1, for the lead): D13 fixed 425 K on WP10's measurement for the `nasa9` fit; for GERG the smallest grid step is at 400 K (-2.2e-6 against +5.8e-5 at 425 K). Both are far inside the limit; moving the join would be a record revision (r3) and one more spine pin move.
- **Kept P2 joins**: N2 and C2H6 would move under P2's rule with the corrected R (section 24); kept on purpose (steps of order 1e-6, no physics change), stated in the record source texts.
- **GERG2008.cpp vs Jaeschke-Schley gas constant**: the record follows the Jaeschke-Schley single-constant form (R* = 8.314510); the NIST code differs by 3.8e-5 J/(mol K) in Cp (section 22). Either choice is far inside every target.
- **Methane S° uncertainty** still carries |CEA - JANAF| = 0.1201 J/(mol K); the line list confirms CEA's value to about 0.0006 with the new R (B.5 item 3 gave 0.0015 against the r1 value). WP10 proposed replacing the estimate; not done here (not in the WP9a brief; texts are not hashed, so it would move no pin).
- **Targeted gate not fully green yet, for a WP7 literal**: `SpineNetworkPathTest` fails on the bundled network's revision literal that WP7's uncommitted formulation change moves (section 26, run 4); every WP9a class and every spine assertion passes. Re-run the targeted command once WP7 commits.
- **Share-alike**: nothing derived from the CC BY-SA line list is bundled (only the test constants, which are a holdout).
- **Detachment**: `MethaneSpineTest` and the updated classes are gate tests and stay; the builder extension lives in the git-ignored `tools/spine-join-measurement/` (P2 versions in `previous/`), to be copied to the main checkout's `tools/` with the batch at WP11. The pin printer used to compute the new fingerprint before the Gradle run was a scratch program outside the repository.

---

# WP2b (2026-09-25): Table S4 provenance

Follow-up to WP2 (sections 1 to 11). The owner provided the publisher's article PDF and Supporting Information of Jaubert, Qian, Lasala, Privat 2022 (FPE 560, 113456, doi:10.1016/j.fluid.2022.113456); the lead extracted Table S4 (the 40-group Akl/Bkl matrix) from the docx's MathType objects into `research/2026-09-24-coolprop-low-temperature/sources/e-ppr78/jaubert-2022-si-table-s4.tsv` (`tools/eppr78-table-s4-extraction`; hashes in the sources `MANIFEST.md` section 5). WP2b rebuilt the bundled matrix from it. Written 2026-09-25 on `claude/coolprop-multiphase-thermo-37f6b0`, commit `f0d4cf8` on top of `7afa990`.

## 28. What changed

| Path | Change |
|---|---|
| `materials/group_interactions/eppr78_2022.json` | Revision `eppr78-2022-clapeyron-0778184-r1` -> `eppr78-2022-si-table-s4-r2`; 355 -> 356 pairs, one per numeric off-diagonal cell of Table S4, first = the lower-numbered group, A and B with the printed decimal text (for example `575.0`, `85.10`, `0.000`); the 424 NA cells are absent, as before. `source` and `provenance` rewritten (Supporting Information file, doi, both file hashes, the TSV hash, extraction method, the licence statements, the group-number confirmation, the zero-term census, and the transcription as history). Groups carry `number` and `name` only: `transcription_label` is dropped (no test or loader read it). 31,966 bytes, SHA-256 `426b821fb99bf90bd90d8892c5bfb049a20bad4f599bae62cacd6a81bacdd182` (LF), git blob `d9eef7f3063d717c7c68a49612a294e03b2d3f6b` |
| `science/material/GroupInteractionMatrix.java` | Javadoc names Table S4; the optional `transcription_label` check is removed (groups are `{number, name}`) |
| `science/material/Eppr78Groups.java` | Javadoc and the per-group name sources: Table S4 prints the 40 labels in number order and confirms every number, including the freon (22-27) and alkyne (38-40) order that WP2 had assumed from the transcription's columns. Names unchanged |
| `materials/interactions/pilot_cryogenic.json`, `materials/packages/pilot_cryogenic.json` | The `source` text and the `EPPR78_KIJ` advisory name Table S4 instead of the transcription (texts, not hashed) |
| `GroupContributionInteractionsTest` | `bundledMatrixCarriesTheFortyGroupsOfTheSchemeAndTheTableS4Pairs` (renamed): revision, 356 pairs, six cells r1 had wrong (CH3/CH_cyclic 293.4/170.9, CH_cyclic/CO2 216.2/-132.8, CH_cyclic/N2 331.5/389.8, C/CF2 479.0/1430, C/CF_double_bond absent, CH3/C2H2F4 158.5/356.5), and the zero-A census (one pair, CF3/CF2, A = B = 0); comments |
| `PairInteractionsTest`, `TranslatedPengRobinsonPairInteractionsTest` | Comments only: the six pilot values are Table S4's, unchanged from the transcription |
| `tools/eppr78-transcription-check/` (git-ignored) | New `build_group_interactions_s4.py` (the r2 builder, `--check` byte comparison); `check_pilot_kij.py` extended (record against Table S4, the row-by-row comparison with the transcription, appendix A from the record); `pilot-kij-output.txt` refreshed; README results of 2026-09-25. `build_group_interactions.py` stays as the r1 builder (history; it still reproduces the r1 record of `7afa990` byte for byte) |

## 29. Discrepancy classes, Table S4 against the transcription (r1)

Row by row over the 780 off-diagonal cells (`check_pilot_kij.py` section 2):

| Class | Pairs | Examples |
|---|---|---|
| Identical numbers | 274 | 55 of them differ in decimal text only (trailing zeros: `575.0` against `575`, `103.60` against `103.6`) |
| Transcription A = 0 with the printed B | 75 | whole rows of groups 18 CH2_alkenic (29), 11 CH_cyclic (19), 20 CH_cycloalkenic (13), 8 Caro (9), 25 CF_double_bond (9); also 12-36, 13-36, 30-36, 32-36 (NO2) |
| Transcription A = 0 with another B | 3 | 5-18 (60.29, printed 68.29), 8-32 (2259.4, printed 2559.4), 11-12 (389.8 = Table S4's B of 11-13) |
| Another B, same A | 2 | 1-27 (3565, printed 356.5), 19-21 (-495.5 = Table S4's B of 20-23) |
| Missing from the transcription | 2 | 4-24 C/CF2 (479.0 / 1430), 11-13 CH_cyclic/N2 (331.5 / 389.8) |
| In the transcription, NA in Table S4 | 1 | 4-25 C/CF_double_bond (A = 0, B = 1430 = Table S4's B of 4-24: the 4-24 cell shifted one column) |
| Absent from both | 423 | |

Total discrepancies 83 (the lead's first comparison, `jaubert-2022-si-table-s4-vs-record-r1.txt`, gives the same 274 / 83). Only one pair of the record is NA in Table S4 (4-25), not five. The six pilot pairs (5-6, 5-12, 5-13, 6-12, 6-13, 12-13: CH4, C2H6, CO2, N2) are identical, so appendix A is reproduced unchanged (48 of 48 cells with the appendix's ethane constants, 46 of 48 with the pilot record's, section 7).

## 30. Zero-A terms

Table S4 has no pair with A = 0 and B != 0. The 79 such rows of the transcription (section 11) were all transcription defects: 78 have a nonzero printed A, and 4-25 is NA in the table. One pair, CF3/CF2 (23/24), is printed A = B = 0.000; the rule drops it as a zero term. The loader's refusal of an A = 0, B != 0 term (`GroupContributionInteractions.resolve`, "undefined term") stays as a guard for other records, and `loaderRefusals` still exercises it on an edited record.

## 31. Pins

The record's revision string enters no identity: `GroupContributionInteractions.matrixId()`/`matrixRevision()` are not hashed; `fingerprint` (hence `scientificRevision()`), `physicsFingerprint` and `fluidThermoFingerprint` hash the rule, the scheme and, per resolved package pair, the groups, A, B and weight of its terms; `spineFingerprint` does not see interactions; `ThermoIdentity` is built from `fluidThermoFingerprint`, the evaluator family, the spine fingerprint and the water revision. Only the pilot package has a rule, and its six single-group pairs carry the same values. Therefore:

- pilot scientific revision `pilot-cryogenic-p3-r1:37a33a61...` and physics fingerprint `7378e199...` (`PilotCryogenicCatalogTest`): unchanged, asserted green in the full suite and again after the final record text;
- the eight bundled packages (no rule): unchanged, asserted (`BUNDLED_PINS`, `assertBundledPinsHold`);
- G3 families (`science.thermo.qualification.*`, pilot package only) and the network digests (`fluidScienceTest`, full suite): green, no literal edited.

No pin moved; `PilotCryogenicCatalogTest.java` was not edited. The pilot spine fingerprint seen moving in the second targeted run (section 32) is the parallel crystal work's, not WP2b's.

## 32. Gradle runs

From Git Bash with `JAVA_OPTS=-Xshare:off`, `--offline`, under `build/gradle.lock` (tag `wp2b`), no dev client running, 2026-09-25. Before the change the lead's counts were 1,226 (`test`) and 219 (`fluidScienceTest`); WP2b adds assertions, no test method.

1. `./gradlew test --tests "com.wormzjl.createcheme.science.material.*" --tests "com.wormzjl.createcheme.science.thermo.*" --offline`: 202 tests in 40 classes, 0 failures (`build/wp2b-gradle-targeted.log`).
2. `./gradlew test --offline`: 1,226 tests in 250 classes, 0 failures, 0 skipped, 2 min 51 s (`build/wp2b-gradle-full.log`).
3. `./gradlew fluidScienceTest --offline`: 219 tests in 58 classes, 0 failures (`build/wp2b-gradle-fluidscience.log`).
4. After the record's licence text was corrected (a provenance string only, section 33), the targeted command again: first stopped in `compileTestJava` on the parallel agent's half-edited `SolidPhaseEvaluatorTest`; then 202 tests, 199 passed, 3 failed (`build/wp2b-gradle-targeted-2.log`): `CrystalReferenceTest.carbonDioxideRecordIsResearchOnlyAndRefusesUse` (grade `ESTIMATED_DECLARED_ERROR` instead of `RESEARCH_ONLY`) and two `PilotCryogenicCatalogTest` spine-fingerprint assertions (`1b33b193...` -> `e844c171...`), all from the uncommitted CO2 crystal record of the parallel agent. Every WP2b class passed, and `pilotPackageIsBundled` passed its scientific-revision and physics-fingerprint assertions before failing on the spine line.

## 33. Notes and open items

- **Licence** (differs from the brief): the article PDF's first page states "© 2022 The Author(s). Published by Elsevier B.V.", open access under CC BY 4.0; the Supporting Information docx states no licence of its own. The MANIFEST (section 5) and the record's provenance record exactly that; the files stay in the git-ignored research folder and are not redistributed.
- **Table S4 as printed** repeats some cells: 8-11 equals 9-11 (-99.17 / -193.5), 18-23 equals 18-24 (155.4 / 154.4), A of 1-18 equals A of 5-18 (48.73), and nine pairs have A = B (for example 11-39 863.7, 8-40 518.5). They are taken as printed; the extraction cannot tell a typesetting repeat from a true value. No bundled species uses these groups.
- **Section 11 items resolved**: Table S4 acquired and compared row by row; the freon and alkyne numbering confirmed; the A = 0 rows settled (section 30).
- The fidelity of the TSV to the printed table rests on `tools/eppr78-table-s4-extraction` (the lead's); WP2b checks its hash and completeness.
- **Detachment**: nothing measurement-only entered a tracked path; the builder and check live in the git-ignored `tools/eppr78-transcription-check/` (worktree copy synchronised to the main checkout).

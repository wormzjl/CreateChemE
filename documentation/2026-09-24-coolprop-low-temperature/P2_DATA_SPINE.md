# P2 (data side): reference spine and crystal records

Batch `2026-09-24-coolprop-low-temperature`, work package P2 of `UNIFIED_MULTIPHASE_THERMO_PLAN.md` (plan section 4, decisions D1, D6, D7). Written 2026-09-24 on branch `claude/coolprop-multiphase-thermo-37f6b0`. The phase and equilibrium contracts of P2 are a parallel piece of work and are not described here; both build on the shared interface `science.thermo.IdealGasFunction` (commit `9ca0f9c`), which this work implements and did not change.

## 1. What exists

| Path | Content |
|---|---|
| `src/main/java/com/wormzjl/createcheme/science/material/ReferenceSpine.java` | Record kind `spine`: loader, validation, closed-form Cp/h/s, offsets, joins; implements `IdealGasFunction` |
| `.../science/material/CrystalReference.java` | Record kind `crystals`: loader, validation, completeness, the triple-point-anchored solid functions |
| `.../science/material/MaterialCoverage.java` | The coverage grade and evidence shared by both kinds |
| `.../science/material/Eppr78Groups.java` | The E-PPR78 group-name list (scheme `eppr78-2022`) with its sources |
| `.../science/material/MaterialCatalog.java` | Two new kinds; `Package` gains `spine`, `crystals`, `spineFingerprint`; `spines()`, `crystals()`, `requireSpine(package, component)`; the JSON helpers became package-private |
| `src/test/resources/data/createcheme/materials/{spine,crystals,components,properties,interactions,packages}/…` | The small model set (section 4) |
| `src/test/resources/materials/pilot-cryogenic-index.json` | The list of the model set's files |
| `src/test/java/com/wormzjl/createcheme/science/material/PilotCryogenicTestCatalog.java` | Bundled resources plus the model set, parsed with `MaterialCatalog.parse` |
| `.../science/material/{PilotCryogenicCatalogTest,ReferenceSpineTest,CrystalReferenceTest}.java` | The tests (section 6) |
| `tools/spine-join-measurement/` (git-ignored; copy to the main checkout's `tools/`) | The offline scripts that chose the joins and built the spine records (section 4.2) |

No bundled record changed and nothing reads a spine or crystal at runtime yet: the model set lives in the test resources only.

Commits on `claude/coolprop-multiphase-thermo-37f6b0`: `9ca0f9c` (the shared `IdealGasFunction` interface), `dc82327` (everything in the table above except `tools/`), `edcffb8` (the `CHANGELOG.md` line under `[Unreleased]` / `Added`). Not pushed.

## 2. Record kind `spine`: `materials/spine/<species>.json`

One record per species. Fields and rules (every rule refuses the record with an `IllegalArgumentException` naming the resource path and the field):

| Field | Rule |
|---|---|
| `schema_version` | 1 (catalog-wide rule) |
| `id` | `createcheme:spine_<species>`; lowercase stable identifier, unique within the kind (catalog-wide rules) |
| `component` | an existing `components/` identity |
| `revision` | stable identifier `[A-Za-z0-9][A-Za-z0-9_.:+-]{0,62}` (at most 63 characters, as for packages) |
| `source` | text |
| `molar_mass_kg_per_mol` | positive; in a package it must agree with the component's property record within 1e-4 relative |
| `formation_enthalpy` | `value_j_per_mol` (number), `uncertainty_j_per_mol` (non-negative), `temperature_kelvin` exactly 298.15, `source` |
| `standard_entropy` | `value_j_per_mol_kelvin` (positive), `pressure_pascal` exactly 100000, `source`; `uncertainty_j_per_mol_kelvin` optional (non-negative), absent when the source states none |
| `ideal_gas.maximum_cp_step_fraction` | optional, default 0.005, in (0, 1) |
| `ideal_gas.segments` | 1 to 16, ordered, contiguous: a segment starting before the previous end is refused as an overlap, after it as a gap; the union must contain 298.15 K |
| each segment | `type`, `temperature_min_kelvin` < `temperature_max_kelvin`, `source`, `revision`, `gas_constant_j_per_mol_kelvin` (the constant the coefficients were fitted with; within 1e-5 of CODATA 2018, which catches unit errors); Cp positive at both ends and the midpoint |
| `helmholtz_ideal_terms` | `reducing_temperature_kelvin`, `terms` (1 to 16): `log_tau` {`a`}, `power` {`n`, `t`}, `planck_einstein` {`n`, `t` > 0}, `planck_einstein_function_t` {`n`, `v` > 0, `critical_temperature_kelvin`}; any other term refused. CoolProp's lead and enthalpy-entropy offset terms are constants in h and s and are not carried |
| `nasa9` | `coefficients`: exactly 9 (a1..a7, b1, b2 as CEA prints them; b1, b2 are not used) |
| `nasa7` | `coefficients`: exactly 7 (a1..a5, a6, a7; a6, a7 not used); a two-range NASA 7 record is two segments |
| `groups` | `scheme` exactly `eppr78-2022`; `counts` non-empty, keys from the E-PPR78 name list, values positive integers |
| `coverage` | `grade` one of `qualified`, `estimated_declared_error`, `research_only`, `unavailable`; `evidence` text; a record without `coverage` is refused |

Closed forms, with tau = T_r/T and x = theta tau:

- Helmholtz terms: Cp/R = 1 + a − Σ n t(t−1) tau^t + Σ n x² e^−x/(1 − e^−x)²; H/(RT) = 1 + a + Σ n t tau^t + Σ n x e^−x/(1 − e^−x); S/R = (1 + a) ln T + Σ n(t−1) tau^t + Σ n[x e^−x/(1 − e^−x) − ln(1 − e^−x)]; theta = t for `planck_einstein`, v/T_c(term) for `planck_einstein_function_t`, as CoolProp's parser converts it.
- NASA 9: Cp/R = a1 T⁻² + a2 T⁻¹ + a3 + a4 T + … + a7 T⁴, H/R and S/R its integrals (McBride, Zehe and Gordon 2002).
- NASA 7: Cp/R = a1 + a2 T + … + a5 T⁴ and its integrals.

Offsets. The segment k holding 298.15 K (the lower segment when 298.15 K is a join) gets h(T) = ΔfH + [H_k(T) − H_k(298.15)] and s(T, 1e5) = S° + [S_k(T) − S_k(298.15)], so h(298.15) and s(298.15, 1e5) equal the record values exactly (bitwise). Every other segment is referred to its join on the anchor side, h(T) = h_neighbour(T_join) + [H_k(T) − H_k(T_join)], and likewise for s; so h and s are continuous by construction. The pressure term is −R ln(P/1e5) with R = 8.31446261815324 (CODATA 2018) for every segment. At each join the loader measures |Cp_upper − Cp_lower|/Cp_lower and refuses the record above `maximum_cp_step_fraction`. A call outside the segments, with a NaN temperature, or with a non-positive pressure is refused with an `IllegalArgumentException` that names the range; nothing is extrapolated. At a join the lower segment is evaluated. Evaluation allocates nothing (a scan over at most 16 segments and sums over primitive arrays) and is a pure function of the record.

## 3. Record kind `crystals`: `materials/crystals/<species>_<crystal>.json`

Pure chemical solids; the inert particles of `solids/` stay a separate kind. Fields:

| Field | Rule |
|---|---|
| `id`, `component`, `revision`, `source`, `coverage` | as for `spine` |
| `crystal` | identity text |
| `heat_capacity.segments` | 1 to 16, ordered, contiguous (overlap and gap refused); `polynomial` {`coefficients` c0..c7, Cp = Σ c_k T^k} or `table` {`temperatures_kelvin` increasing, first and last equal to the segment bounds; `values_j_per_mol_kelvin` positive; linear interpolation}; `source`; optional `estimated`; Cp positive |
| `molar_volume` | `reference_temperature_kelvin`, `reference_pressure_pascal`, `value_m3_per_mol` (positive), `source`, optional `estimated`; `thermal_expansion_per_kelvin`, `isothermal_compressibility_per_pascal` (positive) and the range `temperature_min_kelvin`, `temperature_max_kelvin`, `pressure_min_pascal`, `pressure_max_pascal` may be absent; when present the range must be valid and contain the reference state |
| `transitions` | list, possibly empty, of {`temperature_kelvin` strictly inside the heat-capacity range, `enthalpy_j_per_mol` positive, `volume_change_m3_per_mol`, `to_crystal`}; temperatures increasing |
| `anchor` | `type` exactly `triple_point`, `temperature_kelvin`, `pressure_pascal`, `fusion_enthalpy_j_per_mol` (positive), `source`, optional `estimated` |

Completeness. A record whose heat capacity or molar-volume range does not reach the anchor, or that lacks the expansion, compressibility or volume range, is incomplete. An incomplete record loads only with grade `research_only` or `unavailable` (otherwise refused, naming what is missing); `usable()` is false, `missing()` lists the gaps, and every evaluation throws `IllegalStateException`. No missing number is filled in.

Model (`CrystalReference`). Cp(T) from the segments, taken as pressure-independent; v(T, P) = v0[1 + α(T − T0) − κ(P − P0)] plus the volume changes of the transitions between T0 and T, which is consistent with a pressure-independent Cp because ∂²v/∂T² = 0. A transition counts once T is strictly above it and adds its ΔH, ΔH/T_tr and Δv. Public functions: `heatCapacity(T, P)`, `molarVolume(T, P)`, `enthalpyChange(T, P)` = h_s(T, P) − h_s(T_tp, P_tp), `entropyChange(T, P)`, `gibbsChange(T, P, s_anchor)` = Δh − TΔs − (T − T_tp) s_anchor, `anchorEntropy(μ_L, h_L)` = (h_L − μ_L)/T_tp − ΔH_fus/T_tp, and `gibbs(T, P, μ_L, h_L)` = μ_L + gibbsChange. The contract: the caller supplies the fluid model's liquid chemical potential μ_L and enthalpy h_L at the triple point; then g_s(T_tp, P_tp) = μ_L and h_s(T_tp, P_tp) = h_L − ΔH_fus by construction, so melting, sublimation and the triple point agree with the fluid model. The Gibbs energy difference needs the solid's absolute entropy at the anchor, which only the fluid anchor provides; that is why `gibbsChange` takes it as an argument.

## 4. The small model set

### 4.1 Records

| File (under `src/test/resources/data/createcheme/materials/`) | Content |
|---|---|
| `spine/nitrogen.json` | `createcheme:spine_nitrogen`, revision `spine-nitrogen-r1`, grade qualified |
| `spine/methane.json` | `createcheme:spine_methane`, `spine-methane-r1`, grade estimated_declared_error (section 5.2) |
| `spine/ethane.json` | `createcheme:spine_ethane`, `spine-ethane-r1`, grade qualified |
| `spine/carbon_dioxide.json` | `createcheme:spine_carbon_dioxide`, `spine-carbon_dioxide-r1`, grade qualified |
| `crystals/carbon_dioxide_i.json` | `createcheme:crystal_carbon_dioxide_i`, `crystal-carbon_dioxide-i-r1`, grade research_only, incomplete (section 4.3) |
| `components/carbon_dioxide.json` | new identity `CarbonDioxide` |
| `properties/pilot_carbon_dioxide.json` | `createcheme:pilot_carbon_dioxide`: PR78 Tc 304.1282 K, Pc 7.3773 MPa, ω 0.22394 (CoolProp, Span and Wagner 1996); `normal_boiling_point_kelvin` carries the normal sublimation temperature 194.6855 K (Span and Wagner); standard liquid density 1046.88 kg/m³ at 250 K and 2 MPa from the Java oracle; a degree-5 Cp fit to the Span-Wagner alpha0 over 216.592..900 K (worst 0.13 %) only because the property loader demands one |
| `interactions/pilot_cryogenic.json` | all six pairs kij = 0, sourced as an explicit estimate |
| `packages/pilot_cryogenic.json` | `createcheme:pilot_cryogenic`, model pr78, components Nitrogen, Methane, Ethane, CarbonDioxide (properties `fluid_nitrogen`, `tjl20_methane`, `tjl19_ethane`, `pilot_carbon_dioxide`), `missing_interactions` error, `spine` and `crystals` set, 298.15..900 K (bounded by the methane and ethane property records), 100 Pa..2 MPa |

Sources of the spine records. Segment 1: CoolProp `EOS[0].alpha0` of the bundled fluid files (revision `ae81610e`, MIT; Span et al. 2000, Setzmann and Wagner 1991, Bücker and Wagner 2006, Span and Wagner 1996) from each triple point (63.151, 90.6941, 90.368, 216.592 K) with the file's gas constant. Above the join: NASA CEA `thermo.inp` (nasa/cea `3f4441d2`, Apache-2.0; records N2 tpis78, CH4 g 8/99, C2H6 g 7/00, CO2 g 9/99) to 6000 K with R = 8.314472 (NASA/TP-2002-211556). ΔfH: ATcT 1.220 via CoolProp master `9b35f538` (N2 0 ± 0, CH4 −74 513 ± 43, C2H6 −84 020 ± 120, CO2 −393 477 ± 15 J/mol). S°(298.15 K, 1 bar): the CEA polynomial with R = 8.314472 (N2 191.6088, CH4 186.3702, C2H6 229.2201 extrapolated 1.85 K below its 300 K interval, CO2 213.7864 J/(mol K)); CEA states no uncertainty, so the records carry |CEA − JANAF| as a disagreement estimate (N2 0.0002, CH4 0.1192, CO2 0.0086) and none for ethane. Molar masses are CoolProp's; against the property records the largest difference is N2, 2.9e-6 relative. Groups: N2 {N2: 1}, CH4 {CH4: 1}, C2H6 {C2H6: 1}, CO2 {CO2: 1}.

### 4.2 Join temperatures and Cp steps

Rule: the multiple of 25 K with the smallest Cp step between max(298.15 K, the CEA interval start) and min(the reference equation's published range, 1000 K), measured by `tools/spine-join-measurement/build-spine-records.mjs`. Each join lies above 298.15 K, so every anchor sits in the CoolProp segment.

| Species | CoolProp segment | Join | Cp step at the join (CEA vs CoolProp) | Next join (CEA 1000 K interval) | Step there |
|---|---|---|---|---|---|
| N2 | 63.151..600 K | 600 K | 3.4e-7 (−0.00003 %) | 1000 K | 2.2e-9 |
| CH4 | 90.6941..375 K | 375 K | 2.1e-5 (+0.0021 %) | 1000 K | 5.5e-10 |
| C2H6 | 90.368..500 K | 500 K | 2.0e-6 (+0.0002 %) | 1000 K | 1.6e-9 |
| CO2 | 216.592..975 K | 975 K | 7.3e-5 (−0.0073 %) | 1000 K | 3.8e-9 |

All steps are at least 68 times below the 0.005 limit. CO2 has a 25 K CEA first-interval segment (975..1000 K) because its smallest step on the grid is at 975 K; every species ends at 6000 K.

### 4.3 The CO2 crystal record and its sources

Filled only from open sources that could be cited on 2026-09-24:

| Field | Value | Source | Status |
|---|---|---|---|
| Triple point | 216.592 K, 517 950 Pa | Span and Wagner 1996 (as given in the brief; also the CoolProp file's `Ttriple` and the Wikipedia infobox citing Span) | not estimated |
| Fusion enthalpy | 9019 J/mol at the triple point | Air Liquide Gas Encyclopedia as quoted by Wikipedia "Carbon dioxide (data page)" | `estimated: true`, secondary |
| Solid Cp | 47.11 J/(mol K) at 146.48 K, 54.55 at 189.78 K, linear between | Giauque and Egan 1937 (J. Chem. Phys. 5, 45) as quoted by the same data page | `estimated: true`; the quoted 15.52 K point is omitted because a straight line to it would misstate the low-temperature region |
| Molar volume | 2.81753e-5 m³/mol at 194.65 K, 1 atm | 1562 kg/m³ (Wikipedia "Carbon dioxide" infobox, no primary citation) with the CoolProp molar mass | `estimated: true` |
| Thermal expansion, compressibility, volume range | absent | none found open | record refused for use |
| Transitions | none | phase I is the only solid phase in the range of Trusler 2011 | — |

Not sourced: solid Cp between 189.78 K and the triple point, thermal expansion, isothermal compressibility, and a primary value of the fusion enthalpy. The reference equations (Trusler 2011, JPCRD 40, 043105; Jäger and Span 2012, JCED 57, 590) are behind publisher walls (the NIST reprint server returned 503, figshare 403). The record is therefore `research_only`, `usable()` is false and every evaluation is refused; P4 needs one of those equations before a CO2 solid exists. NIST WebBook has no fusion enthalpy or solid Cp for CO2.

## 5. Reference results

All numbers in this section are the output of the Gradle run of section 7.1 (2026-09-24 22:00), printed by `ReferenceSpineTest`.

### 5.1 CoolProp segment

Cp, h(T) − h(298.15 K) and s(T) − s(298.15 K) at 1e5 Pa are held to the Java Helmholtz oracle (`HelmholtzFluid.idealDerivatives` on the same fluid file) at 1e-9 relative at 61 temperatures across each CoolProp segment (floor 1e-9 R·298.15 for h and 1e-9 R for s where the difference vanishes). Measured worst relative deviations, which are rounding level:

| Species | CoolProp segment | Cp | h(T) − h(298.15) | s(T) − s(298.15) |
|---|---|---|---|---|
| N2 | 63.151..600 K | 4.4e-16 | 1.2e-15 | 2.1e-14 |
| CH4 | 90.6941..375 K | 4.4e-16 | 4.4e-15 | 6.8e-15 |
| C2H6 | 90.368..500 K | 2.2e-16 | 3.2e-15 | 1.1e-14 |
| CO2 | 216.592..975 K | 6.7e-16 | 1.2e-14 | 5.1e-15 |

### 5.1a Joins, measured at load

| Species | Join | Cp below → above, J/(mol K) | Step | Join | Cp below → above | Step |
|---|---|---|---|---|---|---|
| N2 | 600 K (CoolProp → CEA) | 30.109102 → 30.109092 | 3.36e-7 | 1000 K (CEA → CEA) | 32.696292 → 32.696292 | 2.15e-9 |
| CH4 | 375 K | 39.241973 → 39.242805 | 2.12e-5 | 1000 K | 73.676124 → 73.676124 | 5.52e-10 |
| C2H6 | 500 K | 77.915830 → 77.915986 | 2.00e-6 | 1000 K | 122.539506 → 122.539505 | 1.64e-9 |
| CO2 | 975 K | 54.006559 → 54.002623 | 7.29e-5 | 1000 K | 54.308485 → 54.308485 | 3.77e-9 |

The largest step, CO2 at 975 K, is 68.6 times below the 0.005 limit. The join temperatures are pinned in `joinsAreContinuousWithASmallCpStep`.

### 5.2 CEA segment against the D7 holdouts

| Species | Holdout | 1000 K | 1100 K | 1200 K |
|---|---|---|---|---|
| N2 | JANAF N-023 | −0.002 % | +0.003 % | +0.003 % |
| C2H6 | NIST WebBook C74840 (Gurvich et al.) | −0.009 % | −0.009 % | −0.003 % |
| CO2 | JANAF C-095 | +0.001 % | +0.019 % | +0.009 % |
| CH4 | JANAF C-067 | **+2.620 %** | **+3.071 %** | **+3.545 %** |

Spine Cp against holdout, J/(mol K): N2 32.69629 / 32.697, 33.24194 / 33.241, 33.72409 / 33.723; C2H6 122.53951 / 122.550, 128.53833 / 128.550, 133.79623 / 133.800; CO2 54.30848 / 54.308, 55.41977 / 55.409, 56.34691 / 56.342; CH4 73.67612 / 71.795, 77.84877 / 75.529, 81.62729 / 78.833.

N2, C2H6 and CO2 meet the D7 target (0.2 %); the worst is CO2 at 1100 K, +0.019 %, ten times inside it. Methane does not: CEA's methane (Gurvich 1991) is 2.6 to 3.5 % above JANAF at 1000 to 1200 K, the reference disagreement P0 section 2.4 left open. The methane record is graded `estimated_declared_error` with that evidence, and the test holds the deviation inside 2.5 to 3.6 % so that a change of either value is noticed; it does not claim the D7 target. Deciding which reference is right needs an independent modern partition-function evaluation for methane (the open item of P0 section 3.3); the JANAF revision for ten organic molecules (Dorofeeva et al. 2001) does not cover methane.

### 5.3 Anchors, joins, consistency

h(298.15) equals ΔfH and s(298.15 K, 1e5 Pa) equals S° bitwise for all four species; s(T, 2e5) − s(T, 1e5) = −R ln 2 to 1e-12. The spine's values (1e5 Pa for s):

| Species | h(298.15), J/mol | s(298.15), J/(mol K) | Cp(298.15), J/(mol K) | h(1000) − h(298.15), J/mol | s(1000), J/(mol K) |
|---|---|---|---|---|---|
| N2 | 0.0 (ATcT, ± 0) | 191.6088 (± 0.0002) | 29.1253 | 21 462.2 | 228.1698 |
| CH4 | −74 513.0 (± 43) | 186.3702 (± 0.1192) | 35.7085 | 38 685.2 | 248.3321 |
| C2H6 | −84 020.0 (± 120) | 229.2201 (none stated) | 52.4742 | 64 433.8 | 331.6659 |
| CO2 | −393 477.0 (± 15) | 213.7864 (± 0.0086) | 37.1408 | 33 404.8 | 269.3047 |

Cp(298.15) is the CoolProp segment in every case (each anchor lies below its join). At every join h and s agree between the two sides to 1e-9 relative, also when evaluated at T_join ± 1e-6 K through the public functions; dh/dT = Cp and ds/dT = Cp/T within 1e-6 by central differences at 10, 50 and 90 % of every segment. Two independent parses give bitwise-identical Cp, h and s over the whole range (7.3 K grid) and identical fingerprints.

## 6. Fingerprints

Rule. `Package.spineFingerprint()` is the SHA-256 of the Gson JSON of [the numeric content of each spine record in component order, the numeric content of each crystal record in the package's order]; empty when the package declares neither. Numeric content: spine — component, molar mass, ΔfH value, S° value, the Cp step limit, each segment's type, bounds, gas constant and coefficients (Helmholtz: reducing temperature and every term's family and numbers), the group scheme and counts; crystal — component, crystal identity text, Cp segments, the molar-volume numbers (an absent field hashes as "absent"), transitions, anchor numbers. Not hashed: ids, revisions, source and evidence texts, coverage grades, uncertainties, `estimated` flags. The existing `fingerprint` (and so `scientificRevision()` and `physicsFingerprint`) does not see the new records, so adding or editing a spine never moves a package's physics fingerprint, the neural-model binding or the fluid presets.

Proof that existing packages did not move. Before the change, a javac-run main against the compiled classes at `9ca0f9c` printed `scientificRevision()` and `physicsFingerprint(id, components)` of all eight bundled packages; after the change the same main against the new classes printed byte-identical output. `PilotCryogenicCatalogTest.bundledPackageFingerprintsAreUnchanged` pins those 16 values, for the bundled catalog and for the catalog with the model set added, and checks that no bundled package declares a spine or crystal:

| Package | scientificRevision (revision:fingerprint) | physicsFingerprint |
|---|---|---|
| bonga_tjl20 | crude-regrouped-r1:61dcf6df…5528f3 | b1b9f1eb…a6ceb8 |
| cold_lake_blend_tjl20 | crude-regrouped-r1:08620e3b…d7e3 | b1b9f1eb…a6ceb8 |
| dalia_tjl20 | crude-regrouped-r1:36ef9ad1…19fd | b1b9f1eb…a6ceb8 |
| tjl19_dwsim | crude-regrouped-r1:f74f6564…6b18 | 3361fe82…03b4 |
| tjl20_methane | crude-regrouped-r1:dc4be6bf…71e4 | b1b9f1eb…a6ceb8 |
| tjl20_methane_nitrogen | crude-regrouped-r1:c8e890f9…a341 | c9ff50b0…f854 |
| upper_zakum_tjl20 | crude-regrouped-r1:8274de88…45b2 | b1b9f1eb…a6ceb8 |
| wti_light_export_tjl20 | crude-regrouped-r1:9f0540a5…eacc | b1b9f1eb…a6ceb8 |

(Full values in the test.) The spine fingerprint is also tested to ignore a source-text edit and to move on a numeric edit of a spine (S°) or a crystal (fusion enthalpy), while the package `fingerprint` stays put.

The pilot package's own spine fingerprint, with the records as committed (spine r1 of the four species, CO2-I r1), is pinned in `spineFingerprintIsDeterministicAndSeesOnlyNumbers`: `440ecc7f4b52989497950bd2207df0b69a026dc38b4ef46ed23d376b03df5f6a`. A numeric edit of any spine or crystal record of the set, or a change of the hash rule, moves it and fails the test; the pin is then updated together with the record's revision.

## 7. Tests

Gradle: `./gradlew test --tests 'com.wormzjl.createcheme.science.material.*' --offline` (result in section 7.1).

| Class | Tests | Checks |
|---|---|---|
| `PilotCryogenicCatalogTest` | 5 | bundled pins (16 values, two catalogs); pilot spine-fingerprint pin; the set loads (components, spine ids, crystals, grades, zero kij, every spine an `IdealGasFunction` from its triple point to 6000 K); spine fingerprint determinism and what it sees; package rules refused: spine list length, spine/component mismatch, unknown spine, molar-mass mismatch above 1e-4, duplicate crystal, unknown crystal, crystal outside the components; record rules refused: missing coverage (spine and crystal), unknown grade, unknown component, bad revision |
| `ReferenceSpineTest` | 9 | CoolProp segment against the oracle (1e-9, worst deviations printed); CEA against the holdouts (0.2 %, methane declared); exact anchors and the pressure term (reference values printed); joins (continuity 1e-9, step below the limit, join temperatures); dh/dT = Cp and ds/dT = Cp/T; evaluation outside the range, NaN and P ≤ 0 refused with the range in the message; bitwise determinism of two loads; refusals: overlap, gap, Cp step above the limit, unknown group, non-integer count, empty group counts, wrong scheme, unknown segment type, unknown Helmholtz term, Planck-Einstein v ≤ 0, wrong coefficient count, gas constant in wrong units, Cp not positive, empty segment list, step limit outside (0, 1), 298.15 K not covered, ΔfH temperature, negative ΔfH uncertainty, S° pressure; NASA 7 against the equivalent NASA 9 form (synthetic two-range record) |
| `CrystalReferenceTest` | 4 | the CO2-I record is research-only, lists what is missing and refuses every evaluation; on a synthetic complete record: zero changes at the anchor, g_s(T_tp, P_tp) = μ_L, h_s = h_L − ΔH_fus, ∂g/∂T = −s, ∂g/∂P = v, dh/dT = Cp, ds/dT = Cp/T at 28 states; closed forms and the ΔH, ΔH/T, Δv jumps of a transition; refusals: transition outside the Cp range, transitions not increasing, overlap, gap, table bounds, non-positive table value, unknown Cp segment type, incomplete record with an admitting grade (absent expansion, anchor outside the range), anchor type, volume reference outside its range, missing coverage |

18 new tests.

### 7.1 Gradle result

Run 2026-09-24 22:00 in the worktree (no dev client running, `build/gradle.lock` held), from Git Bash:

```
./gradlew test --tests 'com.wormzjl.createcheme.science.material.*' --offline
```

BUILD SUCCESSFUL; 57 tests, 0 failures, 0 errors, 0 skipped: the 18 new tests (`ReferenceSpineTest` 9, `PilotCryogenicCatalogTest` 5, `CrystalReferenceTest` 4) and the 39 existing tests of the package (`MaterialCatalogTest` 8, `ViscosityTest` 7, `FluidAppearanceTest` 5, `LiquidMixtureCorrectionTest` 4, `MaterialAuthoringTest` 4, `MaterialPresetsTest` 4, `Cdu17TestCatalogTest` 3, `MaterialFluidDataTest` 2, `AmbientViscosityTest` 1, `CrudeAssayConversionTest` 1), unchanged. The full suite was not run (P2 changes no bundled record and nothing reads the new kinds at runtime; the 16 bundled fingerprint pins are the guard). The compile included the parallel P2 phase-contract sources as they stood at that moment.

## 8. Known limits and open items

- **Methane at 1000 to 1200 K**: CEA and JANAF disagree by 2.6 to 3.5 %; the D7 0.2 % target is not met for methane and is declared instead (5.2). An independent partition-function source must settle it before P3 qualifies methane above about 600 K.
- **CEA gas constant**: 8.314472 J/(mol K) is taken from NASA/TP-2002-211556 as the P0 extraction script states; the nasa/cea source code was not checked. A different R changes Cp by at most 6e-6 relative.
- **Ethane S°** carries no uncertainty (no second tabulation locally) and is evaluated 1.85 K below CEA's 300 K interval start.
- **CO2 crystal** is incomplete and refuses use (4.3); P4 needs Trusler 2011 or Jäger and Span 2012 (browser download route), then the record can be completed and graded.
- **E-PPR78 names**: the local PDFs name the classes of all 40 groups and the groups CH3, CH2, CH, C, CHaro, CH2,cyclic, CO2, N2, H2S, SH, H2O, CH/C cycloalkenic, H2, CO, He, Ar, SO2, O2, NO, COS, NH3, NO2, N2O. G5 (CH4), G6 (C2H6), G8, G9, G11 and G17 to G19 carry the names of the PPR78 papers the 2022 article cites, marked `literature` in `Eppr78Groups`; the freon groups G22 to G27 and the alkyne groups G38 to G40 have no local name and cannot be used. Table S4 (the Akl/Bkl matrix) confirms or corrects them in P3.
- **Transitions** are taken at a pressure-independent temperature; the Clapeyron slope of a solid-solid transition is not represented. No pilot crystal has a transition; ethane's II→I (D2) would need it.
- **Pilot package range** 298.15..900 K is set by the legacy methane and ethane property records; the spines reach from each triple point to 6000 K.
- **P3 items**: bundle the spine and crystal records (index, `verifyMaterialIndex`); retire `shifted_polynomial_5` in the pilot package (the property loader must then accept a property record without `ideal_gas_cp` when its package declares a spine, and the pilot property placeholder fit goes); build the E-PPR78 kij(T) from Table S4 and replace the zero-kij estimate; let the phase evaluators consume `IdealGasFunction` through `requireSpine`; decide whether the spine revision joins the package's thermodynamic identity (today `spineFingerprint` is separate from `scientificRevision()`).
- **Full suite**: only `science.material.*` was run (7.1). `Package` gained three components and the JSON helpers of `MaterialCatalog` became package-private; the full suite belongs to the batch's merge gate.
- **Pilot spine-fingerprint pin** (section 6): it pins the committed records; P3 edits of the spine or crystal numbers must update it with the record revision.
- **Detachment**: no measurement-only code is in a tracked path. The offline scripts are in `tools/spine-join-measurement/` of the worktree, to be copied to the main checkout's `tools/` with its `README.md` and a `tools/INDEX.md` row; the tests print the measured CEA deviations and join steps, which is part of the gate output, not a probe.

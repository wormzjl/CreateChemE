# P2 (phase and equilibrium side): phase contracts, equilibrium service, thermodynamic identity

Date: 2026-09-24. Batch `2026-09-24-coolprop-low-temperature`, unified multiphase thermo plan section 5, stage P2
(the phase/equilibrium half; the data half, the spine and crystal records, is `P2_DATA_SPINE.md`). Branch
`claude/coolprop-multiphase-thermo-37f6b0`, commit `cbaa791` (changelog `5100233`), on top of the data half's
`dc82327`. Status: implemented as standalone contracts with tests;
nothing in the fluid network or the column calls them yet (integration is P3).

## 1. What exists

New package `src/main/java/com/wormzjl/createcheme/science/thermo/phase/`:

| Type | Role |
|---|---|
| `PhaseEvaluator` | Contract of one family of fluid phase models over a component basis: `family()`, `components()`, `capabilities()`, `evaluate(T, P, amounts, PhaseRoot preference, workspace, out)`, `derivatives(...)` |
| `CubicPhaseEvaluator` | Family `pr78_translated_v1`: the translated PR78 (`TranslatedPengRobinson.residualOnly`) plus one `IdealGasFunction` per component; every phase at the state pressure |
| `PhaseState` | One evaluated phase: kind, crystal, root, root count, T, P, molar volume, h, s, g, u (ideal-gas and residual parts), compressibility factor, `ln phi` view, chemical potentials; caller-owned, `copy()` freezes |
| `PhaseDerivatives` | `d ln phi_i/d n_j`, `d ln phi_i/dT`, `d ln phi_i/dP`, `dv/dT`, `dv/dP`, `dh/dT` (whole `c_p`), `dh/dP`, with the state they were taken at; a block the family did not provide is refused |
| `PhaseKind` | `VAPOR`, `LIQUID`, `FLUID` (one physical root: dense, dilute or supercritical, not classified), `SOLID` |
| `SolidPhaseEvaluator` | Contract of a pure crystal, anchored at the triple point to the fluid family's chemical potential (`TriplePointAnchor`); a test double only (the solid CO2 model is P4) |
| `PhaseAmounts` | One phase of a result: label, crystal or null, T, P, mole numbers over the shared basis (nonnegative, some positive), frozen state |
| `EquilibriumResult` | Status, typed unsupported reason, domain violation, phases, coexisting states, classification, coverage grade with evidence, diagnostics, identity, competition; `conservationDefect(overall)`, aggregate `enthalpy()`, `internalEnergy()`, `entropy()`, `gibbsEnergy()`, `volume()` and their molar forms |
| `EquilibriumRequest` | Specification pair (TP, PH or UV), named species with amounts, requested `PhaseCompetition` |
| `EquilibriumService` | `tp`, `ph`, `uv` (with and without a caller-owned workspace), `contract()` |
| `FluidTpEquilibrium` | The P2 minimal TP implementation: fluid phases only, stability test, two-phase Rachford-Rice with successive substitution, product stability re-check; PH and UV refuse until P3 |
| `PhaseContract` | What a package promises: basis, `WaterParticipation`, qualified competitions, water-chemistry species, `PhaseDomain`, fluid coverage, `ThermoIdentity`; `forNetworkPackage(catalog, id, spine)` |
| `PhaseCompetition` | Crystals (sorted set) and a hydrate flag; `FLUID_ONLY` |
| `WaterParticipation` | `NONE`, `SEPARATE_FREE_WATER` |
| `PhaseDomain` | Envelope plus per-component ranges with the network's rule; returns the network's `ThermoDomainViolation` |
| `ThermoIdentity` | Package fingerprint + evaluator family + spine fingerprint + water model revision; `revision()` |

Changed existing class, additive only: `science/fluid/thermo/TranslatedPengRobinson.java` gains

- `residualOnly(components, interactions, translations)`: all heat-capacity coefficients zero, so `Values.molarEnthalpy()`
  is `h^R + P c` bit for bit and `Values.heatCapacity()` the residual `c_p` (its positivity check is skipped in this mode
  only; the adapter checks the whole `c_p`);
- `evaluateValues(...)`: `evaluate` without the record (the workspace's buffer), which `evaluate` now calls;
- `Values.physicalRootCount()` and `kernel()`.

The network's arithmetic is unchanged (the existing path computes the same expressions in the same order; see section 6
for the regression run).

Tests, `src/test/java/com/wormzjl/createcheme/science/thermo/phase/`: `CubicPhaseEvaluatorTest` (6),
`FluidTpEquilibriumTest` (7), `ThermoIdentityTest` (2), `SolidPhaseEvaluatorTest` (2), the shared fixtures
`PhaseTestSupport` (stub `IdealGasFunction`: the record's own `shifted_polynomial_5` fit or a constant `c_p`, so the tests
do not depend on the data spine's records), and the cost probe `FluidTpEquilibriumCostTest` (runs only with
`CREATECHEME_PHASE_COST=1`; measurement code, section 8).

## 2. Contracts and rules

### 2.1 Phase evaluator

- `evaluate` fills a caller-owned `PhaseState` at the state's own T and P on the preferred root (taken where the model
  has more than one). No reference pressure: a liquid is the cubic's liquid root at (T, P). This is the P1 direct path
  (`P1_ALPHA_AND_LIQUID_PATH_STUDY.md`, section 2.8); the network's 2 MPa `REFERENCE_PRESSURE` path and
  `GlobalLiquidResponse` are not used anywhere in the new package.
- `PhaseState.kind()` states the root situation met, not a classification: three physical roots give `VAPOR` (largest)
  or `LIQUID` (smallest) by the preference; one physical root gives `FLUID` for either preference.
- Spine convention (plan section 4, decision D6): `h = sum x_i h_ig,i(T) + h^R`,
  `s = sum x_i [s_ig,i(T, P) - R ln x_i] + s^R`, `g = sum x_i mu_i` with
  `mu_i = g_ig,i(T, P) + R T (ln x_i + ln phi_i)`, `u = h - P v`, `g^R = R T sum x_i ln phi_i`,
  `s^R = (h^R - g^R)/T`. The ideal-gas parts come only from the `IdealGasFunction`s given at construction; the
  `IdealGasFunction` convention puts the formation enthalpy at 298.15 K and the standard entropy at 298.15 K and
  1e5 Pa. Components with zero amount are absent: their functions are not evaluated, their chemical potential is minus
  infinity.
- Constant translation `c_i` (`v = v_PR + c`): adds `P c` to `h` and `g`, `P c_i/(R T)` to `ln phi_i`, nothing to `s`
  or `c_p`; it cancels from every equilibrium condition, so stability tests and flash iterations run on the untranslated
  kernel.
- Derivatives are declared per family by `Capability` (`FUGACITY_COMPOSITION_DERIVATIVES`,
  `FUGACITY_STATE_DERIVATIVES`, `VOLUMETRIC_DERIVATIVES`, `CALORIC_DERIVATIVES`); `PhaseDerivatives` throws
  `UnsupportedOperationException` for a block the last fill did not provide. The cubic provides all four.
- Errors: malformed input is `IllegalArgumentException`; a present component outside its ideal-gas function's range is a
  `ThermoDomainViolation` naming it; a state the cubic refuses (no physical root, a mechanically unstable root, a
  derivative at root coalescence) is the model's `IllegalArgumentException`/`IllegalStateException`.
- Identity: `family()` is part of `ThermoIdentity`; a change of the residual formulation is a new family id.

### 2.2 Solid evaluator (shape only)

`SolidPhaseEvaluator` has `family()`, `crystal()`, `component()` and `evaluate(T, P, anchor, out)` filling a one-component
`SOLID` state with `ln phi = (g_s - g_ig(T, P))/(R T)`, so its chemical potential compares directly with a fluid's. The
anchor is `TriplePointAnchor(component, T_t, P_t, mu_fluid)`: the caller evaluates the fluid family at the triple point
and passes the chemical potential; the solid contributes the rest from its own record. Melting, sublimation and the
triple point then agree with the fluid family by construction. The test double (constant `c_p` and volume) reproduces
the anchor to 1e-9 and satisfies `dg/dT = -s`, `dg/dP = v`.

### 2.3 Phase-resolved state and conservation

- `PhaseAmounts`: amounts over the contract's shared basis (the same species conserves across gas, liquid and solid
  through one basis), nonnegative and finite with some material; a frozen state at the same T and P; `SOLID` exactly
  when a crystal is named, and the state names the same crystal.
- Labels: on a `PhaseAmounts` the kind is the equilibrium's label. In a two-phase fluid split the denser phase is
  `LIQUID` and the other `VAPOR`; a single phase is labelled by its classification (`FLUID` for one root). The state keeps
  the root situation.
- `EquilibriumResult` status rules (enforced by the constructor): `CONVERGED` has a classification other than `NONE`, a
  coverage grade an answer can have, and phases, except `PURE_COEXISTENCE_UNDERDETERMINED`, which has no phases and
  exactly two coexisting states; `UNSUPPORTED` has a typed reason (`PHASE_COMPETITION_NOT_QUALIFIED`,
  `SPECIES_NOT_IN_PACKAGE`, `WATER_CHEMISTRY_NOT_MODELLED`, `NOT_IMPLEMENTED`) and grade `UNAVAILABLE`; `OUT_OF_DOMAIN`
  carries the `ThermoDomainViolation` and grade `OUT_OF_DOMAIN`; `NOT_CONVERGED` has grade `UNAVAILABLE` and the
  attempt's diagnostics. Only converged results have phases. A solid phase must name a crystal of the result's
  competition, and `SOLID_PRESENT` is set exactly when one is present.
- Classifications: `SINGLE_VAPOR`, `SINGLE_LIQUID` (one phase on that root of a three-root cubic), `SINGLE_FLUID` (one
  root, not classified), `SUPERCRITICAL_FLUID` (one pure component above its critical T and P), `VAPOR_LIQUID`,
  `PURE_COEXISTENCE_UNDERDETERMINED`, `SOLID_PRESENT`, `NONE`.
- `conservationDefect(overall)`: `max_i |sum_p n_(p,i) - overall_i| / overall_i`, with the total overall amount as the
  scale for a component the overall lacks. It and the aggregates throw `IllegalStateException` when there is no split
  (not converged, or pure coexistence).
- Diagnostics: feed verdict and `tm`, flash iterations, accepted extrapolations, Rachford-Rice steps, last `max |d ln K|`,
  the fugacity residual `max |ln f_i^L - ln f_i^V|` of the returned phases, the most negative `tm` of the product
  re-check, kernel and derivative calls.

### 2.4 Water participation (the P2 water decision as encoded)

`WaterParticipation.NONE`: water is not a component; a request carrying water names a species outside the package
(`SPECIES_NOT_IN_PACKAGE`). `WaterParticipation.SEPARATE_FREE_WATER`: water is a basis component that forms its own pure
liquid, never dissolved in the hydrocarbon phases and never dissolving them (the fluid network's model). It is valid
only inside the water model's declared domain (from the 273.16 K triple point, ice not modelled, to its enthalpy
correlation's limit; the network package's `PhaseDomain` carries that range and refuses water below it as
`OUT_OF_DOMAIN`), and only for species declared immiscible with water. No P2 package puts water into the equation of
state.

Refused as `WATER_CHEMISTRY_NOT_MODELLED`: any request whose competition includes gas hydrates, and any request with water
together with a species the contract lists in `waterChemistrySpecies` (ammonia and the like: dissolution and aqueous
non-ideality the approximation cannot describe). A contract cannot qualify a hydrate competition at all (no P2 water
participation models hydrates). The network package lists no water-chemistry species (it holds hydrocarbons and
nitrogen). A request with free water that passes these rules is `NOT_IMPLEMENTED` in `FluidTpEquilibrium`
("the separate free-water phase joins the phase engine in P3").

### 2.5 Phase competition

A package declares the competitions it is qualified for as whole sets (`Set<PhaseCompetition>`), never as independent
per-crystal switches, so two crystals are admitted together only if their joint competition is declared (plan section 3:
no contradictory switches). A request names the competition it needs; a competition outside the package's set is
`PHASE_COMPETITION_NOT_QUALIFIED`, never answered with fewer phases. `PhaseCompetition.FLUID_ONLY` is an explicitly
constrained calculation: every converged fluid-only result has `solidsAssessed() == false`, its competition is
`fluid-only`, and its coverage evidence ends with "fluid-only: solid phases not assessed". A qualified competition with
crystals is `NOT_IMPLEMENTED` in P2 ("solid phases compete from P4 on").

### 2.6 Domain

`PhaseDomain` applies the network rule (`FluidDomain`): inside only if inside the envelope and inside the range of every
carried component; outside, the violation of the carried component with the largest amount (temperature before
pressure), then of the envelope, as the same `ThermoDomainViolation` type the network raises. `PhaseDomain.of(FluidDomain,
basis)` wraps the network package's data; `PhaseDomain.open(...)` is a declared open domain for research and test
contracts on a sub-basis. The ideal-gas functions' own ranges are checked too (`CubicPhaseEvaluator.idealGasViolation`),
also as `OUT_OF_DOMAIN`.

### 2.7 Order of the service's checks

Species outside the package, then water chemistry (hydrates, water with a water-chemistry species), then the phase
competition, then the domain (TP only: a PH or UV request fixes no temperature, so P2 refuses it as `NOT_IMPLEMENTED`
right after the competition check), then what the implementation does not yet do (crystals, free water, PH, UV), then
the ideal-gas functions' ranges, then the calculation. Each is a typed result, never an exception; malformed requests are refused when the request is built.

## 3. Thermodynamic identity and cache invalidation

`ThermoIdentity(packageFingerprint, evaluatorFamily, spineFingerprint, waterModelRevision)`:

- `packageFingerprint`: `MaterialCatalog.fluidThermoFingerprint(packageId)` in `ThermoIdentity.of` (the package physics
  fingerprint, the volume references the translation is calibrated from, and every declared fluid domain);
- `evaluatorFamily`: `PhaseEvaluator.family()`, `pr78_translated_v1`;
- `spineFingerprint`: the data spine's fingerprint; the P2 data work adds it to the package, and until a package carries
  one the caller passes `ThermoIdentity.NO_SPINE` (`spine:none`);
- `waterModelRevision`: `water:none`, or `separate-free-water-v1:<water record revision>`.

`revision()` is `thermo-identity-v1` followed by each part length-prefixed (`;<length>:<part>`), so no two different
identities share a revision whatever characters the parts contain; equality and hash are by revision. Every
`EquilibriumResult` carries the identity of the contract that produced it.

Cache rule: anything that stores a number computed from a phase state, or a decision derived from one, keys on
`revision()` and is discarded, never migrated, when it changes (AGENTS.md: no compatibility work):

- the fluid network's thermodynamic revision (`ApproximationAnchor.thermodynamicRevision`, checked against saved
  islands in `FluidCheckpointStore`), which in P3 must include the identity once the network evaluates through the phase
  engine;
- the column's neural-model physics fingerprint and its eligibility check (`V3AnchorTransformerInitializer`,
  `V3NeuralRegistry`): a model trained under one identity is ineligible under another;
- regression fixtures and pins (chain-100, exact gates, presets) recorded under one identity;
- any approximation state built on the phase engine: anchors, warm starts, factorizations, stability or flash seeds.

## 4. TP algorithm as implemented (`FluidTpEquilibrium`)

1. Contract checks (section 2.7); the request's species are mapped onto the contract basis (fast path when the request
   names exactly the basis list); the EOS feed is the basis without water.
2. Feed stability: `TangentPlaneStability` (P1) on the evaluator's kernel with its default settings. `UNRESOLVED` is
   `NOT_CONVERGED`.
3. Stable feed: one phase on the stability test's feed root (the lower-Gibbs root).
   - One present component with three roots: if `|ln phi_L - ln phi_V| <= coexistenceTolerance (Z_V - Z_L)` (the
     first-order relative pressure distance to the kernel's equal-fugacity pressure, since
     `d(ln phi_L - ln phi_V)/d ln P = Z_L - Z_V`; default 1e-9), the result is `PURE_COEXISTENCE_UNDERDETERMINED`
     with both root states and no split.
   - Otherwise the state's kind decides: `SINGLE_VAPOR`, `SINGLE_LIQUID`, or for one root `SUPERCRITICAL_FLUID`
     (one pure component with T > Tc and P > Pc) or `SINGLE_FLUID`.
4. Unstable feed: two-phase flash.
   - Seed: the stability test's stationary composition `w` and the feed are evaluated on their roots; the one with the
     larger Z is the vapour side, so `ln K_i = +-(ln w_i - ln z_i)`. If that seed is within `trivialDistance` of the feed
     (instability proven by the feed's curvature alone), Wilson's K instead.
   - Rachford-Rice on the window between the poles `1/(1 - K_max)` and `1/(1 - K_min)` (a negative flash is allowed while
     iterating): safeguarded Newton with bisection, stopping at a relative step of 1e-15, at most 200 steps.
   - Successive substitution `ln K_i = ln phi_i^L(x) - ln phi_i^V(y)`, each phase on its own lower-Gibbs root (the
     liquid preferred for `x`, the vapour for `y` at a tie), with a dominant-eigenvalue extrapolation
     `ln K += d lambda/(1 - lambda)`, `lambda = (d_k . d_k)/(d_(k-1) . d_k)`, every 5 plain steps when
     `0 < lambda < 1` (capped at 20 in any `ln K_i`).
   - Converged when a plain step moves no `ln K_i` by `1e-10`; then one more Rachford-Rice with the newest K. Not
     converged after 1,000 iterations, when the K-values stop straddling 1, when `sum ln K_i^2 < 1e-8` (collapse), or when
     the final `beta` is outside (0, 1).
   - Amounts per component: the smaller of the two shares `beta K_i / (1 + beta (K_i - 1))` (vapour) and
     `(1 - beta) / (1 + beta (K_i - 1))` (liquid) is computed directly and capped at `n_i`, the larger is `n_i` minus it,
     so each component conserves to rounding, both amounts are nonnegative, and a trace share keeps its full relative
     precision (a difference would lose it; `traceSharesKeepTheirPrecision`).
   - Stability re-check of both products with the same test; any verdict other than `STABLE` is `NOT_CONVERGED` ("a third
     phase or another split; three-phase equilibrium is P3").
   - Both phases are evaluated with the adapter on their lower-Gibbs roots; the fugacity residual is recorded; the denser
     is labelled `LIQUID`.
5. Deterministic: fixed arithmetic order, no randomness; a reused workspace carries only prepared temperatures, which are
   recomputed bit-identically. The iterations allocate nothing; a call allocates the stability results, the result and
   about 32 bytes per kernel call (the kernel's `RootSelection` record, as in P1).

Settings (`FluidTpEquilibrium.Settings.DEFAULT`): `lnKTolerance` 1e-10, `maximumIterations` 1,000,
`accelerationCycle` 5, `coexistenceTolerance` 1e-9, `trivialDistance` 1e-8, stability `TangentPlaneStability.Settings.DEFAULT`.

## 5. Tests and results

### 5.1 Unit tests (Gradle, green)

Gradle run 2026-09-24 22:01 from Git Bash (an identical green run at 22:00 had its reports replaced by the data-spine
agent's run before they were read):

    CREATECHEME_PHASE_COST=1 JAVA_OPTS=-Xshare:off ./gradlew test --rerun \
      --tests 'com.wormzjl.createcheme.science.thermo.phase.*' \
      --tests 'com.wormzjl.createcheme.science.fluid.thermo.TranslatedPengRobinson*' \
      --tests 'com.wormzjl.createcheme.science.fluid.thermo.HydrocarbonModelTest' --offline

24 tests, 0 failures, 0 skipped: `CubicPhaseEvaluatorTest` 6, `FluidTpEquilibriumTest` 7, `SolidPhaseEvaluatorTest` 2,
`ThermoIdentityTest` 2, `FluidTpEquilibriumCostTest` 1 (enabled by the variable), and the regression of section 6:
`TranslatedPengRobinsonTest` 3, `TranslatedPengRobinsonDerivativesTest` 1, `HydrocarbonModelTest` 2. The tree held the
data-spine agent's `MaterialCatalog` changes, committed a minute later as `dc82327`/`edcffb8`, so the run's sources are
`edcffb8` plus the P2 files; the phase sources and tests also compile against `9ca0f9c` alone (javac over
`git archive 9ca0f9c` plus the P2 files, no Gradle), so they depend on nothing of the data half.

| Test | States | Reference | Outcome |
|---|---|---|---|
| adapter parity (`reproducesTheTranslatedModelAtTheStatePressure`) | 256: CH4/N2 at 100, 110, 120, 150, 200, 298.15, 400, 600 K x 0.1, 1, 3, 6 MPa x pure CH4, pure N2, 0.3/0.7, 0.7/0.3 x both roots | a `TranslatedPengRobinson` built the network's way (the package's cp fits with nitrogen's low segment, the same translations), evaluated directly | v, `ln phi`, dv/dT, dv/dP, dh/dP bit for bit on all 256; residual enthalpy (the reference's h minus its own ideal part) on the 96 states at or above 273.16 K within 1.4e-16 of \|h\|, bitwise at 298.15 K; whole h equal within 1e-12 with zero formation values |
| spine convention | 12: 110 K 2 MPa, 150 K 0.5 MPa, 400 K 3 MPa x CH4/N2 0.3/0.7 and pure CH4 x both roots | stubs with formation values (CH4 -74.6 kJ/mol and 186.3 J/(mol K), N2 0 and 191.6) against zero-reference stubs | ideal h bitwise; residual h and s unmoved, bitwise; h and s moved by exactly `sum x dHf`, `sum x S0` (1e-9); `g = h - T s = sum x mu`; `u = h - P v`; `Z = P v/(R T)`; an absent component's `mu` is minus infinity |
| root situation | CH4/N2 0.9/0.1 at 110 K 0.5 MPa; 0.5/0.5 at 400 K 3 MPa | | three roots: `LIQUID` and `VAPOR` by preference, liquid denser; one root: `FLUID` for both preferences, same volume bits |
| derivatives against central differences | 5: 250 K 1 MPa vapour, 110 K 2 MPa liquid, 300 K 6 MPa, pure N2 liquid 90 K 1 MPa, 150 K 0.2 MPa vapour 0.9/0.1 | central differences of the adapter's own values, steps 1e-3 K, 1e-5 P, 1e-6 mol | every block (`d ln phi/dn`, `d ln phi/dT`, `d ln phi/dP`, dv/dT, dv/dP, c_p, dh/dP) and `-dg/dT = s`, `dg/dP = v` within 1e-6 of the scale; worst 5.1e-7 (`d ln phi_CH4/d n_CH4` of the 150 K 0.2 MPa vapour, the difference quotient's own noise on a near-ideal gas) |
| adapter refusals | | | a present component outside its ideal-gas range: `ThermoDomainViolation` naming it (an absent one is ignored); foreign workspace, negative, all-zero or wrong-length amounts: `IllegalArgumentException`; refilling a frozen state: `IllegalStateException`; a derivative block the family did not provide: `UnsupportedOperationException`, forgotten at the next fill |
| TP binary (`methaneNitrogenSplitsAtTheSixtyStabilityStates`) | the 60 CH4/N2 states of `TangentPlaneStabilityTest`: 110, 120 K x 0.5 to 3 MPa by 0.5 x x_N2 0.1 to 0.9 by 0.2 | `TangentPlaneStability` on an independently built kernel (the dense mixing plan) | 60/60 `CONVERGED`, feed verdicts agree: 12 `UNSTABLE` all `VAPOR_LIQUID`, 48 single phase; worst conservation defect 0.0; worst `\|ln f_L - ln f_V\|` 9.0e-12; chemical potentials on the spine reference equal within 1e-9 RT; both products `STABLE` by the independent test; flash iterations mean 10.1, max 11; at most 154 kernel calls |
| pure nitrogen at 77.355 K | 90, 95, 100 kPa and `P_eq (1 - 2e-9)`; `P_eq (1 + 2e-9)`, 105, 110, 120 kPa; `P_eq (1 - 0.5e-9)`, `P_eq`, `P_eq (1 + 0.5e-9)` | the kernel's equal-fugacity pressure by bisection (within 0.2 % of 102.57 kPa; P1: 102,546.9 Pa) | `SINGLE_VAPOR` on the vapour root below, `SINGLE_LIQUID` on the liquid root above; within 1e-9 relative `PURE_COEXISTENCE_UNDERDETERMINED`, no phases, two coexisting states with `mu` equal within 1e-8 RT, `conservationDefect` and aggregates refuse; 150 K 6 MPa: `SUPERCRITICAL_FLUID` on the open contract, `OUT_OF_DOMAIN` (pressure above) on the network contract; equimolar CH4/N2 400 K 3 MPa: `SINGLE_FLUID` |
| network basis (`networkBasisSplitsAsTheStabilityTestSays`) | Tia Juana light assay + 5 mol% N2 on the network contract (21 slots, water 0) at 350 K 0.5 MPa and 900 K 0.1 MPa | the stability verdict | 350 K: `VAPOR_LIQUID`, defect 1.6e-16, fugacity residual 4.1e-13, 7 iterations, 51 kernel calls, water slot 0, grade `ESTIMATED_DECLARED_ERROR` with "solid phases not assessed", aggregates equal the phase sums (1e-12); 900 K: `STABLE`, one phase `SINGLE_FLUID`, defect 0 |
| trace shares | light gas (10 % each C1 to nC5, 30 % N2, 1e-4 each cut) at 300 to 900 K by 100 K x 0.5, 1, 2 MPa: 21 | | all `CONVERGED`, 18 two-phase, defect below 1e-13, worst `\|ln f_L - ln f_V\|` over all 20 components 5.6e-12 |
| contract refusals | network contract | | ammonia with material: `SPECIES_NOT_IN_PACKAGE` (with zero amount: absent, answered); free water: `NOT_IMPLEMENTED` (P3); a hydrate competition: `WATER_CHEMISTRY_NOT_MODELLED`; an unqualified crystal: `PHASE_COMPETITION_NOT_QUALIFIED`; CH4/N2 at 200 K: `OUT_OF_DOMAIN`, Methane below 293.15 K; CH4 at 3 MPa: pressure above; N2 with water at 250 K: `OUT_OF_DOMAIN` naming Water; PH and UV: `NOT_IMPLEMENTED` after the species check; a PH request to `tp`: `IllegalArgumentException`. Every refusal: no phases, classification `NONE`, the contract's identity, grade `UNAVAILABLE` (`OUT_OF_DOMAIN` for the domain) |
| water chemistry and qualified crystals | test package CH4/NH3 + water, NH3 declared a water-chemistry species, qualified {fluid-only, fluid+ammonia_solid} | | NH3 with water: `WATER_CHEMISTRY_NOT_MODELLED`; dry: `CONVERGED`, `RESEARCH_ONLY`; water without NH3: `NOT_IMPLEMENTED`; the qualified crystal: `NOT_IMPLEMENTED` (P4); an undeclared joint competition: `PHASE_COMPETITION_NOT_QUALIFIED`; contracts qualifying hydrates, `NONE` with a water component, water as its own water-chemistry species, a domain on another basis, or a fluid grade that is no answer: refused |
| determinism | 6: binary 110 K 0.5 MPa, 120 K 3 MPa, 120 K 2.5 MPa; network 350 K and 900 K; pure N2 90 kPa | fresh workspaces against one reused per service in reverse, then forward order | bitwise equal amounts, v, h, s, `ln phi`, counts, feed `tm`, fugacity residual |
| solid double (`SolidPhaseEvaluatorTest`) | a CO2 crystal with constant c_p and v anchored at 216.592 K and 517.95 kPa | | anchor reproduced within 1e-9; `dg/dT = -s` (1e-6), `dg/dP = v` (1e-4); a vapour and frost over a shared N2/CO2 basis conserve exactly and aggregate as phase sums; a solid outside the competition, `SOLID_PRESENT` without a solid, negative or empty amounts, a crystal label on a fluid state and a state at another T: refused |
| identity (`ThermoIdentityTest`) | | | each of the four parts moves the revision, equal parts give equal revision and hash, characters moved between parts give another revision; the catalog identity follows the package, the spine string and the water participation, is the same across catalog loads, and is the network contract's |

### 5.2 Scans (`tools/phase-equilibrium-scans/run.sh`, outside Gradle, 2026-09-24 22:03)

| Scan | States | Outcome | Two-phase | Flash iterations mean / max | Kernel calls mean / max | Worst conservation defect | Worst fugacity residual |
|---|---|---|---|---|---|---|---|
| binary field: CH4/N2, 95 to 190 K by 5 K, 0.1 to 5 MPa by 0.1 MPa, x_N2 0.02 to 0.98 by 0.04 | 25,000 | 19,895 `SINGLE_FLUID`, 1,070 `SINGLE_LIQUID`, 1,488 `SINGLE_VAPOR`, 2,537 `VAPOR_LIQUID`, 10 `NOT_CONVERGED` | 2,537 | 12.3 / 446 | 31.3 / 2,016 | 1.98e-16 | 1.27e-10 (140 K 4.1 MPa x_N2 0.82) |
| near-critical: 130 to 185 K by 5 K, 2.5 to 6 MPa by 0.02 MPa, x_N2 0.01 to 0.99 by 0.02 | 105,600 | 96,433 `SINGLE_FLUID`, 279 `SINGLE_LIQUID`, 344 `SINGLE_VAPOR`, 8,432 `VAPOR_LIQUID`, 112 `NOT_CONVERGED` | 8,432 | 17.0 / 806 | 30.1 / 2,026 | 1.98e-16 | 1.53e-10 (140 K 4.16 MPa x_N2 0.83) |
| network contract: assay + N2 and the light gas, 300 to 900 K by 20 K, 0.1 to 2 MPa by 0.1 MPa | 1,240 | 98 `SINGLE_FLUID`, 1,142 `VAPOR_LIQUID`; phase count equal to `FluidThermodynamics.flashTP` (no water) on 1,240/1,240 | 1,142 | 8.5 / 256 | 55.7 / 570 | 1.89e-16 | 2.25e-11 |

Reading:

- Every converged split conserves each component to 2e-16 and has equal fugacities to 1.5e-10 (the 1e-10 `ln K`
  stopping rule); no feed stability test was `UNRESOLVED`.
- The 122 `NOT_CONVERGED` binary states (0.09 % of 130,600) all lie at 130 to 185 K and 3.58 to 5.02 MPa, beside the
  methane/nitrogen critical locus, where the feed test proves instability but the tie line is short: 103 used the
  1,000-iteration substitution budget (last `max |d ln K|` 1e-5 to 2e-8), 17 collapsed onto the trivial solution, one
  lost the split and one converged to a split outside 0 < beta < 1. Each is a typed `NOT_CONVERGED` with its reason;
  none returned a wrong split. First-order substitution is slow where the dominant eigenvalue approaches one; a
  second-order finish is an open item for P3 (section 9).
- The 20-component network field agrees with the network flash on every phase count, including the light gas whose
  cut shares are 1e-4.

## 6. Regression of the changed class

`TranslatedPengRobinson` is the fluid network's thermodynamic model; its existing behaviour must stay bit-identical.

- The change is additive. On the existing path the same floating-point operations run in the same order: `evaluate` is
  `evaluateValues(...).phase()`, and `evaluateValues` holds exactly the three statements `evaluate` had; both public
  constructors run the old constructor body through the private one, which adds only the assignment of the
  `residualOnly` flag (false for them); the property check reads `!(residualOnly || cp > 0)`, which with the flag false
  is the old `!(cp > 0)`; `fill` gains one `int` assignment (`physicalRootCount`) after every `double` is computed.
  `kernel()` and `Values.physicalRootCount()` are new accessors.
- The class's existing tests, unchanged, are green in the run of section 5.1: `TranslatedPengRobinsonTest` 3,
  `TranslatedPengRobinsonDerivativesTest` 1, and `HydrocarbonModelTest` 2 (the network's hydrocarbon model built on the
  class).
- In the other direction, `CubicPhaseEvaluatorTest` shows the residual-only instance computes the full model's
  volume, `ln phi`, dv/dT, dv/dP and dh/dP bit for bit (256 states), and its enthalpy is the full model's minus the
  ideal part to 1.4e-16 relative.
- Not run, by the targeted scope of this stage: the fluid network and column suites. Nothing in them calls the new
  members, and the network's revision strings are untouched.

## 7. Cost

Measured by `FluidTpEquilibriumCostTest` in the Gradle run of section 5.1 (2026-09-24 22:01), in the single test JVM
after the other phase tests: JDK 21.0.11, 16 processors. Before the run no dev client ran (the Codex `column-gui` dev
client had exited; checked by command line), no other Gradle build held the lock, only the idle Steam client was up;
23.4 GB of 47.6 GB free, CPU load 16 %. JIT warm-up: two passes of 3,000 calls on every state; then 200 timed calls per
state with one reused workspace. Allocation is `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` over 200
calls, with the reused workspace and with a new workspace per call (`tp(request)`). The binary states use the open
research contract, the others the network contract.

| Components, state | Result | Flash iterations | Extrapolations | Kernel calls (derivative) | Mean us | p95 us | Bytes/call, reused workspace | Bytes/call, new workspace |
|---|---|---|---|---|---|---|---|---|
| 2: 110 K 0.5 MPa x_N2 0.5 | VAPOR_LIQUID | 11 | 2 | 136 (15) | 17.30 | 18.40 | 5,448 | 11,784 |
| 2: 120 K 2.5 MPa x_N2 0.3 | SINGLE_FLUID | 0 | 0 | 23 (2) | 3.21 | 4.20 | 1,272 | 7,608 |
| 2: 120 K 3 MPa x_N2 0.5 | SINGLE_FLUID | 0 | 0 | 23 (3) | 3.02 | 3.60 | 1,272 | 7,608 |
| 1 of 21: N2 77.355 K 90 kPa | SINGLE_VAPOR | 0 | 0 | 5 (0) | 2.55 | 2.80 | 1,488 | 41,240 |
| 20 of 21: crude + N2 350 K 0.5 MPa | VAPOR_LIQUID | 7 | 1 | 51 (10) | 36.71 | 38.30 | 4,776 | 44,528 |
| 20 of 21: crude + N2 600 K 2 MPa | VAPOR_LIQUID | 11 | 2 | 68 (11) | 42.88 | 44.40 | 5,320 | 45,072 |
| 20 of 21: crude + N2 900 K 0.1 MPa | SINGLE_FLUID | 0 | 0 | 16 (4) | 13.24 | 14.20 | 1,776 | 41,528 |

Reading of the table:

- One TP call is 2.6 to 3.2 us for a single phase of one or two components, 13 us for the single-phase 20-component
  package, 17 us for the two-phase binary and 37 to 43 us for the two-phase 20-component package. The single-phase cost
  is the feed's stability test (P1: 1.3 to 13.9 us) plus one adapter evaluation.
- In a two-phase call the product re-checks dominate the kernel work: a stable phase runs every stability trial to the
  trivial solution. Binary: about 10 kernel calls for the feed test (P1), 28 to 50 for the flash (2 seeds, 11 iterations
  of 2 to 4 root evaluations, the final evaluations), and about 80 for the two re-checks. 20 components: 6 to 9 calls
  for the feed test, about 30 with 8 of the 10 or 11 derivative calls for the re-checks, and the rest for the flash.
  With P1's per-call costs (0.14 us per value call, 1.12 us per derivative call at 20 components) the kernel accounts
  for about 17 us of the 37 us at 350 K; the rest is per-component `exp`/`log` of the trials and the substitution, the
  two adapter evaluations with their ideal-gas functions, and the result's frozen copies.
- With a reused workspace a call allocates 1.3 to 5.4 kB: the stability results, the result with its frozen states and
  amount arrays, and about 32 bytes per kernel call (the kernel's `RootSelection`). A new workspace per call costs
  7.6 to 11.8 kB for two components and 41 to 45 kB for the 21-slot network basis (the evaluator's and the stability
  test's n x n blocks).
- The scans (section 5.2) averaged 30 to 31 kernel calls per call for the binary fields and 56 for the network field;
  a call that exhausts the 1,000-iteration budget costs about 2,000 kernel calls, about 0.25 ms at the binary's
  0.13 us per call.

## 8. Measurement code to detach before merge

Tracked, to leave the code before the batch merges (AGENTS.md, test classes and tools after a batch):

- `src/test/java/com/wormzjl/createcheme/science/thermo/phase/FluidTpEquilibriumCostTest.java`: the cost probe of
  section 7, enabled only by `CREATECHEME_PHASE_COST=1` (skipped in every normal run). No gate runs it. It goes to the
  main checkout's `tools/<folder>/` (for instance next to P1's `TangentPlaneStabilityCostTest` in one
  `tools/coolprop-low-temperature-cost-probes/`) with a README naming the removing commit and how to re-attach it
  (`git show <sha>:<path>` equals the stored copy). It adds no Gradle switch, run configuration or product hook.

Kept in the code: the gate tests `CubicPhaseEvaluatorTest`, `FluidTpEquilibriumTest`, `SolidPhaseEvaluatorTest`,
`ThermoIdentityTest` and their fixture `PhaseTestSupport` (also used by the scan). Their `System.out` summary lines
(parity, derivative, binary, network and light-gas summaries) are the record of section 5.1; they print a few lines,
measure nothing by time, and may stay or go with the gate decision.

Never tracked: `tools/phase-equilibrium-scans/` (`FlashScan.java`, `run.sh`, `README.md`), the scans of section 5.2,
staged in this worktree's git-ignored `tools/`. It is copied to the main checkout's `tools/phase-equilibrium-scans/` and
indexed in `tools/INDEX.md`; having never been tracked, it has no removing commit. The batch review names it.

## 9. Known limits and open items for P3

- **PH and UV** return `NOT_IMPLEMENTED` ("not implemented before P3"); their signatures and contract checks are in place.
- **Water-inclusive equilibrium**: free water is admitted by the contract but refused by `FluidTpEquilibrium`; P3 adds
  the free-water phase (its own pure liquid, water vapour in the gas) on the same result types, and the separate-water
  approximation's refusal set (water-chemistry species, hydrates) stays.
- **Solids**: only the contract and a test double; the solid CO2 model, crystal records and the vapour-liquid-solid
  competition are P4/P5. A qualified crystal competition is `NOT_IMPLEMENTED` today.
- **Three phases and liquid-liquid**: a two-phase split with an unstable product is `NOT_CONVERGED`; no three-phase
  flash. A liquid-liquid split, if one were found, would be labelled `VAPOR_LIQUID` by density order.
- **Single-phase classification**: one physical root is `SINGLE_FLUID` (not classified vapour-like or liquid-like), and
  `SUPERCRITICAL_FLUID` is only the pure-component rule; mixture supercritical classification (plan section 4: from
  mixture stability, not weighted critical constants) is P3.
- **Critical band**: the declared research-only critical neighbourhood (plan section 3, widened by P1) is not yet
  enforced in the coverage grade; the network contract's evidence says so.
- **Network integration**: nothing in the fluid network or the column uses the phase engine; the network still
  evaluates liquids through the 2 MPa `REFERENCE_PRESSURE` path with `GlobalLiquidResponse`. P3 replaces that path by
  the adapter (liquids at the state pressure), re-anchors the translations (D1: Tr = 0.8) and makes the network's
  thermodynamic revision include `ThermoIdentity.revision()`.
- **Translations**: `CubicPhaseEvaluator` takes the translations from the caller; the tests use P1-sized constants for
  N2/CH4 and zero for the 20-component flash (a flash does not depend on them).
- **Spine**: `ThermoIdentity` takes the spine fingerprint as a string. The data half now gives every package
  `spineFingerprint()` (`dc82327`: a hash of its spine and crystal records, empty for a package with neither, as the
  bundled network packages are); the integration step passes it, mapping the empty string to `NO_SPINE`, and builds the
  `CubicPhaseEvaluator`'s ideal-gas functions from the package's `ReferenceSpine` records. The P2 tests use stub
  `IdealGasFunction`s, not the spine records.
- **`PhaseSupport` and trace truncation**: the flash carries every present component in both phases; the network's
  frozen per-phase support (`PhaseSupport`, `TraceTruncationPolicy`) is not applied. P3 decides whether a truncated
  support is chosen from a converged phase-engine split and how its omitted amounts are reported in `PhaseAmounts`.
- **Near-critical flash convergence**: 122 of the 130,600 scanned binary states (all at 130 to 185 K and 3.58 to
  5.02 MPa, beside the mixture critical locus) end `NOT_CONVERGED`: accelerated successive substitution exhausts its
  1,000 iterations or collapses onto the feed where the tie line is short (section 5.2). The answer is typed, never a
  wrong split. P3 adds a second-order finish (Newton on `ln K` or on the Gibbs energy with the kernel's analytic
  `d ln phi/dn`, as the stability test already does) once substitution stalls, and decides how the critical band's
  coverage grade treats what still fails.
- **Cost of the product re-check**: in a two-phase call the two product stability tests take most of the kernel calls
  (section 7). P3 may run them only where a third phase is possible, or reuse the flash's own information; P2 keeps them
  on every split (correctness first).
- **Stability trial set**: inherited from P1 (only the two Wilson trials above 8 present components).
- **Allocation**: 1.3 to 5.4 kB per call with a reused workspace (the stability results, the result's frozen states,
  about 32 bytes per kernel call from the kernel's `RootSelection` record).

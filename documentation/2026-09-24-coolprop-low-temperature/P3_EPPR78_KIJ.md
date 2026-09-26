# P3 WP1: temperature-dependent pair interactions in the PR78 kernel (E-PPR78 kij(T)), kernel part

Date: 2026-09-24. Batch `2026-09-24-coolprop-low-temperature`, stage P3, work package WP1 of
[P3_PILOT_ENGINE_PLAN.md](P3_PILOT_ENGINE_PLAN.md) (sections 4.1, 4.2, 4.5 and the WP1 row of section 9).
Branch `claude/coolprop-multiphase-thermo-37f6b0`, base `5100233`. Status: implemented, tested and committed (`43d1081`); section 4 has the
Gradle run. Parent commit `6ec5c2c` (WP2 data). The data side (the `group_interactions` record, the interactions `rule`, loader refusals,
fingerprints) is WP2's and is documented by the data agent, not here.

Paths below are relative to `src/main/java/com/wormzjl/createcheme/` unless stated.

## 1. What exists

| Type | Change |
|---|---|
| `science/thermo/PairInteractions.java` (new, 403 lines) | The interaction model: per unordered component pair a constant `k_ij` or the E-PPR78 group-contribution `k_ij(T)`. Immutable; `evaluate(T, sqrt(a), d sqrt(a)/dT, d^2 sqrt(a)/dT^2, b, k, dk/dT, d^2k/dT^2)` fills caller-owned arrays and allocates nothing. Factories: `constant(double[][])` (one pair per nonzero `i < j` entry, row-major, the order `SPARSE_PAIRS` sums them), `none(n)`, `builder(n)` with `constant(i, j, k)`, `groupContribution(i, j, groupCountsI, groupCountsJ, GroupParameters)` (group counts over a group list, `GroupParameters` = symmetric zero-diagonal `A_kl`, `B_kl` in MPa) and `groupTerms(i, j, weights, aPascal, exponents)` (terms already resolved from the decompositions, the exact form `science.material.GroupContributionInteractions.Pair` of WP2 carries, so WP5 can bridge the catalog's rule without re-deriving group fractions). Accessors: `pairCount`, `first`, `second`, `pairIndex(i, j)`, `isGroupContribution`, `constantValue`, `temperatureDependent`. |
| `science/thermo/PengRobinsonKernel.java` | New plan `Mixing.TEMPERATURE_DEPENDENT_PAIRS` (lines 80-91), selected only by the new public constructor `PengRobinsonKernel(Tc, Pc, omega, PairInteractions, Tmin, Tmax, Pmin, Pmax)`; the two existing public constructors delegate to one private constructor and produce the same fields as before; the matrix constructor refuses `TEMPERATURE_DEPENDENT_PAIRS` (it needs a model). `interactions()` returns the model (null for the matrix plans). The workspace gains per-pair `pairK`, `pairKDt`, `pairKDt2` and their views (`pairInteractionsView()`, `pairInteractionTemperatureDerivativesView()`, `pairInteractionSecondTemperatureDerivativesView()`), sized zero for the matrix plans. |
| `science/fluid/thermo/TranslatedPengRobinson.java` | Additive: `TranslatedPengRobinson(components, PairInteractions, cp, translations)` and `residualOnly(components, PairInteractions, translations)` (the form `CubicPhaseEvaluator` uses, for WP5). The existing constructors and `residualOnly(components, double[][], translations)` reach the same private constructor with a null model and build the same `SPARSE_PAIRS` kernel as before (line 139). |
| `science/thermo/PengRobinson78.java` | Unchanged (not needed). |

No other file changed. The CLASSICAL, rank-one and SPARSE_PAIRS arithmetic is untouched: every added expression sits in
a branch taken only when `mixing == TEMPERATURE_DEPENDENT_PAIRS` or `pairIndex != null`, and the V3 column pins and the
network's `SPARSE_PAIRS` tests ran unchanged (section 4).

## 2. Formula and derivative terms as implemented

### 2.1 The pair value (`PairInteractions.evaluate`)

For a group pair (plan section 4.1), with `alpha_ik = count_ik / sum_k count_ik`:

    E_ij(T) = sum over group pairs k < l of  w_kl A_kl (298.15/T)^(n_kl),   w_kl = -(alpha_ik - alpha_jk)(alpha_il - alpha_jl),   n_kl = B_kl/A_kl - 1
    E' = -sum n w A f / T,   E'' = sum n (n + 1) w A f / T^2

A and B are in MPa at input and used in Pa (x 1e6); the exponent is formed from the MPa values. A group pair with
`A_kl = 0` (and then `B_kl = 0`, else refused) contributes nothing; a term with zero weight is dropped; a pair of two
single-group molecules is one term of weight 1, so one `pow` per pair per temperature.

The value is not computed as the plan's cross term `a_ij` followed by `k_ij = 1 - a_ij/sqrt(a_i a_j)` (that costs
about two significant digits of `k_ij` to cancellation when it is 0.003 to 0.01), but in the rearrangement of the formula multiplied
through by `(b_i b_j)^2`, which has no cancellation beyond the model's own difference `E - structural term`:

    s = sqrt(a) (kernel's sqrtA),  beta = b_i b_j,  d = s_i b_j - s_j b_i
    N = E beta^2 - d^2,             D = 2 beta s_i s_j,          k = N / D
    N' = E' beta^2 - 2 d d',        D' = 2 beta (s_i' s_j + s_i s_j'),
    N'' = E'' beta^2 - 2 (d'^2 + d d''),   D'' = 2 beta (s_i'' s_j + 2 s_i' s_j' + s_i s_j'')
    k' = (N' - k D') / D,           k'' = (N'' - 2 k' D' - k D'') / D          (from N = k D differentiated twice)

`s'` and `s''` are the kernel's own `d sqrt(a_i)/dT` and `d^2 sqrt(a_i)/dT^2` (`prepareRootDerivatives`), `a_i` and
`b_i` the kernel's Soave PR78 values (Omega_a 0.45724, Omega_b 0.07780): self-consistent with the mixture the kernel
forms, 1e-5 and 5e-5 relative from E-PPR78's own constants (plan 4.1). A constant pair returns its value and exact zeros.

### 2.2 In the kernel

`q_i = x_i sqrt(a_i)`, `dq_i = x_i d sqrt(a_i)/dT`, sums over the model's pairs (fixed at construction, row-major):

| Kernel place (line) | What the plan adds |
|---|---|
| `prepareTemperature` (265-282) and `prepareInteractions` (290-305) | After the unchanged pure-component loop: the root derivatives (only when a group pair exists) and `PairInteractions.evaluate` into `pairK`, `pairKDt`, `pairKDt2`. A non-finite value (the Soave `sqrt(a_i)` has a zero at `Tr = (1 + 1/kappa)^2`, N2 about 1390 K; or an overflowing exponent) is refused with the pair named, and the workspace's prepared temperature is invalidated first, so a refused temperature is never cached. O(pairs) per distinct temperature, cached with the pure-component values under the same exact-temperature key. |
| `evaluatePrepared`, value sums (342-367) | `g_i`, `S_i`, `a_mix` by `SPARSE_PAIRS`'s expressions on `pairK`; `da_mix/dT` by the same loop, then `-= 2 sum_pairs q_i q_j dk_ij/dT` (line 367). This `da_mix/dT` is the one recorded in the evaluation and used for `H^R` (unchanged line) and by the derivative bundle. |
| `evaluateDerivatives`, `d ln phi_i/dn_j` (`crossA`, line 498) | `1 - k_ij(T)` read through `pairIndex` (a composition derivative at fixed T: the value only). |
| `mixtureSecondTemperatureDerivative` (554-598) and `addInteractionTemperatureTerms` (610-626) | `crossDt` by `SPARSE_PAIRS`'s expressions on `pairK`, the shared `d^2a_mix/dT^2` loop, then `-= 4 sum_pairs (dq_i q_j + q_i dq_j) dk_ij/dT + 2 sum_pairs q_i q_j d^2k_ij/dT^2`; and `crossDt_i -= sum_(j paired with i) q_j dk_ij/dT`, which makes `crossDt_i` the whole `d(S_i/sqrt(a_i))/dT`, so `evaluateDerivatives`' unchanged `dS_i/dT = (d sqrt(a_i)/dT) g_i + sqrt(a_i) crossDt_i` (the plan's `sumADt` term) carries the interactions' own derivative. |
| `dH^R/dT`, partial molar residual enthalpies, `d ln phi_i/dT` | No new expression: they read `da_mix/dT`, `d^2a_mix/dT^2` and `dS_i/dT` above. |
| `TranslatedPengRobinson.fill` (`dP/dT`, `dv/dT`, `cp`, `d^2v/dT^2`, partial molar volumes) | No edit: it consumes `a`, `da`, `dda` (from `mixtureSecondTemperatureDerivative`) and `S_i`. |

For a constant pair every added term is an exact zero (`x - 0.0 = x`), so on `PairInteractions.constant(matrix)` the plan
is `SPARSE_PAIRS` on the same matrix bit for bit (tested, section 4); no separate old path was needed for constant
matrices, and the existing plans stay on their own unchanged code.

## 3. Cost

Measured with a scratch program outside the repository (javac against the compiled kernel, JDK 21, best of five
repetitions after two warm-ups, two rounds agreeing within 3 % except one 13 % outlier; machine shared with other agents'
builds, so absolute values are indicative). Synthetic species; the group model has one term per pair; "constant" is the
same kernel with the same number of constant pairs.

| Components, pairs | `prepareTemperature`, ns: SPARSE_PAIRS | TD, constant model | TD, E-PPR78 | `evaluateDerivatives` at a prepared T, ns: SPARSE / TD E-PPR78 | `evaluate`, ns: SPARSE / TD E-PPR78 |
|---|---|---|---|---|---|
| 4, 6 (pilot) | 22 | 27-34 | 154-156 | 204-206 / 213-216 | 98-101 / 95-97 |
| 20, 11 (network-like) | 45 | 61-63 | 286-288 | 1052-1056 / 1056-1069 | 137-139 / 148-149 |
| 20, 190 (every pair E-PPR78) | 45 | 264-273 | 3,609-4,113 | 1422-1583 / 1859-2136 | 300-306 / 458-462 |

About 20 ns per group pair per distinct temperature (one `pow`), as planned; per evaluation the plan's extra pair sums
are within noise at 6 and 11 pairs and add about 30-50 % at 190 pairs (the `dk/dT` sums and the `pairIndex` lookup in
the `n x n` composition block). A network node prepares once per distinct temperature and evaluates many trials at it.

## 4. Tests

New, all green (counts are the doubles or states each compares):

| Class (package `science.thermo` / `science.fluid.thermo`) | Tests | What it holds |
|---|---|---|
| `PairInteractionsTest` | 7 | Appendix A reproduction (below); closed form: 1,561 comparisons (6 pilot pairs x 223 temperatures 90-1200 K, plus a synthetic three-group pair with fractional group weights), max `abs(dk)` 7.8e-16 against the textbook formula evaluated in the test from the critical constants (gate 1e-12); derivatives: 108 pair-temperatures 92-1200 K, Richardson-extrapolated central differences, worst relative to `max(abs(derivative), abs(k)/T^n)`: `dk/dT` 5.3e-10, `d^2k/dT^2` 1.3e-9 (from `dk/dT`) and 7.9e-8 (from `k`) (gate 1e-6); the resolved-terms route equals the group route bit for bit; the pilot constants equal the bundled records; constant pairs; ten builder and parameter refusals. |
| `PengRobinsonKernelPairInteractionsTest` | 4 | Constant reduction: `TEMPERATURE_DEPENDENT_PAIRS` on `PairInteractions.constant(matrix)` vs `SPARSE_PAIRS`: 15,296 doubles bit-identical (pilot 4 components with 5 pairs, 20 synthetic components with 15 pairs, 20 with none; `ln phi`, Z, `h^R`, a, b, `da/dT`, root separation, `d ln phi/dT`, `d ln phi/dn`, `h^R_i`, `dh^R/dT`, `d^2a/dT^2` and its scratch vectors, both roots, both entry points). E-PPR78 derivatives at 20 pilot states (12 four-component, 8 binary), both roots, 40 evaluations, central differences, worst relative to scale: `da_mix/dT` 1.3e-10, `d^2a_mix/dT^2` 3.5e-10, `dh^R/dT` 3.6e-8, `d ln phi_i/dT` 4.1e-8, `d ln phi_i/dn_j` 4.2e-7 (512 entries), `sum x_i h^R_i = h^R` 1.1e-11 (gate 1e-6); the interactions' own term is up to 25.6 % of `da_mix/dT` at these states, so the check would see it missing. Reused workspace vs fresh: bit-identical. Refusals: matrix constructor with the new plan, basis mismatch, a non-finite pair (refused and not cached). |
| `TranslatedPengRobinsonPairInteractionsTest` | 2 | Constant reduction on the bundled network package (`createcheme:tjl20_methane_nitrogen`, 20 components, 11 pairs): `TranslatedPengRobinson(components, PairInteractions.constant(matrix), ...)` vs the matrix constructor, full and residual-only, 27,984 doubles bit-identical (every `Values` field, `d ln phi/dT`, `d ln phi/dP`, `h_i`, `h^R_i`, `dh^R/dT`, `d ln phi/dn`, both roots, `evaluateValues` too). E-PPR78 pilot model (P1 Tr 0.8 translations): 40 evaluations, worst relative: `cp` vs `dh/dT` 6.3e-9, `dv/dT` 7.4e-9, `d^2v/dT^2` 6.3e-8, `d ln phi_i/dT` 5.9e-8, `dv/dP` 2.7e-7, `dh/dP` 3.4e-7, `d ln phi_i/dn_j` 2.5e-7, `dH/dn_j` 1.7e-9, `dV/dn_j` 2.8e-9; identities `sum x_i h_i = h` 1.6e-15 and `cp = cp_ideal + dH^R/dT` 4.2e-15 (gate 1e-6). |

Appendix A of the plan to four decimals (`PairInteractionsTest.reproducesAppendixA`, bundled constants):

| Pair | 100 K | 150 K | 200 K | 250 K | 300 K | 400 K | 600 K | 900 K |
|---|---|---|---|---|---|---|---|---|
| CH4/C2H6 | 0.007245 | 0.006320 | 0.005900 | 0.005734 | 0.005726 | 0.006025 | 0.007526 | 0.012077 |
| CH4/CO2 | 0.121312 | 0.107402 | 0.104044 | 0.105729 | 0.110447 | 0.125882 | 0.174742 | 0.300951 |
| CH4/N2 | 0.033740 | 0.031463 | 0.029546 | 0.027640 | 0.025570 | 0.020464 | 0.002960 | -0.072890 |
| C2H6/CO2 | 0.173378 | 0.145313 | 0.133098 | 0.127931 | 0.126653 | 0.130797 | 0.154276 | 0.218441 |
| C2H6/N2 | 0.061011 | 0.049210 | **0.040560** | 0.033174 | **0.026254** | 0.012174 | -0.024413 | -0.147189 |
| CO2/N2 | 0.095535 | 0.037071 | 0.004959 | -0.015829 | -0.030606 | -0.050471 | -0.071232 | -0.067479 |

46 of 48 cells round to the appendix. The two bold cells round to 0.0406 and 0.0263 where the appendix prints 0.0405
and 0.0262. Cause found: the appendix was computed with CoolProp's ethane critical pressure 4.8722 MPa instead of the
bundled `tjl19_ethane` record's 4.872 MPa; with 4.8722 MPa all 48 cells round to the appendix (asserted in the same
test). The appendix's statement "PR78 constants (Tc, Pc, omega as in the bundled records)" is therefore off for ethane
by 200 Pa; its two C2H6/N2 cells should read 0.0406 and 0.0263. No other conclusion of the plan depends on it.

Existing tests, unchanged and green in the same run: see the Gradle record below.

Gradle (one invocation under `build/gradle.lock`, tag `wp1`, no dev client running):

    JAVA_OPTS=-Xshare:off ./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.*' \
      --tests 'com.wormzjl.createcheme.science.fluid.thermo.TranslatedPengRobinson*' \
      --tests 'com.wormzjl.createcheme.science.column.v3.thermo.*' \
      --tests 'com.wormzjl.createcheme.science.material.PilotCryogenicCatalogTest' --offline

Result (2026-09-24, about 23:10): BUILD SUCCESSFUL, 33 classes, 181 tests, 0 failures, 0 errors, 2 skipped (the
env-gated cost probes `TangentPlaneStabilityCostTest` and `FluidTpEquilibriumCostTest`). Of these, 13 are new (the three
classes above); the existing ones include the kernel's `PengRobinson78Test`, `PengRobinsonKernelRootPrecisionTest`,
`TangentPlaneStabilityTest`, the `science.thermo.phase` and `science.thermo.reference` classes,
`TranslatedPengRobinsonTest` (3) and `TranslatedPengRobinsonDerivativesTest` (1), all 16 `science.column.v3.thermo`
classes (95 tests, among them `V3CatalogParityTest` (exact) and `V3FeedFlashEquivalenceTest` (bitwise)), and
`PilotCryogenicCatalogTest` (5, including `bundledPackageFingerprintsAreUnchanged`, which pins the bundled package
fingerprints). The printed figures match the scratch runs above bit for bit. The tree also carried the other P3 agents'
uncommitted work in progress (WP2 data, WP4 direct liquid path, WP6a engine) at the time of the run; the numbers above
are for that combined tree.

## 5. Known limits and open items

- **Provenance of the numbers.** The six A/B pairs used in the tests are the Clapeyron.jl transcription (plan 4.5); the
  kernel is validated against the formula, not against Table S4. The functional check against GERG-2008 bubble points
  (plan 4.5 item 4, fixture of WP8) belongs to WP9.
- **High temperature.** `k_ij(T)` of the nitrogen pairs varies fast towards 1200 K because nitrogen's Soave `sqrt(a)`
  approaches its zero near 1390 K (C2H6/N2 -0.147 at 900 K); the formula is exact there but unreferenced (plan section
  10). At the zero itself the value is infinite and `prepareTemperature` refuses the temperature, naming the pair.
- **Zero constants are not pairs.** `PairInteractions.constant` and the builder drop a constant pair whose value is 0.0
  (no pair is the same as `k = 0`, and it keeps the order `SPARSE_PAIRS` uses); `pairIndex` then returns -1. A caller
  that lists which pairs were resolved, and how, uses the catalog's resolution, not `pairCount()`.
- **Signed zeros.** The bit-identity of the constant reduction holds for every value tested; in principle an exact
  `-0.0` in `da_mix/dT`, `d^2a_mix/dT^2` or `crossDt` would become `+0.0` after subtracting the exact-zero interaction
  term (`-0.0 - 0.0` stays `-0.0`, `-0.0 - (-0.0)` is `+0.0`). No physical state produces an exact zero there.
- **Not wired.** Nothing in the network, `CubicPhaseEvaluator` or the column constructs the new plan yet; WP5 builds the
  model from the catalog's resolved pairs (`groupTerms` takes `GroupContributionInteractions.Pair`'s arrays as they are)
  and passes it to the new `TranslatedPengRobinson` constructor or `residualOnly` overload.
- **Measurement code.** None tracked: the cost numbers of section 3 come from a scratch program outside the repository.

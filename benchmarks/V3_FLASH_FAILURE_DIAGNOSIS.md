# V3 feed-flash failure diagnosis - 2026-08-31

This is the pre-fix diagnosis against commit `76075d7`. The subsequent implementation and verification are documented in [root precision and flash-truncation results](V3_PRECISION_FLASH_TRUNCATION_RESULTS.md).

## Outcome and scope

The completed truncation, warm condenser-transition, strict audit, and performance work was committed first as **76075d7da385f650c42f5234e9840386e936eeea** (`Implement V3 stage trace truncation and warm condenser phase recovery`). The worktree was clean after that commit. Its regression evidence records 232 tests passing; see [the committed implementation report](V3_PHASE_TRANSITION_RESULTS.md).

The subsequent investigation identifies **loss of numerical accuracy in the one-real-root Cardano calculation in `V3PengRobinsonKernel.selectRoot`** as the cause of the captured feed-flash failure. This is not the earlier small condenser-vapor phase-audit mismatch, nor evidence that low-pressure operation is physically infeasible.

All production source and property data remain unchanged from that commit. New code is confined to diagnostic benchmark harnesses and their Gradle tasks. Root refinement below is an isolated counterfactual, not an installed production fix. Debugger and tracing runtimes are not clean performance benchmarks.

## Exact failing call

JDI exception observation of the public calculator captured the following state in each requested 50, 70, and 100 kPa run with truncation off:

| Quantity | Captured value |
| --- | --- |
| Package / revision | `createcheme:cdu17_tjl_acs2018` / `cdu17-tjl-kl1976-r2` |
| Temperature | 638.15 K (365 C) |
| Actual feed pressure | **137250 Pa** |
| Current continuation top pressure | **120000 Pa** |
| Geometry | 30 stages; feed stage 24; 750 Pa per stage |
| Caller | `V3ColumnCalculator.solveSingleProblem`, line 516 |
| Exception | `FLASH_NONCONVERGENCE`: `V3 feed flash did not converge within 64 iterations` |

The actual feed pressure is `120000 + (24 - 1) * 750 = 137250 Pa`. This is the shared 120 kPa step on the pressure-continuation path, **before any of the requested target pressures is reached**. It lies inside the package's declared 298.15-900 K and 50 kPa-2 MPa domain. The authored feed is flashed before the stage-truncation support is prepared.

Evidence: [100 kPa capture](results/v3-flash-investigation/100-off-capture.json), [70 kPa capture](results/v3-flash-investigation/70-off-capture.json), [50 kPa capture](results/v3-flash-investigation/50-off-capture.json). The normalized feed composition and terminal log-K / liquid / vapor arrays match across these captures. The diagnostic replay reproduces the captured exit arrays **bit-for-bit**.

## Numerical mechanism

The flash iterates K values toward the Peng-Robinson fugacity ratio. With half-step damping, it initially contracts normally, but its error then fluctuates above the unchanged `1e-10` log-K convergence criterion. Over iterations 30-64, the maximum log-K error ranges from `8.89226470235e-10` to `2.25081588923e-8`; iteration 64 ends at `2.05911128148e-8`.

For the liquid phase at iteration 64, the depressed cubic has:

| Quantity | Value |
| --- | --- |
| Reduced A / B | 0.3649214093189222 / 0.022388334423851432 |
| p / q | 6.617169287292946e-5 / 0.026968762003427415 |
| Discriminant | 1.8182853101010888e-4 |
| Cardano radicands `-q/2 +/- sqrt(D)` | 3.9791607508998794e-13 / -0.02696876200382533 |
| Smaller / larger radicand magnitude | 1.475470305361241e-11 |
| Production Z | 0.026059848574683075 |
| Refined Z | 0.02605984860161155 |
| Cubic residual, production / refined | -7.2632959784041605e-12 / 4.435643869792888e-19 |
| Cubic derivative at production Z | 0.26972555110008667 |

The code independently cube-roots both radicands. One is the tiny difference of two rounded numbers near 0.013484381: cancellation exposes their earlier rounding error, which is magnified in the small cube root. The resulting error in Z propagates into fugacity coefficients. At this iteration, correcting Z changes log-phi by as much as `6.99068536392e-9`; across iterations 30-64, the maximum correction is `2.12960387103e-8`, matching the observed flash noise scale.

This is not a near-multiple-root or root-switching event. Both separately composed phases have one selected real root throughout this replay. The liquid derivative is not near zero. A cancellation-avoiding Cardano comparison (calculate the larger radicand's cube root `u`, recover the other through `v = -p/(3u)`) independently gives Z `0.026059848601611535`, agreeing with Newton refinement to rounding accuracy. That stable formula was observed only, not used by the counterfactual.

General numerical background: closed-form cubic formulas can amplify roundoff; residual-based iterative improvement provides an independent check. This is discussed in [Kahan, *To Solve a Real Cubic Equation*](https://people.eecs.berkeley.edu/~wkahan/Math128/Cubic.pdf), sections 1-3. The diagnosis here rests on the captured code path and controlled replay, not on that reference alone.

## Controlled replay results

All controls use the same captured normalized composition, 638.15 K and 137250 Pa. Unless stated otherwise, they preserve 64 iterations, damping 0.5, the all-component `1e-10` convergence criterion, and the production Rachford-Rice / endpoint arithmetic.

| Diagnostic change | Result | Iterations |
| --- | --- | ---: |
| None; faithful production replay | `FLASH_NONCONVERGENCE` | 64 |
| Increase maximum to 512 | Success after prolonged error fluctuation | 208 |
| Full updates (damping 1.0) | `FLASH_NONCONVERGENCE` | 64 |
| Ignore zero-feed components in convergence check | `FLASH_NONCONVERGENCE` | 64 |
| Refine the cubic root and consistently refresh phase properties | **Success, error 6.90727475217e-11** | **36** |

Evidence: [faithful replay with root metrics](results/v3-flash-investigation/replay-root-observation-64.json), [extended limit](results/v3-flash-investigation/replay-extended-512.json), [full update](results/v3-flash-investigation/replay-full-update-64.json), [active-only check](results/v3-flash-investigation/replay-active-only-64.json), [root-refined replay](results/v3-flash-investigation/replay-polished-roots-64.json).

The successful refined flash is two-phase with beta `0.7855276025697608`. This is the **hot feed vapor fraction**, not the tiny condenser vapor fraction discussed earlier. No material is discarded and no convergence or acceptance tolerance is relaxed.

The refined 36-iteration result also agrees with the original algorithm's eventual 208-iteration result: maximum liquid-composition difference `7.59e-11`, vapor-composition difference `8.99e-12`, beta difference `3.97e-11`, and enthalpy difference `8.78e-7 J/mol`. Refined component material closure is within `2.78e-17` in mole-fraction units.

The root diagnostic reads current-phase mixture coefficients immediately after each evaluation. In refinement mode it changes only Z, log-fugacity coefficients, and residual enthalpy consistently. The latter is not used in the K-iteration stopping criterion. Observation mode does not mutate production arrays; this is checked by the bitwise-identical baseline. Production `evaluateInto` also avoids the public fugacity normalization workspace, so feed/phase-array aliasing does not explain this failure.

Limitations: the diagnostic Newton polish has finite-step, physical-Z, movement, and residual-decrease guards but is not bracketed between neighboring roots. Its success establishes the cause for this captured single-root case, not general qualification near coalescing roots. A production fix still needs branch-preserving root tests, broad property regression, and full-column validation.

## Pressure controls

The following are isolated feed flashes using the **unmodified** algorithm and crude data, with only pressure overridden. They do not run the full column or certify its operating point.

| Requested top pressure / control | Feed pressure (kPa) | Iterations | Result |
| --- | ---: | ---: | --- |
| 50 kPa target | 67.25 | 36 | Success |
| 70 kPa target | 87.25 | 36 | Success |
| 100 kPa target | 117.25 | 36 | Success |
| 110 kPa control | 127.25 | 36 | Success |
| Nearby pressure | 137.00 | 46 | Success |
| Failing continuation point | **137.25** | **64** | **Failure** |
| Nearby pressure | 137.50 | 36 | Success |
| 130 kPa control | 147.25 | 36 | Success |
| 140 kPa control | 157.25 | 36 | Success |
| 150 kPa control | 167.25 | 36 | Success |

Each override has a `pressure-<Pa>-default.json` report under [the investigation results](results/v3-flash-investigation). This demonstrates a localized numerical sensitivity, not monotonic deterioration as pressure falls. It also explains why the successful 110 kPa column case can avoid the defect: pressure continuation is triggered only at or below 100 kPa.

## Crude data audit

The package is a 16-hydrocarbon reduced characterization: methane (zero feed), ethane, propane, a C4 lump, and twelve pseudo-components. It is not a direct export of all properties from the paper's HYSYS model.

The published supporting information provides assay light-end volume percentages, a TBP curve, bulk density 867.6 kg/m3, and a characterization with 25 pseudo-components plus named light ends totaling 662.464 m3/h. Its Table S2 numbers are on a volume-percent basis despite the column heading "Volume Fraction". See [Ledezma-Martinez, Jobson and Smith, supporting information, Tables S1-S2](https://pstorage-acs-6854636.s3.amazonaws.com/11070299/ie7b05252_si_001.pdf).

Both complete relevant PDF pages (printed pages 2-3) were also downloaded and visually inspected after the web screenshot service returned cache misses; table headers, units, source markers, and total were checked against the extraction. The downloaded source and page renders are build-directory diagnostics, not changed project data.

Read-only reconstruction checks found:

- All twelve local lump volume percentages reproduce the corresponding sums of Table S2 rows. Published rounded rows sum to 99.98%; normalization by 99.98 is intentional.
- Volume-weighted boiling points reproduce the local values to their displayed rounding (maximum difference about 0.00505 K); PC12's 1035.975246548323 K agrees without that rounding.
- Conversion is `moles = standard liquid volume * density / molecular weight`, followed by mole normalization. Reconstructed feed mole fractions match the captured input to `1.39e-17`; volume percentages are not being incorrectly supplied to PR as mole fractions.
- The density-fit correction reconstructs exactly 867.6 kg/m3. Golden-vector specific gravities use the corrected densities divided by 999.016 kg/m3.
- The adopted average molecular weight is 229.6180315 g/mol. The source 662.464 m3/h reconstructs 2503.086376 kmol/h under this reduced package. The authored benchmark feed of 2610.7 kmol/h is a different throughput (about 690.944900 m3/h and 599463.795 kg/h), not a composition-normalization error.
- Re-evaluating the repository's Kesler-Lee formulas reproduces all twelve Tc/Pc/acentric vectors to rounding accuracy. Kelvin/Rankine and psia/Pa conversions are internally consistent. This is internal consistency, **not independent verification against the original 1976 paper**.
- All current ideal-gas heat capacities are positive over the declared temperature interval. They do not enter the TP flash K iteration, although they do affect the returned enthalpy and column energy balance.

Remaining data/model concerns are real but separate from the reproduced failure:

1. **PC12 eligibility is metadata only.** `vaporEligible=false` and the advisory say the residue is vapor-ineligible, but no production calculation consults the flag. The flash includes PC12 in vapor equilibrium. In the refined feed flash its vapor mole fraction is `3.21219224658e-6`, above the unused helper's `1e-6` warning trigger. The package emits an unconditional estimated-residue advisory, but does not enforce vapor exclusion or invoke the threshold helper.
2. **Source verification is unfinished.** The properties metadata explicitly marks independent second-reader checking of Kesler-Lee's 1976 coefficients as pending. Matching local golden vectors is not a substitute for that check.
3. **Heavy-cut and caloric accuracy is not established.** PC11/PC12 are marked extrapolated in provenance; PC12 uses an adopted 0.650 kg/mol residue surrogate. At 638.15 K, PC12 Cp is approximately 1703 J/(mol K), versus 625 for PC11. This remains a large difference on a mass basis: 2620.251 versus 1329.787 J/(kg K). It deserves independent caloric calibration; no evidence here establishes it as erroneous, and Cp cannot cause this K-iteration noise.
4. **Zero binary interactions and reduced lumping are modeling assumptions.** They are not experimentally validated by the current convergence/regression checks. Bulk-density closure alone cannot validate individual pseudo-component critical properties or phase splits.

Relevant local sources: [compiled package](../src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3Cdu17TiaJuanaPackage.java), [provenance metadata](../src/main/resources/data/createcheme/thermo/cdu17-tjl-kl1976-r2.properties), [component caloric functions](../src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3PropertyComponent.java), [characterization oracle](../src/test/java/com/wormzjl/createcheme/science/column/v3/thermo/V3CharacterizationOracleTest.java).

Conclusion: no demonstrated volume/mole, pressure-unit, temperature-unit, or gross property transcription mistake explains the captured failure. Keeping the data fixed and correcting only numerical root accuracy resolves it. This does not qualify the crude package as an independently validated physical reference.

## Why cutoff-on failures took longer

The [completed cutoff-on debugger capture](results/v3-flash-investigation/100-on-capture-complete.json) records the same feed failure twice: once in the requested truncated chain, once in the full untruncated retry. With a positive cutoff, the calculator maps a post-admission thermo exception to `NONCONVERGENCE`, allowing alternate-branch work and fallback. With cutoff off, the same exception maps immediately to `PROPERTY_OUT_OF_RANGE` and stops that branch chain.

The 33-36 second cutoff-on versus roughly 5 second cutoff-off cold measurements therefore include recovery work and repeated column prefixes. They do not measure the cost of one feed flash. The terminal `PROPERTY_OUT_OF_RANGE` label is misleading for a numerical flash nonconvergence inside the declared domain.

## Narrow next changes, not implemented here

1. Stabilize the PR cubic root calculation, with residual-checked refinement and branch-preserving safeguards; regress this exact captured state and nearby-pressure cases without loosening tolerances.
2. Distinguish numerical flash failure from actual property-domain rejection. Avoid repeating identical authored-feed failures through unrelated branch/truncation recovery while preserving legitimate recoverable attempts and cancellation behavior.
3. Rerun full cold column benchmarks at 50, 70, 100, 110, and 150 kPa, both cutoff settings, and retain strict independent phase/material/energy audits.
4. Separately settle the residue eligibility contract and independently verify/calibrate crude characterization and heat capacity data. Simply zeroing PC12 vapor or weakening audits would be a model change, not a justified numerical repair.

## Reproduction and evidence hygiene

Use JDK 21.0.11, no competing client or solver, serial execution. From this worktree:

```powershell
./gradlew.bat v3FlashFailureCapture --offline --no-daemon --console=plain '-PflashPressureKpa=100' '-PflashCutoff=0' '-PflashReport=build/reports/benchmarks/flash-capture-repeat.json'
./gradlew.bat v3FlashReplayProbe --offline --no-daemon --console=plain '-PflashReport=build/reports/benchmarks/flash-replay-repeat.json'
./gradlew.bat v3FlashReplayProbe --offline --no-daemon --console=plain '-PflashPolishRoots=true' '-PflashReport=build/reports/benchmarks/flash-polish-repeat.json'
./gradlew.bat v3FlashReplayProbe --offline --no-daemon --console=plain '-PflashReplayPressurePascal=67250' '-PflashReport=build/reports/benchmarks/flash-pressure-repeat.json'
```

The replay defaults to the saved capture from unmodified production code in this investigation directory; `-PflashInput=<capture.json>` selects another. Other explicit controls are `flashMaxIterations`, `flashRelaxation`, `flashSkipInactive`, and `flashEvent`. A successful Gradle invocation means the harness ran; inspect JSON `status` for the scientific result.

The preliminary `100-on-capture.json` contains a debugger `AbsentInformationException` from a generated frame and is **excluded** from conclusions. `100-on-capture-complete.json` is the valid rerun after making frame capture tolerate missing debug-local metadata. No failed evidence was silently overwritten. On an iteration-limit failure, captured `logK` is post-update whereas x/y and last evaluated fugacity correspond to pre-update K; the replay records both explicitly.

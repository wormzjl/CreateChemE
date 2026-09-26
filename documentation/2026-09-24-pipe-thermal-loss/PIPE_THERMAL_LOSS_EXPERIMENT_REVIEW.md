# Pipe thermal-loss experiment review

Date: 2026-09-24. Status: Concluded (research experiment; no merge).
Branch: `codex/pipe-thermal-loss-study`, based on `f9d6be1`.
Worktree: `C:/Users/wormz/.codex/worktrees/pipe-thermal-loss-study/CreateChemE`.
The candidate kernel and six new gate tests are uncommitted in that worktree. Main's tracked sources were not modified.

## Result

The owner's zero-inventory, velocity/length heat-loss proposal works well as a thermal estimate. At a prescribed flow, a fixed 17-point enthalpy/temperature curve met the +/-10% target in all 561 qualified cases, including condensation: maximum heat-loss error 7.103%, 95th percentile 0.976%. An adaptive 9-30-point curve reduced maximum error to 0.0461%.

A constant inlet heat capacity is not adequate across the population. Updating its effective heat capacity once still failed 27 condensing cases. An enthalpy curve captures latent heat without storing pipe fluid or invoking a flash inside the heat-exchange evaluation.

The column-style single hydraulic correction is not generally reliable if this feature also changes pressure drop using the cooled properties along the run. It exceeded 10% heat-loss error in 84/561 cases, with a 649% worst error in a strongly condensing steam case. This is a separate, stronger coupling experiment than the present engine's upstream-property hydraulic model. It does not establish that the current network needs full axial hydraulics, nor that every future network solve will show these errors.

Recommendation: carry forward the small enthalpy-curve heat law, with current mass flow used directly in its cheap evaluation. Keep current hydraulic modelling scope initially. Do not promise one correction will also approximate fully coupled cooling-dependent pressure drop across all conditions. Whole-network energy integration and dependency/cache management are the next implementation/qualification step.

## What was built

`src/main/java/com/wormzjl/createcheme/science/fluid/network/PipeHeatExchange.java`: an immutable experimental H-T curve and exact piecewise-affine integration of:

```text
dh / d(UA) = -(T(h) - T_ambient) / abs(massFlow)
heat loss = abs(massFlow) * (h_in - h_out)
```

For a constant-cp segment this is exponential velocity/length cooling. Equal-temperature enthalpy knots represent latent heat. Exactly zero flow gives zero heat power. Flow sign selects the donor outside this scalar kernel. Positive heat means loss; cold streams receive heat. The kernel owns no stock or clock, and is not wired into the game, checkpoints, replay or PassiveStepSolver.

`src/test/java/com/wormzjl/createcheme/science/fluid/network/PipeHeatExchangeTest.java`: six tests in the existing fluid-science gate for analytic sensible cooling/energy balance, latent heat, heating/reversal, subdivision and energy-reference invariance, zero/near-zero limits, and invalid/defensively owned curve data.

Offline tooling is in canonical `tools/pipe-thermal-loss/`, indexed in `tools/INDEX.md`. It was never inserted into tracked production/test source sets; an external Gradle init file compiles it. `prototype.patch` preserves the exact uncommitted branch additions against the base. No tracked investigation code was removed, so no removal commit applies.

## Population and reference

Ten fluid conditions: water heating/cooling, steam sensible cooling/condensation, methane heating/cooling, liquid/condensing pentane, crude mixture and a heavy liquid pseudocomponent. Bundled `FluidThermodynamics`, material data, mixture viscosities and `PipeResistance` supply properties/correlations.

The main population enumerated 600 combinations: length 1/10/50/150 m; initial velocity 0.002/0.02/0.2/1/3 m/s; prescribed U 2/15/60 W/(m2 K); diameter 0.05 m, roughness 0.000045 m. Uniform ambient and fixed representative thermodynamic pressure are deliberate study assumptions. Initial velocity defines an adiabatic pressure-drop boundary; coupled reference flow can differ.

Thirty-nine cases with initial adiabatic pressure drop above 5% of representative pressure were recorded as SCREENED_DP, not counted as successes. All remaining 561 completed. This screening limits, but does not eliminate, error from holding thermodynamic pressure fixed. It does not bound effects near phase boundaries or qualify compressible/two-phase real pipes.

The thermal reference uses 513-528 knots with extra phase-boundary refinement. Coupled reference pressure drop is integrated over 64 spatial samples and mass flow solved by a 40-step bracketed scalar root. Methods share prescribed U and the existing equilibrium/homogeneous hydraulic physics; errors are numerical/model-reduction errors against that reference, not +/-10% validation against physical measurements.

For the thermal-only comparison, all methods use the SAME reference mass flow. This isolates the heat-loss formula/table error from hydraulic feedback. Single-cp uses the inlet derivative; corrected-cp makes one secant update from its estimated outlet. Fixed 9/17-point tables are uniform in temperature with no phase refinement. Adaptive tables start with nine points and refine curvature/phase boundaries, ending at 9-30 knots.

For the coupling comparison, inlet-property hydraulics provides q0. The first candidate's cooling profile is sampled at eight positions, density/viscosity are frozen, and one scalar flow correction gives q1. A second correction q2 is measured diagnostically. The thermal curve itself remains the same. This isolates frozen-profile hydraulic coupling, although it also includes the eight-versus-64-point spatial approximation; do not attribute every error solely to iteration count.

## Measured accuracy

| Thermal model at identical flow | Cases above 10% | Median error | 95th percentile | Maximum |
|---|---:|---:|---:|---:|
| Inlet-cp exponential | 221 / 561 | 1.421% | 89.504% | 89.504% |
| One secant-cp correction | 27 / 561 | 0.000168% | 9.587% | 61.199% |
| Fixed 9-point H-T curve | 11 / 561 | 0.000352% | 0.964% | 14.510% |
| Fixed 17-point H-T curve | 0 / 561 | 0.000131% | 0.976% | 7.103% |
| Adaptive 9-30-point H-T curve | 0 / 561 | 0.000273% | 0.0357% | 0.0461% |

The 17-point result is a qualification of this population only; its 7.1% worst error leaves limited margin if pressure/composition/U also change. The adaptive curve offers substantial margin and was the curve used in timings and coupling tests. Table build/invalidation cost must be measured in actual network integration.

| Hydraulic approximation, using adaptive heat curve | Cases above 10% heat error | Median | 95th percentile | Maximum |
|---|---:|---:|---:|---:|
| Retain inlet-property flow | 307 / 561 | 17.477% | 368.148% | 650.138% |
| One frozen-profile correction | 84 / 561 | 0.1135% | 85.547% | 649.439% |
| Two corrections | 60 / 561 | 0.0366% | 40.008% | 234.835% |
| Skip first correction when heat self-mismatch <=10% | 85 / 561 | 0.4652% | 85.547% | 649.439% |

One-correction failures by condition: steam condensation 33/60; pentane condensation 25/60; crude cooling 9/60; heavy liquid 9/51; methane heating 3/60; methane cooling 1/60; water heating 2/48; water cooling 2/48. Liquid pentane and sensible steam had no failures.

A water case (150 m, initial 0.02 m/s, U=15) had only 8.76% apparent self-mismatch but 12.40% reference heat error when the correction was skipped. A self-mismatch threshold is not an error certificate. Strongly cooling/condensing runs can substantially change viscosity/density and invalidate the weak-coupling assumption behind the column analogy.

## Reference and conservation checks

- Main campaign refinement: 65 cases, doubled thermal/spatial resolution; maximum heat change 0.1923%.
- Targeted follow-up: two worst one-correction cases per fluid, with 512 and 1024 spatial samples and 1025+ thermal knots. Original-reference difference at most 0.2813%; final 512-to-1024 change at most 0.00848%. Thus the large coupling failures are not explained by the measured reference discretization error.
- Independent RK4 integration of the same refined H-T curve: 20 checks, including actual partially condensed steam/pentane outlets. Maximum relative discrepancy 3.75e-11; step refinement below 9.45e-11. This checks the analytical marcher independently, not the underlying EOS/U assumptions.
- Algebraic stream energy residual max 7.14e-11 relative to max(1 W, abs(heat)). This is stream accounting only; full network conservation, integrator stage ledgers and replay are not yet exercised with thermal loss.
- Pilot at 200 kPa steam encountered `Water-vapor approximation outside qualified partial-pressure range` while cooling. Retained in `pilot/`. Main steam condensation uses 100 kPa, supported by the current model. No domain guard was weakened.

## Cost

Java 21.0.11. The 600-case campaign used eight independent workers, then closed that pool before serial timing. Main run including timing: about 45.8 seconds. No campaign overlapped another campaign, Gradle gate or dev client.

Warm serial timing, five repeated batches, using the adaptive curve:

| Condition | Median exchange call | Median single scalar correction | Median fresh model + curve construction |
|---|---:|---:|---:|
| Methane cooling | 0.157 microseconds | 7.159 microseconds | 4.363 ms |
| Heavy liquid | 0.183 microseconds | 7.381 microseconds | 4.096 ms |
| Condensing steam | 0.449 microseconds | 9.830 microseconds | 4.231 ms |

Exchange timings average 10,000 calls; correction timings 1,000 calls per batch. Results are consumed through a volatile sink. Construction figures include a new thermodynamic model, not just interpolation setup; do not infer network cache rebuild cost directly. Per-case thread CPU timings are quantized on Windows and are not used for performance conclusions. Dense reference timing is diagnostic, not a fair production baseline or whole-network speedup claim.

## Verification and remaining work

`fluidScienceTest`: 188 tests, zero failures/errors/skips, including the six new tests. `git diff --check` passed. No runtime/save/UI source changed, so no dev-client GUI verification was needed or claimed. No full-network performance improvement is claimed.

Before a gameplay implementation: integrate delivered enthalpy and ambient heat consistently in residuals, reconstruction, TR-BDF2 stage quadrature, audit and interval totals; handle table validity under pressure/composition changes; update signatures/persistence/replay; verify flow reversal and stale-worker invalidation; qualify full-network convergence and cache cost. Piecewise table slopes can introduce numerical kinks and must be tested in that setting. Prescribed U and insulation values still need gameplay choices or physical calibration.

No merge, version bump or changelog entry was made. The experiment answers feasibility and identifies a practical thermal estimator; it does not claim the feature is implemented in-world.

## Artifacts

- Canonical tooling: `tools/pipe-thermal-loss/README.md`, Java harnesses, `study.init.gradle`, `analyze.ps1`, `prototype.patch`.
- Canonical data: `research/2026-09-24-pipe-thermal-loss/pilot/`, `campaign-01/`, `followup-01/`, `summary.json`, `validation.json`.
- Plan: `PIPE_THERMAL_LOSS_EXPERIMENT_PLAN.md` in this batch.
- Original assessment: `PIPE_THERMAL_LOSS_REVIEW.md` in this batch; the measured findings here supersede its untested one-correction expectation.

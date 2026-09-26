# Plan 2 solver experiments — 9 September 2026

The bounded Plan 2 experiment implements map-contract diagnostics and one residual-only comparator, DF-SANE. The main batch does not justify replacing the existing solver. DF-SANE found no nontrivial raw MESH root under its declared bounds. A wet restart showed useful seed improvement before Newton cleanup, but the measured timing and available work counters do not establish the proposed 25% performance gain.

Work is on `codex/plan-2-solver-experiments`, based on `e8d8937eacc1d58692f1a4f289f0633ec25b4cd1` plus the user's existing TJL19 migration changes. Three Astra medium agents implemented and reviewed the numerical core, fixture/map diagnostics, and integration contracts. ChatGPT 6 Pro was consulted when the lack of raw convergence remained unexplained. Production, existing test sources, and resources were preserved. The new Java programs and Gradle init script live in [the opt-in research directory](../../benchmarks/solver-alternatives/README.md).

## Implementation and acceptance

[The core](../../benchmarks/solver-alternatives/V3DfSane.java) uses signed BB1 spectral steps and Cruz nonmonotone, two-sided line search. It evaluates residuals only; it does not call a Jacobian, Newton solve, or the production Armijo search. The merit is the squared norm of the fixed transformed residual, with a ten-value history and summable allowance `phi(initial)/(k+1)^2`. Domain failures reject trials, cancellation propagates, and unexpected invariant failures remain visible. The implementation was checked against the [SciPy spectral solver](https://github.com/scipy/scipy/blob/main/scipy/optimize/_spectral.py) and [Cruz line search](https://github.com/scipy/scipy/blob/main/scipy/optimize/_linesearch.py); bounds and native closure are explicit research extensions.

[The adapter](../../benchmarks/solver-alternatives/V3DfSaneMeshAdapter.java) fixes one problem, support, phase branch and wet set. Native log-flow coordinates have unit scale; temperature uses 10 K. Row scales come from the decoded initial anchor. A semantic ledger assignment maps material equations to a liquid or surviving vapor flow with negative sign, VLE to vapor flow with positive sign, dry energy to temperature with negative sign, active wet energy to its free-water flow with positive sign, and saturation to temperature with negative sign. This is a bijective, fixed transformation with the same roots; it is a heuristic choice of spectral directions, without a convergence guarantee for these MESH systems.

[The driver](../../benchmarks/solver-alternatives/V3DfSaneExperiment.java) separates the last accepted raw state, the best valid evaluated trial, and the candidate passed to correction. The best trial can have been rejected by the nonmonotone search. On raw failure it is a seed proposal, never a claimed converged solution. Both variants start from the same `decode(encode(fixtureSeed))` state. Native maximum scaled residual at most `1e-8` defines raw closure independently of frozen merit.

The hybrid then calls the unchanged fine solver with up to 128 iterations. Acceptance requires its actual success and convergence certificate plus a fresh audit using the original thermo model. Using the original model also preserves PR-specific advisory evidence that a counting wrapper would otherwise hide. Holland agreement with its independent oracle is recorded separately. The synthetic active-water fixture explicitly cannot supply the physical flash audit and cannot be accepted. Deadline checks after audit prevent late acceptance; deterministic tests cover interruption during raw work, correction and audit.

## Main batch

The preserved [main JSON](../../build/reports/solver-alternatives/plan2/df-sane.json) contains 56 attempts: seven cases, two variants, one warmup and three measured repetitions. Variant order rotates. It used Java 21.0.11, a 2 GB maximum heap, 300 raw iterations, 2,000 residual callbacks, a three-second raw budget and six-second total budget. Limits are cooperative, so interrupted work can slightly overrun. Fixture construction is excluded; raw scaling/property work, correction, diagnostic residuals and audit are included. This measures fixed-problem inner attempts, not the production continuation chain.

The manufactured portion of that first batch is superseded by the fixture correction described below. The five other cases retain the following results. All raw-root counts are zero out of three measured attempts.

| Case | Fine baseline | DF-SANE then fine | Median total seconds, baseline → hybrid |
|---|---|---|---|
| Current four-stage crude | 0/3 accepted; 128-iteration limit | 0/3; raw evaluation limit, total timeout during cleanup | 3.8680 → 6.0139 |
| Current Sum-Rates binary input | 0/3; line search exhausted | 0/3; raw iteration limit, singular cleanup | 0.01289 → 0.04136 |
| Steam-bearing, floor-supported wet restart | 3/3 accepted; three cleanup iterations | 3/3 accepted; raw iteration limit, one cleanup iteration | 0.12899 → 0.09724 |
| Perturbed independent Holland state, side draw | 3/3 accepted and oracle-qualified; two cleanup iterations | 3/3 accepted and oracle-qualified; raw evaluation limit, two cleanup iterations | 0.01703 → 0.04514 |
| Current binary physical-phase negative control | 0/3; condenser-phase audit rejection | 0/3; raw iteration limit, condenser-phase audit rejection | 0.00158 → 0.00282 |

Small failure times are not performance successes. The phase-negative hybrid reached raw native residual `1.14035e-8`, just above tolerance, then obtained a correction certificate but failed the physical phase audit. That audit rejection is the expected distinction between numerical closure and an accepted physical state.

The wet result is a **hybrid seed observation**. Its terminal raw residual was `1.10931e-4`; selecting the best evaluated trial improved the pre-cleanup residual to `1.74384e-6`, still above `1e-8`. Cleanup fell from three iterations to one. Total median time decreased by 24.6168%, below 25%, from only three repeats in one JVM. Counted fugacity-interface calls were 27,909 raw plus 28,980 cleanup, versus 57,834 baseline: only 1.63% fewer combined calls. This does not substantiate a 25% property-work reduction or a production speedup. All seven initial cases had zero active water-saturation rows; a steam-bearing fixture alone does not establish active free-water coverage.

Holland is an independent reference-state check after cleanup: maximum temperature difference at most `1e-6 K` and relative flow difference at most `1e-7`. Both variants passed, with identical cleanup iteration counts and 11,050 cleanup fugacity calls; the hybrid added 51,824 raw calls. It therefore supplied no measured advantage there.

Counters cover thermodynamic interface calls during raw work and correction, including the scaling anchor. Internal PR-kernel operations and original-model audit property calls are unavailable. Zero calls to the separate `molarEnthalpy` interface do not mean zero enthalpy work: the evaluator consumes enthalpy returned with fugacity. Jacobian callbacks also omit final verification work. Timing medians and example work fields must not be added as though every field were measured in the same median sample.

## Fixture correction and remaining diagnostics

During the requested intermediate consultation, 6 Pro identified an inconsistency in the existing manufactured test thermo. Its `molarEnthalpy` depends on temperature, while `fugacity` embeds zero enthalpy. The residual evaluator uses the embedded value, so its initial energy rows did not constrain temperature. Root preservation and the old perturbed manufactured timing cannot establish energy-coupled solver behavior. The first batch is retained as historical evidence rather than silently overwritten.

A [benchmark-local wrapper](../../benchmarks/solver-alternatives/V3ManufacturedBenchmarkThermo.java) makes the embedded enthalpy agree with the existing manufactured enthalpy API. It preserves the reference root. An independently derived check holds exact flows fixed and increases tray 2 temperature by 1 K: physical energy-row changes must be +30 W, -130 W and +100 W on nodes 1–3, zero elsewhere, within `1e-8 W`. Each of the five noncondenser energy rows also responds to its own temperature perturbation. This excludes identically zero rows; it does not prove full Jacobian rank. The dry adapter self-test uses the same corrected model. Production/test fixture sources are untouched. The revised dataset is `current-NewtonManufacturedThermo-consistent-enthalpy-benchmark-r1`; the case IDs are unchanged for filtering.

The [targeted batch](../../build/reports/solver-alternatives/plan2/df-sane-targeted.json) contains 24 attempts, 18 measured, in a separate JVM with the same bounds. The exact manufactured seed is already a root: raw DF-SANE returns at iteration zero, and both paths pass correction/audit. Its timing is not evidence of solving a nontrivial problem. The perturbed manufactured seed reaches the raw 300-iteration limit, terminal native residual `5.93586e-4`, best pre-cleanup residual `2.87829e-4`. Both variants subsequently pass correction/audit in two iterations. Median total time is `0.0019354 s` baseline versus `0.0122103 s` hybrid. Correcting enthalpy therefore does not reveal a raw convergence or cleanup advantage on this fixture.

The added synthetic three-tray case has 25 coordinates, including one active free-water unknown and one water-saturation row. Its saturation equation is initially closed, but the complete MESH system is not. Raw DF-SANE reaches 2,000 callbacks after 223 iterations, terminal native residual `0.82085`, best pre-cleanup residual `0.47757`. Neither correction converges or passes its certificate: the baseline exhausts line search after 13 iterations, and the hybrid exhausts 128 iterations. Both record `AUDIT_UNAVAILABLE_SYNTHETIC`, with audit result unknown and no acceptance. This covers execution of the wet mapping, not a validated physical wet-column solution.

Across retained main and targeted evidence there are 80 attempts, of which 60 are measured. The 16 original manufactured attempts are superseded; they must not be pooled with the 16 corrected manufactured attempts as independent evidence. No nontrivial raw root closes in either batch.

The corrected manufactured reference is assessed separately from residual/certificate/audit acceptance, using maximum temperature difference `1e-6 K` and maximum relative component-flow difference `1e-7`. All 12 measured corrected outputs pass. The perturbed hybrid differs by at most `1.62e-8 K` and `6.22e-9` relative flow; the baseline is still closer. The raw hybrid proposal remains approximately `0.1495 K` from the reference and is not reference-qualified. Checks and source hashes are saved in the [verification manifest](../../build/reports/solver-alternatives/plan2/verification-manifest.json).

## Existing map contracts

The [qualified map diagnostics](../../build/reports/solver-alternatives/plan2/map-contracts-qualified.json) use the corrected manufactured thermo. All three seed wrappers preserve that reference root. In particular, Sum-Rates now returns `Prepared` at the root; the original manufactured singular-temperature-system result was a fixture artifact and is superseded.

Changing only duty by 1,000 W leaves the bubble projection effectively unchanged (coordinate update around `1e-15`) while the full scaled energy residual is `1.11111e-4`. Hybrid returns the same duty-blind bubble candidate after Sum-Rates fails. Thus a tiny update or prepared result is not a full-energy convergence test. Four repeated bubble projections of the current binary fixture produce full scaled maxima approximately `8.01`, `13.35`, `25.05`, `27.09`, without closure. Scales are frozen per transformation input, so no cross-epoch merit ratio is asserted.

On the floor-supported wet fixture, bubble preparation violates an exact structural zero and is rejected by the downstream residual evaluator. Sum-Rates and Hybrid report side-draw `NotApplicable` without substituting a fallback. These are bounded counterexamples to directly treating the existing wrappers as a general root map; they do not disprove a separately completed sequential solver.

## Reproduction and evidence

Run commands and bounded overrides are in [the experiment README](../../benchmarks/solver-alternatives/README.md). The build init script is explicit opt-in; no normal build configuration or production solver-selection path was changed. The earlier plan comparison remains in [the original investigation](newton-alternatives-2026-09-08.md).

The final focused validation passed **298 research assertions** (43 core, 218 adapter, 37 driver), revised map/enthalpy self-checks, and **14 existing JUnit invocations** (12 focused regression cases plus two Holland benchmark tests). JUnit success includes tests of typed numerical failure and phase rejection; it is not equivalent to every attempted column converging. The [qualified-check log](../../build/reports/solver-alternatives/plan2/qualified-checks.log), [targeted-run log](../../build/reports/solver-alternatives/plan2/targeted-run.log), [main-batch log](../../build/reports/solver-alternatives/plan2/full-batch.log), and [regression metadata](../../build/reports/solver-alternatives/plan2/regression-results.json) record the checks. No whole-project suite was run.

## Recommendation

ChatGPT 6 Pro completed its final evidence review after the fixture correction and returned `C2C DONE` for iteration 2. It verified the thermal checks, reference recovery, deadline handling, unavailable-audit behavior and bounded interpretation, requesting no further correction or numerical rerun. The Astra medium numerical reviewer also found no blocking implementation issue.

Keep this DF-SANE configuration as a diagnostic comparator. The evidence does not support production integration or treating its cleanup acceptances as derivative-free solutions. Nonconvergence within a finite budget does not prove that every scaling, sign assignment or budget would fail, and passing formula tests does not prove these directions are effective on MESH.

Existing seed transformations still require a complete root-map contract before Anderson acceleration is justified. Completing that map, trying guarded chord/Broyden, or changing spectral scaling are distinct later experiments. This batch does not implement or select those methods.

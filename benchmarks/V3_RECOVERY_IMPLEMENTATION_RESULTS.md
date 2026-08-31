# REVERTED experiment: stagnation recovery and stage-support reactivation

Date: 2026-08-31. Branch: `codex/stage-trace-truncation`.

## Status and rollback decision

**Status: REVERTED at the user's request. This is a historical experiment record, not a description of the active production solver.** The restored build is byte-for-byte identical to the pre-experiment mod, and its 289-test suite passes in an uncached rerun. Verification is recorded separately below.

The experiment implemented both mechanisms proposed after `V3_LOW_PRESSURE_DIAGNOSIS.md`, without relaxing physical acceptance gates or changing thermo property values. It materially reduced the saved 55 kPa residual, but did not produce a fully audited solution at either 55 or 50 kPa. The requested 50 kPa state was still not reached.

The final cold 50-off/on measurements increased from **9.993 / 15.496 seconds** before this experiment to **13.236 / 24.597 seconds** with it. These are single cold measurements, not confidence intervals; they show additional work in the failing cases, not a completed convergence or speed fix. An earlier wide-search prototype also exhausted the unchanged 45-second deadline on 50-on. The user chose to remove the added recovery behavior and retain its evidence for a later, separately qualified investigation.

Rollback scope is the latest stagnation-recovery and stage-support-reactivation implementation and its associated production/test changes. The earlier cubic-root precision fix and phase-aware **flash** truncation are to remain; reverting column support reactivation does not mean reverting flash truncation. Numerical property data and strict independent audits are not to be weakened or replaced.

Historical JSON reports remain in `results/v3-low-pressure/`, `results/v3-low-pressure-recovery/`, and `results/v3-flash-cold-recovery-final/`. The complete 20-file source/test/harness change is archived at [archives/v3-recovery-experiment.patch](archives/v3-recovery-experiment.patch). That patch is archival evidence, not an instruction to reapply the experiment.

## Historical implementation (removed by rollback)

The following describes the tested experiment, not behavior promised by the reverted production code.

### Stagnation-aware regularization

- Applied to the existing stage-coloured/block path (at least 96 coordinates), where the large-column trace-profile failure was observed. The established small dense-system path is preserved; an early unrestricted prototype regressed its positive liquid-only warm-start test and was not retained.
- A Newton candidate with step at most `1/64` and at most 1% improvement in the original squared-residual merit may activate recovery, while the residual remains above the unchanged convergence tolerance.
- Local or reused Jacobians cannot become the authority for that recovery: the solver falls through/retries with the current full finite-difference Jacobian.
- Regularized normal equations privately equilibrate each nonzero Jacobian row and its right-hand side by the row's maximum absolute derivative. This changes the candidate direction, **not** the residual used for line search or acceptance.
- Work is capped at three damping values (`1e-8`, `1e-4`, `1`) and eight line-search evaluations each. An accepted Newton candidate is replaced only by a candidate with strictly lower original merit. Failed/unproductive searches back off for four accepted iterations; meaningful progress permits another search next iteration.
- Once activated, the bounded recovery also handles subsequent complete Newton-line-search failures. The ordinary pre-existing fallback remains for attempts that never activate recovery.
- Chosen regularized steps clear the cached Jacobian and final-Newton evidence. Fresh verification and the full independent audit are still required before publication. Cancellation escapes unchanged, including a cancellation whose message matches an optional numerical-error message.

### Stage-support reactivation

- At a failed nonidentity-support attempt boundary, independently recompute the same summed incoming-edge defect used by the audit. This also works if a later condenser flash made the overall audit unavailable.
- If the defect exceeds the existing `8 * cutoff` budget, select the largest contributing components and restore their complete stage profiles. Support only expands during these retries; no component name or hard-coded vapor eligibility is involved.
- Seed those selected profiles using the existing component-material TDMA calculation at the candidate temperatures. No new numerical floor is used by this recovery. Unselected profiles and all temperatures remain unchanged.
- Keep the expanded mask explicit. Do not call ordinary seed-based support derivation again, which could immediately delete newly restored trace points.
- Rebuild the problem/ledger, coordinate map, evaluator, Newton state, convergence evidence and audit. Allow at most two support-reactivation retries per entry, under the same caller-owned deadline. Unavailable or unsuccessful recovery retains normal failure/fallback behavior.
- A mask expanded to identity still retains positive requested-cutoff provenance. Long composed recovery paths are compacted at the diagnostics boundary without changing the 128-character wire limit.

### Historical provenance

The experimental untruncated solver revision was `v3-dry-mesh-r3-stagnation`; its positive-cutoff revision was `v3-dry-mesh-r5-support-recovery`. Explicit zero cutoff matched the experimental untruncated path; the numerical-search change was not advertised under the old `r2` digest. Dataset revision remained `cdu17-tjl-kl1976-r2`. The rollback is intended to restore the pre-experiment untruncated `v3-dry-mesh-r2` and positive-cutoff `v3-dry-mesh-r4-flash-trace` provenance; final verification belongs in the rollback section, not in this historical qualification.

## Findings, architectural costs, and encountered regressions

- **The immediate failure mechanism was improved, not resolved.** Oversized fresh Newton corrections caused very small accepted Armijo steps. Row-equilibrated regularization escaped much of that plateau, but the remaining failed state was dominated by bulk component-material equations. A smaller residual alone was never treated as convergence.
- **Direction generation and acceptance used different metrics deliberately.** Row equilibration preserved the corresponding Newton equations in exact arithmetic but changed the least-squares direction calculation. Every candidate was still evaluated with the original residual/merit, and publication required the original final-step certificate and independent audit. A useful direction metric does not by itself guarantee a robust global convergence path.
- **Recovery cost needed its own budget.** The wide search could try eight damping values with up to twenty line-search evaluations each on many iterations. Initially, any tiny improvement over Newton scheduled another search immediately. The bounded experiment limited this work and backed off after unproductive searches, but did not recover the pre-experiment cost on the failing 50/55 kPa cases.
- **Unrestricted activation exposed a real regression.** An early unrestricted prototype regressed the established positive liquid-only exact warm-start fixture. The tested implementation subsequently restricted stagnation recovery to the large stage-coloured/block path. The fixture and physical gates were not relaxed to accommodate that prototype.
- **A narrower damping grid was not automatically better.** The `1e-4,1e-2,1` experiment reduced candidate work but lost important early residual improvement. The final experimental grid was `1e-8,1e-4,1`. This sensitivity is a reason to preserve all variants rather than present one parameter choice as a general solution.
- **Recovery dispatch had to remain coherent.** After a beneficial regularized step, a later complete Newton-line-search failure could otherwise return to the older unweighted fallback. The activated recovery lane addressed that gap while leaving never-activated attempts on the existing path. Local/stale Jacobians, certificates, and fallback ownership required explicit handling.
- **Moving masks were a separate conservation problem.** A frozen truncated model could converge numerically while its omitted-edge mass defect exceeded the allowed budget. Restoring complete component profiles changed the ledger and Newton coordinates, so the experiment rebuilt numerical state and re-audited instead of changing support inside finite differences. Monotone masks and bounded retries prevented immediate re-deletion and unbounded recovery loops; they did not establish success for 50/55 kPa.
- **State and diagnostic contracts mattered.** The implementation needed cancellation-identity preservation, finite row-equilibration checks, explicit support provenance even after expansion to identity, and bounded composed solve paths. These were additional architectural obligations, not changes to thermodynamic eligibility or audit tolerance.

No physical audit was loosened, no PC12-specific phase ban was introduced, no property value was edited to force convergence, and the final Newton iteration limits were not increased. Successful approximate streams changed when masks changed and were not claimed to be bitwise-compatible with earlier approximate streams.

## Is the remaining issue thermo data?

There is no demonstrated erroneous property value behind the remaining failure. The evidence establishes difficult numerical correction and moving support; it does not prove physical infeasibility or data validity at every operating point.

- The repaired cubic-root cancellation was a numerical implementation issue, not a property-data correction.
- PC12 remains an extrapolated residue surrogate, including its critical properties and acentric factor; that uncertainty is retained in the advisory evidence.
- The failure is not specific to PC12. Oversized corrections involve PC10/PC11, and the newer stalled material residual is concentrated on **PC06** in the rectifying section. A dominant residual identifies a numerical equation, not a bad component property.
- A useful future sensitivity experiment would separate volatility parameters (`Tc`, `Pc`, acentric factor and the package's zero binary-interaction assumption) from enthalpy/heat-capacity parameters, especially around the middle cuts carrying the moving profile. That controlled attribution was not performed here, and none of those values were altered to force convergence.

## Historical pre-rollback verification record

Before rollback, the experimental `test build v3TimeoutBenchmarkHarnessTest` passed: **314 tests, zero failures/errors/skips**, mod build and benchmark self-test successful. **314 is the experiment's historical count, not a post-rollback test result.** Earlier files under `results/v3-low-pressure-recovery/` are labelled development experiments, not final timing evidence.

Experimental tests included stagnation thresholds, the immutable saved 55 kPa state, clearing stale Newton evidence, cancellation identity, row-equilibration arithmetic/overflow/immutability, monotone partial/full support restoration, summed-defect enforcement, repeated reactivation and bounded diagnostics paths. During the experiment, the existing hot-condenser case exercised successful support reactivation and compared fresh result/diagnostic audits and certificates instead of requiring the earlier whole-chain untruncated retry. This paragraph records those removed test changes; it does not redefine the restored test contract.

The experimental fixture `src/test/resources/v3-55kpa-stagnation.txt` preserved all 992 doubles of the original saved terminal state exactly, plus the reference feed enthalpy, without adding a JSON dependency to the isolated science tests. Its source form is included in the rollback/archive scope; the original historical JSON remains retained independently.

At experimental verification, all 20 existing thermo Java source files matched the investigation baseline hashes. The final experimental cold matrix verified all 68 production V3 Java source hashes unchanged for that complete run. These statements do not substitute for checks of the restored sources.

Historical experimental artifact: `build/libs/createcheme-0.1.0.jar`, 656526 bytes, SHA-256 `c994099d21f00ed8f1edfc014afab0067a3ea910dfc46d00374f8d331ddae845`. This is not the identifier of a post-rollback artifact.

### Final experimental cold matrix (retained evidence)

One serial fresh JVM per cell, zero warmup, unchanged 45-second deadline; client closed. These are single measurements, not confidence intervals. Raw reports and manifest: `results/v3-flash-cold-recovery-final/`.

| Top pressure | Cutoff off, seconds | Cutoff 1e-6, seconds | Outcome |
| --- | ---: | ---: | --- |
| 150 kPa | 2.246 | 1.810 | Both strictly accepted |
| 110 kPa | 2.808 | 2.031 | Both strictly accepted |
| 100 kPa | 5.464 | 3.088 | Both strictly accepted |
| 70 kPa | 7.552 | 4.440 | Both strictly accepted |
| 55 kPa | 13.266 | 25.446 | Both nonconverged at 55 kPa |
| 50 kPa | 13.236 | 24.597 | Both stop at 55 kPa; 50 kPa is not reached |

All four successful positive-cutoff chains recorded support reactivation without a whole-chain untruncated retry. The 50/55 positive-cutoff chains still required that retry and ended in the same untruncated failure.

The four successful untruncated product-stream reports are numerically identical to the previous cold baseline (their revisioned digests intentionally differ). Positive-cutoff streams change with the refreshed stage masks and pass the unchanged audits; bitwise compatibility of those approximate streams is not claimed.

The failed 55 kPa recovery's maximum scaled residual fell from `0.13293792698416595` in the pre-change run to `0.002423498806095813`, but remains far above `1e-8`. Its dominant equation is the PC06 component material balance at node 8, not a PC12 phase-eligibility constraint. The final iteration limits were not increased.

Recovery added work on failing cases: the old cold 50-off/on times were 9.993/15.496 seconds, versus 13.236/24.597 seconds here. The final bounded implementation stayed below the deadline in these measurements, unlike the discarded wide-search prototype, but **did not provide a completed convergence or speed fix for 50–55 kPa**. A further correction/continuation or thermo-sensitivity investigation is needed; loosening audits is not justified.

Historical matrix command, using Java 21 and a new output label:

```powershell
.\benchmarks\Run-V3FlashColdBenchmark.ps1 -Label recovery-recheck -Include55Kpa
```

The `Include55Kpa` switch and its extra cases were also reverted and are preserved in the archive. This historical command requires the experimental patch in an isolated worktree. The active script can benchmark the restored implementation using a new label without that switch; do not silently reapply the experiment to the restored production tree.

## Development experiments

- `initial-off-replays.json`: unscaled regularization prototype, interrupted after the 128-iteration predictor replay timed out; not a completed benchmark and not the retained search policy.
- `equilibrated-off-24.json`: row-equilibrated but wide-search prototype; predictor/projected residuals about 0.002424/0.002428 after 24 iterations, still rejected.
- `integration-first.json`: that wide-search prototype hit the unchanged 45-second deadline on 50-on. This motivated the bounded candidate budget and productivity-based backoff; not a final performance claim.
- `bounded-off-24.json`: bounded `1e-4,1e-2,1` damping experiment; not retained because it lost early improvement.
- `bounded-equilibrated-off-24.json`: damping range retained during the experiment, with backoff before subsequent complete-line-search coverage was added.
- `activated-lane-off-24.json`: added subsequent-line-search coverage; saved terminal trajectory improves but remains unaccepted. These are repeated instrumented replays, not cold timings.

The historical baseline files in `results/v3-low-pressure/` were not overwritten. Development and final experimental reports are retained even though the production change was reverted.

## Rollback verification

- Removed the latest stagnation selector, row equilibration, expanded-support retries, associated diagnostic changes/revisions and experiment-only tests. Removed seven experiment-only active files, including the replay harness; their contents remain recoverable from the archived patch. Earlier low-pressure diagnostic probes remain available.
- Preserved the cubic-root precision fix, validated phase-aware flash truncation, shared cutoff policy, earlier binary phase-rejection test recovery, property data, and strict audits. Untruncated provenance is again `v3-dry-mesh-r2`; positive cutoff uses `v3-dry-mesh-r4-flash-trace`; dataset remains `cdu17-tjl-kl1976-r2`.
- All 67 pre-experiment production source definitions are restored. Before Git checkout normalization, 65 files match the historical raw SHA-256; Calculator and SimultaneousColumnSolver retain only historical line-ending differences. No 67/67 raw-byte source-hash claim is made. The identical built artifact below provides a stronger executable check.
- Archive dry-run passed: `git apply --check --ignore-space-change benchmarks/archives/v3-recovery-experiment.patch`. The LF-normalized patch SHA-256 is `9a410d69791cc43f16c8ad5fae543b39ae043c54e823a4fbe85cd33001cb5667`; a Windows checkout can change its raw newline bytes. Applying it would restore the experiment, so it is not applied in the production tree.
- `test build compileBenchmarkJava v3TimeoutBenchmarkHarnessTest` passed, initially reusing matching Gradle compilation/test cache entries. An explicit `cleanTest test --no-build-cache --offline --no-daemon --console=plain` rerun then passed **289 tests, zero failures/errors/skips**.
- Rebuilt `build/libs/createcheme-0.1.0.jar` in the stage-trace worktree before merge: **638684 bytes**, SHA-256 `6152c2121a205341c263e66f600d881f9a804707678ef3414fd19ce8bdfd6b1f`, exactly matching the pre-experiment artifact documented in `V3_PRECISION_FLASH_TRUNCATION_RESULTS.md`.
- No new cold performance claims are made for the rollback. The preserved pre-experiment baseline and removed-experiment reports remain distinct. This rollback does not resolve the previously documented 50–55 kPa nonconvergence or identify an erroneous thermo-data parameter.

## Merge verification

The retained work and this rollback record were committed as `c871d6b` and fast-forwarded into the clean `codex/hybrid-solver` worktree. Its `build compileBenchmarkJava v3TimeoutBenchmarkHarnessTest` verification passed, including **289 tests with zero failures/errors/skips**. No experimental source/test classes were reintroduced.

The target-worktree JAR SHA-256 is `148a05a7e50a7825bcb59e08d7b4e26b023f3fbdb6f1ae1dfc7bbb061ae026f8`. ZIP-entry comparison confirms every compiled class is identical to the verified pre-experiment artifact. Only nine text-resource entries differ, and every difference disappears after CRLF/LF normalization; there is no content or thermodynamic-data change. The archived patch also passes its dry-run check in the merged worktree.

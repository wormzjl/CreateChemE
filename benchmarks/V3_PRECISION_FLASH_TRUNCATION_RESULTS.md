# V3 root precision and phase-aware flash truncation

## Outcome

Implemented in the requested order: **root precision first**, then **flash truncation**. The intermediate precision-only implementation passed 239 tests and completed a ten-case cold benchmark matrix before any flash-truncation implementation edits. Its source manifest verifies that all V3 Java sources stayed unchanged during that matrix.

The original 638.15 K / 137250 Pa feed-flash failure is fixed without increasing its 64-iteration limit or relaxing its `1e-10` log-K convergence criterion. The 100 and 70 kPa full columns now succeed with either cutoff setting. The requested 50 kPa column is still **not a qualified success**: continuation reaches the 55 kPa step and rejects a nonconverged column candidate. It no longer stops at the original feed-flash precision failure.

Phase-aware flash truncation is available through an explicit policy overload and is called by the column's positive-cutoff feed-flash path. The old flash API, phase selection, condenser-transition preparation, and independent acceptance flashes remain unrestricted. No PC12-specific phase ban remains.

## 1. Precision repair

[V3PengRobinsonKernel](../src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3PengRobinsonKernel.java) now checks the backward residual of the original analytic root. When the residual is within eight ulps of the sum of absolute polynomial terms, it preserves the original result bits. Otherwise:

- The one-real-root path evaluates the non-cancelling Cardano term and obtains its partner from `uv = -p/3`.
- At most four Newton refinements are allowed, each requiring a decreasing FMA-evaluated cubic residual, a usable derivative, and a root inside its original derivative-monotonic interval and physical boundary.
- Three-root physical membership is frozen before refinement. The existing near-coalescence classification and arithmetic are unchanged.

This repairs measurable root error without gratuitously perturbing already-accurate finite-difference inputs. Regression tests include the exact captured feed, independent 90-digit bisection oracles for rounded polynomial coefficients, multiple physical roots, coalescence boundaries, and bit-preservation controls.

The initial eager-refinement variant exposed cold-trajectory sensitivity. The accepted liquid-only cold/hot case passes with backward-error gating. A deliberately invalid two-phase fixture now uses the same existing bubble-point/coarse-FD recovery as its companion test, still requiring numerical convergence, a final Newton certificate, fresh physical rejection, and zero-iteration exact hot reuse. No input, numerical tolerance, or acceptance gate was relaxed. See [initial test evidence and qualification](results/v3-flash-investigation/PRECISION_INITIAL_TEST_RUN.md).

## 2. Flash truncation contract

The new [shared policy](../src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3TraceTruncationPolicy.java) uses the existing cutoff domain `[0, 0.01]` in mole-fraction units. Zero is the exact off path. No new game configuration was introduced.

[V3TruncatedFlash](../src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3TruncatedFlash.java) performs:

1. An unrestricted reference flash. Reference failure or cancellation is never rescued with an approximate success.
2. An immutable phase-support decision from that reference. A positive-overall component may be present in both phases, liquid only, or vapor only. Exact-zero overall components remain absent. If both phase concentrations are trace, the conservative decision is to retain both.
3. A warm, bounded reduced solve. Liquid-only components contribute `-z/(1-beta)` to Rachford-Rice; vapor-only components contribute `z/beta`. Infinite log-K sentinels are not used. Each component's material is allocated before calculating phase compositions; finished compositions are never clipped and renormalized to hide lost material.
4. A full-basis check against the reference. Failed checks restore the unrestricted result and its workspace state. An omitted phase contribution whose fresh fugacity ratio exceeds the cutoff triggers full-support reactivation through that fallback.

Limits for a positive cutoff `c`:

| Check | Limit |
| --- | --- |
| Per-component material closure | `64 * ulp(z_i)`; independent of `c` |
| Retained-component log-fugacity equilibrium | `1e-10` |
| Sum of absolute liquid and vapor component-allocation errors, normalized to feed | `8*c` |
| Absolute vapor-fraction error | `8*c` |
| Maximum component mole-fraction error in either phase | `c` |
| Molar enthalpy error | `max(1e-6 J/mol, 8*c*max(1 J/mol, abs(H_reference)))` |
| Overall phase classification | Must remain unchanged |

The error budget is permission to approximate a phase allocation, **not permission to delete feed material**. The returned immutable evidence distinguishes `DISABLED`, `NO_CANDIDATES`, `SINGLE_PHASE`, `APPLIED`, and `FALLBACK`, with separate reference/reduced iteration counts and an explicit indication of whether errors were evaluated.

Public usage:

```java
// Existing unrestricted API: unchanged, used by independent physical audits.
V3FlashResult strict = thermo.flashTP(temperature, pressure, overall, workspace);

// Explicit phase-aware approximation with a 1 ppm phase-composition cutoff.
V3FlashResult bounded = thermo.flashTP(temperature, pressure, overall,
        V3TraceTruncationPolicy.of(1e-6), workspace);
```

A six-argument overload accepts a caller-owned checkpoint. Cancellation and callback exceptions propagate unchanged, including a callback exception that happens to have the same type as a thermodynamic failure.

## Column integration and deliberate limits

`V3ColumnCalculator.solveSingleProblem` invokes the explicit policy for positive cutoffs and records bounded `flash-trace` diagnostics. MESH, audit, and recovery feed enthalpy always use the **unrestricted reference enthalpy**. The authored feed and its physical energy therefore do not change when a flash phase allocation is approximated.

The column still owns its existing whole-stage/component support mask. This work does **not** replace it with a phase-specific column unknown/equation layout, nor insert iterative flashes inside the coupled MESH residual evaluation. The current column consumes feed enthalpy rather than reduced feed x/y or beta. Thus the new flash feature is callable and observable there, but is **not a demonstrated column or flash speed optimization**. A reference-first flash necessarily pays for the reference calculation.

Only the positive-cutoff formulation revision changes to `v3-dry-mesh-r4-flash-trace`; the zero-cutoff revision stays `v3-dry-mesh-r2`. Numerical property data and the property-data revision are unchanged. The unused `vaporEligible` field and misleading vapor-ineligible claims were removed; PC12's estimated-property evidence remains.

## Direct flash evidence

The production probe executes ten reference/policy pairs with fresh workspaces, without timing claims. The [final report](results/v3-flash-truncation/production-flash-pairs-final.json) contains twenty successful calls. Its numerical outputs exactly match the [first report](results/v3-flash-truncation/production-flash-pairs.json).

At cutoff `1e-6`:

| Input | Decision | Numerical evidence |
| --- | --- | --- |
| 50/50 ethane-PC12, 500 K / 250 kPa | PC12 vapor omitted; liquid retained | Reference y(PC12) `1.68185e-10`; closure defect zero; allocation L1 error `1.71888e-10`; enthalpy error `1.77360e-5 J/mol` |
| Registered crude, 500 K / 250 kPa | PC12 vapor omitted; liquid retained | Reference y(PC12) `4.95290e-11`; maximum component closure defect `1.38778e-17`; enthalpy error `4.46081e-6 J/mol` |
| Registered crude, 638.15 K / 67.25-167.25 kPa | No candidates | PC12 vapor exceeds the cutoff; no phase contribution is forced to zero |
| Registered crude, 298.15 K / 250 kPa | Single-phase bypass | Liquid classification preserved |
| Registered crude, 900 K / 50 kPa | Single-phase bypass | Vapor classification preserved, including nonzero PC12 vapor |

The 638.15 K / 137.25 kPa crude flash converges in 36 iterations with beta `0.7855276025697608`; its PC12 vapor mole fraction is `3.21219e-6`, so a 1 ppm policy correctly retains it. Beta is the hot feed vapor fraction, not the earlier small condenser vapor fraction.

## Cold benchmark protocol and results

Each cell is one first solve in its own fresh JVM, with no warmup, an unchanged 45-second budget, and no competing client or solver. JDK 21.0.11; 512 MiB initial / 2 GiB maximum heap. JVM/Gradle startup is outside solver timing. This is not an OS-cache-cold or game-queue latency measurement.

Fixed input: registered TJL crude, 30 stages, feed stage 24, 638.15 K feed, 323.15 K condenser, 750 Pa/stage drop, reflux ratio 2, 8 MW duty, and 2610.7 kmol/h. On means cutoff `1e-6`.

| Top pressure | Precision-only off / on (s) | Final flash-truncation off / on (s) | Outcome, both settings |
| --- | --- | --- | --- |
| 150 kPa | 2.271 / 1.684 | 2.176 / 1.698 | Success |
| 110 kPa | 2.643 / 1.999 | 2.662 / 1.892 | Success |
| 100 kPa | 5.096 / 2.893 | 5.060 / 2.857 | Success |
| 70 kPa | 7.440 / 4.089 | 7.391 / 3.686 | Success |
| 50 kPa | 11.201 / 15.694 | 9.993 / 15.496 | Nonconvergence at the 55 kPa continuation step |

Complete serialized product streams are exactly equal between these two matrices for all eight successful cells. Off-case digests are unchanged; on-case digests intentionally change with the formulation revision. The two failed cells publish no product streams.

Evidence: [precision-only source manifest](results/v3-flash-cold-precision-only/SOURCE_HASHES.json), [final source manifest](results/v3-flash-cold-final/SOURCE_HASHES.json). Both verify unchanged V3 sources across all ten runs; the final manifest covers 67 source files. The precision-only matrix predates every flash-truncation implementation edit. An [earlier flash-truncation repeat](results/v3-flash-cold-with-truncation/SOURCE_HASHES.json), before callback-provenance hardening, is retained separately and gives the same eight successful product streams and two typed nonconvergences. Timing differences between these single samples do not establish a flash speedup.

The remaining 50 kPa case fails after 24 recovery Newton iterations at the 55 kPa step, with maximum scaled residual `0.13293792698416595`; its equilibrium and condenser-split audits fail. The same candidate fails before flash truncation is introduced. No physical-infeasibility conclusion is justified, and no tolerance is waived to publish it.

## Verification and reproduction

Precision-only: 239 tests passed. Final implementation: **289 tests passed, zero failures, errors, or skips**; `build` and the benchmark harness self-test passed. All twenty production API calls in the final ten-pair probe succeeded.

```powershell
./gradlew.bat test build v3TimeoutBenchmarkHarnessTest --offline --no-daemon --console=plain
./gradlew.bat v3FlashTruncationProbe --offline --no-daemon --console=plain '-PflashReport=build/reports/benchmarks/new-flash-pairs.json'
./benchmarks/Run-V3FlashColdBenchmark.ps1 -Label new-repeat
```

The probe and cold runner refuse existing output snapshots. JSON scientific status, not Gradle process success alone, determines a benchmark result. Each matrix records all V3 source hashes before and after its runs.

## Final verification

All final verification completed successfully, except the explicitly reported scientific nonconvergence of the 50 kPa column. No source changed during the final cold matrix. All 184 numeric tokens in the compiled crude package match commit `76075d7`; only the unused phase flag/advisory wording changed.

Final jar: `build/libs/createcheme-0.1.0.jar`, 638684 bytes. SHA-256: `6152c2121a205341c263e66f600d881f9a804707678ef3414fd19ce8bdfd6b1f`.

These changes and investigation artifacts remain uncommitted on `codex/stage-trace-truncation`; `76075d7` is the preceding commit. The remaining numerical investigation is the 60-to-55 kPa column continuation step, not the repaired feed flash. Phase-specific removal of column Newton unknowns is also outside this flash-only implementation.

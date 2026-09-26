# V3 solver design and numerical review

Reviewed 2026-09-05 at `33ae3d8`. Production sources were not changed. This review covers the V3 column calculator, Newton solver, truncation, flash, and linear algebra; it does not claim to audit every legacy thermodynamic solver.

The checkout subsequently switched to `main` at the user's request. Source line references below describe `33ae3d8`; use the [main-specific review](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-05-v3-solver-core-review/V3_MAIN_SOLVER_REVIEW.md) for current-checkout source references and fresh main validation.

The principal convergence problem is a mismatch between physically meaningful accuracy and the representation of vanishing component flows. The implementation then spends substantial work on recovery strategies that encounter the same mismatch. There are also concrete support, acceptance, and matrix-conversion defects. Adding another recovery layer is unlikely to address these together.

The latest retained 36-case screen in this checkout reports **26/36 successes**, not the older root report's 17/36. Those results are historical, not a new screen performed for this review. See [the A0 re-screen](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-01-v3-full-cdu-draw-wall/V3_COLD_DOE_A0_RERUN.md:63). Continuation did improve measured success; removing it wholesale is not supported by the evidence.

1. **High priority: negligible local amounts retain disproportionately strict equations and step requirements.**

   Every retained liquid/vapor flow must be positive and is encoded logarithmically. Every retained two-phase component gets a log-fugacity equality, and the final step requires a maximum log-flow change of `1e-8`, regardless of local material significance. A positive value being representable in `double` does not mean the nonlinear equations determine its relative value accurately.

   The recorded W5b failure is unusually clear: PC11 at tray 1 has liquid/vapor mole fractions about `7.78e-74` and `4.73e-80`. The source state passes the independent physical audit, but the raw Newton step gives both flows a `+5.779e8` log correction. The common movement of those two variables barely changes the material equations; the relative liquid/vapor direction remains constrained. Thus the solver demands precision in a direction that the physical balances effectively cannot resolve. This is a local amount problem, not a reason to remove PC11 from the whole column.

   Sources: [coordinates](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3DryMeshCoordinateMap.java:73), [equilibrium residual](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3MeshResidualEvaluator.java:112), [step gate](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ConvergenceEvidence.java:16), [recorded offender autopsy](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W2_W3_DIRECT_OFFENDER_AUTOPSY.md:39).

   Direction: prototype a formulation that admits numerically zero local amounts, with absolute-plus-relative error scaling and explicit material-loss accounting. A reduced support solve is another candidate, provided support is physically connected and can be reconsidered between attempts. Compare against existing conservation, phase, and energy audits. Simply raising all tiny flows to a floor invents material; simply loosening all residual tolerances would hide unrelated defects. The existing rejected rank-one/terminal rescues already demonstrate that another conditioned search followed by the same raw certificate need not work.

2. **High priority: the final Newton certificate sometimes certifies a different system.**

   The terminal fallback solves `(JᵀJ + damping D) delta = -Jᵀr`, then passes that solve's backward error directly into `assessFinalCandidate` and sets `hasFinalNewtonStep=true`. This backward error concerns the regularized normal system, not `J delta = -r`.

   A fresh manufactured probe reproduced the mismatch: material balances were exact, VLE residuals were constant `1e-9`, and their Jacobian rows were zero. No correction can satisfy those raw Newton rows. The solver nevertheless returned `Converged`, termination `verified final Newton correction`, zero final linear backward error, and zero step, through the damped path. The physical residual is within the configured tolerance; the defect is the claimed mathematical certificate, not proof of an out-of-tolerance physical result.

   Source: [terminal normal candidate](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:430).

   Direction: define one coherent convergence contract. If an original-system Newton certificate is required, evaluate its original-system linear residual. If physical/scaled convergence is sufficient in a reduced or rank-deficient formulation, represent that evidence honestly instead. Tightening this check alone is not a convergence improvement and could reduce reported successes until the formulation is addressed.

3. **High priority: truncation can retain component groups with no feed connection.**

   `pruneUnsupported` checks for an immediate retained inflow neighbor. Two neighboring nodes can therefore keep each other alive even when neither is reachable from the feed. The existing test explicitly retains condenser/tray 1 with positive reflux after deleting that component at tray 2 and feeding tray 3. A fresh probe confirmed that result.

   Summing the two isolated material equations requires `V0 + L0/(1+R) + L1 = 0`. All three terms are positive in the retained logarithmic formulation, so there is no exact positive solution. Iteration can only push the group toward the coordinate boundary. This is directly counterproductive to truncation's purpose.

   Sources: [closure algorithm](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3TruncationSupport.java:310), [test that preserves the island](D:/Minecraft/Modding/1.21/CreateChemE/src/test/java/com/wormzjl/createcheme/science/column/v3/V3TruncationSupportTest.java:112).

   Direction: determine directed reachability from actual component sources through allowed liquid/vapor transport edges. Preserve required draw supply paths, remove unreachable groups, and audit omitted transport. Immediate-neighbor closure is insufficient for a graph with cycles.

4. **High priority: bandwidth inference discards potentially essential small coefficients.**

   The full-Jacobian conversion ignores entries at or below `1e-10` when selecting bandwidth, then preserves every value inside the selected band. Whether a small coefficient survives therefore depends on its position and unrelated larger entries. The local assembler uses different support rules, while LU explicitly scales tiny columns.

   Example: `A=[[0,1e-12],[1,0]]`, `b=[1e-12,1]`. The existing LU solves the full matrix with zero backward error. The bandwidth rule drops its upper entry and makes the matrix singular. This is a verified linear-algebra counterexample, not a reproduced full-column failure.

   Source: [band conversion](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:502).

   Direction: derive storage support from the stage structure, preserving necessary coefficients within it. Eliminate physically insignificant unknowns at the formulation level; do not infer their significance from an unscaled individual matrix entry.

5. **Medium priority: continuation heuristics can override valid acceptance or prevent a target solve.**

   A feature-ramp candidate can converge and pass the audit yet be rejected for a side-draw/liquid-flow ratio above `0.95`. The physical side-draw audit accepts ratios below `1.0`, requiring positive continuing liquid. The calculator later labels the heuristic rejection `INFEASIBLE_SPECIFICATION`. The semantic mismatch is confirmed in code; a real converged 95–100% withdrawal fixture was not reproduced in this review.

   The mandatory `4→8→15→N` grid ladder and low-pressure 150 kPa anchor also return immediately when an artificial intermediate problem fails. Failure of that problem does not establish failure or infeasibility of the authored operating point. Steam-first basin selection uses fixture-derived pressure/loading bands and has no reverse retry, although draws-first selection does have a steam-first retry.

   Sources: [accepted rung rejection](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:930), [physical split audit](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3AcceptanceAuditor.java:173), [stage failure](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:370), [attach order](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:793).

   Direction: use these heuristics to select step size and strategy order under one request budget. Reserve physical infeasibility for physical constraints; retain a bounded authored-problem attempt from the best available seed.

6. **Medium priority: banded/local algorithms retain substantial global allocation and scanning costs.**

   LU column scaling scans every row's `TreeMap` twice for every column: exactly `2n²` lookups even for an identity matrix. A standalone harness compiled from the current original linalg sources measured median solve times of 9.264, 25.279, 88.829, and 358.097 ms at dimensions 1,000, 2,000, 4,000, and 8,000 respectively (five measured repetitions after warmup). These are microbenchmark results, not whole-column speedup estimates.

   Full-Jacobian band conversion copies the dense matrix, scans its `n²` entries, and linearly searches the stage layout for every column visit: `O(n² × stages)`. The local derivative path also clones coordinates, decodes/copies an entire column state, and globally validates it for each plus/minus probe. Thermodynamic evaluation is local, but state handling remains quadratic.

   Sources: [LU scaling](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/linalg/V3BandedPivotedSolver.java:118), [stage lookup](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:506), [local perturbations](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3BlockJacobianAssembler.java:253).

   Direction: compute all column maxima in one pass over stored entries, then scale stored entries in another pass; precompute index-to-stage mapping; use structural band/block storage throughout; perturb reusable node-local workspaces. These remove work without adding recovery strategies.

7. **Medium priority: repeated work is hidden by incomplete diagnostics.**

   Every `solveSingleProblem` reflashes its feed and fully audits its terminal state, including failed intermediate rungs whose audit cannot qualify them for publication. Same-pressure feature ramps and recoveries repeat invariant feed inputs. A failed optional truncation chain can then restart the complete cold chain without truncation. Separate iteration/rung budgets multiply across these paths.

   The final diagnostic construction hardcodes initializer iterations, residual evaluations, and linear solves to zero, and reports only the terminal attempt's Newton iterations. Consequently reported iteration counts cannot explain total calculation time.

   Sources: [feed flash](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:597), [unconditional audit](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:631), [diagnostic counters](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:1534), [cold restart](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3TruncationFallback.java:15).

   Direction: accumulate actual request-wide work counters first. Reuse immutable feed results for identical thermo inputs within a request, defer full audits of failed intermediate states until needed for terminal diagnostics, and share one aggregate numerical work budget across strategies.

8. **Further implementation issues deserve focused follow-up.**

   The strict flash convergence maximum includes exact-zero overall species, although Rachford–Rice and composition splitting skip them. Absent species can therefore impose irrelevant log-K convergence work. The source establishes this dependency; a failing zero-padding fixture was not reproduced here. See [flash update](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3FeedFlash.java:48).

   Optional flash truncation first requires an unrestricted flash to succeed, then can solve again with reduced support. It cannot rescue failure of the prerequisite unrestricted flash. See [truncated flash](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3TruncatedFlash.java:25).

   Normal products retain a compatibility branch in which any nonzero off-stage noise, even a subnormal, switches to dense cubic work. Tests lock in that behavior and cancellation-sensitive summation. Current colored differentiation explicitly leaves off-stage entries zero, so this is unnecessary complexity rather than a demonstrated cause of the measured column latency. See [normal products](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3NormalEquations.java:87).

The implementation sequence I recommend is: add truthful aggregate counters and regression probes for the concrete defects; fix support reachability and structural bandwidth handling; remove measured allocation/scanning overhead; then compare one coherent trace/convergence formulation against the existing cold DOE and independent benchmark. Simplify continuation only with those measurements. Do not stack another terminal conditioner onto the existing logarithmic certificate contract.

Validation performed for this review: compiled the current science sources with Java `--release 21` and local cached dependencies, then ran **36 focused JUnit tests, all passing**, covering truncation support, simultaneous solving, normal products, and banded LU. The disconnected-support and inconsistent-certificate probes ran against that fresh compilation. Their harness and output are [SolverAudit.java](D:/Minecraft/Modding/1.21/CreateChemE/build/solver-audit/SolverAudit.java) and [results.txt](D:/Minecraft/Modding/1.21/CreateChemE/build/solver-audit/results.txt); these build artifacts are ignored by Git. A normal Gradle test run could not configure offline because the Foojay resolver plugin was unavailable. No full-suite pass or new cold-DOE result is claimed.

# V3 numerical-kernel redesign notes

This is a code-structure proposal for the current `6c7d446` main-based branch. It is not a claim that the existing crude cases are physically feasible, and it does not change the declared acceptance audit or the independently tracked final-certificate limitation. The goal is one numerical kernel with one set of budgets and observable failure evidence, rather than another continuation or recovery layer.

This note isolates a backend experiment on the existing model. The [full redesign proposal](D:/Minecraft/Modding/1.21/CreateChemE/documentation/2026-09-05-v3-solver-core-review/V3_FUNDAMENTAL_SOLVER_REDESIGN.md) additionally replaces the log-flow/phase formulation and versions audit rules for zero phases; unchanged V3 equations/audits apply only in the overlapping legacy regime.

## Current core, as implemented

The physical problem is naturally local by stage. `V3MeshResidualEvaluator` creates component material, VLE, and energy rows for a resolved topology in [V3MeshResidualEvaluator.java](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3MeshResidualEvaluator.java:41). Material and energy rows couple adjacent stages; property calculations are made per node in `nodeProperties` at lines 149–173. Residual scaling is explicit by equation family at lines 217–225. This is the right model boundary to keep.

The numerical implementation does not retain that locality end-to-end.

| Layer | Current behavior | Consequence |
|---|---|---|
| Derivatives | `V3FiniteDifferenceJacobian` creates an `n × n` `double[][]` even on the coloured path ([lines 72–119](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3FiniteDifferenceJacobian.java:72)). It uses central or one-sided perturbations and falls back to individual columns when a colour has no feasible side ([lines 102–142](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3FiniteDifferenceJacobian.java:102)). | It allocates dense storage and makes thermodynamic residual calls primarily to rediscover a known stage-local stencil. One-sided probes make derivative quality state dependent at precisely the phase/domain boundaries that need predictable globalization. |
| Local predictor | The solver first attempts a separately assembled local block direction for sufficiently large systems ([V3SimultaneousColumnSolver.java:222–253](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:222)). `V3BlockJacobianAssembler` is described as extracting local blocks from finite-difference verification work, with special local probes. | Two derivative systems coexist: a special local predictor and the authoritative full finite-difference Jacobian. Their different failure behavior becomes solver policy. |
| Primary linear step | The full Jacobian is converted to a stage-banded matrix ([V3SimultaneousColumnSolver.java:265–280](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:265)), then scalar banded LU is used. | This is a sensible direction, but it is fed by a dense finite-difference representation. |
| Failure fallback | On direct-step failure, the solver forms `JᵀJ` and tries damped Gauss–Newton, then a clipped gradient direction ([V3SimultaneousColumnSolver.java:288–306](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:288)). `V3NormalEquations` has a dense compatibility path for any off-band term ([V3NormalEquations.java:127–145](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3NormalEquations.java:127)). | Forming normal equations squares the Jacobian condition number and lets numerical off-band noise choose a different, potentially dense algorithm. |
| Globalization | Every direction is Armijo backtracked, with 20 fine or 40 coarse trial steps ([V3SimultaneousColumnSolver.java:11–21](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:11)). A final extra Newton correction is required after residual convergence ([lines 204–216](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:204)). | The merit test only sees residual reduction. Feasibility, phase/domain distance, and trust in an approximate derivative are handled indirectly through rejected trials and recovery branches. |
| Linear diagnostics | `V3BandedPivotedSolver` scales rows and columns, performs bandwidth-limited partial pivoting, and reports a backward error ([V3BandedPivotedSolver.java:20–97](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/linalg/V3BandedPivotedSolver.java:20)). | After unscaling, backward error is evaluated against the matrix/RHS passed into the linear solver. If that input is a regularized normal system, it is not a certificate of the original Newton system or nonlinear MESH equations. |

The JFR panel supports the allocation concern in this design: before boundary correction, sampled allocation was dominated by objects created in setup/history rather than a stable solver source. After excluding the first allocation interval per thread, the remaining sample sources include boxing and local Jacobian work. That evidence is descriptive only, but is consistent with avoiding dense Jacobian materialization and repeated probe objects.

## Non-negotiable retained boundaries

Keep these boundaries independent of the replacement numerical kernel.

- `V3ColumnInput`, `V3ColumnProblem`, topology, component basis, truncation support, and resolved side-draw/steam semantics remain the physical input contract.
- The residual definitions in `V3MeshResidualEvaluator` remain the governing material, VLE, and energy equations. Refactoring them into a derivative-capable model must not change their equation IDs, raw values, or scales without a separately reviewed model change.
- The current fresh acceptance audit remains the only publication gate. A linear solver backward error, small step, or filter acceptance cannot publish a result.
- `V3SolveControl.checkpoint()` remains on every bounded numerical loop. Cancellation still escapes unchanged to the caller.
- The `V3ColumnCalculator` owns explicit continuation policy and terminal outcome translation. It should call one kernel per resolved problem, rather than embed alternative numerical engines.

## Proposed replacement: block-sparse constrained trust-region Newton

Introduce one package-private `V3BlockNewtonKernel` with four narrow collaborators.

```text
V3ResidualModel
  evaluate(x, workspace) -> ResidualSnapshot
  linearize(x, residual, workspace) -> BlockTridiagonalJacobian

BlockTridiagonalJacobian
  diagonal/lower/upper stage blocks in equation/unknown ledger order
  material blocks assembled analytically
  thermodynamic blocks supplied by derivative API or local AD

V3BlockLinearSolver
  solveNewton(J, -r, scaling) -> LinearStep + linear certificate

V3TrustRegionController
  proposes bounded step, evaluates actual/predicted reduction and feasibility
  returns ACCEPT, SHRINK, or TERMINATE
```

For a backend-only transitional experiment, coordinates may remain log hydrocarbon phase flows plus temperature. This does not resolve exact-zero or trace-relative-conditioning weaknesses. The full replacement instead needs the bound-capable physical formulation described in the main proposal. In either experiment, give variables and residual families explicit scales and constrain trial steps by the applicable physical domain.

At each iteration:

1. Evaluate the residual once and retain the immutable node-property snapshot used by both residual and derivative assembly.
2. Assemble the three stage blocks for each residual row. Material-balance log-flow derivatives are exact. VLE and enthalpy derivatives come from a thermodynamic derivative API; until that exists, use *stage-block directional* finite differences only, never a whole-system `n × n` array.
3. Solve the scaled Newton system with a tested pivoted sparse/banded LU when regular. Use rank-aware QR for a declared linearized least-squares step. Symmetric LDLᵀ is appropriate only for a separately formulated KKT system, not directly for the nonsymmetric Newton Jacobian. Report regularization and the actual factored system; avoid explicit `JᵀJ`.
4. Apply a trust-region/filter decision. Accept a trial if it improves residual merit sufficiently, or if it materially improves quantified domain/physical constraint violation while not degrading the filter. Update radius from actual versus predicted reduction. Rejected trial steps shrink the radius; they do not invoke a different recovery algorithm.
5. On convergence, re-evaluate the *unscaled raw equations* and the acceptance audit from a fresh workspace. Publish only if both satisfy their contracts. Store the nonlinear residual, constraint violation, linear residual/backward error, trust radius, and derivative provenance separately.

This replaces Armijo-only line search, normal-equation damping, clipped gradient descent, and the optional local-block predictor with one globalization policy. It does not add a new path through the physics; it makes the existing local physics the only derivative representation.

### Linear backend choices

| Option | Strength | Cost / risk | Recommendation |
|---|---|---|---|
| Block QR on the rectangular scaled Jacobian | Avoids normal equations; stable rank diagnosis; direct least-squares step. | More implementation work and fill than scalar tridiagonal LU. | Best first production-quality direct backend if a compact block QR implementation is feasible. |
| Block LDLᵀ on an augmented KKT system | Keeps symmetric sparse blocks; can report inertia/rank. | Indefinite pivoting and regularization need careful testing. | Good second choice where a robust block pivot strategy is available. |
| Current scalar banded LU on direct `J` | Existing tested primitive and natural for square full-rank cases. | Does not diagnose rank well; scalar `TreeMap` storage/allocation is expensive. | Retain temporarily behind `V3BlockLinearSolver`; replace storage with contiguous block arrays. |
| Krylov method with block preconditioner | Lower memory for larger columns. | Tolerances and preconditioner quality become a second convergence policy. | Do not use for the first rewrite; it obscures diagnosis while current 30–64 stage cases are tractable directly. |

### Derivative choices

| Variant | Use | Tradeoff |
|---|---|---|
| Analytic plus thermodynamic derivative API | Exact material derivatives and EOS/enthalpy derivatives from one thermodynamic call contract. | Largest initial engineering cost; best reproducibility and testability. |
| Forward-mode automatic differentiation at a stage block | A practical bridge when the PR implementation can propagate block dual numbers. | More memory per property evaluation; avoids finite-difference branch noise. |
| Block directional finite differences | Only perturb one stage block, reuse an immutable base residual snapshot, and produce lower/diagonal/upper blocks directly. | Acceptable transitional oracle; must report one-sided use and never silently fall back to dense global columns. |

The rewrite should select one variant per run and record it in the solver certificate. It must not choose an unreported derivative fallback based on an incidental domain exception.

## What to delete, retain, and refactor

Delete after the replacement has equivalent regression coverage:

- Whole-system `V3FiniteDifferenceJacobian.Jacobian` dense `double[][]` construction and the stage-colouring/global-column fallback machinery.
- `V3NormalEquations`, `dampedGaussNewtonTrial`, and clipped gradient fallback. These are compensating algorithms created by the same approximate-Jacobian failure modes.
- `V3BlockJacobianAssembler` as a separate predictor. Its exact material-row logic and local property concepts should move into the sole derivative API.
- Policy branches that treat off-band finite-difference noise as a reason to select another linear system.

Retain and adapt:

- `V3MeshResidualEvaluator` equation ownership, scales, node-property calculation, and `V3DegreeOfFreedomLedger` ordering.
- `V3BandedPivotedSolver` tests, backward-error calculation, finite/pivot guards, and cancellation checks. Use them to validate the transitional direct backend, then replace its `TreeMap` `SparseRows` storage with block-contiguous storage.
- `V3ConvergenceEvidence` as an outcome type, but add fields or a sibling `V3NumericalCertificate` rather than overloading its final-step flag.
- Fresh physical auditing, using `V3AcceptanceAuditor` for compatible legacy states and a versioned audit for new zero-phase states. Preserve conservation and authored specifications; the shared residual evaluator is not an independent physical oracle.

Refactor `V3ColumnCalculator` so its continuation code selects an input and seed but does not choose local-block, normal-equation, coarse, or gradient numerical personalities. Each `solveSingleProblem` call should receive an immutable `V3KernelConfig` and return the same `KernelOutcome` shape.

## Certificate and budget contract

The replacement must report request-wide work rather than the current terminal-attempt-only counters.

```text
NumericalCertificate
  derivativeMode: ANALYTIC | STAGE_AD | BLOCK_FINITE_DIFFERENCE
  residualEvaluations, propertyEvaluations, jacobianAssemblies
  linearFactorizations, linearIterations, trustTrials
  acceptedSteps, rejectedSteps, domainRejectedTrials
  finalScaledInfinityNorm, finalRawResidualFamilyNorms
  linearBackwardError, rankOrPivotDiagnostic
  finalTrustRadius, termination
```

Each budget is a hard request-local limit. Reaching it returns a typed `WORK_BUDGET_EXHAUSTED` outcome with the last valid state and certificate. It is not converted to an ordinary nonconvergence string. The outer 60-second deadline remains independent and has precedence.

Fresh final verification must comprise: re-evaluate raw residuals; verify all physical/domain constraints; run the existing acceptance audit; and, if a linear certificate is exposed, identify it as a certificate of the last linearized system only. This resolves the current ambiguity where a final Newton-step marker can be mistaken for an original-system certificate.

## Test plan before production cutover

1. **Derivative contracts.** For each equation family and phase branch, compare analytic/AD block derivatives with a high-precision block finite-difference oracle. Assert exact ledger ordering and zero blocks outside the adjacent-stage stencil.
2. **Linear contracts.** Construct well-conditioned, rank-deficient, pivoting, and off-band-rejection fixtures. Compare block solver steps to an independent dense QR oracle on small systems. Test certificate fields, not only solutions.
3. **Globalization contracts.** Fixtures for accepted Newton step, trust-radius shrink, feasible filter acceptance, domain rejection, and each typed budget exhaustion. Assert no mutable seed survives a failed call.
4. **Model regression.** Existing support, acceptance, product, closure, low-pressure, side-draw, steam, and truncation tests run unchanged against the replacement. Exact-off results need an explicit compatibility review rather than a silent tolerance relaxation.
5. **Cold benchmark gate.** Run the frozen paired suite only after numerical and audit tests pass. Compare accepted outcomes, support/fallback classification, raw audits, product drift, request-wide work counters, and isolated timings. Do not accept a speed gain that comes from skipping final verification.

## Small first experiments

Before a full rewrite, run two bounded experiments in a separate numerical branch.

1. Replace only dense global Jacobian storage with a block-stencil container while retaining block finite differences. Verify bit-close residual/Jacobian entries and report allocation/property-call counts on A150 and N30. This measures the data-layout benefit without changing globalization.
2. Replace only `JᵀJ` fallback with direct block least-squares/QR on failures, leaving the primary direct Newton step unchanged. Compare typed linear failure, residual reduction, and certificate rank diagnostics on the known low-pressure and side-draw failures. Do not add retries.

If either experiment changes an accepted result, audit it as a model/solver behavior change before combining it with the trust-region rewrite.

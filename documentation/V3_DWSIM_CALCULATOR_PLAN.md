# V3 Distillation Column Calculator — DWSIM-Inspired Implementation Plan

- Status: implementation plan for `codex/v3-dwsim-calculator-test`
- Repository baseline: `d4755a5f95f94459942a34736cc607d1ed140cb1`
- Target package: `com.wormzjl.createcheme.science.column.v3`
- Decision owner: CreateChemE maintainers
- Plan review gate: required before production implementation begins

## 1. Executive decision

V3 should be a clean, isolated calculator rather than another patch to the legacy or retired V2 solver. Its numerical strategy will follow the successful division of responsibilities visible in DWSIM:

1. validate the physical problem and its degrees of freedom;
2. construct a physically plausible column state with a modified Wang–Henke-style initializer;
3. converge the complete material, equilibrium, summation, and heat equations simultaneously with a Naphtali–Sandholm-style Newton method;
4. independently recompute every acceptance criterion before publishing a result.

The production Newton system will preserve the stage-local block-tridiagonal structure instead of using DWSIM's current dense nonlinear linear algebra. DWSIM is an external behavioral oracle and an architectural reference, not a source-code donor. No GPL source will be copied into this MIT-licensed project.

V3 will also be a new product surface: a new block ID, menu, screen, network protocol, persisted schema, request/result types, and solver package. Existing calculator blocks and their saved data remain untouched until V3 has passed the scientific, runtime, and migration gates in this plan.

## 2. Scope and non-goals

### V3.0 scope

- steady-state equilibrium-stage distillation;
- one characterized crude feed, with bounded side draws and water/steam utility feeds added only after the dry core passes;
- fixed top pressure plus a constant per-stage pressure drop, resolved to an immutable pressure profile;
- the work's explicit partial condenser and equilibrium partial reboiler boundary models;
- vapor–liquid equilibrium through a narrow hydrocarbon thermodynamic interface;
- the existing 16-hydrocarbon public basis plus separately modeled water;
- Peng–Robinson for hydrocarbons and IAPWS-IF97 for water;
- all-liquid, two-phase, and all-vapor feed flashes;
- the deliberately narrow specification set already defined for the experimental calculator: condenser outlet temperature, organic reflux ratio, and reboiler duty, with calculated condenser duty and product flows;
- cold start, exact-input warm start, cancellation, deadline, diagnostics, and strict acceptance;
- server-authoritative execution through the existing bounded CPU solve service;
- reproducible comparison against pinned DWSIM cases.

### Deliberate non-goals for V3.0

- rate-based mass transfer, tray hydraulics, predicted pressure drop, reactions, electrolytes, solids, or a generic three-phase flash model;
- copying DWSIM implementation code or attempting feature parity with DWSIM;
- silently interpreting legacy or retired V2 saved data as V3 data;
- using a second publishable solver as a fallback;
- declaring success from iteration count, step size, temperature stability, or a plausible-looking product split;
- optimizing throughput before the scientific acceptance suite passes.

## 3. DWSIM strategy being adopted

The reference is pinned so later DWSIM changes cannot silently change V3's target behavior:

- DWSIM repository and revision: [`DanWBR/dwsim10@9aad13d`](https://github.com/DanWBR/dwsim10/commit/9aad13d62675db44388cdde4511cecf66b6e8066)
- column orchestration and robust initialization: [`RigorousColumn.vb`, robust initialization](https://github.com/DanWBR/dwsim10/blob/9aad13d62675db44388cdde4511cecf66b6e8066/engine/DWSIM.UnitOperations/UnitOperations/RigorousColumn.vb#L4903-L4917)
- algorithm dispatch/retry behavior: [`RigorousColumn.vb`, solver dispatch](https://github.com/DanWBR/dwsim10/blob/9aad13d62675db44388cdde4511cecf66b6e8066/engine/DWSIM.UnitOperations/UnitOperations/RigorousColumn.vb#L5930-L6127)
- post-solve balance checks: [`RigorousColumn.vb`, balance validation](https://github.com/DanWBR/dwsim10/blob/9aad13d62675db44388cdde4511cecf66b6e8066/engine/DWSIM.UnitOperations/UnitOperations/RigorousColumn.vb#L6423-L6428)
- Wang–Henke material-flow solve: [`BubblePoint.vb`](https://github.com/DanWBR/dwsim10/blob/9aad13d62675db44388cdde4511cecf66b6e8066/engine/DWSIM.UnitOperations/UnitOperations/Auxiliary/DistillationColumn/BubblePoint.vb#L1117-L1197)
- simultaneous Naphtali–Sandholm equations: [`NewtonRaphson.vb`](https://github.com/DanWBR/dwsim10/blob/9aad13d62675db44388cdde4511cecf66b6e8066/engine/DWSIM.UnitOperations/UnitOperations/Auxiliary/DistillationColumn/NewtonRaphson.vb#L632-L699)
- Newton initialization handoff: [`NewtonRaphson.vb`](https://github.com/DanWBR/dwsim10/blob/9aad13d62675db44388cdde4511cecf66b6e8066/engine/DWSIM.UnitOperations/UnitOperations/Auxiliary/DistillationColumn/NewtonRaphson.vb#L1287-L1305)
- current dense Newton implementation: [`NewtonSolver.vb`](https://github.com/DanWBR/dwsim10/blob/9aad13d62675db44388cdde4511cecf66b6e8066/engine/DWSIM.MathOps/NewtonSolver.vb#L240-L448)
- DWSIM licensing: [GPLv3 notice](https://github.com/DanWBR/dwsim10/blob/9aad13d62675db44388cdde4511cecf66b6e8066/README.md#L6-L10)
- independent algorithm reference: [Naphtali and Sandholm, “Multicomponent separation calculations by linearization”](https://doi.org/10.1002/aic.690170130)
- independent initializer reference: [“An Improved Algorithm Using the Wang–Henke Tridiagonal Matrix Method”](https://doi.org/10.1021/ie0108898)

The mapping is intentional:

| DWSIM idea | V3 adoption | V3 difference |
| --- | --- | --- |
| Explicit column topology and specifications | Typed topology and specification records | A formal degree-of-freedom ledger rejects unsupported combinations before solving |
| Bubble/dew and relative-volatility-based initial estimates | Endpoint flashes and volatility-guided profiles | Pure Java clean-room implementation behind V3 interfaces |
| Modified Wang–Henke initialization | Per-component tridiagonal material solves with damped temperature/energy updates | Initializer only; it cannot return a public success |
| Naphtali–Sandholm simultaneous correction | Full MESH residual and Newton correction | Stage-blocked banded Jacobian rather than a dense production matrix |
| Solver retries | Deterministic globalization and continuation of the same physical model | No alternate result-producing fallback |
| Post-solve balance checks | Separate immutable acceptance audit | Checks local/global material, energy, equilibrium, specification, bounds, and finiteness |

The older online API descriptions must not override the pinned source. In particular, the pinned DWSIM Newton path currently uses dense nonlinear algebra even though some descriptions refer to a custom block-tridiagonal Newton method. V3 will exploit the block structure as its own engineering decision.

### 3.1 Assessment of the current implementation strategy

The retired V2 work contained several useful historical boundaries, but its numerical core was not an adequate V3 base. The distinction matters because a green fail-closed suite is not evidence that a real column has been solved.

| Current area | Assessment | V3 action |
| --- | --- | --- |
| Historical V2 immutable problem/outcome concepts and strict acceptance audit | Strong direction | Recreate as V3-owned contracts and make audit the only success factory |
| Property dataset, component basis, PR and IF97 work | Valuable, subject to parity tests | Reuse through one-way package-private adapters first; extract neutral code only after both suites pass |
| Historical V2 Sum-Rates/inside-out iteration | The former default failed closed and had no production nominal-success test | Replace, not subclass: modified Wang–Henke seed plus simultaneous block Newton |
| Historical V2 equation/unknown counts | Equal expressions do not prove an independent square model | Replace with separately enumerated unknown, equation, specification, structural-rank ledgers |
| Current feed flash | Two-phase-bracket behavior does not cover valid single-phase feeds | Implement explicit liquid/two-phase/vapor outcomes |
| Current phase-stability repair | Candidate compositions can overwrite conserved flows | TPD may classify/seed only; raw balances remain authoritative |
| Component/tridiagonal tests | Useful for individual material rows | Retain as algebra evidence, but add a genuinely independent full-MESH dense oracle |
| Bounded shared execution and server-thread commit | Appropriate CPU/runtime design | Extend additively; do not create a V3 executor |
| Next block/menu/network/persistence | Useful comparison product | Leave untouched; V3 receives new IDs, codecs, state machine, and schema |

V3 is therefore an architectural fork at the science boundary and an additive extension at the process-runtime boundary. It is not a rename of `Next`, and failure never invokes `Next` or the legacy solver.

## 4. Physical contract and degree-of-freedom policy

### 4.1 Freeze one baseline problem first

The first accepted end-to-end case will use:

- the existing partial condenser, with specified outlet temperature and calculated heat removal;
- an equilibrium partial reboiler;
- one dry hydrocarbon feed on a declared equilibrium tray;
- a prescribed monotone pressure profile;
- a component-count-generic test model first, followed by the fixed ordered 16-hydrocarbon CDU basis;
- specified condenser outlet temperature, organic reflux ratio, and reboiler duty;
- no side draws, water, or utility feeds in the first nominal-success fixture.

This preserves the scientific/gameplay contract in the current handoff: the partial condenser permits noncondensable C1–C3 overhead, reflux is withdrawn only from condensed hydrocarbon liquid, and the external overhead is the remaining hydrocarbon liquid plus all gas. At positive reflux, independently proven insufficient condensate is `INFEASIBLE_SPECIFICATION`, not numerical nonconvergence; one poor seed with too little condensate is only an initialization failure. At `R=0`, a stable two-phase condenser retains liquid as entirely external product; a genuinely vapor-only condenser compiles a distinct phase branch that removes absent liquid/reflux variables and matching equilibrium equations.

The three authored controls do not simply add three equations to a fixed unknown vector. Specifying condenser temperature removes that boundary temperature from the unknowns; condenser duty remains calculated. Reboiler duty enters its energy balance as a prescribed term. Reflux ratio defines the internal split of calculated condensate. M0 must independently reproduce this equation/unknown map and structural rank before code treats it as valid. V3 must never add a product-flow or condenser-duty input to this set without a new specification schema and DOF proof.

### 4.2 Degree-of-freedom ledger

`V3DegreeOfFreedomLedger` will build a structural report from topology and specifications:

- enumerate every unknown by stable semantic ID;
- enumerate every equation by residual family and stage;
- list prescribed variables and calculated variables;
- reject duplicate control of the same physical degree of freedom;
- detect missing specifications;
- reject equations referencing a phase removed by a topology branch;
- emit a human-readable diagnostic used by tests and the UI.

`unknownCount == equationCount` is necessary but not sufficient. Tests must also check structural rank for every supported small topology by constructing the sparsity incidence matrix and performing a maximum bipartite matching. Numerical rank checks at representative states provide a second guard against dependent specification equations.

### 4.3 Stage state and equations

For a dry two-phase equilibrium stage `j`, use hydrocarbon component liquid flows `l[j,i]`, hydrocarbon component vapor flows `v[j,i]`, and temperature `T[j]`. Totals and compositions are derived:

```text
L[j] = sum_i l[j,i]       x[j,i] = l[j,i] / L[j]
V[j] = sum_i v[j,i]       y[j,i] = v[j,i] / V[j]
```

The stage residual block contains:

- one material balance for every component;
- one vapor–liquid fugacity/equilibrium equation for every component;
- one energy balance.

Using component flows makes phase summations definitions rather than weakly enforced side equations. The equilibrium residual should be logarithmic where both fugacities are defined, which is better scaled than raw `y - Kx`. Topology-specific maps remove physically absent variables instead of retaining zero-flow dummy phases.

For `N = S + 2` dry nodes and `Cactive` components in the positive-reflux branch, separately enumerate `2*Cactive*N + N - 1` Newton unknowns and the same number of equations: `Cactive*N` material rows, `Cactive*N` equilibrium rows, and `N-1` tray/reboiler energy rows. Condenser temperature is prescribed; condenser duty is derived from its energy closure and is not an independent control. Exact-zero external-feed components are removed from the active numerical map and restored as exact zeros in reporting.

Positive active component flows use scaled log coordinates such as `flow = componentScale*exp(u)`. No additive floor enters physical balances. Exact-zero components are eliminated, while every positive trace component receives a nonzero scale derived from its external input and is reconstructed directly for acceptance.

After the dry 16-hydrocarbon solver passes, add water as a separate immiscible subsystem rather than a seventeenth Peng–Robinson component. An ordinary tray/reboiler block contains `2*Cactive+3` unknowns—active hydrocarbon liquid/vapor flows, aqueous-water flow, water-vapor flow, and temperature—matched by `Cactive` hydrocarbon balances, `Cactive` hydrocarbon fugacity equations, one water balance, one water complementarity equation, and one energy balance. With all 16 hydrocarbons active this is the familiar 35-by-35 block; a zero-feed methane case is smaller. The specified-temperature condenser has the corresponding material/water block; its heat removal remains a derived result checked by the final audit.

Use a scaled Fischer–Burmeister residual or an explicitly tested active-set/generalized derivative for water condensation. Water never enters the hydrocarbon `kij` matrix or hydrocarbon reflux. The combined energy equation includes hydrocarbon and water sensible/latent enthalpy. Zero-water inputs must reproduce the dry path within the frozen tolerance profile.

## 5. Proposed code architecture

```text
UI/network snapshot
        |
        v
V3ColumnInput -> V3ColumnProblemResolver -> V3ColumnProblem + V3DegreeOfFreedomLedger
                                                |
                                                v
                                      V3ColumnInitializer
                                                |
                                                v
                                     V3SimultaneousSolver
                                      /       |        \
                            residual model  block J  block linear solve
                                      \       |        /
                                                v
                                      candidate state
                                                |
                                                v
                                      V3AcceptanceAudit
                                       /              \
                               accepted result      typed failure
```

### 5.1 Package layout

Create `src/main/java/com/wormzjl/createcheme/science/column/v3/` with these responsibilities:

- `V3ColumnInput`: immutable user-facing SI input record; defensive copies for arrays/lists.
- `V3ColumnProblem`: fully resolved, immutable numerical problem.
- `V3ColumnProblemResolver`: typed validation, normalization, pressure construction, model lookup, and DOF audit.
- `V3ColumnCalculator`: stateless/thread-safe public solve façade.
- `V3ColumnTopology`: stage and boundary-phase map; no numerical behavior.
- `V3ColumnSpecification`: sealed specification family with units and controlled variable.
- `V3DegreeOfFreedomLedger`: structural equation/unknown/specification report.
- `V3ThermoModel`: narrow fugacity, enthalpy, flash, and stability contract.
- `V3PengRobinsonThermo`: initial production thermodynamic implementation.
- `V3WaterModel` and `V3If97WaterModel`: saturation and liquid/vapor water enthalpy outside the hydrocarbon mixing model.
- `V3ThermoWorkspace`: solve-local reusable buffers; never shared between requests.
- `V3FeedFlash`: all-liquid/two-phase/all-vapor flash result.
- `V3ColumnState`: solver-owned mutable numerical workspace, never exposed publicly.
- `V3ColumnSnapshot`: immutable reconstructed physical state used for audit/results.
- `V3ColumnInitializer`: DWSIM-inspired seed construction.
- `V3MeshResidualEvaluator`: full residual calculation with scale metadata.
- `V3BlockJacobianAssembler`: lower/diagonal/upper stage blocks.
- `V3BandedPivotedSolver`: bandwidth-preserving scalar LU with partial pivoting and condition diagnostics.
- `V3BlockTridiagonalSolver`: optional certified fast path for well-conditioned stage blocks.
- `V3SimultaneousColumnSolver`: globalization, continuation, limits, and cancellation.
- `V3AcceptanceAudit`: independent final recomputation.
- `V3ColumnOutcome`: sealed `Success`/`Failure` public outcome.
- `V3ColumnResult`: immutable accepted physical result.
- `V3ResultProvenance`: deterministic solver/formulation/dataset/basis/assumption/tolerance revisions and scientific digest.
- `V3ConvergenceEvidence`: immutable last-step/linear-solve evidence, distinct from independently recomputed physics.
- `V3SolverDiagnostics`: immutable iteration and residual evidence.
- `V3SolverFailureCode`: stable typed failure categories.
- `V3SolveControl`: cancellation token, deadline, and iteration/evaluation budgets.
- `V3InputDigest`, `V3WarmState`, and `V3ResultView`: versioned cache and presentation boundaries.

Keep linear algebra in `science.column.v3.linalg` and thermodynamics in `science.column.v3.thermo` once either area exceeds a few classes. Do not make public utility classes prematurely; expose one package-level solver façade.

V3 core must not import legacy solver types or retired V2 input/outcome/warm-state/cache types. Its immutable property data and proven PR arithmetic now live in the V3-owned solve-local thermo package; no retired V2 code remains in the runtime dependency graph.

### 5.2 API rules

- Inputs, public outcomes, diagnostics, and cached values are immutable.
- Public constructors validate invariants or remain package-private behind factories.
- Every array crossing a public boundary is copied on input and output.
- Thermodynamic model instances are immutable; mutable scratch buffers belong to one solve.
- Expected numerical failure is data in `V3ColumnOutcome.Failure`, not an exception.
- Programmer errors may throw, but the runtime boundary converts unexpected throwables into a sanitized `INTERNAL_ERROR` while preserving a server log/correlation ID.
- No Minecraft class appears in the science package.
- No V3 worker captures a block entity, level, menu, player, registry view, or mutable collection.
- A `V3WarmState` can be created only from a committed accepted result and cannot be serialized.

## 6. Thermodynamics and phase handling

### 6.1 Narrow thermodynamic boundary

`V3ThermoModel` is the component-count-generic hydrocarbon boundary and should support, at minimum:

```java
V3FugacityResult fugacity(double temperatureK, double pressurePa,
                          double[] composition, V3Phase phase,
                          V3ThermoWorkspace workspace);

double molarEnthalpy(double temperatureK, double pressurePa,
                     double[] composition, V3Phase phase,
                     V3ThermoWorkspace workspace);

V3FlashResult flashTP(double temperatureK, double pressurePa,
                      double[] overallComposition,
                      V3ThermoWorkspace workspace);
```

The final signature may use caller-provided result buffers to reduce allocations, but ownership must remain explicit.

Water is deliberately not part of that composition vector. `V3WaterModel` supplies IF97 Region 1/2/4 saturation and enthalpy operations in SI, including the current hard domain checks and isenthalpic utility-feed throttling contract.

### 6.2 Peng–Robinson requirements

- use one documented unit system internally: K, Pa, mol, J;
- record component property provenance and binary interaction parameters;
- sort/order components once in the problem factory and preserve that order everywhere;
- select liquid and vapor roots deterministically, with explicit near-critical handling;
- compute departure enthalpy consistently with the fugacity model;
- treat zero/tiny fractions without `log(0)` or normalization drift;
- return typed domain/convergence failures containing stage and phase context;
- validate analytic or semi-analytic values against independent reference calculations before the column solver uses them.

Phase-stability/TPD calculations classify and seed a local hydrocarbon phase state only. They must never repair a stage by overwriting conserved component flows. Any hydrocarbon phase appearance/disappearance beyond the declared tray/reboiler topology is a controlled `PHASE_REGIME_MISMATCH`; aqueous-water appearance is handled only by the separately tested water complementarity formulation.

### 6.3 Feed flash

The crude-feed flash must return an explicit hydrocarbon phase classification:

- `LIQUID`: vapor fraction exactly zero with normalized liquid composition;
- `TWO_PHASE`: a converged Rachford–Rice root and both compositions;
- `VAPOR`: vapor fraction exactly one with normalized vapor composition.

Enthalpy and feed quality used by the initializer come from that same result. Single-phase feeds are normal inputs, not exceptional edge cases.

Each water/steam utility feed has its own resolved stage, molar flow, inlet temperature, and upstream pressure. The server resolves its initial phase and enthalpy, validates the pressure direction, and throttles it isenthalpically to the stage pressure before worker admission. Utility feeds are added only after the dry/no-utility solver and zero-water invariance tests pass.

## 7. DWSIM-inspired initialization

`V3ColumnInitializer` produces a seed and evidence; it never produces `Success`.

### Step A — normalize and establish physical bounds

- normalize feed fractions once and reject a material correction larger than the configured input tolerance;
- flash the feed at its declared condition;
- calculate bubble/dew endpoint estimates at the column-end pressures;
- establish component-specific volatility ordering and finite temperature bounds;
- derive flow and energy scaling from feed totals rather than hard-coded component counts.

### Step B — estimate split and internal flows

- use relative volatility and light/heavy-key information to estimate product splits;
- use specified condenser temperature, organic reflux ratio, and reboiler duty to estimate condensate, external overhead, bottoms, reflux, and boilup;
- preserve the partial-condenser gas/liquid split; a positive-reflux seed with insufficient condensate fails that initialization attempt unless independent feasibility evidence proves the specification impossible;
- include feed thermal condition in the above/below-feed flow estimates;
- reject negative or non-finite endpoint flow estimates rather than taking absolute values.

### Step C — build stage profiles

- interpolate temperature between endpoint flash estimates, biased around the feed stage;
- initialize liquid/vapor compositions with volatility-weighted feed/product estimates;
- normalize only property-evaluation scratch compositions and preserve each positive trace component through its scaled log coordinate;
- build liquid/vapor component flows from the composition and internal-flow profiles.

### Step D — modified Wang–Henke refinement

For a bounded number of initializer iterations:

1. evaluate `K`, liquid/vapor enthalpy, and feed terms at every stage;
2. assemble and solve the per-component tridiagonal material equations;
3. update only tray/reboiler temperatures `T[1..N-1]` from bubble-point/equilibrium closure residuals; condenser `T[0]` remains prescribed;
4. update internal flows from energy balances;
5. damp all updates with physical step limits;
6. record material, equilibrium, energy, and bound violations.

The existing Thomas algorithm may inform this component solve only after its indexing, pivot, and residual certificate tests pass. The V3 implementation should own its types and must not inherit the current hard-coded 16-component assumptions.

At the positive-reflux partial condenser, refinement updates condensate and vapor component flows under material and VLE closure while holding the authored condenser temperature. A vapor-only `R=0` condenser has no liquid bubble-point equation.

### Step E — deterministic handoff

- hand the best finite physical seed to the simultaneous solver;
- if the refinement diverges, hand off the guarded Step C seed if it remains admissible;
- if neither state is admissible, return `INITIALIZATION_FAILED` with evidence;
- never label the initializer state converged, accepted, or publishable.

## 8. Simultaneous MESH solver

### 8.1 Residual formulation

`V3MeshResidualEvaluator` will compute a scaled vector containing:

- `M[j,i]`: local component material balances;
- `E[j,i]`: component fugacity-equilibrium residuals;
- `H[j]`: local energy balances;
- after the dry gate, `MW[j]` and `FB[j]`: water balance and scaled water complementarity;
- boundary/specification equations selected by the topology map.

Every residual has a physical unscaled value, a documented scale, and a family label. Scaling uses feed component flow, total feed enthalpy flow, and safe minimum reference values. Solver norms use the scaled vector; the acceptance report retains both scaled maxima and physical closure values.

### 8.2 Block-banded Jacobian

A stage residual depends only on the previous, current, and next stage state, so the Jacobian has lower, diagonal, and upper blocks. V3.0 will use a hybrid assembly:

- exact algebraic derivatives for direct interstage flow couplings;
- scale-aware local numerical differentiation for thermodynamic terms;
- one-sided differences near admissibility bounds;
- a test mode that builds a full finite-difference dense Jacobian for comparison.

Perturbing a stage block must reevaluate only affected residual blocks. Tests will compare every assembled nonzero block with a whole-system finite-difference oracle and verify that all supposedly zero off-band entries are below tolerance.

### 8.3 Banded/block linear solve

The correctness path is `V3BandedPivotedSolver`, because a nonsingular block-tridiagonal matrix can still produce a singular intermediate Schur block in unpivoted block Thomas elimination. It will:

- factor the scalar banded form with bandwidth-limited partial pivoting;
- apply row and column scaling before elimination;
- report small pivots, growth, regularization, and backward error;
- leave input matrices unchanged;
- return a typed singular/ill-conditioned result instead of NaNs.

An optional `V3BlockTridiagonalSolver` may become a fast path only when every intermediate block/Schur factor passes a certificate. If it cannot certify a matrix, the same assembled Newton system goes through the banded pivoted path; this is a linear-algebra choice, not an alternate physical solver. For small matrices, tests compare both corrections with an independent dense pivoted solve. A full dense solver is test-only and must not become a production fallback that hides assembly defects.

### 8.4 Globalization and continuation

Each Newton iteration will:

1. check cancellation, deadline, and evaluation budgets;
2. evaluate and retain the best finite state for diagnostics;
3. assemble the block Jacobian and solve for a scaled correction;
4. cap temperature and transformed-flow steps;
5. perform an Armijo backtracking line search on a weighted residual merit function;
6. reject any trial with invalid thermodynamics, non-finite values, or violated hard bounds;
7. accept a step only when it satisfies the globalization rule.

If a direct step is ill-conditioned or repeatedly rejected, the same model may use diagonal regularization/trust-region damping. Do not implement continuation until the direct cold binary and dry-CDU paths are proven; otherwise it can hide a wrong equation set. After that gate, difficult cold starts may use a deterministic continuation parameter that ramps the same final problem from a dry/zero-drop/zero-draw seed or simplified volatility seed. Continuation is successful only at the complete target problem and full thermodynamics; intermediate states are not results.

Retries may vary damping, continuation increments, or the admissible initializer seed. They may not switch to a different physical equation set and then publish that answer.

The initial attempt order is compatible committed warm seed, canonical endpoint/flash cold seed, then regenerated cold seed with stronger damping. Each attempt uses the same Newton equations, absolute deadline, and final audit. Once qualified, continuation follows these direct attempts and records every increment/halving in bounded diagnostics.

### 8.5 Termination

Newton convergence requires all configured residual-family thresholds, a finite admissible state, and acceptable step/conditioning evidence. A small step with a large residual is `STAGNATED`, not success. Exhausted limits, deadline, cancellation, singularity, thermo failure, or line-search failure produce distinct codes.

V3 inherits the current handoff's Section 7.7 physical acceptance limits; it does not invent a looser V3 profile. Let `Fstar` be total external molar inflow, `Fabs = 1e-12*Fstar`, and `Eabs = 1e-12*Fstar*100000 J/mol` in watts:

| Independently recomputed physical check | Acceptance |
| --- | --- |
| Each local/global component, total-HC, and water balance | `abs(r) <= Fabs + 1e-9*sum(abs(balance terms))` |
| Each node energy balance | `abs(E) <= Eabs + 1e-7*Escale` |
| Rigorous hydrocarbon equilibrium | `max abs(ln(fL_i/fV_i)) <=1e-8` |
| Raw-flow equilibrium equations | material-style absolute-plus-relative guard |
| Composition sums used for reporting/properties | `<=1e-12` |
| Authored side-draw flow | `abs(Ucalc-Uauth) <= Fabs + 1e-10*max(Fstar,abs(Uauth))` |
| Organic reflux ratio | `abs(Rcalc-Rauth) <= 1e-10*max(1,abs(Rauth))` |
| Wet-node saturation | `abs(pW-Psat)/P <=1e-8` |
| Scaled Fischer–Burmeister water complementarity | `<=1e-10` |

Because totals/compositions are reconstructed directly from raw component flows, V3 has no independent Sum-Rates/trial-flow closure equation. Composition-sum limits are reconstruction/property guards, not Newton rows.

The final accepted Newton step also carries solver evidence that the independent auditor cannot recreate from a snapshot:

| Immutable convergence evidence | Required limit |
| --- | --- |
| Banded/block linear backward error | `<=1e-12` |
| Final flow-state change | `max abs(delta ln flow) <=1e-8` |
| Final temperature change | `<=1e-6 K + 1e-9*T` |

These are per-step/convergence guards, not substitutes for physical closure. Require finite nonnegative flows, positive hydrocarbon partial pressure, valid model ranges/roots, and phase indicators consistent with the declared topology. Threshold changes require a benchmark and scientific review; they must not be relaxed merely to turn a failing case green.

## 9. Independent acceptance and diagnostics

`V3AcceptanceAudit` receives only the immutable problem and reconstructed snapshot and returns an immutable physical audit report. It must not reuse the solver's cached residual vector, convergence flag, convergence evidence, or mutable workspace.

It independently checks:

- every number is finite;
- temperatures, pressures, flows, fractions, vapor fractions, and duties are in declared domains;
- each phase composition is normalized;
- each stage and component material balance;
- global component and total material balance;
- each stage energy balance and overall energy balance;
- phase-equilibrium/fugacity closure on every equilibrium stage;
- water saturation/complementarity and separate water material closure when water is enabled;
- condenser/reboiler boundary equations;
- every user specification;
- topology invariants and absent-phase flows;
- reconstructed products agree with stage boundary flows.

One package-private accepted-result gate requires both a passing independent audit report and complete `V3ConvergenceEvidence` within its per-step limits before constructing `V3ColumnOutcome.Success`. The numerical iteration code has no public `success(...)` factory, and a small step can never compensate for a failed physical residual.

Failures include a stable code, concise user message, detailed server message, worst residual family/stage/component, iteration/evaluation counts, termination reason, and sanitized correlation ID. Recommended codes are:

```text
INVALID_INPUT, UNSUPPORTED_MATERIAL, PROPERTY_PACKAGE_MISMATCH,
DOF_MISMATCH, INFEASIBLE_SPECIFICATION, PROPERTY_OUT_OF_RANGE,
EOS_ROOT_FAILURE, WATER_PROPERTY_FAILURE, INITIALIZATION_FAILURE,
SINGULAR_JACOBIAN, LINE_SEARCH_EXHAUSTED, PHASE_REGIME_MISMATCH,
STAGNATED, MAX_ITERATIONS, CONTINUATION_FAILURE, DEADLINE_EXCEEDED,
CANCELLED, ACCEPTANCE_FAILED, INTERNAL_INVARIANT_FAILURE
```

## 10. Runtime, concurrency, and server integration

V3 reuses [`BoundedCpuSolveService`](../src/main/java/com/wormzjl/createcheme/runtime/BoundedCpuSolveService.java), [`ProcessSolveServices`](../src/main/java/com/wormzjl/createcheme/runtime/ProcessSolveServices.java), and the server-thread confinement pattern in [`ProcessSolveCoordinator`](../src/main/java/com/wormzjl/createcheme/network/ProcessSolveCoordinator.java). It does not create an executor per block, per request, or per solver version.

The execution lifecycle is:

1. On the server thread, validate player/menu/block/distance, rollout mode, expected input revision, packet bounds, and resolved scientific problem.
2. Assign process-wide operation ID, non-persisted server-lifetime block-instance token, input/state revisions, and input digest.
3. Retain any older accepted result as visibly stale and enter `CALCULATING`.
4. Check only the V3 exact-result cache; a hit has no worker `JobStamp` but still travels through the same operation/revision/provenance commit guard.
5. Otherwise construct the service `JobStamp` with server epoch, exact owner, operation/revision stamp, and absolute deadline, then submit a pure immutable `V3ColumnCommand`; the service creates and owns the cancellation token.
6. Keep all solve-local mutable state inside that task and return only an immutable outcome.
7. Queue completion back to the server thread.
8. Commit only if the exact V3 block instance still exists and operation ID, block-instance token, server epoch, revisions, digest, schema, solver, formulation, dataset, and acceptance profile still match.
9. Install a warm state and populate the V3 cache only after an accepted result commits.
10. Discard stale completion without mutating the world, result, warm state, or cache.

CPU-bound column work remains on bounded platform threads. Virtual threads do not improve CPU saturation and are not part of this plan. Backpressure/rejection is exposed as `BUSY` at the product boundary. Cancellation checkpoints occur between initialization iterations, residual/Jacobian evaluations, line-search trials, and continuation steps.

The service's queue timeout, owner cancellation, and shutdown statuses are authoritative at runtime. The direct science façade may return `CANCELLED`/`DEADLINE_EXCEEDED` in unit tests, but the runtime wrapper rechecks the service token/status and never publishes or caches a nominal successful service completion that contains a cancellation/deadline outcome.

The existing five-second end-to-end deadline remains absolute and includes queue time; initialization/retries never reset it. Preserve one service owner per dimension/block position. Add a proposed V3 family cap of one or two outstanding V3 request contexts before `trySubmit`; an admitted context remains counted until its terminal completion is drained, while a pre-admission/rejected context is removed immediately. Reject the family limit as `V3_CAP_REACHED`, distinct from global `QUEUE_FULL`. Keep the cap until mixed legacy/Next/V3 measurements justify more. The likely runtime risk is head-of-line latency on the shared CPU service, not a reason to bypass global capacity with another pool.

Warm starts and exact-result caching require an exact canonical `V3InputDigest` including solver schema, topology, every numeric input, solver/formulation/initializer policy, component-basis fingerprint, assumptions, acceptance profile, property data, and binary-interaction revisions. V3 and Next have separate caches. A warm state is block-local, non-persisted, constructible only from a committed V3 success, compatibility-checked, and always receives a fresh rigorous thermo/Jacobian refresh. A near-input warm policy may be considered later, but it must be re-audited and can never reuse an accepted result directly.

## 11. V3 block, menu, screen, network, and persistence

### 11.1 New product types

Add, without repurposing legacy IDs:

- `ColumnCalculatorV3Block`
- `ColumnCalculatorV3BlockEntity`
- `ColumnCalculatorV3Menu`
- `ColumnCalculatorV3Screen`
- `ColumnV3Network`
- registry ID `column_calculator_v3`

Add corresponding blockstate, model, item model, loot table, recipe if desired, language entries, and textures. Until the release gate passes, the block is available only behind a development config/creative-tab flag.

Register the block family and assets unconditionally so worlds remain loadable, but gate discovery and new solve admission with `columnV3.rollout = DISABLED | EXPERIMENTAL | ENABLED`, defaulting to `DISABLED`. `DISABLED` denies new work and hides normal discovery; `EXPERIMENTAL` permits admission and a clearly marked creative-only item; `ENABLED` permits normal configured discovery/recipe behavior. Read rollout mode at server start—mode changes require restart—so an active job cannot cross an untracked rollout transition. Viewing/exporting saved state and cancelling existing work remain available while disabled. Server admission remains authoritative regardless of client visibility.

### 11.2 Input and state contract

V3 owns its schema; it does not reuse or extend the retired V2 calculator input. The conceptual input is:

```text
schemaVersion, packageId, assayId
crude feed flow and temperature
stageCount, crudeFeedStageNumber
topPressurePa, stagePressureDropPa
condenserOutletTemperatureK, reboilerDutyW, organicRefluxRatio
canonical side draws (stage, MOLAR|MASS, authored SI rate)
canonical water/steam feeds (mode, stage, flow, T, upstream P)
```

Resolve the pressure vector once from top pressure and drop; never transmit/persist both generator inputs and a redundant array. Side draws are canonically sorted and unique by stage. A mass-rate draw remains authoritative while its molar equivalent is recomputed from the current stage molecular weight; final acceptance checks the authored mass residual.

Carry the current comparison defaults—100 mol/s crude at 638.15 K, 30 trays, feed tray 24, 250 kPa top, 750 Pa/tray drop, 332.15 K condenser, 8 MW reboiler duty, 4.17 organic reflux, and the current three molar draws—as an explicitly versioned fixture, not an assumed feasible solution. Hard schema limits initially remain 2–64 trays, six unique side draws, eight utility feeds, 24 reported streams, 32 bounded diagnostics, and 64 KiB per decoded wire view. NBT has a separately measured encoded-size budget plus the same collection cardinalities. Any limit or default change bumps the relevant schema/assumptions revision.

The authoritative live block state uses monotonically increasing input and state revisions and one of `IDLE`, `CALCULATING`, `CANCELLING`, `SUCCESS`, `FAILED`, `STALE`, `DIRTY`, or `INCOMPATIBLE`. A valid begin freezes accepted input and retains any old result as stale. Accepted cancel enters `CANCELLING`; drained cancel/unload ends at `STALE` when an older result exists and `DIRTY` otherwise; deadline or solver failure enters `FAILED` while retaining old display data; a matching success atomically replaces it. Unload clears the active operation. A stale completion changes neither status nor `stateRevision`.

### 11.3 Network contract

Use versioned immutable messages:

- C2S `calculate_column_v3`: block position, screen nonce, wire-schema version, expected input revision, and bounded versioned `V3ColumnInput`;
- C2S `cancel_column_v3`: position, screen nonce, exact operation ID, and input revision;
- C2S `request_column_v3_state`: position and screen nonce;
- S2C `column_v3_state`: authoritative state revision, accepted input, status, compact result/audit/provenance, or targeted failure;
- S2C `column_v3_action_rejected`: explicitly non-authoritative nonce-correlated reason for an invalid/stale action;
- optional request/response payloads for bounded profile pages keyed by result revision and digest.

The server revalidates position, loaded chunk, menu ownership/distance, expected revision, numeric ranges, component/model IDs, payload sizes, and rate limits. Client values never choose a class name or bypass the model registry. `wireSchemaVersion` versions framing/codecs; `V3ColumnInput.schemaVersion` versions the scientific input and can change independently. Codecs put the wire version first, serialize stable enum names rather than ordinals, reject lengths before allocation, use presence flags rather than NaNs, cross-check every vector against the server component axis, and enforce a 64 KiB wire/decoded-view bound before allocation.

Do not broadcast full stage profiles on every state transition. Fetch them in bounded pages—eight stages is the starting limit—and discard any page whose revision/digest no longer matches. Compact persisted stale results do not promise profiles; the Profiles tab says recalculation is required until a current in-memory accepted full result exists. Bump the mod protocol version when V2 payloads are retired so incompatible clients cannot silently exchange a mismatched payload set.

### 11.4 UX contract

The screen separates:

- Setup: feed, stage/pressure profile, condenser temperature, reflux, reboiler duty, draws, and utilities;
- Streams: specified/calculated external and internal streams with per-value provenance;
- Profiles: paged stage temperature, pressure, flow, phase, and composition data;
- Convergence: residual families, iterations, condition/globalization, and initializer/recovery path;
- Provenance: solver/formulation/initializer, dataset, component basis, assumptions, tolerance profile, and digests.

The Run action is disabled while local validation fails. Running requests can be cancelled. Each draft records the accepted input revision on which it was based so a second viewer cannot silently overwrite newer input. Clients ignore older state revisions and screen-nonce responses. Old accepted results are visibly marked stale as soon as inputs change and are never presented as the current answer. Failure states show the useful cause—such as DOF mismatch, infeasible specification, thermo domain, singular, deadline, or acceptance failure—without dumping a stack trace.

### 11.5 Persistence

Persist a versioned `V3DataVersion`, accepted input, input/result/state revisions, and a bounded last accepted display result with digest, audit certificate, and model provenance. Never persist live status, operation IDs, futures, queue state, client/viewer data, timing, warm states, caches, or mutable numerical workspaces. On load, derive `IDLE` when no accepted input exists, `STALE` when compact old result data exists, and `DIRTY` otherwise; an in-flight operation is never inferred or resurrected. Initially treat even a validated persisted result as presentation-only stale data until a separately reviewed restoration gate proves its complete certificate compatible with current revisions.

NBT has separately measured encoded-size and collection limits rather than relying on the wire buffer's 64 KiB check. Decode into local candidates, enforce all bounds/finiteness, and publish atomically only after whole-object validation. Unknown future schema becomes `INCOMPATIBLE` and preserves a bounded opaque original tag unchanged for explicit export/reset/downgrade; corrupt or oversized data becomes `DIRTY` with `CORRUPT_PERSISTED_STATE`, never a second undocumented `INVALID` status and never partial normalization. The legacy `column_calculator` NBT remains owned by its existing block entity. V2 `column_calculator_next` content is retired without a mapping or migration, so worlds with it must be backed up before upgrade. Any future conversion is an explicit input-only import into a new V3 draft or a separately tested data migration; it never imports a result/warm state or silently reinterprets a placed block.

### 11.6 Provenance and observability

Digest-covered scientific provenance contains input/schema digest, solver and formulation revisions, initializer/recovery-policy revision, thermo package and dataset revision, ordered component-basis fingerprint, assumptions revision, acceptance-profile revision, and result digest. Execution metadata—operation/server epoch, queue/worker/wall time, iterations, property calls, retries, and delivery origin—stays outside the scientific digest. `EXACT_CACHE` is a delivery origin and never replaces the original cold/warm/recovery provenance of the numbers.

Structured terminal logs include V3 family, opaque target/request, revisions/digests, admission/capacity, timing, initialization/recovery path, evaluation counts, maximum of every residual family, and typed termination. Do not log full component arrays by default. Server shutdown reports bounded counters for admitted, rejected, cache hit, accepted, scientific failure, deadline, cancellation, and stale completion.

## 12. Verification strategy

### 12.1 Test layers

1. **Value/API tests** — immutability, defensive copies, canonical digest, validation, specification equality, schema compatibility.
2. **DOF tests** — known valid/invalid topology/specification combinations, structural matching, representative numerical rank.
3. **Thermo tests** — pure-component roots, mixture fugacity, enthalpy consistency, bubble/dew points, two- and single-phase flashes, component permutation.
4. **Linear-algebra tests** — scalar and block tridiagonal systems, randomized diagonally dominant systems, near singularity, backward error, dense-oracle comparison, no input mutation.
5. **Residual/Jacobian tests** — hand-calculated stage balances, conservation, block sparsity, full finite-difference comparison, scale invariance.
6. **Initializer tests** — finite admissible seeds, volatility ordering, cold single-phase feeds, repeatability, no public success path.
7. **Solver tests** — analytic/toy columns, cold/warm equivalence, typed failure paths, cancellation/deadline, no false success.
8. **External-oracle tests** — pinned DWSIM cases with identical thermodynamic data and specifications.
9. **Runtime tests** — bounded admission, rejection, cancellation races, stale completion, chunk unload, server shutdown, cache versioning, no off-thread world access.
10. **Game tests/manual QA** — placement, menu lifecycle, network validation, persistence/reload, stale-result UX, multiplayer contention.

The current test-only dense cold-model check is useful for its limited material matrix, but it is not a full-column feasibility oracle. V3 needs a full MESH end-to-end oracle and independent final residual reconstruction.

Acceptance-forgery tests start with one complete valid snapshot, then corrupt exactly one required family at a time. They also reject duplicate/missing audit families, require explicit topology-dependent `NOT_APPLICABLE` entries such as water checks on a dry problem, and prove the audit identity embedded in result/provenance/diagnostics is the same immutable report.

### 12.2 Reference cases

Build a versioned corpus under `src/test/resources/column/v3/`:

- a manufactured positive two-component full-MESH state with explicit constant `K`, affine liquid/vapor enthalpies, pressure, boundary terms, feeds, draws, and duties derived independently from that state;
- a two-component ideal/near-ideal VLE case;
- a Peng–Robinson light-hydrocarbon case;
- a tagged 4-tray partial-condenser calibration case proving DWSIM/CreateChemE condenser, tray, feed, side-draw, and reboiler numbering;
- pinned DWSIM partial-condenser dry cases at several stage/feed locations;
- `cdu16-lambda0-30`, the dry zero-pressure-drop/zero-side-draw feasibility case;
- `cdu16-default-30`, the exact current 100 mol/s dry default, followed by a 64-stage dry case;
- the 16-hydrocarbon Tia Juana basis with exactly matched component constants and zero/nonzero BIP variants;
- the same public 17-row basis with water disabled, then independently verified water/steam cases;
- all-liquid, two-phase, and all-vapor feed variants;
- trace-component and difficult relative-volatility cases;
- deliberately under/over-specified, infeasible, near-singular, and deadline cases.

Every external fixture records DWSIM commit/version, .NET/container/runtime provenance, property/flash/column solver settings, parallel processing disabled, component properties, BIPs, enthalpy convention, unit conversions, topology, specifications, initialization policy, attempts, and full-precision exported results. Store bounded data/output fixtures, not copied GPL implementation code or a DWSIM runtime dependency.

Before calling DWSIM an exact numerical oracle, pass a thermo-identity grid for compressibility, `ln(phi_i)`, enthalpy, TP-flash phase fraction/compositions, phase/root classification, and reference state using identical components, PR variant, and BIPs. If that gate fails, label DWSIM `MODEL_MISMATCH` and use it only as a reported engineering/strategy comparison; do not tune V3 toward mismatched numbers and do not let it pass or fail scientific release. External fixture states are `REFERENCE_ACCEPTED`, `REFERENCE_NONCONVERGED`, `REFERENCE_AUDIT_FAILED`, `MODEL_MISMATCH`, or `CONFIGURATION_FAILURE`. DWSIM nonconvergence alone does not prove physical infeasibility.

DWSIM normalization and full MESH reconstruction are an independent test/tool path and must not call `V3MeshResidualEvaluator` or `V3AcceptanceAudit`. `REFERENCE_ACCEPTED` requires DWSIM's convergence and balance checks, a passing independent reconstruction, and agreement on the same physical branch from at least two fresh cold/perturbed starts.

Create a second oracle in test-only code for 2–4 components and 4–8 stages. It must independently implement the complete MESH residual, log-flow coordinates, finite-difference Jacobian, dense pivoted solve/globalization, and final residual checks. It must not call V3 topology assembly, production initialization, block algebra, production audit, or any V2 solver. Manufactured and ideal fixtures use an independently implemented test thermo model. A PR fixture either uses independent PR equations/property parsing or is explicitly labelled an algebra-only oracle that shares thermodynamics and therefore cannot validate thermo correctness. A dense golden requires maximum scaled MESH residual `<=1e-11` and every physical residual at least ten times inside the production limit.

### 12.3 Comparison metrics

For an accepted external-oracle case compare:

- stage temperatures and pressure profile;
- liquid/vapor component flows and compositions;
- distillate and bottoms component flows;
- condenser and reboiler duties;
- local/global material and energy residuals;
- equilibrium residuals and specification closure.

Keep physical acceptance and oracle agreement separate. Cross-simulator agreement is evidence, not the success flag; V3 must independently satisfy its own conservation/equilibrium audit. Any intentional discrepancy needs a fixture note identifying property-data or formulation differences.

Suggested comparison ceilings, frozen from repeatability/conditioning before inspecting V3 deltas, are:

- small exact-model dense oracle, when its scaled Jacobian condition estimate is `<=1e8`: maximum absolute temperature difference `<=1e-6 K` and each flow `abs(fV3-fRef) <= 1e-9*Fstar + 1e-8*max(abs(fV3),abs(fRef))`; above that condition ceiling compare residuals and selected observables rather than coordinates;
- DWSIM thermo identity: exact phase/root classification, maximum absolute compressibility difference `<=1e-9`, `ln(phi)` difference `<=1e-8`, enthalpy difference `<=1e-3 J/mol + 1e-8*max(abs(hV3),abs(hRef))`, and flash vapor-fraction/composition difference `<=1e-8`;
- DWSIM full-state engineering comparison: exact generated pressure-input equality, maximum absolute temperature difference `<=0.1 K`, fraction/recovery difference `<=1e-4`, each component/stage flow `abs(fV3-fRef) <= 1e-9*Fstar + 1e-3*max(abs(fV3),abs(fRef))`, and duty `abs(QV3-QRef) <= 1 W + 1e-3*max(abs(QV3),abs(QRef))`.

Trace fractions are compared by absolute difference; no relative division by a trace reference is permitted. If exact thermo parity succeeds, tighten the full-state limits. If it fails, the broader DWSIM comparison is reported only and the independent dense/original-equation audits remain the numerical authorities.

### 12.4 Metamorphic and randomized tests

- permuting component order and reversing the permutation leaves the physical answer unchanged;
- shuffling side-draw input order leaves the canonical problem and result unchanged;
- scaling every extensive input together—crude/utility feeds, molar or mass side draws, and reboiler duty—scales output flows/duties while preserving temperature and composition within tolerance;
- zero pressure drop equals an explicitly uniform pressure profile;
- molar and converged-stage-MW-equivalent mass side draws agree;
- enabling the water subsystem with zero water/steam reproduces the dry solution;
- converting UI units round-trip does not change the canonical SI problem;
- cold, accepted-warm, damped, and continuation paths reach the same accepted scientific digest;
- repeated runs are deterministic within declared floating-point tolerance;
- invalid, zero, trace, and extreme inputs never produce NaN success;
- concurrent identical requests do not share mutable solver state;
- cancellation and deadline outcomes do not later overwrite an accepted newer result.

Run 100 sequential cold repeats in one JVM and require the same canonical scientific digest; same-JVM floating-point determinism should be bitwise unless explicitly documented otherwise. Run the stateless solver concurrently on 2–8 test workers with identical and different inputs and compare every result to its sequential baseline. Concurrency tests use injected clocks plus latches/barriers/phasers, never sleeps, and cover cancellation before solve, during initialization, property evaluation, Jacobian assembly, line search, continuation, and final audit.

### 12.5 CI and evidence artifacts

Create separate Gradle test tags/tasks for fast unit tests, solver reference tests, checked-in DWSIM fixtures, randomized stress, benchmarks, and long soak. PR gates are milestone-aware: M0–M5 require schema/DOF/thermo/algebra/oracle checks; M6 and later require at least one real production binary `Success`; M8 and later require a real shipped dry-CDU `Success`. Fixed-seed metamorphic and deterministic concurrency tests become mandatory as soon as their feature exists. Nightly runs the expanded seed corpus, 30/64-stage cases, mixed-service stress, and allocation reports. Manual/release jobs rebuild the pinned DWSIM environment, review—not automatically update—goldens, run GameTests/JFR, and soak. On failure, retain a compact reproducible input fixture and diagnostic JSON rather than only console text.

Benchmarks retain the current comparable scopes: direct numerical core including mandatory final audit/result construction, scientific façade, and real bounded-service queue-to-server-thread-stamped-commit. If a harness omits the commit guard it is named queue-to-completion instead. Cold means cache/warm bypassed; V3.0 warm means the same input with exact cache bypassed and a seed from a real committed success; exact repeat is measured separately. Every timed numerical sample must be accepted or the distribution is invalid. Retain the existing targets: dry intermediate `<=400 ms` median and `<=64 MiB`; release dry core `<=200 ms` median and `<=32 MiB`. The 2,012 ms legacy ratio is hardware-normalized planning evidence because the scientific workloads differ, not a like-for-like algorithm-speedup claim. The nearby changed-input warm target of `<=75 ms p95` is deferred until a versioned near-input compatibility policy exists and passes equivalence tests. Ratify wet/64-stage budgets only from accepted data.

Nightly qualification includes at least 1,000 fixed-seed cases partitioned before execution: valid in-envelope cases must return accepted success; invalid/boundary-negative cases must return their exact typed failure; robustness-only outside-envelope cases may return a declared accepted result or bounded typed failure but never NaN/untyped failure. Release qualification includes an eight-hour integrated-server mixed legacy/Next/V3 workload with cache churn, cancellation, unload/reload, saturation, and shutdown; it requires no late commit, worker leak, unexplained scientific failure, or monotonic retained-heap growth.

## 13. Milestones, deliverables, and exit gates

| Milestone | Implementation deliverable | Required exit evidence |
| --- | --- | --- |
| M0 — contract | ADR for partial-condenser topology, unknowns/equations, fixed control set, units, tolerances, license boundary | Positive-reflux and `R=0` DOF/rank tests pass; one hand-audited equation map; maintainers approve contract |
| M1 — immutable core | V3 input/problem/specification/outcome/diagnostic types and canonical digest | API/immutability/validation tests; no Minecraft dependencies in science package |
| M2 — independent evidence | small dense full-MESH oracle, DWSIM runner/manifest, 4-tray mapping fixture | At least one real feasible column is independently established; DWSIM artifacts have explicit acceptance/model-mismatch states |
| M3 — dry thermo | `V3ThermoModel`, PR adapter/implementation, feed flash, thermo workspace | Reference fugacity/enthalpy/flash tests including all three feed phase states and derivative grids |
| M4 — algebra/equations | block structures/solver, topology map, full dry MESH residual, block Jacobian | Random/dense-oracle, DOF, sparsity, conservation, backward-error, and finite-difference tests pass |
| M5 — initializer | endpoint flashes, balance-closed traffic, modified Wang–Henke refinement | Every nominal fixture yields a finite admissible rigorous seed; no fixed split/scaler; failure remains typed |
| M6 — binary Newton | damped stage-banded Newton, solve controls, and independent audit | First real production binary `Success` from cold and warm; all residual families pass; banded/block/dense corrections agree |
| M7 — dry feasibility/DWSIM | pinned dry fixtures, thermo-identity gate, independent full-size feasibility evidence | Shipped default is independently feasible; if the old default is proved infeasible, a versioned replacement is independently established before proceeding; advisory mismatches are report-only |
| M8 — full dry CDU | 16-HC PR/pseudocomponents, pressure drop, reflux branches, side draws, 30/64 stages | V3 returns a real cold/warm `Success` for the shipped dry default; oracle policy passes where exact; no zero-component hard coding |
| M9 — water/steam | V3 IF97 boundary, immiscible-water complementarity, utilities | Zero-water invariance plus water material/energy/complementarity and throttle fixtures pass |
| M10 — runtime/product | V3 shared-runtime protocol, block entity, codecs, screen, persistence | Executor count unchanged; concurrency, stale-result, restart, packet, GameTest, and multiplayer QA pass |
| M11 — hardening | randomized corpus, benchmark, soak, allocation/profile work, docs | No false success/untyped exception/leak; scientific and latency gates pass; release checklist signed |
| M12 — rollout | promote from disabled to experimental/enabled; legacy disposition documented | Migration/compatibility decision, telemetry, and rollback plan approved |

Milestones are sequential by scientific dependency even when code tasks within a milestone run in parallel. UI work may prototype early against fake immutable outcomes, but it cannot define or weaken solver acceptance.

## 14. Branch and commit strategy

Use small reviewable commits on `codex/v3-dwsim-calculator-test`:

1. `docs: plan DWSIM-inspired V3 column calculator`
2. `feat: add V3 immutable problem and DOF ledger`
3. `test: add independent V3 MESH and DWSIM reference fixtures`
4. `feat: add V3 hydrocarbon thermo boundary and feed flash`
5. `feat: add pivoted banded algebra, certified block fast path, and MESH assembly`
6. `feat: add DWSIM-inspired V3 initializer`
7. `feat: add V3 simultaneous dry solver and audit`
8. `test: establish dry CDU feasibility and DWSIM identity`
9. `feat: qualify the V3 16-HC CDU model`
10. `feat: add V3 immiscible water and steam model`
11. `feat: add V3 calculator state and persistence shell`
12. `feat: integrate V3 operation envelope with bounded runtime`
13. `feat: add V3 wire codecs and server handlers`
14. `feat: add V3 calculator screen and assets`
15. `test: harden V3 oracle, concurrency, performance, and lifecycle paths`

Each scientific commit includes its tests. V2 retirement is isolated in its own backup-backed checkpoint, rather than being mixed into numerical solver changes.

## 15. Risk register

| Risk | Consequence | Mitigation and trigger |
| --- | --- | --- |
| Incorrect specification count or dependent equations | Singular Newton system or plausible false answer | M0 equation map, structural matching, numerical rank tests; stop implementation on ambiguity |
| GPL contamination | License incompatibility | Clean-room Java design, cite algorithms/source behavior, copy no DWSIM code; review provenance before merge |
| Property-data mismatch with DWSIM | Misleading oracle failures | Pin all constants/BIPs/models/units in fixtures; classify differences before tuning solver |
| Poor thermo derivatives | Slow or divergent Newton | Local FD verification, scaling, continuation; add analytic derivatives only with oracle tests |
| Hydrocarbon phase appearance/disappearance | Invalid fugacity equations or singular log coordinates | Explicit feed states, fixed tray phase contract, selective TPD, typed `PHASE_REGIME_MISMATCH`; design a later hydrocarbon active-set formulation separately |
| Water complementarity couples sharply to energy | Mask chatter or singular generalized Jacobian | Add only after dry success; zero-water invariance, manufactured wet/dry nodes, hysteresis for iteration only, strict final Fischer–Burmeister audit |
| Positive reflux without condensate | Algebra may converge to an unphysical top boundary | Partial-condenser flash/seed check plus final minimum-condensate specification audit |
| Near-critical/azeotropic behavior | Ill-conditioning | condition evidence, damping/regularization, targeted fixtures; never relax final residuals |
| Initializer mistaken for solver | Conserved-flow corruption or false success | API prevents initializer success; immutable independent audit owns success creation |
| Hard-coded component basis | Binary tests fail and future models break | derive dimensions from immutable active basis; run 2-, small-, 16-HC, and 16-HC-plus-water public-axis suites |
| Worker leaks or world access | Server races, crashes, shutdown hangs | reuse bounded owner-managed service; immutable snapshots; server-thread commit and race tests |
| Stale/persisted result confusion | UI displays result for different input/model | canonical digest + schema/model provenance; stale marking; load validation |
| Performance tuning changes answers | Silent scientific regression | pin acceptance corpus first; benchmark after the dry CDU gate; compare every optimization against fixtures |
| DWSIM target moves | Irreproducible comparison | pinned commit and checked-in fixtures; upgrade only through a reviewed fixture change |

## 16. Release gates and definition of done

V3 is eligible for normal gameplay only when all of the following are true:

- the partial-condenser topology and fixed control set have a reviewed DOF/structural-rank proof for positive-reflux and `R=0` branches;
- every `Success` passes an independent `V3AcceptanceAudit` report plus the separate immutable convergence-evidence gates and satisfies every frozen threshold;
- the nominal analytic, binary, PR, dry 16-HC, and enabled-water corpora pass from cold starts; thermo-identity-qualified DWSIM numeric gates pass, while advisory model-mismatch reports have no release pass/fail authority;
- warm starts produce equivalent accepted results and never bypass the audit;
- a randomized campaign of at least 1,000 in-envelope and boundary cases produces no false success, NaN success, uncaught numerical exception, or cross-request state leak;
- block linear corrections match the dense test oracle on the required randomized matrix suite;
- cancellation, deadline, saturation, stale completion, unload, shutdown, and reload tests pass;
- an invalid or unsupported problem produces a stable typed failure and does not mutate the world result;
- persisted/network schemas are versioned and packet inputs are server-validated;
- DWSIM fixture provenance and the clean-room/GPL boundary have been reviewed;
- performance is measured on declared hardware, with a configured solve deadline and bounded worker count; no server-thread commit exceeds the agreed tick budget;
- documentation explains supported topology/specifications, units, limitations, diagnostics, and the legacy/V2 relationship.

The no-go conditions are equally important: a green test suite that contains only fail-closed outcomes, a visually plausible profile without full MESH closure, or agreement in product totals while local balances fail does not satisfy V3 readiness.

## 17. Decisions required at M0

Before numerical implementation, record answers to these questions in an ADR:

1. Confirm that V3.0 preserves the explicit partial condenser plus equilibrium partial reboiler boundary and its exact node-number convention.
2. Confirm the equation/unknown map for specified condenser temperature, organic reflux ratio, and reboiler duty in positive-reflux and `R=0` branches.
3. Which component/property dataset, PR variant, enthalpy convention, and BIP revision define the first DWSIM parity case?
4. Is V3.0 pressure input exactly top pressure plus constant drop per stage, with explicit profiles deferred?
5. What are the hard temperature/pressure/flow domains for PR, IF97, and each feed/utility type?
6. Are exact-zero components eliminated through a declared active basis while every positive trace component uses a scaled log coordinate with no additive balance floor?
7. Which exact DWSIM comparison tolerances are frozen after property identity, repeatability, and conditioning evidence?
8. Confirm the five-second absolute deadline, proposed V3 admission cap, worker concurrency, cache size, 64 KiB wire-view bound, and separate measured NBT budget.
9. Confirm V3 remains the separate advanced calculator after the complete retirement of V2 `column_calculator_next`.

No question above justifies an ambiguous implementation default. M0 exists to turn each answer into executable validation and a versioned contract.

## 18. Existing work to reuse carefully

The current repository already has valuable boundaries and failure-oriented work:

- V3-owned immutable property data and Peng–Robinson arithmetic in [`science/column/v3/thermo`](../src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/);
- V3 acceptance and convergence diagnostics;
- typed solve outcomes and diagnostics;
- the bounded runtime and server-authoritative completion flow;
- exact-input digest/cache concepts;
- archived V2 optimization findings in [`DISTILLATION_COLUMN_OPTIMIZATION_HANDOFF.md`](DISTILLATION_COLUMN_OPTIMIZATION_HANDOFF.md).

Reuse means preserving proven contracts or porting small ideas with new V3 tests. It does not mean subclassing the current solver, sharing mutable workspaces, importing current assumptions, or accepting the current default case as scientifically solved. Known issues—including tautological equation/unknown counts, a hard-coded hydrocarbon basis, two-phase-only feed assumptions, and phase-repair mutation of conserved flows—must become regression tests before any related V3 code is written.

## 19. First implementation slice after plan approval

The smallest useful coding slice is M0 plus M1, not the full Newton solver:

1. add the ADR/equation map for a 2-component, 4-tray partial-condenser baseline;
2. implement immutable component basis, topology, specification, problem, and DOF ledger types;
3. add exhaustive valid/invalid specification tests and a structural-rank test;
4. define sealed outcome/failure/diagnostic contracts;
5. add one versioned JSON fixture schema for future DWSIM exports;
6. compile and run the entire repository test suite without registering a V3 block yet.

That slice creates a reviewable scientific contract and prevents UI, thermodynamics, or iteration code from hardening around an under- or over-specified model.

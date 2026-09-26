# V3 formulation redesign notes

This note is a read-only design review of `6c7d446` on `codex/v3-convergence-fixes`. It distinguishes an observed code fact from a hypothesis about the cold-crude failures. It does not treat a failed cold solve as proof that the requested separation is physically infeasible.

## What exists now

The current core is an equation-oriented hydrocarbon MESH model. For each retained active hydrocarbon component it uses liquid and vapor component flow variables, encoded as independently scaled logarithms; it uses a temperature variable at every node except the specified-temperature condenser. See `V3DryMeshCoordinateMap.java:32-84` and `V3DegreeOfFreedomLedger.java:191-208`.

At every retained point the residual has a component material row. It adds a VLE row when both phase variables exist, and adds an energy row wherever temperature is unknown. The ledger confirms equal equation and unknown counts by a sparsity matching, not by a numerical Jacobian-rank calculation (`V3DegreeOfFreedomLedger.java:86-103`, `212-330`). The physical residual is:

\[
R^M_{j,i}=L^{in}_{j,i}+V^{in}_{j,i}+F_{j,i}-L_{j,i}-V_{j,i},
\]

\[
R^K_{j,i}=\ln y_{j,i}+\ln\phi^V_{j,i}-\ln x_{j,i}-\ln\phi^L_{j,i}+\Delta_w,
\]

with a phase enthalpy balance for energy. These equations appear in `V3MeshResidualEvaluator.java:90-146`. Material, VLE, and energy rows are scaled respectively by a component feed scale, one, and `max(1, F*100000)` (`217-223`).

The current condenser is a discrete topology branch: vapor-only, liquid-only, or two-phase. Every active hydrocarbon component is treated as condensable (`V3CondenserComponentPhases.java:10-24`); a transition is attempted only after a converged state fails the separate condenser audit (`V3ColumnCalculator.java:617-658`). A transition projects the tray-one vapor through a TP flash and rebuilds the whole topology (`V3CondenserPhaseTransition.java:18-125`).

Water is outside the MESH component vector. Steam creates a known upward water-vapor profile, its vapor enthalpy is added to the energy rows, and wet hydrocarbon VLE receives an algebraic dilution term. The water condenser split is algebraic and the tray dew-point condition is audited after solving (`V3MeshResidualEvaluator.java:110-116`, `138-145`, `206-214`; `V3ColumnProblem.java:137-154`; `V3AcceptanceAuditor.java:103-124`).

Each side draw is a specified total liquid rate. The residual removes every component in the current liquid composition, through `D_j/L_j`; the same fraction multiplies the incoming liquid energy (`V3MeshResidualEvaluator.java:97-107`, `121-131`). The model deliberately permits an invalid withdrawal fraction during Newton and only rejects non-positive remaining downflow in the acceptance audit (`V3ColumnProblem.java:196-200`, `V3AcceptanceAuditor.java:146-161`).

Trace reduction is not an in-solve phase-appearance method. It freezes a component/stage support from a seed, forces the region between the product-path extrema to be retained, then checks directed feed reachability (`V3TruncationSupport.java:96-130`, `309-355`). A reduced-call failure can retry the untruncated formulation. This is an approximation/control path, not a physical species disappearance model.

## Confirmed limitations and risks

### Confirmed: individual hydrocarbon phase amounts cannot vanish on a retained support

`decode` turns every retained flow coordinate into `scale * exp(z)`, and the evaluator rejects a non-positive active phase flow before evaluating fugacity (`V3DryMeshCoordinateMap.java:79-84`; `V3MeshResidualEvaluator.java:177-195`). Thus a retained component always exists in every phase permitted by the topology. This is mathematically incompatible with exact phase-specific component disappearance. It is a deliberate domain restriction, not evidence by itself that it caused a given crude failure.

### Confirmed: topology changes are external restarts, not equations

The model has no phase-fraction or phase-stability complementarity variable. It solves one fixed condenser topology, audits it, then projects/restarts a different topology. Interior trays never gain or lose a hydrocarbon phase. Near a phase boundary, a finite-difference derivative can therefore be taken on a branch that is only locally meaningful. The code does protect itself by rejecting infeasible PR/coordinate perturbations, but it has no smooth derivative contract across a branch change.

### Confirmed: the wet model is a restricted pseudo-component model

The hydrocarbon PR calls use hydrocarbon compositions. Water appears through a fixed profile and a logarithmic dilution correction, rather than a water-containing EOS mixture or a water component material equation. This can be a legitimate *declared* steam-stripping approximation only if water is assumed insoluble in liquid hydrocarbon, ideal in the vapor dilution term, and unable to condense on trays. Those assumptions are not a general hydrocarbon/water equilibrium model. They are particularly weak near the observed condenser and wet-ramp branch changes.

### Confirmed: a fixed draw creates a hidden inequality in the Newton domain

The authored constraint is `D_j < L_j`. The core equation contains `1-D_j/L_j`, while the feasibility condition is held until the audit. A Newton trial can therefore reduce the scaled residual while passing through negative downflow. The current code intentionally allows this, so it is a formulation choice rather than an accidental omission.

### Risk, not confirmed bug: row scaling and independent log-flows can distort the merit geometry

The per-component material scale ranges down to `F*1e-12`, while energy is scaled from the total feed. Independent log component flows also separate total-flow and composition changes that are strongly coupled by an EOS. This can make trace-component directions numerically influential or stiff even when their material contribution is small. The recorded failures do not isolate this as their cause, so changing scales or convergence gates without a manufactured conditioning test would be speculative.

### Risk, not confirmed bug: structural rank is not numerical identifiability

The ledger proves only a matching in the declared sparsity graph. It does not prove that the PR-based Jacobian is well conditioned, nonsingular at a phase boundary, or compatible with a finite-difference stencil. That distinction matters for the existing `MAX_ITERATIONS` paths, but it does not invalidate the bookkeeping proof.

## Recommended replacement: flash-based, complementarity-capable stage model

The cleanest rewrite retains the user-visible input contract but replaces independent component log-flow MESH unknowns with equilibrium-stage flash variables and explicit phase status.

### Scope first

Choose one of these before coding:

1. **Hydrocarbon-only equilibrium with declared steam stripping.** Water has a prescribed vapor profile and no liquid solubility or tray equilibrium. This is computationally smaller, but every result must be labelled as a pseudo-component steam model. It cannot claim general wet VLE accuracy.
2. **Multiphase hydrocarbon/water equilibrium.** Add water to phase material and energy equations and use an EOS/activity model with validated water-hydrocarbon interaction parameters and an aqueous/free-water phase model. PR78 alone, without validated water interaction data and phase-stability treatment, is not enough to justify this option.

The current code implicitly selects option 1 while presenting several water checks that can be mistaken for option 2. A redesign should make the selected scope explicit in the formulation revision and result metadata.

### Hydrocarbon equations

The current pressure field is **prescribed**, not a hydraulic calculation: `P[0]=P_top`, `P[j]=P_top+(j-1)ΔP` for trays, and the reboiler receives the last-tray pressure (`V3ColumnProblemResolver.java:127-134`). A redesign must retain this as a declared fixed-pressure profile unless it also introduces pressure-drop/hydraulic equations. Let `c` denote active hydrocarbon components.

The primary prototype should use **scaled, bound-capable phase amounts**, not unbounded log totals and all-positive softmax compositions. Let `m^L_{j,i}` and `m^V_{j,i}` be nonnegative component rates, scaled by documented component and total-flow scales. An active set records which component-phase amounts are strictly positive. Within a fixed active set, compositions can be normalized from the positive amounts. A projected/trust-region step must preserve

\[
m^\alpha_{j,i}\ge 0,\qquad
\sum_i m^\alpha_{j,i}>0\ \text{for every active phase}.
\]

For a stage flash block, an equivalent reduced form can eliminate one phase's component amounts against the incoming component total `M_{j,i}` and enforce `0\le m^L_{j,i}\le M_{j,i}`, with `m^V_{j,i}=M_{j,i}-m^L_{j,i}`. Because `M` is coupled to neighbouring countercurrent flows, that elimination belongs inside a stage/block solve with bound-aware derivatives; it is not a global algebraic shortcut.

**Positive-branch option only.** If a fixed two-phase, fixed-component support is intentionally selected, the convenient local parameterization is

\[
L_j=\exp(\ell_j),\quad V_j=\exp(v_j),\quad
x_{j}=\operatorname{softmax}(\xi_j),\quad y_j=\operatorname{softmax}(\eta_j).
\]

It has `2c+1` variables: two totals, `2(c-1)` logits, and `T_j`. It is useful *within that declared positive branch*, but it still forbids exact local component zeros and imposes relative trace geometry through the logits. It must not be presented as the fundamental trace/phase-appearance fix.

For every two-phase equilibrium node solve:

\[
R^M_{j,i}=M^{in}_{j,i}-L_jx_{j,i}-V_jy_{j,i}=0,\qquad i=1\ldots c,
\]

\[
R^E_j=H^{in}_j-L_jh^L(T_j,P_j,x_j)-V_jh^V(T_j,P_j,y_j)+Q_j=0,
\]

\[
R^K_{j,i}=\ln(x_{j,i}\phi^L_{j,i})-\ln(y_{j,i}\phi^V_{j,i})=0,
\qquad i=1\ldots c.
\]

For a fixed all-positive two-phase branch, the softmax coordinates eliminate the two composition-sum equations, but all `c` component fugacity equalities remain required. Equation count is therefore `c + 1 + c = 2c+1`, exactly matching the positive-branch variables. For the primary bound-capable formulation, count only active component-phase variables and include complementarity/stability conditions for inactive phases; do not fake a zero with a trace floor.

For phase appearance/disappearance, do **not** force both phases positive with a trace floor. Use an active-set flash at each node:

- two-phase candidate: equations above plus a phase-stability check;
- liquid-only candidate: `V_j=0`, liquid material/energy equations, and vapor tangent-plane-distance/non-negativity condition;
- vapor-only candidate: symmetric form.

The active set can be selected by a deterministic TP/PH flash and held fixed for one Newton trust-region iteration. A switch requires a fresh residual/Jacobian on the new active set. This is preferable to pretending the phase is present at `exp(z)` underflow scale. A fully semismooth alternative uses complementarity functions such as Fischer--Burmeister for phase amount and stability driving force, but that is substantially harder to validate with a cubic EOS.

### Side draws and boundaries

For a liquid draw of specified total rate `D_j`, parameterize the **remaining downflow** directly:

\[
B_j=\exp(b_j)>0,\quad L_j=B_j+D_j,
\quad D_{j,i}=D_jx_{j,i},\quad L^{down}_{j,i}=B_jx_{j,i}.
\]

This eliminates the current `D/L` singularity and guarantees physical positive downflow without accepting an invalid Newton state. It retains the existing well-mixed saturated-liquid-draw assumption; changing that assumption needs a hydraulic or nonequilibrium tray model, not a numerical patch.

At the condenser, solve a separate fixed-`T`, fixed-`P` outlet flash/material system. The organic reflux ratio is then an algebraic split of the liquid hydrocarbon outlet: reflux `R/(1+R)` and liquid product `1/(1+R)`. A liquid-only condenser has only liquid outlet component rates; a two-phase condenser has liquid and vapor rates plus `c` fugacity relations. Do not use a topology restart as the only phase-selection mechanism. The condenser duty remains calculated when temperature is the specification.

At the reboiler, use the same stage flash formulation with specified duty in `R^E`. The current equal tray/reboiler pressure is an explicit fixed-profile assumption. A reboiler pressure drop requires new equations and must not be slipped in as a numerical change.

### Global closure and degrees of freedom

With fixed pressure profile, feed condition, condenser temperature, reflux ratio, side-draw rates, and reboiler duty, each interior/reboiler stage has component material balances plus one energy balance and equilibrium/active-set equations. The condenser has component material balances and its phase equations at specified temperature. Count the exact active-set variables and equations after every phase status change, then test **numerical** Jacobian rank/condition in addition to the existing structural matching.

The accepted-result audit should independently recompute:

- component closure over all external hydrocarbon products;
- water closure separately under the selected water scope;
- total energy closure, including feed, duty, draw products, condenser duty, and any water phase enthalpy;
- phase stability/flash consistency for every active phase, and the complementarity/stability inequality for every inactive phase;
- positive remaining downflow for every draw.

The current auditor cannot remain unchanged in a zero-capable formulation. It requires every retained liquid and vapor component flow to be strictly positive (`V3AcceptanceAuditor.java:164-185`). Version the audit with the new formulation: preserve finite-state, independent material/energy closure, active-phase fugacity, condenser, and draw checks; replace universal strict positivity with nonnegative amounts, active-set consistency, and explicit stability/complementarity checks. Stream reporting must likewise retain exact-zero component entries rather than converting them to a trace floor.

Trace reduction, if retained, should only reduce property evaluation or use a validated grouped pseudo-component representation. It must not remove material-balance unknowns solely because an iterate is small. The existing frozen-support plus untruncated fallback is safer than deleting material, but it is not a replacement for phase complementarity.

## Thermodynamic and derivative contract

The replacement requires a narrow, testable property interface for a fixed phase-status evaluation:

\[
(T,P,z,\alpha) \mapsto h^\alpha,\;\ln\phi_i^\alpha,
\;\partial h^\alpha/\partial q,\;\partial\ln\phi_i^\alpha/\partial q,
\]

where `q` contains temperature and independent composition coordinates. Analytic or automatic derivatives are preferred. If finite differences remain, they must be phase-status-aware: use one-sided perturbations near a stability boundary, report the stencil actually used, and reject a Jacobian when a perturbation changes the active phase set. A PR root selected for a requested phase is not by itself a global phase-stability proof.

Use physically scaled residual and step tests. Component balances should be scaled by an authored component-flow scale with a documented absolute floor; energy by an enthalpy-throughput scale; VLE by its dimensionless fugacity tolerance; and temperatures by an absolute-plus-relative temperature scale. A bound-capable step is accepted only when it both reduces a correspondingly scaled merit and preserves amount bounds/active-set consistency. Do not use a single unweighted log-flow maximum as the phase-appearance criterion, and do not relax physical closure or phase-stability checks to accommodate a step.

The existing feed flash is a Wilson-seeded successive-substitution/Rachford--Rice procedure with endpoint classification (`V3FeedFlash.java:24-78`). It is suitable as a candidate generator, but a redesign should not reuse its branch label as proof of stable multiphase behavior at every MESH stage. Water-containing fugacity calculations require separately validated parameters and a phase model; no conclusion about water/hydrocarbon mutual solubility follows from the current code.

## Migration plan

1. Freeze the present V3 as the regression baseline and preserve its external stream schema.
2. Build a small, independently audited **bound-capable** single-stage flash module with active-set tests: liquid-only, vapor-only, two-phase, local component disappearance/reappearance, trace component, fixed draw, and water scope selected explicitly.
3. Build a two-stage countercurrent system with numerical-rank and derivative-consistency tests before introducing 30 stages or continuation.
4. Replace only the dry hydrocarbon path first. Compare closure, phase status, and output streams against the independent single-stage/two-stage tests; do not claim agreement with the current solver is an oracle.
5. Add wet stripping only after choosing and validating its physical scope. Then add a separate water-phase test matrix.
6. Reintroduce continuation as an initialization aid after the formulation succeeds from multiple independent seeds. Continuation must never be the only mechanism that makes the governing equations solvable.

The present benchmark identifies operationally difficult regions and cost, but it cannot select among these physical models. Any redesign claim needs independent flash/stage reference cases in addition to cold-column convergence counts.

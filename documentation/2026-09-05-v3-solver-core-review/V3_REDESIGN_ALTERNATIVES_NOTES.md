# V3 redesign alternatives: evidence and stage gates

Date: 2026-09-05. This is a design note, not an implementation plan or a claim that a new numerical formulation will converge a specified fraction of crude-column inputs.

## What the completed evidence says

The 64-case cold benchmark gives a useful negative result for a wholesale convergence claim. At the configured 60-second public-call deadline, the candidate accepts 27 cases and main accepts 26. The only confirmed difference is `N64-on`: candidate returns an audited reduced-support result in about 34 seconds, while main reaches the deadline. A separate 120-second main probe returns its own audited reduced result in 83.292 seconds. This is a deadline/latency improvement, not proof that the candidate reaches a distinct steady state.

No baseline `API_FAILURE/NONCONVERGENCE` becomes a candidate success. The large failure regions persist: all twelve 99/100/101 kPa pressure-boundary cases fail; all sixteen 40% side-draw-loading cases fail; only two of sixteen 60 kPa cases succeed; and steam-on remains 8/26 on both revisions. The recorded terminal iteration count, final scaled residual, and solve path agree on every matched screen cell with both records except `F02-wet-on`, where main timed out before terminal diagnostics and candidate produced an ordinary nonconvergence. Those fields are terminal diagnostics, not full Newton trajectories.

The evidence supports retaining the focused support-reachability and linear-work fixes for performance. It does not support claiming that they repair the fundamental side-draw/continuation failure structure, or attributing the `N64-on` result solely to sparse linear algebra.

## Current boundary that a redesign must preserve

Keep `V3ColumnCalculator.calculate(input, control, cutoff)` as the public operation. Inputs retain their immutable feed, tray geometry and pressure profile, condenser temperature, organic reflux ratio, reboiler duty, up to three liquid side draws, and up to two stripping-steam feeds. The migration must retain the registered package/assay property-data path, input digest/provenance, cancellation/deadline ownership, product-stream identifiers, and the current publication result types.

Keep conservation, authored specifications, property-data provenance, and a fresh publication-time recomputation as non-negotiable boundaries. The existing `V3AcceptanceAuditor` remains the compatibility auditor for V3 replay, but its strict-positive-phase convention is not automatically valid for a model with deliberately inactive zero-flow phases. A new core therefore needs versioned convergence and audit semantics: its auditor must independently re-evaluate conservation, active-phase equilibrium/stability, energy, condenser/free-water identity, water, and truncation; it must explicitly distinguish an inactive phase from a numerically invalid negative or non-finite phase. It is still not an independent physical oracle. The current `rawNewtonCertificateVerified=null` limitation and unavailable request-wide work counters must remain explicit until separately instrumented.

The existing solver is already an equation-oriented simultaneous MESH corrector after sequential initialization and continuation. Calling a rewrite “equation-oriented MESH” is therefore insufficient: a proposal must identify changed unknowns, equations, specifications, scaling, and globalization behavior.

## Alternative assessment

| Alternative | What it could address | What is unproved or risky | Decision |
| --- | --- | --- | --- |
| Targeted continuation/ramp repairs | The observed clusters: low-pressure anchors, full side-draw loading, and wet/draw ordering. It can preserve existing states, contracts, and auditor. | A longer or reordered ramp can hide an incorrect degree-of-freedom treatment, consume the whole deadline, or simply move failures. | **First investment.** Instrument and repair one causal continuation fault at a time. |
| Constrained equation-oriented MESH rewrite | A state built from scaled totals/compositions, active-phase/stability decisions, stage-local derivatives, and one consistent globalizer can address log-trace, fixed-tray, and phase-boundary limitations together. | It replaces the core that is already simultaneous MESH; phase switching, property derivatives, side draws, steam, and newly versioned audit semantics need requalification. No independent crude accuracy oracle presently validates it. | **Scoped prototype now justified**, behind the API and a new auditor version. |
| Inside-out balance-elimination method | Eliminating component balances can materially shrink the nonlinear system and supply a genuinely different formulation, rather than merely seeding the current Newton/certificate path. | Its outer specification and K-value closures still need stability/phase semantics, property derivatives, and exact draw/steam bookkeeping. It can fail differently rather than better. | **Competing prototype**, evaluated on the same gates and not subordinated to the current final-certificate path. |
| Pseudo-transient or dynamic relaxation | Can damp an ill-conditioned initial state and provide a continuation parameter when steady Newton stalls. | A transient path does not prove a steady root; time-step/relaxation policy can be another opaque recovery stack. It still needs a final steady MESH solve and all audits. | **Initializer experiment only.** Bounded, cancellable, and never publish directly from the transient state. |

## Scoped-core prototype: decisive competition, not a disguised recovery stack

The existing invariants around positive log flows, trace support, and fixed tray/layout blocks are credible structural boundaries even though the benchmark does not isolate all 32 nonconvergence causes. A fair scoped rewrite should expose two competing prototypes behind the unchanged public API:

1. **Scaled constrained MESH:** stage totals plus bounded composition coordinates; explicit active liquid/vapor/free-water phase sets selected by stability tests; stage-local analytic or AD derivatives; and one trust-region/filter or line-search globalizer for the assembled system. The globalizer accepts only finite, bound-respecting states with a stated merit/filter rule.
2. **Inside-out balance elimination:** eliminate a material-balance block to reduce nonlinear dimension, then solve the remaining K-value/specification/energy closure with its own globalizer. It must publish a full state only after reconstructing balances and running the new auditor; it is not a preconditioner for the old certificate.

Both prototypes use the same property package, authored condenser/reflux/duty specifications, side-draw and steam contracts, cancellation/deadline ownership, and stream mapping. Neither may manufacture a tiny phase flow only to satisfy the old V3 positive-phase gate. A phase declared inactive is exactly zero in the reconstructed stream topology; a phase declared active must meet stated stability and equilibrium conditions.

### Prototype gates

1. **Algebra gate:** for each active-phase pattern, enumerate variables, equations, bounds, and authored specifications. The count must balance; phase-set changes must have an explicit transition rule and must not change a user specification or side-draw rate.
2. **Residual/derivative gate:** on small columns, compare each prototype's residual and dense Jacobian-vector products to finite differences; compare AD derivatives where used. Test phase-set boundaries from both sides, while reporting finite-difference step sensitivity.
3. **Publication gate:** create a versioned `V4` convergence record and auditor result. Require component and water conservation, exact authored specifications, energy, active-phase stability/equilibrium, valid inactive-phase zero topology, and truncation accounting. Run the V3 auditor only as a compatibility observation where its assumptions apply.
4. **Competition gate:** run both prototypes on the same frozen inputs: phase-zero, trace-threshold, 0/1/3 draw dry/wet, 60 kPa, P99/P100/P101, 40% loading, and N64-on. Record status, phase set, residual, full support, closure, output fingerprint, and request-wide work. No target convergence percentage is assumed; a result is useful only if it satisfies the versioned auditor.
5. **Accuracy gate:** require an independently assembled small-case/literature/cross-implementation lane before either prototype publishes ordinary registered-package results. Holland remains a limited special-case guard, not sufficient crude accuracy evidence.

The decisive risk is not merely implementation size. Phase selection can oscillate, total/composition coordinates can be poorly conditioned near trace components, local derivatives can disagree with property branches, and a globalizer can converge to a feasible but unintended root. The inside-out alternative adds outer-loop specification drift and reconstruction error. These risks are measurable in the gates above; preserving the old strict-positive gate would conceal, not solve, the phase-model incompatibility.

## Recommended migration: diagnose first, then replace a bounded layer

### Gate 0 — make the algebra and specifications observable

Before changing the nonlinear method, construct an immutable `V3ProblemDescription` beside the existing resolver. It should enumerate unknown blocks, residual blocks, bounds/transforms, each authored operating specification, every side-draw removal, steam source, and the expected equation-minus-unknown count. The calculator continues to own admission and deadline; the description is a diagnostic artifact, not a second input API.

Required tests:

1. For every supported condenser/reflux/duty configuration, equation and unknown counts balance and each specification maps to exactly one residual family.
2. Zero, one, and three side draws preserve component-wise material bookkeeping and do not silently add/remove a specification.
3. Dry and wet inputs, including two steam feeds, have explicit water accounting and identical topology semantics through resolver, residual, audit, and stream construction.
4. Invalid user input remains an input error; a diagnostic-path-length exception must never replace an otherwise available numerical outcome.

Do not set a convergence-percentage target for this gate. Its measurable output is a checked problem description and counterexamples for every current mismatch.

### Gate 1 — create a small, inspectable residual/Jacobian reference lane

Keep the production sparse/banded solve unchanged. Add test-only small-column references (for example 2–4 trays and a small component set) that assemble the same residual densely. For the exact same state and support, compare:

1. residual vectors and equation ordering;
2. sparse/banded Jacobian-vector products against dense finite differences over several scales;
3. analytic/automatic-differentiation directional derivatives, if AD is introduced, against finite differences; and
4. declared tridiagonal block structure against a dense assembly, failing on a material off-band term rather than dropping it.

Finite differences are a check, not the production derivative source. Their step-size sensitivity must be reported. AD parity is meaningful only after the property package’s derivative convention and unsupported branches are explicitly represented; it must not silently fall back to zero derivatives.

### Gate 2 — phase, trace, and draw semantics before a new globalizer

The hardest production regimes involve disappearing or trace phases and draw/steam coupling. Build property-level and residual-level tests before testing a new method:

1. **Phase-zero cases:** a mathematically absent vapor, liquid, or free-water product must not require an arbitrary positive flow solely to keep a log transform defined. Verify the final stream identity and phase audit.
2. **Trace/support cases:** cutoff off/on must retain external component and water closure; the cutoff-on sink budget is an approximation contract, not an excuse to renormalize missing material. Exercise a component crossing the support threshold from both directions.
3. **Side-draw cases:** each of 0/1/3 draws, dry and wet, checks that total and component removal enters the same tray material balance used by audit and stream reconstruction.
4. **Specification perturbations:** small perturbations to condenser temperature, reflux, duty, pressure, and draw rate must produce finite residuals and well-defined Jacobian directions, even if the solve is not accepted.

### Gate 3 — targeted continuation repairs with causal evidence

Use the current failures as regression fixtures, not as a promised acceptance target. Start with a single changed policy per experiment:

1. preserve a successful side-draw ramp state instead of jumping from a failed partial rung to the full request without an explanation;
2. make pressure-anchor failure and requested-point failure distinguishable in public diagnostics;
3. test a bounded alternate continuation parameter (duty or condenser temperature) only where a nearby accepted state exists; and
4. ensure support masks are re-derived or deliberately frozen with an audited reason at each continuation leg.

Each policy change needs an input-level test for the affected failure route, a saved event history, request-wide attempt/work counters, and a cold paired benchmark. It must be rejected if it only converts failures into unaudited approximate states or exceeds the request deadline.

### Gate 4 — optional prototype competition

Only after Gates 0–3, run the scoped-core competitors above. They consume `V3ProblemDescription` and return a fully reconstructed steady state to the versioned auditor, not to the old final-correction certificate. A pseudo-transient method may still be an initializer experiment, but cannot publish directly from its transient state.

Compare it on the frozen default cases plus specifically constructed phase-zero, trace, draw, wet, and low-pressure fixtures. Record accepted status, audit result, support, stream fingerprint, exact/external closure, terminal residual, and request-wide work. Do not use its transient steps or an oracle-seeded solve as cold-convergence evidence.

### Gate 5 — independent accuracy evidence

The Holland lane is valuable but limited: it supplies an independently assembled special-case oracle and a near-root path, not a cold crude-column truth set. Before changing the public nonlinear core, add at least one of:

1. a second independently assembled MESH residual/property reference for small registered-package fixtures;
2. reproducible literature cases with matching property assumptions and published stream/profile tolerances; or
3. a cross-implementation comparison with fixed property inputs, stream mapping, and disclosed tolerances.

Agreement with the common evaluator, the regularized final-correction field, or deterministic reruns is not enough. Accuracy gates should compare component flows, temperature, phase identity, energy, water, and closure; they must state the unavailable quantities rather than inventing tolerances.

## Decision rule

Do not replace the production core until the algebra, derivative, publication, competition, and independent-accuracy gates pass. Targeted continuation repair remains useful for V3 serviceability, but the scoped MESH and inside-out prototypes should compete fairly rather than being forced through an incompatible old positive-phase certificate. If neither passes the new auditor on the failure fixtures, retain V3 and avoid a rewrite justified only by architectural preference.

# Refinery-wide equilibrium-stage initializer requirements

The [neural-first execution and training-data contract](V3_NEURAL_INITIALIZER_CONTRACT.md) specifies the subsequently requested LNN priority, forced config modes, required inputs/outputs, and switchable wet/dry seed handling.

Updated 2026-09-10 following the user's scope clarification. Research and planning only; no model or training dataset has been built.

## Agreed scope

The initializer must support varied separation requirements across a crude-to-chemicals refinery, not just CDU and VDU. The initial scope is **equilibrium-stage equipment and off-design steady states**. Membranes, adsorption cycles, equipment-fault dynamics, flooding/fouling mechanisms, and startup/shutdown are not part of this first target.

CDU/TJL19 is a validation case, not the definition of the model's supported chemistry or equipment. A small TJL19 network is acceptable as an experiment, but cannot stand in for the required coverage.

## Revised architecture candidate

**Primary candidate: a property-conditioned graph neural network with a shared equilibrium-stage processor and typed boundary/output decoders.** It predicts a bounded initial state for a rigorous solver. This is an architecture hypothesis to benchmark, not an established best model.

The graph represents one equipment item or connected separation section. This does not require immediately training one monolithic model for an entire refinery. Equipment-level initialization and refinery-wide recycle convergence are separate integration problems.

1. **Component/property encoder.** Encode composition, component physical properties, the selected thermodynamic formulation and revision, caloric information, and relevant pair interactions. Use shared component processing so outputs follow component permutations and differing component counts. Preserve interaction information; component names or pooled average boiling points alone are inadequate. Actual extrapolation to a new chemical family still needs validation.
2. **Stage/equipment graph.** Nodes represent equilibrium stages and explicit boundaries. Directed, typed edges distinguish liquid and vapor connections. Feeds, withdrawals, pressure, and heat enter at their actual locations. Flash vessels, absorbers without a condenser/reboiler, and complete fractionators need their own boundary contracts. Do not force every service into the present CDU terminal arrangement.
3. **Specification encoder.** Describe the variable being controlled, its location, value and units, and whether it is fixed, calculated, a desired target, or a limit. This is essential for transferring between temperature-, duty-, flow-, recovery-, and purity-specified problems. Degree-of-freedom validation remains deterministic and precedes prediction.
4. **Shared processor and decoders.** Pass information in both countercurrent directions, with global or multiscale communication for long columns. Decode temperatures and phase component flows for the actual node/component counts, with branch-specific handling where needed. Initial phase or support predictions remain proposals for the physical solver to verify.
5. **Bounded correction and fallback.** Validate the domain and model metadata, construct the native state, run the appropriate solver, and publish only after unchanged numerical and physical acceptance checks. An uncertain learned prediction can be skipped without rejecting the physical problem.

A conditional 1-D residual CNN remains a strong comparator for the subset of linear columns. The graph model becomes a more credible main candidate under the expanded equipment and boundary coverage, but needs to demonstrate a practical benefit. Literal liquid time-constant networks still have no clear advantage for the agreed steady-state task.

There is related evidence for graph representations of chemical processes, although [Stops et al.](https://arxiv.org/abs/2207.12051) study flowsheet synthesis rather than initializer accuracy. More directly, [Bubel et al.](https://arxiv.org/abs/2509.06638) investigate reusable distillation surrogates using thermodynamic descriptors instead of fixed chemical identities. Their study concerns homogeneous ternary VLE systems; it supports the representation principle, not a claim of full refinery or multicomponent-crude coverage.

## Coverage should follow physical families

| Family | Representative initial coverage | What must be validated |
|---|---|---|
| Flash and phase separation | Pressure/temperature flashes, heat-specified flashes, single-phase limiting outcomes | Correct specification modes, phase stability, phase disappearance and property range |
| Conventional hydrocarbon fractionation | Stabilization and light-ends fractionation, reformate/naphtha splitting, CDU/VDU services | Appropriate real components and pseudo-components, pressure/caloric coverage, feed/draw locations |
| Close-boiling separations | Olefin/paraffin and other difficult splitters | Accurate mixture equilibrium, low separation driving force, high reflux and potentially larger stage counts |
| Physical absorption and stripping | Gas/liquid contacting with specified solvent or stripping-medium feeds | Multiple inlet boundaries, no compulsory condenser/reboiler, validated solvent equilibrium |
| Strongly nonideal or multiphase service | Solvent-assisted or water-bearing separation where a validated equilibrium formulation exists | Appropriate thermodynamics, phase stability and possibly multiple liquid phases; do not assume the current water approximation suffices |

These are proposed coverage families, not claims about current V3 capabilities. Reactive absorption and other chemical-equilibrium extensions need an explicitly supported teacher model before they can enter the supported envelope.

For example, [IDAES's flash model](https://idaes-pse.readthedocs.io/en/stable/reference_guides/model_libraries/generic/unit_models/flash.html) distinguishes heat-duty versus outlet-temperature specifications and pressure-change versus outlet-pressure specifications. This illustrates why equipment identity alone is insufficient: the specification mode must also be represented.

## Meaning of inadequate functioning

The model must not equate a well-solved physical state with a successful separation. Keep two independent records: **physical/numerical validity** and **performance against the separation objective**.

| Outcome | State-training treatment | Reported meaning |
|---|---|---|
| Physically accepted, meets product target | Include the accepted state | Separation objective met |
| Physically accepted, misses purity/recovery/cut target | Include the accepted state, with the target miss recorded | Equipment operates, but separation is inadequate |
| Physically accepted at low reflux, limited heating/cooling, low solvent/steam, excessive withdrawal or near a phase limit | Include when supported by the teacher's equations and domain | Off-design operating state; quantify achieved performance |
| Constraint-proven infeasible or incorrectly specified | No invented solution profile; retain diagnostic evidence | Infeasible under the stated constraints, or invalid degrees of freedom |
| Nonconverged, timed out, or failed audit | No solution label; retain solver outcome separately | Numerically unresolved, not demonstrated physical infeasibility |
| Outside thermodynamic/equipment coverage | No claimed valid prediction | Unsupported by the current model envelope |

Illustrative example: if an operating point physically produces 95% purity against a desired 99.5%, that accepted physical state is a valuable label. The initializer must not silently increase heat or reflux until the product appears on-spec. Conversely, a failed attempt to enforce 99.5% purity is not evidence that the actual equipment would produce 95%.

This requires a distinction between **rating mode** and **target-solving mode**. Rating computes achieved products from actual operating conditions and compares them with targets. Target-solving adjusts declared degrees of freedom to meet objectives subject to limits. A capacity-limited rating fallback is a separately specified calculation, not a relaxation of the original physics audit. Finding the best achievable separation is an optimization task beyond initialization itself.

## Current V3 is not yet a complete data teacher

The present [specification interface](../../src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnSpecification.java) supports condenser outlet temperature, organic reflux ratio, and reboiler duty. It does not expose general purity/recovery/product-flow or condenser-duty specifications. Its [property registry](../../src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3PropertyPackageRegistry.java) contains two fixed first-party hydrocarbon packages, and [its thermodynamic boundary](../../src/main/java/com/wormzjl/createcheme/science/column/v3/thermo/V3ThermoModel.java) treats water separately. The [topology contract](../../src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnTopology.java) assumes a column spine with both terminal nodes and currently permits 2–64 trays.

Consequently, increasing the number of samples around TJL19 cannot produce the requested coverage. The reference solver must first support and validate each additional boundary/specification/property family. A practical option is to generalize V3's equilibrium-stage machinery and share that machinery across equipment-specific front ends. Existing V3 data can supply the already-supported subset.

For off-design cases, the teacher also needs the relevant operating constraints. For example, fixed condenser temperature cannot by itself describe a condenser that lacks sufficient cooling duty: a supported duty or capacity constraint must determine the resulting outlet state. Do not introduce flooding, fouling or damaged-internal labels by perturbing ideal-stage outputs; these remain outside the agreed first phase.

## Dataset and qualification plan

1. Define a coverage manifest over equipment family, chemistry/property family, specification mode, geometry, and operating regime. Track supported and unvalidated combinations explicitly.
2. Establish accepted reference fixtures for each supported family, including intentional off-spec operation. Build reusable input/state/specification representations before choosing a large architecture.
3. Generate accepted on-spec and off-spec state labels. Include reduced driving force, low reflux/steam/solvent, constrained duties, changing throughput/feed composition, extreme but supported pressures, and phase-boundary neighborhoods.
4. Retain failed runs as numerical outcomes with provenance. They may inform expected correction difficulty, but cannot train an unqualified physical-infeasibility classifier.
5. Balance sampling across services and regimes. Do not let easy CDU cases dominate the loss or the reported acceptance rate. Add sampling where correction fails, where models disagree, or where boundary coverage is sparse.
6. Hold out complete chemical systems, equipment layouts, specification combinations, and operating regions. Related continuation trajectories stay in one partition. A graph's ability to accept an unseen shape is not evidence that it predicts that shape accurately.
7. Compare a property-conditioned stage GNN against a conditional 1-D ResNet on linear equipment and retrieval/MLP baselines where applicable. Report accepted correction rate, total time including fallback, tail latency, off-spec prediction quality after correction, and performance separately for every coverage family.
8. Promote coverage family by family under one extensible interface. Do not advertise universal refinery coverage until the corresponding chemistry, equations, data and correction tests exist.

The revised goal is therefore a reusable equilibrium-stage initialization framework with explicit coverage, rather than a network specialized to named CDU/VDU presets. The first numerical result to establish remains whether a learned seed improves a rigorously accepted solve; the long-term representation and dataset must already accommodate the broader scope.

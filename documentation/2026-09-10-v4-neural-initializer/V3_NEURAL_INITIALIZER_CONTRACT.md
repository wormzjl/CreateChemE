# Neural-first initialization and training-data contract

Design specification, 2026-09-10. Implements the user's intended policy at the design level: LNN first, current initializer as backup, with forced initializer selection and switchable seed-handling changes. No configuration fields, solver changes, training jobs or datasets have been implemented by this document.

Coverage remains refinery-wide equilibrium-stage equipment and off-design steady states, as defined in [the coverage requirements](V3_REFINERY_INITIALIZER_REQUIREMENTS.md).

## Initializer selection

| Proposed mode | Required behavior |
|---|---|
| `LNN_FIRST` — default | Attempt a supported LNN seed and bounded physical correction. If unavailable, incompatible, rejected, or unsuccessful, run the current initialization path once within the remaining request budget. |
| `LNN_ONLY` | Use the LNN seed and physical correction only. Return an explicit initialization failure if no usable seed or accepted corrected state is obtained. Never silently invoke current initialization. |
| `CURRENT_ONLY` | Bypass model loading/inference for the request and use the existing initialization path. Preserve its original wet-set behavior and acceptance rules. |

Input/DOF invalidity, a proven infeasible specification, unsupported thermodynamic physics, and caller cancellation are not reasons to repeatedly try different initializers. A local neural-attempt budget expiring may trigger backup under `LNN_FIRST`; the whole-request deadline or cancellation must terminate the request instead.

Normal physical correction, branch verification, and support repair are permitted in `LNN_ONLY`. A cold restart that invokes the current initializer is not. The implementation must enforce this distinction inside recovery helpers as well as at the public entry point.

Neural priority must not secretly require the full current initializer to run before inference. Direct state prediction, common feed-property calculations, or a cheap independent reference construction are appropriate. A model that depends on a complete classical seed is a hybrid dependency and must not be advertised as independent neural initialization.

## Proposed configuration

These are proposed additions to the existing `[columnV3]` section of `createcheme-common.toml`, not currently recognized settings:

```toml
[columnV3]
initializerMode = "LNN_FIRST"
lnnWetStartMode = "AUTO"
```

`lnnWetStartMode` applies only to learned seeds:

- `AUTO`: use a validated predicted wet set when the model and adapter support it; otherwise use a dry initial set when compatible with the model's declared seed role. Report the choice.
- `DRY_START`: start with free-water tray variables zero and the initial tray set dry. Subsequent physical wet-tray detection and correction remain enabled.
- `PREDICTED_WET`: initialize the predicted admissible wet set and free-water amounts. If the model cannot supply them or validation fails, decline the neural attempt. Global initializer mode decides whether the current initializer may run.

`DRY_START` does not remove authored steam or PA duties. It changes the initial guess only. A **dry-surrogate seed** is different: it belongs to a separately specified intermediate problem and must proceed through the required feature continuation before a requested-problem result can be accepted.

Additional bounded numerical options should include a neural correction-iteration allowance, total neural-attempt time allowance, and explicit handling of predicted condenser branches. Default numerical budgets require benchmark evidence; they should not be guessed from inference speed alone. Every neural retry, branch attempt and wet-set repair shares one neural budget and the original request deadline.

The logical server reads configuration at admission, following the existing pattern in `ProcessSolveServices.submitV3Column`. The admitted command carries an immutable policy and model revision/reference. Config reload must not change an in-flight request. Multiplayer clients cannot override the server's execution policy.

## Required model inputs

All physical quantities use declared SI units. Features must be obtainable from the request, the selected property package, or explicitly budgeted preprocessing; no feature may require the already-converged target state.

| Input group | Required content | Why it is needed |
|---|---|---|
| Physical model identity | Equipment/phase-equilibrium formulation, property-package identity and revision, applicable phase set, solver/input schema versions | Identical numerical inputs under different physics need not have the same solution. Version identity also gates compatibility. |
| Component basis | Ordered IDs for transport plus shared physical descriptors; real/pseudo-component distinction and pseudo-component characterization | A fixed TJL19 index is not a general chemical description. Predictions must map back to the correct component basis. |
| Thermodynamic descriptors | Parameters appropriate to the selected formulation: for the current PR family, critical properties, acentric factors, molecular weights, mixing/binary interactions and caloric parameters; additional descriptors for other supported formulations | Phase splitting and energy balance depend on mixture properties. A package name alone cannot support chemical transfer. |
| Equipment graph | Node IDs/types, stage count, directed typed phase connections, feeds/outlets, condenser/reboiler configuration or absence, permitted phase topology | Enables differing equilibrium-stage equipment and layouts. |
| Feed streams | Attachment node, per-component molar flows, feed state specification and values such as T/P or H/P, phase information when specified; steam/solvent streams represented explicitly | Defines material and energy entering each location, including multiple feeds. |
| Pressure conditions | Boundary pressure, prescribed stage profile or drop, and pressure specification mode | Determines local phase equilibrium and identifies fixed versus solved pressure. |
| Operating specifications | Typed variable, component/key pair where applicable, location, value, and role: fixed, calculated, target or limit | Distinguishes reflux, duty, temperature, product flow, recovery and purity modes without overspecifying the system. |
| Heat and withdrawals | Signed stage heat, reboiler/condenser specifications, PA heat placement, side-draw node/phase/flow or split rule | Required to distinguish otherwise similar separation services and off-design conditions. |
| Validity and scaling masks | Existing nodes/components/phases, controlled quantities, normalizing flow scales, valid parameter ranges | Prevents padding, absent phases or zeros from being treated as physical flow. |
| Seed task/role | `REQUESTED_PROBLEM` or a precisely described intermediate problem, including actual ramp fractions/specifications | Prevents a dry or partially ramped profile from being labeled as the final requested state. |

The dataset should preserve the authoritative property package or reproducible reference to it, even if the network consumes a smaller standardized descriptor vector. Different thermodynamic families need different sufficient descriptors; a universal fixed list of PR constants is not adequate for all refinery separations.

For rating mode, desired product targets describe performance assessment, not extra physical constraints. Changing a displayed target while keeping all actual operating conditions fixed must not change the predicted physical state. Target-solving mode explicitly declares which operating variables may change and which limits apply.

## Required prediction targets

Use a canonical physical node/phase/component representation. Do not train directly on the packed Newton vector, because phase/support decisions change its length and meaning.

| Target | Current V3 representation | Training/decoding requirement |
|---|---|---|
| Node temperatures | `temperatureKelvin(node)` | Predict unknown temperatures; restore prescribed boundary temperatures exactly. |
| Liquid hydrocarbon component flows | `liquidFlow(node, component)` | Use normalized transformed positive flows plus explicit zero/support information. |
| Vapor hydrocarbon component flows | `vaporFlow(node, component)` | Same treatment; absent boundary vapor must remain zero. |
| Free-water liquid flow | `freeWaterFlow(node)` on permitted trays | Required to initialize a wet state. Separate wet/dry labels from the positive-flow magnitude. |
| Wet-tray set | Accepted problem's wet-tray membership | An initial active-set proposal, not permission to skip dew-point or phase checks. |
| Condenser/boundary branch | Accepted boundary phase regime | Required metadata; use separate heads/models or a ranked branch proposal when supported. |
| Other solved boundary variables | Only variables present in the equipment-specific unknown contract | Needed when future specification modes solve duties, reflux, pressures or other boundary variables explicitly. |

Current V3 derives vapor-water flow from its authored water feeds and free-water state. Export it for consistency checks, but do not independently predict a contradictory second water profile. For future general liquid-liquid systems, export the actual phase component flows; pure free water is a special adapter case, not a universal definition of a second liquid.

Support labels distinguish an absent phase from a small retained component flow. Learned support is advisory: the rigorous support construction and reinsertion mechanisms retain ownership of the physical unknown set. Transform only positive flows logarithmically, represent exact zeros explicitly, and apply the existing numerical floors only where the chosen coordinate map requires them.

Do not impose a global free-water-flow cap equal to injected steam: internal condensation/revaporization can create circulating flows. Validate the actual water-balance and phase-compatibility conditions instead.

## Required sample records, not necessarily network outputs

Each attempted case also needs:

- Exact requested input and exact problem actually solved, input hash, parent/ramp identifiers, data-generation seed and continuation history.
- Numerical outcome, independently recomputed acceptance audit, convergence certificate, tolerances, trace/support policies, and source/property revisions.
- Physically accepted state only when all applicable gates pass. Failed candidates may be stored separately as diagnostics, never as accepted state targets.
- Achieved product compositions, flows, recoveries and duties for consistency and performance evaluation; these are generally derived from the accepted state, not independent variables the initializer must predict.
- Separation-performance status and target errors, separate from physical/numerical validity. Accepted off-spec states are valid training samples.
- Total timing and actual measured correction-work counters, with unavailable counters marked unavailable. Existing placeholder counters must not become apparently measured training targets.

An uncertainty or expected-correction-success head is optional. It requires calibration on held-out outcomes or out-of-fold model attempts. Solver nonconvergence must not be relabeled as physical infeasibility, and a learned classifier must not acquire authority to certify feasibility or physical acceptance.

## Solver changes and their switches

| Change | Proposed control | Necessary behavior |
|---|---|---|
| Initializer orchestration | `initializerMode` | Neural priority, strict forced modes, bounded backup; no hidden classical calls in `LNN_ONLY`. |
| Entry with a complete supplied state | Immutable `SeedCandidate` and policy, distinct from cold initialization | Accept physical arrays, basis, topology, seed role and model provenance; reject incompatible shapes or roles. |
| Initial wet-tray handling | `lnnWetStartMode` | Preserve the exact current dry-first path or admit a validated predicted wet set. |
| Wet-state correction strategy | Internal enum such as `EXISTING_CONTINUATION` / `PREDICTED_ACTIVE_SET_THEN_CORRECT` | Keep branches explicit and testable. A predicted set may be repaired; it is never permanently frozen just because it came from the model. |
| Initial condenser branch | Explicit seed-policy enum rather than an implicit global override | Validate/rank predictions and allow only bounded, permitted correction attempts. |
| Trace/support preparation | Existing cutoff plus explicit seed preparation | Rebuild the support, wet set and DOF ledger consistently from the chosen physical seed; never reuse a training sample's packed indexing blindly. |
| Failed neural candidate | Global initializer mode | In `LNN_FIRST`, discard request-local candidate state and run the unchanged current path. In `LNN_ONLY`, return a typed reason. |
| Audit and convergence gates | No new neural bypass switch | Every route uses the request's existing declared tolerances and physical checks. Neural mode must not loosen them. |

The current `prepareAttempt` begins with `V3WetTraySet.dry(...)`, and `V3WetTraySet.seed` clears free-water flows when the set is dry. An explicit seeded path must therefore carry the proposed wet set alongside the state, construct matching saturation equations/free-water unknowns, and preserve valid predicted amounts. Merely passing a profile array would lose the wet information.

Initial validation checks structural admissibility, finite values, supported phases, nonnegative flows, boundary consistency, numerical scale and reasonable thermodynamic-domain membership. It must not demand that an approximate initial guess already satisfy final equilibrium tolerances. Correction and final audit provide that evidence.

If existing parametric free-water continuation is used to stabilize the learned wet seed, its frozen values remain initialization machinery. The final certifying solve must restore the appropriate free-water unknowns and pass the actual requested problem's audits; a parametric subproblem cannot certify the full solution by itself.

## Code integration points

- `CreateChemE.java`: proposed config enums and bounded options in the existing common config.
- `runtime/ProcessSolveServices.java` and the V3 solve command: snapshot policy/model identity at admission.
- `V3ColumnCalculator.java`: policy-aware orchestration, explicit seeded entry, shared corrected-result publication and backup routing.
- `V3ColumnInitializer.java`: retain the present implementation as the current strategy and preserve regression behavior.
- `V3WetTraySet.java`, `V3ColumnProblemResolver.java`, `V3DegreeOfFreedomLedger.java`, and `V3DryMeshCoordinateMap.java`: consistent seed/phase/unknown preparation for both switch positions.
- `V3SolverDiagnostics.java`: requested/effective initializer, model revision, seed role, wet-start choice, neural work, backup reason and backup work.
- Offline exporter: capture accepted physical profiles and matching problem/support/phase metadata without exposing mutable solver workspaces as normal public results.

The pure science layer receives immutable options and has no dependency on Minecraft configuration APIs. Model weights are immutable; inference workspace belongs to the request. Reproducibility metadata includes an execution fingerprint for initializer policy and model version, distinct from the physical problem identity; any result cache must account for the selected execution semantics.

## Qualification before enabling the trained model

1. Confirm that `CURRENT_ONLY` reproduces the existing result path and acceptance behavior.
2. Verify all three initializer modes for missing/incompatible models, rejected seeds, local neural timeout, global cancellation, unsuccessful correction and successful correction.
3. Replay exact accepted dry and wet states through the new seed adapter before using neural predictions. Test that wet state information survives and matches the DOF ledger.
4. Test near-zero free water, phase disappearance, changed active components, malformed masks, and inconsistent boundaries without weakening audits.
5. Test off-spec but physically accepted operating points in both learned and backup paths.
6. Confirm fallback cannot loop or reset the request deadline and that configuration reload cannot alter admitted work.
7. Compare accepted end-to-end performance on held-out equipment, chemistry, specifications and operating regimes. The model package's coverage manifest must govern where neural-first is attempted.

The intended production default is `LNN_FIRST`. Until a compatible trained model exists, that mode would explicitly report model unavailability and use the current initializer. Forcing `LNN_ONLY` would instead report an unavailable-model failure.

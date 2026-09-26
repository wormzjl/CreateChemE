# Pipe thermal loss feasibility review

Date: 2026-09-24. Status: Concluded (assessment only; scope revised following owner clarification).
Code inspected: main checkout at `f9d6be1`. No source changes, benchmarks or tests run.

## Agreed scope and revised assessment

The owner explicitly does not require cooling stored fluid in stopped pipes. Pipes continue to own zero fluid. Estimate heat loss from flow velocity and pipe length, with approximately +/-10% accuracy acceptable, using a bounded approximation analogous to column tray pressure drop.

This is feasible at medium integration complexity. No pipe inventory, wall thermal state, transport delay or transient axial model is required. The earlier 3-6 week detailed phase-profile and 6-10+ week pipe-holdup scopes are outside this request. The recommendation is now the approximate flowing-stream model, rather than making detailed condensation profiles a prerequisite.

Rough engineering estimate for a developer familiar with this engine: 1-3 days for a heat-law/coupling prototype; about 1-2 weeks total for a qualified integrated feature, including conservation, persistence, replay and tests. These are judgement ranges, not delivery commitments. The allowed model error simplifies thermal physics; it does not remove integration work.

## Existing architecture

Java paths below begin with `src/main/java/com/wormzjl/createcheme/`.

| Mechanism | Source | Implication |
|---|---|---|
| Finite reservoirs own material and internal energy; pipe edges own no fluid | `science/fluid/network/PassiveNetwork.java` | Apply estimated heat to transported energy without adding pipe storage. Filters separately own captured material/energy. |
| Degree-two pipe nodes collapse into runs | `science/fluid/topology/TopologyCompiler.java`, `runtime/fluid/PhysicalFluidTopology.java` | Preserve current aggregation and cost per run. Audit half-link/actuator geometry for thermal-area double counting. |
| Geometry sections coalesce by diameter/roughness; upstream properties serve the run | `PassiveNetwork.Pipe.coalesce`, loss | Suitable for approximate homogeneous runs; mixed ambient/insulation may require ordered thermal sections. |
| Simultaneous energy and junction mixing equations | `science/fluid/network/PassiveStepSolver.java`, Equations.nodeAccumulate | Receiver and junction energy must include the estimated heat within an accepted solve. |
| Independent reconstruction and audit | `ConservativeTransport.reconstruct0`, `PassiveStepSolver.audit` | Reuse the same estimated thermal law in both. |
| Multiple integration paths | `TrBdf2StepSolver`, `PassiveIntervalSolver`, `ApproximationAnchor` | Endpoint rates, stages, error estimates and fallback must agree on the heat ledger. |
| Rest/steady replay and checkpoints | `runtime/fluid/IslandCertificate.java`, `FluidCheckpointCodec.java` | Persist thermal dependencies and ledger as needed; invalidate stale certificates/work on thermal changes. |
| Views sample upstream transport history | `science/fluid/network/PipeTransfer.java`, `runtime/fluid/FluidView.java` | Any displayed estimated outlet temperature/phase requires explicit committed stream data. |

## Velocity/length model

Use a virtual exposure time, not stored fluid or delayed delivery:

```text
exposure = L / abs(v)
T_out = T_ambient + (T_in - T_ambient) exp(-k * exposure)
k = U * perimeter / (rho * flowArea * cp)
Q_loss = abs(massFlow) * cp * (T_in - T_out)
```

Here U is an effective heat-transfer coefficient, cp is mass-specific heat capacity, and perimeter is the chosen thermal perimeter. For a circular bore with the same thermal reference diameter, k = 4 U / (rho cp D). Insulation reduces effective U. This is equivalent to the usual exp[-UA/(massFlow cp)] relation. Velocity and length determine exposure, but temperature difference and estimated thermal properties still determine how much energy is lost.

This relation assumes approximately constant single-phase properties and negligible pressure/elevation effects on temperature. It is a cheap baseline and analytical test oracle. COMSOL's [pipe heat-transfer theory](https://doc.comsol.com/6.3/doc/com.comsol.help.pipe/pipe_ug_heattransfer.06.17.html) documents the underlying wall source, film/wall resistances and quasi-static wall assumption. Coefficients for the game still need selection/qualification.

Exactly zero flow gives zero heat transfer in this zero-inventory model. Near zero flow the outlet approaches ambient while heat power approaches zero. Avoid division at exactly zero and use numerically stable evaluation for small exponents. Reverse flow selects the new upstream state. Cold streams can gain heat using the same signed law. Exposure is purely a constitutive estimate; simulation clocks remain online ticks at nominal 20 TPS.

## Analogy to the column pressure-drop approximation

Verified in `science/column/v3/V3ColumnCalculator.java`: HYDRAULIC_MISMATCH_TOLERANCE is 0.10. The solver holds a pressure profile fixed, evaluates/marches hydraulics from a converged solution, and requests a bounded warm correction when disagreement exceeds that threshold. It includes bounded recovery; it does not perform an unrestricted outer convergence loop. See also `V3TrayHydraulics.java` and `documentation/2026-09-18-tray-pressure-drop/TRAY_PRESSURE_METHOD_REVIEW.md`.

Proposed pipe adaptation:

1. Seed thermal coefficients/property estimates from the last accepted state; on first use, use the authoritative initial/upstream state. Do not require previous history or invented pipe stock.
2. Hold the expensive property/correlation estimates fixed during the network solve. Evaluate the cheap velocity-dependent thermal law using the trial flow, preserving the zero-flow limit and correct donor on reversal. Do not freeze a nonzero heat-power sink independently of flow.
3. From the converged candidate, recompute thermal estimates and compare integrated heat loss with the estimate used. Treat +/-10% as a heat-loss target, with an absolute tolerance near zero heat loss.
4. If discrepancy is significant, make one bounded warm correction with refreshed estimates, starting from the SAME pre-step inventory and time. The provisional candidate is not committed. No double time advance or double deduction of heat.
5. Commit one accepted result and its matching heat ledger. If correction fails, a conservative provisional solution may only be retained under an explicit approximation policy and mismatch indication; it cannot silently be called within 10%. Domain-invalid results still fail/hold normally.

The column's measured weak coupling does not prove this pipe map will contract similarly. Measure hot/cold liquid and gas runs, long/slow runs, flow reversal and phase-boundary cases against a more closely coupled reference. A 10% self-mismatch trigger is not by itself proof of 10% physical accuracy; U uncertainty and the model approximation are separate errors.

## Phase changes within this scope

The energy ledger should remain enthalpy-based. Existing receiver/junction equilibrium can respond to reduced delivered energy and may change phase there; this does not require pipe holdup. A detailed in-pipe phase profile and its local pressure-drop feedback remain approximations.

A constant-cp temperature law is not reliable through condensation. If such cases fall within the intended operating range, use a bounded enthalpy/temperature estimate with latent-heat awareness outside the inner network solve and qualify its heat-loss error. Do not claim general 10% accuracy for condensing runs from the single-phase formula alone. `FluidThermodynamics.flashTP` is explicitly not intended for repeated nested use in time-step residuals. Detailed spatial multiphase hydraulics are not required by the clarified request.

Material-specific temperature/pressure limits in `FluidDomain` remain enforced. Freezing and wax precipitation are not introduced by this change.

## Energy, scheduling and validation

Approximate heat-transfer physics must still have a consistent energy ledger:

```text
delta(stored internal + gravitational + filter energy)
    = boundary energy + pump work - integrated estimated ambient heat loss
```

The donor exports its normal stream energy; the receiver gets that energy less the estimated loss. Do not subtract hydraulic friction again as disappearing energy. Define ordering relative to pumps and filter capture. Approximation error is not permission to relax energy-balance acceptance by 10%.

Compute in due worker solves, using immutable environment/settings snapshots. Fixed ambient is the simplest starting point. Changes to ambient/insulation must be queued dependencies and invalidate affected estimates/certificates. Presentation remains on the engine's roughly five-second schedule. No per-tick process calculation, offline cooling or wall-clock advancement.

Implementation checks:

- Zero conductance matches the adiabatic result; analytical velocity/length scaling, insulation, heating/cooling, zero/near-zero flow, reversal.
- Conservative component/energy closure across junctions, finite/fixed boundaries, pumps, filters and scheduled transfers; rejected/corrected candidates commit only once.
- Matching heat integration across endpoint rates, stages, accepted intervals, fallback and replay. Fixed-boundary runs need observable heat/outlet error checks even if finite-node state error is absent.
- Qualification of the bounded correction against a more closely coupled reference; report error in heat loss with an absolute floor near zero, plus phase-boundary coverage where claimed.
- Validated fresh-world round trips of thermal metadata, clocks, pending events, heat ledger and optimisation state. No migration or old-save gates.
- Existing `fluidScienceTest`, `fluidRuntimeTest`, `fluidSolverRegression` gates and targeted new cases. Measure long-run and steady/rest networks; do not assume the column's timings transfer. One Gradle invocation at a time, never alongside a dev client.
- Any GUI changes verified in a fresh dev world through the Minecraft MCP bridge.

Assessment complete; implementation has not started. No detached tooling, source changes, version bump or changelog entry were needed.

## Measured follow-up

The 2026-09-24 experiment is complete: see [PIPE_THERMAL_LOSS_EXPERIMENT_REVIEW.md](PIPE_THERMAL_LOSS_EXPERIMENT_REVIEW.md). A 17-point enthalpy curve met the thermal-only 10% target in 561 cases; a single hydraulic correction did not reliably reproduce cooling-dependent axial pressure drop. These measured results supersede the untested coupling expectation above. Prototype code exists only in the isolated experiment worktree.


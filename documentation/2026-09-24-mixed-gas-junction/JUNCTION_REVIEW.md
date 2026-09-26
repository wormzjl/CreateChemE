# Mixed-gas junction investigation and proposed fix

Concluded 2026-09-25 (investigation); production fix remains planned.
Base: main 9674bf1 / 0.5.0. Isolated worktree: C:/Users/wormz/.codex/worktrees/mixed-gas-junction/CreateChemE, branch codex/mixed-gas-junction. Main was not changed. All temporary solver edits have been restored; candidate patches/probes are under canonical tools/pipe-junction-probe/.

## Findings

### Confirmed startup defect
The physical compiler initializes a junction from the first boundary's complete state (PhysicalFluidTopology.java:93). In the reproduced case this is pure methane at the common source pressure. Both inlet pressure differences start at zero; the numerical flow estimate is about 4.44e-16 kg/s, below the 1e-14 transport deadband. Consequently refineJunctionReachability/restateJunctions omit the nitrogen donor from the first junction layout. The first solve has only amount, temperature and pressure unknowns for the junction instead of the mixed-gas layout.

The first Newton residual is 0.2217806 from net mass imbalance. Its first direction changes log-temperature by about 0.995 while moving junction log-pressure only 8.38e-9; it then spends the 20-iteration budget approaching an intermediate wrong-support solution. Residual remains 5.8168e-6 for the four-port 1 kPa case. The active set can rebuild donor support only after a converged point; this pass never reaches one. Raising the budget or weakening conservation would hide the initializer problem.

This is an algebraic zero-storage network. Subdividing time does not repair its initial pressure/donor/composition inconsistency. The same bad first pass repeats until the interval refinement exhausts.

### Confirmed additional transient failure
Startup correction is insufficient for general transient operation. With finite tanks, the candidate produces valid evolving flow and changing compositions but rapid discharge still stalls near equilibrium. A fresh PassiveIntervalSolver each accepted 0.1 s interval does not remove this failure, so retained workspace corruption is not an adequate explanation.

The precise near-equilibrium mechanism is not fully isolated. The code normalizes incoming component/enthalpy flux by incoming mass down to 1e-14 kg/s; donor modes and support are frozen per active-set pass. Flow-column finite differences have a scale floor of 1, giving a minimum 1e-6 kg/s perturbation, large compared with the flows near equilibrium. A smaller floor moved the four-port equal-tank failure from 1.6 to 4.5 s and the unequal-tank failure from 1.5 to 2.6 s, but did not cure five-/six-port cases. This supports investigating the low-flow Jacobian and stagnation/donor transitions; it does not establish a complete remedy.

## Experiments

All runs were sequential, with no dev client running. No old-world or migration tests. The probes are external sources loaded by tools/pipe-junction-probe/probe.init.gradle. Some exploratory JUnit wrappers catch failures to report all variants: a green Gradle task is NOT evidence that every case solved. The case-level output below is the evidence.

### Fixed boundaries
Two methane/nitrogen feeds at 350 K, 4/5/6 equipment faces, including vertical faces; source pressure 102325 or 150000 Pa, sinks 101325 Pa. Physical pipe bore 0.05 m, nominal block geometry 1 m (compiled half-block connection sections). Requested interval 0.05 s.

- Unmodified first-boundary seed: all six original port/pressure combinations fail.
- Change only the arbitrary junction pressure to the boundary midpoint: all six solve and preserve each component and total mass. Low-boundary seeds also passed these six. No physics, pressure boundary, tolerance or iteration budget changed.
- Flow-only reseeding while retaining the old junction pressure is insufficient; it fails several cases. Pressure, flow and mixture guesses must be coherent.
- Midpoint-seed prototype: all 18 port/pressure/initial-seed variants passed, and the original direct reproducer passed all three port counts.
- Expanded 32-case matrix adds 3 ports, swapped source species, 300/400 K feeds and unequal source pressures (100 Pa difference at low pressure, 5000 Pa at high pressure). Midpoint alone failed; the pressure/enthalpy-balanced prototype improved this but still exposed genuine nonconvergence in the unequal high-pressure 3-port case and swapped 4-port case. One other probe failure was an invalid assertion that both generators must supply: one-way generator shutoff can legitimately leave only one inlet. That assertion has been corrected in the stored probe. Do not claim full qualification of these variants.
- 38 nearby retained product tests passed alongside the bounded balanced prototype: PassiveStepSolverTest, PumpJunctionStartupTest, FilterBlockLineIslandTest, DeadHeadedLineIslandTest, NetworkRegimeTest, PhysicalFluidTopologyTest, PipePresentationTest. No full-suite or production performance qualification.

### Non-static finite tanks
Two finite 1 m3 tanks feed a junction with 2-4 void outlets at 101325 Pa. Pure methane / nitrogen initially. Equal case: both tanks 150000 Pa / 350 K. Unequal case: first tank 150000 Pa / 350 K, second 90000 Pa / 400 K; the lower tank initially receives methane and later changes flow direction. Each run requests 50 consecutive 0.1 s intervals = five seconds, reusing the accepted graph and normal retained solver.

| Configuration | Unmodified solver | Balanced initializer prototype |
|---|---|---|
| 20 mm bore, 4/5/6 ports, equal tanks | All fail before first interval | All three finish 5 s |
| 20 mm bore, 4/5/6 ports, unequal tanks | All fail before first interval | All three finish 5 s; one edge reverses in each |
| 50 mm bore, 4/5/6 ports, equal tanks | All fail before first interval | Start; stall at 1.6/1.5/1.5 s |
| 50 mm bore, 4/5/6 ports, unequal tanks | All fail before first interval | Start and reverse; all stall at 1.5 s |

Every accepted interval was independently checked against the sum of finite tank inventories and accumulated boundary transfers, including internal plus gravitational energy. Successful 20 mm cases had maximum scaled component error below 1.6e-12 and energy error below 1.9e-11. The receiving tank acquired 3.05-3.20 mol methane, proving composition actually changed. All rapid cases also passed the balance checks until they stalled; no partial failed interval was committed.

For context, in the equal four-port 50 mm case tank pressures fall to about 116.5/114.8 kPa at 1 s, then 102.63/101.67 kPa at 1.5 s, and almost 101325 Pa at 1.6 s. The later failure is an approach-to-equilibrium failure, not the original cold-start failure.

An earlier unequal case at 110000 Pa / 400 K also stalled at about 1.5 s; rebuilding the solver every interval did not resolve it. Its evidence remains in transient-balanced-warm/cold.xml.

## Proposed production fix

1. Add a bounded junction initializer BEFORE freezing donor support. For an eligible passive junction, predict a pressure from connected hydraulic heads and the actual pipe laws, calculate consistent incoming flows, form the incoming component mixture, and match its specific enthalpy. Respect one-way boundaries, gravity, filters/caps and each carried component's material domain. Use one coherent pressure/flow/composition/temperature guess for initialMassFlows, reachability, layouts and Newton. Do not reconstruct physical reservoir inventory; junction property guesses own no inventory. Preserve a valid warm solution. The stored balanced patch is a proof of concept for fixed-boundary stars / instantaneous PORT rate solves, not a finished general junction-network algorithm.

2. Separately repair near-zero-flow conditioning and the flowing/stagnant transition. Derive stable mixture/energy derivatives at small inflow, avoid finite-difference perturbations that dominate the actual flow, and give a zero-throughput junction an explicit retained-composition/temperature closure. A conservative flux-form junction balance with a well-defined zero-flow active set is a candidate design; it needs independent validation. Donor reversal/support updates should occur at a consistent flow event or converged active-set transition, not by promoting species from an arbitrary failed Newton iterate. Check flow-limiter and generator-shutoff transitions as part of this work.

3. Promote the original reproducer and transient cases into real assertion-based gates, including the rapid 50 mm runs continuing through equilibrium, restart after rest, source-order invariance, unequal P/T, reversed flow, vertical branches and adjacent junctions. Use finite-inventory plus boundary-ledger component/energy conservation as the oracle. Include interval accuracy, pump/filter/dead-head and trace-support regressions. Profile initialization cost; prototype's fixed sweeps/bisections are intentionally investigative and must not become an unmeasured worker-budget cost.

Do not ship the initializer alone as a complete fix, loosen conservation tolerances, globally raise iteration limits, or invent trace material. The original startup defect is established and a repair direction is demonstrated; the full transient-safe repair remains implementation work.

## Evidence and cleanup
Canonical tools/pipe-junction-probe/ contains original and dynamic probes, a local Gradle init script, baseline.xml, trace-original.xml, pressure-seed-comparison.xml, flow-only-candidate.xml, pressure-candidate.xml, balanced-static-results/, transient-baseline-two-bores.xml, transient-balanced-two-bores.xml, transient-balanced-warm/cold.xml and transient-low-flow-difference.xml, with text extracts. Candidate/trace patches all apply against 9674bf1. None was committed or merged. Source originals are recoverable with git show 9674bf1:<path>; no tracked removal commit applies because these were uncommitted experiments.

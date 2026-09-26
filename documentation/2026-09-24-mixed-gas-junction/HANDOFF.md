# Mixed-gas junction solver handoff

Updated 2026-09-25. Investigation completed; production fix still needed.

## User request and scope
Investigate mixed-gas multiport junction convergence failures and propose a fix. The user explicitly added: test non-static systems as well.

A confirmed startup defect and a separate unresolved near-equilibrium failure were found. Do not treat the successful startup prototype as a complete fix. No experimental solver changes were committed or merged. The earlier request to skip verification applied to the GUI/bulk-speed merge, not this investigation; this investigation ran numerical tests.

## Repository and working state
- Canonical checkout: D:/Minecraft/Modding/1.21/CreateChemE
- Investigation worktree: C:/Users/wormz/.codex/worktrees/mixed-gas-junction/CreateChemE
- Branch: codex/mixed-gas-junction
- Investigation base: 9674bf1 (main's 0.5.0 GUI merge)
- At handoff, all temporary solver edits were restored and the investigation worktree was clean at that base. Recheck before editing; main may advance independently.
- All probes, results and alternative patches: D:/Minecraft/Modding/1.21/CreateChemE/tools/pipe-junction-probe/
- Canonical report: documentation/2026-09-24-mixed-gas-junction/JUNCTION_REVIEW.md
- Proposed work: documentation/2026-09-24-mixed-gas-junction/JUNCTION_FIX_PLAN.md
- These documentation/tools folders are local and git-ignored. Do not assume another clone has them.
- No client was running during the investigation; no client was launched by it.

## Confirmed startup cause
PhysicalFluidTopology.compile seeds a junction from the first boundary's state. In the reproducer that is pure methane at the same pressure as both feeds. Both inlet pressure differences are zero. Estimated inlet flows are approximately 4.44e-16 kg/s, below the 1e-14 transport-direction deadband.

The initial donor/reachability pass consequently omits nitrogen from the junction layout. Newton must converge this inconsistent intermediate layout before the active-set loop can correct the donor support; it runs out of its 20 iterations instead. Reducing the timestep does not help a zero-storage algebraic star.

Trace evidence:
- Initial junction residual: 0.2217806317.
- Initial Newton direction changes log-temperature by approximately 0.995, but log-pressure only 8.38e-9.
- Four-port, 1 kPa pressure difference: terminal residual 5.816801548255438e-6, active-set pass 0.
- Higher pressure difference: terminal residual 1.3351756362421356e-4.

Relevant source locations on 9674bf1:
- runtime/fluid/PhysicalFluidTopology.java:93 — first-boundary seed.
- science/fluid/network/PassiveStepSolver.java — initialMassFlows, startPoint, refineJunctionReachability, restateJunctions, junctionsTurned, donorsTurned.
- PassiveStepSolver.java:270 — Newton settings, 20 iterations.
- PassiveStepSolver.java:679 — transportDirection deadband.
- PassiveStepSolver.java:1487 — difference floors default to 1.
- PassiveStepSolver.java:2017 — junctionInflow normalized by incoming mass.
- science/fluid/solver/PhaseLayout.java — junctionRows/junctionResidual.
- science/fluid/solver/SparseNewton.java — finite differences and line search.

## Experiments and conclusions
Original fixed-boundary reproducer: two methane/nitrogen feeds, 350 K, source pressure 102325 or 150000 Pa, sinks 101325 Pa; 4/5/6 equipment faces, 50 mm bore; 0.05 s interval.

1. Original seed: all six port/pressure combinations fail.
2. Changing just the junction's arbitrary pressure to the boundary midpoint: all six pass mass/component conservation. The pressure-seed matrix (three initial pressure choices) passed 18 variants with the midpoint repair.
3. Changing initial flows without a coherent pressure guess does not suffice.
4. Unequal P/T and swapped source species expose further failures. A bounded pressure-and-enthalpy initializer is more effective, but expanded fixed-boundary cases still have nonconvergences. Also, one probe originally made an invalid assertion that both generators must supply; a one-way generator may legitimately shut off. The saved robustness probe now allows that in unequal-pressure cases.

The most promising prototype is candidate-balanced-seed.patch. It predicts a pressure by scalar continuity bisection, forms an incoming mixture and matches incoming specific enthalpy, bounded by material temperature ranges. It changes only numerical junction guesses, preserving physical inventories and solver tolerances. Its present scope is passive stars with fixed boundaries (including instantaneous PORT rate evaluations). It is not a qualified general network initializer.

## Non-static results — essential to the next step
Two finite 1 m3 methane/nitrogen tanks feed a junction with 2-4 sink outlets at 101325 Pa. Fifty consecutive 0.1 s intervals = 5 seconds, carrying forward the accepted graph.

Equal tanks: 150000 Pa / 350 K each.
Unequal tanks: methane tank 150000 Pa / 350 K; nitrogen tank 90000 Pa / 400 K. The lower-pressure tank first receives methane, then a connection reverses.

| Case | Unmodified solver | Balanced initializer |
|---|---|---|
| 20 mm, 4/5/6 ports, equal | All fail at startup | All three complete 5 s |
| 20 mm, 4/5/6 ports, unequal | All fail at startup | All three complete 5 s; actual reversal |
| 50 mm, 4/5/6 ports, equal | All fail at startup | Stall at 1.6/1.5/1.5 s |
| 50 mm, 4/5/6 ports, unequal | All fail at startup | Start and reverse; stall at 1.5 s |

Independent accepted-interval accounting uses finite inventories plus accumulated boundary transfers, including internal and gravitational energy. Successful 20 mm runs: scaled component error below 1.6e-12, energy error below 1.9e-11. Receiving tank gains approximately 3.05-3.20 mol methane. Rapid runs conserve through the last accepted interval; a failed partial interval is not committed.

At the rapid four-port equal-tank stall, both tanks have approached 101325 Pa. This is a separate near-equilibrium/near-zero-flow issue. Recreating PassiveIntervalSolver each interval does not fix it.

Suspected conditioning issue, NOT a fully established second root cause:
- Incoming fractions and enthalpy are divided by total inflow down to 1e-14 kg/s.
- Flow finite differences have a 1e-6 kg/s minimum perturbation, which can dominate actual near-zero flows.
- Donors/support are frozen during each active-set pass.
- Reducing the flow-column floor extends some four-port runs to 4.5/2.6 s, but does not cure five-/six-port cases. Do not ship that constant change as a fix.

## Reproduction commands
Use an isolated worktree at the base (or deliberately adapt the patches). JDK 21 is installed here:
C:/Program Files/Java/jdk-21.0.11

PowerShell, from the investigation worktree:
~~~powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21.0.11'
./gradlew.bat --no-configuration-cache -I D:/Minecraft/Modding/1.21/CreateChemE/tools/pipe-junction-probe/probe.init.gradle test --tests '*PipeJunctionMixedFeedProbe.fourFiveAndSixEquipmentConnectionsMixAndBalanceWithoutDuplicatingDisplayedThroughput' --console=plain
~~~

For the non-static sweep, replace the selector with '*JunctionTransientProbe'.
Other selectors: '*JunctionSeedProbe', '*JunctionRobustnessProbe', '*JunctionTraceProbe'.
Use -PcoldEachStep=true for the transient retained-workspace control.

To reproduce the balanced candidate:
~~~powershell
git apply --check D:/Minecraft/Modding/1.21/CreateChemE/tools/pipe-junction-probe/candidate-balanced-seed.patch
git apply D:/Minecraft/Modding/1.21/CreateChemE/tools/pipe-junction-probe/candidate-balanced-seed.patch
~~~
Then run the same selectors. Apply only ONE candidate patch to a clean base; each alternative contains its own complete diff, not an incremental layer.

CRITICAL: several exploratory sweep wrappers catch failures so all variants can be printed. BUILD SUCCESSFUL does NOT mean every case passed. Read their PASS/FAIL lines in build/test-results/test/TEST-*.xml, system-out. Production regression tests must assert case success.

## Artifacts
- README.md and probe.init.gradle: instructions and external-source loading.
- PipeJunctionMixedFeedProbe.java: original assertion-based reproducer.
- JunctionTraceProbe.java: one direct Newton step solve.
- JunctionSeedProbe.java: 18 pressure/seed variants.
- JunctionRobustnessProbe.java: 32 source-order / unequal P/T variants.
- JunctionTransientProbe.java: 12 finite-tank cases (two bores, three port counts, equal/unequal initial conditions).
- trace.patch + trace-original.xml/txt: temporary instrumentation; enable -PjunctionTrace=true.
- candidate-flow-seed.patch: unsuccessful flow-only approach.
- candidate-pressure-seed.patch: midpoint prototype; insufficient generally.
- candidate-balanced-seed.patch: strongest initializer prototype, still incomplete.
- candidate-low-flow-difference.patch: balanced initializer plus smaller flow difference floor, still incomplete.
- transient-baseline-two-bores.xml/txt: final matching baseline.
- transient-balanced-two-bores.xml/txt: candidate 5-second/reversal results.
- transient-balanced-warm/cold.xml/txt: earlier 110000 Pa receiving-tank control, not the final 90000 Pa case.
- transient-low-flow-difference.xml/txt: flow-difference sensitivity.
- balanced-static-results/: static probes and adjacent product regressions.
- pressure-seed-comparison.xml, flow-only-candidate.xml, pressure-candidate.xml, midpoint-unequal-feeds.xml: intermediate experiments.

All patches are against 9674bf1. No tracked removal commit exists: experiments were uncommitted and restored; originals remain in git at the base.

## Recommended next actions
1. Capture a compact failing accepted graph immediately before the rapid transient stall. Trace its junction residual families, donor/support changes, finite-difference steps and boundary/velocity-cap modes.
2. Finish a consistent initialization path, preserving valid warm guesses and avoiding arbitrary failed-iterate donor promotion.
3. Design and validate the low-flow/stagnant transition: stable mixture derivatives or conservative flux equations, with explicit retained composition/temperature at genuine zero throughput. Include flow reversal and one-way boundary shutoff.
4. Add real regression tests for the original defect and the complete transient trajectories THROUGH equilibrium, then restart flow. Include component/energy conservation, source-order invariance, vertical branches, multiple junctions, pumps, filters, trace support, phase changes and material-domain limits.
5. Profile the bounded initializer; the prototype uses exploratory sweep/bisection counts and is not performance-qualified.
6. Run unchanged product gates before any production merge. Do not loosen conservation, globally raise Newton limits, or declare the initializer sufficient.

38 adjacent retained tests passed with the balanced prototype: PassiveStepSolverTest, PumpJunctionStartupTest, FilterBlockLineIslandTest, DeadHeadedLineIslandTest, NetworkRegimeTest, PhysicalFluidTopologyTest and PipePresentationTest. Full-suite qualification has not been performed.

## Standing project constraints
Read the current AGENTS.md before continuing. One Gradle invocation per machine; no suite while a dev client runs. Work only in your own worktree. No legacy-world migration/compatibility work. Keep one-off investigation code in canonical tools/, and update this batch's documentation/index. Do not merge experimental patches merely because the exploration wrapper exits successfully.

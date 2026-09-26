# Pipe thermal loss experiment plan

2026-09-24. Worktree: `C:/Users/wormz/.codex/worktrees/pipe-thermal-loss-study/CreateChemE`; branch `codex/pipe-thermal-loss-study`; base `f9d6be1`.

Owner scope: zero pipe inventory; velocity/length heat loss; approximately 10% accuracy; bounded correction analogous to tray pressure drop.

1. Add an isolated scientific heat-exchange kernel and meaningful unit tests on the experiment branch. No runtime/save/UI changes.
2. Compare inlet-cp exponential cooling with a small monotone enthalpy-temperature table that can represent latent heat. Integrate each linear table segment analytically, without a nested flash in a network residual.
3. Use the actual bundled fluid thermodynamics/viscosities and PipeResistance in a zero-holdup steady-line study. Prescribe uniform ambient/U, diameter and small pressure drop; evaluate thermo at a fixed representative pressure. Reference: refined property table, many spatial samples and converged flow/heat coupling. This is a controlled surrogate, not a qualification of PassiveStepSolver or detailed two-phase slip hydraulics.
4. Measure inlet-property hydraulics, one frozen-profile correction, and fully converged reference. Record thermal-law error separately from hydraulic coupling and spatial approximation error. Include heating/cooling, steam condensation, hydrocarbons, length, velocity and insulation-like U changes.
5. Eight independent workers, no concurrent Gradle suites/dev client. Compile first, then execute the campaign in a separate JVM outside Gradle. Serial timing checks after the campaign; report model build cost separately from reuse cost. Capture every failure.
6. Check the reference by refinement and independent RK integration for selected cases; test zero flow, reversal, energy closure and subdivision invariance.
7. Keep reusable kernel/tests on the branch, one-off harness/runner in canonical `tools/pipe-thermal-loss/`, data in `research/2026-09-24-pipe-thermal-loss/`; write results to this batch and update indices. No merge/version bump.

Acceptance target: <=10% heat-loss error against the stated numerical reference (absolute floor near zero), with tight conservation for the chosen heat rate. Physical U calibration is a separate uncertainty. Do not equate self-mismatch with proven accuracy, or steady-line results with whole-network performance.

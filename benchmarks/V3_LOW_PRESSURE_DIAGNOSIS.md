# V3 flash integration and 50–55 kPa investigation

Date: 2026-08-31. Branch: `codex/stage-trace-truncation`.

Follow-up experiment, results and subsequent rollback: [V3_RECOVERY_IMPLEMENTATION_RESULTS.md](V3_RECOVERY_IMPLEMENTATION_RESULTS.md). This document preserves the pre-change diagnosis.

## Conclusions

1. Flash truncation is currently an opt-in **V3 feed-flash operation**, not a replacement for every existing flash. Its reduced phase allocations do not drive the column MESH equations. Reference feed enthalpy, strict phase decisions, and legacy equipment flashes are unchanged.
2. The production 50 kPa request fails on the **60 → 55 kPa pressure rung**; 50 kPa is never attempted. This is no longer the repaired feed-flash precision failure.
3. The confirmed numerical mechanism is an increasingly large Newton direction followed by extremely small global line-search steps. More iterations, fresh Jacobians, coarser differences, and much smaller pressure steps do not by themselves resolve it.
4. The positive-cutoff chain has an additional issue: a frozen stage mask becomes invalid as the separation profile moves. Extending that attempt eventually solves the reduced equations but fails the independent material-defect audit by a wide margin. Ignoring that audit would accept a materially wrong result.
5. These experiments do **not** prove that a fully material-closed physical solution at 55 or 50 kPa is impossible, or that 56.861 kPa is a physical operating limit. They locate a numerical continuation barrier for the tested paths.

## How flash truncation interacts with existing systems

| System | Current interaction |
| --- | --- |
| Runtime configuration | Existing `columnV3.stageTraceCutoffMolPercent` is captured once at admission and converted to a mole fraction. The development config uses `0.0001 mol% = 1e-6`; source default remains zero. No new queue, executor, or separate flash setting. |
| V3 attempt feed flash | Positive cutoff calls the explicit-policy overload. A successful unrestricted reference flash runs first; a reduced result is accepted only after its conservation, equilibrium, shadow-phase, and reference-error checks. A rejected approximation restores the reference. A failed reference still fails. |
| Column equations | Feed energy uses `referenceMolarEnthalpyJoulesPerMol()`. Reduced flash liquid/vapor compositions and vapor fraction are not used to reduce or drive MESH. For the hot crude feed in these tests, the flash reports no phase-trace candidates anyway. |
| Existing stage truncation | Still derives and freezes its own component-per-stage support from the attempt seed. It shares the cutoff domain, **not the flash's phase-support mask**. It remains the column matrix-reduction mechanism. |
| Initializers and phase recovery | Initializer/preconditioner flashes, preferred condenser branch selection, and warm condenser phase correction still use unrestricted flash. |
| Acceptance | Strict condenser phase/split auditing remains unrestricted. Stage truncation retains its separate sink-edge material-defect audit. No tiny vapor phase is waived. |
| Failure recovery | Flash-level rejection returns the full reference. Separately, a failed truncated column chain can retry with optional truncation off. Existing cancellation/deadline ownership is preserved. |
| Legacy/V1 units | Use the separate `science.thermo` flash stack; not changed by this V3 overload. |
| UI/logs | Flash evidence exists in Java outcome diagnostics. The current game log filter emits `stage-trace` events, not `flash-trace`, and UI packets do not expose flash evidence. A positive-cutoff certificate identifies formulation `r4-flash-trace`; cutoff-off retains `r2`. |

Consequently this integration supplies a checked phase-truncation capability and diagnostics, but is not yet a shared-equipment flash replacement or a source of flash-derived MESH speedup. Reference-first validation also means it is not inherently a faster flash.

Relevant code: `V3ColumnCalculator.solveSingleProblem`, `V3TruncationSupport`, `V3TruncatedFlash`, `V3AcceptanceAuditor`, `ProcessSolveServices`, and `ColumnV3Network`.

## Method and reproducibility

- Fixed input: 30 trays, feed tray 24, Tia Juana crude, 2610.7 kmol/h, feed 638.15 K, condenser 323.15 K, reflux ratio 2, reboiler duty 8 MW, 750 Pa/tray pressure drop.
- At 55 kPa top pressure, the feed flash pressure is **72.25 kPa**, not 55 kPa. The specified pressures are inside the package domain.
- The benchmark-only reflection bridge invokes the current production pressure controller, predictor, projection recovery, and conditional phase correction. It selects the preferred branch from the original 50 kPa request before obtaining an accepted 60 kPa prefix.
- Replays use the production prepared problem, frozen support, exact seed, and reference feed enthalpy. Both untruncated production attempts and the truncated terminal attempt match their replayed terminal states and evidence **exactly**.
- All solves were serial. The client was initially running and was not stopped or restarted by the investigation. A final lifecycle check showed it had exited normally at 18:40:30 local time (`runClient` exit code 0). The initial JSON scope text saying it "remains running" was an assumption, not continuous monitoring; this note corrects it, and future probe wording no longer makes that assumption. Background work was not isolated, and these instrumented/repeated solves are **not clean timing benchmarks**; use the earlier cold matrix for timing comparisons.
- The four solve reports contain identical before/after SHA-256 maps of production V3 Java sources. No production code, property values, convergence tolerances, game deadline, or client configuration was changed during this investigation.
- Diagnostic builds succeeded. The prior implementation's 289-test result is documented in `V3_PRECISION_FLASH_TRUNCATION_RESULTS.md`; this investigation did not rerun that whole suite.

## Production failure and counterfactuals

The accepted 60 kPa state has maximum scaled residual `4.973799150320701e-14`. At 55 kPa the branch is already **TWO_PHASE**. The 12-iteration predictor and 24-iteration projected recovery are separate attempts; recovery starts from a projection of the original accepted 60 kPa state, not the failed predictor state.

| Attempt at 55 kPa, cutoff off | Iterations | Terminal maximum scaled residual | Strict success |
| --- | ---: | ---: | --- |
| Production predictor | 12 | 0.1614422986 | No |
| Production projected recovery | 24 | 0.1329379270 | No |
| Same predictor seed, larger budget | 128 | 0.1593565684 | No |
| Same projected seed, larger budget | 128 | 0.1308636867 | No |
| Fresh FINE Jacobian each iteration, no local predictor/reuse | 128 | 0.1593565902 | No |
| Fresh COARSE Jacobian, longer line-search allowance | 128 | 0.1593565839 | No |
| Project failed predictor state instead of accepted 60 kPa state | 128 | 0.2164978191 | No |

The projected-128 non-PC12 maximum is still about `0.09398`. Material and energy residuals also remain far above the solver's `1e-8` gate. The looser independent material/energy audit limits must not be mistaken for Newton convergence.

The final production condenser audit sees a two-phase reference flash, with beta about `0.04373`, but the candidate split differs by about `0.00406`. It is a nonconverged candidate, not evidence of a missing condenser phase. A historical liquid-to-two-phase event carried in diagnostics is not a new transition at 55 kPa.

## What the Newton/line-search measurements show

Fresh production Jacobians and the actual banded LU were recomputed at five saved states. Each was compared with COARSE differences and with **only** its material rows replaced by the existing exact analytic material derivatives.

| Saved state at 55 kPa | Largest raw temperature correction | Largest raw log-flow correction | First accepted FINE step |
| --- | ---: | ---: | ---: |
| Accepted 60 kPa seed, evaluated at 55 kPa | 14.67 K | 4.16 | 1 |
| Predictor after 12 iterations | 1057.38 K | 273.13 | 2^-10 |
| Projected recovery after 24 iterations | 827.00 K | 207.68 | 2^-10 |
| Predictor after 128 iterations | **75860.87 K** | **14310.68** | **2^-19** |
| Projected recovery after 128 iterations | 8232.49 K | 2056.24 | 2^-16 |

These are proposed Newton corrections, not actual physical temperature jumps. At the predictor-128 state, large trials first overflow the finite positive-flow coordinates or leave the temperature property domain. Smaller, domain-valid trials still increase the total squared residual. The first acceptable correction is `2^-19 = 1.9073486328125e-6` of the raw direction, the smallest step in the normal FINE search. This throttles the whole coupled system and gives negligible material/energy progress.

Crucially, the current solver tries its regularized Gauss–Newton/gradient alternatives only when the Newton line search finds **no** acceptable step (or the linear solve fails). A tiny but accepted step therefore prevents those alternatives from being considered. There is no progress-based handoff out of this stalled regime.

The largest directions involve PC10/PC11 trace flows and the moving rectifying-section temperature/composition profile; this is not solely the PC12 residual that happens to dominate the saved failure.

Controls narrow the explanation:

- Band conversion loses **zero entries** in every sampled variant. The `1e-10` structural threshold does not explain this failure by deleting trace diagonals.
- LU reports successful solves with backward errors around `1e-17`. This verifies the supplied linear systems, not forward conditioning or physical solvability.
- A few extremely small material derivatives round to zero in finite differences. Restoring exact material rows barely changes the directions and does not change their accepted halving indices.
- FINE/COARSE differences agree closely. Disabling predictor/reuse gives essentially the same 128-iteration trajectory.
- At node 18 throughout the recorded traces, both phase EOS evaluations keep three well-separated roots. Saved terminal profiles likewise do not demonstrate a root-loss event. Large rejected trial temperatures are a consequence of the huge Newton direction, not a recurrence of the repaired feed-flash root-precision failure.

Thus the demonstrated failure mechanism is a poor nonlinear correction/continuation path through a very sensitive profile, with severe global backtracking. A formal bifurcation/conditioning analysis was not performed; calling the pressure a thermodynamic limit or proving an exact Jacobian singularity would overstate the evidence.

## Smaller pressure steps

| Diagnostic path | Result |
| --- | --- |
| 2.5 kPa steps, original 12/24 budgets | Stops at 57.5 kPa; residuals `2.84e-7` / `2.04e-6`, so neither is accepted. |
| 1 kPa steps, original budgets | Strictly accepted through 57 kPa; fails at 56 kPa. |
| 0.5 kPa steps, 64/128 budgets | Strictly accepted through 57 kPa; fails at 56.5 kPa. |
| 0.125 kPa steps, 64/128 budgets | Strictly accepted through 56.875 kPa; fails at 56.75 kPa. |
| Adaptive step halving, 64 iterations per predictor | Strictly accepted through **56.861328125 kPa**; next attempted 56.859375 kPa fails. Probe stops when the next step would be below 1 Pa. |

The accepted tray-18 temperature rises from `491.80 K` at 57 kPa to `510.12 K` at 56.875 kPa. Other parts of the profile move sharply too, while condenser/bottom conditions are much smoother. Adaptive stepping helps localize and approach the barrier, but is not a sufficient standalone fix in these tests.

The saved adaptive report contains 19 named adaptive trial records. Its original `adaptiveTrials=20` counter also counted the final unsuccessful loop guard; the harness was corrected afterward without changing these numerical records. The final `0.9765625 Pa` step is untried, not an additional failed solve.

## Positive-cutoff chain: why its numerical convergence is still rejected

The actual positive-cutoff chain, observed **before** the outer untruncated retry, also stops at 55 kPa. Its terminal projected attempt removes 130 stage/component points and finishes 24 iterations at residual `0.0384083376`.

Continuing the same prepared attempt with a 128-iteration allowance converges after **104 iterations**, residual `1.5987211554602254e-14`. The strict condenser split audit passes, but the stage material-defect audit fails:

- Sink-edge defect / feed = `0.016185156057667804` (**1.6185%**).
- Allowed budget = `8e-6` (**0.0008%**), about 2023 times smaller.

The support was selected from the seed and frozen for the attempt. As the separation profile shifts, incoming material on previously negligible sink edges is no longer negligible. Solving the masked equations does not repair those omitted balances. The existing audit and untruncated fallback are therefore doing necessary work; this result must not be published as success.

This is a **stage-support** problem, not a feed-flash phase-support failure. The feed flash retains reference enthalpy, and its phase mask is not used by MESH.

## Recommended next implementation, not performed here

1. Add explicit stall detection based on accepted step size and residual progress. Change the correction/recovery path when Newton directions become enormous and global steps collapse; do not just increase 12/24 limits or the game timeout. A scaled trust-region or a material/VLE-preserving correction is a candidate to qualify against these saved states, not yet a proven fix.
2. Retain adaptive pressure subdivision as one recovery tool, with bounded work, but combine it with a different correction/continuation strategy because subdivision alone stalls here. Consider an alternate continuation parameter/path if necessary; do not declare physical infeasibility solely from this path.
3. On a shifted-profile truncation defect, reactivate affected support and rebuild a material-consistent warm seed **between** Newton attempts, or fall back to full support. Do not mutate a mask within a frozen Newton/Jacobian attempt, drop PC12 by name, or relax the defect/condenser audits.
4. Qualify 55 and 50 kPa with full physical auditing, then repeat the 50/70/100/110/150 kPa cold matrix. UI exposure or broader legacy-flash integration is a separate change, not required to diagnose this failure.

## Artifacts and commands

Reports are in `benchmarks/results/v3-low-pressure/`:

- `replay-counterfactuals.json`: production captures, exact replays, larger-budget/stencil/recovery controls, initial smaller steps.
- `smaller-steps.json`: 0.5/0.125 kPa paths with increased budgets.
- `adaptive-steps.json`: bounded adaptive halving.
- `truncated-chain.json`: positive-cutoff chain before fallback, exact replay and extended attempt.
- `linear-directions.json`: five saved states × four Jacobian variants, band/LU evidence and line-search trials.

Benchmark-only sources: `V3PressureContinuationAccess.java`, `V3LowPressureProbe.java`, `V3LowPressureLinearProbe.java`.

Use Java 21; each report path must be new because the probes refuse to overwrite existing results:

```powershell
.\gradlew.bat v3LowPressureProbe --offline --no-daemon --console=plain '-Pv3Report=build/reports/v3-low-pressure-new.json'
.\gradlew.bat v3LowPressureProbe --offline --no-daemon --console=plain '-Pv3SmallSteps=true' '-Pv3Report=build/reports/v3-smaller-steps-new.json'
.\gradlew.bat v3LowPressureProbe --offline --no-daemon --console=plain '-Pv3Adaptive=true' '-Pv3Report=build/reports/v3-adaptive-new.json'
.\gradlew.bat v3LowPressureProbe --offline --no-daemon --console=plain '-Pv3TruncatedProbe=true' '-Pv3Report=build/reports/v3-truncated-new.json'
.\gradlew.bat v3LowPressureLinearProbe --offline --no-daemon --console=plain '-Pv3Report=build/reports/v3-linear-new.json'
```

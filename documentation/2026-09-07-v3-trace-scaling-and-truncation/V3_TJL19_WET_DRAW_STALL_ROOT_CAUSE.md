# Root cause of the TJL19 wet three-pumparound three-draw stall

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc` at `4a42c0a`. Probes under `build/pkgcmp/`:
`NewtonStallProbe.java` (dumps the calculator's final state, replays the requested-rung Newton from it with both
Jacobian policies, compares the two Newton directions, lists near-null Jacobian rows and columns, and replicates the
banded LU's scaling and pivoting to name the failing pivot), `CutoffProbe.java` (same input through the public
calculator with a stage-trace cutoff). No production code was changed.

## Chain of cause

1. Java 21 (the Gradle test JVM) and Java 25 differ in the last bits of `Math.exp`/`log`. The steam-rung residual
   already differs in the seventh digit, so the ramp reaches the requested rung from slightly different seeds.
2. Both seeds are physically converged to 0.0001 K and 0.001 mol/s. They differ only in the profiles of trace
   components, which Newton never corrects because their material balances are scaled by the component's feed
   flow (45.8 mol/s for TJL_PC09): any imbalance below 4.6e-7 mol/s reads as converged at the 1e-8 tolerance.
3. On Java 21 the TJL_PC09 liquid profile in the pumparound zone carries a spike: 1.2e-37 mol/s on tray 10,
   2.3e-11 on tray 11, 2.4e-25 on tray 12. Its material residual is 5e-13 scaled, invisible. On Java 25 the same
   profile is monotone (1.7e-40, 4.0e-39, 3.0e-35). No other component differs between the two states in this way.
4. `V3BandedPivotedSolver` equilibrates every row by its own maximum before pivoting. The two tiny rows for PC09
   on trays 11 and 12 are both dominated by the same flow (tray-11 liquid, as outflow and as inflow), so after
   equilibration they become the same unit vector. The Jacobian is exactly rank deficient by one. The replica
   LU finds a pivot of 0.0 at the PC09 vapour unknown of tray 10 and a final pivot of 1.3e-20; the production LU
   returns `SINGULAR` for the full finite-difference Jacobian and for the local-block Jacobian alike (the two
   Jacobians agree to 7e-9 on the band, so Jacobian accuracy is not involved).
5. With `SINGULAR`, `V3SimultaneousColumnSolver` falls back to damped Gauss-Newton on the normal equations. The
   damped step is dominated by the null direction, Armijo backtracking shrinks it, and the useful correction to
   the dominant residual (TJL_PC04 material balance on tray 1, 3.8e-8) shrinks with it: 1.7% per iteration for
   32 iterations, from 3.79e-8 to 2.22e-8, then `MAX_ITERATIONS` and `NONCONVERGENCE`.

Replaying from the Java 21 state on Java 25 reproduces the crawl exactly; replaying from the Java 25 state on
Java 21 converges immediately with a verified final correction. The JVM only decides which trace profile the ramp
leaves behind; the mechanism is in the solver.

## Evidence

| Observation | Java 21 state | Java 25 state |
|---|---|---|
| Max scaled residual | 3.79e-8 | 1.47e-13 |
| Temperatures, phase flows | identical to 1e-4 K, 1e-3 mol/s | |
| Rows with max entry below 1e-13 (raw Jacobian) | 155 of 1228 | 157 of 1228 |
| Rank deficiency after equilibration (replica LU) | 1 (pivot 0.0 at PC09 vapour, tray 10) | 0 |
| Production LU on both Jacobians | `SINGULAR` | success, backward error 1.8e-16 |
| Local vs full FD direction | not computable | agree to 2.5e-9 |
| PC09 liquid, trays 10/11/12 (mol/s) | 1.2e-37 / 2.3e-11 / 2.4e-25 | 1.7e-40 / 4.0e-39 / 3.0e-35 |
| Newton restart, either policy, 32 iterations | 3.79e-8 to 2.22e-8, budget exhausted | converged at iteration 0 |
| Stage-trace cutoff 1e-6 or 1e-4 (public calculator) | truncated attempt fails, fallback repeats the stall | |

The pivot trace of the replica shows the dependency directly: for the tray-11 PC09 liquid column, the tray-11 and
tray-12 material rows both offer a coefficient of 1.000000 on Java 21; on Java 25 the tray-12 row does not appear.

## What the root cause is, and is not

- It is a mismatch between two definitions of "small". The residual scaling declares a trace-component balance
  satisfied when its absolute imbalance is below 1e-8 of the component's feed; the LU's row equilibration gives
  that same balance full weight. A trace profile that is inconsistent but invisible therefore produces a singular
  linear system out of a converged state.
- It is not a Jacobian accuracy problem, not the side draws, not the pumparound term, and not a dry-tray or
  condensation cap. The heavy-draw wall recorded earlier ("trace-pair null directions, certificates die below
  tolerance") is the same mechanism.
- It is not fixed by the existing stage-trace truncation: the truncated attempt of this case fails on its own
  mass-defect audit and the fallback returns to the untruncated stall.
- The `SINGULAR` outcome is silent at the calculator level: the events report only "iteration budget exhausted".

## Candidate remedies, none applied

1. Make trace balances observable: scale each material row by the larger of a floor relative to the feed and
   the component's local throughput on that tray, so a spike produces a visible residual that Newton corrects.
   Changes the tolerance semantics and every pinned residual; needs a formulation revision.
2. Detect the equilibrated dependency at the LU and report it as a typed linear failure with the offending
   equation pair, instead of the anonymous `MAX_ITERATIONS` crawl. This does not converge the case but turns
   30 s of crawling into a diagnosis.
3. Before each ramp rung, smooth trace profiles (log-linear interpolation between trays where a component is
   below a threshold of its feed) so spikes cannot be carried across rungs. Cheap and local to the ramp, but it
   edits the seed rather than the formulation.
4. Treat components below the threshold on a tray as fixed rather than as unknowns (the truncation idea), but
   with a support rule based on the spike criterion rather than a mole-fraction cutoff, and without the current
   mass-defect fallback resetting the attempt.

Remedies 1 and 4 address the cause; 2 and 3 address the symptom. The choice is a design decision for the solver
owner.

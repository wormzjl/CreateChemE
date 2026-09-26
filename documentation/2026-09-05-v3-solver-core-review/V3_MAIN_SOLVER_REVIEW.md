# Main-branch solver review

Reviewed 2026-09-05. The shared checkout is now **`main` at `bdfeff1`**. The previous `codex/v3-cdu-ramp-hardening` branch remains at `33ae3d8`. The checkout was clean before switching, and no production source was edited during either review.

`main` is a reasonable base for a simpler redesign. It removes much of the later orchestration policy, but the underlying trace and numerical defects already exist here. The later branch has 1,347 added and 164 removed production-source lines relative to main, a net increase of 1,183; this count excludes its substantial test/probe additions.

| Later-branch machinery | Present on main? |
| --- | --- |
| 95% draw-withdrawal rejection and bare-column starvation screen | No |
| Fixture-derived pressure/loading bands selecting attach order | No |
| Second attach-order retry with a reset rung budget | No |
| Adaptive feature-ramp controller | No |
| Terminal local-block/full-Newton retry wrapper | No |
| Dry-tray transition support and related audit/telemetry additions | No |
| Fixed stage/pressure continuation, several preconditioners, damped recovery, truncation fallback | Yes |

The principal findings on **main itself** are:

1. **Tiny local flows still dominate the convergence requirements.** The map requires positive flows and uses logarithmic coordinates; the final maximum log-flow step remains `1e-8` for every retained flow. Truncation and convergence-evidence files are identical between these branches. Switching to main therefore does not remove the mechanism identified in the previous review. The PC11/W5b numbers in that earlier report were measured on the later branch; they were not remeasured on main.

   Sources: [flow coordinates](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3DryMeshCoordinateMap.java:72), [convergence requirement](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ConvergenceEvidence.java:16).

2. **The incorrect final-certificate claim is reproducible on main.** The terminal fallback forwards the regularized normal system's backward error as evidence for the original Newton system. A freshly compiled manufactured case with constant `1e-9` VLE residuals and zero derivatives returns `Converged`, `verified final Newton correction`, and zero linear backward error, even though those original linear equations have no solution. This is a certificate-contract defect; the physical residual remains within the configured tolerance. A raw candidate decode exception also exits the whole certificate method, bypassing the normal fallback.

   Source: [terminal certificate](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:362).

3. **Disconnected component groups are reproducible on main.** The support builder checks immediate neighboring inflow rather than reachability from the feed. The probe retains a component at condenser/tray 1, deletes it at tray 2, and feeds tray 3. Summed material equations require `V0 + L0/(1+R) + L1 = 0`, which has no exact positive-flow solution. The existing unit test explicitly expects this retained group.

   Source: [support pruning](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3TruncationSupport.java:311).

4. **The banded numerical costs and coefficient-dropping inconsistency remain.** Main still copies/scans the whole dense Jacobian and repeatedly scans the stage layout to recover a band. Entries at or below `1e-10` do not determine bandwidth, so necessary small entries can be discarded before LU rescales the system. LU's column scaling performs two all-row scans per column even on diagonal matrices. The LU and normal-products files are identical between the branches, so the earlier isolated linear-algebra findings apply unchanged.

   Sources: [band conversion](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SimultaneousColumnSolver.java:428), [LU scaling](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/linalg/V3BandedPivotedSolver.java:118).

5. **Main already repeats expensive work and underreports it.** Feed is reflashed for each attempt, failed intermediate attempts receive a full audit, failed truncation can repeat the whole cold chain, and final diagnostics still hardcode three work counters to zero while reporting only terminal Newton iterations. The mandatory stage ladder and 150 kPa anchor can fail before the requested operating point is attempted.

   Sources: [repeated feed flash](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:576), [unconditional audit](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:607), [diagnostics](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:1141).

There are also baseline weaknesses that the later branch addressed:

- A failed partial feature-ramp attempt skips the remaining intermediate fractions and jumps to the full request using the failed state. The final request receives 32 iterations, versus 40 for partial rungs. This is a questionable recovery policy, though it does ensure the full loading is attempted. See [fixed ramp](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:731).
- Wet pressure continuation removes draws and attaches steam before draws afterward. The later branch's measured ordering changes improved the historical cold screen. Main's historical result was 7/36, versus 26/36 for the later retained branch; this review did not rerun that screen. A smaller codebase alone is not evidence of better convergence.
- Long composed diagnostic paths can throw before publishing a converged result. `V3SolverDiagnostics` limits the path to 128 characters, but main passes the unbounded path and catches the resulting exception as `INVALID_INPUT` for the default lane. The branch's bounded-path fix is independent of the numerical recovery stack and is a sensible small fix to retain selectively. See [publication path](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:255) and [diagnostic validation](D:/Minecraft/Modding/1.21/CreateChemE/src/main/java/com/wormzjl/createcheme/science/column/v3/V3SolverDiagnostics.java:28).

Recommended work from main: first establish truthful request-wide work counts and regression cases, correct support reachability and structural matrix storage, remove repeated allocations/scans, then test one consistent treatment of trace flows and convergence. Keep conservation and phase audits. Evaluate only narrowly measured continuation changes afterward; do not import the entire later recovery stack. Making the original-system certificate stricter in isolation could reduce reported successes and does not solve the trace formulation issue.

Validation: current main science sources and focused tests were freshly compiled into a separate `build/main-solver-audit/classes` directory with Java `--release 21`. **33 focused JUnit tests passed**, and both diagnostic probes reproduced their defects on main. These tests are not a cold-convergence guarantee: one existing real-crude test deliberately permits bounded nonconvergence and did return that outcome. No full Gradle suite or new cold DOE is claimed; the previously encountered offline plugin-resolution limitation remains. [Fresh main results](D:/Minecraft/Modding/1.21/CreateChemE/build/main-solver-audit/results.txt).

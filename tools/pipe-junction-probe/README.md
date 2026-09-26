# Short multi-inlet junction probe
Batch: documentation/2026-09-24-column-gui. In progress since 2026-09-24.

Reproduces a known Newton convergence failure for physically compiled short junctions with methane and nitrogen feeds. This is investigative material, not a product gate. Created from uncommitted investigation; no tracked-code removal commit applies.

To reproduce: with no dev client or other Gradle invocation running, copy PipeJunctionMixedFeedProbe.java into an isolated worktree's src/test/java/com/wormzjl/createcheme/runtime/fluid/, then run gradlew.bat test --tests '*PipeJunctionMixedFeedProbe' --console=plain with JDK 21. Remove only that copied file after the run. No world is involved.

Four ports fail first: two feeds at 102325 Pa and two outlets at 101325 Pa, 350 K, default 1 m / 0.05 m pipe geometry, 0.05 s interval. Error: Substep refinement exhausted: Newton iteration limit at residual 5.816801548255438E-6; active-set pass=0. Raising feed pressure to 150000 Pa also failed, residual 1.3351756362421356E-4.

The retained product test instead uses nitrogen for both feeds: 4, 5 and 6 physical ports pass mass/component conservation and automatic presentation throughput checks. This does not establish arbitrary mixed-feed robustness. Numerical solver behavior is unchanged by the GUI work.

## 2026-09-24 follow-up investigation
Batch documentation/2026-09-24-mixed-gas-junction, base commit 9674bf1. No prototype was merged. See JUNCTION_REVIEW.md for the complete findings and limitations.

Probe sources are compiled externally, without copying into src:
    gradlew.bat --no-configuration-cache -I D:/Minecraft/Modding/1.21/CreateChemE/tools/pipe-junction-probe/probe.init.gradle test --tests '*JunctionTransientProbe' --console=plain
Use JDK 21 and no active dev client or other Gradle invocation. Other selectors: PipeJunctionMixedFeedProbe (original assertion-based test), JunctionTraceProbe (one direct step), JunctionSeedProbe (18 pressure variants), JunctionRobustnessProbe (32 unequal/order variants).
IMPORTANT: Seed, robustness and transient sweep wrappers catch case failures to print all outcomes. Read PASS/FAIL lines in system-out; Gradle success alone does not mean the cases passed. Production regressions must use assertions rather than this exploration wrapper.

All candidate patches are alternatives against base 9674bf1; apply one at a time in an isolated worktree with git apply --check first:
- trace.patch: temporary Newton instrumentation; enable with -PjunctionTrace=true.
- candidate-flow-seed.patch: rejected flow-only initialization experiment.
- candidate-pressure-seed.patch: simple midpoint-pressure seed; original cases pass, unequal feeds expose limits.
- candidate-balanced-seed.patch: bounded pressure/enthalpy initializer; improves startup and slower finite-tank runs, but rapid discharge near equilibrium still fails. Not production-ready.
- candidate-low-flow-difference.patch: balanced initializer plus smaller flow-column difference floor; partial improvement, not a cure.

Use -PcoldEachStep=true for a transient control that discards retained solver state after each accepted interval.
XML/text files preserve case outcomes; balanced-static-results holds neighboring product-test results. All instrumentation and prototype source changes were uncommitted and removed from the investigation worktree. Recover the unchanged source with git show 9674bf1:<path>; no tracked-removal commit exists.

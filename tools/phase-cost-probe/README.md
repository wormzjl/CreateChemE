# phase-cost-probe

**Purpose.** The cost probe of one TP, PH or UV call of the equilibrium engine (`FluidTpEquilibrium`): the
methane/nitrogen binary at three states, pure nitrogen, the 20-component network basis dry and wet, and (rows added in
P3) the Newton finish, free water, and PH/UV of the binary and of the 20-component basis; warm-up then measured calls,
printed as a table (microseconds per call, p50/p95, kernel evaluations). A measurement, not a gate. Its numbers are in
`documentation/2026-09-24-coolprop-low-temperature/P2_PHASE_CONTRACTS.md` (section 7) and
`P3_EQUILIBRIUM_ENGINE.md` (section 8 for WP6a, W.5 for WP6b); the P3 budget "TP flash p95 <= 50 us at 21 components"
(`P3_PILOT_ENGINE_PLAN.md` section 10) was measured with it (46.2 us, WP6a).

**Batch.** `2026-09-24-coolprop-low-temperature` (P2, rows added in P3 WP6a and WP6b). Added in `cbaa791`, extended in
`f5ffcf8` and `f116a78`; detached at the batch's close-out (P3 WP11) under the owner's rule for test classes and tools
after a batch.

**Removed from the tracked tree by commit `8a10bfd`** (path
`src/test/java/com/wormzjl/createcheme/science/thermo/phase/FluidTpEquilibriumCostTest.java`). The stored copy
`src/test/java/.../FluidTpEquilibriumCostTest.java` in this folder is byte for byte
`git show 8a10bfd^:src/test/java/com/wormzjl/createcheme/science/thermo/phase/FluidTpEquilibriumCostTest.java`, and
`reattach.patch` re-adds it on top of `8a10bfd` (checked with `git apply --check` there).

## Re-attach and run

From the root of a checkout at `8a10bfd` or later (Git Bash):

    git apply tools/phase-cost-probe/reattach.patch            # or copy src/ over the checkout
    CREATECHEME_PHASE_COST=1 JAVA_OPTS=-Xshare:off ./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.phase.FluidTpEquilibriumCostTest' --offline

The table is printed on the test's standard output. Without the environment variable the class is skipped. It uses the
tracked test fixtures `PhaseTestSupport` and `FluidTestSupport`. Run it on a quiet machine (no game, at least 20 GB
free, no other Gradle), one invocation at a time under `build/gradle.lock`. Remove the file again (`git apply -R`)
before committing.

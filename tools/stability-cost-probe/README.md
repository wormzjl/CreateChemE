# stability-cost-probe

**Purpose.** The cost probe of one tangent-plane stability call (`TangentPlaneStability`, P1): 2, 4 and 20 (+ water,
the 21-component network basis) components, warm-up then measured calls, printed as a table (microseconds per call,
kernel evaluations, the verdict it asserts). A measurement, not a gate. Its numbers are in
`documentation/2026-09-24-coolprop-low-temperature/P1_STABILITY_TEST.md` (section 5; the P3 budget "per-node
stability <= 15 us at 21 components" of `P3_PILOT_ENGINE_PLAN.md` section 10 cites them).

**Batch.** `2026-09-24-coolprop-low-temperature` (unified multiphase thermo plan, P1 item 3, gate G1). Added in
`4500e58`; detached at the batch's close-out (P3 WP11) under the owner's rule for test classes and tools after a batch.

**Removed from the tracked tree by commit `8a10bfd`** (path
`src/test/java/com/wormzjl/createcheme/science/thermo/TangentPlaneStabilityCostTest.java`). The stored copy
`src/test/java/.../TangentPlaneStabilityCostTest.java` in this folder is byte for byte
`git show 8a10bfd^:src/test/java/com/wormzjl/createcheme/science/thermo/TangentPlaneStabilityCostTest.java`, and
`reattach.patch` re-adds it on top of `8a10bfd` (checked with `git apply --check` there).

## Re-attach and run

From the root of a checkout at `8a10bfd` or later (Git Bash):

    git apply tools/stability-cost-probe/reattach.patch        # or copy src/ over the checkout
    CREATECHEME_STABILITY_COST=1 JAVA_OPTS=-Xshare:off ./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.TangentPlaneStabilityCostTest' --offline

The table is printed on the test's standard output (Gradle's test report, or `--info`). Without the environment variable
the class is skipped. It uses the gate test `TangentPlaneStabilityTest`'s kernels (`kernel(...)`, `crudeWithNitrogen()`),
which stay tracked. Run it on a quiet machine (no game, at least 20 GB free, no other Gradle), one invocation at a time
under `build/gradle.lock`. Remove the file again (`git apply -R`) before committing.

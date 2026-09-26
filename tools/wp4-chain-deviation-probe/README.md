# wp4-chain-deviation-probe

**Purpose.** The P3 WP4 measurement probe `DirectLiquidChainDeviationProbe`: it replays chain-100 exactly as
`FluidSolverRegressionTest` does (the harness's cosine chain, one 5 s interval, the shipped trace cutoff), writes the
per-node deviation of the direct liquid path's end state against the pinned reference
`src/test/resources/fluid/regression/chain-100.json` to `build/reports/fluid/chain-100-direct-liquid-deviation.csv`, and
applies the plan's first-order check (`P3_PILOT_ENGINE_PLAN.md` section 2.5, step 3): each node's hydrocarbon-liquid
volume deviation must be explained within 10 % of the deviation by `(kappa_EOS - k)(2 MPa - P)` plus the anchor term
plus the node's change of hydrocarbon inventory, with `k = 1e-9 1/Pa` the retired global compressibility (it uses the
tracked test class `fluid/support/LegacyLiquidPath`, the retired path rebuilt for the comparisons, which stays because
the gate `DirectLiquidContinuityTest` uses it). Its results are the chain-100 deviation table and first-order check of
`documentation/2026-09-24-coolprop-low-temperature/P3_DIRECT_LIQUID_PATH.md` section 6 (residual 4.50 to 4.51 % of the
deviation on every node); the CSV of that run is in
`research/2026-09-24-coolprop-low-temperature/p3-direct-liquid-path/`.

**Batch.** `2026-09-24-coolprop-low-temperature`, stage P3, WP4. Added in `959ea0d`; detached at the batch's close-out
(P3 WP11) under the owner's rule for test classes and tools after a batch.

**Removed from the tracked tree by commit `8a10bfd`** (path
`src/test/java/com/wormzjl/createcheme/science/fluid/network/DirectLiquidChainDeviationProbe.java`). The stored copy
`src/test/java/.../DirectLiquidChainDeviationProbe.java` in this folder is byte for byte
`git show 8a10bfd^:src/test/java/com/wormzjl/createcheme/science/fluid/network/DirectLiquidChainDeviationProbe.java`,
and `reattach.patch` re-adds it on top of `8a10bfd` (checked with `git apply --check` there).

## Re-attach and run

The probe compares against whatever `chain-100.json` is checked out. Since WP11 re-captured that reference on the P3
engine (`ac6ebc1`), the probe at HEAD sees zero deviation and its first-order check (which expects the WP4 deviation)
fails. To reproduce WP4's table, put the pre-P3 reference back for the run only:

    git apply tools/wp4-chain-deviation-probe/reattach.patch
    git show 3c84036:src/test/resources/fluid/regression/chain-100.json > src/test/resources/fluid/regression/chain-100.json
    CREATECHEME_WP4_CHAIN_PROBE=1 JAVA_OPTS=-Xshare:off ./gradlew fluidScienceTest --tests '*DirectLiquidChainDeviationProbe' --offline
    git checkout -- src/test/resources/fluid/regression/chain-100.json
    git apply -R tools/wp4-chain-deviation-probe/reattach.patch

Without the environment variable the class is skipped. One Gradle invocation at a time under `build/gradle.lock`, no
dev client running.

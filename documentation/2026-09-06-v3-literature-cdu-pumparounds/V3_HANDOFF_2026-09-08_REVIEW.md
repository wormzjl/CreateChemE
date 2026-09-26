# V3 handoff review — 2026-09-08

Reviewed `f4e600ae3bfda4d5b503210faec801ed8416e9c4` against the handoff's integration baseline (`6d629db^`). Main and the handoff worktree have the same source revision. Production source files were not changed.

The review concentrated on the new pumparound contract and admission checks, continuation and wet-tray acceptance, transport/persistence changes, the literature preset, and the calculator's Heat/result presentation. This is not independent thermodynamic qualification against DWSIM or the literature.

The handoff's maintainer decisions are respected: dry-tray dew-point violations remain warnings, side strippers remain out of scope, and the documented excessive-draw specifications are not reclassified as solver defects.

## Findings

### 1. [P2] Cooling admission ignores authored positive stage heat

Location: `src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java:208–220`; the same issue appears in `requireCoolingBelowBaseCondenserDuty`, lines 1306–1318.

The input contract permits positive duties, and overlapping pumparounds add their signed duties on each tray. Both global admission checks nevertheless compare **gross negative duties** against heat available from a column that omits all stage heaters. This can reject a valid input before Newton is attempted.

Reproduction against the compiled current code:

1. Start with `ColumnCalculatorV3BlockEntity.pilotPresetInput()`; the public calculator returns `Success`.
2. Add `(return=8, draw=10, duty=-1e9 W, split=RETURN_TRAY)` and `(return=8, draw=11, duty=+1e9 W, split=RETURN_TRAY)`. These are distinct valid pairs.
3. Input validation passes. Expanded stage heat is exactly zero on every node, so the MESH equations are identical to the successful baseline.
4. The public calculator instead returns `INFEASIBLE_SPECIFICATION`: cooling of 1000 MW exceeds 98.31 MW available.

Account for positive stage heat in the energy admission bounds, or derive the checks from the signed expanded heat profile. The same correction is needed for the heat-free condenser-duty comparison, otherwise fixing only static admission leaves a second false rejection.

This is separate from the handoff's known positive-duty continuation stalls: the demonstrated pair has no net heat perturbation at all.

### 2. [P2] A dry-tray warning can mask a wet-tray saturation failure

Location: `src/main/java/com/wormzjl/createcheme/science/column/v3/V3AcceptanceAuditor.java:338–368`, especially the `worstNode` severity decision at lines 360–368.

`waterDewPoint` combines dry saturation ratios and wet normalized saturation errors into one maximum. It rejects only when the node supplying that maximum is wet. If any wet tray fails but a dry tray has a larger ratio, the method returns a passing warning and loses the wet-tray failure. This violates the retained contract that wet-set inconsistency is still a rejection.

Reproduction uses the existing manufactured three-tray fixture, without changing production code:

- Tray 1 is wet, with positive free-water flow and saturation ratio `1.0015` at closure `0.001`; its normalized error is `1.5000000000000568`, above the limit of one.
- Dry tray 2 is set to 390 K and has saturation ratio `2.314993566013753`.
- The actual private audit check returns `passed=true`, naming only tray 2's warning.

Track wet validity separately from the worst dry advisory, and fail if any wet stage is inconsistent. A larger advisory must not change a different check's severity. Add a mixed wet/dry regression, rather than testing the two conditions separately.

Scope of evidence: this reproduces an independent-auditor defect, not an end-to-end falsely accepted public solve. Newton still checks the water-saturation residual; the auditor is meant to provide an independent second check.

### 3. [P2] Failed reruns clear the Heat tab's stale-result indication

Location: `src/main/java/com/wormzjl/createcheme/client/gui/screens/inventory/ColumnCalculatorV3Screen.java:304–310`; consumers at lines 698 and 831–849.

Every state update unconditionally resets `draftEditedSinceState=false`. The block entity deliberately retains the previous accepted `displayResult` while a new request is calculating and after failure. Consequently, after a successful run A, changing cooler duties and submitting a failing run B clears the "Input edited since run" pill as soon as the server acknowledges B. The old duty ledger remains visible, while the tray-map labels switch to B's authored MW values because `renderCoolerBars` treats the cleared flag as proof that the ledger matches.

This produces a Heat page whose summary belongs to A and whose cooler labels belong to B, with the stale-result pill hidden. Reopening the failed calculator has the same problem. Streams already labels retained results explicitly; Heat needs equivalent provenance handling.

Keep the local edit flag separate from whether the retained certificate belongs to the current input. At minimum, mark retained results during `CALCULATING`/`FAILED` and prevent current-draft MW labels from being presented as matching them. The independent input/result revision counters must not simply be compared numerically.

Evidence: traced `tryBegin`/`finishOperation`, network state delivery, `applyServerState`, and both Heat render consumers. This specific failed-rerun scenario was not exercised interactively.

## Verification

- Ran `./gradlew.bat --offline test --no-daemon` on main: **455 tests, 0 failures, 0 errors, 0 skipped**, build successful in 7m 43s. Counts were independently summed from the generated JUnit XML files.
- Added diagnostic-only files under ignored `build/`, not production sources or the permanent test suite: `review.init.gradle`, `review-probe/ReviewProbe.java`.
- Ran `./gradlew.bat --offline --no-daemon --no-configuration-cache --init-script build/review.init.gradle reviewProbe`: build successful. It reproduces findings 1 and 2 and verifies that the heat-free baseline succeeds.
- Probe output is copied to `documentation/V3_HANDOFF_2026-09-08_REVIEW_PROBES.log`.
- The existing suite passing does not cover these newly reproduced cases.

## Installed Minecraft MCP verification

**Successful:** used the installed `minecraft-mod-mcp` 0.3.0 package through a local MCP stdio client, opened the existing calculator, switched tabs, and pressed Run V3. The bridge reported `ColumnCalculatorV3Screen` after the block interaction.

Environment findings:

- No Minecraft MCP tools are registered directly in this Codex session. The existing untracked `.mcp.json` is in `.claude/worktrees/v3-low-pressure-gaps-989c00/`.
- The bridge JAR is also in that worktree's `run/mods`; main's `run/mods` was empty. The initial main-checkout dev client therefore had no bridge listener.
- Unrestricted bridge discovery initially selected the user's other Minecraft instance. No input actions were sent to that instance.
- Launching the handoff client with default settings produced `HTTP server failed: Address already in use: bind`. The installed JAR supports `MC_MCP_PORT`; the review client was started with `MC_MCP_PORT=9875` and the local bridge helper restricted discovery to that exact port. The user subsequently closed the earlier clients and the isolated review client was reopened.
- Helpers are `build/review-mcp.mjs` and `build/review-mcp-server.mjs`; installed package files and Codex configuration were not modified.

Opened `New World (1)` from the handoff worktree, used the existing calculator at `-272 68 -110`, switched to Heat and submitted the literature preset. Convergence reported:

- `Status: SUCCESS`
- `Success with warning: tray 1 at 83.1 C is below the water dew point 86.9 C ...`
- 13 accepted audit checks, 3 final-attempt Newton iterations
- Maximum scaled residual `1.693e-14`
- 6 published streams; 3 coolers, total stage heat `-41.93 MW`

The game log independently records the successful 40-tray, feed-stage-37 request. This confirms the documented default dew-point warning behavior; it is not a review finding.

Screenshot: `documentation/gui/v3-handoff-review-2026-09-08-convergence.png`. The test client is at 854×480 with GUI scale 2, so the current fixed-layout panel overflows vertically and some long text extends beyond the visible area. This capture establishes UI access and outcome, not full layout qualification.

No fixes, commits, or pushes were made. Review documents and diagnostic artifacts are in gitignored directories.

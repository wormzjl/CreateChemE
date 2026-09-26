# V3 handoff fixes — independent verification review

Date: 2026-09-08. Reviewed `f4e600a..e8d8937eacc1d58692f1a4f289f0633ec25b4cd1`, including all ten changed production/test files and the implementation note `V3_HANDOFF_REVIEW_FIXES.md`.

**No new actionable findings in these three fix commits. All three findings from the prior review are resolved.**

## Disposition

| Original finding | Fix | Review result |
| --- | --- | --- |
| Gross cooling admission omits stage heaters | `36dcb3e` | Both the static energy bound and the heat-free condenser-duty bound credit authored positive heat. Existing cooling-only behavior is preserved. The cancelling pair now succeeds; tests also retain rejection of excessive net cooling and exercise the second bound independently. |
| Dry warning masks a wet saturation failure | `ab14be5` | Wet failures and dry advisories are ranked separately, with wet rejection taking precedence. The original mixed-case probe now fails on tray 1; the companion regression preserves a passing dry warning when the wet tray is consistent. |
| Failed rerun hides retained-result provenance | `e8d8937` | Both Heat consumers use the new provenance helper. A result matches only on an unedited `SUCCESS` state. `CALCULATING`, `FAILED`, and persisted `STALE` results keep a notice and do not enable current-input MW map labels. Independent revision counters are not compared. |

The conservative handling of `STALE` is consistent with the block entity's persisted presentation-only contract. This does not claim that a persisted certificate necessarily has different inputs; it requires a fresh successful run before treating it as current.

## Independent verification

- `./gradlew.bat --offline test --no-daemon`: **465 tests, 0 failures, 0 errors, 0 skipped**, successful in 7m 59s. The test task executed; production/test compilation used cached outputs for the current sources. Counts were summed independently from generated JUnit XML.
- Replayed the original ignored `build/review-probe/ReviewProbe.java`, without changing its scientific reproduction cases, using `./gradlew.bat --offline --no-daemon --no-configuration-cache --init-script build/review.init.gradle reviewProbe`.
- Cancelling pair: maximum absolute expanded stage heat `0.0 W`; public calculation now returns `SUCCESS`, and the heat-free baseline still returns `Success`.
- Mixed audit: wet normalized error `1.5000000000000568`; check now returns `passed=false`, naming tray 1's unsatisfied saturation line instead of downgrading it to tray 2's warning.
- `git diff --check f4e600a HEAD` passed.
- No production edits, commits, or pushes were made during this review.

Probe evidence: `V3_HANDOFF_FIXES_VERIFICATION_PROBES.log`. The probe emitted a nonfatal Log4j file-rotation message because the parallel test process held `logs/debug.log`; the diagnostic program and Gradle task completed successfully.

## Live calculator verification

Launched the MCP-enabled handoff worktree at the same reviewed commit on port 9875. Used Minecraft Quick Play to open `New World (1)` and interacted with the existing calculator at `-272 68 -110` through the installed Minecraft MCP stdio bridge.

1. On reopening the persisted result, Heat showed **Retained result from an earlier run**.
2. Ran the restored literature preset; the server recorded `SUCCESS` at input revision 3.
3. Temporarily changed the first cooler from 12.84 MW to 200 MW through the block's NBT, preserving the other input fields and the previous display result. NBT entry was used because the installed bridge does not reliably focus the calculator's text boxes.
4. Pressed Run V3. The server recorded `INFEASIBLE_SPECIFICATION` at input revision 4: 229.1 MW total cooling exceeds the 156.4 MW available budget.
5. After that terminal state update, the Heat page still showed **Retained result from an earlier run**, the 200 MW current input, and the retained -41.93 MW total duty. Thus the mismatch remains explicitly labeled after the client clears its local edit flag.
6. Restored the first cooler to 12.84 MW and reran the original preset. The server recorded `SUCCESS` at input revision 5, then the test world was saved and closed.

Screenshots: `gui/v3-fixes-review-stale.png` and `gui/v3-fixes-review-failed-rerun.png`. Server evidence is in the worktree's `build/review-fixes-quickplay.log`.

Limits of this live check: text entry into EditBox widgets was not verified, and the bridge's capture/window sizing limitations required a narrow panel, where the tray map is hidden. The map-label fix was verified by source inspection and the provenance regressions; its wider-layout appearance was not independently captured. The check covers the failed server update and persisted-result notice, not a complete GUI layout qualification.

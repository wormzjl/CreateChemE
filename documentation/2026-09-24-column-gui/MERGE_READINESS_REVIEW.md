# GUI batch merge readiness review

In progress since 2026-09-24. Branch codex/column-gui; candidate 43bf7c5. Not merged to main.

## Integration and release
- Main 4b21bd0 (0.4.1) incorporated by 7cc7d12. The only conflict was CHANGELOG.md; preserved both the GUI Unreleased entry and the 0.4.1 attribution-rule release. No product conflicts.
- Candidate mod_version is 0.5.0. Batch stays under Unreleased as required until the actual main merge. Product jar metadata is 0.5.0; contains 9 column and 8 mixture JSON resources including indexes, and no MCP helper classes.
- At actual merge, recheck main has not advanced, make the merge with 0.5.0 plus the dated release heading replacing this batch's Unreleased entry in the same merge, and mark the canonical batch index Implemented with the merge date/hash. Main is unchanged by this preparation.
- Main numerical pipe interval/step solvers are unchanged. Column changes implement terminal-inclusive public numbering, feed/steam at the reboiler and 2-tray pots; packet/save changes require a fresh world, without migration.

## Review and cleanup
Reviewed the branch diff and previous batch evidence with Java immutability, validation and ownership checks. Reviewed bounded preset reads/exports, retained codec validation, relative-composition unit conversions, request/subscription delivery lifecycle, pot/tray-pressure tests and multi-connection presentation. No unresolved merge blocker found in this preparation's scope.

All added src/test classes remain ordinary test gates. Changed FluidPresentationGameTests remain in the documented GameTest source set. Product inspection/draft/preset classes remain because the GUI uses them. No test include/exclude, fork or verification settings changed.

Detached batch-only MCP DragInputMixin, ScreenshotMixin and 2560x1440 run configuration under canonical tools/column-gui-mcp/. Source archive exactly matches git show 7cc7d12:<path>; removal commit 43bf7c5. Reattachment patch passes git apply --check on the cleaned branch. Local client.init.gradle restores verification helpers externally without modifying tracked sources. Base MCP tooling predates this batch and remains tracked. This cleanup supersedes the earlier reviews' decision to retain batch-only MCP additions.

Mixed-feed investigation remains in tools/pipe-junction-probe/ with reproduction instructions; no tracked source was detached for that one-off. Dev test worlds remain ignored for owner testing and are not fixtures. Review documents and screenshots are canonical under documentation/2026-09-24-column-gui/.

## Validation
Final command: gradlew.bat check compileFluidGameTestJava compileMcpCompatJava --console=plain. BUILD SUCCESSFUL in 3m 52s: 1,093 tests, zero failures/errors/skips. GameTest and base MCP sources compiled. Client was stopped; no overlapping Gradle invocation. Only source change during the final gate run was removal of a trailing blank line, recompiled successfully at client launch.

External client.init.gradle launch passed with no tracked edits, CreateChemE version 0.5.0, 2560 x 1440, GUI scale 3. Reopened the same-format fluid-6 Pipe inspection GUI test world; four-connection water circuit showed flowing normally and the complete new overview. MCP framebuffer helper works from its detached source. Client left open; MCP control returned to user. Screenshot: screenshots/merge-ready-client.png.

Merge preparation complete at 43bf7c5. Main remains 4b21bd0 and is an ancestor of this clean branch. Actual merge and release-heading/index finalization remain intentionally unperformed.

Main-to-candidate git diff --check passes. Build/test gate definitions equal main exactly. The production jar was inspected as above. Complete GameTest server campaign is not rerun in this preparation; GameTest source compilation and earlier fresh-world MCP checks cover the GUI paths.

## Known non-blocking limitations
- Four-, five-, six-port same-gas junction gates pass mass/component conservation. Live four-way water split verified. Different gas feeds entering a short junction can fail Newton convergence; this existing numerical solver behavior is unchanged. GUI reports its status, and the reproducer is archived.
- Native OS cursor visibility through focus loss/regain was not fully verified because the earlier native-app approval timed out. Bridge interaction and GUI cursor recovery paths were exercised.
- Pressure gradient is endpoint pressure difference divided by connected path length, including elevation. Bulk speed is interval-averaged, not a phase-slip model.
- Earlier full GUI evidence uses fresh worlds appropriate to each protocol. Current fluid-6 world is Pipe inspection GUI; never load older-format worlds for compatibility testing.

## Final merge
Implemented 2026-09-24: main 9674bf1, version 0.5.0. Final source follow-up 9817ecc changes bulk speed to the last five online seconds; owner explicitly skipped further verification and requested merge. Prior passing tests above do not cover this last change. See BULK_SPEED_REVIEW.md. Existing client remains open on its earlier build and requires restart.

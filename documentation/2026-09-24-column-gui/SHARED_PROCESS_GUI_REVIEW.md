# Shared process GUI review

In progress since 2026-09-24 on codex/column-gui; implemented at b443c74, not merged. This follow-up supersedes the independent percentage/rate switches in COLUMN_GUI_SCALE3_REVIEW.md.

## Changes

- Column composition basis now changes relative amounts, normalized percentages, component rates and their unit tags together. Product totals and rate headings also follow the basis. Unit changes preserve the physical mixture and valid results.
- Explicit Load mixture buttons are available on Overview and Composition. Add connection opens full-text Add liquid draw / Add pumparound (PA) / Add steam injection actions, with localized hints and capacity checks.
- Labels read Condenser T and Reboiler duty with sufficient field width.
- Both process screens recover a normal, ungrabbed cursor when they are active and focused. Inspection of the pinned MCP bridge found its exitMcpControlMode explicitly sets mouseGrabbed=true and GLFW_CURSOR_DISABLED even with an open GUI; the recovery handles that control hand-off without reopening.
- Fluid generator: operating controls beside an explicit Composition page, load server/client JSON mixtures, start an empty custom mixture, searchable component selection, individual remove buttons, relative mole/mass amounts and normalized percentages, draggable lists. Compatible JSON files use the existing client mixture directory and explicit refresh.
- Fluid devices share a Celsius-default C/K switch. Numeric drafts preserve untouched physical precision despite concise labels. Pipe diameter remains visibly 0.05000 m, not a rounded 0.1 m.
- Tank and pipe contents share phase tables and list only positive component amounts. Tank quantities use kmol/kg; pipe rates use kmol/h or kg/h for the selected direction and completed interval. Empty phases have an explicit empty state.
- Initial fluid overview chooses a present phase where a state sample is available. Tank properties sit at the top of the information panel. Component tooltips render the name and identity on separate lines.
- Solids editing and filter recovery remain available. Bulk molar-flow summaries use total bulk mass, including solids; solids do not contribute fluid moles.
- Fixed player pipe roughness: 0.000045 m (0.045 mm), shared by placement and server edit handling. No roughness input is exposed. Incoming roughness values do not alter player pipe/filter geometry.

## Shared implementation

client/gui/common contains RelativeComposition, TemperatureUnit, NumericDraft, ProcessScroll and ProcessUi (palette, table cells, number formatting, cursor recovery). Column V3CompositionDraft wraps the common composition model to retain column identity. Fluid and column screens share table/palette/units and the existing JSON preset reader, rather than duplicating composition math or preset filesystem logic.

Fluid static menu payloads now carry a bounded positive molecular-weight list aligned to the server component axis. The client does not substitute its local material catalogue. Protocol bumped from fluid-4 to fluid-5; missing or malformed weights fail validation. Live payload cadence and engine-owned input events remain unchanged. No save migration or compatibility gate was added.

## Verification

- Full Gradle test suite: 1,078 tests, zero failures/errors/skips; BUILD SUCCESSFUL in 4m 1s.
- Final focused shared-presentation, fluid-packet, column-editor and preset checks: 37 tests, zero failures/errors/skips; BUILD SUCCESSFUL in 4s.
- The final bulk-mass denominator correction was subsequently compiled in runMcpClient; the full suite was not repeated for that small display formula.
- git diff --check passed. No tests overlapped a dev client or another Gradle invocation.
- GUI checks through the langyo Minecraft MCP bridge at 2560 x 1440, GUI scale 3, in the fresh creative world Shared process GUI.
- Loaded Tia Juana into the generator; changed molar/mass and C/K. Started an empty mixture, searched Nitrogen and Water, entered relative mass amounts 2 and 3, and applied successfully. The next engine presentation showed 40% / 60%.
- Created steady generator-to-pipe-to-sink flow. The selected water phase displayed only Water and a component rate of 96.6 kmol/h; pipe roughness was absent. Tank vapor table displayed only Nitrogen; switching units showed 1.1 kg and 298.2 K.
- Column basis switch changed the component Flow heading to kg/h and values together; methane feed showed 212.8 kg/h. Standard reference solve accepted. Product total and component column both used kg/h (overhead total 5897.4 kg/h).
- The fresh world was saved and reopened in the same protocol build. The final client is left open for owner testing.
- Control-mode exit was exercised with the GUI still open. An attempted native window-focus check timed out awaiting Computer Use app approval, so actual focus-loss/regain and OS cursor visibility remain a manual verification limitation. The underlying bridge behavior was confirmed from its installed bytecode; no approval bypass was attempted.

## Evidence

screenshots/shared-generator-presets.png, shared-generator-search.png, shared-generator-custom.png, shared-generator-applied.png, shared-generator-kelvin.png, shared-pipe-flow2.png, shared-tank-mass-k.png, shared-column.png, shared-column-connections.png, shared-column-molar.png, shared-column-mass.png and shared-column-products-mass.png record the functional checks. Final layout captures use the shared-final- prefix. shared-gui-test-summary.txt records test totals.

## Cleanup

New shared classes and the preset reuse are product code; ProcessPresentationTest and FluidPacketCodecTest are normal test-task gates. No new one-off source harness, launch configuration or instrumentation was added. The in-game test devices exist only in the isolated ignored dev world, for owner testing. No detached tool folder was created.

Test-world devices at y=-60, z=9: generator x=2, pipe x=3, sink x=4, tank x=6, column x=8.

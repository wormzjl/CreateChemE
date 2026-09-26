# Scale-3 column GUI revision review

Follow-up: SHARED_PROCESS_GUI_REVIEW.md supersedes independent percentage/rate switches and documents shared generator/tank/pipe presentation and cursor recovery.

In progress since 2026-09-24 on codex/column-gui; implemented and verified at bb7a276, not merged. This revision supersedes the layout, linked templates, public tray convention and format versions described in COLUMN_GUI_INTERACTIVE_REVIEW.md.

## Delivered behavior

| Request | Result |
| --- | --- |
| Scale 3 / compact inputs | Uses the full window height. At 2560 x 1440, scale 3, the standard 42-total-tray drawing fits without scrolling; compact input boxes reduce unused spacing. Smaller windows retain draggable scrolling. |
| Separate information columns | Overview has the drawing, Solver Info and Tray Info in three columns. Solver warnings include wet trays, excessive dP and liquid depletion. |
| PA naming and placement | PA labels use PA1 etc. and sit at the middle of their yellow connection braces. |
| Public tray numbering | Condenser = tray 1, reboiler = last tray. The former 40-interior-tray reference has 42 total trays with unchanged physics. GUI, JSON, streams and human-facing diagnostics use this convention; numerical node indexes remain internal. |
| Minimum two trays | Two total trays are a supported pot. Feed and steam can enter the reboiler. Pot residuals, Jacobian, initialization, continuation and inspection codecs support zero interior trays. |
| Terminal dP | Condenser and reboiler have no tray dP, displayed as a dash. A pot has zero column dP. Interior hydraulics provide the column pressure loss. |
| Steam connections | Add, select, edit and remove steam on the drawing. Bottom steam is an injection at the last tray. Duplicate locations are prevented; a pot has only one eligible injection location. |
| Independent presets | Column and mixture have independent Templates and Save buttons. Loading either leaves the other unchanged. Eight bundled column JSON presets and seven bundled mixture presets ship in the jar. |
| Custom JSON | Client-local CreatChemE/columnpreset and CreatChemE/mixturepreset directories beneath the game directory; explicit browser-open/Refresh reads, Open folder, validated UTF-8 saves that preserve existing files. See CLIENT_PRESET_FORMAT.md. |
| Hover and localization | Interactive drawing targets have hover indication and localized explanations. Authored GUI labels/tooltips use translation keys; component data and custom preset names retain their authored names. |
| Percentage switch | Normalized composition percentage changes with the molar/mass basis. Small positive relative amounts retain visible significant figures. |
| Component rates | Composition and product tables include independently switchable kmol/h or kg/h component rates. |
| Profiles dP | Results > Profiles cycles Temperature, Pressure, dP, Traffic. There is no separate dP tab. |
| C/K placement | Global temperature toggle sits in the footer; changing units alone does not discard valid results. |
| Edit invalidation | Physical edits discard old result data and update the drawing. Engine-owned input/presentation scheduling remains at 100 online ticks. |

## Verification

- Full Gradle suite: 1,071 tests, zero failures/errors/skips, 4m 6s. This covered the scientific, numbering and preset implementation before the final codec minimum and small UI refinements.
- Final codec/editor/preset/layout/pot checks: 49 tests, zero failures/errors/skips, 7s.
- Final steam-location/editor checks: 21 tests, zero failures/errors/skips, 3s.
- git diff --check passed. Tests ran with the dev client closed and only one Gradle invocation at a time.
- Live langyo Minecraft MCP verification used a 2560 x 1440 window at GUI scale 3.
- Fresh world Column GUI scale 3 final: dry two-tray pot accepted; both terminal inspections showed no tray dP and column dP was zero. Adding steam at tray 2 solved successfully.
- Saved and reloaded that same-format fresh world: wet pot and accepted results persisted. Clicking steam opened its controls; removal worked.
- Restored and solved the standard 42-tray Tia Juana case: accepted, column dP 22.0 kPa, worst flood 67.5%. Drawing and separate information columns fit the screen.
- Verified the dP plot inside Profiles and product component rates in kg/h. Earlier live checks verified both composition bases, JSON exports and independent template browsers.
- Edited a saved custom JSON name while the browser was open: the visible library retained its snapshot until explicit Refresh, then showed the edited name.
- The final client remains open on Overview with the accepted 42-tray case and selected tray 21 for owner testing.

## Corrections found during verification

A pot initially solved but the inspection decoder still required four nodes. The bound now follows MIN_STAGE_COUNT + 2, with regression coverage for two and three nodes and a successful live pot save/reload. The feed click target was narrowed so coincident feed/steam locations can select steam correctly. Adding steam now chooses an unused valid location and disables when none remains.

Block data format is 12, wire schema 15 and protocol 11. Verification used a fresh world after these changes; no migration or legacy-world test was introduced.

## Evidence and batch cleanup

- screenshots/scale3-final-overview.png
- screenshots/scale3-final-pot.png
- screenshots/scale3-final-steam-reloaded.png
- screenshots/scale3-final-dp-profile.png
- screenshots/scale3-final-product-rates.png
- screenshots/scale3-after-refresh.png
- screenshots/scale3-column-library.png
- scale3-test-summary.txt

No one-off probe, campaign source or analysis harness was added to tracked code in this revision. New test classes are ordinary Gradle test-suite gates; the preset library/resources are product code. Earlier batch MCP compatibility and runMcpClient tooling remain part of the documented GUI verification workflow. No tools were detached in this revision. Two QA-only custom JSON exports were removed from the development game directory after verification; the custom directories remain available.

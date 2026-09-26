# Component dropdown and text-input review

In progress since 2026-09-24 on codex/column-gui, implemented at c8754ec; not merged.

## Delivered

Focused native EditBox controls now receive keys before inventory-screen shortcuts. Printable keys, including E and numeric hotbar keys, and editing shortcuts are consumed by the editor. Plain fields retain normal Escape and Tab behavior. This applies to both column and fluid-device screens, including scalar, composition, filename and solid input fields.

A reusable ComponentDropdown replaces both full-page component pickers. It is an inline field with an arrow and a bounded overlay directly underneath, so the existing composition remains visible. It offers up to six visible suggestions with wheel/drag scrolling, keyboard Up/Down and Enter selection, mouse selection and click-away dismissal. Escape dismisses an expanded dropdown before normal screen handling. Suggestions use the server's available IDs and localized labels, excluding components already added.

ComponentSearch ranks exact and prefix matches ahead of partial names, subsequences and bounded spelling errors, including transposed letters. Matching ignores case/diacritics and normalizes punctuation; multiword tokens can appear in any order. It never invents a component. Typing updates this widget's matches without rebuilding the parent screen. Parent rebuilds preserve the query, text focus and whether the list is expanded.

## Verification

- 28 targeted tests passed, zero failures/errors/skips, BUILD SUCCESSFUL in 3s. Includes new ComponentSearchTest and TextInputPriorityTest plus shared presentation and column editor checks.
- Native EditBox regression tests cover focused E, numeric keys, Enter, Escape, Tab, unfocused fields and screens without a focused editor.
- Matching tests cover exact ordering, missing letters, adjacent transpositions, abbreviations, localized names, stable IDs, punctuation, token ordering, absent matches and immutable catalogue input.
- Minecraft MCP live checks at 2560 x 1440, scale 3, in Shared process GUI (same existing formats; no format change in this follow-up).
- Generator: pressing E with the search field focused kept FluidDeviceScreen open. Pressing E after choosing Overview closed it, confirming actual shortcut dispatch outside text input.
- Generator: ntgn suggested Nitrogen; Enter added a visible Nitrogen row. The original server configuration was restored by closing without Apply.
- Column: E with search and relative-amount editors focused left ColumnCalculatorV3Screen open. methne ranked Methane before Ethane; Down then Enter added Ethane. A relative amount accepted text 1e-3 without closing the GUI.
- Dropdown arrow and overlay placement were visually checked in both screens. The accepted server column configuration was restored by reopening without Solve.
- Bridge note: use plain key names E, ENTER and DOWN with press_key. Its documented key.keyboard.* forms did not deliver the intended events in this installed version. Enter through type_text with press_enter also worked. The bridge's close_screen is a force-close operation and is not evidence of single-Escape semantics.
- git diff --check passed. Tests ran with no dev client running and no other Gradle invocation. The client remains open for owner testing.

Evidence: screenshots/dropdown-column-fuzzy.png, dropdown-column-selected.png, dropdown-column-exponent.png, dropdown-generator-fuzzy.png, dropdown-generator-selected.png, dropdown-arrow-open.png; dropdown-test-summary.txt.

## Cleanup and scope

Only shared GUI product code and ordinary Gradle-gated regression tests were added. No one-off harness, source instrumentation, new runtime switches or detached tooling. No engine timing, packet, material or save-format changes.

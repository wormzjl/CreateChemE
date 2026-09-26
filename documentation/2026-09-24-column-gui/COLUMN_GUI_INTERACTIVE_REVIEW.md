# Interactive column GUI revision review

Follow-up: COLUMN_GUI_SCALE3_REVIEW.md supersedes this revision for layout, independent JSON templates, total tray numbering, two-tray pots and format versions.

In progress since 2026-09-24 on codex/column-gui; implemented at abe151e and verified, not merged. This revision supersedes the first screen organization and retained-result behavior in COLUMN_GUI_IMPLEMENTATION_REVIEW.md.

## Delivered behavior

| Request | Implementation |
| --- | --- |
| 1. Remove tray spinner | Click physical trays; selected tray is outlined and its data appears in the info panel. |
| 2. Composition tables | Tray x/y mol% and product mol%/mass% tables; editable feed table. |
| 3. Drag bars | Canvas and long tables/diagnostics use draggable scrollbars and wheel support. |
| 4. Drawing-attached controls | Feed flow, temperature and tray below the feed; condenser controls beside the head; reboiler duty beside the bottom; draw/PA fields beside the selected connection elevation. |
| 5. Presets/custom compositions | Custom starts empty. Search supported components, add/remove individually, enter relative molar/mass amounts. Positive totals are normalized and need not equal 100. Presets explicitly offer composition only or composition plus column. |
| 6. Draws/PAs | Add at the selected tray, configure or remove on the drawing. Existing engine bounds: 3 draws and 4 PAs; Add disables at capacity. |
| 7. C/K | Global temperature input/display toggle, including profiles; unit-only changes retain result validity. |
| 8. Remove tray-dP input | No nominal dP field. Supplied ordinary templates use diameter-driven hydraulics; scientific input format unchanged. |
| 9. Rounded vessel | Rounded head and bottom caps. |
| 10. Duplicate Profiles shortcut | Removed from Overview; profiles remain in Results. |
| 11. Solver info | Right rail shows wet trays, accepted column dP, flooding/pressure warnings, and relevant failed-attempt liquid/side-draw evidence. Full failure summaries are split into bounded chunks. |
| 12. Live topology / clear results | Geometry reads draft fields independently of complete validation. Physical edits clear colors, tables and profiles immediately. Server solve start also clears old results, including on failed reruns. |
| 13. Click cues | Hover outlines/tooltips on trays, feed and connections; native widget focus cues. |
| 14. Naming | Number of trays, Reboiler duty, Reflux ratio. |
| 15. One decimal | Physical defaults show one decimal; discrete tray numbers stay integers. Untouched values keep full underlying precision. |
| 16. dP inspection | Tray inspector includes inter-tray dP; Results / Column dP plots actual accepted pressure differences. Tray 1 and terminal nodes have no separate interval. |
| 17. Design skill | frontend-design applied: slate process canvas, teal feed/selection, amber heat/warnings, native font and restrained hierarchy. |

The later larger-screen request is included: no fixed GUI-height cap; tray spacing uses available height with a six-pixel minimum target. At 1280 x 900, GUI scale 1, every supported tray count (2–64) fits with its controls and no canvas scrollbar. Small viewports retain scrolling and a drawing/info toggle. runMcpClient now starts at 1280 x 900.

## Verification

- Full ordinary Gradle suite: **1,060 tests, zero failures/errors/skips**, BUILD SUCCESSFUL in 3m 37s.
- Final focused run after responsive layout and complete failure-text transport: **40 tests, zero failures/errors/skips** (all V3 codecs, editor draft and responsive layout); MCP compatibility code compiled.
- New tests cover relative normalization, mass/mole invariance, empty/negative/nonfinite values, selecting mass basis before adding rows, exact unchanged values, Kelvin conversion, preset scopes, incomplete input geometry, connection add/remove, all 2–64 tray layouts in large viewports, and bounded catalogue/full-state wire round trips.
- Fresh world **Column GUI interactive**, created for wire schema 14 / protocol 10. No old-world testing or migration.
- Reference solve accepted: 40 trays, feed tray 37, 736.744575843224 mol/s, 638.15 K; actual accepted column dP displayed as 22.0 kPa. Accepted result survived same-format save/reload.
- Live verified: feed opens Composition; blank custom list; search Methane; add/edit relative amount 27; mass toggle; preset confirmation/full load; composition-only load retaining 64 trays and the edited draw; direct tray selection; old results removed on edit; draw/PA add/remove; Kelvin rendering; 64 trays without scrolling; product tables and dP chart.
- Compact scale 2: real bridge drags moved the drawing and long feed table to their final rows. Feed and steam fields no longer overlap.
- Failure-evidence publication was reviewed and covered by bounded transport checks; no new failure-producing solver campaign was run.
- git diff --check passed. Client intentionally remains open, GUI scale 1, with accepted reference case for owner testing. Do not run Gradle tests while it is active.

## Fixed during verification

1. Unbalanced nested scissor state caused the black/flashing GUI reported by the owner. Matching scissor pop fixed it; large and compact screenshots are stable.
2. Feed-temperature and tray-steam fields overlapped near the sump. Steam controls now follow the lower of column bottom and feed inputs.
3. Scientific assumption text hid selected-tray data. Overview now shows actionable pressure/wet/flood warnings; full evidence remains in Diagnostics.
4. MCP 0.3.0 blocked drag dispatch on the render thread and served cropped cached 854 x 480 screenshots. Reusable mcpCompat mixins deliver actual screen drag callbacks and capture the actual Minecraft framebuffer. This tooling remains exclusive to runMcpClient and never enters the production mod.

## Boundaries and cleanup

Custom components stay within the selected registered property package, using server-authored molecular weights. No cross-package interaction parameters or thermodynamic data are invented. Holland remains a fixed benchmark: full-template loading is available; composition-only loading explains why matching settings are required.

Inputs remain local until Solve. Server actions and presentation retain the engine-owned 100-online-tick schedule. No process calculation was added to rendering, packet handlers or the tick loop.

Retained work consists of product models, gate-run tests and reusable runMcpClient tooling. No campaign scripts or one-off instrumentation were created, so no new detached tools folder is required. Earlier detached helpers remain documented in the first implementation review.

## Evidence

- screenshots/interactive-large.png: complete accepted overview, tray table, no canvas scrollbar.
- screenshots/interactive-64-kelvin.png: 64 trays, Kelvin, live draw fields, no canvas scrollbar.
- screenshots/interactive-pa.png: added PA with adjacent duty/draw/return controls.
- screenshots/interactive-composition-only.png: preset composition retains 64-tray configuration.
- screenshots/interactive-search.png: blank custom workflow and component search.
- screenshots/interactive-preset-choice.png: explicit preset scope choice.
- screenshots/interactive-compact-drag.png: compact drawing after dragging.
- screenshots/interactive-table-drag.png: final component rows after dragging.
- screenshots/interactive-products.png and interactive-dp.png: accepted result tables/chart.
- interactive-test-summary.txt: exact test totals.

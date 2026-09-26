# Interactive column GUI follow-up plan

In progress since 2026-09-24 on codex/column-gui. Owner's 17-item revision supersedes the first screen organization.

Design: a process schematic, not a form beside a small diagram. Large rounded column, directly selected trays, feed fields below its inlet; condenser/reboiler controls beside equipment; selectable and removable draws/PAs with adjacent controls. Right rail contains solver facts and selected-tray data. Larger windows use their full height, spreading trays without unnecessary canvas scrolling. The MCP client opens at 1280 x 900. Small windows use a canvas with draggable horizontal/vertical scrollbars and a switchable info rail. No tray spinner or duplicate Profiles button.

Tokens: slate canvas #202A31, equipment panel #2B3D48, border #536C7A, text #EAF1F5, feed/selection teal #79D6CE, heat/warnings amber #FFCD82; errors use the native red treatment. Native Minecraft font: restrained uppercase section headers, ordinary labels, aligned numeric table columns. Signature: controls attached to the actual process connections, with hover outlines and tooltips.

Composition: blank custom lists; searchable add-component dropdown; remove rows; relative molar/mass weights normalized only for solving. Unit switch converts using server-supplied molecular weights. Preset selection offers composition only versus full operating case. Components remain inside a registered property package; no new thermodynamic model is invented by the UI. All result compositions become tables.

Temperature C/K selector is a view/input-unit preference, not a physical edit. Default numeric values show one decimal (discrete tray indices stay integers), with exact unchanged values retained. Remove authored tray dP from the UI; ordinary templates use diameter-based hydraulics; the editor no longer exposes nominal tray dP. Preset benchmark science stays unchanged.

Edits immediately redraw draft geometry and discard displayed result data, including colors/profiles. Fresh results return only after an accepted solve. Server delivery remains engine-scheduled. Extend the bounded editor catalogue over a new wire version for template inputs/MW; fresh-world GUI verification, no migration.

Key solver rail: actual wet trays, actual accepted pressure drop and hydraulic/pressure warnings, relevant side-draw depletion failure evidence. Tray inspector includes dP to the tray above; a Results pressure-drop page charts the column intervals. No invented hydraulic threshold.

Acceptance: all 17 owner items; pure composition mass/mole invariance, normalization, empty/negative/nonfinite rejection, precise temperature conversion, preset scopes, dynamic connection add/remove, scroll mapping; bounded catalogue wire tests; ordinary suite; then fresh-world MCP checks of actual typing, drag bars, blank/custom/preset feed, connected controls, live draft invalidation, hover/tray selection and dP. One Gradle invocation, client closed before tests.

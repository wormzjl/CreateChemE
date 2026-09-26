# Shared process GUI revision plan

In progress since 2026-09-24, codex/column-gui; extends the same GUI batch.

Design: native Minecraft instrument panel for players defining and inspecting chemical streams. Keep the established slate background #202A31, panel #28363F, rule #536C7A, pale text #EAF1F5, aqua selection #79D6CE and amber warning #FFCD82. Minecraft's native font handles headings and controls; aligned numeric cells distinguish measurements. The signature remains the directly editable column drawing. Fluid screens use an operating-condition rail next to a roomy composition table, rather than duplicating the column illustration.

Column: one basis choice updates composition percentages, component rates and their tags; explicit Load mixture action; full-text connection menu in the toolbar; condenser/reboiler labels; recover cursor visibility when focus/control returns.

Common: extract relative composition, Celsius-default temperature conversion, table presentation and cursor recovery. Generator starts custom compositions empty, supports searchable add/remove and relative mole/mass entries plus explicit preset loading. Tank/pipe contents share phase tables and omit absent components. Keep solids editing/recovery, debug routes and engine-owned presentation.

Fluid static payload supplies server molecular weights for correct mass conversion; validate and bump protocol, use a fresh world. Pipe roughness remains 0.000045 m and is not an editable setting. Test shared conversions and wire bounds, then verify actual screens with the MCP bridge and leave client open.

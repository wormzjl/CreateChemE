# Scale-3 column editor and independent client presets

In progress since 2026-09-24; follow-up on codex/column-gui.

Owner confirmed total tray count includes both ends. Two total trays is a pot with no interior trays. Terminal stages have no dP. Public numbering is node index + 1 across fields, JSON, plots, stream names and presentation; numerical arrays stay zero-indexed. Test pot conservation and solver Jacobian/balances.

Design uses scale 3 on the owner's wide display: compact process drawing with input boxes around 60–100 logical pixels, solver information in its own column, tray information in a second column. PA labels sit at the midpoint of their loops; steam injections become selectable/addable/removable ports. Temperature units move to the footer beside related unit controls. Slate #202A31, panels #28363F, borders #536C7A, text #EAF1F5, process teal #79D6CE, heat/steam amber #FFCD82. Native font, concise labels and explanatory translated hover text.

Column and mixture templates become independent JSON resources bundled in the jar. Client-owned custom files live in gameDirectory/CreatChemE/columnpreset and gameDirectory/CreatChemE/mixturepreset, matching the requested spelling. Only explicit preset-open/refresh reads files. Saving is explicit, bounded, named, and never overwrites a bundled resource. No filesystem path is sent to the server.

Composition normalization columns follow molar/mass basis; composition and product tables gain independently switchable component flow rates. Pressure drop follows pressure inside Profiles. All authored GUI labels/tooltips are language keys. Test resource/file parsing, independent application, exact round trips, normalization/rate conversion and malformed custom files; full suite with client closed; fresh-world bridge verification at GUI scale 3, then leave client open.

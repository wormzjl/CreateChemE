# Client column and mixture presets

The libraries are independent. A column template never selects or changes a mixture; a mixture template never changes feed rate, temperature, tray count, duties or connections.

## Location and refresh

Bundled defaults are JSON resources inside the mod jar:
- assets/createcheme/columnpreset/*.json
- assets/createcheme/mixturepreset/*.json

Custom files are client-local, relative to the active Minecraft game directory:
- CreatChemE/columnpreset/*.json
- CreatChemE/mixturepreset/*.json

The spelling CreatChemE is intentional, matching the owner's request. In the usual installation the parent is .minecraft; an isolated development client uses its own game directory.

Open the corresponding Templates browser to create/read that directory. Open folder opens it for editing. Reads happen only on opening the browser or pressing Refresh. Saving does not rescan. Save writes a new UTF-8 JSON file; a numbered suffix preserves existing files. Files are bounded to 64 KiB; each custom library lists at most 256 files. Invalid files are skipped and identified in the GUI.

## Numbering

All JSON tray numbers are public physical tray numbers. Tray 1 is the condenser; the last tray is the reboiler. The total includes both terminals and ranges from 2 (a pot) to 66. The first interior tray is 2. Feed and steam may enter trays 2 through the last tray. Draws and PA endpoints must be interior trays. Steam at the last tray is bottom steam.

Condenser and reboiler have no pressure drop. Inter-tray dP is solved from the interior section; a pot has zero column dP. JSON does not author a nominal tray dP.

## Mixture schema (version 1)

Required keys: schema_version, kind (mixture), name, translation_key (empty for a custom name), package, basis (mole or mass), components.

Each components row has id and amount. Amounts are finite non-negative relative values; the total must be positive but need not be 100. Only selected rows are saved. The package and component IDs must be supported by the connected server's material catalogue. Molecular weights remain server-owned; they are not loaded from custom JSON.

Example:
{
  "schema_version": 1,
  "kind": "mixture",
  "name": "Light blend",
  "translation_key": "",
  "package": "createcheme:tjl20_methane",
  "basis": "mass",
  "components": [
    {"id": "Methane", "amount": 2.0},
    {"id": "Ethane", "amount": 3.0}
  ]
}

## Column schema (version 1)

Required scalar keys:
- schema_version: 1; kind: column; name; translation_key
- tray_count; feed_tray
- feed_flow_kmol_h; feed_temperature_kelvin
- top_temperature_kelvin (tray 1)
- reboiler_duty_mw (last tray); reflux_ratio
- top_pressure_bar (absolute); diameter_m

Required lists (empty is valid):
- draws: rows with tray and flow_kmol_h; at most 3
- steam: rows with tray, flow_kmol_h and temperature_kelvin; at most 2 distinct locations
- pumparounds: rows with draw_tray, return_tray, cooling_mw and split (UNIFORM or RETURN_TRAY); at most 4

A column file has no package, assay or component fractions. Loading it retains the selected mixture and scales that mixture to the authored feed flow. Positive PA cooling_mw means heat removed.

The bundled pot.json is a two-tray operating example with no side connections. The fixed Holland benchmark is included as separate column and mixture files; select both independently to reconstruct it. The fixed solver continues to reject changed benchmark inputs. Client unit/relative conversion roundoff is canonicalized only when every choice and physical value matches that benchmark within 16 ULPs.

## GUI units and saved units

The GUI C/K switch is a display/input preference. JSON temperatures always use kelvin. The molar/mass basis switch changes both normalized percentages and component flow rates, including their unit labels. Flow results are kmol/h or kg/h. The normalized percentage column follows the selected composition basis.

The solver keeps zero-based numerical node indexes internally. Only public physical tray numbers appear in the GUI, custom JSON, plots, stream labels and human-facing tray diagnostics.

The fluid generator uses the shared relative-composition model and can load server presets or client JSON mixtures whose component IDs are all available on the server network axis. Its mass conversion uses server-provided molecular weights. The fluid screen defaults to Celsius.

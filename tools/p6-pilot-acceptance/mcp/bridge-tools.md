# minecraft-mod-mcp 0.3.0: in-game tools and what actually works here

Parameter names come from the npm package's tool schemas (`dist/cli.js`). The "status" column is
what CreateChemE sessions observed in the NeoForge 1.21.1 dev client between 2026-09-07 and
2026-09-24. "compat" means it only works with the `createcheme_mcp_compat` mixins loaded, i.e.
through the `runMcpClient` Gradle run.

Every tool is one HTTP call: `POST http://127.0.0.1:9876/api/cmd` with `{"cmd":"<name>", ...params}`.
`scripts/bridge.js <name> '<json>'` sends exactly that.

## Sensing (no control mode)

| tool | params | status |
|---|---|---|
| `ping` | | works |
| `screenshot` | | works, returns base64 with a coordinate grid; too large to read as a tool result, use the file variant |
| `screenshot_to_file` | `path` (absolute; defaults to `screenshots/vtty/`) | works; PNG has alpha 0 on every pixel, flatten it |
| `enumerate_widgets` | | works; full widget tree with GUI-scaled coordinates and messages |
| `get_screen_buttons` | | works; ids for `click_button_id`, index order for `click_button_index` |
| `get_minecraft_status` | | works |
| `get_player_info` | | unreliable in this client (rig found it unusable); read `/data get entity @s` through chat instead |
| `get_world_info` | | untested |
| `debug_fields` | | unusable here; tap F3 with a real key (`robot.ps1 f3`) and screenshot |

## Control

| tool | params | status |
|---|---|---|
| `enter_control_mode` | | required once before any input tool; releases the cursor |
| `exit_control_mode` | | works |
| `release_mouse` | | works |
| `pause_game` | | works |
| `close_screen` | | works |
| `open_chat` | | works; wait ~300 ms before typing |
| `set_gamemode` | `mode` survival/creative/adventure/spectator | works (client-side request) |
| `wait` | `seconds` | works |

## Input (control mode required)

| tool | params | status |
|---|---|---|
| `click` | `x`, `y` (framebuffer pixels), `button` | works on buttons; without compat a click into an `EditBox` does not focus it |
| `click_button_id` | `id` from `get_screen_buttons` | works |
| `click_button_index` | `index` 0-based | works |
| `right_click` | | works (use item / place / open block GUI at the crosshair) |
| `mouse_drag` | `x_start`,`y_start`,`x_end`,`y_end`,`button` | untested |
| `scroll` | `clicks` (+ up, − down) | works |
| `scroll_at` | `x`,`y`,`clicks` | untested |
| `press_key` | `key` e.g. `enter`, `escape`, `tab`, `e`, `key.keyboard.w`, `hold_seconds` | works while a screen is open (`enter` submits chat, `escape` closes a GUI); in the world `escape` does nothing and F3 does not toggle the overlay; `tab` moves focus by two widgets |
| `select_list_item` | `index` | exists but fails on the world list (`could not select on WorldSelectionList`) |
| `hotkey` | `keys` comma-separated, e.g. `key.keyboard.left control,key.keyboard.a` | Ctrl+A is fixed by compat; other combos untested |
| `type_text` | `text`, `press_enter` | works in chat and vanilla screens; a container screen's `EditBox` only with compat, otherwise use `robot.ps1 field` |
| `paste_text` | `text`, `press_enter` | inserts into the focused `EditBox` with compat; otherwise use `robot.ps1 field` |
| `switch_tab` | `index` (not `tab`; a wrong name is accepted and ignored) | works on the Create World tabs; mod tab rows are ordinary buttons, click them |
| `use_item` | | works; opens a block GUI when standing on the block looking down |
| `pause_game` | | works in the world; the way to reach Save and Quit |
| `execute_command` | `command` | **broken here**: resolves to KubeJS's client dispatcher, even `/help` is refused. Use `open_chat` + `type_text` with `press_enter` |
| `set_view_angle` | `yaw`, `pitch` | works |
| `look_delta` | `delta_yaw`, `delta_pitch` | works |
| `use_item` | | works |
| `place_block` | | works |
| `overlay_click` | `x`, `y` | the mod's own overlay button; not needed when `enter_control_mode` succeeds |

## Launcher tools (not used in this project)

`launch_minecraft`, `kill_minecraft`, `install_version`, `list_supported_versions`,
`list_installed_versions`, `launch_server`, `install_server`, `serve`, `detect_java`,
`list_accounts`, `create_offline_account`. The bridge's own launcher installed a partial
NeoForge 21.1.172 and failed twice with `unexpected end of file` (2026-09-16); the project needs
NeoForge 21.1.219, so the game is always started from Gradle.

## Coordinates

`--width 854 --height 480` gives an 854x480 framebuffer. At GUI scale 2 (the default at that
size) `enumerate_widgets` reports 427x240 GUI units; multiply by 2 for `click`. At GUI scale 1
(`guiScale:1` in `options.txt`) the two frames coincide, which is simpler for scripted clicks and
lets a 620x360 mod panel fit the capture. The compat `guiClick` does the scaling itself
(`x * guiScaledWidth / width`), so with `runMcpClient` pass framebuffer pixels either way.

The bridge's screenshot is a bottom-left crop of the framebuffer when the window is larger than
the capture; keep the window at the launch size or shrink it so the whole panel is inside.

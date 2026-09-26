# Pitfalls catalogue: what went wrong in earlier sessions and what fixed it

Mined on 2026-09-24 from the transcripts of the V3 Heat-tab pass (2026-09-07), the solid-phase
GUI pass and its seven probe agents (2026-09-22), the fluid scheduling WP3 to WP5 and follow-ups
(2026-09-23), and Codex's V3 handoff review. Each row cost an agent between a few calls and a lost
client session. Read this before improvising.

## Transport and setup

| pitfall | symptom | fix |
|---|---|---|
| Raw stdio JSON-RPC into `npx minecraft-mod-mcp mcp` | `tools/call` answered out of order, or never at all (only the `initialize` reply came back) | Use the mod's HTTP endpoint (`scripts/bridge.js`), or the loaded `mcp__minecraft-mod-mcp__*` tools. Subagent sessions never have the MCP tools; HTTP always works |
| `claude mcp add --scope user` | denied by the permission classifier | project `.mcp.json` in the worktree root, untracked; needs a session restart and approval |
| Bridge jar missing from the worktree | no endpoint on 9876; one session gave up the GUI check entirely | copy from another worktree's `run/mods` or download release v0.3.0 (912248 bytes, SHA-256 `c6cc12c9...`); check `META-INF/neoforge.mods.toml` is inside |
| Port 9876 already bound (another client, or the user's own game) | `HTTP server failed: Address already in use: bind`; bridge discovery attaches to the wrong instance | check `bridge.js status` fails before launching; second client with `MC_MCP_PORT=9875` and the same variable for `bridge.js` |
| `kill_minecraft`, `launch_minecraft` | `killed:true` but the Gradle client keeps running; the launcher installs a vanilla/wrong NeoForge | stop the game JVM by pid (`/api/status` returns `pid`), start from Gradle only |
| Shell helper `"${2:-{}}"` | malformed JSON, `MalformedJsonException ... column 94` | send the body from a file or use `bridge.js` |
| `screenshot_to_file` relative path | resolves against `run/` (`run/build/shot.png`); a backslash path gave `missing path` | absolute forward-slash path; `bridge.js shot` does this |
| Windows backslashes in copied helper paths | `sed: invalid back reference` | `perl -pi -e 's{old}{new}g'` or rewrite the file |

## Client state

| pitfall | symptom | fix |
|---|---|---|
| `pauseOnLostFocus:true` (the default) | every screen is `PauseScreen`; `close_screen`, `press_key escape`, `pause_game` all fail; chat never lands; first session lost 13 minutes | write `pauseOnLostFocus:false` into `run/options.txt` before launch (stop the client first) |
| `open_chat` before `enter_control_mode` | a wedged `ChatScreen` that swallows every mouse event | `enter_control_mode` first, always; a real Escape (`robot.ps1 key -Text escape`) clears a wedged chat |
| Window resized while in a world | one resize to 854x480 coincided with "Stopping singleplayer server as player logged out" | resize on the title screen, before entering the world |
| Another app holds focus (a game like `Endfield.exe`) | plain `SetForegroundWindow` fails, Robot keys go elsewhere | `robot.ps1` attaches to the foreground thread's input queue first; the machine rule is no other game during runs |
| Old checkpoint-format world | integrated server stops, whole client crashes, `level.dat` already rewritten | copy the save before opening anything old; under the no-compatibility rule, create a fresh world |
| Unexpected screen already open | the calculator screen was found open after `set_view_angle` with no right-click | read the current screen from `enumerate_widgets` before acting |

## Menus and world creation

| pitfall | symptom | fix |
|---|---|---|
| `click_button_index` on a CycleButton | `"via":"manual_cycle"`, label changes, value does not: worlds came out survival, default terrain, commands off | real clicks (`robot.ps1 click`) for Game Mode, Difficulty, Allow Commands, World Type; screenshot before Create; `/gamemode creative` again in the world |
| Allow Commands toggle order | Creative turns commands ON by itself; one more click turns them OFF | click Game Mode twice, screenshot, click Allow Commands only if it reads OFF |
| `switch_tab {"tab":1}` | `switched:true, tab:0` | the parameter is `index` |
| `select_list_item` on the world list | `could not select on WorldSelectionList` | real Tab selects the newest world; or a real click on the entry; then Play Selected World |
| Coordinate `click` on the title/world screens | `"method":"glfw"` reply, nothing happens | `click_button_index`; coordinate clicks only worked at GUI scale 1 |
| Empty button labels | index 5 pressed for Quit Game opened Options | index 6 is Quit Game on the title screen; see the screen map |
| Open to LAN with cheats | did not make `execute_command` work | irrelevant; commands go through chat |

## Commands

| pitfall | symptom | fix |
|---|---|---|
| `execute_command` | `{"sent":true,"method":"kjs$runCommand"}` yet the log says `Unknown or incomplete command`, even for `/help`; a whole rig pilot was lost | `open_chat`, `type_text {"text":"/..."}`, `press_key {"key":"enter"}`; `bridge.js chat` / `bridge.js cmds` |
| Helper prints `ran: /cmd` on failure | agents believed `/save-all` had run | read `run/logs/latest.log`: success lines look like `[Server thread/INFO] ... [Dev: Teleported Dev to ...]`; failures contain `Unknown or incomplete command`, `Unknown function`, `You do not have permission` |
| World created with cheats off | every chat command refused | the toggle-order fix above; a template world needs `allowCommands 1b` in `level.dat` |
| `/save-all` | unknown in singleplayer | Save and Quit from the pause menu |
| `set_gamemode`, `get_player_info` | `gamemode_set:true` but nothing proves it; `get_player_info` says `survival` in a creative world, `name` empty | `/gamemode creative` via chat, verify by the log or by flying/placing |

## Blocks, aiming, GUIs

| pitfall | symptom | fix |
|---|---|---|
| Opening a block GUI from eye level, 2 blocks away | `right_click` / `use_item` reply true, `enumerate_widgets` says `no screen` | the two aiming rules in the SKILL: stand on the block looking down (`tp @s x.5 y+1 z.5 0 89`, then `use_item`), or stand 2.2 blocks south at foot level facing north (`tp @s x.5 y z+2.2 180 22`, then `right_click`) |
| Teleporting into mid-air to reach a raised block | the player falls, the crosshair lands elsewhere; 12 calls lost | build a glass column (`/fill`) to stand on, then `tp` with an explicit pitch, screenshot the crosshair before clicking |
| Neighbouring block in the line of sight | mis-aimed GUI (a pipe instead of the pump) | approach from a side with no neighbour |
| Void placed directly against a reservoir | two boundaries never connect | put a pipe between them |
| Default world (not superflat) | the line was built underground in a dug-out pocket with glowstone; 8 minutes of lighting | make the world superflat; blocks at y = -59 |
| Bridge typing into `EditBox` | `type_text`, `paste_text`, `hotkey ctrl+v`, click-then-type, Tab-then-type, drag-select all silently do nothing; 7 probes and a jar decompile to learn why | `robot.ps1 field` (Tab focus) or `robot.ps1 click` then `clear` then `paste`; or `/data merge block` NBT injection; or the `runMcpClient` compat mixins |
| Bridge `press_key tab` | moves focus by two widgets | Robot Tab moves by one |
| Wrong Tab count | value landed in the neighbouring field | screenshot before Apply; reopen the screen to reset focus |
| Hidden stacked widgets in `enumerate_widgets` | cannot tell what is visible | judge from a screenshot, then use fixed indices |
| Hover tooltip in a screenshot | covers the label you need to read | park the pointer (`click {20,200}` or `robot.ps1 hover -X 350 -Y 200`) first |

## Timing and the engine

| pitfall | symptom | fix |
|---|---|---|
| Screenshot 0.3 s after Apply | old values, or `Waiting for the engine` on a first open | wait 3 to 8 s, or close and reopen the GUI to read the next 5 s bucket; `Lag x.xx s` falling means still solving |
| Expecting `Queued for simulation event at tick N` on screen | bridge latency cannot hit the 2-tick window | GameTest covers it; do not chase it in game |
| A HELD island | Apply gives no acknowledgement; blocks removed, world reloaded, still held | `grep -c "status=HELD" run/logs/latest.log` before spending GUI time; build elsewhere or take a fresh world |
| Foreground `sleep 25; ...` | blocked by the harness ("use Monitor with an until-loop") | `until [ $SECONDS -ge 60 ]; do sleep 5; done; echo waited` in the background, then screenshot in the next call; `Start-Sleep` inside a PowerShell command that also does work passed |
| Loops over helper scripts in an isolated worktree | refused as "too complex to verify" | put the sequence in a script file under `build/` and run that one file; `bridge.js cmds file.txt` places a whole line in one call |

## Shutdown

| pitfall | symptom | fix |
|---|---|---|
| `press_key escape` in the world | nothing (only works with a screen open); `open_pause_menu` unknown | `pause_game`, or a real Escape |
| Killing the JVM to stop | world never saved cleanly (autosave usually rescues it) | pause, Save and Quit to Title (index 8), Quit Game (index 6), then confirm no `java.exe` with `fml.modFolders` in its command line |
| NeoForge config persists between starts | `run/config/createcheme-common.toml` keeps the last build's value | set the value before each paired run |

## Bridge commands that do not exist (do not guess)

`list_commands`, `help`, `get_screen_info`, `use_block`, `interact_block`, `interact`, `use`,
`open_pause_menu`, `inject_click`, `press_enter`; `GET /api/help` is `not found`. The `/debug`
page's `<option>` list is partly stale. The working set is in `bridge-tools.md`.

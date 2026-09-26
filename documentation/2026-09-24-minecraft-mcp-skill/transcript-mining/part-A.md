# MCP bridge lessons, part A (sessions of 2026-09-07)

Sources (line numbers are JSONL record numbers):
- `A` = `fff2a6d4-.../subagents/agent-a13e5711283cce4e3.jsonl` (WP4 Heat tab GUI agent)
- `P` = `fff2a6d4-d6de-433c-9f3b-5ea673e601c2.jsonl` (parent: bridge install and smoke test, records ~842-1111)
- Not mined in this part: `d339add2-...`, `4961e857-...`, `b66d591a-...`. Their early records are the same sequence in all three (identical `mcp__minecraft` calls from record ~914 on, `b66d591a` offset to ~1261). They contain a second MCP pass (enter_control_mode, get_screen_buttons, get_player_info, open_chat, type_text `/gamemode creative` with `press_enter:false`, around records 914-1128 of d339add2). Mine them separately.

## Difficulties

1. **Bridge not registered at user scope**
   - Goal: make the MCP tools available.
   - Symptom: `claude mcp add --scope user minecraft-mod-mcp -- cmd /c npx -y minecraft-mod-mcp` was denied: "Permission for this action was denied by the Claude Code auto mode classifier".
   - Attempts: 1.
   - Fix: write a project `.mcp.json` in the worktree root (`{"mcpServers":{"minecraft-mod-mcp":{"type":"stdio","command":"cmd","args":["/c","npx","-y","minecraft-mod-mcp"]}}}`). The tools only appear after a session restart that approves the server. Leave the file untracked. (P 896-906, 1032)

2. **Piped stdio tool calls come back out of order**
   - Goal: drive the bridge from Bash before the tools were loaded.
   - Symptom: `click_button_index` answered `"error": "not in control mode"` even though `enter_control_mode` came earlier in the same pipe. Responses came back as id 7, 3, 2, ...
   - Attempts: 2.
   - Fix: one `tools/call` per `npx -y minecraft-mod-mcp mcp` process when order matters. This only matters for raw stdio; the loaded `mcp__minecraft-mod-mcp__*` tools are sequential. (P 967-977)

3. **Clicks refused outside control mode**
   - Symptom: `{"error":"not in control mode","hint":"Enter control mode via ESC > MCP Take Over"}`.
   - Fix: call `enter_control_mode` first after every client launch. It returns `{"control_mode":true,"platform":"internal","hook":false}`. (P 968, A 189, 604, 1184)

4. **Screenshots show as white**
   - Symptom: every PNG has alpha 0 (`854x480 type=6 meanA=0.0`), so viewers show white. The RGB data is intact.
   - Fix: `"C:/Program Files/Java/jdk-21.0.11/bin/java" -cp build/pkgcmp/classes21 PngFlatten in.png out.png`, then Read `out.png`. PngFlatten copies `p & 0xFFFFFF` into a TYPE_INT_RGB image. Source is in P 999. `screenshot_to_file` needs an absolute forward-slash path; a sed-mangled backslash path gave `"error": "missing path"`. (P 944-1000)

5. **`kill_minecraft` does not stop a Gradle-launched client**
   - Symptom: it returned `{"killed": true}`, but status still showed the client connected.
   - Fix: get the pid from the status endpoint and kill that process: `PID=$(curl -s http://localhost:9876/api/status | grep -o '"pid":[0-9]*' | cut -d: -f2); taskkill //PID "$PID" //F`. Also do not use `launch_minecraft`, which starts a vanilla client. (P 1006-1028, A 593, 1305)

6. **World list entry cannot be selected with a GUI-coordinate click**
   - Goal: open the copied save "New World" from Singleplayer.
   - Symptom: `click {"x":213,"y":62}`, then `{150,68}` and `{150,70}` all returned `"method":"glfw"`, but the entry stayed unselected and "Play Selected World" stayed grey. Tab and Down only moved focus to the search box and buttons. `overlay_click` returned `{"result":"blocked"}`.
   - Attempts: about 8 in session 1, where the agent gave up and created a new world. In session 2 it worked after two changes:
     - `pauseOnLostFocus:false` in `run/options.txt`, plus forcing the window to the foreground.
     - Clicking at window pixels, not GUI units. At GUI scale 2 on the 854x480 capture, `click {"x":300,"y":135}` selected the entry, then `click_button_index 1` (Play Selected World) opened it. (A 209-297, 617-644)
   - Later at GUI scale 1 (560x300 client): `click {420,96}` selected the second row, so a single click works at this scale. `click {420,66}` twice in a row (a double-click) then opened the first world directly. (A 1203-1241)

7. **`execute_command` does not reach the server**
   - Goal: `/gamemode creative` and `/setblock`.
   - Symptom: returned `{"sent":true,"method":"kjs$runCommandSilent"}`, but `run/logs/latest.log` shows `[CHAT] Unknown or incomplete command, see below for error` and `/gamemode creative<--[HERE]`. The same happened with Allow Commands ON and after Open to LAN with cheats ON.
   - The `set_gamemode` tool also returned success (`{"gamemode_set": true, "mode": "CREATIVE"}`), but `get_player_info` still said `"gamemode":"survival"` and nothing proved it took effect.
   - Attempts: about 7.
   - Fix: `open_chat`, then `type_text {"text":"/gamemode creative","press_enter":true}`, with the window focused and `pauseOnLostFocus:false`. Check `latest.log` afterwards: success shows no "Unknown or incomplete command" line. (A 370-664)
   - Note: `get_player_info.gamemode` stayed `"survival"` even after it worked, so it is not a reliable signal. Use player movement or the log instead.

8. **Auto-pause closes chat and other screens**
   - Symptom: PauseScreen reopened right after `close_screen`. Chat typed with `type_text` never landed. `enumerate_widgets` returned `{"error":"no screen"}`.
   - Fix: in `run/options.txt`, change `pauseOnLostFocus:true` to `pauseOnLostFocus:false` (via sed while the client is stopped), then relaunch. In the same session the agent also forced the window to the front with PowerShell user32 `AttachThreadInput` + `SetForegroundWindow`, which returned `foreground pid=65600`. A plain `SetForegroundWindow` was not enough while another app (`Endfield`) held focus. (A 544-593, 614)

9. **Window size and GUI scale (the calculator panel cropped or off-screen)**
   - Symptom: the screenshot is always 854x480, but the window client was 2560x996 (maximised), so the GUI space was 1280x498 and the panel was not visible in the capture. Later, an 854x480 client at 150 % Windows DPI gave only part of the frame at auto scale.
   - Attempts: several.
   - Fix: PowerShell `MoveWindow(h, 100, 100, 575, 337, true)` gives client `560x300` logical. At 150 % DPI that makes a framebuffer of about 840x450 with auto GUI scale 1 and GUI space 840x450, so the capture matches GUI coordinates 1:1 and the 620x360 panel fits. `MoveWindow(h,100,100,375,337,true)` gives client `360x300` for the narrow-layout test. Note that a `ShowWindow(h,9)` + `MoveWindow` to 854x480 coincided with the world quitting to the title screen ("Stopping singleplayer server as player logged out"). (A 715-821, 1080, 1093, 1181)

10. **Typing into a mod EditBox never worked**
    - Goal: type cooler values into the Heat tab EditBoxes.
    - Symptom: `click {166,143}`, `click {332,286}`, click after forcing focus, five Tab presses, and `mouse_drag {150,143 -> 180,143}` all followed by `type_text "12"`. No cursor appeared and no text landed.
    - Attempts: about 5.
    - Never solved. Substitute: write the block-entity NBT through chat with `/data merge block -272 68 -110 {Input:{Pumparounds:[...]}}`, reopen the screen (`right_click`), and the screen mirrors the server input. The review recorded that a human should type one row once. (A 879-1017)

11. **Screen opened unexpectedly**
    - The ColumnCalculatorV3Screen was found open after `set_view_angle` with no `right_click` sent. The cause is unknown; the first screenshot was blurred. Always check `enumerate_widgets` for `"screen"` before acting. (A 684-700)

12. **Preset double state packet (a product bug found through the GUI)**
    - One preset request is answered with both a reply and a viewer broadcast. The client restore ran twice and cleared the rows. This was found only in game (Holland to Tia Juana round trip) and fixed by making the restore idempotent. The lesson: verify round trips in game, not just in unit tests. (A 1150-1304)

13. **Waiting with `sleep` blocked**
    - `sleep 120; tail` was blocked by the harness. Use a background `until grep -qE "BUILD SUCCESSFUL|BUILD FAILED" log; do sleep 5; done`. (A 1323-1329)

## Techniques that worked

- **Launch and readiness:**
  - Launch from the worktree with `./gradlew.bat runClient --offline > build/runClient-gui.log 2>&1` (run_in_background).
  - Poll `curl -s --max-time 3 http://localhost:9876/api/status` until it contains `minecraft-mod`. It was ready about 45-60 s after launch.
  - Status body: `{"ok":true,"type":"minecraft-mod","version":"unknown","loader":"unknown","pid":31336,"port":9876,...}`.
- **Mod jar:** `run/mods/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar` (912248 bytes, modId `mcpmod`, GitHub release v0.3.0).
- **Tool list** (tools/list): click, click_button_id, click_button_index, close_screen, debug_fields, enter_control_mode, enumerate_widgets, execute_command, exit_control_mode, get_minecraft_status, get_player_info, get_screen_buttons, get_world_info, hotkey, kill_minecraft, launch_minecraft, look_delta, mouse_drag, open_chat, overlay_click, paste_text, pause_game, ping, place_block, press_key, release_mouse, right_click, screenshot, screenshot_to_file, scroll, scroll_at, set_gamemode, set_view_angle, switch_tab, type_text, use_item, wait.
- **`click_button_index` is reliable** for Button (`"via":"onPress.onPress"`) and CycleButton (`"via":"manual_cycle","newIdx":n`). It is the preferred way to press anything. `enumerate_widgets` gives class names and boxes but no labels; `get_screen_buttons` has labels, but they came back empty (`""`). Take a screenshot to identify buttons.
- **Onboarding screen:** a fresh `run/` shows `AccessibilityOnboardingScreen`; `click_button_index 4` (the Button at y=214) is Continue and leads to TitleScreen. It does not reappear on relaunch.
- **TitleScreen indices:** 0 Singleplayer, 5 Options (98-wide button at y=198).
- **SelectWorldScreen indices:** 0 WorldSelectionList, 1 Play Selected World, 2 Create New World.
- **CreateWorldScreen (Game tab) indices:** 4 EditBox (name), 5 CycleButton Game Mode (Survival -> Hardcore -> Creative, so click twice), 6 Difficulty, 7 Allow Commands (cycles back on a second click; check with a screenshot), 1 Create World. Wait about 10 s after Create.
- **PauseScreen indices:** 0 Back to Game, 5 Options, 6 Open to LAN, 8 Save and Quit.
- **ShareToLanScreen indices:** 0 game mode, 1 Allow Cheats, 3 Start LAN World. This route did not fix commands (see difficulty 7).
- **Options screen:** index 6 is Video Settings. Its entries are inside an `OptionsList` and not individually indexable, so change GUI scale by resizing the window instead.
- **Opening the calculator:**
  - Place it with chat `/setblock -272 68 -110 createcheme:column_calculator_v3`, using absolute coordinates near the `get_player_info` pos.
  - Aim with `set_view_angle {"yaw":-23.2,"pitch":12.9}`, computed from the player pos to the block centre.
  - Open it with `right_click {}`, which returns `"via":"screen_mouseHandler"`.
  - Calculator tab buttons: 0 Inputs, 1 Streams, 2 Heat, 3 Convergence, 4 preset, 5 Run.
- **Waiting for a mod result:** `wait {"seconds":10}` after Run, then screenshot. For world load, `wait {"seconds":8-12}`, then `get_player_info` (`"name": null` means still not in the world).
- **Closing screens:** `close_screen` (`keyPressed(ESCAPE)`). Reopening the screen fetches fresh server state after `/data merge`.
- **Checking command results:** `tail run/logs/latest.log` for `[CHAT]` lines.

## Exact recipes

Launch to world (session 2, worked):
```
taskkill //PID <old> //F; sed -i 's/^pauseOnLostFocus:true/pauseOnLostFocus:false/' run/options.txt
./gradlew.bat runClient --offline > build/runClient-gui2.log 2>&1   (background)
poll curl http://localhost:9876/api/status
PowerShell: MoveWindow(h,100,100,575,337,true); AttachThreadInput + SetForegroundWindow   -> client=560x300
enter_control_mode {}
click_button_index {"index":0}            # Singleplayer
click {"x":420,"y":66}; click {"x":420,"y":66}   # double-click first world at GUI scale 1
```

Commands and opening the block:
```
open_chat {}
type_text {"text":"/gamemode creative","press_enter":true}
open_chat {}
type_text {"text":"/setblock -272 68 -110 createcheme:column_calculator_v3","press_enter":true}
set_view_angle {"yaw":-23.2,"pitch":12.9}
right_click {}
click_button_index {"index":2}            # Heat tab
screenshot_to_file {"path":"D:/.../documentation/gui/raw/x.png"}  then PngFlatten
```

Inject state instead of typing:
```
close_screen {}; open_chat {}
type_text {"text":"/data merge block -272 68 -110 {Input:{Pumparounds:[{Return:8,Draw:12,DutyWatts:-2000000.0d,Split:\"UNIFORM\"},{Return:13,Draw:17,DutyWatts:-1500000.0d,Split:\"UNIFORM\"},{Return:19,Draw:22,DutyWatts:-1000000.0d,Split:\"RETURN_TRAY\"}]}}","press_enter":true}
right_click {}; click_button_index {"index":2}; click_button_index {"index":5}  # Run
wait {"seconds":10}
```

Stop:
```
taskkill //PID <pid from /api/status> //F ; curl status -> "bridge down"
```

## Open problems never solved

- Typing into mod `EditBox` widgets (click, Tab focus and mouse_drag all failed); worked around with `/data merge block`.
- `execute_command` via `kjs$runCommandSilent` never ran server commands; `set_gamemode` was unverified.
- `get_player_info.gamemode` never updated.
- The client and GUI coordinate mapping changed with window size, DPI and GUI scale and was never pinned down; the fixed 560x300 window was used instead.
- The unexpected screen open (difficulty 11) and the world quitting after the resize were never explained.
- The before screenshots were taken on the modified build, so no true baseline was captured.

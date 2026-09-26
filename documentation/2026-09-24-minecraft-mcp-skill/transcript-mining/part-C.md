# MCP bridge difficulties, part C (seven subagent transcripts)

Source folder: `C:/Users/wormz/.claude/projects/D--Minecraft-Modding-1-21-CreateChemE--claude-worktrees-solid-phase-fluid-system-plan-4369b0/52c95b9f-3221-4e12-9346-67c54bcfffba/subagents/`.
Line numbers `#N` are JSONL line numbers in that file. Short names:

| short | file | task | date | how it drove the bridge |
|---|---|---|---|---|
| **A0F** | `agent-a0fba21980f2fa09a.jsonl` | root-cause HELD filter islands | 2026-09-22 | HTTP `curl` (`mc.sh`/`cmd.sh`) plus a `java.awt.Robot` helper (`poke.ps1`) |
| **AE0** | `agent-ae086faac00564dbc.jsonl` | hydraulic row scale | 2026-09-22 | HTTP plus `poke.ps1` |
| **A74** | `agent-a74f606336340ffe9.jsonl` | F4 tank node block | 2026-09-22 | HTTP plus `poke.ps1` |
| **A28** | `agent-a28b2d53abd8b3809.jsonl` | elevated-line probe | 2026-09-23 | HTTP plus `poke.ps1` |
| **A42** | `agent-a42d400284df5e10d.jsonl` | fluid scheduler WP3 presentation | 2026-09-23 | MCP tools `mcp__minecraft-mod-mcp__*` plus a Robot `field.ps1` |
| **AE6** | `agent-ae6bbf33916a45bc7.jsonl` | fluid scheduler WP4 persistence (save/reload) | 2026-09-23 | MCP tools |
| **A4B** | `agent-a4b16c76012f533f1.jsonl` | fluid scheduler WP5 in-game benchmark | 2026-09-23 | Node `fetch` against the HTTP API from a rig script (`run-client.js`) |

Bridge jar in every run: `run/mods/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar`. HTTP API: `POST http://localhost:9876/api/cmd` with body `{"cmd":"<name>", ...params}`, plus `GET /api/status`. The game window is 854x480 framebuffer pixels at GUI scale 2, so widget coordinates from `enumerate_widgets` are in a 427x240 GUI space.

---

## Difficulties

### 1. "not in control mode": the agent thought control mode needed a world and real desktop input
1. **Goal:** click Singleplayer on the title screen (A0F).
2. **What went wrong:** every input command (`click_button_index`, `press_key`) returned `{"error":"not in control mode","hint":"Enter control mode via ESC > MCP Take Over"}` (A0F #606, #610, #617). The agent guessed the names `take_over`, `control_mode`, `/api/control`, `/api/help`, `/api/commands`, and got `not found` or the same error (#613-#617). It then concluded that it needed real desktop input, and wrote a `java.awt.Robot` helper plus a PowerShell foreground forcer (#631-#729). The paused game menu has **no** "MCP Take Over" button in this build (#767, #1216).
3. **Cost:** about 10 minutes (12:06 to 12:16 UTC, #601 to #785), with 6 Robot/foreground attempts and 3 whole-desktop captures.
4. **Fix:** A0F read the jar's lang file (`mcpmod.control.pause_button: "MCP Take Over"`) and the `ControlModeHelper` strings (#773-#781), then sent `{"cmd":"enter_control_mode"}`, which returned `{"control_mode":true,"platform":"internal","hook":false}` (#785). Later agents confirmed that **`enter_control_mode` works on the title screen**, so no Robot is needed to reach a world (A42 #794, AE0 #485, A74 #715, A28 #514, AE6 #853; AE0 report §8 quoted in A74 #807). Always call it first, and call it again after every client restart (A28 #814, #952).
5. **Evidence:** A0F #606-#785. Output-only commands (`screenshot_to_file`, `enumerate_widgets`, `get_player_info`) work without control mode (#620-#621).

### 2. `open_chat` before control mode left a ChatScreen that swallowed every mouse event
1. **Goal:** test which commands work without control mode (A0F).
2. **What went wrong:** `open_chat` returned `{"error":"null"}` (#621), but it left a `ChatScreen` with no widgets (`{"screen":"ChatScreen","widgets":[],"total":0}`, #651, #668, #682). Every later Robot click on "Singleplayer" did nothing.
3. **Cost:** about 5 minutes, four click attempts (#650, #664, #678, #692) and two desktop screenshots, before the agent worked out that input was dead (#723).
4. **Fix:** a real ESC key through Robot (`poke.ps1 key escape`) closed the ChatScreen and brought back `TitleScreen` (#724-#725). The ESC through the bridge was refused, because control mode was still off.
5. **Evidence:** A0F #620-#728. Rule: enter control mode before anything that opens a screen.

### 3. Windows would not bring the dev client window to the foreground (Robot path)
1. **Goal:** send a real click or keypress into the Minecraft window (A0F, then copied by AE0, A74 and A28).
2. **What went wrong:** a plain `SetForegroundWindow` from a background PowerShell did not focus the window, so clicks landed elsewhere (#664-#685).
3. **Cost:** 3 attempts, about 3 minutes.
4. **Fix:** `poke.ps1` calls `AttachThreadInput(currentThread, foregroundThread, true)`, `ShowWindow(h,9)`, `BringWindowToTop`, `SetForegroundWindow` and `SetFocus`, then detaches and checks `GetForegroundWindow()==h` (#689). It maps GUI coordinates to screen pixels with the window's `ClientToScreen` origin plus `gui * 4/3`. That factor is specific to this host's DPI (a 569x320 logical client area for the 854x480 framebuffer, #639). A0F confirmed the mapping by hovering and taking a screenshot (#642-#646); the full script is under Exact recipes. The window origin changes between sessions (A0F clicked 1279,509 while AE0 clicked 1414,482 for the same widget), so the script reads it on every call.
5. **Evidence:** A0F #638-#729, script body #689.

### 4. The bridge's `execute_command` never reaches the server
1. **Goal:** run `/setblock`, `/fill`, `/gamemode`, `/gamerule` and `/function` (AE6, A4B).
2. **What went wrong:** `execute_command` replied `{"sent":true,"method":"kjs$runCommand"}` (or `kjs$runCommandSilent`), but nothing happened in the world. AE6 first sent it with a leading slash, then without (#985-#1020), and neither placed a block. In A4B, `latest.log` shows `[CHAT] Unknown or incomplete command, see below for error` and `/gamemode creative<--[HERE]` (#812-#813). The call goes through a KubeJS client-side dispatcher, not the integrated server.
3. **Cost:** AE6 lost about 2 minutes, then misdiagnosed the cause as "commands off" and opened the world to LAN (#1033-#1053). A4B's scripted client pilot was **stuck for about 11 minutes** (12:24 to 12:35, #753-#763) with `devices=0`, because every command was silently rejected. The coordinator had to step in (queued message, #773). The coordinator's own theory, `allowCommands 0`, was wrong: `level.dat` held `allowCommands 1` and `GameType 1` (#771-#772, #801-#802).
4. **Fix:** type every command into chat: `open_chat`, then `type_text {"text":"/setblock ...","press_enter":true}` (MCP tools, A42 #880-#918), or over HTTP `open_chat`, `type_text {"text":"/..."}` and `press_key {"key":"enter"}` (A0F `cmd.sh` #567). A4B proved it on the same world: typed `/gamemode spectator` produced `[CHAT] Set own game mode to Spectator Mode`, and `/function createcheme_bench:rest100` produced `Running function ...` (#816-#821). A4B's final driver reads each chat reply back from `run/logs/latest.log` and stops the run on `Unknown or incomplete command|Unknown function|Incorrect argument|...` (#867).
5. **Evidence:** AE6 #985-#1070; A4B #763-#821, #867; A0F `cmd.sh` comment "the bridge's execute_command reaches a client-side dispatcher only" (#567).

### 5. `press_enter` does not exist
1. **Goal:** submit a chat command (AE0).
2. **What went wrong:** `{"result":"unknown: press_enter"}` (AE0 #597-#598).
3. **Cost:** 1 attempt.
4. **Fix:** `press_key {"key":"enter"}` (AE0 #600), or `type_text` with `"press_enter":true` (A42, AE6, A4B).
5. **Evidence:** AE0 #597-#601.

### 6. Unknown command names wasted probes
1. **Goal:** find the bridge's command list, and a command that "uses" a block.
2. **What went wrong:** `help`, `list_commands`, `click_widget`, `use_block`, `interact`, `use` and `kill_minecraft` (over HTTP) all returned `{"result":"unknown: <name>"}` (AE0 #488-#498, A74 #803-#822, A0F #786, A4B #859-#860). `/api/help` and `/api/commands` return `{"error":"not found"}`. Loops that tried several names in one Bash call were refused by the worktree-isolation guard ("runs bash inside a construct too complex to verify", AE0 #495, A74 #819, A0F #614).
3. **Cost:** 3 to 6 calls per agent.
4. **Fix:** AE0 disassembled the handler with `javap -p -c xyz/langyo/minecraft/mcp/common/McpMessageHandler.class | grep -o 'String [a-z_]*'` (#506-#507). The MCP tool list is in the memory note (A42 #639): screenshot, screenshot_to_file, enumerate_widgets, get_screen_buttons, click, click_button_id, click_button_index, overlay_click, right_click, mouse_drag, scroll, scroll_at, type_text, paste_text, press_key, hotkey, switch_tab, close_screen, open_chat, execute_command, get_player_info, get_world_info, get_minecraft_status, set_gamemode, place_block, use_item, look_delta, set_view_angle, enter/exit_control_mode, release_mouse, pause_game, wait, launch_minecraft, kill_minecraft, and others. `kill_minecraft` and `launch_minecraft` belong to the Node MCP server, not the in-game HTTP API.
5. **Evidence:** as listed.

### 7. `click_button_index` on a CycleButton moves the label without firing the value change
1. **Goal:** set Game Mode to Creative, turn Allow Commands on, and pick World Type Superflat in the Create World screen (A74, AE0, AE6, A42).
2. **What went wrong:** `click_button_index` on a `CycleButton` replies `"via":"manual_cycle","newIdx":N`. A74's report says it "moves the widget's internal index **without firing its value change** — the "Allow Commands" toggle reported `newIdx` flipping while the label stayed `OFF` through three clicks" (A74 #1062). AE6 later decided the same thing about game mode (#1032). The evidence is mixed, though. In A42, AE0 and AE6, cycling index 5 twice (game mode) and index 3 once (world type) without any real click gave a superflat world (player at y = -60) where typed chat commands worked (A42 #852-#918, AE0 #530-#614). World type through `manual_cycle` therefore works, and game mode probably does too. The Allow Commands toggle (index 7) cycled `newIdx 1, 0, 1` across tab switches and could not be trusted (AE0 #530-#544, A74 #729-#753).
3. **Cost:** A74 about 1.5 minutes (6 clicks and 4 screenshots); AE0 about 1 minute.
4. **Fix:** a real click through Robot on the CycleButton centre. The Game tab's Allow Commands is at `poke.ps1 gui 213 142` (A74 #759) and Game Mode at `poke.ps1 gui 213 86`, clicked twice for Creative (A0F #740, A28 #529, #863). The World tab's World Type is at `poke.ps1 gui 133 45`, clicked once for Superflat (A0F #754, A28 #543, #869). Check with a screenshot of the tab afterwards. In practice, don't touch Allow Commands: Creative turns commands on by default.
5. **Evidence:** A74 #729-#769, #1062; AE0 #530-#565; AE6 #934-#969, #1032.

### 8. `get_player_info.gamemode` and `get_world_info.gametype` always report "survival"
1. **Goal:** verify creative mode after creating the world (every agent).
2. **What went wrong:** `"gamemode":"survival"` after creating a Creative world (A0F #762, A42 #860, AE6 #981, AE0 #567), after `set_gamemode {"mode":"creative"}` returned `{"gamemode_set":true,"mode":"CREATIVE"}` (AE0 #570-#571), after `/gamemode creative` in chat (AE0 #580-#601, A0F #793-#794), and even after the log showed `Set own game mode to Spectator Mode` (A4B #817). `get_world_info` says `"gametype":"survival"` as well (A4B #798).
3. **Cost:** AE0 lost about 1.5 minutes on 4 attempts (#570-#607); AE6's misdiagnosis in #4 grew partly out of this.
4. **Fix:** don't trust these fields. Verify through the chat reply in `run/logs/latest.log` (`[CHAT] Set own game mode to Creative Mode`), or through a harmless `/setblock` shown in a screenshot (AE0 #607-#614).
5. **Evidence:** as listed; AE0 report: "`get_player_info`'s `gamemode` field reads `survival` in a creative world and cannot be trusted" (A74 #807).

### 9. Typing into an EditBox: the bridge's `type_text` and `press_key` never reach the field
1. **Goal:** type a pressure (`400000`, `150000`, `101325`), a solids percentage, a material id (`createcheme:demo_particle`) or a size into `FluidDeviceScreen`, and a world name into `CreateWorldScreen` (all agents).
2. **What went wrong:** the bridge's typing does not reach a container screen's `EditBox`. The earlier solid-phase pass found the cause: "the bridge reflects a (char,int) method the screen does not declare, and Minecraft reads the live GLFW modifier state for Ctrl+V" (the `Field.java` doc comment, A0F #835). In AE6, the world-name box ignored `click {x:426,y:115}` (`"method":"glfw"`, no widget hit) followed by `press_key key.keyboard.end` and `key.keyboard.backspace` (#910-#933). Plain Robot keystrokes lost letters to a host IME (A0F #873 comment: "a host IME eats letter keystrokes").
3. **Cost:** AE6 about 1 minute, then gave up and kept the default name "New World" (the folder became `New World (1)`). The Robot path cost nothing, because every agent reused the helpers.
4. **Fix:** real input through `java.awt.Robot`, in one of two ways:
   * **Click, clear, paste** (A0F, AE0, A74, A28): `poke.ps1 gui <x> <y>` on the field centre, then `Poke clear` (Ctrl+A, Backspace), then `Poke paste <text>`, which sets the clipboard and sends Ctrl+V. Field centres in `FluidDeviceScreen`: pressure `314 82`; solids % `314 144`; solids material id `128 173`; size `285 173`; the last solids field `367 173`.
   * **Tab focus** (A42): `field.ps1 -Tabs 2 -Text "150000"` presses Tab twice from the freshly opened screen to focus the pressure box, then clears and pastes (#998-#1000). It worked on the first try.
5. **Evidence:** A0F #838-#883; A42 #994-#1007; AE0 #665-#678; AE6 #910-#933.

### 10. The HTTP `click` takes framebuffer pixels (854x480), not GUI coordinates
1. **Goal:** press "Save and Quit to Title" on the pause screen from a script (A4B).
2. **What went wrong:** `click {x:213,y:192}` (GUI coordinates) was halved to `"gui":[106,96]` and pressed a different button (A4B #906, event "save and quit").
3. **Cost:** 1 run's clean quit.
4. **Fix:** double the GUI coordinates: `click {"x":426,"y":384}` (A4B #913). AE6 used framebuffer pixels throughout: `click {x:426,y:204}` is Singleplayer (GUI 213,102), `{x:584,y:394}` is Create New World on the world list (GUI 292,197), and `{x:532,y:288}` is Open to LAN. The reply shows the GUI point it hit and which callback ran (`"callback":"onPress.onPress(widget)"`); `"method":"glfw"` means no widget was hit.
5. **Evidence:** A4B #906-#913; AE6 #883-#898, #1040-#1053.

### 11. In-world `press_key escape` over HTTP does not open the pause menu
1. **Goal:** reach Save and Quit from the world (A28).
2. **What went wrong:** `press_key {"key":"escape"}` replied `{"pressed":"escape"}`, but then `enumerate_widgets` returned `{"error":"no screen"}` (A28 #771-#772, #849).
3. **Cost:** 1 extra call each time, repeated in three sessions.
4. **Fix:** A28 used a Robot ESC (`poke.ps1 key ESCAPE`, #774, #852, #932, #1029), which works. Simpler: the bridge's **`pause_game`** returns `{"paused":true,"method":"setScreen(PauseScreen)"}` (A42 #1107, AE6 #1033, #1135). Then `click_button_index {"index":8}` is Save and Quit to Title, and on the title screen `click_button_index {"index":6}` is Quit Game (A42 #1123-#1132, A28 #781-#791). `press_key escape` *does* close an open screen (pause menu, device GUI: A0F #793, #1026).
5. **Evidence:** A28 #771-#781; A42 #1096-#1132.

### 12. The bridge's F3 key does not toggle the debug overlay
1. **Goal:** screenshot with the F3 overlay (A4B).
2. **What went wrong:** after `press_key {"key":"key.keyboard.f3"}` and after `hotkey {"keys":"key.keyboard.f3"}`, the screenshot was byte-identical to the one without F3 (`cmp ... && echo IDENTICAL`, A4B #824-#842).
3. **Cost:** 2 attempts.
4. **Fix:** `tools/f3.ps1` (bring the window forward, then `KeyTap`, a Robot `VK_F3` press and release), run once to turn the overlay on and once to turn it off (A4B #863; used in run-client.js #867, output `tapped F3`).
5. **Evidence:** A4B #824-#867.

### 13. Opening a block GUI: aim was off, so `right_click` hit air
1. **Goal:** open a generator, tank, filter or valve screen (A28, AE0, A74).
2. **What went wrong:** after `tp @s 2.5 -59 2.5 135 21` and `right_click`, the reply was `{"right_click":true,"via":"screen_mouseHandler"}` followed by `{"error":"no screen"}` (A28 #583-#584); likewise `tp @s 0.5 -58 2.5 0 39` (AE0 #638-#639). An elevated tank (y = -55) needed three re-aims: `tp ... facing` from below, a stone platform `fill 22 -56 19 23 -56 21 stone`, and then explicit yaw/pitch `tp @s 22.5 -55 20.5 90 29` (A28 #722-#744). A74 aimed backwards once with yaw 180 (#892) and fixed it with yaw 0 (#899).
3. **Cost:** A28 about 2 minutes (3 re-aims, 3 screenshots); AE0 about 1 minute.
4. **Fix:** stand at block level with an exact aim point:
   * `tp @s <x+2.5> <y> <z+2.5> facing <x+0.5> <y+0.5> <z+0.5>`, then `right_click` (A28 #593: `tp @s 2.5 -59 2.5 facing 0.5 -58.5 0.5` opened `FluidDeviceScreen`);
   * or stand on the floor 1 to 2 blocks away, looking straight: `tp @s 0.5 -60 2.5 0 0` for a device at (0,-59,4) (AE0 #648-#656), or `tp @s 0.5 -60 -1.6 0 5` for a device at (0,-59,0) (A74 #825-#832).
   * With the MCP tools, `set_view_angle {"yaw":0,"pitch":20}`, then `right_click` (AE6 #1097-#1098, A42 #930).

   `use_item` also opens the looked-at block's GUI (`{"use_item":true,"method":"startUseItem"}`, A0F #822-#823). Check `enumerate_widgets` for `"screen":"FluidDeviceScreen"` before taking the screenshot.
5. **Evidence:** A28 #583-#594, #715-#748; AE0 #638-#656; A74 #892-#899.

### 14. Reloading a saved world: a double-click in the world list did not load it
1. **Goal:** reopen a saved world after Save and Quit (A28, AE6).
2. **What went wrong:** a Robot double-click at `poke.ps1 gui 213 60` (twice) left the client on `SelectWorldScreen` (A28 #817-#821).
3. **Cost:** 1 attempt, about 1 minute including a 20 s wait.
4. **Fix:** a single click to select the entry, then **Play Selected World** by index: `poke.ps1 gui 213 62` followed by `click_button_index {"index":1}` (A28 #824-#828). The newest world is the first entry, and a second entry sits about 36 GUI pixels lower (A28 #960 `gui 213 68`, AE6 `click {x:400,y:207}`). With the MCP tools: `click {x:400,y:135}` (framebuffer pixels, first entry), then `click {x:266,y:394}` for Play Selected World (AE6 #1169-#1170). The title screen's Singleplayer opens `CreateWorldScreen` directly when there are no saves, and `SelectWorldScreen` once there are (A0F #730 vs #1004); on `SelectWorldScreen`, Create New World is `click_button_index {"index":2}` (A28 #860).
5. **Evidence:** A28 #814-#828, #952-#963; AE6 #1156-#1178.

### 15. The bridge's inline `screenshot` result is too large to read
1. **Goal:** take a screenshot (A42, AE6).
2. **What went wrong:** `Error: result (1,126,543 characters across 6 lines) exceeds maximum allowed tokens` (A42 #775-#776, AE6 #861-#862).
3. **Cost:** 1 call each.
4. **Fix:** `screenshot_to_file {"path":"<absolute or run-relative>.png"}`, then Read the PNG. Relative paths are resolved against `run/`: `"../documentation/screenshots/x/name.png"` (A0F #620).
5. **Evidence:** as listed.

### 16. Bridge screenshots carry alpha = 0 on every pixel
1. **Goal:** view the screenshots and keep them as evidence.
2. **What went wrong:** the saved PNGs have a zero alpha channel, so they read as blank or transparent. The previous pass had already solved this.
3. **Cost:** none in these runs; every agent reused `Flatten.java`.
4. **Fix:** `java -cp build/flatten Flatten <png>...` rewrites each file as `TYPE_INT_RGB` with `rgb & 0xFFFFFF` (source in A0F #554). One `shot.sh` wraps `screenshot_to_file` and the flatten step (A0F #570).
5. **Evidence:** A0F #554, #570; A42 #784.

### 17. `get_minecraft_status` misreports; readiness polling
1. **Goal:** know when the client and bridge are up after `./gradlew.bat runClient`.
2. **What went wrong:** the MCP `get_minecraft_status` returned `{"connected":false,"port":null,"processAlive":false}` while the bridge was answering (A42 #768-#769; `ping` returned `pong`, #772-#773). A foreground `sleep 60; curl ...` was refused by the harness: `Blocked: sleep 60 followed by: ... To wait for a condition, use Monitor with an until-loop` (A0F #585-#586, A42 #676-#677, A28 #664, #950, A0F #1168).
3. **Cost:** 1 to 2 calls per agent, many times over.
4. **Fix:** a background until-loop on the HTTP status endpoint: `until curl -s -m 3 http://localhost:9876/api/status | grep -q minecraft-mod; do sleep 3; done` (A42 #765: about 45 s to READY; A0F #591; A28 #501 polled `get_screen_buttons` instead). A `sleep` *inside* a `&&` chain after another command is not blocked (A0F #1098 `... && sleep 45 && ...`); only a leading `sleep` is. For long waits, run `sleep 60; echo waited` with `run_in_background:true` (A0F #1170), or poll `latest.log`.
5. **Evidence:** as listed.

### 18. Worktree-isolation guard and PowerShell quirks broke helper commands
1. **Goal:** create helpers, loop over commands, call HTTP from PowerShell.
2. **What went wrong:** the harness refused `for` loops and heredocs ("too complex to verify", A0F #559, #614, #1027, #1162; AE0 #495; A74 #676, #819), and refused a quoted absolute executable path (`"/c/Program Files/Java/jdk-21.0.11/bin/javac.exe"`: "command whose name is computed at runtime", A0F #574). In PowerShell, `Invoke-WebRequest` failed with `Windows PowerShell is in NonInteractive mode` (A0F #664-#665); `.Substring(0,300)` threw on a short reply (#678-#679); `& bash ...` was not recognized (#1106-#1107).
3. **Cost:** about 8 retries in A0F, each costing a round trip.
4. **Fix:** write each helper with the Write tool and run plain `bash build/mcp/x.sh`; put `javac`/`java` on PATH rather than quoting their full paths (#576); in PowerShell, use `[System.Net.WebClient]::new()` with `.Headers.Add("Content-Type","application/json")` and `.UploadString("http://localhost:9876/api/cmd", '{"cmd":"..."}')` (A0F #692); keep bridge calls in Bash.
5. **Evidence:** as listed.

### 19. A held island never takes queued edits, even across a restart; building elsewhere was the only workaround
1. **Goal:** raise a line's generator to 400 kPa after building the whole line (A28), and reset a held line's generator (A0F).
2. **What went wrong:** the GUI showed `WAITING: configuration event / HELD: ...` and the edit never applied. Removing blocks did not dissolve the island, which survived save, restart and reload with `committed_tick` frozen (A28 #626-#680, report #1062; A0F #1018-#1025, #1216).
3. **Cost:** A28 about 10 minutes and one extra world; A0F about 5 minutes.
4. **Fix (procedural):** build the generator and pipes first, set and **Apply** the generator pressure while the island has no second boundary ("Settings accepted at the current simulation event"), and only then place the tank (A28 #681-#699, report #1062). Otherwise build in a fresh spot (`setblock 20 -59 20 ...`) or a fresh world (A28 #860-#884). Wait about 3 to 12 s after Apply before the screenshot.
5. **Evidence:** A28 #626-#699; A0F #1018-#1025.

### 20. Cycling the composition preset overshot and committed the wrong carrier
1. **Goal:** choose the water slurry composition on a generator (AE0).
2. **What went wrong:** the preset button (`poke.ps1 gui 314 137`) cycles through many presets; AE0 clicked it 1, 1, 2, 3 and then 12 more times (#734-#823), and a crude preset (`WTI Light Export`) got applied by mistake (AE0 report, A74 #807).
3. **Cost:** about 4 minutes, 20+ clicks.
4. **Fix:** A0F clicked `gui 314 137` once on a fresh generator, which gave the water slurry preset (#851-#855). Take a screenshot after **every** single cycle before pressing Apply (`gui 336 218`).
5. **Evidence:** AE0 #734-#830; A0F #851.

### 21. The scripted client pilot waited without limit on a state that never arrived
1. **Goal:** place a scenario with `/function` and wait for `devices=800` (A4B).
2. **What went wrong:** because of #4 the devices never appeared, and the rig kept waiting. A 600 s `until grep` timed out and was moved to the background (#760-#761); the client stayed orphaned until it was killed by PID (#776-#777).
3. **Cost:** about 11 minutes, plus a coordinator intervention.
4. **Fix:** give every wait a limit (15 s for a chat reply, 30 s for device registration, a fixed warm-up and window), fail on error replies, kill the game JVMs of the worktree on any error, and never leave a client running across another Gradle invocation (A4B #867, #884-#887).
5. **Evidence:** A4B #753-#887.

### 22. Opening an old-format world crashes the client
1. **Goal:** show that an old format-2 world is refused (AE6).
2. **What went wrong (expected):** the server refused the world at start and the client closed with a crash report. Vanilla had already rewritten `level.dat` and the region files during the aborted start (#1244-#1263).
3. **Cost:** a client restart. AE6 restored the world from a backup taken beforehand.
4. **Fix:** do that check last, back up the save folder first, and restore it afterwards.
5. **Evidence:** AE6 #1231-#1263.

---

## Techniques that worked

* **Enter control mode first, on the title screen:** `enter_control_mode`. After a client restart, call it again (A28 #814, #952).
* **Create World entirely through the bridge (A42, AE0, A74, A28):** Singleplayer is `click_button_index 0` (goes straight to `CreateWorldScreen` when there are no saves). On the Game tab, `click_button_index 5` twice sets Game Mode to Creative. `switch_tab {"index":1}` opens the World tab (A42 #833; AE0 used `{"tab_index":1}`, #550, which also switched). On the World tab, `click_button_index 3` once sets World Type to Superflat, and `click_button_index 8` once turns Generate Structures off (A42 #852, AE6 #956). `click_button_index 1` is **Create New World**. Widget layout: Game tab `4 EditBox` (world name, 109,48); World tab `3 CycleButton` (58,35 = world type), `4 Button` (Customize), `8 CycleButton` (324,108, structures), `10 CycleButton` (324,132, bonus chest); `1`/`2` are Create/Cancel at y = 214 (A0F #748, A42 #836).
* **World load wait:** `wait {"seconds":15}` (A42 #857) or `sleep 20-25` then `get_screen_buttons` → `{"error":"no screen"}` means in-world (A28 #555). Better: wait for the log line `fluid_world status=LOADED` in the Gradle log (AE6 #972). Player spawns on superflat at y = -60, so devices go at y = -60 on the ground (A42, AE6) or -59 one block up (A0F/AE0/A74/A28).
* **World setup commands via chat:** `/gamerule doDaylightCycle false`, `/difficulty peaceful`, `/time set day` (A42 #880-#896, A0F #1098, A74 #932).
* **Placing equipment:** `/setblock X Y Z createcheme:<id>[facing=east]` and `/fill` for pipe runs: `/fill -7 -60 0 -5 -60 0 createcheme:fluid_pipe` (A42 #902). Block ids: `fluid_generator`, `fluid_pipe`, `inline_filter`, `pressure_control_valve`, `fluid_pump`, `fluid_reservoir`, `fluid_void` (A74 #780; `facing` variants in `blockstates/*.json`). Remove with `setblock ... minecraft:air` or `fill ... air`. Vertical lines: filter and pump need `facing=up` (A28 report).
* **Positioning and aiming:** `/tp @s X Y Z yaw pitch`, or `/tp @s X Y Z facing x y z` (see #13); `set_view_angle {"yaw":0,"pitch":20}` with the MCP tools. Overview shot: `tp @s 2 -55 -6 0 25` (A0F #1193).
* **Opening a block GUI:** `right_click` (`via screen_mouseHandler`) or `use_item`; closing it: `close_screen` (`keyPressed(ESCAPE)`), `press_key escape`, or the screen's Close button (`FluidDeviceScreen` widget 22 via `click_button_index 22`, A28; or `poke.ps1 gui 389 219`, A74).
* **FluidDeviceScreen buttons:** `click_button_index 21` is **Apply** (A42 #1014; real click `gui 336 218`); `gui 210 218` opens or toggles the Solids page; `gui 229 218` is Recover solids on the filter screen; `gui 314 137` cycles the composition preset; widget 22 is the close button. Pressing Apply through `click_button_index 21` fires the real `onPress`, which works; only text entry needs Robot.
* **Waiting for the engine's 5 s presentation:** after Apply or a first open, `wait 5` to `wait 6` and then the screenshot (A42 #1054, AE6 #1099). For the cadence evidence, 6 screenshots 2 s apart, cropped and stacked with `Stack.java` (A42 #942-#967). For "never immediately", screenshot at 0 s, 1 s and 5 s after Apply (A42 #1014-#1025).
* **Proof from the log:** `grep -c "status=HELD" run/logs/latest.log`, `grep "fluid_island=" ...`, and the chat echo `[CHAT] ...` for command replies; copy `latest.log` next to the screenshots.
* **Save and reload:** `pause_game` → `click_button_index 8` (Save and Quit to Title) → wait ~5-12 s (log `Stopping server`) → `click_button_index 0` (Singleplayer, now the world list) → select the first entry (click it) → `click_button_index 1` (Play Selected World) (A28, AE6).
* **Clean client exit:** Save and Quit, then `click_button_index 6` on the title screen (Quit Game). Gradle then ends with `BUILD SUCCESSFUL` (A42 #1131-#1135). Fallback: `save-all` via chat, then `Stop-Process` on the java PID with a "Minecraft" `MainWindowTitle` (A0F #918-#927, AE0 #889-#892).
* **Hotbar and inventory:** `press_key {"key":"e"}` opens the inventory; `press_key {"key":"1"}` selects hotbar slot 1 (A0F #1083-#1090).
* **run/options.txt:** `pauseOnLostFocus:false` at minimum; A4B's rig used `pauseOnLostFocus:false`, `onboardAccessibility:false`, `tutorialStep:none`, `skipMultiplayerWarning:true`, `joinedFirstServer:true`, `renderDistance:10`, `simulationDistance:10`, `maxFps:120`, `enableVsync:false`, `inactivityFpsLimit:"minimized"`, `fullscreen:false`, `soundCategory_master:0.0` (#867). A fresh run directory starts on `AccessibilityOnboardingScreen` otherwise (A42 #639).
* **Quick play into a prepared world (A4B):** copy a template save into `run/saves/<level>` and launch `runClient ... -PfluidClientQuickPlay=<level>`. That Gradle switch belonged to the WP5 rig and may since have been detached. The rig waits for `Dev joined the game` in `latest.log` (#867).
* **Launch command:** `JAVA_OPTS=-Xshare:off ./gradlew.bat runClient --offline --console=plain "-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Xshare:off" > <log> 2>&1` as a background task (A42 #762, AE6 #841).

---

## Exact recipes

### HTTP helper `mc.sh` (A0F #564)
```bash
#!/bin/bash
# mc.sh <cmd> '<extra json fields, object form>'  -- langyo/minecraft-mod-mcp HTTP API.
cmd="$1"; extra="${2:-}"
if [ -z "$extra" ] || [ "$extra" = "{}" ]; then
  body="{\"cmd\":\"$cmd\"}"
else
  inner="${extra#\{}"; inner="${inner%\}}"; body="{\"cmd\":\"$cmd\",$inner}"
fi
printf '%s' "$body" > /tmp/ff-body.json
curl -s -m "${MC_TIMEOUT:-60}" -X POST -H 'Content-Type: application/json' --data-binary @/tmp/ff-body.json http://localhost:9876/api/cmd
```

### Chat command helper `cmd.sh` (A0F #567), the only way commands reach the server
```bash
#!/bin/bash
# cmd.sh '<command without leading slash>'
post() { printf '%s' "$1" > /tmp/ff-cmd.json; curl -s -m 30 -X POST -H 'Content-Type: application/json' --data-binary @/tmp/ff-cmd.json http://localhost:9876/api/cmd > /dev/null; }
post '{"cmd":"open_chat"}'; sleep 0.4
printf '{"cmd":"type_text","text":"/%s"}' "$1" > /tmp/ff-cmd.json
curl -s -m 30 -X POST -H 'Content-Type: application/json' --data-binary @/tmp/ff-cmd.json http://localhost:9876/api/cmd > /dev/null; sleep 0.4
post '{"cmd":"press_key","key":"enter"}'; sleep 0.6
echo "ran: /$1"
```
MCP-tool equivalent (A42 #880-#918): `open_chat {}` then `type_text {"text":"/setblock 2 -60 0 createcheme:fluid_reservoir","press_enter":true}`, one pair per command.

### Screenshot plus flatten (A0F #570, #554)
```bash
bash build/mcp/mc.sh screenshot_to_file '{"path":"../documentation/screenshots/<batch>/<name>.png"}'
java -cp build/flatten Flatten documentation/screenshots/<batch>/<name>.png
```
```java
public class Flatten { public static void main(String[] a) throws Exception { for (String p : a) {
  var in = javax.imageio.ImageIO.read(new java.io.File(p));
  var out = new java.awt.image.BufferedImage(in.getWidth(), in.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
  for (int y = 0; y < in.getHeight(); y++) for (int x = 0; x < in.getWidth(); x++) out.setRGB(x, y, in.getRGB(x, y) & 0xFFFFFF);
  javax.imageio.ImageIO.write(out, "png", new java.io.File(p)); } } }
```

### Full session: fresh creative superflat world, line, GUI edit, engine wait, quit (HTTP; A28/A0F/A42 combined)
```bash
printf 'pauseOnLostFocus:false\nonboardAccessibility:false\n' > run/options.txt   # before launch
# launch runClient in background; then (background) wait for the bridge:
until curl -s -m 3 http://localhost:9876/api/status | grep -q minecraft-mod; do sleep 3; done
bash build/mcp/mc.sh enter_control_mode
bash build/mcp/mc.sh click_button_index '{"index":0}'      # Singleplayer -> CreateWorldScreen (no saves)
bash build/mcp/mc.sh click_button_index '{"index":5}'      # Game Mode: Survival -> Hardcore
bash build/mcp/mc.sh click_button_index '{"index":5}'      # -> Creative  (screenshot to confirm; else poke.ps1 gui 213 86 twice)
bash build/mcp/mc.sh switch_tab '{"index":1}'              # World tab
bash build/mcp/mc.sh click_button_index '{"index":3}'      # World Type -> Superflat (else poke.ps1 gui 133 45)
bash build/mcp/mc.sh click_button_index '{"index":1}'      # Create New World
# wait ~20-25 s; get_screen_buttons -> {"error":"no screen"} means in world
bash build/mcp/cmd.sh 'gamerule doDaylightCycle false'
bash build/mcp/cmd.sh 'difficulty peaceful'
bash build/mcp/cmd.sh 'setblock 0 -59 0 createcheme:fluid_generator[facing=east]'
bash build/mcp/cmd.sh 'setblock 1 -59 0 createcheme:fluid_pipe'
bash build/mcp/cmd.sh 'setblock 2 -59 0 createcheme:inline_filter[facing=east]'
bash build/mcp/cmd.sh 'setblock 3 -59 0 createcheme:fluid_pipe'
bash build/mcp/cmd.sh 'setblock 4 -59 0 createcheme:fluid_reservoir'
bash build/mcp/cmd.sh 'tp @s 2.5 -59 2.5 facing 0.5 -58.5 0.5'   # aim at the generator
bash build/mcp/mc.sh right_click                           # opens FluidDeviceScreen
bash build/mcp/mc.sh enumerate_widgets | head -c 200       # verify "screen":"FluidDeviceScreen"
# type pressure (PowerShell):  poke.ps1 gui 314 82 ; java -cp build/flatten Poke clear ; java -cp build/flatten Poke paste 400000
bash build/mcp/mc.sh click_button_index '{"index":21}'     # Apply (or poke.ps1 gui 336 218)
# wait 5-6 s for the engine bucket, then screenshot_to_file + Flatten
bash build/mcp/mc.sh click_button_index '{"index":22}'     # close the device screen
# pause + save + quit (MCP tool pause_game, or Robot ESC):
bash build/mcp/mc.sh pause_game
bash build/mcp/mc.sh click_button_index '{"index":8}'      # Save and Quit to Title
# ~10-12 s later on TitleScreen:
bash build/mcp/mc.sh click_button_index '{"index":6}'      # Quit Game
```

### Reload the saved world (A28 #814-#828; AE6 #1156-#1170)
```bash
bash build/mcp/mc.sh enter_control_mode
bash build/mcp/mc.sh click_button_index '{"index":0}'      # -> SelectWorldScreen
# select the first entry with ONE click: poke.ps1 gui 213 62   (MCP: click {"x":400,"y":135})
bash build/mcp/mc.sh click_button_index '{"index":1}'      # Play Selected World (MCP: click {"x":266,"y":394})
```

### Robot foreground and click helper `poke.ps1` (A0F #689), plus `Poke.java` actions
```powershell
# poke.ps1 gui X Y | hover X Y | key NAME | clear | paste TEXT
$sig = @'
using System; using System.Runtime.InteropServices;
public class Win {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr pid);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern bool SetFocus(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
'@
if (-not ("Win" -as [type])) { Add-Type -TypeDefinition $sig }
$proc = Get-Process | Where-Object { $_.MainWindowTitle -like "*Minecraft*" } | Select-Object -First 1
$h = $proc.MainWindowHandle
$foreThread = [Win]::GetWindowThreadProcessId([Win]::GetForegroundWindow(), [IntPtr]::Zero)
$mine = [Win]::GetCurrentThreadId()
[Win]::AttachThreadInput($mine, $foreThread, $true) | Out-Null
[Win]::ShowWindow($h, 9) | Out-Null; [Win]::BringWindowToTop($h) | Out-Null
[Win]::SetForegroundWindow($h) | Out-Null; [Win]::SetFocus($h) | Out-Null
[Win]::AttachThreadInput($mine, $foreThread, $false) | Out-Null
Start-Sleep -Milliseconds 500
$pt = New-Object Win+POINT; [Win]::ClientToScreen($h, [ref]$pt) | Out-Null
if ($args[0] -eq "gui" -or $args[0] -eq "hover") {
  $x = [int]($pt.X + [int]$args[1] * 4 / 3); $y = [int]($pt.Y + [int]$args[2] * 4 / 3)   # 4/3 = this host's DPI factor
  & java -cp "$root\build\flatten" Poke ($(if ($args[0] -eq "gui") {"click"} else {"move"})) $x $y
} else { & java -cp "$root\build\flatten" Poke @args }
```
`Poke.java` cases: `click X Y` (mouseMove, 200 ms, BUTTON1 press/release), `move X Y`, `key NAME` (`KeyEvent.VK_<NAME>`), `clear` (Ctrl+A, Backspace), `paste TEXT` (sets the system clipboard, 250 ms, Ctrl+V; A0F #838, #873). Run it with `powershell -NoProfile -ExecutionPolicy Bypass -File build\mcp\poke.ps1 gui 314 82` (AE0 #665).

Field-fill function used for the Solids page (A0F #879):
```powershell
function Field($x,$y,$text) {
  & "$root\build\mcp\poke.ps1" gui $x $y | Out-Null; Start-Sleep -Milliseconds 350
  & "$root\build\mcp\poke.ps1" clear   | Out-Null; Start-Sleep -Milliseconds 250
  & "$root\build\mcp\poke.ps1" paste $text | Out-Null; Start-Sleep -Milliseconds 350
}
Field 314 82 "400000"                          # pressure (Pa)
& "$root\build\mcp\poke.ps1" gui 210 218       # Solids page
Field 314 144 "10"; Field 128 173 "createcheme:demo_particle"; Field 285 173 "100"; Field 367 173 "1"
& "$root\build\mcp\poke.ps1" gui 336 218       # Apply
```

### Tab-focus alternative (A42 #994-#1000, from the solid-phase pass's `Field.java`)
`field.ps1 -Tabs 2 -Text "150000"`: focus the window, press Tab `n` times (focuses the n-th focusable widget; 2 = the pressure box of a freshly opened generator), Ctrl+A, Backspace, then clipboard and Ctrl+V.

### Engine-bucket screenshot series (A42 #942-#967, MCP tools)
```
right_click
screenshot_to_file {"path":"...\\05-tank-refresh-a.png"}; wait {"seconds":2}
screenshot_to_file {"path":"...\\05-tank-refresh-b.png"}; wait {"seconds":2}   ... through f
```
Then flatten, and crop and stack with `Stack <out> <x> <y> <w> <h> <in...>`: the view times advance only on the island's 5 s grid (125.10, 140.10, 145.10 ...).

### Robot F3 overlay (A4B #863)
```java
public class KeyTap { public static void main(String[] a) throws Exception {
  var r = new java.awt.Robot(); r.setAutoDelay(60);
  r.keyPress(java.awt.event.KeyEvent.VK_F3); r.keyRelease(java.awt.event.KeyEvent.VK_F3); } }
```
`f3.ps1`: `ShowWindow(h,9)`, `SetForegroundWindow(h)`, 500 ms, then `java -cp $PSScriptRoot KeyTap`.

### Scripted command with reply check (A4B #867, Node)
```js
async function command(c, expect) {                       // e.g. command('/function x:y', /\[CHAT\] Running function x:y/)
  const from = size();                                     // byte offset of run/logs/latest.log
  await cmd('open_chat'); await sleep(300);
  await cmd('type_text', {text: c, press_enter: true});
  for (const deadline = Date.now() + 15000;;) {
    const t = tail(from);
    const bad = /\[CHAT\] (Unknown or incomplete command|Unknown function|Incorrect argument|That position is not loaded|An unexpected error|Could not|No player was found|You do not have permission)[^\n]*/.exec(t);
    if (bad) throw new Error(`command ${c} failed: ${bad[0]}`);
    const good = expect.exec(t); if (good) return good[0];
    if (Date.now() > deadline) throw new Error(`no reply to ${c} within 15 s`);
    await sleep(250);
  }
}
```

---

## Open problems never solved

1. **Typing through the bridge itself.** No agent got `type_text`, `paste_text` or `press_key` text into an `EditBox` of a container screen or the Create World name box. Every text entry used Robot (click or Tab, clear, clipboard paste). `paste_text` was never tried against an EditBox; it is worth one test. The world name was never set (AE6 kept "New World", #933).
2. **CycleButton semantics.** Whether `click_button_index` (`manual_cycle`) really applies Game Mode is unresolved: A74 saw Allow Commands stay `OFF`, while A42 and AE0 got working commands from `manual_cycle` alone. No agent read the resulting `level.dat` of a bridge-created world to settle it. The safe path is still a real click plus a screenshot.
3. **Reliable game-mode readback.** `get_player_info.gamemode` and `get_world_info.gametype` always say survival; there is no bridge-side way to confirm Creative except the chat reply in the log.
4. **`execute_command`** stays unusable (client-side KubeJS dispatch); there is no server-side command API in the bridge v0.3.0.
5. **In-world ESC and F3 via the bridge.** `press_key escape` does not open the pause menu from the world, and the bridge's F3 key does not toggle the overlay. `pause_game` covers the first; F3 needs Robot.
6. **Timing an edit to hit the `Queued ... at tick N` reply.** The bridge's latency could not time an edit within about 2 ticks of a bucket, so that path was left to GameTests (A42 review §5, #1159).
7. **The Robot path depends on DPI and window position** (the 4/3 factor, `ClientToScreen` origin), and on the window being foregroundable. It was never made independent of the host.
8. **Held islands in the world** (#19) could not be cleared from the GUI or by removing blocks; the only way out was building elsewhere or using a new world. This is a mod defect, recorded here only because it drove the in-game procedure.

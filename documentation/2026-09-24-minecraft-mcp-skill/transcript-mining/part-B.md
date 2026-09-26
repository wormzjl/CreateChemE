# Part B: MCP bridge difficulties mined from the solid-phase sessions

Sources (all under `C:/Users/wormz/.claude/projects/D--Minecraft-Modding-1-21-CreateChemE--claude-worktrees-solid-phase-fluid-system-plan-4369b0/`):

- **SUB** = `52c95b9f-.../subagents/agent-a377346aa370aa91d.jsonl`. This is the solid-phase GUI pass of 2026-09-22 (bridge v0.3.0 jar, NeoForge 1.21.1, HTTP API on `localhost:9876`). Line numbers are JSONL line numbers. Timestamps: client launch 09:50Z, second client in control 10:03Z, typing experiments 10:10 to 10:20Z, world reload 10:41Z, report 11:09Z.
- **MAIN** = `52c95b9f-3221-4e12-9346-67c54bcfffba.jsonl`, the orchestrating session. It includes pasted subagent reports: solid-phase filter fix [1191], hydraulic row scale [1291], pump shutoff [1342], junction donor switch [1387], phantom trace [1447], full-tank solids event [1530], elevated line [1623], dead-headed line [1676], WP3 [3339], WP4 [3448], WP5 [3457]; plus orchestrator messages [3512] and [3605].

All agents drove the bridge over **HTTP** (`POST http://localhost:9876/api/cmd`, body `{"cmd":"<name>", ...args}`), not the MCP stdio server.

---

## Difficulties

### 1. The stdio JSON-RPC route recommended in the brief returned nothing for `tools/call`
1. **Goal:** call bridge tools from Bash. The brief (MAIN 805) said to pipe `initialize`, `notifications/initialized` and one `tools/call` into `npx -y minecraft-mod-mcp mcp`.
2. **What went wrong:** the process printed only the `initialize` response and `[minecraft-mcp] Found mod: unknown-unknown (pid 13824) on port 9876`. It never answered `tools/list` or `tools/call`, even with `sleep 3` before the call and `sleep 20` after it (SUB 234-260). `npx -y minecraft-mod-mcp status` did work and printed "Minecraft mod connected".
3. **Cost:** 6 attempts across 3 helper scripts (`call.sh`, `raw.sh` twice), about 5 minutes.
4. **What worked:** skip MCP stdio. `curl -s http://localhost:9876/debug` serves an HTML debug page that names the endpoints `/api/status`, `/api/cmd`, `/api/calls`, `/api/events` and `/api/screenshot` (SUB 263-270). The page's JS posts `{cmd:<name>, ...extraFields}` to `/api/cmd`. Every later agent used this route. The WP-era report says: "No `.mcp.json` / `npx minecraft-mod-mcp mcp` stdio session. The bridge's HTTP API is the same surface" (MAIN notification [1291]).
5. **Evidence:** SUB 216-274; MAIN 805 (brief).

### 2. Malformed JSON from the shell helper
1. **Goal:** a generic helper `mc.sh <cmd> '<json extra>'`.
2. **What went wrong:** `{"error":"com.google.gson.stream.MalformedJsonException: Use JsonReader.setLenient(true) ... line 1 column 94"}`. The cause was the bash default `"${2:-{}}"`: it parses as `${2:-{}` followed by a literal `}`, so every extra argument got an extra closing brace. Stripping CRs with `tr -d '\r'` did not help.
3. **Cost:** 5 attempts, about 4 minutes (SUB 286-313).
4. **What worked:** strip the braces explicitly and send the body from a file (see recipe R1): `inner="${extra#\{}"; inner="${inner%\}}"; body="{\"cmd\":\"$cmd\",$inner}"; printf '%s' "$body" > /tmp/mc-body.json; curl ... --data-binary @/tmp/mc-body.json`. Direct `curl -d '{"cmd":"press_key","key":"escape"}'` always worked.
5. **Evidence:** SUB 274-313.

### 3. Every input command refused until control mode is on
1. **Goal:** first status and screenshot calls.
2. **What went wrong:** `{"error":"not in control mode","hint":"Enter control mode via ESC > MCP Take Over"}` (SUB 278). The pause menu in this build has no "MCP Take Over" button (MAIN [1191]). The first agent (filter-fix, [1191]) believed control mode could not be entered before a world was loaded, so it drove the title and create-world screens with real `java.awt.Robot` input (`poke.ps1` using `AttachThreadInput`).
3. **Cost:** 1 call in SUB. The filter-fix agent spent many whole-desktop screenshots working out an input path (it says it deleted them).
4. **What worked:** `{"cmd":"enter_control_mode"}` sent over HTTP directly, even from the **title screen**. It replies `{"control_mode":true,"platform":"internal","hook":false}` (SUB 282-284, 486-487). The row-scale agent confirmed: "`enter_control_mode` works from the title screen, so no `java.awt.Robot` was needed to reach a world" (MAIN [1291]). `enumerate_widgets` works without control mode.
5. **Evidence:** SUB 278-284, 487; MAIN [1191], [1291].

### 4. Opening chat before control mode wedges the client
1. **Goal:** type a command.
2. **What went wrong:** "Calling `open_chat` *before* control mode leaves the client in a broken `ChatScreen` that swallows every mouse event; a real ESC clears it" (MAIN [1191]).
3. **Cost:** not quantified.
4. **What worked:** always call `enter_control_mode` first. If the client is already wedged, send a real ESC with Robot.
5. **Evidence:** MAIN notification [1191] §9.

### 5. `click` (GLFW cursor injection) does not reach widgets; `inject_click` and `kill_minecraft` are unknown
1. **Goal:** click "Singleplayer" on the title screen, then click an EditBox.
2. **What went wrong:** `{"cmd":"click","x":213,"y":102}` replied `{"clicked":true,"method":"glfw","gui":[213,102]}` but the screen stayed `TitleScreen` (SUB 326-327). Clicking an EditBox at (128,172) did not focus it (SUB 595). `inject_click` is listed on the debug page but replies `{"result":"unknown: inject_click"}` (SUB 602-603). `kill_minecraft` replies `{"result":"unknown: kill_minecraft"}` (SUB 457-458).
3. **Cost:** 3 calls.
4. **What worked:** `click_button_index {"index":N}`, which replies `{"clicked":true,"index":0,"via":"onPress.onPress","class":"Button"}`. Indices come from `enumerate_widgets`. To stop the client use PowerShell: `Get-Process java | ? MainWindowTitle -like "Minecraft*" | Stop-Process -Force` (SUB 1238, 1334).
5. **Evidence:** SUB 326-331, 457-461, 602-603; final report §7 item 4.

### 6. Create World: game mode and "Allow Commands" do not end up as expected
1. **Goal:** a Creative superflat world with cheats on.
2. **What went wrong:** `click_button_index` on a `CycleButton` replies `"via":"manual_cycle","newIdx":...`. The junction agent found that this "moves the widget's internal index **without firing its value change**": "Allow Commands" reported `newIdx` flipping while the label stayed `OFF` through three clicks (MAIN [1387]). In SUB the first world was created with commands effectively off (SUB 341-372; screenshots 01b-01d show the toggling). The second world needed an extra index-7 click after the screenshot (SUB 496-506). The third world needed two index-7 clicks, each checked with a screenshot (SUB 1136-1149). `get_player_info` then still reported `"gamemode":"survival"` even when Creative had been chosen.
3. **Cost:** first world lost entirely (a second client launch was also needed for difficulty 7). About 4 extra screenshot rounds per world.
4. **What worked:**
   - After every CycleButton click, take a flattened screenshot and read the label.
   - Before the screenshot, move the cursor away with `click {"x":20,"y":200}` so no hover tooltip covers the label (SUB 1143).
   - Once in the world, always run `/gamemode creative` through chat anyway.
   - The junction agent set the toggle with a real Robot click (`poke.ps1 gui X Y`, in 427x240 GUI coordinates).
   - Widget map: title `index 0` = Singleplayer; SelectWorldScreen `index 2` = Create New World, `index 1` = Play Selected World. On CreateWorldScreen tab 0, `index 5` = Game Mode (two clicks from Survival reach Creative), `index 7` = Allow Commands, `index 1` = Create New World. `switch_tab {"index":1}` opens the World tab, where `index 3` = World Type (one click = Superflat).
5. **Evidence:** SUB 330-372, 490-511, 1136-1156; MAIN [1387].

### 7. `pauseOnLostFocus` pauses the game and blocks everything
1. **Goal:** get into the world and close the pause menu.
2. **What went wrong:** after the world loaded, the screen was always `PauseScreen`.
   - `press_key escape`, `click_button_index 0` ("Back to Game"), `pause_game {"paused":false}` (replied `{"paused":true,"method":"setScreen(PauseScreen)"}`) and `close_screen` (replied `"screen_closed":true` while the screen stayed PauseScreen) all failed.
   - Forcing the window to the foreground with `SetForegroundWindow` also failed (SUB 389-416).
   - The player position never changed, because `/tp` also failed (see difficulty 8).
3. **Cost:** about 12 calls, then a kill and relaunch of the client. The whole first client session, about 13 minutes (09:50 to 10:03Z), was lost to difficulties 6-8.
4. **What worked:** stop the client, run `sed -i 's/^pauseOnLostFocus:true/pauseOnLostFocus:false/' run/options.txt` **before** launch, then relaunch (SUB 453-471). Every later agent lists `pauseOnLostFocus:false in run/options.txt` as a prerequisite.
5. **Evidence:** SUB 389-471; final report §7 item 5.

### 8. `execute_command` never reaches the server command dispatcher
1. **Goal:** run `/gamemode creative`, `/tp`, `/time set day`, `/setblock`.
2. **What went wrong:** the reply was `{"sent":true,"method":"kjs$runCommand"}` (later `kjs$runCommandSilent`), but the log showed `[System] [CHAT] Unknown or incomplete command, see below for error` / `/time set day<--[HERE]`. This happened even for `help`, both with and without the leading slash (SUB 376-378, 425-426, 514-519). The agent's conclusion: it runs through KubeJS `runCommand` and "reaches a dispatcher that does not contain the server commands".
   - The WP5 rig driver (MAIN 3587-3605) sent `function createcheme_bench:*` through `execute_command`. The log rejected every call, and the driver then waited about 10 minutes on a 1-hour timeout before the orchestrator intervened.
   - In that world cheats were also off (`allowCommands 0` in the quick-played template's level.dat), so the chat route would have been refused too.
3. **Cost:** about 8 attempts in SUB (both slash forms, and after enabling cheats through Open to LAN, SUB 429-443). In WP5, one wasted pilot run plus an intervention.
4. **What worked:** the chat box: `open_chat` → `type_text {"text":"/gamemode creative"}` → `press_key {"key":"enter"}`, with about 0.4 s between calls (recipe R3). Confirm by reading `run/logs/latest.log` or the runClient stdout log: `[Server thread/INFO] ... [Dev: Set the time to 1000]` or `Teleported Dev to 0.500000, -59.000000, 0.500000` (SUB 529-538). `press_enter` is not a command; `press_key {"key":"enter"}` is (MAIN [1291]). For WP5 the orchestrator ordered (MAIN 3605):
   - set `allowCommands:1b` and GameType creative in the template world's level.dat;
   - after every bridge command, read the chat reply from the log and fail fast on "Unknown or incomplete command";
   - bound every wait.
5. **Evidence:** SUB 376-378, 425-443, 514-538; MAIN 3587-3605, [3457] ("The bridge's `execute_command` never reaches the server; the first client pilot was refused and stopped").

### 9. Open to LAN with cheats did not rescue the first world
1. **Goal:** enable cheats in the running world.
2. **What went wrong:** Pause `index 6` opened ShareToLanScreen. `index 1` (Allow Cheats cycle) and then `index 3` (Start LAN World) gave `Local game hosted on port [58826]`. The next command was still sent through `execute_command` and still failed. The chat attempt afterwards also left the gamemode at survival, most likely because the game was still paused (difficulty 7).
3. **Cost:** 4 calls.
4. **What worked:** nothing in that session. The agent restarted with the pause fix and created a new world with commands on.
5. **Evidence:** SUB 429-449.

### 10. Aiming at a block to open its GUI
1. **Goal:** open a fluid generator's `FluidDeviceScreen`.
2. **What went wrong:** the agent teleported in front of the block at eye level (`tp @s 0.5 -59 0.5 0 29`, block at `0 -59 2`). Then `right_click` replied `{"right_click":true,"via":"screen_mouseHandler"}` and `use_item` replied `{"use_item":true,"method":"startUseItem"}`, but `enumerate_widgets` said `{"error":"no screen"}` both times (SUB 558-570).
3. **Cost:** 3 attempts.
4. **What worked:** stand **on top of** the block and look straight down, then `use_item`: `/tp @s 0.5 -58 2.5 0 89` (block at `0 -59 2`, player feet one block above, pitch 89), `sleep 2`, `{"cmd":"use_item"}`. The screen opens and `enumerate_widgets` reports `FluidDeviceScreen` (SUB 577-578). This pattern then worked every time for generators, pipes, filters and reservoirs (SUB 812-904, 1070-1084, 1283). The junction agent reports that `right_click` does open a block screen when aimed by teleport with explicit yaw and pitch, and that `use_block`, `interact` and `use` are unknown commands (MAIN [1387]).
5. **Evidence:** SUB 551-582; MAIN [1387].

### 11. Typing into a container screen's EditBox is impossible through the bridge (experiments a3 to a9)
1. **Goal:** type `createcheme:demo_particle`, `100`, `1` and a solids percentage into the generator's Solids editor EditBoxes.
2. **What went wrong, attempt by attempt** (each checked with a screenshot and each showing no change):
   - a3: `click {"x":128,"y":172}` then `type_text` replied `{"result":"createcheme:demo_particle"}`, but the field stayed empty (SUB 595).
   - a3b: `inject_click` is unknown (SUB 602).
   - a4: `press_key tab`, then `type_text "ZZZ"` (SUB 625).
   - a5: `paste_text` (SUB 632).
   - a6: `type_text "9"`, then `tab`, then `type_text "7"` (SUB 643).
   - a7: `paste_text` to set the clipboard, then `hotkey {"keys":"ctrl+v"}` (SUB 650).
   - Root cause, found by extracting the jar (renamed to `.zip` because PowerShell `Expand-Archive` refuses `.jar`) and running `javap -p -c` on `ScreenInteractionHelper`/`ReflectedInputHandler`: `type_text` and `paste_text` reflect a `(char,int)` method **declared on the current screen class**. `ChatScreen` declares `charTyped`; `AbstractContainerScreen` does not, so the characters are dropped. `hotkey ctrl+v` fails because `EditBox.keyPressed` checks `Screen.hasControlDown()`, which reads the live GLFW key state (SUB 606-670).
3. **Cost:** 7 probes plus the decompilation, about 8 minutes (10:10 to 10:18Z), about 25 tool calls.
4. **What worked:** real OS input with `java.awt.Robot`. The helper focuses the Minecraft window with `SetForegroundWindow`, presses Tab N times, clears with Ctrl+A and Backspace, puts the text on the system clipboard and presses a real Ctrl+V (`Typer.java` + `type.ps1` for a8; then `Field.java` + `field.ps1`, recipe R5). a8 (`type.ps1 -Mode paste`) was the first visible success (SUB 686-690). Later agents reused the same approach (`poke.ps1`, "clipboard paste", MAIN [1291]). The helpers are now stored in main `tools/mcp-gui-helpers/` (MAIN [4541]).
5. **Evidence:** SUB 595-690; final report §7 item 2.

### 12. The bridge's `press_key tab` moves focus by two widgets
1. **Goal:** find the tab order of the Solids editor.
2. **What went wrong:** the a9 probe (`probe-tabs.ps1`) alternated bridge `press_key tab` with a Robot paste of markers `11`, `22` and so on. The markers landed in every other field: "Each bridge `tab` advances focus by two" (the key press and the release are both dispatched) (SUB 696-713).
3. **Cost:** 1 probe.
4. **What worked:** send Tab through Robot as well. Robot Tab moves focus by exactly one widget. The focus order on the generator Solids page is `temperature, pressure, solidFraction, row0col0..2, row1col0..2, <, >, Solids, Apply, Close`.
   - The Tab count is relative to the current focus. Reopen the screen before each sequence to reset focus: `close_screen`, `use_item`, `click_button_index 19`.
   - Verified fills from a fresh editor open (SUB 726, 951, 1105): `field.ps1 -Tabs 4 "createcheme:demo_particle"`; `-Tabs 1 "100"`; `-Tabs 1 "1"`; then `-Tabs 11 "5"` sets Solids % and `-Tabs 13 "300000"` sets pressure.
   - From a fresh editor open, `-Tabs 6 "-1"` reaches row0 mass share (SUB 765).
   - On the generator's main page, `-Tabs 2 "300000"` reaches pressure (SUB 838).
   - One early try (`-Tabs 5 "5"` after the row fill, SUB 726) missed the Solids % field and needed `-Tabs 6` (SUB 737). Always confirm with a screenshot before pressing Apply.
5. **Evidence:** SUB 696-740; final report §7 item 3.

### 13. `enumerate_widgets` lists invisible and stacked widgets
1. **Goal:** find buttons on `FluidDeviceScreen`.
2. **What went wrong:** the list contains hidden EditBoxes at identical coordinates (`i` 2-5 all at `x:221,y:76`) and every paged row widget, so you cannot tell from the list which widgets are visible.
3. **Cost:** a screenshot per screen.
4. **What worked:** judge visibility from a screenshot and then use fixed indices. On the generator: `19` = Solids editor toggle, `21` = Apply, `15` = phase cycle (Liquid/Water/Vapor/Solids; one click from the default reaches the Solids page), `16` = `<`, `17` = `>`. `page.sh 17 31` clicks `>` 31 times to reach page 32/32.
5. **Evidence:** SUB 581-589, 747-806; final report §7 item 4.

### 14. Screenshots have alpha 0, and relative paths resolve against `run/`
1. **Goal:** save screenshots for the report.
2. **What went wrong:** `screenshot_to_file {"path":"build/shot.png"}` wrote `...\run\build\shot.png`, because the path resolves against `run/`. Every PNG has alpha 0 on every pixel (the RGB data is intact), so viewers show it as blank.
3. **Cost:** 2 calls.
4. **What worked:** use `"path":"../documentation/screenshots/<dir>/<name>.png"`, then run `Flatten.java` (ImageIO, `rgb & 0xFFFFFF` into `TYPE_INT_RGB`) in place (recipe R2). Screenshots are 854x480; widget coordinates are in 427x240 GUI space.
5. **Evidence:** SUB 294-320; final report §7 item 6.

### 15. `press_key escape` in the world does not open the pause menu
1. **Goal:** Save and Quit, then re-enter the world (persistence check).
2. **What went wrong:** with no screen open, `press_key {"key":"escape"}` did nothing and `enumerate_widgets` said `{"error":"no screen"}` (SUB 1033-1034).
3. **Cost:** 1 attempt, including a wasted 25 s wait.
4. **What worked:** `{"cmd":"pause_game"}` opens PauseScreen. Then `click_button_index {"index":8}` (Save and Quit to Title), then wait 25-30 s for `TitleScreen` (SUB 1036-1040, 1235, 1331).
5. **Evidence:** SUB 1033-1040.

### 16. The world list cannot be selected through the bridge
1. **Goal:** re-enter the saved world.
2. **What went wrong:** `select_list_item {"index":0}` failed with `{"error":"could not select on net.minecraft.client.gui.screens.worldselection.WorldSelectionList","methods":"... setSelected(1) ..."}` (SUB 1052-1053).
3. **Cost:** 1 attempt.
4. **What worked:** a real Robot TAB (`type.ps1 -Mode key -Text "TAB"`) focuses the list and selects the first (newest) world. Then `click_button_index {"index":1}` (Play Selected World), then wait about 40 s (SUB 1056-1067, 1280-1284). After loading, run `/gamemode creative` again through chat.
5. **Evidence:** SUB 1052-1070, 1277-1284.

### 17. `get_player_info` cannot be trusted
- It always reports `"gamemode":"survival"`, even in a creative world (SUB 373-394; MAIN [1291]: "`get_player_info`'s `gamemode` field reads `survival` in a creative world and cannot be trusted").
- `"name":""` and `world_name:"unknown"` are always empty.
- Verify state from the chat lines in the log instead.

### 18. Harness and permission friction around bridge scripting
1. **Blocked sleeps.** Foreground `sleep 25; bash shot.sh ...` and `sleep 60; curl ...` were rejected with "Blocked: sleep 25 followed by ... use Monitor with an until-loop" (SUB 967-968, 1011-1012, 1115-1116, 1262-1263). What worked:
   - short sleeps (≤20 s) inside one compound command were allowed in most calls;
   - otherwise take the screenshot without waiting;
   - to wait for the bridge, run a background until-loop: `until curl -s -m 2 http://localhost:9876/api/status | grep -q minecraft-mod; do sleep 3; done; echo "bridge up"` with `run_in_background: true` (SUB 1268).
2. **Worktree-isolation guard.** The guard refused commands it could not prove stay in the worktree. Examples: `jar xf` via a computed path (SUB 606-607), an inline `for` loop wrapping `bash cmd.sh` (SUB 1172-1173), a complex npx pipeline (SUB 242-243) and `bash -x` (SUB 306-307). What worked: write each multi-step sequence to a script file under `build/mcp/` (`build-lines.sh`, `rebuild-lineC.sh` and so on) and run `bash build/mcp/<script>.sh`.
3. **Line endings.** Scripts written with the Write tool were passed through `tr -d '\r' < x.sh > t.sh && mv t.sh x.sh` before every first run. `file` reported them as plain ASCII, so this was defensive, but harmless.

### 19. The bridge jar had disappeared from every worktree (orchestrator)
1. **Goal:** give the GUI agent a jar to copy.
2. **What went wrong:** `ls .../worktrees/*/run/mods/*.jar` found nothing, because the worktree that held it had been removed. The npm cache (`npm-cache/_npx/*/node_modules/minecraft-mod-mcp`, version 0.3.0) contains no jar (MAIN 735-750).
3. **Cost:** 2 searches plus one question to the user (the user answered "re-download").
4. **What worked** (MAIN 768-791):
   - list the release assets: `curl -sL https://api.github.com/repos/langyo/minecraft-mod-mcp/releases/tags/v0.3.0`;
   - download: `curl -sL -o run/mods/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar https://github.com/langyo/minecraft-mod-mcp/releases/download/v0.3.0/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar`, then check the size is 912248 bytes and that it contains `META-INF/neoforge.mods.toml`;
   - write `.mcp.json` with `{"mcpServers":{"minecraft-mod-mcp":{"command":"cmd","args":["/c","npx","-y","minecraft-mod-mcp"]}}}`;
   - later worktrees copied the jar from that worktree's `run/mods` (MAIN 3229).
5. **Evidence:** MAIN 719-791, 3224-3235.

### 20. A HELD fluid island silently ignores GUI edits (this blocked in-game check (c))
1. **Goal:** apply generator settings on filter lines.
2. **What went wrong:** after Apply there was no "Settings accepted" acknowledgement and nothing changed. The log repeated `fluid_island=22 ... status=HELD: Substep refinement exhausted ...` every 5 s. Removing the blocks with `setblock ... air` did not clear it, and neither did Save and Quit. "The world's topology ledger is jammed by the HELD islands" (SUB 911-1132).
3. **Cost:** 5 filter topologies across 2 worlds, about 25 minutes. One new world was created only to get a clean ledger (SUB 1133-1156).
4. **What worked:** nothing in-game; this is a solver defect (finding F1). Detect it early with `grep -o "fluid_island=[0-9]* .*status=HELD" build/runclient2.log | sort -u` before spending GUI time. Use a fresh world when the ledger is jammed.
5. **Evidence:** SUB 911-1200; final report F1-F3.

---

## Techniques that worked

- **The HTTP API is the whole surface.** `GET /api/status` returns `{"ok":true,"type":"minecraft-mod",...,"port":9876}` when the bridge is up, about 20-45 s after runClient starts. Commands go to `POST /api/cmd`. `/debug` is an HTML page whose `<option>` list names commands. Some listed names are stale, for example `inject_click`, and the working set is larger than the list: `switch_tab`, `click_button_index`, `use_item`, `pause_game`, `close_screen`, `screenshot_to_file`, `get_screen_buttons`, `select_list_item`, `paste_text`.
- **`enter_control_mode` first**, from the title screen, before any other input.
- **Drive all menu screens with `click_button_index` and `switch_tab`.** This covers world creation, pause, save and quit, and Open to LAN; widget indices are listed under difficulty 6 and in recipe R4.
- **Run commands through chat only** (recipe R3). Confirm each one in the log.
- **Build equipment with `/setblock` through chat, one block per command.** Lines run along +Z at `y=-59` on superflat, for example `setblock 0 -59 2 createcheme:fluid_generator` through `setblock 0 -59 7 createcheme:fluid_reservoir`. `/setblock` goes through `FluidDeviceBlock.onPlace` → `FluidWorldAuthority.place`, so devices get world defaults (MAIN [1342]).
  - Items come from `give @s createcheme:recovered_solids`.
  - Clean up with `setblock X -59 Z air` and `kill @e[type=item,distance=..40]`.
- **Position the player to open a device:** `tp @s <x+.5> -58 <z+.5> 0 89` (stand on the block, look down), then `use_item`. For an overview shot: `tp @s 0.5 -59 -1.5 0 25`.
- **Wait for the engine's roughly 5-second update:**
  1. Apply (`click_button_index 21`).
  2. `sleep 3-15`.
  3. `close_screen`, then re-teleport and `use_item` to reopen.
  4. Switch page (`click_button_index 15`).
  5. Screenshot.

  An acknowledgement reads `Settings accepted at the current simulation event.` Under the later engine-owned presentation (WP3), a first open shows `Waiting for the engine` until the next bucket (MAIN [3343]).
- **Hotbar and inventory:** `press_key {"key":"e"}` opens the inventory. `press_key "2"` then `"1"` re-selects a slot so its name shows (SUB 1209-1216).
- **Gradle exclusivity:** before relaunching, stop the client with PowerShell and check `Get-Process java` for idle Gradle daemons.
- **Log-based verification:** `grep -c "status=HELD" run/logs/latest.log` and `grep -o "fluid_island=[0-9]*" ... | sort -u` give fast ground truth that no GUI read can.
- **Reading the bridge itself:** copy the jar to `.zip`, run `Expand-Archive`, then `javap -p -c` on `xyz/langyo/minecraft/mcp/common/*.class`. This quickly settles which commands exist and how they dispatch.
- **Dedicated server for measurement** (MAIN 3512): it needs `run/eula.txt` with `eula=true`, which the owner accepted. Run `JAVA_OPTS=-Xshare:off ./gradlew.bat runServer` and drive it through stdin (`/forceload`, `/function`, `/jfr start`, `/stop`). The console has full permission, unlike an integrated-client player in a no-cheats world.

---

## Exact recipes

### R1. HTTP command helper (`build/mcp/mc.sh`, final working version, SUB 309)
```bash
#!/bin/bash
# mc.sh <cmd> '<extra json fields, object form>'
cmd="$1"
extra="${2:-}"
if [ -z "$extra" ] || [ "$extra" = "{}" ]; then
  body="{\"cmd\":\"$cmd\"}"
else
  inner="${extra#\{}"
  inner="${inner%\}}"
  body="{\"cmd\":\"$cmd\",$inner}"
fi
printf '%s' "$body" > /tmp/mc-body.json
curl -s -m "${MC_TIMEOUT:-60}" -X POST -H 'Content-Type: application/json' --data-binary @/tmp/mc-body.json http://localhost:9876/api/cmd
```

### R2. Screenshot and flatten (`shot.sh` + `Flatten.java`, SUB 224, 316)
```bash
bash build/mcp/mc.sh screenshot_to_file "{\"path\":\"../documentation/screenshots/<dir>/$name.png\"}"   # relative to run/
"/c/Program Files/Java/jdk-21.0.11/bin/java.exe" -cp build/flatten Flatten "documentation/screenshots/<dir>/$name.png"
```
```java
BufferedImage in = ImageIO.read(new File(path));
BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_RGB);
for (int y = 0; y < in.getHeight(); y++) for (int x = 0; x < in.getWidth(); x++) out.setRGB(x, y, in.getRGB(x, y) & 0xFFFFFF);
ImageIO.write(out, "png", new File(path));
```

### R3. Server command through chat (`build/mcp/cmd.sh`, SUB 534)
```bash
printf '{"cmd":"open_chat"}' > /tmp/mc-body.json;                         curl -s -m 30 -X POST -H 'Content-Type: application/json' --data-binary @/tmp/mc-body.json http://localhost:9876/api/cmd >/dev/null; sleep 0.4
printf '{"cmd":"type_text","text":"/%s"}' "$text" > /tmp/mc-body.json;    curl ... ; sleep 0.4
printf '{"cmd":"press_key","key":"enter"}' > /tmp/mc-body.json;           curl ... ; sleep 0.6
```
Usage: `bash build/mcp/cmd.sh "gamemode creative"`, `bash build/mcp/cmd.sh "tp @s 0.5 -58 2.5 0 89"`. Check the log for `[Server thread/INFO] ... [Dev: ...]`.

### R4. Launch → Creative superflat world with commands (second client, SUB 465-538)
```bash
sed -i 's/^pauseOnLostFocus:true/pauseOnLostFocus:false/' run/options.txt      # BEFORE launch
./gradlew.bat runClient --offline > build/runclient2.log 2>&1                   # run_in_background
until curl -s -m 2 http://localhost:9876/api/status | grep -q minecraft-mod; do sleep 3; done   # background
mc.sh enter_control_mode
mc.sh click_button_index '{"index":0}'      # Singleplayer -> SelectWorldScreen
mc.sh click_button_index '{"index":2}'      # Create New World -> CreateWorldScreen
mc.sh switch_tab '{"index":1}'; mc.sh click_button_index '{"index":3}'   # World tab, World Type -> Superflat
mc.sh switch_tab '{"index":0}'
mc.sh click_button_index '{"index":5}'; mc.sh click_button_index '{"index":5}'   # Survival -> Hardcore -> Creative
mc.sh click_button_index '{"index":7}'      # Allow Commands; SCREENSHOT (after click {"x":20,"y":200}) and repeat until label reads ON
mc.sh click_button_index '{"index":1}'      # Create New World; wait ~30-40 s until enumerate_widgets = {"error":"no screen"}
cmd.sh "gamemode creative"; cmd.sh "time set day"; cmd.sh "tp 0 -59 0"
```

### R5. Real typing into an EditBox (`Field.java` + `field.ps1`, SUB 714-717)
```powershell
# field.ps1 -Tabs <n> [-Text <value>]
$p = Get-Process java | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
[Win]::ShowWindow($p.MainWindowHandle, 9); [Win]::SetForegroundWindow($p.MainWindowHandle); Start-Sleep -Milliseconds 450
& "C:\Program Files\Java\jdk-21.0.11\bin\java.exe" -cp "$root\build\flatten" Field $Tabs $Text
```
```java
Robot robot = new Robot(); robot.setAutoDelay(40);
for (int i = 0; i < tabs; i++) { robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); }
Thread.sleep(150);
robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_CONTROL);
robot.keyPress(KeyEvent.VK_BACK_SPACE); robot.keyRelease(KeyEvent.VK_BACK_SPACE);
StringSelection s = new StringSelection(text); Toolkit.getDefaultToolkit().getSystemClipboard().setContents(s, s); Thread.sleep(250);
robot.keyPress(KeyEvent.VK_CONTROL); robot.keyPress(KeyEvent.VK_V); robot.keyRelease(KeyEvent.VK_V); robot.keyRelease(KeyEvent.VK_CONTROL);
```
Here `Win` is an `Add-Type` of `user32.dll` `SetForegroundWindow`/`ShowWindow`/`GetForegroundWindow`. The `Typer.java` variant also has `key <NAME>` mode (`KeyEvent.VK_<NAME>` via reflection), used for a real `TAB` on the world list.

### R6. Open a device and fill the generator Solids editor (SUB 723-747, 1102-1108)
```bash
mc.sh close_screen; cmd.sh "tp @s 0.5 -58 2.5 0 89"; sleep 2; mc.sh use_item; sleep 2   # FluidDeviceScreen
mc.sh click_button_index '{"index":19}'                                                  # Solids editor
```
```powershell
& field.ps1 -Tabs 4 -Text "createcheme:demo_particle"; & field.ps1 -Tabs 1 -Text "100"; & field.ps1 -Tabs 1 -Text "1"
& field.ps1 -Tabs 11 -Text "5"        # Solids (volume %)
& field.ps1 -Tabs 13 -Text "150000"   # generator pressure, Pa
```
```bash
bash build/mcp/shot.sh pre; mc.sh click_button_index '{"index":21}'; sleep 10; bash build/mcp/shot.sh applied   # Apply
```

### R7. Save and quit, re-enter (SUB 1036-1070)
```bash
mc.sh close_screen; mc.sh pause_game; sleep 2; mc.sh click_button_index '{"index":8}'; sleep 30   # -> TitleScreen
mc.sh click_button_index '{"index":0}'; sleep 3                                                     # SelectWorldScreen
powershell type.ps1 -Mode key -Text "TAB"                                                            # real TAB selects newest world
mc.sh click_button_index '{"index":1}'; sleep 40                                                     # Play Selected World
cmd.sh "gamemode creative"
```

### R8. Stop the client (SUB 1238)
```powershell
$p = Get-Process java | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1; if ($p) { Stop-Process -Id $p.Id -Force }
```

---

## Open problems never solved

1. **`execute_command` is unusable.** It never reaches the server dispatcher (KubeJS `runCommand`). Every agent fell back to chat. Nobody tried giving the integrated player op or finding another server-side path.
2. **The MCP stdio server (`npx minecraft-mod-mcp mcp`) never answered `tools/call`** on this machine. The cause was not investigated, so `.mcp.json` registration is effectively unused and the HTTP API is the only working transport.
3. **Text entry into container-screen EditBoxes still needs OS-level Robot input.** That input requires the Minecraft window to be foreground, steals the user's desktop focus, and uses the system clipboard. Tab counts are relative to the current focus and were found by trial.
4. **CycleButton toggling via `click_button_index` (`manual_cycle`) is unreliable** for "Allow Commands". Each world needed screenshot checks, sometimes a real Robot click. No deterministic sequence was established; the `newIdx` in the reply does not match the label.
5. **`select_list_item` cannot select a world.** A real TAB was the workaround. `get_player_info` gamemode, name and world name stay wrong.
6. **Opening a block GUI from eye level:** `right_click` and `use_item` failed from the side (pitch 29, 2 blocks away) in SUB. The standing-on-top, pitch-89 trick worked. One later agent reports `right_click` works when aimed by teleport with yaw and pitch, so the exact aiming rule was never pinned down.
7. **Check (c) in-game filter capture, recovery and "filter clogged" was never verified.** Every island containing an inline filter stayed HELD (solver finding F1). HELD islands ignore Apply and survive block removal and reload. A fresh world is the only reset.
8. **Harness sleep blocking** made fixed-delay waits for the engine awkward (for example "sleep 25 then screenshot"). Agents skipped the wait or used background until-loops. There is no in-bridge "wait for next engine bucket" primitive.

# Part D: MCP-bridge difficulties mined from the solid-phase and fluid-follow-up agents

Sources (JSONL line numbers are given as `#N`):

| alias | file | what it did in game |
|---|---|---|
| **PUMP** | `...solid-phase-fluid-system-plan-4369b0/52c95b9f-.../subagents/agent-af5232d111e7302fa.jsonl` | pump shutoff: generator, pump, 3 pipes, reservoir; read GUIs (2026-09-22 17:25-17:36 UTC) |
| **JUNC** | `.../agent-af48289c1308c2ea4.jsonl` | junction phantom trace: superflat world, filter line, pressure + solids typed, client restart and world reload (20:07-20:33) |
| **TANK** | `.../agent-ab8d04b83a80709c0.jsonl` | full-tank solids event: superflat world, filter line, typed pressure and solids, recover-solids, void, save and quit (21:08-21:26) |
| **DEAD** | `.../agent-a5f899be8bc91482c.jsonl` | dead-headed elevated line: vertical line, tank 4 blocks up, aiming, typed pressure twice, lag samples (23:31-23:51) |
| **CLEAN** | `.../agent-a89f7517407d94576.jsonl` | tooling cleanup; quotes the WP3/WP5 reviews and the rig (`run-client.js`) |
| **F3** | `.../agent-a38deab429ba150f1.jsonl` | quotes the WP4 review section 5 (save and reload in the dev client) |
| **F1** | `.../agent-ab67dca12e505db47.jsonl`, **F2** `.../agent-ac00fdc1fc124b561.jsonl` | in-game rig campaigns (server over RCON); quote WP5 findings |
| **TRAY** | `...tray-pressure-study-method-a19e2d/3a8a486a-....jsonl` | GUI check never done (no jar) |
| **V3REV** | `D--Minecraft-Modding-1-21-CreateChemE/337ae6f5-.../subagents/agent-a66811e8e56648826.jsonl` (+ parent `337ae6f5-....jsonl`) | quotes Codex's V3 handoff review, which drove the bridge from a stdio MCP client |

The rest of the assigned files do not drive the bridge: WP0/WP1 (`ae139e4...`), F4 (`a7af260...`), WP2 resume (`a86cfdb...`) and docs organisation (`ac57826...`). They only mention it in briefs or quoted docs.

Every subagent drove the bridge over plain HTTP (`curl` POST to `http://localhost:9876/api/cmd` with `{"cmd":...}`), through three copied shell helpers (`mc.sh`, `cmd.sh`, `shot.sh`) and a PowerShell/`java.awt.Robot` helper (`poke.ps1` + `Poke.java`). None of them used `mcp__minecraft-mod-mcp__*` tools, because those tools are not registered in subagent sessions. The `.mcp.json` was present but unused (PUMP #560-561).

---

## Difficulties

### 1. The bridge's `execute_command` never reaches the server
1. **Goal:** run slash commands (`/help`, `/function ...`, `/setblock`) from the rig and the agents.
2. **What went wrong:** the command resolves to KubeJS's client-side `kjs$runCommand`, and "even `/help` is refused as 'Unknown or incomplete command'". The first client pilot, `pilot-cli-rest100-r01` (2026-09-23 20:24), failed on every command (CLEAN #117, #362, #365; F2 #38).
3. **Cost:** one whole rig pilot run, which was stopped and its directory deleted.
4. **What worked:** type the command into chat. Send `open_chat`, sleep 0.3-0.4 s, send `type_text` with `{"text":"/<cmd>"}` (or with `"press_enter":true`), then either `press_key {"key":"enter"}` or nothing. Read the reply back from `run/logs/latest.log` (`[CHAT] ...`). This is how `cmd.sh` works (PUMP #546, TANK #429) and how `run-client.js` works; the rig fails the run on `Unknown or incomplete command|Unknown function|Incorrect argument|That position is not loaded|...|You do not have permission`.
5. **Evidence:** CLEAN #365 (the WP5 review section 8 text), CLEAN #351 (`run-client.js` header), `tools/fluid-in-game-rig/README.md` "Known pitfalls".

### 2. `click_button_index` on the Create World screen changes the label but not the setting
1. **Goal:** set Game Mode to Creative and Allow Commands to ON before Create New World.
2. **What went wrong:** WP4 says "The bridge's `click_button_index` changes a cycle button's label without calling its setter, so the world was created with the defaults" (F3 #43). PUMP showed the same thing without noticing. It cycled with `click_button_index {"index":5}` (game mode) and `{"index":7}` (allow commands) (#597, #603, #609), and the screenshots looked right. But `get_player_info` in the world returned `"gamemode":"survival"` and `pos -202.5 89.0 -374.5` (#631), which is a default-terrain world, not superflat.
3. **Cost:** PUMP spent about 3 extra tool calls, then fell back to `/gamemode creative` (#657) and dug a pocket underground with `/fill ... air` plus glowstone (#657-#687, about 8 min of lighting and angle fixes). WP4 accepted a default world.
4. **What worked:** real mouse clicks through `poke.ps1 gui X Y`, in the bridge's 427x240 widget space (JUNC #786-#825, TANK #495-#541, DEAD #617-#676). Those worlds came out creative and superflat. Exact coordinates are under "Exact recipes".
5. **Evidence:** F3 #43 (WP4 review line 141); PUMP #597-#631.

### 3. Allow Commands flips back when the game mode changes (toggle-order trap)
1. **Goal:** Creative with commands ON.
2. **What went wrong:** in vanilla 1.21, cycling to Creative switches Allow Commands on by itself, so a later "toggle commands" click turns it OFF. Every agent needed an extra toggle, confirmed each time by a screenshot. TANK: gamemode click, then gamemode + commands, then commands again (#495, #504, #514). DEAD: commands, gamemode, gamemode, commands, commands again (#617, #620, #630, #633, #643). PUMP: indices 5, 7, 5, 7, 7 (#597-#609).
3. **Cost:** 2-3 extra click/screenshot/Read rounds, about 1-1.5 min per world.
4. **What worked:** click Game Mode twice (Survival, then Hardcore, then Creative) at `gui 213 86`, screenshot, and click `gui 213 142` (Allow Commands) only if the screenshot shows OFF. JUNC clicked Game Mode twice and never touched commands (#786), and its commands worked.
5. **Evidence:** the lines above.

### 4. The World tab on Create World: `switch_tab` parameter name, and a mis-aimed tab click
1. **Goal:** open the World tab to change World Type to Superflat.
2. **What went wrong:**
   - DEAD sent `switch_tab {"tab":"World"}` and `switch_tab {"tab":1}`. Both answered `{"switched":true,"tab":0,...}`, so the tab did not change (#646-#654).
   - TANK clicked `poke.ps1 gui 427 27` (the right edge) and the tab did not change (#514, screenshot `05-world-tab`).
3. **Cost:** DEAD 3 calls (about 1 min); TANK 2 calls.
4. **What worked:**
   - `switch_tab {"index":1}`, which answered `{"switched":true,"tab":1,...}` (TANK #524-#525). The parameter is `index`, not `tab`.
   - A real click on the tab header: `poke.ps1 gui 213 13` (JUNC #806) or `gui 213 14` (DEAD #656).
   - Then `gui 133 45` cycles World Type from Default to Superflat, one click (JUNC #816, TANK #531, DEAD #666).
5. **Evidence:** as above.

### 5. The bridge cannot type into a container screen's EditBox
1. **Goal:** type a pressure (`400000`, `101325`, `150000`), a solids % (`5`, `0`), a material id (`createcheme:demo_particle`), a size (`100`) and a share (`1`) into `FluidDeviceScreen`.
2. **What went wrong:** "the bridge's typing does not reach a container screen's EditBox". The bridge "reflects a (char,int) method the screen does not declare, and Minecraft reads the live GLFW modifier state for Ctrl+V" (CLEAN #106, #423 `Field.java` doc comment). Synthesized letter keystrokes are also eaten by the host IME (`Poke.java` comment, DEAD #833).
3. **Cost:** solved in WP3 by writing `Field.java`/`field.ps1` and `Poke.java`. The later agents copied `poke.ps1` + `Poke` from `agent-a74f606336340ffe9/build/{mcp,flatten}` and got it right the first time.
4. **What worked:** focus the field with a real click (`poke.ps1 gui 314 81`), then `poke.ps1 clear` (Ctrl+A, Backspace), then `poke.ps1 paste <text>`. `paste` puts the text on the clipboard and sends a real Ctrl+V. `type` works for digits only. Then click Apply (`gui 335/336 218`). Proof: the screenshot shows "Settings accepted at the current simulation event." (DEAD #868). The WP3 variant is `field.ps1 -Tabs <n> -Text <v>`: focus the n-th widget with real Tab presses, clear, paste.
5. **Evidence:** JUNC #865, #918; TANK #598, #687-#694, #791; DEAD #845-#849, #904-#908.

### 6. `press_key escape` does nothing in the world, so the pause menu will not open
1. **Goal:** open the pause menu to Save and Quit.
2. **What went wrong:** `press_key {"key":"escape"}` returns `{"pressed":"escape"}`, but `get_screen_buttons` then gives `{"error":"no screen"}`. `open_pause_menu` gives `{"result":"unknown: open_pause_menu"}` (PUMP #766-#770, TANK #830-#831, DEAD #993-#994). Escape from the bridge works only while a screen is open, for example to close a device GUI (PUMP #723, JUNC #905).
3. **Cost:** PUMP gave up and killed the JVM (`Stop-Process -Id 42868 -Force`, #783-#787), so the world was never saved cleanly. TANK and DEAD each spent 2-3 calls.
4. **What worked:** send a real key event with `poke.ps1 key escape` (TANK #833) or `poke.ps1 key ESCAPE` (DEAD #996). `get_screen_buttons` then shows `PauseScreen`. The rig itself uses the bridge command `pause_game`, then `click {"x":426,"y":384}` (the pause screen's bottom button in the default window, from `run-client.js`).
5. **Evidence:** as above.

### 7. `/save-all` does not exist in singleplayer
1. **Goal:** flush the world before shutting down.
2. **What went wrong:** chat shows `Unknown or incomplete command, see below for error` / `save-all flush<--[HERE]` and `save-all<--[HERE]` (PUMP #773-#780, TANK #816/#827, JUNC #960). `save-all` is a dedicated-server-only command. The helper `cmd.sh` prints `ran: /save-all` whatever happened, so JUNC and TANK believed the save had worked.
3. **Cost:** PUMP 3 calls. JUNC then killed the JVM with `Stop-Process -Force` (#966) and still reopened the world later (the autosave had kept it). TANK noticed only in the archived log.
4. **What worked:** Save and Quit to Title from the pause menu (difficulty 6), then Quit Game.
5. **Evidence:** as above.

### 8. Buttons come back with empty labels, so index guesses land on the wrong button
1. **Goal:** click Quit Game on the title screen.
2. **What went wrong:** `get_screen_buttons` returns `"label":""` (or `"_"` for EditBoxes) for every widget, and every widget appears twice. TANK used `click_button_index {"index":5}` for Quit, but index 5 is Options. The JVM stayed alive; PowerShell showed PID 36820 still running after 10 s, 25 s and 45 s. `get_screen_buttons` then answered `"screen":"OptionsScreen"` (#849-#866).
3. **Cost:** about 1.5 min and 5 calls.
4. **What worked:** `press_key escape` (which works inside a screen), then `click_button_index {"index":6}` (Quit Game). The JVM was gone 15 s later (#869-#873). DEAD used `poke.ps1 gui 264 208` for Quit Game (#1008).
   - TitleScreen indices: 0 Singleplayer (x113,y92); 5 Options (x113,y198,w98); 6 Quit Game (x215,y198,w98).
   - PauseScreen: index 8 (x111,y182,w204) is Save and Quit to Title (TANK #846), the same as `gui 213 192` (DEAD #1002).
5. **Evidence:** as above.

### 9. Several bridge commands that agents guessed do not exist
1. **Goal:** list the commands, read the screen, use a block.
2. **What went wrong:**
   - `{"result":"unknown: list_commands"}`, `unknown: help`, `unknown: get_screen_info`, `unknown: use_block`, `unknown: interact_block`, `unknown: open_pause_menu`;
   - `GET /api/help` gives `{"error":"not found"}`.
   - The rig adds that `get_player_info` and `debug_fields` are "unusable". PUMP's `get_player_info` still reported `survival` after `/gamemode creative`.
3. **Cost:** PUMP 4 calls (#694-#704); JUNC 2 calls (#762-#766).
4. **Known to work:**
   - `enter_control_mode`, `get_screen_buttons`, `click_button_index {index}`, `switch_tab {index}`;
   - `screenshot_to_file {path}`, `right_click` (answers `{"right_click":true,"via":"screen_mouseHandler"}` and uses the crosshair);
   - `press_key {key}` (screens only), `open_chat`, `type_text {text[,press_enter]}`, `set_view_angle {yaw,pitch}`, `pause_game`, `click {x,y}`;
   - `GET /api/status`.
5. **Evidence:** as above; CLEAN #365 and the rig README.

### 10. Opening a block GUI means aiming the crosshair with `/tp`, and the player falls when placed in mid-air
1. **Goal:** right-click the tank placed 4 blocks above the generator (DEAD), and the pump (PUMP).
2. **What went wrong:**
   - DEAD: `tp @s 0 -55 2 180 0` and `tp @s 0 -54 2 180 20` did not open the tank. The creative player (not flying) falls from the air, and the crosshair lands on something else (screenshots `16/17/18-tank-screen` show the wrong or no screen; #760-#800).
   - PUMP: the first pump attempt from the east side (`tp @s -199.3 -60 -373.5 90 0`) mis-aimed. The screenshot `13-pump-gui` was deleted as "mis-aimed" (#723, #796).
3. **Cost:** DEAD about 3 min and 12 calls (23:40:47 to 23:43:54); PUMP 3 calls.
4. **What worked:**
   - DEAD: build a standing column with `fill 0 -59 3 0 -55 3 minecraft:glass`, then `tp @s 0 -54 3 180 25` (stand on it and look down 25 degrees at the tank one block north), then check the crosshair with a screenshot (`20-aim-tank`) before `right_click` (#812-#826).
   - PUMP: approach from the side with no neighbouring block in the way: `tp @s -200.5 -60 -372.3 180 0` (#730).
   - For blocks at foot level on superflat: stand 2.2 blocks south at the same y, facing north (yaw 180), pitch 22: `tp @s <x+0.5> -59 2.2 180 22` for a block at `<x> -59 0` (JUNC #852, TANK #585). This worked the first time for the generator, reservoir and filter.
5. **Evidence:** as above.

### 11. Worlds are saved but loading one from the list is awkward
1. **Goal:** reopen the saved world after a client restart (JUNC).
2. **What went wrong:** a double real click on the first list entry (`poke.ps1 gui 213 65` twice) left the client on `SelectWorldScreen` after 25 s (#1045-#1049).
3. **Cost:** 1 call plus a 30 s wait.
4. **What worked:** after the entry is selected, click "Play Selected World" with `poke.ps1 gui 133 197`, then wait 30 s (#1054). Title `click_button_index {"index":0}` opens `SelectWorldScreen` when saves exist. With no saves it jumps straight to `CreateWorldScreen` (PUMP #587, TANK #485). The rig avoids the screens entirely with `--quickPlaySingleplayer <save folder>` (Gradle property `-PfluidClientQuickPlay=`, since detached; CLEAN #416).
5. **Evidence:** JUNC #1042-#1057.

### 12. A world with an old fluid-checkpoint format crashes the client when opened
1. **Goal:** WP4 checked the refusal of an old (format-2) development world.
2. **What went wrong:** the integrated server stops with "Fluid checkpoint format 2 cannot be read ... Create a fresh world for this development build", and the whole client closes with a crash report. Minecraft had already rewritten `level.dat` and the region files during the aborted start (F3 #43, WP4 review lines 150-159).
3. **Cost:** a client relaunch; the world had to be restored from a copy.
4. **What worked:** copy the save folder before opening an old world, and restore it afterwards. Under AGENTS.md, always create a fresh world after a format change.
5. **Evidence:** F3 #43.

### 13. Screenshots come out transparent
1. **Goal:** screenshots for reviews.
2. **What went wrong:** `screenshot_to_file` writes PNGs with alpha 0 (CLEAN #106; rig README).
3. **Cost:** none after WP3; every helper flattens.
4. **What worked:** `shot.sh <name>` calls `mc.sh screenshot_to_file '{"path":"../documentation/screenshots/<topic>/<name>.png"}'`. The path is relative to `run/`, and the bridge answers `{"file":"...\\run\\..\\documentation\\...png","size":N}`. `shot.sh` then runs `java -cp <dir with Flatten.class> Flatten <png>`, which prints `flattened ... 854x480`. Then Read the PNG to look at it.
5. **Evidence:** PUMP #590-#591, TANK #438.

### 14. The bridge reports only a Lag number, so the engine's 5 s updates have to be waited for with long idle waits, which the harness blocks
1. **Goal:** wait 45-120 s for a tank to fill or settle, then re-read the GUI.
2. **What went wrong:**
   - Claude Code refused `sleep 60 && ...`, `sleep 45; ...` and `Start-Sleep -Seconds 45; ...` with "Blocked: sleep N followed by ... use Monitor with an until-loop ... or run_in_background" (TANK #621-#622, JUNC #944-#948, DEAD #872-#873, #933-#934).
   - Worktree-isolated agents also had `for`/`until` loops that call `bash build/mcp/*.sh` refused: "runs bash inside a construct too complex to verify" (PUMP #616-#617, #697-#698; TANK #551-#552; DEAD #694-#695, #936-#937; JUNC #980-#981 for `$((n-25))`).
3. **Cost:** 2-4 wasted calls per agent. Each for-loop placement had to be split into 5 separate `cmd.sh` calls (DEAD #697-#709, TANK #560-#572).
4. **What worked:**
   - Background timers: `until [ $SECONDS -ge 60 ]; do sleep 5; done; echo waited` with `run_in_background:true` (TANK #624, #651, #726), or `n=0; until [ $n -ge 45 ]; do n=$((n+5)); sleep 5; done; echo waited` (DEAD #875, #924). The screenshot goes in a separate call after the notification.
   - A foreground form that passed: `start=$(date +%s); until [ $(( $(date +%s) - start )) -ge 60 ]; do sleep 5; done; echo waited` (JUNC #953).
   - PowerShell `Start-Sleep -Seconds 90` inside a command that also does real work was accepted (JUNC #885 `poke.ps1 gui 389 218; Start-Sleep -Seconds 90`).
   - Reading the engine: close the GUI (`gui 389 218` = Close) and reopen it with `right_click` to get the next bucket's view. Track "Lag x.xx s" and "View t s" across reopenings. Lag falling (for example 3.15 to 1.55 s, TANK #662) means the island is still solving. Grep `run/logs/latest.log` for `status=HELD` / `fluid_island=` (the WARN emitted on a held island).
5. **Evidence:** as above.

### 15. The bridge answers first-open and just-applied reads with stale or "Waiting for the engine" views (engine-owned presentation)
1. **Goal:** screenshot a menu right after opening it, or right after Apply.
2. **What went wrong:**
   - A first-ever open shows `Waiting for the engine` with no data.
   - Right after Apply the old view stays up, and "Applied" arrives on the next bucket.
   - `Queued for simulation event at tick N` "needs an awake island and a bucket within about two ticks of the edit. The bridge cannot time that."
   - The "just opened" screenshots came after the first bucket "because of the bridge's latency" (CLEAN #106, WP3 section 5).
3. **Cost:** WP3 could not show `Queued` in game; a GameTest covers it instead.
4. **What worked:**
   - After Apply, wait 3-8 s (`sleep 3`, `sleep 6`, `sleep 8` in the screenshot call; TANK #611, DEAD #861, #913), then screenshot.
   - To capture a first open, screenshot immediately after `right_click` + `sleep 2`.
   - Series of `05-tank-refresh-a..f` screenshots cropped and stacked with `Stack.java` show the 5 s grid.
5. **Evidence:** CLEAN #106.

### 16. The bridge jar is missing from the worktree, or the port is taken
1. **Goal:** start a client with the bridge listening.
2. **What went wrong:**
   - The jar lives only in `solid-phase-fluid-system-plan-4369b0/run/mods/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar`, and main's `.mcp.json` is absent (PUMP #532-#533).
   - TRAY found no jar anywhere on disk ("No MCP bridge jar exists anywhere on disk and downloads are not allowed"; TRAY #525, #527, #542), so the "Diameter (m)" GUI check was never done.
   - In V3REV (Codex), main's `run/mods` was empty, so no bridge was listening. Unrestricted discovery "selected the user's other Minecraft instance". A second client failed with "HTTP server failed: Address already in use: bind".
3. **Cost:** TRAY: the check stayed open (still "GUI check still open" in memory). V3REV: several client restarts.
4. **What worked:**
   - `mkdir -p run/mods && cp .../solid-phase-fluid-system-plan-4369b0/run/mods/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar run/mods/` (PUMP #535; TANK #435; JUNC #734; DEAD #564).
   - For a second client, set `MC_MCP_PORT=9875` and point the helper at that exact port (V3REV #6, review lines 70-74).
   - Before a rig launch, check `if (await status()) throw 'a bridge endpoint is already answering on 9876'`.
5. **Evidence:** as above.

### 17. Knowing when the client and the world are ready
1. **Goal:** avoid sending commands before the bridge is up or the world has loaded.
2. **What went wrong:** before `enter_control_mode`, any command returns `{"error":"not in control mode","hint":"Enter control mode via ESC > MCP Take Over"}` (JUNC #760). The world load time varies from 12 to 30 s.
3. **Cost:** small; agents used fixed sleeps of 12 s (TANK), 20 s (DEAD) and 25-30 s (JUNC).
4. **What worked:**
   - The bridge is up when `until curl -s -m 3 http://localhost:9876/api/status >/dev/null; do sleep 3; done` succeeds (PUMP #571). The answer looks like `{"ok":true,"type":"minecraft-mod",...,"port":9876,"uptime":8.4}`. The POST variant `{"cmd":"get_screen_buttons"}` also works (TANK #460).
   - `enter_control_mode` over HTTP works directly and needs no ESC > MCP Take Over: `{"control_mode":true,"platform":"internal","hook":false}`.
   - JUNC's one-liner waits and enters control mode together: `until curl ... -d '{"cmd":"enter_control_mode"}' ... | grep -q control_mode; do sleep 5; done` (#1039).
   - The world is in when `get_screen_buttons` answers `{"error":"no screen"}` (JUNC #829, DEAD #777), or when `latest.log` shows `joined the game` (PUMP #622; the rig waits for `/Dev joined the game/`).
5. **Evidence:** as above.

### 18. Shell pitfalls while localising copied helpers
1. **Goal:** retarget the copied `shot.sh` / `poke.ps1` paths to the agent's own worktree.
2. **What went wrong:** `sed: -e expression #1, char 162: Invalid back reference` (PUMP #549) and `invalid reference \1 on 's' command's RHS` (JUNC #744), both caused by the Windows backslashes in `poke.ps1`'s `$root`.
3. **Cost:** 1-2 calls.
4. **What worked:**
   - `perl -pi -e 's{agent-ae086faac00564dbc}{agent-af5232d111e7302fa}g; s{screenshots/hydraulic-row-scale}{screenshots/pump-shutoff}g' build/mcp/shot.sh build/mcp/poke.ps1` (PUMP #551).
   - Or keep `poke.ps1`'s `$root` pointing at the donor worktree's `build/flatten` (JUNC, TANK, DEAD).
   - Or rewrite `shot.sh` with the Write tool (TANK #438, DEAD #570).
5. **Evidence:** as above.

### 19. Stopping the client
1. **Goal:** close the game before running Gradle again.
2. **What went wrong:** without a working pause menu (difficulty 6) PUMP and JUNC killed the JVM. JUNC's list also held the Gradle wrapper and `cmd.exe`/`node.exe` (MCP) processes (#963-#967).
3. **Cost:** a lost clean save (JUNC's world still reloaded, from autosave).
4. **What worked:**
   - Graceful: `poke.ps1 key escape`, then Save and Quit (`gui 213 192` or `click_button_index 8`), then after 8 s on the TitleScreen, Quit Game (`gui 264 208` or `click_button_index 6`).
   - Then confirm with `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ? { $_.CommandLine -match 'fml.modFolders|devlaunch|net.minecraft' }`, which should print "NO MINECRAFT JAVA PROCESS REMAINS" (DEAD #1011).
   - Forced: find the PID whose command line contains `-Dfml.modFolders=createcheme%%D:\...\<worktree>` and run `Stop-Process -Id <pid> -Force` (PUMP #783-#786).
5. **Evidence:** as above.

### 20. Rig runs: config values persist between starts, and the machine gates
1. **Goal:** paired in-game runs (before and after builds).
2. **What went wrong:** "NeoForge keeps a value an earlier start wrote" in `run/config/createcheme-common.toml`, so the before-build ran with the after-build's tolerance until the script patched it (F1 #854). Zulu CDS "Checksum verification failed" and `EXCEPTION_ACCESS_VIOLATION` launcher deaths (F1 #43).
3. **Cost:** the run config had to be handled per build, and there were crashed launches.
4. **What worked:**
   - `sed -i "s/^\(\s*certificateStationaryTolerance = \).*/\1$tol/" run/config/createcheme-common.toml` before each run.
   - `JAVA_OPTS=-Xshare:off` on every gradlew.
   - A machine gate (no `Endfield.exe`, at least 20 GB free) and `hs_err` capture with one rerun.
5. **Evidence:** F1 #43, #850-#854.

---

## Techniques that worked

- **Placing equipment:** `/setblock` typed into chat through `cmd.sh`, with an explicit facing:
  - `setblock X -59 0 createcheme:fluid_generator[facing=east]`, then `fluid_pipe[facing=east]`, `inline_filter[facing=east]`, `fluid_reservoir[facing=east]`, `fluid_void[facing=east]`, `fluid_pump[facing=east]`.
  - Pumps, valves and filters connect only along their facing axis (`FluidDeviceBlock` line 56). Block ids: `fluid_reservoir`, `fluid_pipe`, `fluid_pump`, `pressure_control_valve`, `fluid_generator`, `fluid_void`, `inline_filter` (PUMP #627, JUNC #836).
  - Place one block per call when the agent is worktree-isolated.
- **Superflat geometry:** stand and build at y = -59 (floor at -60, blocks at -59). A line along +x at z = 0, x = 0..4. Overview shot: `tp @s 2 -55 8 180 30` or `tp @s 3 -55 8 180 30`; a lower angle is `tp @s 2.5 -59 6 180 5` (JUNC #1107).
- **Aiming without a mouse:** `/tp @s <x+0.5> -59 2.2 180 22` faces the block at `(x,-59,0)` from 2.2 blocks south; then `right_click`. Always check with a screenshot before `right_click` when the geometry is new.
- **Items and inventory checks:** `/data get entity @s Inventory[0].id` shows the recovered item id in chat (TANK #760). No `/give` was needed, since creative plus `/setblock` covered everything.
- **World hygiene:** `gamerule doDaylightCycle false`, `gamerule doMobSpawning false`, `gamerule sendCommandFeedback true` (DEAD #687). Difficulty set to Peaceful with two clicks at `gui 213 114` (JUNC #796).
- **Hover tooltips:** after each Create World click, park the mouse with `gui 350 200` so tooltips do not cover the screenshot (JUNC #796, #816).
- **Log as the source of truth:**
  - count holds with `grep -c 'status=HELD' run/logs/latest.log` and `grep -c fluid_island`;
  - chat replies appear as `[System] [CHAT] ...`;
  - successful commands appear as `[Server thread/INFO] ... [Dev: Teleported Dev to ...]`;
  - archive the log as `client-latest.log` next to the screenshots.
- **The `poke.ps1` window-focus trick:** attach to the foreground thread's input queue (`AttachThreadInput`), then `ShowWindow(9)`, `BringWindowToTop`, `SetForegroundWindow` and `SetFocus`. Map a bridge widget centre (427x240 space) to the screen with `ClientToScreen + coord*4/3` (GUI scale 2 on an 854x480 framebuffer at 150 % DPI). It prints `WARN foreground is ...` if focus failed.
- **Rig (unattended) client driving:**
  - `options.txt` with `pauseOnLostFocus:false`, `onboardAccessibility:false` (suppresses the accessibility onboarding screen), `tutorialStep:none`, `skipMultiplayerWarning:true`, `joinedFirstServer:true`, `renderDistance:10`, `simulationDistance:10`, `maxFps:120`, `enableVsync:false`, `inactivityFpsLimit:"minimized"`, `fullscreen:false`, `soundCategory_master:0.0`;
  - `--quickPlaySingleplayer <save>` so no screen is navigated;
  - a template world with `allowCommands 1b` (patched with `level-allow-commands.js`);
  - `set_view_angle {yaw:0,pitch:90}` to look straight down;
  - an F3 screenshot via a real F3 key (`tools/f3.ps1` + `KeyTap`).
- **Manual agents set only `pauseOnLostFocus:false`** in `run/options.txt` (`printf 'pauseOnLostFocus:false\n' > run/options.txt`). That was enough, and no onboarding screen blocked them.

## Exact recipes

**Helpers.** In the main checkout these are `tools/mcp-gui-helpers/` (`Field`, `Flatten`, `Stack`, `field.ps1`) and `tools/fluid-in-game-rig/tools/`. The agents' copies came from `agent-a74f606336340ffe9/build/{mcp,flatten}`.

```bash
# mc.sh <cmd> '<extra json fields>'
cmd="$1"; extra="${2:-}"
if [ -z "$extra" ] || [ "$extra" = "{}" ]; then body="{\"cmd\":\"$cmd\"}"
else inner="${extra#\{}"; inner="${inner%\}}"; body="{\"cmd\":\"$cmd\",$inner}"; fi
printf '%s' "$body" > /tmp/tnb-body.json
curl -s -m "${MC_TIMEOUT:-60}" -X POST -H 'Content-Type: application/json' --data-binary @/tmp/tnb-body.json http://localhost:9876/api/cmd

# cmd.sh '<command without slash>'  -- chat path (execute_command never reaches the server)
post() { printf '%s' "$1" > /tmp/tnb-cmd.json; curl -s -m 30 -X POST -H 'Content-Type: application/json' --data-binary @/tmp/tnb-cmd.json http://localhost:9876/api/cmd > /dev/null; }
post '{"cmd":"open_chat"}'; sleep 0.4
printf '{"cmd":"type_text","text":"/%s"}' "$1" > /tmp/tnb-cmd.json
curl -s -m 30 -X POST -H 'Content-Type: application/json' --data-binary @/tmp/tnb-cmd.json http://localhost:9876/api/cmd > /dev/null; sleep 0.4
post '{"cmd":"press_key","key":"enter"}'; sleep 0.6
echo "ran: /$1"      # NOTE: prints even on failure; check latest.log for "Unknown or incomplete command"

# shot.sh <name>
bash "$root/build/mcp/mc.sh" screenshot_to_file "{\"path\":\"../documentation/screenshots/<topic>/$name.png\"}"
java -cp "<dir with Flatten.class>" Flatten "$dir/$name.png"
```

`Poke.java` verbs:
- `click X Y` and `move X Y` (absolute screen pixels);
- `paste TEXT` (clipboard, then real Ctrl+V);
- `clear` (Ctrl+A, Backspace);
- `key NAME` (`VK_<NAME>`, for example `escape`, `ESCAPE`, `F3`);
- `type TEXT` (digits and simple characters only; the IME eats letters).

`poke.ps1 gui X Y` converts bridge widget coordinates to screen pixels; `poke.ps1 <verb> ...` passes straight through.

**Launch and connect** (PUMP #565-#583):
```bash
cp /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/solid-phase-fluid-system-plan-4369b0/run/mods/minecraft-mcp-1.21.1-neoforge-v0.3.0.jar run/mods/
printf 'pauseOnLostFocus:false\n' > run/options.txt
./gradlew.bat runClient --offline > build/runClient.log 2>&1          # run_in_background: true
until curl -s -m 3 http://localhost:9876/api/status > /dev/null 2>&1; do sleep 3; done   # run_in_background: true
bash build/mcp/mc.sh enter_control_mode     # -> {"control_mode":true,"platform":"internal","hook":false}
```

**Creative superflat world with commands** (TANK #484-#544 and DEAD #606-#679, coordinates in the 427x240 space; take a screenshot after each step):
```
bash build/mcp/mc.sh click_button_index '{"index":0}'     # Singleplayer -> CreateWorldScreen (no saves)
poke.ps1 gui 213 86 ; poke.ps1 gui 213 86                 # Game Mode: Survival -> Hardcore -> Creative
# screenshot: Allow Commands should read ON (Creative turns it on); if OFF: poke.ps1 gui 213 142
poke.ps1 gui 213 114 ; poke.ps1 gui 213 114               # optional: Difficulty Normal -> Hard -> Peaceful
bash build/mcp/mc.sh switch_tab '{"index":1}'             # World tab   (or poke.ps1 gui 213 13)
poke.ps1 gui 133 45                                       # World Type: Default -> Superflat
poke.ps1 gui 134 224                                      # Create New World
# wait ~15-25 s; in world when get_screen_buttons -> {"error":"no screen"}
```
Do not use `click_button_index` for the cycle buttons (difficulty 2).

**Build and open a GUI** (JUNC #842-#852):
```
cmd.sh 'gamerule doDaylightCycle false'
cmd.sh 'gamerule doMobSpawning false'
cmd.sh 'tp @s 2 -55 8 180 30'
cmd.sh 'setblock 0 -59 0 createcheme:fluid_generator[facing=east]'
cmd.sh 'setblock 1 -59 0 createcheme:fluid_pipe[facing=east]'
cmd.sh 'setblock 2 -59 0 createcheme:inline_filter[facing=east]'
cmd.sh 'setblock 3 -59 0 createcheme:fluid_pipe[facing=east]'
cmd.sh 'setblock 4 -59 0 createcheme:fluid_reservoir[facing=east]'
cmd.sh 'tp @s 0.5 -59 2.2 180 22' ; sleep 1 ; mc.sh right_click ; sleep 2   # -> FluidDeviceScreen
```

**`FluidDeviceScreen` widget centres** (427x240 space; JUNC, TANK, DEAD):

| widget | `poke.ps1 gui` |
|---|---|
| pressure field | `314 81` (or 82) |
| Apply | `335 218` / `336 219` |
| Close | `389 218` / `389 219` |
| Solids page button | `213 218` |
| solids volume % | `314 143` |
| material id | `128 172` |
| particle size µm | `285 172` |
| mass share | `367 172` |
| filter "Recover solids" | `228 218` |

Type a value:
```
poke.ps1 gui 314 81 ; poke.ps1 clear ; poke.ps1 paste 400000 ; <screenshot> ; poke.ps1 gui 336 218 ; sleep 3-6 ; <screenshot>
```
Expected: `Pressure 400.00 kPa abs`, "Settings accepted at the current simulation event."

**Elevated block** (DEAD #812-#825):
```
cmd.sh 'fill 0 -59 3 0 -55 3 minecraft:glass'
cmd.sh 'tp @s 0 -54 3 180 25'
<screenshot to confirm crosshair>
mc.sh right_click
```

**Wait for the engine** (the harness blocks foreground sleeps):
```bash
until [ $SECONDS -ge 60 ]; do sleep 5; done; echo waited      # run_in_background: true, then screenshot in the next call
```

**Reload a saved world** (JUNC #1036-#1057):
```
Start-Process .\gradlew.bat -ArgumentList 'runClient','--offline','--console=plain' -RedirectStandardOutput build\client2.log -RedirectStandardError build\client2.err -WindowStyle Minimized
until curl -s -m 3 -X POST -H 'Content-Type: application/json' -d '{"cmd":"enter_control_mode"}' http://localhost:9876/api/cmd | grep -q control_mode; do sleep 5; done
mc.sh click_button_index '{"index":0}'     # -> SelectWorldScreen
poke.ps1 gui 213 65                        # select first world
poke.ps1 gui 133 197                       # Play Selected World ; wait ~30 s
```

**Save, quit and close** (DEAD #996-#1011; TANK #833-#873):
```
poke.ps1 key escape                        # bridge press_key escape does not open the pause menu in-world
mc.sh get_screen_buttons                   # -> PauseScreen
poke.ps1 gui 213 192                       # Save and Quit to Title   (= click_button_index 8)
sleep 8 ; mc.sh get_screen_buttons         # -> TitleScreen
poke.ps1 gui 264 208                       # Quit Game                (= click_button_index 6; index 5 is Options!)
# verify: no java.exe whose CommandLine matches 'fml.modFolders|devlaunch|net.minecraft'
```

**Rig client run** (`tools/fluid-in-game-rig/run-client.js`): `status()`, then `enter_control_mode`. Each command is `open_chat` + `type_text {text, press_enter:true}` followed by a regex wait on `latest.log`: `/function createcheme_bench:view`, then `set_view_angle {yaw:0,pitch:90}`, then `/function createcheme_bench:forceload`, then `/function createcheme_bench:<scenario>`. After the window it takes `screenshot_to_file {path}`, taps F3 through `f3.ps1` and screenshots again, then `pause_game`, `click {x:426,y:384}` and kills the JVM.

## Open problems never solved

1. **`Queued for simulation event at tick N` cannot be shown in game.** The bridge's latency cannot hit the 2-tick window before a bucket; only the GameTest covers it (WP3, CLEAN #106).
2. **Cycle buttons through the bridge.** `click_button_index` updates the label but not the value. Every agent needs Robot clicks for Game Mode, Allow Commands and World Type (F3 #43).
3. **Text entry through the bridge.** `type_text` works in chat but not in container-screen EditBoxes; a Robot plus clipboard helper stays mandatory. The WP3 "just opened" screenshots missed the first bucket because of bridge latency.
4. **Pause menu through the bridge.** `press_key escape` in the world, `open_pause_menu` and `save-all` do not work. PUMP never saved cleanly and killed the JVM; JUNC killed it too.
5. **Unusable bridge readers.** `get_player_info` is unreliable (it reported `survival` in a creative context) and `debug_fields` is unusable (rig README). Button labels are always empty, so indexes have to be mapped from coordinates or screenshots.
6. **The tray-pressure "Diameter (m)" GUI field was never checked in game.** No jar was on disk in that session (TRAY #527, #542, #673). The V3 Heat-page pill (V3REV #524-#534) also stays an open follow-up.
7. **An old-format world crashes the whole client** instead of returning to the title screen (WP4 open item, F3 #43).
8. **Worktree-isolated agents cannot run loops over the helper scripts**, because the harness refuses "a construct too complex to verify". Every multi-block build becomes one call per block.

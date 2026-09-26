# In-game mixed-gas junction check on the MCP lane (2026-09-26, run 161)

Build: branch `claude/phase-ports-compressor` at `c32acac` (= main 0.6.0), no source edits.
Client: `JAVA_OPTS=-Xshare:off ./gradlew.bat --no-configuration-cache runMcpClient --offline --console=plain`
(Gradle log `../../run161-mcp-client.log`, client log `client-latest.log` = `run/mcp-client/logs/latest.log`).
Game dir `run/mcp-client/`, bridge jar copied into `run/mcp-client-mods/` (SHA-256 `c6cc12c9...` checked by
`prepareMcpControlMod`), `run/mcp-client/options.txt` written before launch (`pauseOnLostFocus:false`,
`guiScale:2`, ...). The log lists `CreateChemE MCP Test Compatibility 1.0.0 (createcheme_mcp_compat)`.
All git-ignored; `git status` clean. No desktop input of any kind was used: every step went through the
bridge's HTTP endpoint (`node %TEMP%/minecraft-mcp-skill/scripts/bridge.js ...`).

## World

Fresh creative superflat world "New World" (Game Mode Creative, Allow Commands ON, World Type Superflat,
structures off; the index clicks were checked on screenshots before Create). `/gamerule doDaylightCycle
false`, `/gamerule doMobSpawning false`, `/difficulty peaceful` confirmed in the log.

## Built (y = -59, `setblock` through chat)

| block | position | settings |
|---|---|---|
| fluid_generator A | (0, -59, 0) | set in its GUI: nitrogen 100 %, 150000 Pa, 76.85 C (350.0 K) |
| fluid_generator B | (2, -59, -2) | set in its GUI: methane 100 %, 150000 Pa, 76.85 C (350.0 K) |
| fluid_pipe | (1,-59,0), (2,-59,-1) | inlet pipes, default diameter 0.05 m |
| fluid_pipe (junction) | (2, -59, 0) | `Junction · 5 connections` |
| fluid_pipe | (3,-59,0), (4,-59,0) -> fluid_void (5,-59,0) | void default 101325 Pa |
| fluid_pipe | (2,-59,1), (2,-59,2) -> fluid_void (2,-59,3) | void default 101325 Pa |
| fluid_pipe (vertical) | (2, -58, 0) -> fluid_reservoir (2, -57, 0) | reservoir placement default: nitrogen, 101325 Pa, 25 C, 1000 L |

Reservoirs have no editable controls (no Apply button; `FluidDeviceScreen` builds no fields for
`RESERVOIR`), so the two sources are generators, and the reservoir hangs on the junction as a fifth,
dead-end port to show (iii). The generators were set while still unconnected; the pipes, voids, reservoir
and last the junction pipe were placed afterwards (junction at 17:41:36 wall clock, about game time
230 s).

## Text entry: the exact bridge calls (all worked, in-process, compat mixins)

Generator opened with `chat "/tp @s 0.5 -58 0.5 0 89"` then `use_item` (B: `/tp @s 2.5 -58 -1.5 0 89`).
Coordinates are framebuffer pixels (GUI x 2).

```
click {"x":172,"y":212,"button":0}                                   pressure EditBox -> {"clicked":true,"method":"minecraft_interface_dispatch"}
hotkey {"keys":"key.keyboard.left control,key.keyboard.a"}          select all (compat HotkeyMixin)
type_text {"text":"150000"}
click {"x":172,"y":296,"button":0}                                   temperature EditBox
hotkey {"keys":"key.keyboard.left control,key.keyboard.a"}
type_text {"text":"76.85"}
click_button_index {"index":1}                                       Composition page
click {"x":500,"y":232,"button":0}                                   component search (ComponentDropdown)
type_text {"text":"nitrogen"}        (B: "methane"; first match Methane, then Ethane)
press_key {"key":"enter"}                                            choose the first match
click_button_index {"index":13}                                      x on the Water row (the row visible first)
click {"x":630,"y":340,"button":0}                                   relative amount EditBox of the new row
hotkey {"keys":"key.keyboard.left control,key.keyboard.a"}
type_text {"text":"1"}
click_button_index {"index":6}                                       Apply changes
```

After Apply the screen read `Applied`, pressure `150000.0`, temperature `76.9`, composition a single row
(Nitrogen, resp. Methane) - `01-methane-generator-applied.png`. `paste_text` was not needed.

Pitfall found: from Git Bash, `bridge.js chat "/tp ..."` is rewritten by MSYS path conversion into
`C:/Program Files/Git/tp ...` (sent as a chat message). Set `MSYS_NO_PATHCONV=1` (and then pass absolute
`D:/...` paths to `shot`, not `$PWD`). `bridge.js cmds <file>` is not affected. The bridge has no `wait`
tool (`unknown: wait`); use `sleep`.

Aiming at the junction (all six neighbours occupied except the diagonals): `/tp @s 3.6 -60 1.6 135 5`
(the `facing` form gave pitch -42 and aimed at the vertical pipe), then `right_click`. Note that on this
lane a bridge `click` with no screen open is routed to the attack path (would break a block in creative);
it was not used in the world.

## Observations (game time = the screen's "Last delivered view")

| game time | device, screen | text read | evidence |
|---|---|---|---|
| 281.2 s | junction (2,-59,0), Overview, Vapor | `Junction · 5 connections`; Methane 51.4 % 36.5 kmol/h, Nitrogen 48.6 % 34.5 kmol/h; Flow rate 71.0 kmol/h; Bulk speed (last 5 s) `0.00126-40.0 m/s` | `02-junction-mixed-t281s.png` |
| 306.2 s | reservoir (2,-57,0) | header `FULL`; 65.9 C, 146.0 kPa, 1000.0 L, 1.4 kg; Methane 10.4 % 0.00540 kmol, Nitrogen 89.6 % 0.0464 kmol | (not kept) |
| 351.2, 366.2, 381.2 s | reservoir, screen kept open (scheduled updates) | header `FULL`; 146.0 -> 146.1 kPa, composition unchanged | (not kept) |
| 386.2 s | junction, Overview | same species split and 71.0 kmol/h; Bulk speed `8.38e-08-100.0 m/s` | (not kept) |
| 401.2 s | reservoir | header `STEADY: replaying 0.2686 kg/s since 385.5 s, next check at 1998.3 s`; 146.1 kPa; Methane 10.4 %, Nitrogen 89.6 % | `03-reservoir-steady-t401s.png` |
| 446.2 s | junction, Connections page | (0,-59,0)->(2,-59,0) 34.5 kmol/h 94.8 m/s 2618.8 Pa/m; (2,-59,-2)->(2,-59,0) 36.5, 100.0 m/s, 2618.8; (2,-59,0)->(5,-59,0) 35.5, 100.0 m/s, 17898.; (2,-59,0)->(2,-59,3) 35.5, 100.0 m/s, 17898.; the fifth row (reservoir) is scrolled off; overview bulk speed `6.60e-08-100.0 m/s` | `04-junction-connections-t446s.png` |
| 486.2 s | generator A, Overview | header `STEADY: replaying 0.2686 kg/s since 385.5 s, next check at 1998.3 s`; Nitrogen 100 % | (not kept) |

`grep -c HELD client-latest.log` = 0; no fluid warning, error or exception in the log.

Checks:

- (i) Mixed contents: PASS. The junction holds both species (CH4 51.4 %, N2 48.6 % in the flow). Inflow
  34.5 + 36.5 = 71.0 kmol/h = outflow 35.5 + 35.5. Bulk speed: the maximum rose from 40.0 m/s (281 s) to
  100.0 m/s and stayed there (the 100 m/s gas velocity cap, D14; the pipe-status line that would say
  "limited by the allowed fluid velocity" lies below the 854x480 capture and was not read). The minimum is
  the dead-end reservoir branch, falling 1.26e-3 -> 8.4e-8 -> 6.6e-8 m/s as the reservoir equalises.
- (ii) Island status: PASS. `FULL` (the full-solve status, presented as the solving/ready form) from the
  junction's placement until at least 381.2 s, then `STEADY: replaying 0.2686 kg/s since 385.5 s` on every
  device read up to 486.2 s. No HELD, no ERROR. 0.2686 kg/s equals the nitrogen inlet (34.5 kmol/h x
  28.01 kg/kmol = 0.268 kg/s), the largest boundary mass rate.
- (iii) Reservoir: PASS. Placed as N2 at 101.3 kPa and 25 C (0.0409 kmol by ideal gas); read at 146.1 kPa,
  65.9 C with 0.0518 kmol, of which 0.0054 kmol methane and 0.0055 kmol added nitrogen: it took in about
  10.9 mol of the junction mixture (about half methane) until its pressure met the junction's, then its
  contents stayed fixed (dead end, no through-flow).

Save and Quit (`pause_game`, `click_button_index 8`; log "All dimensions are saved"), Quit Game
(`click_button_index 6`); afterwards no `java.exe` with `fml.modFolders` remained (only the idle Gradle
daemons).

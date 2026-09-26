# WP4 in-game presence check (2026-09-26, run 155)

Dev client `./gradlew.bat --no-configuration-cache runClient` (log `../run155-client.log`), bridge jar
`minecraft-mcp-1.21.1-neoforge-v0.3.0.jar` copied into `run/mods/`, `.mcp.json` copied to the worktree root
(both git-ignored, nothing tracked). Skill extracted from `claude/minecraft-mcp-skill-c3f0f0` to
`%TEMP%/minecraft-mcp-skill/`. Fresh creative superflat world "New World", commands on. Client log: `client-latest.log`.

## Built (setblock, y = -59)

- Island 1 (a 4-port junction): generator (0,0) - pipe (1,0) - pipe (2,0) [junction] ; generator (2,-2) - pipe (2,-1) - junction ;
  junction - pipes (3,0),(4,0) - void (5,0) ; junction - pipes (2,1),(2,2) - void (2,3). Generators left at the placement
  default (water, 25 C, 101325 Pa), voids at 101325 Pa nitrogen.
- Island 2: reservoir (10,0) - pipe (11,0) - reservoir (12,0), both the default nitrogen charge at 101325 Pa.

## Observed (game time from the screens)

| shot | device | text read | verdict |
|---|---|---|---|
| `01-generator-open.png` | generator (0,0), first open | header `FULL`, Water 100 %, 55.3 kmol | loads |
| (composition page, not kept) | generator (0,0) at 72-161 s | header `STEADY: replaying 4.441e-16 kg/s since 72.1 s, next check at 86472.1 s` | certified; merged status form (WP2) |
| `02-closed-pair-pipe.png` | pipe (11,0) at 81.4 s | flow 0.0 kmol/h, bulk speed 0.0 m/s; the pipe-status line is below the 854x480 capture | not read |
| `03-closed-pair-tank.png` | reservoir (10,0) at 81.4 s | header `STEADY: no flow since 77.6 s`; N2 100 %, 101.3 kPa, 1000 L, 1.1 kg | PASS (merged status, identity certificate) |
| `04-water-junction-pipe.png` | junction pipe (2,0) at 246.1 s | `Junction · 4 connections`; Water 100 %; flow 2.66e-13 kmol/h; bulk speed 2.27e-16 m/s | junction presented; roundoff flow |

`grep -c status=HELD latest.log` = 0; no fluid error in the log. Save and Quit, Quit Game through the bridge; no game JVM left.

## Not done

- The mixed-gas junction (two different gases) was not built. Reservoirs are always a nitrogen charge and generators place
  as water; a second gas and a pressure above the voids need text entry in the generator screen (composition search,
  amount, pressure). On the plain `runClient` lane the bridge cannot type into a container EditBox, and the skill's
  workaround `robot.ps1` sends real desktop input. Its first paste reported "Minecraft is not the foreground window";
  the desktop capture showed a browser window with a payment-method dialog behind the client, so the real-input path
  was stopped at once and not used again. The keystrokes may have gone to that browser window.
- (ii) mixed contents and a settling bulk speed, and the pipe-status line (`no_flow` / `flowing` presentation of a
  pipe), were therefore not observed. The reservoir header shows the raw merged status `STEADY: no flow since ...`.

# Minecraft MCP bridge skill (2026-09-24)

Batch that distilled every session's experience with the langyo/minecraft-mod-mcp bridge into a
tracked agent skill, `.claude/skills/minecraft-mcp/` (branch `claude/minecraft-mcp-skill-c3f0f0`,
commits `20b1587`, `1287d6d` and the mining follow-up; `.gitignore` re-includes `/.claude/skills/`).

## What the skill holds

- `SKILL.md`: launch lanes (`runMcpClient` with the tracked `mcpCompat` mixins, `runClient` with the
  jar in `run/mods`), machine rules, the HTTP transport, world creation, scenario placement with
  chat commands, the three aiming placements that open a block screen, text entry, engine waits,
  screenshots, save and quit.
- `scripts/bridge.js` (sequential HTTP driver: `status`, `wait-ready`, `wait-world`, `shot`, `chat`,
  `cmds`, `batch`, any tool), `scripts/robot.ps1` (real input through `java.awt.Robot`, foreground by
  `AttachThreadInput`, GUI-to-screen mapping with the window DPI) and the Java helpers `Field`,
  `KeyTap`, `Poke`, `Desk`, `Flatten` (same sources as `tools/mcp-gui-helpers/` and the rig).
- `references/screen-map.md` (widget indices and click points), `references/bridge-tools.md`
  (every 0.3.0 tool and what works in this client), `references/pitfalls.md` (failure catalogue).

## Transcript mining (2026-09-24)

Four opus subagents mined the JSONL transcripts of about 15 agents that drove the bridge between
2026-09-07 and 2026-09-24 (V3 Heat tab pass, solid-phase GUI pass and its seven probe agents,
fluid scheduling WP3 to WP5 and follow-ups, Codex's V3 handoff review). Their reports are in
`transcript-mining/part-A.md` to `part-D.md`, each with numbered difficulties (goal, symptom,
cost, fix, evidence line), techniques, verbatim recipes and open problems. The skill's pitfalls
catalogue and screen map are the consolidation.

Headline findings: `execute_command` never reaches the server (chat is the only path); the bridge
cannot type into a container screen's `EditBox` (Robot paste, `/data merge block`, or the
`mcpCompat` lane); `pauseOnLostFocus:false` must be set before launch; `click_button_index` on
Create World cycle buttons sometimes moves the label without the value; `press_key escape` does
nothing in the world (`pause_game`); block screens open from `tp @s x.5 -58 z.5 0 89` + `use_item`,
`tp @s x.5 -59 z+2.2 180 22` + `right_click`, or `tp ... facing ...`; `get_player_info`,
`debug_fields`, `select_list_item`, `kill_minecraft` are unusable; every screenshot has alpha 0.

## Status

In progress since 2026-09-24 on `claude/minecraft-mcp-skill-c3f0f0`; not merged. The skill-creator
eval runs and description optimisation were skipped because they would launch the dev client
repeatedly. Open: confirm text entry on the `runMcpClient` lane once in a client; the coordinate
mapping in `robot.ps1` (GUI units times scale over the DPI factor) is taken from the proven
`poke.ps1` constant and has not been re-run here.

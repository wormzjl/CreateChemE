# Dev-client GUI helpers for the MCP bridge

These are small `java.awt.Robot` and image helpers for GUI verification in the dev client through the langyo/minecraft-mod-mcp bridge (`AGENTS.md`: GUI work is verified there). They fill in where the bridge falls short:

- the bridge's typing does not reach a container screen's `EditBox`;
- its screenshots have alpha 0;
- the F3 overlay needs a real key event.

## Batch and provenance

- Written for fluid scheduling **WP3** (the menu edits and the "Waiting for the engine" screenshots) and reused in **WP4** (the save-and-reload screenshots), batch `2026-09-23-fluid-scheduling-rest`: `FLUID_SCHEDULER_WP3_REVIEW.md` section 5 and `FLUID_SCHEDULER_WP4_REVIEW.md` section 5. `field.ps1` says it was adapted from the solid-phase GUI pass.
- They lived only in the worktree's `build/wp3mcp/`, which `gradlew clean` or removing the worktree would delete. The tooling cleanup moved them here on 2026-09-24 (`FLUID_TOOLING_CLEANUP_REVIEW.md`). They were never tracked, and nothing was removed from the code.
- The in-game rig carries its own copies of `Flatten` and of the F3 key tap (`../fluid-in-game-rig/tools/`). `Flatten.java` is identical in both folders.

## Files

| file | usage | what |
|---|---|---|
| `field.ps1` | `powershell -File field.ps1 -Tabs <n> [-Text <value>]` | Brings the Minecraft window forward, then runs `Field`: focus the n-th widget with real Tab presses, clear it, paste the value. |
| `Field.java` / `.class` | `java -cp <this folder> Field <tabs> [text]` | The Robot part of `field.ps1`. Minecraft reads the live GLFW modifier state for Ctrl+V, so only real key events reach an `EditBox`. |
| `Flatten.java` / `.class` | `java -cp <this folder> Flatten <png>` | Rewrites a bridge screenshot with an opaque alpha channel. |
| `Stack.java` / `.class` | `java -cp <this folder> Stack <out.png> <x> <y> <w> <h> <in.png...>` | Crops the same region of several screenshots and stacks them with labels (WP3's `05-tank-refresh-stack.png` and `08-09-apply-stack.png`). |
| `records/shot.sh`, `records/shot4.sh` | | The WP3 and WP4 one-liners that flattened a screenshot in the batch's `wp3-screenshots/` or `wp4-screenshots/`. They are kept as they ran, with the worktree paths. |

`field.ps1` is edited from the `build/wp3mcp` copy (`changes-from-build-wp3mcp.diff`): it reads the classes from its own folder instead of the worktree's `build/wp3mcp`, and takes the JDK from `RIG_JDK` when that is set. Every other file is byte-identical to its source. The `.class` files are the ones compiled in WP3 (JDK 21); rebuild them with `javac -d . *.java`.

The machine rules hold: the dev client is the only Gradle invocation while it runs, it starts after every suite, and it is closed afterwards.

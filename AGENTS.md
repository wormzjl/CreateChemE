# Agent rules for CreateChemE

Standing rules from the project owner. They apply to every agent working in this repository (Claude, Codex, subagents) for every task, unless the owner states otherwise for a specific task.

## Save and data compatibility (recorded 2026-09-23)

- No compatibility work at the current development stage. A breaking change to a save format, checkpoint, wire protocol or material data set is tested on a fresh world.
- Never test against an old world, never write a migration, absent-field normalisation, or legacy-save test gate for such a change.
- New formats must still validate what they read and round-trip clocks, material, pending events and any optimisation state they carry.

## Player-facing updates (recorded 2026-09-23, the project's gold standard)

- The simulation engine decides when presentation updates happen. Block-entity views, menu data, acknowledgements and status changes are delivered on the engine's own schedule, roughly every five seconds of online time.
- Player inputs and changes to a network are queued as engine events. Nothing is pushed to a client immediately from a packet handler, a block-entity load, a menu open, or a tick hook.

## Process work and the tick loop (recorded 2026-09-23)

- No process-state calculation on Minecraft's per-tick loop. Work is driven by due simulation deadlines, dependency changes and worker completions; the tick hook may only check what is due, drain completions and flush a presentation bucket.
- Simulated time stays online ticks at nominal 20 TPS; no wall-clock or offline advancement.

## Working rules already in force

- One Gradle invocation at a time on the machine; never a test suite while a dev client is running.
- Never use bare `git stash` or `git stash pop`; set work aside with a WIP commit or a tagged stash entry restored with `git stash apply`.
- Work only inside your own worktree; never modify another agent's worktree sources.
- Every review or audit is written to `documentation/<TOPIC>_REVIEW.md`; plans to `documentation/<TOPIC>_PLAN.md`.
- GUI work is verified in the dev client through the langyo/minecraft-mod-mcp bridge (jar in `<worktree>/run/mods`, `.mcp.json` in the worktree root).
- Large solver campaigns run with 8 to 10 worker threads and never overlap another campaign or a Gradle suite.
- Commit messages end with the attribution line given for the session.

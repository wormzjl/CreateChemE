# Agent rules for CreateChemE

Standing rules from the project owner. They apply to every agent working in this repository (Claude, Codex, subagents) for every task, unless the owner states otherwise for a specific task.

## Save and data compatibility (recorded 2026-09-23)

- No compatibility work at the current development stage. A breaking change to a save format, checkpoint, wire protocol or material data set is tested on a fresh world.
- Never test against an old world, never write a migration, absent-field normalisation, or legacy-save test gate for such a change.
- New formats must still validate what they read and round-trip clocks, material, pending events and any optimisation state they carry.

## Player-facing updates (recorded 2026-09-23; amended 2026-09-24)

- The simulation engine produces process presentation snapshots, acknowledgements and status changes on its own schedule, roughly every five seconds of online time.
- Send GUI/process presentation data only in response to a client request. Opening a GUI requests the last published snapshot immediately and subscribes that client to scheduled updates while the GUI remains open; closing it ends that subscription. Do not broadcast contents to clients with no active request.
- Opening a GUI or an explicit read request may immediately replay the server's last published snapshot. This is a read of cached data: it must not run a solver, materialise new process state, apply pending input, or advance simulation time. If no snapshot exists, wait for the engine's first scheduled publication.
- Player inputs and changes to a network remain queued engine events. Their acknowledgements and newly calculated state are delivered on the engine schedule; a packet handler, block-entity load or menu open must not force a new process update.

## Process work and the tick loop (recorded 2026-09-23)

- No process-state calculation on Minecraft's per-tick loop. Work is driven by due simulation deadlines, dependency changes and worker completions; the tick hook may only check what is due, drain completions and flush a presentation bucket.
- Simulated time stays online ticks at nominal 20 TPS; no wall-clock or offline advancement.

## Working rules already in force

- One Gradle invocation at a time on the machine; never a test suite while a dev client is running.
- Never use bare `git stash` or `git stash pop`; set work aside with a WIP commit or a tagged stash entry restored with `git stash apply`.
- Work only inside your own worktree; never modify another agent's worktree sources.
- Every review or audit is written to `documentation/<batch>/<TOPIC>_REVIEW.md`; plans to `documentation/<batch>/<TOPIC>_PLAN.md`, inside the folder of the batch of work they belong to (see the next section).
- GUI work is verified in the dev client through the langyo/minecraft-mod-mcp bridge (jar in `<worktree>/run/mods`, `.mcp.json` in the worktree root).
- Large solver campaigns run with 8 to 10 worker threads and never overlap another campaign or a Gradle suite.

## Documentation, research, tools and versions (recorded 2026-09-23)

- Working documents, research material and offline tooling are local and git-ignored, in three folders of the main checkout (`D:/Minecraft/Modding/1.21/CreateChemE`), which holds the canonical copy:
  - `documentation/`: one folder per batch of work, named `YYYY-MM-DD-topic/` after the day the batch started, plus `reference/` and `repository/`. `documentation/INDEX.md` lists every batch with its status; `documentation/README.md` explains the layout.
  - `research/`: datasets, literature sources, study harnesses and journals, grouped by batch, indexed in `research/INDEX.md`.
  - `tools/`: offline scripts and harnesses, one folder per tool, indexed in `tools/INDEX.md`. `tools/development.gradle` stays at that path.
- Do not add new top-level folders for documents or scripts. `examples/` is tracked and referenced by the build and tests; leave it where it is.
- After every task, update the documentation of its batch in the same session: put the task's documents in the batch folder (create a new dated folder for a new batch) and update the batch row of `documentation/INDEX.md` (and `research/INDEX.md` or `tools/INDEX.md` when those changed) with the status and its date: Implemented (date it reached `main`), In progress since, Planned, Concluded (research only), or Abandoned / Superseded with the reason.
- Documents written in a worktree are copied into the main checkout's `documentation/<batch>/` when the work merges, or when the task ends if it never merges.
- Every merge to `main` adds a `CHANGELOG.md` entry (Keep a Changelog format, one line per batch with its commits and batch folder) and bumps `mod_version` in `gradle.properties` in the same merge: minor version for a merged batch of work, patch version for a merge that only fixes. `0.1.0` is the state before the changelog existed; `0.2.0` is the solid-phase fluid system merge. Branches record their entry under `[Unreleased]`; the merge turns it into the new version heading.

## Test classes and tools after a batch (recorded 2026-09-24)

- Before a batch merges, everything it created for measurement or investigation is sorted. What stays in the code: tests a gate task runs, code the product needs, and harnesses a documented Gradle task runs. Everything else leaves the code: one-off probes, campaign and analysis scripts, in-game rigs, instrumentation and baseline patches, run configurations and Gradle switches that only served them.
- Detached material is stored in the main checkout under `tools/<tool-folder>/` (git-ignored, one folder per tool, indexed in `tools/INDEX.md`), never left scattered in `documentation/` batch folders or in a worktree. Each folder has a `README.md` stating its purpose, the batch it served, how to run it, and, for code removed from a tracked path, the commit that removed it and how to re-attach it (source files plus a patch against that commit, so `git show <sha>:<path>` and the stored copy agree).
- The batch's review names the tool folders that hold its detached material, so an agent finds them from `documentation/INDEX.md` or `tools/INDEX.md`.
- A cleanup neither widens nor narrows a gate suite. The gates are re-run after it and recorded in the batch's review, with a `CHANGELOG.md` line for the detached code.

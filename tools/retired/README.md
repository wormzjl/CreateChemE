# Retired code

This folder holds code removed from a tracked path because nothing ran it any more. It is kept for reference and not maintained. Each entry names the commit that removed it and how to put it back.

| entry | removed in | why | re-attach |
|---|---|---|---|
| `EquipmentType.java` | `ca5bdb6` (2026-09-14) | Untracked from `src`, kept for reference (see `tools/INDEX.md`). | `git show ca5bdb6~1:<path>` gives the tracked version. |
| `fluid-profile-replays/` | `68d8877` (2026-09-24, branch `claude/fluid-followups`, batch `2026-09-23-fluid-followups`, tooling cleanup) | The Gradle tasks `fluidStressProfile` and `fluidModuleProfile`, the property `-PfluidProfileSnapshot`, and the tests `FluidStressProfileTest` and `FluidModuleProfileTest`, added in `a5dedf6` (2026-09-16) and adapted to format 4 in `ed4dfa4`. See below. | `git apply tools/retired/fluid-profile-replays/reattach.patch` from the repository root, at `68d8877` or later. |

## fluid-profile-replays

The two tests replayed one island, read-only, out of a saved world:

- `FluidStressProfileTest` replayed the largest island still at committed tick 0 over 0.01, 0.1, 1 and 5 s intervals.
- `FluidModuleProfileTest` replayed a module's deferred receiver three ways: baseline, one input, and planner.

Each task's default snapshot was a specific preserved benchmark world: `run/fluid-benchmark/stress100-baseline-auto-r01/world/data/createcheme_fluid_core.dat` and `run/fluid-benchmark/pilot-module-warm-01/...`. The 2026-09-24 tooling cleanup (`documentation/2026-09-23-fluid-followups/FLUID_TOOLING_CLEANUP_REVIEW.md`) retired them as dead:

- **Neither snapshot exists** in the main checkout or in any worktree of this machine. The solid-phase batch's `FLUID_SOLVER_REGRESSION_RERECORD.md` and the fluid-network batch's `FLUID_SOLVER_OPTIMIZATION_PROGRESS.md` already recorded `pilot-module-warm-01` as absent.
- **Both worlds come from the 2026-09-15 fluid-network batch (its M9 runs, before 2026-09-18), saved in checkpoint format 1 or 2, well before format 3 (WP4, 2026-09-23).** The code reads format 4 only (`FluidCheckpointCodec`: `"Fluid checkpoint format N cannot be read"`), and F4 changed the network package's thermodynamic revision, so every fluid world saved before F4 is refused as well. No readable world exists on this machine.
- **A fresh paced run would not replace them.** Each test looks for a failure state that such a run does not leave: an island never advanced past tick 0, or a pending input due at its receiver's committed tick.
- **No gate, documented protocol or review of the two fluid batches runs them.** Their only change in those batches was `ed4dfa4` adapting their load call to the format-4 signature.

Contents: `FluidStressProfileTest.java` and `FluidModuleProfileTest.java` (each equals `git show c1b8464:src/test/java/com/wormzjl/createcheme/fluid/benchmark/<name>`), and `reattach.patch`. The patch restores both tests and both tasks. It applies to `68d8877` and to the branch head, and its result equals `c1b8464` (`cleanup-logs/patch-checks.txt`). To use them again:

1. Save a format-4 world that holds the state the test expects.
2. Run `./gradlew fluidStressProfile -PfluidProfileSnapshot=<world>/data/createcheme_fluid_core.dat`. The pack files must be in `data/createcheme_fluid/` beside the core record.

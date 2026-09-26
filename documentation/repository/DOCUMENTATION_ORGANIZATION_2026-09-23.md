# Documentation organization, 2026-09-23

Executed by an opus subagent (documentation organizer) on the owner's instruction; the report itself was saved by the orchestrating session because the agent's file write into the main checkout was blocked. No Java changed, Gradle was not run, no tracked file in the main checkout was edited in place. A checksum check showed all 182 original files present at their new locations.

## Layout after the change (main checkout, git-ignored folders)

```text
documentation/  INDEX.md (batch table, status notes, file lookup), README.md (layout and update rule)
  2026-08-31-v3-stage-trace-truncation/ (archive/)   2026-08-31-v3-side-draws/   2026-08-31-v3-vdu/
  2026-09-01-reaction-thermochemistry-tool/   2026-09-01-v3-holland-benchmark/ (sources/)
  2026-09-01-v3-steam-stripping/   2026-09-01-v3-full-cdu-draw-wall/   2026-09-05-v3-solver-core-review/
  2026-09-06-v3-literature-cdu-pumparounds/ (screenshots/ = old gui/, sources/FULL_TEXT.pdf, codex-branch/)
  2026-09-07-v3-trace-scaling-and-truncation/   2026-09-08-v3-free-water-trays/   2026-09-08-v3-newton-alternatives/
  2026-09-09-v3-convergence-time/   2026-09-09-v1-calculator-removal/   2026-09-09-dwsim-chemsep-trials/
  2026-09-09-solver-feasibility-studies/   2026-09-10-v4-neural-initializer/   2026-09-15-fluid-network/
  2026-09-17-crude-regrouping/   2026-09-18-tray-pressure-drop/ (handoff-bounded-solve-path/)
  2026-09-18-solid-phase-fluid-system/ (screenshots/)   2026-09-23-fluid-scheduling-rest/   reference/   repository/
research/  INDEX.md added; subfolders unchanged (tracked MATERIALS.md and relocation-manifest.json cite their paths)
tools/     INDEX.md added; scripts/ became tools/v3-cold-core-benchmark/
```

Removed: `docs/`, `design/`, `scripts/`, `documentation/archives/`, the empty `documentation/retired-cdu17/`, six `__pycache__` folders (37 `.pyc`). Left in place: `examples/` (tracked, only indexed), `benchmarks/`, `.collab/`, `BRANCH_HANDOFF*` in the root.

## Batches and statuses

| Batch | Status |
|---|---|
| v3-stage-trace-truncation | Implemented 2026-09-01 via PR #1 `bdfeff1`; 55 kPa remask abandoned 2026-08-31 (negative P0, P0b never run) |
| v3-side-draws, v3-holland-benchmark, v3-steam-stripping | Implemented 2026-09-01 (PR #1) |
| v3-vdu | Planned (case A adopted 2026-09-08, WP0 not frozen) |
| reaction-thermochemistry-tool | Planned (research and plan only) |
| v3-full-cdu-draw-wall | Superseded 2026-09-06 by the literature CDU batch; A0 `9d18bb9` only on `claude/wall-probes`; W-1..W-4 candidates rejected; side strippers never built |
| v3-solver-core-review | Implemented 2026-09-05 (`0f94ffe`, `6c7d446`); redesign proposal not pursued |
| v3-literature-cdu-pumparounds, v3-trace-scaling-and-truncation, v3-free-water-trays | Implemented 2026-09-08 (`f4e600a`, fixes to `e8d8937`) |
| v3-newton-alternatives | Abandoned 2026-09-09 (DF-SANE found no nontrivial root) |
| v3-convergence-time | Implemented 2026-09-09 |
| v1-calculator-removal | Implemented 2026-09-09 (`8547fea`) |
| dwsim-chemsep-trials | Concluded 2026-09-10 (research only) |
| solver-feasibility-studies | Concluded 2026-09-18 (research only) |
| v4-neural-initializer | Implemented 2026-09-14 |
| fluid-network | Implemented 2026-09-17 |
| crude-regrouping | Implemented 2026-09-18; follow-ups planned |
| tray-pressure-drop | Implemented 2026-09-22 |
| solid-phase-fluid-system | Implemented 2026-09-23 (v0.2.0) |
| fluid-scheduling-rest | In progress since 2026-09-23 |

Evidence per batch is in the "Batch notes" section of `documentation/INDEX.md`: memory lines, first-parent commit dates, `git branch --contains` checks for A0, W and Codex branches, and each document's own status header.

## Tracked changes (branch `claude/docs-organization`, fast-forwarded onto `main` as `42fdf41`)

- `37d3d29` Add a changelog and version the mod at 0.2.0: `CHANGELOG.md` (Keep a Changelog; `[Unreleased]`, `[0.2.0] - 2026-09-23` for the solid-phase merge plus `AGENTS.md`, `[0.1.0] - 2026-09-22` backfilled one line per batch), `gradle.properties` `mod_version=0.2.0`.
- `42fdf41` Record where documents, research and tools live, and when they are updated: `AGENTS.md` review/plan bullet now points to `documentation/<batch>/...`, plus a new organization and versioning section.
- `.gitignore` needed no change.

## Decisions taken where the brief was open

- Folder names carry full start dates (`YYYY-MM-DD-topic`) so they sort within a month.
- New status word "Concluded" for research-only batches, explained in the index legend.
- The literature CDU merge is split into three batches (pumparounds/handoff, trace scaling and truncation, free water): merged together, separate topics.
- Placement by where the work landed: `V4_HYBRID_INITIALIZER_REVIEW.md` in crude-regrouping; `V4_TRANSFORMER_TRAINING_GUIDE.md` in V4; `FLUID_SOLVER_REGRESSION_RERECORD.md` in solid-phase (landed as `fca6a15`); `V3_CDU17_RETIREMENT_REVIEW.md` in literature CDU; `HYBRID_SOLVER_CODE_GUIDE.md` in `reference/`.
- Research subfolders not renamed (tracked files cite them). `VDU_SIMULATION_RESEARCH.md` stays in `research/crude-regrouping/notes/`; the VDU row points to it.
- Extra copies: 5 docs from the suspended Codex worktree `run/codex-worktrees/v3-literature-cdu` into `codex-branch/` (including its newer `V3_LITERATURE_CDU_PA_PLAN.md`); 3 Plan 2 docs extracted read-only with `git show` from stash `ab4df29` into `2026-09-08-v3-newton-alternatives/` (stash untouched).
- Duplicate kept: `research/convergence-time-optimization-2026-09-09.md` is identical to `V3_CONVERGENCE_TIME_OPTIMIZATION_PROPOSALS.md`; moved next to it, can be deleted.
- Links: 96 markdown link targets rewritten automatically; a few fixed by hand (Plan 2 link in the convergence-time copies, three provenance/scripts/research links, relative prefixes in the Plan 2 docs, `tools/v3-cold-core-benchmark/README.md` paths). 26 links were already broken (targets in `output/`, a stash-only harness, deleted sources).

## Moves

- From flat `documentation/` into the batch folders, file names unchanged. Special cases: `archives/v3-mask-refresh-20260831-222632` to `2026-08-31-v3-stage-trace-truncation/archive/`; `archives/holland-example-3-2-ocr-excerpts.txt` to `2026-09-01-v3-holland-benchmark/sources/`; `FULL_TEXT.pdf` to `2026-09-06-.../sources/`; `gui/` to `2026-09-06-.../screenshots/`; `HYBRID_SOLVER_CODE_GUIDE.md` to `reference/`.
- From other folders: `docs/solid-phase-fluid-system-plan.md` to solid-phase; `docs/v1-calculator-removal-plan.md` to v1-calculator-removal; `design/SIMULATION_ENGINE_AND_FLUID_NETWORK_{DESIGN,WORK_ORDER}.md` to fluid-network; `scripts/*` to `tools/v3-cold-core-benchmark/`; `research/convergence-time-optimization-2026-09-09.md` to v3-convergence-time; `research/deleted-branches-2026-09-09.txt` to `repository/`.
- Copied from the `solid-phase-fluid-system-plan-4369b0` worktree: 16 solid-phase docs plus `screenshots/` (269 files) into solid-phase; `FLUID_ISLAND_REST_PLAN*.md` (revision 3) into fluid-scheduling-rest. The `agent-*` worktree copies are byte-identical; their screenshots are a subset.
- Copied from the `tray-pressure-study-method-a19e2d` worktree: `handoff-bounded-solve-path/` into tray-pressure-drop.
- Created: `documentation/INDEX.md`, `documentation/README.md`, `research/INDEX.md`, `tools/INDEX.md`.

The full 158-row old-to-new table follows at the end when it was available at report time.

## Not classified or not recoverable

- Six early docs named in memory were not found on disk, in any worktree, or in git history: `V3_LOW_PRESSURE_CORRECTNESS_PLAN(_V2).md`, `V3_THERMO_DATA_PIPELINE.md`, `V3_COLUMN_DATA_PIPELINE.md`, `NRTL_GAMMA_PHI_MATERIAL_PROPERTIES.md`, `V3_MATERIAL_PROPERTIES_PLAN.md`.
- The ~700 MB convergence-review journals were left in a worktree that no longer exists.
- The `benchmarks/solver-alternatives/` harness exists only in stash `ab4df29`.

## Follow-ups

- Tracked files still cite the old flat paths: `build.gradle` (`FLUID_NETWORK_PROGRESS.md`), several fluid Java sources and tests, `V3FreeWaterContinuation`, `V3ConvergenceClosureTest`, `v3-cold-core-v1.json`, `V3Tjl19PropertyPackage` (cites `docs/tjl19-property-provenance.md`). Table in `INDEX.md`.
- `examples/Fluid-Benchmarks.py` still defaults to `createcheme-0.1.0.jar`; after the bump the jar is `createcheme-0.2.0.jar`.
- `research/crude-regrouping/relocation-manifest.json` lists two deleted `.pyc` files, so `verify_consolidation.py` will report them missing.
- When the fluid-scheduler branch merges, copy its reports from that worktree's `documentation/fluid-scheduler/` into `2026-09-23-fluid-scheduling-rest/` and update the batch row, the changelog and the version.

## Move table (old path to new path)

| # | Operation | Old path | New path |
|---:|---|---|---|
| 1 | moved | `documentation/V3_STAGE_TRACE_TRUNCATION_PLAN.md` | `documentation/2026-08-31-v3-stage-trace-truncation/V3_STAGE_TRACE_TRUNCATION_PLAN.md` |
| 2 | moved | `documentation/V3_55KPA_WALL_ACTION_PLAN.md` | `documentation/2026-08-31-v3-stage-trace-truncation/V3_55KPA_WALL_ACTION_PLAN.md` |
| 3 | moved | `documentation/archives/v3-mask-refresh-20260831-222632` | `documentation/2026-08-31-v3-stage-trace-truncation/archive/v3-mask-refresh-20260831-222632` |
| 4 | moved | `documentation/V3_SIDE_DRAW_PLAN.md` | `documentation/2026-08-31-v3-side-draws/V3_SIDE_DRAW_PLAN.md` |
| 5 | moved | `documentation/V3_SIDE_DRAW_RESULTS.md` | `documentation/2026-08-31-v3-side-draws/V3_SIDE_DRAW_RESULTS.md` |
| 6 | moved | `documentation/V3_SIDE_DRAW_REVIEW.md` | `documentation/2026-08-31-v3-side-draws/V3_SIDE_DRAW_REVIEW.md` |
| 7 | moved | `documentation/V3_SIDE_DRAW_LITERATURE_CASE.md` | `documentation/2026-08-31-v3-side-draws/V3_SIDE_DRAW_LITERATURE_CASE.md` |
| 8 | moved | `documentation/V3_TRUNCATION_OPTIMIZATION.md` | `documentation/2026-08-31-v3-side-draws/V3_TRUNCATION_OPTIMIZATION.md` |
| 9 | moved | `documentation/V3_VDU_LITERATURE_AND_THERMO_DATA.md` | `documentation/2026-08-31-v3-vdu/V3_VDU_LITERATURE_AND_THERMO_DATA.md` |
| 10 | moved | `documentation/V3_VDU_CASE_A_PLAN.md` | `documentation/2026-08-31-v3-vdu/V3_VDU_CASE_A_PLAN.md` |
| 11 | moved | `documentation/V3_VDU_CASE_A_PLAN_REVIEW.md` | `documentation/2026-08-31-v3-vdu/V3_VDU_CASE_A_PLAN_REVIEW.md` |
| 12 | moved | `documentation/REACTION_ENTHALPY_KINETICS_TOOL_RESEARCH.md` | `documentation/2026-09-01-reaction-thermochemistry-tool/REACTION_ENTHALPY_KINETICS_TOOL_RESEARCH.md` |
| 13 | moved | `documentation/REACTION_TOOL_IMPLEMENTATION_PLAN.md` | `documentation/2026-09-01-reaction-thermochemistry-tool/REACTION_TOOL_IMPLEMENTATION_PLAN.md` |
| 14 | moved | `documentation/V3_HOLLAND_BENCHMARK_CASE.md` | `documentation/2026-09-01-v3-holland-benchmark/V3_HOLLAND_BENCHMARK_CASE.md` |
| 15 | moved | `documentation/archives/holland-example-3-2-ocr-excerpts.txt` | `documentation/2026-09-01-v3-holland-benchmark/sources/holland-example-3-2-ocr-excerpts.txt` |
| 16 | moved | `documentation/V3_STEAM_STRIPPING_PLAN.md` | `documentation/2026-09-01-v3-steam-stripping/V3_STEAM_STRIPPING_PLAN.md` |
| 17 | moved | `documentation/V3_STEAM_STRIPPING_REVIEW.md` | `documentation/2026-09-01-v3-steam-stripping/V3_STEAM_STRIPPING_REVIEW.md` |
| 18 | moved | `documentation/V3_FULL_CDU_PLAN.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_FULL_CDU_PLAN.md` |
| 19 | moved | `documentation/V3_CDU_CONVERGENCE_RISK.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_CDU_CONVERGENCE_RISK.md` |
| 20 | moved | `documentation/V3_COLD_DOE_BENCHMARK.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_COLD_DOE_BENCHMARK.md` |
| 21 | moved | `documentation/V3_COLD_DOE_FAILURE_ANALYSIS.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_COLD_DOE_FAILURE_ANALYSIS.md` |
| 22 | moved | `documentation/V3_COLD_DOE_A0_RERUN.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_COLD_DOE_A0_RERUN.md` |
| 23 | moved | `documentation/V3_A0_WET_LANE_REVIEW.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_A0_WET_LANE_REVIEW.md` |
| 24 | moved | `documentation/V3_FULL_CONVERGENCE_PLAN.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_FULL_CONVERGENCE_PLAN.md` |
| 25 | moved | `documentation/V3_TRACE_PAIR_ANCHOR_BENCHMARK.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_TRACE_PAIR_ANCHOR_BENCHMARK.md` |
| 26 | moved | `documentation/V3_W1_DRY_TRAY_ACTIVATION_BENCHMARK.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W1_DRY_TRAY_ACTIVATION_BENCHMARK.md` |
| 27 | moved | `documentation/V3_W1_DRY_TRAY_FOUNDATION.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W1_DRY_TRAY_FOUNDATION.md` |
| 28 | moved | `documentation/V3_W1_W10_DRY_STATE_PROBE.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W1_W10_DRY_STATE_PROBE.md` |
| 29 | moved | `documentation/V3_W1_W10_DRY_TARGET_RERESOLVE.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W1_W10_DRY_TARGET_RERESOLVE.md` |
| 30 | moved | `documentation/V3_W1_W10_LIVE_HANDOFF_BENCHMARK.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W1_W10_LIVE_HANDOFF_BENCHMARK.md` |
| 31 | moved | `documentation/V3_W1_W5B_DRY_TRAY_RERESOLVE.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W1_W5B_DRY_TRAY_RERESOLVE.md` |
| 32 | moved | `documentation/V3_W2_W3_CONDITIONING_REVIEW.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W2_W3_CONDITIONING_REVIEW.md` |
| 33 | moved | `documentation/V3_W2_W3_DIRECT_OFFENDER_AUTOPSY.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W2_W3_DIRECT_OFFENDER_AUTOPSY.md` |
| 34 | moved | `documentation/V3_W2_W3_NULL_DIRECTION_AUTOPSY.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W2_W3_NULL_DIRECTION_AUTOPSY.md` |
| 35 | moved | `documentation/V3_W2_W3_RANK_ONE_BENCHMARK.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W2_W3_RANK_ONE_BENCHMARK.md` |
| 36 | moved | `documentation/V3_W3_HARD_COMMON_MODE_CONSTRAINT_BENCHMARK.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W3_HARD_COMMON_MODE_CONSTRAINT_BENCHMARK.md` |
| 37 | moved | `documentation/V3_W3_TERMINAL_RESCUE_BENCHMARK.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W3_TERMINAL_RESCUE_BENCHMARK.md` |
| 38 | moved | `documentation/V3_W4_STARVATION_SCREEN_BENCHMARK.md` | `documentation/2026-09-01-v3-full-cdu-draw-wall/V3_W4_STARVATION_SCREEN_BENCHMARK.md` |
| 39 | moved | `documentation/V3_SOLVER_DESIGN_REVIEW.md` | `documentation/2026-09-05-v3-solver-core-review/V3_SOLVER_DESIGN_REVIEW.md` |
| 40 | moved | `documentation/V3_MAIN_SOLVER_REVIEW.md` | `documentation/2026-09-05-v3-solver-core-review/V3_MAIN_SOLVER_REVIEW.md` |
| 41 | moved | `documentation/V3_CORE_FIXES_RESULTS.md` | `documentation/2026-09-05-v3-solver-core-review/V3_CORE_FIXES_RESULTS.md` |
| 42 | moved | `documentation/V3_COLD_CORE_BENCHMARK_PLAN.md` | `documentation/2026-09-05-v3-solver-core-review/V3_COLD_CORE_BENCHMARK_PLAN.md` |
| 43 | moved | `documentation/V3_COLD_CORE_BENCHMARK_RESULTS.md` | `documentation/2026-09-05-v3-solver-core-review/V3_COLD_CORE_BENCHMARK_RESULTS.md` |
| 44 | moved | `documentation/V3_CONVERGENCE_ANALYSIS.md` | `documentation/2026-09-05-v3-solver-core-review/V3_CONVERGENCE_ANALYSIS.md` |
| 45 | moved | `documentation/V3_FUNDAMENTAL_SOLVER_REDESIGN.md` | `documentation/2026-09-05-v3-solver-core-review/V3_FUNDAMENTAL_SOLVER_REDESIGN.md` |
| 46 | moved | `documentation/V3_REDESIGN_ALTERNATIVES_NOTES.md` | `documentation/2026-09-05-v3-solver-core-review/V3_REDESIGN_ALTERNATIVES_NOTES.md` |
| 47 | moved | `documentation/V3_REDESIGN_FORMULATION_NOTES.md` | `documentation/2026-09-05-v3-solver-core-review/V3_REDESIGN_FORMULATION_NOTES.md` |
| 48 | moved | `documentation/V3_REDESIGN_NUMERICS_NOTES.md` | `documentation/2026-09-05-v3-solver-core-review/V3_REDESIGN_NUMERICS_NOTES.md` |
| 49 | moved | `documentation/V3_TRUNCATION_FAILURE_INVESTIGATION.md` | `documentation/2026-09-05-v3-solver-core-review/V3_TRUNCATION_FAILURE_INVESTIGATION.md` |
| 50 | moved | `documentation/V3_F06_FAILED_TRAY_PROFILE.md` | `documentation/2026-09-05-v3-solver-core-review/V3_F06_FAILED_TRAY_PROFILE.md` |
| 51 | moved | `documentation/V3_LITERATURE_CDU_PA_PLAN.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_LITERATURE_CDU_PA_PLAN.md` |
| 52 | moved | `documentation/V3_LITERATURE_CDU_HANDOFF.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_LITERATURE_CDU_HANDOFF.md` |
| 53 | moved | `documentation/V3_LITERATURE_CDU_HANDOFF_REVIEW.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_LITERATURE_CDU_HANDOFF_REVIEW.md` |
| 54 | moved | `documentation/V3_PUMPAROUND_HEAT_EXCHANGER_PLAN.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_PUMPAROUND_HEAT_EXCHANGER_PLAN.md` |
| 55 | moved | `documentation/V3_PUMPAROUND_STEAM_REVIEW.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_PUMPAROUND_STEAM_REVIEW.md` |
| 56 | moved | `documentation/V3_PUMPAROUND_TRANSPORT_REVIEW.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_PUMPAROUND_TRANSPORT_REVIEW.md` |
| 57 | moved | `documentation/V3_PUMPAROUND_GUI_REVIEW.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_PUMPAROUND_GUI_REVIEW.md` |
| 58 | moved | `documentation/V3_CONDENSER_ENERGY_AUDIT_REVIEW.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_CONDENSER_ENERGY_AUDIT_REVIEW.md` |
| 59 | moved | `documentation/V3_THESIS_PUMPAROUND_ARRANGEMENT.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_THESIS_PUMPAROUND_ARRANGEMENT.md` |
| 60 | moved | `documentation/V3_WALL_AND_40MW_BALANCE_ANALYSIS.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_WALL_AND_40MW_BALANCE_ANALYSIS.md` |
| 61 | moved | `documentation/V3_RAMP_PREDICTOR_REVIEW.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_RAMP_PREDICTOR_REVIEW.md` |
| 62 | moved | `documentation/V3_LITERATURE_TOP_TEMPERATURE_CHECK.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_LITERATURE_TOP_TEMPERATURE_CHECK.md` |
| 63 | moved | `documentation/V3_HANDOFF_2026-09-08.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_HANDOFF_2026-09-08.md` |
| 64 | moved | `documentation/V3_HANDOFF_2026-09-08_REVIEW.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_HANDOFF_2026-09-08_REVIEW.md` |
| 65 | moved | `documentation/V3_HANDOFF_2026-09-08_REVIEW_PROBES.log` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_HANDOFF_2026-09-08_REVIEW_PROBES.log` |
| 66 | moved | `documentation/V3_HANDOFF_FIXES_VERIFICATION_PROBES.log` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_HANDOFF_FIXES_VERIFICATION_PROBES.log` |
| 67 | moved | `documentation/V3_HANDOFF_FIXES_VERIFICATION_REVIEW.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_HANDOFF_FIXES_VERIFICATION_REVIEW.md` |
| 68 | moved | `documentation/V3_HANDOFF_REVIEW_FIXES.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_HANDOFF_REVIEW_FIXES.md` |
| 69 | moved | `documentation/V3_CDU17_RETIREMENT_REVIEW.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/V3_CDU17_RETIREMENT_REVIEW.md` |
| 70 | moved | `documentation/FULL_TEXT.pdf` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/sources/FULL_TEXT.pdf` |
| 71 | moved | `documentation/gui` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/screenshots` |
| 72 | copied | `run/codex-worktrees/v3-literature-cdu/documentation/V3_LITERATURE_CDU_HEAT_BALANCE_ANALYSIS.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/codex-branch/V3_LITERATURE_CDU_HEAT_BALANCE_ANALYSIS.md` |
| 73 | copied | `run/codex-worktrees/v3-literature-cdu/documentation/V3_PROPERTY_PACKAGE_COMPARISON.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/codex-branch/V3_PROPERTY_PACKAGE_COMPARISON.md` |
| 74 | copied | `run/codex-worktrees/v3-literature-cdu/documentation/V3_LITERATURE_CDU_MILESTONE_1.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/codex-branch/V3_LITERATURE_CDU_MILESTONE_1.md` |
| 75 | copied | `run/codex-worktrees/v3-literature-cdu/documentation/V3_LITERATURE_CDU_REFERENCE_MILESTONE.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/codex-branch/V3_LITERATURE_CDU_REFERENCE_MILESTONE.md` |
| 76 | moved | `documentation/V3_TJL19_WET_DRAW_STALL_ROOT_CAUSE.md` | `documentation/2026-09-07-v3-trace-scaling-and-truncation/V3_TJL19_WET_DRAW_STALL_ROOT_CAUSE.md` |
| 77 | moved | `documentation/V3_TJL19_WET_DRAW_TRAY_BALANCES.md` | `documentation/2026-09-07-v3-trace-scaling-and-truncation/V3_TJL19_WET_DRAW_TRAY_BALANCES.md` |
| 78 | moved | `documentation/V3_TRACE_BALANCE_REMEDIES_REVIEW.md` | `documentation/2026-09-07-v3-trace-scaling-and-truncation/V3_TRACE_BALANCE_REMEDIES_REVIEW.md` |
| 79 | moved | `documentation/V3_TRUNCATION_EVALUATION.md` | `documentation/2026-09-07-v3-trace-scaling-and-truncation/V3_TRUNCATION_EVALUATION.md` |
| 80 | moved | `documentation/V3_PHASE_SPECIFIC_TRUNCATION_PLAN.md` | `documentation/2026-09-07-v3-trace-scaling-and-truncation/V3_PHASE_SPECIFIC_TRUNCATION_PLAN.md` |
| 81 | moved | `documentation/V3_PHASE_SPECIFIC_TRUNCATION_REVIEW.md` | `documentation/2026-09-07-v3-trace-scaling-and-truncation/V3_PHASE_SPECIFIC_TRUNCATION_REVIEW.md` |
| 82 | moved | `documentation/V3_CLOSURE_AND_BAND_REVIEW.md` | `documentation/2026-09-07-v3-trace-scaling-and-truncation/V3_CLOSURE_AND_BAND_REVIEW.md` |
| 83 | moved | `documentation/V3_FREE_WATER_TRAYS_PLAN.md` | `documentation/2026-09-08-v3-free-water-trays/V3_FREE_WATER_TRAYS_PLAN.md` |
| 84 | moved | `documentation/V3_FREE_WATER_TRAYS_REVIEW.md` | `documentation/2026-09-08-v3-free-water-trays/V3_FREE_WATER_TRAYS_REVIEW.md` |
| 85 | moved | `documentation/V3_FREE_WATER_CONTINUATION_REVIEW.md` | `documentation/2026-09-08-v3-free-water-trays/V3_FREE_WATER_CONTINUATION_REVIEW.md` |
| 86 | moved | `docs/v1-calculator-removal-plan.md` | `documentation/2026-09-09-v1-calculator-removal/v1-calculator-removal-plan.md` |
| 87 | moved | `documentation/V3_CONVERGENCE_TIME_OPTIMIZATION_PROPOSALS.md` | `documentation/2026-09-09-v3-convergence-time/V3_CONVERGENCE_TIME_OPTIMIZATION_PROPOSALS.md` |
| 88 | moved | `documentation/V3_OPTIMIZED_SOLVER_ROBUSTNESS_REVIEW.md` | `documentation/2026-09-09-v3-convergence-time/V3_OPTIMIZED_SOLVER_ROBUSTNESS_REVIEW.md` |
| 89 | moved | `documentation/V3_OPTIMIZED_SOLVER_REGRESSION_FIX_REVIEW.md` | `documentation/2026-09-09-v3-convergence-time/V3_OPTIMIZED_SOLVER_REGRESSION_FIX_REVIEW.md` |
| 90 | moved | `research/convergence-time-optimization-2026-09-09.md` | `documentation/2026-09-09-v3-convergence-time/convergence-time-optimization-2026-09-09.md` |
| 91 | moved | `documentation/DWSIM_DATA_GENERATION.md` | `documentation/2026-09-09-dwsim-chemsep-trials/DWSIM_DATA_GENERATION.md` |
| 92 | moved | `documentation/DWSIM_NATIVE_PSEUDOCOMPONENT_TRIAL.md` | `documentation/2026-09-09-dwsim-chemsep-trials/DWSIM_NATIVE_PSEUDOCOMPONENT_TRIAL.md` |
| 93 | moved | `documentation/DWSIM_REBOILER_DUTY_INVESTIGATION.md` | `documentation/2026-09-09-dwsim-chemsep-trials/DWSIM_REBOILER_DUTY_INVESTIGATION.md` |
| 94 | moved | `documentation/CHEMSEP_NATIVE_PSEUDOCOMPONENT_TRIAL.md` | `documentation/2026-09-09-dwsim-chemsep-trials/CHEMSEP_NATIVE_PSEUDOCOMPONENT_TRIAL.md` |
| 95 | moved | `documentation/CHEMSEP_PETROLEUM_REPLACEMENT.md` | `documentation/2026-09-09-dwsim-chemsep-trials/CHEMSEP_PETROLEUM_REPLACEMENT.md` |
| 96 | moved | `documentation/CHEMSEP_TJL19_PROGRESSIVE_TEST.md` | `documentation/2026-09-09-dwsim-chemsep-trials/CHEMSEP_TJL19_PROGRESSIVE_TEST.md` |
| 97 | moved | `documentation/CHEMSEP_TJL19_ROOT_CAUSE.md` | `documentation/2026-09-09-dwsim-chemsep-trials/CHEMSEP_TJL19_ROOT_CAUSE.md` |
| 98 | moved | `documentation/CHEMSEP_V3_TJL19_TRIAL.md` | `documentation/2026-09-09-dwsim-chemsep-trials/CHEMSEP_V3_TJL19_TRIAL.md` |
| 99 | moved | `documentation/DSTWU_SHORTCUT_FEASIBILITY.md` | `documentation/2026-09-09-solver-feasibility-studies/DSTWU_SHORTCUT_FEASIBILITY.md` |
| 100 | moved | `documentation/BEND_SOLVER_FEASIBILITY.md` | `documentation/2026-09-09-solver-feasibility-studies/BEND_SOLVER_FEASIBILITY.md` |
| 101 | moved | `documentation/V3_NEURAL_INITIALIZER_MODEL_SURVEY.md` | `documentation/2026-09-10-v4-neural-initializer/V3_NEURAL_INITIALIZER_MODEL_SURVEY.md` |
| 102 | moved | `documentation/V3_NEURAL_INITIALIZER_CONTRACT.md` | `documentation/2026-09-10-v4-neural-initializer/V3_NEURAL_INITIALIZER_CONTRACT.md` |
| 103 | moved | `documentation/V3_REFINERY_INITIALIZER_REQUIREMENTS.md` | `documentation/2026-09-10-v4-neural-initializer/V3_REFINERY_INITIALIZER_REQUIREMENTS.md` |
| 104 | moved | `documentation/V4_TRANSFORMER_INITIALIZER_REVIEW.md` | `documentation/2026-09-10-v4-neural-initializer/V4_TRANSFORMER_INITIALIZER_REVIEW.md` |
| 105 | moved | `documentation/V4_IMPLEMENTATION_RESULTS.md` | `documentation/2026-09-10-v4-neural-initializer/V4_IMPLEMENTATION_RESULTS.md` |
| 106 | moved | `documentation/V4_LNN_ONLY_GAP_ANALYSIS.md` | `documentation/2026-09-10-v4-neural-initializer/V4_LNN_ONLY_GAP_ANALYSIS.md` |
| 107 | moved | `documentation/V4_BENCHMARK_POPULATION_REVIEW.md` | `documentation/2026-09-10-v4-neural-initializer/V4_BENCHMARK_POPULATION_REVIEW.md` |
| 108 | moved | `documentation/V4_TRANSFORMER_TRAINING_GUIDE.md` | `documentation/2026-09-10-v4-neural-initializer/V4_TRANSFORMER_TRAINING_GUIDE.md` |
| 109 | moved | `design/SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN.md` | `documentation/2026-09-15-fluid-network/SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN.md` |
| 110 | moved | `design/SIMULATION_ENGINE_AND_FLUID_NETWORK_WORK_ORDER.md` | `documentation/2026-09-15-fluid-network/SIMULATION_ENGINE_AND_FLUID_NETWORK_WORK_ORDER.md` |
| 111 | moved | `documentation/SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN_REVIEW.md` | `documentation/2026-09-15-fluid-network/SIMULATION_ENGINE_AND_FLUID_NETWORK_DESIGN_REVIEW.md` |
| 112 | moved | `documentation/FLUID_NETWORK_PROGRESS.md` | `documentation/2026-09-15-fluid-network/FLUID_NETWORK_PROGRESS.md` |
| 113 | moved | `documentation/FLUID_NETWORK_BUILD.md` | `documentation/2026-09-15-fluid-network/FLUID_NETWORK_BUILD.md` |
| 114 | moved | `documentation/FLUID_NETWORK_ACCEPTANCE.md` | `documentation/2026-09-15-fluid-network/FLUID_NETWORK_ACCEPTANCE.md` |
| 115 | moved | `documentation/FLUID_NETWORK_STRESS_TEST.md` | `documentation/2026-09-15-fluid-network/FLUID_NETWORK_STRESS_TEST.md` |
| 116 | moved | `documentation/FLUID_PROPERTY_COMPATIBILITY.md` | `documentation/2026-09-15-fluid-network/FLUID_PROPERTY_COMPATIBILITY.md` |
| 117 | moved | `documentation/FLUID_TEST_EXECUTION_ORDER.md` | `documentation/2026-09-15-fluid-network/FLUID_TEST_EXECUTION_ORDER.md` |
| 118 | moved | `documentation/FLUID_LUNA_TEST_REVIEW.md` | `documentation/2026-09-15-fluid-network/FLUID_LUNA_TEST_REVIEW.md` |
| 119 | moved | `documentation/FLUID_LUNA_PHYSICAL_TEST_REVIEW.md` | `documentation/2026-09-15-fluid-network/FLUID_LUNA_PHYSICAL_TEST_REVIEW.md` |
| 120 | moved | `documentation/FLUID_SOL_TEST_REVIEW.md` | `documentation/2026-09-15-fluid-network/FLUID_SOL_TEST_REVIEW.md` |
| 121 | moved | `documentation/FLUID_NETWORK_IMPLEMENTATION_REVIEW.md` | `documentation/2026-09-15-fluid-network/FLUID_NETWORK_IMPLEMENTATION_REVIEW.md` |
| 122 | moved | `documentation/FLUID_SOLVER_OPTIMIZATION_PROGRESS.md` | `documentation/2026-09-15-fluid-network/FLUID_SOLVER_OPTIMIZATION_PROGRESS.md` |
| 123 | moved | `documentation/FLUID_SOLVER_OPTIMIZATION_REVIEW.md` | `documentation/2026-09-15-fluid-network/FLUID_SOLVER_OPTIMIZATION_REVIEW.md` |
| 124 | moved | `documentation/FLUID_POOL_MEASUREMENT.md` | `documentation/2026-09-15-fluid-network/FLUID_POOL_MEASUREMENT.md` |
| 125 | moved | `documentation/CRUDE_REGROUPING_PLAN_REVIEW.md` | `documentation/2026-09-17-crude-regrouping/CRUDE_REGROUPING_PLAN_REVIEW.md` |
| 126 | moved | `documentation/CRUDE_REGROUPING_DATA_GENERATION_REVIEW.md` | `documentation/2026-09-17-crude-regrouping/CRUDE_REGROUPING_DATA_GENERATION_REVIEW.md` |
| 127 | moved | `documentation/V4_HYBRID_INITIALIZER_REVIEW.md` | `documentation/2026-09-17-crude-regrouping/V4_HYBRID_INITIALIZER_REVIEW.md` |
| 128 | moved | `documentation/TRAY_PRESSURE_METHOD_REVIEW.md` | `documentation/2026-09-18-tray-pressure-drop/TRAY_PRESSURE_METHOD_REVIEW.md` |
| 129 | moved | `documentation/TRAY_PRESSURE_IMPLEMENTATION_NOTES.md` | `documentation/2026-09-18-tray-pressure-drop/TRAY_PRESSURE_IMPLEMENTATION_NOTES.md` |
| 130 | copied | `.claude/worktrees/tray-pressure-study-method-a19e2d/documentation/handoff-bounded-solve-path` | `documentation/2026-09-18-tray-pressure-drop/handoff-bounded-solve-path` |
| 131 | moved | `docs/solid-phase-fluid-system-plan.md` | `documentation/2026-09-18-solid-phase-fluid-system/solid-phase-fluid-system-plan.md` |
| 132 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/SOLID_PHASE_FLUID_SYSTEM_PLAN_REVIEW.md` | `documentation/2026-09-18-solid-phase-fluid-system/SOLID_PHASE_FLUID_SYSTEM_PLAN_REVIEW.md` |
| 133 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/SOLID_PHASE_FLUID_SYSTEM_IMPLEMENTATION_REVIEW.md` | `documentation/2026-09-18-solid-phase-fluid-system/SOLID_PHASE_FLUID_SYSTEM_IMPLEMENTATION_REVIEW.md` |
| 134 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/SOLID_PHASE_STALL_DIAGNOSIS.md` | `documentation/2026-09-18-solid-phase-fluid-system/SOLID_PHASE_STALL_DIAGNOSIS.md` |
| 135 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/SOLID_PHASE_FIXES_PROGRESS.md` | `documentation/2026-09-18-solid-phase-fluid-system/SOLID_PHASE_FIXES_PROGRESS.md` |
| 136 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/SOLID_PHASE_FIXES_2_PROGRESS.md` | `documentation/2026-09-18-solid-phase-fluid-system/SOLID_PHASE_FIXES_2_PROGRESS.md` |
| 137 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/SOLID_PHASE_GUI_PASS.md` | `documentation/2026-09-18-solid-phase-fluid-system/SOLID_PHASE_GUI_PASS.md` |
| 138 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/SOLID_PHASE_FILTER_ISLAND_DIAGNOSIS.md` | `documentation/2026-09-18-solid-phase-fluid-system/SOLID_PHASE_FILTER_ISLAND_DIAGNOSIS.md` |
| 139 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/HYDRAULIC_ROW_SCALE.md` | `documentation/2026-09-18-solid-phase-fluid-system/HYDRAULIC_ROW_SCALE.md` |
| 140 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/PUMP_SHUTOFF_ACTIVE_SET.md` | `documentation/2026-09-18-solid-phase-fluid-system/PUMP_SHUTOFF_ACTIVE_SET.md` |
| 141 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/TANK_NODE_BLOCK.md` | `documentation/2026-09-18-solid-phase-fluid-system/TANK_NODE_BLOCK.md` |
| 142 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/JUNCTION_PHANTOM_TRACE.md` | `documentation/2026-09-18-solid-phase-fluid-system/JUNCTION_PHANTOM_TRACE.md` |
| 143 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/FULL_TANK_SOLIDS_EVENT.md` | `documentation/2026-09-18-solid-phase-fluid-system/FULL_TANK_SOLIDS_EVENT.md` |
| 144 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/ELEVATED_LINE_PROBE.md` | `documentation/2026-09-18-solid-phase-fluid-system/ELEVATED_LINE_PROBE.md` |
| 145 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/DEAD_HEADED_LINE.md` | `documentation/2026-09-18-solid-phase-fluid-system/DEAD_HEADED_LINE.md` |
| 146 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/FLUID_SOLVER_REGRESSION_RERECORD.md` | `documentation/2026-09-18-solid-phase-fluid-system/FLUID_SOLVER_REGRESSION_RERECORD.md` |
| 147 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/screenshots` | `documentation/2026-09-18-solid-phase-fluid-system/screenshots` |
| 148 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/FLUID_ISLAND_REST_PLAN.md` | `documentation/2026-09-23-fluid-scheduling-rest/FLUID_ISLAND_REST_PLAN.md` |
| 149 | copied | `.claude/worktrees/solid-phase-fluid-system-plan-4369b0/documentation/FLUID_ISLAND_REST_PLAN_REVIEW.md` | `documentation/2026-09-23-fluid-scheduling-rest/FLUID_ISLAND_REST_PLAN_REVIEW.md` |
| 150 | moved | `documentation/HYBRID_SOLVER_CODE_GUIDE.md` | `documentation/reference/HYBRID_SOLVER_CODE_GUIDE.md` |
| 151 | moved | `research/deleted-branches-2026-09-09.txt` | `documentation/repository/deleted-branches-2026-09-09.txt` |
| 152 | moved | `scripts/README.md` | `tools/v3-cold-core-benchmark/README.md` |
| 153 | moved | `scripts/analyze_v3_cold_core_benchmark.py` | `tools/v3-cold-core-benchmark/analyze_v3_cold_core_benchmark.py` |
| 154 | moved | `scripts/analyze_v3_cold_core_jfr.py` | `tools/v3-cold-core-benchmark/analyze_v3_cold_core_jfr.py` |
| 155 | moved | `scripts/test_v3_cold_core_benchmark.py` | `tools/v3-cold-core-benchmark/test_v3_cold_core_benchmark.py` |
| 156 | moved | `scripts/v3_cold_core_benchmark.py` | `tools/v3-cold-core-benchmark/v3_cold_core_benchmark.py` |
| 157 | moved | `scripts/v3_cold_core_jfr_profile.py` | `tools/v3-cold-core-benchmark/v3_cold_core_jfr_profile.py` |
| 158 | copied | `run/codex-worktrees/v3-literature-cdu/documentation/V3_LITERATURE_CDU_PA_PLAN.md` | `documentation/2026-09-06-v3-literature-cdu-pumparounds/codex-branch/V3_LITERATURE_CDU_PA_PLAN.md` |

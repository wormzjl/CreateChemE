# Fluid scheduling and follow-up probes

This folder holds the one-off probes, instrumentation patches and run scripts of two batches:

- fluid scheduling WP0 to WP4 (batch `2026-09-23-fluid-scheduling-rest`);
- fluid follow-ups F1 to F4 (batch `2026-09-23-fluid-followups`).

None of this was ever committed: each probe sat uncommitted in the worktree while it ran, or was kept in the batch folder. It was moved here on 2026-09-24 by the tooling cleanup (`2026-09-23-fluid-followups/FLUID_TOOLING_CLEANUP_REVIEW.md`), which also removed one hook from the code: F3's per-section byte counter, now `f3/instrumentation/payload-breakdown-hook.patch` (removed in `68d8877`). The batch folders keep their copies as the evidence the reviews cite; every file here is byte-identical to its batch copy (`cleanup-logs/tools-manifest.tsv`).

The WP5 in-game rig is in `../fluid-in-game-rig/`. The analysis scripts for paced benchmark reports (`wp2cmp.js`, `wp2diverge.js` and the rest) are in `../fluid-paced-analysis/`. The dev-client GUI helpers of WP3 and WP4 are in `../mcp-gui-helpers/`.

## Layout

Each package folder is `<package>/{probes,scripts,instrumentation,dev-edits,run-scripts}/` and has its own `README.md` saying what each probe measured, which review section cites it, how it was run and against which commit.

| folder | package | contents |
|---|---|---|
| `wp0/` | WP0, counters and fixtures | the `transient100` fixture probe |
| `wp2/` | WP2, certificates | the stationarity and settling probes; campaign and gate scripts |
| `wp3/` | WP3, presentation | the one-off edit that added the `viewers` profile; campaign and gate scripts |
| `wp4/` | WP4, persistence format 3 | the one-off edit that added the save-time report; campaign and gate scripts |
| `f1/` | F1, pumped fills and holds | four probe tests, trace classifiers, two trace-instrumentation patches, gate and paced scripts |
| `f2/` | F2, placement cost | the placement profile probe (before and after), the per-phase timing patch, the extraction and edit scripts, the gate script |
| `f3/` | F3, checkpoint storage | payload-breakdown and save-timing probes, the atomic-write probe, the detached byte-counter hook, the probe runner and its init script |
| `f4/` | F4, pump P1 and thermo domain | fingerprint, cryogenic-nitrogen and pump-line probes, the NIST data and fit scripts, the `sci.sh` runner, the gate script |

The folder depth matches the batch layout (`<package>/<sub>/<file>`). Scripts that address the repository root as `../../../..` (`f2/scripts/apply-edits.js`, `f4/probes/sci.sh`, `f4/probes/build-nitrogen-viscosity-tables.js`) therefore work unchanged from here.

## Running a probe test: read before placing one in `src/`

Most probes are JUnit classes. To run one, it was copied into the test source set at its package path, run alone, and deleted before any gate run or commit:

```bash
JAVA_OPTS=-Xshare:off ./gradlew.bat fluidRuntimeTest --tests "com.wormzjl.createcheme.runtime.fluid.<Class>" --rerun --offline --console=plain
```

A class placed in `src/test/java/com/wormzjl/createcheme/runtime/fluid/` also matches the `fluidRuntimeTest` gate pattern and the ordinary `test` task. The same holds for `runtime/Fluid*Test` and for `science/fluid/` with `fluidScienceTest`. Left there, a probe widens a gate, so remove it before any gate run and never commit it. The F4 probes avoid this: `sci.sh` compiles them with the science sources into a temporary folder, outside Gradle.

The machine rules hold for every run: no `Endfield.exe`, at least 20 GB free, one Gradle invocation at a time, and no suite while a game runs. The `gates.sh` scripts record the machine state in their `START` lines.

## Run scripts

`<package>/run-scripts/gates.sh` is each package's gate runner. Each run writes a log per gate and `START`/`END` lines in `gates.log`. The F1 to F4 versions take a tag (`gate-<tag>-<name>.log`) and also compare the P12 and P31 fingerprints with `fluid-scheduler/wp2-logs`; the WP2 to WP4 versions write `final-gate-<name>.log`, and the P12/P31 copies were taken by hand. `campaign.sh` and `paced.sh` drove the paced benchmark runs. They are kept as they ran, so they name the worktree `agent-ae139e4fc1b184b36` and the batch-folder log paths; copy one and edit `cd`, `L` and `LOGS` before reusing it. The tooling cleanup's own gate runner is `documentation/2026-09-23-fluid-followups/cleanup-logs/gates.sh`.

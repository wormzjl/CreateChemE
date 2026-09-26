# F3 probes: checkpoint storage (format 4)

Batch `2026-09-23-fluid-followups`, package F3. Review `FLUID_CHECKPOINT_STORAGE_REVIEW.md`; numbers in `f3-tables.md`; outputs in the batch's `f3-logs/` (`f3-payload-*.txt`, `f3-save-*.txt`, `probe-t*.log`). The commit each probe ran on is in `f3-logs/gates.log`.

| file | what it measured | cited in | against |
|---|---|---|---|
| `probes/F3Fixtures.java` (package `runtime.fluid`) | Shared fixtures: the paced benchmark's rest100 (CLOSED) and stress100 (THROUGH) ladders built as `FluidServerBenchmark` builds them, the in-game rest line, a lone tank, and clones of solved islands for 100 and 1,000 islands. | used by the four probes below | |
| `probes/F3PayloadBreakdownProbe-before.java` (class `F3PayloadBreakdownProbe`) | What one island's format-3 JSON payload holds, section by section, in bytes. | review section 2; `f3-tables.md` section 2; `f3-payload-before.txt` | `b86b147` (t1-before) |
| `probes/F3PayloadBreakdownAfter.java` | The same islands in format-4 binary units, section by section. It needs the per-section byte hook of `FluidCheckpointCodec.islandUnit`, which was removed from the code in `68d8877`: apply `instrumentation/payload-breakdown-hook.patch` first. | the same; `f3-payload-after.txt` | the format-4 working tree over `b86b147` (t1-after) |
| `probes/breakdown-table.js` | Builds the before/after payload table from the two outputs. Run it from the repository root; it reads `documentation/fluid-followups/f3-logs/f3-payload-{before,after}.txt`, so edit the paths for another layout. | `f3-tables.md` section 2 | |
| `probes/F3SaveTimingBefore.java` | Save time and bytes of the world's saved data for 100 and 1,000 islands, certified and awake (cold, warm, next cadence, load), in format 3. | review section 5; `f3-tables.md` section 3.1; `f3-save-before.txt` | the `b86b147` sources restored into the tree at `0d44cb0` (t4-before) |
| `probes/F3SaveTimingAfter.java` | The same cases in format 4, with the IO thread's time. | the same; section 3.2; `f3-save-after.txt` | `676fc09`, then `0d44cb0` (t4-after, -r2 to -r4) |
| `probes/AtomicWriteProbe.java` | The cost of one durable atomic file write (temporary file, `force(true)`, `ATOMIC_MOVE`) for 1 to 1,000 files of 5 KB to 50 MB. It decided pack files over one file per island. | review section 1.1; `f3-tables.md` section 1 | JDK 21 only, no mod code: `java AtomicWriteProbe.java <scratch folder>` |
| `instrumentation/payload-breakdown-hook.patch` | Re-attaches the `sections` overload of `FluidCheckpointCodec.islandUnit` (its per-section `section.accept` byte counts). Removed from the code in `68d8877`; the patch applies to `68d8877` and to the branch head, and its result equals `c1b8464` (`cleanup-logs/patch-checks.txt`). It does not change the unit bytes. | cleanup review section 3 | `git apply tools/fluid-probes/f3/instrumentation/payload-breakdown-hook.patch` |

## How they were run

`run-scripts/probe.sh <tag> <TestClass> [extra Gradle arguments]` appends `START`/`END` lines to `f3-logs/gates.log` and runs:

```bash
JAVA_OPTS=-Xshare:off ./gradlew.bat fluidRuntimeTest --tests "com.wormzjl.createcheme.runtime.fluid.<TestClass>" --rerun --offline --console=plain
```

The probe classes sat uncommitted in `src/test/java/com/wormzjl/createcheme/runtime/fluid/` during the run, where they also match the `fluidRuntimeTest` gate pattern, and were removed afterwards. The timing harness adds `--init-script run-scripts/f3-harness.init.gradle`, which gives the `fluidRuntimeTest` JVM a 10 GiB heap and turns the scheduler's self-verification off. Verification re-encodes every reused unit, which would time the check instead of the save. That init script is never used for a gate.

- `run-scripts/gates.sh <tag> [...]`: F3's gate runner.
- `run-scripts/paced.sh <runId>`: the one paced `rest100` run (certificates on, `-PfluidBenchmarkMemory=true`, 60 s + 60 s), with the machine check and crash handling.

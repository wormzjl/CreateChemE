# phase-ports-probes

Measurement drivers and gate logs of the batch `documentation/2026-09-26-phase-ports-and-compressor/`, one subfolder per work package. Nothing here is run by a Gradle task; the gates of record are the Gradle commands recorded in `PHASE_PORTS_REVIEW.md` of that batch. Written in the cloud container (OpenJDK 21.0.10, Linux); a Windows JDK 21.0.11 run differs in one ledger roundoff value (see the review, WP1 section 6).

## wp1/ (commit `35e354d`, review section 6-8 and 11)

- `src/BitwiseProbe.java`: gate G0b. Replays chain-100 and 14 all-BULK scenarios (mixed-gas junctions at 5 s and 0.1 s, rising/falling water lines, a pumped line, a wet-crude drain, a slurry filter, a valve) and dumps every double in hex; run on the base and on WP1, the outputs are byte-identical (`results/probe/out-base/`, `results/probe/out-new/`; `scenarios.txt` is the per-scenario dump, `chain-100.json` the regression encoding, byte-identical to `src/test/resources/fluid/regression/chain-100.json`).
- `src/JunctionProbe.java` and `src/instr/PassiveStepSolver.java`: the instrumented scratch copy of the reconstruction's equation gate used to locate the base defect of review section 8 (a junction seeded dry receiving unsaturated water vapour). The instrumented file is a copy of `PassiveStepSolver.java` at `6e1c5b6` with print statements; never build the mod with it.
- `src/HumidJunctionProbe.java`: reproduces that defect on `6e1c5b6` and on WP1 (identical gate residuals 1.3058e-8 at 0.5 % water, 2.6151e-8 at 1 %).
- `src/science-build.sh`: the first javac-only harness (science package and its Minecraft-free tests) used before Gradle could resolve dependencies in the container; superseded by `tools/cloud-science-harness/`.
- `logs/`: the WP1 gate logs, numbered as in the review: `01` cloud harness, `02` Gradle fluid suites on the clean base worktree (`6e1c5b6`; `02-junction-lines-base.txt` holds the 33 MIXED_GAS/LIQUID_JUNCTION lines, `02-test-names-base.txt` the 406 names), `03` the same on WP1 (413 tests, `03-junction-lines-wp1.txt` identical apart from ms/bytes), `04` exact regression 0.000e+00, `05` the 38 adjacent, `06` GameTest and mcpCompat compile, `07` javac science harness.

How to run a probe: compile the science sources (or use `tools/cloud-science-harness/harness.sh compile`) and `javac`/`java` the probe against that output with gson and EJML on the class path, as `science-build.sh` does; each probe prints where it writes its outputs.

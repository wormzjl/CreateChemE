# Fluid paced-benchmark report analysis

These Node scripts read the `report.json` files that the paced server benchmark writes. The benchmark itself is `FluidServerBenchmark`, run through the tracked Gradle tasks `prepareFluidBenchmark`, `runFluidBenchmarkServer` and `fluidServerBenchmark`, and it writes to `build/reports/fluid/M9/<runId>/report.json`. The harness stays in the code; these scripts are its off-line analysis side. They compare certificates off against on, find where two runs part island by island, list certificate evidence, measure how long a window needs to be, and build the batch tables.

## Batch and provenance

- Written in fluid scheduling **WP2 to WP4** (batch `2026-09-23-fluid-scheduling-rest`). Follow-up **F1** (batch `2026-09-23-fluid-followups`) reused `wp2diverge.js` and `wp2cmp.js` to prove bit-for-bit retries (`FLUID_STARTUP_HOLD_REVIEW.md` sections 3 and 5).
- Moved here on 2026-09-24 by the tooling cleanup (`FLUID_TOOLING_CLEANUP_REVIEW.md`). Every file is byte-identical to its batch copy. `wp3-logs/wp3cmp.js` and `wp3-logs/wp3diverge.js` are identical to `wp2cmp.js` and `wp2diverge.js`, so only the `wp2` names are kept.
- Nothing here was removed from the code; these scripts were never tracked.

## Scripts

| script | usage | what it prints | where it was used |
|---|---|---|---|
| `wp2cmp.js` | `node wp2cmp.js <off/report.json> <on/report.json>` | Certificates off against on: full solves, replayed and rested spans, certified counts, demand, latency, after-GC heap, reference-grid deviation. | WP2 section 6; WP3; WP4 `cmp-rest100-*.txt`; F1 `f1-logs/paced/cmp-*.txt` |
| `wp2diverge.js` | `node wp2diverge.js <a/report.json> <b/report.json>` | Where two runs part, island by island, from the publication fingerprints (reports from `e924eae` on). Also the reference-grid deviation, the ledger against the island's own inventory, and the conservation residual. | WP2 section 4 (the bitwise gap); F1 `FLUID_STARTUP_HOLD_REVIEW.md` section 3 (`f1-logs/paced/diverge-*.txt`) |
| `wp2evidence.js` | `node wp2evidence.js <report.json>` | Certificate evidence of one run: certified counts over time, final refusals by kind and magnitude, and the last measured stationarity per ladder family. | WP2 section 3, `wp2-tables.md` |
| `wp2tables.js` | `node wp2tables.js <M9 directory>` | The WP2 per-profile certificate tables. | `wp2-tables.md` |
| `wp3tables.js` | `node wp3tables.js <M9 directory>` | The WP3 tables: presentation packets, view builds and device presentations per 100 ticks per consumer, and the edit replies. | `wp3-tables.md` |
| `wp3viewers.js` | `node wp3viewers.js <plain/report.json> <viewers/report.json>` | Viewers against no viewers: the islands held at start-up, the islands the scripted edits replaced, and whether every other island's solved publications are identical. | WP3 section 3 (`viewers-comparisons.txt`) |
| `wp4savetime.js` | `node wp4savetime.js <report.json> [...]` | Save-time table: cold, warm and next-cadence saves with capture and encode ms, payloads encoded and copied, and bytes. Written for format 3; F3's format-4 reports add write time and pack bytes, which this script does not print. | WP4 section 3 |
| `winlen.js` | `node winlen.js <M9 directory> <runId> [...]` | Latency, worker time and engine percentiles for the 15, 30, 45, 60, 90 and 120 s prefixes of a window. | `BENCHMARK_WINDOW_LENGTH_ANALYSIS.md` (the owner's 60 s + 60 s decision) |

## How to run

1. Produce the reports with the kept harness, one Gradle invocation at a time, after the machine check (no `Endfield.exe`, at least 20 GB free). The command used from WP5 on is:

   ```bash
   JAVA_OPTS=-Xshare:off ./gradlew.bat fluidServerBenchmark -PfluidBenchmarkProfile=<profile> -PfluidBenchmarkRunId=<new id> -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60 -PfluidStressProfile=true [-PfluidRestDetection=false] [-PfluidCertificateTolerance=1e-7] [-PfluidBenchmarkMemory=true] --offline --console=plain
   ```

   The profiles are the elapsed-window `transient100`, `stress100`, `rest100`, `mixed100` and `viewers`, and the older `one`, `many`, `module` and `contention`. Run length follows the owner's rule: 60 s warm-up, 60 s window, one pair per claim.
2. Run the scripts with Node (22 was used) on `build/reports/fluid/M9/<runId>/report.json`, or on the copies under a batch's `<package>-logs/.../reports/`.

The campaign scripts that drove whole sets of paced runs are in `tools/fluid-probes/<package>/run-scripts/` (`campaign.sh`, `paced.sh`) and `tools/fluid-in-game-rig/campaigns/wp5/paced-campaign.sh`.

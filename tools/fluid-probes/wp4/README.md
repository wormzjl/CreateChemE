# WP4 scripts: persistence format 3

Batch `2026-09-23-fluid-scheduling-rest`, package WP4 (checkpoint format 3, cached payloads, save time).

| file | what | cited in | against |
|---|---|---|---|
| `dev-edits/bench-edit.js` | A one-off source edit that inserted the save-time report (cold, warm and next-cadence saves) into `FluidServerBenchmark`. It was applied once; the result is in commit `4dcfa01`, and the script no longer matches the file. It was kept in the worktree's `build/wp4tools/`, which is not a place that survives, so it is recorded here. | WP4 section 3 | the WP4 working tree at 18:50 on 2026-09-23 |
| `run-scripts/campaign.sh` | The WP4 paced campaign (`rest100` off and on with `-PfluidBenchmarkMemory=true`, `stress100` on), with the machine check and crash handling. | WP4 section 3 | `f6b5dd8` |
| `run-scripts/gates.sh` | The WP4 gates, logged to `wp4-logs/final-gate-*.log`. | WP4 section 2 | `ce25b78` |

The save-time table script `wp4savetime.js` is in `../../fluid-paced-analysis/`. The dev-client helpers are in `../../mcp-gui-helpers/`.

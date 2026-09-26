# WP2 probes and run scripts: certificates

Batch `2026-09-23-fluid-scheduling-rest`, package WP2 (rest and steady-flow certificates).

| file | what it measured | cited in | run | against |
|---|---|---|---|---|
| `probes/StationarityProbe.java.txt` (class `ScratchStationarityProbeTest`) | How fast the benchmark ladders become stationary to 1e-9 per interval. | No review names it; output `wp2-probes/stationarity-probe.xml` | copy to `src/test/java/com/wormzjl/createcheme/runtime/fluid/ScratchStationarityProbeTest.java`, run `fluidRuntimeTest --tests "*ScratchStationarityProbeTest"`, then delete it | WP2 working tree before its first commit (13:18 on 2026-09-23; `3075004` is 14:11) |
| `probes/ScratchWp2SettlingProbeTest.java.txt` | Follows stress100 THROUGH and rest100 CLOSED ladders far past the benchmark window (20,000 s) through the real coordinator, with certificates evaluated but unable to issue (eps_s = 0). It showed that through-flow ladders keep moving by about 2e-7 per interval for hours. | `FLUID_SCHEDULER_WP2_REVIEW.md` section 3.3; outputs `wp2-probes/wp2-settling-probe.json`, `.txt`, `settling-probe-run.log` | as above, `--tests "*ScratchWp2SettlingProbeTest"` | WP2 tree at about `e924eae` (15:31 to 15:33) |
| `run-scripts/campaign.sh` | The WP2 paced campaign: one run per spec line (`<runId> <profile> <restDetection> [tolerance|-] [memory]`) through `fluidServerBenchmark`. Before each run it waits for no `Endfield.exe` and 20 GB free; a crash keeps its `hs_err` file and the run is repeated once. | WP2 section 7 item 7 | `bash campaign.sh <spec file>` (paths are the batch worktree's) | `e924eae` |
| `run-scripts/gates.sh` | The WP2 gates, logged to `wp2-logs/final-gate-*.log`. | WP2 section 2 | `bash gates.sh` | `e924eae` |

The comparison scripts written in WP2 (`wp2cmp.js`, `wp2diverge.js`, `wp2evidence.js`, `wp2tables.js`, `winlen.js`) are in `../../fluid-paced-analysis/`.

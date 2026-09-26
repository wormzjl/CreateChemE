# WP3 scripts: presentation

Batch `2026-09-23-fluid-scheduling-rest`, package WP3 (engine-owned presentation, the `viewers` profile).

| file | what | cited in | against |
|---|---|---|---|
| `dev-edits/harness2.js`, `harness2-run.java.txt`, `harness2-presentation.java.txt` | A one-off source edit that inserted the `viewers` profile's run fields and presentation measurement into `FluidServerBenchmark`. It was applied once; the result is in commit `04632e9`, and the script no longer matches the file. Kept as the record of how that code was written. | WP3 section 3 | the WP3 working tree at 17:25 on 2026-09-23, before `04632e9` |
| `run-scripts/campaign.sh` | The WP3 paced campaign (`viewers` pair, `mixed100`), with the same machine check and crash handling as WP2's. | WP3 section 3 | `04632e9` (runs 17:35 to 17:45 on 2026-09-23, `wp3-logs/campaign.log`) |
| `run-scripts/gates.sh` | The WP3 gates, logged to `wp3-logs/final-gate-*.log`. | WP3 section 2 | `2f8d43a` |

The WP3 analysis scripts `wp3tables.js` and `wp3viewers.js` are in `../../fluid-paced-analysis/`. The dev-client helpers used for the WP3 screenshots (`Field`, `field.ps1`, `Flatten`, `Stack`) are in `../../mcp-gui-helpers/`.

# p6-pilot-acceptance (research artifacts)

P6 of batch `2026-09-24-coolprop-low-temperature` (integrate and qualify the few-compound pilot, gate G6), 2026-09-25 to
2026-09-26, worktree branch `claude/coolprop-multiphase-thermo-37f6b0` (`dd3da29` to `4d26a45`). Stage document:
`documentation/2026-09-24-coolprop-low-temperature/P6_PILOT_ACCEPTANCE.md`. Tools: `tools/p6-pilot-acceptance/`.

| Path | Content |
|---|---|
| `logs/` | Every Gradle log of the stage (`test-full-1.log`, `science-`, `runtime-`, `regression-`, `gametest-20260925a.log`, `gates-20260925a.txt`, the two runtime probe runs, the world-island probe), `gate-counts.txt`, the dev client's logs (`mcp-client-gradle.log` is the Gradle stdout of `runMcpClient`, complete across the midnight roll-over; `client-2026-09-25-1.log.gz` and `client-latest.log` are the game's own) |
| `cost-probe-before-warm-start.txt`, `cost-probe-after-warm-start.txt` | `P6CrystalUvCostProbe` before and after the crystal UV warm start |
| `runtime-budget-probe.txt` | `P6RuntimeBudgetProbe`: scheduler counters and checkpoints per slice, pilot against bundled islands of the same shape |
| `chain-probe-head.txt`, `chain-probe-4ff68da.txt`, `chain-probe-trace-head.txt` | `P6ChainProbe` at HEAD and on the P5 sources (identical), and the pass trace of its failing interval (the junction donors flip at zero flow) |
| `world-island-probe.txt` | `P6WorldIslandProbe` on the dev world's final checkpoint: each island's vessels and one timed interval |
| `world-final/` | The dev world's `level.dat` and `data/` (fluid checkpoint format 6) after the last save |
| `screenshots/` | The GUI check through the MCP bridge, numbered in order (flattened 854x480 PNG) |
| `test-output/` | System-out of the P6 network gate tests and `CrystalDepositionIslandTest` from `fluidScienceTest`; the P12 and P31 fingerprint files of the full suite |

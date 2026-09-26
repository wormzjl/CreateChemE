# Automatic pipe inspection review

In progress since 2026-09-24 on codex/column-gui at 413c13c; not merged.

Removed manual Forward/Reverse/run selection. Pipe overview uses the left operating rail for interval throughput, bulk velocity, mean absolute pressure gradient, phase volume shares and wrapped solver status/errors. A junction's read-only Connections table reports all routes, automatic dominant direction and flow/velocity/gradient. Molar/mass switching changes throughput, component rates and connection rates/labels. Shared controls/palette retained; authored labels/tooltips localized.

Server inspection metadata derives from accepted graph/history during engine presentation; cached opening snapshots include it. No thermodynamics or process advancement added to menu opens/ticks. Fluid protocol now fluid-6; fresh world required for live verification.

Physical junction verification covers four, five and six equipment faces including vertical faces: two nitrogen sources at 102325 Pa and 2-4 outlets at 101325 Pa, 350 K. Assertions cover retained topology, all streams, mass/component conservation and outgoing throughput counted once. Also covers straight reverse flow and interval reversals. Gradient is endpoint pressure difference divided by path length, including elevation, not friction-only loss. Speed uses interval bulk volume divided by this block's bore area, without multiphase slip.

Known limitation: short junctions with methane and nitrogen feeds failed Newton convergence at both 1 kPa and 48.675 kPa pressure difference. Topology support does not establish arbitrary mixed-feed robustness. Numerical solver unchanged. Reproducer/errors retained in canonical tools/pipe-junction-probe/ (investigative material, not a gate).

Validation: full Gradle test suite passed: 1,093 tests, zero failures/errors (3m 49s), client stopped for the run. Then a small Connections-tab availability refresh fix compiled and passed live checks. No solver/test logic changed after the full suite.

Fresh world Pipe inspection GUI (fluid-6), 2560 x 1440 at GUI scale 3, via langyo/minecraft-mod-mcp:
- Straight water circuit: 842.5 kmol/h -> 15177.1 kg/h on basis switch; component table and header change together. Velocity 2.2 m/s, mean pressure gradient 1000.0 Pa/m, water 100.0 volume %. No selector or full-width status; no vertical scrolling needed.
- Four-way junction: one inlet 1137.3 kmol/h, three outlets 379.1 each. Mass basis: 20489.0 kg/h inlet, 6829.7 each outlet (display rounding). Left throughput counts once.
- Read-only Connections table lists all four routes, velocities and pressure gradients. Molar/mass headings and values both switch.
- After removing the two branch outlets, Connections disappeared on the next snapshot without reopening; restoring the outlets restored the tab. This fixed a bug found during live verification where topology changed without inputRevision changing.
- Saved and reloaded only the fresh fluid-6 world, not an older world. Client left open on junction mass table; MCP control mode exited.

Evidence: screenshots/pipe-overview-molar.png, pipe-overview-mass.png, pipe-junction-connections.png, pipe-junction-mass-final.png. Intermediate screenshots are local ignored documentation only. No detached tracked code; one-off mixed-feed probe retained under tools/pipe-junction-probe/.

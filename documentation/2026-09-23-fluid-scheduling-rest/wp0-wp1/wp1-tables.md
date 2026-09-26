
### wp1: per-tick counters (all ticks / idle ticks)

| counter | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| islandVisits | 4.00 / 0.00 | 4.00 / 0.00 | 4.00 / 0.00 | 4.00 / 0.00 |
| moduleScans | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 |
| topologySnapshots | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 |
| islandSnapshots | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 |
| viewBuilds | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 |
| menuPackets | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 |
| readinessPumps | 0.92 / 0.00 | 0.96 / 0.00 | 0.88 / 0.00 | 0.96 / 0.00 |
| solvesDispatched | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 |
| completionsRouted | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 |
| islandsPublished | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 |
| deadlinesFired | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 |
| drainContinuations | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 |
| drainContinuationsDeferred | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 |
| ticks (idle) | 2400 (2280) | 2400 (2280) | 2399 (2279) | 2400 (2280) |
| idle ticks with any island visit | 0 | 0 | 0 | 0 |
| idle ticks with any topology snapshot | 0 | 0 | 0 | 0 |
| idle ticks with any readiness pump | 0 | 0 | 0 | 0 |

### wp1: per wall second

| counter | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| islandVisits | 80.0 | 80.0 | 80.0 | 80.0 |
| moduleScans | 0.0 | 0.0 | 0.0 | 0.0 |
| topologySnapshots | 0.0 | 0.0 | 0.0 | 0.0 |
| islandSnapshots | 20.0 | 20.0 | 20.0 | 20.0 |
| viewBuilds | 0.0 | 0.0 | 0.0 | 0.0 |
| menuPackets | 0.0 | 0.0 | 0.0 | 0.0 |
| readinessPumps | 18.5 | 19.2 | 17.7 | 19.1 |
| solvesDispatched | 20.0 | 20.0 | 20.0 | 20.0 |
| completionsRouted | 20.0 | 20.0 | 20.0 | 20.0 |
| islandsPublished | 20.0 | 20.0 | 20.0 | 20.0 |
| deadlinesFired | 20.0 | 20.0 | 20.0 | 20.0 |
| drainContinuations | 0.0 | 0.0 | 0.0 | 0.0 |
| drainContinuationsDeferred | 0.0 | 0.0 | 0.0 | 0.0 |

### wp1: throughput, latency, integrity

| metric | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| workers | 12 | 12 | 12 | 12 |
| measured s | 120.0 | 120.0 | 120.0 | 120.0 |
| accepted intervals / s | 19.992 | 19.992 | 20.000 | 19.992 |
| aggregate realtime ratio | 0.9996 | 0.9996 | 1.0000 | 0.9996 |
| held / approximate | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| ready-to-publication ms p50 / p95 / max | 12.57 / 58.47 / 66.89 | 20.16 / 65.02 / 72.56 | 7.86 / 56.55 / 68.62 | 15.31 / 60.22 / 68.38 |
| dispatch-to-publication ms p50 / p95 | 3.21 / 4.64 | 5.57 / 15.22 | 2.15 / 3.59 | 4.08 / 11.78 |
| worker ms p50 / p95 | 2.47 / 3.98 | 3.93 / 9.95 | 1.56 / 2.96 | 2.74 / 7.76 |
| engine server ms per tick p50 / p95 / max | 0.023 / 0.069 / 8.55 | 0.024 / 0.100 / 8.83 | 0.024 / 0.116 / 6.28 | 0.023 / 0.114 / 17.18 |
| whole tick ms p50 / p95 | 0.264 / 1.171 | 0.264 / 0.954 | 0.255 / 0.947 | 0.264 / 0.990 |
| mean worker CPU occupancy | 0.0026 | 0.0063 | 0.0002 | 0.0038 |
| component / energy balance units | 3.15e-7 / 3.91e-10 | 3.87e-7 / 9.13e-10 | 4.73e-7 / 0.00e+0 | 3.65e-7 / 1.21e-9 |
| fill time constant s | - | 3934 | - | 3882 |
| integrity passed | true | true | true | true |

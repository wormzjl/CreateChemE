
### baseline: per-tick counters (all ticks / idle ticks)

| counter | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| islandVisits | 797 / 700 | 808 / 700 | 774 / 700 | 807 / 700 |
| moduleScans | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 |
| topologySnapshots | 1.00 / 1.00 | 1.00 / 1.00 | 1.00 / 1.00 | 1.00 / 1.00 |
| islandSnapshots | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 |
| viewBuilds | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 |
| menuPackets | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 | 0.00 / 0.00 |
| readinessPumps | 3.81 / 3.00 | 3.95 / 3.00 | 3.64 / 3.00 | 3.91 / 3.00 |
| solvesDispatched | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 |
| completionsRouted | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 |
| islandsPublished | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 | 1.00 / 0.00 |
| ticks (idle) | 2400 (2280) | 2399 (2279) | 2400 (2280) | 2400 (2280) |
| idle ticks with any island visit | 2280 | 2279 | 2280 | 2280 |
| idle ticks with any topology snapshot | 2280 | 2279 | 2280 | 2280 |
| idle ticks with any readiness pump | 2280 | 2279 | 2280 | 2280 |

### baseline: per wall second

| counter | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| islandVisits | 15942 | 16156 | 15475 | 16130 |
| moduleScans | 0.0 | 0.0 | 0.0 | 0.0 |
| topologySnapshots | 20.0 | 20.0 | 20.0 | 20.0 |
| islandSnapshots | 20.0 | 20.0 | 20.0 | 20.0 |
| viewBuilds | 0.0 | 0.0 | 0.0 | 0.0 |
| menuPackets | 0.0 | 0.0 | 0.0 | 0.0 |
| readinessPumps | 76.1 | 78.9 | 72.7 | 78.2 |
| solvesDispatched | 20.0 | 20.0 | 20.0 | 20.0 |
| completionsRouted | 20.0 | 20.0 | 20.0 | 20.0 |
| islandsPublished | 20.0 | 20.0 | 20.0 | 20.0 |

### baseline: throughput, latency, integrity

| metric | stress100 | transient100 | rest100 | mixed100 |
|---|---|---|---|---|
| workers | 12 | 12 | 12 | 12 |
| measured s | 120.0 | 120.0 | 120.0 | 120.0 |
| accepted intervals / s | 19.992 | 20.000 | 19.992 | 19.992 |
| aggregate realtime ratio | 0.9996 | 1.0000 | 0.9996 | 0.9996 |
| held / approximate | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| ready-to-publication ms p50 / p95 / max | 9.82 / 57.19 / 64.25 | 21.26 / 65.59 / 79.38 | 9.41 / 57.88 / 65.53 | 16.21 / 59.57 / 70.22 |
| dispatch-to-publication ms p50 / p95 | 3.56 / 4.65 | 5.38 / 15.92 | 2.50 / 5.67 | 4.49 / 12.25 |
| worker ms p50 / p95 | 2.47 / 4.03 | 4.04 / 10.33 | 1.62 / 3.33 | 2.83 / 7.52 |
| engine server ms per tick p50 / p95 / max | 1.235 / 2.060 / 17.89 | 0.903 / 1.222 / 8.62 | 1.145 / 1.764 / 16.01 | 0.855 / 1.254 / 15.74 |
| whole tick ms p50 / p95 | 1.480 / 2.727 | 1.151 / 2.117 | 1.397 / 2.471 | 1.100 / 2.033 |
| mean worker CPU occupancy | 0.0035 | 0.0078 | 0.0023 | 0.0047 |
| component / energy balance units | 3.20e-7 / 1.83e-11 | 3.96e-7 / 8.98e-10 | 4.73e-7 / 2.43e-10 | 3.54e-7 / 1.34e-9 |
| fill time constant s | - | 3934 | - | 3881 |
| integrity passed | true | true | true | true |

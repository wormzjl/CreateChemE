### Benchmark: mixed100 without viewers (WP2 and WP3 code) and viewers

| metric | mixed100-wp2-off-r02 | mixed100-wp2-on-r02 | mixed100-wp3-on-r01 | viewers-wp3-off-r01 | viewers-wp3-on-r01 |
|---|---|---|---|---|---|
| warm-up / measurement window (configured; older reports: measured) | 60 s / 120 s | 60 s / 120 s | 60 s / 120 s | 60 s / 120 s | 60 s / 120 s |
| reference island tick (mid-window) | 2500 | 2500 | 2500 | 2500 | 2500 |
| restDetection | false | true | true | false | true |
| fixture chunks | all unloaded | all unloaded | all unloaded | all 365 loaded | all 365 loaded |
| full solves in window | 2400 | 1807 | 1807 | 2400 | 1818 |
| solves dispatched / s | 19.99 | 15.05 | 15.06 | 20.00 | 15.14 |
| replayed intervals / seconds | 0 / 0 s | 50 / 3765 s | 50 / 3736 s | 0 / 0 s | 481 / 3412 s |
| identity-advanced intervals / seconds | 0 / 0 s | 0 / 0 s | 0 / 0 s | 0 / 0 s | 22 / 55 s |
| final kinds | 100 AWAKE | 75 AWAKE, 25 STEADY | 75 AWAKE, 25 STEADY | 100 AWAKE | 75 AWAKE, 1 REST, 24 STEADY |
| mean eligible islands | 6.20 | 1.86 | 1.66 | 5.92 | 1.48 |
| ready-to-publication ms p50 / p95 | 14.87 / 63.61 | 14.87 / 54.68 | 15.48 / 54.98 | 16.75 / 125.14 | 16.00 / 53.05 |
| worker ms p50 / p95 | 2.67 / 6.94 | 3.07 / 7.36 | 3.16 / 8.15 | 2.77 / 7.32 | 3.31 / 9.00 |
| engine server ms per tick p50 / p95 / max | 0.020 / 0.103 / 7.2 | 0.021 / 0.116 / 9.3 | 0.011 / 0.069 / 14.5 | 0.166 / 0.515 / 211.4 | 0.150 / 0.455 / 205.9 |
| whole tick ms p50 / p95 | 0.229 / 0.440 | 0.218 / 0.418 | 0.233 / 0.443 | 0.727 / 1.310 | 0.638 / 1.246 |
| view builds per 100 ticks | 0 | 0 | 0 | 10794.977 | 8385.696 |
| device presentations per 100 ticks | n/a | n/a | 0 | 10794.977 | 8382.321 |
| menu packets per 100 ticks | 0 | 0 | 0 | 16.631 | 16.878 |
| bucket flushes per 100 ticks | n/a | n/a | 0 | 97.509 | 77.004 |
| island snapshots per 100 ticks | 100.000 | 75.292 | 75.323 | 329.126 | 322.278 |
| materialisations per 100 ticks | 0 | 0 | 0 | 0 | 19.114 |
| queued inputs in window | n/a | n/a | 0 | 80 | 80 |
| idle ticks | 2280/2400 | 2280/2400 | 2279/2399 | 59/2369 | 484/2370 |
| idle ticks with an island visit | 0 | 0 | 0 | 0 | 0 |
| held intervals window / warm-up | 0 / 5 | 0 / 4 | 0 / 5 | 0 / 6 | 0 / 4 |
| component / energy balance units | 3.60e-7 / 1.25e-9 | 4.92e-7 / 1.18e-9 | 4.22e-7 / 1.71e-9 | 3.27e-7 / 1.47e-9 | 3.76e-7 / 1.07e-9 |
| integrity passed | true | true | true | true | true |
| workers | 12 | 12 | 12 | 12 | 12 |
| artifact sha256 | 6c5f03aefcb7 | 6c5f03aefcb7 | 3ae597acd719 | 3ae597acd719 | 3ae597acd719 |

### Presentation, viewers-wp3-off-r01

Window ticks (1247, 3616] = 2369; 16 open menus; 10803 loaded devices; 80 scripted edits; passed true.

| measure | value |
|---|---|
| live payloads per 100 ticks per menu | 1.000 |
| static payloads per 100 ticks per menu | 0.040 |
| menu packets (all) per 100 ticks per menu | 1.039 |
| view builds per 100 ticks per consumer (menus + loaded devices) | 0.998 |
| device presentations per 100 ticks per loaded device | 0.999 |
| bucket flushes in window | 2310.000 |
| bucket flushes per 100 ticks | 97.509 |
| deliveries off their bucket | 0 |
| edits unanswered | 0 |
| replies not on the first bucket after the edit | 0 |
| replies at the input tick | 0 |
| wrong reply texts | 0 |
| accepted edits never reported Applied | 0 |
| latency minimum, ticks | 5.000 |
| latency ticks count / median / p95 / max | 80 / 42 / 96 / 96 |

Latency histogram (10-tick bins, lower edge: count): 0: 7, 10: 8, 20: 10, 30: 5, 40: 10, 50: 9, 60: 11, 70: 5, 80: 8, 90: 7

Reply texts: "Applied" x15; "Not applied: Controls are outside the supported range" x5; "Not applied: Reservoir initialization is fixed; existing fluid is conserved." x15; "Not applied: Stale fluid controls" x45

| network | role | device kind | deliveries in window | static in window | per 100 ticks | buckets | last status |
|---|---|---|---|---|---|---|---|
| 0 | ACCEPTED | GENERATOR | 24 | 5 | 1.013 | 4, 7, 10, 13, 16 | FULL |
| 1 | INVALID | GENERATOR | 24 | 0 | 1.013 | 5 | FULL |
| 2 | ACCEPTED | GENERATOR | 23 | 5 | 0.971 | 5, 8, 11, 14, 17 | FULL |
| 3 | ACCEPTED | PIPE | 23 | 5 | 0.971 | 7, 6, 9, 12, 15, 18 | FULL |
| 4 | STALE | GENERATOR | 24 | 0 | 1.013 | 8 | FULL |
| 5 | STALE | GENERATOR | 24 | 0 | 1.013 | 9 | FULL |
| 6 | STALE | GENERATOR | 24 | 0 | 1.013 | 10 | FULL |
| 7 | STALE | RESERVOIR | 24 | 0 | 1.013 | 11 | FULL |
| 8 | STALE | GENERATOR | 24 | 0 | 1.013 | 12 | FULL |
| 9 | STALE | GENERATOR | 24 | 0 | 1.013 | 13 | FULL |
| 10 | STALE | GENERATOR | 24 | 0 | 1.013 | 14 | FULL |
| 11 | STALE | RESERVOIR | 24 | 0 | 1.013 | 15 | FULL |
| 12 | STALE | GENERATOR | 24 | 0 | 1.013 | 16 | FULL |
| 13 | STALE | GENERATOR | 23 | 0 | 0.971 | 17 | FULL |
| 14 | STALE | GENERATOR | 23 | 0 | 0.971 | 18 | FULL |
| 15 | STALE | RESERVOIR | 23 | 0 | 0.971 | 19 | FULL |

Accepted edits (network, received, first reply tick and text, Applied tick):

| network | received | reply tick | latency | reply | Applied at |
|---|---|---|---|---|---|
| 0 | 1254 | 1304 | 50 | Applied | 1304 |
| 2 | 1300 | 1305 | 5 | Applied | 1305 |
| 3 | 1323 | 1406 | 83 | Applied | 1406 |
| 0 | 1654 | 1707 | 53 | Applied | 1707 |
| 2 | 1700 | 1708 | 8 | Applied | 1708 |
| 3 | 1723 | 1809 | 86 | Applied | 1809 |
| 0 | 2054 | 2110 | 56 | Applied | 2110 |
| 2 | 2100 | 2111 | 11 | Applied | 2111 |
| 3 | 2123 | 2212 | 89 | Applied | 2212 |
| 0 | 2454 | 2513 | 59 | Applied | 2513 |
| 2 | 2500 | 2514 | 14 | Applied | 2514 |
| 3 | 2523 | 2615 | 92 | Applied | 2615 |
| 0 | 2854 | 2916 | 62 | Applied | 2916 |
| 2 | 2900 | 2917 | 17 | Applied | 2917 |
| 3 | 2923 | 3018 | 95 | Applied | 3018 |

### Presentation, viewers-wp3-on-r01

Window ticks (1253, 3623] = 2370; 16 open menus; 10803 loaded devices; 80 scripted edits; passed true.

| measure | value |
|---|---|
| live payloads per 100 ticks per menu | 1.015 |
| static payloads per 100 ticks per menu | 0.040 |
| menu packets (all) per 100 ticks per menu | 1.055 |
| view builds per 100 ticks per consumer (menus + loaded devices) | 0.775 |
| device presentations per 100 ticks per loaded device | 0.776 |
| bucket flushes in window | 1825.000 |
| bucket flushes per 100 ticks | 77.004 |
| deliveries off their bucket | 0 |
| edits unanswered | 0 |
| replies not on the first bucket after the edit | 0 |
| replies at the input tick | 0 |
| wrong reply texts | 0 |
| accepted edits never reported Applied | 0 |
| latency minimum, ticks | 2.000 |
| latency ticks count / median / p95 / max | 80 / 44 / 90 / 99 |

Latency histogram (10-tick bins, lower edge: count): 0: 8, 10: 11, 20: 10, 30: 10, 40: 7, 50: 13, 60: 5, 70: 1, 80: 9, 90: 6

Reply texts: "Applied" x15; "Not applied: Controls are outside the supported range" x5; "Not applied: Reservoir initialization is fixed; existing fluid is conserved." x15; "Not applied: Stale fluid controls" x45

| network | role | device kind | deliveries in window | static in window | per 100 ticks | buckets | last status |
|---|---|---|---|---|---|---|---|
| 0 | ACCEPTED | GENERATOR | 24 | 5 | 1.013 | 4, 7, 10, 13, 16 | FULL |
| 1 | INVALID | GENERATOR | 24 | 0 | 1.013 | 5 | FULL |
| 2 | ACCEPTED | GENERATOR | 25 | 5 | 1.055 | 6, 5, 8, 11, 14, 17 | FULL |
| 3 | ACCEPTED | PIPE | 24 | 5 | 1.013 | 7, 6, 9, 12, 15, 18 | RESTING: no flow since 156.5 s |
| 4 | STALE | GENERATOR | 24 | 0 | 1.013 | 8 | FULL |
| 5 | STALE | GENERATOR | 24 | 0 | 1.013 | 9 | FULL |
| 6 | STALE | GENERATOR | 24 | 0 | 1.013 | 10 | FULL |
| 7 | STALE | RESERVOIR | 24 | 0 | 1.013 | 11 | STEADY: replaying 6.527e-09 kg/s since 30.0 s, next check at 15370.0 s |
| 8 | STALE | GENERATOR | 24 | 0 | 1.013 | 12 | FULL |
| 9 | STALE | GENERATOR | 24 | 0 | 1.013 | 13 | FULL |
| 10 | STALE | GENERATOR | 24 | 0 | 1.013 | 14 | FULL |
| 11 | STALE | RESERVOIR | 24 | 0 | 1.013 | 15 | STEADY: replaying 5.627e-09 kg/s since 52.5 s, next check at 19622.5 s |
| 12 | STALE | GENERATOR | 24 | 0 | 1.013 | 16 | FULL |
| 13 | STALE | GENERATOR | 24 | 0 | 1.013 | 17 | FULL |
| 14 | STALE | GENERATOR | 24 | 0 | 1.013 | 18 | FULL |
| 15 | STALE | RESERVOIR | 24 | 0 | 1.013 | 19 | STEADY: replaying 6.272e-09 kg/s since 15.0 s, next check at 15370.0 s |

Accepted edits (network, received, first reply tick and text, Applied tick):

| network | received | reply tick | latency | reply | Applied at |
|---|---|---|---|---|---|
| 0 | 1260 | 1304 | 44 | Applied | 1304 |
| 2 | 1306 | 1405 | 99 | Applied | 1405 |
| 3 | 1329 | 1406 | 77 | Applied | 1406 |
| 0 | 1660 | 1707 | 47 | Applied | 1707 |
| 2 | 1706 | 1708 | 2 | Applied | 1708 |
| 3 | 1729 | 1809 | 80 | Applied | 1809 |
| 0 | 2060 | 2110 | 50 | Applied | 2110 |
| 2 | 2106 | 2111 | 5 | Applied | 2111 |
| 3 | 2129 | 2212 | 83 | Applied | 2212 |
| 0 | 2460 | 2513 | 53 | Applied | 2513 |
| 2 | 2506 | 2514 | 8 | Applied | 2514 |
| 3 | 2529 | 2615 | 86 | Applied | 2615 |
| 0 | 2860 | 2916 | 56 | Applied | 2916 |
| 2 | 2906 | 2917 | 11 | Applied | 2917 |
| 3 | 2929 | 3018 | 89 | Applied | 3018 |

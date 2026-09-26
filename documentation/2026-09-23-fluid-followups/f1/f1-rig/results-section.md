### 3.1 Dedicated server, 60 s warm-up + 60 s window

Before = eb28fc5, after = branch. Full rows (thread families, JFR resident set, private bytes, threads, Minecraft's ServerTickTime, placement) are in `wp5-tables.md` section 1.

| scenario (server) | tick ms p50 / p95 (every tick): before -> after | engine ms per tick p50: before -> after | process CPU, cores: before -> after | GC count / pause ms: before -> after | live heap MiB: before -> after | working set MiB (mean): before -> after | allocation MiB/s: before -> after |
|---|---|---|---|---|---|---|---|
| empty | 0.206 / 0.409 -> 0.185 / 0.348 | 0.0217 -> 0.0108 | 0.10 -> 0.10 | 0 / 0 -> 0 / 0 | 304 -> 305 | 1629 -> 1603 | 0.3 -> 0.3 |
| rest100 | 0.339 / 0.714 -> 0.172 / 0.318 | 0.1652 -> 0.0110 | 0.28 -> 0.10 | 4 / 23 -> 0 / 0 | 311 -> 306 | 1327 -> 1389 | 20.4 -> 0.3 |
| through100 | 0.362 / 0.746 -> 0.233 / 0.545 | 0.1909 -> 0.0696 | 0.37 -> 0.35 | 4 / 18 -> 1 / 5 | 318 -> 317 | 1568 -> 1728 | 21.6 -> 12.6 |
| fill100 | 0.579 / 1.293 -> 0.377 / 1.411 | 0.3116 -> 0.1204 | 12.20 -> 12.24 | 679 / 653 -> 620 / 644 | 746 -> 897 | 1829 -> 1973 | 9180.9 -> 9106.6 |
| mixed100 | 0.576 / 1.677 -> 0.374 / 1.127 | 0.3824 -> 0.2106 | 7.81 -> 9.02 | 433 / 424 -> 409 / 410 | 641 -> 541 | 1908 -> 2213 | 5872.8 -> 6984.3 |
| rest1000 | 1.609 / 2.626 -> 0.170 / 0.341 | 1.5190 -> 0.0112 | 0.56 -> 0.12 | 37 / 132 -> 0 / 0 | 378 -> 321 | 1396 -> 1378 | 203.6 -> 0.3 |
| pure100 | 0.333 / 0.784 -> 0.147 / 0.309 | 0.1849 -> 0.0098 | 0.24 -> 0.10 | 1 / 4 -> 0 / 0 | 312 -> 306 | 1441 -> 1888 | 12.5 -> 0.3 |

### 3.2 Integrated client, 60 s warm-up + 60 s window

One JVM with rendering (120 fps cap), the integrated server and the engine; one player looking straight down at the networks from 86 blocks above. Full rows in `wp5-tables.md` section 2.

| scenario (client) | FPS mean / p5: before -> after | tick ms p50 / p95 (every tick): before -> after | engine ms per tick p50: before -> after | process CPU, cores: before -> after | GC count / pause ms: before -> after | live heap MiB: before -> after | working set MiB (mean): before -> after | allocation MiB/s: before -> after |
|---|---|---|---|---|---|---|---|---|
| empty | 115.4 / 111.0 -> 119.0 / 118.0 | 0.578 / 0.952 -> 0.595 / 0.894 | 0.0184 -> 0.0111 | 1.46 -> 1.53 | 20 / 56 -> 16 / 54 | 575 -> 573 | 1952 -> 2113 | 122.8 -> 124.0 |
| rest100 | 119.3 / 118.0 -> 119.1 / 119.0 | 0.731 / 1.222 -> 0.589 / 0.891 | 0.1544 -> 0.0121 | 1.71 -> 1.51 | 20 / 69 -> 16 / 62 | 583 -> 578 | 2112 -> 2103 | 144.0 -> 124.2 |
| through100 | 119.0 / 119.0 -> 119.0 / 118.0 | 0.808 / 1.473 -> 0.636 / 1.052 | 0.2128 -> 0.0696 | 1.72 -> 1.75 | 12 / 60 -> 17 / 62 | 589 -> 588 | 2657 -> 2221 | 145.5 -> 136.7 |
| fill100 | 115.1 / 71.0 -> 112.3 / 71.0 | 1.282 / 3.578 -> 1.054 / 3.606 | 0.3604 -> 0.1009 | 13.32 -> 13.27 | 410 / 507 -> 398 / 483 | 1779 -> 1670 | 3107 -> 3182 | 9190.2 -> 9155.3 |
| mixed100 | 116.0 / 87.0 -> 115.6 / 71.0 | 1.140 / 6.123 -> 0.842 / 4.029 | 0.3686 -> 0.1291 | 8.39 -> 7.17 | 260 / 320 -> 198 / 272 | 684 -> 588 | 3112 -> 3346 | 5701.3 -> 4761.0 |
| rest1000 | 119.1 / 118.0 -> 119.2 / 119.0 | 1.811 / 2.820 -> 0.530 / 0.873 | 1.3278 -> 0.0096 | 1.96 -> 1.45 | 35 / 136 -> 12 / 34 | 658 -> 596 | 2436 -> 2401 | 332.1 -> 124.3 |

### 3.3 After / before ratios

| scenario | server CPU after/before | server tick p50 after/before | server engine p50 after/before | server live heap after/before | client CPU after/before | client FPS mean after/before | client live heap after/before |
|---|---|---|---|---|---|---|---|
| empty | 1.00 | 0.90 | 0.50 | 1.00 | 1.04 | 1.03 | 1.00 |
| rest100 | 0.34 | 0.51 | 0.07 | 0.98 | 0.88 | 1.00 | 0.99 |
| through100 | 0.94 | 0.64 | 0.36 | 1.00 | 1.01 | 1.00 | 1.00 |
| fill100 | 1.00 | 0.65 | 0.39 | 1.20 | 1.00 | 0.98 | 0.94 |
| mixed100 | 1.15 | 0.65 | 0.55 | 0.84 | 0.85 | 1.00 | 0.86 |
| rest1000 | 0.21 | 0.11 | 0.01 | 0.85 | 0.74 | 1.00 | 0.91 |
| pure100 | 0.41 | 0.44 | 0.05 | 0.98 | - | - | - |

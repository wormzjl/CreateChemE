# Benchmark window length, measured 2026-09-23

Question from the owner: is a 60 s measurement window (after a 60 s warm-up) still too long? Answer from the per-publication samples of the 120 s runs: 60 s is the shortest window whose ready-to-publication p50 stays inside the run-to-run band of the 120 s numbers on all eight transient100 runs; 30 and 45 s do not. Script: `wp2-logs/winlen.js` (sub-window percentiles recomputed from `samples`, `rawEngineMillisecondsPerTick`, `stressSamples`, `warmupSamples`).

## transient100 ready-to-publication p50, ms, by window (eight quiet runs, 120 s each)

| window | baseline r01 | r02 | r03 | wp1 r01 | r02 | r03 | wp2 off-r02 | wp2 on-r03 | range | above the 120 s band (20.2 to 23.9)? |
|---|---|---|---|---|---|---|---|---|---|---|
| 15 s | 19.4 | 20.0 | 22.7 | 19.4 | 26.0 | 28.0 | 18.4 | 19.4 | 18.4 to 28.0 | 2 runs |
| 30 s | 22.8 | 21.6 | 25.1 | 20.6 | 24.9 | 26.8 | 23.6 | 23.0 | 20.6 to 26.8 | 3 runs |
| 45 s | 22.4 | 22.4 | 25.1 | 21.9 | 25.0 | 25.6 | 23.4 | 23.8 | 21.9 to 25.6 | 3 runs |
| 60 s | 22.3 | 22.1 | 24.2 | 22.0 | 24.0 | 24.3 | 22.1 | 22.2 | 22.0 to 24.3 | 0 (24.3 is 0.4 above) |
| 120 s | 21.3 | 21.1 | 23.9 | 20.2 | 21.9 | 23.9 | 20.9 | 20.6 | 20.2 to 23.9 | reference |

Latency keeps falling through the whole window (JIT warm-up of solver code continues past 120 s): the 60 s estimate is about 5 % above the 120 s one, and engine ms per tick about 8 % above (transient p50 0.0296 at 15 s, 0.0287 at 60 s, 0.0262 at 120 s). Both sides of a comparison carry the same bias when they use the same window, so 60 s numbers are comparable with each other but must not be quoted against 120 s numbers.

## Warm-up (from warm-up publications per 10 s)

- Start-up transient: ready-to-publication p50 in seconds for 0 to 20 s (wall-budget holds), 50 to 79 ms at 20 to 30 s, 34 to 39 ms at 30 to 40 s, settled at 22 to 30 ms from 40 s. A 60 s warm-up leaves 20 s of margin; 45 s would be the minimum.
- rest100 certification during warm-up: publications per 10 s fall 151, 90, 43, 20, 6 from 20 s on; 94 of 100 islands are certified at the first measured second, 98 at 15 s, 100 at 30 s. A 30 s warm-up would move most certification into the window.
- Allocator shrink after demand drops (rest100 on): worker limit 12 to 4 at 15 s, 2 at 30 s, 1 at 45 s of the window. Showing the pool down to one worker needs at least 45 s of window.

## Decision

Keep 60 s warm-up and 60 s measurement for every profile. Certification counts and replay are final within 30 s of the window for the closed profiles, but a shorter uniform window would bias the timing reference and hide the allocator shrink. Per run: about 140 s including some 20 s of server start-up; the campaign cost is in the number of runs, so repeats stay limited to transient100.

# Reproducible fluid-network benchmark protocol

Proposed protocol for NeoForge 1.21.1, 14 September 2026. **This is a specification, not an implemented benchmark mod or a set of measured results.** It can be executed manually for screening; precise automated results require the small instrumentation/fixture mod described below.

**Use a dedicated server and pin the environment.** Run Minecraft 1.21.1, an explicitly recorded compatible NeoForge build and Java 21. CreateChemE currently declares NeoForge 21.1.219; verify every selected jar's dependency range rather than assuming compatibility. Pin Java vendor/build, JVM flags, garbage collector, heap, OS, CPU, RAM, power mode, server distances, seed, mod/dependency jar hashes, configs and fixture revision. This host's default `java` points to Zulu 25, so select a Java 21 executable explicitly for the test.

Use one candidate transport mod plus its required dependencies and the same instrumentation mod per isolated run. Measure an empty fixture baseline with that same mod set. Later retest finalists together in the intended pack to expose interoperability costs. Do not run competing benchmark servers concurrently on the same machine.

Pregenerate the test region. Disable unrelated mob spawning, random ticks and weather/day changes. Keep a fixed set of chunks ticking, verified by fixture heartbeat counters. Do not rely on visual loading or proximity alone. Keep the same chunk footprint for paired controls; build long serpentine routes in a controlled footprint without accidental adjacency connections. Test chunk count separately. Force-loading commands have limits, so use tiled requests or a verified ticketing fixture where necessary.

**Define identical endpoints.** The preferred fixture supplies these capability-compatible blocks:

- A finite source tank with a large known inventory and a configurable set of 1, 16 or 64 tanks. It records simulated and executed drain calls separately.
- A finite sink tank with a controlled consumer. It records fill attempts, accepted amounts, consumed amounts and per-fluid/component balances.
- The same source in passive and pushing modes. Extraction pipes pull from passive tanks; systems requiring supplied fluid use a documented pusher/pump configuration. Include the necessary pump/pusher cost in the practical-system result.
- A controller that samples counters into memory and writes them after the timed interval. No per-transfer log lines, UI animation or per-tick disk writes.

Use common benchmark-fluid identities available in every test environment. Begin with water; add separate non-placeable test fluids for multi-fluid tests. For the first component case, use one identity carrying a small custom component payload. Keep the serialization schema identical across runs.

Do not use arbitrary creative tanks and trash cans as the sole measurement fixture: their special capacity or capability behavior can change the workload. A manual smoke test may use them, but document the limitation. In long runs, prefill enough fluid or replenish outside the measurement interval; otherwise meter replenishment as an external input. Sources must remain available and sinks must remain receptive except in intentionally blocked scenarios.

**Run functional qualification before timing.** Verify extraction/insertion sides, necessary power, fluids, filters, routes and all destinations. Check every source/sink heartbeat. Confirm that transfers are stable at the chosen offered load. Record initial and final pipe/network inventory when the implementation stores fluid.

For each fluid identity and component variant, the conservation check is:

`initial source + initial sink + initial network + external additions`

`= final source + final sink + final network + metered consumption + declared losses`

No ordinary-transfer run permits unexplained loss or duplication. Check simulated operations do not alter inventories. Account for starting pipe holdup so a network filling itself is not mistaken for poor steady throughput. For physical transport, fill long enough to reach steady state; a fixed short warmup alone is insufficient. For destruction tests, report lost pipe contents according to that mod's behavior rather than silently subtracting them.

For multi-fluid tests, explicitly distinguish multiple independently routed fluids from one chemical mixture. Where a system permits only one fluid per connected network, build parallel networks and report their total installed cost, or mark a single-network requirement unsupported. Do not force incompatible semantics into one score.

**Workload matrix.** N is pipe/cable nodes, A source connections, D destination connections, K tank slots per handler and F fluid/component variants. Count unique physical blocks, logical conduits, pumps, active cards, controllers and connections separately.

| Workload | Proposed sweep | What it resolves |
|---|---|---|
| Stable long route | N = 64, 256, 1,024, 4,096, 16,384; A=D=1 | Per-transit-block scaling, memory and reachability. Stop/escalate according to failures and resource limits. |
| Endpoint fan-out | Fixed scaffold near N=4,096; A=1; D=1,16,64,256 | Destination selection, fairness and failed insertion work. |
| Many-to-one | Same scaffold; A=1,16,64,256; D=1 | Repeated extraction, contention and source fairness. |
| Many-to-many | Same scaffold; A=D=16,64,256 | Extractor×destination amplification and filters. |
| Same total plant, partitioned | 256 sources and 256 sinks total; 1,4,16,64 networks | Benefits/costs of segmentation, extra controllers and duplicated caches. |
| Same-size topology | Match N/endpoints across line, branching tree, ring and grid as feasible | Loops, redundant routes, discovery complexity and path effects. |
| Idle and backpressure | Empty sources; full sinks; wrong-fluid sinks; 50%/90% blocked sinks; powered-off extraction | Whether the system avoids work when no useful transfer is possible. These are different idle states. |
| Handler and filter complexity | K=1,16,64; F=1,4,16; filters=0,8,32 where supported | Capability scans, matching, copies, failed probes and component overhead. |
| Component churn | Fixed F, then continuously changing component values of the same fluid | Cache behavior and interoperability with multicomponent fluids. |
| Split/rejoin | Break/restore one central bridge every 200 ticks, at least 50 events | Recovery time, peak tick work, stale routes and conservation. |
| Chunks and persistence | Unload a middle chunk, an endpoint chunk, a controller/pump chunk; reload; save/restart | Boundary behavior, inventory persistence and recovery. Test each event separately. |
| Watching clients | 0,1,4 clients; same camera positions and settings | Server synchronization, client frame time and rendering overhead. |

Use a fixed scaffold with disconnected endpoint branches where possible so changing D does not silently change N. Record actual N when geometry makes exact matching impossible. For independent pair scaling, many sources should not accidentally compete for one sink unless contention is the intended test.

Create requires valid powered pump stages along long routes. Include pump counts and rotation infrastructure in practical-system cost; a separate idle-pump control can help isolate fluid work. The default pump range must not be increased just to match another mod without labeling that an altered-config run.

IE's released discovery code stops at 1,024 visited pipes. Include boundary cases around 1,023/1,024/1,025 visited nodes and a sink beyond the traversal; record actual discovery rather than relying on a guessed off-by-one mapping from line length. A segmented working IE plant is a different configuration from a single failed giant route.

LaserIO is compared at the same endpoint locations and logical connection requirements, using its actual legal link distances. Its physical node count need not match the cable count of another mod. The same principle applies to required controllers and multi-conduit blocks.

**Compare two operating modes.**

1. **Equal demand:** start with an explicitly chosen common load, for example 50 mB/t per source. Supply enough sink buffering for the slowest allowed batching interval. Require at least 99% of intended delivery after settling, plus a chosen per-sink service deadline, before comparing cost. Run a low-latency requirement and a batch-tolerant requirement separately. Change the target if it is outside a system's capabilities and label that configuration.
2. **Capacity sweep:** increase offered load until delivery falls short, latency grows without bound, or the server loses its tick budget. Report the maximum load satisfying the declared service requirement, including any added pumps, parallel routes, upgrades or controllers. Use normal obtainable upgrades in the main comparison; creative/infinity options belong in a separate test.

Make two complementary graphs: milliseconds of server work against actual delivered mB/t, and milliseconds against installed size at fixed demand. Faster nominal throughput is not lower CPU cost, and a lower operation frequency can buy efficiency by increasing latency.

**Measurement.** Use a monotonic timer around the actual server tick-work interval, excluding deliberate sleep between ticks. For an automated fixture, a version-pinned hook around the server's tick method is preferable to timing loop start-to-start. Document whether queued tasks outside that method are included. NeoForge Pre/Post server tick events can provide a narrower event-span metric if that boundary is explicitly named; do not silently present it as full tick work.

Record these per run:

| Metric | Definition or interpretation |
|---|---|
| Mean, median, p95, p99 and maximum tick work (ms) | Full distribution; retain timestamped samples for burst analysis. |
| Deadline misses | Fraction of ticks over 50 ms and longest consecutive miss sequence at nominal 20 TPS. |
| Delivered throughput | Accepted sink mB / measured game ticks, and accepted sink mB / elapsed wall seconds. Report both when TPS falls. |
| Demand satisfaction | Delivered / requested, aggregate and for every sink; also source fairness. |
| Service latency | Time from a metered demand becoming available to its satisfaction; report ticks and wall time. Use a separate single-pulse test if individual fluid parcels cannot be tracked. |
| Longest starvation interval | Per-sink interval without service while demand and supply exist. |
| Handler work | Separate SIMULATE/EXECUTE drain/fill calls, failed calls, tank queries and invalidation callbacks. Optional sampled timing pass. |
| Memory pressure | Allocation estimate in MB/s, GC count/pause time, post-GC live heap in a separate diagnostic pass. RSS alone is not retained network memory. |
| Mutation recovery | Event tick cost and ticks/wall time until required throughput returns; combine with conservation checks. |
| Network/client | Bytes and packets per second, client p95/p99 frame time in the separate client pass. |

Measure a paired control with the same endpoints, power infrastructure, chunks and metered demand but transport disabled. Also retain an empty-structure control. Disabling a controller may leave ticking pipes and caches active, so explain which costs each baseline retains. A direct fixture-to-fixture transfer control helps expose endpoint overhead.

Calculate paired **mean** cost difference `mean(active) - mean(control)`. Do not interpret `p99(active) - p99(control)` as the p99 of incremental work. Keep raw values and uncertainty; do not clamp a noisy negative difference into evidence of an optimization. For passing active workloads, a useful normalization is `1000 × incremental_ms_per_tick / delivered_mB_per_tick`, giving estimated ms per bucket. If delivery is zero, mark it undefined. Near-noise differences should be reported as indistinguishable.

**Sampling procedure.** Start with a screening run of 60 seconds warmup plus 120 seconds recording after the flow has stabilized. For finalists use at least 5 independent JVM runs, 120 seconds warmup and 300 seconds recording, extending warmup for filling/JIT stabilization. Randomize candidate order and interleave controls. Save raw per-tick samples and run-level summaries; compute uncertainty across independent runs, not by treating successive ticks as independent replicates. Scheduled every-10/20-tick behavior is correlated. Retain slow ticks and GC events rather than discarding them as outliers.

Do a practical staged matrix instead of the full Cartesian product: screen long routes and one 64×64 endpoint case first, then run the complete topology/blocked/churn/latency suite on finalists. A five-minute sample at 20 TPS supplies roughly 6,000 ticks, but rare mutation events still need their own repeated-event experiment. Baseline measurements without the heavyweight profiler are the primary timings; profiling runs explain them.

**Available tools.** Install the exact 1.21.1 NeoForge spark artifact and record its version. Use spark to locate server-thread hotspots and identify spikes. Commands to run on the dedicated-server command interface include:

```text
/spark tps
/spark profiler start
/spark profiler stop --save-to-file
/spark tickmonitor --threshold-tick 50
```

For a separate spike capture, use `/spark profiler start --only-ticks-over 50`, then stop/save it after the scripted events. A duration can be specified with `--timeout`; a controlled manual stop makes local-file collection explicit. These are diagnostic runs, not the primary raw per-tick recorder. [spark command documentation](https://spark.lucko.me/docs/Command-Usage), [spike diagnosis](https://spark.lucko.me/docs/guides/Finding-lag-spikes)

On this Windows host, do not assume spark's async-profiler allocation mode works. Its documented async engine supports Linux/macOS; the Java sampler has different sampling bias. Record the actual engine and use Java Flight Recorder for a separate JVM diagnostic pass, or rerun the same benchmark on a Linux dedicated server with async-profiler. Cross-OS timings form separate series. [spark engine support](https://spark.lucko.me/docs/misc/Using-async-profiler)

With the selected JDK 21 `jcmd` executable, substitute the actual benchmark server PID:

```text
jcmd <server-pid> JFR.start name=fluid-study settings=profile duration=120s filename=fluid-study.jfr
```

JFR helps inspect sampled allocations, GC pauses, execution and thread activity; do not treat allocation samples as an exact byte counter. Use the same settings for all diagnostic runs. Heap dumps/histograms can be expensive and belong outside scored timing windows. [JDK 21 jcmd documentation](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)

Do not compare percentages of unrelated flamegraphs as if they were absolute costs. Identify time in the pipe code, tank capability implementation, shared transfer helpers, allocation/GC and synchronization. A pipe can spend most of its inclusive time inside another mod's expensive tank handler. For suspected causes, count actual handler invocations, then run a separate narrowly instrumented experiment. Microbenchmarks of isolated graph/filter operations can explain a result but cannot replace the server experiment.

**Minimum artifacts to publish with measurements.** Keep the exact mod/config/hash manifest; world or deterministic fixture builder; source/sink and network configuration; all raw tick/transfer samples; run order and seeds; independent-run summaries; local spark/JFR profiles; correctness failures; and recovery logs. State support limits explicitly and retain failed cells as failures rather than omitting them from the comparison.

The final comparison should give the cheapest configuration satisfying each declared service level, its size-scaling curve, and its failure/recovery envelope. The study is successful even if different configurations win different workloads.

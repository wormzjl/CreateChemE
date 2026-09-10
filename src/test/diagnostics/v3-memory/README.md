# Solver RAM evaluation — 2026-09-09

Measured current `main`, commit `8547fea`, using Oracle Java `21.0.11+9-LTS-211`
on Windows, G1 GC, a 64 MiB initial heap, and the heap limits below. No production
solver code or settings were changed. All numbers use binary MiB/GiB.

**The solver has substantial temporary allocation traffic and a much smaller
working heap.** For the existing 30-tray fixtures, three measured solves in a
512 MiB JVM reach 122–167 MiB of sampled heap use. The 64-tray wet fixture reaches
158 MiB in that run, while its JVM's lifetime resident-memory peak reaches
445 MiB during warmup. A 20-solve run reaches 250 MiB of heap use before collection,
then returns to 15.6 MiB after a full collection. This finite workload shows no
retained-heap growth; it is not an exhaustive leak proof.

For these registered feeds, **allow roughly 256–512 MiB of extra heap headroom
per active solver worker**, with 512 MiB the more conservative planning allowance.
That allowance is additional to Minecraft/modpack memory. The tested 64-tray
case runs with 128 MiB but nearly fills it, and fails with 64 MiB. These are
empirical results for these inputs, not a guarantee for every supported input.

## What each number means

* **Allocated per solve:** the solve thread's cumulative allocation delta; objects
  can be created, collected, and replaced many times within one solve. It is an
  approximation from the JVM's [thread allocation counter](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.management/com/sun/management/ThreadMXBean.html).
* **Heap used/committed/maximum:** occupied heap, heap made available to the JVM,
  and its configured ceiling are separate quantities. See the
  [Java memory-usage definitions](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/MemoryUsage.html).
* **Resident RAM:** Windows working-set measurements for the standalone Java
  process, including its heap, runtime, native libraries, JIT and GC overhead.
  Private commit is recorded separately and is not resident RAM. See
  [Windows process-memory counters](https://learn.microsoft.com/en-us/windows/win32/api/psapi/ns-psapi-process_memory_counters_ex).

The Gradle daemon is a separate process and is not included in these process
measurements. This benchmark does not launch Minecraft, load a world, render
textures, or measure server-side controller/result caches.

## Representative results, 512 MiB heap ceiling

Three warmups and three measured calls per case, each case in its own fresh JVM.
Heap and process working set are sampled at solver checkpoints, at most once
every 2 ms. Sampled maxima can miss brief peaks. The Windows lifetime peak is
maintained by the OS and includes initialization and warmup.

| Case | Sampled heap peak MiB | Solve-phase sampled resident peak MiB | Lifetime resident peak MiB | Mean allocation per solve MiB | Mean solve ms | Mean GC ms per solve |
|---|---:|---:|---:|---:|---:|---:|
| A: dry CDU17, base | 121.8 | 245.2 | 245.2 | 362.8 | 264.9 | 7.3 |
| B: dry CDU17, draws/cooler | 131.5 | 260.7 | 260.7 | 1310.9 | 994.9 | 19.7 |
| C: wet TJL19, draws/three pumparounds | 166.9 | 261.0 | 261.0 | 1193.7 | 994.2 | 21.3 |
| D: dry CDU17, 40 MW return duty | 160.2 | 266.4 | 266.4 | 1359.9 | 990.7 | 19.0 |
| E: dry CDU17, draws | 165.4 | 289.3 | 289.4 | 792.5 | 585.3 | 10.3 |
| Holland literature benchmark | 50.5 | 148.9 | 149.2 | 47.2 | 46.2 | 0.7 |
| C64: wet TJL19, 64 trays | 157.9 | 250.7 | 444.7 | 3501.5 | 2994.9 | 137.7 |

A–E are the current closure-test inputs. C64 scales the C fixture from 30 to 64
trays: the feed moves to tray 51, draws to 28/36/47, pumparound zones to
13–19/28–34/43–49, and sump steam to node 65. Rates, temperatures, pressure drop,
and duties are retained. This is a successfully audited stress fixture, not the
same physical column as C. Sixty-four is the supported tray maximum; its feed has
19 public components. The generic component-basis cap is 64, so this is not a
qualification of a hypothetical 64-component package.

The ordering of heap peaks is not monotonic with problem size: G1 changes its
collection frequency and heap size. C64 does more work and collects much more
often. Its higher warmup resident peak is also distinct from its steady solve
heap. These short runs should not be used to infer a precise heap-size speedup.

## Heap-limit sweep

Every successful run below has three measured outcomes matching its 512 MiB
reference exactly, including published streams, diagnostics and step evidence.

| Heap ceiling MiB | C result / sampled peak MiB | C64 result / sampled peak MiB |
|---|---|---|
| 64 | Success / 62.6 | **Out of memory during warmup** |
| 128 | Success / 99.4 | Success / 126.2 |
| 256 | Success / 156.7 | Success / 161.9 |
| 512 | Success / 166.9 | Success / 157.9 |
| 1024 | Success / 152.0 | Success / 293.5 |

C's mean GC time per solve is approximately 90 ms at 64 MiB, 57 ms at 128 MiB,
and 18–24 ms at 256–1024 MiB. C64 uses roughly 115–161 ms across its successful
heap limits. A larger configured maximum does not mean that much memory is
continuously occupied.

The C64/64 MiB failure is `OutOfMemoryError: Java heap space`, with the top stack
at `V3FiniteDifferenceJacobian.Jacobian.copy:276`, called by the Jacobian
constructor during `verifyFinalNewtonCorrection`. It demonstrates a real peak
working-storage constraint, not merely high cumulative allocation.

## Retention and garbage collection

Twenty consecutive C solves in a 256 MiB JVM allocate about 22.4 GiB cumulatively.
The sampled used-heap maximum reaches 250.5 MiB, and process resident memory
peaks at 352.6 MiB. After a full collection, used heap is 15.6 MiB, compared with
17.6 MiB before the measured loop. The benchmark retains its small output records;
even with those retained, the post-collection heap does not grow in this run.

Separate JFR runs show maximum observed GC pauses of 2.34 ms for C and 4.42 ms
for C64, over three measured solves each. The largest after-GC occupied-heap
snapshots are about 42 MiB and 95 MiB respectively. Those young/partial-collection
snapshots are not exact live-set measurements. GC still pauses the JVM, so high
allocation traffic can matter to server responsiveness even with adequate RAM.

Native-memory tracking of the profiled C64 process after the final collection
reports about 205 MiB committed across tracked JVM categories, including a
70 MiB Java heap and roughly 61 MiB of GC infrastructure. Its roughly 2 GiB
reserved virtual address space is not 2 GiB of resident RAM. JFR and NMT also
add profiling overhead; ordinary runs have NMT enabled but no JFR recording.

## Main opportunities

These are measured candidates for future work; this evaluation does not implement
them or promise their combined savings.

1. **Reduce dense verification-Jacobian storage and copies to lower the peak.**
   `V3FiniteDifferenceJacobian` builds an `N × N` array even on the stage-colored
   path (`:97`) and defensively copies it into `Jacobian` (`:258`, `:276`). The
   builder and owned copy each require about `8 × N²` bytes of numeric storage,
   plus row/object overhead. The 64 MiB failure occurs in this copy. An internal
   ownership-transfer factory, or direct band/block storage for this path, could
   reduce the peak while keeping external defensive-copy contracts. Arithmetic,
   support and coupling checks must remain qualified.
2. **Reuse solve-local band and factorization buffers to reduce churn.**
   `V3BandedPivotedSolver.BandRows:265` and `V3BandedMatrix:19` account for about
   22.5% of C's sampled allocation weight and 19.7% of C64's. Reusing buffers
   within an owned solve could avoid repeated large allocations. Matrix shape
   changes and separate concurrent solves need separate ownership.
3. **Reduce structural-matching and validation object creation.**
   `V3DegreeOfFreedomLedger.augment:372` accounts for about 11.9% of C and 10.5%
   of C64, predominantly list iterators in recursive matching. Indexed traversal,
   integer adjacency and reusable visitation marks are candidates; topology and
   structural-rank validation must retain their current behavior.
4. **Review thermodynamic and block-matrix copies.** Public phase-composition
   arrays, fugacity arrays/results and `V3BlockJacobian.copy` also contribute.
   Internal workspace APIs may avoid copies while public result objects remain
   immutable.

These percentages are allocation-sample estimates, not resident-memory shares.
For C64, dense finite-difference assembly and copying together account for about
14.6% of sampled allocation; they matter disproportionately to the minimum heap.
None of these directions inherently requires a looser convergence tolerance.

The server defaults to one active solver worker and permits two. Extra workers
can overlap their matrix workspaces and allocation bursts. Size the overall JVM
for Minecraft/modpack memory plus concurrent solver headroom; the standalone
process footprint is not the total RAM requirement of a running game/server.

## Reproduction and qualification

From the repository root in PowerShell:

```powershell
.\gradlew.bat -I src/test/diagnostics/v3-memory/memory.init.gradle v3MemoryProbe '-PmemoryCase=C64' '-PmemoryHeap=512m' '-PmemoryReport=build/reports/v3-memory/C64-512m.json' --no-configuration-cache
.\gradlew.bat -I src/test/diagnostics/v3-memory/memory.init.gradle v3MemoryProbe '-PmemoryCase=C' '-PmemoryProfile=true' '-PmemoryReport=build/reports/v3-memory/C-profile-qualified.json' --no-configuration-cache
.\gradlew.bat -I src/test/diagnostics/v3-memory/memory.init.gradle v3MemoryProfileSummary '-PmemoryReport=build/reports/v3-memory/C-profile-qualified.json' --no-configuration-cache
node src/test/diagnostics/v3-memory/summarize.cjs
```

`memorySamples` defaults to three; use 20 for the retention run. Raw JSON, JFR and
native-memory text are in `build/reports/v3-memory/`. The summary script expects
the named sweep and repeat files listed in its source. These Windows-specific
probes use the existing JNA dependency to read the current process's counters.

The qualified JFR recordings start before warmup and are filtered to the measured
solve interval. Their allocation-weight sums agree with the solve-thread counters
within 0.2%. An earlier C recording begun after warmup inherited allocation credit
from outside the measured interval and is excluded; only `*-profile-qualified`
recordings support the percentages above. Profiling and checkpoint sampling add
overhead, so timing values here are diagnostic, not replacement performance claims.

Validation: **68 successful measured outcomes**, with exact per-case agreement
across heap sizes, repetition and qualified profiling, plus the explicitly
identified C64/64 MiB out-of-memory result. Every successful outcome carries a
passing independent audit and fresh convergence evidence. Production Java and
Gradle files remain unchanged; only diagnostic sources and this report are added.

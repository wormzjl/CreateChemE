# RAM reduction without numerical concessions

See [the continuation and seed investigation](CONTINUATION_INVESTIGATION.md) for
why allocation spikes and controlled experiments with nearby converged states.

See [compact Jacobian storage and reusable LU workspaces](ROUND3.md) for the latest
pass, benchmarked with the default plus **±5% and ±10% perturbations**.

See [the further reduction pass](ROUND2.md) for the subsequent implementation and
benchmarks using the shipped default plus twelve parameter perturbations.

See [the remaining-allocation breakdown](ALLOCATION_BREAKDOWN.md) for a subsequent
profile of the shipped default and the next reduction candidates.

Branch: `codex/solver-ram-reduction`, based on `8547fea`.
Implemented and measured on 2026-09-09.

The implemented changes reduce temporary allocation and the minimum heap needed
by the tested large column. Numerical outputs remain exact, and representative
runtime measurements improve. **They do not establish a uniform reduction in the
Java process's Windows resident-memory peak.** See the memory qualification below.

## Plan and implementation

1. **Remove duplicate construction storage for freshly built Jacobians.** The
   finite-difference builders now transfer their private, newly allocated matrix
   into the immutable Jacobian wrapper. A private constructor controls that path;
   the normal constructor still copies caller-owned input, and `values()` still
   returns a deep snapshot. The internal wrapper becomes a final class to support
   the two construction paths. Every shape and finite-value check remains.
2. **Apply the same ownership rule to freshly assembled stage blocks.** The two
   assembler return sites transfer their fresh lower/diagonal/upper arrays through
   `V3BlockJacobian.fromOwnedBlocks`. The assembler relinquishes all references.
   The copying constructor and snapshot accessors preserve caller isolation. No
   floating-point operation or matrix entry changes.
3. **Reduce allocation inside structural matching.** Equation references are
   resolved to integer adjacency once, preserving their order. Recursive matching
   traverses primitive arrays, eliminating iterator creation and repeated hash
   lookups. One integer visitation array, stamped per search, replaces a new
   boolean array per equation. Search order, matching assignments and rank checks
   retain their algorithmic behavior.
4. **Qualify against an actual frozen baseline.** Four original production classes
   are compiled separately and prepended to the test runtime for baseline runs.
   Their sources were checked against `8547fea`. Baseline/candidate JVMs alternate;
   full outputs are compared exactly. Dedicated ownership and structural-rank
   oracle tests supplement the existing solver and thermodynamic tests.

All storage remains owned by a builder, immutable result or individual matching
call. There are no new global pools/caches. LU buffer pooling is outside this
patch; retaining large work buffers requires a separate lifetime/memory decision.

**Precision policy is unchanged:** finite-difference steps, arithmetic order,
matrix coefficients, pivoting, residual scaling, continuation, closure tolerances,
final correction evidence and independent physical audits are untouched. This
patch does not add a precision concession or change the existing merit policy.

## Runtime and allocation

Java 21, Windows, `-Xms512m -Xmx2g`, no JFR or memory polling in timing runs.
Each of four serial JVMs (baseline/candidate/baseline/candidate) performs five
warmups then seven measured calls for A–E, Holland and C64. Fixture construction
and report serialization are outside the timer; the public calculator and its
audit are inside it.

The second pair's medians:

| Case | Baseline allocation MiB | Candidate allocation MiB | Reduction | Baseline ms | Candidate ms |
|---|---:|---:|---:|---:|---:|
| A | 365.4 | 307.6 | 15.8% | 217.2 | 213.6 |
| B | 1297.1 | 1004.0 | 22.6% | 756.3 | 585.3 |
| C, wet 30 trays | 1163.5 | 931.9 | 19.9% | 791.5 | 653.1 |
| D | 1378.8 | 1143.1 | 17.1% | 770.3 | 675.5 |
| E | 804.0 | 640.1 | 20.4% | 470.2 | 404.1 |
| Holland | 48.2 | 45.1 | 6.5% | 18.6 | 17.7 |
| C64, wet 64 trays | 3369.4 | 2546.1 | 24.4% | 2280.4 | 1734.9 |

Both pairs improve A–E/C64 runtimes: approximately 2–24%, depending on case and
fork. The first broad Holland run was slower (18.46 to 21.14 ms), so its result
was not accepted without follow-up. Four dedicated Holland JVMs, each with 50
warmups and 40 measurements, give 18.46 to 17.61 ms and 18.33 to 17.33 ms. Both
confirmation pairs improve by approximately 5%; allocation is also lower.

This is evidence of no measured performance sacrifice in the qualified workloads,
not a universal performance guarantee for every input or machine. Counts, sample
distributions and CPU/allocation measurements remain in the raw JSON.

## Heap capacity and resident RAM

The same C64 fixture is measured in isolated G1 JVMs with `-Xms64m`, NMT enabled,
three warmups and three measured calls. The full outputs match the timing baseline.

| Heap ceiling | Baseline | Candidate | Baseline median ms | Candidate median ms |
|---|---|---|---:|---:|
| 64 MiB | Out of memory in Jacobian copy | Success, sampled heap peak 63 MiB | — | 2135.4 |
| 128 MiB | Success | Success | 2437.3 | 1886.0 |
| 512 MiB | Success | Success | 2456.3 | 1897.8 |

The 64 MiB test demonstrates a smaller required working heap: the original
Jacobian constructor's dense copy no longer has to coexist with its builder
matrix. Each avoided dense copy saves roughly `8 × N²` numeric bytes plus array
overhead during construction. It is a capacity test, **not a recommendation to
configure a production server with a 64 MiB heap**. Collection is frequent at that
limit; other workloads and Minecraft need additional headroom.

Resident-memory qualification is more limited. At 128 MiB, both versions fill
roughly the same sampled heap ceiling; lifetime process working-set peaks are
239 and 243 MiB. In the first 512 MiB pair, sampled used-heap peaks are 153 and
159 MiB, and process peaks are 289 and 491 MiB. A repeated 512 MiB pair gives
process peaks of 284 and 317 MiB, with solve-phase sampled resident peaks of 284
and 273 MiB. The larger candidate peak must not be omitted from the result.

In that confirmation pair, post-collection used heap is approximately 15.9 MiB
for both versions. NMT reports nearly equal tracked committed memory after
collection (about 169.5 versus 169.9 MiB), while the recorded compiler-arena
allocation peaks differ (about 42.7 versus 66.2 MiB). These snapshots demonstrate
native/JIT variation but do not fully attribute the earlier 491 MiB peak.

The supported claims are therefore **lower allocation traffic, lower minimum heap
for C64, and faster measured solves**. A consistent reduction in OS resident RAM
at an unchanged generous heap ceiling is not established. No JVM heap, GC or
worker settings are changed by this branch.

## Validation

* Full current-main regression suite: **497 tests passed**, no failures/errors/skips.
  This includes independent Holland checks and the existing phase, closure,
  Jacobian and solver contracts.
* New matching oracle: 250 small sparse/deficient/rectangular graphs compared with
  independent exhaustive subset search.
* Copying and ownership paths: returned block snapshots remain independent;
  copying constructors protect caller inputs; signed zero, shape and finite-value
  contracts are checked. Existing finite-difference snapshot tests remain passing.
* **377 successful measured outcomes** match their baseline exactly: 196 broad
  comparisons, 160 dedicated Holland measurements and 21 successful memory runs.
  Public streams, duties where recorded, solve paths, audit values and correction
  certificates agree. The baseline C64/64 MiB out-of-memory result is recorded
  separately and is not counted as a success.

The prior RAM-evaluation diagnostics are carried onto this branch. Its historical
report remains under `src/test/diagnostics/v3-memory/`; this document describes
the subsequent implementation and its qualifications.

## Follow-up: shipped default parameters

The earlier A–E fixtures are not the fresh block's default input. The default is
read directly from `ColumnCalculatorV3BlockEntity.literatureCduInput()`, which
`freshInput()` selects: 40 trays, feed at tray 37 and 365 C, 250 kPa with zero
tray pressure drop, condenser at 59 C, reflux ratio 4.17, no external reboiler,
three side draws and the published 12.84/17.89/11.20 MW pumparound coolers, with
1,200 kmol/h sump steam. The runs retain trace truncation off, the `1e-8` closure,
one active solve, and the default 45-second deadline.

Four additional timing JVMs, in baseline/candidate/baseline/candidate order,
each use five warmups and seven measurements. These are standalone calls to the
production calculator with the production preset; they do not launch a game world.

| Quantity | Baseline | RAM-reduction branch |
|---|---:|---:|
| Median runtime, pair 1 | 1651.9 ms | 1404.9 ms |
| Median runtime, pair 2 | 1629.7 ms | 1415.2 ms |
| Median allocated per solve, pair 1 | 2435.1 MiB | 1999.0 MiB |
| Median allocated per solve, pair 2 | 2411.5 MiB | 1974.3 MiB |
| Sampled heap peak, separate 512 MiB JVMs | 168.3 MiB | 145.6 MiB |
| Lifetime resident-process peak, same memory runs | 313.6 MiB | 326.9 MiB |
| Used heap after full collection | 18.6 MiB | 18.6 MiB |

The default is approximately **13–15% faster with 18% less allocation** in these
comparisons. Sampled heap use is lower, while resident process RAM again does not
show a reduction. Some warmup drift remains in individual timing sequences;
the two JVM-pair medians are reported rather than a universal speed guarantee.

All **34 additional outcomes** (28 timing and six memory measurements) succeed
and match exactly in outputs, solve path, audit values and correction evidence.
Maximum scaled residual is `3.952393967665557e-14` on both versions. The existing
tray-1 dew-point warning remains identical: tray 1 is 83.1 C against an 86.9 C
water dew point. This is the preset's established advisory behavior, not a new
failure or change in the RAM patch. All three `V3LiteraturePresetTest` tests were
also rerun successfully.

Use `ramCases=Default` for timing or `ramCase=Default` for memory, together with
`ramDeadlineMillis=45000`. Raw files use `default-*` under the same report folder;
`compare-default.cjs` validates them and writes `default-comparison.json`.

## Reproduce

From the repository root in PowerShell:

```powershell
.\src\test\diagnostics\v3-ram-reduction\prepare-baseline.ps1
.\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/comparison.init.gradle v3RamComparison '-PramVariant=baseline' '-PramReport=build/reports/v3-ram-reduction/baseline-1.json' --no-configuration-cache
.\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/comparison.init.gradle v3RamComparison '-PramVariant=candidate' '-PramReport=build/reports/v3-ram-reduction/candidate-1.json' --no-configuration-cache
.\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/comparison.init.gradle v3RamMemory '-PramVariant=candidate' '-PramHeap=64m' '-PramReport=build/reports/v3-ram-reduction/memory-C64-64m-candidate.json' --no-configuration-cache
.\gradlew.bat test
node src/test/diagnostics/v3-ram-reduction/compare.cjs
```

The comparison script expects the complete named run set listed in its source.
Use `ramWarmup=50`, `ramSamples=40`, `ramCases=Holland` for the dedicated timing
confirmation. Raw outputs and the aggregate `comparison.json` are under
`build/reports/v3-ram-reduction/`; native summaries accompany the confirmation
memory runs. Generated baseline classes remain outside production artifacts.

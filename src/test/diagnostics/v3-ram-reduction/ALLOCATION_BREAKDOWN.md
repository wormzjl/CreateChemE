# Remaining allocation on the shipped default

Profiled `codex/solver-ram-reduction` on 2026-09-09, after the existing Jacobian
ownership and structural-matching changes. The input comes directly from the
production fresh-calculator preset: the 40-tray wet literature CDU, with the
default numerical tolerances and 45-second deadline.

Two separate Java 21 JVMs each perform three warmups and five measured solves.
JFR starts before warmup; analysis includes only the measured interval. Across
14,509 allocation samples, sampled weights agree with the solve-thread allocation
counter to within 0.01% in aggregate. All ten outputs match the prior default
result exactly, including audit values, warning and convergence evidence.

The profiled total is about **1,955 MiB (1.91 GiB) allocated per solve**. Profiling
and JVM optimization affect exact allocation counts; the unprofiled comparison
previously recorded roughly 1.93–1.95 GiB. These are cumulative allocations, not
simultaneously resident data. Approximately 62% of sampled bytes are `double[]`.

## Breakdown

Groups are mutually exclusive and use the nearest project allocation site in
each stack. For example, equation-ID objects allocated inside Jacobian assembly
belong to that assembly group. Values below pool both recordings; individual-run
shares vary, so these are estimates rather than exact allocation-site counters.

| Purpose | Estimated share | Estimated MiB per solve |
|---|---:|---:|
| Jacobian assembly and linear-system work | 45.5% | 889 |
| Thermodynamic evaluation and result copies | 23.2% | 454 |
| Residual rows and containers | 13.8% | 269 |
| Topology and validation metadata | 8.5% | 167 |
| Trial states and solver coordinate arrays | 8.0% | 156 |
| Other and diagnostic overhead | 1.0% | 19 |

One public calculation passes through 4/8/15/30/40-tray continuation stages and
introduces the requested draws, steam and heat duties through intermediate solves.
Within those solves, Jacobian construction, linear solves, residual evaluations
and trial-state decoding allocate repeatedly. The final displayed Newton count
does not describe all of this work. Reusing a numerical answer is not the same
as reusing its temporary storage; the latter offers precision-preserving options.

## Concrete remaining sources

* `V3BandedMatrix:19` allocates a new coefficient buffer, and
  `V3BandedPivotedSolver.BandRows:265` allocates another factorization buffer.
  Together these sites account for roughly **23–28%** of sampled allocation in
  the two runs: approximately **450–550 MiB per solve**. They are distinct from
  the Jacobian ownership copies already removed by this branch.
* `V3BlockJacobianAssembler.emptyBlocks:171` still creates the fresh working
  blocks, and `V3FiniteDifferenceJacobian.stageColoredJacobian:97` still creates
  the dense verification matrix. Eliminating the final defensive copy did not
  eliminate these original allocations.
* `V3FugacityResult:24` validates an array through `Arrays.stream(...).anyMatch(...)`.
  The pipeline/spliterator/sink objects at this site account for about **4.5–7.1%**
  of sampled allocation. `V3PengRobinsonThermo.fugacity` also obtains a copied
  coefficient array, constructs a result that copies it again, and returns an
  immutable result object. Related composition/derivative arrays add more traffic.
* `V3MeshResidualEvaluator.evaluate:76` creates a `Row` object per equation per
  evaluation. `V3MeshResidual:11` then creates an immutable list snapshot and
  scans it for nulls. Row/container allocation totals roughly **250–289 MiB**
  per solve in these recordings.
* `V3BlockJacobianAssembler.assembleLocalThermodynamicColumn:473` creates equation
  IDs for row-map lookups inside component/column loops. `V3StageBlockLayout`
  also uses repeated sublists and streams to validate each component's rows.
* `V3DryMeshCoordinateMap.decode` builds fresh liquid/vapor arrays, then the
  `V3DryMeshState` constructor copies them again. `copyFlows:150` alone represents
  roughly **4–5%** of sampled allocation. The safe ownership-transfer pattern
  used for Jacobians is a candidate here too.

## Reduction order

1. **Small changes first:** use ordinary loops for hot finite/null validations,
   precompute equation-row indices for a fixed problem, and transfer freshly
   decoded trial-state arrays into their immutable state through an internal-only
   path. Preserve defensive copying for caller-owned arrays. These address
   allocation patterns that do not require changing numerical operations.
2. **Reuse exact-shape linear work buffers within a solve.** A solve-owned
   workspace can reuse band/factorization storage once the preceding operation
   has finished. It must clear reused entries, preserve the existing pivot and
   arithmetic loops, resize safely, and keep different simultaneous solves
   independent. Holding buffers longer can raise live heap even as cumulative
   allocation drops, so repeat both the memory-capacity and runtime tests.
3. **Replace object-heavy residual evaluation with internal primitive buffers.**
   Keep immutable snapshots for observers, retained base residuals and published
   audit data. This is a larger change because base/candidate/probe residuals may
   coexist; reusing one array indiscriminately would corrupt those comparisons.
4. **Use internal thermodynamic workspace views where ownership permits.** Avoid
   materializing and copying full result objects for hot internal scalar reads,
   while preserving immutable public result APIs and all finite/domain checks.

The remaining profile supports opportunities worth hundreds of MiB per solve;
it does not justify adding the percentages as guaranteed savings. Some numerical
storage must remain, and buffer reuse may trade allocation for longer retention.
Each change should pass exact-output, ownership, minimum-heap and timing
comparisons. Relaxing tolerances or skipping audits is unnecessary for these
methods.

This follow-up adds profiling/grouping support and this analysis. It makes no
additional production changes beyond the existing RAM-reduction branch.

## Reproduce

Use `v3RamMemory` with `ramCase=Default`, `ramProfile=true`, `ramSamples=5`,
`ramDeadlineMillis=45000` and separate `ramReport` paths. Run
`v3MemoryProfileSummary` on each recording's JSON metadata, then:

```powershell
node src/test/diagnostics/v3-ram-reduction/allocation-breakdown.cjs
```

The exact paths expected by that script are under
`build/reports/v3-ram-reduction/default-allocation-{1,2}*`. The combined evidence
is `default-allocation-breakdown.json` in that directory. The grouping rules are
explicit in `V3MemoryProfileSummary` and do not double-count allocation stacks.

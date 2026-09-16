# Remaining fluid qualification: concrete execution order

Updated 2026-09-16. The acceptance matrix in `FLUID_NETWORK_ACCEPTANCE.md` remains authoritative. This document gives the next testing assignments; an assignment is not a pass.

Class/tool consolidation was committed as `a5dedf6`. Subsequent RAM optimization is verified by 790 unit tests, 14 GameTests and 13 Python checks, with all 60 cadence rows and the canonical report unchanged. The current candidate is `0e61828aebbaaa1251c804014856e05fb2719c0ae93a151a4ae7c3b590d895be`; section A records the completed historical `8dae23fe...` group. Future performance runs must use the actual candidate hash and cannot be pooled with earlier artifacts. Use `examples/Fluid-Benchmarks.py` subcommands for benchmark audit/summary/memory operations. Subsequent agent assignments use Sol, as requested; Luna ownership below records already completed historical work.

The RAM checkpoint is complete: one matched 4 GiB baseline/candidate pair and a separate optimized 3 GiB capacity probe, all 100 networks advancing with conservation and clean runtime audits. Allocation per accepted simulated second falls 55.05% in the matched pair; fixed-heap resident RAM barely changes. Nonzero holds and differing host load prevent a sustained-performance or speedup claim. See `FLUID_NETWORK_STRESS_TEST.md` for exact evidence. Sections B–D remain open; future capacity qualification needs repeats under controlled host load and a loaded-world/soak workload.

## A. Complete the current two-worker module replicate group

Owner: Luna testing subagent; parent owns review and production fixes. **Completed:** all three named fresh-process replicates pass on the same production/configuration/fixture revisions; see the acceptance record for pooled statistics and the two retained warmup holds in r03. The directions below record the executed protocol.

- Freeze production artifact `8dae23fe56e0139ec0b4d00c0931fddea208bd9708c896553695057578338d3a`, fixture classes and build configuration. Do not run competing CPU tests or change measured classes.
- Existing pass: `reload-budget-module-2w-r01`.
- Run `luna-module-2w-r02`, then `luna-module-2w-r03`, each in its own fresh JVM/world, fixed two workers, profile `module`, normal 120-second warmup and 200 measured five-second intervals per island.
- Between the new runs, stop for parent validation of the strengthened offline aggregator. Run independent correctness checks concurrently in this gap: one Gradle/JVM owner runs the new physical fixtures while the parent runs the standalone Python evidence checks. A second Luna agent may prepare unit source during a benchmark, but may not compile or execute it until the measurement ends. Do not delete or overwrite failed evidence.
- For each run, verify the report/artifact/log hashes, zero runtime errors, FULL/accepted interval history, 200 consecutive 100-tick slices per island, at least 1,000 measured wall seconds, component/energy errors <=1 tolerance unit, queue-inclusive p95 <2,000 ms, and server engine p95 <2 ms/tick. Retain warmup holds and all maxima explicitly.
- A failed run stops the sequence for diagnosis. Do not weaken a threshold, shorten the run, or substitute a pilot.
- Pool only identical production/science/configuration/fixture revisions. Record individual and pooled p50/p95/max, actual simulation duration per island, process identities and conservation residuals in `FLUID_LUNA_TEST_REVIEW.md`.

Completion closes only the fixed-two-worker module repetition group. It does not close M9.

## B. Characterize aggregate-load cadence adaptation before expanding performance repetitions

The refreshed current-artifact stress run (`luna-stress100-12cap-r01`) now provides a reproducible recovery target: 273 measured holds and 28 in the last 1,200 ticks, followed by a clean final 400 ticks. Preserve this report/JFR and control for host CPU activity before attributing the difference from older runs to a code change. This performance diagnosis and the aggregate-load policy tests below should precede spending time on all remaining long repetition groups.

The current `IslandCoordinator.closeRound` calls `IslandClock.performanceSample` from each completed job's CPU/wall duration. A successful job below one quarter of the soft budget counts as low load, independently of outstanding demand elsewhere. Many individually short jobs can therefore produce low-load observations while the shared pool remains saturated. This is a source-grounded test target; the existing injected-CPU cadence test does not measure this aggregate behavior.

Concrete next fixture:

1. Use production clock/coordinator/allocation policy with 100 independent owners, a twelve-worker ceiling and a deterministic event-driven worker model. Initial cadence is 100 ticks; modeled service is 300 ms of CPU per full interval. Twelve workers can sustain 40 intervals/s: five-second cadence needs 20/s, but one-second cadence needs 100/s.
2. Record each owner's cadence, simulated/online time, debt, admitted work, ready demand and worker occupancy. First establish the expected low-load decrease, then cross the calculated capacity threshold without changing physics or losing time.
3. During sustained overload, verify that adaptation does not keep interpreting fast individual completions as spare aggregate capacity. Use elapsed CPU/work capacity and persistent ready demand as evidence; wall delay alone cannot prove CPU saturation.
4. Remove most demand, require bounded recovery/hysteresis, and preserve 1–20-second cadence bounds, conservation, one outstanding job per owner, causal fences and deadline semantics.
5. Follow any production change with the existing physical cadence references, worker-equivalence tests and actual-server contention before new measured qualification. A changed production artifact starts a new performance-evidence group.

Do not describe a deterministic service model as measured host CPU utilization. Add an actual-server adaptive-load run after the control-policy behavior is established.

## C. Consolidate remaining physical and lifecycle rows

Prioritize shared near-depletion/suction cases (P08/P21/P24), full in-interval reversal history (P10), phase-transition temporal references (P17), real completion versus block replacement (P35), paired loaded/unloaded trajectories (P44), and restart with pending work (P45). Use existing qualified material/phase fixtures and independent component/energy accounting. Each row needs a reproducible case and an explicit assertion, not only a named test class.

The new shared-source fixture now supplies a concrete diagnostic starting point: both branch orders accept 0.11 s, retain 55.9% stock at 277.13 K, then refuse numerically. Reconstruct that exact last accepted state and distinguish an unsupported endpoint from a solvable numerical failure before changing the solver. Its temperature is close to the 273.16 K model floor, but proximity alone does not prove the refusal cause. For the finite-pump case, retain the full discarded-substep history and its working adequate-suction control. The current safety passes do not establish successful near-empty operation.

The external boundary ledger across restart (P29), binding reconciliation report (P46), remaining GUI/debug cases (P06/P18/P22/P48/M8.4), and wider property/reference coverage stay open until implemented and exercised.

## D. Finish performance matrix and soak on the stable candidate

Run fresh-process repeats for profiles `one`, `many`, `module` at configured workers 2, 1 and automatic 0: three qualifying runs per group. Preserve all failed and warmup evidence. Automatic mode retains the configurable twelve-worker default ceiling.

Implement and run the separate 30-minute paced soak with timestamped topology changes, bounded fallback and FULL recovery, loaded/unloaded bindings, conservation including pending material, and final quiescence. Report final debt, pending/queued ownership, retained material and clean shutdown. The current ordinary benchmark profile is not a substitute for this event-driven soak.

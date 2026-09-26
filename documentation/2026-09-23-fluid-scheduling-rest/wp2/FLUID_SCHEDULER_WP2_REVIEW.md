# Fluid scheduler WP2: rest and steady-flow certificates, review of the implementation

Date: 2026-09-23. Branch `claude/fluid-scheduler` (worktree `agent-ae139e4fc1b184b36`), not pushed, not merged. Batch `2026-09-23-fluid-scheduling-rest`.

Plan: `FLUID_ISLAND_REST_PLAN.md` revision 3 (main checkout, `documentation/2026-09-23-fluid-scheduling-rest/`): sections 3.2, 3.3, 3.6 and 4, section 5 items 3, 5 and 6, section 6. Every number quoted here is in `wp2-tables.md`. Logs, test XML, campaign and gate scripts, comparison scripts and crash files are in `wp2-logs/`. Probes are in `wp2-probes/`.

## 0. Summary

* **Built:** REST and STEADY certificates, materialised time, horizon revalidation, a module drive index, release of the retained solver, status strings, property-hold requalification, and the six `[fluid]` keys of section 4, all as the plan describes. The deviations are listed in section 1.6. Certificates live in memory only; persistence is WP4.
* **Flow test fixed (T2, `4f1686e`):** a pipe that moves less than `eps_s` of the smaller finite inventory it draws on, in both intervals, is now quiet and exempt from the relative flow test. On a settled closed ladder the island's largest flow is itself solver noise, so the relative test had been refusing noise. rest100 now certifies 100 of 100 islands, against 95 before, all by the 23rd measured second. Full solves fall from 2400 to 18 in the window.
* **Bitwise gap (T3):** the enabled path is bitwise neutral, and there was nothing to fix in code. Two runs made while the machine was unstable, `transient100-wp2-off-r01` and `stress100-wp2-on-r01`, each carried one perturbed island, 10989 and 11340; every other run agrees with them nowhere else. The 5.2e-9 was in the certificates-off run, not the on run. Tests were added on the synchronous rig and on P12's real twelve-worker executor. Off-vs-off floors are exactly 0 on every profile once those two runs are set aside.
* **Sensitivity (T4):** at `eps_s` 1e-8, stress100 does not certify and is bitwise equal to certificates off. At 1e-7, 92 certificates are issued (26 STEADY at the end) and full solves fall from 2400 to 2002. Inventory deviates by at most 1.0e-8 and energy by 1.45e-8, with the ledger conserving to 1.2e-15. Measured against the island's inventory, the ledger deviates by at most 9.3e-10.
* **Gates on `e924eae`:** science 161/0, runtime 177/0, network 30/9, 19/14, 37/3, regression exactly zero, GameTest 22 passed, P12 and P31 byte-identical.
* **Machine:** it was unstable during the session. There were two new JVM crashes, crashes of dwm.exe, Everything.exe and the game, and a corrupted System event log. The ten replacement runs were all made on a quiet machine with no crash. See section 7.

## 1. What was built, against the plan

Commits of WP2 on the branch, after the WP1 set:

| commit | content |
|---|---|
| `74b62e3` | round wall-budget closure within a tick (a WP1 acceptance follow-up; changelog line of its own) |
| `5cd7801` | changelog WP0/WP1 entries |
| `3075004` | certificates: `IslandCertificate`, `CertificatePolicy`, coordinator certified path, `IslandClock.rest`, `[fluid]` keys, `IslandCertificateTest` (19), certified hold GameTest |
| `7d89784` | module drive index; module host decides from stored island state |
| `82568c6`, `115d690`, `3b7ff82` | benchmark certificate measurements, reference state, refusals |
| `4f1686e` | quiet pipes exempt from the relative flow test; `Stationarity` evidence kept as diagnostics; tests for T2 and T3 |
| `e924eae` | benchmark publication fingerprints, per-interval evidence, verbatim refusals |
| `9b954a4` | changelog WP2 entry |

### 1.1 Materialised time (plan 3.2)

`IslandCoordinator.materialise` advances a certified island to `min(online, nearest fence, hold tick, horizon, earliest drive)` through `IslandClock.rest(toTick, fence)`. `rest` refuses an outstanding slice, a target below the committed tick, and a target past the online tick or the fence, and it clears the retry (unit test `restCommitsWithoutASolveButNeverPastTheOnlineClockAFenceOrAroundAnOutstandingSlice`). Materialisation happens only on demand:

* `snapshot(id)` and `snapshots()`, and therefore views (`FluidWorldAuthority.view` reads `snapshot(owner)`) and saves (`capture`);
* `aligned` at events;
* the island's own deadline, which is its horizon or next module drive;
* suspension for a property hold.

`observe()` reads stored state without advancing anything. The module host and the benchmark's once-a-second samples use it. A REST island with no viewers and no events holds no heap entry at all, and a STEADY island holds one, its horizon. `certifiedIslandsCostNothingPerTickAtOneHundredOrAThousand` shows 0 visits, pumps, solves, deadlines, snapshots and materialisations over 10,000 ticks at 1, 100 and 1,000 islands. The status strings read `RESTING: no flow since <t> s` and `STEADY: replaying <q> kg/s since <t> s, next check at <t> s`.

### 1.2 Certificates (plan 3.3)

* **Entry evidence** is checked in `closeRound`, on accepted FULL intervals with no material transfers and no degraded episode. Candidates with scheduled transfers are already refused by `validCandidate`. Two consecutive intervals of equal duration are compared by `IslandCertificate.stationarity`, which returns the first discrete difference or, for each continuous test, the smallest tolerance it passes at:
  * discrete: FULL, transitions, closures, blocked masks, filter cakes, node solids, phases, endpoint modes, and fixed-node states compared bitwise;
  * continuous: component change-of-change against inventory, energy change-of-change against `max(|U|, nRT)`, per-interval temperature or pressure drift, and flow change with quiet pipes exempt (section 3.2).
  Certification also needs no module drive in the coming cadence, and a streak of `max(2, restConfirmIntervals)` intervals for STEADY or `restConfirmIntervals` exact-zero intervals for REST.
* **REST** requires every inventory change, average flow, gross pipe transfer, boundary transfer and the pump work of the interval to be exactly zero. **STEADY** additionally requires no solids in transport and no filter on the island.
* **Replay** keeps the certificate's base graph and states. The inventory becomes `base + f * delta` with `f = elapsed / D`, and boundary transfers, pump work and pipe history are the recorded interval scaled by the replayed fraction. Each replayed span is published to a separate listener (`onReplayed`), which feeds only accounting. The island keeps its last solved interval as its `lastResult`, so saves and views read what they always read.
* **Horizon:** `base + D * min(K_max, floor(budget / d), zero limit)`. Here `d` (`Summary.drift`) is the largest relative per-interval change of any finite component, internal energy, temperature or pressure, and the zero limit stops replay before any extrapolated component reaches zero. At the horizon the island wakes and solves one slice. If that slice repeats the certified interval within `eps_s` the certificate is renewed and keeps its since-tick; otherwise the island stays awake with the revalidating interval as the first of a new streak.
* **Invalidation:**
  * a topology or configuration event replaces the island, which starts awake;
  * a module drive wakes the island exactly at the drive tick;
  * a property hold caps materialisation at the hold tick, and resume discards the certificate.
* **Retained solver:** `retained = null` is set only in `certify`, after the terminal completion of the qualifying interval, and a wake installs a fresh `RetainedSolver`. The scheduler's self-verification throws if a certified island owns an attempt or solver caches.
* **Drive index (`7d89784`):** per island, a `TreeSet` of due ticks of pending inputs with mass left and the next withdrawal tick of each active cycle with a positive target. Known-zero cycles register nothing. `earliestDrive` is a range query with no side effects. The index is rebuilt after every module commit, committed receipt and rebind, and it tells the coordinator which islands' drives changed.

### 1.3 Property hold (plan 3.6)

`suspendForPropertyChange` materialises a certified island to the hold tick and caps it there, while online time keeps accruing. `resumeQualifiedProperties` discards every certificate and clears all evidence, so the island solves its debt from the held state and requalifies over the confirm count. This is covered by the unit test `aPropertyHoldFreezesACertifiedIslandAndResumeRequalifiesOverTheConfirmCount` (confirm 2 and 3) and the GameTest `aCertifiedIslandIsFrozenByAHoldAndRequalifiesAfterResume`, in which a lone tank certifies REST.

### 1.4 Numerical contract (plan 4)

All six keys are in `[fluid]` with the plan's defaults and ranges, captured at server start through `FluidOptions` and validated by `CertificatePolicy`:

* `restDetection` true;
* `certificateStationaryTolerance` 1e-9, range 0 to 1e-6;
* `certificateInventoryBudget` 1e-6, range 1e-12 to 1e-3;
* `certificateMaximumIntervals` 17280, range 1 to 1,000,000;
* `restConfirmIntervals` 2, range 1 to 10;
* `restRecheckSeconds` 0, range 0 to 86,400.

`restRecheckSeconds > 0` bounds a REST window; at 0 exact rest is never rechecked. The defaults were kept, per the owner ("OK for now, configurable").

### 1.5 Tests (plan 5 items 3, 5, 6)

* `IslandCertificateTest`, 22 tests (19 from `3075004` and 3 from `4f1686e`):
  * REST for a dead-headed line and a dead-headed pump, costing nothing afterwards;
  * a settled closed pair certifies STEADY with a long horizon;
  * generator-to-void and a matched-throughput tank stay within `delta_budget` of solving, with exact conservation;
  * composition flush, slow fill and pump heating do not certify;
  * horizon renewal and drop;
  * the hold with confirm 2 and 3;
  * events inside a window align at their exact ticks;
  * module drives wake exactly at their tick;
  * changed controls replace the island;
  * synthetic evidence: the small-inventory cutoff, gross flow without net flow, slow monotonic change, transitions, horizon arithmetic, and linear replay;
  * new: quiet and loud pipes at and around the edge, and the boundary-only and junction references;
  * new: rest100 ladder 65, rebuilt from the fixture, certifies;
  * new: an island that never certifies is bitwise unchanged (section 4).
* `CausalModuleCoordinatorTest` has 4 new cases: certified islands reproduce the solve-every-interval module outcome; the drive index holds positive withdrawals and due inputs but no known-zero cycle; a known-zero cycle resolves its horizon without waking a certified feed; stranded buffers and partial inputs behave the same.
* `IslandSchedulerTest` shows certified islands cost nothing at 1, 100 and 1,000.
* `IslandClockTest` covers `rest`.
* P12 gained a certificates mode (section 4). The P12 and P31 rows themselves run with certificates disabled.

### 1.6 Deviations from the plan

1. **In memory only.** There is no certificate signature and no persistence: a restart starts every island awake. Plan 3.5 is WP4.
2. **Per-interval deltas.** The certificate stores the last solved interval (per-interval deltas and the recorded result) and scales by the fraction of that interval, rather than storing per-second rates. The arithmetic is the same.
3. **Solids never replay.** The entry evidence requires unchanged node solids, and STEADY requires no solids in transport and no filter. The plan's solid deltas through `SolidInventory.plus/scale` are therefore never needed (plan 9 already defers cake accumulation).
4. **Energy scale.** Energy is compared against `max(|U|, n R T)`, not `|U|`, because internal energy is measured from a reference state and can pass near zero.
5. **Temperature and pressure drift.** An added first-order test requires per-interval temperature and pressure drift at most `eps_s`, so that pump heating and slow depletion cannot certify. The plan's `eps_s` row names "state variable", but entry-evidence item 1 lists only fixed-node states. This test and the flow test are what keep stress100 awake (section 3.3).
6. **Quiet pipes (T2).** The flow test exempts quiet pipes; section 3.2 gives the reasoning. This departs from the plan's formula `|q2 - q1| <= eps_s * max(|q2|, q_ref)`.
7. **Stricter REST.** REST also requires zero gross pipe transfer, zero pump work and zero boundary transfer. Gross flow with a zero average is refused outright.
8. **Module host reads stored state.** The host never materialises a certified island while it decides, because between releasing one fence and installing the next it could pass the next fence's tick. It reads stored state and materialises only through alignment.
9. **Evidence kept as diagnostics (`4f1686e`).** `certificationEvidence` and `certificationRefusal` are read by the benchmark and never by a decision.
10. **Section 6 benchmark rows.** `viewers` and `module` were not run: `viewers` belongs to WP3, and `module` is covered by the unit comparison in `CausalModuleCoordinatorTest`. The plan asks for three repeats per profile; one quiet pair per profile was run, with off-vs-off floors from the earlier runs.

## 2. Gates

On `e924eae` (the only later commit, `9b954a4`, is the changelog). One Gradle invocation at a time, `JAVA_OPTS=-Xshare:off`, daemon started with `-Xshare:off`; logs in `wp2-logs/final-gate-*.log`, sequence in `wp2-logs/gates.log`.

| gate | result |
|---|---|
| `fluidScienceTest --rerun` | 161 tests, 0 failures |
| `fluidRuntimeTest --rerun` | 177 tests, 0 failures (174 before + 3 new) |
| `fluidNetworkBenchmark --rerun` | 30/9, 19/14, 37/3 accepted/rejected substeps |
| `fluidSolverRegression -PfluidRegressionMode=exact` | chain-100 max deviation 0.000e+00 in state/moles, temperature, phase fraction, flow |
| `runFluidGameTestServer -PfluidGameTestRunId=wp2-final-r01` | All 22 required tests passed, scheduler self-verification on |
| P12 / P31 fingerprints | byte-identical to the `wp2-logs` copies (SHA-256 `56332b64...` and `4dcb80a4...`, the WP0 values) |

## 3. The three outcomes against plan section 6 (T1)

### 3.1 Closed islands certify STEADY, not REST

REST is the exact identity map. Every inventory change, average flow, gross pipe transfer, boundary transfer and the pump work of the interval must be exactly zero. The solver produces an exact zero only where a row pins a flow to zero:

* a connection held `CLOSED`: a dead-headed line whose boundary refuses its only root (`1403eeb`), or a pump at its shutoff corner;
* a pipe blocked in both directions by a settled bed or a filter at capacity;
* an island with no pipe at all, like the lone tank of the hold GameTest.

A closed island at hydrostatic balance is found by Newton iteration and keeps residual flows. rest100 on-r02 certified with its largest flow between 4.2e-11 and 2.2e-8 kg/s (median 3.7e-9), and `ConservativeTransport` documents 1e-14 kg/s on an island at rest. So REST is reachable only in the cases above, and plan section 6's expectation that "every island certifies REST" in rest100 cannot hold. What the closed ladders get instead is STEADY with windows of 926 to 17,280 intervals (median 2,977, about 4.1 h). Their drift d is 5e-12 to 1.1e-9 per interval, so `budget / d` is large and `K_max` often binds. The cost is one revalidating solve per window, the same order as REST with a daily recheck. Replay extrapolates that noise drift; the grid deviation it causes is at most 2.4e-8 relative, far inside the 1e-6 budget, and conservation holds to 6e-15.

### 3.2 The ill-conditioned flow test (fixed)

The old test refused a pipe when `|q2 - q1| > eps_s * max(|q2|, q_ref)`, where `q_ref` is the island's largest flow. On a settled closed ladder `q_ref` is itself solver noise, between 1e-10 and 2e-8 kg/s, and a noise flow changes by the order of itself from one interval to the next. In the probe the relative flow change alone has a median of 0.6 to 1.9 over 1,000 intervals on rest100 ladders 46, 58, 65 and 80, with outliers up to 62. The old test passed 0 of 999 pairs on each of these four ladders. They are exactly the four islands rest100 on-r01 refused for the whole window ("pipe flow changed by 2.1e-4 / 0.012 / 0.021 / 0.35 of the largest flow").

**Fix.** A pipe is quiet when the mass it moves in one interval, in both intervals, is at most `eps_s` of the smaller finite inventory it draws on:

* the smaller endpoint mass (fluid moles times molecular weight);
* a junction endpoint stands for the island's smallest finite inventory, since it holds nothing and passes the flow on;
* a pipe between two boundaries has no finite inventory behind it and is never quiet.

A quiet pipe is exempt from the relative test, because whatever its flow does is already bounded by the component and energy tests of the inventories it moves between. A loud pipe (a real through-flow) is still held to the relative test, so a slowly changing throughput is still refused.

This is the formulation that keeps accounting honest. The alternative, scaling the reference by inventory per interval, would also have loosened the main-flow pipes of through-flow islands: 1e-9 of a 24 kg vessel per 5 s is 5e-9 kg/s, which is 1e-7 of their 0.048 kg/s flow, and replaying such a drifting flow for `K_max` intervals would have let the boundary ledger wander. Replay itself stays exact at any tolerance, because it scales the recorded interval.

**Effect.** In the probe, the four ladders pass 403 to 883 of 999 pairs at 1e-9, and their first stationary pair comes at 30 to 85 s. The component test at around 1e-9 then binds. In the benchmark, rest100 on-r02 has 100 STEADY islands (94 at the first measured second, all by the 23rd), 18 full solves in the window against 2400, and 0 eligible islands. After-GC heap is 331 MiB against 1000, and the allocator is down to one admitted worker.

### 3.3 Through-flow islands never certify at eps_s = 1e-9

Final refusals by family on the quiet runs:

| family (runs) | refusal | measure that binds | drift d |
|---|---|---|---|
| TRANSIENT (150: transient100 on-r03 100, mixed100 on-r02 50) | temperature or pressure drift 2.7e-4 to 1.6e-3 per interval, all above 1e-6 | 5.4e-4 to 4.8e-3 | 1.4e-3 to 3.1e-3 |
| THROUGH (125: stress100 on-r02 100, mixed100 on-r02 25) | drift in 1e-8..1e-7 for 74 + 18 islands, in 1e-9..1e-8 for 26 + 7; none above 1e-6 and none below 1e-9 | relative change of the main flow, 3.7e-8 to 3.1e-7 (median 1.04e-7) | 1.2e-7 to 4.4e-7 |

TRANSIENT ladders are filling, as their fixture intends, and must not certify. On the brief's split, all 125 THROUGH refusals fall at or below 1e-7, the band the brief calls the tolerance edge. The binding flow measure puts 57 + 12 of them above 1e-7.

They are not at the tolerance edge in the sense of Newton noise, though: they are a slow physical transient. The evidence is smooth and reproduces bit for bit between runs. The fast start-up transient is gone by 60 s (median flow measure 6e-3 at 20 s, 4e-5 at 40 s, 2.4e-7 at 60 s). After that the ladders sit on a plateau: over 60 to 180 s the median d moves only from 2.53e-7 to 2.21e-7 per interval, a median e-folding of 1,700 s, with 13 islands still rising.

The benchmark window cannot resolve the time constant, so the probe (`wp2-probes/`) followed three stress100 ladders through the real coordinator for 20,000 s:

* the per-interval flow change stays at 6e-8 to 2e-7 for thousands of seconds;
* it then decays with e-folding times of 2,100 to 8,400 s;
* ladder 0 has its first stationary pair at 1e-9 only at 17,010 s (4.7 h), and ladders 7 and 46 have none within 20,000 s;
* at 1e-8 the first pairs come at 11,445 s, 16,965 s and never.

The drift is what one expects from the vessels' initial holdup being flushed by the throughput. Each 1 m3 vessel holds about 24 kg against 0.048 kg/s, and the ladder about 500 kg, so the residence time is of order 10^4 s.

A longer warm-up would therefore not change the result at the default tolerance unless it ran for five hours or more; 10 minutes instead of 60 s changes nothing. This is correct behaviour: the inventories really do move by 2e-7 per interval. At 1e-7 the ladders do certify, with short windows (section 5).

## 4. The bitwise gap (T3)

**Question.** A certificates-enabled run in which nothing certifies must be bitwise identical to certificates off. Earlier comparisons showed 7e-15 (stress100, on-r01 against off-r02) and 5.2e-9 (transient100, on-r02 against off-r01), although nothing had certified in either on run.

**Code.** With certificates on and nothing issued, the only extra work is `qualify`: it builds a `Summary` and compares two of them. Every accessor it reads returns a copy (`Inventory.moles`, `averageMassFlows`, `BoundaryTransfer.moles`, `phaseMoles`, `phaseVolumes`). It writes nothing a solve reads, and the policy reaches nothing but `qualify`. The replay listener is never called without a certificate, and the harness's ledger and reference capture are sums over identical publications.

**Tests (`4f1686e`).**

* `anIslandThatNeverCertifiesSolvesBitForBitAsWithCertificatesOff` runs three fixtures for 40 intervals each on the synchronous rig, with certificates on and off: slow fill, pumped loop, and stress100 ladder 7. It compares a SHA-256 over every publication (clock, status, every node's inventory, energy and state, flows, boundaries, pump work, substeps, pipe transfers) and asserts that the evidence ran with certificates on and not with them off. The digests are identical.
* P12 gained a fourth mode, `AUTOMATIC_TWELVE_CERTIFICATES`: the real bounded executor with twelve workers, certificates on and the entry evidence evaluated at every interval. It uses a confirm count of 10, which three intervals cannot reach; at the default of 2 a water pair certifies at 3 s. It must equal the one-worker reference frame by frame and match `AUTOMATIC_TWELVE`'s digest. It does, and the P12 file stays byte-identical because the mode is kept out of it.

**Benchmarks.** These use the new publication fingerprints (`e924eae`) and the reference grid.

* **transient100.** The new certificates-off run off-r02 (quiet machine) is bitwise equal to the old certificates-on run on-r02 on all 92 exact grid islands, island 10989 included. The old off-r01 differs from both at 10989 and nowhere else. So the 5.2e-9 was in the certificates-off run made at 14:19 to 14:22, during the game's crash series, not in the enabled path. off-r02 against on-r03: 95 of 95 grid islands bitwise equal, and 95 islands identical on every publication.
* **stress100.** The old on-r01 differs at 11340 (7e-15) from off-r01, off-r02 and on-r02 alike, and those three agree everywhere. on-r02 against off-r03: 88 of 88 grid islands and 95 fingerprinted islands identical. on-r02 against off-r02: 95 of 95.
* **Floors.** Off-vs-off on the grid is exactly 0 for stress100 (93 and 88 islands), rest100 (91) and mixed100 (90). For transient100 the only off pair includes the perturbed off-r01; with it set aside the transient floor is 0 (off-r02 = on-r02 = on-r03 on every common grid island).

**What does part runs.** In every pair, the islands that part are the ones a startup wall-deadline hold shifted (4 or 5 per pair). The same islands are held in both runs, both retry the slice `[0, 50]`, and the retries differ from the first retried interval on (for example 44 accepted/11 rejected substeps against 44/12). The retry reuses the island's `RetainedSolver`, including whatever the cancelled job left in it, and how far that job got depends on wall time. This is pre-existing, timing-dependent behaviour, the same with certificates off, and outside WP2. It is a follow-up (section 8).

**Conclusion.** No code change was needed for T3. The enabled path is bitwise neutral in unit tests, on the real executor and in every quiet benchmark pair. The two single-island deviations belong to runs made while the machine was unstable (section 7). A silent memory error would explain one perturbed island per run; that is a hypothesis, not proven.

## 5. Sensitivity (T4)

stress100 with `-PfluidCertificateTolerance`, compared with stress100-wp2-off-r02 on the reference grid. Everything else is at the defaults (budget 1e-6, `K_max` 17280, confirm 2).

| eps_s | certificates issued / final | full solves (off 2400) | replayed | inventory / energy deviation | ledger against own inventory | conservation residual |
|---|---|---|---|---|---|---|
| 1e-9 (on-r02) | 0 / 0 | 2400 | 0 | bitwise 0 (95 islands) | 0 | 0 |
| 1e-8 (tol8-r01) | 0 / 0 | 2400 | 0 | bitwise 0 (95 islands) | 0 | 0 |
| 1e-7 (tol7-r01) | 92 / 26 STEADY | 2002 (-16.6 %) | 114 intervals, 2,083 s | 1.00e-8 / 1.45e-8 | 9.35e-10 | 1.2e-15 (energy 3.5e-12) |

**At 1e-8.** The flow measure of every through ladder, 3.7e-8 to 3.9e-7, is above the tolerance, so nothing certifies. The final refusals are 74 temperature or pressure drifts in 1e-8..1e-7 and 26 flow changes.

**At 1e-7.** Islands certify from the first measured second: 8, then 15 at 30 s, then 26 at 120 s. The windows are 3 to 7 intervals, because `budget / d` with d between 1.4e-7 and 3.3e-7 is small. Each horizon's revalidating slice differs from the certified interval by the accumulated flow change, about K x 1e-7, so all 74 horizon wakes in the window dropped and requalified over two solves; none was renewed. The saving is therefore modest, 16.6 %.

**Deviations at 1e-7.** Every grid deviation sits on an island that replayed: 43 of the 54 replaying islands on the grid differ, and 0 of the rest. Inventory deviates by at most 1.0e-8, 100 times inside the 1e-6 budget.

**The ledger.** The per-island net ledger ratio that `wp2cmp.js` prints reads 9.3e-6. That ratio divides by the island's net exchange (inflow minus outflow), which for a through-flow island is only its accumulation, about 1e-3 of its throughput. The absolute deviations are 1e-8 to 6e-7 mol against about 100 mol that passed through. Measured against the island's own inventory, the budget's reference, the ledger deviates by at most 9.35e-10. The ledger moved by exactly what the inventory moved, to 1.2e-15 of inventory (energy 3.5e-12), and both runs pass the harness's integrity audit (balance units 2.6e-7 against 3.2e-7 off).

**Owner's requirement.** The requirement was that effort be saved when contents vary only microscopically, with exact accounting. Accounting is exact by construction at every tolerance and measured at 1e-15. Effort is saved wherever the per-interval change is below `eps_s`:

* closed islands at 1e-9, with 99 % fewer solves;
* the stress100 ladders only from 1e-7, and only 17 %, because their contents are not microscopically varying: they move by 2e-7 per interval for hours.

A tolerance of 1e-7 with the 1e-6 budget keeps every deviation measured here under 1.5e-8. Keeping the defaults follows the owner's instruction.

## 6. Certificate benchmark per profile (T5)

Full table in `wp2-tables.md`. Quiet pairs, run 15:44 to 16:17: 12 workers, 60 s warm-up, 120 s window, all audits PASS, integrity passed, 0 held intervals in any window.

| profile | full solves off / on | replayed on | final on | mean eligible off / on | zero-demand samples off / on | ready-to-publication p50 / p95 ms, off / on | engine ms per tick p50, off / on |
|---|---|---|---|---|---|---|---|
| transient100 | 2400 / 2400 | 0 | 100 AWAKE | 6.2 / 6.2 | 96 / 96 of 120 | 20.8 / 64.0, 20.6 / 64.1 | 0.021 / 0.020 |
| stress100 | 2400 / 2400 | 0 | 100 AWAKE | 4.8 / 6.2 | 96 / 96 | 10.9 / 56.1, 12.6 / 58.2 | 0.023 / 0.025 |
| rest100 | 2400 / 18 | 200 intervals, 15,050 s | 100 STEADY | 6.2 / 0 | 96 / 120 | 8.5 / 55.4, 3.4 / 4.1 (18 solves) | 0.020 / 0.0098 |
| mixed100 | 2400 / 1807 | 50 intervals, 3,765 s | 25 STEADY, 75 AWAKE | 6.2 / 1.86 | 96 / 96 | 14.9 / 63.6, 14.9 / 54.7 | 0.020 / 0.021 |

* transient100, the fixture that cannot certify, is unchanged within the WP1 noise band: latency p50 20.2 to 23.6 ms over three WP1 runs.
* rest100 after-GC heap: 1000 MiB off, 331 MiB on.
* In mixed100 the transient half keeps demand up, as plan section 6 expects.
* No run certified REST, for the reason in section 3.1.

## 7. Machine and JVM crash record

1. **Class-data-sharing archive.** At the start of the session `java -Xshare:on -XX:+VerifySharedSpaces -version` on Zulu 25 reported "Checksum verification failed", and five launcher JVMs died at start-up (EXCEPTION_ACCESS_VIOLATION at `jvm.dll+0x75f9e0`, 14:47 to 14:48, `hs_err_pid1184/22508/6344/9564/4888`). The workaround was `JAVA_OPTS=-Xshare:off` on every `gradlew`; from 15:27 on, the daemon was also started with `-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Xshare:off`. At about 15:28 the same verification passed ("mixed mode, sharing"). The archive on disk is intact, and the earlier failure was transient.
2. **Two C2 crashes under memory pressure (JDK 21 GameTest servers, previous agent)**, both while the game ran with about 11 GB free:
   * `hs_err_pid21436`, 14:23, transient100-wp2-on-r01: C2 CompilerThread4 compiling `ConservativeTransport::reconstruct0` (OSR);
   * `hs_err_pid34388`, 14:36, rest100-wp2-off-r01: C2 compiling `PassiveStepSolver$Equations::<init>`.
3. **Two new crashes this session**, both while the game was running:
   * the Gradle daemon (pid 18752, Zulu 25, 3.5 h uptime) died at 15:27 with EXCEPTION_ACCESS_VIOLATION in a GC worker thread (`hs_err_pid18752-gradle-daemon-gc.log`);
   * a `fluidRuntimeTest` worker (JDK 21) died 2 s after start at 15:29, in C2 compiling `ConservativeTransport$Workspace::factor`, with 19 GB free (`hs_err_pid6128-runtime-test-worker.log`, `replay_pid6128.log`).
   The failed command was rerun once and passed.
4. **Other processes.** The Windows Application log shows crashes of Endfield.exe (12:03, 14:44 twice, 14:51, 15:10, 15:12), dwm.exe (13:02, dwmcore.dll) and Everything.exe (14:55). A query of the System log for WHEA hardware errors returned "The event log file is corrupted".
5. **Assessment.** Crashes spread across unrelated processes, two JVM vendors and different compiler methods, together with a transient CDS checksum failure and a corrupted event log, point to system-level instability: memory errors or an unstable memory or CPU setting. This is not proven. The owner may want to run a memory test and check the memory profile (XMP or EXPO). Nothing on the system was changed.
6. **Contaminated runs** (started before 14:53) were replaced by the quiet pairs in section 6:
   * `transient100-wp2-off-r01`, `-on-r02`;
   * `stress100-wp2-off-r01`, `-on-r01`;
   * `rest100-wp2-on-r01`, `-off-r02`;
   * `mixed100-wp2-off-r01`, `-on-r01`;
   * the crashed `transient100-wp2-on-r01` and `rest100-wp2-off-r01`.
   Their reports are kept only for the floors in `wp2-tables.md`. Two of them each carry one perturbed island (section 4). `stress100-wp2-off-r02` (14:53) is clean.
7. **The campaign.** `wp2-logs/campaign.sh` checked before every run (no Endfield.exe, at least 20 GB free) and would have kept a crash file and repeated a crashed run once. All ten runs started with no game and 29,270 to 31,500 MB free, ran 15:44:33 to 16:17:23, and none crashed. Gates ran 16:18 to 16:19, also quiet.

## 8. Open items and follow-ups

* **Startup-hold retry nondeterminism (pre-existing, not WP2).**
  * An island whose first slice exceeds the 2 s wall budget is retried with the solver caches the cancelled job left, and its trajectory then depends on how far that job got (4 or 5 islands per benchmark run).
  * A candidate fix is to replace `island.retained` after a non-accepted attempt that was cut off by the wall budget. That is a coordinator change outside this brief, and its cost is one cold start per hold.
* **Through-flow certification.** stress100 does not certify at the default tolerance, because its ladders are really moving for hours. If the owner wants such islands to save work, the choice is `eps_s` (1e-7 certifies with 3- to 7-interval windows and a 17 % saving) or a longer fixture warm-up of at least 5 h. The defaults are unchanged.
* **Revalidation never renews a drifting certificate.** At 1e-7 a revalidation compares the new slice with the interval certified K intervals earlier, so a steady drift of about 1e-7 per interval always fails it. 74 of 74 wakes dropped. Comparing the revalidating slice against the certified interval's extrapolation is a possible refinement; it was not done because it is a plan change.
* **Ledger normalisation in reports.** Through-flow islands should be judged on their gross boundary transfers; the harness records net ledgers only. `wp2diverge.js` now reports the ledger against the island's own inventory and a conservation residual, and a gross-transfer ledger in the harness is a small follow-up.
* **Plan items not done in WP2:**
  * no signature or persistence (WP4);
  * no `viewers` or `module` benchmark rows (WP3 and WP5);
  * one pair per profile rather than three repeats;
  * the property-hold GameTest covers a lone certified tank but not a module-coupled pair (plan 5 item 8 is assigned to WP4 in section 7).
* **Documents** stay in the worktree's untracked `documentation/fluid-scheduler/`. Nothing was copied to the main checkout; per `AGENTS.md` that is done at merge or when the batch ends. Nothing is pushed or merged.

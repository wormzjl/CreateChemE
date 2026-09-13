# Path-dependent `INFEASIBLE_SPECIFICATION` in the V3 column solver

Measured. The change is compiled, the suite is green apart from one pre-existing unrelated failure, and
the 108-case ten-worker verification passes all three criteria (§5).

`INFEASIBLE_SPECIFICATION` is a claim about the *request*: this specification cannot be satisfied, so no
initializer, no seed and no retry will help. Two of the four gates that published it in V3 do not support
that claim — they measure a bound against the last accepted continuation state, so their verdict moves with
the initializer. The R6 screen found the consequence on the 405-case neural validation population
(`tools/infeasible-screen/results.md` on `claude/v4-r6-infeasible-screen`, §1 "Soundness finding"): the
classical control types **108** of 405 as `INFEASIBLE_SPECIFICATION`, and **42** of those 108 are strictly
solved by another pipeline on the byte-identical input (47 counting advisory acceptances).

Reproduce the id extraction with `python tools/path-dependent-infeasible/extract_ids.py`; every count below
comes from `ids.json`, written by that script from recorded journals only.

## 1. Gate classification

REQUEST-ONLY = computable from the authored `V3ColumnInput` alone, before or without any solve. STATE-DEPENDENT
= reads a solved, continuation or seed state. Line numbers are on
`src/main/java/com/wormzjl/createcheme/science/column/v3/` after the change; the pre-change line is given
where it moved.

| # | gate | file:line | reads | class | code before | code after |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `totalDraw >= totalFeed` | `V3ColumnCalculator.java:412-415` (was 408-411) | authored side-draw rates and feed flows | **REQUEST-ONLY** | `INFEASIBLE_SPECIFICATION` | unchanged |
| 2 | static cooling admission (`staticCoolingAdmission` → `V3HeatFeasibility.availableCoolingWatts`) | `V3ColumnCalculator.java:448-463` (publish at 461, was 457); `V3HeatFeasibility.java:33-58` | authored duties + **one feed flash of the authored input**; no solve state | **REQUEST-ONLY** | `INFEASIBLE_SPECIFICATION` | unchanged |
| 3 | base-condenser-duty bound (`requireCoolingBelowBaseCondenserDuty`) | `V3ColumnCalculator.java:1779-1797` (throw at 1793, was 1772); detail `V3HeatFeasibility.java:125-132` | `heatFreeBase.attempt().state()` — the last accepted heat-free continuation state, re-integrated through `V3ColumnDutyLedger.condenserDutyWatts` | **STATE-DEPENDENT** | `INFEASIBLE_SPECIFICATION` | **`NONCONVERGENCE` + hint** |
| 4 | condensation cap (`condensationCappedTray` → `V3HeatFeasibility.condensationCapacityWatts`) | `V3ColumnCalculator.java:1295-1312` (throw at 1306, was 1290), helper at 1800-1812 | `previous.attempt().state()` — vapour traffic and tray temperatures of the last accepted ramp rung | **STATE-DEPENDENT** | `INFEASIBLE_SPECIFICATION` | **`NONCONVERGENCE` + hint** |
| 5 | publisher for 3 and 4 | `V3ColumnCalculator.java:660-666` (was 656-658) | — | — | `catch (InfeasibleSpecification)` → `INFEASIBLE_SPECIFICATION` | `catch (PathDependentHeatBound)` → `NONCONVERGENCE` |

Gates examined and found **not** to publish `INFEASIBLE_SPECIFICATION` at all, so untouched:

| gate | file:line | class | code |
| --- | --- | --- | --- |
| `V3OperatingDomainValidator.assess` (resolved pressure profile vs package envelope) | `V3OperatingDomainValidator.java:12-43`, published at `V3ColumnCalculator.java:509` and `:277` | REQUEST-ONLY (the profile is resolved from the request) | `PROPERTY_OUT_OF_RANGE` (unchanged) |
| neural seed admission inside `correctNeuralSeed` | `V3ColumnCalculator.java:275-278` | REQUEST-ONLY (same validator, on the seed's branch) | `PROPERTY_OUT_OF_RANGE` (unchanged) |
| neural correction terminal failure | `V3ColumnCalculator.java:323-325` | — | `INITIALIZATION_FAILURE` (unchanged) |
| `V3HollandExample32` oracle disagreement | `V3HollandExample32.java:96,100` | — | `NONCONVERGENCE` / `ACCEPTANCE_AUDIT_FAILURE` (unchanged) |

**Structural cross-check that the inventory is complete.** Gates 2, 3 and 4 all return early unless the
request authors net pumparound cooling, and gate 1 needs `sum(draw) >= sum(feed)`. On the 108 typed cases
the maximum draw/feed ratio is **0.584** (so gate 1 cannot fire anywhere) and **all 108 author gross cooling**
(`heatLoopHistogramAmongTyped` = 8/28/29/43 cases with 1/2/3/4 loops, `typedWithoutAuthoredCooling` empty).
R6 independently ported gate 2 and measured a maximum cooling/available ratio of **0.71**, so gate 2 cannot
fire either. Every one of the 108 verdicts therefore comes from gate 3 or gate 4. A typed case with no
authored cooling would have meant a missed publisher; there is none.

## 2. The change

`V3ColumnCalculator.InfeasibleSpecification` is renamed `PathDependentHeatBound` and now composes its message
as `detail + "; " + hint + PATH_DEPENDENT_BOUND_SUFFIX`:

* gate 4 hint — `condensation-capped at tray N on the continuation path`
* gate 3 hint — `cooling above the base condenser duty measured on the continuation path`
* shared suffix — `; this bound reads the last accepted continuation state rather than the request, so the
  specification is not typed infeasible`

The `V3HeatFeasibility` detail builders are untouched, so the GUI still names the same duties, trays and
`Q_cond0` it always did; the summary gains ~190 characters, measured at 330–359 across the 108 verification
cases, inside the 512-character bound. The shorter 256-character diagnostics-event copy is truncated rather
than rejected — see §6 item 2, a latent boundary bug this exposed.
The failure code becomes `NONCONVERGENCE`, which already exists in `V3SolverFailureCode` — **no enum value was
added**. That matters because the code crosses the boundary as a string: `ColumnV3Network.java:218` sends
`failure.code().name()` and `ColumnCalculatorV3BlockEntity.java:124` renders
`failure.code().name() + ": " + failure.summary()`. Nothing serialises the ordinal, and no NBT/wire schema
version is affected.

**Control flow is deliberately held constant.** `V3ColumnCalculator.calculate` (line 423-435) skipped the
alternate condenser-phase branch when the outcome was `INFEASIBLE_SPECIFICATION`. Retyping the two bounds to
`NONCONVERGENCE` would have silently bought a second full cold chain on the other branch — roughly doubling
the cost of every one of these cases and putting the pinned checkpoint budget in
`V3PumparoundCalculatorTest.aStalledDropOnlyFloorRefreshCostsABoundedRepeatOnTheFortyMegawattCase` at risk.
Since the bound is about the authored duty against an already-accepted state, not about the condenser phase,
that retry would re-derive the same stop. The publisher therefore records the fact on the existing
`CondenserAttempts` object (`recordPathDependentHeatBound`, folded into `allowsColdRecovery()`), which
reproduces the old decision exactly. Net behavioural delta: **the published code and the summary text only.**

Not changed: acceptance tolerances, closure, truncation support rules, the neural budget, ramp schedules,
formulation revisions, assumptions revisions and input digests.

## 3. Coverage implication of the neural short-circuit (task step 4)

`V3ColumnCalculator` breaks out of the neural candidate loop (line 240-241) and, in `LNN_FIRST`, returns
`lastFailure` without the classical backup (line 258-259) when a candidate's code is `PROPERTY_OUT_OF_RANGE`
or `INFEASIBLE_SPECIFICATION`.

**Could a state-dependent verdict have reached those branches? No — the path does not exist.** Call-graph
evidence:

* both state-dependent bounds are raised only inside `recoverWithDrawRamp` (`V3ColumnCalculator.java:1292`
  and `:1306`);
* `recoverWithDrawRamp` has exactly three call sites — `:557` (the material-closed fallback inside
  `calculateBranch`), `:740` (`solveStageContinuation`) and `:816` (`solvePressureContinuation`) — all on the
  classical continuation path;
* `correctNeuralSeed` (`:270-326`) calls only `prepareAttempt`, `V3SimultaneousColumnSolver` directly and
  `solveSingleProblem`, and `solveSingleProblem` (`:960`) never calls `recoverWithDrawRamp`.

So `correctNeuralSeed` can only return `PROPERTY_OUT_OF_RANGE` (`:277`), `INITIALIZATION_FAILURE` (`:323`) or
a success. The `|| failure.code() == INFEASIBLE_SPECIFICATION` clauses at 241 and 259 were therefore
**unreachable on the current call graph**: no neural candidate was ever skipped and no classical fallback was
ever suppressed by a path-dependent verdict. The measured 42-of-108 defect is entirely a classical-path
verdict, which is consistent with it having been found by comparing the classical control against
neural-seeded pipelines that solved the same inputs.

Two latent hazards existed anyway, and the change removes both:

1. **Latent escape.** `InfeasibleSpecification` was an unchecked exception, and `correctNeuralSeed`'s caller
   catches only `V3ThermoException | IllegalArgumentException | IllegalStateException` (line 248-249). Had any
   future refactor let the neural correction reach a ramp, the exception would have escaped the public
   `calculate(...)` uncaught instead of producing a typed outcome. The exception is now raised only where it
   is caught, and its name says what it is.
2. **Latent unsoundness of the short-circuit itself.** After the change, `INFEASIBLE_SPECIFICATION` is
   produced by request-only gates *only*. Breaking the candidate loop and skipping the classical backup on it
   is then provably correct: no seed can change a property of the request. Before the change the same two
   lines would have become wrong the moment a state-dependent gate became reachable from the neural path. A
   comment at line 237-240 now pins that invariant.

One real coverage path *was* affected and is intended: in `LNN_FIRST` / neural-backup mode the classical
backup at `:266` runs `calculate(...)`, which can itself hit gate 3 or 4. That outcome is now
`NONCONVERGENCE`, so a caller that treats `INFEASIBLE_SPECIFICATION` as "stop asking" (the in-game block
entity shows it verbatim; any future retry policy would key on it) is no longer told the request is
impossible when it is not.

## 4. Extraction results

From `ids.json` (sources pinned by sha256 inside it):

| quantity | value |
| --- | --- |
| validation population | 405 |
| classical control (`F0`, `modes.current`) typed `INFEASIBLE_SPECIFICATION` | **108** |
| block 1 vs block 2 disagreements on that status | 0 |
| ids with an ambiguous `canonicalInputSha256` across the two journals | 0 |
| of the 108, strictly solved elsewhere — two `validation-case-evidence.jsonl` journals only | 37 |
| of the 108, strictly solved *or* advisory elsewhere — same two journals | 41 |
| of the 108, strictly solved elsewhere — pooled as R6 pooled (adds `tools/neural/generation-comparison-case-map.jsonl` and `build/neural-hybrid-learning/v1/case-map.jsonl`) | **42** |
| of the 108, solvable (strict or advisory) — same pooling | **47** |
| of the 108, never solved by anything | 61 |
| request-only draw gate would fire on | 0 (max draw/feed 0.584) |
| control service time over the 108 (block-averaged) | 93.2 s |

The published "42 / 47" reproduces exactly, and the 61 never-solved remainder matches R6's
`controlStatusOfNeverSolved.INFEASIBLE_SPECIFICATION = 61`. The two extra sources are reported separately
because only the generation map is hash-verified; the hybrid case map carries no input hash and is joined by
id alone.

**Recorded gate text: not available.** Across both journals the only per-case failure evidence kept for a
typed-infeasible case is `failureClass = "NATIVE_SOLVER_ADMISSION_OR_PATH_BOUND"`,
`observedStopPhrases = ["unknown"]` and `newtonIterations = 0` (`terminalFailure` always publishes zero). No
detail string survives, so which of gate 3 and gate 4 fired per case cannot be recovered from the journals.
The verification run records the full summary, and `verify.py compare` reports the split.

## 5. Verification — measured, all three criteria pass

`tools/path-dependent-infeasible/java/V3ClassicalOnlyProbe.java` is a classical-only (`CURRENT_ONLY`)
terminal-status recorder: no model, no neural strategies, no profile capture, so it is not a benchmark and its
timings are not comparable to a campaign. It reuses the campaign's `V3BoundedEvaluation` ten-worker pool, the
30 s per-request deadline and the 4 GiB heap, and refuses any other setting. `verify.py` compiles the
self-contained V3 package with **Gson as the only classpath entry**, so no stale project bytecode can satisfy
a dependency, then runs the probe over exactly the 108 ids in `inputs-108.jsonl` and writes
`verification.json`.

```
python tools/path-dependent-infeasible/verify.py run     --label changed
python tools/path-dependent-infeasible/verify.py compare --label changed
```

Executed with the machine otherwise idle: 105 sources compiled, 108/108 cases completed on 10 distinct worker
threads, 11.87 s wall (the archived control spent 93.2 s of block-averaged single-case service time on the same
108). Results are in `verification.json`.

| criterion | result |
| --- | --- |
| 1 — no id still `INFEASIBLE_SPECIFICATION` | **pass**; `stillInfeasible` empty, so all **42** strictly-solved-elsewhere ids are cleared, and so are the other 66 |
| 2 — every retyped case carries the hint | **pass**; 108/108 retyped, `missingHint` empty |
| 3 — nothing else changed | **pass**; `unexpectedTransitions` empty, `newlyAccepted` empty |

Terminal status histogram over the 108: `{NONCONVERGENCE: 108}`. Every case took the single permitted
transition `INFEASIBLE_SPECIFICATION → NONCONVERGENCE`; nothing became `ACCEPTED`, `DEADLINE_EXCEEDED` or any
other code, which is what §2's "control flow held constant" predicts.

### Measured gate split (first direct measurement)

The journals never kept a detail string, so which of the two state-dependent gates fired was unknown until
this run. Of the 108:

| gate | cases | solve paths |
| --- | ---: | --- |
| 4 — condensation cap (`condensationCappedTray`) | **83** | `cold/heat-cap/heat-{1,2,3,4}` = 8 / 25 / 25 / 25 |
| 3 — base condenser duty (`requireCoolingBelowBaseCondenserDuty`) | **25** | `cold/heat-condenser-bound/heat-{2,3,4}` = 3 / 4 / 18 |

So the condensation cap, not the condenser bound, is the dominant source of the defect — roughly 3:1. The
condenser bound concentrates on four-loop requests (18 of its 25).

Published summaries are 330–359 characters, inside the 512-character contract; the diagnostics event copy is
truncated at exactly 256 (see §6 item 2). Two examples, verbatim:

```
V3 pumparound cooling of 0.8508 MW on tray 3 exceeds the 0.8260 MW its arriving vapor can release even at
the smallest permitted continuation increment; condensation-capped at tray 3 on the continuation path; this
bound reads the last accepted continuation state rather than the request, so the specification is not typed
infeasible

V3 authored pumparound cooling of 42.70 MW is not below the 34.55 MW base condenser duty Q_cond0 of the same
column without stage heat; the overhead would vanish; cooling above the base condenser duty measured on the
continuation path; this bound reads the last accepted continuation state rather than the request, so the
specification is not typed infeasible
```

**Caveat on criterion 3.** The optional measured baseline (`run --label baseline` from a checkout of the parent
commit) was deliberately skipped; the archived journals are the baseline. Those record status only, so
criterion 3 is a status-level comparison, not a detail-level one. What it establishes is that no case moved to
a *different* failure code and none became `ACCEPTED`. That no strict outcome changed elsewhere in the 405-case
population rests on the control-flow argument in §2 plus the full unit suite, not on this run.

**No case behaved unexpectedly.** Every one of the 108 was retyped, hinted and otherwise unchanged.

The request-only leg of the brief's expectation ("the request-only-typed ids are unchanged") is **vacuous on
this population** — no case in the 405 reaches a request-only gate. It is covered instead by the unit test in
`V3PathDependentInfeasibilityTest`, which pins both request-only gates at `INFEASIBLE_SPECIFICATION` with zero
Newton iterations and without the path-dependence suffix.

### Test suite

`.\gradlew.bat --offline test --console=plain`: **564 tests, 1 failure** —
`V3SideDrawCodecTest.malformedAndOversizedListsAreRejectedBeforeAllocationOrSilentMigration`, which asserts a
Netty `DecoderException` and observes `IndexOutOfBoundsException`. Pre-existing and untouched by this change:
it is a network-codec test, this commit's parent *is* `c2cab76` where the failure was already observed, and
nothing in the diff reaches `ColumnV3Network`. All three new tests in `V3PathDependentInfeasibilityTest` pass
(state-dependent gate reached in 0.234 s), as do both retyped tests and the rest of
`V3Pumparound{,Steam}CalculatorTest` (13 and 7 passing).

## 6. Open items and incidental findings

1. Everything in §5 is measured. The change is compiled, tested and verified.
2. **Latent defect found and fixed while verifying** (`V3ColumnCalculator.terminalFailure`). A failure summary
   and an audit check detail are bounded at 512 characters, but a `V3SolverDiagnostics` event is bounded at
   256, and `terminalFailure` passed the summary straight through as its single event. Any typed detail longer
   than 256 characters therefore threw `IllegalArgumentException` **out of the public `calculate(...)`** instead
   of producing a typed outcome — it was simply unreachable while every detail happened to be short. The longer
   path-dependent summaries made it reachable, and the first suite run failed with exactly that on all three
   affected tests. Fixed by truncating the event copy with the existing `boundedEvent` helper; the summary and
   the audit detail keep their full 512 characters. This is a real pre-existing bug in a boundary contract, not
   a consequence of the retyping.
3. The 61 never-solved ids keep a terminal outcome that is now `NONCONVERGENCE` rather than
   `INFEASIBLE_SPECIFICATION`. That is the honest type — V3 has no proof they are infeasible — but it removes
   the only signal a future screen could have reused. R6's recommended request-only liquid-supply screen
   (`rho >= 0.30`, zero false positives on 474 protected cases across two populations) is the intended
   replacement and is independent of this change.
5. **The base branch is not src-identical to main, contrary to the task brief.**
   `codex/v4-transformer-investigation` carries the whole neural-initializer stack: 42 src files differ from
   main, and `V3ColumnCalculator.java` alone is +210/-4. Everything this change touches is byte-identical to
   main *except* the neural candidate loop, which does not exist on main at all (no `correctNeuralSeed`, no
   break on `INFEASIBLE_SPECIFICATION`; main's copies of the catch site, `CondenserAttempts`, both throw sites
   and the exception class match verbatim at lines 464, 1010, 1088, 1570 and 2426). So a cherry-pick onto main
   carries the whole fix and drops exactly one hunk — the four-line comment at branch lines 237-240 — and §3's
   coverage question is branch-only: on main there is no neural short-circuit to suppress anything.
6. The condensation-cap gate (4) subdivides the heat ramp before giving up (`:1297-1304`). A subdivision
   budget that is exhausted is also a path property; the retyping covers it, but the gate would be a better
   citizen if it reported how much of the ramp it did reach.

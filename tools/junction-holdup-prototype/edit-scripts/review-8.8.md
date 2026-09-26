
### 8.8 Events under long BE slices (runs 100+)

Run 2026-09-26 in the same worktree at 9674bf1 by Claude (Opus 5.5), on the owner's acceptance of backward Euler at the product's 5 s cycle (the D+E configuration of 8.7) and his question: does a long backward-Euler slice make an event mechanism *break* (missed event, wrong active set held through a step, a state past a physical bound, a domain violation, nonconvergence) rather than merely *delay*? The planned pressure safety valves (PSV) and tank bursting, and the existing line and filter blockage, may all react one slice late.
- *Code.* `holdup-prototype-be-fast.patch` plus the fixes and switches below; the full diff is `tools/junction-holdup-prototype/holdup-prototype-events.patch`, fourteen tracked files (the thirteen of be-fast plus `network/SolidEventIntegrator`). It contains every earlier patch; apply it alone. `--check --reverse` on the patched tree and `--check` on the restored base pass; `git status --short` shows nothing tracked.
- *Conditions.* Gradle calls one after another, no dev client, nothing committed, no tracked test modified, `checkConservation` and the reconstruction gate unchanged.
- *Configuration.* D+E = `-PcapForm=B2 -PrateForm=frozen -PrateWarmStart=fresh -PvoidStart=history2 -PmintHoldup=on -PstageForm=implicit '-PholdupTau=0.05' -PpinMass=on -PstageClip=on -Pintegrator=be -PbeModeRule=off -PbeColdStart=rate '-PbeStateCap=0.05' -PphaseCheck=once -PlowAlloc=on`. Every log's first line is its command (`edit-scripts/run-probe.ps1`). Reference = the same code at 0.1 s slices.
- *Probe.* `JunctionEventProbe` (README): the smallest island per mechanism, integrated in consecutive 0.1 s and 5 s slices, each slice started at the previous slice's `nextStepEstimate()` as `RetainedSolver` does, with each event switch off and on. One line per slice end with modes, average and endpoint flows, endpoint `|q|/cap`, component ledger and node states. Timing `ms` is order-dependent (JIT) and only ranks; counters are exact.

#### (a) The event hooks under backward Euler (code reading, runs 100a, 100)

- *The hooks.* The TR-BDF2 path calls the stage guard's `checkRate` on its t0 endpoint rate, `checkFilters` on both stages and `check` on the corrected stage. The backward-Euler path (`runBackwardEuler`) has no stages; it calls `checkRate` at every step start (on the previous accepted endpoint, else on the endpoint carried across the call while no connection's identity or closure changed, else, for the solid event integrator's guard, on one port-graph rate solve) and `checkFilters` on every step end. The transition search (`transitionFloor = max(1e-6 s, 1e-9 duration)`, 40 transition rejections, `atStart` declared at once) is the TR-BDF2 path's, line for line. **No hook the TR-BDF2 path calls is skipped.**
- *Why the clog was never declared (run 100a, `-PbeTrace=true`).* The saturated inlet law lands the cake a hair below capacity (24.999999999975 kg, loading 1 - 1e-12) on the step that would have overfilled it. A t0 guard of the *next step of the same interval* declares it (`atCapacity`, relative 1e-9). Under backward Euler a 5 s interval is often a single step (the clog probe: one accepted step per slice from slice 2 on), so the landing is the interval's last step and the declaration comes from the *next interval's* start-of-interval closure pass in `SolidEventIntegrator.solve`. That pass blocks the filter both ways but, unlike a declared transition, never calls `stopFilter`: 34 `BEPRECHECK ... FILTER_CLOGGED filterStopped=false` lines at t = 0, no `BETRANSITION`. The filter carried nothing after the landing (blocked every interval), but `stoppedAtCapacity` stayed false: no "filter clogged" status, no stopped flag in the checkpoint, and the test's `clogged()` false. The same path exists under TR-BDF2 whenever a landing falls on an interval's last step.
- *Fix (part of `be`, no switch):* under `be` that pass stops the filter on `FILTER_CLOGGED`. Run 100: `FullTankSolidsEventTest` 4/4, `FilterBlockLineIslandTest` 11/11.
- *PumpRiseScaling (run 100a).* The typed cause was not lost at the 8.7 configuration: all 70 rejections of the chain are domain refusals (31 line-search stalls, then iteration limits, each with the violation attached), and `failure.domainViolation()` is Nitrogen / TEMPERATURE_BELOW / node 1 (the test's `assertNotNull` passed in 99j). The chain approaches the boundary Zeno-like (t = 0.0338 of 0.05 s) and ends on twenty consecutive rejections, whose message ("Substep refinement exhausted: ...") did not carry the rejection map; TR-BDF2 ended on its 1024-attempt cap, whose message does. *Fix (part of `be`):* the backward-Euler exhaustion message appends `; reasons=<map>`. Run 100: 5/5.

#### (b) Mechanisms at the 5 s cadence (runs 101-108)

| mechanism (case) | declared: 5 s slices vs 0.1 s reference | accepted state at the end of the event slice | class | evidence |
|---|---|---|---|---|
| 1. Filter clog (`clog`: 300 kPa, 5 % 100 um, filter to void) | lands on the last step of slice 8 (40 s), declared at slice 9's t0; reference lands in 31.3-31.4 s, declared at 31.4 s (the cake grows more slowly on 5 s steps: 23.39 against 24.59 kg at 30 s) | cake 24.999999999975 kg (capacity 25 kg less 1e-12); every flow 0 from slice 9 on, blocked both ways; `stoppedAtCapacity` true only with the fix | **DELAYED** (one slice, plus 8.6 s of backward-Euler lag in the cake's growth); stopped flag **FIXED** | 100a, 103 |
| 2a. Passive line whose downstream closes (`passiveFill`: 400 kPa into a tank) | line CLOSED from slice 9 (45 s); reference 28.9 s. The tank is within 0.1 Pa of the generator at 35 s | tank at most 400000.000 Pa (the generator), liquid 0.745 of the volume | **DELAYED** (the asymptotic approach closes later, no overshoot) | 103 |
| 2b. Pump into a dead-headed tank (`pumpFill`, flat) | head limit in slice 17, CLOSED in slice 18 (85-90 s); reference 82.5 s and 84.2 s | tank 601324.947 Pa against the shutoff 101325 + 500000 = 601325 Pa | **DELAYED** (one slice) | 103 |
| 2c. Gas pump between closed tanks (`gasPump`) | CLOSED in slice 1; reference 0.4 s | dP 572.99 Pa against the limit 573.81 Pa | **DELAYED** | 103 |
| 2d. Elevated pumped line (`risingPump`: ElevatedBlockLineIsland's vertical pump, tank 4 m up) | CLOSED in slice 18 at 848734.80 Pa and **held there for the remaining 110 s**; reference CLOSED at 88.9 s at 861063.055 Pa | 12.33 kPa below the pump's own shutoff, for good | **BROKEN** (a wrong active set held forever); **FIXED** by `pumpReopenStart=limit`: head limit at 90 s (861054.29 Pa), CLOSED in slice 20 at 861063.075 Pa (0.02 Pa from the reference) | 106, 107, 108 |
| 3. One-way boundary shut and reopened (transient 0.05:4:true, 5 s, hint: injection starting at rest) | void edges closed at t0 by `closeIllegalStarts` for the whole 3 s injection step | tanks 104128.43 / 104125.80 Pa at 13 s against 101330.94 / 101326.34 | **BROKEN** (bottling: wrong active set held through the step, 2797 Pa); **FIXED** by `boundaryReopen=on`: 101330.93 / 101326.34 (-0.013 / -0.004 Pa) | 97c, 101 |
| 3'. Dead-head closure spanning a step (`withdrawReopen`: dead-headed line at rest 10 s, then 0.3 kg/s withdrawn from the tank) | `closeDeadHeads` holds the generator line shut through each step that starts above the generator | tank 132980 Pa at 15 s against 135581 Pa; 146.12 kg at 25 s against 135.39 kg (+7.9 %) | **BROKEN** (the same class; bounded, it re-decides every step); **FIXED** by `boundaryReopen=on`: 135632 Pa (+51 Pa), 134.35 kg (-0.8 %) | 103 |
| 4. Velocity cap engaging and releasing (`blowdown`: 300 kPa 400 K tank to a void, 50 mm; transient 50 mm cases) | reference on the cap until 3-4 s, CLOSED at 4.7 s; 5 s: slice 1 (31 steps) ends at rest | largest endpoint flow over its cap, over every run, 1.000000001 (0.1 s, inside the 2e-8 closure), 0.72 at 5 s slice ends; **no** "Velocity constraint did not close" in any probe of runs 101-110 | **DELAYED** (nothing to see) | 101-103 |
| 5. Tank filling to full (`pumpFill`, `passiveFill`, `flush`: 200 kPa water through a tank to a void) | `flush` liquid fraction 0.9 at 62.8 s against 60.2 s; 0.999 at 220.1 s against 206.6 s | liquid at most 0.999945 of the volume; no nonconvergence, no held slice; ledger at most 4.9e-10 relative | **DELAYED** | 103 |
| 6. Pressure threshold (PSV / burst proxy; `ramp`: 5 mol/s nitrogen into two 1 m3 tanks, 8.68 kPa/s) | 150 kPa crossed at 5.597 s in both runs, first seen at the slice end t = 10 s; 300 kPa crossed at 22.859 s, seen at 25 s | 188227.6 Pa (+38.2 kPa, 4.40 s late); 318620.8 Pa (+18.6 kPa, 2.14 s late). 0.1 s: +24.6 Pa and +352.6 Pa | **DELAYED** (by construction at most one slice; see (e)) | 103 |
| 6'. The same on the pumped fill | 150 kPa at 30.94 s seen at 35 s; 300 kPa at 65.78 s seen at 70 s; 600 kPa at 84.93 s seen at 85 s | +9.0 kPa, +40.6 kPa, +1.29 kPa (the last capped by the pump's shutoff) | **DELAYED** | 103 |
| 7a. Domain floor (`domainFloor`: PumpRiseScaling's 298.05 K nitrogen floor) | slice 1 held at both cadences | typed hold, Nitrogen TEMPERATURE_BELOW node 1, 71 / 73 domain rejections, message with the map | **DELAYED** (clean) | 103 |
| 7b. Domain ceiling (`domainHot`: 1 mol/s nitrogen plus 20 kW, towards 900 K) | slice 12 held (t0 55 s); reference slice 540 (t0 53.9 s). 899 K crossed at 54.85 s against 53.72 s | last accepted T 899.68 K; typed hold Nitrogen TEMPERATURE_ABOVE node 1; the refused slice spends the 1024-attempt cap (487 accepted and 537 refused micro-steps; the whole 12-slice run took 346 ms) | **DELAYED** (one slice later, clean) | 103 |
| 7c. Water's triple-point floor met by venting (run 101's first `drainReopen`: a 298 K nitrogen tank at 300 kPa vented next to a water line) | held at t = 1.5 s at 0.1 s and inside slice 1 at 5 s | typed hold Water TEMPERATURE_BELOW 273.16 K, node 4, twenty domain rejections | **DELAYED** (clean; the fixture was then moved to 400 K) | 101 |

Every held slice of these runs was a typed domain hold; no case met a Newton nonconvergence. Ledger at most 4.9e-10 relative over every run, 4.4e-11 at 5 s.

#### (c) The two BROKEN classes and their fixes

- **One-way closure at t0 held through a step (`junction.boundaryReopen`).** `closeDeadHeads` and `closeIllegalStarts` decide a closure on the solve's *start* point, per solve. The pass loop never reopens a boundary closure within a solve, and backward Euler solves each step once. So a step that starts closed and should open part-way (an injection into tanks at rest, a withdrawal from a dead-headed tank) is solved with the connection shut throughout: the tanks bottle up or starve. It needs one step to span the event, which is what a rest period grows the steps to; the `drainReopen` case, whose event comes while the steps are still short, opens inside slice 1 either way.
  - *The rule.* A converged step whose accepted modes hold a passive run closed (not one blocked both ways before the solve) although the step's own end states drive the run in a direction every link allows by more than `max(1 Pa, 1e-6 P)` is refused; the same step is solved again with that run exempt from the two start closures. The run is the maximal chain of closed passive connections through degree-two junctions no actuator touches (the run `closeDeadHeads` decides on). The forward head is taken on the first end's density and the reverse head on the second's. A junction end counts only while one of its connections is open. Each run is reopened at most once per attempted step; the pass loop's own `illegalDirection` closure of a reopened run stands.
  - *The retry mechanics.* `boundaryClosed` is built per solve, so a retry is a new solve with a different start closure. Only the active set changes; the step does not.
  - *Measured.* Transient 50 mm at 13 s: the largest tank difference to the 0.1 s run falls from 2799.5 Pa to 1.3 Pa at 5 s with the hint; 20 mm unchanged (117.1 Pa, the lag of 8.7). At 0.1 s every tank pressure is within 0.05 Pa of 97g.
  - *Cost.*

    | case | cadence | reopens | accepted / rejected, off to on | Newton solves | ms off to on |
    |---|---|---|---|---|---|
    | transient, 12 cases (97c to 101) | 5 s, hint | 2 | 232 / 8 to 232 / 10 | 272 to 274 | 342 to 333 |
    | transient, 12 cases (97g to 102) | 0.1 s | 3 | 1922 / 2 to 1922 / 5 | 2158 to 2161 | 837 to 1142 (JIT order: 102 ran no static probe first) |
    | `withdrawReopen` (103) | 5 s | 19 | 145 / 13 to 156 / 46 | - | 32 to 103 |
    | `drainReopen` (103) | 5 s | 19 | 176 / 12 to 187 / 43 | - | 51 to 93 |
    | `flush` (103) | 5 s | 3 | 238 / 11 to 239 / 15 | - | 30 to 30 |

    Every other case: 0 reopens and the same line. The reopened solves of the two water-into-gas cases fail their Newton 17 times (line-search stalls from a zero start flow across the liquid/gas friction kink) before halving converges; that is the cost, not an unconverged result.
- **Pump head limit re-closed at every step (`junction.pumpReopenStart`, run 107 trace).**
  - Once the pump of `risingPump` has closed, the next solve's carry test sees +12328 Pa of margin and offers the head limit back. The pass then starts from the closed point: zero flow and the closed pump's head, 484406 Pa.
  - `Equations.headDensities` decides the pump row's column on that start point. From that head it picks the tank's bulk density (879 kg/m3, gas headspace included) instead of the water column (996 kg/m3).
  - The converged end state therefore sits 3423 Pa past the water-column shutoff, so the pass closes the pump again, and the closed re-solve leaves the tank where it was.
  - At 0.1 s the same code lands at 861063.055 Pa: *inferred*, the flow at each short step end keeps the margin positive until the landing. At 5 s every step ends past it, so this is a fixed point.
  - *Fix:* a pump whose previous mode was CLOSED and which a pass runs on its head limit starts from the limit's own flow and head (`headLimitMassFlow`), as a pump arriving from the target branch already does.
  - *Measured:* 5 s landing 861063.075 Pa (0.1 s: 861063.055); the 0.1 s runs, `pumpFill` and `gasPump` unchanged; ElevatedBlockLineIsland now fails only on the backward-Euler landing, +144.77 Pa against a 90 Pa tolerance (see (d)).
  - *Not fixed:* the column-density inconsistency itself. The shutoff test states the column on the suction density, and the row states it on whichever end `headDensities` picks. It explains the +144.8 Pa, and every vertical pumped line is exposed to it.

#### (d) The 8.6 unexplained failures, under the owner's lens

| 8.6 failure | now | class |
|---|---|---|
| FilterBlock pumped landing, -2.24 Pa (cap 0.02) | passes at cap 0.05 (99j, 105, 110); not re-run at 0.02 | DELAYED (landing accuracy at 0.02) |
| Elevated pumped landing, +143.65 Pa (cap 0.02); 848734.80 Pa at cap 0.05 (8.7) | at cap 0.05 a pump re-closed at every step, 12.3 kPa short for good (106/107); with `pumpReopenStart=limit` 861063.07 Pa, which is the 0.1 s backward-Euler landing to 0.02 Pa and +144.77 Pa from the test's TR-BDF2-derived 860918.31 | **BROKEN, FIXED**; the residual is the landing accuracy of (c) |
| FullTankSolidsEvent, cake at 24.999999999975 kg, no clog declared | the interval-start pass declared it and did not stop the filter | **FIXED** (run 100, 4/4) |
| PumpRiseScaling, "domain cause lost" | the typed cause was present at the 8.7 configuration; the message lacked the rejection map | **FIXED** (run 100, 5/5) |
| CausalModuleCoordinator, last-ulp digests of certified against every-interval islands | message unchanged in 105 and 110; not an event mechanism, not traced here | open (bitwise replay; 8.6 (g) item 4 stands) |

#### (e) Gates

| run | configuration | 7 gate classes (38) | fluid suites | against 99j (386/402, 16 failures) |
|---|---|---|---|---|
| 104 / 105 | D+E + the two `be` fixes + `boundaryReopen=on` | 36/38 | 389/403 (the 399 suite tests plus four probe wrappers, the new `JunctionEventProbe` among them) | FullTankSolidsEvent and PumpRiseScaling now pass; the other 14 messages equal, to 5000 characters |
| 109 / 110 | + `pumpReopenStart=limit` | 36/38 | 389/403 | as 105, except ElevatedBlockLineIsland: 848734.80 to 861063.07 Pa (expected 860918.31 +- 90) |
| 111 | defaults, no init script | - | **399/399** in 91 classes, 0 skipped | - |

The fourteen remaining failures and their classes:
- NetworkRegime x2 and SolidChainTransport x2: intended (they call TR-BDF2 directly or count its structure).
- Accuracy, first order, **DELAYED**: PassiveTimeRefinement, TransientQualification x3, CadenceTrajectory, ScheduledTransferEstimator and McpGameplayRegression. SolidClosureFeasibility belongs here too: the deposition closure is located at 0.0125 against the tight 0.0055, i.e. later.
- ElevatedBlockLineIsland: the landing residual of (c).
- CausalModuleCoordinator: open.

#### (f) What the PSV and burst mechanisms may assume

**What the accepted state at a slice end guarantees (measured over runs 100-111):**
- *Conservation:* species, solids and energy exactly (reconstruction plus `checkConservation`; ledger at most 4.9e-10 relative).
- *Per-step change:* no tank's pressure or mass moves more than 5 % of itself in one accepted step, except an algebraic jump (a change that halving does not reduce) or a step at the transition floor.
- *Physical bounds, all held:*
  - a pump's discharge stays at or below its shutoff (601324.947 against 601325 Pa);
  - a passive fill stays at or below its source (400000.000 Pa);
  - a filter cake stays at capacity less 1e-12;
  - liquid stays inside the tank (by the flash);
  - every endpoint flow stays within its velocity cap to 2e-8.
- *Domain:* every accepted state is inside the material domain. A crossing is a typed hold of the *whole* slice with no partial commit, so the state a check at the slice end sees is the last one inside the domain (899.68 K against 900 K).

**What it does not guarantee:**
- *Timing.* An event that happens inside a slice is seen at the slice end.
- *Overshoot.* A threshold that is not a model bound (a PSV set point, a burst rating) is overshot at the slice end by up to (rate of rise) x (slice length). Measured: 38.2 kPa past 150 kPa and 18.6 kPa past 300 kPa on an 8.68 kPa/s ramp, 40.6 kPa past 300 kPa on the pumped fill; with the 0.1 s slices, 24.6 and 352.6 Pa. The 5 % state cap bounds each step (about 1 s, five steps per slice on the ramp) and not the slice: 38.2 kPa is 25 % of the threshold. The crossing time can be interpolated between two slice ends; on the linear ramp both cadences give 5.597 s.
- *Accuracy.* Slow approaches lag at backward-Euler accuracy: 8.6 s late on the cake growth; 1.1 s late on the 900 K approach.

**Design consequences:**
- *As a slice-end check* (the owner's "one cycle late"): PSV and burst read the accepted state and act as queued events in the next slice. The tank may sit above the set point for one slice by up to rate x 5 s. A burst rating needs that margin, or the check must use the interpolated crossing and the rate.
- *As a device inside the solver* (a relief valve's mode): it inherits the active-set rule of this section. A mode decided on a step's end state is applied to the whole step, and nothing re-examines it within the step.
  - An opening decision is safe: it is the direction `boundaryReopen` and `pumpReopenStart` restore.
  - A closing decision taken on the end state of a long step can hold the wrong mode (2d above). A relief device that closes (reseats) needs the same audit: its start point, and whether its closed re-solve's own end state would reopen it.
- *Burst as a topology event* (destroy the tank, vent it, rebuild the island) belongs with the existing configuration events: applied at an aligned slice boundary, one slice after the state crossed the rating.

#### (g) Open

1. The pump column-density inconsistency of (c): the shutoff test states the column on the suction density, the hydraulic row on the end `headDensities` picks. It explains ElevatedBlockLineIsland's +144.8 Pa and was the root of the 5 s fixed point.
2. The reopened solves' Newton failures (17 line-search stalls per water-into-gas event). A reopened run could start from its allowed-direction flow instead of zero.
3. The domain hold spends the 1024-attempt cap per refused slice (the 12-slice `domainHot` run at 5 s took 346 ms, against 9 ms for the 12-slice ramp). It is parked afterwards (wait-for-inputs), but the first refusal costs that.
4. The two `be` fixes are part of the basis; the two switches are off by default. A production version decides whether the reopen rule and the pump reopen start belong to the basis.
5. `CausalModuleCoordinator`'s last-ulp replay difference (8.6 (g) item 4) is unchanged.
6. *Documentation index.* The main checkout's `documentation/INDEX.md` and the batch's copy there are not updated; no main-checkout edits in this brief.

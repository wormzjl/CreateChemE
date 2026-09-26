# Plan: backward-Euler transient basis and owned junction holdup, production version

Batch documentation/2026-09-24-mixed-gas-junction. Written 2026-09-26 by Claude (Opus 5.5) from HANDOFF_REVIEW.md sections 6-8.9 (the investigation) on the owner's instruction to start the productization. Base for the work: main 9674bf1 (0.5.0); the prototype is `tools/junction-holdup-prototype/holdup-prototype-replay.patch` (16 tracked files, applies alone). Status: Implemented on branch 2026-09-26, awaiting merge (claude/charming-elion-1170cc, not pushed; merge steps in review 9.5 (g)). History: Planned 2026-09-26; WP0 done 2026-09-26 (384/403 and 399/399 reproduced, review 9.0); WP1 done 2026-09-26, stages 1-4 committed on claude/charming-elion-1170cc (3dba4b0, 640d87b, b6422e3, 618ea63; not merged, not pushed; review 9.1); WP2 done 2026-09-26 (5b708fa, checkpoint format 5, review 9.2); WP3 done 2026-09-26 (daa1ccc, review 9.3) except the liquid sizing, which waits for the owner (DECISION_LOG D14); WP4 done 2026-09-26 (review 9.4: chain-100 re-recorded and bitwise on re-run, 30/30 fluid GameTests, in-game check partial with the mixed-gas island not built, D16 pending); WP5 done 2026-09-26 (`b5ed407`: prototype cleanup, CHANGELOG entry, final gates identical to before: 406/406, exact 0.000e+00, 38/38, junction tests 5/5 at 286 Newton solves; review 9.5).

## 1. Objective and acceptance bar

Make mixed-gas multiport junctions, and every fluid island, solve with **zero nonconvergence** at the product's 5 s cycle, at the cost measured in review 8.7-8.9, with exact conservation and bitwise certificate replay. Owner requirements (2026-09-25/26): no nonconvergence may remain; CPU and memory must be low; events may be seen one slice late; trajectory accuracy at the 5 s cycle is accepted as measured (8.7: the approach to rest lags by up to one cycle).

Acceptance at merge:
- Static 32-case matrix 32/32 and the 12 transients 12/12 at 0.1 s, 1 s and 5 s cadence, ledger at roundoff (8.6-8.9 numbers), on the assertion-based versions of the probes.
- The fluid suites and GameTests green with the adjusted assertions of section 5; the exact regression re-recorded and bitwise on re-run; the 38 adjacent tests green.
- Cost not worse than review 8.9 run 115 (5 s cadence: 344 ms for 12 cases x 16 s, 281 Newton solves, 134 MB allocated; retained 50 KB per 5-node island), measured with the same counters.
- Certified replay bitwise (CausalModuleCoordinatorTest, IslandCertificateTest).

## 2. What becomes the basis (switch collapse)

Every prototype switch below becomes the only behaviour; the switch, its Gradle property and its `System.getProperty` read are removed. The prototype's `junction.*` and `createcheme.junctionHoldup` properties do not survive.

| Prototype switch | Production form | Review |
|---|---|---|
| `junction.integrator=be` | `PassiveIntervalSolver` steps with `PassiveStepSolver.solve(graph, h)` directly; `TrBdf2StepSolver`, its endpoint-rate cache, the companion estimate and the second (`algebraic`) solver instance are removed; the stage-guard hooks stay on the BE path (8.8 confirmed none is skipped) | 8.4, 8.6, 8.8 |
| `beStateCap=0.05`, `beModeRule=off`, `beColdStart=rate` | the state-change controller: accept a converged step if no tank's pressure or mass changed by more than 5 %; growth `clamp(0.9*sqrt(cap/change), 0.5, 2)`; halve on a Newton failure; one cold rate solve at a cold start; no one-transition rule | 8.6, 8.7 |
| balanced pressure seed (not behind a switch in the prototype) | kept, cold start only (compile, topology change, structure change) | 6.1, 7.4 |
| `mintHoldup=on`, `holdupTau=0.05`, `pinMass=on` | every junction owns a holdup m_J = tau * max cap flow of its connections at the seed, tau = 0.05 s, in enthalpy form; the reconstruction pins the mass exactly by outflow re-booking; the mint is booked as construction/destruction in the topology ledger | 7.6, 7.8, 7.10 |
| `rateForm`, `stageForm`, `stageClip`, `fluxForm`, `flowFloor`, `rateWarmStart` | removed with TR-BDF2 (BE has no rate stage and a non-negative base); `rateWarmStart=fresh` survives only as the cold rate solve's start rule | 7.6-7.11, 8.6 |
| `capForm=B2` | the velocity-cap row in one scale, `(sign(d)*min(|d|, limitDrop) - loss)/min(pressureScale, 2*limitDrop)`, skipped on an edge into a dead-ended junction | 7.1, 7.5, 7.11, 8.9 |
| `voidStart=history2`, `boundaryReopen=on` | start-of-solve closure of passive runs whose start direction their boundary forbids (only after a solve was accepted on the same structure, and only if the static head has the forbidden sign with either endpoint's density), and the reopen rule: a converged step holding a closed passive run that its own end states would open is refused and retried open | 7.8, 7.11, 8.8 |
| `pumpReopenStart=limit`, `pumpColumn=suction` | a pump reopened from CLOSED starts from its head-limit flow and head; every pump column and the rise limit read the suction's transported density (owner rule 2026-09-26) | 8.8, 8.9 |
| `phaseCheck=once`, `lowAlloc=on` | the reconstruction does not re-check phases the Newton already checked; the flash's equilibrium loop and prepared workspace reuse buffers; decode caches and cold-start graphs are released after a step | 8.7 |
| `replayDeterministic=on` | at job dispatch the retained solver drops numeric factorizations and warm starts and restates mode carries from the committed interval; an interval starts at `min(initialStep, duration)` | 8.9 |
| `rowForm=amount` | the owned junction's mixing and enthalpy rows in amount form (bounded per-step error tol * m_J instead of tol * dt); removes the 1/dt rounding floor of 8.3 class 4 (SolidRuntime) | 8.7 lever C |

Prototype shortcuts to remove in the same pass: m_J carried in the inventory's volume field; `JUNCTION_HOLDUP = Double.MIN_VALUE` as an on/off flag; the probe-only `mintLine`; every `*Trace` print. Newton tolerance, the 1e-8 gate, and the conservation checks are unchanged (8.7: relaxing the tolerance buys nothing and breaks the certificates).

## 3. Model changes that need a decision recorded (DECISION_LOG.md)

D1 BE basis for transients (8.4-8.6). D2 tau = 0.05 s holdup sizing, not throughput sizing (7.10: dt_max is 20 s). D3 suction density only (owner 2026-09-26). D4 one-slice event delay accepted (owner 2026-09-26). D5 REST and STEADY merged into one certificate kind (owner 2026-09-26; section 4). D6 test assertions adjusted by intent (section 5). D7 TR-BDF2 removed rather than kept behind a switch (tooling cleanup rule: no switch that only serves a superseded path). D8 liquid holdup sizing uses a liquid velocity limit (section 6.3) - not implemented in WP3: the model exposes no liquid velocity limit; measured and presented as options, pending owner decision (DECISION_LOG D14, review 9.3 (d)).

## 4. Certificate merge

`IslandCertificate.Kind {REST, STEADY}` becomes one kind carrying the per-interval change `d`. Issue rule: as today's STEADY (`issue`, IslandCertificate.java:266-286) with the horizon `Long.MAX_VALUE` (or the recheck period) when every change, flow, transfer and pump work is exactly zero, else `min(K_max, budget/d, zero crossing)` intervals; the solids-in-transport and filter refusals apply only when something moves; `grossWithoutNet` stays a refusal. `graphAt` is unchanged (it already treats zero deltas as identity). Consumers: the advance label (RESTED/REPLAYED collapses to one), the status string, the checkpoint index kind byte (format change, fresh world, no migration per AGENTS.md). Fixtures: `IslandCertificateTest.aSettledClosedPair...`, `FluidCheckpointFormatTest` x2 assert the merged kind; the two tests that ran to the `Long.MAX_VALUE` horizon (`FluidCheckpointFormatTest.aCertifiedPayloadIsCopied...`, `PresentationBucketTest.aPresentationReadStopsShortOfAWake...`) get a finite horizon in their rig.

## 5. Test adjustments by intent (no test is loosened for a defect)

| Test | Today's assertion | Change | Reason |
|---|---|---|---|
| NetworkRegimeTest x2 | instantaneous ideal mixing to 1e-8; no nitrogen to 1e-9 | assert the holdup lag (junction fraction between the initial and the inflow mixture, converging with time constant m_J/Q) | D1/D2 |
| SolidChainTransportTest x2 | TR-BDF2 substep count, endpoint-rate reuse counter | assert the BE counters (steps, solves) | D7 |
| PassiveTimeRefinementTest, TransientQualificationTest x3, CadenceTest, ScheduledTransferEstimatorTest, SolidClosureFeasibilityTest | second-order trajectory accuracy / exact event location | re-baseline to the BE trajectory at the cadence they use; event location one slice late | D1/D4 |
| RetainedSolverTest | "carried preconditioner 2 vs fresh 1" | assert no numeric carry across jobs | 8.9 |
| ElevatedBlockLineIslandTest | 860918.31 +- 90 Pa | 861063 +- 90 Pa (suction-scaled shutoff) | D3 |
| McpGameplayRegressionTest.belowSeaLevel | 203.419 kg | re-baseline (accuracy at cap 0.05) | D1 |
| IslandCertificateTest closed pair, FluidCheckpointFormatTest x2 | STEADY | merged kind, drift 0 | D5 |
| FluidCheckpointFormatTest.aCertifiedPayloadIsCopied..., PresentationBucketTest.aPresentationReadStopsShortOfAWake... (added in WP2) | policy recheck 0 (Long.MAX_VALUE horizon, never ends); fourth save 2 encoded / 1 reused, totals 6/6 | recheck 25 s / 15 s; fourth save 3 / 0, totals 7/5 (the dead-headed line revalidates too) | D5 |
| Kind, status and counter consumers (added in WP2): IslandCertificateTest x6, FluidCheckpointFormatTest (format 4 -> 5, Kind column gone, two corruption cases removed, discard status), FluidCheckpointStoreTest, IslandSchedulerTest, PresentationBucketTest, CausalModuleCoordinatorTest (restedTicks -> replayedTicks) | kind REST/STEADY; RESTING:/STEADY: status; Advance.RESTED; Kind byte | drift 0 or > 0, or a finite horizon; STEADY: no flow; REPLAYED; no Kind column | D5 (review 9.2 lists old and new values) |
| FluidSolverRegressionTest (exact regression) | bitwise pins | re-record under the basis; bitwise on re-run | D1 |
| TrBdf2Test.embeddedControl...SecondOrder (added in WP1, stage 2 gate) | embedded = step doubling to 0.5 %; fixed-step error ratio > 3 (second order) | controlled interval within 1.5 % of 256 fixed steps; ratio in (1.8, 2.5) (first order; measured 2.03) | D1/D7 |
| Tests that constructed `TrBdf2StepSolver` or `ErrorControl.STEP_DOUBLING` (added in WP1; compile only, before any assertion changed) | TR-BDF2 step; step-doubling interval | one `PassiveStepSolver` step; the default interval solver (the prototype ignored the error control under `be`) | D7 |

Every re-baseline records the old and new value in the batch review.

## 6. Work packages

Each WP ends with its gate run; the review section for the WP lists the commands and the numbers. Estimates are agent-runs of the size used in this batch.

- **WP0 Provenance rerun (1 run).** Apply `holdup-prototype-replay.patch` to a clean 9674bf1, run the fluid suites switched on and at defaults with the commands recorded in the log, and confirm 384/403 (with the two horizon tests excluded) and 399/399. Closes the 7.11 provenance gap.
- **WP1 Switch collapse and TR-BDF2 removal (2-3 runs).** Section 2, on a branch from main. `SolidEventIntegrator` moves onto the BE step. Gate: the 38 adjacent tests, the probes (now assertion-based, moved into `src/test`), and the fluid suites with the section-5 adjustments applied; zero nonconvergence; cost within section 1.
- **WP2 Certificate merge (1-2 runs).** Section 4. Gate: IslandCertificateTest, FluidCheckpointFormatTest, PresentationBucketTest, CausalModuleCoordinatorTest bitwise; checkpoint round-trip tests.
- **WP3 Holdup runtime completeness (1-2 runs).** Liquid sizing: `sizeJunctionHoldups` uses a liquid velocity limit for a liquid seed (7.10: the 100 m/s gas limit made 9.78 kg water junctions and stalled FullTankSolids); a gate through `PhysicalRegistry.apply` for the mint booking (8.8 (g) never exercised it); `IslandCertificate` replay carries junction inventories; remint on a topology edit documented as the accepted behaviour (composition resets at an edit). Gate: FullTankSolidsEventTest, SolidRuntimeTest (amount-form rows), the topology tests, the probes.
- **WP4 Exact regression and GameTests (1 run).** Re-record `FluidSolverRegressionTest` under the basis; run the 30 GameTests; one in-game check of a mixed-gas junction island in the dev client through the MCP bridge (GUI rule), 60 s + 60 s if a benchmark claim is made (benchmark rule), otherwise none.
- **WP5 Cleanup, review, merge (1 run).** Tooling rule: the probes become gate tests or go to `tools/junction-holdup-prototype/` (already there) with the removal commit recorded in its README; every `junction.*` property and init-script forwarding removed; the batch review's final section lists the gates after cleanup; `CHANGELOG.md` `[Unreleased]` line; `mod_version` 0.5.0 -> 0.6.0 (minor: solver basis change) in the merge; `documentation/INDEX.md` row Implemented with the merge date; `tools/INDEX.md` row.

Order: WP0 -> WP1 -> WP2 -> WP3 -> WP4 -> WP5. WP2 and WP3 can run as separate agents after WP1 if they are kept in separate worktrees off the WP1 branch.

## 7. Risks

- TR-BDF2 removal touches `SolidEventIntegrator` and every test that counted stages; the section-5 list may grow. Mitigation: WP1's suite run before any re-baseline is applied, each new failure classified as intended or defect.
- Accuracy re-baselines change presentation numbers; the owner accepted the 5 s lag (D4). Mitigation: the review records old and new values.
- The certificate merge changes the checkpoint format; tested on a fresh world only (AGENTS.md).
- The reopen rule costs 2-3x on the two dead-head fixtures (8.8); acceptable at 5 s cadence, watched in WP1's cost gate.
- Open and not in scope: per-iteration allocation from state records (8.7 E1, needs mutable decode buffers); the chord's contraction across step-size changes (8.7 lever B); a warm start for reopened lines; the 6-port restart case above 5e-3 kg is gone under BE (8.6) and stays a probe case.

## 8. Related plan

`documentation/2026-09-26-phase-ports-and-compressor/PHASE_PORTS_PLAN.md` (liquid-only pump, gas compressor, phase-specific tank outlets) builds on this basis and starts after WP1.

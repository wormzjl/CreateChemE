# V3 literature CDU handoff: situation review

Date: 2026-09-06. Read-only review of `documentation/V3_LITERATURE_CDU_HANDOFF.md`, the
`codex/v3-literature-cdu` worktree (`run/codex-worktrees/v3-literature-cdu`, HEAD `d0464f7`,
18 modified + 5 untracked files), the two committed milestone reports, the three parent review
notes under `build/cdu-reference-probe/`, the last test XML, and the native evidence bundle.
No build, test, or native run was executed; the evidence directories were left untouched.

## Bottom line

- The handoff is accurate. Git state, file list, test XML (11 run / 10 pass / 1 fail, 07:47Z),
  integrity check (52 copies) and native attempt summary all match its claims.
- Two committed milestones are solid, transferable assets: the `tjl19_dwsim` package with a
  thermodynamically consistent enthalpy check, the frozen case contract, and a native DWSIM
  harness with qualified small cases and solver-specific heat-sign handling.
- The uncommitted step 4/5 core work (~400 lines) is structurally in line with the review
  contracts, but it is uncompiled since the last edits, has zero tests for the heat feature, and
  carries one real, unexplained performance regression on the truncated 100 kPa side-draw case.
- The native full reference is blocked by an installed Sum Rates temperature clamp that produces
  bound-induced false convergence. The cheapest remedy is a wider initial temperature envelope,
  not lambda subdivision.
- The full literature target lies inside the regime where V3 has never converged (44% molar
  side draws). Step 6 is an experiment with a real chance of a typed failure, not a formality.
- A 4.8k-line V4 prototype sits uncommitted in the main checkout and touches the same three
  transport files this branch guards; it also defines its own stage-heat types.

## What is verified and committed

| Item | Evidence |
|---|---|
| `27d00ea` package + contract | 360 tests green at that commit; held-out Cp 0.032% vs 0.05% budget; V3 matches native-fugacity-derived PR78 departure to 0.36 J/mol |
| Native default enthalpy inconsistency | measured up to 8.1 kJ/mol departure mismatch; harness restores consistent calorics on replay |
| `d0464f7` native harness | dry/wet NS and wet SR replays pass; +/-1 kW sign tests close to 7e-6 kW; VLL condenser verifies R = 4.17 |
| Full native CDU | **not qualified**; scaffold homotopy accepted to lambda 0.2, lambda 0.4 rejected by independent energy audit (-494.9 kW) |

## Uncommitted core work

### Step 4: mass acceptance, continuation, draw feasibility

Sound and matching `step4-review.md`:

- `V3AcceptanceAuditor.independentHydrocarbonMassChecks`: MW-weighted summed absolute
  component error over original HC feed mass, omitted mass rebuilt from sink edges, both capped
  at 0.001 kg/kg. Truncated states without MW fail closed; untruncated MW-less fixtures get an
  explicitly labelled conservative bound. Legacy `TRUNCATION_MASS_DEFECT` kept as a mol/mol
  diagnostic with limit `Double.MAX_VALUE`. Benchmark worker consumer updated.
- `recoverWithDrawRamp`: last accepted anchor retained; failed rungs never promoted; bounded
  midpoint subdivision (max 4, gap > 1/256); support refresh only on failed intermediates (max 2
  per rung, 4 total), skipped when the retained mask is unchanged.
- `V3SideDraws.withdrawal` rejects fraction >= 1. Inside Newton this becomes a typed
  `STATE_DOMAIN` attempt failure (`V3SimultaneousColumnSolver` line ~192), local-block and FD
  probes swallow it, and the auditor now checks `SIDE_DRAW_SPLIT` before evaluating residuals.
  `feasibleSeed` scales only the seed (1.05x draw) and emits an event.

Concerns:

1. **Whole-component reinsertion rule** (`V3TruncationSupport.derive`, new `uncertain[]`): any
   component with a nonpositive flow at any node where the phase exists is retained on every
   node. Combined with truncation policy now ON at intermediate rungs (was OFF), each rung's
   deciding state is the previous rung's reduced state with structural zeros, so every
   previously omitted component is reinserted in full and floor-seeded, then re-omitted a rung
   later. The mask oscillates and the requested rung likely runs near identity support. This is
   the leading hypothesis for the regression below. It also changes behaviour for zero-feed
   components (e.g. methane in the CDU17 package): now retained everywhere instead of omitted.
   `V3TruncationSupportTest` had one assertion flipped to encode this.
2. Manufactured fixtures needed `fullDecidingEstimate` (1e-12 floors) to keep truncation
   nontrivial. In production the review note forbids floors as omission evidence; the fixture
   use is acceptable only because it is labelled, but it shows the rule is coarse.
3. `conservativeBound` divides by `feed_i`; a zero-feed component in an MW-less fixture yields
   NaN/Infinity and a spurious failure. Only reachable for manufactured/Holland models.
4. Vacuous `allMatch` on a filtered stream in `V3SideDrawCalculatorTest`,
   `V3StageTraceCalculatorTest` and `V3FailedStateRefreshReplayTest`: passes if the family is
   absent. Use `filter(...).findFirst().orElseThrow()`.

### Step 5: prescribed stage heat

Structure matches `stage-heat-review.md`: immutable `V3StageHeatSpec` (tray 1..N, finite,
unique, zero entries dropped, -0 normalised), record field with equals/hash/toString/digest
coverage and an empty-list hash preserved, `+Q` on the interior energy residual only, no
Jacobian change, ramp coupling (heat ramps with draws after the steam ramp; heat-only path has
four rungs), formulation revision `v3-mesh-r7-prescribed-stage-heat`, and transport boundaries
failing closed via `requireLegacyTransportCompatible`.

Gaps:

5. `V3GlobalEnergyAudit` has never been compiled or executed. It only runs when heat is
   nonempty, so no existing test exercises it. Its condenser duty is derived from the local
   overhead balance using the same W1 water assumptions as the MESH equations; it is independent
   of the residual code, not of the water model. A zero-liquid draw tray produces NaN and fails
   closed. Allowance `max(1 W, 1e-6 * scale)` is about 300 W at this column's scale, which is fine.
6. No test for: signed heat residual shift, Jacobian vs finite differences at -17.9 MW,
   energy-row scaling with a large sink, unchanged DOF/bandwidth, continuation preserving
   applied heat fraction, digest sensitivity, exact literature stage mapping.
7. `remappedStageHeats` (coarse-grid remap) is effectively dead: heat is stripped by
   `withoutStageHeats` before the coarse grids are built. Harmless but misleading.
8. `feasibleSeed` throwing `IllegalArgumentException` inside `prepareAttempt` reaches the
   calculator's top-level catch and is labelled `INVALID_INPUT` when cutoff is zero. The
   near-feed-draw test guards one case only.

## The 100 kPa truncated side-draw regression is real

Timings from the only two runs on disk (same machine):

| Case | 2026-09-01 (`bdfeff1`, before sparse optimisation) | 2026-09-06 dirty tree |
|---|---:|---:|
| originalLargeDrawCase (known failing) | 25.3 s | 42.1 s |
| 150 kPa, cutoff 0 | 8.0 s | 5.0 s |
| 100 kPa, cutoff 0 | 12.5 s | 7.8 s |
| 250 kPa, cutoff 0 | 7.4 s | 4.6 s |
| 100 kPa, cutoff 1e-6 | 12.1 s | >45 s (deadline) |

The three untruncated cases got faster, so machine load does not explain the truncated case
going from 12 s to over 45 s, or the failing large-draw case taking 17 s longer to fail. Both are
consistent with concern 1 plus the new refresh/subdivision retries (each failed intermediate
can now cost up to three 40-iteration solves before subdivision). The 120 s deadline edit is a
measurement change, not a fix. The ramp events already record "support refresh" and "adaptive
retry" counts; one instrumented rerun with `rampPolicy` forced to `OFF` at intermediate rungs
will decide it.

## Native reference blocker

Installed `BurninghamOttoMethod.Solve` clamps every updated temperature to
`[min(T0) - max(0.5 * span(T0), 25), max(T0) + ...]`, measures convergence after clipping, and
has no energy-residual gate. The lambda 0.2 scaffold state is nearly isothermal (454-483 K), so
the lower bound is 429.29 K and 22 stages of the lambda 0.4 trial sit exactly on it. The
mechanism is established by IL inspection; nothing was patched.

Remedies, cheapest first:

1. **Widen the initial envelope.** The bound depends only on the initial temperature array.
   Seeding the trial with a profile spanning the physical range (about 330-640 K) pushes the
   clamp at least 150 K away from any plausible stage. Keep the accepted interior profile and
   only stretch the ends, then verify with the independent energy audit.
2. Adaptive lambda subdivision as already proposed (0.20 -> 0.30 -> 0.40).
3. Naphtali-Sandholm warm-started from the SR-accepted state with a 10-minute budget; its cost
   is the dense numerical Jacobian (about 150k enthalpy calls per 90 s).

The native post-check 1e-9 component criterion and the 1e-10 tolerance remain a separate issue
for final reference tolerances.

## Target feasibility risk

Side draws total 325.3 mol/s of 737.7 mol/s feed (44.1% molar); steam is a further 333 mol/s.
V3's measured draw wall: converges at <= 0.25x literature-analogue rates, stalls at >= 0.40x; all
16 DOE cells at 40% fail, root-caused to dry-tray degeneracy below heavy draws. The -41.9 MW of
pumparound cooling is exactly the physical mechanism that supplies that liquid, which is why
the experiment is worth running, but nothing yet shows V3's log-flow, fixed-topology
formulation can reach the target. Plan step 6 should be budgeted as "record margins and typed
failure" with success as the upside.

## Interplay with the uncommitted V4 prototype

- Main checkout: 45 files / 4,795 lines under `science/column/v4`, plus runtime, codec, network
  and GUI files, all untracked; tracked modifications to `ColumnCalculatorV3Screen` (+255),
  `ColumnV3Network` (+11), `ColumnCalculatorV3BlockEntity` (+201). This branch adds one-line
  guards to the same three files; merging is trivial but the fail-closed guard must survive.
- V4 has its own `V4StageHeatDuty`, `V4StageHeatDraft`, `V4HeatEditorLayout`. Two stage-heat
  contracts now exist. Plan step 8 (persistence/UI) should decide whether `V3StageHeatSpec` ever
  becomes a wire/save format or stays a core/test input.
- `V3_FUNDAMENTAL_SOLVER_REDESIGN.md` (09-05) recommends only small diagnostic/seed fixes in V3
  during transition. The mass audit, anchor fix and feasibility guard fit that; the
  intermediate-rung mask refresh and whole-component reinsertion do not, unless measurement
  shows they are needed for the literature target.

## Recommended resume order

1. Compile; run the five touched test classes; rerun the 100 kPa cutoff case with intermediate
   truncation OFF vs ON and read the refresh/retry events. Keep whichever is faster and
   correct; restore the 45 s deadline or document a measured budget.
2. Fix the vacuous assertions; decide the zero-feed-component retention behaviour explicitly
   and cover it against the CDU17 package.
3. Write the step 5 tests from the handoff list; run the full suite; commit step 4 and step 5
   separately with an honest milestone report.
4. Native: initial-envelope remedy, then lambda subdivision, then reflux closure and HC-only
   draw adjustment, always gated on the independent energy audit.
5. Step 6 with typed failure recording; PA on/off and 19/31 comparisons only after that.
6. Before step 8, reconcile with V4 on transport files and the stage-heat contract.

# V3 handoff review fixes — 2026-09-08

Fixes for the three P2 findings of `documentation/V3_HANDOFF_2026-09-08_REVIEW.md`
(reviewed commit `f4e600a`). Work was done in the worktree
`.claude/worktrees/v3-low-pressure-gaps-989c00` on branch
`claude/v3-literature-cdu-handoff-3179dc`; one commit per finding.

| Finding | Commit | Subject |
| --- | --- | --- |
| 1 — cooling admission ignores authored positive stage heat | `36dcb3e` | Credit authored stage heating to both cooling admission bounds |
| 2 — a dry-tray warning can mask a wet-tray saturation failure | `ab14be5` | Rank the wet-tray dew-point failure apart from the dry-tray warning |
| 3 — failed reruns clear the Heat tab's stale-result indication | `e8d8937` | Mark a retained result on the Heat page instead of clearing its pill |

## Revision decision

**No formulation or assumptions revision label was bumped.** Nothing published
for an existing case changes:

- Finding 1 only widens two rejection gates, and only when the input authors a
  positive duty. `heatingCreditWatts` is exactly zero for every cooling-only
  input, so both comparisons and both detail strings are byte-identical to
  before for every existing case. The gates are admission checks; they are not
  part of the MESH formulation, the digest, or any published quantity.
- Finding 2 changes which of two already-computed numbers a check publishes
  only in the masked case, which by construction did not exist in the suite:
  a state that previously passed with a dry warning while a wet tray was
  inconsistent. On the fully-passing path the published `value` is still the
  maximum over both regimes; on the existing warning path the dry maximum
  *is* that maximum (the warning branch was only reachable when the worst node
  was dry), and on the existing failure path the worst wet value likewise was
  that maximum. `V3LiteraturePresetTest` still pins `1.162` and
  `"warning: tray 1 "` and is green.
- Finding 3 is client presentation only.

The pinned digest `V3PumparoundContractTest.HEAT_FREE_DIGEST` is untouched and
still passes, as are `V3PumparoundCalculatorTest`'s
`v3-dry-mesh-r27-stage-heat` / `v3-heat-assumptions-r1` label assertions.

## Finding 1 — cooling admission ignores authored positive stage heat

`V3PumparoundSpec` duties may be positive. Both energy admission checks
compared the **gross** authored cooling (`V3Pumparounds.totalCoolingWatts`)
against a budget computed for a column carrying **no** stage heat, so an input
with a heater beside a cooler could be rejected before Newton ran. The
reviewer's reproduction — `pilotPresetInput()` plus
`(8, 10, -1e9 W, RETURN_TRAY)` and `(8, 11, +1e9 W, RETURN_TRAY)` — expands to
exactly zero net heat on every node, yet was rejected as
`INFEASIBLE_SPECIFICATION` for "cooling of 1000 MW exceeds 98.31 MW available".

Changes:

- `V3Pumparounds.totalHeatingWatts(V3ColumnInput)`: gross authored heating as a
  nonnegative value, the mirror of `totalCoolingWatts`. `totalCoolingWatts`'s
  javadoc no longer says heating is "deliberately excluded"; it now says any
  energy bound built on it owes the heating credit.
- `V3HeatFeasibility.heatingCreditWatts(input)`: the credit, clamped to a
  finite nonnegative value, so a bound can never be *narrowed* by it.
- `V3ColumnCalculator.staticCoolingAdmission`: admits when
  `cooling <= available + heating`.
- `V3ColumnCalculator.requireCoolingBelowBaseCondenserDuty`: admits when
  `cooling < |Q_cond0| + heating`. Fixing only the static gate would have left
  this second false rejection in place, exactly as the review says.
- `staticAdmissionDetail` and `condenserBoundDetail` take the credit and append
  "even with the *N* MW of authored stage heating credited to it" when it is
  nonzero. With a zero credit both strings are unchanged.

Tests added:

- `V3PumparoundContractTest.theGrossCoolingAndHeatingHalvesSplitTheAuthoredDutiesAndCancelOnTheExpandedProfile`
  — the two gross halves, the signed total, and a node-by-node zero expansion
  for two distinct return/draw pairs that both land on tray 2.
- `V3PumparoundContractTest.theHeatingCreditIsNonNegativeAndTheAdmissionDetailNamesItOnlyWhenItIsSpent`
  — the credit is zero without a heater, and both detail strings gain the
  clause only when it is spent (the zero-credit strings are pinned by suffix).
- `V3PumparoundCalculatorTest.aCoolerCancelledByAnEqualHeaterReachesTheHeatFreePresetState`
  — the reviewer's case (a). It first asserts the expanded per-node duty is
  exactly zero everywhere, then solves both the heat-free preset and the
  cancelling pair and asserts every published stream matches to 1e-6 relative
  flow and 1e-6 K, with zero published stage heat and a passing
  `GLOBAL_ENERGY_BALANCE`. Measured: heat-free 4.56 s, cancelling pair 6.06 s
  (the pair costs the extra heat rungs), path
  `.../draw-ramp-1.0/draws-3/heat-2`.
- `V3PumparoundCalculatorTest.theCondenserDutyBoundCreditsAnEqualHeaterOnTheSameTray`
  — case (c). Cooling at 1.02x the heat-free base condenser duty (the exact
  duty `coolingAboveTheBaseCondenserDutyIsRejectedWithThatDutyNamed` rejects)
  with an equal heater on the same tray is admitted and reproduces the
  heat-free condenser duty to 1e-6 relative.
- `V3PumparoundCalculatorTest.aHeaterCreditWidensTheStaticGateWithoutWaivingIt`
  — case (b) reinforced: 200 MW of cooling with a 1 MW heater is still
  rejected with `INFEASIBLE_SPECIFICATION`, solve path `input/heat-2`, zero
  Newton iterations, and the detail names the credit. The pre-existing
  `impossibleCoolingIsRejectedByTheStaticAdmissionGateWithoutASolve` keeps the
  no-heater rejection and now also asserts its detail does *not* mention a
  credit.

Not changed: `V3Pumparounds.nodeDutyWatts` already signs and adds overlapping
zones correctly, and `condensationCappedTray` already reads the summed signed
tray duty, so the per-rung capacity cap needed no correction.

## Finding 2 — a dry-tray warning can mask a wet-tray saturation failure

`V3AcceptanceAuditor.waterDewPoint` reports two different claims on one scale:
a dry stage's saturation ratio against a limit of one, and a wet tray's
`|ratio - 1| / closureLimit()`. It folded both into a single `maximum` /
`worstNode` and failed only when `worstNode` happened to be wet, so an
inconsistent wet tray beneath a larger dry ratio was published as that dry
tray's passing warning and the wet-set failure was lost.

The two regimes are now ranked separately:

1. the pre-existing empty-wet-tray failure still comes first;
2. **any** invalid wet node (normalized error above one, or non-finite, or a
   temperature the water correlation rejects) fails the check, naming the worst
   such wet node, with that node's own normalized error as the published
   `value`;
3. only a fully consistent wet set may let a supersaturated dry stage produce
   the passing warning, whose `value` is the dry maximum;
4. the fully-passing `value` is the maximum over both regimes, exactly as
   before.

A non-finite value now ranks as the largest violation there is, so a wet tray
whose ratio is NaN fails and a dry tray whose ratio is NaN at least names its
own tray in the advisory (it previously fell through to the "a stage that
carries no free-water phase" placeholder).

`waterDewPoint` was dropped from `private` to package-private so the
regression can call it directly. The auditor's `audit()` entry point cannot be
used here: on a `TWO_PHASE` branch it runs the condenser split checks, which
need a flashing property package, and the manufactured three-tray fixture's
`AffineEnthalpyThermo` deliberately throws from `flashTP`. This avoids the
reflection the review probe needed.

Tests added, both in `V3FreeWaterTrayTest` on the existing `wetProblem(1)` /
`saturatedState` / `AffineEnthalpyThermo` fixture, through a new
`withWetRatioAndHotDryTray` helper that retunes tray 1's hydrocarbon vapour to
a requested saturation ratio and heats dry tray 2:

- `aSupersaturatedDryTrayDoesNotDowngradeAnInconsistentWetTrayToAWarning` —
  wet tray 1 at ratio `1 + 1.5 x 0.001` (normalized error 1.5) with dry tray 2
  at 390 K (ratio about 2.31). The check must FAIL, name tray 1, not start
  with `"warning: "`, and publish 1.5. The test also asserts the dry ratio is
  still the larger number on the shared scale, which is what made the old
  ranking pick the wrong node.
- `aConsistentWetTrayLeavesTheSupersaturatedDryTrayAsAPassingWarning` — the
  same dry tray with tray 1 exactly on the saturation line still passes with
  the warning naming tray 2 and publishing the dry ratio.

`V3LiteraturePresetTest` (all three cases, including the pinned tray-1 warning
at 1.162) stays green, as do `V3CondenserPhaseAuditTest`,
`V3SideDrawAuditTest`, `V3TruncationAuditTest` and
`V3PumparoundSteamCalculatorTest`.

## Finding 3 — failed reruns clear the Heat tab's stale-result indication

`applyServerState` clears `draftEditedSinceState` on every server state, and
both Heat consumers read that cleared flag as proof that the retained
`displayResult` belonged to the draft on screen. The block entity keeps the
last accepted result through a `CALCULATING` and a `FAILED` request and across
a save/reload (`STALE`), so after a successful run A, an edit, and a failing
run B the pill vanished, A's ledger summary stayed, and `renderCoolerBars`
labelled the tray map with **B's authored** megawatts as if they were ledger
values.

New client-side-free helper
`client/gui/screens/inventory/V3ResultProvenance` (no Minecraft types beyond
the `V3Status` enum, which loads fine under JUnit — `V3PumparoundCalculatorTest`
already calls `ColumnCalculatorV3BlockEntity.pilotPresetInput()`):

- `retainedResultMatchesInput(V3Status, boolean resultPresent, boolean draftEdited)`
  — true only for `SUCCESS` with a present result and an unedited draft.
- `provenancePill(...)` — `null` when the result is the current one,
  `"Input edited since run"` when the draft was edited, and
  `"Retained result from an earlier run"` otherwise.

`ColumnCalculatorV3Screen` changes are two call sites: `renderColumnDuties`
draws whichever pill the helper returns (abbreviated to the remaining left
column width so the longer text cannot run into the tray map), and
`renderCoolerBars` gates the megawatt labels on
`retainedResultMatchesInput` instead of `!draftEditedSinceState`, falling back
to the existing draw-return label form. No restyling; the input and result
revision counters are still never compared numerically.

`STALE` is treated as not-matching on purpose. A calculator saved after a
failed rerun reloads as `STALE` with the *earlier* input's result, which is the
reopening case the review names. The cost is that the common
"reopen a calculator that last succeeded" case now shows the draw-return
labels and the retained pill rather than megawatts; that is a loss of
convenience but never a false claim, and `STALE`'s own server detail already
reads "presentation-only; recalculate to refresh it".

Test added: `V3ResultProvenanceTest` (3 cases) — the matching rule across every
`V3Status` value plus `null`, the failed-rerun pill sequence including
`CALCULATING` and `STALE`, and no pill at all without a result.

### Follow-up for the maintainer

In-game verification through the `minecraft-mod-mcp` bridge was **not** done
here and is left as a follow-up: open a calculator on the Heat page, run the
preset successfully, edit a cooler duty, submit a run that fails, and confirm
the "Retained result from an earlier run" pill appears and the tray-map cooler
labels show `PA1 10-8` rather than megawatts. Worth deciding at the same time
whether `STALE` deserves its own wording ("Persisted result; recalculate to
refresh") rather than sharing the retained pill.

## Verification

- Targeted, after each fix: `V3PumparoundContractTest` (7),
  `V3PumparoundCalculatorTest` (13), `V3PumparoundSteamCalculatorTest` (7),
  `V3FreeWaterTrayTest` (12), `V3LiteraturePresetTest` (3),
  `V3CondenserPhaseAuditTest` (8), `V3SideDrawAuditTest` (4),
  `V3TruncationAuditTest` (3), and all six
  `client.gui.screens.inventory` classes — all green.
- Full suite: `./gradlew.bat --offline test --no-daemon -q` — **89 suites, 465
  tests, 0 failures, 0 errors, 0 skipped**, summed from the generated JUnit
  XML. The review's baseline was 455; the ten new tests are 2 + 3 (finding 1),
  2 (finding 2) and 3 (finding 3).
- No tolerance was loosened and no assertion was removed anywhere.
- Line endings: `git diff --stat` shows only the edited hunks on every touched
  file, no whole-file churn.

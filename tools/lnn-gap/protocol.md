# Closing the LNN_ONLY gap: solver and decoder rules on the frozen production model

This follows the read-only gap analysis of the promoted Transformer initializer
(`documentation/V4_LNN_ONLY_GAP_ANALYSIS.md`). It is a registered paired campaign on the **frozen**
production weights: no model is trained, no weight byte changes, no tolerance, audit, support rule, digest
or strict-data boundary moves, and the promoted defaults stay exactly what they are. What every arm changes
is a solver rule, a decoder candidate rule or a recovery rule, and each arm changes exactly one of them.

Registered before any arm ran. Every option value in section 3, every rule in section 4 and every gate in
section 5 is stated here; `gap_analysis.py` applies them and chooses nothing of its own.

## 1. What the analysis says the promoted path leaves behind

On the 405 archived validation inputs the promoted path is classical 110, LNN_ONLY 162, LNN_FIRST 180, in
both blocks. The analysis partitions the population:

| Group | Definition | n |
|---|---|---:|
| A | LNN_ONLY strict on the promoted path | 162 |
| B | LNN_FIRST strict, LNN_ONLY not: the classical rescues | 18 |
| C | not LNN_FIRST strict, but strictly solved by some archived arm | 34 |
| D | never strictly solved by anything across 33 archived arms | 191 |

Three findings drive the four experiments.

1. **The extension gate refuses trajectories that were still moving.** The registered contraction factor of
   0.5 lets only 4 of the 20 traced crawling cases through. The two crawling cases in B both stop with the
   bare "iteration budget exhausted" and both converge under a flat 48-iteration attempt inside the same
   two-second wall.
2. **The early stall abort costs coverage the same weights already had.** It fires on 22 of the 34 C cases
   and 7 of the 18 B cases, and at least one case that sibling F0 pipelines solve is lost to it.
3. **Classical reaches every B case the same way, and it is not the seed that does it.** Classical solves a
   feature-free surrogate at the requested geometry by cold stage continuation and then walks the authored
   draws, steam and stage heat up in bounded rungs; the terminal rung takes a median of three Newton
   iterations. Nine B cases need one of those ramps on top of the continuation. The learned route throws its
   failed state away and restarts from nothing.

A fourth question is cheap enough to ask beside them: the decoder's prune rule is a second seed out of the
same forward pass, and sibling pipelines solve 5 of the 34 C cases with it.

## 2. Population, entry point and what is measured

The campaign runs on a **cleaned validation population** built separately from this study and passed to
`gap_register.py --population`; its path, SHA-256, case count and id list are frozen into the study plan
before anything runs, together with which ids it keeps from and drops against the promotion archive. It is
expected to be about 325 inputs.

Every request goes through the production entry point, `V3ColumnCalculator.calculateWithAcceptedProfile`,
at cutoff 0, closure 0 and the default liquid-supply screen ratio, with the bundled artifact
`v3-column-transformer-f0.json` (SHA-256 `7f909d02...`). Strict means what it has meant in every campaign
in this line: an accepted result whose acceptance audit passed every check, whose certificate is a real
final Newton step at closure `1e-8` with backward error at most `1e-12`, maximum log-flow change at most
`1e-8` and temperature step ratio at most 1, and whose accepted profile carries a `DRY_EQUILIBRIUM` or
`WET_EQUILIBRIUM` water qualification.

Ten owned worker threads, a thirty-second request deadline, a 4 GiB heap, the fixed TRAIN warmup, three
strategies per case, two blocks whose arm order is reversed. No step may overlap another campaign or a
Gradle run.

## 3. The arms

Seven arms in round one. `baseline` is the promoted path itself and asks `V3NeuralModels.bundled()` for its
model, so the arm everything is paired against is literally the production holder. Every other arm differs
from it in the single field named below. All seven load the identical weight bytes and the identical
promoted decoder rule (`zeroPhaseFloor(10)`).

`Correction` is `(extensionBlock, extensionMaximumIterations, contractionWindow, contractionFactor,
stallWindow, stallFactor, stallResidualFloor)`; the base cap and the wall are stated beside it.

| Arm | base cap / wall | Correction | candidates | recovery | delta from baseline |
|---|---|---|---|---|---|
| `baseline` | 16 / 2,000 ms | (8, 48, 8, **0.5**, 8, **0.9**, 1e-6) | SINGLE | NONE | — |
| `E1a` | 16 / 2,000 ms | (8, 48, 8, **1.0**, 8, 0.9, 1e-6) | SINGLE | NONE | contraction factor |
| `E1b` | **48** / 2,000 ms | (**0, 0, 0, 0.0**, 8, 0.9, 1e-6) | SINGLE | NONE | flat cap, no extension gate |
| `E2a` | 16 / 2,000 ms | (8, 48, 8, 0.5, **12**, **0.95**, 1e-6) | SINGLE | NONE | stall window and factor |
| `E2b` | 16 / 2,000 ms | (8, 48, 8, 0.5, **0**, **0.0**, **0.0**) | SINGLE | NONE | abort disabled |
| `E3` | 16 / 2,000 ms | (8, 48, 8, 0.5, 8, 0.9, 1e-6) | SINGLE | **RAMP_HANDOFF** | recovery rule |
| `E4` | 16 / 2,000 ms | (8, 48, 8, 0.5, 8, 0.9, 1e-6) | **DECODE_VARIANTS** | NONE | second candidate |

**E1a** is "extend unless the residual rose over the window": with factor 1.0 the extension is granted
whenever the maximum scaled residual at the cap is not strictly above its value eight iterations earlier.
**E1b** removes the gate rather than loosening it, by giving attempt one a flat 48 iterations with no
extension at all. That is the configuration the bounded diagnostic actually measured, and it is the honest
form of "no gate": no value of `contractionFactor` makes the extension test unconditional. Its one declared
confound is that the base cap also bounds the fixed-water wet prepass, so on wet columns E1b changes two
things; the analysis reports its wet and dry regimes separately.

**E3** is the ramp handoff, described in section 6. **E4** offers the prune decode as a second candidate
from the same forward pass; a second condenser branch is deliberately **not** offered, because the branch
is a one-hot input to both the network and the native anchor, so a second branch is a second anchor build
and a second forward pass rather than a second decode.

All four live behind opt-in production switches whose defaults are the promoted values:
`V3InitializationOptions.Correction` (E1, E2), `V3InitializationOptions.Recovery.NONE` (E3) and
`V3AnchorTransformerInitializer.CandidateRule.SINGLE` (E4).

## 4. The registered selection rules

Let the **net LNN_ONLY gain** of an arm be `strict(arm) − strict(baseline)` in a block, and
`netGainBothBlocks` its minimum over the two blocks. Let the **latency noise band** be the baseline arm's
own between-block spread in the pooled LNN_FIRST mean, measured in this campaign from the baseline alone.
A latency change within that band is not a regression: two runs of an identical pipeline on identical seeds
do not produce identical means, and the predecessor campaign measured a 114 ms spread on an identical one.

An arm is **eligible** when `netGainBothBlocks > 0` and its pooled LNN_FIRST mean is at most the baseline's
plus the noise band.

- **E1**: among `E1a` and `E1b`, keep the eligible arm with the larger `netGainBothBlocks`; ties go to the
  cheaper arm by pooled LNN_FIRST mean. If neither is eligible, E1 selects nothing.
- **E2**: the same rule over `E2a` and `E2b`, with one extra tie-break before cost: the larger **crawling
  recall**, defined as the fraction of the 20 registered crawling ids whose LNN_ONLY request either was
  strict or ended on a stop other than the early stall abort, taken as the minimum over the two blocks.
  Recall is reported for every arm whatever the rule does with it.
- **E3**: selected when it is eligible. Its headline numbers are B recovery (how many of the 18 B ids become
  LNN_ONLY strict), the pooled all-case LNN_FIRST mean — a handoff that fails still precedes the cold
  restart, so this is where its cost lands — and the LNN_ONLY net gain.
- **E4**: selected when it is eligible. Its headline number is C recovery.
- **E5 combination**: one arm carrying every field its experiment selected — E1 the extension fields, E2 the
  stall fields, E3 the recovery rule, E4 the candidate rule — run in a second round of two blocks. E5 is
  recommended over the best single arm only if it is strictly better on LNN_ONLY in **both** blocks. If no
  experiment qualifies, no second round runs and the recommendation is the promoted defaults unchanged.

## 5. Gates

Registered unchanged from the predecessor study, with the noise band made explicit:

1. a strict LNN_FIRST gain against the baseline in **both** blocks;
2. preservation of the contemporaneous classical strict union — every case classical solves strictly in
   every run of this campaign must remain LNN_FIRST strict under the arm, in both blocks;
3. no pooled mean LNN_FIRST latency regression beyond the noise band. The strict form (no band) is reported
   beside it.

**Parity gate, before the campaign.** `V3GapDecodeCheck` dumps every arm's ordered candidate list for the
whole population with no solver involved, and the first candidate of every arm must reproduce the promotion
run's committed decoded-seed digests. Afterwards the `baseline` arm must reproduce the promotion run's
classical, LNN_ONLY and LNN_FIRST strict identity sets — the identities, not the counts — restricted to the
ids the cleaned population kept, in both blocks.

Passing authorises further qualification only. Nothing here promotes anything.

## 6. The ramp handoff (E3), and how its budget is accounted

**Where it enters.** In the learned entry of `V3ColumnCalculator`, after the candidate loop has ended with
no accepted result and after the two request-only typed failures (`PROPERTY_OUT_OF_RANGE`,
`INFEASIBLE_SPECIFICATION`) have been returned. That single insertion point is before the cold classical
restart in `LNN_FIRST` and the last step in `LNN_ONLY`, which is what the design asks for.

**When it is eligible.** Three conditions, all necessary:

1. `options.recovery() == RAMP_HANDOFF`;
2. the request carries at least one authored side draw, steam feed or stage heat loop — otherwise there is
   no ramp to hand anything to. This excludes 4 of the 18 B cases by construction, so E3's B ceiling is 14;
3. the correction left a terminal state behind. A seed rejection, an out-of-coverage request or a candidate
   that never reached its first residual evaluation leaves nothing to continue, and the handoff is skipped.

Condition 3 covers the budget-interrupted case deliberately. Six of the 18 B cases stop at the two-second
wall, where the budget throws out of the middle of a Newton solve and no pass is returned at all. The
learned path's existing in-flight recorder already observes every sampled iterate, so it keeps the last one
— a reference to an already-published immutable state — together with the condenser branch of the candidate
that produced it. The recorder only keeps it when a recovery rule was selected, so the default path does
not even write the field.

**What it does.** The classical route never solves a column carrying authored features directly: it solves
the feature-free surrogate at the requested geometry (`withoutSideDraws`, `withoutSteamWithSurrogateDuty`,
`withoutPumparounds`) and then hands the accepted surrogate to `recoverWithDrawRamp`. Dropping those
features changes neither the stage geometry, the condenser branch nor the active component basis, so the
failed learned iterate is a valid seed for the surrogate. The handoff therefore replaces the cold stage
continuation — the expensive half — with that iterate, projects it with `continuationSeed` (the same
projection the ramp uses for its own first fixed-geometry handoff, which also drops the free water a dry
surrogate cannot carry), solves the surrogate, and hands the result to the **unchanged** ramp.

Nothing is published unless the ramp reaches the authored input and its own fresh audit and final Newton
certificate accept it, and the publication goes through the same helper the ordinary learned success uses,
so a recovered result carries the same digest, duty ledger and exported profile. Every failure mode —
its own sub-wall, a path-dependent heat bound, a thermodynamic rejection, an internal guard — returns
nothing and leaves the published failure exactly as it was.

**Budget accounting.** The handoff runs on the caller's own `V3SolveControl`, the thirty-second request
deadline, never on the two-second learned allowance, which by then is already spent. Inside that deadline
it carries its own declared sub-wall of **8,000 ms** (`NEURAL_RAMP_HANDOFF_BUDGET_MILLIS`). The sub-wall is
not a free parameter chosen after the fact: the most expensive classical rescue the analysis measured on
this gap is 8,774 ms with its stage continuation included, and the handoff skips the continuation, so 8 s
bounds it above what the route it replaces costs. Bounding it there rather than at the request deadline is
what stops a handoff that cannot close from eating the classical restart `LNN_FIRST` still owes afterwards.

The cost is published rather than inferred. Every request that armed a handoff carries `handoffMs=<n>` in
its initialization event, whether the handoff closed or not, and the learned allowance is reported beside
it as `neuralMs=`. `gap_analysis.py` reports, per mode: how many requests armed a handoff, how many it
accepted with their ids, the mean and spread of what it spent when armed, when accepted and when refused,
and the total seconds the arm spent inside handoffs. The field appears only when a recovery rule was
selected, so its absence is evidence and is kept distinct from zero.

Declared in advance: E3 **will** cost LNN_FIRST time on the cases it cannot close, because a failed handoff
precedes the restart it does not replace. That is the trade the latency gate is there to price.

## 7. Target groups

Three committed id lists under `ids/`, written by `gap_ids.py`:

| Group | n | Source |
|---|--:|---|
| `group-b` | 18 | transcribed from the analysis and **rechecked** against the promotion evidence: LNN_FIRST strict and LNN_ONLY not, in both blocks. 0 transcription-only and 0 evidence-only ids. |
| `group-c` | 34 | transcribed from the analysis; the promoted-path half of its definition is rechecked (none is classical or LNN_FIRST strict in either block). Its membership also depends on archived arms this study never runs. |
| `crawling` | 20 | read from the neural-budget study's committed trace classification, not transcribed. |

`group-b ∩ crawling` is the two crawling B cases the analysis names; `group-c ∩ crawling` is 5; B and C are
disjoint. 14 of the 18 B ids and all 34 C ids are ramp-eligible. Groups are reported intersected with the
ids the cleaned population keeps, and both counts are published.

## 8. Running it

`P` is `C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-venv/Scripts/python.exe`, run from
`tools/lnn-gap`. Every step owns ten worker threads and must not overlap another campaign or a Gradle run.

```
$P gap_ids.py                                              # committed id lists, seconds, no JVM
$P gap_register.py register --population <cleaned.jsonl>   # freezes population, arms, order, source delta
$P gap_native.py rebuild-core                              # isolated Gson-only rebuild, about a minute
$P gap_preflight.py decode                                 # solver-free decode dump per decode rule
$P gap_preflight.py check                                  # seed parity; must pass before any campaign time
$P gap_benchmark.py validation                             # 7 arms x 2 blocks x 3 modes
$P gap_analysis.py
$P gap_report.py render
# if the rules select a combination:
LNN_GAP_REVISION=v2 $P gap_register.py register --population <cleaned.jsonl> --arms baseline E5
LNN_GAP_REVISION=v2 $P gap_native.py rebuild-core
LNN_GAP_REVISION=v2 $P gap_preflight.py decode && LNN_GAP_REVISION=v2 $P gap_preflight.py check
LNN_GAP_REVISION=v2 $P gap_benchmark.py validation
LNN_GAP_REVISION=v2 $P gap_analysis.py
$P gap_report.py render && $P gap_report.py seal
```

## 9. Limits declared in advance

1. Historical validation on a cleaned population, not a fresh holdout. The two blocks repeat the same
   inputs in reversed arm order, so their spread describes case and load variability rather than a
   confidence interval over independent campaigns.
2. The target groups are outcome-selected diagnostics of the 405-input archive, not a population benchmark,
   and group C's membership rests on archived arms this campaign never re-runs.
3. E1b changes the base cap, which also bounds the fixed-water wet prepass. On wet columns it is therefore
   two changes, and its regimes are reported separately.
4. E3's eligibility rule excludes featureless columns by construction; a warm restart of a plain column from
   a failed learned state is a different intervention and is out of scope here.
5. The 8,000 ms handoff sub-wall is bounded by the classical route it replaces, not proven optimal. Its
   effect is visible in the published per-request cost, and a different value would be a different arm.
6. Advisory successes are excluded from every strict count and no failed input leaves the denominator.
7. No arm may move a decoded seed, and the preflight is what proves it. If an arm's first candidate ever
   differs from the promotion digests, the campaign stops there; that is a broken study, not a result.

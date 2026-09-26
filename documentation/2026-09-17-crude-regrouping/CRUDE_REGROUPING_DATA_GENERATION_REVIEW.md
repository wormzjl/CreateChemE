# Crude regrouping: review of the training-data generation

Reviewed 2026-09-18 against `codex/crude-regrouping` @ `4e84f0e` and the untracked evidence in
`run/codex-worktrees/crude-regrouping/research/crude-regrouping/training/`. Scope: why the packaged
initializer `regrouped-seed-17041-epoch-160` only moves strict acceptance from 44 to 65 of 168 (validation)
and 63 to 76 of 252 (holdout), and what in the request design causes it. Analysis scripts and the probe
journal are in `research/convergence-review/` of the worktree
`.claude/worktrees/crude-regrouping-plan-review-28f25a` (branch fast-forwarded to `4e84f0e`).

## Verdict

The regrouped property basis did **not** make the column harder to solve. The request design did three
things that depress every reported number and starve the model:

1. One request in five is a column the formulation cannot represent (no vapour source below the feed).
   It converges 0 of 243 and no initializer can change that.
2. Equipment counts are a deterministic function of stage count and crude, so the design is a thin
   one-dimensional thread through the structural space. The production configuration (3 pumparounds,
   3 draws, steam) has **13 TRAIN requests and 1 label** across all six crudes.
3. The set is 2.5x smaller than the F0 design while covering six feeds instead of one: 222 strict TRAIN
   labels against 905. The model memorises (train loss 0.33, validation 2.8) and its own seeds solve fewer
   holdout cases (47) than the classical initializer (63). F0's neural-only count was 1.5x classical.

With the unsolvable family removed the classical rate is 306 / 909 = 33.7 %, the same as the old basis
(110 / 330 = 33.3 %).

## Where the 1,152 requests went

Classes are assigned from the input and the published failure text only.

| Class | All | TRAIN | Strict labels | Solver time |
| --- | ---: | ---: | ---: | ---: |
| A. No steam, zero reboiler duty, trays below the feed | 243 | 163 | 0 | 1,426 s |
| B. Path-dependent heat gate (`condensation-capped`, `not below the base condenser duty`) | 174 | 126 | 0 | 130 s |
| C. Typed `INFEASIBLE_SPECIFICATION` (liquid-supply screen) | 34 | 26 | 0 | 0 s |
| D. Open | 701 | 495 | 306 (222 TRAIN) | 6,512 s |

The same split on the two benchmark populations (block 1 of the framework qualification):

| Population | Class | n | Classical | Neural only | Neural first |
| --- | --- | ---: | ---: | ---: | ---: |
| Validation 168 | A | 28 | 0 | 0 | 0 |
| | B | 23 | 0 | 8 | 8 |
| | C | 2 | 0 | 0 | 0 |
| | D | 115 | 44 | 37 | 57 |
| Holdout 252 | A | 52 | 0 | 0 | 0 |
| | B | 48 | 0 | 7 | 7 |
| | C | 6 | 0 | 0 | 0 |
| | D | 146 | 63 | 40 | 69 |

Re-based on requests that are experiments (B + D): validation 44 -> 65 of 138 (31.9 % -> 47.1 %), holdout
63 -> 76 of 194 (32.5 % -> 39.2 %).

## Findings

### DG1 (critical) - variant 0 is a degenerate column family

`make_holdout*.py` (and by its journal the lost generator of `requests.jsonl`) overrides every variant-0
request with `{"watts": 0}`, no steam, no equipment, feed temperature 600-680 K. `make_input` places the
feed at 74-100 % of the column height, so 231 of 378 variant-0 requests have trays below the feed. Those
trays receive liquid and no vapour at all: pressure rises downward, the liquid is subcooled, V = 0, and the
log-flow MESH formulation has no representation for it. Result 0 / 231; the failures are
`Stage continuation stalled`, `no admissible Armijo-reducing step` and `banded LU zero pivot`.

Verified by probe (`research/convergence-review/probe-zero-boilup/`, 10 workers, 30 s, 40 TRAIN requests
picked by input hash):

| Arm | Strict |
| --- | ---: |
| Original request | 0 / 40 |
| Same request, reboiler duty 3 MW | 34 / 40 |
| Same request, trays below the feed removed (`stageCount = feedStageNumber`) | 15 / 40 |

Consequences: 163 TRAIN requests and 956 s of solver time produce no label; 28 validation and 52 holdout
rows are fixed zeros in every arm; and the ordinary plain column **with** boil-up, which converges 85 %
classically and is the cheapest label source there is, is absent from the set. `make_input` on its own
draws the reboiler duty from 0 to 14.7 MW and would not have produced this family.

Product note: a player who removes the stripping steam from a preset (all six presets are `watts: 0` plus
steam) lands in exactly this family and waits about 6 s for `NONCONVERGENCE`. A request-only verdict
("no vapour source below the feed") or the exact reduction (drop the inert trays, solve, publish them as
liquid-only pass-through) would fix the game and the benchmark together. The reduction alone recovers only
the 37-40 % that a feed-at-bottom rectifier without boil-up reaches.

### DG2 (major) - the pressure-boundary probes test DG1, not pressure

The twelve `supported-pressure-boundary` requests are the production presets with steam, draws and
pumparounds removed, `watts: 0`, feed on stage 37 of 40, at 50 and 75 kPa. They are class A. At 250 kPa the
same six inputs converge 0 / 6; with 3 MW of boil-up the 50/75 kPa probes converge 5 / 12. So they say
nothing about the pressure boundary.

They still widen the shipped envelope. `train.py` takes `globalMin` / `globalMax` over **all** requests and
writes `minimumNodePressurePascal: 50000`, while every strict label lies in 100.8-299.8 kPa. `supported()`
therefore admits 50-100 kPa requests for which the model has no label at all. Either label that band with a
solvable family or set the bound to the labelled 100 kPa.

### DG3 (critical) - equipment count is aliased with stage count and crude

```
pas   = (n + k + variant) % 5
draws = (n + k) % 4          # k = preset index, n = stage count
```

For a given crude and steam setting a structural cell recurs only every 20 stage counts, so each
(crude, pumparounds, draws, steam) cell holds about three requests in the whole set and two in TRAIN. A
validation request's nearest TRAIN neighbour with the same crude and cell is always 20 stages away. The
model cannot separate the effect of stage count from the effect of equipment, and it is never asked to
interpolate between neighbours of one cell. The F0 design enumerated all 2,520 cells
(63 x 2 x 5 x 4) with replicates; this one visits 1 / 20 of them per crude.

The production cell shows the cost. TRAIN requests with 3 pumparounds, 3 draws and steam:

| Crude | Stage counts present (label = *) |
| --- | --- |
| Tia Juana | 11, 51 |
| WTI Light | 30*, 50 |
| Upper Zakum | 9, 29 |
| Bonga | 8, 48 |
| Dalia | 27, 47 |
| Cold Lake | 6, 26, 46 |

Thirteen requests, one label. The six exact presets were registered as validation rows, so nothing near the
configuration every player starts from is in TRAIN. Outcome on those six under the packaged model:

| Preset | Classical | Neural only | Neural first |
| --- | --- | --- | --- |
| Dalia | 3.6 s | solved, 1.2 s | 1.2 s |
| Upper Zakum | 1.4 s | solved, 0.7 s | 0.8 s |
| Bonga | 3.9 s | solved, 1.0 s | 0.8 s |
| Tia Juana | 2.1 s | `INITIALIZATION_FAILURE` | 4.1 s |
| WTI Light | 3.0 s | `INITIALIZATION_FAILURE` | 4.6 s |
| Cold Lake | 22.5 s | `INITIALIZATION_FAILURE` | 23.8 s |

On three of six presets the shipped default is slower than classical by the full neural allowance. Four of
the six (Dalia, Tia Juana, Cold Lake, and Upper Zakum under classical) are accepted only with the
`DRY_SUPERSATURATED` advisory, so they are not strict labels either.

The holdout uses the same rule, so it measures the model on the same thread and cannot reveal this.

### DG4 (major) - the set is too small for six feeds

810 TRAIN requests give 222 labels, about 37 per crude and 12 per crude x variant (Dalia variant 0 has 2).
Training shows it: train loss 0.33 against validation 2.7-2.8, still falling at epoch 160. Native evidence:
neural-only 45 / 168 and 47 / 252 against classical 44 and 63. The initializer adds cases mostly where the
classical path is refused by a heat gate (8 of its 21 validation gains, 7 of 13 on the holdout), not by
being a better seed on open cases.

Labelling is not the constraint: the 1,152-request sweep took 819 s on ten workers and a fit takes 9 s per
seed. A 6,000-request design costs about 70 minutes.

### DG5 (major) - composition coverage is six points and six line segments

Variants 0 and 2 use the exact preset composition; variant 1 blends crude k with crude k+1 only. 383
distinct compositions in 1,152 requests, all on a ring of six segments in a 19-dimensional simplex. The
reader enforces it: `V3AnchorTransformerInitializer.compositionSupported` requires the feed to lie within
1e-8 of one of the six registered edges. A blend of two non-adjacent crudes (Tia Juana with Bonga), any
three-way blend, a feed edited by one component in the GUI, or a stream that came out of another column is
refused and runs classical. With the fluid network mixing crudes in tanks that will be the common case. F0
sampled a bounded full-dimensional neighbourhood of its baseline. At minimum sample the full six-crude
simplex (Dirichlet weights) and register the convex hull, not the ring.

### DG6 (minor) - requests the game refuses were generated and kept

The guide's `--request-only-screen-ratio 0.30` was not applied: 34 requests (26 TRAIN, 2 validation,
6 holdout) are typed infeasible in microseconds. Class B (174, 15 %) is the path-dependent heat gate. Those
are **not** removable: the neural seed solves 8 of 23 in validation. They are zero-yield under
classical-only labelling (decision 6B) and are the first place a salvage sweep would pay. Their density
comes from sampling feed temperature (511-766 K) independently of 34-56 MW of authored pumparound cooling:
below 560 K only 12 of 117 pumparound requests converge.

### DG7 (minor) - reproducibility

The generator of `requests.jsonl` is not in the research directory; only the two holdout twins are.
`registration.json` binds the seed and the file hash but the file cannot be regenerated. The holdout twins
draw factors with independent `rng.random()` calls, not the strength-two orthogonal-array Latin hypercube
of `generalized_design.generate`, so two-factor coverage is whatever chance gives at n = 252.

## What a corrected design looks like (inputs only, no outcome-based selection)

1. Drop the variant-0 override. Plain columns come from the ordinary cell (0 pumparounds, 0 draws, steam on
   or off) with `make_input`'s own reboiler and reflux ranges.
2. Cross the structural cells with the crude instead of deriving them from `n + k`: stage count x steam x
   pumparound count x draw count, sampled uniformly per crude with replicates.
3. Add a preset-neighbourhood family in TRAIN: production equipment layout scaled to 36-44 stages at the
   train-fold stage counts, every continuous factor within +-10-20 %.
4. Compositions: pure crude, two-crude blends over all 15 pairs, and Dirichlet blends of all six; register
   the hull.
5. Apply the request-only screen at generation. Keep class B inputs; they are acquisition targets.
6. Five to ten thousand requests. Fold rule unchanged (`stage % 7`).
7. Keep the generator script and register its hash with the request file.
8. Pressure probes: keep steam or boil-up so they test pressure, and only widen the envelope to a band that
   holds labels.

Part 2 records the measured effect.

---

# Part 2 - measured effect of a corrected design (2026-09-18)

Everything ran in the worktree `.claude/worktrees/crude-regrouping-plan-review-28f25a` on `4e84f0e`
(unmodified production code except where stated), ten workers, 30 s deadline, 2 s neural allowance, no
overlapping campaigns. Scripts, journals and models: `research/convergence-review/`. Architecture, loss,
optimiser, seeds and checkpoints are Codex's `train.py` unchanged; only the label set differs. Labels are
classical only (decision 6B).

## Designs

| Set | Generator | Requests | Strict labels | Notes |
| --- | --- | ---: | ---: | --- |
| Original | lost; twin `make_holdout_fixed.py` | 1,152 (810 TRAIN) | 222 TRAIN | aliased equipment, 21 % zero-boil-up |
| `train-v2` | `design_v2.py`, seed 2026091811 | 5,400 TRAIN | 1,629 | every (steam, pumparounds, draws) cell per crude x 20 replicates, stage count free in the TRAIN fold, no variant-0 override, 600 preset-neighbourhood requests, request-only screen at 0.30, ring compositions |
| `train-g` | `design_g.py`, seed 2026091837 | 2,160 TRAIN | 689 | same structure, **global composition**: Dirichlet(0.5) over all six crudes x log-normal(0.25) per component |
| `holdout-v2` | `design_v2.py`, seed 2026091823 | 312 | - | all cells, stage counts 2 to 64, 72 neighbourhood rows, ring compositions |
| `holdout-g` | `design_g.py`, seed 2026091841 | 312 | - | same with global composition |

Classical yield on `train-v2` 30.2 %. Plain columns (no equipment) now converge 80 % without steam and 63 %
with it, against 15.6 % for the original variant 0. Labels in the production cell (3 pumparounds, 3 draws,
steam): 207 against 1.

Arms: **B** = original + `train-v2` (1,851 labels). **B2** = B with the learning rate stepped to 8e-5 for
epochs 121 to 160. **G** = B + `train-g` (2,540 labels). Six B checkpoints were screened natively on Codex's
48-row panel in two reversed blocks (strict neural-first 18 to 20, packaged 20 to 21); `B-17011-80` was
selected by the registered rule.

## Results

Codex's validation population (168 rows, 53 of them fixed zeros):

| Pipeline | Classical | Neural only | Neural first | First / classical latency |
| --- | ---: | ---: | ---: | ---: |
| Packaged `regrouped-seed-17041-epoch-160` | 44 | 45 | 65 | 1.007 |
| `B-17011-80`, block 1 / block 2 | 44 / 44 | 45 / 45 | 64 / 64 | 0.942 / 0.951 |

Six production presets solved from the seed: packaged 3 (Tia Juana, WTI and Cold Lake fail and cost the 2 s
allowance), B 5 and 4 (WTI fails; Cold Lake sits on the 2 s wall).

Fresh full-cell holdout `holdout-v2` (312 rows, ring compositions, classical 106 strict):

| Pipeline | Labels | Neural only | Neural first | Latency ratio | Neighbourhood 72 rows: only / first (classical 31) |
| --- | ---: | ---: | ---: | ---: | --- |
| Packaged | 222 | 74 | 131 | 1.002 | 12 / 33 |
| `B-17011-80`, block 1 / reversed block 2 | 1,851 | 116 / 117 | 145 / 145 | 0.845 / 0.850 | 30 / 35 |
| `B-17023-160` (not screened) | 1,851 | 113 | 145 | 0.883 | 33 / 36 |
| `B2-17011-160` | 1,851 | 117 | 145 | 0.865 | 34 / 36 |
| `G-17011-80` | 2,540 | 91 | 133 | 0.913 | 30 / 34 |
| `G-17023-160` | 2,540 | 118 | 148 | 0.856 | 31 / 35 |
| `G-17041-160` | 2,540 | 119 | 143 | 0.862 | 33 / 36 |

No run lost a classical success except the packaged model (1). The gain holds in every crude, every
equipment count, every stage band, and on stage counts from the validation and test folds that TRAIN never
contains (neural-first 21 and 16 against 18 and 12).

Off-ring holdout `holdout-g` (312 rows, classical 109 strict). The reader refuses these compositions today,
so these three runs used an **uncommitted experimental bypass** of `compositionSupported` (composition
bounded by `globalMin` / `globalMax` only); the patch is reverted and the tree is clean.

| Pipeline | Neural only | Neural first | Latency ratio |
| --- | ---: | ---: | ---: |
| `B-17011-80` (ring-trained) | 33 | 124 | 0.975 |
| `G-17011-80` | 90 | 131 | 0.911 |
| `G-17023-160` | 107 | 140 | 0.882 |

## Reading

1. **Better convergence is obtainable, and the old validation set could not show it.** On the population the
   packaged model was designed around, 8x the labels change nothing in strict count (64 against 65). On a
   population that covers the game's input space the same retrained model adds 14 to 17 strict solutions
   (131 -> 145 to 148, of 312), raises what the seed solves alone from 74 to 113 to 119 (classical 106), and
   turns a latency wash (1.00) into a 12 to 15 % saving. Near the presets the seed alone goes from 12 to 30
   to 34 of 72.
2. **Global composition training costs nothing on the ring and is required off it.** `G-17023-160` is the
   best arm on the ring holdout (118 / 148) and the only usable one off the ring (107 / 140 against 33 / 124
   for the ring-trained model). `G-17011-80` shows the spread between checkpoints (91 / 133): the epoch-80
   checkpoint is undertrained at 2,540 labels, and native screening, not validation loss, has to pick.
3. **A low-rate final stage is neutral** (117 / 145).
4. **What now limits strict convergence is the corrector, not the data.** In every arm the failed seeds end
   at a scaled residual of 1.00 (median), starting from a median of 1.3 to 3.5. For `G-17023-160` on
   `holdout-v2` (143 failed seeds, 28 of them solved classically): the dominant residual is a component
   material balance in 59 % (vapour-liquid equilibrium 28 %, energy 13 %), it sits in the rectifying section
   in 60 %, and 68 % of the component-tagged ones belong to `crude_pc01` to `crude_pc07`, with physical
   magnitudes down to 1e-8 mol/s. That is a relative error of order
   one on a trace flow which Newton with the current support refresh does not repair: the trace-spike
   signature already documented for the classical path. On common successes the seed is 6.7x faster than
   classical (median 292 ms against 1,956 ms, n = 76); 135 requests that nothing solves cost 10 to 11 s each
   and dominate every mean.

## Caveats

- `holdout-v2` was looked at for seven retrained checkpoints, so it is consumed as a selection set. The
  spread among them (143 to 148) is noise; the margin over the packaged model (131) and the neural-only
  margin (74 -> 113 to 119) are not. A promotion decision needs a new holdout.
- One block per arm except `B-17011-80` (two reversed blocks on both populations, identical strict counts).
- No Python-against-Java parity run was made on these exports. The native numbers are what the production
  reader computed from the exported weights, which is what matters for a comparison, but promotion requires
  the parity gate, float64 fixtures and pins.
- A 10 s feature export overlapped the first seconds of the first panel screening run.
- Nothing was promoted and no production file was changed.

## Recommendations

1. Replace the packaged model through Codex's own qualification pipeline with a model trained on a corrected
   design. Start from the G recipe (`research/convergence-review/design_v2.py`, `design_g.py`,
   `train_v2.py`); candidates are in `research/convergence-review/models/`. Screen natively at epoch 160 and
   later; do not take epoch 80 at this label count.
2. Re-base the published numbers: drop the 53 / 58 fixed-zero rows or report per class, and replace the
   holdout by a full-cell one.
3. Give the sidecar a composition domain that works for any number of feeds (schema 3: per-component box or
   hull; the box is already carried by `globalMin` / `globalMax` positions 17 to 17 + c - 1). Without it a
   globally trained model is only served on the six ring segments.
4. Type or reduce the zero-boil-up family in the solver (DG1).
5. Set `minimumNodePressurePascal` and the pressure entry of `globalMin` to the labelled 100 kPa, or label
   50 to 100 kPa with a solvable family.
6. Next convergence lever, registered as its own study: make the decoded seed material-consistent before
   Newton (one tridiagonal component-balance sweep at the seed's temperatures and phase totals), or teach the
   corrector to drop and reinsert the offending trace component. The F0-era "material wrapper" result was
   negative on a different basis with lighter tails; the residual signature here is different enough to test
   again. **Measured the same day** (`V4_HYBRID_INITIALIZER_REVIEW.md`): the tridiagonal sweep is neutral and
   declines on 45 % of seeds; the local rule (lift a retained trace point to the equilibrium split of what
   its neighbours deliver, in `liftFloorSupport`) takes this model from 117 to 142 neural-only and 147 to 155
   neural-first on the same holdout with no retraining.
7. Salvage labels (one `LNN_FIRST` request per unlabelled TRAIN input) remain the only source for the
   heat-gate class, which holds 21 % of the holdout and where the seed already solves a quarter. That
   reopens decision 6B and is the user's call.

## Decisions (user, 2026-09-18)

Recommendations 1, 3, 4 and 7 were approved ("1-4 yes to all"): Codex retrains and requalifies on the
corrected global-composition design; the sidecar gets a box or hull composition domain; the zero-boil-up
family gets a solver verdict or tray reduction; labels may be harvested from `LNN_FIRST` solves for the
heat-gate class (decision 6B is settled that way). The user added two directions the same day: side-draw
requests blocked by liquid depletion are ill-designed (Part 3), and a combined mechanistic-neural initializer
is to be studied independently of the earlier attempts (`documentation/V4_HYBRID_INITIALIZER_REVIEW.md`).

---

# Part 3 - side draws blocked by liquid depletion (2026-09-18)

User direction: a side-draw request whose convergence is blocked because the authored draw exhausts the
liquid reaching its tray is ill-designed. This part measures that family over every journal of this review,
finds what generates it, and gives the generation rule. Every table and script is in
`research/convergence-review/liquid-depletion/` (`summary.md`, `summary.json`, `analyze.py`, `thermo.py`).

## DG8 (major) - draw rates are anchored to the preset, never to the liquid the column can deliver

**How the family is read.** For a request with side draws, `W` is the largest ratio of an authored draw to
the total liquid reaching its tray in the last state the solver produced: the `SIDE_DRAW_SPLIT` audit value
on an accepted or audit-rejected state, otherwise the `(withdrawal W)` annotation the solver appends to a
draw failure. Hard depletion is `W >= 1`, near depletion `0.8 <= W < 1`.

Two caveats govern every count below:

- `SIDE_DRAW_SPLIT` is an acceptance gate with limit 1, so "0 % success at `W >= 1`" is the definition of
  the gate, not a measurement of solvability. Only 27 of the 329 hard cases converged to a MESH state that
  the audit then rejected for non-positive downflow; the other 302 stalled, and their `W` is a property of a
  diverged iterate (values run to 5e42).
- The reading depends on the lane. In the holdout, 5 of the 42 requests whose worst lane reports `W >= 1`
  were solved strictly by another lane. For scoring, the family is therefore defined as **no lane solved
  the request strictly and the worst lane withdrew at least 0.8 of a draw tray's liquid**.

**Counts** (withdrawal from the classical journal; "unknown" = no state with a measurable withdrawal:
heat-gated, deadline-exceeded and typed-infeasible requests, 44 % of all draw requests, so every family
count is a lower bound):

| journal | draw requests | solved | hard `W >= 1` | near `0.8..1` | family | of the near band, solved | unknown `W` | family / draw failures with a known `W` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| design-v2 | 4,200 | 1,440 | 221 | 69 | 290 | 21 | 1,836 | 29.1 % |
| design-g | 1,680 | 587 | 68 | 38 | 106 | 15 | 740 | 25.8 % |
| Codex (1,152 requests) | 572 | 180 | 40 | 15 | 55 | 9 | 264 | 35.9 % |
| pooled | 6,452 | 2,207 | 329 | 122 | 451 | 45 | 2,840 | 29.4 % |

Of the "D open" draw failures (not zero-boil-up, not heat-gated, not typed infeasible) the family is 14.9 %
in design-v2, 13.1 % in design-g and 19.3 % in Codex's design. Solvability decays smoothly from `W` of
about 0.4 upward; 30 to 60 % of requests that end within 20 % of exhausting a tray still solve, which is why
an aggressive screen costs real labels.

**On the fresh holdout** (312 requests, 252 with draws; family read over all six evaluation runs of Part 2):

| definition | n | of which `W >= 1` |
| --- | ---: | ---: |
| worst lane `W >= 0.8`, solved or not (the Part 2 reading) | 61 | 42 |
| no lane in any run solved it and worst lane `W >= 0.8` (scoring definition) | 80 | 66 |

Excluding the 80 changes no strict count (nothing solves them) but it changes the latency comparison,
because each of them costs 10 to 12 s on every route (the classical route runs to its stall or the 30 s
deadline; neural-first pays the seed and then the same):

| run | neural-first / classical, all 312 | same, without the family (n = 232) |
| --- | ---: | ---: |
| packaged `regrouped-seed-17041-epoch-160` | 1.00 | 0.97 |
| `B-17011-80` | 0.85 | 0.69 |
| `G-17023-160` | 0.86 | 0.72 |
| `B-17023-160` | 0.88 | 0.75 |
| `G-17041-160` | 0.86 | 0.73 |

**What generates it.** `generalized_design.make_input` sizes every draw at 80 to 120 % of the preset's own
withdrawal while independently sampling the feed rate, the condenser outlet temperature (0.8 to 1.2 x),
the reflux ratio (0 to 10), the reboiler duty, the feed temperature and the pumparound duties, and it places
draw trays uniformly on 1..N. Nothing couples the withdrawal to the liquid that has to supply it. Family
rate by quartile of each knob (design-v2 / design-g / Codex):

| knob | lowest quartile | highest quartile | reading |
| --- | ---: | ---: | --- |
| condenser outlet temperature | 6.9 / 6.0 / 10.4 % | 22.3 / 21.3 / 36.4 % | hot condenser: little condensate, small `R x D` |
| feed vapour fraction (ideal flash) | 25.7 / 21.7 / 33.8 % | 5.9 / 8.1 / 7.8 % | cold feed: the feed is not the vapour source |
| draws / feed | 5.6 / 4.3 / 3.9 % | 18.1 / 19.6 / 41.6 % | monotone |
| reflux ratio | 19.3 / 18.3 / 28.6 % | 9.8 / 7.7 / 16.9 % | only the lowest quartile stands out |
| pumparound cooling | 14.2 / 15.7 / 18.8 % | 11.2 / 6.0 / 13.0 % | inverted: condensate is liquid |
| reboiler duty | 5.6 / 4.7 / 15.6 % | 12.7 / 17.0 / 11.7 % | weak, not monotone |

The shallowest draw on tray 1 to 3 carries about twice the family rate of a draw below tray 10 in all three
journals: a draw there can only take from the reflux itself. The corner is a hot condenser with a cold feed,
low reflux, many or large draws and few pumparounds; it is not the low-duty, high-cooling corner one would
guess.

**Request-only screen.** The shipped `V3LiquidSupplyScreen` statistic `rho` fires on nothing in design-v2
and design-g (both were redrawn until `rho < 0.30`; measured maxima 0.29998 and 0.29767) and reaches 4.6 %
hard recall at a 0.5 % false-positive budget. Its supply bound uses `D_max = F + S - draws`, which is
generous by an order of magnitude when the condenser is hot. The energy-limited statistic that works:

```
lambda = 60 kJ/mol
vf     = ideal vapour fraction of the authored feed at the feed stage (one flash, no solve)
V_gen  = max(0, Q_reboiler / lambda + vf * F)          # steam is not credited: it leaves as free water
L(T)   = R / (R + 1) * V_gen + coolingAbove(T) / lambda + (1 - vf) * F [if T >= feed tray]
sigma  = max over draw trays T of (authored draws on trays 1..T) / L(T)
```

Pumparound cooling is credited as liquid and never subtracted from the vapour budget: a cooler removes
mostly sensible heat, and what it condenses flows down as tray liquid. Subtracting it (the first form
tried) clamps the vapour budget to zero on 30 to 69 % of solvable requests and gives a coin-flip screen.

| rule (pooled, 2,613 draw requests with a known `W`) | hard recall | near-depletion failure recall | false positives on solved requests |
| --- | ---: | ---: | ---: |
| `sigma >= 0.82` | 11.6 % | 2.6 % | 0 / 2,207 |
| `sigma >= 0.66` | 22.5 % | 14.3 % | 11 / 2,207 (0.50 %) |
| `sigma >= 0.55` | 35.3 % | 23.4 % | 45 / 2,207 (2.0 %) |
| `rho >= 0.241` (same false-positive budget) | 4.6 % | - | 0.50 % |

Area under the curve against solved requests: `sigma` 0.840, `rho` 0.740. The worst solved `sigma` is
0.812 / 0.708 / 0.657 per journal, so the statistic transports across three independently authored designs.
On the holdout, `sigma >= 0.66` fires on 14 of 252 draw requests, catches 9 of the 42 worst-lane-hard cases
and hits 2 of the 101 strictly solved ones. A 23-feature logistic model reaches only 27 to 33 % recall at the
same false-positive budget, and a direct calibration of tray liquid against request-only terms fits with
R² of 0.04 (0.34 in log space): **the ceiling for any request-only screen is about a third of the family**,
because the distillate rate is a strongly nonlinear function of the condenser specification and needs the
property package. The screen is a filter, not a solution; the generator is where the family is removed.

**The user's rule, measured (2026-09-18).** "Large side draws need appropriate pumparound cooling above them
to prevent depletion." With `kappa(T) = coolingAbove(T) / (lambda x cumulative draws on trays 1..T)` at
`lambda = 60 kJ/mol` and `kappa_req` the minimum over a request's draw trays (pooled, 3,612 draw requests
with a known `W`; `research/convergence-review/liquid-depletion/kappa-tables.md`):

| `kappa_req` | n | solved | hard `W >= 1` | family rate |
| --- | ---: | ---: | ---: | ---: |
| 0 (no cooling above any draw) | 1,690 | 51.2 % | 13.7 % | 16.7 % |
| (0, 0.25] | 87 | 48.3 % | 13.8 % | 16.1 % |
| (0.25, 0.5] | 167 | 59.9 % | 12.0 % | 12.6 % |
| (0.5, 1] | 411 | 68.1 % | 7.5 % | 10.2 % |
| (1, 2] | 907 | 75.9 % | 3.1 % | 4.1 % |
| (2, 4] | 255 | 65.1 % | 2.4 % | 3.9 % |
| > 4 | 95 | 68.4 % | 0.0 % | 0.0 % |

The structural form alone (a cooling zone lying entirely at or above every draw tray) gives a family rate of
3.9 % against 14.2 % without one. It is not a pumparound-count artifact: at a fixed count of 1 / 2 / 3 / 4
pumparounds the family rate is 5.0 / 4.6 / 2.7 / 6.2 % with `kappa > 1` and 15.7 / 23.2 / 23.6 / 15.5 % at
`kappa = 0`. The counterweight is the condensation cap: over all 6,452 draw requests the heat-gate rate is
20.3 % at `kappa = 0`, 16.7 % in (1, 2] and 36.4 % above 2, and the solved rate peaks at 48.2 % in (1, 2].
`kappa` is a generation constraint, not a screen: 39 % of solved requests also have `kappa_req = 0`, so as a
rejection rule it has 41 to 58 % false positives; and `sigma >= 0.66` implies `kappa_req <= 1` on every
request it fires on, so the two are the same physics with `sigma` as the duty-weighted refinement. Cooling
prevents depletion; it cannot rescue a draw already larger than the column's liquid (at `sigma >= 0.66` the
family rate is 51 % with or without cooling). Draws on tray 1 can never have a zone above them (83.7 % of
tray-1 draw requests have `kappa_req = 0`), which is most of the tray 1 to 3 penalty above.

## Recommendations (continuing the Part 2 list)

8. **Couple every side draw to pumparound cooling above it (user rule).** For each side draw of rate `d`
   on tray `T` place a cooling pumparound with `returnTray <= drawTray <= T` and `|duty| >= kappa_min x
   lambda x d`, `kappa_min = 1.0`, `lambda = 60 kJ/mol`; keep the cumulative cooling above any draw tray
   below about `2 x lambda x` the cumulative draws above it so the request does not cross the condensation
   cap; place no draw on tray 1. Size the draw itself as `draw_T = phi x L(T)` with `phi` sampled on
   [0.05, 0.45] and `L(T)` as above. In `make_input` this replaces both `baseline_draw * (0.8 + 0.4 * u)` and
   the independent sampling of pumparound zones and duties; the withdrawal fraction and the cooling ratio
   become designed factors with known coverage. Today 35.4 % of the draw population meets `kappa_req >= 1`
   and 17.3 % carries no cooling pumparound at all.
9. **Keep `sigma` as the backstop.** Add it to `generalized_design.request_only_exclusions` beside `rho`,
   redraw at `sigma >= 0.66` (0.82 for zero measured false positives), publish the ratio and the limiting
   tray, and re-measure the worst solved value whenever the preset set changes. Port the same statistic to
   the Java request-only admission if the game should refuse these requests fast.
10. **Report the family as its own benchmark class**, defined as in the scoring definition above, exactly as
    the heat-gate class is reported, and exclude it from latency denominators: it moves the neural-first to
    classical ratio from 0.85 to 0.71 on the holdout and is a property of the request, not of the route.
11. Open, solver side: re-solve the 329 hard cases under several initializers with the `SIDE_DRAW_SPLIT`
    tier disabled to settle how many are truly infeasible (only 27 carry proof today), and give
    `V3LiquidSupplyScreen` a necessary tier above `rho >= 1` from a condenser flash with the production
    property package.
12. Open, design side: whether cooling placed immediately above a draw beats cooling anywhere above it, and
    whether the (1, 2] optimum survives once the rule is enforced, are observational readings of designs
    that never conditioned cooling on draws; the first generated design under rule 8 should carry a pilot
    (guide section 5) that contrasts the two placements.

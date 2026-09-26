# Training the Transformer column initializer: from raw data to deployment and iteration

Revision 2.1, 2026-09-18. Written against `codex/crude-regrouping` @ `4e84f0e` (registry, payload + sidecar,
shape-derived reader). Revision 1 (2026-09-17) described the single 20-component F0 artifact on `main`; what
it measured is kept in section 13. Revision 2.1 adds the side-draw sizing rule and the `sigma` screen
(sections 4.1, 4.4) and the liquid-depleted-draw outcome class (section 6.1), from Part 3 of
`CRUDE_REGROUPING_DATA_GENERATION_REVIEW.md`; the seed-preparation study of
`V4_HYBRID_INITIALIZER_REVIEW.md` is referenced from section 12.

**Why this revision exists.** Revision 1 was followed for the regrouped 19-component, six-crude basis and
produced a model that passed its gates while solving fewer holdout cases from its own seeds than the
classical initializer (47 against 63). The cause was in the places where the guide was silent because it
assumed one frozen basis and one frozen design tool: request design, design audit, data sufficiency, first
model gates, envelope, and the usage panel. The full analysis is
`CRUDE_REGROUPING_DATA_GENERATION_REVIEW.md`. This revision closes those gaps and states the procedure once,
for **any number of components and any number of feeds**. Nothing below may depend on a literal component
count, a literal width, or a hand-written list of feeds.

Related: `V4_TRANSFORMER_INITIALIZER_REVIEW.md`, `V4_IMPLEMENTATION_RESULTS.md`,
`V4_LNN_ONLY_GAP_ANALYSIS.md`, `V4_BENCHMARK_POPULATION_REVIEW.md`,
`CRUDE_REGROUPING_DATA_GENERATION_REVIEW.md`.

---

## 0. The contract, written for c components

| Item | Value |
| --- | --- |
| Shipped artifacts | `data/createcheme/neural/registry.json` listing up to 16 entries, each a trained **payload** (weights, normalization, component axis) and a **sidecar** (eligibility and inference policy). `pipelineSha256 = sha256(sha256(payload) + ":" + sha256(sidecar))`. |
| Loader | `V3NeuralModels.bundled()` -> `V3NeuralRegistry.read` -> `V3TransformerArtifact.read` -> `V3AnchorTransformerInitializer.read` |
| Widths (all derived, `V3GeneralNeuralFeatures` / `V3FactorizedNeuralFeatures`) | global `54 + c`; node `76 + c`; output `4c + 5`; joined input `(76 + c) + (4c + 5) + 3 + 1 = 5c + 85` |
| Output layout | temperature, `log1p(L/F)`, `log1p(V/F)`, 2 x c centred log composition ratios, `log1p(freeWater/(1e-8 F))`, wet flag, 2 x c presence logits |
| Architecture | embedding `5c + 85 -> 64`, two pre-norm self-attention blocks (4 heads of 16, FFN 128, GELU, dropout 0), LayerNorm + linear `64 -> 4c + 5`, branch head `54 + c -> 64 -> 3` |
| Parameters | `67,395 + 64(5c + 85) + 65(4c + 5) + 64(54 + c) = 76,616 + 644c` (c = 19: 88,852; c = 20: 89,496). The reader rejects any other count. |
| Eligibility (`V3NeuralRegistry.bind`, then `supported`) | component axis, `MaterialCatalog.physicsFingerprint(packageId, axis)`, formulation revision, 2 to 64 trays, at most 4 pumparounds / 3 draws / 2 steam feeds, pressure limits, `UNIFORM` splits, steam at the sump, the `globalMin` / `globalMax` box, and the composition domain |
| Policy fixed by the sidecar | decoder `ZERO_PHASE_FLOOR_10`, candidate rule `SINGLE`, correction rule `PROGRESS`; presence threshold 0.02, trace floor 1e-10 |
| Ceiling | `V3ComponentBasis.MAX_COMPONENTS = 64`; payload at most 8 MiB, sidecar 256 KiB |

**The model is a seed generator, not a solver.** Strict qualification belongs to the unchanged Newton
corrector, acceptance audit, final certificate and water qualification. So:

1. The quantity you optimise is **strict native convergence under the fixed budget**, not profile error.
2. The unit of measurement is a **pipeline**: weights + sidecar policy. Changing either requires
   requalification.
3. The physics fingerprint covers property science, the interaction sub-matrix, NRTL pairs, the water model
   and the package limits on the model's axis. **Any property change invalidates every model bound to that
   fingerprint**; the registry then finds nothing and the game runs classical.

---

## 1. One global procedure

These rules are what makes the approach independent of how many compounds or feeds exist.

1. **A basis descriptor drives everything.** `basis.json` holds the ordered component ids, the physics
   fingerprint, the feature and anchor revisions and the four derived widths. Java writes it from the
   catalog; every script reads it and derives `c = len(components)`. A literal 19, 20, 74, 85, 96 or 185 in
   a training or qualification script is a defect. (`tools/neural/train_transformer.ColumnModel` still
   defaults to `inputs=96, outputs=85` and `nn.Linear(74, 64)`; pass the derived widths, as
   `research/crude-regrouping/training/train.py` does.)
2. **One model per physics axis, not per feed.** Train on the union axis of every package that shares the
   physics. Feeds differ only in composition, and composition is an input.
3. **Feeds are discovered, not listed.** The feed set is every assay / column preset the catalog registers
   for packages whose `physicsFingerprint(packageId, axis)` equals the basis fingerprint. A new crude joins
   the next design automatically.
4. **Composition is sampled over the whole domain of the basis**, not at the presets (section 4.3).
5. **Exact zeros are part of the domain.** `V3ActiveComponentBasis` removes zero-feed components exactly, so
   a request with some components absent is a smaller problem on a sub-axis. Sample zero patterns in TRAIN
   (section 4.3) so the model has seen them. Serving a request whose own axis is smaller or larger than the
   model's is the job of the sidecar flags `allowMissingZeroComponents` / `allowExtraZeroComponents`; both
   are `false` today and need their own qualification before being switched on.
6. **Adding a compound** changes the axis and therefore the fingerprint of the packages that contain it.
   Packages without it keep their fingerprint and keep their model. For the new axis: widen the old model
   with zero-initialised slots (section 8.3), prove the predictions on old inputs are unchanged, then
   continue training on old labels (new slot zero) plus new labels in which the compound is present. Retire
   the old registry entry only after the widened model passes the old populations.
7. **Size scales with the domain, not with the clock.** Labelling costs about 0.7 s per request on ten
   workers and a fit takes two minutes per seed at 1,900 labels. There is no reason to train on a few
   hundred labels.

Tooling status (2026-09-18):

| Need | Status |
| --- | --- |
| Shape-derived reader, registry, payload + sidecar | Done on `codex/crude-regrouping` |
| c-generic features, targets, anchors, probe, from-scratch fit | Done, but untracked: `research/crude-regrouping/training/` (Codex worktree) |
| c-generic, multi-feed design generator | `research/convergence-review/design_v2.py`, `design_g.py` (this review's worktree). `tools/neural/generalized_design.generate` still raises unless c = 20 and samples around one baseline; its `make_input`, `digest`, `split_for` and `request_only_exclusions` are generic and are reused. |
| Feed discovery from the catalog | Missing. `presets.json` is exported from `ColumnInputPreset.values()`. |
| Composition domain in the sidecar | Ring of blend segments only (`compositionEdges`, at most 128). A box or hull domain is missing (section 9.2). |
| Request-only verdict for the zero-boil-up family | Missing (section 6.1). |
| Axis-widening tool with identity proof | Missing. |

Promote the first three rows into `tools/neural-global/` before the next campaign so that a campaign is a
configuration, not a rewrite.

---

## 2. Pipeline map

| # | Stage | Output |
| --- | --- | --- |
| 1 | Basis and feeds | `basis.json`, `presets.json` |
| 2 | Design of experiments | `requests.jsonl`, registration with generator hash |
| 3 | **Design audit and pilot** | per-family yield table, signed off before the full sweep |
| 4 | Classical labelling | `cases.jsonl` with native profiles and every failure |
| 5 | Optional acquisition with the current model | extra strict TRAIN labels |
| 6 | Remove useless points | curated TRAIN set, classed benchmark populations |
| 7 | Freeze dataset, usage panel and holdout | `features.jsonl`, panel, holdout manifest |
| 8 | Register, then train | checkpoints |
| 9 | Export and parity | payload, sidecar, fixtures, `precision-check.json` |
| 10 | Native screening, validation, holdout | journals, selection, gate verdicts |
| 11 | Deployment | registry entry, pins, green suite, in-game check |
| 12 | Iterate | gap analysis on the new failures |

---

## 3. Environment and ground rules

**Toolchain.** JDK 21. Python 3.12 with torch 2.10.0+cu128 and numpy 2.3.5; the working environment is
`C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-venv/Scripts/python.exe`. The Codex runtime Python
has numpy but no torch and is enough for design generation. `python` on PATH is the WindowsApps stub.
Training asserts CUDA, float32, TF32 off, deterministic algorithms, `CUBLAS_WORKSPACE_CONFIG=:4096:8`.

`tools/` and `research/` are gitignored. A fresh worktree has neither: copy `tools/` from the main checkout
and the campaign directory from its owner before building. The campaign Java probes compile through an init
script (`-I research/.../training.init.gradle`, task `regroupingTraining`, `-PtrainingMain`,
`-PtrainingArgs`).

**Rules every campaign obeys.**

- Ten owned worker threads, `maximumInFlight` 10, 30 s request deadline, 2,000 ms neural allowance, 16 base
  iterations. Timings from another worker count are not comparable.
- Never overlap two native campaigns, or a campaign and a Gradle suite. Cases on the 2 s wall flip under
  contention. GPU training does not overlap a timing campaign either.
- Outputs are immutable. Writers refuse an existing directory. A partial journal is evidence.
- Register before you measure: cohorts, normalization, architecture, seeds, checkpoints, selection rule,
  budgets, source hashes, **and the hash of the generator script**. Keep the generator; a request file that
  cannot be regenerated is not registered.
- Every dataset, journal and model is identified by the SHA-256 of its bytes. `.gitattributes` pins
  `*.jsonl` and neural resources as binary.

---

## 4. Stage 2: design of experiments

### 4.1 Structure

Enumerate the structural cells and cross them **with every feed**:

```
stage count (2..64) x steam (off, on) x pumparounds (0..4) x side draws (0..3)   = 2,520 cells per feed
```

Sample cells uniformly per feed with replicates; draw the stage count independently of the equipment
counts. **Never derive one structural factor from another** (the regrouped campaign used
`pas = (n + k + variant) % 5`, `draws = (n + k) % 4`, which visits 1 / 20 of the cells per feed and makes
stage count and equipment inseparable). Continuous factors come from `generalized_design.make_input`
unchanged: feed flow and temperature, condenser temperature, top pressure, pressure drop, reflux 0 to 10,
reboiler duty 0 to `0.2 x base feed x 100 kJ/mol`, steam rate and temperature, pumparound placement and
duties. Use a space-filling plan (the strength-two orthogonal array of
`generalized_design.orthogonal_latin_hypercube`) when the factor count allows it; independent uniform draws
are acceptable above about 5,000 requests.

**Side draws and pumparounds are coupled, never sampled independently (revision 2.1, user rule).** A large
side draw needs pumparound cooling above it: the liquid it withdraws has to be condensed on the trays
above, or the tray runs dry and no steady state with positive downflow exists. `make_input` sized each draw
at 80 to 120 % of the preset's own withdrawal, placed draw trays uniformly on 1..N and sampled pumparound
zones and duties independently of the draws. That produced the liquid-depletion family: 7 % of all draw
requests and one in seven of the otherwise open draw failures, concentrated where a hot condenser meets a
cold feed, low reflux, many draws and no cooling above them (`CRUDE_REGROUPING_DATA_GENERATION_REVIEW.md`,
Part 3). Measured with `kappa = coolingAbove(T) / (60 kJ/mol x cumulative draws on trays 1..T)`: family rate
16.7 % with no cooling above the draws, 4.1 % at `kappa` in (1, 2], 0 % above 4; beyond about 2 the
condensation cap takes over, so (1, 2] is the joint optimum. The rule for any generator:

```
for each side draw d on tray T:
    place a cooling pumparound with returnTray <= drawTray <= T   (zone entirely above the draw)
    |duty| >= 1.0 x 60 kJ/mol x d                                 (kappa_min = 1)
keep cumulative cooling above any draw tray below ~2 x 60 kJ/mol x cumulative draws above it
no draw on tray 1; size the draw as phi x L(T), phi in [0.05, 0.45], L(T) from section 4.4
```

The withdrawal fraction and the cooling ratio become designed factors with known coverage. `sigma` of
section 4.4 stays as the backstop screen; `kappa` itself is not a screen (39 % of solved requests have no
cooling above their draws, so as a rejection rule it has 41 to 58 % false positives).

**No family may override the generator's physical ranges** without a pilot (section 5). The regrouped
variant-0 family forced `watts: 0` with no steam and produced 243 requests the formulation cannot represent.

Folds stay by whole stage count:

```
stage_count % 7 == 0 -> test, stage_count % 7 == 3 -> validation, otherwise train
```

### 4.2 Usage neighbourhood

What players run is the shipped presets and small edits of them. Add a family that keeps each preset's
equipment layout, scales tray numbers to a band of stage counts around the preset (train-fold counts only in
TRAIN), and moves every continuous factor by +-15 %. It is 10 % of the design and it is the difference
between solving four or five of six presets from the seed and solving three. The exact presets themselves go
to the **usage panel** (section 7), never to TRAIN.

### 4.3 Composition

For F discovered feeds with fractions `z_f` on the union axis:

```
w ~ Dirichlet(alpha = 0.5) over the F feeds        # pure, binary and many-way blends
z = sum_f w_f z_f ;  z_i *= lognormal(0, 0.25)      # leave the span of the assays
with probability p_zero: zero a registered light-end or heavy-end group exactly
renormalise
```

A component absent from every contributing feed stays exactly zero. This is one rule for any F and any c.
Keep a minority (about one third) of requests at the pure feeds and their pairwise blends, because those are
the most common game inputs. Record the weights and the zero mask in `design`.

### 4.4 Screens applied at generation

- `generalized_design.request_only_exclusions(input, 0.30)`: redraw the factors of the same cell when the
  liquid-supply screen would type the request infeasible. Note that its statistic `rho` bounds the supply by
  `F + S - draws` and fires on nothing once a design has been redrawn against it.
- **Energy-limited draw screen `sigma` (revision 2.1):** with `lambda = 60 kJ/mol`, `vf` the ideal vapour
  fraction of the authored feed at the feed stage (one flash, no solve), `V_gen = max(0, Q / lambda + vf x F)`
  (steam not credited), `L(T) = R / (R + 1) x V_gen + coolingAbove(T) / lambda + (1 - vf) x F` at and below the
  feed tray, and `sigma` the worst over draw trays of the cumulative authored draws on trays 1..T over `L(T)`:
  redraw at `sigma >= 0.66` (22.5 % of hard depletion caught at 0.50 % false positives on 2,207 solved
  requests; 0.82 catches 11.6 % with none). Re-measure the worst solved `sigma` whenever the preset set
  changes. It removes about a fifth of the family; the sizing rule of section 4.1 removes the rest.
- Envelope limits of the reader (pressure, splits, steam position, equipment counts).
- The structural-degeneracy rules of section 6.1.
- Disjointness by `generalized_design.digest(input)` from every earlier request file.

Do **not** screen on anything that needs a solve. Path-dependent heat-gate failures stay in the design: the
seed solves a quarter of them and they are acquisition targets.

### 4.5 Size

Plan for at least 250 strict TRAIN labels per feed and at least 1,500 in total, then check the learning
signals of section 9.4. At a classical yield of 30 to 35 % that is 5,000 to 6,000 TRAIN requests, about 70
minutes of labelling. Measured on the regrouped basis: 222 labels -> neural-only 74 of 312 on a fresh
holdout (classical 106); 1,851 labels -> 116.

---

## 5. Stage 3: design audit and pilot (new, mandatory)

Before the full sweep:

1. **Input audit.** Tabulate requests per feed x steam x pumparounds x draws, per stage band, and per
   family. Every cell must be populated in TRAIN for every feed. Tabulate label-independent ranges per
   family and compare them with what the game's GUI allows.
2. **Pilot.** Label a 5 % random sample (a few minutes). Tabulate strict yield per family and per structural
   factor. **Any family or factor level with zero yield, or a yield far below its neighbours, is explained
   before the full sweep**, by a probe that changes one input at a time. The zero-boil-up family would have
   been caught by a 60-request pilot; the probe that explained it took two minutes (0 / 40 as authored,
   34 / 40 with 3 MW of reboiler duty).
3. **Confounding check.** For each diagnostic family (pressure probes, boundary probes) confirm that the
   property it is named after is the only thing that differs from a solvable control.
4. Sign off the yield table in the registration.

---

## 6. Stages 4 to 6: labels and useless points

### 6.0 Labelling and acquisition

`V3GeneralTrainingProbe generate` runs `CURRENT_ONLY` and journals every input, failures included. A label
is a native state that passed `prepare_transformer_data.strict(row)`: success with `DRY_EQUILIBRIUM` or
`WET_EQUILIBRIUM`, the accepted seed's canonical input equal to the request, every audit check passed, a
real final Newton step at closure 1e-8. Advisory successes (`DRY_SUPERSATURATED`) are not labels. Never use
a network prediction as a label.

Acquisition with the current model (one `LNN_FIRST` request per unlabelled TRAIN input, strict results only,
TRAIN only, no retries) is the cheapest source of labels where classical is refused, in particular the
heat-gate class, which has zero classical labels by construction. The regrouped campaign was run
classical-only by decision; that is a valid choice and its cost is that class.

### 6.1 Requests that are not experiments

| Kind | Rule | Action |
| --- | --- | --- |
| Request-only typed infeasible | `V3ColumnCalculator.requestOnlyAdmission(input, 0.30)` | remove at generation |
| Outside the reader's envelope | `supported(input)` false by construction | remove at generation |
| **Structurally degenerate for the formulation** | no steam, reboiler duty 0, and at least one tray below the feed: those trays carry no vapour and the log-flow MESH cannot represent them | remove at generation until the solver types or reduces them; report as its own class if present |
| **Liquid-depleted side draw** (revision 2.1) | no route solved the request strictly and the worst route's last state withdrew at least 0.8 of the liquid reaching a draw tray (`SIDE_DRAW_SPLIT` value, or the `(withdrawal W)` annotation of the failure) | an outcome class, not a generation rule: report it as its own class like the heat-gate class and exclude it from latency denominators (it costs 10 to 12 s on every route); generation prevents it by the sizing rule of 4.1 and the `sigma` screen of 4.4 |

The third row is a solver gap as well as a data rule. A player who deletes the stripping steam from a preset
is in it and waits about 6 s for `NONCONVERGENCE`. Fix it in the solver (request-only verdict, or drop the
inert trays and publish them as pass-through) and delete the row.

### 6.2 Everything else (unchanged from revision 1)

- Deduplicate by canonical input hash; assert TRAIN is disjoint from validation, panel and every holdout.
- One profile per input by fixed priority; quarantine material root disagreements, never average them.
- Redundancy is measured with `tools/training-curation/curate.py` before anything is moved to reserve.
- Never drop by residual, iterations, time, solver path, model error or any holdout outcome.
- Never delete a never-solved TRAIN input; it is an acquisition target.

### 6.3 Benchmark denominators

Class every benchmark row from its input and its typed failure text, and report every count per class:

| Class | Meaning |
| --- | --- |
| A | structurally degenerate (section 6.1), if any survived |
| B | path-dependent heat gate (`condensation-capped`, `not below the base condenser duty`) |
| C | request-only typed infeasible |
| D | open |

Headline rates use B + D. A and C are fixed zeros for every arm and only dilute the signal.

---

## 7. Stage 7: freeze

- Normalization statistics from strict TRAIN labels only, column-balanced (`1 / nodes`).
- Anchors from the production `V3NativeAnchor` for every legal branch, exported by the feature probe
  (`RegroupingFeatures`). A changed `V3NativeAnchor.REVISION` invalidates anchors and model together.
- **Validation** = the validation-fold rows of the same design.
- **Usage panel** = the exact shipped presets plus about a dozen neighbourhood requests per preset at all
  stage counts. It is reported on its own at every gate (section 10.2).
- **Holdout** = a new seed of the **full** generator (every cell, every stage count, usage neighbourhood),
  generated after the candidate is frozen and never solved before. A holdout drawn from a narrower rule than
  the game's input space cannot reveal a design defect: the regrouped holdout reused the aliased rule and
  reported 63 -> 76 where a full-cell holdout shows neural-only 74 against classical 106.
- A consumed holdout is not blind again. Generate a new one per promotion decision.

---

## 8. Stage 8: train

### 8.1 Route

| Route | When |
| --- | --- |
| From scratch | new physics fingerprint (any property change), new output layout or target encoding |
| Continuation from the shipped model | same axis and fingerprint: new data, new auxiliary loss |
| Widen, then continue | axis extended by a compound (section 1, rule 6) |

### 8.2 From-scratch recipe (pattern: `research/crude-regrouping/training/train.py`)

AdamW, learning rate 8e-4, weight decay 1e-4, batch 32, complete shuffled passes, dropout 0, gradient clip 1,
160 epochs, three seeds, checkpoints at 80 and 160 in the regrouped campaign (above about 2,000 labels epoch
80 is undertrained: export 120, 160 and later instead), float32 CUDA, deterministic. Loss
`train_transformer.loss`: squared error on temperature, `log1p(L/F)`, `log1p(V/F)`, free water, wet flag with
weights 3, 5, 5, 0.5, 2; per phase `2 x KL` on present-phase fractions, `0.1 x` squared error on centred log
ratios, `0.2 x` presence cross-entropy; plus branch cross-entropy.

Assert before fitting that Java features and targets equal the Python twins (`global_features`,
`factor.targets`) on every label, that the axis equals `basis.json`, and that the parameter count equals
`76,616 + 644c`.

### 8.3 Structural changes keep the start point

`common.incumbent_model` appends zero columns; `initialization.py` proves raw outputs differ by less than
2e-4 and decoded masks, branches and invalid flags are identical on all TRAIN cases. An axis widening needs
the same proof with an index remap of the `c`-blocks in node features, global features, anchors and outputs,
normalization mean 0 and scale 1 for the new slots, and presence logits of the new component decoded absent
wherever its feed is zero (the decoder already masks by `feed > 0`).

### 8.4 Checkpoint selection

Validation loss does not pick the winner. Native strict `LNN_FIRST` on the screening panel does
(section 10.1).

---

## 9. Stage 9: export, envelope and parity

### 9.1 Files

Payload: `featureRevision`, `modelType`, `anchorLayout`, `baselineRevision`, `components`, `normalization`
(including anchor `bm`, `bscale`), `weights`. Sidecar: `payloadSha256`, `modelId`, `packageId`,
`propertyRevision`, `physicsFingerprint`, `formulationRevisions`, the two zero-component flags,
`branchesSeen`, thresholds, `globalMin`, `globalMax`, `designConstraints`, `compositionEdges`, and the three
policy names. Both have closed key sets; an extra field is a load failure.

### 9.2 The envelope must not exceed the labels

`supported()` admits what the sidecar declares, so every declared bound must be backed by labels.

- `globalMin` / `globalMax`: take them over the authored requests of **solvable families only**, then check
  each factor's labelled range against it. The regrouped sidecar declares 50 kPa from twelve probes that
  belong to the degenerate family while every label lies in 100.8 to 299.8 kPa.
- `designConstraints.minimumNodePressurePascal` likewise.
- **Composition domain.** `compositionEdges` lists blend segments and the reader requires the feed to lie
  within 1e-8 of one of them. That is correct for a model trained on a ring and wrong for a global model:
  a three-way blend, a one-component edit or a stream from another column is refused and runs classical.
  A model trained with section 4.3 needs a domain the reader can test for any F: the per-component box that
  `globalMin` / `globalMax` already carry (global features 17 .. 17 + c - 1 are the feed fractions), or a
  hull of registered vertices. This is a sidecar schema change (version 3) and a reader change; until it
  lands a global model can only be served on its registered segments.

### 9.3 Parity

Before any native run: Python against Java numeric parity on fixtures in float64, exact decoded masks and
branches, ten-worker shared-model bit parity, cancellation.

### 9.4 Data-sufficiency signals

None of these selects a checkpoint; each says whether the label set is large enough to bother.

| Signal | Starved (regrouped, 222 labels) | Adequate (1,851 labels) |
| --- | --- | --- |
| Train / validation loss at the end | 0.33 / 2.7 | 0.36 / 0.9 to 1.4 |
| Neural-only strict against classical on a full-cell holdout | 74 against 106 | 116 against 106 |
| Presets solved from the seed | 3 of 6 | 4 to 5 of 6 |
| Labels in the production cell, all feeds | 1 | 200+ |

If neural-only is below classical, stop and add data before qualifying anything.

---

## 10. Stage 10: native qualification

### 10.1 Sequence

1. Parity gate.
2. Screen every checkpoint on a fixed panel (usage panel plus validation rows chosen by input hash), two
   reversed blocks, `CURRENT_ONLY`, `LNN_ONLY`, `LNN_FIRST` (`V3GeneralTrainingProbe compare`).
3. Select by the registered rule: highest minimum strict `LNN_FIRST` across blocks, then lower pooled
   `LNN_FIRST` latency, then id. A 48-row panel separates candidates by one or two cases; treat ties as ties.
4. Freeze, then full validation in two reversed blocks, then generate and run the holdout.

### 10.2 Gates

Against an incumbent pipeline (same population, contemporaneous runs):

- strict `LNN_FIRST` improves in both blocks,
- the union of contemporaneous classical successes is preserved in both,
- pooled all-case `LNN_FIRST` mean does not regress beyond the baseline's between-block band.

For the **first model on a basis** there is no incumbent, and "better than classical alone" is too weak: the
regrouped model passed it while its seeds were worse than classical. Add:

- neural-only strict >= classical strict on the full-cell holdout (section 9.4);
- on the usage panel, `LNN_FIRST` is not slower than classical in aggregate, and every preset that classical
  solves is solved from the seed or listed as a named exception;
- `LNN_FIRST` mean latency <= 1.0 x classical on the holdout (a seed that helps pays for itself; 1.10 only
  allows a model that mostly fails to be promoted);
- every count reported per class A to D.

Two blocks are the same inputs twice. They measure repeatability, not 2n cases.

---

## 11. Stage 11: deployment

1. Copy payload and sidecar byte for byte into `src/main/resources/data/createcheme/neural/`; add or replace
   the `registry.json` entry with `modelId`, paths, `priority` and `pipelineSha256`. Several entries may
   coexist for different axes or fingerprints; the first eligible by priority wins and selection performs no
   inference.
2. Update the pins: `V3BundledRegroupedModelTest` (payload hash, model id, parameter count, float64
   fixtures `regrouped-predictions.json`), `V3NeuralRegistryTest`, the provenance record beside the artifact.
3. `.gitattributes` binary pin, then verify the committed blob hashes.
4. Full suite with no campaign running: `./gradlew.bat test --offline`.
5. Production-path campaign through the registry (`compare ... registry.json`): identical strict sets in
   both blocks; name any case within 10 % of the 2 s wall.
6. In-game check through the Minecraft MCP bridge: a preset solve reports the model id, a property override
   through `/datapack` + `/reload` makes the result stale and the registry unavailable, and removing it
   restores the model.
7. Saves and packets do not carry the model. No NBT or wire bump unless the input record changes.

A missing or invalid registry logs a warning and returns `UNAVAILABLE`: `LNN_FIRST` runs classical,
`LNN_ONLY` publishes a typed failure.

---

## 12. Stage 12: iterate

1. Gap analysis on the newest production-path journals: solved neural-only, classical rescues, solved by
   another arm, never solved; per class A to D and per structural cell.
2. Read **why** seeds fail before adding data (`events` of the neural attempt: initial and final scaled
   residual, dominant residual family, node and component). On the regrouped basis the failed seeds end at a
   scaled residual of 1.0 on a component material balance of a mid-range cut in the rectifying section, from
   a median initial residual of 1.3 to 3. That is a trace-level composition the corrector cannot repair, the
   same signature as the classical trace-spike stall. More labels of the same kind improve latency and
   neural-only counts and do not move it.
3. Pick one lever, register, acquire or curate, train paired arms, qualify, seal.
4. **Look at the solver's seed preparation before training anything (revision 2.1).** The component-balance
   crawl of point 2 is not a composition the corrector cannot repair; it is a retained trace point seeded
   below the material its neighbours deliver, and `liftFloorSupport` had no rule for it. Adding the lift (the
   mirror of `capOversizedPoint`) moved the same model from 117 to 142 neural-only and 147 to 155 neural-first
   on the fresh holdout with no retraining (`V4_HYBRID_INITIALIZER_REVIEW.md`). Two consequences for this
   procedure: the journal's `scaled residual: initial=` belongs to the last attempt, after a support
   refresh, and must not be read as a seed property; and the gates of section 10 are to be run with the
   production preparation rules of the day, because a preparation change moves the counts more than a
   checkpoint choice does.

---

## 13. What has been measured

### 13.1 F0 line (20 components, one feed, `main` before the regrouping)

| Lever | Result |
| --- | --- |
| Regularization and decoded-flow loss for profile accuracy | strict +10 / -7; precision is not the constraint |
| Trace-margin auxiliary loss | negative |
| Compact 3-coordinate anchors | worse than full anchors |
| 101 untargeted curated profiles | neutral |
| 4,640 against 4,160 updates | worse natively |
| Four layers | no replicated benefit |
| Hybrid residual on the anchor with a material wrapper | underperformed |
| Regime router | negative |
| Component-level decoder floor | 0 / 0 |
| Phase-level floor at ten support floors | positive, shipped |
| Progress-based correction budget, factor 1.0 | positive, shipped |
| Flat 48-iteration cap | negative |
| Ramp handoff after a failed neural attempt | +8 neural-only, fails latency, opt-in |
| Second decode candidate | +2 / +2 for +93 ms, opt-in |

Reference on the cleaned 330 inputs: classical 110, neural-only 164, neural-first 182, ceiling 214.

### 13.2 Regrouped basis (19 components, six crudes)

Fresh full-cell holdout, 312 requests, classical 106 strict (details:
`CRUDE_REGROUPING_DATA_GENERATION_REVIEW.md` part 2). Classical-only labels throughout.

| Lever | Neural only | Neural first | First / classical latency |
| --- | ---: | ---: | ---: |
| Packaged model: 222 labels, aliased design with a degenerate family | 74 | 131 | 1.00 |
| Corrected design, 1,851 labels, ring compositions (two blocks) | 116 / 117 | 145 / 145 | 0.85 |
| Same, unscreened second seed | 113 | 145 | 0.88 |
| Same plus a final stage at learning rate 8e-5 | 117 | 145 | 0.87 |
| Plus 689 global-composition labels (2,540), epoch 160, two seeds | 118 / 119 | 148 / 143 | 0.86 |
| Same at epoch 80 | 91 | 133 | 0.91 |

Off-ring holdout (three-way blends and per-component perturbation), 312 requests, classical 109, reader
composition check bypassed for the experiment: ring-trained 33 / 124, globally trained 107 / 140.

On Codex's original validation population the retrained model ties the packaged one (64 against 65 strict
neural-first) because that population follows the packaged model's own design thread and a third of it is
fixed zeros. Presets solved from the seed: 3 of 6 -> 4 to 5 of 6.

What this says:

- Design coverage and label count are worth +14 to +17 strict solutions per 312 and a 12 to 15 % latency
  saving, and they decide whether the model works at all away from the training thread.
- Global composition sampling is free on the registered blends and necessary everywhere else.
- Epoch 80 is undertrained at 2,500 labels. Screen later checkpoints, natively.
- A low-rate final stage is neutral.
- The remaining failures are a corrector limit (section 12, item 2), not a data limit.

Unmeasured, in the order I would try them:

1. Material-consistent seeds: one tridiagonal component-balance sweep at the seed's temperatures and phase
   totals before Newton, or drop-and-reinsert of the dominant trace component in the corrector.
2. Salvage labels for the heat-gate class (21 % of the holdout, zero classical labels, a quarter already
   solved from the seed).
3. Zero-pattern sampling and `allowMissingZeroComponents`, qualified on sub-axis requests.
4. Phase-total target encoding with an explicit absent class (carried over from the F0 line).

---

## 14. Pitfalls that have already cost time

- **A family that cannot converge.** Overriding the generator (`watts: 0`, no steam) created 21 % fixed
  zeros. Pilot every family.
- **Aliased structural factors.** Equipment counts computed from stage count and feed index. Cross them.
- **Diagnostic probes that test something else.** The pressure probes were the degenerate family.
- **Envelope wider than the labels.** Bounds taken over all requests, including unsolvable ones.
- **A holdout from the training rule.** It inherits the design's blind spots.
- **Gates that a weak model passes.** Gain over classical-only with a 1.10 latency allowance.
- **Presets outside TRAIN and outside every gate.** Three of six presets were slower with the model.
- **A lost generator.** Register its hash and keep the file.
- **Literal widths and feed lists.** Derive from `basis.json` and the catalog.
- CRLF on hashed artifacts; contention on the 2 s wall; selecting offline; reusing a consumed test set;
  path-dependent verdicts typed as infeasible; changing two things in one arm; agent worktrees starting at
  the session's original HEAD.

---

## 15. Command reference

```bash
./gradlew.bat -I research/crude-regrouping/training/training.init.gradle regroupingTraining --offline --console=plain
```

```bash
python research/convergence-review/design_v2.py
```

```bash
./gradlew.bat -I research/crude-regrouping/training/training.init.gradle regroupingTraining -PtrainingMain=com.wormzjl.createcheme.science.column.v3.V3GeneralTrainingProbe "-PtrainingArgs=generate <out-dir> <requests.jsonl> 10 30" --offline --console=plain
```

```bash
./gradlew.bat -I research/crude-regrouping/training/training.init.gradle regroupingTraining -PtrainingMain=com.wormzjl.createcheme.science.column.v3.RegroupingFeatures "-PtrainingArgs=<cases.jsonl> <features.jsonl>" --offline --console=plain
```

```bash
.neural-venv/Scripts/python.exe research/convergence-review/train_v2.py <arm> "<cases.jsonl>|<features.jsonl>" ...
```

```bash
./gradlew.bat -I research/crude-regrouping/training/training.init.gradle regroupingTraining -PtrainingMain=com.wormzjl.createcheme.science.column.v3.V3GeneralTrainingProbe "-PtrainingArgs=compare <out-dir> <population.jsonl> 10 30 <sidecar.json or registry.json>" --offline --console=plain
```

```bash
node research/convergence-review/score.js <run-dir> ...
```

```bash
./gradlew.bat test --offline
```

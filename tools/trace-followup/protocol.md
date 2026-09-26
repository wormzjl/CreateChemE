# Trace loss, anchor inputs and exposure: controlled continuation study

The user resumed all four ranked follow-ups from the hybrid diagnosis: train reference-present trace flows before pruning, screen predefined checkpoints with native outcomes, simplify anchor features while preserving initial predictions, and compare added data with matched old-example exposure and an extra-compute control. This is an additive experimental study from `bad3346`; no production model, decoder threshold, physical model, strict audit or preceding archive changes.

## Data and initial state

The curated TRAIN-only source contains 905 records (SHA-256 `56682d2886060c01e778cd36eb12217800301c360770d88b0d647550e45323f1`). Canonical-input intersection with the original N805 gives C804; the remaining 101 form A101. C905 is their union. Every original raw record and label is preserved. The unresolved historical target stays quarantined and never appears in a new minibatch. Both new domains retain all eleven LIQUID_ONLY profiles. The original 405 validation records, including 168 certified references, must match the preceding archive byte-for-byte. Former fresh-test outcomes are not used for any new fit, checkpoint choice or benchmark.

Every fit starts from the same retained Transformer, whose exported weights hash is `7aa7eaa5ecbe4cea51a31bbe4724569e40900b128e98b1d69a6068e5e7d43e5e`. The unchanged historical normalization and input admission bounds are shared. This is continuation training: the incumbent's history includes the subsequently quarantined record. Excluding it from new minibatches is not unlearning. Frozen native controls are I (incumbent raw-seed pipeline) and H805 (historical N-20260911 hybrid including its material wrapper).

## Ten fits and thirty checkpoints

| Arm | Cohort | Inputs | Auxiliary loss |
|---|---|---|---|
| C | C804 | Original 96 | None |
| T | C804 | Original 96 | Fixed trace margin |
| F | C804 | 96 + 85 anchor + 3 branch + 1 availability | None |
| K | C804 | 96 + 3 anchor + 3 branch + 1 availability | None |
| D | C905 | Original 96 | None |

Each arm uses training-order seeds 20260911 and 20260912; these are order replications, not distinct pretrained initializations. All ten fits run 4,640 AdamW updates at learning rate 8e-5, weight decay 1e-4, batch size 32, dropout zero, no scheduler, clipping norm one, float32 CUDA and TF32 disabled. Complete shuffled passes retain partial batches; there is no replacement sampling or oversampling. Independent seeded permutation generators keep C/T/F/K streams identical for each seed despite their different parameter counts. Save complete checkpoints only at 3,120, 4,160 and 4,640; curves outside those steps cannot create additional candidate artifacts. Record per-ID presentation counts and batch-order hashes at each checkpoint.

The fixed endpoint comparisons remain mandatory regardless of native checkpoint selection:

| Contrast | Old-example presentations | Meaning |
|---|---|---|
| C4160 versus D4160 | 160 versus 143–144 | Equal updates |
| C4160 versus D4640 | 160 versus 160 | Matched exposure, added computation |
| C4640 versus D4640 | 178–179 versus 160 | Equal extended updates |
| C4160 versus C4640 | 160 versus 178–179 | Extra optimization control |

T−C isolates the loss; K−C tests useful anchor inputs; K−F tests simplification. Compare these at 4,160 and report native-selected variants separately. Do not combine T, K and D in another fit or select the better training-order seed for full validation.

## Trace objective

Use feed-normalized flows and `floor = 1e-10 * max(component feed fraction, 1e-12)`. On reference-present entries at or above the floor, with active feed and valid native nodes, penalize squared positive shortfall in log10 flow below `min(reference flow, 10 * floor)`. Average within each column and then across columns. Structural zeros, below-floor references and padding contribute zero. The ten-floor target is a registered hypothesis motivated by native reinsertion hysteresis, not a proven optimum.

The predicted flow is an all-active-component surrogate: predicted phase total times composition softmax over active feed components, before learned presence masking and trace pruning. It intentionally differs from the deployed presence-conditioned normalization. Presence BCE stays unchanged, and disabling a presence logit cannot evade this term. Nonpositive predicted totals retain finite arithmetic guards; their recovery relies on the base phase-total loss. Neither the hard decoder nor native support refresh changes.

Three disjoint C804 calibration batches of 32 are selected by the fixed canonical-input hash salt before gradient measurements. At the common initial model, compute median base and trace parameter-gradient norms, with unused parameter gradients treated as zero. Set lambda to `min(0.1, 0.1 * median(base norm) / max(median(trace norm), 1e-12))`. Freeze batches, source hashes, result and coefficient before fitting. Calibration performs no optimizer steps and reads no validation tensors. The bound applies to initial median contribution only; log later gradient norms, cosine and clipping without adapting the coefficient.

## Incumbent-preserving anchor inputs

Copy every incumbent parameter unchanged, append zero columns to its input embedding, and keep absolute output denormalization. Never add an anchor directly to output coordinates. F adds all 85 native anchor coordinates plus predicted-branch one-hot and availability. K adds only native temperature, log liquid total/feed and log vapor total/feed plus the same four indicators (103 total inputs, 84,248 parameters). All 85 output coordinates—including water, wet and presence heads—remain intact.

Normalize anchors once from C804 native input-derived anchors with column balancing and the existing floors; use the same three continuous scales in F and K. Always select the anchor by the model's predicted legal branch. Unavailable anchors use finite neutral values and an availability flag, without dropping the input; cancellation propagates. MATERIAL_CLOSED is the unchanged baseline, and pumparounds remain prescribed stage heat. New arms use raw factorized seeds without the material-completion wrapper. Baseline construction, including failed attempts, is inside their neural budget. Network equivalence at initialization is not a claim of equal end-to-end cost.

## Native checkpoint screening

Reuse the exact 65-input historical validation diagnostic panel, SHA-256 `0b287ecc9ff91f073dbdefdffaf92bc1b0ff452aef6ad5bb1779939911b9c1a8`. Its outcome stratification and rare LIQUID_ONLY reference are retained. It is a diagnostic selection panel, not a neutral population sample or a blind test.

Screen 30 new checkpoints plus I/H805 once in the frozen order under CURRENT_ONLY, LNN_ONLY and LNN_FIRST. Use ten owned workers, the same TRAIN warmup, 2-second neural budget, 30-second request deadline, 16 iterations, unchanged strict certification and 4 GiB heap. Per arm/seed: prefer checkpoints preserving the union of contemporaneous classical panel successes, then maximize strict FIRST count, then minimize all-case FIRST mean time, then prefer earlier update. If all fail the preservation gate, report the best diagnostic choice and its failed gate. Single-pass timing is only a tie-break heuristic.

Seed 20260911 is the primary full-validation replicate; 20260912 is panel robustness evidence. Separately select primary T's minimum original profile score among the same three checkpoints (earliest tie), to compare native and profile selection on one fixed trajectory.

## Full validation and acceptance

Before launching full validation, revalidate all screening journals, recompute selections and freeze complete pipeline identities. Include primary C/D at both 4,160 and 4,640; primary T/F/K at 4,160; each primary native-selected checkpoint; primary T's profile-selected checkpoint; and I/H805. Deduplicate by complete pipeline identity, not a shared weight file alone. The list has at most fifteen pipelines. Evaluate every listed pipeline on all 405 validation inputs, all three strategies, in two blocks with reversed pipeline order. Report the 65 screening cases and 340 remaining inputs separately alongside complete-population results. The remainder is an internal validation check, not a new holdout.

The maximum measured budget is 6,240 screening requests plus 36,450 complete-validation requests. Each pipeline invocation additionally has six fixed TRAIN warmup requests, counted separately. No new candidate, acquisition or test campaign follows from an attractive result. A failed/incomplete invocation is preserved and diagnosed rather than silently retried or replaced.

Report paired strict gains/losses, advisory qualifications, failure classes, above-floor omissions, fallback, profile accuracy, native energy/equilibrium diagnostics and complete request costs. A candidate is eligible for further qualification only if it improves strict FIRST qualification over I in both full-validation blocks, preserves the union of contemporaneous classical successes in both, and avoids pooled all-case mean-latency regression. This is not automatic promotion. Include incumbent-only neural losses even when that gate passes. Trace improvement also requires fewer above-floor omissions without merely inflating traffic or degrading other physical behavior.

## Verification and preservation

Preflight checks cover original-record/cohort bindings, unchanged archived validation references, exact initial outputs and branch/wet/zero masks, arbitrary finite and unavailable anchors at zero coupling, learnable zero projection, inference independence from teacher targets, trace-boundary semantics and exact exposure accounting. Every candidate export must pass Python/Java numeric parity, exact masks and ten-worker shared-model/cancellation checks. New Java classes compile separately; old measurement helper bodies and source/class outputs are preserved.

The new probe additionally captures the already computed seed presented by each pipeline, for exact native support/profile analysis; it does not add another prediction or change the measurement equations. For H805 that seed includes its historical wrapper; for every new arm it is the raw factorized prediction.

The training registration binds cohorts, calibration, normalization, architecture, seeds, checkpoints, selection rules, budgets and runtime sources before fitting. Final analysis is reconstructed from complete immutable journals and checkpoints; summaries, case maps and report must match replays before a separate ZIP is sealed and every entry checked. Verify predecessor archive hashes. Preliminary 145-input drafts, the initial Gradle classpath failure and a preflight Python syntax correction remain explicitly superseded preflight evidence; the registered K arm has 103 inputs.

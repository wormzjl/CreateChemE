# Four-layer full-anchor capacity experiment

This prospective continuation study implements the user's approved four-layer comparison. Prior tolerance loosening did not help; the solver acceptance criteria remain fixed. The wider model and other research proposals are outside this experiment. No production promotion is authorized.

## Starting model and data

Both arms start at the exact archived `F-20260911-s4160` full-anchor model, called F0. Its weight-export SHA-256 is `7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962`. The pipeline uses predicted-branch native anchors and absolute outputs, with no material-completion wrapper. I is the unchanged original Transformer control. Both are remeasured contemporaneously.

Use the unchanged N804 JSONL, SHA-256 `12573c376342389cf40731d68f92e2aacbb26306c84b959a522365b4a18bcc4b`, including all eleven LIQUID_ONLY cases. No added-101 cases enter minibatches. The quarantined historical target is excluded from new training; its earlier influence is inherited through pretraining, not unlearned. Original normalization, anchor caches, property package, bounds, validation inputs/references and warmup remain fixed. Their actual archive bytes are checked.

## Architecture and training

| Arm | Layers | Width / heads / feed-forward width | Parameters |
|---|---:|---|---:|
| L2 | 2 | 64 / 4 / 128 | 89,496 |
| L4 | 4 | 64 / 4 / 128 | 156,440 |

L4 appends two copies of F0's block 1, zeroing both the weights and biases of each attention output projection and feed-forward output projection. Pre-norm residual structure makes each new block initially the identity. All shared weights, branch predictor, inputs and outputs are copied exactly. Whole-pool Python training/evaluation checks and independent Java F0/L2/L4 comparisons precede fitting. Disposable TRAIN-only tests verify first-step projection gradients and subsequent gradients into the new interiors.

Run four fits: each arm at training-order seeds 20260913 and 20260914. Every fit receives 3,120 **additional** updates from F0, with complete checkpoints at +1,040, +2,080 and +3,120. These correspond to exactly 40, 80 and 120 additional presentations of each N804 example. Keep the partial four-example batch and pair exact shuffle streams across L2/L4.

Use fresh AdamW states in both arms: learning rate 8e-5, weight decay 1e-4, batch size 32, global gradient clipping at one, no scheduler, zero dropout, float32 CUDA, deterministic algorithms and TF32 disabled. Train every parameter. Keep the existing supervised loss; no trace auxiliary or new regularizer. Additional parameters participate in global clipping, so shared-parameter updates need not remain identical. Record training time and GPU memory; equal updates are not equal compute.

Record the same per-column profile metrics on N804 and the original 168 certified validation references at initialization and the three endpoints. Record added-projection norms and observed hidden-state changes on a fixed first-32 TRAIN probe. These diagnostics do not create extra candidate checkpoints or alter the recipe.

## Runtime and preflight

Compile archive-matched solver sources into a fresh isolated directory with only Gson as a compiler dependency. No old project classes may satisfy compilation. Record source-to-build provenance, ordered runtime classpath, resources/JARs and compiled fingerprints. Verify the actually loaded calculator, Newton solver, auditor, trace policy, scheduler and model classes against those bytes. Previous source/class directories remain unchanged.

Every new export must pass the predecessor's Java/Python numerical tolerances, exact decoded masks, 14 fixed TRAIN fixtures, cancellation propagation and forty shared-model predictions across ten owned workers. Reject invalid depth metadata, missing/extra blocks, wrong tensor shapes and parameter counts. Preserve failed preflight attempts; never widen parity tolerances to admit a depth candidate.

## Screening and validation

Screen all twelve checkpoints plus F0 and I once on the unchanged 65-case diagnostic panel, using all three strategies. Order: F0, then each seed/checkpoint with L2 followed by L4, then I. The panel is historical and outcome-stratified, not a new representative holdout.

For each arm and seed independently, prefer preservation of the union of contemporaneous classical successes, then maximize strict FIRST count, minimize all-case mean FIRST milliseconds, then prefer the earlier additional update. Retain a failed preservation flag if every checkpoint fails that gate. Do not select a better seed.

Before full validation, recompute every choice from complete screening journals and freeze the union of both arms/both seeds at fixed +2,080, all four native-selected checkpoints, F0 and I. Deduplicate complete pipeline identities. At most ten pipelines receive all 405 validation inputs in all three strategies and two reversed-order blocks. Report the 65 panel inputs and remaining 340 separately, while retaining complete-405 accounting. All are historical validation; there is no new test evaluation.

All native runs use ten owned workers, sixteen correction iterations, a two-second neural allowance, thirty-second request deadline and 4 GiB heap. Native anchor construction, inference, decoding and failed neural attempts consume the neural allowance; classical fallback consumes the remaining overall request budget under the existing policy. No cached anchors make native inference free. Maximum corrected requests: 2,730 screening plus 24,300 full validation, with at most 204 TRAIN warmup requests separately counted. Prediction/parity checks add no corrected-column campaign.

## Decision and preservation

The primary depth contrast is L4 minus L2 at fixed +2,080, paired by training-order seed and validation block. A strong replicated benefit requires higher strict FIRST count for both seeds in both blocks, preservation of the contemporaneous classical-success union, and no pooled mean FIRST latency regression against the matched L2 model for either seed. Mixed results remain mixed. Independently selected checkpoints are secondary comparisons and can differ in training exposure.

For further qualification, apply strict gain, classical preservation and no mean-latency regression against F0, then report the same checks against I. Passing against I alone is insufficient evidence of improvement over F0. Always disclose ONLY and FIRST gains and losses, advisory/failure outcomes, CPU/latency distributions, allocations, memory, fallback, timeout evidence and stable common-success costs. Lower averages from quicker failures do not establish faster solving.

Before sealing, independently reconstruct all exposure/shuffle counts, tensor exports, selections, summaries, paired case/profile maps and report text from the complete records. Require equality with stored artifacts before ZIP creation, and verify archive entries and all predecessors. No new architecture, seed, loss, data acquisition, retired test, loosened criterion or automatic production change is included.

# Transformer accuracy study, revision 1

This study tests regularization and decoded-flow loss separately and together.
It preserves the original 805 certified training columns, 405 validation inputs
(168 qualified references), all historical test sets and all prior models.
No label expansion, branch rebalancing or solver-policy change is included.

Before fitting, generate an independent 252-case input-only test: four cases per
stage count 2–64, two steam-on and two steam-off, with equipment balancing using
the existing sampler. Exclude historical inputs and prior candidate pools.
Freeze the pool, selected inputs and source hashes. Do not create a timing subset.

All arms keep the 83,800-parameter transformer, factorized heads, feed prior,
presence BCE, trace supervision and deployment decoder. Seeds are 20260910,
20260911 and 20260912. Each run uses CUDA float32 with TF32 disabled, batch 32,
gradient clipping 1, at most 160 epochs and patience 40.

| Arm | Dropout | AdamW weight decay | Learning rate | Added decoded-flow weight |
|---|---:|---:|---|---:|
| baseline | 0 | 1e-4 | constant 8e-4 | 0 |
| regularized-05 | .05 | 1e-3 | cosine 8e-4 to 8e-5 | 0 |
| regularized-10 | .10 | 1e-3 | cosine 8e-4 to 8e-5 | 0 |
| decoded | 0 | 1e-4 | constant 8e-4 | 2 |
| regularized-05-decoded | .05 | 1e-3 | cosine 8e-4 to 8e-5 | 2 |
| regularized-10-decoded | .10 | 1e-3 | cosine 8e-4 to 8e-5 | 2 |

Regularization is a predefined bundle; this experiment does not independently
identify the contribution of its dropout, weight decay and schedule components.

The added loss is Smooth L1 (beta .05) on decoded component flows divided by
feed, summed over components and averaged across the two phases, nodes within
each column, and columns. Hard presence and trace masks match deployment on
admissible raw outputs; their gradients are zero. The original BCE continues to
teach presence. Arithmetic clamps guard training; invalid raw predictions are
flagged and penalized in validation, rather than treated as valid clipped seeds.
Condenser branch comes from the prediction; teacher branches are never features.

Every arm uses the same checkpoint-selection score, averaged equally by column:

    temperature MAE / 25 K
    + 0.5 * (liquid total MAE / feed + vapor total MAE / feed)
    + 0.5 * (liquid component L1 / feed + vapor component L1 / feed)
    + 0.02 * trace log10 MAE
    + 0.25 * branch error
    + 10 * invalid-prediction indicator

Trace entries are true phase fractions at most 1e-4 with true flow at least the
existing native trace floor. Their log10 errors use that same floor. A column
without eligible trace entries contributes zero; entry counts are retained.
Metrics use the actual hard decoder, including fixed condenser temperature.
Lowest score selects a checkpoint. Lowest mean checkpoint score across the
three seeds selects the arm (tie: lexical name); lowest score selects its seed
(tie: seed). Freeze this selection before native test execution. Also retain
all individual metrics so a composite improvement cannot hide tradeoffs.

Export the selected candidate using the unchanged inference architecture and
historical admissibility bounds. Require Java/PyTorch-double parity on the same
14 TRAIN-only fixtures, exact branch/wet/zero masks, 32 identical shared-model
predictions, cancellation propagation and malformed-artifact rejection.

Compare the frozen candidate and the existing native transformer on every fresh
test case. Use the unified CURRENT_ONLY/LNN_ONLY/LNN_FIRST serial policy with
2-second neural and 30-second whole-request budgets and 16 iterations per
correction pass. Report strict/advisory/failed counts together with all-case
elapsed and CPU time, mean and sample SD, diagnostic iterations and paired
success gains/losses. Test results never change the selected candidate.

Archive every fit, checkpoint, history, input pool, selection, parity record,
native journal and source dependency outside Gradle clean. Preserve previous
caches. This study does not promote a runtime default.

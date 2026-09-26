# Transformer investigation protocol

This phase adds qualified Gen3 native solves, characterizes the data, and runs an
offline CUDA pilot. Native solver behavior and the deployed initializer are not
changed by this experiment.

## Frozen data decisions (before fitting)

The user authorized retiring Gen3's 252 fresh cases and creating a replacement
holdout. The new eligible training set has **805 columns**: 579 retained labels,
133 newly solved original TRAIN cases, and 93 unambiguous fresh successes. One of
94 fresh successes has differing accepted condenser branch labels and is
quarantined. All original matrix validation/test folds remain separate. All 35
historical challenge cases stay outside fitting, including 14 newly solved ones.
Original validation gains 32 qualified references, bringing it to 168.

Three inputs have materially different accepted profiles across methods. Existing
original profiles remain the deterministic targets; new ambiguous training cases
are quarantined. Differences mean label ambiguity, not proof that every reported
root is physically appropriate. Do not average these profiles.

The new 252-case holdout uses input-only seed 202609104, four cases per N=2..64,
two with steam and two without at every N. Its 64-case benchmark subset is also
input-only. Neither receives a native solve or neural prediction in this phase.
Because fresh Gen3 examples enter training, the old geometry test no longer
guarantees wholly unseen stage counts; it remains a fixed regression fold.

## Characterization and architectural choice

805 independent columns contain 20,992 stage/boundary nodes. Nodes in a column
are correlated; normalization, batching, loss and metrics give each column equal
weight. All fitted columns are dry equilibrium, despite steam being present in
many inputs. Liquid-only cases are naturally rare for the current methane-containing
composition; this is expected, not a sampling defect to correct by artificial
balancing. No VAPOR_ONLY examples are available. No wet-regime or unseen-branch
competence can be inferred.

The sequence length is only N+2 = 4..66. A two-block, width-64, four-head
bidirectional transformer is small enough that ordinary attention is appropriate.
Stage position, feed distance, boundary flags, pressure, local/cumulative sources
and equipment descriptors reuse the existing encoder. Teacher branch indicators
are removed from stage inputs; a separate input-only branch classifier is trained.
The transformer has no access to labels at inference.

Compare against a residual per-stage MLP with the same width and two blocks;
its larger feed-forward layers closely match the transformer's parameter count.
Both use the same training data, 85 factorized targets, feed-prior composition
decoder, optimizer, whole-column weights, epoch budget, stopping rule and seeds.
This isolates the value of stage interaction more fairly than comparing only
against the older model trained on fewer labels.

Use CUDA float32 on the RTX 4070 Ti, TF32 off, batch size 32, AdamW at 8e-4,
weight decay 1e-4, gradient clipping 1, maximum 160 epochs and validation patience
40. Run seeds 20260910, 20260911 and 20260912 for both architectures. Select each
run's epoch using only the same qualified original validation fold. Report
per-column means and sample SD, plus variation across seeds. This is a bounded
pilot, not a hyperparameter search or native-convergence comparison.

## Research basis and limits

[Transolver](https://arxiv.org/abs/2402.02366) uses learned physical slices to make
attention practical on large PDE meshes. Our maximum of 66 nodes does not yet
justify that machinery; transferring its mesh results directly to MESH column
initialization would be an unsupported inference.

[LinearNO](https://arxiv.org/abs/2511.06294) revisits Physics-Attention through
linear attention and reports that architectural/training choices need careful
separation. This motivates the parameter-matched MLP control here. It does not
establish which architecture will work for these columns.

[Learning to warm-start fixed-point optimization algorithms](https://jmlr.org/papers/v25/23-1174.html)
supports studying solver-aware warm starts. Its theoretical setting is not a
convergence guarantee for this nonconvex, active-set column solver. A subsequent
phase should rank candidates by native strict convergence and equal-policy total
runtime, including inference and fallback. Regression loss alone cannot do that.

[PyTorch TransformerEncoderLayer documentation](https://docs.pytorch.org/docs/2.10/generated/torch.nn.TransformerEncoderLayer.html)
defines batch-first sequences and padding masks. The pilot excludes padded keys
and padded loss contributions, and checks padding invariance and finite gradients.
GPU model export and Java parity are later gates before any deployment.

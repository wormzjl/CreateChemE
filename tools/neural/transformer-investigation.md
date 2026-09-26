# Transformer data and GPU pilot

**805 qualified training columns** are available, versus 579 before: +133 original TRAIN rescues and +93 fresh Gen3 labels (39.0% more columns). The 94th fresh success is quarantined because accepted methods disagree on its condenser branch.

Native audits and final Newton certificates were checked before any label was admitted. All original validation/test inputs retain their folds; validation now has 168 qualified references, the old test has 167, and 14 solved historical cases remain challenge-only. Repeated successful solves produce one target per input, with journal hashes and provenance.

The replacement **252-case holdout** and its 64-case benchmark subset are frozen and unevaluated. There are four cases at every stage count 2–64, two steam-on and two steam-off, with zero overlap against 5,637 earlier input hashes. The old geometry test is now a regression fold: fresh Gen3 additions introduce some of its stage counts into training.

## What the data says

The 805 columns contain 20,992 correlated nodes, not 20,992 independent training examples. All normalization, losses and reported errors give each column equal weight. Training covers 61 of 63 stage counts; **N=38 and N=52 have no fitted labels**, and several other stage counts have only one to three.

The branch labels are 794 TWO_PHASE and 11 LIQUID_ONLY. As expected for the current methane-containing mixture, liquid-only cases are rare; the pilot keeps that natural distribution. There are 437 steam-on columns, but all fitted profiles have dry equilibrium and no free-water nodes. Steam presence must not be interpreted as a wet-equilibrium label.

| Stages | Qualified / original TRAIN inputs | Qualified / retired fresh inputs | Eligible fitted columns |
|---|---:|---:|---:|
| 2–16 | 289 / 476 | 38 / 60 | 326 |
| 17–32 | 210 / 492 | 30 / 64 | 240 |
| 33–48 | 143 / 540 | 17 / 64 | 160 |
| 49–64 | 70 / 485 | 9 / 64 | 79 |

Qualified-label coverage declines with column size. These counts describe observed solver success; unlabelled failures are not proofs of infeasibility. Fresh successes help equipment coverage: four-pumparound training columns rise from 57 to 100.

| Per-column training statistic | Mean ± sample SD | Median | 95th percentile |
|---|---:|---:|---:|
| Mean profile temperature (K) | 481.6 ± 48.7 | 478.7 | 566.4 |
| Maximum adjacent temperature jump (K) | 73.88 ± 30.7 | 67.15 | 135.1 |
| Mean liquid traffic / feed | 1.459 ± 0.796 | 1.325 | 2.994 |
| Mean vapor traffic / feed | 1.757 ± 0.81 | 1.638 | 3.265 |
| Zero liquid component fraction | 0.2584 ± 0.098 | 0.2643 | 0.4125 |
| Zero vapor component fraction | 0.2894 ± 0.0845 | 0.288 | 0.4265 |

Large temperature jumps and sparse phase/component flows argue against forcing smooth profiles or using only unweighted raw-flow error. The pilot reuses factorized phase totals, feed-prior composition, trace-support heads and the conservative decoder. Three inputs show materially differing accepted profiles across methods: the new branch-ambiguous case is quarantined; the two existing labels remain unchanged. One existing training case differs by up to 68.86 K and 9.77 times feed in component flow from a later replay, so a future root-selection policy deserves investigation.

## CUDA comparison

Both architectures use the same 805 columns, 168 validation references, factorized targets, branch classifier, optimizer, stopping rule and three seeds. The transformer uses two width-64 blocks and four heads; the residual MLP is matched within 0.7% in parameter count. Teacher branch labels are absent from input features. At only 4–66 tokens, full attention is inexpensive.

The following SD is **across the three seed-level means**, not across columns. Individual reports also contain per-column sample SD and paired differences.

| Metric | Transformer | MLP |
|---|---:|---:|
| Best validation objective | 1.298 ± 0.0766 | 1.541 ± 0.0706 |
| GPU training time per run (s) | 21.75 ± 2.75 | 12.07 ± 0.467 |
| Temperature MAE (K) | 10.94 ± 0.222 | 12.57 ± 0.501 |
| Liquid total MAE / feed | 0.1243 ± 0.00332 | 0.1501 ± 0.00454 |
| Vapor total MAE / feed | 0.1228 ± 0.00173 | 0.145 ± 0.00565 |
| Branch accuracy | 0.994 ± 0 | 0.994 ± 0 |

Parameter counts: transformer 83,800; MLP 83,288. All six runs used PyTorch 2.10.0+cu128 on the RTX 4070 Ti, float32 with TF32 disabled. Timing covers optimization and validation/early stopping, excludes environment startup/data preparation, and is not an inference or native-solver benchmark.

## Interpretation and next gates

This is evidence about held-out profile regression on the qualified validation subset. It does not establish native convergence improvements, performance on the unlabelled failures, or support for wet equilibrium. The validation fold has been reused across generations, so it is model-selection evidence. The fresh replacement test remains available for a later frozen native campaign.

Before any promotion: export the chosen model with CPU/Java parity checks; compare strict native convergence and equal-policy end-to-end runtime against the incumbent nearest-profile initializer and the matched MLP; test branch/root ambiguity and trace-support behavior; then evaluate the untouched replacement holdout after candidate selection is frozen. Targeted additional native data at N=38/52 and sparsely covered large columns is more justified than artificially balancing liquid-only cases.

The research rationale and pre-fit choices are in [transformer-protocol.md](transformer-protocol.md), including [Transolver](https://arxiv.org/abs/2402.02366), [LinearNO](https://arxiv.org/abs/2511.06294), and [warm-start fixed-point optimization](https://jmlr.org/papers/v25/23-1174.html). Large-mesh attention and optimization convergence results do not automatically transfer to this MESH active-set solver.

## Reproduce and retain

Use the project-local Python environment and `requirements-transformer.txt`. Restore the verified Gen3 campaign, run `prepare_transformer_data.py`, run `test_transformer.py`, then run `train_transformer.py` for the two architectures and seeds listed in the protocol. `finalize_transformer_pilot.py` regenerates this report and the immutable cache.

The cache manifest in `transformer-cache-manifest.json` records SHA-256 for the full dataset, provenance, replacement holdouts, all six checkpoints, validation predictions, training histories, environment lock and implementation snapshots. The archive lives under `.neural-cache/transformer-investigation-v1/`, outside Gradle clean. Earlier generation caches are preserved.

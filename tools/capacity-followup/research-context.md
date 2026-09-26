# Research context for increasing hybrid-model capacity

Checked 13 September 2026. This note explains the existing experiment; it does not change its registered recipe.

**Growing a trained model is a reasonable controlled experiment.** Chen, Goodfellow and Shlens describe function-preserving transformations that transfer an existing network into a deeper or wider network. This supports testing additional capacity without discarding the learned starting function. It does not predict a benefit for this thermodynamic solver. [Net2Net: Accelerating Learning via Knowledge Transfer](https://arxiv.org/abs/1511.05641)

Here, the exact mechanism is specific to our pre-norm residual architecture: both output projections and their biases are zero in each appended block, so its residual contribution starts at zero. Copying the trained interior supplies a nonrandom basis for subsequent learning. Python and Java initial-prediction checks establish equivalence for this implementation. A matched two-layer continuation separates extra depth from simply training the existing model longer.

**The useful target is downstream solver behavior.** Sambharya and colleagues study learned warm starts followed by fixed-point iterations, with objectives based on either final residual or distance to a solution. Their reported guarantees concern specified classes of fixed-point operators, including contractive and averaged operators. Those guarantees cannot be assumed for this phase-switching nonlinear column solver. [Learning to Warm-Start Fixed-Point Optimization Algorithms, JMLR 2024](https://www.jmlr.org/papers/v25/23-1174.html)

Our inference is that lower supervised profile error alone is insufficient evidence of improved initialization: admissibility, branch/support selection, and the finite-budget correction path can determine whether a request actually converges. The current experiment therefore measures strict native successes, paired losses, fallback and cost under unchanged criteria. It does not introduce a new solver-aware loss.

The user's prior tests found that loosening acceptance criteria did not help. This study preserves those criteria and tests robustness through better initial predictions. It addresses whether this particular increase from two to four layers helps under matched updates; it cannot establish that every larger network, a wider network, or a larger training dataset would behave similarly.

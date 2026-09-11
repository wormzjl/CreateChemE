# Hybrid training comparison: retain the current Transformer

The trained hybrid did not improve strict native convergence. No pipeline met the replacement gate, and production defaults remain unchanged. The investigation, framework and evidence are on `codex/v4-transformer-investigation`; misplaced cache copies were moved out of the clean `main` worktree after all 71 files were checked against their canonical copies.

The ten-worker sweep of 2,793 original conditions recovered **101 new certified TRAIN profiles**. N preserves its original **805** profiles exactly; N+1 contains **906**. All nontraining records and the original validation references were preserved. The added profiles include 53 steam-fed conditions, but none has a qualified wet profile.

Six hybrid fits used three paired seeds, the same N-derived normalization and 4,160 optimizer updates per fit. A native MATERIAL_CLOSED guess anchors the learned continuous residuals. Training and inference both choose the anchor branch with the classifier. The exact material-completion wrapper is included in the hybrid pipelines and tested separately on the current Transformer.

| Pipeline | Strict FIRST validation, each block / 405 | Strict FIRST fresh test, each block / 252 | Fresh ONLY / 252 | Fresh FIRST mean seconds |
|---|---:|---:|---:|---:|
| Current Transformer | 161 | 92 | 68 | 4.662 |
| Current + material completion | 161 | 93 | 69 | 4.638 |
| Hybrid N, seed 20260911 | 159 | 91 | 63 | 4.926 |
| Hybrid N+1, seed 20260910 | 155 | 89 | 62 | 4.737 |

These four pipelines reproduced their strict counts in both blocks. Classical-only controls produced 110/405 validation successes and 58/252 fresh-test successes throughout. Each population contains the same independent inputs in its two repeated blocks. Mean request time includes failures and fallback; differences are descriptive and do not establish a causal speedup. All requests used ten workers, a 30-second deadline, a two-second neural budget and 16 correction iterations.

On the fresh test, N gained 12 incumbent failures but lost 13 incumbent successes; N+1 gained 10 and lost 13. The wrapper gained one case and lost none. The extra N+1 data reduced neural-first validation success for every paired seed in this fixed experiment. This does not establish that additional certified data is generally harmful: it tests this data addition with this architecture, normalization and training recipe.

On the same 168 original certified reference profiles, selected N slightly improved raw temperature RMSE (11.112 K versus 11.371 K) and phase-total RMSE/feed (0.133951 versus 0.138993). Its completed phase-total error increased to 0.159497. Selected N+1 had raw temperature RMSE 11.580 K and raw/final phase-total error 0.129892/0.198502. Better raw profile error therefore did not predict better native convergence.

Completion prepared only 12/405 validation and 8/252 test inputs for selected N, 17/405 and 8/252 for selected N+1, and 16/405 and 13/252 for the wrapped incumbent. Remaining inputs were inapplicable or explicitly declined and stayed in all benchmark denominators. Completion preserves temperature and does not enforce energy or equilibrium. Physical-family residuals, support changes and unavailable property evaluations are retained in the diagnostic evidence.

The practical result is to retain the current Transformer and keep this hybrid framework as an offline experiment. The wrapper's one-case fresh-test gain did not satisfy the preregistered validation improvement gate. Further work should first investigate the recorded case losses and the mismatch between profile error and convergence before increasing training scale.

Evidence:

- [Complete native benchmark](native-results.md): all six seeds, both controls, timings, advisory/failure counts and paired differences.
- [Physical and profile diagnostics](profile-results.md): common-reference errors, preparation coverage and equation-family evidence description.
- [Protocol](protocol.md), [selection guard](test-gate-note.md), and [sealing checks](sealing-note.md).
- [Salvage results](../neural/salvage_results.md) and [worktree organization audit](../neural/worktree-artifact-organization.json).
- [Training archive manifest](training-cache-manifest.json) and [results archive manifest](results-cache-manifest.json). Full journals, per-case maps, strata, model weights, datasets and recomputation proofs are preserved outside Gradle clean in `.neural-cache/hybrid-learning-v1/`.

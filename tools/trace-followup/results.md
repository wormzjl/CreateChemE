# Four controlled follow-ups: results

**7 candidate pipeline(s) meet the registered validation rule for further qualification:** C@4,160, C@4,640, D@3,120, D@4,160, F@4,160, K@3,120, K@4,160.

All four requested experiments are complete. Ten continuation fits produced thirty predefined checkpoints. Each checkpoint passed native export parity before screening. The final pipelines were frozen before two full-validation blocks; no production model, decoder threshold, thermodynamic property, support rule or strict audit changed.

C is the curated continuation control; T adds the trace-margin loss; F adds all native anchor inputs; K keeps only temperature and two phase-traffic anchors; D adds the 101 curated profiles. I is the retained Transformer. H805 is the historical hybrid with its original wrapper and is contextual, not a matched causal control.

## Full native validation

Counts cover all 405 validation inputs. Each cell gives block 1 / block 2. ONLY is neural correction alone; FIRST includes classical fallback. Latency is pooled all-case FIRST service time under the fixed ten-worker load.

| Pipeline | Strict ONLY | Strict FIRST | FIRST mean ms | Further-qualification gates |
|---|---:|---:|---:|---|
| I | 116 / 116 | 161 / 161 | 3811.3 | reference |
| T@3,120 | 116 / 116 | 159 / 159 | 3822.0 | strict gain, latency |
| K@3,120 | 129 / 129 | 162 / 162 | 3757.3 | pass |
| D@3,120 | 126 / 126 | 166 / 166 | 3702.3 | pass |
| C@4,160 | 129 / 129 | 166 / 166 | 3710.4 | pass |
| T@4,160 | 122 / 122 | 162 / 162 | 3835.5 | latency |
| F@4,160 | 134 / 134 | 168 / 168 | 3606.0 | pass |
| K@4,160 | 128 / 128 | 165 / 165 | 3679.0 | pass |
| D@4,160 | 126 / 126 | 167 / 167 | 3743.4 | pass |
| C@4,640 | 120 / 120 | 162 / 162 | 3733.6 | pass |
| D@4,640 | 118 / 118 | 160 / 160 | 3864.5 | strict gain, latency |
| H805 | 103 / 103 | 159 / 159 | 3940.2 | strict gain, latency |

Passing requires a strict FIRST gain over I in both blocks, preservation of the contemporaneous classical-success union in both, and no pooled mean-latency regression. A passing result supports further qualification; it is not automatic deployment or new blind-test evidence.

## Fixed causal and exposure comparisons

Gains/losses are paired strict cases relative to the specified reference. These fixed endpoints remain in the results even when checkpoint screening selects another update.

| Question | Candidate versus reference | ONLY gains/losses, blocks 1 / 2 | FIRST gains/losses, blocks 1 / 2 |
|---|---|---:|---:|
| Trace margin | T@4,160 versus C@4,160 | 9/16 / 9/16 | 4/8 / 4/8 |
| Useful compact anchors | K@4,160 versus C@4,160 | 2/3 / 2/3 | 0/1 / 0/1 |
| Anchor simplification | K@4,160 versus F@4,160 | 1/7 / 1/7 | 1/4 / 1/4 |
| Added data: equal updates | D@4,160 versus C@4,160 | 12/15 / 12/15 | 9/8 / 9/8 |
| Added data: matched old exposure | D@4,640 versus C@4,160 | 11/22 / 11/22 | 7/13 / 7/13 |
| Added data: equal extended updates | D@4,640 versus C@4,640 | 16/18 / 16/18 | 9/11 / 9/11 |
| Extra optimization only | C@4,640 versus C@4,160 | 8/17 / 8/17 | 3/7 / 3/7 |
| Native versus profile checkpoint choice | T@3,120 versus T@3,120 | 0/0 / 0/0 | 0/0 / 0/0 |

## 1. Trace-flow treatment

The fixed coefficient is **0.09128014**, calibrated once from three predefined TRAIN batches without an optimizer step or validation data. The term penalizes log-flow shortfall below the lesser of the reference flow and ten times the unchanged floor. Presence BCE remains unchanged. The surrogate is computed over all active feed components before presence and trace pruning; it is not an exact differentiable copy of the hard decoder.

At update 4,160, actual native pipeline seeds give the following comparison on original certified references available for both C and T:

| Block | Common reference cases | Above-floor omissions C → T | Mean temperature MAE change, K | Mean liquid component-L1 change / feed | Mean vapor component-L1 change / feed |
|---|---:|---:|---:|---:|---:|
| 1 | 168 | 778 → 842 | 0.00863979 | -0.00190405 | -0.00352928 |
| 2 | 168 | 778 → 842 | 0.00863979 | -0.00190405 | -0.00352928 |

Signed mean traffic-error changes T−C (liquid, vapor)/feed, blocks 1 / 2: (0.000482867, -0.00111628) / (0.000482867, -0.00111628).
Mean additional above-floor entry-count changes per column, blocks 1 / 2: 0.220238 / 0.220238.

| Block | Terminal ONLY energy-audit failures / available, C | T | Terminal ONLY equilibrium-audit failures / available, C | T |
|---|---:|---:|---:|---:|
| 1 | 6 / 305 | 10 / 307 | 145 / 305 | 158 / 307 |
| 2 | 7 / 308 | 11 / 304 | 148 / 308 | 155 / 304 |

The case evidence separates omissions at a branch-forbidden phase, an allowed phase whose decoded total is zero, and a positive phase with missing components. A zero decoded phase does not by itself distinguish a nonpositive raw total from total loss during pruning. The evidence also reports signed traffic errors, extra above-floor entries, branch agreement, unavailable predictions, and energy/equilibrium audit results by terminal trajectory owner. A smaller omission count alone does not establish a better seed or mandatory support at every admissible root. State-dependent projected residuals and audit ratios are diagnostics, not one common training objective. FIRST terminal energy evidence may belong to its classical fallback, while the separate ONLY request is a different trajectory.

## 2. Native checkpoint selection

The same three updates—3,120, 4,160 and 4,640—were screened for each fit. The panel contains 65 historically outcome-stratified validation cases, including its rare LIQUID_ONLY reference. Selection prioritizes classical-success preservation, then strict FIRST count, mean FIRST time and earlier update. No better seed was selected: 20260911 was fixed as the full-validation replicate in advance.

| Arm | Native-selected update, seed 20260911 | Native-selected update, seed 20260912 | Selected strict FIRST, primary / second seed | Primary preserves panel classical union |
|---|---:|---:|---:|---|
| C | 4,160 | 4,160 | 42 / 42 | yes |
| T | 3,120 | 4,160 | 43 / 44 | yes |
| F | 4,160 | 4,160 | 42 / 42 | yes |
| K | 3,120 | 3,120 | 42 / 42 | yes |
| D | 3,120 | 3,120 | 46 / 44 | yes |

For primary T, profile scoring selects **T@3,120**, while native screening selects **T@3,120**. They select the same artifact, so this study observes no checkpoint-choice difference for T.

Detailed screening results retain both training-order seeds at all three updates. Full-validation summaries separately report the 65 screening cases and the remaining 340. The remainder provides an internal validation check; it is not a newly independent holdout. Single-pass screening time is a tie-break heuristic, not proof of a speedup.

## 3. Compact incumbent-preserving anchors

F has 185 inputs and 89,496 parameters. K has 103 inputs and 84,248 parameters, compared with I/C at 96 inputs and 83,800 parameters. K retains native temperature, log liquid traffic and log vapor traffic plus the predicted-branch and availability indicators. All 85 output heads, including water, wetness and component presence, remain intact.

Zero-initialized added embedding columns preserve the incumbent’s raw outputs, branches and decoded masks exactly on all 905 TRAIN inputs before fitting. Outputs remain absolute; no mechanistic anchor is added to them. Arbitrary finite and unavailable anchor tests also preserve the initial function, and nonzero gradients can train the new projection. Native parity and ten-worker cancellation/ownership checks pass for every exported checkpoint.

K−F at 4,160 measures simplification; K−C at the same update measures whether adding these anchor features helps. F and K pay for MATERIAL_CLOSED baseline construction inside the neural budget, including failed attempts. Algebraic initialization equivalence therefore does not imply equal request cost.

## 4. Added-data and compute accounting

The new training domains are 804 unchanged original records and those same records plus 101 additions. The disputed historical target is excluded from every new minibatch. Each new fit nevertheless inherits the incumbent’s historical pretraining; this is continuation training, not unlearning. Historical input/output normalization is shared across arms.

| Endpoint | Old examples: presentations each | Added examples: presentations each |
|---|---:|---:|
| C at 4,160 | 160 | — |
| D at 4,160 | 143–144 | 143–144 |
| D at 4,640 | 160 | 160 |
| C at 4,640 | 178–179 | — |

Per-case counters verify these bounds at both training-order seeds. C/T/F/K share exact minibatch-order hashes for each seed. The fixed endpoint table distinguishes equal updates, matched original-example exposure, equal extended updates and extra optimization alone. The selected-checkpoint results do not replace those comparisons.

## Verification, scope and files

Completed accounting: **35,400 measured native requests**, plus **336 fixed TRAIN warmup requests**. Full validation covers 12 frozen pipelines × 405 inputs × three strategies × two blocks. All ten fits, thirty checkpoints, export identities, exact masks, source bindings and presentation counts were verified.

The main evidence files are `screen-analysis.json`, `validation-analysis.json`, their case/profile sidecars, `selection.json`, `validation-plan.json` and the ten fit reports. They preserve strict/advisory/failure outcomes, fallback and unknown evidence, paired case identities, support/profile metrics, gradient diagnostics and complete request costs.

All original validation records match the preceding archive. No former fresh-test outcomes, new acquisition, coefficient sweep, extra architecture or production-default change was used. Missing wet-qualified and VAPOR_ONLY training populations remain gaps. The preliminary 145-input draft and both preflight setup/syntax failures are retained as superseded evidence; the registered compact arm has 103 inputs.

See the [protocol](protocol.md) and [archive manifest](cache-manifest.json) for source hashes, registration, verification and restoration.

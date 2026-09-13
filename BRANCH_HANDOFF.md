# LNN and Transformer work: branch handoff

## What the LNN and Transformer work is about

The overall project investigates **neural-assisted initialization of CreateChemE’s V3 equilibrium-stage column solver**. A column request specifies composition, feed conditions, pressure, reflux, steam, withdrawals, stage count and heat duties. Solving it requires finding a physically consistent state across the column. The LNN path uses a learned model to propose that initial state, with the aim of recovering difficult operating conditions and reducing the correction work required.

The learned state includes temperatures, liquid and vapor component flows, and, where supported, free-water flows and wet-phase information. **Transformers are one predictor family used inside this LNN path.** Earlier predictors used dense neural networks; later experiments added shared stage models, factorized outputs, attention between column stages, and native physics information. Throughout this work, a predicted profile becomes a qualified solution only after native correction and certification.

### How a request passes through the system

1. **Describe the requested column.** Encode the operating conditions and geometry, and check that the selected model supports the property package and input domain. Learned features and normalization come from the registered training process.
2. **Predict and decode an initial state.** The model proposes the column profile and relevant phase information. The decoder applies the existing bounds, structural-zero, component-support and water/phase rules. Some experimental models also receive a native initialization guess as input or predict corrections relative to it.
3. **Run the physical solver.** V3 corrects the proposed state against its material, energy, equilibrium and phase equations. Inference, preparation and correction consume the registered request budgets.
4. **Certify the result and apply the selected recovery policy.** Strict success requires the native acceptance audit and final Newton certificate. `LNN_ONLY` evaluates neural initialization plus correction. `LNN_FIRST` permits classical fallback when necessary. `CURRENT_ONLY` supplies the classical control. Advisory outcomes and failed requests remain visible in the results.

This separation is central to the investigation: profile-prediction error measures one part of the system, while the practical outcome is a strictly qualified solve and its complete request cost. A model can predict profiles more accurately and still send the corrector toward less useful states. Likewise, a method can recover additional cases while increasing average runtime through failed attempts and fallback.

### How the work developed

| Stage | Done | Worked in the measured experiment | Did not work / remains unproven | Direct proof |
| --- | --- | --- | --- | --- |
| Original TJL19 LNN pilot — inherited | Implemented neural-first routing, physical-state transfer and a trained fixed-geometry temperature-slice predictor. | Neural-seeded correction accepted 10/10 held-out inputs versus 9/10 classically. | Narrow interpolation only; accepted results retain the historical dry-supersaturation advisory policy. No learned wet-tray coverage or broad speedup was established. | [Model card: measurements, splits and hashes](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/model-card.json>); [scope and measured result](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/legacy-tjl19-pilot.md>). |
| Coupled methane dry/wet experts — inherited | Trained separate dry/wet predictors from 80 dry and 36 wet TRAIN profiles; implemented residual ranking and bounded wet refinement. | Neural modes accepted all 40 held-out inputs in both repetitions versus 35 classically. On the eight wet-boundary inputs, the 16 neural runs returned 14 wet-equilibrium and two dry-equilibrium results. | Broad-domain neural results included dry-supersaturation advisories: 40/40 accepted is not 40/40 strict wet/equilibrium coverage. Arbitrary wet zones, geometry and chemistry remain outside qualification. | [Model card: per-domain modes and water classifications](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/methane-model-card.json>); [domain and wet-boundary limits](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/methane-model.md>). |
| Generalized Gen2 — inherited | Trained a shared stage predictor for 2–64 trays and varied composition/equipment using 483 qualified profiles. | Recovered 141 strictly qualified matrix failures. On its 64-case benchmark, neural-first produced 31 qualified outcomes versus 25 classically. | Neural-only qualified 42/395 held-out cases versus 113 classically. Increasing width or the correction-iteration cap did not improve the selected validation result; neural-first increased median benchmark latency. | [Measured results and limitations](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/generalized-model.md>); [model card](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/generalized-model-card.json>); [preserved artifacts](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/gen2-cache-manifest.json>). |
| Generalized Gen3 — inherited, compared again here | Added 96 TRAIN rescues, tested MLP and factorized outputs, and compared nearest-profile transfer. | On the remaining-failure pool, the factorized network rescued 86 strict cases and nearest-profile transfer 146, including 14 historical hard failures. | The selected network qualified 33/252 fresh inputs versus 38 for Gen2 and 63 classically. The 195-case union of separate recovery runs was never a measured combined cascade. | [Results by operating/recovery population](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/gen3-model.md>); [model card](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/gen3-model-card.json>); [archive manifest](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/gen3-cache-manifest.json>). |
| Transformer development — this branch | Expanded training data; ran a matched CUDA Transformer/MLP pilot; implemented native inference and complete-request evaluation. | Pilot mean profile objective: 1.298 versus 1.541 for MLP. In the unified native campaign, Transformer FIRST qualified 98/252 versus 66 classically. | Profile improvement alone did not establish convergence. In that native campaign, mean FIRST time was 3.753 seconds versus paired classical 3.437 seconds; broad speedup was not demonstrated. | [Pilot measurements](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-pilot-summary.json>); [native results](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/unified-evaluation-results.md>); [native evidence archive](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/unified-evaluation-cache-manifest.json>). |
| Hybrid and training follow-ups — this branch | Tested material completion, native-anchor residual models, data additions/curation, support treatment, checkpoint selection and absolute-output anchor features. | The later full-anchor absolute-output model F0 reached 168/405 FIRST versus 166 for matched continuation C and 161 for I, in both historical validation blocks. | The earlier residual hybrids did not pass replacement gates. Canonical support scaling, the tested trace-margin treatment and compact-anchor simplification did not improve their matched controls. Individual studies and proofs are separated below. | [Residual-hybrid results](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/results.md>); [support diagnosis and ablation](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-diagnosis/results.md>); [controlled follow-up results](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/trace-followup/results.md>); [follow-up verification](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-trace-followup/v1/verification.json>). |
| Larger-network test — this branch | Compared function-preserving two-/four-layer continuations from F0 across two seeds and two native validation blocks. | Initial Java predictions matched on all 804 optimization inputs; added layers trained and reduced profile error. | Matched FIRST changes were +1 and −1 across seeds; the +1 pair regressed in mean latency. No candidate passed the gates against F0. | [Complete capacity results](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/capacity-followup/results.md>); [native case/contrast analysis](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-capacity-followup/v1/validation-analysis.json>); [verification proof](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-capacity-followup/v1/verification.json>). |

**How to read the proof:** each row links its own recorded outcomes and, where available, machine-readable measurements or artifact bindings. Model cards and summaries state the measured result; manifests and verification records identify the underlying models, inputs, journals and checks. The early pilot’s **accepted** counts can include advisories and must not be relabelled as later **strict-qualified** counts. “Remains unproven” identifies missing evidence, rather than a failed experiment. The detailed branch campaigns below use the same done/worked/did-not-work structure.

The data also developed in stages. The generalized training pool grew from **483 to 579 to 805** certified columns. A later original-matrix sweep added **101** profiles, yielding **906**. Curation held one historically disputed target aside, leaving **905**; the corresponding original-data continuation subset contains **804**. The earlier local wet experts used a separate qualified wet dataset. The generalized/Transformer pools in these studies contain no wet-qualified training profiles, so their steam-fed cases do not establish wet-equilibrium coverage.

The engineering work includes the evaluation machinery as well as the models: native inference parity, cancellation and worker-ownership checks, strict label/provenance checks, frozen selections, paired gains and losses, repeated validation, and verifiable archives. The resulting handoff therefore records both successful developments and rejected approaches. The campaign-by-campaign sections below explain the measured results and the limits of each comparison.

Background references: [original TJL19 pilot](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/legacy-tjl19-pilot.md>), [coupled methane experts](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/methane-model.md>), [generalized Gen2](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/generalized-model.md>), [Gen3 development and recovery results](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/gen3-model.md>), and [neural initialization guide](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/README.md>).

## Branch scope and recorded snapshot

Branch: `codex/v4-transformer-investigation` · Prepared: 2026-09-13T13:01:51.421768+00:00

Scientific snapshot: `98d19cda108fd3aecb97e7a0b05e2d314c780abe`. The opening describes the inherited LNN groundwork and the subsequent Transformer/hybrid work. The detailed branch campaign history covers work after branch base `ef052603d2f840e7f624ab034f234ddf3953d146` (`codex/v3-neural-gen3-comparison`), including the completed capacity experiment. Documentation commits follow that snapshot; this expanded opening does not change the sealed results or archive inventory.

Workspace: `C:/Users/wormz/.codex/worktrees/8848/CreateChemE`. The branch diff changes offline research tools, three Gradle verification tasks and ignore rules; it contains no changes under `src/`. No experiment automatically promoted a production model.

## Where the work ended

The strongest completed historical full-validation candidate is **F0**, the two-layer full-anchor, absolute-output Transformer from the controlled continuation study: 168/405 strict FIRST successes and 134/405 ONLY successes in both of that study’s blocks, versus the retained original Transformer I at 161/405 and 116/405. Its gain over matched continuation C is only two FIRST cases; the full seven-case difference from I also includes continued training.

The later four-layer experiment did not meet its registered replicated depth rule.

The table below is from the latest completed capacity campaign. FIRST includes classical fallback; ONLY is a separately executed neural-only request. Counts require the unchanged native strict audit and final Newton certificate.

| Latest pipeline | ONLY, blocks 1 / 2 | FIRST, blocks 1 / 2 | Pooled all-case FIRST mean ms |
| --- | --- | --- | --- |
| F0 | 134 / 134 | 168 / 168 | 4018.66 |
| L2-20260913-s2080 | 120 / 120 | 161 / 161 | 4052.02 |
| L4-20260913-s2080 | 123 / 123 | 162 / 162 | 4189.71 |
| L2-20260914-s2080 | 125 / 125 | 161 / 161 | 4241.35 |
| L4-20260914-s2080 | 129 / 129 | 160 / 160 | 4226.03 |
| L4-20260914-s3120 | 118 / 118 | 159 / 159 | 4262.24 |
| I | 116 / 116 | 161 / 161 | 4214.64 |

Latest candidates passing every further-qualification gate against F0: none. Those gates require strict improvement in both blocks, preservation of the contemporaneous classical-success union, and no pooled mean FIRST latency regression. Even passing supports further qualification, not deployment.

Read [the capacity report](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/capacity-followup/results.md>) and [the preceding controlled-study report](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/trace-followup/results.md>) first.

## Work and results by campaign

### 1. Expanded certified data and CUDA architecture pilot

**Done.** Qualified training coverage grew from 579 to 805 columns: 133 original TRAIN rescues and 93 fresh Gen3 labels. One additional branch-ambiguous fresh success was quarantined. The pilot treated each column, rather than each correlated tray/node, as the sampling and scoring unit. It preserved 168 qualified validation references and historical fold identities, and froze replacement holdouts before use.

Six matched fits compared a two-layer width-64/four-head Transformer (83,800 parameters) with a residual MLP (83,288), using three seeds. Mean validation objective was 1.298 versus 1.541; temperature MAE was 10.94 versus 12.57 K. Mean GPU fit time was 21.75 versus 12.07 seconds. This supported the Transformer for profile prediction, not yet native convergence.

**Worked.** The Transformer beat the matched MLP on the three-seed mean profile objective (1.298 versus 1.541) and temperature MAE (10.94 versus 12.57 K).

**Did not work / remains unproven.** The pilot did not establish native convergence or an inference speedup. It retained gaps at stage counts 38/52 and had no wet-qualified training profiles.

**Proof records:** [Per-seed measurements](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-pilot-summary.json>); [Dataset/model artifact hashes](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-cache-manifest.json>).

**Detailed reports and method notes:** [tools/neural/transformer-investigation.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-investigation.md>).

### 2. Native exports and unified convergence/runtime evaluation

**Done.** Added an offline Java Transformer initializer, model export/parity checks, malformed-artifact rejection, cancellation/concurrency checks, and a common three-strategy evaluator. The earlier ten-second neural validation/test and separate 64-case timing scheme were superseded by a serial two-second neural / thirty-second request evaluation on every one of a frozen 252-input population. The interrupted historical nearest-profile timing journal was retained as incomplete.

The unified campaign ran 3,024 requests. FIRST strict counts were Transformer 98/252, matched MLP 89, nearest-profile 81, and factorized Gen3 79; paired classical controls were 66. Transformer FIRST gained 32 classical failures without losing a classical success, but its all-case mean was 3.753 seconds versus paired classical 3.437 seconds. The result was improved coverage, not a general runtime speedup.

**Worked.** On the same 252 inputs, Transformer FIRST qualified 32 additional cases that classical initialization failed, while retaining every classical success: 98 versus 66 qualified cases.

**Did not work / remains unproven.** Mean complete-request time increased from 3.437 to 3.753 seconds. The superseded ten-second runs and incomplete nearest-profile timing run cannot be pooled into this result.

**Proof records:** [Paired cases, costs and outcomes](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/unified-evaluation-summary.json>); [Verified journals and dependency archives](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/unified-evaluation-cache-manifest.json>).

**Detailed reports and method notes:** [tools/neural/unified-evaluation-results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/unified-evaluation-results.md>) and [tools/neural/unified-evaluation-findings.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/unified-evaluation-findings.md>).

### 3. Accuracy/loss ablations and planning review

**Done.** Six predeclared accuracy arms across three seeds compared baseline training, decoded-flow loss, and two regularization strengths with/without decoded-flow loss. The baseline had the best mean profile-selection score (1.111 versus 1.123–1.136). The selected baseline seed was 20260911; no improvement was attributed to the added loss/regularization.

On that campaign’s separate prospectively frozen 252-case test, the selected candidate reached 86 FIRST successes versus 83 for its incumbent, with 10 gains and seven losses. Both preserved the 58 paired classical successes. The subsequent ChatGPT review prioritized complete native validation and case-level diagnosis over profile score alone.

**Worked.** The selected baseline checkpoint improved FIRST from 83 to 86 on this campaign's frozen test, with 10 gains and seven losses; it also improved the reported reference-profile errors.

**Did not work / remains unproven.** None of the added regularization/decoded-flow-loss arms improved the baseline mean selection score. The candidate gain is not evidence that those added terms helped.

**Proof records:** [Ablation and native measurements](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-accuracy-summary.json>); [Registered source and result hashes](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-accuracy-cache-manifest.json>).

**Detailed reports and method notes:** [tools/neural/transformer-accuracy-results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-accuracy-results.md>) and [tools/neural/transformer-accuracy-review.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-accuracy-review.md>).

### 4. Native checkpoint selection with ten workers

**Done.** Three archived seeds were evaluated without retraining on all 405 validation cases. FIRST counts were 154, 161 and 154 for seeds 20260910, 20260911 and 20260912. No alternative met all replacement gates, so seed 20260911 remained the reference. Its frozen test execution reached 80/252 FIRST and 54/252 ONLY, with 51 classical successes on that campaign’s test population.

Revision 1 stopped before scientific evaluation because a scheduler fixture was neural-rescued but not cold-classical qualified. Revision 2 changed only the numerical-check fixtures and their registration; model bytes, inputs, selection rule, solver and budgets were preserved. A ten-worker lifecycle/failure/cancellation harness was added. Do not execute the superseded v1 campaign entry points.

**Worked.** Full native validation discriminated the archived seeds: the retained seed reached 161/405 FIRST versus 154 for each alternative. Ten-worker numerical and ownership checks passed after the documented fixture correction.

**Did not work / remains unproven.** No alternative passed every replacement gate. Revision 1's failed fixture check was not a completed campaign and must not be treated as one.

**Proof records:** [Selection gates and complete outcomes](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/checkpoint-selection-summary.json>); [Verified revision-2 archive](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/checkpoint-selection-cache-manifest.json>).

**Detailed reports and method notes:** [tools/neural/checkpoint-selection-results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/checkpoint-selection-results.md>) and [tools/neural/checkpoint-selection-amendment-v2.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/checkpoint-selection-amendment-v2.md>).

### 5. Matched generation comparison and balance-formulation correction

**Done.** Frozen Gen2, Gen3 and Transformer models were compared on the same exposed populations, reusing verified Transformer journals. FIRST validation counts were 136, 134 and 161 out of 405; the corresponding test counts were 60, 66 and 80 out of 252. This compared whole generations with different training/selection history, not architecture alone. No default changed.

An additive erratum corrected a proposed material-loss formulation: current V3 pumparounds are prescribed stage-heat terms, not circulation edges in component material balances. They belong in a separately proposed energy loss. The comparison’s frozen numerical results and archive were not edited.

**Worked.** The Transformer led the common populations: FIRST validation 161 versus Gen2/Gen3 at 136/134, and test 80 versus 60/66.

**Did not work / remains unproven.** These whole-generation comparisons do not isolate architecture or demonstrate a general speedup over classical initialization. A union of successes from separate models is not a measured cascade. The original proposed material-loss formulation required the linked heat-duty erratum.

**Proof records:** [Matched outcomes and timings](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/generation-comparison-summary.json>); [Per-case membership and results](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/generation-comparison-case-map.jsonl>); [Verified comparison archive](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/generation-comparison-cache-manifest.json>).

**Detailed reports and method notes:** [tools/neural/generation-comparison-results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/generation-comparison-results.md>) and [tools/neural/generation-comparison-recommendation-erratum-v1.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/generation-comparison-recommendation-erratum-v1.md>).

### 6. Material-completion feasibility and original-matrix salvage

**Done.** On twenty dry/no-side-draw TRAIN inputs, frozen-model material completion increased FIRST strict outcomes from 17 to 19, with two gains and no losses; preparation applied to eight cases and declined twelve. This was a small TRAIN feasibility result, not held-out qualification. Material closure did not imply energy/equilibrium closure.

A one-pass, ten-worker sweep of 2,793 admitted original conditions recovered 101 novel strict TRAIN labels. Of 1,993 TRAIN requests, 752 were strict; 651 duplicated existing N inputs and 101 were appended. Validation 161/405 and historical test 164/395 outcomes were recorded but not added to training. The original 805-row N prefix and all nontraining records remained unchanged, yielding N+1=906. Advisory outcomes were excluded, every failed attempt was retained, and no retry campaign was used.

A separate organization audit moved 71 misplaced untracked cache copies from the main worktree to this worktree after confirming every file matched its canonical copy. This was cache housekeeping, not a production source change.

**Worked.** Material completion gained two of twenty TRAIN cases without losses. Separately, the salvage sweep acquired 101 novel strict TRAIN profiles while preserving the 805-row prefix and held-out records. The cache relocation audit matched all 71 files.

**Did not work / remains unproven.** Completion prepared only eight of twenty inputs and did not establish held-out or coupled energy/equilibrium performance. Salvage supplied no wet-qualified additions and did not prove that training on them would improve a model.

**Proof records:** [Completion case-level results](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/hybrid-initializer-feasibility-summary.json>); [Completion archive](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/hybrid-initializer-feasibility-cache-manifest.json>); [Salvage verification](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/salvage_verification.json>); [Certified data and journal/archive bindings](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/salvage_dataset_manifest.json>).

**Detailed reports and method notes:** [tools/neural/hybrid-initializer-feasibility-results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/hybrid-initializer-feasibility-results.md>), [tools/neural/salvage_results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/salvage_results.md>) and [tools/neural/worktree-artifact-organization.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/worktree-artifact-organization.json>).

### 7. Learned native-baseline residual hybrids, N versus N+1

**Done.** Six paired hybrid fits used three seeds, the same N-derived normalization and 4,160 updates. The branch classifier chose a native MATERIAL_CLOSED anchor in training and inference. Continuous coordinates were learned residuals, and the pipeline included the exact material-completion wrapper.

Across two full-validation blocks, the selected N hybrid reached 159/405 FIRST and selected N+1 reached 155, versus incumbent I and I+wrapper at 161. On this study’s new frozen 252-case test, I reached 92, I+wrapper 93, N 91, and N+1 89 FIRST successes in both blocks. N gained/lost 12/13 I cases; N+1 gained/lost 10/13. No pipeline passed the replacement gate. Better raw profile errors did not imply better convergence; the wrapper applied to relatively few inputs.

A supported test entry point was added to recompute selection and validation-journal bindings before releasing test requests. Final sealing also replays selection, summaries, case maps and report text. These changes repaired verification boundaries without changing frozen scientific treatments.

**Worked.** The wrapper alone gained one fresh-test case with no losses (93 versus 92 FIRST). The paired training, repeated evaluation and selection/sealing guards produced a reproducible comparison; selected N also slightly improved some raw profile errors.

**Did not work / remains unproven.** Neither learned residual hybrid met the replacement gate: selected N/N+1 reached 159/155 validation FIRST versus I at 161, and 91/89 on the fresh test versus 92. The wrapper's one test gain did not satisfy the preregistered validation gate.

**Proof records:** [All seed/block outcomes and paired losses](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/native-results.md>); [Raw/completed profile diagnostics](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/profile-results.md>); [Verified result and case-map archive](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/results-cache-manifest.json>).

**Detailed reports and method notes:** [tools/hybrid-learning/results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/results.md>), [tools/hybrid-learning/native-results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/native-results.md>), [tools/hybrid-learning/profile-results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/profile-results.md>), [tools/hybrid-learning/test-gate-note.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/test-gate-note.md>) and [tools/hybrid-learning/sealing-note.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/sealing-note.md>).

### 8. Hybrid diagnosis and controlled support-coordinate ablation

**Done.** Case analysis linked many lost successes to continuous trace flows falling below the existing cutoff even when the presence head kept the component. Completion was not the direct source of those losses because it had not prepared their seeds. The MATERIAL_CLOSED composition anchor was essentially the feed prior and supplied little separation information. The added 101 cases were learned; uniform gradient conflict was not supported. Equal update budgets reduced old-example exposure in N+1.

A paired, function-preserving canonical support-output reparameterization did not help: on the 65-case diagnostic panel, empirical control FIRST was 46 and canonical FIRST 43 in each block. Canonical gained/lost 4/7 FIRST cases, and total above-floor omissions increased from 1,150 to 1,338. The experiment used 1,170 measured requests plus 36 warmups. Its empirical control reproduced the earlier selected checkpoint exactly. The proposed scaling was rejected for this recipe.

**Worked.** The empirical control reproduced the earlier selected weights exactly. Recorded lost/gained cases showed a strong association with omitted continuous trace flows; completion was ruled out as the direct cause on the specified lost cases because it had not prepared their seeds.

**Did not work / remains unproven.** Canonical support scaling reduced panel FIRST from 46 to 43 and increased omissions from 1,150 to 1,338. The support association alone does not prove which physical root is necessary or establish a successful new loss.

**Proof records:** [Paired ablation analysis](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-hybrid-diagnosis/v1/ablation-analysis.json>); [Reconstruction and artifact checks](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-hybrid-diagnosis/v1/verification.json>); [Complete diagnosis archive](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-diagnosis/cache-manifest.json>).

**Detailed reports and method notes:** [tools/hybrid-diagnosis/results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-diagnosis/results.md>).

### 9. Certified-data curation

**Done.** All 906 certified records passed strict source/shape checks, but gd-s08-w0-p1-d0-r00 had a recorded historical profile disagreement and was held unchanged in quarantine. The remaining 905 records had no admissible nonself representative pairs under the registered coverage rules, even at half/double continuous resolution: 905 selected, zero reserve, one quarantined. No arbitrary smaller target size was imposed, and no fit or solver campaign was run for curation.

All eleven LIQUID_ONLY cases remained. Stage counts 38 and 52 still had no fitted labels; no wet-qualified or VAPOR_ONLY profiles were created. The quarantine records a target-consistency problem, not proof of multiple physical roots.

**Worked.** The procedure retained all 905 unambiguous records and all eleven LIQUID_ONLY examples while preserving the disputed record separately with its provenance.

**Did not work / remains unproven.** No demonstrably redundant nonself pairs were found at half, primary or double resolution, so no smaller representative subset was justified. No retraining benefit, missing-regime coverage or resolution of the disputed target was established.

**Proof records:** [Per-case decisions, quotas and witnesses](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/training-curation/v1/selection.json>); [Selection verification](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/training-curation/v1/verification.json>); [Preserved curation artifacts](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/training-curation/cache-manifest.json>).

**Detailed reports and method notes:** [tools/training-curation/results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/training-curation/results.md>).

### 10. Controlled trace, anchor, checkpoint and exposure follow-ups

**Done.** Ten continuation fits (five arms, two order seeds) produced thirty fixed checkpoints. I was preserved at initialization; new anchor embeddings started with zero coupling. C used curated N804, T added one TRAIN-calibrated trace-margin term, F added full native anchors with absolute outputs, K added compact temperature/traffic anchors, and D added the 101 curated profiles. No wrapper was added to these new arms.

The primary full-validation seed was fixed in advance. Twelve pipelines were tested twice on 405 historical inputs, with 35,400 measured requests and 336 TRAIN warmups including screening. At +4,160, FIRST counts were I 161, C 166, T 162, F 168, K 165 and D 167, identical across both blocks. F became the F0 starting reference for the capacity study.

The trace treatment lost four net FIRST cases against matched C and increased above-floor omissions from 778 to 842. Compact K lost one net FIRST case against C and three against full F. D at equal +4,160 updates gained one net case against C, but D at +4,640 matched old-example exposure fell to 160 versus C@4,160 at 166; extra C training alone fell to 162. The native and profile rules chose the same primary T checkpoint, so no checkpoint-choice gain was observed for T. Seven endpoints passed the further-qualification rule against I; none was deployed and no fresh test was used.

**Worked.** F at +4,160 reached 168/405 FIRST in both blocks, versus C at 166 and I at 161. Seven endpoints met the registered further-qualification gates against I. The experiment separated additional data from additional updates and retained the fixed comparisons.

**Did not work / remains unproven.** T lost four net FIRST cases to matched C and increased omissions; compact K lost three net cases to F. Matching old-example exposure with D@4,640 did not improve C@4,160. Native and profile selection chose the same primary T checkpoint, so no selection improvement was observed. These results did not establish fresh-test generalization or authorize deployment.

**Proof records:** [Fixed and selected contrasts with paired case groups](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-trace-followup/v1/validation-analysis.json>); [Exact replay and exposure verification](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-trace-followup/v1/verification.json>); [Sealed study archive](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/trace-followup/cache-manifest.json>).

**Detailed reports and method notes:** [tools/trace-followup/results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/trace-followup/results.md>) and [tools/trace-followup/protocol.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/trace-followup/protocol.md>).

### 11. Robustness research and the four-layer capacity experiment

**Done.** Research covered robustness to poor initial guesses and numerical acceptance tolerance. The user supplied prior negative evidence for loosening criteria; the suggestion to repeat a tolerance sweep, including loose initialization followed by strict polishing, was withdrawn. No new tolerance experiment was run here. The offline follow-up probe uses default strict closure directly, so changing the game config would not change this benchmark.

The authorized capacity experiment grew F0 from two to four width-64 layers (89,496 to 156,440 parameters). Two appended residual blocks initially contributed exactly zero through their output projections. Both arms started with identical predictions and fresh AdamW states, used matched N804 minibatches for order seeds 20260913 and 20260914, and received +3,120 updates with checkpoints at +1,040/+2,080/+3,120. The primary comparison remained fixed at +2,080; independent native-selected endpoints were secondary.

Four fits and twelve exports completed. Screening covered fourteen pipelines; full validation retained seven pipelines in two reversed blocks. Accounting is 19,740 measured native requests plus 168 TRAIN warmups. No new data or test set, loss, decoder, support threshold, solver acceptance gate or production default changed.

The deeper model reduced profile error, but the final native results and paired losses above determine its practical value. The native core was rebuilt from 113 archive-matched Java sources with only Gson as a compiler dependency. All 804 optimization inputs had identical complete Java F0/L2/L4 initial predictions; eight malformed manifests were rejected. Eleven preflight tests, four decision tests, all twelve export parity checks, cancellation/ten-worker checks, tensor and optimizer verification, independent per-ID exposure reconstruction, exact analysis/report replay, and archive checks passed.

The preserved capacity preflight failures involved sandbox access to the Java dependency cache and an initial source closure that included an unused Minecraft-dependent probe; error printing also encountered an encoding failure. They were corrected before registration and fitting. They were not discarded fit trajectories or discarded native campaign outcomes.

**Worked.** All 804 initial Java predictions matched; the extra layers received gradients and departed from identity. Four-layer fits lowered profile scores and gained three/four ONLY successes over matched L2 at the fixed endpoint.

**Did not work / remains unproven.** Complete-request FIRST effects were mixed (+1 and -1 across seeds), and the +1 pair regressed in mean latency. All new candidates fell below F0 and none passed its gates. No new tolerance sweep, solver-facing loss, seed portfolio or recovery modification was executed; those research proposals remain unproven.

**Proof records:** [Native contrasts, losses and cost distributions](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-capacity-followup/v1/validation-analysis.json>); [Final gate decisions](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-capacity-followup/v1/report-summary.json>); [Tensor/exposure/analysis verification](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/build/neural-capacity-followup/v1/verification.json>); [Sealed capacity archive](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/capacity-followup/cache-manifest.json>).

**Detailed reports and method notes:** [tools/capacity-followup/results.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/capacity-followup/results.md>), [tools/capacity-followup/protocol.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/capacity-followup/protocol.md>), [tools/capacity-followup/research-context.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/capacity-followup/research-context.md>) and [tools/capacity-followup/robustness-research.md](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/capacity-followup/robustness-research.md>). The robustness note is a preserved pre-capacity research snapshot; its unexecuted proposals are not additional measured results.

## Dataset and model naming

| Name | Meaning |
| --- | --- |
| N / H805 training | 805 original certified TRAIN records; H805 is the old residual hybrid with completion wrapper. |
| N+1 | The same 805-record prefix plus 101 certified additions = 906. |
| N804 | Original N excluding the one disputed historical target. |
| N905 / curated selected | N804 plus the same 101 additions; all eleven LIQUID_ONLY records retained. |
| I | Retained seed-20260911 absolute-output Transformer from native checkpoint selection, 83,800 parameters; distinct from the earlier pilot’s seed-20260912 export. |
| C / T / D | Plain continuation / trace treatment / added-data continuation in the controlled study. |
| F / F0 | Full-anchor absolute-output model, 185 inputs and 89,496 parameters; F0 is F-20260911-s4160. |
| K | Compact-anchor absolute-output model, 103 inputs and 84,248 parameters. |
| L2 / L4 | Matched continuations from F0 in the latest capacity study; update numbers are additional to F0. |

Excluding the disputed target from new minibatches does not undo its historical influence through pretrained weights. Steam-fed inputs are not evidence of wet-equilibrium training. The 168 certified validation references are a subset of the 405 evaluation inputs, not a second independent evaluation population.

## Rules for interpreting and extending the results

- Different campaigns used different 252-input populations. Compare within the named campaign and its input hashes; do not pool their counts.
- Serial timings, concurrent timings and ten-second historical neural runs are different conditions. All-case costs include failed requests and fallback. Stable common-success costs are the appropriate additional check for speed claims.
- Repeated blocks reuse identical inputs. Standard deviations usually describe case variability, not a confidence interval from independent campaigns.
- Strict outcomes require native audit plus final Newton evidence; advisory success is excluded. No failed input leaves the denominator.
- FIRST terminal evidence can belong to classical fallback. A separately timed ONLY request is not FIRST’s internal neural trajectory.
- Initial projected residuals and audit ratios depend on support and scaling. Neither material closure, lower profile RMSE, nor fewer support omissions alone proves a better warm start.
- Preserve the ten-worker budget, solver/property/support/decoder rules and the distinction between fixed endpoints and selected endpoints. Do not rerun exposed tests to tune a choice.

The next research proposal is a frozen-weight, paired trajectory diagnosis under strict criteria: compare accepted step sizes, residual families, support/phase changes and rejected property-domain trials on gained/lost cases. Only then choose one bounded intervention, such as solver-facing training, a budgeted seed selector, or a recovery change. These are proposals, not completed improvements. Current code already contains line search and recovery mechanisms; adding generic damping is not automatically a new method.

## Artifacts, environment and safe continuation

Git contains the protocols, source, readable reports and manifests. Large datasets, checkpoints, journals and ZIPs live under the ignored .neural-cache directory; working copies under build can be removed by Gradle clean. Preserve the caches together with this branch and the linked Gen2/Gen3 prerequisite caches.

16 authoritative branch-study archives were rehashed for this handoff. Full SHA-256 values, sizes, manifest bindings and the captured commit history are in [BRANCH_HANDOFF_ARTIFACTS.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/BRANCH_HANDOFF_ARTIFACTS.json>).

| Study manifest | Archive | Size MiB |
| --- | --- | --- |
| [tools/neural/transformer-cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-cache-manifest.json>) | [.neural-cache/transformer-investigation-v1/study.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/transformer-investigation-v1/study.zip>) | 19.77 |
| [tools/neural/unified-evaluation-cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/unified-evaluation-cache-manifest.json>) | [.neural-cache/unified-evaluation-v1/study.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/unified-evaluation-v1/study.zip>) | 9.30 |
| [tools/neural/unified-evaluation-cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/unified-evaluation-cache-manifest.json>) | [.neural-cache/unified-evaluation-v1/dependencies.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/unified-evaluation-v1/dependencies.zip>) | 20.72 |
| [tools/neural/transformer-accuracy-cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/transformer-accuracy-cache-manifest.json>) | [.neural-cache/transformer-accuracy-v1/study.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/transformer-accuracy-v1/study.zip>) | 42.42 |
| [tools/neural/checkpoint-selection-cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/checkpoint-selection-cache-manifest.json>) | [.neural-cache/checkpoint-selection-v2/study.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/checkpoint-selection-v2/study.zip>) | 67.90 |
| [tools/neural/generation-comparison-cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/generation-comparison-cache-manifest.json>) | [.neural-cache/generation-comparison-v1/study.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/generation-comparison-v1/study.zip>) | 21.41 |
| [tools/neural/hybrid-initializer-feasibility-cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/hybrid-initializer-feasibility-cache-manifest.json>) | [.neural-cache/hybrid-initializer-feasibility-v1/study.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/hybrid-initializer-feasibility-v1/study.zip>) | 1.63 |
| [tools/neural/salvage_dataset_manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/neural/salvage_dataset_manifest.json>) | [.neural-cache/salvage-nplus1-v1/certified-campaign.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/salvage-nplus1-v1/certified-campaign.zip>) | 101.82 |
| [tools/hybrid-learning/training-cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/training-cache-manifest.json>) | [.neural-cache/hybrid-learning-v1/training.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/hybrid-learning-v1/training.zip>) | 219.84 |
| [tools/hybrid-learning/results-cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-learning/results-cache-manifest.json>) | [.neural-cache/hybrid-learning-v1/results.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/hybrid-learning-v1/results.zip>) | 139.72 |
| [tools/hybrid-diagnosis/cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/hybrid-diagnosis/cache-manifest.json>) | [.neural-cache/hybrid-diagnosis-v1/study.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/hybrid-diagnosis-v1/study.zip>) | 15.67 |
| [tools/training-curation/cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/training-curation/cache-manifest.json>) | [.neural-cache/training-curation-v1/study.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/training-curation-v1/study.zip>) | 59.82 |
| [tools/trace-followup/registration-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/trace-followup/registration-manifest.json>) | [.neural-cache/trace-followup-v1/registration.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/trace-followup-v1/registration.zip>) | 33.48 |
| [tools/trace-followup/cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/trace-followup/cache-manifest.json>) | [.neural-cache/trace-followup-v1/results.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/trace-followup-v1/results.zip>) | 365.56 |
| [tools/capacity-followup/registration-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/capacity-followup/registration-manifest.json>) | [.neural-cache/capacity-followup-v1/registration.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/capacity-followup-v1/registration.zip>) | 35.41 |
| [tools/capacity-followup/cache-manifest.json](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/tools/capacity-followup/cache-manifest.json>) | [.neural-cache/capacity-followup-v1/results.zip](<C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-cache/capacity-followup-v1/results.zip>) | 186.81 |

Follow each manifest’s restoration rules: legacy archives can use different restore roots. The trace/capacity registration and results ZIP entries are repository-root relative. Their source/runtime checks bind the recorded paths; relocation requires an explicit provenance-preserving restoration or amendment, not silent edits to a sealed plan.

The latest environment used Python from `.neural-venv/Scripts/python.exe`, PyTorch 2.10.0+cu128, an RTX 4070 Ti, deterministic float32 CUDA with TF32 disabled, Java 21.0.11, and Gson 2.10.1. The latest native runtime is under `build/neural-capacity-followup/native-core-v2` and `classes-v1`; exact external/runtime paths and hashes are in its registration.

Read-only verification of the completed capacity archive:

```powershell
Set-Location -LiteralPath 'C:/Users/wormz/.codex/worktrees/8848/CreateChemE'
.\.neural-venv\Scripts\python.exe tools/capacity-followup/capacity_seal.py verify
```

The `fit`, `export`, benchmark, registration and sealing entry points create experimental artifacts. They are not status commands; do not invoke them to inspect an already completed campaign. Existing directories are intentionally guarded against overwrite or silent retries. Protected Java-cache and Git writes may require the app’s permission flow.

Planning/review integration was separately updated with explicit user approval to codex-with-chatgpt 0.1.3 (checkout commit 9663b887), and the workspace connection was restored. This was machine/workspace tooling work, not a model or solver change. The final independent review uses the same [ChatGPT conversation](https://chatgpt.com/g/g-p-6aa026927b5c819187b10da97168efd4-createcheme/c/6aa13fb1-1d04-83ec-8fb0-8f0a7c432664).

## Commit trail

The base already contained earlier Gen2/Gen3 training. The following are the branch-specific commits through the scientific snapshot; the handoff’s own commit and any later review correction can be found with git log.

```text
f02a4d7 Expand qualified training data and investigate CUDA stage transformers
0a48549 Add native transformer and unified column evaluation study
d02834c Evaluate transformer accuracy ablations and preserve native results
459313b Document ChatGPT accuracy review and prioritized experiments
931a56c Evaluate archived checkpoints with ten-worker native validation
4c5e9be Compare frozen neural generations on matched native populations
c56f8bc Clarify native balance loss recommendation in comparison erratum
9142aa4 Investigate mechanistic completion of frozen transformer seeds
f46ab75 Preserve workspace artifacts and certify N-plus-one salvage data
e0eea20 Train matched native-baseline residual transformers and freeze repeated evaluation
f071800 Guard fresh-test selection and recompute reports before sealing
84e7aa8 Verify separately registered native profile diagnostics in final reports
444f9b0 Bind final verification to the exact reporting source snapshot
f459085 Record verified N and N+1 hybrid comparison results
2c41d3e Diagnose hybrid trace-support losses with matched ablation
bad3346 Curate certified training profiles by coverage and label consistency
54b14f4 Register controlled trace, anchor and exposure continuation study
dde7086 Add paired native follow-up analysis and artifact verification
6528c38 Seal controlled trace, anchor and exposure follow-up results
e745c4b Register function-preserving four-layer capacity experiment
98d19cd Seal matched two-layer and four-layer capacity results
```

The experiment and its result preservation are complete. No production rollout or additional research campaign is pending under this task. Future experiments require their own defined scope and frozen evaluation policy.

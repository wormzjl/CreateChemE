# Why the hybrid underperformed, and what to improve

The strongest lead is **local trace-flow/support error**, which the average profile score does not adequately distinguish. The cold material anchor supplies little separation information. Added data changes coverage and exposure, but is not simply ignored by training. One controlled experiment rejected the proposed canonical support-output scaling as a useful fix. Keep the current Transformer.

## What the case evidence shows

| Selected hybrid | Validation ONLY gains / losses | Validation FIRST gains / losses | Retrospective test ONLY gains / losses | Retrospective test FIRST gains / losses |
|---|---:|---:|---:|---:|
| N-20260911 | 30 / 43 | 15 / 17 | 17 / 22 | 12 / 13 |
| Nplus1-20260910 | 34 / 41 | 17 / 23 | 19 / 25 | 10 / 13 |

These are stable paired strict outcomes across the two original blocks. FIRST includes classical fallback, which conceals some neural-only losses. FIRST terminal failure evidence is labelled as neural, classical-backup or unknown; a separately timed ONLY request is never treated as its internal trajectory. The former test is retrospective evidence, not a new holdout.

**Completion is not the direct source of these lost cases.** None of the selected hybrids’ incumbent-only validation or retrospective-test losses was PREPARED. Completion returned the raw seed on those cases. Its own raw/final physical changes are retained separately, including changed equation support and unavailable property evaluations. Lower full-grid material defect does not establish energy, equilibrium or audited solution quality.

**Meaningful phase-component flows are omitted.** On the same certified references within neural-only losses:

| Candidate | Reference columns in loss group | Incumbent omissions / column | Candidate omissions / column | Candidate omissions despite presence head keeping the component |
|---|---:|---:|---:|---:|
| N-20260911 | 34 | 0.294 | 11.265 | 9.706 |
| Nplus1-20260910 | 28 | 0.357 | 7.500 | 7.321 |

An omission here means that the certified reference flow is above the unchanged decoder threshold, but the predicted decoded flow is zero. Below-threshold reference traces are counted separately. Most extra omissions occur after the presence head keeps a component: the continuous flow prediction falls below the trace cutoff. The relationship reverses on N’s newly gained cases, where the incumbent averages 12.261 such omissions and N only 0.261. This is a strong case-level association, not proof that every reference phase is necessary at every other admissible root. Native support refresh can also change the initial seed.

On N’s 43 validation neural-only losses, raw material-residual median improves from 3.717 to 2.880, while VLE-residual median worsens from 20.410 to 27.664; the VLE maximum is worse on 32/43 inputs. Among the 34 losses with original certified references, average temperature and phase-total errors also improve slightly. Thus neither average profile agreement nor material closure alone captures the difficult local equilibrium/support errors. Native scaled maxima use state-dependent scales and support, so they are descriptive diagnostics rather than one common optimization objective.

Iteration limits are frequent in the neural-only loss evidence: N has 36 iteration-limit, 5 neural-time-limit and 2 no-admissible-step observations on validation; N+1 has 35, 4 and 2. These are observed stop reasons from this study, not flags copied from earlier experiments. More iterations might help some cases, but no budget relaxation was tested and stagnation/support errors can persist.

## What the anchor and added data actually contribute

The cached MATERIAL_CLOSED composition anchors equal the feed-composition prior to floating-point precision: all forty centered liquid/vapor composition coordinates are within about 1.5e-14 of zero. The native feed flash is computed, but this mode does not use it to refine the returned separation profile. The network still learns essentially all composition separation.

| Encoded target group, original N | Residual variance / absolute-target variance |
|---|---:|
| liquidComposition | 1.000 |
| logLiquidTotal | 1.119 |
| logVaporTotal | 0.827 |
| temperature | 0.774 |
| vaporComposition | 1.000 |

Temperature and vapor-traffic offsets reduce variance somewhat; liquid-traffic variance increases, and composition variance is unchanged. This supports simplifying redundant anchor inputs or designing a more informative bounded anchor. It does not prove that removing the anchor would improve convergence; that ablation was not run.

The added 101 profiles average 37.30 stages versus 24.08 for original N. They include 78 side-draw cases, are all TWO_PHASE, and add no wet-qualified profiles. They are certified but selected by successful incumbent-assisted solves, rather than uniformly sampled new coverage. N+1 learns them: the added-subset profile score falls from roughly 1.25–1.43 under N models to 0.50–0.57 under N+1 models. Original-N error changes are mixed across seeds.

Equal 4,160-update budgets give every N example 160 presentations in N, but only 143 or 144 in N+1. Selected checkpoints introduce further exposure differences: N seed 20260911 selects after 159 full passes, while paired N+1 selects after 107 full passes plus 17 minibatches. Exposure and sampling shifts are plausible contributors, not proof that more data is harmful. The measured old/new gradient cosines for the selected N+1 checkpoint are positive (0.428, 0.442, 0.411); a universal gradient-conflict explanation is unsupported. All fixed sampled minibatches would trigger clipping, including the incumbent, and the support head is not the dominant measured parameter-gradient block.

## The controlled support-output experiment

Two N-data fits used seed 20260911, exactly the same minibatch order and 160 presentations of every example. The canonical arm changes only support-output parameter coordinates. Transforming its initial output-layer weights and biases preserves initial physical logits and exact decoded masks. The empirical control reproduces the original selected checkpoint weights **exactly**. Both exports pass native parity, exact masks, cancellation and ten-worker shared-model checks.

| Pipeline | Selected update | Profile score used for selection | Panel ONLY strict, blocks 1 / 2 | Panel FIRST strict, blocks 1 / 2 |
|---|---:|---:|---:|---:|
| empirical | 4134 | 1.020980 | 32 / 32 | 46 / 46 |
| canonical | 3874 | 1.024626 | 31 / 31 | 43 / 43 |

The incumbent also obtains 32 ONLY and 46 FIRST successes in each panel block. The 65 inputs were deliberately selected from old validation outcome groups before the new fits; these are diagnostic counts, not a population performance estimate. All three modes use the unchanged budgets and ten workers. Accounting includes 1,170 measured panel requests and 36 fixed TRAIN warmup requests. No new model was evaluated on the former fresh test.

Canonical support scaling gains 9 control ONLY successes but loses 10; for FIRST it gains 4 and loses 7. Both blocks reproduce these sets. On all 168 reference profiles, presence-head omissions decrease slightly (114 to 101), but omissions after the head keeps a component increase (1,036 to 1,237). Total above-floor omissions increase from 1,150 to 1,338. **Do not adopt this reparameterization as the fix.** The result tests one seed and one fixed recipe, not all possible support-training schemes. AdamW regularization and clipping operate in the changed parameter coordinates, and the unchanged checkpoint rule selects different updates.

## Ranked improvements

1. **Train continuous trace flows to survive the existing support cutoff.** Add a bounded auxiliary log-flow or support-margin loss on certified TRAIN phase-component entries that are above the existing floor. Compute it before hard pruning, and retain the current branch, structural-zero, decoder and audit rules. A concrete candidate penalizes `relu(min(log(q_reference), log(10 * floor)) - log(q_predicted_before_pruning))²`; only reference-present entries participate. The factor ten is motivated by the native support reinsertion hysteresis, and is a proposal to validate, not a tested improvement. Monitor gradients so this term does not overwhelm temperature, traffic or major-component accuracy.

2. **Select and assess seeds using a bounded amount of native correction evidence.** Screen a small, preregistered set of checkpoints on TRAIN/validation with fixed correction budgets, tracking trace support, VLE/energy errors, strict success and reference-only losses. Do not choose solely by average RMSE or the initial material residual. Longer term, training through a short solver trajectory is a candidate: learned warm-start research explicitly optimizes downstream residual/distance after solver iterations. Its results for fixed-point optimization do not guarantee success for this nonconvex MESH solver. [Sambharya et al., JMLR 2024](https://www.jmlr.org/beta/papers/v25/23-1174.html).

3. **Preserve the useful incumbent while adding physics information.** First consider a simpler model that removes redundant composition-anchor inputs, or introduces useful anchor features through initially zero-coupled weights while preserving the incumbent’s initial prediction. A more informative phase/energy anchor would require a separate bounded experiment with its property cost included. Simply enlarging the Transformer or substituting an expensive sequential solve is not supported by this diagnosis.

4. **Test data exposure and targeted acquisition explicitly.** Preserve original-N exposure when adding examples, and include a matched extra-update N control so any gain is not credited to data alone. Acquire independent certified TRAIN cases in demonstrated coverage gaps, retain rare branches, and keep all existing validation/test boundaries. Added steam-fed dry profiles do not establish wet-profile coverage.

The first practical follow-up is the trace-flow/support-margin treatment, paired with native validation screening. Combining multiple new losses, larger architecture, new decoder thresholds and more data at once would leave the cause unresolved. Composite physics losses can themselves suffer gradient imbalance, which is a reason to measure and balance them rather than assume more terms help. [Wang, Teng and Perdikaris](https://arxiv.org/abs/2001.04536).

## Evidence and limits

The original study and production defaults remain unchanged. The analysis distinguishes supported associations, ruled-out direct mechanisms and untested improvements. It does not claim a newly improved production model. The separate archive preserves preliminary analyses, refined both-block attribution, frozen-model profile/support/gradient evidence, the prospective ablation registration, both fits, parity checks and all panel journals. See [protocol](protocol.md) and [archive manifest](cache-manifest.json).

# Neural initializer model survey for V3

**Scope update:** The user subsequently specified refinery-wide equilibrium-stage equipment and off-design steady states. The [revised coverage and architecture requirements](V3_REFINERY_INITIALIZER_REQUIREMENTS.md) supersede the fixed-TJL19 recommendation as the overall model target. The residual MLP remains a prototype benchmark; the broader target favors a property-conditioned stage graph with typed boundary decoders, compared against a conditional 1-D network for linear columns.

Research and architecture selection only, 2026-09-10. No training, new simulation dataset, or initializer implementation was performed for this survey. “LNN” follows the user's earlier clarification: a neural-network initializer generally. Literal liquid neural networks are also evaluated below.

## Recommendation

**Start with a small supervised residual MLP, predicting a correction to a cheap physically constructed seed, with explicit phase/topology masks. Compare it against a conditional 1-D residual CNN before selecting a model for variable tray layouts.** Add physics constraints incrementally and retain the native V3 corrector and acceptance gates.

The residual MLP is the best first experiment for the existing fixed 40-tray TJL19 case. The conditional 1-D network is the stronger structural candidate for covering V3's different tray counts and feed/draw locations. This ranking is an engineering inference from the code and literature, not a measured V3 neural-network result. A graph network is a later candidate if the supported topology becomes branched or contains real recycle connections.

Literal liquid time-constant networks are not the preferred starting point. Their temporal structure would become relevant if the task changes to predicting operating trajectories or learning corrections from a sequence of solver iterates.

## What the model must fit

The current [input contract](../../src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnInput.java) permits 2–64 trays, up to three liquid side draws, two steam feeds, and three PAs. The [topology](../../src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnTopology.java) is a single column spine with condenser and reboiler, rather than an arbitrary flowsheet graph. PA is prescribed stage heat, not a circulating liquid stream.

The target is a steady state: operating conditions and geometry map to temperature and phase-flow profiles. Tray order is spatial, not time. Strong coupling is bidirectional because liquid and vapor travel in opposite directions. Feed, steam, and draw locations create local changes that a model must preserve rather than smooth away.

The 40-tray, 19-hydrocarbon case has 42 physical nodes. A canonical physical representation contains temperature and 38 hydrocarbon component-flow values per node, plus separate free water where applicable. Boundary phases, wet trays, and trace support create structural zeros. The [Newton coordinate map](../../src/main/java/com/wormzjl/createcheme/science/column/v3/V3DryMeshCoordinateMap.java) depends on these choices, so a packed Newton vector is an unsuitable universal training target.

The numerical solver remains responsible for convergence and physical acceptance. A useful initializer need not reproduce a profile perfectly; it must put V3 in a region from which correction is faster and reliable.

## Closest domain evidence

[Zhao et al. (2026)](https://www.sciencedirect.com/science/article/pii/S0098135426001298) investigate initialization of distillation columns using a ResNet with superstructure-based MESH constraints. Their variable-stage treatment and column-section initialization are directly relevant. The paper reports improved profile prediction and demonstrates initialization of a dividing-wall column. The accessible abstract and indexed introduction were inspected; the complete methods and dataset were not accessible in this review. Therefore the study supports testing this family, but does not establish suitability for PR78/TJL19, wet trays, or V3's acceptance tolerances.

A separate [steady-state CFD warm-start study](https://arxiv.org/html/2312.11842v2) uses a learned improvement to a cheaper physical solution, then resumes the numerical solver. It also reports an extrapolated case without a clear runtime advantage and distinguishes offline model-development cost from subsequent solve savings. This supports evaluating a correction to an existing seed and measuring end-to-end cost; its speedups cannot be transferred to V3.

## Architecture comparison

The suitability judgments below are V3-specific inferences. Architecture capability is not a guarantee of generalization to new stage counts, assays, or phase regimes.

| Family | Relevant evidence and properties | Fit for V3 | Decision |
|---|---|---|---|
| Residual MLP / dense ResNet | Residual MLPs are competitive numerical-feature baselines in [Gorishniy et al.](https://arxiv.org/abs/2106.11959). | Global coupling is immediate; simple CPU/Java inference. Flattened input/output needs a fixed shape or explicit padding/masks. | **First candidate for fixed TJL19 geometry.** |
| PCA/POD bottleneck + MLP | [PCA-Net analysis](https://arxiv.org/abs/2303.16317) relates approximation cost to output-basis complexity. | Could compress strongly correlated profiles. Moving sharp transitions and phase changes may require many modes. | Test only after checking reconstruction error and native correction from compressed labels. |
| Conditional 1-D residual CNN / small U-Net | Convolutions are credible sequence alternatives to recurrence in [Bai et al.](https://arxiv.org/abs/1803.01271); [PDEBench](https://arxiv.org/abs/2210.07182) includes U-Net scientific baselines. | Shared stage processing, local source features, and length masks fit the column spine. Needs global context or sufficient receptive field. | **Second candidate; preferred structural hypothesis for multiple tray layouts.** |
| Bidirectional GRU / LSTM | Recurrent models provide sequence processing; the cited [convolution/recurrent comparison](https://arxiv.org/abs/1803.01271) does not establish a universal winner. | Two directions fit countercurrent coupling. Serial inference is an added dependency, and there is no physical time variable to exploit. | Useful challenger if CNN locality fails, not required in the first experiment. |
| Graph neural network / graph operator | [MeshGraphNets](https://arxiv.org/html/2010.03409v2) uses learned messages on mesh edges and adapts to mesh representations. | Stage nodes and distinct liquid/vapor edges are natural. Local message passing needs enough propagation or global links. Current single-spine topology limits the advantage over a CNN. | Promote when branching, side strippers, or physical recycles enter scope. |
| DeepONet | [Lu et al.](https://doi.org/10.1038/s42256-021-00302-5) separate input-function encoding from output-coordinate evaluation. | Can query a stage-position-dependent profile. Discrete tray count, boundaries, and feed positions must be explicit. Variable output queries do not solve variable input encoding automatically. | Reasonable later operator-learning comparison. |
| Fourier neural operator | [Li et al.](https://arxiv.org/abs/2010.08895) parameterize nonlocal operators in Fourier space. | Global communication is attractive. A small nonperiodic column with sharp sources and phase masks offers no obvious need for a spectral representation. Boundary treatment and spectral truncation require testing. | Lower priority; not inherently unsuitable, but no demonstrated advantage here. |
| LTC, literal liquid time-constant network | The [original LTC work](https://ojs.aaai.org/index.php/AAAI/article/view/16936) introduces continuous-time recurrent dynamics. | A steady-state profile does not supply a meaningful time axis. Numerical integration adds work to the initializer. | Do not choose for the initial steady-state predictor. |
| CfC, closed-form continuous-time network | [Hasani et al.](https://arxiv.org/abs/2106.13898) approximate LTC dynamics in closed form, avoiding its numerical integration burden. | More attractive than LTC if temporal/history inputs become useful. Treating trays as sequence positions is possible, but does not establish a liquid-specific advantage. | Preferred liquid-network challenger only for a later history-based task. |
| Transformer / attention | The [tabular benchmark](https://arxiv.org/abs/2106.11959) also finds a competitive Transformer adaptation. | Direct global interactions could help. With at most 66 physical nodes, attention is feasible, but added architecture and data demands need to earn their cost. | Reserve as a matched-budget challenger; an LLM is unnecessary. |
| KAN | [Liu et al.](https://arxiv.org/abs/2404.19756) replace scalar edge weights with learned univariate functions. | Interesting for compact smooth response maps; no reviewed evidence establishes an advantage for phase-changing column initialization. | Exploratory, not the initial architecture. |

**PINN is primarily a training formulation, not a competing backbone.** A ResNet, CNN, or graph model can all receive physical residual penalties. The distillation study motivates this, while [Krishnapriyan et al.](https://arxiv.org/abs/2109.01050) show that soft physics penalties can themselves create difficult optimization problems. For V3, supervised labels plus exact structural constraints should precede full MESH residual training. PR root choices, support changes, and wet-tray decisions make differentiating through the entire Java solver a substantial additional project.

Two useful additions are separate from the backbone choice. First, branch-specific heads or models can avoid averaging incompatible condenser regimes. Their choices remain suggestions checked by V3. Second, a component encoder based on physical properties and interaction information could eventually support changing component sets; [Deep Sets](https://arxiv.org/abs/1703.06114) supplies a basis for permutation-aware designs. A plain pooled composition vector would lose component-specific outputs and pair interactions, so the initial model should retain TJL19's fixed ordered basis.

## Proposed first model

This is a candidate configuration for an experiment, not an implemented or tuned model.

1. **Inputs:** normalized feed composition and flow, feed temperature, pressure/profile, reflux, condenser temperature, reboiler duty, per-stage feeds, steam, draws, and signed heat. Include geometry and phase-mode metadata, plus a cheap deterministic seed when available.
2. **Backbone A:** a small dense residual MLP for the fixed 42-node layout. Begin with two or three residual blocks and widths around 128–256, selected using validation data. Compare a direct head with a PCA-compressed head if compression preserves useful seeds.
3. **Backbone B:** a small noncausal 1-D residual CNN with global operating-condition inputs, per-stage local inputs, node-type flags, feed-relative positions, and masks. Its receptive field must cover the whole column, using dilation, a coarse-to-fine path, or global context. Padding is masked throughout, not just at the output.
4. **Targets:** corrections to temperatures and transformed normalized component flows. Use the physical stage/component layout; reconstruct the correct coordinate map afterward. Enforce specified condenser temperature and absent-phase zeros exactly. Do not enforce temperature monotonicity, because heating, cooling, and feeds can violate it.
5. **Alternative decoder:** predict temperature and total phase-traffic information, then recover component flows with V3's material-balance projection. Compare this with direct component-flow prediction; projection can improve feasibility but also adds cost and may remove useful learned detail.
6. **Training:** first fit accepted profiles, with separate temperature/flow scaling and trace-aware weighting. Then test modest material-balance and other accessible physical penalties. A low initial residual is a screening metric, not the final training-success criterion.
7. **Inference:** validate model revision, basis, geometry, and operating range; predict one bounded candidate; run V3 correction; accept only through the unchanged audit and convergence gates; otherwise fall back within the caller's deadline.

The two uses of “residual” should not be confused: a ResNet has internal skip connections, while the proposed output predicts a change from a baseline seed. Both are optional design choices to benchmark independently.

## The wet-state integration constraint

[V3's initial attempt](../../src/main/java/com/wormzjl/createcheme/science/column/v3/V3ColumnCalculator.java) explicitly begins with a dry free-water tray set. The code documents why deriving wet trays from an unconverged profile can introduce large erroneous latent-heat effects. Consequently, a learned full wet profile cannot simply be inserted into the existing entry point and assumed to retain its meaning.

The lowest-risk first comparison predicts an accepted dry surrogate at the requested geometry and retains V3's existing feature continuation. A later full-state predictor needs an explicit, bounded wet-state initialization path, tested against exact accepted-state replay before testing approximate predictions. The model must not acquire authority to declare wet trays physically valid or publish an unaudited branch.

This integration issue is more consequential than choosing between similar small network architectures. If feature and wet-tray continuation dominate runtime, accelerating only the dry surrogate may provide too little benefit; measure this before expanding the dataset.

## Selection experiment and decision rules

Use the same audited V3 dataset and correction budget for four initial contenders: current initializer, nearest-neighbor retrieval, residual MLP, and conditional 1-D ResNet. Treat PCA compression and material projection as separate ablations. Do not compare different architectures trained on different operating regions.

Split data by operating region and geometry, keeping related sweep/continuation trajectories together. Reserve pressure extremes, steam and PA transitions, unseen feed/draw locations, and selected tray counts. Do not treat all trays from one simulation as independent train/test samples.

Measure accepted-solve rate, total wall time including inference and fallback, median and tail latency, native correction work, and all unchanged acceptance metrics. [Sambharya et al.](https://jmlr.org/papers/v25/23-1174.html) explicitly train warm starts against downstream iterative objectives; their theoretical guarantees concern particular fixed-point operator classes and do not automatically apply to V3's nonlinear Newton/active-set machinery.

Adding trays changes the physical separation problem; it is not merely refining the discretization of the same PDE. Therefore neural-operator resolution-transfer results are not proof of transfer across tray counts. Similarly, predictions for unseen assays require property and interaction coverage, not merely a flexible tensor shape. [Operator cost/accuracy studies](https://arxiv.org/abs/2203.13181) also support treating architecture rankings as problem-dependent.

A reasonable proposed promotion target is a material median speedup, ideally around 2x, with no lost accepted cases on the locked test set and a bounded fallback penalty. This is a target, not a forecast or guarantee. Also report the offline break-even point: total data-generation and training cost divided by the measured average saving per subsequent solve.

## What would change the recommendation

- If a compact residual MLP beats the structured models on accepted runtime, keep it for the fixed-layout domain.
- If multiple tray layouts are essential and CNN performance holds on withheld geometries, select the conditional 1-D network.
- If topology becomes branched or contains actual recycle edges, compare an edge-typed graph model.
- If successive operating conditions or solver histories become the input, compare GRU and CfC; only then consider whether LTC's dynamics justify its numerical cost.
- If small models fail because of regime mixing, first separate phases and improve labels/decoding before increasing model size.

No surveyed paper establishes a best model for V3 specifically. The evidence supports a small physics-guided residual predictor as the first family to test, and an end-to-end V3 correction benchmark as the deciding experiment.

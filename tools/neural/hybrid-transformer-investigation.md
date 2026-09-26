# Hybrid mechanistic / Transformer initialization

Investigation against commit `c56f8bc`, 2026-09-11.

## Feasibility and the existing hybrid

A stronger hybrid initializer is feasible, and the bounded probe below gives
a positive initial convergence result. The current implementation already
uses the Transformer as a warm start for a mechanistic solver: its prediction
passes through `V3NeuralSeed`, the native MESH corrector, support/wet-phase
handling and the independent acceptance audit. The next question is whether
mechanics should also construct part of the initial state.

The retained Transformer predicts stage temperature, phase totals, component
composition/support and free-water fields, plus a condenser branch. Its
factorized decoder enforces nonnegative flows, phase-total normalization,
inactive-feed zeros and the prescribed condenser temperature. It does not
enforce coupled stage material balances, energy balance or phase equilibrium.

Relevant implementation: `V3ColumnTransformerInitializer.predict()` and
`raw()` in `tools/neural`; `V3FactorizedNeuralFeatures.decode()` and
`V3ColumnCalculator.correctNeuralSeed()` in the native V3 package.

## Measured feasibility: frozen network, one material solve

The offline wrapper was tested once on 20 predeclared dry/no-side-draw TRAIN
inputs, selected by stage-count quantiles from 153 eligible inputs. The selected
columns span 2-64 stages and retain their authored pumparound heat duties. All
20 predictions chose TWO_PHASE; this probe supplies no LIQUID_ONLY, VAPOR_ONLY,
steam or wet-tray evidence. No prediction error or timing selected these inputs.

Both treatments used LNN_FIRST on ten owned workers, with 2,000 ms for prediction
plus preprocessing/correction, a 30,000 ms request deadline and the unchanged
16-iteration policy. There were exactly 40 complete measured requests. Diagnostic
evaluations and preparation-only checks were outside the measured requests.

| Treatment | Strict / 20 | Advisory | Failed | Classical fallback used | Mean request, ms | Sample SD, ms |
|---|---:|---:|---:|---:|---:|---:|
| Frozen Transformer | 17 | 0 | 3 | 10 | 2,681.51 | 4,909.86 |
| Transformer plus one material solve | 19 | 0 | 1 | 7 | 2,177.57 | 4,651.06 |

There are **two strict gains and no losses**. The gains are
`gd-s08-w0-p4-d0-r00` and `gd-s15-w0-p4-d0-r00`. Each has four heat-only
pumparounds. The ordinary neural attempt failed, and its classical fallback
encountered a cooling/path-bound rejection. The completed seed instead reached
an audited requested-state solution directly through the neural corrector.
Those earlier rejection codes do not prove that the requested inputs are
physically infeasible. One 58-stage input failed in both treatments.

Preprocessing took 8.21 ms on average across all 20 requests, with a maximum of
23.89 ms. Prediction plus preprocessing averaged 12.24 ms. Reconstruction was
accepted for **8/20** inputs and declined for the other 12, which retained their
raw predictions. All eight accepted reconstructions were on 4-25-stage columns;
none of the selected taller columns received a prepared seed in this probe.
Declines were retained without replacement:

- Six: no positive phase composition for a native PR call.
- Five: the existing tridiagonal routine returned a nonpositive flow.
- One: completed flow conflicted with native phase structure.

These are recorded native failure categories, not a diagnosis of each underlying
numerical cause. The present wrapper is consequently a limited optional step,
not a general replacement initializer.

On the eight prepared inputs, the maximum full-grid component material defect,
normalized by the fixed input-component flow scale, was `1.35e-14`. Temperatures
were bit-identical to the raw prediction. However, subsequent native trace
support projection changed the state, and the native scaled material maximum
was not zero: its mean was 0.00237 on those eight inputs. The mean of per-case
scaled energy maxima increased from 0.0837 to 0.2905, while the corresponding
VLE mean decreased from 32.62 to 28.01. These native maxima use each state's
support and row scales; they are descriptive, not a common optimization
objective. Material completion alone does not establish full MESH feasibility.

**The timing reduction is not a controlled speedup estimate.** The mean paired
elapsed change is -503.95 ms (sample SD 678.11 ms), but the 12 declined cases,
whose seeds did not change, also average -381.94 ms. They retain the same 11/12
strict successes and seven fallback uses. The eight prepared cases average
-686.96 ms, with one individual slowdown of 545.61 ms. Treatment order was
split 11 raw-first / 9 completion-first overall, and 6/6 among declined cases.
Single-run timings remain affected by warmup, contention and budget-dependent
execution. Subtracting the declined group's mean from the prepared group's mean
would not isolate causality, because the groups contain different inputs.

The two gained native certificates and reduced fallback count make a larger
validation experiment worthwhile. TRAIN-only observations do not establish
generalization, and 19/20 here must not be compared as a success rate against
the earlier 252-case test. The [full probe results](hybrid-initializer-feasibility-results.md),
[summary](hybrid-initializer-feasibility-summary.json) and
[supplementary controls](hybrid-initializer-feasibility-supplementary.json)
retain case-level evidence and denominators.

## Candidate designs

| Design | What mechanics does | What the Transformer learns | Main cost or limitation |
|---|---|---|---|
| Current warm start | Corrects the entire predicted state and audits the result | Full stage profiles and discrete branch/support fields | Already implemented; much of the runtime is correction |
| Physics-informed training | Adds native balance residuals to the training objective | The existing profiles, with an additional consistency objective | Soft penalties do not guarantee closure; scaling and native parity matter |
| Mechanistic baseline plus learned corrections | Builds a cheap state before learned corrections and final reconstruction | Temperature and flow/split corrections relative to that baseline | Needs baseline features/targets and retraining; all baseline cost must be counted |
| Material-balance completion | Reconstructs component flows from a partial learned state | Temperatures and flow-ratio/composition information | A restricted material solve is not full MESH feasibility; support and side draws complicate completion |
| Unrolled mechanistic updates | Performs a bounded sequence of physical updates | Initial state or bounded update controls | Thermodynamic roots, discrete support changes and gradients add substantial complexity |
| Coarse-to-fine hybrid | Solves a cheaper column, then constructs the requested grid | Corrections to a coarse/interpolated profile | Coarse solve cost and failure must be included; V3 already has stage continuation |

The first practical experiment should retain the frozen network and add one
material-completion step before the existing corrector. The bounded probe above
implements that design without fitting. It changes the initializer treatment
while retaining the same learned weights, decoder and native correction policy.

```mermaid
flowchart LR
    I[Requested column] --> T[Frozen Transformer]
    T --> M[One material reconstruction]
    M --> N[Native MESH correction]
    N --> A[Strict physical audit]
    I --> C[Classical fallback]
    N --> C
    C --> A
```

## Native integration constraints

`V3ColumnInitializer.solveMaterialBalances()` already solves a tridiagonal
system per active component. Its input is an equilibrium ratio `K = y/x`;
it converts this into a component-flow ratio using the previous stage totals:

\[
\rho_{j,i}=K_{j,i}\frac{V_j}{L_j},\qquad v_{j,i}=\rho_{j,i}l_{j,i}.
\]

For a dry interior tray without side draws, the completed liquid flows satisfy

\[
l_{j-1,i}-(1+\rho_{j,i})l_{j,i}
  +\rho_{j+1,i}l_{j+1,i}=-f_{j,i},
\]

with the native condenser/reflux and reboiler boundary equations. Treating
`K` as `v_i/l_i` would omit the stage-total factor and solve a different system.
The PR equilibrium ratios depend on temperature, pressure and both phase
compositions; a single update freezes those values and does not establish a
self-consistent equilibrium state after the flows change.

Several current helpers have important limits:

- `projectMaterialBalancesAtFixedTemperature()` performs three damped flow
  sweeps **and updates bubble-point temperatures**, despite its name and
  fixed-temperature description. It is not the proposed single fixed-T step.
- The material solve uses estimated side-draw fractions based on the incoming
  totals, capped at 0.95. Native residuals use the actual, uncapped physical
  withdrawal. Thus one solve is not exact completion for a side-draw column.
- `V3HybridPreconditioner` is a chooser between classical bubble-point and
  sum-rates methods; it is not a learned hybrid. It rejects side-draw columns.
  Sum-rates can perform up to twelve sweeps including energy/temperature work.
  The inspected production calculator does not call this strategy selector;
  its recovery path calls the bubble-point preconditioner directly.
- Thermodynamic helper calls are not all internally cancellation-aware. An
  initializer wrapper must check the supplied control around bounded work,
  charge preprocessing to the neural/request budgets and report overruns.
- Native trace support and condenser component phases must be preserved or
  explicitly recomputed through existing rules. Completing dense flows can
  reintroduce phases that the learned decoder omitted; this is not evidence
  that those phases belong in the final equilibrium state.
- Steam presence and actual free-water trays are separate concerns. Carrying
  a wet mask through a dry component solve does not enforce water saturation,
  steam dilution or energy closure. The first probe should be dry and have no
  side draws; extending coverage requires separate work.
- Current V3 pumparounds are prescribed stage-heat duties. They enter energy
  equations, not new component-flow connections. The
  [generation-comparison erratum](generation-comparison-recommendation-erratum-v1.md)
  records this distinction.

## A deeper hybrid architecture

A mechanistic-baseline/residual Transformer is a separate, plausible design:

1. Construct a cheap baseline `z0(input)` from the existing material/phase
   machinery. Do not run the complete classical solver before the network.
2. Supply the baseline profiles, authored inputs and stage positions to the
   Transformer. Train it to predict corrections in the factorized coordinates:
   temperature, log phase totals and composition coordinates, with separate
   branch/support treatment.
3. Reconstruct a bounded physical seed and complete the chosen balance
   equations before handing it to the normal corrector and audit.

This would require new training examples containing baseline features and
same-input, same-branch certified targets, plus export/parity checks. The current
Transformer predicts absolute profiles and cannot be reinterpreted as a
residual network. Adding residuals directly to physical flows can produce
negative values, while log residuals at structural zeros are undefined. Preserve
the existing phase rules and trace semantics explicitly rather than introducing
an arbitrary floor to hide those cases.

A later constraint-completion decoder could learn temperatures and positive
component flow-ratio coordinates and solve for the remaining flows, reducing
redundant predictions. A PR-based version also needs composition information
from the baseline or network; temperatures alone do not determine mixture
K-values. Differentiating through a fixed nonsingular material system is a
smaller problem than differentiating through the full wet MESH solver, but
poor conditioning, discrete phase changes and side-draw feedback still need
separate treatment. This architecture was not trained or measured here.

## Recommended next steps

First, register a full paired native validation of the exact frozen wrapper and
ordinary Transformer, including all 405 validation inputs and an explicit
applicability breakdown. Keep inapplicable steam/side-draw inputs in the totals;
their behavior is raw-seed fallback under the shared budget. Preserve strict
certification and report every gained/lost input. If latency is a decision
criterion, predeclare interleaved repetitions and adequate warmup, with unchanged
seed controls. The current single sample does not supply repeated-run timing
uncertainty. Freeze any accepted policy before using a new independent test set.

Second, investigate the preparation declines through phase-aware material
completion and numerical diagnostics. Identify the failing node/component and
condition of each system, then qualify any altered solve independently. The
current 8/20 preparation coverage and absence of a prepared tall-column case
are the main limitations to address. Do not force positive flow into an absent
phase or modify the current frozen native helper to make this probe look better.

Third, consider baseline/residual training or a differentiable completion
decoder if the larger validation supports the mechanistic step. Keep native
TRAIN-derived targets, preserve branch/root provenance and change one architectural
or loss component at a time. A native material-balance loss is another smaller
training ablation; an energy loss must include authored pumparound duties with
their current heat-only semantics. Actual wet-equilibrium labels are still
needed before claiming wet-tray coverage.

Learned damping/preconditioner selection or unrolled solver guidance should
follow those simpler experiments. They require trajectory data and qualification
of solver-policy changes, whereas the present wrapper changes only a seed.

## What the literature supports

Zhao et al. describe a distillation initializer trained with variable-stage
superstructure MESH losses and a ResNet, followed by mechanistic solution.
This supports physics-informed initialization as a research direction; it
does not establish that a Transformer, our property package or our wet-phase
rules will gain the same benefit. The available publisher abstract and
section summaries were inspected, not the full methods.
[Computers & Chemical Engineering, 2026](https://www.sciencedirect.com/science/article/pii/S0098135426001298).

DC3 distinguishes predicting all variables with soft constraint penalties
from predicting a subset and completing equalities through differentiable
operations. It provides a useful design pattern for a future balance-aware
decoder, but its demonstrations concern optimization and AC power flow, not
this distillation solver. The partial solve must be well posed and numerical
failures still need explicit handling.
[Donti, Rolnick and Kolter, ICLR 2021](https://arxiv.org/html/2104.12225v1).

Neural-operator warm starts from lower-fidelity solutions have also been
demonstrated for steady-state CFD. That is relevant to a possible coarse-to-fine
column initializer, with no transfer of their speedup claims to this task.
[Zhou et al., revised 2025](https://arxiv.org/abs/2312.11842).

The authors' debutanizer hybrid repository demonstrates differentiable PR,
bubble-point and tridiagonal calculations while learning thermodynamic
interaction-parameter corrections. It is an implementation reference, not a
drop-in initializer: changing interaction parameters changes the physical
model being solved. This investigation retains the registered property model.
The repository's synthetic data are described as demonstration data; its
plant-data results were not reproduced here.
[Kim and Kwon, author repository](https://github.com/danny-taehyun-kim/debutanizer-hybrid-model).

## Verification and preservation

The previous matched comparison found 80/252 strict neural-first successes for
the retained Transformer versus 51/252 classical, with mean request times of
5.429 and 5.153 seconds. Raw inference averaged about 3.13 ms. These motivate
studying initial-state consistency and correction cost; they do not measure
a stronger hybrid initializer. See the
[matched comparison](generation-comparison-findings.md).

Six focused analysis/provenance tests passed. The preparation check compared
40 calls across ten simultaneous workers against serial outputs, verified
unchanged source/subsequent predictions, and injected cancellation after the
first property call. Owned workers terminated. Its material diagnostic matched
8,531 native rows, including 8,198 nonzero rows, on native support-projected
states. Earlier failed preflight attempts are retained: one compile signature
fix, an initial synthetic-fixture test omission, and the correction of a parity
check that had attempted to evaluate zero-valued raw unknowns without native
support preparation. All were resolved before registration and measured requests.

The [protocol](hybrid-initializer-feasibility-protocol.md) and
[frozen plan](hybrid-initializer-feasibility-plan.json) bind the original TRAIN
source, selected inputs, model, implementation and preflight evidence. The
report is recomputed from complete paired journals before sealing; the
[cache manifest](hybrid-initializer-feasibility-cache-manifest.json) retains the
archive and required preceding dependencies. The supplementary control analysis
is explicitly post-run and recomputable. No fitting, holdout tuning, runtime
default change or larger native campaign was performed in this investigation.

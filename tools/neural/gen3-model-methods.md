# Generation 3 initializer methods and reproduction

Generation 3 reuses the frozen generalized design and adds final, physically qualified solver profiles recovered during the Generation 2 campaign. It compares the unchanged stage-MLP representation, a network that separates phase totals from phase composition, and nearest-profile transfer. Every method supplies an initial guess to the same native V3 corrector. This document records methods and artifact provenance; operating-test, recovery, and benchmark outcomes belong in the separate outcome report.

**Data lineage and allowed labels.** The retained baseline is `.neural-cache/gen2-1a4a01d/`, outside `build/` so Gradle cleaning does not remove it. Its manifest is also recorded in `tools/neural/gen2-cache-manifest.json`. It preserves the original design, teacher journal, models, selected weights, evaluation and benchmark evidence, and relevant source provenance. Existing cached bytes are verified before reuse, not replaced by Generation 3 outputs.

`prepare_gen3_data.py` builds `build/neural-gen3/data/cases.jsonl`. It preserves all 2,793 admitted authored inputs and their original splits. The original matrix was a 2,809-row, 48-dimensional strength-two OA-LHS design, with 16 structural exclusions recorded separately. Component fractions remain a closed 20-component mixture: every fraction varies, with 19 independent composition degrees of freedom. The methane-containing property-package identifier is retained for compatibility; methane receives no special feature, distance weight, or training role.

| Original fold | Original qualified profiles | Added qualified final profiles | Gen3 qualified profiles | Use |
| --- | ---: | ---: | ---: | --- |
| Train | 483 | 96 | 579 | Fit normalization, network weights, or reference library |
| Validation | 110 | 26 | 136 | Epoch selection and raw-error checks; never fit |
| Test | 113 | 19 | 132 | Held-out references; never fit or select |

The 141 added profiles are final native solver states, not raw Gen2 predictions. Admission checks require the same canonical requested input and component basis, every native acceptance-audit check, and the final Newton certificate. The certificate retains closure `1e-8`, linear backward error at most `1e-12`, maximum log-flow change at most `1e-8`, and temperature-step ratio at most one. Six additional native-accepted rescue states carry supersaturation advisories and provide no fitted labels. Other failures remain journaled without targets.

The canonical augmented dataset SHA-256 is:

```text
a54ffc39a394dbe85a1dd73ab47dc89d0a53fe79e455d2b4992a0d51d44244f4
```

`data/data.json` records source hashes, counts, exclusions, and unchanged-fold evidence. The cached original teacher hash is `484645e51ab1b3d17da7e91c52db647b591bc4fd176f7f0331b6838118f9b961`; the recovery journal hash is `df54b2f307591cec44ca73cc53c80d062bd069d9edd1dca9a7a48706af106423`.

**Shared input representation.** The two neural candidates use the unchanged Gen2 encoder: 74 global features and 99 features per node. Global inputs include all 20 feed fractions, feed flow and temperature, geometry, pressure and pressure drop, condenser temperature, reflux, reboiler duty, and ordered PA, side-draw, and steam tuples. Per-node inputs add position and feed-relative position, terminal/feed indicators, actual node pressure, local and cumulative heat/material sources, nearest equipment positions, and a condenser-branch one-hot condition. A single shared stage network serves every permitted stage count; its parameter count does not grow with the number of trays.

Each neural candidate has a `74 → 64 → 3` global condenser classifier and a `99 → 96 → 96 → output` stage network, with tanh hidden layers and linear exported heads. Training uses NumPy float32 optimization and Adam; the exported Java runtime evaluates immutable double arrays on the CPU. All learned means and scales use training data only. Profile statistics and loss weights give each column equal total weight, so a 64-tray column does not count as 32 independent two-tray examples. Entire columns retain their fold assignments; no tray-level train/validation split is used.

The training set contains 569 `TWO_PHASE` and 10 `LIQUID_ONLY` condenser profiles. `VAPOR_ONLY` is not a trained branch in these artifacts and cannot be selected. Runtime branch gating also forbids vapor-only condensation when organic reflux is positive.

**MLP control.** `train_generalized.py` preserves the Gen2 architecture and target encoding. Its 43 per-node outputs are temperature, 20 liquid and 20 vapor component flows transformed by `log1p(q / (max(Fc, F×1e-12)×1e-5))`, free water, and wet score. This directly predicts individual component traffic; phase totals are their sums. The Gen3 control refits that same representation on the 579 eligible columns. It uses random seed `240910`, a 500-epoch budget, batch size 2,048, learning rate `0.002`, and patience 100. Validation selected profile epoch 225; training stopped at epoch 325. This controls representation and optimizer. It does not isolate training-label additions alone, because the validation reference set also grows from 110 to 136.

**Factorized neural candidate.** `train_gen3_factorized.py` uses 85 outputs per node:

- Temperature and separate `log1p(L/F)` and `log1p(V/F)` phase-total heads.
- Two 20-component centered log-ratio vectors relative to the feed fractions.
- Free water and wet score.
- Two 20-component presence-logit vectors used only to construct seed support.

For a phase composition, the decoder adds `log(feed fraction)` to its predicted component logits and normalizes with softmax. It distributes the independently predicted phase total across those fractions. All components use the same transformation. Zero-feed components stay exactly zero. An optional conservative presence threshold of `0.02` removes low-confidence predicted support; if it removes every component from a positive phase, the largest predicted composition component is retained. The inferred support is only an initial guess. It does not replace the native solver's support-refresh logic or declare an accepted phase state.

The objective gives explicit weights to temperature and phase-total errors, combines bulk-composition KL loss with a small centered-log-ratio error for trace fidelity, and trains presence logits with binary cross-entropy. Temperature, liquid total, vapor total, water, and wet scalar losses have weights `3, 5, 5, 0.5, 2`. Each phase's composition KL has weight `2`; its mean log-ratio error has weight `0.1`; its mean support cross-entropy has weight `0.2`. The analytic gradients are covered by finite-difference tests. This loss targets phase traffic independently of the many dilute-component outputs.

The candidate uses seed `240910`, a 600-epoch budget, batch size 2,048, learning rate `0.0015`, and patience 100. Validation selected profile epoch 200; training stopped at epoch 300. Both neural candidates selected classifier epoch 270 from the same 600-epoch classifier budget. Their differently defined profile loss values are not directly comparable.

The decoder enforces nonnegative flows, the prescribed condenser temperature, inactive components, and the selected condenser's absent terminal phase. It rejects nonfinite or unbounded predictions. It applies the existing native per-phase trace floor, `max(Fc, F×1e-12)×1e-10`, and renormalizes surviving component flows to the predicted phase total.

There is a tiny-phase exception to the phase-total sum rule: if every component flow in a predicted phase falls below its native trace floor, all are zeroed and there is no surviving flow to renormalize. That phase therefore becomes exactly zero, rather than preserving a sub-floor total. The removed total is bounded by the sum of those component floors. This is seed truncation, not a relaxation of material-balance acceptance; native support refresh and final defect audits remain mandatory. Consequently “preserves phase totals” applies to phases retaining at least one component, in addition to the explicit condenser-branch constraints.

**Nearest-profile controls.** `train_nearest_profile.py` exports a nonparametric transfer initializer, not another neural network. Both `k=1` and `k=3` libraries contain exactly the same 579 qualified training profiles. No validation/test seed is stored. Normalization is fitted only on those training inputs. The 74-feature weighted distance gives every component fraction weight one; the first 17 global operating/geometry features receive weight four, and equipment positions receive weight two. These weights are declared in code, not optimized against test outcomes.

The selected cohort policy is `steam-equipment-counts`: source and request must agree on steam being enabled and PA/side-draw counts. A requested positive-feed component must exist in the source. A source fed on its first tray is rejected when the requested column needs an upstream section. Positive reflux excludes vapor-only reference branches. The maximum weighted mean-square distance is `1.4408670235190366`, obtained as 1.5 times the largest leave-one-input-out nearest distance among eligible training references, with a lower limit of 0.5. Validation and test distances do not calibrate that cutoff.

Transfer aligns condenser, feed tray, and sump, with piecewise linear interpolation between them. Upstream mapping is clamped before the source feed tray so its feed discontinuity is not smeared into the rectifying section. Each component flow is scaled by the requested/source component feed ratio; free water is scaled by the steam-rate ratio. For `k=3`, averaging only combines references with the nearest reference's condenser branch and identical mapped wet mask, using inverse-distance weights. An exact match retains its reference instead of averaging. This aligns major boundaries but does not align every PA zone or side draw; all requested equipment remains in the native correction problem.

**Artifact sizes and identities.** The sizes below are model artifacts, not measured process RAM. Neural primitive bytes count weight and bias arrays; transfer primitive bytes include stored profiles and their numerical lookup index. Object headers, strings, preprocessing arrays outside that accounting, scratch allocations, JVM state, and solver memory require separate measurement.

| Candidate | Weight parameters / references | Reported primitive bytes | JSON bytes |
| --- | ---: | ---: | ---: |
| Frozen Gen2 MLP | 28,078 weights/biases | 224,624 | 590,462 |
| Gen3 MLP control | 28,078 weights/biases | 224,624 | 590,637 |
| Gen3 factorized | 32,152 weights/biases | 257,216 | 674,047 |
| Nearest `k=1` | 579 reference columns | 5,389,514 | 10,200,649 |
| Nearest `k=3` | 579 reference columns | 5,389,514 | 10,200,649 |

| Artifact | SHA-256 |
| --- | --- |
| `.neural-cache/gen2-1a4a01d/model.json` | `f4cab9cb22fcb0e528dbd32c76cf9f41dd3404b80838e97a105260b36c28f380` |
| `build/neural-gen3/mlp-v1/general-model.json` | `b495278effc0244f088b5a9dd3060bde195f5fd4593e31518ae22f5e11dbf520` |
| `build/neural-gen3/factorized-v1/factorized-model.json` | `19ed50603c2955841a9dd8e5a539aa076b1d8d59a09304b2bc0c613cc520e512` |
| `build/neural-gen3/nearest-k1/model.json` | `4975ef506cce65efd888e412b81a572cd8f87316c507ff3fdc627b7c04005eec` |
| `build/neural-gen3/nearest-k3/model.json` | `4b832c63c96684d099ce6915abbf2373ad04c106afe407b075beb7f7124cb92d` |

**Validation selection and boundaries.** Epoch selection uses the 136 qualified validation reference profiles. Native candidate selection uses the complete original 405 validation requests, counting strict physical qualification, including failures and coverage rejections in the denominator. `build/neural-gen3/selection.json` froze selection at `2026-09-10T13:51:22.550933+00:00`: maximize strictly qualified validation solves among declared new candidates, then break ties by smaller artifact and lexical ID. This selects the factorized neural candidate and nearest `k=1` transfer independently. Candidate hashes, routing, and correction budgets were frozen before prospective comparison outputs. The separate 252-input fresh operating holdout was frozen before fitting; the old geometry test remains excluded from fitting but is previously reported regression evidence, not a newly blind test.

All fitted profiles in this generation are `DRY_EQUILIBRIUM`, including steam-enabled inputs. There are no qualified wet-tray training examples. Retaining water/wet outputs and the native wet solver does not establish learned wet-tray accuracy; a perfect dry-mask match is not wet-regime validation. The current artifacts also do not establish arbitrary-feed-location, new-component, new-property-package, or general refinery coverage.

The declared design uses 2–64 trays, all 20 mixture fractions within 80–120% of their baseline values under closure, reflux 0–10, zero to four PAs, zero to three liquid side draws, and steam off/on. Other authored variations follow the recorded generalized design. Feed position varies around the baseline normalized feed height rather than covering every possible feed tray. Steam is injected only at the sump; PA heat placement is `UNIFORM` only. PAs retain V3's existing heat-duty representation, not a new explicit circulating material stream.

Pressure is constrained at every node to 100–300 kPa. The stage drop is bounded by `0..min(1000 Pa, (300000 Pa − Ptop)/(N−1))`; a per-feature pressure box alone is insufficient. The runtime enforces this coupled node-pressure envelope, sump-only steam, permitted PA split, component/property/formulation identity, stage limits, and global authored-input bounds. The neural artifacts have no optional nearest-distance guard enabled. Nearest transfer additionally requires a compatible training reference inside its calibrated distance limit. Passing any of these coverage checks does not prove physical feasibility or convergence.

**Native solver and publication policy.** No candidate changes V3 thermodynamic equations, material or energy balances, trace-floor policy, wet-phase physics, Newton linear algebra, correction acceptance, or final audits. Candidate inference and correction are bounded; final physical checks remain authoritative. The frozen parallel comparison uses ten workers, a fresh 30-second parent deadline per method/request, a 10-second candidate allowance, and 16 correction iterations. Serial comparisons use one worker and a 2-second candidate allowance with the same 30-second parent deadline and 16 iterations. Timing and RAM comparisons must use matched requests and account for unsuccessful attempts separately.

`columnV3.initializerModel = "GENERALIZED_GEN3_EXPERIMENTAL"` explicitly selects the factorized artifact bundled as `src/main/resources/data/createcheme/neural/v3-general-gen3-factorized.json`. Its bytes match the selected artifact hash above. `GENERALIZED_EXPERIMENTAL` still selects the original Gen2 resource, and `LOCAL_EXPERTS` remains the default. `LNN_FIRST` permits classical fallback; `LNN_ONLY` does not invoke classical initialization; `CURRENT_ONLY` bypasses learned initialization. Selection is captured at request admission, and immutable models are safely published through separate static holders. Nearest-profile transfer remains an offline comparison candidate.

**Exact training/export reproduction.** Run from the repository root with the recorded Python 3.12.14 / NumPy 2.3.5 runtime and one BLAS thread. The commands below use new output directories to preserve the current artifacts and cache. Training reports contain elapsed times and need not hash identically; verify the model bytes against the identity table. Floating-point libraries or platform changes can alter trained bytes despite the fixed random seed.

```powershell
Set-Location 'D:\Minecraft\Modding\1.21\CreateChemE'
$python = 'C:\Users\wormz\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$env:OPENBLAS_NUM_THREADS = '1'
$env:MKL_NUM_THREADS = '1'
$env:OMP_NUM_THREADS = '1'

& $python tools/neural/prepare_gen3_data.py --verify-cache-only
```

If `build/neural-gen3/data/` has not been prepared, run `& $python tools/neural/prepare_gen3_data.py` once. It verifies the retained cache and refuses to replace an existing dataset. Verify its `cases.jsonl` hash against the dataset identity above before fitting. Prospective holdouts must also be frozen before a new study begins; `prepare_gen3_holdouts.py` records that input-only design in `build/neural-gen3/fresh-design/`. Existing frozen holdouts and selection records must not be regenerated after viewing their results.

The original design metadata can be read from the retained cache; this yields the same model constraints as `build/neural-generalized/design/design.json` used in the original commands.

```powershell
$design = '.neural-cache/gen2-1a4a01d/study/design/design.json'
$reproduction = 'build/neural-gen3/reproduce'
if (Test-Path -LiteralPath $reproduction) { throw 'Choose a fresh reproduction directory.' }
New-Item -ItemType Directory -Path "$reproduction/mlp-v1" -ErrorAction Stop | Out-Null
Copy-Item -LiteralPath 'build/neural-gen3/data/cases.jsonl' -Destination "$reproduction/mlp-v1/cases.jsonl"

& $python tools/neural/train_generalized.py "$reproduction/mlp-v1" --epochs 500 --branch-epochs 600 --hidden-width 96 --batch-size 2048 --learning-rate 0.002 --patience 100 --seed 240910 --model-id tjl20-general-gen3-mlp-v1 --label-policy equilibrium-only --design-bounds --design-file $design --prediction-split validation

& $python tools/neural/train_gen3_factorized.py build/neural-gen3/data --output-directory "$reproduction/factorized-v1" --epochs 600 --branch-epochs 600 --hidden-width 96 --batch-size 2048 --learning-rate 0.0015 --patience 100 --presence-threshold 0.02 --seed 240910 --model-id tjl20-gen3-factorized-v1 --design-bounds --design-file $design --prediction-split validation

& $python tools/neural/train_nearest_profile.py build/neural-gen3/data --output "$reproduction/nearest-k1/model.json" --neighbors 1 --cohort-policy steam-equipment-counts --model-id tjl20-gen3-nearest-k1 --design-file $design

& $python tools/neural/train_nearest_profile.py build/neural-gen3/data --output "$reproduction/nearest-k3/model.json" --neighbors 3 --cohort-policy steam-equipment-counts --model-id tjl20-gen3-nearest-k3 --design-file $design
```

No `--coverage-guard` or explicit nearest `--maximum-distance` was used for the recorded artifacts. Both neural commands emit validation-only raw comparisons. Their frozen-model evaluation options can later evaluate another fold without fitting, but those outputs must not be used to revise the same frozen candidate selection.

The Java/Python checks below exercise the real exported models on two training fixture columns, without native solving or held-out targets. Their expected `atol` and `rtol` are both `1e-8`; branch and wet masks must match exactly. The already-recorded factorized maximum decoded-flow discrepancy was below `2e-12 mol/s`, and the MLP discrepancy was below `5e-12 mol/s`.

```powershell
.\gradlew.bat --offline generalNeuralModelParity "-PgeneralModelDirectory=$reproduction/mlp-v1" "-PgeneralParityOutput=$reproduction/mlp-v1/java-parity.json"
& $python tools/neural/check_general_model_parity.py "$reproduction/mlp-v1" "$reproduction/mlp-v1/java-parity.json" --output "$reproduction/mlp-v1/parity-report.json"

.\gradlew.bat --offline factorizedNeuralModelParity "-PfactorizedModelDirectory=$reproduction/factorized-v1" "-PfactorizedParityOutput=$reproduction/factorized-v1/java-parity.json"
& $python tools/neural/check_gen3_factorized_parity.py "$reproduction/factorized-v1" "$reproduction/factorized-v1/java-parity.json" --output "$reproduction/factorized-v1/parity-report.json"
```

The nearest-profile check uses six TRAIN-origin inputs: four exact references, a small flow/temperature perturbation and a 2→4-stage interpolation. Its independent scalar Python implementation checks actual Java inference, including three-reference averaging for k=3. Both recorded checks pass at absolute tolerance `1e-9` and relative tolerance `1e-10`; the largest decoded discrepancy is below `1.14e-13`. No native solver or held-out profile is used.

```powershell
& $python tools/neural/check_nearest_profile_parity.py prepare --source build/neural-gen3/data/cases.jsonl --model "$reproduction/nearest-k1/model.json" --model "$reproduction/nearest-k3/model.json" --output "$reproduction/nearest-parity/fixture.json"

foreach ($k in 1, 3) {
    .\gradlew.bat nearestProfileModelParity "-PnearestModelFile=$reproduction/nearest-k$k/model.json" "-PnearestParityFixture=$reproduction/nearest-parity/fixture.json" "-PnearestParityOutput=$reproduction/nearest-parity/java-k$k.json" --offline
    & $python tools/neural/check_nearest_profile_parity.py verify "$reproduction/nearest-k$k/model.json" "$reproduction/nearest-parity/fixture.json" "$reproduction/nearest-parity/java-k$k.json" --output "$reproduction/nearest-parity/verified-k$k.json"
}
```

The reproduction sources are `train_generalized.py`, `train_gen3_factorized.py`, and `train_nearest_profile.py`; the definitive metadata are each artifact's training/export report, `data/data.json`, and `selection.json`. Frozen Gen2 bytes and metrics remain separate controls throughout.

# V3 neural initialization

The [evaluation workflow](unified-evaluation.md) uses **405 validation cases and one frozen 252-case test set per study**. Convergence and runtime are measured together on every test case under the same budgets. The previous 64-case timing subset is retained only for historical reproduction.

**Future native evaluations should use 10 worker threads**, as requested on 2026-09-11. Register this in the next study's protocol and record the worker count with its timing results. Existing serial protocols and results, including accuracy revision 1, remain reproducible with their original settings; timings collected with ten workers measure execution under concurrent load and should be reported separately from those serial measurements.

The [matched generation comparison](generation-comparison-findings.md) reruns frozen Gen2 and selected Gen3 on the same 405 validation and 252 test inputs as the retained transformer, with ten workers and the same correction budgets. On the shared test, neural-only qualifies **29 / 32 / 54** cases and neural-first qualifies **60 / 66 / 80**, in Gen2 / Gen3 / transformer order. Every neural-first run preserves the same 51 classical successes; mean neural-first times are **6.20 / 6.10 / 5.43 seconds**. The transformer leads overall, while the older models add seven complementary test successes across separate runs. The report discusses iteration-cap experiments, physical training losses, targeted labels and routing as future work. The [full results](generation-comparison-results.md), [summary](generation-comparison-summary.json) and [verified cache manifest](generation-comparison-cache-manifest.json) retain the evidence. The reused test is not another blind test, and runtime defaults remain unchanged.

The completed [native checkpoint-selection study](checkpoint-selection-findings.md) retains **seed 20260911**. Across all 405 validation inputs with ten workers, neural-first qualified **161 cases**, versus **154** for each alternative; both alternatives also had higher observed mean latency. All preserved the same 110 classical successes. On a new prospective 252-case test, the retained artifact qualified **80 cases** with neural-first versus **51 classical**, gaining 29 with no losses, while averaging **5.429 s versus 5.153 s** per case. Winner and reference share one test execution because their artifact hashes match. The [results](checkpoint-selection-results.md), [summary](checkpoint-selection-summary.json), [protocol amendment](checkpoint-selection-amendment-v2.md) and [verified manifest](checkpoint-selection-cache-manifest.json) preserve this experiment. No training or runtime-default change was made.

The completed [accuracy study](transformer-accuracy-findings.md) tested six training configurations across three CUDA seeds. Added regularization and decoded-flow loss did not beat the baseline's mean selection score. A baseline checkpoint chosen with the new decoded criterion lowered validation temperature MAE from **10.76 ± 6.80 K to 9.54 ± 6.44 K** across 168 certified columns. On a new prospective 252-case test, strict transformer-first convergence rose from **83/252 to 86/252**, with 10 gains and 7 losses; the classical control qualified 58/252. The [full results](transformer-accuracy-results.md), [summary](transformer-accuracy-summary.json), [protocol](transformer-accuracy-protocol.md) and [verified cache manifest](transformer-accuracy-cache-manifest.json) preserve this study. Production defaults are unchanged.

The preceding [unified native findings](unified-evaluation-findings.md) report **98/252 strictly qualified cases with transformer-first fallback**, compared with 66/252 for classical initialization, 89/252 for the MLP, 79/252 for factorized Gen3 and 81/252 for nearest-profile transfer. Transformer-first gained 32 cases with no classical losses and added 316 ± 1,484 ms per request on average. This is a different historical test cohort from the accuracy study above. The [full results](unified-evaluation-results.md), [summary](unified-evaluation-summary.json) and [verified cache manifest](unified-evaluation-cache-manifest.json) preserve convergence, timing and dependencies. These models remain offline experimental candidates.

The [transformer investigation](transformer-investigation.md) expands fitting to 805 qualified columns, freezes a replacement 252-case holdout, characterizes coverage and label ambiguity, and compares a small transformer with a parameter-matched MLP on CUDA across three seeds. The GPU pilot is followed by the native Java evaluation above. The [protocol](transformer-protocol.md), [machine-readable summary](transformer-pilot-summary.json), and [cache manifest](transformer-cache-manifest.json) preserve the pilot. Use a project-local environment with `requirements-transformer.txt` for GPU training.

The [Generation 3 comparison](gen3-model.md) adds 96 qualified training-fold rescues, trains two neural candidates, and compares nearest-profile transfer against the frozen Generation 2 and classical controls. It preserves the Generation 2 study in `.neural-cache/gen2-1a4a01d/`, outside Gradle's build directory. The [methods](gen3-model-methods.md), [prospective protocol](gen3_protocol.md), [model card](gen3-model-card.json), and [case map](gen3-case-map.jsonl.gz) record the separate validation, fresh-test, regression, recovery and serial benchmark populations.

The [Generation 2 generalized experiment](generalized-model.md) varies all twenty component fractions, 2–64 trays, steam, pressure, reflux, side draws and up to four pumparounds. Its [finite design](generalized-design.md), [model card](generalized-model-card.json) and [complete case map](generalized-case-map.jsonl.gz) preserve failures and distinguish physical necessities from solver limitations. Generalized models remain **opt-in**; the existing local experts remain the default.

The methane initializer uses **two multivariable state predictors**, one trained on qualified dry columns and one on certified wet columns. It ranks predictions using the requested column's material, energy and water-saturation residuals, then corrects candidates with the rigorous solver. It does not classify a column from condenser temperature alone or average wet and dry profiles together.

See [the methane model design and reproduction guide](methane-model.md) and [the measured model card](methane-model-card.json). The [methane property qualification](methane-qualification.md) records the property sources and original classical convergence result. The [original TJL19 pilot](legacy-tjl19-pilot.md) remains available for existing 19-component inputs.

## Configuration

In `config/createcheme-common.toml`, section `[columnV3]`:

| Key | Default |
|---|---|
| `initializerMode` | `LNN_FIRST` |
| `initializerModel` | `LOCAL_EXPERTS` |
| `lnnWetStartMode` | `AUTO` |
| `lnnMaxCorrectionIterations` | `16` |
| `lnnBudgetMilliseconds` | `2000` |

- `LNN_FIRST`: try compatible neural candidates, then initialize classically once if necessary.
- `LNN_ONLY`: neural candidates and rigorous correction only; unsupported inputs or unsuccessful correction return a typed failure. No classical initializer is hidden in this mode.
- `CURRENT_ONLY`: unchanged classical initialization, without consulting the model.
- `initializerModel = "GENERALIZED_EXPERIMENTAL"`: select the retained Generation 2 generalized model.
- `initializerModel = "GENERALIZED_GEN3_EXPERIMENTAL"`: select the Generation 3 network with separate phase-total and composition predictions. `LOCAL_EXPERTS` selects the existing dry/wet and legacy predictors. Keep `LNN_FIRST` when experimenting so that classical fallback remains available.
- `AUTO` / `PREDICTED_WET`: retain the predictor's wet mask. `DRY_START` clears its initial water and wet mask; native phase checks and repairs still apply.

The neural time budget is shared by inference, ranking, all neural candidates, wet refinement and final correction. The iteration limit applies to each native correction pass. A wet prediction first receives a bounded pass with water held fixed; every water unknown is released for its final certificate. Parametric intermediate states are never results or training labels.

Qualified equilibrium candidates are preferred over a dry result carrying the existing water-dew-point advisory. If no qualified candidate succeeds, the existing accepted-advisory policy is retained. Training the new methane predictors excludes supersaturated labels; this does not silently change the runtime publication policy.

Whole-request cancellation always wins, and backup uses the original deadline's remaining time. Immutable settings and model references are captured at admission. Weights load once from the classpath; rebuilding and restarting replaces them. Missing or invalid experts leave other compatible experts and fallback available.

## Scope

The local experts cover the TJL20 methane property package and the existing 40-tray geometry. Composition, feed temperature and flow, pressure, reflux, steam, cooling and withdrawals are represented jointly. The methane model card distinguishes the broader dry design from the narrower coupled wet-boundary design. The generalized experiment covers additional authored geometries and operating variables, but has no wet training labels and is not a replacement for the local wet expert or classical initialization.

Unsupported combinations are rejected using package and geometry checks, declared feature bounds, and a guard learned from correlations and distances among training inputs. New calculators retain the 0.5 mol% methane preset. Existing saves retain their composition. The original TJL19 model is preserved alongside the two methane models; the input selects compatible predictors.

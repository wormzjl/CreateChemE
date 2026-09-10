# V3 neural initialization

The new [generalized experiment](generalized-model.md) varies all twenty component fractions, 2–64 trays, steam, pressure, reflux, side draws and up to four pumparounds. Its [finite design](generalized-design.md), [model card](generalized-model-card.json) and [complete case map](generalized-case-map.jsonl.gz) preserve failures and distinguish physical necessities from solver limitations. The trained general model is **opt-in**: validation showed weak flow-profile and tall-column coverage, so the existing local experts remain the default.

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
- `initializerModel = "GENERALIZED_EXPERIMENTAL"`: select only the freshly trained generalized model. `LOCAL_EXPERTS` selects the existing dry/wet and legacy predictors. Keep `LNN_FIRST` when experimenting so that classical fallback remains available.
- `AUTO` / `PREDICTED_WET`: retain the predictor's wet mask. `DRY_START` clears its initial water and wet mask; native phase checks and repairs still apply.

The neural time budget is shared by inference, ranking, all neural candidates, wet refinement and final correction. The iteration limit applies to each native correction pass. A wet prediction first receives a bounded pass with water held fixed; every water unknown is released for its final certificate. Parametric intermediate states are never results or training labels.

Qualified equilibrium candidates are preferred over a dry result carrying the existing water-dew-point advisory. If no qualified candidate succeeds, the existing accepted-advisory policy is retained. Training the new methane predictors excludes supersaturated labels; this does not silently change the runtime publication policy.

Whole-request cancellation always wins, and backup uses the original deadline's remaining time. Immutable settings and model references are captured at admission. Weights load once from the classpath; rebuilding and restarting replaces them. Missing or invalid experts leave other compatible experts and fallback available.

## Scope

The local experts cover the TJL20 methane property package and the existing 40-tray geometry. Composition, feed temperature and flow, pressure, reflux, steam, cooling and withdrawals are represented jointly. The methane model card distinguishes the broader dry design from the narrower coupled wet-boundary design. The generalized experiment covers additional authored geometries and operating variables, but has no wet training labels and is not a replacement for the local wet expert or classical initialization.

Unsupported combinations are rejected using package and geometry checks, declared feature bounds, and a guard learned from correlations and distances among training inputs. New calculators retain the 0.5 mol% methane preset. Existing saves retain their composition. The original TJL19 model is preserved alongside the two methane models; the input selects compatible predictors.

# V3 neural initializer MVP

This branch contains an operational neural-first path, strict forced modes, physical-state transfer,
switchable wet-set initialization, a small bundled trained model, and offline reproduction tools.

The long-term target remains refinery-wide equilibrium-stage initialization. **The bundled dense model is
only a temperature-slice baseline for the current full TJL19 case.** The `V3NeuralInitializer` interface
permits a later property-conditioned graph model without changing the selection/correction policy.

The fresh calculator now uses a [methane-enriched TJL20 feed](methane-qualification.md), qualified with
`CURRENT_ONLY`. The bundled model still covers only the original TJL19 pilot; it declines the methane
package and `LNN_FIRST` uses the current initializer. Existing saved inputs retain their composition.

## Configuration

The following keys are now recognized in `config/createcheme-common.toml`:

```toml
[columnV3]
initializerMode = "LNN_FIRST"
lnnWetStartMode = "AUTO"
lnnMaxCorrectionIterations = 16
lnnBudgetMilliseconds = 2000
```

- `LNN_FIRST`: try a compatible learned seed, then run the current initializer once if needed.
- `LNN_ONLY`: no current initializer, including hidden cold-start recovery. Missing/unsupported/rejected
  predictions and unsuccessful correction produce a typed failure.
- `CURRENT_ONLY`: unchanged classical path; the request does not consult the model.

`AUTO` and `PREDICTED_WET` retain the model's supplied admissible wet mask. This MVP's seed interface
requires an explicit mask, so `AUTO` has no missing-mask case. `DRY_START` clears the initial mask and
free-water amounts while retaining authored steam and all subsequent physical wet-tray correction.
An inadmissible wet mask declines the neural attempt; it cannot silently change the requested wet policy.

Every neural inference and correction pass shares the time allowance. The iteration allowance is per
native correction pass; floor/wet-set repair can require additional passes. Whole-request cancellation
always wins, and backup uses only the original deadline's remaining time. Settings and an immutable
model reference are captured at request admission. Weights load once from the bundled classpath artifact;
replacing that artifact requires rebuilding/restarting, not editing an arbitrary model path at runtime.

The existing numerical API overloads remain classical for reproducible teacher generation and existing
callers. The mod's admission path uses the configured strategy. The Holland benchmark retains its dedicated
reference solver for current/backup operation; forced LNN cannot claim support for its different property model.

## What is actually trained

- Registered TJL19 property package and ordered 19-hydrocarbon basis.
- 40 trays, feed at tray 37, the original three side draws, steam feed and three PA heat zones.
- Feed temperature from **634.15 to 642.15 K**. Other encoded physical inputs are fixed.
- 40 independent V3 solves; only 37 returned accepted labels. Failed cases remain recorded as failures.
- Preassigned split: 23 accepted training samples, 5 accepted validation samples, and 10 held-out test inputs.
  Two adjacent points in each temperature block are withheld, rather than splitting individual trays.
- One 32-unit tanh hidden layer, an eight-dimensional PCA target basis, and a folded linear output layer.
  The PCA basis, normalization and neural weights are fitted only to training samples; epoch selection uses validation.
- Canonical outputs: node temperatures, transformed normalized liquid/vapor component flows,
  transformed free water and wet indicators. Fixed condenser temperature and absent phases are restored on decode.

The model manifest checks package, property revision, formulation revision, component ordering, stage count,
and per-feature bounds. These bounds reject unsupported conditions; being inside them is not an accuracy
certificate. The rigorous correction and all current convergence/audit gates still decide acceptance.

The pilot carries steam but its accepted tray states have **no free-water trays**. Predicted-wet transfer
is tested structurally against the native wet-set/DOF machinery, but this model is not a learned wet-tray
model. General wet-state datasets, other geometries/chemical systems, broader off-design regimes and a graph
backbone remain subsequent work. The current reference solver's documented assumptions and warnings still apply.
In particular, V3 accepts the pilot's supersaturated dry top tray with a water-dew-point advisory. The
student inherits that teacher policy; it does not establish a fully equilibrated aqueous phase or repair
the underlying wet-physics limitation. The model card retains those advisories explicitly.

## Measured pilot result

The held-out comparison accepted all 10 neural-seeded cases. The current initializer accepted 9/10 in the
same comparison. Both successful paths used the existing physical audit and final Newton certificate.
The detailed measurements, hashes and split information are in [model-card.json](model-card.json).

This is a single small interpolation experiment, with alternating order and JVM/JIT effects, not a general
speedup or reliability claim. One teacher-failed test input was accepted after neural correction; it was
never used as a solution label in training.

## Reproduction

The input snapshot is [tjl19-pilot-input.json](tjl19-pilot-input.json). A regenerated study writes to the
ignored build directory and does **not** overwrite the deployed model automatically.

```powershell
.\gradlew.bat generateNeuralMvpData -PneuralMvpOutput=build/neural-mvp/regenerated
python tools/neural/train_mvp.py build/neural-mvp/regenerated
.\gradlew.bat evaluateNeuralMvp -PneuralMvpOutput=build/neural-mvp/regenerated
python tools/neural/summarize_mvp.py build/neural-mvp/regenerated
```

Python needs NumPy. No deep-learning runtime is required in the mod. The offline Java source set is not
included in the mod JAR. Each generator solve has a cooperative 15-second deadline; incomplete profiles
are excluded. `cases.jsonl` retains the actual input, physical snapshot, audit, certificate, features,
targets and outcome. Evaluate all withheld inputs, including those without an accepted teacher label.

Before generating a new dataset, retain the source revision and previous journal. Solver timings and
near-boundary outcomes can vary across JVM/runtime versions; the persisted journal hash identifies the
actual training run, not an expectation that timed records reproduce byte-for-byte.

## Validation and extension points

- `V3InitializationOptions`: immutable forced-mode/wet-mode/budget snapshot.
- `V3NeuralSeed`: defensive requested-problem physical state, including the public component axis.
- `V3ColumnCalculator.calculate(..., options, model)`: no classical pre-initialization, guarded physical
  correction and one clean backup. It will not publish an intermediate or parametric free-water problem.
- `calculateWithAcceptedProfile`: explicit offline observer after all requested-result publication gates.
- `V3DenseNeuralInitializer`: bounded JSON loading, immutable CPU inference, manifest coverage checks.
- `V3NeuralModels`: safely published optional classpath model; unavailable/corrupt models retain fallback.

Regression coverage includes forced-mode routing, immutable admitted settings, cancellation, local-budget
fallback, exact accepted-state transfer, wrong-request rejection, malformed artifacts, coverage rejection,
wet/dry ledger consistency, and the unchanged current solver's steam/PA, closure and trace paths.

This MVP accepts complete requested-problem profiles only. A dry-surrogate model needs an explicit
subproblem/continuation adapter before it can be added. Broader refinery equipment, thermodynamic families
and specification modes must be supported and validated by the data teacher before their coverage is advertised.

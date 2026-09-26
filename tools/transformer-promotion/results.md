# The Transformer initializer in production, measured through the production entry point

**The shipped path is the qualified path.** The bundled artifact is the registered F0 weight document byte
for byte, the shipped decoder rule and the shipped correction rule are the two the neural-budget study
qualified on exactly those weights, and the production learned entry reproduces that study's decoded seeds
on all 405 frozen validation inputs — 0 of 405 mismatched — and its classical and neural-first strict
identity sets exactly, in two blocks. Neural-only lands two cases below the qualification campaign, and
both of them are columns whose learned correction needed 90% or more of the two-second allowance on a
machine that is running 2% to 22% slower than the one the qualification ran on.

## 1. What was promoted

| Piece | Was | Is |
| --- | --- | --- |
| Weights | `build/neural-trace-followup/v1/models/F-20260911-s4160/model.json`, sealed Codex worktree | `src/main/resources/data/createcheme/neural/v3-column-transformer-f0.json` |
| Initializer | `tools/neural-budget/java/V3BudgetInitializer.java` (generated from the trace-followup class) | `src/main/java/.../v3/V3AnchorTransformerInitializer.java` |
| Native anchor | `tools/hybrid-learning/java/V3HybridBaseline.java` | `src/main/java/.../v3/V3NativeAnchor.java` (the offline copy stays; six sealed studies compile against it) |
| Decoder rule | study manifest `zero-phase-floor`, factor 10 | `V3NeuralModels` states `DecodeOptions.zeroPhaseFloor(10)` |
| Correction rule | study manifest `progress` | `V3InitializationOptions.Correction.PROGRESS`, carried by `DEFAULT` and by `CreateChemE.columnV3InitializationOptions()` |
| Model selection | config `initializerModel`, three families | removed; one model ships |

SHA-256 of the bundled bytes: `7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962`
(2,658,860 bytes, 89,496 parameters, 185 input features). Model card:
`src/main/resources/data/createcheme/neural/v3-column-transformer-f0.md`.

`V3BudgetInitializer` was deleted rather than left in place: `tools/neural-budget/java/V3BudgetModels.java`
now loads the production class, and `generate_budget_native.py` no longer generates an offline twin — it
asserts that the promoted class's numerical body is still byte-identical to the sealed predecessor's under
the one rename the promotion applied. A campaign in this line can therefore only ever measure the shipped
arithmetic.

## 2. Protocol

Registered before the first measurement and unchanged afterwards: ten owned worker threads, a 30 s request
deadline, a 2,000 ms neural allowance, a base cap of 16 Newton iterations, the default liquid-supply screen
ratio, cutoff 0 and closure 0, and the same 405 frozen inputs
(`95d773b06126b1637492e07cc5e7ab62b819655f5ac221c98c961bff436a9a0a`) and warmup the qualification used.
Two blocks, each an independent repetition of the whole population; the qualification's block order was a
rotation over its four pipelines, and with one pipeline here a block is simply a repeat.

Strict is the study's definition, ported to Java in the probe and applied to the objects instead of their
JSON: every acceptance-audit check passed, a real final Newton step at the frozen 1e-8 closure with
backward error ≤ 1e-12, log-flow change ≤ 1e-8 and temperature step ratio ≤ 1, and a `DRY_EQUILIBRIUM` or
`WET_EQUILIBRIUM` water qualification of the accepted profile.

The probe calls
`V3ColumnCalculator.calculateWithAcceptedProfile(input, control, options, model, observer)`. That is the
public learned entry — the same private entry as
`calculate(input, control, 0, 0, options, model)`, with the same cutoff, closure, default screen ratio and
`V3NewtonTrace.NONE` — with the accepted profile published, because the water half of the strict
definition is assessed on that profile. `options` is what the mod's admission thread builds, and the probe
refuses to run if the shipped defaults are not the qualified ones. `model` is `V3NeuralModels.bundled()`;
nothing is read from a study manifest, because there is nothing left in one.

## 3. Decoder parity

| Check | Result |
| --- | --- |
| Decoded seeds, all 405 inputs, production model vs committed `F0-progress-phase-floor` digests | **0 of 405 mismatched** |
| Cases the model supports | 405, same as the qualified pipeline |

The bundled weights, read by the promoted reader with the shipped decoder rule, produce the qualified
pipeline's seeds bit for bit. This is also a JUnit test
(`V3BundledTransformerPromotionTest.decodedSeedsReproduceTheQualifiedPipelineDigests`), which ports the
study's digest routine — Gson's tree, then CPython's canonical `json.dumps` — into the suite so the claim
is re-checked on every build rather than only when a campaign runs.

## 4. Strict counts and identities

| Route | Block 1 | Block 2 | Qualification | Identity set identical |
| --- | ---: | ---: | ---: | --- |
| Classical (`CURRENT_ONLY`) | **110** | **110** | 110 / 110 | yes, both blocks |
| Learned only (`LNN_ONLY`) | 162 | 162 | 163 / 164 | no: 1 and 2 cases short |
| Learned first (`LNN_FIRST`) | **180** | **180** | 180 / 180 | yes, both blocks |

`LNN_FIRST` is what ships. Its 180 columns are the same 180 columns in both blocks and the same 180 the
qualification published, and the classical union is preserved exactly.

### The two neural-only differences

| Case | Qualification block 1 | Qualification block 2 | Production block 1 | Production block 2 |
| --- | --- | --- | --- | --- |
| `gd-s38-w0-p0-d2-r00` | strict, 1,879.7 ms | strict, 1,800.4 ms | failed at the wall, 2,000.3 ms | failed at the wall, 2,000.3 ms |
| `gd-s59-w0-p1-d0-r00` | failed at the wall, 2,000.3 ms | strict, 2,007.6 ms | failed at the wall, 2,000.5 ms | failed at the wall, 2,006.8 ms |

No case is strict here and not there: `productionOnlyIds` is empty for every route in both blocks. The
difference is entirely a loss, and it is a loss at the clock.

**Why.** The classical route is untouched code running an untouched trajectory — its 110 strict identities
and their iteration counts are identical in both campaigns — so its cost difference measures the machine
and nothing else. Paired over those 110 columns it is **+202 ms mean (+12.7%) in block 1 and +325 ms
(+22.1%) in block 2**; over all 405 requests it is +2.2% and +9.1%. A uniformly slower machine under a
fixed 2,000 ms wall loses exactly the learned corrections that were already spending most of it.

Only three of the 163/164 qualified neural-only successes cost more than 1,500 ms. All three got slower by
the same 10–21%, and the two whose reference cost was 1,800–2,008 ms crossed the wall:

| Case | Qualification ms | Production ms | Outcome |
| --- | ---: | ---: | --- |
| `gd-s52-w0-p4-d2-r00` | 1,581.2 | 1,745.3 | still strict |
| `gd-s59-w1-p1-d0-r01` | 1,642.3 | 1,848.8 | still strict |
| `gd-s38-w0-p0-d2-r00` | 1,879.7 | 2,000.3 | lost |
| `gd-s59-w0-p1-d0-r00` | 2,007.6 (block 2) | 2,006.8 | lost |

`gd-s59-w0-p1-d0-r00` is the case the qualification report itself named as its own non-reproducible one —
"a neural-only loss for the progress arm in block 1 and not in block 2 … wall jitter rather than a changed
failure mode". Its published count was 163 in one block and 164 in the other for this reason. This
campaign finds it on the losing side of the same coin twice.

Both lost columns are recovered by classical fallback, which is why `LNN_FIRST` is unaffected. The
promotion does not change what the game can solve; on a machine 13–22% faster it would read 163/164.

## 5. What the promotion buys

Paired, on the columns both routes solve strictly, ten workers, both blocks:

| Comparison | Cases | Classical mean | Learned mean | Difference |
| --- | ---: | ---: | ---: | ---: |
| `LNN_FIRST` vs classical, block 1 | 110 | 2,542.5 ms | 1,632.9 ms | **−909.6 ms mean, −591.2 ms median** |
| `LNN_FIRST` vs classical, block 2 | 110 | 2,567.7 ms | 1,633.2 ms | **−934.5 ms mean, −563.0 ms median** |
| `LNN_ONLY` vs classical, block 1 | 92 | 2,545.3 ms | 1,193.9 ms | −1,351.4 ms mean, −739.3 ms median |
| `LNN_ONLY` vs classical, block 2 | 92 | 2,580.4 ms | 1,203.4 ms | −1,377.0 ms mean, −746.4 ms median |

And it solves 180 of the 405 columns against classical's 110 — **70 columns the classical initializer does
not reach at all**, at a median learned-first cost of 228 ms.

Paired against the qualification campaign on the cases both call strict, learned-first costs +91.0 ms mean
(block 1) and +130.1 ms (block 2) — the same machine drift the classical route shows, on a smaller base.

## 6. Production changes beyond the promotion

One model now ships, so model selection went away with it.

- `V3NeuralModels` is a single `bundled()`. The `Family` enum and the `initializerModel` config key are
  gone. NeoForge's `ModConfigSpec` removes a key that the spec does not define when it corrects a loaded
  file, so an existing `createcheme-common.toml` carrying `initializerModel = "LOCAL_EXPERTS"` loads, has
  that line stripped and is backed up; nothing throws. `initializerMode` (default `LNN_FIRST`) and
  `lnnWetStartMode` are unchanged.
- Retired out of `src/main` into `tools/neural/retired/` (allowlisted, still in the `neuralMvp` source set
  and still in the sealed studies' core source lists through a relocation map in `budget_register.py` and
  `floor_register.py`): `V3DenseNeuralInitializer`, `V3GeneralNeuralInitializer`,
  `V3FactorizedNeuralInitializer`, `V3NearestProfileInitializer`, `V3PhaseAwareNeuralInitializer`, and the
  artifacts `v3-mvp.json`, `v3-tjl20-dry.json`, `v3-tjl20-wet.json`, `v3-general-stage.json`,
  `v3-general-gen3-factorized.json`. Nothing was deleted.
- `V3ColumnCalculator`'s learned path used to name `V3PhaseAwareNeuralInitializer` by type to ask for more
  than one candidate. That became a default method on `V3NeuralInitializer`, `candidates(input, control)`,
  whose default is the single prediction. The retired bundle overrides it. The bundled Transformer takes
  the default, so the learned path runs the identical arithmetic it ran before.
- No GUI, wire or NBT schema enumerates model families, so **no schema bump is needed**. The initialization
  event carries only the model id, which is now always `trace-followup/F-20260911-s4160`.

### Production tests deleted

No offline test source set exists, so six test classes that only exercised the retired families were
deleted rather than moved. Each is listed so the loss is explicit:

| Deleted | What it covered |
| --- | --- |
| `V3DenseNeuralInitializerTest` | the dense TJL19/TJL20 expert reader and its manifest validation |
| `V3GeneralNeuralInitializerTest` | the Gen2 generalized stage model reader |
| `V3FactorizedNeuralInitializerTest` | the Gen3 factorized reader and its bundled artifact |
| `V3NearestProfileInitializerTest` | the nearest-profile transfer initializer |
| `V3PhaseAwareNeuralInitializerTest` | the expert bundle's ranking and candidate ordering |
| `V3MultivariableNeuralTest` | multivariable coverage of the dry TJL20 expert artifact |

`V3MethaneColumnTest` lost its trailing assertion that the 19-component dense model declines the methane
package, and its learned-path test now asserts the outcome and the bundled model id rather than that the
learned route in particular won — which route wins a given preset is a coverage question this campaign
answers, not an invariant. `V3BundledTransformerPromotionTest` replaces the deleted coverage on the single
shipped path: the artifact's SHA-256, the model's identity, parameter count and property revision, the
single-parse holder, the qualified correction rule, and the 405-case decoded-seed parity.

Suite after the promotion and the codec fix: **545 tests, 0 failures, 0 errors** (580 before, minus 39 in
the six deleted classes, plus 4 new).

## 7. Caveats

1. Neural-only reads 162/162 against a published 163/164. The evidence above attributes both missing cases
   to the wall and the machine, and no third block was run to chase the number: re-running until a count
   appears is not a measurement. If the two-case difference matters, the cheap and honest fix is to measure
   the wall itself, not to repeat the campaign.
2. Re-registering the decoder-floor study against the current tree now needs its `DECLARED_SOURCE_DELTA`
   widened, because the promotion changed more `src/main` files than that study declared. Its committed
   evidence stands on the tree it was sealed against, which its cache manifest binds.
3. Python report and verification scripts under `tools/neural/` that reference the retired artifacts by
   their old `src/main/resources` paths (`finalize_gen3_report.py`, `freeze_generalized_model.py`,
   `prepare_gen3_data.py`, `summarize_methane.py`, `verify_gen3_campaign.py`, the two
   `native_checkpoint_selection_*.py` globs) were not repointed. They read archived studies and none is on
   the path of anything this branch runs.
4. The full journals (1.3 MB per block, 9.0 MB for the decode dump) stay under `build/`; per-case outcome,
   status, cost, iteration count and water grade for all three routes in both blocks, and the per-case seed
   digests, are committed under `evidence/` and bound by `evidence/cache-manifest.json`.

## 8. Reproducing

```
python tools/transformer-promotion/promotion_native.py rebuild-core
python tools/transformer-promotion/promotion_native.py decode
python tools/transformer-promotion/promotion_native.py block1
python tools/transformer-promotion/promotion_native.py block2
python tools/transformer-promotion/promotion_compare.py 1 2
python tools/transformer-promotion/promotion_seal.py
```

Each block is about 370 s on ten workers and must not overlap another campaign or a Gradle run. The core
rebuild refuses to run if the bundled artifact is not the registered F0 bytes.

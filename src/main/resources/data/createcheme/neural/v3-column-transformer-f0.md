# Bundled column initializer: anchor-augmented Transformer F0

The one learned initializer CreateChemE ships. `V3NeuralModels.bundled()` parses these bytes once,
publishes the result immutably, and `V3AnchorTransformerInitializer` runs them.

## Identity

| Field | Value |
| --- | --- |
| Artifact | `data/createcheme/neural/v3-column-transformer-f0.json` |
| SHA-256 | `7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962` |
| Bytes | 2,658,860 |
| `modelId` | `trace-followup/F-20260911-s4160` |
| Registered name | `F-20260911-s4160` (arm F, seed 20260911, checkpoint step 4,160) |
| `featureRevision` | `v3-anchor-augmented-1` |
| `modelType` / `anchorLayout` / `outputConvention` | `anchor-augmented` / `full` / `absolute` |
| Parameters | 89,496 across 34 tensors |
| Input width | 185 = 96 node features + 85 anchor coordinates + 3 branch one-hot + 1 anchor availability |
| Hidden width / blocks / heads | 64 / 2 pre-norm self-attention blocks / 4 heads of 16 |
| Property package | `createcheme:tjl20_methane`, dataset revision `tjl20-methane-nist-r1` |
| Components | 20, fixed order, as listed in the document |
| Branches seen | `LIQUID_ONLY`, `TWO_PHASE` (no `VAPOR_ONLY`) |
| Formulation revisions | 8 dry and wet mesh revisions, listed in the document |
| Design envelope | 100–300 kPa node pressure, `UNIFORM` pumparound splits, steam at the sump only |

## Provenance

Trained offline on branch `codex/v4-transformer-investigation` by the trace-followup study, arm F at
step 4,160 (`build/neural-trace-followup/v1/models/F-20260911-s4160/model.json` in the sealed Codex
worktree, bound by the SHA-256 above in `tools/neural-budget/budget_common.py` as `BASE_WEIGHTS_SHA`
and in `tools/capacity-followup/capacity_common.py`).

Bound hashes carried inside the document: checkpoint
`0a638a1f8f41912dccd6e6d3ebb8f5242132cd0bde17c5a899b9ac3159a9bdf4`, training data
`12573c376342389cf40731d68f92e2aacbb26306c84b959a522365b4a18bcc4b`, training plan
`03196a6fe431b3ba46844394ca24f537fbc2253e554baf97467285210f5b7d82`, initialization source
`7aa7eaa5ecbe4cea51a31bbe4724569e40900b128e98b1d69a6068e5e7d43e5e`, anchor revision
`native-material-closed-anchor-v1` (`V3NativeAnchor.REVISION`).

The weight bytes are byte-identical to the offline artifact. No retraining, quantization or
re-serialization happened during promotion.

## Qualified defaults

The bundled model ships with the two interventions the neural-budget study qualified on exactly these
weights, and only with them:

- **Decoder**: `DecodeOptions.zeroPhaseFloor(10)`. A phase whose total decodes zero, whose branch admits
  it and whose presence head kept at least one component is seeded at ten support floors over those
  components. Registered as `PHASE_FLOOR_DECODER`.
- **Correction budget**: `V3InitializationOptions.Correction.PROGRESS` — base cap 16 inside the
  unchanged 2,000 ms allowance, extension blocks of 8 up to 48 while the maximum scaled residual has
  contracted below 0.5 over the last 8 iterations, early stop when it has not fallen below 0.9 over 8
  iterations while above 1e-6. Registered as `PROGRESS_CORRECTION`.

## Measured coverage

Over the frozen 405-input validation population, ten workers, 2 s neural allowance, 30 s request
deadline, strict = unchanged acceptance audit + final Newton certificate + water qualification:

| Route | Strict cases |
| --- | ---: |
| Classical only | 110 |
| Learned only (`LNN_ONLY`) | 163 / 164 |
| Learned first (`LNN_FIRST`) | 180 |

Source: `tools/neural-budget/results.md`, pipeline `F0-progress-phase-floor`; reproduced through the
production entry point in `tools/transformer-promotion/results.md`.

## Limits

Coverage is the design envelope above. Outside it `supported(input)` returns false, `predict` is empty,
and `LNN_FIRST` falls back to the classical initializer. A property-package revision other than
`tjl20-methane-nist-r1` makes the seed inadmissible; it is rejected, never rescaled.

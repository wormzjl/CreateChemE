# Benchmark population cleanup — handoff

Branch `claude/v4-benchmark-cleanup` (base `claude/v4-transformer-promotion` @ 7c7d88e). Report and tables:
`tools/benchmark-population/v1/population.md`. Method and harness hook: `tools/benchmark-population/README.md`.

## What changed in production code

One seam, no behaviour change. `V3ColumnCalculator.requestOnlyAdmission(input, ratio)` is a new
package-private method holding the three request-only gates the private `calculate` already applied in that
order (`totalDraw >= totalFeed`, `V3LiquidSupplyScreen`, `staticCoolingAdmission`), returning a
`RequestAdmission(gate, failure)` record. `calculate` calls it; the offline probe calls the same method, so
an offline population screen cannot drift from production. `gradlew compileJava` passes.

## Numbers

| Population | n | `REQUEST_ONLY_TYPED` | `STATE_GATE_NEVER_SOLVED` | cleaned | strict ceiling | open set |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| validation | 405 | 16 | 59 | 330 | 214 | 116 |
| g4fresh | 252 | 11 | 49 | 192 | 110 | 82 |
| g6fresh | 252 | 10 | 36 | 206 | 87 | 119 |
| historical-test | 395 | 20 | 48 | 327 | 186 | 141 |
| train | 1,993 | 96 | 348 | 1,549 | 813 | 736 |

Promoted pipeline on the cleaned validation set (numerators unchanged in every route and block):
classical 110 = 33.3%, LNN_ONLY 162 = 49.1%, LNN_FIRST 180 = 54.5%; ceiling 214 = 64.8%.

## Things a reviewer should look at

1. **The 66 / 61 question.** The published D1 is reproduced exactly (66). The rule as written excludes on
   "never strict *and* never advisory", which releases 5 of those 66 that reached advisory on 8 archived
   arms each, reproducibly in both blocks. They are kept, per the instruction not to exclude advisory-only
   cases. Under the strict-only reading the cleaned validation denominator would be 325 instead of 330.
2. **The classical control for TRAIN and the historical test** is the generalized design matrix's own
   acquisition (`build/neural-gen3/data/cases.jsonl`, `labelProvenance.kind == original_current_initializer`),
   at formulation `v3-dry-mesh-r23`. It typed 87 of the validation fold where the later capacity-followup
   control typed 108, so the two folds are screened against a slightly older solver than validation is.
   No newer classical control over TRAIN exists in any archive.
3. **Arm coverage is very uneven**: 198 arm-mode-block combinations on validation against 12 on the
   historical test. A never-solved verdict on the thinly covered populations is weaker evidence than the
   same verdict on validation.
4. **The static cooling admission fired on none of the 3,297 inputs**, and `totalDraw >= totalFeed` on none
   either (the design generator already excludes that cell). Every request-only exclusion in all five
   populations is the liquid-supply screen. Worth knowing before anyone tunes the heat gate.
5. **`.gitattributes` was missing the promotion study's own byte-hashed files.** On a `core.autocrlf=true`
   checkout `validation-inputs.jsonl`, `warmup.json` and the bundled `v3-column-transformer-f0.json` all
   hash differently from their registered values and `verify_frozen()` fails. Fixed by pinning `*.jsonl`
   and the neural resources. This was live on the promotion branch, not introduced here.

## Not determined

- Whether the 44 gap-analysis "arms" map one-to-one onto the 198 arm-mode-block labels used here; the two
  agree on the only two published aggregates that can be compared (D1 = 66, ceiling = 214 of 405).
- The absolute paired means in `tools/transformer-promotion/results.md` §5 could not be reproduced from the
  committed evidence; they pair a classical mean over the learned route's own strict set with a difference
  over the common set. Every *difference* reproduces exactly.
- The certified label sets are N = 805 and N+1 = 906 in `certified-dataset-manifest.json`, not the 804 /
  905 the task quoted. Zero labels in either sit on an excluded TRAIN input; `classify.py` asserts it.

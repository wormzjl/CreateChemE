# Decoder presence-floor results on the frozen F0 weights

**The presence floor changes no strict outcome.** All three lift variants produce exactly the same
strict success sets as the default decoder, on all 405 validation inputs, in all three strategies, in
both blocks: zero gains and zero losses. No variant passes the study's gates, because none gains a
single strict FIRST case. The classical union is preserved and there is no mean FIRST latency
regression, but those two gates are satisfied vacuously by a pipeline that does nothing.

The campaign contains 9,720 measured corrected-column requests plus 48 TRAIN warmups. The two blocks
repeat the same 405 inputs; they are not 810 independent cases. All four pipelines load the identical
archived F0 weight document `F-20260911-s4160`, SHA-256
`7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962`, and differ only in the decode
rule stated in the pipeline manifest. No weight byte, tolerance, support rule, solver budget or
production default changed. See [protocol.md](protocol.md).

## Harness parity

`F0-baseline` reproduces the predecessor capacity campaign's F0 results exactly.

| Check | Result |
| --- | --- |
| Decoded seeds, all 405 inputs, versus both archived F0 journals | 0 of 405 mismatched, both blocks |
| Strict classical identity set versus archive | identical, both blocks, 110 cases |
| Strict ONLY identity set versus archive | identical, both blocks, 134 cases |
| Strict FIRST identity set versus archive | identical, both blocks, 168 cases |

The decode parity was measured before the campaign, with no solver involved, and is the gate that
justified spending the campaign time. The benchmark additionally required that the seed each measured
request received equalled the seed the preflight had recorded for that variant.

The native core was rebuilt from 113 sources with only Gson on the compiler classpath, producing 278
classes. Against the sealed trace-followup registration archive, 102 sources matched byte for byte,
five differ only in checkout line endings, and exactly two differ in content: the decoder
`V3FactorizedNeuralFeatures.java` and the offline Transformer initializer that threads the option.
Java 21.0.11, sixteen available processors, ten owned worker threads per run.

## What the decoder actually changed

Measured on the decoded seeds, before any solver ran:

| Pipeline | Cases whose seed changes | Entries lifted from zero | Above-floor omissions on the 168 references |
| --- | ---: | ---: | ---: |
| F0-baseline | - | - | 819 |
| F0-lift-p002-k10 | 46 of 405 | 82 | 819 |
| F0-lift-p050-k10 | 8 of 405 | 13 | 819 |
| F0-lift-p050-k1 | 8 of 405 | 13 | 819 |

**The omission count does not move at all.** That is the central finding, and it contradicts the
premise the recommendation was written on. Of F0's 819 above-floor omissions across the 168 certified
reference profiles, 765 are whole phases the phase-total head decoded as zero and 54 are components
the presence head itself dropped; the split is 765 / 54 / 0 for
`zeroAllowedPhaseOmissions` / `positivePhaseComponentOmissions` / `predictedBranchOmissions`, and it
is identical under every variant. A lift that fires only when the presence head kept a component
cannot reach a phase whose total is zero, and it cannot reach a component the head did not keep. The
"presence head keeps it, the continuous head undershoots, the decoder zeroes it" case is measurably
**zero** of F0's reference omissions: had any of the 54 positive-phase omissions been a kept
component, the permissive `p002` variant would have lifted it and the count would have fallen.

The 82 entries the permissive variant does lift are real instances of the mechanism, but they sit
where the certified reference does not place an above-floor flow. Of them, 24 land inside the 168
reference columns as additional above-floor entries (6,557 to 6,581); the rest are in the 237
non-reference inputs. The diagnosis figures that motivated R3 (about 9.7 kept-component omissions per
column) were measured on the *hybrid* candidates inside their neural-only loss groups, not on F0.
They do not transfer to this model.

## Complete validation counts and costs

FIRST includes classical fallback; ONLY is a separately executed neural-only request. All counts
require the unchanged strict native audit and the final Newton certificate. Costs include failed and
advisory requests.

| Pipeline | Block | Classical | ONLY | FIRST | FIRST advisory | FIRST failed | FIRST mean ms | FIRST p95 ms | FIRST CPU mean ms | FIRST alloc mean MiB | Fallback observed | ONLY mean ms | Run s |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| F0-baseline | 1 | 110 | 134 | 168 | 9 | 228 | 4017.40 | 16564.65 | 3878.09 | 2603.09 | 257 | 844.58 | 395.0 |
| F0-lift-p002-k10 | 1 | 110 | 134 | 168 | 9 | 228 | 3919.83 | 15798.55 | 3780.13 | 2614.89 | 257 | 824.52 | 384.5 |
| F0-lift-p050-k10 | 1 | 110 | 134 | 168 | 9 | 228 | 3951.76 | 15820.95 | 3797.84 | 2613.63 | 257 | 834.80 | 388.3 |
| F0-lift-p050-k1 | 1 | 110 | 134 | 168 | 9 | 228 | 3979.92 | 16625.88 | 3839.78 | 2631.21 | 257 | 833.60 | 390.9 |
| F0-baseline | 2 | 110 | 134 | 168 | 9 | 228 | 4030.39 | 16888.50 | 3905.94 | 2592.92 | 257 | 845.18 | 394.4 |
| F0-lift-p002-k10 | 2 | 110 | 134 | 168 | 9 | 228 | 3985.47 | 16663.70 | 3863.77 | 2604.54 | 257 | 840.41 | 391.7 |
| F0-lift-p050-k10 | 2 | 110 | 134 | 168 | 9 | 228 | 3998.12 | 16418.03 | 3877.01 | 2606.10 | 257 | 842.03 | 392.5 |
| F0-lift-p050-k1 | 2 | 110 | 134 | 168 | 9 | 228 | 3931.59 | 16369.72 | 3804.82 | 2609.94 | 257 | 829.65 | 386.1 |

The 110 classical strict successes are the same 110 cases in every one of the eight runs, which is
expected: the classical strategy does not consult the model.

## Paired gains and losses against F0-baseline

| Candidate | Block | ONLY gains / losses | FIRST gains / losses | Sets reproduced in both blocks |
| --- | --- | ---: | ---: | --- |
| F0-lift-p002-k10 | 1 | 0 / 0 | 0 / 0 | yes |
| F0-lift-p002-k10 | 2 | 0 / 0 | 0 / 0 | yes |
| F0-lift-p050-k10 | 1 | 0 / 0 | 0 / 0 | yes |
| F0-lift-p050-k10 | 2 | 0 / 0 | 0 / 0 | yes |
| F0-lift-p050-k1 | 1 | 0 / 0 | 0 / 0 | yes |
| F0-lift-p050-k1 | 2 | 0 / 0 | 0 / 0 | yes |

There are no case identities to list: every strict set is identical to the baseline's. The per-regime
tables by `heatLoopCount` (0 to 4) and by `stageCount` (3, 10, 17, 24, 31, 38, 45, 52, 59) are
likewise identical for every pipeline in both blocks and both neural strategies, so they add nothing
beyond the baseline profile: FIRST falls from 47/81 at zero heat loops to 24/83 at four, and from
34/46 at three stages to 9/45 at fifty-nine.

## Costs on stable common successes

Cases strict under both pipelines in both blocks. ONLY: 134 cases; FIRST: 168 cases, in every
comparison, because the success sets coincide.

| Comparison | Strategy | Cases | Reference mean / median ms | Candidate mean / median ms | Paired mean / median ms |
| --- | --- | ---: | --- | --- | --- |
| F0-lift-p002-k10 | ONLY | 134 | 217.06 / 121.89 | 212.07 / 120.33 | -4.99 / -3.32 |
| F0-lift-p002-k10 | FIRST | 168 | 829.70 / 183.17 | 809.39 / 173.60 | -20.32 / -2.78 |
| F0-lift-p050-k10 | ONLY | 134 | 217.06 / 121.89 | 215.15 / 124.15 | -1.91 / -1.37 |
| F0-lift-p050-k10 | FIRST | 168 | 829.70 / 183.17 | 813.98 / 172.56 | -15.72 / -2.16 |
| F0-lift-p050-k1 | ONLY | 134 | 217.06 / 121.89 | 211.93 / 121.55 | -5.13 / -2.45 |
| F0-lift-p050-k1 | FIRST | 168 | 829.70 / 183.17 | 814.24 / 178.79 | -15.46 / -2.49 |

Pooled all-case FIRST means are 4023.89 ms for the baseline against 3952.65, 3974.94 and 3955.75 ms
for `p002-k10`, `p050-k10` and `p050-k1`. **This is not a speed result.** The same F0 pipeline in the
archived campaign measured 4075.86 and 3961.46 ms in its two blocks, a 114 ms spread on identical
inputs and identical decoded seeds, which is wider than every difference in this table. With
identical success sets and identical seeds on 359 of 405 cases, there is no mechanism by which the
lift could make the shared cases faster; the differences are concurrent-load noise.

## Stop-phrase evidence

Observed stop phrases, block 1 / block 2, over all 405 cases per run.

| Pipeline | ONLY iteration_limit | ONLY neural_time_limit | ONLY no_admissible_step | ONLY unknown | FIRST iteration_limit | FIRST neural_time_limit | FIRST whole_request_deadline |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F0-baseline | 138 / 137 | 75 / 75 | 17 / 17 | 41 / 42 | 111 / 111 | 69 / 68 | 8 / 8 |
| F0-lift-p002-k10 | 143 / 142 | 68 / 71 | 17 / 16 | 43 / 42 | 111 / 111 | 62 / 68 | 8 / 8 |
| F0-lift-p050-k10 | 143 / 143 | 69 / 69 | 17 / 18 | 42 / 41 | 111 / 111 | 65 / 67 | 8 / 8 |
| F0-lift-p050-k1 | 142 / 145 | 71 / 68 | 16 / 17 | 42 / 41 | 111 / 111 | 64 / 67 | 8 / 8 |

FIRST `no_admissible_step` is 51 in every run and FIRST `unknown` ranges 78 to 82. The only column
that moves is the boundary between `neural_time_limit` and `iteration_limit` in ONLY, which is the
two-second neural budget wall: the baseline's own blocks already disagree (138 against 137), and a
case that finishes its sixteenth correction a few milliseconds before the wall is recorded as
iteration-capped instead of time-capped. The sum of the two stays between 211 and 213 in all eight
runs, so nothing is being converted into a success.

## The two documented failure groups

| Group | Baseline, block 1 / 2 | Recovered by p002-k10 | by p050-k10 | by p050-k1 |
| --- | --- | --- | --- | --- |
| Classical strict, ONLY not strict | 34 / 34 | 0 / 0 | 0 / 0 | 0 / 0 |
| Classical strict, FIRST not strict | 0 / 0 | - | - | - |
| Iteration-capped ONLY failures | 138 / 137 | 0 / 0 strict | 0 / 0 strict | 0 / 0 strict |

Of the iteration-capped ONLY failures, 2 or 3 per block stop reporting the iteration cap under a
variant, but none becomes strict and the identities differ between blocks
(`gd-s38-w0-p3-d0-r00`, `gd-s38-w1-p0-d1-r00`, `gd-s59-w0-p0-d2-r00` in block 1 for `p002-k10`
against `gd-s24-w0-p2-d0-r00`, `gd-s59-w1-p4-d0-r00` in block 2), so this is the same budget-wall
jitter, not a reproducible change of failure mode. No classical-only loss is recovered by any
variant in either mode. Every classical strict success is already a FIRST strict success under every
pipeline, through the unchanged fallback.

## Verdict

- `F0-lift-p002-k10`: **fails** the gates. Strict FIRST 168/168, equal to the baseline, so no gain in
  either block. Classical union preserved. No pooled mean FIRST latency regression.
- `F0-lift-p050-k10`: **fails**, same figures.
- `F0-lift-p050-k1`: **fails**, same figures.
- ONLY did not improve for any variant: 134/134 in both blocks, with zero gains and zero losses.

Recommendation R3 is not supported for F0 as specified. The decode-level measurement explains why,
and it is the more durable result: the lift can only act on components the presence head kept, and on
this model that set and F0's reference omissions are disjoint. A treatment aimed at F0's omissions
would have to move the **phase-total** head, which produces 93 percent of them, or the presence head
itself, which produces the remaining 7 percent. Neither is a decoder change.

## Evidence and limits

Historical validation, not a fresh holdout; the 168 certified references are a subset of the 405
evaluation inputs. Above-floor omissions are decode diagnostics against those references and do not
prove that a reference phase is necessary at every admissible root. Repeated blocks reuse identical
inputs, so their spread describes case and load variability, not a confidence interval over
independent campaigns. Advisory successes are excluded from every strict count and no failed input
leaves the denominator.

Per-case outcome, status, cost and stop evidence for all eight runs, the reference profile evidence
and the per-case decoded-seed digests are committed gzipped under `evidence/` (0.54 MiB). The
complete 25 MiB journals per run and the full decoded seeds stay under
`build/neural-decoder-floor/v1`; [cache-manifest.json](cache-manifest.json) binds them by hash. The
machine-readable verdict is [summary.json](summary.json).

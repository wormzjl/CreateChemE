# Decoder presence-floor experiment on the frozen F0 weights

This implements recommendation R3 of the hybrid diagnosis as a paired decoder experiment. No model is
trained, no weight byte changes, and no production default changes. The solver acceptance criteria,
support rules, budgets and strict audit are untouched.

## The mechanism under test

`V3FactorizedNeuralFeatures.decodePhase` renormalizes the softmax over components to the predicted
phase total and then prunes any component whose decoded flow lands below
`max(feed[c], totalFeed * 1e-12) * TRACE_FLOOR_FRACTION` to exactly zero, including components the
presence head kept. The native support then has to reinsert such a point through a support refresh.

The opt-in rule seeds such a component at `liftFactor` support floors instead of zero.
`V3TruncationSupport.FLOOR_REINSERTION_FACTOR` is 10, the hysteresis above which the native support
treats a removed phase as carrying material again, which is the default the diagnosis proposed and
the default used here. `liftPresenceProbability` gates the rule on the presence head's own
probability: 0.02 is "any component the head kept" (the production presence threshold), 0.5 is
"confident presences only". The component a positive phase total rescues when every support score is
uncertain is never lifted, because the head did not keep it.

**The phase total is not renormalized after lifting.** The lift is applied after the existing
renormalization, so every component that stays above its floor keeps its default decoded value bit
for bit, and the phase total exceeds the predicted total by at most one lift per component, of order
1e-9 of the feed. Renormalizing would perturb every major component of the phase to pay for a trace;
the material rows are Newton unknowns and absorb the added mass instead.
`V3ColumnCalculator.prepareAttempt` -> `liftFloorSupport` -> `capOversizedPoint` already lowers any
retained point more than three times above its material row, and that code is not touched here.

## Pipelines

All four pipelines load the identical archived F0 weight document
`F-20260911-s4160`, SHA-256 `7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962`,
with full native anchors, absolute outputs and no completion wrapper. The decoder variant lives in
the pipeline manifest, never in the model bytes.

| Pipeline | Decoder rule | liftFactor | liftPresenceProbability |
| --- | --- | ---: | ---: |
| F0-baseline | prune (production) | - | - |
| F0-lift-p002-k10 | presence-floor-lift | 10 | 0.02 |
| F0-lift-p050-k10 | presence-floor-lift | 10 | 0.5 |
| F0-lift-p050-k1 | presence-floor-lift | 1 | 0.5 |

## Runtime

The native core is rebuilt from this worktree's sources into an isolated directory with only Gson on
the compiler classpath, so no stale project bytecode can satisfy a dependency. The registration
records every source hash and proves, against the sealed trace-followup registration archive, that
exactly two sources differ from the predecessor core: the decoder and the offline Transformer
initializer that threads the option. Files whose bytes differ only by checkout line endings are
listed separately.

All runs use the frozen 405-input validation population, the 168 certified references, ten owned
workers, sixteen correction iterations, a two-second neural allowance, a thirty-second request
deadline, a 4 GiB heap and the fixed TRAIN warmup. Every pipeline is run over all three strategies in
two blocks whose pipeline order is reversed.

## Parity gate, run before the campaign

`V3FloorDecodeCheck` dumps the complete decoded seed of every pipeline for all 405 inputs with no
solver involved. `F0-baseline` must reproduce the predecessor capacity campaign's archived F0 seeds
exactly, in both archived blocks, before any campaign time is spent. The benchmark additionally
requires that the seed each measured request actually received equals the seed this preflight
recorded for that variant.

## Decision

A variant passes the study's own gates only with a strict FIRST gain in **both** blocks, preservation
of the contemporaneous classical strict union, and no pooled mean FIRST latency regression against
`F0-baseline`. ONLY changes, paired case identities, stop phrases, stable common-success costs,
per-regime tables and the decode-level omission counts are reported whatever the verdict. Passing
authorizes further qualification only; nothing is promoted.

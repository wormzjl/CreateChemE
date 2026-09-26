# Transformer native validation protocol

**Historical protocol.** The active test method is now
[unified evaluation](unified-evaluation.md), as requested by the user. It measures
convergence and runtime together on all 252 inputs. The separate 10-second test
and 64-case timing stages below are superseded diagnostics; their results must
not be pooled with the unified test. The 405-case validation selection remains frozen.

The user authorized proceeding from the completed GPU pilot to native convergence
and runtime evaluation. Both architectures export seed 20260912, chosen separately
by the lowest offline objective on the original validation fold. The two frozen
Gen3 controls are factorized and nearest-k1. No runtime default is changed.

Before solving: require Java/PyTorch-double feature, raw output and full decoded
seed parity on 14 fitted columns spanning small/large columns and liquid-only
branch; exact wet/branch/zero-flow masks; bounded artifact size and finite weights; cancellation
propagation; and identical results for 32 predictions sharing one model across
eight threads. The Java implementation is restricted to offline tools.

Run all four candidates on the same original 405 validation inputs, including
unlabelled failures, with 10 workers, 16 correction iterations, 10-second neural
budget and 30-second request deadline. The model performs real timed Java inference
inside the native request. Decoder constraints, native residuals, trace support,
water qualification and final Newton certificate remain unchanged.

Select between transformer and matched MLP by strict-qualified validation count,
then smaller artifact, then lexical model id. Freeze selection and all model
hashes before evaluating any replacement holdout. Gen3 methods are fixed controls.

Then evaluate the four frozen models and a classical CURRENT_ONLY control on the new 252 cases. Run the 64-case
input-only benchmark serially for each model under the existing equal-policy
CURRENT_ONLY, LNN_ONLY and LNN_FIRST harness, with 2-second neural budgets and
30-second whole-request deadlines. Warm-ups and deterministic per-case strategy
rotation reuse the existing harness. Report strict and advisory outcomes
separately, paired successes, recovery/fallback differences and means with sample
SD. Concurrent-run per-case time is not a latency benchmark. Serial time includes
fresh inference, correction and any fallback for that strategy.

Do not select or retune using fresh outcomes. Native results determine whether
the offline regression advantage survives rigorous correction. Preserve failures
as outcomes and all qualified profiles for future explicitly governed dataset
updates. Promotion to a user-facing default requires separate evidence and scope.

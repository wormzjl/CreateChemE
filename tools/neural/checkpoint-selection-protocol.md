# Native checkpoint selection, revision 1

This study completes experiment 1 from the ChatGPT review. It compares the
three archived accuracy-v1 baseline checkpoints (20260910/11/12), without
training, changing labels, changing the solver or promoting runtime defaults.
The reference is accuracy-v1's selected seed 20260911, whose exported artifact
is reused byte for byte. The older native-v1 transformer is historical.

Preserve all 405 original validation inputs and their order. The 168 certified
profiles supply diagnostic prediction metrics only. Before validation, freeze
a new input-only 252-case test: four cases per tray count 2–64, two steam-on
and two steam-off, with the existing equipment-balancing sampler. Never choose
by predicted branch or solver outcome. Preserve naturally rare LIQUID_ONLY
cases. Exclude canonical hashes of historical input populations accounted for
from verified Gen2, Gen3, transformer, unified-evaluation and accuracy cache
manifests, plus any additional current build inputs. Missing required archive
entries fail closed. Include the entire accuracy-v1 pool and regenerate the
earlier Gen4 pool, checking its counts and selected inputs against archived
evidence. Record each population's provenance, counts and canonical-hash digest.
Freeze the exclusion inventory, input hashes, generator/selection seeds and
source hashes. Historical test outcomes cannot be used for selection.

Use the separate concurrent-column-evaluation-v1 Java/Python path. Its policy
is exactly ten platform workers, at most ten outstanding case tasks, a bounded
queue of ten, 2,000 ms neural budget, 30,000 ms per strategy request and 16
iterations per correction pass. Each worker runs CURRENT_ONLY, LNN_ONLY and
LNN_FIRST sequentially for its case, rotating their order by the case ID's Java
hash. Predictions inside timed requests are fresh. Queue wait and untimed raw
diagnostics are outside each request deadline; queue wait is recorded separately.
Keep the original native measurement, strict certification, water qualification,
cancellation and fallback definitions. State and workspaces belong to one solve;
the three strategies do not reuse accepted solver states.

Run models one at a time, in seed order 20260910, 20260911, 20260912, with the
same input order, Java 21, 4 GiB maximum heap and two fixed-input warmup rounds
of all three strategies. Each model receives one full validation campaign.
Do not repeat campaigns to obtain favorable measurements. Wall time includes
contention from ten workers; it is not comparable to archived serial timing.
Report sample SD across cases, not as repeated-run timing uncertainty.

For each seed s, let Q_s be its strictly qualified LNN_FIRST validation IDs,
C_s its CURRENT_ONLY IDs, and t_s its unrounded mean LNN_FIRST milliseconds
over all 405 inputs, including failures. Let r=20260911 and C_union be the
union of all three current ten-worker classical control sets. A non-reference
candidate is eligible exactly when:

1. |Q_s| > |Q_r|;
2. C_union is a subset of Q_s;
3. t_s <= t_r.

Rank eligible candidates by descending |Q_s|, ascending t_s, then ascending
numeric seed. If none is eligible, retain r. Retention does not claim r covers
C_union. Report every eligibility decision, classical-set disagreement and
candidate/reference gain or loss. The gate protects classical successes but
does not prohibit loss of an individual reference-only neural success.

Freeze the winner, calculations, validation journal hashes and model hashes
before any test inference or solve. Evaluate the reference then the winner on
every new test input under the same ten-worker policy. If their artifact hashes
match, run once and identify both roles without duplicating evidence. Test
outcomes cannot change the winner or trigger runner-up testing.

Before campaigns, require scheduling/failure/interruption checks, native
serial/ten-worker numerical consistency on ten TRAIN fixtures, and native
export parity for the other checkpoints. Reuse the reference's verified parity.
Bind numerical-check results to the exact ten TRAIN inputs, then freeze the
certification and export hashes in an execution lock before the first campaign.
Validate exact input coverage, all strategies, finite elapsed measurements,
frozen hashes/policy and completed owned-worker shutdown. Test selection-rule
boundaries, ties, fallback, deduplication and premature-test rejection.

Before sealing, revalidate all journals, artifacts and certification, recompute
the summary and report, and require equality with the frozen published results.
Archive all inputs, exported models, parity evidence, raw validation/test
journals, decisions and source dependencies outside Gradle clean. Preserve
the earlier archives and frozen serial source files unchanged. An unchanged
winner or adverse fresh-test result is a valid outcome.

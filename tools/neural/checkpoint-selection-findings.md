# Native checkpoint selection

This study evaluates the three archived accuracy-study baseline checkpoints
under complete native validation. It performs no training. The reference is
seed 20260911, copied from the accuracy study byte for byte.

## Validation decision

**Retain seed 20260911.** Neither alternative passed the predeclared improvement
gate: each qualified seven fewer neural-first cases and had a higher observed
all-case mean latency. This experiment supports keeping the existing checkpoint;
it did not produce a replacement.

| Seed | Neural-first strict / 405 | Mean elapsed, ms | Sample SD, ms | Gains / losses against reference | Preserves all classical successes | Eligible alternative |
|---|---:|---:|---:|---:|---|---|
| 20260910 | 154 | 4,883.18 | 6,410.33 | 15 / 22 | Yes | No |
| 20260911, reference | 161 | 4,071.37 | 5,796.39 | 0 / 0 | Yes | Reference retained |
| 20260912 | 154 | 4,518.25 | 6,066.53 | 14 / 21 | Yes | No |

All three classical controls qualified the same **110/405** inputs; there were
no classical-set disagreements. All three neural-first campaigns preserved
these 110 successes. The reference added 51 strict qualifications over its
classical control, with no losses, while its average elapsed time increased
from 3,872.85 ms to 4,071.37 ms across all 405 cases.

Classical mean times themselves varied across campaigns: 4,542.73, 3,872.85 and
4,146.73 ms in seed order. The reference's lowest observed latency is therefore
not evidence of a statistically established or purely checkpoint-caused speed
advantage. All measurements come from one predeclared campaign per checkpoint
under concurrent load.

The winner was frozen before the prospective test. Reference and winner have
the same model hash, so they share a single full test execution rather than
being presented as independent measurements.

## Method and evidence

All 405 original validation inputs are evaluated under classical initialization,
neural-only initialization and neural-first initialization with classical
fallback. Each campaign uses ten platform workers, at most ten outstanding
cases, a 2-second neural budget, a 30-second request deadline and 16 iterations
per correction pass. Campaigns run once per checkpoint in seed order
20260910, 20260911, 20260912. Queue wait is measured separately.

An alternative must qualify more validation cases with neural-first than the
reference, preserve the union of classical successes from all three campaigns,
and have no increase in unrounded mean neural-first latency across all cases.
Eligible models rank by qualifications, latency and seed. If none passes all
three gates, retain the reference. Individual reference-only neural successes
are not protected by the classical-preservation gate; paired gains and losses
are reported explicitly.

The independent prospective test contains 252 input-only cases: four per tray
count from 2 through 64, two with steam and two without. Its complete input
packet and historical exclusion inventory were frozen before validation.
Historical candidate pools are accounted for from retained manifests, including
unselected inputs. The winner is frozen before test execution. Matching
reference/winner artifact hashes use one shared test execution.

## Prospective test result

The retained checkpoint's neural-first strategy qualified **80/252** cases,
compared with **51/252** for classical initialization: **29 gains and no losses**.
It increased average elapsed time by 276.28 ms across all cases; the paired
difference's sample SD was 3,046.23 ms. This is improved convergence coverage
with an average time cost. The test does not compare two independently executed
checkpoints, because the reference and winner are the same artifact.

| Strategy | Strict / 252 | Advisory only | Failed | Mean elapsed, ms | Sample SD, ms |
|---|---:|---:|---:|---:|---:|
| Classical | 51 | 4 | 197 | 5,152.79 | 6,528.60 |
| Neural only | 54 | 4 | 194 | 1,026.87 | 764.58 |
| Neural first with fallback | 80 | 6 | 166 | 5,429.08 | 6,681.01 |

| Strategy | Mean thread CPU, ms | Sample SD, ms |
|---|---:|---:|
| Classical | 4,946.92 | 6,267.38 |
| Neural only | 985.80 | 734.48 |
| Neural first with fallback | 5,208.83 | 6,434.21 |

All 252 cases contribute to timing statistics, including failed and advisory
outcomes. Neural-only timing uses its shorter neural budget and accompanies
much lower coverage than neural-first; it is not a substitute for comparing
equally successful complete solves. Counts on this new cohort must not be
compared directly with the earlier accuracy study's different 252-case cohort.

The [full generated results](checkpoint-selection-results.md) retain all three
strategies for every validation campaign. The [summary](checkpoint-selection-summary.json)
contains exact decisions, paired IDs, profile diagnostics, allocations, queue
delays and native iteration statistics. The [cache manifest](checkpoint-selection-cache-manifest.json)
binds the preserved evidence.

## Verification

The 27 focused Python checks passed, including selection boundaries, historical
pool exclusion, fixture eligibility, complete population checks, mutation
rejection and report consistency. The Java scheduler check completed 25,000
tasks and verified failure, interruption, bounded shutdown and worker ownership.
The numerical check produced bit-identical serial and ten-worker accepted
profiles on ten original classical TRAIN fixtures.

All three exports passed the registered parity bounds on 14 fixtures, with
branch, wet and zero masks matching, 32 identical parallel predictions,
cancellation and malformed-shape rejection. Reference parity was reused from
the verified earlier study. Each of the three full validation campaigns and
the one complete prospective test passed coverage, hash, policy and completed
worker-shutdown checks. No new model was trained or promoted.

The [protocol](checkpoint-selection-protocol.md) records the scientific rule.
The [pre-campaign amendment](checkpoint-selection-amendment-v2.md) corrects a
numerical-check fixture selection error while preserving the first registration,
its failed check, all scientific inputs, model exports and solver code.
The corrected check uses original accepted classical TRAIN observations and
compares ten simultaneous native solves with their serial accepted profiles.

## Reproduction

Use Java 21 and the project Python environment. The current driver is
`tools/neural/native_checkpoint_selection_v2.py`; revision 1 is retained as
provenance of the superseded pre-campaign registration. Commands are `prepare`,
`export`, `parity`, `validation`, `select`, `test`, `report`, and `seal`, in that
order. Completed campaign folders are validated and reused; partial or altered
results are rejected rather than overwritten. Do not rerun completed campaigns
to seek a more favorable timing measurement.

The published results, machine-readable summary and cache manifest accompany
this document. The verified archive resides at
`.neural-cache/checkpoint-selection-v2/study.zip`, outside Gradle clean, and
contains both registrations, scientific inputs, three checkpoints, native
exports, parity evidence, numerical and scheduling checks, raw journals,
selection calculations, reports and source dependencies. Retain preceding
study caches for their historical provenance. Sealing recomputes the summary
from verified journals and checks every archived entry's size and SHA-256.

Across-case sample standard deviations describe case variability; they do not
estimate repeated-run timing uncertainty. Concurrent wall times include CPU
contention and must not be pooled with previous serial measurements. The 168
certified-reference profile metrics remain diagnostic and do not choose the
winner. No production default is promoted by this experiment.

The frozen generated report contains an encoding artifact in its separator:
`卤` means "mean plus/minus sample standard deviation" in those tables. The
numeric values and JSON fields are unaffected. The separately authored results
below use distinct mean and sample-SD columns. The registered driver and its
generated report are preserved to maintain the execution provenance.

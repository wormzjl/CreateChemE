# Matched Gen2, Gen3 and transformer comparison

Compare three frozen published candidates: the Gen2 MLP, the validation-selected
Gen3 factorized network, and the retained accuracy-study transformer seed
20260911. The Gen3 MLP and nearest-profile variants remain historical controls;
they are not substitutes for the selected Gen3 neural model in this comparison.

Use the exact 405 validation inputs and 252 test inputs from checkpoint selection
revision 2. These test outcomes are already exposed for the transformer, so this
is a fixed-model comparison on a shared cohort, not a new blind test or a new
selection exercise. No model fitting, threshold tuning, winner selection or
runtime promotion is performed.

Reuse the complete verified ten-worker transformer validation and test journals
byte for byte. Run Gen2 validation, Gen3 validation, Gen2 test, then Gen3 test,
once each. Each new campaign has its own contemporaneous classical control.
Preserve case order, Java 21, 4 GiB maximum heap, fixed warmup, ten platform
workers, at most ten outstanding cases, a queue of ten, 2,000 ms neural budget,
30,000 ms request deadline and 16 iterations per correction pass. Use the
unchanged concurrent-column-evaluation-v1 harness and strict acceptance rules.
Never repeat campaigns to obtain more favorable measurements.

Verify candidate bytes and existing parity evidence against their retained
manifests. Before campaigns, test shared-model predictions on original TRAIN
inputs with ten simultaneous workers, including bit-identical serial/parallel
outputs, cancellation, subsequent unchanged predictions and owned termination.
The first ten calls must all produce supported predictions and overlap at a
control checkpoint inside prediction execution. Repeat supported TRAIN inputs
if necessary; record fixture coverage separately from the concurrency batch.
Keep this check outside measured campaigns. Reuse the existing verified native
solver/scheduler checks; do not edit frozen source files or build.gradle.
Freeze artifact, population, reference-journal, implementation and evidence
hashes before the first new scientific campaign.

Report strict qualifications, advisory-only and failed outcomes separately for
all three strategies on every case. Timing and CPU statistics include failures;
missing optional counters remain missing. Compare each model with its paired
classical control and with the reused transformer by input ID. Report classical
control disagreements and cross-campaign timing variation. Sample SD describes
case variation, not repeated-run uncertainty. Generations differ in training
data and model selection as well as architecture; do not attribute differences
solely to network structure.

Diagnose stage/steam strata, raw prediction availability, native failure codes,
and common-reference profile errors with explicit denominators. Report any
union of separately solved success sets only as a diagnostic upper bound on
potential complementarity, never as a measured cascade result or speedup.
Use these observations to rank subsequent experiments without implementing
new training or solver policies in this study.

Before publication, recompute the full report from validated journals and
require equality. Preserve raw journals, model bytes, checks, plans and source
dependencies in a separately verified archive outside Gradle clean. Keep the
previous checkpoint-selection archive and all older studies intact.

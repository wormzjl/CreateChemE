# Frozen Transformer plus one material-completion pass: feasibility probe

This is a bounded TRAIN-only investigation, not model selection or a new
validation/test campaign. Preserve the retained Transformer seed 20260911,
all solver sources, decoder, acceptance rules and preceding sealed evidence.

Select exactly 20 distinct inputs from the existing 805 eligible, strictly
certified TRAIN columns. Restrict inputs to no steam and no side draws; retain
authored heat-only pumparounds. Sort eligible inputs by `(stageCount, id)` and
take indices `floor(i * (n - 1) / 19)`, for `i = 0..19`. This selection uses no
predicted profiles, errors, timings or holdout results. Keep all selected cases
after preprocessing failures; do not replace or expand them. Verify the source
against the accuracy registration and model bytes against the accepted
generation-comparison registration. Freeze selected input/provenance hashes,
implementation hashes and preflight evidence before the first measured request.

Compare two treatments once on every input: ordinary Transformer LNN_FIRST and
the same Transformer with one mechanistic material-completion pass, also
LNN_FIRST. There are at most 40 complete measured requests. Alternate treatment
order by the fixed case ID hash. Use the existing owned ten-worker evaluator
with at most ten cases outstanding, Java 21 and 4 GiB maximum heap. Queue wait
is separate; each treatment starts a fresh 30-second request deadline. Prediction
and completion share the existing 2,000 ms neural budget and 16 correction
iterations. No new classical control or neural-only request is run. Use the
first selected TRAIN input for two prediction-only warmups per treatment.

The offline wrapper obtains the unchanged Transformer prediction, resolves its
requested problem/branch and copies its arrays. It computes current PR K-values
from the predicted temperatures and compositions, then invokes the existing
single component tridiagonal material solve. Preserve predicted temperatures,
branch, public component basis and zero free-water fields. Do not call the
three-sweep bubble-point projection or the twelve-sweep sequential selector.
Do not reapply component thresholding after completion. The normal native
corrector continues to derive and refresh support and exclusively owns final
acceptance.

Preprocessing is inapplicable to steam, free water or side draws. Decline
nonfinite/negative/structurally invalid candidates or phase totals above
1,000 times feed, matching the existing decoder's phase-total bound. Return the
raw prediction on ordinary preprocessing decline. Check the caller's control
before and after each thermodynamic call using a request-local delegating
boundary, as well as around the component solve. Propagate cancellation and
neural-budget exceptions; do not convert them into an ordinary decline. One
in-progress property call remains indivisible. Record all preparation cost,
declines and aborts without claiming a hard real-time deadline.

Preflight checks serial/parallel seed equality, unchanged source predictions,
temperature/branch/input preservation, material closure for prepared states,
cancellation during the thermodynamic boundary and owned-worker termination.
Synchronize ten preparation calls at the first property boundary for the
parallel check. These are preparation-only checks, not additional corrected
column requests. Reuse verified network parity and native solver/scheduler
evidence from the preceding comparison; no original files are changed.

Run raw/prepared diagnostics separately from timed requests. Record full-grid
component material defects using fixed input-component flow scales, native
residual families and their denominators/scales, support changes, raw-to-prepared
profile movement and preprocessing cost. Native support-projected family
residuals are descriptive; their state-dependent scales do not define a common
objective or acceptance gate. Preserve unavailable diagnostic reasons.

For each complete request record strict/advisory/failed outcomes, native events,
fallback use, accepted profile/certificate, wall time and CPU time. Statistics
include every selected case, including failures and preprocessing declines.
Report paired gained/lost IDs and all-case latency differences. Do not count a
raw or material-closed seed as a solved column. TRAIN results and one timing
sample do not establish generalization or a production speedup.

Recompute the report from complete journals and frozen evidence. Retain the
probe, source dependencies, model, input-only fixtures, preflight checks and
report in a new verified archive outside Gradle clean. Any future training,
coverage extension or policy change needs a separate validation-driven study
and a new independent test cohort.

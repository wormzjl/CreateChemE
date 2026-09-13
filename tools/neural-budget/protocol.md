# Neural correction budget: walls into progress rules, on the frozen F0 weights

This implements recommendation R2 of the Transformer initializer review as a bounded diagnostic followed
by a paired campaign. No model is trained, no weight byte changes, and no production default changes. The
acceptance audit, the support rules, every tolerance and the strict-data boundary are untouched.

Registered before the diagnostic ran. Every threshold in section 4 and every parameter in section 5 is
stated here; `budget_trace.py` applies them and chooses nothing of its own.

## 1. What the archive says, and what it cannot say

On the 405 validation inputs with F0 the strict counts are classical 110, neural-only 134, neural-first
168, reproduced in both blocks by the decoder-floor campaign. Of the 271 non-strict neural-only requests,
137 stop at exactly sixteen Newton iterations, 75 stop at the two-second neural allowance, 18 stop with no
admissible step, 35 are unattributed and 6 are advisory. Seventy-eight per cent are budget walls rather
than divergence, and on the 34 cases the classical solver takes while the neural-only request does not,
the neural arm sits at the sixteen-iteration cap while classical needs a median of three Newton steps
after its continuation.

None of that says whether another step would have helped. A stop phrase is a label on a wall, not a
measurement of the trajectory that hit it. Two things in particular are not recoverable from the archived
journals:

- **The 75 time-limit cases record zero Newton iterations.** That is a reporting artefact, not a seed
  rejected before its first step. `V3ColumnCalculator.calculate` throws `NeuralBudgetExceeded` out of
  whatever the correction was doing; the candidate loop then has no failure of its own and published a
  zero-iteration, zero-residual placeholder. This study's source change captures the interrupted attempt's
  evidence instead. It changes published diagnostics only: no outcome, budget, tolerance or digest moves,
  and the archived strict identity sets are the parity gate that measures that claim.
- **Whether a capped trajectory was still contracting.** Nothing in the journals carries a per-iteration
  residual. The bounded diagnostic below records one.

## 2. The bounded diagnostic

`V3BudgetTraceProbe` runs one ordinary `LNN_ONLY` request per case through the unchanged production path
with the calculator's package-local observation trace attached. The trace observes; it cannot change a
step, a support, a tolerance or an outcome, and every production entry point passes `V3NewtonTrace.NONE`.

Per case it records the decoded seed's zero-phase count, the initial projected maximum scaled MESH
residual, the published outcome and diagnostics, and the wall and CPU cost. Per Newton attempt inside the
case it records the iteration budget that attempt was given, the support retention and wet-tray count it
was prepared on, the maximum scaled residual and scaled merit of every iteration, the accepted and
rejected local-block directions, the fresh and reused finite-difference Jacobians, and the solver code
that ended it. The fixed-water wet prepass appears as its own segment, and the attempt left open when the
case ends is the one the budget interrupted.

**Target population** (`tools/neural-budget/ids/`, derived from the decoder-floor campaign's committed
baseline evidence, which reproduced the archived F0 journals exactly):

| Group | Block 1 / 2 | Stable across blocks | Union |
| --- | --- | ---: | ---: |
| iteration-capped | 138 / 137 | 134 | 141 |
| time-capped | 75 / 75 | 70 | 80 |
| classical-only loss | 34 / 34 | 34 | 34 |
| **traced union** | | | **216** |

A case enters a group when it qualifies in either block, because the boundary between the two walls is
itself jitter: a case finishing its sixteenth correction a few milliseconds before the wall is recorded as
iteration-capped instead of time-capped, and seven cases change wall between the baseline's own two blocks.
Of the 216, 52 are among the 168 certified reference profiles (50 of them capped); the seed-omission half
of the classification is decidable only on those, and the decoder's own zero-phase count stands in for the
rest.

**Two configurations**, both on the `F0-baseline` pipeline, ten owned workers, thirty-second request
deadline, 4 GiB heap, the fixed TRAIN warmup:

| Configuration | Correction iterations | Neural allowance |
| --- | ---: | ---: |
| production | 16 | 2,000 ms |
| diagnostic | 48 | 6,000 ms |

Only the two walls differ. Every tolerance, support rule, refresh limit and audit is the production one,
so a case that converges only under the diagnostic configuration was crawling, not failing.

## 3. Definitions

- **Converged** means the study's own strict definition, the same one the campaign counts use: an accepted
  result with a qualified equilibrium, every audit check passed, a final Newton step, closure `1e-8`,
  linear backward error at most `1e-12`, maximum log-flow change at most `1e-8`.
- **Contraction ratio** of a trajectory is the maximum scaled residual at its last recorded iteration
  divided by the one eight iterations earlier. It is undefined, and the case is reported as *short*, when
  the terminal attempt recorded eight or fewer iterations.
- **Capped population** is the union of the iteration-capped and time-capped groups: 214 of the 216 traced
  cases, since seven cases hit one wall in one block and the other in the other, and 32 of the 34
  classical-only losses are themselves capped. The two remaining classical-only losses stop with no
  admissible step or without attributable evidence, and are traced and reported but never counted in a
  decision rule.

## 4. Pre-declared classification and decision rules

Per traced case, on the terminal attempt of the stated configuration. The groups are not exclusive and
every case reports all of them.

| Class | Rule |
| --- | --- |
| crawling | Not converged under the production configuration, converged under the diagnostic one. |
| stalled | Not converged under the diagnostic configuration and its contraction ratio exceeds 0.5. |
| hopeless | Not converged under the diagnostic configuration and its contraction ratio exceeds 0.9. |
| reinserting | Spent at least one support refresh, and either omitted at least one above-floor reference entry (on the 52 reference cases) or decoded at least one phase to zero (on the rest). |

Decision, evaluated on the capped population:

| Rule | Fires when | Selects |
| --- | --- | --- |
| A-extend | at least **10** capped cases are crawling | the progress extension of intervention A |
| A-abort | at least **25%** of capped cases are stalled | the early stall stop of intervention A |
| B | at least **25%** of capped cases are reinserting **and** the median decoded zero-phase count over the capped population is at least 1 | intervention B |

A is implemented when A-extend or A-abort fires; the stall stop is always part of A, and the extension is
included only when A-extend fires. B is implemented when B fires. Both are implemented when both fire, and
the campaign then also carries their combination. **If none of the three fires, the fallback is A in
abort-only form**, because a diagnostic that finds the capped trajectories flat has shown that the value
is in returning the wasted time rather than spending more of it, and that is still a measurable claim
against the latency gate.

Ten crawling cases is the smallest recovery worth eight runs of campaign time: the review puts the total
coverage headroom for any initializer at 30 to 39 cases, and the campaign gate demands a strict neural-first
gain in *both* blocks, which a smaller and jitter-sized group could not supply.

## 5. The two candidate interventions

### A. Progress-based correction budget (solver side, neural path only)

The sixteen-iteration base cap stays. An attempt that reaches it is granted a further block of **8**
iterations, up to a total of **48**, while the windowed contraction ratio over its last 8 iterations is
below **0.5** — that is, while the residual is still at least halving over each window — and while it is
above the closure tolerance. **The two-second neural allowance is not raised.** The wall stays where it is
so that the whole intervention is bounded by the same wall-clock budget the archive measured, and the
extension can only spend time a capped case was already permitted to spend.

The early stop is the rung-budget stall rule the steam rungs already carry, applied to the neural
correction with deliberately conservative settings: window **8**, factor **0.9**, residual floor **1e-6**,
with every damping step, the gradient fallback and the verification cascade of `RungBudget.DEFAULT`
retained. Factor 0.9 stops only a trajectory that has barely moved at all across half the base budget;
the steam rungs use 0.5. The floor keeps the rule away from a case that is merely closing slowly near
tolerance. Reduced rung budgets are admissible only on the stage-local continuation policy, which is the
policy the neural path uses.

**Net-time rule.** Before the campaign is registered, the extension's predicted cost and the abort's
predicted saving are computed from the production-configuration trace: for each capped case, the
extension costs (granted iterations beyond 16, bounded by the remaining wall) times that case's measured
milliseconds per iteration, and the abort saves (iterations after the stall point) times the same rate.
The candidate is registered at the first setting in this ladder whose predicted saving is at least its
predicted cost:

| Step | Extension cap | Contraction factor |
| ---: | ---: | ---: |
| 1 | 48 | 0.5 |
| 2 | 32 | 0.5 |
| 3 | 32 | 0.25 |
| 4 | 24 | 0.25 |
| 5 | abort only | — |

The ladder is fixed here so that the parameter is chosen by a declared rule and not by the campaign's own
result. The campaign then measures the real cost; the ladder only keeps the candidate from being registered
in a form the pooled-mean latency gate obviously cannot pass.

### B. Phase-level decoder floor (decoder side, frozen F0 weights)

The decoder-floor study measured where F0's 819 above-floor omissions on the 168 reference profiles come
from: 765 are whole phases the phase-total head decoded as zero, 54 are components the presence head
dropped, and **zero** are the "kept but undershot" case its own lift rule could reach. A treatment has to
move the phase-total head's zeros, which is what this is.

`V3FactorizedNeuralFeatures.DecodeOptions` gains a zero-phase floor. When a phase total decodes to exactly
zero, the condenser branch allows that phase at that node, and at least one component of the phase reaches
the production presence threshold, the phase total is seeded at **10** support floors summed over the kept
components, and the phase is then decoded by the unchanged rule. Ten is
`V3TruncationSupport.FLOOR_REINSERTION_FACTOR`, the hysteresis above which the native support already
treats a removed phase as carrying material again, so a lifted phase lands exactly at the flow the support
rules call present. A phase the branch forbids — the condenser's vapour under `LIQUID_ONLY`, its liquid
under `VAPOR_ONLY` — is never lifted, and neither is a phase in which the presence head kept nothing.

One parameter setting, not a sweep: the campaign budget is eight runs, and the decoder-floor study showed
that a decode rule which changes nothing measurable is not made interesting by trying three of it.

## 6. The campaign

All pipelines load the identical archived F0 weight document `F-20260911-s4160`, SHA-256
`7f909d025e12cbc7e8457b459adfbc63e48f52fb9e98ce45b91e64e84ddd1962`, with full native anchors, absolute
outputs and no completion wrapper. What differs is stated in the pipeline manifest: the decode rule and
the correction budget.

| Pipeline | Decoder | Correction | Registered when |
| --- | --- | --- | --- |
| F0-baseline | prune | fixed, 16 iterations, 2,000 ms | always |
| F0-progress | prune | progress | A fires or the fallback applies |
| F0-phase-floor | zero-phase floor, factor 10 | fixed | B fires |
| F0-progress-phase-floor | zero-phase floor, factor 10 | progress | both fire |

Every pipeline runs all three strategies over the frozen 405-input population in two blocks whose pipeline
order is reversed, on exactly ten owned worker threads, with a thirty-second request deadline and a 4 GiB
heap, never overlapping another campaign or a Gradle run. The native core is rebuilt from this worktree's
sources with only Gson on the compiler classpath; the registration records every source hash and proves,
against the sealed trace-followup registration archive, that every source which differs is one of the six
files named in `budget_register.ALLOWED_SOURCE_DELTA`.

**Parity gate, before the campaign.** `V3BudgetDecodeCheck` dumps the complete decoded seed of every
pipeline for all 405 inputs with no solver involved, and `F0-baseline` must reproduce the archived F0
seeds of the predecessor campaign exactly in both archived blocks. Afterwards `F0-baseline` must reproduce
the archived strict identity sets exactly: 110 classical, 134 neural-only, 168 neural-first, the same case
identities, in both blocks. Published diagnostics fields are deliberately not part of that claim — the
in-flight evidence fix replaces the archived zero-iteration placeholder of a budget-stopped failure with
what the interrupted attempt measured — and no strict outcome depends on them.

**Decision.** A variant passes the study's gates only with a strict neural-first gain in **both** blocks,
preservation of the contemporaneous classical strict union, and no pooled mean neural-first latency
regression against `F0-baseline`. Neural-only changes, paired case identities, stop phrases, stable
common-success costs, **stable common-failure costs and iteration distributions**, per-regime tables, the
three registered groups and the decode-level omission counts are reported whatever the verdict. Passing
authorises further qualification only; nothing is promoted.

Failure latency is reported beside success latency because that is where a reshaped budget acts first: 91
per cent of all-case time is the 237 cases nothing solves, and a rule that stops a hopeless correction
earlier shows up there and nowhere else.

## 7. Running it

`P` is `C:/Users/wormz/.codex/worktrees/8848/CreateChemE/.neural-venv/Scripts/python.exe`, run from
`tools/neural-budget`. Nothing here may overlap another campaign or a Gradle run: every step owns ten
worker threads.

```
$P budget_ids.py build                 # committed id lists, seconds, no JVM
$P budget_register.py register         # frozen inputs, pipeline manifests, source delta
$P budget_ids.py inputs                # the 216-case traced subset of the frozen inputs
$P budget_native.py rebuild-core       # isolated Gson-only rebuild, about a minute
$P budget_preflight.py decode          # solver-free decode dump per pipeline, about a minute each
$P budget_preflight.py check           # archive seed parity; must pass before any campaign time
$P budget_trace.py production          # bounded diagnostic, 216 cases, 16 iterations / 2 s
$P budget_trace.py diagnostic          # bounded diagnostic, 216 cases, 48 iterations / 6 s
$P budget_trace.py analyse             # classification and the declared decision
                                       # implement the selected intervention, then rebuild and re-register
$P budget_benchmark.py validation      # 405 x 3 modes x 2 blocks per pipeline, about 6.6 minutes a run
$P budget_analysis.py
$P budget_report.py
$P budget_seal.py
```

The two diagnostic runs are a few minutes each: 216 cases of a single strategy on ten workers, where the
whole 405-case three-strategy campaign run takes about 395 seconds. The campaign is two runs per pipeline;
four pipelines is eight runs, about 53 minutes.

## 8. Limits declared in advance

The traced groups are outcome-selected diagnostics, not a population benchmark, and the diagnostic budget
is not a candidate: nothing proposes shipping 48 iterations and six seconds. Historical validation, not a
fresh holdout; the 168 certified references are a subset of the 405 evaluation inputs. The two blocks
repeat the same inputs, so their spread describes case and load variability, not a confidence interval
over independent campaigns. The decoder-floor campaign measured a 114 ms spread in the pooled neural-first
mean between two runs of an identical pipeline on identical seeds, so latency differences below that are
concurrency noise and will be reported as such. Above-floor omissions are decode diagnostics against the
certified references and do not prove that a reference phase is necessary at every admissible root.
Advisory successes are excluded from every strict count and no failed input leaves the denominator.

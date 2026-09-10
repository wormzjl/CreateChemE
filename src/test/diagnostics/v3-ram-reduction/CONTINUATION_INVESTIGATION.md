# Why allocation spikes: continuation work and seed quality

Investigated on `codex/solver-ram-reduction` after the compact-Jacobian/LU pass.
This work adds diagnostic tools and generated instrumented copies; the production
solver and its policies were not changed.

## Findings

The high-allocation cases spend much more work in unsuccessful continuation and
recovery attempts. A nearby converged state can avoid much of that work. The
evidence supports improving seed selection and the continuation route; it does
not identify a defect in the cold initializer's equations.

The following are **whole-calculation counts**, including support refreshes and
free-water sub-solves, from the first of two matching instrumented repetitions.
Allocation is cumulative temporary allocation, not resident RAM.

| Case | Allocation MiB | Newton solves | Total Newton iterations | Full residual evaluations | Fresh FD Jacobians | Line-search trials |
|---|---:|---:|---:|---:|---:|---:|
| Default | 1157.0 | 21 | 183 | 6582 | 24 | 719 |
| Pressure -5% | 5972.3 | 31 | 396 | 32717 | 121 | 6787 |
| Pressure -10% | 1111.0 | 20 | 184 | 6325 | 23 | 615 |
| Reflux +10% | 4138.4 | 38 | 608 | 19047 | 58 | 5491 |
| Temperature -5% (cold route fails) | 2507.9 | 33 | 433 | 13753 | 40 | 4580 |

The existing final diagnostics show only 3–5 Newton iterations for the successful
cases above. Those are the final solve's iterations, not the cost of the complete
calculation. The new counts explain the apparent mismatch.

Pressure -5% performs about **five times as many full residual evaluations and
fresh finite-difference Jacobians**, and **9.4 times as many line-search trials**,
as the default. PR phase evaluations rise from 455,679 to 2,622,221.

### Where the expensive work goes

For pressure -5%, 11 of the 31 Newton solves terminate without convergence. They
account for **4700.4 MiB, or 78.7% of the calculation's allocation**. This is a
classification of work inside unsuccessful attempts, not a claim that all of it
can safely be discarded: failed states can seed later recovery attempts.

The largest high-level passes are:

| Pressure -5% pass | Allocation MiB | Result |
|---|---:|---|
| Retried steam ramp at 62.5%, full budget | 2322.2 | Line search exhausted |
| First requested side-draw/full-feature pass | 1108.5 | Singular linear solve |
| Eventual requested full-feature pass | 628.2 | Converged and audited |
| Full-budget steam attempt at 45.83% | 545.4 | Line search exhausted |

The retried 62.5% steam pass contains 82 Newton iterations across its internal
solves/refreshes, despite the event text reporting the last solve's 13 iterations.

For reflux +10%, unsuccessful Newton solves account for **2468.8 MiB, or 59.7%**.
Several heat-ramp retries and the first requested full-feature attempt contribute.
For the default, the corresponding unsuccessful-attempt share is only **15.6%**.

Constructing the initial seed itself costs only 17–21 MiB in these cases: about
**1.5% of default allocation and 0.35% for pressure -5%**. Its quality can still
determine how much work follows; the initializer's own allocation is not the spike.

## Controlled seed experiment

Two direct full-input solves use the same liquid-only condenser branch, target
problem, local-Jacobian policy, full rung budget, maximum 128 iterations, support
refresh logic, fresh thermodynamic model and acceptance audit:

1. A fresh steam-capable `MATERIAL_CLOSED` cold initializer supplies the state.
2. The numerical state of a previously accepted default calculation supplies it.

Neither receives a saved convergence certificate. Both must produce fresh final
Newton evidence and pass the unchanged `1e-8` closure and correction gates.
The direct procedure is an experiment; the production cold route normally starts
on simpler stage/feature problems. Thus this comparison isolates seed quality
**within the direct procedure**, while comparison with the production route also
changes the continuation path.

Both repetitions produced the same numerical outcomes and work counts:

| Target | Production cold-route allocation MiB | Direct fresh-cold guess | Direct nearby-default guess |
|---|---:|---|---|
| Default | 1157.0 | Fails; 4541.5–4541.7 MiB | Passes; 203.1–203.2 MiB |
| Pressure -5% | 5972.3 | Fails; 9436.6 MiB | Passes; 353.0 MiB |
| Pressure -10% | 1111.0 | Fails; 9407.3 MiB | Passes; 323.6 MiB |
| Reflux +10% | 4138.4 | Fails; 2510.6 MiB | Passes; 354.8 MiB |
| Temperature -5% | 2507.9; fails | Fails; 9796.2 MiB | Passes; 687.7 MiB |

Pressure -5% falls from 396 total Newton iterations to 13 with the nearby state;
reflux +10% falls from 608 to 15. The temperature case remains harder: its nearby
attempt still uses 136 iterations across solves/refreshes, but reaches an accepted
solution that the production cold route did not find.

Simply bypassing continuation with a fresh cold guess fails in all five controlled
cases. The useful ingredient is the better state, not omission of the continuation
schedule alone.

### Independent wider check

The accepted default state was exported losslessly and passed to the ordinary,
**uninstrumented production solver** through the same direct procedure. Across
the default and all 24 one-factor ±5%/±10% perturbations, **24 of 25 cases pass**.
Both ±5% temperature cases now pass; temperature -10% still fails. All 22 cases
that passed the ordinary cold route also pass this nearby-state experiment.

For the five controlled cases, the uninstrumented result, audit, certificate and
duty payloads match the instrumented nearby experiment exactly. Across the 22
cases with accepted cold results, the largest observed cold-versus-nearby differences
are:

* Stream temperature: **2.16e-8 K**.
* Relative stream molar flow: **3.71e-10**.
* Absolute component mole fraction: **6.46e-10**.

These are not bit-identical cold-versus-nearby results, but they remain very close
and pass the same acceptance and correction checks. The two newly solved
temperature cases have no accepted cold result to compare against. Their success
means they pass the current model's criteria, including its existing advisory
water-dew-point behavior; it is not a new independent validation of that model.

The source/default calculation's cost is excluded from nearby-attempt figures.
If a nearby state is already available after a parameter edit, that cost is sunk.
If it must be calculated solely as an anchor, add approximately 1157 MiB here:
pressure -5% still costs about 1510 MiB rather than 5972 MiB, but pressure -10%
would cost about 1481 MiB rather than its already cheap 1111 MiB cold route.
Always calculating an anchor is therefore not a universal improvement.

## What to pursue next

The strongest candidate is a **guarded nearby-state start**, with the current cold
route retained as fallback. It should rebuild the target problem and phase/support
decisions, preserve source-state immutability, bound the warm attempt, and require
fresh residuals, correction evidence and the full target audit. Source compatibility
must cover topology, component basis and property package; this experiment held
those fixed and is not a test of arbitrary structural edits or wet-to-wet reuse.

For genuinely cold calculations, improve predictors and continuation choices at
the expensive steam/heat transitions. Do not simply remove retries or lower all
budgets: the failed states sometimes provide useful progress, and the direct-cold
controls failed. Cumulative solver telemetry would also make the UI's work counts
more informative than the final-attempt count.

## Instrumentation and reproduction

Ten production source files are copied into `build/generated/continuation-profile`
and instrumented there. The manifest hashes verify that their original production
sources have not changed. The diagnostic classpath places only those generated
classes first; regular builds use the production classes.

Scopes record inclusive and exclusive thread allocation, wall time, initialization,
high-level passes, Newton solves, predictors and free-water continuation. Counts
include full residual evaluations, local thermodynamic terms, PR phase evaluations,
FD/local Jacobians, banded LU solves and Armijo trials. Newton iteration totals sum
each solve's evidence; an additional observed-state count includes repeated visits.
Inclusive parent/child allocation is checked algebraically to prevent double counting.

All ten full-route profiles match fresh uninstrumented reference outputs exactly,
including failure diagnostics, and match the earlier round-three benchmark.
Profiling allocation stays within 5% of the uninstrumented reference (a validator
guard, not an asserted overhead percentage). Counts and numerical scope data
repeat exactly. Timings are diagnostic measurements, not a new controlled speed
benchmark. The two controlled repetitions and the wider one-pass witness are
retained separately.

```powershell
node src/test/diagnostics/v3-ram-reduction/prepare-continuation-profile.cjs
.\gradlew.bat -I src/test/diagnostics/v3-ram-reduction/continuation-profile.init.gradle v3ContinuationProfile v3ContinuationReference v3ContinuationWitness --no-configuration-cache
node src/test/diagnostics/v3-ram-reduction/analyze-continuation-profile.cjs
```

The validator also uses the earlier `round3-candidate-2.json` benchmark as an
independent reference. Raw results are in `build/reports/v3-continuation/`:
`profile.json`, `reference.json`, `default-seed.json`, `unprofiled-nearby.json` and
`summary.json`. Detailed scope records identify individual expensive passes.

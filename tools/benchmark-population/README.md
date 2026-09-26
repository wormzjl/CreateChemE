# Benchmark populations that count only solvable requests

Every neural benchmark in this project divided by a population that contained requests the shipped solver
refuses without solving. This study removes exactly those, records why for each one, and re-bases the
headline statistics on what is left. The result is in [`v1/population.md`](v1/population.md).

Nothing here runs a campaign, and nothing here is a solver change. The one production edit is a seam:
`V3ColumnCalculator.requestOnlyAdmission(input, ratio)` now holds the three request-only gates the
`calculate` path already applied in that order, so an offline screen can ask production what it would answer
instead of reimplementing it.

## The rule

Applied in this order; the reason, the gate and the evidence source are recorded per id.

| Reason | Meaning |
| --- | --- |
| `REQUEST_ONLY_TYPED` | The promoted branch types the input `INFEASIBLE_SPECIFICATION` from the specification alone, at the production liquid-supply ratio 0.30. |
| `STATE_GATE_NEVER_SOLVED` | The archived classical control typed it `INFEASIBLE_SPECIFICATION` while the retired `condensationCappedTray` / `requireCoolingBelowBaseCondenserDuty` bounds still published that code, **and** no archived arm, in any mode or block, reached strict or advisory on it. |

Advisory-only and never-converged cases are **not** excluded. They are the work left, and they are listed
per population in `v1/<population>/open-set.json`.

## Layout

```
tools/benchmark-population/
  java/V3RequestAdmissionProbe.java   the admission probe; calls production, never solves
  population_common.py                paths, hashing, the one row shape every population uses
  prepare_populations.py              copy the five populations out of their archives
  run_probe.py                        compile the isolated core and run the probe on each
  evidence.py                         the archived journals, per population, with their hashes
  classify.py                         apply the rule; write the filtered populations
  rebase_statistics.py                re-base the headline numbers; render v1/population.md
  harness.py                          the hook a campaign harness uses to take a filtered population
  v1/manifest.json                    source hashes bound to filtered hashes, for all five
  v1/statistics.json                  the re-based numbers
  v1/population.md                    the report
  v1/<population>/exclusions.json     id, reason, gate, detail, evidence source
  v1/<population>/open-set.json       what is retained and still unsolved
  v1/<population>/<population>-inputs.jsonl   the filtered population
```

## Reproducing

```
python tools/benchmark-population/prepare_populations.py
python tools/benchmark-population/run_probe.py
python tools/benchmark-population/classify.py
python tools/benchmark-population/rebase_statistics.py
```

The first step reads the sealed predecessor worktree; zip members are extracted only under `%TEMP%` and
nothing under it is ever written. `run_probe.py` compiles the self-contained `science/column/v3` package with
Gson as the sole classpath entry and sweeps all 3,297 inputs in under two seconds, because it performs no
solve.

## Running a campaign on a filtered population

`harness.resolve` is the whole hook, and it changes no default: with no argument a study gets the archived
population it always used, so every sealed campaign reproduces byte for byte. Two entry points accept a
filtered one:

```
python tools/transformer-promotion/promotion_native.py block1 \
    --population tools/benchmark-population/v1/validation/validation-inputs.jsonl
python tools/transformer-promotion/promotion_compare.py 1 2 \
    --population tools/benchmark-population/v1/validation/validation-inputs.jsonl

python tools/neural-budget/budget_benchmark.py validation \
    --population tools/benchmark-population/v1/validation/validation-inputs.jsonl
```

`CREATECHEME_BENCHMARK_POPULATION` does the same for a script without the flag.

Three properties make this safe to point at a sealed study:

1. **Registered or refused.** A requested population must appear in `v1/manifest.json` by SHA-256, so the
   denominator a campaign publishes can always be traced back to the rule that produced it. An unregistered
   file is rejected with the command that would register it.
2. **Separate outputs.** A non-archived run writes under `build/<study>/<rev>/population/<label>/`, never
   over the archived run directory. `promotion_seal.py` still seals the archived run only.
3. **Narrowed, not weakened.** `promotion_compare.py` asserts the filtered ids are a subset of the qualified
   population before restricting the reference to them, so the identity check it performs is the same check.

To add the hook to a third harness:

```python
sys.path.insert(0, str(ROOT / 'tools/benchmark-population'))
import harness

_POPULATION = None

def population():
    global _POPULATION
    if _POPULATION is None:
        _POPULATION = harness.resolve(INPUTS / 'validation-inputs.jsonl')
    return _POPULATION
```

then replace the hard-coded input path with `population().path`, the hard-coded case count with
`population().cases`, the frozen-input hash assertion with `population().sha256`, and prefix the run
directory with `population().label` when `population().archived` is false.

## Generating conditions that are feasible to begin with

`tools/neural/generalized_design.py` can reject sampled conditions the request-only admission would type,
at generation time:

```
python tools/neural/generalized_design.py generate <out> --request-only-screen-ratio 0.30
```

Off by default, because turning it on changes which points a design admits and every archived matrix has to
keep reproducing — `generate(baseline, 202609104)` still yields 2,809 candidates and 17 preflight exclusions,
exactly as the frozen Gen4 pool records. At 0.30 the same pool loses 125 more points (101 by the calibrated
envelope, 24 by the necessary `rho >= 1` balance); the base design matrix loses 132, which is exactly the
16 + 20 + 96 requests this study excludes from its validation, historical-test and train folds.

Two of the three request-only gates are pure arithmetic on the request and are ported to Python in
`generalized_design.liquid_supply_ratio`, checked case for case against the Java verdict on all 3,297
archived requests by `tools/neural/test_generalized_design_request_only.py`. The third,
`V3HeatFeasibility.availableCoolingWatts`, needs one feed flash and so needs the property package: a
generator that wants it must pass its matrix through `java/V3RequestAdmissionProbe.java`, which is one
command and seconds of wall time. It fired on none of the 3,297 archived requests, so this is a completeness
step rather than a load-bearing one.

## Line endings

Every population, journal and model artifact here is identified by the SHA-256 of its bytes. On a
`core.autocrlf=true` checkout git rewrites their line endings and every hash assertion in every campaign
harness fails on files whose committed blobs are correct — including, before this branch, the promotion
study's own `validation-inputs.jsonl`, `warmup.json` and bundled model. `.gitattributes` pins them.

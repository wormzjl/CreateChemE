# Generalized column experiment design

The frozen experiment jointly varies every registered hydrocarbon component,
geometry and operating conditions. Methane is one of the 20 component coordinates;
it has no separate feature, sampling group or train/test rule. The same property
package is retained, so this is an experiment over synthetic mixtures of its
registered components, not evidence for arbitrary crude assays or new species.

## Finite design and what completeness means

The structural candidate matrix is the full Cartesian product of:

| Structural factor | Levels |
| --- | --- |
| Equilibrium tray count | Every integer 2–64, inclusive |
| Sump steam | Off, on |
| Pumparound count | 0, 1, 2, 3, 4 |
| Side-draw count | 0, 1, 2, 3 |

There are **2,520 distinct structural cells**. A seeded selection of 289 cells is
replicated, giving **2,809 candidate columns**. Every replicate changes the whole
operating vector together. The design is generated and its split frozen before
any teacher output exists. The master file retains invalid structural cells;
the solver receives only cells passing preflight.

With the default seed `20260910`, preflight excludes 16 two-tray structural
candidates and admits **2,793 columns**: 1,993 train, 405 validation and 395 test.
There are eight PA-pair violations and ten side-draw-tray violations, overlapping
in two rows. None of these sixteen exclusions is classified as a general physical
impossibility. Every pressure from the top through the sump is within the requested
100–300 kPa, and admitted inputs include exact reflux ratios zero and ten.

The 48-dimensional continuous/location/mixture latent vector uses a
**strength-two orthogonal-array Latin hypercube**, OA(53², 48, 53, 2). Construction
uses the prime-field columns `a`, `b`, `a+b`, `a+2b`, …, independent symbol
permutations and independent sublevel permutations. Each latent marginal has
exactly one point in each of 2,809 fine strata; each pair of latent factors has
exactly one point in each of 53 × 53 coarse cells. The generator independently
checks all 48 marginals and 1,128 pairs. Replacing the two extreme points of each
marginal with exact 0 and 1 retains their strata and includes operating limits.
Seeded random assignment joins the continuous design to the structural matrix.
It does not claim continuous orthogonality conditional on every structural cell.

This is a complete **specified finite experiment**, with complete structural
enumeration and pairwise latent-space coverage. It cannot enumerate an infinite
continuous operating space, all PA/side-draw location combinations, or every
feasible mixture. Two replicates in some cells cannot establish every nonlinear
interaction in 48 variables. Success regions, uncertainty and extrapolation must
therefore be measured after the experiment; completeness is not a model-coverage
guarantee. The method is a strength-two OA-LHS, not a definitive screening design
or an unqualified full factorial of all continuous variables. The construction
and its prime-square restriction follow the formal design described in the
[SciPy OA-LHS documentation](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.qmc.LatinHypercube.html).

## Mixture and process bounds

Mixture fractions are constrained jointly: `sum(z)=1` and
`0.8 z_i0 <= z_i <= 1.2 z_i0` for **every** component. The 20 latent coordinates
produce relative factors `r_i=0.8+0.4 u_i`. A common shift is found by bisection so
that `z_i=z_i0 clip(r_i-shift,0.8,1.2)` sums to one. All species follow the same
rule; a permutation of the component axis permutes the output. There are 19
independent physical composition degrees of freedom. No species is used as a
balancing remainder. This bounded-simplex transformation preserves the requested
final ±20% limits; simply renormalizing independent ±20% multipliers would not.

The physical mixture is not uniformly sampled on the bounded simplex, and its
fractions no longer have the latent OA marginal-stratification property. The
latent vector is saved for audit. Zeros and pure-component feeds lie outside this
relative ±20% campaign because all baseline fractions are positive. Constrained
mixture and process variables must be handled together rather than treating
fractions as independent unrestricted factors; see
[NIST's mixture-design guidance](https://www.itl.nist.gov/div898/handbook/pri/section5/pri54.htm)
and [bounded mixtures](https://www.itl.nist.gov/div898/handbook/pri/section5/pri554.htm).

| Process factor | Declared range or transformation |
| --- | --- |
| Total hydrocarbon feed | 80–120% of 737.6996333 mol/s; independent of mixture fractions |
| Feed temperature | 510.52–765.78 K (±20% of 638.15 K) |
| Condenser temperature | 298.15–398.58 K; ±20% of 332.15 K intersected with qualified hydrocarbon thermal-fit range |
| Organic reflux ratio | 0–10, including exact endpoints in the candidate latent design |
| Top pressure | 100–300 kPa |
| Tray pressure drop | `u * min(1000 Pa, (300000 Pa - Ptop)/(N-1))` |
| Steam rate when enabled | 266.6667–400 mol/s |
| Steam temperature | 426.52–639.78 K (±20%); injection at sump node N+1 |
| Feed location | Baseline normalized height `(37-1)/(40-1)` times 0.8–1.2, clipped to 0–1 and rounded to an available tray |
| Side-draw locations | Random distinct trays, conditional on count; rates stay attached to their respective sampled withdrawal |
| Side-draw rates | ±20% of 136.3889, 143.0556 and 45.8333 mol/s, respectively |
| PA locations | Random return and span on trays 1–N; duplicate pairs resampled without replacement; overlap and same-tray heat effects are supported |
| PA duties | ±20% of −12.84, −17.89 and −11.20 MW; fourth reference is their mean, −13.9767 MW |
| Reboiler duty | 0–14.75399 MW, defined below |

Percentage variations use absolute Kelvin, not degrees Celsius. Zero baseline
quantities need explicit nonzero scales: the pressure-drop reference is
5,000 Pa/tray, and the reboiler reference is baseline hydrocarbon flow times
100,000 J/mol, or 73.76996 MW. Their nonnegative variation is **0–20% of those
declared scales**. This is not represented as “±20% of zero.” All resolved node
pressures remain between 100 and 300 kPa; V3 uses `Ptop+(tray-1)*dp` and gives the
sump the final tray pressure. The conditional pressure transformation and
integer/location mappings change physical-space strata and are documented
instead of claiming that those transformed outputs remain an OA.

V3 PAs are prescribed distributed heat effects, not explicit circulating liquid
streams. The experiment varies the model's actual return/draw heat zones. Adding
a fourth PA extends that existing contract and does not add a new hydraulic model.

## Physical exclusion tool

`generalized_design.py screen` can screen any JSONL matrix using the same rules
as generation. Each excluded row retains its input, ID, split and a list of
`category`, `code`, `proof` and numerical `evidence`. Categories remain separate:

* `physical_necessity`: negative material supplies, nonpositive absolute states,
  side withdrawals exceeding total hydrocarbon feed, or a declared pure-water
  vapor source below its saturation temperature at injection pressure. The last
  check uses the same pinned IAPWS Wagner–Pruss correlation as `V3WaterProperties`.
* `model_contract`: topological and representational restrictions, including
  duplicate side draws or PA pairs, nonnegative pressure drop, the solver's 5 K
  steam-superheat margin and its steam/feed cap. These are not claims of general
  physical impossibility. With two trays, three distinct side-draw trays or four
  distinct PA pairs cannot be authored under this contract: only three PA pairs
  exist because same-tray heat zones are allowed.
* `property_domain` and `invalid_input`: property validity limits or malformed
  input. A property limit is not proof that a real plant cannot operate there.

Passing preflight means **no implemented necessary condition failed**. It does
not prove a feasible steady state. PA duty, condenser temperature, steam, feed
enthalpy and reflux interact through the full energy and phase balances. The
filter therefore never declares “impossible” from a temperature threshold,
cooling/feed ratio, failed Newton iteration, dew-point advisory or missing label.
The Java solver's native heat-admission estimates and continuation limitations
must be recorded separately as solver-admission outcomes, with their diagnostics.
The Python tool intentionally does not promote the feed-composition cold-liquid
estimate to a rigorous global bound over every separated product composition.

## Splits, labels and failure mapping

Entire tray counts are held out. Counts divisible by seven form test; counts
congruent to three modulo seven form validation; all remaining counts train.
All equipment cells and replicates of a held-out tray count remain in that split.
Consequently neighboring profiles, same-cell replicates and individual trays are
never split across fitting and evaluation. The exact count lists are saved in
`design.json`. This tests interpolation to unseen geometry sizes across the range;
it does not constitute an independent composition-family extrapolation test.

Teacher runs use `CURRENT_ONLY`. Preflight exclusions, native solver admission,
timeout, nonconvergence, accepted dry advisories and physically qualified final
states must have distinct outcomes. Nonconverged and advisory-only cases provide
no training labels. Every original failure remains in the case map and can later
be retried by the freshly trained initializer. Held-out outcomes cannot choose
feature normalization, weights, epochs, coverage limits or model routing. A
separate prior-failure retry set must retain its prior IDs and be audited against
training-input hashes.

## Reproduce and verify

The generator needs only Python's standard library. Use a fresh directory; it
refuses to overwrite a frozen matrix. It does not start solvers or choose worker
counts, which belong to the Java experiment runner.

```powershell
python tools/neural/generalized_design.py generate build/neural-generalized/design
python -m unittest discover -s tools/neural -p test_generalized_design.py
python tools/neural/generalized_design.py screen build/neural-generalized/design/candidate-matrix.jsonl build/neural-generalized/rescreened
```

The four outputs are `candidate-matrix.jsonl` (every candidate), `matrix.jsonl`
(preflight-admitted inputs for the runner), `exclusions.jsonl` (complete rejected
rows with evidence), and `design.json` (method, seed, bounds, baseline, SHA-256
hashes, split and complete-coverage counts). IDs are stable strings such as
`gd-s02-w0-p0-d0-r00`; they are not row numbers. All JSONL files use canonical
sorted-key JSON, LF line endings and UTF-8. The frozen files, not a fresh run with
another software version, are the exact replay authority.

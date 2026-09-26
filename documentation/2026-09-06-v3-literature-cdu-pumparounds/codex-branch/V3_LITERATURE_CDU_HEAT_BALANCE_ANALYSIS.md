# Native CDU reference: heat balance failure at lambda 0.40

Date: 2026-09-06. Branch `codex/v3-literature-cdu`. Read-only analysis of the committed evidence
(`output/cdu-reference/evidence/scaffold-*.json`, `attempt-summary.json`), the local raw run
(`output/cdu-reference/full-scaffold-corrected-heat/`), the harness (`scripts/dwsim/CduReference.cs`),
the installed-binary IL dump (`build/cdu-reference-probe/sumrates-installed-il.txt`), the column
source `RigorousColumn.vb` supplied by the user, and the upstream `SumRates.vb` head on GitHub.
No native run was executed and no binary was touched.

## Summary

- The −494.9 kW global energy defect of the lambda 0.40 candidate is a solver artifact, not an
  accounting error. The same accounting closes to 5e-5 kW at lambda 0.20 with −8.4 MW of
  prescribed cooling applied through the same sign translation.
- The installed Sum Rates (`BurninghamOttoMethod.Solve`, DWSIM.UnitOperations 10.2.3.0) does two
  things the upstream source does not: it caps every per-iteration temperature step at
  `max(0.5*span(T0), 5)` K and it clips every stage temperature into
  `[min(T0) − max(0.5*span(T0), 25), max(T0) + max(0.5*span(T0), 25)]`, where `T0` is the initial
  temperature array. Its exit test, shared with upstream, only measures temperature and
  composition change and never the energy residual.
- The lambda 0.20 solution, reused as `T0`, spans 454.29–483.11 K. Half-span is 14.41 K, so the
  step cap is 14.41 K and the lower bound is 429.286 K. In iteration 1, 36 of 41 stages took the
  full capped step; in iteration 2, 20 stages landed on the bound; from iteration 3 the pinned
  block never moved again. The solver declared convergence at iteration 56 on the free stages.
- The three cooled stages are pinned. Their residuals (−2186, −6096, −4414 kW) are the part of
  the prescribed duties (−5136, −7156, −4480 kW) the stages could not reject. Neighbouring pinned
  stages carry positive residuals from the coupled temperature update, netting −494.9 kW.
- Remedy: pass an initial temperature array whose envelope already spans the authored
  column (top 332.15 K, bottom 638.15 K). The bound then sits at 179 K and the step cap at
  153 K, and the constraint never binds. Adaptive lambda subdivision alone is marginal because
  the minimum temperature descends faster per unit lambda than the fixed 25 K margin allows.

## 1. The accounting is validated by the accepted rungs

The harness computes `input + heat − output` from native stream enthalpies with the consistent
caloric callback, and the stage audit recomputes every stage balance independently
(`StageAudit`, `Audit` in `CduReference.cs`).

| lambda | heat applied (kW) | global error (kW) | max stage error (kW) | T min (K) | T max (K) | span (K) | bound for next rung (K) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.00 | 0 | −1.4e-5 | 3.2e-5 | 500.00 | 500.00 | 0.0 | 475.0 |
| 0.02 | −838.6 | 6.8e-4 | 2.8e-3 | 495.45 | 497.69 | 2.2 | 470.4 |
| 0.05 | −2096.5 | 5.6e-4 | 1.5e-3 | 488.59 | 494.45 | 5.9 | 463.6 |
| 0.10 | −4193.0 | −1.6e-4 | 1.0e-4 | 477.07 | 489.65 | 12.6 | 452.1 |
| 0.20 | −8386.0 | −5.3e-5 | 3.3e-4 | 454.29 | 483.11 | 28.8 | **429.29** |
| 0.40 | −16772.0 | **−494.9** | **6096.2** | 429.29 (pinned) | 485.05 | 55.8 | n/a |

Rungs 0.02 to 0.20 are also rejected by the native post-check (a 1e-9 relative component
criterion) but pass the harness's fresh audits; the harness treats that post-check as documented
and separate. At 0.40 the native post-check again only reports a 7.9e-9 ethane mass error. Only
the independent energy audit caught the defect.

## 2. Where the defect sits in the rejected state

From `native-rejected-profile-summary.json` (stage residual = in + Q − out):

| Group | Stages | Sum of residuals (kW) |
|---|---|---:|
| Pinned at 429.2856560513721 K | 8, 9, 10, 16–34 (22 stages) | −3864.3 |
| Free | all others (19 stages) | +3369.4 |
| Column | 41 | **−494.9** |

| Sink stage | Applied duty (kW) | Residual (kW) | Duty not rejected |
|---:|---:|---:|---:|
| 8 | −5136 | −2185.7 | 43% |
| 16 | −7156 | −6096.2 | 85% |
| 26 | −4480 | −4414.4 | 99% |

Stage 26 sits inside a block of pinned stages at one temperature, so no enthalpy gradient can
carry its duty; the residual equals the duty. Stage 8 is fed by stage 7 at 433.1 K, so part of
the duty is absorbed as sensible heat. Positive residuals on stages 9, 10, 15, 17–24 and 31–35
(up to +3390 kW at stage 34) are the redistribution produced by the coupled tridiagonal
temperature update once the block cannot descend. Phases stay distinct with positive flows
(V min 374 mol/s, L min 709 mol/s), so this is not phase disappearance.

## 3. What the solver did, iteration by iteration

From the raw convergence report for the lambda 0.40 run (initial estimates = lambda 0.20 solution):

| Iteration | Stages taking the full −14.41 K step | Stages on the 429.286 K bound afterwards | Reported sum of squared temperature change |
|---:|---:|---:|---:|
| 1 | 36 of 41 | 0 | 7486 |
| 2 | 15 | 20 | 6461 |
| 3 | 0 | 28 | 508 |
| 13 | 0 | pinned block unchanged | 0.15 |
| 56 | 0 | 22 (final) | 5.4e-11 |

36 × 14.41² = 7475, which reproduces the reported 7486. In other words the unconstrained
temperature correction for almost the whole column exceeded the cap in two consecutive
iterations, i.e. the true lambda 0.40 solution is well below 429 K in the cooled sections.
Stages 37–41 (feed and steam section, anchored by the 638 K crude) moved by at most 2 K.
Later iterations only settle the 19 free stages; the pinned stages contribute exactly zero to
the temperature-change measure because the measure is taken after clipping.

## 4. Why: installed binary versus source

Installed `BurninghamOttoMethod.Solve` (IL offsets from the dump, argument 17 is the initial
temperature array):

| IL range | Behaviour |
|---|---|
| IL_0596–IL_0660 | From `T0`: relaxed pair `[min−8, max+8]`; non-relaxed pair `[min − max(0.5·span, 25), max + max(0.5·span, 25)]`, lower value floored at 100 K; step cap `max(0.5·span, 5)` stored in local 32 |
| IL_2aab–IL_2ad8 | `TDMASolve` on the energy balances gives the temperature perturbation vector |
| IL_2b37–IL_2b52 | Perturbation multiplied by 0.7 (non-relaxed) or 0.15 (relaxed) |
| IL_2bb9–IL_2bfb | Perturbation magnitude capped at local 32 (non-relaxed) or 0.02·T (relaxed), sign kept |
| IL_2c1e–IL_2cd4 | Updated temperature clipped to the pair selected by `RelaxTemperatureUpdates` |
| IL_339f–IL_33ca | `t_error += (Tj − Tj_ant)²` computed after cap and clip |
| IL_3cb7–IL_3cd9 | Exit when `t_error ≤ tol(1)` and `comperror ≤ tol(1)` and `ic > 10`; no energy residual term |

Upstream `SumRates.vb` (windows branch head, copyright header 2008–2022) has
`Tj(i) = Tj(i) + 0.7 * deltat(i)` with an optional damping-factor relaxation, no step cap, no
bounds, the same exit test, and `H(i) = ... − Q(i)` (positive Q removes heat, matching the
harness's Sum Rates sign translation). The cap and the bounds are therefore an addition in the
installed 10.2.3 build. The exit test never checked energy residuals in either version; without
the bounds a stagnating energy balance would at least keep the temperatures moving.

`RigorousColumn.vb` (user-supplied) shows the dispatch: for a "Rates" solver the column first
runs with `RelaxTemperatureUpdates = False`; only if the solver throws does it retry with
`RelaxTemperatureUpdates = True`, which uses the tighter ±8 K bounds, a 0.02·T step cap and a
0.15 multiplier. A clamped false convergence throws nothing, so the retry never runs, and it
would be more restrictive if it did.

## 5. Why the manufactured continuation walks into the bound

The initial temperatures for each rung come from the previous accepted solution:
`AutoUpdateInitialEstimates` copies `Tf`, `Vf`, `Lf`, `xf`, `yf` into `InitialEstimates` after
every valid solve, and `GetSolverInputData` passes `InitialEstimates.StageTemps` to the solver
whenever `UseTemperatureEstimates` is set and the values validate. The harness never sets
temperatures after the isothermal 500 K seed at lambda 0, so the envelope is always the previous
solution's envelope.

The scaffold blends the top feed from 500 K toward 332.15 K and the steam toward its expansion
temperature linearly in lambda, and scales crude flow, side draws and duties by lambda, while
the top-feed and steam flows are already at target. The column stays nearly isothermal, so
half-span stays below 25 K through lambda 0.20 and the margin is always the 25 K floor.
Meanwhile the minimum temperature descends 4.6, 6.9, 11.5 and 22.8 K over successive rungs
(delta lambda 0.02, 0.03, 0.05, 0.10): about −230 K per unit lambda at lambda 0.2 and
accelerating, with 122 K still to go before the authored 332.15 K top. A rung is only safe when
its descent stays under 25 K, so subdivision would need delta lambda of roughly 0.1 or less at
lambda 0.2 and smaller later, discovering each failure only through the energy audit after a
full 80-iteration solve. Subdivision is therefore a marginal remedy on its own.

## 6. Remedies, cheapest first

1. **Widen the initial temperature envelope (recommended).** The bound and the cap depend only on
   the minimum and maximum of the array handed to the solver. Keep the previous accepted profile
   for the interior and anchor the two ends at the authored boundary temperatures before each
   rung:

   ```csharp
   // ScaffoldRetry, before CalculateAllowingDocumentedPostcheck(m), when last != null
   var t = last.StageTemps.Select(p => p.Value).ToArray();
   t[0]  = Math.Min(t[0], 332.15);   // authored condenser temperature
   t[40] = Math.Max(t[40], 638.15);  // authored crude feed temperature
   m.Column.SetInitialTemperatureEstimates(t);
   ```

   With span 306 K the bound becomes [179 K, 791 K] and the step cap 153 K, so neither can bind.
   Two of 41 starting temperatures are perturbed (−100 K at the top, +156 K at the bottom); the
   interior starts on the accepted profile. `SetInitialTemperatureEstimates` only replaces the
   temperatures and sets `UseTemperatureEstimates`; the auto-updated flows and compositions
   remain in force.
2. **Detect pinning explicitly.** After every native solve, recompute the pair from the array that
   was passed (`lo = max(100, min − max(0.5·span, 25))`, `hi = max + max(0.5·span, 25)`) and reject
   the candidate if any `Tf(j)` lies within 1e-6 K of `lo` or `hi`, recording the count, the pair
   and the cap in the candidate JSON. This turns a −495 kW mystery into a typed rejection at
   iteration 56 instead of after an audit, and it protects any later replay.
3. **Keep the energy audit as the acceptance gate.** It is the only check that caught this
   state; the native post-check is a mass criterion and the exit test is a step-size criterion.
4. **Adaptive lambda subdivision** (0.20 → 0.30 → 0.40) can be layered on top of 1 for the
   ordinary nonlinearity of the path, but it does not remove the constraint and should not be
   the primary fix.
5. **Naphtali–Sandholm from the accepted lambda 0.20 state** is a fallback whose cost is the
   dense numerical Jacobian (about 150 k enthalpy calls per iteration, roughly 90 s each).
   Whether the installed NS also bounds temperatures has not been inspected.

A scaffold that does not fight its own envelope would also help: hold the top feed at the
authored 332.15 K from lambda 0 (its flow is already at target), or scale its flow with lambda
like the crude, so intermediate rungs resemble proportional columns instead of an isothermal
absorber that is cooled from inside. That is a design change and needs its own accepted rungs.

## 7. Physical sanity of a colder middle section

With vapour water mole fractions of 0.22–0.32 the water partial pressure is 55–80 kPa and the
tray water dew point about 357–367 K. A lambda 0.40 solution with its cooled sections at
400–420 K therefore condenses no water on trays; the aqueous phase stays a condenser-only
phenomenon at this rung. Near lambda 1 the top trays approach 360–380 K, so tray water in the
native model remains a comparison item against V3's W1 model, as the plan already records.
The manufactured profile is inverted at intermediate lambda (top feed at 433 K above a middle
that wants to be below 429 K); that is a property of the homotopy, not a physical CDU state.

## 8. Implications for V3

V3 cannot fail this way: its Newton certificate requires the scaled residual, including every
energy row, below 1e-8, and the acceptance auditor recomputes the stage energy balances. The
transferable lesson is the audit design: the new `V3GlobalEnergyAudit` gives the whole-column
check that identified this native defect, and it should be kept as an acceptance check rather
than a diagnostic once it is compiled and tested. When the native reference is finally qualified
against V3, stage energy residuals and the pinning check must be part of the comparison bundle.

## 9. Not done

No native run, no binary modification, no change to `CduReference.cs` or to the committed
evidence. The next controlled experiment is remedy 1 plus remedy 2 at lambda 0.40 from the
accepted 0.20 state, with the existing audits unchanged, before any reflux closure or
hydrocarbon-only draw adjustment work.

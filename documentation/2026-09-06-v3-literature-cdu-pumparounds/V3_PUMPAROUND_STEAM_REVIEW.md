# V3 pumparound x sump-steam integration review

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc`, base `a174831` (WP2).
Scope: the WP2 code paths where a prescribed stage heat and free-water sump steam interact, plus a wet
calculator test class. Worktree `.claude/worktrees/v3-low-pressure-gaps-989c00`.

## 1. Findings

| # | Path | Verdict | Action |
|---|---|---|---|
| 1 | `requireCoolingBelowBaseCondenserDuty` took `Q_cond0` from the **dry surrogate seed** | WRONG on a wet column | fixed: gate moved to the last accepted heat-free rung |
| 2 | `GLOBAL_ENERGY_BALANCE` ran only when `hasPumparounds()` | MISSING on wet columns | fixed: also runs when `hasSteamFeeds()` |
| 3 | Steam ramp count `min(12, ceil(rate/4))` contradicted its own "<= 4 mol/s per rung" comment | WRONG at high rate | fixed: cap raised to 24; comment made truthful |
| 4 | Heat-bearing draw ramp fixed at 4 rungs | TOO COARSE with heat | fixed: 8 rungs in the heat-bearing lane only |
| 5 | Static admission gate steam term | CORRECT as written | none |
| 6 | Surrogate reboiler duty during heat rungs | CORRECT (steam is at 1.0 before the first heat rung, so the surrogate is fully removed) | none |
| 7 | `V3ColumnDutyLedger` steam-in / free-water-out / slip-out terms | CORRECT | none (now covered) |
| 8 | `V3HeatFeasibility.condensationCapacityWatts` water term | CORRECT (water stays vapor, so only its sensible cooling counts) | none |
| 9 | `V3MeshResidualEvaluator.phaseEnergy` drops the water term when the hydrocarbon phase flow is zero | LATENT BUG, unreachable in the tested envelope | not changed; see section 4 |

### 1.1 Finding 1 in detail

`Q_cond0` is documented as "the condenser duty of the same column without stage heat". WP2 computed it from
`seedBase`, which is the **dry surrogate**: steam replaced by `totalSteam * hvap(450 K)` of extra reboiler duty,
no water at the condenser at all. On the 30-stage CDU17 column at 1200 kmol/h sump steam the two numbers are
59.4 MW (dry, 8 MW reboiler) versus 94.7 MW (wet). The gate therefore rejected admissible wet cooling duties
between those two values, as an `INFEASIBLE_SPECIFICATION` naming a duty the wet column does not have.

Fix: the gate is evaluated lazily inside the ramp loop, on the last accepted rung before the first rung that
carries any heat, and only if that rung is an accepted pass. For a steam-free input that rung is still
`seedBase`, so the dry behaviour and the existing dry test are byte-identical.

### 1.2 Finding 3 in detail (root cause of the zero-reboiler failure)

`withoutSteamWithSurrogateDuty` replaces the authored steam with `totalSteam * hvap(450 K)` of reboiler duty:
11.96 MW at 1200 kmol/h. The real wet sump does the opposite - the steam is heated from 533 K to about 620 K
and *absorbs* about 1 MW. The ramp therefore has to unwind roughly 13 MW of sump boilup, which is fine when the
authored reboiler duty is large and marginal when it is zero.

The rung size made it fail: `min(12, ...)` put 27.8 mol/s of water on the first rung, seven times the 4 mol/s
the comment claims. Measured on the 30-stage CDU17 column, 1200 kmol/h sump steam, **no pumparound at all**:

- 12 rungs, `Q_reb = 0`: first rung diverges (40 iterations, residual 9.96e-2), NONCONVERGENCE.
- 12 rungs, `Q_reb = 2 MW`: converges (22.1 s).
- 12 rungs, `Q_reb = 0`, half steam rate: converges (4.1 s).
- 24 rungs, `Q_reb = 0`: converges (8.5 s).

So the zero-reboiler failure was a pre-existing wet-lane defect, not a pumparound interaction. Only inputs
above 48 mol/s of steam change rung count; every existing wet test (8 and 22.2 mol/s) is unaffected.

## 2. Rejected change

Making `intermediateFailed` per-feature (a failed steam rung jumps only steam to 1.0 and keeps the heat and
draw ramps) is intuitively right but measured worse on TJL19: `tjl19 + steam + draws` went 23.0 s -> 51.9 s and
`tjl19 + steam + heat + draws` went 23.2 s -> 139.7 s, with the final residual moving from 3.8e-8 to 1.9e-4.
Reverted. The coarse jump-to-requested policy stays.

## 3. Measured envelope (30 stages, feed tray 24, 250 kPa, 750 Pa/stage, 332.15 K condenser, R = 4.17, 2610.7 kmol/h Tia Juana Light, 1200 kmol/h sump steam at 533.15 K)

| case | outcome | s | Newton | Q_cond |
|---|---|---|---|---|
| steam only (wet baseline) | SUCCESS | 9.0 | 3 | -94.74 MW |
| steam + uniform -5 MW trays 8-12 | SUCCESS | 10.4 | 4 | -89.91 MW |
| steam + uniform -5 MW, `Q_reb = 0` | SUCCESS | 10.7 | 3 | -89.03 MW |
| steam + 3 pumparounds (-15 MW) + 3 preset draws | SUCCESS | 15.0 | 8 | -75.04 MW |
| TJL19, steam + 3 pumparounds | SUCCESS | 12.3 | 10 | -98.22 MW |
| TJL19, steam + 3 pumparounds + 3 draws | NONCONVERGENCE (3.8e-8) | 21.1 | - | - |
| steam + cooling 1.02 x Q_cond0 | INFEASIBLE_SPECIFICATION | 9.0 | 0 | - |

The condenser branch is LIQUID_ONLY throughout; all 333.33 mol/s of water decants as free water.

## 4. Open items

- **Finding 9.** `phaseEnergy(state, node, false, ...)` returns 0 before adding the water term when the node's
  hydrocarbon vapor flow is zero. With `V3WaterCondenserRegime.ALL_VAPOR` on a `LIQUID_ONLY` condenser branch
  (reachable in principle when `Psat(T_cond) >= P_top`, i.e. a low-pressure wet column), the arriving water is
  published as an `overhead_vapor` steam product by `V3ColumnStreamProperties` but its enthalpy never leaves
  the condenser node, so the energy balance leaks `water * hv(T_0)`. Not reachable in any current test. Now
  guarded rather than silent: extending `GLOBAL_ENERGY_BALANCE` to wet problems makes such a state fail closed.
  Fixing it changes wet residuals and needs a `v3-wet-mesh-*` revision bump, so it was left out of this change.
  - **Resolution (2026-09-07, branch `claude/wet-energy-leak-fix`).** Reachability confirmed by construction:
    50 kPa overhead, 360 K condenser (Psat ~ 62 kPa), 4 mol/s sump steam, resolved on `LIQUID_ONLY` -> regime
    `ALL_VAPOR`, pure-water `overhead_vapor` product published. Two premises above were wrong: (a) the
    condenser vapor outlet enters no MESH energy row (tray-1 uses the condenser *liquid* only), so the wet
    residuals are unchanged by the fix and only the published condenser duty moves (it was overstated by
    `water * hv(T_0)`, -20.7 kW instead of -12.4 kW on the constructed case); (b) `GLOBAL_ENERGY_BALANCE` does
    **not** fail closed: the closure adds the condenser vapor outlet inside the condenser duty and subtracts it
    again as a product, so the term cancels and the check passes with closure 0.0 W before and after the fix.
    Fix: `phaseEnergy` now adds the water term when the hydrocarbon phase flow is zero; wet labels bumped
    `v3-wet-mesh-r6-steam` -> `r7` and `r7-steam...-stage-heat` -> `r8`; regression test
    `V3SteamFeedContractTest.lowPressureAllVaporWaterOnALiquidOnlyCondenserLeavesAsSteamProductEnthalpy`.
    Open follow-up: the whole-column closure needs an independent condenser-node term to guard this class of
    boundary error.
    - Follow-up DONE 2026-09-07: new `CONDENSER_ENERGY_BALANCE` audit family (independent condenser-node
      closure vs the published duty, fails on the pre-fix duty by 8352 W). Notes in
      `V3_CONDENSER_ENERGY_AUDIT_REVIEW.md`.
- **TJL19 wet + side draws.** Fails identically with and without pumparounds (steam rung 0.375 stalls at
  5.3e-2, then the requested rung stalls on a trace component with liquid flow 8e-64 mol/s). This is the known
  Phase W dry-tray/trace-component wall from `V3_FULL_CONVERGENCE_PLAN.md`; pinned in the suite as a typed
  NONCONVERGENCE contract test, not as a success.
- No DWSIM external reference for a wet pumparound case (WP5 still open).

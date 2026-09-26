# V3 condenser-node energy audit (follow-up to V3_PUMPAROUND_STEAM_REVIEW finding 9)

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc`, base `973fc0b` (the finding-9 fix).
Worktree used for the work: `.claude/worktrees/recursing-lamarr-6cb268` on `claude/wet-energy-leak-fix`,
fast-forwarded onto the handoff branch. Companion note: finding 9 resolution in
`V3_PUMPAROUND_STEAM_REVIEW.md` section 4.

## 1. Problem

`V3AcceptanceAuditor.globalEnergyBalance` computes

```
condenser = liquidEnergy[0] + vaporEnergy[0] + freeWater - vaporEnergy[1]
closure   = feed + reboiler + steam + stageHeat + condenser
            - distillate - vaporEnergy[0] - freeWater - sideDraws - bottoms
```

`vaporEnergy[0]` and `freeWater` appear once inside `condenser` with a plus sign and once as products with a
minus sign, so they cancel exactly. The check reduces to `feed + reboiler + steam + stageHeat + r*L0
- V1 - sideDraws - bottoms`, i.e. the sum of the tray and sump energy rows. It therefore guards the
stage-heat sign and the steam-in versus water-leaving-tray-one bookkeeping, but is algebraically blind
to any condenser boundary term. Measured on the finding-9 case (50 kPa, 360 K condenser, LIQUID_ONLY
branch, ALL_VAPOR regime, 4 mol/s sump steam, zero-enthalpy hydrocarbons): the check passed with closure
0.0 W both before and after the evaluator fix, while the published condenser duty moved from
-20705.7 W to -12353.3 W. The javadoc claimed the opposite ("a dropped or double-counted water boundary
term fails here rather than silently shifting the condenser duty"); that sentence was false for the
condenser side.

## 2. Change

New check family `CONDENSER_ENERGY_BALANCE`, emitted on every steam-bearing problem right after
`GLOBAL_ENERGY_BALANCE` (`V3AcceptanceAuditor.condenserEnergyBalance`, package-private static so a test
can substitute the published duty):

| Term | Source (none reuse the residual evaluator's condenser terms) |
|---|---|
| hydrocarbon liquid / vapor outlets | candidate flows -> normalized public composition -> `V3ThermoModel.molarEnthalpy` at the node T, P |
| water vapor outlet | auditor's own `independentCondenserWaterSplit` (ALL_VAPOR: all authored water; FREE_WATER: slip-capped on TWO_PHASE, zero on LIQUID_ONLY) x `V3WaterProperties.vaporMolarEnthalpy(T_0)` |
| free-water outlet | same split x `V3WaterProperties.liquidMolarEnthalpy(T_0)` |
| arriving vapor | tray-one hydrocarbon vapor via `molarEnthalpy` + authored steam total x `vaporMolarEnthalpy(T_1)` |

`expected = outlets - arriving`; the check compares `expected` with
`V3ColumnDutyLedger.condenserDutyWatts` (the number the result publishes). Limit
`max(1 W, 1e-6 x largest term)`, the same scale rule as the global closure.

`globalEnergyBalance`'s javadoc and the call-site comment now say what is actually guarded and point to
the new check. Existing helper methods (`independentCondenserWaterSplit`, `independentWaterSlipCoefficient`,
`condenserTemperatureKelvin`, `authoredWaterAtCondenser`) gained static overloads taking the problem; the
instance versions delegate, no behaviour change.

Independence caveat, stated plainly: `V3PengRobinsonThermo.molarEnthalpy` and the enthalpy inside
`fugacity(...)` share `phaseMolarEnthalpy`, so the check is independent in bookkeeping (which flows,
which water split, which temperatures, which sign) but not in the equation of state. That is the intended
scope: the finding-9 class of bug is a bookkeeping error.

## 3. Tests

- `V3SteamFeedContractTest.condenserEnergyBalanceRejectsAPublishedDutyThatDropsOrDoubleCountsTheSteamProduct`:
  on the finding-9 case the fixed ledger duty passes with value 0; the pre-fix duty (ledger minus the
  steam-product enthalpy, exactly what the early-return evaluator published) FAILS with value equal to
  `4 mol/s x hv(360 K)` = 8352.4 W against a limit of ~0.02 W; the double-counted duty fails symmetrically.
- `V3SteamFeedContractTest.lowPressureAllVaporWaterOnALiquidOnlyCondenserLeavesAsSteamProductEnthalpy`
  now also asserts the new family passes on the full audit.
- `V3CondenserPhaseAuditTest.independentlyRejectsAWetCondenserPartition...`: ALL_VAPOR on a TWO_PHASE drum
  (150 kPa, 400 K: Psat > P_top) passes the new check on the exact manufactured state. (While writing this it
  turned out that fixture was never a decanting drum; the earlier test name in that class is still accurate
  because it only exercises the phase split.)
- `V3CondenserPhaseAuditTest.condenserEnergyBalanceClosesADecantingDrumWithBothTheSlipAndTheFreeWaterBoot`:
  new FREE_WATER case at 600 kPa with 12 mol/s sump steam (slip cap ~6.9 mol/s, so ~5.1 mol/s decants; 450 K
  steam still superheated at 600 kPa). Passes and the detail string carries the expected free-water term.
  A first attempt at 2 MPa was rejected by the resolver because 450 K steam is below Tsat + 5 K there;
  the helper `wetProblem` gained a steam-rate overload for this.
- `V3PumparoundSteamCalculatorTest` (real TJL19/CDU17 wet solves through the calculator) still passes,
  which is the evidence that the new check does not reject genuine accepted wet states.

## 4. Verification

Targeted classes (`V3SteamFeedContractTest`, `V3CondenserPhaseAuditTest`, `V3PumparoundSteamCalculatorTest`)
green. Full suite result is recorded at the end of this file.

## 5. Not changed

- The global closure itself is left as the true whole-column balance; restructuring it to "not cancel"
  would only re-derive the condenser check inside it.
- The check is emitted only on steam-bearing problems. On a dry problem the condenser duty is a pure
  function of the evaluator terms and the check would degrade to a `molarEnthalpy` versus
  `fugacity().molarEnthalpy` consistency test, which is not its purpose and would couple every dry test
  stub to that consistency.
- `main` (2d0d1cf) still carries the finding-9 bug and none of this; it has neither the stage-heat work nor
  the wet global closure.

## 6. Result

Committed as `286a799` on `claude/wet-energy-leak-fix` and fast-forwarded onto
`claude/v3-literature-cdu-handoff-3179dc` (worktree B clean before and after). Full suite in worktree A:
`.\gradlew.bat --offline test --no-daemon` -> BUILD SUCCESSFUL in 4m 41s, 382 tests, 0 failures, 0 errors,
0 skipped (380 + the two new tests). Not pushed.

# Free water on trays: replace the dew-point verdict with a wet-tray water phase

Date: 2026-09-08. Branch `claude/v3-literature-cdu-handoff-3179dc` at `ce702bc`. Decision by the user: a tray
below the water dew point must not be a rejection; water is allowed to condense on the tray as a separate
free-water phase, flow down and re-evaporate where it is hotter. This supersedes the typed `WATER_DEW_POINT`
verdict of `9b2a599` and the halved top cooler of `ce702bc`.

## 1. How water is carried today

- Stripping steam is authored per stage (`V3SteamFeedSpec`, at most two feeds: sump and one tray).
- `V3ColumnProblem.waterVaporFlowMolPerSecond(node)` is a **known upward profile**: every mole of steam fed at
  or below a node passes it as vapour (`V3SteamFeeds.upwardVaporProfile`). Water is not an unknown anywhere in
  the column; it is a parameter of each node.
- Water enters the hydrocarbon model in two places: the equilibrium row gets a dilution term
  `ln(V_hc / (V_hc + W))` (`V3MeshResidualEvaluator.waterDilutionLogTerm`) and the vapour phase energy adds
  `W · h_v,water(T)` (`phaseEnergy`). Steam feed enthalpy enters the fed node's energy row.
- The condenser is the only place water can leave the vapour: `V3WaterCondenserRegime` (NONE / FREE_WATER /
  ALL_VAPOR) is chosen once at problem resolution from the arriving water and the drum pressure, and
  `waterCondenserSplit(state)` divides the arriving water between overhead vapour (slip) and a free-water product.
  Audits `FREE_WATER_SPLIT`, `CONDENSER_ENERGY_BALANCE`, `WATER_PROFILE`; ledger and stream properties publish the
  free-water product and its enthalpy.
- `WATER_DEW_POINT` audits every water-bearing node: `P_n · W_n / (V_hc,n + W_n) ≤ P_sat(T_n)`. Since `9b2a599`
  a converged column failing only this check returns the typed `WATER_DEW_POINT` failure with a message naming
  the tray.

## 2. Impact of a free-water phase on trays

Physically: on a tray whose temperature is below the water dew point of its vapour, water condenses until the
vapour is exactly saturated; the condensate is an immiscible aqueous liquid that leaves the tray downward with the
hydrocarbon liquid (it is not withdrawn with side products in this model), and on a hotter tray below it
re-evaporates into the vapour. The hydrocarbon VLE is unchanged apart from the dilution term now using the
actual water vapour on the tray.

Model consequences:

| Area | Today | With wet trays |
|---|---|---|
| Water vapour profile | parameter per node | state: `W_n = W_{n+1} + F_{n-1} + S_n − F_n` (steam fed at n, free water arriving from above, free water leaving) |
| New unknown | none | `F_n` free-water liquid leaving tray n, only on **wet** trays (`UnknownFamily.FREE_WATER_FLOW`) |
| New equation | none | water saturation on wet trays: `P_n · W_n / (V_hc,n + W_n) = P_sat(T_n)` in log form (`EquationFamily.WATER_SATURATION`) |
| Dry trays | all water passes | unchanged (`F_n = 0`, no row); their `W_n` still depends on upstream `F` |
| Energy row | vapour water enthalpy | plus free water in (`F_{n-1} · h_l,water(T_{n-1})`) and out (`F_n · h_l,water(T_n)`); condensation heat appears automatically |
| Condenser | arriving water = parameter | arriving water = `W_1(state)`; regime logic unchanged otherwise |
| Sump | steam in, all up | free water reaching the sump evaporates if the sump is above its dew point (it always is in a CDU); if not, it leaves with the bottoms and is reported |
| Regime | condenser only | per-tray wet/dry set, frozen per attempt, refreshed from the solved state like the truncation support |
| Audit | dew point ≤ 1 everywhere | dry trays: ratio ≤ 1; wet trays: `F_n > 0` and ratio = 1 to tolerance; plus a fresh water balance closure |
| Verdict | typed `WATER_DEW_POINT` | removed; a converged column with wet trays is a success carrying a wet-tray advisory |

Numerics: `F_n` is strictly positive on a wet tray by definition, so it can take the log-flow coordinate like a
component flow, scaled by the total steam fed. The saturation row couples `F_n` (through `W_n`), `T_n` and the
tray's hydrocarbon vapour flows, all local to node n and its neighbour, so the banded structure holds; the
stage block gains one unknown and one row on wet trays. The Jacobian assemblers take the unknown set from the
ledger and need only the new family in their per-node layout.

Regime refresh (the truncation-support pattern): the first attempt of every rung is solved with the wet set
derived from the seed (ratio > 1 → wet, seeded with `F_n` = water in excess of saturation); after the solve the
set is re-derived from the solved state (a wet tray whose `F_n` fell below a floor becomes dry; a dry tray whose
ratio exceeds 1 by more than a hysteresis becomes wet) and the solve repeats, at most three times, with the
same bounded-repeat rule as the support refresh. A refresh that only removes wet trays after a stall is not
repeated.

Expected effect on the literature column: with the published 12.84 MW top cooler the top two or three trays go
wet, a few tens of kmol/h of water condense there and re-evaporate around tray 4 to 6, the top temperatures rise
slightly, and the case becomes a success. Dry columns are untouched (no steam, no water rows). Wet columns whose
trays are all above the dew point produce the same water profile as today and should reproduce their results to
tolerance; their formulation labels bump anyway because the contract changed.

## 3. Work packages

| WP | Content | Gate |
|---|---|---|
| F1 | Revert the typed verdict: `V3SolverFailureCode.WATER_DEW_POINT` removed (and from `V3TruncationFallback`), the calculator's special case removed, the audit detail kept as an advisory-quality message; `V3LiteraturePresetTest` reshaped for F3 | suite green with the verdict test replaced |
| F2 | Wet-tray water phase: ledger families, coordinate map, evaluator (water profile from state, saturation row, energy terms), problem (per-attempt wet set, `waterVaporFlow(state, node)`), auditor (regime-consistent dew-point check, water balance, advisory listing wet trays and free-water flows), ledger/streams (free water in bottoms if any), calculator regime refresh in the attempt loop, block Jacobian layout, seed from excess water, labels bump | unit tests on a hand-built wet tray; wet regression cases (steam tests) unchanged to tolerance; literature column with the **published 12.84 MW** top cooler converges as SUCCESS with wet top trays and a closed water balance |
| F3 | Preset back to 12.84 MW; `V3LiteraturePresetTest` asserts success, the wet-tray advisory, water balance closure and that the top trays' water condensation is reported; GUI Convergence page shows the wet-tray advisory line if there is a one-line hook (optional, in-game check required if touched) | suite green; report the literature profile against thesis Table 1.1 |

Out of scope: water in side products, water solubility in hydrocarbon liquid, three-phase VLE (the aqueous phase
is pure water with unit activity), free water on the feed tray from the crude (none authored).

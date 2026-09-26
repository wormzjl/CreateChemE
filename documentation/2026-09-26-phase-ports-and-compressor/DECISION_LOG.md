# Decision log: phase ports and compressor (batch 2026-09-26-phase-ports-and-compressor)

Plan: `PHASE_PORTS_PLAN.md` in this folder. Entries are dated; a later entry supersedes an earlier one only by saying so.

## Owner decisions (2026-09-26)

- **D1 Liquid-only pump.** The centrifugal pump is a placeholder that handles liquid only, possibly with some solids. A pump whose inlet is gas plus liquid (two-phase) or gas gives an error.
  - Behaviour proposed to the owner, pending O1: the pump goes to a CLOSED mode with a typed reason shown in the GUI, and the island keeps running. It is not an island hold, because a hold freezes every other line on the island.
- **D2 Gas compressor.** Add a compressor for gas: gas-only inlet, with the same error rule for a liquid or two-phase inlet.
- **D3 Tank outlets with a specified phase.** Top output = gas, middle output = mixed (today's bulk withdrawal), bottom output = liquid. Inputs are not distinguished.
- **D4 Suction density only.** Pump calculations and their verification use the input (suction) density only; the in/out density difference is small and immaterial.
  - The rise limit keeps its suction-density scaling, `riseLimit = maximumAddedPressure * rho_suction / pumpReferenceDensity` (`PassiveStepSolver.java:1051` at 9674bf1).
- **D5 One-slice delay accepted.** Events may be seen one 5 s slice late. The fluid engine is moving to a backward-Euler basis with 5 s slices (a separate plan covers that). Nothing may be designed that needs sub-slice event timing.

## Open decision points for the owner (recommendations in the plan, section 9)

| # | Question | Recommended | Alternative |
|---|---|---|---|
| O1 | Wrong inlet phase on a pump or compressor | CLOSED with a typed reason (`INLET_WRONG_PHASE`), island keeps running, decided per slice with hysteresis | Typed island hold |
| O2 | Level head `rho_l g level` at a bottom outlet in v1 | No (needs a tank height the model lacks; at most about 10 kPa in a one-block tank) | Yes, with a per-tank height setting |
| O3 | Compressor limit form | Pressure ratio `P_out/P_in <= r_max` | Fixed added pressure |
| O4 | Compressor shaft work | Booked as heat into the discharge (ideal isothermal work on suction properties over efficiency) | Ignored ("ideally intercooled") |

## Defaults taken by the plan unless the owner objects (reversible)

- **A1 Faces.** On the single-block tank: UP face = top (gas), DOWN face = bottom (liquid), horizontal faces = middle (mixed). No per-face configuration in v1.
- **A2 Bottom outlet contents.** The bottom outlet draws hydrocarbon liquid, free water and mobile solids together. No water/oil decant.
- **A3 What the inlet check reads.** The source stream the suction line delivers, not the pump's own inlet junction. There is no cavitation error; flashing at the inlet only lowers the suction density and so the rise limit.
- **A4 Thresholds** (plan Appendix B):
  - ports open at 1 % of the vessel volume, and the throttle lands them at 0.5 %;
  - the pump refuses above 2 % vapour by volume and resumes below 0.5 %;
  - the compressor refuses above 1 % condensed by mass and resumes below 0.2 %.
- **A5 When the inlet decision is taken.** Wrong-phase at each slice start on the committed state, with hysteresis carried in the committed endpoint mode. Empty suction at each step start, following the port.
- **A6 What stays bulk.** Module withdrawals and generator supplies stay bulk.
- **A7 Compressor and solids.** The compressor refuses any solid population above the trace volume fraction.

## Owner answers to the open points (2026-09-26, later the same day)

- **O1, O3, O4: recommended options accepted.** `INLET_WRONG_PHASE` CLOSED with a typed reason and per-slice hysteresis (O1); compressor limit as a pressure ratio (O3); compressor shaft work booked as heat into the discharge, ideal isothermal work on suction properties (O4). These are now decisions D6, D7, D8 of this batch.
- **O2: not decided.** The owner asks for an evaluation of the cost of adding an actual hydraulic (level) head to the current system before deciding. Evaluation written to `LEVEL_HEAD_REVIEW.md` in this folder; the plan's WP1 proceeds with the port model, which the level head extends without rework (the head enters the port's driving pressure only).
- **O2 evaluation done (2026-09-26, `LEVEL_HEAD_REVIEW.md`).** Head = g x condensed mass x H / V, linear in the phase amounts, zero with no liquid; no new unknown, row or nonzero (edge rows already span both end nodes and the FD Jacobian re-evaluates them); about 0-2 % wall on islands with liquid at a bottom port, bitwise elsewhere; never evaluated in replay; does not enter the 5 % step controller or the availability band. Options: A none (0 runs), B bottom port, fixed 1 m (1-1.25 runs, 3 Elevated assertions + 6 new tests), C per-tank height setting (2.5-3 runs, format + edit-path tests), D per-side-port height (4-5 runs, flat islands lose bitwise). Recommended B as a small package after phase-port WP2. **Pending the owner.** Plan amended: driving pressure applies in both directions (3.1), one per-end driving-pressure helper in WP1, checkpoint format 5 -> 6.
- **O2 decided (2026-09-26): option B.** Level head at the bottom port only, fixed 1 m height, no setting; implemented as a small work package after phase-port WP2 (plan amendment: the per-end driving-pressure helper of WP1 carries the offset). Decision D9 of this batch.
- **D14 of the mixed-gas batch (owner 2026-09-26):** the 100 m/s velocity cap stays as it is for every phase (it prevents supersonic flow); no liquid velocity setting.
- **WP0 done 2026-09-26 (Appendix C of `PHASE_PORTS_PLAN.md`, basis c32acac).** 15 unit tests become compressor tests, 1 a refusal test, 3 re-baselines await the owner (two crude pump starts, the wet-crude fallback pump case), and the D9 level head moves 4 assertions (the 3 of `LEVEL_HEAD_REVIEW.md` plus `DeadHeadedLineIslandTest` :234).

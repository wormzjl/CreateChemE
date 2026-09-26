# Where the two hard cases fail: tray balances of the 0.40× draw wall and the 40 MW cooler

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc` at `faefa0a` (per-phase support, sweep
reinsertion, band relaxation and the closure knob all in). Analysis only; no production code changed.

Probes (all under `build/pkgcmp/`, compiled with JDK 21 against a pristine copy of the HEAD science sources in
`classes-head2`, and against the `b42d85a` sources in `classes-old`):

- `TrayBalanceProbe2.java`: runs the continuation by reflection, prints per node T, L, V, withdrawal fraction,
  stage duty, phase enthalpies, energy residual (physical and scaled), worst material and equilibrium row,
  per-phase support counts, and the twelve largest scaled residual rows. Arguments `W<scale>`, `D`,
  `P<scale>:<MW>` (draw case plus one pumparound 21→18). Logs `tray-balance2.log`, `tray-balance-pa.log`.
- `TrayBalanceProbeOld.java`: the same profile from the pre-phase-mask solver for the 40 MW case and its
  heat-free base. Log `tray-balance-old.log`.

## 1. Case W: three draws at 0.40× the literature rates, 150 kPa

Input: CDU17 package, 30 stages, feed tray 24 at 638 K, condenser 400 K, reflux ratio 2.0, reboiler 8 MW,
draws on trays 8 / 15 / 22 at 0.40 × (496 / 653 / 149) kmol/h = 55.1 / 72.6 / 16.6 mol/s. No pumparound.
Terminal state: draw ramp reached the requested input, 32 iterations, scaled residual 0.079.

### Energy is closed, mass is not

| Family | Largest scaled residual | Where |
|---|---|---|
| Energy balance | 2.2e-3 (−159 kW) | tray 16, just below draw 15; next 23 (−141 kW) and 9 (−38 kW) |
| Component material balance | 7.9e-2 | tray 23, PC11; then tray 16 PC08 (7.1e-2), tray 9 PC07 (6.2e-2), tray 16 PC09, tray 23 PC10 |
| Equilibrium | 2.4e-2 | tray 23, every component, the same value |

Every failing material row is the heaviest cut that still carries flow on the tray directly below a draw.
The equilibrium rows of tray 23 are off by the same 2.4% for all fifteen components: that is a bubble-point
mismatch of about one kelvin, which Newton would remove in one step on a normal tray.

### The liquid profile is the cause

| Tray | 0.25× (converged, 4 it) | 0.40× (failed iterate) |
|---|---|---|
| 7 | 421 mol/s | 394 |
| 8 (draw) | 419, withdrawal 0.082 | 367, withdrawal 0.150 |
| 9 | 380 | 288 |
| 15 (draw) | 270, withdrawal 0.168 | 162, withdrawal 0.448 |
| 16 | 210 | 67 |
| 22 (draw) | 130, withdrawal 0.080 | 22, withdrawal 0.751 |
| 23 | 62 | **0.8** |
| 24 (feed) | 210 | 167 |

With reflux ratio 2.0 the condenser returns 451 mol/s to tray 1. That liquid evaporates as it descends into
hotter trays and nothing below the condenser condenses vapour to replace it, so the internal liquid falls to
62 mol/s above the feed even at 0.25×. Raising the draws from 90 to 144 mol/s takes essentially all of it:
tray 23 is left with 0.8 mol/s spread over fifteen components. On that tray the liquid composition is a ratio
of near-zeros, the material rows of the heavy cuts are scaled by throughputs of a few mol/s, and the bubble
point cannot be met together with the balances. The Newton line search then crawls (0.131 → 0.079 in 32
iterations) exactly as the earlier "dry tray below a heavy draw" note described. The per-phase floor does not
touch it: 0.05 mol/s per component is far above 1e-10 of the feed, so all fifteen points stay BOTH.

### It is a liquid-supply wall, not a solver wall

Adding one pumparound cooler above the starving tray, 8 MW spread over trays 18 to 21, and nothing else:

| | 0.40× without pumparound | 0.40× with 8 MW pumparound 21→18 |
|---|---|---|
| Outcome | NONCONVERGENCE, 32 it, 0.079 | **SUCCESS, 3 iterations, 2e-14** |
| Tray 22 withdrawal | 0.751 | 0.302 |
| Tray 23 liquid | 0.8 mol/s | 11.8 mol/s |
| Tray 15 withdrawal | 0.448 | 0.578 |

The pumparound condenses about 250 mol/s of vapour into the section that feeds draw 22, and the case that
"could not converge" converges in three iterations.

Pushing to 0.50× with a 12 MW pumparound in the same place fails again, and the failure moves up: the
`SIDE_DRAW_SPLIT` audit reports withdrawal 1.02 on tray 15 (90.7 mol/s requested against 88.7 available),
trays 16 and 17 run completely dry, and the pumparound below tray 15 cannot help a draw above it. That is
true starvation of draw 15, and the remedy is the same: liquid for draw 15 has to be generated above it, i.e.
a pumparound between draws 8 and 15, which is where the literature columns put one.

Conclusion: the draw-rate wall recorded in `V3_CDU_CONVERGENCE_RISK.md` is the internal-reflux mass balance
of a draws-only column at reflux ratio 2.0. Literature draw rates need the literature pumparounds. With the
Heat tab in place the right benchmark is the full configuration, draws plus interleaved pumparounds, not the
draws alone.

What remains a solver item: the model insists every tray keeps a liquid phase (`restoreEmptiedPhases`, node-level
`hasLiquidPhase`). A genuinely dry tray such as 23 at 0.40× or 16/17 at 0.50× would be represented cleanly by a
node-level VAPOR_ONLY branch (vapour pass-through, temperature from the energy balance), which the per-phase
mask now makes a small step rather than a formulation rewrite. It is only worth doing if dry trays are wanted
as a legitimate regime; with pumparounds they do not arise.

## 2. Case D: 40 MW cooler on tray 8, return-tray split

Input: CDU17 package, 30 stages, feed tray 24 at 638 K, condenser 332 K (liquid-only branch), reflux ratio
4.17, reboiler 8 MW, pumparound 12→8 with the whole −40 MW on tray 8. Terminal state: heat ramp at 1.0 after
stalls at 0.5 and three times at 0.375, 16 iterations, scaled residual 0.0117.

### The failure is global, not local

| Family | Largest scaled residual | Pattern |
|---|---|---|
| Energy balance | 1.17e-2 (−847 kW) on tray 17 | **−819 kW on every tray 1 to 24**, −650 to −740 kW on trays 25 to 31 |
| Component material balance | 3.0e-3, PC03 | the same value on every tray 0 to 24 |
| Equilibrium | 9.6e-4 | tray 31 |

The reported "dominant residual on node 17" is just the maximum of a flat profile. The sum of the tray energy
deficits is 24.6 MW, and the fresh boundary closure audit reports the same number: the iterate condenses 45.8 MW
in the condenser and 40 MW on tray 8 while the column only has 68.7 MW coming in (feed 60.7 + reboiler 8). It
has absorbed about 16 of the cooler's 40 MW and is stuck partway.

### What the converged solution looks like

The `b42d85a` solver still converges this case (7 iterations, 3.6e-14, via the condenser phase correction):

| | Base, no cooler | 40 MW cooler, converged (b42d85a) | 40 MW, failed iterate (HEAD) |
|---|---|---|---|
| Condenser duty | 59.4 MW | 30.1 MW | 45.8 MW |
| Distillate liquid | 288 mol/s | 187 | 248 |
| Tray 1 temperature | 401 K | 367 K | 385 K |
| Feed tray temperature | 546 K | 490 K | 520 K |
| Bottoms temperature | 573 K | 513 K | 557 K |
| Liquid below tray 8 | 1337 mol/s | 2000 to 2037 | 2086 to 2207 |
| Vapour below tray 8 | 1620 mol/s | 2190 to 2233 | 2389 to 2459 |

The true solution runs the whole column about 60 K colder, halves the condenser duty and shrinks the
distillate by a third; the cooler's condensate (about 1250 mol/s) circulates down to tray 23 and is
re-evaporated by the feed vapour in a nearly isothermal section at 414 to 431 K. The failed iterate has the
internal recycle but not the temperature drop. Shifting all tray temperatures together changes every energy
row by the same amount while barely moving the material and equilibrium rows, which is why the deficit is
uniform and why Newton advances so slowly: it is a long, flat valley in the merit, and the line search takes
tiny steps along it (0.0121 → 0.0117 in 16 iterations).

### Why the ramp loses it now

The heat ramp seeds rung 1.0 from the last stalled state at 0.375. On `b42d85a` that state happened to sit in
the basin of the cold solution; under the per-phase mask it starts three retained points further along and
does not. Nothing about the phase mask is wrong here (the earlier review excluded four candidate mechanisms
by experiment); the ramp is fragile at this rung because the solution branch moves a long way in temperature
between 0.375 and 1.0 of the duty.

### What would fix it

A heat-rung predictor rather than a tolerance or support change: before Newton on a heat rung, shift every
tray temperature by the enthalpy-consistent amount, ΔT ≈ −ΔQ / Σ(L·Cp_L + V·Cp_V) over the trays between the
cooler and the feed, or equivalently take one Newton step on the energy rows alone with the flows frozen.
That puts the seed into the cold basin directly and removes the dependence on which stalled state the ramp
happens to leave. Finer subdivision between 0.375 and 1.0 would also work but costs more.

## 3. Summary

| Case | Where it fails | Kind | Remedy |
|---|---|---|---|
| Draws at 0.40× | Tray 23 dry (0.8 mol/s), material rows of the heaviest cuts below each draw, uniform 2.4% bubble-point miss on tray 23 | Mass balance: draws exhaust the internal reflux of a draws-only column | Run literature draws with their pumparounds (0.40× converges in 3 iterations with one 8 MW cooler); optional node-level dry-tray branch |
| 40 MW cooler | Every tray, uniform −0.82 MW energy deficit summing to the 24 MW not yet absorbed | Ramp seeding: the solution branch moves 60 K between heat rungs 0.375 and 1.0 | Enthalpy-consistent temperature shift predictor on heat rungs |

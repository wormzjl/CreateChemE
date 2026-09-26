# Pumparound arrangement and draw ratios from the Manchester thesis, applied to the two hard cases

Date: 2026-09-07. Branch `claude/v3-literature-cdu-handoff-3179dc` at `faefa0a`. Analysis and probes only; no
production code changed. Source: Ledezma-Martínez, *Design of Crude Oil Distillation Systems with Preflash
Units*, PhD thesis, University of Manchester, 2019 (`documentation/FULL_TEXT.pdf` in the main checkout; text
extracted to `build/pkgcmp/thesis.txt` with `build/pkgcmp/pdfjs/extract.mjs`). Base case is Watkins (1979) via
Chen (2008), the same lineage as Table 1.1 and Appendix A.

## 1. What the thesis says

**Arrangement** (p. 30, section 1.2): the column has "three pump-arounds that pull a certain amount of liquid
on a tray, cool it down by heat recovery and then return it to the column two or four stages above the
withdrawal. Pump-arounds provide local reflux to the crude distillation unit, which does have a huge impact on
the separation quality."

**Where** (Table 1.1 stage temperatures, Table A4 sections, Table A6 pumparound inlet temperatures):

| Section | Stages | Temperature top → bottom, °C | Pumparound | Inlet → outlet, °C | ΔT, °C | Duty, MW |
|---|---|---|---|---|---|---|
| 1 | 1 to 9 | 93.7 → 146.5 | | | | |
| 2 | 10 to 17 | 147.4 → 227.5 | PA1 drawn at stage 10 | 147.4 → 117.4 | 30 | 12.84 |
| 3 | 18 to 27 | 238.6 → 304.9 | PA2 drawn at stage 18 | 238.6 → 188.6 | 50 | 17.89 |
| 4 | 28 to 36 | 310.9 → 341.3 | PA3 drawn at stage 28 | 310.9 → 290.9 | 20 | 11.20 |
| 5 | 37 to 41 | 341.3 → 335.1 | feed at 37, steam at 41 | | | |

The pumparound inlet temperatures are exactly the temperatures of stages 10, 18 and 28, which are also the
side-product draw stages of the reconstruction contract (`tjl19-literature-cdu-v1.json`). Each pumparound is
therefore drawn from the product draw stage and returned two stages above it (8, 16, 26), where the
reconstruction already places the duties. The cooled return condenses vapour on the two or three trays above
the draw, and that condensate is what the draw below withdraws. Three coolers of 11 to 18 MW each, 41.93 MW in
total, spread over three sections; the condenser removes 56.2 MW.

**Product slate** (Table A5, 100,000 bbl/day):

| Product | m³/h | % of feed volume | kmol/h | % of product moles |
|---|---|---|---|---|
| Light naphtha (overhead) | 102 | 15.4 | 833 | 32.4 |
| Heavy naphtha (draw, stage 10) | 87 | 13.1 | 491 | 19.1 |
| Light distillate (draw, stage 18) | 128 | 19.3 | 515 | 20.0 |
| Heavy distillate (draw, stage 28) | 54 | 8.2 | 165 | 6.4 |
| Residue | 292 | 44.1 | 565 | 22.0 |
| **Side draws together** | 269 | **40.6** | 1171 | **45.6** |

Operating variables: reflux ratio 4.17, condenser 59 °C, column inlet 365 °C, main stripping steam
1,200 kmol/h, uniform 2.5 bar, no external reboiler.

## 2. Case 1, the draw wall, against the thesis

The wall sweep (`V3_CDU_CONVERGENCE_RISK.md`) scales draws of 496 / 653 / 149 kmol/h on the 30-tray CDU17
column at reflux ratio 2.0 and a 400 K condenser. At 1.0× the side draws are 1,298 kmol/h, **49.7% of the feed
moles**, already above the thesis's 45.6% and its 40.6% by volume. Worse, that column's condenser at 400 K
with reflux 2.0 produces an overhead of about 59% of the feed (431 mol/s of 725 at the converged 0.25× state),
so at 1.0× the requested overhead plus side draws exceed the feed. The 1.0× case is not a valid specification,
and 0.75× (37% draws plus 59% overhead) is already at the boundary. The tray balances in
`V3_WALL_AND_40MW_BALANCE_ANALYSIS.md` show exactly that: tray 23 dries out.

With the thesis arrangement, one pumparound per draw, drawn at the draw stage and returned two stages above,
duties scaled with the draws:

| Draw scale | Pumparounds | Outcome | Notes |
|---|---|---|---|
| 0.40× | none | NONCONVERGENCE 0.079 | tray 23 liquid 0.8 mol/s |
| 0.40× | one 8 MW cooler 21→18 | SUCCESS, 3 it | tray 23 liquid 11.8 mol/s |
| 0.40× | thesis arrangement at 0.4 × duties (5.1 / 7.2 / 4.5 MW) | **SUCCESS, 3 it**, 12.1 s | tray 23 liquid 57 mol/s, withdrawals 0.18 / 0.28 / 0.13 |
| 0.75× | thesis arrangement at 0.75 × duties | NONCONVERGENCE | heat ramp stalls at 0.28 to 0.5 of duty, then tray 23 dry |
| 1.0× | thesis arrangement at full duties | NONCONVERGENCE | heat ramp stalls at 0.25 of duty; invalid specification anyway |

At 0.40× the arrangement works and restores healthy internal liquid everywhere. At 0.75× and 1.0× the
failure is no longer the draws but the heat ramp, which cannot get the 31 to 42 MW of cooling onto a column
whose condenser at 400 K and reflux 2.0 has nowhere near the thesis's internal traffic. Those two rows are not
thesis-consistent inputs and should not be used as benchmarks.

## 3. Case 2, the single 40 MW cooler, against the thesis

The thesis never puts 40 MW on one tray; it distributes 41.9 MW over three coolers in three sections, each
spanning two to four trays. Replacing the single 40 MW return-tray cooler with the thesis arrangement on the
CDU17 30-tray column at the thesis operating point (reflux 4.17, condenser 332 K, reboiler 8 MW):

| Variant | Outcome | Time |
|---|---|---|
| Single 40 MW cooler on tray 8 (old test case) | NONCONVERGENCE | 8.6 s |
| Thesis arrangement, full duties (6←8 12.84, 13←15 17.89, 20←22 11.20 MW), no draws | NONCONVERGENCE, heat ramp stalls at 0.34 to 0.5 | 11.0 s |
| Same + preset draws 8 / 15 / 22 | **SUCCESS**, 7 it, 3.2e-14 | 10.9 s |
| Same + preset draws + 1,200 kmol/h sump steam | **SUCCESS**, 1 it, 6.3e-14 | 15.9 s |

With draws the distributed arrangement converges at the full 41.9 MW. Without draws it fails at the heat ramp:
the draws remove hot liquid and shrink the internal recycle the coolers would otherwise have to re-evaporate.
Every one of these ramps still stalls at 0.34 to 0.5 of the duty on the way and only succeeds after
subdivision or at the final jump, which is the energy-shift valley described in the balance analysis and the
reason a heat-rung temperature predictor is still worth having.

`V3PumparoundCalculatorTest.aLargeSingleTrayDutyStaysWithinTheTypedFailureContract` should be replaced by the
thesis-arranged three-cooler case with draws as the large-duty qualification.

## 4. The literature column itself

Running the reconstruction contract on the current solver (TJL19 package, 40 trays plus bottom node, feed 37,
draws 10 / 18 / 28 at 136.4 / 143.1 / 45.8 mol/s, coolers 8←10, 16←18, 26←28 at 12.84 / 17.89 / 11.20 MW,
1,200 kmol/h steam at node 41, no reboiler, condenser 332.15 K, reflux 4.17):

| Variant | Outcome | Time |
|---|---|---|
| Plain column, 30 trays, reboiler 8 MW | SUCCESS | 3.8 s |
| Plain column, 40 trays, reboiler 8 MW | NONCONVERGENCE (base grid) | 287 s |
| Same with 750 Pa/stage drop | NONCONVERGENCE | 564 s |
| Any 40-tray variant with steam, draws or coolers | NONCONVERGENCE (base grid) | 250 to 270 s |

Every 40-tray case fails before any feature is applied, at the continuation's last grid: the schedule is
4-8-15-N, and the 15 → 40 jump hands Newton a seed whose energy rows are all off by the same 285 kW; the first
Newton direction is rejected by the line search at iteration 0. The same seed with one more grid:

| Schedule | Plain 40 trays | Full literature case |
|---|---|---|
| 4-8-15-40 (production) | fails at iteration 0 | fails at the base grid |
| 4-8-15-30-40 (probe copy) | **SUCCESS 5.4 s** | passes the base grid; steam ramp stalls at 5/12 of the steam, final attempt fails `WATER_DEW_POINT` 1.62 |

So the literature geometry needs a continuation schedule fix first (add a 30-stage grid, or double from 15
until the request). After that two further items stand between it and a benchmark: the steam ramp stalls with
the same uniform energy signature as the heat ramps, and the final iterate has water condensing on a tray near
the top (dew-point ratio 1.62), which is outside V3's contract. The thesis's top tray is at 93.7 °C with a
59 °C condenser and 1,200 kmol/h of steam; whether V3's converged column keeps its top trays above the water
dew point is open until it converges.

## 5. Follow-ups in order

1. Continuation schedule: 4-8-15-30-N for N > 30 (one line plus a 40-tray test); measured to unblock the
   literature base column.
2. Heat/steam-rung predictor: enthalpy-consistent temperature shift before Newton on a heat or steam rung
   (see the balance analysis); removes the repeated 0.34 to 0.5 stalls in every cooler case and the 40 MW
   regression.
3. Replace the single-tray 40 MW test with the thesis-arranged three-cooler case with draws.
4. Retire the 0.75× and 1.0× rows of the wall sweep as invalid specifications; keep 0.25× as the draws-only
   qualification and add the 0.40× thesis-arranged case as the draws-plus-coolers qualification.
5. Then run the literature column and resolve the water dew-point question on its converged state.

Probes: `build/pkgcmp/TrayBalanceProbe2.java` (`T<scale>:<dutyFactor>`, `L`, `Lplain`, `P<scale>:<MW>`),
`build/pkgcmp/LitVariants.java` (public-API sweep), variant calculator with the extra grid in
`build/pkgcmp/src-sched`, compiled to `build/pkgcmp/classes-sched`. Logs: `tray-balance-thesis.log`,
`tray-balance-literature.log`, `lit-variants.log`.

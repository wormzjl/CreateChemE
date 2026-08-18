# Atmospheric crude-distillation benchmark

**Target:** Minecraft 1.21.1, NeoForge<br>
**Purpose:** compare the proposed scientific model with a published crude-distillation case and bound its CPU cost<br>
**Status:** benchmark design and property-kernel measurement, updated 2026-08-19

The first executable implementation is the click-triggered [Milestone-1 crude-distillation calculator](./MILESTONE_1_CRUDE_DISTILLATION_POC.md). It is intentionally simpler than the later reduced, continuously operating gameplay column evaluated in this report; both are compared with the source model below.

## 1. Result in brief

The current scientific basis contains the property and equipment building blocks needed to attempt a convincing atmospheric crude-distillation unit. Milestone 1 first uses one bare main column with a player-selected theoretical-stage count and direct liquid side draws, calculated only on button press. The later continuously operating reduced-order target remains four equivalent section modules represented by 10–16 **virtual equilibrium cells in total**, with 10–12 petroleum pseudocomponents and lumped side-stripper behaviour. Neither topology has yet been implemented, and the reduced cell count remains an unvalidated gameplay design target rather than a demonstrated fidelity threshold. The actual cell balances/convergence method, per-cut property regression, tray-efficiency and hydraulic limits, and full-column validation still have to be implemented.

A twelve-cut sharp-boundary reconstruction of the selected literature case, fitted to its five product flowrates, gives an 11.0 °C mean absolute error over the reported product `T5/T50/T95` points and a 37.2 °C worst error. This shows what the custom twelve-interval display can reconstruct after fitting; it does not establish PR-component resolution or validate operating-response predictions because the product yields are calibration inputs.

A standalone Java 21 microbenchmark measured the staged Peng–Robinson flash/fugacity **kernel**, not a converged column solver. On the test Ryzen 7 9700X, its first timing pass was sub-millisecond for 10–16 cells and about 7 ms for a deliberately large 61-stage case. A provisional 10-times planning hypothesis suggests that a reduced column is likely compatible with asynchronous updates every 1–5 seconds, but the full equilibrium-cell/recycle implementation could invalidate that multiplier. Replace it with measurements of the real solver before making a capacity promise.

## 2. Selected literature case

The primary regression target is the **base/existing unit** in Case Study 6.1 of Lu Chen's University of Manchester thesis, [*Heat-Integrated Crude Oil Distillation System Design*](https://pure.manchester.ac.uk/ws/portalfiles/portal/31440025/FULL_TEXT.PDF). It is a literature/simulation benchmark rather than independent plant-test data, but it exposes an assay, pseudocomponent basis, pressure, stage distribution, reflux, steam, pumparound and reboiler/condenser duties, product flows, and product TBP points. It also uses Peng–Robinson, matching the proposed game property package. The optimized Case 6.1 results use different controls and are not mixed into this base-case table.

The feed is 100,000 bbl/day of Tia Juana Light, reported as 2610.7 kmol/h with density 865.4 kg/m³. It is preheated from 25 to 266 °C, fired to 365 °C, and separated at 2.5 bar. The source characterises the assay with 25 HYSYS-generated pseudocomponents.

| Distilled volume (%) | 0 | 5 | 10 | 30 | 50 | 70 | 90 | 95 | 100 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| TBP (°C) | -3.0 | 63.5 | 101.7 | 221.8 | 336.9 | 462.9 | 680.4 | 787.2 | 894.0 |

The one physical main fractionator and its three side strippers are represented mathematically as four thermally coupled simple columns. Their rectifying-plus-stripping stage counts are `9+5`, `10+5`, `8+7`, and `9+6`, or 59 equivalent theoretical stages across main-column and side-stripper sections—not 59 main-tower trays. The first two equivalent columns use 1200 and 250 kmol/h stripping steam; both streams enter at 4.5 bar and 260 °C. The last two use 8.78 and 6.63 MW reboilers. Three pumparounds remove 12.84, 17.89, and 11.20 MW with temperature drops of 30, 50, and 20 °C, and the top section has reflux ratio 4.17 and condenser duty 47.87 MW.

Those condenser, reboiler, and pumparound numbers are internal process duties. The reported 63.8 MW hot and 67.3 MW cold utilities belong to the integrated preheat/heat-exchanger network and are not expected to sum to the column duties. A column-only game solver can regress internal duties; external utility demand requires an explicit HEN boundary.

| Product | Flow (kmol/h) | Yield from rounded flows (%) | T5 (°C, mole-TBP) | T50 (°C, mole-TBP) | T95 (°C, mole-TBP) |
|---|---:|---:|---:|---:|---:|
| Light naphtha (LN) | 678 | 25.97 | 3 | 71 | 118 |
| Heavy naphtha (HN) | 496 | 19.00 | 117 | 156 | 196 |
| Light distillate (LD) | 653 | 25.01 | 190 | 248 | 317 |
| Heavy distillate (HD) | 149 | 5.71 | 285 | 339 | 372 |
| Atmospheric residue (RES) | 635 | 24.32 | 353 | 462 | 798 |

The rounded dry-hydrocarbon products total 2611 kmol/h, only 0.3 kmol/h above the stated crude feed. They exclude the 1450 kmol/h injected steam/water, which needs its own component closure. The feed assay above is volume-% TBP, whereas these product quantiles are mole-% TBP. Notice that adjacent product ranges overlap: `LN T95 = 118 °C` versus `HN T5 = 117 °C`, `HN T95 = 196 °C` versus `LD T5 = 190 °C`, and so on. A physically useful model therefore needs fractional component recoveries or equilibrium cells; one hard boiling-point recipe cannot represent all product tails.

As a plant-scale cross-check, [Said and Albasir's Azzawia refinery study](https://uotpa.org.ly/alostath/index.php/alostath/article/view/77) reports a 410 m³/h, 34-tray atmospheric tower and actual volume yields of approximately 29.3% naphtha, 20.5% kerosene, 13.9% light gas oil, 11.1% heavy gas oil, and 24.4% residue. Its incomplete pressure, steam, pumparound and product-quality data make it a yield plausibility check, not a replacement for the Manchester regression target.

## 3. Twelve-cut game representation

The published 25-cut feed is reduced here by grouping its source pseudocomponents into the following twelve-cut basis. The representative NBPs are molar-flow-weighted within each group, and the intervals are deliberately dense around adjacent product overlaps. Percentages are molar and sum to 99.96% because the published source values are rounded.

| Cut | NBP interval (°C) | Representative NBP (°C) | Feed (mol-%) |
|---:|---:|---:|---:|
| 1 | -3–50 | 22.3 | 8.34 |
| 2 | 50–90 | 75.5 | 12.07 |
| 3 | 90–125 | 111.0 | 6.73 |
| 4 | 125–175 | 149.0 | 12.99 |
| 5 | 175–210 | 187.0 | 6.37 |
| 6 | 210–260 | 223.8 | 11.36 |
| 7 | 260–305 | 274.9 | 9.33 |
| 8 | 305–350 | 325.7 | 7.95 |
| 9 | 350–405 | 375.8 | 6.86 |
| 10 | 405–480 | 432.6 | 6.48 |
| 11 | 480–620 | 536.3 | 6.34 |
| 12 | 620–894 | 719.1 | 5.14 |

This table is not yet a complete property package. Each cut still needs `MW`, specific gravity/density, `Tc`, `Pc`, acentric factor, ideal caloric data, viscosity anchors, and optionally PNA/sulfur/H:C quality attributes. Normal boiling point plus whole-crude density does not determine those properties uniquely. Generate them offline with a documented petroleum-characterisation method, regress first against the 25-cut Chen literature case and bulk assay, then test against the independent full-stage grid. A useful modern reference is [Satyro and Yarranton's assay-constrained oil-characterisation method](https://doi.org/10.1021/ef9000242).

## 4. A reproducible sharp-boundary baseline

This baseline asks a deliberately limited question: can twelve cut intervals encode the published product boiling ranges once the product amounts are known?

1. Normalize the twelve rounded feed percentages.
2. Treat molar content as uniform inside each authored NBP interval.
3. Normalize the five rounded product flows and place four sharp cumulative boundaries so those yields are recovered exactly.
4. Calculate each product's 5th, 50th, and 95th boiling quantiles from the resulting assay intervals.

| Product | Predicted T5 | Error | Predicted T50 | Error | Predicted T95 | Error |
|---|---:|---:|---:|---:|---:|---:|
| LN | 5.2 | +2.2 | 65.4 | -5.6 | 112.1 | -5.9 |
| HN | 123.8 | +6.8 | 157.0 | +1.0 | 196.2 | +0.2 |
| LD | 208.3 | +18.3 | 258.2 | +10.2 | 313.5 | -3.5 |
| HD | 322.2 | +37.2 | 336.7 | -2.3 | 351.8 | -20.2 |
| RES | 363.8 | +10.8 | 472.2 | +10.2 | 829.2 | +31.2 |

All temperatures and errors are in °C. Across the fifteen points, MAE is 11.04 °C, RMSE is 15.41 °C, and maximum absolute error is 37.2 °C. The largest misses are product-tail overlap and the very broad residue tail—the exact weaknesses expected from a sharp splitter. Chen constructs TBP curves from discrete pseudocomponent NBPs with midpoint interpolation; this baseline instead assumes uniform molar material inside the twelve authored intervals. Its error therefore describes this custom display representation, not the resolution of a future twelve-component PR solve.

This is a **representation test, not a prediction test**. It obtains zero product-flow error only because the literature flows set its boundaries. It does not calculate reflux, steam response, stage temperatures, pumparound effects, or energy duty. Keep this calculation as a fast recipe/debug fallback and as an initial guess for the staged solver.

## 5. Mapping the case onto the scientific model

**Benchmark reporting rule:** every literature case added to this report must include an explicit side-by-side comparison of the source model, the actually implemented in-game milestone, and any later proposed model. A fitted result without this comparison is incomplete.

### 5.1 Source model versus in-game model

| Aspect | Chen Case 6.1 base/existing-unit model | Milestone-1 in-game PoC | Later real-time in-game target | Consequence |
|---|---|---|---|---|
| Purpose | Process/heat-integration case study and optimization basis | On-demand single-block scientific calculator | Continuously operating gameplay process response | PoC validates the solver/lifecycle before process integration |
| Feed representation | 25 HYSYS-characterised petroleum pseudocomponents | One named 10–12-cut assay; twelve for this case | 10–12 regressed pseudocuts, with adaptive refinement possible later | Product tails need calibration and held-out tests |
| Physical topology | One main atmospheric fractionator plus three side strippers and three pumparounds | One bare main column with direct liquid side draws | Same visible equipment concept as the source | PoC side products are not stripped products |
| Numerical topology | Four thermally coupled equivalent simple columns; 59 equivalent stages across main and stripper sections | Player-selected main theoretical stages; total condenser and partial reboiler separate | Four equivalent modules with a proposed 10–16 total cells including lumped stripper behaviour | Neither in-game topology is a literal source reproduction |
| Governing separation model | Semi-rigorous shortcut/key-component recovery sequence with PR properties | Steady MESH material/equilibrium/summation/enthalpy solve | Reduced equilibrium/enthalpy cell balances; empirical recovery only as fallback/initial guess | Case 6.1 is not itself a published full-MESH validation case |
| Vapour–liquid equilibrium | Peng–Robinson through HYSYS/property calculations | Peng–Robinson with compiled cut properties and fitted `kij` | Same kernel | Same EOS family, different property characterisation and numerics |
| Pressure | Fixed 2.5 bar in all four equivalent columns | Fixed uniform 2.5 bar | Fixed 2.5 bar for first regression; drop later | Directly comparable initial pressure assumption |
| Caloric model | HYSYS properties; HEN streams segmented every 40 °C and at phase changes | Authored piecewise ideal caloric curves plus PR departure enthalpy | Same, with plant heat ledgers | Compatible basis, but cut-specific fits are outstanding |
| Steam/water | 1450 kmol/h steam; water participates in column vapour | Omitted | Vapour participation plus separate condensed aqueous phase | PoC cannot reproduce steam-stripping response |
| Side strippers | One steam-stripped HD and two reboiled HN/LD strippers | Omitted | Behaviour assigned to cells inside the four-module/total-cell budget | Light-end contamination will differ in PoC products |
| Pumparounds | Three specified duties and temperature drops | Omitted | Three enthalpy-accounted draw/cool/return recycles | PoC temperature profile and duties will differ |
| Dynamics | Steady-state base case | Button-triggered steady solve | Event-resolved quasi-steady algebraic segments | Neither claims physical tray startup waves |
| Hydraulics | Theoretical stages and diameters, but not a rate-based tray model | None | No flooding/weeping/entrainment/foaming in version 0 | Throughput limits remain gameplay constraints |
| Heat integration | Explicit 23-exchanger HEN and external utility accounting | Column process duties only | Later connected exchanger network | External utilities are outside PoC balance boundary |
| Runtime strategy | Offline FORTRAN/HYSYS simulation and optimization | One bounded Java worker job per Calculate request | Seconds-scale adaptive plant cadence | PoC needs no cadence scheduler but still must avoid server-thread stalls |
| Evidence level | Published simulation benchmark, not independent plant validation | Planned full-column solve; current evidence is only representation and synthetic kernel timing | Unvalidated reduced-order design target | No current full-column accuracy or capacity claim |

### 5.2 Implementation crosswalk

| Literature feature | Milestone-1 representation | Later real-time representation | Present status |
|---|---|---|---|
| 25 petroleum pseudos | Named 12-cut assay | Regressed 10–12-cut assay | Grouping selected; property regression outstanding |
| Peng–Robinson VLE | Full-stage main-column property kernel | Shared reduced-cell property kernel | Equations specified; validation data and solver code outstanding |
| 59 equivalent stages | Player-selected bare main-column theoretical stages | Four modules represented by 10–16 total cells | Both solver paths unimplemented |
| Three side strippers | Explicitly absent | Cells assigned inside the four modules, with vapour return | Later representation specified, not implemented |
| Three pumparounds | Explicitly absent | `Q`/`ΔT` draw-cool-return recycles | Later recycle convergence unimplemented |
| Steam stripping | Explicitly absent | Water lowers hydrocarbon partial pressures and may condense separately | Later qualitative support only; sour water omitted |
| Overhead system | Total condenser; no water boot | Vapour/hydrocarbon-liquid/aqueous flash | PoC contract specified; later three-phase robustness untested |
| Product TBP points | Quantiles of direct product pseudocut intervals | Same for stripped/reduced products | Directly supportable |
| Energy duties | Piecewise caloric curves plus PR departure enthalpy | Same with connected ledgers | Correct basis exists; cut-specific fits outstanding |
| Tray capacity/pressure drop | None | None in version 0 | Cannot predict flooding, weeping, diameter, entrainment, or throughput limits |

### 5.3 Side-stripper treatment

The Milestone-1 calculator does **not** include side strippers: its side products are direct liquid withdrawals from main-column stages. The later reduced staged model is intended to include all three side strippers, but neither the sharp-boundary baseline nor the Java property-kernel timing includes them.

The physical source arrangement is one main tower with HN, LD, and HD side strippers. The HD stripper receives steam, while the HN and LD strippers are reboiled; steam also enters the main-column bottom. Each in-game section module that owns a side product therefore solves four coupled streams:

1. hot liquid side draw from the appropriate main-tower location;
2. admitted stripping steam or reboiler heat;
3. stripped product liquid leaving the side-stripper bottom;
4. light vapour returning to the main-tower section above the draw.

The stripper cells are included inside the proposed 10–16 total-cell budget. They are not three additional chains appended after reducing the paper's 59 equivalent stages. Pumparounds remain separate draw/cool/return recycles because they remove enthalpy but do not produce a net product stream.

For regression, compare each side-product flow and `T5/T50/T95`, vapour-return flow/composition, stripper steam or reboiler duty, and the receiving main-section temperature. Until those checks pass, the model may mimic the topology but cannot claim that it reproduces side-stripper performance.

### 5.4 Reduced solver topology and caloric treatment

A practical game solve should use four connected **equivalent section modules** corresponding roughly to `RES/HD`, `HD/LD`, `LD/HN`, and `HN/LN`, with 2–4 virtual equilibrium cells in each module and 10–16 cells in total. Cells assigned to a module include its lumped main-tower separation and associated side-stripper behaviour; do not add three more independent stripper chains on top of that total. Internal cells are numerical state, not world blocks. Pumparounds couple the modules and the overhead unit performs the vapour/oil/water split. This equilibrium-cell formulation is distinct from the optional four-node empirical recovery surrogate used only as an initial guess or degraded fallback. Keep pressure fixed at 2.5 bar for the first regression, as the selected source does.

The source itself uses piecewise temperature–enthalpy data and 40 °C segmentation, plus new boundaries at phase changes, for heat integration. This is precedent for an event-aligned piecewise caloric representation, not proof that 40–50 K game knots meet an error target. Each compiled cut must still pass the caloric interpolation tests. One constant `Cp` over the feed's 25–365 °C heating path is not adequate for duty comparison, especially across vaporisation.

## 6. How far the present model is from the paper

The scientific basis specifies the core equations for a gameplay equilibrium/energy model, but the project is **data-incomplete and solver-unimplemented**. It can define the required conserved amounts, phases, PR equilibrium, piecewise enthalpy, water shortcut, equipment ledgers, and async update semantics. It cannot yet claim to reproduce the paper or show that 10–16 cells retain adequate fidelity.

The dominant remaining work is:

1. Generate a thermodynamically consistent 12-cut property set and constrain it to the published assay/bulk properties.
2. Implement the Milestone-1 bare-main-column MESH solver with its total condenser, partial reboiler, and direct liquid side draws.
3. Build a separately implemented 25-cut **same-topology bare-column oracle** so held-out feed temperature/composition, pressure, reflux, reboiler-duty, feed/draw-stage, and draw-rate tests isolate pseudocut/property/numerical error from equipment-topology error.
4. Warm-start from the previous result, report every residual, and never accept a fast non-converged answer as success; profile cold and warm solves through the Minecraft request/commit lifecycle.
5. Rebuild the published 25-pseudocomponent Case 6.1 shortcut/base calculation as a separate source-like literature target. Its side strippers, steam, and pumparounds are deliberately absent from Milestone 1, so source-versus-PoC differences are topology differences, not failures of the same-topology oracle.
6. After the PoC, implement the four-module, 10–16-cell continuous model—including assigned stripper behaviour and pumparound recycle tears—and run a second set of source-like held-out perturbations.
7. Mark every datum as a solver input, calibration target, or prediction. A specified pumparound duty, reflux, steam rate, side draw, or fitted product flow is not a validation prediction. Compare genuinely predicted flowrates, `T5/T50/T95`, temperatures, and duties—not yields alone. Use the separate Azzawia case as a coarse external yield check, not a complete plant validation.

Chen publishes the 25 pseudocomponent NBPs and molar flows but not the complete HYSYS `MW/SG/Tc/Pc/omega/enthalpy/kij` export. Generate the oracle's missing properties with the documented assay-constrained characterisation method, match bulk density and any available mean-MW constraints, and record those estimates as `C`-grade data. Use a solver path independent of the reduced in-game implementation and verify it against standard flash/column fixtures. This oracle supplies held-out model-to-model validation; it is not independent experimental truth.

Reasonable **game acceptance targets after regression**, rather than claims about current accuracy, are:

| Quantity | Initial target |
|---|---:|
| Numerical component-balance residual | <`1e-9` relative |
| Numerical energy-balance residual | <`1e-7` relative |
| Published dry-hydrocarbon rounded-flow discrepancy | Retain the raw 0.3 kmol/h, or 0.0115%, mismatch as fixture metadata; normalize or uncertainty-weight regression targets while the computed balance closes tightly |
| Water/steam component closure | <`1e-9` relative |
| Major product flows | ±8% relative or about ±2 percentage points of feed yield |
| Small HD product flow | ±15% relative |
| Product T50 | ±15–20 °C |
| Product T5/T95 | ±25–35 °C |
| Residue T95 | ±75 °C |
| Cell/module outlet temperatures | ±15 °C |
| Major duties | ±15–20% |

Do not adopt the few-percent simplified-versus-rigorous agreement from the thesis's separate Chapter 3 example as the Case 6.1 game target. First reproduce a tuned 20–25-cut independent reference. Six broad cuts remain useful for an arcade splitter; 10–12 are the working minimum design target for five overlapping atmospheric products, still subject to held-out validation.

## 7. Measured numerical-kernel cost

The local [Java benchmark](../benchmarks/CrudeColumnKernelBenchmark.java) is dependency-free and deliberately separate from NeoForge. It performs Wilson-initialised Peng–Robinson TP flashes, Rachford–Rice phase splitting, liquid/vapour fugacity iterations with quadratic mixing, caloric sums, and neighbouring-stage composition sweeps using preallocated arrays. Its properties and stage profiles are synthetic pseudocut-like inputs, not the calibrated Manchester assay. The current harness uses a two-second warmup per case and records individual update samples with nearest-rank percentiles; a release benchmark still needs longer profiling runs and archived raw results.

It does **not** solve complete MESH material/energy residuals, perform stage-temperature corrections, close condenser/reboiler/pumparound recycles, enforce product specifications, validate residuals, create immutable results, or include Minecraft snapshot/commit/GC contention. Its timings are therefore lower-bound property-kernel measurements.

Quick measurements used Java 21.0.11 on an AMD Ryzen 7 9700X with 16 logical processors and one benchmark thread. Six fresh JVM runs produced the results below. These values are refreshed after any harness change.

| Case | Components | Stages/cells | Sweeps | Stage flashes/update | Phase-EOS evaluations/update | p50 range (ms) | p95 range (ms) | p99 range (ms) | Estimated numeric workspace |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Gameplay | 12 | 10 | 6 | 60 | 360 | 0.1707–0.1716 | 0.1730–0.1749 | 0.1803–0.1934 | 5.8 KiB |
| Reduced CDU | 12 | 16 | 10 | 160 | 1,280 | 0.6151–0.6182 | 0.6996–0.7058 | 0.7275–0.8114 | 8.6 KiB |
| Synthetic full-stage stress | 16 | 61 | 15 | 915 | 10,980 | 6.9887–7.0464 | 7.6443–7.7840 | 7.8196–7.8666 | 40.1 KiB |

The measured kernel's major numeric arrays are small. Whole-solver memory remains unmeasured because snapshots, results, Jacobians, auxiliary arrays, and object overhead are excluded. These quick runs are useful for order of magnitude only; the production benchmark must add a full energy-corrected column solve, CPU-time measurement, ten-second-or-longer warmup, thirty-second sampling, convergence/residual assertions, allocation profiling, archived raw results, and integrated-server one-worker/two-worker tests.

## 8. Planning envelope and real-time feasibility

### 8.1 Milestone-1 click-triggered policy

Milestone 1 has no periodic cadence, token bucket, or adaptive scheduler. One accepted Calculate request creates at most one immutable job. The provisional game integration uses one FIFO worker, an eight-entry bounded queue, one in-flight request per block, visible queue-full/wait-expiry faults, cooperative solve limits, and revision-checked server-thread commit. Its acceptance benchmark records cold/warm solve wall and CPU time plus snapshot, queue, result construction, commit, logging, and packet costs. The 500 ms solve deadline remains a hypothesis until that complete solver is measured.

### 8.2 Later continuous-column planning envelope

Until the full solver exists, multiply the median kernel measurements by 10 as a provisional ordinary-state scheduling hypothesis. It is neither conservative evidence nor a measured upper bound; difficult cold starts or failed recycle convergence can exceed it and must hit an iteration/deadline fault.

| Case | 10× planning cost/update | CPU demand for one plant at 1 s | At 5 s |
|---|---:|---:|---:|
| 10-cell gameplay column | 1.7 ms | 0.17% of one core | 0.034% |
| 16-cell reduced CDU | 6.2 ms | 0.62% | 0.124% |
| 61-stage stress case | 70.0 ms | 7.00% | 1.40% |

At the proposed scheduler's 350 worker-ms/s target, examples at a five-second cadence are:

- 100 gameplay columns at 1.7 ms each require about 34 worker-ms/s: 3.4% of one core and 9.7% of the 350 ms/s scheduler budget;
- 20 reduced CDUs at 6.2 ms each require about 24.8 worker-ms/s: 2.5% of one core and 7.1% of that budget;
- 10 deliberately full-stage cases at 70.0 ms each require about 140 worker-ms/s: 14.0% of one core and 40.0% of that budget.

These examples are CPU arithmetic only, not whole-server capacity promises. Main-thread snapshot/commit work, other mods, garbage collection, chunk activity, and solver variance reduce the safe population.

The appropriate runtime policy for the later continuously operating column is:

- solve each plant on one worker; parallelise **between plants**, not within one small column;
- use one bounded worker by default and allow two only after dedicated-server testing;
- warm-start every flash, cell/module state, and recycle from the last committed result;
- use approximately 1–5 seconds for changing/interactive columns and 5–20 seconds for stable active columns; a truly inactive/parked column with no unprocessed boundary ledger may be event-driven with a slower validation wake and is exempt from the active 20-second staleness limit;
- let measured CPU cost set the minimum period, then apply a global server-load dilation and worker-ms token bucket;
- cap iterations and wall time, keep the previous valid state on failure, and expose a visible `NOT_CONVERGED` or `DEGRADED` diagnostic.

A steady-state online crude-column model has been demonstrated in the process literature specifically for online applications; see [Kumar et al., *A crude distillation unit model suitable for online applications*](https://doi.org/10.1016/S0378-3820(01)00195-3). That supports the general seconds-scale approach, but it does not substitute for measuring this Java implementation inside a modded NeoForge server.

## 9. Updated feasibility verdict

Real-time gameplay simulation remains plausible and worth implementing, but full-column feasibility is not yet measured. The first executable target is now the [Milestone-1 calculator](./MILESTONE_1_CRUDE_DISTILLATION_POC.md): a calibrated 12-cut, bare-main-column MESH solve with direct liquid side draws, run asynchronously only after the player presses Calculate. It deliberately omits side strippers, pumparounds, steam, and continuous cadence. Its purpose is to establish scientific closure, arbitrary-input failure handling, full-solver cost, Minecraft lifecycle safety, GUI/console agreement, and a reusable full-stage reference—not to claim source-case fidelity.

After that PoC is measured, the first continuously operating target remains a calibrated 12-cut model with four equivalent section modules and 10–16 total virtual equilibrium cells, solved asynchronously on a seconds-scale cadence. Use part of the paper's outputs for calibration, a separately implemented perturbed 25-cut oracle for held-out model-to-model validation, and an external assay for a coarse physical plausibility check.

The scientific risk is dominated by pseudocomponent characterisation and calibration; the computational risk is dominated by recycle/cold-start convergence and main-thread integration. No fundamental blocker is evident, but the reduced model earns its feasibility claim only after the held-out fidelity tests and integrated full-solver benchmark pass. A full-tray model can remain an offline reference, while the reduced-cell model supplies normal gameplay.

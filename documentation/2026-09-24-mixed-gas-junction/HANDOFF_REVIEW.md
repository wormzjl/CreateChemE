# Review of the mixed-gas junction handoff, with a replacement fix proposal

> **Revised 2026-09-25 after experiments (section 6), re-analysed (section 7) and re-measured (7.5). Read 7.5 first: the cap-exit stall (7.1) is real and form B removes it, but it is not what stops the 50 mm runs; they, like the 20 mm runs, fail at the approach to rest (7.2). 7.4 stands with the order reversed: item 3 decides every transient case, item 2 is secondary.** Sections 1-5 are kept as the pre-experiment analysis. The experiments **confirmed** the junction-row Jacobian defect (2.1). They **falsified** the claim that a regularised or holdup junction closure makes the pressure seed unnecessary (1, 4.3). They showed that the 50 mm stall at 1.5 s is the **velocity-cap exit**, not near-zero flow; that corrects both the handoff and my section 2.2. They also found a fourth failure, at the approach to rest **after a restart**. The revised proposal is in 6.3.

Written 2026-09-25 by Claude (Opus 5.5) from an independent reading of HANDOFF.md, JUNCTION_REVIEW.md, the stored probe output in tools/pipe-junction-probe/, and the solver source at 9674bf1. No code was changed and no Gradle run was made for this review. The claims marked *measured* come from the stored probe files. The claims marked *hypothesis* still need to be tested (section 5).

## 1. Verdict

The handoff's startup observations are correct, and so is its warning not to ship the initializer on its own. Its framing is wrong in one important way. It describes **two** problems: a cold-start seed defect, and a separate near-equilibrium conditioning issue. The source and the stored data both point to **one** defect that shows up at both ends of a trajectory. The zero-holdup junction's mixing rows are a ratio that does not scale with flow, `w = Σ|q|w_e / Σ|q|`, and they switch hard to the stored guess below 1e-14 kg/s. So their Jacobian grows like `Δw/Q` as the total inflow `Q → 0`. The finite-difference step on a flow column is 1e-6 kg/s, which is far larger than `Q` at both of the moments when `Q → 0`:

- **Startup:** the seed pressure equals the feed pressures, so `Q ≈ 8.9e-16 kg/s`, which is below the 1e-14 floor.
- **Approach to rest:** the tanks equalise with the sinks, so `Q` falls to 1e-9 kg/s and below.

The balanced initializer moves the *start point* away from `Q = 0`, but the trajectory comes back to `Q = 0` by physics. That is why every 50 mm run stalls. A better initializer cannot fix this; the junction closure has to be well posed at zero throughput.

**Proposed fix (section 4):** replace the ratio-plus-floor-plus-frozen-donor closure with a regularised mixing closure. It is realised as a tiny *owned* junction holdup of fixed mass `m_J`, so it stays exactly conservative. Algebraically, this is the regularisation Modelica stream connectors use for ideal mixing at zero flow (Franke et al., 2009). Written as a backward-Euler holdup, it also closes the books exactly.

## 2. Evidence for the single root cause

### 2.1 Why the startup Jacobian is wrong (source reading, *hypothesis* until E1 runs)

- `Equations.junctionInflow` (PassiveStepSolver.java:2017-2025) returns the incoming mass fractions and specific enthalpy as `incoming/incomingMass`. When `incomingMass ≤ ConservativeTransport.JUNCTION_INFLOW_FLOOR = 1e-14` (ConservativeTransport.java:18), it returns the **stored guess** instead. `nodeSolidRows` (line 2011) has the same switch.
- Newton settings (line 270) use `differenceStep = 1e-6`, and the flow columns have `differenceFloors = 1` (line 1487). So every flow column is perturbed by 1e-6 kg/s.
- At startup both inflows are about 4.44e-16 kg/s (handoff), so the base point uses the stored pure-methane guess. Perturbing the nitrogen inflow column by 1e-6 kg/s crosses the floor, and the mixture jumps to almost pure nitrogen. The Jacobian therefore holds `(h_N2 − h_CH4)/1e-6` in the enthalpy row and about `1/1e-6` in any fraction row. Perturbing the methane column changes nothing. These are derivatives of a step function, not of the equations, and they fit the recorded first direction: log-temperature moves by 0.995 while log-pressure moves by 8.38e-9.
- On top of this, the deadband in `transportDirection` (line 679) drops nitrogen from the junction basis (`refineJunctionReachability`, line 699). A layout with only methane, whose enthalpy row asks pure methane to match a mixed CH4+N2 inflow, has a spurious root far from the physical one. Both mechanisms depend on the same thing: flows that sit numerically at zero.

### 2.2 The transient failures are zero-flow failures (*measured*, stored files)

- **The 20 mm runs never go near equilibrium.** At t = 5.0 s every 20 mm case still has tank 1 at 122.57 kPa, with its inlet edge on the velocity cap (flow constant at 0.02228 kg/s). The equal-tank cases also have tank 2 at about 120-122 kPa (transient-balanced-two-bores.txt). "Completes 5 s" is therefore not evidence of transient robustness: these runs never enter the regime where the 50 mm runs fail. The handoff table should say so.
- **The 50 mm runs fail as `Q → 0`.** Tank 1's inlet stays velocity-capped until about 1.5 s (flow proportional to density: 0.1350 → 0.1248 → 0.1223 kg/s). The runs stall at the step where the tanks reach the sink pressure. In the equal 4-port case, at t = 1.6 s the tanks are at 101325.00006 and 101325.00008 Pa, and the remaining flow is driven only by gas static head.
- **Clearest single case: the low-flow-difference 4-port equal run** (transient-low-flow-difference.txt). It *reaches rest*: the outlets are closed by the one-way void, and the two tanks exchange ±4.9e-8 kg/s at 3.0 s and ±2.1e-9 kg/s at 4.0 s through the junction. It then fails at 4.5 s **at rest**, with residual 0.0049. No startup, velocity cap or reversal is involved, only a junction whose total inflow is 1e-9 kg/s. This is the ratio closure failing on its own.
- Rebuilding the solver every interval changes nothing (handoff), which fits a formulation defect rather than corrupted retained state.

### 2.3 Other corrections to the handoff

1. **The balanced seed does not apply to the finite-tank stage solves.** Its eligibility test needs every neighbour of the junction to be `fixed()`. Finite tanks are `RESERVOIR`. The seed only reaches the transient probes through the TR-BDF2 endpoint rate evaluation, which turns reservoirs into `PORT` nodes (TrBdf2StepSolver.java:70). Its effect on transients is therefore indirect and depends on how that solver warm-starts the stage solves.
2. **Confounder at the 50 mm stall.** The failing step also contains the tank edge leaving the velocity cap. The cap law in `edgeRows` (line 1855) swaps one row formula for another at `|driving| = limitDrop`, so the row is continuous but its Jacobian is not. It also contains the outlets approaching the one-way void shutoff. The rest-state failure in 2.2 isolates the junction mechanism, but the 1.5 s stalls may carry more than one cause. Handoff action 1 (trace the stalled graph) should record the cap and shutoff state separately.
3. **The initializer's cost is unbounded by design.** It runs up to 8 sweeps × (36 bisections + 30 TP flashes) per ineligible-pressure junction per solve. The handoff says this itself; it matters more now that the initializer would no longer be necessary.

## 3. What is right in the handoff and should be kept

- The startup defect is real, and so is the first-boundary seed at PhysicalFluidTopology.java:93.
- Its warnings are correct: do not raise the Newton budget, loosen conservation, or ship the initializer or the smaller difference floor as a fix. The finite-inventory plus boundary-ledger conservation oracle is the right test.
- Recommended actions 1 (trace the stalled graph) and 4 (assertion-based gates *through* equilibrium and restart) stand. Action 2 (finish the initializer) should be dropped in favour of section 4. Action 3 is what section 4 implements.

## 4. Proposed fix: owned-holdup regularised junction closure

### 4.1 Equations

Give each junction a fixed, owned holdup mass `m_J` with composition `w_J` and specific enthalpy `h_J`. Pressure stays algebraic, and the net-mass row is unchanged, so the holdup mass stays constant. The backward-Euler species and energy balances for a step `dt` are:

```
(m_J/dt + Q_in) · w_J,i  =  (m_J/dt) · w_J,i^old  +  Σ_e max(q_e→J, 0) · w_e,i
(m_J/dt + Q_in) · h_J    =  (m_J/dt) · h_J^old    +  Σ_e max(q_e→J, 0) · (h_e + gΔz_e)
Q_in = Σ_e max(q_e→J, 0)
```

With `ε = m_J/dt`, this is the ratio closure with an `ε`-weighted retained term:

- `|∂w_J/∂q| ≤ |Δw|/(Q+ε)`, bounded by `|Δw|/ε`. There is no floor switch and no deadband.
- At `Q ≫ ε` it matches ideal mixing to within `ε/Q`. At `Q = 0` it keeps the owned composition and temperature. This gives the "explicit retained composition/temperature at genuine zero throughput" the handoff asks for, and the value is owned state, not a stored guess.
- Every flow-dependent term is multiplied by `max(q,0)`, which is continuous at zero, exactly as for a vessel. The live upwind is therefore safe, and the **frozen-donor machinery can be removed**: `junctionDonorFirst`, `donorsTurned`, `junctionsTurned`, and the donor half of the cycle key. That removes the active-set passes the handoff says must converge before support can be corrected. If one-sided differences across the kink cause trouble, use a C1-smoothed positive part over `|q| < δ`, as Modelica does.
- Refining the substep now helps, because `ε = m_J/dt` grows as `dt` shrinks. Today refinement cannot help a zero-storage star.

### 4.2 Exact conservation

- `ConservativeTransport.reconstruct` treats each junction as a vessel with `endMass = m_J`. The row becomes `(m_J + dt·Q)·w = m_J·w_old + Σ dt|q|·w_donor`, which is the vessel row at lines 182-192. The `fed`/`JUNCTION_INFLOW_FLOOR` branch (lines 170-179) is deleted.
- `checkConservation` (line 1243) and the probes' ledger oracle **must include** junction inventories. Today they skip junctions.
- The junction's energy is booked in the same form the row states (`m_J·h`, enthalpy form, because pressure is algebraic), so the audit closes to rounding.
- Minting has to be conservative. `PhysicalFluidTopology.compile` currently mints the junction from the first boundary's state. It must instead debit `m_J` from a neighbouring owned inventory, or book it as a boundary transfer from a generator. Split, merge and removal must return it the same way. Under the no-compatibility rule this is a checkpoint-format change tested on a fresh world, with no migration.

### 4.3 Basis and seeds

- With owned stock, the junction basis can be the **undirected** potential-donor closure used for vessels (connections that allow inflow, plus the junction's own stock). It no longer depends on the sign of the start-point flows through the 1e-14 deadband. A species nothing delivers then decays geometrically from `w_old` and is held by the existing trace clamp and projection rules, as in a vessel.
- `restateJunctions`, `refineJunctionReachability` and the balanced initializer become unnecessary. A cold junction starts from its owned state. Nitrogen enters through bounded derivatives, with no spurious root to chase.
- **Must-pass gate:** the phantom-trace case (documentation/JUNCTION_PHANTOM_TRACE.md, filter block line). Directed junction bases were introduced for it, and that measurement has to be repeated under the new closure.

### 4.4 Choosing `m_J`

- `m_J` sets `ε`, and it should be sized against the flow finite-difference step. The proposal is `m_J = κ · ρ_seed · V_fitting`, fixed at compile, with κ swept over 1e-5 … 1e-2. Measure convergence, and measure the change in ideal-mixing results on the existing exact-regression and junction gates.
- For scale: a 50 mm gas fitting holds about 5e-3 kg at 1 bar. With κ = 1e-3 and `dt = 0.1 s`, `m_J ≈ 5e-6 kg` gives `ε ≈ 5e-5 kg/s`, which is 50 times the 1e-6 kg/s difference step. The composition lag at 0.1 kg/s is 5e-5 s.
- For very long rest intervals `ε` shrinks. Tie the flow-column difference floor to `ε`, for example `floor = ε`, so perturbations stay below the regularisation scale.

### 4.5 What stays unchanged

Newton tolerances, the iteration budget, the 1e-8 equation gate, conservation tolerances, the velocity-cap law, device active sets and the property domain all stay as they are. The velocity-cap switch in 2.3(2) is a separate, smaller Jacobian discontinuity. Look at it only if the traces still show it after this change.

## 5. Validation order: falsify cheaply first

- **E1: Jacobian check (instrumentation only).** At the startup point and at the stalled rest point (low-flow-difference run, 4-port, t = 4.5 s), compare the finite-difference junction-row columns at steps 1e-6, 1e-9 and 1e-12 kg/s against a central difference. Prediction: at 1e-6 the entries scale like `Δw/step` and disagree by orders of magnitude.
- **E2: Newton-only prototype.** Regularise `junctionInflow` and the reconstruction's junction row with `ε`, but do not yet book the holdup; the prototype alone may relax conservation. Run on base 9674bf1 **without** the balanced seed. Pass condition: the static reproducer, all 12 transient cases, and the 50 mm cases running *through* rest to 10 s. If this fails, the single-root-cause diagnosis is wrong and the handoff's two-track plan stands.
- **E3: the real implementation (section 4).** Include owned holdup, ledger inclusion, conservative minting and removal of the frozen donors. Gates: the handoff's action 4 list (rest then restart, reversal, source order, vertical branches, adjacent junctions, pumps, filters, dead-head, phantom trace, phase change, domain limits), the exact regression, the κ sweep, and a timing comparison with main.

All of this falls under the working rules: one Gradle run at a time, no dev client running, probes kept under tools/pipe-junction-probe/.

## Reference

Franke, R., Casella, F., Otter, M., Sielemann, M., Elmqvist, H., Mattsson, S. E., Olsson, H. (2009). *Stream Connectors – An Extension of Modelica for Device-Oriented Modeling of Convective Transport Phenomena.* Proc. 7th International Modelica Conference, Como. (The regularised `inStream` mixing at zero flow. The holdup form above differs in being exactly conservative.)

## 6. Experiments (2026-09-25) and revised proposal

Run in worktree `remove-agents-attribution-line-3d0c97` at 9674bf1: 21 sequential Gradle runs, no dev client running. Everything is in `tools/junction-holdup-prototype/` (README, patches, probes, logs). The sources were restored afterwards. With every prototype switch off, the harness reproduces the handoff's residuals bit for bit: 5.816801548255438e-6 and 1.3351756362421356e-4 at base, 0.004103230521212475 and 2.7039147965659144e-4 with the balanced seed, and 0.004891078087940204 with the small flow floor.

### 6.1 Results

**E1, junction-row Jacobian: confirmed.** 3-port case, first failing solve (TR-BDF2 rate solve, dt = 1):
- At the start point the junction inflow is 8.9e-16 kg/s, below the 1e-14 switch.
- The enthalpy-row derivative with respect to the nitrogen inflow is 3.5e5, 3.5e7, 3.5e9 and 3.5e11 at steps 1e-6, 1e-8, 1e-10 and 1e-12. It scales as 1/h: the slope of a step function. The central difference gives 0.07.
- At the failed point the junction is still methane-only, at a spurious **334.5 K** with 350 K feeds.

**Startup is dominated by the start pressure, not the closure.** In the labelled dumps:
- The compiled junction (first-boundary state) starts both outlets **exactly on the velocity cap** (4-port: q = −0.11089 = capFlow).
- In the unequal 150 kPa case the 145 kPa generator's edge starts **reversed** (into the generator) and capped. The capped rows are flat in pressure, and continuity demands 0.38 kg/s through an edge capped at 0.19.
- The residual of these failures is identical to 9 digits across every closure variant.

**E2, closure without the seed: falsified.**
- Static matrix: ratio closure plus holdup, 0/32. Adding the undirected basis and live upwind, 0/32. Flux form, 0/32. Physical-size 5e-3 kg holdup, 0/32. Base is 7/32.
- The flux form does make the junction-row derivatives exact: 1.0 and 0.352 at every step size.
- But at `Q ≈ 0` the composition and temperature are held only by `ε`, so the first Newton step predicts `dw ≈ Δw·dQ/ε`. Any small-`ε` closure inherits the start-point problem. The pressure seed is necessary.

**Seed alone versus seed plus closure, on identical probes:**

| | Base | Balanced seed | Seed + flux form (ε = 1e-12 kg/dt) |
|---|---|---|---|
| Static 32-case matrix | 7/32 | **30/32** | 29/32 |
| 50 mm finite tanks | fail at step 0 | stall at step 15-16 | stall at step 15-16 |
| 20 mm, rest then restart (16 s) | fail at step 0 | fail at steps 134-138 | fail at steps 135-148 |

The closure change adds nothing measurable on these probes. An owned holdup also needs TR-BDF2 stage support: stage graphs carry a junction state from the previous stage but its original inventory, and the ledger did not close (runs 8-15).

**The 50 mm stall at 1.5 s is the velocity-cap exit (run 16, balanced seed alone).** At the stalled substep:
- The junction rows have converged (1.2e-9 and 1.9e-10).
- The whole residual is **edge 0 (tank 1 → junction), hydraulic/cap row**, with q = 0.12164 kg/s against a cap of 0.12075 at 629 Pa driving.
- All flows are 0.03-0.15 kg/s, so it is **not** near zero flow.

The row switches formula at `|driving| = limitDrop` (`edgeRows`, PassiveStepSolver.java:1855), and the cap depends on the donor density. The handoff's "near-equilibrium / near-zero-flow" account of this stall, and the smaller flow floor as its lever, do not describe this failure.

**New: the approach to rest after a restart (run 19, 20 mm 4-port, balanced seed alone).**
- Methane injection stops at 13.0 s. Tank 1 drains at about 1.5 kPa/s and reaches 101325.001 Pa at step 138.
- The failure's residual is entirely in the **junction mixing rows** (8.6e-5 / 2.7e-5), with junction inflow at 5.4e-7 kg/s. The flow difference step (1e-6) exceeds the flows.
- This is the zero-flow mechanism of 2.1, observed directly. Neither the flux form nor a 1e-6 flow floor, alone or together, fixes it: each passes 1 of 12 transient cases.

### 6.2 What changes in this review

1. The single-cause claim (section 1) is wrong. There are **three** independent mechanisms: the start pressure (startup), the velocity-cap switch (flowing transients), and the junction mixing rows at near-zero inflow (the approach to rest).
2. The handoff's pressure seed is the right startup fix, and my "drop the initializer" recommendation (4.3) is withdrawn. The owned-holdup design (4.1-4.4) is not recommended. It needs TR-BDF2 inventory support and gave no measurable gain.
3. Section 2.2's claim that "the 50 mm runs fail as `Q → 0`" is corrected: they fail at the cap exit. The "20 mm runs never reach equilibrium" point stands and has been extended. With a restart phase added, they fail at their approach to rest.

### 6.3 Revised proposal

1. **Pressure seed (the handoff's balanced seed), generalised.** Its eligibility test excludes finite reservoirs for no reason. At the start of a step every owned neighbour has a known pressure, so the scalar continuity bisection applies to any junction whose neighbours are not junctions. Chains of junctions need a small coupled pressure solve. The seed must set the Newton start only, never the stored junction state. Measured: static 7/32 → 30/32. Profile its cost (up to 8 × (36 + 30) evaluations per junction).
2. **Velocity cap as a smooth complementarity row.** Replace the `|driving| > limitDrop` formula switch with one row, `φ(a, b) = a + b − sqrt(a² + b² + δ²)`, where `a = (driving − loss(q))/scale` and `b = (limit − |q|)/limit` (Fischer–Burmeister, the standard semismooth treatment of this switch). Its root is either the hydraulic law with `|q| < limit` or `|q| = limit` with surplus driving pressure, and it has no region switch. This targets the 50 mm stall directly. **Not yet tested.**
3. **The junction at near-zero inflow is still open.** Measured insufficient: the flux form with a small `ε`, and a smaller flow floor. Candidates still untested:
   - an explicit stagnant active set: a junction whose inflow falls below a threshold keeps its composition and temperature, its mixing rows are replaced by retention rows, and the state is revised between passes like the device modes;
   - analytic mixing-row derivatives with respect to the flows, so the finite-difference step never straddles the regime.

   Test against run 19's case (20 mm rest, restart, rest).
4. **Gates:** the probes in `tools/junction-holdup-prototype/` (static matrix; transients through rest, restart and rest again) become assertion-based. Also run the handoff's action-4 list, the exact regression, and the unchanged conservation tolerances.

Recommended next step: implement item 2 behind a switch and rerun the 50 mm transients. It is the smallest change aimed at the failure that stops every 50 mm run.

## 7. Re-analysis of the stored dumps (2026-09-25, second pass)

A second reading of the run 16, 19, 20 and 17/21 dumps and of runs 8-15, without new Gradle runs. It changes two of the three mechanism accounts in 6.2 and withdraws two of the three candidates in 6.3. Sections 1-6 are kept as written.

### 7.1 The 50 mm stall is a scale mismatch between the two cap branches, not a Jacobian discontinuity alone

Run 16, 4-port equal, the failed substep (dt = 8.9e-4 s):

- Start point: edge 0 at q = 0.11201 with cap 0.12079, row residual +0.0727. That equals `(limit - q)/limit`, so the substep **starts on the cap branch** (driving 673 Pa > limitDrop).
- Failed point: q = 0.12164, cap 0.12075, residual -8.89e-5. With `pressureScale = max(1e5, P) = 1.02e5` this is `driving - loss(q) = -9.1 Pa`, so the point is **on the hydraulic branch** (driving 629.5 Pa < limitDrop) with the flow 0.7 % above the cap. Both facts together put limitDrop within about 1 Pa of the driving pressure: the exact solution of this substep is the crossing itself (q = limit, driving = limitDrop).
- Row slope with respect to q: cap branch -1/limit = -8.3; hydraulic branch -loss'(q)/scale = -1.05e4/1.02e5 = -0.10. **The same flow error is 80 times larger as a residual on the cap branch.** A line-search candidate that crosses the switch from the hydraulic side therefore reads as a residual increase (7.4e-3 for the same 0.7 % overshoot), fails the descent test at every halving, and the search stalls at the hydraulic-side value 8.9e-5, which is what the dump shows. All other rows are at 1e-9 or below.
- The 20 mm runs meet the same exit at 8.x s (run 19, two failures at q = capFlow to 5 digits) and survive it only through substep refinement, which is why they cost 10-13 substeps there.

Fix, smaller than the Fischer-Burmeister row of 6.3(2): keep the switch on the driving pressure but state both branches in the same units, with no jump:

```
f = (sign(driving) * min(|driving|, limitDrop) - loss(q)) / pressureScale
```

Below the cap this is the hydraulic row unchanged. Above it, its root is `loss(q) = limitDrop`, that is `|q| = limit`, in pressure units with slope -loss'(limit)/scale on both sides of the switch. The row is continuous everywhere (a kink at `|driving| = limitDrop`, no jump), the line search sees one residual scale, and the one-sided Jacobian samples a finite slope on either side. The FB row stays as the C1 fallback if the kink itself is ever measured to matter. Both are untested.

### 7.2 The rest failure is curvature of the ratio closure, and no Jacobian accuracy can fix it

Run 19, 20 mm 4-port, the repeated failures at steps 134-138 (dt from 0.0098 down to 0.0012 s):

- Every refinement level starts from the **same, already converged point**: initial |f|max = 1.45e-9 (tolerance 1e-9), on the tank rows; the junction rows are at 1.3e-13.
- The Newton then leaves that point and stalls at 5e-7 to 1.7e-4 in the junction mixing rows. Flows: 2.1e-7, 4.8e-7, -3.4e-7, -3.4e-7 kg/s; junction inflow Q = 6.8e-7 kg/s.
- The dumped junction-row derivatives with respect to the inflows are -1.02e6 and +4.4e5 (central difference); at the production step h = 1e-6 the forward difference gives -4.1e5 and +1.8e5, a factor 2.5 low, because h exceeds Q.
- The 1.45e-9 tank residual needs the flows to move by a fraction of themselves (the flows decay geometrically at rest, so each substep's solution is not the previous one). With dw/dq = dw_mix/Q = 1e6 and d2w/dq2 = dw_mix/Q^2 = 1e12, a flow change of 6e-8 kg/s carries a linearization error of about 4e-3 in the fraction row, seven orders above the 1e-9 target. The descent test accepts only alpha below about 1e-6, so the Newton either stalls or advances by nothing per iteration.

Run 20 confirms the reading: with the flow floor at 1e-6 the flow columns are differenced at h = 1e-12 (the dumps agree with the central difference to 6 digits), and the same cases still fail at steps 134-146, now as **iteration limit** instead of line-search stall. **Candidate 6.3(3b), analytic mixing derivatives, is therefore refuted**: the problem is the equations' curvature at the scale of the iterates, not the accuracy of their slopes. A zero-holdup junction whose composition is the ratio of two vanishing flows is ill-posed as Q -> 0 whatever the solver does, and it is also physically wrong there: a 20 mm fitting holds about 1e-4 kg of gas, which 7e-7 kg/s renews on a timescale of minutes, not in one 10 ms substep.

Runs 17 and 21 show what row scaling without a real holdup does. With eps = 1e-12 kg per dt (1e-10 kg/s, three orders below Q) the flux form `(Q+eps) w_J = sum q w_e + eps w_old` multiplies the mixing rows by Q = 1e-7, so they no longer constrain the junction: the same runs then fail with "Methane temperature > 900 K" (112 rejected substeps in one interval), step-refinement errors of 0.03 and total-energy-balance failures. A Newton step `J^-1 f` is invariant to row scaling, so the flux form with eps << Q changes only the acceptance test, and changes it in the wrong direction. The **6.1 claim that "the closure adds nothing measurable" is valid only for eps << Q**; it does not test the regularisation, which needs eps >> Q.

**Candidate 6.3(3a), a stagnant active set, is withdrawn too**: it is the eps -> infinity limit of the same closure as a hard switch at a flow threshold, which is one more active-set flip the pass loop would have to chatter across while Q hovers at the threshold. The continuous holdup row contains it.

### 7.3 What the physical-holdup runs did and did not test

Runs 8-15 (balanced seed, holdup 1e-6 kg, so eps = m/dt = 1e-5 to 1e-3 kg/s >> Q at rest) never failed in the Newton at rest. Every failure is bookkeeping: "Conservative reconstruction fails equation gate" 3.6e-7 to 11.4, "Component balance failed" with `before - after = 2.5e-6 mol` against `external = -3.3e-6` (the holdup's own change, unbooked), and the probe's energy oracle at 1.4e-7. The cause is in TrBdf2StepSolver.java:112-135: the three stage-base loops carry a junction with the previous stage's *state* but the *initial* graph's inventory (`node.kind()==RESERVOIR ? updated : node.inventory()`), and `checkConservation` (PassiveStepSolver.java:1243) skips junctions altogether. My prototype worked around that with `holdupOld`, which is why the books never closed.

So the holdup closure at rest is **untested, not falsified**. The E2 falsification in 6.1 concerns startup only, and it stands: a composition closure cannot place the junction pressure.

### 7.4 Revised proposal (replaces 6.3)

1. **Pressure seed**, as 6.3(1). Unchanged.
2. **Cap row in one scale** (7.1), one line in `edgeRows` (PassiveStepSolver.java:1855). Test: the 12 transients; the 50 mm cases must pass 1.5 s and the 20 mm cases must stop refining at 8.x s. FB is the fallback.
3. **Owned junction holdup with the stage carry done properly** (7.2, 7.3). Rows as in 4.1, pressure algebraic, mass constant; m_J = kappa * rho * V_fitting with kappa swept so that eps = m_J/dt >= 1e-4 kg/s at dt = 0.1 s (m_J >= 1e-5 kg; a 20 mm fitting holds about 1e-4 kg anyway). Bookkeeping: a junction's inventory is carried through the TR-BDF2 stage bases exactly as a RESERVOIR's (the three loops at TrBdf2StepSolver.java:112, 121, 155; `dn` is zero for a junction because it has no boundaries), `reconstruct` produces a junction inventory (my prototype's row), and `checkConservation` stops skipping junctions. The junction `Reservoir` record already carries an `Inventory`, so the checkpoint format may not change; the mint at compile (PhysicalFluidTopology.java:93) has to debit a neighbour or book a boundary transfer. Keep the frozen donors and the directed basis in the first version; remove them only after the phantom-trace gate is re-measured under the holdup.
   - Compile the junction as a small vessel instead? No: a liquid-full fitting vessel is stiff in pressure and trips the 1e-10 small-headspace tolerance for the whole island, which is the filter-block defect the junction kind was introduced to avoid. The holdup rows regularise composition and enthalpy only and leave pressure algebraic.
4. **Gates** as 6.3(4), plus: the 12 transients through rest, restart and rest again; the phantom-trace measurement; the exact regression; the kappa sweep against the ideal-mixing results.

Order: 2 first (one line, decisive for every 50 mm run), then 3. The rest certificate (documentation/2026-09-23-fluid-scheduling-rest) may hide 3 in play by stopping the solves before Q reaches 1e-7, but every restart that decays through a mixed junction meets it again, so it is not optional.

### 7.5 Cap-row experiment (runs 22-26)

Run 2026-09-25 in the same worktree at 9674bf1: `holdup-prototype-with-balanced-seed.patch` plus a `junction.capForm` switch in the cap block of `edgeRows`, holdup 0, every other switch at its default. Five sequential Gradle runs, no dev client. The sources were restored afterwards. The full diff is `tools/junction-holdup-prototype/holdup-prototype-cap-minform.patch`, and the logs and XML files are `run22`-`run26` in the same folder.

- **old**: the unchanged row, cap branch `(±limit - q)/limit`. Baseline is run 18; run 24 reproduces its 0.05:4:false residual bit for bit (0.004103230521212475).
- **A**: cap branch `(sign(driving)*limitDrop - loss)/pressureScale`, the 7.1 row.
- **B**: the same numerator, with both branches of a clampable edge divided by `S = min(pressureScale, 2*limitDrop)`. This also re-weights the *hydraulic* row of every clampable edge by `pressureScale/S`, about 80x at 50 mm, wherever the edge sits. So B is not a pure cap-branch change.

**Results** (step = the failing 0.1 s interval; LS = line search stalled, IT = iteration limit, ISL = interval substep limit):

| | old (run 18) | A (run 22) | B (run 23) |
|---|---|---|---|
| Static 32-case matrix | 30/32 | 30/32 | **31/32** |
| Static failures | 3:150:no-swap:unequal singular LU; 4:150:swap:unequal LS 0.402 | 3:150:no-swap:unequal IT 0.0314; 3:150:swap:unequal IT 0.497 | 3:150:swap:unequal IT 2.19e-5 |
| 0.05:4:false | step 16, LS 4.10e-3 | step 16, LS 3.70e-3 | step 15, LS 1.35e-9 |
| 0.05:4:true | step 15, LS 4.46e-3 | step 15, ISL (517 accepted / 508 rejected) | step 15, LS 2.20e-9 |
| 0.05:5:false | step 15, IT 2.70e-4 | step 15, IT 1.50e-6 | step 15, LS 1.75e-9 |
| 0.05:5:true | step 15, IT 2.70e-9 | step 15, LS 0.903 | step 15, LS 3.15e-9 |
| 0.05:6:false | step 15, IT 2.52e-6 | step 15, IT 1.93e-9 | step 15, LS 2.28e-9 |
| 0.05:6:true | step 15, IT 2.44e-4 | step 15, LS 0.905 | step 15, LS 2.63e-9 |
| 0.02:4:false | step 138, LS 8.63e-5 | step 138, LS 8.63e-5 | step 138, LS 1.28e-4 |
| 0.02:4:true | step 138, LS 4.09e-7 | step 138, LS 4.13e-7 | step 138, ISL (497 / 527) |
| 0.02:5:false | step 135, IT 1.75e-8 | step 135, IT 1.75e-8 | step 135, IT 6.90e-9 |
| 0.02:5:true | step 136, IT 2.86e-8 | step 136, IT 7.63e-9 | step 135, IT 1.48e-7 |
| 0.02:6:false | step 134, IT 2.41e-3 | step 134, IT 2.41e-3 | step 134, LS 0.0818 |
| 0.02:6:true | step 135, IT 1.87e-7 | step 135, IT 2.01e-7 | step 135, IT 2.24e-6 |
| Transients passed | 0/12 | 0/12 | 0/12 |

**The 50 mm cap exit does not pass under any form.** No 50 mm case completes the 1.5-1.6 s interval under A or B. Under old and A, 0.05:4:false completes it and then fails in 1.6-1.7 s.

**Substeps (accepted/rejected) on the trajectory lines.** The 1.4 s line covers the 1.3-1.4 s interval, and so on.
- 50 mm: identical under all three forms except 0.05:5:false at 1.4 s (old 10/5, A and B 9/4), 0.05:6:false at 1.4 s (old 13/8, A and B 10/6), and 0.05:4:false at 1.6 s (old 44/24, A 42/24, B has no line because it fails in that interval). The 1.5 s lines: 4:false 9/5, 5:false 13/6, 6:false 14/5, and the three unequal cases 1/0, under every form.
- 20 mm: the 8.0 and 9.0 s lines are identical under all forms: 1/0 everywhere except 0.02:6:false at 8.0 s, which is 3/1. The probe prints only every tenth interval there. The 10-13 substeps that 7.1 attributes to 8.x s are therefore **not measured** by these runs.

**Closure guard.** "Velocity constraint did not close" fired in no run under A or B (0 occurrences in the run 22 and 23 XML).

**Where the 50 mm runs actually stop** (runs 24 and 26, 0.05:4:false, every failed pass dumped):
- **old (run 24):** the interval 1.5-1.6 s contains two failed passes. The first is the 7.1 stall: edge 0 cap row, 8.89e-5, q = 0.12164 against cap 0.12075. The second is already at rest: tanks 1e-4 Pa above the void, flows 7e-7 to 1.7e-6 kg/s, residual 8.1e-4 in the junction mixing rows. Refinement survives both (44/24 substeps). The **terminal** failure, in 1.6-1.7 s, is 27 failed passes at rest:
  - Every refinement level starts from the same point: |f| = 1.14e-9 on tank rows (tolerance 1e-9).
  - Each ends at 4.1e-3 in junction row local 0, with junction inflow 5.3e-7 kg/s and edge flows 1.6e-7 to 3.7e-7 kg/s.
  - This is the 7.2 mechanism, not the cap exit. 7.1 read the first dumped failed pass of run 16 (dump budget 2), and refinement survives that pass.
- **B (run 26):** there is no failed pass at cap-level flow. All 31 failed passes are in the approach to rest within 1.5-1.6 s: all edges passive, q = 2.5e-4 to 1.6e-3 kg/s against caps of 0.12 to 0.21, tanks 0.06-0.14 Pa above the void.
  - At the terminal passes the start-point residual sits in tank 1's energy and volume rows (local 2 and 3, +1.42192e-9 and -1.42195e-9) and in tank 0's (5.5e-10).
  - It does not shrink as refinement drives dt from 2.3e-4 down to 1.3e-12 s. The Newton lowers it only to 1.345e-9, against the 1e-9 tolerance. The junction rows stay at 1e-11.
  - The same start-point floor, 1.14e-9 on the same rows, is visible in old's terminal passes. There the Newton wanders into the junction rows instead.
  - Why an accepted state carries a 1.1-1.4e-9 energy/volume inconsistency that the Newton cannot remove is **not measured**. A candidate is the gap between the 1e-8 reconstruction gate and the 1e-9 Newton tolerance, and/or an evaluation-noise floor in those rows (*hypothesis*).

**Verdict on 7.1.** The mechanism is real but it is not what stops the 50 mm runs.
- *What is confirmed:* the cap-branch scale mismatch produces a failed pass at the exit (run 24's first failed pass, stalled at 8.9e-5 on the cap row). Form B removes it: run 26 has no failed pass anywhere near the cap. Form A, same units but divided by pressureScale, changes nothing measurable. Its failures are at the same steps with residuals of the same orders, 0.05:4:true hits the interval substep limit, and 0.05:5:true and 0.05:6:true end at 0.90 instead of 2.7e-9 and 2.4e-4. So scaling the cap branch into pressure units is not enough; B's gain comes with the ~80x re-weighting of the hydraulic rows.
- *What is refuted:* the 50 mm runs stop at the cap exit. In the old form, refinement already survives it. The terminal failure is at rest in the junction mixing rows (7.2), one interval later.
- *Under B:* every 50 mm case now fails in step 15, at 1.35 to 3.15 times the tolerance (1.35e-9 to 3.15e-9), on a start-point residual floor in the tank energy/volume rows that no step size removes. B is the better form: static 31/32, and the 50 mm residuals are 1e-9 instead of up to 4.5e-3. But neither form fixes a single transient case.
- *The ordering in 7.4 changes:* item 2 is not decisive for the 50 mm runs. The work that decides them is 7.2/7.4 item 3 (the rest mechanism) and the new start-point floor above, which has to be explained before any rest fix can be judged on these probes.

### 7.6 Owned-holdup experiment (runs 27-39)

Run 2026-09-25 in the same worktree at 9674bf1. Runs 27-36 were made by the previous agent (Opus 5.5) before its session was cut off; runs 37-39 and this section by Claude (Fable 5.1) finishing the task from the stored XML files, the uncommitted diff and three further dump runs, one Gradle call each, no dev client. The complete diff is `tools/junction-holdup-prototype/holdup-prototype-owned-holdup.patch` (five tracked files; it contains the cap-minform and balanced-seed patches, apply it alone). The sources were restored afterwards and nothing was committed. All numbers below are read from the `HOLDUP_*` and `JDUMP` lines of `run27`-`run39` in that folder.

**(a) What the prototype changed**, in the structure of 7.4 item 3:

1. *Rows read the graph inventory.* `junctionInflow` builds the incoming fractions as `(incoming_c + held_c/dt) MW_c / (Q + m/dt)` and the incoming enthalpy as `(incomingEnergy + E_old/dt)/(Q + m/dt)`, with `held` the graph inventory's moles, `m` its mass and `E_old = m h_old` its energy field, stored in enthalpy form. `nodeSolidRows` does the same for the solid moments. `junctionWeight = Q + m/dt` (flux form, default on) multiplies the mixing rows in `PhaseLayout.junctionRows`; the pressure row (net mass flow, or the retained pressure of an isolated junction) and the amount row are unchanged, so the junction mass stays constant and pressure stays algebraic. With the holdup on, the junction mixes on the live upwind (`mixed = donor`) and `restateJunctions`, `donorsTurned` and `junctionsTurned` are skipped. **Note:** 7.4 item 3 said to keep the frozen donors and the directed basis in the first version; the prototype switches them off. The balanced pressure seed stays (it is only a Newton starting guess).
2. *`reconstruct` emits a junction inventory.* An owned junction is `fed` unconditionally and takes the vessel row, weight `dt |q|`, diagonal `endMass + dt outgoing = m_old + dt Q`, so the linear row is the Newton row; the projection carries `Inventory(volume, endMass w / MW, energy[node], solids)` for it, and its state keeps the Newton's free amount scale. The `JUNCTION_INFLOW_FLOOR` branch remains for holdup 0.
3. *TR-BDF2 stage carry.* The boundary loop and both stage-base loops treat an owned junction like a RESERVOIR (`node.kind()==RESERVOIR || owned && node.junction()`), so the stage bases are `n + ALPHA dt dn` and `n + A (n_stage1 - n)` for it too. Its `dn`/`du` come from the rate projection's inventory change (`rate.inventories().get(i)` minus the initial inventory, regularised form) or from its pseudo-boundary (frozen form, below). The endpoint reconstruct is called with `FROZEN_RATE`.
4. *`checkConservation` includes junctions* in the before/after moles and energy sums when the holdup is on (energy field in enthalpy form, booked like a vessel's internal energy), and the component-balance message prints before/external/after/turnover.
5. *Minting.* The probes mint through `JunctionHoldupStaticProbe.mintHoldup`: the compiler's property-guess junction state (the island's first boundary state, `PhysicalFluidTopology.compile`) is scaled to `m_J` kg at the same composition and temperature with energy `m_J h`, i.e. created from nothing, and the probes' oracle takes its initial totals after the mint. The runtime mint would still need a debit from a neighbouring owned inventory or a boundary transfer at compile, split, merge and removal, and a decision on the composition of a fitting between tanks of different gases (the probe gives it the first boundary's). Untested here.
6. *Companion estimate left out.* The order-three companion leaves the junction stock at its stage-two base and gives it no defect ("a small quantity"); the companion solve skips junction rows.

*The `frozen` rate form* (`junction.rateForm=frozen`, `-PrateForm=frozen`, `FROZEN_RATE`) acts only in rate-only solves: `solveRate` (dt = 1 on the PORT graph, the cold start-of-substep rate) and the endpoint reconstruct at dt = 1. Under `regularised` (default) those solves use the stage rows with `eps = m_J / 1 s`, so the junction stock relaxes towards the inflow mixture over a whole second inside the rate evaluation, and `reconstruct` books `endMass w` as the junction inventory. Under `frozen` the junction rows become `w_J - w_old = 0`, `h_J - h_old = 0` (the code's comment: the eps -> infinity limit, the rate of a differential state evaluated at its own value), `reconstruct` keeps the junction at its inventory composition and books its net accumulation over dt (`sum dt |q| w_donor` in minus out, and the energy change) as a pseudo-boundary at the junction, which `TrBdf2StepSolver` reads like a port boundary into `dn`/`du`. *Why it was introduced (inference, the previous agent left no note):* under `regularised` every 20 mm case fails at the restart (steps 100-108) with "Embedded boundary error" of 1.0e-3 to 2.3e-3 (runs 27-29), and the boundary dumps of runs 30/31 (0.02:6:false, 1e-5 kg) put the discrepancy on the void ids 5 and 6, energy and nitrogen: coarse -8.0264 against estimate -8.0690 (0.53 %), nitrogen 1.5 % to 2.4 %, on every substep of the injection. The embedded companion's boundary ledger weights the start-of-step rate projection's boundaries with `e0 dt`; when that projection comes from a one-second backward-Euler relaxation of the junction stock, it is not a rate, and the estimate cannot agree with the coarse step. The frozen form makes the rate evaluation a rate. Measured effect: 20 mm at the restart goes from 0/6 (regularised, all three masses) to 6/6 (frozen, 1e-4 kg).

**(b) Results.** Regularised = runs 27/28/29, frozen = runs 34/35/36; cap form B throughout; IT = Newton iteration limit, LS = line search stalled, ISL = interval substep limit (accepted/rejected), EBE = embedded boundary error, SRE = step refinement error, NEG = "Negative/nonfinite inventory". The static matrix: 31/32, 30/32, 30/32 regularised and 31/32, 31/32, 30/32 frozen. The frozen 1e-5 and 1e-4 static failure is 3:150:swap:unequal at IT 2.19e-5, the same case and residual as run 23 (B, holdup 0), so the holdup does not change the static matrix at those masses; regularised fails the same case at 6.1e-4, 1e-4 also loses 4:150:swap:unequal ("No finite Jacobian trial"), and 1e-6 loses 3:150:no-swap:unequal to an ISL under both forms.

| Case | reg 1e-5 (27) | reg 1e-4 (28) | reg 1e-6 (29) | frozen 1e-5 (34) | frozen 1e-4 (35) | frozen 1e-6 (36) |
|---|---|---|---|---|---|---|
| 0.05:4:false | 100 IT 5.3e-9 | 100 IT 1.2e-7 | 100 IT 2.4e-6 | 100 IT 1.06e-9 | 100 IT 1.30e-5 | **PASS** |
| 0.05:4:true | 100 IT 7.9e-7 | 100 IT 5.6e-6 | 100 IT 1.1e-6 | **PASS** | **PASS** | 100 IT 2.2e-7 |
| 0.05:5:false | 100 IT 398 | 100 IT 537 | 100 ISL (760/265) | 15 IT 7.0e-9 | 15 IT 6.94e-9 | 15 IT 7.7e-9 |
| 0.05:5:true | 15 IT 7.8e-8 | 15 IT 2.0e-9 | 100 ISL (760/265) | **PASS** | **PASS** | **PASS** |
| 0.05:6:false | 16 ISL (837/241) | 15 IT 1.6e-8 | 15 IT 3.8e-8 | 15 IT 3.0e-8 | 16 ISL (519/505, 494 IT) | 15 IT 3.0e-8 |
| 0.05:6:true | 16 IT 7.8e-7 | 16 IT 4.5e-8 | 15 IT 1.4e-5 | 16 ISL (828/241) | 16 ISL (808/244, 235 SRE) | 15 IT 4.1e-6 |
| 0.02:4:false | 108 ISL (EBE) | 100 EBE 1.3e-3 | **PASS** | **PASS** | **PASS** | **PASS** |
| 0.02:4:true | 100 ISL (EBE) | 100 ISL (EBE) | 102 ISL (NEG 57, EBE 303) | **PASS** | **PASS** | 103 ISL (NEG 516) |
| 0.02:5:false | 100 ISL (EBE) | 100 ISL (EBE) | 102 ISL (EBE) | **PASS** | **PASS** | 103 ISL (NEG 516) |
| 0.02:5:true | 100 ISL (EBE) | 100 ISL (EBE) | 100 ISL (EBE) | **PASS** | **PASS** | 102 ISL (NEG 516) |
| 0.02:6:false | 100 ISL (EBE) | 100 ISL (EBE) | 100 ISL (EBE) | 141 IT 7.4e-9 | **PASS** (12.0 s) | 102 ISL (NEG 516) |
| 0.02:6:true | 100 ISL (EBE) | 100 IT 6.1e-9 | 100 ISL (EBE) | **PASS** (40 s) | **PASS** (11.7 s) | 137 ISL (470 IT) |
| Passed | 0/12 | 0/12 | 1/12 | 7/12 | **8/12** | 3/12 |

(The brief for this task quoted run 36 as 4/12; the XML has 3/12.) Step numbers are the failing 0.1 s interval: 15/16 = the 50 mm approach to rest at 1.5-1.7 s, 100-108 = the restart, 137-141 = the second approach to rest.

`HOLDUP_METRICS`, the probe's own ledger (tank inventories + junction stock + boundaries), max component / energy error per case:

| Case | 27 | 28 | 29 | 34 | 35 | 36 |
|---|---|---|---|---|---|---|
| 0.05:4:false | 4.2e-15 / 2.1e-15 | 3.3e-15 / 1.9e-15 | 2.8e-15 / 1.3e-15 | 2.3e-15 / 1.0e-15 | 2.5e-15 / 1.0e-15 | 1.1e-14 / 2.3e-15 |
| 0.05:4:true | 3.1e-15 / 7.9e-15 | 5.0e-15 / 1.5e-14 | 4.1e-15 / 9.8e-15 | 7.1e-15 / 2.6e-15 | 3.2e-15 / 6.5e-15 | 2.1e-15 / 4.6e-15 |
| 0.05:5:false | 3.0e-15 / 1.5e-15 | 2.5e-15 / 1.5e-15 | 2.5e-15 / 2.5e-15 | 1.7e-15 / 9.9e-16 | 1.4e-15 / 1.1e-15 | 1.1e-15 / 6.1e-16 |
| 0.05:5:true | 9.2e-16 / 1.7e-15 | 9.2e-16 / 1.0e-15 | 4.0e-15 / 2.0e-15 | 8.2e-15 / 4.2e-15 | 5.4e-15 / 5.4e-15 | 3.9e-15 / 9.2e-15 |
| 0.05:6:false | 1.7e-15 / 1.6e-15 | 7.6e-16 / 2.1e-15 | 8.6e-16 / 3.8e-16 | 2.1e-15 / 6.4e-16 | 3.2e-15 / 7.0e-16 | 1.3e-15 / 1.2e-15 |
| 0.05:6:true | 3.2e-15 / 8.4e-16 | 2.1e-15 / 1.0e-15 | 7.6e-16 / 7.3e-16 | 3.2e-15 / 1.9e-15 | 2.8e-15 / 2.2e-15 | 2.1e-15 / 1.3e-15 |
| 0.02:4:false | 2.0e-15 / 1.9e-15 | 2.8e-15 / 1.2e-15 | 2.3e-15 / 2.7e-15 | 2.1e-15 / 9.0e-16 | 1.9e-15 / 1.9e-15 | 2.8e-15 / 3.1e-15 |
| 0.02:4:true | 1.2e-15 / 2.4e-15 | 2.3e-15 / 2.2e-15 | 2.8e-15 / 1.3e-14 | 2.6e-13 / 2.0e-14 | 1.3e-14 / 1.2e-14 | 2.0e-14 / 2.6e-15 |
| 0.02:5:false | 2.8e-15 / 7.8e-16 | 2.5e-15 / 1.4e-15 | 2.8e-15 / 1.0e-15 | 2.7e-13 / 3.2e-14 | 1.3e-14 / 9.3e-15 | 3.4e-15 / 6.0e-15 |
| 0.02:5:true | 2.2e-15 / 2.4e-15 | 1.3e-15 / 3.2e-15 | 1.8e-15 / 1.1e-15 | 2.5e-13 / 3.8e-14 | 1.7e-14 / 1.3e-14 | 7.1e-15 / 6.4e-14 |
| 0.02:6:false | 2.4e-15 / 1.1e-15 | 1.9e-15 / 1.2e-15 | 2.0e-15 / 1.5e-15 | 2.7e-13 / 2.8e-14 | 2.3e-14 / 2.7e-14 | 7.1e-15 / 6.3e-15 |
| 0.02:6:true | 2.0e-15 / 9.9e-16 | 1.5e-15 / 2.2e-15 | 1.9e-15 / 1.6e-15 | 2.5e-13 / 2.7e-14 | 1.9e-14 / 1.1e-14 | 2.6e-14 / 5.2e-14 |

The ledger closes to roundoff in all 72 case runs, passing and failing (max 2.7e-13 component, 6.4e-14 energy, against the probe's 1e-7 gate); the 7.3 bookkeeping defect (`checkConservation` skipping junctions, the stage carry with the initial inventory) is fixed by items 2-4.

*Junction composition on the passing 20 mm case 0.02:4:false (frozen).* The probe prints only the methane mass fraction of the junction inventory (`wJ`), of its state (`wJstate`), its mass, its temperature, the inflow `Qin` and the methane fraction of the inflow mixture from interval-average flows and end-of-interval donor states (`wMix`); the full composition is not printed. 1e-4 kg (run 35): t = 1.0 s `wJ` 0.377869 (`wMix` 0.377865), `mJ` 1.0000e-4, `TJ` 346.448 K, `Qin` 0.0668 kg/s; t = 10.0 s `wJ` 0.357016 (`wMix` 0.362003), `TJ` 317.579 K, `Qin` 0.01834 kg/s. 1e-6 kg (run 36): t = 1.0 s `wJ` 0.377875 (`wMix` 0.377871), `mJ` 1.0000e-6, `TJ` 346.441 K, `Qin` 0.0668; t = 10.0 s `wJ` 0.356485 (`wMix` 0.362109), `TJ` 317.567 K, `Qin` 0.01833. `wJ` equals `wJstate` to 1e-16 in every printed line. The `wJ`-`wMix` gap at 10 s is 5.0e-3 at 1e-4 kg and 5.6e-3 at 1e-6 kg, so it does not scale with `m_J` and is not the holdup's composition lag (`m_J/Q` = 5 ms and 0.05 ms against a 0.1 s interval); it is the probe's estimate of `wMix` from interval averages.

**(c) Dump classifications (frozen, 1e-4 kg, B, `-PjacobianDump=200`, one Gradle call each, runs 37-39).** Each reproduces its run 35 case bit for bit.

- **0.05:5:false (run 37), step 15 = 1.5-1.6 s.** 17 failed passes, all at `dt=1.0` on the PORT graph: the rate-only solve (`solveRate`) at the start of a substep, which does not depend on the substep, so the "Substep refinement exhausted" wrapper re-solves the identical problem 16 times (16 dumps bit-identical, IT 6.941881547757138E-9). Start point: warm-start flows 0.1626 / 0.2835 / -0.1487 / -0.1487 / -0.1487 kg/s against caps 0.1200 / 0.2143 / 0.1344 (35 % above cap), `initial |f|max` 0.887 on edge 0's row, -0.864 edge 1, 0.60 edges 2-4; ports 0 and 1 at 101355.44 and 101334.63 Pa (30 and 10 Pa above the 101325 Pa voids), junction P 101332.64, T 319.56 K; junction rows 1e-16 / 0 / -1.7e-12 / 0. Failed point: flows 0.02106 / 0.00715 / -0.01223 / -0.01223 / -0.00374 kg/s, `|f|max` 6.94e-9 on **edge 4's hydraulic/cap row** (the void at z = +1 m), -3.83e-9 on edge 1, edges 2/3 -4.3e-11, edge 0 1.1e-11; junction rows 0 / 3.0e-18 / -9.8e-17 / 0; junction P 101332.644. The one different pass (LS at 0.5000004662) has all edge rows at -0.5000, which is form B's cap branch `(limitDrop - loss)/(2 limitDrop)` evaluated at zero loss. *Classification:* not the junction rows, not tank rows (a rate solve has none); an edge-row residual floor at 7x the tolerance in the dt-independent rate solve, on form B's re-weighted hydraulic/cap rows, reached from a stale cap-level warm start. Cap-related in the sense of 7.5 (form B), not the 7.1 cap-exit stall (the flows are at 10 % of cap).
- **0.05:6:false (run 38), step 16 = 1.6-1.7 s** (the 1.5-1.6 s interval passed with 104/53 substeps). 200 dumps of the 496 Newton failures: the first two, at dt = 3.07e-5 s in 1.5-1.6 s, are the 7.1 cap exit (edge 0 q = 0.1205769818362 against cap 0.1205769818363, LS 2.9e-5, tank 0 at 101999 Pa) and refinement survives them; the other 198 are stage solves at dt = 1.9655731187874232E-5 s, IT 2.9926e-8 to 2.9930e-8. Start point (the same to 12 digits on all 198): tanks 0 and 1 and the junction at **101317.527 Pa, 7.47 Pa below the voids** (the z = -1 m void's gas column: rho g h = 0.756 x 9.81 x 1 = 7.4 Pa), every flow 0 (edges 0/1 at 1e-14), the four void edges CLOSED in the accepted state but PASSIVE in pass 0, `initial |f|max` 8.50e-3 on edge 4's row (z = +1 m void: 7.5 + 9.6 Pa the wrong way), 3.73e-3 on edges 2/3, 2.7e-5 on edge 5, junction energy row 5.7e-12, tank rows below 3.7e-12. Failed point: a backflow from voids 2/3/4 (0.00682 / 0.00682 / 0.01794 kg/s) into the tanks (0.01053 each) and void 5 (0.01051), junction P 101322.92, `|f|max` 2.99e-8 on **edge 5's hydraulic/cap row** (the void at z = -1 m), 3.54e-9 on edges 2/3, 8.8e-11 on edges 0/1, junction rows 7.7e-11 / -1.7e-18 / -1.7e-11 / -1.1e-16, tank rows below 7.7e-11. The interval creeps (519 accepted substeps over 0.0224 s, every other attempt failing) to the substep limit. *Classification:* other: an active-set/cap-row failure on the void edges after the tanks undershoot the void pressure by the gravity head of the lowest void; pass 0 opens the blocked void edges, the Newton converges to a backflow solution to 3e-8 on form B's edge rows and stops there. Not the junction rows (1e-11), not the tank rows (below 1e-10), and not the 7.5 floor.
- **0.05:4:false (run 39), step 100 = the restart.** 20 failed passes, all at `dt=1.0`: again the rate-only solve, now at the first substep of the injection (the `RateKey` cache misses because the graph gains a scheduled transfer), 19 bit-identical at IT 1.2973803207713445E-5. Start point: warm-start flows 0.12355 / 0.20808 / -0.16582 / -0.16582 kg/s against caps 0.1200 / 0.2142 / 0.1643, identical to six digits to run 33's start point at 1e-5 kg (0.12355273 / 0.20807677 / -0.16581475) and to the 1.4-1.5 s interval-average flows, i.e. the rate solver's own last solution from the cap exit 8.5 s earlier (`startPoint`: `previousAvailable` reuses `previousFlows` whenever the pipe identities and node ids match; the rate solver had not run since, the endpoint rates coming from the cache and the reconstruct). Ports at 101324.99969 Pa (3e-4 Pa below the voids), junction 101324.99969, `initial |f|max` 0.472 on edge 1's row, junction rows 5.6e-17 / 0 / -4.8e-9 / 2.2e-16. Failed point: flows -6.3e-5 / 3.11e-4 / -1.24e-4 / -1.24e-4 kg/s (tank 1, which receives the 0.5 mol/s methane, feeds the junction), junction P 101325.0047, `|f|max` 1.297e-5 on **edge 1's hydraulic/cap row**, 1.58e-6 on edges 2/3, 7.1e-7 on edge 0, junction rows -5.6e-17 / -9.8e-17 / 3.0e-17 / -2.2e-16. *Classification:* not the junction rows, not tank rows (none); a rate-solve Newton that does not converge (1.3e-5, four orders above the tolerance, so not a floor) from a warm start four orders away from the solution; cap-related only through form B's edge rows. Compare run 33 (regularised, 1e-5 kg, same case and step): also 20 failed passes at dt = 1 from the same warm start, but there the residual is 5.29e-9 in the **junction mixing row** at Q = 4.4e-6 kg/s, the 7.2 mechanism; under frozen the junction rows are exactly satisfied and the failure moves to the edge rows.

**(d) The 1.42e-9 start-point floor of 7.5** does not appear in any of the 237 dumped passes of runs 37-39. Runs 37 and 39 fail in rate-only solves, which have no tank rows and start at 0.887 and 0.472 on the edge rows. Run 38's stage solves start with the tank rows below 3.7e-12 and end with them below 7.7e-11. The interval that carried the floor in run 26 (0.05:4:false, 1.5-1.6 s) now passes under frozen 1e-4 (trajectory line at 1.6 s, 29/13 substeps) and its failure has moved to the restart. Whether the floor is gone or merely no longer on a failed pass is not measured: the dumps cover failed passes only.

**(e) Verdict on 7.4 item 3, measured numbers only.**

- The bookkeeping part is done and closes: 72/72 case runs at 2.7e-13 or better with the junction stock inside the oracle (the 7.3 defect).
- The rest mechanism of 7.2 is removed on the 20 mm probes by the owned holdup **with the frozen rate form**: 6/6 at 1e-4 kg through the first approach to rest, the restart and the second approach to rest (Qin 1e-15 kg/s at 2-10 s with `wJ` constant to 1e-9; substeps 1/0 on every rest line), 5/6 at 1e-5 kg (0.02:6:false, step 141, IT 7.4e-9), 1/6 at 1e-6 kg. Every earlier configuration in this folder scored 0/6 or 1/6 on these six. The regularised rate form alone scores 0/6 at every mass: the 20 mm cases get through the first rest (they no longer fail at steps 134-138) but fail the restart on the embedded boundary error. So item 3 needs a rate-evaluation rule the plan did not name, and the frozen form is the one that works.
- 1e-6 kg is too small for the stage carry: the 20 mm restarts reject 516 of 1024 attempts with a negative junction inventory (the base `n + ALPHA dt dn` with dn = 8e-3 kg/s of methane against a 4e-7 kg methane stock; inference from the numbers). 1e-5 kg and above, as 7.4 item 3 specified, and 1e-4 kg scores best.
- The 50 mm probes are not decided by item 3: frozen 1e-4 passes 2/6 (the two unequal-feed cases 4:true and 5:true), and none of the four dumped or listed failures is in the junction rows (frozen rate solves 1e-16, stage solves 1e-11). Two of the four (5:false at 1.5 s, 4:false at the restart) fail in the rate-only solve, which the substep refinement cannot touch, from a warm start that is the rate solver's own stale solution; one (6:false) fails on the void edges after a 7.4 Pa gravity-head undershoot; one (6:true) is 235 step-refinement rejections that the Jacobian dump does not capture.
- Static: frozen 1e-5/1e-4 = 31/32 with the same failing case and residual as run 23, so the holdup neither helps nor hurts the fixed-boundary matrix.

*Open:*
1. The remaining 50 mm failures: the rate-only solve's warm start (`previousFlows` reused across seconds of rest; candidate, not tested), form B's edge-row residual floor at 3e-8 to 7e-9 in those solves, the void-edge active set after a gravity-head undershoot, and the 0.05:6:true step-refinement rejections (not dumped).
2. The runtime mint at `PhysicalFluidTopology.compile` (debit or boundary transfer; composition choice; split/merge/removal), a checkpoint-format question under the no-compatibility rule.
3. The phantom-trace gate (documentation/JUNCTION_PHANTOM_TRACE.md): not run, and the prototype disables the frozen donors and the directed basis that 7.4 item 3 said to keep for it.
4. The exact regression and the fluid gate suites: not run; only the two probes were executed under this patch.
5. Timing: the passing 20 mm cases take 0.3-0.5 s per 16 s case at 1e-4 kg except 0.02:6:* at 12 s, and 0.02:6:true took 40 s at 1e-5 kg; nothing compared against the base, and no in-game run.
6. The companion estimate still gives the junction no defect; whether that matters for the step-size control is not measured.

### 7.7 Rate-solve warm start (runs 40+)

Run 2026-09-25 in the same worktree at 9674bf1 by Claude (Opus 5.5): `holdup-prototype-owned-holdup.patch` plus one switch, nine sequential Gradle calls (runs 40-48), no dev client, nothing committed, sources restored afterwards. The full diff is `tools/junction-holdup-prototype/holdup-prototype-warmstart.patch` (it contains the owned-holdup patch; apply it alone). Every probe run uses `-PjunctionHoldup=1e-4 -PcapForm=B -PrateForm=frozen`.

**(a) Why the rate solve's warm start is stale (code, line numbers at 9674bf1, confirmed by the trace runs 41 and 48).**

1. TR-BDF2 owns two step solvers, and the start-of-step rate is solved only by the second one, only on a cache miss. `TrBdf2StepSolver.java:32`: `implicit=new PassiveStepSolver(model,ownership,transport);algebraic=new PassiveStepSolver(model,ownership,transport);`. Lines 73-80: `var cached=endpointRates.get(new RateKey(initial,PassiveStepSolver.Acceptance.FULL));` ... `if(cached==null) { var solved=algebraic.solveRate(portGraph,checkpoint,acceptance); ... cache(initial,rate,initialFlows,initialModes,acceptance); }`. `algebraic` has no other caller, so its `previousFlows` move only when the cache misses.
2. A hit is the normal case. Every finished step caches its endpoint rate from a reconstruction, not from a solve (line 154: `cache(endpoint,endpointRate,second.massFlows(),second.modes(),acceptance);`), and the next step starts from an equal graph. `RateKey` holds the whole `PassiveNetwork` record, and its states compare their arrays by reference. It still hits, because the accepted graph reuses the endpoint's `State` objects.
3. A miss happens in three places. The trace (`RATEMISS`) shows all three in 0.05:4:false (run 48):
   - At t = 0 the cache is empty.
   - **Eviction.** Line 233: `if(endpointRates.size()>=8)endpointRates.remove(endpointRates.keySet().iterator().next());`. The map is insertion-ordered and every trial that finishes caches its endpoint, rejected trials included. After eight insertions the accepted point's own rate is gone, and the next attempt from it misses (`everCached=true cacheSize=8`, in 1.4-1.5 s in 4:false and in 1.5-1.6 s in 5:false/6:false/6:true).
   - **A new key.** The injection at the restart adds a scheduled transfer to the graph (`PassiveNetwork(reservoirs,pipes,scheduledTransfers)` is the key). The same node states are cached, but under a different key (`everCached=false transfers=1`). A failed rate solve never caches, so every refinement level misses again and re-solves the identical dt = 1 problem: 20 times in run 48, at dt 0.1, 0.05, 0.025, and so on.
4. `startPoint` checks only whether the history belongs to the same *structure*, not the same *state*. `PassiveStepSolver.java:934`: `boolean previousAvailable=previousPipes.equals(pipeIdentities)&&Arrays.equals(previousNodeIds,nodeIds(graph));`. Line 937: `double[] estimate=warmFlow&&!previousAvailable?initialMassFlows(graph):null;`. Line 953: `flows[i]=previousFlows[i];heads[i]=previousHeads[i]/1e5;headSet[i]=true;`. `previousFlows` are written only by the solver that ran (lines 358 and 372). For `implicit` they are one stage old, which is what the warm start was designed for. For `algebraic` they are the last cache miss's solution, however old.
5. Measured age (runs 41 and 48, `RATEWARM`):
   - 0.05:4:false: the rate solver runs three times in 10 s.
     - The 1.4-1.5 s solve warm-starts from the t = 0 flows (max pressure change 47.7 kPa). It converges to 0.12355/0.20808/-0.16582/-0.16582 kg/s.
     - The restart solve at 10.0 s starts from exactly those flows, 3.94 kPa and 8.5 s away. `initialMassFlows` of the current states is -1.7e-8/1.7e-9/4.7e-6/4.7e-6 kg/s.
   - 0.05:5:false at 1.5 s starts from flows 48.7 kPa old (0.163/0.283/-0.149). `initialMassFlows` is 0.0211/0.0072/-0.0122/-0.0122/-0.0037, which is the run 37 failed point to three digits.

So the diagnosis of 7.6 holds as stated: stale warm start, from the separate `algebraic` instance, reached by eviction or by a new transfer key, and preferred because `previousAvailable` ignores the state.

**(b) Rule implemented: candidate (a).** `junction.rateWarmStart=fresh` (Gradle `-PrateWarmStart`, default `previous` = unchanged):
- Every write of `previousFlows` now also records the solve's input node states.
- In a rate-only solve, `startPoint` sets `previousAvailable=false` unless those states are value-identical to the current input states (T, P, mass, volume, enthalpy, phase amounts), so `initialMassFlows(graph)` is used. Code: `if(rateOnly&&RATE_WARM_FRESH&&!recordedHere)previousAvailable=false;`.

Why (a) and not (b) or (c):
- It is the narrowest rule that removes every stale case: a rate-only solve still continues from its own flows on a later active-set pass of the same solve, and on an exact re-solve. Run 43 shows the first: after a fresh start, 4 further passes warm-start with `sameState=true`.
- (b) would tie the rule to the TR-BDF2 cache and miss the other rate caller (`SolidEventIntegrator.java:71`).
- (c) would also drop the pass-to-pass continuation.

Scope:
- Stage solves (`rateOnly == false`) are untouched.
- The pump-mode carry (`previousModes`, `carryModes`) is not changed. It uses the same structure-only test, but no probe or gate fixture exercises a pump through a rate solve here.
- The trace switch (`-PrateWarmStartTrace=true`) only prints.

**(c) Results, run 40 (`fresh`) against run 35 (`previous`), same patch otherwise.**

| Case | run 35 | run 40 |
|---|---|---|
| Static 32-case matrix | 31/32 (3:150:swap:unequal IT 2.19e-5) | 31/32, all 32 lines identical to run 35 apart from ms |
| 0.05:4:false | step 100 IT 1.30e-5 | **PASS** (1.7 s) |
| 0.05:4:true | PASS | PASS |
| 0.05:5:false | step 15 IT 6.94e-9 | **PASS** (1.4 s) |
| 0.05:5:true | PASS | PASS |
| 0.05:6:false | step 16 ISL (519/505, 494 IT) | step 15 IT 1.02e-8 (rate solve) |
| 0.05:6:true | step 16 ISL (808/244, 235 SRE) | step 16 ISL (525/501, 488 IT 2.51e-9, 13 SRE) |
| 0.02:4:false ... 0.02:6:true | 6/6 PASS | 6/6 PASS |
| Transients passed | 8/12 | **10/12** |

Details:
- The printed trajectory lines match run 35 bit for bit until the first rate solve whose start changed, then differ in the last digits:
  - from the 11.0 s line in 0.02:4:false, 0.02:4:true, 0.02:5:true, 0.02:6:false and 0.05:4:true (the restart);
  - from the 10.0 s line in 0.02:6:true;
  - from the 8.0 s line in 0.02:5:false;
  - from the 1.0 s line in 0.05:5:true.
- The probe ledger closes in all 12 cases (max 2.6e-14 component, 2.2e-14 energy).
- The junction mass drifts at the 1e-7 relative level after the restart in both runs (for example 0.02:4:false ends at 9.99999824e-5 kg in run 40 and 1.00000000e-4 in run 35; 0.05:5:true ends at 9.9999895e-5 in run 35). It is booked conservatively, and it is a property of the frozen pseudo-boundary taking the rate solve's converged junction mass residual. It is not introduced by this switch.
- 0.02:6:* take 15.1/15.5 s against 12.0/11.7 s in run 35. These are single timings, not repeated.

**(d) Dump classification of what is left (runs 42 and 43, `fresh`, dump 200).** Both failures are one mechanism, the run 38 one, and neither involves the warm start.
- **0.05:6:false, step 15 (run 42).**
  - Where it fails: 17 of 19 dumped failures are rate solves at dt = 1 in 1.5-1.6 s, after an eviction miss. The first eviction-miss rate solve in that interval converges from its fresh start; the second fails.
  - Start point: tanks at 101317.52 Pa, 7.48 Pa below all four voids (the z = -1 m void's gas column), junction at the tank pressure, `initial |f|max` 0.561 on the junction mass row, all void edges PASSIVE.
  - Failed point: the Newton heads for a backflow out of voids 2/3/4 (0.0076/0.0076/0.0183 kg/s) into the tanks and void 5 (junction at 101322.49 Pa). A void may only receive (`boundaryAllowed`), so this is an illegal branch. The Newton stops at 1.0244e-8 on edge 1's (tank 1) hydraulic/cap row; the other edges are at 1e-9 to 6e-11 and the junction rows at 8e-13. Flows are at 5-10 % of cap.
  - Every refinement re-solves the identical rate problem.
- **0.05:6:true, step 16 (run 43).**
  - Where it fails: 192 of 200 dumps are stage solves at dt = 1.006e-4 s, IT 2.506e-9 to 2.576e-9 (tolerance 1e-9). Only 3 rate solves happen in the whole case.
  - Start point: tanks and junction at 101318.41 Pa (6.6 Pa below the voids), all flows 0, void edges CLOSED in the accepted state but PASSIVE in pass 0, `initial |f|max` 8.1e-3 on edge 4's row (the z = +1 m void).
  - Failed point: again a backflow from voids 2/3/4 (0.0056/0.0056/0.0174 kg/s). The Newton stops at 2.506e-9 on edge 5's row (the z = -1 m void); edges 2/3 are at 1.8e-10, junction rows below 3e-11 and tank rows below 1e-10. This is run 38's classification, one case over.
- *Classification:* the void-edge active set after a gravity-head undershoot.
  - Pass 0 of every solve reopens the void edges the accepted state had closed; `boundaryClosed` is built from `blockedDirections` only, and closed passive modes are not carried.
  - The all-open active set has a boundary-illegal backflow root. The Newton reaches 2.5e-9 to 1.0e-8 on form B's edge rows, 2.5-10x the tolerance, and never converges, so the pass loop never gets to close the illegal edges.
  - It is not the junction rows, not the tank rows, not the warm start and not the cap exit. Whether the stall is a noise floor of form B's re-weighted rows or a slow Newton tail is **not measured**: the dump records the first and last iterates only.

**(e) Product gates (the 38 tests of the handoff list).**
- **Run 44: the warm-start patch, every property at its default, no init script.** 38/38 pass: FilterBlockLineIsland 11/11, NetworkRegime 9/9, PassiveStepSolver 6/6, DeadHeadedLineIsland 4/4, PhysicalFluidTopology 4/4, PipePresentation 3/3, PumpJunctionStartup 1/1.
  - This is pass/fail neutrality only; no bitwise comparison against the base was made.
  - The balanced seed is **not** behind a switch in this patch (`seedBoundaryJunctions` runs in every solve), so "every property at its default" still includes it.
- **Run 45: init script, 1e-4 kg, B, frozen, `fresh`.** 28/38; 10 fail:
  - FilterBlockLineIslandTest (5 fail):
    - `aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn`: "A pumped line must settle at its own shutoff pressure ==> expected: <601325.0> but was: <601328.4942241239>".
    - `filterLineToAVoidIntegratesWithSolidsAndDrivingPressure`: "HELD at interval 1/1 (t=0 s): Solid population balance failed".
    - `filterLineToAVoidIntegratesWithModerateSolidsAndPressure`: "HELD at interval 1/1 (t=0 s): Solid population balance failed".
    - `valveLineToATankKeepsIntegratingOnceTheTankIsFull`: "HELD at interval 1/40 (t=0 s): Substep refinement exhausted: Newton line search stalled at residual 182354.43679870095; active-set pass=0".
    - **the phantom-trace gate** `filterLineToATankKeepsIntegratingOnceTheTankIsFull`: **FAIL**, "HELD at interval 1/40 (t=0 s): Interval substep limit; no partial interval may commit: advanced=4.526711904070401E-4 of 5.0 s, accepted=499, rejected=526, reasons={Step refinement error #=3, Newton line search stalled at residual #; active-set pass=#=515, Newton iteration limit at residual #; active-set pass=#=8}", at the first swept pressure (150 kPa).
  - NetworkRegimeTest (2 fail):
    - `twoDifferentFeedsMixAtAZeroHoldupJunction`: "No finite Jacobian trial at variable 0; active-set pass=0".
    - `reversingOneFeedSwitchesJunctionUpwindingAndSerialValvesRemainSolvable`: the same message.
  - PassiveStepSolverTest (2 fail):
    - `branchJunctionHasNoInventoryAndBalancesEveryConnectedFlow`: "Newton line search stalled at residual 2.5988422328344756; active-set pass=0".
    - `aJunctionNoOpenConnectionReachesKeepsItsPressureInsteadOfGoingSingular`: "Newton line search stalled at residual 2.636028230177307; active-set pass=0".
  - PumpJunctionStartupTest (1 fail): `waterAndAmbientCrudeStartThroughAZeroStoragePumpInletWithoutInventingUpstreamNitrogen`, "water total energy ==> expected: <-2.4650108962188363E7> but was: <-2.4651117161713272E7>".
  - DeadHeadedLineIsland, PhysicalFluidTopology and PipePresentation pass.
- **Run 46: the same with `previous`.** The same 10 tests fail with the same messages; only `valveLineToATank...` differs, an ISL at 1.68e-4 s instead of the line-search stall. **The 10 failures belong to the owned-holdup/B/frozen configuration, not to `fresh`.**
- **What engaged (run 47, trace).**
  - The holdup rows ran on every junction the fixtures build, with inventories of **0.94 to 996 kg**. That is the compiler's or fixture's copy of a whole 1 m³ boundary state (`Reservoir(id,z,state,kind)` builds `Inventory(state.volume(), totalAmounts(state), state.internalEnergy(), ...)`), with energy in internal-energy form where the prototype reads enthalpy form. Nothing minted 1e-4 kg: the prototype has no runtime mint, and `createcheme.junctionHoldup` is only a >0 switch in production code.
  - So these gates exercised an unminted, kg-scale "holdup" with the frozen donors and directed basis switched off. They did not exercise the 1e-4 kg holdup the probes measure. The phantom-trace gate is therefore **not yet measured** under the design in 7.4 item 3.
  - The balanced seed engaged once: PipePresentationTest, in a rate solve on a degree-4 junction.
  - Of 353 rate-solve start points that had a warm start, 129 switched to `fresh` (FilterBlock 78/272, NetworkRegime 51/80, PipePresentation 0/1).

**(f) Verdict.**
- The stale warm start was real and is the whole cause of two of the four 50 mm failures in 7.6. Rule (a) turns 0.05:4:false (the restart) and 0.05:5:false into passes: 8/12 becomes 10/12 at 1e-4 kg. It leaves the static matrix and the 20 mm cases' pass/fail unchanged. With the switch at its default the 38 gates pass.
- Rule (a) is a correct rule independent of the junction work: a rate solve at a new state should start from that state. It could move to production on its own, after the exact regression and the fluid gate suites are run with it on (not done here).
- The remaining two 50 mm failures are one mechanism, the void-edge active set after a gravity-head undershoot (run 38's). The next item for the 50 mm probes is there, not in the junction closure.

*Open:*
1. The void-edge active set: pass 0 reopens the void edges an accepted state had closed. Candidates to test:
   - start a pass with an edge closed when its start-point direction is boundary-illegal and the accepted state had it closed;
   - carry closed passive modes like the pump modes.

   Plus the unmeasured question whether form B's edge rows have a 1e-9 to 1e-8 noise floor (a per-iteration residual dump would decide it).
2. The endpoint-rate cache evicts the accepted point's own rate after eight rejected-trial insertions. Keeping the accepted point's entry pinned would make eviction misses rare, but that is a performance question once (a) is in; not tested.
3. The pump-mode carry uses the same structure-only test in rate solves; not exercised.
4. The runtime mint (7.6 open 2) now also decides the product gates. Until a junction's inventory is minted at m_J, the gates run the holdup rows on whole-boundary inventories and 10/38 fail in both switch states. The phantom-trace gate under the designed holdup is still open (7.6 open 3).
5. Exact regression, full fluid gate suites and timing with `fresh` on: not run.
6. The junction-mass drift of about 1e-7 relative after a restart (frozen pseudo-boundary booking the rate solve's junction mass residual): conservative, but not explained further.

### 7.8 Void-edge start closure and runtime mint (runs 49+)

Run 2026-09-25 in the same worktree at 9674bf1 by Claude (Opus 5.5): `holdup-prototype-warmstart.patch` plus two switches, eight sequential Gradle calls (runs 49, 49d, 49h, 50, 50h, 50m, 51, 53), no dev client, nothing committed, sources restored afterwards (`git status --short` empty). The full diff is `tools/junction-holdup-prototype/holdup-prototype-mint.patch` (seven tracked files; it contains the warm-start patch, apply it alone; `--check` on the restored base and `--check --reverse` on the patched tree verified). Every probe run uses `-PjunctionHoldup=1e-4 -PcapForm=B -PrateForm=frozen -PrateWarmStart=fresh`.

**(a) Part A rule: `junction.voidStart` (Gradle `-PvoidStart`, default `off`).**
- `on`: in `PassiveStepSolver.solve`, right after `closeDeadHeads` and before the reachable components and seeds are stated, `closeIllegalStarts` computes the point pass 0 starts from (`startPoint` with this solve's modes and closures) and closes every **passive** connection whose start direction `boundaryAllowed` forbids: `boundaryClosed[edge]=true`, exactly what the `illegalDirection` rule does after a converged pass.
- The direction is the start-point flow of the edge. Where that flow is `|q| <= 1e-10` (the rule's own threshold) it is the direction of `initialMassFlow` on the current states. The fallback is needed: an edge the previous accepted point had closed records exactly 0 in `previousFlows`, so a stage solve warm-started from it has no direction to read (run 43's start point: void edges at q = 0.0). Without it the rule could not fire in the run 43 case.
- Nothing is carried from the previous solve; the closure is re-derived from each solve's own start point, so an edge whose pressures turn legal opens at the next solve.
- Passive edges only, as the comment above the `illegalDirection` rule requires: before pass 0 no device has been decided, so a device edge is left to the pass loop.
- `history` (added after run 49, see (b)): the same rule, applied only once this solver has *accepted* a solve on the same structure (pipe identities and node ids recorded at the accepting return), so a first solve, whose junction pressures are the compiler's property guess or the balanced seed, is left alone.
- `-PvoidStartTrace=true` prints one `VOIDSTART` line per closure.

**(b) Part A results against run 40.**

| Case | run 40 | run 49 (`on`) | run 49h (`history`) |
|---|---|---|---|
| Static matrix | 31/32 (3:150:swap:unequal IT 2.19e-5) | 31/32, **different failure**: 4:150:swap:unequal IT 5.21e-6; 3:150:swap:unequal PASS | 31/32, the run 40 failure; all lines as run 40 except 3:150:no-swap:unequal (1/0 instead of 4/4 substeps, `in` 8e-14 kg apart) |
| 0.05:4:false | PASS 1.7 s | PASS 0.73 s | PASS 0.70 s |
| 0.05:4:true | PASS 0.65 s | PASS 0.67 s | PASS 0.66 s |
| 0.05:5:false | PASS 1.4 s | PASS 0.68 s | PASS 0.69 s |
| 0.05:5:true | PASS 1.0 s | PASS 0.63 s | PASS 0.67 s |
| 0.05:6:false | step 15 IT 1.02e-8 | **PASS** 1.4 s | **PASS** 1.4 s |
| 0.05:6:true | step 16 ISL (525/501) | **PASS** 1.2 s | **PASS** 1.1 s |
| 0.02:4:false ... 0.02:6:true | 6/6 PASS (0.02:6:* 15.1/15.5 s) | 6/6 PASS (0.02:6:* 0.55/0.65 s) | 6/6 PASS (0.47/0.56 s) |
| Transients | 10/12 | **12/12** | **12/12** |

- The ten run 40 passes stay passes. Their trajectory lines (substep counts aside) are bit-identical to run 40 up to the 1.6 s line (50 mm) and the 14.0-15.0 s lines (20 mm), i.e. until the first approach to rest where a void edge is closed at a start point. After that the tank pressures differ at the 1e-2 Pa level at rest (0.02:6:false at 16 s: 101317.581 against 101317.592 Pa). The 20 mm second approach to rest drops from 142-159 accepted substeps per interval to 1-2 (0.02:6:false), which accounts for the whole timing change (single timings, not repeated).
- 0.05:6:false and 0.05:6:true pass their 1.5-1.6 s interval with 78/43 and 80/47 substeps (the cap exit of 7.5/7.6), and 1.6-1.7 s, where they failed before, with 11/4 and 2/0.
- `HOLDUP_METRICS`, max component / energy error over all 12 cases: 2.1e-14 / 1.6e-14 (run 40: 2.6e-14 / 2.2e-14). The ledger closes in every case.
- The junction mass drift after the restart is larger in some cases: final m_J ranges from 9.99901e-5 (0.05:5:true, 9.9e-6 relative; run 40 1.5e-6) to 1.0000003e-4 (0.02:4:true). It is booked, since the ledger closes; its source is still the frozen pseudo-boundary taking the rate solve's junction mass residual (7.7 open 6).
- Run 49h's trajectory and metric lines are identical to run 49's in all 12 cases.

*The new static failure (run 49d, dump and trace).* 4:150:swap:unequal fails in the t = 0 rate solve (dt = 1, 20 failed passes, all bit-identical).
- `VOIDSTART` closes edge 1, generator 2 at 145 kPa, because its start flow is -0.3275 kg/s: the start point reads the junction at the balanced seed's 148448 Pa, a guess, not a solved pressure.
- The reduced problem then stalls at IT 5.21e-6 on **edge 0's hydraulic/cap row**: q = 0.33090819 against a cap of 0.33090643 kg/s, junction at 101962.7 Pa, junction rows 1e-15, edges 2/3 at 2.8e-6.
- Run 40 solved this case with both generators feeding (`inlets=2`). The closure is therefore a misfire: the rule read a direction off a start point whose junction pressure had never been solved.
- The cap-row stall is the 7.5 cap-exit shape, reached on the wrongly reduced problem.

The `history` variant removes exactly this: it does not fire on a solver's first solve of a structure, and it keeps 12/12.

*Verdict.* The void-edge active set of 7.7 was the whole cause of the last two 50 mm failures. Closing the boundary-illegal edges at the start point turns 10/12 into 12/12 with the ledger closed. `on`, as specified, can misfire on a first solve, where the junction pressure is a property guess. `history` costs nothing measurable against `on` and restores the run 40 static matrix, so it is the form to carry forward.

**(c) Part B: energy form of a junction inventory.** Enthalpy form, `E = m_J h`, confirmed from the code:
- The holdup row reads the field as enthalpy: `junctionInflow` returns `inventory.internalEnergy()/heldMass` as the specific enthalpy in the frozen form, and uses it as `E_old` in `(incomingEnergy + E_old/dt)/(Q + m/dt)`.
- `ConservativeTransport.reconstruct0` starts `energy[node]` at the field and moves it only by enthalpy flows (`moved*(h + g z) - moved*g z`), with no p dV term. The field therefore stays `m h` exactly when it starts as `m h`.
- `checkConservation` adds field + m g z for a junction as for a vessel. The TR-BDF2 `du` is a difference of fields or a pseudo-boundary energy; both are form-agnostic.
- The unchanged 4-argument constructor writes `state.internalEnergy()` (U = H - pV of the whole state), which disagrees with the rows by the state's pV (150 kJ for a 1 m³ junction at 1.5 bar). That was 7.7's mismatch.

Readers and writers of a junction's `inventory()`, from a grep of `inventory()` and `NodeKind.JUNCTION`/`junction()` in src/main:
- *Writers:*
  - `PassiveNetwork.Reservoir(id,z,state,kind)`, the mint; it is the only constructor that builds an inventory from a state.
  - `PhysicalFluidTopology.compile` lines 98, 111 and 125, through that constructor.
  - `ConservativeTransport.reconstruct0`: the owned `endMass*w` plus the energy ledger; in the frozen form it carries the inventory and books the pseudo-boundary.
  - `TrBdf2StepSolver` stage bases one and two (`n + ALPHA dt dn`, field + `ALPHA dt du`, and the A-blend). The companion's corrected base leaves the junction at its stage-two base.
- *Carry it unchanged:* `PassiveIntervalSolver.replace`, `InventoryEquilibrium`, `SolidEventIntegrator` and TR-BDF2's PORT conversions, and the balanced seed.
- *Readers in the solver:* `Equations.junctionInflow` (held moles, field), `nodeSolidRows` (held solids), the possible-component basis (inventory moles when the holdup is on), `checkConservation`, and the `ENGAGED` trace.
- *Readers in the runtime:*
  - `FluidCheckpointCodec` encode (372, 386-387) and decode (514-516, 844) write and read the volume, moles, field and solids of every node raw, junctions included. The enthalpy-form field round-trips unchanged; there is no form check.
  - `WorldTopologyLedger.MaterialTotal.plus`: field + m g z, the same sum `checkConservation` keeps.
  - `IslandCertificate`: the summary (60-75) skips non-RESERVOIR nodes, so a junction's inventory change is neither tested for REST/STEADY nor replayed by `graphAt` (441). The digest (335-337) hashes junction moles but not the volume.
  - `IslandCoordinator` 681 (the structure comparison skips the junction volume) and 780 (the topology audit checks RESERVOIR stock only).
  - `PhysicalRegistry`: its stock maps exclude junctions.
- *Not readers:* `PipePresentation` (pipe transfers only) and `CausalModuleCoordinator.parcel` (buffer reservoirs only).

**(d) Part B: the mint, `junction.mintHoldup` (Gradle `-PmintHoldup`, default `off`).**
- With `on` and `createcheme.junctionHoldup > 0`, the 4-argument `Reservoir` constructor gives a JUNCTION m_J kg of the state's composition:
  - fluid and solids scaled by `m_J/state.mass()`;
  - field `m_J h`;
  - volume field the state's (nothing reads it for a junction).
- This is the probes' `mintHoldup` arithmetic, so the two agree bit for bit.
- It sits in the constructor rather than inside `compile`. Every compiler mint goes through that constructor, and so do the fixtures that build junctions directly (NetworkRegimeTest, PassiveStepSolverTest, PumpJunctionStartupTest). With the mint only in `compile`, those fixtures would have kept the kg-scale inventories 7.7 found.
- m_J is the property value, the same for every fitting. A physical sizing `kappa * rho * V_fitting` is later work.

*Conservation booking: a boundary transfer at the junction at compile, in the world topology ledger.* Under the switch, `PhysicalRegistry.apply` books through `WorldTopologyLedger.applyBatch`'s `constructed`/`destroyed` totals (moles, field + m g z, solids):
- every junction of an island the batch replaces as **destroyed**, at its current owned inventory;
- every junction of a replacement island as **constructed**, at its minted inventory.

Why this booking, and not a debit from the neighbour the state is copied from:
- `IslandCoordinator.topology` refuses any replacement whose RESERVOIR inventory differs from the old stock ("Topology event changed stock or elevation energy"), so a debit would be refused.
- The neighbour is often a GENERATOR or VOID, with no stock to debit, or another junction.
- The construction/destruction totals are how the runtime already accounts for a tank that is placed or broken, and they are persisted in the checkpoint (`FluidCheckpointCodec` 175).

`PhysicalRegistry.load` compiles only to bind owners; the islands come from the checkpoint with their junction inventories, so a load mints nothing.

**Not tested:** none of the gates goes through `PhysicalRegistry.apply` (they compile directly), and `PhysicalRegistryTest` was not run.

*Splits, merges and removals.* `PhysicalFluidTopology` has none. Every topology batch recompiles the affected component from scratch: it discards every junction of the affected islands and mints fresh ones. A split, a merge and a removal are therefore all "destroy the old junctions, construct the new", and the booking above covers each of them. The cost is that a junction's composition resets to the first boundary's state at every edit. Nothing further was implemented.

**(e) Product gates (the 38 tests).**

| Class | run 51 (default, no init script) | run 45 (7.7: holdup on, no mint) | run 50 (`on` + mint) | run 50h (`history` + mint) | run 50m (mint, void rule off) |
|---|---|---|---|---|---|
| FilterBlockLineIsland | 11/11 | 6/11 | 9/11 | 9/11 | 9/11 |
| NetworkRegime | 9/9 | 7/9 | 7/9 | 7/9 | 7/9 |
| PassiveStepSolver | 6/6 | 4/6 | **6/6** | 6/6 | 6/6 |
| PumpJunctionStartup | 1/1 | 0/1 | **1/1** | 1/1 | 1/1 |
| DeadHeaded, PhysicalFluidTopology, PipePresentation | 4/4, 4/4, 3/3 | pass | pass | pass | pass |
| Total | **38/38** | 28/38 | **34/38** | 34/38 | 34/38 |

- **Phantom-trace gate** `FilterBlockLineIslandTest.filterLineToATankKeepsIntegratingOnceTheTankIsFull`: **PASSES** with the designed 1e-4 kg holdup in runs 50, 50h and 50m. The frozen donors and the directed basis are off in this configuration (7.6 (a)1).
- Six tests that failed in run 45 now pass:
  - `aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn`
  - `valveLineToATankKeepsIntegratingOnceTheTankIsFull`
  - the phantom-trace gate
  - `branchJunctionHasNoInventoryAndBalancesEveryConnectedFlow`
  - `aJunctionNoOpenConnectionReachesKeepsItsPressureInsteadOfGoingSingular`
  - `waterAndAmbientCrudeStartThroughAZeroStoragePumpInletWithoutInventingUpstreamNitrogen`

  Run 50m shows that the mint is what makes them pass; the void rule changes no gate verdict.
- Traces (run 50h):
  - `ENGAGED` prints once per junction id and mass exponent per JVM: 6 lines, all 1.0e-4 kg (run 47: 0.94 to 996 kg).
  - `VOIDSTART` fired 368 times, all in FilterBlockLineIslandTest, all on edge 0 (generator to filter inlet junction; 249 stage and 119 rate solves), on estimated backflows of 1.5e-10 to 1.67 kg/s into the generator. Those tests pass.

The four remaining failures have identical messages in runs 50, 50h and 50m.

1. `NetworkRegimeTest.twoDifferentFeedsMixAtAZeroHoldupJunction` and
2. `NetworkRegimeTest.reversingOneFeedSwitchesJunctionUpwindingAndSerialValvesRemainSolvable`:
   - *Failure:* `IllegalArgumentException: Negative/nonfinite inventory` at `TrBdf2StepSolver.integrate:142`, the junction's stage-one base `n + ALPHA dt dn`.
   - *Mechanism:* both tests call `TrBdf2StepSolver.solve` directly, at dt = 1 s and 0.1 s, on a nitrogen junction fed with methane. Under the frozen form the junction's nitrogen rate is about `-Q_out`, so the explicit half of the stage base goes negative once `ALPHA dt Q > m_J`, i.e. for dt above about a millisecond at these flows. This is the 7.6 mechanism ("1e-6 kg is too small for the stage carry"). The interval solver turns it into rejections; these direct calls turn it into an exception.
   - **Classification: defect** of the design. The TR-BDF2 stage carry does not keep a small owned stock positive when it turns over many times per step. It is not a one-line fix: an implicit-only carry for the junction changes the integrator's order for that stock.
   - *Next assertions:* partly an **intent change**. The first test requires the junction state's methane fraction to equal `q0/q2` to 1e-8 and its enthalpy to equal the inflow mixture's to 1e-3. The second requires the junction state to hold no nitrogen to 1e-9 after 0.1 s. A holdup lags both by construction (backward-Euler mixing with an `m_J/dt` weight on the old stock). Not measured, because the step throws first.
3. `FilterBlockLineIslandTest.filterLineToAVoidIntegratesWithSolidsAndDrivingPressure` and
4. `FilterBlockLineIslandTest.filterLineToAVoidIntegratesWithModerateSolidsAndPressure`:
   - *Failure:* "HELD at interval 1/1 (t=0 s): Solid population balance failed". The tests assert that an interval of a slurry line through a filter to a void integrates OK, which the holdup design does not change by intent.
   - **Classification: defect**, a gap in the prototype's frozen rate form. The pseudo-boundary that books a frozen junction's accumulation is fluid-only (its own comment: "Fluid only: solids are not in this probe"). The slurry solids entering the filter's inlet junction in the dt = 1 rate solve are booked at the generator but stored nowhere.
   - *Evidence:* inference from the code, plus run 45, which gave the same message with the kg-scale inventories.
   - Not a one-line fix: it needs signed per-population solid booking (two transfers, in and out) and the solids' m g z in the booked energy.

Run 52 was therefore not made: no remaining failure has an obvious one-line cause.

**(f) The probes on the runtime mint (run 53).** The two mints do not collide destructively: the probe's `mintHoldup` recomputes the same inventory from the unchanged junction state, so applying it after the runtime mint is idempotent. To make the probes measure the runtime path, `JunctionHoldupStaticProbe.mintHoldup` (untracked, in the tool folder) now returns the graph unchanged when `junction.mintHoldup=on`. Run 53 (`on` + mint) is **bit-identical** to run 49 in every static line, every `HOLDUP_DYNAMIC` verdict and every `HOLDUP_TRAJECTORY`/`HOLDUP_METRICS` line: static 31/32, transients 12/12.

*Open:*
1. The void rule: carry `history` forward rather than `on` (the run 49d misfire). Neither has been through the exact regression or the full fluid suites. Any start-point closure lags by one solve, because a closed edge is not reopened within a solve: an edge whose pressures turn legal inside a step opens only at the next solve. The size of that lag is not measured.
2. Stage-carry positivity of a small owned stock (gate failures 1-2): a junction-specific carry (implicit-only, or limited), with its effect on the step-error estimate. Needed before any m_J small enough to be physical runs through direct TR-BDF2 calls or large steps.
3. Solids in the frozen pseudo-boundary (gate failures 3-4).
4. Physical sizing `kappa * rho * V_fitting` instead of one property value, and the composition a fitting between tanks of different gases is minted with (currently the first boundary's, by id order).
5. Splits, merges and removals reset every junction of the component at each edit (booked, but the composition history is lost). The `PhysicalRegistry.apply` booking itself is untested.
6. `IslandCertificate` ignores junction inventories. A STEADY replay extrapolates tank inventories and boundaries but not the junction stock: a conservation gap equal to the junction's change over the recorded interval, zero at an exact steady state.
7. Exact regression, the full fluid gate suites, and timing with `fresh`, the void rule and the mint on: not run.
8. The 8-entry endpoint-rate cache eviction (7.7 open 2): unchanged.
9. The junction-mass drift (7.7 open 6): now up to 9.9e-6 relative after the restart (0.05:5:true), booked conservatively, source unchanged.

### 7.9 Stage-one holdup term and solids (runs 54+)

Run 2026-09-25 in the same worktree at 9674bf1 by Claude (Opus 5.5): `holdup-prototype-mint.patch` plus one switch, eleven sequential Gradle calls (runs 54-64), no dev client, nothing committed, sources restored afterwards (`git status --short` empty). The full diff is `tools/junction-holdup-prototype/holdup-prototype-stageform.patch` (eight tracked files: the seven of the mint patch plus `PipeTransfer`; it contains the mint patch, apply it alone; `--check --reverse` on the patched tree and `--check` on the restored base verified). Every run uses `-PjunctionHoldup=1e-4 -PcapForm=B -PrateForm=frozen -PrateWarmStart=fresh -PvoidStart=history -PmintHoldup=on` and, unless stated, `-PstageForm=implicit`. Run numbers differ from the brief's plan: 54 is a superseded first form, 56/57 are two added budget traces, 59 an intermediate filter run, 64 a final-code probe confirmation (mapping in (h)).

**(a) Ledger identity and booking choice.**

For each node i and step dt, TR-BDF2 (gamma = 2 - sqrt 2, alpha = gamma/2, A = 1/(gamma (2 - gamma)) = 1.2071) moves the stock as

```
n_i(dt) = n_i(0) + A alpha dt r_i + A T1_i + T2_i
T1_i = n1_i - base1_i,   T2_i = n2_i - base2_i
base1_i = n_i(0) + alpha dt r_i,   base2_i = n_i(0) + A (n1_i - n_i(0))
```

`r_i` is the endpoint-rate booking at i per second: its boundary, or, for an owned junction, its pseudo-boundary, which `TrBdf2StepSolver` routes into `dn`/`du`/`firstSolids` and never into `boundaries`. The step's ledger is `B = A alpha dt B_rate + A B1 + B2`, the physical boundaries of the three evaluations (`append(..., A*ALPHA*dt)`, `A`, `1`). Each evaluation books every edge once, with the same amount at both ends, so `sum_i r_i = B_rate`, `sum_i T1_i = B1` and `sum_i T2_i = B2`. Hence `sum_i [n_i(dt) - n_i(0)] = B` exactly, and so does the solid-population and energy analogue. That is what `checkConservation(initial, projection)` and the probe oracle test.

The identity holds whatever composition the flows carry, as long as the booking is edge-consistent and the junction's stock takes its own `r_J` at the same weight `A alpha dt`. So the ledger does not decide what a junction's outflow carries in the rate evaluation; positivity and consistency decide it. The options:

- **explicit (unchanged):** `r_J = In - Q S0/m` (frozen composition). `base1_J = S0 + alpha dt r_J` is negative once `alpha dt Q > m_J` for a species the inflow lacks (7.8).
- **Stage-one base = S0 with the frozen rate kept for the ports** (the brief's "keep it and subtract it"): the junction's share `A alpha dt r_J` is then missing from `sum_i`. It closes the ledger only if it is booked as a boundary at J. That is a species swap with the outside world of about `A alpha dt Q |w_J - w_mix|` per step, up to 35 % of the step's throughput during a flush. **Rejected; not run.**
- **Pass-through** (the brief's "drop the pseudo-boundary; only the booking changes"): outflow = inflow, so `r_J = 0` and `base1_J = S0`. The ledger is exact. **Run 54, 0/12, superseded:** it is not the rate `f(y0)`. As dt -> 0 its outflows carry the inflow mixture while the stage solves carry the junction's own composition. Every restart case therefore failed on "Embedded boundary error" 1.0e-3 to 2.6e-2, at steps down to 5e-8 s. Four cases also failed at step 0 on a rounding-level negative accumulation of a species the junction did not hold.
- **Chosen: relaxed booking.** Each owned junction's outflows over dt carry `Q S1/m`, where `S1 = (S0 + alpha dt In)/(1 + alpha dt Q/m)` is its stock advanced by backward Euler over `alpha dt` with its booked inflow (species, solid populations, energy field, pump work). So `r_J = In - Q S1/m` and `base1_J = S0 + alpha dt r_J = S1 >= 0` as an algebraic identity. As `alpha dt -> 0` this is the frozen rate (consistent). As `alpha dt -> infinity` it is the pass-through. The stage-one solve then applies a second backward-Euler step over `alpha dt` from S1, so the junction gets `gamma dt = 2 alpha dt` of transport in stage one, like the ports.
  - Because the booking depends on dt, `TrBdf2StepSolver` re-books the cached rate for every step (`ConservativeTransport.reconstruct(portGraph, rate.states(), flows, heads, 1, ..., frozen, relax = ALPHA*dt)`). The cache now also keeps the device heads for the pump work.
  - The rate solve, its flows and states and the cache keys are unchanged. Junctions on a junction cycle (in flow direction) or with a scheduled transfer keep the frozen booking (no fixture exercises either).
- **Companion:** `deltaMoles`/`deltaEnergy`/`deltaSolids` are set only in the RESERVOIR branch, so they stay zero for a junction. `PassiveStepSolver.companion` skips junction layout rows (`solved.reservoirs().get(node).junction()`). The companion's `e0 dt` term reads the same re-booked physical boundaries as the committed ledger.
- **`PassiveIntervalSolver`:** it consumes TR-BDF2's `boundaries` (physical only, unchanged in form) and `pipeTransfers`. The explicit pipe history `PipeTransfer.sample(initial, rate.states(), ...)` sampled the frozen junction composition. It is now re-weighted the same way (`PipeTransfer.relaxed`: `a` times the frozen sample plus `b` times the share of the delivered inflow streams, with `a = m/(m + alpha dt Q)` and `b = 1 - a`). Without this, run 54's static probe failed its pipe-history component oracle in 24/32 cases.

**(b) The stage-two base is not a convex combination.** The brief expected `n0 + A (ns - n0)` to be a convex combination. It is the vessel formula (`TrBdf2StepSolver` second-base loop), but `A = 1.2071 > 1`, so `base2 = (1 - A) n0 + A n1 = -0.207 n0 + 1.207 n1`.

- For a species absent from the inflow, `n1 = n0/(1 + r)^2`, where `r = alpha dt Q/m_J` (two backward-Euler steps). Then `base2 >= 0` requires `(1 + r)^2 <= A/(A - 1) = 3 + 2 sqrt 2`, i.e. `r <= sqrt 2`, i.e. `dt <= 4.8 m_J/Q`.
- The explicit form needs `r <= 1` at stage one and then `r <= 1/(2A - 1) = 1/sqrt 2` at stage two (`dt <= 2.4 m_J/Q`).
- So the implicit stage one doubles the admissible step for a flushing junction but keeps a bound.
- The bound is structural: with every edge booked at the same weights at both ends, `base2_J = n0 + A (n1 - n0)` is forced by the identity in (a). The weight `A > 1` then credits the receivers of stage one's flush with `(A - 1)(n0 - n1)` more than the junction held.

**(c) Probes, run 55 (final code: run 64, identical line for line apart from ms) against run 53.**

- Static: 31/32 against run 53's 31/32. Run 53 was made with `voidStart=on` (README: "as 49 plus mint", bit-identical to run 49). The comparable baseline for `history` is therefore run 49h (transients of 49h and 53 identical), whose failure 3:150:swap:unequal (IT 2.19e-5) run 55 keeps.
  - Run 55 passes the same 31 cases as run 49h. The inflows differ from run 49h by up to 4e-4 relative.
  - Rejected substeps are fewer in 26 of 31 cases, equal in 2 and more in 3 (sums: accepted 338 -> 282, rejected 218 -> 147).
- Transient: **12/12** (run 53: 12/12). The trajectory lines differ from the 1.0 s line on, because the booking changed. `HOLDUP_METRICS` closes in every case.

| Case | run 53 | run 55 | metrics comp / energy 53 | 55 | mJ/1e-4 - 1 at 13 s, 53 | at 16 s, 53 | at 13 s, 55 | at 16 s, 55 | substeps on printed lines 53 / 55 |
|---|---|---|---|---|---|---|---|---|---|
| 0.05:4:false | PASS 743 ms | PASS 1529 ms | 1.3e-14 / 8.2e-15 | 1.3e-14 / 3.6e-15 | -1.46e-7 | -1.46e-7 | -2.05e-9 | -6.09e-6 | 59/19, 77/26 |
| 0.05:4:true | PASS 658 | PASS 965 | 4.9e-15 / 7.0e-15 | 1.2e-14 / 7.5e-15 | -1.36e-7 | -1.36e-7 | -1.73e-9 | -2.15e-6 | 74/20, 91/23 |
| 0.05:5:false | PASS 635 | PASS 1255 | 1.0e-14 / 9.0e-15 | 2.0e-14 / 6.6e-15 | -4.40e-6 | -4.41e-6 | -2.26e-8 | -5.09e-6 | 119/51, 143/67 |
| 0.05:5:true | PASS 615 | PASS 1210 | 4.8e-15 / 9.1e-15 | 1.1e-14 / 1.1e-14 | -9.88e-6 | -9.89e-6 | -7.25e-7 | -9.39e-6 | 92/37, 113/48 |
| 0.05:6:false | PASS 1282 | PASS 2483 | 1.1e-14 / 1.6e-14 | 1.2e-14 / 9.6e-15 | -1.80e-6 | -1.80e-6 | -2.24e-6 | -2.24e-6 | 176/78, 184/81 |
| 0.05:6:true | PASS 1145 | PASS 2162 | 2.0e-14 / 5.4e-15 | 2.2e-14 / 4.8e-15 | -2.23e-7 | -7.04e-6 | -4.59e-9 | -7.74e-6 | 137/62, 158/76 |
| 0.02:4:false | PASS 422 | PASS 779 | 5.2e-15 / 2.3e-15 | 3.2e-15 / 3.7e-15 | -1.76e-7 | -1.76e-7 | -1.12e-8 | -1.12e-8 | 24/3, 63/10 |
| 0.02:4:true | PASS 580 | PASS 719 | 1.4e-14 / 1.6e-14 | 3.8e-15 / 1.5e-14 | +3.09e-7 | +3.14e-7 | -1.99e-7 | -1.99e-7 | 34/14, 27/5 |
| 0.02:5:false | PASS 612 | PASS 849 | 1.9e-14 / 1.7e-14 | 3.0e-15 / 9.7e-15 | -7.60e-8 | -7.60e-8 | -5.62e-8 | -5.62e-8 | 30/12, 24/3 |
| 0.02:5:true | PASS 601 | PASS 770 | 2.1e-14 / 1.4e-14 | 7.7e-15 / 1.0e-14 | -1.45e-7 | -7.48e-6 | -2.81e-10 | -7.33e-6 | 34/15, 27/5 |
| 0.02:6:false | PASS 528 | PASS 794 | 2.0e-14 / 1.4e-14 | 9.8e-15 / 8.2e-15 | -8.72e-7 | -8.80e-7 | -1.98e-7 | -1.98e-7 | 34/15, 28/6 |
| 0.02:6:true | PASS 635 | PASS 878 | 1.5e-14 / 1.5e-14 | 3.5e-15 / 1.7e-14 | -6.32e-7 | -5.44e-6 | -3.45e-7 | -5.15e-6 | 33/14, 27/5 |

The substep counts are summed over the 21 printed trajectory lines only. The timings are single runs. Run 64 of the same code took 726-1464 ms for the 50 mm cases and 531-603 ms for the 20 mm cases, so the run 55 timings are not a measured cost.

**(d) The junction mass drift.** It is booked in both forms (the ledger closes), and it is no longer only the frozen pseudo-boundary. Runs 56 (explicit) and 57 (implicit) repeat runs 53 and 55 with `-PstageFormTrace=true`. That trace sums the accepted steps' junction mass change as three terms: the endpoint-rate term `A (base1 - n0)`, stage one `A (n1 - base1)` and stage two `n2 - base2`. The trajectory lines are identical to runs 53/55 apart from the appended budget, and the budget matches the junction mass to 4e-19 kg. Two components:

1. **Rate-only drift** (stage terms around 1e-14). This is 7.7 open 6: the frozen pseudo-boundary takes the rate solve's junction mass residual. Explicit: 7.6e-8 to 8.8e-7 relative. Under `implicit` the relaxed booking scales that residual by `m/(m + alpha dt Q)`. It is smaller in all nine cases comparable at 13 s, by 1.4x to 500x: for example 0.05:4:false -1.46e-7 -> -2.05e-9, 0.02:5:true -1.45e-7 -> -2.8e-10, 0.02:6:false -8.7e-7 -> -2.0e-7.
2. **Weighted drift, 1e-6 to 1e-5**, split rate : stage 1 : stage 2 = 0.35 : 0.35 : 0.29, i.e. the TR-BDF2 weights `A alpha : A alpha : alpha`.
   - This is a net junction mass imbalance that the rate evaluation and both stage solves of those steps share, and the owned stock integrates it.
   - Explicit: six cases, 1.8e-6 to 9.9e-6 (0.05:5:false, 5:true and 6:false before 10 s; 0.05:6:true, 0.02:5:true and 0.02:6:true after the restart).
   - Implicit: eight cases, 2.1e-6 to 9.4e-6, six of them only after the restart.
   - This component is why run 55's 13 -> 16 s drift is larger than run 53's in the 50 mm cases.
   - *Inference, not measured:* its source is the junction's net-mass row converging to the Newton tolerance in every solve. The owned m_J has no restoring term, so it integrates that residual: about 1e-9 of the throughput, which against a 1e-4 kg stock is 1e-6 to 1e-5 per 16 s case.

**(e) NetworkRegimeTest, run 58 (and run 61), 7/9.**

- `twoDifferentFeedsMixAtAZeroHoldupJunction`: `expected: <0.6396198572911722> but was: <0.6656649859035046>`, the first mixing assertion (junction methane fraction against `q0/q2`, tolerance 1e-8). The enthalpy assertion is not reached. Stage one no longer throws, and the stage-two base stays positive here: nitrogen is in the inflow too.
  - The junction state **overshoots** the inflow mixture by +0.026. That is **not** the holdup's intended lag: a lag stays between the initial 0 and the mixture, and it decays as `exp(-Q t/m_J)`.
  - It is the stage-two extrapolation of (b): `base2 = A n1 - (A - 1) n0` overshoots the mixture, and stage two's backward-Euler step over `alpha dt` only damps the overshoot by `1/(1 + r)`.
  - Classification: **defect** of the integrator on a stiff junction stock (the same root as the next test). Whether the intended lag alone would still miss 1e-8 depends on Q, which the test does not print. Not measured.
- `reversingOneFeedSwitchesJunctionUpwindingAndSerialValvesRemainSolvable`: `IllegalArgumentException: Negative/nonfinite inventory` at `TrBdf2StepSolver.integrate:160` (patched file), the **stage-two** base `Inventory`, at dt = 0.1 s. A nitrogen junction flushed by pure methane has `r > sqrt 2`. Classification: **defect**, structural ((b)). The follow-on assertion (no nitrogen at the junction to 1e-9 after 0.1 s) is not reached. Under a holdup it would be an intent change: the nitrogen left at the junction is `exp(-Q t/m_J)` of the initial, not 0.

**(f) FilterBlockLineIslandTest (defect 2).**

- Run 59 was taken with the solid booking only in the relaxed re-booking: 9/11, the two filter-to-void cases unchanged ("Solid population balance failed").
  - The failing check is `checkConservation` inside `solveRate` itself. The rate solve's own reconstruct (relax 0) still used the fluid-only frozen block.
  - So the defect does **not** disappear with a changed stage-one base. The frozen booking itself had to carry solids.
- Final code: under `implicit` every frozen booking forms the junction pseudo-boundary from the booked tallies, solids included:
  - populations gained leave the network into the junction (`direction -1`);
  - populations lost enter from it (a second transfer, `direction +1`, no moles, no energy);
  - the energy of the pair is `-(field change + accumulated mass g z)`.
- Run 60: **11/11**. The two cases print "(iv) ... OK intervals=1 advanced=5.0 accepted=42 rejected=6 reasons={Embedded pipe error #=6}" and "(v) ... OK ... accepted=24 rejected=7 reasons={Embedded pipe error #=7}".
- **The phantom-trace gate `filterLineToATankKeepsIntegratingOnceTheTankIsFull` passes** (runs 60 and 61).
- The explicit form keeps the old fluid-only block (so explicit runs reproduce run 53), so defect 2 remains under `explicit`.

**(g) The 38 adjacent gates and the fluid suites.**

| Class | run 51 (7.8, default) | run 50h (7.8, all on, explicit) | run 61 (all on, `implicit`) | run 62 (default, no init script) |
|---|---|---|---|---|
| FilterBlockLineIsland | 11/11 | 9/11 | **11/11** | 11/11 |
| NetworkRegime | 9/9 | 7/9 | 7/9 | 9/9 |
| PassiveStepSolver | 6/6 | 6/6 | 6/6 | 6/6 |
| PumpJunctionStartup | 1/1 | 1/1 | 1/1 | 1/1 |
| DeadHeaded, PhysicalFluidTopology, PipePresentation | 4/4, 4/4, 3/3 | pass | pass | pass |
| Total | 38/38 | 34/38 | **36/38** | **38/38** |

- Run 61's two failures are those of (e), with the same messages as run 58. Classification: defect (TR-BDF2 stage two on a stiff junction stock). The first test's 1e-8 mixing tolerance would additionally be an intent change under any holdup.
- Run 63: every test under `com.wormzjl.createcheme.science.fluid.*` and `com.wormzjl.createcheme.runtime.fluid.*` at defaults (no init script, no properties): **399/399 in 91 classes**, 0 skipped. This is pass/fail neutrality of the switched-off patch. No bitwise comparison with the base was made, and the exact solver regression was not run.

**(h) Verdict.**

- Defect 2 is fixed under `implicit`: the frozen booking carries solids, FilterBlock passes 11/11 and the phantom-trace gate passes.
- Defect 1 is fixed for stage one only.
  - The relaxed booking keeps the stage-one base non-negative for any dt, stays a consistent rate (it tends to the frozen rate as dt -> 0), keeps the ledger exact and keeps the probes at 12/12 and 31/32.
  - The stage-two base `n0 + A (n1 - n0)` with `A = 1.2071` is not convex. Under the edge-consistent ledger it cannot be changed for the junction alone. A junction flushed of a species therefore still needs `alpha dt Q/m_J <= sqrt 2` (was `1/sqrt 2`).
  - Inside `PassiveIntervalSolver` that is a rejection and a smaller step. In a direct `TrBdf2StepSolver.solve` at a large dt it is an exception (NetworkRegimeTest).
  - Removing it needs the junction out of the A-blend. Two ways, neither tested: an integrator whose stage combination is convex for the whole island when an owned stock is this stiff (for example backward Euler, or an SDIRK with non-negative weights), or m_J sized so that `m_J/Q` is not small against the accepted step.
- Run map: brief run 54 (probes) = runs 54 (pass-through, superseded) and 55/64 (final); brief 55 (NetworkRegime) = 58; brief 56 (FilterBlock) = 59/60; brief 57/58 (gates on/default) = 61/62; brief 59 (fluid suites) = 63; added: 56/57 (budget traces).

*Open (carried from 7.8, plus new):*
1. The mint's world-ledger booking through `PhysicalRegistry.apply` is untested (`PhysicalRegistryTest` not run).
2. `IslandCertificate` skips junction inventories (REST/STEADY summary, `graphAt` replay; the digest omits the volume).
3. Remint at every topology edit resets a junction's composition to the first boundary's.
4. `kappa` sizing `m_J = kappa rho V_fitting` instead of one property value (also the lever on the stage-two bound).
5. Rate-cache eviction of the accepted point's own entry after eight insertions.
6. The exact regression re-record and a bitwise neutrality check at defaults: not run.
7. New: stage-two positivity for a stiff junction stock ((b), (e)).
8. New: the weighted junction mass drift ((d) 2): the owned m_J has no restoring term against the net-mass-row residual.
9. New: the per-step re-booking costs one extra reconstruct per TR-BDF2 step (not timed). The frozen fallback for junction cycles and junctions with scheduled transfers is untested.

### 7.10 Holdup sized to throughput and mass pin (runs 65+)

Run 2026-09-25 in the same worktree at 9674bf1 by Claude (Opus 5.5): `holdup-prototype-stageform.patch` plus two switches, sequential Gradle calls (runs 65-72 and the attribution runs 69a/69b, 71a-71g), no dev client, nothing committed, sources restored afterwards (`git status --short` empty). The full diff is `tools/junction-holdup-prototype/holdup-prototype-sized.patch` (the eight tracked files of the stageform patch; it contains that patch, apply it alone; `--check --reverse` on the patched tree and `--check` on the restored base verified). Base property set of every switched-on run: `-PcapForm=B -PrateForm=frozen -PrateWarmStart=fresh -PvoidStart=history -PmintHoldup=on -PstageForm=implicit`. In PowerShell `-PholdupTau=0.05` must be quoted (`'-PholdupTau=0.05'`), or Gradle reads `.05` as a task.

**(a) Owner decision (2026-09-25).** The stiffness bound of 7.9 (b) is met by sizing m_J to throughput, not by bounding the substep. The holdup is a numerical regularisation, and its mixing lag m_J/Q at full flow is meant to be invisible at the 5 s presentation. Instantaneous-mixing assertions in tests become intended changes; they are listed below, not edited.

**(b) Quick confirmation at a fixed 5e-3 kg (runs 65, 66).**
- Run 65, NetworkRegimeTest 7/9. Neither case throws any more; both fail on the mixing assertion the holdup changes by design:
  - `twoDifferentFeedsMixAtAZeroHoldupJunction` (line 34): `expected: <0.6282675228509497> but was: <0.47113902468468033>`, the junction methane fraction after one 1 s TR-BDF2 step from a nitrogen-filled holdup. It lags the inflow mixture and does not overshoot it (7.9 (e) overshot at 1e-4 kg).
  - `reversingOneFeed...` (line 43): `expected: <0.0> but was: <51.77117443378145>` (nitrogen amount of the junction state after 0.1 s).
- Run 66, probes against run 64:
  - Static 31/32, with the same failure (3:150:swap:unequal IT 2.19e-5).
  - Transients **10/12**. Two new failures, both at the restart (step 100): 0.05:6:false (IT 1.28e-6) and 0.05:6:true (IT 4.26e-9).
  - Junction mass drift at 16 s is at most 1.4e-7 relative. The absolute drift is unchanged and m_J is 50x larger.
  - Rejected substeps did **not** drop overall. On the printed trajectory lines the 50 mm cases have 27/31/49/47 against run 64's 26/23/67/48, and the 20 mm cases 10-12 against 3-10. The static matrix has fewer rejections in 22 of 31 cases (sums 132/81 against 282/147).

**(c) dt_max and the tau rule.**
- *The largest step one solve can take in the product* is `min(Settings.maximumStep, slice)`:
  - `PassiveIntervalSolver.Settings.defaults()` = (initial 1 s, **maximum 20 s**, tol 1e-3, 1024 attempts). `IslandCoordinator` dispatches every island with these defaults, and so does `ModuleTransferPlanner`. The approximate fallback uses `(min(1, duration), 20, 2.5e-3, 256)`.
  - `grow()` caps the controller at `maximumStep`, and `RetainedSolver` carries `nextStepEstimate` across intervals (capped by the slice).
  - The slice is at most the island cadence. `IslandClock` allows 20..400 ticks, i.e. 1..20 s; the default is 100 ticks = 5 s, and the adaptive cadence grows to 400 ticks under load.
  - **dt_max = 20 s**, and 5 s at the default cadence. The 0.1 s of the probes is a probe choice.
- *The rule* (7.9 (b)):
  - For a species the inflow lacks, the stage-two base `(1 - A) n0 + A n1` stays non-negative while `alpha dt Q/m_J <= sqrt 2`, i.e. `dt <= 4.8 m_J/Q`.
  - With `Q <= capFlow` and `dt <= dt_max` the bound holds for every step once `m_J >= capFlow dt_max/4.8`. Hence `tau = dt_max/4.8` times a margin.
  - For the 0.1 s probe interval, tau = 0.05 s is a 2.4x margin.
- *What that means in the product:*
  - dt_max = 20 s needs tau >= 4.2 s, or **10 s with the same 2.4x margin**; the default 5 s cadence needs 1.0 s (2.5 s with margin).
  - The mixing lag at full cap flow is tau itself, so the lag would be 4-10 s, not the ~20 ms the decision assumes. The two goals, a lag invisible at 5 s and the bound holding for the largest product step, **cannot both be met by sizing**.
  - For liquids the cap uses `velocityLimit = min(100 m/s, acoustic)` = 100 m/s. A water fitting then sizes to `tau * rho A v`: 9.78 kg at tau = 0.05 s in FullTankSolidsEventTest (capFlow 195.6 kg/s), and about 2 t at tau = 10 s.
  - **Recommended default: tau = 0.05 s**, with a stated scope. The bound then holds for `dt <= 0.24 s * capFlow/Q`. A longer step that flushes a species out of a junction is a positivity failure, and `PassiveIntervalSolver` turns it into a rejection and a smaller step: the product only calls TR-BDF2 through `PassiveIntervalSolver.stepSolver` (grep). A direct `TrBdf2StepSolver.solve` at a large dt, as the NetworkRegime fixtures make, can still throw. Removing the bound for every product step needs the integrator change of 7.9 (h), not sizing.
- *The bound is optimistic in the probes, measured:* `max over connections` understates the throughput.
  - Two inflows at their caps add up, and the seed is the first generator's state: methane at 150 kPa, 0.828 kg/m³.
  - At t = 1.0 s the 50 mm cases carry Qin = 0.363-0.370 kg/s against the seed capFlow of 0.163 kg/s (2.2-2.3x), and the 20 mm cases 0.067-0.069 against 0.026 kg/s (2.6x).
  - The effective `4.8 m_J/Q` is therefore 0.105 s (50 mm) and 0.09 s (20 mm), about the 0.1 s interval rather than 2.4x above it.
  - A bound on the actual throughput is `Q <= (1/2) sum_e cap_e`, since every connection is either an inflow or an outflow and Q_in = Q_out. With each cap on its donor's density, not the seed's, it is conservative.
- *Seed density and a later state (stated, not measured):*
  - The mint happens once, at compile. If the line later runs at a lower density, the cap flow falls with it and the bound only gets easier. The lag m_J/Q grows, though: a water-sized junction carrying gas at 0.2 kg/s would lag about 50 s.
  - If the density rises, for example liquid arriving in a gas-minted fitting (0.8 to 1000 kg/m³ at the same 100 m/s limit), the cap flow and the admissible Q rise about 1000x while m_J stays. `4.8 m_J/Q` then falls to about 0.1 ms, and gas-to-liquid arrival is exactly a species flush.
  - So a **remint (resize) on phase change is needed** if a fitting can change phase. The pin in (g) holds the mass, so a gas-minted m_J full of liquid, or a liquid-minted m_J full of gas, keeps a mass the fitting cannot physically hold.

**(d) Implementation (`junction.holdupTau`, Gradle `'-PholdupTau=<s>'`, default 0 = the fixed `createcheme.junctionHoldup`; needs `-PmintHoldup=on`).**
- With tau > 0, the `Reservoir(id,z,state,kind)` constructor mints a placeholder: volume field `Double.MIN_VALUE`, mass `createcheme.junctionHoldup` or 1 kg. It cannot size there, because it knows neither the connections nor the model.
- `PassiveNetwork.sizeJunctionHoldups(graph, model)` sizes every placeholder once from its seed state: `m_J = max(createcheme.junctionHoldup, tau * max_e rho_seed A_min,e velocityLimit(seed))`. A filter connection uses the same formula. The inventory is minted exactly like the fixed mint (fluid and solids scaled, energy field `m_J h`), and its volume field carries m_J in kg (nothing reads a junction's volume field, 7.8 (c)).
- It is called:
  - in `PhysicalRegistry.apply` before the constructed booking;
  - at the entries of `PassiveIntervalSolver.solve/solveApproximate/run`, `TrBdf2StepSolver.integrate` and `PassiveStepSolver.solve`, where it is a no-op on a sized graph, so fixtures that build junctions directly are sized too;
  - by the probes after compile, so the oracle's initial totals hold m_J.
- With only tau set, `PassiveStepSolver.JUNCTION_HOLDUP` = `Double.MIN_VALUE`. That value is the flag every holdup branch tests (`> 0`), never a mass.
- Minted sizes, run 67 (`HOLDUP_MINT` and the static PASS lines):

| Probe cases | seed state | rho_seed kg/m³ | capFlow kg/s | m_J kg |
|---|---|---|---|---|
| transient 50 mm (all six) | methane 150 kPa 350 K | 0.8283 | 0.16264 | 8.1322e-3 |
| transient 20 mm (all six) | same | 0.8283 | 0.026023 | 1.3011e-3 |
| static 102325 Pa, no swap, equal / unequal T | methane gen. 1, 350 / 300 K | 0.5648 / 0.6595 | 0.11089 / 0.12949 | 5.5445e-3 / 6.4745e-3 |
| static 150000 Pa, no swap, equal / unequal | methane, 350 / 300 K | 0.8283 / 0.9677 | 0.16264 / 0.19001 | 8.1322e-3 / 9.5003e-3 |
| static 102325 Pa, swap, equal / unequal | nitrogen gen. 1, 350 / 300 K | 0.9849 / 1.1495 | 0.19339 / 0.22571 | 9.6696e-3 / 1.1285e-2 |
| static 150000 Pa, swap, equal / unequal | nitrogen, 350 / 300 K | 1.4438 / 1.6853 | 0.28349 / 0.33091 | 1.4174e-2 / 1.6545e-2 |
| NetworkRegime fixtures (run 68) | nitrogen 195 kPa 350 K, 20 mm | 1.8769 | 0.058963 | 2.9482e-3 |
| FullTankSolidsEvent water junctions (run 71b) | water/slurry | 996.3 | 195.62 | 9.7812 |

**(e) Probes at tau = 0.05 s (run 67) and with the pin (run 69) against run 64 (1e-4 kg).**

| Case | run 64 | run 67 (tau) | run 69 (tau + pin) | metrics comp / energy, 67 | 69 | mJ/m_J - 1 at 13 s / 16 s, 64 | 67 | **69** | substeps printed lines 64 / 67 (all intervals 67) |
|---|---|---|---|---|---|---|---|---|---|
| 0.05:4:false | PASS | PASS | PASS | 1.0e-14 / 1.7e-14 | 9.3e-15 / 2.6e-14 | -2.05e-9 / -6.09e-6 | 7.2e-10 / 7.3e-10 | 2.1e-16 / 2.1e-16 | 77/26, 79/31 (460/137) |
| 0.05:4:true | PASS | PASS | PASS | 2.1e-14 / 2.8e-14 | 9.8e-15 / 4.6e-15 | -1.73e-9 / -2.15e-6 | -5.1e-9 / -4.5e-8 | 0 / 0 | 91/23, 95/29 (526/173) |
| 0.05:5:false | PASS | PASS | PASS | 7.9e-15 / 1.2e-14 | 1.2e-14 / 8.8e-15 | -2.26e-8 / -5.09e-6 | -5.3e-8 / -1.0e-7 | 0 / 0 | 143/67, 132/49 (520/156) |
| 0.05:5:true | PASS | PASS | PASS | 1.1e-14 / 3.0e-15 | 6.7e-15 / 8.4e-15 | -7.25e-7 / -9.39e-6 | -4.1e-8 / -4.1e-8 | 0 / -2.1e-16 | 113/48, 110/42 (539/170) |
| 0.05:6:false | PASS | PASS | PASS | 4.0e-15 / 1.9e-15 | 6.7e-15 / 3.8e-15 | -2.24e-6 / -2.24e-6 | 3.8e-15 / 6.2e-15 | -2.1e-16 / 2.1e-16 | 184/81, 137/59 (525/163) |
| 0.05:6:true | PASS | **FAIL** step 100 IT 4.11e-8 | **FAIL** step 100 IT 4.11e-8 | (to step 100) 3.0e-15 / 2.6e-15 | 4.6e-15 / 2.9e-15 | -4.59e-9 / -7.74e-6 | - | - | 158/76, 97/52 (228/74) |
| 0.02:4:false | PASS | PASS | PASS | 5.7e-15 / 4.2e-15 | 3.2e-15 / 3.9e-15 | -1.12e-8 / -1.12e-8 | -2.6e-9 / -2.6e-9 | -1.7e-16 / -1.7e-16 | 63/10, 57/10 (584/128) |
| 0.02:4:true | PASS | PASS | PASS | 4.5e-15 / 4.9e-15 | 4.4e-15 / 2.8e-15 | -1.99e-7 / -1.99e-7 | -2.6e-8 / -5.8e-7 | -1.7e-16 / -3.3e-16 | 27/5, 61/8 (731/189) |
| 0.02:5:false | PASS | PASS | PASS | 1.4e-14 / 4.6e-15 | 4.5e-15 / 2.9e-15 | -5.62e-8 / -5.62e-8 | -9.5e-9 / -9.5e-9 | -1.7e-16 / -1.7e-16 | 24/3, 60/6 (704/157) |
| 0.02:5:true | PASS | PASS | PASS | 9.2e-15 / 5.6e-15 | 4.5e-15 / 8.2e-15 | -2.81e-10 / -7.33e-6 | -2.7e-8 / -8.7e-7 | -3.3e-16 / -3.3e-16 | 27/5, 54/7 (703/190) |
| 0.02:6:false | PASS | PASS | PASS | 9.6e-15 / 3.7e-15 | 5.7e-15 / 9.2e-15 | -1.98e-7 / -1.98e-7 | -2.3e-8 / -2.3e-8 | 0 / -3.3e-16 | 28/6, 57/7 (700/180) |
| 0.02:6:true | PASS | PASS | PASS | 7.7e-15 / 5.4e-15 | 4.2e-15 / 6.3e-15 | -3.45e-7 / -5.15e-6 | -7.7e-9 / -7.2e-8 | -1.7e-16 / -1.7e-16 | 27/5, 46/5 (665/196) |

- Static: 31/32 in runs 67 and 69, with the same failure as run 64 (3:150:swap:unequal IT 2.19e-5). Rejections are fewer in 22 of 31 cases, equal in 6 and more in 3 (sums: accepted 282 -> 98, rejected 147 -> 66). A larger m_J makes the one-interval static solves easier.
- Transients 11/12. The one failure, 0.05:6:true at the restart (step 100, `Newton iteration limit at residual 4.11e-8`), is new with every holdup of 5e-3 kg or more:
  - run 66: 0.05:6:false and 0.05:6:true;
  - runs 67 and 69: 0.05:6:true.
  - It was not diagnosed (open item 8).
- The run 69 trajectories match run 67's closely (same verdicts, substep counts within a few). Run 69 changes only the booking.
- Timings are single runs, 537-985 ms per case; they are not a measured cost.

**(f) Lag measurement (run 67 against run 64).**
- The probe's `wMix` is the inflow mass fraction from the *interval-average* flows and the donors' end-of-interval states, while `wJ` is the endpoint stock. The two differ even without a holdup, by the averaging offset.
- The lag is therefore read as run 67's offset minus run 64's, whose own lag m_J/Q is 0.3 ms at 1e-4 kg. The slope comes from the 1.0 and 1.4 s lines.

| Case, t = 1.0 s | Qin kg/s | m_J/Q | wJ - wMix run 64 | run 67 | holdup lag in wJ | dwMix/dt (1.0-1.4 s) | lag time |
|---|---|---|---|---|---|---|---|
| 0.05:4:false (50 mm, flowing) | 0.3634 | 22 ms | +9.47e-5 | +4.45e-5 | -5.0e-5 | +1.67e-3 /s | about 30 ms |
| 0.02:4:false (20 mm, flowing) | 0.06686 | 19 ms | +2.32e-6 | +1.27e-6 | -1.05e-6 | +7.5e-5 /s | about 14 ms |

- The measured lag has the size m_J/Q predicts (tens of ms), and in mass fraction it is 5e-5 (50 mm) and 1e-6 (20 mm) at t = 1 s.
- In NetworkRegimeTest the same lag shows as the intended-change values in (h): one step from a nitrogen-filled holdup is not the mixture.

**(g) Mass pin (`junction.pinMass`, Gradle `-PpinMass=on`, default `off`; needs the mint).**

*Placement and identity.* The brief left two choices: a pseudo-boundary at the junction, or a correction to the outflow bookings. The outflow correction is used. `ConservativeTransport.pinnedFlows` replaces the flows a reconstruction *books* (the Newton flows, states and pipe histories stay unchanged) before anything is assembled. Junctions are taken in flow order (on a junction cycle a junction is left unpinned), with In' = what the inflows deliver as booked (an upstream junction's factor applied, filter capture removed):

```
f_J = max(0, m_old + dt In' - m_J) / (dt Out)                (Out > 0: every outflow of J booked x f_J)
g_J = (m_J - m_old - dt In'_junction) / (dt In'_other)        (Out = 0, only if 0 <= g_J <= 1: non-junction inflows x g_J)
=>  endMass_J = m_old + dt (In' - Out') = m_J
```

It applies to every reconstruction of an owned junction:
- the stage and companion solves: the booked inventory is `m_J w`;
- the frozen and relaxed rate bookings: the pseudo-boundary carries `m_J - m_old`, zero once the stock is at m_J.

So the TR-BDF2 bases `n0 + alpha dt r`, `n1` and `(1 - A) n0 + A n1` all have mass m_J, and the accepted step does too.

*Why it is exact.*
- The reconstruction's ledger identity is edge-by-edge: each edge books one amount `dt b_e w_donor` at both ends. That amount is read by the receiver's vessel row (column weight and endMass), by a fixed node's boundary, by the pipe loop's energy and filter cake, and by the pseudo-boundary tallies. So `sum_i (N_i - N_i,old) = sum of boundaries` per component, solid population and energy for any booked rates b_e, and 7.9 (a)'s TR-BDF2 identity then carries it through the step.
- The junction's own row stays `(endMass + dt Out') w = m_old w_old + sum dt b_in w_in`. With outflow scaling, `endMass + dt Out' = m_old + dt In'` whatever f_J is, so w_J is the unpinned row's composition for the same inflows. The pin moves no composition, only where the Newton's mass residual lands.
- The total mass (fluid and solids, as minted) is pinned. That is a deviation from the brief's "fluid only, solids as the row moved": the minted m_J includes solids, and scaling a booked edge moves both alike.
- The pseudo-boundary alternative was rejected on reading, not measured. It would put a new, tiny, noisy boundary id into:
  - the committed ledger;
  - `PassiveIntervalSolver.boundaryError` (floors 1e-10 mol / 1e-4 J, active whenever scheduled transfers exist, as in the restart phase);
  - `IslandCertificate`'s all-zero-boundaries REST test (line 83), which it would block at rest.

*Forms tried.*
- Run 69a pinned the stage reconstructions only, outflows only. The drift stayed at -5.5e-7 / -3.8e-7 / -2.4e-7 in 0.02:4/5/6:true at rest. There the unpinned rate booking (flows about 1e-10 kg/s, outflow only) drains the stock, and outflows cannot refill it (202 PINSKIP).
- Run 69b pinned every reconstruction and refilled deficits by amplifying non-junction inflows. Near rest the factors reached 1.6e4, which moved the junction composition off the Newton's. 0.05:5:false and 0.02:4:true then failed on "Conservative reconstruction fails equation gate" 2.5e-3 / 8.8e-3. Inflows are therefore only ever reduced (g <= 1).
- The final form, run 69, booked 299 `PINZERO` and no `PINSKIP` or `PININFLOW` in the transients. `PINZERO` is a junction short of m_J by at most 1.7e-18 kg (rounding) that books no outflow; the outflows involved are at most 6.3e-10 kg/s, at rest.

*Drift table.* `mJ/m_J - 1` at 13 s / 16 s is in column "69" of (e): within **3.3e-16** in all 11 completed cases, against 7.3e-10 ... 8.7e-7 without the pin (run 67). HOLDUP_METRICS stays at 2.6e-14 or below in every case.

**(h) Gates.**

*Run 70, the 38 adjacent tests, everything on (tau = 0.05 s, pin on, base set): 36/38.*

| Class | run 61 (7.9) | run 70 |
|---|---|---|
| FilterBlockLineIsland | 11/11 | 11/11 (phantom-trace gate passes) |
| NetworkRegime | 7/9 | 7/9 |
| PassiveStepSolver, PumpJunctionStartup | 6/6, 1/1 | 6/6, 1/1 |
| DeadHeaded, PhysicalFluidTopology, PipePresentation | 4/4, 4/4, 3/3 | 4/4, 4/4, 3/3 |
| Total | 36/38 | **36/38** |

The two NetworkRegime failures are now **intended changes** (instantaneous-mixing assertions). They no longer throw:
- `twoDifferentFeedsMixAtAZeroHoldupJunction`, NetworkRegimeTest.java:34, `assertEquals(q[0]/q[2], <junction methane mass fraction>, 1e-8)`: `expected: <0.6326450275296205> but was: <0.5722427451894476>`. The enthalpy assertion on line 36 is not reached.
- `reversingOneFeedSwitchesJunctionUpwindingAndSerialValvesRemainSolvable`, line 43, `assertEquals(0, <junction nitrogen amount>, 1e-9)`: `expected: <0.0> but was: <44.07302495656579>`. The serial-valve half is not reached.

*Run 71, all fluid suites, everything on: 395/401 in 93 classes.* The 401 are the 399 suite tests plus the two probe wrappers the init script adds, which pass by construction. Every failure, with its attribution run:

| Test | Assertion | Attribution | Class |
|---|---|---|---|
| `NetworkRegimeTest.twoDifferentFeedsMixAtAZeroHoldupJunction` | line 34, above | holdup | **intended change** |
| `NetworkRegimeTest.reversingOneFeedSwitchesJunctionUpwindingAndSerialValvesRemainSolvable` | line 43, above | holdup | **intended change** |
| `ElevatedBlockLineIslandTest.elevatedLinesIntegrateFortyIntervalsLikeTheirFlatControl` | line 218 "A tank below its generator must settle one water column above it ==> expected: <439081.6931625868> but was: <434925.6987174727>" (the falling line stops 4156 Pa short of hydrostatic rest) | fails with the 7.9 configuration (71a, same value to the last digit) and passes with `voidStart=off` (71e): the void-start rule | **defect** of `voidStart=history` (not the holdup): it closes the generator edge on a start-point estimate before the falling column reaches rest |
| `IslandCertificateTest.deadHeadedLinesCertifyRestAndThenCostNothing` | line 149 "dead-headed pump did not certify: no accepted interval ==> expected: <true> but was: <false>" | fails with `-PcapForm=B` alone (71g) | **defect** of cap form B (not the holdup) |
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` | line 94, `Nonconvergence: Substep refinement exhausted: Newton line search stalled at residual 8.234888610726238E-8` | fails with the 7.9 configuration (71a, 4.95e-7), passes with only capForm B + fresh (71f) | **defect** of the owned-holdup family (present since 7.9, independent of sizing and pin), mechanism **not understood** |
| `FullTankSolidsEventTest.theFilterCapturesOnceTheDrainedTankLetsTheLineFlow` | line 238 "HELD at interval 6/40 (tank drawn down through a void): ... Newton line search stalled at residual 23.81232618852451" | passes with tau and no pin (71b), passes with the pin at a fixed 1e-4 kg (71d), fails with tau + pin (71, 71c, 71e); its water junctions are sized 9.78 kg | **defect** of the pin at a liquid-sized holdup, mechanism **not understood** |

- The exact solver regression is not among these suites: it is `com.wormzjl.createcheme.fluid.benchmark.FluidSolverRegressionTest`, the separate `fluidSolverRegression` task, and it was not run.
- Under the holdup it will differ **by intent**: junctions own an inventory and mix with a lag, so a re-record is needed rather than a pass.

*Run 72, the same suites at defaults, no init script: **399/399** in 91 classes, 0 skipped.* This is pass/fail neutrality of the switched-off patch. No bitwise comparison with the base was made.

**(i) Verdict.**
- Sizing to throughput at tau = 0.05 s keeps the probes' ledger closed. The NetworkRegime fixtures no longer throw: their two failures are now pure intended changes. The measured mixing lag is the predicted m_J/Q, tens of ms.
- It does not meet the stiffness bound for the product's largest step. dt_max is 20 s, and a tau that covers it gives a 4-10 s lag and kg- to tonne-scale liquid holdups. It also costs one 50 mm restart case (0.05:6:true).
- The mass pin holds m_J to rounding in every completed case, with the ledger exact.
- The full fluid suites with everything on show two intended changes, three defects from the earlier switches (void rule, cap form B, owned-holdup family) and one pin defect at a liquid-sized holdup.

*Open:*
1. The mint and sizing booking through `PhysicalRegistry.apply`: `PhysicalRegistryTest` (8 tests) passed in run 71, but whether any of its cases mints a junction and checks the world-ledger booking was not examined; treat the booking as untested.
2. `IslandCertificate` skips junction inventories (REST/STEADY summary, `graphAt` replay, digest without the volume). With the pin, a junction's inventory is constant in mass but not in composition.
3. Remint at every topology edit resets a junction's composition, and now also its size, to the first boundary's state.
4. The rate-cache eviction of the accepted point's own entry after eight insertions.
5. The exact regression re-record (intended difference) and a bitwise neutrality check at defaults.
6. Timing and cost: the per-step re-booking of the rate (7.9 open 9) and `pinnedFlows`, which is O(junctions x edges) per reconstruction. Neither is timed.
7. Remint (resize) on phase change is **needed** if a fitting can change phase, (c).
8. New: the 50 mm 6-port restart failure with holdups of 5e-3 kg or more (0.05:6:true at tau = 0.05; 0.05:6:false too at a fixed 5e-3), not diagnosed.
9. New: the sizing rule's `max over connections at the seed state` understates Q by 2.2-2.6x in the probes. `(1/2) sum_e cap_e` on donor densities is the conservative form.
10. New: liquid sizing through the 100 m/s velocity limit gives kg-scale fitting holdups (9.78 kg). The pin fails FullTankSolidsEvent at that size, not understood.
11. New, from run 71: the `voidStart=history` early closure on a falling liquid column (ElevatedBlockLineIsland), the cap form B dead-headed-pump certificate (IslandCertificate), and the owned-holdup SolidRuntime stall. All three predate this batch's switches.
12. Prototype shortcuts a real implementation must replace:
    - the junction inventory's volume field carries m_J;
    - `JUNCTION_HOLDUP = Double.MIN_VALUE` serves as a flag;
    - the placeholder sentinel marks an unsized mint.

    A real implementation needs an owned-mass field in the node and the checkpoint: a format change, tested on a fresh world.

### 7.11 Positivity clip and switch defects (runs 73-83)

Run 2026-09-25 in the same worktree at 9674bf1 by Claude (Opus 5.5): `holdup-prototype-sized.patch` plus three switches, sequential Gradle calls (runs 73-83 with the lettered runs 73d, 75b-75d, 76a, 77b and 78a), no dev client, nothing committed. The agent that made the runs was cut off during the write-up. A second agent (Claude, Opus 5.5) wrote this section from the stored files and the uncommitted diff, made no Gradle call, and restored the sources (`git status --short` shows nothing tracked). The full diff is `tools/junction-holdup-prototype/holdup-prototype-clip.patch`: the eight tracked files of the sized patch, containing that patch, so apply it alone. `--check --reverse` on the patched tree and `--check` on the restored base are verified. Since the sized patch it changes only ConservativeTransport, PassiveStepSolver and TrBdf2StepSolver.

The run logs do not record the command lines. Configurations are the ones the brief assigned. The stored files fix these points:
- Gradle recompiled the main sources in runs 75b, 76 and 78 only. Runs 73-75 therefore ran a first clip form, 75b onward the patch's swap form, 76 onward `history2`, and 78 onward `B2`.
- Run 82's Elevated and IslandCertificate passes need `history2` and `B2`.

Base property set of every switched-on run: `-PcapForm=B -PrateForm=frozen -PrateWarmStart=fresh -PvoidStart=history -PmintHoldup=on -PstageForm=implicit '-PholdupTau=0.05' -PpinMass=on`, the run 69 configuration of 7.10. "Everything on" (runs 76a-83) means that set plus `-PstageClip=on -PvoidStart=history2 -PcapForm=B2`.

**(a) Owner decision (2026-09-25).**
- Product dt_max is 20 s (7.10 (c)). Sizing the holdup to cover it would give a 4-10 s mixing lag and kg- to tonne-scale liquid holdups, so sizing to throughput is out as the positivity fix.
- tau = 0.05 s stays.
- The TR-BDF2 stage-two positivity bound `alpha dt Q/m_J <= sqrt 2` (7.9 (b)) is to be removed by an exact per-species clip of the stage-two base. The clip is booked on that stage's junction outflows, with the same re-booking as the mass pin (`pinnedFlows`).

**(b) The clip, `junction.stageClip` (Gradle `-PstageClip=on`, default `off`; needs `stageForm=implicit`, `rateForm=frozen` and a holdup).**
- *Recording.* TR-BDF2 records what every edge booked in two places, the relaxed rate re-booking and the stage-one reconstruction: donor, receiver, species moles, mass, field and solids (`ConservativeTransport.recordBookings`/`takeBookings`, a thread-local `EdgeBooking[]` filled at the end of `reconstruct`).
- *Building the stage-two bases.* `TrBdf2StepSolver.clippedSecondBase` builds them from the vessel formula, clips every owned junction's negative species or solid population, and returns the extra boundary transfers. `integrate` appends those transfers to the step's `boundaries`.

The identity and booking, quoted from the code comment of `clippedSecondBase` (TrBdf2StepSolver):

> For an owned junction J and a species c (the same for a solid population) with `d = -base2_J,c > 0`, let `net_e` be the A-weighted amount of c the two bookings moved out of J over edge e (out minus in, as they were booked: pinned flows, relaxed compositions; recorded by `ConservativeTransport.recordBookings`). Then `sum_e net_e = n0_J,c - base2_J,c = n0_J,c + d >= d`, so the positive part `S = sum_{net_e > 0} net_e >= d`, and each such edge takes back `d_e = d net_e/S <= net_e`. The clip is a *composition swap* on the edge: it returns d_e of c and sends the same mass `dm_e = d_e M_c` of the junction's own admissible mixture y (mol/kg of its positive species, taken once before the junction's swaps) the other way:
>
> ```
>   base2'_J = base2_J + sum_e (d_e e_c - dm_e y)       (c -> 0; every other species stays >= 0, see below)
>   base2'_k = base2_k - d_e e_c + dm_e y               (k = the edge's other end, a vessel or an owned junction)
>   B'       = B + {+d_e e_c - dm_e y at k}            (k fixed: one boundary transfer at k's own id)
> ```
>
> Per component and solid population `sum_i base2'_i - sum_i base2_i = sum_{k fixed} (d_e e_c - dm_e y)`, so the step's ledger `sum_i [n_i(dt) - n_i(0)] = A alpha dt B_rate + A B1 + B2 + B_clip` (review 7.9 (a)) holds exactly: each edge still books one amount at both ends. Every edge keeps its booked mass, so no node's mass changes, the mass pin and the junction's algebraic mass (the Newton's net-mass row) see nothing, and no energy is re-booked. The junction's positive species lose `D/(m + D)` of themselves at most (D = the clipped mass, m + D = their mass, m = the junction's mass >= 0), so they stay non-negative. The receiver keeps `net_e - d_e >= 0` of the c it got over that edge: after the clip the junction delivers exactly the c it held and received, and the rest of the edge's mass is its current mixture, which is what a flush delivers; before it, the A-blend delivered (A - 1)(n0 - n1) more c than the junction ever held.

The same comment states the remaining rules:
- *Energy.* The enthalpy field has no sign bound and is never clipped. The swap moves no mass, so no energy moves either: no per-species enthalpy is available, and each end keeps its specific field.
- *Solids.* They are swapped per population on non-filter edges only. An uncovered remainder is left and traced.
- *Passes.* The clip repeats up to junctions + 2 passes, because a swap can lower a downstream junction.
- *Safety check.* The recorded bookings are first checked against each junction's own rate and stage-one changes. On a mismatch the junction is left unclipped and a `STAGECLIP MISMATCH` line is printed; no stored run printed one.
- *Superseded first form* (runs 73-75), which returned the species without the swap, with energy at the edge's specific field. Under the pin, the junction's surplus mass was pinned out through stage two's outflows, which the Newton had not solved. The stage-two solve then failed its equation gate. Run 75 shows the gate failure itself: `Conservative reconstruction fails equation gate: 7.191226854822332E-6` at `TrBdf2StepSolver.integrate:179`. That mechanism comes from the comment and is not traced further.

**(c) Probes against run 69 (runs 73, 77, 77b).**

Static: 31/32 in all three runs, every line identical to run 69 apart from ms (the failure is 3:150:swap:unequal, IT 2.191412096162098e-5). Transients:

| Case | run 69 | run 73 (first clip form) | run 77 (swap + `history2`, B) | run 77b (final, B2) | metrics comp / energy (69 = 73; 77 = 77b) | mJ/m_J - 1 at 13 s / 16 s | accepted/rejected, all intervals (all four runs) |
|---|---|---|---|---|---|---|---|
| 0.05:4:false | PASS | PASS | PASS | PASS | 9.30e-15 / 2.64e-14 | 2.1e-16 / 2.1e-16 | 460/137 |
| 0.05:4:true | PASS | PASS | PASS | PASS | 9.81e-15 / 4.58e-15 | 0 / 0 | 524/173 |
| 0.05:5:false | PASS | PASS | PASS | PASS | 1.16e-14 / 8.83e-15 | 0 / 0 | 519/154 |
| 0.05:5:true | PASS | PASS | PASS | PASS | 6.71e-15 / 8.39e-15 | 0 / -2.1e-16 | 545/170 |
| 0.05:6:false | PASS | PASS | PASS | PASS | 6.65e-15 / 3.80e-15 | -2.1e-16 / 2.1e-16 | 530/166 |
| 0.05:6:true | FAIL step 100 IT 4.1095e-8 | same | same | same | 4.60e-15 / 2.86e-15 (to step 100) | - | 227/67 |
| 0.02:4:false | PASS | PASS | PASS | PASS | 3.21e-15 / 3.92e-15 | -1.7e-16 / -1.7e-16 | 584/128 |
| 0.02:4:true | PASS | PASS | PASS | PASS | 4.39e-15 / 2.80e-15 | -1.7e-16 / -3.3e-16 | 729/189 |
| 0.02:5:false | PASS | PASS | PASS | PASS | 4.53e-15 / 2.85e-15; **5.12e-15 / 2.79e-15** | -1.7e-16 / -1.7e-16; **-1.7e-16 / -3.3e-16** | 704/157 |
| 0.02:5:true | PASS | PASS | PASS | PASS | 4.50e-15 / 8.22e-15 | -3.3e-16 / -3.3e-16 | 701/190 |
| 0.02:6:false | PASS | PASS | PASS | PASS | 5.72e-15 / 9.15e-15 | 0 / -3.3e-16 | 700/180 |
| 0.02:6:true | PASS | PASS | PASS | PASS | 4.19e-15 / 6.27e-15 | -1.7e-16 / -1.7e-16 | 666/196 |

- **Run 73** is identical to run 69 in every HOLDUP_DYNAMIC, TRAJECTORY and METRICS line, ms aside.
- **Run 77** differs from run 69 only in 0.02:5:false, from the 16 s line on, and only in the last digits:
  - P changes by 4e-8 Pa; one edge's q moves from 1.14396e-11 to 1.14312e-11 kg/s;
  - metrics move as in the table; substep counts are unchanged.
  - The cause is the case's single `VOIDKEEP`: `history2` kept void edge 3 open at the start of one solve at rest (start flow 5.4e-10 kg/s into the junction) that `history` had closed. The pass loop closed it again, so the 16 s modes are unchanged. Run 77's 11474 `VOIDSTART` closures are otherwise the void edges of 7.8.
- **Run 77b**, with the final code and no traces, is line-for-line identical to run 77. B2 therefore changes nothing in the probes, and run 82's probe wrappers are identical to run 77b too.
- No probe run had the clip trace on. Whether the clip fired in the probes is not recorded. What is recorded is that it changed no verdict, no trajectory line and no substep count.

*The 0.05:6:true restart failure (run 73d, dump; open item 8 of 7.10).* Reproduced bit for bit (4.109527754711185e-8). The clip does not touch it: it is a rate-only solve.
- *Where it fails.* All 20 failed passes are the restart's rate-only solve (dt = 1, PORT graph). The last block has IT 4.11e-8 on edge 5's hydraulic/cap row (the z = -1 m void), 3.86e-8 on edge 1's (tank 2) and 6.7e-10 on edge 0's. Junction rows are at most 2.2e-16. A rate solve has no tank rows.
- *Start point.* Both ports and the junction are at 101318.98068 Pa, 6.0193 Pa below the voids. That is exactly the junction gas column to the z = -1 m void: rho_J g x 1 m = 0.61380 x 9.80665 = 6.0193 Pa. The island was at hydrostatic rest through void 5, with every void edge closed in the accepted state.
  - `VOIDSTART` closes void edges 2/3/4 (estimated inflows 0.0124/0.0124/0.0211 kg/s).
  - Edge 5 starts open at q = -0.0092 kg/s, out of the junction into void 5.
- *Failed point.* Flows are 6.1e-7 / -4.3e-7 / -1.8e-7 kg/s on edges 0/1/5, against caps of 0.12-0.19 kg/s, so no cap is active.
- *Classification:* edge hydraulic rows at zero flow in a rate solve whose start is an exact hydrostatic balance on its only open outlet. That is the zero-flow rest shape of 7.6/7.7 (runs 38, 43), reached at the restart. Not junction rows, not tank rows, not a cap, not a device.
- *Inference, not tested:* the donor-density head of edge 5 switches at q = 0 between the junction gas (0.614 kg/m³, zero driving pressure) and the void gas (0.975 kg/m³, 3.5 Pa pushing outward against an inflow). The hydraulic row is therefore not smooth at the solution.

**(d) NetworkRegimeTest and direct TR-BDF2 steps.**

| Run | Holdup, clip | `twoDifferentFeedsMixAtAZeroHoldupJunction` (line 34, dt = 1 s) | `reversingOneFeed...` (line 43, dt = 0.1 s) | Total |
|---|---|---|---|---|
| 70 (7.10) | tau 0.05 + pin, no clip | expected 0.6326450275296205 but was 0.5722427451894476 | expected 0.0 but was 44.07302495656579 | 7/9 |
| 74 | tau 0.05 + pin, first clip form | same values to the last digit | same | 7/9 |
| 81, 82 | tau 0.05 + pin, swap clip | same | same | 7/9 |
| 75 | fixed 1e-4 kg + pin, first clip form | expected 0.6396198572911722 but was 0.6656649859035046 (the 7.9 (e) overshoot, run 58's value) | `Nonconvergence: Conservative reconstruction fails equation gate: 7.191226854822332E-6` (stage-two solve), after `STAGECLIP junction id=3 c=19 dt=0.1 deficit=3.376e-4` | 7/9 |
| 75b | fixed 1e-4 kg + pin, swap clip | unchanged (0.6656649859035046) | **passes**: the same clip line, junction nitrogen clipped to 0 | **8/9** |

- At tau = 0.05 s neither fixture reaches the bound at its own dt (0.1 s and 1 s). The clip therefore does not fire there, and both failures stay the **intended changes** of 7.10 (h): the holdup's mixing lag and the nitrogen left at the junction.
- At 1e-4 kg the reversing fixture is a genuine flush (r >> sqrt 2).
  - The swap clip turns 7.9's exception into a pass. The nitrogen is gone because the clip removes the A-blend's excess, and the test's `0 to 1e-9` assertion then holds.
  - The mixing fixture keeps the 7.9 (e) overshoot. Nitrogen is in its inflow, so no base is negative and the clip has nothing to do. That stage-two extrapolation overshoot is **not** addressed by the clip; at tau = 0.05 s it does not occur (7.10 (b)).

*Direct steps up to dt_max (`JunctionStageClipProbe`, tau 0.05 + pin, m_J = 2.948e-3 kg; runs 75c clip on, 75d clip off):*

| Fixture, dt | 0.1 s | 1 s | 5 s | 20 s |
|---|---|---|---|---|
| mixing, clip on / off | PASS, CH4 0.142 | PASS, 0.572 | PASS, 0.680 | PASS, 0.668 (on = off to the last digit) |
| reversed, clip off (75d) | PASS, N2 44.07 mol | PASS, 4.03 mol | **Negative/nonfinite inventory** | **Negative/nonfinite inventory** |
| reversed, clip on (75c) | PASS, 44.07 mol | PASS, 4.03 mol | PASS, N2 0 (`STAGECLIP deficit=0.01634 outflowNet=0.1216`) | PASS, N2 2.2e-17 mol (deficit 0.02129) |

With the clip, one direct TR-BDF2 step at the product's dt_max passes on the flush fixture. The junction mass stays 2.948152e-3 kg in every step, and the step's own conservation check ran inside `TrBdf2StepSolver`.

**(e) Part B1: `voidStart=history2` (ElevatedBlockLineIslandTest, runs 76a, 76, 77).**

*What changes.* `history` closes, at the start of a solve, a passive connection whose start direction `boundaryAllowed` forbids. For an edge with no start flow (|q| <= 1e-10, which is every edge the previous accepted point had closed), the direction comes from `initialMassFlow` on the current states. `history2` adds one condition (`VOID_START_ROBUST`, `illegalWithEitherDensity`): the static driving pressure `P_a - P_b - rho g (z_b - z_a)` must have the forbidden flow's sign with rho = **either** endpoint's density. It is strictly narrower than `history`. On a level edge (dz = 0) it can only differ when the start flow disagrees in sign with the current pressure difference; the run 77 `VOIDKEEP` at rest (start flow 5.4e-10 kg/s) is inferred to be such a case.

*Cause (run 76a trace; the density rule verified in `initialMassFlow`).*
- `initialMassFlow` states the head with the density of the higher-pressure endpoint (`upstream = firstPressure >= secondPressure ? a : b`).
- In the falling line (400 kPa water generator 4 m above a nitrogen tank), the tank passes the generator's pressure while still short of the column. From then on the estimate reads the 4 m water column as 4 m of nitrogen and reports a backflow into the generator.
- The trace shows 113 `VOIDSTART` closures of the falling line's generator edge. The start flow is 0.0 (closed at the previous accepted point) and the estimate is -4.82 to -4.83 kg/s.
- A closed edge records zero, so each next start reads the same estimate and the edge stays closed. The tank stops at 434925.6987174727 Pa, 4156 Pa short of 439081.69 Pa, where the column still drives the flow forward.
- The dead-headed falling line of the same class had 76 such closures (-7.01 kg/s) and still settled correctly (140395.02 Pa).
- *Reconstructed from the code and the logs:* the ordering of the closures within the test (after the flat control, before the filter case's junction) places them on the falling line. The prototype's code comment gives the same account ("reports a 4.8 kg/s backflow into the generator while the column still drives 4.2 kPa forward").

*Fix (run 76): **4/4**.*
- The falling line settles at 439081.8102046798 Pa, 0.117 Pa from the column.
- 81 `VOIDKEEP` lines: 75 on the dead-headed falling line, 4 on the falling line (-4.82 to -6.58 kg/s) and 2 on the filter line.
- 2 `VOIDSTART` lines, on the filter line's generator edge (-0.063/-0.054 kg/s).
- Between two gas endpoints, as on the probes' void edges, both densities give the same sign and the rule fires as before (run 77: 11474 closures, 1 keep).

**(f) Part B2: `capForm=B2` (IslandCertificateTest, runs 78a, 78).**

*The change* (in `edgeRows`): the cap guard `canClamp(mode) && !boundaryClosed[edge]` gains `&& !(B2 && (deadEnd(a,edge) || deadEnd(b,edge)))`. `Equations.deadEnd(node, edge)` is true for a junction whose every other connection is boundary-closed or a device in mode `CLOSED`. On such an edge the cap is skipped; every other edge is capped exactly as under B.

*Cause (run 78a dump, `-PcapForm=B`, the dead-headed pump alone).*
- The failure is "dead-headed pump did not certify: no accepted interval". All 30 failed passes are `Singular Newton Jacobian` in the dt = 1 rate solve.
- The island is port 0 (101325 Pa, nitrogen) -> passive edge 0 -> junction 1 -> edge 1, a pump in mode `CLOSED` -> port 2 (800 kPa).
- *Start.* The junction is at 101325 Pa. Edge 0 starts at q = -1.713 kg/s against a cap of 0.225 kg/s, with its row at 27.7.
- *Failed iterate.* The junction is at 68433.8 Pa and edge 0 at q = -2e-21 kg/s. Edge 0's row is exactly 0.5, which is `limitDrop/(2 limitDrop)`: the form-B capped branch at zero flow, with the driving pressure of 32.9 kPa beyond the cap drop. The junction mass row is 2e-21.
- The capped branch has no pressure column in any form. The junction's only other connection is closed, so its mass row already fixes q = 0. The junction pressure then has no row, and the Jacobian is singular.

*Why B and not `old`* is the prototype's statement, in the `deadEnd` comment: "form B's re-weighting of the hydraulic row by pressureScale/min(pressureScale, 2 limitDrop) lets the line search accept exactly such a step". It is not measured: no `old` dump of this case exists. It passes at defaults (runs 72, 83) and fails with B alone (run 71g).

*Fix.* At a solution the cap on such an edge can never be active, because the mass row forces zero flow, so B2 leaves only the hydraulic row that places the junction pressure. Run 78: IslandCertificateTest **22/22**, and the dead-headed pump certifies REST at tick 200.

**(g) Part C: the two remaining defects (runs 79, 80; last dump block of each).**

| Test | Failure | Last dump | Rows | Classification |
|---|---|---|---|---|
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` (run 79) | line search stalled at 8.234888610726238e-8 (as runs 71/82) | Stage solve at dt = 2.23e-8 s. Generator (slurry) -> J1 -> pump (PUMP_TARGET) -> J2 -> J4 -> nitrogen receiver; 9.96 kg/s on every edge. J1 99.0 kPa, J2 118.1 kPa, J4 109.2 kPa; start max 4.34 on J1's enthalpy row. m_J = 9.778 kg per junction (`HOLDUP_SIZE`, velocityLimit 100 m/s). | **Junction rows**: the enthalpy (mixing) rows of J2 and J4 at 8.2349e-8; tank rows 1.4e-16; hydraulic rows 3e-17 | junction rows; **1/dt rounding floor** (below) |
| `FullTankSolidsEventTest.theFilterCapturesOnceTheDrainedTankLetsTheLineFlow` (run 80) | HELD at interval 6/40, line search stalled at 23.81232618852451 (as runs 71/82) | **The stall is not dumped.** The 200-dump budget was used up earlier by 200 equation-gate rejections, all at tank pressures of 161.9-164.4 kPa. In each the Newton converged (at most 9.8e-10) and the reconstructed residual (1.0e-8 to 5.8e-4) was largest on the tank row (node 2 RESERVOIR local 2). Last one: junction J1 292.9 kPa (38.82 kg/s in), J4 258.1 kPa (35.31 kg/s after the filter), tank->void 25.76 kg/s against a cap of 27.45; all edges passive. 684 `PINSKIP`, all at zero flow. | earlier rejections: **tank rows** (reconstruction gate); the stall: unknown | **not classifiable from the stored files** |

*SolidRuntime, the 1/dt floor.*
- *The numbers.* The eleven dumped failures are three large ones at dt = 1.1e-3, 5.7e-4 and 2.9e-4 s (2.52, 0.74 and 0.36, all on J1's enthalpy row), then eight at dt = 1.43e-6 down to 2.23e-8 s. In those eight the stalled residual times dt is 1.84e-15 in every one (1.29e-9 x 1.43e-6 = ... = 8.23e-8 x 2.23e-8).
- *Why it grows as the step shrinks* (from the code): under the default flux form the junction mixing rows are multiplied by `junctionWeight = Q + m_J/dt` (`junctionInflow`). Their achievable accuracy is therefore that weight times the rounding of a scaled O(1) difference: 1.84e-15/9.778 kg = 1.9e-16, one unit of rounding.
- *Consequence.* Every refinement below about 1e-6 s makes the floor larger. Once the step controller has to go there, the substep refinement cannot succeed ("exhausted").
- *Reconstructed from the code and the dump, not tested:* this explains why refinement fails. What drives the step down to 1e-6 s in the first place is **not understood**: the first three failures at dt ~1e-3 s on J1's enthalpy row, with a start mismatch of 4.34.
- Present since the 7.9 configuration (7.10 (h): 71a, at 1e-4 kg: 4.95e-7), so it is independent of sizing, pin and clip.

*FullTankSolidsEvent.*
- *The 9.78 kg does come from the 100 m/s velocity limit on a liquid.* `HOLDUP_SIZE` prints m_J 9.781210336977425 kg = tau 0.05 s x capFlow 195.624 kg/s. That capFlow is rho_seed 996.306 kg/m³ x A 1.9635e-3 m² (50 mm) x velocityLimit 100.0 m/s, evaluated by `sizeJunctionHoldups` on the water seed state.
- *Classification stays as in 7.10:* a defect of the pin at a liquid-sized holdup (71b: tau without the pin passes; 71d: the pin at 1e-4 kg passes). The mechanism is **not understood**.
- The stored dump documents only the earlier, recovered equation-gate rejections on the tank row. The HELD state (J1 398.7 kPa, J4 102.9 kPa, tank 101.7 kPa, void 101.3 kPa, 23.97 kg captured) shows where interval 6 starts.
- A re-run with the dump budget reserved for failed passes (or `-PjacobianDump` large enough) is needed to classify the stall.

**(h) Gates.**

*Run 81, the 38 adjacent tests, everything on: 36/38.*

| Class | run 70 (7.10) | run 81 |
|---|---|---|
| FilterBlockLineIsland | 11/11 | 11/11 (phantom-trace gate passes) |
| NetworkRegime | 7/9 | 7/9, the same two intended changes, same values |
| PassiveStepSolver, PumpJunctionStartup | 6/6, 1/1 | 6/6, 1/1 |
| DeadHeaded, PhysicalFluidTopology, PipePresentation | 4/4, 4/4, 3/3 | 4/4, 4/4, 3/3 |
| Total | 36/38 | **36/38** |

*Run 82, all fluid suites, everything on: 397/401 in 93 classes.* The count is the 399 suite tests plus the two probe wrappers; their lines are identical to run 77b.

| Test | Assertion | run 71 (7.10) | Class |
|---|---|---|---|
| `NetworkRegimeTest.twoDifferentFeedsMixAtAZeroHoldupJunction` | line 34, expected 0.6326450275296205 but was 0.5722427451894476 | same | **intended change** (holdup mixing lag) |
| `NetworkRegimeTest.reversingOneFeedSwitchesJunctionUpwindingAndSerialValvesRemainSolvable` | line 43, expected 0.0 but was 44.07302495656579 | same | **intended change** (nitrogen left at the junction after 0.1 s) |
| `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` | line 94, line search stalled at 8.234888610726238e-8 | same | **defect** of the owned-holdup family: junction enthalpy rows, flux-form 1/dt rounding floor under refinement ((g)); trigger **not understood** |
| `FullTankSolidsEventTest.theFilterCapturesOnceTheDrainedTankLetsTheLineFlow` | line 238, HELD at interval 6/40, stalled at 23.81232618852451 | same | **defect** of the pin at a liquid-sized (9.78 kg) holdup; stall not dumped, **not understood** |
| `ElevatedBlockLineIslandTest.elevatedLinesIntegrateFortyIntervalsLikeTheirFlatControl` | - | failed (`history`) | **passes** (`history2`) |
| `IslandCertificateTest.deadHeadedLinesCertifyRestAndThenCostNothing` | - | failed (B) | **passes** (B2) |

The exact regression (`FluidSolverRegressionTest`, task `fluidSolverRegression`) is not in these suites and was not run.

*Run 83, the same suites at defaults, no init script: **399/399** in 91 classes, 0 skipped.* This is pass/fail neutrality of the switched-off patch. No bitwise comparison with the base was made.

**(i) Verdict.**
- *The clip.* The swap clip removes the stage-two positivity bound for an owned junction without touching its mass, the pin or the Newton's rows, and the step ledger stays exact.
  - One direct TR-BDF2 step of the flush fixture passes at 5 s and at the product's 20 s dt_max, where it threw before.
  - At 1e-4 kg the NetworkRegime reversing fixture passes outright.
  - It changes nothing in the probes or the gates at tau = 0.05 s.
- *Two switch defects fixed.*
  - `history2` removes the void-start misfire on a falling liquid column (Elevated 4/4).
  - `B2` removes the singular capped dead-end (IslandCertificate 22/22).
  - Neither changes a probe verdict.
- *Remaining failures with everything on.*
  - Two intended NetworkRegime changes.
  - Two defects: the SolidRuntime floor, whose refinement failure is explained but whose trigger is not, and the FullTankSolids pin stall, which is not dumped.
  - The 0.05:6:true restart failure remains; it is now classified as a zero-flow hydraulic stall in the restart's rate solve.
- *Defaults.* The switched-off patch keeps 399/399.
- *Run map to the brief:*

| Brief | Stored runs |
|---|---|
| Part A | 73, 73d, 74, 75, 75b, 75c, 75d |
| Part B1 | 76a, 76, 77 |
| Part B2 | 78a, 78, then 77b as the final-code probe check |
| Part C | 79, 80 |
| Part D | 81, 82, 83 |

**Open (replaces 7.10's list).**
1. **SolidRuntime stall** ((g)): the flux-form weight `Q + m_J/dt` makes the junction rows' rounding floor grow as 1/dt, so refinement below about 1e-6 s cannot converge. The trigger at dt ~1e-3 s (J1's enthalpy row, start mismatch 4.34) is not understood. A candidate to test, not tested: scale the flux-form weight by `max(Q, m_J/dt)` or state the holdup rows per unit of that weight.
2. **FullTankSolidsEvent pin stall** at the 9.78 kg water junctions: not dumped, not understood. Re-run with the dump budget kept for failed passes.
3. 0.05:6:true restart failure (tau >= 5e-3 kg): a zero-flow hydraulic stall in the restart's rate solve at an exact hydrostatic rest through the z = -1 m void ((c)). Not fixed.
4. The mint and sizing booking through `PhysicalRegistry.apply` is untested. `PhysicalRegistryTest` (8 tests) passes in run 82, but whether it mints a junction and checks the world-ledger booking was not examined.
5. `IslandCertificate` skips junction inventories: the REST/STEADY summary, the `graphAt` replay, and a digest without the volume. With the pin a junction is constant in mass, not in composition.
6. Remint at every topology edit resets a junction's composition and size to the first boundary's state.
7. Rate-cache eviction of the accepted point's own entry after eight insertions.
8. Exact regression `FluidSolverRegressionTest`: a re-record by intent (junctions own an inventory and mix with a lag), plus a bitwise neutrality check at defaults. Neither run.
9. Cost, not timed: the per-step re-booking of the rate (7.9 open 9), `pinnedFlows` (O(junctions x edges) per reconstruction) and now the clip's recording of two bookings per step plus `clippedSecondBase`.
10. Resize on phase change: needed if a fitting can change phase (7.10 (c)).
11. Liquid holdup sizing: the 100 m/s velocity limit gives 9.78 kg water junctions at tau = 0.05 s, a fitting cannot hold that much, and it is the size at which the pin stalls (item 2).
12. The clip's physics shortcuts:
    - the swap moves no energy, so no species' enthalpy is exchanged;
    - solid deficits on filter edges are left uncovered;
    - the mixing fixture's stage-two overshoot at small m_J (7.9 (e)) is not addressed.
    None was measured to matter at tau = 0.05 s.
13. The sizing rule's `max over connections at the seed state` understates Q by 2.2-2.6x in the probes (7.10 open 9).
14. Prototype shortcuts a real implementation must replace:
    - m_J carried in the junction inventory's volume field;
    - `JUNCTION_HOLDUP = Double.MIN_VALUE` as a flag;
    - the placeholder sentinel for an unsized mint;
    - the balanced seed, which is not behind a switch;
    - the thread-local booking recorder of the clip.
    A real implementation needs an owned-mass field in the node and the checkpoint: a format change, tested on a fresh world.
15. **Switch set a production version would collapse into defaults** (and delete the rest):
    - owned holdup (`createcheme.junctionHoldup`/`junction.holdupTau` as a sizing rule), `mintHoldup=on`, `rateForm=frozen`, `rateWarmStart=fresh`;
    - `voidStart=history2`, `capForm=B2`, `stageForm=implicit`, `pinMass=on`, `stageClip=on`;
    - `fluxForm=true` and `flowFloor=1`, which are the defaults already;
    - the balanced seed.
    The trace and dump switches (`jacobianDump`, `ledgerDebug`, `boundaryDump`, `rateWarmStartTrace`, `voidStartTrace`, `stageFormTrace`, `passTrace`, `sizeTrace`, `pinTrace`, `stageClipTrace`) and the superseded forms (`capForm` old/A/B, `rateForm=regularised`, `voidStart` on/history, `stageForm=explicit`) go.

## 8. Cost structure and a cheaper basis (2026-09-25, owner request)

Owner requirements, stated after 7.11: **no nonconvergence may remain**, and the fix must be cheap in CPU and memory. Assumptions, the working basis and error tolerances are all open to change. This section reads the cost off the stored runs and the code, separates what is enforced by tolerance from what is enforced by construction, and proposes the change of basis that removes most of the cost and most of the nonconvergence classes together. Nothing here is measured yet unless marked *measured*.

### 8.1 Where the cost is (*measured*, run 77b/82, final chain, 12 cases × 160 intervals of 0.1 s)

- Accepted substeps per case 460–729, rejected 128–196: **2.9–4.6 accepted attempts per 0.1 s interval and a 22–27 % rejection rate**. Every attempt is one TR stage Newton, one BDF2 stage Newton, the companion estimate, three reconstructions and a conservation check; on a cache miss also the dt = 1 rate solve with the balanced seed (up to 8 × (36 + 30) flashes per junction).
- Wall time 0.53–0.83 s per case for 16 s of simulated time on a five-node island: about 0.5 ms per Newton solve, so the count, not the solve, is the cost. A flowing island of this size costs about 5 % of a core; an island at rest costs nothing (certificate).
- What binds the step is not recorded in these runs; `SolverDiagnostics.attempt` records the rejection reason (embedded boundary / pipe / state error, step refinement error, Newton failure) and is off by default. The first measurement is to switch it on.

### 8.2 What is enforced by tolerance and what by construction

| Quantity | Enforced by | Tolerance | Consequence of relaxing |
|---|---|---|---|
| Species, solids, energy conservation | construction: `ConservativeTransport.reconstruct` + `checkConservation` | 1e-10 + 1e-8 relative (a check, not a knob) | not needed and not proposed |
| Junction mass = m_J, stage positivity | construction (pin, clip) | exact | none |
| Equation residuals (hydraulics, mixing, phase) | Newton | 1e-9 (1e-10 small headspace); APPROXIMATE path 1e-6 with `checkApproximation` | pressures to 1e-2 Pa instead of 1e-4 Pa; ledger unchanged |
| Reconstruction gate | check after reconstruction | 1e-8 (1e-6 APPROXIMATE) | must stay ≥ the Newton tolerance |
| Trajectory accuracy | TR-BDF2 embedded estimate | `Settings.relativeTolerance` 1e-3; boundary floors 1e-10 per component (1e-4 water) | larger steps, fewer rejections; the 5 s presentation and the rest certificate are the only consumers |
| Rest / steady certification | certificate | eps_s 1e-9 (separate batch) | unchanged |

So **every relaxation that buys speed is on the equation and trajectory tolerances, and none of them touches conservation**, which the exact reconstruction guarantees at any Newton tolerance.

### 8.3 The nonconvergence classes seen in this batch, and which survive a tolerance change

1. Zero-flow junction mixing rows (7.2): removed by the holdup. Not a tolerance matter.
2. Stage-two positivity of a tiny stock (7.9): removed by the clip; would not exist under backward Euler at all.
3. Rate-solve failures that refinement cannot help (7.7): the dt = 1 rate solve is re-solved bit-identically 16–20 times per refinement chain. A rate solve should never trigger refinement; under backward Euler there is no rate solve.
4. **Rounding floors above the tolerance at small steps** (7.5's 1.42e-9 tank rows; 7.11's SolidRuntime enthalpy rows where residual × dt = 1.84e-15 is constant): rows stated in rate form carry a rounding floor ε·E/(dt·scale) that grows as the step shrinks, so every refinement chain ends at an unreachable tolerance. This is a defect of the row form, not of the solver: state those rows in amount form (multiply by dt) or make the tolerance dt-aware. Under a 1e-6 tolerance it is invisible until dt < 1e-10 s.
5. Iteration-limit crawls at 1.3–7× the tolerance (many 50 mm dumps): a tolerance at the rows' noise floor. Same fix as 4, or the APPROXIMATE tolerance.
6. Zero-flow hydraulic stall in the 6-port restart rate solve (7.11): class 3.

Classes 2, 3 and 6 disappear with the change of basis below; 4 and 5 need the row-form fix or the looser equation tolerance; 1 is solved.

### 8.4 Proposed change of basis: backward Euler for transients, no rate solve

TR-BDF2 buys second-order trajectory accuracy at the price of two Newton solves plus a companion per attempt, a dt = 1 rate solve on every cache miss (the whole frozen/relaxed booking, stage carry, clip and warm-start machinery of 7.6–7.11 exists only to make that rate solve and the extrapolating stage base consistent with a tiny stock), an 8-entry cache of full projections per island, and a second `PassiveStepSolver` instance. The consumers of that accuracy are a 5 s presentation bucket and a rest certificate that stops solving once the island is still.

`PassiveStepSolver.solve(graph, dt)` already **is** a backward-Euler step with the exact reconstruction: one Newton solve, one reconstruction, one check. Proposal, behind a switch in `PassiveIntervalSolver`:

- Step the interval with `implicit.solve` directly. No rate solve, no stage bases, no companion, no endpoint cache, no `algebraic` solver instance. The holdup row is the plain vessel row (`m_J/dt` weight); the base is `n0`, so positivity holds without a clip; the frozen/relaxed bookings, the stage carry and the clip are not needed.
- Step control without an embedded estimate: accept a step if the island's state change is within a cap (largest relative pressure or mass change per node ≤ a few percent, and a velocity-cap or device-mode transition is at most one per step), grow by ×2 otherwise ×0.5; optionally a step-doubling estimate every N-th step for the trajectory-error budget. Any rejected step is a Newton failure, never an accuracy rejection of a converged solve, so the counts of 8.1 fall by construction.
- Equation tolerance: run transients on the existing APPROXIMATE path (1e-6 Newton, 1e-6 gate, `checkApproximation`) and keep FULL for the rest certificate. This removes classes 4 and 5 without touching the ledger.
- The balanced seed stays for the first solve after compile or a topology change (the only cold start left).

Expected cost per attempt: one Newton solve instead of two plus a companion (≥ 2.5× fewer), no rate solves, no cache; attempts per interval fall with the accuracy rejections gone. Memory per island: one accepted graph and one workspace instead of eight cached projections, two solver instances and four workspaces each. These are expectations to be measured, not results. Accuracy: first order in time; with the state-change cap the local error on a 1 s blowdown at 0.1 s steps is below 1 %, invisible at the presentation and irrelevant at rest.

### 8.5 Measurement plan

1. Baseline with `SolverDiagnostics` on: the final chain (7.11 patch) on the 12 transients and the static matrix — attempts by rejection reason, Newton solves and iterations, passes, LU time, seed flashes, allocation via the diagnostics if it counts it, else the in-game rig later.
2. Backward-Euler mode behind `junction.integrator=be` with the state-change step controller, FULL tolerance: the same counters; zero nonconvergence is the acceptance bar, then the cost.
3. The same in APPROXIMATE acceptance.
4. The row-form fix for class 4 behind a switch, measured under TR-BDF2 and BE.
5. The 38 gates and the fluid suites in each configuration (defaults must stay 399/399).

### 8.6 Measurements (runs 84-90)

Run 2026-09-25 in the same worktree at 9674bf1 by Claude (Opus 5.5).
- *Code.* `holdup-prototype-clip.patch` plus the backward-Euler basis and the cost instrumentation. The full diff is `tools/junction-holdup-prototype/holdup-prototype-be.patch`: nine tracked files, the eight of the clip patch plus `solver/SparseNewton`. It contains every earlier patch, so apply it alone.
- *Verified.* `--check --reverse` on the patched tree and `--check` on the restored base both pass; `git status --short` shows nothing tracked.
- *Conditions.* Gradle calls one after another, no dev client, nothing committed, no tracked test modified, `checkConservation` and the reconstruction gate unchanged. Environment: `JAVA_HOME=C:/Program Files/Java/jdk-21.0.11`, `JAVA_OPTS=-Xshare:off`.
- *Timing caveats.* Every probe `ms` includes the diagnostics' own overhead. The static probe runs first in the JVM, so its ms carries JIT warm-up.

Commands (PowerShell, from the worktree). `BASE` = `-PcapForm=B2 -PrateForm=frozen -PrateWarmStart=fresh -PvoidStart=history2 -PmintHoldup=on -PstageForm=implicit '-PholdupTau=0.05' -PpinMass=on -PstageClip=on`; `FINAL` = `-Pintegrator=be -PbeModeRule=off -PbeColdStart=rate`.
- *Probes:* `./gradlew.bat --no-configuration-cache -I tools/junction-holdup-prototype/holdup.init.gradle test --tests '*JunctionHoldupStaticProbe' --tests '*JunctionHoldupTransientProbe' BASE -PsolverDiag=true [switches] --console=plain`.

  | Run | Switches |
  |---|---|
  | 84 | none |
  | 85, and 85e/85t/88/88b (the 85e code) | `-Pintegrator=be` |
  | 87 | `-Pintegrator=be '-PbeStateCap=0.05'` |
  | 85f, 85g | `FINAL` |
  | 86b | `FINAL -Pacceptance=approximate` |
  | 87b | `FINAL '-PbeStateCap=0.05'` |

  Dumps add `'-PjunctionCases=<case>' -PjacobianDump=N -PbeTrace=true` and run the transient probe only.
- *Gates, 88g/88h:* `... -I .../holdup.init.gradle test --tests '*PassiveStepSolverTest' --tests '*PumpJunctionStartupTest' --tests '*FilterBlockLineIslandTest' --tests '*DeadHeadedLineIslandTest' --tests '*NetworkRegimeTest' --tests '*PhysicalFluidTopologyTest' --tests '*PipePresentationTest' BASE FINAL --console=plain --continue`.
- *Suites, 89c:* `--tests 'com.wormzjl.createcheme.science.fluid.*' --tests 'com.wormzjl.createcheme.runtime.fluid.*' BASE FINAL --continue`.
- *Run 90:* the same suites with no `-I` and no property.

#### (a) Run 84: where the TR-BDF2 chain spends (*measured*, final chain of 7.11, diagnostics on)

Every probe line of run 84 is identical to run 77b (ms aside), so the instrumentation only prints and counts.
- *Rejection reasons.* `PassiveIntervalSolver` calls `SolverDiagnostics.attempt` with `dominant` = `boundary`, `pipe` or `state` (the embedded estimate) or `refinement` (step doubling). The prototype also logs every attempt that threw: `newton|<message>`, `other|<message>` for an `IllegalArgumentException`, and `transition`.
- *Allocation.* `SolverDiagnostics` counts no allocation, so the probe prints `alloc=n/a`. It also prints the probe thread's allocated bytes from `ThreadMXBean`.
- *Seed flashes.* A prototype counter around `balancedBoundarySeed` counts its applications and the `flashTP` calls inside them.

| case | ms | accepted attempts | rejected | rejected by | Newton solves | Newton/accepted | iter/Newton | backtracks | Jacobians | LU ms | companion solves/filters | rate builds | seeds/seed flashes | flashes | alloc MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.05:4:false | 929 | 452 | 137 | embedded-boundary 96, embedded-pipe 14, refinement 17, newton 2, other 8 | 1451 | 3.21 | 6.09 | 1263 | 762 | 9.5 | 101/480 | 34 | 1/248 | 9291 | 796 |
| 0.05:4:true | 795 | 514 | 173 | embedded-boundary 98, embedded-pipe 53, refinement 16, newton 2, other 4 | 1725 | 3.36 | 5.37 | 1350 | 687 | 7.5 | 186/497 | 33 | 1/248 | 11968 | 940 |
| 0.05:5:false | 760 | 496 | 154 | embedded-boundary 94, embedded-pipe 35, refinement 17, newton 2, other 6 | 1732 | 3.49 | 5.98 | 1129 | 959 | 8.5 | 147/495 | 34 | 1/248 | 10880 | 943 |
| 0.05:5:true | 723 | 517 | 170 | embedded-boundary 97, embedded-pipe 55, refinement 16, other 2 | 1890 | 3.66 | 5.05 | 980 | 696 | 6.4 | 199/486 | 33 | 1/248 | 11753 | 976 |
| 0.05:6:false | 831 | 518 | 166 | embedded-boundary 77, embedded-pipe 60, refinement 24, newton 1, other 4 | 2214 | 4.27 | 6.88 | 1403 | 1335 | 9.9 | 374/306 | 35 | 2/496 | 13509 | 1210 |
| 0.05:6:true (FAIL step 100) | 370 | 220 | 87 | embedded-pipe 50, refinement 16, newton 20, other 1 | 1040 | 4.73 | 6.00 | 600 | 426 | 3.5 | 139/147 | 21 | 1/248 | 6393 | 529 |
| 0.02:4:false | 630 | 579 | 128 | embedded-boundary 91, embedded-pipe 26, refinement 6, newton 1, other 4 | 1583 | 2.73 | 6.00 | 656 | 512 | 4.3 | 79/624 | 32 | 1/248 | 12587 | 948 |
| 0.02:4:true | 789 | 725 | 189 | embedded-boundary 103, embedded-pipe 74, refinement 10, newton 1, other 1 | 2091 | 2.88 | 5.03 | 850 | 454 | 4.8 | 165/748 | 32 | 1/248 | 14690 | 1124 |
| 0.02:5:false | 722 | 698 | 157 | embedded-boundary 98, embedded-pipe 43, refinement 11, newton 4, other 1 | 1959 | 2.81 | 5.51 | 871 | 491 | 5.2 | 91/762 | 34 | 1/248 | 13929 | 1114 |
| 0.02:5:true | 774 | 696 | 190 | embedded-boundary 92, embedded-pipe 83, refinement 13, newton 1, other 1 | 2132 | 3.06 | 5.23 | 844 | 524 | 5.4 | 173/712 | 33 | 1/248 | 14685 | 1170 |
| 0.02:6:false | 792 | 695 | 180 | embedded-boundary 99, embedded-pipe 59, refinement 9, newton 1, other 12 | 2041 | 2.94 | 5.44 | 746 | 522 | 5.7 | 95/768 | 33 | 1/248 | 14249 | 1177 |
| 0.02:6:true | 779 | 659 | 196 | embedded-boundary 91, embedded-pipe 90, refinement 14, other 1 | 2072 | 3.14 | 5.21 | 883 | 557 | 5.7 | 154/700 | 33 | 1/248 | 14182 | 1171 |
| **transient total** | **8894** | **6769** | **1927** | embedded-boundary 1036, embedded-pipe 642, refinement 169, newton 35, other 45 | **21930** | **3.24** | 5.62 | 11575 | 7925 | 76.5 | 1903/6725 | 387 | 13/3224 | 148116 | 12097 |
| static total (32 cases) | 1036 | 95 | 86 | embedded-pipe 57, refinement 9, newton 20 | 427 | 4.49 | 8.64 | 436 | 412 | 13.7 | 0/161 | 55 | 55/13640 | 14760 | 717 |

- **The embedded boundary error is the dominant rejection**: 1036 of 1927 (54 %).
  - `boundaryError` is non-zero only while a scheduled transfer exists, so all of them fall in the 3 s methane injection (steps 100-129): about 86 per case in 30 intervals.
  - The rest: the embedded pipe error 642 (33 %), step refinement 169, `Negative/nonfinite inventory` 45 (`other`), and Newton 35 (20 of them the 0.05:6:true restart chain).
- **3.24 Newton solves per accepted attempt, 5.62 iterations per solve.** The solves are:
  - the two stages;
  - the companion full solves: 1903, against 6725 linear filters;
  - 387 endpoint-rate builds, 32-35 per case;
  - the rejected attempts.
- **Seeds: 13 for the 12 cases**, 248 flashes each and 3224 in all (the 6-port case seeds twice). The seed is a cold-start cost, 2 % of the 148116 flashes.
- **Allocation: 0.8-1.2 GB per 16 s case**, 12.1 GB for the twelve.
- LU time is 76.5 ms of 8894 ms. The cost is the number of solves and property calls, as 8.1 inferred.

#### (b) The backward-Euler basis as built (`junction.integrator=be`)

- *Step.* `PassiveIntervalSolver.runBackwardEuler` calls `implicit.solve(graph, h, checkpoint, acceptance)` of the TR-BDF2 solver's own stage solver, reached through a new accessor `TrBdf2StepSolver.implicitSolver()`. That call is one backward-Euler step:
  - Newton with its active-set passes;
  - `ConservativeTransport.reconstruct` with dt = h, where the pin books `endMass = m_J`;
  - the equation gate;
  - `checkConservation`.
- *What is never reached.* The frozen and relaxed rate bookings, the stage-one re-booking, the stage clip and `PipeTransfer.relaxed` exist only inside `TrBdf2StepSolver`. `FROZEN_RATE` acts only in rate-only solves. The junction row is the `regularised` path of `junctionInflow` with dt = h.
- *Bookkeeping.* The per-step `Result` carries states, inventories, boundaries, pipe transfers and pump work. The interval sums them as it sums TR-BDF2 trials (`transferred += h q`, `pipeTransfers.add(..., 1)`).
- *Kept.* Mint, tau sizing, pin, cap form B2, void start `history2`. `rateWarmStart=fresh` acts only on rate-only solves. A backward-Euler step is a stage solve and takes the stage branch: previous flows whenever the pipe identities and node ids match.
- *Controller.*
  - Accept a converged step when the largest relative change of a RESERVOIR node's pressure (over max(100 Pa, P0)) or state mass is at most `junction.beStateCap` (default 0.02). Otherwise reject and halve.
  - Junctions are not in the measure: their pressure is algebraic (the Newton's net-mass row) and their mass is pinned.
  - Growth after an accepted step: `clamp(0.9 sqrt(cap/change), 0.5, 2)`, and 2 at zero change, bounded by `maximumStep` and the interval end.
  - Newton, gate and conservation failures halve as before. `maximumAttempts`, the twenty-consecutive-rejection limit and the transition floor `max(1e-6, 1e-9 duration)` are kept.
- *The one-transition rule of 8.4* (`junction.beModeRule=strict`, the default): a converged step with more than one accepted-mode change against the previous accepted step (carried across intervals) is refused and halved. The final configuration is **`off`**, see (c) and (f).
- *Additions the runs forced* (all behind `be`):
  1. *Cold-start seed eligibility (run 85d).* With no rate solve the balanced seed never reached a junction whose neighbours are tanks. Vessel neighbours are now eligible **until the interval solver has accepted a backward-Euler step of the structure** (`markBackwardEulerAccepted`/`backwardEulerCold`). Two other conditions failed first:
     - eligible at every solve, the seed fired 480 times in one case at hydrostatic rest (run 85b, 6.5 s);
     - keyed on the step solver's first converged solve, a converged step the state cap refused left the halved retry unseeded (run 85c, 2 failures).
  2. *Cold-start rate solve (final, `junction.beColdStart=rate`).* One rate solve of the port graph at the cold start; its junction states start the first solves. Run 88d: a pumped line into a dry tank, with three 9.78 kg liquid junctions, fails at every step size from the compiled guess. TR-BDF2's stage gets the same states from its endpoint rate.
  3. *A separate bound for mode refusals.* They have their own limit (40, as the solid-transition search) and do not count towards the twenty consecutive rejections. Run 88: 20 halvings from a 5 s step end at 4.8e-6 s, above the floor, and the interval failed on the rule itself.
  4. *Algebraic jumps.* A change that halving did not reduce (at least 0.9 of the refused change at twice the step) is accepted. Run 88d: water into a dry gas tank drops its pressure about 5 % at every step size.
  5. *Start-of-step guard.* `checkRate` (the solid event integrator's capacity and mobility test) reads the previous accepted endpoint. On the first step of a call it reads the flows carried from the last call, when no connection's identity or closure changed. Otherwise, and only for a non-trivial guard, it runs one port-graph rate solve.
- *`SolidEventIntegrator` under `be`*: not entangled.
  - Its segments call `integrateToTransition`, which reaches `runBackwardEuler`.
  - Transitions thrown by `checkFilters`/`checkRate` are handled exactly as on the TR-BDF2 path.
  - Its own interval-start `rate()` pre-check is unchanged.
- *`junction.acceptance=approximate`*: `PassiveIntervalSolver.solve` runs the interval at `Acceptance.APPROXIMATE` (1e-6 Newton and gate, `checkApproximation`).
  - It does so whenever `SolidMobility.requiresFull` allows it and the island does not go to the solid event integrator. That is the same admission test as `solveApproximate`, whose stage guard also refuses solids.
  - It applies the same `InventoryEquilibrium.refresh` a FULL interval starts with (`run()` refreshes only under FULL).
  - Under `be` an `ApproximationRejected` from a step is a halving rejection instead of escaping the interval.

#### (c) Backward Euler, FULL acceptance (runs 85-85g; nonconvergence first)

| Run | Configuration | static nonconvergence | transient nonconvergence | transient ms | accepted / rejected attempts | rejected by | Newton solves (per accepted) | iter/Newton | flashes | alloc GB |
|---|---|---|---|---|---|---|---|---|---|---|
| 84 | TR-BDF2 (reference) | 1 (3:150:swap:unequal) | 1 (0.05:6:true, step 100) | 8894 | 6769 / 1927 | boundary 1036, pipe 642, refinement 169, other 45, newton 35 | 21930 (3.24) | 5.62 | 148116 | 12.10 |
| 85 | be, strict rule, seed as before | 0 | **3** (step 0: 0.05:4:true, 0.05:6:true, 0.02:5:true) | - | - | - | - | - | - | - |
| 85b | + vessel neighbours eligible at every solve | 0 | 0 | 11248 | 3006 / 1100 | be-mode 1006, be-state 89, newton 5 | 5881 (1.96) | 6.29 | 219936 | 11.53 |
| 85c | seed until the first converged solve | 0 | **2** (step 0: 0.05:5:true, 0.05:6:true) | - | - | - | - | - | - | - |
| 85e | seed until the first accepted step, strict rule | 0 | **0** | 1742 | 2440 / 532 | be-mode 434, be-state 89, newton 9 | 3586 (1.47) | 4.48 | 27113 | 1.89 |
| **85f** | **final: rule off, cold rate solve** | **0** | **0** | **1167** | **2014 / 94** | be-state 90, newton 4 | **2323 (1.15)** | 4.55 | 18461 | **1.28** |
| 85g | final code (after the guard changes of 89b/89c), final configuration | 0 | 0 | 1290 | identical to 85f line for line (ms aside) | | | | | |

*Static matrix.*
- Run 84: 1036 ms, 95 accepted / 86 rejected attempts, 427 Newton solves.
- Run 85f: **32/32**, 637 ms, 35 / 3, 72 Newton solves (35 steps, one per case, plus the cold rate solve). The TR-BDF2 failure 3:150:swap:unequal passes: its 20 failed passes were rate solves.

The transient 0.05:6:true restart failure of 7.11 (c) was a rate-solve stall, and it is gone with the rate solve.

Per case, run 85f:

| case | ms | accepted attempts | rejected | rejected by | Newton solves | Newton/accepted | iter/Newton | backtracks | Jacobians | LU ms | companion solves/filters | rate builds | seeds/seed flashes | flashes | alloc MB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.05:4:false | 142 | 177 | 17 | newton 2, be-state 15 | 195 | 1.10 | 3.03 | 115 | 71 | 1.0 | 0/0 | 0 | 1/248 | 1582 | 106 |
| 0.05:4:true | 100 | 175 | 15 | be-state 15 | 195 | 1.11 | 2.74 | 55 | 67 | 0.9 | 0/0 | 0 | 1/248 | 1492 | 99 |
| 0.05:5:false | 110 | 175 | 15 | be-state 15 | 192 | 1.10 | 3.31 | 31 | 76 | 0.9 | 0/0 | 0 | 1/248 | 1646 | 109 |
| 0.05:5:true | 90 | 175 | 15 | be-state 15 | 197 | 1.13 | 2.68 | 47 | 64 | 0.8 | 0/0 | 0 | 1/248 | 1506 | 101 |
| 0.05:6:false | 119 | 175 | 15 | be-state 15 | 264 | 1.51 | 5.07 | 173 | 81 | 1.3 | 0/0 | 0 | 1/248 | 1746 | 130 |
| 0.05:6:true | 116 | 176 | 16 | newton 1, be-state 15 | 298 | 1.69 | 3.75 | 203 | 165 | 1.9 | 0/0 | 0 | 1/248 | 1784 | 133 |
| 0.02:4:false | 89 | 160 | 0 | - | 163 | 1.02 | 6.02 | 12 | 30 | 0.6 | 0/0 | 0 | 1/248 | 1545 | 102 |
| 0.02:4:true | 81 | 160 | 0 | - | 163 | 1.02 | 5.42 | 2 | 35 | 0.6 | 0/0 | 0 | 1/248 | 1386 | 93 |
| 0.02:5:false | 78 | 160 | 0 | - | 162 | 1.01 | 5.89 | 33 | 37 | 0.5 | 0/0 | 0 | 1/248 | 1491 | 102 |
| 0.02:5:true | 79 | 160 | 0 | - | 164 | 1.02 | 5.74 | 31 | 29 | 0.5 | 0/0 | 0 | 1/248 | 1391 | 97 |
| 0.02:6:false | 81 | 161 | 1 | newton 1 | 166 | 1.03 | 6.27 | 30 | 43 | 0.6 | 0/0 | 0 | 1/248 | 1508 | 106 |
| 0.02:6:true | 82 | 160 | 0 | - | 164 | 1.02 | 6.28 | 10 | 36 | 0.6 | 0/0 | 0 | 1/248 | 1384 | 100 |
| **transient total** | **1167** | **2014** | **94** | newton 4, be-state 90 | **2323** | **1.15** | 4.55 | 742 | 734 | 10.0 | 0/0 | 0 | 12/2976 | 18461 | 1278 |
| static total (32 cases) | 637 | 35 | 3 | newton 3 | 72 | 2.06 | 10.47 | 432 | 159 | 11.7 | 0/0 | 0 | 36/8928 | 9354 | 416 |

*Against run 84 on the twelve transients:* wall time is **7.6x** lower, Newton solves **9.4x**, flashes 8.0x, allocation **9.5x**, and rejected attempts 20x fewer.
- The minimum is one attempt per 0.1 s probe interval, 1920 over twelve cases. The 2014 accepted attempts are 1.05 per interval.
- 90 of the 94 rejections are `be-state` refusals of the first 0.1 s attempt of an interval during the 50 mm blowdown (2.4-2.9 % change). The probe calls `solve` without a starting step, so every interval starts from `initialStep`. That is a probe artefact; the runtime passes `nextStepEstimate`.

*The one-transition rule (runs 85e, 85t).*
- It accounts for 434 of the 532 rejections in 85e.
- Cause, from the 85t trace: the symmetric void pair e2/e3 (z = -1 and +1 m, the same elevation) opens and closes together, so every event is 2 transitions. No step size separates them. Each event costs a 17-halving chain to the 1e-6 s floor, where the step is accepted anyway.
- In the gates the same rule drove a resting line into the class-4 rounding floor (runs 88/88e, see (f)).
- With the rule off the state cap alone bounds every step, and no probe or gate outcome got worse.

#### (d) APPROXIMATE acceptance (runs 86, 86b, 86d)

| Run | Configuration | static nonconvergence | transient nonconvergence | transient ms | accepted / rejected | rejected by |
|---|---|---|---|---|---|---|
| 86 | 85e + `-Pacceptance=approximate` | 0 | **12** | 5407 | 3412 / 3511 | newton 3282, be-mode 106, approx 94, be-state 29 |
| 86b | 85f + `-Pacceptance=approximate` | 0 | **12** | 5395 | 5854 / 5614 | newton 4944, approx 629, be-state 41 |

What the rejections of 86b are:

| Count | Kind | Message |
|---|---|---|
| 4714 | newton | `Linearized error estimate does not contract inside the supported domain` (from `SparseNewton.estimateCorrection`, called by `checkApproximation`, thrown as `Nonconvergence`) |
| 629 | approx | `Coupled flow-error estimate exceeds the fallback trust profile` |
| 213 | newton | `Velocity constraint did not close` |
| 12 | newton | line-search stall |
| 3 | newton | iteration limit |
| 2 | newton | equation gate |

The cases fail on "Interval substep limit" (1024 attempts spent on a 0.1 s interval) or on "Substep refinement exhausted".

*Classification (run 86d).* JDUMP does not instrument `checkApproximation`, so a trace replaced the dump run: `-PapproxTrace=true` on 0.05:4:true and 0.02:4:true.
- 1031 of the 1040 refused contractions are on row 13, edge 1's hydraulic row (tank 2 to junction).
  - A typical one: residual 3.7e-10 before the linearised correction and 1.02e-10 after. That is a ratio of 0.28 against a required 0.1, and just above the 1e-10 absolute floor.
  - The reconstructed point already sits within a few times of that row's noise floor, so one Newton correction cannot contract it tenfold. This is class 5 of 8.3, inside the trust probe itself.
- `Velocity constraint did not close` is the 2e-8 relative cap check applied to a flow converged only to 1e-6.
- The refusals halve the step to about 1e-12 s, where the junction enthalpy row's 1/dt floor (class 4) stops the Newton. Last JDUMP of 86d: line search stalled at 1.77e-6, dt = 1.1e-12 s.

So the route the code allows for a gas island is not a zero-nonconvergence configuration. Its checks were made for a fallback that retries at FULL, not for a transient basis. **Run 86 does not qualify, and the gates use the FULL configuration of 85f.**

#### (e) State cap 0.05 and trajectory accuracy (runs 87, 87b)

| Run | Configuration | nonconvergence (static / transient) | transient ms | accepted / rejected | Newton solves (per accepted) |
|---|---|---|---|---|---|
| 85f | final, cap 0.02 | 0 / 0 | 1167 | 2014 / 94 | 2323 (1.15) |
| 87b | final, cap 0.05 | 0 / 0 | 1114 | 1922 / 2 | 2158 (1.12) |
| 87 | 85e (strict rule), cap 0.05 | 0 / 0 | 1733 | 2307 / 398 | 3315 (1.44) |

A looser cap buys almost nothing here. The 0.1 s probe interval bounds the step, and at 0.02 the cap binds only on the first attempt of each 50 mm blowdown interval.

Tank pressures against TR-BDF2 (run 84); all twelve cases are in `run87b-trajectory-vs-run84.txt`:

| case @ t | run 84 P1, P2 (Pa) | 85f (cap 0.02) - 84 (Pa) | 87b (cap 0.05) - 84 (Pa) |
|---|---|---|---|
| 0.05:4:false @ 1.0 s | 116575.09, 115212.80 | +147.3, +193.5 | +310.7, +413.7 |
| 0.05:4:false @ 1.5 s | 102679.02, 101753.30 | +213.2, +309.1 | +439.3, +605.6 |
| 0.05:4:false @ 10 s | 101325.00, 101325.00 | 0.00, 0.00 | 0.00, 0.00 |
| 0.02:4:false @ 1.0 s | 144084.85, 143969.29 | +2.0, +15.2 | +2.0, +15.2 |
| 0.02:4:false @ 1.5 s | 141208.25, 141014.60 | +7.7, +20.7 | +7.7, +20.7 |
| 0.02:4:false @ 10 s | 101873.30, 102057.99 | +99.7, +100.5 | +99.7, +100.5 |

- *Backward Euler lags the decay*, as a first-order method does.
- *50 mm.* At 1 s the lag is 147-193 Pa on a 14-15 kPa gauge: **1.0-1.3 %** at cap 0.02, 2.0-2.7 % at 0.05. At 1.5 s the gauge is only 0.4-1.3 kPa, so the same lag is 16-30 % of what is left. Both caps reach 101325 Pa exactly by 10 s.
- *20 mm.* The step is the 0.1 s interval at both caps, so the two runs are identical. At 10 s the lag is +100 Pa on 550-730 Pa of remaining gauge, 14-18 %. That is the expected `k^2 h t/2` relative error of backward Euler for a decay rate k ~ 0.5 1/s at h = 0.1 s.
- *Against 8.4.* Its "below 1 %" holds only early in a 50 mm blowdown. Late in a decay, the relative error of the remaining gauge grows linearly in time.

#### (f) Gates under the final configuration

*Runs 88g/88h, the 38 adjacent tests: **35/38**.*
- NetworkRegime x2: the run 81 values to the last digit. These tests call `TrBdf2StepSolver` directly and never reach `be`. **Intended change** (holdup), as in 7.11 (h).
- `FilterBlockLineIslandTest.aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn`, line 376: expected 601325.0 but was 601322.7594477679. The tolerance is 0.60 Pa. TR-BDF2 (run 81) gives 601325.418, and the passive reference 601324.99999994. See the pump item in the suite table below.
- The earlier gate runs with the brief's controller: run 88 34/38 and run 88b 34/38. Their extra failures (the pumped cold start, and the one-transition rule at rest) are what forced additions 2-4 of (b) and the rule `off`.

*Run 89c, all fluid suites: **385/401** in 93 classes*: the 399 suite tests plus the two probe wrappers. Every failure:

| Test | Assertion | Class |
|---|---|---|
| `NetworkRegimeTest.twoDifferentFeedsMixAtAZeroHoldupJunction` | line 34, expected 0.6326450275296205 but was 0.5722427451894476 | **intended change** (holdup mixing lag; TR-BDF2 direct) |
| `NetworkRegimeTest.reversingOneFeedSwitchesJunctionUpwinding...` | line 43, expected 0.0 but was 44.07302495656579 | **intended change** (holdup; TR-BDF2 direct) |
| `SolidChainTransportTest.clearChainSubstepCountsAreUnchanged` | expected 25 but was 3 | **intended change** (substep counts recorded for the TR-BDF2 controller) |
| `SolidChainTransportTest.filterIslandReusesItsSolverWorkspacesAcrossIntervals` | "Endpoint rates were rebuilt: 0 reused against 0 solved" | **intended change** (no endpoint-rate cache under `be`) |
| `PassiveTimeRefinementTest.adaptiveGasBlowdownMatchesIndependentlyRefinedFixedSteps` | expected true but was false (pressure, temperature and flow <= 0.5 % against 400 fixed 2.5 ms steps) | **intended change**: TR-BDF2-order trajectory accuracy |
| `TransientQualificationTest.diluteGasSmallSignalMatchesAnalyticAdiabaticPoiseuilleRelaxation` | expected true but was false (1 % against the analytic relaxation) | **intended change** (accuracy). The cap measures change relative to max(100 Pa, P), so a 1 Pa signal in 1000 Pa tanks never binds it: small-signal transients get about one step per interval |
| `TransientQualificationTest.freeWaterDisappearanceMatchesRefinedTrajectoriesAcrossThreeIntervals` | expected true but was false (0.5 % against a refined reference) | **intended change** (accuracy) |
| `TransientQualificationTest.liquidCompressionWetCrudeReversalAndPumpMatchRefinedTrajectories` | "wet crude equalization / reversed direction: TIME/refinement threshold exceeded" | **intended change** (accuracy thresholds 0.1-0.5 %) |
| `CadenceTrajectoryQualificationTest.fixedAndAdaptiveCadencesPreserveTheRefinedPhysicalTrajectory` | "nitrogen fixed-20 cumulative flow: 2.4842454217475704E-4" | **intended change** (cumulative-flow accuracy against the refined trajectory) |
| `ScheduledTransferEstimatorTest.changingGasCompositionAndWithdrawnEnergyMatchTighterStepDoubling` | Boundary -2 component 0, expected -0.11970231114961034 but was -0.1599817108564745 | **intended change** (accuracy). The cap does not see the boundary or composition integral; the fixture has a 100 Pa difference at 150 kPa |
| `SolidClosureFeasibilityTest.drainingCarrierStopsAtTheParticleThreshold...` | expected 0.005497312500000001 but was 0.007031201044266619 | **intended change** (event-location accuracy). The reference is backward Euler at <= 1e-4 s steps, and the cap does not see the flow's approach to the deposition threshold |
| `FilterBlockLineIslandTest.aFilterBlockCostsAPumpedLineNoIntervalOfItsOwn` | expected 601325.0 but was 601322.7594477679 | **not understood**: BE-specific pump shutoff landing, 2.24 Pa short (TR-BDF2 +0.42 Pa) |
| `ElevatedBlockLineIslandTest.elevatedLinesIntegrateFortyIntervalsLikeTheirFlatControl` | "A pumped tank must settle one water column below its own shutoff pressure", expected 860918.3068374132 but was 861061.9583598579 | **not understood**: BE-specific, 143.65 Pa above against a 90 Pa tolerance (TR-BDF2 gap 23.6 Pa per the test comment). Neither pump landing was dumped |
| `FullTankSolidsEventTest.theFilterCapturesOnceTheDrainedTankLetsTheLineFlow` | line 240, "and reach its capacity" | **defect of `be` as built, not understood**: the cake lands at 24.999999999975 kg (the saturated-inlet landing), yet no FILTER_CLOGGED transition is ever declared (run 89d trace: no `BETRANSITION` line), although the start-of-step guard now runs at every step start. The run 82 pin stall (HELD at interval 6) is gone |
| `PumpRiseScalingTest.aDomainRefusalIsItsOwnRejectionKeyAndTheReasonTheIntervalFails` | "the rejection map counts it under its key: Substep refinement exhausted: Newton iteration limit at residual 5.146138626733338E-4" | **not understood**: the interval still fails, but its last rejection is a Newton iteration limit, not the domain refusal, so `domainCause` is null |
| `CausalModuleCoordinatorTest.certifiedIslandsReproduceTheModuleOutcomeOfIslandsThatSolveEveryInterval` | "coupled reads=true": islands 3 and 4 differ in the last 2-5 ulps of their inventory digests (`...b01928:...122796/...253` vs `...b0192a:...12279b/...254`) | **not understood**: BE-specific bitwise path dependence between certified and every-interval runs; mechanism not traced |

*Against run 82 (TR-BDF2 chain, 397/401):*
- `SolidRuntimeTest.physicalPumpFilterAndNitrogenReceiverStartTogether` **passes** in 89c. It failed in 89/89b with a line-search stall at 6.19e-4, and passes since the port-graph rate solve for the guard (addition 5). Why that fixes it is not understood.
- `ElevatedBlockLineIslandTest` and `IslandCertificateTest` keep their `history2`/`B2` passes. Elevated now fails on its pumped line instead (see the table).

*Run 90, the same suites at defaults, no init script: **399/399** in 91 classes, 0 skipped.* The switched-off patch is pass/fail neutral. No bitwise comparison with the base was made.

#### (g) Verdict on 8.4, and open items

- **Cost: confirmed.**
  - Backward Euler with the state cap needs 1.15 Newton solves per accepted step, against 3.24.
  - It runs no rate solves outside the cold start and no companion, and it takes about one step per 0.1 s interval on these probes.
  - On the same probes it is 7.6x cheaper than the TR-BDF2 chain in wall time, 9.4x in Newton solves and 9.5x in allocation. Rejected attempts fall from 1927 to 94.
- **Zero nonconvergence: met on the probes with FULL acceptance**, 32/32 static and 12/12 transients. That is one better than TR-BDF2 on each: the static 3:150 case and the 0.05:6:true restart were both rate-solve failures.
  - It needs the cold-start handling of (b) 1-2. The balanced seed as it stood did not cover the only cold start left, and 8.4 had anticipated that this cold start stays.
  - It needs the one-transition rule dropped.
  - It is not met on the gates. Four failures there are not understood or are defects: the two pump shutoff landings, the filter capacity declaration and the domain-cause key. The last-ulp certificate difference comes on top.
- **APPROXIMATE acceptance: refuted as a route**, 0/12 transients.
  - The existing path's trust probe and its velocity closure sit at the rows' noise floor under a 1e-6 Newton. The halving they cause runs into the class-4 floor.
  - Classes 4 and 5 need the row-form fix (8.5 item 4), not the looser path.
- **Accuracy: first order, as expected, and visible.**
  - The lag is 1-1.3 % of the gauge 1 s into a 50 mm blowdown, and 14-18 % of the remaining gauge late in a 20 mm decay.
  - Ten suite tests fail on TR-BDF2-order accuracy or TR-BDF2 structure.
  - The state cap is blind to small signals (it is relative to absolute pressure), to boundary and composition integrals, and to a flow's approach to a threshold.

Open:
1. *Pump shutoff landings under `be`* (FilterBlock -2.24 Pa, Elevated +143.65 Pa). Dump the last steps before shutoff.
2. *`FullTankSolidsEvent`:* no FILTER_CLOGGED declaration under `be`, although the cake is at the landing value. Trace `checkRate` at the landing step.
3. *`PumpRiseScaling`:* the domain cause is lost when the last rejection of the chain is a Newton failure.
4. *`CausalModuleCoordinator` last-ulp path dependence.* Certificates promise bitwise replay, so this must be understood before `be` could back them.
5. *The accuracy decision is the owner's.* Either re-baseline the ten accuracy and structure tests to backward-Euler tolerances, or add an accuracy control the state cap lacks: a step-doubling check every N steps, as 8.4 offered, or a cap on edge pressure differences or flows.
6. *Class 4 remains.* The junction enthalpy row's `m_J/dt` rounding floor at 9.78 kg liquid junctions is reached whenever a step falls below about 1e-6 s (88e, 86d). Backward Euler only makes such steps rarer; the row-form fix of 8.5 item 4 is still needed.
7. *Event rule.* The strict rule as specified is unworkable (85t, 88e). If event location is wanted, count events, not edges: a symmetric pair is one event.
8. *The remaining rate solves.* The cold rate solve (one per cold start) and the guard's rate solve (one per solid segment start when nothing is carried) are the only rate solves left. Their cost on a solid island was not checked.
9. *Probe start step.* The probe starts every interval at `initialStep`. A runtime-like probe would pass `nextStepEstimate` and remove about 90 of the 94 remaining rejections.
10. *Prototype shortcuts*, in addition to item 14 of 7.11:
    - the cold state held per `PassiveStepSolver` (`beAcceptedPipes`);
    - the modes and flows carried per interval solver;
    - `ApproximationRejected` caught under `be`.
    A production version decides whether the carried accepted modes and flows belong in the checkpoint.
11. *Documentation index.* The main checkout's `documentation/INDEX.md` was not touched, because the brief allowed no main-checkout edits. The batch row still needs this update when the documents are copied.

### 8.7 Cost per solve (runs 91+)

Run 2026-09-26 in the same worktree at 9674bf1 by Claude (Opus 5.5), on the owner's statement that the cost measured in 8.6 is still too high, CPU and memory both.
- *Code.* `holdup-prototype-be.patch` plus the levers below and their counters; the full diff is `tools/junction-holdup-prototype/holdup-prototype-be-fast.patch`, thirteen tracked files (the nine of the be patch plus `thermo/FluidThermodynamics`, `HydrocarbonModel`, `TranslatedPengRobinson`, `GlobalLiquidResponse`). It contains every earlier patch; apply it alone. `--check --reverse` on the patched tree and `--check` on the restored base pass; `git status --short` shows nothing tracked.
- *Conditions.* Gradle calls one after another, no dev client, nothing committed, no tracked test modified, `checkConservation` and the reconstruction gate unchanged at defaults.
- *Configuration.* Every run starts from run 87b's set: `BASE FINAL '-PbeStateCap=0.05'` (8.6), with `-PsolverDiag=true` on probe runs. The probe runner `edit-scripts/run-probe.ps1` writes the exact command as the first line of each log.
- *Timing caveats.* Probe `ms` carries the diagnostics' own overhead and about 5 % run-to-run noise (run 96e repeats 91a at 1023 against 1082 ms). The counters are exact and are the evidence; `ms` only ranks.

#### (a) Cadence: what an island costs in the product's slices (runs 91a-e)

The probe bounded every step at its 0.1 s interval. `-PjunctionInterval` now cuts the same physical schedule (10 s rest, 3 s injection, 3 s settle) into 0.1, 1 or 5 s intervals (5 s: 5, 5, 3, 3). `-PstartStep=hint` starts each interval at the last interval's `nextStepEstimate()`, as `RetainedSolver` does; the default starts it at `Settings.initialStep` (1 s, capped by the interval).

Twelve transient cases, 16 s simulated each:

| interval | start | nonconvergence | ms | accepted / rejected | rejected by | Newton solves | iter/solve | Jacobians | alloc MB |
|---|---|---|---|---|---|---|---|---|---|
| 0.1 s (91a = 87b line for line) | initial | 0 | 1082 | 1922 / 2 | newton 2 | 2158 | 5.11 | 560 | 1213 |
| 1 s (91b) | initial | 0 | 431 | 262 / 40 | be-state 30, newton 10 | 355 | 8.19 | 442 | 365 |
| 5 s (91c) | initial | 0 | 363 | 227 / 19 | be-state 15, newton 4 | 281 | 8.50 | 381 | 317 |
| 1 s (91d) | hint | 0 | 437 | 318 / 10 | newton 5, be-state 5 | 379 | 6.93 | 361 | 371 |
| 5 s (91e) | hint | 0 | 351 | 232 / 8 | newton 3, be-state 5 | 272 | 8.43 | 307 | 309 |

- *Zero nonconvergence at every cadence*: 12/12 transients and 32/32 static in all five runs. The Newton rejections are recovered by halving.
- *The cold start is now half of a 5 s-cadence island.* The balanced seed (248 flashes) and the cold rate solve cost 157-165 ms of the twelve cases in every cadence (runs 92, 96, 97): about 13.6 ms per cold start, 45-50 % of the 5 s total.
- *Warm cost at 5 s:* (349 - 163)/12 = 15.5 ms per case for 16 s simulated, about 1 ms per simulated second of a flowing five-node island. At rest it is zero (certificate).

Accuracy against the 0.1 s run (`run91-trajectory-summary.txt`; largest tank-pressure difference over the cases of a bore, and the 0.1 s run's remaining gauge range at that time). No interval of the 1 s and 5 s runs ends at 1.5 s, so the 1.5 s comparison of the brief cannot be made there; 1.0 s is the nearest shared time.

| run | bore | @ 1 s | @ 5 s | @ 10 s | @ 13 s | @ 16 s |
|---|---|---|---|---|---|---|
| 1 s | 50 mm | 184 Pa [gauge 57-15561] | 0.6 | 0.6 | 0.2 | 0.1 |
| 1 s | 20 mm | 121 Pa | 509 Pa [196-21297] | **858 Pa [19-834]** | 87 Pa [33-557] | 0.1 |
| 5 s | 50 mm | - | 0.2 | 0.2 | 3.8 | 0.1 |
| 5 s | 20 mm | - | 484 Pa | **845 Pa** | 131 Pa | 0.9 |
| 5 s hint | 50 mm | - | 0.7 | 0.7 | **2800 Pa [-1-6]** | 3.2 |
| 5 s hint | 20 mm | - | 358 Pa | 799 Pa | 117 Pa | 12.7 |

- *Slow decays lag by about their whole remaining gauge.* The 20 mm blowdown at 10 s is 816-858 Pa behind the 0.1 s run in both slice lengths, on 650-830 Pa of remaining gauge; the 0.1 s run itself is +100 Pa behind TR-BDF2 (8.6 (e)). The 0.05 state cap is relative to absolute pressure, so a decay of a few kPa takes 1-5 s steps.
- *A new defect of long slices: injection bottling (91e).* In 0.05:4:true at 5 s with the hint, the first injection interval (10-13 s) is one 3 s step from rest. `closeIllegalStarts` (`voidStart=history2`) closes the void edges at the start point, whose driving pressure is zero, and the 2.8 % tank change it produces is inside the cap. Both tanks end 2.8 kPa high and vent in the next interval. The initial-start run (91c) takes a 1 s first step and is 3.7 Pa off. This is a step-size question for the start-point closure, not a solver failure.

#### (b) Where the cost is (runs 91a/91k, 92)

New counters: flashes by call site, cold-seed and cold-rate timers, and ThreadMXBean allocation around the Newton, the Jacobian build, the reconstruction, `checkConservation` and the phase checks. Allocation sites come from JFR `jdk.ObjectAllocationSample` (`-Pjfr`, `jfr-sites.js`). JFR execution sampling did not take effect (66-77 CPU samples per run), so the CPU split comes from the timers.

| run 91a, 0.1 s | ms | share | MB | share |
|---|---|---|---|---|
| phase-check and trace-seed flashes (12222 + 1676) | 466 | 43 % | 532 (phase checks) + 77 (trace seeds, JFR) | 50 % |
| cold start (2976 seed flashes) | about 160 (runs 92/96e) | 15 % | 128 (JFR) | 11 % |
| Newton iterations (13712 residual evaluations; LU 8 ms) | rest | | 213 | 18 % |
| Jacobian builds (560) | 35 | 3 % | 22 | 2 % |
| reconstruction (2158) | 39 | 4 % | 49 | 4 % |
| `PipeTransfer.sample`/accumulate | - | | 67 (JFR) | 6 % |

The phase check is `phaseCorrection`'s TP flash of every single-phase node, run twice per accepted pass. `PassiveStepSolver.java:455` (patched) checks the converged Newton point and `:530` the reconstructed one. It costs 33-35 us and 44.6 KB per flash. Before the change, 204 + 180 MB of its bytes were the two array copies in `TranslatedPengRobinson$Values.phase:161-162`, 78 MB `GlobalLiquidResponse.logFugacity:49`, and 66 MB the `HydrocarbonModel$Phase` records.

#### (c) The levers, one at a time (5 s probe, run 92 = 91c repeated, and the static matrix)

| lever (run) | nonconv. | ms | accepted / rejected (newton) | solves | iter/solve | Jacobians | opened preconditioned | residual evals | alloc MB | static: solves, iter/solve, ms |
|---|---|---|---|---|---|---|---|---|---|---|
| none (92) | 0 | 349 | 227 / 19 (4) | 281 | 8.50 | 381 | 194 | 3112 | 317 | 72, 10.47, 654 |
| A `predictor=linear` (92c) | 0 | 446 | 243 / 38 (23) | 317 | 9.71 | 741 | 201 | 4662 | 366 | 72, 10.57, 639 |
| B `jacobianReuse=on` (92d) | 0 | 360 | 227 / 19 (4) | 281 | 8.59 | 374 | 207 | 3156 | 317 | 72, 10.47, 635 |
| C `rowForm=amount`, 1e-9 (92e) | 0 | 366 | 234 / 28 (14) | 290 | 8.61 | 414 | 198 | 3617 | 329 | 68, 10.54, 642 |
| C, 1e-8 (92f) | 0 | 384 | 230 / 24 (10) | 283 | 8.18 | 389 | 193 | 3435 | 324 | 68, 10.18, 610 |
| C, 1e-7, gate 1e-7 (92g) | 0 | 410 | 238 / 31 (17) | 299 | 7.69 | 366 | 205 | 3440 | 374 | 84, 10.21, 749 |
| rate rows, 1e-8 (92h, control) | 0 | 535 | 424 / 217 (201) | 680 | 5.46 | 356 | 585 | 5023 | 494 | 72, 10.14, 702 |
| D `phaseCheck=once` (92a) | 0 | 333 | as 92 | 281 | 8.50 | 381 | 194 | 3112 | 283 | as 92, 633 |
| E `lowAlloc=on` (96a, final form) | 0 | 352 | as 92 | 281 | 8.50 | 381 | 194 | 3112 | **140** | as 92, 624; 139 MB |

- **A, predictor: worse, rejected.**
  - Linear extrapolation over steps that grow by up to 2x on exponential decays overshoots. Iterations +14 %, Jacobians x1.9, Newton rejections 4 -> 23, ms +28 %. 251 of 281 solves were predicted.
  - At rest it also moves the start point off the previous state. The converged points then wobble within the Newton tolerance, and `IslandCertificateTest`'s closed ladder stops certifying (stationarity 1.59e-9 against eps_s 1e-9, run 99e).
- **B, Jacobian reuse: already taken; the switch is neutral.**
  - The dt-keyed workspace misses on almost every 5 s-cadence step (259 builds for 281 solves in 91c). The new workspace is then `forkPreconditioner()` of the structure's latest one (`PassiveStepSolver.java:397`), so 194 of 281 solves already open on the previous step's factorization.
  - The other 87 are the cold start, the solves after a Newton failure (`invalidate`) and 13 factorizations a sibling superseded.
  - The cost is that the chord built at another dt contracts poorly. 191 of the 381 Jacobian builds are stall refreshes (`reduction > 0.8`, `SparseNewton.java:188`) and 147 are age refreshes. Opening on the structure's latest workspace raises the preconditioned opens to 207 and changes nothing else measurable.
  - At 0.1 s, where dt is constant, the chord is reused 1842/2158 and still takes 5.11 iterations per solve.
- **C, amount-form junction rows.**
  - The rows divided by dt are the owned junction's mixing and enthalpy rows. Their flux-form weight is `m_J/dt + Q` (`junctionInflow`, patched `:2513`). Every vessel row is already in amount form: `(n - n_old - dt sum q x)/componentScale` and `(U - U_old - ...)/energyScale`. The net-mass row is a rate in kg/s, but its terms are flows, so its floor (eps q) does not grow as dt shrinks, and it is left alone. The junction's solid rows are ratios.
  - `rowForm=amount` multiplies the two rows by `dt/m_J`. The weight becomes `1 + dt Q/m_J`, and the rounding floor goes from `eps (m_J/dt + Q)` to `eps (1 + dt Q/m_J)`.
  - *Tolerance in amount units.* A junction row at tolerance tol now bounds the step's species mass error to `tol m_J` kg, and the enthalpy error to `tol m_J max(1, E_scale/m)` J. In rate form the bound was `tol dt` kg.
    - With m_J = 8.1e-3 kg (50 mm) and 1.3e-3 kg (20 mm), the amount form is tighter by m_J/dt: 12x and 77x at dt = 0.1 s, 620x and 3800x at 5 s. That is why it costs slightly more at 1e-9 (8.61 against 8.50 iterations; 14 Newton rejections, iteration limits and line-search stalls at the largest steps).
  - *Ledger:* closes to 3.8e-16-7.5e-16 in every run, and the probes pass.
  - *1e-8:* 8.18 iterations at 5 s (-4 %) and 4.34 at 0.1 s (-15 %, run 93g). The gate stays at 1e-8, equal to the tolerance and not moved.
    - The amount form removes the gate failures the rate rows have at 1e-8 (92h: 199 "Conservative reconstruction fails equation gate").
    - At 0.1 s, 48 steps still fail the gate with no margin left. Dump 93k shows the junction enthalpy row at 9.72e-9 after the Newton and 1.14e-8 after the pinned reconstruction.
  - *1e-7:* the gate moves to 1e-7 under the switch only. 7.69 iterations, but 11 "Velocity constraint did not close" (the 2e-8 relative cap check of 8.6 (d)) and 2 gate failures. Static solves go 72 -> 84.
  - *The rest certificate:* at 1e-8, with either row form, `IslandCertificateTest` fails both certificates. The closed pair ends REST instead of STEADY, and the closed ladder's stationarity is 1.66e-9 against eps_s 1e-9 (runs 99f, 99i).
  - **Result: the tolerance lever buys 4-15 % of the iterations and no wall time here.** It is not usable without a gate margin above the tolerance and a certificate threshold above the Newton noise, and both are owner decisions. The amount form itself is safe at 1e-9, and it removes the class-4 floor analytically. That floor was not re-measured at micro-steps.
- **D, flash accounting.**
  - A residual evaluation and a Jacobian build flash nothing. They decode through state calls: 11.8 per block Jacobian build (6635/560, one decode per node column; `differentiateEntries` decodes only the perturbed node), and the residual's decode cache skips unchanged nodes.
  - The flashes are all outside the Newton, at 0.1 s cadence:
    - the Newton-point phase check, 3.0 per solve;
    - the reconstructed-point check, 2.7 per solve;
    - the trace seed of `initialPhaseSeeds`, 0.87 per implicit solve, for a tank that lacks a reachable component (`:964`);
    - 248 per cold seed.
  - *The reconstruction re-flashes states the Newton already has.* The check at `:455` asks the same regime question of the Newton point that `:530` asks of the reconstructed point, which agrees with it to the Newton tolerance. `phaseCheck=once` drops the first check.
    - Every probe line is unchanged (92a, 96d, 97a/b/g/h), and the fluid suites are unchanged message for message (99c/99j against 99a).
    - Flashes 17012 -> 10568 and flash time 444 -> 231 ms at 0.1 s; 5023 -> 4216 at 5 s.
- **E1, allocation (`lowAlloc=on`, arithmetic unchanged, every probe line and every suite message equal).** Per unit of work, from `run97-alloc-units.txt`:

  | | alloc MB (12 cases) | per accepted step KB | per Newton solve KB | per Newton iteration KB | per Jacobian build KB | per reconstruction KB | per phase-check flash KB | MB per simulated s |
  |---|---|---|---|---|---|---|---|---|
  | 0.1 s base (96e) | 1213 | 646 | 576 | 19.7 | 39.7 | 26.1 | 44.6 | 6.32 |
  | 0.1 s D+E (97a) | 530 | 282 | 251 | 18.7 | 36.4 | 25.3 | 7.1 | 2.76 |
  | 5 s base (92) | 317 | 1430 | 1155 | 20.6 | 39.9 | 26.3 | 44.6 | 1.65 |
  | 5 s D+E (97b) | 133 | 598 | 483 | 19.8 | 38.8 | 25.5 | 7.2 | 0.69 |
  | static base / D+E | 416 / 138 | | 5916 / 1967 | 13.4 / 13.2 | 18.8 / 18.3 | 20.1 / 20.0 | 40.7 / 7.9 | |

  JFR, solver phases only (the JVM start and the material catalog, 142-161 MB per recording, are left out):

  | phase | 0.1 s before (91k) | after (96j) | 5 s before (91j) | after (96k) |
  |---|---|---|---|---|
  | phase-check flashes | 461.6 MB | 45.7 | 59.3 | 6.4 |
  | Newton (residuals, line search, LU) | 208.5 | 186.2 | 48.2 | 41.2 |
  | cold seed | 128.2 | 40.6 | 125.0 | 38.2 |
  | trace-seed flashes | 76.6 | 26.0 | 14.6 | 5.5 |
  | PipeTransfer sample/accumulate | 66.9 | 69.5 (before the shared empty stream) | 6.0 | 6.5 |
  | reconstruction | 44.4 | 45.5 | 4.5 | 7.0 |
  | equations/layout build | 30.5 | 24.3 | 4.0 | 3.5 |
  | Jacobian build | 18.5 | 13.5 | 11.0 | 12.5 |

  - *What was fixed.*
    - The flash's equilibrium iteration built two phase records per iteration. It now writes ln phi into two buffers (`HydrocarbonModel.logFugacityInto`, `TranslatedPengRobinson.evaluateValues`, `GlobalLiquidResponse.logFugacityInto`).
    - A phase-check flash reuses the node's prepared Peng-Robinson workspace.
    - `HydrocarbonModel.phase` copies only the coefficients its record keeps.
    - `PipeTransfer.sample` shares one empty stream.
  - *Suspects cleared by the numbers.*
    - The `differentiateEntries` clones: the whole Jacobian build is 1.5-2 % of the bytes, 36-40 KB per build.
    - `x.clone()`/`candidate` in the line search: `SparseNewton.solve:157/160`, 3.5-4.5 MB.
    - `checkConservation`: 3.4-4.4 KB per step.
    - `WorkspaceKey`/`supports`/`startPoint` churn: inside "equations/layout build" and "step solve other", 2-4 %.
  - *What is left.* 19-21 KB per Newton iteration is the state evaluation of each changed node, about 2.9 KB per state call. Its sources:
    - a new `Prepared` per temperature change, with its Peng-Robinson workspace (`TranslatedPengRobinson$Workspace.<init>:122-123`, `PengRobinsonKernel$Workspace.<init>`) and viscosity terms (`MixtureViscosity$Prepared.<init>:63-64`);
    - the `State` and `Phase` records;
    - the `GlobalLiquidResponse.evaluate:24` input array;
    - `PengRobinsonKernel.selectRoot:536`.

    Removing it needs mutable per-node decode buffers, which is not a contained change. The instrumentation's own ThreadMXBean reads are about 1 % (10-12 MB).
- **E2, retained memory per island** (`JunctionHoldupMemoryProbe`: N islands solved through two 1 s intervals and held, live heap after repeated GC, `jcmd GC.class_histogram` diff):

  | configuration (run) | 5-node island | of which graph / solver | 50-node gas chain | of which graph / solver |
  |---|---|---|---|---|
  | 87b set (95a, repeat 95e) | 80.8 KB (81.3) | 14.9 / 65.8 | 856 KB (851) | 103 / 753 |
  | `jacobianReuse=on` (95c) | 75.4 | 14.9 / 60.5 | 861 | 103 / 758 |
  | `lowAlloc=on`, decode cache and cold graphs released (95b) | 64.0 | 14.9 / 49.0 | 569 | 103 / 466 |
  | `lowAlloc=on`, final: + the last solve released (95f) | **50.4** | 14.9 / 35.4 | **432** | 103 / 329 |
  | final `lowAlloc` + `jacobianReuse` (95g) | 44.6 | 14.9 / 29.7 | 436 | 103 / 333 |

  - *Retention the probe found* (`run95-retained-classes.txt`):
    - The step solver's last solve (an `Equations` whose decode cache holds a `State`, `Prepared`, Peng-Robinson workspace and transport row per node) is read only by TR-BDF2's companion.
    - The interval solver kept the cold-start graph and its seeded copy for the island's life. The chain held 150 `Reservoir`s per island for 50 nodes; 50 after.
  - Both are released under `lowAlloc` after each accepted backward-Euler step (`PassiveIntervalSolver.java:357`). `BlockJacobianEquivalenceTest`, which reads `acceptedEquations()` from a direct `PassiveStepSolver`, still passes.
  - *The largest retained structure now* is the Newton workspace's sparse pattern and LU: `[D` + `[I` = 359 KB of the chain's 432 KB (about 7 KB per node), then the accepted graph (103 KB, 2 KB per node).
  - Of the brief's candidates:
    - "one workspace per structure key" is lever B, which changes the Newton path, so it is not under `lowAlloc`; it saves another 5.8 KB on the 5-node island and nothing on the chain.
    - Releasing the LU of non-current workspaces was not done: it would give up the preconditioner the reuse measurement in (c) shows is taken.

#### (d) Combined configurations

| configuration | cadence | nonconvergence | ms | accepted / rejected (newton) | solves | iter/solve | Jacobians | alloc MB | against run 87b (0.1 s: 1114 ms, 2158 solves, 1197 MB) |
|---|---|---|---|---|---|---|---|---|---|
| A+B+C(1e-8)+D+E (97e) | 0.1 s | 0 (static 32/32) | 900 | 1970 / 55 (55: 32 line-search stalls, 14 iteration limits, 10 gate, run 93b) | 2370 | 4.57 | 1030 | 598 | -19 % ms, +10 % solves, -50 % alloc |
| A+B+C(1e-8)+D+E (97d) | 5 s | 0 (32/32) | 381 | 247 / 41 (27) | 329 | 9.57 | 743 | 176 | |
| **D+E (97a/97g)** | 0.1 s | 0 (32/32) | 832-837 | 1922 / 2 (2) | 2158 | 5.11 | 560 | 530 | **-25 % ms, same solves, -56 % alloc; every probe line equal** |
| **D+E (97b/97h)** | 5 s | 0 (32/32) | 330 | 227 / 19 (4) | 281 | 8.50 | 381 | 133 | against 5 s base (92): -5 % ms, -58 % alloc |
| **D+E (97c)** | 5 s, hint | 0 (32/32) | 342 | 232 / 8 (3) | 272 | 8.43 | 307 | 126 | against 91e: -3 % ms, -59 % alloc |
| **D+E (97f)** | 1 s | 0 (32/32) | 383 | 262 / 40 (10) | 355 | 8.19 | 442 | 161 | against 91b: -11 % ms, -56 % alloc |

- The brief's combination A+B+C(1e-8) passes every probe, but it is dominated by D+E: A adds Jacobians and Newton rejections, and C at 1e-8 adds gate rejections at 0.1 s.
- **Recommended: D+E**, zero nonconvergence at every cadence, with the numerics of the 87b set.
  - Wall time -23 to -25 % at the 0.1 s probe cadence (against 91a and 87b) and -3 to -11 % at 1-5 s slices, where the cold seed (13.6 ms per cold start) now dominates.
  - Allocation -56 to -59 % at every cadence: 0.69 MB per simulated second of a flowing five-node island at 5 s slices, cold start included.
  - Retained memory -38 % (5-node) and -50 % (50-node chain).

#### (e) Gates

| run | set | 7 gate classes | fluid suites (399 + 3 probe wrappers) | against the no-lever reference |
|---|---|---|---|---|
| 98a / 99a | 87b set, no lever (reference) | 36/38 | 386/402 | - |
| 98c-d / 99c, 99j | + D+E | 36/38 | 386/402 | **equal, message for message** |
| 98b / 99b | + A+B+C(1e-8)+D+E | 36/38 | 384/402 | +2 failures |
| 99d | defaults, no init script | - | **399/399** (91 classes, 0 skipped) | - |

- *The two NetworkRegime gate failures* are the intended holdup change of 7.11 (h): run 81 values; under A-E the expected value moves in its 11th digit.
- *The reference against run 89c's list (385/401, cap 0.02).* The cap 0.05 of the 87b set, not a lever, moves three tests:
  - `FilterBlockLineIslandTest.aFilterBlockCostsAPumpedLine...` now **passes**.
  - `ElevatedBlockLineIslandTest` still fails, now at 848734.80 against 860918.31. It was 861061.96 at cap 0.02, so the pumped landing is 12.2 kPa low at cap 0.05.
  - `McpGameplayRegressionTest.belowSeaLevelNitrogenTankAcceptsWaterAfterAnIdlePeriodAndPressureEdit` **fails new**: tank mass 203.164 against a refined 203.419 kg at 0.1 % tolerance. **Accuracy class** (cap 0.05's longer steps against a refined trajectory).
  - The other fourteen failures (NetworkRegime x2 included) are those of 8.6 (f), with the same classes; the 0.02 -> 0.05 cap moves only their numbers.
- *The two new A-E failures, attributed by single-lever runs 99e-i:*
  - `IslandCertificateTest.aSettledBenchmarkClosedLadderCertifiesOnceItsQuietPipesAreExempt`: "the relative flow test alone would have refused", stationarity 1.47e-9 against eps_s 1e-9. Caused by the predictor alone (99e: 1.59e-9) and by tolerance 1e-8 alone, with either row form (99f, 99i: 1.66e-9).
  - `IslandCertificateTest.aSettledClosedPairCertifiesWithALongHorizon`: "horizon of 606 intervals" under A-E, "expected STEADY but was REST" under 1e-8 alone. Caused by tolerance 1e-8.
  - **Class: defects of levers A and C(1e-8)**. Both leave the rest state moving at the Newton noise, above the certificate's stationarity threshold. `rowForm=amount` at 1e-9 and `jacobianReuse` (99g, 99h) do not fail them.

#### (f) Open

1. *Cold start.* 248 flashes and about 13.6 ms per balanced seed. It is the largest CPU item left at the product's 5 s cadence (47 % of the transient probe) and 76-77 % of the static matrix. Its allocation fell to a third under `lowAlloc`; its flash count is untouched.
2. *Per-iteration allocation* of the state evaluation: 19-21 KB, from a `Prepared`, Peng-Robinson and viscosity workspace per temperature change plus the State/Phase records. It needs mutable per-node decode buffers.
3. *Chord contraction across dt changes.* 8.5 iterations per solve at 5 s against 5.1 at 0.1 s; half the Jacobian builds are stall refreshes of a chord built at another dt. Refreshing when the dt ratio exceeds a bound, or rescaling the chord's dt-dependent entries, is untested.
4. *Accuracy of long slices*, now measured. It remains the owner's decision of 8.6 (g) item 5.
   - The 20 mm decay at 10 s is 816-858 Pa behind at 1-5 s slices, on 650-830 Pa of gauge.
   - The injection-start bottling of (a) (2.8 kPa at 13 s) needs either a first-step bound after a boundary change or the start-point closure re-evaluated after a long step.
5. *The tolerance lever* needs a gate margin above the Newton tolerance and a certificate threshold above the Newton noise before 1e-8 can be used. Both are owner decisions; the amount-form rows are a prerequisite.
6. *Class-4 floor under the amount form:* removed analytically, not re-measured at micro-steps (the 88e line).
7. 8.6 (g) items 1-4 and 8-11 stand. With cap 0.05, item 1's FilterBlock landing passes and Elevated's landing is 12.2 kPa low.
8. *Prototype shortcuts added here:*
   - the predictor's retained start/end states per step solver;
   - `releaseLastSolve` called by the interval solver;
   - the flash's use of a node's prepared workspace, which relies on single-threaded use of a `Prepared` (already the documented contract);
   - the static `ZERO` stream map.
9. *Documentation index.* The main checkout's `documentation/INDEX.md` and this batch's copy there are not updated (no main-checkout edits in this brief).

### 8.8 Events under long BE slices (runs 100+)

Run 2026-09-26 in the same worktree at 9674bf1 by Claude (Opus 5.5), on the owner's acceptance of backward Euler at the product's 5 s cycle (the D+E configuration of 8.7) and his question: does a long backward-Euler slice make an event mechanism *break* (missed event, wrong active set held through a step, a state past a physical bound, a domain violation, nonconvergence) rather than merely *delay*? The planned pressure safety valves (PSV) and tank bursting, and the existing line and filter blockage, may all react one slice late.
- *Code.* `holdup-prototype-be-fast.patch` plus the fixes and switches below; the full diff is `tools/junction-holdup-prototype/holdup-prototype-events.patch`, fourteen tracked files (the thirteen of be-fast plus `network/SolidEventIntegrator`). It contains every earlier patch; apply it alone. `--check --reverse` on the patched tree and `--check` on the restored base pass; `git status --short` shows nothing tracked.
- *Conditions.* Gradle calls one after another, no dev client, nothing committed, no tracked test modified, `checkConservation` and the reconstruction gate unchanged.
- *Configuration.* D+E = `-PcapForm=B2 -PrateForm=frozen -PrateWarmStart=fresh -PvoidStart=history2 -PmintHoldup=on -PstageForm=implicit '-PholdupTau=0.05' -PpinMass=on -PstageClip=on -Pintegrator=be -PbeModeRule=off -PbeColdStart=rate '-PbeStateCap=0.05' -PphaseCheck=once -PlowAlloc=on`. Every log's first line is its command (`edit-scripts/run-probe.ps1`). Reference = the same code at 0.1 s slices.
- *Probe.* `JunctionEventProbe` (README): the smallest island per mechanism, integrated in consecutive 0.1 s and 5 s slices, each slice started at the previous slice's `nextStepEstimate()` as `RetainedSolver` does, with each event switch off and on. One line per slice end with modes, average and endpoint flows, endpoint `|q|/cap`, component ledger and node states. Timing `ms` is order-dependent (JIT) and only ranks; counters are exact.

#### (a) The event hooks under backward Euler (code reading, runs 100a, 100)

- *The hooks.* The TR-BDF2 path calls the stage guard's `checkRate` on its t0 endpoint rate, `checkFilters` on both stages and `check` on the corrected stage. The backward-Euler path (`runBackwardEuler`) has no stages; it calls `checkRate` at every step start (on the previous accepted endpoint, else on the endpoint carried across the call while no connection's identity or closure changed, else, for the solid event integrator's guard, on one port-graph rate solve) and `checkFilters` on every step end. The transition search (`transitionFloor = max(1e-6 s, 1e-9 duration)`, 40 transition rejections, `atStart` declared at once) is the TR-BDF2 path's, line for line. **No hook the TR-BDF2 path calls is skipped.**
- *Why the clog was never declared (run 100a, `-PbeTrace=true`).* The saturated inlet law lands the cake a hair below capacity (24.999999999975 kg, loading 1 - 1e-12) on the step that would have overfilled it. A t0 guard of the *next step of the same interval* declares it (`atCapacity`, relative 1e-9). Under backward Euler a 5 s interval is often a single step (the clog probe: one accepted step per slice from slice 2 on), so the landing is the interval's last step and the declaration comes from the *next interval's* start-of-interval closure pass in `SolidEventIntegrator.solve`. That pass blocks the filter both ways but, unlike a declared transition, never calls `stopFilter`: 34 `BEPRECHECK ... FILTER_CLOGGED filterStopped=false` lines at t = 0, no `BETRANSITION`. The filter carried nothing after the landing (blocked every interval), but `stoppedAtCapacity` stayed false: no "filter clogged" status, no stopped flag in the checkpoint, and the test's `clogged()` false. The same path exists under TR-BDF2 whenever a landing falls on an interval's last step.
- *Fix (part of `be`, no switch):* under `be` that pass stops the filter on `FILTER_CLOGGED`. Run 100: `FullTankSolidsEventTest` 4/4, `FilterBlockLineIslandTest` 11/11.
- *PumpRiseScaling (run 100a).* The typed cause was not lost at the 8.7 configuration: all 70 rejections of the chain are domain refusals (31 line-search stalls, then iteration limits, each with the violation attached), and `failure.domainViolation()` is Nitrogen / TEMPERATURE_BELOW / node 1 (the test's `assertNotNull` passed in 99j). The chain approaches the boundary Zeno-like (t = 0.0338 of 0.05 s) and ends on twenty consecutive rejections, whose message ("Substep refinement exhausted: ...") did not carry the rejection map; TR-BDF2 ended on its 1024-attempt cap, whose message does. *Fix (part of `be`):* the backward-Euler exhaustion message appends `; reasons=<map>`. Run 100: 5/5.

#### (b) Mechanisms at the 5 s cadence (runs 101-108)

| mechanism (case) | declared: 5 s slices vs 0.1 s reference | accepted state at the end of the event slice | class | evidence |
|---|---|---|---|---|
| 1. Filter clog (`clog`: 300 kPa, 5 % 100 um, filter to void) | lands on the last step of slice 8 (40 s), declared at slice 9's t0; reference lands in 31.3-31.4 s, declared at 31.4 s (the cake grows more slowly on 5 s steps: 23.39 against 24.59 kg at 30 s) | cake 24.999999999975 kg (capacity 25 kg less 1e-12); every flow 0 from slice 9 on, blocked both ways; `stoppedAtCapacity` true only with the fix | **DELAYED** (one slice, plus 8.6 s of backward-Euler lag in the cake's growth); stopped flag **FIXED** | 100a, 103 |
| 2a. Passive line whose downstream closes (`passiveFill`: 400 kPa into a tank) | line CLOSED from slice 9 (45 s); reference 28.9 s. The tank is within 0.1 Pa of the generator at 35 s | tank at most 400000.000 Pa (the generator), liquid 0.745 of the volume | **DELAYED** (the asymptotic approach closes later, no overshoot) | 103 |
| 2b. Pump into a dead-headed tank (`pumpFill`, flat) | head limit in slice 17, CLOSED in slice 18 (85-90 s); reference 82.5 s and 84.2 s | tank 601324.947 Pa against the shutoff 101325 + 500000 = 601325 Pa | **DELAYED** (one slice) | 103 |
| 2c. Gas pump between closed tanks (`gasPump`) | CLOSED in slice 1; reference 0.4 s | dP 572.99 Pa against the limit 573.81 Pa | **DELAYED** | 103 |
| 2d. Elevated pumped line (`risingPump`: ElevatedBlockLineIsland's vertical pump, tank 4 m up) | CLOSED in slice 18 at 848734.80 Pa and **held there for the remaining 110 s**; reference CLOSED at 88.9 s at 861063.055 Pa | 12.33 kPa below the pump's own shutoff, for good | **BROKEN** (a wrong active set held forever); **FIXED** by `pumpReopenStart=limit`: head limit at 90 s (861054.29 Pa), CLOSED in slice 20 at 861063.075 Pa (0.02 Pa from the reference) | 106, 107, 108 |
| 3. One-way boundary shut and reopened (transient 0.05:4:true, 5 s, hint: injection starting at rest) | void edges closed at t0 by `closeIllegalStarts` for the whole 3 s injection step | tanks 104128.43 / 104125.80 Pa at 13 s against 101330.94 / 101326.34 | **BROKEN** (bottling: wrong active set held through the step, 2797 Pa); **FIXED** by `boundaryReopen=on`: 101330.93 / 101326.34 (-0.013 / -0.004 Pa) | 97c, 101 |
| 3'. Dead-head closure spanning a step (`withdrawReopen`: dead-headed line at rest 10 s, then 0.3 kg/s withdrawn from the tank) | `closeDeadHeads` holds the generator line shut through each step that starts above the generator | tank 132980 Pa at 15 s against 135581 Pa; 146.12 kg at 25 s against 135.39 kg (+7.9 %) | **BROKEN** (the same class; bounded, it re-decides every step); **FIXED** by `boundaryReopen=on`: 135632 Pa (+51 Pa), 134.35 kg (-0.8 %) | 103 |
| 4. Velocity cap engaging and releasing (`blowdown`: 300 kPa 400 K tank to a void, 50 mm; transient 50 mm cases) | reference on the cap until 3-4 s, CLOSED at 4.7 s; 5 s: slice 1 (31 steps) ends at rest | largest endpoint flow over its cap, over every run, 1.000000001 (0.1 s, inside the 2e-8 closure), 0.72 at 5 s slice ends; **no** "Velocity constraint did not close" in any probe of runs 101-110 | **DELAYED** (nothing to see) | 101-103 |
| 5. Tank filling to full (`pumpFill`, `passiveFill`, `flush`: 200 kPa water through a tank to a void) | `flush` liquid fraction 0.9 at 62.8 s against 60.2 s; 0.999 at 220.1 s against 206.6 s | liquid at most 0.999945 of the volume; no nonconvergence, no held slice; ledger at most 4.9e-10 relative | **DELAYED** | 103 |
| 6. Pressure threshold (PSV / burst proxy; `ramp`: 5 mol/s nitrogen into two 1 m3 tanks, 8.68 kPa/s) | 150 kPa crossed at 5.597 s in both runs, first seen at the slice end t = 10 s; 300 kPa crossed at 22.859 s, seen at 25 s | 188227.6 Pa (+38.2 kPa, 4.40 s late); 318620.8 Pa (+18.6 kPa, 2.14 s late). 0.1 s: +24.6 Pa and +352.6 Pa | **DELAYED** (by construction at most one slice; see (e)) | 103 |
| 6'. The same on the pumped fill | 150 kPa at 30.94 s seen at 35 s; 300 kPa at 65.78 s seen at 70 s; 600 kPa at 84.93 s seen at 85 s | +9.0 kPa, +40.6 kPa, +1.29 kPa (the last capped by the pump's shutoff) | **DELAYED** | 103 |
| 7a. Domain floor (`domainFloor`: PumpRiseScaling's 298.05 K nitrogen floor) | slice 1 held at both cadences | typed hold, Nitrogen TEMPERATURE_BELOW node 1, 71 / 73 domain rejections, message with the map | **DELAYED** (clean) | 103 |
| 7b. Domain ceiling (`domainHot`: 1 mol/s nitrogen plus 20 kW, towards 900 K) | slice 12 held (t0 55 s); reference slice 540 (t0 53.9 s). 899 K crossed at 54.85 s against 53.72 s | last accepted T 899.68 K; typed hold Nitrogen TEMPERATURE_ABOVE node 1; the refused slice spends the 1024-attempt cap (487 accepted and 537 refused micro-steps; the whole 12-slice run took 346 ms) | **DELAYED** (one slice later, clean) | 103 |
| 7c. Water's triple-point floor met by venting (run 101's first `drainReopen`: a 298 K nitrogen tank at 300 kPa vented next to a water line) | held at t = 1.5 s at 0.1 s and inside slice 1 at 5 s | typed hold Water TEMPERATURE_BELOW 273.16 K, node 4, twenty domain rejections | **DELAYED** (clean; the fixture was then moved to 400 K) | 101 |

Every held slice of these runs was a typed domain hold; no case met a Newton nonconvergence. Ledger at most 4.9e-10 relative over every run, 4.4e-11 at 5 s.

#### (c) The two BROKEN classes and their fixes

- **One-way closure at t0 held through a step (`junction.boundaryReopen`).** `closeDeadHeads` and `closeIllegalStarts` decide a closure on the solve's *start* point, per solve. The pass loop never reopens a boundary closure within a solve, and backward Euler solves each step once. So a step that starts closed and should open part-way (an injection into tanks at rest, a withdrawal from a dead-headed tank) is solved with the connection shut throughout: the tanks bottle up or starve. It needs one step to span the event, which is what a rest period grows the steps to; the `drainReopen` case, whose event comes while the steps are still short, opens inside slice 1 either way.
  - *The rule.* A converged step whose accepted modes hold a passive run closed (not one blocked both ways before the solve) although the step's own end states drive the run in a direction every link allows by more than `max(1 Pa, 1e-6 P)` is refused; the same step is solved again with that run exempt from the two start closures. The run is the maximal chain of closed passive connections through degree-two junctions no actuator touches (the run `closeDeadHeads` decides on). The forward head is taken on the first end's density and the reverse head on the second's. A junction end counts only while one of its connections is open. Each run is reopened at most once per attempted step; the pass loop's own `illegalDirection` closure of a reopened run stands.
  - *The retry mechanics.* `boundaryClosed` is built per solve, so a retry is a new solve with a different start closure. Only the active set changes; the step does not.
  - *Measured.* Transient 50 mm at 13 s: the largest tank difference to the 0.1 s run falls from 2799.5 Pa to 1.3 Pa at 5 s with the hint; 20 mm unchanged (117.1 Pa, the lag of 8.7). At 0.1 s every tank pressure is within 0.05 Pa of 97g.
  - *Cost.*

    | case | cadence | reopens | accepted / rejected, off to on | Newton solves | ms off to on |
    |---|---|---|---|---|---|
    | transient, 12 cases (97c to 101) | 5 s, hint | 2 | 232 / 8 to 232 / 10 | 272 to 274 | 342 to 333 |
    | transient, 12 cases (97g to 102) | 0.1 s | 3 | 1922 / 2 to 1922 / 5 | 2158 to 2161 | 837 to 1142 (JIT order: 102 ran no static probe first) |
    | `withdrawReopen` (103) | 5 s | 19 | 145 / 13 to 156 / 46 | - | 32 to 103 |
    | `drainReopen` (103) | 5 s | 19 | 176 / 12 to 187 / 43 | - | 51 to 93 |
    | `flush` (103) | 5 s | 3 | 238 / 11 to 239 / 15 | - | 30 to 30 |

    Every other case: 0 reopens and the same line. The reopened solves of the two water-into-gas cases fail their Newton 17 times (line-search stalls from a zero start flow across the liquid/gas friction kink) before halving converges; that is the cost, not an unconverged result.
- **Pump head limit re-closed at every step (`junction.pumpReopenStart`, run 107 trace).**
  - Once the pump of `risingPump` has closed, the next solve's carry test sees +12328 Pa of margin and offers the head limit back. The pass then starts from the closed point: zero flow and the closed pump's head, 484406 Pa.
  - `Equations.headDensities` decides the pump row's column on that start point. From that head it picks the tank's bulk density (879 kg/m3, gas headspace included) instead of the water column (996 kg/m3).
  - The converged end state therefore sits 3423 Pa past the water-column shutoff, so the pass closes the pump again, and the closed re-solve leaves the tank where it was.
  - At 0.1 s the same code lands at 861063.055 Pa: *inferred*, the flow at each short step end keeps the margin positive until the landing. At 5 s every step ends past it, so this is a fixed point.
  - *Fix:* a pump whose previous mode was CLOSED and which a pass runs on its head limit starts from the limit's own flow and head (`headLimitMassFlow`), as a pump arriving from the target branch already does.
  - *Measured:* 5 s landing 861063.075 Pa (0.1 s: 861063.055); the 0.1 s runs, `pumpFill` and `gasPump` unchanged; ElevatedBlockLineIsland now fails only on the backward-Euler landing, +144.77 Pa against a 90 Pa tolerance (see (d)).
  - *Not fixed:* the column-density inconsistency itself. The shutoff test states the column on the suction density, and the row states it on whichever end `headDensities` picks. It explains the +144.8 Pa, and every vertical pumped line is exposed to it.

#### (d) The 8.6 unexplained failures, under the owner's lens

| 8.6 failure | now | class |
|---|---|---|
| FilterBlock pumped landing, -2.24 Pa (cap 0.02) | passes at cap 0.05 (99j, 105, 110); not re-run at 0.02 | DELAYED (landing accuracy at 0.02) |
| Elevated pumped landing, +143.65 Pa (cap 0.02); 848734.80 Pa at cap 0.05 (8.7) | at cap 0.05 a pump re-closed at every step, 12.3 kPa short for good (106/107); with `pumpReopenStart=limit` 861063.07 Pa, which is the 0.1 s backward-Euler landing to 0.02 Pa and +144.77 Pa from the test's TR-BDF2-derived 860918.31 | **BROKEN, FIXED**; the residual is the landing accuracy of (c) |
| FullTankSolidsEvent, cake at 24.999999999975 kg, no clog declared | the interval-start pass declared it and did not stop the filter | **FIXED** (run 100, 4/4) |
| PumpRiseScaling, "domain cause lost" | the typed cause was present at the 8.7 configuration; the message lacked the rejection map | **FIXED** (run 100, 5/5) |
| CausalModuleCoordinator, last-ulp digests of certified against every-interval islands | message unchanged in 105 and 110; not an event mechanism, not traced here | open (bitwise replay; 8.6 (g) item 4 stands) |

#### (e) Gates

| run | configuration | 7 gate classes (38) | fluid suites | against 99j (386/402, 16 failures) |
|---|---|---|---|---|
| 104 / 105 | D+E + the two `be` fixes + `boundaryReopen=on` | 36/38 | 389/403 (the 399 suite tests plus four probe wrappers, the new `JunctionEventProbe` among them) | FullTankSolidsEvent and PumpRiseScaling now pass; the other 14 messages equal, to 5000 characters |
| 109 / 110 | + `pumpReopenStart=limit` | 36/38 | 389/403 | as 105, except ElevatedBlockLineIsland: 848734.80 to 861063.07 Pa (expected 860918.31 +- 90) |
| 111 | defaults, no init script | - | **399/399** in 91 classes, 0 skipped | - |

The fourteen remaining failures and their classes:
- NetworkRegime x2 and SolidChainTransport x2: intended (they call TR-BDF2 directly or count its structure).
- Accuracy, first order, **DELAYED**: PassiveTimeRefinement, TransientQualification x3, CadenceTrajectory, ScheduledTransferEstimator and McpGameplayRegression. SolidClosureFeasibility belongs here too: the deposition closure is located at 0.0125 against the tight 0.0055, i.e. later.
- ElevatedBlockLineIsland: the landing residual of (c).
- CausalModuleCoordinator: open.

#### (f) What the PSV and burst mechanisms may assume

**What the accepted state at a slice end guarantees (measured over runs 100-111):**
- *Conservation:* species, solids and energy exactly (reconstruction plus `checkConservation`; ledger at most 4.9e-10 relative).
- *Per-step change:* no tank's pressure or mass moves more than 5 % of itself in one accepted step, except an algebraic jump (a change that halving does not reduce) or a step at the transition floor.
- *Physical bounds, all held:*
  - a pump's discharge stays at or below its shutoff (601324.947 against 601325 Pa);
  - a passive fill stays at or below its source (400000.000 Pa);
  - a filter cake stays at capacity less 1e-12;
  - liquid stays inside the tank (by the flash);
  - every endpoint flow stays within its velocity cap to 2e-8.
- *Domain:* every accepted state is inside the material domain. A crossing is a typed hold of the *whole* slice with no partial commit, so the state a check at the slice end sees is the last one inside the domain (899.68 K against 900 K).

**What it does not guarantee:**
- *Timing.* An event that happens inside a slice is seen at the slice end.
- *Overshoot.* A threshold that is not a model bound (a PSV set point, a burst rating) is overshot at the slice end by up to (rate of rise) x (slice length). Measured: 38.2 kPa past 150 kPa and 18.6 kPa past 300 kPa on an 8.68 kPa/s ramp, 40.6 kPa past 300 kPa on the pumped fill; with the 0.1 s slices, 24.6 and 352.6 Pa. The 5 % state cap bounds each step (about 1 s, five steps per slice on the ramp) and not the slice: 38.2 kPa is 25 % of the threshold. The crossing time can be interpolated between two slice ends; on the linear ramp both cadences give 5.597 s.
- *Accuracy.* Slow approaches lag at backward-Euler accuracy: 8.6 s late on the cake growth; 1.1 s late on the 900 K approach.

**Design consequences:**
- *As a slice-end check* (the owner's "one cycle late"): PSV and burst read the accepted state and act as queued events in the next slice. The tank may sit above the set point for one slice by up to rate x 5 s. A burst rating needs that margin, or the check must use the interpolated crossing and the rate.
- *As a device inside the solver* (a relief valve's mode): it inherits the active-set rule of this section. A mode decided on a step's end state is applied to the whole step, and nothing re-examines it within the step.
  - An opening decision is safe: it is the direction `boundaryReopen` and `pumpReopenStart` restore.
  - A closing decision taken on the end state of a long step can hold the wrong mode (2d above). A relief device that closes (reseats) needs the same audit: its start point, and whether its closed re-solve's own end state would reopen it.
- *Burst as a topology event* (destroy the tank, vent it, rebuild the island) belongs with the existing configuration events: applied at an aligned slice boundary, one slice after the state crossed the rating.

#### (g) Open

1. The pump column-density inconsistency of (c): the shutoff test states the column on the suction density, the hydraulic row on the end `headDensities` picks. It explains ElevatedBlockLineIsland's +144.8 Pa and was the root of the 5 s fixed point.
2. The reopened solves' Newton failures (17 line-search stalls per water-into-gas event). A reopened run could start from its allowed-direction flow instead of zero.
3. The domain hold spends the 1024-attempt cap per refused slice (the 12-slice `domainHot` run at 5 s took 346 ms, against 9 ms for the 12-slice ramp). It is parked afterwards (wait-for-inputs), but the first refusal costs that.
4. The two `be` fixes are part of the basis; the two switches are off by default. A production version decides whether the reopen rule and the pump reopen start belong to the basis.
5. `CausalModuleCoordinator`'s last-ulp replay difference (8.6 (g) item 4) is unchanged.
6. *Documentation index.* The main checkout's `documentation/INDEX.md` and the batch's copy there are not updated; no main-checkout edits in this brief.

### 8.9 Certified replay and pump column density (runs 112+)

Run 2026-09-26 in the same worktree at 9674bf1 by Claude (Opus 5.5), on the owner's directives: find why the certified run and the solve-every-interval run of `CausalModuleCoordinatorTest` differ in the last ulps (certificates promise bitwise replay), and state a pump's hydrostatic column on the fluid it pumps (its suction), never on the destination, behind a switch. Events seen one slice late stay accepted.
- *Code.* `holdup-prototype-events.patch` plus the two switches and a trace below; the full diff is `tools/junction-holdup-prototype/holdup-prototype-replay.patch`, sixteen tracked files (the fourteen of the events patch plus `runtime/fluid/RetainedSolver` and `runtime/fluid/IslandCoordinator`). It contains every earlier patch; apply it alone. `--check --reverse` on the patched tree and `--check` on the restored base pass; `git status --short` shows nothing tracked.
- *Conditions.* Gradle calls one after another, no dev client, nothing committed, no tracked test modified, `checkConservation` and the reconstruction gate unchanged.
- *Configuration.* Run 110's set: D+E (8.8) plus `-PboundaryReopen=on -PpumpReopenStart=limit`; "both switches" adds `-PreplayDeterministic=on -PpumpColumn=suction`. Every log's first line is its command (`edit-scripts/run-probe.ps1`). Line numbers below are in the tree with the replay patch applied.

#### (a) What the test compares

`certifiedIslandsReproduceTheModuleOutcomeOfIslandsThatSolveEveryInterval`: three scenarios of single-reservoir islands (no pipes) coupled by a `FixedSplitModule`; "coupled" has two wet feed tanks (islands 1, 2) and two dry nitrogen product tanks (3, 4), cadence 300 ticks. The reference harness solves every 100-tick interval with certificates disabled; the certified harness (reads true, then false) lets islands certify REST between cycles and deliveries and replays them. After 3000 ticks it compares, per island, the clock, fences and the bits of every inventory mole number and internal energy, then the module and pending ledger. Run 110: islands 3 and 4 differ in the last 2-5 ulps (the certified harness dispatched 118 solves against 120).

A certified island is woken (drive or horizon) with a **new `RetainedSolver`** (`IslandCoordinator.java:901`; certifying drops it, `:956`). The every-interval island keeps its solver, so everything a solver carries between jobs is an input the certified island does not have.

#### (b) History-dependent inputs (code reading)

| # | input | where | reaches |
|---|---|---|---|
| 1 | **Newton workspaces with their numeric Jacobian and LU** (`workspaces`, `structures`) | `PassiveStepSolver.java:306-307`; a dt-keyed miss opens on `structures.get(structure).forkPreconditioner()` (`:529`, `SparseNewton.java:111`), and `SparseNewton.solve` then skips the first Jacobian (`:137`) | the first Newton of a job opens on the last job's factorization; the converged point differs within the Newton tolerance |
| 2 | warm-start flows and heads (`previousFlows/Heads`, `previousInputStates`) | `:310`, `:313`; read by `startPoint` (`:1396`) | Newton start point |
| 3 | pump active-set carry (`previousModes`) | `:326`; carry offer `:414`; `pumpReopenStart` | pass-0 modes, pump start flow |
| 4 | "structure accepted" for the void-start gate (`acceptedPipes`) | `:328`, gate `:1746` | start-of-solve closures (`history2`) |
| 5 | "structure accepted" for backward Euler (`beAcceptedPipes`, `backwardEulerCold`) | `:332`, `:347`; balanced-seed vessel eligibility `:1217`; cold rate seed in `PassiveIntervalSolver` | junction seed states, one rate solve |
| 6 | predictor states | `:336` (predictor off in this configuration) | pass-0 start |
| 7 | reopen exemption (`keepOpen`) | `:190`, consumed by the next non-rate solve | start closures of a later job if a job ended before consuming it |
| 8 | last solve | `:351` (read by TR-BDF2's companion only) | - |
| 9 | backward-Euler endpoint carry (`beModes`, `beFlows`) | `PassiveIntervalSolver.java:478-480`; start-of-step guard `:327`, carried modes `:361` | solid guard at t0, mode rule (off) |
| 10 | cold-start seed graphs | `:438` | cold start |
| 11 | **step hint** (`nextStepEstimate` into `RetainedSolver.stepHint`) | `PassiveIntervalSolver.java:429`; `RetainedSolver.java:42`, `:93`; a fresh `RetainedSolver` starts at 0.05 s | first step of an ordinary interval; planner trials always start at 0.05 s (`:99`) |

Checked and not history-dependent: the LU ordering (from the symbolic pattern) and the transport orderings (keyed on the pattern and its stored zeros); the flash (Wilson K at every call; `PengRobinsonKernel` roots from the immutable polynomial); `lowAlloc`'s reuse of a node's Peng-Robinson workspace (arithmetic-neutral, 8.7); `phaseCheck=once` (no cache); the viscosity buffer (rewritten before use). `SolidEventIntegrator` is new per call. TR-BDF2's endpoint-rate cache is history too, but backward Euler never reaches it.

#### (c) First divergence (runs 112, 113)

Run 112 traces every job (`REPLAYJOB`) and every backward-Euler attempt (`REPLAYTRACE`: step bits, verdict, iterations, whether the first Newton opened on a held factorization, node P/T bits) in both harnesses; `replay-compare.js` aligns the delivery trials of each island.
- Islands 1 and 2 (30 withdrawal trials each) are equal bit for bit.
- Islands 3 and 4: the first delivery trial is equal (both solvers fresh). The **second delivery trial** (job 1 of 9) differs at its first attempt: same start state, same step (0.05 s, the trial's cold start), but the every-interval solver opens its Newton on the factorization left by the first delivery 15 s earlier (`preconditioned=true`, 4 iterations, `workspaces=4 structures=2`), the woken solver opens fresh (3 iterations). The converged pressures differ in the last hex digits (`40f9a28852db6af9` against `...52e27f9f`), and from then on the committed P/T differ.
- The inventories stay equal while the controller's step sizes do: a pipe-less tank's moles are `n0 + sum dt q` with a fixed q. At trial 6, attempt 4, the first step size differs (`3fe75c2cdaaf51cd` against `...520e`): backward Euler's growth factor `clamp(0.9 sqrt(cap/change))` (`PassiveIntervalSolver.java:398`) is a continuous function of the converged pressure change, so the ulp difference in P reaches the step, the decomposition of the delivery changes and the summed inventory differs in its last bits. TR-BDF2's factor is clamped at 2 on these error-free steps, which is why the same history did not show there.
- **Cause: input 1**, a preconditioner carried across a job boundary. Input 11 does not enter this test (the committed results are trials, which start at 0.05 s in both runs; the baseline solves they discard differ in step count, 1 against 7 accepted, but a pipe-less rest step is the exact shortcut).

#### (d) The fix: `junction.replayDeterministic=on`

A job starts from what a fresh solver would derive from the island's committed interval, nothing else:
- `IslandCoordinator` hands the island's `RetainedSolver` its committed interval (`lastResult`) at dispatch; the job's first call runs `PassiveIntervalSolver.replayStart(graph, committed graph, committed endpoint modes)`.
- Inputs 1, 2, 6-10 are dropped: every retained workspace loses its numeric Jacobian and LU (pattern, colouring and ordering stay; they are functions of the structure), the warm start starts from `initialMassFlows` of the committed states.
- Inputs 3-5 are restated from the committed interval, so a woken island and one that solved every interval get the same values: the pump carry from the committed endpoint modes (a pump on its head limit or CLOSED there), the two "structure accepted" flags true exactly when the committed interval has the same pipe identities and node ids (no extra cold start: the cold seed flashes stay 2976 in run 115).
- Input 11: the ordinary interval starts at `min(Settings.initialStep, duration)`, a function of the interval.

Results:
- **Run 113: the test passes**, all three scenarios, reads true and false; every trial trace of islands 1-4 equal bit for bit between the harnesses.
- *Cost, 5 s transient probe with the hint (run 114, the run 110 set) against the switch (run 115; the probe applies the same rule per interval):*

  | run | nonconvergence | ms | accepted / rejected | Newton solves | iter/solve | Jacobians | opened preconditioned | alloc MB |
  |---|---|---|---|---|---|---|---|---|
  | 114 | 0 (static 32/32) | 318 | 232 / 10 (newton 3, be-reopen 2, be-state 5) | 274 | 8.43 | 311 | 204 | 125.9 |
  | 115 | 0 (static 32/32) | 344 | 228 / 22 (be-state 15, be-reopen 2, newton 5) | 281 | 8.56 | 409 | 164 | 134.2 |

  +8 % wall, +2.6 % Newton solves, +32 % Jacobian builds (every job's first Newton builds one), +12 rejections (the 1 s first step of an injection interval refused by the 0.05 cap). Accuracy against the 0.1 s run 97g is the 8.7 class: 20 mm at 10 s 845 Pa (114: 799), 50 mm at 13 s 0.3 Pa (114: 1.3).
- *Side effect (gates, (f)).* With no warm start, a closed pair of nitrogen tanks 1.3e-5 Pa apart converges in 0 iterations from zero flow (hydraulic residual about 1e-10 against the 1e-9 tolerance): change exactly 0, a REST certificate. Without the switch the warm start carries the last flows and every interval re-converges elsewhere inside the tolerance: 0-9 iterations, changes of 4.4e-12 to 1e-9 per interval, STEADY (runs 125/126). Three fixtures assert that STEADY ("they settle to solver-noise flows and certify STEADY"), and two tests loop forever: a default REST horizon is `Long.MAX_VALUE` (`recheckSeconds` 0) and they run the rig to the horizon.

#### (e) Pump column density: `junction.pumpColumn=suction`

Every place a pump edge's static column is chosen (code reading):

| place | density used before | under `suction` |
|---|---|---|
| hydraulic row `edgeRows` (`driving = P_a - P_b - rho g dz + head`) | `Equations.headDensities`: the start point's bulk density of the first **or the second** end, by the bistable rule, with the pump head in the driving pressure | the suction's transported density at the trial, `tr[first].density` |
| pass-loop shutoff/margin test (`demand`, `riseLimit`) | suction bulk density at the converged point | unchanged (already suction) |
| carry offer at solve start | suction bulk density of the accepted start state | unchanged |
| `headLimitMassFlow` (the reopen start) | suction bulk density of the start state | unchanged |
| `initialMassFlow` of a pump | target flow times suction density (no column) | unchanged |
| accepted-mode classification | no column | - |

The model has no inlet-phase concept: a node is withdrawn homogeneously and `Transport.density` is its bulk mass over volume, the donor transport the row's loss already uses. So the rule is "the suction's bulk density at the trial", and the row and the margin test now read the same value at the converged point.

Measured:
- **ElevatedBlockLineIsland (runs 116-118):** 861062.98 Pa under backward Euler (110: 861063.07), still +144.67 Pa against 860918.31 +- 90. The +144.8 Pa of 8.8 was **not** a column mismatch:
  - trace 117 at the landing: suction junction 390229.58 Pa, density 996.296, closed head 500144.50 Pa, margin 4.3e-5 Pa. The landing is the model's exact shutoff, `P_suction + riseLimit - rho g 3 m`;
  - `riseLimit` scales the 500000 Pa setting by suction density over the pump reference density (996.296 / 996.008, +0.029 %): **+144.50 Pa**. The test's expectation `pressure + PUMP_HEAD - head(pressure, LIFT)` takes the rise as exactly 500000 Pa;
  - **TR-BDF2 with the suction rule (defaults + `pumpColumn=suction`, run 118) lands at 861063.08 Pa and fails the test too.** At defaults it lands at 860948.06 (run 111) and passes; the switch changes nothing but the row's column, so (inferred, not traced) its row used the other end's column in some passes and the pump closed 115 Pa early, which happened to offset the rise scaling. The test's "measured gap 23.6 Pa" is that inconsistency.
- **8.8's 5 s fixed point:** with `suction`, `risingPump` at 5 s closes in slice 19 at 861062.982 Pa with `pumpReopenStart=carry` and with `limit` (run 119; run 108 `carry`: held at 848734.80 for good). The `limit` start is no longer needed for this case.
- `risingPump` 0.1 s: CLOSED at 88.9 s at 861063.055 Pa (unchanged). `pumpFill` (flat: 601324.947 Pa at 5 s) and `gasPump` (flat: dP 572.99 against the limit 573.81 Pa) unchanged bit for bit, as `dz` = 0.
- DeadHeadedLineIsland 4/4, PumpJunctionStartup 1/1, PumpRiseScaling 5/5, IslandCertificate 22/22 (run 116). No nonconvergence, no held slice.

#### (f) Gates

| run | configuration | 7 gate classes (38) | fluid suites |
|---|---|---|---|
| 109 / 110 | run 110 set | 36/38 | 389/403 |
| 120 / 124 | + both switches | **36/38**, NetworkRegime x2 as 109 | **384/401** run, 2 tests excluded because they never end (runs 121, 123 killed); 384/403 counting them failed |
| 127 | defaults, no init script | - | **399/399** in 91 classes, 0 skipped |

Every change of run 124 against run 110 (the other 12 failure messages are equal to 110's):

| test | 110 | 124 | class |
|---|---|---|---|
| `CausalModuleCoordinatorTest.certifiedIslands...` | ulp difference, islands 3/4 | **passes** (bitwise) | **FIXED** (`replayDeterministic`) |
| `ElevatedBlockLineIslandTest.elevatedLines...` | 861063.07 | 861062.98 (expected 860918.31 +- 90) | **stale expectation**: the landing is the model's shutoff with the rise limit's density scaling (+144.50 Pa), which TR-BDF2 also reaches once its column is consistent (run 118) |
| `FluidCheckpointFormatTest.clocksMaterialFences...` | passes | "the certificate summary names the kind", expected STEADY (2) but was REST (1) | **consequence of `replayDeterministic`**: the closed pair rests exactly (no warm start), the fixture expects solver-noise STEADY |
| `FluidCheckpointFormatTest.aRestoredCertificate...` | passes | "the closed pair moved since its base" false | same |
| `IslandCertificateTest.aSettledClosedPairCertifies...` | passes | expected STEADY but was REST | same |
| `FluidCheckpointFormatTest.aCertifiedPayloadIsCopied...` | passes | never ends (excluded) | same, plus a test-harness hazard: it runs to a REST horizon of `Long.MAX_VALUE` |
| `PresentationBucketTest.aPresentationReadStopsShortOfAWake...` | passes | never ends (excluded) | same |
| `RetainedSolverTest.aRetainedSolverReusesItsStructureAcrossTheIntervalBoundary` | passes | "carried preconditioner 2 vs fresh 1 Jacobian builds" | **intended**: the switch forbids a preconditioner across a job boundary, which this test asserts |

Classes of the remaining twelve, unchanged from 8.8 (e): NetworkRegime x2 and SolidChainTransport x2 intended (TR-BDF2 structure); PassiveTimeRefinement, TransientQualification x3, CadenceTrajectory, ScheduledTransferEstimator, McpGameplayRegression and SolidClosureFeasibility accuracy (first order, DELAYED).

Run 122 tried a JUnit per-test timeout instead of the exclusion: in `SEPARATE_THREAD` mode it fails 16 IslandCoordinatorTest cases on "Island coordinator belongs to its server thread". It is a harness artifact and was discarded (369/403, `run122-gates-timeout-artifact/`).

#### (g) Open

1. *The closed-pair decision is the owner's.* Under `replayDeterministic` a pair settled to within the Newton tolerance is an exact REST (cheaper, horizon never rechecked by default) instead of a noise-level STEADY. Either re-baseline the three closed-pair fixtures to REST, and give the two tests that run to a horizon a finite one (`recheckSeconds`), or carry the warm start as part of the committed state (the committed interval's own flows handed over like the modes), which keeps replay bitwise and restores the noise.
2. *ElevatedBlockLineIsland's expectation* should be `P_gen + PUMP_HEAD rho_s/rho_ref - rho g LIFT` (the model's shutoff), or the rise limit's density scaling revisited. This holds under TR-BDF2 as soon as the column is consistent (run 118).
3. *Replay scope.* The fix is backward-Euler only: TR-BDF2's endpoint-rate cache is not reset. The pump carry is restated from *accepted* modes, so a pump on its head limit that is also velocity-limited (`PUMP_VELOCITY_LIMIT`) is not carried. Holds still hand the retry a fresh solver whose committed state is the same, so they are covered; `maximumSliceTicks` after a hold is coordinator state and can still make the two runs cut different slices.
4. *Cost of the reset:* +8 % wall and +32 % Jacobian builds at 5 s slices (one fresh Jacobian per job). A certificate that carries the factorization was not tried.
5. 8.8 (g) items 2-4 and 6 stand; item 1 is resolved (the column is not the residual) and item 5 is fixed under the switch.
6. *Documentation index.* The main checkout's `documentation/INDEX.md` and the batch's copy there are not updated (no main-checkout edits in this brief).

## 9. Productization (BE_INTEGRATOR_PLAN.md)

### 9.0 WP0 provenance rerun

Run 2026-09-26 in worktree remove-agents-attribution-line-3d0c97, branch claude/charming-elion-1170cc at 9674bf1, by Claude (Opus 5.5). `git apply --check` then `git apply tools/junction-holdup-prototype/holdup-prototype-replay.patch` (16 tracked files), the two runs below one after another, then `git checkout -- src` back to clean. Environment `JAVA_HOME=C:/Program Files/Java/jdk-21.0.11`, `JAVA_OPTS=-Xshare:off`. Logs and XML: `tools/junction-holdup-prototype/run128-*`, `run129-*`.

| Run | Command (first line of the log) | Result | Expected |
|---|---|---|---|
| 128 | `./gradlew.bat --no-configuration-cache -I tools/junction-holdup-prototype/holdup.init.gradle test --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* -PcapForm=B2 -PrateForm=frozen -PrateWarmStart=fresh -PvoidStart=history2 -PmintHoldup=on -PstageForm=implicit -PholdupTau=0.05 -PpinMass=on -PstageClip=on -Pintegrator=be -PbeModeRule=off -PbeColdStart=rate -PbeStateCap=0.05 -PphaseCheck=once -PlowAlloc=on -PboundaryReopen=on -PpumpReopenStart=limit -PreplayDeterministic=on -PpumpColumn=suction -PexcludeTests=*FluidCheckpointFormatTest.aCertifiedPayloadIsCopiedNotReencodedUntilASolveOrACertificate,*PresentationBucketTest.aPresentationReadStopsShortOfAWakeAndNeverWakesTheIsland --console=plain --continue` | **384/401 run, 384/403** counting the two excluded; the 17 failure messages equal run 124's character for character | 384/403 |
| 129 | `./gradlew.bat --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` | **399/399** in 91 classes, 0 skipped | 399/399 |

The first run 129 came FROM-CACHE (Gradle build cache, identical inputs to run 127); it was repeated with `--no-build-cache test --rerun`, which the table records. The 7.11 provenance gap is closed: the replay patch reproduces 8.9 (f) on a clean 9674bf1.

### 9.1 WP1: switch collapse and TR-BDF2 removal

Run 2026-09-26, same worktree and branch, by Claude (Opus 5.5). Four WIP commits on claude/charming-elion-1170cc (not pushed): stage 1 `3dba4b0`, stage 2 `640d87b`, stage 3 `b6422e3`, stage 4 `618ea63`. Whole WP1 against 9674bf1: 38 files, +1370/-981 (main sources 22 files, +1031/-913; `TrBdf2StepSolver` deleted, `StageGuard` new). Gate logs and XML: `tools/junction-holdup-prototype/run130` to `run138`. The fluid-suite gates use `tools/junction-holdup-prototype/exclude.init.gradle` (exclusion only, `-PexcludeTests`), because the two tests that run a REST certificate to its `Long.MAX_VALUE` horizon never end until WP2 (8.9 (d)); run 131's first attempt without it hung in `FluidCheckpointFormatTest.aCertifiedPayloadIsCopied...` and was stopped.

#### Stage 1: the basis (`3dba4b0`)

- *Diff.* PassiveStepSolver, ConservativeTransport, PassiveNetwork, PhysicalRegistry, PipeTransfer, PhaseLayout and the four thermo files of lowAlloc. Every prototype switch of plan section 2 that belongs to the basis became the only behaviour: owned junction holdup (tau 0.05 s x max cap flow, enthalpy form), the mass pin, the mint booked in the topology ledger, amount-form junction mixing and enthalpy rows, cap row B2, void start `history2`, the reopen support (`reopenable`, `reopenNext`), pump reopen from the head limit, suction density for every pump column, phaseCheck once, lowAlloc. Removed: every `junction.*` and `createcheme.junctionHoldup` property, every trace print and prototype counter, the Jacobian dump, the dead zero-holdup junction code (`restateJunctions`, `refineJunctionReachability`, `donorsTurned`, `junctionsTurned`, the inflow-floor ratio closure, `JUNCTION_INFLOW_FLOOR`). TR-BDF2 stayed the integrator with the frozen rate, the implicit stage one and the stage-two clip unconditional.
- *m_J without the shortcuts.* A junction built from a property state is minted with an **empty** inventory, which means exactly "not sized yet" (a sized junction always owns positive mass); `PassiveNetwork.sizeJunctionHoldups` sizes it once its pipes are known (PhysicalRegistry before the constructed booking, otherwise at every solve entry) and mints m_J of its seed state; the volume field is the state's (nothing reads it for a junction). m_J is derived from the inventory's own mass, and the pin holds that mass: outflows booked at `In'/Out` (no outflow: non-junction inflows at `(Out - In'_junction)/In'_other` when in [0, 1]). The prototype's volume-field m_J, its `Double.MIN_VALUE` unsized flag and 1 kg placeholder, `JUNCTION_HOLDUP` and `mintLine` are gone. Consequence: a junction that drifts (an unpinned junction cycle) keeps its drifted mass instead of being re-anchored to a stored m_J; no gate saw a difference.
- *Gate (run 130), 38 adjacent tests: 36/38*, the two NetworkRegime holdup changes only (0.5722427452 against the TR-BDF2 expectation 0.6326450275; 44.07 against 0.0), run 120's values to 10 digits. ElevatedBlockLineIsland is not in the 38.

#### Stage 2: the integrator (`640d87b`)

- *Diff.* `PassiveIntervalSolver` steps with `PassiveStepSolver.solve(graph, h)` directly: the state-change controller (cap 0.05, growth `clamp(0.9 sqrt(cap/change), 0.5, 2)`, halving on Newton failure, the algebraic-jump acceptance, the transition floor, no one-transition rule), boundary reopen with its own rejection key, one cold rate solve with the balanced seed while the structure is cold (vessel neighbours count as held until the first accepted step), `checkRate` at every step start (previous endpoint, else the carried one, else one port-graph rate solve), `checkFilters` at every step end, the rejection map in the exhaustion message, `replayStart`. `SolidEventIntegrator` runs on it and stops the filter at the interval-start closure pass on FILTER_CLOGGED. `RetainedSolver` resets the solver at every job start from the committed interval that `IslandCoordinator` hands over at dispatch, and starts an ordinary interval at `min(initialStep, duration)` (the step hint is gone). Removed: `TrBdf2StepSolver` (endpoint-rate cache, companion, algebraic solver, stage bases and clip), `ErrorControl` and the embedded and step-doubling estimators, `PipeTransfer.relaxed`, the relaxed booking and the recorded bookings of `ConservativeTransport`, `SparseNewton.applyFactorization`, `PhaseLayout.targetRows` and the unweighted junction rows, the companion and endpoint-rate diagnostics counters, `InlineFilter.combine`, `SolidInventory.combine` and `finishNonNegative`. `StageGuard` is a top-level interface (ApproximationAnchor, SolidEventIntegrator and RetainedSolver use it). The rate-only solve keeps the frozen junction rows and pseudo-boundary booking (the cold seed and the guard read its states and flows).
- *Tests, compile only* (no assertion changed before this gate): `new TrBdf2StepSolver(model)` to `new PassiveStepSolver(model)` (one step) in EmptyNetworkTest, HydraulicMatrixQualificationTest x4, HydraulicReferenceQualificationTest, NetworkRegimeTest x4, TrBdf2Test x3; `new PassiveIntervalSolver(model, ErrorControl.STEP_DOUBLING)` to `new PassiveIntervalSolver(model)` in CadenceTrajectoryQualificationTest, McpGameplayRegressionTest x2, ScheduledTransferEstimatorTest, TrBdf2Test (the prototype ignored the error control under `be`, so these reproduce its behaviour).
- *Gate (run 131), fluid suites before any assertion was edited: 379/397 run, 379/399* with the two horizon tests excluded. Classification:

| Test | Message (run 131) | Class |
|---|---|---|
| NetworkRegimeTest x2 | 0.4577 against 0.6445; 45.73 against 0.0 | intended, listed (D1/D2) |
| SolidChainTransportTest.clearChainSubstepCounts... | expected 25 but was 3 | intended, listed (D7) |
| SolidChainTransportTest.filterIslandReuses... | Unknown solver diagnostic endpointRateReuses | intended, listed (D7) |
| PassiveTimeRefinementTest, TransientQualificationTest x3, CadenceTrajectoryQualificationTest, ScheduledTransferEstimatorTest, SolidClosureFeasibilityTest | the 8.9 (f) messages | intended, listed (D1/D4) |
| RetainedSolverTest | carried preconditioner 2 vs fresh 1 | intended, listed (8.9) |
| ElevatedBlockLineIslandTest | 861062.98 against 860918.31 +- 90 | intended, listed (D3/D6) |
| McpGameplayRegressionTest.belowSeaLevel... | 203.164 against 203.419 | intended, listed (D1) |
| IslandCertificateTest closed pair, FluidCheckpointFormatTest x2 | STEADY/REST | intended, listed; WP2 |
| **TrBdf2Test.embeddedControl...SecondOrder** | error ratio 2.03 (> 3 asserted) | **intended, not listed** (D1/D7: backward Euler is first order); added to plan section 5 |

  The 17 failures of run 128 are the same tests; HydraulicMatrix, HydraulicReference and EmptyNetwork pass on the backward-Euler step. No defect.

#### Stage 3: test adjustments (`b6422e3`)

| Test | Old assertion | New assertion | Measured (runs 132-134) | Reason |
|---|---|---|---|---|
| NetworkRegimeTest.twoDifferentFeedsMix... | junction methane fraction = q0/q2 to 1e-8; specific enthalpy = inflow mixture +-0.001 J/kg | fraction = q0/(m_J/dt + Q) to 1e-8 and strictly between 0 and q0/q2; enthalpy = (m_J/dt h_seed + sum q h)/(m_J/dt + Q) +-0.001 J/kg | pass | D1/D2 |
| NetworkRegimeTest.reversingOneFeed... | junction nitrogen amount 0 +-1e-9 | nitrogen mass fraction = (m_J/dt)/(m_J/dt + Q_in) to 1e-8, in (0, 1) | pass | D1/D2 |
| SolidChainTransportTest.clearChainSubstepCounts... | 25/10, 32/14, 18/5 accepted/rejected | 3/1, 5/1, 4/0 | exact | D7 |
| SolidChainTransportTest.filterIslandReuses... | endpoint-rate reuses > 4 x builds | implicit solves <= attempted steps + 2 per interval | 76 solves, 57 attempts, 19 intervals | D7 |
| PassiveTimeRefinementTest | P 0.005, T 0.5 K, integrated mass 0.005 | 0.0075, 0.75 K, 0.02 | 0.00475, 0.496 K, 0.0141 | D1 |
| TransientQualificationTest.diluteGas... | pressure difference 0.01, mass 0.01 | 0.025, 0.06 | 0.0166, 0.0398 | D1 (a 1 Pa signal never binds the cap) |
| TransientQualificationTest.freeWater... | mass 0.005, P 0.005, T 0.5 K (reference refinement 0.001 and water fraction 0.005 unchanged) | 0.015, 0.0125, 2 K | 0.0094, 0.0081, 1.37 K | D1 |
| TransientQualificationTest.liquidCompression... | integrated mass 0.005 | 0.075 | 0.049 (wet crude, one 0.5 s step) | D1 |
| CadenceTrajectoryQualificationTest | reference refinement 0.001; fixed and adaptive cadences 0.005 | 0.0025; 0.02 | 0.00185 (water); 0.0148 (water, 20 s cadence, cumulative flow) | D1/D4 |
| ScheduledTransferEstimatorTest | each boundary component within 0.1 % of itself | within 1 % of the boundary's total amount (energy 0.1 % unchanged) | methane 0.160 against 0.120 mol, 0.74 % of 5.41 mol | D1 (the composition integral is not in the cap) |
| SolidClosureFeasibilityTest | closure within 5 floors (5e-6 s) of the tight one | not earlier than the tight one, within 10.5 ms | 0.0125 against 0.0055 s | D4 (one slice late) |
| McpGameplayRegressionTest.belowSeaLevel... | tank mass 0.1 % | 0.2 % | 0.125 % | D1 |
| RetainedSolverTest.aRetainedSolverReuses... | carried Jacobian builds < fresh | carried builds = fresh builds; inventories and pressures bit for bit | 1 = 1 after the fix below | 8.9 / D8 |
| ElevatedBlockLineIslandTest | P + 500000 - rho g 4 (860918.31) +-90 Pa | P + 500000 rho_s/rho_ref - rho g 4 +-90 Pa (rho_s: water one block above the generator) | 861062.98 | D6 |
| TrBdf2Test (method renamed `controlledIntervalAgreesWithFixedStepsAndFixedStepsShowFirstOrder`) | embedded = step doubling to 0.5 %; error ratio > 3 | controlled interval within 1.5 % of 256 fixed steps; 1.8 < ratio < 2.5 | 0.88 %; 2.03 | D1/D7 (not listed in the plan; added) |

- *Defect found and fixed:* the new RetainedSolverTest assertion showed a carried job building 2 Jacobians where a fresh handle built 1. `replayStart` invalidated the dt-keyed workspaces in place; a later step at another dt then reused its own empty dt-keyed workspace instead of forking the chord the job had just built, as a fresh solver does, so the two took different Newton paths. `PassiveStepSolver.replayStart` now clears the dt-keyed workspaces and replaces each structure's latest workspace by `forkStructure()` (pattern, colouring, ordering; no numerics). The prototype's CausalModuleCoordinatorTest pass did not cover this (its islands have no pipes). The fix is in the stage-3 commit.
- *Gates:* fluid suites (run 135) **394/397 run, 394/399** with the two horizon tests excluded; the three failures are the WP2 certificate fixtures (IslandCertificateTest closed pair: STEADY/REST; FluidCheckpointFormatTest x2: kind 2/1, "closed pair moved"), left failing as the brief says. The 38 adjacent tests (run 136) **38/38**. GameTests not run (WP4).

#### Stage 4: the probes become gates (`618ea63`)

- `MixedGasJunctionStaticTest` (32 cases, all must converge and close the junction balance) and `MixedGasJunctionTransientTest` (12 cases at 0.1 s and at the 5 s cycle, each interval a job started by `replayStart`; ledger bound 1e-12 relative, measured at most 5.4e-15; Newton solves per case below twice run 115's) in `src/test/java/com/wormzjl/createcheme/runtime/fluid/`, no Gradle properties. The tool folder keeps the probes as the record.
- *Gates:* fluid suites (run 137) **397/400 run, 397/402**, the same three WP2 fixtures failing; both new tests pass. Run 138 (the two new tests alone): pass.
- *Cost, 5 s cycle, 12 cases x 16 s, SolverDiagnostics on, ThreadMXBean allocation:*

| | ms | Newton solves | allocated MB |
|---|---|---|---|
| 8.9 run 115 (prototype, the section-1 bar) | 344 | 281 | 134 |
| run 137 (in the suite JVM) | 314 | 286 | 134.1 |
| run 138 (the two tests alone) | 347 | 286 | 132.7 |

  Per case the solves are within +-3 of run 115 (largest 35 against 32). The +5 solves are the amount-form junction rows, which the plan made part of the basis and which run 115's configuration did not include (8.7 (c) C measured +9 at 5 s on the same probe). Wall time equal within noise, allocation equal.

#### WP1 acceptance (plan section 1)

- Zero nonconvergence in the new tests: 32/32 static; 12/12 transients at 0.1 s and at 5 s.
- Fluid suites green except the WP2 certificate fixtures (and the two horizon tests WP2 must give a finite horizon).
- The 38 adjacent tests green.
- Cost: the table above; Newton solves +1.8 % against run 115, attributed to the amount-form rows.

#### Deviations from the plan and the brief

1. m_J derived from the inventory's own mass, unsized = empty inventory (stage 1), instead of a new field.
2. An `ApproximationRejected` from a step escapes the interval (the base semantics: the caller falls back to FULL) instead of being a halving rejection as in the prototype, whose catch served the refuted `junction.acceptance=approximate` route. No gate changed.
3. Amount-form junction rows are in the basis (plan section 2) although run 128's set did not include them; cost +5 Newton solves at 5 s.
4. Dead code removed beyond the listed switches: the zero-holdup junction machinery and the orphans of the TR-BDF2 removal listed under stage 2. `PassiveIntervalSolver.Settings.relativeTolerance` is kept (widely constructed) but no longer read.
5. The replayStart workspace defect (stage 3) was fixed; it was not in the prototype.
6. Tests: compile-only substitutions before the stage-2 gate (listed); TrBdf2Test added to the section-5 table, its class name kept; the fluid-suite gates used the exclusion-only init script.
7. Replay determinism is handed over only at the island dispatch, as in the prototype; a module prepare job on the same handle uses the committed interval last handed over.
8. Commit trailer: the system-provided attribution (Claude Opus 5.5) was used instead of the brief's "Claude Fable 5.1".

#### Open (for WP2 onwards)

- WP2: merged certificate kind; the three fixtures and the two horizon tests.
- WP3: liquid sizing (`sizeJunctionHoldups` still uses the velocity limit of the seed state, gas or liquid), a gate through `PhysicalRegistry.apply` for the mint booking, certificate replay of junction inventories.
- WP4: the exact regression (`com.wormzjl.createcheme.fluid.benchmark.FluidSolverRegressionTest`, outside the fluid-suite filters, not run in WP1; its bitwise pins are expected to fail until re-recorded) and the 30 GameTests.
- The main checkout's `documentation/INDEX.md` and this batch's copy there are not updated (no main-checkout edits in this brief).

### 9.2 WP2: REST and STEADY certificates merged

Run 2026-09-26, same worktree and branch, by Claude (Opus 5.5), on 618ea63; committed as `5b708fa` (WIP WP2, not merged, not pushed). Owner decision D7 (plan section 4). Gate logs and XML: `tools/junction-holdup-prototype/run139` to `run141`.

#### Code

- `IslandCertificate`: the `Kind` enum is gone. A certificate carries its per-interval change `drift()` = `Summary.drift()`, and exactly 0 for the identity map (every change, flow, gross transfer, boundary transfer and pump work zero). `issue` is today's rule: identity map -> horizon `Long.MAX_VALUE`, or the recheck period when `recheckSeconds > 0`; otherwise `min(K_max, budget/d, zero crossing)`; the solids-in-transport and filter refusals only past the identity branch; `grossWithoutNet` a refusal. `graphAt` short-cuts on the identity map instead of the kind (same arithmetic). `Saved` loses its kind; `restore` compares the re-issued horizon only.
- `IslandCoordinator`: `Advance {SOLVED, REPLAYED}` (RESTED collapsed into REPLAYED); `Certified(double drift, since, base, horizon, largestFlow, saved)`; one status line, `STEADY: no flow since X s` or `STEADY: replaying Q kg/s since X s`, with `, next check at Y s` when the horizon is finite; `REVALIDATING: certificate horizon at ...`; `WAITING: saved certificate discarded: ...`. The confirm rule of `qualify` (one interval for an identity map, two otherwise) is unchanged: it keys on the interval, not a kind.
- `FluidRuntimeDiagnostics`: `restedTicks` removed; `replayedTicks` counts every replayed tick.
- **Checkpoint format 5** (`FluidCheckpointCodec.VERSION` 4 -> 5, envelope digest label `createcheme-fluid-checkpoint-5`): the island index loses its `Kind` byte column (the `Base` column already distinguishes awake from certified); `IndexRow` loses `kind`; `FluidCheckpointStore` writes rows without it. Format 4 and older are refused with the fresh-world instruction, as 3 and older were. No migration, no legacy test (AGENTS.md); the format tests exercise format 5 only.
- `FluidDeviceScreen.pipeStatus`: `STEADY: no flow` maps to `no_flow`, any other `STEADY:` to flowing or velocity-limited (the `RESTING:` branch is gone). Not checked in the dev client (WP4's GUI check).
- Config comments of `restDetection`, the budget, `K_max` and `restRecheckSeconds` reworded (keys unchanged).
- GameTest sources updated to compile against the merged kind (`drift()==0` for the former REST checks, the new status text, REPLAYED; the benchmark counts identity and moving certificates from drift and flow and drops its `restedIntervals` field). **Compiled, not run** (WP4).

#### Changed assertions (all intended, D7; no defect found)

| Test | Old | New | Measured |
|---|---|---|---|
| IslandCertificateTest.aSettledClosedPair... (**failing in WP1**) | kind STEADY | drift 0 | certified at tick 300, drift 0.0, horizon MAX; three-interval-window half unchanged |
| IslandCertificateTest.deadHeadedLines... | kind REST; status `RESTING: no flow since`; advance RESTED | drift 0; `STEADY: no flow since`; REPLAYED | certified at 200, drift 0.0, horizon MAX |
| IslandCertificateTest.generatorToVoid... | kind STEADY | horizon finite | drift 0.0 (no finite node), horizon 1728200 (= WP1's), status `STEADY: replaying` |
| IslandCertificateTest.grossFlowWithAZeroAverageIsNotRest | issue(still) kind REST | drift 0 and horizon MAX | pass |
| IslandCertificateTest.theHorizonKeepsReplay... | kind STEADY | drift > 0 | horizon assertions unchanged, pass |
| IslandCertificateTest.aSettledBenchmarkClosedLadder... | kind STEADY | horizon finite | drift 1.64e-9, horizon 61300 (= WP1's) |
| FluidCheckpointFormatTest.clocksMaterialFences... (**failing in WP1**) | `FluidFormat` 4; index `Kind` byte = STEADY (2); loaded kind equal | `FluidFormat` 5; no `Kind` column; island 2's certificate drift 0; loaded drift equal | pass |
| FluidCheckpointFormatTest.aRestoredCertificate... (**failing in WP1**) | "the closed pair moved since its base" (some inventory differs) | the closed pair's inventory equals its base (identity, drift 0) | pass; the restored run bit for bit over 5000 ticks as before |
| FluidCheckpointFormatTest.everyInvalidCertificateFieldIsRefused | cases "lacks its certificate record" (Kind = 0) and "unknown certificate kind" (Kind = 9) | both cases removed: the column no longer exists (the unit-level "lacks its certificate record" check stays in the codec, but no index edit reaches it) | the other 16 cases pass |
| FluidCheckpointFormatTest.restDetectionOff... | status `WAITING: saved <KIND> certificate discarded` | `WAITING: saved certificate discarded` | pass |
| FluidCheckpointFormatTest.aCertifiedPayloadIsCopied... (**horizon test**) | policy `(1e-9, 1e-6, 5, 2, recheck 0)`; fourth save encodes 2, reuses 1; totals 6/6 | recheck 25 s; fourth save encodes 3, reuses 0; totals 7/5 | 0.02 s (run 140); the dead-headed line reaches its 25 s horizon with the pair and revalidates too (run 139: 3 against 2 expected) |
| FluidCheckpointFormatTest.aThousandCertifiedIslands... | kind REST | drift 0 | horizon 1400 as before |
| PresentationBucketTest.aPresentationReadStopsShortOfAWake... (**horizon test**) | policy `(1e-9, 1e-6, 3, 2, recheck 0)` | recheck 15 s | 0.004 s (run 140); every assertion unchanged |
| PresentationBucketTest.presentationCostsNothing... | kind REST; `RESTING: no flow since` | drift 0; `STEADY: no flow since` | pass |
| IslandSchedulerTest.certifiedIslandsCostNothing... | kind REST | drift 0 | pass |
| CausalModuleCoordinatorTest.certifiedIslandsReproduce... | `restedTicks > 0` | `replayedTicks > 0` (the same ticks, one counter) | pass, outcome bitwise equal in all three scenarios |
| FluidCheckpointStoreTest (round trip, relabel fixture) | compares and rebuilds `kind` | compares and rebuilds `drift` | pass |

`exclude.init.gradle` is no longer needed by any gate.

#### Gates

| Run | Command (first line of the log) | Result |
|---|---|---|
| 139 | `./gradlew.bat --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.runtime.fluid.IslandCertificateTest --tests ...FluidCheckpointFormatTest --tests ...PresentationBucketTest --tests ...CausalModuleCoordinatorTest --tests ...FluidCheckpointStoreTest --tests ...FluidCheckpointCodecTest --tests ...IslandSchedulerTest --tests ...IslandCertificateDomainTest --console=plain --continue` (full package names in the log) | 74/75 (the payload-copy count above) |
| 140 | the same, after the adjustment | **75/75** |
| 141 | `./gradlew.bat --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` (run 137's line without the init script and the exclusion) | **402/402** in 93 classes, 0 skipped; MIXED_GAS_COST 5 s 264 ms, 286 Newton solves, 134.2 MB |

#### Deviations

1. The `Kind` byte column is removed rather than kept with a single value; the `Base` column carries awake/certified. Two corruption cases of the invalid-field test went with it.
2. The status keeps the `STEADY:` prefix for the one kind and names "no flow" from the replayed flow, so the GUI still shows "no flow" for a resting island; `RESTING:` no longer exists.
3. The payload-copy test's finite horizon applies to both of its certified islands (the policy is per rig), so its fourth-save counts changed. The alternative (swap the closed pair for the generator-to-void fixture, whose horizon is K_max) was not taken because the brief asks for a finite horizon in the rig.
4. D7's wording "horizon infinite at drift 0" is implemented as plan section 4 states it: infinite for the identity map (everything exactly zero). A generator-to-void island has d = 0 (no finite node) but flows, and keeps its K_max horizon, as before.

### 9.3 WP3: junction holdup runtime completeness

Run 2026-09-26, same worktree and branch, by Claude (Opus 5.5), on the WP2 commit; committed as `daa1ccc` (WIP WP3, not merged, not pushed). Gate logs and XML: `tools/junction-holdup-prototype/run142` to `run148`. No existing assertion was changed in WP3; four tests were added.

#### (a) Certificate replay carries the junction inventories

- *Defect (confirmed, fixed).* `IslandCertificate.Summary` recorded rows only for RESERVOIR nodes, so a junction's owned holdup (mass pinned, composition and energy field moving) was neither in the evidence nor in the replay: `graphAt` returned the base junction inventory for the whole window. The pin keeps the junction's mass, so the island's mass stayed right, but its composition and energy did not follow the recorded interval, and the finite stock no longer changed by exactly what crossed the boundaries (the ledger gap is f x the junction's per-interval change).
- *Fix.* A junction owns stock: its row is recorded like a vessel's (moles, energy field, solids, phases, temperature and pressure change), so it enters the identity test, the drift d, the zero-crossing limit, the stationarity measures and the replay `base + f * delta`. The quiet-pipe reference mass keeps standing a junction for the island's smallest *vessel* inventory (the small holdup passes the flow on), so the flow test is not tightened by m_J. Recorded as recommended decision D12 (reversible).
- *Gate test* `IslandCertificateTest.aCertifiedIslandReplaysItsJunctionHoldupsExactly`: generator (60 % methane) - pipe - 10 m3 tank (nitrogen) - a branching pipe (a compiled junction) - two voids, certified under a loose policy `(1e-6, 1e-3, K_max 5)` while the flush still moves the junction's composition (run 143/148: certified at tick 10700, drift 4.46e-6, one junction, 1.22e-6 mol per interval). Materialised 2.5 intervals into the window, every junction equals `base + f * delta` bit for bit (moles and energy field), and vessels plus junction holdups changed by exactly the replayed boundary transfers (1e-12 relative). With the change reverted (run 147) the test fails: junction moles 0.29748137807410596 (the base) against 0.2974844358796288.
- No other certificate gate moved (run 148: IslandCertificate 23/23, FluidCheckpointFormat 9/9, CausalModuleCoordinator 12/12 bitwise, PresentationBucket 10/10).

#### (b) The mint booking through `PhysicalRegistry.apply`

- *Gate test* `PhysicalRegistryTest.junctionHoldupsAreBookedAsConstructedAndRemintedAtATopologyEdit`, on the registry path with a real coordinator and a synchronous worker: a methane generator at 150 kPa fills two nitrogen tanks at 1 atm through a line of three pipes with a branch off the middle pipe to the second tank (one compiled junction). Checks:
  1. every junction owns `m_J = 0.05 s x max over its connections of rho_seed A_min v_limit(seed)` (run 146: 0.00956 kg = 0.05 x 0.1912 kg/s);
  2. the placement books both tanks and every minted junction as constructed and nothing as destroyed (`MaterialTotal.plus`, moles and energy field + m g z, to 1e-12 of the cumulative totals);
  3. after ten 5 s intervals the junction has taken up methane (fraction 0.9999999965); a stub pipe placed off the first pipe recompiles the island; the ledger books the replaced island's **solved** junction stock as destroyed and the recompile's freshly minted junctions (and no tank) as constructed;
  4. every fresh junction holds its seed state's composition.
- *Fixture findings (runs 142-145, not defects of the product):* a series of pipe blocks compiles to one multi-section connection, so a straight line has no junction; a dead-end stub is not a junction either; a RESERVOIR is always initialised with a nitrogen charge whatever composition its spec names (`FluidDeviceSpec.initialize`), so a methane source has to be a generator. (The last point also means that `IslandCertificateTest`'s `throughTank(feed, charge, ...)` fixtures run with nitrogen tanks whatever `charge` says; their assertions hold either way, and they are not changed here.)
- *Accepted behaviour (plan WP3, documented, D13):* a topology edit discards every junction of a replaced island and mints the recompile's junctions afresh from their seed states. The solved stock is not lost: it is booked as destroyed, the new mint as constructed, both in the topology ledger. The consequence is that a junction's composition (and, if the seed differs, its size) resets at an edit; the reset is at most one fitting's m_J per junction and is visible only as a short mixing transient after the edit.

#### (c) SolidRuntimeTest and FullTankSolidsEventTest

Green after WP1 and unchanged by WP2/WP3: SolidRuntimeTest **8/8** (0.16 s), FullTankSolidsEventTest **4/4** (0.22 s) in runs 142 and 148, including the two 7.10 (h) defect cases (`physicalPumpFilterAndNitrogenReceiverStartTogether`, the owned-holdup stall; `theFilterCapturesOnceTheDrainedTankLetsTheLineFlow`, the pin at the 9.78 kg water junction), which fail no more on the backward-Euler basis.

#### (d) Liquid holdup sizing: measured, not changed (options for the owner)

*Measurement* (`LiquidJunctionTransientTest`, a gate test for convergence and the ledger; the lag is printed, not asserted). Two water inlets, 300 K at 150 kPa and 350 K at 130 kPa, feed a compiled junction with two void outlets at 1 atm, 1 m pipes, for 20 s, at 0.1 s intervals and at the 5 s cycle. Both inlets water, so the composition lag is measured on the same junction rows through the specific enthalpy: `mismatch = (h_J - h_mix)/(h_350K - h_300K)`, h_mix the inflow mixture at the interval's end. All eight runs converge and close the ledger at roundoff (runs 142, 148).

| inlets, bore | m_J | Q through the junction at 1 s | m_J/Q | tau fitted from the 0.1 s trajectory | mismatch after each 5 s slice | Newton solves, 20 s at 5 s |
|---|---|---|---|---|---|---|
| generators, 50 mm | 9.774 kg | 54.0 kg/s | 0.181 s | 0.1809 s | -3.9e-4, -4.1e-7, -4.3e-10, 5.5e-10 | 13 |
| generators, 20 mm | 1.564 kg | 4.83 kg/s | 0.324 s | 0.3239 s | -1.7e-3, -8.2e-6, -3.9e-8, 9.2e-9 | 13 |
| liquid-full 1 m3 tanks, 50 mm | 9.774 kg | 0.015 kg/s (5 s average of the first slice); 2.7e-11 kg/s at 1 s at 0.1 s | - | - (no flow) | -0.36, -0.47, -0.47, -0.47 | 17 |
| liquid-full 1 m3 tanks, 20 mm | 1.564 kg | as above; 3.1e-12 kg/s | - | - | -0.34, -0.081, -0.081, -0.081 | 17 |

- The lag is m_J/Q exactly (fitted tau equals m_J/Q to 4 digits). For gases the design intent was 14-30 ms (7.10 (f)); for water at these flows it is 0.18-0.32 s, 6-23 times longer, and it grows as 1/Q: 9.8 s at 1 kg/s through a 50 mm fitting. At the 5 s cycle the mismatch left after the first slice is 0.04-0.17 % of the inlet difference and vanishes by the second.
- Liquid-full tanks lose their pressure as soon as a few grams leave (liquid compressibility 1e-9 /Pa), so the flow stops within the first slice; the junction then keeps 9.8 kg (1.6 kg) of water at its mixed-seed enthalpy for good. Nothing moves, so no transport result depends on it; a junction's presented temperature would show that stale value.
- Newton cost at 5 s is 13-17 solves per 20 s; nothing is stiff about the kg-scale holdup (the pin defect of 7.10 (h) does not recur under backward Euler).

*Does the model expose a liquid velocity limit?* No. `FluidThermodynamics.velocityLimit(state) = min(maximumVelocity, isothermalAcousticBound(state))`: one configured maximum (100 m/s, `maximumVelocityMetresPerSecond`) for every phase, and the acoustic bound, which for water is about 1000 m/s and never binds below 100 m/s. `SolidMobility`/`SlurryTransport` carry only *minimum* (deposition, suspension) velocities; `PassiveStepSolver` and `PhaseLayout` read `velocityLimit`. So sizing with "the liquid velocity limit" would need a number that neither physics nor a recorded rule gives, and under the owner's rule of 2026-09-26 it is presented as options, not implemented:

| Option | What changes | Effect (from the measurement) | Cost / risk |
|---|---|---|---|
| **A. Leave as is** (recommended) | nothing | liquid lag 0.18-0.32 s at the fixture flows, m_J/Q in general; kg-scale water holdups (9.8 kg at 50 mm, 1.6 kg at 20 mm) booked exactly in the ledger; every gate green | the lag grows at small flows (seconds below ~5 kg/s at 50 mm); a stale junction temperature where liquid stops |
| B. A configured liquid velocity for the sizing only | `sizeJunctionHoldups` uses `min(v_liquid, velocityLimit)` for a seed with liquid; a new config value the owner sets | m_J and the lag scale by v_liquid/100 (e.g. 3 m/s: 0.29 kg, 5-10 ms at the fixture flows) | a new number to choose; the stiffness margin of D2 (tau x the *cap* flow) no longer holds for a liquid fitting, since the cap itself stays at 100 m/s: a liquid step at full cap flow would flush m_J in 0.05 x v_liquid/100 s |
| C. A configured liquid velocity for the cap and the sizing | the hydraulic cap of liquid-filled connections also uses v_liquid | as B, and D2's margin holds again | a model change well beyond sizing (every liquid line's maximum flow drops); needs its own gate campaign |
| D. tau against the liquid's acoustic bound | size with `isothermalAcousticBound` instead of `min(100 m/s, acoustic)` | m_J about 10 x larger for water (about 100 kg at 50 mm), lag 10 x longer | the opposite of what 7.10 asked; listed because the brief names it |
| E. Physical fitting holdup | m_J = rho x the fitting's own pipe volume (a 1 m block of 50 mm holds 1.96 L: 1.96 kg of water, 2.4 g of gas at 1.2 kg/m3) | at the 100 m/s limit this is tau = 0.01 s for every phase: lag 5 x shorter than A for liquids and gases alike | not phase-specific at all; it undercuts D2's 2.4 x margin at 0.1 s steps for gases, so the gas gates and the 32/12 probe matrix would have to be re-run |

A pending row (D14) is in DECISION_LOG.md. Nothing was implemented; the remaining WP3 items did not depend on it.

#### Gates

| Run | Command (first line of the log) | Result |
|---|---|---|
| 142 | `./gradlew.bat --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.runtime.fluid.IslandCertificateTest --tests ...PhysicalRegistryTest --tests ...LiquidJunctionTransientTest --tests ...SolidRuntimeTest --tests ...FullTankSolidsEventTest --console=plain --continue` | 44/46 (the two new tests' fixtures, above) |
| 143-146 | the new tests alone (full names in the logs) | fixture iterations, above; 146 passes |
| 147 | the junction-replay test with the (a) change reverted | fails as intended |
| 148 | `./gradlew.bat --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` | **406/406** in 94 classes, 0 skipped; MIXED_GAS_COST 5 s 273 ms, 286 Newton solves, 134.2 MB (WP1 run 137: 314 ms, 286, 134.1) |

The 38 adjacent tests, the topology tests (PhysicalFluidTopology, PhysicalRegistry, WorldTopologyLedger) and the mixed-gas gate tests are all inside run 148's filters. GameTests and the exact regression are WP4's.

#### Deviations from the plan and the brief

1. Plan WP3's "liquid sizing uses a liquid velocity limit" (plan D8) is not implemented: the model has none; options above, pending D14.
2. The junction rows enter the certificate's evidence (drift, zero crossing, stationarity, identity test), not only the replay; replay alone would extrapolate a junction without a zero-crossing bound. D12.
3. The (a) test certifies under a loose policy so that the junction still moves at certification; at the default tolerance a junction is stationary to roundoff when an island certifies and the replay gap is roundoff too.
4. The liquid transient measures the lag on enthalpy (both inlets water), the same junction rows; a liquid composition pair (a water-hydrocarbon mixture) would be two-phase in this package.
5. `LiquidJunctionTransientTest` stays in `src/test` as a gate (convergence and ledger at 0.1 s and 5 s for liquid junctions, which no other gate covers); it prints but does not assert the lag.

#### Open (for WP4 onwards)

- D14: the owner's choice of liquid sizing.
- WP4: the exact regression re-record, the 30 GameTests (their sources were updated for the merged certificate in WP2 and compile), the in-game GUI check (including the `STEADY: no flow` status mapping of WP2).
- 7.10 (c)/(i) item 7, remint on phase change (a gas-minted fitting filled with liquid keeps its gas m_J): unchanged, not in WP3's scope.
- The main checkout's `documentation/INDEX.md` and the batch's copy there are not updated (no main-checkout edits in this brief).

### 9.4 WP4: exact regression re-recorded, GameTests, in-game check

Run 2026-09-26, same worktree and branch, by Claude (Opus 5.5), on `daa1ccc`. Logs and XML: `tools/junction-holdup-prototype/run149` to `run155`; old and new references and the deviation table in `tools/junction-holdup-prototype/wp4-regression/`; screenshots and notes in `tools/junction-holdup-prototype/wp4-ingame/`. `JAVA_HOME=C:/Program Files/Java/jdk-21.0.11`, `JAVA_OPTS=-Xshare:off`. One Gradle invocation at a time (only the idle daemon was running before each run), and no dev client during the Gradle runs.

#### (a) Exact regression (`fluidSolverRegression`, plan section 5, D1)

Procedure as recorded in `2026-09-18-solid-phase-fluid-system/FLUID_SOLVER_REGRESSION_RERECORD.md` and `2026-09-24-coolprop-low-temperature/P3_PILOT_ENGINE_REVIEW.md` section 3: a declared run against the old reference, a capture with `-PfluidRegressionCapture=true`, then exact mode against the new reference. The capture path worked as documented. The three island fixtures (`quiet-11312`, `quiet-11324`, `cold-11312`) are skipped as before (no `build/probe` snapshot); their stale `154007d` references are untouched.

| Run | Command (first line of the log) | Result |
|---|---|---|
| 149 | `./gradlew.bat --no-configuration-cache fluidSolverRegression --console=plain` (declared, old reference `3c84036`) | FAILED as expected: 40 regression differences; substeps 37/1 -> 3/0 |
| 150 | `... fluidSolverRegression -PfluidRegressionCapture=true` | `CAPTURED src\test\resources\fluid\regression\chain-100.json`; BUILD SUCCESSFUL |
| 151 | `... fluidSolverRegression -PfluidRegressionMode=exact` | **0.000e+00** in state/moles, temperature, phase fraction and flow; substeps equal; BUILD SUCCESSFUL |
| 152 | the same again | **0.000e+00** on all four; bitwise on re-run |

Reference file SHA-256: old (`3c84036`) `d5db21c0087ca9f8ffb3522afe4c9e17d2a2daf6b8de800fc4f63048f75da0f9`, new `7cb85a9bbe9fc9e48fc49a7968b49a7df905f13b2470d33895228747402046fc`. The new trajectory takes three steps (1 s, 2 s, 2 s), all accepted, with `be-state` dominant (state-change errors 1.28e-3, 1.97e-3 and 1.55e-3 against the 0.05 cap): 4 Newton solves, 29 iterations and 4 Jacobian builds.

Deviation of the new reference against the old one (100 nodes, 99 pipes, one 5 s interval), computed with `D:/Minecraft/Modding/1.21/CreateChemE/tools/wp11-closeout/chain-deviation.js`:

| Quantity | max | mean | where | declared gate |
|---|---|---|---|---|
| pressure (relative) | 1.792e-4 | 9.298e-5 | node 10 | 1e-6: exceeded |
| temperature (K) | 2.466e-3 | 1.283e-3 | node 10 | 1e-4 K: exceeded |
| mass (relative) | 2.574e-4 | 1.337e-4 | node 10 | 1e-6: exceeded |
| component moles (relative, 2,100 entries) | 2.574e-4 | 1.337e-4 | node 10, moles[3] | 1e-6: exceeded |
| vessel volume (relative) | 1.651e-10 | 4.761e-12 | node 10 | 1e-6 |
| liquid / vapour / water volume fraction (absolute) | 1.138e-5 / 1.152e-5 / 1.461e-7 | 5.9e-6 / 5.9e-6 / 7.5e-8 | node 10 | 1e-6: liquid and vapour exceeded |
| pipe flow (relative to max(q, floor)) | 1.428e-1 | 4.896e-2 | pipe 4 (4.92e-4 -> 4.22e-4 kg/s) | 1e-3: exceeded |
| pipe flow (kg/s, absolute) | 2.395e-3 | 1.624e-3 | pipe 87 (-0.04938 -> -0.04698 kg/s) | - |
| substeps accepted / rejected | 37/1 -> 3/0 | | | equal: no |

*Classification: intended (D1, D4).* The chain is a closed transient in which pressures equalise. Three backward-Euler steps (1, 2 and 2 s) replace 38 TR-BDF2 steps. First-order damping lowers the 5 s-average flows (the largest pipe by 4.9 %), so each node's mass ends about 1e-4 behind the old trajectory. Total mass is equal to the last printed digit (3849.5460962498273 kg in both files) and total moles agree to 3e-16 relative, so nothing is created or lost. No solver code changed in WP4.

#### (b) GameTests

The gate is the one run at merge by the last batch that ran GameTests (`2026-09-23-fluid-followups/cleanup-logs/gates.sh`: `runFluidGameTestServer -PfluidGameTestRunId=<fresh id>`). `columnGameTestServer` is not in that gate, and the 0.5.0 merge ran no GameTests, so it was not run.

| Run | Command | Result |
|---|---|---|
| 153 | `./gradlew.bat --no-configuration-cache runGameTestServer --console=plain` | 0 tests: the main source set has no GameTests (`IllegalArgumentException: No test functions were given!`, server not started; Gradle exit 0). Nothing to classify |
| 154 | `./gradlew.bat --no-configuration-cache runFluidGameTestServer -PfluidGameTestRunId=wp4-be-20260926 --console=plain` (fresh world `run/fluid-gametest-wp4-be-20260926`, scheduler verify on) | **All 30 required tests passed**, 28 batches, 10 s; BUILD SUCCESSFUL in 23 s |

There is no failure to classify. The only fluid WARN lines are the three `HELD: property data changed` warnings from the property-reload test, the same three as in the 0.4.0 gate log (the committed ticks differ because the batch order differs). The WP2 GameTest source changes (drift, `STEADY:` status, REPLAYED) hold.

#### (c) In-game check (MCP bridge), partial

Details and screenshots: `tools/junction-holdup-prototype/wp4-ingame/NOTES.md`. Fresh creative superflat world containing a 4-port junction island (two generators and two voids at the placement defaults: water, 25 C, 101325 Pa) and a closed nitrogen reservoir pair.

| Sub-check | Evidence | Observed | Verdict |
|---|---|---|---|
| (i) merged status, no flow | `03-closed-pair-tank.png` | reservoir header `STEADY: no flow since 77.6 s` | PASS for the status text. The pipe's presented `no_flow` line lies below the 854x480 capture and was not read |
| (i) merged status, replaying | generator header (screen open at 72-161 s) | `STEADY: replaying 4.441e-16 kg/s since 72.1 s, next check at 86472.1 s` | the replaying form appears |
| (ii) mixed contents, settling bulk speed | - | not built (see below) | NOT DONE |
| (iii) no error or hold | `client-latest.log` | `status=HELD` count 0, no fluid error | PASS for the islands built |
| junction presentation | `04-water-junction-pipe.png` | `Junction · 4 connections`, Water 100 %, flow 2.66e-13 kmol/h, bulk speed 2.27e-16 m/s | presented |

*Why the mixed-gas island was not built.* Reservoirs always hold a nitrogen charge and generators place as water. A second gas, and a generator pressure above the voids, both need text entry (composition search, amount, pressure). On the `runClient` lane the bridge cannot type into a container `EditBox`, and the skill's workaround (`robot.ps1`) sends real desktop keystrokes. The first `robot.ps1 paste` reported that Minecraft was not the foreground window, and a desktop capture then showed a browser window with a payment-method dialog open behind the client. The real-input path was stopped at once and not used again (the paste may have reached that browser window). The remaining observations used only the bridge's in-process calls. The alternative, the `runMcpClient` lane, whose compat mixins let the bridge's own `type_text` reach the focused widget in-process, was not tried in this session.

*Finding (presentation, not a defect of the physics).* With two water generators and two voids all at 101325 Pa, the island carries a roundoff flow (4.4e-16 kg/s for the island, 2.27e-16 m/s in the junction pipe) instead of exactly zero. The certificate is therefore not the identity map, and the status reads `STEADY: replaying 4.441e-16 kg/s` with a K_max horizon rather than `STEADY: no flow`. The closed reservoir pair gives an exact zero and `no flow`. The options for the owner are in DECISION_LOG D16. None is implemented, because a threshold is an assumption that neither physics nor a recorded rule forces.

#### Deviations

1. `runGameTestServer` has no tests to run (main source set); recorded, not a gate.
2. `columnGameTestServer` was not run (it is not in the recorded gate).
3. The in-game check is partial: sub-check (ii) and the pipe-status presentation were not observed (see above). The brief's two reservoirs would have had to be generators in any case, because a reservoir always holds nitrogen (9.3 (b)).
4. The declared (non-exact) mode was not re-run against the new reference; exact mode implies it.

#### Open (for WP5 and the owner)

- D16: a roundoff flow between equal-pressure boundaries is presented as `replaying`.
- The in-game mixed-gas junction check (ii): preferably on the `runMcpClient` lane (in-process typing), or done by hand by the owner.
- Stale island references `quiet-11312`, `quiet-11324`, `cold-11312` (unchanged since 2026-09-22).

### 9.5 WP5: cleanup and final gates

Run 2026-09-26, same worktree and branch, by Claude (Opus 5.5), on `c7a7dc2`; committed as `b5ed407` (`WIP WP5: prototype cleanup, changelog entry and final gates`; not merged, not pushed; `mod_version` not bumped). Logs and XML: `tools/junction-holdup-prototype/run156` to `run159`; the removal record is the tool README's section "WP5 of the productization". `JAVA_HOME=C:/Program Files/Java/jdk-21.0.11`, `JAVA_OPTS=-Xshare:off`; one Gradle invocation at a time (only an idle daemon was running), no dev client.

#### (a) Prototype residue (tooling rule)

Checked in `src/main`, `src/test`, `src/fluidGameTest`, `src/mcpCompat`, `build.gradle` and `gradle.properties`: no `System.getProperty("junction.` or `createcheme.junctionHoldup` read, no `*Trace` print and no `System.out`/`System.err` in the fluid solver and runtime packages, no `mintLine`, no `JUNCTION_HOLDUP`, no `Double.MIN_VALUE` flag in the fluid code (the remaining `Double.MIN_VALUE` uses are bounds in the column solver and the PR kernel). `git diff 9674bf1 HEAD -- build.gradle gradle.properties` is empty: no `-P` switch in a tracked file served the prototype (its switches were forwarded only by the git-ignored `holdup.init.gradle`). `holdup.init.gradle` and `exclude.init.gradle` stay in the tool folder; no gate uses either since run 141. Nothing to remove.

#### (b) Dead code and stale text the basis change left

| Item | Action |
|---|---|
| `PassiveIntervalSolver.Settings.relativeTolerance` (unread since `640d87b`) | **removed** with its constructor argument and its validation: `Settings(initialStep, maximumStep, maximumAttempts)`, `defaults()` = (1, 20, 1024). Call sites: `ProcessSolveServices` (the soft-budget approximate settings) and 14 in 9 test classes (the argument dropped, nothing else). The record's doc says why there is no tolerance |
| `SolverDiagnostics` counters | all 56 are still recorded by main code (checked one by one; `stepAttempts` and `stepAttemptsAccepted` through `attempt(...)`); the companion and endpoint-rate counters went in WP1 stage 2. Nothing removed |
| Stale comments and javadoc | `ConservativeTransport` ("stage graphs" to "solved graphs"), `SolidEventIntegrator.Transition` ("stage solve" to "step solve"); text still naming format 4 as the current checkpoint format: `FluidUnitIO` ("format 4 onwards"), `FluidCheckpointCodecTest`, `FluidCheckpointFormatTest`, `FluidCheckpointStoreTest` (also "REST and STEADY certificates" to no-flow and moving certificates), the GameTest sources `FluidCheckpointGameTests` (class doc and one assertion *message*; the condition is unchanged), `FluidHarnessGameTests` (comment), `FluidServerBenchmark` (report note); the `integrator` field of `FluidNetworkBenchmarkTest`'s report said "TR-BDF2 with filtered embedded 2(3) estimator; valve intervals use step doubling" and now names backward Euler under the state-change controller. Test comments that compare a new bound with the former TR-BDF2 bound are history and stay |
| `TrBdf2Test` | **renamed** `BackwardEulerStepTest` (`git mv`; same five tests, same assertions); class doc added, the first test's doc reduced to what it tests |
| Left, deliberately | the property-revision label `fluid-trbdf2-r1` in `ApproximationAnchor` (and the checkpoint fixtures carrying it): an identity string inside the network package's thermodynamic revision, not a description; renaming it changes every saved revision and two test fixtures for no functional gain (format 5 already refuses older worlds). `PassiveIntervalSolver`'s class doc keeps its one-sentence comparison with the TR-BDF2 it replaced |

Main sources: 5 files, +9/-8 lines. Test and GameTest sources: 20 files (one rename): comments, the dropped argument, and the three deletions of (c).

#### (c) Stale exact-regression island references

`2026-09-18-solid-phase-fluid-system/FLUID_SOLVER_REGRESSION_RERECORD.md` (section "What could not be re-recorded") recorded the follow-up: the `154007d` references `quiet-11312.json`, `quiet-11324.json`, `cold-11312.json` (22-wide basis) should be deleted so that a future snapshot produces the explicit missing-reference failure; its `git rm` line was left for the maintainer. **Done** (`git rm` of the three files). The skip path of `FluidSolverRegressionTest` (fixtures skipped when no `build/probe` snapshot exists) is **kept**: the same document relies on it (the task passes without a snapshot) and does not ask for its removal. Run 157 prints the three SKIP lines as before. Re-recording the island fixtures still needs a current-basis stress world holding islands 11312 and 11324.

#### (d) Gates after the cleanup (identical to before)

| Run | Command (first line of the log) | Result | Before |
|---|---|---|---|
| 156 | `./gradlew.bat --no-configuration-cache --no-build-cache test --rerun --tests com.wormzjl.createcheme.science.fluid.* --tests com.wormzjl.createcheme.runtime.fluid.* --console=plain --continue` | **406/406** in 94 classes, 0 skipped; the same 406 test names as run 148 (TrBdf2Test read as BackwardEulerStepTest); every MIXED_GAS_STATIC/TRANSIENT and LIQUID_JUNCTION line equal to run 148's character for character apart from wall ms and per-case allocated bytes (ledger errors and Newton solves bitwise); MIXED_GAS_COST 290 ms, 286 Newton solves, 134.2 MB | run 148: 406/406; 273 ms, 286, 134.2 MB |
| 157 | `./gradlew.bat --no-configuration-cache fluidSolverRegression -PfluidRegressionMode=exact --console=plain` | **0.000e+00** on state/moles, temperature, phase fraction and flow; 3 accepted / 0 rejected, 4 Newton solves, 29 iterations, attempt log equal to run 151's | runs 151/152: 0.000e+00 |
| 158 | `./gradlew.bat --no-configuration-cache --no-build-cache test --rerun --tests *PassiveStepSolverTest --tests *PumpJunctionStartupTest --tests *FilterBlockLineIslandTest --tests *DeadHeadedLineIslandTest --tests *NetworkRegimeTest --tests *PhysicalFluidTopologyTest --tests *PipePresentationTest --console=plain --continue` | **38/38** | run 136: 38/38 (and inside run 148) |
| 159 | `./gradlew.bat --no-configuration-cache --no-build-cache test --rerun --tests *MixedGasJunctionStaticTest --tests *MixedGasJunctionTransientTest --tests *LiquidJunctionTransientTest --console=plain --continue` | **5/5**; MIXED_GAS_COST 5 s: **261 ms, 286 Newton solves, 132.4 MB** (SolverDiagnostics on, switched by the test) | run 138 (the two mixed-gas tests alone): 347 ms, 286, 132.7 MB; in the suite, runs 141/148/156: 264/273/290 ms, 286, 134.2 MB |

No count or conservation number differs. Wall time varies by tens of ms between runs (noise). The allocation is 134.2 MB in the suite JVM in runs 141, 148 and 156, and 132.4-132.7 MB when the junction tests run alone (JIT and class-loading context), with the same Newton path per case. No assertion was changed.

#### (e) Tool folders holding the batch's detached material

- `tools/junction-holdup-prototype/` (in this worktree, git-ignored; copied to the main checkout's `tools/` at merge): the prototype patches against 9674bf1, the probes that became `MixedGasJunctionStaticTest` and `MixedGasJunctionTransientTest` at `618ea63`, `TrBdf2StepSolver.java` as of 9674bf1 (identical to `git show 9674bf1:src/main/java/com/wormzjl/createcheme/science/fluid/network/TrBdf2StepSolver.java`) and as of 3dba4b0, `reattach-trbdf2-against-640d87b.patch`, the init scripts, the analysis scripts and runs 1-159 with their command lines. The README's last section lists every removal commit and how to re-attach it.
- `tools/pipe-junction-probe/` (main checkout, from the Codex investigation of 2026-09-24/25): the mixed-feed startup and finite-tank reproductions, the Newton trace and the candidate initializer patches, including `candidate-balanced-seed.patch`, which the prototype and the basis contain.

#### (f) Open owner decisions

- **D14** liquid junction holdup sizing (options A-E of 9.3 (d); A, leave as is, recommended). Nothing implemented.
- **D16** the roundoff flow between equal-pressure boundaries, presented as `STEADY: replaying 4.441e-16 kg/s` (9.4 (c)). Nothing implemented.
- The in-game mixed-gas junction check (9.4 (c) sub-check (ii), and the pipe's presented `no_flow` line) is still partial: to be done on the `runMcpClient` lane (in-process typing through the bridge) or by the owner by hand.

#### (g) What the merge needs

1. `CHANGELOG.md`: this batch's `[Unreleased]` line (under `### Changed`) moves under a new heading `## [0.6.0] - <merge date>`; its "WP5 cleanup" reference can be replaced by `b5ed407`.
2. `gradle.properties`: `mod_version` 0.5.0 to **0.6.0** (minor: solver basis change, checkpoint format 5).
3. Main checkout indexes (git-ignored): the `documentation/INDEX.md` row of `2026-09-24-mixed-gas-junction` becomes "Implemented <merge date>", and a `tools/INDEX.md` row for `junction-holdup-prototype/` is added; both texts are in `documentation/2026-09-24-mixed-gas-junction/INDEX_ROWS.md`. The batch folder (plan, decision log, this review, INDEX_ROWS.md) and `tools/junction-holdup-prototype/` are copied from this worktree to the main checkout.
4. A fresh world for play: checkpoint format 5 refuses every older fluid world.

### 9.6 Merge

Merged to main 2026-09-26 on the owner's instruction: main eae658a (culling fix) merged into the branch as 6296e21, 0.6.0 commit c32acac (CHANGELOG heading, mod_version 0.5.0 -> 0.6.0), fluid suites run 160 on the merged tree 406/406 (287 ms / 286 solves / 134.2 MB), main fast-forwarded 9674bf1..eae658a -> c32acac. Not pushed. Batch folder, phase-ports folder and tool folder copied to the main checkout; INDEX rows updated. Owner decisions the same day: D14 keep the 100 m/s cap (supersonic guard), D16 leave the roundoff-flow status, O2 level head option B. The mixed-gas in-game check continues in the background on the in-process MCP lane.

### 9.7 In-game mixed-gas check (MCP lane)

Run 161, 2026-09-26, on `c32acac` (main 0.6.0), no source edits: `JAVA_OPTS=-Xshare:off ./gradlew.bat --no-configuration-cache runMcpClient --offline --console=plain` (game dir `run/mcp-client/`, bridge jar via `run/mcp-client-mods/`, compat mod `createcheme_mcp_compat` loaded). Every step went through the bridge's in-process HTTP calls; no desktop input was used. Details, block positions and the exact bridge calls: `tools/junction-holdup-prototype/wp4-ingame/mcp-lane/NOTES.md`.

*Built.* Fresh creative superflat world. Generator A (0,-59,0) set to nitrogen and generator B (2,-59,-2) set to methane, both 150000 Pa and 76.85 C (350.0 K), through their GUIs. Junction pipe (2,-59,0) with five connections: the two generator inlets, two lines of two pipes to voids at (5,-59,0) and (2,-59,3) (placement default 101325 Pa), and a vertical pipe to a reservoir at (2,-57,0) (placement default: nitrogen, 101325 Pa, 25 C, 1000 L). Reservoirs have no editable controls, so the second gas came from a generator, as the brief allowed; the reservoir is a dead-end fifth port for (iii).

*Text entry.* On this lane the bridge's own calls reach the focused widget: `click` on the field (framebuffer pixels, dispatched as `minecraft_interface_dispatch`), `hotkey` `key.keyboard.left control,key.keyboard.a`, `type_text`; in the component search `type_text {"text":"methane"}` then `press_key enter` selects the first match. Each generator showed `Applied` with pressure `150000.0`, temperature `76.9` and a single composition row. This closes the WP4 gap (text entry on the plain `runClient` lane).

| Sub-check | Evidence (`mcp-lane/`) | Observed (game time) | Verdict |
|---|---|---|---|
| (i) mixed contents, bulk speed | `02-junction-mixed-t281s.png`, `04-junction-connections-t446s.png` | `Junction · 5 connections`; Methane 51.4 % (36.5 kmol/h), Nitrogen 48.6 % (34.5 kmol/h), flow 71.0 kmol/h at 281.2, 386.2 and 446.2 s. Inlets 34.5 + 36.5 = outlets 35.5 + 35.5 kmol/h. Bulk speed (last 5 s) `0.00126-40.0` (281 s), `8.38e-08-100.0` (386 s), `6.60e-08-100.0 m/s` (446 s): the maximum settles at the 100 m/s gas cap (D14) on three connections (94.8 m/s on the nitrogen inlet); the minimum is the dead-end reservoir branch, decaying as it equalises | PASS |
| (ii) island status | `03-reservoir-steady-t401s.png` | reservoir header `FULL` at 306.2, 351.2, 366.2, 381.2 s; `STEADY: replaying 0.2686 kg/s since 385.5 s, next check at 1998.3 s` at 401.2 s, and the same on generator A at 486.2 s. 0.2686 kg/s is the nitrogen inlet (34.5 kmol/h x 28.01 kg/kmol). `HELD` count in the client log 0; no fluid warning or error | PASS |
| (iii) reservoir contents | `03-reservoir-steady-t401s.png` | placed as pure N2 at 101.3 kPa, 25 C (0.0409 kmol); read 146.0-146.1 kPa, 65.9 C, Methane 10.4 % (0.00540 kmol), Nitrogen 89.6 % (0.0464 kmol): it took in about 10.9 mol of the roughly half-and-half junction mixture until its pressure met the junction's, then stayed constant | PASS |

Not read: the pipe's presented status line (`flowing` / `velocity_limited`) lies below the 854x480 capture at GUI scale 2, as in WP4; the island status was read from the reservoir and generator headers instead. The fifth Connections row (reservoir branch) was scrolled off the page.

Pitfalls recorded for the skill: from Git Bash, `bridge.js chat "/tp ..."` is rewritten by MSYS path conversion (set `MSYS_NO_PATHCONV=1`); the bridge has no `wait` tool; on this lane a bridge `click` with no screen open takes the attack path (breaks a block in creative).

The world was saved and quit through the bridge (`pause_game`, Save and Quit, Quit Game); no game JVM remained. Gradle log `tools/junction-holdup-prototype/run161-mcp-client.log`, client log `mcp-lane/client-latest.log`.

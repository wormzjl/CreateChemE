# Flow-dependent tray pressure drop — method review and replacement proposal

**Date:** 2026-09-18. **Reviewer:** Claude (independent research + measurement; no production source changed).
**Reviewed:** `C:/Users/wormz/.codex/worktrees/tray-pressure-study/CreateChemE/experiments/tray-pressure-study/TASK.md`
(Codex study at production revision `c5c8af3`).
**New experiments:** worktree `.claude/worktrees/tray-pressure-study-method-a19e2d` at `4e84f0e` (V3 numerical sources
identical to `c5c8af3`; only the neural-model registry differs). Harness, journals and scripts:
`experiments/tray-pressure-method/` (copied to main `research/tray-pressure-method/`).
**User constraints:** ~10 % error in total column ΔP is acceptable; convergence and calculation time must not be
sacrificed; an extra solve is acceptable but never a full hydraulic iteration; must work on a fresh (cold) solve;
the correlation takes a player-entered column diameter with a default that matches the default throughput.

---

## 1. Verdict

The Codex architecture decision (freeze the pressure profile during a MESH solve; never add pressure unknowns) is
right. The Codex *method* is not usable, for two reasons that are both fixable at almost no cost:

1. It predicts traffic from the inputs with a one-parameter energy shortcut. That predictor is the entire −70 % bias.
   The solver already owns a far better traffic source: **its own accepted MESH state**. Internal traffic is set by
   the energy and material balances and barely moves when the pressure profile changes.
2. It evaluates the hydraulic map at the *old* pressures. The dry term is `ρV·QV² = ṁV·ṅV·R·T / P`, i.e. exactly
   `∝ 1/P`. Evaluating each tray's drop at the pressure being marched (a scalar fixed point per tray, microseconds)
   removes the dominant pressure feedback analytically. After that, **no outer hydraulic iteration is needed**.

**Replacement ("march + one correction"):** solve exactly as today on a nominal profile → march the per-tray
hydraulic profile from the accepted state (0.01–0.07 ms) → if it disagrees with the profile that was used, run **one**
bounded warm correction on the marched per-tray profile → publish. Never iterate further.

Measured (Section 4): total-ΔP error vs the fully converged hydraulic reference **median 0.16 %, max 2.8 %** on a fresh
40-case validation population with the new correlation, and **median 1.1 %, max 10.6 %** on Codex's own 50 cases
(which run up to 2.8 kPa/tray, far beyond flooding). Cost **+6 % median** on a cold solve (27–29 ms, 3 Newton
iterations). Convergence is unchanged *by construction*: the first solve is today's solve, and if the correction ever
fails the first solution is still a valid accepted answer (78/78 corrections succeeded, one needed a half step).
All 7 shipped presets: 0.3–1.4 % error.

**Correlation:** replace the geometry-free study formula by a sieve-tray model — orifice dry drop + Bennett, Agrawal &
Cook (1983) clear-liquid height (published mean absolute error 6.0 % on total tray ΔP) + Fair flooding fraction — with
**column diameter as the one player input, default 8.0 m** (default preset: 565 Pa/tray, 22 kPa total, 69 % of flood).

---

## 2. Findings on the current (Codex) method

| # | Finding | Evidence |
| --- | --- | --- |
| F1 | **Wrong information source.** The input-only predictor uses one calibrated "latent heat" of 159.7 kJ/mol (= 8 MW / nominal reboiler vapor — 4–5× any physical latent heat, because the nominal reboiler makes almost no vapor), one feed flash, fixed molecular masses and a transplanted temperature shape. | All 40 pairs under-predict, median −70 %. Re-using the *same* hydraulic formula on the traffic of the accepted fixed-750 state of the same request gives median 0.9 % (offline, Codex's own journal, no new solves). |
| F2 | **Hydraulic map evaluated at stale pressures.** `G(P̂)` computes `QV` with the solved state's pressures, so a profile that was 50 kPa too low inflates the dry term by up to 40 %. This, not flow feedback, is the measured `G′ ≈ −0.08` median / `−0.59` worst. | Offline on the Codex journal, fixed-750 state → profile: naive map median 3.4 % / p95 29.6 % / worst 40.2 %; pressure-consistent march **0.94 % / 7.5 % / 9.4 %**. |
| F3 | **The "expensive reference" is an artifact.** 0.5 damping needs `log2(ΔP/1 Pa) ≈ 13` steps whatever the physics; 1 Pa closure is 3–4 orders tighter than the 10 % target. | Undamped marched iteration reaches 1 Pa in **1 warm solve (median), 3 max** (validation) and 2 median / 5 max (Codex cases), vs Codex's 10–16. |
| F4 | **Scalar averaging discards shape** and buys nothing: the resolver hook already proves the solver accepts an arbitrary per-tray profile with no Jacobian change. | Both new arms publish per-tray profiles; acceptance audits unchanged. |
| F5 | **Correlation has no geometry, no liquid density, no validity limit.** Fixed reference loads from a 250 kPa column applied at 75 kPa give 2–2.8 kPa/tray (65–80 kPa over 32 trays). A real tray floods near 1 kPa. Half the error population is physically meaningless, and those are also the cases that dominate the convergence losses. | With the sieve-tray model on a column sized at 75 % flood, the validation set spans 300–1530 Pa/tray with flood fraction 0.41–1.56; inside the flood limit the one-shot error is ≤ 0.26 %. |
| F6 | **Temperature "correction" is not worth having.** A Clausius–Clapeyron shift of the lagged temperatures makes the march *worse* (higher pressure also lowers molar vapor traffic, which outweighs the T rise). | Offline: 0.94 → 1.09 % median, 9.4 → 11.1 % worst. Dropped. |
| F7 | Side issue, unrelated to hydraulics: `INVALID_INPUT: solvePath is blank or exceeds the bounded contract` still mislabels long-path solves on main (2/40 validation, 2/50 dev cases). | `V3AcceptanceAudit`/diagnostics bounded-string contract; fix exists on the A0 wet-lane branch per earlier review. |

What Codex got right and should be kept: frozen profile per solve (adjacent-stage Jacobian untouched), N−1 interval
convention, water-traffic accounting, the post-solve mismatch indicator, the 50-case population as a regression set,
and the honest failure bookkeeping.

---

## 3. Proposed method

### 3.1 Algorithm

```text
0. P0 = nominal profile: the per-tray profile persisted from this block's last accepted solve when the topology
        matches, else the uniform authored/default drop (recommend 600 Pa/tray instead of today's preset 0 Pa).
1. x0 = existing solve on P0 — cold ladder, neural seed or warm re-solve, completely unchanged.
        Failure here is today's failure. Nothing below can make it worse.
2. P1 = march(x0)                                    // 0.01–0.07 ms
        P1[1] = Ptop;  for j = 2..N:  solve  P1[j] = P1[j-1] + ΔP_tray(traffic_j(x0), P1[j])
        (scalar fixed point per tray, 2–4 evaluations; its contraction is ΔP_dry/P ≈ 0.003)
3. m = |ΔPtot(P1) − ΔPtot(P0)| / ΔPtot(P1).
   m ≤ 10 %  → publish x0 on P0; display P1 as the hydraulic profile.        (no extra solve)
   m > 10 %  → x1 = ONE warm correction on P1, seeded by x0, ≤ 24 Newton iterations
               (this is exactly one more pressure-continuation step; that machinery exists).
               success → publish x1 on P1, report residual mismatch march(x1) vs P1 as an advisory number.
               failure → half step, then full (needed 1 time in 78); still failing → publish x0 with a
                         HYDRAULIC_MISMATCH advisory. Never a request failure.
4. Stop. Never a second correction, never a closure loop.
```

`traffic_j` = vapor moles and mass leaving tray j (hydrocarbon + authored steam at/below j + free water from the
tray above), liquid mass and standard volume, tray temperature — all already in the accepted state.

### 3.2 Why one step is enough

After step 2 the only feedback left is "pressure changes the MESH solution, which changes traffic". Measured
contraction of the marched map (residual mismatch ÷ initial mismatch): **median 0.018, max 0.086** (validation),
median 0.043, max 0.18 (Codex cases incl. flooded). So an initial nominal error of 50 % leaves ≤ 4 % (≤ 9 % extreme)
after one correction, and the published error is bounded by `contraction × m`.

### 3.3 Fresh solve vs re-solve

* **Fresh solve:** the nominal is a guess, so the correction usually fires (uniform 750 Pa was within 10 % in only
  11/34 validation cases). Cost = one warm correction, +6 % median.
* **In-world re-solve / drift re-solve:** P0 is the persisted profile of the previous accepted solve, the mismatch is
  small, step 3 skips. Cost = the march only.

### 3.4 Optional optimisation — in-ladder profile (measured, *not* recommended as the primary)

Each `solveSingleProblem` takes its profile from the last accepted state of the same request (previous stage rung
mapped feed-tray-to-feed-tray, previous pressure/ramp step), adding the current rung's authored steam exactly; a
correction fires only when the final self-mismatch exceeds 5 %.

* Plain ladders: raw error median 1.7 %, correction fired in only 3/34 and 5/45 cases, total time ratio 1.007.
* But: it does not cover the neural-seed path (no intermediate accepted state; 3 presets published the authored
  0 Pa profile), the final feature-ramp step of the 3-draw/3-pumparound presets lags by 37–62 %, and it changes every
  rung's operating point (dev set: +3 / −2 convergence flips, one a 28 s budget exhaustion).
* The seed handed to a stage rung is **not** usable as a traffic source (projected seeds carry 3–5× inflated vapor:
  +410 % ΔP on a 12-tray case). Only accepted states are.

Keep it in reserve; the simple method already meets the target with a hard no-regression guarantee.

---

## 4. Measured evidence

Serial, single warmed JVM (Java 21.0.11, 2 GiB), `V3InitializationOptions.DEFAULT`, 30 s budget, corrections via the
supplied-seed path with 64-iteration / 10 s caps, reference = undamped marched fixed point to 1 Pa. Errors are against
that reference. `fixed` = today's behaviour (uniform 750 Pa).

### 4.1 Validation population (new, 10 scenarios × 90/130/200/300 kPa, sieve-tray correlation, D = 8.33 m)

Scenarios differ from the Codex ten in trays (12–36), load (0.7–1.25), feed T, reflux (2–5), duty (0–9 MW), steam
(0–35 %), draw and cooling. 34/40 solved in every arm; the 6 failures are identical in all arms (pre-existing
low-pressure / long-path failures, F7).

| Arm (34 cases) | total ΔP err % median / p95 / max | max T err K (max) | max x err pp (max) |
| --- | --- | --- | --- |
| fixed 750 Pa (today) | 16.5 / 43.5 / 51.1 | 6.51 | 1.66 |
| **march + one correction** | **0.16 / 1.76 / 2.82** | **0.34** | **0.07** |
| in-ladder, raw | 1.69 / 10.6 / 21.4 | 1.35 | 0.33 |
| in-ladder + triggered correction (3 fired) | 1.53 / 3.66 / 3.90 | 0.33 | 0.33 |

Inside the flood limit (22 cases): one-shot max **0.26 %**. Reference profile: 300–1530 Pa/tray, flood 0.41–1.56.
Corrections: 34/34 succeeded on the full undamped step, no fallback.

### 4.2 Codex's 50 cases, Codex correlation (up to 2.8 kPa/tray)

| Arm | total ΔP err % median / p95 / max | max T err K (p95 / max) | max x err pp (p95 / max) |
| --- | --- | --- | --- |
| Codex frozen estimate (TASK.md) | 70.2 / 79.0 / 81.1 | 14.8 / 41.2 | 2.66 / 16.95 |
| fixed 750 Pa (44) | 47.1 / 87.8 / 148 | 15.5 / 41.4 | 3.69 / 24.4 |
| **march + one correction (44)** | **1.14 / 7.76 / 10.6** | **2.10 / 3.34** | **0.46 / 2.47** |
| in-ladder + triggered correction (45, 5 fired) | 1.01 / 4.47 / 4.54 | 0.47 / 1.18 | 0.20 / 1.15 |

Corrections: 43/44 on the full step; `P075-S08` (zero reboiler duty, 40 % steam, −75 % initial mismatch) needed the
half step. Residual mismatch > 10 % in 2/44, both far beyond flooding. The 10.6 % worst case is `P075-S05`
(−63 % nominal error, 1.8 kPa/tray).

### 4.3 Cost

| Quantity | Validation | Codex cases |
| --- | --- | --- |
| cold solve, median / p95 ms | 473 / 1614 | 450 / 2827 |
| march | 0.02 ms | 0.01 ms |
| warm correction, median / p95 / max ms | 29 / 63 / 88 | 27 / 94 / 143 |
| correction Newton iterations, median / max | 3 / 5 | 3 / 5 |
| **(cold + correction) / cold, median / p95 / max** | 1.06 / 1.30 / 1.34 | **1.06 / 1.13 / 1.34** |
| in-ladder (+trigger) / cold, median / p95 | 0.985 / 1.18 | 1.007 / 1.09 |
| Codex outer reference / frozen (TASK.md) | — | 1.69 / 2.39 / 2.89 |

The large relative overheads are the cheap solves (a 13–70 ms correction on a 140–200 ms solve).

### 4.4 Shipped presets, D = 8.0 m (nominal = authored 0 Pa/tray, i.e. the worst possible guess)

| Preset | ΔP/tray Pa | total kPa | max flood | correction ms (cold ms) | ΔP err % | T err K | today's 0-Pa solution: T err K / x err pp |
| --- | --- | --- | --- | --- | --- | --- | --- |
| tia_juana (default) | 565 | 22.0 | 0.69 | 215 (3442) | 1.30 | 0.03 | 2.2 / 7.3 |
| literature_tia_juana | 572 | 22.3 | 0.69 | 300 (1161) | 1.32 | 0.04 | 2.7 / 8.7 |
| wti_light_export | 635 | 24.8 | 0.85 | 82 (3408) | 0.30 | 0.02 | 3.2 / 1.1 |
| bonga | 576 | 22.5 | 0.73 | 57 (672) | 1.43 | 0.11 | 10.7 / 8.6 |
| upper_zakum | 552 | 21.5 | 0.67 | 74 (493) | 1.19 | 0.06 | 5.0 / 5.7 |
| dalia | 481 | 18.7 | 0.53 | 195 (972) | 1.04 | 0.06 | 6.1 / 3.0 |
| cold_lake_blend | 465 | 18.1 | 0.47 | 1226 (18149) | 0.71 | 0.05 | 5.1 / 8.4 |

7/7 corrections succeed on a 18–25 kPa jump in bottom pressure. The last column is what ignoring pressure drop costs
the presets today.

---

## 5. Pressure-drop correlation

### 5.1 Equations (SI; per tray j, traffic leaving tray j, pressure P of tray j)

```text
A      = π D²/4          Ab = (1 − 2 fdc) A   (bubbling)      An = (1 − fdc) A   (net)      Lw = 0.726 D
ρV     = ṁV P / (ṅV R T)                       QV = ṁV / ρV
ρL     = ρL,std · Z_RA^[(1−Tr,std)^(2/7) − (1−Tr)^(2/7)]     (Rackett scaling; Kay's-rule Tc, ω of the tray liquid;
                                                              Z_RA = 0.29056 − 0.08775 ω; ρL,std from component data)
QL     = ṁL/ρL + free water

dry      ΔPd  = ρV uh² / (2 C0²)                 uh = QV /(φ Ab)                       [orifice; FRI/Wang 2018 eq. 11]
froth    Ks   = (QV/Ab) · sqrt(ρV/(ρL−ρV))
         αe   = exp(−12.55 Ks^0.91)                                                     [Bennett 1983]
         C    = 0.501 + 0.438 exp(−137.8 hw)
         hcl  = αe [ hw + C (QL /(Lw αe))^(2/3) ]          ΔPl = ρL g hcl
residual ΔPσ  = 6σ / DB,   DB = 1.27 [dh σ /(g(ρL−ρV))]^(1/3)     (≈ 28 Pa, < 5 % — see 5.4)
total    ΔP   = ΔPd + ΔPl + ΔPσ

flood    FLV  = (ṁL/ṁV) sqrt(ρV/ρL)
         Csb  = [0.0105 + 8.127e-4 TS_mm^0.755 exp(−1.463 FLV^0.842)] (σ/0.02)^0.2      [Fair; Lygeros & Magoulas 1986]
         flood fraction = (QV/An) / (Csb sqrt((ρL−ρV)/ρV))
```

Fixed tray preset (not player inputs in v1): `fdc = 0.10`, hole fraction `φ = 0.10`, weir `hw = 50 mm`,
`C0 = 0.78`, `dh = 12.7 mm`, `σ = 0.02 N/m`, tray spacing `TS = 0.6 m`. **Player input: column diameter D.**

### 5.2 Default diameter

Shipped presets all feed 0.18326 std m³/s (~100 kbpd). Diameter for 80 % / 70 % of flood at the worst tray:
tia_juana 7.53 / 8.05 m, literature 7.57 / 8.09, upper_zakum 7.45 / 7.97, bonga 7.76 / 8.30, wti 8.38 / 8.95,
dalia 6.61 / 7.07, cold_lake 6.18 / 6.61. **Default D = 8.0 m**: every preset sits at 47–85 % of flood and the default
preset at 69 %, 565 Pa/tray — inside the usual 0.4–0.8 kPa/tray for atmospheric crude trays, and a realistic
diameter for a 100 kbpd crude tower. GUI range suggestion 0.5–15 m. Throughput scales ΔPd with `(feed/D²)²`.

### 5.3 Behaviour (default preset, D = 8 m)

Rectifying trays: dry 66–292 Pa rising down the column, froth 340–380 Pa, residual 28 Pa, flood 0.32–0.68.
Stripping trays 38–40: dry 9–21 Pa, froth 540–570 Pa, flood 0.15–0.22. The liquid term falls as vapor rises
(αe), which is why the total is much flatter than the `QV²` study formula and why the fixed point is so benign.

### 5.4 Status of each ingredient

* Bennett αe / hcl / C equations and the orifice dry-drop form: **verified** against Wang, McCarley, Cai & Vennavelli
  (FRI), *Chem. Eng. Trans.* 69 (2018) eqs. 4–6, 11. Bennett's published accuracy for total sieve-tray ΔP: mean
  absolute error 6.0 %, bias −0.6 % (AIChE J. 29, 434).
* Lygeros–Magoulas fit of Fair's flooding chart: **verified** (coefficients confirmed); ±15–20 % class accuracy,
  which is fine for a warning threshold.
* `C0 = 0.78` (Liebson-type orifice coefficient for φ = 0.10, t/dh ≈ 0.4) and the residual term's exact constants are
  **from memory — check against Bennett 1983 / Kister before coding**. Both move total ΔP by < 5 %.
* FRI's conclusion for *valve* trays: orifice models under-predict; a three-region (closed / partly open / open)
  model is needed (Klein 1982, Glitsch Bulletin 4900: Kc ≈ 1.68, Ko ≈ 0.26–0.30 mH2O/(ft/s)²). A valve-tray preset is a
  natural later option and is numerically friendlier still (flat ΔP across the operating range). Not needed for v1.

### 5.5 Validity guards to publish with the number

* **USER DECISION 2026-09-18: flooding is a WARNING, never a typed failure.** Flood fraction > 1.0 publishes the
  solution with a warning that says the tray ΔP is too high and why: worst tray, flood %, that tray's ΔP split
  (dry / froth), the cause (vapor load too high for this diameter when the dry term or the vapor velocity dominates;
  liquid load too high when the weir-crest/froth term dominates) and the remedy (larger diameter, or less
  feed / steam / reboiler duty / reflux). Do not clamp silently.
* weeping advisory when the dry drop collapses (stripping trays above: 9–21 Pa). Real towers swage the stripping
  section; a second diameter below the feed is the natural v2 parameter.
* march falls back to the nominal profile if any pressure is non-finite or outside the property package envelope.

---

## 6. Integration plan (for the implementer)

1. **Contract.** Add a hydraulics spec to `V3ColumnInput`: mode `PRESCRIBED_DROP` (today, kept bit-identical) /
   `SIEVE_TRAY`, `columnDiameterMetres` (default 8.0), correlation revision string. `stagePressureDropPascal` becomes
   the nominal guess in `SIEVE_TRAY` mode; set presets to 600 Pa. Bump input schema, NBT, wire, `V3InputDigest`
   (mode + diameter + correlation revision), assumptions revision.
2. **Explicit profile.** `V3ColumnProblemResolver.resolve(input, branch, double[] profile)`; replace the study's static
   hook. Steam-injection validation and `V3OperatingDomainValidator` must read the supplied profile.
3. **`V3TrayHydraulics`** (new, pure): traffic from `(problem, V3DryMeshState)`, `march`, per-tray terms, flood
   fraction. No thermo calls except component constants (MW, standard density, Tc, ω).
4. **Calculator.** After the publishable pass in `calculateBranch` and in the learned path (`acceptLearnedPass`): march,
   compare, optionally one `solveSingleProblem` on the re-profiled problem seeded with the accepted state, path
   suffix `/hydraulic-correction`, `PRESSURE_LOCAL_PREDICTOR` policy, ≤ 24 iterations; half-step fallback; else publish
   the first pass with a `HYDRAULIC_MISMATCH` advisory. Feed flash/enthalpy is rebuilt inside `solveSingleProblem`
   already, so no stale pressure-derived value survives.
5. **Result/GUI.** Publish per-tray ΔP, total ΔP, max flood fraction + tray, residual mismatch. GUI: diameter field on
   the column screen (use the minecraft-mod-mcp bridge for the GUI check), hydraulic summary on the result page.
6. **Persistence.** Store the accepted per-tray profile with the block so re-solves start from it (3.3).
7. **Neural initializer.** Features stay on the nominal linear profile (the seed is only a start; the correction is
   classical). Confirm the 600 Pa nominal lies inside model coverage.
8. **Tests.** Units and term values at a pinned state; N−1 interval convention; water traffic; zero-vapor tray;
   monotonic terms at fixed properties; `PRESCRIBED_DROP` bit-identity (digests/pins unchanged); correction fallback
   path (`P075-S08` as the regression case); the 7 presets within 2 % of their converged reference; flood warnings.
   Keep the Codex 50 and the new 40 as regression journals.

No solver-core change (Jacobian, ledger, coordinates, certificates) is required.

---

## 7. Limits of this evidence

* One assay family for the two campaigns (TJL19) plus 7 shipped presets across 7 packages; no vacuum (< 75 kPa).
* Timings are single-pass, serial, one machine; ratios are paired within a case.
* The reference shares the correlation with the arms: the numbers certify *numerical* closure of the method, not the
  correlation against equipment (that rests on Bennett's published 6 %).
* Ideal-gas vapor density (Z = 1) and one fixed σ; PR Z would shift the dry term by ~3–7 % at 3–4 bar.
* 6 validation / 3–6 dev cases fail in *every* arm (pre-existing solver limits, F7), so they have no reference.
* The in-ladder arm was prototyped through a shadowed calculator; its convergence flips (+3/−2) are single runs.

## 8. Reproduction

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:GRADLE_USER_HOME='C:\Users\wormz\.gradle'
.\gradlew.bat -I experiments/tray-pressure-method/study.gradle trayPressureMethod '-PstudyMode=val' '-PstudyOutput=<new dir>' --offline --console=plain
#   modes: val | dev | val-pilot | dev-pilot | presets (add -Dstudy.diameter=8.0)
python experiments/tray-pressure-method/analyze.py experiments/tray-pressure-method/val      # real interpreter: ~/.local/bin/python3.12.exe
python experiments/tray-pressure-method/offline/offline_candidates.py     # re-scores the Codex journal, no solves
```

Files: `java/TrayPressureMethodStudy.java` (arms, correlations, march), `java/V3ColumnCalculator.java` (production copy
+ 2 hook lines), `java/V3ColumnProblemResolver.java` (production copy + profile hook), `val/`, `dev/`, `presets-d8/`,
`presets-sizing/` journals. Serial only (static hooks); never put this classpath in the mod.

## Sources

* Bennett, Agrawal, Cook, "New pressure drop correlation for sieve tray distillation columns", AIChE J. 29 (1983) 434 — https://aiche.onlinelibrary.wiley.com/doi/10.1002/aic.690290313
* Wang, McCarley, Cai, Vennavelli (FRI), "Study of Clear Liquid Height and Dry Pressure Drop Models for Valve Trays", Chem. Eng. Trans. 69 (2018) — https://www.aidic.it/cet/18/69/069.pdf
* Lygeros & Magoulas, Hydrocarbon Processing 65(12) (1986) 43 (fit of Fair's flooding chart); coefficients as quoted in Souza et al., Ind. Eng. Chem. Res. — https://pubs.acs.org/doi/10.1021/acs.iecr.4c03115
* Kister & Haas flooding correlation overview — https://www.researchgate.net/publication/265168031

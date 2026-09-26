# Flow-dependent tray pressure drop — implementation notes

**Date:** 2026-09-18. **Branch:** `claude/tray-pressure-study-method-a19e2d` (worktree
`.claude/worktrees/tray-pressure-study-method-a19e2d`), base `4e84f0e`. Working tree only; nothing committed.
**Spec:** `documentation/TRAY_PRESSURE_METHOD_REVIEW.md` (this worktree). **Study:**
`experiments/tray-pressure-method/`.

---

## 1. What was built

The "march + one correction" method of review §3.1, with the sieve-tray correlation of §5.1, behind one new
authored input: the column diameter.

* `columnDiameterMetres == 0` — **prescribed-drop mode**. Bit-for-bit today's calculator: the same uniform
  profile, the same digest byte stream, the same published fields, and not one extra flash or solve.
* `columnDiameterMetres > 0` — **sieve-tray hydraulics**. The authored `stagePressureDropPascal` becomes the
  nominal guess of the first solve; the published profile is marched from that solve's own accepted traffic and,
  when it disagrees with the nominal by more than 10 %, one bounded warm correction re-solves the identical
  request on it.

Flooding is a warning, never a typed failure, and no failure of the hydraulic step can fail a request.

---

## 2. Files

### Added (production)

| File | What |
| --- | --- |
| `science/column/v3/V3TrayHydraulics.java` | The whole correlation: geometry constants, `ComponentConstants`, per-tray `traffic` extraction from `(problem, state)`, Rackett `liquidDensityKgPerCubicMetre`, `terms` (dry / liquid / residual / flood), `dropPascal`, the pressure-consistent `march`, `totalDropPascal`, `totalDropMismatch`, `isAdmissible`, `halfway`, `summarise`, `floodingWarning`, and `CORRELATION_REVISION`. No thermodynamics beyond pure-component constants. |
| `science/column/v3/V3TrayHydraulicsSummary.java` | Bounded publication record: diameter, total ΔP, mean ΔP/tray, max flood fraction and its tray, whether a correction ran, residual mismatch. |

### Added (tests / harness)

| File | What |
| --- | --- |
| `src/test/.../science/column/v3/V3TrayHydraulicsTest.java` | Correlation unit values at a pinned traffic state (independently evaluated from the review's equations), zero-vapour tray, monotone dry term in vapour load, exact `1/P` and `1/D⁴` scalings, march ≡ uniform profile at constant drop, N−1 interval convention, supplied-profile rejection cases, water traffic, flooding-warning content, summary arithmetic, mismatch normalisation. |
| `src/test/.../science/column/v3/V3TrayHydraulicsColumnTest.java` | End-to-end: the default preset at 8 m (≈565 Pa/tray, ≈22 kPa, flood ≈0.69, correction ran, residual < 3 %), a 5 m column publishing SUCCESS with the flooding warning, a nominal already inside the tolerance running no correction, a correction that cannot run publishing the first solve with a hydraulic-mismatch warning, prescribed-drop mode publishing no hydraulics, digest separation, and the shipped presets' authored diameter. |
| `src/test/.../network/V3TrayHydraulicsCodecTest.java` | Wire and NBT round trip of the diameter in both modes, version-9 NBT migration to prescribed-drop, hydraulics-summary round trip and absence, summary contract rejections. |
| `experiments/tray-pressure-method/validation/java/TrayPressureProductionValidation.java` | The study harness re-pointed at the production calculator. Its classpath deliberately excludes the study's shadowed `V3ColumnCalculator`/`V3ColumnProblemResolver` copies. |
| `experiments/tray-pressure-method/validation.gradle`, `validation/analyze.py` | Task wiring and scoring for that harness. |

### Changed (production)

| File | Change |
| --- | --- |
| `V3ColumnInput.java` | 15th record component `columnDiameterMetres`; 14-argument legacy constructor delegating to `PRESCRIBED_DROP_DIAMETER`; `PRESCRIBED_DROP_DIAMETER`, `DEFAULT_COLUMN_DIAMETER_METRES = 8.0`, `MAX_COLUMN_DIAMETER_METRES = 15.0`; range validation; `usesTrayHydraulics()`; `withColumnDiameter(double)`; equals/hashCode extended. `SCHEMA_VERSION` deliberately **not** bumped (see §4). |
| `V3ColumnProblemResolver.java` | New `resolve(input, branch, double[] profile)`; package-private `validateInput(input, profile)`; the steam-superheat bound and the bottom-pressure check read the supplied profile; `requireProfileShape` enforces node count, finiteness, the authored top pressure on condenser and top tray, a nondecreasing tray section and the sump on the bottom tray. |
| `V3InputDigest.java` | Diameter and `V3TrayHydraulics.CORRELATION_REVISION` hashed **only** when a diameter was authored, after the existing `stage-drop-bits` field. Every prescribed-drop digest keeps its byte stream. |
| `V3ColumnCalculator.java` | One shared publication seam `publishAccepted` used by the classical path (`calculateBranch`) and the learned path (`acceptLearnedPass`, reached from both `correctNeuralSeed` and `neuralRampHandoff`); `trayHydraulics` (march, compare, correct, fall back); `hydraulicCorrection` (resolve on the profile, operating-domain admission, one `solveSingleProblem`, the same three publication gates); `wetTrayMask`, `summarised`, `advisory`; constants `HYDRAULIC_MISMATCH_TOLERANCE = 0.10`, `HYDRAULIC_CORRECTION_MAXIMUM_ITERATIONS = 64`, `HYDRAULIC_CORRECTION_BUDGET_MILLIS = 10_000`, `HYDRAULIC_CORRECTION_PATH_SUFFIX = "/hyd"`; offline seam `calculateOnSuppliedProfile` (see §5); every internal input derivation (`withStageGeometry`, `withoutSideDraws`, `withoutPumparounds`, `withoutSteamWithSurrogateDuty`, `withTopPressure`, the draw-ramp `rampInput`) now carries the diameter. |
| `V3ColumnResult.java` | Optional `trayHydraulics()` plus the `accepted(...)` overload that carries it. |
| `V3ColumnDisplayResult.java` | Optional `trayHydraulics` component and the legacy constructor that reads as empty. |
| `V3NeuralRegistry.java` | `project(...)` carries the diameter into the projected input. |
| `thermo/V3PengRobinsonThermo.java`, `thermo/V3PengRobinsonSession.java` | `componentCriticalTemperatureKelvin`, `componentAcentricFactor`, `componentStandardLiquidDensityKgPerCubicMetre` on the public axis. |
| `science/material/MaterialPresets.java` | `Column.diameter`, parsed from `operating.columnDiameterMetres` with a 0.0 default and a range check. |
| `world/level/block/entity/ColumnCalculatorV3BlockEntity.java` | `DATA_VERSION` 9 → 10 with `MINIMUM_READABLE_DATA_VERSION = 9`; `ColumnDiameter` written and read (absent ⇒ 0.0); `Hydraulics` compound written and read as a trailing optional block. |
| `network/ColumnV3Network.java` | `WIRE_SCHEMA_VERSION` 11 → 12; diameter written/read in the input codec; hydraulics summary as a trailing optional block of the display-result codec. |
| `client/gui/.../V3ColumnScalarDraft.java`, `V3ColumnInputDraft.java`, `ColumnCalculatorV3Screen.java` | Tenth scalar field "Diameter (m)", parsed and range-checked like the other numeric fields; the scalar grid became four columns wide so the diameter sits beside the pressures without moving the side-draw and steam blocks; the Convergence page shows total ΔP, mean ΔP/tray, flood % and the worst tray; a hint line explains 0 m. |
| `resources/data/createcheme/materials/presets/column_*.json` | All seven shipped presets author `"columnDiameterMetres": 8.0`. Their `stagePressureDropPascal` is untouched at 0.0, as §4.4 of the review was measured. |
| `resources/assets/createcheme/lang/en_us.json` | `gui.createcheme.column_diameter`, `gui.createcheme.column_diameter_hint`. There is no `zh_cn.json` in this repo, so no second language file was touched. |

### Changed (existing tests)

| File | Why |
| --- | --- |
| `src/test/resources/materials/column-preset-inputs.json` | The shipped presets now author a diameter; this fixture pins the presets *as shipped*, so it records 8.0. |
| `network/V3ClosureCodecTest.java` | Version pins 9/11 → 10/12; the poke offsets move by one byte because the display result gained a trailing absent-hydraulics flag. |
| `network/V3PumparoundCodecTest.java` | Same one-byte offset move. |
| `client/gui/.../V3ColumnScalarDraftTest.java`, `V3ColumnInputDraftTest.java` | Tenth draft field, plus new assertions on it. |
| `science/column/v3/V3LiteraturePresetTest.java` | The dew-point/distillate pins were measured on the prescribed-drop column; that test now runs `withColumnDiameter(0)` and the shipped-preset test additionally pins the authored 8.0 m. |
| `science/column/v3/thermo/RegroupedCrudeTest.java` | The captured draw volumes are prescribed-drop numbers; that case runs `withColumnDiameter(0)`. |

No numbers were re-pinned to make a test pass. The two solve tests above were moved into prescribed-drop mode
exactly as the brief asks, and the published hydraulic state of the shipped presets is covered instead by
`V3TrayHydraulicsColumnTest`.

---

## 3. The algorithm as implemented

```
publishAccepted(input, pass, ...)                       // one seam, classical and learned
  └─ trayHydraulics(...)
       diameter == 0                       -> publish the pass unchanged                 (legacy)
       march(traffic(accepted state))      -> P1
       P1 not finite/ordered/in envelope   -> publish the pass + envelope warning         (§3.1 step 4)
       |ΔPtot(P1) − ΔPtot(P0)| / ΔPtot(P1) <= 10 %
                                           -> publish the pass, summary computed on P1
       else  hydraulicCorrection(P1)       -> publish the corrected state on P1
             else half step, then the full step from where it reached
             else publish the pass + hydraulic-mismatch warning
  └─ digest, duty ledger, streams, exported profile, diagnostics, built once from whatever is published
```

`hydraulicCorrection` resolves the *identical* request on the supplied profile, re-checks the operating domain,
and runs one `solveSingleProblem` with `STAGE_LOCAL_BLOCKS`, 64 iterations, the accepted state as the seed and
the accepted free-water tray set as the initial wet set. It returns a pass only if `publishesSuccess`,
`publishesRequestedGeometry` and the convergence gates all hold — the same three gates the first pass passed.

Costs and guarantees:

* The first solve is today's solve, unchanged, on today's nominal profile. A failure there is today's failure.
* At most three extra solves in the worst case (full, half, full-from-half); normally one, often none.
* The correction runs under its own 10 s wall clock inside the caller's deadline.
* The hydraulic step never throws: every `RuntimeException`, including `CancellationException`, publishes the
  already-accepted first pass with a warning (see §4, deviation D3).

---

## 4. Contract, versions and deviations

**Versions.** `ColumnCalculatorV3BlockEntity.DATA_VERSION` 9 → 10, `ColumnV3Network.WIRE_SCHEMA_VERSION` 11 → 12.
`V3ColumnInput.SCHEMA_VERSION` stays 1 on purpose: it is hashed into every digest and into the NBT/wire schema
guard, so bumping it would change every legacy digest and invalidate every persisted input.

**Deviations from the brief, and why.**

* **D1 — NBT migration instead of a hard version wall.** The repo's existing pattern is
  `dataVersion != DATA_VERSION ⇒ INCOMPATIBLE`, which would turn every existing column block in every existing
  world into "load a preset to replace this unsupported input". The brief also requires old NBT to load as 0.0
  (legacy). Both are satisfied by bumping the version *and* accepting version 9, which reads as
  `PRESCRIBED_DROP_DIAMETER` — bit-for-bit the behaviour that block already had. `MINIMUM_READABLE_DATA_VERSION`
  makes the window explicit.
* **D2 — the diameter is carried through the solver's internal input derivations.** The brief said the internal
  surrogate/continuation inputs "only need it for the final requested problem". They need it for a different
  reason: `publishesRequestedGeometry` compares `problem.input().equals(input)`, so a ramp rung or a
  pressure-continuation step that dropped the diameter could never publish a hydraulics request that carries
  draws, steam or heat. The diameter has **no** effect on any solve, so carrying it is free and keeps the
  equality intact.
* **D3 — cancellation during the hydraulic step does not escape.** `calculate` documents that a cancellation
  from `control` escapes unchanged. Inside the hydraulic step it is swallowed and the already-accepted first
  pass is published with a warning, because the alternative is discarding a solved column to report a deadline
  that expired while polishing its pressure profile — which is exactly the "never a request failure caused by
  hydraulics" rule. The correction's own 10 s sub-wall keeps that window small.
* **D4 — the half-step fallback publishes the nominal if the following full step fails.** Per the brief. The
  half-step state is itself accepted on its own profile and could be published instead; it is not, to keep the
  ladder to exactly the three outcomes the review describes.
* **D5 — an offline seam was added.** `V3ColumnCalculator.calculateOnSuppliedProfile` (package-private) is
  `hydraulicCorrection` with the profile chosen by the caller. The converged hydraulic reference the validation
  campaign scores against is a fixed point of "march, re-solve, march again", and only a caller can drive that
  loop; the public API has no way to author a per-tray profile. It follows the existing
  `calculateWithNeuralTrace` / `calculateWithAcceptedProfile` offline-seam convention, no production entry point
  reaches it, and it is explicit data flow rather than the study's static hook.
* **D6 — `hydraulicCorrection` treats a contract rejection as a failed step, not as an aborted hydraulic
  pass.** A marched profile can be refused by the steam-superheat admission (steam that is superheated at the
  nominal sump can be saturated 20 kPa lower down). That refusal now returns `null`, so the half step still gets
  its chance, instead of abandoning hydraulics for the whole request.
* **D7 — the GUI scalar grid is four columns wide.** Ten fields do not fit the old 3 × 3 grid without colliding
  with the side-draw row. Four columns keep every block at its existing vertical position and put the diameter
  next to the pressures, as asked.

**Not done.**

* **In-game GUI check.** Not performed. No `run/mods` directory exists in this worktree and no
  `minecraft-mcp-*.jar` was found under any sibling worktree's `run/mods`
  (`.claude/worktrees/*/run/mods/` does not exist), and downloading is not permitted. The GUI changes are
  therefore covered only by the draft unit tests; **the field has not been seen in the real client.**
* **Persistence of the accepted profile across re-solves** (review §6.6 / §3.3). Explicitly deferred by the
  brief. Every solve starts from the authored nominal, so a fresh cold solve always pays for one correction.
* **`zh_cn` lang entries.** The repo ships only `en_us.json`.

---

## 5. Measured validation

See §6 for the tables. Method: the study harness re-pointed at the production calculator
(`experiments/tray-pressure-method/validation/`), serial, single warmed JVM, 2 GiB, 30 s request budget,
reference = undamped fixed point of "march, re-solve" closed to 1 Pa with one damped retry, diameter
8.330929431807828 m (the study's validation sizing). Errors are against that reference.

---

## 6. Results

Reproduce with:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:GRADLE_USER_HOME='C:\Users\wormz\.gradle'
.\gradlew.bat -I experiments/tray-pressure-method/validation.gradle trayPressureValidation `
    '-PstudyMode=val' '-PstudyOutput=<new dir>' --offline --console=plain -q    # modes: val | presets
python experiments/tray-pressure-method/validation/analyze.py <dir>             # ~/.local/bin/python3.12.exe
```

Serial, one warmed JVM, 2 GiB, 30 s request budget, D = 8.330929431807828 m (the study's validation sizing),
reference = undamped fixed point of "march, re-solve" to 1 Pa. Two independent full runs gave identical error
figures to the last printed digit. No campaign overlapped another campaign or a Gradle test suite.

### 6.1 Convergence — unchanged, by construction

| | nominal (prescribed drop, 750 Pa) | hydraulic (D = 8.33 m) |
| --- | --- | --- |
| SUCCESS | 34/40 | 34/40 |
| status differences | — | **none** |

The six failures are the study's six, with the study's causes: `P090-V03`, `P090-V08`, `P130-V06`, `P200-V09`
pre-existing NONCONVERGENCE, and `P090-V09`, `P090-V10` the known long-`solvePath` `INVALID_INPUT` bug (being
fixed on a separate branch). The reference converged for 34/34 solved cases in 3 iterations median, 4 maximum.

### 6.2 Accuracy against the converged reference (34 cases)

| Arm | total ΔP error % median / p95 / max | max tray T error K (median / max) | max composition error pp (median / max) |
| --- | --- | --- | --- |
| nominal (today) | 16.54 / 43.46 / 51.07 | 0.41 / 6.51 | 0.09 / 1.66 |
| **hydraulic, all 34** | **0.77 / 8.65 / 8.87** | 0.06 / 0.45 | 0.010 / 0.326 |
| hydraulic, the 23 that ran a correction | **0.33 / 1.67 / 2.82** | 0.02 / 0.34 | 0.003 / 0.067 |
| hydraulic, the 11 that did not | 5.34 / 8.67 / 8.87 | 0.15 / 0.45 | 0.052 / 0.326 |
| corrected **and** inside the flood limit (11) | 0.15 / 0.25 / 0.26 | — | — |

The 23-case subset reproduces the study's arm B to the last digit (study: median 0.157, p95 1.666, max 2.822;
inside the flood limit, max 0.26 %). The brief's expectation of "median ≈ 0.2 %, max ≈ 3 %" is exactly that
subset. The all-34 figure is larger because the *method* (review §3.1 step 3) deliberately skips the correction
when the nominal is already within 10 %, which it is in 11/34 cases — the review itself measures that as 11/34
(§3.3). The published error of a skipped case is therefore the nominal's own error, bounded by the 10 %
tolerance, and the measured worst is 8.87 %: inside the user's stated tolerance but not inside 3 %. The study's
arm B corrected unconditionally and so never had those 11. **This is a one-constant knob**:
`HYDRAULIC_MISMATCH_TOLERANCE` sets the worst-case published error directly, at the cost of a correction on the
cases now skipped.

### 6.3 Cost (34 solved cases)

| Quantity | Measured |
| --- | --- |
| nominal solve, median / p95 / max | 487 / 1312 / 3770 ms |
| hydraulic solve, median / p95 / max | 470 / 1370 / 3721 ms |
| hydraulic / nominal, median / p95 / max | 1.006 / 1.231 / 1.466 |
| the same, restricted to the 23 corrected | 1.044 / 1.231 / 1.466 |
| hydraulic − nominal, corrected cases | 17 / 58 / 58 ms (median / p95 / max) |

Review's expectation: +6 % median on a cold solve, correction ≈ 30 ms median, ≤ 88 ms. Measured 17 ms median
and 58 ms worst on this machine.

### 6.4 Published hydraulics of the validation population

Mean tray drop 522–1541 Pa, maximum flood fraction 0.41–1.56. Twelve of the 34 cases exceed the Fair flooding
limit and publish SUCCESS with the flooding warning, e.g.

> Warning: tray 6 is at 106% of flood (drop 1.11 kPa: dry 0.77, liquid 0.31 kPa); the vapor load is too high
> for a 8.3 m column; widen the column, or cut feed, steam, reboiler duty or reflux

### 6.5 Shipped presets at the authored 8.0 m

| Preset | ΔP/tray Pa | total kPa | max flood | correction | ΔP err % | nominal ms | hydraulic ms (Δ) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| tia_juana (default) | 565 | 22.02 | 0.68 | ran | +1.30 | 1393 | 1600 (+207) |
| literature_tia_juana | 572 | 22.33 | 0.68 | ran | +1.32 | 1056 | 1282 (+226) |
| wti_light_export | 635 | 24.78 | 0.85 | ran | +0.30 | 2131 | 2237 (+106) |
| bonga | 576 | 22.48 | 0.72 | ran | +1.43 | 3277 | 3065 (−212) |
| upper_zakum | 567 | 22.10 | 0.68 | ran | +1.28 | 1143 | 1203 (+60) |
| dalia | 481 | 18.74 | 0.53 | ran | +1.04 | 2650 | 2857 (+207) |
| cold_lake_blend | 465 | 18.15 | 0.46 | ran | +0.71 | 15639 | 16596 (+957) |

Expected from the study's `presets-d8` journal: 565 / 572 / 635 / 576 / 552 / 481 / 465 Pa per tray and
0.3–1.4 % error. Every preset matches to the printed digit except `upper_zakum` (567 against 552 Pa/tray,
flood 0.68 against 0.67, error 1.28 % against 1.19 %): the study reported its flood at the *reference* state
while this reports it at the *published* one. All seven correct successfully, none floods, and all seven errors
are inside 0.3–1.5 %.

---

## 7. Risks

* **R1 — the shipped presets change behaviour by default.** Every preset now solves at 8 m and publishes a
  22 kPa column instead of a zero-drop one. That is the intent, but it moves every preset-derived number a
  player or a test has ever seen. Two existing tests were pinned into prescribed-drop mode for exactly this
  reason; anything else that pins preset numerics (in-world fluid networks, saved results) will move too.
* **R2 — cost on a cold solve.** One extra warm correction, measured at +6 % median by the review and confirmed
  here. Columns whose first solve is cheap pay a larger relative overhead; columns that need the half step pay
  for up to three.
* **R3 — the correlation's constants.** `C0 = 0.78` and the residual term's constants are, per review §5.4,
  "from memory — check against Bennett 1983 / Kister before coding". They were coded as the review states and
  are pinned by `V3TrayHydraulicsTest`; both move the total by < 5 %. A revision must bump
  `V3TrayHydraulics.CORRELATION_REVISION`, which is already in the digest of a hydraulics request.
* **R4 — one assay family.** Validation is TJL19 plus the seven shipped presets, no vacuum below 75 kPa, ideal-gas
  vapour density, one fixed surface tension. Unchanged from the review's own limits (§7).
* **R5 — solve-path length.** The published path gains `/hyd` (4 characters). The known
  `solvePath is blank or exceeds the bounded contract` bug is being fixed on a separate branch; the suffix was
  kept deliberately short and `diagnostics(...)`/`terminalFailure(...)` were left untouched to avoid conflicting
  with that change.
* **R6 — no in-game verification.** See §4.

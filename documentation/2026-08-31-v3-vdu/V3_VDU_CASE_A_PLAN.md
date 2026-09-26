# V3 VDU plan: case A, Ji & Bagajewicz (2002) vacuum tower

Date: 2026-09-08. Branch `claude/v3-literature-cdu-handoff-3179dc` at `530ee05`. Status: **PLAN, nothing implemented.**
Basis: `documentation/V3_VDU_LITERATURE_AND_THERMO_DATA.md` (case selection, thermo limits) and
`documentation/VDU_SIMULATION_RESEARCH.md` (main checkout; process facts, D1–D8).

Maintainer decision recorded: **case A adopted** ("Go for 1"). Two further choices from the research doc are taken
as working assumptions here and are flagged where they bite: K-value policy = PR78 as characterized, with the
Maxwell-Bonnell bias published as advisory evidence (§4, WP1); feed basis = residue-only re-cut of the light-crude
TBP tail (§2.2, WP1).

Maintainer requirement added 2026-09-08: **thermodynamic parameters must be viable for both CDU and VDU**.
Section 2.5 is a hard acceptance requirement for WP1, WP3 and any WP5 refit. The source-case and boundary-model
issues in `V3_VDU_CASE_A_PLAN_REVIEW.md` remain unresolved and still precede WP0 freeze.

Standing rules carried over: literature before experiments (experiments start only in WP2, after the package and
the frozen contract exist); CDU lanes stay bit-identical at τ = 0; every new behaviour behind its own formulation
revision; audits are the arbiter; every review gets `documentation/<TOPIC>_REVIEW.md`; work only in this worktree.

---

## 1. What the current contract already gives us

Read against the source at `530ee05` while planning (no code changed):

| VDU feature | Current V3 mechanism | Gap |
|---|---|---|
| No condenser; vapour leaves the top | Node 0 with `CondenserOutletTemperature` and `OrganicRefluxRatio(0)`; `V3CondenserPhaseBranch` TWO_PHASE / VAPOR_ONLY chosen by the resolver; ledger allows zero reflux (`VAPOR_ONLY_WITH_POSITIVE_REFLUX` is the only guard) | Node 0's duty is a computed output that models the ejector precondenser, not a column heat sink. Needs an audit, not new code (§2.4) |
| No reboiler; feed enters the flash zone; steam-stripped bottom | `ReboilerDuty(0)` is legal when a sump steam feed exists (`V3ColumnProblemResolver`, "zero reboiler duty requires sump steam"); node N+1 is then a steam-stripped equilibrium stage; the literature CDU already runs this way | None |
| Heat removal by pumparounds | `V3PumparoundSpec(returnTray, drawTray, dutyWatts, Split)`, max 3, WP1–WP4 landed (solver, steam, NBT/wire v7, Heat tab) | Return temperature is an output, not a spec; case A publishes duties, so duty-spec is the right fit and the temperatures become audits |
| LVGO / HVGO side draws | `V3SideDrawSpec(tray, mol/s)`, max 3 | Literature rates are volumetric (m³/h); conversion is a documented data step (§2.3) |
| Feed partially vaporized at the flash zone | Feed is given as T at the feed-tray pressure; `V3ColumnInitializer` seeds from `thermo.flashTP` at that pressure | Feed T at column pressure *is* the flash-zone condition; the flash-zone tray temperature becomes an audit against 382 °C |
| Overflash | Not a named quantity | New calculated quantity + audit (§2.4) |
| Sub-atmospheric pressure | `V3OperatingDomainValidator` admits any pressure above the package floor; below 100 kPa the `LOW_PRESSURE_HYBRID` lane runs `solveDwsimPressureContinuation` from a 150 kPa anchor in **linear** steps | Package floor is 50 kPa (declaration); the linear schedule is unsuitable for 10 kPa (§3, WP2) |
| Water | Steam feeds, `V3WaterCondenserRegime` ALL_VAPOR at a hot top; dew point at 1–5 kPa partial pressure is 7–33 °C | None expected; wet-tray machinery stays idle |

Conclusion: no new topology class is needed for case A. The old research doc's "condenser/reboiler hardwired" gap
has been closed indirectly by the literature-CDU work (zero reflux, zero duty, steam sump, pumparounds, draws).

---

## 2. Reconstruction contract (to be frozen as `src/test/resources/.../vdu-case-a-v1.json` in WP0)

### 2.1 Published values (Part I Table 1, Part II Table 1, Part I Table 5, conventional light-crude plant)

| Item | Value | Role in V3 |
|---|---|---|
| Trays | 7 | `stageCount = 7` (+ sump node 8) |
| Flash zone pressure | 1.90 psia = 13.10 kPa | pressure at the feed tray (§2.2) |
| Flash zone temperature | 382 °C | feed temperature input; feed-tray T audited ±5 K |
| Overhead temperature | 127 °C | `CondenserOutletTemperature(400.15 K)`; tray-1 T audited ±10 K |
| Overflash ratio | 0.02 | audit on liquid leaving the tray above the feed / feed |
| LVGO spec | D86 95 % = 410 °C ≡ 30 vol % of total VGO | rate spec derived from the yields |
| Bottom steam | 2.74 lb/bbl of vacuum residue | 105.53 m³/h × 6.2898 bbl/m³ = 663.8 bbl/h → 825 kg/h = **45.8 kmol/h = 12.7 mol/s** at the sump |
| Vacuum PA1 | 232.2 → 93.3 °C, 7.20 MW | `V3PumparoundSpec(return, draw, −7.20e6, UNIFORM)` at the LVGO draw; draw T audited |
| Vacuum PA2 | 312.8 → 176.7 °C, 7.33 MW | same at the HVGO draw |
| LVGO / HVGO / residue draw temperatures | 232.2 / 312.8 / 371.1 °C | audits |
| Yields (m³/h, std) | LVGO 22.92, HVGO 81.43, residue 105.53, overhead 0.202 | draw rate specs (LVGO, HVGO); residue and overhead are outputs |
| Feed | atmospheric residue = 210.1 m³/h = 26.4 vol % of 795 m³/h crude | residue-only re-cut of the crude TBP above 73.6 vol % (≈ 412 °C) |
| Crude | 845 kg/m³ (36 API); TBP °C at 5/10/30/50/70/90 vol % = 45/82/186/281/382/552; light ends vol % C2 0.13, C3 0.78, iC4 0.49, nC4 1.36, iC5 1.05, nC5 1.30 | WP1 characterization input |
| Quench | bottoms quenched to 365 °C | not modelled (outside the column balance) |

### 2.2 Stage layout and pressures (assumptions, to be pinned in the contract)

The paper gives tray count, flash-zone pressure and the PA temperatures, not stage numbers. Proposed layout,
top to bottom, following the Watkins arrangement the authors used for the initial scheme:

| Node | Role |
|---|---|
| 0 | vapour exit at 127 °C, zero reflux (ejector precondenser; condensate ≈ the 0.202 m³/h overhead) |
| 1 | PA1 return tray (LVGO section top) |
| 2 | LVGO draw, PA1 draw |
| 3 | PA2 return tray (HVGO section top) |
| 4 | HVGO draw, PA2 draw |
| 5 | wash tray (liquid to the flash zone = overflash) |
| 6 | flash zone, feed tray |
| 7 | stripping tray |
| 8 (sump) | stripping stage with steam, residue outlet, duty 0 |

Pressure: flash zone 13.10 kPa at tray 6; one declared per-tray drop. With 0.50 kPa per tray (VDU research doc:
0.1–0.3 kPa per theoretical stage in packed towers, wider for trays) the top is 10.6 kPa and the sump 14.1 kPa.
**Assumption A1**: top pressure 10.6 kPa, drop 0.50 kPa/tray. A2: feed tray 6 (alternative 7 with one stripping
stage is a WP3 sensitivity, not a second contract). Steam supply 150 °C (A3; the resolver requires ≥ 5 K superheat
above 52.5 °C saturation at 14 kPa; enthalpy difference is < 0.1 MW either way).

### 2.3 Draw rates

`V3SideDrawSpec` is molar. Conversion of 22.92 and 81.43 m³/h at 60 °F to mol/s uses the characterized cut slate:
LVGO = the lightest 30 vol % of total VGO, HVGO = the next 70 vol %, each converted with the volume-weighted MW and
density of the cuts that fill that volume. The conversion is a table in the contract with its cut assignment; the
resulting molar rates are the specs, and the product volumes computed by V3 from its own densities are the audited
quantities (±5 % on volume closes the loop).

### 2.4 Audits and acceptance bands

Bands are wide on purpose: the PRO/II property method is unstated and PR78 sits 4–13 K below Maxwell-Bonnell at
these pressures (thermo doc §2.4). A miss inside the band is reported, not chased.

| Quantity | Literature | Band |
|---|---|---|
| Tray-1 vapour temperature | 127 °C | ±10 K |
| Node-0 duty (precondenser) | not published | magnitude ≤ 1.0 MW, reported |
| LVGO draw tray T / HVGO draw tray T / sump T | 232.2 / 312.8 / 371.1 °C | ±10 K |
| Flash zone T | 382 °C | ±5 K (feed-dominated) |
| Overflash (liquid from tray 5 / feed, volume) | 0.02 | 0.01–0.04, reported |
| Vacuum residue volume | 105.53 m³/h | ±5 % |
| Overhead condensate + gas | 0.202 m³/h | reported (tiny; may be zero on the VAPOR_ONLY branch) |
| Water | all steam overhead as vapour | WATER_BALANCE passes, no wet tray, no dew-point warning |
| MESH residual, mass defect, audits | existing gates | unchanged tolerances |

---

### 2.5 Shared CDU/VDU thermodynamic contract

For the same real component or identically defined pseudo-cut, both units must use one parameter revision:
Tc, Pc, acentric factor/alpha-function parameters, MW, density correlation, ideal-gas Cp, enthalpy reference,
and binary-interaction rules. Temperature, pressure and composition change between units; the unit type must
not select a different fit. PR78 is the first candidate, not a guarantee that the current estimates are adequate.

The proposed `vdu_lc36_dwsim` identifier may identify a residue basis/view, but must not become a separate
vacuum-tuned parameter family. Use canonical assay/cut definitions with stable component identities. Prefer
the same component definitions across a future CDU-bottoms/VDU-feed handoff. Any regrouping or refinement of
cuts requires distinct identities and a reviewed material/enthalpy mapping. LC36 and Tia Juana need not have
identical pseudo-component parameters: they are different assays. A residue composition need not contain
every light component needed for a whole-crude CDU.

Freeze a joint validation matrix before fitting: intended vacuum states, intermediate pressures, and
atmospheric/pressurized CDU states. Cover saturation/VLE, representative mixture flashes, liquid density,
Cp, enthalpy differences, and numerical continuity across the pressure range. Declare reference provenance
and property-specific error limits in advance. PR-versus-Maxwell-Bonnell differences are model spread;
an independent implementation of PR checks implementation consistency, not physical accuracy by itself.

Every new fit must pass both regimes. A broad envelope declaration is not qualification. Existing CDU tests
remain mandatory, but also exercise the new parameters themselves at CDU conditions: unchanged legacy
packages cannot prove the new LC36 fit is accurate. A full LC36 CDU plant simulation remains separate work;
joint property/mixture qualification is required here.

Existing released property revisions remain reproducible. A deliberately adopted new shared revision may
change predictions in both units and needs joint qualification; it need not reproduce the old fit bit-for-bit.
No automatic per-unit parameter switching is permitted.

---

## 3. Work packages

### WP0 Contract (½ day)
- Write `documentation/V3_VDU_CASE_A_CONTRACT.md` (this §2 with every assumption numbered) and the frozen JSON
  fixture, same pattern as `tjl19-literature-cdu-v1.json` in the codex worktree.
- Freeze the joint CDU/VDU validation matrix and acceptance limits from §2.5 before characterization.
- Review (`V3_VDU_CASE_A_CONTRACT_REVIEW.md`). Gate: assumptions A1–A4 and the shared-parameter contract resolved.

### WP1 Property package `createcheme:vdu_lc36_dwsim` (1–2 days, the DWSIM pull)
1. Copy `scripts/dwsim/*` from `run/codex-worktrees/v3-literature-cdu` into this worktree (read-only source;
   they are untracked there) and track them.
2. Environment: `csc.exe` (.NET 4) + DWSIM 10.2.3 API for the characterization; a real Python 3 with numpy for
   `generate-v3-tjl19.py`-style generation and `verify_tjl_characterization.py` (the system `python` is the
   Microsoft Store stub; install CPython or use the DWSIM-bundled `libpython3.12` via a venv).
3. `source-assay-lc36.json`: fit Riazi's TBP distribution function to the 30–90 % points, extrapolate to 100 %
   (declared end point), cut every 25 °C from 412 °C (the 73.6 % residue cut point) to 552 °C, then 4 tail cuts to
   the end point; no light ends, no cuts below the residue cut point (**assumption A4**: sharp residue cut, no
   distillate tail in the residue). Bulk residue density from the crude density and the cut SG distribution the
   DWSIM estimator produces (declared).
4. Run `characterize-tjl.ps1` on it (Riazi-Daubert Tc/Pc, Lee-Kesler ω, PR78 ω refit to NBP, Rackett fit), verify,
   reload-check, hash manifest; generate `V3VduLc36DwsimPackage.java` (declared envelope **500 Pa–2 MPa**,
   298.15–900 K), register it in `V3PropertyPackageRegistry`, add the golden-value tests the property contract
   requires, expanded to the joint CDU/VDU matrix in §2.5. Include the proposed VDU feed flash and representative
   mixtures at CDU conditions using the same generated parameters.
5. Advisory evidence: `LOW_PRESSURE_VAPOR_PRESSURE_BIAS` with the per-cut PR78-minus-Maxwell-Bonnell saturation
   temperature at 13 kPa (from the `psat-check.mjs` method, re-run on the new cuts); `ESTIMATED_HEAVY_RESIDUE`
   as today. Revision `vdu-lc36-dwsim-10.2.3-r1`.
6. Second reader (W5 lesson): an independent PR78 check of two cuts' saturation pressure against the package.

Gate: package registered, joint property/mixture matrix passes with one parameter set, tests green,
`documentation/V3_VDU_PACKAGE_REVIEW.md`. If PR78 cannot meet the frozen joint limits, assess characterization
or model alternatives before claiming both services are qualified.

### WP2 Vacuum admission and the first solves (1–2 days; experiments allowed from here)
1. Pressure lane: replace the linear `dwsimPressureSteps` for targets below 50 kPa with a geometric schedule
   (150 → 100 → 60 → 35 → 20 → 13 → target kPa, halving when a rung fails, bounded), behind a formulation
   revision bump for the low-pressure lane only; τ = 0 CDU digests unchanged (test).
2. Race two starts on the frozen case, probe-first (`build/pkgcmp/VduProbe`): (a) cold start at 10.6 kPa from the
   feed-flash initializer, (b) the geometric pressure continuation with the existing steam → heat → draw ramps.
   Record time, rung count, terminal failure codes. Pick one; keep the other as a recovery path only if it wins
   somewhere.
3. Overflash: new `V3CalculatedQuantity.OVERFLASH_LIQUID_FLOW` (liquid leaving the tray above the feed tray) and
   an `OVERFLASH` audit family (advisory band, never a rejection).
4. Precondenser audit: node-0 duty magnitude reported as `PRECONDENSER_DUTY` with the tray-1 temperature; no
   rejection.
5. Trace flows: the residue cuts above the wash tray are the structural trace case; rely on the per-phase truncation
   and support refresh; record what the mask does per rung. Any new stall is analysed with the existing
   trace-spike tooling before any solver change.

Gate: the frozen case solves to a published MESH state under unchanged tolerances, or a root-caused reason why not.
`documentation/V3_VDU_CASE_A_SOLVE_REVIEW.md`.

### WP3 Qualification against the literature (2–3 days)
1. Comparison table per §2.4 with the bands; sensitivities: feed tray 6 vs 7, top pressure 9.6 / 10.6 / 11.6 kPa,
   PA split RETURN_TRAY vs UNIFORM, steam 2 vs 3 lb/bbl.
2. Preset `vduLiteratureInput()` in the block entity and `V3VduLiteraturePresetTest` (same shape as
   `V3LiteraturePresetTest`): SUCCESS, audits accepted, yields inside band.
3. Findings doc `documentation/V3_VDU_CASE_A_QUALIFICATION.md`; if a band is missed, attribute it (K-value bias,
   layout assumption, thermo method) before deciding whether WP5 is worth doing.
4. Any property revision made during qualification must rerun the joint matrix and CDU regressions. Separate
   same-model implementation agreement from independent property and column-reference agreement.

### WP4 Game surface (1–2 days)
- Preset switch on the calculator (literature CDU / literature VDU); product stream names LVGO / HVGO / vacuum
  residue / vacuum overhead; the Heat tab already carries the pumparounds; NBT/wire carry `packageId` as a string,
  so no schema bump. GUI verified through the minecraft-mod-mcp bridge (user rule).

### WP5 Optional: shared CDU/VDU property revision
- Only if WP3 attributes a miss to the vapour-pressure bias: two-point refit of ω and Tc per cut to the NBP at
  760 mmHg and the Maxwell-Bonnell point at 10 mmHg, as revision `-r2` with its own golden values; and/or a
  DWSIM Grayson-Streed K-value cross-check helper. Not a default. Two anchors are fitting constraints, not
  sufficient validation: the revised set must pass §2.5, including CDU states, density and caloric properties.
  A VDU improvement that fails CDU qualification is not accepted. If PR78's current form is inadequate, assess
  a consistent characterization/model revision rather than unit-specific fits or a separate K-value correction
  disconnected from the energy model.

Order: WP0 → WP1 → WP2 → WP3 → WP4 (WP5 conditional). Total 6–10 working days plus reviews.

---

## 4. Risks and how they are handled

| Risk | Handling |
|---|---|
| PR78 K-values 14–25 % high at 13 kPa vs BK10-class methods → yields high / temperatures low against PRO/II | Wide bands (§2.4), bias published as advisory, WP5 held in reserve |
| Residue-only sharp re-cut (A4) removes the distillate tail a real CDU leaves; LVGO yield sensitive to it | Declared; the LVGO/HVGO split is a rate spec, so only temperatures and overflash feel it |
| Tail extrapolation beyond 90 % TBP sets the residue cuts' NBP and ω (2–3.6) | Declared end point; those cuts have K ≈ 1e-4–1e-7 at column conditions, products insensitive |
| Trace-flow degeneracy of residue cuts above the wash tray | Truncation lane is load-bearing; probe-first; trace-spike root-cause tooling exists |
| Pressure lane from 150 kPa to 10 kPa through 15× in pressure | Geometric schedule with halving; cold start raced as the alternative |
| Top branch flips between TWO_PHASE and VAPOR_ONLY as the 0.2 m³/h condensate appears/disappears | Both branches are legal with zero reflux; the transition machinery exists; audit reports which one published |
| Steam is small (12.7 mol/s) and the sump is the only wet node | Existing steam contract; superheat rule satisfied at 150 °C |
| Python environment for the verification scripts | Prerequisite step in WP1; CPython install, not the Store stub |

## 5. Not in scope

No side strippers (already excluded), no ejector/cracking/coking gameplay (D8, later), no whole-crude
characterization or literature CDU for the light crude (decision 3 alternative), no changes to the TJL packages or
the CDU lanes, no efficiencies or D1160 specifications.

## 6. Decisions embedded here that the maintainer may want to overturn

- A1–A4 (top pressure and drop, feed tray, steam temperature, sharp residue cut).
- Shared CDU/VDU parameter viability (§2.5) is an explicit requirement, not an optional working assumption.
- K-value policy: PR78 as characterized with the bias published (WP5 only on evidence).
- WP2 experiments start only after WP0 and WP1 are complete and reviewed.

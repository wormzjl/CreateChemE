# Review: five-heavy-fraction regrouping plan

Date: 2026-09-17. Reviewer: Claude (plan review, high effort; no production code changed).

Scope: `research/crude-regrouping/PLAN.md` (dated 2026-09-17, "planning only") with its companion
`README.md`, `select_viscosity.py`, `viscosity-selection.json`, checked against tracked code at
`c5c8af3` (main == worktree `claude/crude-regrouping-plan-review-28f25a`) and the ignored research under
`research/`. Numbers in F5 were recomputed from
`research/viscosity-temperature-plots/calibrated-rheology/calibrated_results.json` with Node, because
`python` on this machine's PATH is only the WindowsApps stub (see F14).

Part 1 reviews the plan as written. **Part 2** (added the same day on the user's direction) audits what the
framework needs for regular compound additions and several neural models, and how that reorders the plan.

## Verdict

The plan is well structured: honest scope limits, gates per stage, classical-before-neural ordering, no
tolerance weakening, no silent legacy aliasing. **Every checkable statement it makes about the current code
is correct** (list below). It is not yet ready to hand to an implementer, because five decisions that
determine the outcome are left open or rest on a wrong premise:

1. There is **no Tia Juana source distribution to rebin from** - the "hash-matched DWSIM input" is 13
   already-lumped pseudocomponents with estimated boundaries (F1).
2. **Existing worlds with fluid data will abort at server start** after the basis change; the plan treats
   save handling as a final check, not a work package (F2).
3. The **default/literature column presets are molar** and derived from the current MWs; the plan does not
   say how they are re-derived, and they sit next to a known feasibility wall (F3).
4. **91 of 197 test classes** are coupled to the current data; only the neural fixtures have a migration
   rule (F4).
5. The viscosity family has **no quantitative acceptance criterion**; the research accuracy figure does not
   apply to the mixing rule the plan keeps (F5).

Resolve F1-F5 in the plan text (each is a decision plus a gate, not a redesign), then it is sound to execute.

## Verified correct

| Plan statement | Evidence |
|---|---|
| PC07/H1 boundary 651.0249814474507 K = 377.8749814474507 C | `components/tjl19_pc08.json` `lower_kelvin` |
| Nine production packages; cdu17 is a distinct 16-component set (3 exact + C4 lump + 12 cuts) | `materials/packages/*.json` |
| cdu17 PC09 spans 353.535-406.865 C and crosses the boundary; eight lower cuts | `components/cdu17_pc09.json` |
| Counts 20 -> 19, 19 -> 18, 21 -> 20, cdu17 16 -> 18 | package files; arithmetic |
| Reader hardcodes 20 / 74 / 96 / 85; new values 73 / 95 / 81 / 180 | `V3AnchorTransformerInitializer.java:73-110`, `V3GeneralNeuralFeatures.java:15-16` (54 + c, +22 local), `V3FactorizedNeuralFeatures.java:15` (4c + 5); bundled F0 is `anchorLayout: full` |
| Reader accepts exactly one package id + fingerprint | `V3AnchorTransformerInitializer.java:165-167`; F0 pins `createcheme:tjl20_methane` |
| Stage counts 2-64; 50 kPa package floor | `:168`; `pressure_min_pascal: 50000` in every package |
| Campaign settings (10 workers, 30 s, 2 s neural, 16 base iterations, no overlap), three seeds, TF32 off, stage-count folds, two reversed blocks | `documentation/V4_TRANSFORMER_TRAINING_GUIDE.md:63,84-86,112,398,517` |
| `fluidScienceTest`, `fluidRuntimeTest`, `runFluidGameTestServer`, isolated run id, conditional `tools/development.gradle` with `generalNeuralExperiment` | `build.gradle:89-96,259-268,420`; `tools/development.gradle:31` |
| Viscosity screen: 7 temperatures, 13 cuts, scores 0.01266 / 0.18895 / 0.22392 / 1.55683 / 2.18774, Dalia heavy cut 2.525 Pa s at 100 C | `viscosity-selection.json`; recomputed |
| Liquid mixture rule is logarithmic (mole-weighted) | `MixtureViscosity.java:116-128` |
| `research/crude-assays/source-data.json` and `research/viscosity-tools/source-feed.dwxml` exist | directory listing |

Extra pin the plan should name (not wrong, just implicit in "audit every shape"): the parameter-storage check
`83800 + (inputs - 96) * 64` at `V3AnchorTransformerInitializer.java:105` becomes
`83412 + (inputs - 95) * 64` = **88,852** parameters for the full layout (180 inputs); the compact layout
would be 102 inputs / 83,860.

## Findings

Severity: CRITICAL = blocks users or invalidates the premise; MAJOR = outcome-determining decision missing;
MINOR = wording, inventory or robustness.

### F1 (CRITICAL, CONFIRMED) - Tia Juana has no source distribution to "rebin"

Plan 2.3: "Rebin Tia Juana source characterization from the hash-matched DWSIM input and its source feed
distribution"; 2.4: "Prefer the original DWSIM characterization API".

`research/viscosity-tools/source-feed.dwxml` contains 13 pre-characterized hypothetical components
`TJL_PC01..13` and a material stream - no TBP/D86 curve, no assay object (grep for
assay/curve/tbp/distill: zero elements). The production cut boundaries are `"derivation":
"adjacent_nbp_midpoints"`, `"estimated": true`, and `MATERIALS.md:51` says such boundaries are "labels, not
recovered assay boundaries". Every new boundary except 377.875 C falls **inside** an old lump:

| New boundary | Falls inside old lump |
|---|---|
| 450 C | PC09 435.8-513.6 |
| 550 C | PC10 513.6-609.5 |
| 650 C | PC11 609.5-730.5 |
| 750 C | PC12 730.5-874.9 |

So each of H1-H5 is assembled from fragments of two old lumps, and the split inside a lump is an assumption
the plan never states. This covers 46.0 % of the Tia Juana feed mass (18.1 mol %), and Tia Juana is the
default preset, the fluid network's crude basis, and the basis whose H1-H5 property records every other crude
shares. The DWSIM characterization API cannot be "re-run" because its input (a distillation curve) is not
retained. The same applies to cdu17 (12 KL-1976 lumps).

Fix direction - the plan must pick and document one reconstruction, e.g.:
- cumulative mass (and standard volume) versus temperature through the 13 lump boundaries with a monotone
  interpolant (PCHIP), SG(Tb) and MW(Tb) interpolated through the lump NBP points, then integrate over the
  new ranges; H5 from the integrated tail mean, never a midpoint;
- hard constraints: total mass and standard volume conserved exactly per crude; PC01-PC07 amounts unchanged;
- a sensitivity gate: repeat with a second interpolant (piecewise-linear) and report the spread of H1-H5
  NBP/MW/SG and of the default-preset product yields - if the spread is comparable to the change the
  regrouping is meant to achieve, say so;
- state that the five producer crudes are different: they do have a transcribed TBP to 590 C, and the new
  450/550 boundaries align with their source cuts, which is the real justification for the round grid.

Also decide and write down whether exact lumping on old boundaries (merge PC12+PC13 only; no intra-lump
assumption, exact mass/mole/volume conservation) was considered and why the round grid wins.

### F2 (CRITICAL, CONFIRMED) - existing worlds with fluid data abort at server start

Plan 2.7 asks for "an explicit reset/reselection requirement" and defers remapping; 6 lists "explicit
handling of obsolete parcels/results" as a check. Today:

- `FluidCheckpointCodec.java:139` throws `"Saved properties require explicit migration"` when the saved
  `propertyRevision` (which embeds revision + fingerprint, `HydrocarbonModel.java:61`) differs.
- `FluidSavedData.java:88-99`: the failed read is deliberately not treated as an empty world - it throws
  `"Existing fluid authority could not be read; refusing to replace its inventories"`.
- `CreateChemE.java:238,250-253` rethrows from server start. **The world does not open.**

The only bypass is a hardcoded allowlist of two complete revision strings
(`compatibleAmbientExtension`, `FluidCheckpointCodec.java:173-179`), added in `ea780ea` for exactly this kind
of change. A live `/reload` is gentler (`FluidPropertyReloadGuard` -> HOLD with a player-visible status), but
a restart is not.

The column block entity is better but still not what the plan promises: removed component ids make
`V3MaterialInputs.migrate` throw, which `ColumnCalculatorV3BlockEntity.java:294-302` turns into a silent
reset to `freshInput()` with `detail = "CORRUPT_PERSISTED_STATE"` - the same string as genuine NBT
corruption, not the existing `INCOMPATIBLE` status, and the player's edited feed is replaced without a
reselection prompt. The 2026-09-08 cdu17 retirement already built the right pattern ("detection happens
before decoding the incompatible component axis ... reports that recalculation is required",
`documentation/V3_CDU17_RETIREMENT_REVIEW.md`).

Unfingerprinted positional state will fail with misleading messages rather than a basis message:
`FluidDeviceSpec.java:12`, `WorldTopologyLedger.java:22,26`, client packet `FluidNetwork.java:31` all test
only `length != 22`; pending transfers and buffers are decoded against their own saved reference and never
compared to the model (`FluidCheckpointCodec.java:153-155`).

Fix direction - add a work package between 2 and 4 with one explicit decision:
- (a) mass-conserving remap of 22-wide arrays through the same old-lump -> new-cut allocation matrix as F1,
  energy re-derived at preserved temperature; or
- (b) load obsolete islands into a held "basis retired - drain or reset" state with a player-visible status
  and an operator command; inventories kept on disk until discarded; or
- (c) keep refusing to start, on purpose, with a message naming the basis change and the remedy.
Whichever is chosen: a recognized-obsolete-basis path for the column (status `INCOMPATIBLE`, not
`CORRUPT_PERSISTED_STATE`), removal of the dead `compatibleAmbientExtension` hashes, a basis/fingerprint on
device specs and the ledger instead of a bare length, and a GameTest that opens a pre-change checkpoint
(`src/test/resources/fluid/mcp-*-checkpoint.json` are ready-made fixtures).

### F3 (MAJOR, CONFIRMED) - molar preset constants are derived from the current characterization

`ColumnCalculatorV3BlockEntity.java:55`: `LITERATURE_FEED_MOL_PER_SECOND = 737.6996333000835` is
"100,000 bbl/day of Tia Juana Light" converted with today's MWs and standard densities; `:58`
`DEFAULT_FEED_KMOL_PER_HOUR = 2_610.7`; `:432-433` side draws 491 / 515 / 165 kmol/h; the assay itself is on
a **mole** basis (`assays/tjl20.json`, `tjl19.json`). Re-characterization changes pseudomoles of 46 % of the
feed mass. If the constant is kept, the feed is no longer 100,000 bbl/d and the light-end molar flows rise by
renormalization; if the molar draws are kept, their share of the molar feed changes. Earlier work showed this
case sits beside a liquid-supply wall (draws are 45.6 % molar of feed in the thesis; convergence depends on
the pumparound arrangement), so a few percent matters for the 4 gate "all declared gameplay/default cases
pass strict acceptance".

Plan 2.5 ("preserve total feed mass ... report the change in pseudomoles") is the right principle but never
reaches these constants. Fix direction: state that volumetric feed (100,000 bbl/d; cdu17 `volume_scale`
662.464) is the invariant and the molar totals are re-derived; state whether thesis draw rates stay molar
(literature-faithful) or are converted to preserve mass/volume yield; add a literature gate - the pins in
`V3LiteraturePresetTest` (naphtha draw 699.4 kmol/h +/-1, dew-point ratio 1.162 +/-0.02, duties
-12.84 / -41.93 MW) are re-measured and the shift is reported against the thesis values, not just re-pinned.

### F4 (MAJOR, CONFIRMED) - no test-suite migration work package

91 of 197 tracked test classes reference `tjl19` / `tjl20` / `cdu17`. The plan gives a rule only for neural
fixtures (5) and says oracles stay "unchanged" (4). Categories the implementer will hit:

- **Bit-exact parity with frozen Java tables**: `V3CatalogParityTest.java:9-32` against
  `V3Tjl19PropertyPackage`, `V3Tjl20MethanePropertyPackage`, `V3Cdu17TiaJuanaPackage` - must be regenerated
  wholesale or the test's meaning redefined.
- **Golden vectors**: `CrudeAssayConversionTest.java:37-43` (five crudes, 20 entries, 1e-14) with
  `crude-assay-conversion-fixture.json`; `dwsim-viscosity-api.json` / `dwsim-ambient-viscosity-api.json`
  (DWSIM API goldens for PC01-13 - these lose their referent entirely once the family is Dalia-derived);
  405 decoded-seed digests and `F0_SHA256` in `V3BundledTransformerPromotionTest.java:35,101-123`.
- **Regression pins**: Newton iteration counts in `V3ConvergenceClosureTest.java:42-48`, checkpoint ceiling
  `V3PumparoundCalculatorTest.java:355-360`, stalled-state and wet-boundary fixtures.
- **Structural widths**: 38 test files hardcode `new double[20|21|22]` or indices 20/21 (nitrogen/water),
  mostly under `science/fluid/network`, `science/fluid/solver`, `runtime/fluid`, plus
  `src/fluidGameTest` (`FluidServerBenchmark`, `FluidModuleGameTests`, `MaterialReloadGameTests.java:45`).
- **Literal ids**: `MaterialCatalogTest.java:27-56`, `ViscosityTest.java:29-30`,
  `FluidPropertyReloadGameTests.java:24` (all PC07 - survive), `V3TraceFloorSupportTest.java:37`
  (`tjl19_pc09` - does not).
- **Independent and unaffected** (about 7 classes): Holland 3-2, manufactured flash, sparse LU/Newton,
  NRTL fixture, 2-component cold-core worker.

Fix direction: add a stage between 2 and 4 that classifies every coupled test as
literature oracle (re-measure and report shift) / regression pin (re-pin with recorded before/after) /
structural (derive width from the package, do not replace 22 with 21) / obsolete (delete with reason), and
make "suite green" part of the 2 gate. Replacing literals with `package.components().size()` is worth doing
once here so the next basis change is cheap.

Matching main-source inventory for plan 2.7 (the plan's generic sentence is right; these are the sites):
`FluidDeviceSpec.java:12,18,19`, `WorldTopologyLedger.java:22,26`, `FluidNetwork.java:31`,
`FluidPresetCatalog.java:30` (+ comments :12, :38-40), `FluidDeviceScreen.java:21,22,70,75,127,138`
(**:138 renames only `tjl19_pc*` to "Crude cut N" - `crude_h0N` would display raw**),
`MaterialRuntime.java:23`, `en_us.json:28-40`, `materials-index.json`, alias maps `TJL_PC08..13` in eight
package files, `MATERIALS.md:42`.

### F5 (MAJOR, CONFIRMED by recomputation) - viscosity family has no acceptance criterion

The README's 0.874 % / 3.87 % figures were obtained **with four fitted Grunberg-Nissan coefficients per
crude**. The plan drops those (correctly, for a shared family) and notes the old scores "will not carry
over", but never states what error replaces them or what result would reject the choice. Recomputed on the
existing 13-cut curves with the production rule (log-mole average, no interaction term), ratio
predicted / assay-reported dynamic viscosity:

| Crude | Whole 20-50 C, Dalia family | Residue 50-100 C, Dalia family | Whole, current production | Residue, current production |
|---|---|---|---|---|
| Dalia (donor) | 0.50-0.55 | 0.72-0.82 | 0.21-0.27 | 0.16-0.26 |
| Upper Zakum | 0.65-0.74 | 1.10-1.44 | 0.42 | 0.32-0.34 |
| Bonga | 0.60-0.61 | 0.30-0.57 | 0.31-0.39 | 0.08-0.26 |
| WTI Light | 0.73-0.74 | **2.32-4.94** | 0.49-0.57 | 1.08-1.37 |
| Cold Lake Blend | **0.04-0.11** | **0.03-0.11** | 0.02-0.06 | 0.01-0.03 |

Reading: the Dalia family is better than today's DWSIM family on 8 of 10 rows, so the direction is right.
But (i) even the donor's own whole-crude viscosity comes out at half the measured value - that is the
mixing rule, not the family; (ii) WTI residue regresses from about 1.2x to 2.3-4.9x; (iii) Cold Lake stays
10-30x too thin, which matters for a pipeline/pump game loop. A mass-weighted log rule is not a fix
(6-15x too thick for the donor) - checked.

The mole-weighted result depends on the new MWs from section 2, so family and characterization are coupled
and the check has to run after the MWs are frozen.

Also: with five donors, two of them outliers, the pointwise median **is** one of the three central donors at
every point. Dalia's 0.0127 means "Dalia is the median almost everywhere", not "Dalia agrees with a consensus
to 1.3 %". It is a valid tie-break between three near-equivalent donors (heavy cut at 100 C: 2.37 / 2.52 /
2.87 Pa s); it should not be quoted as agreement. The 40-150 C window was not varied.

Fix direction: put the table above (recomputed on the new grid) in the plan as the accepted baseline; add a
donor round-trip gate through the production lookup route (Dalia assay -> Dalia whole/residue/source-cut
values, numbers recorded); state a rejection rule (for example: not worse than the current family on the
whole-crude points of every crude, and the WTI residue regression explicitly accepted by the user).

### F6 (MAJOR, CONFIRMED) - fluid ambient floor is 293.15 K, not 298.15 K

Plan 3: "Cover the existing admitted operating range (including 298.15 K)". The fluid model admits
**293.15 K**: `HydrocarbonModel.java:19` `AMBIENT_MINIMUM_TEMPERATURE`, applied only when a bundled record's
minimum is exactly 298.15 K (`:38-39`); every current liquid table starts at 293.15 K and ends at 900 K;
`MixtureViscosity.java:99-100` throws `PROPERTY_UNAVAILABLE` below a liquid table's minimum. A new family
tabulated from 298.15 K makes a 20 C generator or reservoir fail. The "fluid-only continuation of the
bundled 25 C caloric fits down to 20 C" was qualified for the old Cp records only. Fix: require
293.15-900 K liquid tables (same domain as today) and re-check the Cp/enthalpy continuation on
293.15-298.15 K for the five new records; add 293.15 K to the 3 gate.

### F7 (MAJOR, PLAUSIBLE) - which crude defines the shared H1-H5 property records?

All six crude packages reference the same property records (`createcheme:tjl19_tjl19_pcNN`; evidence string
`SHARED_COMPONENT_SURROGATE`). Plan 2.4 says "weighted source moments" without saying whose. The 750 C+
content differs by two orders of magnitude between crudes (PC13 alone: WTI 0.03 %, Dalia 0.79 %, Tia Juana
1.41 %, Cold Lake 1.88 % by mass) and the within-cut mean of an open cut depends on each crude's tail.
Fix: state that H1-H5 are Tia Juana-derived (consistent with today) or pooled with stated weights, that the
producer crudes remain surrogates, and add a sanity gate against the one independent datum available -
assay-reported 370 C+ residue MW and density (Dalia reports 528 g/mol, 963.2 kg/m3).

### F8 (MAJOR, PLAUSIBLE) - rebinning cdu17 costs more than it gains

- It invents resolution: 3.5 source lumps (upper PC09, PC10, PC11, PC12) become five cuts plus a 24 K sliver
  (353.5-377.9 C, about 3.5 vol %).
- It gains no basis compatibility: lower cuts and the C4 lump still differ (18 vs 19), cdu17 is not used by
  the fluid network at all, and the preset is labelled "Tia Juana pilot (legacy)".
- It breaks the one thing cdu17 is good for: `V3CatalogParityTest` ties production cdu17 bit-exactly to the
  fixture that `V3CharacterizationOracleTest` re-derives independently from Kesler-Lee 1976 (hard-fails
  unless exactly 12 vectors), and `V3Cdu17TiaJuanaPackageTest.java:99-102` closes the ACS-2018 bulk density
  867.6 kg/m3 to 1e-9. "Independent literature fixtures remain separate" means production cdu17 stops being
  validated by them.
- History: cdu17 was already retired once (2026-09-08 review) and came back as a legacy preset.

Recommendation: exclude cdu17 from the thermodynamic regrouping (apply only the viscosity family by boiling
range), or retire it again. If the reason to keep it in scope is common heavy-lump ids for the later
reaction work, write that reason into the plan.

### F9 (MINOR-MAJOR, CONFIRMED) - neural eligibility across six packages: prefer a physics fingerprint

`MaterialCatalog.java:245` hashes `model, ids, properties, interactions, nrtl, assays, water, T/P range`.
Because **assays are included**, six numerically identical packages have six fingerprints, and any assay
correction later voids the model for that package. The plan's manifest of id/revision/fingerprint pairs
works but asserts equivalence instead of proving it. `FluidPresetCatalog.java:27` already contains the right
predicate (components, properties, interactions, water, viscosity fingerprint equal). Recommendation: expose
a physics-only fingerprint on `Package` (everything except assays and the id) and have the model pin that;
the column request carries its own feed vector, so the assay is not an input to the solve.

Related: the envelope test is a per-feature box (`globalMin` / `globalMax`, `:177-181`). With six crudes and
blends, the box over 19 feed fractions admits compositions far from any training point. Decide whether the
envelope stays a box or becomes a convex-combination check, before labels are generated.

Note for the data freeze: viscosity is **not** in the package fingerprint (it has its own
`viscosityFingerprint`), so a late viscosity edit does not void column labels but does change the fluid
revision; any edit to a property record, interaction or assay after the 4 gate voids the corpus. Say "data
freeze at the 4 gate" explicitly.

### F10 (MINOR-MAJOR) - bootstrap cost is unbounded

From scratch with no F0 salvage means generation-0 labels are classical successes only (on the cleaned F0
set that was 110 of 330, versus 182 neural-first), so the first model learns the easy region and the hard
cells need several label-acquisition generations. The plan has no generation count, stop rule or effort
estimate. Option worth an explicit allow/deny: map old-basis converged profiles onto the new basis with the
F1 allocation matrix and use them as **initial guesses** for the native solver in a research-only entry
point; only strict native convergence under the new physics produces a label, so this is neither
relabelling old arrays nor using predictions as targets. Register it before any measurement and keep the
root-disagreement quarantine.

### F11 (MINOR) - interim state between stage 2 and stage 5

After stage 2 the bundled F0 artifact can never be eligible; the default `LNN_FIRST` silently runs classical
and F0-pinned tests fail until stage 5. Define: one integration branch; at the stage-2 commit delete the F0
artifact and its pinned tests and exercise the existing `V3NeuralInitializer.UNAVAILABLE` path
(`V3NeuralModels.java:38-45`); suite green at every gate; nothing merges to main before stage 6.

### F12 (MINOR) - one shared curve for H3/H4/H5 has a gameplay consequence

With a shared curve and a log-mole rule, residue viscosity is independent of cut depth: a 750 C+ residue is
exactly as viscous as a 550 C+ one. The plan's refusal to invent a gradient is defensible; record the
consequence so it is not rediscovered as a bug when VDU or visbreaking work starts.

### F13 (MINOR) - wording that does not match the code

- "six crude presets" / "all crude generators" (3, 6): the fluid network has **three** crude presets
  (Tia Juana, WTI, Cold Lake; `FluidPresetCatalog.java:24`); Dalia, Bonga and Upper Zakum are column-only.
  The donor crude is therefore not pumpable in game - worth a sentence, or add it.
- README "do not extend `MaterialQuality`": no such class or concept exists anywhere in the repository.
- Package names in the plan are file names; the ids differ (`tjl19.json` -> `createcheme:tjl19_dwsim`,
  `tjl20.json` -> `createcheme:tjl20_methane`, `cdu17.json` -> `createcheme:cdu17_tjl_acs2018`). List ids,
  since fingerprints and saves key on them.
- `.gitattributes` still pins a `v3-general-gen3-factorized.json` that no longer exists and `tools/...`
  paths that are now ignored; `src/main/resources/data/createcheme/neural/*.json -text` already covers a new
  artifact.

### F14 (MINOR) - tooling preflight is not reproducible as written

`python` / `python3` on PATH resolve to the WindowsApps stub (exit 49); no `.neural-venv` exists in the main
checkout or this worktree. README's "configured Python runtime" and plan 5's "verify ... Python
dependencies" should name the interpreter path and the environment file, otherwise the first reproduce step
(`python research/crude-regrouping/select_viscosity.py`) fails on this machine.

## Suggested order for revising the plan

1. F1 - choose and document the Tia Juana / cdu17 reconstruction method (or aligned lumping); decide F8
   (cdu17 in or out) and F7 (whose moments) in the same edit, since they share the allocation matrix.
2. F2 - decide the save policy (remap / held state / intentional refusal) and add it as a work package with
   a pre-change checkpoint GameTest.
3. F3 - declare volumetric feed the invariant, re-derive molar constants, add the literature re-measurement
   gate.
4. F4 - add the test classification stage and the concrete width/id inventory.
5. F5 + F6 - put the uncorrected-blend table, the donor round-trip gate, the rejection rule and the
   293.15-900 K domain into section 3.
6. F9-F11 - physics fingerprint, envelope rule, data freeze, bootstrap bound, branch policy.
7. F12-F14 - wording and preflight.

---

# Part 2 - readiness for regular compound additions and several neural models

User direction, 2026-09-17 (given during this review): *the framework must be ready for regular addition of
new compounds, and the distillation system must support different neural models for different compositions.*
This part audits what that needs beyond the regrouping plan. Same code state (`c5c8af3`).

## Summary

The **catalog layer is already close**: records are JSON, datapack-overridable, validated as a whole and
published atomically; column saves are keyed by component id; the column wire format requires exact basis
equality; the solver eliminates zero-feed components exactly (`V3ActiveComponentBasis.java:34-57`), so a
compound that is absent from a feed costs nothing numerically.

Four layers are **not** ready, and the regrouping plan would touch every one of them once for 22 -> 21 and
again at the next addition:

| Layer | State today | Blocking? |
|---|---|---|
| Persistence | no additive migration anywhere; fluid worlds abort on any property change | yes |
| Fluid network basis | welded to "20 hydrocarbons + nitrogen@20 + water@21 = 22" | yes |
| Neural initializer | one artifact constant, fixed shapes 20/74/96/85, keyed on package id + full fingerprint | yes |
| Thermo scope | PR78 hydrocarbons + one immiscible water phase; NRTL parsed but has no solver | for polar / aqueous species |

Plus tooling and GUI gaps (hand-maintained index, positional assays, preset enum, unscrolled lists).

## Three primitives everything else should be built on

**P1 - per-component and sub-basis fingerprints.** Today there is one package hash
(`MaterialCatalog.java:245`) that also covers assays. Add (a) a hash per property record (the `Property`
record already has value equality - `HydrocarbonModel.java:38-39` relies on it) and (b) a *sub-basis physics
fingerprint* over an ordered id list: EOS model, those property hashes, the kij sub-matrix, the water model,
T/P range. Keep the package fingerprint for whole-package identity.

**P2 - one additive-migration rule, used by every persisted array.** A saved array that carries its own
component-id axis is valid against the current basis iff every saved component with a nonzero amount still
exists with an unchanged per-component hash. New components fill with zero, vanished zero-amount components
are dropped, order follows the current package. Anything else is *non-additive* (the regrouping is one) and
goes to an explicit remap matrix or a held "basis retired" state (F2). Users: `V3MaterialInputs.migrate`
(today `:19` throws on any size difference), fluid islands / parcels / buffers
(`FluidCheckpointCodec.java:139-142,153-155`), `FluidDeviceSpec`, `WorldTopologyLedger`, client packets.

**P3 - widths and special positions derived, never literal.** Basis width from
`package.components().size()`; nitrogen and water located by id. Removes `22`, `n[20]`, `n[21]`.

## Work packages

### E1 (BLOCKER) - persistence that survives a growing basis
Implements P2; subsumes F2. Additions: device specs and the ledger gain an id axis (today bare `double[22]`);
`FluidPropertyReloadGuard` resumes automatically when a reload is additive-compatible instead of holding the
whole network (`:24-32`); remove the literal-hash allowlist `compatibleAmbientExtension`
(`FluidCheckpointCodec.java:173-179`); energy stays valid under an additive change because the sensible
reference has zero offsets and existing components' Cp records are unchanged (assert that, do not assume
it). GameTests: open a checkpoint saved on basis N with basis N+1 (additive, must resume) and with a changed
property record (must hold, not abort).

### E2 (BLOCKER) - fluid network basis generalization
- Literal width / indices: `FluidDeviceSpec.java:12,18,19`, `WorldTopologyLedger.java:22,26`,
  `FluidNetwork.java:31`, `FluidPresetCatalog.java:30`, `FluidDeviceScreen.java:21,22,37,70,75,127,136,138`.
- `requireNetworkExtendsCrudeBasis` (`FluidPresetCatalog.java:42-53`) asserts "crude basis + nitrogen and
  nothing else" with a zero nitrogen kij row. Generalize to: every preset package's components are a subset
  of the network package with equal property records and equal kij sub-matrix (P1); pad presets **by id**.
- `FluidMaterialCatalog.java:27-51` hardcodes the two package ids and the nitrogen rule; `MaterialRuntime.java:23`
  falls back to `createcheme:tjl20_methane`.
- Packet budget: `FluidNetwork.java:24` `MAX_JSON = 65536` grows with component count; measure at 40 and 64.
- Decide the network's basis policy: one growing global package (simple, every addition is an E1 migration
  for every world) versus per-island sub-bases (no migration for worlds that never see the compound, but a
  larger change). P2 makes the first workable; say which one is intended.

### E3 (BLOCKER) - neural model registry
What exists: a clean interface (`V3NeuralInitializer`, with `candidates()` already foreseeing "a bundle of
experts") and a safe `UNAVAILABLE` fallback. What is missing:

1. **Shape-generic reader.** Derive everything from `c = components.size()`: global `54 + c`, node
   `76 + c`, anchor/output `4c + 5`, joined full `(76 + c) + (4c + 5) + 4`, compact `(76 + c) + 3 + 4`,
   parameter count `67,267 + 65(4c + 5) + 64(55 + c) + 64(inputs + 1)` (gives 83,800 + 64(inputs - 96) at
   c = 20, matching today's check) instead of the literal `83800`
   (`V3AnchorTransformerInitializer.java:73-74,81-85,92-93,105,110,198-205,223`;
   `V3NativeAnchor.java:43` literal 85). Bound `c` by `V3ComponentBasis.MAX_COMPONENTS`.
2. **Registry instead of `ARTIFACT`.** `V3NeuralModels.java:24` is one classpath constant and
   `CreateChemE.java:205-206,235` parses one model at start. Replace with a manifest
   (`data/createcheme/neural/models.json`: artifact path, SHA-256, parameter count, decode rule, candidate
   rule, basis key) and a composite `V3NeuralInitializer` that dispatches to the first model whose
   `supported()` accepts the request, in manifest order; report the chosen `modelId`.
3. **Eligibility on the active sub-basis, not the package.** Today `supported()` needs
   `packageId` equality, full-package fingerprint equality and exact component-list equality
   (`:165-167`). Because zero-feed components are eliminated exactly, the numerical problem of a larger
   package with zeros is identical to the smaller basis. Key models on the P1 sub-basis fingerprint of their
   own component list; accept a request when every nonzero-feed component is in the model's list and the
   sub-basis hash matches; build features by gathering the feed onto the model's axis and scatter the decode
   back by id. Consequences: appending a compound to a package no longer voids existing models; the six
   crude packages qualify by construction (replaces the manifest in plan 5 and F9); `tjl19` can be served
   by the methane-inclusive model if methane = 0 is inside its envelope.
4. **Per-model decode and candidate rules.** `QUALIFIED_ZERO_PHASE_FLOOR_FACTOR` and `CandidateRule.SINGLE`
   are holder constants (`V3NeuralModels.java:21,49-51`) "of the loading caller, not of the artifact". With
   several models each is qualified under its own rule, so the rule belongs in the manifest entry.
5. **Reader factory by `featureRevision` / `modelType`.** `REVISION` is a single constant (`:24,70`). A
   factory lets an older-encoding model and a newer one ship side by side.
6. **Snapshot consistency.** Jobs pin the catalog at admission (`ProcessSolveServices.java:433-440`). If
   models ever become datapack-loadable, the registry must publish atomically with the catalog and be pinned
   the same way. Recommendation: bundled-only for now (`isBundledScience` already gates this), but load the
   registry through the same snapshot object so the later step is not a redesign.
7. **Manifest-driven qualification tests.** `V3BundledTransformerPromotionTest` pins one `F0_SHA256` and one
   405-line digest file (`:35,101-123`). Drive it from the manifest: per model a SHA, parameter count,
   parity fixture and decoded-seed digests; plus one registry test (no two models claim the same sub-basis;
   every declared basis resolves in the bundled catalog).
8. **Training tools parameterized by basis.** `tools/neural/train_transformer.py:24,37` hardcodes
   `inputs=96, outputs=85` and `nn.Linear(74, 64)`; the guide's paths, hashes and populations are F0's. One
   basis descriptor (component list + sub-basis hash + package list) should drive the generator probe, data
   preparation, training, export, parity and the model card; one campaign folder per model.
9. **Cost.** About 89k float64 parameters per model (0.7 MB heap, 2-3 MB JSON). Eager parse of every model at
   server start is fine for a handful; switch to lazy-on-first-eligible-request before it is dozens.
10. **Research item, not a blocker:** a composition-agnostic encoder (each component a token described by
    Tb / MW / omega / Tc / Pc, shared per-component output head) would let one model cover new pseudo-cuts
    without retraining. Worth a registered pilot after the registry exists; the registry is needed either
    way.

### E4 (MAJOR) - catalog and tooling gaps
- **`materials-index.json` is hand-maintained and unverified** (`MaterialCatalog.java:371`; `MATERIALS.md:291-293`).
  Generate it in Gradle or add a test that diffs it against the directory. Datapacks do not need it.
- **Assays are positional and must list every package component** (`MaterialCatalog.java:234-236`): one new
  compound in the crude basis means editing all six crude assays, the nitrogen assay and `tjl19`. Accept
  id-keyed sparse amounts (missing = 0).
- **Eight packages repeat the same component / property / alias lists.** Add a shared basis record (or
  `extends`) so a basis change is one edit. This alone would shrink the regrouping's stage 2.
- **Two mandatory fluid data files are classpath-only**: `dissolved_viscosity.json`
  (`MixtureViscosity.java:15,187`) and `liquid_calibration.json` (`HydrocarbonModel.java:116-127`), and their
  revision strings are literals in code (`MixtureViscosity.java:26`, `FluidCheckpointCodec.java:177`). Move
  both into property records so they are overridable, validated and inside `viscosityFingerprint`.
- **Supercritical gases** need a fake sub-ambient liquid table *and* a conditional-solute curve to avoid
  `PROPERTY_UNAVAILABLE` (`MixtureViscosity.java:90-105`; nitrogen does exactly this). Give the catalog a
  "dissolved gas" transport kind instead of repeating the trick for hydrogen, H2S, CO2.
- **One narrow compound shrinks every package that includes it**: package T range must lie inside every
  property's range (`:205`). The per-compound checklist must require 293.15-900 K coverage or state the
  package policy.
- **Only `shifted_polynomial_5` Cp with a 298.15 K reference** (`:163-165`). Fine, but ship the fitting tool
  and its acceptance check with the checklist.
- **`missing_interactions: "zero"` is silent.** Acceptable between hydrocarbons; for N2 / H2 / CO2 / H2S pairs
  it is a modelling decision. Emit an automatic advisory-evidence line listing assumed-zero pairs that involve
  a non-hydrocarbon.
- **A generic per-component qualification test** parameterized over every catalog component (finite PR
  a/b/alpha over the package range, Cp positive, enthalpy monotone, both viscosity phases positive and
  available over the admitted range, pure-component bubble point near NBP, liquid density near the standard
  value). Additions are then tested by existing, not by writing a test per compound.
- A written **compound-addition checklist** in `MATERIALS.md` (the table in this section's source audit:
  component, property, Cp type, pr78 block, T range, viscosity both phases, interactions, package + assays,
  index, lang, appearance, dissolved curve, calibration point).

### E5 (MAJOR, scope decision) - which compound classes the thermo layer can honestly admit
- `"Water"` is a magic id outside the indexed basis with its own IAPWS model and a separate immiscible free
  phase (`MaterialCatalog.java:70,92`, `MixtureViscosity.java:35`, `FluidThermodynamics.java:106,193-197`).
  **Nothing can dissolve in it.** H2S / NH3 / CO2 in sour water, amines, methanol, glycols are out of reach.
- Column thermo is PR78 only; NRTL packages parse but have no solver (`MATERIALS.md:5-6,319`).
- Fluid side: `HydrocarbonModel`, Wilson K-value initial guesses (`FluidThermodynamics.java:238-239`),
  state domain 273.16-600 K and 100-2e6 Pa (`:187`).
- Hydrogen needs care in a cubic EOS (very high reduced temperature; alpha-function extrapolation).

Recommendation: give components a declared class and make packages enforce it.
Class A hydrocarbons and pseudo-cuts - supported. Class B light inorganic gases (N2, H2, CO2, H2S) in PR78
with stated kij, no aqueous solubility - admitted with an advisory. Class C polar / associating / aqueous -
refused by `pr78` packages until a gamma-phi or CPA path exists. This keeps "regular additions" honest
without blocking on a new thermodynamic model.

### E6 (MAJOR) - GUI and presets
- Column package/assay choice is a Java enum (`ColumnInputPreset.java:11-17`) and "callers never supply
  compositions"; there is no per-component feed editor. A new package is unreachable without a code change.
  Make presets data records (package, assay, operating template).
- `ColumnCalculatorV3Screen.java:720-731` renders all fractions in one unscrolled column (12 px per row);
  beyond roughly 25 components it overflows. `FluidDeviceScreen` pages by literal 22 and shows raw ids
  except the `tjl19_pc` prefix special case (`:138`); use `MaterialName` and the cut descriptors.
- Hard caps to keep in view: 64 components (`V3ComponentBasis.java:10`), name cap `2*65`
  (`ColumnV3Network.java:602`), draws 3 / steam 2 / pumparounds 4 (`V3ColumnInput.java:33-35`).

### E7 (MINOR) - measure the practical component ceiling
Zero-feed elimination means cost follows *active* components, so additions absent from a feed are free. For
feeds that do carry more species, run the classical control at 25 / 32 / 40 active components on the
difficult cells before promising growth of the crude basis; the 30 s request and 2 s neural budgets were
qualified at 20.

### E8 - basis-agnostic tests
Same as F4: 38 test files hardcode 20/21/22 and nitrogen/water indices. Convert to package-derived widths
and id lookups once, during the regrouping.

## What this changes in the regrouping plan

The regrouping is the first client of these mechanisms, not a separate job:

- Do **P3 + E2 literals** and **E8** inside stage 2, so 22 is removed rather than replaced by 21.
- Make **F2's save decision** the first use of **P2**: the additive rule for future compounds, the explicit
  remap / held state for this non-additive change.
- Build the **E3 reader + registry** in stage 5 before training; the new 19-component model is registry
  entry number one, keyed on its sub-basis (P1), which replaces the plan's package manifest and resolves F9.
- **E4** shared-basis record and id-keyed assays before stage 2 would cut that stage's edit count roughly
  eightfold; index verification and the generic per-component test belong in the stage 2 gate.
- **E5** and **E6** are independent of the regrouping and can follow it.

Suggested order: P1 -> P3/E2 literals + E8 -> E4 (basis record, sparse assays, index check, generic test) ->
P2/E1 (with F2) -> regrouping stages 2-4 -> E3 -> regrouping stages 5-6 -> E6 -> E5 -> E7.

---

# Part 3 - check of the revised plan (PLAN.md saved 2026-09-17 17:07)

The revision records the user's decisions: five estimated fractions with sensitivity checks; production
CDU17 retired; one global Dalia mixture correction (option 4A); classical-only labels (6B); extensibility
framework last (5A); **no backward compatibility at this development stage**; whole pseudocomponent family
renamed `crude_pc01`..`crude_pc12`.

## Status of Part 1 findings

| Finding | Status in revision |
|---|---|
| F1 no Tia Juana source curve | Addressed (2.3): stated as estimated, PCHIP + piecewise-linear, mass/volume constraints, stop rule. Residual R4. |
| F2 worlds abort / saves | Decided: unsupported, reject explicitly, fresh worlds. Residual R5. |
| F3 molar preset constants | Addressed (2.8). Residual R3 (needs an old-basis baseline first). |
| F4 test migration | Addressed (new subsection), including "a test must not regenerate its own expected answer". |
| F5 viscosity acceptance | Changed approach (4A) with numeric gates. Residual R1 - the gate does not discriminate. |
| F6 293.15 K floor | Addressed (3, 6). |
| F7 whose moments | Addressed (2.4): Tia Juana, with producer residue density/MW check. |
| F8 cdu17 | Decided: retire. Residual R2 - 40 test classes depend on it. |
| F9 fingerprint / envelope | Addressed: explicit contract set now, physics sub-basis fingerprint in 7, composition-domain check. Residual R6. |
| F10 bootstrap | Decided: classical-only, finite registered design. Residual R7. |
| F11 interim state | Addressed (integration branch, F0 retired at the data change). |
| F12 shared 550 C+ curve | Addressed (3 gate wording). |
| F13 / F14 wording, Python | Addressed (package ids listed, three generator presets named, interpreter path given). |
| Part 2 E1 additive migration | Explicitly out of scope by user decision. E2-E4, E6 adopted in 7; E5 stated as a limit. |

## Residuals

### R1 (MAJOR, computed) - the 4A gate passes almost by construction, and the correction hurts two crudes
Dalia has six bulk points (whole 20/40/50 C, residue 50/60/100 C). The earlier study fitted four
coefficients to four of them and "held out" whole 40 C and residue 60 C - both interior temperatures of the
same two streams - and got 0.6 % and 1.0 %. A 10 % / 20 % gate on such points checks smooth interpolation,
not the mixing model. It will pass for almost any smooth form.

The informative evidence is what one Dalia-fitted correction does to the other crudes. Applying the
existing Dalia coefficients (0.700, 0.403, 0.073, 0.046) globally with the Dalia family on the current
13-cut grid, predicted / reported:

| Crude | Whole, uncorrected -> corrected | Residue, uncorrected -> corrected | mean abs ln ratio |
|---|---|---|---|
| Dalia (donor) | 0.50-0.55 -> 1.00-1.01 | 0.72-0.82 -> 1.00-1.01 | 0.454 -> 0.003 |
| Bonga | 0.60-0.61 -> 1.13-1.20 | 0.30-0.57 -> 0.41-0.71 | 0.718 -> 0.400 |
| Upper Zakum | 0.65-0.74 -> 1.23-1.47 | 1.10-1.44 -> **1.34-2.01** | 0.312 -> **0.407** |
| WTI Light | 0.73-0.74 -> 1.36-1.40 | 2.32-4.94 -> **2.88-6.80** | 0.793 -> **0.936** |
| Cold Lake | 0.04-0.11 -> 0.07-0.20 | 0.03-0.11 -> 0.04-0.14 | 2.842 -> 2.375 |

So the correction fixes the donor, helps Bonga and (slightly) Cold Lake, and makes WTI and Upper Zakum
worse; WTI residue ends 3-7x too viscous. WTI is one of the three pumpable generator presets.

Ask of the plan: (a) register the coefficient count against the six points (four coefficients leave two
degrees of freedom - say so, or use fewer); (b) make the corrected-versus-uncorrected table for all five
crudes a registered output with an explicit rule - either "accepted as is" signed off by the user with these
numbers in view, or a do-no-harm criterion on the non-donor aggregate; (c) keep the factorized O(c) form
the research code already uses (`model.py:97-110`) - `MixtureViscosity.liquid` is on the fluid solver's hot
path; (d) store coefficients, retention factors `h_i` and slopes `E_i` as catalog data inside the viscosity
fingerprint, and bump `log-liquid-wilke-v1`; (e) define `h_i` / `E_i` for exact chemicals,
conditional-solute gases, nitrogen and any future compound (the slope between 40 and 50 C is undefined for a
supercritical solute).

### R2 (MAJOR) - CDU17 retirement touches 40 test classes, including core numerics
`grep` finds cdu17 in 40 files under `src/test/java`: not only the literature cases (Sotelo 2019, Ledezma
A4/A6, KL-1976 oracle) but the PR kernel, derivative, root-precision, flash, Jacobian-assembler,
preconditioner, continuation and cold-start tests. Main code still uses it for the PILOT preset and as the
load-path default (`ColumnCalculatorV3BlockEntity.java:52,271,396-402,472`; `ColumnInputPreset.java:17,40`).
The plan's one paragraph ("retain ... as test-only references") does not say how a test runs a column on a
package that is no longer in the production catalog.

Recommendation: move the cdu17 JSON records to `src/test/resources` and load them through a test-scope
catalog overlay, so those 40 classes keep an **unchanged** dataset and their pins while production data
changes underneath everything else. Migrating them to the new basis in the same release (what the
2026-09-08 retirement did, at the cost of re-deriving many fixtures) removes the one fixed reference the
solver tests would otherwise have during the regrouping. Replace `defaultInput()` at `:271` with the new
default in the same step.

### R3 (MAJOR) - the old-basis baseline is a prerequisite, not "if useful"
2.8 wants gameplay defaults to "preserve intended physical draw yields and recalculate molar draws". The
mass/volume rate of a molar draw is only known from a converged old-basis solution (draw composition x MW),
and the plan keeps no legacy dataset. Section 4 still says "Record a compact old-basis behavior summary ...
if useful". Make it a stage-0 deliverable, captured before stage 2: for the default, literature and six
crude presets - feed mass and standard-volume rate, each draw's molar / mass / volume rate and mean MW,
distillate and residue rates, stage temperatures, duties, dew-point ratio, outcome class and iterations.
Store it under `research/crude-regrouping/` with the commit and fingerprints it came from.

### R4 (MINOR-MAJOR) - the sensitivity axis that matters is knot placement, not the interpolant
Both PCHIP and piecewise-linear interpolate the same knots: cumulative amount at the **estimated midpoint
boundaries**. Those boundaries are themselves a convention. Add a second reconstruction family that treats
each lump's NBP as its cumulative midpoint (cumulative at NBP_i = sum of lighter lumps + half of lump i) and
report the spread across both axes. If the two knot conventions move heavy-cut amounts more than the two
interpolants do, the interpolant sensitivity alone under-reports the uncertainty.

### R5 (MINOR) - consequences of "no backward compatibility" to write down
- Line 5 says obsolete state must not be "automatically erased", but the column block entity today resets an
  unreadable input to the fresh default with `CORRUPT_PERSISTED_STATE`
  (`ColumnCalculatorV3BlockEntity.java:294-302`). Either accept that for the column explicitly or switch it
  to the existing `INCOMPATIBLE` status.
- The fluid side already "rejects explicitly": the server refuses to start. Make the message name the basis
  change and the remedy (delete the fluid data file / new world), because the developer's own `run/` world
  will hit it first.
- "Remove obsolete compatibility allowlists" should name both: `compatibleAmbientExtension`
  (`FluidCheckpointCodec.java:173-179`) and the legacy `tjl20_methane` island-id migration
  (`FluidMaterialCatalog.java:32,42-51`). Regenerate `src/test/resources/fluid/mcp-*-checkpoint.json`.
- Section 7 keeps additive save migration out of scope. With one global network basis that means **every
  future compound added to the network package again makes existing worlds unreadable**. That is consistent
  with the decision; state it in section 7 so "ready for regular additions" is not read as save-safe. Storing
  an id axis in every current-format array (already planned) keeps the later option open without another
  format break.

### R6 (MINOR) - keep eligibility metadata outside the qualified weight bytes
Stage 5 qualifies an artifact whose manifest pins package id / fingerprint; stage 7 replaces that with a
sub-basis fingerprint in a registry entry. If eligibility fields live inside the SHA-pinned file, stage 7
changes the qualified bytes and every pin with them. Lay out the stage-5 artifact so the hashed blob holds
weights, normalization, encoding revision and component axis only; put package contracts, decode and
candidate rules beside it. Stage 7 then swaps the sidecar, not the model.

### R7 (MINOR) - define the no-promotion branch
Section 5 allows "no candidate passes"; with classical-only labels that outcome is plausible (the model
mostly sees what classical already solves, and promotion requires a replicated strict-count benefit).
Sections 7 and 8 assume a promoted model ("with this artifact as the first entry", "the new compatible
transformer ... is served through the final registry"). Add the branch: registry proven with fixture models
only, release ships classical, neural promotion reported incomplete. Also pre-register whether a
latency-only benefit with equal strict count is promotable.

### R8 (MINOR) - numbering collision in research cross-references
`crude_pc08`..`crude_pc12` reuse the numbers of `tjl19_pc08`..`pc12` with different ranges, and the DWSIM
names `TJL_PC08..13`, the notes under `research/crude-regrouping/notes/` and `viscosity-selection.json` all
say "PC08" for the old cut. The ids differ by prefix, so production is unambiguous; add a one-table
old-to-new mapping to the README and always write the prefix in new research output.

### R9 (MINOR) - literal 21 now, derived in stage 7
2.7 updates the fixed widths to the new numbers while the test subsection already derives widths from the
package. The main-code sites are few (`FluidDeviceSpec.java:12,18,19`, `WorldTopologyLedger.java:22,26`,
`FluidNetwork.java:31`, `FluidPresetCatalog.java:30`, `FluidDeviceScreen.java`), so deriving them in stage 2
costs little and avoids touching them twice. Optional; the chosen ordering is workable either way.

## Verdict on the revision

Sound to start once R1-R3 are written in: R3 has to happen before any data is replaced, R2 decides how much
of the test suite moves at once, and R1 is a decision about accepting worse WTI / Upper Zakum transport in
exchange for a correct donor.

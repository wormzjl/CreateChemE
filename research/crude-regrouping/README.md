# Crude regrouping: consolidated decision and research

Updated 2026-09-17. **Research consolidation is complete; scientific regrouping, retraining and final qualification are planned, not executed.**

Start with [the implementation plan](PLAN.md). This is the current decision; recommendations in older reports that retain the original component basis or require crude-specific transport parameters are superseded for this release.

## Decision

- Replace TJL PC08–PC13 with five fractions: 377.874981–450, 450–550, 550–650, 650–750, and 750°C+ (atmospheric-equivalent characterization temperatures). Keep the exact original PC07 upper boundary, not rounded377.9°C, in data.
- Rename the entire pseudocomponent family consistently to `crude_pc01`–`crude_pc12`. The first seven retain their original boiling ranges/thermodynamic data; PC08–PC12 are the five new heavy fractions. Exact chemical IDs remain unchanged; displays use localized NBP-range names. Regenerate affected records and retire CDU17's production package/preset, keeping independent reference tests. Discard superseded production datasets and weights; no backward compatibility, old-save migration, compatibility aliases or backup catalog is required.
- Use one **Dalia-derived liquid-viscosity family plus one global mixture correction**, fitted to Dalia whole-crude/residue measurements and applied identically to all crudes. The correction preserves pure-component limits; it is not a uniform multiplier. No per-crude coefficients or conditional wax/yield-stress model is promoted. Preserve 293.15–900 K liquid-table coverage with explicit extrapolation limits.
- First qualify the classical initializer, then generate **classical-only** labels/anchors and train a new transformer: no old-profile starting guesses or neural-assisted label salvage. Complete initial column/pipeline qualification, then build the compound-addition/multiple-model framework **last**, and rerun final verification.
- Tia Juana has no retained original TBP curve: reconstruct its intra-cut distribution explicitly, constrain mass/reference volume and test sensitivity. Its reconstruction defines the shared thermodynamic properties; producer assays retain their own source curves and tails.
- Defer appended elemental/SARA/wax/reactivity state, hydrocracking/thermal-cracking kinetics, and predictive gel/pour-point models. Keep their evidence here for later application; do not add their runtime schema in this work.

## Evidence and its limits

The updated [plan review](notes/CRUDE_REGROUPING_PLAN_REVIEW.md), Part 3, is retained with this collection. The plan now requires an old-basis numerical baseline before replacement, an unchanged CDU17 test-only catalog, explicit shared-viscosity transfer limitations, two knot-placement sensitivity families, immutable model payloads with sidecar eligibility, and an explicit no-neural-promotion branch. Non-donor viscosity errors remain an accepted approximation under the user's existing instruction; the old-grid review numbers are not new-grid validation.

## Old and new pseudocomponent names

| Old full ID | Old estimated range°C | Contributes to new full IDs |
|---|---|---|
| `tjl19_pc01`–`tjl19_pc07` | unchanged lower cuts | `crude_pc01`–`crude_pc07`, respectively |
| `tjl19_pc08` |377.875–435.783|`crude_pc08`|
| `tjl19_pc09` |435.783–513.588|`crude_pc08`, `crude_pc09`|
| `tjl19_pc10` |513.588–609.465|`crude_pc09`, `crude_pc10`|
| `tjl19_pc11` |609.465–730.508|`crude_pc10`, `crude_pc11`|
| `tjl19_pc12` |730.508–874.851|`crude_pc11`, `crude_pc12`|
| `tjl19_pc13` |874.851+|`crude_pc12`|

This is a characterization overlap map, not a save-migration matrix. Amounts within split old cuts require the registered reconstruction. Historical `TJL_PC08`/bare `PC08` in old reports means the old cut; all new scientific output must use the full `crude_pc08`-style IDs. Identical numeric suffixes do not imply identical materials.

## Evidence index

| Topic | Retained evidence | Decision impact |
|---|---|---|
| Assays | Five producer assays; measured/transcribed TBP through590°C, source-specific estimated tail | Rebin from the source CDF, not rounded old mole percentages; label higher cuts estimated |
| Hydroprocessing | [Cut/lump literature](notes/vacuum-residue-hydroprocessing-cuts.md) | Separate reaction and fractionation resolution; roughly735–750°C distinction is useful, not universal |
| Heavy-end viscosity | [Literature and unit audit](notes/HEAVY_FRACTION_VISCOSITY_LITERATURE.md) | PC11's prior and the PC12/13 fallback cannot be trusted as a global replacement family |
| Latest transport study | [Calibrated study](../viscosity-temperature-plots/calibrated-rheology/README.md), [gallery](../viscosity-temperature-plots/calibrated-rheology/index.html) | Source-cut curves are usable priors; low-temperature rheology remains conditional |
| Middle-profile choice | [Reproducible selection](viscosity-selection.json), [script](select_viscosity.py) | Dalia is closest to the pointwise log-median across the five donor families in the declared40–150°C screen |
| Missing chemistry | [Elemental estimates](notes/CUT_ELEMENTAL_PROFILES.md), [estimation methods](notes/MISSING_PARAMETER_ESTIMATION_RESEARCH.md) | Deferred; not part of regrouping acceptance |
| Cold flow | [Cold-flow screen](notes/PSEUDOCOMPONENT_COLD_FLOW.md), [wax study](../viscosity-temperature-plots/wax-precipitation/README.md) | No universal no-flow viscosity or inferred pure-cut freezing temperature; do not fit a gel transition into the Newtonian transport table |

The previous bulk-fit plots, component reconstruction and wax sensitivity plots are stages of the research, not interchangeable validated models. The latest calibrated study uses reported wax contents, correcting the earlier overly broad claim that wax data were missing. Its 10 same-assay temperature checks gave mean absolute error0.874% and maximum3.87%; that does not validate transfer to another crude, the cold gel branch, or extrapolation to500°C. The new shared family explicitly accepts crude-specific mismatch.

## Where the files now live

- `notes/`: all six thread reports formerly at repository root, property provenance/export, VDU background and hydroprocessing cut review.
- [../crude-assays](../crude-assays/): source transcriptions, converter, appearance estimates, converted amounts, elemental research and their scripts/tests.
- [../crude-assay-sources](../crude-assay-sources/): producer PDFs, text extracts and QA images moved out of `build/`.
- [../cold-flow](../cold-flow/): installed-API probe, screening results and unit audit.
- [../viscosity-tools](../viscosity-tools/): exporters/importers, cold-flow tools, their raw outputs, and the hash-matched DWSIM source characterization.
- [../viscosity-temperature-plots](../viscosity-temperature-plots/): figures, numerical results, scripts, local article PDFs and existing downloadable archives. These were already under `research/`.
- [relocation-manifest.json](relocation-manifest.json):66 relocated files, original/final paths and hashes; post-repair hashes distinguish path corrections from numerical changes.

All these research files remain ignored and untracked. Original numerical result JSON and existing ZIP archives retain their original provenance paths; the relocation manifest resolves those historical names. Existing archives are historical deliverables, not the canonical source tree for rerunning tools. The relocations do not regenerate scientific results.

The referenced operational guide remains at the explicitly requested [documentation/V4_TRANSFORMER_TRAINING_GUIDE.md](../../documentation/V4_TRANSFORMER_TRAINING_GUIDE.md), together with its existing V4 companions and training tools. Unrelated older solver/ChemSep research and environment dependencies were not swept into this thread's collection. The production catalog, runnable tests and independent numerical test fixtures remain tracked; the assay regression now reads a minimal classpath fixture rather than an ignored research report.

## Reproduce current checks

From the repository root, use `C:/Users/wormz/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe` (NumPy/Matplotlib/pdfplumber for plotting/extraction). The commands below use `python` as shorthand for that executable, not the WindowsApps stub. Training requires its own verified CUDA environment from `tools/neural/requirements-transformer.txt`:

```
python research/crude-assays/test_convert.py
python research/crude-assays/test_quality.py
python research/crude-regrouping/select_viscosity.py
./gradlew.bat test --offline --tests com.wormzjl.createcheme.science.material.CrudeAssayConversionTest
```

These check consolidation/current evidence only. They are not evidence that the proposed19-component basis or new transformer has already been qualified. The conversion/import tools can write production resources when deliberately invoked; do not run them as a read-only audit.

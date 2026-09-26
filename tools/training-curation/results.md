# Representative training-data selection

Selected **905 profiles** from the 906 certified TRAIN records. **0** are represented reserve cases and **1** is held separately because its certified target has recorded root disagreement. No fixed subset size was imposed.

Good training data here means a trustworthy same-input native certificate, useful operating and physical-profile coverage, and an unambiguous target policy. A difficult or unusual solution is not poor data. Solver speed, iteration count, model error and holdout outcomes did not influence selection.

## Quality and ambiguity

All 906 records pass the existing strict certification and shape/provenance checks. The case `gd-s08-w0-p1-d0-r00` remains certified, but its existing provenance records an unresolved historical profile disagreement. It is preserved unchanged in `quarantine.jsonl`; no alternative target is chosen and no label is averaged. The historical flag establishes a label-consistency issue, not proof of multiple physical roots. See `quarantine-evidence.json` for the exact TRAIN evidence and metadata bindings.

## Redundancy and chosen size

A reserve case needs a retained direct representative with the same contract, exact stage count, condenser branch, active-feed set, equipment counts and heat-placement rules. Inputs, every native temperature, phase traffic, composition, retained component flows and full-grid trace bands must all pass their registered proximity gates. There is no cross-stage interpolation or transitive clustering.

| Resolution multiplier | Selected | Reserve | Protected witnesses | Admissible nonself pairs | Isolated profiles |
|---|---:|---:|---:|---:|---:|
| 0.5 | 905 | 0 | 351 | 0 | 905 |
| 1.0 | 905 | 0 | 351 | 0 | 905 |
| 2.0 | 905 | 0 | 351 | 0 | 905 |

These are data-representation resolutions, not solver convergence tolerances. The primary setting allows 5% of each effective TRAIN input span, at most 2 K temperature difference, bounded phase-traffic differences, 0.02 composition total variation and 0.25 decades in above-floor component flows. Trace bands and structural masks cannot cross. Half/double sensitivity changes only continuous resolutions.

If the selected set stays large, that is evidence against aggressive compression at these resolutions. These 906 examples were already sparse and varied; forcing a round number would discard distinct examples rather than remove demonstrated redundancy. This selection does not prove that retraining on it will improve convergence.

## Coverage

| Category | Original certified pool | Selected |
|---|---|---|
| branch | {"LIQUID_ONLY": 11, "TWO_PHASE": 895} | {"LIQUID_ONLY": 11, "TWO_PHASE": 894} |
| steam | {"False": 416, "True": 490} | {"False": 415, "True": 490} |
| sideDrawCount | {"0": 354, "1": 267, "2": 163, "3": 122} | {"0": 353, "1": 267, "2": 163, "3": 122} |
| heatLoopCount | {"0": 239, "1": 212, "2": 181, "3": 160, "4": 114} | {"0": 239, "1": 211, "2": 181, "3": 160, "4": 114} |
| heatRules | {"NONE": 239, "UNIFORM": 667} | {"NONE": 239, "UNIFORM": 666} |
| wet | {"False": 906} | {"False": 905} |

All eleven LIQUID_ONLY profiles and every observed stage count are retained. Known stage-count gaps remain [38, 52]; the pool has no wet-qualified or VAPOR_ONLY profiles. Selection cannot manufacture those regimes.

Quarantine removes 2 original deterministic boundary/extreme witnesses and leaves 0 original coverage-quota exceptions. Exact exceptions and replacement witnesses within the 905-case domain are recorded in `selection.json`. Original witness loss is not silently counted as satisfied.

Protected records include scarce categories, operating/profile extrema and nearest observed witnesses on both sides of one-floor and ten-floor boundaries by component, phase and physical neighborhood. Heat loops are represented as authored stage heat only. Every quota and boundary witness is checked after selection.

Representative multiplicities describe coverage of the unambiguous pool and sum to its size. They are not applied as training weights. All labels, certificates and provenance retain their original raw JSONL record bytes. The original 906-record dataset and all older studies remain unchanged.

## Files

- `selected.jsonl`: recommended unambiguous training records at the registered coverage resolution.
- `reserve.jsonl`: valid records directly represented by selected cases; an empty file means no demonstrated redundancy.
- `quarantine.jsonl`: original certified records held aside for target ambiguity.
- `case-decisions.jsonl`: every source ID, hashes, reasons, representative and gate values.
- `selection.json` and `redundancy.json`: coverage, witnesses, resolutions and pairwise evidence.

The files are preserved in the separate [archive](cache-manifest.json). See [protocol](protocol.md) for the complete rules. No neural training, native solve, thermodynamic campaign or production-default change was performed.

# TJL19 property reconstruction provenance

The production TJL19 reconstruction is stored in `src/main/resources/data/createcheme/materials/`: the `properties/tjl19_*.json` records, `packages/tjl19.json`, `interactions/tjl19.json`, and `assays/tjl19.json`. The former `V3Tjl19PropertyPackage` (previously `V3Tjl19DwsimPackage`) now exists only as an independent test fixture. See [Material data packs](../../../MATERIALS.md) for the schema and reload behavior.

The original source comment identifies `scripts/dwsim/generate-v3-tjl19.py` and DWSIM 10.2.3 as the generator/reconstruction source. That generator is not tracked in this checkout. The September 2026 naming refactor does not regenerate or alter any component values, feed fractions, or correlations.

These are reconstructed petroleum properties, not the original HYSYS property data. The reconstruction assumes a 60 F standard-liquid basis. Its source uses the PR78 low-omega coefficient 1.5422; the V3 implementation uses 1.54226. Renaming the code does not establish numerical equivalence with an external simulator or change the existing model limitations.

The package ID `createcheme:tjl19_dwsim` and revision `tjl19-dwsim-10.2.3-r1` remain stable for saved data and result provenance. Source-level class/method names and diagnostic labels now describe the dataset and algorithms directly. Historical research reports retain their original external-source attribution.

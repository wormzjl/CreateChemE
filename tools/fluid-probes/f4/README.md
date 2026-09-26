# F4 probes and data: pump option P1, the thermodynamic domain, cryogenic nitrogen

Batch `2026-09-23-fluid-followups`, package F4. Review `FLUID_PUMP_AND_THERMO_DOMAIN_REVIEW.md`; numbers in `f4-tables.md`; outputs in the batch's `f4-logs/` (`probe-*.log`, `fingerprints-*.txt`).

## The off-line runner

`probes/sci.sh <ProbeFile.java> <fully.qualified.MainClass> [args...]` builds and runs one probe:

- It compiles the science sources (which have no Minecraft dependency), three Minecraft-free runtime classes (`PhysicalFluidTopology`, `FluidDeviceSpec`, `SlurryFeed`) and the probe with `javac`, into a temporary folder.
- It runs the probe against `src/main/resources`. This takes seconds, needs no Gradle, and never touches `src/`.
- With `REF=<commit>` it extracts that commit's `src/main` with `git archive` and runs against it: a "before" measurement without a checkout.
- It finds the repository root as `../../../..` from its own folder, so it works from here unchanged.
- It takes Gson and EJML from the Gradle cache (`~/.gradle/caches/modules-2`).

## Probes

| file | what it measured | cited in | against |
|---|---|---|---|
| `probes/FingerprintProbe.java` (package `science.material`) | Every package's fingerprint and physics fingerprint, and the network package's fluid thermodynamic fingerprint: which ones F4's data changes moved. | `f4-tables.md` section 5; `fingerprints-before.txt`, `fingerprints-after.txt` | `REF=e837ada`, and the F4 tree |
| `probes/NitrogenCryogenicProbe.java` (package `science.fluid.thermo`) | The network's nitrogen model at cryogenic states, read through the translated PR78 directly so the domain checks do not interfere: saturation pressure, liquid density and vapour Cp against NIST. Its first argument is the NIST saturation table (default `../nist/sat-triple-to-critical.tsv`). | review section 1.4; `f4-tables.md` sections 1.1 and 1.2; `probe-nitrogen-validation.log` | the F4 tree (`c26d162`) |
| `probes/PumpLineProbe.java` (package `science.fluid.network`) | The rig's pump lines with the placement defaults, integrated off-line in consecutive intervals on one `PassiveIntervalSolver`. Arguments: `<layout> <cold or interval seconds> <count> [traceFrom]`. It covered the water fill's shutoff, the gas transfer before and after P1, and the junction-donor cycle. | review section 3; `f4-tables.md` section 2; `probe-fill-*.log`, `probe-gas-transfer-*.log`, `probe-domain-violation.log` | `REF=e837ada` for "before"; the F4 tree for "after" |

## NIST data and the scripts that turned it into the committed nitrogen record

| file | what |
|---|---|
| `nist/*.tsv` | NIST WebBook fluid data for nitrogen (Span et al. 2000 equation of state, Lemmon and Jacobsen 2004 transport), fetched as tab-separated text: the saturation line from the triple point to the critical point, the 77.355 K isotherm, the 101.325 kPa isobar from 100 to 200 K, the 0.1, 1 and 10 kPa isobars, and the zero-pressure Cp derived from them. The source URLs are in `write-nitrogen-record.js`. |
| `probes/fit-nitrogen-cp-low.js` | Fits the ideal-gas Cp segment below 273.16 K to `../nist/cp0-zero-pressure-63-303K.tsv` (worst error 0.018 %, continuous at the joint). |
| `probes/build-nitrogen-viscosity-tables.js` | Builds the liquid and low-pressure vapour viscosity log tables from `../nist/`, using the existing record in `src/main/resources/.../nitrogen.json` as its base. |
| `probes/write-nitrogen-record.js` | Writes `src/main/resources/data/createcheme/materials/properties/nitrogen.json` (revision `fluid-nitrogen-nist-r2`) from the fits. Run it from the repository root. It produced the committed data in `c26d162`; running it again overwrites that file. |

## Run scripts

`run-scripts/gates.sh <tag> [science runtime network regression gametest compile column]` is F4's gate runner. It added the `compile` gate and the `column` gate (`test --tests science.column.* --tests science.material.* --tests science.thermo.*`), because `MaterialCatalog` is shared. F4 made no paced, rig or dev-client run.

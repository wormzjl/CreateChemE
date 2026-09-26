# pilot-volume-anchors

Batch `2026-09-24-coolprop-low-temperature`, P3 WP3 (bundled pilot package). One-off printer; not part of the build or of any Gradle task.

## Purpose

Prints, for the four pilot property records (`createcheme:pilot_{nitrogen,methane,ethane,carbon_dioxide}`), the `volume_translation` anchor of plan section 3 (P1 rule b): T = 0.8 Tc of the record's own PR78 critical temperature, the reference saturation pressure there and the saturated-liquid molar volume, from the Java Helmholtz oracle (`src/test/java/com/wormzjl/createcheme/science/thermo/reference`, commit `6ded7c7`, on the CoolProp `dev/fluids` files at `ae81610e7d23efc57f9d051c8e70a4d66e87537f` in `src/test/resources/science/thermo/coolprop/`). It also prints the shift `c = v_ref - v_PR,L(T, Psat_ref)` on the untranslated PR78 kernel (`VolumeTranslation.shift`) and the old anchor for comparison (the `liquid_calibration.json` NIST point at 2 MPa, for CO2 the P2 standard density at 250 K and 2 MPa, through the same kernel). Output: `anchors.json`, read by `tools/pilot-transport-tables/assemble_pilot_properties.py`.

## How to run

From the worktree root in Git Bash (javac 21+, the gson 2.10.1 jar of the Gradle cache; no Gradle run):

    bash tools/pilot-volume-anchors/run.sh > tools/pilot-volume-anchors/anchors.json

`run.sh` compiles the eight non-JUnit oracle classes, `PrintPilotVolumeAnchors.java` and (through `-sourcepath src/main/java`) the main classes it needs into a temporary directory and runs the printer with `src/test/resources` on the class path. The output is byte-for-byte reproducible (every number is `Double.toString`).

## Results (2026-09-24)

| Record | T (K) | Psat (Pa) | v_L (m3/mol) | c (cm3/mol) | old c at 2 MPa (cm3/mol) |
|---|---|---|---|---|---|
| pilot_nitrogen | 100.9536 | 830956.9070807779 | 4.098312423417949e-05 | 3.518832 | 4.063490 |
| pilot_methane | 152.4512 | 1159979.5409666544 | 4.546080933102867e-05 | 3.389108 | 3.643535 |
| pilot_ethane | 244.256 | 1100180.1723689875 | 6.56330788671276e-05 | 3.451672 | 3.831907 |
| pilot_carbon_dioxide | 243.30256 | 1435091.8271856345 | 4.0936022932852e-05 | 1.169707 | 0.945916 |

`PilotVolumeAnchorTest` recomputes T, Psat and v_L from the oracle at 1e-12 relative and the shifts against P1 (3.519, 3.389, 3.454, 1.170 cm3/mol) at 1e-3 cm3/mol; ethane matches P1 only with P1's CoolProp constants (305.322 K, 4.8722 MPa; c = 3.454), the record keeps the bundled ethane constants (305.32 K, 4.872 MPa).

No code of this folder was ever in a tracked path; nothing to re-attach.

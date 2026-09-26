# solid-co2-qualification

Purpose: measure the solid CO2 model of P4 stage 1 without Gradle, and print every table of
`documentation/2026-09-24-coolprop-low-temperature/P4_SOLID_CO2_MODEL.md`. Batch `2026-09-24-coolprop-low-temperature`,
stage P4 stage 1 (the Jaeger and Span 2012 Gibbs function on the P2 crystal contract).

Nothing of this folder is in a tracked path and nothing needs re-attaching: the model (`JaegerSpanGibbsFunction`,
`CrystalReference`, `CrystalPhaseEvaluator`), the gate tests (`JaegerSpanGibbsFunctionTest`, `CrystalReferenceTest`,
`CrystalPhaseEvaluatorTest`, `SolidCarbonDioxideQualificationTest`) and their reference helper
(`SolidCarbonDioxideReferences`: the Span-Wagner, Trusler and Fray-Schmitt equations and the coexistence solve) are tracked.
The probe here is a one-off measurement aid: it scans denser grids than the gates and runs both fusion enthalpies
(8875 and 9019 J/mol) on both fluids (the Span-Wagner oracle and the pilot family).

## Contents

| File | Content |
|---|---|
| `run.sh` | javac of the P4 sources of the worktree (model, evaluator, gate tests, reference helper) and the probe against the last Gradle compile of the worktree (`build/classes/java/{main,test}`, `build/resources`, gson, JUnit API, EJML from the Gradle cache), with the worktree's `src/main/resources` first on the classpath so the edited crystal record is read. `run.sh probe` runs the probe, `run.sh tests [-Dmethod=name]` the gate classes through the reflective runner |
| `RunTests.java` | the reflective JUnit 5 runner of `tools/g3-qualification` (PASS/FAIL per method) |
| `src/com/wormzjl/createcheme/science/thermo/phase/SolidCo2QualificationProbe.java` | the probe: Table 5 with the published g0 and g1; the oracle anchoring with 8875 and 9019 J/mol (g0 and g1 recovered, sublimation 80 K to T_t against Span-Wagner 3.12, Trusler 48 and Fray-Schmitt, melting 216.6 to 270 K against Span-Wagner 3.10 and Trusler 47, sublimation enthalpies); the pilot family with both values (the cubic at the triple point, sublimation 130 K to T_t, melting to 10 MPa, sublimation enthalpies); the Trusler 2011 cross-check of v, alpha and c_p |

## How to run

From Git Bash in the worktree root, after at least one Gradle compile of the tree (the runner reads `build/classes`),
with the folder staged at `tools/solid-co2-qualification/` of the worktree (copy it back from the main checkout if the
worktree's copy is gone):

    bash tools/solid-co2-qualification/run.sh probe > probe.txt
    bash tools/solid-co2-qualification/run.sh tests

The standalone runs use whatever main classes the last Gradle compile left in `build/classes` (another agent's
uncommitted work included) plus the P4 sources compiled here; the gate is the Gradle run recorded in the document:

    JAVA_OPTS=-Xshare:off ./gradlew test --tests "com.wormzjl.createcheme.science.material.*" --tests "com.wormzjl.createcheme.science.thermo.*" --offline

The probe's output of 2026-09-25 is `research/2026-09-24-coolprop-low-temperature/p4-solid-co2/probe-2026-09-25.txt`
in the main checkout.

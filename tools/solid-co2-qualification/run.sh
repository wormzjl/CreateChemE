#!/usr/bin/env bash
# Solid CO2 qualification (P4 stage 1, batch 2026-09-24-coolprop-low-temperature) without Gradle: javac of the P4 sources
# of the worktree (the crystal model, the evaluator, the gate tests and their reference helper) against the last Gradle
# compile of the worktree (build/classes/java/main and test, build/resources), with the worktree's src/main/resources
# first on the classpath so the edited crystal record is read. Then either the probe (default) or the gate tests.
# Usage, from Git Bash in the worktree root:
#   bash "$TOOL/run.sh" probe            # SolidCo2QualificationProbe: every table of P4_SOLID_CO2_MODEL.md
#   bash "$TOOL/run.sh" tests [-Dmethod=name]   # the gate test classes through the reflective runner
set -euo pipefail
TOOL="$(cd "$(dirname "$0")" && pwd)"
cd "$(git rev-parse --show-toplevel)"
JDK="${JDK:-/c/Program Files/Java/jdk-21.0.11/bin}"
G=/c/Users/wormz/.gradle/caches/modules-2/files-2.1
GSON="$G/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
JUNIT=$(find $G/org.junit.jupiter/junit-jupiter-api -name "junit-jupiter-api-5.11.4.jar" | head -1)
OPENTEST=$(find $G/org.opentest4j/opentest4j -name "opentest4j-1.3.0.jar" | head -1)
COMMONS=$(find $G/org.junit.platform/junit-platform-commons -name "junit-platform-commons-1.11.4.jar" | head -1)
EJML=""
for m in ejml-core ejml-ddense ejml-dsparse; do J=$(find $G/org.ejml/$m -name "$m-0.44.0.jar" | head -1); EJML="$EJML;$(cygpath -w "$J")"; done
OUT=build/solid-co2-qualification-classes
rm -rf "$OUT"; mkdir -p "$OUT"
CP="$(cygpath -w "$PWD/$OUT");$(cygpath -w "$PWD/src/main/resources");$(cygpath -w "$PWD/build/classes/java/main");$(cygpath -w "$PWD/build/classes/java/test");$(cygpath -w "$PWD/build/resources/main");$(cygpath -w "$PWD/build/resources/test");$(cygpath -w "$PWD/src/test/resources");$(cygpath -w "$GSON");$(cygpath -w "$JUNIT");$(cygpath -w "$OPENTEST");$(cygpath -w "$COMMONS")$EJML"
SOURCES=(
  src/main/java/com/wormzjl/createcheme/science/material/JaegerSpanGibbsFunction.java
  src/main/java/com/wormzjl/createcheme/science/material/CrystalReference.java
  src/main/java/com/wormzjl/createcheme/science/thermo/phase/SolidPhaseEvaluator.java
  src/main/java/com/wormzjl/createcheme/science/thermo/phase/CrystalPhaseEvaluator.java
  src/test/java/com/wormzjl/createcheme/science/thermo/phase/SolidCarbonDioxideReferences.java
)
for f in src/test/java/com/wormzjl/createcheme/science/material/JaegerSpanGibbsFunctionTest.java \
         src/test/java/com/wormzjl/createcheme/science/material/CrystalReferenceTest.java \
         src/test/java/com/wormzjl/createcheme/science/thermo/phase/CrystalPhaseEvaluatorTest.java \
         src/test/java/com/wormzjl/createcheme/science/thermo/phase/SolidCarbonDioxideQualificationTest.java \n         src/test/java/com/wormzjl/createcheme/science/thermo/phase/SolidPhaseEvaluatorTest.java; do
  [ -f "$f" ] && SOURCES+=("$f")
done
"$JDK/javac" -nowarn -encoding UTF-8 -d "$(cygpath -w "$OUT")" -cp "$CP" "${SOURCES[@]}" "$TOOL/RunTests.java" \
  "$TOOL/src/com/wormzjl/createcheme/science/thermo/phase/SolidCo2QualificationProbe.java"
MODE="${1:-probe}"; shift || true
if [ "$MODE" = probe ]; then
  "$JDK/java" -cp "$CP" com.wormzjl.createcheme.science.thermo.phase.SolidCo2QualificationProbe "$@"
else
  PROPS=(); for a in "$@"; do PROPS+=("$a"); done
  "$JDK/java" "${PROPS[@]}" -cp "$CP" RunTests com.wormzjl.createcheme.science.material.JaegerSpanGibbsFunctionTest \
    com.wormzjl.createcheme.science.material.CrystalReferenceTest com.wormzjl.createcheme.science.thermo.phase.CrystalPhaseEvaluatorTest \
    com.wormzjl.createcheme.science.thermo.phase.SolidCarbonDioxideQualificationTest com.wormzjl.createcheme.science.thermo.phase.SolidPhaseEvaluatorTest
fi

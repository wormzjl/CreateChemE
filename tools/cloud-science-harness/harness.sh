#!/usr/bin/env bash
# Minecraft-free JUnit and exact-regression runner for the fluid gates (javac + JUnit console launcher, no Gradle).
# A stand-in for the Gradle gates in a container that cannot resolve Minecraft/NeoForge; the Gradle gates remain the
# gates of record. See README.md in this folder.
#
# Usage: harness.sh <command> [extra JUnit console launcher arguments]
#   fetch       download the five jars from Maven Central into $LIB (checked against Maven Central's .sha1)
#   compile     check the verbatim stand-ins, then compile main (science, runtime, stubs) and the tests into $OUT
#   science     fluidScienceTest: package com.wormzjl.createcheme.science.fluid (PackagedSparseLuTest excluded)
#   runtime     fluidRuntimeTest: package com.wormzjl.createcheme.runtime.fluid plus runtime.Fluid*Test, then the
#               MIXED_GAS/LIQUID_JUNCTION lines compared with reference/junction-lines.txt
#   adjacent    the 38-test selection (*PassiveStepSolverTest *PumpJunctionStartupTest ... *PipePresentationTest)
#   regression  fluidSolverRegression -PfluidRegressionMode=exact (chain-100 against its captured reference)
#   all         compile, science, runtime, adjacent, regression; exits nonzero if any failed
#   select      compile-free run of whatever JUnit selectors follow (e.g. select --select-class x.y.ZTest)
#
# Environment:
#   REPO   repository root to build and test (default: the checkout holding this tool folder)
#   OUT    compiled classes and reports (default: $REPO/build/cloud-harness; git-ignored)
#   LIB    jar folder (default: this folder's lib/, filled by `fetch`)
#   JAVA_HOME  optional; javac/java from its bin/ when set
#   HARNESS_JVM_OPTS  extra JVM options for the test JVM (default: -Xmx2g)
set -uo pipefail
unset JAVA_TOOL_OPTIONS   # the container proxy options only print a banner here

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "${REPO:-$HERE/../..}" && pwd)
OUT=${OUT:-$REPO/build/cloud-harness}
LIB=${LIB:-$HERE/lib}
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
JVM_OPTS=${HARNESS_JVM_OPTS:--Xmx2g}

MAVEN=https://repo.maven.apache.org/maven2
JARS=(
  "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"
  "org/ejml/ejml-core/0.44.0/ejml-core-0.44.0.jar"
  "org/ejml/ejml-ddense/0.44.0/ejml-ddense-0.44.0.jar"
  "org/ejml/ejml-dsparse/0.44.0/ejml-dsparse-0.44.0.jar"
  "org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar"
)
DEPS=$LIB/gson-2.10.1.jar:$LIB/ejml-core-0.44.0.jar:$LIB/ejml-ddense-0.44.0.jar:$LIB/ejml-dsparse-0.44.0.jar
JUNIT=$LIB/junit-platform-console-standalone-1.11.4.jar

MAIN_JAVA=$REPO/src/main/java
TEST_JAVA=$REPO/src/test/java
PKG=com/wormzjl/createcheme

# Test sources that cannot compile without Minecraft (see README "Not covered").
EXCLUDED_TEST_SOURCES=(
  "runtime/fluid/FluidPacketCodecTest.java"       # netty ByteBuf, RegistryFriendlyByteBuf, network.FluidNetwork codecs
  "runtime/fluid/FluidDeviceSpecDomainTest.java"  # network.FluidNetwork.Controls (packet class)
)

die() { echo "harness: $*" >&2; exit 2; }

fetch() {
  mkdir -p "$LIB"
  for path in "${JARS[@]}"; do
    local name=${path##*/} want got
    want=$(curl -fsS --retry 6 --retry-delay 5 --retry-all-errors "$MAVEN/$path.sha1" | cut -c1-40) || die "cannot reach $MAVEN/$path.sha1"
    if [ -f "$LIB/$name" ] && [ "$(sha1sum "$LIB/$name" | cut -c1-40)" = "$want" ]; then echo "ok      $name"; continue; fi
    curl -fsS --retry 6 --retry-delay 5 --retry-all-errors -o "$LIB/$name.part" "$MAVEN/$path" || die "download failed: $path"
    got=$(sha1sum "$LIB/$name.part" | cut -c1-40)
    [ "$got" = "$want" ] || { rm -f "$LIB/$name.part"; die "sha1 mismatch for $name ($got, Maven Central says $want)"; }
    mv "$LIB/$name.part" "$LIB/$name"; echo "fetched $name"
  done
}

require_jars() {
  local name
  for path in "${JARS[@]}"; do name=${path##*/}; [ -f "$LIB/$name" ] || die "missing $LIB/$name; run: $0 fetch (or set LIB=)"; done
}

# Every line between VERBATIM-BEGIN/END in a stand-in must still be a line of the real source it was copied from.
check_verbatim() {
  local stub=$1 real=$2 drift=0 line
  [ -f "$real" ] || die "verbatim source missing: $real"
  while IFS= read -r line; do
    [ -z "${line// /}" ] && continue
    grep -Fxq -- "$line" "$real" || { [ $drift = 0 ] && echo "harness: stand-in ${stub#$HERE/} has drifted from ${real#$REPO/}:" >&2; echo "  not in the real file: $line" >&2; drift=1; }
  done < <(awk '/VERBATIM-BEGIN/{on=1;next}/VERBATIM-END/{on=0}on' "$stub")
  # The FluidOptions record must be complete: every real line naming it must be in the stand-in.
  if [[ $stub == */CreateChemE.java ]]; then
    while IFS= read -r line; do
      grep -Fxq -- "$line" "$stub" || { echo "harness: real CreateChemE line missing from the stand-in: $line" >&2; drift=1; }
    done < <(grep -E "FluidOptions|fluidOptions|CertificatePolicy\(FLUID_|define(InRange|Enum)?\(" "$real" | grep -vE "SOLVER_|CALCULATION_LOGGING|^\s*\.define\(\"enableCalculationLogging\"|defineInRange\(\"(workers|automaticWorkerLimit|readyQueueCapacity|deadlineMilliseconds|gracefulShutdownMilliseconds|forcedShutdownMilliseconds)\"")
  fi
  [ $drift = 0 ] || die "update the stand-in from the real file (copy the changed lines verbatim), then compile again"
}

compile() {
  require_jars
  check_verbatim "$HERE/stubs/$PKG/CreateChemE.java" "$MAIN_JAVA/$PKG/CreateChemE.java"
  check_verbatim "$HERE/stubs/$PKG/world/level/block/entity/ColumnCalculatorV3BlockEntity.java" \
                 "$MAIN_JAVA/$PKG/world/level/block/entity/ColumnCalculatorV3BlockEntity.java"
  rm -rf "$OUT/main" "$OUT/test"; mkdir -p "$OUT/main" "$OUT/test"
  # Main: the whole science and runtime packages as they are, the MC-free ColumnInputPreset, and the stand-ins.
  { find "$MAIN_JAVA/$PKG/science" "$MAIN_JAVA/$PKG/runtime" -name '*.java'
    echo "$MAIN_JAVA/$PKG/world/level/block/entity/ColumnInputPreset.java"
    find "$HERE/stubs" -name '*.java'; } > "$OUT/main-sources.txt"
  "$JAVAC" -encoding UTF-8 --release 21 -nowarn -Xmaxerrs 200 -d "$OUT/main" -cp "$DEPS" @"$OUT/main-sources.txt" \
    || die "main compilation failed"
  # Tests: every test under science/fluid/, runtime/ and fluid/ except the Minecraft-bound ones; helpers they use from
  # other test packages are compiled implicitly through -sourcepath.
  find "$TEST_JAVA/$PKG/science/fluid" "$TEST_JAVA/$PKG/runtime" "$TEST_JAVA/$PKG/fluid" -name '*.java' | sort > "$OUT/test-sources.all"
  local pattern; pattern=$(printf '|%s' "${EXCLUDED_TEST_SOURCES[@]}"); pattern="/$PKG/(${pattern:1})\$"
  grep -vE "$pattern" "$OUT/test-sources.all" > "$OUT/test-sources.txt"
  "$JAVAC" -encoding UTF-8 --release 21 -nowarn -Xmaxerrs 200 -d "$OUT/test" -cp "$OUT/main:$DEPS:$JUNIT" \
      -sourcepath "$TEST_JAVA" -implicit:class @"$OUT/test-sources.txt" \
    || die "test compilation failed (a new test may need a stand-in, or belongs in EXCLUDED_TEST_SOURCES)"
  echo "harness: compiled $(wc -l < "$OUT/main-sources.txt") main sources (incl. $(find "$HERE/stubs" -name '*.java' | wc -l) stand-ins)" \
       "and $(wc -l < "$OUT/test-sources.txt") test sources of $(wc -l < "$OUT/test-sources.all") into $OUT"
}

require_compiled() { [ -d "$OUT/test/$PKG" ] || compile; }

# junit <label> <system properties...> -- <launcher selectors and arguments...>
junit() {
  local label=$1; shift
  local props=()
  while [ $# -gt 0 ] && [ "$1" != -- ]; do props+=("$1"); shift; done
  shift
  mkdir -p "$OUT/reports/$label"
  rm -f "$OUT/reports/$label"/*.xml
  echo "=== harness $label (repo $REPO, $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo '?')$(git -C "$REPO" diff --quiet HEAD -- src 2>/dev/null || echo ' + uncommitted src changes'))"
  # Gradle runs tests with the project directory as working directory; so does the harness.
  (cd "$REPO" && "$JAVA" $JVM_OPTS "${props[@]}" -Dfluid.modJar="$OUT/no-mod-jar" -jar "$JUNIT" execute \
      -cp "$OUT/test:$REPO/src/test/resources:$OUT/main:$REPO/src/main/resources:$REPO/src/generated/resources:$DEPS" \
      --disable-banner --details=summary --details-theme=ascii --fail-if-no-tests \
      --reports-dir="$OUT/reports/$label" "$@") 2>&1 | tee "$OUT/reports/$label/console.txt"
  return "${PIPESTATUS[0]}"
}

# Compare the junction ledger lines with the reference, ignoring wall time and allocation (they vary run to run).
junction_lines() {
  local console=$OUT/reports/$1/console.txt ref=$HERE/reference/junction-lines.txt
  local got=$OUT/reports/$1/junction-lines.txt
  grep -oE "(MIXED_GAS_(STATIC|TRANSIENT|COST)|LIQUID_JUNCTION) .*" "$console" | sed -E 's/\r$//' > "$got"
  [ -s "$got" ] || { echo "harness: no MIXED_GAS/LIQUID_JUNCTION lines in this run (junction tests not selected?)"; return 0; }
  echo "--- junction ledger lines (MIXED_GAS_COST, LIQUID_JUNCTION as printed):"
  grep -E "^(MIXED_GAS_COST|LIQUID_JUNCTION)" "$got"
  normalise() { grep -E '^(MIXED_GAS|LIQUID_JUNCTION)' "$1" | sed -E 's/ ms=[0-9]+//; s/, bytes=[0-9]+//; s/ allocatedMB=[0-9.]+//' | sort; }
  local refjdk jdk
  refjdk=$(sed -n 's/^# java.runtime.version=//p' "$ref")
  jdk=$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.runtime.version = //p')
  [ "$refjdk" = "$jdk" ] || echo "harness: NOTE the reference was recorded on JDK $refjdk, this run uses $jdk; a last-bit ledger difference can be the JDK (README, Reference results)"
  if diff <(normalise "$ref") <(normalise "$got") > "$OUT/reports/$1/junction-lines.diff"; then
    echo "harness: $(wc -l < "$got") junction lines identical to reference/junction-lines.txt (6e1c5b6; wall ms, bytes and allocatedMB ignored)"
  else
    echo "harness: JUNCTION LINES DIFFER from reference/junction-lines.txt (< reference at 6e1c5b6, > this run):"
    cat "$OUT/reports/$1/junction-lines.diff"; return 1
  fi
}

science()    { require_compiled; junit science -Dcreatecheme.fluid.scheduler.verify=true -- \
                 --select-package com.wormzjl.createcheme.science.fluid --exclude-classname '.*\.PackagedSparseLuTest' "$@"; }
runtime()    { require_compiled; local rc=0
               junit runtime -Dcreatecheme.fluid.scheduler.verify=true -- \
                 --select-package com.wormzjl.createcheme.runtime.fluid \
                 $(cd "$OUT/test" && ls $PKG/runtime/Fluid*Test.class 2>/dev/null | sed -E 's#/#.#g; s#\.class$##; s#^#--select-class=#') "$@" || rc=$?
               [ $# -gt 0 ] || junction_lines runtime || rc=1
               return $rc; }
adjacent()   { require_compiled; local sel=() c
               for c in science.fluid.network.PassiveStepSolverTest science.fluid.network.PumpJunctionStartupTest \
                        runtime.fluid.FilterBlockLineIslandTest runtime.fluid.DeadHeadedLineIslandTest \
                        science.fluid.network.NetworkRegimeTest runtime.fluid.PhysicalFluidTopologyTest runtime.fluid.PipePresentationTest; do
                 sel+=("--select-class=com.wormzjl.createcheme.$c"); done
               # Gradle's `--tests *XTest` matches by simple name anywhere in the test source set; check none is missed.
               local expected; expected=$(cd "$OUT/test" && find . -name '*.class' ! -name '*$*' | grep -cE '/(PassiveStepSolverTest|PumpJunctionStartupTest|FilterBlockLineIslandTest|DeadHeadedLineIslandTest|NetworkRegimeTest|PhysicalFluidTopologyTest|PipePresentationTest)\.class$')
               [ "$expected" = 7 ] || echo "harness: WARNING the selection patterns match $expected classes, not 7; update adjacent()"
               junit adjacent -Dcreatecheme.fluid.scheduler.verify=true -- "${sel[@]}" "$@"; }
regression() { require_compiled; junit regression -Dfluid.regression.mode=exact -Dfluid.regression.capture=false -- \
                 --select-class=com.wormzjl.createcheme.fluid.benchmark.FluidSolverRegressionTest "$@"; }
select_()    { require_compiled; junit select -Dcreatecheme.fluid.scheduler.verify=true -- "$@"; }

cmd=${1:-}; shift || true
case "$cmd" in
  fetch) fetch ;;
  compile) compile ;;
  science) science "$@" ;;
  runtime) runtime "$@" ;;
  adjacent) adjacent "$@" ;;
  regression) regression "$@" ;;
  select) select_ "$@" ;;
  all)
    compile || exit 2
    declare -A result; failed=0
    for gate in science runtime adjacent regression; do
      if "$gate" "$@"; then result[$gate]=PASS; else result[$gate]=FAIL; failed=1; fi
    done
    echo "=== harness all (repo $REPO)"
    for gate in science runtime adjacent regression; do
      summary=$(grep -E "tests (successful|failed|found)" "$OUT/reports/$gate/console.txt" | tr -s ' []' ' ' | paste -sd',' -)
      printf '%-10s %s  %s\n' "$gate" "${result[$gate]}" "$summary"
    done
    grep -hE "^chain-100 vs reference" "$OUT/reports/regression/console.txt" || true
    exit $failed ;;
  *) sed -n '2,30p' "$0"; exit 2 ;;
esac

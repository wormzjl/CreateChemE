#!/usr/bin/env bash
# JDK determinism sweep: run one compiled build of the fluid science code under many Java runtimes and capture
# every output that must be bitwise identical across them.
#
# Usage: sweep.sh <capture-dir> [runtime-id ...]
#   (no ids: every JDK folder in $JDKS that has bin/java, plus "system" = the container JDK)
#
# Per runtime <id>, into <capture-dir>/<id>/:
#   meta.txt              java.runtime.version, java.vm.name, vendor version, walls, JUnit summaries, exit codes
#   junction-lines.txt    the 33 MIXED_GAS/LIQUID_JUNCTION lines of `harness.sh runtime`, normalised (wall ms, bytes
#                         and allocatedMB stripped, sorted) - the reference check of the harness is not allowed to stop
#                         the sweep: compare afterwards (compare.py)
#   runtime-summary.txt   the JUnit summary of `harness.sh runtime` and the MIXED_GAS_COST line as printed (with wall)
#   regression.txt        `harness.sh regression` (chain-100 exact against the checked-in reference): the table and summary
#   probe/                the d9 BitwiseProbe outputs (chain-100.json, scenarios.txt, gas-ports.txt, liquid-ports.txt)
#   probe-nolibm/         the same with -XX:+UnlockDiagnosticVMOptions -XX:-UseLibmIntrinsic (HotSpot only)
#   mathsweep-jit.txt     MathSweep (3 repetitions, last reported), default JIT
#   mathsweep-xint.txt    MathSweep under -Xint (1 repetition)
#   mathsweep-nolibm.txt  MathSweep with -XX:+UnlockDiagnosticVMOptions -XX:-UseLibmIntrinsic (HotSpot only)
#
# Environment:
#   REPO   the worktree whose resources and regression reference are used (default: this checkout)
#   OUT    harness compile output (default: $SP/jdk-sweep/out-before) - compiled ONCE by the container JDK
#   PROBE  compiled BitwiseProbe classes against $OUT/main (default: $SP/jdk-sweep/probe-before)
#   MSWEEP compiled MathSweep classes (default: $SP/jdk-sweep/mathsweep)
#   JDKS   runtime folders (default: $SP/jdks);  LIB  harness jars (default: $SP/lib)
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SP=${SP:-/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad}
REPO=$(cd "${REPO:-$HERE/../..}" && pwd)
OUT=${OUT:-$SP/jdk-sweep/out-before}
PROBE=${PROBE:-$SP/jdk-sweep/probe-before}
MSWEEP=${MSWEEP:-$SP/jdk-sweep/mathsweep}
JDKS=${JDKS:-$SP/jdks}
LIB=${LIB:-$SP/lib}
CAP=${1:?usage: sweep.sh <capture-dir> [runtime-id ...]}; shift
mkdir -p "$CAP"
HARNESS="$REPO/tools/cloud-science-harness/harness.sh"
DEPS=$LIB/gson-2.10.1.jar:$LIB/ejml-core-0.44.0.jar:$LIB/ejml-ddense-0.44.0.jar:$LIB/ejml-dsparse-0.44.0.jar

ids=("$@")
if [ ${#ids[@]} -eq 0 ]; then
  ids=(system)
  for d in "$JDKS"/*/; do [ -x "$d/bin/java" ] && ids+=("$(basename "$d")"); done
fi

javabin() { if [ "$1" = system ]; then echo /usr/lib/jvm/java-21-openjdk-amd64/bin/java; else echo "$JDKS/$1/bin/java"; fi; }
now() { date +%s.%N; }

for id in "${ids[@]}"; do
  J=$(javabin "$id"); [ -x "$J" ] || { echo "sweep: no java for $id"; continue; }
  D=$CAP/$id; rm -rf "$D"; mkdir -p "$D/probe"
  props=$(env -u JAVA_TOOL_OPTIONS "$J" -XshowSettings:properties -version 2>&1)
  vm=$(sed -n 's/^ *java.vm.name = //p' <<<"$props")
  {
    echo "id=$id"
    echo "java=$J"
    echo "java.runtime.version=$(sed -n 's/^ *java.runtime.version = //p' <<<"$props")"
    echo "java.vm.name=$vm"
    echo "java.vm.version=$(sed -n 's/^ *java.vm.version = //p' <<<"$props")"
    echo "java.vendor=$(sed -n 's/^ *java.vendor = //p' <<<"$props")"
    echo "java.vendor.version=$(sed -n 's/^ *java.vendor.version = //p' <<<"$props")"
    echo "build=$OUT ($(cat "$OUT/build-id.txt" 2>/dev/null || echo '?'))"
  } > "$D/meta.txt"
  echo "=== sweep $id ($(sed -n 's/^java.runtime.version=//p' "$D/meta.txt"), $vm)"

  # 1. harness runtime: the 33 junction lines (the harness's own reference check is ignored here)
  t0=$(now)
  JAVA=$J REPO=$REPO OUT=$OUT LIB=$LIB bash "$HARNESS" runtime > "$D/runtime-console.full.txt" 2>&1; rc=$?
  t1=$(now)
  grep -oE "(MIXED_GAS_(STATIC|TRANSIENT|COST)|LIQUID_JUNCTION) .*" "$OUT/reports/runtime/console.txt" | sed -E 's/\r$//' \
    | sed -E 's/ ms=[0-9]+//; s/, bytes=[0-9]+//; s/ allocatedMB=[0-9.]+//' | sort > "$D/junction-lines.txt"
  { grep -E "tests (successful|failed|found|aborted|skipped)" "$OUT/reports/runtime/console.txt"
    grep -E "^MIXED_GAS_COST" "$OUT/reports/runtime/console.txt"; } > "$D/runtime-summary.txt"
  printf 'runtime.exit=%s runtime.wall=%.1fs junction.lines=%s\n' "$rc" "$(echo "$t1 - $t0" | bc)" "$(wc -l < "$D/junction-lines.txt")" >> "$D/meta.txt"
  rm -f "$D/runtime-console.full.txt"

  # 2. harness regression: chain-100 exact
  JAVA=$J REPO=$REPO OUT=$OUT LIB=$LIB bash "$HARNESS" regression > /dev/null 2>&1; rc=$?
  grep -E "chain-100|tests (successful|failed)|max deviation|accepted" "$OUT/reports/regression/console.txt" > "$D/regression.txt"
  echo "regression.exit=$rc" >> "$D/meta.txt"

  # 3. BitwiseProbe
  t0=$(now)
  (cd "$REPO" && env -u JAVA_TOOL_OPTIONS "$J" -Xmx2g -cp "$PROBE:$OUT/main:$REPO/src/main/resources:$REPO/src/generated/resources:$DEPS" \
      BitwiseProbe "$D/probe") > "$D/probe.log" 2>&1; rc=$?
  t1=$(now)
  printf 'probe.exit=%s probe.wall=%.1fs\n' "$rc" "$(echo "$t1 - $t0" | bc)" >> "$D/meta.txt"
  (cd "$D/probe" && sha256sum chain-100.json scenarios.txt gas-ports.txt liquid-ports.txt) > "$D/probe-sha256.txt" 2>/dev/null
  [ -s "$D/probe.log" ] || rm -f "$D/probe.log"
  # 3b. BitwiseProbe with HotSpot's libm intrinsics off (Math.log/exp/pow/... then take the shared-runtime fdlibm
  #     path): if these outputs equal another runtime's default outputs, the libm alone explains that difference.
  if [[ $vm != *OpenJ9* ]]; then
    mkdir -p "$D/probe-nolibm"
    (cd "$REPO" && env -u JAVA_TOOL_OPTIONS "$J" -Xmx2g -XX:+UnlockDiagnosticVMOptions -XX:-UseLibmIntrinsic \
        -cp "$PROBE:$OUT/main:$REPO/src/main/resources:$REPO/src/generated/resources:$DEPS" BitwiseProbe "$D/probe-nolibm") > /dev/null 2>&1
    echo "probe.nolibm.exit=$?" >> "$D/meta.txt"
    (cd "$D/probe-nolibm" && sha256sum chain-100.json scenarios.txt gas-ports.txt liquid-ports.txt) > "$D/probe-nolibm-sha256.txt" 2>/dev/null
  fi

  # 4. MathSweep: default JIT (3 reps), -Xint, and HotSpot without libm intrinsics
  env -u JAVA_TOOL_OPTIONS "$J" -cp "$MSWEEP" MathSweep sweep 3 > "$D/mathsweep-jit.txt" 2>&1
  echo "mathsweep.jit.exit=$?" >> "$D/meta.txt"
  env -u JAVA_TOOL_OPTIONS "$J" -Xint -cp "$MSWEEP" MathSweep sweep 1 > "$D/mathsweep-xint.txt" 2>&1
  echo "mathsweep.xint.exit=$?" >> "$D/meta.txt"
  if [[ $vm != *OpenJ9* ]]; then
    env -u JAVA_TOOL_OPTIONS "$J" -XX:+UnlockDiagnosticVMOptions -XX:-UseLibmIntrinsic -cp "$MSWEEP" MathSweep sweep 3 > "$D/mathsweep-nolibm.txt" 2>&1
    echo "mathsweep.nolibm.exit=$?" >> "$D/meta.txt"
  else
    echo "skipped: OpenJ9 has no -XX:-UseLibmIntrinsic (it silently ignores unknown -XX options)" > "$D/mathsweep-nolibm.txt"
  fi
  tail -n +3 "$D/meta.txt" | tr '\n' ' ' | sed 's/java=[^ ]* //'; echo
done

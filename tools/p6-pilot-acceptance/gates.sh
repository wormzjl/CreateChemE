#!/usr/bin/env bash
# P6 gates, one Gradle invocation at a time under build/gradle.lock (holder p6), from the worktree root.
# Usage: bash tools/p6-pilot-acceptance/gates.sh <suffix> [science|runtime|regression|gametest|test ...]
set -u
cd "$(git rev-parse --show-toplevel)"
L=/d/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-coolprop-low-temperature/p6-pilot-acceptance/logs
mkdir -p "$L"
SUFFIX="$1"; shift
while [ -f build/gradle.lock ]; do sleep 30; done
if tasklist //v 2>/dev/null | grep -i "java" >/dev/null && jps -lvm 2>/dev/null | grep -q -E "fml.modFolders|runMcpClient|runClient"; then echo "a dev client runs: refusing"; exit 2; fi
echo "p6 $(date '+%Y-%m-%d %H:%M:%S')" > build/gradle.lock
trap 'rm -f build/gradle.lock' EXIT
for gate in "$@"; do
  case "$gate" in
    test) args=(test) ;;
    science) args=(fluidScienceTest) ;;
    runtime) args=(fluidRuntimeTest) ;;
    regression) args=(fluidSolverRegression -PfluidRegressionMode=exact) ;;
    gametest) args=(runFluidGameTestServer -PfluidGameTestRunId=p6-$SUFFIX) ;;
    *) echo "unknown gate $gate"; exit 2 ;;
  esac
  log="$L/$gate-$SUFFIX.log"
  echo "== $gate start $(date '+%H:%M:%S')" | tee -a "$L/gates-$SUFFIX.txt"
  JAVA_OPTS=-Xshare:off ./gradlew "${args[@]}" --offline > "$log" 2>&1
  code=$?
  echo "== $gate exit $code end $(date '+%H:%M:%S') log $log" | tee -a "$L/gates-$SUFFIX.txt"
done

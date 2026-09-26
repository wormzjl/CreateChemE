#!/usr/bin/env bash
# P6b gates, one Gradle invocation at a time under build/gradle.lock (holder p6b), from the worktree root.
# Usage: bash tools/p6b-runtime-failures/gates.sh <suffix> [science|runtime|regression|gametest|test|tests=<pattern> ...]
set -u
cd "$(git rev-parse --show-toplevel)"
L=/d/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-coolprop-low-temperature/p6b-runtime-failures/logs
mkdir -p "$L"
SUFFIX="$1"; shift
while [ -f build/gradle.lock ]; do sleep 30; done
if jps -lvm 2>/dev/null | grep -q -E "fml.modFolders|runMcpClient|runClient|mcp-client"; then echo "a dev client runs: refusing"; exit 2; fi
for gate in "$@"; do
  case "$gate" in
    test) args=(test) ;;
    science) args=(fluidScienceTest) ;;
    runtime) args=(fluidRuntimeTest) ;;
    regression) args=(fluidSolverRegression -PfluidRegressionMode=exact) ;;
    gametest) args=(runFluidGameTestServer -PfluidGameTestRunId=p6b-$SUFFIX) ;;
    tests=*) args=(test --tests "${gate#tests=}") ;;
    *) echo "unknown gate $gate"; exit 2 ;;
  esac
  name="${gate%%=*}"
  log="$L/$name-$SUFFIX.log"
  while [ -f build/gradle.lock ]; do sleep 30; done
  echo "p6b $(date '+%Y-%m-%d %H:%M:%S') $gate" > build/gradle.lock
  echo "== $gate start $(date '+%H:%M:%S')" | tee -a "$L/gates-$SUFFIX.txt"
  JAVA_OPTS=-Xshare:off ./gradlew "${args[@]}" --offline > "$log" 2>&1
  code=$?
  rm -f build/gradle.lock
  echo "== $gate exit $code end $(date '+%H:%M:%S') log $log" | tee -a "$L/gates-$SUFFIX.txt"
done

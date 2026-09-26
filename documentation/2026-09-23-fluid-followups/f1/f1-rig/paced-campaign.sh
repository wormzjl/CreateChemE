#!/usr/bin/env bash
# WP5 secondary: paced harness transient100 at the 60 s window, three pairs (baseline = eb28fc5 worktree, candidate =
# branch with -PfluidRestDetection=true), interleaved base/candidate, then the module profile off/on on the branch.
# One Gradle invocation at a time; machine gate before each run; a crashed run keeps its hs_err file and is rerun once.
set -u
BR=/d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36
BASE=/d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/fluid-baseline-eb28fc5
LOGS=$BR/documentation/fluid-scheduler/wp5-logs/paced
mkdir -p "$LOGS" "$BR/documentation/fluid-scheduler/wp5-logs/jvm-crash"
gate() {
  local out; out=$(powershell -NoProfile -Command "\$e=@(Get-Process Endfield -ErrorAction SilentlyContinue).Count; \$f=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024); \"\$e \$f\"")
  local e=${out% *} f=${out#* }; f=${f//[$'\r\n ']/}
  echo "$(date '+%F %T') gate endfield=$e freeMiB=$f" | tee -a "$LOGS/campaign.log"
  [ "$e" = "0" ] && [ "$f" -ge 20480 ]
}
bench() { # worktree runId extra...
  local wt=$1 id=$2; shift 2
  for attempt in 1 2; do
    until gate; do echo "gate failed, waiting 60 s" | tee -a "$LOGS/campaign.log"; sleep 60; done
    local before; before=$(ls "$wt"/run/fluid-benchmark/"$id"/hs_err*.log 2>/dev/null | wc -l)
    echo "$(date '+%F %T') start $id attempt $attempt ($wt)" | tee -a "$LOGS/campaign.log"
    (cd "$wt" && JAVA_OPTS=-Xshare:off ./gradlew.bat fluidServerBenchmark -PfluidBenchmarkRunId="$id" -PfluidBenchmarkWorkers=0 \
      -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60 -PfluidStressProfile=true --offline --console=plain "$@") > "$LOGS/bench-$id.log" 2>&1
    local rc=$?
    echo "$(date '+%F %T') end $id rc=$rc" | tee -a "$LOGS/campaign.log"
    local crash; crash=$(ls "$wt"/run/fluid-benchmark/"$id"/hs_err*.log "$wt"/hs_err*.log 2>/dev/null)
    if [ -n "$crash" ]; then cp $crash "$BR/documentation/fluid-scheduler/wp5-logs/jvm-crash/" ; echo "JVM crash in $id: $crash" | tee -a "$LOGS/campaign.log"; id="$id-rerun"; continue; fi
    mkdir -p "$LOGS/reports/$id" && cp -r "$wt"/build/reports/fluid/M9/"$id"/* "$LOGS/reports/$id/" 2>/dev/null
    return $rc
  done
}
case "${1:-transient}" in
  transient)
    for r in 01 02 03; do
      bench "$BASE" transient100-wp5-base-r$r -PfluidBenchmarkProfile=transient100
      bench "$BR" transient100-wp5-on-r$r -PfluidBenchmarkProfile=transient100 -PfluidRestDetection=true
    done ;;
  module-off) bench "$BR" module-wp5-off-r01 -PfluidBenchmarkProfile=module -PfluidRestDetection=false ;;
  module-on) bench "$BR" module-wp5-on-r01 -PfluidBenchmarkProfile=module -PfluidRestDetection=true ;;
esac

#!/usr/bin/env bash
# F1 paced benchmark runs on this branch, one Gradle invocation at a time, 60 s warm-up + 60 s window, 12 automatic
# workers. Machine gate before each run (no Endfield.exe, at least 20 GB free); a crashed run keeps its hs_err file
# under f1-logs/jvm-crash/ and is rerun once. Usage: paced.sh <runId> <profile> [extra gradle properties...]
set -u
BR=/d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36
LOGS=$BR/documentation/fluid-followups/f1-logs/paced
mkdir -p "$LOGS/reports" "$BR/documentation/fluid-followups/f1-logs/jvm-crash"
gate() {
  local out; out=$(powershell -NoProfile -Command "\$e=@(Get-Process Endfield -ErrorAction SilentlyContinue).Count; \$f=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024); \"\$e \$f\"")
  local e=${out% *} f=${out#* }; f=${f//[$'\r\n ']/}
  echo "$(date '+%F %T') gate endfield=$e freeMiB=$f" | tee -a "$LOGS/campaign.log"
  [ "$e" = "0" ] && [ "$f" -ge 20480 ]
}
id=$1 profile=$2; shift 2
for attempt in 1 2; do
  until gate; do echo "gate failed, waiting 60 s" | tee -a "$LOGS/campaign.log"; sleep 60; done
  echo "$(date '+%F %T') start $id attempt $attempt at $(cd $BR && git rev-parse --short HEAD) $*" | tee -a "$LOGS/campaign.log"
  (cd "$BR" && JAVA_OPTS=-Xshare:off ./gradlew.bat fluidServerBenchmark -PfluidBenchmarkRunId="$id" -PfluidBenchmarkProfile="$profile" -PfluidBenchmarkWorkers=0 \
    -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60 -PfluidStressProfile=true --offline --console=plain \
    "-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Xshare:off" "$@") > "$LOGS/bench-$id.log" 2>&1
  rc=$?
  echo "$(date '+%F %T') end $id rc=$rc" | tee -a "$LOGS/campaign.log"
  crash=$(ls "$BR"/run/fluid-benchmark/"$id"/hs_err*.log "$BR"/hs_err*.log 2>/dev/null)
  if [ -n "$crash" ]; then for f in $crash; do mv "$f" "$BR/documentation/fluid-followups/f1-logs/jvm-crash/$(basename "$f" .log)-$id.log"; done; echo "JVM crash in $id" | tee -a "$LOGS/campaign.log"; id="$id-rerun"; continue; fi
  mkdir -p "$LOGS/reports/$id" && cp -r "$BR"/build/reports/fluid/M9/"$id"/* "$LOGS/reports/$id/" 2>/dev/null
  exit $rc
done

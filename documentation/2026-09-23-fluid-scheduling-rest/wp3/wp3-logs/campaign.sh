#!/usr/bin/env bash
# WP3 benchmark campaign, one run at a time. Before every run: no Endfield.exe and at least 20 GB of free
# physical memory, else wait and re-check every two minutes. A JVM crash (hs_err file) is preserved under
# jvm-crash/ and that run is repeated once. Usage: campaign.sh <run spec file>; each spec line is
#   <runId> <profile> <restDetection> [tolerance|-] [memory]
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36" || exit 2
LOGS=documentation/fluid-scheduler/wp3-logs
CAMPAIGN=$LOGS/campaign.log
quiet() {
  local out
  out=$(powershell -NoProfile -Command "\$g=Get-Process Endfield -ErrorAction SilentlyContinue; \$f=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024); if(\$g){'GAME '+\$f}else{'NOGAME '+\$f}")
  echo "$out"
}
run_once() {
  local id=$1 profile=$2 rest=$3 tol=$4 mem=$5 attempt=$6
  local check
  while true; do
    check=$(quiet); set -- $check
    if [ "$1" = "NOGAME" ] && [ "$2" -ge 20480 ]; then break; fi
    echo "$(date '+%F %T') WAIT $id: $check" >> $CAMPAIGN; sleep 120
  done
  local extra=""
  [ "$tol" != "-" ] && extra="$extra -PfluidCertificateTolerance=$tol"
  [ "$mem" = "memory" ] && extra="$extra -PfluidBenchmarkMemory=true"
  local log=$LOGS/bench-$id.log; [ "$attempt" = 2 ] && log=$LOGS/bench-$id-attempt2.log
  echo "$(date '+%F %T') START $id (attempt $attempt): pre-run check $check; flags restDetection=$rest$extra" >> $CAMPAIGN
  local before; before=$(ls hs_err_pid*.log run/fluid-benchmark/$id/hs_err_pid*.log 2>/dev/null | sort)
  JAVA_OPTS=-Xshare:off ./gradlew.bat fluidServerBenchmark -PfluidBenchmarkProfile=$profile -PfluidBenchmarkRunId=$id \
    -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=120 -PfluidStressProfile=true \
    -PfluidRestDetection=$rest $extra --offline --console=plain "-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Xshare:off" > $log 2>&1
  local code=$?
  local crashes; crashes=$(ls hs_err_pid*.log run/fluid-benchmark/$id/hs_err_pid*.log 2>/dev/null | sort)
  local fresh; fresh=$(comm -13 <(echo "$before") <(echo "$crashes") | grep -v '^$')
  echo "$(date '+%F %T') END $id (attempt $attempt): exit $code, post-run $(quiet)" >> $CAMPAIGN
  if [ -n "$fresh" ]; then
    for f in $fresh; do mv "$f" "$LOGS/jvm-crash/$(basename "$f" .log)-$id-attempt$attempt.log"; done
    echo "$(date '+%F %T') CRASH $id (attempt $attempt): $fresh" >> $CAMPAIGN; return 3
  fi
  grep -q "A fatal error has been detected" $log && { echo "$(date '+%F %T') CRASH $id (attempt $attempt): fatal error in log" >> $CAMPAIGN; return 3; }
  return $code
}
while read -r id profile rest tol mem; do
  [ -z "$id" ] && continue
  case "$id" in \#*) continue;; esac
  run_once "$id" "$profile" "$rest" "${tol:--}" "${mem:-}" 1; code=$?
  if [ $code = 3 ]; then
    # A crashed run leaves a partial world under the run id; the rerun uses the same id with a fresh world.
    rm -rf "run/fluid-benchmark/$id" "build/reports/fluid/M9/$id"
    run_once "$id" "$profile" "$rest" "${tol:--}" "${mem:-}" 2; code=$?
  fi
  echo "RESULT $id exit $code"
done < "$1"
echo "CAMPAIGN DONE"

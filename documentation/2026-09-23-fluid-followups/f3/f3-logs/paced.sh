#!/usr/bin/env bash
# F3 T4: the one paced run (rest100, certificates on, 60 s warm-up + 60 s window), as WP4's campaign.sh ran
# rest100-wp4-on-r01: before the run no Endfield.exe and at least 20 GB free, else wait; a JVM crash is kept under
# jvm-crash/ and the run repeated once. Usage: paced.sh <runId>
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36" || exit 2
L=documentation/fluid-followups/f3-logs;id=$1
quiet(){ powershell -NoProfile -Command "\$g=Get-Process Endfield -ErrorAction SilentlyContinue; \$f=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024); if(\$g){'GAME '+\$f}else{'NOGAME '+\$f}"; }
for attempt in 1 2; do
  while true; do check=$(quiet); set -- $check; if [ "$1" = "NOGAME" ] && [ "$2" -ge 20480 ]; then break; fi; echo "$(date '+%F %T') WAIT $id: $check" >> $L/gates.log; sleep 120; done
  echo "$(date '+%F %T') START paced $id (attempt $attempt) at $(git rev-parse --short HEAD): $check" >> $L/gates.log
  before=$(ls hs_err_pid*.log run/fluid-benchmark/$id/hs_err_pid*.log 2>/dev/null | sort)
  JAVA_OPTS=-Xshare:off ./gradlew.bat fluidServerBenchmark -PfluidBenchmarkProfile=rest100 -PfluidBenchmarkRunId=$id \
    -PfluidBenchmarkWorkers=0 -PfluidStressWarmupSeconds=60 -PfluidStressMeasurementSeconds=60 -PfluidStressProfile=true \
    -PfluidRestDetection=true -PfluidBenchmarkMemory=true --offline --console=plain "-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Xshare:off" > $L/bench-$id.log 2>&1
  code=$?
  crashes=$(ls hs_err_pid*.log run/fluid-benchmark/$id/hs_err_pid*.log 2>/dev/null | sort)
  fresh=$(comm -13 <(echo "$before") <(echo "$crashes") | grep -v '^$')
  echo "$(date '+%F %T') END paced $id (attempt $attempt): exit $code, post-run $(quiet)" >> $L/gates.log
  if [ -n "$fresh" ]; then for f in $fresh; do mv "$f" "$L/jvm-crash/$(basename "$f" .log)-$id-attempt$attempt.log"; done; echo "$(date '+%F %T') CRASH $id" >> $L/gates.log; id=${id}-rerun; continue; fi
  exit $code
done

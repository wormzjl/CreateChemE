#!/usr/bin/env bash
# WP5 gates, one Gradle invocation at a time, logs in wp5-logs/final-gate-*.log.
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36" || exit 2
L=documentation/fluid-scheduler/wp5-logs
G() { local name=$1; shift
  echo "$(date '+%F %T') START $name at $(git rev-parse --short HEAD) $(powershell -NoProfile -Command "\$g=Get-Process Endfield -ErrorAction SilentlyContinue; \$f=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024); if(\$g){'GAME '+\$f}else{'NOGAME '+\$f}")" >> $L/gates.log
  JAVA_OPTS=-Xshare:off ./gradlew.bat "$@" --offline --console=plain "-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Xshare:off" > $L/final-gate-$name.log 2>&1
  local code=$?; echo "$(date '+%F %T') END $name exit $code" >> $L/gates.log
  ls hs_err_pid*.log 2>/dev/null && { for f in hs_err_pid*.log; do mv "$f" "$L/jvm-crash/${f%.log}-gate-$name.log"; done; echo "$(date '+%F %T') CRASH $name" >> $L/gates.log; }
}
G science fluidScienceTest --rerun
G runtime fluidRuntimeTest --rerun
G network fluidNetworkBenchmark --rerun
G regression fluidSolverRegression -PfluidRegressionMode=exact --rerun
G gametest runFluidGameTestServer -PfluidGameTestRunId=wp5-final-r01
echo "GATES DONE" >> $L/gates.log
for f in P12-worker-trajectories.json P31-cadence-trajectories.json; do cp build/reports/fluid/$f $L/$f; sha256sum $L/$f documentation/fluid-scheduler/wp4-logs/$f >> $L/gates.log; done

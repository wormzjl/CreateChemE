#!/usr/bin/env bash
# F3 off-line probes: one Gradle invocation at a time. Usage: probe.sh <tag> <TestClass> [extra gradle args...]
# The probe class sits uncommitted in src/test/java/com/wormzjl/createcheme/runtime/fluid while it runs.
cd "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36" || exit 2
L=documentation/fluid-followups/f3-logs
TAG=$1; CLASS=$2; shift 2
echo "$(date '+%F %T') START probe $TAG $CLASS at $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- src || echo +dirty) $(powershell -NoProfile -Command "\$g=Get-Process Endfield -ErrorAction SilentlyContinue; \$f=[math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024); if(\$g){'GAME '+\$f}else{'NOGAME '+\$f}")" >> $L/gates.log
JAVA_OPTS=-Xshare:off ./gradlew.bat fluidRuntimeTest --tests "com.wormzjl.createcheme.runtime.fluid.$CLASS" --rerun "$@" --offline --console=plain "-Dorg.gradle.jvmargs=-Xmx3G -Dfile.encoding=UTF-8 -Xshare:off" > $L/probe-$TAG.log 2>&1
code=$?; echo "$(date '+%F %T') END probe $TAG exit $code" >> $L/gates.log
ls hs_err_pid*.log 2>/dev/null && { for f in hs_err_pid*.log; do mv "$f" "$L/jvm-crash/${f%.log}-probe-$TAG.log"; done; echo "$(date '+%F %T') CRASH probe $TAG" >> $L/gates.log; }
exit $code

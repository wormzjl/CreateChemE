#!/bin/bash
# WP7d: one Gradle invocation under build/gradle.lock (tag wp7d); refuses while a dev client runs; args: log name, then
# gradle args. The lock is deleted afterwards, also on failure.
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/coolprop-multiphase-thermo-37f6b0 || exit 1
if powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -match 'runMcpClient|fml.modFolders' } | Select-Object -ExpandProperty ProcessId" | grep -q '[0-9]'; then
  echo "a dev client is running; not starting Gradle"; exit 2
fi
log="build/wp7d/$1"; shift
while [ -e build/gradle.lock ]; do echo "waiting for lock: $(cat build/gradle.lock)"; sleep 20; done
echo "wp7d $(date -Iseconds) $*" > build/gradle.lock
trap 'rm -f build/gradle.lock' EXIT
echo "START $(date -Iseconds) $*" > "$log"
JAVA_OPTS=-Xshare:off ./gradlew "$@" --offline >> "$log" 2>&1
code=$?
echo "END $(date -Iseconds) exit=$code" >> "$log"
exit $code

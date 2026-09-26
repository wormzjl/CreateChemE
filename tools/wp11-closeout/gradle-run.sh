#!/bin/bash
# WP11: one Gradle invocation under build/gradle.lock (tag wp11). Waits (polling every 60 s) while a dev client runs
# (a java.exe command line with runMcpClient or fml.modFolders) and while another agent holds the lock; the lock is
# deleted afterwards, also on failure. Args: log name (written to build/wp11/), then the Gradle arguments.
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/coolprop-multiphase-thermo-37f6b0 || exit 1
mkdir -p build/wp11
log="build/wp11/$1"; shift
client() { powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -match 'runMcpClient|fml.modFolders' } | Select-Object -ExpandProperty ProcessId" | grep -q '[0-9]'; }
while client; do echo "$(date -Iseconds) a dev client is running; waiting 60 s"; sleep 60; done
while [ -e build/gradle.lock ]; do echo "$(date -Iseconds) waiting for lock: $(cat build/gradle.lock)"; sleep 60; done
echo "wp11 $(date -Iseconds) $*" > build/gradle.lock
trap 'rm -f build/gradle.lock' EXIT
echo "START $(date -Iseconds) $*" > "$log"
JAVA_OPTS=-Xshare:off ./gradlew "$@" --offline >> "$log" 2>&1
code=$?
echo "END $(date -Iseconds) exit=$code" >> "$log"
exit $code

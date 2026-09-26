#!/bin/bash
# WP7c: one Gradle invocation under build/gradle.lock (tag wp7c); args: log name, then gradle args.
cd /d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/coolprop-multiphase-thermo-37f6b0 || exit 1
log="build/wp7c/$1"; shift
while [ -e build/gradle.lock ]; do echo "waiting for lock: $(cat build/gradle.lock)"; sleep 20; done
echo "wp7c $(date -Iseconds) $*" > build/gradle.lock
trap 'rm -f build/gradle.lock' EXIT
echo "START $(date -Iseconds) $*" > "$log"
JAVA_OPTS=-Xshare:off ./gradlew "$@" --offline >> "$log" 2>&1
code=$?
echo "END $(date -Iseconds) exit=$code" >> "$log"
exit $code

#!/usr/bin/env bash
# The G3 Gradle run under the worktree's lock protocol (AGENTS.md; plan section 9): refuses while a dev client runs, waits
# while build/gradle.lock exists, holds it with the tag wp9b, deletes it afterwards (also on failure). Log in out/.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
if powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -match 'runMcpClient|fml.modFolders' } | Select-Object -ExpandProperty ProcessId" | grep -q '[0-9]'; then
  echo "a dev client is running; not starting Gradle"; exit 2
fi
while [ -e build/gradle.lock ]; do echo "waiting for lock: $(cat build/gradle.lock)"; sleep 20; done
echo wp9b > build/gradle.lock
trap 'rm -f build/gradle.lock' EXIT
LOG="tools/g3-qualification/out/gradle-$(date +%Y%m%d-%H%M%S).log"
echo "HEAD $(git rev-parse --short HEAD); status:" > "$LOG"; git status --short >> "$LOG"
JAVA_OPTS=-Xshare:off ./gradlew test --tests 'com.wormzjl.createcheme.science.thermo.qualification.*' \
  --tests 'com.wormzjl.createcheme.science.fluid.network.NearCriticalNitrogenIslandTest' \
  --tests 'com.wormzjl.createcheme.science.fluid.thermo.FluidPropertyCoverageTest' \
  --tests 'com.wormzjl.createcheme.science.fluid.thermo.FluidNitrogenCryogenicTest' \
  --tests 'com.wormzjl.createcheme.science.fluid.thermo.LiquidCompressionQualificationTest' \
  --tests 'com.wormzjl.createcheme.science.fluid.thermo.LegacyNetworkPathPinTest' \
  --tests 'com.wormzjl.createcheme.science.fluid.thermo.DirectLiquidContinuityTest' \
  --offline >> "$LOG" 2>&1
code=$?
echo "exit $code" >> "$LOG"
echo "$LOG exit $code"

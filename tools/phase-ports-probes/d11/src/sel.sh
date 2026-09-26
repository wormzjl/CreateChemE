#!/usr/bin/env bash
# sel.sh <method selector ...>: recompile main+tests into harness-new, run selected with tracing
S=/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad
cd /home/user/CreateChemE
LIB=$S/lib OUT=$S/d11/harness-new REPO=/home/user/CreateChemE bash tools/cloud-science-harness/harness.sh compile 2>&1 | grep -E 'error|harness: compiled' | head -20
LIB=$S/lib OUT=$S/d11/harness-new REPO=/home/user/CreateChemE HARNESS_JVM_OPTS="-Xmx2g ${JOPTS:-}" bash tools/cloud-science-harness/harness.sh select "$@"

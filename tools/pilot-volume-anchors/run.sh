#!/usr/bin/env bash
# P3 WP3: prints the Tr = 0.8 volume anchors of the pilot records (anchors.json). Run from Git Bash in the worktree
# root (the directory holding src/): bash tools/pilot-volume-anchors/run.sh > tools/pilot-volume-anchors/anchors.json
# Needs javac 21+ and the gson 2.10.1 jar of the Gradle cache (GSON may point elsewhere). No Gradle run.
set -euo pipefail
ROOT="$(pwd)"
GSON="${GSON:-$HOME/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar}"
OUT="${TMPDIR:-${TEMP:-/tmp}}/pilot-volume-anchors-classes"
rm -rf "$OUT"; mkdir -p "$OUT"
REF="$ROOT/src/test/java/com/wormzjl/createcheme/science/thermo/reference"
SEP=":"; RES="$ROOT/src/test/resources"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; GSON="$(cygpath -w "$GSON")"; OUT_W="$(cygpath -w "$OUT")"; RES="$(cygpath -w "$RES")";; *) OUT_W="$OUT";; esac
javac -J-Duser.language=en -nowarn -encoding UTF-8 -d "$OUT_W" -cp "$GSON" -sourcepath "$ROOT/src/main/java" \
  "$REF/AlphaDerivatives.java" "$REF/HelmholtzFluid.java" "$REF/HelmholtzState.java" "$REF/IdealHelmholtz.java" \
  "$REF/JsonFluidFiles.java" "$REF/ResidualHelmholtz.java" "$REF/SaturationAncillary.java" "$REF/SaturationState.java" \
  "$ROOT/tools/pilot-volume-anchors/PrintPilotVolumeAnchors.java"
java -cp "$OUT_W$SEP$GSON$SEP$RES" PrintPilotVolumeAnchors

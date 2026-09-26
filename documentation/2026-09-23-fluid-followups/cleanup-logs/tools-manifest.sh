#!/usr/bin/env bash
# Builds cleanup-logs/tools-manifest.tsv: every file under tools/ with its SHA-256, where it came from and whether it is
# byte-identical to that source. Run from the worktree root.
S=documentation/fluid-scheduler; F=documentation/fluid-followups
src_of() { local t=$1 r=${1#tools/}
  case $t in
    tools/fluid-in-game-rig/mod-side/reattach.patch) echo "git diff c1b8464 c1b8464~1";;
    tools/fluid-in-game-rig/mod-side/FluidInGame*.java) echo "git:88df883:src/main/java/com/wormzjl/createcheme/runtime/fluid/$(basename $t)";;
    tools/fluid-in-game-rig/mod-side/baseline-eb28fc5/*) echo "$F/f2-rig/baseline-rig/$(basename $t)";;
    tools/fluid-in-game-rig/campaigns/f1/*) echo "$F/f1-rig/$(basename $t)";;
    tools/fluid-in-game-rig/campaigns/*) echo "$F/f2-rig/$(basename $t)";;
    tools/fluid-in-game-rig/f1-table.js) echo "$F/f1-rig/f1-table.js";;
    tools/fluid-in-game-rig/changes-from-f2-rig.diff) echo "generated (git diff --no-index)";;
    tools/fluid-in-game-rig/README.md) echo "written";;
    tools/fluid-in-game-rig/*) echo "$F/f2-rig/${t#tools/fluid-in-game-rig/}";;
    tools/fluid-paced-analysis/wp2*|tools/fluid-paced-analysis/winlen.js) echo "$S/wp2-logs/$(basename $t)";;
    tools/fluid-paced-analysis/wp3*) echo "$S/wp3-logs/$(basename $t)";;
    tools/fluid-paced-analysis/wp4*) echo "$S/wp4-logs/$(basename $t)";;
    tools/fluid-probes/wp0/probes/*) echo "$S/wp0-reference/$(basename $t)";;
    tools/fluid-probes/wp2/probes/*) echo "$S/wp2-probes/$(basename $t)";;
    tools/fluid-probes/wp2/run-scripts/*) echo "$S/wp2-logs/$(basename $t)";;
    tools/fluid-probes/wp3/*/*) echo "$S/wp3-logs/$(basename $t)";;
    tools/fluid-probes/wp4/dev-edits/*) echo "build/wp4tools/$(basename $t)";;
    tools/fluid-probes/wp4/run-scripts/*) echo "$S/wp4-logs/$(basename $t)";;
    tools/fluid-probes/f1/probes/*) echo "$F/probes/$(basename $t)";;
    tools/fluid-probes/f1/*/*) echo "$F/f1-logs/$(basename $t)";;
    tools/fluid-probes/f2/probes/*|tools/fluid-probes/f2/instrumentation/*) echo "$F/f2-logs/probes/$(basename $t)";;
    tools/fluid-probes/f2/scripts/*) echo "$F/f2-logs/scripts/$(basename $t)";;
    tools/fluid-probes/f2/run-scripts/*) echo "$F/f2-logs/$(basename $t)";;
    tools/fluid-probes/f3/instrumentation/*) echo "git diff 68d8877 68d8877~1 -- FluidCheckpointCodec.java";;
    tools/fluid-probes/f3/probes/*) echo "$F/f3-logs/probes/$(basename $t)";;
    tools/fluid-probes/f3/run-scripts/*) echo "$F/f3-logs/$(basename $t)";;
    tools/fluid-probes/f4/probes/*) echo "$F/f4-logs/probes/$(basename $t)";;
    tools/fluid-probes/f4/nist/*) echo "$F/f4-logs/nist/$(basename $t)";;
    tools/fluid-probes/f4/run-scripts/*) echo "$F/f4-logs/$(basename $t)";;
    tools/mcp-gui-helpers/records/*) echo "build/wp3mcp/$(basename $t)";;
    tools/mcp-gui-helpers/changes-from-build-wp3mcp.diff) echo "generated (git diff --no-index)";;
    tools/mcp-gui-helpers/*.java|tools/mcp-gui-helpers/*.class|tools/mcp-gui-helpers/*.ps1) echo "build/wp3mcp/$(basename $t)";;
    tools/retired/fluid-profile-replays/reattach.patch) echo "git diff 68d8877 68d8877~1 -- build.gradle and the two tests";;
    tools/retired/fluid-profile-replays/*.java) echo "git:c1b8464:src/test/java/com/wormzjl/createcheme/fluid/benchmark/$(basename $t)";;
    *) echo "written";;
  esac; }
printf 'tools path\tsha256\tsource\tsource sha256\tstatus\n'
find tools -type f | sort | while read -r t; do
  h=$(sha256sum "$t" | cut -c1-64); s=$(src_of "$t")
  case $s in
    git:*) spec=${s#git:}; sh=$(git show "$spec" | sha256sum | cut -c1-64);;
    written|generated*|"git diff"*) sh="-";;
    *) [ -f "$s" ] && sh=$(sha256sum "$s" | cut -c1-64) || sh="MISSING";;
  esac
  if [ "$sh" = "-" ]; then st="${s%% *}"; elif [ "$h" = "$sh" ]; then st=identical; else st=edited; fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$t" "$h" "$s" "$sh" "$st"
done

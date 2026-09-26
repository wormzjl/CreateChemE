#!/usr/bin/env bash
# Copies the branch's FluidInGameDiagnostics into the uncommitted baseline rig (eb28fc5 has no certificates, so the
# kind of every island is AWAKE); the rest of the baseline rig patch is in baseline-rig.patch.
W=/d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36
B=/d/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/fluid-baseline-eb28fc5
F=src/main/java/com/wormzjl/createcheme/runtime/fluid/FluidInGameDiagnostics.java
sed 's|kinds.merge(s.certificate().map(c->c.kind().name()).orElse("AWAKE"),1,Integer::sum);|kinds.merge("AWAKE",1,Integer::sum); // no certificates before WP2|' "$W/$F" > "$B/$F"
diff "$W/$F" "$B/$F"

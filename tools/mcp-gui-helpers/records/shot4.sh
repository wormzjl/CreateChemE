#!/bin/bash
# shot4.sh <name>: flatten a bridge screenshot already written to wp4-screenshots/<name>.png
f="D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36/documentation/fluid-scheduler/wp4-screenshots/$1.png"
"/c/Program Files/Java/jdk-21.0.11/bin/java.exe" -cp "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/agent-ae139e4fc1b184b36/build/wp3mcp" Flatten "$f"

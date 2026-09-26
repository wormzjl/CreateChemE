# Column GUI Minecraft MCP helpers
Batch: documentation/2026-09-24-column-gui. Detached during merge preparation on 2026-09-24.

Purpose: correct MCP 0.3.0 drag event dispatch and framebuffer screenshots during GUI verification at 2560 x 1440 / scale 3. These helpers never enter the product jar. The base runMcpClient tooling predates this batch and remains tracked.

Source archive: src/mcpCompat/... and build.gradle exactly match git show 7cc7d12:<path>. The latter two files preserve the complete original config as evidence, not a standalone build. Removal commit: 43bf7c5 (sources exist at parent 7cc7d12). reattach.patch applies against the removal commit and restores exactly the batch helpers/window arguments; run git apply --check first in an isolated worktree. Never overwrite another agent's worktree.

Preferred use without source changes, from an isolated worktree with the documented pinned MCP jar installed:
    ./gradlew.bat --no-configuration-cache -I D:/Minecraft/Modding/1.21/CreateChemE/tools/column-gui-mcp/client.init.gradle runMcpClient
JDK 21 required. This init file adds the external helper source/resource only to mcpCompat and sets its window size. It does not alter any gate task, normal runClient, or product packaging. Exit the client before invoking any test/Gradle task. A later ordinary runMcpClient rebuilds its resources/classes without these detached additions.

Alternative: git apply reattach.patch; run runMcpClient; after closing, reverse only that patch with git apply -R reattach.patch.

Verification worlds remain in the isolated ignored run/mcp-client/saves directory for owner testing; they are never committed or used as compatibility fixtures.

Verified on candidate 43bf7c5: external init launch succeeds at 2560 x 1440, and the MCP screenshot helper captures the full framebuffer. No tracked source changes; normal production jar excludes all MCP helper classes.

# Requested opening snapshot plan

In progress since 2026-09-24 on codex/column-gui.

Owner change: opening a fluid GUI requests an immediate replay of the server's last published update. GUI/process data must not be broadcast to clients without a request. Update AGENTS.md to distinguish cached replay from new engine state and acknowledgements.

Cache a complete immutable MenuData at each device's scheduled presentation, including controls and static metadata from the same revision, with no player-specific reply. After the requested menu's open packet and installation, send its cached static/live payloads using the existing protocol. Seed only the subscription's delivered static revision; do not change its next update deadline or pending replies. Cache creation remains inside the engine bucket. Unload/removal/server close release cache entries. A never-published device waits for its first bucket.

Test first opening after a device ran without viewers, no-cache startup, no reads/materialisation/solves during replay, unchanged scheduled cadence and delayed input acknowledgements. Verify first-open behavior in a new client session through the Minecraft bridge, then leave the client open.

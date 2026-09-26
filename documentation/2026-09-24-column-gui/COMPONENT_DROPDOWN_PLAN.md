# Text input and component dropdown plan

In progress since 2026-09-24, same column GUI branch/batch.

Retain the slate/aqua native instrument style and font. Replace the full-page component picker with one compact edit field and a six-row overlay directly beneath it. Keep the user's existing composition visible; a small arrow signals the dropdown. Exact/prefix matches rank before partial names, subsequences and bounded spelling errors. Search includes localized component labels and stable server IDs.

Share dropdown, matching and focused-text key routing between column and generator. Focused fields consume inventory/gameplay shortcuts, including E and copy/paste editing, before the container screen. Escape first dismisses an open dropdown, then closes the GUI; Tab traverses focus. Arrow keys and Enter select, click-away dismisses, wheel/drag scroll long suggestion lists. Do not rebuild the screen on each typed character.

Validate ranking/typos deterministically in ordinary tests, then use real key events through the Minecraft bridge to verify E does not close either screen and Enter adds a suggested component. Preserve earlier client-open preference.

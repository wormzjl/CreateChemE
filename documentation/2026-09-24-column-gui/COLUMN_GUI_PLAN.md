# Column GUI implementation

In progress since 2026-09-24 on codex/column-gui, isolated worktree column-gui/CreateChemE, base f9d6be1.

Scope: immutable accepted profiles/audits and round-trip codecs; engine-queued column actions with 100-online-tick presentation; draft preservation and consistent result freshness; Overview/Setup/Results/Diagnostics with native responsive controls, stage selection and plots. Numerical policy unchanged. Export/custom feed/progress history/cancel deferred per review.

Ownership: workers extract immutable accepted inspection data, server owns events and result publication, client owns drafts/selection. No worker touches Minecraft, no thermodynamics on tick or render paths. The fluid engine online epoch schedules column actions and presentation. Unsent menu actions are session commands and expire with their menu; accepted operations remain block-owned.

Validation: focused profile/codec/editor/deadline tests, full ordinary tests, column GameTests where applicable, then fresh-world MCP dev-client verification. One Gradle invocation at a time; no tests while client runs. GUI screenshots and results go into this batch. New wire/save versions reject unsupported versions; no migrations.

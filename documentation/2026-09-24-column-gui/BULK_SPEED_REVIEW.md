# Five-second bulk speed change
Implemented 2026-09-24 in main merge 9674bf1, release 0.5.0. Source commit 9817ecc.

Bulk speed uses accepted gross transported volume in the preceding 100 online ticks / 5 seconds / the local pipe bore area. The ephemeral per-island PipeSpeedWindow records every accepted solver interval and certified replay, including both directions, all fluid phases and solids. Rejected/stale candidates do not contribute. History is pruned to the window and memoized per online tick; all access stays on the coordinator owner thread. No new worker, per-tick process calculation, solver invocation or GUI-open calculation was added.

Partial overlaps use each accepted interval's recorded mean volume rate. Missing, unsolved or startup history contributes zero to the fixed five-second denominator. Reload/topology replacement starts a fresh presentation history, so it warms up over five online seconds; this cache is not checkpoint state. No wire/save format change. Flow-rate/composition/pressure metrics retain their existing intervals; only bulk speed changes, in overview and connections alike. Tooltip states the five-second basis and warm-up.

Owner explicitly requested no verification and immediate merge. No tests, compilation or live GUI verification were run for 9817ecc. The 1,093-test passing merge-preparation run predates this final change. The existing client was left open on the older running build; restart it to load the new bulk-speed calculation.

Main merge 9674bf1 contains version 0.5.0 and the dated changelog entry. No remote push performed.

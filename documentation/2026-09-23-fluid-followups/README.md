# Fluid scheduling follow-ups (started 2026-09-23)

Owner decisions on the batch review `../2026-09-23-fluid-scheduling-rest/FLUID_ISLAND_REST_REVIEW.md` section 5, given 2026-09-23 after the 0.3.0 merge:

| item | decision | package |
|---|---|---|
| Pumped fills held for minutes or forever (in-game finding) | find the root cause and fix | F1 (done 2026-09-24: fills fixed; gas transfers remain, see F4) |
| Placement quadratic in world device count (in-game finding) | find the root cause and fix | F2 |
| 1. Default stationarity tolerance 1e-9 | loosen it (1e-7) and keep it configurable | F1 |
| 2. Revalidation against the certified interval vs its extrapolation | keep for later | none |
| 3. Start-up wall-budget retry nondeterminism | fix | F1 |
| 4. 64 MiB checkpoint bound | replace with the recommended storage (not one JSON/NBT blob) | F3 (done 2026-09-24: format 4, per-island binary units in pack files, no bound; `f3/`) |
| 5. Refused save crashes the client | do nothing | none |
| Gas transfers hold at the 273.16 K property floor (F1 finding) | 2026-09-24: implement pump option P1 (pressure rise scaled by suction density); ALSO extend the thermo package domain below the floor because cryogenic air separation comes later; a state outside the package domain must raise a dedicated error made clear to the player and to agents | F4 (done 2026-09-24: P1, `fluid_domain` data, `ThermoDomainViolation`; `f4/`) |

Branch `claude/fluid-followups` from main 23beadd, worktree `agent-ae139e4fc1b184b36`. Reports land here as each package is verified.

Status 2026-09-24: F1 to F4 done and verified; release commit `d6bcc1e` (0.4.0) prepared on `claude/fluid-followups` over `dea8a7e`; then the AGENTS.md tooling rule (`88df883`) and the tooling cleanup (`c1b8464`..`f9d6be1`, review `FLUID_TOOLING_CLEANUP_REVIEW.md`, tools in the main `tools/`, see `TOOLS.md`); branch tip `f9d6be1`, MERGED TO MAIN 2026-09-24 by fast-forward (23beadd to f9d6be1, version 0.4.0, not pushed). Existing fluid worlds are refused at load (format 4, changed thermodynamic revision): create fresh worlds. Open owner decisions: whether islands loaded from a save should also start on a one-tick slice (F1); whether solver entry traces of out-of-range components should be exempt from the domain rule (F4 open item 1); the deferred items 2 and 5 above. Reports: `f1/`, `f2/`, `f3/`, `f4/` (the agent-facing error reference is `f4/THERMO_DOMAIN_ERROR.md`).

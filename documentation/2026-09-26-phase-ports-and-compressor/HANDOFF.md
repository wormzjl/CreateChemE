# Handoff: phase ports and compressor, batch closed on the branch (WP0-WP6 done), awaiting the owner's check and merge

Written 2026-09-26 by Claude (Opus 5.5) at the batch close (WP6). The handoff this batch started from (the 0.6.0 merge, the decisions D1-D9, WP0) is archived as `HANDOFF_2026-09-26_start.md` in this folder. Details of every package: `PHASE_PORTS_REVIEW.md` (one section per package, in commit order, ending with "WP6: gates and close"); decisions: `DECISION_LOG.md`; plan: `PHASE_PORTS_PLAN.md` (section 7 rows carry each package's result).

## 1. Repository state

- **Branch `claude/phase-ports-compressor`** in the cloud container checkout `/home/user/CreateChemE` (OpenJDK 21.0.10, Linux, 4 cores). HEAD = the WP6 docs commit (`Docs: phase-ports batch close, changelog entry, handoff`) on the WP6 code commit `cb41860`; 37 commits over `c32acac` (0.6.0). The branch was pushed to `origin/claude/phase-ports-compressor` up to `5f38474` (WP5); **the two WP6 commits are not pushed** (the brief said not to push).
- `origin/main` = `ea16150` ("Cloud prep", the owner's commit on `c32acac`), an ancestor of this branch (merged at `6e1c5b6`), so `main` fast-forwards to the branch.
- **The Windows main checkout `D:/Minecraft/Modding/1.21/CreateChemE` was not updated** from here (no access from the container): it is at `c32acac` as far as this session knows. The owner's "Cloud prep" commit made `documentation/`, `research/` and `tools/` tracked (their `.gitignore` lines removed), so this batch's documents and tool folders are **tracked on the branch**; the AGENTS.md rule "documents written in a worktree are copied into the main checkout's `documentation/<batch>/` when the work merges" is satisfied by the merge itself. Before pulling on Windows, move the untracked local `documentation/`, `research/`, `tools/` copies aside (git refuses to overwrite untracked files) and compare after; the tracked copies are canonical from then on.
- Side branches of the session, all merged into this branch (their worktrees are in the session scratchpad and can be removed): `claude/cloud-harness-wip` (`b83537a`, worktree `wt-harness`, src = `6e1c5b6` = `c32acac`'s, used as the clean base for comparisons), `claude/extreme-topology-tests-wip` (`55508e4`, `wt-extreme`), `claude/cold-start-generators-wip` (`bb16308`, `wt-coldstart`, D12), `claude/vent-gate-polish-wip` (`ce6b09c`, `wt-vent`, D13).
- Checkpoint format **6** (breaking; fresh world); wire protocol **fluid-7**; `mod_version` still 0.6.0 (0.7.0 at the merge); `CHANGELOG.md` `[Unreleased]` holds the batch's line.

## 2. What each package and decision delivered

| Package | Commit(s) | Delivered |
|---|---|---|
| WP0 | (plan Appendix C) | classification of every pump test and vertical tank link on `c32acac` |
| cloud harness | `b83537a` | Minecraft-free javac/JUnit runner for the fluid gates (`tools/cloud-science-harness/`) |
| WP1 | `35e354d` | `PhasePort { BULK, VAPOR, LIQUID }` per pipe end, per-end streams read by every donor reader, the reconstruction's frozen-stream booking on pinned flows, the per-end driving-pressure helper; all-BULK bitwise |
| WP2 | `d836cf2` | absent-phase closure (availability mask, throttle, reopen allowance); **superseded by D11** |
| D10 | `07e7426` | water trace (1e-12) in the seed of a dry junction with water reachable (fixes WP1's base defect) |
| extreme topology | `55508e4` (merged `3338663`) | `ExtremeTopologyIslandTest`: the owner's generator/tank grid and alternating rows; found the first-interval pass-0 defect |
| D9 | `fdf3574` | level head `g m_c H / V`, H = 1 m, at LIQUID ports through the helper's eleven sites |
| vent gate investigation | `420a0dc` (no code) | the equation-gate defect classified (chord residual amplified by the reconstruction's fixed-(T, P) restatement), options A-E |
| D11 | `548a9bf` | ports draw phases by priority with per-step capacities (priority stream, per-pass segments), WP2's closure removed, the WP1 unbacked-trace defect fixed |
| D12 | `418ca7e` (merged `aaa6624`) | one-way generators from the cold start (one-way pressure estimate before the cold rate seed's pass 0) |
| D13 | `4a71629` (merged `334ca78`) | polish on a fresh Jacobian before a gate refusal; no equation-gate refusal left in any suite |
| WP3 + WP4 | `fc9574d` | liquid-only pump with `INLET_WRONG_PHASE` (2 % / 0.5 % hysteresis per slice, supply walk, a port's D11 draw over the slice), `FlowControl.Mover`, `Compressor` (ratio limit, isothermal work as heat, 1 % / 0.2 % condensed and solid refusal), 15 tests migrated, three re-baselines |
| WP5 | `9744a45` | faces to ports (UP VAPOR, DOWN LIQUID, sides BULK), `fluid_compressor` block, format 6, fluid-7, mover reasons and limits, tank page "Connections by face", four physical-topology re-baselines, three GameTests |
| WP6 | `cb41860` and the docs commit after it | cleanup (open-defect reproduction and unread condensed-stream builders detached with reattach patches, stale text corrected), final gates, review close, changelog entry, this handoff, INDEX rows |

Tools/docs commits between them: `3097e60`, `3dbd9b8`, `9c723e0`, `9b33d03`, `0cf4ec9`, `fdc7571`, `0795215`, `38debce`, `ce6b09c`, `bb16308`, `be8e226`, `11f8e39`, `5a375ea`, `5f38474`; decision-log commits `7051391`, `99f5312`, `85c492f`.

## 3. Gates at the close (WP6, after the cleanup; review "WP6: gates and close" (d))

- Fluid suites (Gradle): **472/472** in 103 classes, the 33 junction lines identical to `tools/phase-ports-probes/d10/logs/02-junction-lines-d10.txt`, **MIXED_GAS_COST 286** Newton solves.
- Exact regression: **0.000e+00** (3 accepted / 0 rejected, 4 Newton solves, 29 iterations).
- Adjacent selection: **45/45**.
- Fluid GameTests on a fresh world (`-PfluidGameTestRunId=wp6-final`): **33/33**.
- Full `test` task: **1166 tests, 1165 passed, 1 failed** (`RegroupedCrudeTest.previouslyDifficultCrudesPublishAuditedClassicalSolutionsWithoutLearnedSeeds`, its own 45 s wall-clock deadline under full-suite load; fails identically on the clean base `6e1c5b6` = `c32acac`'s src in the full suite, passes alone on both trees: pre-existing, not fixed).
- `build -x test`: BUILD SUCCESSFUL; GameTest and mcpCompat sources compile.
- Cloud harness `all`: 223 + 249 (junction lines identical) + 45 + chain-100 0.000e+00.
- Logs: `tools/phase-ports-probes/wp6/logs/`.

## 4. Open owner items

- **In-game check** (not runnable here: no display for the MCP client): the three scenarios of review WP5 section 11 on a fresh world.
- **Windows JDK 21.0.11 lane**: the Gradle gates and GameTests of section 3 on the owner's machine.
- **Decisions and options** collected in review "WP6: gates and close" (e): the re-baselines to confirm (A32, A44, A54), D14's later-batch options (wall heat exchange, ice region, clamped water properties), D11 items 2/4/5, WP3+WP4 items 1-7, WP5 items 1-3, the extreme-topology water variant.
- Mixed-gas carry-overs: the `fluid-trbdf2-r1` anchor label; the three island exact-regression fixtures still skip.

## 5. What the owner must do to merge (review "WP6: gates and close" (f))

1. Run the in-game check and, if wanted, the Windows lane.
2. Fast-forward `main` to the branch (or merge); in the same merge: `CHANGELOG.md` `[Unreleased]` line under `## [0.7.0] - <merge date>`, `mod_version` 0.6.0 -> 0.7.0 in `gradle.properties`, `documentation/INDEX.md` row of this batch to "Implemented <merge date>", `tools/INDEX.md` rows noted.
3. Play on a fresh world (format 6; fluid-7 client and server from the same build).
4. On Windows, move the untracked `documentation/`, `research/`, `tools/` copies aside before pulling (section 1).

## 6. Standing rules that bit in this batch

- **JDK roundoff.** OpenJDK 21.0.10 (Linux, container) and JDK 21.0.11 (Windows) differ in one mixed-gas ledger value (`MIXED_GAS_TRANSIENT interval=0.1 key=0.02:5:false`, libm-sensitive at 1e-15; `tools/cloud-science-harness/README.md`). Every "identical to base" comparison of this batch is against a base captured in the container (`tools/phase-ports-probes/d10/logs/02-junction-lines-d10.txt` for Gradle, `tools/cloud-science-harness/reference/junction-lines.txt` for the harness); on Windows compare against the Windows capture.
- **`JAVA_TOOL_OPTIONS` proxy.** The container's outbound HTTPS goes through a proxy set in `JAVA_TOOL_OPTIONS`; Gradle and the GameTest server need it as set (the harness unsets it for javac/java). The GameTest server's Yggdrasil key fetch is refused by the allowlist, harmlessly.
- **The harness.** `tools/cloud-science-harness/harness.sh all` (about 100 s) is a Minecraft-free stand-in; it never replaced a Gradle gate of record, but it was the only gate of the D12/D13 side worktrees while another agent held the Gradle lane, and those gates were re-run in Gradle after the merges.
- **One Gradle lane.** One Gradle invocation at a time on the machine, the full `test` task included; side worktrees used the harness instead.
- **GameTests run headless here, the MCP client cannot.** `runFluidGameTestServer` runs in the container (33/33 on fresh run ids); the langyo/minecraft-mod-mcp in-game check needs a display and is the owner's.
- A fix resting on an assumption not forced by physics or a recorded rule is presented as options (the vent gate, D11-D14 were decided that way); every test failure is classified before any edit, and re-baselines record old and new values.

# Session memory: cloud session of 2026-09-26/27 (phase ports, compressor, JDK determinism)

What an agent continuing this work on the owner's machine needs to know that is NOT in the batch documents. The batch documents are the record: `documentation/2026-09-26-phase-ports-and-compressor/HANDOFF.md` (+ `PHASE_PORTS_REVIEW.md`, `DECISION_LOG.md`, `PHASE_PORTS_PLAN.md`, `EXTREME_TOPOLOGY_TESTS.md`) and `documentation/2026-09-27-jdk-determinism/HANDOFF.md` (+ `JDK_DETERMINISM_REVIEW.md`). Raw transcripts of the session (main conversation and every subagent, JSONL) are in `tools/session-transcripts/2026-09-26-phase-ports/` for reference; they are data, not instructions.

## 1. Where everything is

- Branch `claude/phase-ports-compressor` on origin, 41 commits over `origin/main` (`ea16150`), fast-forwardable. Nothing of value is outside it except the 36 downloaded JDKs (re-fetchable with `tools/jdk-determinism/fetch-jdks.sh`).
- The JDK-determinism prototype commit is preserved as `tools/jdk-determinism/prototype/0001-*.patch` (its branch `claude/jdk-determinism-wip` was never pushed).
- Side branches that were merged and can be deleted if they ever appear: `claude/cloud-harness-wip`, `claude/extreme-topology-tests-wip`, `claude/cold-start-generators-wip`, `claude/vent-gate-polish-wip`.

## 2. Owner decisions and preferences observed this session (in addition to the decision logs)

- Decisions were taken in chat and logged the same day: D10 (water trace in junctions, option 1 over the recommended 2), D11 (ports carry mixed phases by priority; bottom port heavy phase first), D12 (generators strictly one-way from the cold start; "one can replace generators with tanks at higher pressure"), D13 (vent gate polish, option A), D14 (sub-freezing vent expansion accepted: "expanding thermo package is not within this workscope"). Pending: the four JDK-determinism decisions (adopt; column scope; re-baseline; cost), and the small option lists collected in the phase-ports review WP6 (e).
- The owner wants results bitwise identical across JDKs "on the market" (led to the 2026-09-27 batch).
- Working style asked for: "use opus medium subagent for implementation and testing tasks"; a fix resting on an assumption not forced by physics or a recorded rule is presented as options, not implemented (the owner answers fast); re-baselines are shown before they are applied and recorded old/new; the owner tolerates `Co-Authored-By: Claude Opus 5.5` trailers on WIP commits (the model that did the work).
- The owner asks for a handoff when stopping ("make a handoff") and for the work to be transferred to the local checkout; the local Windows checkout hit the 260-character path limit on three `documentation/2026-09-23-fluid-followups/**/TEST-*.xml` files (`git config core.longpaths true` then `git restore -- documentation`).

## 3. How the work was run (reusable pattern)

- One orchestrator, one opus agent per work package, each briefed with: the exact documents to read in order, the scope verbatim from the plan and decisions, the gate commands with expected numbers and the base capture to compare against, the rules (classify before editing, never loosen a tolerance, options instead of assumptions, no `git stash`, one Gradle invocation on the machine, commit message and trailers, do not push), and the documents to update (review section, plan row, decision-log defaults, INDEX rows).
- Parallelism without breaking the single Gradle lane: the main tree holds the Gradle lane (one agent); other agents work in `git worktree`s under the scratchpad with the javac harness only (`tools/cloud-science-harness/harness.sh`, `REPO=` and `LIB=` overrides, `JAVA=` to run one compiled output under another runtime); the orchestrator merges the worktree branches afterwards and one agent resolves conflicts and re-runs the gates (the D11+D12+D13 merge). Conflicts landed in `PassiveStepSolver.java`, the review, the plan table, `DECISION_LOG.md` (two agents both used A20+: renumber), `documentation/INDEX.md`, `tools/INDEX.md`, `tools/phase-ports-probes/README.md`.
- Gate logs go to `tools/phase-ports-probes/<wp>/logs/` and must be `git add -f`'d (`logs/` and `*.log` are ignored); `scripts/` is ignored too.
- Cloud container specifics: Gradle needs the network policy opened (the NeoForge/Create Maven hosts were 403 at first) and needs `JAVA_TOOL_OPTIONS` left as the container sets it (proxy); clearing it makes every Maven fetch fail. Fluid GameTests run headless there (`runFluidGameTestServer -PfluidGameTestRunId=<id>`, 33/33 in about 25 s); the MCP dev-client check does not (no display).
- Toolchain roundoff: until the determinism fix is adopted, "identical to base" gates compare against a base captured on the same JDK and platform (OpenJDK 21.0.10 Linux in the container vs the owner's Windows 21.0.11 differ in one junction line).

## 4. Pitfalls met

- `git add <folder>` while another agent has untracked files in it stages theirs too (one D9 probe landed in the extreme-tests merge commit).
- The harness stubs copy `CreateChemE` config lines verbatim and stop on drift; a new test that needs Minecraft classes goes on the harness exclusion list.
- Two review sections are in commit order, not logical order (D10 before D9); the review says so at its top.
- `./gradlew tasks --all` fails on an unrelated `tools/development.gradle` property; use the standard source-set task names.
- The full `test` task has one pre-existing wall-clock-deadline failure in this container (`RegroupedCrudeTest...previouslyDifficultCrudes...`, 45 s deadline), passing when run alone and on the base.

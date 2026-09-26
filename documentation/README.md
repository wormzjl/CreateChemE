# documentation/

Local, git-ignored working documents of CreateChemE: plans, reviews, results, diagnoses, handoffs and guides. The copy in the main checkout (`D:/Minecraft/Modding/1.21/CreateChemE/documentation/`) is the canonical one; worktree copies are drafts until they are consolidated here.

Start with [INDEX.md](INDEX.md): one row per batch of work with its status and date, and a file lookup table.

## Layout

- `YYYY-MM-DD-topic/`: one folder per batch of work, dated by the day the batch started, so the folder list sorts chronologically. A batch is one coherent piece of work that is planned, reviewed and merged together (a feature, an investigation, a fix series).
- Inside a batch folder:
  - `<TOPIC>_PLAN.md` for plans and `<TOPIC>_REVIEW.md` for reviews and audits; results, progress logs, diagnoses and handoffs keep their descriptive names.
  - `screenshots/` for GUI evidence, `sources/` for literature PDFs and OCR excerpts, `archive/` for frozen probe code and raw results, `codex-branch/` for copies taken from Codex worktrees.
- `reference/`: living guides that span batches.
- `repository/`: repository hygiene records (this organization, deleted-branch lists).

File names were kept unchanged when the flat folder was split on 2026-09-23, so a name cited anywhere (agent memory, commit messages, Java comments, `build.gradle`) can still be found: use the lookup table at the end of [INDEX.md](INDEX.md).

## Where other material lives

| Folder | Holds | Tracked |
|---|---|---|
| `documentation/` | Markdown documents per batch (this folder) | no |
| `research/` | Datasets, literature sources, study harnesses and journals; see [research/INDEX.md](../research/INDEX.md) | no |
| `tools/` | Offline scripts, training/evaluation tooling, DWSIM automation and `tools/development.gradle`; see [tools/INDEX.md](../tools/INDEX.md) | no |
| `examples/` | Example datapack and fluid reference/benchmark scripts used by the build and tests | yes |
| `benchmarks/` | Large benchmark outputs (about 2 GB) cited by `tools/development.gradle` defaults; left in place | no |
| `BRANCH_HANDOFF.md`, `BRANCH_HANDOFF_ARTIFACTS.json` (repository root) | V4 neural branch handoff, written there by `tools/capacity-followup/build_branch_handoff.py` | no |
| `CHANGELOG.md` (repository root) | Release history and version rule | yes |

## Update rule

After every task, in the same session that finishes it:

1. Put the task's documents in the batch folder (create `YYYY-MM-DD-topic/` for a new batch). Reviews go to `<batch>/<TOPIC>_REVIEW.md`, plans to `<batch>/<TOPIC>_PLAN.md`.
2. Update the batch row in [INDEX.md](INDEX.md): status with its date (Implemented / In progress since / Planned / Concluded / Abandoned or Superseded with the reason), main documents, code areas; add or refresh the batch note with what is still open.
3. Documents written in a worktree are copied into this folder when the work merges (or when the task ends, if it never merges).
4. On every merge to `main`: add the `CHANGELOG.md` entry and bump `mod_version` in `gradle.properties` (minor per merged batch, patch for fixes-only merges). The rule is in `AGENTS.md`.

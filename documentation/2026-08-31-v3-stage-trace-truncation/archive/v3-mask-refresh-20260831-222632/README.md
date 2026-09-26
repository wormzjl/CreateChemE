# Rolled-back V3 mask-refresh experiments

Archived 2026-08-31 from `codex/v3-55kpa-mask-refresh` at base `54a4203`.
The user requested rollback of the branch work before trying conservation-first approximation.

This directory contains hash-verified copies of all 17 changed/new files from the prior P0/P0b/P3
probe work, preserving relative paths. `tracked-changes.patch` contains the four tracked-file edits.
The remaining thirteen files were untracked probe code, findings and JSON reports.

All old active probe changes were removed from the implementation checkout. No production code,
property data, test code, or main-worktree `.gitignore` change was rolled back. This archive is outside
the implementation checkout and is not an active source dependency.

Restoration, if explicitly requested later: apply `tracked-changes.patch` at the same base and copy
the thirteen previously untracked files back to their relative paths. Do not overwrite subsequent
work without reviewing its diff.

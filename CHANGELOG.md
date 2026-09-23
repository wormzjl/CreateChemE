# Changelog

All notable changes to CreateChemE are recorded in this file. The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), and `mod_version` in `gradle.properties` follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).

## Version rule

- `mod_version` in `gradle.properties` is the single version number; it names the jar and the mod metadata.
- Every merge to `main` adds its entry here and bumps `mod_version` in the same merge:
  - **minor** (`0.x.0`) for a merged batch of work: a feature, a solver or model change, new content;
  - **patch** (`0.x.y`) for a merge that contains fixes only.
- The major version stays `0` until the owner declares a public release.
- A work branch writes its entry under `[Unreleased]`; the merge moves it under a new version heading dated with the merge day.
- One line per batch of work, with its main commits and the name of its batch folder in the local `documentation/` index (`documentation/INDEX.md`, not tracked).
- `0.1.0` is the state of `main` before this changelog existed. Its section was backfilled from `git log --first-parent main --date=short` since 2026-08-01. No git tags exist for these versions; they are identified by commit id (`0.1.0` = `3c27271`).

## [Unreleased]

## [0.3.0] - 2026-09-23

### Added

- Fluid scheduling WP0: opt-in server-thread counters of scheduling work (island visits, module scans, topology and island snapshots, views, menu packets, pumps, dispatches), elapsed-window `transient100`, `rest100` and `mixed100` benchmark profiles beside `stress100`, and bitwise trajectory fingerprints for the P12 and P31 qualification tests (`eb28fc5`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling WP2: rest and steady-flow certificates. An island whose solved intervals repeat exactly (REST) or within `eps_s` (STEADY) advances by identity or by scaled replay of its last solved interval without solving, releases its solver caches, holds at most one deadline (its horizon or next module drive), is materialised on demand, revalidates at its horizon, and requalifies after a property hold; a pipe that moves less than `eps_s` of the inventory it draws on is exempt from the relative flow test; `[fluid]` keys `restDetection`, `certificateStationaryTolerance`, `certificateInventoryBudget`, `certificateMaximumIntervals`, `restConfirmIntervals`, `restRecheckSeconds` (`3075004`, `4f1686e`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling WP3: `viewers` benchmark profile: `mixed100` with every fixture chunk loaded and bound, 16 open menus and scripted edits through the real edit handler; the report adds presentation packets, view builds and device presentations per 100 ticks per consumer, bucket flushes, and every edit's reply with its latency; every paced report states its configured warm-up and window and how its reference tick was derived (`04632e9`, `2f8d43a`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling WP4: save time in the paced benchmark: at the end of the window the whole fixture is saved cold (payload cache cleared), warm (the next save in the same tick) and once more one cadence later, each with capture and encode milliseconds, payloads encoded and copied, payload, ledger and topology bytes and the gzip size; GameTests for a module-coupled set through a property hold and for a saved resting world reloading certified with no solve; a load log line with islands and certificates saved, restored and discarded (`4dcfa01`, `86d3332`, `ce25b78`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling WP5: opt-in in-game performance rig. `-PfluidRigDiagnosticTicks=N` on the client and server runs enables `FluidInGameDiagnostics`, which logs a `fluid_diag` line every N server ticks (island kinds and statuses, scheduling-counter deltas, worker allocation, per-tick engine and whole-tick time), every gap of more than 250 ms between ticks, and every tick's times to `fluid_diag_ticks.csv`; on a client it also logs the frame rate once a second to `fluid_diag_fps.csv`. `-PfluidRigHeapMiB` sets the run's `-Xmx`, `-PfluidClientQuickPlay` opens a singleplayer world at launch. Nothing is registered or passed when the switches are absent. The datapack generator, run drivers, JFR settings, samplers, parsers and results are in the local batch folder (`documentation/fluid-scheduler/wp5-rig/`, `wp5-logs/`) (`36caef6`, `39aabac`; batch `2026-09-23-fluid-scheduling-rest`).

### Changed

- Fluid scheduling WP1: islands are scheduled by deadline on a shared online epoch instead of being visited on every tick; event owners come from a fence index, recoveries and module work run on deadlines and dependency changes, and a drain that exhausts its per-tick budget owes one continuation; dispatch order, budgets and trajectories are unchanged (`a0a791a`..`cadd182`; benchmark script default jar `5f866a9`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling: every tick checks the wall budget of the open dispatch rounds, so an expired round closes within one tick of its budget on a slow server (`74b62e3`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling WP2: the module host keeps a per-island drive index (due ticks of pending inputs, positive withdrawals) and decides module cycles from stored island state, never materialising a certified island mid-decision (`7d89784`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling WP2: the paced benchmark measures certificates: `-PfluidRestDetection` and `-PfluidCertificateTolerance`, full solves, replayed and identity-advanced spans, a mid-window reference state, after-GC heap, refusals, per-interval certificate evidence and publication fingerprints (`82568c6`, `115d690`, `3b7ff82`, `e924eae`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling WP3: engine-owned presentation. Loaded devices and open menus are updated only on their island's presentation bucket, about every 100 online ticks and staggered by island; a publication, chunk load, block-entity load or identity binding only marks a device, a view is built only for a loaded device or an open menu, and a view reads a certified island without ever waking it (`9d58e95`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling WP3: menu protocol `fluid-4` (breaking wire change): a static payload (kind, component axis, presets, material names) and a live payload (view, controls, reply), both delivered only on the bucket, the static one only when the registration revision changed; the 10-tick menu refresh is gone. Edits and filter recoveries are queued inputs: the handler validates, queues the ledger event and answers nothing; the reply (`Queued for simulation event at tick N`, `Applied`, `Not applied: <reason>`) arrives with the next bucket, the screen shows `Waiting for the engine` until then and opens on the last view delivered for the device (`f081342`; batch `2026-09-23-fluid-scheduling-rest`).
- Fluid scheduling WP4: checkpoint format 3 (breaking save change; formats 1 and 2 are refused with the instruction to create a fresh world, there is no upgrade). One NBT compound holds the world epoch, the checksummed ledger of transfers, pending material and modules, and one compound per island: identity, revision, clock, certificate base or the awake sentinel with the certificate's fields, and an opaque checksummed payload of graph, anchor, last interval, allowance, fences and status. Certificates are persisted with a validity signature (full property revision, the six policy values, a digest of graph identity) and restored on load, materialised from their base to the saved committed tick without a solve and holding only their horizon; a changed signature or `restDetection=false` discards them and keeps the inventory; a certificate saved during a property hold is not kept. Island payloads are cached on revision and payload generation, so a certified island's payload is copied, not encoded, on every save; the world topology is saved without its online tick (topology format 4) and its encoding kept while the ledger is unchanged. Every new field is validated on load (`ff2fba1`, `4f39701`, `f6b5dd8`; batch `2026-09-23-fluid-scheduling-rest`).

### Removed

- Fluid scheduling WP4: the version-1 solids upgrade, the format-2 absent-field defaults and the energy-datum migration, which read only formats 1 and 2, with their legacy-save tests (`4f39701`; batch `2026-09-23-fluid-scheduling-rest`).

## [0.2.0] - 2026-09-23

### Added

- Solid phases in the fluid network: particle populations reduced to three moments, slurry transport and mobility, inline filters with a saturated capacity law, deposition and clogging located on the step grid, solids in the device screens (`440a754`, fix series to `1403eeb`; batch `2026-09-18-solid-phase-fluid-system`).
- `AGENTS.md` with the owner's standing rules (`2a7bfdd`), extended with the documentation organization rule; this changelog and the version rule (branch `claude/docs-organization`; batch `repository`).

### Fixed

- Solid-moment dust no longer freezes the Newton line search; transport transitions are located on the step grid instead of by replay; retained solver state is keyed on a pipe's identity, not its filter cake (`2f618b9`..`a4db1fe`).
- Solid and filter islands use the block Jacobian and the embedded error estimator; step regrowth after a transition; population-limit closure; an isolated junction keeps its pressure and a transport failure closes both directions (`3e949c4`..`e97fe5b`).
- The device screen tells the player which reason closed a connection; three small screen defects (`3672cf1`, `a399100`, `bfb22ef`).
- The solver regression chain is built on the configured 21-component basis and its reference re-captured (`fca6a15`).
- Player-built block lines around an inline filter solve: the tight Newton tolerance comes only from nodes that hold an inventory, and reconstruction mixes exactly the junctions the equations mixed (`782e6ab`..`90f16c7`).
- Hydraulic, pump-head and valve rows are scaled by the island pressure instead of 1e5 Pa; reference re-captured (`c4079dd`..`bdc113c`).
- A pump at its own shutoff no longer holds its island (`eb25fc1`, `ae3b37c`).
- A zero-holdup junction freezes its donor for the pass and takes its species from what its donors deliver, removing the tank-node stall and the phantom nitrogen trace on filter lines (`a02a426`..`04ad5ca`).
- Solids fed into a full filter line after a configuration event: a junction's held composition is read off one state and new junctions are minted without the boundary's stock (`a6f91f7`..`c7da543`).
- Static head on elevated lines is evaluated on the pass's column, not the iterate (`8c072f7`, `ec9ea74`).
- Dead-headed lines are closed before the pass and held at exactly zero flow (`b0590a4`..`1403eeb`).

## [0.1.0] - 2026-09-22

State of `main` up to `3c27271`, before this changelog existed. One line per batch, newest first.

### Added

- 2026-09-22: flow-dependent sieve-tray pressure drop with a player-entered column diameter and one warm correction per solve (`bae4737`, merge `3c27271`; batch `2026-09-18-tray-pressure-drop`).
- 2026-09-17 to 2026-09-18: crude regrouped into twelve cuts `crude_pc01`..`crude_pc12` with a shared Dalia liquid-viscosity correction, qualified regrouped Transformer initializer, model selection by captured physics, externalized operating presets and network basis, reload GameTests (`ea780ea`..`4e84f0e`; batch `2026-09-17-crude-regrouping`).
- 2026-09-16 to 2026-09-17: fluid network: conserved fluid simulation engine and consolidated tooling (`a5dedf6`, `154007d`), then solver optimization (retained per-island solvers, carried step size, linear TR-BDF2 error filter, shared Peng-Robinson kernel, block Jacobian, per-phase trace truncation, nitrogen in the shared material catalog) (`15403b9`..`af65243`; batch `2026-09-15-fluid-network`).
- 2026-09-15: material properties externalized to data packs; selectable crude column presets (`e660097`).
- 2026-09-10 to 2026-09-14: neural initializer for the V3 column: neural-first path with audited correction, trained model generations, Transformer F0 promoted as the single production model with a progress-based correction budget and phase floor, request-only liquid-supply screen, cleaned benchmark populations (`caa466d`..`e66b374`; batch `2026-09-10-v4-neural-initializer`).
- 2026-09-09: V3 convergence-time optimization: flat band LU, decode-free probes, doubling steam ramp, analytic PR78 derivatives, trace-seed cap (`6a5ab33`..`db46e88`), and a faster feed flash (`cf9ce35`) (batch `2026-09-09-v3-convergence-time`).
- 2026-09-06 to 2026-09-08: literature CDU: TJL19 DWSIM property package, pumparounds as prescribed stage heat with a Heat tab and persisted duty ledger, independent condenser energy audit, local-throughput row scaling, per-phase truncation, convergence closure setting, continuation grid doubling to the request, energy-shift predictor, free water on cold trays with the dew point as a warning (`6d629db`..`530ee05`, merge `f4e600a`, review fixes to `e8d8937`; batches `2026-09-06-v3-literature-cdu-pumparounds`, `2026-09-07-v3-trace-scaling-and-truncation`, `2026-09-08-v3-free-water-trays`).
- 2026-09-05: V3 core fixes (sparse Newton work, pruning of disconnected support) and reproducible cold-core benchmark tooling (`0f94ffe`, `6c7d446`; batch `2026-09-05-v3-solver-core-review`).
- 2026-09-01: hybrid solver merge (pull request #1, `bdfeff1`): Kesler-Lee material properties, total condensation, stage-trace truncation with warm condenser recovery, liquid side draws, Holland Example 3-2 benchmark and in-game preset, steam stripping (`dec3777`..`56acd38`; batches `2026-08-31-v3-stage-trace-truncation`, `2026-08-31-v3-side-draws`, `2026-09-01-v3-holland-benchmark`, `2026-09-01-v3-steam-stripping`).
- 2026-08-27: hybrid continuation core: V3 property package extracted, admission preflight, bubble-point and Sum-Rates preconditioners, pressure lanes, low-pressure recovery (`61a2209`..`87b544d`).
- 2026-08-26: V3 column calculator: equation-oriented MESH solver with a banded Newton method, acceptance audit, calculator block and screen, persistence and protocol, DWSIM-style cold start and stage continuation, 45 s deadline (`d4755a5`..`20d5003`).
- 2026-08-25: Peng-Robinson thermodynamics with flash stability screening, caloric properties, an equilibrium-stage kernel and an isobaric crude column cascade connected to the calculator (`ad6541e`..`aec245e`).
- 2026-08-19: project bootstrap: crude column calculator proof of concept with asynchronous calculations (`4aca96d`..`4b0e58e`).

### Changed

- 2026-09-14 to 2026-09-17: research artifacts, petroleum research and the fluid working documents were untracked and kept local; test-only helpers separated (`ca5bdb6`, `83e176a`, `c5c8af3`, `5e47076`).

### Removed

- 2026-09-17: pre-V3 flash and equilibrium-stage solvers, the pre-kernel Peng-Robinson oracle and other unreferenced fluid code (`b5d0800`..`6deb609`).
- 2026-09-09: V1 column calculator (`8547fea`; batch `2026-09-09-v1-calculator-removal`).
- 2026-08-27: V2 NextGen calculator (`82f96ee`).

### Fixed

- 2026-09-22: a long solve path no longer discards a completed column solve (`51442f7`).

# Solid phase fluid system — runtime change and in-game GUI pass

Worktree `D:\Minecraft\Modding\1.21\CreateChemE\.claude\worktrees\agent-a377346aa370aa91d`,
branch `claude/solid-phase-gui`. Date 2026-09-22. Client driven through the
langyo/minecraft-mod-mcp bridge in the dev client.

## 0. Setup verification

| Step | Expected | Observed | Result |
| --- | --- | --- | --- |
| `git checkout -b claude/solid-phase-gui claude/solid-phase-fixes-2` | branch created | created | PASS |
| `git log -1 --oneline` at branch point | `e97fe5b Close both directions of a transport failure` | exactly that | PASS |
| Bridge jar in `run/mods/` | `minecraft-mcp-1.21.1-neoforge-v0.3.0.jar`, 912248 bytes | 912248 bytes | PASS |
| Gradle exclusivity | no other Gradle/Java build active before each invocation | two idle daemons only (CPU flat across a 5 s sample); never two invocations at once, never a suite while the client ran | PASS |
| Worktree isolation | main checkout and other worktrees untouched | only this worktree written; `C:/Users/wormz/.codex/...` read only | PASS |
| Stash | no bare `git stash` / `git stash pop` | none used | PASS |

Note: the worktree started at `3c27271`, not `4e84f0e` as the brief stated. The
branch point requested (`claude/solid-phase-fixes-2` @ `e97fe5b`) was reached
regardless, so this had no effect.

Commits made on this branch:

```
bfb22ef Repair three small defects on the fluid device screen
a399100 Close the feed the population limit actually needed to stop
3672cf1 Tell the player which reason closed a connection
```

```
 .../gui/screens/inventory/FluidDeviceScreen.java   | 24 +++++-
 .../runtime/fluid/FluidWorldAuthority.java         | 34 +++++++-
 .../fluid/network/SolidEventIntegrator.java        | 91 +++++++++++++++++++---
 .../runtime/fluid/SolidRuntimeTest.java            | 24 ++++++
 .../network/SolidTransportAcceptanceTest.java      | 26 +++++++
 5 files changed, 184 insertions(+), 15 deletions(-)
```

## 1. Test counts

Run sequentially, nothing else on the machine, counted from
`build/test-results/*/TEST-*.xml`.

| Task | Baseline | Final (HEAD `bfb22ef`) | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| `fluidScienceTest` | 160 | **161** | 0 | 0 | 0 |
| `fluidRuntimeTest` | 105 | **106** | 0 | 0 | 0 |

One test added to each suite (`theFlaggedFeedThatCarriesTheKeysNobodyElseSuppliesIsTheOneThatCloses`
in `SolidTransportAcceptanceTest`, `aClosedConnectionTellsTheDeviceStatusWhichReasonStoppedIt`
in `SolidRuntimeTest`). No existing test was modified.

## 2. Runtime change — closure reason in the device status (finding G4)

`SolidEventIntegrator.Transition` now carries the pipe identity and puts it in
its message:

```
blocked with solid: DEPOSITION; pipe=101; velocity=0.95; deposition=2.89
```

`FluidWorldAuthority.solidClosure(long,Map<String,Integer>)` finds the key
containing `; pipe=<id>;` in the island's last result's `rejectionReasons()` and
renders a short player-readable prefix:

```
blocked with solid: DEPOSITION (0.95 m/s, needs 2.89 m/s) /
```

A reason with no velocity of its own (`POPULATION_LIMIT`, `FILTER_CLOGGED`) is
named without two zeroes, and a blocked mask with no matching record falls back
to the old bare `blocked with solid`. All tokens existing tests match on are
preserved: `SolidClosureFeasibilityTest.closureTime` uses
`startsWith("blocked with solid: " + reason)` and `lastIndexOf("; t=")`, both
still correct with `; pipe=` inserted after the reason.

## 3. Runtime change — POPULATION_LIMIT selection rule (finding G1)

`SolidEventIntegrator.failed` used to flag every open inbound connection of an
over-populated receiver and let the shared rule (lowest velocity ratio, then
lowest pipe id) pick. A receiver holding 60 grades, fed 4 new ones by one
connection and 10 by another, closed the 4-grade feed, was still at 70, and
closed the 10-grade feed on the next pass — two closures, one of them for a
delivery that always fit.

Flagged connections are now deferred into `narrowLimits`, grouped by receiver,
and only the connection contributing the most keys that no other open inbound
donor — nor the receiver's own stock or injection — supplies stays a candidate.
Ties fall back unchanged to lowest velocity ratio then lowest pipe id.
`suppliedWithout` builds the "everything else" key set. The transitive reach and
the 64-with-duplicates skip bound are untouched, so an ordinary island pays
nothing.

`aSixtyFifthPopulationClosesAFeedInsteadOfStallingTheInterval` (two equal
64-grade feeds) still closes pipe 10 and still passes. The new test with the
exact 60 + 4 + 10 shape asserts one closure, on pipe 11 (the 10-key feed), that
the 4-key feed still delivers (`averageMassFlows()[0] > 0`), that the closure
reason names `pipe=11`, and that the receiver ends with exactly 64 populations.

## 4. GUI pass

World: creative superflat, cheats on, default GUI scale (854×480 framebuffer,
427×240 GUI space). Screenshots under
`documentation/screenshots/solid-phase-gui/`, all alpha-flattened.

### (a) Generator solids editor — PASS

| Sub-check | Evidence | Result |
| --- | --- | --- |
| `Solids` button present on a generator | `a1-generator-default.png` — bottom row reads `Vapor  <  >  Edit mix  Solids ... Apply  Close` | PASS |
| absent on reservoir | `a20-reservoir-no-solids-button.png` — bottom row `Vapor  <  >   Apply  Close` | PASS |
| absent on pipe | `a21-pipe-no-solids-button.png` — same | PASS |
| absent on filter | `a22-filter-no-solids-button.png` — bottom row `Solids  <  >   Recover solids   Apply  Close`; the left `Solids` is the phase-cycle button (a filter is forced to `phase=3`), not the editor | PASS |
| editor shows `Solids (volume %)` and 3-column rows | `a2-generator-solids-editor.png` — `Solids (volume %)` field and header `Material ID   Size (µm)   Mass share` | PASS |
| enter `createcheme:demo_particle` / `100` / `1` and 5 % | `a10-row-filled.png`, `a11-solids-5pct.png` | PASS |
| Apply accepted | `a12-applied.png` — `Settings accepted at the current simulation event.`, phase bar `L 0 W 95 V 0 S 5 %` | PASS |
| Solids phase page lists the population with its mass | `a13-solids-phase-page.png` — `createcheme:demo_particle / 100.00 µm / 125.00 kg` (5 % of 1 m³ × 2500 kg/m³ = 125 kg) | PASS |
| bad material id rejected visibly, not applied | `a15-bad-material-rejected.png` — `Not applied: Unknown solid material: createcheme:not_a_particle`, phase bar still `S 5 %` | PASS |
| negative share rejected visibly, not applied | `a14-negative-share-rejected.png` — `Not applied: Each particle grade needs a material, positive diameter and positiv` (truncated pre-fix; see finding GUI-2) | PASS |
| 65th row rejected | `a16`, `a17`, `a18`, `a19` — paging is clamped at page 32 of 32; the editor structurally offers exactly 64 rows and a 65th cannot be entered | PASS with note |

Note on the 65th row: `particleText` is `new String[64][3]` and the row widgets
are `setVisible(index<64)`, so the screen cannot express a 65th grade at all.
The server-side cap (`SlurryFeed`: `grades.size()>64` throws) therefore never
fires from this screen. Prevention rather than rejection — recorded as observed,
not as a defect.

Paging: rows per page is `max(2,(imageHeight-200)/12)` = **2** at the default
window size, so 64 grades are 32 pages. `>` clamps at page 32
(`a18-page-past-end.png` identical to `a17`), `<` returns to page 1 with row 0
intact (`a19-page-back-to-first.png`).

### (b) Slurry transport — PASS

Line A: generator `(0,-59,2)` → four pipes → reservoir `(0,-59,7)`.

| Sub-check | Evidence | Result |
| --- | --- | --- |
| reservoir Solids page fills over successive intervals | `a20` (2.29 kg, `S 0 %`, no populations) → `b3-reservoir-solids-1.png` (706.81 kg, `L 0 W 63 V 34 S 3 %`, `createcheme:demo_particle / 100.00 µm / 82.20 kg`) after raising the generator to 300 kPa (`b2`, flow 31.37 kg/s) | PASS |
| depositing configuration blocks with the new status | 2000 µm at 301 kPa against a 299.99 kPa reservoir (`b6-deposit-config-applied.png`) → `b7-pipe-deposition-blocked.png`: **`blocked with solid: DEPOSITION (0.95 m/s, needs 2.89 m/s) / FULL`**, `Net 0.00 kg/s` | PASS |
| reservoir stops receiving | `b8-reservoir-stopped.png` — 706.81 kg unchanged, no 2000 µm population | PASS |
| raising generator pressure re-opens | 400 kPa → `b10-reservoir-after-reopen.png`: 793.18 kg, 391.60 kPa, **two** populations `100.00 µm / 82.20 kg` and `2000.00 µm / 10.08 kg` | PASS |

The status at 101.325 kPa with zero driving force reads
`blocked with solid: DEPOSITION (0.00e+00 m/s, needs 0.08 m/s) / FULL`
(`a21-pipe-no-solids-button.png`), and after the reservoir refills to the
closure boundary it reads `(2.89 m/s, needs 2.89 m/s)` (`b9-pipe-reopened.png`)
— the event is located exactly on the threshold, as the integrator intends.

### (c) In-line filter — PARTIAL, blocked by a solver defect

| Sub-check | Evidence | Result |
| --- | --- | --- |
| filter page shows `Load`, `Captured`, `Capacity`, `Pressure drop` | `c11-lineE-filter.png`, `e6-voidline-filter.png` — `Load 0.00 %`, `Captured 0.00 kg`, `Capacity 10.00 L solids`, `Pressure drop 0.00 kPa` | PASS |
| `Recover solids` inactive while the cake is empty | `c11-lineE-filter.png`, `f3-fix-recover-inactive.png` — greyed | PASS |
| `Recover solids` active once solids are captured | — | **NOT VERIFIED** |
| recovery yields a `Recovered Solids` item and resets Load to 0 | — | **NOT VERIFIED** |
| `filter clogged` status and recovery restoring flow | — | **NOT VERIFIED** |

The item itself exists and renders: `/give @s createcheme:recovered_solids`
produces an item named **`Recovered Solids`** with its own model
(`c16-recovered-solids-item.png`, `c17-recovered-solids-name.png`). That item
was given by command, not produced by a filter, so it carries no cake contents
and its contents tooltip was not exercised.

Everything else in (c) is blocked by finding **F1** below: *no in-world island
containing an inline filter ever reached a state where solids could be carried
into it.* Five topologies were tried across two fresh worlds; every one is
permanently `HELD`.

### (d) Persistence — PASS

Save and quit to title, re-enter the same world.

| Item | Before | After reload | Result |
| --- | --- | --- | --- |
| generator solids | `d0-before-save-generator.png` — 400 kPa, `Solids 5.0 %`, `createcheme:demo_particle / 2000 / 1.0`, status `FULL` | `d3-after-reload-generator.png` — identical | PASS |
| reservoir populations | `b10` — 793.18 kg, 391.60 kPa, `100.00 µm / 82.20 kg` + `2000.00 µm / 10.08 kg` | `d4-after-reload-reservoir.png` — identical | PASS |
| pipe status | `b9` — `blocked with solid: DEPOSITION (2.89 m/s, needs 2.89 m/s) / FULL` | `d5-after-reload-pipe-status.png` — identical | PASS |
| filter cake | — | **NOT VERIFIED** (no filter ever held a cake; see F1) | — |

The status survives because the closure reason is re-recorded by the pre-interval
pass every interval, not because any text is persisted — which is exactly what
the G4 change relies on.

### (e) Client log — FAIL (HELD islands present)

`run/logs/latest.log` (and `build/runclient2.log` for the same session):

- `grep -c "status=HELD"` → **885** warnings.
- Six distinct islands held, with four distinct signatures:

```
fluid_island=20  HELD: Conservative reconstruction fails equation gate: 0.05834390392013748
fluid_island=22  HELD: Substep refinement exhausted: Newton line search stalled at residual 1.0470535549152183E-10; active-set pass=1
fluid_island=42  HELD: Substep refinement exhausted: Newton line search stalled at residual 1232666.6046244698; active-set pass=1
fluid_island=46  HELD: Newton line search stalled at residual 16435.926921102466; active-set pass=0
fluid_island=6   HELD: Substep refinement exhausted: Newton line search stalled at residual 1232666.6046244698; active-set pass=1
fluid_island=62  HELD: Newton line search stalled at residual 5531.127013914242; active-set pass=0
```

- `grep -E "Exception|Caused by"` → **nothing** beyond one unrelated mixin
  warning at startup (`ClassNotFoundException: org.jetbrains.annotations.ApiStatus$ScheduledForRemoval`).
- Every one of the six held islands contains an inline filter. The filter-free
  line A ran the whole session (101–400 kPa, 5 % solids, 100 µm and 2000 µm,
  a save/reload cycle) and never appeared in a HELD warning.

## 5. Findings

### F1 — every in-world island containing an inline filter is permanently HELD (blocking)

Five topologies, two fresh worlds, fully reproducible:

| Topology | Solids | Result |
| --- | --- | --- |
| generator → pipe → filter → pipe → reservoir | none, at rest | HELD from creation: *line search stalled at residual 1.05e-10, active-set pass=1* (`a22-filter-no-solids-button.png`, `c1-lineB-generator.png`) |
| generator → pump → pipe → filter → pipe → reservoir | none, at rest | HELD from creation: *residual 1232666.6046244698*, byte-identical residual in two different worlds (`e5-pumpline-filter-2.png`) |
| generator → pipe → filter → pipe → void | none, at rest | healthy, `FULL` (`c3-lineC-filter.png`, `c11-lineE-filter.png`, `e6-voidline-filter.png`) |
| generator → pipe → filter → pipe → void | 20 % / 100 µm at 300 kPa | HELD on apply: *residual 16435.93, active-set pass=0* (`c5-lineC-generator-applied.png`) |
| generator → pipe → filter → pipe → void | 5 % / 100 µm at 150 kPa | HELD on apply: *residual 5531.13, active-set pass=0* (`c13-lineE-generator-applied.png`) |
| generator → pipe → filter → pipe → void | 5 % / 100 µm, **pressure unchanged** (no flow) | HELD on apply: **`Conservative reconstruction fails equation gate: 0.05834390392013748`** (`e7-voidline-solids-only.png`) |

The last row is the sharpest: with no driving pressure at all, merely giving the
generator a solids feed on a filter island fails the conservative reconstruction
gate. The first two rows fail with no solids anywhere in the world.

The third row is the only healthy filter configuration found, and it stops being
healthy the moment solids are introduced. The second row is the same topology
the Codex in-world GameTest reportedly passes, so this is a behaviour difference
between the GameTest harness and a live world worth chasing first.

None of this is caused by the two commits in this branch: the message change is
text only, and the POPULATION_LIMIT rule only engages on islands holding more
than 64 populations (these hold one).

### F2 — a HELD island can no longer be reconfigured

An edit applied to a device on a HELD island is accepted into the queue and the
status becomes `WAITING: configuration event / HELD: ...`, but the island never
advances, so the edit never takes effect. The device keeps showing the old
values indefinitely (`c2-lineB-after-pressure.png` — lag 1409.95 s,
`c7-lineC-5pct.png` — lag 74.45 s). There is no in-game way out.

### F3 — a HELD island survives the removal of its blocks and of the world session

Setting every block of a HELD island to air left islands 22 and 42 reporting
HELD for the rest of the session, and both were still HELD after a save, quit to
title and re-entry (island 22 reappeared with a different residual, 7.385e-9, so
it does re-solve on load and stalls again). While they were held, a newly built
filter line sat at `WAITING: topology event` for minutes
(`c8-lineC-rebuilt-filter.png`, `c10-lineC-filter-after-clear.png`), so a held
island can delay topology events for unrelated new devices.

### GUI-1 (fixed) — `Recover solids` enabled with no cake to report

`FluidDeviceScreen.renderBg` only assigned `recoverButton.active` inside
`if(view.filter()!=null)`, so a filter whose view carries no filter state — a
device still waiting for its topology event — kept the `true` a fresh `Button`
starts with and offered recovery for a state nobody knew yet
(`c15-lineE-rebuilt.png`, `e4-pumpline-filter.png`). Fixed in `bfb22ef`: the
assignment is now unconditional,
`recoverButton.active = view.filter()!=null && !view.filter().captured().empty()`.

Verified by reading; the transient `WAITING: topology event` window could not be
re-entered reliably after the fix (the bridge's command path takes ~2 s per
command and the window closed first). The post-fix normal case is correct
(`f3-fix-recover-inactive.png`, `f5-fix-recover-waiting.png` — greyed on an
empty cake).

### GUI-2 (fixed) — validation messages cut mid-word

The local message was drawn with `font.plainSubstrByWidth(...)`, one panel-wide
line, so `Not applied: Each particle grade needs a material, positive diameter
and positive mass share` ended as `...and positiv` (`a14`). Fixed in `bfb22ef`:
`font.split` into up to two lines drawn at `imageHeight-42` and `-33`, which is
the free band between the last listed row (ends at `+184`) and the button row
(starts at `+204`). Verified: `f2-fix-wrapped-message.png` shows the full
sentence on two lines with no overlap.

### GUI-3 (fixed) — solids editor had no page indicator

Sixty-four grades at two rows a page is thirty-two pages, and the editor showed
no page number, so a row could only be identified by counting clicks — the
composition heading has carried `(page+1)/(pages)` all along. Fixed in `bfb22ef`:
the solids heading now draws the same, right-aligned so it clears the
`Mass share` column. Verified: `f1-fix-page-indicator.png` shows `6/32`.

### GUI-4 (not fixed) — two rows per page at the default window

`rows = max(2,(imageHeight-200)/12)` with `imageHeight = min(284, height-12)`.
At the default 427×240 GUI space this is `min(284,228)` → `rows = 2`, so the
editor shows two of sixty-four grades at a time and the composition list two of
eleven components. The screen is written for a taller window than the default
one provides. Recorded rather than changed: raising the row count means changing
the panel geometry, which is more than a small GUI defect.

## 6. What was not done, and why

- **Check (c) beyond the static filter page.** Capture, `Recover solids`
  becoming active, the recovered item carrying a cake, `Load` resetting to 0,
  the `filter clogged` status, and recovery restoring flow are all unverified.
  Every filter island the live world would build is HELD (F1), so no solid ever
  reached a cake. Five topologies were tried across two fresh worlds before
  stopping.
- **Filter cake persistence in check (d)**, for the same reason.
- **Shrinking the filter capacity via config** to reach `filter clogged` faster
  was never needed — the island stalls long before the cake matters.
- **GUI-1 post-fix observation in the failing state.** Code-verified only; see
  above.
- **The `Recovered Solids` contents tooltip.** The item was obtained with
  `/give`, so it has no contents to show.
- **A root-cause investigation of F1.** Out of scope for a GUI pass; it needs
  the checkpoint of a held filter island and a solver-side probe.

## 7. Notes on the bridge, for the next session

Three things cost real time and are worth recording.

1. `execute_command` runs through KubeJS `runCommand`/`runCommandSilent`, which
   reaches a dispatcher that does not contain the server commands — even `/help`
   comes back "Unknown or incomplete command". The only path that works is
   `open_chat` → `type_text` → `press_key enter`
   (`build/mcp/cmd.sh` in this worktree).
2. `type_text` and `paste_text` reflect a `(char,int)` method **declared on the
   screen class**. `ChatScreen` declares `charTyped`; `AbstractContainerScreen`
   does not, so nothing typed through the bridge ever reaches an `EditBox` on
   `FluidDeviceScreen`. `hotkey ctrl+v` does not help either: `EditBox.keyPressed`
   asks `Screen.hasControlDown()`, which reads the live GLFW key state. The
   working path is a real `java.awt.Robot` — `build/flatten/Field.java` plus
   `build/mcp/field.ps1`, which focuses the window with `SetForegroundWindow`,
   presses Tab *n* times, clears with Ctrl+A/Backspace and pastes from the
   system clipboard.
3. `press_key {"key":"tab"}` advances focus by **two** widgets, not one (press
   and release both dispatch). Robot-driven Tab advances by one. The focus order
   on the generator's solids page is
   `temperature, pressure, solidFraction, row0col0..2, row1col0..2, <, >, Solids, Apply, Close`.
4. `click` (GLFW cursor injection) does not reach widgets; `click_button_index`
   does, and `enumerate_widgets` lists invisible widgets too, so button
   visibility must be judged from a screenshot.
5. `pauseOnLostFocus` must be set to `false` in `run/options.txt` before the
   client starts, or the client reopens the pause menu every time the window
   loses focus and nothing can be driven.
6. `screenshot_to_file` resolves relative paths against `run/`.

## 8. Screenshot index

All under `documentation/screenshots/solid-phase-gui/`, 854×480, alpha
flattened by `build/flatten/Flatten.java`.

Setup: `00-title`, `01-create-world`, `01b..01e`, `02-world`, `03-pause`,
`04-lan`, `05-chat`, `06-create-final`, `06b-create-final`, `07-chat-typed`,
`08-lineA-view`, `09-aim-generator`.

Check (a): `a1-generator-default`, `a2-generator-solids-editor`,
`a10-row-filled`, `a11-solids-5pct`, `a12-applied`, `a13-solids-phase-page`,
`a14-negative-share-rejected`, `a15-bad-material-rejected`, `a16-page-last`,
`a17-page-last-marked`, `a18-page-past-end`, `a19-page-back-to-first`,
`a20-reservoir-no-solids-button`, `a21-pipe-no-solids-button`,
`a22-filter-no-solids-button`.
Bridge probes kept as evidence for §7: `a3-typed-material`, `a3b-typed-material`,
`a4-tab-test`, `a5-paste-test`, `a6-type-probe`, `a7-hotkey-paste`,
`a8-robot-paste`, `a9-tab-probe`.

Check (b): `b1-generator-300kpa-pre`, `b2-generator-300kpa-applied`,
`b3-reservoir-solids-1`, `b4-reservoir-solids-2`, `b5-deposit-config-pre`,
`b6-deposit-config-applied`, `b7-pipe-deposition-blocked`,
`b8-reservoir-stopped`, `b9-pipe-reopened`, `b10-reservoir-after-reopen`.

Check (c) and finding F1: `c1-lineB-generator`, `c2-lineB-after-pressure`,
`c3-lineC-filter`, `c4-lineC-generator-pre`, `c5-lineC-generator-applied`,
`c6-lineC-after-wait`, `c7-lineC-5pct`, `c8-lineC-rebuilt-filter`,
`c9-lineC-filter-ready`, `c10-lineC-filter-after-clear`, `c11-lineE-filter`,
`c12-lineE-generator-pre`, `c13-lineE-generator-applied`, `c14-lineE-after-wait`,
`c15-lineE-rebuilt`, `c16-recovered-solids-item`, `c17-recovered-solids-name`,
`e1-new-world-settings`, `e2-new-world-commands`, `e3-new-world-commands-on`,
`e4-pumpline-filter`, `e5-pumpline-filter-2`, `e6-voidline-filter`,
`e7-voidline-solids-only`.

Check (d): `d0-before-save-generator`, `d1-world-list`, `d2-list-focus`,
`d3-after-reload-generator`, `d4-after-reload-reservoir`,
`d5-after-reload-pipe-status`.

GUI fixes: `f1-fix-page-indicator`, `f2-fix-wrapped-message`,
`f3-fix-recover-inactive`, `f5-fix-recover-waiting`.

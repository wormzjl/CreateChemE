# V3 pumparound GUI (WP4 — Heat tab) — implementation and verification note

Branch: `claude/v3-literature-cdu-handoff-3179dc` (worktree `.claude/worktrees/v3-low-pressure-gaps-989c00`)
Base: `cb939c4` "Persist and transmit V3 pumparounds and the duty ledger"
Date: 2026-09-07

## 1. What changed

| File | Kind | Summary |
| --- | --- | --- |
| `src/main/java/com/wormzjl/createcheme/client/gui/screens/inventory/V3PumparoundDraft.java` | new | Pure parser for the three Heat rows plus the only place that flips the cooling sign. |
| `src/main/java/com/wormzjl/createcheme/client/gui/screens/inventory/V3ColumnInputDraft.java` | new | Widget-free assembly of the candidate `V3ColumnInput` from all editor drafts (extracted out of the screen so the Run payload is unit-testable). |
| `src/main/java/com/wormzjl/createcheme/client/gui/screens/inventory/ColumnCalculatorV3Screen.java` | edit | Fourth `Page.HEAT` tab: three cooler rows, advisories, duty-ledger panel, draft-derived tray map, Convergence cooler line, preset stash/restore, edit tracking. |
| `src/test/java/com/wormzjl/createcheme/client/gui/screens/inventory/V3PumparoundDraftTest.java` | new | 8 tests: sign mapping, round trip, disabled rows, every diagnostic. |
| `src/test/java/com/wormzjl/createcheme/client/gui/screens/inventory/V3ColumnInputDraftTest.java` | new | 3 tests: assembled input carries the coolers in order with their splits, empty rows keep the dry contract, `Heat: ` diagnostic prefix. |

No file under `science/column/v3/` was modified; the solver, residuals and formulation revisions are untouched (read-only use of `V3PumparoundSpec`, `V3ColumnDutyLedger`, `V3ColumnDisplayResult`).

## 2. Sign mapping

`V3PumparoundDraft` is the single boundary between player units and the science contract:

```java
static double coolingMegawattsToDutyWatts(double coolingMegawatts) { return -coolingMegawatts * 1_000_000.0; }
static double dutyWattsToCoolingMegawatts(double dutyWatts)        { return -dutyWatts / 1_000_000.0; }
```

* The GUI authors **positive MW removed**; the contract stores a **signed watt duty, negative for cooling**.
* `5.0 MW` → `-5.0e6 W`; the inverse is exact for the values pinned in the test (0.1, 0.5, 3.3, 5.0, 12.75, 250.0).
* A server-authored **heater** (positive duty) is displayed with a negative cooling number and reported by the
  advisory line as `Pumparound k carries a heater duty (set on server)`. It deliberately fails row validation, so
  Run stays disabled until the row is corrected — heaters are not authorable in the GUI, as specified.
* Duties are shown in the ledger boxes and per-tray table **with the sign as stored** (condenser negative,
  reboiler positive, coolers negative).

Row semantics: both tray fields blank ⇒ row off, no spec (the duty text is not even read). One blank tray, blank
duty, non-numeric duty, zero/negative duty, non-integer tray, tray outside `1..N`, `returnTray > drawTray`, and a
repeated draw/return pair are all errors with a `Cooler k …` message, surfaced on the existing status line
(prefixed `Heat: ` by `V3ColumnInputDraft`).

## 3. Layout coordinates (panel-relative; `CONTENT_TOP = 58`, panel ≤ 620×360)

Tab row at `topPos + 28`:

| Widget | x (leftPos+) | w |
| --- | --- | --- |
| Inputs | 10 | 72 |
| Streams | 86 | 72 |
| **Heat** | 162 | 72 |
| Convergence | 238 | 96 |
| Preset (Inputs page only) | 340 | 128 |

Heat page, left column:

| Element | y | x / size |
| --- | --- | --- |
| Header "Pumparound coolers (duty removed from the column)" | 58 | 10 |
| Column labels `#` / Draw tray / Return tray / Cooling (MW) / Split | 76 | 12 / 26 / 92 / 158 / 240 |
| Row *i* editors (i = 0..2) | 88 + 26·i (h 20) | draw 26 w60, return 92 w60, cooling 158 w76, split button 240 w92 (clamped to `imageWidth-10-240`, min 56) |
| Row number text | 94 + 26·i | 12 |
| Hint "Empty draw and return trays disable the row. Cooling is entered positive." | 166 | 10 |
| Advisory (NOTICE, first of n with `(+k more)`) | 180 | 10 |
| "Column duties" + `Input edited since run` pill | 196 | 10 / pill at `10 + width("Column duties") + 10` |
| Duty boxes Condenser / Reboiler / Coolers total | 212 (h 44) | 10, 134, 258 (w 118) |
| "Feed enthalpy X MW · steam enthalpy Y MW" | 260 | 10 |
| "Per tray" label | 274 | 10 |
| Per-tray table (2 rows, h 12) | 286, 298 | label col 34, cells 44, truncated with `…` |
| Run button / status line | `imageHeight-29` / `imageHeight-24` | 10 / 101 |

Tray map (right column, hidden when `imageWidth < 560`):

* panel x `400`, width `imageWidth - 410`; ladder at x `430`, from y `84` to `imageHeight - 58`.
* tray y = `top + round((tray-1) · (bottom-top) / (N-1))`; ticks every 5 trays, "1" and "N" labelled at x `414`.
* feed = coral triangle left of the ladder; side draws = teal bars left of the ladder; cooler *k* = blue bar in
  lane `436 + 8k`, thickness `2 + dutyRank` (2/3/4 by duty tercile), spanning return→draw; steam = amber (sump
  below the last tray, tray steam at its stage). Labels at x `442 + 8·coolerCount`.
* legend at `imageHeight-44` / `imageHeight-32`, colours `0xFF378ADD` blue, `0xFF1D9E75` teal, `0xFFD85A30`
  coral, `0xFFEF9F27` amber.

Convergence page gained one line at `CONTENT_TOP + 128`:
`Coolers: k requested · stage heat total X MW` (ledger total when present, else the summed authored duties).

## 4. Behaviour notes and deviations

1. **"Input edited since run" is tracked, not derived from revisions.** `resultRevision < inputRevision` is
   always true after the first run (the two counters are independent), and comparing the draft to the server
   input gives false positives because the feed vector is rescaled from a rounded kmol/h field. The screen now
   sets `draftEditedSinceState` from the editor responders (suppressed while `loadingInput` mirrors server
   state) and clears it whenever a server state arrives. The pill only shows when a display result exists.
2. **Cooler duty labels in the tray map** are shown (`PA1  -2 MW`) only when a ledger is present *and* the draft
   has not been edited since; otherwise the label falls back to `PA1  12–8`. The number is the authored draft
   duty (the ledger stores per-tray duties, not per-cooler totals), so it is only shown when draft and result
   agree.
3. **Run button is visible on the Heat page** as well as Inputs (the spec asks for the bottom bar with the
   status line there). The preset button stays Inputs-only.
4. **Preset stash/restore.** Requesting the Holland preset stashes the three rows and their splits; the next
   non-Holland input with no pumparounds restores them. The restore is deliberately *idempotent* — the server
   answers one preset with both a `reply` and a `pushToViewers` broadcast, so the first (non-idempotent)
   implementation restored and then immediately re-cleared the rows. The stash is dropped by the next authored
   edit or by any input that carries pumparounds.
5. **No GLOBAL_ENERGY_BALANCE number is displayed.** `V3ColumnDisplayResult` carries only
   `acceptanceCheckCount`, not audit values, so no closure figure was invented.
6. The duty ledger is capped at `MAX_STAGE_DUTIES = 16`; the per-tray table shows as many trays as fit and
   appends `…`.

## 5. Tests

* `./gradlew.bat test --offline` — **401 tests, 0 failures, 0 skipped** (390 before, +11 new).
* New: `V3PumparoundDraftTest` (8) and `V3ColumnInputDraftTest` (3). No tolerance was changed.

## 6. In-game verification (dev client, `runClient`)

World: `run/saves/New World (1)` (creative, cheats). Block placed with
`/setblock -272 68 -110 createcheme:column_calculator_v3`.

Because this environment's synthetic mouse/keyboard events do not reach the game window reliably, the three
cooler rows were loaded by writing them into the block-entity NBT and letting the screen mirror them:

```
/data merge block -272 68 -110 {Input:{Pumparounds:[
  {Return:8,Draw:12,DutyWatts:-2000000.0d,Split:"UNIFORM"},
  {Return:13,Draw:17,DutyWatts:-1500000.0d,Split:"UNIFORM"},
  {Return:19,Draw:22,DutyWatts:-1000000.0d,Split:"RETURN_TRAY"}]}}
```

The screen then displayed `2`, `1.5`, `1` MW cooling — i.e. the inverse sign mapping — and **Run V3** (pressed
through the mod bridge) converged: `Status: SUCCESS`, residual `1.223e-13`, formulation
`v3-dry-mesh-r12-side-draws-stage-heat`, ledger `Condenser -39.2 MW`, `Reboiler 8 MW`,
`Coolers total -4.5 MW` (= 2 + 1.5 + 1), per-tray `-0.4 … -0.3 MW` on trays 8..13 and beyond.

Screenshots (flattened; `documentation/gui/` is gitignored):

| Path | Content |
| --- | --- |
| `documentation/gui/before-inputs.png` | Inputs page (new five-button tab row) |
| `documentation/gui/before-streams.png` | Streams page, no result yet |
| `documentation/gui/before-convergence.png` | Convergence page, no result yet |
| `documentation/gui/after-heat-empty.png` | Heat page, all rows off, "Run the column to see …", tray map with draws/feed only |
| `documentation/gui/after-heat-editing.png` | Heat page, three coolers authored, side-draw advisory, "Draft valid · 3 coolers active", cooler spans in the map |
| `documentation/gui/after-heat-result.png` | Heat page after a successful run: three duty boxes, feed/steam enthalpy, per-tray table, map labelled with duties |
| `documentation/gui/after-heat-edited-pill.png` | Same, after cycling a split: `Input edited since run` pill, ledger retained, labels back to spans |
| `documentation/gui/after-heat-narrow.png` | 520 px panel: tray map hidden, left column full width, per-tray table widened |
| `documentation/gui/after-heat-holland.png` | Holland preset: cooler rows cleared and disabled, 11-tray map |
| `documentation/gui/after-heat-preset-roundtrip.png` | Back on Tia Juana: stashed rows and splits restored |
| `documentation/gui/after-convergence.png` | `Coolers: 3 requested · stage heat total -4.5 MW` |
| `documentation/gui/after-streams.png` | Streams page with the accepted result (unchanged layout) |
| `documentation/gui/after-inputs.png` | Inputs page after the run |

Environment notes for whoever repeats this: the bridge's screenshot is a bottom-left crop of the framebuffer,
so the client window was shrunk to a 560×300 client rect (840×450 framebuffer, GUI scale 1) to make the whole
620×360 panel fit one capture; PNGs are alpha-0 and were flattened with `build/pkgcmp/PngFlatten`. The bridge's
`execute_command` parses against the client dispatcher and fails for server commands — type commands through
`open_chat` + `type_text` instead. `click_button_index`, `right_click` and chat typing work; raw `click` into an
`EditBox` did not, which is why NBT injection was used for data entry.

## 7. Left undone / follow-ups

* Typing into the Heat `EditBox`es was never exercised by a real keystroke in-game (bridge limitation). The
  parse path is covered by unit tests and by the NBT-loaded round trip, but a human should type one row once.
* The Convergence page now has 11 px between the new coolers line and "Input digest"; readable, but a future
  pass could re-space that block.
* Cooler bar thickness uses a rank over at most three coolers (2/3/4 px); with equal duties the row order wins.
* The "before" screenshots were taken on the modified build, so they already show the four-tab row; a true
  pre-change baseline was not captured.

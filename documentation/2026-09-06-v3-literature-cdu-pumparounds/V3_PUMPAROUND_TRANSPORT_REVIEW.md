# V3 pumparound transport and persistence (WP3) — review / handoff

Branch: `claude/v3-literature-cdu-handoff-3179dc` (worktree `.claude/worktrees/v3-low-pressure-gaps-989c00`).
Date: 2026-09-07.

## Scope

WP3 only: carry the already-existing science records `V3PumparoundSpec` and `V3ColumnDutyLedger` across
save/load and across the client/server wire. No solver, residual, calculator, formulation-revision or digest
code was touched — `src/main/java/com/wormzjl/createcheme/science/column/v3/` is unmodified.

## Files changed

- `src/main/java/com/wormzjl/createcheme/world/level/block/entity/ColumnCalculatorV3BlockEntity.java`
- `src/main/java/com/wormzjl/createcheme/network/ColumnV3Network.java`
- `src/test/java/com/wormzjl/createcheme/network/V3PumparoundCodecTest.java` (new)

## NBT layout (`DATA_VERSION` 6 → 7)

`Input` compound gains a `Pumparounds` `ListTag` of compounds, written after `SteamFeeds`:

| key         | type   | meaning                                        |
|-------------|--------|------------------------------------------------|
| `Return`    | int    | one-based return tray                          |
| `Draw`      | int    | one-based draw tray (`>= Return`)              |
| `DutyWatts` | double | signed, positive = heat added to the column    |
| `Split`     | string | `V3PumparoundSpec.Split` enum name             |

`Result` compound gains an optional `DutyLedger` compound:

| key              | type   |
|------------------|--------|
| `Condenser`      | double |
| `Reboiler`       | double |
| `StageHeatTotal` | double |
| `FeedEnthalpy`   | double |
| `SteamEnthalpy`  | double |
| `StageDuties`    | list of `{Tray:int, DutyWatts:double}` |

`readInput` applies the same strictness as the steam-feed list: a missing `Pumparounds` tag reads as an empty
list; a present non-list, a list whose elements are not compounds, more than `V3ColumnInput.MAX_PUMPAROUNDS`
entries, an unknown `Split` name, or a non-finite/zero duty all throw `IllegalArgumentException`, which
`loadAdditional` already maps to `CORRUPT_PERSISTED_STATE`. `readDisplayResult` mirrors that for the ledger
(missing → `Optional.empty()`, non-compound → throw, `StageDuties` non-list / wrong element type / more than
`V3ColumnDutyLedger.MAX_STAGE_DUTIES` → throw); the record constructors supply the finiteness checks.

### Migration rule

A version-6 tag has neither `Pumparounds` nor `DutyLedger`. It therefore migrates in place: the input decodes
with an empty pumparound list, the persisted result decodes with an absent ledger and is kept, and
`loadAdditional` still lands on the `STALE` branch. No new version branch was added and `setChanged()` is not
called on load (the existing `loadAdditional` never calls it), so no state is dirtied by the bump. The version
comment next to the version-6 note documents this.

## Wire layout (`WIRE_SCHEMA_VERSION` 6 → 7)

`writeInput` appends, after the steam-feed list: varint count (bounded through `readCount` by
`MAX_PUMPAROUNDS`), then per entry varint `returnTray`, varint `drawTray`, double duty (`finite` guarded),
varint `Split` ordinal. An out-of-range ordinal raises `DecoderException` from the new `splitOrdinal` helper
rather than `ArrayIndexOutOfBoundsException`.

`writeDisplayResult` appends a boolean presence flag; when set, five doubles (condenser, reboiler, stage-heat
total, feed enthalpy, steam enthalpy) followed by a varint stage-duty count bounded by `MAX_STAGE_DUTIES` and
that many `{varint tray, double duty}` pairs.

Both directions are guarded by the existing `requireWireSchema`, so a version-6 client and a version-7 server
reject each other's payloads outright instead of misparsing them.

## Sign convention

Duties are stored and transmitted raw. Nothing negates, clamps or absolute-values them on either transport, so
a cooling pumparound stays negative end to end. `coolingDutiesStayNegativeThroughEveryTransportHop` asserts
this for both the input specs and the ledger, over both NBT and wire, with exact (`delta = 0.0`) comparisons.

## Tests

New class `com.wormzjl.createcheme.network.V3PumparoundCodecTest` (6 tests):

1. wire + NBT round trip of 0/1/2/3 pumparounds covering both `Split` kinds;
2. version-6 migration — `Pumparounds` and `DutyLedger` removed from freshly written tags, empty list, absent
   ledger, result otherwise preserved;
3. rejection of a fourth pumparound, a non-finite duty and an invalid split on the wire (byte patched), plus
   the NBT equivalents (oversized list, NaN duty, unknown split name, non-list tag, non-compound elements);
4. display-result round trip with no ledger, a one-entry ledger and a full 16-entry ledger;
5. rejection of a seventeenth stage duty on both transports and of a non-compound `DutyLedger`;
6. the negative-duty sign test above.

Style follows `V3SideDrawCodecTest`: the codecs stay `private static` and are reached by reflection. The unit
source set has no Minecraft bootstrap, so `ColumnCalculatorV3BlockEntity` cannot be instantiated and
`loadAdditional` itself is not directly exercised; the migration test drives the exact static helpers
`loadAdditional` delegates to. No visibility was widened.

Suite: 390 tests, 0 failures, 0 errors, 0 skipped across 80 classes (`./gradlew.bat test`). Before this change
the same suite was 384 tests — no existing test file was edited and no tolerance was loosened.

## Left undone / notes for the next work package

- WP4 (GUI) still has no way to author pumparounds or display the ledger; the screen compiles unchanged and
  simply never sets a non-empty list, so in practice version-7 saves are byte-identical to version-6 ones plus
  two empty containers until WP4 lands.
- No `loadAdditional`-level integration test exists (no bootstrap in the unit source set). If a Minecraft test
  harness is ever added, the version-6-keeps-its-result path is the one worth re-asserting end to end,
  including that the block entity is not marked dirty.
- The ledger is persisted but never recomputed on load; it stays a presentation-only certificate, consistent
  with the rest of `V3ColumnDisplayResult`.

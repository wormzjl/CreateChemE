# V3 side-draw codec guard fix

`V3SideDrawCodecTest.malformedAndOversizedListsAreRejectedBeforeAllocationOrSilentMigration` failed with
`expected: <io.netty.handler.codec.DecoderException> but was: <java.lang.IndexOutOfBoundsException>` on every branch
descending from `ef05260`, and passed on `main` (`f01a28e`).

## Introducing commit

`1a4a01d` "Train and qualify a generalized column initializer experiment".

Bisect over the six commits between `main` and `ef05260` (oldest first: `caa466d`, `e5d837e`, `f87a4b7`, `1a4a01d`,
`00fc47d`, `ef05260`), running `./gradlew.bat --offline test --tests '*V3SideDrawCodecTest*' --console=plain` per
commit:

| step | commit | result |
| --- | --- | --- |
| 0 | `f01a28e` (main) | PASS 5/5 (given) |
| 1 | `f87a4b7` (midpoint) | PASS 5/5 |
| 2 | `1a4a01d` | **FAIL** 5 tests, 1 failed, at `V3SideDrawCodecTest.java:115` |
| 3 | `ef05260` | FAIL (given) |

Good at `f87a4b7`, bad at `1a4a01d`, and the two intervening commits are after the first bad one, so `1a4a01d` is the
first bad commit.

Neither `ColumnV3Network.java` nor `V3SideDrawCodecTest.java` changed at all between `main` and the integration branch
(`git diff main HEAD` over both files is empty). The break came entirely from a constant.

## Mechanism: the guard was not bypassed; the test was aimed at the wrong field

No read was reordered ahead of a length check, no new field is decoded before validation, and no buffer index moved.
The side-draw guard in `readInput` is intact and still rejects before allocation:
`readCount(buffer, V3ColumnInput.MAX_SIDE_DRAWS, "side draw")` validates the count and throws `DecoderException`
*before* `new ArrayList<>(drawCount)`.

What `1a4a01d` changed is one line in `V3ColumnInput`:

```java
-    public static final int MAX_PUMPAROUNDS = 3;
+    public static final int MAX_PUMPAROUNDS = 4;
```

`writeInput` emits the three bounded lists last, in order: side draws, steam feeds, pumparounds. For the test's
`input(0)` all three are empty, so the packet ends in three zero-count bytes. The test poked
`buffer.writerIndex() - 1`, i.e. the **pumparound** count, while claiming to forge an oversized **side-draw** list:

```java
buffer.setByte(buffer.writerIndex() - 1, V3ColumnInput.MAX_SIDE_DRAWS + 1);   // == 4
```

- Before `1a4a01d`, `MAX_PUMPAROUNDS == 3`, so the forged `4` violated the pumparound bound and `readCount` threw
  `DecoderException`. The assertion passed on a coincidence: `MAX_SIDE_DRAWS + 1 > MAX_PUMPAROUNDS`. The side-draw
  guard was never exercised on the wire.
- After `1a4a01d`, `MAX_PUMPAROUNDS == 4`, so `4` is a legal pumparound count. `readCount` correctly accepted it, and
  the loop then read past the end of an exhausted buffer, raising a raw `IndexOutOfBoundsException`
  (`readerIndex(164) + length(1) exceeds writerIndex(164)`).

So the regression exposed a real but distinct latent gap rather than a bypassed bound: a **truncated** packet whose
declared count is inside its bound escaped `readInput` as a raw `IndexOutOfBoundsException` instead of the codec's
`DecoderException` contract. `readInput` caught `DecoderException`, `IllegalArgumentException` and
`NullPointerException`, but not `IndexOutOfBoundsException`.

## Fix

`src/main/java/com/wormzjl/createcheme/network/ColumnV3Network.java`

1. `readCount` gained a `minimumBytesPerElement` overload: after the bound check, it requires
   `buffer.readableBytes() >= count * minimumBytesPerElement` and otherwise throws
   `DecoderException("Truncated V3 <field> list")`. The rejection still happens **before** the caller allocates. The
   three bounded input lists pass their minimum encodings (VarInt >= 1 byte, double == 8 bytes): side draw 9, steam
   feed 17, pumparound 11. The existing three-argument `readCount` delegates with `0`, so all other call sites are
   unchanged.
2. `readInput` now also catches `IndexOutOfBoundsException` and rethrows it as `DecoderException("Invalid V3 input")`,
   as a backstop for any short read elsewhere in the record (client-to-server path; a malformed packet must not escape
   as a raw runtime failure).

`src/test/java/com/wormzjl/createcheme/network/V3SideDrawCodecTest.java`

The assertion is sharpened to address each count by name instead of poking a blind trailing offset. It now asserts the
targeted byte really is the empty count before forging it, and covers four cases: side draw, steam feed and pumparound
counts over their bounds, plus a pumparound count *inside* its bound with no payload behind it. Failure messages name
the field and the escaping exception.

**Wire format is unchanged and `WIRE_SCHEMA_VERSION` stays at 8.** No byte is added, removed or reordered, and no
encoded value changes; only the rejection path for input that was already invalid is affected. A valid packet from an
unmodified peer encodes and decodes to exactly the same bytes, so a bump would force a needless protocol break.

`MAX_PUMPAROUNDS = 4` is left as `1a4a01d` set it — that is a deliberate capability change for the generalized
initializer work, and the test, not the constant, was wrong.

## Verification

- Sharpened test against the unfixed codec: fails on exactly the truncated-pumparound case
  (`truncated pumparound count 4 escaped the guard as java.lang.IndexOutOfBoundsException`), confirming the codec
  change is load-bearing and that the three bound-violation cases were already guarded.
- `./gradlew.bat --offline test --tests '*V3SideDrawCodecTest*'`: 5/5 pass.
- Full suite `./gradlew.bat --offline test`: **580 tests, 0 failures, 0 errors, 0 skipped** (107 test classes).
  BUILD SUCCESSFUL.

package com.wormzjl.createcheme.network;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * Transport contract for the V3 pumparound specifications and the calculated duty ledger.
 *
 * <p>The block entity and network codecs are private statics reached by reflection, matching
 * {@link V3SideDrawCodecTest}; the unit suite has no Minecraft bootstrap, so the block entity itself cannot be
 * instantiated and {@code loadAdditional} is exercised through the same helpers it delegates to.</p>
 */
class V3PumparoundCodecTest {
    private static final List<V3PumparoundSpec> PUMPAROUNDS = List.of(
            new V3PumparoundSpec(1, 1, -2_500_000.0, V3PumparoundSpec.Split.RETURN_TRAY),
            new V3PumparoundSpec(2, 3, -900_000.0, V3PumparoundSpec.Split.UNIFORM),
            new V3PumparoundSpec(2, 4, 450_000.0, V3PumparoundSpec.Split.RETURN_TRAY));

    @Test
    void wireAndNbtRoundTripZeroThroughThreePumparoundsAcrossBothSplits() throws Exception {
        for (int count = 0; count <= V3ColumnInput.MAX_PUMPAROUNDS; count++) {
            V3ColumnInput input = input(count);
            assertEquals(count, input.pumparounds().size());
            assertEquals(input, wireRoundTrip(input));
            assertEquals(input, readNbt(writeNbt(input)));
        }
    }

    @Test
    void versionSixStateMigratesWithNoPumparoundsAndKeepsItsLedgerlessResult() throws Exception {
        CompoundTag inputTag = writeNbt(input(3));
        inputTag.remove("Pumparounds");
        V3ColumnInput migrated = (V3ColumnInput) readNbt(inputTag);
        assertEquals(List.of(), migrated.pumparounds());
        assertEquals(input(0), migrated);

        V3ColumnDisplayResult ledgered = result(Optional.of(ledger(2)));
        CompoundTag resultTag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeDisplayResult",
                new Class<?>[] {V3ColumnDisplayResult.class}, ledgered);
        resultTag.remove("DutyLedger");
        V3ColumnDisplayResult legacy = readNbtResult(resultTag);
        assertEquals(Optional.empty(), legacy.dutyLedger());
        assertEquals(result(Optional.empty()), legacy);
        assertEquals(ledgered.streams(), legacy.streams());
    }

    @Test
    void wireAndNbtRejectAFourthPumparoundANonFiniteDutyAndAnUnknownSplit() throws Exception {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            writeWire(buffer, input(0));
            // The trailing byte is the pumparound count varint; an out-of-contract count is refused before allocation.
            buffer.setByte(buffer.writerIndex() - 1, V3ColumnInput.MAX_PUMPAROUNDS + 1);
            assertInstanceOf(DecoderException.class, readWireFailure(buffer));
        } finally {
            buffer.release();
        }

        buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            writeWire(buffer, input(1));
            // One entry trails as varint return, varint draw, eight duty bytes, varint split ordinal.
            buffer.setDouble(buffer.writerIndex() - 9, Double.NaN);
            assertInstanceOf(DecoderException.class, readWireFailure(buffer));
        } finally {
            buffer.release();
        }

        buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            writeWire(buffer, input(1));
            buffer.setByte(buffer.writerIndex() - 1, V3PumparoundSpec.Split.values().length);
            assertInstanceOf(DecoderException.class, readWireFailure(buffer));
        } finally {
            buffer.release();
        }

        CompoundTag oversized = writeNbt(input(3));
        ListTag entries = oversized.getList("Pumparounds", Tag.TAG_COMPOUND);
        entries.add(entries.getCompound(0).copy());
        assertInstanceOf(IllegalArgumentException.class, readNbtFailure(oversized));

        CompoundTag nonFinite = writeNbt(input(1));
        nonFinite.getList("Pumparounds", Tag.TAG_COMPOUND).getCompound(0).putDouble("DutyWatts", Double.NaN);
        assertInstanceOf(IllegalArgumentException.class, readNbtFailure(nonFinite));

        CompoundTag unknownSplit = writeNbt(input(1));
        unknownSplit.getList("Pumparounds", Tag.TAG_COMPOUND).getCompound(0).putString("Split", "SIDEWAYS");
        assertInstanceOf(IllegalArgumentException.class, readNbtFailure(unknownSplit));

        CompoundTag notAList = writeNbt(input(1));
        notAList.putString("Pumparounds", "not a list");
        assertInstanceOf(IllegalArgumentException.class, readNbtFailure(notAList));

        CompoundTag wrongElements = writeNbt(input(1));
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("not a pumparound"));
        wrongElements.put("Pumparounds", strings);
        assertInstanceOf(IllegalArgumentException.class, readNbtFailure(wrongElements));
    }

    @Test
    void displayResultCarriesAnAbsentAndAFullyPopulatedDutyLedgerThroughWireAndNbt() throws Exception {
        for (Optional<V3ColumnDutyLedger> ledger :
                List.of(Optional.<V3ColumnDutyLedger>empty(), Optional.of(ledger(1)),
                        Optional.of(ledger(V3ColumnDutyLedger.MAX_STAGE_DUTIES)))) {
            V3ColumnDisplayResult result = result(ledger);
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                invoke(ColumnV3Network.class, "writeDisplayResult",
                        new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnDisplayResult.class}, buffer, result);
                V3ColumnDisplayResult decoded = (V3ColumnDisplayResult) invoke(ColumnV3Network.class,
                        "readDisplayResult", new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer);
                assertEquals(result, decoded);
                assertEquals(ledger, decoded.dutyLedger());
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
            CompoundTag tag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeDisplayResult",
                    new Class<?>[] {V3ColumnDisplayResult.class}, result);
            assertEquals(result, readNbtResult(tag));
        }
    }

    @Test
    void aSeventeenthStageDutyIsRejectedByBothTransports() throws Exception {
        V3ColumnDisplayResult result = result(Optional.of(ledger(V3ColumnDutyLedger.MAX_STAGE_DUTIES)));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            invoke(ColumnV3Network.class, "writeDisplayResult",
                    new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnDisplayResult.class}, buffer, result);
            // The stage-duty count precedes sixteen tray/duty pairs of nine bytes each.
            buffer.setByte(buffer.writerIndex() - 9 * V3ColumnDutyLedger.MAX_STAGE_DUTIES - 1,
                    V3ColumnDutyLedger.MAX_STAGE_DUTIES + 1);
            assertInstanceOf(DecoderException.class, assertThrows(InvocationTargetException.class, () ->
                    invoke(ColumnV3Network.class, "readDisplayResult",
                            new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer)).getCause());
        } finally {
            buffer.release();
        }

        CompoundTag tag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeDisplayResult",
                new Class<?>[] {V3ColumnDisplayResult.class}, result);
        ListTag duties = tag.getCompound("DutyLedger").getList("StageDuties", Tag.TAG_COMPOUND);
        duties.add(duties.getCompound(0).copy());
        assertInstanceOf(IllegalArgumentException.class, assertThrows(InvocationTargetException.class,
                () -> readNbtResult(tag)).getCause());

        CompoundTag malformed = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeDisplayResult",
                new Class<?>[] {V3ColumnDisplayResult.class}, result);
        malformed.putString("DutyLedger", "not a ledger");
        assertInstanceOf(IllegalArgumentException.class, assertThrows(InvocationTargetException.class,
                () -> readNbtResult(malformed)).getCause());
    }

    @Test
    void coolingDutiesStayNegativeThroughEveryTransportHop() throws Exception {
        V3ColumnInput input = input(2);
        assertTrue(input.pumparounds().stream().allMatch(pumparound -> pumparound.dutyWatts() < 0.0));
        for (V3ColumnInput decoded : List.of(wireRoundTrip(input), (V3ColumnInput) readNbt(writeNbt(input)))) {
            assertEquals(input.pumparounds(), decoded.pumparounds());
            for (int index = 0; index < decoded.pumparounds().size(); index++) {
                double duty = decoded.pumparounds().get(index).dutyWatts();
                assertTrue(duty < 0.0, "cooling duty lost its sign at index " + index);
                assertEquals(PUMPAROUNDS.get(index).dutyWatts(), duty, 0.0);
            }
        }

        V3ColumnDutyLedger cooling = new V3ColumnDutyLedger(-4_000_000.0, 8_000_000.0, -3_400_000.0,
                List.of(new V3ColumnDutyLedger.StageDuty(3, -3_400_000.0)), 1_200_000.0, 0.0);
        V3ColumnDisplayResult result = result(Optional.of(cooling));
        CompoundTag tag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeDisplayResult",
                new Class<?>[] {V3ColumnDisplayResult.class}, result);
        V3ColumnDutyLedger persisted = readNbtResult(tag).dutyLedger().orElseThrow();
        assertEquals(-4_000_000.0, persisted.condenserWatts(), 0.0);
        assertEquals(-3_400_000.0, persisted.stageDuties().get(0).dutyWatts(), 0.0);

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            invoke(ColumnV3Network.class, "writeDisplayResult",
                    new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnDisplayResult.class}, buffer, result);
            V3ColumnDutyLedger sent = ((V3ColumnDisplayResult) invoke(ColumnV3Network.class, "readDisplayResult",
                    new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer)).dutyLedger().orElseThrow();
            assertEquals(cooling, sent);
            assertEquals(-3_400_000.0, sent.stageHeatTotalWatts(), 0.0);
        } finally {
            buffer.release();
        }
    }

    private static V3ColumnInput wireRoundTrip(V3ColumnInput input) throws Exception {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            writeWire(buffer, input);
            V3ColumnInput decoded = (V3ColumnInput) invoke(ColumnV3Network.class, "readInput",
                    new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static void writeWire(RegistryFriendlyByteBuf buffer, V3ColumnInput input) throws Exception {
        invoke(ColumnV3Network.class, "writeInput",
                new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnInput.class}, buffer, input);
    }

    private static Throwable readWireFailure(RegistryFriendlyByteBuf buffer) {
        return assertThrows(InvocationTargetException.class, () -> invoke(ColumnV3Network.class, "readInput",
                new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer)).getCause();
    }

    private static Throwable readNbtFailure(CompoundTag tag) {
        return assertThrows(InvocationTargetException.class, () -> readNbt(tag)).getCause();
    }

    private static CompoundTag writeNbt(V3ColumnInput input) throws Exception {
        return (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeInput",
                new Class<?>[] {V3ColumnInput.class}, input);
    }

    private static Object readNbt(CompoundTag tag) throws Exception {
        return invoke(ColumnCalculatorV3BlockEntity.class, "readInput", new Class<?>[] {CompoundTag.class}, tag);
    }

    private static V3ColumnDisplayResult readNbtResult(CompoundTag tag) throws Exception {
        return (V3ColumnDisplayResult) invoke(ColumnCalculatorV3BlockEntity.class, "readDisplayResult",
                new Class<?>[] {CompoundTag.class}, tag);
    }

    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static V3ColumnDutyLedger ledger(int stageDutyCount) {
        List<V3ColumnDutyLedger.StageDuty> duties = new ArrayList<>(stageDutyCount);
        double total = 0.0;
        for (int tray = 1; tray <= stageDutyCount; tray++) {
            double duty = -100_000.0 * tray;
            total += duty;
            duties.add(new V3ColumnDutyLedger.StageDuty(tray, duty));
        }
        return new V3ColumnDutyLedger(-5_500_000.0, 8_000_000.0, total, duties, 1_250_000.0, 320_000.0);
    }

    private static V3ColumnDisplayResult result(Optional<V3ColumnDutyLedger> ledger) {
        List<V3ColumnStreamProperties> streams = List.of(new V3ColumnStreamProperties(
                "distillate", "Distillate", "LIQUID", 10, 1, 400, 250_000, 0,
                List.of(new V3ColumnStreamProperties.ComponentFraction("a", 1, 1))));
        return new V3ColumnDisplayResult("0".repeat(64), "mesh", "assumptions", "data", 2, 0, 6, streams, ledger);
    }

    private static V3ColumnInput input(int pumparoundCount) {
        return new V3ColumnInput(1, "test:binary", "test:codec", new V3ComponentBasis(List.of("a", "b")),
                new double[] {40, 60}, 450, 4, 2, 250_000, 750, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                new V3ColumnSpecification.ReboilerDuty(8_000_000)),
                List.of(new V3SideDrawSpec(2, 3)), List.of(), PUMPAROUNDS.subList(0, pumparoundCount));
    }
}

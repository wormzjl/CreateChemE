package com.wormzjl.createcheme.network;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.createcheme.science.material.Cdu17FixtureExtension.class)
class V3SideDrawCodecTest {
    @Test
    void roundedServerDefaultPublishesAndSolvesTheQualifiedLiteratureSideDraws() throws Exception {
        V3ColumnInput input = com.wormzjl.createcheme.science.material.Cdu17TestCatalog.pilotInput();

        assertEquals(29, input.stageCount());
        assertEquals(150_000.0, input.topPressurePascal());
        assertEquals(List.of(13, 17, 22), input.sideDraws().stream().map(V3SideDrawSpec::trayNumber).toList());
        assertArrayEquals(new double[] {92.3, 131.85, 32.96},
                input.sideDraws().stream().mapToDouble(draw -> draw.molarFlowMolPerSecond() * 3.6).toArray(),
                1.0e-12);

        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started > 45_000_000_000L) {
                throw new AssertionError("rounded GUI default exceeded 45 seconds");
            }
        });
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
    }

    @Test
    void wireAndNbtRoundTripZeroThroughThreeDrawsAndRejectMissingList() throws Exception {
        for (int count = 0; count <= 3; count++) {
            V3ColumnInput input = input(count);
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                invoke(ColumnV3Network.class, "writeInput", new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnInput.class}, buffer, input);
                assertEquals(input, invoke(ColumnV3Network.class, "readInput", new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
            CompoundTag tag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeInput",
                    new Class<?>[] {V3ColumnInput.class}, input);
            assertEquals(input, readNbt(tag));
            tag.remove("SideDraws");
            assertThrows(InvocationTargetException.class, () -> readNbt(tag));
        }
    }

    @Test
    void wireAndNbtRoundTripSteamFeedsAndRejectMissingList() throws Exception {
        V3ColumnInput base = input(1);
        V3ColumnInput steam = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(),
                base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(), base.sideDraws(),
                List.of(new V3SteamFeedSpec(5, 3.0, 450.0), new V3SteamFeedSpec(2, 1.0, 450.0)));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            invoke(ColumnV3Network.class, "writeInput", new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnInput.class}, buffer, steam);
            assertEquals(steam, invoke(ColumnV3Network.class, "readInput", new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer));
        } finally {
            buffer.release();
        }
        CompoundTag tag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeInput",
                new Class<?>[] {V3ColumnInput.class}, steam);
        assertEquals(steam, readNbt(tag));
        tag.remove("SteamFeeds");
        assertThrows(InvocationTargetException.class, () -> readNbt(tag));
    }

    @Test
    void sevenStreamCertificateRoundTripsThroughWireAndNbtAndRejectsAnEighthStream() throws Exception {
        List<V3ColumnStreamProperties> streams = java.util.stream.IntStream.range(0, 7).mapToObj(index ->
                new V3ColumnStreamProperties("product_" + index, "Product " + index, "LIQUID", 10, 1, 400, 250_000, 0,
                        List.of(new V3ColumnStreamProperties.ComponentFraction("a", 1, 1)))).toList();
        V3ColumnDisplayResult result = new V3ColumnDisplayResult("0".repeat(64), "mesh", "assumptions", "data", 2, 0, 6, streams);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            invoke(ColumnV3Network.class, "writeDisplayResult", new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnDisplayResult.class}, buffer, result);
            assertEquals(result, invoke(ColumnV3Network.class, "readDisplayResult", new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
        CompoundTag tag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeDisplayResult",
                new Class<?>[] {V3ColumnDisplayResult.class}, result);
        assertEquals(result, invoke(ColumnCalculatorV3BlockEntity.class, "readDisplayResult", new Class<?>[] {CompoundTag.class}, tag));
        ListTag streamTags = tag.getList("Streams", net.minecraft.nbt.Tag.TAG_COMPOUND);
        streamTags.add(streamTags.getCompound(0).copy());
        assertInstanceOf(IllegalArgumentException.class, assertThrows(InvocationTargetException.class, () ->
                invoke(ColumnCalculatorV3BlockEntity.class, "readDisplayResult", new Class<?>[] {CompoundTag.class}, tag)).getCause());
    }

    @Test
    void malformedAndOversizedListsAreRejectedBeforeAllocationOrSilentMigration() throws Exception {
        // An empty input ends in the three bounded list counts, written in order as one zero byte each. Address them
        // by name: poking the last byte alone once forged a pumparound count while claiming to test side draws, so
        // the assertion only tracked the side-draw bound while MAX_PUMPAROUNDS happened to be smaller.
        assertForgedCountIsRejected(3, V3ColumnInput.MAX_SIDE_DRAWS + 1, "side draw");
        assertForgedCountIsRejected(2, V3ColumnInput.MAX_STEAM_FEEDS + 1, "steam feed");
        assertForgedCountIsRejected(1, V3ColumnInput.MAX_PUMPAROUNDS + 1, "pumparound");
        // In bound but with no payload behind it: still the decoder's to refuse, never a read off the end.
        assertForgedCountIsRejected(1, V3ColumnInput.MAX_PUMPAROUNDS, "truncated pumparound");

        CompoundTag tag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeInput", new Class<?>[] {V3ColumnInput.class}, input(1));
        ListTag invalid = new ListTag();
        invalid.add(StringTag.valueOf("not a draw"));
        tag.put("SideDraws", invalid);
        assertInstanceOf(IllegalArgumentException.class, assertThrows(InvocationTargetException.class, () -> readNbt(tag)).getCause());
    }

    /** Overwrites one trailing list-count byte of an otherwise well-formed input and requires a decoder rejection. */
    private static void assertForgedCountIsRejected(int bytesFromEnd, int forgedCount, String field) throws Exception {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            invoke(ColumnV3Network.class, "writeInput", new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnInput.class}, buffer, input(0));
            int index = buffer.writerIndex() - bytesFromEnd;
            assertEquals(0, buffer.getByte(index), () -> "expected the empty " + field + " count at this offset");
            buffer.setByte(index, forgedCount);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> invoke(ColumnV3Network.class,
                    "readInput", new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer));
            assertInstanceOf(DecoderException.class, failure.getCause(),
                    () -> field + " count " + forgedCount + " escaped the guard as " + failure.getCause());
        } finally {
            buffer.release();
        }
    }

    private static Object readNbt(CompoundTag tag) throws Exception {
        return invoke(ColumnCalculatorV3BlockEntity.class, "readInput", new Class<?>[] {CompoundTag.class}, tag);
    }

    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static V3ColumnInput input(int count) {
        List<V3SideDrawSpec> draws = List.of(new V3SideDrawSpec(1, 2), new V3SideDrawSpec(2, 3), new V3SideDrawSpec(4, 5));
        return new V3ColumnInput(1, "test:binary", "test:codec", new V3ComponentBasis(List.of("a", "b")),
                new double[] {40, 60}, 450, 4, 2, 250_000, 750, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(332.15), new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                new V3ColumnSpecification.ReboilerDuty(8_000_000)), draws.subList(0, count));
    }
}

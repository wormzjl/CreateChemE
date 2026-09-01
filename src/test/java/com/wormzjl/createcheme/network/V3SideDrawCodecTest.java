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

class V3SideDrawCodecTest {
    @Test
    void serverDefaultPublishesTheQualifiedLiteratureSideDraws() throws Exception {
        V3ColumnInput input = (V3ColumnInput) invoke(
                ColumnCalculatorV3BlockEntity.class, "defaultInput", new Class<?>[0]);

        assertEquals(29, input.stageCount());
        assertEquals(150_000.0, input.topPressurePascal());
        assertEquals(List.of(13, 17, 22), input.sideDraws().stream().map(V3SideDrawSpec::trayNumber).toList());
        assertArrayEquals(new double[] {92.2974747474748, 131.853535353535, 32.9633838383838},
                input.sideDraws().stream().mapToDouble(draw -> draw.molarFlowMolPerSecond() * 3.6).toArray(),
                1.0e-12);
    }

    @Test
    void wireAndNbtRoundTripZeroThroughThreeDrawsAndLegacyNbt() throws Exception {
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
            assertEquals(input(0), readNbt(tag));
        }
    }

    @Test
    void sixStreamCertificateRoundTripsThroughWireAndNbtAndRejectsASeventhStream() throws Exception {
        List<V3ColumnStreamProperties> streams = java.util.stream.IntStream.range(0, 6).mapToObj(index ->
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
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            invoke(ColumnV3Network.class, "writeInput", new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnInput.class}, buffer, input(0));
            buffer.setByte(buffer.writerIndex() - 1, V3ColumnInput.MAX_SIDE_DRAWS + 1);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> invoke(ColumnV3Network.class,
                    "readInput", new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer));
            assertInstanceOf(DecoderException.class, failure.getCause());
        } finally {
            buffer.release();
        }
        CompoundTag tag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeInput", new Class<?>[] {V3ColumnInput.class}, input(1));
        ListTag invalid = new ListTag();
        invalid.add(StringTag.valueOf("not a draw"));
        tag.put("SideDraws", invalid);
        assertInstanceOf(IllegalArgumentException.class, assertThrows(InvocationTargetException.class, () -> readNbt(tag)).getCause());
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

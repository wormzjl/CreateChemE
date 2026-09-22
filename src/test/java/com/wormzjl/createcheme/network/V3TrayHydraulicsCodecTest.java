package com.wormzjl.createcheme.network;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * Transport contract for the authored column diameter and the tray hydraulics of an accepted result
 * (data version 10, wire schema 12).
 *
 * <p>Same reflection pattern as {@link V3ClosureCodecTest}: the codecs are private statics and the unit suite
 * has no Minecraft bootstrap.</p>
 */
class V3TrayHydraulicsCodecTest {
    @Test
    void bothModesOfTheInputRoundTripThroughWireAndNbt() throws Exception {
        for (double diameter : new double[] {V3ColumnInput.PRESCRIBED_DROP_DIAMETER, 0.5,
                V3ColumnInput.DEFAULT_COLUMN_DIAMETER_METRES, V3ColumnInput.MAX_COLUMN_DIAMETER_METRES}) {
            V3ColumnInput input = input(diameter);

            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                invoke(ColumnV3Network.class, "writeInput",
                        new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnInput.class}, buffer, input);
                V3ColumnInput decoded = (V3ColumnInput) invoke(ColumnV3Network.class, "readInput",
                        new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer);
                assertEquals(diameter, decoded.columnDiameterMetres());
                assertEquals(input, decoded);
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }

            CompoundTag tag = writeInputNbt(input);
            assertEquals(diameter, tag.getDouble("ColumnDiameter"));
            assertEquals(input, readInputNbt(tag));
        }
    }

    /**
     * A persisted input written before the diameter existed is the prescribed-drop column it always was.
     *
     * <p>That is why version 9 is still readable rather than retained as an unsupported state: nothing about
     * such a block's published behaviour changes when this build loads it.</p>
     */
    @Test
    void aVersionNineInputWithoutTheKeyLoadsInPrescribedDropMode() throws Exception {
        assertEquals(10, ColumnCalculatorV3BlockEntity.DATA_VERSION);
        assertEquals(12, ColumnV3Network.WIRE_SCHEMA_VERSION);

        CompoundTag tag = writeInputNbt(input(V3ColumnInput.DEFAULT_COLUMN_DIAMETER_METRES));
        tag.remove("ColumnDiameter");
        assertFalse(tag.contains("ColumnDiameter", Tag.TAG_DOUBLE));

        V3ColumnInput migrated = readInputNbt(tag);
        assertEquals(V3ColumnInput.PRESCRIBED_DROP_DIAMETER, migrated.columnDiameterMetres());
        assertFalse(migrated.usesTrayHydraulics());
        assertEquals(input(V3ColumnInput.PRESCRIBED_DROP_DIAMETER), migrated);
    }

    @Test
    void theHydraulicsSummaryRoundTripsAndIsAbsentFromAPrescribedDropResult() throws Exception {
        for (Optional<V3TrayHydraulicsSummary> hydraulics : List.of(
                Optional.<V3TrayHydraulicsSummary>empty(), Optional.of(summary()))) {
            V3ColumnDisplayResult result = result(hydraulics);

            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                invoke(ColumnV3Network.class, "writeDisplayResult",
                        new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnDisplayResult.class}, buffer, result);
                V3ColumnDisplayResult decoded = (V3ColumnDisplayResult) invoke(ColumnV3Network.class,
                        "readDisplayResult", new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer);
                assertEquals(hydraulics, decoded.trayHydraulics());
                assertEquals(result, decoded);
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }

            CompoundTag tag = (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeDisplayResult",
                    new Class<?>[] {V3ColumnDisplayResult.class}, result);
            assertEquals(hydraulics.isPresent(), tag.contains("Hydraulics", Tag.TAG_COMPOUND));
            V3ColumnDisplayResult persisted = (V3ColumnDisplayResult) invoke(ColumnCalculatorV3BlockEntity.class,
                    "readDisplayResult", new Class<?>[] {CompoundTag.class}, tag);
            assertEquals(hydraulics, persisted.trayHydraulics());
            assertEquals(result, persisted);
        }
    }

    @Test
    void anImplausibleSummaryIsRejectedByTheContractRatherThanDisplayed() {
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                0.0, 22_000.0, 565.0, 0.69, 33, true, 0.004, 292.4, 344.1),
                "a summary needs the diameter it was computed at");
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                8.0, Double.NaN, 565.0, 0.69, 33, true, 0.004, 292.4, 344.1));
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                8.0, 22_000.0, 565.0, 0.69, 0, true, 0.004, 292.4, 344.1), "the worst tray is a tray number");
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                8.0, 22_000.0, 565.0, 0.69, V3ColumnInput.MAX_STAGE_COUNT + 1, true, 0.004, 292.4, 344.1));
        assertThrows(IllegalArgumentException.class, () -> new V3TrayHydraulicsSummary(
                8.0, 22_000.0, 565.0, 0.69, 33, true, 0.004, -1.0, 344.1), "drop terms are nonnegative");
        assertTrue(summary().floods() == (summary().maximumFloodFraction() > 1.0));
        assertFalse(summary().vaporLimited(), "the pinned worst tray is froth dominated");
    }

    private static V3TrayHydraulicsSummary summary() {
        return new V3TrayHydraulicsSummary(8.0, 22_034.5, 564.9, 0.687, 33, true, 0.0041, 292.4, 344.1);
    }

    private static V3ColumnDisplayResult result(Optional<V3TrayHydraulicsSummary> hydraulics) {
        List<V3ColumnStreamProperties> streams = List.of(new V3ColumnStreamProperties(
                "distillate", "Distillate", "LIQUID", 10, 1, 400, 250_000, 0,
                List.of(new V3ColumnStreamProperties.ComponentFraction("a", 1, 1))));
        return new V3ColumnDisplayResult("0".repeat(64), "mesh", "assumptions", "data", 2, 0, 6, streams,
                Optional.empty(), V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, hydraulics);
    }

    private static V3ColumnInput input(double diameter) {
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured",
                "test:binary", new V3ComponentBasis(List.of("component-a", "component-b")),
                new double[] {30.0, 60.0}, 400.0, 3, 2, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(330.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(1.0e6)),
                List.of(), List.of(), List.of(), diameter);
    }

    private static CompoundTag writeInputNbt(V3ColumnInput input) throws Exception {
        return (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeInput",
                new Class<?>[] {V3ColumnInput.class}, input);
    }

    private static V3ColumnInput readInputNbt(CompoundTag tag) throws Exception {
        return (V3ColumnInput) invoke(ColumnCalculatorV3BlockEntity.class, "readInput",
                new Class<?>[] {CompoundTag.class}, tag);
    }

    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
}

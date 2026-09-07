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
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

/**
 * Transport contract for the accepted result's convergence closure (data version 8, wire schema 8).
 *
 * <p>Same reflection pattern as {@link V3PumparoundCodecTest}: the codecs are private statics and the unit
 * suite has no Minecraft bootstrap.</p>
 */
class V3ClosureCodecTest {
    @Test
    void everyAdmittedClosureRoundTripsThroughWireAndNbt() throws Exception {
        for (double closure : new double[] {V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, 1.0e-6, 5.0e-4,
                V3ConvergenceEvidence.MAXIMUM_CLOSURE_TOLERANCE}) {
            for (Optional<V3ColumnDutyLedger> ledger : List.of(Optional.<V3ColumnDutyLedger>empty(), Optional.of(ledger()))) {
                V3ColumnDisplayResult result = result(ledger, closure);
                assertEquals(closure, result.closureTolerance(), 0.0);

                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    writeWire(buffer, result);
                    V3ColumnDisplayResult decoded = readWire(buffer);
                    assertEquals(result, decoded);
                    assertEquals(closure, decoded.closureTolerance(), 0.0);
                    assertEquals(ledger, decoded.dutyLedger());
                    assertEquals(0, buffer.readableBytes());
                } finally {
                    buffer.release();
                }

                CompoundTag tag = writeNbt(result);
                assertEquals(closure, tag.getDouble("ClosureTolerance"), 0.0);
                V3ColumnDisplayResult persisted = readNbt(tag);
                assertEquals(result, persisted);
                assertEquals(closure, persisted.closureTolerance(), 0.0);
            }
        }
    }

    @Test
    void aVersionSevenResultWithoutTheKeyReadsAsTheDefaultClosure() throws Exception {
        assertEquals(8, ColumnCalculatorV3BlockEntity.DATA_VERSION);
        assertEquals(8, ColumnV3Network.WIRE_SCHEMA_VERSION);

        CompoundTag tag = writeNbt(result(Optional.of(ledger()), 1.0e-3));
        tag.remove("ClosureTolerance");
        assertFalse(tag.contains("ClosureTolerance", Tag.TAG_DOUBLE));
        V3ColumnDisplayResult migrated = readNbt(tag);
        assertEquals(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, migrated.closureTolerance(), 0.0);
        assertEquals(result(Optional.of(ledger()), V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE), migrated);
    }

    @Test
    void bothTransportsRejectAClosureOutsideTheAdmittedRange() throws Exception {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            writeWire(buffer, result(Optional.empty(), 1.0e-3));
            // The closure double is the last field of a ledger-free certificate but for the absent-ledger flag.
            buffer.setDouble(buffer.writerIndex() - 9, Double.NaN);
            assertInstanceOf(DecoderException.class, assertThrows(InvocationTargetException.class,
                    () -> readWire(buffer)).getCause());
        } finally {
            buffer.release();
        }

        RegistryFriendlyByteBuf tooLoose = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            writeWire(tooLoose, result(Optional.empty(), 1.0e-3));
            tooLoose.setDouble(tooLoose.writerIndex() - 9, 1.0e-2);
            assertInstanceOf(DecoderException.class, assertThrows(InvocationTargetException.class,
                    () -> readWire(tooLoose)).getCause());
        } finally {
            tooLoose.release();
        }

        CompoundTag tooTight = writeNbt(result(Optional.empty(), 1.0e-3));
        tooTight.putDouble("ClosureTolerance", 1.0e-9);
        assertInstanceOf(IllegalArgumentException.class, assertThrows(InvocationTargetException.class,
                () -> readNbt(tooTight)).getCause());
    }

    private static void writeWire(RegistryFriendlyByteBuf buffer, V3ColumnDisplayResult result) throws Exception {
        invoke(ColumnV3Network.class, "writeDisplayResult",
                new Class<?>[] {RegistryFriendlyByteBuf.class, V3ColumnDisplayResult.class}, buffer, result);
    }

    private static V3ColumnDisplayResult readWire(RegistryFriendlyByteBuf buffer) throws Exception {
        return (V3ColumnDisplayResult) invoke(ColumnV3Network.class, "readDisplayResult",
                new Class<?>[] {RegistryFriendlyByteBuf.class}, buffer);
    }

    private static CompoundTag writeNbt(V3ColumnDisplayResult result) throws Exception {
        return (CompoundTag) invoke(ColumnCalculatorV3BlockEntity.class, "writeDisplayResult",
                new Class<?>[] {V3ColumnDisplayResult.class}, result);
    }

    private static V3ColumnDisplayResult readNbt(CompoundTag tag) throws Exception {
        return (V3ColumnDisplayResult) invoke(ColumnCalculatorV3BlockEntity.class, "readDisplayResult",
                new Class<?>[] {CompoundTag.class}, tag);
    }

    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static V3ColumnDutyLedger ledger() {
        List<V3ColumnDutyLedger.StageDuty> duties = new ArrayList<>();
        duties.add(new V3ColumnDutyLedger.StageDuty(3, -3_400_000.0));
        return new V3ColumnDutyLedger(-5_500_000.0, 8_000_000.0, -3_400_000.0, duties, 1_250_000.0, 320_000.0);
    }

    private static V3ColumnDisplayResult result(Optional<V3ColumnDutyLedger> ledger, double closure) {
        List<V3ColumnStreamProperties> streams = List.of(new V3ColumnStreamProperties(
                "distillate", "Distillate", "LIQUID", 10, 1, 400, 250_000, 0,
                List.of(new V3ColumnStreamProperties.ComponentFraction("a", 1, 1))));
        return new V3ColumnDisplayResult("0".repeat(64), "mesh", "assumptions", "data", 2, 0, 6, streams, ledger,
                closure);
    }
}

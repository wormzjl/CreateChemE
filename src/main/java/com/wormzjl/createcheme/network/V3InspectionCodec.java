package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.science.column.v3.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One bounded binary representation for both the inspection wire payload and persisted byte array. */
public final class V3InspectionCodec {
    public static final int MAX_BYTES = 262144;
    private V3InspectionCodec() {}

    public static byte[] encode(Optional<V3ColumnInspection> inspection) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeBoolean(inspection.isPresent());
            if (inspection.isPresent()) {
                var value = inspection.orElseThrow();
                ColumnV3Network.writeInput(buffer, value.input());
                for (var node : value.nodes()) {
                    buffer.writeDouble(node.temperatureKelvin()); buffer.writeDouble(node.pressurePascal());
                    buffer.writeDouble(node.liquidMolPerSecond()); buffer.writeDouble(node.vaporMolPerSecond());
                    buffer.writeDouble(node.waterVaporMolPerSecond()); buffer.writeDouble(node.freeWaterMolPerSecond());
                    for (double x : node.liquidFractions()) buffer.writeDouble(x);
                    for (double y : node.vaporFractions()) buffer.writeDouble(y);
                }
                buffer.writeVarInt(value.audit().checks().size());
                for (var check : value.audit().checks()) {
                    buffer.writeUtf(check.family(), 96); buffer.writeDouble(check.value());
                    buffer.writeDouble(check.limit()); buffer.writeBoolean(check.passed());
                    buffer.writeUtf(check.detail(), 512);
                }
                buffer.writeVarInt(value.audit().advisoryEvidence().size());
                for (var warning : value.audit().advisoryEvidence()) buffer.writeUtf(warning, 256);
            }
            if (buffer.readableBytes() > MAX_BYTES) throw new IllegalArgumentException("Inspection too large");
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally { buffer.release(); }
    }

    public static Optional<V3ColumnInspection> decode(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new DecoderException("Invalid inspection size");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), RegistryAccess.EMPTY);
        try {
            Optional<V3ColumnInspection> result = Optional.empty();
            if (buffer.readBoolean()) {
                var input = ColumnV3Network.readInput(buffer);
                int components = input.componentBasis().componentCount(), count = input.stageCount() + 2;
                long required = 8L * count * (6 + 2 * components);
                if (count < V3ColumnInput.MIN_STAGE_COUNT + 2 || count > V3ColumnInput.MAX_STAGE_COUNT + 2 || required > buffer.readableBytes())
                    throw new DecoderException("Invalid inspection dimensions");
                List<V3ColumnInspection.Node> nodes = new ArrayList<>(count);
                for (int n = 0; n < count; n++) {
                    double t = buffer.readDouble(), p = buffer.readDouble(), l = buffer.readDouble(),
                            v = buffer.readDouble(), w = buffer.readDouble(), f = buffer.readDouble();
                    List<Double> x = new ArrayList<>(components), y = new ArrayList<>(components);
                    for (int c = 0; c < components; c++) x.add(buffer.readDouble());
                    for (int c = 0; c < components; c++) y.add(buffer.readDouble());
                    nodes.add(new V3ColumnInspection.Node(t, p, l, v, w, f, x, y));
                }
                int checks = boundedCount(buffer, 64);
                List<V3AcceptanceAudit.Check> audit = new ArrayList<>(checks);
                for (int n = 0; n < checks; n++)
                    audit.add(new V3AcceptanceAudit.Check(buffer.readUtf(96), buffer.readDouble(),
                            buffer.readDouble(), buffer.readBoolean(), buffer.readUtf(512)));
                int warnings = boundedCount(buffer, 16);
                List<String> advisories = new ArrayList<>(warnings);
                for (int n = 0; n < warnings; n++) advisories.add(buffer.readUtf(256));
                result = Optional.of(new V3ColumnInspection(input, nodes, new V3AcceptanceAudit(audit, advisories)));
            }
            if (buffer.isReadable()) throw new DecoderException("Trailing inspection data");
            return result;
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            throw new DecoderException("Invalid column inspection", invalid);
        } finally { buffer.release(); }
    }

    private static int boundedCount(RegistryFriendlyByteBuf buffer, int max) {
        int count = buffer.readVarInt();
        if (count < 0 || count > max || count > buffer.readableBytes())
            throw new DecoderException("Invalid inspection list length");
        return count;
    }
}

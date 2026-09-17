package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.world.level.block.entity.ColumnInputPreset;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnPresetCodecTest {
    @SuppressWarnings("unchecked")
    private static StreamCodec<RegistryFriendlyByteBuf,Object> codec() throws Exception {
        var field=Class.forName(ColumnV3Network.class.getName()+"$PresetPayload").getDeclaredField("STREAM_CODEC");
        field.setAccessible(true);
        return (StreamCodec<RegistryFriendlyByteBuf,Object>)field.get(null);
    }
    @Test void allPresetIdsRoundTripWithTheirTargetAndRevision() throws Exception {
        var constructor=Class.forName(ColumnV3Network.class.getName()+"$PresetPayload")
                .getDeclaredConstructor(BlockPos.class,long.class,long.class,String.class);
        constructor.setAccessible(true);
        var codec=codec();
        for (var choice:ColumnInputPreset.values()) {
            var packet=constructor.newInstance(new BlockPos(1,2,3),12L,34L,choice.id());
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try {
                codec.encode(buffer,packet);
                assertEquals(packet,codec.decode(buffer));
                assertEquals(0,buffer.readableBytes());
            } finally {buffer.release();}
        }
    }
    @Test void malformedAndOversizedIdsAreRejectedBeforeServerLookup() throws Exception {
        var codec=codec();
        for (String id:new String[]{"bad preset id","x".repeat(129)}) {
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try {
                buffer.writeVarInt(ColumnV3Network.WIRE_SCHEMA_VERSION);
                buffer.writeBlockPos(BlockPos.ZERO);buffer.writeVarLong(1);buffer.writeVarLong(0);buffer.writeUtf(id);
                assertThrows(DecoderException.class,()->codec.decode(buffer));
            } finally {buffer.release();}
        }
    }
}

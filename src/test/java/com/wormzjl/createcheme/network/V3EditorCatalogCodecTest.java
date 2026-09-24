package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V3EditorCatalogCodecTest {
    private V3EditorCatalog catalog(){
        var materials=MaterialCatalog.bundled();
        return V3EditorCatalog.from(materials,materials.presets().column(materials.presets().visibleColumns().getFirst().id()).input(materials));
    }
    @Test void completeProductionCatalogueRoundTripsAndContainsMatchingWeights(){
        var c=catalog();var bytes=V3EditorCatalogCodec.encode(c);
        assertEquals(c,V3EditorCatalogCodec.decode(bytes));assertTrue(bytes.length<V3EditorCatalogCodec.MAX_BYTES);
        for(var input:c.templates().values())assertEquals(input.componentBasis().componentCount(),c.weightsFor(input).size());
        assertTrue(c.templates().containsKey("holland_3_2"));
    }
    @Test void realStatePacketCarriesTemplatesAndPreciseMolecularWeights() throws Exception {
        var c=catalog();var input=c.templates().values().iterator().next();
        var state=new V3State(1,2,0,3,-1,V3Status.DIRTY,input,Optional.empty(),List.of("Ready"),Map.of(),List.of(),c);
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            var write=ColumnV3Network.class.getDeclaredMethod("writeState",RegistryFriendlyByteBuf.class,V3State.class);
            var read=ColumnV3Network.class.getDeclaredMethod("readState",RegistryFriendlyByteBuf.class);
            write.setAccessible(true);read.setAccessible(true);write.invoke(null,b,state);
            assertEquals(state,read.invoke(null,b));assertEquals(0,b.readableBytes());
        }finally{b.release();}
    }
    @Test void rejectsTruncatedTrailingOversizedAndNonphysicalData(){
        var bytes=V3EditorCatalogCodec.encode(catalog());
        assertThrows(DecoderException.class,()->V3EditorCatalogCodec.decode(Arrays.copyOf(bytes,bytes.length-1)));
        assertThrows(DecoderException.class,()->V3EditorCatalogCodec.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(DecoderException.class,()->V3EditorCatalogCodec.decode(new byte[V3EditorCatalogCodec.MAX_BYTES+1]));
        assertThrows(IllegalArgumentException.class,()->new V3EditorCatalog(Map.of(),Map.of("test",List.of(Double.NaN))));
        assertThrows(IllegalArgumentException.class,()->new V3EditorCatalog(Map.of(),Map.of("test",List.of(0.0))));
    }
}

package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.science.column.v3.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.*;

/** Bounded current-version editor catalogue; transmitted at engine presentation, never persisted. */
public final class V3EditorCatalogCodec {
    public static final int MAX_BYTES=262144;
    private V3EditorCatalogCodec() {}
    public static byte[] encode(V3EditorCatalog catalog) {
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            b.writeVarInt(catalog.molecularWeights().size());
            for(var entry:new TreeMap<>(catalog.molecularWeights()).entrySet()){
                b.writeUtf(entry.getKey(),128);b.writeVarInt(entry.getValue().size());
                for(double value:entry.getValue())b.writeDouble(value);
            }
            b.writeVarInt(catalog.templates().size());
            for(var entry:new TreeMap<>(catalog.templates()).entrySet()){
                b.writeUtf(entry.getKey(),128);ColumnV3Network.writeInput(b,entry.getValue());
            }
            if(b.readableBytes()>MAX_BYTES)throw new IllegalArgumentException("Editor catalogue too large");
            byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);return bytes;
        }finally{b.release();}
    }
    public static V3EditorCatalog decode(byte[] bytes) {
        if(bytes.length>MAX_BYTES)throw new DecoderException("Editor catalogue too large");
        var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);
        try{
            var weights=new LinkedHashMap<String,List<Double>>();
            int count=count(b,V3EditorCatalog.MAX_PACKAGES);
            for(int i=0;i<count;i++){
                String id=b.readUtf(128);int size=count(b,64);
                if(size*8>b.readableBytes())throw new DecoderException("Short weight axis");
                List<Double> values=new ArrayList<>();
                for(int c=0;c<size;c++)values.add(b.readDouble());
                if(weights.putIfAbsent(id,values)!=null)throw new DecoderException("Duplicate package");
            }
            var templates=new LinkedHashMap<String,V3ColumnInput>();
            count=count(b,V3EditorCatalog.MAX_TEMPLATES);
            for(int i=0;i<count;i++){
                String id=b.readUtf(128);
                if(templates.putIfAbsent(id,ColumnV3Network.readInput(b))!=null)throw new DecoderException("Duplicate template");
            }
            if(b.isReadable())throw new DecoderException("Trailing editor catalogue data");
            return new V3EditorCatalog(templates,weights);
        }catch(IllegalArgumentException|IndexOutOfBoundsException e){throw new DecoderException("Invalid editor catalogue",e);}
        finally{b.release();}
    }
    private static int count(RegistryFriendlyByteBuf b,int maximum){
        int value=b.readVarInt();if(value<0||value>maximum||value>b.readableBytes())throw new DecoderException("Invalid catalogue count");return value;
    }
}

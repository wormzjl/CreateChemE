package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.science.material.*;
import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialNamesCodecTest {
    @Test void serverNamingDescriptorsRoundTripWithoutClientCatalogLookup() throws Exception {
        var input=ColumnCalculatorV3BlockEntity.methaneCduInput();
        var descriptor=new MaterialName("custom_cut","custom.translation","Crude oil","petroleum_fraction",600.,650.,true);
        var state=new V3State(1,2,0,1,-1,V3Status.DIRTY,input,Optional.empty(),List.of(),Map.of("custom_cut",descriptor));
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            invoke(ColumnV3Network.class,"writeState",new Class<?>[]{RegistryFriendlyByteBuf.class,V3State.class},buffer,state);
            var decoded=(V3State)invoke(ColumnV3Network.class,"readState",new Class<?>[]{RegistryFriendlyByteBuf.class},buffer);
            assertEquals(state,decoded);assertEquals(0,buffer.readableBytes());
        }finally{buffer.release();}
    }
    @Test void retiredAxesAreRejectedByTheActualNbtReader() throws Exception {
        var input=ColumnCalculatorV3BlockEntity.methaneCduInput();
        var tag=(CompoundTag)invoke(ColumnCalculatorV3BlockEntity.class,"writeInput",new Class<?>[]{V3ColumnInput.class},input);
        var axis=tag.getList("Axis",Tag.TAG_STRING);
        for(int i=0;i<axis.size();i++) {
            String id=axis.getString(i);if(id.startsWith("crude_pc"))axis.set(i,StringTag.valueOf("tjl19_pc"+id.substring(8)));
        }
        var error=assertThrows(java.lang.reflect.InvocationTargetException.class,()->invoke(ColumnCalculatorV3BlockEntity.class,"readInput",new Class<?>[]{CompoundTag.class},tag));
        assertInstanceOf(IllegalArgumentException.class,error.getCause());
    }
    @Test void descriptorsRejectInvalidBoundsAndOversizeLabels() {
        assertThrows(IllegalArgumentException.class,()->new MaterialName("id","key","x".repeat(129),"lump",null,null,false));
        assertThrows(IllegalArgumentException.class,()->new MaterialName("id","key","Crude oil","petroleum_fraction",600.,500.,true));
        assertThrows(IllegalArgumentException.class,()->new MaterialName("Ethane","key","Propane","chemical",null,null,false));
    }
    private static Object invoke(Class<?> type,String name,Class<?>[] signature,Object... arguments) throws Exception {
        Method method=type.getDeclaredMethod(name,signature);method.setAccessible(true);return method.invoke(null,arguments);
    }
}

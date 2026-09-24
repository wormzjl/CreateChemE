package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.science.column.v3.*;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V3InspectionCodecTest {
    private static V3ColumnInspection inspection(int trays, int components) {
        List<String> axis = new ArrayList<>();
        double[] flows = new double[components];
        for (int c=0;c<components;c++) {axis.add("c"+c);flows[c]=1;}
        var input = new V3ColumnInput(1,"test:package","test:case",new V3ComponentBasis(axis),flows,500,
                trays,1,250000,0,List.of(new V3ColumnSpecification.CondenserOutletTemperature(350),
                new V3ColumnSpecification.OrganicRefluxRatio(2),new V3ColumnSpecification.ReboilerDuty(0)));
        var x = Collections.nCopies(components,1.0/components);
        List<V3ColumnInspection.Node> nodes = new ArrayList<>();
        for(int n=0;n<trays+2;n++) nodes.add(new V3ColumnInspection.Node(350+n,250000,1,2,0,0,x,x));
        return new V3ColumnInspection(input,nodes,new V3AcceptanceAudit(
                List.of(V3AcceptanceAudit.Check.pass("material",1e-12,1e-8,"Independent conservation")),
                List.of("Example advisory")));
    }
    @Test void minimumAndMaximumProfilesRoundTripExactly() {
        for(int n:new int[]{0,1,2,64})for(int c:new int[]{1,64}){
            var profile=Optional.of(inspection(n,c));byte[] bytes=V3InspectionCodec.encode(profile);
            assertTrue(bytes.length<V3InspectionCodec.MAX_BYTES);
            assertEquals(profile,V3InspectionCodec.decode(bytes));
        }
        assertEquals(Optional.empty(),V3InspectionCodec.decode(V3InspectionCodec.encode(Optional.empty())));
    }
    @Test void rejectsTruncatedTrailingAndOversizedPayloads() {
        byte[] bytes=V3InspectionCodec.encode(Optional.of(inspection(2,1)));
        assertThrows(DecoderException.class,()->V3InspectionCodec.decode(Arrays.copyOf(bytes,bytes.length-1)));
        assertThrows(DecoderException.class,()->V3InspectionCodec.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(DecoderException.class,()->V3InspectionCodec.decode(new byte[V3InspectionCodec.MAX_BYTES+1]));
    }
    @Test void snapshotIsDeeplyImmutableAndRejectsNonphysicalProfiles() {
        var profile=inspection(2,1);
        assertThrows(UnsupportedOperationException.class,()->profile.nodes().clear());
        assertThrows(UnsupportedOperationException.class,()->profile.nodes().getFirst().liquidFractions().set(0,0.0));
        assertThrows(IllegalArgumentException.class,()->new V3ColumnInspection.Node(Double.NaN,1,1,1,0,0,List.of(1.0),List.of(1.0)));
        assertThrows(IllegalArgumentException.class,()->new V3ColumnInspection.Node(300,1,0,1,0,0,List.of(1.0),List.of(1.0)));
        assertThrows(IllegalArgumentException.class,()->new V3ColumnInspection(profile.input(),List.of(profile.nodes().getFirst()),profile.audit()));
    }
    @Test void acceptedDisplayRoundTripsThroughRealWireAndNbt() throws Exception {
        var profile=inspection(0,1);
        var result=new V3ColumnDisplayResult("0".repeat(64),"mesh","assumptions","data",3,1e-12,1,
                List.of(),Optional.empty(),V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE,Optional.empty(),Optional.of(profile));
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            var write=ColumnV3Network.class.getDeclaredMethod("writeDisplayResult",RegistryFriendlyByteBuf.class,V3ColumnDisplayResult.class);
            var read=ColumnV3Network.class.getDeclaredMethod("readDisplayResult",RegistryFriendlyByteBuf.class);
            write.setAccessible(true);read.setAccessible(true);write.invoke(null,buffer,result);
            assertEquals(result,read.invoke(null,buffer));assertEquals(0,buffer.readableBytes());
        } finally {buffer.release();}
        var write=ColumnCalculatorV3BlockEntity.class.getDeclaredMethod("writeDisplayResult",V3ColumnDisplayResult.class);
        var read=ColumnCalculatorV3BlockEntity.class.getDeclaredMethod("readDisplayResult",CompoundTag.class);
        write.setAccessible(true);read.setAccessible(true);
        var tag=(CompoundTag)write.invoke(null,result);
        assertEquals(result,read.invoke(null,tag));
        tag.remove("Inspection");
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->read.invoke(null,tag));
    }
}

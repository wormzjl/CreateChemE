package com.wormzjl.createcheme.runtime.fluid;

import com.google.gson.Gson;
import com.wormzjl.createcheme.network.FluidNetwork;
import com.wormzjl.createcheme.science.fluid.network.PipeTransfer;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidPacketCodecTest {
    @Test void editWirePreservesItsExactMenuPositionIdentityAndRevisionAndRejectsOversizedText() {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            var payload=new FluidNetwork.EditPayload(5,new BlockPos(-30,80,400),Long.MAX_VALUE-1,37,"{\"pressure\":200000}");
            FluidNetwork.EditPayload.STREAM_CODEC.encode(buffer,payload);assertEquals(payload,FluidNetwork.EditPayload.STREAM_CODEC.decode(buffer));
            assertThrows(RuntimeException.class,()->FluidNetwork.EditPayload.STREAM_CODEC.encode(buffer,new FluidNetwork.EditPayload(5,BlockPos.ZERO,1,0,"x".repeat(FluidNetwork.MAX_EDIT_JSON+1))));
        } finally {buffer.release();}
    }
    @Test void nonfiniteOutOfDomainAndMalformedCompositionControlsCannotReachAnApply() {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(Double.NaN,101325,.05,.000045,.01,500000,n));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,Double.POSITIVE_INFINITY,.05,.000045,.01,500000,n));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,3000000,.05,.000045,.01,500000,n));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,new double[23]));
        var accepted=new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,n);n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=0;assertEquals(1,accepted.composition()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]);
    }
    @Test void aFullBranchHistoryFitsTheBoundedClientWireAndSurvivesTheActualJsonSchema() {
        double[][] amounts=new double[3][22];for(int p=0;p<3;p++)for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)amounts[p][c]=(p+1)*(c+1)*1e-6;
        var history=new ArrayList<PipeTransfer>();for(int i=0;i<12;i++)history.add(new PipeTransfer(i+1,new PipeTransfer.Stream(12.34,amounts,new double[]{.01,.02,.03}),new PipeTransfer.Stream(2.34,amounts,new double[]{.001,.002,.003})));
        var view=new FluidView(42,7,200,250,"APPROXIMATE",new FluidView.State(101325,298.15,1,50,new double[]{.1,.2,.7},amounts),.25,history);
        var names=new ArrayList<String>();for(int i=0;i<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;i++)names.add("component_"+i);
        var controls=new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,FluidDeviceSpec.nitrogen().composition());
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,view,controls,names,List.of(),"");var gson=new Gson();String json=gson.toJson(data);assertTrue(json.length()<FluidNetwork.MAX_JSON);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            var packet=new FluidNetwork.StatePayload(3,42,json);FluidNetwork.StatePayload.STREAM_CODEC.encode(buffer,packet);var decoded=FluidNetwork.StatePayload.STREAM_CODEC.decode(buffer);
            assertEquals(packet,decoded);var roundTrip=gson.fromJson(decoded.json(),FluidNetwork.MenuData.class);
            assertEquals(12,roundTrip.view().pipeHistory().size());assertArrayEquals(amounts[2],roundTrip.view().pipeHistory().get(11).reverse().phaseMoles()[2]);assertEquals(250,roundTrip.view().onlineTick());
        } finally {buffer.release();}
    }
}

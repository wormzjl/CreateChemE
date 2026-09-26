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
    private static List<Double> weights(int count){return java.util.stream.IntStream.range(0,count).mapToObj(i->0.01+i*0.002).toList();}
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
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,-1,.05,.000045,.01,500000,n));
        // Where a pressure may be evaluated is the property package's (F4): a structurally valid 3 MPa reaches the server,
        // whose edit handler refuses it against the model's domain before anything is queued.
        var model=com.wormzjl.createcheme.fluid.support.FluidTestSupport.networkModel();
        var outside=new FluidNetwork.Controls(298.15,3000000,.05,.000045,.01,500000,n);
        assertThrows(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation.class,()->new FluidDeviceSpec(1,outside.temperature(),outside.pressure(),outside.composition()).validate(model,TopologyCompiler.Kind.GENERATOR));
        assertThrows(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation.class,()->model.domain().checkPressure(outside.pressure()));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,new double[23]));
        var accepted=new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,n);n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=0;assertEquals(1,accepted.composition()[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]);
    }
    @Test void receivedComponentAxisDoesNotDependOnTheClientCatalog() {
        double[] amounts=new double[65];amounts[64]=1;
        var controls=new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,amounts);
        var names=new ArrayList<String>();for(int i=0;i<65;i++)names.add("server_"+i);
        var descriptor=new com.wormzjl.createcheme.science.material.MaterialName("server_0","server.cut","Crude oil","petroleum_fraction",800.,null,true);
        var view=new FluidView(1,0,0,0,"READY",null,0,List.of());
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.GENERATOR,view,controls,names,List.of(),"",Map.of("server_0",descriptor),weights(names.size()));
        var json=new Gson();var decoded=json.fromJson(json.toJson(data),FluidNetwork.MenuData.class);
        assertEquals(names,decoded.components());assertEquals(descriptor,decoded.materialNames().get("server_0"));assertArrayEquals(amounts,decoded.controls().composition());
        double[] oversized=new double[66];oversized[0]=1;
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,oversized));
    }
    @Test void maximumAdmittedAxisAndPresetCountsFitThePacketBudget() {
        int count=com.wormzjl.createcheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS;
        double[][] n=new double[3][count];for(var phase:n)Arrays.fill(phase,1.2345678901234567e-123);
        var history=new ArrayList<PipeTransfer>();for(int i=0;i<12;i++)history.add(new PipeTransfer(i+1,new PipeTransfer.Stream(1,n,new double[]{1,2,3}),new PipeTransfer.Stream(1,n,new double[]{1,2,3})));
        var ids=new ArrayList<String>();var descriptors=new HashMap<String,com.wormzjl.createcheme.science.material.MaterialName>();
        for(int i=0;i<count;i++){String id="server_"+i;ids.add(id);descriptors.put(id,new com.wormzjl.createcheme.science.material.MaterialName(id,"t".repeat(128),"l".repeat(128),"lump",null,null,false));}
        double[] amounts=new double[count];Arrays.fill(amounts,1.0/count);var presets=new ArrayList<FluidPresetCatalog.Preset>();
        for(int i=0;i<32;i++)presets.add(new FluidPresetCatalog.Preset("p".repeat(120)+i,"n".repeat(128),amounts));
        var view=new FluidView(1,0,0,0,"READY",new FluidView.State(101325,298.15,1,1,new double[]{1,2,3},n),0,history);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,view.withPipeInfo(FluidView.PipeInfo.empty(view.pipeHistory().getFirst().forward().componentMoles().length)),new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,amounts),ids,presets,"",descriptors,weights(ids.size()));
        String json=new Gson().toJson(data);assertTrue(json.length()<FluidNetwork.MAX_JSON);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            // fluid-6: the static part (axis, presets, names) and the live part (view, controls, reply) each fit on their own.
            String fixed=new Gson().toJson(data.staticData(7)),live=new Gson().toJson(data.liveData());
            assertTrue(fixed.length()<FluidNetwork.MAX_JSON&&live.length()<FluidNetwork.MAX_JSON,"static "+fixed.length()+", live "+live.length());
            var s=new FluidNetwork.StaticPayload(1,1,fixed);FluidNetwork.StaticPayload.STREAM_CODEC.encode(buffer,s);var l=new FluidNetwork.LivePayload(1,1,live);FluidNetwork.LivePayload.STREAM_CODEC.encode(buffer,l);
            assertTrue(buffer.readableBytes()<1048576);assertEquals(s,FluidNetwork.StaticPayload.STREAM_CODEC.decode(buffer));assertEquals(l,FluidNetwork.LivePayload.STREAM_CODEC.decode(buffer));
            var joined=FluidNetwork.MenuData.of(FluidNetwork.decodeStatic(fixed),FluidNetwork.decodeLive(live));
            assertEquals(ids,joined.components());assertEquals(32,joined.presets().size());assertEquals(count,joined.materialNames().size());
        }
        finally{buffer.release();}
    }
    @Test void aFullBranchHistoryFitsTheBoundedClientWireAndSurvivesTheActualJsonSchema() {
        double[][] amounts=new double[3][com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];for(int p=0;p<3;p++)for(int c=0;c<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;c++)amounts[p][c]=(p+1)*(c+1)*1e-6;
        var history=new ArrayList<PipeTransfer>();for(int i=0;i<12;i++)history.add(new PipeTransfer(i+1,new PipeTransfer.Stream(12.34,amounts,new double[]{.01,.02,.03}),new PipeTransfer.Stream(2.34,amounts,new double[]{.001,.002,.003})));
        var view=new FluidView(42,7,200,250,"APPROXIMATE",new FluidView.State(101325,298.15,1,50,new double[]{.1,.2,.7},amounts),.25,history);
        var names=new ArrayList<String>();for(int i=0;i<com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;i++)names.add("component_"+i);
        var controls=new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,FluidDeviceSpec.nitrogen().composition());
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,view.withPipeInfo(FluidView.PipeInfo.empty(view.pipeHistory().getFirst().forward().componentMoles().length)),controls,names,List.of(),"",Map.of(),weights(names.size()));var gson=new Gson();String json=gson.toJson(data);assertTrue(json.length()<FluidNetwork.MAX_JSON);
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            String live=gson.toJson(data.liveData());assertTrue(live.length()<FluidNetwork.MAX_JSON);
            var packet=new FluidNetwork.LivePayload(3,42,live);FluidNetwork.LivePayload.STREAM_CODEC.encode(buffer,packet);var decoded=FluidNetwork.LivePayload.STREAM_CODEC.decode(buffer);
            assertEquals(packet,decoded);var roundTrip=FluidNetwork.MenuData.of(data.staticData(0),FluidNetwork.decodeLive(decoded.json()));
            assertEquals(12,roundTrip.view().pipeHistory().size());assertArrayEquals(amounts[2],roundTrip.view().pipeHistory().get(11).reverse().phaseMoles()[2]);assertEquals(250,roundTrip.view().onlineTick());
        } finally {buffer.release();}
    }
    // ---------------- fluid-6: static and live payloads ----------------

    private static FluidNetwork.MenuData generatorMenu(String message) {
        int count=com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1;var names=new ArrayList<String>();for(int i=0;i<count;i++)names.add("component_"+i);
        double[] amounts=new double[count];amounts[0]=1;
        var presets=List.of(new FluidPresetCatalog.Preset("a","First",amounts),new FluidPresetCatalog.Preset("b","Second",amounts));
        var descriptor=new com.wormzjl.createcheme.science.material.MaterialName("component_0","component.zero","Zero","lump",null,null,false);
        var view=new FluidView(9,4,1200,1300,"RESTING: no flow since 55.0 s",null,0,List.of());
        return new FluidNetwork.MenuData(TopologyCompiler.Kind.GENERATOR,view,new FluidNetwork.Controls(298.15,150000,.05,.000045,.01,500000,amounts),names,presets,message,Map.of("component_0",descriptor),weights(names.size()));
    }
    /** The protocol is fluid-7 (fluid-6 plus the compressor's ratio control and a tank's outlets, phase-ports WP5) and
     * every payload has its own wire identity. Test name kept. */
    @Test void theProtocolIsFluidSixWithDistinctStaticAndLivePayloads() {
        assertEquals("fluid-7",FluidNetwork.PROTOCOL);
        var ids=java.util.Set.of(FluidNetwork.EditPayload.TYPE.id(),FluidNetwork.RecoverPayload.TYPE.id(),FluidNetwork.StaticPayload.TYPE.id(),FluidNetwork.LivePayload.TYPE.id());
        assertEquals(4,ids.size());
    }
    /** The static part carries the axis, presets, names and kind only; the live part the view, controls and reply only; joined they are the menu state. */
    @Test void theStaticAndLivePayloadsSplitAndRejoinIntoTheSameMenuState() {
        var data=generatorMenu(FluidPresentation.QUEUED+"1250");var gson=new Gson();
        String fixed=gson.toJson(data.staticData(4)),live=gson.toJson(data.liveData());
        for(String key:new String[]{"\"view\"","\"controls\"","\"message\""})assertFalse(fixed.contains(key),"static payload carries "+key);
        for(String key:new String[]{"\"components\"","\"presets\"","\"materialNames\""})assertFalse(live.contains(key),"live payload carries "+key);
        var decodedStatic=FluidNetwork.decodeStatic(fixed);var decodedLive=FluidNetwork.decodeLive(live);
        assertEquals(4,decodedStatic.revision());assertEquals(TopologyCompiler.Kind.GENERATOR,decodedStatic.kind());
        var joined=FluidNetwork.MenuData.of(decodedStatic,decodedLive);
        assertEquals(data.molecularWeights(),joined.molecularWeights());assertEquals(data.kind(),joined.kind());assertEquals(data.components(),joined.components());assertEquals(data.materialNames(),joined.materialNames());
        assertEquals(data.presets().size(),joined.presets().size());assertEquals(data.message(),joined.message());
        assertEquals(data.view().status(),joined.view().status());assertEquals(1300,joined.view().onlineTick());
        assertArrayEquals(data.controls().composition(),joined.controls().composition());
        // The live part is small: it is what every bucket sends, the static part only on a revision change.
        assertTrue(live.length()<fixed.length(),"live "+live.length()+" static "+fixed.length());
    }
    /** A live payload joined to the static part of another axis, or with an unbounded reply, is refused. */
    @Test void aLivePayloadMustMatchItsStaticAxisAndABoundedReply() {
        var data=generatorMenu("");
        var shortAxis=new FluidNetwork.StaticData(TopologyCompiler.Kind.GENERATOR,0,List.of("only"),List.of(),Map.of(),weights(1));
        assertThrows(IllegalArgumentException.class,()->FluidNetwork.MenuData.of(shortAxis,data.liveData()));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.LiveData(data.view(),data.controls(),"x".repeat(1025)));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.StaticData(TopologyCompiler.Kind.GENERATOR,-1,data.components(),List.of(),Map.of(),weights(data.components().size())));
    }
    @Test void staticMolecularWeightsAreMandatoryPositiveAndOnTheServerAxis(){
        var data=generatorMenu("");var json=new Gson().toJsonTree(data.staticData(4)).getAsJsonObject();
        json.add("molecularWeights",new Gson().toJsonTree(List.of(0.01)));
        assertThrows(RuntimeException.class,()->FluidNetwork.decodeStatic(json.toString()));
        json.add("molecularWeights",new Gson().toJsonTree(Collections.nCopies(data.components().size(),0.0)));
        assertThrows(RuntimeException.class,()->FluidNetwork.decodeStatic(json.toString()));
        json.remove("molecularWeights");assertThrows(RuntimeException.class,()->FluidNetwork.decodeStatic(json.toString()));
    }
    @Test void pipeInspectionRoundTripsAndRejectsInvalidMetricValues(){
        var base=generatorMenu("");int count=base.components().size();double[][] n=new double[3][count];n[2][0]=4;
        var info=new FluidView.PipeInfo(new PipeTransfer.Stream(2,n,new double[]{0,0,.5}),
            List.of(new FluidView.PipeConnection(17,1,2,3,true)),true,true);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,base.view().withPipeInfo(info),base.controls(),
            base.components(),base.presets(),"",base.materialNames(),base.molecularWeights());
        var gson=new Gson();var copy=FluidNetwork.MenuData.of(data.staticData(0),FluidNetwork.decodeLive(gson.toJson(data.liveData())));
        assertEquals(info.connections(),copy.view().pipeInfo().connections());
        assertTrue(copy.view().pipeInfo().junction());assertTrue(copy.view().pipeInfo().changedDirection());
        assertArrayEquals(info.contents().componentMoles(),copy.view().pipeInfo().contents().componentMoles());
        assertThrows(IllegalArgumentException.class,()->new FluidView.PipeConnection(1,-1,2,3,false));
        assertThrows(IllegalArgumentException.class,()->new FluidView.PipeConnection(1,1,Double.NaN,3,false));
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.MenuData(TopologyCompiler.Kind.PIPE,base.view(),base.controls(),
            base.components(),base.presets(),"",base.materialNames(),base.molecularWeights()));
    }
    /** The client remembers the last delivered state of the 32 most recent devices, so a reopened menu shows it until its bucket; leaving a world forgets it. */
    @Test void theClientRemembersTheLastDeliveredStateOfRecentDevices() {
        FluidNetwork.forgetDelivered();
        try {
            assertNull(FluidNetwork.lastDelivered(1));
            var first=generatorMenu("");var second=generatorMenu(FluidPresentation.APPLIED);
            FluidNetwork.remember(1,first);assertSame(first,FluidNetwork.lastDelivered(1));
            FluidNetwork.remember(1,second);assertSame(second,FluidNetwork.lastDelivered(1),"the last delivery replaces the earlier one");
            for(long id=2;id<=32;id++)FluidNetwork.remember(id,first);
            assertNotNull(FluidNetwork.lastDelivered(1),"thirty-two devices fit");
            FluidNetwork.lastDelivered(1);FluidNetwork.remember(33,first);
            assertNotNull(FluidNetwork.lastDelivered(1),"the most recently read device stays");assertNull(FluidNetwork.lastDelivered(2),"the least recently used device goes");
            FluidNetwork.forgetDelivered();assertNull(FluidNetwork.lastDelivered(1));
        } finally {FluidNetwork.forgetDelivered();}
    }
    /** The compressor's ratio control (phase-ports WP5): bounded to 1.01-10, read back from a compressor's registration,
     * and required on the wire (a payload without it is refused, not defaulted). */
    @Test void theCompressorRatioIsBoundedReadFromItsRegistrationAndRequiredOnTheWire() {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;
        for(double ratio:new double[]{1.01,3,10})assertEquals(ratio,new FluidNetwork.Controls(298.15,101325,.05,.000045,.05,500000,n,com.wormzjl.createcheme.runtime.fluid.SlurryFeed.NONE,ratio).maximumPressureRatio());
        for(double ratio:new double[]{1.009,10.01,Double.NaN,0})
            assertThrows(IllegalArgumentException.class,()->new FluidNetwork.Controls(298.15,101325,.05,.000045,.05,500000,n,com.wormzjl.createcheme.runtime.fluid.SlurryFeed.NONE,ratio),"ratio "+ratio);
        assertEquals(3.0,new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,n).maximumPressureRatio(),"the placement default of plan Appendix B");
        var device=new PhysicalFluidTopology.Device(7,new PhysicalFluidTopology.Position("minecraft:overworld",0,64,0),TopologyCompiler.Kind.COMPRESSOR,PhysicalFluidTopology.Direction.EAST,
                new com.wormzjl.createcheme.science.fluid.network.PipeResistance.Geometry(1,.05,.000045,0),new com.wormzjl.createcheme.science.fluid.network.FlowControl.Compressor(.05,2.5,1));
        var controls=FluidNetwork.Controls.from(new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(),3));
        assertEquals(.05,controls.volumeFlow());assertEquals(2.5,controls.maximumPressureRatio());
        var gson=new Gson();var object=com.google.gson.JsonParser.parseString(gson.toJson(controls)).getAsJsonObject();
        assertEquals(controls.maximumPressureRatio(),gson.fromJson(object.toString(),FluidNetwork.Controls.class).maximumPressureRatio());
        object.remove("maximumPressureRatio");
        assertThrows(RuntimeException.class,()->gson.fromJson(object.toString(),FluidNetwork.Controls.class),"an absent ratio is refused");
    }
    /** A tank's outlet lines round-trip on the live payload; only a tank lists outlets, at most six, with finite values. */
    @Test void aTanksOutletsRoundTripAndAreBounded() {
        var base=generatorMenu("");
        var outlets=List.of(new FluidView.Outlet(11,com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort.VAPOR,.002,.07,2,0),
                new FluidView.Outlet(12,com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort.BULK,-.5,-27,FluidView.Outlet.NONE,0),
                new FluidView.Outlet(13,com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort.LIQUID,1.25,69.4,1,2931.5));
        var view=base.view().withOutlets(outlets);
        var data=new FluidNetwork.MenuData(TopologyCompiler.Kind.RESERVOIR,view,base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights());
        var copy=FluidNetwork.MenuData.of(data.staticData(0),FluidNetwork.decodeLive(new Gson().toJson(data.liveData())));
        assertEquals(outlets,copy.view().outlets());
        assertThrows(IllegalArgumentException.class,()->new FluidNetwork.MenuData(TopologyCompiler.Kind.GENERATOR,view,base.controls(),base.components(),base.presets(),"",base.materialNames(),base.molecularWeights()));
        assertThrows(IllegalArgumentException.class,()->base.view().withOutlets(java.util.Collections.nCopies(7,outlets.getFirst())));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Outlet(1,com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort.LIQUID,Double.NaN,0,1,0));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Outlet(1,com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort.LIQUID,1,1,3,0));
        assertThrows(IllegalArgumentException.class,()->new FluidView.Outlet(1,com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.PhasePort.LIQUID,1,1,1,-1));
        var json=com.google.gson.JsonParser.parseString(new Gson().toJson(data.liveData())).getAsJsonObject();json.getAsJsonObject("view").remove("outlets");
        assertThrows(RuntimeException.class,()->FluidNetwork.decodeLive(json.toString()),"a live view without its outlet list is refused");
    }
    /** A refused control reaches the handler wrapped by the JSON reader; the player reads the control's own reason, not the wrapper. */
    @Test void aRefusedControlIsRepliedWithItsOwnReason() {
        double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.NETWORK+1];n[com.wormzjl.createcheme.science.material.MaterialTestBasis.NITROGEN]=1;
        var gson=new Gson();var object=com.google.gson.JsonParser.parseString(gson.toJson(new FluidNetwork.Controls(298.15,101325,.05,.000045,.01,500000,n))).getAsJsonObject();object.addProperty("pressure",-3e6);
        var refused=assertThrows(RuntimeException.class,()->gson.fromJson(object.toString(),FluidNetwork.Controls.class));
        assertEquals("Controls are outside the supported range",FluidNetwork.reason(refused));
        // A setting the property package cannot evaluate is replied with the thermo-domain error's own text.
        var domain=assertThrows(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation.class,
                ()->new FluidDeviceSpec(1,298.15,3e6,n).validate(com.wormzjl.createcheme.fluid.support.FluidTestSupport.networkModel(),TopologyCompiler.Kind.GENERATOR));
        assertEquals("Thermo domain: Nitrogen at 3000000 Pa is above its valid range 100..2000000 Pa (package createcheme:tjl20_methane_nitrogen); the state cannot be evaluated",FluidNetwork.reason(domain));
        assertEquals("Stale fluid controls",FluidNetwork.reason(new IllegalStateException("Stale fluid controls")));
        assertEquals("null",FluidNetwork.reason(new RuntimeException()));
    }
}

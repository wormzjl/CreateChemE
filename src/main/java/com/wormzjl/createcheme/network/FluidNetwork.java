package com.wormzjl.createcheme.network;

import com.google.gson.Gson;
import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.world.inventory.FluidDeviceMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import java.util.*;

/** Position/identity/revision-bound controls; clients never send inventory or solver results. */
public final class FluidNetwork {
    private static final Gson JSON=new Gson();
    public static final int MAX_JSON=262144;
    public static final int MAX_EDIT_JSON=4096;
    private FluidNetwork() {}
    public record Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition,SlurryFeed solids) {
        public Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition){this(temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure,composition,SlurryFeed.NONE);}
        public Controls {
            Objects.requireNonNull(solids);composition=composition.clone();
            for(double value:new double[]{temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure})if(!Double.isFinite(value))throw new IllegalArgumentException("All controls must be finite numbers");
            if(temperature<273.16||temperature>600||pressure<100||pressure>2e6||diameter<.001||diameter>1||roughness<0||roughness>=diameter||volumeFlow<0||volumeFlow>10||maximumAddedPressure<=0||maximumAddedPressure>2e6||(composition.length<1||composition.length>com.wormzjl.createcheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS))throw new IllegalArgumentException("Controls are outside the supported range");
            double sum=0;for(double n:composition){if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Composition entries must be nonnegative");sum+=n;}if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("Composition cannot be empty");
        }
        @Override public double[] composition(){return composition.clone();}
        public static Controls from(WorldTopologyLedger.Registration record) {
            var d=record.device();double flow=d.control() instanceof FlowControl.Pump p?p.targetVolumeFlow():.01;
            double head=d.control() instanceof FlowControl.Pump p?p.maximumAddedPressure():500000;
            double pressure=d.control() instanceof FlowControl.PressureValve v?v.targetPressure():record.spec().pressure();
            return new Controls(record.spec().temperature(),pressure,d.geometry().diameter(),d.geometry().roughness(),flow,head,record.spec().composition(),record.spec().solids());
        }
    }
    public record MenuData(TopologyCompiler.Kind kind,FluidView view,Controls controls,List<String> components,List<FluidPresetCatalog.Preset> presets,String message,
            Map<String,com.wormzjl.createcheme.science.material.MaterialName> materialNames) {
        public MenuData(TopologyCompiler.Kind kind,FluidView view,Controls controls,List<String> components,List<FluidPresetCatalog.Preset> presets,String message) {
            this(kind,view,controls,components,presets,message,Map.of());
        }
        public MenuData {
            Objects.requireNonNull(kind);Objects.requireNonNull(view);Objects.requireNonNull(controls);Objects.requireNonNull(message);
            components=new com.wormzjl.createcheme.science.material.MaterialAxis(components).ids();presets=List.copyOf(presets);materialNames=Map.copyOf(materialNames);
            if(message.length()>1024||controls.composition().length!=components.size()||presets.size()>com.wormzjl.createcheme.science.material.MaterialPresets.MAX_PRESETS
                    ||presets.stream().map(FluidPresetCatalog.Preset::id).distinct().count()!=presets.size()||materialNames.size()>components.size()
                    ||!components.containsAll(materialNames.keySet()))throw new IllegalArgumentException("Invalid bounded fluid menu state");
            if(view.pipeHistory().size()>12||view.pipeRoutes().size()>12||view.status().length()>2048)throw new IllegalArgumentException("Fluid view exceeds display bounds");
            if(view.state()!=null)requirePhaseAxis(view.state().phaseMoles(),components.size());
            for(var transfer:view.pipeHistory()){requirePhaseAxis(transfer.forward().phaseMoles(),components.size());requirePhaseAxis(transfer.reverse().phaseMoles(),components.size());}
            for(var p:presets)if(p.moleFractions().length!=components.size())throw new IllegalArgumentException("Fluid preset axis mismatch");
            for(var e:materialNames.entrySet())if(!e.getKey().equals(e.getValue().id()))throw new IllegalArgumentException("Fluid name identity mismatch");
        }
    }
    private static void requirePhaseAxis(double[][] phases,int components) {
        if(phases.length!=3)throw new IllegalArgumentException("Fluid phase count mismatch");
        for(var phase:phases)if(phase.length!=components)throw new IllegalArgumentException("Fluid phase axis mismatch");
    }
    public record EditPayload(int menuId,BlockPos position,long identity,long revision,String json) implements CustomPacketPayload {
        public static final Type<EditPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID,"fluid_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf,EditPayload> STREAM_CODEC=new StreamCodec<>() {
            public EditPayload decode(RegistryFriendlyByteBuf b){return new EditPayload(b.readVarInt(),b.readBlockPos(),b.readLong(),b.readLong(),b.readUtf(MAX_EDIT_JSON));}
            public void encode(RegistryFriendlyByteBuf b,EditPayload p){b.writeVarInt(p.menuId);b.writeBlockPos(p.position);b.writeLong(p.identity);b.writeLong(p.revision);b.writeUtf(p.json,MAX_EDIT_JSON);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record RecoverPayload(int menuId,BlockPos position,long identity,long revision) implements CustomPacketPayload {
        public static final Type<RecoverPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID,"fluid_recover_solids"));
        public static final StreamCodec<RegistryFriendlyByteBuf,RecoverPayload> STREAM_CODEC=new StreamCodec<>() {
            public RecoverPayload decode(RegistryFriendlyByteBuf b){return new RecoverPayload(b.readVarInt(),b.readBlockPos(),b.readLong(),b.readLong());}
            public void encode(RegistryFriendlyByteBuf b,RecoverPayload p){b.writeVarInt(p.menuId);b.writeBlockPos(p.position);b.writeLong(p.identity);b.writeLong(p.revision);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void recoverSolids(FluidDeviceMenu menu,long revision){PacketDistributor.sendToServer(new RecoverPayload(menu.containerId,menu.position(),menu.identity(),revision));}
    private static void recover(RecoverPayload payload,IPayloadContext context){
        if(!(context.player() instanceof ServerPlayer player)||!(player.containerMenu instanceof FluidDeviceMenu menu))return;
        if(menu.containerId!=payload.menuId||menu.identity()!=payload.identity||!menu.position().equals(payload.position)||!menu.stillValid(player)||menu.debug()||!player.mayBuild()||player.isSpectator()||!player.level().mayInteract(player,payload.position)||!menu.admitEdit(player.server.getTickCount()))return;
        FluidWorldAuthority.find(player.server).ifPresent(world->{try{world.recoverFilter(payload.identity,payload.revision,player);sendState(player,menu,"Solid recovery requested.");}catch(RuntimeException rejected){sendState(player,menu,"Not applied: "+rejected.getMessage());}});
    }
    public record StatePayload(int menuId,long identity,String json) implements CustomPacketPayload {
        public static final Type<StatePayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID,"fluid_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,StatePayload> STREAM_CODEC=new StreamCodec<>() {
            public StatePayload decode(RegistryFriendlyByteBuf b){return new StatePayload(b.readVarInt(),b.readLong(),b.readUtf(MAX_JSON));}
            public void encode(RegistryFriendlyByteBuf b,StatePayload p){b.writeVarInt(p.menuId);b.writeLong(p.identity);b.writeUtf(p.json,MAX_JSON);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar("fluid-3").executesOn(HandlerThread.MAIN);
        registrar.playToServer(EditPayload.TYPE,EditPayload.STREAM_CODEC,FluidNetwork::edit);
        registrar.playToServer(RecoverPayload.TYPE,RecoverPayload.STREAM_CODEC,FluidNetwork::recover);
        registrar.playToClient(StatePayload.TYPE,StatePayload.STREAM_CODEC,(payload,context)->{
            if(context.player().containerMenu instanceof FluidDeviceMenu menu&&menu.containerId==payload.menuId&&menu.identity()==payload.identity)menu.acceptData(JSON.fromJson(payload.json,MenuData.class));
        });
    }
    public static void sendState(ServerPlayer player,FluidDeviceMenu menu,String message) {
        FluidWorldAuthority.find(player.server).ifPresent(world->{
            var record=world.registrations().get(menu.identity());if(record==null)return;
            var data=new MenuData(record.device().kind(),world.view(menu.identity()),Controls.from(record),world.components(),world.presets(),message,world.materialNames());
            PacketDistributor.sendToPlayer(player,new StatePayload(menu.containerId,menu.identity(),JSON.toJson(data)));
        });
    }
    public static void sendEdit(FluidDeviceMenu menu,long revision,Controls controls){PacketDistributor.sendToServer(new EditPayload(menu.containerId,menu.position(),menu.identity(),revision,JSON.toJson(controls)));}
    private static void edit(EditPayload payload,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player)||!(player.containerMenu instanceof FluidDeviceMenu menu))return;
        if(menu.containerId!=payload.menuId||menu.identity()!=payload.identity||!menu.position().equals(payload.position)||!menu.stillValid(player)||menu.debug()||!player.mayBuild()||player.isSpectator()||!player.level().mayInteract(player,payload.position)||!menu.admitEdit(player.server.getTickCount()))return;
        FluidWorldAuthority.find(player.server).ifPresent(world->{
            try {
                var controls=Objects.requireNonNull(JSON.fromJson(payload.json,Controls.class));
                if(controls.composition().length!=world.components().size())throw new IllegalArgumentException("Composition differs from the server network axis");
                var old=Objects.requireNonNull(world.registrations().get(payload.identity));var d=old.device();
                var geometry=d.geometry();var control=d.control();var spec=old.spec();
                switch(d.kind()) {
                    case PIPE,FILTER->geometry=new PipeResistance.Geometry(geometry.length(),controls.diameter,controls.roughness,geometry.minorLoss());
                    case PUMP->control=new FlowControl.Pump(controls.volumeFlow,controls.maximumAddedPressure,1);
                    case VALVE->control=new FlowControl.PressureValve(controls.pressure);
                    case GENERATOR->spec=new FluidDeviceSpec(spec.volume(),controls.temperature,controls.pressure,controls.composition,controls.solids);
                    case VOID->spec=new FluidDeviceSpec(spec.volume(),spec.temperature(),controls.pressure,spec.composition());
                    case RESERVOIR->throw new IllegalArgumentException("Reservoir initialization is fixed; existing fluid is conserved.");
                }
                world.edit(d.id(),payload.revision,new PhysicalFluidTopology.Device(d.id(),d.position(),d.kind(),d.facing(),geometry,control),spec);sendState(player,menu,"Settings accepted at the current simulation event.");
            }catch(RuntimeException rejected){sendState(player,menu,"Not applied: "+String.valueOf(rejected.getMessage()));}
        });
    }
}

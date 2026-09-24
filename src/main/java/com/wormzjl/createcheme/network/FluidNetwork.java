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

/**
 * Menu protocol {@value #PROTOCOL}: position/identity/revision-bound controls; clients never send inventory or
 * solver results. The server answers nothing directly. A menu receives a static payload (kind, components,
 * presets, material names) and a live payload (view, controls, the engine's reply) only on its island's
 * presentation bucket ({@link FluidPresentation}); the static payload again only when the device's registration
 * revision changed. An edit or recovery packet is validated as before, queued as a ledger event, and its reply -
 * {@code Queued for simulation event at tick N}, {@code Applied} or {@code Not applied: <reason>} - arrives with
 * the next bucket.
 */
public final class FluidNetwork {
    public static final String PROTOCOL="fluid-5";
    private static final Gson JSON=new Gson();
    public static final int MAX_JSON=262144;
    public static final int MAX_EDIT_JSON=4096;
    private FluidNetwork() {}
    public record Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition,SlurryFeed solids) {
        public Controls(double temperature,double pressure,double diameter,double roughness,double volumeFlow,double maximumAddedPressure,double[] composition){this(temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure,composition,SlurryFeed.NONE);}
        public Controls {
            Objects.requireNonNull(solids);composition=composition.clone();
            for(double value:new double[]{temperature,pressure,diameter,roughness,volumeFlow,maximumAddedPressure})if(!Double.isFinite(value))throw new IllegalArgumentException("All controls must be finite numbers");
            // Temperature and pressure are only required to be physical here: the range the fluid model can evaluate
            // is the property package's and depends on the composition, so the server checks it against the model when
            // the edit arrives and refuses it with the thermo-domain error (see edit below).
            if(!(temperature>0)||!(pressure>0)||diameter<.001||diameter>1||roughness<0||roughness>=diameter||volumeFlow<0||volumeFlow>10||maximumAddedPressure<=0||maximumAddedPressure>2e6||(composition.length<1||composition.length>com.wormzjl.createcheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS))throw new IllegalArgumentException("Controls are outside the supported range");
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
    /**
     * What a menu shows: a static part and a live part as one validated record. The server builds it at a bucket
     * and splits it for the wire; the client joins the two parts it received and validates the whole again.
     */
    public record MenuData(TopologyCompiler.Kind kind,FluidView view,Controls controls,List<String> components,List<FluidPresetCatalog.Preset> presets,String message,
            Map<String,com.wormzjl.createcheme.science.material.MaterialName> materialNames,List<Double> molecularWeights) {
        public MenuData {
            Objects.requireNonNull(kind);Objects.requireNonNull(view);Objects.requireNonNull(controls);Objects.requireNonNull(message);
            components=new com.wormzjl.createcheme.science.material.MaterialAxis(components).ids();presets=List.copyOf(presets);materialNames=Map.copyOf(materialNames);molecularWeights=validatedWeights(molecularWeights,components.size());
            if(message.length()>1024||controls.composition().length!=components.size()||presets.size()>com.wormzjl.createcheme.science.material.MaterialPresets.MAX_PRESETS
                    ||presets.stream().map(FluidPresetCatalog.Preset::id).distinct().count()!=presets.size()||materialNames.size()>components.size()
                    ||!components.containsAll(materialNames.keySet()))throw new IllegalArgumentException("Invalid bounded fluid menu state");
            if(view.pipeHistory().size()>12||view.pipeRoutes().size()>12||view.status().length()>2048)throw new IllegalArgumentException("Fluid view exceeds display bounds");
            if(view.state()!=null)requirePhaseAxis(view.state().phaseMoles(),components.size());
            for(var transfer:view.pipeHistory()){requirePhaseAxis(transfer.forward().phaseMoles(),components.size());requirePhaseAxis(transfer.reverse().phaseMoles(),components.size());}
            for(var p:presets)if(p.moleFractions().length!=components.size())throw new IllegalArgumentException("Fluid preset axis mismatch");
            for(var e:materialNames.entrySet())if(!e.getKey().equals(e.getValue().id()))throw new IllegalArgumentException("Fluid name identity mismatch");
        }
        /** Joins what a menu received: its last static payload and the live payload just delivered. */
        public static MenuData of(StaticData fixed,LiveData live){return new MenuData(fixed.kind(),live.view(),live.controls(),fixed.components(),fixed.presets(),live.message(),fixed.materialNames(),fixed.molecularWeights());}
        public StaticData staticData(long revision){return new StaticData(kind,revision,components,presets,materialNames,molecularWeights);}
        public LiveData liveData(){return new LiveData(view,controls,message);}
    }
    /** The static payload: what changes only with the device's registration revision (and never within one server run otherwise). */
    public record StaticData(TopologyCompiler.Kind kind,long revision,List<String> components,List<FluidPresetCatalog.Preset> presets,Map<String,com.wormzjl.createcheme.science.material.MaterialName> materialNames,List<Double> molecularWeights) {
        public StaticData {
            Objects.requireNonNull(kind);components=new com.wormzjl.createcheme.science.material.MaterialAxis(components).ids();presets=List.copyOf(presets);materialNames=Map.copyOf(materialNames);molecularWeights=validatedWeights(molecularWeights,components.size());
            if(revision<0||presets.size()>com.wormzjl.createcheme.science.material.MaterialPresets.MAX_PRESETS||materialNames.size()>components.size()||!components.containsAll(materialNames.keySet()))
                throw new IllegalArgumentException("Invalid bounded fluid static state");
            for(var p:presets)if(p.moleFractions().length!=components.size())throw new IllegalArgumentException("Fluid preset axis mismatch");
        }
    }
    /** The live payload: the bucket's view, the device's current controls, and the engine's reply (empty for none). */
    public record LiveData(FluidView view,Controls controls,String message) {
        public LiveData {
            Objects.requireNonNull(view);Objects.requireNonNull(controls);Objects.requireNonNull(message);
            if(message.length()>1024||view.pipeHistory().size()>12||view.pipeRoutes().size()>12||view.status().length()>2048)throw new IllegalArgumentException("Fluid view exceeds display bounds");
        }
    }
    private static List<Double> validatedWeights(List<Double> values,int count){
        Objects.requireNonNull(values,"Missing molecular weights");var copy=List.copyOf(values);
        if(copy.size()!=count||copy.stream().anyMatch(w->!Double.isFinite(w)||w<=0))
            throw new IllegalArgumentException("Invalid molecular weights");
        return copy;
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
    /** Server to client, at a bucket: the static part of a menu's state, as JSON of {@link StaticData}. */
    public record StaticPayload(int menuId,long identity,String json) implements CustomPacketPayload {
        public static final Type<StaticPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID,"fluid_static"));
        public static final StreamCodec<RegistryFriendlyByteBuf,StaticPayload> STREAM_CODEC=new StreamCodec<>() {
            public StaticPayload decode(RegistryFriendlyByteBuf b){return new StaticPayload(b.readVarInt(),b.readLong(),b.readUtf(MAX_JSON));}
            public void encode(RegistryFriendlyByteBuf b,StaticPayload p){b.writeVarInt(p.menuId);b.writeLong(p.identity);b.writeUtf(p.json,MAX_JSON);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    /** Server to client, at every bucket of an open menu: the live part, as JSON of {@link LiveData}. */
    public record LivePayload(int menuId,long identity,String json) implements CustomPacketPayload {
        public static final Type<LivePayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateChemE.MOD_ID,"fluid_live"));
        public static final StreamCodec<RegistryFriendlyByteBuf,LivePayload> STREAM_CODEC=new StreamCodec<>() {
            public LivePayload decode(RegistryFriendlyByteBuf b){return new LivePayload(b.readVarInt(),b.readLong(),b.readUtf(MAX_JSON));}
            public void encode(RegistryFriendlyByteBuf b,LivePayload p){b.writeVarInt(p.menuId);b.writeLong(p.identity);b.writeUtf(p.json,MAX_JSON);}
        };
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar=event.registrar(PROTOCOL).executesOn(HandlerThread.MAIN);
        registrar.playToServer(EditPayload.TYPE,EditPayload.STREAM_CODEC,FluidNetwork::edit);
        registrar.playToServer(RecoverPayload.TYPE,RecoverPayload.STREAM_CODEC,FluidNetwork::recover);
        registrar.playToClient(StaticPayload.TYPE,StaticPayload.STREAM_CODEC,(payload,context)->{
            if(context.player().containerMenu instanceof FluidDeviceMenu menu&&menu.containerId==payload.menuId&&menu.identity()==payload.identity)menu.acceptStatic(decodeStatic(payload.json));
        });
        registrar.playToClient(LivePayload.TYPE,LivePayload.STREAM_CODEC,(payload,context)->{
            if(context.player().containerMenu instanceof FluidDeviceMenu menu&&menu.containerId==payload.menuId&&menu.identity()==payload.identity)menu.acceptLive(decodeLive(payload.json));
        });
    }
    public static StaticData decodeStatic(String json){return Objects.requireNonNull(JSON.fromJson(json,StaticData.class));}
    public static LiveData decodeLive(String json){return Objects.requireNonNull(JSON.fromJson(json,LiveData.class));}

    // ---- server: delivery at a bucket only ----

    /**
     * Delivers one presentation bucket to an open menu. Called only by the engine's flush ({@link FluidPresentation}):
     * the static payload when the menu has not yet received the device's current registration revision, then the
     * live payload with the view built for this bucket and the reply the engine composed.
     */
    public static void deliver(FluidWorldAuthority world,FluidDeviceMenu menu,WorldTopologyLedger.Registration record,boolean withStatic,FluidView view,String reply) {
        var player=menu.serverPlayer();if(player==null)return;
        var data=new MenuData(record.device().kind(),view,Controls.from(record),world.components(),world.presets(),reply,world.materialNames(),Arrays.stream(world.model().molecularWeights()).boxed().toList());
        if(withStatic) {
            PacketDistributor.sendToPlayer(player,new StaticPayload(menu.containerId,menu.identity(),JSON.toJson(data.staticData(record.revision()))));
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.menuPackets);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.staticPayloads);
        }
        PacketDistributor.sendToPlayer(player,new LivePayload(menu.containerId,menu.identity(),JSON.toJson(data.liveData())));
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.menuPackets);
    }

    // ---- server: inputs are queued, never answered here ----

    public static void recoverSolids(FluidDeviceMenu menu,long revision){PacketDistributor.sendToServer(new RecoverPayload(menu.containerId,menu.position(),menu.identity(),revision));}
    private static void recover(RecoverPayload payload,IPayloadContext context){if(context.player() instanceof ServerPlayer player)recover(payload,player);}
    /** The recovery handler: the same admission as an edit; the recovery is queued as a ledger event and the handler returns without a reply. */
    public static void recover(RecoverPayload payload,ServerPlayer player) {
        if(!(player.containerMenu instanceof FluidDeviceMenu menu))return;
        if(menu.containerId!=payload.menuId||menu.identity()!=payload.identity||!menu.position().equals(payload.position)||!menu.stillValid(player)||menu.debug()||!player.mayBuild()||player.isSpectator()||!player.level().mayInteract(player,payload.position)||!menu.admitEdit(player.server.getTickCount()))return;
        FluidWorldAuthority.find(player.server).ifPresent(world->{
            WorldTopologyLedger.Event event=null;String refusal=null;
            try{event=world.recoverFilter(payload.identity,payload.revision,player);}catch(RuntimeException rejected){refusal=reason(rejected);}
            world.input(menu,event,refusal);
        });
    }
    public static void sendEdit(FluidDeviceMenu menu,long revision,Controls controls){PacketDistributor.sendToServer(new EditPayload(menu.containerId,menu.position(),menu.identity(),revision,JSON.toJson(controls)));}
    private static void edit(EditPayload payload,IPayloadContext context){if(context.player() instanceof ServerPlayer player)edit(payload,player);}
    /**
     * The edit handler: validates the menu, the player and the payload's revision and bounds as before, queues the
     * change as a ledger event, and returns without a reply. The engine composes the reply with the menu's next
     * bucket. An inadmissible packet (wrong menu, position or identity, a probe, a distant, read-only or flooding
     * player) is dropped with no reply at all, as before.
     */
    public static void edit(EditPayload payload,ServerPlayer player) {
        if(!(player.containerMenu instanceof FluidDeviceMenu menu))return;
        if(menu.containerId!=payload.menuId||menu.identity()!=payload.identity||!menu.position().equals(payload.position)||!menu.stillValid(player)||menu.debug()||!player.mayBuild()||player.isSpectator()||!player.level().mayInteract(player,payload.position)||!menu.admitEdit(player.server.getTickCount()))return;
        FluidWorldAuthority.find(player.server).ifPresent(world->{
            WorldTopologyLedger.Event event=null;String refusal=null;
            try {
                var controls=Objects.requireNonNull(JSON.fromJson(payload.json,Controls.class));
                if(controls.composition().length!=world.components().size())throw new IllegalArgumentException("Composition differs from the server network axis");
                var old=Objects.requireNonNull(world.registrations().get(payload.identity));var d=old.device();
                var geometry=d.geometry();var control=d.control();var spec=old.spec();
                switch(d.kind()) {
                    case PIPE,FILTER->geometry=new PipeResistance.Geometry(geometry.length(),controls.diameter,PipeResistance.DEFAULT_ROUGHNESS_METRES,geometry.minorLoss());
                    case PUMP->control=new FlowControl.Pump(controls.volumeFlow,controls.maximumAddedPressure,1);
                    case VALVE->control=new FlowControl.PressureValve(controls.pressure);
                    case GENERATOR->spec=new FluidDeviceSpec(spec.volume(),controls.temperature,controls.pressure,controls.composition,controls.solids);
                    case VOID->spec=new FluidDeviceSpec(spec.volume(),spec.temperature(),controls.pressure,spec.composition());
                    case RESERVOIR->throw new IllegalArgumentException("Reservoir initialization is fixed; existing fluid is conserved.");
                }
                // Refused here, before anything is queued, with the dedicated thermo-domain error: the reply with the
                // next bucket reads "Not applied: Thermo domain: ...".
                switch(d.kind()) {
                    case GENERATOR,VOID->spec.validate(world.model(),d.kind());
                    case VALVE->world.model().domain().checkPressure(controls.pressure);
                    default->{}
                }
                event=world.edit(d.id(),payload.revision,new PhysicalFluidTopology.Device(d.id(),d.position(),d.kind(),d.facing(),geometry,control),spec);
            }catch(RuntimeException rejected){refusal=reason(rejected);}
            world.input(menu,event,refusal);
        });
    }

    /**
     * The player-facing reason of a refused input: the innermost refusal message. A control refused by its own
     * validation reaches the handler wrapped by the JSON reader, whose message names the constructor and its
     * arguments; the player reads the control's reason.
     */
    public static String reason(Throwable refused) {
        String message=null;
        for(Throwable t=refused;t!=null;t=t.getCause()==t?null:t.getCause())if(t.getMessage()!=null&&(t instanceof IllegalArgumentException||t instanceof IllegalStateException))message=t.getMessage();
        return message!=null?message:String.valueOf(refused.getMessage());
    }

    // ---- client: the last delivered view of each device ----

    private static final int REMEMBERED_VIEWS=32;
    private static final Map<Long,MenuData> DELIVERED=new LinkedHashMap<>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long,MenuData> eldest){return size()>REMEMBERED_VIEWS;}
    };
    /** Client: remembers the last state delivered for a device, so a menu reopened on it shows that view until its next bucket. */
    public static synchronized void remember(long identity,MenuData data){DELIVERED.put(identity,Objects.requireNonNull(data));}
    /** Client: the last state delivered for a device in this session, or null. Opening a menu never asks the server. */
    public static synchronized MenuData lastDelivered(long identity){return DELIVERED.get(identity);}
    /** Client: forgets every delivered state (leaving a world; identities belong to a world). */
    public static synchronized void forgetDelivered(){DELIVERED.clear();}
}

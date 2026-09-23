package com.wormzjl.createcheme.fluid.gametest;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.network.ProcessSolveCoordinator;
import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.ApproximationAnchor;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.*;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidPropertyReloadGameTests {
    private FluidPropertyReloadGameTests() {}
    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-property-reload")
    public static void betweenTickRoutingHoldsChangedScienceAndRestoringItResumesConservedState(GameTestHelper helper) {
        var level=helper.getLevel();var server=level.getServer();var world=FluidWorldAuthority.find(server).orElseThrow();
        var original=MaterialRuntime.active();var resources=new HashMap<>(original.resources());
        String path="data/createcheme/materials/properties/crude_pc07.json";
        var property=JsonParser.parseString(resources.get(path)).getAsJsonObject();property.addProperty("molecular_weight_kg_per_mol",.31);resources.put(path,property.toString());
        var changed=MaterialCatalog.parse(resources);
        var pos=helper.absolutePos(new BlockPos(0,1,0));level.setBlock(pos,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
        long id=((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity();
        IslandCoordinator.Snapshot[] before={null};
        java.util.function.Supplier<IslandCoordinator.Snapshot> snapshot=()->world.capture().checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot)
                .filter(s->s.graph().reservoirs().stream().anyMatch(n->n.id()==id)).findFirst().orElseThrow();
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(snapshot.get().lastResult().isPresent()&&snapshot.get().anchor().isPresent(),"Waiting for initial FULL interval"))
        .thenExecute(()->{
            before[0]=snapshot.get();
            try {
                MaterialRuntime.publish(changed);
                // Exercise the real between-tick callback entry, before the next normal world tick.
                ProcessSolveCoordinator.drainCompletedCalculations(server);
                var held=snapshot.get();helper.assertTrue(held.status().startsWith("HELD: property data"),"Old properties were not held before completion routing");
                helper.assertTrue(held.clock().committedTick()==before[0].clock().committedTick(),"Reload advanced old scientific state");
                helper.assertTrue(world.view(id).status().contains("property data changed"),"Loaded view hides the property-data hold");
                var capture=world.capture();
                var model=FluidThermodynamics.forNetwork(original,FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
                var encoded=FluidCheckpointCodec.encode(capture.checkpoint(),capture.world().onlineTick(),key->model);
                var roundTrip=FluidCheckpointCodec.decode(encoded,key->model);
                helper.assertTrue(roundTrip.islands().getFirst().snapshot().allowance().equals(held.allowance()),"Save renewed degraded grace");
                boolean refused=false;try{FluidCheckpointCodec.decode(encoded,key->FluidThermodynamics.forNetwork(changed,key.packageId(),key.compressibility()));}
                catch(IllegalArgumentException expected){refused=true;}
                helper.assertTrue(refused,"Restart could silently reinterpret saved energy under changed science");
                helper.assertTrue(!held.anchor().orElseThrow().propertyRevision().equals(ApproximationAnchor.revision(model)),"Reload retained an eligible fallback anchor");
            }catch(RuntimeException|AssertionError failure){MaterialRuntime.publish(original);FluidWorldAuthority.refreshProperties(server);helper.fail("Property reload scenario failed: "+failure);}
        }).thenIdle(10).thenExecute(()->{
            try {
                var held=snapshot.get();
                helper.assertTrue(held.clock().committedTick()==before[0].clock().committedTick(),"Held world advanced");
                helper.assertTrue(held.clock().onlineTick()>before[0].clock().onlineTick(),"Held world discarded online debt");
                helper.assertTrue(held.graph().equals(before[0].graph()),"Property reload changed canonical inventory or energy");
            }finally{MaterialRuntime.publish(original);FluidWorldAuthority.refreshProperties(server);}
        }).thenWaitUntil(()->helper.assertTrue(world.view(id).committedTick()>before[0].clock().committedTick(),"Waiting for FULL recovery with restored qualified science"))
        .thenExecute(()->{
            helper.assertTrue(snapshot.get().graph().reservoirs().getFirst().inventory().equals(before[0].graph().reservoirs().getFirst().inventory()),"Recovery recreated or lost isolated nitrogen");
            level.removeBlock(pos,false);
        }).thenWaitUntil(()->helper.assertTrue(!world.capture().world().active().containsKey(id),"Waiting for topology cleanup")).thenSucceed();
    }

    /**
     * Plan section 3.6 with a certificate: a lone tank certifies REST; a property hold materialises it to the hold
     * tick and freezes it there while online time accrues; resume discards the certificate, and the island solves
     * its debt from the held state and certifies again only after restConfirmIntervals fresh intervals.
     */
    @GameTest(template="empty",timeoutTicks=10000,batch="fluid-property-reload")
    public static void aCertifiedIslandIsFrozenByAHoldAndRequalifiesAfterResume(GameTestHelper helper) {
        var level=helper.getLevel();var server=level.getServer();var world=FluidWorldAuthority.find(server).orElseThrow();
        var original=MaterialRuntime.active();var resources=new HashMap<>(original.resources());
        String path="data/createcheme/materials/properties/crude_pc07.json";
        var property=JsonParser.parseString(resources.get(path)).getAsJsonObject();property.addProperty("molecular_weight_kg_per_mol",.31);resources.put(path,property.toString());
        var changed=MaterialCatalog.parse(resources);
        var pos=helper.absolutePos(new BlockPos(0,1,0));level.setBlock(pos,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
        long id=((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity();
        // Stored, not materialised: what the island holds without a read advancing it.
        java.util.function.Supplier<IslandCoordinator.Snapshot> stored=()->world.diagnosticSnapshots().stream()
                .filter(s->s.graph().reservoirs().stream().anyMatch(n->n.id()==id)).findFirst().orElseThrow();
        java.util.function.Supplier<IslandCoordinator.Snapshot> current=()->world.capture().checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot)
                .filter(s->s.graph().reservoirs().stream().anyMatch(n->n.id()==id)).findFirst().orElseThrow();
        long[] hold={-1};IslandCoordinator.Snapshot[] held={null};
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(stored.get().certificate().isPresent(),"Waiting for the lone tank to certify"))
        .thenExecute(()->{
            var certified=stored.get();
            helper.assertTrue(certified.certificate().orElseThrow().kind()==IslandCertificate.Kind.REST,"A lone tank rests exactly: "+certified.certificate());
            helper.assertTrue(certified.status().startsWith("RESTING: no flow since"),"Status while certified: "+certified.status());
            try {
                MaterialRuntime.publish(changed);ProcessSolveCoordinator.drainCompletedCalculations(server);
                var s=stored.get();hold[0]=s.clock().onlineTick();held[0]=s;
                helper.assertTrue(s.status().startsWith("HELD: property data"),"Not held: "+s.status());
                helper.assertTrue(s.clock().committedTick()==hold[0],"The hold must materialise the certified island to the hold tick: "+s.clock());
            }catch(RuntimeException|AssertionError failure){MaterialRuntime.publish(original);FluidWorldAuthority.refreshProperties(server);helper.fail("Certified hold scenario failed: "+failure);}
        }).thenIdle(40).thenExecute(()->{
            try {
                var s=current.get();
                helper.assertTrue(s.clock().committedTick()==hold[0],"A read during the hold advanced committed time: "+s.clock());
                helper.assertTrue(s.clock().onlineTick()>=hold[0]+40,"The hold discarded online debt: "+s.clock());
                helper.assertTrue(s.graph().equals(held[0].graph()),"The hold changed inventory");
            }finally{MaterialRuntime.publish(original);FluidWorldAuthority.refreshProperties(server);}
            var resumed=stored.get();
            helper.assertTrue(resumed.certificate().isEmpty(),"Resume must discard the certificate");
            helper.assertTrue(resumed.clock().committedTick()==hold[0],"Resume starts from the held state");
        }).thenWaitUntil(()->helper.assertTrue(stored.get().certificate().isPresent(),"Waiting to requalify after resume"))
        .thenExecute(()->{
            var again=stored.get();var certificate=again.certificate().orElseThrow();
            int confirm=world.certificates().confirmIntervals();
            helper.assertTrue(certificate.sinceTick()==certificate.baseTick()&&certificate.baseTick()>=hold[0]+(long)confirm*again.clock().cadenceTicks(),
                    "Requalified before "+confirm+" fresh intervals: base "+certificate.baseTick()+", hold "+hold[0]+", cadence "+again.clock().cadenceTicks());
            helper.assertTrue(again.graph().reservoirs().getFirst().inventory().equals(held[0].graph().reservoirs().getFirst().inventory()),"Recovery changed the resting inventory");
            level.removeBlock(pos,false);
        }).thenWaitUntil(()->helper.assertTrue(!world.capture().world().active().containsKey(id),"Waiting for topology cleanup")).thenSucceed();
    }
}

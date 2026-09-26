package com.wormzjl.createcheme.fluid.gametest;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.wormzjl.createcheme.network.FluidNetwork;
import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.world.inventory.FluidDeviceMenu;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.*;

/**
 * Engine-owned presentation in the real server: opening replays the last published snapshot when available,
 * and new state arrives on the island bucket; a queued edit is answered on the following bucket, tick
 * for tick; a certified island's status line arrives the same way; and identity binding on a chunk load carries
 * no view. The observations are the engine's own delivery log for each menu and the block entity's view.
 */
@GameTestHolder("createcheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class FluidPresentationGameTests {
    private FluidPresentationGameTests() {}
    private static ServerPlayer player(GameTestHelper helper,String name,BlockPos near) {
        var player=FakePlayerFactory.get(helper.getLevel(),new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),name));
        player.setGameMode(GameType.CREATIVE);player.setPos(near.getX()+1.5,near.getY(),near.getZ()+.5);return player;
    }
    private static FluidDeviceMenu open(ServerPlayer player,BlockPos pos,long identity,int id){var menu=new FluidDeviceMenu(id,player.getInventory(),pos,identity,false);player.containerMenu=menu;return menu;}
    private static void close(ServerPlayer player,FluidDeviceMenu menu){menu.removed(player);player.containerMenu=player.inventoryMenu;}
    private static long identity(GameTestHelper helper,BlockPos pos){return ((FluidDeviceBlockEntity)helper.getLevel().getBlockEntity(pos)).fluidIdentity();}
    /** Waits until the removal events of a test's devices have applied, so no accounting from this test lands in another. */
    private static void assertCleaned(GameTestHelper helper,FluidWorldAuthority world,long... ids) {
        var active=world.capture().world().active();for(long id:ids)helper.assertTrue(!active.containsKey(id),"Waiting for topology cleanup");
    }
    /** The first bucket tick strictly after {@code tick} for a bucket key. */
    private static long nextBucket(long tick,long key){return tick+1+Math.floorMod(key-(tick+1),(long)FluidPresentation.BUCKET_TICKS);}

    /**
     * A never-published device waits for its first bucket. A second opening between buckets replays
     * that cached snapshot immediately, then receives live-only updates at the unchanged deadlines.
     */
    @GameTest(template="empty",timeoutTicks=2000,batch="fluid-presentation-open")
    public static void aMenuOpeningReplaysCachedDataThenFollowsItsIslandsBucket(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var pos=helper.absolutePos(new BlockPos(0,1,0));level.setBlock(pos,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);long id=identity(helper,pos);
        var first=player(helper,"PresentationOpenA",pos);var second=player(helper,"PresentationOpenB",pos);
        var a=open(first,pos,id,61);long openedA=world.onlineTick();
        helper.assertTrue(!world.replayOnOpen(a),"A new device has no published snapshot yet");
        helper.assertTrue(world.deliveries(a).isEmpty(),"New state arrived before its first bucket");
        FluidDeviceMenu[] b={null};long[] at={-1,-1};String[] failure={null};
        helper.onEachTick(()->{
            if(failure[0]!=null)helper.fail(failure[0]);
            long now=world.onlineTick();long key=world.presentationKey(id);
            if(at[0]<0) {
                var delivered=world.deliveries(a);if(delivered.isEmpty())return;
                var d=delivered.getFirst();at[0]=d.tick();
                if(d.tick()!=nextBucket(openedA,key)||!d.withStatic()||d.viewOnlineTick()!=d.tick())failure[0]="First delivery "+d+" is not the static and live payload of the first bucket after "+openedA+" for key "+key;
                return;
            }
            if(b[0]==null) {
                if(now<at[0]+30)return;
                close(first,a);helper.assertTrue(world.deliveries(a).isEmpty(),"A closed menu stays subscribed");
                b[0]=open(second,pos,id,62);at[1]=now;
                if(!world.replayOnOpen(b[0]))failure[0]="The second menu did not replay its published snapshot";
                if(!world.deliveries(b[0]).isEmpty())failure[0]="Cached replay was logged as new scheduled state";
                return;
            }
            var delivered=world.deliveries(b[0]);long bucket=nextBucket(at[1],key);
            if(now<bucket&&!delivered.isEmpty())failure[0]="Delivered at "+delivered.getFirst().tick()+" before the bucket "+bucket;
            if(now>=bucket&&!delivered.isEmpty()) {
                var d=delivered.getFirst();
                if(d.tick()!=bucket||d.withStatic()||d.viewOnlineTick()!=bucket)failure[0]="The second menu's first delivery "+d+" is not the bucket "+bucket;
            }
        });
        boolean[] removed={false};
        helper.succeedWhen(()->{
            if(!removed[0]) {
                helper.assertTrue(b[0]!=null&&world.deliveries(b[0]).size()>=2,"Waiting for the second menu's second bucket");
                var delivered=world.deliveries(b[0]);
                helper.assertTrue(delivered.get(1).tick()==delivered.get(0).tick()+FluidPresentation.BUCKET_TICKS&&!delivered.get(1).withStatic(),"The second bucket is not a live-only delivery 100 ticks later: "+delivered);
                close(second,b[0]);level.removeBlock(pos,false);removed[0]=true;
            }
            assertCleaned(helper,world,id);
        });
    }

    /**
     * Plan section 3.4: an edit is validated and queued by its handler, which answers nothing. With the island's
     * bucket on the tick after the edit, the reply there is exactly {@code Queued for simulation event at tick e};
     * the next delivery, at the replacement island's first bucket after the event applied, says {@code Applied}; a
     * stale edit's refusal arrives with the first bucket after it. Every check is tick-precise.
     */
    @GameTest(template="empty",timeoutTicks=6000,batch="fluid-presentation-edit")
    public static void aQueuedEditIsAcknowledgedOnTheFollowingBucket(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var tank=helper.absolutePos(new BlockPos(0,1,0));var pipe=tank.east();var source=pipe.east();
        level.setBlock(tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);level.setBlock(pipe,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);
        level.setBlock(source,ModBlocks.FLUID_GENERATOR.get().defaultBlockState(),3);long generator=identity(helper,source);
        // Water at 2.5 bar into the nitrogen tank: the island keeps filling (awake) for the whole test.
        var initial=world.registrations().get(generator);var spec=initial.spec();
        world.edit(generator,initial.revision(),initial.device(),new FluidDeviceSpec(spec.volume(),spec.temperature(),250000,spec.composition()));
        var player=player(helper,"PresentationEdit",source);var menu=open(player,source,generator,71);
        var gson=new Gson();
        // 0: wait for a quiet, awake island and its first delivery; 1: queued edit sent; 2: queued reply seen; 3: applied seen; 4: stale edit sent; 5: done.
        int[] phase={0};long[] sent={-1},queuedKey={-1},appliedAt={-1};int[] seen={0};String[] failure={null};
        helper.onEachTick(()->{
            if(failure[0]!=null)helper.fail(failure[0]);
            long now=world.onlineTick();long key=world.presentationKey(generator);var delivered=world.deliveries(menu);
            switch(phase[0]) {
                case 0->{
                    if(delivered.isEmpty())return;
                    // The island must be awake and its slices must not end at the edit's tick, so nothing can
                    // align it before the bucket: a fresh island commits at multiples of the cadence past its base.
                    var island=world.diagnosticSnapshots().stream().filter(s->s.id()==key).findFirst().orElse(null);
                    if(island==null||island.certificate().isPresent()||!island.fences().isEmpty())return;
                    long base=island.clock().committedTick()%island.clock().cadenceTicks();
                    if(nextBucket(now,key)!=now+1||Math.floorMod(now-base,(long)island.clock().cadenceTicks())==0)return;
                    var record=world.registrations().get(generator);var c=FluidNetwork.Controls.from(record);
                    String json=gson.toJson(new FluidNetwork.Controls(c.temperature(),200000,c.diameter(),c.roughness(),c.volumeFlow(),c.maximumAddedPressure(),c.composition()));
                    seen[0]=delivered.size();sent[0]=now;queuedKey[0]=key;
                    FluidNetwork.edit(new FluidNetwork.EditPayload(71,source,generator,record.revision(),json),player);
                    if(world.deliveries(menu).size()!=seen[0])failure[0]="The edit handler delivered a reply";
                    phase[0]=1;
                }
                case 1->{
                    if(delivered.size()==seen[0]){failure[0]="No delivery on the bucket after the edit at "+sent[0];return;}
                    var d=delivered.get(seen[0]);
                    if(d.tick()!=sent[0]+1||!d.reply().equals(FluidPresentation.QUEUED+sent[0]))failure[0]="Expected the queued reply at "+(sent[0]+1)+": "+d;
                    seen[0]++;phase[0]=2;
                }
                case 2->{
                    // The event applies inside a tick's hook, which then replaces the island: the menu follows the device
                    // to the replacement's bucket, and its first delivery from there says Applied.
                    if(key!=queuedKey[0]&&appliedAt[0]<0)appliedAt[0]=now;
                    if(delivered.size()==seen[0])return;
                    var d=delivered.get(seen[0]);seen[0]++;
                    if(appliedAt[0]<0){if(!d.reply().isEmpty())failure[0]="A reply before the event applied: "+d;return;}
                    long expected=nextBucket(appliedAt[0]-1,key);
                    if(d.tick()!=expected||!d.reply().equals(FluidPresentation.APPLIED))failure[0]="Expected Applied at "+expected+", the replacement island's (key "+key+") first bucket after "+appliedAt[0]+": "+d;
                    if(world.registrations().get(generator).spec().pressure()!=200000)failure[0]="The edit did not apply";
                    phase[0]=3;
                }
                case 3->{
                    if(now<sent[0]+250)return;
                    var record=world.registrations().get(generator);var c=FluidNetwork.Controls.from(record);
                    String json=gson.toJson(new FluidNetwork.Controls(c.temperature(),180000,c.diameter(),c.roughness(),c.volumeFlow(),c.maximumAddedPressure(),c.composition()));
                    seen[0]=delivered.size();sent[0]=now;
                    FluidNetwork.edit(new FluidNetwork.EditPayload(71,source,generator,record.revision()-1,json),player);
                    if(world.deliveries(menu).size()!=seen[0])failure[0]="The stale edit's handler delivered a reply";
                    phase[0]=4;
                }
                case 4->{
                    long bucket=nextBucket(sent[0],key);
                    if(now<bucket){if(delivered.size()!=seen[0])failure[0]="Delivered before the bucket "+bucket;return;}
                    var d=delivered.get(seen[0]);
                    if(d.tick()!=bucket||!d.reply().equals(FluidPresentation.REFUSED+"Stale fluid controls"))failure[0]="Expected the refusal at "+bucket+": "+d;
                    if(world.registrations().get(generator).spec().pressure()!=200000)failure[0]="The stale edit changed the controls";
                    phase[0]=5;
                }
                default->{}
            }
        });
        long pipeId=identity(helper,pipe),tankId=identity(helper,tank);
        helper.succeedWhen(()->{
            if(phase[0]!=6) {
                helper.assertTrue(phase[0]==5,"Waiting for the edit replies, phase "+phase[0]);
                close(player,menu);for(var p:List.of(source,pipe,tank))level.removeBlock(p,false);phase[0]=6;
            }
            assertCleaned(helper,world,generator,pipeId,tankId);
        });
    }

    /** The certificate's no-flow and replaying status lines reach an open menu with its island's bucket once the island certifies. */
    @GameTest(template="empty",timeoutTicks=12000,batch="fluid-presentation-status")
    public static void aCertifiedIslandsStatusLineArrivesWithItsBucket(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var tank=helper.absolutePos(new BlockPos(0,1,0));
        level.setBlock(tank,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);long tankId=identity(helper,tank);
        // A nitrogen line from 1.5 bar to the atmosphere: steady through-flow.
        var source=helper.absolutePos(new BlockPos(0,1,2));var line=new ArrayList<BlockPos>(List.of(source));
        level.setBlock(source,ModBlocks.FLUID_GENERATOR.get().defaultBlockState(),3);
        for(int x=1;x<=3;x++){var p=source.east(x);level.setBlock(p,ModBlocks.FLUID_PIPE.get().defaultBlockState(),3);line.add(p);}
        var sink=source.east(4);level.setBlock(sink,ModBlocks.FLUID_VOID.get().defaultBlockState(),3);line.add(sink);
        long generator=identity(helper,source);var record=world.registrations().get(generator);var nitrogen=FluidDeviceSpec.nitrogen(com.wormzjl.createcheme.science.material.MaterialRuntime.active());
        world.edit(generator,record.revision(),record.device(),new FluidDeviceSpec(1,298.15,150000,nitrogen.composition()));
        var restPlayer=player(helper,"PresentationRest",tank);var steadyPlayer=player(helper,"PresentationSteady",source);
        var rest=open(restPlayer,tank,tankId,81);var steady=open(steadyPlayer,source,generator,82);
        String[] failure={null};
        helper.onEachTick(()->{
            if(failure[0]!=null)helper.fail(failure[0]);
            for(var menu:List.of(rest,steady)){long key=world.presentationKey(menu.identity());
                for(var d:world.deliveries(menu))if(d.tick()%FluidPresentation.BUCKET_TICKS!=key%FluidPresentation.BUCKET_TICKS&&d.tick()>world.onlineTick()-FluidPresentation.BUCKET_TICKS)failure[0]="Delivery off its bucket: "+d+" key "+key;}
        });
        boolean[] removed={false};var lineIds=line.stream().mapToLong(p->identity(helper,p)).toArray();
        helper.succeedWhen(()->{
            if(removed[0]){assertCleaned(helper,world,tankId);assertCleaned(helper,world,lineIds);return;}
            var resting=world.deliveries(rest).stream().filter(d->d.status().startsWith("STEADY: no flow since")).findFirst();
            var replaying=world.deliveries(steady).stream().filter(d->d.status().startsWith("STEADY: replaying")&&d.status().contains("kg/s since")&&d.status().contains(", next check at")).findFirst();
            helper.assertTrue(resting.isPresent(),"Waiting for no flow on the lone tank's menu: "+world.deliveries(rest).stream().map(FluidPresentation.Delivery::status).reduce((x,y)->y).orElse("none"));
            helper.assertTrue(replaying.isPresent(),"Waiting for STEADY on the line's menu: "+world.deliveries(steady).stream().map(FluidPresentation.Delivery::status).reduce((x,y)->y).orElse("none"));
            // A certified island's view is materialised to its bucket: no lag.
            helper.assertTrue(resting.get().viewCommittedTick()==resting.get().tick(),"The resting view lags: "+resting.get());
            close(restPlayer,rest);close(steadyPlayer,steady);level.removeBlock(tank,false);for(var p:line)level.removeBlock(p,false);removed[0]=true;
            helper.assertTrue(false,"Waiting for topology cleanup");
        });
    }

    /**
     * Identity binding on a chunk load is not an update: the loaded block entity carries its identity and nothing
     * else, no view is built or handed over at the load, and the view arrives with the island's next bucket.
     */
    @GameTest(template="empty",timeoutTicks=3000,batch="fluid-presentation-load")
    public static void identityBindingOnAChunkLoadCarriesNoViewData(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var origin=helper.absolutePos(BlockPos.ZERO);var pos=new BlockPos(((origin.getX()>>4)+112)*16+8,80,(origin.getZ()>>4)*16+8);
        var chunk=new net.minecraft.world.level.ChunkPos(pos);level.setChunkForced(chunk.x,chunk.z,true);level.setBlock(pos,ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(),3);
        long id=((FluidDeviceBlockEntity)level.getBlockEntity(pos)).fluidIdentity();level.setChunkForced(chunk.x,chunk.z,false);
        long[] loadedAt={-1};String[] failure={null};boolean[] removed={false};
        helper.onEachTick(()->{
            if(failure[0]!=null)helper.fail(failure[0]);
            if(removed[0])return;
            if(loadedAt[0]<0) {
                if(level.hasChunkAt(pos))return;
                // Reload with a ticket: the block entity loads with its saved identity and only marks itself.
                level.setChunkForced(chunk.x,chunk.z,true);level.getChunk(pos);loadedAt[0]=world.onlineTick();
                var entity=(FluidDeviceBlockEntity)level.getBlockEntity(pos);
                if(entity.fluidIdentity()!=id)failure[0]="The reload changed the identity";
                if(entity.lastView()!=null)failure[0]="A view was present at the load";
                var tag=entity.getUpdateTag(level.registryAccess());
                if(!tag.getAllKeys().equals(Set.of("FluidIdentity")))failure[0]="The binding update carries more than the identity: "+tag;
                if(!world.viewDirty(id))failure[0]="The load did not mark the device for its bucket";
                return;
            }
            var entity=(FluidDeviceBlockEntity)level.getBlockEntity(pos);long bucket=nextBucket(loadedAt[0],world.presentationKey(id));
            if(world.onlineTick()<bucket&&entity.lastView()!=null)failure[0]="A view arrived at "+entity.lastView().onlineTick()+" before the bucket "+bucket;
            if(world.onlineTick()>=bucket&&(entity.lastView()==null||entity.lastView().onlineTick()!=bucket))failure[0]="No view at the bucket "+bucket+": "+entity.lastView();
        });
        helper.succeedWhen(()->{
            if(!removed[0]) {
                helper.assertTrue(loadedAt[0]>=0,"Waiting for the chunk to unload and reload");
                var entity=(FluidDeviceBlockEntity)level.getBlockEntity(pos);
                helper.assertTrue(entity.lastView()!=null,"Waiting for the bucket");
                level.removeBlock(pos,false);removed[0]=true;
            }
            assertCleaned(helper,world,id);level.setChunkForced(chunk.x,chunk.z,false);
        });
    }
}

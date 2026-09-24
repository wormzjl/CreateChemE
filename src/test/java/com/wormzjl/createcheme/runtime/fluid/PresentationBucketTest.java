package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Engine-owned presentation (plan R2, section 3.4, section 5 item 2) on the synchronous rig: the coordinator with
 * real solves, a {@link FluidPresentation} flushed at the end of every tick as the world does, and a host whose
 * menus and block entities record what they receive. Loaded and unloaded devices; zero, one and many open menus;
 * steady and transient islands; about one packet per 100 ticks per consumer; replies to queued inputs on the
 * following bucket and never before; and scientific progress identical with and without viewers.
 */
class PresentationBucketTest {
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final BoundedCpuSolveService.CancellationToken NEVER=new BoundedCpuSolveService.CancellationToken() {
        public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}
        public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}
    };
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final int components=model.components().size();
    private final int water=components-1,nitrogen=MaterialTestBasis.NITROGEN;

    @AfterEach void counters(){FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}
    private static long counter(String name){return FluidRuntimeDiagnostics.sample().get(name);}
    private static void countFromHere(){FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;}

    // ---------------- fixtures (as in IslandCertificateTest) ----------------

    private double[] pure(int component){var n=new double[components];n[component]=1;return n;}
    private static PhysicalFluidTopology.Device device(long id,int x,Kind kind) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,0,0),kind,PhysicalFluidTopology.Direction.EAST,BLOCK,new FlowControl.Passive());
    }
    private PassiveNetwork line(Map<Long,FluidDeviceSpec> specs,Kind... kinds) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();for(int i=0;i<kinds.length;i++)devices.add(device(i+1,i,kinds[i]));
        var stock=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:devices){var spec=specs.get(d.id());if(spec!=null)stock.put(d.id(),spec.initialize(d,model,()->{}));}
        var connected=PhysicalFluidTopology.compile(devices,stock).islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size());return connected.getFirst().graph();
    }
    /** Cannot flow at all: certifies REST. */
    private PassiveNetwork deadHeadedLine(){return line(Map.of(1L,new FluidDeviceSpec(1,298.15,101325,pure(water)),5L,new FluidDeviceSpec(1,298.15,200000,pure(nitrogen))),Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR);}
    /** Two tanks a kilopascal apart: settle and certify STEADY. */
    private PassiveNetwork closedPair(){return line(Map.of(1L,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),5L,new FluidDeviceSpec(1,298.15,149000,pure(nitrogen))),Kind.RESERVOIR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR);}
    /** Steady through-flow from a generator to a void: certifies STEADY within a few intervals. */
    private PassiveNetwork generatorToVoid(){return line(Map.of(1L,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),6L,new FluidDeviceSpec(1,298.15,101325,pure(nitrogen))),Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.VOID);}
    /** A generator slowly filling a large tank: transient, never certifies. */
    private PassiveNetwork slowFill(){return line(Map.of(1L,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),6L,new FluidDeviceSpec(1000,298.15,101325,pure(nitrogen))),Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR);}

    // ---------------- the rig and the world around it ----------------

    /** The coordinator on a shared epoch, solving each admitted slice at once, as in IslandCertificateTest. */
    private final class Rig {
        final long[] epoch={0};
        final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        final List<IslandCoordinator.Snapshot> published=new ArrayList<>(),replayed=new ArrayList<>();
        Consumer<List<IslandCoordinator.Snapshot>> onPublished=changed->{};
        final IslandCoordinator coordinator;
        long requests;int solves;
        Rig(CertificatePolicy policy) {
            coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 64-attempts.size();}
                public long nextRequestId(){return ++requests;}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
                public void cancel(long request){throw new AssertionError("no deadlines here");}
            },changed->{published.addAll(changed);onPublished.accept(changed);},()->0L,new IslandCoordinator.Settings(30_000_000_000L,20_000_000_000L,64,false,100,policy),IslandCoordinator.CommitHook.NO_MATERIAL,()->epoch[0]);
            coordinator.onReplayed(replayed::addAll);
        }
        void register(long id,PassiveNetwork graph) {
            coordinator.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
        }
        void drain() {
            while(!attempts.isEmpty()) {
                for(long request:List.copyOf(attempts.keySet())) {
                    var attempt=attempts.remove(request);var command=commands.remove(request);solves++;
                    coordinator.completed(attempt,Optional.of((ProcessSolveServices.FluidIslandSolveResult)command.solve(NEVER)));
                }
                coordinator.pump();
            }
        }
        void tick(){epoch[0]++;coordinator.tick();drain();}
    }
    /** An open menu: what it received, bucket by bucket. */
    private static final class Menu {
        final long device;boolean open=true;final List<Received> received=new ArrayList<>();
        Menu(long device){this.device=device;}
        List<String> replies(){return received.stream().map(Received::reply).filter(r->!r.isEmpty()).toList();}
    }
    private record Received(long tick,boolean withStatic,String reply,FluidView view) {}
    /** The world side: device ownership, loaded block entities, registration revisions, queued events, views. */
    private final class World implements FluidPresentation.Host<Menu> {
        final Rig rig;final FluidPresentation<Menu> presentation;
        final Map<Long,Long> owners=new HashMap<>();final Map<Long,List<Long>> members=new HashMap<>();
        final Set<Long> loadedBlocks=new HashSet<>();final Map<Long,Long> revisions=new HashMap<>();final Set<UUID> pending=new HashSet<>();
        final Map<Long,List<Long>> presentedAt=new TreeMap<>();final Map<Long,Integer> viewsOf=new TreeMap<>();
        int islandReads;
        final Map<Long,FluidView> cached=new HashMap<>();
        World(Rig rig) {
            this.rig=rig;presentation=new FluidPresentation<>(this,rig.epoch[0]);
            // As FluidWorldAuthority.published: a publication marks the island's loaded devices, nothing more.
            rig.onPublished=changed->{for(var s:changed)for(long device:members.getOrDefault(s.id(),List.of()))presentation.markIfLoaded(device);};
        }
        World device(long device,long island){owners.put(device,island);members.computeIfAbsent(island,ignored->new ArrayList<>()).add(device);revisions.putIfAbsent(device,0L);return this;}
        /** One world tick: the engine's work, then the presentation bucket due at this tick, as the world hook orders them. */
        void tick(){rig.tick();presentation.tick(rig.epoch[0]);}
        void run(int ticks){for(int i=0;i<ticks;i++)tick();}
        Menu open(long device){var menu=new Menu(device);presentation.subscribe(menu,device);return menu;}
        public long key(long device){return owners.getOrDefault(device,device);}
        public long revision(long device){return revisions.getOrDefault(device,-1L);}
        public boolean eventPending(UUID event){return pending.contains(event);}
        final Map<UUID,String> refused=new HashMap<>();
        public String eventRefusal(UUID event){return refused.remove(event);}
        public boolean open(Menu menu){return menu.open;}
        public FluidView view(long device,Map<Long,IslandCoordinator.Snapshot> islands) {
            viewsOf.merge(device,1,Integer::sum);
            var island=islands.computeIfAbsent(owners.get(device),id->{islandReads++;return rig.coordinator.presentation(id);});
            var view=new FluidView(device,revision(device),island.clock().committedTick(),island.clock().onlineTick(),island.status(),null,0,List.of());
            cached.put(device,view);return view;
        }
        public boolean present(long device,Supplier<FluidView> view) {
            if(!loadedBlocks.contains(device))return false;
            var built=view.get();assertEquals(device,built.identity());presentedAt.computeIfAbsent(device,ignored->new ArrayList<>()).add(rig.epoch[0]);return true;
        }
        public void deliver(Menu menu,long device,boolean withStatic,FluidView view,String reply){menu.received.add(new Received(rig.epoch[0],withStatic,reply,view));}
        public long replay(Menu menu,long device){
            var view=cached.get(device);if(view==null)return -1;
            deliver(menu,device,true,view,"");return view.inputRevision();
        }
    }

    @Test void firstRequestedOpeningReplaysLastBucketWithoutAnyScienceWorkOrDeadlineShift(){
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,deadHeadedLine());
        var world=new World(rig).device(101,1);world.loadedBlocks.add(101L);world.presentation.deviceLoaded(101);
        world.run(150);var snapshot=world.cached.get(101L);assertNotNull(snapshot);
        int reads=world.islandReads,solves=rig.solves,builds=world.viewsOf.get(101L);
        var menu=world.open(101);long due=world.presentation.nextDue();
        assertTrue(menu.received.isEmpty(),"a closed GUI never received background contents");
        assertTrue(world.presentation.replayOnOpen(menu));
        assertEquals(1,menu.received.size());var opening=menu.received.getFirst();
        assertSame(snapshot,opening.view());assertTrue(opening.withStatic());assertEquals("",opening.reply());
        assertEquals(150,opening.tick());assertTrue(opening.view().onlineTick()<opening.tick());
        assertEquals(reads,world.islandReads);assertEquals(solves,rig.solves);assertEquals(builds,world.viewsOf.get(101L));
        assertEquals(due,world.presentation.nextDue());
        world.run((int)(due-rig.epoch[0]));
        assertEquals(2,menu.received.size());assertEquals(due,menu.received.getLast().tick());
        assertFalse(menu.received.getLast().withStatic(),"the opening snapshot already included its metadata");
    }
    @Test void cachedReplayDoesNotConsumePendingInputRepliesOrUseANewerUnpublishedRevision(){
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,deadHeadedLine());
        var world=new World(rig).device(101,1);world.loadedBlocks.add(101L);world.presentation.deviceLoaded(101);
        world.run(150);var snapshot=world.cached.get(101L);var menu=world.open(101);
        world.revisions.put(101L,9L);
        world.presentation.input(menu,101,FluidPresentation.Input.refused(rig.epoch[0],"Stale controls"));
        assertTrue(world.presentation.replayOnOpen(menu));assertSame(snapshot,menu.received.getFirst().view());
        assertEquals(0,menu.received.getFirst().view().inputRevision());assertTrue(menu.replies().isEmpty());
        world.run(51);var next=menu.received.getLast();
        assertEquals(9,next.view().inputRevision());assertTrue(next.withStatic());
        assertEquals("Not applied: Stale controls",next.reply());
    }
    @Test void noPublishedSnapshotOrNoActiveRequestCannotTriggerAReplayOrNewView(){
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,deadHeadedLine());
        var world=new World(rig).device(101,1);var menu=world.open(101);
        assertFalse(world.presentation.replayOnOpen(menu));assertTrue(menu.received.isEmpty());assertTrue(world.viewsOf.isEmpty());
        world.run(101);int count=menu.received.size();menu.open=false;
        assertFalse(world.presentation.replayOnOpen(menu));assertEquals(count,menu.received.size());
        world.presentation.unsubscribe(menu);assertFalse(world.presentation.replayOnOpen(menu));
    }

    // ---------------- menus: one live payload per bucket, static only on a revision change ----------------

    /** Zero, one and many open menus on a transient and a steady island: one delivery per 100 ticks per menu, at the island's bucket. */
    @Test void openMenusReceiveOneLivePayloadPerBucketAndTheStaticPartOnlyWhenTheRevisionChanges() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,slowFill());rig.register(2,generatorToVoid());
        var world=new World(rig);for(long d=101;d<=104;d++)world.device(d,1);for(long d=201;d<=204;d++)world.device(d,2);
        // Zero menus, nothing loaded: no bucket is ever due and nothing is built.
        countFromHere();world.run(1_000);
        assertEquals(Long.MAX_VALUE,world.presentation.nextDue());assertEquals(0,counter("bucketFlushes"));assertTrue(world.viewsOf.isEmpty());
        // One menu, then fifteen more: two menus on each of the eight devices of the two islands.
        var single=world.open(101);world.run(1_000);
        assertEquals(10,single.received.size(),"one delivery per 100 ticks");
        for(var r:single.received)assertEquals(1,r.tick()%100,"at the island's bucket");
        var many=new ArrayList<Menu>(List.of(single));
        for(long d:new long[]{101,102,103,104,201,202,203,204})for(int copy=0;copy<(d==101?1:2);copy++)many.add(world.open(d));
        assertEquals(16,many.size());
        long start=rig.epoch[0];countFromHere();world.viewsOf.clear();world.islandReads=0;
        world.run(1_000);world.revisions.put(203L,1L);world.run(1_000);
        for(var menu:many) {
            var window=menu.received.stream().filter(r->r.tick()>start).toList();
            assertEquals(20,window.size(),"menu on "+menu.device+": one live payload per 100 ticks");
            long key=world.key(menu.device);for(var r:window)assertEquals(key%100,r.tick()%100,"menu on "+menu.device+" off its bucket");
            long statics=menu.received.stream().filter(Received::withStatic).count();
            // The static part goes with a menu's first bucket, and again only for device 203, whose revision changed.
            assertEquals(menu.device==203?2:1,statics,"static payloads for the menu on "+menu.device);
            assertTrue(menu.received.getFirst().withStatic());
        }
        // One view per device per bucket however many menus show it, and one island read per bucket.
        for(long d:new long[]{101,102,103,104,201,202,203,204})assertEquals(20,world.viewsOf.get(d),"views of "+d);
        assertEquals(40,world.islandReads,"each island read once per bucket");
        assertEquals(40,counter("bucketFlushes"));
        // Closing a menu ends its deliveries at its next bucket.
        single.open=false;int before=single.received.size();world.run(300);assertEquals(before,single.received.size());
        assertFalse(world.presentation.subscribed(single));
    }

    // ---------------- loaded devices: marked by publications, presented on the bucket ----------------

    /** A publication marks the island's loaded devices only; the bucket presents them; unloaded devices never build a view. */
    @Test void loadedDevicesArePresentedOnTheirIslandsBucketAndUnloadedOnesNever() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,slowFill());rig.register(2,generatorToVoid());
        var world=new World(rig).device(101,1).device(102,1).device(201,2).device(202,2);
        world.loadedBlocks.addAll(List.of(101L,201L));world.presentation.deviceLoaded(101);world.presentation.deviceLoaded(201);
        world.run(3_000);
        assertFalse(world.viewsOf.containsKey(102L),"an unloaded device builds no view");assertFalse(world.viewsOf.containsKey(202L));
        // The transient island publishes every interval: its loaded device is presented once per interval, at its bucket.
        var transientTicks=world.presentedAt.get(101L);
        assertTrue(transientTicks.size()>=29&&transientTicks.size()<=31,"presentations of the transient device: "+transientTicks.size());
        for(long t:transientTicks)assertEquals(1,t%100);
        // The steady island certifies and stops publishing: its device is presented until then and not after.
        var certified=rig.coordinator.observe(2).certificate();assertTrue(certified.isPresent(),"generator to void did not certify");
        long since=certified.orElseThrow().baseTick();var steadyTicks=world.presentedAt.get(201L);
        for(long t:steadyTicks)assertEquals(2,t%100);
        assertTrue(steadyTicks.getLast()<=since+100,"presented after certification without a publication: "+steadyTicks+" certified at "+since);
        // A block entity that unloads is no longer marked, and one the bucket finds unloaded is forgotten.
        world.presentation.deviceUnloaded(101);world.loadedBlocks.remove(101L);int count=transientTicks.size();world.run(500);
        assertEquals(count,world.presentedAt.get(101L).size());
        world.presentation.deviceLoaded(102);world.run(200);
        assertFalse(world.viewsOf.containsKey(102L),"a device found unloaded at its bucket is not presented");
        assertEquals(1,world.presentation.stats().loadedDevices(),"the unloaded device was forgotten: "+world.presentation.stats());
        // Identity binding or a chunk load marks without presenting: nothing happens before the bucket.
        world.presentation.deviceLoaded(101);world.loadedBlocks.add(101L);int presentations=world.presentedAt.get(101L).size();
        long now=rig.epoch[0];long bucket=now+1+Math.floorMod(1-(now+1),100L);
        while(rig.epoch[0]<bucket-1){world.tick();assertEquals(presentations,world.presentedAt.get(101L).size(),"presented before its bucket at "+rig.epoch[0]);}
        world.tick();assertEquals(presentations+1,world.presentedAt.get(101L).size());assertEquals(bucket,world.presentedAt.get(101L).getLast());
    }

    // ---------------- inputs: the reply comes with the following bucket ----------------

    /** Every reply arrives with the menu's first bucket after the tick its input arrived, never with an earlier delivery. */
    @Test void anInputIsAnsweredOnTheFollowingBucketAndNeverBefore() {
        var rig=new Rig(CertificatePolicy.disabled());rig.register(1,slowFill());
        var world=new World(rig).device(101,1);var menu=world.open(101);
        world.run(150);
        // Refused: the reply is the refusal, at the next bucket (201), not in between.
        world.presentation.input(menu,101,FluidPresentation.Input.refused(rig.epoch[0],"Stale fluid controls"));
        int seen=menu.received.size();world.run(50);
        assertEquals(seen,menu.received.size(),"nothing is delivered before the bucket");
        world.tick();assertEquals(201,menu.received.getLast().tick());assertEquals("Not applied: Stale fluid controls",menu.received.getLast().reply());
        // Queued: the event is still pending at the bucket, then applied; the application is reported at the bucket after.
        var event=UUID.randomUUID();world.pending.add(event);world.run(99);
        world.presentation.input(menu,101,FluidPresentation.Input.queued(rig.epoch[0],event,rig.epoch[0]));
        world.tick();assertEquals(301,menu.received.getLast().tick());assertEquals(FluidPresentation.QUEUED+"300",menu.received.getLast().reply(),"latency one tick: the following bucket");
        world.run(50);world.pending.remove(event);world.run(49);assertEquals(301,menu.received.getLast().tick());
        world.tick();assertEquals(401,menu.received.getLast().tick());assertEquals(FluidPresentation.APPLIED,menu.received.getLast().reply());
        // Applied before the bucket (the event was applied inside the handler), and a change that changed nothing.
        world.run(10);var applied=UUID.randomUUID();
        world.presentation.input(menu,101,FluidPresentation.Input.queued(rig.epoch[0],applied,rig.epoch[0]));
        world.presentation.input(menu,101,new FluidPresentation.Input(rig.epoch[0],null,null,-1));
        world.run(90);assertEquals(501,menu.received.getLast().tick());assertEquals("Applied / Applied",menu.received.getLast().reply());
        // An input that arrives inside a bucket's own tick, before its flush, waits for the next bucket.
        world.run(99);rig.tick();assertEquals(601,rig.epoch[0]);
        world.presentation.input(menu,101,FluidPresentation.Input.refused(rig.epoch[0],"late"));
        world.presentation.tick(rig.epoch[0]);assertEquals(601,menu.received.getLast().tick());assertEquals("",menu.received.getLast().reply(),"the bucket of the input's own tick does not answer it");
        world.run(100);assertEquals(701,menu.received.getLast().tick());assertEquals("Not applied: late",menu.received.getLast().reply());
        // An event that applied but did nothing for its input (a recovery that found no cake) is refused in the reply.
        var empty=UUID.randomUUID();world.refused.put(empty,"Filter unchanged: inventory full or no captured solids");
        world.presentation.input(menu,101,FluidPresentation.Input.queued(rig.epoch[0],empty,rig.epoch[0]));
        world.run(100);assertEquals(801,menu.received.getLast().tick());assertEquals("Not applied: Filter unchanged: inventory full or no captured solids",menu.received.getLast().reply());
        // Every reply is on the first delivery after its input; buckets without an input carry none.
        assertEquals(List.of("Not applied: Stale fluid controls",FluidPresentation.QUEUED+"300",FluidPresentation.APPLIED,"Applied / Applied","Not applied: late","Not applied: Filter unchanged: inventory full or no captured solids"),menu.replies());
        for(var r:menu.received)assertEquals(1,r.tick()%100);
    }

    // ---------------- science: presentation never drives progress ----------------

    /**
     * The same islands with and without viewers: sixteen menus and every device loaded and presented, against no
     * presentation at all. Solved publications are bit for bit the same (the WP2 publication fingerprint), so are
     * the solve counts and the state a final read materialises; replayed accounting splits into more spans with
     * viewers (a view materialises a certified island) and sums to the same totals.
     */
    @Test void scientificProgressIsIdenticalWithAndWithoutViewers() {
        // Exact rest, steady through-flow, a settled pair revalidated at short horizons, and a transient fill.
        var shortWindow=new CertificatePolicy(true,1e-9,1e-6,3,2,0);
        var fixtures=List.<Supplier<PassiveNetwork>>of(this::deadHeadedLine,this::generatorToVoid,this::closedPair,this::slowFill);
        Rig quiet=new Rig(shortWindow),viewed=new Rig(shortWindow);
        for(var rig:List.of(quiet,viewed))for(int i=0;i<fixtures.size();i++)rig.register(i+1,fixtures.get(i).get());
        var world=new World(viewed);var menus=new ArrayList<Menu>();
        for(long island=1;island<=4;island++)for(long d=0;d<4;d++){long device=island*100+d;world.device(device,island);world.loadedBlocks.add(device);world.presentation.deviceLoaded(device);menus.add(world.open(device));}
        for(int i=0;i<12_000;i++){quiet.tick();world.tick();}
        assertTrue(world.viewsOf.values().stream().mapToInt(Integer::intValue).sum()>=16*100,"the viewers really viewed");
        assertTrue(viewed.coordinator.observe(1).certificate().isPresent(),"the dead-headed line rests");
        for(long island:new long[]{2,3})assertTrue(viewed.replayed.stream().anyMatch(r->r.id()==island),"island "+island+" replayed a steady certificate");
        assertTrue(viewed.replayed.size()>quiet.replayed.size(),"views materialised certified islands: "+viewed.replayed.size()+" against "+quiet.replayed.size());
        assertEquals(quiet.solves,viewed.solves,"solves");
        assertEquals(IslandCertificateTest.publications(quiet.published),IslandCertificateTest.publications(viewed.published),"solved publications");
        for(long island=1;island<=4;island++) {
            var a=quiet.coordinator.snapshot(island);var b=viewed.coordinator.snapshot(island);
            assertEquals(state(a),state(b),"state of island "+island);assertEquals(a.certificate(),b.certificate());
            // Replayed boundary accounting: the same totals, in more pieces.
            double[] x=replayedBoundary(quiet.replayed,island),y=replayedBoundary(viewed.replayed,island);
            for(int c=0;c<x.length;c++)assertEquals(x[c],y[c],1e-12*Math.max(1,Math.abs(x[c])),"replayed boundary total "+c+" of island "+island);
        }
        for(var menu:menus)assertTrue(menu.received.size()>=119,"menu on "+menu.device+" received "+menu.received.size());
    }
    /** Clock and every node's inventory, energy and state, bit for bit. */
    private static String state(IslandCoordinator.Snapshot island) {
        var b=new StringBuilder(island.clock().toString());java.util.function.DoubleConsumer bits=v->b.append(',').append(Long.toHexString(Double.doubleToLongBits(v)));
        for(var node:island.graph().reservoirs()) {
            b.append('|').append(node.id());for(double n:node.inventory().moles())bits.accept(n);bits.accept(node.inventory().internalEnergy());
            var state=node.state();bits.accept(state.temperature());bits.accept(state.pressure());for(double v:state.liquid())bits.accept(v);for(double v:state.vapor())bits.accept(v);
        }
        return b.toString();
    }
    private static double[] replayedBoundary(List<IslandCoordinator.Snapshot> replays,long island) {
        double[] sum=null;
        for(var s:replays)if(s.id()==island)for(var b:s.lastResult().orElseThrow().boundaries()){var n=b.moles();if(sum==null)sum=new double[n.length+2];for(int c=0;c<n.length;c++)sum[c]+=n[c];sum[n.length]+=b.totalEnergyJoule();}
        for(var s:replays)if(s.id()==island){if(sum==null)sum=new double[2];sum[sum.length-1]+=s.lastResult().orElseThrow().pumpWorkJoule();}
        return sum==null?new double[0]:sum;
    }
    /** A view of a certified island stops one tick short of a wake it has not acted on; the island's own deadline wakes it, at the same tick as without the view. */
    @Test void aPresentationReadStopsShortOfAWakeAndNeverWakesTheIsland() {
        var policy=new CertificatePolicy(true,1e-9,1e-6,3,2,0);
        Rig plain=new Rig(policy),read=new Rig(policy);
        for(var rig:List.of(plain,read)){rig.register(1,closedPair());for(int i=0;i<40_000&&rig.coordinator.observe(1).certificate().isEmpty();i++)rig.tick();}
        assertEquals(plain.epoch[0],read.epoch[0]);var certificate=read.coordinator.observe(1).certificate().orElseThrow();
        long horizon=certificate.horizonTick();assertTrue(horizon>read.epoch[0]);
        while(read.epoch[0]<horizon-1){plain.tick();read.tick();}
        // The epoch reaches the horizon; before the deadline runs, a view reads the island.
        plain.epoch[0]++;read.epoch[0]++;
        var viewed=read.coordinator.presentation(1);
        assertEquals(horizon-1,viewed.clock().committedTick(),"a view stops one tick short of the horizon");
        assertTrue(viewed.certificate().isPresent(),"a view never wakes");assertEquals(0,read.attempts.size());
        // The horizon's own deadline wakes both islands identically.
        plain.coordinator.tick();plain.drain();read.coordinator.tick();read.drain();
        assertEquals(state(plain.coordinator.observe(1)),state(read.coordinator.observe(1)));
        assertEquals(plain.solves,read.solves);
        assertEquals(IslandCertificateTest.publications(plain.published),IslandCertificateTest.publications(read.published));
    }

    // ---------------- cost ----------------

    /** Nothing loaded and no menu: no bucket is due. A loaded device of a resting island: nothing after it rests. A menu: one flush per 100 ticks. */
    @Test void presentationCostsNothingPerTickUnlessABucketHasWork() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(7,deadHeadedLine());
        var world=new World(rig).device(701,7);world.loadedBlocks.add(701L);world.presentation.deviceLoaded(701);
        for(int i=0;i<4_000&&rig.coordinator.observe(7).certificate().isEmpty();i++)world.tick();
        assertEquals(IslandCertificate.Kind.REST,rig.coordinator.observe(7).certificate().orElseThrow().kind());
        world.run(100);
        countFromHere();world.run(10_000);
        for(var name:List.of("bucketFlushes","viewBuilds","devicePresentations","materialisations","islandVisits","solvesDispatched"))assertEquals(0,counter(name),name);
        assertEquals(Long.MAX_VALUE,world.presentation.nextDue());
        var menu=world.open(701);countFromHere();world.run(10_000);
        assertEquals(100,counter("bucketFlushes"));assertEquals(100,menu.received.size());
        assertEquals(100,counter("materialisations"),"one materialisation of the resting island per bucket, for its menu");
        assertEquals(0,counter("solvesDispatched"));
        assertTrue(menu.received.getLast().view().status().startsWith("RESTING: no flow since"),menu.received.getLast().view().status());
        assertEquals(menu.received.getLast().tick(),menu.received.getLast().view().committedTick(),"the view reads the island materialised to its bucket");
    }
    /** A topology change moves a menu with its device to the replacement island's bucket. */
    @Test void aMenuFollowsItsDeviceToItsNewIslandsBucket() {
        var rig=new Rig(CertificatePolicy.disabled());rig.register(1,slowFill());rig.register(7,deadHeadedLine());
        var world=new World(rig).device(101,1);var menu=world.open(101);
        world.run(300);assertEquals(1,menu.received.getLast().tick()%100);
        world.owners.put(101L,7L);world.presentation.rekey();world.run(300);
        var last=menu.received.stream().filter(r->r.tick()>300).toList();
        assertEquals(3,last.size());for(var r:last)assertEquals(7,r.tick()%100);
    }
}

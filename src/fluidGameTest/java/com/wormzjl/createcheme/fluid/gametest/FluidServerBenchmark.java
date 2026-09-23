package com.wormzjl.createcheme.fluid.gametest;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialRuntime;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.*;
import java.util.*;

/** Actual-server, paced performance fixture. Pilot output is deliberately not qualification evidence. */
@GameTestHolder("createcheme_fluid_benchmark")
@PrefixGameTestTemplate(false)
public final class FluidServerBenchmark {
    private static final long TICK_NANOS=50_000_000L;
    /** Profiles measured over a fixed elapsed window instead of a count of accepted intervals per island. */
    private static final Set<String> TIMED_PROFILES=Set.of("stress100","transient100","rest100","mixed100","viewers");
    /**
     * Ladder families. THROUGH is the stress100 ladder: a 150.1 kPa generator, 1 m3 reservoirs and a 150.0 kPa
     * void, near-stationary through-flow. TRANSIENT has no void: a 200 kPa generator fills 1000 m3 reservoirs
     * through 100 m of 0.30 m pipe, a fill time constant of about 2,500 to 7,400 s for 10 to 30 reservoirs
     * (4,930 s measured for 20 by direct interval solves), so every interval changes inventory by about 5e-4
     * and that change itself moves by about 1e-7 per interval for the whole run. CLOSED has no generator and
     * no void: the stress100 rung pressures spread over 1 m3 reservoirs and settle to rest.
     */
    private enum Ladder {THROUGH,TRANSIENT,CLOSED}
    private static final double TRANSIENT_GENERATOR_PRESSURE=200000,TRANSIENT_RESERVOIR_VOLUME=1000,TRANSIENT_PIPE_DIAMETER=.3,TRANSIENT_FEED_LENGTH=100;
    private static Fixture fixture;
    /** The world's saved data as installed with the fixture: the benchmark times its saves (plan section 6, save time). */
    private static FluidSavedData savedData;
    private static Run run;
    private static jdk.jfr.Recording recording;
    private FluidServerBenchmark() {}
    private record Fixture(FluidThermodynamics model,FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot topology,Set<Long> chunks,int physicalPipes,int compressedPipes,Map<Long,Ladder> ladders,List<ViewerTarget> viewers) {}
    /**
     * The viewers profile's sixteen menus, one per network 0-15 of the mixed100 fixture, and the edits each one sends
     * through the real edit handler. ACCEPTED edits change a generator's pressure or a pipe's roughness back and
     * forth (networks 0 and 2: a transient and a through-flow generator; network 3: a pipe of a closed, certified
     * ladder), INVALID ones ask for an out-of-range pressure (network 1), and STALE ones carry an old revision
     * (networks 4-15; on a closed ladder's reservoir the kind is refused first). Only the three ACCEPTED islands
     * change; every other island must follow the mixed100 trajectory bit for bit.
     */
    private record ViewerTarget(int network,long device,Kind kind,BlockPos position,String role) {}
    private record Sample(long island,IslandCoordinator.Metrics timing,String status,PassiveStepSolver.Acceptance acceptance,int substeps,int rejectedSubsteps,Double readyToPublicationMillis) {}
    /** Test-only one-second observations. Heap usage includes garbage awaiting collection; it is not retained live size. */
    private record MemorySample(long onlineTick,long epochMillis,double sinceStartSeconds,boolean measured,
                                long heapUsed,long heapCommitted,long heapMax,long nonHeapUsed,
                                long directBufferBytes,long mappedBufferBytes,long gcCount,long gcMillis,long heapAfterLastGc) {}
    private static final class Run {
        final MinecraftServer server;final GameTestHelper helper;final FluidWorldAuthority world;
        final boolean pilot=Boolean.getBoolean("createcheme.fluid.benchmark.pilot");
        final boolean contention="contention".equals(System.getProperty("createcheme.fluid.benchmark.profile"));
        final String profile=System.getProperty("createcheme.fluid.benchmark.profile","one");
        final boolean stress=TIMED_PROFILES.contains(profile);
        final int warmupTicks=stress?20*Math.max(10,Integer.getInteger("createcheme.fluid.stress.warmupSeconds",60)):pilot?20*Math.max(5,Integer.getInteger("createcheme.fluid.benchmark.pilotWarmupSeconds",5)):2400,targetIntervals=pilot?5:200;
        final long stressMeasurementNanos=1_000_000_000L*Math.max(20,Integer.getInteger("createcheme.fluid.stress.measurementSeconds",120));
        final long startTick,startNanos=System.nanoTime();
        final long startEpochMillis=System.currentTimeMillis();
        final boolean memoryEnabled=Boolean.getBoolean("createcheme.fluid.benchmark.memory");
        /** Solver counters over the measurement window only; see {@link SolverDiagnostics}. */
        final boolean diagnosticsEnabled=Boolean.getBoolean("createcheme.fluid.benchmark.diagnostics");
        SolverDiagnostics.Sample diagnostics;
        final java.lang.management.MemoryMXBean memoryBean=memoryEnabled?java.lang.management.ManagementFactory.getMemoryMXBean():null;
        final List<java.lang.management.GarbageCollectorMXBean> gcBeans=memoryEnabled?java.lang.management.ManagementFactory.getGarbageCollectorMXBeans():List.of();
        final List<java.lang.management.BufferPoolMXBean> bufferPools=memoryEnabled?java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class):List.of();
        final List<MemorySample> memorySamples=new ArrayList<>();
        long measuredStartEpochMillis,lastMemoryTick=Long.MIN_VALUE;
        final List<Sample> samples=new ArrayList<>();final List<Double> engineMillis=new ArrayList<>(),tickMillis=new ArrayList<>(),tickSpacingMillis=new ArrayList<>();
        final List<Sample> warmupSamples=new ArrayList<>();
        final Map<Long,Long> seen=new HashMap<>();final double[] external=new double[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.conservedCount()];
        final LinkedHashMap<Long,Long> eligibleTickStarts=new LinkedHashMap<>();
        long tickStarted,previousTickStarted,nextTick,lastMeter,measuredStarted;double externalEnergy,pumpWork;boolean finished;
        /** After the window: the report waiting for the save one cadence after the window's end, which the paced ticks
         * in between reach; the saves measured so far; the tick of that last save. */
        Map<String,Object> report;boolean pass,probing,probed;long probeTick;final Map<String,Object> saves=new LinkedHashMap<>();
        FluidContentionProbe competing;long contentionStartTick,contentionFinishedTick,maximumDebtTicks;int maximumOutstanding,maximumReady;boolean bounded=true;
        final List<Map<String,Object>> contentionSamples=new ArrayList<>();
        final List<Map<String,Object>> stressSamples=new ArrayList<>();
        /** Scheduling counters over the measurement window; see {@link FluidRuntimeDiagnostics}. A tick is
         * idle when it routed no completion, dispatched no solve, published no island and fired no current
         * deadline (a counter that exists only once deadlines do, so the definition is the same for runs
         * without them); everything the engine still did on such a tick is scheduling overhead. The
         * harness's own reads are paused out. */
        final List<String> counterNames=FluidRuntimeDiagnostics.names();
        final long[] lastCounters=new long[counterNames.size()],counterTotals=new long[counterNames.size()],idleCounterTotals=new long[counterNames.size()],idleTicksWithWork=new long[counterNames.size()],maximumPerTick=new long[counterNames.size()];
        long countedTicks,idleTicks;
        void countTick() {
            var values=FluidRuntimeDiagnostics.sample();long[] delta=new long[counterNames.size()];
            for(int i=0;i<delta.length;i++){long value=values.get(counterNames.get(i));delta[i]=value-lastCounters[i];lastCounters[i]=value;}
            boolean idle=true;for(var busy:List.of("completionsRouted","solvesDispatched","islandsPublished","deadlinesFired","bucketFlushes")){int index=counterNames.indexOf(busy);if(index>=0&&delta[index]!=0)idle=false;}
            countedTicks++;if(idle)idleTicks++;
            for(int i=0;i<delta.length;i++){counterTotals[i]+=delta[i];maximumPerTick[i]=Math.max(maximumPerTick[i],delta[i]);if(idle){idleCounterTotals[i]+=delta[i];if(delta[i]>0)idleTicksWithWork[i]++;}}
        }
        /** Mean pressure gap between each transient ladder's generator and its reservoirs, sampled once a second. */
        final List<double[]> fillGaps=new ArrayList<>();
        /** Viewers: the online tick the measurement window starts at, the open menus, and the edits they sent. */
        final boolean viewers="viewers".equals(profile);
        long measureStartTick=-1;boolean presentationPassed=true;
        record ViewerMenu(ViewerTarget target,net.minecraft.server.level.ServerPlayer player,com.wormzjl.createcheme.world.inventory.FluidDeviceMenu menu,double original,boolean through) {}
        record ScriptedEdit(int menu,int network,String role,long receivedTick,long revision) {}
        final List<ViewerMenu> viewerMenus=new ArrayList<>();final List<ScriptedEdit> scriptedEdits=new ArrayList<>();
        /** Ten seconds into the warm-up (every block entity bound by then) the menus open; in the window each edits five times, through the real handler. */
        void viewerTick() {
            if(!viewers)return;long now=world.onlineTick();
            if(viewerMenus.isEmpty()&&now-startTick>=200) {
                var level=server.overworld();
                for(var target:fixture.viewers) {
                    var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(new UUID(2026092301L,target.network()),"FluidViewer"+target.network()));
                    player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);player.setPos(target.position().getX()+.5,target.position().getY()+1,target.position().getZ()+.5);
                    var menu=new com.wormzjl.createcheme.world.inventory.FluidDeviceMenu(1000+target.network(),player.getInventory(),target.position(),target.device(),false);player.containerMenu=menu;
                    var record=world.registrations().get(target.device());
                    viewerMenus.add(new ViewerMenu(target,player,menu,target.kind()==Kind.PIPE?record.device().geometry().roughness():record.spec().pressure(),target.network()%4==2));
                }
            }
            if(measureStartTick<0||viewerMenus.isEmpty())return;
            for(int i=0;i<viewerMenus.size();i++) {
                long offset=now-measureStartTick-7-23L*i;if(offset<0||offset%400!=0||offset/400>=5)continue;
                var m=viewerMenus.get(i);var record=world.registrations().get(m.target().device());var c=com.wormzjl.createcheme.network.FluidNetwork.Controls.from(record);
                long revision=record.revision();boolean even=offset/400%2==0;var gson=new com.google.gson.Gson();String json;
                switch(m.target().role()) {
                    case "ACCEPTED"->{
                        double roughness=c.roughness(),pressure=c.pressure();
                        if(m.target().kind()==Kind.PIPE)roughness=even?.00005:m.original();
                        else pressure=even?m.original()-(m.through()?5:1000):m.original();
                        json=gson.toJson(new com.wormzjl.createcheme.network.FluidNetwork.Controls(c.temperature(),pressure,c.diameter(),roughness,c.volumeFlow(),c.maximumAddedPressure(),c.composition()));
                    }
                    case "INVALID"->{var object=com.google.gson.JsonParser.parseString(gson.toJson(c)).getAsJsonObject();object.addProperty("pressure",3e6);json=object.toString();}
                    default->{json=gson.toJson(c);revision+=7;}
                }
                scriptedEdits.add(new ScriptedEdit(i,m.target().network(),m.target().role(),now,revision));
                com.wormzjl.createcheme.network.FluidNetwork.edit(new com.wormzjl.createcheme.network.FluidNetwork.EditPayload(m.menu().containerId,m.target().position(),m.target().device(),revision,json),m.player());
            }
        }
        Run(GameTestHelper helper){
            this.helper=helper;server=helper.getLevel().getServer();world=FluidWorldAuthority.find(server).orElseThrow();startTick=world.onlineTick();FluidRuntimeMeter.enable(server);world.observe(this::published);
            // Every fixture island was loaded together, so one offset maps the world tick to each island's online tick.
            var islands=world.diagnosticSnapshots();long offset=islands.getFirst().clock().onlineTick()-world.onlineTick();
            if(islands.stream().anyMatch(s->s.clock().onlineTick()-world.onlineTick()!=offset))throw new IllegalStateException("Fixture islands do not share one online clock");
            long middle=world.onlineTick()+offset+warmupTicks+stressMeasurementNanos/100_000_000L;
            referenceTick=(middle+99)/100*100;referenceWorldTick=referenceTick-offset;
            if(memoryEnabled) {
                var path=Path.of(System.getProperty("createcheme.fluid.benchmark.output")).resolveSibling("memory-start.json");
                try {Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                        "processId",ProcessHandle.current().pid(),"startEpochMillis",startEpochMillis,
                        "warmupTicks",warmupTicks,"heapMaxBytes",memoryBean.getHeapMemoryUsage().getMax())));}
                catch(java.io.IOException failure){throw new IllegalStateException("Cannot write memory benchmark identity",failure);}
                memoryTick(true);
            }
        }
        void memoryTick(boolean force) {
            if(!memoryEnabled||(!force&&(world.onlineTick()%20!=0||lastMemoryTick==world.onlineTick())))return;
            lastMemoryTick=world.onlineTick();var heap=memoryBean.getHeapMemoryUsage();long count=0,millis=0,direct=0,mapped=0;
            for(var gc:gcBeans){if(gc.getCollectionCount()>=0)count+=gc.getCollectionCount();if(gc.getCollectionTime()>=0)millis+=gc.getCollectionTime();}
            for(var pool:bufferPools){if(pool.getName().equals("direct"))direct+=pool.getMemoryUsed();else if(pool.getName().equals("mapped"))mapped+=pool.getMemoryUsed();}
            memorySamples.add(new MemorySample(world.onlineTick(),System.currentTimeMillis(),(System.nanoTime()-startNanos)/1e9,
                    measuredStarted!=0,heap.getUsed(),heap.getCommitted(),heap.getMax(),memoryBean.getNonHeapMemoryUsage().getUsed(),direct,mapped,count,millis,heapAfterLastGc()));
        }
        /** Heap in use right after each heap pool's most recent collection, summed: live size without forcing a GC. */
        long heapAfterLastGc() {
            long used=0;
            for(var pool:java.lang.management.ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==java.lang.management.MemoryType.HEAP&&pool.getCollectionUsage()!=null)used+=pool.getCollectionUsage().getUsed();
            return used;
        }
        /** Rest and steady-flow certificates: the replayed and identity-advanced spans inside the window, never solves. */
        long replayedIntervals,restedIntervals;double replayedSeconds,restedSeconds;
        /**
         * The reference state for comparing a run with certificates against one without: every island's
         * inventory and its own boundary ledger (components, then energy with pump work) at island tick
         * {@link #referenceTick}, mid-window on the five-second grid. Certified islands are materialised exactly
         * then; a solved island publishes an interval ending there, or - when a startup wall-deadline retry
         * shifted its grid - the two publications around it are kept for interpolation. Both sides pay the same
         * one materialising read.
         */
        final long referenceTick,referenceWorldTick;boolean referenceMaterialised;
        record ReferencePoint(long tick,List<double[]> inventories,double[] external) {}
        final Map<Long,double[]> islandExternal=new HashMap<>();
        final Map<Long,ReferencePoint> referenceBefore=new TreeMap<>(),referenceAfter=new TreeMap<>();
        /**
         * Per island, a 64-bit fingerprint of every accepted publication (solved or replayed): its end tick, every
         * node's inventory, internal energy, temperature and pressure, and the interval's flows, boundary transfers
         * and pump work, bit for bit. Two runs agree on an island's trajectory up to the first entry that differs,
         * which locates where two runs part without storing their states.
         */
        final Map<Long,List<String>> fingerprints=new TreeMap<>();
        /**
         * Per island, what the certificate evidence measured at each solved interval ({@link
         * IslandCoordinator.Evidence}): end tick, then the smallest stationary tolerance and its parts (state drift,
         * component, energy, flow with quiet pipes exempt, relative flow change alone) against the interval before,
         * and the interval's own drift d. Empty with certificates off, which compute nothing.
         */
        final Map<Long,List<double[]>> evidence=new TreeMap<>();
        static String fingerprint(IslandCoordinator.Snapshot island,PassiveIntervalSolver.Result result,long endTick,IslandCoordinator.Advance advance) {
            long[] h={0xcbf29ce484222325L};
            java.util.function.LongConsumer mix=v->{long x=h[0]^v;x*=0x9E3779B97F4A7C15L;h[0]=x^(x>>>29);};
            java.util.function.DoubleConsumer bits=v->mix.accept(Double.doubleToLongBits(v));
            mix.accept(endTick);
            for(var node:island.graph().reservoirs()){mix.accept(node.id());for(double n:node.inventory().moles())bits.accept(n);bits.accept(node.inventory().internalEnergy());bits.accept(node.state().temperature());bits.accept(node.state().pressure());}
            for(double q:result.averageMassFlows())bits.accept(q);
            for(var boundary:result.boundaries()){mix.accept(boundary.nodeId());for(double n:boundary.moles())bits.accept(n);bits.accept(boundary.totalEnergyJoule());}
            bits.accept(result.pumpWorkJoule());
            return endTick+":"+advance.name().charAt(0)+":"+Long.toHexString(h[0]);
        }
        void referenceTick() {
            if(!stress||referenceMaterialised||world.onlineTick()!=referenceWorldTick)return;
            referenceMaterialised=true;FluidRuntimeDiagnostics.pause();
            try{world.materialiseAll();}finally{FluidRuntimeDiagnostics.resume();}
        }
        boolean measuring(){return world.onlineTick()-startTick>=warmupTicks;}
        boolean enoughSamples() {
            if(stress)return measuredStarted>0&&System.nanoTime()-measuredStarted>=stressMeasurementNanos;
            if(contention)return competing!=null&&competing.complete()&&contentionFinishedTick>0&&world.onlineTick()>=contentionFinishedTick+200;
            if(pilot)return samples.size()>=targetIntervals;
            // Count complete cohorts, not 200 records from only two ticks of a 100-island run.
            if(world.onlineTick()-startTick<warmupTicks+100L*targetIntervals)return false;
            var counts=new HashMap<Long,Integer>();for(var sample:samples)counts.merge(sample.island(),1,Integer::sum);
            return fixture.checkpoint.islands().stream().allMatch(i->counts.getOrDefault(i.snapshot().id(),0)>=targetIntervals);
        }
        void stressTick() {
            if(!stress||!measuring()||world.onlineTick()%20!=0)return;
            FluidRuntimeDiagnostics.pause();
            try{stressSample();}finally{FluidRuntimeDiagnostics.resume();}
        }
        private void stressSample() {
            var diagnostics=ProcessSolveServices.diagnostics(server);
            // Stored islands, not a capture: a capture materialises every certified island, and this once-a-second
            // read must not advance anything the engine itself would not.
            var snapshots=world.diagnosticSnapshots();
            // Committed simulated seconds, not online seconds: the graph is the state at the committed tick.
            double gap=0,committed=0;int filling=0;
            for(var s:snapshots)if(fixture.ladders.get(s.id())==Ladder.TRANSIENT) {
                gap+=TRANSIENT_GENERATOR_PRESSURE-s.graph().reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).mapToDouble(n->n.state().pressure()).average().orElseThrow();
                committed+=s.clock().committedTick()/20.0;filling++;
            }
            if(filling>0)fillGaps.add(new double[]{committed/filling,gap/filling});
            // A certified island's stored committed tick lags by design; it owes nothing, so debt counts awake islands only.
            var awake=snapshots.stream().filter(s->s.certificate().isEmpty()).toList();
            var debts=awake.stream().map(s->(s.clock().onlineTick()-s.clock().committedTick())/20.0).toList();
            long eligible=awake.stream().filter(s->s.clock().onlineTick()-s.clock().committedTick()>=s.clock().cadenceTicks()).count();
            long rest=snapshots.stream().filter(s->s.certificate().map(c->c.kind()==IslandCertificate.Kind.REST).orElse(false)).count();
            long steady=snapshots.stream().filter(s->s.certificate().map(c->c.kind()==IslandCertificate.Kind.STEADY).orElse(false)).count();
            var sample=new LinkedHashMap<String,Object>();
            sample.put("onlineTick",world.onlineTick());sample.put("activeWorkers",diagnostics.activeWorkers());sample.put("workerLimit",diagnostics.workerCount());
            sample.put("outstandingJobs",diagnostics.outstandingJobs());sample.put("readyJobs",diagnostics.readyJobs());sample.put("pendingCompletions",diagnostics.pendingCompletions());
            sample.put("eligibleIslands",eligible);sample.put("certifiedIslands",rest+steady);sample.put("restIslands",rest);sample.put("steadyIslands",steady);
            sample.put("debtSeconds",statistics(debts));sample.put("wallSeconds",(System.nanoTime()-measuredStarted)/1e9);
            stressSamples.add(sample);
        }
        void contentionTick() {
            if(!contention||!measuring())return;
            var diagnostics=ProcessSolveServices.diagnostics(server);
            if(competing==null&&diagnostics.activeWorkers()==0&&diagnostics.readyJobs()==0) {
                competing=FluidContentionProbe.start(server,fixture.model,diagnostics.workerCount());contentionStartTick=world.onlineTick();
            }
            if(competing==null)return;
            maximumOutstanding=Math.max(maximumOutstanding,diagnostics.outstandingJobs());maximumReady=Math.max(maximumReady,diagnostics.readyJobs());
            bounded&=diagnostics.outstandingJobs()<=diagnostics.workerCount()+diagnostics.readyCapacity()&&diagnostics.readyJobs()<=diagnostics.readyCapacity();
            if(competing.complete()&&contentionFinishedTick==0)contentionFinishedTick=world.onlineTick();
            long debt=world.capture().checkpoint().islands().stream().mapToLong(i->i.snapshot().clock().onlineTick()-i.snapshot().clock().committedTick()).max().orElse(0);
            maximumDebtTicks=Math.max(maximumDebtTicks,debt);
            if((world.onlineTick()-contentionStartTick)%20==0)contentionSamples.add(Map.of("onlineTick",world.onlineTick(),"maximumDebtTicks",debt,"activeWorkers",diagnostics.activeWorkers(),"outstandingJobs",diagnostics.outstandingJobs(),"readyJobs",diagnostics.readyJobs()));
        }
        void published(List<IslandCoordinator.Snapshot> changed,Map<Long,IslandCoordinator.Metrics> timings) {
            for(var island:changed) {
                var timing=timings.get(island.id());if(timing==null||seen.getOrDefault(island.id(),0L)>=timing.sequence())continue;seen.put(island.id(),timing.sequence());
                var result=timing.accepted()?island.lastResult().orElseThrow():null;
                if(result!=null){for(var boundary:result.boundaries()){var n=boundary.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=boundary.totalEnergyJoule();}pumpWork+=result.pumpWorkJoule();}
                if(result!=null&&stress) {
                    fingerprints.computeIfAbsent(island.id(),ignored->new ArrayList<>()).add(fingerprint(island,result,timing.endTick(),timing.advance()));
                    var measured=timing.advance()==IslandCoordinator.Advance.SOLVED?world.certificationEvidence(island.id()):null;
                    if(measured!=null&&measured.endTick()==timing.endTick()) {
                        // JSON has no NaN or infinity: -1 marks an interval with no consecutive one before it, and
                        // Double.MAX_VALUE a discrete difference or material appearing in an empty node.
                        var s=measured.stationarity();double none=-1;
                        evidence.computeIfAbsent(island.id(),ignored->new ArrayList<>()).add(Arrays.stream(new double[]{timing.endTick(),s==null?none:s.measure(),s==null?none:s.state(),s==null?none:s.component(),s==null?none:s.energy(),s==null?none:s.flow(),s==null?none:s.largestRelativeFlowChange(),measured.drift()}).map(v->Double.isInfinite(v)?Double.MAX_VALUE:v).toArray());
                    }
                    var ledger=islandExternal.computeIfAbsent(island.id(),ignored->new double[external.length+1]);
                    for(var boundary:result.boundaries()){var n=boundary.moles();for(int c=0;c<n.length;c++)ledger[c]+=n[c];ledger[n.length]+=boundary.totalEnergyJoule();}
                    ledger[ledger.length-1]+=result.pumpWorkJoule();
                    long end=timing.endTick();
                    if(end>=referenceTick-200&&end<=referenceTick+200&&(end<=referenceTick||!referenceAfter.containsKey(island.id()))) {
                        var nodes=new ArrayList<double[]>();
                        for(var node:island.graph().reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR){var n=node.inventory().moles();var row=Arrays.copyOf(n,n.length+1);row[n.length]=node.inventory().internalEnergy();nodes.add(row);}
                        var point=new ReferencePoint(end,nodes,ledger.clone());
                        if(end<=referenceTick)referenceBefore.put(island.id(),point);
                        if(end>=referenceTick&&!referenceAfter.containsKey(island.id()))referenceAfter.put(island.id(),point);
                    }
                }
                // A replayed or identity-advanced span is accounted like any other advance, but it is not a solve.
                if(timing.advance()!=IslandCoordinator.Advance.SOLVED) {
                    if(measuring()){double seconds=(timing.endTick()-timing.startTick())/20.0;
                        if(timing.advance()==IslandCoordinator.Advance.REPLAYED){replayedIntervals++;replayedSeconds+=seconds;}else{restedIntervals++;restedSeconds+=seconds;}}
                    continue;
                }
                var ready=eligibleTickStarts.get(timing.endTick());Double totalLatency=ready==null?null:(System.nanoTime()-ready)/1e6;
                var sample=new Sample(island.id(),timing,island.status(),result==null?null:result.acceptance(),result==null?0:result.acceptedSubsteps(),result==null?0:result.rejectedSubsteps(),totalLatency);
                if(measuring())samples.add(sample);else if(warmupSamples.size()<10000)warmupSamples.add(sample);
            }
        }
    }

    public static void installFixture(ServerStartingEvent event) {
        var server=event.getServer();String profile=System.getProperty("createcheme.fluid.benchmark.profile","one");
        if(!Set.of("one","many","module","contention").contains(profile)&&!TIMED_PROFILES.contains(profile))throw new IllegalArgumentException("Unknown benchmark profile "+profile);
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/"+FluidSavedData.DATA_NAME+".dat")))throw new IllegalStateException("Benchmark requires a fresh disposable world");
        var model=FluidThermodynamics.forNetwork(MaterialRuntime.active(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var composition=Arrays.copyOf(MaterialRuntime.with(MaterialRuntime.active(),FluidPresetCatalog.NETWORK_PACKAGE,()->V3PengRobinsonThermo.fromRegisteredPackage(FluidPresetCatalog.NETWORK_PACKAGE).crudeFeed("createcheme:tia_juana_light_methane").moleFractions()),com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.conservedCount());composition[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.nitrogenIndex()]=.1;composition[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.waterIndex()]=.2;
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var registrations=new LinkedHashMap<Long,WorldTopologyLedger.Registration>();var stocks=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();var reservoirs=new ArrayList<Long>();
        var ladderOf=new HashMap<Long,Ladder>();
        class Builder {
            long next=1;int pipes;Ladder ladder;int network=-1;final Map<Integer,Map<Kind,Long>> firstOf=new HashMap<>();
            void add(int x,int z,Kind kind,double pressure){add(x,z,kind,pressure,1,.05,1);}
            void add(int x,int z,Kind kind,double pressure,double length,double diameter,double volume) {
                long id=next++;var device=new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",1024+x,80,1024+z),kind,PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(length,diameter,.000045,0),new FlowControl.Passive());devices.add(device);
                if(ladder!=null)ladderOf.put(id,ladder);
                if(network>=0)firstOf.computeIfAbsent(network,ignored->new EnumMap<>(Kind.class)).putIfAbsent(kind,id);
                var spec=new FluidDeviceSpec(volume,350,pressure,composition);registrations.put(id,new WorldTopologyLedger.Registration(device,spec,0));
                if(kind==Kind.PIPE){pipes++;return;}
                if(kind==Kind.RESERVOIR) {
                    // Divide first, then scale: a 1 m3 reservoir keeps the exact amounts of the original stress100 fixture.
                    var amounts=composition.clone();var unit=model.flashTP(350,pressure,amounts,()->{});for(int c=0;c<model.componentCount();c++)amounts[c]=amounts[c]/unit.volume()*volume;var state=model.flashTP(350,pressure,amounts,()->{});
                    stocks.put(id,new PassiveNetwork.Reservoir(id,80,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(volume,amounts,state.internalEnergy())));reservoirs.add(id);
                }else stocks.put(id,spec.initialize(device,model,()->{}));
            }
            /** stress100 builds 100 THROUGH ladders in exactly this device order and random sequence. */
            void ladder(int network,Ladder kind,Random random) {
                ladder=kind;this.network=network;
                int count=10+(network*13%21),columns=count/2,baseX=15360+80*(network%10),baseZ=15360+16*(network/10);
                boolean fill=kind==Ladder.TRANSIENT;double volume=fill?TRANSIENT_RESERVOIR_VOLUME:1,diameter=fill?TRANSIENT_PIPE_DIAMETER:.05;
                if(kind!=Ladder.CLOSED) {
                    add(baseX-8,baseZ,Kind.GENERATOR,fill?TRANSIENT_GENERATOR_PRESSURE:150100);
                    for(int x=-7;x<0;x++)add(baseX+x,baseZ,x==-4&&count%2==1?Kind.RESERVOIR:Kind.PIPE,150090,fill?TRANSIENT_FEED_LENGTH/7:1,diameter,volume);
                }
                for(int c=0;c<columns;c++) {
                    double pressure=150100-100*(c+.5)/columns;
                    for(int row=0;row<2;row++) {
                        add(baseX+4*c,baseZ+4*row,Kind.RESERVOIR,pressure+4*(random.nextDouble()-.5),1,diameter,volume);
                        if(c+1<columns)for(int dx=1;dx<4;dx++)add(baseX+4*c+dx,baseZ+4*row,Kind.PIPE,pressure,1,diameter,volume);
                    }
                    for(int dz=1;dz<4;dz++)add(baseX+4*c,baseZ+dz,Kind.PIPE,pressure,1,diameter,volume);
                }
                if(kind==Ladder.THROUGH) {
                    int end=baseX+4*(columns-1);
                    for(int dx=1;dx<4;dx++)add(end+dx,baseZ+4,Kind.PIPE,150010);
                    add(end+4,baseZ+4,Kind.VOID,150000);
                }
                ladder=null;this.network=-1;
            }
        }
        var builder=new Builder();
        if(TIMED_PROFILES.contains(profile)) {
            var random=new Random(2026091603L);
            for(int network=0;network<100;network++)builder.ladder(network,switch(profile){
                case "transient100"->Ladder.TRANSIENT;case "rest100"->Ladder.CLOSED;
                case "mixed100","viewers"->network%4<2?Ladder.TRANSIENT:network%4==2?Ladder.THROUGH:Ladder.CLOSED;
                default->Ladder.THROUGH;},random);
        }
        else if(profile.equals("many")||profile.equals("contention"))for(int row=0;row<100;row++){builder.add(0,4*row,Kind.GENERATOR,150100);for(int x=1;x<=5;x++)builder.add(x,4*row,Kind.PIPE,150050);builder.add(6,4*row,Kind.RESERVOIR,150050);for(int x=7;x<=11;x++)builder.add(x,4*row,Kind.PIPE,150050);builder.add(12,4*row,Kind.VOID,150000);}
        else {
            int x=0;builder.add(x,0,Kind.GENERATOR,150100);
            for(int i=0;i<100;i++) {
                if(profile.equals("module")&&i==50)x+=3;
                else for(int pipe=0;pipe<(i==0?(profile.equals("module")?15:5):10);pipe++)builder.add(++x,0,Kind.PIPE,150050);
                builder.add(++x,0,Kind.RESERVOIR,150100-100*(i+.5)/100.0);
            }
            for(int pipe=0;pipe<5;pipe++)builder.add(++x,0,Kind.PIPE,150050);builder.add(++x,0,Kind.VOID,150000);
        }
        boolean timed=TIMED_PROFILES.contains(profile);
        if(!timed&&(builder.pipes!=1000||reservoirs.size()!=100))throw new IllegalStateException("Benchmark component count mismatch");
        var compiled=PhysicalFluidTopology.compile(devices,stocks);var entries=new ArrayList<FluidCheckpointCodec.IslandEntry>();var ownerByNode=new HashMap<Long,Long>();long next=builder.next;
        if(timed&&(compiled.islands().size()!=100||compiled.islands().stream().anyMatch(i->{long count=i.graph().reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).count();return count<10||count>30;})))throw new IllegalStateException("Timed fixture must contain 100 isolated 10-30-reservoir islands");
        var islandLadders=new HashMap<Long,Ladder>();
        for(var island:compiled.islands()) {
            if(island.error().isPresent())throw new IllegalStateException(island.error().orElseThrow());long id=next++;
            var kinds=island.physicalIds().stream().map(ladderOf::get).filter(Objects::nonNull).distinct().toList();
            if(timed&&kinds.size()!=1)throw new IllegalStateException("A fixture island must come from exactly one ladder");
            if(timed)islandLadders.put(id,kinds.getFirst());
            var snapshot=new IslandCoordinator.Snapshot(id,0,island.graph(),new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY");
            entries.add(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",FluidPresetCatalog.NETWORK_PACKAGE,1e-9,snapshot));for(var node:island.graph().reservoirs())ownerByNode.put(node.id(),id);
        }
        var buffers=new LinkedHashMap<UUID,BufferedTransfers.Buffer>();var bindings=new ArrayList<CausalModuleCoordinator.Binding>();var modules=new ArrayList<FixedSplitModule.Snapshot>();
        if(profile.equals("module")) {
            for(int index:new int[]{49,50,51}){long node=reservoirs.get(index);var id=new UUID(9,node);buffers.put(id,new BufferedTransfers.Buffer(id,10000,stocks.get(node).state().mass(),Map.of()));bindings.add(new CausalModuleCoordinator.Binding(id,ownerByNode.get(node),node));}
            double[] fractions=new double[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.conservedCount()];Arrays.fill(fractions,1);
            var definition=new FixedSplitModule.Definition(new UUID(9,999999),List.of(new FixedSplitModule.Feed(bindings.getFirst().buffer(),.02)),bindings.get(1).buffer(),bindings.get(2).buffer(),300,fractions);
            modules.add(new FixedSplitModule.Snapshot(definition,0,0,false,null));
        }
        double[] weights=weights(model);var finite=stocks.values().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).toList();
        var topology=new WorldTopologyLedger.Snapshot(0,next,registrations,List.of(),WorldTopologyLedger.MaterialTotal.empty().plus(finite,weights),WorldTopologyLedger.MaterialTotal.empty());
        var checkpoint=new FluidCheckpointCodec.Checkpoint(entries,new BufferedTransfers.Snapshot(0,buffers,Map.of()),modules,bindings);
        var chunks=new HashSet<Long>();var level=server.overworld();
        for(var device:devices) {
            // The timed profiles isolate the persistent engine and keep every fixture chunk unloaded; viewers loads them all.
            if(timed&&!profile.equals("viewers"))continue;
            var p=device.position();var pos=new BlockPos(p.x(),p.y(),p.z());var chunk=new ChunkPos(pos);if(chunks.add(chunk.toLong()))level.setChunkForced(chunk.x,chunk.z,true);
            var block=switch(device.kind()){case RESERVOIR->ModBlocks.FLUID_RESERVOIR.get();case GENERATOR->ModBlocks.FLUID_GENERATOR.get();case VOID->ModBlocks.FLUID_VOID.get();default->ModBlocks.FLUID_PIPE.get();};
            level.setBlock(pos,block.defaultBlockState(),3);
        }
        savedData=new FluidSavedData(checkpoint,topology,key->model);level.getDataStorage().set(FluidSavedData.DATA_NAME,savedData);
        var viewers=new ArrayList<ViewerTarget>();
        if(profile.equals("viewers"))for(int network=0;network<16;network++) {
            var first=builder.firstOf.get(network);boolean closed=network%4==3;
            Kind kind=network==3?Kind.PIPE:closed?Kind.RESERVOIR:Kind.GENERATOR;long id=first.get(kind);
            var p=registrations.get(id).device().position();
            viewers.add(new ViewerTarget(network,id,kind,new BlockPos(p.x(),p.y(),p.z()),network==0||network==2||network==3?"ACCEPTED":network==1?"INVALID":"STALE"));
        }
        fixture=new Fixture(model,checkpoint,topology,Set.copyOf(chunks),builder.pipes,compiled.islands().stream().mapToInt(i->i.graph().pipes().size()).sum(),Map.copyOf(islandLadders),List.copyOf(viewers));
    }

    @GameTest(template="empty",timeoutTicks=200000,batch="paced-benchmark")
    public static void pacedServerQualification(GameTestHelper helper) {
        if(!Boolean.getBoolean("createcheme.fluid.benchmark")||fixture==null){helper.fail("Run this fixture with fluidServerBenchmark");return;}
        if(Boolean.getBoolean("createcheme.fluid.stress.profile"))try {
            recording=new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));recording.start();
        }catch(java.io.IOException|java.text.ParseException failure){throw new IllegalStateException("Cannot start stress profile",failure);}
        run=new Run(helper);
        helper.startSequence().thenWaitUntil(()->{
            helper.assertTrue(run.enoughSamples(),"Collecting paced intervals: "+run.samples.size()+" (target "+run.targetIntervals+" per island)");
        }).thenExecute(()->finish(run)).thenWaitUntil(()->helper.assertTrue(run.probed,"Waiting one cadence for the next save"))
        .thenExecute(()->complete(run)).thenSucceed();
    }
    public static void beforeTick(ServerTickEvent.Pre event) {
        if(run==null||run.finished)return;long now=System.nanoTime();
        run.viewerTick();run.contentionTick();run.stressTick();run.memoryTick(false);run.referenceTick();
        if(run.measuring()&&run.previousTickStarted!=0)run.tickSpacingMillis.add((now-run.previousTickStarted)/1e6);
        run.tickStarted=now;run.previousTickStarted=now;
        run.eligibleTickStarts.put(run.world.onlineTick()+1,now);if(run.eligibleTickStarts.size()>8192)run.eligibleTickStarts.pollFirstEntry();
    }
    public static void afterTick(ServerTickEvent.Post event) {
        if(run==null)return;
        if(run.finished) {
            // After the window, still paced: one cadence of ordinary running, then the next save.
            if(!run.probing)return;
            if(run.world.onlineTick()>=run.probeTick){run.probing=false;run.saves.put("nextCadence",save(run,false));run.probed=true;return;}
            pace(run,event.getServer());return;
        }
        run.referenceTick();long now=System.nanoTime(),meter=FluidRuntimeMeter.totalNanos(event.getServer());
        if(run.measuring()) {
            if(run.measuredStarted==0) {
                run.measuredStarted=now;run.measureStartTick=run.world.onlineTick();run.measuredStartEpochMillis=System.currentTimeMillis();
                // Warm-up work is not the production steady state; start the counters here.
                if(run.diagnosticsEnabled){SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;}
                FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;
            } else run.countTick();
            run.engineMillis.add((meter-run.lastMeter)/1e6);if(run.tickStarted!=0)run.tickMillis.add((now-run.tickStarted)/1e6);
        }
        run.lastMeter=meter;
        pace(run,event.getServer());
    }
    /** GameTestServer normally ticks unpaced. Service its real server mailbox while waiting;
     * short solver completions can therefore refill workers between 20-TPS ticks. */
    private static void pace(Run r,MinecraftServer server) {
        long now=System.nanoTime();
        if(r.nextTick==0||now>r.nextTick+TICK_NANOS)r.nextTick=now+TICK_NANOS;else r.nextTick+=TICK_NANOS;
        long deadline=r.nextTick;server.managedBlock(()->System.nanoTime()>=deadline);
    }
    /**
     * One save of the whole fixture through the world's saved data, as an autosave makes it: the capture (which
     * materialises certified islands) and the encoding, timed on the server thread, with the bytes each part wrote
     * and the size the tag compresses to on disk (the compression is not timed: it is the file writer's).
     * {@code cold} forgets the cached payloads first.
     */
    private static Map<String,Object> save(Run r,boolean cold) {
        if(cold)savedData.clearPayloadCache();
        var tag=savedData.save(new net.minecraft.nbt.CompoundTag(),r.server.registryAccess());var timing=savedData.lastSave();
        var result=new LinkedHashMap<String,Object>();
        result.put("onlineTick",r.world.onlineTick());result.put("cacheClearedFirst",cold);
        result.put("totalMilliseconds",timing.totalNanos()/1e6);result.put("captureMilliseconds",timing.captureNanos()/1e6);result.put("encodeMilliseconds",timing.encodeNanos()/1e6);
        result.put("islands",timing.islands());result.put("payloadsEncoded",timing.payloadsEncoded());result.put("payloadsReused",timing.payloadsReused());
        result.put("payloadBytes",timing.payloadBytes());result.put("ledgerBytes",timing.ledgerBytes());result.put("topologyBytes",timing.topologyBytes());result.put("bytes",timing.bytes());
        try{var out=new java.io.ByteArrayOutputStream();var root=new net.minecraft.nbt.CompoundTag();root.put("data",tag);net.minecraft.nbt.NbtIo.writeCompressed(root,out);result.put("compressedBytes",out.size());}
        catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
        return result;
    }
    private static void finish(Run r) {
        // Close the counters before anything else, so the readout covers the window and nothing after it.
        if(r.diagnosticsEnabled){SolverDiagnostics.ENABLED=false;r.diagnostics=SolverDiagnostics.sample();}
        FluidRuntimeDiagnostics.ENABLED=false;
        // Certified islands are materialised to now while the observer still listens, so the replayed spans reach
        // the boundary ledger before the final state is read (the capture below then has nothing left to advance).
        r.world.materialiseAll();
        r.finished=true;r.world.observe(null);r.memoryTick(true);long now=System.nanoTime();
        Long heapAfterForcedGc=null;
        if(r.memoryEnabled){System.gc();System.gc();heapAfterForcedGc=r.memoryBean.getHeapMemoryUsage().getUsed();}
        var certificates=r.world.certificates();
        var finalIslands=r.world.diagnosticSnapshots();
        var worker=r.samples.stream().map(Sample::timing).filter(m->m.workerNanos()>=0).map(m->m.workerNanos()/1e6).toList();
        var latency=r.samples.stream().map(s->s.timing().dispatchToPublicationNanos()/1e6).toList();long held=r.samples.stream().filter(s->!s.timing().accepted()).count();
        var endToEnd=r.samples.stream().map(Sample::readyToPublicationMillis).filter(Objects::nonNull).toList();
        var finalState=r.world.capture().checkpoint();var initial=totals(fixture.checkpoint,fixture.model);var actual=totals(finalState,fixture.model);double maximumBalance=0;
        for(int c=0;c<r.external.length;c++)maximumBalance=Math.max(maximumBalance,Math.abs(actual[c]-initial[c]-r.external[c])/(1e-10+1e-8*Math.max(Math.abs(initial[c]),Math.abs(actual[c]))));
        double energyError=Math.abs(actual[actual.length-1]-initial[initial.length-1]-r.externalEnergy-r.pumpWork)/(1e-4+1e-6*(Math.abs(initial[initial.length-1])+Math.abs(r.externalEnergy)+Math.abs(r.pumpWork)));
        Double workerP95=percentile(worker,.95),serverP95=percentile(r.engineMillis,.95);
        long approximate=r.samples.stream().filter(s->s.acceptance()==PassiveStepSolver.Acceptance.APPROXIMATE).count();
        long finalDebt=finalState.islands().stream().mapToLong(i->i.snapshot().clock().onlineTick()-i.snapshot().clock().committedTick()).max().orElse(0);
        boolean latencyPass=r.contention?r.competing.passed()&&r.maximumDebtTicks>=800&&finalDebt<=100&&r.bounded:endToEnd.size()==r.samples.size()&&percentile(endToEnd,.95)<2000;
        boolean pass=held==0&&approximate==0&&maximumBalance<=1&&energyError<=1&&workerP95!=null&&workerP95<2000&&latencyPass&&serverP95!=null&&serverP95<2&&!Boolean.getBoolean("createcheme.fluid.benchmark.testFailure");
        var report=new LinkedHashMap<String,Object>();report.put("status",r.pilot?(pass?"PILOT_PASSED_NOT_QUALIFICATION":"PILOT_FAILED"):r.contention?(pass?"CONTENTION_PASSED":"CONTENTION_FAILED"):pass?"REPLICATE_PASSED":"REPLICATE_FAILED");report.put("gatesPassed",pass);report.put("approximateIntervals",approximate);
        if(r.contention){report.put("competingJobs",r.competing.report());report.put("contentionSamples",r.contentionSamples);report.put("maximumDebtTicks",r.maximumDebtTicks);report.put("finalDebtTicks",finalDebt);report.put("maximumOutstandingJobs",r.maximumOutstanding);report.put("maximumReadyJobs",r.maximumReady);report.put("boundedQueues",r.bounded);report.put("contentionNote","Every shared worker occupied by an actual 45-second CPU kernel. Queue latency is reported without applying the ordinary-load two-second gate. Fluid work must recover within ten seconds of terminal delivery.");}
        report.put("profile",System.getProperty("createcheme.fluid.benchmark.profile"));report.put("processId",ProcessHandle.current().pid());report.put("java",System.getProperty("java.runtime.version"));report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));report.put("processorIdentifier",System.getenv("PROCESSOR_IDENTIFIER"));report.put("availableProcessors",Runtime.getRuntime().availableProcessors());
        report.put("jvmArguments",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        try {report.put("manifest",com.google.gson.JsonParser.parseString(Files.readString(Path.of(System.getProperty("createcheme.fluid.benchmark.manifest")))));}
        catch(java.io.IOException failure){throw new IllegalStateException("Missing benchmark revision manifest",failure);}
        report.put("measuredIntervalsPerIsland",r.samples.stream().collect(java.util.stream.Collectors.groupingBy(Sample::island,java.util.stream.Collectors.counting())));
        report.put("workers",ProcessSolveServices.diagnostics(r.server).workerCount());report.put("cadenceSeconds",5);report.put("adaptiveCadence",false);report.put("warmupTicks",r.warmupTicks);report.put("warmupSeconds",r.warmupTicks/20.0);report.put("measurementSecondsConfigured",r.stressMeasurementNanos/1e9);report.put("elapsedSeconds",(now-r.startNanos)/1e9);report.put("measuredSeconds",(now-r.measuredStarted)/1e9);
        report.put("reservoirs",100);report.put("physicalPipes",fixture.physicalPipes);report.put("compiledPipes",fixture.compressedPipes);report.put("components",r.external.length);report.put("islands",fixture.checkpoint.islands().size());report.put("loadedFixtureChunks",fixture.chunks.size());report.put("chunkTickets","Explicit test-harness tickets only; simulation engine creates none");
        report.put("propertyRevision",ApproximationAnchor.revision(fixture.model));report.put("compressibility",1e-9);report.put("heldIntervals",held);report.put("componentBalanceToleranceUnits",maximumBalance);report.put("energyBalanceToleranceUnits",energyError);
        report.put("workerMilliseconds",statistics(worker));report.put("dispatchToPublicationMilliseconds",statistics(latency));report.put("engineServerMillisecondsPerTick",statistics(r.engineMillis));report.put("wholeTickMilliseconds",statistics(r.tickMillis));report.put("tickSpacingMilliseconds",statistics(r.tickSpacingMillis));
        report.put("readyToPublicationMilliseconds",statistics(endToEnd));report.put("latencyDefinition","Start of the eligible server tick to atomic publication; includes readiness queue/debt and the cohort barrier. Missing bounded timing history fails qualification.");
        report.put("samples",r.samples);report.put("rawEngineMillisecondsPerTick",r.engineMillis);report.put("rawTickSpacingMilliseconds",r.tickSpacingMillis);
        if(r.memoryEnabled){report.put("memorySamples",r.memorySamples);report.put("startEpochMillis",r.startEpochMillis);report.put("measuredStartEpochMillis",r.measuredStartEpochMillis);
            report.put("memoryNote","One-second JVM observations; heap used includes uncollected garbage. Use JFR after-GC heap and pause durations separately; GC MXBean time is collection time, not necessarily stop-the-world pause time. External process RSS/private bytes are separate from Java heap.");}
        if(r.diagnostics!=null)report.put("solverDiagnostics",diagnostics(r,r.samples.size()-held,(now-r.measuredStarted)/1e9));
        report.put("runtimeCounters",runtimeCounters(r,(now-r.measuredStarted)/1e9));
        if(r.viewers)report.put("presentation",presentation(r));
        var certified=new LinkedHashMap<String,Object>();
        certified.put("note","Rest and steady-flow certificates. Full solves are accepted solved intervals published in the window; replayed and identity-advanced spans are materialisations of STEADY and REST certificates, accounted in the boundary ledger but never solved. The reference state is every island's inventory at one mid-window island tick and the boundary ledger up to it, for comparing a run with certificates against one without.");
        certified.put("restDetection",certificates.enabled());certified.put("stationaryTolerance",certificates.stationaryTolerance());certified.put("inventoryBudget",certificates.inventoryBudget());
        certified.put("maximumIntervals",certificates.maximumIntervals());certified.put("confirmIntervals",certificates.confirmIntervals());certified.put("recheckSeconds",certificates.recheckSeconds());
        certified.put("fullSolves",r.samples.stream().filter(s->s.timing().accepted()).count());
        certified.put("replayedIntervals",r.replayedIntervals);certified.put("replayedSeconds",r.replayedSeconds);
        certified.put("restedIntervals",r.restedIntervals);certified.put("identityAdvancedSeconds",r.restedSeconds);
        var kinds=new TreeMap<String,Long>();for(var island:finalIslands)kinds.merge(island.certificate().map(c->c.kind().name()).orElse("AWAKE"),1L,Long::sum);
        certified.put("finalIslandKinds",kinds);
        // Why the awake islands did not certify at the end: reasons with their numbers masked, and a few verbatim.
        var refusals=new TreeMap<String,Long>();var examples=new ArrayList<String>();
        for(var island:finalIslands)if(island.certificate().isEmpty()){var reason=r.world.certificationRefusal(island.id());
            refusals.merge(reason==null?"none":reason.replaceAll("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?","#"),1L,Long::sum);if(reason!=null&&examples.size()<8)examples.add(island.id()+": "+reason);}
        certified.put("finalRefusals",refusals);certified.put("refusalExamples",examples);
        var verbatim=new TreeMap<Long,String>();for(var island:finalIslands)if(island.certificate().isEmpty())verbatim.put(island.id(),String.valueOf(r.world.certificationRefusal(island.id())));
        certified.put("finalRefusalsVerbatim",verbatim);
        certified.put("evidenceColumns",List.of("endTick","measure","state","component","energy","flow","relativeFlowChange","drift"));
        certified.put("evidence",r.evidence);certified.put("publicationFingerprints",r.fingerprints);
        certified.put("certifiedIslandsPerSecond",r.stressSamples.stream().map(s->s.get("certifiedIslands")).toList());
        // What each certificate says, without the saved interval it carries for a checkpoint.
        certified.put("finalCertificates",finalIslands.stream().filter(s->s.certificate().isPresent()).collect(java.util.stream.Collectors.toMap(s->s.id(),s->{var c=s.certificate().orElseThrow();var v=new LinkedHashMap<String,Object>();
            v.put("kind",c.kind());v.put("sinceTick",c.sinceTick());v.put("baseTick",c.baseTick());v.put("horizonTick",c.horizonTick());v.put("largestFlow",c.largestFlow());return v;},(a,b)->a,TreeMap::new)));
        if(r.stress){var reference=new LinkedHashMap<String,Object>();reference.put("islandTick",r.referenceTick);reference.put("derivation","First island tick on the 100-tick grid at or after the middle of the configured window: warm-up "+r.warmupTicks/20+" s plus half of "+r.stressMeasurementNanos/1_000_000_000L+" s");reference.put("materialised",r.referenceMaterialised);
            reference.put("islandsExact",r.referenceBefore.entrySet().stream().filter(e->e.getValue().tick()==r.referenceTick).count());
            reference.put("before",r.referenceBefore);reference.put("after",r.referenceAfter);certified.put("reference",reference);}
        if(heapAfterForcedGc!=null)certified.put("heapAfterForcedGcBytes",heapAfterForcedGc);
        report.put("certificates",certified);
        report.put("warmupSamples",r.warmupSamples);report.put("warmupHeldIntervals",r.warmupSamples.stream().filter(s->!s.timing().accepted()).count());
        report.put("moduleCommittedTicks",finalState.modules().stream().map(FixedSplitModule.Snapshot::committedTick).toList());report.put("pendingTransferRecords",finalState.transfers().pending().size());report.put("plannedCapacityRecords",finalState.transfers().planned().size());
        report.put("qualificationNote","One fresh-JVM replicate only. Three replicates and one/many/module, worker-count, contention and soak coverage are required. Queue debt is present in each sample and is not hidden in worker timing.");
        if(r.stress) {
            long reservoirCount=fixture.checkpoint.islands().stream().flatMap(i->i.snapshot().graph().reservoirs().stream()).filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).count();
            boolean allAdvanced=finalState.islands().stream().allMatch(i->i.snapshot().clock().committedTick()>0);
            boolean unloaded=fixture.topology.active().values().stream().noneMatch(record->{var p=record.device().position();return r.server.overworld().hasChunkAt(new BlockPos(p.x(),p.y(),p.z()));});
            report.put("status","STRESS_COMPLETED");report.put("qualificationNote","Fixed-duration stress characterization, not an M9 qualification replicate. All failures, lag, warmup and quality are retained; ordinary latency gates are reported separately.");
            report.put("reservoirs",reservoirCount);report.put("fixtureSeed",2026091603L);report.put("topology",switch(r.profile){
                case "transient100"->"100 isolated closed-end ladders, 10-30 finite 1000 m3 reservoirs each, filled by a 200 kPa generator through 100 m of 0.30 m pipe; no void; current-basis wet TJL + nitrogen";
                case "rest100"->"100 isolated closed ladders, 10-30 finite 1 m3 reservoirs each, stress100 rung pressures spread over 100 Pa; no generator or void; current-basis wet TJL + nitrogen";
                case "mixed100"->"100 isolated ladders by network index mod 4: 0-1 transient100 fill, 2 stress100 through-flow, 3 rest100 closed; current-basis wet TJL + nitrogen";
                case "viewers"->"mixed100 with every fixture chunk loaded and bound, 16 open menus on networks 0-15 and scripted edits through the real edit handler; current-basis wet TJL + nitrogen";
                default->"100 isolated ladder networks, 10-30 finite reservoirs each, series rails and parallel rungs; current-basis wet TJL + nitrogen";});
            var byLadder=new TreeMap<String,Object>();
            for(var ladder:Ladder.values()) {
                var ids=fixture.ladders.entrySet().stream().filter(e->e.getValue()==ladder).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());if(ids.isEmpty())continue;
                var own=r.samples.stream().filter(s->ids.contains(s.island())).toList();var summary=new LinkedHashMap<String,Object>();
                summary.put("islands",ids.size());summary.put("measuredIntervals",own.size());summary.put("acceptedIntervals",own.stream().filter(s->s.timing().accepted()).count());
                summary.put("advancedSimulatedSecondsPerWallSecond",own.stream().filter(s->s.timing().accepted()).mapToDouble(s->(s.timing().endTick()-s.timing().startTick())/20.0).sum()/((now-r.measuredStarted)/1e9));
                summary.put("workerMilliseconds",statistics(own.stream().map(Sample::timing).filter(m->m.workerNanos()>=0).map(m->m.workerNanos()/1e6).toList()));
                summary.put("readyToPublicationMilliseconds",statistics(own.stream().map(Sample::readyToPublicationMillis).filter(Objects::nonNull).toList()));
                summary.put("substeps",statistics(own.stream().filter(s->s.timing().accepted()).map(s->(double)s.substeps()).toList()));
                byLadder.put(ladder.name(),summary);
            }
            report.put("ladders",byLadder);
            if(r.fillGaps.size()>=2) {
                var first=r.fillGaps.getFirst();var last=r.fillGaps.getLast();var fill=new LinkedHashMap<String,Object>();
                fill.put("definition","Mean over transient ladders of generator pressure minus mean reservoir pressure, against mean committed simulated seconds; time constant assumes exponential approach over the measurement window");
                fill.put("firstSample",Map.of("committedSeconds",first[0],"gapPascal",first[1]));fill.put("lastSample",Map.of("committedSeconds",last[0],"gapPascal",last[1]));
                fill.put("timeConstantSeconds",last[1]<first[1]&&last[0]>first[0]?(last[0]-first[0])/Math.log(first[1]/last[1]):null);
                fill.put("samples",r.fillGaps);report.put("transientFill",fill);
            }
            report.put("reservoirsPerIsland",fixture.checkpoint.islands().stream().collect(java.util.stream.Collectors.toMap(i->i.snapshot().id(),i->i.snapshot().graph().reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).count())));
            boolean loaded=fixture.topology.active().values().stream().allMatch(record->{var p=record.device().position();return r.server.overworld().hasChunkAt(new BlockPos(p.x(),p.y(),p.z()));});
            report.put("stressSamples",r.stressSamples);report.put("allFixtureChunksUnloaded",unloaded);report.put("allFixtureChunksLoaded",loaded);report.put("everyIslandAdvanced",allAdvanced);
            double seconds=(now-r.measuredStarted)/1e9;
            report.put("completedIntervalsPerSecond",r.samples.stream().filter(s->s.timing().accepted()).count()/seconds);
            double advancedSeconds=r.samples.stream().filter(s->s.timing().accepted()).mapToDouble(s->(s.timing().endTick()-s.timing().startTick())/20.0).sum();
            report.put("equivalentFiveSecondIntervalsPerSecond",advancedSeconds/(5*seconds));
            report.put("aggregateRealtimeRatio",advancedSeconds/(fixture.checkpoint.islands().size()*seconds));
            report.put("aggregateRealtimeRatioIncludingCertified",(advancedSeconds+r.replayedSeconds+r.restedSeconds)/(fixture.checkpoint.islands().size()*seconds));
            report.put("workerCpuOccupancyNote","Lower bound from outcomes retained before their deadline; timed-out outcomes can omit CPU. Use the JFR CPU-load trace for process utilization, including rejected work.");
            report.put("meanWorkerCpuOccupancy",r.samples.stream().mapToLong(s->Math.max(0,s.timing().workerCpuNanos())).sum()/(seconds*1e9*ProcessSolveServices.diagnostics(r.server).workerCount()));
            report.put("meanReportedActiveWorkers",r.stressSamples.stream().mapToInt(s->((Number)s.get("activeWorkers")).intValue()).average().orElse(0));
            report.put("finalDebtSeconds",statistics(finalState.islands().stream().map(i->(i.snapshot().clock().onlineTick()-i.snapshot().clock().committedTick())/20.0).toList()));
            pass=maximumBalance<=1&&energyError<=1&&allAdvanced&&(r.viewers?loaded&&r.presentationPassed:unloaded);
            report.put("stressIntegrityPassed",pass);
        }
        // Save time (plan section 6): the whole fixture at the end of the window, once with the payload cache cleared
        // and once right after (a second consecutive save), then once more one cadence later, after the paced ticks
        // in between (where awake islands have solved and certified ones have only been materialised).
        r.saves.put("cold",save(r,true));r.saves.put("warm",save(r,false));
        r.report=report;r.pass=pass;r.probeTick=r.world.onlineTick()+100;r.probing=true;
    }
    /** Writes the report once the post-window save was taken, and releases the fixture. */
    private static void complete(Run r) {
        var saveTime=new LinkedHashMap<String,Object>();
        saveTime.put("note","Checkpoint format 3 saves of the whole fixture through the world's saved data, timed on the server thread: capture (materialises certified islands) plus encoding. cold: payload cache cleared first, at the end of the window; warm: the second consecutive save, same tick; nextCadence: one save 100 paced ticks later, after awake islands solved again. compressedBytes is the gzip NBT size a file write would produce, not timed.");
        saveTime.putAll(r.saves);r.report.put("saveTime",saveTime);
        try {var path=Path.of(System.getProperty("createcheme.fluid.benchmark.output"));Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(r.report));
            if(recording!=null){recording.stop();recording.dump(path.resolveSibling("stress.jfr"));recording.close();recording=null;}}
        catch(java.io.IOException failure){throw new IllegalStateException("Could not write benchmark evidence",failure);}
        for(long chunk:fixture.chunks){var p=new ChunkPos(chunk);r.server.overworld().setChunkForced(p.x,p.z,false);}FluidRuntimeMeter.forget(r.server);
        r.helper.assertTrue(r.pass,"Paced benchmark failed; inspect the complete raw report");
    }
    /**
     * The {@link SolverDiagnostics} readout for the measurement window. Counts and nanosecond totals
     * are summed over every worker, so the nanosecond figures are occupied thread time and their sum
     * legitimately exceeds the window's wall duration. The per-interval means divide by the accepted
     * intervals published inside the window; work belonging to held intervals, to the jobs that
     * straddle the window's edges, and to intervals published after it is counted in the totals but
     * has no interval of its own here.
     */
    private static Map<String,Object> diagnostics(Run r,long acceptedIntervals,double measuredSeconds) {
        var counters=new LinkedHashMap<String,Object>();var perInterval=new LinkedHashMap<String,Object>();
        for(var name:SolverDiagnostics.names()) {
            long value=r.diagnostics.value(name);counters.put(name,value);
            perInterval.put(name,acceptedIntervals>0?value/(double)acceptedIntervals:null);
        }
        var dominant=new TreeMap<String,Long>();
        for(var attempt:r.diagnostics.attempts())dominant.merge(attempt.accepted()?attempt.dominant():attempt.dominant()+"-rejected",1L,Long::sum);
        var result=new LinkedHashMap<String,Object>();
        result.put("note","Summed over all solver workers inside the measurement window; nanosecond totals are occupied thread time, not wall time.");
        result.put("measuredSeconds",measuredSeconds);result.put("acceptedIntervals",acceptedIntervals);
        result.put("workers",ProcessSolveServices.diagnostics(r.server).workerCount());
        result.put("counters",counters);result.put("perAcceptedInterval",perInterval);
        result.put("recordedAttempts",r.diagnostics.attempts().size());
        result.put("attemptLogTruncated",r.diagnostics.attempts().size()>=SolverDiagnostics.MAXIMUM_ATTEMPTS);
        result.put("attemptDominantTerms",dominant);
        return result;
    }
    /**
     * The {@link FluidRuntimeDiagnostics} readout for the measurement window, as totals, per wall second and
     * per server tick, and the same over idle ticks only. The harness's own world reads are paused out.
     */
    /**
     * The viewers profile's presentation readout over the measurement window: deliveries per menu, packets and view
     * builds per 100 ticks per consumer, bucket flushes, and every scripted edit with its reply: the tick it was
     * received, the first delivery after it (which must carry the reply), the latency in ticks and the reply.
     */
    private static Map<String,Object> presentation(Run r) {
        long start=r.measureStartTick,end=r.world.onlineTick();double hundreds=(end-start)/100.0;
        var result=new LinkedHashMap<String,Object>();
        result.put("note","Presentation over the measurement window (online ticks start, end]. Consumers are the open menus and the loaded, bound devices. An edit's reply must be on the first delivery its menu receives after the tick the edit arrived in; an ACCEPTED edit's event must later be reported Applied. Latency is the delivery tick minus the arrival tick.");
        result.put("windowStartTick",start);result.put("windowEndTick",end);result.put("windowTicks",end-start);
        var stats=r.world.presentationStats();int menus=r.viewerMenus.size();int loaded=stats.loadedDevices();
        result.put("openMenus",stats.menus());result.put("loadedDevices",loaded);result.put("dirtyDevicesAtEnd",stats.dirtyDevices());
        java.util.function.ToLongFunction<String> total=name->r.counterTotals[r.counterNames.indexOf(name)];
        long live=0,statics=0,offBucket=0;var perMenu=new ArrayList<Map<String,Object>>();
        var logs=new HashMap<Integer,List<FluidPresentation.Delivery>>();
        for(int i=0;i<menus;i++) {
            var m=r.viewerMenus.get(i);var log=r.world.deliveries(m.menu());logs.put(i,log);
            var window=log.stream().filter(d->d.tick()>start&&d.tick()<=end).toList();long s=window.stream().filter(FluidPresentation.Delivery::withStatic).count();
            live+=window.size();statics+=s;for(var d:log)if(Math.floorMod(d.tick(),100L)!=d.bucket())offBucket++;
            var row=new LinkedHashMap<String,Object>();row.put("network",m.target().network());row.put("device",m.target().device());row.put("kind",m.target().kind().name());row.put("role",m.target().role());
            row.put("deliveriesInWindow",window.size());row.put("staticInWindow",s);row.put("deliveriesPer100Ticks",window.size()/hundreds);
            row.put("buckets",window.stream().map(FluidPresentation.Delivery::bucket).distinct().toList());row.put("totalDeliveries",log.size());row.put("firstDeliveryTick",log.isEmpty()?null:log.getFirst().tick());
            row.put("lastStatus",log.isEmpty()?null:log.getLast().status());
            perMenu.add(row);
        }
        result.put("menus",perMenu);
        result.put("livePacketsPer100TicksPerMenu",live/hundreds/menus);result.put("staticPacketsPer100TicksPerMenu",statics/hundreds/menus);
        result.put("menuPacketsPer100TicksPerMenu",total.applyAsLong("menuPackets")/hundreds/menus);
        result.put("viewBuildsPer100TicksPerConsumer",total.applyAsLong("viewBuilds")/hundreds/(menus+loaded));
        result.put("devicePresentationsPer100TicksPerLoadedDevice",loaded==0?null:total.applyAsLong("devicePresentations")/hundreds/loaded);
        result.put("bucketFlushes",total.applyAsLong("bucketFlushes"));result.put("bucketFlushesPer100Ticks",total.applyAsLong("bucketFlushes")/hundreds);
        result.put("deliveriesOffTheirBucket",offBucket);
        var edits=new ArrayList<Map<String,Object>>();var latencies=new ArrayList<Double>();var histogram=new TreeMap<Long,Long>();
        long unanswered=0,notOnFollowingBucket=0,wrongReply=0,immediate=0,neverApplied=0;var replies=new TreeMap<String,Long>();
        for(var edit:r.scriptedEdits) {
            var log=logs.get(edit.menu());var after=log.stream().filter(d->d.tick()>edit.receivedTick()).toList();
            var row=new LinkedHashMap<String,Object>();row.put("network",edit.network());row.put("role",edit.role());row.put("receivedTick",edit.receivedTick());row.put("revision",edit.revision());
            immediate+=log.stream().filter(d->d.tick()==edit.receivedTick()&&!d.reply().isEmpty()).count();
            if(after.isEmpty()){unanswered++;row.put("reply",null);edits.add(row);continue;}
            var first=after.getFirst();String reply=first.reply();long latency=first.tick()-edit.receivedTick();
            row.put("replyTick",first.tick());row.put("latencyTicks",latency);row.put("reply",reply);
            if(reply.isEmpty())notOnFollowingBucket++;
            boolean expected=switch(edit.role()){case "ACCEPTED"->reply.startsWith(FluidPresentation.QUEUED)||reply.equals(FluidPresentation.APPLIED);
                case "INVALID"->reply.equals(FluidPresentation.REFUSED+"Controls are outside the supported range");default->reply.startsWith(FluidPresentation.REFUSED);};
            if(!expected)wrongReply++;
            if(edit.role().equals("ACCEPTED")) {
                var applied=after.stream().filter(d->d.reply().contains(FluidPresentation.APPLIED)).findFirst();
                row.put("appliedReplyTick",applied.map(FluidPresentation.Delivery::tick).orElse(null));if(applied.isEmpty())neverApplied++;
            }
            replies.merge(reply.replaceAll("\\d+","#"),1L,Long::sum);
            latencies.add((double)latency);histogram.merge(latency/10*10,1L,Long::sum);edits.add(row);
        }
        result.put("edits",edits);result.put("editCount",r.scriptedEdits.size());result.put("replyTexts",replies);
        result.put("latencyTicks",statistics(latencies));result.put("latencyHistogramTenTickBins",histogram);
        result.put("latencyMinimumTicks",latencies.stream().mapToDouble(Double::doubleValue).min().orElse(-1));
        result.put("unanswered",unanswered);result.put("repliesNotOnTheFollowingBucket",notOnFollowingBucket);result.put("wrongReplies",wrongReply);
        result.put("repliesAtTheInputTick",immediate);result.put("acceptedEditsNeverApplied",neverApplied);
        double perMenu100=live/hundreds/menus;
        boolean passed=unanswered==0&&notOnFollowingBucket==0&&wrongReply==0&&immediate==0&&neverApplied==0&&offBucket==0&&perMenu100>=.95&&perMenu100<=1.05&&!r.scriptedEdits.isEmpty();
        result.put("passed",passed);r.presentationPassed=passed;
        return result;
    }
    private static Map<String,Object> runtimeCounters(Run r,double measuredSeconds) {
        var result=new LinkedHashMap<String,Object>();
        result.put("note","Server-thread scheduling counters over the measurement window. A tick's counts cover its tick hooks and the mailbox work since the previous tick. Idle ticks routed no completion, dispatched no solve, published no island and fired no current scheduler deadline.");
        result.put("measuredSeconds",measuredSeconds);result.put("ticks",r.countedTicks);result.put("idleTicks",r.idleTicks);
        var totals=new LinkedHashMap<String,Object>();var perSecond=new LinkedHashMap<String,Object>();var perTick=new LinkedHashMap<String,Object>();
        var idle=new LinkedHashMap<String,Object>();var idlePerTick=new LinkedHashMap<String,Object>();var idleWithWork=new LinkedHashMap<String,Object>();var maximum=new LinkedHashMap<String,Object>();
        for(int i=0;i<r.counterNames.size();i++) {
            String name=r.counterNames.get(i);totals.put(name,r.counterTotals[i]);perSecond.put(name,r.counterTotals[i]/measuredSeconds);
            perTick.put(name,r.countedTicks>0?r.counterTotals[i]/(double)r.countedTicks:null);maximum.put(name,r.maximumPerTick[i]);
            idle.put(name,r.idleCounterTotals[i]);idlePerTick.put(name,r.idleTicks>0?r.idleCounterTotals[i]/(double)r.idleTicks:null);idleWithWork.put(name,r.idleTicksWithWork[i]);
        }
        result.put("totals",totals);result.put("perSecond",perSecond);result.put("perTick",perTick);result.put("maximumPerTick",maximum);
        result.put("idleTickTotals",idle);result.put("idleTickPerTick",idlePerTick);result.put("idleTicksWithAny",idleWithWork);
        return result;
    }
    private static Double percentile(List<Double> values,double fraction){if(values.isEmpty())return null;var sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(fraction*sorted.size())-1));}
    private static Map<String,Object> statistics(List<Double> values){var result=new LinkedHashMap<String,Object>();result.put("count",values.size());result.put("median",percentile(values,.5));result.put("p95",percentile(values,.95));result.put("max",percentile(values,1));return result;}
    private static double[] weights(FluidThermodynamics model){double[] weights=new double[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.conservedCount()];for(int c=0;c<model.componentCount();c++)weights[c]=c==model.componentCount()-1?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c);return weights;}
    private static double[] totals(FluidCheckpointCodec.Checkpoint checkpoint,FluidThermodynamics model) {
        double[] sum=new double[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.conservedCount()+1],weights=weights(model);for(var island:checkpoint.islands())for(var node:island.snapshot().graph().reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR) {
            var n=node.inventory().moles();double mass=0;for(int c=0;c<model.componentCount();c++){sum[c]+=n[c];mass+=n[c]*weights[c];}sum[sum.length-1]+=node.inventory().internalEnergy()+mass*PassiveStepSolver.GRAVITY*node.elevation();
        }
        for(var p:checkpoint.transfers().pending().values())add(sum,p.remaining());for(var module:checkpoint.modules())if(module.cycle()!=null)for(var input:module.cycle().inputs().values())add(sum,input.owned());return sum;
    }
    private static void add(double[] sum,MaterialParcel parcel){var n=parcel.moles();for(int c=0;c<n.length;c++)sum[c]+=n[c];sum[sum.length-1]+=parcel.energyJoule();}
}

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
    private static Fixture fixture;
    private static Run run;
    private static jdk.jfr.Recording recording;
    private FluidServerBenchmark() {}
    private record Fixture(FluidThermodynamics model,FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot topology,Set<Long> chunks,int physicalPipes,int compressedPipes) {}
    private record Sample(long island,IslandCoordinator.Metrics timing,String status,PassiveStepSolver.Acceptance acceptance,int substeps,int rejectedSubsteps,Double readyToPublicationMillis) {}
    /** Test-only one-second observations. Heap usage includes garbage awaiting collection; it is not retained live size. */
    private record MemorySample(long onlineTick,long epochMillis,double sinceStartSeconds,boolean measured,
                                long heapUsed,long heapCommitted,long heapMax,long nonHeapUsed,
                                long directBufferBytes,long mappedBufferBytes,long gcCount,long gcMillis) {}
    private static final class Run {
        final MinecraftServer server;final GameTestHelper helper;final FluidWorldAuthority world;
        final boolean pilot=Boolean.getBoolean("createcheme.fluid.benchmark.pilot");
        final boolean contention="contention".equals(System.getProperty("createcheme.fluid.benchmark.profile"));
        final boolean stress="stress100".equals(System.getProperty("createcheme.fluid.benchmark.profile"));
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
        FluidContentionProbe competing;long contentionStartTick,contentionFinishedTick,maximumDebtTicks;int maximumOutstanding,maximumReady;boolean bounded=true;
        final List<Map<String,Object>> contentionSamples=new ArrayList<>();
        final List<Map<String,Object>> stressSamples=new ArrayList<>();
        Run(GameTestHelper helper){
            this.helper=helper;server=helper.getLevel().getServer();world=FluidWorldAuthority.find(server).orElseThrow();startTick=world.onlineTick();FluidRuntimeMeter.enable(server);world.observe(this::published);
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
                    measuredStarted!=0,heap.getUsed(),heap.getCommitted(),heap.getMax(),memoryBean.getNonHeapMemoryUsage().getUsed(),direct,mapped,count,millis));
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
            var diagnostics=ProcessSolveServices.diagnostics(server);
            var snapshots=world.capture().checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList();
            var debts=snapshots.stream().map(s->(s.clock().onlineTick()-s.clock().committedTick())/20.0).toList();
            long eligible=snapshots.stream().filter(s->s.clock().onlineTick()-s.clock().committedTick()>=s.clock().cadenceTicks()).count();
            stressSamples.add(Map.of("onlineTick",world.onlineTick(),"activeWorkers",diagnostics.activeWorkers(),
                    "workerLimit",diagnostics.workerCount(),
                    "outstandingJobs",diagnostics.outstandingJobs(),"readyJobs",diagnostics.readyJobs(),"pendingCompletions",diagnostics.pendingCompletions(),
                    "eligibleIslands",eligible,"debtSeconds",statistics(debts),"wallSeconds",(System.nanoTime()-measuredStarted)/1e9));
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
                var ready=eligibleTickStarts.get(timing.endTick());Double totalLatency=ready==null?null:(System.nanoTime()-ready)/1e6;
                var sample=new Sample(island.id(),timing,island.status(),result==null?null:result.acceptance(),result==null?0:result.acceptedSubsteps(),result==null?0:result.rejectedSubsteps(),totalLatency);
                if(measuring())samples.add(sample);else if(warmupSamples.size()<10000)warmupSamples.add(sample);
            }
        }
    }

    public static void installFixture(ServerStartingEvent event) {
        var server=event.getServer();String profile=System.getProperty("createcheme.fluid.benchmark.profile","one");
        if(!Set.of("one","many","module","contention","stress100").contains(profile))throw new IllegalArgumentException("Unknown benchmark profile "+profile);
        if(Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/"+FluidSavedData.DATA_NAME+".dat")))throw new IllegalStateException("Benchmark requires a fresh disposable world");
        var model=FluidThermodynamics.forNetwork(MaterialRuntime.active(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var composition=Arrays.copyOf(MaterialRuntime.with(MaterialRuntime.active(),FluidPresetCatalog.NETWORK_PACKAGE,()->V3PengRobinsonThermo.fromRegisteredPackage(FluidPresetCatalog.NETWORK_PACKAGE).crudeFeed("createcheme:tia_juana_light_methane").moleFractions()),com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.conservedCount());composition[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.nitrogenIndex()]=.1;composition[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.waterIndex()]=.2;
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var registrations=new LinkedHashMap<Long,WorldTopologyLedger.Registration>();var stocks=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();var reservoirs=new ArrayList<Long>();
        class Builder {
            long next=1;int pipes;
            void add(int x,int z,Kind kind,double pressure) {
                long id=next++;var device=new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",1024+x,80,1024+z),kind,PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());devices.add(device);
                var spec=new FluidDeviceSpec(1,350,pressure,composition);registrations.put(id,new WorldTopologyLedger.Registration(device,spec,0));
                if(kind==Kind.PIPE){pipes++;return;}
                if(kind==Kind.RESERVOIR) {
                    var amounts=composition.clone();var unit=model.flashTP(350,pressure,amounts,()->{});for(int c=0;c<model.componentCount();c++)amounts[c]/=unit.volume();var state=model.flashTP(350,pressure,amounts,()->{});
                    stocks.put(id,new PassiveNetwork.Reservoir(id,80,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,amounts,state.internalEnergy())));reservoirs.add(id);
                }else stocks.put(id,spec.initialize(device,model,()->{}));
            }
        }
        var builder=new Builder();
        if(profile.equals("stress100")) {
            var random=new Random(2026091603L);
            for(int network=0;network<100;network++) {
                int count=10+(network*13%21),columns=count/2,baseX=15360+80*(network%10),baseZ=15360+16*(network/10);
                builder.add(baseX-8,baseZ,Kind.GENERATOR,150100);
                for(int x=-7;x<0;x++)builder.add(baseX+x,baseZ,x==-4&&count%2==1?Kind.RESERVOIR:Kind.PIPE,150090);
                for(int c=0;c<columns;c++) {
                    double pressure=150100-100*(c+.5)/columns;
                    for(int row=0;row<2;row++) {
                        builder.add(baseX+4*c,baseZ+4*row,Kind.RESERVOIR,pressure+4*(random.nextDouble()-.5));
                        if(c+1<columns)for(int dx=1;dx<4;dx++)builder.add(baseX+4*c+dx,baseZ+4*row,Kind.PIPE,pressure);
                    }
                    for(int dz=1;dz<4;dz++)builder.add(baseX+4*c,baseZ+dz,Kind.PIPE,pressure);
                }
                int end=baseX+4*(columns-1);
                for(int dx=1;dx<4;dx++)builder.add(end+dx,baseZ+4,Kind.PIPE,150010);
                builder.add(end+4,baseZ+4,Kind.VOID,150000);
            }
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
        if(!profile.equals("stress100")&&(builder.pipes!=1000||reservoirs.size()!=100))throw new IllegalStateException("Benchmark component count mismatch");
        var compiled=PhysicalFluidTopology.compile(devices,stocks);var entries=new ArrayList<FluidCheckpointCodec.IslandEntry>();var ownerByNode=new HashMap<Long,Long>();long next=builder.next;
        if(profile.equals("stress100")&&(compiled.islands().size()!=100||compiled.islands().stream().anyMatch(i->{long count=i.graph().reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).count();return count<10||count>30;})))throw new IllegalStateException("Stress fixture must contain 100 isolated 10-30-reservoir islands");
        for(var island:compiled.islands()) {
            if(island.error().isPresent())throw new IllegalStateException(island.error().orElseThrow());long id=next++;
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
            // This profile isolates the persistent engine; all fixture chunks stay unloaded.
            if(profile.equals("stress100"))continue;
            var p=device.position();var pos=new BlockPos(p.x(),p.y(),p.z());var chunk=new ChunkPos(pos);if(chunks.add(chunk.toLong()))level.setChunkForced(chunk.x,chunk.z,true);
            var block=switch(device.kind()){case RESERVOIR->ModBlocks.FLUID_RESERVOIR.get();case GENERATOR->ModBlocks.FLUID_GENERATOR.get();case VOID->ModBlocks.FLUID_VOID.get();default->ModBlocks.FLUID_PIPE.get();};
            level.setBlock(pos,block.defaultBlockState(),3);
        }
        level.getDataStorage().set(FluidSavedData.DATA_NAME,new FluidSavedData(checkpoint,topology,key->model));
        fixture=new Fixture(model,checkpoint,topology,Set.copyOf(chunks),builder.pipes,compiled.islands().stream().mapToInt(i->i.graph().pipes().size()).sum());
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
        }).thenExecute(()->finish(run)).thenSucceed();
    }
    public static void beforeTick(ServerTickEvent.Pre event) {
        if(run==null||run.finished)return;long now=System.nanoTime();
        run.contentionTick();run.stressTick();run.memoryTick(false);
        if(run.measuring()&&run.previousTickStarted!=0)run.tickSpacingMillis.add((now-run.previousTickStarted)/1e6);
        run.tickStarted=now;run.previousTickStarted=now;
        run.eligibleTickStarts.put(run.world.onlineTick()+1,now);if(run.eligibleTickStarts.size()>8192)run.eligibleTickStarts.pollFirstEntry();
    }
    public static void afterTick(ServerTickEvent.Post event) {
        if(run==null||run.finished)return;long now=System.nanoTime(),meter=FluidRuntimeMeter.totalNanos(event.getServer());
        if(run.measuring()) {
            if(run.measuredStarted==0) {
                run.measuredStarted=now;run.measuredStartEpochMillis=System.currentTimeMillis();
                // Warm-up work is not the production steady state; start the counters here.
                if(run.diagnosticsEnabled){SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;}
            }
            run.engineMillis.add((meter-run.lastMeter)/1e6);if(run.tickStarted!=0)run.tickMillis.add((now-run.tickStarted)/1e6);
        }
        run.lastMeter=meter;
        // GameTestServer normally ticks unpaced. Service its real server mailbox while waiting;
        // short solver completions can therefore refill workers between 20-TPS ticks.
        if(run.nextTick==0||now>run.nextTick+TICK_NANOS)run.nextTick=now+TICK_NANOS;else run.nextTick+=TICK_NANOS;
        long deadline=run.nextTick;event.getServer().managedBlock(()->System.nanoTime()>=deadline);
    }
    private static void finish(Run r) {
        // Close the counters before anything else, so the readout covers the window and nothing after it.
        if(r.diagnosticsEnabled){SolverDiagnostics.ENABLED=false;r.diagnostics=SolverDiagnostics.sample();}
        r.finished=true;r.world.observe(null);r.memoryTick(true);long now=System.nanoTime();
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
        report.put("workers",ProcessSolveServices.diagnostics(r.server).workerCount());report.put("cadenceSeconds",5);report.put("adaptiveCadence",false);report.put("warmupTicks",r.warmupTicks);report.put("elapsedSeconds",(now-r.startNanos)/1e9);report.put("measuredSeconds",(now-r.measuredStarted)/1e9);
        report.put("reservoirs",100);report.put("physicalPipes",fixture.physicalPipes);report.put("compiledPipes",fixture.compressedPipes);report.put("components",r.external.length);report.put("islands",fixture.checkpoint.islands().size());report.put("loadedFixtureChunks",fixture.chunks.size());report.put("chunkTickets","Explicit test-harness tickets only; simulation engine creates none");
        report.put("propertyRevision",ApproximationAnchor.revision(fixture.model));report.put("compressibility",1e-9);report.put("heldIntervals",held);report.put("componentBalanceToleranceUnits",maximumBalance);report.put("energyBalanceToleranceUnits",energyError);
        report.put("workerMilliseconds",statistics(worker));report.put("dispatchToPublicationMilliseconds",statistics(latency));report.put("engineServerMillisecondsPerTick",statistics(r.engineMillis));report.put("wholeTickMilliseconds",statistics(r.tickMillis));report.put("tickSpacingMilliseconds",statistics(r.tickSpacingMillis));
        report.put("readyToPublicationMilliseconds",statistics(endToEnd));report.put("latencyDefinition","Start of the eligible server tick to atomic publication; includes readiness queue/debt and the cohort barrier. Missing bounded timing history fails qualification.");
        report.put("samples",r.samples);report.put("rawEngineMillisecondsPerTick",r.engineMillis);report.put("rawTickSpacingMilliseconds",r.tickSpacingMillis);
        if(r.memoryEnabled){report.put("memorySamples",r.memorySamples);report.put("startEpochMillis",r.startEpochMillis);report.put("measuredStartEpochMillis",r.measuredStartEpochMillis);
            report.put("memoryNote","One-second JVM observations; heap used includes uncollected garbage. Use JFR after-GC heap and pause durations separately; GC MXBean time is collection time, not necessarily stop-the-world pause time. External process RSS/private bytes are separate from Java heap.");}
        if(r.diagnostics!=null)report.put("solverDiagnostics",diagnostics(r,r.samples.size()-held,(now-r.measuredStarted)/1e9));
        report.put("warmupSamples",r.warmupSamples);report.put("warmupHeldIntervals",r.warmupSamples.stream().filter(s->!s.timing().accepted()).count());
        report.put("moduleCommittedTicks",finalState.modules().stream().map(FixedSplitModule.Snapshot::committedTick).toList());report.put("pendingTransferRecords",finalState.transfers().pending().size());report.put("plannedCapacityRecords",finalState.transfers().planned().size());
        report.put("qualificationNote","One fresh-JVM replicate only. Three replicates and one/many/module, worker-count, contention and soak coverage are required. Queue debt is present in each sample and is not hidden in worker timing.");
        if(r.stress) {
            long reservoirCount=fixture.checkpoint.islands().stream().flatMap(i->i.snapshot().graph().reservoirs().stream()).filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).count();
            boolean allAdvanced=finalState.islands().stream().allMatch(i->i.snapshot().clock().committedTick()>0);
            boolean unloaded=fixture.topology.active().values().stream().noneMatch(record->{var p=record.device().position();return r.server.overworld().hasChunkAt(new BlockPos(p.x(),p.y(),p.z()));});
            report.put("status","STRESS_COMPLETED");report.put("qualificationNote","Fixed-duration stress characterization, not an M9 qualification replicate. All failures, lag, warmup and quality are retained; ordinary latency gates are reported separately.");
            report.put("reservoirs",reservoirCount);report.put("fixtureSeed",2026091603L);report.put("topology","100 isolated ladder networks, 10-30 finite reservoirs each, series rails and parallel rungs; current-basis wet TJL + nitrogen");
            report.put("reservoirsPerIsland",fixture.checkpoint.islands().stream().collect(java.util.stream.Collectors.toMap(i->i.snapshot().id(),i->i.snapshot().graph().reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).count())));
            report.put("stressSamples",r.stressSamples);report.put("allFixtureChunksUnloaded",unloaded);report.put("everyIslandAdvanced",allAdvanced);
            double seconds=(now-r.measuredStarted)/1e9;
            report.put("completedIntervalsPerSecond",r.samples.stream().filter(s->s.timing().accepted()).count()/seconds);
            double advancedSeconds=r.samples.stream().filter(s->s.timing().accepted()).mapToDouble(s->(s.timing().endTick()-s.timing().startTick())/20.0).sum();
            report.put("equivalentFiveSecondIntervalsPerSecond",advancedSeconds/(5*seconds));
            report.put("aggregateRealtimeRatio",advancedSeconds/(fixture.checkpoint.islands().size()*seconds));
            report.put("workerCpuOccupancyNote","Lower bound from outcomes retained before their deadline; timed-out outcomes can omit CPU. Use the JFR CPU-load trace for process utilization, including rejected work.");
            report.put("meanWorkerCpuOccupancy",r.samples.stream().mapToLong(s->Math.max(0,s.timing().workerCpuNanos())).sum()/(seconds*1e9*ProcessSolveServices.diagnostics(r.server).workerCount()));
            report.put("meanReportedActiveWorkers",r.stressSamples.stream().mapToInt(s->((Number)s.get("activeWorkers")).intValue()).average().orElse(0));
            report.put("finalDebtSeconds",statistics(finalState.islands().stream().map(i->(i.snapshot().clock().onlineTick()-i.snapshot().clock().committedTick())/20.0).toList()));
            pass=maximumBalance<=1&&energyError<=1&&allAdvanced&&unloaded;
            report.put("stressIntegrityPassed",pass);
        }
        try {var path=Path.of(System.getProperty("createcheme.fluid.benchmark.output"));Files.createDirectories(path.getParent());Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(report));
            if(recording!=null){recording.stop();recording.dump(path.resolveSibling("stress.jfr"));recording.close();recording=null;}}
        catch(java.io.IOException failure){throw new IllegalStateException("Could not write benchmark evidence",failure);}
        for(long chunk:fixture.chunks){var p=new ChunkPos(chunk);r.server.overworld().setChunkForced(p.x,p.z,false);}FluidRuntimeMeter.forget(r.server);
        r.helper.assertTrue(pass,"Paced benchmark failed; inspect the complete raw report");
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

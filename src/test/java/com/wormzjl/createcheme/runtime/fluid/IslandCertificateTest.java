package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Rest and steady-flow certificates (plan sections 3.2, 3.3 and 4; section 5 items 3, 5 and 6): the entry
 * evidence, the horizon arithmetic, replay accounting, and the coordinator's certified path with real
 * solves - what certifies and what must not, materialisation at fences, horizon revalidation, wake,
 * property hold, and trajectories against the same island solving every interval.
 */
class IslandCertificateTest {
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final BoundedCpuSolveService.CancellationToken NEVER=new BoundedCpuSolveService.CancellationToken() {
        public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}
        public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}
    };
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final int components=model.components().size();
    private final int water=components-1,nitrogen=MaterialTestBasis.NITROGEN;
    private final int methane=MaterialCatalog.bundled().requirePackage("createcheme:tjl20_methane_nitrogen").components().indexOf("Methane");

    @AfterEach void counters(){FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}

    // ---------------- fixtures ----------------

    private double[] pure(int component){var n=new double[components];n[component]=1;return n;}
    private double[] mix(double methaneFraction){var n=new double[components];n[methane]=methaneFraction;n[nitrogen]=1-methaneFraction;return n;}
    private static PhysicalFluidTopology.Device device(long id,int x,int z,Kind kind,FlowControl control) {
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,0,z),kind,PhysicalFluidTopology.Direction.EAST,BLOCK,control);
    }
    private static PhysicalFluidTopology.Device device(long id,int x,int z,Kind kind){return device(id,x,z,kind,new FlowControl.Passive());}
    /** The one island the world would compile from these blocks, its boundaries freshly initialised. */
    private PassiveNetwork island(List<PhysicalFluidTopology.Device> devices,Map<Long,FluidDeviceSpec> specs) {
        var stock=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var d:devices){var spec=specs.get(d.id());if(spec!=null)stock.put(d.id(),spec.initialize(d,model,()->{}));}
        var compiled=PhysicalFluidTopology.compile(devices,stock);
        var connected=compiled.islands().stream().filter(i->i.physicalIds().size()==devices.size()).toList();
        assertEquals(1,connected.size(),"one connected island expected: "+compiled.diagnostics());
        return connected.getFirst().graph();
    }
    /** A line of blocks along x: the kinds in order, pipes between. */
    private List<PhysicalFluidTopology.Device> line(Kind... kinds) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        for(int i=0;i<kinds.length;i++)devices.add(device(i+1,i,0,kinds[i],kinds[i]==Kind.PUMP?new FlowControl.Pump(.001,100000,1):new FlowControl.Passive()));
        return devices;
    }
    /** A water generator at 1 atm against a nitrogen tank charged at 2 bar: the line cannot flow at all. */
    private PassiveNetwork deadHeadedLine() {
        var devices=line(Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR);
        return island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,101325,pure(water)),5L,new FluidDeviceSpec(1,298.15,200000,pure(nitrogen))));
    }
    /** A pump limited to 100 kPa between a tank at 1 atm and a tank at 8 bar: dead-headed at its shutoff. */
    private PassiveNetwork deadHeadedPump() {
        var devices=line(Kind.RESERVOIR,Kind.PIPE,Kind.PUMP,Kind.PIPE,Kind.RESERVOIR);
        return island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,101325,pure(nitrogen)),5L,new FluidDeviceSpec(1,298.15,800000,pure(nitrogen))));
    }
    /** Two nitrogen tanks a kilopascal apart, three pipes between: they settle into a closed pair. */
    private PassiveNetwork closedPair() {
        var devices=line(Kind.RESERVOIR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR);
        return island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),5L,new FluidDeviceSpec(1,298.15,149000,pure(nitrogen))));
    }
    /** A nitrogen generator feeding a void through four pipes: steady flow and no finite node. */
    private PassiveNetwork generatorToVoid(double pressure) {
        var devices=line(Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.VOID);
        return island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,pressure,pure(nitrogen)),6L,new FluidDeviceSpec(1,298.15,101325,pure(nitrogen))));
    }
    /** generator - two pipes - tank - two pipes - void, the tank started at the pressure it settles at. */
    private PassiveNetwork throughTank(double[] feed,double[] charge,double volume,double tankPressure) {
        var devices=line(Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR,Kind.PIPE,Kind.PIPE,Kind.VOID);
        return island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,125000,feed),4L,new FluidDeviceSpec(volume,298.15,tankPressure,charge),7L,new FluidDeviceSpec(1,298.15,101325,feed)));
    }
    /** Two nitrogen tanks in a loop driven by a pump: flow is steady and the pump heats the gas. */
    private PassiveNetwork pumpedLoop() {
        var devices=new ArrayList<PhysicalFluidTopology.Device>(List.of(device(1,0,0,Kind.RESERVOIR),device(2,1,0,Kind.PIPE),
                device(3,2,0,Kind.PUMP,new FlowControl.Pump(.005,500000,1)),device(4,3,0,Kind.PIPE),device(5,4,0,Kind.RESERVOIR)));
        long id=6;for(int[] at:new int[][]{{4,1},{4,2},{3,2},{2,2},{1,2},{0,2},{0,1}})devices.add(device(id++,at[0],at[1],Kind.PIPE));
        return island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,101325,pure(nitrogen)),5L,new FluidDeviceSpec(1,298.15,101325,pure(nitrogen))));
    }

    // ---------------- the coordinator with real solves ----------------

    /** A coordinator on a shared epoch whose worker solves each admitted slice at once, as the pool would. */
    private final class Rig {
        final long[] epoch={0};
        final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        final List<IslandCoordinator.Snapshot> published=new ArrayList<>(),replayed=new ArrayList<>();
        final IslandCoordinator coordinator;
        long requests;int solves;
        Rig(CertificatePolicy policy) {
            coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 64-attempts.size();}
                public long nextRequestId(){return ++requests;}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
                public void cancel(long request){throw new AssertionError("no deadlines here");}
            },published::addAll,()->0L,new IslandCoordinator.Settings(30_000_000_000L,20_000_000_000L,64,false,100,policy),IslandCoordinator.CommitHook.NO_MATERIAL,()->epoch[0]);
            coordinator.onReplayed(replayed::addAll);
        }
        void register(long id,PassiveNetwork graph) {
            coordinator.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(0,0,0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
        }
        /** Solves and completes everything admitted, then pumps, until nothing is outstanding: one completion drain. */
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
        void run(int ticks){for(int i=0;i<ticks;i++)tick();}
        /** Runs until the island certifies, at most the given number of ticks; the tick it certified at, or -1. */
        long runUntilCertified(long id,int ticks) {
            for(int i=0;i<ticks;i++){tick();if(stored(id).certificate().isPresent())return epoch[0];}
            return -1;
        }
        IslandCoordinator.Snapshot stored(long id){return coordinator.observe(id);}
        IslandCoordinator.Snapshot read(long id){return coordinator.snapshot(id);}
    }
    private static long counter(String name){return FluidRuntimeDiagnostics.sample().get(name);}
    private static void countFromHere(){FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;}
    private static PassiveNetwork.Reservoir node(PassiveNetwork graph,long id){return graph.reservoirs().stream().filter(n->n.id()==id).findFirst().orElseThrow();}
    private static double[] totals(PassiveNetwork graph) {
        double[] sum=null;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR){var n=node.inventory().moles();if(sum==null)sum=new double[n.length+1];for(int c=0;c<n.length;c++)sum[c]+=n[c];sum[n.length]+=node.inventory().internalEnergy();}
        return sum;
    }

    /** Plan section 5 item 5: the dead-headed line and the dead-headed pump carry exactly nothing and certify REST. */
    @Test void deadHeadedLinesCertifyRestAndThenCostNothing() {
        for(var fixture:List.of(Map.entry("dead-headed line",deadHeadedLine()),Map.entry("dead-headed pump",deadHeadedPump()))) {
            var rig=new Rig(CertificatePolicy.defaults());rig.register(1,fixture.getValue());
            long at=rig.runUntilCertified(1,2_000);
            System.out.println(fixture.getKey()+": certified at tick "+at+" "+rig.stored(1).certificate()+" refusal="+rig.coordinator.certificationRefusal(1));
            assertTrue(at>0,fixture.getKey()+" did not certify: "+rig.coordinator.certificationRefusal(1));
            var certified=rig.stored(1).certificate().orElseThrow();
            assertEquals(IslandCertificate.Kind.REST,certified.kind(),fixture.getKey());
            assertEquals(Long.MAX_VALUE,certified.horizonTick(),"exact rest is never rechecked by default");
            assertTrue(rig.stored(1).status().startsWith("RESTING: no flow since"),rig.stored(1).status());
            assertFalse(rig.coordinator.retainsSolver(1),"a certified island releases its solver caches");
            var before=rig.stored(1).graph();int solves=rig.solves;
            countFromHere();rig.run(10_000);
            assertEquals(solves,rig.solves);
            for(var name:List.of("islandVisits","readinessPumps","solvesDispatched","deadlinesFired","materialisations"))assertEquals(0,counter(name),fixture.getKey()+" "+name);
            assertEquals(Long.MAX_VALUE,rig.coordinator.nextDue());
            var read=rig.read(1);
            assertEquals(rig.epoch[0],read.clock().committedTick(),"a read materialises the island to now");
            assertEquals(before,read.graph(),"rest is the identity");
            assertEquals(IslandCoordinator.Advance.RESTED,rig.coordinator.metrics(1).orElseThrow().advance());
            assertEquals(1,counter("materialisations"));
        }
    }

    /** Plan section 5 item 5: a settled closed pair is stationary to eps_s and certifies with a long horizon. */
    @Test void aSettledClosedPairCertifiesWithALongHorizon() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,closedPair());
        long at=rig.runUntilCertified(1,40_000);
        System.out.println("closed pair: certified at tick "+at+" "+rig.stored(1).certificate()+" refusal="+rig.coordinator.certificationRefusal(1));
        assertTrue(at>0,"closed pair did not certify: "+rig.coordinator.certificationRefusal(1));
        var certified=rig.stored(1).certificate().orElseThrow();
        // The settled pair still carries solver-noise flows of order 1e-11 kg/s, so it is STEADY, not REST.
        assertEquals(IslandCertificate.Kind.STEADY,certified.kind());
        assertTrue(certified.horizonTick()-certified.baseTick()>=1_000*100L,"horizon of "+(certified.horizonTick()-certified.baseTick())/100+" intervals");
        assertFalse(rig.coordinator.retainsSolver(1));
        // With a short window its noise flows need not repeat at the revalidation (a fresh solver finds other noise);
        // then the island drops and certifies again from two fresh intervals, and never strays from its inventory.
        var shortWindow=new Rig(new CertificatePolicy(true,1e-9,1e-6,3,2,0));shortWindow.register(1,closedPair());
        long first=shortWindow.runUntilCertified(1,40_000);assertTrue(first>0);
        var inventory=node(shortWindow.read(1).graph(),5).inventory().moles();
        int solves=shortWindow.solves;shortWindow.run(4_000);
        System.out.println("closed pair, three-interval window: "+(shortWindow.solves-solves)+" solves in 40 intervals, "+shortWindow.stored(1).certificate()+" refusal="+shortWindow.coordinator.certificationRefusal(1));
        assertTrue(shortWindow.solves-solves<=20,"at most one revalidation and one requalifying interval per window");
        var now=node(shortWindow.read(1).graph(),5).inventory().moles();
        for(int c=0;c<now.length;c++)assertEquals(inventory[c],now[c],1e-6*inventory[c]+1e-18,"component "+c);
    }

    /** Plan section 5 item 6: generator to void replays STEADY with exactly conserved totals and within delta_budget of solving. */
    @Test void generatorToVoidReplaysWithinBudgetOfTheSolvedTrajectory() {
        var reference=new Rig(CertificatePolicy.disabled());reference.register(1,generatorToVoid(150000));
        var certified=new Rig(CertificatePolicy.defaults());certified.register(1,generatorToVoid(150000));
        long at=certified.runUntilCertified(1,2_000);
        System.out.println("generator to void: certified at tick "+at+" "+certified.stored(1).certificate()+" refusal="+certified.coordinator.certificationRefusal(1));
        assertTrue(at>0,certified.coordinator.certificationRefusal(1));
        assertEquals(IslandCertificate.Kind.STEADY,certified.stored(1).certificate().orElseThrow().kind());
        assertTrue(certified.stored(1).status().startsWith("STEADY: replaying"),certified.stored(1).status());
        // Accumulate what crosses the boundaries, solved against replayed, over the same 2000 s.
        var solvedLedger=new double[components];var replayedLedger=new double[components];
        reference.coordinator.onReplayed(changed->{throw new AssertionError("reference must not replay");});
        reference.run(40_000);
        for(var snapshot:reference.published)for(var b:snapshot.lastResult().orElseThrow().boundaries())if(b.nodeId()==6){var n=b.moles();for(int c=0;c<n.length;c++)solvedLedger[c]+=n[c];}
        int solvesBefore=certified.solves;certified.run(40_000-(int)at);
        certified.read(1);
        for(var snapshot:certified.published)for(var b:snapshot.lastResult().orElseThrow().boundaries())if(b.nodeId()==6){var n=b.moles();for(int c=0;c<n.length;c++)replayedLedger[c]+=n[c];}
        for(var snapshot:certified.replayed)for(var b:snapshot.lastResult().orElseThrow().boundaries())if(b.nodeId()==6){var n=b.moles();for(int c=0;c<n.length;c++)replayedLedger[c]+=n[c];}
        assertEquals(40_000,certified.stored(1).clock().committedTick());assertEquals(40_000,reference.stored(1).clock().committedTick());
        System.out.printf(Locale.ROOT,"generator to void: %d solves certified vs %d reference; void received %.9e vs %.9e mol nitrogen%n",certified.solves,reference.solves,replayedLedger[nitrogen],solvedLedger[nitrogen]);
        assertTrue(certified.solves-solvesBefore<=1,"no solves while certified: "+(certified.solves-solvesBefore));
        assertEquals(solvedLedger[nitrogen],replayedLedger[nitrogen],1e-6*Math.abs(solvedLedger[nitrogen]),"boundary ledger within delta_budget");
        // The generator's delivery and the void's receipt balance exactly under replay.
        double generator=0,sink=0;
        for(var snapshot:certified.replayed)for(var b:snapshot.lastResult().orElseThrow().boundaries()){if(b.nodeId()==1)generator+=b.moles()[nitrogen];if(b.nodeId()==6)sink+=b.moles()[nitrogen];}
        assertEquals(0,generator+sink,1e-12*Math.abs(sink),"replay conserves: what the generator delivers the void receives");
    }

    /** Plan section 5 item 6: a tank passing a matched throughput certifies, its inventory within delta_budget of solving. */
    @Test void aMatchedThroughputTankReplaysWithinBudgetAndConservesExactly() {
        var reference=new Rig(CertificatePolicy.disabled());var certified=new Rig(CertificatePolicy.defaults());
        for(var rig:List.of(reference,certified))rig.register(1,throughTank(pure(nitrogen),pure(nitrogen),1,113000));
        long at=certified.runUntilCertified(1,20_000);
        System.out.println("matched throughput: certified at tick "+at+" "+certified.stored(1).certificate()+" refusal="+certified.coordinator.certificationRefusal(1));
        assertTrue(at>0,certified.coordinator.certificationRefusal(1));
        var initial=totals(certified.stored(1).graph());long base=certified.stored(1).clock().committedTick();
        int publishedBefore=certified.published.size();
        int end=(int)(at+20_000);reference.run(end);certified.run(end-(int)at);
        var solved=node(reference.read(1).graph(),4).inventory();var replayed=node(certified.read(1).graph(),4).inventory();
        var a=solved.moles();var b=replayed.moles();
        for(int c=0;c<a.length;c++)assertEquals(a[c],b[c],1e-6*a[c]+1e-18,"component "+c+" within delta_budget");
        assertEquals(solved.internalEnergy(),replayed.internalEnergy(),1e-6*Math.abs(solved.internalEnergy()),"energy within delta_budget");
        // Conservation: the finite inventory changed by exactly what crossed the boundaries after the certificate
        // (boundary moles count what entered the island), replayed spans and any revalidating solve alike.
        var after=totals(certified.stored(1).graph());var crossed=new double[components+1];
        var results=new ArrayList<PassiveIntervalSolver.Result>();
        for(var snapshot:certified.replayed)results.add(snapshot.lastResult().orElseThrow());
        for(var snapshot:certified.published.subList(publishedBefore,certified.published.size()))results.add(snapshot.lastResult().orElseThrow());
        for(var result:results)for(var boundary:result.boundaries()){var n=boundary.moles();for(int c=0;c<n.length;c++)crossed[c]+=n[c];crossed[components]+=boundary.totalEnergyJoule();}
        for(int c=0;c<=components;c++)assertEquals(after[c]-initial[c],crossed[c],1e-9*Math.max(Math.abs(initial[c]),1e-30)+1e-15,c==components?"energy":"component "+c);
        System.out.println("matched throughput: base "+base+", solves certified "+certified.solves+" vs reference "+reference.solves);
    }

    /**
     * Plan section 5 item 6: flushing a tank with another composition at a nearly steady mass flow must not
     * certify while the composition is still being replaced; once the flush is complete the tank is steady.
     */
    @Test void aTankBeingFlushedWithAnotherCompositionDoesNotCertifyUntilTheFlushIsComplete() {
        // The flush decays exponentially, so what is left of it when an interval first changes by less than eps_s
        // scales with eps_s: under 1e-6 of the feed fraction at 1e-9, under 1e-4 at the default 1e-7.
        for(var policy:List.of(new CertificatePolicy(true,1e-9,1e-6,17_280,2,0),CertificatePolicy.defaults())) {
            var rig=new Rig(policy);rig.register(1,throughTank(mix(.6),mix(.4),10,113000));
            double largest=0,allowed=1000*policy.stationaryTolerance();String refusal=null;
            for(int tick=1;tick<=40_000&&rig.stored(1).certificate().isEmpty();tick++) {
                rig.tick();if(tick%100!=0)continue;
                var n=node(rig.stored(1).graph(),4).inventory().moles();double deviation=Math.abs(n[methane]/(n[methane]+n[nitrogen])-.6);
                if(rig.stored(1).certificate().isPresent())assertTrue(deviation<allowed,"eps_s "+policy.stationaryTolerance()+": certified at tick "+tick+" with the methane fraction still "+deviation+" from the feed");
                else if(deviation>1e-3){largest=Math.max(largest,deviation);refusal=rig.coordinator.certificationRefusal(1);}
            }
            System.out.println("composition replacement at eps_s "+policy.stationaryTolerance()+": "+rig.stored(1).certificate()+", last refusal while flushing: "+refusal);
            // Refused on the component rates or, first, on the pressure the changing composition moves.
            assertTrue(largest>.1,"the flush was observed");
            assertTrue(refusal!=null&&(refusal.contains("component")||refusal.contains("temperature or pressure")),"refused as not stationary: "+refusal);
            assertTrue(rig.stored(1).certificate().isPresent(),"the completed flush is steady");
        }
    }

    /** Plan section 5 item 5: slow monotonic evolution (a generator filling a large tank) fails stationarity. */
    @Test void aSlowlyFillingTankNeverCertifies() {
        var devices=line(Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR);
        var rig=new Rig(CertificatePolicy.defaults());
        rig.register(1,island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),6L,new FluidDeviceSpec(1000,298.15,101325,pure(nitrogen)))));
        rig.run(20_000);
        System.out.println("slow fill: "+rig.stored(1).certificate()+" refusal="+rig.coordinator.certificationRefusal(1));
        assertTrue(rig.stored(1).certificate().isEmpty());assertEquals(200,rig.solves);
        assertNotNull(rig.coordinator.certificationRefusal(1));
    }

    /** Plan section 5 item 6: a pump heating the gas it circulates must not certify. */
    @Test void aPumpHeatingItsLoopDoesNotCertify() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,pumpedLoop());
        rig.run(20_000);
        var last=rig.stored(1).lastResult().orElseThrow();
        System.out.println("pumped loop: pump work "+last.pumpWorkJoule()+" J per interval, "+rig.stored(1).certificate()+" refusal="+rig.coordinator.certificationRefusal(1));
        assertTrue(last.pumpWorkJoule()>0,"the pump works");
        assertTrue(rig.stored(1).certificate().isEmpty(),"pump heating certified");
    }

    /** Plan section 5 item 5: at the horizon one slice is solved; a repeat renews the certificate, keeping its since tick. */
    @Test void aHorizonRevalidationRenewsAStationaryCertificate() {
        var policy=new CertificatePolicy(true,1e-9,1e-6,3,2,0);
        var rig=new Rig(policy);rig.register(1,generatorToVoid(150000));
        long at=rig.runUntilCertified(1,40_000);assertTrue(at>0,rig.coordinator.certificationRefusal(1));
        var first=rig.stored(1).certificate().orElseThrow();assertEquals(first.baseTick()+300,first.horizonTick(),"three intervals");
        countFromHere();int solves=rig.solves;
        // Woken at the horizon, the island's one slice [horizon, horizon + 100] is due a cadence later.
        rig.run((int)(first.horizonTick()+100-rig.epoch[0]));
        assertEquals(solves+1,rig.solves,"exactly one revalidating slice at the horizon");
        System.out.println("renewal: "+rig.stored(1).certificate()+" refusal="+rig.coordinator.certificationRefusal(1));
        var renewed=rig.stored(1).certificate().orElseThrow();
        assertEquals(1,counter("certificatesRenewed"));assertEquals(1,counter("certificateWakes"));
        assertEquals(first.sinceTick(),renewed.sinceTick(),"a renewal keeps the since tick");
        assertEquals(first.horizonTick()+100,renewed.baseTick());assertEquals(renewed.baseTick()+300,renewed.horizonTick());
        assertFalse(rig.coordinator.retainsSolver(1),"released again after the renewal");
        rig.run(3_000);
        assertEquals(solves+1+7,rig.solves,"one revalidating slice per four intervals");
    }

    /** Plan section 5 item 5: a revalidation that no longer repeats drops the certificate; the island needs a fresh streak. */
    @Test void aHorizonRevalidationThatNoLongerRepeatsDropsTheCertificate() {
        // A loose tolerance lets the flush certify while its composition still moves; at the horizon it has moved on.
        var policy=new CertificatePolicy(true,1e-6,1e-3,5,2,0);
        var rig=new Rig(policy);rig.register(1,throughTank(mix(.6),mix(.4),10,113000));
        long at=rig.runUntilCertified(1,40_000);assertTrue(at>0,rig.coordinator.certificationRefusal(1));
        var first=rig.stored(1).certificate().orElseThrow();
        countFromHere();
        rig.run((int)(first.horizonTick()+100-rig.epoch[0]));
        System.out.println("drop: certified "+first+", then "+rig.stored(1).certificate()+" refusal="+rig.coordinator.certificationRefusal(1));
        assertTrue(rig.stored(1).certificate().isEmpty(),"the revalidation must not renew");
        assertTrue(rig.coordinator.certificationRefusal(1).startsWith("revalidation: "),rig.coordinator.certificationRefusal(1));
        assertEquals(0,counter("certificatesRenewed"));assertEquals(1,counter("certificateWakes"));
        assertTrue(rig.coordinator.retainsSolver(1),"an awake island has fresh solver caches");
        assertTrue(rig.stored(1).status().startsWith("FULL")||!rig.stored(1).status().startsWith("STEADY"),rig.stored(1).status());
        // The next interval can certify again only if it repeats the revalidating one: two fresh stationary intervals.
        rig.run(100);
        var again=rig.stored(1).certificate();
        if(again.isPresent())assertEquals(first.horizonTick()+200,again.orElseThrow().baseTick(),"the revalidating slice and one more");
    }

    /** Plan sections 3.6 and 5 item 5: a hold freezes committed time; resume discards the certificate and requalifies. */
    @Test void aPropertyHoldFreezesACertifiedIslandAndResumeRequalifiesOverTheConfirmCount() {
        for(int confirm:new int[]{2,3}) {
            var rig=new Rig(new CertificatePolicy(true,1e-9,1e-6,17_280,confirm,0));rig.register(1,deadHeadedLine());
            long at=rig.runUntilCertified(1,2_000);assertEquals(100L*confirm,at,"exact rest certifies after the confirm count");
            rig.run(537);
            rig.coordinator.suspendForPropertyChange("HELD: test property change");
            long hold=rig.epoch[0];var held=rig.stored(1);
            assertEquals(hold,held.clock().committedTick(),"the hold materialises the island to the hold tick");
            rig.run(1_000);
            var during=rig.read(1);
            assertEquals(hold,during.clock().committedTick(),"committed time does not move during a hold");
            assertEquals(hold+1_000,during.clock().onlineTick(),"online time does, so the debt is kept");
            assertEquals(held.graph(),during.graph());
            countFromHere();int solves=rig.solves;
            rig.coordinator.resumeQualifiedProperties();
            assertTrue(rig.stored(1).certificate().isEmpty(),"resume discards every certificate");assertEquals(1,counter("certificateWakes"));
            assertTrue(rig.coordinator.retainsSolver(1));
            rig.coordinator.pump();rig.drain();
            // The island solves its debt from the materialised state and must qualify again over the confirm count.
            var again=rig.stored(1).certificate().orElseThrow();
            assertEquals(hold+100L*confirm,again.baseTick(),"requalified over "+confirm+" intervals after resume");
            assertEquals(hold+100L*confirm,again.sinceTick());
            assertEquals(solves+confirm,rig.solves);
        }
    }

    /** Plan section 5 item 3: events while certified are answered by materialisation to their exact tick, in order. */
    @Test void eventsInsideACertifiedWindowAlignByMaterialisationAtTheirExactTicks() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,generatorToVoid(150000));
        long at=rig.runUntilCertified(1,2_000);assertTrue(at>0);
        long base=rig.stored(1).certificate().orElseThrow().baseTick();
        var recorded=rig.stored(1).lastResult().orElseThrow();double[] rate=recorded.boundaries().stream().filter(b->b.nodeId()==6).findFirst().orElseThrow().moles();
        // A fence at the committed tick: aligned at once, nothing materialised.
        var now=UUID.randomUUID();rig.coordinator.fence(now,base,List.of(1L));
        assertTrue(rig.coordinator.aligned(now,List.of(1L)));assertTrue(rig.replayed.isEmpty());rig.coordinator.releaseFence(now,List.of(1L));
        rig.run(600);
        // Three events due inside the window, installed out of order, and one beyond the online clock.
        var first=UUID.randomUUID();var second=UUID.randomUUID();var third=UUID.randomUUID();var future=UUID.randomUUID();
        rig.coordinator.fence(third,base+400,List.of(1L));rig.coordinator.fence(first,base+137,List.of(1L));rig.coordinator.fence(second,base+250,List.of(1L));
        rig.coordinator.fence(future,rig.epoch[0]+333,List.of(1L));
        assertTrue(rig.stored(1).certificate().isPresent(),"a fence does not wake a certified island");
        assertFalse(rig.coordinator.aligned(third,List.of(1L)),"the earlier events come first");
        assertEquals(base+137,rig.stored(1).clock().committedTick());
        assertTrue(rig.coordinator.aligned(first,List.of(1L)));rig.coordinator.releaseFence(first,List.of(1L));
        assertTrue(rig.coordinator.aligned(second,List.of(1L)));assertEquals(base+250,rig.stored(1).clock().committedTick());rig.coordinator.releaseFence(second,List.of(1L));
        assertTrue(rig.coordinator.aligned(third,List.of(1L)));assertEquals(base+400,rig.stored(1).clock().committedTick());rig.coordinator.releaseFence(third,List.of(1L));
        // Every replayed span is the recorded interval scaled by its exact fraction: 137, 113 and 150 ticks of 100.
        var spans=rig.replayed.stream().map(s->s.lastResult().orElseThrow()).toList();
        assertEquals(List.of(6.85,5.65,7.5),spans.stream().map(PassiveIntervalSolver.Result::advancedSeconds).toList());
        int[] ticks={137,113,150};
        for(int i=0;i<3;i++){var got=spans.get(i).boundaries().stream().filter(b->b.nodeId()==6).findFirst().orElseThrow().moles();
            for(int c=0;c<got.length;c++)assertEquals(rate[c]*(ticks[i]/100.0),got[c],0,"span "+i+" component "+c);}
        // The future event: reads stop at the online clock until it is due, then exactly at its tick.
        long due=rig.epoch[0]+333;var read=rig.read(1);assertEquals(rig.epoch[0],read.clock().committedTick());
        assertFalse(rig.coordinator.aligned(future,List.of(1L)));
        rig.run(400);
        assertTrue(rig.coordinator.aligned(future,List.of(1L)));assertEquals(due,rig.read(1).clock().committedTick(),"held at the fence until released");
        rig.coordinator.releaseFence(future,List.of(1L));
        assertTrue(rig.stored(1).certificate().isPresent(),"released events leave the certificate in place");
        assertEquals(rig.epoch[0],rig.read(1).clock().committedTick());
    }

    /**
     * Plan section 3.3: a module drive (a positive withdrawal, a due input) wakes a certified island exactly at its
     * tick; a drive in the coming cadence keeps an island from certifying; and a drive announced after its tick
     * has passed is not answered in the announcing call (the host may be mid-decision) but on the next pass over
     * due deadlines, still at its own tick.
     */
    @Test void aModuleDriveWakesACertifiedIslandAtItsExactTick() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,deadHeadedLine());
        long[] drive={Long.MAX_VALUE};
        rig.coordinator.drives((island,from,to)->drive[0]>=from&&drive[0]<=to?drive[0]:Long.MAX_VALUE);
        long at=rig.runUntilCertified(1,2_000);assertEquals(200,at);
        rig.run(300);
        drive[0]=rig.epoch[0]+250;rig.coordinator.drivesChanged(1);
        rig.run(249);assertTrue(rig.stored(1).certificate().isPresent());assertEquals(at,rig.stored(1).clock().committedTick());
        rig.run(1);
        assertTrue(rig.stored(1).certificate().isEmpty(),"woken by the drive");
        assertEquals(drive[0],rig.stored(1).clock().committedTick(),"materialised exactly to the drive");
        assertTrue(rig.stored(1).status().startsWith("WAITING: module drive at "+drive[0]),rig.stored(1).status());
        assertTrue(rig.coordinator.retainsSolver(1));
        // While the drive is within the coming cadence the island does not certify again.
        int solves=rig.solves;rig.run(100);assertEquals(solves+1,rig.solves);
        drive[0]=rig.stored(1).clock().committedTick()+130;rig.coordinator.drivesChanged(1);
        rig.run(100);assertTrue(rig.stored(1).certificate().isEmpty(),"a drive in the coming cadence blocks certification");
        assertEquals("a module drive is due",rig.coordinator.certificationRefusal(1));
        drive[0]=Long.MAX_VALUE;rig.coordinator.drivesChanged(1);
        long again=rig.runUntilCertified(1,1_000);assertTrue(again>0);long base=rig.stored(1).clock().committedTick();
        rig.run(500);
        // Announced late: nothing moves in the announcing call; the next tick materialises exactly to it.
        drive[0]=rig.epoch[0]-120;rig.coordinator.drivesChanged(1);
        assertEquals(base,rig.stored(1).clock().committedTick());assertTrue(rig.stored(1).certificate().isPresent());
        rig.tick();
        assertTrue(rig.stored(1).certificate().isEmpty());
        // Woken at the drive with more than a cadence of debt, it solves its first slice from exactly that tick.
        var metrics=rig.coordinator.metrics(1).orElseThrow();
        assertEquals(IslandCoordinator.Advance.SOLVED,metrics.advance());assertEquals(drive[0],metrics.startTick());
    }

    /** Plan section 5 item 6: a configuration event replaces the island; the new one solves and qualifies afresh. */
    @Test void changedControlsReplaceACertifiedIslandWithAnAwakeOne() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,generatorToVoid(150000));
        long at=rig.runUntilCertified(1,2_000);assertTrue(at>0);double before=rig.stored(1).certificate().orElseThrow().largestFlow();
        rig.run(250);
        var event=UUID.randomUUID();long tick=rig.epoch[0];rig.coordinator.fence(event,tick,List.of(1L));assertTrue(rig.coordinator.aligned(event,List.of(1L)));
        rig.coordinator.topology(event,Set.of(1L),List.of(new IslandCoordinator.Replacement(2,generatorToVoid(200000))),model,tick,tick,Map.of(),Set.of(),()->{});
        assertTrue(rig.stored(2).certificate().isEmpty(),"a replaced island starts awake");
        assertTrue(rig.coordinator.retainsSolver(2));
        int solves=rig.solves;long again=rig.runUntilCertified(2,2_000);
        assertTrue(again>0);assertTrue(rig.solves-solves>=2,"it qualifies over fresh solved intervals");
        assertTrue(rig.stored(2).certificate().orElseThrow().largestFlow()>before*1.1,"the new control's flow");
    }

    // ---------------- entry evidence and horizon arithmetic on synthetic intervals ----------------

    private final FluidThermodynamics.State gas=model.initialNitrogenCharge(1,298.15,101325,()->{});
    /** A finite tank (id 1) holding {@code moles} of nitrogen and one or two more components, and a void (id 2). */
    private PassiveNetwork tank(double[] moles,double energy,int blocked) {
        var tank=new PassiveNetwork.Reservoir(1,0,gas,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,moles,energy));
        var sink=new PassiveNetwork.Reservoir(2,0,gas,PassiveNetwork.NodeKind.VOID);
        return new PassiveNetwork(List.of(tank,sink),List.of(new PassiveNetwork.Pipe(9,0,1,BLOCK).withBlockedDirections(blocked)));
    }
    private double[] amounts(double nitrogenMoles,double methaneMoles){var n=new double[components];n[nitrogen]=nitrogenMoles;n[methane]=methaneMoles;return n;}
    private PipeTransfer.Stream stream(double mass){return new PipeTransfer.Stream(mass,new double[3][components],new double[3]);}
    /** One accepted FULL interval of 100 ticks from {@code before} to {@code after} with the given flow and gross transfers. */
    private IslandCertificate.Summary interval(long start,PassiveNetwork before,PassiveNetwork after,double flow,double forward,double reverse,Map<String,Integer> reasons) {
        var boundary=new ConservativeTransport.BoundaryTransfer(2,amounts(-flow*5/.028,0),-flow*5e3);
        var result=new PassiveIntervalSolver.Result(after,5,new double[]{flow},1,0,0,flow==0?List.of():List.of(boundary),reasons,List.of(FlowControl.Mode.PASSIVE),new double[]{0},
                PassiveStepSolver.Acceptance.FULL,List.of(new PipeTransfer(9,stream(forward),stream(reverse))));
        return new IslandCertificate.Summary(new IslandCertificate.Interval(start,start+100,before,result),model.molecularWeights());
    }
    /** Two consecutive intervals of a tank draining component amounts {@code delta1} then {@code delta2} from {@code moles}. */
    private IslandCertificate.Summary[] draining(double[] moles,double[] delta1,double[] delta2,double flow1,double flow2) {
        var a=tank(moles,-1e5,0);var m1=moles.clone();for(int c=0;c<m1.length;c++)m1[c]+=delta1[c];var b=tank(m1,-1e5,0);
        var m2=m1.clone();for(int c=0;c<m2.length;c++)m2[c]+=delta2[c];var d=tank(m2,-1e5,0);
        return new IslandCertificate.Summary[]{interval(0,a,b,flow1,flow1*5,0,Map.of()),interval(100,b,d,flow2,flow2*5,0,Map.of())};
    }

    /** Plan section 4: eps_s is relative to each node's own inventory, so a small inventory needs a proportionally smaller change. */
    @Test void aSmallInventoryFailsTheRelativeToleranceThatALargeOnePasses() {
        var large=draining(amounts(1000,0),amounts(-1e-3,0),amounts(-1e-3-1e-7,0),1e-3,1e-3);
        assertNull(IslandCertificate.stationaryRefusal(large[0],large[1],1e-9),"1e-7 mol of 1000 mol is 1e-10");
        var small=draining(amounts(1,0),amounts(-1e-3,0),amounts(-1e-3-1e-7,0),1e-3,1e-3);
        var refusal=IslandCertificate.stationaryRefusal(small[0],small[1],1e-9);
        assertNotNull(refusal);assertTrue(refusal.contains("component "+nitrogen),refusal);
        // The plan's example: 1 g held against a 1e-9 kg/s drift is d = 5e-6 per interval, over the budget: no certificate at all.
        double gram=1e-3/.028,drift=1e-9*5/.028;
        var steady=draining(amounts(gram,0),amounts(-drift,0),amounts(-drift,0),1e-9,1e-9);
        assertNull(IslandCertificate.stationaryRefusal(steady[0],steady[1],1e-9));
        var issued=IslandCertificate.issue(steady[1],CertificatePolicy.defaults());
        assertNull(issued.certificate());assertTrue(issued.refusal().contains("exceeds the inventory budget"),issued.refusal());
    }

    /** Plan section 5 item 5: a map that moves material back and forth with a zero average is not rest. */
    @Test void grossFlowWithAZeroAverageIsNotRest() {
        var at=tank(amounts(40,0),-1e5,0);
        var shuttling=interval(0,at,at,0,1e-3,1e-3,Map.of());
        assertFalse(shuttling.exactZero);
        var issued=IslandCertificate.issue(shuttling,CertificatePolicy.defaults());
        assertNull(issued.certificate());assertTrue(issued.refusal().contains("gross"),issued.refusal());
        var still=interval(0,at,at,0,0,0,Map.of());
        assertTrue(still.exactZero);assertEquals(IslandCertificate.Kind.REST,IslandCertificate.issue(still,CertificatePolicy.defaults()).certificate().kind());
    }

    /** Plan section 5 item 5: a rate that keeps changing, however slowly, is not stationary. */
    @Test void slowMonotonicEvolutionFailsStationarity() {
        var drifting=draining(amounts(1000,0),amounts(-1,0),amounts(-1-2e-6,0),1,1);
        assertNotNull(IslandCertificate.stationaryRefusal(drifting[0],drifting[1],1e-9),"a rate change of 2e-9 of the inventory per interval");
        var flow=draining(amounts(1000,0),amounts(-1,0),amounts(-1,0),1,1+1e-8);
        var refusal=IslandCertificate.stationaryRefusal(flow[0],flow[1],1e-9);assertNotNull(refusal);assertTrue(refusal.contains("pipe flow"),refusal);
        var repeat=draining(amounts(1000,0),amounts(-1,0),amounts(-1,0),1,1);
        assertNull(IslandCertificate.stationaryRefusal(repeat[0],repeat[1],1e-9));
    }

    /** Plan section 5 item 5: solid transport, phase and closure or filter transitions reset qualification. */
    @Test void solidPhaseAndClosureTransitionsResetQualification() {
        var a=tank(amounts(40,0),-1e5,0);
        var transition=interval(0,a,a,0,0,0,Map.of("Solid transport transition at pipe 9; t=2.5",1));
        assertFalse(transition.transitionFree);
        assertNotNull(IslandCertificate.stationaryRefusal(interval(0,a,a,0,0,0,Map.of()),transition,1e-9));
        assertNull(IslandCertificate.issue(transition,CertificatePolicy.defaults()).certificate());
        assertTrue(IslandCertificate.transitionFree(Map.of("Settled bed closed pipe 9; t=0",1)),"a closure the interval started with is no transition");
        assertFalse(IslandCertificate.transitionFree(Map.of("Settled bed closed pipe 9; t=1.25",1)));
        // A phase appears: free water condenses in the tank.
        double[] wet=amounts(40,0);wet[water]=10;var condensed=model.flashTP(298.15,101325,wet,()->{});
        var wetTank=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,condensed,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,amounts(40,0),-1e5)),
                new PassiveNetwork.Reservoir(2,0,gas,PassiveNetwork.NodeKind.VOID)),a.pipes());
        assertTrue(condensed.waterVolume()>0);
        var phase=interval(0,a,wetTank,0,0,0,Map.of());
        assertFalse(phase.phasesUnchanged);assertNull(IslandCertificate.issue(phase,CertificatePolicy.defaults()).certificate());
        // A connection closes inside the interval.
        var closed=interval(0,a,tank(amounts(40,0),-1e5,3),0,0,0,Map.of());
        assertFalse(closed.closuresUnchanged);assertNull(IslandCertificate.issue(closed,CertificatePolicy.defaults()).certificate());
        var stays=interval(100,tank(amounts(40,0),-1e5,3),tank(amounts(40,0),-1e5,3),0,0,0,Map.of());
        var refusal=IslandCertificate.stationaryRefusal(closed,stays,1e-9);assertNotNull(refusal);
    }

    /** Plan section 4: the horizon is min(K_max, budget / d); it stops before any component could reach zero. */
    @Test void theHorizonKeepsReplayWithinTheBudgetAndStopsBeforeDepletion() {
        var policy=CertificatePolicy.defaults();
        var tenth=draining(amounts(1,1e-6),amounts(-.99e-7,-.5e-13),amounts(-.99e-7,-.5e-13),1e-6,1e-6);
        var certificate=IslandCertificate.issue(tenth[1],policy).certificate();
        assertEquals(IslandCertificate.Kind.STEADY,certificate.kind());
        assertEquals(200+10*100,certificate.horizonTick(),"d = 0.99e-7 gives ten intervals of a 1e-6 budget");
        // A trace draining faster sets d and shortens the window to one interval.
        var trace=draining(amounts(1,1e-6),amounts(-1e-9,-.9e-12),amounts(-1e-9,-.9e-12),1e-6,1e-6);
        assertEquals(300,IslandCertificate.issue(trace[1],policy).certificate().horizonTick());
        // Whatever the budget, the extrapolated inventory at the horizon stays within it of the base and positive.
        for(double budget:new double[]{1e-12,1e-9,1e-6,1e-3}) {
            var issued=IslandCertificate.issue(tenth[1],new CertificatePolicy(true,1e-9,budget,1_000_000,2,0)).certificate();
            if(issued==null)continue;
            var base=issued.graphAt(issued.baseTick()).reservoirs().getFirst().inventory().moles();
            var end=issued.graphAt(issued.horizonTick()).reservoirs().getFirst().inventory().moles();
            for(int c=0;c<base.length;c++){assertTrue(end[c]>=0,"component "+c);if(base[c]>0)assertTrue(Math.abs(end[c]-base[c])<=budget*base[c]*(1+1e-12),"budget "+budget+" component "+c);}
        }
        // A drift of more than the budget per interval: no window at all.
        var fast=draining(amounts(1,0),amounts(-.01,0),amounts(-.01,0),1e-3,1e-3);
        assertNull(IslandCertificate.issue(fast[1],policy).certificate());
        // K_max bounds a tiny drift; recheck bounds exact rest only when configured.
        var tiny=draining(amounts(1e9,0),amounts(-1e-9,0),amounts(-1e-9,0),1e-9,1e-9);
        assertEquals(200+17_280*100L,IslandCertificate.issue(tiny[1],policy).certificate().horizonTick());
        var at=tank(amounts(40,0),-1e5,0);var rest=interval(0,at,at,0,0,0,Map.of());
        assertEquals(100+20*60,IslandCertificate.issue(rest,new CertificatePolicy(true,1e-9,1e-6,17_280,2,60)).certificate().horizonTick());
    }

    /** Plan section 3.3: replay is the recorded interval scaled by the elapsed fraction; conservation is exact. */
    @Test void replayScalesTheRecordedIntervalLinearly() {
        var steps=draining(amounts(10,2),amounts(-1e-8,-2e-9),amounts(-1e-8,-2e-9),1e-6,1e-6);
        var certificate=IslandCertificate.issue(steps[1],CertificatePolicy.defaults()).certificate();
        var recorded=steps[1].result;
        for(long to:new long[]{201,237,300,350,1000}) {
            var replay=certificate.replay(200,to);double g=(to-200)/100.0;
            assertEquals((to-200)/20.0,replay.advancedSeconds());
            var b=replay.boundaries().getFirst().moles();var r=recorded.boundaries().getFirst().moles();
            for(int c=0;c<b.length;c++)assertEquals(r[c]*g,b[c],0);
            assertEquals(recorded.boundaries().getFirst().totalEnergyJoule()*g,replay.boundaries().getFirst().totalEnergyJoule(),0);
            assertEquals(recorded.pipeTransfers().getFirst().forward().massKg()*g,replay.pipeTransfers().getFirst().forward().massKg(),0);
            var base=certificate.graphAt(200).reservoirs().getFirst().inventory().moles();var now=replay.graph().reservoirs().getFirst().inventory().moles();
            double f=(to-200)/100.0;var delta=steps[1].moles[0];
            for(int c=0;c<base.length;c++)assertEquals(base[c]+f*delta[c],now[c],0,"component "+c);
            assertEquals(-1e-8,delta[nitrogen],1e-14);assertEquals(-2e-9,delta[methane],1e-14);
            assertEquals(0,replay.acceptedSubsteps());assertEquals(PassiveStepSolver.Acceptance.FULL,replay.acceptance());
        }
        assertThrows(IllegalArgumentException.class,()->certificate.replay(300,300));
        assertThrows(IllegalArgumentException.class,()->certificate.graphAt(certificate.horizonTick()+1));
        assertThrows(IllegalArgumentException.class,()->certificate.graphAt(199));
    }

    // ---------------- quiet pipes ----------------

    /**
     * Plan section 3.3, flow test: a pipe that moves less than eps_s of the finite inventory it draws on in both
     * intervals is quiet and exempt from the relative test; a pipe that moves more is held to it; a pipe with no
     * finite inventory behind it is never quiet; a junction stands for the island's smallest finite inventory.
     */
    @Test void aQuietPipeIsExemptFromTheRelativeFlowTestAndALoudOneIsNot() {
        // The reference is the smaller inventory the pipe draws on, here the tank after the second interval.
        double kilograms=1000*model.molecularWeight(nitrogen),drained=999.998*model.molecularWeight(nitrogen),tolerance=1e-9;
        // Noise flows of 1e-10 then 3e-10 kg/s out of a 28 kg tank: the flow changes by two thirds of itself, which
        // the relative test alone refused, but the pipe moves 5e-11 of the tank per interval.
        var noise=draining(amounts(1000,0),amounts(-1e-3,0),amounts(-1e-3,0),1e-10,3e-10);
        var measured=IslandCertificate.stationarity(noise[0],noise[1]);
        assertNull(measured.refusal(tolerance),measured.toString());
        assertEquals(2.0/3,measured.largestRelativeFlowChange(),1e-12,"the relative test alone refuses");
        assertEquals(3e-10*5/drained,measured.flowMoves(),1e-12*3e-10*5/drained);
        // The same relative change on a flow that moves material is refused.
        var loud=draining(amounts(1000,0),amounts(-1e-3,0),amounts(-1e-3,0),1e-3,3e-3);
        var refusal=IslandCertificate.stationaryRefusal(loud[0],loud[1],tolerance);
        assertNotNull(refusal);assertTrue(refusal.contains("pipe flow"),refusal);
        // The edge: quiet below eps_s of the inventory per interval, refused above it.
        for(double moves:new double[]{.9e-9,1.1e-9}) {
            double flow=moves*drained/5;var edge=draining(amounts(1000,0),amounts(-1e-3,0),amounts(-1e-3,0),flow/3,flow);
            var s=IslandCertificate.stationarity(edge[0],edge[1]);
            assertEquals(moves,s.flowMoves(),1e-12*moves);
            assertEquals(moves<tolerance,s.refusal(tolerance)==null,moves+" of the tank per interval: "+s);
        }
        // eps_s = 0 admits only an exact repeat, so no flow that changes at all is quiet.
        assertNotNull(IslandCertificate.stationaryRefusal(noise[0],noise[1],0));
        // measure() is the smallest tolerance the pair passes at.
        for(var pair:List.of(noise,loud)) {
            var s=IslandCertificate.stationarity(pair[0],pair[1]);double m=s.measure();
            assertNull(s.refusal(m));assertNotNull(s.refusal(Math.nextDown(m)));
        }
        // A generator straight into a void draws on no finite inventory: its noise is never quiet.
        var generator=new PassiveNetwork.Reservoir(1,0,gas,PassiveNetwork.NodeKind.GENERATOR);var sink=new PassiveNetwork.Reservoir(2,0,gas,PassiveNetwork.NodeKind.VOID);
        var line=new PassiveNetwork(List.of(generator,sink),List.of(new PassiveNetwork.Pipe(9,0,1,BLOCK)));
        var boundaries=IslandCertificate.stationaryRefusal(interval(0,line,line,1e-10,5e-10,0,Map.of()),interval(100,line,line,3e-10,1.5e-9,0,Map.of()),tolerance);
        assertNotNull(boundaries);assertTrue(boundaries.contains("pipe flow"),boundaries);
        // A junction holds nothing and passes its flow on: it stands for the island's smallest finite inventory.
        var small=new PassiveNetwork.Reservoir(1,0,gas,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,amounts(1000,0),-1e5));
        var large=new PassiveNetwork.Reservoir(4,0,gas,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,amounts(5000,0),-5e5));
        var junction=new PassiveNetwork.Reservoir(3,0,gas,PassiveNetwork.NodeKind.JUNCTION);
        var tee=new PassiveNetwork(List.of(small,junction,large,sink),List.of(new PassiveNetwork.Pipe(9,0,1,BLOCK),new PassiveNetwork.Pipe(10,1,2,BLOCK),new PassiveNetwork.Pipe(11,2,3,BLOCK)));
        var summary=interval(0,tee,tee,0,0,0,Map.of());
        assertArrayEquals(new double[]{kilograms,kilograms,5*kilograms},summary.referenceMass,1e-12*kilograms);
    }

    /** The paced benchmark's composition: the Tia Juana light feed with 0.1 nitrogen and 0.2 water, not renormalised. */
    private static double[] benchmarkComposition() {
        var catalog=MaterialCatalog.bundled();
        var z=Arrays.copyOf(com.wormzjl.createcheme.science.material.MaterialRuntime.with(catalog,FluidPresetCatalog.NETWORK_PACKAGE,
                ()->com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo.fromRegisteredPackage(FluidPresetCatalog.NETWORK_PACKAGE)
                        .crudeFeed("createcheme:tia_juana_light_methane").moleFractions()),
                com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.conservedCount());
        z[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.nitrogenIndex()]=.1;z[com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog.waterIndex()]=.2;
        return z;
    }
    /**
     * Ladder {@code network} of the paced benchmark's timed fixtures ({@code FluidServerBenchmark.installFixture}),
     * rebuilt with the same device identities, positions, rung pressures and inventories: CLOSED as in rest100,
     * THROUGH as in stress100. The ladders before it only count identities and draw from the shared random sequence.
     */
    private PassiveNetwork benchmarkLadder(int network,boolean through) {
        var composition=benchmarkComposition();var random=new Random(2026091603L);
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var stocks=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();long[] next={1};
        class Builder {
            boolean keep;
            void add(int x,int z,Kind kind,double pressure) {
                long id=next[0]++;if(!keep)return;
                var device=new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",1024+x,80,1024+z),kind,PhysicalFluidTopology.Direction.EAST,
                        new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
                devices.add(device);
                if(kind==Kind.PIPE)return;
                if(kind==Kind.RESERVOIR) {
                    var amounts=composition.clone();var unit=model.flashTP(350,pressure,amounts,()->{});
                    for(int c=0;c<amounts.length;c++)amounts[c]=amounts[c]/unit.volume();
                    var state=model.flashTP(350,pressure,amounts,()->{});
                    stocks.put(id,new PassiveNetwork.Reservoir(id,80,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,amounts,state.internalEnergy())));
                }else stocks.put(id,new FluidDeviceSpec(1,350,pressure,composition).initialize(device,model,()->{}));
            }
        }
        var b=new Builder();
        for(int k=0;k<=network;k++) {
            b.keep=k==network;
            int count=10+(k*13%21),columns=count/2,baseX=15360+80*(k%10),baseZ=15360+16*(k/10);
            if(through) {
                b.add(baseX-8,baseZ,Kind.GENERATOR,150100);
                for(int x=-7;x<0;x++)b.add(baseX+x,baseZ,x==-4&&count%2==1?Kind.RESERVOIR:Kind.PIPE,150090);
            }
            for(int c=0;c<columns;c++) {
                double pressure=150100-100*(c+.5)/columns;
                for(int row=0;row<2;row++) {
                    b.add(baseX+4*c,baseZ+4*row,Kind.RESERVOIR,pressure+4*(random.nextDouble()-.5));
                    if(c+1<columns)for(int dx=1;dx<4;dx++)b.add(baseX+4*c+dx,baseZ+4*row,Kind.PIPE,pressure);
                }
                for(int dz=1;dz<4;dz++)b.add(baseX+4*c,baseZ+dz,Kind.PIPE,pressure);
            }
            if(through) {
                int end=baseX+4*(columns-1);
                for(int dx=1;dx<4;dx++)b.add(end+dx,baseZ+4,Kind.PIPE,150010);
                b.add(end+4,baseZ+4,Kind.VOID,150000);
            }
        }
        var islands=PhysicalFluidTopology.compile(devices,stocks).islands();
        assertEquals(1,islands.size());return islands.getFirst().graph();
    }

    /**
     * The flow test on a real island: rest100's ladder 65 settles to noise flows whose relative changes are of
     * order one, and the relative test alone refused it for the whole benchmark window (0.35 of the largest flow at
     * its end). With quiet pipes exempt it certifies STEADY, and its evidence shows the relative test would still
     * have refused the qualifying pair.
     */
    @Test void aSettledBenchmarkClosedLadderCertifiesOnceItsQuietPipesAreExempt() {
        var rig=new Rig(CertificatePolicy.defaults());rig.register(1,benchmarkLadder(65,false));
        long at=rig.runUntilCertified(1,6_000);
        var evidence=rig.coordinator.certificationEvidence(1);
        System.out.println("closed ladder 65: certified at tick "+at+" "+rig.stored(1).certificate()+" refusal="+rig.coordinator.certificationRefusal(1)+" evidence="+evidence);
        assertTrue(at>0,"did not certify: "+rig.coordinator.certificationRefusal(1));
        assertEquals(IslandCertificate.Kind.STEADY,rig.stored(1).certificate().orElseThrow().kind());
        assertEquals(at,evidence.endTick());
        double tolerance=CertificatePolicy.defaults().stationaryTolerance();
        assertTrue(evidence.stationarity().flow()<=tolerance,evidence.toString());
        assertTrue(evidence.stationarity().largestRelativeFlowChange()>tolerance,"the relative flow test alone would have refused: "+evidence);
    }

    // ---------------- certificates that never issue change nothing ----------------

    /** A bit-for-bit fingerprint of everything an island publishes: clock, status, graph and the interval's result. */
    static String publications(List<IslandCoordinator.Snapshot> published) {
        try {
            var sha=java.security.MessageDigest.getInstance("SHA-256");var buffer=java.nio.ByteBuffer.allocate(8);
            java.util.function.LongConsumer put=v->{buffer.clear();buffer.putLong(v);sha.update(buffer.array());};
            java.util.function.DoubleConsumer bits=v->put.accept(Double.doubleToLongBits(v));
            for(var s:published) {
                put.accept(s.id());put.accept(s.clock().onlineTick());put.accept(s.clock().committedTick());put.accept(s.clock().retryAtTick());put.accept(s.clock().cadenceTicks());
                sha.update(s.status().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for(var node:s.graph().reservoirs()) {
                    put.accept(node.id());for(double n:node.inventory().moles())bits.accept(n);bits.accept(node.inventory().internalEnergy());
                    var state=node.state();for(double v:new double[]{state.temperature(),state.pressure(),state.mass(),state.vaporVolume(),state.liquidVolume(),state.waterVolume()})bits.accept(v);
                }
                var result=s.lastResult().orElseThrow();
                for(double q:result.averageMassFlows())bits.accept(q);
                for(var boundary:result.boundaries()){put.accept(boundary.nodeId());for(double n:boundary.moles())bits.accept(n);bits.accept(boundary.totalEnergyJoule());}
                bits.accept(result.pumpWorkJoule());put.accept(result.acceptedSubsteps());put.accept(result.rejectedSubsteps());
                for(var pipe:result.pipeTransfers()){put.accept(pipe.pipeId());bits.accept(pipe.forward().massKg());bits.accept(pipe.reverse().massKg());}
            }
            return java.util.HexFormat.of().formatHex(sha.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }

    /**
     * With certificates on, an island that never certifies must follow the certificates-off trajectory bit for bit:
     * the evidence reads the solved intervals and never feeds anything back. Slow fill, pump heating and the
     * stress100 through-flow ladder, each for forty intervals, at eps_s 1e-9, where none of them certifies (the
     * ladder's holdup drifts by about 2e-7 per interval and certifies at the default 1e-7).
     */
    @Test void anIslandThatNeverCertifiesSolvesBitForBitAsWithCertificatesOff() {
        var devices=line(Kind.GENERATOR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR);
        var slowFill=island(devices,Map.of(1L,new FluidDeviceSpec(1,298.15,150000,pure(nitrogen)),6L,new FluidDeviceSpec(1000,298.15,101325,pure(nitrogen))));
        for(var fixture:List.of(Map.entry("slow fill",slowFill),Map.entry("pumped loop",pumpedLoop()),Map.entry("stress100 ladder 7",benchmarkLadder(7,true)))) {
            var off=new Rig(CertificatePolicy.disabled());var on=new Rig(new CertificatePolicy(true,1e-9,1e-6,17_280,2,0));
            for(var rig:List.of(off,on)){rig.register(1,fixture.getValue());rig.run(4_000);}
            assertTrue(on.stored(1).certificate().isEmpty(),fixture.getKey()+" certified");
            assertNotNull(on.coordinator.certificationEvidence(1),fixture.getKey()+": the evidence ran");
            assertNull(off.coordinator.certificationEvidence(1),fixture.getKey()+": certificates off compute nothing");
            assertTrue(on.replayed.isEmpty());assertEquals(40,on.published.size());
            System.out.println(fixture.getKey()+": "+on.coordinator.certificationRefusal(1));
            assertEquals(publications(off.published),publications(on.published),fixture.getKey());
        }
    }
}

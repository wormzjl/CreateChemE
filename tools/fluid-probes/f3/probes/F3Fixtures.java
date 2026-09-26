package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.BoundedCpuSolveService;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;

/**
 * F3 probe fixtures (not committed): the paced benchmark's rest100 (CLOSED) and stress100 (THROUGH) ladders, built
 * exactly as FluidServerBenchmark.installFixture builds them (same seed, same device order, wet TJL + nitrogen at
 * 350 K), the in-game rest line of WP5 (nitrogen tank, three pipes, tank at the placement defaults) and a lone tank;
 * a synchronous rig whose worker solves each admitted slice at once; and a relabelling that clones a solved island
 * under new island, node and pipe identities (for the 100 and 1,000 island save timings).
 */
final class F3Fixtures {
    static final String PACKAGE="createcheme:tjl20_methane_nitrogen";
    static final BoundedCpuSolveService.CancellationToken NEVER=new BoundedCpuSolveService.CancellationToken() {
        public long deadlineNanos(){return Long.MAX_VALUE;}public boolean isDeadlineExceeded(){return false;}
        public boolean isCancellationRequested(){return false;}public void throwIfCancellationRequested(){}
    };
    final FluidThermodynamics model=FluidTestSupport.networkModel();
    final int components=model.components().size();
    final double[] crude;
    F3Fixtures() {
        crude=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE).crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),components);
        crude[MaterialTestBasis.NITROGEN]=.1;crude[components-1]=.2;
    }
    enum Ladder{THROUGH,CLOSED}

    /** A built ladder: its island and the world registrations of its devices. */
    record Built(PassiveNetwork graph,List<WorldTopologyLedger.Registration> registrations) {}
    PassiveNetwork ladder(int network,Ladder kind,Random random,long firstId){return build(network,kind,random,firstId).graph();}
    /** One benchmark ladder as one island. {@code random} is the benchmark's shared sequence (seed 2026091603). */
    Built build(int network,Ladder kind,Random random,long firstId) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var stocks=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        var registrations=new ArrayList<WorldTopologyLedger.Registration>();
        long[] next={firstId};
        class B {
            void add(int x,int z,Kind k,double pressure){add(x,z,k,pressure,1,.05,1);}
            void add(int x,int z,Kind k,double pressure,double length,double diameter,double volume) {
                long id=next[0]++;
                var device=new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",1024+x,80,1024+z),k,PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(length,diameter,.000045,0),new FlowControl.Passive());
                devices.add(device);
                var spec=new FluidDeviceSpec(volume,350,pressure,crude);registrations.add(new WorldTopologyLedger.Registration(device,spec,0));
                if(k==Kind.PIPE)return;
                if(k==Kind.RESERVOIR) {
                    var amounts=crude.clone();var unit=model.flashTP(350,pressure,amounts,()->{});
                    for(int c=0;c<model.componentCount();c++)amounts[c]=amounts[c]/unit.volume()*volume;
                    var state=model.flashTP(350,pressure,amounts,()->{});
                    stocks.put(id,new PassiveNetwork.Reservoir(id,80,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(volume,amounts,state.internalEnergy())));
                } else stocks.put(id,spec.initialize(device,model,()->{}));
            }
        }
        var b=new B();
        int count=10+(network*13%21),columns=count/2,baseX=15360+80*(network%10),baseZ=15360+16*(network/10);
        if(kind!=Ladder.CLOSED) {
            b.add(baseX-8,baseZ,Kind.GENERATOR,150100);
            for(int x=-7;x<0;x++)b.add(baseX+x,baseZ,x==-4&&count%2==1?Kind.RESERVOIR:Kind.PIPE,150090,1,.05,1);
        }
        for(int c=0;c<columns;c++) {
            double pressure=150100-100*(c+.5)/columns;
            for(int row=0;row<2;row++) {
                b.add(baseX+4*c,baseZ+4*row,Kind.RESERVOIR,pressure+4*(random.nextDouble()-.5),1,.05,1);
                if(c+1<columns)for(int dx=1;dx<4;dx++)b.add(baseX+4*c+dx,baseZ+4*row,Kind.PIPE,pressure,1,.05,1);
            }
            for(int dz=1;dz<4;dz++)b.add(baseX+4*c,baseZ+dz,Kind.PIPE,pressure,1,.05,1);
        }
        if(kind==Ladder.THROUGH) {
            int end=baseX+4*(columns-1);
            for(int dx=1;dx<4;dx++)b.add(end+dx,baseZ+4,Kind.PIPE,150010);
            b.add(end+4,baseZ+4,Kind.VOID,150000);
        }
        var islands=PhysicalFluidTopology.compile(devices,stocks).islands();
        if(islands.size()!=1||islands.getFirst().error().isPresent())throw new IllegalStateException("ladder "+network+" did not compile to one island: "+islands.size());
        return new Built(islands.getFirst().graph(),registrations);
    }
    /** WP5's in-game rest line: a 1 m3 nitrogen tank, three pipes, a 1 m3 nitrogen tank, at the placement defaults. */
    PassiveNetwork restLine(long first,int z) {
        var devices=new ArrayList<PhysicalFluidTopology.Device>();var stocks=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        var kinds=new Kind[]{Kind.RESERVOIR,Kind.PIPE,Kind.PIPE,Kind.PIPE,Kind.RESERVOIR};
        var nitrogen=new double[components];nitrogen[MaterialTestBasis.NITROGEN]=1;
        for(int i=0;i<kinds.length;i++) {
            var device=new PhysicalFluidTopology.Device(first+i,new PhysicalFluidTopology.Position("minecraft:overworld",i,64,z),kinds[i],PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
            devices.add(device);
            if(kinds[i]==Kind.RESERVOIR)stocks.put(device.id(),new FluidDeviceSpec(1,298.15,101325,nitrogen).initialize(device,model,()->{}));
        }
        return PhysicalFluidTopology.compile(devices,stocks).islands().getFirst().graph();
    }
    PassiveNetwork loneTank(long node) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(node,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
    }

    /** A coordinator whose worker solves each admitted slice at once, on the calling thread. */
    final class Rig {
        final long[] epoch;final IslandCoordinator coordinator;
        final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveServices.FluidIslandCommand> commands=new HashMap<>();
        long requests;int solves;
        Rig(CertificatePolicy policy,long start) {
            epoch=new long[]{start};
            coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers(){return 64-attempts.size();}
                public long nextRequestId(){return ++requests;}
                public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);return true;}
                public void cancel(long request){throw new AssertionError("no deadlines here");}
            },changed->{},()->0L,new IslandCoordinator.Settings(30_000_000_000L,20_000_000_000L,64,false,100,policy),IslandCoordinator.CommitHook.NO_MATERIAL,()->epoch[0]);
        }
        void register(long id,PassiveNetwork graph) {
            coordinator.register(new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(epoch[0],epoch[0],0,100),FallbackAllowance.NONE,Optional.empty(),Optional.empty(),"READY"),model);
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
        void run(long ticks){for(long i=0;i<ticks;i++)tick();}
        boolean certified(long id){return coordinator.observe(id).certificate().isPresent();}
        void runUntilCertified(Collection<Long> ids,int ticks){for(int i=0;i<ticks&&!ids.stream().allMatch(this::certified);i++)tick();}
        FluidCheckpointCodec.Checkpoint checkpoint() {
            return new FluidCheckpointCodec.Checkpoint(coordinator.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,1e-9,s)).toList(),new BufferedTransfers.Snapshot(0,Map.of(),Map.of()));
        }
    }

    /** The world topology of {@code n} clones of {@code templates} (clone i of template i mod K), devices shifted by
     * {@code (i+1) * stride} and moved 2,000 blocks apart, at {@code onlineTick}. */
    static WorldTopologyLedger.Snapshot topology(List<Built> templates,int n,long stride,long onlineTick) {
        var empty=WorldTopologyLedger.Snapshot.empty(com.wormzjl.createcheme.science.material.MaterialCatalog.bundled());
        var active=new LinkedHashMap<Long,WorldTopologyLedger.Registration>();long max=0;
        for(int i=0;i<n;i++) {
            var t=templates.get(i%templates.size());long offset=(i+1)*stride;int dx=2000*(i%40),dz=2000*(i/40);
            for(var r:t.registrations()) {
                var d=r.device();var p=d.position();
                var moved=new PhysicalFluidTopology.Device(d.id()+offset,new PhysicalFluidTopology.Position(p.dimension(),p.x()+dx,p.y(),p.z()+dz),d.kind(),d.facing(),d.geometry(),d.control());
                active.put(moved.id(),new WorldTopologyLedger.Registration(moved,r.spec(),0));max=Math.max(max,moved.id());
            }
        }
        return new WorldTopologyLedger.Snapshot(onlineTick,max+1,active,List.of(),empty.constructed(),empty.destroyed(),empty.basis(),Map.of());
    }

    // ---------------- relabelling: a solved island under new identities ----------------

    static PassiveNetwork relabel(PassiveNetwork graph,long nodeOffset,long pipeOffset) {
        return new PassiveNetwork(graph.reservoirs().stream().map(n->new PassiveNetwork.Reservoir(n.id()+nodeOffset,n.elevation(),n.state(),n.kind(),n.inventory())).toList(),
                graph.pipes().stream().map(p->new PassiveNetwork.Pipe(p.id()+pipeOffset,p.first(),p.second(),p.sections(),p.control(),p.blockedDirections(),p.filter())).toList());
    }
    static PassiveIntervalSolver.Result relabel(PassiveIntervalSolver.Result r,long nodeOffset,long pipeOffset) {
        return new PassiveIntervalSolver.Result(relabel(r.graph(),nodeOffset,pipeOffset),r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),
                r.boundaries().stream().map(b->new ConservativeTransport.BoundaryTransfer(b.nodeId()+nodeOffset,b.moles(),b.totalEnergyJoule(),b.solids(),b.solidDirection())).toList(),
                r.rejectionReasons(),r.endpointModes(),r.endpointHeads(),r.acceptance(),
                r.pipeTransfers().stream().map(p->new PipeTransfer(p.pipeId()+pipeOffset,p.forward(),p.reverse())).toList());
    }
    /** A snapshot of a solved island under a new island identity and node/pipe identities; its certificate signed anew. */
    IslandCoordinator.Snapshot clone(IslandCoordinator.Snapshot s,long id,long nodeOffset,long pipeOffset,CertificatePolicy policy) {
        var anchor=s.anchor().map(a->new ApproximationAnchor(a.propertyRevision(),relabel(a.graph(),nodeOffset,pipeOffset),a.modes()));
        var result=s.lastResult().map(r->relabel(r,nodeOffset,pipeOffset));
        Optional<IslandCoordinator.Certified> certificate=s.certificate().map(c->{
            var saved=c.saved().orElseThrow();var interval=saved.interval();
            var result2=relabel(interval.result(),nodeOffset,pipeOffset);
            var copy=new IslandCertificate.Saved(saved.kind(),saved.sinceTick(),saved.horizonTick(),new IslandCertificate.Interval(interval.startTick(),interval.endTick(),relabel(interval.before(),nodeOffset,pipeOffset),result2),
                    IslandCertificate.Signature.of(model,policy,result2.graph()));
            return new IslandCoordinator.Certified(c.kind(),c.sinceTick(),c.baseTick(),c.horizonTick(),c.largestFlow(),Optional.of(copy));
        });
        return new IslandCoordinator.Snapshot(id,s.revision(),relabel(s.graph(),nodeOffset,pipeOffset),s.clock(),s.allowance(),anchor,result,s.status(),Map.of(),certificate);
    }
}

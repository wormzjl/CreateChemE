package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * F2 T1 probe (not committed): the topology event path of the world, driven through PhysicalRegistry with a real
 * IslandCoordinator that runs no solve, on worlds of N devices built from WP5 rest lines (tank, three pipes, tank;
 * 16 columns 6 blocks apart, rows 2 blocks apart), exactly as the rig's datapack places them: one event per block,
 * each applied before the next block. Times one placement, removal, edit and merge at N = 500, 2,000 and 5,000,
 * the registry load (server start) and a position lookup after an event (chunk load binding).
 * Output: build/reports/fluid/f2-placement-profile.txt (and stdout).
 */
class PlacementProfileProbe {
    static final String DIM="minecraft:overworld";static final int Y=64,X0=16,Z0=16;
    final FluidThermodynamics model=FluidTestSupport.networkModel();
    final MaterialCatalog catalog=MaterialCatalog.bundled();
    final WorldTopologyLedger ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(catalog));
    final IslandCoordinator coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
        public int availableWorkers(){return 0;}
        public long nextRequestId(){return 1;}
        public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command){return false;}
        public void cancel(long requestId){}
    },changed->{},System::nanoTime,IslandCoordinator.Settings.defaults(),IslandCoordinator.CommitHook.NO_MATERIAL,ledger::onlineTick);
    final PhysicalRegistry.Host host=new PhysicalRegistry.Host() {
        public IslandCoordinator coordinator(){return coordinator;}
        public void topology(String dimension,UUID event,Set<Long> affected,List<IslandCoordinator.Replacement> replacements,long committed,long online,Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,InlineFilter> releasedFilters,Runnable commit) {
            coordinator.topology(event,affected,replacements,model,committed,online,additions,removals,releasedFilters,commit);
        }
        public boolean acceptsRecovery(UUID player){return true;}
        public void refused(UUID event,String reason){}
        public void committed(Map<Long,WorldTopologyLedger.Registration> active){}
    };
    final PhysicalRegistry registry=new PhysicalRegistry(ledger,model,InlineFilter.empty(),host);
    long submitNanos,applyNanos;
    final StringBuilder out=new StringBuilder();
    void say(String s){System.out.println(s);out.append(s).append('\n');}

    WorldTopologyLedger.Registration record(long id,int x,int z,Kind kind,PhysicalFluidTopology.Direction facing) {
        var control=switch(kind){case PUMP->new FlowControl.Pump(.01,500000,1);case VALVE->new FlowControl.PressureValve(200000);default->new FlowControl.Passive();};
        var device=new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIM,x,Y,z),kind,facing,new PipeResistance.Geometry(1,.05,.000045,0),control);
        var spec=kind==Kind.GENERATOR?FluidDeviceSpec.water(catalog):FluidDeviceSpec.nitrogen(catalog);
        if(kind==Kind.RESERVOIR)spec=new FluidDeviceSpec(1,298.15,101325,spec.composition());
        return new WorldTopologyLedger.Registration(device,spec,0);
    }
    /** One event, as FluidWorldAuthority.submit runs it: queue, then apply what is ready. */
    void event(List<WorldTopologyLedger.Edit> edits,long nextId) {
        long a=System.nanoTime();registry.submit(edits,nextId,null);long b=System.nanoTime();registry.applyPending();long c=System.nanoTime();
        submitNanos+=b-a;applyNanos+=c-b;
    }
    long place(int x,int z,Kind kind){long t=System.nanoTime();if(registry.at(new PhysicalFluidTopology.Position(DIM,x,Y,z)).isPresent())throw new IllegalStateException("occupied");submitNanos+=System.nanoTime()-t;long id=ledger.nextIdentity();event(List.of(new WorldTopologyLedger.Edit(id,record(id,x,z,kind,PhysicalFluidTopology.Direction.EAST))),id+1);return id;}
    void remove(long id){event(List.of(new WorldTopologyLedger.Edit(id,null)),ledger.nextIdentity());}
    void face(long id,PhysicalFluidTopology.Direction facing) {
        var old=registry.registrations().get(id);var d=old.device();
        event(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device(d.id(),d.position(),d.kind(),facing,d.geometry(),d.control()),old.spec(),old.revision()+1))),ledger.nextIdentity());
    }
    int lines;
    void line(int n){int x=X0+6*(n%16),z=Z0+2*(n/16);String layout="RPPPR";for(int i=0;i<layout.length();i++)place(x+i,z,layout.charAt(i)=='R'?Kind.RESERVOIR:Kind.PIPE);}
    long firstPipe;
    record Op(String name,double[] submitMs,double[] applyMs) {}
    static double median(double[] v){var c=v.clone();Arrays.sort(c);return c.length%2==1?c[c.length/2]:(c[c.length/2-1]+c[c.length/2])/2;}
    static double p90(double[] v){var c=v.clone();Arrays.sort(c);return c[(int)Math.min(c.length-1,Math.ceil(.9*c.length)-1)];}
    Op measure(String name,int repeats,Runnable setup,Runnable op,Runnable teardown) {
        double[] s=new double[repeats],a=new double[repeats];
        for(int i=0;i<repeats;i++){setup.run();submitNanos=0;applyNanos=0;op.run();s[i]=submitNanos/1e6;a[i]=applyNanos/1e6;teardown.run();}
        return new Op(name,s,a);
    }
    void report(int n,Op op) {
        double[] total=new double[op.submitMs.length];for(int i=0;i<total.length;i++)total[i]=op.submitMs[i]+op.applyMs[i];
        say(String.format(Locale.ROOT,"N=%5d %-26s median %8.3f ms (submit %7.3f, apply %7.3f)  p90 %8.3f ms  runs %d",n,op.name,median(total),median(op.submitMs),median(op.applyMs),p90(total),total.length));
    }
    @Test void placementCostAgainstWorldSize() throws Exception {
        say("PlacementProfileProbe "+java.time.LocalDateTime.now()+" java "+System.getProperty("java.version"));
        int repeats=Integer.getInteger("f2.repeats",30);
        for(int target:new int[]{500,2000,5000}) {
            long started=System.nanoTime();int before=registry.registrations().size();
            while(lines*5<target)line(lines++);
            double built=(System.nanoTime()-started)/1e9;int n=registry.registrations().size();
            say(String.format(Locale.ROOT,"N=%5d built %d devices in %.2f s (%d islands)",n,n-before,built,coordinator.observe().size()));
            if(PHASES!=null){say("(build phases)");phases(n);}
            // Warm-up at this size (JIT), then the measured single events. N is restored after each pair.
            var lone=new long[1];var extension=new long[1];var bridge=new long[1];
            long pipe=registry.at(new PhysicalFluidTopology.Position(DIM,X0+2,Y,Z0)).orElseThrow().device().id();
            for(int i=0;i<10;i++){lone[0]=place(X0,Z0-10,Kind.RESERVOIR);remove(lone[0]);}
            Runnable none=()->{};
            report(n,measure("place lone tank",repeats,none,()->lone[0]=place(X0,Z0-10,Kind.RESERVOIR),()->remove(lone[0])));
            report(n,measure("remove lone tank",repeats,()->lone[0]=place(X0,Z0-10,Kind.RESERVOIR),()->remove(lone[0]),none));
            report(n,measure("extend line (pipe)",repeats,none,()->extension[0]=place(X0+1,Z0-1,Kind.PIPE),()->remove(extension[0])));
            report(n,measure("remove extension (pipe)",repeats,()->extension[0]=place(X0+1,Z0-1,Kind.PIPE),()->remove(extension[0]),none));
            report(n,measure("merge two lines (pipe)",repeats,none,()->bridge[0]=place(X0+5,Z0,Kind.PIPE),()->remove(bridge[0])));
            report(n,measure("split two lines (remove)",repeats,()->bridge[0]=place(X0+5,Z0,Kind.PIPE),()->remove(bridge[0]),none));
            report(n,measure("edit (pipe facing)",repeats,none,()->face(pipe,PhysicalFluidTopology.Direction.NORTH),()->face(pipe,PhysicalFluidTopology.Direction.EAST)));
            // A block-entity load binds through at(position): the first lookup after an event, then a warm one.
            double[] first=new double[repeats],warm=new double[repeats];var probe=new PhysicalFluidTopology.Position(DIM,X0+2,Y,Z0);
            for(int i=0;i<repeats;i++){lone[0]=place(X0,Z0-10,Kind.RESERVOIR);long a=System.nanoTime();registry.at(probe);long b=System.nanoTime();registry.at(probe);long c=System.nanoTime();first[i]=(b-a)/1e6;warm[i]=(c-b)/1e6;remove(lone[0]);}
            say(String.format(Locale.ROOT,"N=%5d %-26s first after an event median %8.3f ms, warm %8.4f ms",n,"at(position)",median(first),median(warm)));
            // Server start: a fresh registry over the same ledger and islands compiles and binds everything once.
            double[] load=new double[5];for(int i=0;i<load.length;i++){var fresh=new PhysicalRegistry(ledger,model,InlineFilter.empty(),host);long a=System.nanoTime();fresh.load();load[i]=(System.nanoTime()-a)/1e6;}
            say(String.format(Locale.ROOT,"N=%5d %-26s median %8.3f ms (5 runs)",n,"load (server start)",median(load)));
            // The marginal cost of the last 100 blocks of the build, one event each (lines of the grid).
            submitNanos=0;applyNanos=0;long t=System.nanoTime();for(int i=0;i<20;i++)line(lines++);double last=(System.nanoTime()-t)/1e6/100;
            say(String.format(Locale.ROOT,"N=%5d %-26s %8.3f ms per block (submit %.3f, apply %.3f)",n,"next 100 blocks (20 lines)",last,submitNanos/1e6/100,applyNanos/1e6/100));
            if(PHASES!=null)phases(n);
        }
        Files.createDirectories(Path.of("build/reports/fluid"));Files.writeString(Path.of("build/reports/fluid/f2-placement-profile.txt"),out.toString());
    }
    /** The compile alone: TopologyCompiler over the whole registry, and PhysicalFluidTopology's island assembly on top. */
    @Test void compileCostAgainstWorldSize() throws Exception {
        var text=new StringBuilder("CompileProfile "+java.time.LocalDateTime.now()+System.lineSeparator());
        for(int count:new int[]{100,400,1000,2000}) {
            var devices=new ArrayList<PhysicalFluidTopology.Device>();var stock=new HashMap<Long,PassiveNetwork.Reservoir>();long id=1;
            for(int n=0;n<count;n++){int x=X0+6*(n%16),z=Z0+2*(n/16);for(int i=0;i<5;i++){var kind=i==0||i==4?Kind.RESERVOIR:Kind.PIPE;var r=record(id,x+i,z,kind,PhysicalFluidTopology.Direction.EAST);devices.add(r.device());if(kind==Kind.RESERVOIR)stock.put(id,r.spec().initialize(r.device(),model,()->{}));id++;}}
            var seed=model.initialNitrogenCharge(1,298.15,101325,()->{});
            double[] whole=new double[15],inner=new double[15];
            for(int i=0;i<25;i++){long a=System.nanoTime();PhysicalFluidTopology.compile(devices,stock,Map.of(),seed);long b=System.nanoTime();
                var nodes=new ArrayList<com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Node>();var links=new ArrayList<com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Link>();
                innerInputs(devices,nodes,links);long c=System.nanoTime();com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.compile(nodes,links);long d=System.nanoTime();
                if(i>=10){whole[i-10]=(b-a)/1e6;inner[i-10]=(d-c)/1e6;}}
            var line=String.format(Locale.ROOT,"devices %5d islands %4d: PhysicalFluidTopology.compile median %8.3f ms, of which TopologyCompiler.compile about %8.3f ms",devices.size(),count,median(whole),median(inner));
            System.out.println(line);text.append(line).append(System.lineSeparator());
        }
        Files.createDirectories(Path.of("build/reports/fluid"));Files.writeString(Path.of("build/reports/fluid/f2-compile-profile.txt"),text.toString());
    }
    /** The nodes and links PhysicalFluidTopology hands TopologyCompiler (a copy of its first loop, for timing only). */
    static void innerInputs(List<PhysicalFluidTopology.Device> devices,List<com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Node> nodes,List<com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Link> links) {
        var positions=new HashMap<PhysicalFluidTopology.Position,PhysicalFluidTopology.Device>();for(var d:devices){positions.put(d.position(),d);nodes.add(new com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Node(d.id(),d.kind(),d.position().y()));}
        long virtual=-1,link=1;
        for(var d:devices)for(var direction:PhysicalFluidTopology.Direction.values()){if(!d.connects(direction))continue;var other=positions.get(d.position().offset(direction));
            if(other==null||other.id()<=d.id()||!other.connects(direction)||d.boundary()&&other.boundary())continue;
            if(d.boundary()||other.boundary())links.add(new com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Link(link++,d.id(),other.id(),d.geometry()));
            else{long middle=virtual--;nodes.add(new com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Node(middle,Kind.PIPE,d.position().y()));links.add(new com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Link(link++,d.id(),middle,d.geometry()));links.add(new com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Link(link++,middle,other.id(),other.geometry()));}}
    }
    /** With the instrumentation patch: per-phase totals since the last report, per event. */
    static final long[] PHASES=phases();
    static long[] phases(){try{return (long[])PhysicalRegistry.class.getDeclaredField("PHASES").get(null);}catch(ReflectiveOperationException none){return null;}}
    void phases(int n){var names=PHASE_NAMES();long events=PHASES[31];var line=new StringBuilder(String.format(Locale.ROOT,"N=%5d phases over %d events (ms per event):",n,events));
        for(int i=0;i<names.length;i++)if(names[i]!=null&&PHASES[i]!=0)line.append(String.format(Locale.ROOT," %s=%.4f",names[i],PHASES[i]/1e6/Math.max(1,events)));say(line.toString());Arrays.fill(PHASES,0);}
    static String[] PHASE_NAMES(){try{return (String[])PhysicalRegistry.class.getDeclaredField("PHASE_NAMES").get(null);}catch(ReflectiveOperationException none){return new String[0];}}
}

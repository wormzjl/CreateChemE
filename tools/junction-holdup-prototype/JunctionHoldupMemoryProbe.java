package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Review 8.7 E2: retained memory per island between solves. For each fixture it builds N independent islands, each
 * with its own {@link PassiveIntervalSolver}, integrates every island through a few flowing intervals, keeps the solver
 * and the accepted graph, and reads the live heap after repeated System.gc() before, with everything held, and with
 * only the graphs held (solvers dropped); plus a {@code jcmd GC.class_histogram} diff of the same two points. Fixtures:
 * the 5-node transient probe island (0.05 m bore, 4 ports, equal feeds) after two 1 s intervals of its blowdown, and a
 * 50-reservoir gas chain (methane/nitrogen, cosine pressures around 150 kPa, 100 m x 50 mm pipes, as the regression
 * harness's cosine chain) after two 1 s intervals. Prints HOLDUP_MEMORY lines; never fails.
 */
class JunctionHoldupMemoryProbe {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private record Island(PassiveIntervalSolver solver,PassiveNetwork graph) {}

    @Test void retainedPerIsland() throws Exception {
        // Warm everything once so class loading and JIT metadata are not charged to the islands.
        measure("warmup-5node",4,this::fiveNode,false);
        measure("warmup-chain50",2,()->chain(50),false);
        measure("5node",40,this::fiveNode,true);
        measure("chain50",8,()->chain(50),true);
    }

    private void measure(String label,int count,java.util.function.Supplier<PassiveNetwork> fixture,boolean report) throws Exception {
        var islands=new ArrayList<Island>();
        long before=used();String histogramBefore=report?histogram():"";
        long solveNanos=0;
        for(int i=0;i<count;i++) {
            var graph=fixture.get();var solver=new PassiveIntervalSolver(model);
            long start=System.nanoTime();
            for(int k=0;k<2;k++)graph=solver.solve(graph,1,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
            solveNanos+=System.nanoTime()-start;
            islands.add(new Island(solver,graph));
        }
        long held=used();String histogramHeld=report?histogram():"";
        var graphs=new ArrayList<PassiveNetwork>();for(var island:islands)graphs.add(island.graph());
        islands.clear();
        long graphsOnly=used();
        if(!report){graphs.clear();return;}
        int nodes=graphs.getFirst().reservoirs().size(),pipes=graphs.getFirst().pipes().size();
        System.out.println("HOLDUP_MEMORY fixture="+label+" islands="+count+" nodes="+nodes+" pipes="+pipes
                +" retainedPerIslandBytes="+(held-before)/count+" graphPerIslandBytes="+(graphsOnly-before)/count
                +" solverPerIslandBytes="+(held-graphsOnly)/count+" solveMsPerIsland="+String.format("%.1f",solveNanos/1e6/count)
                +" lowAlloc="+System.getProperty("junction.lowAlloc","off")+" jacobianReuse="+System.getProperty("junction.jacobianReuse","off"));
        for(var line:histogramDiff(histogramBefore,histogramHeld,count,15))System.out.println("HOLDUP_MEMORY_CLASS fixture="+label+" "+line);
        graphs.clear();
    }
    private static long used() throws InterruptedException {
        var runtime=Runtime.getRuntime();long last=Long.MAX_VALUE;
        for(int i=0;i<8;i++){System.gc();Thread.sleep(40);long now=runtime.totalMemory()-runtime.freeMemory();if(Math.abs(now-last)<4096)return now;last=now;}
        return last;
    }
    private static String histogram() {
        try {
            var jcmd=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"jcmd.exe":"jcmd").toString();
            var process=new ProcessBuilder(jcmd,Long.toString(ProcessHandle.current().pid()),"GC.class_histogram").redirectErrorStream(true).start();
            var text=new String(process.getInputStream().readAllBytes());process.waitFor();return text;
        }catch(Exception failure){return "jcmd failed: "+failure;}
    }
    /** Per-class instance and byte growth between two histograms, per island, largest first. */
    private static List<String> histogramDiff(String before,String after,int count,int top) {
        var a=parse(before);var b=parse(after);var rows=new ArrayList<String[]>();
        for(var e:b.entrySet()){long[] old=a.getOrDefault(e.getKey(),new long[2]);long bytes=e.getValue()[1]-old[1],instances=e.getValue()[0]-old[0];
            if(bytes>0)rows.add(new String[]{Long.toString(bytes),Long.toString(instances),e.getKey()});}
        rows.sort((x,y)->Long.compare(Long.parseLong(y[0]),Long.parseLong(x[0])));
        var out=new ArrayList<String>();long total=0;for(var r:rows)total+=Long.parseLong(r[0]);
        out.add("histogramGrowthPerIslandBytes="+total/count);
        for(int i=0;i<Math.min(top,rows.size());i++)out.add("class="+rows.get(i)[2]+" bytesPerIsland="+Long.parseLong(rows.get(i)[0])/count+" instancesPerIsland="+String.format("%.1f",Long.parseLong(rows.get(i)[1])/(double)count));
        return out;
    }
    private static Map<String,long[]> parse(String text) {
        var map=new HashMap<String,long[]>();
        for(var line:text.split("\\R")) {
            var parts=line.trim().split("\\s+");
            if(parts.length<4||!parts[0].endsWith(":"))continue;
            try{map.put(parts[3],new long[]{Long.parseLong(parts[1]),Long.parseLong(parts[2])});}catch(NumberFormatException ignored){}
        }
        return map;
    }

    /** The transient probe's 5-node island: two 1 m3 tanks at 150 kPa (methane, nitrogen), 350 K, 2 void outlets, 50 mm. */
    private PassiveNetwork fiveNode() {
        int[][] positions={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1}};
        var geometry=new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(new PhysicalFluidTopology.Device(100,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),Kind.PIPE,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive()));
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<4;i++){
            int[] at=positions[i];var kind=i<2?Kind.RESERVOIR:Kind.VOID;
            var d=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",at[0],at[1],at[2]),kind,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive());devices.add(d);
            double pressure=i<2?150000:101325;
            double[] n=new double[mw.length];n[i==0?0:MaterialTestBasis.NITROGEN]=1;
            var unit=model.flashTP(350,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
            var state=model.flashTP(350,pressure,n,()->{});
            boundaries.put(d.id(),new PassiveNetwork.Reservoir(d.id(),at[1],state,i<2?PassiveNetwork.NodeKind.RESERVOIR:PassiveNetwork.NodeKind.VOID));
        }
        return PassiveNetwork.sizeJunctionHoldups(PhysicalFluidTopology.compile(devices,boundaries).islands().getFirst().graph(),model);
    }
    /** A chain of gas tanks (0.7 methane / 0.3 nitrogen, 350 K, 150 kPa + 1 kPa cos(0.7 i)) joined by 100 m x 50 mm pipes. */
    private PassiveNetwork chain(int count) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(int i=0;i<count;i++) {
            double pressure=150000+1000*Math.cos(i*.7);double[] n=new double[mw.length];n[0]=.7;n[MaterialTestBasis.NITROGEN]=.3;
            var unit=model.flashTP(350,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
            var state=model.flashTP(350,pressure,n,()->{});
            nodes.add(new PassiveNetwork.Reservoir(i+1,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,n,state.internalEnergy())));
            if(i>0)pipes.add(new PassiveNetwork.Pipe(i,i-1,i,new PipeResistance.Geometry(100,.05,.000045,0)));
        }
        return new PassiveNetwork(nodes,pipes);
    }
}

import com.google.gson.*;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.state.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.*;
import java.nio.file.*;
import java.util.*;

/** Scratch bitwise probe (not in the repo): chain-100 exactly as SolverRegressionHarness replays it, plus a set of
 * all-BULK scenarios whose every double is dumped in hex, for a before/after comparison of WP1.
 * D9 extension (tools/phase-ports-probes/d9): {@code gas-ports.txt}, gas-only islands whose vessels carry LIQUID (and
 * VAPOR) ports, which the level head must leave bitwise; {@code liquid-ports.txt}, islands whose LIQUID port sees a
 * liquid, which the head is expected to move (dumped for the record, not a gate). */
public class BitwiseProbe {
    static final Runnable NOOP=()->{};
    static StringBuilder out=new StringBuilder();
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]);Files.createDirectories(dir);
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9,
                FluidThermodynamics.DEFAULT_MAXIMUM_VELOCITY,FluidThermodynamics.DEFAULT_TRACE_CUTOFF_MOLE_FRACTION);
        // ---- chain-100, the harness replay ----
        var chain=cosineChain(model,100);var retained=new RetainedSolver();
        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        PassiveIntervalSolver.Result result;
        try{result=retained.solve(model,chain,5,PassiveIntervalSolver.Settings.defaults(),NOOP);}finally{SolverDiagnostics.ENABLED=false;}
        Files.writeString(dir.resolve("chain-100.json"),new Gson().toJson(encode("chain-100",result))+"\n");
        // ---- scenarios ----
        var m2=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
        for(double bore:new double[]{.05,.02})for(int ports:new int[]{4,6})scenario("mixed "+bore+":"+ports,()->mixed(m2,bore,ports,true,5));
        scenario("mixed 0.02:4 at 0.1",()->mixed(m2,.02,4,true,.1));
        scenario("rising water line",()->elevated(m2,true,5,4));
        scenario("falling water line",()->elevated(m2,false,5,4));
        scenario("rising water line 0.1",()->elevated(m2,true,.1,30));
        scenario("pumped water line",()->pumped(m2,5,4));
        scenario("pumped water line 0.1",()->pumped(m2,.1,20));
        scenario("wet crude drain",()->crude(m2,5,3));
        scenario("wet crude drain 0.1",()->crude(m2,.1,10));
        scenario("slurry filter",()->slurry(m2,5,3));
        scenario("valve",()->valve(m2,5,3));
        Files.writeString(dir.resolve("scenarios.txt"),out.toString());
        // ---- D9: gas-only islands with phase ports (must stay bitwise) ----
        out=new StringBuilder();
        scenario("gas generator into a LIQUID port 5",()->gasIntoLiquidPort(m2,5,4));
        scenario("gas generator into a LIQUID port 0.1",()->gasIntoLiquidPort(m2,.1,20));
        scenario("gas tank pair VAPOR to LIQUID 5",()->gasTankPair(m2,5,4));
        scenario("gas tank pair VAPOR to LIQUID 0.1",()->gasTankPair(m2,.1,20));
        scenario("mixed gas junction with LIQUID ports 5",()->gasJunctionPorts(m2,5,3));
        scenario("mixed gas junction with LIQUID ports 0.1",()->gasJunctionPorts(m2,.1,20));
        scenario("dead-headed rising LIQUID port 5",()->deadHeadedRising(m2,5,4));
        scenario("dry LIQUID drain refused 5",()->dryDrain(m2,5,3));
        Files.writeString(dir.resolve("gas-ports.txt"),out.toString());
        // ---- D9: LIQUID ports that see a liquid (expected to move with the head) ----
        out=new StringBuilder();
        scenario("rising water line into a LIQUID port 5",()->risingIntoLiquidPort(m2,5,40));
        scenario("water drain through a LIQUID port 5",()->waterDrain(m2,5,6));
        Files.writeString(dir.resolve("liquid-ports.txt"),out.toString());
        System.out.println("probe written to "+dir);
    }
    interface Body{void run() throws Exception;}
    static void scenario(String name,Body body){out.append("### ").append(name).append('\n');
        try{body.run();}catch(Throwable failure){out.append("FAILED ").append(failure).append('\n');}}
    static String h(double v){return Double.toHexString(v);}
    static void dump(PassiveIntervalSolver.Result r) {
        out.append("acc=").append(r.acceptedSubsteps()).append(" rej=").append(r.rejectedSubsteps()).append(" work=").append(h(r.pumpWorkJoule())).append(" modes=").append(r.endpointModes()).append('\n');
        for(var node:r.graph().reservoirs()){var s=node.state();var inv=node.inventory();
            out.append(" n").append(node.id()).append(' ').append(h(s.temperature())).append(' ').append(h(s.pressure())).append(' ').append(h(s.mass())).append(' ').append(h(s.volume()))
               .append(' ').append(h(s.liquidVolume())).append(' ').append(h(s.vaporVolume())).append(' ').append(h(s.waterVolume())).append(" U=").append(h(inv.internalEnergy())).append(" n=");
            for(double n:inv.moles())out.append(h(n)).append(',');
            out.append(" solids=").append(h(inv.solids().massKg())).append('\n');}
        out.append(" flows=");for(double q:r.averageMassFlows())out.append(h(q)).append(',');out.append('\n');
        out.append(" heads=");for(double q:r.endpointHeads())out.append(h(q)).append(',');out.append('\n');
        for(var b:r.boundaries()){out.append(" b").append(b.nodeId()).append(' ').append(h(b.totalEnergyJoule())).append(' ');for(double n:b.moles())out.append(h(n)).append(',');out.append(' ').append(h(b.solids().massKg())).append('\n');}
        for(var t:r.pipeTransfers())for(var s:new PipeTransfer.Stream[]{t.forward(),t.reverse()}){out.append(" t").append(t.pipeId()).append(' ').append(h(s.massKg())).append(' ');
            for(var p:s.phaseMoles())for(double n:p)out.append(h(n)).append(',');for(double v:s.phaseVolumes())out.append(h(v)).append(',');out.append(h(s.solids().massKg())).append('\n');}
        for(var p:r.graph().pipes())if(p.filter()!=null)out.append(" f").append(p.id()).append(' ').append(h(p.filter().captured().massKg())).append(' ').append(h(p.filter().energyJoule())).append('\n');
    }
    static void run(FluidThermodynamics model,PassiveNetwork graph,double interval,int count,List<ScheduledTransfer> transfers) {
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        for(int i=0;i<count;i++){
            graph=new PassiveNetwork(graph.reservoirs(),graph.pipes(),transfers);
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var r=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),NOOP);committed=r;graph=r.graph();
            out.append("interval ").append(i).append(' ');dump(r);
        }
    }
    static FluidThermodynamics.State charge(FluidThermodynamics model,double[] n,double t,double p,double volume) {
        n=n.clone();var unit=model.flashTP(t,p,n,NOOP);for(int c=0;c<n.length;c++)n[c]*=volume/unit.volume();return model.flashTP(t,p,n,NOOP);
    }
    static double[] pure(FluidThermodynamics model,String name){var n=new double[model.componentCount()];n[model.components().indexOf(name)]=1;return n;}
    static void mixed(FluidThermodynamics model,double bore,int ports,boolean unequal,double interval) {
        double[] mw=model.molecularWeights();
        int[][] positions={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1},{0,1,0},{0,-1,0}};
        var geometry=new PipeResistance.Geometry(1,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(new PhysicalFluidTopology.Device(100,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),Kind.PIPE,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive()));
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();int nitrogen=model.components().indexOf("Nitrogen");
        for(int i=0;i<ports;i++){
            int[] at=positions[i];var kind=i<2?Kind.RESERVOIR:Kind.VOID;
            var d=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",at[0],at[1],at[2]),kind,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive());devices.add(d);
            double pressure=i==0?150000:i==1?(unequal?90000:150000):101325;double temperature=i==1&&unequal?400:350;
            double[] n=new double[mw.length];n[i==0?0:nitrogen]=1;
            var state=charge(model,n,temperature,pressure,1);
            boundaries.put(d.id(),new PassiveNetwork.Reservoir(d.id(),at[1],state,i<2?PassiveNetwork.NodeKind.RESERVOIR:PassiveNetwork.NodeKind.VOID));
        }
        var graph=PassiveNetwork.sizeJunctionHoldups(PhysicalFluidTopology.compile(devices,boundaries).islands().getFirst().graph(),model);
        int tank=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==1)tank=i;
        double[] feed=new double[mw.length];feed[0]=.5;var feedState=model.flashTP(350,150000,feed,NOOP);
        double feedEnergy=feedState.enthalpy()+.5*mw[0]*PassiveStepSolver.GRAVITY*graph.reservoirs().get(tank).elevation();
        int steps=(int)Math.round(10/interval);run(model,graph,interval,steps,List.of());
    }
    static void elevated(FluidThermodynamics model,boolean rising,double interval,int count) {
        var water=model.flashTP(298.15,400000,pure(model,"Water"),NOOP);
        var tank=model.initialNitrogenCharge(1,298.15,150000,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,rising?0:4,water,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,rising?4:0,tank));
        run(model,new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(4,.05,.000045,0)))),interval,count,List.of());
    }
    static void pumped(FluidThermodynamics model,double interval,int count) {
        var water=model.flashTP(298.15,101325,pure(model,"Water"),NOOP);
        var tank=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,water,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,water,PassiveNetwork.NodeKind.JUNCTION),new PassiveNetwork.Reservoir(3,0,tank));
        var g=new PipeResistance.Geometry(1,.05,.000045,0);
        run(model,new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(10,0,1,g),new PassiveNetwork.Pipe(11,1,2,g,new FlowControl.Pump(.002,400000,.7)))),interval,count,List.of());
    }
    static void crude(FluidThermodynamics model,double interval,int count) {
        var crude=V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane");
        var feed=crude.crudeFeed("createcheme:tia_juana_light_methane").moleFractions();
        var composition=new double[model.componentCount()];
        for(int c=0;c<feed.length;c++)composition[model.components().indexOf(crude.componentBasis().componentId(c))]=feed[c];
        composition[model.components().indexOf("Nitrogen")]=.1;composition[model.components().indexOf("Water")]=.2;
        var tank=charge(model,composition,350,200000,1);var other=model.initialNitrogenCharge(1,350,150000,NOOP);
        var sink=model.initialNitrogenCharge(1,350,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,other),new PassiveNetwork.Reservoir(3,0,sink,PassiveNetwork.NodeKind.VOID));
        var g=new PipeResistance.Geometry(10,.02,.000045,0);
        run(model,new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(10,0,1,g),new PassiveNetwork.Pipe(11,0,2,g))),interval,count,List.of());
    }
    static void slurry(FluidThermodynamics model,double interval,int count) {
        var solids=new SolidInventory(List.of(new SolidInventory.Population(model.solids.require("createcheme:demo_particle"),ParticleSize.micrometres("100"),100)));
        var a=charge(model,pure(model,"Water"),298.15,160000,1);var b=charge(model,pure(model,"Water"),298.15,150000,1);
        a=model.flashTP(298.15,160000,PhaseLayout.totalAmounts(a),NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,a.withSolids(solids)),new PassiveNetwork.Reservoir(2,0,b));
        var pipe=new PassiveNetwork.Pipe(10,0,1,List.of(new PipeResistance.Geometry(2,.05,.000045,0)),new FlowControl.Passive(),0,InlineFilter.empty());
        run(model,new PassiveNetwork(nodes,List.of(pipe)),interval,count,List.of());
    }
    static void valve(FluidThermodynamics model,double interval,int count) {
        var tank=model.initialNitrogenCharge(1,298.15,300000,NOOP);var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.VOID));
        run(model,new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(2,.02,.000045,0),new FlowControl.PressureValve(250000)))),interval,count,List.of());
    }
    static PassiveNetwork.Pipe port(long id,int a,int b,PipeResistance.Geometry g,PassiveNetwork.PhasePort pa,PassiveNetwork.PhasePort pb) {
        return new PassiveNetwork.Pipe(id,a,b,List.of(g),new FlowControl.Passive(),0,null,pa,pb);
    }
    static final PassiveNetwork.PhasePort BULK=PassiveNetwork.PhasePort.BULK,VAPOR=PassiveNetwork.PhasePort.VAPOR,LIQUID=PassiveNetwork.PhasePort.LIQUID;
    static void gasIntoLiquidPort(FluidThermodynamics model,double interval,int count) {
        var supply=model.flashTP(298.15,200000,pure(model,"Nitrogen"),NOOP);var tank=model.initialNitrogenCharge(1,298.15,150000,NOOP);var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,supply,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,tank),new PassiveNetwork.Reservoir(3,0,sink,PassiveNetwork.NodeKind.VOID));
        var g=new PipeResistance.Geometry(10,.02,.000045,0);
        run(model,new PassiveNetwork(nodes,List.of(port(10,0,1,g,BULK,LIQUID),port(11,1,2,new PipeResistance.Geometry(10,.01,.000045,0),VAPOR,BULK))),interval,count,List.of());
    }
    static void gasTankPair(FluidThermodynamics model,double interval,int count) {
        var a=model.initialNitrogenCharge(1,298.15,200000,NOOP);var b=model.initialNitrogenCharge(1,298.15,120000,NOOP);var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,a),new PassiveNetwork.Reservoir(2,1,b),new PassiveNetwork.Reservoir(3,0,sink,PassiveNetwork.NodeKind.VOID));
        var g=new PipeResistance.Geometry(10,.02,.000045,0);
        run(model,new PassiveNetwork(nodes,List.of(port(10,0,1,g,VAPOR,LIQUID),port(11,1,2,new PipeResistance.Geometry(10,.01,.000045,0),LIQUID,BULK))),interval,count,List.of());
    }
    static void gasJunctionPorts(FluidThermodynamics model,double interval,int count) {
        var methane=charge(model,pure(model,"Methane"),350,150000,1);var nitrogen=charge(model,pure(model,"Nitrogen"),400,90000,1);
        var sink=model.initialNitrogenCharge(1,350,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,methane),new PassiveNetwork.Reservoir(2,0,nitrogen),new PassiveNetwork.Reservoir(3,0,methane,PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(4,0,sink,PassiveNetwork.NodeKind.VOID));
        var g=new PipeResistance.Geometry(1,.02,.000045,0);
        var graph=PassiveNetwork.sizeJunctionHoldups(new PassiveNetwork(nodes,List.of(port(10,0,2,g,VAPOR,BULK),port(11,1,2,g,LIQUID,BULK),port(12,2,3,g,BULK,BULK))),model);
        run(model,graph,interval,count,List.of());
    }
    static void deadHeadedRising(FluidThermodynamics model,double interval,int count) {
        var water=model.flashTP(298.15,150000,pure(model,"Water"),NOOP);var tank=model.initialNitrogenCharge(1,298.15,150000,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,water,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,4,tank));
        run(model,new PassiveNetwork(nodes,List.of(port(10,0,1,new PipeResistance.Geometry(4,.05,.000045,0),BULK,LIQUID))),interval,count,List.of());
    }
    static void dryDrain(FluidThermodynamics model,double interval,int count) {
        var tank=model.initialNitrogenCharge(1,298.15,150000,NOOP);var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.VOID));
        run(model,new PassiveNetwork(nodes,List.of(port(10,0,1,new PipeResistance.Geometry(10,.02,.000045,0),LIQUID,BULK))),interval,count,List.of());
    }
    static void risingIntoLiquidPort(FluidThermodynamics model,double interval,int count) {
        var water=model.flashTP(298.15,400000,pure(model,"Water"),NOOP);var tank=model.initialNitrogenCharge(1,298.15,150000,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,water,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,4,tank));
        run(model,new PassiveNetwork(nodes,List.of(port(10,0,1,new PipeResistance.Geometry(4,.05,.000045,0),BULK,LIQUID))),interval,count,List.of());
    }
    static void waterDrain(FluidThermodynamics model,double interval,int count) {
        double[] n=new double[model.componentCount()];int w=model.components().indexOf("Water"),nn=model.components().indexOf("Nitrogen");
        n[w]=.5*997/model.molecularWeights()[w];n[nn]=101325*.5/(FluidThermodynamics.R*298.15);
        var tank=charge(model,n,298.15,101325,1);var sink=model.initialNitrogenCharge(1,298.15,101325,NOOP);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,tank),new PassiveNetwork.Reservoir(2,0,sink,PassiveNetwork.NodeKind.VOID));
        run(model,new PassiveNetwork(nodes,List.of(port(10,0,1,new PipeResistance.Geometry(10,.025,.000045,0),LIQUID,BULK))),interval,count,List.of());
    }
    static PassiveNetwork cosineChain(FluidThermodynamics model,int count) {
        var crude=V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane");
        var feed=crude.crudeFeed("createcheme:tia_juana_light_methane").moleFractions();
        var composition=new double[model.componentCount()];
        for(int c=0;c<feed.length;c++)composition[model.components().indexOf(crude.componentBasis().componentId(c))]=feed[c];
        composition[model.components().indexOf("Nitrogen")]=.1;composition[model.components().indexOf("Water")]=.2;
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(int i=0;i<count;i++) {
            double pressure=150000+1000*Math.cos(i*.7);var n=composition.clone();var unit=model.flashTP(350,pressure,n,NOOP);
            for(int c=0;c<n.length;c++)n[c]/=unit.volume();var state=model.flashTP(350,pressure,n,NOOP);
            nodes.add(new PassiveNetwork.Reservoir(i+1,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,n,state.internalEnergy())));
            if(i>0)pipes.add(new PassiveNetwork.Pipe(i,i-1,i,new PipeResistance.Geometry(100,.05,.000045,0)));
        }
        return new PassiveNetwork(nodes,pipes);
    }
    static JsonObject encode(String fixture,PassiveIntervalSolver.Result result) {
        var root=new JsonObject();root.addProperty("fixture",fixture);var intervals=new JsonArray();
        var entry=new JsonObject();entry.addProperty("interval",0);
        entry.addProperty("accepted",result.acceptedSubsteps());entry.addProperty("rejected",result.rejectedSubsteps());
        var nodes=new JsonArray();
        for(var reservoir:result.graph().reservoirs()) {
            var s=reservoir.state();var item=new JsonObject();item.addProperty("id",reservoir.id());
            item.addProperty("temperature",s.temperature());item.addProperty("pressure",s.pressure());
            item.addProperty("mass",s.mass());item.addProperty("volume",s.volume());
            item.addProperty("liquidVolume",s.liquidVolume());item.addProperty("vaporVolume",s.vaporVolume());
            item.addProperty("waterVolume",s.waterVolume());item.add("moles",numbers(PhaseLayout.totalAmounts(s)));
            nodes.add(item);
        }
        entry.add("nodes",nodes);entry.add("flows",numbers(result.averageMassFlows()));intervals.add(entry);
        root.add("intervals",intervals);return root;
    }
    static JsonArray numbers(double[] values){var a=new JsonArray(values.length);for(double v:values)a.add(v);return a;}
}

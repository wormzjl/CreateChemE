package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Work in progress: measurement runner. */
class ExtremeTopologyIslandTest {
    private static final String DIMENSION="minecraft:overworld";
    private static final int Y=64;
    private static final PipeResistance.Geometry BLOCK=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final double TEMPERATURE=350;
    static final double[] GENERATORS={110e3,140e3,170e3,200e3,130e3,160e3,190e3,120e3,150e3,180e3};
    static final double[] TANKS={140e3,110e3,180e3,150e3,120e3,190e3,160e3,130e3,100e3,170e3};
    static final double GENERATOR_CENTRE=155e3,TANK_CENTRE=145e3;
    static final double ZERO_FLOW=1e-10,DIRECTION_MARGIN=100;
    static final double COMPONENT_LEDGER=1e-12,ENERGY_LEDGER=1e-10,CADENCE_TOLERANCE=.02;
    private final FluidThermodynamics defaultModel=FluidTestSupport.networkModel();

    enum Geometry{GRID,ALTERNATING,ALTERNATING_BOTH_SIDES}
    enum Fluid{NITROGEN,WATER}
    record Case(Geometry geometry,int pairs,double spread,Fluid fluid) {
        String name(){return String.format(Locale.ROOT,"%s-%d@%.2f-%s",geometry.name().toLowerCase(Locale.ROOT),pairs,spread,fluid.name().toLowerCase(Locale.ROOT));}
        double generator(int i){return GENERATOR_CENTRE+spread*(GENERATORS[i]-GENERATOR_CENTRE);}
        double tank(int i){return TANK_CENTRE+spread*(TANKS[i]-TANK_CENTRE);}
    }
    private static PhysicalFluidTopology.Device device(long id,int x,int z,Kind kind){
        return new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIMENSION,x,Y,z),kind,PhysicalFluidTopology.Direction.EAST,BLOCK,new FlowControl.Passive());
    }
    record Placement(List<PhysicalFluidTopology.Device> devices,Map<Long,Double> pressures) {}
    static Placement place(Case c) {
        var d=new ArrayList<PhysicalFluidTopology.Device>();var p=new LinkedHashMap<Long,Double>();int n=c.pairs();
        switch(c.geometry()) {
            case GRID->{
                for(int x=0;x<n;x++){d.add(device(1+x,x,0,Kind.GENERATOR));p.put(1L+x,c.generator(x));}
                for(int x=0;x<n;x++)d.add(device(11+x,x,1,Kind.PIPE));
                for(int x=0;x<n;x++)d.add(device(21+x,x,2,Kind.PIPE));
                for(int x=0;x<n;x++){d.add(device(31+x,x,3,Kind.RESERVOIR));p.put(31L+x,c.tank(x));}
            }
            case ALTERNATING,ALTERNATING_BOTH_SIDES->{
                for(int x=0;x<2*n;x++){boolean g=x%2==0;d.add(device(1+x,x,0,g?Kind.GENERATOR:Kind.RESERVOIR));p.put(1L+x,g?c.generator(x/2):c.tank(x/2));}
                for(int x=0;x<2*n;x++)d.add(device(21+x,x,1,Kind.PIPE));
                if(c.geometry()==Geometry.ALTERNATING_BOTH_SIDES)for(int x=0;x<2*n;x++)d.add(device(41+x,x,-1,Kind.PIPE));
            }
        }
        return new Placement(d,p);
    }
    private double[] pure(FluidThermodynamics model,boolean water){double[] n=new double[model.components().size()];n[water?n.length-1:MaterialTestBasis.NITROGEN]=1;return n;}

    record Compiled(int islands,int physical,PassiveNetwork graph,Map<Long,String> diagnostics) {}
    Compiled compile(Case c,FluidThermodynamics model) {
        var placement=place(c);var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        for(var dev:placement.devices())if(placement.pressures().containsKey(dev.id())) {
            boolean water=c.fluid()==Fluid.WATER&&dev.kind()==Kind.GENERATOR;
            boundaries.put(dev.id(),new FluidDeviceSpec(1,TEMPERATURE,placement.pressures().get(dev.id()),pure(model,water)).initialize(dev,model,()->{}));
        }
        var compiled=PhysicalFluidTopology.compile(placement.devices(),boundaries);
        var island=compiled.islands().getFirst();
        return new Compiled(compiled.islands().size(),island.physicalIds().size(),PassiveNetwork.sizeJunctionHoldups(island.graph(),model),compiled.diagnostics());
    }

    record Frame(double time,double[] tankPressures,double[] tankTemperatures,double[][] tankMoles,double[] supplied) {}
    static final class Outcome {
        Case c;double slice;int intervals,completed;String failure;long newton,ms;int accepted,rejected;
        Map<String,Integer> reasons=new TreeMap<>();double worstMoles,worstEnergy;List<Frame> frames=new ArrayList<>();
        List<String> violations=new ArrayList<>(),directions=new ArrayList<>();PassiveNetwork initial,graph;PassiveIntervalSolver.Result last;
        double[] minTank;int monotoneTanks;double pMax;
        boolean ok(){return failure==null;}
        String line(){
            return String.format(Locale.ROOT,"EXTREME_TOPOLOGY case=%s interval=%s intervals=%d/%d newtonSolves=%d accepted=%d rejected=%d worstMoles=%.3g worstEnergy=%.3g ms=%d%s",
                    c.name(),slice==5?"5":"0.1",completed,intervals,newton,accepted,rejected,worstMoles,worstEnergy,ms,failure==null?"":" FAILED "+failure);
        }
    }

    Outcome integrate(Case c,double slice,int count,FluidThermodynamics model) {
        var o=new Outcome();o.c=c;o.slice=slice;o.intervals=count;
        var graph=compile(c,model).graph();o.initial=graph;
        double[] mw=model.molecularWeights();
        var nodes=graph.reservoirs();
        List<Integer> tanks=new ArrayList<>(),generators=new ArrayList<>();
        for(int i=0;i<nodes.size();i++){if(nodes.get(i).kind()==PassiveNetwork.NodeKind.RESERVOIR)tanks.add(i);if(nodes.get(i).kind()==PassiveNetwork.NodeKind.GENERATOR)generators.add(i);}
        o.pMax=generators.stream().mapToDouble(i->nodes.get(i).state().pressure()).max().orElseThrow();
        Map<Long,Integer> generatorSlot=new HashMap<>();for(int g=0;g<generators.size();g++)generatorSlot.put(nodes.get(generators.get(g)).id(),g);
        double[] initial=totals(graph),external=new double[initial.length];double initialEnergy=energy(graph,mw),externalEnergy=0;
        double[] supplied=new double[generators.size()];
        o.minTank=new double[count+1];o.minTank[0]=tanks.stream().mapToDouble(i->nodes.get(i).state().pressure()).min().orElseThrow();
        double[][] tankHistory=new double[tanks.size()][count+1];for(int t=0;t<tanks.size();t++)tankHistory[t][0]=nodes.get(tanks.get(t)).state().pressure();
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        long start=System.nanoTime();int framesEvery=(int)Math.round(5/slice);
        try {
            for(int i=0;i<count;i++) {
                solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                PassiveIntervalSolver.Result result;
                try{result=solver.solve(graph,slice,PassiveIntervalSolver.Settings.defaults(),()->{});}
                catch(RuntimeException held){o.failure="interval "+(i+1)+" (t="+(slice*i)+" s): "+held;break;}
                if(result.acceptance()!=PassiveStepSolver.Acceptance.FULL||result.advancedSeconds()!=slice)o.violations.add("interval "+(i+1)+" not a full interval: "+result.acceptance()+" "+result.advancedSeconds());
                committed=result;graph=result.graph();o.completed++;o.accepted+=result.acceptedSubsteps();o.rejected+=result.rejectedSubsteps();
                result.rejectionReasons().forEach((k,v)->o.reasons.merge(k,v,Integer::sum));
                for(var transfer:result.boundaries()) {
                    var n=transfer.moles();double mass=0;
                    for(int k=0;k<n.length;k++){external[k]+=n[k];mass+=n[k]*mw[k];}externalEnergy+=transfer.totalEnergyJoule();
                    Integer g=generatorSlot.get(transfer.nodeId());
                    if(g==null)o.violations.add("interval "+(i+1)+": boundary transfer at a non-generator "+transfer.nodeId());
                    else supplied[g]+=mass;
                    if(mass<-ZERO_FLOW*slice)o.violations.add("interval "+(i+1)+": generator "+transfer.nodeId()+" received "+(-mass)+" kg");
                }
                var sum=totals(graph);
                for(int k=0;k<sum.length;k++){double e=Math.abs(sum[k]-initial[k]-external[k])/Math.max(1,initial[k]);o.worstMoles=Math.max(o.worstMoles,e);}
                double e=Math.abs(energy(graph,mw)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));o.worstEnergy=Math.max(o.worstEnergy,e);
                var now=graph.reservoirs();
                double min=Double.POSITIVE_INFINITY;
                for(int t=0;t<tanks.size();t++){double p=now.get(tanks.get(t)).state().pressure();tankHistory[t][i+1]=p;min=Math.min(min,p);
                    if(p>o.pMax*(1+1e-9))o.violations.add("interval "+(i+1)+": tank "+now.get(tanks.get(t)).id()+" at "+p+" Pa above the highest source "+o.pMax);}
                o.minTank[i+1]=min;
                if(min<o.minTank[i]*(1-1e-9))o.violations.add("interval "+(i+1)+": lowest tank fell from "+o.minTank[i]+" to "+min+" Pa");
                var flows=result.averageMassFlows();
                if(slice<1||i==count-1)for(var error:directionErrors(graph,flows,slice))o.directions.add("interval "+(i+1)+": "+error);
                if((i+1)%framesEvery==0) {
                    double[] p=new double[tanks.size()],temperature=new double[tanks.size()];double[][] n=new double[tanks.size()][];
                    for(int t=0;t<tanks.size();t++){p[t]=now.get(tanks.get(t)).state().pressure();temperature[t]=now.get(tanks.get(t)).state().temperature();n[t]=now.get(tanks.get(t)).inventory().moles();}
                    o.frames.add(new Frame((i+1)*slice,p,temperature,n,supplied.clone()));
                }
            }
        } finally {
            o.newton=SolverDiagnostics.sample().value("newtonSolves");SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();
            o.ms=(System.nanoTime()-start)/1000000;
        }
        o.graph=graph;o.last=committed;
        for(int t=0;t<tanks.size();t++){boolean mono=true;double[] h=tankHistory[t];for(int k=1;k<=o.completed;k++)if(h[k]<h[k-1]*(1-1e-9))mono=false;if(mono)o.monotoneTanks++;}
        return o;
    }
    static List<String> directionErrors(PassiveNetwork graph,double[] flows,double slice) {
        var errors=new ArrayList<String>();var nodes=graph.reservoirs();
        for(int i=0;i<flows.length;i++) {
            var pipe=graph.pipes().get(i);var a=nodes.get(pipe.first());var b=nodes.get(pipe.second());
            boolean aBoundary=!a.junction(),bBoundary=!b.junction();if(aBoundary==bBoundary)continue;
            var boundary=aBoundary?a:b;var junction=aBoundary?b:a;double out=aBoundary?flows[i]:-flows[i];
            double dp=boundary.state().pressure()-junction.state().pressure();
            String what=boundary.kind()+" "+boundary.id()+" at "+String.format(Locale.ROOT,"%.1f",boundary.state().pressure())+" Pa, junction "+String.format(Locale.ROOT,"%.1f",junction.state().pressure())+" Pa, outflow "+out+" kg/s";
            if(dp>DIRECTION_MARGIN&&!(out>0))errors.add(what+": above its junction but not supplying");
            if(dp<-DIRECTION_MARGIN&&boundary.kind()==PassiveNetwork.NodeKind.GENERATOR&&Math.abs(out)>ZERO_FLOW)errors.add(what+": below its junction but not closed");
            if(dp<-DIRECTION_MARGIN&&boundary.kind()==PassiveNetwork.NodeKind.RESERVOIR&&!(out<0))errors.add(what+": below its junction but not receiving");
        }
        return errors;
    }
    private static double[] totals(PassiveNetwork graph) {
        double[] t=null;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){var n=node.inventory().moles();if(t==null)t=new double[n.length];for(int c=0;c<n.length;c++)t[c]+=n[c];}
        return t;
    }
    private static double energy(PassiveNetwork graph,double[] mw) {
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){
            double mass=0;var n=node.inventory().moles();for(int c=0;c<n.length;c++)mass+=mw[c]*n[c];
            energy+=node.inventory().internalEnergy()+mass*PassiveStepSolver.GRAVITY*node.elevation();
        }
        return energy;
    }
    String trajectory(Outcome o) {
        var s=new StringBuilder();
        for(var f:o.frames)if(Math.round(f.time())%(o.slice==5?25:5)==0||f.time()==5){s.append(String.format(Locale.ROOT,"\n   t=%5.0f",f.time()));for(double p:f.tankPressures())s.append(String.format(Locale.ROOT," %6.0f",p));}
        return s.toString();
    }

    String detail(Outcome o){
        double dev=0;for(var n:o.graph.reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)dev=Math.max(dev,Math.abs(n.state().pressure()-o.pMax));
        return " monotoneTanks="+o.monotoneTanks+" endDeviation="+dev+" violations="+o.violations.size()+(o.violations.isEmpty()?"":" first="+o.violations.getFirst())
            +" directions="+o.directions.size()+(o.directions.isEmpty()?"":" first="+o.directions.getFirst()+" last="+o.directions.getLast())+" reasons="+o.reasons;
    }
    String compare(Outcome coarse,Outcome fine) {
        double dp=0,dt=0,dn=0,dm=0,dg=0;
        for(var f:coarse.frames){var g=fine.frames.stream().filter(x->Math.abs(x.time()-f.time())<1e-6).findFirst().orElse(null);if(g==null)continue;
            double total=Arrays.stream(g.supplied()).sum();
            for(int t=0;t<f.tankPressures().length;t++){dp=Math.max(dp,Math.abs(f.tankPressures()[t]-g.tankPressures()[t])/g.tankPressures()[t]);dt=Math.max(dt,Math.abs(f.tankTemperatures()[t]-g.tankTemperatures()[t]));
                for(int k=0;k<f.tankMoles()[t].length;k++)dn=Math.max(dn,Math.abs(f.tankMoles()[t][k]-g.tankMoles()[t][k])/Math.max(1e-30,Math.abs(g.tankMoles()[t][k])));}
            dm=Math.max(dm,Math.abs(Arrays.stream(f.supplied()).sum()-total)/total);
            for(int k=0;k<f.supplied().length;k++)dg=Math.max(dg,Math.abs(f.supplied()[k]-g.supplied()[k])/total);}
        return String.format(Locale.ROOT,"CADENCE %s dP=%.3g dT=%.3g K dn=%.3g dMass=%.3g dGen=%.3g",coarse.c.name(),dp,dt,dn,dm,dg);
    }
    @Test void scan() {
        var fast=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9,100000);
        for(var g:Geometry.values())for(double f:new double[]{.2,.22,.24,.26,.28})for(double slice:new double[]{5,.1}){
            var o=integrate(new Case(g,10,f,Fluid.NITROGEN),slice,1,defaultModel);System.out.println("B "+o.line());}
        for(var g:Geometry.values())for(int pairs=2;pairs<=10;pairs++)for(double slice:new double[]{5,.1}){
            if(g==Geometry.GRID&&pairs>3)continue;
            var o=integrate(new Case(g,pairs,1,Fluid.NITROGEN),slice,1,defaultModel);System.out.println("N "+o.line());}
        for(var entry:List.of(Map.entry(defaultModel,.2),Map.entry(fast,1.0)))for(var g:Geometry.values()){
            var c=new Case(g,10,entry.getValue(),Fluid.NITROGEN);
            var coarse=integrate(c,5,40,entry.getKey());var fine=integrate(c,.1,400,entry.getKey());
            System.out.println((entry.getKey()==fast?"[v=1e5] ":"")+coarse.line()+detail(coarse));
            System.out.println((entry.getKey()==fast?"[v=1e5] ":"")+fine.line()+detail(fine));
            if(coarse.ok()&&fine.ok())System.out.println(compare(coarse,fine));
        }
        for(var entry:List.of(Map.entry(defaultModel,.2),Map.entry(fast,1.0)))for(var g:Geometry.values()){
            var c=new Case(g,10,entry.getValue(),Fluid.WATER);
            var coarse=integrate(c,5,40,entry.getKey());System.out.println((entry.getKey()==fast?"[v=1e5] ":"")+coarse.line()+detail(coarse));
            var fine=integrate(c,.1,400,entry.getKey());System.out.println((entry.getKey()==fast?"[v=1e5] ":"")+fine.line()+detail(fine));
            if(coarse.ok()&&fine.ok())System.out.println(compare(coarse,fine));
        }
    }
}

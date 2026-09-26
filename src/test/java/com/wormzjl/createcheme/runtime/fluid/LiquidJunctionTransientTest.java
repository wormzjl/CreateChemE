package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Liquid junction transients (BE_INTEGRATOR_PLAN.md WP3 (d), review 9.3): two water inlets at different pressures and
 * temperatures (300 K at 150 kPa, 350 K at 130 kPa) feed a compiled junction with two void outlets at 1 atm, 50 or 20 mm
 * bore, for 20 s. The inlets are either generators (fixed states, a steady through-flow) or liquid-full 1 m3 tanks. Every
 * case must converge at 0.1 s intervals and at the product's 5 s cycle and close its ledger (tanks plus the junction's
 * owned holdup plus the boundary transfers) at roundoff, as MixedGasJunctionTransientTest does for gases.
 *
 * <p>It also measures what the junction holdup costs a liquid: the junction's specific enthalpy lags the inflow mixture
 * with the time constant m_J/Q, and m_J is sized from the configured 100 m/s velocity limit for every phase (review 7.10:
 * 9.78 kg of water at 50 mm). The {@code LIQUID_JUNCTION} lines report m_J, the inflow Q, m_J/Q, the time constant
 * fitted from the 0.1 s trajectory, the mismatch left after each 5 s slice, and the Newton cost at 5 s. The lag is
 * printed, not asserted: how a liquid holdup should be sized is an open owner decision (DECISION_LOG.md).
 */
class LiquidJunctionTransientTest {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final double[] mw=model.molecularWeights();
    private final int water=mw.length-1;
    private static final double LEDGER=1e-12;
    private static final double DURATION=20;

    @Test void everyLiquidCaseConvergesAndClosesItsLedgerAtTenthSecondIntervals() {
        var failures=new ArrayList<String>();
        for(boolean tanks:new boolean[]{false,true})for(double bore:new double[]{.05,.02}) {
            try{System.out.println("LIQUID_JUNCTION interval=0.1 "+run(tanks,bore,.1));}catch(Exception|AssertionError failure){failures.add(key(tanks,bore)+": "+failure);}
        }
        assertTrue(failures.isEmpty(),failures.size()+" of 4 liquid cases failed at 0.1 s:\n"+String.join("\n",failures));
    }
    @Test void everyLiquidCaseConvergesAtTheFiveSecondCycle() {
        var failures=new ArrayList<String>();
        for(boolean tanks:new boolean[]{false,true})for(double bore:new double[]{.05,.02}) {
            boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            try{System.out.println("LIQUID_JUNCTION interval=5 "+run(tanks,bore,5)+" newtonSolves="+SolverDiagnostics.sample().value("newtonSolves"));}
            catch(Exception|AssertionError failure){failures.add(key(tanks,bore)+": "+failure);}
            finally{SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}
        }
        assertTrue(failures.isEmpty(),failures.size()+" of 4 liquid cases failed at 5 s:\n"+String.join("\n",failures));
    }

    private static String key(boolean tanks,double bore){return (tanks?"tanks":"generators")+":"+bore;}
    private PassiveNetwork.Reservoir boundary(long id,int y,double temperature,double pressure,PassiveNetwork.NodeKind kind) {
        double[] n=new double[mw.length];n[water]=1;
        var unit=model.flashTP(temperature,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return new PassiveNetwork.Reservoir(id,y,model.flashTP(temperature,pressure,n,()->{}),kind);
    }
    /** One case: the junction trajectory summary; throws on nonconvergence or a ledger error. */
    private String run(boolean tanks,double bore,double interval) {
        int[][] positions={{-1,0,0},{1,0,0},{0,0,-1},{0,0,1}};
        var geometry=new PipeResistance.Geometry(1,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0);
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        devices.add(new PhysicalFluidTopology.Device(100,new PhysicalFluidTopology.Position("minecraft:overworld",0,0,0),Kind.PIPE,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive()));
        var boundaries=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<4;i++) {
            int[] at=positions[i];boolean inlet=i<2;
            var kind=inlet?(tanks?Kind.RESERVOIR:Kind.GENERATOR):Kind.VOID;
            var d=new PhysicalFluidTopology.Device(i+1,new PhysicalFluidTopology.Position("minecraft:overworld",at[0],at[1],at[2]),kind,PhysicalFluidTopology.Direction.EAST,geometry,new FlowControl.Passive());devices.add(d);
            var nodeKind=inlet?(tanks?PassiveNetwork.NodeKind.RESERVOIR:PassiveNetwork.NodeKind.GENERATOR):PassiveNetwork.NodeKind.VOID;
            boundaries.put(d.id(),boundary(d.id(),at[1],i==1?350:300,i==0?150000:i==1?130000:101325,nodeKind));
        }
        var graph=PassiveNetwork.sizeJunctionHoldups(PhysicalFluidTopology.compile(devices,boundaries).islands().getFirst().graph(),model);
        int junction=-1,first=-1,second=-1;
        for(int i=0;i<graph.reservoirs().size();i++){var node=graph.reservoirs().get(i);if(node.junction())junction=i;if(node.id()==1)first=i;if(node.id()==2)second=i;}
        double mJ=mass(graph.reservoirs().get(junction));
        double hFirst=specificEnthalpy(graph.reservoirs().get(first).state()),hSecond=specificEnthalpy(graph.reservoirs().get(second).state());
        double[] initial=totals(graph),external=new double[mw.length];double initialEnergy=energy(graph),externalEnergy=0;
        var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
        long steps=Math.round(DURATION/interval),started=System.nanoTime();
        var mismatch=new double[(int)steps];var inflow=new double[(int)steps];
        for(int step=0;step<steps;step++) {
            solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
            var result=solver.solve(graph,interval,PassiveIntervalSolver.Settings.defaults(),()->{});committed=result;graph=result.graph();
            for(var transfer:result.boundaries()){var n=transfer.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];externalEnergy+=transfer.totalEnergyJoule();}
            var sum=totals(graph);
            for(int c=0;c<sum.length;c++){double error=Math.abs(sum[c]-initial[c]-external[c])/Math.max(1,initial[c]);assertTrue(error<LEDGER,"interval "+step+": component "+c+" ledger error "+error);}
            double error=Math.abs(energy(graph)-initialEnergy-externalEnergy)/Math.max(1,Math.abs(initialEnergy));assertTrue(error<LEDGER,"interval "+step+": energy ledger error "+error);
            // The inflow mixture at the end of the interval: the inflows' donor states weighted by the interval-average flows.
            double q=0,qh=0;
            for(int p=0;p<graph.pipes().size();p++) {
                var pipe=graph.pipes().get(p);double flow=result.averageMassFlows()[p];int donor;
                if(pipe.second()==junction&&flow>0)donor=pipe.first();else if(pipe.first()==junction&&flow<0)donor=pipe.second();else continue;
                q+=Math.abs(flow);qh+=Math.abs(flow)*specificEnthalpy(graph.reservoirs().get(donor).state());
            }
            var node=graph.reservoirs().get(junction);
            inflow[step]=q;mismatch[step]=q>0?(node.inventory().internalEnergy()/mass(node)-qh/q)/(hSecond-hFirst):Double.NaN;
        }
        long ms=(System.nanoTime()-started)/1_000_000;
        // The time constant from the 0.1 s trajectory: backward Euler over h leaves the mismatch x 1/(1 + h/tau).
        String fitted="";
        if(interval<1) {
            int at=(int)Math.round(1/interval);double r=mismatch[at+1]/mismatch[at];
            fitted=String.format(Locale.ROOT," tauFit(1.0-1.1s)=%.4g s",interval/(1/r-1));
        }
        var slices=new StringBuilder();
        if(interval>=1)for(int s=0;s<steps;s++)slices.append(String.format(Locale.ROOT,"%s%.3g",s==0?"":",",mismatch[s]));
        double q=inflow[(int)Math.min(steps-1,Math.round(1/interval))];
        return String.format(Locale.ROOT,"%s m_J=%.4g kg Q(1s)=%.4g kg/s m_J/Q=%.4g s%s mismatch(1s)=%.3g mismatch(end)=%.3g%s ms=%d",
                key(tanks,bore),mJ,q,q>0?mJ/q:Double.POSITIVE_INFINITY,fitted,mismatch[(int)Math.min(steps-1,Math.round(1/interval))],mismatch[(int)steps-1],
                slices.length()>0?" perSlice=["+slices+"]":"",ms);
    }
    private double mass(PassiveNetwork.Reservoir node){double m=0;var n=node.inventory().moles();for(int c=0;c<n.length;c++)m+=mw[c]*n[c];return m;}
    private static double specificEnthalpy(FluidThermodynamics.State state){return state.enthalpy()/state.mass();}
    private double[] totals(PassiveNetwork graph) {
        var totals=new double[mw.length];
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){var n=node.inventory().moles();for(int c=0;c<n.length;c++)totals[c]+=n[c];}
        return totals;
    }
    private double energy(PassiveNetwork graph) {
        double energy=0;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction())
            energy+=node.inventory().internalEnergy()+mass(node)*PassiveStepSolver.GRAVITY*node.elevation();
        return energy;
    }
}

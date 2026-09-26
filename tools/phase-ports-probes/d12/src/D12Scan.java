package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** D12: the pass/fail scan of EXTREME_TOPOLOGY_TESTS.md 4.4 (column/pair counts at full spread, spreads at 10), every case
 * run over 40 x 5 s and 400 x 0.1 s intervals as the island runtime runs them; prints completion, cost, generator receipt
 * and the ledger error. Args: optional "quick" (first interval only). */
public class D12Scan {
    public static void main(String[] args) {
        var model=FluidTestSupport.networkModel();boolean quick=args.length>0&&args[0].equals("quick");
        var cases=new ArrayList<Object[]>();
        for(String g:new String[]{"grid","alternating","both"}) {
            for(int n=1;n<=10;n++)cases.add(new Object[]{g,1.0,n});
            for(double s:new double[]{.01,.1,.2,.22,.24,.26,.28,.3,.5,.75})cases.add(new Object[]{g,s,10});
        }
        int failed=0;
        for(var c:cases)for(double slice:new double[]{5,.1}) {
            String g=(String)c[0];double spread=(Double)c[1];int n=(Integer)c[2];
            int count=quick?1:(slice>=1?40:400);
            var graph=D12Probe.island(model,g,spread,n);double[] mw=model.molecularWeights();
            var generators=new HashSet<Long>();for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.GENERATOR)generators.add(node.id());
            double[] initial=totals(graph),external=new double[initial.length];
            var solver=new PassiveIntervalSolver(model);PassiveIntervalSolver.Result committed=null;
            boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            int done=0,accepted=0,rejected=0,received=0;double worst=0;String failure=null;long start=System.nanoTime();
            for(int i=0;i<count;i++) {
                solver.replayStart(graph,committed==null?null:committed.graph(),committed==null?null:committed.endpointModes());
                try{committed=solver.solve(graph,slice,PassiveIntervalSolver.Settings.defaults(),()->{});}
                catch(RuntimeException e){failure="interval "+(i+1)+": "+String.valueOf(e.getMessage()).substring(0,Math.min(160,String.valueOf(e.getMessage()).length()));break;}
                graph=committed.graph();done++;accepted+=committed.acceptedSubsteps();rejected+=committed.rejectedSubsteps();
                for(var t:committed.boundaries()){double m=0;var mol=t.moles();for(int k=0;k<mol.length;k++){external[k]+=mol[k];m+=mol[k]*mw[k];}if(generators.contains(t.nodeId())&&m<-1e-10*slice)received++;}
                var sum=totals(graph);for(int k=0;k<sum.length;k++)worst=Math.max(worst,Math.abs(sum[k]-initial[k]-external[k])/Math.max(1,initial[k]));
            }
            long solves=SolverDiagnostics.sample().value("newtonSolves");SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();
            if(failure!=null)failed++;
            System.out.printf(Locale.ROOT,"D12SCAN %s-%d@%.2f slice=%s intervals=%d/%d newtonSolves=%d accepted=%d rejected=%d generatorReceipts=%d worstMoles=%.3g ms=%d%s%n",
                    g,n,spread,slice>=1?"5":"0.1",done,count,solves,accepted,rejected,received,worst,(System.nanoTime()-start)/1000000,failure==null?"":" FAILED "+failure);
        }
        System.out.println("D12SCAN failed runs: "+failed+" of "+2*cases.size());
    }
    static double[] totals(PassiveNetwork graph) {
        double[] t=null;
        for(var node:graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction()){var n=node.inventory().moles();if(t==null)t=new double[n.length];for(int c=0;c<n.length;c++)t[c]+=n[c];}
        return t;
    }
}

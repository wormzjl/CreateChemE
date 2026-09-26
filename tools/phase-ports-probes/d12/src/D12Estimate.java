package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** D12 prototype: one-way junction pressure estimate by Gauss-Seidel (gas, BULK, flat). Args: geometry spread pairs sweeps. */
public class D12Estimate {
    static double flow(PassiveNetwork.Pipe pipe,double pa,double pb,double[] rho,double[] mu,double[] cap){
        int dir=pa>=pb?0:1;double d=pa-pb;
        if(pipe.pressureDrop(cap[dir],rho[dir],mu[dir])<=Math.abs(d))return Math.copySign(cap[dir],d);
        double lo=0,hi=cap[dir];for(int k=0;k<50;k++){double m=(lo+hi)/2;if(pipe.pressureDrop(m,rho[dir],mu[dir])>Math.abs(d))hi=m;else lo=m;}
        return Math.copySign((lo+hi)/2,d);
    }
    public static void main(String[] args){
        var model=FluidTestSupport.networkModel();
        String geometry=args[0];double spread=Double.parseDouble(args[1]);int n=Integer.parseInt(args[2]);int sweeps=Integer.parseInt(args[3]);
        double closeMargin=args.length>4?Double.parseDouble(args[4]):0;
        var graph=D12Probe.island(model,geometry,spread,n);
        int N=graph.reservoirs().size(),E=graph.pipes().size();
        double[][] rho=new double[E][2],mu=new double[E][2],cap=new double[E][2];
        for(int e=0;e<E;e++){var p=graph.pipes().get(e);for(int dir=0;dir<2;dir++){var s=graph.reservoirs().get(dir==0?p.first():p.second()).state();
            rho[e][dir]=s.mass()/s.volume();mu[e][dir]=model.viscosity.vapor(s.temperature(),s.vaporView(),s.waterVapor());cap[e][dir]=rho[e][dir]*p.minimumArea()*model.velocityLimit(s);}}
        double[] P=new double[N];double lo=1e300,hi=-1e300;
        for(int i=0;i<N;i++){P[i]=graph.reservoirs().get(i).state().pressure();if(!graph.reservoirs().get(i).junction()){lo=Math.min(lo,P[i]);hi=Math.max(hi,P[i]);}}
        List<List<Integer>> edgesAt=new ArrayList<>();for(int i=0;i<N;i++)edgesAt.add(new ArrayList<>());
        for(int e=0;e<E;e++){edgesAt.get(graph.pipes().get(e).first()).add(e);edgesAt.get(graph.pipes().get(e).second()).add(e);}
        long t0=System.nanoTime();int sweep;double change=0;
        for(sweep=0;sweep<sweeps;sweep++){change=0;
            for(int j=0;j<N;j++){if(!graph.reservoirs().get(j).junction())continue;
                double a=lo,b=hi;
                for(int k=0;k<40&&b-a>1e-3;k++){double m=(a+b)/2,net=0;
                    for(int e:edgesAt.get(j)){var p=graph.pipes().get(e);double pa=p.first()==j?m:P[p.first()],pb=p.second()==j?m:P[p.second()];
                        double q=flow(p,pa,pb,rho[e],mu[e],cap[e]);if(!PassiveStepSolver_boundaryAllowed(graph,p,q))q=0;net+=p.second()==j?q:-q;}
                    if(net>0)a=m;else b=m;}
                double np=(a+b)/2;change=Math.max(change,Math.abs(np-P[j]));P[j]=np;}
            if(change<1)break;}
        long ms=(System.nanoTime()-t0)/1000000;
        System.out.printf(Locale.ROOT,"sweeps=%d lastChange=%.3g ms=%d%n",sweep+1,change,ms);
        var closed=new ArrayList<Long>();
        for(int e=0;e<E;e++){var p=graph.pipes().get(e);var a=graph.reservoirs().get(p.first());var b=graph.reservoirs().get(p.second());
            if(a.kind()==PassiveNetwork.NodeKind.GENERATOR&&b.junction()&&P[p.first()]<P[p.second()]-closeMargin)closed.add(a.id());
            if(b.kind()==PassiveNetwork.NodeKind.GENERATOR&&a.junction()&&P[p.second()]<P[p.first()]-closeMargin)closed.add(b.id());}
        System.out.println("junctions: "+java.util.stream.IntStream.range(0,N).filter(i->graph.reservoirs().get(i).junction()).mapToObj(i->String.format(Locale.ROOT,"%.0f",P[i])).toList());
        System.out.println("closed generators: "+closed);
        // Rate solve with those closures and the estimate as the junction seed.
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(var p:graph.pipes()){var a=graph.reservoirs().get(p.first());var b=graph.reservoirs().get(p.second());
            pipes.add(closed.contains(a.id())&&b.junction()||closed.contains(b.id())&&a.junction()?p.withBlockedDirections(3):p);}
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<N;i++){var r=graph.reservoirs().get(i);nodes.add(r.junction()?new PassiveNetwork.Reservoir(r.id(),r.elevation(),model.flashTP(r.state().temperature(),P[i],PhaseLayout.totalAmounts(r.state()),()->{}),r.kind(),r.inventory()):r);}
        var seeded=new PassiveNetwork(nodes,pipes,graph.scheduledTransfers());
        try{var rate=new PassiveStepSolver(model).solveRate(D12Probe.ports(seeded),()->{},PassiveStepSolver.Acceptance.FULL);D12Probe.dump("rate converged",seeded,rate.states(),rate.massFlows(),rate.modes());}
        catch(RuntimeException e){System.out.println("rate FAILED "+e.getMessage());}
        for(double dt:new double[]{5,.1}) try{var step=new PassiveStepSolver(model).solve(seeded,dt,()->{});System.out.println("step dt="+dt+" converged");}
        catch(RuntimeException e){System.out.println("step dt="+dt+" FAILED "+e.getMessage());}
    }
    static boolean PassiveStepSolver_boundaryAllowed(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow){
        var a=graph.reservoirs().get(pipe.first()).kind();var b=graph.reservoirs().get(pipe.second()).kind();
        return !pipe.blocked(flow)&&!(flow<0&&(a==PassiveNetwork.NodeKind.GENERATOR||b==PassiveNetwork.NodeKind.VOID)||flow>0&&(b==PassiveNetwork.NodeKind.GENERATOR||a==PassiveNetwork.NodeKind.VOID));
    }
}

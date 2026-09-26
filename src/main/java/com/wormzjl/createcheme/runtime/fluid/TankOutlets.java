package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/**
 * A tank's outlet lines (plan 4.3 under decision D11): read-only presentation of committed data, built in the view on the
 * engine's schedule. Nothing here solves or flashes: the ports are the committed graph's, the flows and the phase split of
 * what each connection drew are the last interval's pipe transfers (the solver samples every drawn phase into them,
 * {@link PipeTransfer}), and the bottom port's level head is the committed state's condensed mass times {@code g H / V}
 * (decision D9), arithmetic on stored amounts.
 */
public final class TankOutlets {
    private TankOutlets() {}
    /**
     * The outlets of node {@code node} of {@code graph}, one per connection with an end there, in connection order.
     * {@code result} (null: no interval completed) is the last interval's; a connection it has no transfer for reads as no
     * flow.
     */
    public static List<FluidView.Outlet> of(FluidThermodynamics model,PassiveNetwork graph,int node,PassiveIntervalSolver.Result result) {
        var tank=graph.reservoirs().get(node);var outlets=new ArrayList<FluidView.Outlet>();
        double[] weights=model.molecularWeights();double seconds=result==null?0:result.advancedSeconds();
        for(var pipe:graph.pipes()) {
            for(int end=0;end<2;end++) {
                if((end==0?pipe.first():pipe.second())!=node)continue;
                var port=end==0?pipe.firstPort():pipe.secondPort();
                PipeTransfer transfer=null;
                if(result!=null&&seconds>0)for(var t:result.pipeTransfers())if(t.pipeId()==pipe.id()){transfer=t;break;}
                double massFlow=0,moleFlow=0;int drawn=FluidView.Outlet.NONE;
                if(transfer!=null) {
                    // Forward is first to second: out of the tank when the tank is the first end.
                    var out=end==0?transfer.forward():transfer.reverse();var in=end==0?transfer.reverse():transfer.forward();
                    massFlow=(out.massKg()-in.massKg())/seconds;moleFlow=(total(out)-total(in))/seconds;
                    if(massFlow>0)drawn=leading(out,weights);
                }
                double head=port==PassiveNetwork.PhasePort.LIQUID&&!tank.empty()
                        ?PassiveStepSolver.GRAVITY*model.liquidMass(tank.state())*PassiveStepSolver.LEVEL_HEAD_HEIGHT/tank.inventory().volume():0;
                outlets.add(new FluidView.Outlet(pipe.id(),port,massFlow,moleFlow,drawn,head));
            }
        }
        return List.copyOf(outlets);
    }
    /** The phase that carried the most mass in what a connection drew, or {@link FluidView.Outlet#NONE} if it drew none. */
    static int leading(PipeTransfer.Stream stream,double[] weights) {
        var n=stream.phaseMoles();int leading=FluidView.Outlet.NONE;double largest=0;
        for(int p=0;p<3;p++){double mass=0;for(int c=0;c<n[p].length;c++)mass+=n[p][c]*weights[c];if(mass>largest){largest=mass;leading=p;}}
        return leading;
    }
    private static double total(PipeTransfer.Stream stream){double sum=0;for(var phase:stream.phaseMoles())for(double amount:phase)sum+=amount;return sum;}
}

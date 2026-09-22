package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.solver.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** Refreshes a derived state from conserved inventory before an interval, never inside a network residual. */
public final class InventoryEquilibrium {
    private InventoryEquilibrium() {}
    public static PassiveNetwork refresh(PassiveNetwork graph,FluidThermodynamics model,Runnable checkpoint) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();boolean changed=false;
        for(var node:graph.reservoirs()) {
            checkpoint.run();var state=node.fixed()||node.junction()||node.empty()?node.state():solve(model,node.state(),node.inventory(),false,checkpoint);
            changed|=state!=node.state();nodes.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),state,node.kind(),node.inventory()));
        }
        return changed?new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers()):graph;
    }
    public static FluidThermodynamics.State solve(FluidThermodynamics model,FluidThermodynamics.State previous,PassiveNetwork.Inventory inventory,boolean forceProperties,Runnable checkpoint) {
        var n=inventory.moles();if(Arrays.stream(n).sum()==0) {
            if(inventory.solids().empty())throw new IllegalArgumentException("Empty inventory has no initialized thermodynamic state");
            if(inventory.solids().volume()>inventory.volume())throw new IllegalArgumentException("Dry solids exceed vessel capacity");
            double temperature=com.wormzjl.createcheme.science.material.SolidMaterial.REFERENCE_TEMPERATURE+inventory.internalEnergy()/inventory.solids().moments().heatCapacity();
            return model.solidState(temperature,previous.pressure(),inventory.solids());
        }
        var old=PhaseLayout.totalAmounts(previous);boolean changedBasis=false;
        if(n.length!=old.length||n.length!=model.hydrocarbon.componentCount()+1)throw new IllegalArgumentException("Inventory equilibrium basis mismatch");
        for(int c=0;c<n.length;c++)changedBasis|=(n[c]==0)!=(old[c]==0)||Math.abs(n[c]-old[c])>1e-12+1e-10*Math.max(n[c],old[c]);
        var state=(forceProperties||changedBasis?model.flashTP(previous.temperature(),previous.pressure(),n,checkpoint):previous).withSolids(inventory.solids());
        var seen=new HashSet<String>();
        for(int pass=0;pass<16;pass++) {
            checkpoint.run();if(!seen.add(regime(state)))throw new SparseNewton.Nonconvergence("Inventory equilibrium phase cycle");
            var layout=new PhaseLayout(model,state);var equations=layout.fixedInventory(n,inventory.internalEnergy(),inventory.volume());var x=layout.encode(state);
            double[] residual=new double[layout.size()];layout.residual(state,n,inventory.internalEnergy(),inventory.volume(),residual,0,x);
            if(norm(residual)<=1e-8)return state;
            FluidThermodynamics.State candidate;
            try {
                double tolerance=state.vaporVolume()/state.volume()<.01?1e-11:1e-9;
                var solved=SparseNewton.solve(equations,x,new SparseNewton.Settings(24,tolerance,1e-6,24),checkpoint);candidate=layout.decode(solved.variables(),0);
            }catch(SparseNewton.Nonconvergence failure) {
                if(failure.lastVariables()==null)throw failure;
                var trial=layout.decode(failure.lastVariables(),0);var phase=model.flashTP(trial.temperature(),trial.pressure(),n,checkpoint).withSolids(inventory.solids());
                if(regime(phase).equals(regime(state)))throw failure;state=phase;continue;
            }
            var stable=model.flashTP(candidate.temperature(),candidate.pressure(),n,checkpoint).withSolids(inventory.solids());
            if(!regime(stable).equals(regime(candidate))){state=stable;continue;}
            candidate=ConservativeTransport.repartition(model,candidate,n);x=layout.encode(candidate);
            layout.residual(candidate,n,inventory.internalEnergy(),inventory.volume(),residual,0,x);
            if(norm(residual)>1e-8)throw new SparseNewton.Nonconvergence("Inventory refresh does not meet full equation closure");
            return candidate;
        }
        throw new SparseNewton.Nonconvergence("Inventory equilibrium phase limit");
    }
    static String regime(FluidThermodynamics.State state){return (state.liquidVolume()>0?"L":"")+(state.vaporProperties()!=null?"G":"")+(state.waterLiquid()>0?"W":"")+(state.waterVapor()>0?"S":"");}
    private static double norm(double[] values){double maximum=0;for(double value:values){if(!Double.isFinite(value))return Double.POSITIVE_INFINITY;maximum=Math.max(maximum,Math.abs(value));}return maximum;}
}

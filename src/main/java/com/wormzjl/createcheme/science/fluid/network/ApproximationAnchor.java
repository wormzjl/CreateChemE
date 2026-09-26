package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.lang.ref.WeakReference;
import java.util.*;

/** The immutable last full solution. Approximate commits must not replace this episode anchor. */
public record ApproximationAnchor(String propertyRevision,PassiveNetwork graph,List<FlowControl.Mode> modes) {
    public ApproximationAnchor {
        Objects.requireNonNull(propertyRevision);Objects.requireNonNull(graph);modes=List.copyOf(modes);
        if(modes.size()!=graph.pipes().size())throw new IllegalArgumentException("Anchor mode count mismatch");
    }
    /** Keep saved thermodynamic reference compatibility separate from the hydraulic acceptance law. */
    public static String thermodynamicRevision(FluidThermodynamics model){return revisions(model).thermodynamic;}
    public static String revision(FluidThermodynamics model){return revisions(model).full;}
    /** Both strings are concatenated from immutable model identity, so they are built once per
     * model instead of once per dispatched island job, where the profile found the string building.
     * One slot is enough: a dimension solves against one property package at a time, and a miss
     * only rebuilds them. The reference is weak so caching a model cannot keep its catalog alive. */
    private record Revisions(WeakReference<FluidThermodynamics> model,String thermodynamic,String full) {}
    private static volatile Revisions cached;
    private static Revisions revisions(FluidThermodynamics model) {
        Objects.requireNonNull(model);
        var known=cached;
        if(known!=null&&known.model.get()==model)return known;
        String thermodynamic="fluid-trbdf2-r1:"+model.hydrocarbon.revision()+":"+model.viscosity.revision();
        // One publication, so a reader can never pair a model with another model's strings.
        // The trace cutoff joins the hydraulic acceptance law, not the thermodynamic reference: it
        // changes which unknowns a Newton pass carries, so an anchor recorded under another cutoff
        // describes a different numerical path and must not be reused - but it changes no property
        // data, so a saved island's inventory and energy stay readable across a change of it.
        var built=new Revisions(new WeakReference<>(model),thermodynamic,
                thermodynamic+":velocity-clamp-v1:trace-relative-v1:max="+Double.toHexString(model.maximumVelocityMetresPerSecond())
                        +":trace="+Double.toHexString(model.traceTruncation().cutoffMoleFraction())+":solids-v1:"+model.solidSettings);
        cached=built;return built;
    }
    public static ApproximationAnchor fromFull(FluidThermodynamics model,PassiveIntervalSolver.Result full) {
        if(full.acceptance()!=PassiveStepSolver.Acceptance.FULL)throw new IllegalArgumentException("Only a full result may renew the anchor");
        return new ApproximationAnchor(revision(model),full.graph(),full.endpointModes());
    }
    public StageGuard guard(FluidThermodynamics model,PassiveNetwork current) {
        if(!propertyRevision.equals(revision(model)))throw new ApproximationRejected("Property or numerical revision changed");
        if(!graph.scheduledTransfers().equals(current.scheduledTransfers()))throw new ApproximationRejected("Scheduled material boundary changed");
        if(graph.reservoirs().size()!=current.reservoirs().size()||graph.pipes().size()!=current.pipes().size())throw new ApproximationRejected("Topology changed");
        var nodes=new HashMap<Long,PassiveNetwork.Reservoir>();for(var node:graph.reservoirs())nodes.put(node.id(),node);
        var references=new ArrayList<PassiveNetwork.Reservoir>();
        for(var node:current.reservoirs()) {
            var old=nodes.get(node.id());
            if(old==null||old.kind()!=node.kind()||old.elevation()!=node.elevation()||old.inventory().volume()!=node.inventory().volume())throw new ApproximationRejected("Node identity, volume or topology changed");
            if(node.fixed()&&(!old.inventory().equals(node.inventory())||old.state().temperature()!=node.state().temperature()||old.state().pressure()!=node.state().pressure()))throw new ApproximationRejected("Boundary configuration changed");
            references.add(old);
        }
        var pipes=new HashMap<Long,PassiveNetwork.Pipe>();var oldModes=new HashMap<Long,FlowControl.Mode>();
        for(int i=0;i<graph.pipes().size();i++){var pipe=graph.pipes().get(i);pipes.put(pipe.id(),pipe);oldModes.put(pipe.id(),modes.get(i));}
        var expectedModes=new ArrayList<FlowControl.Mode>();
        for(var pipe:current.pipes()) {
            var old=pipes.get(pipe.id());
            if(old==null||!old.sections().equals(pipe.sections())||!old.control().equals(pipe.control())
                    ||graph.reservoirs().get(old.first()).id()!=current.reservoirs().get(pipe.first()).id()
                    ||graph.reservoirs().get(old.second()).id()!=current.reservoirs().get(pipe.second()).id())throw new ApproximationRejected("Pipe topology or control changed");
            expectedModes.add(oldModes.get(pipe.id()));
        }
        StageGuard guard=(states,actualModes)->{
            if(states.size()!=references.size()||!actualModes.equals(expectedModes))throw new ApproximationRejected("Device regime changed");
            for(int i=0;i<states.size();i++) {
                var old=references.get(i).state();var state=states.get(i);
                if(state.solidMoments().mass()>0||com.wormzjl.createcheme.science.fluid.transport.SolidMobility.immobileLiquid(model,state))throw new ApproximationRejected("Solid transition requires a full solve");
                if(!InventoryEquilibrium.regime(old).equals(InventoryEquilibrium.regime(state)))throw new ApproximationRejected("Phase regime changed");
                if(Math.abs(state.pressure()-old.pressure())>.01*old.pressure()+1||Math.abs(state.temperature()-old.temperature())>1
                        ||Math.abs(state.vaporVolume()/state.volume()-old.vaporVolume()/old.volume())>.01
                        ||Math.abs(state.liquidVolume()/state.volume()-old.liquidVolume()/old.volume())>.01
                        ||Math.abs(state.waterVolume()/state.volume()-old.waterVolume()/old.volume())>.01
                        ||compositionDrift(old,state)>.01)throw new com.wormzjl.createcheme.science.fluid.solver.SparseNewton.Nonconvergence("Outside the last full solution's trust region");
            }
        };
        try{guard.check(current.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),expectedModes);}
        catch(com.wormzjl.createcheme.science.fluid.solver.SparseNewton.Nonconvergence outside){throw new ApproximationRejected("Initial state is outside the last full solution's trust region");}
        return guard;
    }
    private static double compositionDrift(FluidThermodynamics.State old,FluidThermodynamics.State current) {
        double[] a=com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(old),b=com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(current);
        if(a.length!=b.length)throw new ApproximationRejected("Component basis changed");
        double sumA=Arrays.stream(a).sum(),sumB=Arrays.stream(b).sum(),drift=0;
        if(!(sumA>0&&sumB>0))throw new ApproximationRejected("Empty mixture has no fallback composition");
        for(int i=0;i<a.length;i++)drift=Math.max(drift,Math.abs(a[i]/sumA-b[i]/sumB));
        return drift;
    }
}

import sys
p='src/main/java/com/wormzjl/createcheme/science/fluid/network/PassiveStepSolver.java'
s=open(p).read()
def rep(old,new,count=1):
    global s
    n=s.count(old)
    if n!=count: sys.exit(f"expected {count} got {n}: {old[:120]}")
    s=s.replace(old,new)
rep("suctionCapped(graph,pipe,pump)?FlowControl.Mode.PUMP_HEAD_LIMIT","suctionCapped(graph,modes.size(),pump)?FlowControl.Mode.PUMP_HEAD_LIMIT")
rep("""            if(!(graph.pipes().get(i).control() instanceof FlowControl.Passive))modes.set(i,FlowControl.Mode.CLOSED);
        }
        // A run the interval solver reopened""","""            if(!(graph.pipes().get(i).control() instanceof FlowControl.Passive))modes.set(i,FlowControl.Mode.CLOSED);
        }
        // Decision D6: a pump or compressor refused for this slice because its supply is the wrong phase is closed for the
        // whole solve like a connection blocked both ways (its flow is exactly zero, no pass reopens it, nothing reaches
        // its discharge through it), and holds the mode INLET_WRONG_PHASE, which the carry never restates; see
        // {@link #refusedInlets}.
        boolean[] refused=refusedInlets(inputGraph,carryModes,dt);
        for(int i=0;i<refused.length;i++)if(refused[i]){boundaryClosed[i]=true;modes.set(i,FlowControl.Mode.INLET_WRONG_PHASE);}
        // A run the interval solver reopened""")
rep("""    /** Connections the next step solve may not close at its start; set by {@link #reopenNext}, consumed by that solve. */""",
"""    /**
     * The movers (pipe ids) refused for the slice in progress ({@link FlowControl.Mode#INLET_WRONG_PHASE}, decision D6),
     * set by the interval solver at each slice start ({@link #refuseInlets}) and read by every solve until the next;
     * null for a step solver no interval drives, which decides at each solve ({@link #refusedInlets}).
     */
    private Set<Long> sliceRefusals;
    /** The movers (pipe ids) refused for the slice that starts now; see {@link PassiveIntervalSolver} and {@link InletPhase}. */
    void refuseInlets(Set<Long> pipeIds){sliceRefusals=Set.copyOf(pipeIds);}
    /**
     * Which connections of {@code graph} this solve holds in {@link FlowControl.Mode#INLET_WRONG_PHASE}: the slice's
     * refusals when an interval solver set them ({@link #refuseInlets}: decided once per slice at its start, on the
     * committed state and the committed endpoint modes, so every step, rate seed and certified replay of the slice agrees);
     * otherwise, for a step solver used on its own, {@link InletPhase#decide} on this solve's input states, with the
     * carried modes as the hysteresis input and the step as the slice (a rate solve: the leading phase).
     */
    private boolean[] refusedInlets(PassiveNetwork graph,boolean carryModes,double dt) {
        boolean[] refused=new boolean[graph.pipes().size()];
        if(sliceRefusals!=null) {
            for(int i=0;i<refused.length;i++)refused[i]=graph.pipes().get(i).control() instanceof FlowControl.Mover&&sliceRefusals.contains(graph.pipes().get(i).id());
            return refused;
        }
        if(graph.pipes().stream().noneMatch(pipe->pipe.control() instanceof FlowControl.Mover))return refused;
        return InletPhase.decide(model,graph,carryModes?previousModes:null,rateOnly?0:dt);
    }
    /**
     * Whether a mover's target mass flow on the start state ({@code Q rho_s}, the suction end's density) exceeds the
     * velocity cap of a link on its suction walk ({@link InletPhase#walk}: each link's cap on the stream its own donor end
     * draws, {@link #massFlowLimit}), or a link of the walk may not carry flow towards the device at all. Such a target
     * has no solution (the target row fixes the device's flow, the cap the suction line's, and the junction balances
     * equate them), so the device starts on its head limit, where the flow is free (plan 3.6, "Target above what the
     * suction can deliver"). A port's D11 capacities bound only the phases before its last, never its total (decision
     * A22), so they are no limit here; a port that would run into another phase is the inlet check's business. A device
     * drawing straight from its first node walks no link, and the pump edge's own cap is tested by the caller.
     */
    private boolean suctionCapped(PassiveNetwork graph,int edge,FlowControl.Mover mover) {
        var walk=InletPhase.walk(graph,edge);
        if(walk.links().length==0)return false;
        var pipe=graph.pipes().get(edge);
        double target=suctionMassFlow(mover.targetVolumeFlow(),graph.reservoirs().get(pipe.first()).state(),pipe.firstPort());
        for(int k=0;k<walk.links().length;k++) {
            var link=graph.pipes().get(walk.links()[k]);int donor=walk.donors()[k];
            if(!boundaryAllowed(graph,link,link.first()==donor?1:-1))return true;
            if(target>massFlowLimit(link,graph.reservoirs().get(donor).state(),link.portAt(donor)))return true;
        }
        return false;
    }
    /** Connections the next step solve may not close at its start; set by {@link #reopenNext}, consumed by that solve. */""")
open(p,'w').write(s)
print("ok")

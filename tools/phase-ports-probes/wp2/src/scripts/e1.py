import json
F="src/main/java/com/wormzjl/createcheme/science/fluid/network/PassiveStepSolver.java"
E=[]
# 1. constants + helpers after HOLDUP_TAU
E.append(["""    static final double HOLDUP_TAU=.05;
""","""    static final double HOLDUP_TAU=.05;
    /**
     * The share of its vessel's volume a port's phase must fill, on the accepted state a step starts from, for the port to
     * carry outflow in that step ({@code phi_open}; decision A4 of the phase-ports batch, plan Appendix B): a VAPOR port
     * reads {@code vaporVolume/V}, a LIQUID port {@code (liquidVolume + waterVolume)/V}. Below it the port is closed for
     * outflow for the whole step ({@link #boundaryAllowed}); inflow through it is never refused.
     */
    static final double PHASE_PORT_OPEN=.01;
    /**
     * Where the outflow throttle of an open phase port lands its phase ({@code phi_reserve}; decision A4): the port may
     * draw at most {@code (phi_start - phi_reserve) V rho_stream,start / (n dt)} over a step (see {@link Equations#throttles}).
     * The gap to {@link #PHASE_PORT_OPEN} is the band: a port drained to the reserve cannot reopen until its phase has
     * regrown by half a percent of the vessel, so it cannot open and close on alternate steps by itself, and the band needs
     * no carried state (plan 3.4; documentation/2026-09-26-phase-ports-and-compressor/PHASE_PORTS_REVIEW.md, WP2).
     */
    static final double PHASE_PORT_RESERVE=.005;
    /** The share of {@code state}'s volume the phase {@code port} draws fills: the vapour's at a VAPOR port, the hydrocarbon
     * liquid's plus the free water's at a LIQUID port (solids alone do not flow as a liquid); a BULK end draws everything. */
    static double portPhaseShare(FluidThermodynamics.State state,PassiveNetwork.PhasePort port) {
        return switch(port) {
            case BULK->1;
            case VAPOR->state.vaporVolume()/state.volume();
            case LIQUID->(state.liquidVolume()+state.waterVolume())/state.volume();
        };
    }
    /** Whether a port may carry outflow from a vessel in {@code state}: a BULK end always, a phase port while its phase
     * fills at least {@link #PHASE_PORT_OPEN} of the vessel. Stateless: nothing is carried from step to step. */
    static boolean portAvailable(FluidThermodynamics.State state,PassiveNetwork.PhasePort port) {
        return port==PassiveNetwork.PhasePort.BULK||portPhaseShare(state,port)>=PHASE_PORT_OPEN;
    }
    private static int portBit(PassiveNetwork.PhasePort port){return port==PassiveNetwork.PhasePort.VAPOR?1:port==PassiveNetwork.PhasePort.LIQUID?2:0;}
    /**
     * The start-state availability mask of a solve: per node, bit 1 set when a VAPOR port some connection has at that node
     * may not carry outflow ({@link #portAvailable} false on the node's own state in {@code graph}), bit 2 the same for a
     * LIQUID port. Only ports some connection actually has are recorded, so a graph with no phase port - every graph
     * before phase ports - has an all-zero mask whatever its states, and every reader of it decides exactly as before.
     *
     * <p>Why a mask from the start state and not a state argument: {@link #boundaryAllowed} is read by the start-of-solve
     * closures, the pass loop, the reachable-component sweep, the start point and the approximation probe on this solve's
     * start, and by the boundary-reopen test ({@link #reopenable}) on the step's <em>end</em> states. A port decided open on
     * one state and closed on another would let the reopen test reopen a run whose only driven direction the solve then
     * refuses (plan Appendix C.3, availability caveat). So every reader of one step reads this one mask, built from the
     * accepted state the step starts from.
     */
    static byte[] closedPorts(PassiveNetwork graph) {
        byte[] closed=new byte[graph.reservoirs().size()];
        for(var pipe:graph.pipes())for(int end=0;end<2;end++) {
            var port=end==0?pipe.firstPort():pipe.secondPort();if(port==PassiveNetwork.PhasePort.BULK)continue;
            int node=end==0?pipe.first():pipe.second();
            if(!portAvailable(graph.reservoirs().get(node).state(),port))closed[node]|=(byte)portBit(port);
        }
        return closed;
    }
"""])
# 2. reopenable uses the start graph's mask
E.append(["""        boolean[] taken=new boolean[edges];var reopen=new LinkedHashSet<PassiveNetwork.Pipe.Identity>();
        for(int edge=0;edge<edges;edge++) {
            if(taken[edge]||!closed[edge])continue;""","""        boolean[] taken=new boolean[edges];var reopen=new LinkedHashSet<PassiveNetwork.Pipe.Identity>();
        // The step's start-state availability, the mask the solve itself refused its directions on: a port closed for
        // outflow is one more link that refuses a direction, so a run is reopened only in a direction every link, port
        // included, allows (inflow through a closed port reopens as any one-way run does; plan 3.4).
        byte[] closedPorts=closedPorts(graph);
        for(int edge=0;edge<edges;edge++) {
            if(taken[edge]||!closed[edge])continue;"""])
E.append(["""                var link=graph.pipes().get(chain.get(position));boolean aligned=link.first()==order.get(position);
                forwardAllowed&=boundaryAllowed(graph,link,aligned?1:-1);
                reverseAllowed&=boundaryAllowed(graph,link,aligned?-1:1);
            }
            var a=states.get(start);var b=states.get(finish);""","""                var link=graph.pipes().get(chain.get(position));boolean aligned=link.first()==order.get(position);
                forwardAllowed&=boundaryAllowed(graph,link,aligned?1:-1,closedPorts);
                reverseAllowed&=boundaryAllowed(graph,link,aligned?-1:1,closedPorts);
            }
            var a=states.get(start);var b=states.get(finish);"""])
# 3. solve: compute mask before seeding
E.append(["""        // A cold junction's pressure guess replaced by the balanced one; a Newton starting guess only, the junction's
        // owned inventory is untouched. See {@link #seedBoundaryJunctions}.
        var graph=seedBoundaryJunctions(inputGraph,checkpoint);""","""        // Which phase ports may carry outflow in this step, on the accepted state it starts from (every vessel state the
        // junction seed below leaves unchanged); read by every direction test of this solve. See {@link #closedPorts}.
        byte[] closedPorts=closedPorts(inputGraph);
        // A cold junction's pressure guess replaced by the balanced one; a Newton starting guess only, the junction's
        // owned inventory is untouched. See {@link #seedBoundaryJunctions}.
        var graph=seedBoundaryJunctions(inputGraph,checkpoint,closedPorts);"""])
E.append(["""        closeDeadHeads(graph,boundaryClosed,keep);
        closeIllegalStarts(graph,modes,boundaryClosed,keep);""","""        closeDeadHeads(graph,boundaryClosed,keep,closedPorts);
        closeIllegalStarts(graph,modes,boundaryClosed,keep,closedPorts);"""])
E.append(["""        var reachable=reachableComponents(graph,null,boundaryClosed);
        var seeds=initialPhaseSeeds(graph,dt,checkpoint,reachable);""","""        var reachable=reachableComponents(graph,null,boundaryClosed,closedPorts);
        var seeds=initialPhaseSeeds(graph,dt,checkpoint,reachable,closedPorts);"""])
E.append(["""            var equations=new Equations(graph,dt,modes,boundaryClosed,seeds,reachable,supports);""","""            var equations=new Equations(graph,dt,modes,boundaryClosed,seeds,reachable,supports,closedPorts);"""])
E.append(["""            var structure=new WorkspaceKey(0,nodeIds,kinds,pipeIdentities,phases,componentMask,supportCodes(supports),modeCodes,boundaryClosed.clone(),equations.junctionDonorFirst);
            if(!seen.add(structure))throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");
            var key=new WorkspaceKey(Double.doubleToLongBits(dt),nodeIds,kinds,pipeIdentities,phases,componentMask,structure.supports,modeCodes,structure.boundaryClosed,structure.junctionDonors);""","""            var structure=new WorkspaceKey(0,nodeIds,kinds,pipeIdentities,phases,componentMask,supportCodes(supports),modeCodes,boundaryClosed.clone(),equations.junctionDonorFirst,closedPorts);
            if(!seen.add(structure))throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");
            var key=new WorkspaceKey(Double.doubleToLongBits(dt),nodeIds,kinds,pipeIdentities,phases,componentMask,structure.supports,modeCodes,structure.boundaryClosed,structure.junctionDonors,closedPorts);"""])
E.append(["""                if(!boundaryAllowed(graph,pipe,flows[i])&&Math.abs(flows[i])>1e-10){if(illegalDirection<0)illegalDirection=i;continue;}""","""                if(!boundaryAllowed(graph,pipe,flows[i],closedPorts)&&Math.abs(flows[i])>1e-10){if(illegalDirection<0)illegalDirection=i;continue;}"""])
# WorkspaceKey
E.append(["""                                int[] phases,boolean[] componentMask,byte[] supports,byte[] modes,boolean[] boundaryClosed,
                                boolean[] junctionDonors) {""","""                                int[] phases,boolean[] componentMask,byte[] supports,byte[] modes,boolean[] boundaryClosed,
                                boolean[] junctionDonors,byte[] closedPorts) {"""])
E.append(["""                    &&Arrays.equals(junctionDonors,key.junctionDonors);""","""                    &&Arrays.equals(junctionDonors,key.junctionDonors)&&Arrays.equals(closedPorts,key.closedPorts);"""])
E.append(["""            return 31*hash+Arrays.hashCode(junctionDonors);""","""            return 31*(31*hash+Arrays.hashCode(junctionDonors))+Arrays.hashCode(closedPorts);"""])
# approximation probe
E.append(["""                    ||!boundaryAllowed(equations.graph,pipe,q)&&Math.abs(q)>floor)""","""                    ||!boundaryAllowed(equations.graph,pipe,q,equations.closedPorts)&&Math.abs(q)>floor)"""])
# reachableComponents
E.append(["""    private boolean[][] reachableComponents(PassiveNetwork graph,double[] directions){return reachableComponents(graph,directions,null);}
    /** {@code closed} names the connections this pass has already pinned to a flow of exactly zero;
     * they carry no species in either direction. {@code null} is no closure at all. */
    private boolean[][] reachableComponents(PassiveNetwork graph,double[] directions,boolean[] closed) {""","""    private boolean[][] reachableComponents(PassiveNetwork graph,double[] directions){return reachableComponents(graph,directions,null,closedPorts(graph));}
    /** {@code closed} names the connections this pass has already pinned to a flow of exactly zero;
     * they carry no species in either direction. {@code null} is no closure at all. {@code closedPorts} is the solve's
     * start-state availability mask ({@link #closedPorts}): a port closed for outflow sends nothing out of its vessel. */
    private boolean[][] reachableComponents(PassiveNetwork graph,double[] directions,boolean[] closed,byte[] closedPorts) {"""])
E.append(["""                if((directions==null||directions[edge]>1e-14)&&boundaryAllowed(graph,pipe,1))outgoing.get(pipe.first()).add(pipe.second());
                if((directions==null||directions[edge]<-1e-14)&&pipe.control() instanceof FlowControl.Passive&&boundaryAllowed(graph,pipe,-1))outgoing.get(pipe.second()).add(pipe.first());""","""                if((directions==null||directions[edge]>1e-14)&&boundaryAllowed(graph,pipe,1,closedPorts))outgoing.get(pipe.first()).add(pipe.second());
                if((directions==null||directions[edge]<-1e-14)&&pipe.control() instanceof FlowControl.Passive&&boundaryAllowed(graph,pipe,-1,closedPorts))outgoing.get(pipe.second()).add(pipe.first());"""])
# initialPhaseSeeds
E.append(["""    private List<FluidThermodynamics.State> initialPhaseSeeds(PassiveNetwork graph,double dt,Runnable checkpoint,boolean[][] reachable) {""","""    private List<FluidThermodynamics.State> initialPhaseSeeds(PassiveNetwork graph,double dt,Runnable checkpoint,boolean[][] reachable,byte[] closedPorts) {"""])
E.append(["""                if(receiver!=nodeIndex||!boundaryAllowed(graph,pipe,flow))continue;""","""                if(receiver!=nodeIndex||!boundaryAllowed(graph,pipe,flow,closedPorts))continue;"""])
# seedBoundaryJunctions
E.append(["""    private PassiveNetwork seedBoundaryJunctions(PassiveNetwork graph,Runnable checkpoint) {""","""    private PassiveNetwork seedBoundaryJunctions(PassiveNetwork graph,Runnable checkpoint,byte[] closedPorts) {"""])
E.append(["""            var seed=balancedBoundarySeed(graph,node,low,high,checkpoint);""","""            var seed=balancedBoundarySeed(graph,node,low,high,checkpoint,closedPorts);"""])
E.append(["""    private FluidThermodynamics.State balancedBoundarySeed(PassiveNetwork graph,int node,double low,double high,Runnable checkpoint) {""","""    private FluidThermodynamics.State balancedBoundarySeed(PassiveNetwork graph,int node,double low,double high,Runnable checkpoint,byte[] closedPorts) {"""])
E.append(["""                    if(boundaryAllowed(trial,pipe,flow))net+=pipe.first()==node?-flow:flow;""","""                    if(boundaryAllowed(trial,pipe,flow,closedPorts))net+=pipe.first()==node?-flow:flow;"""])
E.append(["""                if(donor==node||!boundaryAllowed(trial,pipe,flow))continue;""","""                if(donor==node||!boundaryAllowed(trial,pipe,flow,closedPorts))continue;"""])
# startPoint
E.append(["""    private void startPoint(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                            List<PassiveNetwork.Pipe.Identity> pipeIdentities,List<FluidThermodynamics.State> seeds,
                            double[] flows,double[] heads,boolean[] headSet) {""","""    private void startPoint(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                            List<PassiveNetwork.Pipe.Identity> pipeIdentities,List<FluidThermodynamics.State> seeds,
                            double[] flows,double[] heads,boolean[] headSet,byte[] closedPorts) {"""])
E.append(["""                if(boundaryAllowed(graph,pipe,q)){flows[missing]=q;known[missing]=true;changed=true;}""","""                if(boundaryAllowed(graph,pipe,q,closedPorts)){flows[missing]=q;known[missing]=true;changed=true;}"""])
# closeDeadHeads
E.append(["""    private void closeDeadHeads(PassiveNetwork graph,boolean[] boundaryClosed,boolean[] keep) {""","""    private void closeDeadHeads(PassiveNetwork graph,boolean[] boundaryClosed,boolean[] keep,byte[] closedPorts) {"""])
E.append(["""                var link=graph.pipes().get(chain.get(position));boolean aligned=link.first()==order.get(position);
                forwardAllowed&=boundaryAllowed(graph,link,aligned?1:-1);
                reverseAllowed&=boundaryAllowed(graph,link,aligned?-1:1);
            }
            if(forwardAllowed&&reverseAllowed)continue;""","""                var link=graph.pipes().get(chain.get(position));boolean aligned=link.first()==order.get(position);
                forwardAllowed&=boundaryAllowed(graph,link,aligned?1:-1,closedPorts);
                reverseAllowed&=boundaryAllowed(graph,link,aligned?-1:1,closedPorts);
            }
            if(forwardAllowed&&reverseAllowed)continue;"""])
# closeIllegalStarts
E.append(["""    private void closeIllegalStarts(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,boolean[] keep) {""","""    private void closeIllegalStarts(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,boolean[] keep,byte[] closedPorts) {"""])
E.append(["""        startPoint(graph,modes,boundaryClosed,identities(graph),null,start,new double[edges],new boolean[edges]);""","""        startPoint(graph,modes,boundaryClosed,identities(graph),null,start,new double[edges],new boolean[edges],closedPorts);"""])
E.append(["""            if(Math.abs(q)>1e-10&&!boundaryAllowed(graph,pipe,q)) {""","""            if(Math.abs(q)>1e-10&&!boundaryAllowed(graph,pipe,q,closedPorts)) {"""])
# boundaryAllowed
E.append(["""    private static boolean boundaryAllowed(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        var a=graph.reservoirs().get(pipe.first()).kind();var b=graph.reservoirs().get(pipe.second()).kind();
        return !pipe.blocked(flow)&&!(flow<0&&(a==PassiveNetwork.NodeKind.GENERATOR||b==PassiveNetwork.NodeKind.VOID)
                ||flow>0&&(b==PassiveNetwork.NodeKind.GENERATOR||a==PassiveNetwork.NodeKind.VOID));
    }""","""    /**
     * Whether a connection may carry {@code flow}'s direction: not blocked that way, not out of a void or into a generator,
     * and - phase ports - not out of an end whose port is closed for outflow in {@code closedPorts}, the solve's start-state
     * availability mask ({@link #closedPorts}). Flow into a vessel through any port is never refused by the port (D3: inputs
     * are not distinguished). A BULK end never reads the mask, so an all-BULK graph is decided exactly as before.
     */
    private static boolean boundaryAllowed(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        var a=graph.reservoirs().get(pipe.first()).kind();var b=graph.reservoirs().get(pipe.second()).kind();
        return !pipe.blocked(flow)&&!(flow<0&&(a==PassiveNetwork.NodeKind.GENERATOR||b==PassiveNetwork.NodeKind.VOID)
                ||flow>0&&(b==PassiveNetwork.NodeKind.GENERATOR||a==PassiveNetwork.NodeKind.VOID));
    }
    private static boolean boundaryAllowed(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow,byte[] closedPorts) {
        if(!boundaryAllowed(graph,pipe,flow))return false;
        if(flow>0&&pipe.firstPort()!=PassiveNetwork.PhasePort.BULK&&(closedPorts[pipe.first()]&portBit(pipe.firstPort()))!=0)return false;
        return !(flow<0&&pipe.secondPort()!=PassiveNetwork.PhasePort.BULK&&(closedPorts[pipe.second()]&portBit(pipe.secondPort()))!=0);
    }"""])
json.dump(E,open("/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad/wp2/e1.json","w"))

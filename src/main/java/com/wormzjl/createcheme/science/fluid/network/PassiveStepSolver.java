package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.linalg.SparseLuSolver;
import com.wormzjl.createcheme.science.fluid.solver.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.thermo.PhaseSupport;
import java.util.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;
import com.wormzjl.createcheme.science.fluid.transport.SlurryTransport;

/** One simultaneous backward-Euler step with a fixed phase regime; no nested TP/UV/PH flash. */
public final class PassiveStepSolver {
    public enum Acceptance { FULL, APPROXIMATE }
    public static final double GRAVITY=9.80665;
    /**
     * How far below its own difference floor a solid moment unknown stops bounding the Newton step
     * length. The solid difference floors are {@code max(1e-6, encoded value)}, so on a node whose
     * moments are dust this is a scaled value of {@value #SOLID_CLAMP_FRACTION}e-6.
     *
     * <p>Swept on the ten- and thirty-reservoir water chains at 0 (the unfloored rule), 1e-6, 1e-3
     * and 1, i.e. scaled floors of 0, 1e-12, 1e-9 and 1e-6. All three nonzero values give exactly
     * the same accepted/rejected counts and no line-search stall anywhere; 0 still fails the
     * thirty-reservoir chain outright. The smallest that works is kept, so the exemption covers
     * only values at which {@code x + alpha*d} is bitwise {@code x} for every unknown of order one
     * and no backtrack could produce a different residual anyway.
     */
    private static final double SOLID_CLAMP_FRACTION=1e-6;
    /**
     * How far below its own difference floor a fluid amount unknown stops bounding the Newton step
     * length, and what a trial keeps of it instead: {@link Equations#project} holds such a trace at
     * {@value #TRACE_RETENTION} of its value rather than let the whole step shrink to that trace's
     * size. The same fraction as {@link #SOLID_CLAMP_FRACTION}, for the same reason: at a value this
     * far below the scale the Jacobian's own differences resolve the unknown on, the one-sided
     * difference step is a million times the unknown itself.
     *
     * <p>The case it settles is a connection whose flow the step reverses. A vessel's transported
     * amounts are linearized with the donor of the current flow sign, so a step that turns a
     * connection around predicts that the vessel on the far side loses the <em>near</em> side's
     * composition. Where that vessel holds a species only as a trace - the {@code 1e-12} entry trace
     * {@code initialPhaseSeeds} plants for every reachable component, or the 1e-14 to 1e-30 a reversal
     * has already carried there - the prediction drives it negative by orders of magnitude, and
     * {@link Equations#maximumStep}'s {@code .99*x/-d} rule then cut every step of the whole island
     * to that trace's size: 2e-10 of a step at the first iteration, 2e-12 at the next, until the line
     * search reported a stall at a residual its own direction would have removed. Measured on every
     * pumped fill of a chain of nitrogen tanks with the placement defaults: water entering the first
     * tank evaporates into its dry nitrogen, cools it about 17 K to its wet-bulb temperature within a
     * few milliseconds, its pressure drops about 4.5 kPa, and every connection downstream reverses;
     * 73 of the 76 Newton failures of the three-tank chain's first interval and every one of the
     * six-tank chain's held retries in game ({@code Newton line search stalled at residual 0.096...})
     * were this limiter on a steam trace in the second tank. See
     * {@code documentation/fluid-followups/FLUID_PUMPED_FILL_REVIEW.md}.
     *
     * <p>Projecting instead is safe because such a trace is invisible to every row the Newton
     * converges on: its material row is scaled by the node's whole amount (a gas node, or a trace
     * the node did not hold at the start of the step), so a trace below the floor moves it by less
     * than {@code 1e-10}, below the tightest tolerance; its equilibrium and closure rows carry it at
     * the same order. Inventories are rebuilt from the converged flows by
     * {@link ConservativeTransport#reconstruct}, so conservation never depends on the value the
     * projection kept. In a liquid-full node whose trace is resolved against its own reference the
     * difference floor is that trace itself, so only dust a millionth of it is exempt.
     */
    private static final double TRACE_CLAMP_FRACTION=1e-6,TRACE_RETENTION=.01;
    /**
     * How far below its stated capacity the saturated filter inlet aims. The committed cake is
     * reconstructed from the converged flow by a different summation than the loading row solves,
     * so a law aimed exactly at capacity lands within a unit in the last place of it on either
     * side, and a cake one ULP over its own capacity is not a state the owned inventory should
     * ever hold. Aiming a hair under makes the landing one-sided while leaving it at capacity to
     * far better than any audit resolves; {@link SolidEventIntegrator} treats a cake this close to
     * capacity as having met it.
     */
    private static final double FILTER_CAPACITY_MARGIN=1e-12;
    /**
     * The owned holdup of a junction, in seconds of its largest connection's velocity-cap flow: a junction owns
     * {@code m_J = HOLDUP_TAU * max over its connections of rho_seed * minimumArea * velocityLimit(seed)} of fluid, minted
     * from its seed state when it is first solved; see {@link PassiveNetwork#sizeJunctionHoldups}.
     *
     * <p>Why a junction owns stock: a junction that owns nothing mixes by a ratio closure, the inflow-weighted mixture of
     * what arrives, which is singular at zero through-flow, and an island coming to rest drives every junction there. With
     * a holdup the junction's mixing and enthalpy rows are the backward-Euler vessel rows
     * {@code (m_J/dt + Q) w = (m_J/dt) w_old + sum q w_e}, regular at Q = 0, and the reconstruction books the junction as a
     * vessel whose mass is pinned at m_J ({@code ConservativeTransport.pinnedFlows}). The holdup is a numerical
     * regularisation sized to the flow, not to the throughput over a step (the longest step is 20 s): its mixing lag at
     * full flow is m_J/Q = tau. See documentation/2026-09-24-mixed-gas-junction/HANDOFF_REVIEW.md 7.2, 7.6 and 7.10, and
     * decision D2 of that batch's DECISION_LOG.md.
     */
    static final double HOLDUP_TAU=.05;
    /** Connections the next step solve may not close at its start; set by {@link #reopenNext}, consumed by that solve. */
    private Set<PassiveNetwork.Pipe.Identity> keepOpen=Set.of();
    /** Exempts these connections from the start-of-solve closures ({@link #closeDeadHeads}, {@link #closeIllegalStarts})
     * of the next step solve only; the pass loop may still close them on a converged point. */
    void reopenNext(Set<PassiveNetwork.Pipe.Identity> identities){keepOpen=Set.copyOf(identities);}
    /** The driving pressure (Pa) a closed run's end states must exceed in an allowed direction to be reopened. */
    static double reopenBand(double pa,double pb){return Math.max(1,1e-6*Math.max(Math.abs(pa),Math.abs(pb)));}
    /**
     * The passive connections a converged step holds closed ({@code boundaryClosed}, i.e. accepted mode CLOSED; not a
     * connection blocked both ways before the solve) whose run - the maximal chain of closed passive connections through
     * degree-two junctions no actuator touches, the run {@link #closeDeadHeads} decides on - is driven by the step's own
     * end states in a direction every link allows, by more than {@link #reopenBand}. A forward run stands in the first
     * end's fluid and a reverse run in the second's, as in {@link #closeDeadHeads}. A junction end counts only while one
     * of its connections is open in the step, so its pressure is a solved hydraulic pressure; a junction every
     * connection of which is closed says nothing.
     *
     * <p>Why: {@link #closeDeadHeads} and {@link #closeIllegalStarts} decide a closure on the solve's start point, and the
     * pass loop never reopens a boundary closure within a solve. A backward-Euler step is solved once, so a step that
     * starts closed and should open part-way (an injection into tanks at rest, a withdrawal from a dead-headed tank) would
     * be solved shut throughout, and the tanks would bottle up or starve for the whole step: 2.8 kPa at 13 s on a 5 s
     * slice (review 8.8 (b) and (c)). The interval solver refuses such a step and solves it again with the run exempt.
     */
    static Set<PassiveNetwork.Pipe.Identity> reopenable(PassiveNetwork graph,Result result) {
        int edges=graph.pipes().size(),nodes=graph.reservoirs().size();
        var states=result.states();var modes=result.modes();
        if(modes.size()!=edges||states.size()!=nodes)return Set.of();
        boolean[] closed=new boolean[edges];boolean[] open=new boolean[nodes];boolean any=false;
        for(int e=0;e<edges;e++) {
            var pipe=graph.pipes().get(e);
            closed[e]=pipe.control() instanceof FlowControl.Passive&&pipe.blockedDirections()!=3&&modes.get(e)==FlowControl.Mode.CLOSED;any|=closed[e];
            if(modes.get(e)!=FlowControl.Mode.CLOSED){open[pipe.first()]=true;open[pipe.second()]=true;}
        }
        if(!any)return Set.of();
        int[] degree=new int[nodes];boolean[] actuated=new boolean[nodes];
        for(var pipe:graph.pipes()) {
            degree[pipe.first()]++;degree[pipe.second()]++;
            if(!(pipe.control() instanceof FlowControl.Passive)){actuated[pipe.first()]=true;actuated[pipe.second()]=true;}
        }
        boolean[] interior=new boolean[nodes];
        for(int i=0;i<nodes;i++)interior[i]=graph.reservoirs().get(i).junction()&&degree[i]==2&&!actuated[i];
        int[][] nodeEdges=new int[nodes][];
        for(int i=0;i<nodes;i++)nodeEdges[i]=new int[degree[i]];
        Arrays.fill(degree,0);
        for(int edge=0;edge<edges;edge++){var pipe=graph.pipes().get(edge);nodeEdges[pipe.first()][degree[pipe.first()]++]=edge;nodeEdges[pipe.second()][degree[pipe.second()]++]=edge;}
        boolean[] taken=new boolean[edges];var reopen=new LinkedHashSet<PassiveNetwork.Pipe.Identity>();
        for(int edge=0;edge<edges;edge++) {
            if(taken[edge]||!closed[edge])continue;
            var pipe=graph.pipes().get(edge);
            var chain=new ArrayList<Integer>();var order=new ArrayList<Integer>();
            order.add(pipe.first());chain.add(edge);order.add(pipe.second());taken[edge]=true;
            for(int end=0;end<2;end++) {
                int at=end==0?pipe.first():pipe.second(),from=edge;
                while(interior[at]) {
                    int next=nodeEdges[at][0]==from?nodeEdges[at][1]:nodeEdges[at][0];
                    if(taken[next]||!closed[next])break;
                    taken[next]=true;var step=graph.pipes().get(next);
                    int beyond=step.first()==at?step.second():step.first();
                    if(end==0){chain.addFirst(next);order.addFirst(beyond);}else{chain.add(next);order.add(beyond);}
                    at=beyond;from=next;
                }
            }
            int start=order.getFirst(),finish=order.getLast();
            if(start==finish)continue;
            if(graph.reservoirs().get(start).junction()&&!open[start]||graph.reservoirs().get(finish).junction()&&!open[finish])continue;
            boolean forwardAllowed=true,reverseAllowed=true;
            for(int position=0;position<chain.size();position++) {
                var link=graph.pipes().get(chain.get(position));boolean aligned=link.first()==order.get(position);
                forwardAllowed&=boundaryAllowed(graph,link,aligned?1:-1);
                reverseAllowed&=boundaryAllowed(graph,link,aligned?-1:1);
            }
            var a=states.get(start);var b=states.get(finish);
            double dz=graph.reservoirs().get(finish).elevation()-graph.reservoirs().get(start).elevation(),difference=a.pressure()-b.pressure();
            double band=reopenBand(a.pressure(),b.pressure());
            double forwardDrive=difference-a.mass()/a.volume()*GRAVITY*dz,reverseDrive=difference-b.mass()/b.volume()*GRAVITY*dz;
            if(forwardAllowed&&forwardDrive>band||reverseAllowed&&reverseDrive<-band)for(int link:chain)reopen.add(graph.pipes().get(link).identity());
        }
        return reopen;
    }
    private final FluidThermodynamics model;
    private final SolverOwnership ownership;
    private final Map<WorkspaceKey,SparseNewton.Workspace> workspaces=new LinkedHashMap<>();
    private final Map<WorkspaceKey,SparseNewton.Workspace> structures=new LinkedHashMap<>();
    private List<PassiveNetwork.Pipe.Identity> previousPipes=List.of();
    private long[] previousNodeIds=new long[0];
    private double[] previousFlows=new double[0],previousHeads=new double[0];
    /** The input states {@link #previousFlows} were recorded at, and the input states of the solve in progress: a
     * rate-only solve warm-starts only from flows recorded at value-identical input states; see {@link #startPoint}. */
    private List<FluidThermodynamics.State> previousInputStates=List.of(),inputStates=List.of();
    /**
     * The device active set the last pass of the last solve on this graph settled on.
     *
     * <p>A pump standing on its head limit, or at its own shutoff corner, is a property of the
     * island's state and not of one step. Restarting every solve on the target branch makes each
     * one re-walk the whole power-law hydraulic curve down from a flow the head limit has already
     * refused - 9.96 kg/s to 0.07 near shutoff - and the closer the discharge sits to shutoff the
     * longer that walk is, until it no longer fits in the Newton iteration budget at any step size.
     * Starting where the previous point ended makes the first pass the right one and its Newton a
     * correction rather than a descent. Valid only while {@link #previousPipes} and
     * {@link #previousNodeIds} still describe this graph.
     */
    private List<FlowControl.Mode> previousModes=List.of();
    /** The structure of the last solve this solver accepted; {@link #closeIllegalStarts} acts only on it. */
    private List<PassiveNetwork.Pipe.Identity> acceptedPipes=List.of();
    private long[] acceptedNodeIds=new long[0];
    /** The last successful solve, together with the Newton tolerance it converged under: the
     * companion filter may not claim to resolve a defect the solve itself could not. */
    record LastSolve(Equations equations,double[] variables,SparseNewton.Workspace workspace,double newtonTolerance) {}
    private LastSolve lastSolve;
    private boolean rateOnly;
    /** Owned by this solver, which the ownership latch confines to one worker at a time. */
    private final com.wormzjl.createcheme.science.fluid.transport.MixtureViscosity.Workspace viscosities=
            new com.wormzjl.createcheme.science.fluid.transport.MixtureViscosity.Workspace();
    /** The island's retained transport linear algebra; the TR-BDF2 solver hands the same one to both
     * of its stage solvers and uses it for the endpoint rate, so an island holds exactly one. */
    private final ConservativeTransport.Workspace transport;
    public PassiveStepSolver(FluidThermodynamics model){this(model,SolverOwnership.confinedToCurrentThread());}
    public PassiveStepSolver(FluidThermodynamics model,SolverOwnership ownership) {
        this(model,ownership,new ConservativeTransport.Workspace(Objects.requireNonNull(ownership)));
    }
    PassiveStepSolver(FluidThermodynamics model,SolverOwnership ownership,ConservativeTransport.Workspace transport) {
        this.model=Objects.requireNonNull(model);this.ownership=Objects.requireNonNull(ownership);
        this.transport=Objects.requireNonNull(transport);
    }
    public record Result(List<FluidThermodynamics.State> states,double[] massFlows,double deltaTime,SparseNewton.Result numerical,
                         List<FlowControl.Mode> modes,double[] devicePressureChanges,double pumpWorkJoule,double[] externalMoles,double externalEnergyJoule,
                         List<PassiveNetwork.Inventory> inventories,List<ConservativeTransport.BoundaryTransfer> boundaries,List<PipeTransfer> pipeTransfers,Map<Long,InlineFilter> filters) {
        public Result(List<FluidThermodynamics.State> states,double[] massFlows,double deltaTime,SparseNewton.Result numerical,List<FlowControl.Mode> modes,double[] devicePressureChanges,double pumpWorkJoule,double[] externalMoles,double externalEnergyJoule,List<PassiveNetwork.Inventory> inventories,List<ConservativeTransport.BoundaryTransfer> boundaries,List<PipeTransfer> pipeTransfers){this(states,massFlows,deltaTime,numerical,modes,devicePressureChanges,pumpWorkJoule,externalMoles,externalEnergyJoule,inventories,boundaries,pipeTransfers,Map.of());}
        public Result {filters=Map.copyOf(filters);states=List.copyOf(states);massFlows=massFlows.clone();modes=List.copyOf(modes);devicePressureChanges=devicePressureChanges.clone();externalMoles=externalMoles.clone();inventories=List.copyOf(inventories);boundaries=List.copyOf(boundaries);pipeTransfers=List.copyOf(pipeTransfers);}
        @Override public double[] massFlows(){return massFlows.clone();}
        @Override public double[] devicePressureChanges(){return devicePressureChanges.clone();}
        @Override public double[] externalMoles(){return externalMoles.clone();}
    }
    public Result solve(PassiveNetwork graph,double dt,Runnable checkpoint) {
        return solve(graph,dt,checkpoint,Acceptance.FULL);
    }
    public Result solveRate(PassiveNetwork graph,Runnable checkpoint,Acceptance acceptance){return solve(graph,1,checkpoint,acceptance,true);}
    public Result solve(PassiveNetwork graph,double dt,Runnable checkpoint,Acceptance acceptance) {return solve(graph,dt,checkpoint,acceptance,false);}
    private Result solve(PassiveNetwork inputGraph,double dt,Runnable checkpoint,Acceptance acceptance,boolean rateOnly) {
        // A junction minted by its constructor and not yet sized to its connections is sized here (no-op otherwise).
        inputGraph=PassiveNetwork.sizeJunctionHoldups(inputGraph,model);
        this.rateOnly=rateOnly;
        inputStates=inputGraph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList();
        Objects.requireNonNull(acceptance);SolverDiagnostics.count(SolverDiagnostics.implicitSolves);
        ownership.check("Each executing island job needs its own step workspace");
        lastSolve=null;
        if(!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Positive finite substep required");
        // A cold junction's pressure guess replaced by the balanced one; a Newton starting guess only, the junction's
        // owned inventory is untouched. See {@link #seedBoundaryJunctions}.
        var graph=seedBoundaryJunctions(inputGraph,checkpoint);
        if(graph.pipes().isEmpty()&&graph.scheduledTransfers().isEmpty())return new Result(graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),new double[0],dt,
                new SparseNewton.Result(new double[0],0,0,0,0,0),List.of(),new double[0],0,new double[model.hydrocarbon.componentCount()+1],0,
                graph.reservoirs().stream().map(PassiveNetwork.Reservoir::inventory).toList(),List.of(),List.of());
        if(graph.reservoirs().stream().anyMatch(PassiveNetwork.Reservoir::empty))throw new IllegalArgumentException("Evacuated reservoir has no fluid temperature; connected filling requires a supported initialization state");
        var modes=new ArrayList<FlowControl.Mode>();
        boolean carryModes=previousModes.size()==graph.pipes().size()&&previousPipes.equals(identities(graph))&&Arrays.equals(previousNodeIds,nodeIds(graph));
        // Pumps this solve has recognized as standing at their own shutoff corner; see
        // {@link #shutoffBand}. Not part of any workspace or cycle key: it changes no equation, it
        // only forbids one transition from being taken back within this solve.
        boolean[] atShutoff=new boolean[graph.pipes().size()];
        for(var pipe:graph.pipes())modes.add(switch(pipe.control()) {
            case FlowControl.Passive ignored->FlowControl.Mode.PASSIVE;
            case FlowControl.Pump pump->pump.targetVolumeFlow()==0?FlowControl.Mode.CLOSED:
                    graph.pipes().stream().anyMatch(p->p.blockedDirections()!=0)?FlowControl.Mode.PUMP_HEAD_LIMIT:
                    pump.targetVolumeFlow()>pipe.minimumArea()*model.velocityLimit(graph.reservoirs().get(pipe.first()).state())?FlowControl.Mode.PUMP_HEAD_LIMIT:FlowControl.Mode.PUMP_TARGET;
            case FlowControl.PressureValve valve->graph.reservoirs().get(pipe.first()).state().pressure()>valve.targetPressure()+.01
                    ?FlowControl.Mode.VALVE_OPEN:FlowControl.Mode.CLOSED;
        });
        // A pump the previous point left on its head limit or at its shutoff corner opens there;
        // see {@link #previousModes}. Only those two: the target branch and the velocity clamp are
        // decided against this step's own states above, and a presentation-only accepted mode is
        // never a mode a pass may run in.
        if(carryModes)for(int i=0;i<modes.size();i++) {
            var pipe=graph.pipes().get(i);
            if(!(pipe.control() instanceof FlowControl.Pump pump)||pump.targetVolumeFlow()==0)continue;
            var carried=previousModes.get(i);
            if(carried!=FlowControl.Mode.PUMP_HEAD_LIMIT&&carried!=FlowControl.Mode.CLOSED)continue;
            modes.set(i,carried);
            // The one place a pump that stood at its shutoff corner is offered its head limit back.
            // The test is taken on the accepted state this step starts from, once per solve, so it
            // is not a transition the pass sequence can take again; see {@link #atShutoff}.
            if(carried!=FlowControl.Mode.CLOSED)continue;
            var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
            double margin=riseLimit(pump,a.state())-(b.state().pressure()-a.state().pressure()
                    +a.state().mass()/a.state().volume()*GRAVITY*(b.elevation()-a.elevation()));
            if(margin>shutoffBand(Math.max(a.state().pressure(),b.state().pressure()),1e-9))modes.set(i,FlowControl.Mode.PUMP_HEAD_LIMIT);
            else atShutoff[i]=true;
        }
        // A connection closed to all transport before the solve starts is in the same active-set
        // state the pass-by-pass closure below produces, actuator included: an actuator left on its
        // own setpoint across a closed edge is a second equation for a flow the closure has already
        // decided, and on a junction whose other edges are closed too it is one equation too many
        // and the factorization is singular.
        boolean[] boundaryClosed=new boolean[graph.pipes().size()];
        for(int i=0;i<boundaryClosed.length;i++)if(graph.pipes().get(i).blockedDirections()==3) {
            boundaryClosed[i]=true;
            if(!(graph.pipes().get(i).control() instanceof FlowControl.Passive))modes.set(i,FlowControl.Mode.CLOSED);
        }
        // A run the interval solver reopened is exempt from the start-of-solve closures of this one solve; the pass loop
        // may still close it on a converged point. See {@link #reopenable}.
        boolean[] keep=null;
        if(!keepOpen.isEmpty()&&!rateOnly){var ids=identities(graph);keep=new boolean[ids.size()];for(int i=0;i<keep.length;i++)keep[i]=keepOpen.contains(ids.get(i));keepOpen=Set.of();}
        closeDeadHeads(graph,boundaryClosed,keep);
        closeIllegalStarts(graph,modes,boundaryClosed,keep);
        // The species this island can carry, and the trial states built from them, are read off the
        // connections this pass leaves open - so they are stated after the closure above and not
        // before it. A connection the closure has taken carries exactly zero by a row of its own,
        // for the whole solve, so it delivers nothing; seeding a vessel behind it with an entry
        // trace of a component only that connection could bring puts an unknown on a nonnegativity
        // boundary whose only root is zero, which is what used to refuse the whole Newton step. See
        // {@link #closeDeadHeads} and documentation/DEAD_HEADED_LINE.md.
        var reachable=reachableComponents(graph,null,boundaryClosed);
        var seeds=initialPhaseSeeds(graph,dt,checkpoint,reachable);
        var seen=new HashSet<WorkspaceKey>();
        // Constant for this solve: every pass keys on the same graph identity and component support.
        var pipeIdentities=identities(graph);
        long[] nodeIds=nodeIds(graph);byte[] kinds=new byte[nodeIds.length];
        for(int i=0;i<kinds.length;i++)kinds[i]=(byte)graph.reservoirs().get(i).kind().ordinal();
        boolean[] componentMask=componentMask(graph);
        // Components this solve has reactivated, per node. Reactivation is monotone within one solve
        // - a later pass's seed may not undo it - which is what keeps the support half of the cycle
        // key monotone and the pass sequence finite; demotion happens at the next solve's seed.
        boolean[][] promoted=new boolean[seeds.size()][];
        int maximumPasses=Math.min(512,16+2*graph.reservoirs().size()+2*graph.pipes().size());
        for(int pass=0;pass<maximumPasses;pass++) {
            checkpoint.run();SolverDiagnostics.count(SolverDiagnostics.activeSetPasses);
            // A junction owns stock like a vessel: it keeps the undirected composition basis
            // {@link #reachableComponents} gives every node and its own state as its seed.
            int[] phases=new int[seeds.size()];for(int i=0;i<phases.length;i++)phases[i]=phaseCode(seeds.get(i));
            byte[] modeCodes=new byte[modes.size()];for(int i=0;i<modeCodes.length;i++)modeCodes[i]=(byte)modes.get(i).ordinal();
            var supports=supports(graph,seeds,reachable,promoted);
            var equations=new Equations(graph,dt,modes,boundaryClosed,seeds,reachable,supports);
            // The active-set state is exactly what varies between passes, so the structure key is
            // also the cycle key: repeating one means the pass sequence cannot make progress. The
            // frozen junction donors belong to it for the same reason the modes do - they decide
            // which equations this pass states, and the pass loop revises them.
            var structure=new WorkspaceKey(0,nodeIds,kinds,pipeIdentities,phases,componentMask,supportCodes(supports),modeCodes,boundaryClosed.clone(),equations.junctionDonorFirst);
            if(!seen.add(structure))throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");
            var key=new WorkspaceKey(Double.doubleToLongBits(dt),nodeIds,kinds,pipeIdentities,phases,componentMask,structure.supports,modeCodes,structure.boundaryClosed,structure.junctionDonors);
            var workspace=workspaces.get(key);
            SolverDiagnostics.count(workspace==null?SolverDiagnostics.workspaceBuilds:SolverDiagnostics.workspaceReuses);
            if(workspace==null){if(workspaces.size()>=4)workspaces.remove(workspaces.keySet().iterator().next());var previous=structures.get(structure);workspace=previous==null?new SparseNewton.Workspace(ownership):previous.forkPreconditioner();workspaces.put(key,workspace);}
            if(structures.size()>=4&&!structures.containsKey(structure))structures.remove(structures.keySet().iterator().next());structures.put(structure,workspace);
            SparseNewton.Result numerical;
            // Small headspaces amplify inventory roundoff into pressure/flow errors; solve these
            // more tightly - but only a node that holds a finite inventory has a headspace at all.
            // A generator and a void carry a prescribed boundary state, and a junction a
            // normalized guess with no volume and no stock; none of them owns roundoff for a small
            // headspace to amplify, and none of them contributes an unknown or a row, so none may
            // tighten the tolerance every other node is then held to. A port does keep its say: it
            // is a finite reservoir held fixed for one instantaneous rate evaluation, and holding
            // a liquid-full pair of them at rest to 1e-10 kg/s is exactly what the tolerance buys.
            //
            // A filter block compiles to two junctions seeded from the island's first boundary
            // state, which on a water line is liquid-full, so every placed filter line demanded
            // 1e-10 of nodes that cannot reach it - a tank with a little water in it floors its
            // water saturation row near 1.03e-10 - and the island refined its step to nothing.
            boolean smallHeadspace=false;
            for(int i=0;i<seeds.size();i++) {
                var kind=graph.reservoirs().get(i).kind();
                if(kind!=PassiveNetwork.NodeKind.RESERVOIR&&kind!=PassiveNetwork.NodeKind.PORT)continue;
                if(seeds.get(i).vaporVolume()/seeds.get(i).volume()<.01){smallHeadspace=true;break;}
            }
            // 1e-11 stalls on caloric cancellation in nearly liquid-full water with trace N2.
            // Keep two orders of margin to the unchanged 1e-8 full-equation gate; every final
            // reconstruction and the independent interval accuracy/accounting checks still run.
            double tolerance=acceptance==Acceptance.APPROXIMATE?1e-6:smallHeadspace?1e-10:1e-9;
            try{numerical=SparseNewton.solve(equations,equations.initial(),new SparseNewton.Settings(20,tolerance,1e-6,24),checkpoint,workspace);}
            catch(SparseNewton.Nonconvergence failure) {
                if(failure.lastVariables()==null)throw failure;
                double[] trialFlows=Arrays.copyOfRange(failure.lastVariables(),equations.edgeOffset,equations.edgeOffset+graph.pipes().size());
                // A point the Newton never reached is deliberately not read as evidence about this
                // pass's frozen junction donors, nor about the junction basis those donors state,
                // unlike the converged tests further down. Measured on the filter block line:
                // turning a junction onto a diverged trial's flow sign hands it the tank's nitrogen
                // as a seeded trace nothing delivers, and every swept pressure lost an interval by
                // it - 400 kPa went from interval 8 back to 7, and from a tank settled at
                // 399999.99971 Pa to one at 399998.24 Pa. Widening the junction basis from the same
                // diverged trial is the same mistake with a bigger blast radius, and it is what
                // used to put the phantom trace back at every refinement level: the trial's flows
                // at a stalled point are -1.14e-6 kg/s on a line whose generator drives it
                // forwards, and reading them as backflow let the tank's nitrogen into both of the
                // filter's zero-holdup junctions; see documentation/JUNCTION_PHANTOM_TRACE.md.
                var changedSeeds=phaseCorrection(graph,equations.states(failure.lastVariables()),checkpoint,false);
                if(changedSeeds==null)throw new SparseNewton.Nonconvergence(failure.getMessage()+"; active-set pass="+pass,failure.lastVariables(),failure.domainViolation());
                seeds=changedSeeds;continue;
            }
            double[] x=numerical.variables();var states=equations.states(x);double[] flows=new double[graph.pipes().size()],heads=new double[flows.length];
            // The converged Newton point is not phase-checked here: the reconstructed point below, which is what an accepted
            // step commits, is, and the two agree to the Newton tolerance, so checking both asks one question twice at a TP
            // flash per single-phase node each (review 8.7 (c) D: 43 % of the wall time of a flowing island at 0.1 s).
            // The same outer stability question the phase correction above answers for a whole phase,
            // asked per component of the frozen trace support: this converged point's own fugacity
            // coefficients decide whether an omitted phase is still a trace.
            if(reactivate(equations,states,promoted)>0){seeds=states;continue;}
            boolean changed=false;double work=0;int illegalDirection=-1;
            for(int i=0;i<flows.length;i++) {
                flows[i]=x[equations.edgeOffset+i];heads[i]=equations.controlOffsets[i]<0?0:x[equations.controlOffsets[i]]*1e5;
                if(boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED){flows[i]=0;x[equations.edgeOffset+i]=0;}
                var pipe=graph.pipes().get(i);var up=states.get(pipe.first());double rho=up.mass()/up.volume();var mode=modes.get(i);var next=mode;
                if(!boundaryAllowed(graph,pipe,flows[i])&&Math.abs(flows[i])>1e-10){if(illegalDirection<0)illegalDirection=i;continue;}
                if(boundaryClosed[i])continue;
                if(pipe.control() instanceof FlowControl.Pump pump) {
                    double limit=riseLimit(pump,rho),margin=limit-demand(graph,pipe,states,rho);
                    double band=shutoffBand(equations.pressureScales[i],tolerance);
                    if(pump.targetVolumeFlow()==0)next=FlowControl.Mode.CLOSED;
                    else if(mode==FlowControl.Mode.PUMP_TARGET&&pump.targetVolumeFlow()>pipe.minimumArea()*model.velocityLimit(up)*(1+1e-8))next=FlowControl.Mode.PUMP_HEAD_LIMIT;
                    else if(mode==FlowControl.Mode.PUMP_TARGET&&heads[i]>limit+.01)next=FlowControl.Mode.PUMP_HEAD_LIMIT;
                    else if(mode==FlowControl.Mode.PUMP_HEAD_LIMIT&&margin<band)next=FlowControl.Mode.CLOSED;
                    else if(mode==FlowControl.Mode.PUMP_HEAD_LIMIT&&flows[i]/rho>pump.targetVolumeFlow()*(1+1e-8))next=FlowControl.Mode.PUMP_TARGET;
                    else if(mode==FlowControl.Mode.CLOSED&&!atShutoff[i]&&margin>band)next=FlowControl.Mode.PUMP_HEAD_LIMIT;
                    work+=dt*Math.max(0,flows[i])/rho*Math.max(0,heads[i])/pump.efficiency();
                }else if(pipe.control() instanceof FlowControl.PressureValve valve) {
                    if(mode==FlowControl.Mode.VALVE_REGULATING&&flows[i]<-1e-10)next=FlowControl.Mode.CLOSED;
                    else if(mode==FlowControl.Mode.VALVE_REGULATING&&flows[i]>massFlowLimit(pipe,up)*(1+1e-8))next=FlowControl.Mode.VALVE_OPEN;
                    else if(mode==FlowControl.Mode.VALVE_REGULATING&&heads[i]<-.01)next=FlowControl.Mode.VALVE_OPEN;
                    else if(mode==FlowControl.Mode.VALVE_OPEN&&flows[i]<-1e-10)next=FlowControl.Mode.CLOSED;
                    else if(mode==FlowControl.Mode.VALVE_OPEN&&!graph.reservoirs().get(pipe.first()).fixed()&&up.pressure()<valve.targetPressure()-.01&&flows[i]>1e-10)next=FlowControl.Mode.VALVE_REGULATING;
                    else if(mode==FlowControl.Mode.CLOSED&&up.pressure()>valve.targetPressure()+.01&&heads[i]>.01)next=FlowControl.Mode.VALVE_REGULATING;
                }
                if(next!=mode&&!changed) {
                    modes.set(i,next);changed=true;
                    if(next==FlowControl.Mode.CLOSED&&mode==FlowControl.Mode.PUMP_HEAD_LIMIT)atShutoff[i]=true;
                }
            }
            // A passive connection showing a direction its own boundary forbids, next to a device
            // that has just decided it cannot run in its current mode, is that device's symptom and
            // not an event of its own: a pump held above its shutoff head pushes its whole suction
            // line backwards, and the generator edge feeding it is the first thing that shows it.
            // The pass applies one active-set change, so whichever of the two is taken first
            // discards the other - and {@code boundaryClosed} is never cleared again within a solve,
            // so taking the symptom first pins the wrong active set for good: the closed suction
            // edge forces the pump's own flow to zero through the junction mass balance, the
            // reverse-flow test that would have closed the pump never fires again, and the pump is
            // left at its head limit on a junction sitting exactly on the upwind and
            // junction inflow-floor discontinuities of the former zero-holdup junction rows, where the Newton
            // cannot converge at any step size. Deciding the device first leaves the boundary
            // closure to a later pass, where it is applied only if the direction is still illegal
            // once every device runs in a mode it can actually hold.
            if(!changed&&illegalDirection>=0) {
                var pipe=graph.pipes().get(illegalDirection);boundaryClosed[illegalDirection]=true;
                if(!(pipe.control() instanceof FlowControl.Passive))modes.set(illegalDirection,FlowControl.Mode.CLOSED);
                changed=true;
            }
            if(changed){seeds=states;previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousInputStates=inputStates;previousFlows=flows.clone();previousHeads=heads.clone();previousModes=List.copyOf(modes);continue;}
            for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);var upstream=states.get(flows[edge]>=0?pipe.first():pipe.second());
                if(Math.abs(flows[edge])>massFlowLimit(pipe,upstream)*(1+2e-8)+1e-12)throw new SparseNewton.Nonconvergence("Velocity constraint did not close");
            }
            var projection=ConservativeTransport.reconstruct(graph,states,flows,heads,dt,model,checkpoint,transport,rateOnly);
            var changedSeeds=phaseCorrection(graph,projection.states(),checkpoint,true,equations.cachedPrepared);if(changedSeeds!=null){seeds=changedSeeds;continue;}
            var reconstructed=x.clone();
            for(int node=0;node<projection.states().size();node++)if(equations.layout[node]!=null){var encoded=equations.layout[node].encode(projection.states().get(node));System.arraycopy(encoded,0,reconstructed,equations.offsets[node],encoded.length);}
            SolverDiagnostics.count(SolverDiagnostics.verificationResiduals);
            double maximumResidual=0;for(double residual:equations.residual(reconstructed))maximumResidual=Math.max(maximumResidual,Math.abs(residual));
            if(maximumResidual>(acceptance==Acceptance.FULL?1e-8:1e-6))throw new SparseNewton.Nonconvergence("Conservative reconstruction fails equation gate: "+maximumResidual);
            if(acceptance==Acceptance.APPROXIMATE)checkApproximation(equations,reconstructed,projection.states(),flows,workspace,checkpoint,tolerance);
            checkConservation(graph,projection);
            previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousInputStates=inputStates;previousFlows=flows.clone();previousHeads=heads.clone();previousModes=List.copyOf(modes);
            acceptedPipes=pipeIdentities;acceptedNodeIds=nodeIds;
            var acceptedModes=new ArrayList<>(modes);
            for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);
                if(boundaryClosed[edge]&&pipe.control() instanceof FlowControl.Passive)acceptedModes.set(edge,FlowControl.Mode.CLOSED);
                else if(canClamp(modes.get(edge))&&Math.abs(flows[edge])>=massFlowLimit(pipe,projection.states().get(flows[edge]>=0?pipe.first():pipe.second()))*(1-1e-7)) {
                    acceptedModes.set(edge,switch(pipe.control()){case FlowControl.Passive ignored->FlowControl.Mode.VELOCITY_LIMITED;case FlowControl.Pump ignored->FlowControl.Mode.PUMP_VELOCITY_LIMIT;case FlowControl.PressureValve ignored->FlowControl.Mode.VALVE_VELOCITY_LIMIT;});
                }
            }
            if(SolverDiagnostics.ENABLED)SolverDiagnostics.count(SolverDiagnostics.solidMomentProjectionsAtAcceptedPoints,
                    equations.negativeSolidUnknowns(x)+equations.negativeSolidUnknowns(reconstructed));
            lastSolve=new LastSolve(equations,x,workspace,tolerance);
            var accepted=new Result(projection.states(),flows,dt,numerical,acceptedModes,heads,projection.pumpWork(),projection.externalMoles(),projection.externalEnergy(),projection.inventories(),projection.boundaries(),PipeTransfer.sample(graph,projection.states(),flows,dt),projection.filters());
            // The retained last solve keeps its equations and point, not their per-node decode caches (states, transport
            // rows, prepared temperature workspaces): a later reader re-decodes (review 8.7 (c) E2).
            equations.releaseDecodeCache();
            return accepted;
        }
        throw new SparseNewton.Nonconvergence("Device active-set limit");
    }
    /** A linearized companion stage: reconstructed states, edge mass flows and boundary ledger. */
    record Companion(List<FluidThermodynamics.State> states,double[] massFlows,List<ConservativeTransport.BoundaryTransfer> boundaries) {}
    /**
     * Filters a change of reservoir target inventories through the last successful solve instead of
     * re-solving it. TR-BDF2's embedded companion stage is exactly that: the same equations at the
     * same step, with the stage-two targets shifted by the order-three defect. The residual
     * subtracts the target on a reservoir's component and energy rows only, so shifting it by
     * {@code delta} moves those rows by {@code delta/scale} and leaves the volume, equilibrium,
     * hydraulic and junction rows alone. The base point is the converged solution, where the
     * residual is already inside the Newton tolerance, so the step to the perturbed root is
     * {@code J*dx = delta/scale}; the endpoint is then decoded from {@code x+dx} and reconstructed
     * on {@code corrected} exactly as an ordinary solve would be.
     *
     * <p>The factorization may be the chord an earlier stage built; that is a first-order
     * linearization either way, and the result is an error estimate that is never committed.
     * Returns {@code null} - and the caller must then run the full nonlinear stage - when there is
     * no matching last solve, when its factorization is gone or singular, when the linearized point
     * leaves the property domain, or when the reconstruction refuses it.
     *
     * <p>Two rules keep the filter inside what the engine can actually resolve, because unlike the
     * nonlinear stage it has no residual of its own to answer to:
     *
     * <ul>
     * <li>A defect whose scaled right-hand side is already inside the Newton tolerance that solve
     *     converged under is not filtered at all: the correction is zero, because the engine cannot
     *     resolve a target shift its own stage solve treats as converged. That is what the
     *     nonlinear stage did - its Newton exited at iteration 0 and returned the stage-two point.</li>
     * <li>Otherwise the filtered point must actually solve the perturbed equations: the residual it
     *     leaves, {@code f(x+dx) - delta/scale}, may not exceed the solve's own tolerance or the
     *     defect it was handed. The factorization may be a chord inherited from another step size -
     *     {@link SparseNewton.Workspace#forkPreconditioner} carries it across substeps and intervals
     *     - and such a chord is a fine search direction, which the nonlinear residual corrects, but
     *     it is the implicit operator of <em>that</em> step, so as an error filter it can overshoot
     *     severalfold. A quiescent island holds one chord for whole intervals, and the overshoot
     *     lands in the flow unknowns, where it is many times the controller's own numerical
     *     allowance and rejects steps that need no refinement. The check costs one residual
     *     assembly and shares its node decode with the endpoint below.</li>
     * </ul>
     */
    Companion companion(PassiveNetwork solved,PassiveNetwork corrected,double[][] deltaMoles,double[] deltaEnergy,
                        double[][] deltaSolidMoments,double[] heads,double dt,Runnable checkpoint) {
        ownership.check("Each executing island job needs its own step workspace");
        var last=lastSolve;
        if(last==null||last.equations.graph!=solved||last.equations.dt!=dt)return null;
        var equations=last.equations;
        double[] rows=new double[equations.size];
        for(int node=0;node<equations.layout.length;node++) {
            if(equations.layout[node]==null||solved.reservoirs().get(node).junction())continue;
            equations.layout[node].targetRows(deltaMoles[node],deltaEnergy[node],
                    deltaSolidMoments==null?null:deltaSolidMoments[node],rows,equations.offsets[node]);
        }
        double defect=0;for(double row:rows)defect=Math.max(defect,Math.abs(row));
        List<FluidThermodynamics.State> states;double[] flows=new double[solved.pipes().size()];
        try {
            double[] x;
            if(defect<=last.newtonTolerance) {
                SolverDiagnostics.count(SolverDiagnostics.companionDefectsBelowTolerance);x=last.variables.clone();
            }else {
                x=SparseNewton.applyFactorization(last.workspace,last.variables,rows);
                if(x==null)return null;
                SolverDiagnostics.count(SolverDiagnostics.companionFilterResiduals);
                double left=0;double[] perturbed=equations.residual(x);
                for(int row=0;row<perturbed.length;row++)left=Math.max(left,Math.abs(perturbed[row]-rows[row]));
                if(left>Math.max(last.newtonTolerance,defect)) {
                    SolverDiagnostics.count(SolverDiagnostics.companionFiltersRefused);return null;
                }
            }
            states=equations.states(x);
            for(int edge=0;edge<flows.length;edge++)flows[edge]=equations.boundaryClosed[edge]||equations.modes.get(edge)==FlowControl.Mode.CLOSED
                    ?0:x[equations.edgeOffset+edge];
            var projection=ConservativeTransport.reconstruct(corrected,states,flows,heads,dt,model,checkpoint,transport);
            return new Companion(projection.states(),flows,projection.boundaries());
        }catch(SparseLuSolver.SolveFailure|SparseNewton.Nonconvergence|IllegalArgumentException outsideTheLinearization){return null;}
    }
    /** Value key over compact codes instead of rendered strings and boxed lists; the arrays belong
     * to the key from construction on, so a caller must hand over a snapshot of anything it mutates.
     * {@code supports} is the per-node, per-component phase support flattened in node order: it
     * decides how many unknowns and rows each node block has, so the sparsity pattern, the colouring,
     * the ordering and every retained factorization belong to it as much as to the phase code. */
    private record WorkspaceKey(long stepBits,long[] nodeIds,byte[] kinds,List<PassiveNetwork.Pipe.Identity> pipes,
                                int[] phases,boolean[] componentMask,byte[] supports,byte[] modes,boolean[] boundaryClosed,
                                boolean[] junctionDonors) {
        @Override public boolean equals(Object other) {
            return other instanceof WorkspaceKey key&&stepBits==key.stepBits&&Arrays.equals(nodeIds,key.nodeIds)
                    &&Arrays.equals(kinds,key.kinds)&&pipes.equals(key.pipes)&&Arrays.equals(phases,key.phases)
                    &&Arrays.equals(componentMask,key.componentMask)&&Arrays.equals(supports,key.supports)
                    &&Arrays.equals(modes,key.modes)&&Arrays.equals(boundaryClosed,key.boundaryClosed)
                    &&Arrays.equals(junctionDonors,key.junctionDonors);
        }
        @Override public int hashCode() {
            int hash=31*Long.hashCode(stepBits)+Arrays.hashCode(nodeIds);
            hash=31*(31*hash+Arrays.hashCode(kinds))+pipes.hashCode();
            hash=31*(31*hash+Arrays.hashCode(phases))+Arrays.hashCode(componentMask);
            hash=31*hash+Arrays.hashCode(supports);
            hash=31*(31*hash+Arrays.hashCode(modes))+Arrays.hashCode(boundaryClosed);
            return 31*hash+Arrays.hashCode(junctionDonors);
        }
    }
    /**
     * Every node's frozen per-component phase support for one pass, derived from that pass's seeds
     * and never from a Newton iterate, with the components this solve has already reactivated kept in
     * both phases. A fixed node owns no unknowns and gets none.
     */
    private PhaseSupport[][] supports(PassiveNetwork graph,List<FluidThermodynamics.State> seeds,boolean[][] reachable,boolean[][] promoted) {
        var supports=new PhaseSupport[seeds.size()][];
        for(int node=0;node<supports.length;node++)
            if(!graph.reservoirs().get(node).fixed())
                supports[node]=PhaseLayout.support(seeds.get(node),Arrays.copyOf(reachable[node],model.hydrocarbon.componentCount()),model.traceTruncation(),promoted[node]);
        return supports;
    }
    /**
     * Components whose omitted phase the converged point no longer supports omitting, marked for the
     * next pass; returns how many. The test runs on the Newton solution of the reduced system, where
     * the fugacity coefficients of both phases are converged and the equation of state has filled
     * {@code ln phi_i} for the omitted phase at infinite dilution, which is what the inequality needs.
     * Every node is swept before any pass is repeated, so one reactivation pass restores everything
     * the state asks for instead of one component at a time.
     */
    private int reactivate(Equations equations,List<FluidThermodynamics.State> states,boolean[][] promoted) {
        if(!model.traceTruncation().enabled())return 0;
        int components=model.hydrocarbon.componentCount(),restored=0;
        for(int node=0;node<states.size();node++) {
            var layout=equations.layout[node];
            if(layout==null||layout.singlePhaseComponentCount()==0)continue;
            boolean[] flags=promoted[node]==null?new boolean[components]:promoted[node];
            int added=layout.reactivate(states.get(node),model.traceTruncation(),flags);
            if(added>0){promoted[node]=flags;restored+=added;}
        }
        if(restored>0)SolverDiagnostics.count(SolverDiagnostics.traceReactivations,restored);
        return restored;
    }
    /** The support arrays as one flat byte array for the workspace and cycle keys. */
    private byte[] supportCodes(PhaseSupport[][] supports) {
        int components=model.hydrocarbon.componentCount();
        byte[] codes=new byte[supports.length*components];
        for(int node=0;node<supports.length;node++) {
            if(supports[node]==null){Arrays.fill(codes,node*components,(node+1)*components,(byte)-1);continue;}
            for(int c=0;c<components;c++)codes[node*components+c]=(byte)supports[node][c].ordinal();
        }
        return codes;
    }
    /** This graph's connections as the part of themselves the retained solver state may key on. */
    private static List<PassiveNetwork.Pipe.Identity> identities(PassiveNetwork graph) {
        var identities=new ArrayList<PassiveNetwork.Pipe.Identity>(graph.pipes().size());
        for(var pipe:graph.pipes())identities.add(pipe.identity());
        return List.copyOf(identities);
    }
    private static long[] nodeIds(PassiveNetwork graph) {
        long[] ids=new long[graph.reservoirs().size()];
        for(int i=0;i<ids.length;i++)ids[i]=graph.reservoirs().get(i).id();
        return ids;
    }
    /** Components any vessel or scheduled injection can supply; a Newton pass carries unknowns for
     * all of them, so an absent component can still arrive through a pipe. Depends on the graph
     * only, so it is evaluated once per solve rather than once per active-set pass. */
    private boolean[] componentMask(PassiveNetwork graph) {
        boolean[] mask=new boolean[model.hydrocarbon.componentCount()];
        for(var node:graph.reservoirs())if(node.kind()!=PassiveNetwork.NodeKind.VOID){var amounts=node.inventory().moles();for(int c=0;c<mask.length;c++)mask[c]|=amounts[c]>0;}
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection input){var amounts=input.molesPerSecond();for(int c=0;c<mask.length;c++)mask[c]|=amounts[c]>0;}
        return mask;
    }
    /** Online trust check against a contracting correction of the complete coupled equations, not separate flashes. */
    private void checkApproximation(Equations equations,double[] point,List<FluidThermodynamics.State> candidate,double[] flows,SparseNewton.Workspace workspace,Runnable checkpoint,double tolerance) {
        var estimate=SparseNewton.estimateCorrection(equations,point,checkpoint,workspace);var probe=estimate.probe();var corrected=equations.states(probe);
        double inflation=estimate.initialResidual()>1e-12?1/(1-Math.min(.5,estimate.probeResidual()/estimate.initialResidual())):1;
        if(phaseCorrection(equations.graph,corrected,checkpoint,true)!=null)throw new ApproximationRejected("Error probe changes a phase regime");
        for(int i=0;i<candidate.size();i++) {
            var a=candidate.get(i);var b=corrected.get(i);
            if(inflation*Math.abs(a.pressure()-b.pressure())>.001*b.pressure()+.1
                    ||inflation*Math.abs(a.temperature()-b.temperature())>.2
                    ||inflation*Math.abs(a.vaporVolume()/a.volume()-b.vaporVolume()/b.volume())>.002
                    ||inflation*Math.abs(a.liquidVolume()/a.volume()-b.liquidVolume()/b.volume())>.002
                    ||inflation*Math.abs(a.waterVolume()/a.volume()-b.waterVolume()/b.volume())>.002)
                throw new ApproximationRejected("Coupled property-error estimate exceeds the fallback trust profile");
        }
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=equations.graph.pipes().get(edge);double q=probe[equations.edgeOffset+edge],floor=1e-10;
            for(int node:new int[]{pipe.first(),pipe.second()})if(!equations.graph.reservoirs().get(node).junction()&&!equations.graph.reservoirs().get(node).fixed())floor+=1e-9*candidate.get(node).mass()/equations.dt;
            if(inflation*Math.abs(q-flows[edge])>floor+.002*Math.abs(q)
                    ||Math.signum(q)!=Math.signum(flows[edge])&&Math.max(Math.abs(q),Math.abs(flows[edge]))>floor
                    ||!boundaryAllowed(equations.graph,pipe,q)&&Math.abs(q)>floor)
                throw new ApproximationRejected("Coupled flow-error estimate exceeds the fallback trust profile");
            double head=equations.controlOffsets[edge]<0?0:probe[equations.controlOffsets[edge]]*1e5;
            var up=corrected.get(pipe.first());var mode=equations.modes.get(edge);
            if(pipe.control() instanceof FlowControl.Pump pump) {
                // A pump the solve closed at its own shutoff corner holds a head within
                // {@link #shutoffBand} of its maximum by construction, so the probe reads the
                // reopening test against that same band rather than a fixed 0.01 Pa.
                double band=shutoffBand(equations.pressureScales[edge],tolerance);
                double limit=riseLimit(pump,up);
                if(q<-floor||mode!=FlowControl.Mode.CLOSED&&head>limit+.01||mode==FlowControl.Mode.CLOSED&&pump.targetVolumeFlow()>0&&head<limit-band)
                    throw new ApproximationRejected("Error probe changes pump feasibility");
            }else if(pipe.control() instanceof FlowControl.PressureValve valve) {
                if(q<-floor||mode==FlowControl.Mode.VALVE_REGULATING&&head<-.01
                        ||mode==FlowControl.Mode.VALVE_OPEN&&up.pressure()<valve.targetPressure()-.01&&q>floor
                        ||mode==FlowControl.Mode.CLOSED&&up.pressure()>valve.targetPressure()+.01&&head>.01)
                    throw new ApproximationRejected("Error probe changes valve feasibility");
            }
            var donor=corrected.get(q>=0?pipe.first():pipe.second());
            if(Math.abs(q)>massFlowLimit(pipe,donor)*(1+2e-8)+floor)throw new ApproximationRejected("Error probe violates velocity constraint");
        }
    }
    /** Physical species can traverse passive pipes in either direction, actuators only downstream.
     * Junction property guesses own no inventory and therefore cannot introduce a species. */
    private boolean[][] reachableComponents(PassiveNetwork graph,double[] directions){return reachableComponents(graph,directions,null);}
    /** {@code closed} names the connections this pass has already pinned to a flow of exactly zero;
     * they carry no species in either direction. {@code null} is no closure at all. */
    private boolean[][] reachableComponents(PassiveNetwork graph,double[] directions,boolean[] closed) {
        int count=model.hydrocarbon.componentCount()+1,nodes=graph.reservoirs().size();
        var possible=new BitSet[nodes];var outgoing=new ArrayList<List<Integer>>(nodes);
        for(int i=0;i<nodes;i++) {
            possible[i]=new BitSet(count);outgoing.add(new ArrayList<>());
            var node=graph.reservoirs().get(i);
            if(node.kind()!=PassiveNetwork.NodeKind.VOID) {
                var amounts=node.inventory().moles();for(int c=0;c<count;c++)if(amounts[c]>0)possible[i].set(c);
            }
        }
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection injection) {
            var amounts=injection.molesPerSecond();for(int c=0;c<count;c++)if(amounts[c]>0)possible[injection.node()].set(c);
        }
        for(int edge=0;edge<graph.pipes().size();edge++) {var pipe=graph.pipes().get(edge);
            if(closed!=null&&closed[edge])continue;
            if(!(pipe.control() instanceof FlowControl.Pump pump)||pump.targetVolumeFlow()>0) {
                if((directions==null||directions[edge]>1e-14)&&boundaryAllowed(graph,pipe,1))outgoing.get(pipe.first()).add(pipe.second());
                if((directions==null||directions[edge]<-1e-14)&&pipe.control() instanceof FlowControl.Passive&&boundaryAllowed(graph,pipe,-1))outgoing.get(pipe.second()).add(pipe.first());
            }
        }
        var queue=new ArrayDeque<Integer>();var queued=new boolean[nodes];
        for(int i=0;i<nodes;i++){queue.add(i);queued[i]=true;}
        while(!queue.isEmpty()) {
            int from=queue.remove();queued[from]=false;
            for(int to:outgoing.get(from)) {
                int before=possible[to].cardinality();possible[to].or(possible[from]);
                if(before!=possible[to].cardinality()&&!queued[to]){queued[to]=true;queue.add(to);}
            }
        }
        var result=new boolean[nodes][count];
        for(int i=0;i<nodes;i++) {
            // A hydraulically isolated junction retains only its arbitrary property guess.
            if(possible[i].isEmpty()&&graph.reservoirs().get(i).junction()) {
                var amounts=PhaseLayout.totalAmounts(graph.reservoirs().get(i).state());for(int c=0;c<count;c++)if(amounts[c]>0)possible[i].set(c);
            }
            for(int c=possible[i].nextSetBit(0);c>=0;c=possible[i].nextSetBit(c+1))result[i][c]=true;
        }
        return result;
    }
    /**
     * A trial point for one pass, per node.
     *
     * <p>A vessel is seeded as it always was: its own inventory, plus a quarter of its mass at
     * most of what is about to arrive, which is a correction to a stock it already owns. A
     * junction is seeded with its own state: its owned holdup is a small stock whose mixture the
     * backward-Euler rows move from where it is.
     */
    private List<FluidThermodynamics.State> initialPhaseSeeds(PassiveNetwork graph,double dt,Runnable checkpoint,boolean[][] reachable) {
        int count=model.hydrocarbon.componentCount()+1,nodes=graph.reservoirs().size();
        double[] flows=initialMassFlows(graph);
        var seeds=new ArrayList<FluidThermodynamics.State>(nodes);
        for(int nodeIndex=0;nodeIndex<nodes;nodeIndex++) {
            var node=graph.reservoirs().get(nodeIndex);var available=reachable[nodeIndex];
            var state=node.state();
            if(node.fixed()||node.junction()){seeds.add(state);continue;}
            var n=PhaseLayout.totalAmounts(state);double total=Arrays.stream(n).sum();boolean changed=false;
            // This is only a trial guess. The accumulation equations still start from the exact original inventory.
            for(int i=0;i<count;i++)if(available[i]&&n[i]==0)changed=true;
            double weightedTemperature=state.mass()*state.temperature(),seedMass=state.mass();
            if(changed)for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection input&&input.node()==nodeIndex) {
                var rates=input.molesPerSecond();for(int i=0;i<count;i++)n[i]+=dt*rates[i];
            }
            if(changed)for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);
                if(pipe.first()!=nodeIndex&&pipe.second()!=nodeIndex)continue;
                double flow=flows[edge];int receiver=flow>=0?pipe.second():pipe.first(),donor=flow>=0?pipe.first():pipe.second();
                if(receiver!=nodeIndex||!boundaryAllowed(graph,pipe,flow))continue;
                var incoming=graph.reservoirs().get(donor).state();double mass=Math.min(state.mass()*.25,dt*Math.abs(flow));var feed=PhaseLayout.totalAmounts(incoming);
                for(int i=0;i<count;i++)n[i]+=mass*feed[i]/incoming.mass();
                weightedTemperature+=mass*incoming.temperature();seedMass+=mass;
            }
            for(int i=0;i<count;i++)if(available[i]&&n[i]==0)n[i]=total*1e-12;
            try{seeds.add(changed?model.flashTP(weightedTemperature/seedMass,state.pressure(),n,checkpoint).withSolidState(state.solids(),state.solidMoments()):state);}
            catch(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation violation){throw violation.at(graph.reservoirs().get(nodeIndex).id());}
        }
        return seeds;
    }
    /** Value identity of two node-state lists (T, P, amounts, mass, volume, enthalpy). */
    private static boolean sameStates(List<FluidThermodynamics.State> a,List<FluidThermodynamics.State> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++) {
            var x=a.get(i);var y=b.get(i);if(x==y)continue;
            if(Double.doubleToLongBits(x.temperature())!=Double.doubleToLongBits(y.temperature())||Double.doubleToLongBits(x.pressure())!=Double.doubleToLongBits(y.pressure())
                    ||Double.doubleToLongBits(x.mass())!=Double.doubleToLongBits(y.mass())||Double.doubleToLongBits(x.volume())!=Double.doubleToLongBits(y.volume())
                    ||Double.doubleToLongBits(x.enthalpy())!=Double.doubleToLongBits(y.enthalpy())
                    ||Double.doubleToLongBits(x.waterLiquid())!=Double.doubleToLongBits(y.waterLiquid())||Double.doubleToLongBits(x.waterVapor())!=Double.doubleToLongBits(y.waterVapor())
                    ||!Arrays.equals(x.liquid(),y.liquid())||!Arrays.equals(x.vapor(),y.vapor()))return false;
        }
        return true;
    }
    /**
     * The balanced pressure seed of a cold junction: every junction of degree three or more joined by open passive
     * connections to nodes that hold their pressure over the solve, whose guess lies outside its neighbours' pressure
     * range, starts from the pressure at which its connections' estimated flows balance, and from the mixture those
     * flows deliver at that pressure. A property guess outside the range makes every flow of the star point the same way
     * and the Newton descend from a state no solution is near (the 7-of-32 static matrix of review 6.1). A starting guess
     * only: the junction's owned inventory is not touched.
     */
    private PassiveNetwork seedBoundaryJunctions(PassiveNetwork graph,Runnable checkpoint) {
        List<PassiveNetwork.Reservoir> nodes=null;
        for(int node=0;node<graph.reservoirs().size();node++) {
            var junction=graph.reservoirs().get(node);
            if(!junction.junction()||!junction.state().solids().empty())continue;
            double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;int degree=0;boolean eligible=true;
            for(var pipe:graph.pipes())if(pipe.first()==node||pipe.second()==node) {
                degree++;int other=pipe.first()==node?pipe.second():pipe.first();
                boolean held=graph.reservoirs().get(other).fixed();
                if(!held||!(pipe.control() instanceof FlowControl.Passive)
                        ||pipe.filter()!=null||pipe.blockedDirections()!=0){eligible=false;break;}
                double pressure=graph.reservoirs().get(other).state().pressure();
                low=Math.min(low,pressure);high=Math.max(high,pressure);
            }
            if(!eligible||degree<3||!(high>low))continue;
            double pressure=junction.state().pressure();
            if(pressure>low&&pressure<high)continue; // Keep an already interior/settled guess.
            var seed=balancedBoundarySeed(graph,node,low,high,checkpoint);
            if(nodes==null)nodes=new ArrayList<>(graph.reservoirs());
            nodes.set(node,new PassiveNetwork.Reservoir(junction.id(),junction.elevation(),seed,
                junction.kind(),junction.inventory()));
        }
        return nodes==null?graph:new PassiveNetwork(nodes,graph.pipes(),graph.scheduledTransfers());
    }

    /** The seed itself: eight sweeps of a 36-step bisection on the junction pressure for zero net estimated inflow, then a
     * 30-step bisection on the temperature at which the delivered mixture has the delivered specific enthalpy. */
    private FluidThermodynamics.State balancedBoundarySeed(PassiveNetwork graph,int node,double low,double high,Runnable checkpoint) {
        var junction=graph.reservoirs().get(node);var seed=junction.state();
        double stock=PhaseLayout.sum(PhaseLayout.totalAmounts(seed));
        for(int sweep=0;sweep<8;sweep++) {
            checkpoint.run();
            var nodes=new ArrayList<>(graph.reservoirs());
            nodes.set(node,new PassiveNetwork.Reservoir(junction.id(),junction.elevation(),seed,junction.kind(),junction.inventory()));
            var trial=new PassiveNetwork(nodes,graph.pipes());
            double lo=low,hi=high;
            for(int k=0;k<36;k++) {
                double pressure=lo+(hi-lo)*.5,net=0;
                for(var pipe:graph.pipes())if(pipe.first()==node||pipe.second()==node) {
                    double flow=initialMassFlow(trial,pipe,pipe.first()==node?pressure:nodes.get(pipe.first()).state().pressure(),
                        pipe.second()==node?pressure:nodes.get(pipe.second()).state().pressure());
                    if(boundaryAllowed(trial,pipe,flow))net+=pipe.first()==node?-flow:flow;
                }
                if(net>0)lo=pressure;else hi=pressure;
            }
            double pressure=lo+(hi-lo)*.5,mass=0,heat=0,tLow=Double.POSITIVE_INFINITY,tHigh=0;
            var amounts=new double[model.hydrocarbon.componentCount()+1];
            for(var pipe:graph.pipes())if(pipe.first()==node||pipe.second()==node) {
                double flow=initialMassFlow(trial,pipe,pipe.first()==node?pressure:nodes.get(pipe.first()).state().pressure(),
                    pipe.second()==node?pressure:nodes.get(pipe.second()).state().pressure());
                int donor=flow>=0?pipe.first():pipe.second();
                if(donor==node||!boundaryAllowed(trial,pipe,flow))continue;
                var source=nodes.get(donor);double rate=Math.abs(flow);var n=PhaseLayout.totalAmounts(source.state());
                for(int c=0;c<n.length;c++)amounts[c]+=rate*n[c]/source.state().mass();
                mass+=rate;heat+=rate*(source.state().enthalpy()/source.state().mass()+GRAVITY*(source.elevation()-junction.elevation()));
                tLow=Math.min(tLow,source.state().temperature());tHigh=Math.max(tHigh,source.state().temperature());
            }
            if(!(mass>0))return seed;
            double total=PhaseLayout.sum(amounts);for(int c=0;c<amounts.length;c++)amounts[c]*=stock/total;
            double lower=Math.max(tLow-20,model.domain().envelope().minimumTemperature());
            double upper=Math.min(tHigh+20,model.domain().envelope().maximumTemperature());
            for(int c=0;c<amounts.length;c++)if(amounts[c]>0) {
                var range=model.domain().range(c);lower=Math.max(lower,range[0]);upper=Math.min(upper,range[1]);
            }
            if(!(lower<upper))return seed;
            for(int k=0;k<30;k++) {
                double t=lower+(upper-lower)*.5;
                var point=model.flashTP(t,pressure,amounts,checkpoint);
                if(point.enthalpy()/point.mass()<heat/mass)lower=t;else upper=t;
            }
            seed=model.flashTP(lower+(upper-lower)*.5,pressure,amounts,checkpoint);
        }
        return seed;
    }

    /** The mass of a conserved amount vector, water last. */
    private double molarMass(double[] amounts) {
        double mass=0;for(int c=0;c<amounts.length;c++)mass+=amounts[c]*model.molecularWeight(c);
        return mass;
    }
    /**
     * The whole island's connection flows as the states this solve starts at imply them, with
     * every chain of zero-holdup junctions carrying one flow.
     *
     * <p>{@link #initialMassFlow} answers one connection at a time, against the two pressures at
     * its ends. On a vessel that is the right question. Across a zero-holdup junction it is not:
     * the junction stores nothing, so the connections on either side of it carry the <em>same</em>
     * flow, and the junction's own pressure - which is what the per-connection answer reads - is
     * an output of the previous solve carrying that solve's roundoff. On a settled line the
     * pressures along the chain differ by micropascals and the per-connection answer is noise: at
     * 150 kPa it said the tank was pushing -7.2e-7 kg/s back into the filter's outlet junction
     * while the filter was pushing +1.6e-5 kg/s forward into the same junction, a pattern no
     * junction can be in. Everything a pass states about a junction - which species it mixes, what
     * that mixture is, which end of each connection donates into it - is read off this point, so
     * stating it from a point like that is what left the tank's nitrogen on a junction nothing was
     * feeding; see {@code documentation/JUNCTION_PHANTOM_TRACE.md}.
     *
     * <p>So a maximal run of degree-two junctions joined by passive connections is contracted to
     * one resistance between the two nodes at its ends, which do own their pressures, and the
     * single flow that resistance passes is laid on every connection in it. An actuator is never
     * contracted through, because its own setpoint and not the chain's resistance decides what it
     * passes; a junction of degree three or more is not contracted either, because its split needs
     * the solve and not an estimate.
     */
    private double[] initialMassFlows(PassiveNetwork graph) {
        int edges=graph.pipes().size(),nodes=graph.reservoirs().size();
        double[] q=new double[edges];
        for(int edge=0;edge<edges;edge++)q[edge]=initialMassFlow(graph,graph.pipes().get(edge));
        int[] degree=new int[nodes];boolean[] actuated=new boolean[nodes];
        for(var pipe:graph.pipes()) {
            degree[pipe.first()]++;degree[pipe.second()]++;
            if(!(pipe.control() instanceof FlowControl.Passive)){actuated[pipe.first()]=true;actuated[pipe.second()]=true;}
        }
        boolean[] interior=new boolean[nodes];boolean any=false;
        for(int i=0;i<nodes;i++)any|=interior[i]=graph.reservoirs().get(i).junction()&&degree[i]==2&&!actuated[i];
        if(!any)return q;
        int[][] nodeEdges=new int[nodes][];
        for(int i=0;i<nodes;i++)nodeEdges[i]=new int[degree[i]];
        Arrays.fill(degree,0);
        for(int edge=0;edge<edges;edge++) {
            var pipe=graph.pipes().get(edge);
            nodeEdges[pipe.first()][degree[pipe.first()]++]=edge;nodeEdges[pipe.second()][degree[pipe.second()]++]=edge;
        }
        boolean[] taken=new boolean[edges];
        for(int edge=0;edge<edges;edge++) {
            if(taken[edge])continue;
            var pipe=graph.pipes().get(edge);
            if(!interior[pipe.first()]&&!interior[pipe.second()])continue;
            // Walk out of this connection in both directions until a node that owns a pressure.
            var chain=new ArrayList<Integer>();var order=new ArrayList<Integer>();
            order.add(pipe.first());chain.add(edge);order.add(pipe.second());taken[edge]=true;
            for(int end=0;end<2;end++) {
                int at=end==0?pipe.first():pipe.second(),from=edge;
                while(interior[at]) {
                    int next=nodeEdges[at][0]==from?nodeEdges[at][1]:nodeEdges[at][0];
                    if(taken[next])break;
                    taken[next]=true;var step=graph.pipes().get(next);
                    int beyond=step.first()==at?step.second():step.first();
                    if(end==0){chain.addFirst(next);order.addFirst(beyond);}else{chain.add(next);order.add(beyond);}
                    at=beyond;from=next;
                }
            }
            int start=order.getFirst(),finish=order.getLast();
            if(start==finish||interior[start]||interior[finish])continue;
            var a=graph.reservoirs().get(start);var b=graph.reservoirs().get(finish);
            var upstream=a.state().pressure()>=b.state().pressure()?a.state():b.state();
            double rho=upstream.mass()/upstream.volume(),mu=viscosity(upstream);
            double driving=a.state().pressure()-b.state().pressure()-rho*GRAVITY*(b.elevation()-a.elevation());
            double lo=0,hi=1;
            while(chainPressureDrop(graph,chain,hi,rho,mu)<Math.abs(driving)&&hi<1e6)hi*=2;
            for(int j=0;j<50;j++) {
                double mid=(lo+hi)/2;
                if(chainPressureDrop(graph,chain,mid,rho,mu)>Math.abs(driving))hi=mid;else lo=mid;
            }
            double magnitude=(lo+hi)/2;
            var donor=graph.reservoirs().get(driving>=0?start:finish).state();
            for(int link:chain)magnitude=Math.min(magnitude,massFlowLimit(graph.pipes().get(link),donor));
            double flow=Math.copySign(magnitude,driving);
            for(int position=0;position<chain.size();position++) {
                var link=graph.pipes().get(chain.get(position));
                q[chain.get(position)]=link.first()==order.get(position)?flow:-flow;
            }
        }
        return q;
    }
    /**
     * The connection flows and actuator heads a pass with this active set starts from, which is
     * the whole of {@link Equations#buildInitial}'s edge half.
     *
     * <p>It lives here rather than in {@link Equations} because the pass reads it twice: once to
     * lay it into its own initial point, and once - before the equations exist - in
     * {@link #closeIllegalStarts}, which reads the direction each connection starts in.
     *
     * <p>{@code seeds} may be {@code null} before this solve has any, which only a regulating
     * valve reads, and no pass can start in that mode.
     */
    private void startPoint(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                            List<PassiveNetwork.Pipe.Identity> pipeIdentities,List<FluidThermodynamics.State> seeds,
                            double[] flows,double[] heads,boolean[] headSet) {
        boolean warmFlow=graph.reservoirs().stream().anyMatch(node->node.junction()||node.fixed())
                ||graph.pipes().stream().anyMatch(pipe->phaseCode(graph.reservoirs().get(pipe.first()).state())!=phaseCode(graph.reservoirs().get(pipe.second()).state()));
        // A carry restated at a job start holds modes but no flows; see {@link #replayStart}.
        boolean previousAvailable=previousPipes.equals(pipeIdentities)&&Arrays.equals(previousNodeIds,nodeIds(graph))&&previousFlows.length==graph.pipes().size();
        // A rate-only solve's warm start is only as good as the state it was recorded at: it is a cold start's solve, or
        // a start-of-step guard's, whose previous flows belong to another point in time, and a rate solve warm-started
        // from cap-level flows of seconds ago stalls in the junction rows at rest (review 7.7). It uses them only when
        // they were recorded at value-identical input states (a re-solve of the same point, or a later pass of it).
        if(rateOnly&&!sameStates(previousInputStates,inputStates))previousAvailable=false;
        // Built once, and only when this point has no history to start from; see
        // {@link #initialMassFlows}.
        double[] estimate=warmFlow&&!previousAvailable?initialMassFlows(graph):null;
        for(int i=0;i<graph.pipes().size();i++) {
            var pipe=graph.pipes().get(i);var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
            if(boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED)continue;
            if(modes.get(i)==FlowControl.Mode.PUMP_TARGET){flows[i]=((FlowControl.Pump)pipe.control()).targetVolumeFlow()*a.state().mass()/a.state().volume();continue;}
            // A connection whose pump is holding its limit starts from a flow that limit can
            // produce, never from one it has already refused; see {@link #headLimitMassFlow}.
            // A point already on the limit - the previous pass, or the previous accepted step
            // of a settled island - carries its own flow forward as before.
            // A pump the carry has just offered its head limit back from CLOSED starts from the limit's own flow and head,
            // not from the closed point's (zero flow and the head the closed pump held), a head the pass will not have.
            // From that closed head a long step's pass chose the discharge tank's bulk density for the column and ended
            // every 5 s step past the shutoff, so the pump re-closed for good 12.3 kPa short (review 8.8 (c)).
            boolean reopened=previousModes.size()==graph.pipes().size()&&previousModes.get(i)==FlowControl.Mode.CLOSED;
            if(modes.get(i)==FlowControl.Mode.PUMP_HEAD_LIMIT&&pipe.control() instanceof FlowControl.Pump pump
                    &&(reopened||!(previousAvailable&&previousHeads[i]<=riseLimit(pump,a.state())))) {
                flows[i]=headLimitMassFlow(graph,pipe,pump);
                heads[i]=riseLimit(pump,a.state())/1e5;headSet[i]=true;
                continue;
            }
            if(previousAvailable){
                flows[i]=previousFlows[i];heads[i]=previousHeads[i]/1e5;headSet[i]=true;
                if(modes.get(i)==FlowControl.Mode.VALVE_REGULATING&&pipe.control() instanceof FlowControl.PressureValve valve) {
                    double predictedDrop=a.state().pressure()-(seeds==null?a.state():seeds.get(pipe.first())).pressure();
                    if(predictedDrop>0)flows[i]*=Math.clamp((a.state().pressure()-valve.targetPressure())/predictedDrop,0,1);
                    double rho=a.state().mass()/a.state().volume();
                    heads[i]=(a.state().pressure()-b.state().pressure()-rho*GRAVITY*(b.elevation()-a.elevation())
                            -pipe.pressureDrop(flows[i],rho,viscosity(a.state())))/1e5;
                }
                continue;
            }
            if(!warmFlow)continue;
            flows[i]=estimate[i];
        }
        if(!hasSolids(graph)&&graph.pipes().stream().noneMatch(pipe->pipe.filter()!=null))return;
        int nodes=graph.reservoirs().size();int[] degree=new int[nodes];
        for(var pipe:graph.pipes()){degree[pipe.first()]++;degree[pipe.second()]++;}
        int[][] nodeEdges=new int[nodes][];
        for(int i=0;i<nodes;i++)nodeEdges[i]=new int[degree[i]];
        Arrays.fill(degree,0);
        for(int edge=0;edge<graph.pipes().size();edge++) {
            var pipe=graph.pipes().get(edge);
            nodeEdges[pipe.first()][degree[pipe.first()]++]=edge;nodeEdges[pipe.second()][degree[pipe.second()]++]=edge;
        }
        boolean[] known=new boolean[graph.pipes().size()];
        for(int i=0;i<known.length;i++)known[i]=modes.get(i)==FlowControl.Mode.PUMP_TARGET||boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED;
        for(int pass=0;pass<known.length;pass++) {
            boolean changed=false;
            for(int node=0;node<nodes;node++)if(graph.reservoirs().get(node).junction()) {
                int missing=-1;double net=0;boolean usable=true;
                for(int edge:nodeEdges[node]) {
                    if(!known[edge]){if(missing>=0){usable=false;break;}missing=edge;continue;}
                    var pipe=graph.pipes().get(edge);double q=flows[edge];boolean receiving=node==(q>=0?pipe.second():pipe.first());
                    double factor=receiving&&pipe.filter()!=null?1-graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state().solidMoments().mass()/graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state().mass():1;
                    net+=receiving?Math.abs(q)*factor:-Math.abs(q);
                }
                if(!usable||missing<0)continue;
                var pipe=graph.pipes().get(missing);double q=pipe.first()==node?net:-net;
                if(node==(q>=0?pipe.second():pipe.first())&&pipe.filter()!=null){var donor=graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state();q/=Math.max(1e-12,1-donor.solidMoments().mass()/donor.mass());}
                if(boundaryAllowed(graph,pipe,q)){flows[missing]=q;known[missing]=true;changed=true;}
            }
            if(!changed)break;
        }
    }
    private static double chainPressureDrop(PassiveNetwork graph,List<Integer> chain,double flow,double rho,double mu) {
        double drop=0;for(int link:chain)drop+=graph.pipes().get(link).pressureDrop(flow,rho,mu);
        return drop;
    }
    private double initialMassFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe) {
        return initialMassFlow(graph,pipe,graph.reservoirs().get(pipe.first()).state().pressure(),
                graph.reservoirs().get(pipe.second()).state().pressure());
    }
    private double initialMassFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double firstPressure,double secondPressure) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        if(pipe.control() instanceof FlowControl.Pump pump)
            return Math.min(pump.targetVolumeFlow()*a.state().mass()/a.state().volume(),massFlowLimit(pipe,a.state()));
        var upstream=firstPressure>=secondPressure?a.state():b.state();double rho=upstream.mass()/upstream.volume(),mu=viscosity(upstream);
        double driving=firstPressure-secondPressure-rho*GRAVITY*(b.elevation()-a.elevation());
        double lo=0,hi=1;while(pipe.pressureDrop(hi,rho,mu)<Math.abs(driving)&&hi<1e6)hi*=2;
        for(int j=0;j<50;j++){double mid=(lo+hi)/2;if(pipe.pressureDrop(mid,rho,mu)>Math.abs(driving))hi=mid;else lo=mid;}
        var donor=graph.reservoirs().get(driving>=0?pipe.first():pipe.second()).state();
        return Math.copySign(Math.min((lo+hi)/2,massFlowLimit(pipe,donor)),driving);
    }
    /**
     * The flow a pump holding its maximum added pressure can actually push through its own
     * connection, from the start-of-step endpoint states: the same bisection
     * {@link #initialMassFlow} runs for a passive connection, with the pump's limit added to the
     * driving pressure.
     *
     * <p>A pump only reaches {@code PUMP_HEAD_LIMIT} because its target flow was refused, and the
     * pass that refused it leaves that target flow behind as the warm start. Near shutoff the two
     * are orders of magnitude apart - 9.96 kg/s against 0.07 - and the connection's loss is a power
     * law in between, so Newton walks down it by roughly halving the flow per iteration and the
     * closer the discharge sits to shutoff the further it has to walk. That walk is what runs out
     * of iterations, and it does so at every step size, which is why refining the step cannot help.
     * Seeding from the limit the pass is actually imposing starts the walk where it would have
     * ended.
     */
    private double headLimitMassFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe,FlowControl.Pump pump) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        double rho=a.state().mass()/a.state().volume(),mu=viscosity(a.state());
        double driving=a.state().pressure()-b.state().pressure()-rho*GRAVITY*(b.elevation()-a.elevation())+riseLimit(pump,rho);
        if(driving<=0)return 0;
        double lo=0,hi=1;while(pipe.pressureDrop(hi,rho,mu)<driving&&hi<1e6)hi*=2;
        for(int j=0;j<50;j++){double mid=(lo+hi)/2;if(pipe.pressureDrop(mid,rho,mu)>driving)hi=mid;else lo=mid;}
        return Math.min((lo+hi)/2,Math.min(massFlowLimit(pipe,a.state()),pump.targetVolumeFlow()*rho));
    }
    /**
     * A pump's pressure-rise limit on what it withdraws: the setting, which is the rise for water at 298.15 K and 1 atm
     * ({@link FluidThermodynamics#pumpReferenceDensity()}), scaled by the suction's density over water's - a head, the
     * one quantity a real pump fixes. On water the limit is the setting within water's compressibility; on nitrogen at
     * 1 atm it is about 1/870 of it, so a pump moving gas between closed vessels reaches its limit, and then its shutoff,
     * within a second instead of evacuating the suction vessel until the gas left the property domain (F1 review,
     * section 5, option P1; documentation/fluid-followups/FLUID_PUMP_AND_THERMO_DOMAIN_REVIEW.md).
     *
     * <p>The density is the suction node's bulk density, mass over volume of everything it holds, because the pump
     * withdraws the node's inventory homogeneously: the same density its target row converts the volume flow with and
     * the velocity clamp caps that end with. No mode, band or switch is added; the target, head-limit and closed modes,
     * the shutoff band and the velocity clamp are decided exactly as before, on this limit.
     *
     * <p>The density is the trial's: the head-limit row reads the suction's decoded state, so the Jacobian carries the
     * limit's dependence on that node's unknowns, and every mode test reads the same density at the converged point.
     */
    private double riseLimit(FlowControl.Pump pump,double suctionDensity) {
        return pump.maximumAddedPressure()*suctionDensity/model.pumpReferenceDensity();
    }
    private double riseLimit(FlowControl.Pump pump,FluidThermodynamics.State suction) {
        return riseLimit(pump,suction.mass()/suction.volume());
    }
    private double massFlowLimit(PassiveNetwork.Pipe pipe,FluidThermodynamics.State donor) {
        return donor.mass()/donor.volume()*pipe.minimumArea()*model.velocityLimit(donor);
    }
    /**
     * The head this connection demands of its device at the point one active-set pass converged
     * to: the discharge pressure less the suction pressure, plus the static column between them.
     *
     * <p>This is the one scalar the {@code PUMP_HEAD_LIMIT}/{@code CLOSED} pair is decided on, and
     * it is literally the same expression in both modes. In {@code PUMP_HEAD_LIMIT} the actuator
     * row pins the head to the maximum, so the hydraulic row makes this {@code maximum - loss(q)}:
     * the margin below is then the edge's own pressure loss, positive for forward flow and
     * negative for reverse. In {@code CLOSED} the actuator row pins the flow to zero, so the free
     * head unknown <em>is</em> this demand and the margin is how much head the pump still has in
     * hand. Reading one quantity in both modes is what makes the pair a threshold on a line rather
     * than two one-sided tests of two different unknowns.
     */
    private static double demand(PassiveNetwork graph,PassiveNetwork.Pipe pipe,List<FluidThermodynamics.State> states,double density) {
        return states.get(pipe.second()).pressure()-states.get(pipe.first()).pressure()
                +density*GRAVITY*(graph.reservoirs().get(pipe.second()).elevation()-graph.reservoirs().get(pipe.first()).elevation());
    }
    /**
     * How much head margin this edge's own rows can actually resolve, and therefore the band around
     * the shutoff corner inside which a device's mode may not be decided by the sign of a flow.
     *
     * <p>The pressure-dimensioned rows of an edge are stated in {@link Equations#pressureScales},
     * so a solve converged to {@code tolerance} has decided them to {@code tolerance*scale} Pa -
     * 1e-4 Pa on an atmospheric island and 6e-4 Pa at 6 bar. Through the line's resistance that is
     * a flow resolution of order 1e-5 kg/s, while the rule this replaces closed a pump on a reverse
     * flow of 1e-10 kg/s: five orders of magnitude below anything the solve decided the sign of.
     * The floor of 0.01 Pa is the band the {@code CLOSED} side already used, kept so that the
     * reopening test is bit for bit the test it was on every island whose scale is at or below
     * 1e5 Pa - which is every island the regression reference was captured on.
     *
     * <p>Both sides of the pair now read the same margin against this one band, so there is no
     * interval in which a pump at its shutoff corner is left holding its head limit. That corner is
     * degenerate - head at the maximum and zero flow satisfy both modes' equations at once - and
     * {@code CLOSED} is its well-posed representative: it pins the flow to exactly zero through the
     * actuator row instead of leaving the solve to find zero by iteration, which is what keeps a
     * zero-holdup junction off its own upwind and
     * junction inflow-floor discontinuities of the former zero-holdup junction rows.
     */
    private static double shutoffBand(double pressureScale,double tolerance) {
        return Math.max(.01,tolerance*Math.max(1e5,pressureScale));
    }
    private static boolean canClamp(FlowControl.Mode mode) {
        return mode==FlowControl.Mode.PASSIVE||mode==FlowControl.Mode.PUMP_HEAD_LIMIT||mode==FlowControl.Mode.VALVE_OPEN;
    }
    /** Outer active-set stability check; never runs inside a Newton residual or Jacobian evaluation. */
    private List<FluidThermodynamics.State> phaseCorrection(PassiveNetwork graph,List<FluidThermodynamics.State> states,Runnable checkpoint,boolean converged) {
        return phaseCorrection(graph,states,checkpoint,converged,null);
    }
    /** {@code prepared}, when given, is the pass's per-node prepared temperature state: a node's flash at exactly that
     * temperature reuses its Peng-Robinson workspace instead of preparing a new one (the arithmetic is the same). */
    private List<FluidThermodynamics.State> phaseCorrection(PassiveNetwork graph,List<FluidThermodynamics.State> states,Runnable checkpoint,boolean converged,FluidThermodynamics.Prepared[] prepared) {
        var corrected=new ArrayList<FluidThermodynamics.State>();boolean changed=false;
        for(int i=0;i<states.size();i++) {
            var state=states.get(i);if(graph.reservoirs().get(i).fixed()){corrected.add(state);continue;}
            // When all allowed phases are populated, the simultaneous fugacity/saturation rows
            // already enforce their equilibrium. An additional TP flash repeats the same work.
            // Missing-phase stability and unsuccessful Newton passes still require the outer check.
            boolean hydroComplete=state.liquidProperties()!=null&&state.vaporProperties()!=null||state.liquidProperties()==null&&state.vaporProperties()==null;
            boolean waterComplete=state.waterLiquid()>0&&state.waterVapor()>0||state.waterLiquid()+state.waterVapor()==0;
            if(converged&&hydroComplete&&waterComplete&&(state.vaporProperties()==null||state.vaporProperties().vaporBranch())){corrected.add(state);continue;}
            FluidThermodynamics.State equilibrium;
            try{equilibrium=model.flashTP(state.temperature(),state.pressure(),PhaseLayout.totalAmounts(state),checkpoint,prepared==null?null:prepared[i]).withSolidState(state.solids(),state.solidMoments());}
            catch(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation violation){throw violation.at(graph.reservoirs().get(i).id());}
            if(phaseCode(state)!=phaseCode(equilibrium)&&!changed){changed=true;corrected.add(equilibrium);}else corrected.add(state);
        }
        return changed?corrected:null;
    }
    /** Which phases this state carries, as four flags: hydrocarbon liquid, hydrocarbon vapor, free
     * water and steam. Only ever compared for equality, so it needs no rendering. */
    private static int phaseCode(FluidThermodynamics.State state) {
        return (state.solidMoments().mass()>0?16:0)|(state.liquidVolume()>0?1:0)|(state.vaporProperties()!=null?2:0)
                |(state.waterLiquid()>0?4:0)|(state.waterVapor()>0?8:0);
    }
    /**
     * Closes, before the first pass, every passive connection whose own starting point leaves it no
     * direction it is allowed to carry.
     *
     * <p>The hydraulic row of a connection is {@code driving - loss(q)}, and {@code loss} is zero at
     * zero flow and strictly increasing away from it, so a root with {@code q > 0} exists only when
     * the driving pressure a forward flow stands in is positive, and a root with {@code q < 0} only
     * when the driving pressure a reverse flow stands in is negative. A connection one of whose ends
     * forbids a direction - a generator, a void, a directionally blocked pipe - therefore has no
     * admissible root at all when the only sign with a root is the forbidden one, and its one
     * admissible answer is a flow of exactly zero. That is the same answer
     * {@code boundaryClosed} already produces, and the pass loop already reaches it the moment a
     * converged point shows a direction the boundary refuses. The whole defect is that on a
     * dead-headed line the pass cannot converge to that point: the reverse root lies across a flow
     * of zero, where the friction slope changes by three orders between a liquid and a gas, and the
     * step limiter has its own reasons to refuse the journey. So the closure is taken from the
     * starting point instead, which says the same thing without asking the Newton to travel there -
     * the pre-interval rate pass of {@link SolidEventIntegrator} closes a transport failure the same
     * way rather than letting the interval discover it.
     *
     * <p>Three conditions keep this a statement that cannot be wrong for the solve it is made in.
     *
     * <ul>
     * <li><b>Both columns are asked.</b> A forward flow stands in the fluid of {@link
     * PassiveNetwork.Pipe#first} and a reverse flow in that of {@link PassiveNetwork.Pipe#second},
     * so each direction is tested against the static head of its own column - the same pairing
     * {@link Equations#headDensities} states a connection's head on. A direction is refused only
     * when the column that would fill it does not drive it.
     * <li><b>Neither end of the run is a zero-holdup junction.</b> A junction owns no volume, so
     * the pressure it carries into a pass is an output of the previous solve rather than a property
     * of any stock, and it is free to move as far as the hydraulics need within this one. A
     * vessel's and a boundary's are not: a boundary holds its pressure fixed by construction, and a
     * vessel's pressure moves only with what it receives, monotonically against the flow that
     * delivers it. So across a run between a boundary and a vessel a driving pressure that refuses
     * a direction at the starting point refuses it everywhere the solve can go - delivering in the
     * allowed direction only pushes it further away - while a junction's pressure says nothing. The
     * run is the one {@link #initialMassFlows} already contracts: a maximal chain of passive
     * connections through degree-two junctions, which carry the one flow the chain passes and own
     * no pressure of their own, so a static column across the chain is the column across its two
     * ends and nothing in between enters it. A run of one connection is the ordinary case.
     * <li><b>Only passive connections.</b> An actuator decides its own mode, and the device-first
     * rule of the pass loop exists precisely so that a boundary direction is never closed on
     * evidence that is really a device's symptom. A pre-pass closure of an actuator's connection
     * would take that decision before the device had spoken at all. A run through a junction an
     * actuator touches is left alone for the same reason.
     * </ul>
     *
     * <p>It cannot cycle. The closure is monotone within a solve, it is taken once before any pass,
     * it is read by the existing pass loop exactly as a closure taken by that loop would be, and
     * it is never cleared - so it adds no transition to the active-set sequence and cannot lengthen
     * it. It is per solve, so a line whose generator is raised afterwards is decided again from the
     * new starting point and opens.
     */
    private static void closeDeadHeads(PassiveNetwork graph,boolean[] boundaryClosed,boolean[] keep) {
        int edges=graph.pipes().size(),nodes=graph.reservoirs().size();
        int[] degree=new int[nodes];boolean[] actuated=new boolean[nodes];
        for(var pipe:graph.pipes()) {
            degree[pipe.first()]++;degree[pipe.second()]++;
            if(!(pipe.control() instanceof FlowControl.Passive)){actuated[pipe.first()]=true;actuated[pipe.second()]=true;}
        }
        boolean[] interior=new boolean[nodes];
        for(int i=0;i<nodes;i++)interior[i]=graph.reservoirs().get(i).junction()&&degree[i]==2&&!actuated[i];
        int[][] nodeEdges=new int[nodes][];
        for(int i=0;i<nodes;i++)nodeEdges[i]=new int[degree[i]];
        Arrays.fill(degree,0);
        for(int edge=0;edge<edges;edge++) {
            var pipe=graph.pipes().get(edge);
            nodeEdges[pipe.first()][degree[pipe.first()]++]=edge;nodeEdges[pipe.second()][degree[pipe.second()]++]=edge;
        }
        boolean[] taken=new boolean[edges];
        for(int edge=0;edge<edges;edge++) {
            if(taken[edge]||boundaryClosed[edge]||!(graph.pipes().get(edge).control() instanceof FlowControl.Passive))continue;
            var pipe=graph.pipes().get(edge);
            // The maximal passive run through degree-two junctions containing this connection, as
            // a node order and the links between consecutive nodes; see {@link #initialMassFlows}.
            var chain=new ArrayList<Integer>();var order=new ArrayList<Integer>();
            order.add(pipe.first());chain.add(edge);order.add(pipe.second());taken[edge]=true;
            for(int end=0;end<2;end++) {
                int at=end==0?pipe.first():pipe.second(),from=edge;
                while(interior[at]) {
                    int next=nodeEdges[at][0]==from?nodeEdges[at][1]:nodeEdges[at][0];
                    if(taken[next]||!(graph.pipes().get(next).control() instanceof FlowControl.Passive))break;
                    taken[next]=true;var step=graph.pipes().get(next);
                    int beyond=step.first()==at?step.second():step.first();
                    if(end==0){chain.addFirst(next);order.addFirst(beyond);}else{chain.add(next);order.add(beyond);}
                    at=beyond;from=next;
                }
            }
            int start=order.getFirst(),finish=order.getLast();
            if(start==finish||graph.reservoirs().get(start).junction()||graph.reservoirs().get(finish).junction())continue;
            // Which way the run as a whole may carry: every link must allow its own share of it.
            boolean forwardAllowed=true,reverseAllowed=true;
            for(int position=0;position<chain.size();position++) {
                var link=graph.pipes().get(chain.get(position));boolean aligned=link.first()==order.get(position);
                forwardAllowed&=boundaryAllowed(graph,link,aligned?1:-1);
                reverseAllowed&=boundaryAllowed(graph,link,aligned?-1:1);
            }
            if(forwardAllowed&&reverseAllowed)continue;
            var a=graph.reservoirs().get(start);var b=graph.reservoirs().get(finish);
            double dz=b.elevation()-a.elevation(),difference=a.state().pressure()-b.state().pressure();
            boolean forward=forwardAllowed&&difference-a.state().mass()/a.state().volume()*GRAVITY*dz>0;
            boolean reverse=reverseAllowed&&difference-b.state().mass()/b.state().volume()*GRAVITY*dz<0;
            if(keep!=null){boolean kept=false;for(int link:chain)kept|=keep[link];if(kept)continue;}
            if(!forward&&!reverse)for(int link:chain)boundaryClosed[link]=true;
        }
    }
    /**
     * The {@code illegalDirection} closure of the pass loop, taken once at the start of the solve on the point pass 0
     * starts from.
     *
     * <p>A passive connection whose start-point flow runs in a direction its own boundary forbids
     * ({@link #boundaryAllowed}) is closed for this solve exactly as that rule closes it after a converged pass.
     * The direction is the one {@link #startPoint} gives the connection; where the start point gives it none
     * ({@code |q| <= 1e-10}, the rule's own threshold, which is every connection the previous accepted point had
     * closed, since a closed connection records exactly zero), it is the direction of {@link #initialMassFlow} on
     * the current states. Nothing is carried from the previous solve: the closure is derived afresh from this
     * solve's start point, so a connection whose pressures turn legal opens again at the next solve.
     *
     * <p>Passive connections only. The comment above the {@code illegalDirection} rule decides devices first: a
     * passive edge showing an illegal direction next to a device that cannot hold its mode is that device's
     * symptom, and closing the symptom first pins the wrong active set. Before any pass no device has been
     * decided, so a device connection is left to the pass loop.
     *
     * <p>Why: an island coming to rest next to a void or a generator leaves the one-way connections to it with flows that
     * point the forbidden way at the start of the next solve; the pass loop would close them only after a pass that
     * converged, and at rest that pass is the zero-flow stall of review 7.8. Only once this solver has accepted a solve on
     * the same structure (pipe identities and node ids): a first solve's junction pressures are the compiler's property
     * guess, not a state. And only where the static driving pressure forbids the direction with either end's density
     * ({@link #illegalWithEitherDensity}). {@code keep} names the connections the interval solver reopened.
     */
    private void closeIllegalStarts(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,boolean[] keep) {
        if(!(acceptedPipes.equals(identities(graph))&&Arrays.equals(acceptedNodeIds,nodeIds(graph))))return;
        int edges=graph.pipes().size();
        double[] start=new double[edges];
        startPoint(graph,modes,boundaryClosed,identities(graph),null,start,new double[edges],new boolean[edges]);
        double[] estimate=null;
        for(int i=0;i<edges;i++) {
            var pipe=graph.pipes().get(i);
            if(boundaryClosed[i]||!(pipe.control() instanceof FlowControl.Passive))continue;
            if(keep!=null&&keep[i])continue;
            double q=start[i];
            if(Math.abs(q)<=1e-10){if(estimate==null)estimate=initialMassFlows(graph);q=estimate[i];}
            if(Math.abs(q)>1e-10&&!boundaryAllowed(graph,pipe,q)) {
                if(illegalWithEitherDensity(graph,pipe,q))boundaryClosed[i]=true;
            }
        }
    }
    /**
     * Whether the static driving pressure {@code P_a - P_b - rho g (z_b - z_a)} has the sign of {@code flow} with rho = either endpoint's density.
     * {@link #initialMassFlow}, which supplies the direction of a connection the previous point had closed, states
     * the head on the endpoint with the higher pressure. On a falling water line into a nitrogen tank that is the
     * tank's gas once the tank passes the generator's pressure, so the estimate reads a 4 m water column as 4 m of
     * nitrogen and reports a 4.8 kg/s backflow into the generator while the column still drives 4.2 kPa forward
     * (ElevatedBlockLineIslandTest, run 76a); the connection then stays closed for good, because a closed connection
     * records zero and the next start reads the same estimate. Between two gas endpoints (the void edges of review
     * 7.8) the two densities give the same sign and the rule fires as before.
     */
    private static boolean illegalWithEitherDensity(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        double dp=a.state().pressure()-b.state().pressure(),dz=b.elevation()-a.elevation();
        for(var end:List.of(a,b)){double driving=dp-end.state().mass()/end.state().volume()*GRAVITY*dz;if(!(Math.signum(driving)==Math.signum(flow)))return false;}
        return true;
    }
    private static boolean boundaryAllowed(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        var a=graph.reservoirs().get(pipe.first()).kind();var b=graph.reservoirs().get(pipe.second()).kind();
        return !pipe.blocked(flow)&&!(flow<0&&(a==PassiveNetwork.NodeKind.GENERATOR||b==PassiveNetwork.NodeKind.VOID)
                ||flow>0&&(b==PassiveNetwork.NodeKind.GENERATOR||a==PassiveNetwork.NodeKind.VOID));
    }
    void checkConservation(PassiveNetwork graph,ConservativeTransport.Projection projection) {
        int count=model.hydrocarbon.componentCount()+1;double[] before=new double[count],after=new double[count],turnover=new double[count];double eb=0,ea=0,energyScale=0;
        for(var boundary:projection.boundaries()){var n=boundary.moles();for(int c=0;c<count;c++)turnover[c]+=Math.abs(n[c]);energyScale+=Math.abs(boundary.totalEnergyJoule());}
        for(int i=0;i<graph.reservoirs().size();i++) {
            var reservoir=graph.reservoirs().get(i);var old=reservoir.inventory();var next=projection.inventories().get(i);
            // A junction's inventory is owned stock (energy field in enthalpy form, m_J*h), booked exactly like a vessel's.
            if(reservoir.fixed())continue;
            var a=old.moles();var b=next.moles();double ma=old.solids().massKg(),mb=next.solids().massKg();
            for(int j=0;j<count;j++){before[j]+=a[j];after[j]+=b[j];double mw=model.molecularWeight(j);ma+=a[j]*mw;mb+=b[j]*mw;}
            eb+=old.internalEnergy()+ma*GRAVITY*reservoir.elevation();ea+=next.internalEnergy()+mb*GRAVITY*reservoir.elevation();
            energyScale+=Math.abs(old.internalEnergy())+Math.abs(ma*GRAVITY*reservoir.elevation());
        }
        var solidBalance=new TreeMap<SolidInventory.Key,double[]>();
        for(int i=0;i<graph.reservoirs().size();i++)if(!graph.reservoirs().get(i).fixed()){
            for(var p:graph.reservoirs().get(i).inventory().solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[3])[0]+=p.massKg();
            for(var p:projection.inventories().get(i).solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[3])[1]+=p.massKg();
        }
        for(var pipe:graph.pipes())if(pipe.filter()!=null){
            var old=pipe.filter();var next=projection.filters().getOrDefault(pipe.id(),old);eb+=old.energyJoule();ea+=next.energyJoule();energyScale+=Math.abs(old.energyJoule())+Math.abs(next.energyJoule());
            for(var p:old.captured().populations())solidBalance.computeIfAbsent(p.key(),k->new double[3])[0]+=p.massKg();
            for(var p:next.captured().populations())solidBalance.computeIfAbsent(p.key(),k->new double[3])[1]+=p.massKg();
        }
        for(var boundary:projection.boundaries())for(var p:boundary.solids().populations())solidBalance.computeIfAbsent(p.key(),k->new double[3])[2]+=boundary.solidDirection()*p.massKg();
        for(var b:solidBalance.values())if(Math.abs(b[0]+b[2]-b[1])>1e-10+1e-8*Math.max(Math.max(b[0],b[1]),Math.abs(b[2])))throw new SparseNewton.Nonconvergence("Solid population balance failed");
        var external=projection.externalMoles();
        for(int i=0;i<count;i++)if(Math.abs(before[i]+external[i]-after[i])>1e-10+1e-8*Math.max(turnover[i],Math.max(before[i],after[i])))throw new SparseNewton.Nonconvergence("Component balance failed: "+i);
        if(Math.abs(ea-eb-projection.pumpWork()-projection.externalEnergy())>1e-4+1e-6*(energyScale+Math.abs(projection.pumpWork())))throw new SparseNewton.Nonconvergence("Total energy balance failed");
    }
    private double carrierViscosity(FluidThermodynamics.State s) {
        double value=0,volume=s.liquidVolume()+s.waterVolume()+s.vaporVolume();
        if(s.liquidVolume()>0)value+=s.liquidVolume()*model.viscosity.liquid(s.temperature(),s.liquidView()).pascalSeconds();
        if(s.waterVolume()>0)value+=s.waterVolume()*model.viscosity.waterLiquid(s.temperature());
        if(s.vaporVolume()>0)value+=s.vaporVolume()*model.viscosity.vapor(s.temperature(),s.vaporView(),s.waterVapor());
        return value/volume;
    }
    static boolean hasSolids(PassiveNetwork graph) {
        return graph.reservoirs().stream().anyMatch(n->n.state().solidMoments().mass()>0||!n.inventory().solids().empty())
                ||graph.scheduledTransfers().stream().anyMatch(t->t instanceof ScheduledTransfer.Injection in&&!in.solidsPerSecond().empty());
    }
    private double viscosity(FluidThermodynamics.State s) {
        return viscosity(s,null);
    }
    /** {@code prepared} must be this state's own temperature bundle, or {@code null} to evaluate
     * the pure-component terms here as the standalone paths do. */
    private double viscosity(FluidThermodynamics.State s,FluidThermodynamics.Prepared prepared) {
        var terms=prepared==null?null:prepared.viscosities();
        double value=0;
        if(s.liquidVolume()>0)value+=s.liquidVolume()*model.viscosity.liquid(s.temperature(),s.liquidView(),terms).pascalSeconds();
        if(s.waterVolume()>0)value+=s.waterVolume()*model.viscosity.waterLiquid(s.temperature(),terms);
        double liquidVolume=s.liquidVolume()+s.waterVolume(),solidVolume=s.solidMoments().volume();
        if(liquidVolume>0&&solidVolume>0){double phi=solidVolume/(liquidVolume+solidVolume);value=SlurryTransport.effectiveViscosity(value/liquidVolume,Math.min(phi,0.62-1e-9))*(liquidVolume+solidVolume);}
        if(s.vaporVolume()>0)value+=s.vaporVolume()*model.viscosity.vapor(s.temperature(),s.vaporView(),s.waterVapor(),viscosities,terms);
        return value/(liquidVolume==0?s.vaporVolume():s.volume());
    }
    /** The equations of the last accepted solve, for the check that the block Jacobian sweep and an
     * independent column-by-column difference of {@link Equations#residual} produce the same entries;
     * {@code null} until a solve has been accepted. Nothing in production reads it. */
    Equations acceptedEquations(){return lastSolve==null?null:lastSolve.equations();}
    /** The point {@link #acceptedEquations} converged to. */
    double[] acceptedPoint(){return lastSolve==null?null:lastSolve.variables().clone();}
    /** The rows a block Jacobian sweep has to re-evaluate for one perturbed node or edge column. */
    final class Equations implements SparseNewton.Equations {
        final double[][] solidTargets,solidIncoming;
        final int[] filterOffsets;
        /** The connections as the warm-flow check may compare them: never including the cake. */
        final List<PassiveNetwork.Pipe.Identity> pipeIdentities;
        final PassiveNetwork graph;final double dt;final PhaseLayout[] layout;final int[] offsets;
        final int edgeOffset,size;final double[][] oldAmounts;int[][] sparsity;final List<FlowControl.Mode> modes;final int[] controlOffsets;final boolean[] boundaryClosed;final List<FluidThermodynamics.State> seeds;
        final double[][] cachedVariables;final FluidThermodynamics.State[] cachedStates;final Transport[] cachedTransport;
        final Transport[][] capSources;final double[][] capMassFlows,capPressureDrops;
        final FluidThermodynamics.Prepared[] cachedPrepared;
        /**
         * Whether a device in this island prescribes its own mass flow this pass. A saturated
         * filter inlet and a pump holding a target volume flow are two equations for the same edge
         * once continuity has related them, so the filter capacity is not imposed as a law while
         * one is active; those islands meet the capacity by step refusal instead, exactly as every
         * island did before the law existed. A pump on its head limit prescribes a pressure rather
         * than a flow and leaves the edge free, so it does not count.
         */
        final boolean prescribedFlow;
        /** Whether this island carries the three aggregate solid moment unknowns on every node;
         * the whole-island predicate {@link PhaseLayout} is built with, evaluated once. */
        final boolean solidSupport;
        final boolean[] amountVariables,solidVariables;
        final double[] differenceFloors;
        /** Below this value an amount unknown's nonnegativity is not a live constraint on the step
         * length: a solid moment at or below it is dust (see {@link #SOLID_CLAMP_FRACTION}), and a
         * fluid amount at or below it is a trace that {@link #project} keeps positive instead (see
         * {@link #TRACE_CLAMP_FRACTION}). */
        final double[] clampFloors;
        /** Edge indices incident to each node, ascending - which is the order the whole-island
         * residual accumulates that node's targets, energy and junction inflows in, so a per-node
         * re-assembly reproduces every accumulator bit for bit. */
        final int[][] nodeEdges;
        /** The pressure a junction no open connection reaches keeps, or 0 for every other node.
         * Depends on the pass's active set, so it is built with the equations and not per
         * evaluation; see {@link PhaseLayout#junctionRows}. */
        final double[] retainedPressures;
        /**
         * The pressure each edge's pressure-dimensioned rows are stated in, so that the single
         * Newton tolerance means the same <em>relative</em> closure on every island.
         *
         * <p>Those rows - the hydraulic balance, a pump's head limit, a valve's regulated inlet
         * pressure and an open valve's zero head - used to divide by a literal 1e5 Pa. That is a
         * fixed unit, not a scale: at a tolerance of 1e-9 it demands 1e-4 Pa of closure whatever
         * the island runs at, which is a relative 1e-9 at atmospheric pressure and a relative
         * 2.5e-10 at 400 kPa. The second is below what the endpoint pressures themselves can be
         * resolved to once they are reconstructed and re-encoded through the logarithmic pressure
         * unknown, so a driven line settles a fraction of a milli-pascal apart and the island
         * stalls there forever - and the more resistive the edge, the larger the imbalance its own
         * row shows, which is why an inline filter (clean resistance ~150x a plain pipe's) is what
         * this stops first.
         *
         * <p>The scale is {@code max(1e5, max(P_first, P_second))} from the endpoint states of
         * <em>this pass</em>, so:
         *
         * <ul>
         * <li>it is constant through the whole Newton solve, which keeps it out of the Jacobian
         *     entirely: no derivative of the scale, no new sparsity, and the block sweep and the
         *     coloured whole-island sweep still evaluate the identical row;</li>
         * <li>the 1e5 Pa floor leaves every island at or below atmospheric pressure bit-identical
         *     to what it was, so only islands that actually run above 1 bar move at all;</li>
         * <li>it is per edge rather than per island, because an island may span a 4 bar header and
         *     an atmospheric vent and only the rows that sit at high pressure should be loosened. A
         *     single island-wide maximum would relax an atmospheric branch by the header's factor
         *     for no reason.</li>
         * </ul>
         *
         * <p>Taking the live iterate's pressures instead was rejected: the scale would then be a
         * function of the unknowns, so its derivative would enter every one of those rows, the
         * {@code max} would put a kink in them, and the rows would stop being a fixed positive
         * multiple of the physical equation - all of it to track a pressure that a converging
         * Newton is already driving to the pass's own endpoint pressures.
         */
        final double[] pressureScales;
        /** This pass's starting point, built once: {@link #junctionDonorFirst} is read off it. */
        final double[] initialPoint;
        /**
         * Which end of each connection donates into a zero-holdup junction for the whole of this
         * pass, taken from the point the pass starts at rather than from the live iterate.
         *
         * <p>A junction's mass fractions and its specific enthalpy are the mixture of what arrives,
         * a ratio that does not shrink with the flows that form it. Deciding the donor inside the
         * residual therefore put a finite jump in those rows at exactly zero flow, and the
         * one-sided differences the Jacobian is built from only ever sample the positive side of
         * it: on a settled island the Newton direction points the other way, so the derivative the
         * step was computed from is not the derivative along the step. Measured on the valve block
         * line at 400 kPa, the linear model predicted a residual of 8e-25 and the full step
         * produced 1.7e-3, twenty orders apart, with every one of the twenty-four backtracks
         * landing on the same plateau because the jump is a step and not a slope. That is the
         * whole of why a tank behind a filter or a valve held while the same tank on plain pipes
         * runs indefinitely: a plain pipe carries its donor's properties only inside terms already
         * multiplied by the flow, so its own upwind switch is continuous and its jump is zero.
         *
         * <p>Freezing it makes one pass's residual smooth in its own unknowns, which is the rule
         * every other switch in this solver already follows - device modes, phase regimes, trace
         * support and boundary closure are all decided once per pass and revised by the outer
         * loop. The outer loop does the same here: a converged point whose flow disagrees with the
         * donor it was solved under is one more active-set change, and the donor signs join the
         * cycle key so the pass sequence stays finite. Only the junction mixture reads them; the
         * reservoir targets, the net-flow balance and every edge row keep the live upwind, so an
         * accepted point is upwinded exactly as {@link ConservativeTransport} reconstructs it.
         */
        final boolean[] junctionDonorFirst;
        /**
         * The density each connection's static head {@code rho*g*dz} is stated with for the whole
         * of this pass, read off the point the pass starts at rather than from the live iterate.
         *
         * <p>It is {@link #junctionDonorFirst}'s twin on the hydraulic row, and the reason is the
         * same. {@code edgeRows} used to take the head's density from the upwind end of the
         * <em>current</em> flow, so a connection whose two ends hold fluids of different density
         * had a finite jump of {@code (rho_first - rho_second)*g*dz} in its own row at exactly
         * zero flow - and the head does not shrink with the flow, so that jump is degree zero,
         * exactly like a zero-holdup junction's mixture. The one-sided differences the Jacobian is
         * built from sample only the branch the iterate is already on, so the line search is
         * handed a direction whose derivative does not hold across the switch, and a settled line
         * lands on a plateau it cannot leave. Every fixture in the tree used to be flat, where
         * {@code dz} is zero and the term vanished; measured on a water generator four blocks
         * below a nitrogen-charged tank at 400 kPa, the row jumped by 2.754e-2 - the predicted
         * {@code (996.31-715.46)*9.80665*4/400000} to every digit - the linear model predicted
         * 1.08e-19, the full step produced 2.76e-2, and all twenty-four backtracks landed on the
         * same 2.716e-2 plateau. See documentation/ELEVATED_LINE_PROBE.md.
         *
         * <p>Freezing it is also the physically honest reading. A connection owns no holdup, so
         * the fluid whose weight the head is has to be inferred, and what a vertical pipe from a
         * water generator into a gas space actually contains is the water that was pushed into it
         * - not whichever end momentarily reads as upstream of a 1e-8 kg/s flow. Stating it once
         * per pass makes the column a property of the connection for that pass, the row a smooth
         * function of the pass's own unknowns, and the rest point {@code P_b = P_a - rho*g*dz}
         * an actual root instead of a value the residual steps over.
         *
         * <p>Which column it is, is stated below from the pass's own seed pressures and elevations
         * - never from the start point's flow sign, which at a settled line is a numerical zero and
         * means nothing, and never from a converged flow either. It is deliberately <em>not</em>
         * revised by the outer active-set loop, which is the one place it differs from the junction
         * donor, and the difference is measured rather than argued: the two columns of a line near
         * its own hydrostatic balance each put the flow on the other one - the heavier column
         * leaves the tank marginally over-filled, so the flow is negative, and the lighter one then
         * leaves it marginally under-filled, so it is positive - so a rule that turned the head
         * over on a converged disagreement would alternate forever, and if the column joined the
         * cycle key it would alternate for exactly two passes and then throw. The pass keeps the
         * column it solved under; the flow that disagrees with it is small by construction, and the
         * connection's own boundary closure or the next step's seeds take it from there. The
         * friction term keeps the live donor: {@code pressureDrop} is odd in the flow and zero at
         * zero, so its upwind switch is continuous and carries no jump to freeze.
         *
         * <p>It is also deliberately not part of {@link WorkspaceKey}, unlike the junction donors,
         * and that too was measured rather than argued. Adding it - as a real boolean per connected
         * elevation and a constant for every flat one, so that a flat island's workspace identity
         * could not move - kept the pass sequence honest in principle but cost the vertical line
         * standing at its own hydrostatic balance its whole run: from forty intervals settled at
         * 360918.3068 Pa to a hold on {@code Newton iteration limit at residual 2.202e-7}, because
         * the extra key component churns the workspace cache and the modified Newton loses the
         * preconditioner it was reusing. It belongs where {@link #pressureScales} belongs: a
         * quantity each pass reads off its own seeds, revised only by the seeds moving.
         */
        final double[] headDensities;
        /** Residual scratch. Every one of these is written before it is read within a single
         * evaluation and never escapes it, and the evaluations of one solve are sequential on the
         * worker holding the latch, so they are filled again rather than allocated again. The
         * returned residual is not among them: {@link SparseNewton} holds the current and the
         * candidate residual at the same time. */
        final double[][] targets,incoming;
        final double[] energy,incomingMass,incomingEnergy,netMass,fractions;
        /** The weight of the mixing and enthalpy rows the last {@link #junctionInflow} call set: they are stated in
         * amount form over the junction's own mass; see there. */
        double junctionWeight=1;
        Equations(PassiveNetwork graph,double dt,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> seeds,boolean[][] reachable,
                  PhaseSupport[][] supports) {
            this.modes=List.copyOf(modes);
            prescribedFlow=this.modes.contains(FlowControl.Mode.PUMP_TARGET);
            this.seeds=List.copyOf(seeds);
            this.boundaryClosed=boundaryClosed.clone();
            this.graph=graph;this.dt=dt;pipeIdentities=identities(graph);solidSupport=hasSolids(graph);int count=graph.reservoirs().size();layout=new PhaseLayout[count];offsets=new int[count];oldAmounts=new double[count][];
            capSources=new Transport[graph.pipes().size()][2];capMassFlows=new double[graph.pipes().size()][2];capPressureDrops=new double[graph.pipes().size()][2];
            cachedVariables=new double[count][];cachedStates=new FluidThermodynamics.State[count];cachedTransport=new Transport[count];
            cachedPrepared=new FluidThermodynamics.Prepared[count];
            int cursor=0;
            for(int i=0;i<count;i++) {
                var state=graph.reservoirs().get(i).state();offsets[i]=cursor;oldAmounts[i]=graph.reservoirs().get(i).inventory().moles();
                if(!graph.reservoirs().get(i).fixed()) {
                    layout[i]=new PhaseLayout(model,seeds.get(i),Arrays.copyOf(reachable[i],model.hydrocarbon.componentCount()),oldAmounts[i],supports[i],solidSupport);cursor+=layout[i].size();
                    if(SolverDiagnostics.ENABLED) {
                        int omitted=layout[i].singlePhaseComponentCount();
                        if(omitted>0){SolverDiagnostics.traceOmittedUnknowns.add(omitted);SolverDiagnostics.truncatedNodePasses.increment();}
                    }
                }
            }
            edgeOffset=cursor;cursor+=graph.pipes().size();controlOffsets=new int[graph.pipes().size()];Arrays.fill(controlOffsets,-1);
            for(int i=0;i<controlOffsets.length;i++)if(!(graph.pipes().get(i).control() instanceof FlowControl.Passive))controlOffsets[i]=cursor++;
            filterOffsets=new int[graph.pipes().size()];Arrays.fill(filterOffsets,-1);for(int i=0;i<filterOffsets.length;i++)if(graph.pipes().get(i).filter()!=null)filterOffsets[i]=cursor++;
            size=cursor;
            amountVariables=new boolean[size];solidVariables=new boolean[size];
            differenceFloors=new double[size];clampFloors=new double[size];Arrays.fill(differenceFloors,1);
            for(int node=0;node<count;node++)if(layout[node]!=null)for(int local=0;local<layout[node].size();local++) {
                int column=offsets[node]+local;
                amountVariables[column]=layout[node].totalAmountVariable(local);differenceFloors[column]=layout[node].differenceScale(local,0);
                solidVariables[column]=layout[node].solidVariable(local);
                if(solidVariables[column])clampFloors[column]=SOLID_CLAMP_FRACTION*differenceFloors[column];
                else if(amountVariables[column])clampFloors[column]=TRACE_CLAMP_FRACTION*differenceFloors[column];
            }
            int components=oldAmounts[0].length;
            targets=new double[count][components];incoming=new double[count][components];fractions=new double[components];
            solidTargets=new double[count][3];solidIncoming=new double[count][3];
            energy=new double[count];incomingMass=new double[count];incomingEnergy=new double[count];netMass=new double[count];
            for(int node=0;node<count;node++)if(layout[node]!=null)cachedVariables[node]=new double[layout[node].size()];
            int[] degree=new int[count];
            for(var pipe:graph.pipes()){degree[pipe.first()]++;degree[pipe.second()]++;}
            nodeEdges=new int[count][];for(int node=0;node<count;node++)nodeEdges[node]=new int[degree[node]];
            Arrays.fill(degree,0);
            for(int edge=0;edge<graph.pipes().size();edge++) {
                var pipe=graph.pipes().get(edge);
                nodeEdges[pipe.first()][degree[pipe.first()]++]=edge;nodeEdges[pipe.second()][degree[pipe.second()]++]=edge;
            }
            pressureScales=new double[graph.pipes().size()];
            for(int edge=0;edge<pressureScales.length;edge++) {
                var pipe=graph.pipes().get(edge);
                pressureScales[edge]=Math.max(1e5,Math.max(seeds.get(pipe.first()).pressure(),seeds.get(pipe.second()).pressure()));
            }
            retainedPressures=new double[count];
            for(int node=0;node<count;node++) {
                if(layout[node]==null||!graph.reservoirs().get(node).junction())continue;
                boolean open=false;
                for(int edge:nodeEdges[node])open|=!this.boundaryClosed[edge]&&this.modes.get(edge)!=FlowControl.Mode.CLOSED;
                if(!open)retainedPressures[node]=graph.reservoirs().get(node).state().pressure();
            }
            initialPoint=buildInitial();
            junctionDonorFirst=new boolean[graph.pipes().size()];
            for(int edge=0;edge<junctionDonorFirst.length;edge++) {
                var pipe=graph.pipes().get(edge);
                // A connection with no junction at either end donates into no mixture, so it has no
                // frozen donor to state and none to key a workspace on. Leaving it constant is what
                // keeps an island without a single zero-holdup node bit for bit what it was.
                junctionDonorFirst[edge]=!graph.reservoirs().get(pipe.first()).junction()&&!graph.reservoirs().get(pipe.second()).junction()
                        ||initialPoint[edgeOffset+edge]>=0;
            }
            headDensities=new double[graph.pipes().size()];
            for(int edge=0;edge<headDensities.length;edge++) {
                var pipe=graph.pipes().get(edge);
                var start=seeds.get(pipe.first());var end=seeds.get(pipe.second());
                double first=start.mass()/start.volume(),second=end.mass()/end.volume();
                double dz=graph.reservoirs().get(pipe.second()).elevation()-graph.reservoirs().get(pipe.first()).elevation();
                // The same pressure difference the row drives on, actuator included: a pump's own
                // head is the larger part of what pushes its connection, so a column decided
                // without it is decided on the suction/discharge gap alone and always reads the
                // discharge as the donor.
                double actuator=controlOffsets[edge]<0?0:initialPoint[controlOffsets[edge]]*1e5;
                double difference=start.pressure()-end.pressure()
                        +(pipe.control() instanceof FlowControl.Pump?actuator:-actuator);
                // The column that is its own donor. A column states which end fills the connection,
                // so a column is admissible exactly when the driving pressure it produces points
                // away from the end it was read off: the first end's when that pressure is forward,
                // the second end's when it is backward. Where both columns agree on the direction
                // only one of them is admissible and there is nothing to decide - a line pouring
                // downhill stands in what feeds it from above, and a line the pressures cannot lift
                // stands in what would come back down it.
                //
                // Taking the start point's upwind end instead reads a numerical zero as a
                // direction, and both readings of that zero are wrong somewhere. A water generator
                // four blocks under a nitrogen tank at the same pressure sits at a flow of exactly
                // zero; read as the water column it asks for 39 kPa the generator has not got, so
                // the pass is sent looking for a reverse flow the whole way across zero and stalls
                // on the friction kink there, where the slope changes by three orders between the
                // two fluids. Read the other way, a liquid line resting on its own two-metre column
                // whose ends differ only by water's compressibility takes the lighter end's
                // density, and the 0.18 Pa that leaves drives a permanent 8.3e-7 kg/s through a
                // graph that has to be at rest.
                //
                // Two cases are left, and they are opposites rather than one band.
                //
                // Neither column admissible - the driving pressure is below the heavier head and
                // above the lighter one - is where a settled line actually lives, and it is the
                // same fact that forbids revising the head on a converged flow, since there each
                // column puts the flow on the other one. The column to state is then the one
                // nearest its own rest point, which makes rest a fixed point of the rule as well as
                // of the equations: a line standing at {@code P_a - P_b = rho*g*dz} has no driving
                // pressure at all under {@code rho} and the whole difference between the heads
                // under the other one, so it keeps the column it is balanced under.
                //
                // Both columns admissible is genuine bistability - the connection can stand full of
                // either fluid and be at rest in both - and there the start point's flow sign is
                // the right evidence and the only evidence, because which fluid is in the pipe is
                // exactly the history the model does not otherwise carry. Deciding it by rest point
                // instead stops a filling line at the lighter column's balance: the falling
                // four-block line at 400 kPa settled at 429851 Pa against the 439082 Pa its own
                // water column demands, because the tank's own mixture balances 9 kPa earlier and
                // the line met that point first.
                //
                // On an island whose devices all sit at one y both tests read the same pressure
                // difference and the column they pick multiplies a {@code dz} of zero, so a flat
                // island is bitwise what it was whichever end is named.
                double drivingFirst=difference-first*GRAVITY*dz,drivingSecond=difference-second*GRAVITY*dz;
                boolean fromFirst;
                if(drivingFirst>=0&&drivingSecond>=0)fromFirst=true;
                else if(drivingFirst<=0&&drivingSecond<=0)fromFirst=false;
                else if(drivingFirst<0)fromFirst=Math.abs(drivingFirst)<=Math.abs(drivingSecond);
                else fromFirst=initialPoint[edgeOffset+edge]>=0;
                headDensities[edge]=fromFirst?first:second;
            }
        }
        private void buildSparsity() {
            int count=layout.length;
            BitSet[] columns=new BitSet[size];for(int i=0;i<size;i++)columns[i]=new BitSet(size);
            for(int node=0;node<count;node++)for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++)columns[c].set(offsets[node],offsets[node]+nodeSize(node));
            for(int edge=0;edge<graph.pipes().size();edge++) {
                var pipe=graph.pipes().get(edge);int er=edgeOffset+edge;
                for(int node:new int[]{pipe.first(),pipe.second()}) {
                    columns[er].set(offsets[node],offsets[node]+nodeSize(node));
                    for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++) {
                        columns[c].set(er);
                        for(int receiver:new int[]{pipe.first(),pipe.second()})columns[c].set(offsets[receiver],offsets[receiver]+nodeSize(receiver));
                    }
                }
                columns[er].set(er);
                int filter=filterOffsets[edge];if(filter>=0){columns[filter].set(er);columns[filter].set(filter);columns[er].set(filter);for(int node:new int[]{pipe.first(),pipe.second()})for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++)columns[c].set(filter);}
                int control=controlOffsets[edge];
                if(control>=0) {
                    columns[control].set(er);columns[control].set(control);columns[er].set(control);
                    for(int node:new int[]{pipe.first(),pipe.second()}) {
                        columns[control].set(offsets[node],offsets[node]+nodeSize(node));
                        for(int c=offsets[node];c<offsets[node]+nodeSize(node);c++)columns[c].set(control);
                    }
                }
            }
            // Exact structural zeros: phase allocation/T/P cannot change bulk component flow at
            // fixed total amounts and kg/s. Suppress cancellation-noise derivatives, not small physics.
            BitSet materialRows=new BitSet(size);
            for(int node=0;node<count;node++)if(layout[node]!=null) {
                int components=layout[node].componentBalanceCount();
                materialRows.set(offsets[node],offsets[node]+components-(graph.reservoirs().get(node).junction()?1:0));
                if(graph.reservoirs().get(node).junction())materialRows.set(offsets[node]+components+1);
            }
            for(int node=0;node<count;node++)if(layout[node]!=null)for(int local=0;local<layout[node].size();local++)
                if(!layout[node].totalAmountVariable(local))columns[offsets[node]+local].andNot(materialRows);
            sparsity=new int[size][];for(int c=0;c<size;c++)sparsity[c]=columns[c].stream().toArray();
        }
        public int size(){return size;}
        public double differenceScale(int column,double value){return Math.max(differenceFloors[column],Math.abs(value));}
        public double maximumStep(double[] variables,double[] direction) {
            double alpha=1;
            // Approach a nonnegative amount boundary directly instead of repeatedly halving a step
            // that misses it by roundoff. This bounds a trial step; it never clips accepted inventory.
            // Below clampFloors the unknown is not a boundary this step has to respect: a solid
            // moment 1e-30 to 1e-70 below its own scale would otherwise bound alpha at 1e-12 to
            // 1e-18, at which x+alpha*d is bitwise x for every unknown of order one and no
            // backtrack can ever produce a different residual.
            for(int c=0;c<edgeOffset;c++)if(amountVariables[c]&&variables[c]>clampFloors[c]&&direction[c]<0)alpha=Math.min(alpha,.99*variables[c]/-direction[c]);
            return alpha;
        }
        /**
         * The other half of the trace rule in {@link #maximumStep}: a fluid amount at or below its
         * floor does not bound the step, so a trial that would take it below {@value #TRACE_RETENTION}
         * of its value keeps that fraction instead, which is the share the {@code .99} rule would have
         * left it while scaling every other unknown by the same tiny factor. The rest of the step is
         * taken as the Newton direction says; see {@link #TRACE_CLAMP_FRACTION}. Solid moments keep
         * their own projection in {@link PhaseLayout#decode}.
         */
        @Override public void project(double[] variables,double[] candidate) {
            for(int c=0;c<edgeOffset;c++)
                if(amountVariables[c]&&!solidVariables[c]&&variables[c]<=clampFloors[c]&&candidate[c]<TRACE_RETENTION*variables[c])
                    candidate[c]=TRACE_RETENTION*variables[c];
        }
        /** Solid unknowns this point leaves negative, i.e. where {@link PhaseLayout#decode}'s
         * nonnegativity projection would be active. Must be zero at every accepted point. */
        int negativeSolidUnknowns(double[] x) {
            int count=0;for(int c=0;c<edgeOffset;c++)if(solidVariables[c]&&x[c]<0)count++;
            return count;
        }
        int nodeSize(int node){return layout[node]==null?0:layout[node].size();}
        public int[][] columnRows(){if(sparsity==null)buildSparsity();return sparsity;}
        double[] initial(){return initialPoint.clone();}
        /** The connection flows this pass starts from: the edge columns of {@link #initialPoint},
         * which is the same array {@link #junctionDonorFirst} is read off. */
        double[] startFlows(){return Arrays.copyOfRange(initialPoint,edgeOffset,edgeOffset+graph.pipes().size());}
        private double[] buildInitial() {
            double[] x=new double[size];for(int i=0;i<layout.length;i++)if(layout[i]!=null){var encoded=layout[i].encode(seeds.get(i));System.arraycopy(encoded,0,x,offsets[i],encoded.length);}
            int edges=graph.pipes().size();
            double[] q=new double[edges],head=new double[edges];boolean[] headSet=new boolean[edges];
            startPoint(graph,modes,boundaryClosed,pipeIdentities,seeds,q,head,headSet);
            for(int i=0;i<edges;i++) {
                x[edgeOffset+i]=q[i];
                if(headSet[i]&&controlOffsets[i]>=0)x[controlOffsets[i]]=head[i];
            }
            for(int i=0;i<filterOffsets.length;i++)if(filterOffsets[i]>=0)x[filterOffsets[i]]=graph.pipes().get(i).filter().loading();
            return x;
        }
        List<FluidThermodynamics.State> states(double[] x) {
            decode(x);
            var states=new ArrayList<FluidThermodynamics.State>(layout.length);
            for(var state:cachedStates)states.add(state);
            return states;
        }
        /** Drops the per-node decode cache; the next decode rebuilds every node exactly. */
        void releaseDecodeCache(){Arrays.fill(cachedStates,null);Arrays.fill(cachedTransport,null);Arrays.fill(cachedPrepared,null);
            for(var sources:capSources)Arrays.fill(sources,null);}
        /** Brings the per-node decode cache up to {@code x}; the residual reads the arrays directly. */
        private void decode(double[] x) {
            for(int i=0;i<layout.length;i++) {
                boolean same=cachedStates[i]!=null&&(layout[i]==null||Arrays.mismatch(x,offsets[i],offsets[i]+layout[i].size(),cachedVariables[i],0,cachedVariables[i].length)<0);
                if(same)continue;
                if(layout[i]!=null) {
                    double temperature=layout[i].temperature(x,offsets[i]);
                    if(cachedPrepared[i]==null||cachedPrepared[i].temperature()!=temperature)cachedPrepared[i]=model.prepare(temperature);
                }
                FluidThermodynamics.State state;
                // A trial outside the property domain names the node it was refused at; see ThermoDomainViolation.
                try{state=layout[i]==null?graph.reservoirs().get(i).state():layout[i].decode(x,offsets[i],cachedPrepared[i]);}
                catch(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation violation){throw violation.at(graph.reservoirs().get(i).id());}
                var transport=new Transport(PhaseLayout.totalAmounts(state),state.mass()/state.volume(),viscosity(state,cachedPrepared[i]),state.enthalpy()/state.mass(),model.velocityLimit(state));
                cachedStates[i]=state;cachedTransport[i]=transport;
                if(layout[i]!=null)System.arraycopy(x,offsets[i],cachedVariables[i],0,layout[i].size());
            }
        }
        private record Transport(double[] moles,double density,double viscosity,double specificEnthalpy,double velocityLimit) {}
        public double[] residual(double[] x) {
            decode(x);double[] f=new double[size];
            assemble(x,cachedStates,cachedTransport,cachedPrepared,f);
            return f;
        }
        /**
         * One complete residual from already decoded node states. The three passes are the whole
         * island's version of the three assemblies a block Jacobian sweep performs for a single
         * perturbed column, and they are the only place each row's formula exists.
         */
        private void assemble(double[] x,FluidThermodynamics.State[] st,Transport[] tr,FluidThermodynamics.Prepared[] pr,double[] f) {
            for(int node=0;node<layout.length;node++)if(layout[node]!=null)nodeAccumulate(node,x,st,tr);
            for(int edge=0;edge<graph.pipes().size();edge++)edgeRows(edge,x,st,tr,f);
            for(int node=0;node<layout.length;node++)if(layout[node]!=null)nodeRows(node,x,st,pr,f);
        }
        /**
         * This node's accumulated backward-Euler targets: the inventory it started from, the
         * scheduled transfers that name it, and every incident edge in edge order - which is the
         * order the whole-island loop reached them in, so the sums are the same doubles. A node
         * without a layout owns nothing and is only ever a donor or receiver, so its accumulators
         * are never read.
         */
        private void nodeAccumulate(int node,double[] x,FluidThermodynamics.State[] st,Transport[] tr) {
            double[] target=targets[node],in=incoming[node];
            System.arraycopy(graph.reservoirs().get(node).inventory().solids().moments().values(),0,solidTargets[node],0,3);Arrays.fill(solidIncoming[node],0);
            System.arraycopy(oldAmounts[node],0,target,0,target.length);Arrays.fill(in,0);
            double stored=graph.reservoirs().get(node).inventory().internalEnergy(),mass=0,heat=0,net=0;
            double elevation=graph.reservoirs().get(node).elevation();
            for(var transfer:graph.scheduledTransfers()) {
                if(transfer.node()!=node)continue;
                var state=st[node];
                if(transfer instanceof ScheduledTransfer.Withdrawal withdrawal) {
                    double withdrawn=dt*withdrawal.massKgPerSecond();var n=tr[node].moles;
                    for(int c=0;c<n.length;c++)target[c]-=withdrawn*n[c]/state.mass();
                    stored-=withdrawn*state.enthalpy()/state.mass();
                    var moments=state.solidMoments().values();for(int c=0;c<3;c++)solidTargets[node][c]-=withdrawn*moments[c]/state.mass();
                } else if(transfer instanceof ScheduledTransfer.Injection input) {
                    var rates=input.molesPerSecond();double massRate=0;
                    for(int c=0;c<rates.length;c++){target[c]+=dt*rates[c];massRate+=rates[c]*model.molecularWeight(c);}
                    massRate+=input.solidsPerSecond().massKg();var moments=input.solidsPerSecond().moments().values();for(int c=0;c<3;c++)solidTargets[node][c]+=dt*moments[c];
                    stored+=dt*(input.totalEnergyPerSecond()-massRate*GRAVITY*elevation);
                }
            }
            for(int edge:nodeEdges[node]) {
                var pipe=graph.pipes().get(edge);int a=pipe.first(),b=pipe.second();double flow=x[edgeOffset+edge];
                boolean first=node==a;int donor=flow>=0?a:b;var upstream=st[donor];var transport=tr[donor];
                boolean receiver=node==(flow>=0?b:a);
                double captureFraction=pipe.filter()==null?0:upstream.solidMoments().mass()/upstream.mass();
                double deliveredFlow=Math.abs(flow)*(1-captureFraction);
                net+=receiver?deliveredFlow:-Math.abs(flow);
                var amounts=transport.moles;double moving=dt*flow;
                for(int c=0;c<amounts.length;c++){double moved=moving*amounts[c]/upstream.mass();target[c]+=first?-moved:moved;}
                var moments=upstream.solidMoments().values();
                for(int c=0;c<3;c++)if(!receiver||pipe.filter()==null)solidTargets[node][c]+=(first?-1:1)*moving*moments[c]/upstream.mass();
                double donorZ=graph.reservoirs().get(donor).elevation(),h=transport.specificEnthalpy;
                stored+=(first?-1:1)*moving*(h+GRAVITY*(donorZ-elevation));
                // What arrives at this node, on the live upwind: read only by a junction's rows, which mix it into the
                // junction's owned stock like a vessel's inflow.
                int mixed=donor;
                if(receiver) {
                    var source=st[mixed];var carried=tr[mixed];var sourceAmounts=carried.moles;
                    double sourceCapture=pipe.filter()==null?0:source.solidMoments().mass()/source.mass();
                    double sourceZ=graph.reservoirs().get(mixed).elevation(),sourceEnthalpy=carried.specificEnthalpy;
                    mass+=Math.abs(flow)*(1-sourceCapture);
                    for(int c=0;c<sourceAmounts.length;c++)in[c]+=Math.abs(flow)*sourceAmounts[c]/source.mass();
                    heat+=Math.abs(flow)*(sourceEnthalpy+GRAVITY*(sourceZ-elevation));
                    var sourceMoments=source.solidMoments().values();
                    if(pipe.filter()==null)for(int c=0;c<3;c++)solidIncoming[node][c]+=Math.abs(flow)*sourceMoments[c]/source.mass();
                    else heat-=Math.abs(flow)*(source.solidMoments().enthalpy(source.temperature(),source.pressure())/source.mass()+sourceCapture*GRAVITY*(sourceZ-elevation));
                }
                if(receiver&&pipe.filter()!=null) {
                    double capturedRate=Math.abs(flow)*(upstream.solidMoments().enthalpy(upstream.temperature(),upstream.pressure())/upstream.mass()+captureFraction*GRAVITY*(donorZ-elevation));
                    stored-=dt*capturedRate;
                }
                if(!first&&pipe.control() instanceof FlowControl.Pump pump) {
                    int control=controlOffsets[edge];double head=control<0?0:x[control]*1e5;
                    double power=Math.max(0,flow)/(st[a].mass()/st[a].volume())*Math.max(0,head)/pump.efficiency();
                    stored+=dt*power;heat+=power;
                }
            }
            energy[node]=stored;incomingMass[node]=mass;incomingEnergy[node]=heat;netMass[node]=net;
        }
        /**
         * Whether {@code node} is a junction
         * whose every connection other than {@code edge} is closed (closed by the boundary rule or a device in mode
         * CLOSED). Its mass row then forces zero flow through {@code edge} at every solution, so the edge's velocity cap
         * can never be active there, and its row's only job is to place the junction pressure. The capped branch has no
         * pressure column (in every form), so an iterate that lands on it with the flow already fixed by the mass row
         * makes the Jacobian structurally singular; form B's re-weighting of the hydraulic row by
         * pressureScale/min(pressureScale, 2 limitDrop) lets the line search accept exactly such a step
         * (IslandCertificateTest's dead-headed pump, review 7.11). Such an edge gets no cap.
         */
        private boolean deadEnd(int node,int edge) {
            if(!graph.reservoirs().get(node).junction())return false;
            for(int e=0;e<graph.pipes().size();e++) {
                var p=graph.pipes().get(e);
                if(e==edge||p.first()!=node&&p.second()!=node)continue;
                if(!(boundaryClosed[e]||modes.get(e)==FlowControl.Mode.CLOSED))return false;
            }
            return true;
        }
        /** The hydraulic row of one edge and its actuator row, from the two endpoint states alone. */
        private void edgeRows(int edge,double[] x,FluidThermodynamics.State[] st,Transport[] tr,double[] f) {
            var pipe=graph.pipes().get(edge);int a=pipe.first(),b=pipe.second();double flow=x[edgeOffset+edge];
            int donor=flow>=0?a:b;var transport=tr[donor];double rho=transport.density;
            double dz=graph.reservoirs().get(b).elevation()-graph.reservoirs().get(a).elevation();
            double loss=pipe.pressureDrop(flow,rho,transport.viscosity);
            double filterCoefficient=0;
            if(pipe.filter()!=null){
                var state=st[donor];double fluidVolume=state.volume()-state.solidMoments().volume();double carrier=carrierViscosity(state);
                double loading=x[filterOffsets[edge]];
                filterCoefficient=pipe.filter().cleanResistance()*(carrier/.001)*(1+99*loading*loading)*fluidVolume/state.mass();loss=filterCoefficient*flow;
                double volume=pipe.filter().captured().volume()+(rateOnly?0:dt)*Math.abs(flow)*state.solidMoments().volume()/state.mass();
                f[filterOffsets[edge]]=loading-volume/pipe.filter().capacity();
            }
            int control=controlOffsets[edge];double head=control<0?0:x[control]*1e5;
            double signedHead=pipe.control() instanceof FlowControl.Pump?head:-head;
            // The static column is this pass's, never the live iterate's upwind end; see
            // {@link #headDensities}. On an island whose devices all sit at one y, {@code dz} is
            // zero and this term is bitwise the zero it always was.
            // A pump's column is the fluid it moves, the suction node's transported density at this trial - the density
            // its rise limit, its target row, its velocity clamp and the pass loop's shutoff margin ({@link #demand}) read -
            // never the destination's (owner rule, decision D6 of the mixed-gas junction batch). The model has no inlet-phase
            // concept (a node is withdrawn homogeneously), so that is the suction's bulk density.
            double column=pipe.control() instanceof FlowControl.Pump?tr[a].density:headDensities[edge];
            double driving=st[a].pressure()-st[b].pressure()-column*GRAVITY*dz+signedHead;
            // Every pressure-dimensioned row of this edge is stated in the island's own pressure,
            // never in a fixed 1e5 Pa unit; see {@link #pressureScales}. The throttled-inlet row
            // below and the closed-edge row are mass flows and keep their own flow scale, and the
            // pump's target row is a volume flow.
            double pressureScale=pressureScales[edge];
            f[edgeOffset+edge]=(driving-loss)/pressureScale;
            // No cap on an edge a dead-ended junction can only carry zero through; see {@link #deadEnd}.
            if(canClamp(modes.get(edge))&&!boundaryClosed[edge]&&!deadEnd(a,edge)&&!deadEnd(b,edge)) {
                // Which end's velocity limit caps a plain passive connection is read off the driving
                // pressure, not the live flow: at every root of this row the flow has the driving
                // pressure's sign, so the two agree wherever it matters, but deciding it by the
                // flow put a finite jump in the row at exactly zero flow whenever the driving
                // pressure lay between the two ends' limit drops - the hydraulic row on one side,
                // the throttled law's (+-limit - flow)/limit, about -1, on the other. The one-sided
                // Jacobian only samples one side of it, so a Newton step that turns the connection
                // round lands on a plateau no backtrack leaves: measured on the pumped three-tank
                // fill, where the second tank cools and the third pushes nitrogen back into it,
                // as the held retries in game at "Newton line search stalled at residual
                // 0.1047..." and here, bit for bit. See {@link #TRACE_CLAMP_FRACTION} for the
                // start-up it belongs to. A device's connection keeps its flow's donor: a pump or a
                // valve decides its direction through its own active set (a pump pushed backwards
                // is closed, not throttled), and a filter keeps its cake-dependent resistance on
                // the flow's donor as before.
                boolean byDriving=pipe.filter()==null&&pipe.control() instanceof FlowControl.Passive;
                int direction=(byDriving?driving>=0:flow>=0)?0:1;
                var capTransport=byDriving?tr[direction==0?a:b]:transport;
                // A colored Jacobian perturbs only a few nodes. Unchanged immutable donor
                // properties have the same cap and loss; keep one entry per edge/direction.
                if(pipe.filter()!=null||capSources[edge][direction]!=capTransport) {
                    double limit=capTransport.density*pipe.minimumArea()*capTransport.velocityLimit;
                    if(pipe.filter()!=null&&!rateOnly&&!prescribedFlow) {
                        // A filter has a second inlet limit: the cake it can still hold. The
                        // retained volume at the end of this step is the same expression the
                        // loading row below states, so bounding the inlet by the room divided by
                        // the step's own retention per unit mass makes the filter fill exactly at
                        // a step boundary instead of past one. A rate solve carries no step and no
                        // retention, so it is exempt, and a base already at capacity is left to
                        // the stage guard rather than throttled to a degenerate zero limit.
                        var state=st[donor];double retainedPerMass=state.solidMoments().volume()/state.mass();
                        double room=pipe.filter().capacity()*(1-FILTER_CAPACITY_MARGIN)-pipe.filter().captured().volume();
                        if(retainedPerMass>0&&room>0)limit=Math.min(limit,room/(dt*retainedPerMass));
                    }
                    capMassFlows[edge][direction]=limit;capPressureDrops[edge][direction]=pipe.filter()==null?pipe.pressureDrop(limit,capTransport.density,capTransport.viscosity):filterCoefficient*limit;capSources[edge][direction]=capTransport;
                }
                double limitDrop=capPressureDrops[edge][direction];
                // A saturated pressure/flow law: the unused driving pressure is throttled.
                // The same bounded flow unknown enters every component and enthalpy balance.
                // No post-solve clipping, temperature prescription, or inventory adjustment.
                //
                // Both branches are stated in one pressure unit, the loss against the driving pressure clamped at the cap's
                // own drop, over S = min(pressureScale, 2 limitDrop). The capped branch used to be a flow row,
                // (+-limit - q)/limit, on a scale 80x apart from the hydraulic row's on a 50 mm line, and a Newton crossing
                // the cap exit stalled on the mismatch; rescaling only the capped branch left the same mismatch at the
                // cap's edge (review 7.1, 7.5). The floor keeps a filter with no room left from a zero scale.
                double capScale=Math.max(Math.min(pressureScale,2*limitDrop),1e-12);
                f[edgeOffset+edge]=(driving-loss)/capScale;
                if(Math.abs(driving)>limitDrop)f[edgeOffset+edge]=(Math.copySign(limitDrop,driving)-loss)/capScale;
            }
            if(boundaryClosed[edge]&&control<0)f[edgeOffset+edge]=flow;
            if(control>=0)f[control]=switch(modes.get(edge)) {
                case PUMP_TARGET->(flow/(st[a].mass()/st[a].volume())-((FlowControl.Pump)pipe.control()).targetVolumeFlow())/.01;
                // The limit reads the suction's density at this trial, so the row carries it into the Jacobian: the block
                // sweep re-evaluates this edge's rows for every perturbed column of its first node, and the sparsity
                // already declares the actuator row on both endpoints' columns (buildSparsity).
                case PUMP_HEAD_LIMIT->(head-riseLimit((FlowControl.Pump)pipe.control(),tr[a].density))/pressureScale;
                case VALVE_REGULATING->(st[a].pressure()-((FlowControl.PressureValve)pipe.control()).targetPressure())/pressureScale;
                case VALVE_OPEN->head/pressureScale;
                case CLOSED->flow;
                case PASSIVE,VELOCITY_LIMITED,PUMP_VELOCITY_LIMIT,VALVE_VELOCITY_LIMIT->throw new IllegalStateException("Presentation-only or passive mode has actuator unknown");
            };
        }
        /**
         * The Jacobian from per-node block perturbations instead of a coloured whole-island sweep.
         *
         * <p>One local column at a time, for every node that owns one: the node is decoded at the
         * perturbed point and only the rows that can see it are re-assembled - its own block, the
         * block of each neighbour across an incident edge (whose backward-Euler targets and
         * aggregate solid targets carry this node's composition, solid moments and specific
         * enthalpy when it is the donor), and the hydraulic, actuator and filter-loading rows of
         * those edges. The flow, actuator and filter columns sweep the same way over their own
         * edge. Every such row is produced by the same {@link #nodeAccumulate},
         * {@link #edgeRows} and {@link #nodeRows} the whole-island residual uses, in the same
         * accumulation order, so each entry is the double the coloured sweep computed: the
         * colouring guarantees that no other column in a group can reach a row, which is exactly
         * the statement that evaluating that row with one column perturbed gives the same value.</p>
         *
         * <p>The cost this removes is the assembly, not the decodes. The coloured sweep already
         * decodes each node once per one of its own columns - the colour groups are
         * {@code (column, independent node set)} pairs - but it pays a complete island residual,
         * every node block and every edge row, for each of those 143-191 groups.</p>
         */
        public int differentiateEntries(double[] x,double[] f,double differenceStep,int[] entryOffsets,int[] entryRows,
                                        double[] entries,Runnable checkpoint) {
            if(sparsity==null)buildSparsity();
            FluidThermodynamics.State[] st,baseStates;Transport[] tr,baseTransport;FluidThermodynamics.Prepared[] pr,basePrepared;
            double[] trial,perturbed;
            try {
                decode(x);
                st=cachedStates.clone();tr=cachedTransport.clone();pr=cachedPrepared.clone();
                baseStates=cachedStates.clone();baseTransport=cachedTransport.clone();basePrepared=cachedPrepared.clone();
                trial=x.clone();perturbed=new double[size];
            }catch(IllegalArgumentException outsideDomain) {
                SolverDiagnostics.count(SolverDiagnostics.jacobianBlockFallbacks);return -1;
            }
            int columns=0;
            try {
                for(int node=0;node<layout.length;node++) {
                    if(layout[node]==null)continue;
                    for(int local=0;local<layout[node].size();local++) {
                        checkpoint.run();columns++;
                        int column=offsets[node]+local;
                        double step=differenceStep*differenceScale(column,x[column]);
                        trial[column]+=step;
                        double temperature=layout[node].temperature(trial,offsets[node]);
                        var prepared=basePrepared[node]!=null&&basePrepared[node].temperature()==temperature?basePrepared[node]:model.prepare(temperature);
                        var state=layout[node].decode(trial,offsets[node],prepared);
                        st[node]=state;pr[node]=prepared;
                        tr[node]=new Transport(PhaseLayout.totalAmounts(state),state.mass()/state.volume(),
                                viscosity(state,prepared),state.enthalpy()/state.mass(),model.velocityLimit(state));
                        // Seed every neighbour before recomputing any of them: parallel pipes reach
                        // the same node twice, and a seed must never overwrite a recomputed row.
                        for(int edge:nodeEdges[node])seed(other(edge,node),perturbed,f);
                        nodeAccumulate(node,trial,st,tr);nodeRows(node,trial,st,pr,perturbed);
                        for(int edge:nodeEdges[node]) {
                            edgeRows(edge,trial,st,tr,perturbed);
                            int other=other(edge,node);
                            if(layout[other]!=null&&carries(edge,node,trial)) {
                                nodeAccumulate(other,trial,st,tr);nodeTargetRows(other,trial,st,perturbed);
                            }
                        }
                        write(entryOffsets,entryRows,entries,column,perturbed,f,step);
                        st[node]=baseStates[node];tr[node]=baseTransport[node];pr[node]=basePrepared[node];
                        trial[column]=x[column];
                    }
                }
                for(int edge=0;edge<graph.pipes().size();edge++) {
                    checkpoint.run();
                    var pipe=graph.pipes().get(edge);
                    // The filter's retained-volume unknown is the third column of an edge. It enters
                    // this edge's clogging resistance and its own loading row and nothing else - no
                    // node block carries it, which is what buildSparsity declares - so its whole
                    // stencil is what one edge assembly writes.
                    for(int which=0;which<3;which++) {
                        int column=which==0?edgeOffset+edge:which==1?controlOffsets[edge]:filterOffsets[edge];
                        if(column<0)continue;
                        columns++;
                        double step=differenceStep*differenceScale(column,x[column]);
                        trial[column]+=step;
                        edgeRows(edge,trial,st,tr,perturbed);
                        // No node is decoded again, so only the two endpoints' inflow rows move.
                        if(which<2)for(int node:new int[]{pipe.first(),pipe.second()})if(layout[node]!=null) {
                            seed(node,perturbed,f);nodeAccumulate(node,trial,st,tr);nodeTargetRows(node,trial,st,perturbed);
                        }
                        write(entryOffsets,entryRows,entries,column,perturbed,f,step);
                        trial[column]=x[column];
                    }
                }
            }catch(IllegalArgumentException outsideDomain) {
                // A perturbed node left the property domain. The coloured sweep has a bounded
                // one-sided stencil for exactly that, so hand the whole build back to it.
                SolverDiagnostics.count(SolverDiagnostics.jacobianBlockFallbacks);return -1;
            }
            SolverDiagnostics.count(SolverDiagnostics.jacobianBlockColumns,columns);
            return columns;
        }
        private int other(int edge,int node) {
            var pipe=graph.pipes().get(edge);return pipe.first()==node?pipe.second():pipe.first();
        }
        /** A row this column cannot move keeps the value the base residual already produced for it. */
        private void seed(int node,double[] perturbed,double[] f) {
            if(layout[node]!=null)System.arraycopy(f,offsets[node],perturbed,offsets[node],layout[node].size());
        }
        private void write(int[] entryOffsets,int[] entryRows,double[] entries,int column,double[] perturbed,double[] f,double step) {
            for(int entry=entryOffsets[column];entry<entryOffsets[column+1];entry++) {
                int row=entryRows[entry];entries[entry]=(perturbed[row]-f[row])/step;
            }
        }
        /** The balance, energy, volume, equilibrium and closure rows this node owns. */
        private void nodeRows(int node,double[] x,FluidThermodynamics.State[] st,FluidThermodynamics.Prepared[] pr,double[] f) {
            if(!graph.reservoirs().get(node).junction())
                layout[node].residual(st[node],targets[node],energy[node],graph.reservoirs().get(node).inventory().volume(),f,offsets[node],x,pr[node]);
            else layout[node].junctionResidual(st[node],fractions,junctionInflow(node),netMass[node],retainedPressures[node],f,offsets[node],x,pr[node],junctionWeight);
            nodeSolidRows(node,x,st,f);
        }
        /**
         * Only the rows a change of this node's inflow can move. A block sweep that perturbs one of
         * this node's <em>neighbours</em> leaves the decoded state here untouched, so the volume
         * closure, the equilibrium rows and the amount normalization are the base residual's own
         * doubles and the sweep seeds them from it rather than recomputing them.
         */
        private void nodeTargetRows(int node,double[] x,FluidThermodynamics.State[] st,double[] f) {
            if(!graph.reservoirs().get(node).junction())
                layout[node].balanceRows(st[node],targets[node],energy[node],graph.reservoirs().get(node).inventory().volume(),f,offsets[node],x);
            else layout[node].junctionRows(st[node],fractions,junctionInflow(node),netMass[node],retainedPressures[node],f,offsets[node],x,junctionWeight);
            nodeSolidRows(node,x,st,f);
        }
        /**
         * This node's three aggregate solid rows against the targets <em>this</em> evaluation
         * accumulated, which is what makes them rows a neighbour's column can move.
         *
         * <p>{@link PhaseLayout#balanceRows} and {@link PhaseLayout#junctionRows} already write
         * these rows, but against the layout's own seed reference, because a layout evaluated on its
         * own has no accumulated target. Both whole-island assembly and block sweep therefore have
         * to overwrite them here, with the same targets in the same order, or a neighbour's column
         * would be differentiated against the seed reference while the base residual holds the real
         * one - a silently wrong derivative rather than a missing one.
         */
        private void nodeSolidRows(int node,double[] x,FluidThermodynamics.State[] st,double[] f) {
            if(!solidSupport)return;
            if(!graph.reservoirs().get(node).junction()) {
                layout[node].solidRows(st[node],x,offsets[node],solidTargets[node],false,f);return;
            }
            // A junction's solid moments are its owned stock's, mixed with what arrives like its fluid; a rate-only solve
            // holds them at the stock's own (see junctionInflow).
            var inventory=graph.reservoirs().get(node).inventory();
            double retained=(molarMass(oldAmounts[node])+inventory.solids().massKg())/dt,total=incomingMass[node]+retained;
            var solids=solidIncoming[node].clone();var held=inventory.solids().moments().values();
            for(int c=0;c<3;c++)solids[c]=rateOnly?held[c]/(retained*dt):(solids[c]+held[c]/dt)/total;
            layout[node].solidRows(st[node],x,offsets[node],solids,true,f);
        }
        /**
         * Fills {@link #fractions} with the mass fractions this junction's rows mix to and returns the specific enthalpy,
         * and sets {@link #junctionWeight}.
         *
         * <p>A step solve is backward Euler over the step on the junction's owned inventory,
         * {@code (m/dt + Q) w = (m/dt) w_old + sum q w_e} and {@code (m/dt + Q) h = E_old/dt + sum q (h_e + g dz)}, with m,
         * w_old and E_old = m h_old (the inventory's energy field, in enthalpy form) read from the graph this step solves -
         * the same numbers the vessel row of {@code ConservativeTransport.reconstruct} reads. The rows are stated in amount
         * form over the junction's own mass, {@code (dt/m)(m/dt + Q)(w - w_mix)}: a row at the Newton tolerance then bounds
         * the step's species error at tol m_J, and the rounding floor is {@code eps (1 + dt Q/m)}, which does not grow as
         * the step shrinks (in rate form, {@code eps (m/dt + Q)} kg/s, it did: every refinement chain ended on it; review
         * 8.3 class 4 and 8.7 (c) C).
         *
         * <p>A rate-only solve evaluates a differential state at its own value: the junction's composition and enthalpy
         * are held at its owned inventory's, its pressure stays algebraic, and the reconstruction books its accumulation
         * as a pseudo-boundary at the junction ({@code ConservativeTransport.reconstruct}, frozen junctions).
         */
        private double junctionInflow(int node) {
            var inventory=graph.reservoirs().get(node).inventory();var held=oldAmounts[node];
            double heldMass=molarMass(held)+inventory.solids().massKg(),total=incomingMass[node]+heldMass/dt;
            if(rateOnly) {
                junctionWeight=1;
                for(int c=0;c<fractions.length;c++)fractions[c]=held[c]*model.molecularWeight(c)/heldMass;
                return inventory.internalEnergy()/heldMass;
            }
            junctionWeight=total*dt/Math.max(heldMass,1e-300);
            for(int c=0;c<fractions.length;c++)
                fractions[c]=(incoming[node][c]+held[c]/dt)*model.molecularWeight(c)/total;
            return (incomingEnergy[node]+inventory.internalEnergy()/dt)/total;
        }
        /** Whether this edge can carry a change of {@code node}'s decoded state into the other
         * endpoint's rows: the transported amounts and enthalpy are the donor's, a junction's
         * mixture is this pass's frozen donor's, and a pump's shaft power is metered on the first
         * endpoint's density. */
        private boolean carries(int edge,int node,double[] x) {
            var pipe=graph.pipes().get(edge);
            return node==(x[edgeOffset+edge]>=0?pipe.first():pipe.second())
                    ||graph.reservoirs().get(other(edge,node)).junction()&&node==(junctionDonorFirst[edge]?pipe.first():pipe.second())
                    ||node==pipe.first()&&pipe.control() instanceof FlowControl.Pump;
        }
    }
}

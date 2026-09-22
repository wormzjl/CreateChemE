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
     * How far below its stated capacity the saturated filter inlet aims. The committed cake is
     * reconstructed from the converged flow by a different summation than the loading row solves,
     * so a law aimed exactly at capacity lands within a unit in the last place of it on either
     * side, and a cake one ULP over its own capacity is not a state the owned inventory should
     * ever hold. Aiming a hair under makes the landing one-sided while leaving it at capacity to
     * far better than any audit resolves; {@link SolidEventIntegrator} treats a cake this close to
     * capacity as having met it.
     */
    private static final double FILTER_CAPACITY_MARGIN=1e-12;
    private final FluidThermodynamics model;
    private final SolverOwnership ownership;
    private final Map<WorkspaceKey,SparseNewton.Workspace> workspaces=new LinkedHashMap<>();
    private final Map<WorkspaceKey,SparseNewton.Workspace> structures=new LinkedHashMap<>();
    private List<PassiveNetwork.Pipe.Identity> previousPipes=List.of();
    private long[] previousNodeIds=new long[0];
    private double[] previousFlows=new double[0],previousHeads=new double[0];
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
    private Result solve(PassiveNetwork graph,double dt,Runnable checkpoint,Acceptance acceptance,boolean rateOnly) {
        this.rateOnly=rateOnly;
        Objects.requireNonNull(acceptance);SolverDiagnostics.count(SolverDiagnostics.implicitSolves);
        ownership.check("Each executing island job needs its own step workspace");
        lastSolve=null;
        if(!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Positive finite substep required");
        if(graph.pipes().isEmpty()&&graph.scheduledTransfers().isEmpty())return new Result(graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),new double[0],dt,
                new SparseNewton.Result(new double[0],0,0,0,0,0),List.of(),new double[0],0,new double[model.hydrocarbon.componentCount()+1],0,
                graph.reservoirs().stream().map(PassiveNetwork.Reservoir::inventory).toList(),List.of(),List.of());
        if(graph.reservoirs().stream().anyMatch(PassiveNetwork.Reservoir::empty))throw new IllegalArgumentException("Evacuated reservoir has no fluid temperature; connected filling requires a supported initialization state");
        var reachable=reachableComponents(graph);
        var seeds=initialPhaseSeeds(graph,dt,checkpoint,reachable);
        var modes=new ArrayList<FlowControl.Mode>();
        for(var pipe:graph.pipes())modes.add(switch(pipe.control()) {
            case FlowControl.Passive ignored->FlowControl.Mode.PASSIVE;
            case FlowControl.Pump pump->pump.targetVolumeFlow()==0?FlowControl.Mode.CLOSED:
                    graph.pipes().stream().anyMatch(p->p.blockedDirections()!=0)?FlowControl.Mode.PUMP_HEAD_LIMIT:
                    pump.targetVolumeFlow()>pipe.minimumArea()*model.velocityLimit(graph.reservoirs().get(pipe.first()).state())?FlowControl.Mode.PUMP_HEAD_LIMIT:FlowControl.Mode.PUMP_TARGET;
            case FlowControl.PressureValve valve->graph.reservoirs().get(pipe.first()).state().pressure()>valve.targetPressure()+.01
                    ?FlowControl.Mode.VALVE_OPEN:FlowControl.Mode.CLOSED;
        });
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
            int[] phases=new int[seeds.size()];for(int i=0;i<phases.length;i++)phases[i]=phaseCode(seeds.get(i));
            byte[] modeCodes=new byte[modes.size()];for(int i=0;i<modeCodes.length;i++)modeCodes[i]=(byte)modes.get(i).ordinal();
            var supports=supports(graph,seeds,reachable,promoted);
            // The active-set state is exactly what varies between passes, so the structure key is
            // also the cycle key: repeating one means the pass sequence cannot make progress.
            var structure=new WorkspaceKey(0,nodeIds,kinds,pipeIdentities,phases,componentMask,supportCodes(supports),modeCodes,boundaryClosed.clone());
            if(!seen.add(structure))throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");
            var equations=new Equations(graph,dt,modes,boundaryClosed,seeds,reachable,supports);
            var key=new WorkspaceKey(Double.doubleToLongBits(dt),nodeIds,kinds,pipeIdentities,phases,componentMask,structure.supports,modeCodes,structure.boundaryClosed);
            var workspace=workspaces.get(key);
            SolverDiagnostics.count(workspace==null?SolverDiagnostics.workspaceBuilds:SolverDiagnostics.workspaceReuses);
            if(workspace==null){if(workspaces.size()>=4)workspaces.remove(workspaces.keySet().iterator().next());var previous=structures.get(structure);workspace=previous==null?new SparseNewton.Workspace(ownership):previous.forkPreconditioner();workspaces.put(key,workspace);}
            if(structures.size()>=4&&!structures.containsKey(structure))structures.remove(structures.keySet().iterator().next());structures.put(structure,workspace);
            SparseNewton.Result numerical;
            // Small headspaces amplify inventory roundoff into pressure/flow errors; solve these more tightly.
            boolean smallHeadspace=seeds.stream().anyMatch(state->state.vaporVolume()/state.volume()<.01);
            // 1e-11 stalls on caloric cancellation in nearly liquid-full water with trace N2.
            // Keep two orders of margin to the unchanged 1e-8 full-equation gate; every final
            // reconstruction and the independent interval accuracy/accounting checks still run.
            double tolerance=acceptance==Acceptance.APPROXIMATE?1e-6:smallHeadspace?1e-10:1e-9;
            try{numerical=SparseNewton.solve(equations,equations.initial(),new SparseNewton.Settings(20,tolerance,1e-6,24),checkpoint,workspace);}
            catch(SparseNewton.Nonconvergence failure) {
                if(failure.lastVariables()==null)throw failure;
                double[] trialFlows=Arrays.copyOfRange(failure.lastVariables(),equations.edgeOffset,equations.edgeOffset+graph.pipes().size());
                if(refineJunctionReachability(graph,reachable,trialFlows)){seeds=initialPhaseSeeds(graph,dt,checkpoint,reachable);continue;}
                var changedSeeds=phaseCorrection(graph,equations.states(failure.lastVariables()),checkpoint,false);
                if(changedSeeds==null)throw new SparseNewton.Nonconvergence(failure.getMessage()+"; active-set pass="+pass,failure.lastVariables());
                seeds=changedSeeds;continue;
            }
            double[] x=numerical.variables();var states=equations.states(x);double[] flows=new double[graph.pipes().size()],heads=new double[flows.length];
            if(refineJunctionReachability(graph,reachable,Arrays.copyOfRange(x,equations.edgeOffset,equations.edgeOffset+graph.pipes().size()))){seeds=initialPhaseSeeds(graph,dt,checkpoint,reachable);continue;}
            var changedSeeds=phaseCorrection(graph,states,checkpoint,true);if(changedSeeds!=null){seeds=changedSeeds;continue;}
            // The same outer stability question the phase correction above answers for a whole phase,
            // asked per component of the frozen trace support: this converged point's own fugacity
            // coefficients decide whether an omitted phase is still a trace.
            if(reactivate(equations,states,promoted)>0){seeds=states;continue;}
            boolean changed=false;double work=0;
            for(int i=0;i<flows.length;i++) {
                flows[i]=x[equations.edgeOffset+i];heads[i]=equations.controlOffsets[i]<0?0:x[equations.controlOffsets[i]]*1e5;
                if(boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED){flows[i]=0;x[equations.edgeOffset+i]=0;}
                var pipe=graph.pipes().get(i);var up=states.get(pipe.first());double rho=up.mass()/up.volume();var mode=modes.get(i);var next=mode;
                if(!boundaryAllowed(graph,pipe,flows[i])&&Math.abs(flows[i])>1e-10) {
                    if(!changed){boundaryClosed[i]=true;if(!(pipe.control() instanceof FlowControl.Passive))modes.set(i,FlowControl.Mode.CLOSED);changed=true;}continue;
                }
                if(boundaryClosed[i])continue;
                if(pipe.control() instanceof FlowControl.Pump pump) {
                    if(pump.targetVolumeFlow()==0)next=FlowControl.Mode.CLOSED;
                    else if(mode==FlowControl.Mode.PUMP_TARGET&&pump.targetVolumeFlow()>pipe.minimumArea()*model.velocityLimit(up)*(1+1e-8))next=FlowControl.Mode.PUMP_HEAD_LIMIT;
                    else if(mode==FlowControl.Mode.PUMP_TARGET&&heads[i]>pump.maximumAddedPressure()+.01)next=FlowControl.Mode.PUMP_HEAD_LIMIT;
                    else if(mode==FlowControl.Mode.PUMP_HEAD_LIMIT&&flows[i]<-1e-10)next=FlowControl.Mode.CLOSED;
                    else if(mode==FlowControl.Mode.PUMP_HEAD_LIMIT&&flows[i]/rho>pump.targetVolumeFlow()*(1+1e-8))next=FlowControl.Mode.PUMP_TARGET;
                    else if(mode==FlowControl.Mode.CLOSED&&heads[i]<pump.maximumAddedPressure()-.01)next=FlowControl.Mode.PUMP_HEAD_LIMIT;
                    work+=dt*Math.max(0,flows[i])/rho*Math.max(0,heads[i])/pump.efficiency();
                }else if(pipe.control() instanceof FlowControl.PressureValve valve) {
                    if(mode==FlowControl.Mode.VALVE_REGULATING&&flows[i]<-1e-10)next=FlowControl.Mode.CLOSED;
                    else if(mode==FlowControl.Mode.VALVE_REGULATING&&flows[i]>massFlowLimit(pipe,up)*(1+1e-8))next=FlowControl.Mode.VALVE_OPEN;
                    else if(mode==FlowControl.Mode.VALVE_REGULATING&&heads[i]<-.01)next=FlowControl.Mode.VALVE_OPEN;
                    else if(mode==FlowControl.Mode.VALVE_OPEN&&flows[i]<-1e-10)next=FlowControl.Mode.CLOSED;
                    else if(mode==FlowControl.Mode.VALVE_OPEN&&!graph.reservoirs().get(pipe.first()).fixed()&&up.pressure()<valve.targetPressure()-.01&&flows[i]>1e-10)next=FlowControl.Mode.VALVE_REGULATING;
                    else if(mode==FlowControl.Mode.CLOSED&&up.pressure()>valve.targetPressure()+.01&&heads[i]>.01)next=FlowControl.Mode.VALVE_REGULATING;
                }
                if(next!=mode&&!changed){modes.set(i,next);changed=true;}
            }
            if(changed){seeds=states;previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousFlows=flows.clone();previousHeads=heads.clone();continue;}
            for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);var upstream=states.get(flows[edge]>=0?pipe.first():pipe.second());
                if(Math.abs(flows[edge])>massFlowLimit(pipe,upstream)*(1+2e-8)+1e-12)throw new SparseNewton.Nonconvergence("Velocity constraint did not close");
            }
            var projection=ConservativeTransport.reconstruct(graph,states,flows,heads,dt,model,checkpoint,transport);
            changedSeeds=phaseCorrection(graph,projection.states(),checkpoint,true);if(changedSeeds!=null){seeds=changedSeeds;continue;}
            var reconstructed=x.clone();
            for(int node=0;node<projection.states().size();node++)if(equations.layout[node]!=null){var encoded=equations.layout[node].encode(projection.states().get(node));System.arraycopy(encoded,0,reconstructed,equations.offsets[node],encoded.length);}
            SolverDiagnostics.count(SolverDiagnostics.verificationResiduals);
            double maximumResidual=0;for(double residual:equations.residual(reconstructed))maximumResidual=Math.max(maximumResidual,Math.abs(residual));
            if(maximumResidual>(acceptance==Acceptance.FULL?1e-8:1e-6))throw new SparseNewton.Nonconvergence("Conservative reconstruction fails equation gate: "+maximumResidual);
            if(acceptance==Acceptance.APPROXIMATE)checkApproximation(equations,reconstructed,projection.states(),flows,workspace,checkpoint);
            checkConservation(graph,projection);
            previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousFlows=flows.clone();previousHeads=heads.clone();
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
            return new Result(projection.states(),flows,dt,numerical,acceptedModes,heads,projection.pumpWork(),projection.externalMoles(),projection.externalEnergy(),projection.inventories(),projection.boundaries(),PipeTransfer.sample(graph,projection.states(),flows,dt),projection.filters());
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
                                int[] phases,boolean[] componentMask,byte[] supports,byte[] modes,boolean[] boundaryClosed) {
        @Override public boolean equals(Object other) {
            return other instanceof WorkspaceKey key&&stepBits==key.stepBits&&Arrays.equals(nodeIds,key.nodeIds)
                    &&Arrays.equals(kinds,key.kinds)&&pipes.equals(key.pipes)&&Arrays.equals(phases,key.phases)
                    &&Arrays.equals(componentMask,key.componentMask)&&Arrays.equals(supports,key.supports)
                    &&Arrays.equals(modes,key.modes)&&Arrays.equals(boundaryClosed,key.boundaryClosed);
        }
        @Override public int hashCode() {
            int hash=31*Long.hashCode(stepBits)+Arrays.hashCode(nodeIds);
            hash=31*(31*hash+Arrays.hashCode(kinds))+pipes.hashCode();
            hash=31*(31*hash+Arrays.hashCode(phases))+Arrays.hashCode(componentMask);
            hash=31*hash+Arrays.hashCode(supports);
            return 31*(31*hash+Arrays.hashCode(modes))+Arrays.hashCode(boundaryClosed);
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
    private void checkApproximation(Equations equations,double[] point,List<FluidThermodynamics.State> candidate,double[] flows,SparseNewton.Workspace workspace,Runnable checkpoint) {
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
                if(q<-floor||mode!=FlowControl.Mode.CLOSED&&head>pump.maximumAddedPressure()+.01||mode==FlowControl.Mode.CLOSED&&pump.targetVolumeFlow()>0&&head<pump.maximumAddedPressure()-.01)
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
    private boolean[][] reachableComponents(PassiveNetwork graph){return reachableComponents(graph,null);}
    private boolean[][] reachableComponents(PassiveNetwork graph,double[] directions) {
        int count=model.hydrocarbon.componentCount()+1,nodes=graph.reservoirs().size();
        var possible=new BitSet[nodes];var outgoing=new ArrayList<List<Integer>>(nodes);
        for(int i=0;i<nodes;i++) {
            possible[i]=new BitSet(count);outgoing.add(new ArrayList<>());
            var node=graph.reservoirs().get(i);
            if(!node.junction()&&node.kind()!=PassiveNetwork.NodeKind.VOID) {
                var amounts=node.inventory().moles();for(int c=0;c<count;c++)if(amounts[c]>0)possible[i].set(c);
            }
        }
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection injection) {
            var amounts=injection.molesPerSecond();for(int c=0;c<count;c++)if(amounts[c]>0)possible[injection.node()].set(c);
        }
        for(int edge=0;edge<graph.pipes().size();edge++) {var pipe=graph.pipes().get(edge);
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
    private boolean refineJunctionReachability(PassiveNetwork graph,boolean[][] reachable,double[] flows){
        if(!hasSolids(graph)&&graph.pipes().stream().noneMatch(p->p.filter()!=null))return false;
        var directed=reachableComponents(graph,flows);boolean changed=false;
        for(int i=0;i<reachable.length;i++)if(graph.reservoirs().get(i).junction()&&!Arrays.equals(reachable[i],directed[i])){reachable[i]=directed[i];changed=true;}
        return changed;
    }
    private List<FluidThermodynamics.State> initialPhaseSeeds(PassiveNetwork graph,double dt,Runnable checkpoint,boolean[][] reachable) {
        int count=model.hydrocarbon.componentCount()+1;
        var seeds=new ArrayList<FluidThermodynamics.State>();
        for(int nodeIndex=0;nodeIndex<graph.reservoirs().size();nodeIndex++) {
            var node=graph.reservoirs().get(nodeIndex);var available=reachable[nodeIndex];
            var state=node.state();if(node.fixed()){seeds.add(state);continue;}
            var n=PhaseLayout.totalAmounts(state);double total=Arrays.stream(n).sum();boolean changed=false;
            // Only a zero-storage guess can discard an unreachable species; real inventories are untouched.
            if(node.junction())for(int c=0;c<count;c++)if(!available[c]&&n[c]>0){n[c]=0;changed=true;}
            // This is only a trial guess. The accumulation equations still start from the exact original inventory.
            for(int i=0;i<count;i++)if(available[i]&&n[i]==0)changed=true;
            double weightedTemperature=state.mass()*state.temperature(),seedMass=state.mass();
            if(changed)for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection input&&input.node()==nodeIndex) {
                var rates=input.molesPerSecond();for(int i=0;i<count;i++)n[i]+=dt*rates[i];
            }
            if(changed)for(var pipe:graph.pipes()) {
                if(pipe.first()!=nodeIndex&&pipe.second()!=nodeIndex)continue;
                double flow=initialMassFlow(graph,pipe);int receiver=flow>=0?pipe.second():pipe.first(),donor=flow>=0?pipe.first():pipe.second();
                if(receiver!=nodeIndex||!boundaryAllowed(graph,pipe,flow))continue;
                var incoming=graph.reservoirs().get(donor).state();double mass=Math.min(state.mass()*.25,dt*Math.abs(flow));var feed=PhaseLayout.totalAmounts(incoming);
                for(int i=0;i<count;i++)n[i]+=mass*feed[i]/incoming.mass();
                weightedTemperature+=mass*incoming.temperature();seedMass+=mass;
            }
            for(int i=0;i<count;i++)if(available[i]&&n[i]==0)n[i]=total*1e-12;
            seeds.add(changed?model.flashTP(weightedTemperature/seedMass,state.pressure(),n,checkpoint).withSolidState(state.solids(),state.solidMoments()):state);
        }
        return seeds;
    }
    private double initialMassFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        if(pipe.control() instanceof FlowControl.Pump pump)
            return Math.min(pump.targetVolumeFlow()*a.state().mass()/a.state().volume(),massFlowLimit(pipe,a.state()));
        var upstream=a.state().pressure()>=b.state().pressure()?a.state():b.state();double rho=upstream.mass()/upstream.volume(),mu=viscosity(upstream);
        double driving=a.state().pressure()-b.state().pressure()-rho*GRAVITY*(b.elevation()-a.elevation());
        double lo=0,hi=1;while(pipe.pressureDrop(hi,rho,mu)<Math.abs(driving)&&hi<1e6)hi*=2;
        for(int j=0;j<50;j++){double mid=(lo+hi)/2;if(pipe.pressureDrop(mid,rho,mu)>Math.abs(driving))hi=mid;else lo=mid;}
        var donor=graph.reservoirs().get(driving>=0?pipe.first():pipe.second()).state();
        return Math.copySign(Math.min((lo+hi)/2,massFlowLimit(pipe,donor)),driving);
    }
    private double massFlowLimit(PassiveNetwork.Pipe pipe,FluidThermodynamics.State donor) {
        return donor.mass()/donor.volume()*pipe.minimumArea()*model.velocityLimit(donor);
    }
    private static boolean canClamp(FlowControl.Mode mode) {
        return mode==FlowControl.Mode.PASSIVE||mode==FlowControl.Mode.PUMP_HEAD_LIMIT||mode==FlowControl.Mode.VALVE_OPEN;
    }
    /** Outer active-set stability check; never runs inside a Newton residual or Jacobian evaluation. */
    private List<FluidThermodynamics.State> phaseCorrection(PassiveNetwork graph,List<FluidThermodynamics.State> states,Runnable checkpoint,boolean converged) {
        var corrected=new ArrayList<FluidThermodynamics.State>();boolean changed=false;
        for(int i=0;i<states.size();i++) {
            var state=states.get(i);if(graph.reservoirs().get(i).fixed()){corrected.add(state);continue;}
            // When all allowed phases are populated, the simultaneous fugacity/saturation rows
            // already enforce their equilibrium. An additional TP flash repeats the same work.
            // Missing-phase stability and unsuccessful Newton passes still require the outer check.
            boolean hydroComplete=state.liquidProperties()!=null&&state.vaporProperties()!=null||state.liquidProperties()==null&&state.vaporProperties()==null;
            boolean waterComplete=state.waterLiquid()>0&&state.waterVapor()>0||state.waterLiquid()+state.waterVapor()==0;
            if(converged&&hydroComplete&&waterComplete&&(state.vaporProperties()==null||state.vaporProperties().vaporBranch())){corrected.add(state);continue;}
            var equilibrium=model.flashTP(state.temperature(),state.pressure(),PhaseLayout.totalAmounts(state),checkpoint).withSolidState(state.solids(),state.solidMoments());
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
            if(reservoir.junction()||reservoir.fixed())continue;
            var a=old.moles();var b=next.moles();double ma=old.solids().massKg(),mb=next.solids().massKg();
            for(int j=0;j<count;j++){before[j]+=a[j];after[j]+=b[j];double mw=model.molecularWeight(j);ma+=a[j]*mw;mb+=b[j]*mw;}
            eb+=old.internalEnergy()+ma*GRAVITY*reservoir.elevation();ea+=next.internalEnergy()+mb*GRAVITY*reservoir.elevation();
            energyScale+=Math.abs(old.internalEnergy())+Math.abs(ma*GRAVITY*reservoir.elevation());
        }
        var solidBalance=new TreeMap<SolidInventory.Key,double[]>();
        for(int i=0;i<graph.reservoirs().size();i++)if(!graph.reservoirs().get(i).fixed()&&!graph.reservoirs().get(i).junction()){
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
         * length. Zero for every fluid amount, so {@link #maximumStep} is bit for bit the rule it
         * was for a clear-fluid island; see {@link #SOLID_CLAMP_FRACTION}. */
        final double[] clampFloors;
        /** Edge indices incident to each node, ascending - which is the order the whole-island
         * residual accumulates that node's targets, energy and junction inflows in, so a per-node
         * re-assembly reproduces every accumulator bit for bit. */
        final int[][] nodeEdges;
        /** Residual scratch. Every one of these is written before it is read within a single
         * evaluation and never escapes it, and the evaluations of one solve are sequential on the
         * worker holding the latch, so they are filled again rather than allocated again. The
         * returned residual is not among them: {@link SparseNewton} holds the current and the
         * candidate residual at the same time. */
        final double[][] targets,incoming;
        final double[] energy,incomingMass,incomingEnergy,netMass,fractions;
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
        /** Solid unknowns this point leaves negative, i.e. where {@link PhaseLayout#decode}'s
         * nonnegativity projection would be active. Must be zero at every accepted point. */
        int negativeSolidUnknowns(double[] x) {
            int count=0;for(int c=0;c<edgeOffset;c++)if(solidVariables[c]&&x[c]<0)count++;
            return count;
        }
        int nodeSize(int node){return layout[node]==null?0:layout[node].size();}
        public int[][] columnRows(){if(sparsity==null)buildSparsity();return sparsity;}
        double[] initial() {
            double[] x=new double[size];for(int i=0;i<layout.length;i++)if(layout[i]!=null){var encoded=layout[i].encode(seeds.get(i));System.arraycopy(encoded,0,x,offsets[i],encoded.length);}
            boolean warmFlow=graph.reservoirs().stream().anyMatch(node->node.junction()||node.fixed())
                    ||graph.pipes().stream().anyMatch(pipe->phaseCode(graph.reservoirs().get(pipe.first()).state())!=phaseCode(graph.reservoirs().get(pipe.second()).state()));
            boolean previousAvailable=previousPipes.equals(pipeIdentities)&&Arrays.equals(previousNodeIds,nodeIds(graph));
            for(int i=0;i<graph.pipes().size();i++) {
                var pipe=graph.pipes().get(i);var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
                if(boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED)continue;
                if(modes.get(i)==FlowControl.Mode.PUMP_TARGET){x[edgeOffset+i]=((FlowControl.Pump)pipe.control()).targetVolumeFlow()*a.state().mass()/a.state().volume();continue;}
                if(previousAvailable){
                    x[edgeOffset+i]=previousFlows[i];if(controlOffsets[i]>=0)x[controlOffsets[i]]=previousHeads[i]/1e5;
                    if(modes.get(i)==FlowControl.Mode.VALVE_REGULATING&&pipe.control() instanceof FlowControl.PressureValve valve) {
                        double predictedDrop=a.state().pressure()-seeds.get(pipe.first()).pressure();
                        if(predictedDrop>0)x[edgeOffset+i]*=Math.clamp((a.state().pressure()-valve.targetPressure())/predictedDrop,0,1);
                        double rho=a.state().mass()/a.state().volume();
                        x[controlOffsets[i]]=(a.state().pressure()-b.state().pressure()-rho*GRAVITY*(b.elevation()-a.elevation())
                                -pipe.pressureDrop(x[edgeOffset+i],rho,viscosity(a.state())))/1e5;
                    }
                    continue;
                }
                if(!warmFlow)continue;
                x[edgeOffset+i]=initialMassFlow(graph,pipe);
            }
            if(hasSolids(graph)||graph.pipes().stream().anyMatch(pipe->pipe.filter()!=null)) {
                boolean[] known=new boolean[graph.pipes().size()];
                for(int i=0;i<known.length;i++)known[i]=modes.get(i)==FlowControl.Mode.PUMP_TARGET||boundaryClosed[i]||modes.get(i)==FlowControl.Mode.CLOSED;
                for(int pass=0;pass<known.length;pass++) {
                    boolean changed=false;
                    for(int node=0;node<layout.length;node++)if(graph.reservoirs().get(node).junction()) {
                        int missing=-1;double net=0;boolean usable=true;
                        for(int edge:nodeEdges[node]) {
                            if(!known[edge]){if(missing>=0){usable=false;break;}missing=edge;continue;}
                            var pipe=graph.pipes().get(edge);double q=x[edgeOffset+edge];boolean receiving=node==(q>=0?pipe.second():pipe.first());
                            double factor=receiving&&pipe.filter()!=null?1-graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state().solidMoments().mass()/graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state().mass():1;
                            net+=receiving?Math.abs(q)*factor:-Math.abs(q);
                        }
                        if(!usable||missing<0)continue;
                        var pipe=graph.pipes().get(missing);double q=pipe.first()==node?net:-net;
                        if(node==(q>=0?pipe.second():pipe.first())&&pipe.filter()!=null){var donor=graph.reservoirs().get(q>=0?pipe.first():pipe.second()).state();q/=Math.max(1e-12,1-donor.solidMoments().mass()/donor.mass());}
                        if(boundaryAllowed(graph,pipe,q)){x[edgeOffset+missing]=q;known[missing]=true;changed=true;}
                    }
                    if(!changed)break;
                }
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
        /** Brings the per-node decode cache up to {@code x}; the residual reads the arrays directly. */
        private void decode(double[] x) {
            for(int i=0;i<layout.length;i++) {
                boolean same=cachedStates[i]!=null&&(layout[i]==null||Arrays.mismatch(x,offsets[i],offsets[i]+layout[i].size(),cachedVariables[i],0,cachedVariables[i].length)<0);
                if(same)continue;
                if(layout[i]!=null) {
                    double temperature=layout[i].temperature(x,offsets[i]);
                    if(cachedPrepared[i]==null||cachedPrepared[i].temperature()!=temperature)cachedPrepared[i]=model.prepare(temperature);
                }
                var state=layout[i]==null?graph.reservoirs().get(i).state():layout[i].decode(x,offsets[i],cachedPrepared[i]);
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
                for(int c=0;c<3;c++){if(!receiver||pipe.filter()==null)solidTargets[node][c]+=(first?-1:1)*moving*moments[c]/upstream.mass();if(receiver&&pipe.filter()==null)solidIncoming[node][c]+=Math.abs(flow)*moments[c]/upstream.mass();}
                double donorZ=graph.reservoirs().get(donor).elevation(),h=transport.specificEnthalpy;
                if(receiver) {
                    mass+=deliveredFlow;
                    for(int c=0;c<amounts.length;c++)in[c]+=Math.abs(flow)*amounts[c]/upstream.mass();
                    heat+=Math.abs(flow)*(h+GRAVITY*(donorZ-elevation));
                }
                stored+=(first?-1:1)*moving*(h+GRAVITY*(donorZ-elevation));
                if(receiver&&pipe.filter()!=null){double capturedRate=Math.abs(flow)*(upstream.solidMoments().enthalpy(upstream.temperature(),upstream.pressure())/upstream.mass()+captureFraction*GRAVITY*(donorZ-elevation));stored-=dt*capturedRate;heat-=capturedRate;}
                if(!first&&pipe.control() instanceof FlowControl.Pump pump) {
                    int control=controlOffsets[edge];double head=control<0?0:x[control]*1e5;
                    double power=Math.max(0,flow)/(st[a].mass()/st[a].volume())*Math.max(0,head)/pump.efficiency();
                    stored+=dt*power;heat+=power;
                }
            }
            energy[node]=stored;incomingMass[node]=mass;incomingEnergy[node]=heat;netMass[node]=net;
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
            double driving=st[a].pressure()-st[b].pressure()-rho*GRAVITY*dz+signedHead;
            f[edgeOffset+edge]=(driving-loss)/1e5;
            if(canClamp(modes.get(edge))&&!boundaryClosed[edge]) {
                int direction=flow>=0?0:1;
                // A colored Jacobian perturbs only a few nodes. Unchanged immutable donor
                // properties have the same cap and loss; keep one entry per edge/direction.
                if(pipe.filter()!=null||capSources[edge][direction]!=transport) {
                    double limit=rho*pipe.minimumArea()*transport.velocityLimit;
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
                    capMassFlows[edge][direction]=limit;capPressureDrops[edge][direction]=pipe.filter()==null?pipe.pressureDrop(limit,rho,transport.viscosity):filterCoefficient*limit;capSources[edge][direction]=transport;
                }
                double limit=capMassFlows[edge][direction],limitDrop=capPressureDrops[edge][direction];
                // A saturated pressure/flow law: the unused driving pressure is throttled.
                // The same bounded flow unknown enters every component and enthalpy balance.
                // No post-solve clipping, temperature prescription, or inventory adjustment.
                if(Math.abs(driving)>limitDrop)f[edgeOffset+edge]=(Math.copySign(limit,driving)-flow)/Math.max(limit,1e-8);
            }
            if(boundaryClosed[edge]&&control<0)f[edgeOffset+edge]=flow;
            if(control>=0)f[control]=switch(modes.get(edge)) {
                case PUMP_TARGET->(flow/(st[a].mass()/st[a].volume())-((FlowControl.Pump)pipe.control()).targetVolumeFlow())/.01;
                case PUMP_HEAD_LIMIT->(head-((FlowControl.Pump)pipe.control()).maximumAddedPressure())/1e5;
                case VALVE_REGULATING->(st[a].pressure()-((FlowControl.PressureValve)pipe.control()).targetPressure())/1e5;
                case VALVE_OPEN->head/1e5;
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
            else layout[node].junctionResidual(st[node],fractions,junctionInflow(node),netMass[node],f,offsets[node],x,pr[node]);
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
            else layout[node].junctionRows(st[node],fractions,junctionInflow(node),netMass[node],f,offsets[node],x);
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
            var solids=solidIncoming[node].clone();if(incomingMass[node]>1e-14){for(int c=0;c<3;c++)solids[c]/=incomingMass[node];}else{solids=graph.reservoirs().get(node).state().solidMoments().values();for(int c=0;c<3;c++)solids[c]/=graph.reservoirs().get(node).state().mass();}
            layout[node].solidRows(st[node],x,offsets[node],solids,true,f);
        }
        /** Fills {@link #fractions} with this junction's incoming mass fractions and returns its
         * incoming specific enthalpy, falling back to the stored guess when nothing arrives. */
        private double junctionInflow(int node) {
            var previous=graph.reservoirs().get(node).state();
            if(incomingMass[node]>1e-14) {
                for(int c=0;c<fractions.length;c++)fractions[c]=incoming[node][c]*model.molecularWeight(c)/incomingMass[node];
                return incomingEnergy[node]/incomingMass[node];
            }
            for(int c=0;c<fractions.length;c++)fractions[c]=oldAmounts[node][c]*model.molecularWeight(c)/previous.mass();
            return previous.enthalpy()/previous.mass();
        }
        /** Whether this edge can carry a change of {@code node}'s decoded state into the other
         * endpoint's rows: the transported amounts and enthalpy are the donor's, and a pump's
         * shaft power is metered on the first endpoint's density. */
        private boolean carries(int edge,int node,double[] x) {
            var pipe=graph.pipes().get(edge);
            return node==(x[edgeOffset+edge]>=0?pipe.first():pipe.second())
                    ||node==pipe.first()&&pipe.control() instanceof FlowControl.Pump;
        }
    }
}

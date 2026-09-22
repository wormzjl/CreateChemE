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
        var reachable=reachableComponents(graph,null);
        boolean hasJunction=false;
        for(var node:graph.reservoirs())hasJunction|=node.junction();
        var seeds=initialPhaseSeeds(graph,dt,checkpoint,reachable);
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
            double margin=pump.maximumAddedPressure()-(b.state().pressure()-a.state().pressure()
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
        closeDeadHeads(graph,boundaryClosed);
        var seen=new HashSet<WorkspaceKey>();
        // Constant for this solve: every pass keys on the same graph identity and component support.
        var pipeIdentities=identities(graph);
        long[] nodeIds=nodeIds(graph);byte[] kinds=new byte[nodeIds.length];
        for(int i=0;i<kinds.length;i++)kinds[i]=(byte)graph.reservoirs().get(i).kind().ordinal();
        boolean[] componentMask=componentMask(graph);
        // The connection directions every zero-holdup junction's basis and seed are stated from:
        // the point a pass with this active set starts at, which is the same array
        // {@link Equations#junctionDonorFirst} is read off, so the basis, the seed and the donor
        // are three readings of one thing and cannot disagree. A pass whose own starting point
        // carries differently - because an active-set change moved the flows it starts from -
        // restates all three and runs again, and that is the only place {@code reachable} and the
        // junction seeds are written.
        double[] stated=null;
        // Components this solve has reactivated, per node. Reactivation is monotone within one solve
        // - a later pass's seed may not undo it - which is what keeps the support half of the cycle
        // key monotone and the pass sequence finite; demotion happens at the next solve's seed.
        boolean[][] promoted=new boolean[seeds.size()][];
        int maximumPasses=Math.min(512,16+2*graph.reservoirs().size()+2*graph.pipes().size());
        for(int pass=0;pass<maximumPasses;pass++) {
            checkpoint.run();SolverDiagnostics.count(SolverDiagnostics.activeSetPasses);
            // A zero-holdup junction owns no volume, so its composition is nothing but the mixture
            // of what arrives: the species it carries are the ones the connections this pass reads
            // as donating into it deliver, and its seed is that mixture. Both are stated here,
            // from the point this pass starts at, which is the same array
            // {@link Equations#junctionDonorFirst} is read off - so the basis, the seed and the
            // donor cannot disagree - and both are revised only by a converged point, through
            // {@link #junctionsTurned} and {@link #donorsTurned}, which move the flows this pass
            // will start from and so restate all three on the pass after.
            if(hasJunction) {
                double[] start=new double[graph.pipes().size()];
                startPoint(graph,modes,boundaryClosed,pipeIdentities,seeds,start,new double[start.length],new boolean[start.length]);
                if(!sameTransport(stated,start)) {
                    refineJunctionReachability(graph,reachable,start);
                    seeds=restateJunctions(graph,seeds,reachable,start,checkpoint);stated=start;
                }
            }
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
                if(changedSeeds==null)throw new SparseNewton.Nonconvergence(failure.getMessage()+"; active-set pass="+pass,failure.lastVariables());
                seeds=changedSeeds;continue;
            }
            double[] x=numerical.variables();var states=equations.states(x);double[] flows=new double[graph.pipes().size()],heads=new double[flows.length];
            var changedSeeds=phaseCorrection(graph,states,checkpoint,true);if(changedSeeds!=null){seeds=changedSeeds;continue;}
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
                    double margin=pump.maximumAddedPressure()-demand(graph,pipe,states,rho);
                    double band=shutoffBand(equations.pressureScales[i],tolerance);
                    if(pump.targetVolumeFlow()==0)next=FlowControl.Mode.CLOSED;
                    else if(mode==FlowControl.Mode.PUMP_TARGET&&pump.targetVolumeFlow()>pipe.minimumArea()*model.velocityLimit(up)*(1+1e-8))next=FlowControl.Mode.PUMP_HEAD_LIMIT;
                    else if(mode==FlowControl.Mode.PUMP_TARGET&&heads[i]>pump.maximumAddedPressure()+.01)next=FlowControl.Mode.PUMP_HEAD_LIMIT;
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
            // {@link ConservativeTransport#JUNCTION_INFLOW_FLOOR} discontinuities, where the Newton
            // cannot converge at any step size. Deciding the device first leaves the boundary
            // closure to a later pass, where it is applied only if the direction is still illegal
            // once every device runs in a mode it can actually hold.
            if(!changed&&illegalDirection>=0) {
                var pipe=graph.pipes().get(illegalDirection);boundaryClosed[illegalDirection]=true;
                if(!(pipe.control() instanceof FlowControl.Passive))modes.set(illegalDirection,FlowControl.Mode.CLOSED);
                changed=true;
            }
            // A zero-holdup junction mixed on the donor this pass froze; see
            // {@link Equations#junctionDonorFirst}. A converged point that turned one of those
            // connections around was solved against the wrong neighbour's composition and
            // enthalpy, so it is an active-set change like any other: the next pass starts from
            // these flows, derives the turned donor from them, and the donor signs are part of the
            // cycle key, so the sequence is finite.
            if(!changed&&donorsTurned(graph,equations,flows))changed=true;
            // And the composition basis those same donors state; see
            // {@link #refineJunctionReachability}. A converged point whose connection directions
            // are not the ones this pass mixed its junctions on was solved against the wrong
            // species set, which is the same kind of evidence and takes the same action: the next
            // pass starts from these flows and restates both the donors and the basis from them.
            if(!changed&&hasJunction&&junctionsTurned(graph,stated,reachable,flows))changed=true;
            if(changed){seeds=states;previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousFlows=flows.clone();previousHeads=heads.clone();previousModes=List.copyOf(modes);continue;}
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
            if(acceptance==Acceptance.APPROXIMATE)checkApproximation(equations,reconstructed,projection.states(),flows,workspace,checkpoint,tolerance);
            checkConservation(graph,projection);
            previousPipes=pipeIdentities;previousNodeIds=nodeIds;previousFlows=flows.clone();previousHeads=heads.clone();previousModes=List.copyOf(modes);
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
                if(q<-floor||mode!=FlowControl.Mode.CLOSED&&head>pump.maximumAddedPressure()+.01||mode==FlowControl.Mode.CLOSED&&pump.targetVolumeFlow()>0&&head<pump.maximumAddedPressure()-band)
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
    /**
     * Whether any connection into a zero-holdup junction points the other way from the donor this
     * pass mixed that junction on; see {@link Equations#junctionDonorFirst}.
     *
     * <p>It is the same question the mode tests ask of a device: the pass stated its equations
     * against one active set and the point it reached says another. A connection with no junction
     * at either end is not asked, because nothing in its rows reads a frozen donor.
     */
    private static boolean donorsTurned(PassiveNetwork graph,Equations equations,double[] flows) {
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=graph.pipes().get(edge);
            if(!graph.reservoirs().get(pipe.first()).junction()&&!graph.reservoirs().get(pipe.second()).junction())continue;
            if(equations.junctionDonorFirst[edge]!=flows[edge]>=0)return true;
        }
        return false;
    }
    /**
     * Whether this converged point's connection directions are not the ones the junction basis of
     * this pass was stated from; the basis twin of {@link #donorsTurned}.
     *
     * <p>The question is about the basis and not about the flows. {@link #reachableComponents}
     * reads a flow only through {@link #transportDirection}, and the rest of the closure - which
     * ends a connection admits, which nodes hold stock, what an isolated junction falls back to -
     * is a property of the graph and of the states this solve starts from, neither of which a pass
     * can change. So two points that classify every connection the same way cannot produce
     * different bases, and that cheap test is the screen; the converse does not hold - a
     * connection crossing the deadband usually leaves the basis exactly where it was - so when the
     * screen fires the closure is built and the bases are compared. Answering on the
     * classification alone reports a change that restates nothing, and the pass after it rebuilds
     * the same active set and trips the cycle guard.
     */
    private boolean junctionsTurned(PassiveNetwork graph,double[] stated,boolean[][] reachable,double[] flows) {
        if(sameTransport(stated,flows))return false;
        var directed=reachableComponents(graph,flows);
        for(int i=0;i<reachable.length;i++)
            if(graph.reservoirs().get(i).junction()&&!Arrays.equals(reachable[i],directed[i]))return true;
        return false;
    }
    /** Which way {@link #reachableComponents} lets species cross a connection carrying this flow:
     * downstream, upstream, or neither, which is what a flow inside the deadband means. */
    private static int transportDirection(double flow){return flow>1e-14?1:flow<-1e-14?-1:0;}
    /** Whether two points carry every connection the same way, which is all a junction basis and a
     * junction seed read a flow for. {@code null} is no statement at all and matches nothing. */
    private static boolean sameTransport(double[] stated,double[] flows) {
        if(stated==null)return false;
        for(int edge=0;edge<flows.length;edge++)if(transportDirection(stated[edge])!=transportDirection(flows[edge]))return false;
        return true;
    }
    /**
     * Restates every zero-holdup junction's composition basis as what these connection directions
     * deliver into it, and reports whether any of them moved.
     *
     * <p>The undirected closure {@link #reachableComponents} runs with no directions is the right
     * answer for a vessel, which owns stock and may be fed either way over a whole step. It is the
     * wrong answer for a junction, which owns nothing: a species no connection is currently
     * carrying into it is not in its mixture, and seeding it there anyway costs the junction a
     * phase - and its rows - that its own equations cannot determine, because a junction has no
     * volume closure to fix a phase amount with. That is the defect
     * {@code documentation/JUNCTION_PHANTOM_TRACE.md} measures.
     */
    private boolean refineJunctionReachability(PassiveNetwork graph,boolean[][] reachable,double[] flows){
        var directed=reachableComponents(graph,flows);boolean changed=false;
        for(int i=0;i<reachable.length;i++)if(graph.reservoirs().get(i).junction()&&!Arrays.equals(reachable[i],directed[i])){reachable[i]=directed[i];changed=true;}
        return changed;
    }
    /**
     * A trial point for one pass, per node.
     *
     * <p>A vessel is seeded as it always was: its own inventory, plus a quarter of its mass at
     * most of what is about to arrive, which is a correction to a stock it already owns. A
     * zero-holdup junction is seeded by {@link #restateJunctions} instead, because it owns no
     * stock and that model does not describe it.
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
            seeds.add(changed?model.flashTP(weightedTemperature/seedMass,state.pressure(),n,checkpoint).withSolidState(state.solids(),state.solidMoments()):state);
        }
        return seeds;
    }
    /**
     * The same seeds, with every zero-holdup junction's rewritten as the mixture the given
     * connection flows deliver into it.
     *
     * <p>A junction owns no stock, so its converged composition <em>is</em> that mixture and not
     * its stored guess with a step's worth of inflow blended in; on a settled line the two are
     * orders of magnitude apart, and a Newton cannot travel between them, because the amount of a
     * species the mixture does not contain has its root at exactly zero and the nonnegativity
     * limiter takes one percent of the distance to it per iteration. The sweep runs along the
     * donor chain, so a junction fed by another junction sees what that one is about to hold
     * rather than what it happens to be holding now.
     *
     * <p>Nothing invents a trace on a junction. A species no donor delivers is simply not there,
     * which is what {@link #refineJunctionReachability} has already said about its basis, and
     * {@link PhaseLayout} then carries it as an omitted trace in the mask; on a vessel the
     * {@code 1e-12} entry trace is unchanged, because a vessel has a volume closure that fixes the
     * amount of the phase the trace opens and a junction has none. That pair is the defect
     * {@code documentation/JUNCTION_PHANTOM_TRACE.md} measures.
     */
    private List<FluidThermodynamics.State> restateJunctions(PassiveNetwork graph,List<FluidThermodynamics.State> seeds,
                                                            boolean[][] reachable,double[] flows,Runnable checkpoint) {
        int count=model.hydrocarbon.componentCount()+1,nodes=graph.reservoirs().size(),junctions=0;
        for(var node:graph.reservoirs())if(node.junction())junctions++;
        if(junctions==0)return seeds;
        var amounts=new double[nodes][];double[] temperature=new double[nodes],stock=new double[nodes];
        for(int i=0;i<nodes;i++){var s=seeds.get(i);amounts[i]=PhaseLayout.totalAmounts(s);temperature[i]=s.temperature();stock[i]=PhaseLayout.sum(amounts[i]);}
        boolean[] mixed=new boolean[nodes];
        // One sweep per junction resolves any chain of them; a loop of junctions reaches its own
        // fixed point or stops on the bound, and either is a trial guess.
        for(int sweep=0;sweep<junctions;sweep++) {
            boolean moved=false;
            for(int j=0;j<nodes;j++) {
                if(!graph.reservoirs().get(j).junction())continue;
                double[] n=new double[count];double weightedTemperature=0,weight=0;
                for(int edge=0;edge<flows.length;edge++) {
                    int carried=transportDirection(flows[edge]);if(carried==0)continue;
                    var pipe=graph.pipes().get(edge);
                    int donor=carried>0?pipe.first():pipe.second();
                    if((carried>0?pipe.second():pipe.first())!=j||!boundaryAllowed(graph,pipe,flows[edge]))continue;
                    double rate=Math.abs(flows[edge]),donorMass=molarMass(amounts[donor]);
                    if(!(donorMass>0))continue;
                    for(int c=0;c<count;c++)n[c]+=rate*amounts[donor][c]/donorMass;
                    weightedTemperature+=rate*temperature[donor];weight+=rate;
                }
                for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection input&&input.node()==j) {
                    var rates=input.molesPerSecond();double rate=molarMass(rates);if(!(rate>0))continue;
                    for(int c=0;c<count;c++)n[c]+=rates[c];
                    weightedTemperature+=rate*temperature[j];weight+=rate;
                }
                double arriving=PhaseLayout.sum(n);
                if(!(weight>0)||!(arriving>0)) {
                    // Nothing delivers into it, so it keeps the guess it has - minus any species
                    // its own basis no longer names, which only a zero-storage guess may drop.
                    boolean dropped=false;
                    for(int c=0;c<count;c++)if(!reachable[j][c]&&amounts[j][c]>0){amounts[j][c]=0;dropped=true;}
                    if(dropped){mixed[j]=true;moved=true;}
                    continue;
                }
                for(int c=0;c<count;c++)n[c]=reachable[j][c]?n[c]*stock[j]/arriving:0;
                // The guess it already holds is the mixture it held when it was last reconstructed,
                // so it is replaced only when the species arriving are not the species it carries -
                // which is the whole question a junction's layout is built on, and the one case
                // where its own guess is orders away from what it has to converge to. Matching the
                // species and keeping the guess leaves a junction on a settled line exactly where
                // it always was, unflashed.
                if(sameSpecies(n,amounts[j]))continue;
                amounts[j]=n;temperature[j]=weightedTemperature/weight;mixed[j]=true;moved=true;
            }
            if(!moved)break;
        }
        var restated=new ArrayList<>(seeds);
        for(int j=0;j<nodes;j++)if(mixed[j]) {
            var stored=graph.reservoirs().get(j).state();
            restated.set(j,model.flashTP(temperature[j],stored.pressure(),amounts[j],checkpoint).withSolidState(stored.solids(),stored.solidMoments()));
        }
        return restated;
    }
    /** Whether two conserved amount vectors carry exactly the same species, at any amounts. */
    private static boolean sameSpecies(double[] a,double[] b) {
        for(int c=0;c<a.length;c++)if(a[c]>0!=b[c]>0)return false;
        return true;
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
     * the solve and not an estimate, and a converged point still restates it through
     * {@link #junctionsTurned}.
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
     * lay it into its own initial point, and once - before the equations exist - to state every
     * zero-holdup junction's composition basis and seed from the same connection directions
     * {@link Equations#junctionDonorFirst} is read off. Three readings of one array cannot
     * disagree, which is the property the junction rules in {@link #solve} rest on.
     *
     * <p>{@code seeds} may be {@code null} before this solve has any, which only a regulating
     * valve reads, and no pass can start in that mode.
     */
    private void startPoint(PassiveNetwork graph,List<FlowControl.Mode> modes,boolean[] boundaryClosed,
                            List<PassiveNetwork.Pipe.Identity> pipeIdentities,List<FluidThermodynamics.State> seeds,
                            double[] flows,double[] heads,boolean[] headSet) {
        boolean warmFlow=graph.reservoirs().stream().anyMatch(node->node.junction()||node.fixed())
                ||graph.pipes().stream().anyMatch(pipe->phaseCode(graph.reservoirs().get(pipe.first()).state())!=phaseCode(graph.reservoirs().get(pipe.second()).state()));
        boolean previousAvailable=previousPipes.equals(pipeIdentities)&&Arrays.equals(previousNodeIds,nodeIds(graph));
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
            if(modes.get(i)==FlowControl.Mode.PUMP_HEAD_LIMIT&&pipe.control() instanceof FlowControl.Pump pump
                    &&!(previousAvailable&&previousHeads[i]<=pump.maximumAddedPressure())) {
                flows[i]=headLimitMassFlow(graph,pipe,pump);
                heads[i]=pump.maximumAddedPressure()/1e5;headSet[i]=true;
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
        double driving=a.state().pressure()-b.state().pressure()-rho*GRAVITY*(b.elevation()-a.elevation())+pump.maximumAddedPressure();
        if(driving<=0)return 0;
        double lo=0,hi=1;while(pipe.pressureDrop(hi,rho,mu)<driving&&hi<1e6)hi*=2;
        for(int j=0;j<50;j++){double mid=(lo+hi)/2;if(pipe.pressureDrop(mid,rho,mu)>driving)hi=mid;else lo=mid;}
        return Math.min((lo+hi)/2,Math.min(massFlowLimit(pipe,a.state()),pump.targetVolumeFlow()*rho));
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
     * {@link ConservativeTransport#JUNCTION_INFLOW_FLOOR} discontinuities.
     */
    private static double shutoffBand(double pressureScale,double tolerance) {
        return Math.max(.01,tolerance*Math.max(1e5,pressureScale));
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
    private static void closeDeadHeads(PassiveNetwork graph,boolean[] boundaryClosed) {
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
            if(!forward&&!reverse)for(int link:chain)boundaryClosed[link]=true;
        }
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
                for(int c=0;c<3;c++)if(!receiver||pipe.filter()==null)solidTargets[node][c]+=(first?-1:1)*moving*moments[c]/upstream.mass();
                double donorZ=graph.reservoirs().get(donor).elevation(),h=transport.specificEnthalpy;
                stored+=(first?-1:1)*moving*(h+GRAVITY*(donorZ-elevation));
                // The mixture this node would hold if it holds nothing, on the donor this pass was
                // solved under rather than on the live iterate's flow sign; see
                // {@link #junctionDonorFirst}. Read only by the junction rows.
                int mixed=junctionDonorFirst[edge]?a:b;
                if(node==(junctionDonorFirst[edge]?b:a)) {
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
            double driving=st[a].pressure()-st[b].pressure()-headDensities[edge]*GRAVITY*dz+signedHead;
            // Every pressure-dimensioned row of this edge is stated in the island's own pressure,
            // never in a fixed 1e5 Pa unit; see {@link #pressureScales}. The throttled-inlet row
            // below and the closed-edge row are mass flows and keep their own flow scale, and the
            // pump's target row is a volume flow.
            double pressureScale=pressureScales[edge];
            f[edgeOffset+edge]=(driving-loss)/pressureScale;
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
                case PUMP_HEAD_LIMIT->(head-((FlowControl.Pump)pipe.control()).maximumAddedPressure())/pressureScale;
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
            else layout[node].junctionResidual(st[node],fractions,junctionInflow(node),netMass[node],retainedPressures[node],f,offsets[node],x,pr[node]);
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
            else layout[node].junctionRows(st[node],fractions,junctionInflow(node),netMass[node],retainedPressures[node],f,offsets[node],x);
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
            var solids=solidIncoming[node].clone();if(incomingMass[node]>ConservativeTransport.JUNCTION_INFLOW_FLOOR){for(int c=0;c<3;c++)solids[c]/=incomingMass[node];}else{solids=graph.reservoirs().get(node).state().solidMoments().values();for(int c=0;c<3;c++)solids[c]/=graph.reservoirs().get(node).state().mass();}
            layout[node].solidRows(st[node],x,offsets[node],solids,true,f);
        }
        /** Fills {@link #fractions} with this junction's incoming mass fractions and returns its
         * incoming specific enthalpy, falling back to the stored guess when nothing arrives. The
         * accumulators it reads are the frozen donors'; see {@link #junctionDonorFirst}. */
        private double junctionInflow(int node) {
            var previous=graph.reservoirs().get(node).state();
            if(incomingMass[node]>ConservativeTransport.JUNCTION_INFLOW_FLOOR) {
                for(int c=0;c<fractions.length;c++)fractions[c]=incoming[node][c]*model.molecularWeight(c)/incomingMass[node];
                return incomingEnergy[node]/incomingMass[node];
            }
            for(int c=0;c<fractions.length;c++)fractions[c]=oldAmounts[node][c]*model.molecularWeight(c)/previous.mass();
            return previous.enthalpy()/previous.mass();
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

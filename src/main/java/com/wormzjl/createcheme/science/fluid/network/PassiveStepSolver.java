package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.linalg.SparseLuSolver;
import com.wormzjl.createcheme.science.fluid.solver.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** One simultaneous backward-Euler step with a fixed phase regime; no nested TP/UV/PH flash. */
public final class PassiveStepSolver {
    public enum Acceptance { FULL, APPROXIMATE }
    public static final double GRAVITY=9.80665;
    private final FluidThermodynamics model;
    private final SolverOwnership ownership;
    private final Map<WorkspaceKey,SparseNewton.Workspace> workspaces=new LinkedHashMap<>();
    private final Map<WorkspaceKey,SparseNewton.Workspace> structures=new LinkedHashMap<>();
    private List<PassiveNetwork.Pipe> previousPipes=List.of();
    private List<Long> previousNodeIds=List.of();
    private double[] previousFlows=new double[0],previousHeads=new double[0];
    private Equations lastEquations;
    private double[] lastVariables;
    private SparseNewton.Workspace lastWorkspace;
    public PassiveStepSolver(FluidThermodynamics model){this(model,SolverOwnership.confinedToCurrentThread());}
    public PassiveStepSolver(FluidThermodynamics model,SolverOwnership ownership) {
        this.model=Objects.requireNonNull(model);this.ownership=Objects.requireNonNull(ownership);
    }
    public record Result(List<FluidThermodynamics.State> states,double[] massFlows,double deltaTime,SparseNewton.Result numerical,
                         List<FlowControl.Mode> modes,double[] devicePressureChanges,double pumpWorkJoule,double[] externalMoles,double externalEnergyJoule,
                         List<PassiveNetwork.Inventory> inventories,List<ConservativeTransport.BoundaryTransfer> boundaries,List<PipeTransfer> pipeTransfers) {
        public Result {states=List.copyOf(states);massFlows=massFlows.clone();modes=List.copyOf(modes);devicePressureChanges=devicePressureChanges.clone();externalMoles=externalMoles.clone();inventories=List.copyOf(inventories);boundaries=List.copyOf(boundaries);pipeTransfers=List.copyOf(pipeTransfers);}
        @Override public double[] massFlows(){return massFlows.clone();}
        @Override public double[] devicePressureChanges(){return devicePressureChanges.clone();}
        @Override public double[] externalMoles(){return externalMoles.clone();}
    }
    public Result solve(PassiveNetwork graph,double dt,Runnable checkpoint) {
        return solve(graph,dt,checkpoint,Acceptance.FULL);
    }
    public Result solve(PassiveNetwork graph,double dt,Runnable checkpoint,Acceptance acceptance) {
        Objects.requireNonNull(acceptance);SolverDiagnostics.count(SolverDiagnostics.implicitSolves);
        ownership.check("Each executing island job needs its own step workspace");
        lastEquations=null;lastVariables=null;lastWorkspace=null;
        if(!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Positive finite substep required");
        if(graph.pipes().isEmpty()&&graph.scheduledTransfers().isEmpty())return new Result(graph.reservoirs().stream().map(PassiveNetwork.Reservoir::state).toList(),new double[0],dt,
                new SparseNewton.Result(new double[0],0,0,0,0,0),List.of(),new double[0],0,new double[model.hydrocarbon.componentCount()+1],0,
                graph.reservoirs().stream().map(PassiveNetwork.Reservoir::inventory).toList(),List.of(),List.of());
        if(graph.reservoirs().stream().anyMatch(PassiveNetwork.Reservoir::empty))throw new IllegalArgumentException("Evacuated reservoir has no fluid temperature; connected filling requires a supported initialization state");
        var seeds=initialPhaseSeeds(graph,dt,checkpoint);
        var modes=new ArrayList<FlowControl.Mode>();
        for(var pipe:graph.pipes())modes.add(switch(pipe.control()) {
            case FlowControl.Passive ignored->FlowControl.Mode.PASSIVE;
            case FlowControl.Pump pump->pump.targetVolumeFlow()==0?FlowControl.Mode.CLOSED:
                    pump.targetVolumeFlow()>pipe.minimumArea()*model.velocityLimit(graph.reservoirs().get(pipe.first()).state())?FlowControl.Mode.PUMP_HEAD_LIMIT:FlowControl.Mode.PUMP_TARGET;
            case FlowControl.PressureValve valve->graph.reservoirs().get(pipe.first()).state().pressure()>valve.targetPressure()+.01
                    ?FlowControl.Mode.VALVE_OPEN:FlowControl.Mode.CLOSED;
        });
        boolean[] boundaryClosed=new boolean[graph.pipes().size()];var seen=new HashSet<String>();
        int maximumPasses=Math.min(512,16+2*graph.reservoirs().size()+2*graph.pipes().size());
        for(int pass=0;pass<maximumPasses;pass++) {
            checkpoint.run();SolverDiagnostics.count(SolverDiagnostics.activeSetPasses);
            if(!seen.add(modes.toString()+Arrays.toString(boundaryClosed)+seeds.stream().map(PassiveStepSolver::phaseSignature).toList()))throw new SparseNewton.Nonconvergence("Phase/device active-set cycle");
            var equations=new Equations(graph,dt,modes,boundaryClosed,seeds);
            var key=new WorkspaceKey(Double.doubleToLongBits(dt),graph.reservoirs().stream().map(PassiveNetwork.Reservoir::id).toList(),
                    graph.reservoirs().stream().map(PassiveNetwork.Reservoir::kind).toList(),graph.pipes(),seeds.stream().map(PassiveStepSolver::phaseSignature).toList(),
                    equations.componentMask,List.copyOf(modes),Arrays.toString(boundaryClosed));
            var workspace=workspaces.get(key);
            var structure=new WorkspaceKey(0,key.nodeIds,key.kinds,key.pipes,key.phases,key.componentMask,key.modes,key.boundaryClosed);
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
                var changedSeeds=phaseCorrection(graph,equations.states(failure.lastVariables()),checkpoint,false);
                if(changedSeeds==null)throw new SparseNewton.Nonconvergence(failure.getMessage()+"; active-set pass="+pass,failure.lastVariables());
                seeds=changedSeeds;continue;
            }
            double[] x=numerical.variables();var states=equations.states(x);double[] flows=new double[graph.pipes().size()],heads=new double[flows.length];
            var changedSeeds=phaseCorrection(graph,states,checkpoint,true);if(changedSeeds!=null){seeds=changedSeeds;continue;}
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
            if(changed){seeds=states;previousPipes=graph.pipes();previousNodeIds=graph.reservoirs().stream().map(PassiveNetwork.Reservoir::id).toList();previousFlows=flows.clone();previousHeads=heads.clone();continue;}
            for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);var upstream=states.get(flows[edge]>=0?pipe.first():pipe.second());
                if(Math.abs(flows[edge])>massFlowLimit(pipe,upstream)*(1+2e-8)+1e-12)throw new SparseNewton.Nonconvergence("Velocity constraint did not close");
            }
            var projection=ConservativeTransport.reconstruct(graph,states,flows,heads,dt,model,checkpoint);
            changedSeeds=phaseCorrection(graph,projection.states(),checkpoint,true);if(changedSeeds!=null){seeds=changedSeeds;continue;}
            var reconstructed=x.clone();
            for(int node=0;node<projection.states().size();node++)if(equations.layout[node]!=null){var encoded=equations.layout[node].encode(projection.states().get(node));System.arraycopy(encoded,0,reconstructed,equations.offsets[node],encoded.length);}
            double maximumResidual=0;for(double residual:equations.residual(reconstructed))maximumResidual=Math.max(maximumResidual,Math.abs(residual));
            if(maximumResidual>(acceptance==Acceptance.FULL?1e-8:1e-6))throw new SparseNewton.Nonconvergence("Conservative reconstruction fails equation gate: "+maximumResidual);
            if(acceptance==Acceptance.APPROXIMATE)checkApproximation(equations,reconstructed,projection.states(),flows,workspace,checkpoint);
            checkConservation(graph,projection);
            previousPipes=graph.pipes();previousNodeIds=graph.reservoirs().stream().map(PassiveNetwork.Reservoir::id).toList();previousFlows=flows.clone();previousHeads=heads.clone();
            var acceptedModes=new ArrayList<>(modes);
            for(int edge=0;edge<flows.length;edge++) {
                var pipe=graph.pipes().get(edge);
                if(boundaryClosed[edge]&&pipe.control() instanceof FlowControl.Passive)acceptedModes.set(edge,FlowControl.Mode.CLOSED);
                else if(canClamp(modes.get(edge))&&Math.abs(flows[edge])>=massFlowLimit(pipe,projection.states().get(flows[edge]>=0?pipe.first():pipe.second()))*(1-1e-7)) {
                    acceptedModes.set(edge,switch(pipe.control()){case FlowControl.Passive ignored->FlowControl.Mode.VELOCITY_LIMITED;case FlowControl.Pump ignored->FlowControl.Mode.PUMP_VELOCITY_LIMIT;case FlowControl.PressureValve ignored->FlowControl.Mode.VALVE_VELOCITY_LIMIT;});
                }
            }
            lastEquations=equations;lastVariables=x;lastWorkspace=workspace;
            return new Result(projection.states(),flows,dt,numerical,acceptedModes,heads,projection.pumpWork(),projection.externalMoles(),projection.externalEnergy(),projection.inventories(),projection.boundaries(),PipeTransfer.sample(graph,projection.states(),flows,dt));
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
     */
    Companion companion(PassiveNetwork solved,PassiveNetwork corrected,double[][] deltaMoles,double[] deltaEnergy,
                        double[] heads,double dt,Runnable checkpoint) {
        ownership.check("Each executing island job needs its own step workspace");
        var equations=lastEquations;
        if(equations==null||equations.graph!=solved||equations.dt!=dt)return null;
        double[] rows=new double[equations.size];
        for(int node=0;node<equations.layout.length;node++) {
            if(equations.layout[node]==null||solved.reservoirs().get(node).junction())continue;
            equations.layout[node].targetRows(deltaMoles[node],deltaEnergy[node],rows,equations.offsets[node]);
        }
        List<FluidThermodynamics.State> states;double[] flows=new double[solved.pipes().size()];
        try {
            double[] x=SparseNewton.applyFactorization(lastWorkspace,lastVariables,rows);
            if(x==null)return null;
            states=equations.states(x);
            for(int edge=0;edge<flows.length;edge++)flows[edge]=equations.boundaryClosed[edge]||equations.modes.get(edge)==FlowControl.Mode.CLOSED
                    ?0:x[equations.edgeOffset+edge];
            var projection=ConservativeTransport.reconstruct(corrected,states,flows,heads,dt,model,checkpoint);
            return new Companion(projection.states(),flows,projection.boundaries());
        }catch(SparseLuSolver.SolveFailure|SparseNewton.Nonconvergence|IllegalArgumentException outsideTheLinearization){return null;}
    }
    private record WorkspaceKey(long stepBits,List<Long> nodeIds,List<PassiveNetwork.NodeKind> kinds,List<PassiveNetwork.Pipe> pipes,
                                List<String> phases,String componentMask,List<FlowControl.Mode> modes,String boundaryClosed) {}
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
    private List<FluidThermodynamics.State> initialPhaseSeeds(PassiveNetwork graph,double dt,Runnable checkpoint) {
        int count=model.hydrocarbon.componentCount()+1;boolean[] available=new boolean[count];
        for(var node:graph.reservoirs())if(node.kind()!=PassiveNetwork.NodeKind.VOID){var n=node.inventory().moles();for(int i=0;i<count;i++)available[i]|=n[i]>0;}
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection input){var n=input.molesPerSecond();for(int i=0;i<count;i++)available[i]|=n[i]>0;}
        var seeds=new ArrayList<FluidThermodynamics.State>();
        for(int nodeIndex=0;nodeIndex<graph.reservoirs().size();nodeIndex++) {
            var node=graph.reservoirs().get(nodeIndex);
            var state=node.state();if(node.fixed()){seeds.add(state);continue;}
            var n=PhaseLayout.totalAmounts(state);double total=Arrays.stream(n).sum();boolean changed=false;
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
            seeds.add(changed?model.flashTP(weightedTemperature/seedMass,state.pressure(),n,checkpoint):state);
        }
        return seeds;
    }
    private double initialMassFlow(PassiveNetwork graph,PassiveNetwork.Pipe pipe) {
        var a=graph.reservoirs().get(pipe.first());var b=graph.reservoirs().get(pipe.second());
        var upstream=a.state().pressure()>=b.state().pressure()?a.state():b.state();double rho=upstream.mass()/upstream.volume(),mu=viscosity(upstream);
        double driving=a.state().pressure()-b.state().pressure()-rho*GRAVITY*(b.elevation()-a.elevation());
        double lo=0,hi=1;while(pipe.loss(hi,rho,mu).pressureDrop()<Math.abs(driving)&&hi<1e6)hi*=2;
        for(int j=0;j<50;j++){double mid=(lo+hi)/2;if(pipe.loss(mid,rho,mu).pressureDrop()>Math.abs(driving))hi=mid;else lo=mid;}
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
            var equilibrium=model.flashTP(state.temperature(),state.pressure(),PhaseLayout.totalAmounts(state),checkpoint);
            if(!phaseSignature(state).equals(phaseSignature(equilibrium))&&!changed){changed=true;corrected.add(equilibrium);}else corrected.add(state);
        }
        return changed?corrected:null;
    }
    private static String phaseSignature(FluidThermodynamics.State state) {
        return (state.liquidVolume()>0?"L":"")+(state.vaporProperties()!=null?"G":"")+(state.waterLiquid()>0?"W":"")+(state.waterVapor()>0?"S":"");
    }
    private static boolean boundaryAllowed(PassiveNetwork graph,PassiveNetwork.Pipe pipe,double flow) {
        var a=graph.reservoirs().get(pipe.first()).kind();var b=graph.reservoirs().get(pipe.second()).kind();
        return !(flow<0&&(a==PassiveNetwork.NodeKind.GENERATOR||b==PassiveNetwork.NodeKind.VOID)
                ||flow>0&&(b==PassiveNetwork.NodeKind.GENERATOR||a==PassiveNetwork.NodeKind.VOID));
    }
    void checkConservation(PassiveNetwork graph,ConservativeTransport.Projection projection) {
        int count=model.hydrocarbon.componentCount()+1;double[] before=new double[count],after=new double[count],turnover=new double[count];double eb=0,ea=0,energyScale=0;
        for(var boundary:projection.boundaries()){var n=boundary.moles();for(int c=0;c<count;c++)turnover[c]+=Math.abs(n[c]);energyScale+=Math.abs(boundary.totalEnergyJoule());}
        for(int i=0;i<graph.reservoirs().size();i++) {
            var reservoir=graph.reservoirs().get(i);var old=reservoir.inventory();var next=projection.inventories().get(i);
            if(reservoir.junction()||reservoir.fixed())continue;
            var a=old.moles();var b=next.moles();double ma=0,mb=0;
            for(int j=0;j<count;j++){before[j]+=a[j];after[j]+=b[j];double mw=model.molecularWeight(j);ma+=a[j]*mw;mb+=b[j]*mw;}
            eb+=old.internalEnergy()+ma*GRAVITY*reservoir.elevation();ea+=next.internalEnergy()+mb*GRAVITY*reservoir.elevation();
            energyScale+=Math.abs(old.internalEnergy())+Math.abs(ma*GRAVITY*reservoir.elevation());
        }
        var external=projection.externalMoles();
        for(int i=0;i<count;i++)if(Math.abs(before[i]+external[i]-after[i])>1e-10+1e-8*Math.max(turnover[i],Math.max(before[i],after[i])))throw new SparseNewton.Nonconvergence("Component balance failed: "+i);
        if(Math.abs(ea-eb-projection.pumpWork()-projection.externalEnergy())>1e-4+1e-6*(energyScale+Math.abs(projection.pumpWork())))throw new SparseNewton.Nonconvergence("Total energy balance failed");
    }
    private double viscosity(FluidThermodynamics.State s) {
        double value=0;
        if(s.liquidVolume()>0)value+=s.liquidVolume()*model.viscosity.liquid(s.temperature(),s.liquid()).pascalSeconds();
        if(s.waterVolume()>0)value+=s.waterVolume()*model.viscosity.waterLiquid(s.temperature());
        if(s.vaporVolume()>0)value+=s.vaporVolume()*model.viscosity.vapor(s.temperature(),s.vapor(),s.waterVapor());
        return value/s.volume();
    }
    private final class Equations implements SparseNewton.Equations {
        final PassiveNetwork graph;final double dt;final PhaseLayout[] layout;final int[] offsets;
        final int edgeOffset,size;final double[][] oldAmounts;int[][] sparsity;final List<FlowControl.Mode> modes;final int[] controlOffsets;final boolean[] boundaryClosed;final List<FluidThermodynamics.State> seeds;
        final double[][] cachedVariables;final FluidThermodynamics.State[] cachedStates;final Transport[] cachedTransport;
        final Transport[][] capSources;final double[][] capMassFlows,capPressureDrops;
        final com.wormzjl.createcheme.science.fluid.thermo.TranslatedPengRobinson.TemperatureTerms[] cachedTemperatureTerms;
        final String componentMask;
        final boolean[] amountVariables;
        final double[] differenceFloors;
        Equations(PassiveNetwork graph,double dt,List<FlowControl.Mode> modes,boolean[] boundaryClosed,List<FluidThermodynamics.State> seeds) {
            this.modes=List.copyOf(modes);
            this.seeds=List.copyOf(seeds);
            this.boundaryClosed=boundaryClosed.clone();
            this.graph=graph;this.dt=dt;int count=graph.reservoirs().size();layout=new PhaseLayout[count];offsets=new int[count];oldAmounts=new double[count][];
            capSources=new Transport[graph.pipes().size()][2];capMassFlows=new double[graph.pipes().size()][2];capPressureDrops=new double[graph.pipes().size()][2];
            cachedVariables=new double[count][];cachedStates=new FluidThermodynamics.State[count];cachedTransport=new Transport[count];
            cachedTemperatureTerms=new com.wormzjl.createcheme.science.fluid.thermo.TranslatedPengRobinson.TemperatureTerms[count];
            boolean[] mask=new boolean[model.hydrocarbon.componentCount()];
            for(var node:graph.reservoirs())if(node.kind()!=PassiveNetwork.NodeKind.VOID){var amounts=node.inventory().moles();for(int c=0;c<mask.length;c++)mask[c]|=amounts[c]>0;}
            for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection input){var amounts=input.molesPerSecond();for(int c=0;c<mask.length;c++)mask[c]|=amounts[c]>0;}
            componentMask=Arrays.toString(mask);
            int cursor=0;
            for(int i=0;i<count;i++) {
                var state=graph.reservoirs().get(i).state();offsets[i]=cursor;oldAmounts[i]=graph.reservoirs().get(i).inventory().moles();
                if(!graph.reservoirs().get(i).fixed()){layout[i]=new PhaseLayout(model,seeds.get(i),mask,oldAmounts[i]);cursor+=layout[i].size();}
            }
            edgeOffset=cursor;cursor+=graph.pipes().size();controlOffsets=new int[graph.pipes().size()];Arrays.fill(controlOffsets,-1);
            for(int i=0;i<controlOffsets.length;i++)if(!(graph.pipes().get(i).control() instanceof FlowControl.Passive))controlOffsets[i]=cursor++;
            size=cursor;
            amountVariables=new boolean[size];differenceFloors=new double[size];Arrays.fill(differenceFloors,1);
            for(int node=0;node<count;node++)if(layout[node]!=null)for(int local=0;local<layout[node].size();local++) {
                amountVariables[offsets[node]+local]=layout[node].totalAmountVariable(local);differenceFloors[offsets[node]+local]=layout[node].differenceScale(local,0);
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
            for(int c=0;c<edgeOffset;c++)if(amountVariables[c]&&variables[c]>0&&direction[c]<0)alpha=Math.min(alpha,.99*variables[c]/-direction[c]);
            return alpha;
        }
        int nodeSize(int node){return layout[node]==null?0:layout[node].size();}
        public int[][] columnRows(){if(sparsity==null)buildSparsity();return sparsity;}
        double[] initial() {
            double[] x=new double[size];for(int i=0;i<layout.length;i++)if(layout[i]!=null){var encoded=layout[i].encode(seeds.get(i));System.arraycopy(encoded,0,x,offsets[i],encoded.length);}
            boolean warmFlow=graph.reservoirs().stream().anyMatch(node->node.junction()||node.fixed())
                    ||graph.pipes().stream().anyMatch(pipe->!phaseSignature(graph.reservoirs().get(pipe.first()).state()).equals(phaseSignature(graph.reservoirs().get(pipe.second()).state())));
            boolean previousAvailable=previousPipes.equals(graph.pipes())&&previousNodeIds.equals(graph.reservoirs().stream().map(PassiveNetwork.Reservoir::id).toList());
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
                                -pipe.loss(x[edgeOffset+i],rho,viscosity(a.state())).pressureDrop())/1e5;
                    }
                    continue;
                }
                if(!warmFlow)continue;
                x[edgeOffset+i]=initialMassFlow(graph,pipe);
            }
            return x;
        }
        List<FluidThermodynamics.State> states(double[] x) {
            var states=new ArrayList<FluidThermodynamics.State>(layout.length);
            for(int i=0;i<layout.length;i++) {
                boolean same=cachedStates[i]!=null&&(layout[i]==null||Arrays.mismatch(x,offsets[i],offsets[i]+layout[i].size(),cachedVariables[i],0,cachedVariables[i].length)<0);
                if(!same) {
                    if(layout[i]!=null&&layout[i].hasHydrocarbons()) {
                        double temperature=layout[i].temperature(x,offsets[i]);
                        if(cachedTemperatureTerms[i]==null||cachedTemperatureTerms[i].temperature()!=temperature)cachedTemperatureTerms[i]=model.hydrocarbon.temperatureTerms(temperature);
                    }
                    var state=layout[i]==null?graph.reservoirs().get(i).state():layout[i].decode(x,offsets[i],cachedTemperatureTerms[i]);
                    var transport=new Transport(PhaseLayout.totalAmounts(state),state.mass()/state.volume(),viscosity(state),state.enthalpy()/state.mass(),model.velocityLimit(state));
                    cachedStates[i]=state;cachedTransport[i]=transport;
                    if(layout[i]!=null)cachedVariables[i]=Arrays.copyOfRange(x,offsets[i],offsets[i]+layout[i].size());
                }
                states.add(cachedStates[i]);
            }
            return states;
        }
        private record Transport(double[] moles,double density,double viscosity,double specificEnthalpy,double velocityLimit) {}
        public double[] residual(double[] x) {
            var states=states(x);double[][] targets=new double[layout.length][];double[] energy=new double[layout.length],f=new double[size];
            double[][] incoming=new double[layout.length][oldAmounts[0].length];double[] incomingMass=new double[layout.length],incomingEnergy=new double[layout.length],netMass=new double[layout.length];
            for(int i=0;i<layout.length;i++){targets[i]=oldAmounts[i].clone();energy[i]=graph.reservoirs().get(i).inventory().internalEnergy();}
            for(var transfer:graph.scheduledTransfers()) {
                int node=transfer.node();var state=states.get(node);double elevation=graph.reservoirs().get(node).elevation();
                if(transfer instanceof ScheduledTransfer.Withdrawal withdrawal) {
                    double mass=dt*withdrawal.massKgPerSecond();var n=cachedTransport[node].moles;
                    for(int c=0;c<n.length;c++)targets[node][c]-=mass*n[c]/state.mass();
                    energy[node]-=mass*state.enthalpy()/state.mass();
                } else if(transfer instanceof ScheduledTransfer.Injection input) {
                    var rates=input.molesPerSecond();double massRate=0;
                    for(int c=0;c<rates.length;c++){targets[node][c]+=dt*rates[c];massRate+=rates[c]*model.molecularWeight(c);}
                    energy[node]+=dt*(input.totalEnergyPerSecond()-massRate*GRAVITY*elevation);
                }
            }
            for(int edge=0;edge<graph.pipes().size();edge++) {
                var pipe=graph.pipes().get(edge);int a=pipe.first(),b=pipe.second();double flow=x[edgeOffset+edge];
                int donor=flow>=0?a:b;var upstream=states.get(donor);var transport=cachedTransport[donor];double rho=transport.density;
                int receiver=flow>=0?b:a;netMass[a]-=flow;netMass[b]+=flow;
                double dz=graph.reservoirs().get(b).elevation()-graph.reservoirs().get(a).elevation();
                var loss=pipe.loss(flow,rho,transport.viscosity);
                int control=controlOffsets[edge];double head=control<0?0:x[control]*1e5;
                double signedHead=pipe.control() instanceof FlowControl.Pump?head:-head;
                double driving=states.get(a).pressure()-states.get(b).pressure()-rho*GRAVITY*dz+signedHead;
                f[edgeOffset+edge]=(driving-loss.pressureDrop())/1e5;
                if(canClamp(modes.get(edge))&&!boundaryClosed[edge]) {
                    int direction=flow>=0?0:1;
                    // A colored Jacobian perturbs only a few nodes. Unchanged immutable donor
                    // properties have the same cap and loss; keep one entry per edge/direction.
                    if(capSources[edge][direction]!=transport) {
                        double limit=rho*pipe.minimumArea()*transport.velocityLimit;
                        capMassFlows[edge][direction]=limit;capPressureDrops[edge][direction]=pipe.loss(limit,rho,transport.viscosity).pressureDrop();capSources[edge][direction]=transport;
                    }
                    double limit=capMassFlows[edge][direction],limitDrop=capPressureDrops[edge][direction];
                    // A saturated pressure/flow law: the unused driving pressure is throttled.
                    // The same bounded flow unknown enters every component and enthalpy balance.
                    // No post-solve clipping, temperature prescription, or inventory adjustment.
                    if(Math.abs(driving)>limitDrop)f[edgeOffset+edge]=(Math.copySign(limit,driving)-flow)/Math.max(limit,1e-8);
                }
                if(boundaryClosed[edge]&&control<0)f[edgeOffset+edge]=flow;
                if(control>=0)f[control]=switch(modes.get(edge)) {
                    case PUMP_TARGET->(flow/(states.get(a).mass()/states.get(a).volume())-((FlowControl.Pump)pipe.control()).targetVolumeFlow())/.01;
                    case PUMP_HEAD_LIMIT->(head-((FlowControl.Pump)pipe.control()).maximumAddedPressure())/1e5;
                    case VALVE_REGULATING->(states.get(a).pressure()-((FlowControl.PressureValve)pipe.control()).targetPressure())/1e5;
                    case VALVE_OPEN->head/1e5;
                    case CLOSED->flow;
                    case PASSIVE,VELOCITY_LIMITED,PUMP_VELOCITY_LIMIT,VALVE_VELOCITY_LIMIT->throw new IllegalStateException("Presentation-only or passive mode has actuator unknown");
                };
                var amounts=transport.moles;double transfer=dt*flow;
                for(int c=0;c<amounts.length;c++){double moved=transfer*amounts[c]/upstream.mass();targets[a][c]-=moved;targets[b][c]+=moved;}
                double donorZ=graph.reservoirs().get(donor).elevation();double h=transport.specificEnthalpy;
                incomingMass[receiver]+=Math.abs(flow);
                for(int c=0;c<amounts.length;c++)incoming[receiver][c]+=Math.abs(flow)*amounts[c]/upstream.mass();
                incomingEnergy[receiver]+=Math.abs(flow)*(h+GRAVITY*(donorZ-graph.reservoirs().get(receiver).elevation()));
                energy[a]-=transfer*(h+GRAVITY*(donorZ-graph.reservoirs().get(a).elevation()));
                energy[b]+=transfer*(h+GRAVITY*(donorZ-graph.reservoirs().get(b).elevation()));
                if(pipe.control() instanceof FlowControl.Pump pump) {
                    double power=Math.max(0,flow)/(states.get(a).mass()/states.get(a).volume())*Math.max(0,head)/pump.efficiency();
                    energy[b]+=dt*power;incomingEnergy[b]+=power;
                }
            }
            for(int i=0;i<layout.length;i++) {
                if(layout[i]==null)continue;
                if(!graph.reservoirs().get(i).junction())layout[i].residual(states.get(i),targets[i],energy[i],graph.reservoirs().get(i).inventory().volume(),f,offsets[i],x);
                else {
                    var previous=graph.reservoirs().get(i).state();double[] fractions=new double[incoming[i].length];double specificH;
                    if(incomingMass[i]>1e-14) {
                        for(int c=0;c<fractions.length;c++)fractions[c]=incoming[i][c]*model.molecularWeight(c)/incomingMass[i];
                        specificH=incomingEnergy[i]/incomingMass[i];
                    }else {
                        for(int c=0;c<fractions.length;c++)fractions[c]=oldAmounts[i][c]*model.molecularWeight(c)/previous.mass();
                        specificH=previous.enthalpy()/previous.mass();
                    }
                    layout[i].junctionResidual(states.get(i),fractions,specificH,netMass[i],f,offsets[i],x);
                }
            }
            return f;
        }
    }
}

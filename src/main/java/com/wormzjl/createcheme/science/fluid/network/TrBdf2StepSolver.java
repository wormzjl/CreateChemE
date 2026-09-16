package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** Second-order, L-stable TR-BDF2, using two simultaneous implicit stages with equal diagonal coefficient.
 * Inventories are stage right-hand sides, not new physical initializations. No stage may commit independently.
 */
public final class TrBdf2StepSolver {
    /** How the embedded order-three companion defect is filtered. LINEAR applies the stage-two
     * factorization once, which is the standard TR-BDF2 estimate; NONLINEAR re-solves the perturbed
     * step completely, which is what this did before and is still the fallback whenever the linear
     * filter cannot be produced. A switch for qualification runs, never changed in production. */
    enum CompanionFilter { LINEAR, NONLINEAR }
    static volatile CompanionFilter companionFilter=CompanionFilter.LINEAR;
    private static final double GAMMA=2-Math.sqrt(2), ALPHA=GAMMA/2, A=1/(GAMMA*(2-GAMMA));
    private final FluidThermodynamics model;
    private final PassiveStepSolver implicit,algebraic;
    private record RateKey(PassiveNetwork graph,PassiveStepSolver.Acceptance acceptance) {}
    private record Rate(ConservativeTransport.Projection properties,double[] flows,List<FlowControl.Mode> modes) {
        private Rate{flows=flows.clone();modes=List.copyOf(modes);}
    }
    private final Map<RateKey,Rate> endpointRates=new LinkedHashMap<>();
    private final SolverOwnership ownership;
    public TrBdf2StepSolver(FluidThermodynamics model){this(model,SolverOwnership.confinedToCurrentThread());}
    public TrBdf2StepSolver(FluidThermodynamics model,SolverOwnership ownership) {
        this.model=Objects.requireNonNull(model);this.ownership=Objects.requireNonNull(ownership);
        implicit=new PassiveStepSolver(model,ownership);algebraic=new PassiveStepSolver(model,ownership);
    }

    public record Trial(PassiveStepSolver.Result solution,List<FluidThermodynamics.State> estimatedStates,double[] estimatedMassFlows,List<ConservativeTransport.BoundaryTransfer> estimatedBoundaries) {
        public Trial(PassiveStepSolver.Result solution,List<FluidThermodynamics.State> estimatedStates,double[] estimatedMassFlows){this(solution,estimatedStates,estimatedMassFlows,solution.boundaries());}
        public Trial{estimatedStates=List.copyOf(estimatedStates);estimatedMassFlows=estimatedMassFlows.clone();estimatedBoundaries=List.copyOf(estimatedBoundaries);}
        @Override public double[] estimatedMassFlows(){return estimatedMassFlows.clone();}
    }
    @FunctionalInterface public interface StageGuard {
        StageGuard NONE=(states,modes)->{};
        void check(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes);
    }
    public Trial trial(PassiveNetwork initial,double dt,Runnable checkpoint){return trial(initial,dt,checkpoint,PassiveStepSolver.Acceptance.FULL,StageGuard.NONE);}
    public Trial trial(PassiveNetwork initial,double dt,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance,StageGuard guard){return integrate(initial,dt,checkpoint,true,acceptance,guard);}

    /** massFlows is the quadrature average over dt; state, modes and heads describe the endpoint. */
    public PassiveStepSolver.Result solve(PassiveNetwork initial,double dt,Runnable checkpoint) {
        return solve(initial,dt,checkpoint,PassiveStepSolver.Acceptance.FULL,StageGuard.NONE);
    }
    public PassiveStepSolver.Result solve(PassiveNetwork initial,double dt,Runnable checkpoint,PassiveStepSolver.Acceptance acceptance,StageGuard guard){return integrate(initial,dt,checkpoint,false,acceptance,guard).solution();}
    private Trial integrate(PassiveNetwork initial,double dt,Runnable checkpoint,boolean estimate,PassiveStepSolver.Acceptance acceptance,StageGuard guard) {
        Objects.requireNonNull(acceptance);Objects.requireNonNull(guard);
        ownership.check("TR-BDF2 workspace belongs to the worker holding its solver latch");
        if(!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Positive finite substep required");
        if(initial.reservoirs().stream().anyMatch(n->n.kind()==PassiveNetwork.NodeKind.PORT))throw new IllegalArgumentException("Internal ports cannot be integrated");
        if(initial.pipes().isEmpty()&&initial.scheduledTransfers().isEmpty()){checkpoint.run();var unchanged=implicit.solve(initial,dt,checkpoint,acceptance);guard.check(unchanged.states(),unchanged.modes());return new Trial(unchanged,unchanged.states(),unchanged.massFlows());}
        if(initial.reservoirs().stream().anyMatch(PassiveNetwork.Reservoir::empty))throw new IllegalArgumentException("Evacuated reservoir has no fluid temperature; connected filling requires a supported initialization state");
        var ports=new ArrayList<PassiveNetwork.Reservoir>();
        for(var node:initial.reservoirs())ports.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),
                node.kind()==PassiveNetwork.NodeKind.RESERVOIR?PassiveNetwork.NodeKind.PORT:node.kind(),node.inventory()));
        // Algebraic junction compositions and device flows must be consistent at the start of the interval.
        var portGraph=new PassiveNetwork(ports,initial.pipes());
        var cached=endpointRates.get(new RateKey(initial,PassiveStepSolver.Acceptance.FULL));
        if(cached==null&&acceptance==PassiveStepSolver.Acceptance.APPROXIMATE)cached=endpointRates.get(new RateKey(initial,acceptance));
        double[] initialFlows;ConservativeTransport.Projection rate;List<FlowControl.Mode> initialModes;
        if(cached==null) {
            var solved=algebraic.solve(portGraph,1,checkpoint,acceptance);initialFlows=solved.massFlows();initialModes=solved.modes();
            rate=new ConservativeTransport.Projection(solved.inventories(),solved.states(),solved.boundaries(),solved.externalMoles(),solved.externalEnergyJoule(),solved.pumpWorkJoule());
            cache(initial,rate,initialFlows,initialModes,acceptance);
        }else {initialFlows=cached.flows.clone();rate=cached.properties;initialModes=cached.modes;}
        guard.check(rate.states(),initialModes);
        var byId=new HashMap<Long,Integer>();for(int i=0;i<ports.size();i++)byId.put(ports.get(i).id(),i);
        double[][] dn=new double[ports.size()][model.hydrocarbon.componentCount()+1];double[] du=new double[ports.size()];
        var physicalBoundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        for(var boundary:rate.boundaries()) {
            int i=byId.get(boundary.nodeId());var node=initial.reservoirs().get(i);
            if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR){physicalBoundaries.add(boundary);continue;}
            var n=boundary.moles();double mass=0;
            for(int c=0;c<n.length;c++){dn[i][c]-=n[c];mass+=n[c]*model.molecularWeight(c);}
            du[i]-=boundary.totalEnergyJoule()-mass*PassiveStepSolver.GRAVITY*node.elevation();
        }
        for(var transfer:initial.scheduledTransfers()) {
            int i=transfer.node();var node=initial.reservoirs().get(i);var state=rate.states().get(i);double[] n;double energy,mass=0;
            if(transfer instanceof ScheduledTransfer.Withdrawal out) {
                mass=-out.massKgPerSecond();n=com.wormzjl.createcheme.science.fluid.solver.PhaseLayout.totalAmounts(state);
                for(int c=0;c<n.length;c++)n[c]*=mass/state.mass();
                energy=mass*(state.enthalpy()/state.mass()+PassiveStepSolver.GRAVITY*node.elevation());
            } else {
                var in=(ScheduledTransfer.Injection)transfer;n=in.molesPerSecond();energy=in.totalEnergyPerSecond();
                for(int c=0;c<n.length;c++)mass+=n[c]*model.molecularWeight(c);
            }
            for(int c=0;c<n.length;c++)dn[i][c]+=n[c];du[i]+=energy-mass*PassiveStepSolver.GRAVITY*node.elevation();
            physicalBoundaries.add(new ConservativeTransport.BoundaryTransfer(transfer.id(),n,energy));
        }
        var firstBase=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<ports.size();i++) {
            var node=initial.reservoirs().get(i);var inventory=node.inventory();
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR) {
                var n=inventory.moles();for(int c=0;c<n.length;c++)n[c]+=ALPHA*dt*dn[i][c];
                inventory=new PassiveNetwork.Inventory(inventory.volume(),n,inventory.internalEnergy()+ALPHA*dt*du[i]);
            }
            firstBase.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),rate.states().get(i),node.kind(),inventory));
        }
        var first=implicit.solve(new PassiveNetwork(firstBase,initial.pipes(),initial.scheduledTransfers()),ALPHA*dt,checkpoint,acceptance);guard.check(first.states(),first.modes());
        var secondBase=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<ports.size();i++) {
            var node=initial.reservoirs().get(i);var inventory=node.inventory();
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR) {
                var n=inventory.moles();var stage=first.inventories().get(i);var ns=stage.moles();
                for(int c=0;c<n.length;c++)n[c]+=A*(ns[c]-n[c]);
                inventory=new PassiveNetwork.Inventory(inventory.volume(),n,inventory.internalEnergy()+A*(stage.internalEnergy()-inventory.internalEnergy()));
            }
            secondBase.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),first.states().get(i),node.kind(),inventory));
        }
        var secondGraph=new PassiveNetwork(secondBase,initial.pipes(),initial.scheduledTransfers());
        var second=implicit.solve(secondGraph,ALPHA*dt,checkpoint,acceptance);guard.check(second.states(),second.modes());
        var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        append(boundaries,physicalBoundaries,A*ALPHA*dt);append(boundaries,first.boundaries(),A);append(boundaries,second.boundaries(),1);
        double[] external=new double[dn[0].length];double energy=0;
        for(var boundary:boundaries){var n=boundary.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];energy+=boundary.totalEnergyJoule();}
        double work=A*ALPHA*dt*rate.pumpWork()+A*first.pumpWorkJoule()+second.pumpWorkJoule();
        var projection=new ConservativeTransport.Projection(second.inventories(),second.states(),boundaries,external,energy,work);
        implicit.checkConservation(initial,projection);
        var endpoint=PassiveIntervalSolver.replace(initial,second);var endpointPorts=new ArrayList<PassiveNetwork.Reservoir>();
        for(var node:endpoint.reservoirs())endpointPorts.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),
                node.kind()==PassiveNetwork.NodeKind.RESERVOIR?PassiveNetwork.NodeKind.PORT:node.kind(),node.inventory()));
        var endpointRate=ConservativeTransport.reconstruct(new PassiveNetwork(endpointPorts,initial.pipes()),second.states(),second.massFlows(),second.devicePressureChanges(),1,model,checkpoint);
        cache(endpoint,endpointRate,second.massFlows(),second.modes(),acceptance);
        var q=initialFlows.clone();var q1=first.massFlows();var q2=second.massFlows();
        for(int i=0;i<q.length;i++)q[i]=A*ALPHA*(q[i]+q1[i])+ALPHA*q2[i];
        var pipeTransfers=new PipeTransfer.Accumulator();
        pipeTransfers.add(PipeTransfer.sample(initial,rate.states(),initialFlows,1),A*ALPHA*dt);
        pipeTransfers.add(first.pipeTransfers(),A);pipeTransfers.add(second.pipeTransfers(),1);
        var solution=new PassiveStepSolver.Result(second.states(),q,dt,second.numerical(),second.modes(),second.devicePressureChanges(),work,external,energy,second.inventories(),boundaries,pipeTransfers.snapshot());
        if(!estimate)return new Trial(solution,second.states(),q);
        // Embedded order-three companion. Its defect is smoothed through the same implicit operator,
        // which extends the usual (I-alpha*h*J)^-1 filter to our constrained states. The correction
        // is only an error estimate and its material/energy ledger is never committed.
        double w=A*ALPHA,e0=(1-w)/3-w,e1=(3*w+1)/3-w,e2=ALPHA/3-ALPHA;
        double[][] deltaMoles=new double[ports.size()][dn[0].length];double[] deltaEnergy=new double[ports.size()];
        var correctedBase=new ArrayList<PassiveNetwork.Reservoir>();
        for(int i=0;i<ports.size();i++) {
            var node=secondBase.get(i);var inventory=node.inventory();
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR) {
                var n=inventory.moles();var base=n.clone();var n0=firstBase.get(i).inventory().moles();var n1=first.inventories().get(i).moles();var n2=second.inventories().get(i).moles();
                for(int c=0;c<n.length;c++){deltaMoles[i][c]=e0*dt*dn[i][c]+e1/ALPHA*(n1[c]-n0[c])+e2/ALPHA*(n2[c]-base[c]);n[c]+=deltaMoles[i][c];}
                deltaEnergy[i]=e0*dt*du[i]+e1/ALPHA*(first.inventories().get(i).internalEnergy()-firstBase.get(i).inventory().internalEnergy())
                        +e2/ALPHA*(second.inventories().get(i).internalEnergy()-inventory.internalEnergy());
                inventory=new PassiveNetwork.Inventory(inventory.volume(),n,inventory.internalEnergy()+deltaEnergy[i]);
            }
            correctedBase.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),second.states().get(i),node.kind(),inventory));
        }
        var correctedGraph=new PassiveNetwork(correctedBase,initial.pipes(),initial.scheduledTransfers());
        // The companion stage changes nothing but those targets, so one solve of the stage-two
        // Jacobian answers it. The complete nonlinear stage stays the fallback and the reference.
        var filtered=companionFilter==CompanionFilter.LINEAR
                ?implicit.companion(secondGraph,correctedGraph,deltaMoles,deltaEnergy,second.devicePressureChanges(),ALPHA*dt,checkpoint):null;
        List<FluidThermodynamics.State> correctedStates;double[] qc;List<ConservativeTransport.BoundaryTransfer> correctedBoundaries;
        if(filtered!=null) {
            SolverDiagnostics.count(SolverDiagnostics.companionFilters);
            correctedStates=filtered.states();qc=filtered.massFlows();correctedBoundaries=filtered.boundaries();
            guard.check(correctedStates,second.modes());
        }else {
            SolverDiagnostics.count(SolverDiagnostics.companionSolves);
            var corrected=implicit.solve(correctedGraph,ALPHA*dt,checkpoint,acceptance);guard.check(corrected.states(),corrected.modes());
            correctedStates=corrected.states();qc=corrected.massFlows();correctedBoundaries=corrected.boundaries();
        }
        var estimated=q.clone();
        // The transfer integral is an extra differential variable, so its defect uses the same filter.
        for(int i=0;i<estimated.length;i++)estimated[i]+=e0*initialFlows[i]+e1*q1[i]+e2*q2[i]+ALPHA*(qc[i]-q2[i]);
        // Source/sink integrals are additional differential variables too. Include their
        // companion defect and the same implicit endpoint correction; this ledger is never
        // committed. It lets smooth module intervals qualify boundary accuracy without three
        // complete integrations. Phase/device transitions still use conservative step doubling.
        var estimatedBoundaries=new ArrayList<>(solution.boundaries());
        append(estimatedBoundaries,physicalBoundaries,e0*dt);append(estimatedBoundaries,first.boundaries(),e1/ALPHA);append(estimatedBoundaries,second.boundaries(),e2/ALPHA);
        append(estimatedBoundaries,correctedBoundaries,1);append(estimatedBoundaries,second.boundaries(),-1);
        return new Trial(solution,correctedStates,estimated,estimatedBoundaries);
    }
    private void cache(PassiveNetwork graph,ConservativeTransport.Projection rate,double[] flows,List<FlowControl.Mode> modes,PassiveStepSolver.Acceptance acceptance) {
        // A valve reaching its setpoint has a nonsmooth endpoint rate; reevaluate its algebraic regime.
        if(graph.pipes().stream().anyMatch(pipe->pipe.control() instanceof FlowControl.PressureValve))return;
        if(endpointRates.size()>=8)endpointRates.remove(endpointRates.keySet().iterator().next());
        endpointRates.put(new RateKey(graph,acceptance),new Rate(rate,flows,modes));
    }
    private static void append(List<ConservativeTransport.BoundaryTransfer> target,List<ConservativeTransport.BoundaryTransfer> source,double scale) {
        for(var transfer:source){var n=transfer.moles();for(int c=0;c<n.length;c++)n[c]*=scale;target.add(new ConservativeTransport.BoundaryTransfer(transfer.nodeId(),n,transfer.totalEnergyJoule()*scale));}
    }
}

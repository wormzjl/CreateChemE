package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;

/** Second-order, L-stable TR-BDF2, using two simultaneous implicit stages with equal diagonal coefficient.
 * Inventories are stage right-hand sides, not new physical initializations. No stage may commit independently.
 */
public final class TrBdf2StepSolver {
    private static final double GAMMA=2-Math.sqrt(2), ALPHA=GAMMA/2, A=1/(GAMMA*(2-GAMMA));
    /** The embedded order-three companion's weights on the endpoint rate and the two stages. They
     * are constants of the tableau, hoisted so that the solid stock of the corrected reservoirs can
     * be accumulated where the endpoint rate is read rather than reconstructed from it later. */
    private static final double W=A*ALPHA, E0=(1-W)/3-W, E1=(3*W+1)/3-W, E2=ALPHA/3-ALPHA;
    private final FluidThermodynamics model;
    private final PassiveStepSolver implicit,algebraic;
    private record RateKey(PassiveNetwork graph,PassiveStepSolver.Acceptance acceptance) {}
    private record Rate(ConservativeTransport.Projection properties,double[] flows,List<FlowControl.Mode> modes,double[] heads) {
        private Rate{flows=flows.clone();modes=List.copyOf(modes);heads=heads.clone();}
    }
    private final Map<RateKey,Rate> endpointRates=new LinkedHashMap<>();
    private final SolverOwnership ownership;
    /** One per island: both stage solvers and the endpoint rate below refill the same EJML storage. */
    private final ConservativeTransport.Workspace transport;
    public TrBdf2StepSolver(FluidThermodynamics model){this(model,SolverOwnership.confinedToCurrentThread());}
    public TrBdf2StepSolver(FluidThermodynamics model,SolverOwnership ownership) {
        this.model=Objects.requireNonNull(model);this.ownership=Objects.requireNonNull(ownership);
        transport=new ConservativeTransport.Workspace(ownership);
        implicit=new PassiveStepSolver(model,ownership,transport);algebraic=new PassiveStepSolver(model,ownership,transport);
    }

    public record Trial(PassiveStepSolver.Result solution,List<FluidThermodynamics.State> estimatedStates,double[] estimatedMassFlows,List<ConservativeTransport.BoundaryTransfer> estimatedBoundaries) {
        public Trial(PassiveStepSolver.Result solution,List<FluidThermodynamics.State> estimatedStates,double[] estimatedMassFlows){this(solution,estimatedStates,estimatedMassFlows,solution.boundaries());}
        public Trial{estimatedStates=List.copyOf(estimatedStates);estimatedMassFlows=estimatedMassFlows.clone();estimatedBoundaries=List.copyOf(estimatedBoundaries);}
        @Override public double[] estimatedMassFlows(){return estimatedMassFlows.clone();}
    }
    @FunctionalInterface public interface StageGuard {
        StageGuard NONE=(states,modes)->{};
        void check(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes);
        default void checkFlow(List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows){check(states,modes);}
        default void checkFilters(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows){checkFlow(states,modes,flows);}
        /**
         * The endpoint rate at the start of a step, evaluated before any stage is solved, together
         * with the live cake of the graph being stepped. A transition seen here is at the step's own
         * t0 and is therefore already exactly located: the interval solver declares it at the
         * current elapsed time instead of refining the step towards it.
         */
        default void checkRate(Map<Long,InlineFilter> filters,List<FluidThermodynamics.State> states,List<FlowControl.Mode> modes,double[] flows){checkFlow(states,modes,flows);}
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
        initial=PassiveNetwork.sizeJunctionHoldups(initial,model);
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
        double[] initialFlows,initialHeads;ConservativeTransport.Projection rate;List<FlowControl.Mode> initialModes;
        SolverDiagnostics.count(cached==null?SolverDiagnostics.endpointRateBuilds:SolverDiagnostics.endpointRateReuses);
        if(cached==null) {
            var solved=algebraic.solveRate(portGraph,checkpoint,acceptance);initialFlows=solved.massFlows();initialModes=solved.modes();initialHeads=solved.devicePressureChanges();
            rate=new ConservativeTransport.Projection(solved.inventories(),solved.states(),solved.boundaries(),solved.externalMoles(),solved.externalEnergyJoule(),solved.pumpWorkJoule(),solved.filters());
            cache(initial,rate,initialFlows,initialModes,initialHeads,acceptance);
        }else {initialFlows=cached.flows.clone();rate=cached.properties;initialModes=cached.modes;initialHeads=cached.heads.clone();}
        // Review 7.9 (junction.stageForm=implicit): re-book the cached rate's flows for this step, each owned
        // junction's outflows carrying its stock advanced by backward Euler over alpha*dt; see STAGE_IMPLICIT.
        boolean relaxed=true;
        // Review 7.11 (junction.stageClip=on): the stage-two clip re-books on the edges exactly as the relaxed rate
        // booking and the stage-one reconstruction booked them, so both record their per-edge bookings.
        boolean clip=true;ConservativeTransport.EdgeBooking[] rateBook=null,firstBook=null;
        if(relaxed) {
            if(clip)ConservativeTransport.recordBookings();
            try{rate=ConservativeTransport.reconstruct(portGraph,rate.states(),initialFlows,initialHeads,1,model,checkpoint,transport,true,ALPHA*dt);}
            finally{if(clip)rateBook=ConservativeTransport.takeBookings();}
        }
        guard.checkRate(liveFilters(initial),rate.states(),initialModes,initialFlows);
        var byId=new HashMap<Long,Integer>();for(int i=0;i<ports.size();i++)byId.put(ports.get(i).id(),i);
        double[][] dn=new double[ports.size()][model.hydrocarbon.componentCount()+1];double[] du=new double[ports.size()];
        var firstSolids=new SolidInventory.Accumulator[ports.size()];for(int i=0;i<ports.size();i++){firstSolids[i]=new SolidInventory.Accumulator();firstSolids[i].add(initial.reservoirs().get(i).inventory().solids(),1);}
        // The companion's corrected solid stock. Its endpoint-rate term is the same source and sink
        // stream the stage-one base reads below, at the companion's own weight instead of alpha*dt,
        // so it is accumulated here rather than divided back out of a finished stage inventory,
        // which a node losing solids would have had to hold as a negative population.
        var companionSolids=estimate?new SolidInventory.Accumulator[ports.size()]:null;
        if(estimate)for(int i=0;i<ports.size();i++)companionSolids[i]=new SolidInventory.Accumulator();
        var physicalBoundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        for(var boundary:rate.boundaries()) {
            int i=byId.get(boundary.nodeId());var node=initial.reservoirs().get(i);
            // Owned-holdup prototype, frozen rate form: a junction's pseudo-boundary is its rate, read like a port's.
            if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR&&!node.junction()){physicalBoundaries.add(boundary);continue;}
            var n=boundary.moles();double mass=0;
            for(int c=0;c<n.length;c++){dn[i][c]-=n[c];mass+=n[c]*model.molecularWeight(c);}
            mass+=boundary.solidDirection()*boundary.solids().massKg();
            firstSolids[i].add(boundary.solids(),-ALPHA*dt*boundary.solidDirection());
            if(estimate)companionSolids[i].add(boundary.solids(),-E0*dt*boundary.solidDirection());
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
            SolidInventory moved;int direction;
            if(transfer instanceof ScheduledTransfer.Withdrawal out){moved=state.solids().scale(out.massKgPerSecond()/state.mass());direction=-1;}
            else{moved=((ScheduledTransfer.Injection)transfer).solidsPerSecond();direction=1;du[i]-=moved.massKg()*PassiveStepSolver.GRAVITY*node.elevation();}
            firstSolids[i].add(moved,ALPHA*dt*direction);
            if(estimate)companionSolids[i].add(moved,E0*dt*direction);
            physicalBoundaries.add(new ConservativeTransport.BoundaryTransfer(transfer.id(),n,energy,moved,direction));
        }
        // Owned-holdup prototype: a junction has no boundary and no scheduled transfer, so dn/du above are
        // zero for it. But its owned stock changes during the rate solve (the regularised row at dt = 1,
        // eps = m_J/1 s), and the ports' boundaries balance that change, not zero: its rate is that change
        // per second, read off the same conservative projection the port boundaries came from.
        boolean owned=true;
        if(owned)for(int i=0;i<ports.size();i++)if(initial.reservoirs().get(i).junction()) {
            var was=initial.reservoirs().get(i).inventory();var now=rate.inventories().get(i);var a=was.moles();var b=now.moles();
            for(int c=0;c<a.length;c++)dn[i][c]+=b[c]-a[c];du[i]+=now.internalEnergy()-was.internalEnergy();
        }
        var firstBase=new ArrayList<PassiveNetwork.Reservoir>();
        // Review 7.9 (junction.stageForm=implicit): the re-booked rate gives a junction dn = In - Q S1/m, so its
        // stage-one base n0 + alpha dt dn is exactly its backward-Euler stock S1 over alpha dt (non-negative).
        // The stage-two base below is the same A-blend as a vessel's, n0 + A (n1 - n0) with A = 1.2071 > 1.
        for(int i=0;i<ports.size();i++) {
            var node=initial.reservoirs().get(i);var inventory=node.inventory();
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||owned&&node.junction()) {
                var n=inventory.moles();for(int c=0;c<n.length;c++)n[c]+=ALPHA*dt*dn[i][c];
                inventory=new PassiveNetwork.Inventory(inventory.volume(),n,inventory.internalEnergy()+ALPHA*dt*du[i],firstSolids[i].finish());
            }
            firstBase.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),rate.states().get(i),node.kind(),inventory));
        }
        if(clip)ConservativeTransport.recordBookings();
        PassiveStepSolver.Result first;
        try{first=implicit.solve(new PassiveNetwork(firstBase,filterStage(initial,rate.filters(),ALPHA*dt),initial.scheduledTransfers()),ALPHA*dt,checkpoint,acceptance);}
        finally{if(clip)firstBook=ConservativeTransport.takeBookings();}
        guard.checkFilters(first.filters(),first.states(),first.modes(),first.massFlows());
        var secondBase=new ArrayList<PassiveNetwork.Reservoir>();
        List<ConservativeTransport.BoundaryTransfer> clipBoundaries=List.of();
        if(clip)clipBoundaries=clippedSecondBase(initial,firstBase,first,rateBook,firstBook,dt,secondBase);
        else for(int i=0;i<ports.size();i++) {
            var node=initial.reservoirs().get(i);var inventory=node.inventory();
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR||owned&&node.junction()) {
                var n=inventory.moles();var stage=first.inventories().get(i);var ns=stage.moles();
                for(int c=0;c<n.length;c++)n[c]+=A*(ns[c]-n[c]);
                inventory=new PassiveNetwork.Inventory(inventory.volume(),n,inventory.internalEnergy()+A*(stage.internalEnergy()-inventory.internalEnergy()),SolidInventory.combine(inventory.solids(),1-A,stage.solids(),A));
            }
            secondBase.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),first.states().get(i),node.kind(),inventory));
        }
        var secondGraph=new PassiveNetwork(secondBase,filterStage(initial,first.filters(),A),initial.scheduledTransfers());
        var second=implicit.solve(secondGraph,ALPHA*dt,checkpoint,acceptance);guard.checkFilters(second.filters(),second.states(),second.modes(),second.massFlows());
        var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        append(boundaries,physicalBoundaries,A*ALPHA*dt);append(boundaries,first.boundaries(),A);append(boundaries,second.boundaries(),1);
        boundaries.addAll(clipBoundaries); // Review 7.11: already weighted; only at fixed nodes that already book this step.
        double[] external=new double[dn[0].length];double energy=0;
        for(var boundary:boundaries){var n=boundary.moles();for(int c=0;c<n.length;c++)external[c]+=n[c];energy+=boundary.totalEnergyJoule();}
        double work=A*ALPHA*dt*rate.pumpWork()+A*first.pumpWorkJoule()+second.pumpWorkJoule();
        var projection=new ConservativeTransport.Projection(second.inventories(),second.states(),boundaries,external,energy,work,second.filters());
        implicit.checkConservation(initial,projection);
        var endpoint=PassiveIntervalSolver.replace(initial,second);var endpointPorts=new ArrayList<PassiveNetwork.Reservoir>();
        for(var node:endpoint.reservoirs())endpointPorts.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),
                node.kind()==PassiveNetwork.NodeKind.RESERVOIR?PassiveNetwork.NodeKind.PORT:node.kind(),node.inventory()));
        var endpointRate=ConservativeTransport.reconstruct(new PassiveNetwork(endpointPorts,endpoint.pipes()),second.states(),second.massFlows(),second.devicePressureChanges(),1,model,checkpoint,transport,true);
        cache(endpoint,endpointRate,second.massFlows(),second.modes(),second.devicePressureChanges(),acceptance);
        var q=initialFlows.clone();var q1=first.massFlows();var q2=second.massFlows();
        for(int i=0;i<q.length;i++)q[i]=A*ALPHA*(q[i]+q1[i])+ALPHA*q2[i];
        var pipeTransfers=new PipeTransfer.Accumulator();
        var rateTransfers=PipeTransfer.sample(initial,rate.states(),initialFlows,1);
        // Review 7.9: the explicit history matches the pass-through booking of the frozen rate (rate graph = portGraph).
        if(relaxed)rateTransfers=PipeTransfer.relaxed(portGraph,rateTransfers,initialFlows,ALPHA*dt,model.molecularWeights());
        pipeTransfers.add(rateTransfers,A*ALPHA*dt);
        pipeTransfers.add(first.pipeTransfers(),A);pipeTransfers.add(second.pipeTransfers(),1);
        var solution=new PassiveStepSolver.Result(second.states(),q,dt,second.numerical(),second.modes(),second.devicePressureChanges(),work,external,energy,second.inventories(),boundaries,pipeTransfers.snapshot(),second.filters());
        if(!estimate)return new Trial(solution,second.states(),q);
        // Embedded order-three companion. Its defect is smoothed through the same implicit operator,
        // which extends the usual (I-alpha*h*J)^-1 filter to our constrained states. The correction
        // is only an error estimate and its material/energy ledger is never committed.
        double e0=E0,e1=E1,e2=E2;
        double[][] deltaMoles=new double[ports.size()][dn[0].length];double[] deltaEnergy=new double[ports.size()];
        double[][] deltaSolids=new double[ports.size()][3];
        var correctedBase=new ArrayList<PassiveNetwork.Reservoir>();
        // Owned-holdup prototype: a junction's owned stock is left out of the estimate - it keeps its
        // stage-two base inventory and gets no defect (a small quantity; the companion skips junction rows).
        for(int i=0;i<ports.size();i++) {
            var node=secondBase.get(i);var inventory=node.inventory();
            if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR) {
                var n=inventory.moles();var base=n.clone();var n0=firstBase.get(i).inventory().moles();var n1=first.inventories().get(i).moles();var n2=second.inventories().get(i).moles();
                for(int c=0;c<n.length;c++){deltaMoles[i][c]=e0*dt*dn[i][c]+e1/ALPHA*(n1[c]-n0[c])+e2/ALPHA*(n2[c]-base[c]);n[c]+=deltaMoles[i][c];}
                deltaEnergy[i]=e0*dt*du[i]+e1/ALPHA*(first.inventories().get(i).internalEnergy()-firstBase.get(i).inventory().internalEnergy())
                        +e2/ALPHA*(second.inventories().get(i).internalEnergy()-inventory.internalEnergy());
                // The same combination on the conserved populations, whose aggregate moments are
                // the companion's targets for the three solid rows. The endpoint-rate term is
                // already in the accumulator; the rest is the stage-two base plus the two stage
                // differences, at the weights the amounts above use.
                var baseSolids=inventory.solids();
                companionSolids[i].add(baseSolids,1);
                companionSolids[i].add(first.inventories().get(i).solids(),e1/ALPHA);
                companionSolids[i].add(firstBase.get(i).inventory().solids(),-e1/ALPHA);
                companionSolids[i].add(second.inventories().get(i).solids(),e2/ALPHA);
                companionSolids[i].add(baseSolids,-e2/ALPHA);
                var solids=companionSolids[i].finishNonNegative();
                double[] before=baseSolids.moments().values(),after=solids.moments().values();
                for(int c=0;c<3;c++)deltaSolids[i][c]=after[c]-before[c];
                inventory=new PassiveNetwork.Inventory(inventory.volume(),n,inventory.internalEnergy()+deltaEnergy[i],solids);
            }
            correctedBase.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),second.states().get(i),node.kind(),inventory));
        }
        // The stage-two cakes, not the interval's: the companion reconstructs the same step the
        // stage-two solve did, so the material it hands a filter has to land on the same base.
        var correctedGraph=new PassiveNetwork(correctedBase,secondGraph.pipes(),initial.scheduledTransfers());
        // The companion stage changes nothing but those targets, so one solve of the stage-two
        // Jacobian answers it. The complete nonlinear stage stays the fallback and the reference.
        var filtered=implicit.companion(secondGraph,correctedGraph,deltaMoles,deltaEnergy,deltaSolids,second.devicePressureChanges(),ALPHA*dt,checkpoint);
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
    /**
     * The
     * stage-two bases, with every owned junction's base clipped at zero per species and solid population, the
     * clipped amount booked back on the junction's own outflows at both ends of each edge, the way
     * {@code ConservativeTransport.pinnedFlows} books the mass pin. Fills {@code secondBase} and returns the
     * boundary transfers the clip books at fixed nodes.
     *
     * <p><b>Why a junction's base can be negative.</b> {@code base2 = (1 - A) n0 + A n1} with A = 1.2071 > 1. A species
     * the inflow lacks leaves the junction as {@code n1 = n0/(1 + r)^2}, r = alpha dt Q/m_J, and base2 < 0 once
     * r > sqrt 2 (review 7.9 (b)). Every node's stage-two base is {@code base2_i = n0_i + A (alpha dt r_i + T1_i)}:
     * the rate booking (per second, weight A alpha dt) and the stage-one booking (weight A) of every edge at
     * that node. The A weight credits the receivers of the junction's flush with {@code (A - 1)(n0 - n1)} more
     * than the junction held.
     *
     * <p><b>The clip and its identity.</b> For an owned junction J and a species c (the same for a solid population)
     * with {@code d = -base2_J,c > 0}, let {@code net_e} be the A-weighted amount of c the two bookings moved out of J
     * over edge e (out minus in, as they were booked: pinned flows, relaxed compositions; recorded by
     * {@code ConservativeTransport.recordBookings}). Then {@code sum_e net_e = n0_J,c - base2_J,c = n0_J,c + d >= d},
     * so the positive part {@code S = sum_{net_e > 0} net_e >= d}, and each such edge takes back
     * {@code d_e = d net_e/S <= net_e}. The clip is a <em>composition swap</em> on the edge: it returns d_e of c and
     * sends the same mass {@code dm_e = d_e M_c} of the junction's own admissible mixture y (mol/kg of its positive
     * species, taken once before the junction's swaps) the other way:
     * <pre>
     *   base2'_J = base2_J + sum_e (d_e e_c - dm_e y)       (c -> 0; every other species stays >= 0, see below)
     *   base2'_k = base2_k - d_e e_c + dm_e y               (k = the edge's other end, a vessel or an owned junction)
     *   B'       = B + {+d_e e_c - dm_e y at k}            (k fixed: one boundary transfer at k's own id)
     * </pre>
     * Per component and solid population {@code sum_i base2'_i - sum_i base2_i = sum_{k fixed} (d_e e_c - dm_e y)},
     * so the step's ledger {@code sum_i [n_i(dt) - n_i(0)] = A alpha dt B_rate + A B1 + B2 + B_clip} (review 7.9 (a))
     * holds exactly: each edge still books one amount at both ends. Every edge keeps its booked mass, so no node's
     * mass changes, the mass pin and the junction's algebraic mass (the Newton's net-mass row) see nothing, and no
     * energy is re-booked. The junction's positive species lose {@code D/(m + D)} of themselves at most
     * (D = the clipped mass, m + D = their mass, m = the junction's mass >= 0), so they stay non-negative. The
     * receiver keeps {@code net_e - d_e >= 0} of the c it got over that edge: after the clip the junction delivers
     * exactly the c it held and received, and the rest of the edge's mass is its current mixture, which is what a
     * flush delivers; before it, the A-blend delivered (A - 1)(n0 - n1) more c than the junction ever held.
     *
     * <p><b>Energy.</b> The enthalpy field has no sign bound (enthalpy reference), so it is never clipped; the swap
     * moves no mass, so it moves no energy either (a per-species enthalpy is not available; the junction keeps its
     * specific field and the receivers theirs).
     *
     * <p><b>Solids.</b> Per population, on edges without a filter only (a filter edge's solids went to the cake,
     * which is not re-booked); an uncovered remainder is left and traced. The mass returned is replaced by the
     * junction's fluid mixture.
     *
     * <p>Junctions are clipped repeatedly (a swap lowers a downstream junction's species) up to junctions + 2
     * passes. A clipped value within rounding of zero is set to 0. The recorded bookings are checked against the
     * junction's own stage changes first (rate: base1 - n0; stage one: n1 - base1); a junction whose check fails
     * is left unclipped and a STAGECLIP MISMATCH line is printed. A first form that returned the mass without the
     * swap (energy at the edge's specific field) was superseded by run 75: under the pin the junction's surplus
     * mass was pinned out through stage two's outflows, which the Newton had not solved, and the stage-two solve
     * failed its equation gate (7.2e-6).
     */
    private List<ConservativeTransport.BoundaryTransfer> clippedSecondBase(PassiveNetwork initial,List<PassiveNetwork.Reservoir> firstBase,PassiveStepSolver.Result first,
            ConservativeTransport.EdgeBooking[] rateBook,ConservativeTransport.EdgeBooking[] firstBook,double dt,List<PassiveNetwork.Reservoir> secondBase) {
        int nodes=initial.reservoirs().size(),edges=initial.pipes().size(),components=model.hydrocarbon.componentCount()+1;double[] mw=model.molecularWeights();
        boolean[] stock=new boolean[nodes];double[][] moles=new double[nodes][];double[] field=new double[nodes];
        var basis=new TreeMap<SolidInventory.Key,SolidInventory.Population>();var solids=new ArrayList<TreeMap<SolidInventory.Key,Double>>();
        for(int i=0;i<nodes;i++) {
            var node=initial.reservoirs().get(i);var inventory=node.inventory();var map=new TreeMap<SolidInventory.Key,Double>();solids.add(map);
            stock[i]=node.kind()==PassiveNetwork.NodeKind.RESERVOIR||node.junction();
            if(!stock[i])continue;
            var n=inventory.moles();var stage=first.inventories().get(i);var ns=stage.moles();
            for(int c=0;c<n.length;c++)n[c]+=A*(ns[c]-n[c]);
            moles[i]=n;field[i]=inventory.internalEnergy()+A*(stage.internalEnergy()-inventory.internalEnergy());
            for(var p:inventory.solids().populations()){basis.putIfAbsent(p.key(),p);map.merge(p.key(),(1-A)*p.massKg(),Double::sum);}
            for(var p:stage.solids().populations()){basis.putIfAbsent(p.key(),p);map.merge(p.key(),A*p.massKg(),Double::sum);}
        }
        var books=new ConservativeTransport.EdgeBooking[][]{rateBook,firstBook};double[] weights={A*ALPHA*dt,A};
        boolean usable=rateBook!=null&&firstBook!=null&&rateBook.length==edges&&firstBook.length==edges;
        var boundaryMoles=new TreeMap<Integer,double[]>();var boundarySolids=new TreeMap<Integer,SolidInventory.Accumulator>();
        if(usable) {
            // Check: the recorded bookings reproduce each junction's own changes (rate: base1 - n0 over alpha dt; stage one: n1 - base1).
            boolean[] trusted=new boolean[nodes];int junctions=0;
            for(int j=0;j<nodes;j++)if(initial.reservoirs().get(j).junction()) {
                trusted[j]=true;
                double[] n0=initial.reservoirs().get(j).inventory().moles(),b1=firstBase.get(j).inventory().moles(),n1=first.inventories().get(j).moles();
                for(int b=0;b<2&&trusted[j];b++)for(int c=0;c<components&&trusted[j];c++) {
                    double sum=0,scale=0;
                    for(int e=0;e<edges;e++){var k=books[b][e];if(k==null)continue;double a=(b==0?ALPHA*dt:1)*k.species()[c];if(k.receiver()==j){sum+=a;scale+=Math.abs(a);}else if(k.donor()==j){sum-=a;scale+=Math.abs(a);}}
                    double target=b==0?b1[c]-n0[c]:n1[c]-b1[c];scale=Math.max(scale,Math.max(Math.abs(b==0?n0[c]:b1[c]),Math.abs(b==0?b1[c]:n1[c])));
                    if(Math.abs(sum-target)>1e-9*scale+1e-300){trusted[j]=false;}
                }
                if(trusted[j])junctions++;
            }
            for(int pass=0;pass<junctions+2;pass++) {
                boolean clipped=false;
                for(int j=0;j<nodes;j++) {
                    if(!trusted[j])continue;
                    boolean negative=false;for(int c=0;c<components;c++)negative|=moles[j][c]<0;for(double m:solids.get(j).values())negative|=m<0;
                    if(!negative)continue;
                    clipped=true;
                    // The junction's admissible mixture, mol per kg of its positive fluid species.
                    double positiveMass=0;double[] mixture=new double[components];
                    for(int c=0;c<components;c++)if(moles[j][c]>0)positiveMass+=moles[j][c]*mw[c];
                    if(!(positiveMass>0))continue;
                    for(int c=0;c<components;c++)if(moles[j][c]>0)mixture[c]=moles[j][c]/positiveMass;
                    for(int c=0;c<components;c++)if(moles[j][c]<0) {
                        double d=-moles[j][c];double[] net=new double[edges];double positive=0;
                        for(int e=0;e<edges;e++){for(int b=0;b<2;b++){var k=books[b][e];if(k==null)continue;double a=weights[b]*k.species()[c];if(k.donor()==j)net[e]+=a;else if(k.receiver()==j)net[e]-=a;}if(net[e]>0)positive+=net[e];}
                        if(!(positive>0))continue;
                        double fraction=Math.min(1,d/positive);
                        for(int e=0;e<edges;e++)if(net[e]>0) {
                            double taken=fraction*net[e];var returned=new double[components];returned[c]=taken;
                            swap(initial,j,e,returned,null,taken*mw[c],mixture,stock,moles,solids,boundaryMoles,boundarySolids);
                        }
                        if(fraction<1||moles[j][c]>-1e-12*d)moles[j][c]=Math.max(0,moles[j][c]);
                    }
                    for(var key:new ArrayList<>(solids.get(j).keySet()))if(solids.get(j).get(key)<0) {
                        double d=-solids.get(j).get(key);double[] net=new double[edges];double positive=0;
                        for(int e=0;e<edges;e++){if(initial.pipes().get(e).filter()!=null)continue;for(int b=0;b<2;b++){var k=books[b][e];if(k==null)continue;double a=weights[b]*k.solids().mass(key);if(k.donor()==j)net[e]+=a;else if(k.receiver()==j)net[e]-=a;}if(net[e]>0)positive+=net[e];}
                        if(!(positive>0))continue;
                        double fraction=Math.min(1,d/positive);var population=basis.get(key);
                        for(int e=0;e<edges;e++)if(net[e]>0) {
                            double taken=fraction*net[e];
                            swap(initial,j,e,new double[components],new SolidInventory(List.of(new SolidInventory.Population(population.material(),population.size(),taken))),taken,mixture,stock,moles,solids,boundaryMoles,boundarySolids);
                        }
                        if(fraction<1||solids.get(j).get(key)>-1e-12*d)solids.get(j).put(key,Math.max(0,solids.get(j).get(key)));
                    }
                    // The mixture's species lost at most D/(m + D) of themselves; only rounding can take one below zero.
                    for(int c=0;c<components;c++)if(moles[j][c]<0&&moles[j][c]>-1e-12*positiveMass/mw[c])moles[j][c]=0;
                }
                if(!clipped)break;
            }
        }
        for(int i=0;i<nodes;i++) {
            var node=initial.reservoirs().get(i);var inventory=node.inventory();
            if(stock[i]) {
                var populations=new ArrayList<SolidInventory.Population>();
                for(var entry:solids.get(i).entrySet()){var p=basis.get(entry.getKey());populations.add(new SolidInventory.Population(p.material(),p.size(),entry.getValue()));}
                inventory=new PassiveNetwork.Inventory(inventory.volume(),moles[i],field[i],new SolidInventory(populations));
            }
            secondBase.add(new PassiveNetwork.Reservoir(node.id(),node.elevation(),first.states().get(i),node.kind(),inventory));
        }
        var result=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        for(var entry:boundaryMoles.entrySet()) {
            int k=entry.getKey();var returned=boundarySolids.get(k).finish();
            result.add(new ConservativeTransport.BoundaryTransfer(initial.reservoirs().get(k).id(),entry.getValue(),0,returned,returned.empty()?0:1));
        }
        return result;
    }
    /** Review 7.11: on edge e, return {@code returned} species and {@code returnedSolids} from the edge's other end into
     * junction j and send the same mass {@code mass} of j's mixture (mol/kg) the other way; no energy moves. */
    private static void swap(PassiveNetwork initial,int j,int e,double[] returned,SolidInventory returnedSolids,double mass,double[] mixture,
            boolean[] stock,double[][] moles,List<TreeMap<SolidInventory.Key,Double>> solids,Map<Integer,double[]> boundaryMoles,Map<Integer,SolidInventory.Accumulator> boundarySolids) {
        var pipe=initial.pipes().get(e);int k=pipe.first()==j?pipe.second():pipe.first();
        var delta=new double[returned.length];for(int c=0;c<delta.length;c++)delta[c]=returned[c]-mass*mixture[c];
        for(int c=0;c<delta.length;c++)moles[j][c]+=delta[c];
        if(returnedSolids!=null)for(var p:returnedSolids.populations())solids.get(j).merge(p.key(),p.massKg(),Double::sum);
        if(stock[k]) {
            for(int c=0;c<delta.length;c++)if(delta[c]!=0){moles[k][c]-=delta[c];if(moles[k][c]<0&&moles[k][c]>-1e-12*Math.abs(delta[c]))moles[k][c]=0;}
            if(returnedSolids!=null)for(var p:returnedSolids.populations()){double left=solids.get(k).merge(p.key(),-p.massKg(),Double::sum);if(left<0&&left>-1e-12*p.massKg())solids.get(k).put(p.key(),0.0);}
        }else {
            var n=boundaryMoles.computeIfAbsent(k,x->new double[delta.length]);for(int c=0;c<delta.length;c++)n[c]+=delta[c];
            var s=boundarySolids.computeIfAbsent(k,x->new SolidInventory.Accumulator());if(returnedSolids!=null)s.add(returnedSolids,1);
        }
    }
    /** The cake each filter edge of this graph carries right now, for the start-of-step guard. */
    private static Map<Long,InlineFilter> liveFilters(PassiveNetwork graph) {
        Map<Long,InlineFilter> filters=null;
        for(var pipe:graph.pipes())if(pipe.filter()!=null){if(filters==null)filters=new HashMap<>();filters.put(pipe.id(),pipe.filter());}
        return filters==null?Map.of():filters;
    }
    private static List<PassiveNetwork.Pipe> filterStage(PassiveNetwork graph,Map<Long,InlineFilter> result,double weight) {
        return graph.pipes().stream().map(p->p.filter()==null?p:p.withFilter(InlineFilter.combine(p.filter(),1-weight,result.getOrDefault(p.id(),p.filter()),weight))).toList();
    }
    private void cache(PassiveNetwork graph,ConservativeTransport.Projection rate,double[] flows,List<FlowControl.Mode> modes,double[] heads,PassiveStepSolver.Acceptance acceptance) {
        // A valve reaching its setpoint has a nonsmooth endpoint rate; reevaluate its algebraic regime.
        if(graph.pipes().stream().anyMatch(pipe->pipe.control() instanceof FlowControl.PressureValve))return;
        if(endpointRates.size()>=8)endpointRates.remove(endpointRates.keySet().iterator().next());
        endpointRates.put(new RateKey(graph,acceptance),new Rate(rate,flows,modes,heads));
    }
    private static void append(List<ConservativeTransport.BoundaryTransfer> target,List<ConservativeTransport.BoundaryTransfer> source,double scale) {
        for(var transfer:source){var n=transfer.moles();for(int c=0;c<n.length;c++)n[c]*=scale;target.add(new ConservativeTransport.BoundaryTransfer(transfer.nodeId(),n,transfer.totalEnergyJoule()*scale,transfer.solids().scale(Math.abs(scale)),scale<0?-transfer.solidDirection():transfer.solidDirection()));}
    }
}

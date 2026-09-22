package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import com.wormzjl.createcheme.science.fluid.state.EnergyReference;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** Worker-local preparation of time-distributed buffer transfers. No attempt mutates material ownership. */
public final class ModuleTransferPlanner {
    public record Input(UUID id,long reservoirId,long dueTick,MaterialParcel remaining,double maximumKg) {
        public Input {Objects.requireNonNull(id);Objects.requireNonNull(remaining);if(dueTick<0||!Double.isFinite(maximumKg)||maximumKg<0)throw new IllegalArgumentException("Invalid requested delivery");}
    }
    public record Withdrawal(UUID id,long reservoirId,double maximumKg) {
        public Withdrawal {Objects.requireNonNull(id);if(!Double.isFinite(maximumKg)||maximumKg<0)throw new IllegalArgumentException("Invalid requested withdrawal");}
    }
    public record Proposal(PassiveIntervalSolver.Result candidate,Map<UUID,MaterialParcel> delivered,Map<UUID,MaterialParcel> withdrawn) {
        public Proposal {Objects.requireNonNull(candidate);delivered=Map.copyOf(delivered);withdrawn=Map.copyOf(withdrawn);}
    }
    private final FluidThermodynamics model;
    private final double[] weights;
    private final EnergyReference reference;
    public ModuleTransferPlanner(FluidThermodynamics model) {
        this.model=Objects.requireNonNull(model);weights=model.molecularWeights();
        reference=EnergyReference.sensible(model.components());
    }
    public Proposal prepare(PassiveNetwork original,long startTick,int durationTicks,List<Input> inputs,List<Withdrawal> withdrawals,Runnable checkpoint) {
        return prepare(original,startTick,durationTicks,inputs,withdrawals,checkpoint,new RetainedSolver());
    }
    /** Plans through the island's retained solver, so a buffered interval reuses the same pattern,
     * colouring, ordering and preconditioner as the island's ordinary intervals, across the whole
     * feasibility search below - its trials share one lease. Only the transfer-free baseline solve
     * continues the island's step estimate; each trial introduces a boundary change, so it starts
     * from the cold step and leaves that estimate to the interval that is actually committed. */
    public Proposal prepare(PassiveNetwork original,long startTick,int durationTicks,List<Input> inputs,List<Withdrawal> withdrawals,
                            Runnable checkpoint,RetainedSolver retained) {
        if(startTick<0||durationTicks<1||!original.scheduledTransfers().isEmpty())throw new IllegalArgumentException("Invalid module interval");
        double seconds=durationTicks/20.0;var index=new HashMap<Long,Integer>();var used=new HashSet<Long>();
        for(int i=0;i<original.reservoirs().size();i++){var n=original.reservoirs().get(i);used.add(n.id());if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)index.put(n.id(),i);}
        var identities=new HashSet<UUID>();
        for(var input:inputs){if(input.dueTick>startTick||!identities.add(input.id)||!index.containsKey(input.reservoirId))throw new IllegalArgumentException("Future/duplicate/unknown input");validateBasis(input.remaining);}
        for(var output:withdrawals)if(!identities.add(output.id)||!index.containsKey(output.reservoirId))throw new IllegalArgumentException("Duplicate/unknown withdrawal");
        return retained.run(model,solver->plan(solver,original,seconds,index,used,inputs,withdrawals,checkpoint));
    }
    private Proposal plan(RetainedSolver.Job solver,PassiveNetwork original,double seconds,Map<Long,Integer> index,Set<Long> used,
                          List<Input> inputs,List<Withdrawal> withdrawals,Runnable checkpoint) {
        var settings=PassiveIntervalSolver.Settings.defaults();
        // A feasible requested withdrawal needs only its actual coupled solve. Solving a closed
        // feed network first both duplicates work and creates a different startup transient.
        // Keep the conservative baseline/partial search below for an infeasible full request.
        if(inputs.isEmpty()&&!withdrawals.isEmpty()) {
            var trial=new ArrayList<ScheduledTransfer>();var mapped=new HashMap<Long,UUID>();var trialUsed=new HashSet<>(used);long boundary=Long.MIN_VALUE;
            for(var withdrawal:withdrawals)if(withdrawal.maximumKg>0) {
                while(trialUsed.contains(boundary))boundary++;long id=boundary++;trialUsed.add(id);
                trial.add(new ScheduledTransfer.Withdrawal(id,index.get(withdrawal.reservoirId),withdrawal.maximumKg/seconds));mapped.put(id,withdrawal.id);
            }
            try {
                var full=solver.solveTrial(new PassiveNetwork(original.reservoirs(),original.pipes(),trial),seconds,settings,checkpoint);
                if(full.boundaries().stream().noneMatch(b->mapped.containsKey(b.nodeId())&&!b.solids().empty()))return completed(full,trial,mapped,Map.of());
            }catch(SparseNewton.Nonconvergence|IllegalArgumentException infeasible) {
                checkpoint.run();
            }
        }
        var candidate=solver.solve(original,seconds,settings,checkpoint);var accepted=new ArrayList<ScheduledTransfer>();var delivered=new HashMap<UUID,MaterialParcel>();
        var withdrawalIds=new HashMap<Long,UUID>();long id=Long.MIN_VALUE;
        for(var input:inputs) {
            while(used.contains(id))id++;long boundary=id++;used.add(boundary);
            double kg=Math.min(input.maximumKg,input.remaining.massKg());
            for(int attempt=0;attempt<12&&kg>0;attempt++,kg*=.5) {
                checkpoint.run();var material=input.remaining.takeMass(kg).delivered();var rates=material.moles();for(int c=0;c<rates.length;c++)rates[c]/=seconds;
                var transfer=new ScheduledTransfer.Injection(boundary,index.get(input.reservoirId),rates,material.energyJoule()/seconds,material.solids().scale(1/seconds));
                var trial=new ArrayList<>(accepted);trial.add(transfer);
                try {
                    var result=solver.solveTrial(new PassiveNetwork(original.reservoirs(),original.pipes(),trial),seconds,settings,checkpoint);
                    accepted.add(transfer);candidate=result;delivered.put(input.id,material);break;
                }catch(SparseNewton.Nonconvergence|IllegalArgumentException infeasible) {
                    // Keep the previous feasible whole-interval proposal, including unrelated receiving flow.
                }
            }
        }
        for(var withdrawal:withdrawals) {
            while(used.contains(id))id++;long boundary=id++;used.add(boundary);double kg=withdrawal.maximumKg;
            for(int attempt=0;attempt<12&&kg>0;attempt++,kg*=.5) {
                checkpoint.run();var transfer=new ScheduledTransfer.Withdrawal(boundary,index.get(withdrawal.reservoirId),kg/seconds);var trial=new ArrayList<>(accepted);trial.add(transfer);
                try {
                    var result=solver.solveTrial(new PassiveNetwork(original.reservoirs(),original.pipes(),trial),seconds,settings,checkpoint);
                    if(result.boundaries().stream().anyMatch(b->b.nodeId()==boundary&&!b.solids().empty()))break;
                    accepted.add(transfer);candidate=result;withdrawalIds.put(boundary,withdrawal.id);break;
                }catch(SparseNewton.Nonconvergence|IllegalArgumentException infeasible) {}
            }
        }
        return completed(candidate,accepted,withdrawalIds,delivered);
    }
    private Proposal completed(PassiveIntervalSolver.Result candidate,List<ScheduledTransfer> accepted,Map<Long,UUID> withdrawalIds,Map<UUID,MaterialParcel> delivered) {
        var amounts=new HashMap<UUID,double[]>();var energy=new HashMap<UUID,Double>();var external=new ArrayList<ConservativeTransport.BoundaryTransfer>();
        var scheduled=new HashSet<Long>();for(var transfer:accepted)scheduled.add(transfer.id());
        for(var transfer:candidate.boundaries()) {
            var key=withdrawalIds.get(transfer.nodeId());
            if(key!=null){var n=transfer.moles();var total=amounts.computeIfAbsent(key,ignored->new double[weights.length]);for(int c=0;c<n.length;c++)total[c]-=n[c];energy.merge(key,-transfer.totalEnergyJoule(),Double::sum);}
            if(!scheduled.contains(transfer.nodeId()))external.add(transfer);
        }
        var withdrawn=new HashMap<UUID,MaterialParcel>();amounts.forEach((key,n)->withdrawn.put(key,new MaterialParcel(n,weights,energy.get(key),reference)));
        // Request-scoped source terms become explicit owned parcels and disappear from the next interval.
        var graph=new PassiveNetwork(candidate.graph().reservoirs(),candidate.graph().pipes());
        var result=new PassiveIntervalSolver.Result(graph,candidate.advancedSeconds(),candidate.averageMassFlows(),candidate.acceptedSubsteps(),candidate.rejectedSubsteps(),candidate.pumpWorkJoule(),external,candidate.rejectionReasons(),candidate.endpointModes(),candidate.endpointHeads(),candidate.acceptance(),candidate.pipeTransfers());
        return new Proposal(result,delivered,withdrawn);
    }
    private void validateBasis(MaterialParcel parcel) {
        model.solids.validate(parcel.solids());
        if(!parcel.reference().components().equals(reference.components())||!parcel.reference().revision().equals(reference.revision())||!Arrays.equals(parcel.molecularWeights(),weights))throw new IllegalArgumentException("Module material needs explicit basis/reference migration");
        for(int c=0;c<weights.length;c++)if(parcel.reference().offsetJoulesPerMole(c)!=0)throw new IllegalArgumentException("Module material needs explicit energy migration");
    }
}

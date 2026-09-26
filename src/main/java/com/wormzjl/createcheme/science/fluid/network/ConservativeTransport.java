package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.SolverOwnership;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.linalg.*;
import com.wormzjl.createcheme.science.fluid.solver.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import com.wormzjl.createcheme.science.fluid.state.SolidInventory;

/** Conservative reconstruction for a candidate's already-solved flows; it does not solve hydraulics or flash. */
public final class ConservativeTransport {
    private ConservativeTransport() {}
    /**
     * One island's transport linear algebra, retained across its reconstructions the way
     * {@code SparseNewton.Workspace.Shared} is retained across a fork family: one EJML storage that
     * every reconstruction refills in place, and the fill-reducing ordering of each structure the
     * island actually produces.
     *
     * <p>Without it each reconstruction built its own {@link SparseLuSolver.Storage} - an EJML
     * solver, a CSC copy, two dense vectors, a sorter and the scaling buffers - and its own
     * ordering, three or four times per interval for the life of the island. The solved graphs
     * alternate (a step solve and a rate solve of the port graph do not share a sparsity
     * pattern), so the orderings are a small keyed cache while the storage, which
     * reshapes itself, is single and shared.
     *
     * <p>Reuse is bitwise: a {@link SparseLuSolver.Storage} clears every buffer it reads before
     * reading it and its {@code verified} flag is reset by each factorization, and an ordering is
     * only reused for a matrix with the identical structure, so it is the permutation a fresh
     * computation would have produced. Each factorization is consumed inside the reconstruction
     * that produced it, so no holder can be superseded by the next one.
     */
    public static final class Workspace {
        private static final int RETAINED_ORDERINGS=4;
        private final SparseLuSolver.Storage factors;
        private final LinkedHashMap<Structure,SparseLuSolver.Ordering> orderings=new LinkedHashMap<>();
        public Workspace(SolverOwnership ownership){factors=new SparseLuSolver.Storage(ownership);}
        private SparseLuSolver.Factorization factor(SparseMatrix matrix) {
            var structure=Structure.of(matrix);
            var ordering=orderings.get(structure);
            if(ordering==null) {
                if(orderings.size()>=RETAINED_ORDERINGS)orderings.remove(orderings.keySet().iterator().next());
                ordering=SparseLuSolver.prepareOrdering(matrix);orderings.put(structure,ordering);
            }
            return factors.factor(matrix,ordering);
        }
    }
    /** Everything the ordering depends on: the sparsity pattern, and which stored entries are
     * exactly zero, because the reverse Cuthill-McKee graph skips a stored zero. */
    private record Structure(int size,int[] offsets,int[] rows,long[] zeros) {
        private static Structure of(SparseMatrix matrix) {
            int size=matrix.size(),count=matrix.nonzeroCount();
            int[] offsets=new int[size+1],rows=new int[count];long[] zeros=new long[(count+63)/64];
            for(int column=0;column<size;column++)offsets[column]=matrix.columnStart(column);
            offsets[size]=count;
            for(int entry=0;entry<count;entry++) {
                rows[entry]=matrix.rowAt(entry);
                if(matrix.valueAt(entry)==0)zeros[entry>>6]|=1L<<(entry&63);
            }
            return new Structure(size,offsets,rows,zeros);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Structure key&&size==key.size&&Arrays.equals(offsets,key.offsets)
                    &&Arrays.equals(rows,key.rows)&&Arrays.equals(zeros,key.zeros);
        }
        @Override public int hashCode() {
            return 31*(31*(31*size+Arrays.hashCode(offsets))+Arrays.hashCode(rows))+Arrays.hashCode(zeros);
        }
    }
    public record BoundaryTransfer(long nodeId,double[] moles,double totalEnergyJoule,SolidInventory solids,int solidDirection) {
        public BoundaryTransfer(long nodeId,double[] moles,double totalEnergyJoule){this(nodeId,moles,totalEnergyJoule,SolidInventory.EMPTY,0);}
        public BoundaryTransfer{moles=moles.clone();Objects.requireNonNull(solids);if(solidDirection < -1||solidDirection > 1||!solids.empty()&&solidDirection==0)throw new IllegalArgumentException("Invalid solid boundary direction");}
        @Override public double[] moles(){return moles.clone();}
    }
    public record Projection(List<PassiveNetwork.Inventory> inventories,List<FluidThermodynamics.State> states,
                             List<BoundaryTransfer> boundaries,double[] externalMoles,double externalEnergy,double pumpWork,Map<Long,InlineFilter> filters) {
        public Projection(List<PassiveNetwork.Inventory> inventories,List<FluidThermodynamics.State> states,List<BoundaryTransfer> boundaries,double[] externalMoles,double externalEnergy,double pumpWork){this(inventories,states,boundaries,externalMoles,externalEnergy,pumpWork,Map.of());}
        public Projection{filters=Map.copyOf(filters);inventories=List.copyOf(inventories);states=List.copyOf(states);boundaries=List.copyOf(boundaries);externalMoles=externalMoles.clone();}
        @Override public double[] externalMoles(){return externalMoles.clone();}
    }
    /** One-shot: a caller with no retained island workspace builds fresh linear algebra, as before. */
    public static Projection reconstruct(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint) {
        return reconstruct(graph,candidate,flows,heads,dt,model,checkpoint,null);
    }
    public static Projection reconstruct(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint,Workspace workspace) {
        return reconstruct(graph,candidate,flows,heads,dt,model,checkpoint,workspace,false,null);
    }
    /**
     * {@code frozenJunctions} (rate evaluations only): every junction is held at its owned inventory's composition,
     * as the rate-only rows of {@code PassiveStepSolver.Equations.junctionInflow} hold it, and its accumulation over
     * dt is booked as a pseudo-boundary at the junction.
     *
     * <p>{@code draws}: per connection, the mass flow each phase ({@link FluidThermodynamics#GAS}, {@link FluidThermodynamics#OIL},
     * {@link FluidThermodynamics#WATER}) supplied to the outflow of a phase-port end at the solved flow (decision D11, the
     * priority stream; they add up to the solved flow), null for a connection that drew the bulk; null altogether for a
     * graph none of whose outflows drew phases. A caller whose graph has phase ports passes the step solver's draws.
     */
    public static Projection reconstruct(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint,Workspace workspace,boolean frozenJunctions,double[][] draws) {
        if(draws!=null&&draws.length!=flows.length)throw new IllegalArgumentException("Invalid phase draws");
        if(!SolverDiagnostics.ENABLED)return reconstruct0(graph,candidate,flows,heads,dt,model,checkpoint,workspace,frozenJunctions,draws);
        long started=System.nanoTime();boolean previous=SolverDiagnostics.enterReconstruct();
        try{return reconstruct0(graph,candidate,flows,heads,dt,model,checkpoint,workspace,frozenJunctions,draws);}
        finally {
            SolverDiagnostics.leaveReconstruct(previous);
            SolverDiagnostics.reconstructNanos.add(System.nanoTime()-started);SolverDiagnostics.reconstructCalls.increment();
        }
    }
    /**
     * The mass fractions of a node whose composition this reconstruction does not solve for: a
     * prescribed boundary. It is a <em>composition</em> and not a conserved amount, so every entry is read off one state - the
     * candidate - and the stored inventory supplies nothing but the split of the candidate's
     * aggregate solid mass across its populations.
     *
     * <p>A Newton candidate carries its solids as the three aggregate moments and keeps the seed's
     * population list unscaled ({@link com.wormzjl.createcheme.science.fluid.solver.PhaseLayout#decode}),
     * so the stored list is the only place the split can come from; its absolute masses are not.
     * Reading the fluid half off the candidate and the solid half off the stored inventory, both
     * over the candidate's total mass, is what broke: a junction owns no volume, so its total
     * amount is a free scale the Newton moves, and an absolute stored 125.0 kg divided by a total
     * the Newton has shrunk by 0.099 % leaves the fractions summing to 1.000115804 instead of 1.
     * See {@code documentation/FULL_TANK_SOLIDS_EVENT.md}.
     *
     * <p>Where candidate and inventory already agree the scale is exactly 1.0 and this is bit for
     * bit what the two separate reads produced, which is why the exact solver regression does not
     * move.
     */
    private static void storedFractions(double[] result,FluidThermodynamics.State state,PassiveNetwork.Reservoir reservoir,
                                        List<SolidInventory.Key> populationKeys,double[] molecularWeight,int components) {
        var amounts=PhaseLayout.totalAmounts(state);
        for(int c=0;c<components;c++)result[c]=amounts[c]*molecularWeight[c]/state.mass();
        var stored=reservoir.inventory().solids();double storedMass=stored.massKg();
        double scale=storedMass>0?state.solidMoments().mass()/storedMass:0;
        for(int c=0;c<populationKeys.size();c++)result[components+c]=scale*stored.mass(populationKeys.get(c))/state.mass();
    }
    /*
     * A draw vector (decision D11; PhaseDraw.Segment.rates) has six entries: the mass flow of each phase (GAS, OIL, WATER)
     * drawn at the step's end state, then of each drawn at the step's start state (a phase pinned at its capacity). An
     * entry k is phase k % 3 of the end state (the candidate, or the reconstructed donor for the energy) for k < 3 and of
     * the start state (the donor's state in the graph the step starts from) for k >= 3.
     */
    /** The entries a draw vector supplies, in entry order. */
    private static int[] drawnPhases(double[] rates) {
        int count=0;for(double rate:rates)if(rate>0)count++;
        int[] phases=new int[count];int at=0;for(int k=0;k<rates.length;k++)if(rates[k]>0)phases[at++]=k;
        return phases;
    }
    /** The weight of entry {@code k} in its draw's stream, {@code r_k/|q|}; exactly 1 for a draw of one phase. */
    private static double weight(double[] rates,int[] phases,int k) {
        if(phases.length==1)return 1;
        double total=0;for(int p:phases)total+=rates[p];
        return rates[k]/total;
    }
    /** The state entry {@code k} of a draw is read on: {@code end} for an end-state entry, {@code start} for a pinned one. */
    private static FluidThermodynamics.State at(int k,FluidThermodynamics.State end,FluidThermodynamics.State start){return k<3?end:start;}
    /** The mass share of solids in what a connection draws out of its donor: the bulk share {@code solids/mass} of
     * {@code donor} (the double it always was) where it draws the bulk, and otherwise its phases' solid shares weighted by
     * their mass flows (none in the gas; a liquid's share of the solids over its stream's mass). */
    private static double drawnSolidShare(FluidThermodynamics model,double[] rates,FluidThermodynamics.State donor,FluidThermodynamics.State start) {
        if(rates==null)return donor.solidMoments().mass()/donor.mass();
        var phases=drawnPhases(rates);double share=0;
        for(int k:phases){int phase=k%3;var state=at(k,donor,start);
            if(phase!=FluidThermodynamics.GAS)share+=weight(rates,phases,k)*(FluidThermodynamics.phaseSolidShare(state,phase)*state.solidMoments().mass()/model.phaseMass(state,phase));}
        return share;
    }
    /**
     * The mass fractions of one phase stream of {@code donor} (fluid components, then solid populations in
     * {@code populationKeys} order), each over the stream's own mass: the gas's (no solid) or a liquid's with its volume
     * share of every solid; the solid split across populations is the stored inventory's, scaled to the candidate's
     * aggregate solid mass, exactly as {@link #storedFractions} splits a fixed node's. The entries sum to 1 to rounding.
     */
    private static double[] phaseFractions(FluidThermodynamics model,int phase,FluidThermodynamics.State donor,
                                           PassiveNetwork.Reservoir reservoir,List<SolidInventory.Key> populationKeys,double[] molecularWeight,int components) {
        var result=new double[components+populationKeys.size()];
        double[] n=FluidThermodynamics.phaseMoles(donor,phase);double mass=model.phaseMass(donor,phase);
        for(int c=0;c<components;c++)result[c]=n[c]*molecularWeight[c]/mass;
        if(phase!=FluidThermodynamics.GAS) {
            var stored=reservoir.inventory().solids();double storedMass=stored.massKg();
            double scale=FluidThermodynamics.phaseSolidShare(donor,phase)*(storedMass>0?donor.solidMoments().mass()/storedMass:0);
            for(int c=0;c<populationKeys.size();c++)result[components+c]=scale*stored.mass(populationKeys.get(c))/mass;
        }
        return result;
    }
    /**
     * The mass fractions of what a phase-port outflow draws (decision D11: the priority stream): its phases' fractions
     * ({@link #phaseFractions}) weighted by their mass flows at the solved flow, the end-state ones frozen from the
     * Newton's converged candidate and the pinned ones read on the step's start state, exactly one phase's where it draws
     * one. Booked as a fixed donor on the pinned flow, so {@code dt q w} leaves the donor and reaches the receiver and mass
     * is conserved whatever the weights.
     */
    private static double[] streamFractions(FluidThermodynamics model,double[] rates,FluidThermodynamics.State donor,FluidThermodynamics.State start,
                                            PassiveNetwork.Reservoir reservoir,List<SolidInventory.Key> populationKeys,double[] molecularWeight,int components) {
        var phases=drawnPhases(rates);
        if(phases.length==1)return phaseFractions(model,phases[0]%3,at(phases[0],donor,start),reservoir,populationKeys,molecularWeight,components);
        var result=new double[components+populationKeys.size()];
        for(int k:phases){var w=phaseFractions(model,k%3,at(k,donor,start),reservoir,populationKeys,molecularWeight,components);double weight=weight(rates,phases,k);
            for(int c=0;c<result.length;c++)result[c]+=weight*w[c];}
        return result;
    }
    private static Projection reconstruct0(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] solvedFlows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint,Workspace workspace,boolean frozen,double[][] draws) {
        int nodes=graph.reservoirs().size(),components=model.hydrocarbon.componentCount()+1;
        if(candidate.size()!=nodes||solvedFlows.length!=graph.pipes().size()||heads.length!=solvedFlows.length||!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Invalid transport reconstruction");
        for(double q:solvedFlows)if(!Double.isFinite(q))throw new IllegalArgumentException("Nonfinite candidate flow/head");
        // The flows this reconstruction books: the solved flows with every junction's mass held; see pinnedFlows.
        final double[] flows=pinnedFlows(graph,candidate,solvedFlows,model,draws);
        var populationBasis=new TreeMap<SolidInventory.Key,SolidInventory.Population>();
        for(var node:graph.reservoirs())for(var population:node.inventory().solids().populations())populationBasis.merge(population.key(),population,(a,b)->{if(!a.material().equals(b.material()))throw new IllegalArgumentException("Conflicting solid material definitions");return a;});
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in)for(var population:in.solidsPerSecond().populations())populationBasis.merge(population.key(),population,(a,b)->{if(!a.material().equals(b.material()))throw new IllegalArgumentException("Conflicting solid material definitions");return a;});
        var populationKeys=List.copyOf(populationBasis.keySet());int conserved=components+populationKeys.size();
        int[] index=new int[nodes];Arrays.fill(index,-1);int count=0;
        double[] molecularWeight=model.molecularWeights();
        double[][] old=new double[nodes][],fractions=new double[nodes][conserved];
        double[] endMass=new double[nodes],incoming=new double[nodes],outgoing=new double[nodes];
        for(int node=0;node<nodes;node++) {
            var reservoir=graph.reservoirs().get(node);old[node]=reservoir.inventory().moles();
            for(int c=0;c<components;c++)endMass[node]+=old[node][c]*molecularWeight[c];
            endMass[node]+=reservoir.inventory().solids().massKg();
            if(frozen&&reservoir.junction()) {
                // Frozen rate: a known composition, the owned inventory's (fluid and solid mass fractions).
                var solids=reservoir.inventory().solids();
                for(int c=0;c<components;c++)fractions[node][c]=old[node][c]*molecularWeight[c]/endMass[node];
                for(int c=0;c<populationKeys.size();c++)fractions[node][components+c]=solids.mass(populationKeys.get(c))/endMass[node];
            }
            else if(!reservoir.fixed())index[node]=count++;
            else storedFractions(fractions[node],candidate.get(node),reservoir,populationKeys,molecularWeight,components);
        }
        // A phase-port outflow (a draw) is booked as a fixed donor with its stream fractions frozen from the candidate
        // (plan 3.3 item 2; decision D11: the priority stream at the solved flow): it leaves its donor's diagonal (outgoing)
        // and enters the donor's right-hand side as -dt q w_stream and the receiver's as +dt q w_stream, on the pinned flow
        // like every other booking. The matrix stays one matrix for every component, so one factorization still serves them
        // all.
        double[][] drawn=new double[flows.length][];
        // Which nodes receive anything in this step (a connection's inflow, a scheduled injection): a frozen stream may not
        // book out of its donor a component the donor neither holds nor receives (see unbacked below).
        boolean[] receives=new boolean[nodes];
        for(int edge=0;edge<flows.length;edge++)if(flows[edge]!=0){var pipe=graph.pipes().get(edge);receives[flows[edge]>0?pipe.second():pipe.first()]=true;}
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection)receives[transfer.node()]=true;
        for(int edge=0;edge<flows.length;edge++) {
            if(!Double.isFinite(flows[edge])||!Double.isFinite(heads[edge]))throw new IllegalArgumentException("Nonfinite candidate flow/head");
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();double massRate=Math.abs(flows[edge]);
            var rates=draws==null?null:draws[edge];boolean phase=rates!=null;
            if(phase) {
                drawn[edge]=streamFractions(model,rates,candidate.get(donor),graph.reservoirs().get(donor).state(),graph.reservoirs().get(donor),populationKeys,molecularWeight,components);
                // Unbacked traces. A vessel's Newton seed carries an entry trace (1e-12 of its amount) of every component the
                // island can bring it, whether or not anything does in this step, and the candidate keeps some of it; the
                // implicit bulk booking below lands such a component at exactly zero in a donor whose inventory lacks it
                // ((m + dt Q) w = m w_old), but a frozen stream would book the candidate's trace out of an inventory of
                // zero and refuse the step as a negative reconstruction at every step size (measured: 5e-25 mol of
                // nitrogen in a methane tank drawn through a VAPOR port into a junction a nitrogen tank also feeds; the
                // WP1 tree fails the same way). A donor that neither holds a component nor receives anything in the step
                // cannot deliver it, so its stream books none of it; the ledger stays exact (donor and receiver book the
                // same w).
                if(!receives[donor]&&!graph.reservoirs().get(donor).fixed()) {
                    var reservoir=graph.reservoirs().get(donor);
                    for(int c=0;c<components;c++)if(old[donor][c]==0)drawn[edge][c]=0;
                    for(int c=0;c<populationKeys.size();c++)if(reservoir.inventory().solids().mass(populationKeys.get(c))==0)drawn[edge][components+c]=0;
                }
            }
            double deliveredRate=pipe.filter()==null?massRate:massRate*(1-drawnSolidShare(model,rates,candidate.get(donor),graph.reservoirs().get(donor).state()));
            if(!phase)outgoing[donor]+=massRate;
            incoming[receiver]+=deliveredRate;endMass[donor]-=dt*massRate;endMass[receiver]+=dt*deliveredRate;
        }
        for(var transfer:graph.scheduledTransfers()) {
            int node=transfer.node();
            if(transfer instanceof ScheduledTransfer.Withdrawal out){outgoing[node]+=out.massKgPerSecond();endMass[node]-=dt*out.massKgPerSecond();}
            else if(transfer instanceof ScheduledTransfer.Injection in){var n=in.molesPerSecond();for(int c=0;c<components;c++)endMass[node]+=dt*n[c]*molecularWeight[c];endMass[node]+=dt*in.solidsPerSecond().massKg();}
        }
        // Every vessel and every junction (it owns stock) takes the vessel row below. A junction's diagonal
        // endMass+dt*outgoing = m_old+dt*incoming, so its row is (m_old/dt+Q) w = (m_old/dt) w_old + sum q w_e,
        // exactly the Newton junction row, and the booked inventory is endMass*w (exact ledger).
        var columns=new ArrayList<TreeMap<Integer,Double>>();for(int c=0;c<count;c++)columns.add(new TreeMap<>());
        double[][] rhs=new double[conserved][count];
        for(int node=0;node<nodes;node++)if(index[node]>=0) {
            checkpoint.run();int row=index[node];var reservoir=graph.reservoirs().get(node);
            if(!(endMass[node]>0)||!Double.isFinite(endMass[node]))throw new SparseNewton.Nonconvergence("Candidate overdraws a reservoir");
            add(columns,row,row,endMass[node]+dt*outgoing[node]);
            for(int c=0;c<components;c++)rhs[c][row]=old[node][c]*molecularWeight[c];
            for(int c=0;c<populationKeys.size();c++)rhs[components+c][row]=reservoir.inventory().solids().mass(populationKeys.get(c));
        }
        var solidColumns=new ArrayList<TreeMap<Integer,Double>>();for(var column:columns)solidColumns.add(new TreeMap<>(column));
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            if(drawn[edge]!=null&&flows[edge]!=0) {
                double weight=dt*Math.abs(flows[edge]);var w=drawn[edge];
                // The donor loses the stream, solids included whether or not a filter then captures them.
                if(index[donor]>=0)for(int c=0;c<conserved;c++)rhs[c][index[donor]]-=weight*w[c];
                if(index[receiver]>=0)for(int c=0;c<conserved;c++)if(c<components||pipe.filter()==null)rhs[c][index[receiver]]+=weight*w[c];
                continue;
            }
            if(index[receiver]<0||flows[edge]==0)continue;
            double weight=dt*Math.abs(flows[edge]);
            if(index[donor]>=0){add(columns,index[donor],index[receiver],-weight);if(pipe.filter()==null)add(solidColumns,index[donor],index[receiver],-weight);}
            else for(int c=0;c<conserved;c++)if(c<components||pipe.filter()==null)rhs[c][index[receiver]]+=weight*fractions[donor][c];
        }
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in&&index[in.node()]>=0) {
            var n=in.molesPerSecond();for(int c=0;c<components;c++)rhs[c][index[in.node()]]+=dt*n[c]*molecularWeight[c];
            for(int c=0;c<populationKeys.size();c++)rhs[components+c][index[in.node()]]+=dt*in.solidsPerSecond().mass(populationKeys.get(c));
        }
        double[][] solved;
        // One backward-error check on the first component vector qualifies this factorization; the
        // remaining component columns are bounded by the junction continuity and conservation
        // checks below, which are the quantities this solve exists to produce.
        try{var system=matrix(columns);
            if(populationKeys.isEmpty()||graph.pipes().stream().noneMatch(p->p.filter()!=null))solved=(workspace==null?SparseLuSolver.factor(system):workspace.factor(system)).solveMultiple(rhs,SparseLuSolver.Verification.UNTIL_VERIFIED);
            else {
                solved=new double[conserved][];
                var fluid=(workspace==null?SparseLuSolver.factor(system):workspace.factor(system)).solveMultiple(Arrays.copyOf(rhs,components),SparseLuSolver.Verification.UNTIL_VERIFIED);
                var solidSystem=matrix(solidColumns);var solids=(workspace==null?SparseLuSolver.factor(solidSystem):workspace.factor(solidSystem)).solveMultiple(Arrays.copyOfRange(rhs,components,conserved),SparseLuSolver.Verification.UNTIL_VERIFIED);
                System.arraycopy(fluid,0,solved,0,components);System.arraycopy(solids,0,solved,components,solids.length);
            }}
        catch(SparseLuSolver.SolveFailure failure){throw new SparseNewton.Nonconvergence("Transport reconstruction failed: "+failure.getMessage());}
        double[][] moles=new double[nodes][];var states=new ArrayList<FluidThermodynamics.State>();
        // A junction's owned inventory (endMass*w) and its solid stock; its state keeps the Newton's free amount scale,
        // which the junction's amount row pins.
        double[][] ownedMoles=new double[nodes][];var ownedSolids=new SolidInventory[nodes];
        for(int node=0;node<nodes;node++) {
            var reservoir=graph.reservoirs().get(node);if(index[node]<0){moles[node]=old[node];states.add(candidate.get(node));continue;}
            double total=0,molesPerKg=0;
            for(int c=0;c<components;c++){double w=solved[c][index[node]];if(w<0||!Double.isFinite(w))throw new SparseNewton.Nonconvergence("Negative transport reconstruction");fractions[node][c]=w;total+=w;molesPerKg+=w/molecularWeight[c];}
            for(int c=components;c<conserved;c++){double w=solved[c][index[node]];if(w<0||!Double.isFinite(w))throw new SparseNewton.Nonconvergence("Negative solid reconstruction");fractions[node][c]=w;total+=w;}
            if(Math.abs(total-1)>1e-8)throw new SparseNewton.Nonconvergence("Junction mass continuity does not close");
            if(reservoir.junction()) {
                ownedMoles[node]=new double[components];for(int c=0;c<components;c++)ownedMoles[node][c]=endMass[node]*fractions[node][c]/molecularWeight[c];
                var owned=new ArrayList<SolidInventory.Population>();
                for(int c=0;c<populationKeys.size();c++){var source=populationBasis.get(populationKeys.get(c));owned.add(new SolidInventory.Population(source.material(),source.size(),endMass[node]*fractions[node][components+c]));}
                ownedSolids[node]=new SolidInventory(owned);
            }
            double mass=reservoir.junction()?PhaseLayout.sum(PhaseLayout.totalAmounts(candidate.get(node)))/molesPerKg:endMass[node];
            moles[node]=new double[components];for(int c=0;c<components;c++)moles[node][c]=mass*fractions[node][c]/molecularWeight[c];
            var populations=new ArrayList<SolidInventory.Population>();
            for(int c=0;c<populationKeys.size();c++){var source=populationBasis.get(populationKeys.get(c));populations.add(new SolidInventory.Population(source.material(),source.size(),mass*fractions[node][components+c]));}
            try{states.add(repartition(model,candidate.get(node),moles[node]).withSolids(new SolidInventory(populations)));}
            catch(com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation violation){throw violation.at(reservoir.id());}
        }
        double[] energy=new double[nodes];for(int node=0;node<nodes;node++)energy[node]=graph.reservoirs().get(node).inventory().internalEnergy();
        double[] external=new double[components];double externalEnergy=0,pumpWork=0;var boundaries=new ArrayList<BoundaryTransfer>();
        for(var transfer:graph.scheduledTransfers()) {
            checkpoint.run();int node=transfer.node();var state=states.get(node);double[] moved=new double[components];double movedEnergy,mass=0;
            if(transfer instanceof ScheduledTransfer.Withdrawal out) {
                mass=-dt*out.massKgPerSecond();
                for(int c=0;c<components;c++)moved[c]=mass*fractions[node][c]/molecularWeight[c];
                movedEnergy=mass*(state.enthalpy()/state.mass()+PassiveStepSolver.GRAVITY*graph.reservoirs().get(node).elevation());
            } else {
                var in=(ScheduledTransfer.Injection)transfer;var n=in.molesPerSecond();
                for(int c=0;c<components;c++){moved[c]=dt*n[c];mass+=moved[c]*molecularWeight[c];}
                movedEnergy=dt*in.totalEnergyPerSecond();
            }
            SolidInventory movedSolids;int solidDirection;
            if(transfer instanceof ScheduledTransfer.Withdrawal out){movedSolids=state.solids().scale(dt*out.massKgPerSecond()/state.mass());solidDirection=-1;}
            else{movedSolids=((ScheduledTransfer.Injection)transfer).solidsPerSecond().scale(dt);solidDirection=1;mass+=movedSolids.massKg();}
            energy[node]+=movedEnergy-mass*PassiveStepSolver.GRAVITY*graph.reservoirs().get(node).elevation();
            for(int c=0;c<components;c++)external[c]+=moved[c];externalEnergy+=movedEnergy;
            boundaries.add(new BoundaryTransfer(transfer.id(),moved,movedEnergy,movedSolids,solidDirection));
        }
        var filters=new HashMap<Long,InlineFilter>();
        // A frozen booking tallies what each junction gains and loses over its connections, to form its pseudo-boundary
        // below, solids included.
        double[][] accMoles=frozen?new double[nodes][components]:null;SignedSolids[] accSolids=frozen?new SignedSolids[nodes]:null;
        if(frozen)for(int node=0;node<nodes;node++)accSolids[node]=new SignedSolids();
        for(int edge=0;edge<flows.length;edge++) {
            checkpoint.run();var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            var upstream=states.get(donor);double moved=dt*Math.abs(flows[edge]),upstreamZ=graph.reservoirs().get(donor).elevation();
            // A phase-port outflow carries the frozen stream fractions booked above, and the stream's specific enthalpy on
            // the reconstructed donor (whatever number is booked leaves the donor and reaches the receiver, so the energy
            // ledger closes exactly either way); a bulk one its donor's end composition and enthalpy, as always.
            var w=drawn[edge]!=null?drawn[edge]:fractions[donor];var rates=drawn[edge]==null?null:draws[edge];var phases=rates==null?null:drawnPhases(rates);
            var startState=graph.reservoirs().get(donor).state();
            double specificEnthalpy;
            if(rates==null)specificEnthalpy=upstream.enthalpy()/upstream.mass();
            else{specificEnthalpy=0;for(int k:phases)specificEnthalpy+=weight(rates,phases,k)*model.phaseSpecificEnthalpy(at(k,upstream,startState),k%3,null);}
            double movedEnergy=moved*(specificEnthalpy+PassiveStepSolver.GRAVITY*upstreamZ);
            energy[donor]-=movedEnergy-moved*PassiveStepSolver.GRAVITY*upstreamZ;
            SolidInventory movedSolids;
            if(drawn[edge]==null)movedSolids=upstream.solids().scale(moved/upstream.mass());
            else{var moving=new ArrayList<SolidInventory.Population>();
                for(int c=0;c<populationKeys.size();c++){var source=populationBasis.get(populationKeys.get(c));moving.add(new SolidInventory.Population(source.material(),source.size(),moved*w[components+c]));}
                movedSolids=new SolidInventory(moving);}
            double deliveredEnergy=movedEnergy,deliveredMass=moved;
            if(pipe.filter()!=null){
                double capturedEnergy;
                if(drawn[edge]==null)capturedEnergy=moved*(upstream.solidMoments().enthalpy(upstream.temperature(),upstream.pressure())/upstream.mass()+upstream.solidMoments().mass()/upstream.mass()*PassiveStepSolver.GRAVITY*upstreamZ);
                else{double perMass=0;
                    // Each liquid phase's share of the solids, over its stream's mass, weighted by its mass flow (none in the gas).
                    for(int k:phases)if(k%3!=FluidThermodynamics.GAS){var state=at(k,upstream,startState);int phase=k%3;double share=FluidThermodynamics.phaseSolidShare(state,phase),streamMass=model.phaseMass(state,phase);
                        perMass+=weight(rates,phases,k)*(share*state.solidMoments().enthalpy(state.temperature(),state.pressure())/streamMass+share*state.solidMoments().mass()/streamMass*PassiveStepSolver.GRAVITY*upstreamZ);}
                    capturedEnergy=moved*perMass;}
                filters.put(pipe.id(),pipe.filter().add(movedSolids,capturedEnergy));deliveredEnergy-=capturedEnergy;deliveredMass-=movedSolids.massKg();}
            energy[receiver]+=deliveredEnergy-deliveredMass*PassiveStepSolver.GRAVITY*graph.reservoirs().get(receiver).elevation();
            double work=0;
            if(pipe.control() instanceof FlowControl.Mover mover&&flows[edge]>0){var suction=states.get(pipe.first());
                // Metered on the suction stream's specific volume (the suction's bulk at a BULK end) and, for a compressor,
                // the suction node's pressure (decision D8: ideal isothermal work on suction properties); the Newton's
                // energy rows book the same power (PassiveStepSolver.Equations.nodeAccumulate).
                double specificVolume;
                if(rates==null)specificVolume=suction.volume()/suction.mass();
                else{specificVolume=0;for(int k:phases){var state=at(k,suction,graph.reservoirs().get(pipe.first()).state());specificVolume+=weight(rates,phases,k)*(FluidThermodynamics.phaseVolume(state,k%3)/model.phaseMass(state,k%3));}}
                work=switch(mover) {
                    case FlowControl.Pump pump->rates==null?dt*flows[edge]*suction.volume()/suction.mass()*Math.max(0,heads[edge])/pump.efficiency()
                            :dt*flows[edge]*specificVolume*Math.max(0,heads[edge])/pump.efficiency();
                    case FlowControl.Compressor compressor->dt*compressor.power(flows[edge],heads[edge],1/specificVolume,suction.pressure());
                };
                energy[receiver]+=work;pumpWork+=work;}
            if(frozen) {
                var species=new double[components];for(int c=0;c<components;c++)species[c]=moved*w[c]/molecularWeight[c];
                var delivered=pipe.filter()==null?movedSolids:SolidInventory.EMPTY;
                if(graph.reservoirs().get(receiver).junction()){for(int c=0;c<components;c++)accMoles[receiver][c]+=species[c];accSolids[receiver].add(delivered,1);}
                if(graph.reservoirs().get(donor).junction()){for(int c=0;c<components;c++)accMoles[donor][c]-=species[c];accSolids[donor].add(movedSolids,-1);}
            }
            if(graph.reservoirs().get(donor).fixed()||graph.reservoirs().get(receiver).fixed()) {
                var transferred=new double[components];for(int c=0;c<components;c++)transferred[c]=moved*w[c]/molecularWeight[c];
                if(graph.reservoirs().get(donor).fixed()){boundaries.add(new BoundaryTransfer(graph.reservoirs().get(donor).id(),transferred,movedEnergy,drawn[edge]==null?upstream.solids().scale(moved/upstream.mass()):movedSolids,1));for(int c=0;c<components;c++)external[c]+=transferred[c];externalEnergy+=movedEnergy;}
                if(graph.reservoirs().get(receiver).fixed()){var removed=transferred.clone();for(int c=0;c<components;c++){removed[c]=-removed[c];external[c]+=removed[c];}boundaries.add(new BoundaryTransfer(graph.reservoirs().get(receiver).id(),removed,-deliveredEnergy-work,pipe.filter()==null?movedSolids:SolidInventory.EMPTY,-1));externalEnergy-=deliveredEnergy+work;}
            }
        }
        // Frozen rate: each junction keeps its inventory and books its accumulation over dt as a pseudo-boundary at
        // itself, signed like a port's (positive = into the network), formed from the amounts the pipe loop actually
        // booked, with solids: populations the junction gained leave the network into it (direction -1), any it lost
        // enter from it (a second transfer, direction +1, no moles, no energy). The energy of the pair is
        // -(field change + accumulated mass g z), so the ledger reads exactly the field change.
        if(frozen)for(int node=0;node<nodes;node++)if(graph.reservoirs().get(node).junction()) {
            checkpoint.run();double accumulatedMass=0;var booked=accMoles[node].clone();
            for(int c=0;c<components;c++){accumulatedMass+=booked[c]*molecularWeight[c];booked[c]=-booked[c];external[c]+=booked[c];}
            var gained=accSolids[node].part(1);var lost=accSolids[node].part(-1);accumulatedMass+=gained.massKg()-lost.massKg();
            double energyChange=energy[node]-graph.reservoirs().get(node).inventory().internalEnergy(),z=graph.reservoirs().get(node).elevation();
            double bookedEnergy=-(energyChange+accumulatedMass*PassiveStepSolver.GRAVITY*z);externalEnergy+=bookedEnergy;
            long id=graph.reservoirs().get(node).id();
            boundaries.add(new BoundaryTransfer(id,booked,bookedEnergy,gained,-1));
            if(!lost.empty())boundaries.add(new BoundaryTransfer(id,new double[components],0,lost,1));
        }
        var inventories=new ArrayList<PassiveNetwork.Inventory>();
        for(int node=0;node<nodes;node++) {
            var oldNode=graph.reservoirs().get(node);var state=states.get(node);
            if(frozen&&oldNode.junction()){inventories.add(oldNode.inventory());continue;}
            // A junction books endMass*w and its energy ledger, whose field is in enthalpy form (m*h, potential energy
            // excluded, as for a vessel's internal energy).
            if(oldNode.junction()){inventories.add(new PassiveNetwork.Inventory(oldNode.inventory().volume(),ownedMoles[node],energy[node],ownedSolids[node]));continue;}
            inventories.add(oldNode.fixed()?oldNode.inventory():new PassiveNetwork.Inventory(oldNode.inventory().volume(),moles[node],energy[node],state.solids()));
        }
        return new Projection(inventories,states,boundaries,external,externalEnergy,pumpWork,filters);
    }
    /**
     * The flows a reconstruction books so that every junction keeps exactly the mass it owns, m_J (the mass of its
     * inventory; {@link PassiveNetwork#sizeJunctionHoldups}). The Newton's net-mass row at a junction closes only to its
     * tolerance, so {@code dt (In - Out) != 0} by that residual, and an owned stock with no restoring term integrates it
     * (review 7.9 (d)). Here the residual is booked onto the junction's own connections, per junction J in flow order:
     * <ul>
     * <li>with a positive outflow, its outflows are booked scaled by {@code f_J = In'/Out}, where In' is what its
     * inflows deliver as booked (an upstream junction's factor applied, filter capture removed): the excess leaves
     * with the junction's own composition;</li>
     * <li>with no outflow, its inflows from non-junction donors are booked scaled by
     * {@code g_J = (Out - In'_junction)/In'_other} when that lies in [0, 1], i.e. to zero (an inflow is only ever
     * reduced: amplifying one moves the junction's composition off the Newton's, and the equation gate refused exactly
     * that, review 7.10 run 69b);</li>
     * <li>otherwise (a junction cycle, or nothing to scale) it stays unpinned.</li>
     * </ul>
     * Pinned, {@code dt (In' - Out') = 0}. It applies to every reconstruction: the step solves (the junction's booked
     * inventory is then m_J w) and the frozen rate booking (the junction's pseudo-boundary then carries no mass).
     *
     * <p>Exact: every edge still books one amount at both ends (a receiver's vessel row, a fixed node's boundary,
     * the pipe loop and the pseudo-boundary tallies all read the same booked rate), so sum_i (N_i - N_i,old) = sum of
     * boundaries per component, solid population and energy holds as before. Only the booking moves: the flows,
     * states and pipe histories the Newton produced are unchanged. The total mass (fluid and solids, as minted) is
     * pinned.
     */
    private static double[] pinnedFlows(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,FluidThermodynamics model,double[][] draws) {
        int nodes=graph.reservoirs().size();boolean[] junction=new boolean[nodes];boolean any=false;
        for(int node=0;node<nodes;node++)any|=junction[node]=graph.reservoirs().get(node).junction();
        if(!any)return flows;
        int[] indegree=new int[nodes];
        for(int edge=0;edge<flows.length;edge++){if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=flows[edge]>0?pipe.first():pipe.second(),receiver=flows[edge]>0?pipe.second():pipe.first();if(junction[donor]&&junction[receiver])indegree[receiver]++;}
        var queue=new ArrayDeque<Integer>();for(int node=0;node<nodes;node++)if(junction[node]&&indegree[node]==0)queue.add(node);
        double[] factor=new double[flows.length];Arrays.fill(factor,1);
        while(!queue.isEmpty()) {
            int node=queue.poll();double fromJunctions=0,fromOthers=0,out=0;
            for(int edge=0;edge<flows.length;edge++) {
                if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=flows[edge]>0?pipe.first():pipe.second(),receiver=flows[edge]>0?pipe.second():pipe.first();
                double rate=Math.abs(flows[edge]);
                if(receiver==node){double delivered=rate*factor[edge];if(pipe.filter()!=null)delivered*=1-drawnSolidShare(model,draws==null?null:draws[edge],candidate.get(donor),graph.reservoirs().get(donor).state());if(junction[donor])fromJunctions+=delivered;else fromOthers+=delivered;}
                if(donor==node)out+=rate;
            }
            double outflow=fromJunctions+fromOthers,inflow=out-fromJunctions;
            if(out>0&&Double.isFinite(outflow)){double f=outflow/out;for(int edge=0;edge<flows.length;edge++){if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);if((flows[edge]>0?pipe.first():pipe.second())==node)factor[edge]=f;}}
            else if(fromOthers>0&&inflow>=0&&inflow<=fromOthers){double g=inflow/fromOthers;for(int edge=0;edge<flows.length;edge++){if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=flows[edge]>0?pipe.first():pipe.second(),receiver=flows[edge]>0?pipe.second():pipe.first();if(receiver==node&&!junction[donor])factor[edge]=g;}}
            for(int edge=0;edge<flows.length;edge++){if(flows[edge]==0)continue;var pipe=graph.pipes().get(edge);int donor=flows[edge]>0?pipe.first():pipe.second(),receiver=flows[edge]>0?pipe.second():pipe.first();if(donor==node&&junction[receiver]&&--indegree[receiver]==0)queue.add(receiver);}
        }
        var booked=flows.clone();for(int edge=0;edge<flows.length;edge++)booked[edge]*=factor[edge];
        return booked;
    }
    /** A signed per-population solid tally of a junction's booked flows. */
    private static final class SignedSolids {
        private final TreeMap<SolidInventory.Key,SolidInventory.Population> basis=new TreeMap<>();
        private final TreeMap<SolidInventory.Key,Double> mass=new TreeMap<>();
        void add(SolidInventory stock,double factor){for(var p:stock.populations()){basis.putIfAbsent(p.key(),p);mass.merge(p.key(),factor*p.massKg(),Double::sum);}}
        /** The positive part of sign * tally, as a stock. */
        SolidInventory part(int sign) {
            var result=new ArrayList<SolidInventory.Population>();
            for(var entry:mass.entrySet()){double m=sign*entry.getValue();if(m>0){var p=basis.get(entry.getKey());result.add(new SolidInventory.Population(p.material(),p.size(),m));}}
            return new SolidInventory(result);
        }
    }
    static FluidThermodynamics.State repartition(FluidThermodynamics model,FluidThermodynamics.State state,double[] moles) {
        double[] oldL=state.liquidView(),oldV=state.vaporView(),l=new double[oldL.length],v=new double[l.length];
        for(int c=0;c<l.length;c++) {
            double total=oldL[c]+oldV[c];
            if(total==0){if(state.liquidVolume()>0)l[c]=moles[c];else v[c]=moles[c];}
            else if(oldL[c]<oldV[c]){l[c]=moles[c]*(oldL[c]/total);v[c]=moles[c]-l[c];}
            else{v[c]=moles[c]*(oldV[c]/total);l[c]=moles[c]-v[c];}
        }
        double water=state.waterLiquid()+state.waterVapor(),wl,wv;
        if(water==0){wl=state.vaporVolume()>0?0:moles[l.length];wv=moles[l.length]-wl;}
        else if(state.waterLiquid()<state.waterVapor()){wl=moles[l.length]*(state.waterLiquid()/water);wv=moles[l.length]-wl;}
        else{wv=moles[l.length]*(state.waterVapor()/water);wl=moles[l.length]-wv;}
        return model.adoptingState(state.temperature(),state.pressure(),l,v,wl,wv,state.hydrocarbonPartialPressure(),null).withSolidState(state.solids(),state.solidMoments());
    }
    private static void add(List<TreeMap<Integer,Double>> columns,int column,int row,double value){columns.get(column).merge(row,value,Double::sum);}
    private static SparseMatrix matrix(List<TreeMap<Integer,Double>> columns) {
        int count=columns.size();int[] offsets=new int[count+1];for(int c=0;c<count;c++)offsets[c+1]=offsets[c]+columns.get(c).size();
        int[] rows=new int[offsets[count]];double[] values=new double[rows.length];int at=0;
        for(var column:columns)for(var entry:column.entrySet()){rows[at]=entry.getKey();values[at++]=entry.getValue();}
        return SparseMatrix.adopting(count,offsets,rows,values);
    }
}

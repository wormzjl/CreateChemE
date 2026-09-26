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
    /** The delivered mass rate a zero-holdup junction has to receive before what arrives, rather
     * than what it stored, decides its contents. It is the floor
     * {@code PassiveStepSolver.Equations.junctionInflow} and {@code nodeSolidRows} already apply,
     * repeated here so that the reconstruction mixes exactly the junctions the equations mixed. */
    static final double JUNCTION_INFLOW_FLOOR=1e-14;
    /**
     * One island's transport linear algebra, retained across its reconstructions the way
     * {@code SparseNewton.Workspace.Shared} is retained across a fork family: one EJML storage that
     * every reconstruction refills in place, and the fill-reducing ordering of each structure the
     * island actually produces.
     *
     * <p>Without it each reconstruction built its own {@link SparseLuSolver.Storage} - an EJML
     * solver, a CSC copy, two dense vectors, a sorter and the scaling buffers - and its own
     * ordering, three or four times per interval for the life of the island. The stage graphs
     * alternate (the algebraic port solve, the two implicit stages and the endpoint rate do not
     * share a sparsity pattern), so the orderings are a small keyed cache while the storage, which
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
        if(!SolverDiagnostics.ENABLED)return reconstruct0(graph,candidate,flows,heads,dt,model,checkpoint,workspace);
        long started=System.nanoTime();boolean previous=SolverDiagnostics.enterReconstruct();
        try{return reconstruct0(graph,candidate,flows,heads,dt,model,checkpoint,workspace);}
        finally {
            SolverDiagnostics.leaveReconstruct(previous);
            SolverDiagnostics.reconstructNanos.add(System.nanoTime()-started);SolverDiagnostics.reconstructCalls.increment();
        }
    }
    /**
     * The mass fractions of a node whose composition this reconstruction does not solve for: a
     * prescribed boundary, or a zero-holdup junction nothing is delivering into. Both are a
     * <em>composition</em> and not a conserved amount, so every entry is read off one state - the
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
    private static Projection reconstruct0(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint,Workspace workspace) {
        int nodes=graph.reservoirs().size(),components=model.hydrocarbon.componentCount()+1;
        if(candidate.size()!=nodes||flows.length!=graph.pipes().size()||heads.length!=flows.length||!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Invalid transport reconstruction");
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
            if(!reservoir.fixed())index[node]=count++;
            else storedFractions(fractions[node],candidate.get(node),reservoir,populationKeys,molecularWeight,components);
        }
        for(int edge=0;edge<flows.length;edge++) {
            if(!Double.isFinite(flows[edge])||!Double.isFinite(heads[edge]))throw new IllegalArgumentException("Nonfinite candidate flow/head");
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();double massRate=Math.abs(flows[edge]);
            double deliveredRate=pipe.filter()==null?massRate:massRate*(1-candidate.get(donor).solidMoments().mass()/candidate.get(donor).mass());
            outgoing[donor]+=massRate;incoming[receiver]+=deliveredRate;endMass[donor]-=dt*massRate;endMass[receiver]+=dt*deliveredRate;
        }
        for(var transfer:graph.scheduledTransfers()) {
            int node=transfer.node();
            if(transfer instanceof ScheduledTransfer.Withdrawal out){outgoing[node]+=out.massKgPerSecond();endMass[node]-=dt*out.massKgPerSecond();}
            else if(transfer instanceof ScheduledTransfer.Injection in){var n=in.molesPerSecond();for(int c=0;c<components;c++)endMass[node]+=dt*n[c]*molecularWeight[c];endMass[node]+=dt*in.solidsPerSecond().massKg();}
        }
        // Whether a zero-holdup junction is fed, decided exactly as the equations decide it. The
        // junction rows of PassiveStepSolver take a junction's mass fractions, its specific
        // enthalpy and its aggregate solid moments from what arrives only while the arriving mass
        // rate exceeds this same floor, and retain the stored guess below it; both accumulate the
        // identical delivered rate over the same edges in the same order, so the two agree bit for
        // bit. Asking here only whether a flow is exactly zero did not: an island at rest carries
        // flows of order 1e-14 kg/s, which is nonzero and below the floor, so the reconstruction
        // mixed a junction the equations had retained - and on the far side of a filter, where the
        // arriving stream carries no solids at all, that disagreement is the junction's whole
        // solid fraction. A clear island never noticed, because what arrives and what was stored
        // are the same fluid.
        boolean[] fed=new boolean[nodes];
        for(int node=0;node<nodes;node++)fed[node]=!graph.reservoirs().get(node).junction()||incoming[node]>JUNCTION_INFLOW_FLOOR;
        var columns=new ArrayList<TreeMap<Integer,Double>>();for(int c=0;c<count;c++)columns.add(new TreeMap<>());
        double[][] rhs=new double[conserved][count];
        for(int node=0;node<nodes;node++)if(index[node]>=0) {
            checkpoint.run();int row=index[node];var reservoir=graph.reservoirs().get(node);
            if(reservoir.junction()) {
                add(columns,row,row,1);
                if(!fed[node]){var held=new double[conserved];storedFractions(held,candidate.get(node),reservoir,populationKeys,molecularWeight,components);
                    for(int c=0;c<conserved;c++)rhs[c][row]=held[c];}
            }else {
                if(!(endMass[node]>0)||!Double.isFinite(endMass[node]))throw new SparseNewton.Nonconvergence("Candidate overdraws a reservoir");
                add(columns,row,row,endMass[node]+dt*outgoing[node]);
                for(int c=0;c<components;c++)rhs[c][row]=old[node][c]*molecularWeight[c];
                for(int c=0;c<populationKeys.size();c++)rhs[components+c][row]=reservoir.inventory().solids().mass(populationKeys.get(c));
            }
        }
        var solidColumns=new ArrayList<TreeMap<Integer,Double>>();for(var column:columns)solidColumns.add(new TreeMap<>(column));
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            if(index[receiver]<0||flows[edge]==0||!fed[receiver])continue;
            double weight=graph.reservoirs().get(receiver).junction()?Math.abs(flows[edge])/incoming[receiver]:dt*Math.abs(flows[edge]);
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
        for(int node=0;node<nodes;node++) {
            var reservoir=graph.reservoirs().get(node);if(index[node]<0){moles[node]=old[node];states.add(candidate.get(node));continue;}
            double total=0,molesPerKg=0;
            for(int c=0;c<components;c++){double w=solved[c][index[node]];if(w<0||!Double.isFinite(w))throw new SparseNewton.Nonconvergence("Negative transport reconstruction");fractions[node][c]=w;total+=w;molesPerKg+=w/molecularWeight[c];}
            for(int c=components;c<conserved;c++){double w=solved[c][index[node]];if(w<0||!Double.isFinite(w))throw new SparseNewton.Nonconvergence("Negative solid reconstruction");fractions[node][c]=w;total+=w;}
            if(Math.abs(total-1)>1e-8)throw new SparseNewton.Nonconvergence("Junction mass continuity does not close");
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
        for(int edge=0;edge<flows.length;edge++) {
            checkpoint.run();var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            var upstream=states.get(donor);double moved=dt*Math.abs(flows[edge]),upstreamZ=graph.reservoirs().get(donor).elevation();
            double movedEnergy=moved*(upstream.enthalpy()/upstream.mass()+PassiveStepSolver.GRAVITY*upstreamZ);
            energy[donor]-=movedEnergy-moved*PassiveStepSolver.GRAVITY*upstreamZ;
            double deliveredEnergy=movedEnergy,deliveredMass=moved;SolidInventory movedSolids=upstream.solids().scale(moved/upstream.mass());
            if(pipe.filter()!=null){double capturedEnergy=moved*(upstream.solidMoments().enthalpy(upstream.temperature(),upstream.pressure())/upstream.mass()+upstream.solidMoments().mass()/upstream.mass()*PassiveStepSolver.GRAVITY*upstreamZ);
                filters.put(pipe.id(),pipe.filter().add(movedSolids,capturedEnergy));deliveredEnergy-=capturedEnergy;deliveredMass-=movedSolids.massKg();}
            energy[receiver]+=deliveredEnergy-deliveredMass*PassiveStepSolver.GRAVITY*graph.reservoirs().get(receiver).elevation();
            double work=0;
            if(pipe.control() instanceof FlowControl.Pump pump&&flows[edge]>0){var suction=states.get(pipe.first());work=dt*flows[edge]*suction.volume()/suction.mass()*Math.max(0,heads[edge])/pump.efficiency();energy[receiver]+=work;pumpWork+=work;}
            if(graph.reservoirs().get(donor).fixed()||graph.reservoirs().get(receiver).fixed()) {
                var transferred=new double[components];for(int c=0;c<components;c++)transferred[c]=moved*fractions[donor][c]/molecularWeight[c];
                if(graph.reservoirs().get(donor).fixed()){boundaries.add(new BoundaryTransfer(graph.reservoirs().get(donor).id(),transferred,movedEnergy,upstream.solids().scale(moved/upstream.mass()),1));for(int c=0;c<components;c++)external[c]+=transferred[c];externalEnergy+=movedEnergy;}
                if(graph.reservoirs().get(receiver).fixed()){var removed=transferred.clone();for(int c=0;c<components;c++){removed[c]=-removed[c];external[c]+=removed[c];}boundaries.add(new BoundaryTransfer(graph.reservoirs().get(receiver).id(),removed,-deliveredEnergy-work,pipe.filter()==null?movedSolids:SolidInventory.EMPTY,-1));externalEnergy-=deliveredEnergy+work;}
            }
        }
        var inventories=new ArrayList<PassiveNetwork.Inventory>();
        for(int node=0;node<nodes;node++) {
            var oldNode=graph.reservoirs().get(node);var state=states.get(node);
            inventories.add(oldNode.fixed()?oldNode.inventory():oldNode.junction()?new PassiveNetwork.Inventory(state.volume(),moles[node],state.internalEnergy(),state.solids()):new PassiveNetwork.Inventory(oldNode.inventory().volume(),moles[node],energy[node],state.solids()));
        }
        return new Projection(inventories,states,boundaries,external,externalEnergy,pumpWork,filters);
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

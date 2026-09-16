package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.science.fluid.linalg.*;
import com.wormzjl.createcheme.science.fluid.solver.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** Conservative reconstruction for a candidate's already-solved flows; it does not solve hydraulics or flash. */
public final class ConservativeTransport {
    private ConservativeTransport() {}
    public record BoundaryTransfer(long nodeId,double[] moles,double totalEnergyJoule) {
        public BoundaryTransfer{moles=moles.clone();}
        @Override public double[] moles(){return moles.clone();}
    }
    public record Projection(List<PassiveNetwork.Inventory> inventories,List<FluidThermodynamics.State> states,
                             List<BoundaryTransfer> boundaries,double[] externalMoles,double externalEnergy,double pumpWork) {
        public Projection{inventories=List.copyOf(inventories);states=List.copyOf(states);boundaries=List.copyOf(boundaries);externalMoles=externalMoles.clone();}
        @Override public double[] externalMoles(){return externalMoles.clone();}
    }
    public static Projection reconstruct(PassiveNetwork graph,List<FluidThermodynamics.State> candidate,double[] flows,double[] heads,
                                         double dt,FluidThermodynamics model,Runnable checkpoint) {
        int nodes=graph.reservoirs().size(),components=model.hydrocarbon.componentCount()+1;
        if(candidate.size()!=nodes||flows.length!=graph.pipes().size()||heads.length!=flows.length||!Double.isFinite(dt)||dt<=0)throw new IllegalArgumentException("Invalid transport reconstruction");
        int[] index=new int[nodes];Arrays.fill(index,-1);int count=0;
        double[] molecularWeight=model.molecularWeights();
        double[][] old=new double[nodes][],fractions=new double[nodes][components];
        double[] endMass=new double[nodes],incoming=new double[nodes],outgoing=new double[nodes];
        for(int node=0;node<nodes;node++) {
            var reservoir=graph.reservoirs().get(node);old[node]=reservoir.inventory().moles();
            for(int c=0;c<components;c++)endMass[node]+=old[node][c]*molecularWeight[c];
            if(!reservoir.fixed())index[node]=count++;
            else{var n=PhaseLayout.totalAmounts(candidate.get(node));for(int c=0;c<components;c++)fractions[node][c]=n[c]*molecularWeight[c]/candidate.get(node).mass();}
        }
        for(int edge=0;edge<flows.length;edge++) {
            if(!Double.isFinite(flows[edge])||!Double.isFinite(heads[edge]))throw new IllegalArgumentException("Nonfinite candidate flow/head");
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();double massRate=Math.abs(flows[edge]);
            outgoing[donor]+=massRate;incoming[receiver]+=massRate;endMass[donor]-=dt*massRate;endMass[receiver]+=dt*massRate;
        }
        for(var transfer:graph.scheduledTransfers()) {
            int node=transfer.node();
            if(transfer instanceof ScheduledTransfer.Withdrawal out){outgoing[node]+=out.massKgPerSecond();endMass[node]-=dt*out.massKgPerSecond();}
            else if(transfer instanceof ScheduledTransfer.Injection in){var n=in.molesPerSecond();for(int c=0;c<components;c++)endMass[node]+=dt*n[c]*molecularWeight[c];}
        }
        var columns=new ArrayList<TreeMap<Integer,Double>>();for(int c=0;c<count;c++)columns.add(new TreeMap<>());
        double[][] rhs=new double[components][count];
        for(int node=0;node<nodes;node++)if(index[node]>=0) {
            checkpoint.run();int row=index[node];var reservoir=graph.reservoirs().get(node);
            if(reservoir.junction()) {
                add(columns,row,row,1);
                if(incoming[node]==0){var n=PhaseLayout.totalAmounts(candidate.get(node));for(int c=0;c<components;c++)rhs[c][row]=n[c]*molecularWeight[c]/candidate.get(node).mass();}
            }else {
                if(!(endMass[node]>0)||!Double.isFinite(endMass[node]))throw new SparseNewton.Nonconvergence("Candidate overdraws a reservoir");
                add(columns,row,row,endMass[node]+dt*outgoing[node]);
                for(int c=0;c<components;c++)rhs[c][row]=old[node][c]*molecularWeight[c];
            }
        }
        for(int edge=0;edge<flows.length;edge++) {
            var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            if(index[receiver]<0||flows[edge]==0)continue;
            double weight=graph.reservoirs().get(receiver).junction()?Math.abs(flows[edge])/incoming[receiver]:dt*Math.abs(flows[edge]);
            if(index[donor]>=0)add(columns,index[donor],index[receiver],-weight);
            else for(int c=0;c<components;c++)rhs[c][index[receiver]]+=weight*fractions[donor][c];
        }
        for(var transfer:graph.scheduledTransfers())if(transfer instanceof ScheduledTransfer.Injection in&&index[in.node()]>=0) {
            var n=in.molesPerSecond();for(int c=0;c<components;c++)rhs[c][index[in.node()]]+=dt*n[c]*molecularWeight[c];
        }
        double[][] solved;
        try{solved=SparseLuSolver.solveMultiple(matrix(columns),rhs);}catch(SparseLuSolver.SolveFailure failure){throw new SparseNewton.Nonconvergence("Transport reconstruction failed: "+failure.getMessage());}
        double[][] moles=new double[nodes][];var states=new ArrayList<FluidThermodynamics.State>();
        for(int node=0;node<nodes;node++) {
            var reservoir=graph.reservoirs().get(node);if(index[node]<0){moles[node]=old[node];states.add(candidate.get(node));continue;}
            double total=0,molesPerKg=0;
            for(int c=0;c<components;c++){double w=solved[c][index[node]];if(w<0||!Double.isFinite(w))throw new SparseNewton.Nonconvergence("Negative transport reconstruction");fractions[node][c]=w;total+=w;molesPerKg+=w/molecularWeight[c];}
            if(Math.abs(total-1)>1e-8)throw new SparseNewton.Nonconvergence("Junction mass continuity does not close");
            double mass=reservoir.junction()?Arrays.stream(PhaseLayout.totalAmounts(candidate.get(node))).sum()/molesPerKg:endMass[node];
            moles[node]=new double[components];for(int c=0;c<components;c++)moles[node][c]=mass*fractions[node][c]/molecularWeight[c];
            states.add(repartition(model,candidate.get(node),moles[node]));
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
            energy[node]+=movedEnergy-mass*PassiveStepSolver.GRAVITY*graph.reservoirs().get(node).elevation();
            for(int c=0;c<components;c++)external[c]+=moved[c];externalEnergy+=movedEnergy;
            boundaries.add(new BoundaryTransfer(transfer.id(),moved,movedEnergy));
        }
        for(int edge=0;edge<flows.length;edge++) {
            checkpoint.run();var pipe=graph.pipes().get(edge);int donor=flows[edge]>=0?pipe.first():pipe.second(),receiver=flows[edge]>=0?pipe.second():pipe.first();
            var upstream=states.get(donor);double moved=dt*Math.abs(flows[edge]),upstreamZ=graph.reservoirs().get(donor).elevation();
            double movedEnergy=moved*(upstream.enthalpy()/upstream.mass()+PassiveStepSolver.GRAVITY*upstreamZ);
            energy[donor]-=movedEnergy-moved*PassiveStepSolver.GRAVITY*upstreamZ;
            energy[receiver]+=movedEnergy-moved*PassiveStepSolver.GRAVITY*graph.reservoirs().get(receiver).elevation();
            double work=0;
            if(pipe.control() instanceof FlowControl.Pump pump&&flows[edge]>0){var suction=states.get(pipe.first());work=dt*flows[edge]*suction.volume()/suction.mass()*Math.max(0,heads[edge])/pump.efficiency();energy[receiver]+=work;pumpWork+=work;}
            if(graph.reservoirs().get(donor).fixed()||graph.reservoirs().get(receiver).fixed()) {
                var transferred=new double[components];for(int c=0;c<components;c++)transferred[c]=moved*fractions[donor][c]/molecularWeight[c];
                if(graph.reservoirs().get(donor).fixed()){boundaries.add(new BoundaryTransfer(graph.reservoirs().get(donor).id(),transferred,movedEnergy));for(int c=0;c<components;c++)external[c]+=transferred[c];externalEnergy+=movedEnergy;}
                if(graph.reservoirs().get(receiver).fixed()){var removed=transferred.clone();for(int c=0;c<components;c++){removed[c]=-removed[c];external[c]+=removed[c];}boundaries.add(new BoundaryTransfer(graph.reservoirs().get(receiver).id(),removed,-movedEnergy-work));externalEnergy-=movedEnergy+work;}
            }
        }
        var inventories=new ArrayList<PassiveNetwork.Inventory>();
        for(int node=0;node<nodes;node++) {
            var oldNode=graph.reservoirs().get(node);var state=states.get(node);
            inventories.add(oldNode.fixed()?oldNode.inventory():oldNode.junction()?new PassiveNetwork.Inventory(state.volume(),moles[node],state.internalEnergy()):new PassiveNetwork.Inventory(oldNode.inventory().volume(),moles[node],energy[node]));
        }
        return new Projection(inventories,states,boundaries,external,externalEnergy,pumpWork);
    }
    static FluidThermodynamics.State repartition(FluidThermodynamics model,FluidThermodynamics.State state,double[] moles) {
        double[] oldL=state.liquid(),oldV=state.vapor(),l=new double[oldL.length],v=new double[l.length];
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
        return model.state(state.temperature(),state.pressure(),l,v,wl,wv,state.hydrocarbonPartialPressure());
    }
    private static void add(List<TreeMap<Integer,Double>> columns,int column,int row,double value){columns.get(column).merge(row,value,Double::sum);}
    private static SparseMatrix matrix(List<TreeMap<Integer,Double>> columns) {
        int count=columns.size();int[] offsets=new int[count+1];for(int c=0;c<count;c++)offsets[c+1]=offsets[c]+columns.get(c).size();
        int[] rows=new int[offsets[count]];double[] values=new double[rows.length];int at=0;
        for(var column:columns)for(var entry:column.entrySet()){rows[at]=entry.getKey();values[at++]=entry.getValue();}
        return new SparseMatrix(count,offsets,rows,values);
    }
}

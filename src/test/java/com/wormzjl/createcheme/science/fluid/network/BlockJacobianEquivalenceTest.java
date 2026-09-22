package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.state.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The block Jacobian sweep against an independent column-by-column difference of the whole-island
 * residual, on the two island shapes it used to refuse: a chain carrying solids and an island with
 * an inline filter.
 *
 * <p>The coloured fallback is precisely that difference - colouring only packs columns whose
 * declared stencils are disjoint into one residual evaluation, so the value stored for a column is
 * the same one a lone perturbation of it produces. The sweep is therefore required to agree
 * <em>bit for bit</em>, not to a tolerance: it re-assembles the same rows from the same
 * accumulators in the same order, and an entry that merely rounds the same way would mean some row
 * formula had been restated rather than shared.
 *
 * <p>The failure this is built for is silent. A neighbour's three aggregate solid rows are written
 * twice - once by {@code PhaseLayout.balanceRows}/{@code junctionRows} against the layout's own
 * seed reference, then over the top of it against the targets the evaluation accumulated - so a
 * sweep that stops at the first write differentiates the neighbour against the seed reference while
 * the base residual holds the real target. Every such entry is then wrong by the whole difference
 * of the two targets divided by the difference step, and nothing else in the suite looks at it.
 */
class BlockJacobianEquivalenceTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    private FluidThermodynamics.State water(double pressure) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]/=model.flashTP(350,pressure,n,()->{}).volume();
        return model.flashTP(350,pressure,n,()->{});
    }
    private SolidInventory grade(String micrometres,double mass) {
        return new SolidInventory(List.of(new SolidInventory.Population(
                model.solids.require("createcheme:demo_particle"),ParticleSize.micrometres(micrometres),mass)));
    }
    /** {@code count} reservoirs in series, the first holding 100 kg of 100 um particles. */
    private PassiveNetwork chain(int count) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(int i=0;i<count;i++) {
            var state=water(i==0?160000:150000-100*i);
            if(i==0)state=state.withSolids(grade("100",100));
            nodes.add(new PassiveNetwork.Reservoir(i+1,0,state));
            if(i>0)pipes.add(new PassiveNetwork.Pipe(100+i,i-1,i,new PipeResistance.Geometry(100,.05,.000045,0)));
        }
        return new PassiveNetwork(nodes,pipes);
    }
    /** A junction fed by a filter edge and by a plain edge, draining into one finite reservoir. The
     * filter keeps every particle it is handed, so the junction's incoming solids come from the
     * plain edge alone and its own solid rows are the mass-fraction-scaled junction form. */
    private PassiveNetwork filterJunction() {
        var nodes=List.of(
                new PassiveNetwork.Reservoir(1,0,water(200000).withSolids(grade("100",80)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(180000).withSolids(grade("60",40)),PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(3,0,water(160000),PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(4,0,water(150000)));
        var pipes=List.of(
                new PassiveNetwork.Pipe(10,0,2,List.of(new PipeResistance.Geometry(100,.05,.000045,0)),
                        new FlowControl.Passive(),0,new InlineFilter(10,1e6,SolidInventory.EMPTY,0)),
                new PassiveNetwork.Pipe(11,1,2,new PipeResistance.Geometry(100,.05,.000045,0)),
                new PassiveNetwork.Pipe(12,2,3,new PipeResistance.Geometry(100,.05,.000045,0)));
        return new PassiveNetwork(nodes,pipes);
    }

    @Test void theBlockSweepReproducesTheColumnDifferencesOfASolidChain() {
        var comparison=compare(chain(10),.05,"ten-node slurry chain");
        assertTrue(comparison.solidCoupling()>0,
                "No neighbour solid row moved with a donor column, so the comparison never reached the rows that matter");
    }

    @Test void theBlockSweepReproducesTheColumnDifferencesOfAFilterIsland() {
        var comparison=compare(filterJunction(),.001,"filter island with a junction");
        assertTrue(comparison.solidCoupling()>0,"No neighbour solid row moved with a donor column");
        assertTrue(comparison.filterColumns()>0,"The island carried no swept filter column");
    }

    private record Comparison(int columns,int entries,int solidCoupling,int filterColumns) {}

    private Comparison compare(PassiveNetwork graph,double dt,String name) {
        var solver=new PassiveStepSolver(model);
        solver.solve(graph,dt,()->{});
        var equations=solver.acceptedEquations();double[] x=solver.acceptedPoint();
        assertNotNull(equations,name+": no solve was accepted");
        int size=equations.size();
        int[][] columnRows=equations.columnRows();
        int[] offsets=new int[size+1];for(int c=0;c<size;c++)offsets[c+1]=offsets[c]+columnRows[c].length;
        int[] rows=new int[offsets[size]];for(int c=0;c<size;c++)System.arraycopy(columnRows[c],0,rows,offsets[c],columnRows[c].length);

        double[] base=equations.residual(x);
        double[] block=new double[rows.length];
        boolean enabled=SolverDiagnostics.ENABLED;int swept;
        try {
            SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            swept=equations.differentiateEntries(x,base,1e-6,offsets,rows,block,()->{});
            assertEquals(0,SolverDiagnostics.sample().value("jacobianBlockFallbacks"),name+": the block sweep refused the island");
        } finally {SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}
        assertEquals(size,swept,name+": the block sweep did not sweep every column");

        double[] reference=new double[rows.length];
        for(int column=0;column<size;column++) {
            double step=1e-6*equations.differenceScale(column,x[column]);
            double[] trial=x.clone();trial[column]+=step;
            double[] perturbed=equations.residual(trial);
            for(int entry=offsets[column];entry<offsets[column+1];entry++)
                reference[entry]=(perturbed[rows[entry]]-base[rows[entry]])/step;
        }

        int mismatches=0,worstEntry=-1;double worst=0;
        for(int entry=0;entry<rows.length;entry++) {
            if(block[entry]==reference[entry])continue;
            mismatches++;
            double scale=Math.max(Math.abs(reference[entry]),Math.abs(block[entry]));
            double relative=scale==0?Math.abs(block[entry]-reference[entry]):Math.abs(block[entry]-reference[entry])/scale;
            if(relative>=worst){worst=relative;worstEntry=entry;}
        }
        if(mismatches>0) {
            int column=0;while(offsets[column+1]<=worstEntry)column++;
            fail(name+": "+mismatches+" of "+rows.length+" Jacobian entries disagree; worst relative "+worst
                    +" at column "+column+" row "+rows[worstEntry]+" block="+block[worstEntry]+" reference="+reference[worstEntry]);
        }

        // Every neighbour solid row that actually responds to a donor's own column. This is the
        // coupling the sweep used to skip; a comparison that never reaches one proves nothing.
        int coupling=0;
        for(int node=0;node<equations.layout.length;node++) {
            if(equations.layout[node]==null)continue;
            int first=equations.offsets[node]+equations.layout[node].componentBalanceCount()+2;
            for(int column=0;column<size;column++) {
                if(column>=equations.offsets[node]&&column<equations.offsets[node]+equations.layout[node].size())continue;
                for(int entry=offsets[column];entry<offsets[column+1];entry++)
                    if(rows[entry]>=first&&rows[entry]<first+3&&reference[entry]!=0)coupling++;
            }
        }
        int filters=0;for(int offset:equations.filterOffsets)if(offset>=0)filters++;
        System.out.printf(Locale.ROOT,"block jacobian: %s columns=%d entries=%d neighbour-solid-couplings=%d filter columns=%d%n",
                name,swept,rows.length,coupling,filters);
        return new Comparison(swept,rows.length,coupling,filters);
    }
}

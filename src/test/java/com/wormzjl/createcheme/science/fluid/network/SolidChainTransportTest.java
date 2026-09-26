package com.wormzjl.createcheme.science.fluid.network;

import com.wormzjl.createcheme.fluid.support.FluidTestSupport;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.state.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A reservoir chain fed by one slurry tank. Every node of a solid-bearing island carries the three
 * aggregate solid moment unknowns, and backward Euler moves about a millionth of a node's solids
 * one hop per step, so node k of the chain holds roughly 1e-6^k of the source - 1e-26 at node 3
 * and 1e-59 at node 9. Those values are strictly positive, so an unfloored nonnegativity rule
 * treated them as live boundaries and bounded the Newton step at 1e-12 to 1e-18, where the
 * candidate point is bitwise the current one and no backtrack can change the residual. This
 * fixture is the regression for that: the chain has to integrate without a single line-search
 * stall, and the clear-fluid chain beside it has to be untouched.
 */
class SolidChainTransportTest {
    // Measured under the backward-Euler controller (WP1); see clearChainSubstepCountsAreUnchanged.
    private static final int HALF_ACCEPTED=3,HALF_REJECTED=1,FULL_ACCEPTED=5,FULL_REJECTED=1,TWO_ACCEPTED=4,TWO_REJECTED=0;
    /** The solid closure pass solves the port graph once at each interval start (measured: 1 per interval). */
    private static final int RATE_SOLVES_PER_INTERVAL=2;
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    private FluidThermodynamics.State water(double pressure) {
        double[] n=new double[model.componentCount()];n[n.length-1]=1;
        n[n.length-1]/=model.flashTP(350,pressure,n,()->{}).volume();
        return model.flashTP(350,pressure,n,()->{});
    }
    /** {@code count} reservoirs in series, the first holding 100 kg of 100 um particles. */
    private PassiveNetwork chain(int count,boolean solids) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(int i=0;i<count;i++) {
            var state=water(i==0?160000:150000-100*i);
            if(i==0&&solids)state=state.withSolids(new SolidInventory(List.of(new SolidInventory.Population(
                    model.solids.require("createcheme:demo_particle"),ParticleSize.micrometres("100"),100))));
            nodes.add(new PassiveNetwork.Reservoir(i+1,0,state));
            if(i>0)pipes.add(new PassiveNetwork.Pipe(100+i,i-1,i,new PipeResistance.Geometry(100,.05,.000045,0)));
        }
        return new PassiveNetwork(nodes,pipes);
    }
    private static int reasons(PassiveIntervalSolver.Result result,String fragment) {
        int total=0;
        for(var entry:result.rejectionReasons().entrySet())if(entry.getKey().contains(fragment))total+=entry.getValue();
        return total;
    }
    /** Every accepted step keeps the island's solid mass, and the chain is closed, so it never moves. */
    private void assertSolidMassClosed(PassiveIntervalSolver.Result result,double expected) {
        double held=result.graph().reservoirs().stream().mapToDouble(node->node.inventory().solids().massKg()).sum();
        double crossed=result.boundaries().stream().mapToDouble(b->b.solidDirection()*b.solids().massKg()).sum();
        for(var pipe:result.graph().pipes())if(pipe.filter()!=null)held+=pipe.filter().captured().massKg();
        assertEquals(expected,held-crossed,1e-9,"Solid mass left the island");
    }

    @Test void tenReservoirChainIntegratesFiveSecondsWithoutALineSearchStall() {
        var graph=chain(10,true);
        long start=System.nanoTime();
        var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        long ms=(System.nanoTime()-start)/1_000_000;

        assertEquals(0,reasons(result,"line search stalled"),"Solid dust must not bound the Newton step: "+result.rejectionReasons());
        // The bound is on the adaptive controller's own work. Walking onto each of the two closures
        // costs its own rejections, which are counted separately and are not these. Measured 29/6;
        // the headroom covers a different event instant, not a different order of work.
        assertTrue(result.acceptedSubsteps()<=50,"accepted="+result.acceptedSubsteps());
        assertTrue(result.rejectedSubsteps()<=16,"rejected="+result.rejectedSubsteps());
        assertEquals(2,result.graph().pipes().stream().filter(p->p.blockedDirections()!=0).count(),
                "Both deposition closures must be present");
        assertEquals(2,reasons(result,"DEPOSITION"));
        assertSolidMassClosed(result,100);
        assertEquals(5,result.advancedSeconds());
        System.out.printf(Locale.ROOT,"solid chain: 10 nodes 5 s ms=%d accepted=%d rejected=%d reasons=%s%n",
                ms,result.acceptedSubsteps(),result.rejectedSubsteps(),result.rejectionReasons());
    }

    @Test void thirtyReservoirChainIntegratesOneIntervalWithinItsBudget() {
        var graph=chain(30,true);
        var solver=new PassiveIntervalSolver(model);
        solver.solve(chain(30,true),0.05,PassiveIntervalSolver.Settings.defaults(),()->{}); // warm the JIT
        long start=System.nanoTime();
        var result=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        long ms=(System.nanoTime()-start)/1_000_000;

        assertEquals(0,reasons(result,"line search stalled"),result.rejectionReasons().toString());
        assertTrue(result.acceptedSubsteps()<=50,"accepted="+result.acceptedSubsteps()); // measured 31
        assertTrue(result.rejectedSubsteps()<=16,"rejected="+result.rejectedSubsteps()); // measured 10
        assertSolidMassClosed(result,100);
        assertTrue(ms<=1500,"Thirty reservoirs took "+ms+" ms");
        System.out.printf(Locale.ROOT,"solid chain: 30 nodes 5 s ms=%d accepted=%d rejected=%d%n",
                ms,result.acceptedSubsteps(),result.rejectedSubsteps());
    }

    @Test void clearChainSubstepCountsAreUnchanged() {
        // Recorded under the backward-Euler controller (WP1 of the mixed-gas junction batch; the TR-BDF2 counts of
        // 440a754 were 25/10, 32/14 and 18/5). The nonnegativity floor is zero for every fluid amount and the decode
        // projection exists only where there are solid moments, so a clear island must take the same steps it always
        // took, not merely a similar number.
        var graph=chain(10,false);
        var half=new PassiveIntervalSolver(model).solve(graph,0.5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var full=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var two=new PassiveIntervalSolver(model).solve(chain(2,false),5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(HALF_ACCEPTED,half.acceptedSubsteps());
        assertEquals(HALF_REJECTED,half.rejectedSubsteps());
        assertEquals(FULL_ACCEPTED,full.acceptedSubsteps());
        assertEquals(FULL_REJECTED,full.rejectedSubsteps());
        assertEquals(TWO_ACCEPTED,two.acceptedSubsteps());
        assertEquals(TWO_REJECTED,two.rejectedSubsteps());
    }

    @Test void filterIslandReusesItsSolverWorkspacesAcrossIntervals() {
        // PassiveNetwork.Pipe is a record whose equality used to include the mutable cake, and
        // every step rebuilds the pipe list with the cake it left, so every workspace key, structure
        // key and warm-flow comparison missed on every step of a filter island.
        var slurry=water(400000).withSolids(new SolidInventory(List.of(new SolidInventory.Population(
                model.solids.require("createcheme:demo_particle"),ParticleSize.micrometres("100"),100))));
        var graph=new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(1,0,slurry,PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2,0,water(150000),PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(3,0,1,List.of(new PipeResistance.Geometry(1,.05,.000045,0)),
                        new FlowControl.Passive(),0,new InlineFilter(10,1e6,SolidInventory.EMPTY,0))));
        var solver=new PassiveIntervalSolver(model);
        boolean enabled=SolverDiagnostics.ENABLED;
        try {
            var first=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
            SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            var running=first.graph();
            for(int interval=1;interval<20;interval++)running=solver.solve(running,5,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
            var sample=SolverDiagnostics.sample();
            long reuses=sample.value("workspaceReuses"),builds=sample.value("workspaceBuilds");
            assertTrue(reuses>0,"A filter island never reused a solver workspace");
            assertTrue(reuses>builds,"Workspace reuse must dominate after the first interval: "+reuses+" vs "+builds);
            assertEquals(0,sample.value("solidMomentProjectionsAtAcceptedPoints"),
                    "The decode projection must never be active at an accepted point");
            // The block sweep covers solid and filter islands; a fallback here would mean every
            // Jacobian of this island was paying a coloured whole-island residual per colour again.
            assertTrue(sample.value("jacobianBlockBuilds")>0,"A filter island built no block Jacobian");
            assertEquals(0,sample.value("jacobianBlockFallbacks"),"A filter island fell back to the coloured Jacobian sweep");
            // Every step is one implicit solve (backward Euler): the solves beyond the attempted steps are the rate
            // solves of the solid closure pass at each interval start and of the start-of-step guard, a few per interval.
            long solves=sample.value("implicitSolves"),attempts=sample.value("stepAttempts"),acceptedSteps=sample.value("stepAttemptsAccepted");
            assertTrue(solves<=attempts+RATE_SOLVES_PER_INTERVAL*19,"Implicit solves beyond one per step: "+solves+" for "+attempts+" attempted steps in 19 intervals");
            assertFalse(running.pipes().getFirst().filter().captured().empty());
            System.out.printf(Locale.ROOT,"solid chain: filter island reuses=%d builds=%d orderings=%d factorizations=%d jacobians=%d block=%d fallbacks=%d solves=%d/%d attempts%n",
                    reuses,builds,sample.value("luOrderings"),sample.value("luFactorizations"),
                    sample.value("jacobianBuilds"),sample.value("jacobianBlockBuilds"),sample.value("jacobianBlockFallbacks"),solves,attempts);
        } finally {SolverDiagnostics.ENABLED=enabled;SolverDiagnostics.reset();}
    }
}

package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The truncated unknown set inside a complete implicit step: what it removes, and what it restores. */
class TraceTruncationStepTest {
    private static final String PACKAGE=FluidMaterialCatalog.NETWORK_PACKAGE;
    private static final double CUTOFF=1e-6;
    private final FluidThermodynamics truncating=
            FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,1e-9,FluidThermodynamics.DEFAULT_MAXIMUM_VELOCITY,CUTOFF);
    private final FluidThermodynamics exact=
            FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,1e-9,FluidThermodynamics.DEFAULT_MAXIMUM_VELOCITY,0);

    private double[] wetCrude() {
        double[] n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE)
                .crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),22);
        n[20]=.1;n[21]=.2;return n;
    }
    private FluidThermodynamics.State fill(FluidThermodynamics model,double pressure) {
        double[] n=wetCrude();var unit=model.flashTP(350,pressure,n,()->{});
        for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(350,pressure,n,()->{});
    }
    private PassiveNetwork graph(FluidThermodynamics model) {
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,fill(model,200000)),
                new PassiveNetwork.Reservoir(2,0,fill(model,101325))),
                List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
    }

    @Test void aTruncatedStepConservesEveryComponentExactlyAndAgreesWithTheExactUnknownSet() {
        var graph=graph(exact);
        var reference=new PassiveStepSolver(exact).solve(graph,.1,()->{});
        var reduced=new PassiveStepSolver(truncating).solve(graph,.1,()->{});
        int components=exact.componentCount();
        double[] before=new double[components],after=new double[components];
        for(int node=0;node<2;node++) {
            double[] start=PhaseLayout.totalAmounts(graph.reservoirs().get(node).state());
            double[] end=PhaseLayout.totalAmounts(reduced.states().get(node));
            for(int c=0;c<components;c++){before[c]+=start[c];after[c]+=end[c];}
        }
        // Material is exact, not approximately exact: truncation moves a trace between phases of one
        // node and never between nodes, and the omitted phase is written as a hard zero.
        for(int c=0;c<components;c++)assertEquals(before[c],after[c],1e-9+1e-12*before[c],"component "+c);
        for(int node=0;node<2;node++) {
            var a=reference.states().get(node);var b=reduced.states().get(node);
            assertEquals(a.temperature(),b.temperature(),1e-4);
            assertEquals(a.pressure(),b.pressure(),1);
            assertEquals(a.volume(),b.volume(),1e-9);
            assertEquals(a.liquidVolume()/a.volume(),b.liquidVolume()/b.volume(),1e-6);
            assertEquals(a.vaporVolume()/a.volume(),b.vaporVolume()/b.volume(),1e-6);
            assertEquals(a.waterVolume()/a.volume(),b.waterVolume()/b.volume(),1e-6);
        }
        assertEquals(reference.massFlows()[0],reduced.massFlows()[0],1e-6*Math.abs(reference.massFlows()[0])+1e-9);
    }

    @Test void theStepSolverCountsTheUnknownsItOmitsAndNoneAtTheOffSwitch() {
        var graph=graph(exact);
        long omitted,blocks,exactOmitted;
        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        try {
            new PassiveStepSolver(truncating).solve(graph,.1,()->{});
            omitted=SolverDiagnostics.sample().value("traceOmittedUnknowns");
            blocks=SolverDiagnostics.sample().value("truncatedNodePasses");
            SolverDiagnostics.reset();
            new PassiveStepSolver(exact).solve(graph,.1,()->{});
            exactOmitted=SolverDiagnostics.sample().value("traceOmittedUnknowns");
        } finally {SolverDiagnostics.ENABLED=false;}
        assertTrue(omitted>0,"the gameplay basis has vapour-side traces to omit");
        assertTrue(blocks>0);
        assertEquals(0,exactOmitted,"the off switch omits nothing");
    }

    @Test void aTraceThatStopsBeingOneIsReactivatedInsideTheActiveSetLoop() {
        // One reservoir starts with all of its methane in the liquid: the seed says the vapour side
        // is empty, so the frozen support drops it, and the converged fugacity coefficients then say
        // the vapour mole fraction it implies is far above ten times the cutoff.
        var equilibrium=fill(truncating,101325);
        double[] l=equilibrium.liquid(),v=equilibrium.vapor();
        int methane=0;l[methane]+=v[methane];v[methane]=0;
        var displaced=truncating.state(equilibrium.temperature(),equilibrium.pressure(),l,v,
                equilibrium.waterLiquid(),equilibrium.waterVapor(),equilibrium.hydrocarbonPartialPressure());
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,fill(truncating,200000)),
                new PassiveNetwork.Reservoir(2,0,displaced)),
                List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(10,.05,.000045,0))));
        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        PassiveStepSolver.Result result;
        long reactivations;
        try {
            result=new PassiveStepSolver(truncating).solve(graph,.1,()->{});
            reactivations=SolverDiagnostics.sample().value("traceReactivations");
        } finally {SolverDiagnostics.ENABLED=false;}
        assertTrue(reactivations>0,"the displaced methane must be put back into both phases");
        // And it came back with a real amount, not a zero the reduced layout would have kept.
        assertTrue(result.states().get(1).vapor()[methane]>0);
        double before=0,after=0;
        for(int node=0;node<2;node++) {
            before+=PhaseLayout.totalAmounts(graph.reservoirs().get(node).state())[methane];
            after+=PhaseLayout.totalAmounts(result.states().get(node))[methane];
        }
        assertEquals(before,after,1e-12*before,"reactivation must move methane between phases, never create it");
    }
}

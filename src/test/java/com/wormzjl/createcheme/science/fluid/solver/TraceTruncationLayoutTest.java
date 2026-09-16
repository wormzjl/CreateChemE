package com.wormzjl.createcheme.science.fluid.solver;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.thermo.PhaseSupport;
import com.wormzjl.createcheme.science.thermo.TraceTruncationPolicy;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The frozen per-component phase support and the node layout it reduces. The cutoff-0 cases are the
 * statement the whole work package rests on: the off switch is the pre-truncation path, not a very
 * small cutoff.
 */
class TraceTruncationLayoutTest {
    private static final String PACKAGE=FluidMaterialCatalog.NETWORK_PACKAGE;
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,1e-9);

    /** A wet crude at conditions that carry all three phases, which is the gameplay regime. */
    private FluidThermodynamics.State wetCrude(double temperature,double pressure) {
        double[] n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE)
                .crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),22);
        n[20]=.1;n[21]=.2;
        return model.flashTP(temperature,pressure,n,()->{});
    }
    private static PhaseSupport[] support(FluidThermodynamics.State seed,double cutoff) {
        return PhaseLayout.support(seed,null,TraceTruncationPolicy.of(cutoff),null);
    }
    private static int count(PhaseSupport[] support,PhaseSupport value) {
        int n=0;for(var entry:support)if(entry==value)n++;return n;
    }

    @Test void supportFollowsTheSeedCompositionsAndNothingElse() {
        var seed=wetCrude(350,101325);
        double[] l=seed.liquid(),v=seed.vapor();double nl=Arrays.stream(l).sum(),nv=Arrays.stream(v).sum();
        assertTrue(nl>0&&nv>0,"fixture must be two-phase");
        for(double cutoff:new double[]{1e-6,1e-5}) {
            var support=support(seed,cutoff);
            assertEquals(l.length,support.length);
            for(int i=0;i<l.length;i++) {
                boolean liquidTrace=l[i]/nl<cutoff,vaporTrace=v[i]/nv<cutoff;
                var expected=l[i]+v[i]==0?PhaseSupport.ABSENT
                        :liquidTrace&&!vaporTrace?PhaseSupport.VAPOR_ONLY
                        :vaporTrace&&!liquidTrace?PhaseSupport.LIQUID_ONLY:PhaseSupport.BOTH;
                assertEquals(expected,support[i],"component "+i+" at cutoff "+cutoff);
            }
            // The gameplay basis really does have vapour-side heavy ends to drop; a cutoff that
            // omitted nothing would make every other assertion here vacuous.
            assertTrue(count(support,PhaseSupport.LIQUID_ONLY)>0,"cutoff "+cutoff+" omitted no vapour trace");
        }
        // A cutoff that reaches both phases of a component keeps both: the reference cannot say
        // which phase such a component belongs to, so it is not the reference's decision to make.
        assertEquals(PhaseSupport.BOTH,PhaseSupport.of(1e-9,1e-9,1e-6));
        assertEquals(PhaseSupport.LIQUID_ONLY,PhaseSupport.of(1e-3,1e-9,1e-6));
        assertEquals(PhaseSupport.VAPOR_ONLY,PhaseSupport.of(1e-9,1e-3,1e-6));
        assertEquals(PhaseSupport.BOTH,PhaseSupport.of(1e-9,1e-9,0));
    }

    @Test void theOffSwitchIsThePreTruncationLayoutBitForBit() {
        var seed=wetCrude(350,101325);
        var off=support(seed,0);
        for(int i=0;i<off.length;i++)
            assertEquals(seed.liquid()[i]+seed.vapor()[i]==0?PhaseSupport.ABSENT:PhaseSupport.BOTH,off[i]);
        var reference=new PhaseLayout(model,seed);
        var explicit=new PhaseLayout(model,seed,null,null,off);
        assertEquals(reference.size(),explicit.size());
        assertEquals(reference.componentBalanceCount(),explicit.componentBalanceCount());
        assertEquals(0,explicit.singlePhaseComponentCount());
        assertArrayEquals(reference.encode(seed),explicit.encode(seed),0,"encode must be bitwise identical");
        double[] n=PhaseLayout.totalAmounts(seed);
        double[] a=new double[reference.size()],b=new double[explicit.size()];
        var x=reference.encode(seed);
        reference.residual(seed,n,seed.internalEnergy(),seed.volume(),a,0,x);
        explicit.residual(seed,n,seed.internalEnergy(),seed.volume(),b,0,x);
        assertArrayEquals(a,b,0,"residual must be bitwise identical");
    }

    @Test void aReducedLayoutLosesOneUnknownAndOneRowPerOmittedPhase() {
        var seed=wetCrude(350,101325);
        var full=new PhaseLayout(model,seed);
        for(double cutoff:new double[]{1e-6,1e-5}) {
            var support=support(seed,cutoff);
            int omitted=count(support,PhaseSupport.LIQUID_ONLY)+count(support,PhaseSupport.VAPOR_ONLY);
            var layout=new PhaseLayout(model,seed,null,null,support);
            assertEquals(omitted,layout.singlePhaseComponentCount());
            assertEquals(full.size()-omitted,layout.size(),"one unknown per omitted phase");
            // Square: the component balances, the water balance, the energy and volume closures and
            // the water/partial-pressure rows are untouched, so the rows it loses are exactly the
            // equilibrium rows of the components it reduced.
            assertEquals(full.componentBalanceCount(),layout.componentBalanceCount());
            double[] residual=new double[layout.size()];
            layout.residual(layout.decode(layout.encode(seed),0),PhaseLayout.totalAmounts(seed),
                    seed.internalEnergy(),seed.volume(),residual,0,layout.encode(seed));
            assertTrue(Arrays.stream(residual).allMatch(Double::isFinite));
        }
    }

    @Test void theOmittedPhaseIsExactlyZeroAndTheComponentTotalIsExact() {
        var seed=wetCrude(350,101325);
        var support=support(seed,1e-5);
        var layout=new PhaseLayout(model,seed,null,null,support);
        var decoded=layout.decode(layout.encode(seed),0);
        double[] l=decoded.liquid(),v=decoded.vapor(),before=PhaseLayout.totalAmounts(seed),after=PhaseLayout.totalAmounts(decoded);
        int checked=0;
        for(int i=0;i<support.length;i++) {
            if(support[i]==PhaseSupport.LIQUID_ONLY){assertEquals(0,v[i],0,"vapour of component "+i);checked++;}
            if(support[i]==PhaseSupport.VAPOR_ONLY){assertEquals(0,l[i],0,"liquid of component "+i);checked++;}
        }
        assertTrue(checked>0);
        // Folding an omitted trace into the retained phase is a phase-split approximation and never a
        // material one: the balance rows subtract the same totals the untruncated layout would.
        for(int i=0;i<before.length;i++)assertEquals(before[i],after[i],Math.max(1e-30,4*Math.ulp(before[i])),"total of component "+i);
        assertEquals(seed.waterLiquid(),decoded.waterLiquid(),0);
        assertEquals(seed.waterVapor(),decoded.waterVapor(),0);
    }

    @Test void aTruncatedInventorySolveReachesTheSameTemperaturePressureAndTotals() {
        double[] n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE)
                .crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),22);
        n[20]=.1;n[21]=.2;
        var seed=model.flashTP(350,101325,n,()->{});var target=model.flashTP(351,105000,n,()->{});
        var layout=new PhaseLayout(model,seed,null,null,support(seed,1e-5));
        assertTrue(layout.singlePhaseComponentCount()>0);
        var equations=layout.fixedInventory(n,target.internalEnergy(),target.volume());
        var solved=SparseNewton.solve(equations,layout.encode(seed),new SparseNewton.Settings(30,1e-10,1e-6,24),()->{});
        var actual=layout.decode(solved.variables(),0);
        assertEquals(target.temperature(),actual.temperature(),1e-3);
        assertEquals(target.pressure(),actual.pressure(),10);
        assertArrayEquals(n,PhaseLayout.totalAmounts(actual),1e-10);
    }

    @Test void theNetworkCutoffIsCappedWellBelowTheColumnsAndZeroIsAccepted() {
        assertEquals(1e-5,FluidThermodynamics.MAX_TRACE_CUTOFF_MOLE_FRACTION);
        assertTrue(FluidThermodynamics.MAX_TRACE_CUTOFF_MOLE_FRACTION<TraceTruncationPolicy.MAX_CUTOFF_MOLE_FRACTION);
        var catalog=MaterialCatalog.bundled();
        assertEquals(TraceTruncationPolicy.OFF,
                FluidThermodynamics.forNetwork(catalog,PACKAGE,1e-9,100,0).traceTruncation());
        assertEquals(1e-6,FluidThermodynamics.forNetwork(catalog,PACKAGE,1e-9,100,1e-6).traceTruncation().cutoffMoleFraction());
        for(double refused:new double[]{1.1e-5,1e-4,1e-2,-1e-9,Double.NaN})
            assertThrows(IllegalArgumentException.class,
                    ()->FluidThermodynamics.forNetwork(catalog,PACKAGE,1e-9,100,refused),"cutoff "+refused);
        // The column keeps its own ceiling, which is a thousand times larger.
        assertDoesNotThrow(()->TraceTruncationPolicy.of(1e-2));
    }

    @Test void freeWaterAndHeadspaceRegimesAreOutsideTheCutoffsReach() {
        // A nearly liquid-full vessel (the small-headspace regime) and a dry one: the water unknowns,
        // the water equilibrium row and the partial-pressure closure belong to the regime state, not
        // to the hydrocarbon trace support, so the cutoff may not move any of them.
        double[] wet=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE)
                .crudeFeed("createcheme:tia_juana_light_methane").moleFractions(),22);
        wet[20]=.001;wet[21]=.2;
        double[] dry=Arrays.copyOf(wet,22);dry[21]=0;
        for(double[] n:new double[][]{wet,dry})for(double pressure:new double[]{101325,1_500_000}) {
            var seed=model.flashTP(320,pressure,n,()->{});
            var full=new PhaseLayout(model,seed);
            var reduced=new PhaseLayout(model,seed,null,null,support(seed,1e-5));
            assertEquals(full.componentBalanceCount(),reduced.componentBalanceCount());
            assertEquals(full.size()-reduced.singlePhaseComponentCount(),reduced.size());
            var reference=full.decode(full.encode(seed),0);
            var decoded=reduced.decode(reduced.encode(seed),0);
            assertEquals(reference.waterLiquid(),decoded.waterLiquid(),0);
            assertEquals(reference.waterVapor(),decoded.waterVapor(),0);
            assertEquals(reference.hydrocarbonPartialPressure(),decoded.hydrocarbonPartialPressure(),0);
            assertEquals(reference.pressure(),decoded.pressure(),0);
            assertEquals(reference.temperature(),decoded.temperature(),0);
        }
    }
}

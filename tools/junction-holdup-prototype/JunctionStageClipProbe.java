package com.wormzjl.createcheme.probe;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Review 7.11 probe (untracked, tools/junction-holdup-prototype): the two NetworkRegimeTest fixtures that call
 * TR-BDF2 directly (a nitrogen junction fed methane + nitrogen, and a nitrogen junction flushed by methane while
 * a nitrogen tank back-feeds) stepped once at dt = 0.1, 1, 5 and 20 s (20 s = the product's dt_max). Prints one
 * HOLDUP_CLIP line per case and step: PASS with the junction's nitrogen amount and methane mass fraction, or the
 * exception. The step's own conservation check runs inside TrBdf2StepSolver. Exploration wrapper: never fails.
 */
class JunctionStageClipProbe {
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"createcheme:tjl20_methane_nitrogen",1e-9);
    private final PipeResistance.Geometry geometry=new PipeResistance.Geometry(100,.02,.000045,0);
    private PassiveNetwork.Reservoir gas(long id,double t,double p,int component,PassiveNetwork.NodeKind kind) {
        var n=new double[MaterialTestBasis.NETWORK+1];n[component]=1;var unit=model.flashTP(t,p,n,()->{});n[component]/=unit.volume();
        return new PassiveNetwork.Reservoir(id,0,model.flashTP(t,p,n,()->{}),kind);
    }
    @Test void directStepsUpToTheProductMaximum() {
        int N2=MaterialTestBasis.NITROGEN;
        var mixing=new PassiveNetwork(List.of(gas(1,350,220000,0,PassiveNetwork.NodeKind.GENERATOR),gas(2,400,210000,N2,PassiveNetwork.NodeKind.GENERATOR),
                gas(3,350,195000,N2,PassiveNetwork.NodeKind.JUNCTION),gas(4,350,180000,N2,PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(1,0,2,geometry),new PassiveNetwork.Pipe(2,1,2,geometry),new PassiveNetwork.Pipe(3,2,3,geometry)));
        var reversed=new PassiveNetwork(List.of(gas(1,350,220000,0,PassiveNetwork.NodeKind.GENERATOR),gas(2,400,170000,N2,PassiveNetwork.NodeKind.RESERVOIR),
                gas(3,350,195000,N2,PassiveNetwork.NodeKind.JUNCTION),gas(4,350,180000,N2,PassiveNetwork.NodeKind.VOID)),List.of(
                new PassiveNetwork.Pipe(1,0,2,geometry),new PassiveNetwork.Pipe(2,1,2,geometry),new PassiveNetwork.Pipe(3,2,3,geometry)));
        for(var entry:List.of(Map.entry("mixing",mixing),Map.entry("reversed",reversed)))for(double dt:new double[]{.1,1,5,20}) {
            try {
                var result=new TrBdf2StepSolver(model).solve(entry.getValue(),dt,()->{});
                var junction=result.inventories().get(2);var n=junction.moles();double mass=0;for(int c=0;c<n.length;c++)mass+=n[c]*model.molecularWeight(c);
                var state=result.states().get(2);var amounts=PhaseLayout.totalAmounts(state);
                System.out.println("HOLDUP_CLIP case="+entry.getKey()+" dt="+dt+" PASS flows="+Arrays.toString(result.massFlows())+" junctionMass="+mass
                        +" junctionN2mol="+n[N2]+" stateN2mol="+amounts[N2]+" stateCH4massFraction="+amounts[0]*model.molecularWeight(0)/state.mass());
            }catch(RuntimeException failure) {
                System.out.println("HOLDUP_CLIP case="+entry.getKey()+" dt="+dt+" FAIL "+failure);
            }
        }
    }
}

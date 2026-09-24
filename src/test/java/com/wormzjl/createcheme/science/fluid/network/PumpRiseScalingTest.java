package com.wormzjl.createcheme.science.fluid.network;

import com.google.gson.JsonParser;
import com.wormzjl.createcheme.science.fluid.solver.SparseNewton;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F4, T3 (option P1 of the F1 review): a pump's "maximum pressure rise" is the rise for water at 298.15 K and 1 atm, and
 * on what it actually withdraws its limit is that setting times the suction's bulk density over water's - a head. And
 * T2: a trial the property domain refuses is its own rejection key and the reason a failed interval gives.
 */
class PumpRiseScalingTest {
    private static final String NETWORK="createcheme:tjl20_methane_nitrogen";
    private static final PipeResistance.Geometry LINE=new PipeResistance.Geometry(1,.05,.000045,0);
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),NETWORK,1e-9);

    /** Two closed 1 m3 nitrogen tanks at 298.15 K and 1 atm, a pump from the first into the second. */
    private static PassiveNetwork transfer(FluidThermodynamics model,double setting) {
        var a=model.initialNitrogenCharge(1,298.15,101325,()->{});var b=model.initialNitrogenCharge(1,298.15,101325,()->{});
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,a),new PassiveNetwork.Reservoir(2,0,b)),
                List.of(new PassiveNetwork.Pipe(10,0,1,LINE,new FlowControl.Pump(.01,setting,1))));
    }

    @Test void theReferenceIsThisModelsOwnWaterAtRoomConditions() {
        double rho=model.waterMolecularWeight/model.waterLiquid(298.15,101325).molarVolume();
        assertEquals(rho,model.pumpReferenceDensity(),1e-12*rho);
        // IF97 Region 1 at the 2 MPa reference carried to 1 atm by the global 1e-9 1/Pa compressibility: 996.0 kg/m3.
        assertEquals(996.0,model.pumpReferenceDensity(),.1);
    }

    /**
     * Nitrogen pumped out of a closed tank: the pump reaches its limit at about setting x 1.145/996 (575 Pa for 500 kPa)
     * and then its shutoff within a fraction of a second, with the suction tank a quarter of a kelvin cooler - and never
     * anywhere near the property domain's floor. Before P1 the same pump evacuated the tank until the gas reached the
     * model's floor after 21.9 s (F1 review, section 2.6).
     */
    @Test void aGasTransferBetweenClosedTanksClosesWithinASecond() {
        var graph=transfer(model,500000);var solver=new PassiveIntervalSolver(model);double time=0,step=.05;
        double initialDensity=graph.reservoirs().getFirst().state().mass()/graph.reservoirs().getFirst().state().volume();
        PassiveIntervalSolver.Result result=null;double closedAt=Double.NaN;
        for(int k=0;k<40;k++) {
            result=solver.solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{},step);graph=result.graph();step=solver.nextStepEstimate();time+=.05;
            if(Double.isNaN(closedAt)&&result.endpointModes().getFirst()==FlowControl.Mode.CLOSED)closedAt=time;
        }
        var suction=graph.reservoirs().get(0).state();var discharge=graph.reservoirs().get(1).state();double rise=result.endpointHeads()[0];
        System.out.printf(Locale.ROOT,"gas transfer (P1): closed at %.2f s, suction %.6f K %.3f Pa, discharge %.6f K %.3f Pa, rise at shutoff %.4f Pa, limit at the start %.4f Pa%n",
                closedAt,suction.temperature(),suction.pressure(),discharge.temperature(),discharge.pressure(),rise,500000*initialDensity/model.pumpReferenceDensity());
        assertTrue(closedAt<=1,"the pump reaches its shutoff within a second: "+closedAt);
        assertEquals(FlowControl.Mode.CLOSED,result.endpointModes().getFirst());assertEquals(0,result.averageMassFlows()[0]);
        assertEquals(500000*1.145/model.pumpReferenceDensity(),rise,3,"the rise at shutoff is the setting scaled by the gas density");
        assertEquals(discharge.pressure()-suction.pressure(),rise,.05,"the pump holds the whole pressure difference at shutoff");
        assertTrue(suction.temperature()<298.15&&suction.temperature()>298.15-1,"a fraction of a kelvin cooler: "+suction.temperature());
        assertTrue(result.rejectionReasons().keySet().stream().noneMatch(k->k.startsWith(ThermoDomainViolation.REASON_PREFIX)),"never near the domain: "+result.rejectionReasons());
    }

    /** On water the limit is the setting within water's compressibility: the same shutoff as before P1. */
    @Test void onWaterTheLimitIsTheSettingWithinWatersCompressibility() {
        double[] water=new double[model.componentCount()];water[model.componentCount()-1]=1;
        for(double p:new double[]{101325,601325,2e6}) {
            var unit=model.flashTP(298.15,p,water,()->{});double rho=unit.mass()/unit.volume();
            assertEquals(1,rho/model.pumpReferenceDensity(),p*1e-9*1.01,"water at "+p+" Pa");
        }
    }

    /**
     * A trial the property domain refuses is counted under its own key, and an interval it stops fails with the
     * violation itself. Forced by narrowing the nitrogen record's fluid_domain to 298.05 K: the transfer cools the
     * suction 0.24 K, past it.
     */
    @Test void aDomainRefusalIsItsOwnRejectionKeyAndTheReasonTheIntervalFails() {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());String key="data/createcheme/materials/properties/nitrogen.json";
        var o=JsonParser.parseString(resources.get(key)).getAsJsonObject();o.getAsJsonObject("fluid_domain").addProperty("temperature_min_kelvin",298.05);resources.put(key,o.toString());
        var narrowed=FluidThermodynamics.forNetwork(MaterialCatalog.parse(resources),NETWORK,1e-9);
        var failure=assertThrows(SparseNewton.Nonconvergence.class,()->{
            var graph=transfer(narrowed,500000);var solver=new PassiveIntervalSolver(narrowed);
            for(int k=0;k<40;k++)graph=solver.solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{},solver.nextStepEstimate()>0?solver.nextStepEstimate():.05).graph();
        });
        var violation=failure.domainViolation();
        assertNotNull(violation,"the failed interval names the domain, not the Newton symptom: "+failure.getMessage());
        assertEquals("Nitrogen",violation.component());assertEquals(ThermoDomainViolation.Code.THERMO_DOMAIN_TEMPERATURE_BELOW,violation.code());
        assertEquals(298.05,violation.minimum());assertEquals(1,violation.node(),"the suction tank");
        assertTrue(failure.getMessage().contains("thermo-domain: Nitrogen temperature < 298.05 K="),"the rejection map counts it under its key: "+failure.getMessage());
        assertEquals(violation,SparseNewton.domainViolation(failure));
    }

    @Test void anIntervalFailsOnTheDomainWhenItsLastOrMostRejectionsWereDomainOnes() {
        var v=new ThermoDomainViolation(NETWORK,"Nitrogen",ThermoDomainViolation.Property.TEMPERATURE,60,63.151,900);
        assertSame(v,PassiveIntervalSolver.domainCause(true,v,1,20),"the last rejection");
        assertSame(v,PassiveIntervalSolver.domainCause(false,v,11,20),"most rejections");
        assertNull(PassiveIntervalSolver.domainCause(false,v,10,20),"met on the way, failed for another reason");
        assertNull(PassiveIntervalSolver.domainCause(false,null,0,20));
    }
}

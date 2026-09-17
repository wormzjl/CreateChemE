package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowControlTest {
    private final FluidThermodynamics model=new FluidThermodynamics(MaterialCatalog.bundled(),"createcheme:tjl20_methane",1e-9);
    private PassiveStepSolver.Result run(double pa,double pb,int component,FlowControl control,double dt) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>();
        for(double p:new double[]{pa,pb}){double[] n=new double[com.wormzjl.createcheme.science.material.MaterialTestBasis.CRUDE+1];n[component]=1;var unit=model.flashTP(350,p,n,()->{});n[component]/=unit.volume();nodes.add(new PassiveNetwork.Reservoir(nodes.size(),0,model.flashTP(350,p,n,()->{})));}
        return new PassiveStepSolver(model).solve(new PassiveNetwork(nodes,List.of(new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0),control))),dt,()->{});
    }
    @Test void pumpMeetsItsSuctionFlowTargetAndAccountsForAddedEnergy() {
        var result=run(101325,200000,0,new FlowControl.Pump(.001,500000,1),.1);var suction=result.states().getFirst();
        assertEquals(.001,result.massFlows()[0]/(suction.mass()/suction.volume()),1e-10);
        assertEquals(FlowControl.Mode.PUMP_TARGET,result.modes().getFirst());assertTrue(result.devicePressureChanges()[0]>0);assertTrue(result.pumpWorkJoule()>0);
    }
    @Test void naturalFlowIsLimitedWithoutInventedPumpWorkAndOpposingHeadShutsThePump() {
        var natural=run(300000,101325,0,new FlowControl.Pump(.001,500000,1),.1);
        assertTrue(natural.devicePressureChanges()[0]<0);assertEquals(0,natural.pumpWorkJoule());assertTrue(natural.massFlows()[0]>0);
        var shutoff=run(101325,800000,0,new FlowControl.Pump(.001,100000,1),.1);
        assertEquals(FlowControl.Mode.CLOSED,shutoff.modes().getFirst());assertEquals(0,shutoff.massFlows()[0],1e-10);assertEquals(0,shutoff.pumpWorkJoule(),1e-6);
    }
    @Test void pressureValveClosesBelowTargetAndRegulatesAboveIt() {
        var closed=run(101325,80000,model.componentCount()-1,new FlowControl.PressureValve(175000),.1);
        assertEquals(FlowControl.Mode.CLOSED,closed.modes().getFirst());assertEquals(0,closed.massFlows()[0],1e-10);
        var regulated=run(200000,101325,model.componentCount()-1,new FlowControl.PressureValve(175000),.1);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,regulated.modes().getFirst());assertEquals(175000,regulated.states().getFirst().pressure(),.01);
        assertTrue(regulated.massFlows()[0]>0);assertTrue(regulated.devicePressureChanges()[0]>0);
    }
}

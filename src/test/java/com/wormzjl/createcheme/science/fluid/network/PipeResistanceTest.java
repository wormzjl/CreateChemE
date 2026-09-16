package com.wormzjl.createcheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PipeResistanceTest {
    @Test void poiseuilleReferenceAndZeroFlowSlope() {
        var pipe=new PipeResistance.Geometry(1,.01,0,0);
        var loss=PipeResistance.evaluate(pipe,.001,1000,.001);
        // 1 mL/s through a 1 m x 10 mm tube, water-like mu=1 mPa.s: hand-evaluated Poiseuille relation.
        assertEquals(4.07436654315252,loss.pressureDrop(),1e-12);
        assertEquals(127.323954473516,loss.reynolds(),1e-10);
        assertEquals(loss.pressureDrop(),-PipeResistance.evaluate(pipe,-.001,1000,.001).pressureDrop(),1e-12);
        assertEquals(0,PipeResistance.evaluate(pipe,0,1000,.001).pressureDrop());
        assertTrue(PipeResistance.evaluate(pipe,0,1000,.001).massFlowDerivative()>0);
    }
    @Test void frictionDerivativeIsContinuousAcrossTransitionAndPositiveInBothDirections() {
        var pipe=new PipeResistance.Geometry(20,.05,.000045,2);
        for(double reynolds:new double[]{0,1999.99,2000,2000.01,3000,3999.99,4000,4000.01,1e5,1e6}) {
            double flow=reynolds*Math.PI*.001*.05/4,h=1e-7;
            for(double sign:new double[]{-1,1}) {
                double q=sign*flow;var loss=PipeResistance.evaluate(pipe,q,900,.001);
                double derivative=(PipeResistance.evaluate(pipe,q+h,900,.001).pressureDrop()-PipeResistance.evaluate(pipe,q-h,900,.001).pressureDrop())/(2*h);
                assertEquals(derivative,loss.massFlowDerivative(),1e-5*Math.abs(derivative)+1e-4,"Re="+reynolds);
            }
        }
    }
}

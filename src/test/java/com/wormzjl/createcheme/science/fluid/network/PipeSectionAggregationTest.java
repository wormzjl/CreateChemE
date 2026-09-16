package com.wormzjl.createcheme.science.fluid.network;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PipeSectionAggregationTest {
    @Test void manyPhysicalSectionsPreserveLossDerivativeAndMinimumAreaAcrossFlowRegimes() {
        var sections=new ArrayList<PipeResistance.Geometry>();
        for(int i=0;i<1000;i++)sections.add(new PipeResistance.Geometry(.5,i%3==0?.02:.05,i%3==0?.0001:.000045,.03));
        var pipe=new PassiveNetwork.Pipe(1,0,1,sections,new FlowControl.Passive());assertEquals(2,pipe.sections().size());
        assertEquals(500,pipe.sections().stream().mapToDouble(PipeResistance.Geometry::length).sum());assertEquals(Math.PI*.02*.02/4,pipe.minimumArea());
        for(double flow:new double[]{-.2,-1e-5,0,1e-5,.2,4}) {
            double loss=0,derivative=0;for(var section:sections){var part=PipeResistance.evaluate(section,flow,850,.003);loss+=part.pressureDrop();derivative+=part.massFlowDerivative();}
            var compact=pipe.loss(flow,850,.003);assertEquals(loss,compact.pressureDrop(),1e-11*Math.max(1,Math.abs(loss)));assertEquals(derivative,compact.massFlowDerivative(),1e-11*derivative);
        }
    }
}

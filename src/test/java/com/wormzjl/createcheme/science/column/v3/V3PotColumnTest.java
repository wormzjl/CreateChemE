package com.wormzjl.createcheme.science.column.v3;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.createcheme.science.material.Cdu17FixtureExtension.class)
class V3PotColumnTest {
    @Test void twoAndThreeTotalTraysSolveAndPassIndependentConservationAudit(){
        var thermo=V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] feed=new double[thermo.componentBasis().componentCount()];feed[6]=50;feed[13]=50;
        for(int interior:new int[]{0,1})for(boolean wet:new boolean[]{false,true}){
            var input=new V3ColumnInput(1,thermo.packageId(),"test:pot",thermo.componentBasis(),feed,550,
                interior,interior+1,250000,750,List.of(new V3ColumnSpecification.CondenserOutletTemperature(300),
                    new V3ColumnSpecification.OrganicRefluxRatio(2),new V3ColumnSpecification.ReboilerDuty(0)),
                List.of(),wet?List.of(new V3SteamFeedSpec(interior+1,10,600)):List.of(),List.of(),8);
            var outcome=V3ColumnCalculator.calculate(input);
            var success=assertInstanceOf(V3ColumnOutcome.Success.class,outcome,()->outcome.toString());
            assertTrue(success.result().acceptanceAudit().accepted());
            var problem=success.result().problem();
            assertEquals(interior+2,problem.topology().nodeCount());
            assertEquals(250000,problem.nodePressurePascal(0));
            assertEquals(250000,problem.nodePressurePascal(problem.topology().reboilerNode()));
            assertEquals(0,V3TrayHydraulics.totalDropPascal(problem.topology(),problem.nodePressuresPascal()));
        }
    }
}

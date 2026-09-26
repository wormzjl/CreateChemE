package com.wormzjl.createcheme.fluid.benchmark;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Unpaced numerical scaling screening. M9 requires separate paced server replicates and soak. */
class FluidNetworkBenchmarkTest {
    @Test void screenWetCrudeChainsWithTheConfiguredComponents() throws Exception {
        var model=com.wormzjl.createcheme.fluid.support.FluidTestSupport.networkModel();
        var composition=new double[model.componentCount()];
        var crude=V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl20_methane");
        var feed=crude.crudeFeed("createcheme:tia_juana_light_methane").moleFractions();
        for(int c=0;c<feed.length;c++)composition[model.components().indexOf(crude.componentBasis().componentId(c))]=feed[c];
        composition[model.components().indexOf("Nitrogen")]=.1;composition[model.components().indexOf("Water")]=.2;
        var rows=new ArrayList<Map<String,Object>>();var failures=new ArrayList<String>();
        Files.createDirectories(Path.of("build/reports/fluid"));
        for(int count:new int[]{2,10,100}) {
            var nodes=new ArrayList<PassiveNetwork.Reservoir>();var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(int i=0;i<count;i++) {
                double pressure=150000+1000*Math.cos(i*.7);var n=composition.clone();var unit=model.flashTP(350,pressure,n,()->{});
                for(int c=0;c<n.length;c++)n[c]/=unit.volume();var state=model.flashTP(350,pressure,n,()->{});
                nodes.add(new PassiveNetwork.Reservoir(i+1,0,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(1,n,state.internalEnergy())));
                if(i>0)pipes.add(new PassiveNetwork.Pipe(i,i-1,i,new PipeResistance.Geometry(100,.05,.000045,0)));
            }
            var graph=new PassiveNetwork(nodes,pipes);var row=new LinkedHashMap<String,Object>();rows.add(row);
            row.put("reservoirs",count);row.put("compiledEdges",pipes.size());row.put("components",model.componentCount());row.put("equations",nodes.stream().mapToInt(node->new PhaseLayout(model,node.state()).size()).sum()+pipes.size());
            row.put("profile","Unpaced 5 simulated seconds; wet TJL20 + nitrogen; 350 K; P=150000+1000*cos(0.7*i) Pa; 100 m pipe runs");
            long start=System.nanoTime();
            try {
                var result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
                row.put("status","CONVERGED");row.put("acceptedSubsteps",result.acceptedSubsteps());row.put("rejectedSubsteps",result.rejectedSubsteps());
                row.put("rejectionReasons",result.rejectionReasons());row.put("integrator","backward Euler under the state-change controller (vessel pressure and mass change at most 5 % per step)");
                row.put("firstCallMilliseconds",(System.nanoTime()-start)/1e6);
                var repeated=new ArrayList<Double>();
                for(int repeat=0;repeat<2;repeat++){long measured=System.nanoTime();new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});repeated.add((System.nanoTime()-measured)/1e6);}
                row.put("warmRepeatedMilliseconds",repeated);
            }catch(RuntimeException failure){row.put("status","FAILED");row.put("failure",failure.toString());failures.add(count+": "+failure);}
            row.put("wallMilliseconds",(System.nanoTime()-start)/1e6);row.put("propertyRevision",model.hydrocarbon.revision());
            Files.writeString(Path.of("build/reports/fluid/M2-network-scaling-screening.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows));
            System.out.println("Fluid scaling screening "+row);
        }
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }
}

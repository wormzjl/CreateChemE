package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;

/** Snapshot hook compiled only into a generated research copy of the calculator. */
public final class V3ChemSepWarmExport {
    private static Path directory;
    public static void captured(V3ColumnProblem problem,V3DryMeshState state) {
        var rows=new ArrayList<Object>();
        for(int node=0;node<state.nodeCount();node++) {
            var row=new LinkedHashMap<String,Object>();
            var liquid=new double[state.componentCount()+1];var vapor=new double[liquid.length];
            for(int c=0;c<state.componentCount();c++){liquid[c]=state.liquidFlow(node,c);vapor[c]=state.vaporFlow(node,c);}
            liquid[liquid.length-1]=state.freeWaterFlow(node);vapor[vapor.length-1]=problem.waterVaporFlow(state,node);
            row.put("node",node);row.put("temperature_K",state.temperatureKelvin(node));row.put("liquid_mol_s",liquid);row.put("vapor_mol_s",vapor);rows.add(row);
        }
        var data=new LinkedHashMap<String,Object>();data.put("input",problem.input());data.put("branch",problem.topology().condenserPhaseBranch());data.put("nodes",rows);
        try{Files.writeString(directory.resolve("v3-accepted-profile.json"),new GsonBuilder().setPrettyPrinting().create().toJson(data));}catch(Exception e){throw new RuntimeException(e);}
    }
    public static void main(String[] args) throws Exception {
        directory=Path.of(args[0]);
        var input=(V3ColumnInput)Class.forName("com.wormzjl.createcheme.science.column.v3.thermo.CurrentChemSepInput").getMethod("literatureCduInput").invoke(null);
        long start=System.nanoTime(),deadline=start+60_000_000_000L;
        var report=new LinkedHashMap<String,Object>();
        try {
            var outcome=V3ColumnCalculator.calculate(input,()->{if(System.nanoTime()>deadline)throw new java.util.concurrent.CancellationException("60 second deadline");},0,0);
            report.put("outcome",outcome);report.put("success",outcome instanceof V3ColumnOutcome.Success);
        } catch(Exception e){report.put("error",e.toString());}
        report.put("elapsed_ms",(System.nanoTime()-start)/1e6);
        Files.writeString(directory.resolve("v3-outcome.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("V3 result written, "+report.get("elapsed_ms")+" ms");
    }
}

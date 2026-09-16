package com.wormzjl.createcheme.fluid.benchmark;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.network.*;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;

/** Read-only saved stress-state diagnostics. No qualification or deadline relaxation in production. */
class FluidStressProfileTest {
    @Test void replayAnUnadvancedLargeIsland() throws Exception {
        var source=Path.of(System.getProperty("fluid.profile.snapshot"));
        var tag=NbtIo.readCompressed(source,NbtAccounter.unlimitedHeap()).getCompound("data");
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidPresetCatalog.NETWORK_PACKAGE,1e-9);
        var saved=FluidSavedData.load(tag,key->model).checkpoint();
        var entry=saved.islands().stream().filter(i->i.snapshot().clock().committedTick()==0)
                .max(Comparator.comparingInt(i->i.snapshot().graph().reservoirs().size())).orElseThrow();
        var graph=entry.snapshot().graph();var rows=new ArrayList<Map<String,Object>>();
        for(double duration:new double[]{.01,.1,1,5}) {
            var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("durationSeconds",duration);long started=System.nanoTime();
            try {
                var result=new PassiveIntervalSolver(model).solve(graph,duration,PassiveIntervalSolver.Settings.defaults(),()->{
                    if(System.nanoTime()-started>20_000_000_000L)throw new java.util.concurrent.CancellationException("20 s profile ceiling");
                });
                row.put("status","CONVERGED");row.put("accepted",result.acceptedSubsteps());row.put("rejected",result.rejectedSubsteps());row.put("reasons",result.rejectionReasons());
            }catch(RuntimeException failure){row.put("status","FAILED");row.put("error",failure.toString());}
            row.put("milliseconds",(System.nanoTime()-started)/1e6);System.out.println(row);
            var output=Path.of("build/reports/fluid/stress-island-profile.json");Files.createDirectories(output.getParent());
            Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("source",source.toString(),"island",entry.snapshot().id(),"nodes",graph.reservoirs().size(),"pipes",graph.pipes().size(),"rows",rows)));
        }
    }
}

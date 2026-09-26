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
import static org.junit.jupiter.api.Assertions.*;

/** Replays a preserved failing world read-only; this is a profiler, never an M9 acceptance run. */
class FluidModuleProfileTest {
    @Test void profileActualDeferredReceiver() throws Exception {
        var source=Path.of(System.getProperty("fluid.profile.snapshot"));var tag=NbtIo.readCompressed(source,NbtAccounter.unlimitedHeap()).getCompound("data");
        var models=new HashMap<FluidCheckpointCodec.PackageKey,FluidThermodynamics>();var catalog=MaterialCatalog.bundled();
        java.util.function.Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> resolve=key->models.computeIfAbsent(key,k->FluidThermodynamics.forNetwork(catalog,k.packageId(),k.compressibility()));
        var saved=FluidSavedData.load(tag,resolve,FluidCheckpointStore.directory(source.getParent(),Runnable::run,false)).checkpoint();var module=saved.modules().getFirst();var binding=saved.moduleBindings().stream().filter(b->b.buffer().equals(module.definition().firstProduct())).findFirst().orElseThrow();
        var island=saved.islands().stream().filter(i->i.snapshot().id()==binding.island()).findFirst().orElseThrow();var graph=island.snapshot().graph();long startTick=island.snapshot().clock().committedTick();var model=resolve.apply(new FluidCheckpointCodec.PackageKey(island.packageId(),island.compressibility()));
        var inputs=saved.transfers().pending().values().stream().filter(p->p.receiver().equals(binding.buffer())&&p.dueTick()<=startTick).sorted(Comparator.comparingLong(PendingTransfers.Pending::dueTick)).map(p->new ModuleTransferPlanner.Input(p.id(),binding.reservoir(),p.dueTick(),p.remaining(),p.remaining().massKg())).toList();assertFalse(inputs.isEmpty());
        var rows=new ArrayList<Map<String,Object>>();int index=-1;for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==binding.reservoir())index=i;final int receivingIndex=index;
        for(String mode:List.of("baseline","one-input","planner")) {
            var row=new LinkedHashMap<String,Object>();rows.add(row);row.put("mode",mode);row.put("startTick",startTick);row.put("inputCount",inputs.size());long started=System.nanoTime();
            Runnable checkpoint=()->{if(System.nanoTime()-started>15_000_000_000L)throw new java.util.concurrent.CancellationException("Profiling ceiling 15 s");};
            try {
                PassiveIntervalSolver.Result result;
                if(mode.equals("planner"))result=new ModuleTransferPlanner(model).prepare(graph,startTick,100,inputs,List.of(),checkpoint).candidate();
                else if(mode.equals("one-input")){var p=inputs.getFirst().remaining();var rates=p.moles();for(int c=0;c<rates.length;c++)rates[c]/=5;result=new PassiveIntervalSolver(model).solve(new PassiveNetwork(graph.reservoirs(),graph.pipes(),List.of(new ScheduledTransfer.Injection(Long.MIN_VALUE,receivingIndex,rates,p.energyJoule()/5))),5,PassiveIntervalSolver.Settings.defaults(),checkpoint);}
                else result=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),checkpoint);
                row.put("status","CONVERGED");row.put("accepted",result.acceptedSubsteps());row.put("rejected",result.rejectedSubsteps());row.put("rejectionReasons",result.rejectionReasons());
            }catch(RuntimeException failure){row.put("status","FAILED");row.put("error",failure.toString());}
            row.put("milliseconds",(System.nanoTime()-started)/1e6);System.out.println(row);
            var output=Path.of("build/reports/fluid/M9/module-receiver-profile.json");Files.createDirectories(output.getParent());Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("source",source.toString(),"qualification",false,"rows",rows)));
        }
        assertTrue(rows.stream().allMatch(r->r.get("status").equals("CONVERGED")),rows.toString());
    }
}

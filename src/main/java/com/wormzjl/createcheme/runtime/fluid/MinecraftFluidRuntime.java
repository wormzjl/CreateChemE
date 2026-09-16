package com.wormzjl.createcheme.runtime.fluid;

import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import java.util.*;

/** Owner-thread bridge to the existing shared pool. The world authority owns this bridge's lifetime. */
public final class MinecraftFluidRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final IslandCoordinator coordinator;
    private final Map<Long,ResourceKey<Level>> dimensions=new HashMap<>();
    private final Runnable previousPump;
    private boolean closed;

    public MinecraftFluidRuntime(MinecraftServer server,IslandCoordinator.Publisher publisher,IslandCoordinator.Settings settings) {
        this(server,publisher,settings,(attempt,command)->command,IslandCoordinator.CommitHook.NO_MATERIAL);
    }
    public MinecraftFluidRuntime(MinecraftServer server,IslandCoordinator.Publisher publisher,IslandCoordinator.Settings settings,
            java.util.function.BiFunction<IslandCoordinator.Attempt,ProcessSolveServices.FluidIslandCommand,ProcessSolveServices.FluidSolveCommand> commands,
            IslandCoordinator.CommitHook commitHook) {
        this.server=Objects.requireNonNull(server);owned();
        Objects.requireNonNull(commands);
        coordinator=new IslandCoordinator(new IslandCoordinator.Dispatcher() {
            public void demand(int eligibleOwners){ProcessSolveServices.fluidWorkerDemand(server,eligibleOwners);}
            public int availableWorkers(){var d=ProcessSolveServices.diagnostics(server);return d.readyJobs()>0?0:Math.max(0,d.workerCount()-d.activeWorkers());}
            public long nextRequestId(){return ProcessSolveServices.nextRequestId();}
            public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveServices.FluidIslandCommand command) {
                var target=new ProcessSolveServices.FluidIslandTarget(Objects.requireNonNull(dimensions.get(attempt.islandId())),attempt.islandId());
                var handler=new ProcessSolveServices.FluidCompletionHandler() {
                    public void completed(ProcessSolveServices.FluidIslandCompletion result){coordinator.completed(attempt,result.completion().result());}
                    public void abandoned(ProcessSolveServices.FluidIslandRequest request){coordinator.completed(attempt,Optional.empty());}
                };
                return ProcessSolveServices.submitFluidIsland(server,new ProcessSolveServices.FluidIslandRequest(attempt.slice().requestId(),target,attempt.revision(),handler),Objects.requireNonNull(commands.apply(attempt,command))).admission()==ProcessSolveServices.Admission.ACCEPTED;
            }
            public void cancel(long requestId){ProcessSolveServices.cancelRequest(server,requestId);}
        },publisher,System::nanoTime,settings,commitHook);
        previousPump=ProcessSolveServices.setReadinessPump(server,coordinator::pump);
    }
    private void owned(){if(!server.isSameThread())throw new IllegalStateException("Fluid runtime requires the logical server thread");}
    public IslandCoordinator coordinator(){owned();return coordinator;}
    public void register(ResourceKey<Level> dimension,IslandCoordinator.Snapshot snapshot,FluidThermodynamics model) {
        owned();Objects.requireNonNull(dimension);if(closed||dimensions.containsKey(snapshot.id()))throw new IllegalStateException("Closed/duplicate runtime island");
        coordinator.register(snapshot,model);dimensions.put(snapshot.id(),dimension);
    }
    public void repartition(UUID event,Set<Long> affected,List<IslandCoordinator.Replacement> replacements) {
        owned();if(affected.isEmpty())throw new IllegalArgumentException("Empty topology event");
        var dimension=Objects.requireNonNull(dimensions.get(affected.iterator().next()));
        if(affected.stream().anyMatch(id->!dimension.equals(dimensions.get(id))))throw new IllegalStateException("Cross-dimension pipe connection");
        coordinator.repartition(event,affected,replacements);
        affected.forEach(dimensions::remove);for(var replacement:replacements)dimensions.put(replacement.id(),dimension);
    }
    public void topology(ResourceKey<Level> dimension,UUID event,Set<Long> affected,List<IslandCoordinator.Replacement> replacements,
            FluidThermodynamics model,long committed,long online,Map<Long,com.wormzjl.createcheme.science.fluid.network.PassiveNetwork.Reservoir> additions,Set<Long> removals,Runnable metadataCommit) {
        owned();Objects.requireNonNull(dimension);
        if(affected.stream().anyMatch(id->!dimension.equals(dimensions.get(id))))throw new IllegalStateException("Cross-dimension topology event");
        coordinator.topology(event,affected,replacements,model,committed,online,additions,removals,()->{
            metadataCommit.run();affected.forEach(dimensions::remove);for(var replacement:replacements)dimensions.put(replacement.id(),dimension);
        });
    }
    public void tick(){owned();if(!closed)coordinator.tick();}
    @Override public void close(){owned();if(closed)return;closed=true;coordinator.stop();ProcessSolveServices.setReadinessPump(server,previousPump);}
}

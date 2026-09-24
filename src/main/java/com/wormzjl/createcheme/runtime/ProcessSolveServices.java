package com.wormzjl.createcheme.runtime;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.createcheme.science.column.v3.V3HollandExample32;
import com.wormzjl.createcheme.science.column.v3.V3InitializationOptions;
import com.wormzjl.createcheme.science.column.v3.V3NeuralInitializer;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.world.level.Level;
import com.wormzjl.createcheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.createcheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.fluid.network.ApproximationAnchor;
import com.wormzjl.createcheme.science.fluid.network.ApproximationRejected;
import com.wormzjl.createcheme.runtime.fluid.FluidFallbackPolicy;
import com.wormzjl.createcheme.runtime.fluid.FallbackAllowance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NeoForge lifecycle adapter for the pure, bounded CPU solve service.
 *
 * <p>Every method is server-thread confined. The worker receives only a deeply immutable
 * {@link ProcessSolveCommand}; Minecraft objects and request-delivery metadata stay in the server-thread context
 * map. V3 jobs enter the one owned bounded service through this sealed envelope.</p>
 */
public final class ProcessSolveServices {
    public static final int MAXIMUM_COMPLETIONS_PER_TICK = 64;

    private static final AtomicLong SERVER_EPOCH_SEQUENCE = new AtomicLong();
    private static final AtomicLong PROCESS_REQUEST_SEQUENCE = new AtomicLong();
    private static final Map<MinecraftServer, ServerState> STATES = new IdentityHashMap<>();
    private ProcessSolveServices() {}

    /**
     * Returns the only process-wide request identity used by calculator requests.
     *
     * <p>The value is deliberately not reset with a logical server: this keeps stale packet/log identities
     * unambiguous across an integrated-server restart in the same JVM. It is allocated only on the logical server
     * thread by the network admission boundary.</p>
     */
    public static long nextRequestId() {
        return PROCESS_REQUEST_SEQUENCE.updateAndGet(previous -> {
            if (previous == Long.MAX_VALUE) {
                throw new IllegalStateException("Process-solve request sequence exhausted");
            }
            return previous + 1L;
        });
    }

    /** Creates exactly one owned solve service for a starting logical server. */
    public static ServerStarted startServer(MinecraftServer server, Config config) {
        requireServerThread(server);
        Objects.requireNonNull(config, "config");
        if (STATES.containsKey(server)) {
            throw new IllegalStateException("Process solve service already exists for this server");
        }
        long epoch = SERVER_EPOCH_SEQUENCE.incrementAndGet();
        var wakeup=new CompletionWakeup(action->server.tell(new TickTask(0,action)),()->{
            var current=STATES.get(server);
            if(current==null||current.stopResult!=null)return;
            com.wormzjl.createcheme.network.ProcessSolveCoordinator.drainCompletedCalculations(server);
        });
        BoundedCpuSolveService<SolveTarget, ProcessSolveCommand, ProcessSolveResult> service =
                new BoundedCpuSolveService<>(epoch, new BoundedCpuSolveService.Config(
                        config.workerCount(),
                        config.readyCapacity(),
                        "createcheme-cpu-solve-",
                        true,
                        config.gracefulShutdown(),
                        config.forcedShutdown()),wakeup::signal);
        STATES.put(server, new ServerState(service, config,wakeup));
        if(config.dynamicWorkers())service.setWorkerLimit(1);
        BoundedCpuSolveService.Diagnostics diagnostics = service.diagnostics();
        return new ServerStarted(
                epoch,
                diagnostics.workerCount(),
                diagnostics.readyCapacity(),
                config.solveDeadline().toMillis(),
                config.gracefulShutdown().toMillis(),
                config.forcedShutdown().toMillis());
    }

    /** Admits one immutable V3 input through the existing shared bounded service. */
    public static AdmissionResult submitV3Column(MinecraftServer server, V3ColumnRequest request) {
        requireServerThread(server);
        Objects.requireNonNull(request, "request");
        double stageTraceCutoffMoleFraction = CreateChemE.columnV3StageTraceCutoffMolPercent() / 100.0;
        double convergenceClosureFraction = CreateChemE.columnV3ConvergenceClosurePercent() / 100.0;
        double liquidSupplyScreenRatio = CreateChemE.columnV3LiquidSupplyScreenRatio();
        V3InitializationOptions initialization = CreateChemE.columnV3InitializationOptions();
        var catalog=com.wormzjl.createcheme.science.material.MaterialRuntime.current();
        V3NeuralInitializer model = initialization.mode() == V3InitializationOptions.Mode.CURRENT_ONLY
                ? V3NeuralInitializer.UNAVAILABLE : CreateChemE.columnV3NeuralModel().bind(catalog,request.operation().input());
        return submit(server, request, new V3ColumnCommand(request.operation().input(),
                        stageTraceCutoffMoleFraction, convergenceClosureFraction, initialization, model,
                        liquidSupplyScreenRatio,catalog),
                request.operation().input().packageId());
    }

    /** Fluid islands share the existing workers and queue with V3; their wall budget starts on the worker. */
    public static AdmissionResult submitFluidIsland(MinecraftServer server,FluidIslandRequest request,FluidSolveCommand command) {
        requireServerThread(server);Objects.requireNonNull(request);Objects.requireNonNull(command);
        var state=STATES.get(server);
        if(state!=null) {
            var diagnostics=Diagnostics.from(state.service.diagnostics());
            if(state.requestsBySequence.values().stream().anyMatch(context->context.request().target().equals(request.target())))return new AdmissionResult(Admission.OWNER_BUSY,diagnostics);
            // Fluid readiness stays in the coordinator's owner set. Fill all idle workers, but do not
            // occupy every queue slot with catch-up jobs ahead of newly arriving calculator/module work.
            if(diagnostics.readyJobs()>0||diagnostics.activeWorkers()>=diagnostics.workerCount())return new AdmissionResult(Admission.QUEUE_FULL,diagnostics);
        }
        return submit(server,request,command,command.model().hydrocarbon.revision()+":"+command.model().viscosity.revision());
    }

    public static Diagnostics diagnostics(MinecraftServer server) {
        requireServerThread(server);var state=STATES.get(server);return state==null?Diagnostics.EMPTY:Diagnostics.from(state.service.diagnostics());
    }
    /** Independent fluid owners report readiness without queueing their snapshots. Other module
     * and calculator jobs already owned by the service share the same demand budget. */
    public static void fluidWorkerDemand(MinecraftServer server,int eligibleOwners) {
        requireServerThread(server);if(eligibleOwners<0)throw new IllegalArgumentException("Negative fluid demand");
        var state=STATES.get(server);if(state==null||state.stopResult!=null)return;
        state.fluidDemand=eligibleOwners;allocateWorkers(server,state,0);
    }
    /** Ticks until the automatic allocator would apply its pending lower-demand shrink when observed, or -1 when
     * none is pending or the pool is fixed. The fluid scheduler answers it with one deadline instead of
     * observing demand on every tick. */
    public static long fluidDemandShrinkDelay(MinecraftServer server) {
        requireServerThread(server);var state=STATES.get(server);
        if(state==null||state.stopResult!=null||state.allocator==null)return -1;
        long due=state.allocator.shrinkTick();
        return due<0?-1:Math.max(0,due-Integer.toUnsignedLong(server.getTickCount()));
    }
    private static void allocateWorkers(MinecraftServer server,ServerState state,int incoming) {
        if(state.allocator==null)return;
        int demand=Math.addExact(state.service.diagnostics().outstandingJobs(),Math.addExact(state.fluidDemand,incoming));
        state.service.setWorkerLimit(state.allocator.observe(demand,Integer.toUnsignedLong(server.getTickCount())));
    }
    /** Installs the one world coordinator's owner-thread readiness pump; workers never invoke it. */
    public static Runnable setReadinessPump(MinecraftServer server,Runnable pump) {
        requireServerThread(server);var state=STATES.get(server);if(state==null)throw new IllegalStateException("Process service is not started");var previous=state.readinessPump;state.readinessPump=Objects.requireNonNull(pump);return previous;
    }
    public static void pumpReady(MinecraftServer server) {
        requireServerThread(server);var state=STATES.get(server);if(state!=null&&state.stopResult==null)state.readinessPump.run();
    }

    /** Cancellation does not release owner/worker capacity until the terminal completion is drained. */
    public static BoundedCpuSolveService.CancellationResult cancelRequest(MinecraftServer server,long requestId) {
        requireServerThread(server);var state=STATES.get(server);var context=state==null?null:state.requestsBySequence.get(requestId);
        return context==null?BoundedCpuSolveService.CancellationResult.NOT_FOUND:state.service.cancel(context.stamp(),BoundedCpuSolveService.CancelReason.STALE_REVISION);
    }

    private static AdmissionResult submit(
            MinecraftServer server,
            ProcessSolveRequest request,
            ProcessSolveCommand command,
            String datasetRevision) {
        ServerState state = STATES.get(server);
        if (state == null) {
            return new AdmissionResult(Admission.SERVICE_UNAVAILABLE, Diagnostics.EMPTY);
        }
        if (state.requestsBySequence.containsKey(request.requestId())) {
            throw new IllegalStateException("Duplicate process-solve request sequence");
        }

        allocateWorkers(server,state,request instanceof FluidIslandRequest&&state.fluidDemand>0?0:1);
        long now = System.nanoTime();
        long deadline = request instanceof FluidIslandRequest?BoundedCpuSolveService.NO_DEADLINE:now + state.config.solveDeadline().toNanos();
        var stamp = new BoundedCpuSolveService.JobStamp<SolveTarget>(
                state.service.serverEpoch(),
                request.requestId(),
                request.target(),
                request.inputRevision(),
                datasetRevision,
                deadline);
        BoundedCpuSolveService.Admission serviceAdmission = state.service.trySubmit(
                stamp, command, ProcessSolveServices::solveCommand);
        BoundedCpuSolveService.Diagnostics serviceDiagnostics = state.service.diagnostics();
        Diagnostics diagnostics = Diagnostics.from(serviceDiagnostics);
        Admission admission = Admission.from(serviceAdmission);
        if (admission == Admission.ACCEPTED) {
            if(request instanceof FluidIslandRequest&&state.fluidDemand>0)state.fluidDemand--;
            RequestContext old = state.requestsBySequence.put(
                    request.requestId(), new RequestContext(request, diagnostics, stamp));
            if (old != null) {
                throw new IllegalStateException("Accepted process-solve request replaced existing context");
            }
        }
        return new AdmissionResult(admission, diagnostics);
    }

    /** Drains safely-published worker completions on the logical server thread. */
    public static List<ProcessSolveCompletion> drainCompletions(MinecraftServer server, int maximum) {
        requireServerThread(server);
        if(maximum<1)throw new IllegalArgumentException("maximum must be positive");
        ServerState state = STATES.get(server);
        if (state == null) {
            return List.of();
        }
        int tick=server.getTickCount();if(state.lastDrainTick!=tick){state.lastDrainTick=tick;state.drainedThisTick=0;}
        int remaining=Math.min(maximum,Math.max(0,MAXIMUM_COMPLETIONS_PER_TICK-state.drainedThisTick));
        // A wakeup can arrive after this tick has consumed its budget. Keep the terminal
        // message queued for the next tick; the underlying service requires a positive drain.
        if(remaining==0){owe(server,state,tick);return List.of();}
        var result=drain(state,remaining);
        state.drainedThisTick+=result.size();
        if(state.drainedThisTick>=MAXIMUM_COMPLETIONS_PER_TICK)owe(server,state,tick);
        return result;
    }
    /**
     * A drain that stopped at the per-tick budget with completions left behind owes exactly one continuation.
     * It is posted once per exhausted tick and never drains inside that tick, so it cannot spin; it drains
     * once the tick has advanced, so completed work cannot strand even with no further worker activity.
     *
     * <p>The TickTask tick is not a delay in this Minecraft version (it only marks a task as late), so the
     * posted task normally runs in the same tick's idle phase, finds the tick unchanged and leaves the owed
     * drain to the next tick's completion hook, which drains before anything else that tick. If the task
     * instead runs in a later tick (a blocked mailbox), it performs the drain itself.
     */
    private static void owe(MinecraftServer server,ServerState state,int exhaustedTick) {
        if(state.continuationTick==exhaustedTick||state.service.diagnostics().pendingCompletions()==0)return;
        state.continuationTick=exhaustedTick;
        com.wormzjl.createcheme.runtime.fluid.FluidRuntimeDiagnostics.count(com.wormzjl.createcheme.runtime.fluid.FluidRuntimeDiagnostics.drainContinuations);
        server.tell(new TickTask(1,()->{
            if(STATES.get(server)!=state||state.stopResult!=null)return;
            if(server.getTickCount()==exhaustedTick) {
                com.wormzjl.createcheme.runtime.fluid.FluidRuntimeDiagnostics.count(com.wormzjl.createcheme.runtime.fluid.FluidRuntimeDiagnostics.drainContinuationsDeferred);
                return;
            }
            com.wormzjl.createcheme.network.ProcessSolveCoordinator.drainCompletedCalculations(server);
        }));
    }

    /** Stops admission and performs the owned bounded two-phase shutdown. */
    public static StopResult stopServer(MinecraftServer server) {
        requireServerThread(server);
        ServerState state = STATES.get(server);
        if (state == null) {
            return StopResult.missing();
        }
        if (state.stopResult != null) {
            return StopResult.alreadyStopped(state.stopResult.shutdownReport());
        }

        state.wakeup.close();
        BoundedCpuSolveService.ShutdownReport shutdown =
                state.service.shutdown(state.config.gracefulShutdown(), state.config.forcedShutdown());
        List<ProcessSolveCompletion> completions = drain(state, Integer.MAX_VALUE);
        List<ProcessSolveRequest> abandoned = state.requestsBySequence.values().stream()
                .map(RequestContext::request)
                .toList();
        state.requestsBySequence.clear();
        state.stopResult = new StopResult(true, shutdown, completions, abandoned);
        return state.stopResult;
    }

    /** Removes the stopped server identity after the final NeoForge lifecycle event. */
    public static int removeStoppedServer(MinecraftServer server) {
        requireServerThread(server);
        ServerState state = STATES.remove(server);
        if (state == null) {
            return 0;
        }
        if (state.stopResult == null) {
            BoundedCpuSolveService.ShutdownReport ignored =
                    state.service.shutdown(state.config.gracefulShutdown(), state.config.forcedShutdown());
            drain(state, Integer.MAX_VALUE);
            int abandoned = state.requestsBySequence.size();
            state.requestsBySequence.clear();
            return abandoned;
        }
        return state.requestsBySequence.size();
    }

    private static ProcessSolveResult solveCommand(
            ProcessSolveCommand command, BoundedCpuSolveService.CancellationToken cancellationToken) {
        return command.solve(cancellationToken);
    }

    private static List<ProcessSolveCompletion> drain(ServerState state, int maximum) {
        List<BoundedCpuSolveService.Completion<SolveTarget, ProcessSolveResult>> completed =
                state.service.drainCompletions(maximum);
        if (completed.isEmpty()) {
            return List.of();
        }
        List<ProcessSolveCompletion> result = new ArrayList<>(completed.size());
        for (var completion : completed) {
            RequestContext context = state.requestsBySequence.remove(completion.stamp().sequence());
            if (context == null) {
                throw new IllegalStateException("Process-solve completion has no request context");
            }
            if (context.request() instanceof V3ColumnRequest v3Request) {
                result.add(new V3ColumnCompletion(v3Request, context.admissionDiagnostics(), v3Completion(completion)));
            } else if(context.request() instanceof FluidIslandRequest fluidRequest) {
                result.add(new FluidIslandCompletion(fluidRequest,context.admissionDiagnostics(),fluidCompletion(completion)));
            } else {
                throw new IllegalStateException("Unknown process-solve request family");
            }
        }
        return List.copyOf(result);
    }

    private static BoundedCpuSolveService.Completion<ColumnTarget, V3ColumnOutcome> v3Completion(
            BoundedCpuSolveService.Completion<SolveTarget, ProcessSolveResult> completion) {
        Optional<V3ColumnOutcome> outcome = completion.result().map(result -> {
            if (!(result instanceof V3ColumnSolveResult v3)) {
                throw new IllegalStateException("V3 request completed with a non-V3 process result");
            }
            return v3.outcome();
        });
        return new BoundedCpuSolveService.Completion<>(
                stampFor(completion.stamp(),ColumnTarget.class), completion.status(), outcome, completion.failure(), completion.detail(),
                completion.enqueuedNanos(), completion.startedNanos(), completion.completedNanos());
    }

    private static <T extends SolveTarget> BoundedCpuSolveService.JobStamp<T> stampFor(BoundedCpuSolveService.JobStamp<SolveTarget> stamp,Class<T> type) {
        return new BoundedCpuSolveService.JobStamp<>(stamp.serverEpoch(),stamp.sequence(),type.cast(stamp.owner()),stamp.inputRevision(),stamp.datasetRevision(),stamp.deadlineNanos());
    }
    private static BoundedCpuSolveService.Completion<FluidIslandTarget,FluidIslandSolveResult> fluidCompletion(BoundedCpuSolveService.Completion<SolveTarget,ProcessSolveResult> completion) {
        var result=completion.result().map(value->{if(!(value instanceof FluidIslandSolveResult fluid))throw new IllegalStateException("Fluid request completed with a different result family");return fluid;});
        return new BoundedCpuSolveService.Completion<>(stampFor(completion.stamp(),FluidIslandTarget.class),completion.status(),result,completion.failure(),completion.detail(),completion.enqueuedNanos(),completion.startedNanos(),completion.completedNanos());
    }

    private static void requireServerThread(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Process solve services must be coordinated on the server thread");
        }
    }

    public enum Admission {
        ACCEPTED,
        OWNER_BUSY,
        QUEUE_FULL,
        STALE_EPOCH,
        STOPPING,
        SERVICE_UNAVAILABLE;

        private static Admission from(BoundedCpuSolveService.Admission admission) {
            return switch (admission) {
                case ACCEPTED -> ACCEPTED;
                case OWNER_BUSY -> OWNER_BUSY;
                case QUEUE_FULL -> QUEUE_FULL;
                case STALE_EPOCH -> STALE_EPOCH;
                case STOPPING -> STOPPING;
            };
        }
    }

    /** Immutable worker envelope. Adding a job family extends this sealed protocol, never the executor count. */
    public sealed interface ProcessSolveCommand permits V3ColumnCommand,FluidSolveCommand {
        ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken);
    }

    /** Immutable worker result envelope routed only after main-thread completion draining. */
    public sealed interface ProcessSolveResult permits V3ColumnSolveResult,FluidIslandSolveResult {}

    public sealed interface FluidSolveCommand extends ProcessSolveCommand permits FluidIslandCommand,BufferedIslandCommand {
        FluidThermodynamics model();
    }

    /** Contains no live level, block entity, callback, or mutable server-owned collection. */
    public record FluidIslandCommand(FluidThermodynamics model,PassiveNetwork snapshot,double durationSeconds,
                                     PassiveIntervalSolver.Settings settings,long wallBudgetNanos,FluidFallbackPolicy fallback,
                                     com.wormzjl.createcheme.runtime.fluid.RetainedSolver retained) implements FluidSolveCommand {
        public FluidIslandCommand(FluidThermodynamics model,PassiveNetwork snapshot,double durationSeconds,PassiveIntervalSolver.Settings settings,long wallBudgetNanos) {
            this(model,snapshot,durationSeconds,settings,wallBudgetNanos,FluidFallbackPolicy.disabled());
        }
        /** Without a retained handle the job gets its own, which is the per-job solver of before. */
        public FluidIslandCommand(FluidThermodynamics model,PassiveNetwork snapshot,double durationSeconds,PassiveIntervalSolver.Settings settings,long wallBudgetNanos,FluidFallbackPolicy fallback) {
            this(model,snapshot,durationSeconds,settings,wallBudgetNanos,fallback,new com.wormzjl.createcheme.runtime.fluid.RetainedSolver());
        }
        public FluidIslandCommand {
            Objects.requireNonNull(model);Objects.requireNonNull(snapshot);Objects.requireNonNull(settings);Objects.requireNonNull(fallback);Objects.requireNonNull(retained);
            if(!Double.isFinite(durationSeconds)||durationSeconds<=0||wallBudgetNanos<=0)throw new IllegalArgumentException("Invalid island solve budget");
            if(fallback.enabled()&&(fallback.softBudgetNanos()>=wallBudgetNanos||durationSeconds<.05||durationSeconds>20||!Double.isFinite(durationSeconds*20)
                    ||Math.abs(durationSeconds*20-Math.rint(durationSeconds*20))>1e-8))throw new IllegalArgumentException("Fallback needs tick-aligned duration and separate soft/hard budgets");
        }
        @Override public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            long cpuStart=WorkerAllocation.CpuTime.sample();
            var result=(FluidIslandSolveResult)solve(cancellationToken,System::nanoTime);
            long cpu=WorkerAllocation.CpuTime.elapsed(cpuStart,WorkerAllocation.CpuTime.sample());
            return new FluidIslandSolveResult(result.candidate(),result.detail(),result.workerNanos(),cpu,result.proposedAllowance(),result.proposedAnchor(),Optional.empty(),result.domain());
        }
        ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken,java.util.function.LongSupplier nanoClock) {
            Objects.requireNonNull(cancellationToken);Objects.requireNonNull(nanoClock);long start=nanoClock.getAsLong();
            long ticks=Math.round(durationSeconds*20);boolean eligible=fallback.enabled()&&!com.wormzjl.createcheme.science.fluid.transport.SolidMobility.requiresFull(model,snapshot)&&fallback.anchor().orElseThrow().propertyRevision().equals(ApproximationAnchor.revision(model))&&fallback.allowance().permits(ticks,fallback.cadenceTicks());
            long[] elapsed={0};
            Runnable hard=()->{cancellationToken.throwIfCancellationRequested();elapsed[0]=nanoClock.getAsLong()-start;if(elapsed[0]>=wallBudgetNanos)throw new FluidWallDeadline();};
            Runnable checkpoint=()->{hard.run();if(eligible&&elapsed[0]>=fallback.softBudgetNanos())throw new FluidSoftDeadline();};
            try {
                try {
                    var candidate=retained.solve(model,snapshot,durationSeconds,settings,checkpoint);hard.run();
                    return new FluidIslandSolveResult(Optional.of(candidate),"FULL",elapsed[0],FallbackAllowance.NONE,Optional.of(ApproximationAnchor.fromFull(model,candidate)));
                }catch(FluidSoftDeadline deadline) {
                    hard.run();
                    // A refused fallback is still the soft budget's decision: the full solve had not finished when it
                    // ran out. Its detail says so, so the hold policy treats it as a budget hold (IslandCoordinator.hold).
                    try {
                        var guard=fallback.anchor().orElseThrow().guard(model,snapshot);
                        var approximateSettings=new PassiveIntervalSolver.Settings(Math.min(1,durationSeconds),20,.0025,256);
                        var candidate=retained.solveApproximate(model,snapshot,durationSeconds,approximateSettings,hard,guard);hard.run();
                        return new FluidIslandSolveResult(Optional.of(candidate),"APPROXIMATE: soft budget",elapsed[0],fallback.allowance().accept(ticks,fallback.cadenceTicks()),fallback.anchor());
                    }catch(com.wormzjl.createcheme.science.fluid.solver.SparseNewton.Nonconvergence|IllegalArgumentException|ApproximationRejected refused) {
                        cancellationToken.throwIfCancellationRequested();
                        return new FluidIslandSolveResult(Optional.empty(),SOFT_BUDGET_REFUSED+refused.getMessage(),elapsed[0],fallback.allowance(),fallback.anchor());
                    }
                }
            }catch(FluidWallDeadline deadline){return new FluidIslandSolveResult(Optional.empty(),WALL_DEADLINE,elapsed[0],fallback.allowance(),fallback.anchor());}
            catch(com.wormzjl.createcheme.science.fluid.solver.SparseNewton.Nonconvergence|IllegalArgumentException|ApproximationRejected failed) {
                cancellationToken.throwIfCancellationRequested();
                // A failure the property domain decided carries the violation itself, so the coordinator can hold the island
                // with the dedicated status and tell a reproduced violation from a numerical one (IslandCoordinator.hold).
                var domain=Optional.ofNullable(com.wormzjl.createcheme.science.fluid.solver.SparseNewton.domainViolation(failed));
                return new FluidIslandSolveResult(Optional.empty(),domain.map(v->THERMO_DOMAIN+v.getMessage()).orElse("HELD: "+failed.getMessage()),elapsed[0],-1,fallback.allowance(),fallback.anchor(),Optional.empty(),domain);
            }
        }
    }
    /** The detail of an island interval the hard wall budget cut. */
    public static final String WALL_DEADLINE="HELD: wall deadline";
    /** The detail prefix of an island interval the property domain refused; the result carries the violation. */
    public static final String THERMO_DOMAIN="HELD (thermo domain): ";
    /** The detail prefix of an island interval whose full solve ran out of the soft budget and whose approximate
     * fallback then refused the interval. */
    public static final String SOFT_BUDGET_REFUSED="HELD: soft budget; approximate fallback refused: ";
    private static final class FluidWallDeadline extends RuntimeException {}
    private static final class FluidSoftDeadline extends RuntimeException {}
    public record FluidIslandSolveResult(Optional<PassiveIntervalSolver.Result> candidate,String detail,long workerNanos,long workerCpuNanos,
                                        FallbackAllowance proposedAllowance,Optional<ApproximationAnchor> proposedAnchor,
                                        Optional<com.wormzjl.createcheme.runtime.fluid.ModuleTransferPlanner.Proposal> materialTransfers,
                                        Optional<com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation> domain) implements ProcessSolveResult {
        public FluidIslandSolveResult(Optional<PassiveIntervalSolver.Result> candidate,String detail,long workerNanos,long workerCpuNanos,FallbackAllowance allowance,Optional<ApproximationAnchor> anchor) {
            this(candidate,detail,workerNanos,workerCpuNanos,allowance,anchor,Optional.empty());
        }
        public FluidIslandSolveResult(Optional<PassiveIntervalSolver.Result> candidate,String detail,long workerNanos,long workerCpuNanos,FallbackAllowance allowance,Optional<ApproximationAnchor> anchor,
                                      Optional<com.wormzjl.createcheme.runtime.fluid.ModuleTransferPlanner.Proposal> materialTransfers) {
            this(candidate,detail,workerNanos,workerCpuNanos,allowance,anchor,materialTransfers,Optional.empty());
        }
        public FluidIslandSolveResult(Optional<PassiveIntervalSolver.Result> candidate,String detail,long workerNanos,FallbackAllowance allowance,Optional<ApproximationAnchor> anchor) {
            this(candidate,detail,workerNanos,-1,allowance,anchor);
        }
        public FluidIslandSolveResult {Objects.requireNonNull(domain);if(domain.isPresent()&&candidate.isPresent())throw new IllegalArgumentException("An accepted interval has no domain failure");Objects.requireNonNull(candidate);Objects.requireNonNull(detail);Objects.requireNonNull(proposedAllowance);Objects.requireNonNull(proposedAnchor);Objects.requireNonNull(materialTransfers);if(workerNanos<0||workerCpuNanos < -1)throw new IllegalArgumentException("Invalid worker elapsed time");if(materialTransfers.isPresent()&&(candidate.isEmpty()||materialTransfers.orElseThrow().candidate()!=candidate.orElseThrow()))throw new IllegalArgumentException("Material ledger/result mismatch");}
    }
    /** New material boundaries require full qualification; failed probes never consume pending inputs. */
    public record BufferedIslandCommand(FluidThermodynamics model,PassiveNetwork snapshot,long startTick,int durationTicks,
            List<com.wormzjl.createcheme.runtime.fluid.ModuleTransferPlanner.Input> inputs,
            List<com.wormzjl.createcheme.runtime.fluid.ModuleTransferPlanner.Withdrawal> withdrawals,
            long wallBudgetNanos,FallbackAllowance previousAllowance,Optional<ApproximationAnchor> previousAnchor,
            com.wormzjl.createcheme.runtime.fluid.RetainedSolver retained) implements FluidSolveCommand {
        /** Without a retained handle the job gets its own, which is the per-job solver of before. */
        public BufferedIslandCommand(FluidThermodynamics model,PassiveNetwork snapshot,long startTick,int durationTicks,
                List<com.wormzjl.createcheme.runtime.fluid.ModuleTransferPlanner.Input> inputs,
                List<com.wormzjl.createcheme.runtime.fluid.ModuleTransferPlanner.Withdrawal> withdrawals,
                long wallBudgetNanos,FallbackAllowance previousAllowance,Optional<ApproximationAnchor> previousAnchor) {
            this(model,snapshot,startTick,durationTicks,inputs,withdrawals,wallBudgetNanos,previousAllowance,previousAnchor,
                    new com.wormzjl.createcheme.runtime.fluid.RetainedSolver());
        }
        public BufferedIslandCommand {
            Objects.requireNonNull(model);Objects.requireNonNull(snapshot);inputs=List.copyOf(inputs);withdrawals=List.copyOf(withdrawals);Objects.requireNonNull(previousAllowance);Objects.requireNonNull(previousAnchor);Objects.requireNonNull(retained);
            if(startTick<0||durationTicks<1||wallBudgetNanos<1||inputs.size()+withdrawals.size()>4096)throw new IllegalArgumentException("Invalid buffered interval");
        }
        @Override public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken token) {
            long started=System.nanoTime(),cpuStart=WorkerAllocation.CpuTime.sample();
            Runnable checkpoint=()->{token.throwIfCancellationRequested();if(System.nanoTime()-started>=wallBudgetNanos)throw new FluidWallDeadline();};
            Optional<com.wormzjl.createcheme.runtime.fluid.ModuleTransferPlanner.Proposal> proposal=Optional.empty();String detail;Optional<com.wormzjl.createcheme.science.fluid.thermo.ThermoDomainViolation> domain=Optional.empty();
            try {
                proposal=Optional.of(new com.wormzjl.createcheme.runtime.fluid.ModuleTransferPlanner(model).prepare(snapshot,startTick,durationTicks,inputs,withdrawals,checkpoint,retained));checkpoint.run();detail="FULL: buffered transfers";
            }catch(FluidWallDeadline cut){proposal=Optional.empty();detail=WALL_DEADLINE+" (buffered interval)";}
            catch(com.wormzjl.createcheme.science.fluid.solver.SparseNewton.Nonconvergence|IllegalArgumentException held){proposal=Optional.empty();domain=Optional.ofNullable(com.wormzjl.createcheme.science.fluid.solver.SparseNewton.domainViolation(held));detail=domain.map(v->THERMO_DOMAIN+v.getMessage()).orElse("HELD: buffered interval "+held.getMessage());}
            token.throwIfCancellationRequested();long elapsed=System.nanoTime()-started;
            long cpu=WorkerAllocation.CpuTime.elapsed(cpuStart,WorkerAllocation.CpuTime.sample());
            var candidate=proposal.map(com.wormzjl.createcheme.runtime.fluid.ModuleTransferPlanner.Proposal::candidate);
            return new FluidIslandSolveResult(candidate,detail,elapsed,cpu,candidate.isPresent()?FallbackAllowance.NONE:previousAllowance,candidate.map(r->ApproximationAnchor.fromFull(model,r)).or(()->previousAnchor),proposal,domain);
        }
    }

    /** Immutable worker snapshot; configuration has already been read and converted by server-thread admission. */
    record V3ColumnCommand(
            V3ColumnInput input, double stageTraceCutoffMoleFraction, double convergenceClosureFraction,
            V3InitializationOptions initialization, V3NeuralInitializer model, double liquidSupplyScreenRatio,
            com.wormzjl.createcheme.science.material.MaterialCatalog catalog)
            implements ProcessSolveCommand {
        V3ColumnCommand(V3ColumnInput input, double cutoff, double closure,
                V3InitializationOptions initialization, V3NeuralInitializer model, double screen) {
            this(input, cutoff, closure, initialization, model, screen,
                    com.wormzjl.createcheme.science.material.MaterialRuntime.current());
        }
        /** Existing initializer-explicit callers retain the default request-only liquid-supply screen. */
        V3ColumnCommand(V3ColumnInput input, double cutoff, double closure,
                V3InitializationOptions initialization, V3NeuralInitializer model) {
            this(input, cutoff, closure, initialization, model,
                    V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        }
        /** Existing explicit numerical callers retain their classical behavior. */
        V3ColumnCommand(V3ColumnInput input, double cutoff, double closure) {
            this(input, cutoff, closure, V3InitializationOptions.CURRENT, V3NeuralInitializer.UNAVAILABLE);
        }
        /** Cutoff-only snapshot at the frozen default convergence closure. */
        V3ColumnCommand(V3ColumnInput input, double stageTraceCutoffMoleFraction) {
            this(input, stageTraceCutoffMoleFraction, 0.0);
        }

        V3ColumnCommand {
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(initialization, "initialization");
            Objects.requireNonNull(model, "model");
            if (!Double.isFinite(stageTraceCutoffMoleFraction) || stageTraceCutoffMoleFraction < 0.0
                    || stageTraceCutoffMoleFraction > 0.01) {
                throw new IllegalArgumentException("V3 stage-trace cutoff must be finite and in [0, 0.01] mole fraction");
            }
            if (!Double.isFinite(convergenceClosureFraction) || convergenceClosureFraction < 0.0
                    || convergenceClosureFraction > 1.0e-3) {
                throw new IllegalArgumentException("V3 convergence closure must be finite and in [0, 1e-3]");
            }
            // Revalidated here rather than trusted from the config range, exactly as the two fractions above are.
            if (!Double.isFinite(liquidSupplyScreenRatio) || liquidSupplyScreenRatio < 0.0
                    || liquidSupplyScreenRatio > 1.0) {
                throw new IllegalArgumentException("V3 liquid-supply screen ratio must be finite and in [0, 1]");
            }
        }

        @Override
        public ProcessSolveResult solve(BoundedCpuSolveService.CancellationToken cancellationToken) {
            return com.wormzjl.createcheme.science.material.MaterialRuntime.with(catalog, input.packageId(),
                    () -> solveInCatalog(cancellationToken));
        }
        private ProcessSolveResult solveInCatalog(BoundedCpuSolveService.CancellationToken cancellationToken) {
            cancellationToken.throwIfCancellationRequested();
            V3ColumnOutcome outcome = V3HollandExample32.isPackage(input.packageId())
                    && initialization.mode() != V3InitializationOptions.Mode.LNN_ONLY
                    ? V3HollandExample32.calculate(input, cancellationToken::throwIfCancellationRequested)
                    : V3ColumnCalculator.calculate(input, cancellationToken::throwIfCancellationRequested,
                            stageTraceCutoffMoleFraction, convergenceClosureFraction, initialization, model,
                            liquidSupplyScreenRatio);
            cancellationToken.throwIfCancellationRequested();
            return new V3ColumnSolveResult(outcome);
        }
    }

    record V3ColumnSolveResult(V3ColumnOutcome outcome) implements ProcessSolveResult {
        V3ColumnSolveResult {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** Stable block identity; no loaded level or block entity is retained. */
    public sealed interface SolveTarget permits ColumnTarget,FluidIslandTarget {}
    public record ColumnTarget(ResourceKey<Level> dimension, BlockPos blockPos) implements SolveTarget {
        public ColumnTarget {
            Objects.requireNonNull(dimension, "dimension");
            blockPos = Objects.requireNonNull(blockPos, "blockPos").immutable();
        }
    }

    public record FluidIslandTarget(ResourceKey<Level> dimension,long islandId) implements SolveTarget {
        public FluidIslandTarget {Objects.requireNonNull(dimension);if(islandId<=0)throw new IllegalArgumentException("Invalid island identity");}
    }

    /** Immutable server-thread request context retained until exactly one terminal completion is drained. */
    public sealed interface ProcessSolveRequest permits V3ColumnRequest,FluidIslandRequest {
        long requestId();

        SolveTarget target();

        long inputRevision();
    }

    /** Server-only delivery context; the handler is never included in the worker command. */
    public record FluidIslandRequest(long requestId,FluidIslandTarget target,long inputRevision,FluidCompletionHandler handler) implements ProcessSolveRequest {
        public FluidIslandRequest {Objects.requireNonNull(target);Objects.requireNonNull(handler);if(requestId<=0||inputRevision<0)throw new IllegalArgumentException("Invalid fluid request stamp");}
    }
    public interface FluidCompletionHandler {
        void completed(FluidIslandCompletion completion);
        void abandoned(FluidIslandRequest request);
    }
    public record FluidIslandCompletion(FluidIslandRequest request,Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.Completion<FluidIslandTarget,FluidIslandSolveResult> completion) implements ProcessSolveCompletion {
        public FluidIslandCompletion {
            Objects.requireNonNull(request);Objects.requireNonNull(admissionDiagnostics);Objects.requireNonNull(completion);
            if(request.requestId()!=completion.stamp().sequence()||!request.target().equals(completion.stamp().owner())||request.inputRevision()!=completion.stamp().inputRevision())throw new IllegalArgumentException("Fluid completion stamp mismatch");
        }
    }

    /** Immutable V3 pilot metadata retained only on the server thread until worker completion drains. */
    public record V3ColumnRequest(
            long requestId, ColumnTarget target, V3Operation operation, long receivedNanos) implements ProcessSolveRequest {
        public V3ColumnRequest {
            if (requestId <= 0L || receivedNanos <= 0L) {
                throw new IllegalArgumentException("V3 request identifiers must be positive");
            }
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(operation, "operation");
            if (requestId != operation.operationId()) {
                throw new IllegalArgumentException("V3 request identity disagrees with its operation");
            }
        }

        @Override
        public long inputRevision() {
            return operation.inputRevision();
        }
    }

    /** One V3 completion joined to immutable server-thread metadata for stale-operation rejection. */
    public record V3ColumnCompletion(
            V3ColumnRequest request,
            Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.Completion<ColumnTarget, V3ColumnOutcome> completion)
            implements ProcessSolveCompletion {
        public V3ColumnCompletion {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(admissionDiagnostics, "admissionDiagnostics");
            Objects.requireNonNull(completion, "completion");
            if (request.requestId() != completion.stamp().sequence()
                    || !request.target().equals(completion.stamp().owner())
                    || request.inputRevision() != completion.stamp().inputRevision()) {
                throw new IllegalArgumentException("Completion stamp does not match V3 request context");
            }
        }
    }

    /** Marker for the central router; only it may drain the shared completion queue. */
    public sealed interface ProcessSolveCompletion permits V3ColumnCompletion,FluidIslandCompletion {}

    public record AdmissionResult(Admission admission, Diagnostics diagnostics) {
        public AdmissionResult {
            Objects.requireNonNull(admission, "admission");
            Objects.requireNonNull(diagnostics, "diagnostics");
        }

        public boolean accepted() {
            return admission == Admission.ACCEPTED;
        }
    }

    public record Diagnostics(
            int workerCount,
            int activeWorkers,
            int readyJobs,
            int readyCapacity,
            int outstandingJobs,
            int pendingCompletions) {
        public static final Diagnostics EMPTY = new Diagnostics(0, 0, 0, 0, 0, 0);

        private static Diagnostics from(BoundedCpuSolveService.Diagnostics diagnostics) {
            return new Diagnostics(
                    diagnostics.workerCount(),
                    diagnostics.activeWorkers(),
                    diagnostics.readyJobs(),
                    diagnostics.readyCapacity(),
                    diagnostics.outstandingJobs(),
                    diagnostics.pendingCompletions());
        }
    }

    public record ServerStarted(
            long serverEpoch,
            int workerCount,
            int readyCapacity,
            long solveDeadlineMilliseconds,
            long gracefulShutdownMilliseconds,
            long forcedShutdownMilliseconds) {}

    public record Config(
            int workerCount,
            int readyCapacity,
            Duration solveDeadline,
            Duration gracefulShutdown,
            Duration forcedShutdown,boolean dynamicWorkers) {
        public Config(int workerCount,int readyCapacity,Duration solveDeadline,Duration gracefulShutdown,Duration forcedShutdown) {
            this(workerCount,readyCapacity,solveDeadline,gracefulShutdown,forcedShutdown,false);
        }
        public Config {
            if (workerCount < 1 || readyCapacity < 1) {
                throw new IllegalArgumentException("Worker and ready capacities must be positive");
            }
            Objects.requireNonNull(solveDeadline, "solveDeadline");
            Objects.requireNonNull(gracefulShutdown, "gracefulShutdown");
            Objects.requireNonNull(forcedShutdown, "forcedShutdown");
            if (solveDeadline.isZero()
                    || solveDeadline.isNegative()
                    || gracefulShutdown.isNegative()
                    || forcedShutdown.isNegative()) {
                throw new IllegalArgumentException("Invalid solve-service duration");
            }
        }
    }

    public record StopResult(
            boolean shutdownPerformed,
            BoundedCpuSolveService.ShutdownReport shutdownReport,
            List<ProcessSolveCompletion> completions,
            List<ProcessSolveRequest> abandonedRequests) {
        public StopResult {
            completions = List.copyOf(Objects.requireNonNull(completions, "completions"));
            abandonedRequests = List.copyOf(Objects.requireNonNull(abandonedRequests, "abandonedRequests"));
        }

        private static StopResult missing() {
            return new StopResult(
                    false,
                    new BoundedCpuSolveService.ShutdownReport(true, false, false, 0),
                    List.of(),
                    List.of());
        }

        private static StopResult alreadyStopped(BoundedCpuSolveService.ShutdownReport shutdownReport) {
            return new StopResult(false, shutdownReport, List.of(), List.of());
        }
    }

    private record RequestContext(
            ProcessSolveRequest request,
            Diagnostics admissionDiagnostics,
            BoundedCpuSolveService.JobStamp<SolveTarget> stamp) {}

    private static final class ServerState {
        private final BoundedCpuSolveService<SolveTarget, ProcessSolveCommand, ProcessSolveResult> service;
        private final Config config;
        private final CompletionWakeup wakeup;
        private final WorkerAllocation.Demand allocator;
        private int fluidDemand;
        private Runnable readinessPump=()->{};
        private int lastDrainTick=Integer.MIN_VALUE,drainedThisTick,continuationTick=Integer.MIN_VALUE;
        private final Map<Long, RequestContext> requestsBySequence = new LinkedHashMap<>();
        private StopResult stopResult;

        private ServerState(
                BoundedCpuSolveService<SolveTarget, ProcessSolveCommand, ProcessSolveResult> service,
                Config config,CompletionWakeup wakeup) {
            this.service = service;
            this.config = config;
            this.wakeup=wakeup;
            this.allocator=config.dynamicWorkers()?new WorkerAllocation.Demand(config.workerCount()):null;
        }
    }

}

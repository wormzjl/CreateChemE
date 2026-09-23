package com.wormzjl.createcheme;

import com.mojang.logging.LogUtils;
import com.wormzjl.createcheme.network.ColumnV3Network;
import com.wormzjl.createcheme.network.ProcessSolveCoordinator;
import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.registry.ModItems;
import com.wormzjl.createcheme.registry.ModMenus;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.WorkerAllocation;
import com.wormzjl.createcheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.createcheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.createcheme.science.column.v3.V3InitializationOptions;
import com.wormzjl.createcheme.science.column.v3.V3NeuralModels;
import com.wormzjl.createcheme.science.column.v3.V3NeuralInitializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.time.Duration;

@Mod(CreateChemE.MOD_ID)
public final class CreateChemE {
    public static final String MOD_ID = "createcheme";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final ModConfigSpec CONFIG_SPEC;
    private static final ModConfigSpec.BooleanValue CALCULATION_LOGGING;
    private static final ModConfigSpec.IntValue SOLVER_WORKERS;
    private static final ModConfigSpec.IntValue SOLVER_AUTOMATIC_WORKER_LIMIT;
    private static final ModConfigSpec.IntValue SOLVER_READY_CAPACITY;
    private static final ModConfigSpec.IntValue SOLVER_DEADLINE_MILLISECONDS;
    private static final ModConfigSpec.IntValue SOLVER_GRACEFUL_SHUTDOWN_MILLISECONDS;
    private static final ModConfigSpec.IntValue SOLVER_FORCED_SHUTDOWN_MILLISECONDS;
    private static final ModConfigSpec.IntValue FLUID_INITIAL_INTERVAL_SECONDS,FLUID_WALL_BUDGET_MILLISECONDS;
    private static final ModConfigSpec.DoubleValue SOLID_IMMOBILE_VISCOSITY,SOLID_TRACE_FRACTION,SOLID_SUSPENSION_MULTIPLIER,FILTER_CAPACITY,FILTER_RESISTANCE;
    private static final ModConfigSpec.DoubleValue FLUID_LIQUID_COMPRESSIBILITY,FLUID_INITIAL_VOLUME,FLUID_INITIAL_TEMPERATURE,FLUID_INITIAL_PRESSURE,FLUID_MAXIMUM_VELOCITY,FLUID_TRACE_CUTOFF;
    private static final ModConfigSpec.BooleanValue FLUID_DEBUG_CHAT,FLUID_ADAPTIVE_CADENCE;
    private static final ModConfigSpec.BooleanValue FLUID_REST_DETECTION;
    private static final ModConfigSpec.DoubleValue FLUID_CERTIFICATE_TOLERANCE,FLUID_CERTIFICATE_BUDGET;
    private static final ModConfigSpec.IntValue FLUID_CERTIFICATE_MAXIMUM_INTERVALS,FLUID_REST_CONFIRM_INTERVALS,FLUID_REST_RECHECK_SECONDS;
    private static final ModConfigSpec.DoubleValue COLUMN_V3_STAGE_TRACE_CUTOFF_MOL_PERCENT;
    private static final ModConfigSpec.DoubleValue COLUMN_V3_CONVERGENCE_CLOSURE_PERCENT;
    private static final ModConfigSpec.DoubleValue COLUMN_V3_LIQUID_SUPPLY_SCREEN_RATIO;
    private static final ModConfigSpec.EnumValue<V3InitializationOptions.Mode> COLUMN_V3_INITIALIZER_MODE;
    private static final ModConfigSpec.EnumValue<V3InitializationOptions.WetStart> COLUMN_V3_WET_START;
    private static final ModConfigSpec.IntValue COLUMN_V3_LNN_ITERATIONS;
    private static final ModConfigSpec.IntValue COLUMN_V3_LNN_MILLISECONDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CALCULATION_LOGGING = builder
                .comment("Log process-equipment calculation inputs, results, diagnostics, timing, and outputs.")
                .define("enableCalculationLogging", true);
        builder.push("solver");
        SOLVER_WORKERS = builder
                .comment("Shared CPU solve workers. 0 allocates by demand within automaticWorkerLimit and available processors - 2; 1-32 sets a fixed limit.",
                        "Applied when the server starts. Performance qualification uses an explicit value of 2.")
                .defineInRange("workers", 0, 0, WorkerAllocation.MAXIMUM_WORKERS);
        SOLVER_AUTOMATIC_WORKER_LIMIT=builder.comment("Maximum shared CPU workers in automatic mode; idle capacity scales down with demand.")
                .defineInRange("automaticWorkerLimit",WorkerAllocation.DEFAULT_AUTOMATIC_LIMIT,1,WorkerAllocation.MAXIMUM_WORKERS);
        SOLVER_READY_CAPACITY = builder
                .comment("Maximum admitted process solves waiting for a worker.")
                .defineInRange("readyQueueCapacity", 8, 1, 32);
        SOLVER_DEADLINE_MILLISECONDS = builder
                .comment("Cooperative wall-clock deadline for one process solve, in milliseconds; iterative kernels poll it.")
                .defineInRange("deadlineMilliseconds", 45_000, 100, 60_000);
        SOLVER_GRACEFUL_SHUTDOWN_MILLISECONDS = builder
                .comment("Server-stop grace period before interrupting solver workers, in milliseconds.")
                .defineInRange("gracefulShutdownMilliseconds", 1_000, 0, 10_000);
        SOLVER_FORCED_SHUTDOWN_MILLISECONDS = builder
                .comment("Bounded wait after solver-worker interruption, in milliseconds.")
                .defineInRange("forcedShutdownMilliseconds", 1_000, 0, 10_000);
        builder.pop();
        builder.push("fluid");
        SOLID_IMMOBILE_VISCOSITY=builder.comment("Liquid phases above this dynamic viscosity (Pa.s) cannot be transported. Applied at server start.").defineInRange("solidViscosityPascalSeconds",100.0,1e-6,1e12);
        SOLID_TRACE_FRACTION=builder.comment("Ignore smaller particle volume fractions for blockage only; all mass and size records remain conserved. 0 disables masking.").defineInRange("solidTraceVolumeFraction",1e-8,0,0.01);
        SOLID_SUSPENSION_MULTIPLIER=builder.comment("Gameplay multiplier applied to particle settling speed for minimum liquid transport velocity.").defineInRange("solidSuspensionMultiplier",10.0,.01,10000.0);
        FILTER_CAPACITY=builder.comment("Retained solid volume capacity of an in-line filter (m3). Existing captured material is preserved when this changes.").defineInRange("filterSolidCapacityCubicMetres",.01,1e-9,1000.0);
        FILTER_RESISTANCE=builder.comment("Clean in-line filter resistance at 1 mPa.s, in Pa.s/m3.").defineInRange("filterCleanResistance",1e6,1.0,1e15);
        FLUID_INITIAL_INTERVAL_SECONDS=builder.comment("Starting hydraulic cadence for newly created islands. Existing saved clocks retain their cadence; adaptation uses 1-20 s.").defineInRange("initialIntervalSeconds",5,1,20);
        FLUID_ADAPTIVE_CADENCE=builder.comment("Adapt island cadence to measured CPU load. Disable for reproducible fixed-cadence qualification; saved simulation debt is retained.").define("adaptiveCadence",true);
        FLUID_WALL_BUDGET_MILLISECONDS=builder.comment("Hard worker budget per island interval; 75% is reserved for the full solve. Applied on server start.").defineInRange("wallBudgetMilliseconds",2000,100,60000);
        FLUID_LIQUID_COMPRESSIBILITY=builder.comment("One liquid compressibility for every liquid, in 1/Pa, for a new fluid world. Existing saves retain their recorded model until explicit migration.").defineInRange("liquidCompressibility",1e-9,1e-12,5e-7);
        FLUID_MAXIMUM_VELOCITY=builder.comment("Maximum bulk pipe velocity in m/s, also limited by the current fluid's acoustic bound. Caps mass transfer inside the coupled equations; does not set temperature. Captured on server start.").defineInRange("maximumVelocityMetresPerSecond",100.0,.01,100000.0);
        FLUID_INITIAL_VOLUME=builder.comment("Volume of newly placed reservoirs in cubic metres. Applied on server start.").defineInRange("reservoirVolumeCubicMetres",1.0,.001,1000.0);
        FLUID_INITIAL_TEMPERATURE=builder.comment("One-time nitrogen charge temperature in kelvin for newly placed reservoirs.").defineInRange("initialNitrogenTemperatureKelvin",298.15,273.16,600.0);
        FLUID_INITIAL_PRESSURE=builder.comment("One-time nitrogen charge absolute pressure in pascals for newly placed reservoirs.").defineInRange("initialNitrogenPressurePascal",101325.0,100.0,2000000.0);
        FLUID_TRACE_CUTOFF=builder
                .comment("Trace cutoff as a mole fraction for the hydraulic network's Newton unknowns, not a feed filter.",
                        "A component whose seed mole fraction is below this in one hydrocarbon phase and at or above it",
                        "in the other keeps a single unknown - its component total, in the phase it is actually in - and",
                        "loses its equilibrium row; the omitted phase is held at exactly zero, so every component total,",
                        "transport split and conservation audit stays exact. The converged fugacity coefficients put a",
                        "component back into both phases as soon as its equilibrium fraction reaches ten times this value.",
                        "0 is the exact off switch: the identical numerical path, not a very small cutoff.",
                        "Applied on server start.")
                .defineInRange("fluidTraceCutoffMoleFraction",FluidThermodynamics.DEFAULT_TRACE_CUTOFF_MOLE_FRACTION,
                        0,FluidThermodynamics.MAX_TRACE_CUTOFF_MOLE_FRACTION);
        FLUID_DEBUG_CHAT=builder.comment("Report held fluid intervals in chat, at most once per second; full details remain in the server log.").define("debugChat",false);
        FLUID_REST_DETECTION=builder.comment("Certify islands whose interval map is exactly the identity (REST) or stationary (STEADY) and advance them without solving:",
                "REST by identity, STEADY by replaying the last solved interval. Off disables certificates; islands solve every interval. Captured on server start.")
                .define("restDetection",true);
        FLUID_CERTIFICATE_TOLERANCE=builder.comment("Largest change between two consecutive intervals that still counts as stationary: per node and component,",
                "relative to that inventory; per node energy, relative to its thermal scale; per pipe, relative to the island's largest flow;",
                "and the largest per-interval drift of a node's temperature or pressure. 0 admits only intervals that repeat exactly. Captured on server start.")
                .defineInRange("certificateStationaryTolerance",1e-9,0,1e-6);
        FLUID_CERTIFICATE_BUDGET=builder.comment("Largest relative inventory change a STEADY certificate may extrapolate before one interval is solved again. Captured on server start.")
                .defineInRange("certificateInventoryBudget",1e-6,1e-12,1e-3);
        FLUID_CERTIFICATE_MAXIMUM_INTERVALS=builder.comment("Most intervals one STEADY certificate may replay; 17280 is one game day at a 5 s cadence. Captured on server start.")
                .defineInRange("certificateMaximumIntervals",17_280,1,1_000_000);
        FLUID_REST_CONFIRM_INTERVALS=builder.comment("Consecutive stationary solved intervals before an island certifies, and again after a property hold. Captured on server start.")
                .defineInRange("restConfirmIntervals",2,1,10);
        FLUID_REST_RECHECK_SECONDS=builder.comment("Solve an exact-zero REST island again after this many simulated seconds; 0 means never. Captured on server start.")
                .defineInRange("restRecheckSeconds",0,0,86_400);
        builder.pop();
        builder.push("columnV3");
        COLUMN_V3_INITIALIZER_MODE = builder
                .comment("LNN_FIRST tries the bundled learned seed, then the current initializer as backup.",
                        "LNN_ONLY never invokes current initialization. CURRENT_ONLY bypasses the model.",
                        "One learned model ships: the qualified anchor-augmented Transformer F-20260911-s4160,",
                        "with the phase-level decoder floor and the progress-based correction budget it was",
                        "qualified with. There is no model selection; the earlier experimental families were",
                        "retired to the offline tools and an initializerModel key in an older config is ignored.",
                        "All routes retain physical acceptance checks. Captured at request admission.")
                .defineEnum("initializerMode", V3InitializationOptions.Mode.LNN_FIRST);
        COLUMN_V3_WET_START = builder
                .comment("Learned seeds only: AUTO and PREDICTED_WET retain the model's validated wet mask.",
                        "DRY_START clears the initial wet set; subsequent water physics and wet correction remain enabled.")
                .defineEnum("lnnWetStartMode", V3InitializationOptions.WetStart.AUTO);
        COLUMN_V3_LNN_ITERATIONS = builder
                .comment("Maximum Newton iterations per learned correction pass; all passes also share the LNN time budget.")
                .defineInRange("lnnMaxCorrectionIterations", 16, 1, 128);
        COLUMN_V3_LNN_MILLISECONDS = builder
                .comment("Shared inference/correction allowance in milliseconds; never extends the overall solve deadline.")
                .defineInRange("lnnBudgetMilliseconds", 2_000, 1, 60_000);
        COLUMN_V3_STAGE_TRACE_CUTOFF_MOL_PERCENT = builder
                .comment("V3 stage-level trace cutoff in mol% (not a feed filter).",
                        "Components below this cutoff in every testable phase at a stage may be structurally removed there.",
                        "Feed trays are retained; removed inflows form an audited molar defect. Failed truncated solves retry untruncated.",
                        "0 is the exact off switch. Default remains 0 pending accuracy/performance evaluation.",
                        "Captured at admission; config reloads do not change in-flight solves.")
                .defineInRange("stageTraceCutoffMolPercent", 0.0, 0.0, 1.0);
        COLUMN_V3_CONVERGENCE_CLOSURE_PERCENT = builder
                .comment("V3 convergence closure in percent: how tightly every residual row must close.",
                        "A component balance closes to this fraction of its own tray throughput, equilibrium to this",
                        "difference in log composition, and each tray's energy to this fraction of a reference flow.",
                        "The acceptance audit's equilibrium, condenser-split and energy-closure limits move with it.",
                        "0 is the exact off switch and keeps the frozen 1e-8 closure; 0.1 is the loosest admitted.",
                        "A nonzero value changes the accepted state, so it is part of the result digest and label.",
                        "Captured at admission; config reloads do not change in-flight solves.")
                .defineInRange("columnV3ConvergenceClosurePercent", 0.0, 0.0, 0.1);
        COLUMN_V3_LIQUID_SUPPLY_SCREEN_RATIO = builder
                .comment("Request-only liquid-supply screen: reject a specification whose authored side draws withdraw",
                        "at least this fraction of the liquid that reflux, feed and authored pumparound condensation",
                        "can deliver to their trays. No flash and no thermodynamics; a rejected request fails in",
                        "microseconds with INFEASIBLE_SPECIFICATION instead of seconds of Newton work.",
                        "0.30 is calibrated, not proved: over 474 independently generated solvable requests the worst",
                        "reached 0.2176, and 0.30 typed 16 of 405 and 11 of 252 never-solved requests with no false",
                        "positive. Do not lower it below 0.2176 without re-measuring. A ratio at or above 1 is",
                        "physically necessary and is always rejected, whatever this value is.",
                        "0 disables the calibrated tier and leaves only that necessary rejection, which is what a",
                        "research probe on the draw-wall specifications wants.",
                        "Captured at admission; config reloads do not change in-flight solves.")
                .defineInRange("columnV3LiquidSupplyScreenRatio",
                        V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, 0.0, 1.0);
        builder.pop();
        CONFIG_SPEC = builder.build();
    }

    public CreateChemE(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenus.register(modEventBus);
        modEventBus.addListener(ColumnV3Network::register);
        modEventBus.addListener(com.wormzjl.createcheme.network.FluidNetwork::register);
        modEventBus.addListener(CreateChemE::addCreativeTabItem);
        modContainer.registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC, "createcheme-common.toml");

        // ProcessSolveServices is thread-confined to the logical server. Pin every lifecycle edge to the GAME bus.
        IEventBus gameEventBus = NeoForge.EVENT_BUS;
        gameEventBus.addListener(com.wormzjl.createcheme.material.MaterialReloadListener::register);
        gameEventBus.addListener(CreateChemE::onServerStarting);
        gameEventBus.addListener(CreateChemE::onServerTickPost);
        gameEventBus.addListener(CreateChemE::onServerStopping);
        gameEventBus.addListener(CreateChemE::onServerStopped);
        gameEventBus.addListener(CreateChemE::onFluidChunkLoaded);
        // Opt-in in-game measurement log (WP5 rig); registers nothing unless its system property is set.
        com.wormzjl.createcheme.runtime.fluid.FluidInGameDiagnostics.register(gameEventBus);
        if(com.wormzjl.createcheme.runtime.fluid.FluidInGameDiagnostics.enabled()&&net.neoforged.fml.loading.FMLEnvironment.dist.isClient())
            com.wormzjl.createcheme.runtime.fluid.FluidInGameClientDiagnostics.register(gameEventBus);
    }
    private static void onFluidChunkLoaded(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            var server=level.getServer();var dimension=level.dimension();long chunk=event.getChunk().getPos().toLong();
            server.execute(()->com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority.find(server).ifPresent(world->world.loadedChunk(dimension,chunk)));
        }
    }

    public static boolean calculationLoggingEnabled() {
        return CALCULATION_LOGGING.getAsBoolean();
    }
    public record FluidOptions(int initialCadenceTicks,long wallBudgetNanos,double compressibility,double volume,double temperature,double pressure,boolean debugChat,boolean adaptiveCadence,double maximumVelocity,double traceCutoffMoleFraction,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings solids,com.wormzjl.createcheme.runtime.fluid.CertificatePolicy certificates) {
        public FluidOptions(int initialCadenceTicks,long wallBudgetNanos,double compressibility,double volume,double temperature,double pressure,boolean debugChat,boolean adaptiveCadence,double maximumVelocity,double traceCutoffMoleFraction){this(initialCadenceTicks,wallBudgetNanos,compressibility,volume,temperature,pressure,debugChat,adaptiveCadence,maximumVelocity,traceCutoffMoleFraction,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings.defaults());}
        public FluidOptions(int initialCadenceTicks,long wallBudgetNanos,double compressibility,double volume,double temperature,double pressure,boolean debugChat,boolean adaptiveCadence,double maximumVelocity,double traceCutoffMoleFraction,com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings solids){this(initialCadenceTicks,wallBudgetNanos,compressibility,volume,temperature,pressure,debugChat,adaptiveCadence,maximumVelocity,traceCutoffMoleFraction,solids,com.wormzjl.createcheme.runtime.fluid.CertificatePolicy.defaults());}
        public FluidOptions {java.util.Objects.requireNonNull(solids);java.util.Objects.requireNonNull(certificates);}
    }
    public static FluidOptions fluidOptions() {
        return new FluidOptions(20*FLUID_INITIAL_INTERVAL_SECONDS.getAsInt(),1_000_000L*FLUID_WALL_BUDGET_MILLISECONDS.getAsInt(),FLUID_LIQUID_COMPRESSIBILITY.get(),FLUID_INITIAL_VOLUME.get(),FLUID_INITIAL_TEMPERATURE.get(),FLUID_INITIAL_PRESSURE.get(),FLUID_DEBUG_CHAT.getAsBoolean(),FLUID_ADAPTIVE_CADENCE.getAsBoolean(),FLUID_MAXIMUM_VELOCITY.get(),FLUID_TRACE_CUTOFF.get(),new com.wormzjl.createcheme.science.fluid.transport.SolidTransportSettings(SOLID_IMMOBILE_VISCOSITY.get(),SOLID_TRACE_FRACTION.get(),SOLID_SUSPENSION_MULTIPLIER.get(),FILTER_CAPACITY.get(),FILTER_RESISTANCE.get()),
                new com.wormzjl.createcheme.runtime.fluid.CertificatePolicy(FLUID_REST_DETECTION.getAsBoolean(),FLUID_CERTIFICATE_TOLERANCE.get(),FLUID_CERTIFICATE_BUDGET.get(),FLUID_CERTIFICATE_MAXIMUM_INTERVALS.getAsInt(),FLUID_REST_CONFIRM_INTERVALS.getAsInt(),FLUID_REST_RECHECK_SECONDS.getAsInt()));
    }

    /**
     * Read only on the logical server's admission thread; workers receive the immutable result.
     *
     * <p>The correction rule is the qualified one and is not configurable: it is part of what the
     * bundled model was measured with, so a config that could separate them could not be qualified.</p>
     */
    public static V3InitializationOptions columnV3InitializationOptions() {
        return new V3InitializationOptions(COLUMN_V3_INITIALIZER_MODE.get(), COLUMN_V3_WET_START.get(),
                COLUMN_V3_LNN_ITERATIONS.get(), COLUMN_V3_LNN_MILLISECONDS.get(),
                V3InitializationOptions.Correction.PROGRESS);
    }

    /** Resolve the safely published immutable bundled model on the admission thread. */
    public static V3NeuralInitializer columnV3NeuralModel() {
        return V3NeuralModels.bundled();
    }

    /** Read on the server thread when admitting a V3 request, never from its worker. */
    public static double columnV3StageTraceCutoffMolPercent() {
        return COLUMN_V3_STAGE_TRACE_CUTOFF_MOL_PERCENT.get();
    }

    /** Read on the server thread when admitting a V3 request, never from its worker. */
    public static double columnV3ConvergenceClosurePercent() {
        return COLUMN_V3_CONVERGENCE_CLOSURE_PERCENT.get();
    }

    /** Read on the server thread when admitting a V3 request, never from its worker. */
    public static double columnV3LiquidSupplyScreenRatio() {
        return COLUMN_V3_LIQUID_SUPPLY_SCREEN_RATIO.get();
    }

    private static void addCreativeTabItem(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.COLUMN_CALCULATOR_V3.get());
            event.accept(ModItems.FLUID_RESERVOIR.get());event.accept(ModItems.FLUID_PIPE.get());event.accept(ModItems.FLUID_PUMP.get());event.accept(ModItems.PRESSURE_CONTROL_VALVE.get());event.accept(ModItems.FLUID_GENERATOR.get());event.accept(ModItems.FLUID_VOID.get());event.accept(ModItems.INLINE_FILTER.get());event.accept(ModItems.FLUID_DEBUGGER.get());
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        try {
            if (columnV3InitializationOptions().mode() != V3InitializationOptions.Mode.CURRENT_ONLY)
                columnV3NeuralModel(); // Parse the selected immutable artifact before admitting normal work.
            ProcessSolveServices.ServerStarted started =
                    ProcessSolveServices.startServer(server, solveServiceConfig());
            com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority.start(server);
            if (calculationLoggingEnabled()) {
                LOGGER.info(
                        "process_solver lifecycle=STARTED epoch={} workers={} ready_capacity={} "
                                + "deadline_ms={} graceful_shutdown_ms={} forced_shutdown_ms={}",
                        started.serverEpoch(),
                        started.workerCount(),
                        started.readyCapacity(),
                        started.solveDeadlineMilliseconds(),
                        started.gracefulShutdownMilliseconds(),
                        started.forcedShutdownMilliseconds());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("process_solver lifecycle=START_FAILED", exception);
            throw exception;
        }
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        com.wormzjl.createcheme.runtime.fluid.FluidRuntimeMeter.enter(event.getServer());
        try {
            var materialCatalog=com.wormzjl.createcheme.science.material.MaterialRuntime.active();
            if (lastMaterialCatalog != materialCatalog) {
                lastMaterialCatalog=materialCatalog;
                ColumnV3Network.refreshMaterialViewers(event.getServer());
            }
            ProcessSolveCoordinator.drainCompletedCalculations(event.getServer());
            com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority.tick(event.getServer());
        } catch (RuntimeException exception) {
            LOGGER.error("process_solver lifecycle=DRAIN_FAILED", exception);
            throw exception;
        } finally {
            com.wormzjl.createcheme.runtime.fluid.FluidRuntimeMeter.exit(event.getServer());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        try {
            com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority.stop(event.getServer());
            ProcessSolveCoordinator.stopCalculations(event.getServer());
        } catch (RuntimeException exception) {
            LOGGER.error("process_solver lifecycle=STOP_FAILED", exception);
            throw exception;
        }
    }

    private static com.wormzjl.createcheme.science.material.MaterialCatalog lastMaterialCatalog;

    private static void onServerStopped(ServerStoppedEvent event) {
        try {
            // ServerStopped can still fire after an abnormal lifecycle path that skipped ServerStopping.
            com.wormzjl.createcheme.runtime.fluid.FluidWorldAuthority.forget(event.getServer());
            com.wormzjl.createcheme.runtime.fluid.FluidRuntimeMeter.forget(event.getServer());
            ProcessSolveCoordinator.stopCalculations(event.getServer());
            int remainingContexts = ProcessSolveServices.removeStoppedServer(event.getServer());
            com.wormzjl.createcheme.science.material.MaterialRuntime.reset();
            if (remainingContexts != 0) {
                LOGGER.error(
                        "process_solver lifecycle=STOPPED unexpected_remaining_contexts={}",
                        remainingContexts);
            } else if (calculationLoggingEnabled()) {
                LOGGER.info("process_solver lifecycle=STOPPED");
            }
        } catch (RuntimeException exception) {
            LOGGER.error("process_solver lifecycle=REMOVE_FAILED", exception);
            throw exception;
        }
    }

    private static ProcessSolveServices.Config solveServiceConfig() {
        return new ProcessSolveServices.Config(
                WorkerAllocation.resolve(
                        SOLVER_WORKERS.getAsInt(), Runtime.getRuntime().availableProcessors(),SOLVER_AUTOMATIC_WORKER_LIMIT.getAsInt()),
                SOLVER_READY_CAPACITY.getAsInt(),
                Duration.ofMillis(SOLVER_DEADLINE_MILLISECONDS.getAsInt()),
                Duration.ofMillis(SOLVER_GRACEFUL_SHUTDOWN_MILLISECONDS.getAsInt()),
                Duration.ofMillis(SOLVER_FORCED_SHUTDOWN_MILLISECONDS.getAsInt()),SOLVER_WORKERS.getAsInt()==0);
    }

}

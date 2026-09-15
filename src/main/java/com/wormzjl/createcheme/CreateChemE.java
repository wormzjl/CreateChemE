package com.wormzjl.createcheme;

import com.mojang.logging.LogUtils;
import com.wormzjl.createcheme.network.ColumnV3Network;
import com.wormzjl.createcheme.network.ProcessSolveCoordinator;
import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.registry.ModItems;
import com.wormzjl.createcheme.registry.ModMenus;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.science.column.v3.V3ColumnCalculator;
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
    private static final ModConfigSpec.IntValue SOLVER_READY_CAPACITY;
    private static final ModConfigSpec.IntValue SOLVER_DEADLINE_MILLISECONDS;
    private static final ModConfigSpec.IntValue SOLVER_GRACEFUL_SHUTDOWN_MILLISECONDS;
    private static final ModConfigSpec.IntValue SOLVER_FORCED_SHUTDOWN_MILLISECONDS;
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
                .comment("Platform workers for CPU-bound process solves. Keep at 1 unless measurements justify 2.")
                .defineInRange("workers", 1, 1, 2);
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
        modEventBus.addListener(CreateChemE::addCreativeTabItem);
        modContainer.registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC, "createcheme-common.toml");

        // ProcessSolveServices is thread-confined to the logical server. Pin every lifecycle edge to the GAME bus.
        IEventBus gameEventBus = NeoForge.EVENT_BUS;
        gameEventBus.addListener(com.wormzjl.createcheme.material.MaterialReloadListener::register);
        gameEventBus.addListener(CreateChemE::onServerStarting);
        gameEventBus.addListener(CreateChemE::onServerTickPost);
        gameEventBus.addListener(CreateChemE::onServerStopping);
        gameEventBus.addListener(CreateChemE::onServerStopped);
    }

    public static boolean calculationLoggingEnabled() {
        return CALCULATION_LOGGING.getAsBoolean();
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
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        try {
            if (columnV3InitializationOptions().mode() != V3InitializationOptions.Mode.CURRENT_ONLY)
                columnV3NeuralModel(); // Parse the selected immutable artifact before admitting normal work.
            ProcessSolveServices.ServerStarted started =
                    ProcessSolveServices.startServer(server, solveServiceConfig());
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
        try {
            var materialCatalog=com.wormzjl.createcheme.science.material.MaterialRuntime.active();
            if (lastMaterialCatalog != materialCatalog) {
                lastMaterialCatalog=materialCatalog;
                ColumnV3Network.refreshMaterialViewers(event.getServer());
            }
            ProcessSolveCoordinator.drainCompletedCalculations(event.getServer());
        } catch (RuntimeException exception) {
            LOGGER.error("process_solver lifecycle=DRAIN_FAILED", exception);
            throw exception;
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        try {
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
                SOLVER_WORKERS.getAsInt(),
                SOLVER_READY_CAPACITY.getAsInt(),
                Duration.ofMillis(SOLVER_DEADLINE_MILLISECONDS.getAsInt()),
                Duration.ofMillis(SOLVER_GRACEFUL_SHUTDOWN_MILLISECONDS.getAsInt()),
                Duration.ofMillis(SOLVER_FORCED_SHUTDOWN_MILLISECONDS.getAsInt()));
    }

}

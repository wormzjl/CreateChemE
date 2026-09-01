package com.wormzjl.createcheme;

import com.mojang.logging.LogUtils;
import com.wormzjl.createcheme.network.ColumnNetwork;
import com.wormzjl.createcheme.network.ProcessSolveCoordinator;
import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.registry.ModBlocks;
import com.wormzjl.createcheme.registry.ModItems;
import com.wormzjl.createcheme.registry.ModMenus;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
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
        COLUMN_V3_STAGE_TRACE_CUTOFF_MOL_PERCENT = builder
                .comment("V3 stage-level trace cutoff in mol% (not a feed filter).",
                        "Components below this cutoff in every testable phase at a stage may be structurally removed there.",
                        "Feed trays are retained; removed inflows form an audited molar defect. Failed truncated solves retry untruncated.",
                        "0 is the exact off switch. Default remains 0 pending accuracy/performance evaluation.",
                        "Captured at admission; config reloads do not change in-flight solves.")
                .defineInRange("stageTraceCutoffMolPercent", 0.0, 0.0, 1.0);
        builder.pop();
        CONFIG_SPEC = builder.build();
    }

    public CreateChemE(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenus.register(modEventBus);
        modEventBus.addListener(ColumnNetwork::register);
        modEventBus.addListener(CreateChemE::addCreativeTabItem);
        modContainer.registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC, "createcheme-common.toml");

        // ProcessSolveServices is thread-confined to the logical server. Pin every lifecycle edge to the GAME bus.
        IEventBus gameEventBus = NeoForge.EVENT_BUS;
        gameEventBus.addListener(CreateChemE::onServerStarting);
        gameEventBus.addListener(CreateChemE::onServerTickPost);
        gameEventBus.addListener(CreateChemE::onServerStopping);
        gameEventBus.addListener(CreateChemE::onServerStopped);
    }

    public static boolean calculationLoggingEnabled() {
        return CALCULATION_LOGGING.getAsBoolean();
    }

    /** Read on the server thread when admitting a V3 request, never from its worker. */
    public static double columnV3StageTraceCutoffMolPercent() {
        return COLUMN_V3_STAGE_TRACE_CUTOFF_MOL_PERCENT.get();
    }

    private static void addCreativeTabItem(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.COLUMN_CALCULATOR.get());
            event.accept(ModItems.COLUMN_CALCULATOR_V3.get());
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        try {
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

    private static void onServerStopped(ServerStoppedEvent event) {
        try {
            // ServerStopped can still fire after an abnormal lifecycle path that skipped ServerStopping.
            ProcessSolveCoordinator.stopCalculations(event.getServer());
            int remainingContexts = ProcessSolveServices.removeStoppedServer(event.getServer());
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

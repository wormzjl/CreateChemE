package com.wormzjl.createcheme.network;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.ColumnCompletion;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.ColumnRequest;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.NextColumnCompletion;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.NextColumnRequest;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.ProcessSolveCompletion;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.ProcessSolveRequest;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.V3ColumnCompletion;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.V3ColumnRequest;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Objects;

/**
 * The sole main-thread completion router for the shared process-solve service.
 *
 * <p>Legacy and next packet families retain their independent codecs and commit handlers, but neither family may
 * drain the shared completion queue on its own. This coordinator is called once per logical-server lifecycle edge
 * by {@link CreateChemE}.</p>
 */
public final class ProcessSolveCoordinator {
    private ProcessSolveCoordinator() {}

    /** Drains and routes at most the configured bounded number of terminal worker messages. */
    public static void drainCompletedCalculations(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        route(server, ProcessSolveServices.drainCompletions(
                server, ProcessSolveServices.MAXIMUM_COMPLETIONS_PER_TICK));
    }

    /** Stops the one owned service and routes every terminal/abandoned job family before lifecycle teardown. */
    public static void stopCalculations(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        ProcessSolveServices.StopResult stop = ProcessSolveServices.stopServer(server);
        route(server, stop.completions());
        for (ProcessSolveRequest abandoned : stop.abandonedRequests()) {
            routeAbandoned(server, abandoned);
        }
        if (!stop.shutdownPerformed()) {
            return;
        }
        var report = stop.shutdownReport();
        if (!report.terminated() || report.callerInterrupted() || !stop.abandonedRequests().isEmpty()) {
            CreateChemE.LOGGER.error(
                    "process_solver lifecycle=STOPPED_WITH_FAULT terminated={} forced={} interrupted={} "
                            + "never_started={} completions={} abandoned={}",
                    report.terminated(),
                    report.forced(),
                    report.callerInterrupted(),
                    report.neverStartedExecutorTasks(),
                    stop.completions().size(),
                    stop.abandonedRequests().size());
        } else if (CreateChemE.calculationLoggingEnabled()) {
            CreateChemE.LOGGER.info(
                    "process_solver lifecycle=STOPPING terminated={} forced={} never_started={} "
                            + "terminal_completions={}",
                    report.terminated(),
                    report.forced(),
                    report.neverStartedExecutorTasks(),
                    stop.completions().size());
        }
    }

    private static void route(MinecraftServer server, List<ProcessSolveCompletion> completions) {
        for (ProcessSolveCompletion completion : completions) {
            if (completion instanceof ColumnCompletion legacy) {
                ColumnNetwork.handleRoutedCompletion(server, legacy);
            } else if (completion instanceof NextColumnCompletion next) {
                ColumnNextNetwork.handleRoutedCompletion(server, next);
            } else if (completion instanceof V3ColumnCompletion v3) {
                ColumnCalculatorV3BlockEntity.handleRoutedCompletion(server, v3);
            } else {
                throw new IllegalStateException("Unknown process-solve completion family");
            }
        }
    }

    private static void routeAbandoned(MinecraftServer server, ProcessSolveRequest request) {
        if (request instanceof ColumnRequest legacy) {
            ColumnNetwork.handleRoutedAbandoned(server, legacy);
        } else if (request instanceof NextColumnRequest next) {
            ColumnNextNetwork.handleRoutedAbandoned(server, next);
        } else if (request instanceof V3ColumnRequest v3) {
            ColumnCalculatorV3BlockEntity.handleRoutedAbandoned(server, v3);
        } else {
            throw new IllegalStateException("Unknown process-solve request family");
        }
    }
}

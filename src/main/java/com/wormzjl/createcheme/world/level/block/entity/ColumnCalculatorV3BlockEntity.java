package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.CreateChemE;
import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.runtime.ProcessSolveServices;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.V3ColumnCompletion;
import com.wormzjl.createcheme.runtime.ProcessSolveServices.V3ColumnRequest;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.createcheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorV3Menu;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread-confined V3 pilot state.
 *
 * <p>The worker sees only the immutable {@link V3ColumnInput}; this block entity is located again on the server
 * thread for each completion and commits only a matching {@link V3Operation}.</p>
 */
public final class ColumnCalculatorV3BlockEntity extends BlockEntity implements MenuProvider {
    private static final String PILOT_PACKAGE = "createcheme:cdu17_tjl_acs2018";

    private V3Status status = V3Status.IDLE;
    private long inputRevision;
    private String detail = "Pilot ready";
    private V3Operation activeOperation;
    private V3ColumnOutcome lastOutcome;

    public ColumnCalculatorV3BlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COLUMN_CALCULATOR_V3.get(), pos, state);
    }

    /** Starts the registered binary V3 pilot if this block currently has no active operation. */
    public boolean startPilot() {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        if (CreateChemE.columnV3Rollout() == CreateChemE.V3Rollout.DISABLED) {
            status = V3Status.FAILED;
            detail = "V3 rollout is disabled";
            setChanged();
            return false;
        }
        if (activeOperation != null) return false;
        try {
            long nextRevision = Math.incrementExact(inputRevision);
            V3Operation operation = new V3Operation(ProcessSolveServices.nextRequestId(), nextRevision, pilotInput());
            activeOperation = operation;
            inputRevision = nextRevision;
            status = V3Status.CALCULATING;
            detail = "Calculating registered PR binary pilot";
            setChanged();
            ProcessSolveServices.V3ColumnRequest request = new V3ColumnRequest(operation.operationId(),
                    new ProcessSolveServices.ColumnTarget(serverLevel.dimension(), worldPosition), operation, System.nanoTime());
            ProcessSolveServices.AdmissionResult admission = ProcessSolveServices.submitV3Column(serverLevel.getServer(), request);
            if (!admission.accepted()) {
                activeOperation = null;
                status = V3Status.FAILED;
                detail = "Admission rejected: " + admission.admission();
                setChanged();
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            activeOperation = null;
            status = V3Status.FAILED;
            detail = bounded(failure.getMessage());
            setChanged();
            return false;
        }
    }

    public int statusCode() {
        return status.ordinal();
    }

    public String detail() {
        return detail;
    }

    /** Routes a worker completion through server-level and block-position lookup; no worker retains a block entity. */
    public static void handleRoutedCompletion(MinecraftServer server, V3ColumnCompletion completion) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(completion, "completion");
        ServerLevel level = server.getLevel(completion.request().target().dimension());
        if (level != null && level.getBlockEntity(completion.request().target().blockPos()) instanceof ColumnCalculatorV3BlockEntity calculator) {
            calculator.complete(completion);
        }
    }

    /** Stopped-server requests are never committed; a still-loaded matching block becomes dirty/failed. */
    public static void handleRoutedAbandoned(MinecraftServer server, V3ColumnRequest request) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(request, "request");
        ServerLevel level = server.getLevel(request.target().dimension());
        if (level != null && level.getBlockEntity(request.target().blockPos()) instanceof ColumnCalculatorV3BlockEntity calculator
                && request.operation().equals(calculator.activeOperation)) {
            calculator.activeOperation = null;
            calculator.status = V3Status.FAILED;
            calculator.detail = "V3 operation stopped with the server";
            calculator.setChanged();
        }
    }

    private void complete(V3ColumnCompletion routed) {
        if (!routed.request().operation().equals(activeOperation)) return;
        activeOperation = null;
        V3ColumnOutcome outcome = routed.completion().result().orElse(null);
        if (outcome instanceof V3ColumnOutcome.Success success) {
            lastOutcome = success;
            status = V3Status.SUCCESS;
            detail = "Accepted residual " + success.diagnostics().maximumScaledResidual();
        } else if (outcome instanceof V3ColumnOutcome.Failure failure) {
            lastOutcome = failure;
            status = V3Status.FAILED;
            detail = bounded(failure.summary());
        } else {
            status = V3Status.FAILED;
            detail = bounded(routed.completion().detail());
        }
        setChanged();
    }

    private static V3ColumnInput pilotInput() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PILOT_PACKAGE);
        double[] feedFlows = new double[thermo.componentBasis().componentCount()];
        feedFlows[6] = 50.0;
        feedFlows[13] = 50.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                thermo.componentBasis(), feedFlows, 550.0, 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "V3 worker did not provide a detail";
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.createcheme.column_calculator_v3");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (level == null) return null;
        return new ColumnCalculatorV3Menu(
                containerId, inventory, ContainerLevelAccess.create(level, worldPosition), worldPosition);
    }

    /** Immutable operation identity retained only while its worker completion is outstanding. */
    public record V3Operation(long operationId, long inputRevision, V3ColumnInput input) {
        public V3Operation {
            if (operationId <= 0L || inputRevision <= 0L) {
                throw new IllegalArgumentException("V3 operation identity must be positive");
            }
            input = Objects.requireNonNull(input, "input");
        }
    }

    public enum V3Status {
        IDLE,
        CALCULATING,
        SUCCESS,
        FAILED
    }
}

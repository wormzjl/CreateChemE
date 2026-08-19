package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.registry.ModBlockEntities;
import com.wormzjl.createcheme.world.inventory.ColumnCalculatorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Server-owned shell state for the first calculator block.
 *
 * <p>The fields intentionally contain no thermodynamic types. The scientific input/result records can replace the
 * placeholder summary without making the block, menu, or persistence layer depend on solver internals.</p>
 */
public final class ColumnCalculatorBlockEntity extends BlockEntity implements MenuProvider {
    private static final String TAG_STATUS = "Status";
    private static final String TAG_INPUT_REVISION = "InputRevision";
    private static final String TAG_RESULT_REVISION = "ResultRevision";
    private static final String TAG_RESULT_SUMMARY = "ResultSummary";
    private static final int MAX_SUMMARY_LENGTH = 256;
    private static final long MINIMUM_REQUEST_INTERVAL_TICKS = 5L;

    private CalculatorStatus status = CalculatorStatus.IDLE;
    private long inputRevision;
    private long resultRevision = -1L;
    private String resultSummary = "";
    private long nextAllowedRequestGameTime;

    public ColumnCalculatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COLUMN_CALCULATOR.get(), pos, state);
    }

    public CalculatorStatus status() {
        return status;
    }

    public long inputRevision() {
        return inputRevision;
    }

    public long resultRevision() {
        return resultRevision;
    }

    public String resultSummary() {
        return resultSummary;
    }

    public boolean tryBeginCalculation(long gameTime) {
        if (status == CalculatorStatus.CALCULATING || gameTime < nextAllowedRequestGameTime) {
            return false;
        }
        inputRevision++;
        status = CalculatorStatus.CALCULATING;
        nextAllowedRequestGameTime = gameTime + MINIMUM_REQUEST_INTERVAL_TICKS;
        setChanged();
        return true;
    }

    public void failCalculation() {
        status = CalculatorStatus.FAILED;
        setChanged();
    }

    public void commitDummyResult(String summary) {
        resultRevision = inputRevision;
        resultSummary = truncate(summary, MAX_SUMMARY_LENGTH);
        status = CalculatorStatus.SUCCESS;
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.createcheme.column_calculator");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        if (level == null) {
            return null;
        }

        return new ColumnCalculatorMenu(
                containerId,
                inventory,
                ContainerLevelAccess.create(level, worldPosition),
                worldPosition
        );
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(TAG_STATUS, status.serializedName());
        tag.putLong(TAG_INPUT_REVISION, inputRevision);
        tag.putLong(TAG_RESULT_REVISION, resultRevision);
        tag.putString(TAG_RESULT_SUMMARY, resultSummary);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        status = tag.contains(TAG_STATUS, Tag.TAG_STRING)
                ? CalculatorStatus.fromSerializedName(tag.getString(TAG_STATUS))
                : CalculatorStatus.IDLE;
        inputRevision = Math.max(0L, tag.getLong(TAG_INPUT_REVISION));
        resultRevision = tag.contains(TAG_RESULT_REVISION, Tag.TAG_LONG)
                ? tag.getLong(TAG_RESULT_REVISION)
                : -1L;
        resultSummary = truncate(tag.getString(TAG_RESULT_SUMMARY), MAX_SUMMARY_LENGTH);

        if (status == CalculatorStatus.CALCULATING) {
            status = CalculatorStatus.DIRTY;
        }
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    public enum CalculatorStatus {
        IDLE("idle"),
        DIRTY("dirty"),
        CALCULATING("calculating"),
        SUCCESS("success"),
        FAILED("failed"),
        STALE("stale");

        private final String serializedName;

        CalculatorStatus(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static CalculatorStatus fromSerializedName(String name) {
            for (CalculatorStatus value : values()) {
                if (value.serializedName.equals(name)) {
                    return value;
                }
            }
            return IDLE;
        }
    }
}

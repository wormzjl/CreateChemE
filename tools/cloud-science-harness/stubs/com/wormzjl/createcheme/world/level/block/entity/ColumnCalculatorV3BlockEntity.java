package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import java.util.Objects;

/**
 * Harness stand-in for the real block entity (Minecraft BlockEntity): only its V3Operation record and the
 * methaneCduInput() fixture accessor, copied verbatim (harness.sh checks the VERBATIM lines against the real file).
 */
public final class ColumnCalculatorV3BlockEntity {
    private ColumnCalculatorV3BlockEntity() {}

    // VERBATIM-BEGIN
    public static V3ColumnInput methaneCduInput() {
        var catalog=com.wormzjl.createcheme.science.material.MaterialRuntime.current();
        return catalog.presets().column("tia_juana").input(catalog);
    }

    public record V3Operation(long operationId, long inputRevision, V3ColumnInput input) {
        public V3Operation {
            if (operationId <= 0L || inputRevision <= 0L) {
                throw new IllegalArgumentException("V3 operation identity must be positive");
            }
            input = Objects.requireNonNull(input, "input");
        }
    }
    // VERBATIM-END
}

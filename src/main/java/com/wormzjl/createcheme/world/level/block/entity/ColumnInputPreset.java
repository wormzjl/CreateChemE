package com.wormzjl.createcheme.world.level.block.entity;

import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3HollandExample32;
import com.wormzjl.createcheme.science.material.MaterialCatalog;
import com.wormzjl.createcheme.science.material.MaterialRuntime;
import java.util.Arrays;

/** Allowlisted server-owned inputs. Wire identity is the explicit ID, never the enum ordinal. */
public enum ColumnInputPreset {
    TIA_JUANA("tia_juana", "Tia Juana Light (default)", "createcheme:tjl20_methane", "createcheme:tia_juana_light_methane"),
    WTI("wti_light_export", "WTI Light - Export", "createcheme:wti_light_export_tjl20", "createcheme:wti_light_export"),
    UPPER_ZAKUM("upper_zakum", "Upper Zakum", "createcheme:upper_zakum_tjl20", "createcheme:upper_zakum"),
    BONGA("bonga", "Bonga", "createcheme:bonga_tjl20", "createcheme:bonga"),
    DALIA("dalia", "Dalia", "createcheme:dalia_tjl20", "createcheme:dalia"),
    COLD_LAKE("cold_lake_blend", "Cold Lake Blend", "createcheme:cold_lake_blend_tjl20", "createcheme:cold_lake_blend"),
    HOLLAND("holland_3_2", "Holland Example 3-2", "", "");

    private final String id, label, packageId, assayId;
    ColumnInputPreset(String id, String label, String packageId, String assayId) {
        this.id=id; this.label=label; this.packageId=packageId; this.assayId=assayId;
    }
    public String id() { return id; }
    public String label() { return label; }
    public String translationKey() { return "gui.createcheme.column_preset." + id; }
    public boolean matches(V3ColumnInput input) {
        return this == HOLLAND ? V3HollandExample32.isPackage(input.packageId())
                : packageId.equals(input.packageId()) && assayId.equals(input.assayId());
    }
    public static ColumnInputPreset fromId(String id) {
        return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown column input preset: " + id));
    }

    /** Resolves the feed against one captured catalog; callers never supply compositions in preset requests. */
    public V3ColumnInput input(MaterialCatalog catalog) {
        return MaterialRuntime.with(catalog, packageId, () -> switch (this) {
            case HOLLAND -> V3HollandExample32.input();
            default -> ColumnCalculatorV3BlockEntity.assayCduInput(packageId, assayId);
        });
    }
}

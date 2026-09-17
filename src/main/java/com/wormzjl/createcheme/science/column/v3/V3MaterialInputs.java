package com.wormzjl.createcheme.science.column.v3;

import com.wormzjl.createcheme.science.material.MaterialCatalog;

/** Exact current-format identity check. No legacy aliases, additive padding or saved-axis migration. */
public final class V3MaterialInputs {
    private V3MaterialInputs() {}
    public static V3ColumnInput requireCurrent(V3ColumnInput input,MaterialCatalog catalog) {
        if(V3HollandExample32.isPackage(input.packageId()))return input;
        if(!catalog.requirePackage(input.packageId()).components().equals(input.componentBasis().componentIds()))
            throw new IllegalArgumentException("Unsupported persisted component axis");
        return input;
    }
}

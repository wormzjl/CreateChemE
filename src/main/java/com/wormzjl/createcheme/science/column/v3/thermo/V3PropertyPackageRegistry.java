package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.material.MaterialRuntime;

/** Resolves immutable package data from the current calculation's catalog snapshot. */
final class V3PropertyPackageRegistry {
    private V3PropertyPackageRegistry() {}

    static V3PropertyPackage require(String packageId) {
        return new V3CatalogPropertyPackage(MaterialRuntime.current(),packageId);
    }
}

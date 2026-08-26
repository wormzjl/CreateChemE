package com.wormzjl.createcheme.science.column.nextgen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable first-party package registry. Runtime JSON/database loading is intentionally absent. */
public final class ColumnModelRegistry {
    private static final Map<String, ColumnThermoPackage> PACKAGES = Map.of(
            Cdu17TiaJuanaPackage.PACKAGE_ID, Cdu17TiaJuanaPackage.INSTANCE);

    private ColumnModelRegistry() {}

    public static ColumnThermoPackage require(String packageId) {
        ColumnThermoPackage value = PACKAGES.get(packageId);
        if (value == null) throw new IllegalArgumentException("Unsupported column package: " + packageId);
        return value;
    }

    public static Map<String, ColumnThermoPackage> registeredPackages() {
        return new LinkedHashMap<>(PACKAGES);
    }

    public static void requireSupportedMaterial(String packageId, String materialId) {
        Objects.requireNonNull(materialId, "materialId");
        if (!require(packageId).supportedMaterials().contains(materialId)) {
            throw new IllegalArgumentException("Material " + materialId + " is unsupported by package " + packageId);
        }
    }
}

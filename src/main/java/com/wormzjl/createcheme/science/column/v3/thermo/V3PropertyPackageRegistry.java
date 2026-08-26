package com.wormzjl.createcheme.science.column.v3.thermo;

import java.util.Map;

/** Fixed first-party V3 property-package registry; runtime package loading is intentionally unsupported. */
final class V3PropertyPackageRegistry {
    private static final Map<String, V3PropertyPackage> PACKAGES = Map.of(
            V3Cdu17TiaJuanaPackage.PACKAGE_ID, V3Cdu17TiaJuanaPackage.INSTANCE);

    private V3PropertyPackageRegistry() {}

    static V3PropertyPackage require(String packageId) {
        V3PropertyPackage propertyPackage = PACKAGES.get(packageId);
        if (propertyPackage == null) throw new IllegalArgumentException("Unsupported V3 property package: " + packageId);
        return propertyPackage;
    }
}

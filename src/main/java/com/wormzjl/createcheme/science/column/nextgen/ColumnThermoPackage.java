package com.wormzjl.createcheme.science.column.nextgen;

import java.util.List;

/** Package boundary used by problem resolution; solvers receive this immutable selection rather than a global singleton. */
public interface ColumnThermoPackage {
    String packageId();
    String datasetRevision();
    ComponentBasis basis();
    CharacterizedFeed feedForAssay(String assayId);
    double minimumTemperatureKelvin();
    double maximumTemperatureKelvin();
    double minimumPressurePascal();
    double maximumPressurePascal();
    List<String> supportedMaterials();
    double[][] binaryInteractions();
}

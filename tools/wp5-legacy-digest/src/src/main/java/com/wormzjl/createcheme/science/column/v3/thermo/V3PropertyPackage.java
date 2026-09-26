package com.wormzjl.createcheme.science.column.v3.thermo;

import com.wormzjl.createcheme.science.column.v3.V3ComponentBasis;
import java.util.List;

/** Immutable property package selected by V3 before a solve begins. */
interface V3PropertyPackage {
    String packageId();
    String datasetRevision();
    V3ComponentBasis componentBasis();
    V3PropertyComponent component(int publicComponent);
    V3CrudeFeed crudeFeed(String assayId);
    double minimumTemperatureKelvin();
    double maximumTemperatureKelvin();
    double minimumPressurePascal();
    double maximumPressurePascal();
    double[][] binaryInteractions();

    /** Non-blocking, dataset-owned evidence copied into every calculation's audit and diagnostics. */
    default List<String> advisoryEvidence() {
        return List.of();
    }
}

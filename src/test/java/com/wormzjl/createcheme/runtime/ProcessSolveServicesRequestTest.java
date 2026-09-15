package com.wormzjl.createcheme.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessSolveServicesRequestTest {
    @Test
    void commandCapturesTheCatalogAtAdmissionRatherThanWorkerStart() {
        var original=com.wormzjl.createcheme.science.material.MaterialCatalog.bundled();
        var replacement=com.wormzjl.createcheme.science.material.MaterialCatalog.parse(original.resources());
        try {
            com.wormzjl.createcheme.science.material.MaterialRuntime.publish(original);
            var input=com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.methaneCduInput();
            var command=new ProcessSolveServices.V3ColumnCommand(input,0);
            com.wormzjl.createcheme.science.material.MaterialRuntime.publish(replacement);
            org.junit.jupiter.api.Assertions.assertSame(original,command.catalog());
            org.junit.jupiter.api.Assertions.assertSame(replacement,new ProcessSolveServices.V3ColumnCommand(input,0).catalog());
        } finally {com.wormzjl.createcheme.science.material.MaterialRuntime.reset();}
    }
    @Test
    void processWideRequestIdsArePositiveAndStrictlyMonotonic() {
        long first = ProcessSolveServices.nextRequestId();
        long second = ProcessSolveServices.nextRequestId();

        assertTrue(first > 0L);
        assertTrue(second > first);
    }
}

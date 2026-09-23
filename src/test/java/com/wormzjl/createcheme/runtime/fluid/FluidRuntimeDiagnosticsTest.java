package com.wormzjl.createcheme.runtime.fluid;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidRuntimeDiagnosticsTest {
    @Test void countersAreInertUntilEnabledAndExcludeAPausedObserver() {
        FluidRuntimeDiagnostics.reset();
        try {
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandVisits,5);
            assertEquals(0,FluidRuntimeDiagnostics.sample().get("islandVisits"),"a disabled counter must not record");
            FluidRuntimeDiagnostics.ENABLED=true;
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandVisits,5);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.readinessPumps);
            FluidRuntimeDiagnostics.pause();FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.islandVisits,7);FluidRuntimeDiagnostics.resume();
            var sample=FluidRuntimeDiagnostics.sample();
            assertEquals(5,sample.get("islandVisits"),"a paused observer's work must not be charged to the engine");
            assertEquals(1,sample.get("readinessPumps"));
            assertEquals(FluidRuntimeDiagnostics.names(),java.util.List.copyOf(sample.keySet()));
            assertThrows(IllegalStateException.class,FluidRuntimeDiagnostics::resume);
            FluidRuntimeDiagnostics.reset();assertEquals(0,FluidRuntimeDiagnostics.sample().get("islandVisits"));
        } finally {FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}
    }
}

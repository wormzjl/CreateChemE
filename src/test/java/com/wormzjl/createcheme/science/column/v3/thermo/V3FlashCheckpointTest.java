package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Callback failures are caller failures, even when their type also describes a recoverable flash failure. */
class V3FlashCheckpointTest {
    private static final String PACKAGE_ID = "createcheme:cdu17_tjl_acs2018";
    private static final V3TraceTruncationPolicy POLICY = V3TraceTruncationPolicy.of(1.0e-6);

    @Test
    void oneShotThermoFailureDuringReducedWorkEscapesInsteadOfBecomingFallback() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3FlashResult successful = thermo.flashTP(500.0, 250_000.0, binaryFeed(thermo),
                POLICY, thermo.newWorkspace(), () -> { });
        assertEquals(V3FlashTruncationEvidence.Status.APPLIED, successful.truncationEvidence().status(),
                successful.truncationEvidence()::toString);

        assertCheckpointEscapes(3, new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE,
                null, "one-shot caller failure during reduced work"));
    }

    @Test
    void thermoFailuresBeforeAndAfterTheReferencePreserveTheirIdentity() {
        for (int checkpoint : new int[] {1, 2}) {
            assertCheckpointEscapes(checkpoint, new V3ThermoException(V3ThermoException.Code.DOMAIN,
                    null, "caller failure at reference boundary " + checkpoint));
        }
    }

    @Test
    void cancellationEscapesUnchangedAtEveryFlashBoundary() {
        for (int checkpoint : new int[] {1, 2, 3}) {
            assertCheckpointEscapes(checkpoint, new CancellationException("caller cancelled at " + checkpoint));
        }
    }

    @Test
    void unrelatedRuntimeFailureEscapesUnchangedAtEveryFlashBoundary() {
        for (int checkpoint : new int[] {1, 2, 3}) {
            assertCheckpointEscapes(checkpoint, new IllegalStateException("caller failed at " + checkpoint));
        }
    }

    private static void assertCheckpointEscapes(int failingCheckpoint, RuntimeException expected) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        AtomicInteger checkpoints = new AtomicInteger();
        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> thermo.flashTP(500.0, 250_000.0, binaryFeed(thermo), POLICY, thermo.newWorkspace(),
                        () -> {
                            // Deliberately one-shot: a mistakenly recovered exception must not be
                            // hidden by another throw when the fallback invokes the callback again.
                            if (checkpoints.incrementAndGet() == failingCheckpoint) throw expected;
                        }));
        assertSame(expected, actual, "The public boundary must preserve the original callback failure");
        assertEquals(failingCheckpoint, checkpoints.get(), "No checkpoint may run after caller failure");
    }

    private static double[] binaryFeed(V3PengRobinsonThermo thermo) {
        double[] feed = new double[thermo.componentBasis().componentCount()];
        feed[1] = 0.5; // Ethane.
        feed[15] = 0.5; // PC12; phase omission is qualified by the reference flash, not its identity.
        return feed;
    }
}

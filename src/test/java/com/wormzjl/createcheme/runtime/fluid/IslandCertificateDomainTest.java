package com.wormzjl.createcheme.runtime.fluid;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** F4, T2.2: an interval whose step control met the property domain's boundary is not replayed by a certificate. */
class IslandCertificateDomainTest {
    @Test void aThermoDomainRejectionKeepsTheIntervalFromCertifying() {
        assertTrue(IslandCertificate.transitionFree(Map.of("Embedded pipe error #",3)));
        assertFalse(IslandCertificate.transitionFree(Map.of("Embedded pipe error #",3,"thermo-domain: Nitrogen temperature < 63.151 K",1)));
    }
}

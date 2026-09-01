package com.wormzjl.createcheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class V3FeedFlashTest {
    @Test
    void convergedLiquidEndpointDoesNotPublishAnInfinitesimalVaporPhase() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        // A 50 Celsius crude-condenser outlet whose Wilson guess starts inside the two-phase bracket.
        double[] composition = {0.0, 0.002221751374857895, 0.019949047354192243, 0.054160836655037715,
                0.2442251337042464, 0.11704483090680007, 0.20198741266388825, 0.09732234881493797,
                0.17093587548826125, 0.0881394158911579, 0.003960475367106011, 5.245952693290889e-5,
                4.11295543900127e-7, 9.5703604528816e-10, 1.4138582777064319e-15, 2.3450168525575994e-27};

        V3FlashResult flash = thermo.flashTP(323.15, 110_000.0, composition, thermo.newWorkspace());

        assertTrue(flash.iterations() > 0);
        assertEquals(V3FeedPhase.LIQUID, flash.phase(), flash::detail);
        assertEquals(0.0, flash.vaporFraction());
        assertEquals(0, flash.vaporComposition().length);
        assertArrayEquals(composition, flash.liquidComposition(), 1.0e-12);
    }
}

package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DryAcceptanceAuditTest {
    @Test
    void incompletePassingAuditCannotForgeASuccess() {
        DryAcceptanceAudit audit = new DryAcceptanceAudit(List.of(
                DryAcceptanceAudit.Check.pass(DryResidualFamily.LOCAL_COMPONENT_BALANCE, 0.0, 0.0,
                        -1, -1, "partial fixture")));

        assertFalse(audit.accepted());
    }

    @Test
    void completeUniquePassingAuditIsAccepted() {
        ArrayList<DryAcceptanceAudit.Check> checks = new ArrayList<>();
        for (DryResidualFamily family : DryResidualFamily.values()) {
            if (family == DryResidualFamily.INPUT_VALIDITY || family == DryResidualFamily.CANCELLATION) continue;
            checks.add(DryAcceptanceAudit.Check.pass(family, 0.0, 0.0, -1, -1, "complete fixture"));
        }

        assertTrue(new DryAcceptanceAudit(checks).accepted());
    }

    @Test
    void passingFlagCannotContradictItsTolerance() {
        assertThrows(IllegalArgumentException.class, () -> new DryAcceptanceAudit.Check(
                DryResidualFamily.ENERGY_BALANCE, 1.0, 0.0, -1, -1, true, "forged pass"));
    }
}

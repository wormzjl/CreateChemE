package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class V3TruncationAuditTest {
    @Test
    void auditConsumesFrozenSupportAndAcceptsTheRecomputedSmallDefect() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3TruncationSupport support = fixture.problem().truncationSupport();
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), fixture.thermo(), 0.0)
                .audit(fixture.exact(), fixture.thermo().newWorkspace());
        assertTrue(audit.accepted(), audit::toString);
        V3AcceptanceAudit.Check defect = defectCheck(audit);
        assertEquals(0.01 / 90.01, defect.value(), 1.0e-16);
        assertEquals(0.08, defect.limit());
        assertSame(support, fixture.problem().truncationSupport());
        assertEquals(5, support.truncatedPointCount());
    }

    @Test
    void auditRejectsAnOversizedDefectEvenWhenAllRetainedMeshRowsClose() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 10.0);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), fixture.thermo(), 0.0)
                .audit(fixture.exact(), fixture.thermo().newWorkspace());
        assertFalse(audit.accepted());
        assertEquals(0.1, defectCheck(audit).value(), 1.0e-15);
        assertFalse(defectCheck(audit).passed());
        assertTrue(audit.checks().stream().filter(check -> !check.family().equals("TRUNCATION_MASS_DEFECT"))
                .allMatch(V3AcceptanceAudit.Check::passed));
    }

    @Test
    void digestIncludesTheRequestedCutoffButNotPathDependentSupportAndPreservesZeroEncoding() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3InputDigest original = V3InputDigest.of(fixture.original(), "mesh-r2", "data", "assumptions");
        assertEquals(original, V3InputDigest.of(fixture.original(), "mesh-r2", "data", "assumptions", 0.0));
        assertEquals(original, V3InputDigest.of(fixture.original(), "mesh-r2", "data", "assumptions", -0.0));
        V3InputDigest enabled = V3InputDigest.of(fixture.problem(), "mesh-r3", "data", "assumptions", 1.0e-6);
        assertEquals(enabled, V3InputDigest.of(fixture.original(), "mesh-r3", "data", "assumptions", 1.0e-6));
        assertNotEquals(original, enabled);
        assertNotEquals(enabled, V3InputDigest.of(fixture.problem(), "mesh-r3", "data", "assumptions", 1.0e-5));
        assertThrows(IllegalArgumentException.class,
                () -> V3InputDigest.of(fixture.problem(), "mesh-r3", "data", "assumptions", Double.NaN));
    }

    private static V3AcceptanceAudit.Check defectCheck(V3AcceptanceAudit audit) {
        return audit.checks().stream().filter(check -> check.family().equals("TRUNCATION_MASS_DEFECT"))
                .findFirst().orElseThrow();
    }
}

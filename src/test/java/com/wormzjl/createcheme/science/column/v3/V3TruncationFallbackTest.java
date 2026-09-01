package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class V3TruncationFallbackTest {
    @ParameterizedTest
    @EnumSource(V3SolverFailureCode.class)
    void onlyAdmissionInvariantFailuresAreTerminalAndAllOtherFailuresRetryExactlyOnce(V3SolverFailureCode code) {
        List<Double> cutoffs = new ArrayList<>();
        V3ColumnOutcome.Failure first = failure(code);
        V3ColumnOutcome.Success fallback = success();
        V3ColumnOutcome outcome = V3TruncationFallback.calculate(1.0e-6, cutoff -> {
            cutoffs.add(cutoff);
            return cutoffs.size() == 1 ? first : fallback;
        });
        if (code == V3SolverFailureCode.INVALID_INPUT || code == V3SolverFailureCode.PROPERTY_OUT_OF_RANGE) {
            assertSame(first, outcome);
            assertEquals(List.of(1.0e-6), cutoffs);
        } else {
            assertEquals(List.of(1.0e-6, 0.0), cutoffs);
            assertSame(fallback.result(), assertInstanceOf(V3ColumnOutcome.Success.class, outcome).result());
            assertEquals("stage-trace cutoff=1.0E-6; truncated=12/480; closure-pruned=0; defect/feed=0", outcome.diagnostics().events().getFirst());
            assertTrue(outcome.diagnostics().events().get(1).contains("retried untruncated"));
            assertTrue(outcome.diagnostics().events().get(1).contains(code.name()));
            assertTrue(outcome.diagnostics().events().stream().allMatch(event -> event.length() <= 256));
            assertEquals(32, outcome.diagnostics().events().size());
            assertTrue(outcome.diagnostics().solvePath().length() <= 128);
        }
    }

    @Test
    void aFailedUntruncatedRetryDoesNotRecurseAndZeroNeverRetries() {
        AtomicInteger calls = new AtomicInteger();
        V3ColumnOutcome.Failure failure = failure(V3SolverFailureCode.NONCONVERGENCE);
        V3ColumnOutcome result = V3TruncationFallback.calculate(1.0e-6, cutoff -> { calls.incrementAndGet(); return failure; });
        assertEquals(2, calls.get());
        assertInstanceOf(V3ColumnOutcome.Failure.class, result);
        calls.set(0);
        assertSame(failure, V3TruncationFallback.calculate(0.0, cutoff -> { calls.incrementAndGet(); return failure; }));
        assertEquals(1, calls.get());
    }

    @Test
    void successIsNotRetriedAndCancellationEscapesTheFirstChainAndTheRetry() {
        V3ColumnOutcome.Success success = success();
        AtomicInteger calls = new AtomicInteger();
        assertSame(success, V3TruncationFallback.calculate(1.0e-6, cutoff -> { calls.incrementAndGet(); return success; }));
        assertEquals(1, calls.get());
        CancellationException cancelled = new CancellationException("caller deadline");
        assertSame(cancelled, assertThrows(CancellationException.class,
                () -> V3TruncationFallback.calculate(1.0e-6, cutoff -> { throw cancelled; })));
        calls.set(0);
        assertSame(cancelled, assertThrows(CancellationException.class,
                () -> V3TruncationFallback.calculate(1.0e-6, cutoff -> {
                    if (calls.incrementAndGet() == 1) return failure(V3SolverFailureCode.NONCONVERGENCE);
                    throw cancelled;
                })));
        assertEquals(2, calls.get());
    }

    private static V3ColumnOutcome.Failure failure(V3SolverFailureCode code) {
        V3AcceptanceAudit audit = new V3AcceptanceAudit(List.of(
                V3AcceptanceAudit.Check.fail("TRUNCATION_MASS_DEFECT", 0.1, 0.01, "constructed failure")));
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(0, 2, 0, 0, 0.0, 0.0, "test/truncated",
                List.of("stage-trace cutoff=1.0E-6; truncated=12/480; closure-pruned=0; defect/feed=0"),
                audit, V3ConvergenceEvidence.unavailable());
        return new V3ColumnOutcome.Failure(code, "constructed failure", diagnostics);
    }

    private static V3ColumnOutcome.Success success() {
        V3ColumnProblem problem = V3TruncationSupportTest.problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3AcceptanceAudit audit = new V3AcceptanceAudit(List.of(V3AcceptanceAudit.Check.pass("TEST", 0, 0, "fixture")));
        V3ConvergenceEvidence evidence = new V3ConvergenceEvidence(true, 0, 0, 0, 0);
        V3ColumnResult result = V3ColumnResult.accepted(problem, new V3InputDigest("0".repeat(64)), audit, evidence);
        List<String> events = new ArrayList<>();
        for (int index = 0; index < 32; index++) events.add("existing event " + index);
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(0, 3, 0, 0, 0.0, 0.0,
                "test/untruncated", events, audit, evidence);
        return new V3ColumnOutcome.Success(result, diagnostics);
    }
}

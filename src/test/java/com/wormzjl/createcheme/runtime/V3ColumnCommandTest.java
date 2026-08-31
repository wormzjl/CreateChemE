package com.wormzjl.createcheme.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.createcheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.createcheme.science.column.v3.V3ColumnInput;
import com.wormzjl.createcheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.createcheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V3ColumnCommandTest {
    @Test
    void admittedCommandRetainsItsCutoffAfterConfigurationChangesBeforeExecution() {
        V3ColumnInput input = input();
        AtomicReference<Double> configMolPercent = new AtomicReference<>(0.0001);
        ProcessSolveServices.V3ColumnCommand command = new ProcessSolveServices.V3ColumnCommand(
                input, configMolPercent.get() / 100.0);
        configMolPercent.set(0.0); // Simulated reload while this immutable command is awaiting execution.

        assertEquals(1.0e-6, command.stageTraceCutoffMoleFraction());
        assertSame(input, command.input());
        ProcessSolveServices.V3ColumnSolveResult result = assertInstanceOf(ProcessSolveServices.V3ColumnSolveResult.class,
                command.solve(unbounded()));
        V3ColumnOutcome.Success actual = assertInstanceOf(V3ColumnOutcome.Success.class, result.outcome());
        V3ColumnOutcome.Success expected = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(input, () -> {}, 1.0e-6));
        V3ColumnOutcome.Success disabled = assertInstanceOf(V3ColumnOutcome.Success.class, V3ColumnCalculator.calculate(input));
        assertEquals(expected.result().inputDigest(), actual.result().inputDigest());
        assertNotEquals(disabled.result().inputDigest(), actual.result().inputDigest());
        assertEquals(expected.result().streams(), actual.result().streams());
    }

    @Test
    void immutableCommandRevalidatesTheFractionIndependentlyOfTheConfigRange() {
        for (double invalid : new double[] {-Double.MIN_VALUE, Math.nextUp(0.01), Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ProcessSolveServices.V3ColumnCommand(input(), invalid));
        }
        assertDoesNotThrow(() -> new ProcessSolveServices.V3ColumnCommand(input(), 0.0));
        assertDoesNotThrow(() -> new ProcessSolveServices.V3ColumnCommand(input(), 0.01));
        assertThrows(NullPointerException.class, () -> new ProcessSolveServices.V3ColumnCommand(null, 0.0));
    }

    @Test
    void callerCancellationStillEscapesBeforeScientificWork() {
        CancellationException cancellation = new CancellationException("cancelled worker");
        BoundedCpuSolveService.CancellationToken token = new BoundedCpuSolveService.CancellationToken() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public boolean isDeadlineExceeded() { return false; }
            @Override public boolean isCancellationRequested() { return true; }
            @Override public void throwIfCancellationRequested() { throw cancellation; }
        };
        assertSame(cancellation, assertThrows(CancellationException.class,
                () -> new ProcessSolveServices.V3ColumnCommand(input(), 1.0e-6).solve(token)));
    }

    private static BoundedCpuSolveService.CancellationToken unbounded() {
        return new BoundedCpuSolveService.CancellationToken() {
            @Override public long deadlineNanos() { return Long.MAX_VALUE; }
            @Override public boolean isDeadlineExceeded() { return false; }
            @Override public boolean isCancellationRequested() { return false; }
            @Override public void throwIfCancellationRequested() {}
        };
    }

    private static V3ColumnInput input() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        double[] flows = new double[thermo.componentBasis().componentCount()];
        flows[6] = 50.0;
        flows[13] = 50.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:command-snapshot",
                thermo.componentBasis(), flows, 550.0, 2, 1, 250_000.0, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                new V3ColumnSpecification.OrganicRefluxRatio(2.0), new V3ColumnSpecification.ReboilerDuty(0.0)));
    }
}

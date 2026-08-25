package com.wormzjl.createcheme.science.column;

import static com.wormzjl.createcheme.science.column.ColumnSimulation.INPUT_SCHEMA_VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CounterCurrentColumnSolverTest {
    @Test
    void crudeCascadeConvergesAndClosesEveryProductAndComponentFlow() {
        ColumnSimulation.ColumnInput input = standardInput();

        CounterCurrentColumnSolver.Result result = new CounterCurrentColumnSolver().solve(input);

        assertTrue(result.converged());
        assertTrue(result.sweeps() <= CounterCurrentColumnSolver.MAXIMUM_SWEEPS);
        assertEquals(input.stageCount(), result.temperatures().length);
        assertEquals(input.sideDraws().size() + 2, result.productFlows().length);
        assertTrue(result.maximumEquilibriumResidual() <= 1.0e-8);
        assertTrue(result.maximumVaporFractionResidual() <= 1.0e-6);
        assertTrue(result.maximumRelativeStageComponentResidual()
                <= CounterCurrentColumnSolver.STAGE_COMPONENT_TOLERANCE);
        assertTrue(result.propertyEvaluations() < 100_000);

        double productTotal = 0.0;
        for (double productFlow : result.productFlows()) {
            productTotal += productFlow;
        }
        assertEquals(input.feedMolarFlowMolPerSecond(), productTotal, 1.0e-10);

        double[] feed = TiaJuanaLight12PropertyPackage.feedMoleFractions();
        double[][] componentFlows = result.componentFlows();
        for (int component = 0; component < feed.length; component++) {
            double componentTotal = 0.0;
            for (double productComponentFlow : componentFlows[component]) {
                componentTotal += productComponentFlow;
            }
            assertEquals(
                    input.feedMolarFlowMolPerSecond() * feed[component],
                    componentTotal,
                    1.0e-9);
        }

        double topLightFraction = productFraction(result, 0, 0);
        double bottomLightFraction = productFraction(
                result, 0, result.productFlows().length - 1);
        double topHeavyFraction = productFraction(
                result, feed.length - 1, 0);
        double bottomHeavyFraction = productFraction(
                result, feed.length - 1, result.productFlows().length - 1);
        assertTrue(topLightFraction > bottomLightFraction);
        assertTrue(bottomHeavyFraction > topHeavyFraction);
    }


    @Test
    void inGameCrudeCaseConvergesWithinTheWorkerDeadlineBudget() {
        ColumnSimulation.ColumnInput input = new ColumnSimulation.ColumnInput(
                INPUT_SCHEMA_VERSION,
                TiaJuanaLight12PropertyPackage.ASSAY_ID,
                2610.7 * 1000.0 / 3600.0,
                365.0 + 273.15,
                30,
                24,
                8.0e6,
                4.17,
                ColumnSimulation.RefluxCondition.saturatedLiquid(),
                List.of(
                        new ColumnSimulation.SideDrawSpec(8, 496.0 * 1000.0 / 3600.0),
                        new ColumnSimulation.SideDrawSpec(15, 653.0 * 1000.0 / 3600.0),
                        new ColumnSimulation.SideDrawSpec(22, 149.0 * 1000.0 / 3600.0)));

        long started = System.nanoTime();
        CounterCurrentColumnSolver.Result result = new CounterCurrentColumnSolver().solve(input);
        long elapsedMilliseconds = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(result.converged());
        assertTrue(result.maximumRelativeStageComponentResidual()
                <= CounterCurrentColumnSolver.STAGE_COMPONENT_TOLERANCE);
        assertTrue(elapsedMilliseconds < 5_000L);
    }

    @Test
    void cascadeIsDeterministic() {
        CounterCurrentColumnSolver solver = new CounterCurrentColumnSolver();
        ColumnSimulation.ColumnInput input = standardInput();

        CounterCurrentColumnSolver.Result first = solver.solve(input);
        CounterCurrentColumnSolver.Result second = solver.solve(input);

        assertEquals(first.sweeps(), second.sweeps());
        assertEquals(first.maximumCompositionChange(), second.maximumCompositionChange());
        for (int component = 0; component < first.componentFlows().length; component++) {
            for (int product = 0; product < first.componentFlows()[component].length; product++) {
                assertEquals(
                        first.componentFlows()[component][product],
                        second.componentFlows()[component][product]);
            }
        }
    }

    private static double productFraction(
            CounterCurrentColumnSolver.Result result, int component, int product) {
        return result.componentFlows()[component][product] / result.productFlows()[product];
    }

    private static ColumnSimulation.ColumnInput standardInput() {
        return new ColumnSimulation.ColumnInput(
                INPUT_SCHEMA_VERSION,
                TiaJuanaLight12PropertyPackage.ASSAY_ID,
                100.0,
                620.0,
                30,
                24,
                8.0e6,
                3.0,
                ColumnSimulation.RefluxCondition.saturatedLiquid(),
                List.of(
                        new ColumnSimulation.SideDrawSpec(8, 10.0),
                        new ColumnSimulation.SideDrawSpec(15, 12.0),
                        new ColumnSimulation.SideDrawSpec(22, 8.0)));
    }
}

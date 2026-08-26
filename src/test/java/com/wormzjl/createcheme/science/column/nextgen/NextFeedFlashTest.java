package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NextFeedFlashTest {
    @Test
    void defaultFeedResolvesAsABoundedTwoPhaseState() {
        ColumnProblem problem = ColumnProblem.resolve(ColumnNextInput.defaults());
        NextPengRobinsonKernel kernel = new NextPengRobinsonKernel(problem.propertyPackage());
        double[] feed = new double[kernel.componentCount()];
        for (int component = 0; component < feed.length; component++) feed[component] = problem.feed().moleFraction(component);

        NextFeedFlash.Workspace workspace = new NextFeedFlash.Workspace(kernel);
        NextFeedFlash.Result result = NextFeedFlash.resolve(kernel, problem.input().crudeFeed().temperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedStage()), feed, workspace);

        assertTrue(result.converged(), result::detail);
        assertTrue(result.vaporFraction() > 0.0 && result.vaporFraction() < 1.0);
        assertTrue(Math.abs(sum(workspace.liquidComposition) - 1.0) < 1.0e-12);
        assertTrue(Math.abs(sum(workspace.vaporComposition) - 1.0) < 1.0e-12);
    }

    private static double sum(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum;
    }
}

package com.wormzjl.createcheme.science.column.nextgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NextPhaseStabilityTest {
    @Test
    void defaultFeedHasBoundedStabilityEvidenceRatherThanAnAssumedMergedPhase() {
        ColumnProblem problem = ColumnProblem.resolve(ColumnNextInput.defaults());
        NextPengRobinsonKernel kernel = new NextPengRobinsonKernel(problem.propertyPackage());
        double[] feed = new double[kernel.componentCount()];
        for (int component = 0; component < feed.length; component++) feed[component] = problem.feed().moleFraction(component);

        NextPhaseStability.Result result = NextPhaseStability.assess(kernel,
                problem.input().crudeFeed().temperatureKelvin(), problem.nodePressurePascal(problem.topology().feedStage()),
                feed, new NextPhaseStability.Workspace(kernel));

        assertTrue(result.converged(), result::toString);
        assertTrue(result.unstable(), () -> "TPD=" + result.minimumTangentPlaneDistance());
        assertTrue(result.minimumTangentPlaneDistance() < 0.0);
    }
}

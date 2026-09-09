package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two seed rules that keep a continuation grid from starting with oversized trace flows.
 *
 * <p>Both exist because of one identity. A material row in log-flow coordinates is {@code r = I − a·e^z},
 * and once {@code a·e^z} dominates the delivered material {@code I} the exact Newton step is
 * {@code −1 + I/(a·e^z)}: one e-fold per iteration, on a scaled residual that sits at exactly one throughout.
 * A flow seeded eighteen e-folds too high therefore costs eighteen iterations no matter how good the
 * Jacobian is. The stage map ({@link V3ColumnCalculator#sourcePosition}) stops a rectifying node from
 * reading such a flow off the source feed tray, and the cap inside
 * {@code V3ColumnCalculator.liftFloorSupport} removes one wherever it still appears.</p>
 *
 * <p>The cap's fixture is the manufactured ternary column of {@link V3TruncationNumericsTest}: four trays,
 * feed on tray two, and a thermodynamic model calibrated on the seed so that every node's {@code K_c V/L} is
 * exactly the vapour-to-liquid ratio the seed already has there. Its two bulk components are in exact
 * material balance at every node, so anything the lift changes is the trace component the test is about.</p>
 */
class V3ContinuationSeedTest {
    private static final List<Integer> SCHEDULE = List.of(4, 8, 15, 30, 40);
    private static final int REQUESTED_STAGES = 40;
    private static final int REQUESTED_FEED_TRAY = 37;
    private static final double TRACE_FEED = 0.01;
    private static final double TRACE_FLOOR = TRACE_FEED * V3TruncationSupport.TRACE_FLOOR_FRACTION;
    private static final int TRACE = 2;

    /**
     * No rectifying node of any grid in the literature schedule reads the previous grid's feed tray, both
     * column ends stay fixed points, and the map never walks back up the profile.
     *
     * <p>The first claim is the one the seed depends on: {@code interpolate} blends the source nodes
     * {@code floor(position)} and {@code floor(position) + 1}, so a rectifying position bounded by
     * {@code sourceFeed − 1} cannot draw on the source feed tray at all. The unclamped whole-column map gave
     * the tray above the feed a position of 27.22 on the 30-to-40 rung — 22 % of the source feed tray, on the
     * one tray of the column where the heaviest components sit sixteen orders of magnitude below their feed
     * flow.</p>
     *
     * <p>Below the feed the map is deliberately left alone, so the assertions stop at the feed tray. The
     * symmetric clamp was measured and costs two of the three cold total-condenser pilot cases their
     * convergence; {@link V3ColumnCalculator#sourcePosition} records that measurement.</p>
     */
    @Test
    void noRectifyingNodeOfAStageGridReadsThePreviousGridsFeedTray() {
        for (int index = 1; index < SCHEDULE.size(); index++) {
            V3ColumnTopology source = grid(SCHEDULE.get(index - 1));
            V3ColumnTopology target = grid(SCHEDULE.get(index));
            String pair = SCHEDULE.get(index - 1) + "-to-" + SCHEDULE.get(index);

            assertEquals(0.0, V3ColumnCalculator.sourcePosition(target.condenserNode(), source, target),
                    pair + ": the condenser is a fixed point");
            assertEquals(source.reboilerNode(),
                    V3ColumnCalculator.sourcePosition(target.reboilerNode(), source, target),
                    pair + ": the reboiler is a fixed point");

            // Monotone rather than strictly monotone: a clamped run of rectifying nodes shares one source
            // node, which is the whole cost of the clamp.
            double previous = Double.NEGATIVE_INFINITY;
            for (int node = 0; node <= target.reboilerNode(); node++) {
                double position = V3ColumnCalculator.sourcePosition(node, source, target);
                assertTrue(position >= previous, pair + ": node " + node + " must not walk back up the profile");
                assertTrue(position >= 0.0 && position <= source.reboilerNode(),
                        pair + ": node " + node + " read outside the source column at " + position);
                previous = position;
                if (node < target.feedTrayNumber()) {
                    assertTrue(position <= source.feedTrayNumber() - 1.0,
                            pair + ": rectifying node " + node + " read the source feed tray at " + position);
                }
            }
        }
        // The rung the plateau was measured on: tray 36 of the 40-tray grid is the tray above its feed, and
        // the unclamped map gave it position 27.22 — 22 % of the 30-tray grid's feed tray, node 28.
        assertEquals(27.0, V3ColumnCalculator.sourcePosition(36, grid(30), grid(40)));
    }

    /** A ramp rung maps a grid onto itself, and it must do so exactly: every position is its own node. */
    @Test
    void aGridMapsOntoItselfAsTheIdentity() {
        V3ColumnTopology grid = grid(REQUESTED_STAGES);
        for (int node = 0; node <= grid.reboilerNode(); node++) {
            assertEquals((double) node, V3ColumnCalculator.sourcePosition(node, grid, grid));
        }
    }

    /**
     * A retained trace point far above its own material row is brought back to what its neighbours deliver,
     * split the way its equilibrium row would split it, and every point at or below its inflow is untouched.
     *
     * <p>Tray four holds more than a million times the material tray three's liquid and the reboiler's vapour
     * deliver to it. Tray three holds two parts in a million of what <em>its</em> neighbours deliver, tray one
     * is exactly at its balance, and the condenser is below its own: all three must come back bit-for-bit
     * unchanged, because the cap is one-sided and raising a flow is the reinsertion's job.</p>
     */
    @Test
    void anOversizedTracePointIsBroughtBackToTheMaterialItsNeighboursDeliver() {
        V3ColumnProblem problem = ternary(V3CondenserPhaseBranch.TWO_PHASE);
        // Tray one is exactly at its balance: half of the condenser's 3e-3 of reflux plus 4e-3 from the feed
        // tray's vapour. Tray four carries one mol/s in each phase against an inflow of 1.5e-6.
        double[][] liquid = {{10, 10, 3.0e-3}, {5, 5, 2.5e-3}, {35, 65, 6.0e-3}, {35, 65, 1.0e-6},
                {35, 65, 1.0}, {17, 53, 5.0e-7}};
        double[][] vapor = {{8, 2, 2.0e-3}, {18, 12, 3.0e-3}, {18, 12, 4.0e-3}, {18, 12, 1.0e-6},
                {18, 12, 1.0}, {18, 12, 5.0e-7}};
        V3DryMeshState seed = state(problem, liquid, vapor);

        V3DryMeshState lifted = V3ColumnCalculator.liftFloorSupport(problem, thermo(problem, seed), seed);

        // Tray four is fed by tray three's liquid and the reboiler's vapour, neither of which the cap touches.
        double delivered = liquid[3][TRACE] + vapor[5][TRACE];
        assertEquals(delivered, lifted.liquidFlow(4, TRACE) + lifted.vaporFlow(4, TRACE), 1.0e-18,
                "the capped point holds exactly the material delivered to it");
        // Calibrating the manufactured thermo on the seed makes K_c V/L the seed's own vapour-to-liquid ratio
        // at that node, so the equilibrium split reproduces the ratio the point already had.
        assertEquals(vapor[4][TRACE] / liquid[4][TRACE],
                lifted.vaporFlow(4, TRACE) / lifted.liquidFlow(4, TRACE), 1.0e-9,
                "the capped point is split the way its own equilibrium row would split it");
        assertTrue(lifted.liquidFlow(4, TRACE) >= TRACE_FLOOR && lifted.vaporFlow(4, TRACE) >= TRACE_FLOOR,
                "a capped phase never falls below the support floor");
        assertTrue(lifted.liquidFlow(4, TRACE) < liquid[4][TRACE] && lifted.vaporFlow(4, TRACE) < vapor[4][TRACE],
                "the cap only ever reduces a flow");

        for (int node : new int[] {0, 1, 3, 5}) {
            assertEquals(liquid[node][TRACE], lifted.liquidFlow(node, TRACE),
                    "node " + node + " is at or below its inflow and must be left alone");
            assertEquals(vapor[node][TRACE], lifted.vaporFlow(node, TRACE),
                    "node " + node + " is at or below its inflow and must be left alone");
        }
        // The bulk components are in balance at every node and must come through untouched as well.
        for (int node = 0; node < 6; node++) {
            for (int component = 0; component < TRACE; component++) {
                assertEquals(liquid[node][component], lifted.liquidFlow(node, component));
                assertEquals(vapor[node][component], lifted.vaporFlow(node, component));
            }
        }
    }

    /**
     * The feed tray is never capped, for the reason {@code V3TruncationSupport.derive} retains it whole: it is
     * the root of every material path, and its flows anchor the continuation rather than follow from the
     * trays around it.
     */
    @Test
    void theFeedTrayIsNeverCapped() {
        V3ColumnProblem problem = ternary(V3CondenserPhaseBranch.TWO_PHASE);
        // Ten mol/s in each phase of a component whose whole feed is 0.01 mol/s, against an inflow of 0.0135.
        double[][] liquid = {{10, 10, 3.0e-3}, {5, 5, 2.5e-3}, {35, 65, 10.0}, {35, 65, 1.0e-6},
                {35, 65, 1.0e-6}, {17, 53, 5.0e-7}};
        double[][] vapor = {{8, 2, 2.0e-3}, {18, 12, 3.0e-3}, {18, 12, 10.0}, {18, 12, 1.0e-6},
                {18, 12, 1.0e-6}, {18, 12, 5.0e-7}};
        V3DryMeshState seed = state(problem, liquid, vapor);

        V3DryMeshState lifted = V3ColumnCalculator.liftFloorSupport(problem, thermo(problem, seed), seed);

        assertEquals(10.0, lifted.liquidFlow(2, TRACE));
        assertEquals(10.0, lifted.vaporFlow(2, TRACE));
    }

    /**
     * A phase the point does not structurally have stays at exactly zero, and a one-phase point is capped to
     * the whole delivered material rather than to a share of it.
     *
     * <p>The condenser of a total-condenser branch is the clean case of both: it has no vapour at all, so its
     * material row is simply the vapour tray one sends up against the liquid it keeps, and there is no
     * equilibrium ratio at the node to split anything with.</p>
     */
    @Test
    void aStructurallyAbsentPhaseStaysZeroAndAOnePhasePointTakesTheWholeInflow() {
        V3ColumnProblem problem = ternary(V3CondenserPhaseBranch.LIQUID_ONLY);
        double[][] liquid = {{18, 12, 1.0}, {5, 5, 2.5e-3}, {35, 65, 6.0e-3}, {35, 65, 1.0e-6},
                {35, 65, 1.0e-6}, {17, 53, 5.0e-7}};
        double[][] vapor = {{0, 0, 0.0}, {18, 12, 2.0e-3}, {18, 12, 4.0e-3}, {18, 12, 1.0e-6},
                {18, 12, 1.0e-6}, {18, 12, 5.0e-7}};
        V3DryMeshState seed = state(problem, liquid, vapor);

        V3DryMeshState lifted = V3ColumnCalculator.liftFloorSupport(problem, thermo(problem, seed), seed);

        assertEquals(vapor[1][TRACE], lifted.liquidFlow(0, TRACE),
                "the condenser keeps exactly the trace tray one sends up");
        for (int component = 0; component < 3; component++) {
            assertEquals(0L, Double.doubleToLongBits(lifted.vaporFlow(0, component)),
                    "a structurally absent phase is never written, not even to a floor");
        }
    }

    /** The topology of one continuation grid of a 40-stage, feed-tray-37 request. */
    private static V3ColumnTopology grid(int stageCount) {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:continuation", "test:ternary",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30.0, 60.0}, 400.0,
                REQUESTED_STAGES, REQUESTED_FEED_TRAY, 250_000.0, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                new V3ColumnSpecification.ReboilerDuty(0.0)));
        V3ColumnInput grid = V3ColumnCalculator.withStageGeometry(input, stageCount);
        assertEquals(stageCount, grid.stageCount());
        return V3ColumnTopology.twoPhase(grid.stageCount(), grid.feedStageNumber());
    }

    /** The manufactured ternary column of {@link V3TruncationNumericsTest}: four trays, feed on tray two. */
    private static V3ColumnProblem ternary(V3CondenserPhaseBranch branch) {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:continuation", "test:ternary",
                new V3ComponentBasis(List.of("component-a", "dormant", "component-b", "trace")),
                new double[] {30.0, 0.0, 60.0, TRACE_FEED}, 400.0, 4, 2, 250_000.0, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                new V3ColumnSpecification.ReboilerDuty(0.0)));
        return V3ColumnProblemResolver.resolve(input, branch);
    }

    private static V3DryMeshState state(V3ColumnProblem problem, double[][] liquid, double[][] vapor) {
        return new V3DryMeshState(problem.topology(), 3, liquid, vapor, V3TruncationNumericsTest.temperatures());
    }

    private static V3TruncationNumericsTest.ManufacturedThermo thermo(V3ColumnProblem problem, V3DryMeshState state) {
        return new V3TruncationNumericsTest.ManufacturedThermo(problem.input().componentBasis(), state);
    }
}

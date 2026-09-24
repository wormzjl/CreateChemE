package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sieve-tray correlation and the pressure-consistent march, on hand-built traffic.
 *
 * <p>The pinned term values come from an independent evaluation of the published equations
 * (TRAY_PRESSURE_METHOD_REVIEW section 5.1) rather than from this implementation, so a change to either the
 * constants or the arithmetic is visible here before it reaches a column.</p>
 */
class V3TrayHydraulicsTest {
    private static final double TOP_PRESSURE_PASCAL = 1_500_000.0;
    private static final double STEAM_MOL_PER_SECOND = 10.0;

    /** 1,200 mol/s of 50 g/mol vapour and 90 kg/s of 750 kg/m3 liquid at 420 K, 200 kPa, in an 8 m column. */
    private static final V3TrayHydraulics.Tray PINNED =
            new V3TrayHydraulics.Tray(1_200.0, 60.0, 420.0, 90.0, 0.12, 650.0, 0.45, 0.0);

    @Test
    void thePinnedTrayReproducesTheIndependentlyEvaluatedTermsAndFloodFraction() {
        V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(8.0);

        assertEquals(651.070213955397, hydraulics.liquidDensityKgPerCubicMetre(PINNED), 1.0e-9);
        V3TrayHydraulics.Terms terms = hydraulics.terms(PINNED, 200_000.0);
        assertEquals(63.89208937396111, terms.dryPascal(), 1.0e-9);
        assertEquals(395.01298527220723, terms.liquidPascal(), 1.0e-9);
        assertEquals(27.638278662191365, terms.residualPascal(), 1.0e-9);
        assertEquals(486.5433533083597, terms.totalPascal(), 1.0e-9);
        assertEquals(0.3310344839483188, terms.floodFraction(), 1.0e-9);
        assertEquals(terms.totalPascal(), hydraulics.dropPascal(PINNED, 200_000.0), 0.0);
        // The bubble residual is the review's small, nearly constant term: about 28 Pa whatever the traffic.
        assertTrue(terms.residualPascal() < 0.1 * terms.totalPascal());
    }

    @Test
    void aTrayWithoutVaporKeepsOnlyItsFrothHeadAndTheBubbleResidual() {
        V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(8.0);
        V3TrayHydraulics.Tray dry = new V3TrayHydraulics.Tray(0.0, 0.0, 420.0, 90.0, 0.12, 650.0, 0.45, 0.0);

        V3TrayHydraulics.Terms terms = hydraulics.terms(dry, 200_000.0);

        assertEquals(0.0, terms.dryPascal(), 0.0);
        assertEquals(584.1506241916667, terms.liquidPascal(), 1.0e-9);
        assertEquals(27.664740479439256, terms.residualPascal(), 1.0e-9);
        assertEquals(611.815364671106, terms.totalPascal(), 1.0e-9);
        // No vapour is no flooding, and the drop no longer depends on the pressure it is evaluated at.
        assertEquals(0.0, terms.floodFraction(), 0.0);
        assertEquals(terms.totalPascal(), hydraulics.dropPascal(dry, 900_000.0), 1.0e-12);
    }

    @Test
    void theDryTermRisesWithTheVaporLoadAndFallsWithTheDiameterAtFixedProperties() {
        V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(8.0);
        double previous = -1.0;
        double previousFlood = -1.0;
        for (double scale : new double[] {0.5, 1.0, 1.5, 2.0, 3.0}) {
            V3TrayHydraulics.Tray tray = new V3TrayHydraulics.Tray(1_200.0 * scale, 60.0 * scale, 420.0,
                    90.0, 0.12, 650.0, 0.45, 0.0);
            V3TrayHydraulics.Terms terms = hydraulics.terms(tray, 200_000.0);
            assertTrue(terms.dryPascal() > previous, "dry drop is monotone in the vapour load");
            assertTrue(terms.floodFraction() > previousFlood, "so is the flooding fraction");
            previous = terms.dryPascal();
            previousFlood = terms.floodFraction();
        }
        // Widening the column at a fixed load falls as the fourth power of the diameter through the hole velocity.
        double wide = V3TrayHydraulics.ofDiameter(16.0).terms(PINNED, 200_000.0).dryPascal();
        double narrow = hydraulics.terms(PINNED, 200_000.0).dryPascal();
        assertEquals(narrow / 16.0, wide, narrow * 1.0e-9);
    }

    @Test
    void theDryTermFallsWithThePressureItIsEvaluatedAtBecauseTheVaporExpands() {
        V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(8.0);
        double low = hydraulics.terms(PINNED, 100_000.0).dryPascal();
        double high = hydraulics.terms(PINNED, 400_000.0).dryPascal();
        assertEquals(low / 4.0, high, low * 1.0e-9, "the dry term is exactly inverse in the pressure");
    }

    @Test
    void aNonPositiveDiameterIsNotASieveTray() {
        assertThrows(IllegalArgumentException.class, () -> V3TrayHydraulics.ofDiameter(0.0));
        assertThrows(IllegalArgumentException.class, () -> V3TrayHydraulics.ofDiameter(Double.NaN));
    }

    /**
     * A column whose trays all carry the same pressure-independent traffic marches to a uniform profile.
     *
     * <p>That is the N-1 interval convention in full: the condenser shares the top tray's pressure, the sump
     * shares the bottom tray's, and only the {@code N-1} intervals between trays carry a drop. The vapour-free
     * traffic is what makes the fixed point exact rather than merely converged, so the equality is arithmetic.</p>
     */
    @Test
    void aConstantDropMarchesToTheUniformProfileOnTheNMinusOneIntervals() {
        V3ColumnProblem problem = problem(List.of());
        V3ColumnTopology topology = problem.topology();
        V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(8.0);
        V3TrayHydraulics.Tray[] trays = new V3TrayHydraulics.Tray[topology.nodeCount()];
        V3TrayHydraulics.Tray uniform = new V3TrayHydraulics.Tray(0.0, 0.0, 420.0, 90.0, 0.12, 650.0, 0.45, 0.0);
        for (int tray = 1; tray <= topology.trayCount(); tray++) trays[tray] = uniform;

        double[] marched = hydraulics.march(problem, trays);
        double drop = hydraulics.dropPascal(uniform, TOP_PRESSURE_PASCAL);

        assertEquals(topology.nodeCount(), marched.length);
        assertEquals(TOP_PRESSURE_PASCAL, marched[topology.condenserNode()], 0.0);
        assertEquals(TOP_PRESSURE_PASCAL, marched[1], 0.0);
        for (int tray = 2; tray <= topology.trayCount(); tray++) {
            assertEquals(TOP_PRESSURE_PASCAL + (tray - 1) * drop, marched[tray], 1.0e-9);
        }
        assertEquals(marched[topology.trayCount()], marched[topology.reboilerNode()], 0.0);
        assertEquals((topology.trayCount() - 1) * drop,
                V3TrayHydraulics.totalDropPascal(topology, marched), 1.0e-9);
        // And the resolved problem accepts exactly that profile, so a solve cannot tell it from a generated one.
        assertEquals(marched[topology.trayCount()],
                V3ColumnProblemResolver.resolve(problem.input(), V3CondenserPhaseBranch.TWO_PHASE, marched)
                        .nodePressurePascal(topology.trayCount()), 0.0);
    }

    @Test
    void theMarchedProfileIsRejectedWhenItDoesNotDescribeTheAuthoredColumn() {
        V3ColumnProblem problem = problem(List.of());
        double[] profile = new double[problem.topology().nodeCount()];
        java.util.Arrays.fill(profile, TOP_PRESSURE_PASCAL);
        V3ColumnInput input = problem.input();

        profile[2] = TOP_PRESSURE_PASCAL - 1.0;
        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE, profile.clone()),
                "a profile may not rise up the column");
        profile[2] = TOP_PRESSURE_PASCAL;
        profile[1] = TOP_PRESSURE_PASCAL + 1.0;
        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE, profile.clone()),
                "the top tray carries the authored top pressure");
        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE,
                        new double[] {TOP_PRESSURE_PASCAL}),
                "a profile of the wrong length is not this column's");
    }

    /**
     * The vapour leaving a tray carries the authored steam fed at or below it plus the free water from above.
     *
     * <p>This is the solver's own water bookkeeping, so the correlation sees exactly the vapour the energy and
     * material balances placed there, and the tray's own free water stays in the liquid where it belongs.</p>
     */
    @Test
    void trayTrafficCarriesTheAuthoredSteamAndTheFreeWaterOfTheTrayAbove() {
        V3ColumnProblem dry = problem(List.of(new V3SteamFeedSpec(4, STEAM_MOL_PER_SECOND, 520.0)));
        boolean[] wet = new boolean[dry.topology().nodeCount()];
        wet[1] = true;
        V3ColumnProblem problem = V3ColumnProblemResolver.withTruncation(dry, dry.truncationSupport(),
                V3WetTraySet.of(dry.topology(), wet));
        double[] freeWater = new double[problem.topology().nodeCount()];
        freeWater[1] = 1.5;
        V3DryMeshState state = new V3DryMeshState(problem.topology(), 2, flows(problem, 3.0), flows(problem, 7.0),
                temperatures(problem, 420.0), freeWater);

        V3TrayHydraulics.Tray[] trays = V3TrayHydraulics.traffic(problem, state, constants());

        // Component vapour is 2 x 7 mol/s everywhere; the sump feed rises through every tray.
        assertEquals(14.0 + STEAM_MOL_PER_SECOND, trays[1].vaporMolarFlowMolPerSecond(), 1.0e-12);
        assertEquals(14.0 + STEAM_MOL_PER_SECOND + 1.5, trays[2].vaporMolarFlowMolPerSecond(), 1.0e-12,
                "tray two also carries the free water tray one shed");
        assertEquals(14.0 * 0.05 + (STEAM_MOL_PER_SECOND + 1.5) * 0.01801528,
                trays[2].vaporMassFlowKgPerSecond(), 1.0e-12);
        assertEquals(1.5, trays[1].freeWaterMolarFlowMolPerSecond(), 0.0);
        assertEquals(0.0, trays[2].freeWaterMolarFlowMolPerSecond(), 0.0);
        // Liquid is hydrocarbon only, on Kay's rule over the registered constants.
        assertEquals(6.0 * 0.05, trays[1].liquidMassFlowKgPerSecond(), 1.0e-12);
        assertEquals(650.0, trays[1].liquidCriticalTemperatureKelvin(), 1.0e-12);
        assertEquals(0.45, trays[1].liquidAcentricFactor(), 1.0e-12);
        assertEquals(420.0, trays[1].temperatureKelvin(), 0.0);
        // The free water joins the liquid volume of its own tray, never the vapour of it.
        V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(8.0);
        assertTrue(hydraulics.terms(trays[1], TOP_PRESSURE_PASCAL).liquidPascal()
                > hydraulics.terms(trays[2], TOP_PRESSURE_PASCAL).liquidPascal());
    }

    @Test
    void aFloodedTrayNamesItsCauseAndItsRemedy() {
        V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(3.0);
        V3TrayHydraulics.Tray heavy = new V3TrayHydraulics.Tray(1_200.0, 60.0, 420.0, 90.0, 0.12, 650.0, 0.45, 0.0);
        V3TrayHydraulics.Terms terms = hydraulics.terms(heavy, 200_000.0);
        assertTrue(terms.floodFraction() > 1.0, () -> Double.toString(terms.floodFraction()));
        V3TrayHydraulicsSummary summary = new V3TrayHydraulicsSummary(3.0, 8_000.0, 1_000.0,
                terms.floodFraction(), 7, true, 0.01, terms.dryPascal(), terms.liquidPascal());
        assertTrue(summary.vaporLimited(), "an orifice-dominated tray is vapour limited");

        String warning = V3TrayHydraulics.floodingWarning(summary);

        assertTrue(warning.contains("tray 8"), warning);
        assertTrue(warning.contains("% of flood"), warning);
        assertTrue(warning.contains("dry") && warning.contains("liquid"), warning);
        assertTrue(warning.contains("vapor load is too high"), warning);
        assertTrue(warning.contains("3.0 m"), warning);
        assertTrue(warning.contains("widen the column"), warning);
        assertTrue(warning.length() <= 240, "the warning fits the bounded advisory contract");
    }

    @Test
    void aSummaryFindsTheWorstTrayItsSplitAndTheMeanDropOverTheIntervals() {
        V3ColumnProblem problem = problem(List.of());
        V3ColumnTopology topology = problem.topology();
        V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(8.0);
        V3TrayHydraulics.Tray[] trays = new V3TrayHydraulics.Tray[topology.nodeCount()];
        trays[1] = new V3TrayHydraulics.Tray(1_200.0, 60.0, 420.0, 90.0, 0.12, 650.0, 0.45, 0.0);
        trays[2] = new V3TrayHydraulics.Tray(2_400.0, 120.0, 420.0, 90.0, 0.12, 650.0, 0.45, 0.0);
        trays[3] = new V3TrayHydraulics.Tray(600.0, 30.0, 420.0, 90.0, 0.12, 650.0, 0.45, 0.0);
        double[] profile = hydraulics.march(problem, trays);

        V3TrayHydraulicsSummary summary = hydraulics.summarise(problem, trays, profile, true, 0.004);

        assertEquals(8.0, summary.columnDiameterMetres(), 0.0);
        assertEquals(2, summary.maximumFloodTray(), "tray one has no interval above it to drop across");
        assertEquals(hydraulics.terms(trays[2], profile[2]).floodFraction(), summary.maximumFloodFraction(), 1.0e-12);
        assertEquals(hydraulics.terms(trays[2], profile[2]).dryPascal(), summary.worstTrayDryPascal(), 1.0e-12);
        assertEquals(hydraulics.terms(trays[2], profile[2]).liquidPascal(), summary.worstTrayLiquidPascal(), 1.0e-12);
        assertEquals(V3TrayHydraulics.totalDropPascal(topology, profile), summary.totalPressureDropPascal(), 1.0e-12);
        assertEquals(summary.totalPressureDropPascal() / 2.0, summary.meanTrayPressureDropPascal(), 1.0e-12);
        assertTrue(summary.correctionApplied());
        assertFalse(summary.floods());
        assertEquals(0.004, summary.residualMismatchFraction(), 0.0);
    }

    @Test
    void theTotalDropMismatchIsNormalisedOnTheMarchedProfileSoAZeroNominalReportsOne() {
        V3ColumnProblem problem = problem(List.of());
        V3ColumnTopology topology = problem.topology();
        double[] nominal = new double[topology.nodeCount()];
        java.util.Arrays.fill(nominal, TOP_PRESSURE_PASCAL);
        double[] marched = nominal.clone();
        for (int tray = 2; tray <= topology.trayCount(); tray++) {
            marched[tray] = TOP_PRESSURE_PASCAL + (tray - 1) * 600.0;
        }
        marched[topology.reboilerNode()] = marched[topology.trayCount()];

        assertEquals(1.0, V3TrayHydraulics.totalDropMismatch(topology, nominal, marched), 1.0e-12);
        assertEquals(0.0, V3TrayHydraulics.totalDropMismatch(topology, marched, marched), 0.0);
        double[] half = V3TrayHydraulics.halfway(topology, nominal, marched);
        assertEquals(0.5, V3TrayHydraulics.totalDropMismatch(topology, half, marched), 1.0e-12);
        assertEquals(half[topology.trayCount()], half[topology.reboilerNode()], 0.0);
    }

    private static V3TrayHydraulics.ComponentConstants constants() {
        return new V3TrayHydraulics.ComponentConstants(new double[] {0.05, 0.05}, new double[] {750.0, 750.0},
                new double[] {650.0, 650.0}, new double[] {0.45, 0.45});
    }

    private static V3ColumnProblem problem(List<V3SteamFeedSpec> steam) {
        return V3ColumnProblemResolver.resolve(new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured",
                "test:binary", new V3ComponentBasis(List.of("component-a", "component-b")),
                new double[] {30.0, 60.0}, 400.0, 3, 2, TOP_PRESSURE_PASCAL, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)), List.of(), steam),
                V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static double[][] flows(V3ColumnProblem problem, double perComponent) {
        double[][] flows = new double[problem.topology().nodeCount()][2];
        for (double[] node : flows) java.util.Arrays.fill(node, perComponent);
        return flows;
    }

    private static double[] temperatures(V3ColumnProblem problem, double kelvin) {
        double[] temperatures = new double[problem.topology().nodeCount()];
        java.util.Arrays.fill(temperatures, kelvin);
        return temperatures;
    }
}

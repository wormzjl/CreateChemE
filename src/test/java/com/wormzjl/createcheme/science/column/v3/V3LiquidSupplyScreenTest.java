package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The request-only liquid-supply screen: a specification whose side draws outrun the liquid that can reach
 * their trays is typed {@code INFEASIBLE_SPECIFICATION} before any flash.
 *
 * <p>Every case here is arithmetic on the authored request, so the expected {@code rho} is written out rather
 * than measured. The synthetic column has four trays, feeds tray 3 and draws on tray 2, so the draw sits above
 * the feed and its only supply is the reflux {@code R * D_max} plus whatever authored cooling condenses above
 * it: {@code rho = d / (R * (F - d))} with {@code F = 100} and {@code R = 0.5}.</p>
 */
class V3LiquidSupplyScreenTest {
    private static final double FEED_MOL_PER_SECOND = 100.0;
    private static final double REFLUX_RATIO = 0.5;
    private static final int DRAW_TRAY = 2;

    /**
     * The calibrated tier fires on a request the solver has never solved, and says what it measured.
     *
     * <p>{@code d = 20} gives {@code rho = 20 / (0.5 * 80) = 0.5}, between the calibrated 0.30 and the
     * necessary 1.0, so only the empirical envelope can reject it — and the detail must say so.</p>
     */
    @Test
    void theCalibratedTierFiresAndNamesTheRatioTheTrayAndTheThreshold() {
        V3ColumnInput input = input(20.0, List.of());
        assertEquals(0.5, V3LiquidSupplyScreen.evaluate(input).ratio(), 1.0e-12);

        V3ColumnOutcome.Failure failure = failure(V3ColumnCalculator.calculate(input));

        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure.code());
        assertTrue(failure.summary().contains("0.5000 of"), failure::summary);
        assertTrue(failure.summary().contains("tray " + DRAW_TRAY), failure::summary);
        assertTrue(failure.summary().contains("0.3000"), failure::summary);
        assertTrue(failure.summary().contains("demonstrated liquid-supply envelope"), failure::summary);
        assertFalse(failure.summary().contains("no liquid balance closes"),
                "an empirical envelope must not be published as a closed balance");
    }

    /** The learned route must publish the identical verdict: the screen is a property of the request. */
    @Test
    void theLearnedRouteIsScreenedAtTheSameEntryAsTheClassicalOne() {
        V3ColumnInput input = input(20.0, List.of());

        V3ColumnOutcome.Failure classical = failure(V3ColumnCalculator.calculate(input));
        V3ColumnOutcome.Failure learned = failure(V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED,
                0.0, 0.0, lnnOnly(), V3NeuralInitializer.UNAVAILABLE,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));

        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, learned.code());
        assertEquals(classical.summary(), learned.summary());
        assertEquals(classical.diagnostics().solvePath(), learned.diagnostics().solvePath());
    }

    /**
     * {@code rho >= 1} is physics, not calibration: the cumulative withdrawal exceeds a supply bound that is
     * already generous in every term, so disabling the calibrated tier cannot make the request solvable.
     *
     * <p>{@code d = 40} gives {@code rho = 40 / (0.5 * 60) = 1.3333}.</p>
     */
    @Test
    void theNecessaryTierFiresEvenWithTheCalibratedRatioDisabled() {
        V3ColumnInput input = input(40.0, List.of());
        assertEquals(4.0 / 3.0, V3LiquidSupplyScreen.evaluate(input).ratio(), 1.0e-12);

        V3ColumnOutcome.Failure failure = failure(
                V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 0.0, 0.0, 0.0));

        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure.code());
        assertTrue(failure.summary().contains("1.333 of"), failure::summary);
        assertTrue(failure.summary().contains("no liquid balance closes at or above 1.000"), failure::summary);
        assertEquals(failure.summary(), failure(V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED,
                0.0, 0.0, lnnOnly(), V3NeuralInitializer.UNAVAILABLE, 0.0)).summary());
    }

    /**
     * Zero really is the off switch for the calibrated tier.
     *
     * <p>The project intends to learn to solve exactly these draw-wall specifications, so a research probe has
     * to be able to hand one to the raw solver. The learned route with no model reaches its own
     * {@code INITIALIZATION_FAILURE} without any classical solve, which is enough to show the screen let the
     * request through rather than typing it.</p>
     */
    @Test
    void aDisabledCalibratedTierAdmitsARequestBetweenTheTwoThresholds() {
        V3ColumnInput input = input(20.0, List.of());
        assertTrue(V3LiquidSupplyScreen.evaluate(input).fires(V3LiquidSupplyScreen.DEFAULT_CALIBRATED_RATIO));
        assertFalse(V3LiquidSupplyScreen.evaluate(input).fires(0.0));

        V3ColumnOutcome.Failure admitted = failure(V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED,
                0.0, 0.0, lnnOnly(), V3NeuralInitializer.UNAVAILABLE, 0.0));

        assertNotEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, admitted.code());
        assertEquals(V3SolverFailureCode.INITIALIZATION_FAILURE, admitted.code());
        assertFalse(admitted.summary().contains("liquid that reflux"), admitted::summary);
    }

    /**
     * No thermodynamics may precede the verdict.
     *
     * <p>The request names a property package that was never registered, so any flash, enthalpy or envelope
     * evaluation on the way to the answer would have produced a property failure instead. It publishes the
     * screen's verdict on the admission path with zero Newton iterations.</p>
     */
    @Test
    void theVerdictPrecedesEveryThermodynamicEvaluation() {
        V3ColumnInput unregistered = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION,
                "test:package_that_was_never_registered", "test:baseline",
                new V3ComponentBasis(List.of("methane", "n-pentane")), new double[] {40.0, 60.0}, 450.0, 4, 3,
                250_000.0, 750.0, specifications(), List.of(new V3SideDrawSpec(DRAW_TRAY, 20.0)));

        V3ColumnOutcome.Failure failure = failure(V3ColumnCalculator.calculate(unregistered));

        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure.code());
        assertEquals("input/liquid-supply-1", failure.diagnostics().solvePath());
        assertEquals(0, failure.diagnostics().newtonIterations(), "no solve may precede a request-only verdict");
        assertEquals(0, failure.diagnostics().residualEvaluations());
        assertEquals(0, failure.diagnostics().linearSolves());
    }

    /**
     * Authored cooling above a draw condenses vapour into liquid the draw can take, so it raises the supply.
     *
     * <p>The same {@code d = 20} request that fires without a pumparound is admitted once 1.5 MW of cooling is
     * authored above the draw: at 30 kJ/mol that is 50 mol/s of extra liquid on tray 1, so the supply rises
     * from 40 to 90 mol/s and {@code rho} falls from 0.5 to 0.2222.</p>
     */
    @Test
    void authoredCoolingAboveTheDrawRaisesTheSupplyAndAdmitsTheRequest() {
        double coolingWatts = 1_500_000.0;
        V3ColumnInput without = input(20.0, List.of());
        V3ColumnInput cooled = input(20.0,
                List.of(new V3PumparoundSpec(1, DRAW_TRAY, -coolingWatts, V3PumparoundSpec.Split.RETURN_TRAY)));

        double expected = 20.0 / (REFLUX_RATIO * (FEED_MOL_PER_SECOND - 20.0)
                + coolingWatts / V3LiquidSupplyScreen.PUMPAROUND_LATENT_HEAT_JOULES_PER_MOL);
        assertEquals(0.5, V3LiquidSupplyScreen.evaluate(without).ratio(), 1.0e-12);
        assertEquals(expected, V3LiquidSupplyScreen.evaluate(cooled).ratio(), 1.0e-12);
        assertTrue(expected < V3LiquidSupplyScreen.DEFAULT_CALIBRATED_RATIO,
                () -> String.format(Locale.ROOT, "cooled ratio %.4f must clear the calibrated threshold", expected));

        assertEquals(V3SolverFailureCode.INFEASIBLE_SPECIFICATION, failure(V3ColumnCalculator.calculate(without)).code());
        V3ColumnOutcome.Failure admitted = failure(V3ColumnCalculator.calculate(cooled, V3SolveControl.UNBOUNDED,
                0.0, 0.0, lnnOnly(), V3NeuralInitializer.UNAVAILABLE,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        assertFalse(admitted.summary().contains("liquid that reflux"), admitted::summary);
    }

    /** A request with no side draws has nothing to screen, whatever else it authors. */
    @Test
    void aRequestWithoutSideDrawsIsNeverScreened() {
        V3ColumnInput drawless = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:ideal_binary", "test:baseline",
                new V3ComponentBasis(List.of("methane", "n-pentane")), new double[] {40.0, 60.0}, 450.0, 4, 3,
                250_000.0, 750.0, specifications(), List.of());

        assertEquals(V3LiquidSupplyScreen.Verdict.NONE, V3LiquidSupplyScreen.evaluate(drawless));
        assertFalse(V3LiquidSupplyScreen.evaluate(drawless).fires(V3LiquidSupplyScreen.DEFAULT_CALIBRATED_RATIO));
        assertFalse(V3LiquidSupplyScreen.evaluate(drawless).fires(0.0));
    }

    /** The authored ratio is revalidated at every entry that accepts it, exactly as the cutoff and closure are. */
    @Test
    void anOutOfRangeScreenRatioIsRejected() {
        V3ColumnInput input = input(20.0, List.of());
        for (double invalid : new double[] {-Double.MIN_VALUE, Math.nextUp(1.0), Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 0.0, 0.0, invalid));
            assertThrows(IllegalArgumentException.class, () -> V3ColumnCalculator.calculate(input,
                    V3SolveControl.UNBOUNDED, 0.0, 0.0, lnnOnly(), V3NeuralInitializer.UNAVAILABLE, invalid));
        }
    }

    private static V3ColumnOutcome.Failure failure(V3ColumnOutcome outcome) {
        return assertInstanceOf(V3ColumnOutcome.Failure.class, outcome, outcome::toString);
    }

    private static V3InitializationOptions lnnOnly() {
        return new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY,
                V3InitializationOptions.WetStart.AUTO, 8, 500);
    }

    private static List<V3ColumnSpecification> specifications() {
        return List.of(new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(REFLUX_RATIO),
                new V3ColumnSpecification.ReboilerDuty(8_000_000.0));
    }

    /** Four trays, feed on tray 3, one draw on tray 2 so the draw is supplied by reflux and cooling only. */
    private static V3ColumnInput input(double drawMolPerSecond, List<V3PumparoundSpec> pumparounds) {
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:ideal_binary", "test:baseline",
                new V3ComponentBasis(List.of("methane", "n-pentane")), new double[] {40.0, 60.0}, 450.0, 4, 3,
                250_000.0, 750.0, specifications(), List.of(new V3SideDrawSpec(DRAW_TRAY, drawMolPerSecond)),
                List.of(), pumparounds);
    }
}

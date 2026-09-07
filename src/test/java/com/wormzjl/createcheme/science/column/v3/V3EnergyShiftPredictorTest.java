package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The frozen-flow energy/temperature predictor: its tridiagonal system on a hand-built column, and its effect
 * on the first heat rung of the source three-cooler arrangement.
 */
class V3EnergyShiftPredictorTest {
    private static final long BUDGET_NANOS = 180_000_000_000L;
    private static final Pattern FIRST_RUNG =
            Pattern.compile("scaled energy ([0-9.eE+-]+) -> ([0-9.eE+-]+)");

    /**
     * Three trays and a sump with manufactured heat capacities, one of them behind a side draw.
     *
     * <p>The Jacobian is written out here independently of {@link V3EnergyShiftPredictor#assemble}, a shift is
     * chosen, and the energy residual that shift would close is computed from it. The predictor has to recover
     * the chosen shift exactly.</p>
     */
    @Test
    void theTridiagonalSystemReproducesAKnownTemperatureShiftOnAHandBuiltColumn() {
        double[] liquidSlope = {40_000.0, 45_000.0, 50_000.0, 55_000.0};
        double[] vaporSlope = {60_000.0, 65_000.0, 70_000.0, 20_000.0};
        double[] retainedLiquid = {1.0, 0.8, 1.0, 0.0};
        double[] expected = {-6.0, -9.0, -12.0, -3.0};
        double[][] jacobian = independentJacobian(liquidSlope, vaporSlope, retainedLiquid);
        double[] energyResidual = new double[expected.length];
        for (int row = 0; row < expected.length; row++) {
            double sum = 0.0;
            for (int column = 0; column < expected.length; column++) sum += jacobian[row][column] * expected[column];
            energyResidual[row] = -sum;
        }

        V3EnergyShiftPredictor.TemperatureSystem system = V3EnergyShiftPredictor.assemble(
                liquidSlope, vaporSlope, retainedLiquid, energyResidual);

        for (int row = 0; row < expected.length; row++) {
            assertEquals(jacobian[row][row], system.diagonal()[row], 1.0e-9, "diagonal " + row);
            assertEquals(row == 0 ? 0.0 : jacobian[row][row - 1], system.lower()[row], 1.0e-9, "lower " + row);
            assertEquals(row == expected.length - 1 ? 0.0 : jacobian[row][row + 1], system.upper()[row], 1.0e-9,
                    "upper " + row);
            assertEquals(-energyResidual[row], system.rightHandSide()[row], 1.0e-9, "right-hand side " + row);
        }
        assertArrayEquals(expected, V3EnergyShiftPredictor.solveTridiagonal(system), 1.0e-9);
    }

    @Test
    void theShiftIsScaledToTheTrayBoundWithoutChangingItsDirection() {
        double[] within = {-30.0, 12.5, 40.0, 0.0};
        double[] beyond = {-80.0, 20.0, -10.0, 0.0};

        assertEquals(40.0, V3EnergyShiftPredictor.MAXIMUM_SHIFT_KELVIN);
        assertArrayEquals(within,
                V3EnergyShiftPredictor.clamped(within, V3EnergyShiftPredictor.MAXIMUM_SHIFT_KELVIN), 0.0,
                "a shift already inside the bound is untouched");
        double[] bounded = V3EnergyShiftPredictor.clamped(beyond, V3EnergyShiftPredictor.MAXIMUM_SHIFT_KELVIN);
        assertArrayEquals(new double[] {-40.0, 10.0, -5.0, 0.0}, bounded, 1.0e-12);
        for (int index = 0; index < beyond.length; index++) {
            // Scaling, not truncation: every entry keeps its share of the direction.
            assertEquals(0.5 * beyond[index], bounded[index], 1.0e-12, "entry " + index);
            assertTrue(Math.abs(bounded[index]) <= V3EnergyShiftPredictor.MAXIMUM_SHIFT_KELVIN, "entry " + index);
        }
    }

    /**
     * With a direction-preserving bound the linear model's energy residual falls from {@code E} to
     * {@code (1 - t) E}, so a bounded prediction can never make its own rows worse in the model it was
     * derived from. Truncating each entry instead would break that, which is why the bound scales.
     */
    @Test
    void aBoundedShiftStillReducesTheLinearisedEnergyResidual() {
        double[] liquidSlope = {40_000.0, 45_000.0, 50_000.0, 55_000.0};
        double[] vaporSlope = {60_000.0, 65_000.0, 70_000.0, 20_000.0};
        double[] retainedLiquid = {1.0, 0.8, 1.0, 0.0};
        double[] energyResidual = {-9.0e6, -9.2e6, -9.4e6, -2.0e6};
        double[][] jacobian = independentJacobian(liquidSlope, vaporSlope, retainedLiquid);

        double[] full = V3EnergyShiftPredictor.solveTridiagonal(V3EnergyShiftPredictor.assemble(
                liquidSlope, vaporSlope, retainedLiquid, energyResidual));
        double[] bounded = V3EnergyShiftPredictor.clamped(full, V3EnergyShiftPredictor.MAXIMUM_SHIFT_KELVIN);
        double largest = 0.0;
        for (double value : full) largest = Math.max(largest, Math.abs(value));
        double fraction = Math.min(1.0, V3EnergyShiftPredictor.MAXIMUM_SHIFT_KELVIN / largest);

        for (int row = 0; row < energyResidual.length; row++) {
            double linearised = energyResidual[row];
            for (int column = 0; column < energyResidual.length; column++) {
                linearised += jacobian[row][column] * bounded[column];
            }
            assertEquals((1.0 - fraction) * energyResidual[row], linearised, 1.0e-6, "row " + row);
            assertTrue(Math.abs(linearised) <= Math.abs(energyResidual[row]) + 1.0e-6, "row " + row);
        }
    }

    @Test
    void aSingularSystemIsRefusedRatherThanReturningAnArbitraryShift() {
        double[] noSlope = {0.0, 0.0, 0.0};
        V3EnergyShiftPredictor.TemperatureSystem system = V3EnergyShiftPredictor.assemble(
                noSlope, noSlope, new double[] {1.0, 1.0, 0.0}, new double[] {1.0, 1.0, 1.0});

        IllegalArgumentException singular = assertThrows(IllegalArgumentException.class,
                () -> V3EnergyShiftPredictor.solveTridiagonal(system));
        assertTrue(singular.getMessage().contains("singular"), singular::getMessage);
    }

    @Test
    void theCommonModeSignatureNeedsMoreThanHalfTheRowsOnOneSide() {
        assertTrue(V3EnergyShiftPredictor.hasCommonModeSignature(new double[] {-1.0, -1.0, -1.0, 2.0}));
        assertTrue(V3EnergyShiftPredictor.hasCommonModeSignature(new double[] {1.0, 1.0, -3.0}));
        assertFalse(V3EnergyShiftPredictor.hasCommonModeSignature(new double[] {-1.0, 1.0, -1.0, 1.0}));
        assertFalse(V3EnergyShiftPredictor.hasCommonModeSignature(new double[] {0.0, 0.0, 0.0, 0.0}));
    }

    /**
     * The source three-cooler arrangement without side draws is the case whose heat ramp stalls repeatedly at
     * 0.34 to 0.5 of the duty with every energy row short by the same amount. The predictor has to reduce the
     * scaled energy residual of the first heat rung it sees, and the whole case has to converge.
     */
    @Test
    void thePredictorReducesTheFirstHeatRungResidualOfTheThreeCoolerArrangement() {
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(threeCoolerNoDrawInput(), () -> {
            if (System.nanoTime() - started >= BUDGET_NANOS) {
                throw new AssertionError("the three-cooler no-draw case exceeded its cold budget");
            }
        });
        System.out.println("Three-cooler no-draw case: " + (System.nanoTime() - started) / 1e9 + " s; "
                + outcome.diagnostics().solvePath() + "; " + outcome.diagnostics().events());

        String event = outcome.diagnostics().events().stream()
                .filter(candidate -> candidate.startsWith("energy-shift predictor:")).findFirst()
                .orElseThrow(() -> new AssertionError("no energy-shift predictor event: "
                        + outcome.diagnostics().events()));
        Matcher matcher = FIRST_RUNG.matcher(event);
        assertTrue(matcher.find(), () -> "the predictor event must report its first rung: " + event);
        double before = Double.parseDouble(matcher.group(1));
        double after = Double.parseDouble(matcher.group(2));
        assertTrue(after < before, () -> "the predictor must reduce the first heat rung's scaled energy residual: "
                + event);
        assertTrue(event.contains("applied="), event);

        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertEquals(-41.93e6, success.result().dutyLedger().orElseThrow().stageHeatTotalWatts(), 1.0);
    }

    /** Row-by-row form of the three documented derivatives, written independently of the production assembly. */
    private static double[][] independentJacobian(
            double[] liquidSlope, double[] vaporSlope, double[] retainedLiquid) {
        int size = liquidSlope.length;
        double[][] jacobian = new double[size][size];
        for (int row = 0; row < size; row++) {
            jacobian[row][row] = -(liquidSlope[row] + vaporSlope[row]);
            if (row > 0) jacobian[row][row - 1] = retainedLiquid[row - 1] * liquidSlope[row - 1];
            if (row < size - 1) jacobian[row][row + 1] = vaporSlope[row + 1];
        }
        return jacobian;
    }

    /** The source arrangement on the 30-tray CDU17 column at the source operating point, without side draws. */
    private static V3ColumnInput threeCoolerNoDrawInput() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), List.of(), List.of(), List.of(
                        new V3PumparoundSpec(6, 8, -12.84e6, V3PumparoundSpec.Split.UNIFORM),
                        new V3PumparoundSpec(13, 15, -17.89e6, V3PumparoundSpec.Split.UNIFORM),
                        new V3PumparoundSpec(20, 22, -11.20e6, V3PumparoundSpec.Split.UNIFORM)));
    }
}

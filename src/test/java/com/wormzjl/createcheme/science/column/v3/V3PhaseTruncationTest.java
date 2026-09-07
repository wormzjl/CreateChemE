package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Per-phase stage support: a point can be BOTH, LIQUID_ONLY, VAPOR_ONLY or ABSENT.
 *
 * <p>The fixture is the manufactured ternary column of {@link V3TruncationNumericsTest} with a hand-built
 * trace profile that produces all four states at once: the trace is ABSENT at the condenser and below tray
 * three, VAPOR_ONLY on tray one, BOTH on the feed tray and LIQUID_ONLY on tray three.</p>
 */
class V3PhaseTruncationTest {
    private static final double TRACE_FEED = 0.01;
    private static final int TRACE = 2;

    @Test
    void oneDecidingStateProducesAllFourPhaseStatesAndCountsThemSeparately() {
        Fixture fixture = onePhaseFixture();
        V3TruncationSupport support = fixture.support();

        assertEquals(V3TruncationSupport.PointPhases.ABSENT, support.pointPhases(0, TRACE));
        assertEquals(V3TruncationSupport.PointPhases.VAPOR_ONLY, support.pointPhases(1, TRACE));
        assertEquals(V3TruncationSupport.PointPhases.BOTH, support.pointPhases(2, TRACE));
        assertEquals(V3TruncationSupport.PointPhases.LIQUID_ONLY, support.pointPhases(3, TRACE));
        assertEquals(V3TruncationSupport.PointPhases.ABSENT, support.pointPhases(4, TRACE));
        assertEquals(V3TruncationSupport.PointPhases.ABSENT, support.pointPhases(5, TRACE));
        for (int node = 0; node < 6; node++) {
            for (int component = 0; component < TRACE; component++) {
                assertEquals(V3TruncationSupport.PointPhases.BOTH, support.pointPhases(node, component));
            }
        }
        assertEquals(3, support.truncatedPointCount());
        assertEquals(2, support.onePhasePointCount());
        assertEquals(15, support.retainedPointCount());
        // Two phases per point everywhere, less two for each removed point and one for each one-phase point.
        assertEquals(2 * 18 - 2 * 3 - 2, support.presentPhaseCount());
        assertEquals(0, support.closurePrunedCount());
        assertFalse(support.isIdentity());
        assertTrue(support.retains(1, TRACE) && support.retains(3, TRACE));
        assertTrue(support.retainsVapor(1, TRACE) && !support.retainsLiquid(1, TRACE));
        assertTrue(support.retainsLiquid(3, TRACE) && !support.retainsVapor(3, TRACE));
        assertThrows(IndexOutOfBoundsException.class, () -> support.pointPhases(6, 0));
    }

    /**
     * The only presence queries the package may use. A one-phase point keeps its material row and its one
     * flow unknown and loses its equilibrium row, so the reduced ledger stays square.
     */
    @Test
    void aOnePhasePointKeepsItsMaterialRowAndLosesItsEquilibriumRow() {
        Fixture fixture = onePhaseFixture();
        V3ColumnProblem problem = fixture.problem();
        V3DegreeOfFreedomLedger ledger = problem.degreeOfFreedomLedger();

        assertTrue(problem.hasVaporUnknown(1, TRACE) && !problem.hasLiquidUnknown(1, TRACE));
        assertTrue(problem.hasLiquidUnknown(3, TRACE) && !problem.hasVaporUnknown(3, TRACE));
        assertFalse(problem.hasEquilibriumRow(1, TRACE) || problem.hasEquilibriumRow(3, TRACE));
        assertTrue(problem.hasEquilibriumRow(2, TRACE));
        assertTrue(ledger.isValid(), ledger::humanReadableDiagnostic);
        assertEquals(List.of(
                new V3DegreeOfFreedomLedger.UnknownId(V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, 1, TRACE),
                new V3DegreeOfFreedomLedger.UnknownId(V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 2, TRACE),
                new V3DegreeOfFreedomLedger.UnknownId(V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, 2, TRACE),
                new V3DegreeOfFreedomLedger.UnknownId(V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 3, TRACE)),
                traceUnknowns(ledger));
        assertEquals(traceEquations(ledger), List.of(
                new V3DegreeOfFreedomLedger.EquationId(
                        V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE, 1, TRACE),
                new V3DegreeOfFreedomLedger.EquationId(
                        V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE, 2, TRACE),
                new V3DegreeOfFreedomLedger.EquationId(
                        V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM, 2, TRACE),
                new V3DegreeOfFreedomLedger.EquationId(
                        V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE, 3, TRACE)));
        // The tray-three material row sees its own liquid and the liquid arriving from the feed tray; the
        // vapour it would have received from tray four is gone with that removed point.
        assertEquals(List.of(
                new V3DegreeOfFreedomLedger.UnknownId(V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 2, TRACE),
                new V3DegreeOfFreedomLedger.UnknownId(V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW, 3, TRACE)),
                references(ledger, V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE, 3));
        assertEquals(List.of(
                new V3DegreeOfFreedomLedger.UnknownId(V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, 1, TRACE),
                new V3DegreeOfFreedomLedger.UnknownId(V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, 2, TRACE)),
                references(ledger, V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE, 1));
    }

    /** Mass is never lost at a one-phase point: the whole component leaves in the phase that is present. */
    @Test
    void aOnePhasePointConservesItsComponentIntoThePhaseThatIsPresent() {
        Fixture fixture = onePhaseFixture();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(fixture.problem(), fixture.thermo(), 0.0);
        V3MeshResidual residual = evaluator.evaluate(fixture.state(), fixture.thermo().newWorkspace());
        V3DryMeshState state = fixture.state();

        // Tray three is LIQUID_ONLY: liquid in from the feed tray, no vapour in from the removed tray four,
        // and the whole component leaves in the liquid.
        assertEquals(state.liquidFlow(2, TRACE) - state.liquidFlow(3, TRACE),
                physical(residual, 3, TRACE), 1.0e-18);
        // Tray one is VAPOR_ONLY: the vapour arriving from the feed tray leaves as vapour, and no reflux
        // liquid arrives because the condenser point is removed.
        assertEquals(state.vaporFlow(2, TRACE) - state.vaporFlow(1, TRACE),
                physical(residual, 1, TRACE), 1.0e-18);
        assertEquals(0L, Double.doubleToLongBits(state.vaporFlow(3, TRACE)));
        assertEquals(0L, Double.doubleToLongBits(state.liquidFlow(1, TRACE)));
        assertTrue(residual.rows().stream().noneMatch(row -> row.equation().component() == TRACE
                && row.equation().family() == V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM
                && row.equation().node() != 2));
    }

    @Test
    void coordinateMapRoundTripsAStateWithAOnePhasePoint() {
        Fixture fixture = onePhaseFixture();
        V3DryMeshCoordinateMap map = new V3DryMeshCoordinateMap(fixture.problem());
        V3DryMeshState decoded = map.decode(map.encode(fixture.state()));

        assertEquals(fixture.problem().degreeOfFreedomLedger().unknownCount(), map.coordinateCount());
        for (int node = 0; node < 6; node++) {
            for (int component = 0; component < 3; component++) {
                assertEquals(fixture.state().liquidFlow(node, component), decoded.liquidFlow(node, component),
                        1.0e-14 * fixture.state().liquidFlow(node, component), "liquid " + node + "/" + component);
                assertEquals(fixture.state().vaporFlow(node, component), decoded.vaporFlow(node, component),
                        1.0e-14 * fixture.state().vaporFlow(node, component), "vapor " + node + "/" + component);
            }
            assertEquals(fixture.state().temperatureKelvin(node), decoded.temperatureKelvin(node));
        }
        assertEquals(0L, Double.doubleToLongBits(decoded.vaporFlow(3, TRACE)));
        assertEquals(0L, Double.doubleToLongBits(decoded.liquidFlow(1, TRACE)));
    }

    /**
     * The production tri-block Jacobian must see exactly the unknown set the ledger publishes. A one-phase
     * point contributes one column and one material row; a wrong entry here is invisible to the residual and
     * shows up only as rejected Newton directions.
     */
    @Test
    void theLocalBlockJacobianMatchesTheUncoloredOracleWithOnePhasePoints() {
        Fixture fixture = onePhaseFixture();
        V3ColumnProblem problem = fixture.problem();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, fixture.thermo(), 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        V3DryMeshState state = perturb(coordinates, fixture.state());

        for (V3FiniteDifferenceJacobian.DifferenceScale scale : V3FiniteDifferenceJacobian.DifferenceScale.values()) {
            double[][] full = V3FiniteDifferenceJacobian.evaluate(evaluator, coordinates, state,
                    fixture.thermo()::newWorkspace, scale).values();
            V3BandedMatrix banded = V3BlockJacobianAssembler.assembleLocal(problem, evaluator, coordinates, state,
                    fixture.thermo()::newWorkspace, scale, V3SolveControl.UNBOUNDED).toBandedMatrix();
            for (int row = 0; row < full.length; row++) {
                for (int column = 0; column < full.length; column++) {
                    assertEquals(full[row][column], banded.get(row, column),
                            1.0e-6 * Math.max(1.0, Math.abs(full[row][column])), "row/column " + row + "/" + column);
                }
            }
        }
    }

    /**
     * A one-phase point has no sink edge, so {@code TRUNCATION_MASS_DEFECT} says nothing about it and
     * {@code PHASE_TRUNCATION_DEFECT} bounds the flow its equilibrium row would give the absent phase.
     */
    @Test
    void phaseDefectBoundsTheEquilibriumImpliedAbsentFlowAndFiniteTopologyAcceptsItsExactZeros() {
        Fixture fixture = onePhaseFixture();
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), fixture.thermo(), 0.0)
                .audit(fixture.state(), fixture.thermo().newWorkspace());

        assertTrue(check(audit, "FINITE_TOPOLOGY").passed(), () -> audit.checks().toString());
        V3AcceptanceAudit.Check phaseDefect = check(audit, "PHASE_TRUNCATION_DEFECT");
        assertEquals(2.0e-9, phaseDefect.limit(), 1.0e-24);
        // The manufactured equilibrium ratios come from this very state, so the implied absent flows are the
        // 1e-15 mol/s the fixture put there: 1e-13 of the trace feed, well inside the bound.
        assertTrue(phaseDefect.passed(), phaseDefect::toString);
        assertTrue(phaseDefect.value() > 0.0 && phaseDefect.value() < 1.0e-11, phaseDefect::toString);
        assertEquals(0.008, check(audit, "TRUNCATION_MASS_DEFECT").value()
                * fixture.problem().activeComponentBasis().totalFeedFlowMolPerSecond(), 1.0e-15);
    }

    @Test
    void phaseDefectRejectsAnAbsentPhaseTheEquilibriumRowWouldFill() {
        Fixture fixture = onePhaseFixture();
        double[][] liquid = V3TruncationNumericsTest.copyFlows(fixture.state(), true);
        double[][] vapor = V3TruncationNumericsTest.copyFlows(fixture.state(), false);
        // Multiply the LIQUID_ONLY point's liquid by 1e6 without changing its equilibrium ratio: the vapour
        // the row would give it grows by the same factor and leaves the reinsertion threshold behind.
        liquid[3][TRACE] *= 1.0e6;
        V3DryMeshState inflated = new V3DryMeshState(fixture.problem().topology(), 3, liquid, vapor,
                V3TruncationNumericsTest.temperatures());
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), fixture.thermo(), 0.0)
                .audit(inflated, fixture.thermo().newWorkspace());

        V3AcceptanceAudit.Check phaseDefect = check(audit, "PHASE_TRUNCATION_DEFECT");
        assertFalse(phaseDefect.passed(), phaseDefect::toString);
        assertTrue(phaseDefect.value() > phaseDefect.limit(), phaseDefect::toString);
        assertFalse(audit.accepted());
    }

    /** The floor may thin a node's phase but never empty it, and a truncation-free audit keeps its checks. */
    @Test
    void aNodeNeverLosesAStructuralPhaseToTheFloorAndAnIdentitySupportAddsNoCheck() {
        V3ColumnProblem original = original();
        V3DryMeshState exact = exactState(original);
        double[][] liquid = V3TruncationNumericsTest.copyFlows(exact, true);
        double[][] vapor = V3TruncationNumericsTest.copyFlows(exact, false);
        // Tray three's liquid collapses below the floor in every component that has one at all.
        for (int component = 0; component < 3; component++) liquid[3][component] *= 1.0e-12;
        V3DryMeshState collapsed = new V3DryMeshState(original.topology(), 3, liquid, vapor,
                V3TruncationNumericsTest.temperatures());
        V3TruncationSupport support = V3TruncationSupport.derive(original, 0.0, collapsed);

        for (int component = 0; component < TRACE; component++) {
            assertTrue(support.retainsLiquid(3, component), "tray three keeps a liquid unknown for " + component);
            assertEquals(V3TruncationSupport.PointPhases.BOTH, support.pointPhases(3, component));
        }
        assertEquals(0, support.onePhasePointCount());
        // A support with removed points but no one-phase point keeps exactly the checks it had before.
        V3TruncationNumericsTest.Fixture pointOnly =
                V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        assertEquals(0, pointOnly.problem().truncationSupport().onePhasePointCount());
        assertTrue(pointOnly.problem().truncationSupport().truncatedPointCount() > 0);
        V3AcceptanceAudit pointOnlyAudit = new V3AcceptanceAuditor(pointOnly.problem(), pointOnly.thermo(), 0.0)
                .audit(pointOnly.exact(), pointOnly.thermo().newWorkspace());
        assertTrue(pointOnlyAudit.checks().stream().anyMatch(check -> check.family().equals("TRUNCATION_MASS_DEFECT")));
        assertTrue(pointOnlyAudit.checks().stream().noneMatch(check -> check.family().equals("PHASE_TRUNCATION_DEFECT")));
    }

    /**
     * A trace profile that left the column entirely re-enters over all of its trays in one lift, and every
     * reinserted point arrives with its material row and its equilibrium row already closed.
     *
     * <p>Reading the unlifted state instead made reinsertion a front that advanced one tray per refresh, and
     * splitting the delivered material evenly instead of by the local equilibrium made every reinsertion
     * restart from a scaled residual of two to five.</p>
     */
    @Test
    void aRemovedTraceProfileIsRestoredAcrossAllOfItsTraysInOneSweep() {
        V3ColumnProblem original = original();
        // The trace exists on the feed tray only; the reboiler side and the condenser side are exact zeros.
        double[][] liquid = {{10, 10, 0}, {5, 5, 0}, {35, 65, 0.006}, {35, 65, 0}, {35, 65, 0}, {17, 53, 0}};
        double[][] vapor = {{8, 2, 0}, {18, 12, 0}, {18, 12, 0.004}, {18, 12, 0}, {18, 12, 0}, {18, 12, 0}};
        V3DryMeshState removed = new V3DryMeshState(original.topology(), 3, liquid, vapor,
                V3TruncationNumericsTest.temperatures());
        V3TruncationNumericsTest.ManufacturedThermo thermo =
                new V3TruncationNumericsTest.ManufacturedThermo(original.input().componentBasis(), removed);

        V3DryMeshState lifted = V3ColumnCalculator.liftFloorSupport(original, thermo, removed);

        double floor = TRACE_FEED * V3TruncationSupport.TRACE_FLOOR_FRACTION;
        for (int node = 0; node < 6; node++) {
            assertTrue(lifted.liquidFlow(node, TRACE) >= floor,
                    () -> "liquid restored above the floor on every tray in one sweep");
            assertTrue(lifted.vaporFlow(node, TRACE) >= floor,
                    () -> "vapour restored above the floor on every tray in one sweep");
        }
        // Tray three: the whole liquid the feed tray sends down leaves again, split the way its own
        // equilibrium row would split it. The manufactured ratio of an untouched node is one.
        double delivered = removed.liquidFlow(2, TRACE);
        assertEquals(delivered, lifted.liquidFlow(3, TRACE) + lifted.vaporFlow(3, TRACE), 1.0e-18);
        double vaporTotal = 18.0 + 12.0;
        double liquidTotal = 35.0 + 65.0 + lifted.liquidFlow(3, TRACE);
        assertEquals(vaporTotal / liquidTotal, lifted.vaporFlow(3, TRACE) / lifted.liquidFlow(3, TRACE), 1.0e-3);
        // Tray four is fed only by the value tray three was just lifted to, which is what "in one sweep" means.
        assertEquals(lifted.liquidFlow(3, TRACE),
                lifted.liquidFlow(4, TRACE) + lifted.vaporFlow(4, TRACE), 1.0e-18);
        // The condenser is reached by the upward pass, from the vapour the downward pass gave tray one.
        assertEquals(lifted.vaporFlow(1, TRACE),
                lifted.liquidFlow(0, TRACE) + lifted.vaporFlow(0, TRACE), 1.0e-18);
    }

    /**
     * A one-phase point is lifted by the flow its own equilibrium row would give the absent phase, and only
     * once that reaches {@link V3TruncationSupport#FLOOR_REINSERTION_FACTOR} floors.
     */
    @Test
    void aOnePhasePointIsLiftedOnlyWhenItsImpliedAbsentFlowReachesTenFloors() {
        V3ColumnProblem original = original();
        // Calibration state: the trace is in equilibrium on trays three and four, so the manufactured ratio
        // there is L/V and the implied absent vapour of a liquid-only point is its own liquid flow.
        double[][] calibrationLiquid = {{10, 10, 0}, {5, 5, 0}, {35, 65, 0.006}, {35, 65, 0.004},
                {35, 65, 0.004}, {17, 53, 0}};
        double[][] calibrationVapor = {{8, 2, 0}, {18, 12, 0}, {18, 12, 0.004}, {18, 12, 0.004},
                {18, 12, 0.004}, {18, 12, 0}};
        V3TruncationNumericsTest.ManufacturedThermo thermo =
                new V3TruncationNumericsTest.ManufacturedThermo(original.input().componentBasis(),
                        new V3DryMeshState(original.topology(), 3, calibrationLiquid, calibrationVapor,
                                V3TruncationNumericsTest.temperatures()));
        double floor = TRACE_FEED * V3TruncationSupport.TRACE_FLOOR_FRACTION;
        // Tray three implies 0.004 mol/s of vapour, far above ten floors; tray four implies half a floor.
        double[][] liquid = {{10, 10, 0}, {5, 5, 0}, {35, 65, 0.006}, {35, 65, 0.004},
                {35, 65, 5.0 * floor}, {17, 53, 0}};
        double[][] vapor = {{8, 2, 0}, {18, 12, 0}, {18, 12, 0.004}, {18, 12, 1.0e-15},
                {18, 12, 1.0e-15}, {18, 12, 0}};
        V3DryMeshState onePhase = new V3DryMeshState(original.topology(), 3, liquid, vapor,
                V3TruncationNumericsTest.temperatures());

        V3DryMeshState lifted = V3ColumnCalculator.liftFloorSupport(original, thermo, onePhase);

        // The calibrated ratio carries the calibration state's own vapour total, which held 0.004 mol/s of
        // trace that the lifted state does not, so the implied flow reproduces it to one part in ten thousand.
        assertEquals(0.004, lifted.vaporFlow(3, TRACE), 1.0e-3 * 0.004);
        assertEquals(1.0e-15, lifted.vaporFlow(4, TRACE));
        assertEquals(5.0 * floor, lifted.liquidFlow(4, TRACE));
        assertEquals(V3TruncationSupport.PointPhases.BOTH,
                V3TruncationSupport.derive(original, 0.0, lifted).pointPhases(3, TRACE));
        assertEquals(V3TruncationSupport.PointPhases.LIQUID_ONLY,
                V3TruncationSupport.derive(original, 0.0, lifted).pointPhases(4, TRACE));
    }

    private static List<V3DegreeOfFreedomLedger.UnknownId> traceUnknowns(V3DegreeOfFreedomLedger ledger) {
        return ledger.unknowns().stream().map(V3DegreeOfFreedomLedger.Unknown::id)
                .filter(id -> id.component() == TRACE).toList();
    }

    private static List<V3DegreeOfFreedomLedger.EquationId> traceEquations(V3DegreeOfFreedomLedger ledger) {
        return ledger.equations().stream().map(V3DegreeOfFreedomLedger.Equation::id)
                .filter(id -> id.component() == TRACE).toList();
    }

    private static List<V3DegreeOfFreedomLedger.UnknownId> references(
            V3DegreeOfFreedomLedger ledger, V3DegreeOfFreedomLedger.EquationFamily family, int node) {
        return ledger.equations().stream()
                .filter(equation -> equation.id().family() == family && equation.id().node() == node
                        && equation.id().component() == TRACE)
                .findFirst().orElseThrow().referencedUnknowns().stream()
                .filter(id -> id.component() == TRACE).toList();
    }

    private static double physical(V3MeshResidual residual, int node, int component) {
        return residual.rows().stream()
                .filter(row -> row.equation().family()
                        == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE
                        && row.equation().node() == node && row.equation().component() == component)
                .findFirst().orElseThrow().physicalValue();
    }

    private static V3AcceptanceAudit.Check check(V3AcceptanceAudit audit, String family) {
        return audit.checks().stream().filter(candidate -> candidate.family().equals(family)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + family + ": " + audit.checks()));
    }

    private static V3DryMeshState perturb(V3DryMeshCoordinateMap map, V3DryMeshState state) {
        double[] values = map.encode(state);
        for (int index = 0; index < values.length; index++) {
            boolean temperature = map.unknowns().get(index).id().family()
                    == V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE;
            values[index] += ((index % 3) - 1) * (temperature ? 0.1 : 0.01);
        }
        return map.decode(values);
    }

    private static V3ColumnProblem original() {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:truncation", "test:ternary",
                new V3ComponentBasis(List.of("component-a", "dormant", "component-b", "trace")),
                new double[] {30.0, 0.0, 60.0, TRACE_FEED}, 400.0, 4, 2, 250_000.0, 750.0, List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                new V3ColumnSpecification.ReboilerDuty(0.0)));
        return V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static V3DryMeshState exactState(V3ColumnProblem original) {
        double[][] liquid = {{10, 10, 0}, {5, 5, 0}, {35, 65, 0.006}, {35, 65, 0}, {35, 65, 0}, {17, 53, 0}};
        double[][] vapor = {{8, 2, 0}, {18, 12, 0}, {18, 12, 0.004}, {18, 12, 0}, {18, 12, 0}, {18, 12, 0}};
        return new V3DryMeshState(original.topology(), 3, liquid, vapor, V3TruncationNumericsTest.temperatures());
    }

    /**
     * Trace profile with all four phase states: ABSENT at the condenser and below tray three, VAPOR_ONLY on
     * tray one, BOTH on the feed tray (which the product-path band forces anyway), LIQUID_ONLY on tray three.
     * The floor of the trace component is {@code 0.01 * 1e-10 = 1e-12} mol/s.
     */
    private static Fixture onePhaseFixture() {
        V3ColumnProblem original = original();
        double[][] liquid = {{10, 10, 0}, {5, 5, 1.0e-15}, {35, 65, 0.006}, {35, 65, 0.004},
                {35, 65, 0}, {17, 53, 0}};
        double[][] vapor = {{8, 2, 0}, {18, 12, 0.004}, {18, 12, 0.004}, {18, 12, 1.0e-15},
                {18, 12, 0}, {18, 12, 0}};
        V3DryMeshState raw = new V3DryMeshState(original.topology(), 3, liquid, vapor,
                V3TruncationNumericsTest.temperatures());
        V3TruncationSupport support = V3TruncationSupport.derive(original, 0.0, raw);
        V3ColumnProblem problem = V3ColumnProblemResolver.withTruncation(original, support);
        return new Fixture(problem, support, support.projectSeed(original, raw),
                new V3TruncationNumericsTest.ManufacturedThermo(original.input().componentBasis(), raw));
    }

    private record Fixture(V3ColumnProblem problem, V3TruncationSupport support, V3DryMeshState state,
                           V3TruncationNumericsTest.ManufacturedThermo thermo) {}
}

package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Regression fixture for the TJL19 wet three-pumparound three-draw Newton stall.
 *
 * <p>The pinned state is the one the Java 21 continuation left behind before this change: physically converged
 * to 0.0001 K and 0.001 mol/s, but carrying a spike in the TJL_PC09 liquid profile (1.2e-37, 2.3e-11 and
 * 2.4e-25 mol/s on trays 10, 11 and 12 of a 45.8 mol/s feed component). Under feed-scaled material balances
 * that spike was a 5e-13 scaled residual — invisible to Newton and to the acceptance audit — while the banded
 * LU equilibrated the same two rows onto one unit vector and returned SINGULAR, after which the damped
 * Gauss-Newton fallback crawled to the iteration budget at residual 3.8e-8.</p>
 *
 * <p>The two assertions are the two remedies: the spike is visible in the local-throughput scaling, and the
 * points that carry it are below the support floor and are therefore not unknowns at all.</p>
 */
class V3TraceFloorSupportTest {
    private static final String STATE_RESOURCE =
            "/science/column/v3/tjl19-wet-three-pumparound-three-draw-stalled-state-java21.txt";
    private static final String SPIKE_COMPONENT = "TJL_PC09";
    private static final int[] SPIKE_NODES = {10, 11, 12};
    private static final double SUMP_STEAM_MOL_PER_SECOND = 1_200.0 * 1_000.0 / 3_600.0;
    private static final double SUMP_STEAM_TEMPERATURE_KELVIN = 533.15;
    private static final int STAGE_COUNT = 30;

    @Test
    void theStalledSpikeIsVisibleInTheUntruncatedResidualAndBelowTheSupportFloor() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl19_dwsim");
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input(thermo), V3CondenserPhaseBranch.LIQUID_ONLY);
        Fixture fixture = fixture(problem);
        int component = activeComponent(problem, SPIKE_COMPONENT);
        double floor = problem.activeComponentBasis().flowScale(component) * V3TruncationSupport.TRACE_FLOOR_FRACTION;

        V3MeshResidual residual = new V3MeshResidualEvaluator(problem, thermo, fixture.feedMolarEnthalpy())
                .evaluate(fixture.state(), thermo.newWorkspace());
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.0, fixture.state());

        for (int node : SPIKE_NODES) {
            double scaled = scaledMaterialResidual(residual, node, component);
            System.out.printf(Locale.ROOT,
                    "  %s node %d: liquid=%.4g vapor=%.4g floor=%.4g retained=%b scaled material residual=%.4g%n",
                    SPIKE_COMPONENT, node, fixture.state().liquidFlow(node, component),
                    fixture.state().vaporFlow(node, component), floor, support.retains(node, component), scaled);
            assertFalse(support.retains(node, component),
                    () -> SPIKE_COMPONENT + " on node " + node + " is below the support floor and cannot be an unknown");
        }
        // The feed-scaled formulation put this pair of rows at 5e-13, five orders below the 1e-8 acceptance
        // tolerance, while the equilibrated LU gave them full weight. Against their own local throughput they
        // are 5e-3, five orders above tolerance, so a state carrying the spike can no longer be accepted.
        // Trays 11 and 12 report the same value because they are the two rows the equilibration collapsed
        // onto one unit vector; removing their points is what removes the dependency.
        for (int node : new int[] {11, 12}) {
            assertTrue(scaledMaterialResidual(residual, node, component) > 1.0e-3,
                    () -> "the trace spike on tray " + node + " must be a visible residual, measured "
                            + scaledMaterialResidual(residual, node, component));
        }
        assertTrue(residual.maximumAbsoluteScaledResidual() > V3ColumnCalculator.SCALED_RESIDUAL_TOLERANCE);
    }

    @Test
    void theRequestedRungConvergesFromTheStalledStateOnTheFloorSupport() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:tjl19_dwsim");
        V3ColumnProblem untruncated = V3ColumnProblemResolver.resolve(input(thermo), V3CondenserPhaseBranch.LIQUID_ONLY);
        Fixture fixture = fixture(untruncated);

        V3TruncationSupport support = V3TruncationSupport.derive(untruncated, 0.0, fixture.state());
        assertTrue(support.truncatedPointCount() > 0, "the floor must remove the trace tail of this state");
        V3ColumnProblem problem = V3ColumnProblemResolver.withTruncation(untruncated, support);
        V3DryMeshState seed = support.projectSeed(untruncated, fixture.state());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, fixture.feedMolarEnthalpy());

        long started = System.nanoTime();
        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(
                problem, evaluator, new V3DryMeshCoordinateMap(problem), seed, thermo::newWorkspace,
                32, V3ColumnCalculator.SCALED_RESIDUAL_TOLERANCE, V3SolveControl.UNBOUNDED);
        System.out.printf(Locale.ROOT,
                "  floor-supported restart: %s residual=%.4g iterations=%d truncated=%d/%d in %.1f s (%s)%n",
                attempt.getClass().getSimpleName(), attempt.evidence().maximumScaledResidual(),
                attempt.evidence().iterations(), support.truncatedPointCount(), support.totalPointCount(),
                (System.nanoTime() - started) / 1.0e9, attempt.evidence().termination());

        assertInstanceOf(V3SimultaneousColumnSolver.Attempt.Converged.class, attempt,
                () -> attempt.evidence().toString());
        assertTrue(attempt.evidence().convergenceEvidence().satisfiesGates(),
                () -> "the restart must end on a verified final Newton correction: " + attempt.evidence());
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(problem, thermo, fixture.feedMolarEnthalpy())
                .audit(attempt.state(), thermo.newWorkspace());
        assertTrue(audit.checks().stream().filter(check -> check.family().equals("TRUNCATION_MASS_DEFECT"))
                .allMatch(V3AcceptanceAudit.Check::passed), audit::toString);
    }

    private static double scaledMaterialResidual(V3MeshResidual residual, int node, int component) {
        return residual.rows().stream()
                .filter(row -> row.equation().equals(new V3DegreeOfFreedomLedger.EquationId(
                        V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE, node, component)))
                .mapToDouble(row -> Math.abs(row.scaledValue())).max()
                .orElseThrow(() -> new AssertionError("no material row for node " + node));
    }

    private static int activeComponent(V3ColumnProblem problem, String componentId) {
        for (int active = 0; active < problem.activeComponentBasis().componentCount(); active++) {
            int publicIndex = problem.activeComponentBasis().publicIndex(active);
            if (problem.input().componentBasis().componentId(publicIndex).equals(componentId)) return active;
        }
        throw new AssertionError(componentId + " is not an active component of this feed");
    }

    /**
     * Loads the pinned state: a header of {@code branch nodeCount componentCount feedMolarEnthalpy}, then one
     * line per node holding the temperature, every liquid component flow and every vapor component flow.
     */
    private static Fixture fixture(V3ColumnProblem problem) {
        List<String> lines = new ArrayList<>();
        try (InputStream stream = V3TraceFloorSupportTest.class.getResourceAsStream(STATE_RESOURCE)) {
            if (stream == null) throw new AssertionError("missing fixture " + STATE_RESOURCE);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) if (!line.isBlank()) lines.add(line.trim());
            }
        } catch (IOException unreadable) {
            throw new AssertionError("unreadable fixture " + STATE_RESOURCE, unreadable);
        }
        String[] header = lines.get(0).split(" ");
        assertEquals(V3CondenserPhaseBranch.LIQUID_ONLY, V3CondenserPhaseBranch.valueOf(header[0]));
        int nodes = Integer.parseInt(header[1]);
        int components = Integer.parseInt(header[2]);
        assertEquals(problem.topology().nodeCount(), nodes);
        assertEquals(problem.activeComponentBasis().componentCount(), components);
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            String[] parts = lines.get(node + 1).split(" ");
            temperatures[node] = Double.parseDouble(parts[0]);
            for (int component = 0; component < components; component++) {
                liquid[node][component] = Double.parseDouble(parts[1 + component]);
                vapor[node][component] = Double.parseDouble(parts[1 + components + component]);
            }
        }
        return new Fixture(new V3DryMeshState(problem.topology(), components, liquid, vapor, temperatures),
                Double.parseDouble(header[3]));
    }

    /** The same input as {@code V3PumparoundSteamCalculatorTest.wetInput} on the TJL19 package. */
    private static V3ColumnInput input(V3PengRobinsonThermo thermo) {
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        double totalFlow = 2_610.7 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlow;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 365.0 + 273.15, STAGE_COUNT, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)),
                ColumnCalculatorV3BlockEntity.pilotPresetInput().sideDraws(),
                List.of(new V3SteamFeedSpec(STAGE_COUNT + 1, SUMP_STEAM_MOL_PER_SECOND, SUMP_STEAM_TEMPERATURE_KELVIN)),
                List.of(new V3PumparoundSpec(6, 9, -5.0e6, V3PumparoundSpec.Split.UNIFORM),
                        new V3PumparoundSpec(13, 16, -6.0e6, V3PumparoundSpec.Split.UNIFORM),
                        new V3PumparoundSpec(20, 23, -4.0e6, V3PumparoundSpec.Split.UNIFORM)));
    }

    private record Fixture(V3DryMeshState state, double feedMolarEnthalpy) {}
}

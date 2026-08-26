package com.wormzjl.createcheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.createcheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.createcheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.createcheme.science.column.v3.thermo.V3Phase;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoWorkspace;
import java.util.List;
import org.junit.jupiter.api.Test;

class V3SimultaneousColumnSolverTest {
    @Test
    void dampedBandedNewtonRecoversTheManufacturedFullMeshStateOnlyThroughBothAcceptanceGates() {
        V3ColumnProblem problem = problem();
        NewtonManufacturedThermo thermo = new NewtonManufacturedThermo();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        V3DryMeshState exact = exactState(problem.topology());
        double[] perturbedCoordinates = coordinates.encode(exact);
        for (int index = 0; index < perturbedCoordinates.length; index++) {
            perturbedCoordinates[index] += index < 24 ? ((index % 5) - 2) * 0.02 : (index % 2 == 0 ? 0.5 : -0.5);
        }
        V3DryMeshState perturbed = coordinates.decode(perturbedCoordinates);

        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                problem, evaluator, coordinates, perturbed, thermo::newWorkspace, 16, 1.0e-9);

        assertTrue(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged, attempt::toString);
        V3SimultaneousColumnSolver.Attempt.Converged converged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, attempt);
        assertTrue(converged.evidence().iterations() > 0);
        assertTrue(converged.evidence().maximumScaledResidual() <= 1.0e-9);
        assertTrue(converged.evidence().convergenceEvidence().satisfiesGates());
        V3MeshResidual finalResidual = evaluator.evaluate(converged.state(), thermo.newWorkspace());
        assertTrue(finalResidual.maximumAbsoluteScaledResidual() <= 1.0e-9);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(problem, thermo, 0.0).audit(converged.state(), thermo.newWorkspace());
        assertTrue(audit.accepted());
        V3ConvergenceEvidence convergenceEvidence = converged.evidence().convergenceEvidence();
        V3ColumnResult result = V3ColumnResult.accepted(
                problem, new V3InputDigest("0".repeat(64)), audit, convergenceEvidence);
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(
                0, converged.evidence().iterations(), 1, 1, converged.evidence().maximumScaledResidual(), 0.0,
                "manufactured-newton", List.of(), audit, convergenceEvidence);
        V3ColumnOutcome.Success success = new V3ColumnOutcome.Success(result, diagnostics);
        assertEquals(convergenceEvidence, success.result().convergenceEvidence());
    }

    @Test
    void registeredTiaJuanaPrNewtonAttemptIsBoundedAndRecordsItsActualConvergenceState() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("createcheme:tia_juana_light");
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(realCrudeInput(crude), V3CondenserPhaseBranch.TWO_PHASE);
        V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()), crude.moleFractions(), thermo.newWorkspace());
        V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace, 128, 1.0e-8);

        V3MeshResidual terminalResidual = evaluator.evaluate(attempt.state(), thermo.newWorkspace());
        System.out.println("V3 real crude Newton outcome: " + attempt + "; residual families="
                + maximumResidualsByFamily(terminalResidual));
        assertTrue(Double.isFinite(attempt.evidence().maximumScaledResidual()));
        assertTrue(Double.isFinite(attempt.evidence().scaledMerit()));
        if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged) {
            assertTrue(converged.evidence().convergenceEvidence().satisfiesGates());
            assertTrue(new V3AcceptanceAuditor(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                    .audit(converged.state(), thermo.newWorkspace()).accepted());
        } else {
            V3SimultaneousColumnSolver.Attempt.Failure failure = assertInstanceOf(
                    V3SimultaneousColumnSolver.Attempt.Failure.class, attempt);
            assertFalse(failure.code().isBlank());
        }
    }

    @Test
    void registeredPrBinaryColdStartConvergesAndPassesBothPublicationGates() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("createcheme:cdu17_tjl_acs2018");
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(binaryInput(thermo), V3CondenserPhaseBranch.TWO_PHASE);
        V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        V3SimultaneousColumnSolver.Attempt.Converged converged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, V3SimultaneousColumnSolver.solve(
                        problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace, 128, 1.0e-8));
        V3ConvergenceEvidence convergenceEvidence = converged.evidence().convergenceEvidence();
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                .audit(converged.state(), thermo.newWorkspace());

        assertTrue(convergenceEvidence.satisfiesGates());
        assertTrue(audit.accepted());
        V3ColumnResult result = V3ColumnResult.accepted(
                problem, new V3InputDigest("1".repeat(64)), audit, convergenceEvidence);
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(
                0, converged.evidence().iterations(), 1, 1, converged.evidence().maximumScaledResidual(), 0.0,
                "registered-pr-binary", List.of(), audit, convergenceEvidence);
        assertTrue(new V3ColumnOutcome.Success(result, diagnostics).isSuccess());

        V3SimultaneousColumnSolver.Attempt.Converged warmConverged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, V3SimultaneousColumnSolver.solve(
                        problem, evaluator, new V3DryMeshCoordinateMap(problem), converged.state(), thermo::newWorkspace,
                        convergenceEvidence, 128, 1.0e-8));
        assertEquals(0, warmConverged.evidence().iterations());
        assertTrue(warmConverged.evidence().convergenceEvidence().satisfiesGates());
        assertTrue(new V3AcceptanceAuditor(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                .audit(warmConverged.state(), thermo.newWorkspace()).accepted());
    }

    private static V3ColumnProblem problem() {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:newton", "test:binary",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30.0, 60.0}, 400.0,
                4, 2, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
        return V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static V3ColumnInput realCrudeInput(V3CrudeFeed crude) {
        double[] feedFlows = crude.moleFractions();
        for (int component = 0; component < feedFlows.length; component++) feedFlows[component] *= 100.0;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), feedFlows, 638.15, 4, 2, 266_500.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    private static V3ColumnInput binaryInput(V3PengRobinsonThermo thermo) {
        double[] feedFlows = new double[thermo.componentBasis().componentCount()];
        feedFlows[6] = 50.0; // PC03
        feedFlows[13] = 50.0; // PC10
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                thermo.componentBasis(), feedFlows, 550.0, 2, 1, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
    }

    private static String maximumResidualsByFamily(V3MeshResidual residual) {
        StringBuilder summary = new StringBuilder();
        for (V3DegreeOfFreedomLedger.EquationFamily family : V3DegreeOfFreedomLedger.EquationFamily.values()) {
            double maximum = 0.0;
            for (V3MeshResidual.Row row : residual.rows()) {
                if (row.equation().family() == family) maximum = Math.max(maximum, Math.abs(row.scaledValue()));
            }
            if (!summary.isEmpty()) summary.append(", ");
            summary.append(family).append('=').append(maximum);
        }
        return summary.toString();
    }

    private static V3DryMeshState exactState(V3ColumnTopology topology) {
        return new V3DryMeshState(topology, 2, new double[][] {
                {10.0, 10.0}, {5.0, 5.0}, {35.0, 65.0}, {35.0, 65.0}, {35.0, 65.0}, {17.0, 53.0}
        }, new double[][] {
                {8.0, 2.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}
        }, new double[] {400.0, 410.0, 420.0, 430.0, 440.0, 450.0});
    }

    private static final class NewtonManufacturedThermo implements V3ThermoModel {
        private static final double[][] K = {
                {1.6, 0.4}, {1.2, 0.8}, {12.0 / 7.0, 8.0 / 13.0},
                {12.0 / 7.0, 8.0 / 13.0}, {12.0 / 7.0, 8.0 / 13.0}, {42.0 / 17.0, 28.0 / 53.0}
        };
        private final V3ComponentBasis basis = new V3ComponentBasis(List.of("component-a", "component-b"));

        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(2); }

        @Override
        public V3FugacityResult fugacity(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            int node = nodeForTemperature(temperatureKelvin);
            double[] logPhi = new double[2];
            if (phase == V3Phase.LIQUID) {
                double referenceTemperature = 400.0 + 10.0 * node;
                for (int component = 0; component < 2; component++) logPhi[component] = Math.log(K[node][component])
                        + (component + 1) * 1.0e-3 * (temperatureKelvin - referenceTemperature);
            }
            return new V3FugacityResult(phase, logPhi, 1.0, 0.0, 1, 0.0);
        }

        @Override
        public double molarEnthalpy(
                double temperatureKelvin, double pressurePascal, double[] composition, V3Phase phase,
                V3ThermoWorkspace workspace) {
            return temperatureKelvin - (400.0 + 10.0 * nodeForTemperature(temperatureKelvin));
        }

        @Override
        public V3FlashResult flashTP(
                double temperatureKelvin, double pressurePascal, double[] overallComposition, V3ThermoWorkspace workspace) {
            throw new UnsupportedOperationException("The Newton manufactured model does not implement a flash");
        }

        private static int nodeForTemperature(double temperatureKelvin) {
            int node = (int) Math.round((temperatureKelvin - 400.0) / 10.0);
            if (node < 0 || node >= K.length) throw new IllegalArgumentException("Manufactured Newton temperature is outside its grid");
            return node;
        }
    }
}

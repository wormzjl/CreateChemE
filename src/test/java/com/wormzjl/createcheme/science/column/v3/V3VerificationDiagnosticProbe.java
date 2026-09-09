package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedMatrix;
import com.wormzjl.createcheme.science.column.v3.linalg.V3BandedPivotedSolver;
import com.wormzjl.createcheme.science.column.v3.thermo.V3ThermoModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Untimed observation of actual final-verification candidates in a separately compiled diagnostic solver. */
public final class V3VerificationDiagnosticProbe {
    static boolean direct;
    private static String label;
    private static final List<Map<String, Object>> ROWS = new ArrayList<>();
    private static final List<Map<String, Object>> CASES = new ArrayList<>();
    private static int linearSolves;
    private V3VerificationDiagnosticProbe() {}

    public static void main(String[] args) throws Exception {
        var factory = V3ConvergenceClosureTest.class.getDeclaredMethod("evaluationCase", String.class);
        factory.setAccessible(true);
        for (String current : List.of("A", "B", "C", "D", "E", "Holland")) {
            label = current;
            linearSolves = 0;
            int firstRow = ROWS.size();
            V3ColumnOutcome outcome = current.equals("Holland")
                    ? V3HollandExample32.calculate(V3HollandExample32.input(), V3SolveControl.UNBOUNDED)
                    : V3ColumnCalculator.calculate((V3ColumnInput) factory.invoke(null, current));
            System.out.println(current + ": " + outcome.getClass().getSimpleName());
            CASES.add(Map.of("case", current, "linearSolves", linearSolves,
                    "verificationCandidates", ROWS.size() - firstRow,
                    "outcome", outcome.getClass().getSimpleName()));
        }
        Path report = Path.of(System.getProperty("verificationReport"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("cases", CASES, "candidates", ROWS)));
        System.out.println("Recorded " + ROWS.size() + " actual candidates");
    }

    static void record(V3MeshResidualEvaluator evaluator, V3DryMeshState base, V3DryMeshState candidate,
            double baseMerit, double candidateMerit, double candidateMaximum, double closure,
            V3ConvergenceEvidence evidence, boolean actualAccepted) {
        try {
            V3ColumnProblem problem = (V3ColumnProblem) field(evaluator, "problem");
            V3ThermoModel thermo = (V3ThermoModel) field(evaluator, "thermo");
            double feedEnthalpy = (double) field(evaluator, "feedMolarEnthalpyJoulesPerMol");
            double baseMaximum = evaluator.evaluate(base, thermo.newWorkspace()).maximumAbsoluteScaledResidual();
            V3AcceptanceAudit audit = new V3AcceptanceAuditor(problem, thermo, feedEnthalpy, V3ConvergenceEvidence.closureOf(closure))
                    .audit(candidate, thermo.newWorkspace());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("case", label);
            row.put("direct", direct);
            row.put("nodes", problem.topology().nodeCount());
            row.put("baseMaximum", baseMaximum);
            row.put("candidateMaximum", candidateMaximum);
            row.put("baseMerit", baseMerit);
            row.put("candidateMerit", candidateMerit);
            row.put("closure", closure);
            row.put("evidence", evidence);
            row.put("audit", audit);
            row.put("actualAccepted", actualAccepted);
            row.put("meritOnlyRejected", candidateMaximum <= closure && candidateMerit > baseMerit
                    && evidence.satisfiesGates(closure));
            row.put("boundedException", baseMaximum <= Math.min(1e-12, closure)
                    && candidateMaximum <= Math.min(1e-12, closure));
            ROWS.add(row);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static V3BandedPivotedSolver.Result solve(V3BandedMatrix matrix, double[] rhs) {
        linearSolves++;
        return V3BandedPivotedSolver.solve(matrix, rhs);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}

package com.wormzjl.createcheme.science.column.v3.thermo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serial production-API evidence probe; intentionally contains no timing or speed measurements. */
public final class V3FlashTruncationProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final String PACKAGE_ID = "createcheme:cdu17_tjl_acs2018";
    private static final String ASSAY_ID = "createcheme:tia_juana_light";
    private static final double CUTOFF = 1.0e-6;

    private V3FlashTruncationProbe() {}

    public static void main(String[] args) throws IOException {
        Path destination = reportPath(args);
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE_ID);
        V3TraceTruncationPolicy policy = V3TraceTruncationPolicy.of(CUTOFF);
        List<CaseInput> inputs = cases(thermo);
        List<Map<String, Object>> cells = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("scope", "Serial production API evidence, NOT a timing benchmark or a speed comparison. "
                + "Each public call has a fresh workspace. The policy call computes its own unrestricted reference.");
        report.put("packageId", thermo.packageId());
        report.put("datasetRevision", thermo.datasetRevision());
        report.put("componentIds", java.util.stream.IntStream.range(0, thermo.componentBasis().componentCount())
                .mapToObj(thermo.componentBasis()::componentId).toList());
        report.put("advisoryEvidence", thermo.advisoryEvidence());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("cutoffMoleFraction", CUTOFF);
        report.put("expectedCases", inputs.size());
        report.put("status", "RUNNING");
        report.put("cases", cells);
        Files.createDirectories(destination.getParent());
        // Claim a new report atomically; an earlier snapshot is never replaced.
        Files.writeString(destination, JSON.toJson(report), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        int successfulCalls = 0;
        int thermodynamicFailures = 0;
        try {
            for (CaseInput input : inputs) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("name", input.name());
                cell.put("temperatureKelvin", input.temperatureKelvin());
                cell.put("pressurePascal", input.pressurePascal());
                cell.put("normalizedOverall", input.overall());
                cell.put("status", "RUNNING_REFERENCE");
                cells.add(cell);
                Map<String, Object> reference = evaluate(thermo, input, null);
                cell.put("reference", reference);
                if (succeeded(reference)) successfulCalls++;
                else thermodynamicFailures++;
                cell.put("status", "RUNNING_POLICY");
                Map<String, Object> requested = evaluate(thermo, input, policy);
                cell.put("requested", requested);
                if (succeeded(requested)) successfulCalls++;
                else thermodynamicFailures++;
                cell.put("status", succeeded(reference) && succeeded(requested) ? "SUCCESS" : "THERMO_FAILURE");
                report.put("successfulCalls", successfulCalls);
                report.put("thermodynamicFailures", thermodynamicFailures);
                writeProgress(destination, report);
                System.out.println("FLASH_TRUNCATION_CASE case=" + input.name() + " status=" + cell.get("status")
                        + " reference=" + reference.get("status") + " requested=" + requested.get("status")
                        + " truncation=" + requested.get("truncationStatus"));
            }
        } catch (RuntimeException unexpected) {
            report.put("status", "ABORTED_UNEXPECTED_FAILURE");
            report.put("successfulCalls", successfulCalls);
            report.put("thermodynamicFailures", thermodynamicFailures);
            report.put("unexpectedFailure", Map.of("type", unexpected.getClass().getName(),
                    "message", String.valueOf(unexpected.getMessage())));
            writeProgress(destination, report);
            System.out.println("FLASH_TRUNCATION_PROBE status=ABORTED_UNEXPECTED_FAILURE report=" + destination);
            throw unexpected;
        }
        String status = thermodynamicFailures == 0 ? "COMPLETE" : "COMPLETED_WITH_THERMO_FAILURES";
        report.put("status", status);
        report.put("completedCases", inputs.size());
        report.put("successfulCalls", successfulCalls);
        report.put("thermodynamicFailures", thermodynamicFailures);
        writeProgress(destination, report);
        System.out.println("FLASH_TRUNCATION_PROBE status=" + status + " cases=" + inputs.size()
                + " successfulCalls=" + successfulCalls + " thermodynamicFailures=" + thermodynamicFailures
                + " report=" + destination);
        if (thermodynamicFailures != 0) {
            throw new IllegalStateException("Flash evidence probe completed with " + thermodynamicFailures
                    + " typed thermodynamic failures; inspect " + destination);
        }
    }

    private static Map<String, Object> evaluate(
            V3PengRobinsonThermo thermo, CaseInput input, V3TraceTruncationPolicy policy) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("api", policy == null ? "unrestricted public flashTP" : "explicit-policy public flashTP");
        try {
            V3FlashResult result = policy == null
                    ? thermo.flashTP(input.temperatureKelvin(), input.pressurePascal(), input.overall(), thermo.newWorkspace())
                    : thermo.flashTP(input.temperatureKelvin(), input.pressurePascal(), input.overall(), policy, thermo.newWorkspace());
            values.put("status", "SUCCESS");
            values.put("phase", result.phase());
            values.put("vaporFraction", result.vaporFraction());
            values.put("iterations", result.iterations());
            values.put("liquidComposition", result.liquidComposition());
            values.put("vaporComposition", result.vaporComposition());
            values.put("molarEnthalpyJoulesPerMol", result.molarEnthalpyJoulesPerMol());
            values.put("referenceMolarEnthalpyJoulesPerMol", result.referenceMolarEnthalpyJoulesPerMol());
            values.put("fullBasisMaterialClosureError", materialClosureError(input.overall(), result));
            values.put("truncationStatus", result.truncationEvidence().status());
            values.put("truncationEvidence", result.truncationEvidence());
            values.put("detail", result.detail());
        } catch (V3ThermoException failure) {
            values.put("status", "THERMO_FAILURE");
            values.put("failureCode", failure.code());
            values.put("failurePhase", failure.phase());
            values.put("failureMessage", failure.getMessage());
            if (failure.getCause() != null) {
                values.put("causeType", failure.getCause().getClass().getName());
                values.put("causeMessage", failure.getCause().getMessage());
            }
        }
        return values;
    }

    private static double materialClosureError(double[] overall, V3FlashResult result) {
        double total = Arrays.stream(overall).sum();
        double[] liquid = result.liquidComposition();
        double[] vapor = result.vaporComposition();
        double maximum = 0.0;
        for (int component = 0; component < overall.length; component++) {
            double liquidAllocation = liquid.length == 0 ? 0.0 : (1.0 - result.vaporFraction()) * liquid[component];
            double vaporAllocation = vapor.length == 0 ? 0.0 : result.vaporFraction() * vapor[component];
            maximum = Math.max(maximum, Math.abs(overall[component] / total - liquidAllocation - vaporAllocation));
        }
        return maximum;
    }

    private static boolean succeeded(Map<String, Object> result) {
        return "SUCCESS".equals(result.get("status"));
    }

    private static List<CaseInput> cases(V3PengRobinsonThermo thermo) {
        double[] binary = new double[thermo.componentBasis().componentCount()];
        binary[1] = 0.5;
        binary[15] = 0.5;
        double[] crude = thermo.crudeFeed(ASSAY_ID).moleFractions();
        List<CaseInput> cases = new ArrayList<>();
        cases.add(new CaseInput("binary-500K-250000Pa", 500.0, 250_000.0, binary));
        cases.add(new CaseInput("binary-638.15K-137250Pa", 638.15, 137_250.0, binary));
        cases.add(new CaseInput("crude-500K-250000Pa", 500.0, 250_000.0, crude));
        for (int pressure : new int[] {67_250, 87_250, 117_250, 137_250, 167_250}) {
            cases.add(new CaseInput("crude-638.15K-" + pressure + "Pa", 638.15, pressure, crude));
        }
        cases.add(new CaseInput("crude-liquid-298.15K-250000Pa", 298.15, 250_000.0, crude));
        cases.add(new CaseInput("crude-vapor-900K-50000Pa", 900.0, 50_000.0, crude));
        return List.copyOf(cases);
    }

    private static Path reportPath(String[] args) {
        if (args.length != 1 || !args[0].startsWith("--report=") || args[0].substring("--report=".length()).isBlank()) {
            throw new IllegalArgumentException("Exactly one --report=<new-output-path.json> argument is required");
        }
        Path path = Path.of(args[0].substring("--report=".length())).toAbsolutePath().normalize();
        if (Files.exists(path)) throw new IllegalArgumentException("Refusing to overwrite an existing flash probe report: " + path);
        return path;
    }

    private static void writeProgress(Path destination, Map<String, Object> report) throws IOException {
        Files.writeString(destination, JSON.toJson(report), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private record CaseInput(String name, double temperatureKelvin, double pressurePascal, double[] overall) {
        private CaseInput { overall = overall.clone(); }
        @Override public double[] overall() { return overall.clone(); }
    }
}

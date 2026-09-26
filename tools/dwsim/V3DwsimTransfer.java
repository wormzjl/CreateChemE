package com.wormzjl.createcheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.createcheme.science.column.v3.thermo.*;
import java.nio.file.*;
import java.util.*;

/** Offline DWSIM seed qualification; never publishes or changes an in-game result. */
public final class V3DwsimTransfer {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private V3DwsimTransfer() {}

    public static void main(String[] args) throws Exception {
        JsonObject external = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        JsonObject originalInput = external.getAsJsonObject("input").deepCopy();
        JsonObject input = external.getAsJsonObject("input");
        V3PengRobinsonThermo thermo = external.has("native_compound_properties") ? V3NativeThermoFixture.create(external)
                : V3PengRobinsonThermo.fromRegisteredPackage(input.get("package_id").getAsString());
        JsonArray components = input.getAsJsonArray("components");
        int[] axis = new int[components.size()];
        double[] feed = new double[thermo.componentBasis().componentCount()];
        for (int i = 0; i < axis.length; i++) {
            axis[i] = thermo.componentBasis().componentIds().indexOf(components.get(i).getAsJsonObject().get("id").getAsString());
            if (axis[i] < 0) throw new IllegalArgumentException("Unknown component");
            feed[axis[i]] = input.getAsJsonArray("feed_mol_s").get(i).getAsDouble();
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("DWSIM_input", originalInput);
        report.put("resolved_V3_input", input);
        var comparisons = new ArrayList<Object>();
        for (JsonElement element : external.getAsJsonArray("property_checks")) {
            JsonObject check = element.getAsJsonObject();
            double[] z = new double[feed.length];
            double mw = 0;
            for (int i = 0; i < axis.length; i++) {
                z[axis[i]] = check.getAsJsonArray("composition").get(i).getAsDouble();
                mw += z[axis[i]] * components.get(i).getAsJsonObject().get("molecularWeightKgPerMol").getAsDouble();
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("DWSIM", check);
            try {
                var phase = V3Phase.valueOf(check.get("phase").getAsString().toUpperCase(Locale.ROOT));
                var value = thermo.fugacity(number(check, "temperature_K"), number(check, "pressure_Pa"), z, phase, thermo.newWorkspace());
                double[] phi = new double[axis.length];
                double[] difference = new double[axis.length];
                for (int i = 0; i < axis.length; i++) {
                    phi[i] = Math.exp(value.logFugacityCoefficient(axis[i]));
                    difference[i] = 100 * (check.getAsJsonArray("phi").get(i).getAsDouble() / phi[i] - 1);
                }
                row.put("V3_phi", phi);
                row.put("DWSIM_phi_difference_percent_relative_to_V3", difference);
                row.put("V3_h_J_mol", value.molarEnthalpyJoulesPerMol());
                row.put("DWSIM_h_J_mol", number(check, "enthalpy_kJ_kg") * 1000 * mw);
                row.put("DWSIM_minus_V3_h_J_mol", number(check, "enthalpy_kJ_kg") * 1000 * mw - value.molarEnthalpyJoulesPerMol());
                row.put("V3_root_count", value.physicalRootCount());
            } catch (V3ThermoException failure) { row.put("V3_error", failure.toString()); }
            comparisons.add(row);
        }
        report.put("property_comparisons", comparisons);
        if (external.has("profile")) {
            if (!external.get("solved").getAsBoolean()) throw new IllegalArgumentException("Unsolved profile");
            V3ColumnInput request = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:dwsim-binary",
                    thermo.componentBasis(), feed, number(input, "feed_temperature_K"), input.get("trays").getAsInt(),
                    input.get("feed_tray").getAsInt(), number(input, "top_pressure_Pa"), number(input, "stage_pressure_drop_Pa"),
                    List.of(new V3ColumnSpecification.CondenserOutletTemperature(number(input, "condenser_temperature_K")),
                            new V3ColumnSpecification.OrganicRefluxRatio(number(input, "reflux_ratio")),
                            new V3ColumnSpecification.ReboilerDuty(number(input, "reboiler_duty_W"))));
            var problem = V3ColumnProblemResolver.resolve(request, V3CondenserPhaseBranch.LIQUID_ONLY);
            JsonObject profile = external.getAsJsonObject("profile");
            int nodes = problem.topology().nodeCount();
            double[] temperatures = JSON.fromJson(profile.get("Tf"), double[].class);
            double[] pressures = JSON.fromJson(profile.get("P0"), double[].class);
            double[] l = JSON.fromJson(profile.get("Lf"), double[].class);
            double[] v = JSON.fromJson(profile.get("Vf"), double[].class);
            double[][] x = JSON.fromJson(profile.get("xf"), double[][].class);
            double[][] y = JSON.fromJson(profile.get("yf"), double[][].class);
            List<String> ids = Arrays.asList(JSON.fromJson(profile.get("compids"), String[].class));
            if (temperatures.length != nodes || pressures.length != nodes || l.length != nodes || v.length != nodes
                    || x.length != nodes || y.length != nodes || ids.size() != axis.length) throw new IllegalArgumentException("Profile dimensions");
            double[][] liquid = new double[nodes][axis.length], vapor = new double[nodes][axis.length];
            for (int n = 0; n < nodes; n++) {
                if (Math.abs(pressures[n] - problem.nodePressurePascal(n)) > 1e-6) throw new IllegalArgumentException("Pressure mismatch at " + n);
                for (int c = 0; c < axis.length; c++) {
                    int externalIndex = ids.indexOf(thermo.componentBasis().componentId(axis[c]));
                    if (externalIndex < 0) throw new IllegalArgumentException("External component missing");
                    liquid[n][c] = l[n] * x[n][externalIndex];
                    vapor[n][c] = n == 0 ? 0 : v[n] * y[n][externalIndex];
                }
            }
            // DWSIM Lf[0] is reflux; V3 L[0] includes reflux plus distillate.
            for (int c = 0; c < axis.length; c++) liquid[0][c] *= 1 + 1 / number(input, "reflux_ratio");
            var seed = new V3DryMeshState(problem.topology(), axis.length, liquid, vapor, temperatures);
            double hFeed = thermo.flashTP(request.feedTemperatureKelvin(), problem.nodePressurePascal(request.feedStageNumber()), feed, thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
            var evaluator = new V3MeshResidualEvaluator(problem, thermo, hFeed);
            report.put("external_seed", evaluate(problem, thermo, evaluator, hFeed, seed));
            report.put("cold_seed", evaluate(problem, thermo, evaluator, hFeed, V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace()).state()));
        }
        Files.writeString(Path.of(args[1]), JSON.toJson(report));
        System.out.println(JSON.toJson(report));
    }

    private static Map<String, Object> evaluate(V3ColumnProblem problem, V3PengRobinsonThermo thermo,
            V3MeshResidualEvaluator evaluator, double hFeed, V3DryMeshState seed) {
        var data = new LinkedHashMap<String, Object>();
        data.put("initial_residual", evaluator.evaluate(seed, thermo.newWorkspace()).maximumAbsoluteScaledResidual());
        data.put("initial_audit", new V3AcceptanceAuditor(problem, thermo, hFeed).audit(seed, thermo.newWorkspace()));
        long started = System.nanoTime();
        var attempt = V3SimultaneousColumnSolver.solve(problem, evaluator, new V3DryMeshCoordinateMap(problem), seed, thermo::newWorkspace, 128, 1e-8);
        data.put("correction_ms", (System.nanoTime() - started) / 1e6);
        data.put("outcome", attempt.getClass().getSimpleName());
        data.put("evidence", attempt.evidence());
        data.put("audit", new V3AcceptanceAuditor(problem, thermo, hFeed).audit(attempt.state(), thermo.newWorkspace()));
        data.put("corrected_state", attempt.state());
        return data;
    }

    private static double number(JsonObject value, String key) { return value.get(key).getAsDouble(); }
}

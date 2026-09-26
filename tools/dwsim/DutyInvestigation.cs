using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

// Runs bounded, independent native solver counterfactuals without changing installed assemblies.
internal static class DutyInvestigation
{
    private static string install;
    private static Type T(string assembly, string name) { return Assembly.LoadFrom(Path.Combine(install, assembly + ".dll")).GetType(name, true); }
    private static dynamic E(string name, string value) { return Enum.Parse(T("DWSIM.UnitOperations", name), value); }
    private static double[] Vec(object source) { return ((IEnumerable)source).Cast<object>().Select(Convert.ToDouble).ToArray(); }
    private static double[][] Rows(object source) { return ((IEnumerable)source).Cast<object>().Select(Vec).ToArray(); }
    private static Dictionary<string, object> Snapshot(object data) {
        var result = new Dictionary<string, object>();
        foreach (var property in data.GetType().GetProperties()) {
            if (property.Name == "ColumnObject" || property.Name == "CondenserSpec" || property.Name == "ReboilerSpec") continue;
            object value = property.GetValue(data, null);
            if (value == null) continue;
            result[property.Name] = property.PropertyType.IsEnum ? value.ToString() : value;
        }
        var json = new JavaScriptSerializer { MaxJsonLength = 32 * 1024 * 1024 };
        return json.Deserialize<Dictionary<string, object>>(json.Serialize(result));
    }

    [STAThread]
    private static int Main(string[] args) {
        install = Path.GetFullPath(args[0]);
        string inputFile = Path.GetFullPath(args[1]);
        string outputFile = Path.GetFullPath(args[2]);
        AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs e) {
            string path = Path.Combine(install, new AssemblyName(e.Name).Name + ".dll");
            return File.Exists(path) ? Assembly.LoadFrom(path) : null;
        };
        Directory.SetCurrentDirectory(install);
        var report = new Dictionary<string, object>();
        var cases = new List<object>();
        report["cases"] = cases;
        using (var sha = SHA256.Create()) report["unit_operations_sha256"] = BitConverter.ToString(sha.ComputeHash(File.ReadAllBytes(Path.Combine(install, "DWSIM.UnitOperations.dll")))).Replace("-", "").ToLowerInvariant();
        dynamic automation = Activator.CreateInstance(T("DWSIM.Automation", "DWSIM.Automation.Automation3"));
        try {
            foreach (string mode in args.Length > 3 ? args[3].Split(',') : new[] {"baseline", "duty-cold", "duty-warm", "duty-warm-condenser-heat", "duty-warm-condenser-heat-exact-distillate"}) {
                var record = new Dictionary<string, object> { {"mode", mode} };
                cases.Add(record);
                dynamic fs = automation.LoadFlowsheet2(inputFile);
                dynamic column = ((IDictionary)fs.SimulationObjects).Values.Cast<object>().Single(v => v.GetType().Name == "DistillationColumn");
                var errors = new List<string>();
                foreach (Exception error in automation.CalculateFlowsheet4(fs)) errors.Add(error.ToString());
                if (errors.Count != 0 || !(bool)fs.Solved) throw new InvalidOperationException("Baseline failed");
                int last = (int)column.NumberOfStages - 1;
                double qc = column.CondenserDuty, qr = column.ReboilerDuty, distillate = column.LSSf[0];
                record["baseline_qc_kW"] = qc; record["baseline_qr_kW"] = qr; record["baseline_distillate_mol_s"] = distillate;
                column.CreateSolverConvergengeReport = true;
                if (mode.Contains("warm")) {
                    column.SetInitialTemperatureEstimates((double[])column.Tf);
                    column.SetInitialLiquidMolarFlowEstimates((double[])column.Lf);
                    column.SetInitialVaporMolarFlowEstimates((double[])column.Vf);
                    column.SetInitialMolarCompositionEstimates(Rows(column.xf), Rows(column.yf));
                    column.InitialEstimates.DistillateFlowRate = distillate;
                    column.InitialEstimates.BottomsFlowRate = (double)column.Lf[last];
                    column.InitialEstimates.VaporProductFlowRate = (double)column.Vf[0];
                    column.UseTemperatureEstimates = true; column.UseLiquidFlowEstimates = true;
                    column.UseVaporFlowEstimates = true; column.UseCompositionEstimates = true;
                }
                if (mode != "baseline") {
                    column.Specs["R"].SType = E("DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps.ColumnSpec+SpecType", "Heat_Duty");
                    column.Specs["R"].SpecValue = -qr; column.Specs["R"].SpecUnit = "kW";
                }
                if (mode.Contains("exact-distillate")) column.Specs["C"].SpecValue = distillate;
                // Inspector's shared item list is unsafe under the installed parallel path.
                T("DWSIM.GlobalSettings", "DWSIM.GlobalSettings.Settings").GetProperty("EnableParallelProcessing").SetValue(null, false, null);
                bool inspectEnabled = !mode.StartsWith("ns-");
                T("DWSIM.GlobalSettings", "DWSIM.GlobalSettings.Settings").GetProperty("InspectorEnabled").SetValue(null, inspectEnabled, null);
                ((IList)T("DWSIM.Inspector", "DWSIM.Inspector.Host").GetField("Items").GetValue(null)).Clear();
                dynamic data = column.GetSolverInputData(false);
                if (mode.Contains("condenser-heat")) data.StageHeats[0] = qc;
                record["input"] = Snapshot(data);
                record["heat_spec_converted_to_SI"] = T("DWSIM.SharedClasses", "DWSIM.SharedClasses.SystemsOfUnits.Converter").GetMethod("ConvertToSI", new[] {typeof(string), typeof(double)}).Invoke(null, new object[] {"kW", -qr});
                // Independently reproduce the documented first heat-spec balance, using installed property calls.
                dynamic pp = column.PropertyPackage;
                dynamic liquidPhase = Enum.Parse(T("DWSIM.Thermodynamics", "DWSIM.Thermodynamics.PropertyPackages.State"), "Liquid");
                dynamic vaporPhase = Enum.Parse(T("DWSIM.Thermodynamics", "DWSIM.Thermodynamics.PropertyPackages.State"), "Vapor");
                double[] hL = new double[last + 1], hV = new double[last + 1];
                for (int n = 0; n <= last; n++) {
                    double[] x = Vec(data.LiquidCompositions[n]), y = Vec(data.VaporCompositions[n]);
                    hL[n] = (double)pp.DW_CalcEnthalpy(x, (double)data.StageTemperatures[n], (double)data.StagePressures[n], liquidPhase) * (double)pp.AUX_MMM(x) / 1000;
                    hV[n] = (double)pp.DW_CalcEnthalpy(y, (double)data.StageTemperatures[n], (double)data.StagePressures[n], vaporPhase) * (double)pp.AUX_MMM(y) / 1000;
                }
                double available = 0;
                for (int n = 0; n <= last; n++) available += (double)data.FeedFlows[n] * (double)data.FeedEnthalpies[n] / 1000
                    - (double)data.LiquidSideDraws[n] * hL[n] - (double)data.VaporSideDraws[n] * hV[n];
                double otherHeat = Vec((object)data.StageHeats).Take(last).Sum();
                record["documented_first_bottoms_estimate_mol_s"] = (available - otherHeat - (double)data.VaporFlows[0] * hV[0] - qr) / hL[last];
                var watch = Stopwatch.StartNew();
                dynamic solver = Activator.CreateInstance(T("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps.SolvingMethods."
                    + (mode.StartsWith("ns-") ? "NaphtaliSandholmMethod" : "WangHenkeMethod")));
                try {
                    object result = solver.SolveColumn(data);
                    record["returned"] = true;
                    record["output"] = Snapshot(result);
                } catch (Exception error) {
                    record["returned"] = false; record["error"] = error.ToString();
                    record["frames"] = new StackTrace(error, true).GetFrames().Select(frame => new { method = frame.GetMethod().ToString(), il_offset = frame.GetILOffset() }).ToArray();
                }
                if (mode.StartsWith("ns-")) record["duty_equation_probe"] = ProbeDutyEquation((object)solver, (Dictionary<string, object>)record["input"], -qr);
                record["elapsed_ms"] = watch.ElapsedMilliseconds;
                string convergenceReport = column.ColumnSolverConvergenceReport;
                if (!string.IsNullOrEmpty(convergenceReport)) File.WriteAllText(Path.ChangeExtension(outputFile, "." + mode + ".txt"), convergenceReport);
                var trace = new StringBuilder();
                var seen = new HashSet<string>();
                foreach (object item in (IEnumerable)T("DWSIM.Inspector", "DWSIM.Inspector.Host").GetField("Items").GetValue(null)) Inspect(item, seen, trace);
                File.WriteAllText(Path.ChangeExtension(outputFile, "." + mode + ".inspector.txt"), trace.ToString());
                T("DWSIM.GlobalSettings", "DWSIM.GlobalSettings.Settings").GetProperty("InspectorEnabled").SetValue(null, false, null);
                Console.WriteLine(mode + ": returned=" + record["returned"] + "; initial B=" + record["documented_first_bottoms_estimate_mol_s"] + "; ms=" + watch.ElapsedMilliseconds);
            }
            return 0;
        } catch (Exception error) {
            report["error"] = error.ToString(); Console.Error.WriteLine(error); return 1;
        } finally {
            File.WriteAllText(outputFile, new JavaScriptSerializer { MaxJsonLength = 32 * 1024 * 1024 }.Serialize(report));
            automation.ReleaseResources();
        }
    }
    private static void Inspect(dynamic item, HashSet<string> seen, StringBuilder trace) {
        if (!seen.Add((string)item.ID)) return;
        if (((string)item.Name).Contains("Bubble") || ((string)item.MethodName).Contains("Solve")) {
            trace.AppendLine((string)item.Name);
            foreach (string paragraph in item.Paragraphs) trace.AppendLine(paragraph);
        }
        foreach (object child in item.Items) Inspect(child, seen, trace);
    }

    private static object ProbeDutyEquation(object solver, Dictionary<string, object> input, double heat) {
        var type = solver.GetType();
        var flags = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
        double maxT = (double)type.GetField("_maxT", flags).GetValue(solver);
        double maxV = (double)type.GetField("_maxvc", flags).GetValue(solver);
        double maxL = (double)type.GetField("_maxlc", flags).GetValue(solver);
        double[] t = Vec(input["StageTemperatures"]), l = Vec(input["LiquidFlows"]), v = Vec(input["VaporFlows"]), side = Vec(input["LiquidSideDraws"]);
        double[][] x = Rows(input["LiquidCompositions"]), y = Rows(input["VaporCompositions"]);
        int nc = x[0].Length, width = 2*nc + 1;
        double[] point = new double[t.Length * width];
        for (int n = 0; n < t.Length; n++) {
            point[n*width] = t[n] / maxT;
            for (int c = 0; c < nc; c++) {
                point[n*width+c+1] = (n == 0 ? side[0] * x[0][c] : v[n] * y[n][c]) / maxV;
                point[n*width+c+1+nc] = l[n] * x[n][c] / maxL;
            }
        }
        type.GetField("grad", flags).SetValue(solver, true);
        var dutyField = type.GetField("_spval2", flags);
        var function = type.GetMethod("FunctionValue");
        dutyField.SetValue(solver, heat);
        double[] first = (double[])function.Invoke(solver, new object[] {point});
        dutyField.SetValue(solver, heat * 1.5);
        double[] second = (double[])function.Invoke(solver, new object[] {point});
        int energyRow = (t.Length - 1)*width;
        point[energyRow] += 1.0 / maxT;
        double[] perturbed = (double[])function.Invoke(solver, new object[] {point});
        if (first.Concat(second).Concat(perturbed).Any(a => double.IsNaN(a) || double.IsInfinity(a))) throw new InvalidOperationException("Nonfinite residual probe");
        return new { equation_count = first.Length, max_residual_change_for_50_percent_duty_change = first.Zip(second, (a,b) => Math.Abs(a-b)).Max(),
            reboiler_energy_row = energyRow, original_reboiler_energy_residual = first[energyRow], changed_duty_reboiler_energy_residual = second[energyRow],
            changed_temperature_reboiler_energy_residual = perturbed[energyRow], max_residual_change_for_1K_bottom_temperature_change = second.Zip(perturbed, (a,b) => Math.Abs(a-b)).Max() };
    }
}

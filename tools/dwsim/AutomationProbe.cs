// Local research probe: inspect installed APIs, optionally solve a copied sample.
// Compile for .NET Framework x64; see documentation/DWSIM_DATA_GENERATION.md.
using System;
using System.IO;
using System.Reflection;
using System.Linq;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Web.Script.Serialization;

public static class AutomationProbe
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Length < 1) return 2;
        string install = Path.GetFullPath(args[0]);
        AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs e) {
            string file = Path.Combine(install, new AssemblyName(e.Name).Name + ".dll");
            return File.Exists(file) ? Assembly.LoadFrom(file) : null;
        };
        Directory.SetCurrentDirectory(install);
        try {
            var automationAssembly = Assembly.LoadFrom(Path.Combine(install, "DWSIM.Automation.dll"));
            var automationType = automationAssembly.GetType("DWSIM.Automation.Automation3", true);
            var unitAssembly = Assembly.LoadFrom(Path.Combine(install, "DWSIM.UnitOperations.dll"));
            var columnType = unitAssembly.GetType("DWSIM.UnitOperations.UnitOperations.Column", true);
            if (args.Length == 1) {
                Console.WriteLine("Automation assembly: " + automationAssembly.GetName().Version);
                foreach (var method in automationType.GetMethods().Where(m => m.Name.Contains("Flowsheet")))
                    Console.WriteLine("API " + method);
                foreach (var prop in columnType.GetProperties().Where(p => p.DeclaringType == columnType))
                    Console.WriteLine("PROPERTY " + prop.PropertyType + " " + prop.Name);
                foreach (var field in columnType.GetFields())
                    Console.WriteLine("FIELD " + field.FieldType + " " + field.Name);
                return 0;
            }
            if (args.Length != 3 && args.Length != 4) throw new ArgumentException("Expected install directory, copied flowsheet, output JSON, optional case overrides JSON.");
            string input = Path.GetFullPath(args[1]);
            string output = Path.GetFullPath(args[2]);
            var total = Stopwatch.StartNew();
            Console.WriteLine("Creating automation instance");
            dynamic automation = Activator.CreateInstance(automationType);
            try {
                Console.WriteLine("Loading " + input);
                dynamic flowsheet = automation.LoadFlowsheet2(input);
                var serializer = new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024 };
                var overrides = args.Length == 4 ? serializer.Deserialize<Dictionary<string, double>>(File.ReadAllText(Path.GetFullPath(args[3]))) : new Dictionary<string, double>();
                foreach (string key in overrides.Keys) {
                    if (key != "feed_temperature_delta_K" && key != "pressure_multiplier" && key != "reboiler_heat_added_W" && key != "initialize_from_native_flow_solution" && key != "column_solver_code"
                        && key != "condenser_reflux_ratio" && key != "bottoms_mol_s" && key != "solver_tolerance" && key != "normalize_feed_mole_fractions")
                        throw new ArgumentException("Unsupported case override: " + key);
                    if (double.IsNaN(overrides[key]) || double.IsInfinity(overrides[key])) throw new ArgumentException("Nonfinite override: " + key);
                }
                object initialization = null;
                if (overrides.ContainsKey("initialize_from_native_flow_solution")) {
                    if (overrides["initialize_from_native_flow_solution"] != 1.0) throw new ArgumentException("Initialization flag must equal 1.");
                    var initializationTimer = Stopwatch.StartNew();
                    var initializationErrors = new List<string>();
                    foreach (Exception error in automation.CalculateFlowsheet4(flowsheet)) initializationErrors.Add(error.ToString());
                    if (initializationErrors.Count > 0 || !(bool)flowsheet.Solved) throw new InvalidOperationException("Native flow-spec initialization failed: " + string.Join("\n", initializationErrors));
                    foreach (DictionaryEntry entry in (IDictionary)flowsheet.SimulationObjects) {
                        if (!columnType.IsInstanceOfType(entry.Value)) continue;
                        dynamic column = entry.Value;
                        column.SetInitialTemperatureEstimates((double[])column.Tf);
                        column.SetInitialLiquidMolarFlowEstimates((double[])column.Lf);
                        column.SetInitialVaporMolarFlowEstimates((double[])column.Vf);
                        double[][] liquid = ((IEnumerable)column.xf).Cast<double[]>().Select(row => (double[])row.Clone()).ToArray();
                        double[][] vapor = ((IEnumerable)column.yf).Cast<double[]>().Select(row => (double[])row.Clone()).ToArray();
                        column.SetInitialMolarCompositionEstimates(liquid, vapor);
                        column.InitialEstimates.DistillateFlowRate = (double)column.LSSf[0];
                        column.InitialEstimates.BottomsFlowRate = (double)column.Lf[(int)column.NumberOfStages - 1];
                        column.InitialEstimates.VaporProductFlowRate = (double)column.Vf[0];
                        column.UseTemperatureEstimates = true; column.UseLiquidFlowEstimates = true;
                        column.UseVaporFlowEstimates = true; column.UseCompositionEstimates = true;
                    }
                    initialization = new { source = "fresh solve of unchanged native flow-specified input", elapsed_milliseconds = initializationTimer.ElapsedMilliseconds };
                }
                var compounds = new List<object>();
                foreach (DictionaryEntry entry in (IDictionary)flowsheet.SelectedCompounds) compounds.Add(ScalarProperties(entry.Value));
                var inputColumns = new List<object>();
                foreach (DictionaryEntry entry in (IDictionary)flowsheet.SimulationObjects) {
                    if (!columnType.IsInstanceOfType(entry.Value)) continue;
                    dynamic column = entry.Value;
                    var connections = new List<object>();
                    foreach (DictionaryEntry streamEntry in (IDictionary)column.MaterialStreams) {
                        dynamic connection = streamEntry.Value;
                        dynamic stream = ((IDictionary)flowsheet.SimulationObjects)[(string)connection.StreamID];
                        if (connection.StreamBehavior.ToString() == "Feed") {
                            if (overrides.ContainsKey("normalize_feed_mole_fractions")) {
                                if (overrides["normalize_feed_mole_fractions"] != 1) throw new ArgumentException("Normalization flag must equal 1.");
                                double totalFlow = stream.GetMolarFlow();
                                double[] composition = stream.GetOverallComposition();
                                double sum = composition.Sum();
                                if (sum <= 0 || composition.Any(v => v < 0 || double.IsNaN(v) || double.IsInfinity(v))) throw new ArgumentException("Invalid feed composition");
                                stream.SetOverallComposition(composition.Select(v => v / sum).ToArray());
                                stream.SetMolarFlow(totalFlow);
                            }
                            if (overrides.ContainsKey("feed_temperature_delta_K")) stream.SetTemperature((double)stream.GetTemperature() + overrides["feed_temperature_delta_K"]);
                            if (overrides.ContainsKey("pressure_multiplier")) stream.SetPressure((double)stream.GetPressure() * overrides["pressure_multiplier"]);
                        }
                        var record = ScalarProperties(streamEntry.Value);
                        record["tag"] = (string)stream.GraphicObject.Tag;
                        record["stage_index"] = (int)column.StageIndex((string)connection.AssociatedStage);
                        record["specified_flow_mol_s"] = (double)connection.FlowRate.Value;
                        connections.Add(record);
                    }
                    if (overrides.ContainsKey("pressure_multiplier")) foreach (dynamic stage in column.Stages) stage.P = (double)stage.P * overrides["pressure_multiplier"];
                    if (overrides.ContainsKey("column_solver_code")) {
                        double code = overrides["column_solver_code"];
                        if (code != 0 && code != 1 && code != 2) throw new ArgumentException("Solver code: 0 Wang-Henke, 1 modified Wang-Henke, 2 Napthali-Sandholm.");
                        column.SolvingMethodName = code == 0 ? "Wang-Henke (Bubble Point)" : code == 1 ? "Modified Wang-Henke (Bubble Point)" : "Napthali-Sandholm (Simultaneous Correction)";
                    }
                    if (overrides.ContainsKey("bottoms_mol_s")) {
                        if (overrides.ContainsKey("reboiler_heat_added_W") || overrides["bottoms_mol_s"] <= 0) throw new ArgumentException("Bottoms flow must be positive and cannot accompany a duty override.");
                        dynamic productFlowType = Enum.Parse(unitAssembly.GetType("DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps.ColumnSpec+SpecType", true), "Product_Molar_Flow_Rate");
                        column.Specs["R"].SType = productFlowType; column.Specs["R"].SpecValue = overrides["bottoms_mol_s"]; column.Specs["R"].SpecUnit = "mol/s";
                    }
                    if (overrides.ContainsKey("condenser_reflux_ratio")) {
                        if (overrides["condenser_reflux_ratio"] <= 0) throw new ArgumentException("Reflux ratio must be positive.");
                        dynamic refluxType = Enum.Parse(unitAssembly.GetType("DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps.ColumnSpec+SpecType", true), "Stream_Ratio");
                        column.Specs["C"].SType = refluxType; column.Specs["C"].SpecValue = overrides["condenser_reflux_ratio"]; column.Specs["C"].SpecUnit = "";
                    }
                    if (overrides.ContainsKey("solver_tolerance")) {
                        if (overrides["solver_tolerance"] <= 0) throw new ArgumentException("Solver tolerance must be positive.");
                        column.InternalLoopTolerance = overrides["solver_tolerance"]; column.ExternalLoopTolerance = overrides["solver_tolerance"];
                    }
                    if (overrides.ContainsKey("reboiler_heat_added_W")) {
                        if (overrides["reboiler_heat_added_W"] < 0) throw new ArgumentException("Reboiler heat added must be nonnegative.");
                        // Wang-Henke accepts positive reboiler heat input in the specification,
                        // but reports it as negative stage heat (ReboilerDuty). Energy units are kW.
                        dynamic heatDutyType = Enum.Parse(unitAssembly.GetType(
                            "DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps.ColumnSpec+SpecType", true), "Heat_Duty");
                        column.Specs["R"].SType = heatDutyType;
                        column.Specs["R"].SpecValue = overrides["reboiler_heat_added_W"] / 1000.0;
                        column.Specs["R"].SpecUnit = "kW";
                        column.Specs["R"].StageNumber = -1;
                    }
                    var specs = new Dictionary<string, object>();
                    foreach (DictionaryEntry spec in (IDictionary)column.Specs) specs[(string)spec.Key] = ScalarProperties(spec.Value);
                    inputColumns.Add(new { id = (string)column.Name, connections = connections, specifications = specs,
                        property_package = column.PropertyPackage.GetType().FullName,
                        use_temperature_estimates = (bool)column.UseTemperatureEstimates, use_flow_estimates = (bool)column.UseLiquidFlowEstimates || (bool)column.UseVaporFlowEstimates,
                        use_composition_estimates = (bool)column.UseCompositionEstimates, internal_tolerance = (double)column.InternalLoopTolerance, external_tolerance = (double)column.ExternalLoopTolerance });
                }
                var inputStreams = Streams(flowsheet);
                if (overrides.Count > 0) {
                    string configuredCase = Path.ChangeExtension(output, ".configured.dwxml");
                    if (string.Equals(input, configuredCase, StringComparison.OrdinalIgnoreCase)) throw new ArgumentException("Configured case must differ from input.");
                    automation.SaveFlowsheet2(flowsheet, configuredCase);
                }
                Console.WriteLine("Solving sample");
                var solve = Stopwatch.StartNew();
                var errors = new List<string>();
                foreach (Exception error in automation.CalculateFlowsheet4(flowsheet)) errors.Add(error.ToString());
                solve.Stop();
                bool solved = flowsheet.Solved;
                var columns = new List<object>();
                foreach (DictionaryEntry entry in (IDictionary)flowsheet.SimulationObjects) {
                    object item = entry.Value;
                    if (!columnType.IsInstanceOfType(item)) continue;
                    dynamic column = item;
                    var profile = new Dictionary<string, object>();
                    profile["id"] = (string)column.Name;
                    profile["tag"] = (string)column.GraphicObject.Tag;
                    profile["type"] = item.GetType().FullName;
                    profile["calculated"] = (bool)column.Calculated;
                    profile["stages"] = (int)column.NumberOfStages;
                    profile["condenser_type"] = column.CondenserType.ToString();
                    profile["solver"] = (string)column.SolvingMethodName;
                    if (solved && errors.Count == 0 && (bool)column.Calculated) {
                        foreach (string field in new[] { "compids", "Tf", "P0", "Lf", "Vf", "xf", "yf", "LSSf", "VSSf" })
                            profile[field] = columnType.GetField(field).GetValue(item);
                        profile["condenser_duty_kW"] = (double)column.CondenserDuty;
                        profile["reboiler_duty_kW"] = (double)column.ReboilerDuty;
                        profile["actual_reflux_ratio"] = (double)column.Lf[0] / (double)column.LSSf[0];
                    }
                    columns.Add(profile);
                }
                var result = new Dictionary<string, object> {
                    { "purpose", "DWSIM native sample data; not yet qualified as V3 training labels" },
                    { "version", automationAssembly.GetName().Version.ToString() },
                    { "input", input }, { "solved", solved },
                    { "solve_milliseconds", solve.ElapsedMilliseconds },
                    { "elapsed_milliseconds", total.ElapsedMilliseconds },
                    { "errors", errors }, { "columns", columns }, { "compounds", compounds },
                    { "input_columns", inputColumns }, { "input_streams", inputStreams },
                    { "output_streams", solved && errors.Count == 0 ? Streams(flowsheet) : new List<object>() },
                    { "overrides", overrides }, { "initialization", initialization }
                };
                File.WriteAllText(output, serializer.Serialize(result));
                if (solved && errors.Count == 0 && overrides.Count > 0) {
                    string savedCase = Path.ChangeExtension(output, ".solved.dwxml");
                    if (string.Equals(input, savedCase, StringComparison.OrdinalIgnoreCase)) throw new ArgumentException("Output case must differ from input.");
                    automation.SaveFlowsheet2(flowsheet, savedCase);
                    Console.WriteLine("Saved case=" + savedCase);
                }
                Console.WriteLine("Solved=" + solved + "; errors=" + errors.Count + "; native columns=" + columns.Count);
                Console.WriteLine("Export=" + output);
                return solved && errors.Count == 0 && columns.Count > 0 ? 0 : 1;
            } finally { automation.ReleaseResources(); }
        } catch (Exception e) {
            Console.Error.WriteLine(e.ToString());
            return 1;
        }
    }

    private static Dictionary<string, object> ScalarProperties(object value) {
        var result = new Dictionary<string, object>();
        foreach (var p in value.GetType().GetProperties()) {
            if (p.GetIndexParameters().Length != 0 || !p.CanRead) continue;
            var t = p.PropertyType;
            if (t == typeof(string) || t == typeof(double) || t == typeof(int) || t == typeof(bool)) result[p.Name] = p.GetValue(value, null);
            else if (t.IsEnum) result[p.Name] = p.GetValue(value, null).ToString();
        }
        return result;
    }

    private static List<object> Streams(dynamic flowsheet) {
        var result = new List<object>();
        foreach (DictionaryEntry entry in (IDictionary)flowsheet.SimulationObjects) {
            if (entry.Value.GetType().FullName != "DWSIM.Thermodynamics.Streams.MaterialStream") continue;
            dynamic stream = entry.Value;
            result.Add(new { id = (string)stream.Name, tag = (string)stream.GraphicObject.Tag,
                temperature_K = (double)stream.GetTemperature(), pressure_Pa = (double)stream.GetPressure(),
                molar_flow_mol_s = (double)stream.GetMolarFlow(), mass_flow_kg_s = (double)stream.GetMassFlow(),
                enthalpy_kJ_kg = (double)stream.GetMassEnthalpy(), composition = (double[])stream.GetOverallComposition() });
        }
        return result;
    }
}

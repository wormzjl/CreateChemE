using System;
using System.IO;
using System.Reflection;
using System.Linq;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Web.Script.Serialization;

// Isolated local research worker. Input and output paths must be absolute.
internal static class HydrocarbonWorker
{
    private static string install;
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 16777216 };
    private static Type TypeOf(string dll, string name) { return Assembly.LoadFrom(Path.Combine(install, dll + ".dll")).GetType(name, true); }
    private static dynamic New(string dll, string name) { return Activator.CreateInstance(TypeOf(dll, name)); }
    private static dynamic EnumOf(string dll, string name, string value) { return Enum.Parse(TypeOf(dll, name), value); }
    private static double Number(Dictionary<string, object> data, string key) { return Convert.ToDouble(data[key]); }
    private static double[] Vector(object value) { return ((IEnumerable)value).Cast<object>().Select(Convert.ToDouble).ToArray(); }
    private static dynamic Add(dynamic fs, string kind, string tag, int x, int y) {
        return fs.AddObject(EnumOf("DWSIM.Interfaces", "DWSIM.Interfaces.Enums.GraphicObjects.ObjectType", kind), x, y, tag);
    }

    [STAThread]
    private static int Main(string[] args)
    {
        install = Path.GetFullPath(args[0]);
        string inputPath = Path.GetFullPath(args[1]);
        string output = Path.GetFullPath(args[2]);
        Directory.CreateDirectory(output);
        AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs e) {
            string path = Path.Combine(install, new AssemblyName(e.Name).Name + ".dll");
            return File.Exists(path) ? Assembly.LoadFrom(path) : null;
        };
        Directory.SetCurrentDirectory(install);
        var report = new Dictionary<string, object>();
        dynamic automation = null;
        try {
            var data = Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(inputPath));
            report["input"] = data;
            report["engine_version"] = TypeOf("DWSIM.Automation", "DWSIM.Automation.Automation3").Assembly.GetName().Version.ToString();
            automation = New("DWSIM.Automation", "DWSIM.Automation.Automation3");
            dynamic fs = automation.CreateFlowsheet();
            var cpChecks = new List<object>();
            if (data.ContainsKey("native_compounds")) {
                foreach (string name in (IEnumerable)data["native_compounds"]) fs.AddCompound(name);
                var nativeProperties = new List<object>();
                foreach (DictionaryEntry item in (IDictionary)fs.SelectedCompounds) {
                    var values = new Dictionary<string, object>();
                    foreach (var property in item.Value.GetType().GetProperties()) {
                        if (property.PropertyType == typeof(string) || property.PropertyType == typeof(double) || property.PropertyType == typeof(int) || property.PropertyType == typeof(bool))
                            values[property.Name] = property.GetValue(item.Value, null);
                    }
                    nativeProperties.Add(values);
                }
                report["native_compound_properties"] = nativeProperties;
            } else foreach (Dictionary<string, object> component in (IEnumerable)data["components"]) {
                dynamic cp = New("DWSIM.Thermodynamics", "DWSIM.Thermodynamics.BaseClasses.ConstantProperties");
                cp.Name = (string)component["id"];
                cp.ID = 100000 + cpChecks.Count;
                cp.OriginalDB = "User"; cp.CurrentDB = "User";
                cp.CAS_Number = "V3-" + cp.Name;
                cp.Molar_Weight = Number(component, "molecularWeightKgPerMol") * 1000;
                cp.Normal_Boiling_Point = Number(component, "normalBoilingPointKelvin"); cp.NBP = cp.Normal_Boiling_Point;
                cp.Critical_Temperature = Number(component, "criticalTemperatureKelvin");
                cp.Critical_Pressure = Number(component, "criticalPressurePascal");
                cp.Acentric_Factor = Number(component, "acentricFactor");
                cp.Critical_Compressibility = 0.27;
                cp.Critical_Volume = 0.27 * 8314.0 * cp.Critical_Temperature / cp.Critical_Pressure;
                // This pilot's Cp is linear in T-298.15, expressed here in J/(kmol K).
                if (Number(component, "cpC") != 0 || Number(component, "cpD") != 0 || Number(component, "cpE") != 0 || Number(component, "cpF") != 0)
                    throw new InvalidOperationException("Pilot supports linear heat capacity only.");
                cp.IdealgasCpEquation = "100";
                cp.Ideal_Gas_Heat_Capacity_Const_A = 1000 * (Number(component, "cpA") - 298.15 * Number(component, "cpB"));
                cp.Ideal_Gas_Heat_Capacity_Const_B = 1000 * Number(component, "cpB");
                cp.LiquidDensityEquation = "100";
                cp.Liquid_Density_Const_A = Number(component, "standardLiquidDensityKgPerCubicMetre");
                ((IDictionary)fs.SelectedCompounds).Add((string)cp.Name, cp);
                string message = "";
                cpChecks.Add(new { id = (string)cp.Name, cp_550_raw = (double)cp.GetIdealGasHeatCapacity(550.0, ref message), message = message });
            }
            report["cp_checks"] = cpChecks;
            dynamic pp = New("DWSIM.Thermodynamics", "DWSIM.Thermodynamics.PropertyPackages.PengRobinsonPropertyPackage");
            fs.AddPropertyPackage(pp);
            dynamic feed = Add(fs, "MaterialStream", "Feed", 50, 200);
            feed.PropertyPackage = pp;
            var feedFlows = Vector(data["feed_mol_s"]);
            double feedTotal = feedFlows.Sum();
            double[] z = feedFlows.Select(v => v / feedTotal).ToArray();
            feed.SetTemperature(Number(data, "feed_temperature_K"));
            double feedP = Number(data, "top_pressure_Pa") + Number(data, "feed_tray") * Number(data, "stage_pressure_drop_Pa");
            feed.SetPressure(feedP);
            feed.SetMolarFlow(feedTotal);
            feed.SetOverallComposition(z);
            pp.CurrentMaterialStream = feed;
            var nativeCpChecks = new List<object>();
            foreach (DictionaryEntry item in (IDictionary)fs.SelectedCompounds) {
                foreach (double temperature in new[] {298.15, 350.0, 400.0, 550.0}) {
                    nativeCpChecks.Add(new { id = (string)item.Key, temperature_K = temperature, cp_kJ_kg_K = (double)pp.AUX_CPi((string)item.Key, temperature) });
                }
            }
            report["package_cp_checks"] = nativeCpChecks;
            var kij = new List<double[]>();
            double[,] interactions = pp.RET_VKij();
            for (int i = 0; i < z.Length; i++) {
                double[] row = new double[z.Length];
                for (int j = 0; j < z.Length; j++) row[j] = interactions[i, j];
                kij.Add(row);
            }
            report["binary_interactions"] = kij;
            var densities = new List<double>();
            for (int i = 0; i < z.Length; i++) {
                double[] pure = new double[z.Length]; pure[i] = 1.0;
                densities.Add((double)pp.AUX_LIQDENS(298.15, pure, 101325.0));
            }
            report["reference_liquid_density_kg_m3"] = densities;
            var propertyChecks = new List<object>();
            foreach (double temperature in data.ContainsKey("native_compounds") ? new[] {300.0, 350.0, 370.0, 400.0, 450.0} : new[] {400.0, 550.0, 700.0}) {
                foreach (string phase in new[] {"Liquid", "Vapor"}) {
                    dynamic state = EnumOf("DWSIM.Thermodynamics", "DWSIM.Thermodynamics.PropertyPackages.State", phase);
                    double[] phi = pp.DW_CalcFugCoeff(z, temperature, 250000.0, state);
                    double h = pp.DW_CalcEnthalpy(z, temperature, 250000.0, state);
                    propertyChecks.Add(new { temperature_K = temperature, pressure_Pa = 250000.0, composition = z, phase = phase, phi = phi, enthalpy_kJ_kg = h });
                }
            }
            report["property_checks"] = propertyChecks;
            File.WriteAllText(Path.Combine(output, "properties.json"), Json.Serialize(report));
            Console.WriteLine("Property checks exported");
            if (args.Length > 3 && args[3] == "properties") return 0;

            dynamic column = Add(fs, "DistillationColumn", "V3 binary pilot", 250, 150);
            dynamic distillate = Add(fs, "MaterialStream", "Distillate", 450, 50);
            dynamic bottoms = Add(fs, "MaterialStream", "Bottoms", 450, 350);
            dynamic condenserHeat = Add(fs, "EnergyStream", "Condenser heat", 450, 120);
            dynamic reboilerHeat = Add(fs, "EnergyStream", "Reboiler heat", 50, 350);
            column.PropertyPackage = pp; distillate.PropertyPackage = pp; bottoms.PropertyPackage = pp;
            column.SetNumberOfStages(Convert.ToInt32(data["trays"]) + 2);
            column.CondenserType = EnumOf("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.Column+condtype", "Total_Condenser");
            fs.ConnectObjects(feed.GraphicObject, column.GraphicObject, 0, 0);
            fs.ConnectObjects(column.GraphicObject, distillate.GraphicObject, 0, 0);
            fs.ConnectObjects(column.GraphicObject, bottoms.GraphicObject, 1, 0);
            fs.ConnectObjects(column.GraphicObject, condenserHeat.GraphicObject, 10, 0);
            fs.ConnectObjects(reboilerHeat.GraphicObject, column.GraphicObject, 0, 10);
            column.SyncConnectedStreams();
            column.SetStreamFeedStage(feed, Convert.ToInt32(data["feed_tray"]));
            report["feed_stage_index"] = (int)column.GetStreamFeedStageIndex(feed);
            for (int node = 0; node < (int)column.NumberOfStages; node++) {
                column.Stages[node].P = Number(data, "top_pressure_Pa") + node * Number(data, "stage_pressure_drop_Pa");
                column.Stages[node].Efficiency = 1.0;
            }
            column.Specs["C"].SType = EnumOf("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps.ColumnSpec+SpecType", "Stream_Ratio");
            column.Specs["C"].SpecValue = Number(data, "reflux_ratio"); column.Specs["C"].SpecUnit = "";
            column.Specs["R"].SType = EnumOf("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps.ColumnSpec+SpecType", "Heat_Duty");
            column.Specs["R"].SpecValue = Number(data, "reboiler_duty_W") / 1000; column.Specs["R"].SpecUnit = "kW";
            column.Specs["R"].StageNumber = -1;
            if (data.ContainsKey("bottoms_mol_s")) {
                column.Specs["R"].SType = EnumOf("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.Auxiliary.SepOps.ColumnSpec+SpecType", "Product_Molar_Flow_Rate");
                column.Specs["R"].SpecValue = Number(data, "bottoms_mol_s"); column.Specs["R"].SpecUnit = "mol/s";
            }
            column.SolvingMethodName = data.ContainsKey("solver") ? (string)data["solver"] : "Wang-Henke (Bubble Point)";
            report["solver"] = (string)column.SolvingMethodName;
            column.MaxIterations = 200;
            column.InternalLoopTolerance = 1e-7; column.ExternalLoopTolerance = 1e-7;
            var solveTrace = new List<object>();
            bool matched = false;
            for (int iteration = 0; iteration < 16; iteration++) {
                var timer = Stopwatch.StartNew();
                var errors = new List<string>();
                foreach (Exception error in automation.CalculateFlowsheet4(fs)) errors.Add(error.ToString());
                timer.Stop();
                solveTrace.Add(new { iteration = iteration, elapsed_ms = timer.ElapsedMilliseconds, errors = errors, solved = (bool)fs.Solved });
                report["solve_trace"] = solveTrace;
                if (errors.Count != 0 || !(bool)column.Calculated) throw new InvalidOperationException("DWSIM solve failed: " + string.Join("\n", errors));
                if (data.ContainsKey("condenser_mode") && (string)data["condenser_mode"] == "saturated") { matched = true; break; }
                double difference = (double)column.Tf[0] - Number(data, "condenser_temperature_K");
                Console.WriteLine("Condenser T=" + column.Tf[0] + "; subcool=" + column.TotalCondenserSubcoolingDeltaT);
                if (Math.Abs(difference) < 1e-6) { matched = true; break; }
                column.TotalCondenserSubcoolingDeltaT = Math.Max(0, (double)column.TotalCondenserSubcoolingDeltaT + difference);
            }
            if (!matched) throw new InvalidOperationException("Condenser temperature did not match.");
            var profile = new Dictionary<string, object>();
            var columnType = TypeOf("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.Column");
            foreach (string field in new[] { "compids", "Tf", "P0", "Lf", "Vf", "xf", "yf", "LSSf", "VSSf" }) profile[field] = columnType.GetField(field).GetValue(column);
            profile["condenser_duty_kW"] = (double)column.CondenserDuty;
            profile["reboiler_duty_kW"] = (double)column.ReboilerDuty;
            profile["subcooling_K"] = (double)column.TotalCondenserSubcoolingDeltaT;
            report["property_package_type"] = pp.GetType().FullName;
            var streams = new List<object>();
            foreach (dynamic stream in new object[] { feed, distillate, bottoms }) {
                streams.Add(new { tag = (string)stream.GraphicObject.Tag, molar_flow_mol_s = (double)stream.GetMolarFlow(),
                    temperature_K = (double)stream.GetTemperature(), pressure_Pa = (double)stream.GetPressure(),
                    composition = (double[])stream.GetOverallComposition(), enthalpy_kJ_kg = (double)stream.GetMassEnthalpy() });
            }
            report["streams"] = streams;
            report["profile"] = profile;
            report["solved"] = (bool)fs.Solved && (bool)column.Calculated;
            automation.SaveFlowsheet2(fs, Path.Combine(output, "hydrocarbon-template.dwxml"));
            return 0;
        } catch (Exception error) {
            report["solved"] = false; report["error"] = error.ToString(); Console.Error.WriteLine(error);
            return 1;
        } finally {
            File.WriteAllText(Path.Combine(output, "result.json"), Json.Serialize(report));
            if (automation != null) automation.ReleaseResources();
        }
    }
}

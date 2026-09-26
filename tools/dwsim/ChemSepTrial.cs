using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Collections;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Web.Script.Serialization;
using CapeOpen;

internal static class ChemSepTrial {
    private static string install;
    private static Type T(string dll, string name) { return Assembly.LoadFrom(Path.Combine(install, dll + ".dll")).GetType(name, true); }
    private static dynamic New(string dll, string name) { return Activator.CreateInstance(T(dll, name)); }
    private static dynamic Add(dynamic fs, string kind, string name, int x, int y) {
        dynamic objectType = Enum.Parse(T("DWSIM.Interfaces", "DWSIM.Interfaces.Enums.GraphicObjects.ObjectType"), kind);
        return fs.AddObject(objectType, x, y, name);
    }
    [ComImport, Guid("00000109-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPersistStream {
        void GetClassID(out Guid id);
        [PreserveSig] int IsDirty();
        void Load(IStream stream);
        void Save(IStream stream, [MarshalAs(UnmanagedType.Bool)] bool clearDirty);
        void GetSizeMax(out long size);
    }
    [STAThread]
    private static int Main(string[] args) {
        install = Path.GetFullPath(args[0]);
        string input = Path.GetFullPath(args[1]), output = Path.GetFullPath(args[2]);
        Directory.CreateDirectory(output);
        AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs e) {
            string path = Path.Combine(install, new AssemblyName(e.Name).Name + ".dll");
            return File.Exists(path) ? Assembly.LoadFrom(path) : null;
        };
        Directory.SetCurrentDirectory(install);
        dynamic automation = null, unit = null;
        try {
            automation = New("DWSIM.Automation", "DWSIM.Automation.Automation3");
            Console.WriteLine("Automation initialized");
            dynamic source = automation.LoadFlowsheet2(input);
            Console.WriteLine("Source loaded");
            dynamic fs = automation.CreateFlowsheet();
            foreach (DictionaryEntry c in (IDictionary)source.SelectedCompounds) ((IDictionary)fs.SelectedCompounds).Add(c.Key, c.Value);
            dynamic pp = New("DWSIM.Thermodynamics", "DWSIM.Thermodynamics.PropertyPackages.PengRobinsonPropertyPackage");
            fs.AddPropertyPackage(pp);
            dynamic originalFeed = ((IDictionary)source.SimulationObjects).Values.Cast<object>().Single(v => v.GetType().Name == "MaterialStream" && (string)((dynamic)v).GraphicObject.Tag == "Oil");
            dynamic feed = Add(fs, "MaterialStream", "Oil", 30, 150);
            feed.PropertyPackage = pp;
            double[] composition = originalFeed.GetOverallComposition();
            double sum = composition.Sum();
            feed.SetOverallComposition(composition.Select(v => v / sum).ToArray());
            feed.SetTemperature((double)originalFeed.GetTemperature()); feed.SetPressure((double)originalFeed.GetPressure());
            feed.SetMolarFlow((double)originalFeed.GetMolarFlow());
            Console.WriteLine("Flashing feed");
            foreach (Exception error in automation.CalculateFlowsheet4(fs)) throw new InvalidOperationException("Feed flash failed", error);
            Console.WriteLine("Creating CAPE-OPEN wrapper");
            // The headless AddObject path opens the interactive selector for CAPE-OPEN.
            // Construct the wrapper and graphic separately, then select the known registration.
            unit = New("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.CapeOpenUO");
            unit.Name = "CO-" + Guid.NewGuid().ToString();
            unit.SetFlowsheet(fs);
            dynamic graphic = New("DWSIM.Drawing.SkiaSharp", "DWSIM.Drawing.SkiaSharp.GraphicObjects.Shapes.CAPEOPENGraphic");
            graphic.X = 250; graphic.Y = 150; graphic.Width = 100; graphic.Height = 100;
            graphic.Tag = "ChemSep petroleum column";
            graphic.Name = (string)unit.Name; graphic.Owner = unit;
            unit.GraphicObject = graphic;
            fs.AddGraphicObject(graphic);
            fs.AddSimulationObject(unit);
            Console.WriteLine("Wrapper created");
            unit.PropertyPackage = pp;
            foreach (dynamic candidate in (IEnumerable)T("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.CapeOpenUO").GetMethod("SearchRegisteredUnitOperations").Invoke(null, new object[] {false})) {
                if (((string)candidate.TypeName).StartsWith("ChemSepUO.")) { unit._seluo = candidate; break; }
            }
            if (unit._seluo == null) throw new InvalidOperationException("ChemSep unit not found in registered CAPE-OPEN list.");
            Console.WriteLine("Selected " + unit._seluo.TypeName);
            unit.InstantiateSelected();
            object native = unit.GetCAPEOPENObject();
            LoadState(native, args.Length > 3 ? Path.GetFullPath(args[3]) : Path.Combine(Path.GetDirectoryName(output), "chemsep-loaded-state.bin"));
            // Preserve the unit's saved default UseCOSEThermo=true; automatic SEP initialization
            // has no old profiles to reuse. Some native option parameters are read-only.
            unit._ports.Clear(); unit._params.Clear();
            unit.GetPorts(); unit.GetParams(); unit.CreateConnectors();
            foreach (ICapeUnitPort port in unit._ports) Console.WriteLine("PORT " + ((ICapeIdentification)port).ComponentName + " " + port.direction);
            dynamic top = Add(fs, "MaterialStream", "Distillate", 500, 70);
            dynamic bottom = Add(fs, "MaterialStream", "Bottoms", 500, 250);
            dynamic sideLight = Add(fs, "MaterialStream", "SideLight", 500, 350);
            dynamic sideHeavy = Add(fs, "MaterialStream", "SideHeavy", 500, 450);
            top.PropertyPackage = pp; bottom.PropertyPackage = pp; sideLight.PropertyPackage = pp; sideHeavy.PropertyPackage = pp;
            fs.ConnectObjects(feed.GraphicObject, unit.GraphicObject, 0, 0);
            fs.ConnectObjects(unit.GraphicObject, top.GraphicObject, 0, 0);
            fs.ConnectObjects(unit.GraphicObject, bottom.GraphicObject, 1, 0);
            fs.ConnectObjects(unit.GraphicObject, sideLight.GraphicObject, 2, 0);
            fs.ConnectObjects(unit.GraphicObject, sideHeavy.GraphicObject, 3, 0);
            unit.UpdatePortsFromConnectors();
            string message = "";
            bool valid = ((ICapeUnit)native).Validate(ref message);
            Console.WriteLine("ChemSep valid=" + valid + "; " + message);
            if (!valid) throw new InvalidOperationException("ChemSep configuration invalid: " + message);
            Console.WriteLine("Solving ChemSep column");
            var clock = System.Diagnostics.Stopwatch.StartNew();
            var errors = new List<string>();
            foreach (Exception error in automation.CalculateFlowsheet4(fs)) errors.Add(error.ToString());
            clock.Stop();
            if (errors.Count > 0 && native is ECapeUser) {
                var capeError = (ECapeUser)native;
                Console.Error.WriteLine("CHEMSEP: " + capeError.description + "; scope=" + capeError.scope + "; operation=" + capeError.operation);
                File.WriteAllText(Path.Combine(output,"native-error.txt"), capeError.description + "\n" + capeError.scope + "\n" + capeError.moreInfo);
            }
            if (native is ICapeUnitReport) {
                var reporter = (ICapeUnitReport)native;
                int index = 0;
                foreach (string reportName in (IEnumerable)reporter.reports) {
                    reporter.selectedReport = reportName; string contents = ""; reporter.ProduceReport(ref contents);
                    File.WriteAllText(Path.Combine(output,"native-report-" + index++ + ".txt"), reportName + "\n" + contents);
                    Console.WriteLine("REPORT " + reportName);
                }
            }
            SaveState(native, Path.Combine(output, "chemsep-state.bin"));
            automation.SaveFlowsheet2(fs, Path.Combine(output, "chemsep-configured.dwxml"));
            byte[] persisted = File.ReadAllBytes(Path.Combine(output, "chemsep-state.bin"));
            int length = BitConverter.ToInt32(persisted, 4);
            File.WriteAllText(Path.Combine(output, "chemsep-calculated.sep"), System.Text.Encoding.ASCII.GetString(persisted, 8, length));
            var streams = new List<object>();
            if ((bool)fs.Solved && errors.Count == 0) foreach (dynamic stream in new object[] {feed,top,bottom,sideLight,sideHeavy}) {
                streams.Add(new { tag = (string)stream.GraphicObject.Tag, molar_flow_mol_s = (double)stream.GetMolarFlow(), mass_flow_kg_s = (double)stream.GetMassFlow(),
                    temperature_K = (double)stream.GetTemperature(), pressure_Pa = (double)stream.GetPressure(), enthalpy_kJ_kg = (double)stream.GetMassEnthalpy(), composition = (double[])stream.GetOverallComposition() });
            }
            File.WriteAllText(Path.Combine(output,"result.json"), new JavaScriptSerializer {MaxJsonLength=16777216}.Serialize(new { dwsim_version = T("DWSIM.Automation","DWSIM.Automation.Automation3").Assembly.GetName().Version.ToString(), solved=(bool)fs.Solved, calculated=(bool)unit.Calculated, solve_ms=clock.ElapsedMilliseconds, errors=errors, streams=streams }));
            Console.WriteLine("Solved=" + fs.Solved + "; errors=" + errors.Count + "; ms=" + clock.ElapsedMilliseconds);
            foreach (string error in errors) Console.Error.WriteLine(error);
            foreach (ICapeParameter parameter in unit._params) Console.WriteLine("PARAM " + ((ICapeIdentification)parameter).ComponentName + "=" + parameter.value);
            return (bool)fs.Solved && errors.Count == 0 ? 0 : 1;
        } catch (Exception error) { Console.Error.WriteLine(error); return 1; }
        finally { if (unit != null) unit.Terminate(); if (automation != null) automation.ReleaseResources(); }
    }
    private static void SaveState(object unit, string path) {
        using (var memory = new MemoryStream()) {
            var wrapper = (IStream)Activator.CreateInstance(T("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.Auxiliary.CapeOpen.ComIStreamWrapper"), BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null, new object[] {memory}, null);
            ((IPersistStream)unit).Save(wrapper, false);
            File.WriteAllBytes(path, memory.ToArray());
        }
    }
    private static void LoadState(object unit, string path) {
        using (var memory = new MemoryStream(File.ReadAllBytes(path))) {
            var wrapper = (IStream)Activator.CreateInstance(T("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.Auxiliary.CapeOpen.ComIStreamWrapper"), BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null, new object[] {memory}, null);
            ((IPersistStream)unit).Load(wrapper);
        }
    }
    private static void SetParameter(object unit, string name, object value) {
        var parameters = (ICapeCollection)((ICapeUtilities)unit).parameters;
        for (int i=1;i<=parameters.Count();i++) { object p=parameters.Item(i); if (((ICapeIdentification)p).ComponentName == name) { ((ICapeParameter)p).value=value; return; } }
        throw new InvalidOperationException("ChemSep parameter not exposed: " + name);
    }
}

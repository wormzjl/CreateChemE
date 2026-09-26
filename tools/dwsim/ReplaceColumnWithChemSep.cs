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

internal static class ReplaceColumnWithChemSep {
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
            dynamic fs = source;
            bool reload = args.Length > 3 && args[3] == "--reload";
            bool energyPorts = args.Length > 4 && args[4] == "--energy-ports";
            dynamic originalColumn = ((IDictionary)fs.SimulationObjects).Values.Cast<object>().Single(v => v.GetType().Name == (reload ? "CapeOpenUO" : "DistillationColumn"));
            dynamic pp = originalColumn.PropertyPackage;
            dynamic feed = ByTag(fs, "Oil");
            dynamic top = ByTag(fs, "Light Product"), bottom = ByTag(fs, "\"Heavy\" Product");
            dynamic sideLight = ByTag(fs, "Light Intermediate product"), sideHeavy = ByTag(fs, "Intermediate Product");
            dynamic qc = ByTag(fs, "Condenser Duty"), qr = ByTag(fs, "Reboiler Duty");
            string originalName = originalColumn.Name, originalTag = originalColumn.GraphicObject.Tag;
            double gx = originalColumn.GraphicObject.X, gy = originalColumn.GraphicObject.Y;
            if (reload) { unit = originalColumn; goto Configured; }
            // Normalize the sample's rounded fractions while retaining its specified total flow.
            double[] composition = feed.GetOverallComposition();
            double sum = composition.Sum(), totalFlow = feed.GetMolarFlow();
            feed.SetOverallComposition(composition.Select(v => v / sum).ToArray());
            feed.SetMolarFlow(totalFlow);
            Console.WriteLine("Original composition sum=" + sum.ToString("R"));
            fs.DeleteObject(originalName, false);
            Console.WriteLine("Creating CAPE-OPEN wrapper");
            // The headless AddObject path opens the interactive selector for CAPE-OPEN.
            // Construct the wrapper and graphic separately, then select the known registration.
            unit = New("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.CapeOpenUO");
            unit.Name = originalName;
            unit.SetFlowsheet(fs);
            dynamic graphic = New("DWSIM.Drawing.SkiaSharp", "DWSIM.Drawing.SkiaSharp.GraphicObjects.Shapes.CAPEOPENGraphic");
            graphic.X = (float)gx; graphic.Y = (float)gy; graphic.Width = 100; graphic.Height = 160;
            graphic.Tag = originalTag;
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
            LoadState(native, Path.GetFullPath(args[3]), energyPorts);
            // Preserve the unit's saved default UseCOSEThermo=true; automatic SEP initialization
            // has no old profiles to reuse. Some native option parameters are read-only.
            unit._ports.Clear(); unit._params.Clear();
            unit.GetPorts(); unit.GetParams();
            unit.UpdateParams();
            unit._ports.Clear(); unit.GetPorts(); unit.CreateConnectors();
            foreach (ICapeUnitPort port in unit._ports) Console.WriteLine("PORT " + ((ICapeIdentification)port).ComponentName + " " + port.direction);
            fs.ConnectObjects(feed.GraphicObject, unit.GraphicObject, 0, 0);
            fs.ConnectObjects(unit.GraphicObject, top.GraphicObject, 0, 0);
            fs.ConnectObjects(unit.GraphicObject, bottom.GraphicObject, 1, 0);
            fs.ConnectObjects(unit.GraphicObject, sideLight.GraphicObject, 2, 0);
            fs.ConnectObjects(unit.GraphicObject, sideHeavy.GraphicObject, 3, 0);
            int inlet = 0;
            foreach (ICapeUnitPort port in unit._ports) {
                if (port.direction != CapePortDirection.CAPE_INLET) continue;
                string name = ((ICapeIdentification)port).ComponentName;
                if (port.portType == CapePortType.CAPE_ENERGY) {
                    if (name == "Column heat duty") { inlet++; continue; }
                    if (!name.ToLowerInvariant().Contains("reb")) throw new InvalidOperationException("Unknown inlet energy port " + name);
                    qr.SetEnergyFlow(27282.5);
                    fs.ConnectObjects(qr.GraphicObject, unit.GraphicObject, 0, inlet);
                }
                inlet++;
            }
            int outlet = 0;
            foreach (ICapeUnitPort port in unit._ports) {
                if (port.direction != CapePortDirection.CAPE_OUTLET) continue;
                string name = ((ICapeIdentification)port).ComponentName;
                if (port.portType == CapePortType.CAPE_ENERGY) {
                    dynamic energy = name.ToLowerInvariant().Contains("cond") ? qc : name.ToLowerInvariant().Contains("reb") ? qr : null;
                    if (energy == null) throw new InvalidOperationException("Unknown energy port " + name);
                    fs.ConnectObjects(unit.GraphicObject, energy.GraphicObject, outlet, 0);
                }
                outlet++;
            }
            Configured:
            object nativeUnit = unit.GetCAPEOPENObject();
            unit.UpdatePortsFromConnectors();
            foreach (dynamic obj in ((IDictionary)fs.SimulationObjects).Values) { obj.Calculated = false; obj.GraphicObject.Calculated = false; }
            string message = "";
            bool valid = ((ICapeUnit)nativeUnit).Validate(ref message);
            Console.WriteLine("ChemSep valid=" + valid + "; " + message);
            if (!valid) throw new InvalidOperationException("ChemSep configuration invalid: " + message);
            Console.WriteLine("Solving ChemSep column");
            var clock = System.Diagnostics.Stopwatch.StartNew();
            var errors = new List<string>();
            foreach (Exception error in automation.CalculateFlowsheet4(fs)) errors.Add(error.ToString());
            clock.Stop();
            if (errors.Count > 0 && nativeUnit is ECapeUser) {
                var capeError = (ECapeUser)nativeUnit;
                Console.Error.WriteLine("CHEMSEP: " + capeError.description + "; scope=" + capeError.scope + "; operation=" + capeError.operation);
                File.WriteAllText(Path.Combine(output,"native-error.txt"), capeError.description + "\n" + capeError.scope + "\n" + capeError.moreInfo);
            }
            if (nativeUnit is ICapeUnitReport) {
                var reporter = (ICapeUnitReport)nativeUnit;
                int index = 0;
                foreach (string reportName in (IEnumerable)reporter.reports) {
                    reporter.selectedReport = reportName; string contents = ""; reporter.ProduceReport(ref contents);
                    File.WriteAllText(Path.Combine(output,"native-report-" + index++ + ".txt"), reportName + "\n" + contents);
                    Console.WriteLine("REPORT " + reportName);
                }
            }
            SaveState(nativeUnit, Path.Combine(output, "chemsep-state.bin"));

            byte[] persisted = File.ReadAllBytes(Path.Combine(output, "chemsep-state.bin"));
            int length = BitConverter.ToInt32(persisted, 4);
            File.WriteAllText(Path.Combine(output, "chemsep-calculated.sep"), System.Text.Encoding.ASCII.GetString(persisted, 8, length));
            if (!energyPorts && (bool)fs.Solved && errors.Count == 0) {
                string sep = System.Text.Encoding.ASCII.GetString(persisted, 8, length);
                double condenserW = Duty(sep, "Condenser Heat Duty"), reboilerW = Duty(sep, "Reboiler Heat Duty");
                qc.SetEnergyFlow(-condenserW/1000.0); qr.SetEnergyFlow(reboilerW/1000.0);
                string note = "Disconnected reference from this ChemSep solve. Duty is specified/calculated inside ChemSep; these reference values do not update when solving in the GUI. DWSIM 10.2.5 CAPE energy port passes kW as W.";
                qc.Annotation = note; qr.Annotation = note;
                qc.GraphicObject.Tag = "Condenser Duty (reference)"; qr.GraphicObject.Tag = "Reboiler Duty (reference)";
                foreach (dynamic drawing in ((IDictionary)fs.GraphicObjects).Values) {
                    if (drawing.GetType().Name == "TextGraphic" && ((string)drawing.Text).Contains("Bubble-Point"))
                        drawing.Text = "PETROLEUM DISTILLATION - CHEMSEP REPLACEMENT\n30 original pseudocomponents; original Peng-Robinson package.\n12 stages; liquid side draws on stages 4 and 8; feed on stage 8.\nDuty is specified inside ChemSep. The disconnected duty streams\nare reference values from the saved solve; see ChemSep reports\nfor current calculated duties after changing the case.";
                }
                unit.Annotation = "ChemSep replacement of the shipped Petroleum Distillation column. Same 30 compounds and Peng-Robinson property package; 12 stages, feed stage 8, liquid side draws 40 mol/s at stage 4 and 70 mol/s at stage 8. Specs: D=290.01 mol/s and Qr=27.2825 MW (ChemSep rounding of the fresh native baseline). Original feed fractions normalized from sum 0.999998, retaining total molar flow. Duty streams are disconnected reference values; inspect ChemSep reports for current duties.";
            }
            automation.SaveFlowsheet2(fs, Path.Combine(output, "chemsep-configured.dwxml"));
            var streams = new List<object>();
            if ((bool)fs.Solved && errors.Count == 0) foreach (dynamic stream in new object[] {feed,top,bottom,sideLight,sideHeavy}) {
                streams.Add(new { id = (string)stream.Name, tag = (string)stream.GraphicObject.Tag, molar_flow_mol_s = (double)stream.GetMolarFlow(), mass_flow_kg_s = (double)stream.GetMassFlow(),
                    temperature_K = (double)stream.GetTemperature(), pressure_Pa = (double)stream.GetPressure(), enthalpy_kJ_kg = (double)stream.GetMassEnthalpy(), composition = (double[])stream.GetOverallComposition() });
            }
            File.WriteAllText(Path.Combine(output,"result.json"), new JavaScriptSerializer {MaxJsonLength=16777216}.Serialize(new { dwsim_version = T("DWSIM.Automation","DWSIM.Automation.Automation3").Assembly.GetName().Version.ToString(), solved=(bool)fs.Solved, calculated=(bool)unit.Calculated, solve_ms=clock.ElapsedMilliseconds, errors=errors, streams=streams, energy_streams=new [] { new {tag=(string)qc.GraphicObject.Tag, energy_kW=(double)qc.GetEnergyFlow()}, new {tag=(string)qr.GraphicObject.Tag,energy_kW=(double)qr.GetEnergyFlow()} }, object_count=((IDictionary)fs.SimulationObjects).Count, compounds=((IDictionary)fs.SelectedCompounds).Count, property_package=pp.GetType().FullName, reload=reload, energy_ports=energyPorts }));
            Console.WriteLine("Solved=" + fs.Solved + "; errors=" + errors.Count + "; ms=" + clock.ElapsedMilliseconds);
            foreach (string error in errors) Console.Error.WriteLine(error);
            foreach (ICapeParameter parameter in unit._params) Console.WriteLine("PARAM " + ((ICapeIdentification)parameter).ComponentName + "=" + parameter.value);
            return (bool)fs.Solved && errors.Count == 0 ? 0 : 1;
        } catch (Exception error) { Console.Error.WriteLine(error); return 1; }
        finally { if (unit != null) unit.Terminate(); if (automation != null) automation.ReleaseResources(); }
    }
    private static double Duty(string sep, string name) {
        var m = System.Text.RegularExpressions.Regex.Match(sep, @"(?m)^\[" + name + @"\]\s*\r?\n\s*([-+0-9.Ee]+)\s+Duty");
        if (!m.Success) throw new InvalidDataException("Missing duty " + name);
        return double.Parse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture);
    }
    private static dynamic ByTag(dynamic fs, string tag) { return ((IDictionary)fs.SimulationObjects).Values.Cast<object>().Single(v => (string)((dynamic)v).GraphicObject.Tag == tag || (string)((dynamic)v).GraphicObject.Tag == tag + " (reference)"); }
    private static void SaveState(object unit, string path) {
        using (var memory = new MemoryStream()) {
            var wrapper = (IStream)Activator.CreateInstance(T("DWSIM.UnitOperations", "DWSIM.UnitOperations.UnitOperations.Auxiliary.CapeOpen.ComIStreamWrapper"), BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null, new object[] {memory}, null);
            ((IPersistStream)unit).Save(wrapper, false);
            File.WriteAllBytes(path, memory.ToArray());
        }
    }
    private static void LoadState(object unit, string path, bool energyPorts) {
        byte[] data = File.ReadAllBytes(path);
        // EnergyPorts is read-only through ICapeParameter. Enable its persisted VARIANT_BOOL.
        // Find the observed length-prefixed UTF-16 name only in the parameter trailer.
        byte[] key = System.Text.Encoding.Unicode.GetBytes("EnergyPorts\0");
        int match = -1;
        for (int i = 8 + BitConverter.ToInt32(data, 4); i <= data.Length - key.Length - 2; i++) {
            if (data.Skip(i).Take(key.Length).SequenceEqual(key)) { if (match >= 0) throw new InvalidDataException("Duplicate EnergyPorts parameter"); match = i; }
        }
        if (match < 4 || BitConverter.ToInt32(data, match-4) != key.Length || data[match+key.Length] != 0 || data[match+key.Length+1] != 0)
            throw new InvalidDataException("Unexpected EnergyPorts persistence schema");
        if (energyPorts) { data[match+key.Length] = 255; data[match+key.Length+1] = 255; }
        using (var memory = new MemoryStream(data)) {
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







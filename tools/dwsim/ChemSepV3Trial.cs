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

internal static class ChemSepV3Trial {
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
            if(args.Length>3 && args[3]=="verify-configured") {
                dynamic reloaded=automation.LoadFlowsheet2(Path.Combine(output,"chemsep-configured.dwxml"));
                Console.WriteLine("Reloaded configured case: compounds="+((IDictionary)reloaded.SelectedCompounds).Count+" objects="+((IDictionary)reloaded.SimulationObjects).Count);
                return 0;
            }
            var json = new JavaScriptSerializer { MaxJsonLength=16777216 };
            var data = json.Deserialize<Dictionary<string,object>>(File.ReadAllText(input));
            var spec = (Dictionary<string,object>)data["input"];
            dynamic source = automation.LoadFlowsheet2(Path.Combine(Path.GetDirectoryName(input), "source-feed.dwxml"));
            Console.WriteLine("Source loaded");
            dynamic fs = automation.CreateFlowsheet();
            bool includeWater=!data.ContainsKey("include_water")||Convert.ToBoolean(data["include_water"]);
            bool waterFirst=includeWater&&data.ContainsKey("water_first")&&Convert.ToBoolean(data["water_first"]);
            if(waterFirst) fs.AddCompound("Water");
            foreach (DictionaryEntry c in (IDictionary)source.SelectedCompounds) ((IDictionary)fs.SelectedCompounds).Add(c.Key, c.Value);
            if(includeWater&&!waterFirst) fs.AddCompound("Water");
            dynamic pp = data.ContainsKey("instrument_thermo") && Convert.ToBoolean(data["instrument_thermo"])
                ? Activator.CreateInstance(Assembly.GetExecutingAssembly().GetType("ChemSepLoggedPr78",true))
                : New("DWSIM.Thermodynamics", "DWSIM.Thermodynamics.PropertyPackages.PengRobinson1978PropertyPackage");
            if(data.ContainsKey("diagnostic_flash_limit")) Assembly.GetExecutingAssembly().GetType("ChemSepLoggedPr78").GetField("MaximumDiagnosticFlashCalls").SetValue(null,Convert.ToInt32(data["diagnostic_flash_limit"]));
            if(data.ContainsKey("validate_pv_flash")&&Convert.ToBoolean(data["validate_pv_flash"])) Assembly.GetExecutingAssembly().GetType("ChemSepLoggedPr78").GetField("FlashValidationDirectory").SetValue(null,output);
            if(data.ContainsKey("correct_water_pv")&&Convert.ToBoolean(data["correct_water_pv"])) Assembly.GetExecutingAssembly().GetType("ChemSepLoggedPr78").GetField("CorrectWaterPv").SetValue(null,true);
            fs.AddPropertyPackage(pp);
            if(data.ContainsKey("disable_extra_properties") && Convert.ToBoolean(data["disable_extra_properties"])) pp.CalculateAdditionalMaterialStreamProperties=false;
            if(data.ContainsKey("flash_algorithm")) {
                pp.FlashAlgorithm=New("DWSIM.Thermodynamics","DWSIM.Thermodynamics.PropertyPackages.Auxiliary.FlashAlgorithms."+(string)data["flash_algorithm"]);
                pp.ForceNewFlashAlgorithmInstance=false;
            }
            if(data.ContainsKey("flash_settings")) foreach(var setting in (Dictionary<string,object>)data["flash_settings"]) {
                object key=Enum.Parse(T("DWSIM.Interfaces","DWSIM.Interfaces.Enums.FlashSetting"),setting.Key);
                ((IDictionary)pp.FlashSettings)[key]=Convert.ToString(setting.Value,System.Globalization.CultureInfo.InvariantCulture);
            }
            dynamic feed = Add(fs, "MaterialStream", "Oil", 30, 150);
            feed.PropertyPackage = pp;
            double[] flows = ((IEnumerable)spec["feedComponentMolarFlowsMolPerSecond"]).Cast<object>().Select(Convert.ToDouble).ToArray();
            if(includeWater) flows=flows.Concat(new[]{0.0}).ToArray();
            if(waterFirst)flows=new[]{0.0}.Concat(flows.Take(flows.Length-1)).ToArray();
            feed.SetOverallComposition(flows.Select(v=>v/flows.Sum()).ToArray());
            feed.SetTemperature(Convert.ToDouble(spec["feedTemperatureKelvin"])); feed.SetPressure(Convert.ToDouble(spec["topPressurePascal"]));
            feed.SetMolarFlow(flows.Sum());
            var streamsToExport = new List<object> {feed};
            var steamStreams = new List<object>();
            foreach(Dictionary<string,object> steamSpec in (IEnumerable)spec["steamFeeds"]) {
                if(!includeWater) throw new InvalidOperationException("Steam requires Water in the component list.");
                dynamic steam=Add(fs,"MaterialStream","Steam"+(steamStreams.Count+1),30,300+steamStreams.Count*70);
                steam.PropertyPackage=pp;
                var z=new double[flows.Length];z[waterFirst?0:z.Length-1]=1;
                steam.SetOverallComposition(z);steam.SetTemperature(Convert.ToDouble(steamSpec["temperatureKelvin"]));
                steam.SetPressure(Convert.ToDouble(spec["topPressurePascal"]));steam.SetMolarFlow(Convert.ToDouble(steamSpec["molarFlowMolPerSecond"]));
                steamStreams.Add(steam);streamsToExport.Add(steam);
            }
            pp.CurrentMaterialStream=feed;
            var components = new List<object>();
            foreach(DictionaryEntry c in (IDictionary)fs.SelectedCompounds) {
                dynamic cp=c.Value;
                components.Add(new {name=(string)cp.Name,cas=(string)cp.CAS_Number,mw=(double)cp.Molar_Weight,tc=(double)cp.Critical_Temperature,pc=(double)cp.Critical_Pressure,omega=(double)cp.Acentric_Factor});
            }
            double[,] kij=pp.RET_VKij();var kijRows=new List<double[]>();
            for(int i=0;i<flows.Length;i++){var row=new double[flows.Length];for(int j=0;j<flows.Length;j++)row[j]=kij[i,j];kijRows.Add(row);}
            var flashSettings=new Dictionary<string,string>();foreach(DictionaryEntry setting in (IDictionary)pp.FlashSettings) flashSettings[setting.Key.ToString()]=setting.Value.ToString();
            File.WriteAllText(Path.Combine(output,"native-properties.json"),json.Serialize(new {components=components,kij=kijRows,caloric_mode=pp.EnthalpyEntropyCpCvCalculationMode.ToString(),package="PengRobinson1978PropertyPackage",actual_flash_base=pp.FlashBase.GetType().Name,flash_settings=flashSettings}));
            if(args.Length>3 && args[3]=="properties") return 0;
            if(args.Length>3 && args[3]=="contract") {NativeThermoContractProbe.Run(pp,feed,data,output);return 0;}
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
            graphic.Tag = "ChemSep V3 TJL19 column";
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
            if(data.ContainsKey("only_k_h") && Convert.ToBoolean(data["only_k_h"])) SetParameter(native,"UseOnlyKValuesAndEnthalpyFromCOSE",true);
            if(data.ContainsKey("perturb_derivatives") && Convert.ToBoolean(data["perturb_derivatives"])) {
                SetParameter(native,"UsePerturbedDerivativesOnly",true);
                SetParameter(native,"UsePerturbed_ddX",true);
            }
            SetParameter(native,"LogPropertyCalls",true);
            // DWSIM restores its parameter copies immediately before Calculate.
            // Capture native option changes so RestoreParams cannot overwrite them.
            unit.UpdateParams();
            foreach (ICapeUnitPort port in unit._ports) Console.WriteLine("PORT " + ((ICapeIdentification)port).ComponentName + " " + port.direction);
            int inlet=0;
            foreach(ICapeUnitPort port in unit._ports) if(port.direction==CapePortDirection.CAPE_INLET && port.portType==CapePortType.CAPE_MATERIAL) {
                string name=((ICapeIdentification)port).ComponentName;
                dynamic connected=name.StartsWith("Feed2",StringComparison.OrdinalIgnoreCase) ? steamStreams[0] : (object)feed;
                fs.ConnectObjects(connected.GraphicObject,unit.GraphicObject,0,inlet++);
                Console.WriteLine("CONNECT " + name + " <- " + connected.GraphicObject.Tag);
            }
            int outlet=0;
            foreach(ICapeUnitPort port in unit._ports) if(port.direction==CapePortDirection.CAPE_OUTLET && port.portType==CapePortType.CAPE_MATERIAL) {
                dynamic product=Add(fs,"MaterialStream",((ICapeIdentification)port).ComponentName,500,50+outlet*70);
                product.PropertyPackage=pp;fs.ConnectObjects(unit.GraphicObject,product.GraphicObject,outlet++,0);streamsToExport.Add(product);
            }
            unit.UpdatePortsFromConnectors();
            string message = "";
            bool valid = ((ICapeUnit)native).Validate(ref message);
            Console.WriteLine("ChemSep valid=" + valid + "; " + message);
            if (!valid) throw new InvalidOperationException("ChemSep configuration invalid: " + message);
            if(data.ContainsKey("configure_only") && Convert.ToBoolean(data["configure_only"])) {
                SaveState(native,Path.Combine(output,"chemsep-state.bin"));
                automation.SaveFlowsheet2(fs,Path.Combine(output,"chemsep-configured.dwxml"));
                File.WriteAllText(Path.Combine(output,"configuration.json"),json.Serialize(new {configured=true,solved=false,validated=valid,note="Configuration only; no ChemSep solve was performed in this export."}));
                Console.WriteLine("Configured case saved; no solve requested for this export.");
                return 0;
            }
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
            if ((bool)fs.Solved && errors.Count == 0) foreach (dynamic stream in streamsToExport) {
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

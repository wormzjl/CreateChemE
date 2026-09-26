using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using TYPEATTR = System.Runtime.InteropServices.ComTypes.TYPEATTR;
using FUNCDESC = System.Runtime.InteropServices.ComTypes.FUNCDESC;
using System.IO;
using System.Reflection;
using CapeOpen;
using System.Collections.Generic;
using System.Web.Script.Serialization;

internal static class ChemSepInspect {
    [ComImport, Guid("37D84F60-42CB-11CE-8135-00AA004BB851"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPersistPropertyBag {
        void GetClassID(out Guid id);
        void InitNew();
        void Load(IPropertyBag bag, IntPtr errorLog);
        void Save(IPropertyBag bag, [MarshalAs(UnmanagedType.Bool)] bool clearDirty, [MarshalAs(UnmanagedType.Bool)] bool allProperties);
    }
    [ComVisible(true), Guid("55272A00-42CB-11CE-8135-00AA004BB851"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IPropertyBag {
        [PreserveSig] int Read([MarshalAs(UnmanagedType.LPWStr)] string name, [In, Out, MarshalAs(UnmanagedType.Struct)] ref object value, IntPtr errorLog);
        [PreserveSig] int Write([MarshalAs(UnmanagedType.LPWStr)] string name, [In, MarshalAs(UnmanagedType.Struct)] ref object value);
    }
    [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
    public class Bag : IPropertyBag {
        public readonly Dictionary<string, object> Values = new Dictionary<string, object>();
        public int Read(string name, ref object value, IntPtr errorLog) { if (!Values.ContainsKey(name)) return unchecked((int)0x80070057); value = Values[name]; return 0; }
        public int Write(string name, ref object value) { Values[name] = value; return 0; }
    }
    [ComImport, Guid("00000109-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPersistStream {
        void GetClassID(out Guid id);
        [PreserveSig] int IsDirty();
        void Load(IStream stream);
        void Save(IStream stream, [MarshalAs(UnmanagedType.Bool)] bool clearDirty);
        void GetSizeMax(out long size);
    }
    [ComImport, Guid("00020400-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDispatch {
        void GetTypeInfoCount(out uint count);
        void GetTypeInfo(uint index, uint locale, out ITypeInfo info);
    }
    [STAThread]
    private static int Main(string[] args) {
        object unit = null;
        try {
            AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs e) {
                string path = Path.Combine("C:/Program Files/DWSIM", new AssemblyName(e.Name).Name + ".dll");
                return File.Exists(path) ? Assembly.LoadFrom(path) : null;
            };
            unit = Activator.CreateInstance(Type.GetTypeFromProgID("ChemSepUO.ChemSep_UnitOperation", true));
            InspectCape(unit, args.Length > 0 ? args[0] : null);
            return 0;
        } catch (Exception error) { Console.Error.WriteLine(error); return 1; }
        finally { if (unit != null && Marshal.IsComObject(unit)) Marshal.FinalReleaseComObject(unit); }
    }
    private static void InspectCape(object unit, string sepFile) {
        var utilities = (ICapeUtilities)unit;
        utilities.Initialize();
        try {
            if (sepFile != null) {
                byte[] baseline = File.ReadAllBytes("build/dwsim-research/chemsep-empty-state.bin");
                byte[] sep = System.Text.Encoding.ASCII.GetBytes(File.ReadAllText(sepFile));
                using (var content = new MemoryStream()) {
                    var writer = new BinaryWriter(content);
                    writer.Write(baseline, 0, 4); writer.Write(sep.Length); writer.Write(sep); writer.Write(baseline, 8, baseline.Length - 8); writer.Flush();
                    content.Position = 0;
                    var wt = Assembly.LoadFrom("C:/Program Files/DWSIM/DWSIM.UnitOperations.dll").GetType("DWSIM.UnitOperations.UnitOperations.Auxiliary.CapeOpen.ComIStreamWrapper", true);
                    var stream = (IStream)Activator.CreateInstance(wt, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null, new object[] {content}, null);
                    ((IPersistStream)unit).Load(stream);
                    Console.WriteLine("Loaded SEP through observed native persistence envelope");
                }
            }
            Console.WriteLine(((ICapeIdentification)unit).ComponentName);
            var parameters = (ICapeCollection)utilities.parameters;
            for (int i = 1; i <= parameters.Count(); i++) {
                object parameter = parameters.Item(i);
                Console.WriteLine("PARAM " + ((ICapeIdentification)parameter).ComponentName + " = " + ((ICapeParameter)parameter).value);
            }
            var ports = (ICapeCollection)((ICapeUnit)unit).ports;
            for (int i = 1; i <= ports.Count(); i++) Console.WriteLine("PORT " + ((ICapeIdentification)ports.Item(i)).ComponentName);
            Console.WriteLine("IPersistFile=" + (unit is IPersistFile));
            Console.WriteLine("IPersistPropertyBag=" + (unit is IPersistPropertyBag));
            if (unit is IPersistPropertyBag) {
                var bag = new Bag();
                ((IPersistPropertyBag)unit).Save(bag, false, true);
                foreach (var entry in bag.Values) Console.WriteLine("BAG " + entry.Key + " (" + (entry.Value == null ? "null" : entry.Value.GetType().Name) + ")=" + entry.Value);
                File.WriteAllText("build/dwsim-research/chemsep-empty-bag.json", new JavaScriptSerializer().Serialize(bag.Values));
            }
            using (var memory = new MemoryStream()) {
                var type = Assembly.LoadFrom("C:/Program Files/DWSIM/DWSIM.UnitOperations.dll").GetType("DWSIM.UnitOperations.UnitOperations.Auxiliary.CapeOpen.ComIStreamWrapper", true);
                var stream = (IStream)Activator.CreateInstance(type, BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic, null, new object[] {memory}, null);
                ((IPersistStream)unit).Save(stream, false);
                File.WriteAllBytes(sepFile == null ? "build/dwsim-research/chemsep-empty-state.bin" : "build/dwsim-research/chemsep-loaded-state.bin", memory.ToArray());
                Console.WriteLine("Persisted bytes=" + memory.Length);
            }
        } finally { utilities.Terminate(); }
    }
    private static void InspectDispatch(object unit) {
            ITypeInfo info;
            ((IDispatch)unit).GetTypeInfo(0, 0, out info);
            ITypeLib library; int index;
            info.GetContainingTypeLib(out library, out index);
            for (int t = 0; t < library.GetTypeInfoCount(); t++) {
                ITypeInfo type; library.GetTypeInfo(t, out type);
                IntPtr ptr; type.GetTypeAttr(out ptr);
                var attr = (TYPEATTR)Marshal.PtrToStructure(ptr, typeof(TYPEATTR));
                string name, doc, help; int context;
                type.GetDocumentation(-1, out name, out doc, out context, out help);
                Console.WriteLine("TYPE " + name + " " + attr.guid + " " + doc);
                for (int f = 0; f < attr.cFuncs; f++) {
                    IntPtr fp; type.GetFuncDesc(f, out fp);
                    var fd = (FUNCDESC)Marshal.PtrToStructure(fp, typeof(FUNCDESC));
                    string[] names = new string[40]; int count;
                    type.GetNames(fd.memid, names, names.Length, out count);
                    Console.WriteLine("  " + fd.invkind + " " + string.Join(", ", names, 0, count));
                    type.ReleaseFuncDesc(fp);
                }
                type.ReleaseTypeAttr(ptr);
            }
    }
}

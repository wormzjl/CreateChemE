using System;
using System.IO;
using System.Reflection;
using System.Linq;

// Read-only inspection of the locally installed API. No GUI or flowsheet mutation.
internal static class InspectApi
{
    [STAThread]
    private static void Main(string[] args)
    {
        string install = Path.GetFullPath(args[0]);
        AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs e) {
            string path = Path.Combine(install, new AssemblyName(e.Name).Name + ".dll");
            return File.Exists(path) ? Assembly.LoadFrom(path) : null;
        };
        Directory.SetCurrentDirectory(install);
        for (int i = 1; i < args.Length; i++) {
            string[] spec = args[i].Split('|');
            var assembly = Assembly.LoadFrom(Path.Combine(install, spec[0] + ".dll"));
            if (spec[1].StartsWith("?")) {
                foreach (var candidate in assembly.GetTypes().Where(t => t.FullName.IndexOf(spec[1].Substring(1), StringComparison.OrdinalIgnoreCase) >= 0)) Console.WriteLine(candidate.FullName);
                continue;
            }
            var type = assembly.GetType(spec[1], true);
            Console.WriteLine("TYPE " + type.FullName);
            if (type.IsEnum) { Console.WriteLine(string.Join(", ", Enum.GetNames(type))); continue; }
            foreach (var member in type.GetMembers().Where(m => spec.Length < 3 || m.Name.IndexOf(spec[2], StringComparison.OrdinalIgnoreCase) >= 0))
                Console.WriteLine(member.MemberType + " " + member + (member is MethodInfo ? " virtual=" + ((MethodInfo)member).IsVirtual : ""));
        }
    }
}

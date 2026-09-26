using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Reflection.Emit;
using System.Collections.Generic;

// Read-only IL inspection: verifies the installed binary rather than assuming public source parity.
internal static class DumpInstalledIl {
    private static void Main(string[] args) {
        string directory = Path.GetFullPath(args[0]);
        AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs e) {
            string path = Path.Combine(directory, new AssemblyName(e.Name).Name + ".dll");
            return File.Exists(path) ? Assembly.LoadFrom(path) : null;
        };
        var codes = typeof(OpCodes).GetFields().Where(f => f.FieldType == typeof(OpCode)).Select(f => (OpCode)f.GetValue(null)).ToDictionary(c => unchecked((ushort)c.Value));
        var type = Assembly.LoadFrom(Path.Combine(directory, args[1] + ".dll")).GetType(args[2], true);
        foreach (var method in type.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static | BindingFlags.Instance).Where(m => m.Name == args[3])) {
            Console.WriteLine("METHOD " + method);
            var body = method.GetMethodBody();
            if (body == null) continue;
            foreach (var local in body.LocalVariables) Console.WriteLine("LOCAL " + local.LocalIndex + " " + local.LocalType);
            byte[] bytes = body.GetILAsByteArray();
            for (int p = 0; p < bytes.Length;) {
                int start = p;
                ushort key = bytes[p++];
                if (key == 0xfe) key = (ushort)(0xfe00 | bytes[p++]);
                var code = codes[key];
                object operand = "";
                switch (code.OperandType) {
                    case OperandType.InlineNone: break;
                    case OperandType.ShortInlineI: operand = (sbyte)bytes[p++]; break;
                    case OperandType.ShortInlineVar: operand = bytes[p++]; break;
                    case OperandType.InlineVar: operand = BitConverter.ToUInt16(bytes, p); p += 2; break;
                    case OperandType.InlineI: operand = BitConverter.ToInt32(bytes, p); p += 4; break;
                    case OperandType.InlineI8: operand = BitConverter.ToInt64(bytes, p); p += 8; break;
                    case OperandType.ShortInlineR: operand = BitConverter.ToSingle(bytes, p); p += 4; break;
                    case OperandType.InlineR: operand = BitConverter.ToDouble(bytes, p); p += 8; break;
                    case OperandType.ShortInlineBrTarget: operand = p + 1 + (sbyte)bytes[p]; p++; break;
                    case OperandType.InlineBrTarget: operand = p + 4 + BitConverter.ToInt32(bytes, p); p += 4; break;
                    case OperandType.InlineSwitch:
                        int count = BitConverter.ToInt32(bytes, p); p += 4;
                        int end = p + 4 * count;
                        var targets = new int[count];
                        for (int i = 0; i < count; i++) { targets[i] = end + BitConverter.ToInt32(bytes, p); p += 4; }
                        operand = string.Join(",", targets); break;
                    default:
                        int token = BitConverter.ToInt32(bytes, p); p += 4;
                        try { operand = code.OperandType == OperandType.InlineString ? "\"" + method.Module.ResolveString(token) + "\"" : method.Module.ResolveMember(token).ToString(); }
                        catch { operand = "token:" + token; }
                        break;
                }
                Console.WriteLine(start.ToString("D6") + " " + code.Name + " " + operand);
            }
        }
    }
}

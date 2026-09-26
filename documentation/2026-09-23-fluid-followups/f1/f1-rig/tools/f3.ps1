# Brings the Minecraft window to the foreground and taps F3 with a real key event (WP5 rig, after the window only).
$ErrorActionPreference = "Stop"
if (-not ([System.Management.Automation.PSTypeName]'WinF3').Type) {
  Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class WinF3 {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
"@
}
$p = Get-Process java | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
if (-not $p) { throw "no Minecraft window" }
[WinF3]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
[WinF3]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 500
if ([WinF3]::GetForegroundWindow() -ne $p.MainWindowHandle) { Write-Output "WARN: not foreground" }
& "C:\Program Files\Java\jdk-21.0.11\bin\java.exe" -cp $PSScriptRoot KeyTap

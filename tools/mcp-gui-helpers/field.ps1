param([Parameter(Mandatory=$true)][int]$Tabs, [string]$Text = "")
# Focus the n-th focusable widget of the open Minecraft screen with real key events and replace its text
# (the bridge's typing does not reach a container screen's EditBox). Adapted from the solid-phase GUI pass.
$ErrorActionPreference = "Stop"
# tools/ copy: the classes are read from this folder; RIG_JDK overrides the JDK's bin folder.
if (-not ([System.Management.Automation.PSTypeName]'Win').Type) {
  Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class Win {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
"@
}
$p = Get-Process java | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
if (-not $p) { throw "no Minecraft window" }
[Win]::ShowWindow($p.MainWindowHandle, 9) | Out-Null
[Win]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 450
if ([Win]::GetForegroundWindow() -ne $p.MainWindowHandle) { Write-Output "WARN: not foreground" }
$java = if ($env:RIG_JDK) { Join-Path $env:RIG_JDK 'java.exe' } else { 'C:\Program Files\Java\jdk-21.0.11\bin\java.exe' }
& $java -cp "$PSScriptRoot" Field $Tabs $Text
Write-Output ("field tabs=" + $Tabs + " text=" + $Text)

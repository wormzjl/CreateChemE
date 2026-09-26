param(
  [Parameter(Mandatory=$true)][ValidateSet('field','f3','click','hover','key','paste','clear','type','desktop')][string]$Action,
  [int]$Tabs = 0,
  [string]$Text = "",
  [int]$X = 0,
  [int]$Y = 0,
  [double]$GuiScale = 1.0,
  [string]$Out = ""
)
# Real desktop input for the dev client through java.awt.Robot, for what the bridge cannot do:
#   field   -Tabs n -Text v   focus the n-th focusable widget with Tab presses, Ctrl+A, Backspace, paste v
#   f3                        tap F3 (toggles the debug overlay; the bridge's press_key cannot)
#   click   -X x -Y y         left-click at a point given in the bridge's GUI coordinates (enumerate_widgets space)
#   hover   -X x -Y y         move the pointer there (tooltips)
#   key     -Text escape      press one key by java.awt.event.KeyEvent name (escape, enter, tab, e, f3 ...)
#   paste   -Text v           clipboard paste into the focused widget (real Ctrl+V)
#   clear                     Ctrl+A, Backspace in the focused widget
#   type    -Text v           synthesized keystrokes (a host IME can eat letters; prefer paste)
#   desktop -Out shot.png     whole-desktop capture, to see where the client window really is
# -GuiScale is the Minecraft GUI scale the coordinates were read at: 2 for the default 854x480 window
# (enumerate_widgets reports 427x240), 1 when options.txt has guiScale:1. Default 1. The window's DPI
# factor is read from Windows (150 % on this host).
#
# Windows refuses SetForegroundWindow from a background process; attaching to the current foreground
# thread's input queue first (AttachThreadInput) is the documented way in and worked every time.
$ErrorActionPreference = "Stop"
$sig = @'
using System;
using System.Runtime.InteropServices;
public class McWin {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr pid);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern bool SetFocus(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
'@
if (-not ("McWin" -as [type])) { Add-Type -TypeDefinition $sig }

$java = if ($env:RIG_JDK) { Join-Path $env:RIG_JDK 'java.exe' } else { 'java' }
$javac = if ($env:RIG_JDK) { Join-Path $env:RIG_JDK 'javac.exe' } else { 'javac' }
function Ensure-Class([string]$cls) {
  if (-not (Test-Path (Join-Path $PSScriptRoot "$cls.class"))) { & $javac -d $PSScriptRoot (Join-Path $PSScriptRoot "$cls.java") }
}

if ($Action -eq 'desktop') {
  Ensure-Class 'Desk'
  if ($Out -eq "") { $Out = Join-Path (Get-Location) 'desktop.png' }
  & $java -cp $PSScriptRoot Desk $Out
  exit
}

$p = Get-Process | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
if (-not $p) { throw "no Minecraft window (is the dev client past the launcher?)" }
$h = $p.MainWindowHandle
$fore = [McWin]::GetForegroundWindow()
$foreThread = [McWin]::GetWindowThreadProcessId($fore, [IntPtr]::Zero)
$mine = [McWin]::GetCurrentThreadId()
[McWin]::AttachThreadInput($mine, $foreThread, $true) | Out-Null
[McWin]::ShowWindow($h, 9) | Out-Null
[McWin]::BringWindowToTop($h) | Out-Null
[McWin]::SetForegroundWindow($h) | Out-Null
[McWin]::SetFocus($h) | Out-Null
[McWin]::AttachThreadInput($mine, $foreThread, $false) | Out-Null
Start-Sleep -Milliseconds 500
if ([McWin]::GetForegroundWindow() -ne $h) { Write-Output "WARN: Minecraft is not the foreground window; keys may go elsewhere" }

switch ($Action) {
  'field' { Ensure-Class 'Field'; & $java -cp $PSScriptRoot Field $Tabs $Text }
  'f3'    { Ensure-Class 'KeyTap'; & $java -cp $PSScriptRoot KeyTap }
  { $_ -in 'click','hover' } {
    Ensure-Class 'Poke'
    # Robot moves in logical (DPI-scaled) pixels, the GUI coordinates are framebuffer pixels / GuiScale,
    # and the framebuffer is physical pixels: screen = client origin + gui * GuiScale / dpiFactor.
    # Measured on this host: 427x240 GUI space, 854x480 framebuffer, 150 % DPI -> factor 2 / 1.5 = 4/3.
    $pt = New-Object McWin+POINT; [McWin]::ClientToScreen($h, [ref]$pt) | Out-Null
    $dpi = [McWin]::GetDpiForWindow($h) / 96.0
    if ($dpi -le 0) { $dpi = 1.0 }
    $sx = [int]($pt.X + $X * $GuiScale / $dpi); $sy = [int]($pt.Y + $Y * $GuiScale / $dpi)
    $verb = if ($Action -eq 'click') { 'click' } else { 'move' }
    & $java -cp $PSScriptRoot Poke $verb $sx $sy
  }
  'key'   { Ensure-Class 'Poke'; & $java -cp $PSScriptRoot Poke key $Text }
  'paste' { Ensure-Class 'Poke'; & $java -cp $PSScriptRoot Poke paste $Text }
  'clear' { Ensure-Class 'Poke'; & $java -cp $PSScriptRoot Poke clear }
  'type'  { Ensure-Class 'Poke'; & $java -cp $PSScriptRoot Poke type $Text }
}
Write-Output ("robot " + $Action + " x=" + $X + " y=" + $Y + " tabs=" + $Tabs + " text=" + $Text)

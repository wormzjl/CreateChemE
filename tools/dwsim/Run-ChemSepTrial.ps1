param([string]$Dwsim='C:\Program Files\DWSIM', [string]$OutputDirectory='build/dwsim-research/chemsep-direct-duty')
$ErrorActionPreference='Stop'
$workspace=(Get-Location).Path
$null=New-Item -ItemType Directory -Path $OutputDirectory -Force
$output=(Resolve-Path $OutputDirectory).Path
$build=(Resolve-Path build/dwsim-research).Path
$compiler='C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
foreach($name in @('ChemSepInspect','ChemSepTrial')) {
  & $compiler /nologo /platform:x64 /r:Microsoft.CSharp.dll /r:System.Web.Extensions.dll "/r:$Dwsim\CapeOpen.dll" "/out:$build\$name.exe" "$workspace\tools\dwsim\$name.cs"
  if($LASTEXITCODE -ne 0){throw "Compilation failed: $name"}
  Copy-Item -LiteralPath "$Dwsim\DWSIM.exe.config" -Destination "$build\$name.exe.config"
}
Copy-Item -LiteralPath "$Dwsim\CapeOpen.dll" -Destination "$build\CapeOpen.dll"
# Obtain an observed native persistence envelope and a shipped CAPE-OPEN SEP template.
& "$build\ChemSepInspect.exe" | Set-Content "$output\empty-state.log"
if($LASTEXITCODE -ne 0){throw 'Could not initialize registered ChemSep.'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead("$Dwsim\samples\Natural Gas Processing Unit with Turbo-Expansion (Classic UI - Windows only).dwxmz")
try {
  $entry=$archive.Entries | Where-Object FullName -like '*.xml' | Select-Object -First 1
  $reader=[IO.StreamReader]::new($entry.Open())
  try{[xml]$source=$reader.ReadToEnd()}finally{$reader.Dispose()}
}finally{$archive.Dispose()}
$unit=$source.DWSIM_Simulation_Data.SimulationObjects.SimulationObject | Where-Object Type -eq DWSIM.UnitOperations.UnitOperations.CapeOpenUO | Select-Object -First 1
$bytes=[Convert]::FromBase64String($unit.SelectSingleNode('PersistedData').InnerText)
if([Text.Encoding]::ASCII.GetString($bytes,0,4) -ne 'CSUO'){throw 'Unknown ChemSep persistence signature.'}
$length=[BitConverter]::ToInt32($bytes,4)
if($length -le 0 -or $length -gt $bytes.Length-8){throw 'Invalid embedded SEP length.'}
$template=Join-Path $output 'shipped-template.sep'
[IO.File]::WriteAllText($template,[Text.Encoding]::ASCII.GetString($bytes,8,$length))
& ./tools/dwsim/Build-ChemSepCase.ps1 -Template $template -Output "$output\petroleum.sep"
& "$build\ChemSepInspect.exe" "$output\petroleum.sep" | Set-Content "$output\loaded-state.log"
if($LASTEXITCODE -ne 0){throw 'ChemSep rejected the generated state.'}
Copy-Item -LiteralPath "$build\chemsep-loaded-state.bin" -Destination "$output\prepared-state.bin"
$inputCase=(Resolve-Path build/dwsim-research/petroleum-native.dwxml).Path
$arguments=@($Dwsim,$inputCase,$output,"$output\prepared-state.bin") | ForEach-Object {
  if($_.Contains('"')){throw 'Quote characters in paths are unsupported.'}; '"'+$_+'"'
}
$process=Start-Process -FilePath "$build\ChemSepTrial.exe" -ArgumentList $arguments -WindowStyle Hidden -PassThru `
  -RedirectStandardOutput "$output\run.stdout.log" -RedirectStandardError "$output\run.stderr.log"
if(-not $process.WaitForExit(60000)){$process.Kill();throw 'ChemSep worker exceeded 60 seconds.'}
$process.Refresh()
if($process.ExitCode -ne 0){throw "ChemSep trial failed; see $output\run.stderr.log"}
Get-Content "$output\result.json" -Raw


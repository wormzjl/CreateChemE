param(
  [string]$Dwsim='C:\Program Files\DWSIM',
  [string]$InputFile='build/dwsim-research/petroleum-native.dwxml',
  [string]$StateFile='build/dwsim-research/chemsep-direct-duty/prepared-state.bin',
  [string]$OutputDirectory='build/dwsim-research/petroleum-chemsep-replacement',
  [switch]$EnergyPorts
)
$ErrorActionPreference='Stop'
$source=(Resolve-Path "$PSScriptRoot/ReplaceColumnWithChemSep.cs").Path
$inputPath=(Resolve-Path $InputFile).Path
$statePath=(Resolve-Path $StateFile).Path
$null=New-Item -ItemType Directory -Force $OutputDirectory
$root=(Resolve-Path $OutputDirectory).Path
$build=Split-Path $root -Parent
$worker=Join-Path $build 'ReplaceColumnWithChemSep.exe'
& 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe' /nologo /platform:x64 /r:Microsoft.CSharp.dll /r:System.Web.Extensions.dll "/r:$Dwsim\CapeOpen.dll" "/out:$worker" $source
if($LASTEXITCODE -ne 0){throw 'Compilation failed.'}
Copy-Item -LiteralPath "$Dwsim\DWSIM.exe.config" -Destination "$worker.config"
Copy-Item -LiteralPath "$Dwsim\CapeOpen.dll" -Destination (Join-Path $build 'CapeOpen.dll')
function Invoke-Worker([string]$case, [string]$folder, [string]$state, [bool]$ports) {
  $null=New-Item -ItemType Directory -Force $folder
  $arguments=@($Dwsim,$case,$folder,$state)
  if($ports){$arguments+='--energy-ports'}
  $quoted=$arguments | ForEach-Object {if($_.Contains('"')){throw 'Quotes in paths unsupported.'}; '"'+$_+'"'}
  $process=Start-Process -FilePath $worker -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput "$folder/run.stdout.log" -RedirectStandardError "$folder/run.stderr.log"
  if(-not $process.WaitForExit(55000)){$process.Kill($true);throw 'ChemSep worker exceeded 55 seconds.'}
  $process.Refresh()
  if($process.ExitCode -ne 0){throw "Replacement solve failed; see $folder/run.stderr.log"}
}
$first=Join-Path $root $(if($EnergyPorts){'connected-energy'}else{'internal-duty'})
Invoke-Worker $inputPath $first $statePath $EnergyPorts.IsPresent
& "$PSScriptRoot/Validate-ChemSepTrial.ps1" -Directory $first -DistillateTag 'Light Product'
if(-not $EnergyPorts){
  $reload=Join-Path $root 'reload'
  Invoke-Worker "$first/chemsep-configured.dwxml" $reload '--reload' $false
  & "$PSScriptRoot/Validate-ChemSepTrial.ps1" -Directory $reload -DistillateTag 'Light Product'
}

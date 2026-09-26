param([string]$Directory='build/dwsim-research/chemsep-v3',[int]$TimeoutSeconds=120)
$ErrorActionPreference='Stop'
$output=(Resolve-Path $Directory).Path
$build=(Resolve-Path build/dwsim-research).Path
$workspace=(Get-Location).Path
$dwsim='C:\Program Files\DWSIM'
& 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe' /nologo /platform:x64 /r:Microsoft.CSharp.dll /r:System.Web.Extensions.dll "/r:$dwsim\CapeOpen.dll" "/r:$dwsim\DWSIM.Thermodynamics.dll" "/r:$dwsim\DWSIM.Interfaces.dll" "/r:$dwsim\DWSIM.SharedClasses.dll" "/out:$build\ChemSepV3Trial.exe" "$workspace\tools\dwsim\ChemSepV3Trial.cs" "$workspace\tools\dwsim\ChemSepLoggedPr78.cs" "$workspace\tools\dwsim\NativeThermoContractProbe.cs" "$workspace\tools\dwsim\CorrectedWaterPvFlash.cs"
if($LASTEXITCODE -ne 0){throw 'Compilation failed.'}
Copy-Item "$dwsim\DWSIM.exe.config" "$build\ChemSepV3Trial.exe.config"
& "$build\ChemSepInspect.exe" "$output\input.sep" > "$output\loaded-state.log"
if($LASTEXITCODE -ne 0){throw 'Native input load failed.'}
Copy-Item "$build\chemsep-loaded-state.bin" "$output\prepared-state.bin"
$arguments=@($dwsim,"$output\input.json",$output,"$output\prepared-state.bin")|ForEach-Object {'"'+$_+'"'}
if(Test-Path "$output\result.json"){Move-Item -LiteralPath "$output\result.json" -Destination "$output\previous-result-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')).json"}
$timer=[Diagnostics.Stopwatch]::StartNew()
$worker=Start-Process -FilePath "$build\ChemSepV3Trial.exe" -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput "$output\run.stdout.log" -RedirectStandardError "$output\run.stderr.log"
Write-Output "Worker PID $($worker.Id)"
while(-not $worker.WaitForExit(1000)){
  if($timer.Elapsed.TotalSeconds -ge $TimeoutSeconds){
    # Kill only this research worker and its descendants, including the native column process.
    $worker.Kill($true)
    $null=$worker.WaitForExit(5000)
    "Stopped worker $($worker.Id) and descendants through Process.Kill(true)."|Set-Content "$output\timeout-cleanup.log"
    @{solved=$false;outcome='timeout';deadline_seconds=$TimeoutSeconds;elapsed_ms=$timer.ElapsedMilliseconds}|ConvertTo-Json|Set-Content "$output\result.json"
    Get-Content "$output\run.stdout.log" -Tail 8
    throw "ChemSep exceeded $TimeoutSeconds seconds; see $output"
  }
}
$worker.Refresh()
Get-Content "$output\run.stdout.log" -Tail 10
Get-Content "$output\run.stderr.log" -Tail 8
Write-Output "Worker exit $($worker.ExitCode); wall $($timer.ElapsedMilliseconds) ms"

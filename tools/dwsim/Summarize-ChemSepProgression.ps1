param([string]$Directory='build/dwsim-research/chemsep-progressive')
$ErrorActionPreference='Stop'
$rows=@(foreach($folder in Get-ChildItem -LiteralPath $Directory -Directory|Sort-Object Name){
  if(-not (Test-Path "$($folder.FullName)/result.json")){continue}
  $data=Get-Content "$($folder.FullName)/input.json" -Raw|ConvertFrom-Json
  $result=Get-Content "$($folder.FullName)/result.json" -Raw|ConvertFrom-Json
  $report=if(Test-Path "$($folder.FullName)/native-report-1.txt"){Get-Content "$($folder.FullName)/native-report-1.txt" -Raw}else{''}
  $nativeError=if(Test-Path "$($folder.FullName)/native-error.txt"){(Get-Content "$($folder.FullName)/native-error.txt" -TotalCount 1)}else{''}
  $iterations=@([regex]::Matches($report,'(?m)^\s*(\d+)\s+([-+]?\d+\.\d+)\s*$'))
  $phase=if($result.outcome -eq 'timeout'){'timeout; no returned native report'}elseif($report.Contains('Run level: Complete model')){'column iterations'}elseif($report.Contains('Run level: Initialization')){'initialization'}else{'before iteration report'}
  $reason=if($result.solved){'converged'}elseif($result.outcome -eq 'timeout'){'90-second worker deadline'}elseif($nativeError -match 'compressibility factor'){'PR78 compressibility calculation failed'}elseif($nativeError -match 'missing values'){'invalid/missing fugacity values'}elseif($report -match 'Convergence not obtained in (\d+) iterations'){'iteration limit: '+$Matches[1]}else{$nativeError}
  $first=if($iterations.Count){[double]$iterations[0].Groups[2].Value}else{$null}
  $last=if($iterations.Count){[double]$iterations[-1].Groups[2].Value}else{$null}
  $init=[regex]::Match($report,'(?m)^Initialization\s+(\d+) milliseconds')
  [ordered]@{
    case=$folder.Name
    steam_mol_s=[double]($data.input.steamFeeds|Measure-Object molarFlowMolPerSecond -Sum).Sum
    cooling_MW=-($data.input.pumparounds|Measure-Object dutyWatts -Sum).Sum/1e6
    pumparounds=@($data.input.pumparounds)
    side_draws=@($data.input.sideDraws).Count
    reboiler_duty_W=($data.input.specifications|Where-Object {$null -ne $_.watts}).watts
    components=$(if($data.include_water -eq $false){19}else{20})
    solved=[bool]$result.solved
    phase=$phase
    reason=$reason
    solve_seconds=$(if($null -ne $result.solve_ms){$result.solve_ms/1000.0}else{$null})
    initialization_seconds=$(if($init.Success){[double]$init.Groups[1].Value/1000.0}else{$null})
    last_reported_iteration=$(if($iterations.Count){[int]$iterations[-1].Groups[1].Value}else{$null})
    first_log_error_over_tolerance=$first
    last_log_error_over_tolerance=$last
    independent_cold_start=$true
    outcome_file=Join-Path $folder.FullName 'result.json'
  }
})
$rows|ConvertTo-Json -Depth 10|Set-Content "$Directory/summary.json"
$rows|ForEach-Object {[pscustomobject]$_}|Select-Object case,steam_mol_s,cooling_MW,solved,solve_seconds,last_reported_iteration,reason|Format-Table -AutoSize

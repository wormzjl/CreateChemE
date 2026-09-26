param(
    [string]$Install = 'C:\Program Files\DWSIM',
    [string]$InputCase = 'build/dwsim-research/petroleum-native.dwxml',
    [string]$BaselineProfile = 'build/dwsim-research/petroleum-native-profile.json',
    [string]$OutputDirectory = 'build/dwsim-research/equivalent-duty'
)
$ErrorActionPreference = 'Stop'
$inputPath = (Resolve-Path -LiteralPath $InputCase).Path
$probePath = (Resolve-Path -LiteralPath 'build/dwsim-research/AutomationProbe.exe').Path
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
$outputPath = (Resolve-Path -LiteralPath $OutputDirectory).Path
$baseline = Get-Content -LiteralPath $BaselineProfile -Raw | ConvertFrom-Json
if (-not $baseline.solved -or $baseline.columns.Count -ne 1) { throw 'A successful single-column baseline is required.' }
$connections = $baseline.input_columns[0].connections
$feedConnections = @($connections | Where-Object StreamBehavior -eq 'Feed')
if ($feedConnections.Count -ne 1) { throw 'This pilot requires exactly one feed.' }
$feedId = $feedConnections[0].StreamID
$feed = $baseline.output_streams | Where-Object id -eq $feedId
$targetDistillate = $baseline.input_columns[0].specifications.C.SpecValue
if ($baseline.input_columns[0].specifications.C.SType -ne 'Product_Molar_Flow_Rate' -or $baseline.input_columns[0].specifications.C.SpecUnit -ne 'mol/s') { throw 'Expected a distillate molar-flow specification.' }
$sideFlow = ($connections | Where-Object StreamBehavior -eq 'Sidedraw' | Measure-Object specified_flow_mol_s -Sum).Sum
$bottoms = $feed.molar_flow_mol_s - $sideFlow - $targetDistillate
if ($bottoms -le 0) { throw 'Material balance gives nonpositive bottoms.' }
$targetHeat = -1000 * $baseline.columns[0].reboiler_duty_kW
$history = [Collections.Generic.List[object]]::new()
$timer = [Diagnostics.Stopwatch]::StartNew()

function Evaluate-Reflux([double]$ratio) {
    $index = $history.Count
    $overrideFile = Join-Path $outputPath "case-$index.overrides.json"
    $profileFile = Join-Path $outputPath "case-$index.profile.json"
    @{
        condenser_reflux_ratio = $ratio
        bottoms_mol_s = $bottoms
        normalize_feed_mole_fractions = 1
        solver_tolerance = 1e-8
    } | ConvertTo-Json | Set-Content -LiteralPath $overrideFile
    $arguments = @($Install, $inputPath, $profileFile, $overrideFile) | ForEach-Object {
        if ($_.Contains('"')) { throw 'Quote characters are not supported in file paths.' }
        '"' + $_ + '"'
    }
    $process = Start-Process -FilePath $probePath -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $outputPath "case-$index.stdout.log") `
        -RedirectStandardError (Join-Path $outputPath "case-$index.stderr.log")
    if (-not $process.WaitForExit(30000)) {
        $process.Kill()
        throw "Native worker exceeded 30 seconds in case $index."
    }
    $process.Refresh()
    if ($process.ExitCode -ne 0) { throw "Native worker failed in case $index; inspect its JSON/logs." }
    $profile = Get-Content -LiteralPath $profileFile -Raw | ConvertFrom-Json
    if (-not $profile.solved -or $profile.errors.Count -ne 0) { throw 'Native solve not accepted.' }
    $actualHeat = -1000 * $profile.columns[0].reboiler_duty_kW
    $point = [pscustomobject]@{reflux_ratio=$ratio; heat_added_W=$actualHeat; duty_error_W=$actualHeat-$targetHeat; solve_ms=$profile.solve_milliseconds; profile=$profileFile}
    $history.Add($point)
    Write-Host ("Case {0}: reflux={1:G15}, duty error={2:G8} W" -f $index,$ratio,$point.duty_error_W)
    return $point
}

# Preserve both target D and target Q. Inner column uses stable native reflux/bottoms specs;
# the outer scalar solve adjusts reflux until the calculated native duty reaches Q.
$center = $baseline.columns[0].actual_reflux_ratio
$low = Evaluate-Reflux ($center * 0.99)
$high = Evaluate-Reflux ($center * 1.01)
if ($low.duty_error_W * $high.duty_error_W -ge 0) { throw 'The initial +/-1% reflux bracket does not straddle the target.' }
$accepted = $null
for ($iteration = 0; $iteration -lt 8; $iteration++) {
    $ratio = ($low.reflux_ratio * $high.duty_error_W - $high.reflux_ratio * $low.duty_error_W) / ($high.duty_error_W - $low.duty_error_W)
    if ($ratio -le $low.reflux_ratio -or $ratio -ge $high.reflux_ratio) { $ratio = ($low.reflux_ratio + $high.reflux_ratio) / 2 }
    $point = Evaluate-Reflux $ratio
    if ([Math]::Abs($point.duty_error_W) -le 1) { $accepted = $point; break }
    if ($point.duty_error_W * $low.duty_error_W -lt 0) { $high = $point } else { $low = $point }
}
if ($null -eq $accepted) { throw 'Outer solve did not reach the 1 W duty tolerance.' }
$finalProfile = Get-Content -LiteralPath $accepted.profile -Raw | ConvertFrom-Json
$distillateId = ($connections | Where-Object StreamBehavior -eq 'Distillate').StreamID
$actualDistillate = ($finalProfile.output_streams | Where-Object id -eq $distillateId).molar_flow_mol_s
if ([Math]::Abs($actualDistillate - $targetDistillate) -gt 1e-6) { throw 'The independently checked distillate target was not met.' }
Copy-Item -LiteralPath $accepted.profile -Destination (Join-Path $outputPath 'profile.json')
Copy-Item -LiteralPath ([IO.Path]::ChangeExtension($accepted.profile, '.solved.dwxml')) -Destination (Join-Path $outputPath 'equivalent-duty.solved.dwxml')
$summary = [ordered]@{
    method = 'Outer duty root in reflux ratio; native Wang-Henke inner solve with bottoms determined by the specified distillate material balance'
    normalized_feed = $true
    original_feed_composition_sum = ($feed.composition | Measure-Object -Sum).Sum
    feed_molar_flow_preserved_mol_s = $feed.molar_flow_mol_s
    target_distillate_mol_s = $targetDistillate
    actual_distillate_mol_s = $actualDistillate
    bottoms_mol_s = $bottoms
    target_reboiler_heat_added_W = $targetHeat
    actual_reboiler_heat_added_W = $accepted.heat_added_W
    duty_error_W = $accepted.duty_error_W
    reflux_ratio = $accepted.reflux_ratio
    native_solve_count = $history.Count
    total_wall_ms = $timer.ElapsedMilliseconds
    history = $history
    qualified_for_v3_training = $false
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputPath 'summary.json')
$summary | ConvertTo-Json -Depth 5

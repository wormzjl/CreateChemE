param(
    [string]$DataRoot = 'D:/Minecraft/Modding/1.21/CreateChemE/research/2026-09-24-pipe-thermal-loss'
)
$ErrorActionPreference = 'Stop'
$cases = @(Import-Csv (Join-Path $DataRoot 'campaign-01/cases.csv'))
$ok = @($cases | Where-Object status -eq 'OK')
function Summarize-Values($Values) {
    $v = @($Values | Where-Object { -not [double]::IsNaN($_) } | Sort-Object)
    [ordered]@{ count=$v.Count; median=$v[[int][math]::Floor(.5*($v.Count-1))]; p95=$v[[int][math]::Floor(.95*($v.Count-1))]; maximum=$v[-1]; above10Percent=@($v | Where-Object {$_ -gt .1}).Count }
}
$summary = [ordered]@{
    enumerated=$cases.Count; accepted=$ok.Count
    statuses=@($cases | Group-Object status | ForEach-Object {[ordered]@{status=$_.Name; count=$_.Count}})
    metrics=[ordered]@{}
    perFluid=@($ok | Group-Object fluid | ForEach-Object {
        $group=$_.Group
        [ordered]@{fluid=$_.Name;count=$group.Count;cpFailures=@($group | Where-Object {[double]$_.cp0_error -gt .1}).Count;correctedCpFailures=@($group | Where-Object {[double]$_.cp1_error -gt .1}).Count;oneCorrectionFailures=@($group | Where-Object {[double]$_.one_error -gt .1}).Count}
    })
}
foreach($field in @('cp0_error','cp1_error','table_error','frozen_error','one_error','two_error','gated_error','flow_one_error','refinement_error','energy_residual')) {
    $summary.metrics[$field] = Summarize-Values @($ok | ForEach-Object {[double]($_.$field)})
}
$tables = @(Import-Csv (Join-Path $DataRoot 'followup-01/table-sizes.csv'))
foreach($field in @('nine_error','seventeen_error')) {
    $summary.metrics[$field] = Summarize-Values @($tables | ForEach-Object {[double]($_.$field)})
}
$summary.referenceChecks = @(Import-Csv (Join-Path $DataRoot 'followup-01/reference-checks.csv') | Group-Object check | ForEach-Object {
    [ordered]@{kind=$_.Name;error=(Summarize-Values @($_.Group | ForEach-Object {[double]$_.error})); refinement=(Summarize-Values @($_.Group | ForEach-Object {[double]$_.refinement}))}
})
$summary.serialTimings = @(Import-Csv (Join-Path $DataRoot 'campaign-01/timings.csv') | Group-Object fluid | ForEach-Object {
    [ordered]@{fluid=$_.Name;medianExchangeMicroseconds=(Summarize-Values @($_.Group | ForEach-Object {[double]$_.exchange_us})).median;medianProfileAndModelBuildMilliseconds=(Summarize-Values @($_.Group | ForEach-Object {[double]$_.curve_build_ms})).median;medianOneCorrectionMicroseconds=(Summarize-Values @($_.Group | ForEach-Object {[double]$_.one_correction_us})).median}
})
$summary | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $DataRoot 'summary.json') -Encoding utf8
$summary | ConvertTo-Json -Depth 3

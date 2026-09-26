param(
    [Parameter(Mandatory)][string]$Profile,
    [Parameter(Mandatory)][string]$Report
)
$ErrorActionPreference = 'Stop'
$data = Get-Content -LiteralPath $Profile -Raw | ConvertFrom-Json
if (-not $data.solved -or $data.errors.Count -ne 0 -or $data.columns.Count -ne 1) {
    throw 'Expected a freshly solved single-column export without solver errors.'
}
$column = $data.columns[0]
$inputColumn = $data.input_columns[0]
$count = $column.compids.Count
$nodes = $column.stages
$compoundIds = @($data.compounds | ForEach-Object { $_.Name })
if (($compoundIds -join '|') -ne ($column.compids -join '|')) { throw 'Compound axes differ.' }
$streams = @{}
foreach ($stream in $data.output_streams) { $streams[$stream.id] = $stream }
$feeds = @($inputColumn.connections | Where-Object StreamBehavior -eq 'Feed')
$products = @($inputColumn.connections | Where-Object StreamBehavior -ne 'Feed')
$maximumCompositionError = 0.0
$minimumComposition = [double]::PositiveInfinity
foreach ($field in @('Tf', 'P0', 'Lf', 'Vf', 'LSSf', 'VSSf', 'xf', 'yf')) {
    if ($column.$field.Count -ne $nodes) { throw "Wrong number of rows: $field" }
}
foreach ($field in @('Tf', 'P0', 'Lf', 'Vf', 'LSSf', 'VSSf')) {
    foreach ($value in $column.$field) {
        if (-not [double]::IsFinite($value) -or $value -lt 0) { throw "Invalid $field value" }
    }
}
foreach ($field in @('xf', 'yf')) {
    foreach ($row in $column.$field) {
        if ($row.Count -ne $count) { throw "Wrong component count in $field" }
        $sum = 0.0
        foreach ($value in $row) {
            if (-not [double]::IsFinite($value) -or $value -lt 0) { throw "Invalid $field composition" }
            $sum += $value
            $minimumComposition = [Math]::Min($minimumComposition, $value)
        }
        $maximumCompositionError = [Math]::Max($maximumCompositionError, [Math]::Abs($sum - 1))
    }
}
$netMoles = 0.0; $netMass = 0.0; $netEnthalpy = 0.0
$componentClosure = [double[]]::new($count)
foreach ($connection in $inputColumn.connections) {
    $stream = $streams[$connection.StreamID]
    $sign = if ($connection.StreamBehavior -eq 'Feed') { 1.0 } else { -1.0 }
    $netMoles += $sign * $stream.molar_flow_mol_s
    $netMass += $sign * $stream.mass_flow_kg_s
    $netEnthalpy += $sign * $stream.mass_flow_kg_s * $stream.enthalpy_kJ_kg
    for ($c = 0; $c -lt $count; $c++) { $componentClosure[$c] += $sign * $stream.molar_flow_mol_s * $stream.composition[$c] }
}
$maxLocal = 0.0
for ($n = 0; $n -lt $nodes; $n++) {
    for ($c = 0; $c -lt $count; $c++) {
        $balance = -($column.Lf[$n] + $column.LSSf[$n]) * $column.xf[$n][$c] - ($column.Vf[$n] + $column.VSSf[$n]) * $column.yf[$n][$c]
        if ($n -gt 0) { $balance += $column.Lf[$n - 1] * $column.xf[$n - 1][$c] }
        if ($n -lt $nodes - 1) { $balance += $column.Vf[$n + 1] * $column.yf[$n + 1][$c] }
        foreach ($connection in $feeds) {
            if ($connection.stage_index -eq $n) {
                $stream = $streams[$connection.StreamID]
                $balance += $stream.molar_flow_mol_s * $stream.composition[$c]
            }
        }
        $maxLocal = [Math]::Max($maxLocal, [Math]::Abs($balance))
    }
}
$summary = [ordered]@{
    source_sha256 = (Get-FileHash -LiteralPath $data.input -Algorithm SHA256).Hash.ToLowerInvariant()
    profile_sha256 = (Get-FileHash -LiteralPath $Profile -Algorithm SHA256).Hash.ToLowerInvariant()
    engine_version = $data.version
    solved = $data.solved
    qualified_for_v3_training = $false
    components = $count
    pseudocomponents = @($data.compounds | Where-Object IsPF -eq 1).Count
    stages_including_boundaries = $nodes
    maximum_composition_sum_error = $maximumCompositionError
    minimum_composition = $minimumComposition
    overall_molar_closure_mol_s = $netMoles
    overall_mass_closure_kg_s = $netMass
    maximum_overall_component_closure_mol_s = ($componentClosure | ForEach-Object { [Math]::Abs($_) } | Measure-Object -Maximum).Maximum
    maximum_local_component_closure_mol_s = $maxLocal
    energy_closure_kW = $netEnthalpy - $column.condenser_duty_kW - $column.reboiler_duty_kW
    solve_milliseconds = $data.solve_milliseconds
    temperature_top_K = $column.Tf[0]
    temperature_bottom_K = $column.Tf[-1]
    actual_reflux_ratio = $column.actual_reflux_ratio
    internal_solver_tolerance = $inputColumn.internal_tolerance
    external_solver_tolerance = $inputColumn.external_tolerance
    note = 'Uses the solver tolerances recorded above. Numerical checks do not establish thermodynamic equivalence or V3 acceptance. Top vapor numerical floor is not a physical product.'
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Report
$summary | ConvertTo-Json -Depth 6

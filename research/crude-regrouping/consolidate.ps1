$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskPrefix = $taskRoot.TrimEnd('\') + '\'
$taskMoves = [ordered]@{
    'CRUDE_ASSAY_CONVERSION.md' = 'research/crude-regrouping/notes/CRUDE_ASSAY_CONVERSION.md'
    'CUT_ELEMENTAL_PROFILES.md' = 'research/crude-regrouping/notes/CUT_ELEMENTAL_PROFILES.md'
    'HEAVY_FRACTION_LUMPING_STUDY.md' = 'research/crude-regrouping/notes/HEAVY_FRACTION_LUMPING_STUDY.md'
    'HEAVY_FRACTION_VISCOSITY_LITERATURE.md' = 'research/crude-regrouping/notes/HEAVY_FRACTION_VISCOSITY_LITERATURE.md'
    'MISSING_PARAMETER_ESTIMATION_RESEARCH.md' = 'research/crude-regrouping/notes/MISSING_PARAMETER_ESTIMATION_RESEARCH.md'
    'PSEUDOCOMPONENT_COLD_FLOW.md' = 'research/crude-regrouping/notes/PSEUDOCOMPONENT_COLD_FLOW.md'
    'docs/tjl19-property-provenance.md' = 'research/crude-regrouping/notes/tjl19-property-provenance.md'
    'documentation/current_crude_thermo_parameters.json' = 'research/crude-regrouping/notes/current_crude_thermo_parameters.json'
    'documentation/VDU_SIMULATION_RESEARCH.md' = 'research/crude-regrouping/notes/VDU_SIMULATION_RESEARCH.md'
    'research/vacuum-residue-hydroprocessing-cuts.md' = 'research/crude-regrouping/notes/vacuum-residue-hydroprocessing-cuts.md'
    'examples/crude-assays' = 'research/crude-assays'
    'examples/cold-flow' = 'research/cold-flow'
    'build/crude-assay-sources' = 'research/crude-assay-sources'
}
foreach ($taskName in @('Export-DissolvedViscosity.cs','Export-DwsimViscosity.cs','Export-DwsimViscosity.ps1','Probe-DwsimColdFlow.cs','analyze_cold_flow.py','audit_residue_viscosity_units.py','extend_ambient_viscosity.py','import_dwsim_viscosity.py')) {
    $taskMoves['examples/' + $taskName] = 'research/viscosity-tools/' + $taskName
}
foreach ($taskName in @('dwsim-ambient-dissolved.json','dwsim-ambient-viscosity-export.json','dwsim-cold-flow-probe.json','dwsim-viscosity-export.json','dwsim-viscosity-probe.json','DwsimViscosityProbe.cs','DwsimViscosityProbe.exe','DwsimViscosityProbe.exe.config','Export-DissolvedViscosity.exe','Export-DissolvedViscosity.exe.config','Export-DwsimViscosity.exe','Export-DwsimViscosity.exe.config','Probe-DwsimColdFlow.exe','Probe-DwsimColdFlow.exe.config','read_crude_assays.py')) {
    $taskMoves['build/' + $taskName] = 'research/viscosity-tools/raw/' + $taskName
}
$taskMoves['build/dwsim-research/chemsep-v3/source-feed.dwxml'] = 'research/viscosity-tools/source-feed.dwxml'
$taskEntries = @()
foreach ($taskMove in $taskMoves.GetEnumerator()) {
    $taskSource = [IO.Path]::GetFullPath((Join-Path $taskRoot $taskMove.Key))
    $taskDestination = [IO.Path]::GetFullPath((Join-Path $taskRoot $taskMove.Value))
    if (!$taskSource.StartsWith($taskPrefix, [StringComparison]::OrdinalIgnoreCase) -or !$taskDestination.StartsWith($taskPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Move escapes workspace' }
    if (!(Test-Path -LiteralPath $taskSource) -and (Test-Path -LiteralPath $taskDestination)) {
        $taskFiles = if ((Get-Item -LiteralPath $taskDestination).PSIsContainer) { @(Get-ChildItem -LiteralPath $taskDestination -Recurse -File) } else { @(Get-Item -LiteralPath $taskDestination) }
        foreach ($taskFile in $taskFiles) {
            $taskSuffix = $taskFile.FullName.Substring($taskDestination.Length).Replace('\','/')
            $taskEntries += [ordered]@{ old = $taskMove.Key + $taskSuffix; new = $taskMove.Value + $taskSuffix; sha256 = (Get-FileHash -LiteralPath $taskFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = $taskFile.Length }
        }
        continue
    }
    if (!(Test-Path -LiteralPath $taskSource)) { throw "Missing source: $taskSource" }
    if (Test-Path -LiteralPath $taskDestination) { throw "Destination exists: $taskDestination" }
    $taskFiles = if ((Get-Item -LiteralPath $taskSource).PSIsContainer) { @(Get-ChildItem -LiteralPath $taskSource -Recurse -File) } else { @(Get-Item -LiteralPath $taskSource) }
    foreach ($taskFile in $taskFiles) {
        $taskSuffix = $taskFile.FullName.Substring($taskSource.Length).Replace('\','/')
        $taskEntries += [ordered]@{ old = $taskMove.Key + $taskSuffix; new = $taskMove.Value + $taskSuffix; sha256 = (Get-FileHash -LiteralPath $taskFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = $taskFile.Length }
    }
}
# Both absolute paths and every destination were checked before any move.
foreach ($taskMove in $taskMoves.GetEnumerator()) {
    $taskSource = Join-Path $taskRoot $taskMove.Key
    $taskDestination = Join-Path $taskRoot $taskMove.Value
    if (!(Test-Path -LiteralPath $taskSource) -and (Test-Path -LiteralPath $taskDestination)) { continue }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $taskDestination) | Out-Null
    Move-Item -LiteralPath $taskSource -Destination $taskDestination
}
foreach ($taskEntry in $taskEntries) {
    $taskHash = (Get-FileHash -LiteralPath (Join-Path $taskRoot $taskEntry.new) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($taskHash -ne $taskEntry.sha256) { throw "Relocation hash mismatch: $($taskEntry.new)" }
}
[ordered]@{ date = '2026-09-17'; description = 'Byte-exact relocation hashes before path repairs; no catalog/model regeneration'; files = $taskEntries } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'relocation-manifest.json') -Encoding utf8
Write-Output "Relocated and hash-verified $($taskEntries.Count) files."

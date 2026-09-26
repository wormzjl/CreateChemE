param(
    [Parameter(Mandatory=$true)][ValidateSet('comparison','recovery','replay','profile','benchmark')][string]$Stage,
    [string[]]$Candidate
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $repository
$selection = Get-Content -LiteralPath 'build/neural-gen3/selection.json' -Raw | ConvertFrom-Json
if (-not $Candidate) {
    $Candidate = switch ($Stage) {
        'comparison' { $selection.comparisonCandidateLabels }
        'recovery' { $selection.fullRecoveryLabels }
        'replay' { @($selection.selectedNeural, $selection.selectedTransfer) }
        'profile' { $selection.serialCandidateLabels }
        'benchmark' { $selection.fallbackBenchmarkLabels }
    }
}
foreach ($label in $Candidate) {
    if ($label -eq 'current' -and $Stage -eq 'profile') {
        $modelPath = $selection.candidates.gen2.modelPath
    } else {
        $entry = $selection.candidates.PSObject.Properties[$label]
        if (-not $entry) { throw "Unknown frozen candidate: $label" }
        $modelPath = $entry.Value.modelPath
        if ((Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value.modelSha256) {
            throw "Frozen model changed: $label"
        }
    }
    $mode = 'evaluate'
    $workers = 10
    $budget = 10000
    switch ($Stage) {
        'comparison' { $source = 'build/neural-gen3/evaluation-inputs/comparison-source.jsonl'; $output = "build/neural-gen3/comparison-$label" }
        'recovery' { $source = 'build/neural-gen3/fresh-design/remaining-gen2-failures.jsonl'; $output = "build/neural-gen3/recovery-$label" }
        'replay' { $source = 'build/neural-gen3/fresh-design/train-rescue-replay.jsonl'; $output = "build/neural-gen3/replay-$label" }
        'profile' {
            $source = 'build/neural-gen3/fresh-design/fresh-benchmark.jsonl'
            $output = "build/neural-gen3/profile-$label"
            $mode = if ($label -eq 'current') { 'profile-current' } else { 'profile-neural' }
            $workers = 1; $budget = 2000
        }
        'benchmark' {
            $source = 'build/neural-gen3/evaluation-inputs/fresh-benchmark-source.jsonl'
            $output = "build/neural-gen3/benchmark-$label"
            $mode = 'benchmark'; $workers = 1; $budget = 2000
        }
    }
    if (Test-Path -LiteralPath "$output/evaluation.jsonl") { throw "Output already exists; do not overwrite a completed or partial run: $output" }
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $monitor = $null
    try {
        if ($Stage -eq 'profile') {
            $monitorScript = Join-Path $PSScriptRoot 'watch_experiment_memory.ps1'
            $memoryOutput = Join-Path $repository "$output/memory.json"
            $monitor = Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -PassThru -ArgumentList @(
                '-NoProfile', '-File', ('"' + $monitorScript + '"'), '-CommandPattern', $output,
                '-RequiredCommandPattern', 'com.wormzjl.createcheme.science.column.v3.V3GeneralTrainingProbe',
                '-OutputPath', ('"' + $memoryOutput + '"')) -RedirectStandardOutput "$output/memory-monitor.log" -RedirectStandardError "$output/memory-monitor.err"
        }
        Write-Output "Running $Stage / $label with $workers worker(s)"
        & .\gradlew.bat generalNeuralExperiment "-PgeneralMode=$mode" "-PgeneralSource=$source" "-PgeneralOutput=$output" "-PgeneralModel=$modelPath" "-PgeneralWorkers=$workers" "-PgeneralNeuralBudgetMillis=$budget" '-PgeneralIterations=16' --offline
        if ($LASTEXITCODE -ne 0) { throw "Experiment failed: $Stage / $label" }
    } finally {
        if ($monitor) {
            if (-not $monitor.WaitForExit(10000)) { throw "Owned memory monitor did not finish after experiment: $label" }
            if ($monitor.ExitCode -ne 0) { throw "Memory monitor failed: $output/memory-monitor.err" }
        }
    }
}

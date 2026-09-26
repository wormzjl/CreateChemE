# Historical reproduction only. Use unified_column_evaluation.py for the active test.
param(
    [Parameter(Mandatory=$true)][ValidateSet('validation','fresh','benchmark')][string]$Stage,
    [string[]]$Candidate = @('transformer','mlp','gen3-factorized','nearest-k1')
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
Set-Location -LiteralPath $repository
$root = 'build/neural-transformer/native-v1'
$manifest = Get-Content -LiteralPath "$root/candidates.json" -Raw | ConvertFrom-Json
if (-not (Test-Path -LiteralPath "$root/parity.json")) { throw 'CPU/Java parity must pass first' }
if ($Stage -ne 'validation' -and -not (Test-Path -LiteralPath "$root/selection.json")) { throw 'Freeze native validation selection before holdout evaluation' }
if ($Stage -ne 'validation') {
    $selection = Get-Content -LiteralPath "$root/selection.json" -Raw | ConvertFrom-Json
    if ((Get-FileHash -LiteralPath 'tools/neural/V3ColumnTransformerInitializer.java' -Algorithm SHA256).Hash.ToLowerInvariant() -ne $selection.inferenceSourceSha256) { throw 'Frozen inference implementation changed' }
}
foreach ($label in $Candidate) {
    if ($label -eq 'current' -and $Stage -ne 'fresh') { throw 'Standalone classical control is only used for the fresh full cohort' }
    $lookup = if ($label -eq 'current') { 'gen3-factorized' } else { $label }
    $entry = $manifest.candidates.PSObject.Properties[$lookup]
    if (-not $entry) { throw "Unknown frozen candidate: $label" }
    $modelPath = $entry.Value.modelPath
    if ((Get-FileHash -LiteralPath $modelPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value.modelSha256) { throw "Frozen model changed: $label" }
    $source = switch ($Stage) {
        'validation' { "$root/validation.jsonl" }
        'fresh' { 'build/neural-transformer/data-v2/fresh-holdout.jsonl' }
        'benchmark' { 'build/neural-transformer/data-v2/fresh-benchmark.jsonl' }
    }
    $output = "$root/$Stage-$label"
    if ((Test-Path -LiteralPath "$output/evaluation.jsonl") -or (Test-Path -LiteralPath "$output/cases.jsonl")) { throw "Never overwrite a partial or completed journal: $output" }
    $workers = if ($Stage -eq 'benchmark') { 1 } else { 10 }
    $budget = if ($Stage -eq 'benchmark') { 2000 } else { 10000 }
    $mode = if ($label -eq 'current') { 'generate' } elseif ($Stage -eq 'benchmark') { 'benchmark' } else { 'evaluate' }
    & .\gradlew.bat generalNeuralExperiment "-PgeneralMode=$mode" "-PgeneralSource=$source" "-PgeneralOutput=$output" "-PgeneralModel=$modelPath" "-PgeneralWorkers=$workers" "-PgeneralNeuralBudgetMillis=$budget" '-PgeneralIterations=16' '-PgeneralDeadlineSeconds=30' --offline
    if ($LASTEXITCODE -ne 0) { throw "Native campaign failed: $Stage / $label" }
}

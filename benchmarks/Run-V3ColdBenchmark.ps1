param(
    [ValidatePattern('^[a-z0-9-]+$')]
    [string] $Label = 'after'
)

$ErrorActionPreference = 'Stop'
$v3Workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$v3Destination = Join-Path $PSScriptRoot "results/v3-phase-cold-$Label"
if (Test-Path -LiteralPath $v3Destination) {
    throw "Refusing to overwrite an existing cold-benchmark snapshot: $v3Destination"
}

# Every JavaExec invocation launches a fresh JVM and executes only one facade call.
# Do not run builds, other benchmarks, or the Minecraft client concurrently.
$v3Rounds = @(
    @('150-off', '110-off', '110-on'),
    @('110-on', '110-off', '150-off'),
    @('150-off', '110-off', '110-on'),
    @('100-off', '110-test-feed')
)

Push-Location -LiteralPath $v3Workspace
try {
    for ($v3Round = 0; $v3Round -lt $v3Rounds.Count; $v3Round++) {
        foreach ($v3Case in $v3Rounds[$v3Round]) {
            $v3Report = "benchmarks/results/v3-phase-cold-$Label/$v3Case-$($v3Round + 1).json"
            $v3Arguments = @(
                'v3TimeoutBenchmark', '--offline', '--no-daemon', '--console=plain',
                '-Pv3Warmup=0', '-Pv3Samples=1', '-Pv3DeadlineSeconds=45',
                "-Pv3Cases=$v3Case", "-Pv3Report=$v3Report"
            )
            & .\gradlew.bat @v3Arguments |
                Select-String -Pattern 'BEGIN case=|END case=|PROGRESS case=|REPORT |BUILD |FAILED|compileJava|compileBenchmarkJava' |
                ForEach-Object { $_.Line }
            if ($LASTEXITCODE -ne 0) { throw "Cold benchmark invocation failed: $v3Case" }
        }
    }
} finally {
    Pop-Location
}

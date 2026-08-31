param(
    [ValidateNotNullOrEmpty()]
    [ValidateLength(1, 64)]
    [ValidatePattern('^[a-z0-9]+(?:-[a-z0-9]+)*$')]
    [string] $Label = 'after'
)

$ErrorActionPreference = 'Stop'
$v3Workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$v3SourceRoot = Join-Path $v3Workspace 'src/main/java/com/wormzjl/createcheme/science/column/v3'
$v3Destination = Join-Path $PSScriptRoot "results/v3-flash-cold-$Label"
if (Test-Path -LiteralPath $v3Destination) {
    throw "Refusing to overwrite an existing flash cold-benchmark snapshot: $v3Destination"
}

function Get-V3FlashSourceHashes {
    $v3Files = @(Get-ChildItem -LiteralPath $v3SourceRoot -File -Recurse -Filter '*.java' |
            Sort-Object -Property FullName)
    if ($v3Files.Count -eq 0) { throw "No V3 Java sources found under $v3SourceRoot" }
    $v3Hashes = [ordered]@{}
    foreach ($v3File in $v3Files) {
        $v3Relative = $v3File.FullName.Substring($v3Workspace.Length + 1).Replace('\', '/')
        $v3Hashes[$v3Relative] = (Get-FileHash -LiteralPath $v3File.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return $v3Hashes
}

# Every JavaExec invocation launches a fresh JVM and executes one facade call, with no warmup.
# Do not run builds, other benchmarks, or the Minecraft client concurrently.
# JAVA_HOME and PATH are supplied by the caller; this script does not select or change the JDK.
$v3Cases = @('150-off', '150-on', '110-off', '110-on', '100-off', '100-on',
        '70-off', '70-on', '50-off', '50-on')
$v3HashesBefore = Get-V3FlashSourceHashes
$null = New-Item -ItemType Directory -Path $v3Destination
$v3ManifestPath = Join-Path $v3Destination 'SOURCE_HASHES.json'
$v3Manifest = [ordered]@{
    schemaVersion = 1
    label = $Label
    startedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    completedUtc = $null
    javaHome = $env:JAVA_HOME
    sourceRoot = 'src/main/java/com/wormzjl/createcheme/science/column/v3'
    hashAlgorithm = 'SHA256'
    deadlineSeconds = 45
    samplesPerFreshJvm = 1
    warmupCallsPerFreshJvm = 0
    profile = $false
    cases = $v3Cases
    completedCases = @()
    status = 'running'
    invocationError = $null
    sourceVerificationError = $null
    sourcesUnchanged = $null
    changedSourcePaths = @()
    sourceHashesBefore = $v3HashesBefore
    sourceHashesAfter = $null
}
$v3Manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $v3ManifestPath -Encoding UTF8

$v3InvocationError = $null
$v3VerificationError = $null
Push-Location -LiteralPath $v3Workspace
try {
    foreach ($v3Case in $v3Cases) {
        $v3Report = "benchmarks/results/v3-flash-cold-$Label/$v3Case.json"
        $v3Arguments = @(
            'v3TimeoutBenchmark', '--offline', '--no-daemon', '--console=plain',
            '-Pv3Warmup=0', '-Pv3Samples=1', '-Pv3DeadlineSeconds=45', '-Pv3Profile=false',
            "-Pv3Cases=$v3Case", "-Pv3Report=$v3Report"
        )
        & .\gradlew.bat @v3Arguments |
            Select-String -Pattern 'BEGIN case=|END case=|PROGRESS case=|REPORT |BUILD |FAILED|compileJava|compileBenchmarkJava' |
            ForEach-Object { $_.Line }
        if ($LASTEXITCODE -ne 0) { throw "Flash cold benchmark invocation failed: $v3Case (exit $LASTEXITCODE)" }
        $v3Manifest.completedCases += $v3Case
        $v3Manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $v3ManifestPath -Encoding UTF8
    }
    $v3Manifest.status = 'completed'
} catch {
    $v3InvocationError = $_
    $v3Manifest.status = 'invocation-failed'
    $v3Manifest.invocationError = $_.Exception.Message
} finally {
    try {
        try {
            $v3HashesAfter = Get-V3FlashSourceHashes
            $v3Manifest.sourceHashesAfter = $v3HashesAfter
            $v3AllPaths = @(@($v3HashesBefore.Keys) + @($v3HashesAfter.Keys) | Sort-Object -Unique)
            $v3ChangedPaths = @($v3AllPaths | Where-Object {
                    -not $v3HashesBefore.Contains($_) -or -not $v3HashesAfter.Contains($_) -or
                    $v3HashesBefore[$_] -ne $v3HashesAfter[$_]
                })
            $v3Manifest.changedSourcePaths = $v3ChangedPaths
            $v3Manifest.sourcesUnchanged = $v3ChangedPaths.Count -eq 0
            if (-not $v3Manifest.sourcesUnchanged) { $v3Manifest.status = 'source-changed' }
        } catch {
            $v3VerificationError = $_
            $v3Manifest.status = 'source-verification-failed'
            $v3Manifest.sourceVerificationError = $_.Exception.Message
        }
        $v3Manifest.completedUtc = [DateTimeOffset]::UtcNow.ToString('o')
        $v3Manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $v3ManifestPath -Encoding UTF8
    } finally {
        Pop-Location
    }
}

if ($null -ne $v3VerificationError) { throw $v3VerificationError }
if (-not $v3Manifest.sourcesUnchanged) {
    throw "V3 Java sources changed during the benchmark; snapshot is not comparable. See $v3ManifestPath"
}
if ($null -ne $v3InvocationError) { throw $v3InvocationError }
Write-Output "SOURCE_HASHES unchanged: $v3ManifestPath"

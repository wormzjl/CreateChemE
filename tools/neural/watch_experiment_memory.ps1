param(
    [Parameter(Mandatory=$true, ParameterSetName='Id')][int]$ExperimentProcessId,
    [Parameter(Mandatory=$true, ParameterSetName='Command')][string]$CommandPattern,
    [Parameter(ParameterSetName='Command')][string]$RequiredCommandPattern,
    [Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
if ($CommandPattern) {
    $attachDeadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $matchingExperiments = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
            Where-Object { $_.CommandLine -and $_.CommandLine.Contains($CommandPattern) -and
                (-not $RequiredCommandPattern -or $_.CommandLine.Contains($RequiredCommandPattern)) })
        if ($matchingExperiments.Count -gt 1) { throw 'More than one JVM matches the owned experiment command' }
        if ($matchingExperiments.Count -eq 1) { $ExperimentProcessId = $matchingExperiments[0].ProcessId; break }
        if ([DateTime]::UtcNow -ge $attachDeadline) { throw 'Experiment JVM did not appear within 60 seconds' }
        Start-Sleep -Milliseconds 100
    } while ($true)
}
$experimentProcess = Get-Process -Id $ExperimentProcessId
$experimentStarted = $experimentProcess.StartTime.ToUniversalTime()
$sampledPeakWorkingSet = 0L
$sampledPeakPrivateBytes = 0L
$osPeakWorkingSet = 0L
$sampleCount = 0
$watchStarted = [DateTime]::UtcNow
while ($true) {
    try {
        $experimentProcess.Refresh()
        if ($experimentProcess.HasExited) { break }
        if ($experimentProcess.StartTime.ToUniversalTime() -ne $experimentStarted) { break }
        $sampledPeakWorkingSet = [Math]::Max($sampledPeakWorkingSet, $experimentProcess.WorkingSet64)
        $sampledPeakPrivateBytes = [Math]::Max($sampledPeakPrivateBytes, $experimentProcess.PrivateMemorySize64)
        $osPeakWorkingSet = [Math]::Max($osPeakWorkingSet, $experimentProcess.PeakWorkingSet64)
        $sampleCount++
    } catch [System.InvalidOperationException] { break }
    $measurement = [ordered]@{
        processId = $ExperimentProcessId
        commandPattern = $CommandPattern
        requiredCommandPattern = $RequiredCommandPattern
        processStartedUtc = $experimentStarted.ToString('o')
        monitorStartedUtc = $watchStarted.ToString('o')
        lastSampleUtc = [DateTime]::UtcNow.ToString('o')
        sampleIntervalMillis = 1000
        sampleCount = $sampleCount
        osPeakWorkingSetBytesSinceProcessStart = $osPeakWorkingSet
        sampledPeakWorkingSetBytes = $sampledPeakWorkingSet
        sampledPeakPrivateBytes = $sampledPeakPrivateBytes
        scope = 'Whole experiment JVM, including loaded inputs, model, native runtime, heap and all solver workers; not per-case retained RAM.'
    }
    $measurement | ConvertTo-Json | Set-Content -LiteralPath $OutputPath -Encoding utf8
    Start-Sleep -Milliseconds 1000
}
$measurement['monitorFinishedUtc'] = [DateTime]::UtcNow.ToString('o')
$measurement | ConvertTo-Json | Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Output "Recorded $sampleCount memory samples for experiment PID $ExperimentProcessId"

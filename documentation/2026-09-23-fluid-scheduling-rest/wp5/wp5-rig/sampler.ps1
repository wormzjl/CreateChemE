# External process sampler for the WP5 in-game benchmark: once a second, the measured JVM's CPU time (user + kernel,
# all threads), working set, private bytes, paged-pool-free commit and thread count, into a CSV. The same script is
# used for both builds and for the server and client runs. It stops when the stop file appears or the process exits.
param([Parameter(Mandatory=$true)][int]$ProcessId,[Parameter(Mandatory=$true)][string]$Out,[Parameter(Mandatory=$true)][string]$StopFile)
$ErrorActionPreference = 'Stop'
$p = Get-Process -Id $ProcessId
"epoch_ms,cpu_s,user_s,kernel_s,working_set_bytes,private_bytes,peak_working_set_bytes,threads,handles" | Out-File -FilePath $Out -Encoding ascii
while (-not (Test-Path $StopFile)) {
    try { $p.Refresh() } catch { break }
    if ($p.HasExited) { break }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $line = "{0},{1},{2},{3},{4},{5},{6},{7},{8}" -f $now, $p.TotalProcessorTime.TotalSeconds.ToString([Globalization.CultureInfo]::InvariantCulture), $p.UserProcessorTime.TotalSeconds.ToString([Globalization.CultureInfo]::InvariantCulture), $p.PrivilegedProcessorTime.TotalSeconds.ToString([Globalization.CultureInfo]::InvariantCulture), $p.WorkingSet64, $p.PrivateMemorySize64, $p.PeakWorkingSet64, $p.Threads.Count, $p.HandleCount
    Add-Content -Path $Out -Value $line -Encoding ascii
    $next = 1000 - ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $now)
    if ($next -gt 0) { Start-Sleep -Milliseconds $next }
}

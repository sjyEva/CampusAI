#Requires -Version 7.4
param([Parameter(Mandatory)][string]$StatePath, [Parameter(Mandatory)][string]$Generation)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rag-processes.ps1')
$target = Split-Path -Parent $StatePath
$state = Get-RagState $StatePath
if (-not $state -or $state.Generation -ne $Generation) { exit 1 }
try {
    Assert-RagBackend $state.Backend ([int]$state.Port)
    if (-not (Get-RagMatchingProcess $state.Tunnel)) { throw '本次隧道已退出。' }
    $serviceProcess = Get-RagMatchingProcess $state.Backend
    Set-Content -LiteralPath (Join-Path $target 'rag-tunnel.watch-ready') -Value $Generation
    while (-not $serviceProcess.WaitForExit(500)) {
        if (-not (Get-RagMatchingProcess $state.Tunnel)) { break }
        Assert-RagBackend $state.Backend ([int]$state.Port)
    }
} finally {
    Stop-RagProcess $state.Tunnel
    # A new start may be taking ownership after this tunnel exits. Serialize
    # artifact cleanup so this watcher cannot erase the next generation's URL.
    $cleanupLock = $null
    for ($attempt = 0; $attempt -lt 50 -and -not $cleanupLock; $attempt++) {
        try { $cleanupLock = Enter-RagLock $target } catch { Start-Sleep -Milliseconds 100 }
    }
    if ($cleanupLock) {
        try {
            $current = Get-RagState $StatePath
            if ($current -and $current.Generation -eq $Generation) { Remove-RagPublicArtifacts $target }
        } finally { $cleanupLock.Dispose() }
    }
}

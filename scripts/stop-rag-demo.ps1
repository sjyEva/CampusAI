#Requires -Version 7.4
param([ValidateRange(1,65535)][int]$Port = 18080)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rag-processes.ps1')
$target = Join-Path (Split-Path -Parent $PSScriptRoot) 'backend/target'
$tunnelExecutable = Join-Path $env:LOCALAPPDATA 'CampusAI/tools/cloudflared.exe'
$launchLock = Enter-RagLock $target
try {
    $tunnelState = Get-RagState (Join-Path $target 'rag-tunnel.state.json')
    $backendState = Get-RagState (Join-Path $target 'rag-demo.state.json')
    $tunnel = Get-RagActiveTunnel $target $tunnelExecutable $Port
    Stop-RagProcess $tunnel
    if ($tunnelState) { Stop-RagProcess $tunnelState.Watcher }
    if ($backendState) {
        Stop-RagProcess $backendState.Backend
        Stop-RagProcess $backendState.Launcher
    } elseif ($tunnelState) {
        Stop-RagProcess $tunnelState.Backend
    } else {
        # Upgrade path for an old launcher: verify the Java command before stopping it.
        $listener = Get-RagListener $Port
        if ($listener) {
            $legacy = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.Id)" -ErrorAction SilentlyContinue
            if ($legacy -and $legacy.Name -eq 'java.exe' -and $legacy.CommandLine -like '*campusai-backend-0.1.0-SNAPSHOT.jar*') { Stop-RagProcess $listener }
            else { throw "端口 $Port 由其他服务占用，未停止该进程。" }
        }
    }
    Remove-RagPublicArtifacts $target
    foreach ($name in @('rag-demo.pid','rag-demo.state.json')) {
        $path = Join-Path $target $name
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
    Write-Host '公网隧道、看护进程和演示后端已停止。'
} finally { $launchLock.Dispose() }

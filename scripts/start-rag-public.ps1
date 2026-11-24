#Requires -Version 7.4
param([ValidateRange(1,65535)][int]$Port = 18080)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rag-processes.ps1')
$repo = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repo 'backend'
$target = Join-Path $backend 'target'
$tunnelExecutable = Join-Path $env:LOCALAPPDATA 'CampusAI/tools/cloudflared.exe'
$base = "http://127.0.0.1:$Port"
$statePath = Join-Path $target 'rag-tunnel.state.json'
$launchLock = Enter-RagLock $target
$generation = [guid]::NewGuid().ToString('N')
$tokens = [Collections.Generic.List[string]]::new()
$passwords = $null
$service = $null
$tunnel = $null
$watcher = $null
$published = $false
$ownsArtifacts = $false
function Test-Login([string]$Username, [string]$Password) {
    try { $result = Invoke-RestMethod -Uri "$base/login" -Method Post -ContentType 'application/json' -Body (@{username=$Username;password=$Password} | ConvertTo-Json) -TimeoutSec 10 }
    catch { throw "无法检查 $Username 的后端登录状态。" }
    if ($result.token) { $tokens.Add([string]$result.token) }
    return $result
}
try {
    if (-not (Test-Path -LiteralPath $tunnelExecutable)) { throw '请先从 Cloudflare 官方发行版安装 cloudflared 到本机 CampusAI/tools。' }
    if (Get-RagActiveTunnel $target $tunnelExecutable $Port) { throw '公网隧道已运行；重新启动前请先执行 scripts/stop-rag-demo.ps1。' }
    Remove-RagPublicArtifacts $target
    $ownsArtifacts = $true
    $service = Get-RagListener $Port
    if (-not $service) { throw '后端尚未运行，请先使用 start-rag-demo.ps1 -PublicAccess -Background。' }
    $passwords = Read-RagAccountPasswords
    $defaultPasswords = @('CampusAI-Dev-2026')
    if ($env:CAMPUSAI_DEV_PASSWORD) { $defaultPasswords += $env:CAMPUSAI_DEV_PASSWORD }
    $studentLogin = $null
    foreach ($name in @('admin','teacher','student','student2')) {
        foreach ($defaultPassword in $defaultPasswords) {
            if ((Test-Login $name $defaultPassword).code -eq 200) { throw "拒绝开放公网：$name 仍接受开发模式密码。" }
        }
        foreach ($passwordOwner in @('admin','teacher','student','student2')) {
            $login = Test-Login $name $passwords[$passwordOwner]
            if ($name -eq $passwordOwner) {
                if ($login.code -ne 200 -or -not $login.token) { throw "拒绝开放公网：$name 的独立密码与当前后端不一致。" }
                if ($name -eq 'student2') { $studentLogin = $login }
            } elseif ($login.code -eq 200) { throw '拒绝开放公网：不同账号的密码仍可互通。' }
        }
    }
    $capabilities = Invoke-RestMethod -Uri "$base/campusai/courses/2/rag/capabilities" -Headers @{Authorization=('Bearer '+$studentLogin.token)} -TimeoutSec 10
    if ($capabilities.code -ne 200 -or -not $capabilities.data.generationConfigured) { throw '拒绝开放公网：模型未配置。' }
    Assert-RagBackend $service $Port
    $logPath = Join-Path $target 'rag-tunnel.log'
    if (Test-Path -LiteralPath $logPath) { Clear-Content -LiteralPath $logPath }
    $process = Start-Process -FilePath $tunnelExecutable -ArgumentList @('tunnel','--url',$base,'--protocol','http2','--no-autoupdate','--logfile','target/rag-tunnel.log') -WorkingDirectory $backend -WindowStyle Hidden -RedirectStandardOutput (Join-Path $target 'rag-tunnel.stdout.log') -RedirectStandardError (Join-Path $target 'rag-tunnel.stderr.log') -PassThru
    $tunnel = [pscustomobject]@{ Id = $process.Id; StartedUtcTicks = $process.StartTime.ToUniversalTime().Ticks }
    $state = [ordered]@{ Generation = $generation; Port = $Port; Backend = $service; Tunnel = $tunnel; Watcher = $null }
    Set-RagState $statePath $state
    Set-Content -LiteralPath (Join-Path $target 'rag-tunnel.pid') -Value $tunnel.Id
    $watchPath = Join-Path $PSScriptRoot 'watch-rag-public.ps1'
    $watchProcess = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') -ArgumentList @('-NoProfile','-File',('"{0}"' -f $watchPath),'-StatePath',('"{0}"' -f $statePath),'-Generation',$generation) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $target 'rag-watch.stdout.log') -RedirectStandardError (Join-Path $target 'rag-watch.stderr.log') -PassThru
    $watcher = [pscustomobject]@{ Id = $watchProcess.Id; StartedUtcTicks = $watchProcess.StartTime.ToUniversalTime().Ticks }
    $state.Watcher = $watcher
    Set-RagState $statePath $state
    $publicUrl = $null
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        Assert-RagBackend $service $Port
        if (-not (Get-RagMatchingProcess $tunnel)) { throw '隧道已退出，请查看 target/rag-tunnel.stderr.log。' }
        if (-not (Get-RagMatchingProcess $watcher)) { throw '公网看护进程未能持续运行，已取消隧道。' }
        $log = if (Test-Path -LiteralPath $logPath) { Get-Content -LiteralPath $logPath -Raw } else { '' }
        $readyPath = Join-Path $target 'rag-tunnel.watch-ready'
        $watchReady = (Test-Path -LiteralPath $readyPath) -and ((Get-Content -LiteralPath $readyPath -Raw).Trim() -eq $generation)
        if ($watchReady -and $log -match 'Registered tunnel connection' -and $log -match 'https://[a-z0-9-]+\.trycloudflare\.com') { $publicUrl = $Matches[0]; break }
        Start-Sleep -Seconds 1
    }
    if (-not $publicUrl) { throw '隧道未能在 60 秒内连接成功，本次进程和访问说明已清理。' }
    Assert-RagBackend $service $Port
    $accessNote = @"
CampusAI 组内演示

网址：$publicUrl/rag-demo.html
软件工程账号：student2
登录密码：$($passwords.student2)

这是 CampusAI 的学生登录密码，不是 DeepSeek API 密钥。
模型密钥只留在后端，网页和源码不含该密钥。
学生可查询已选课程的已发布资料，不能上传、发布、删除或管理账号。
提问会消耗项目配置的 DeepSeek 额度，请仅在组内使用。
电脑关机、休眠或重启隧道后地址可能失效；后端退出时看护进程会关闭本次隧道。
统一停止：scripts/stop-rag-demo.ps1
"@
    Set-Content -LiteralPath (Join-Path $target 'rag-public-access.txt') -Value $accessNote -Encoding utf8
    Set-Content -LiteralPath (Join-Path $target 'rag-public-url.txt') -Value $publicUrl -Encoding utf8
    $published = $true
    Write-Host "临时公网地址：$publicUrl/rag-demo.html"
    Write-Host '组内账号说明已保存到 backend/target/rag-public-access.txt（不含模型密钥，不提交 Git）。'
} finally {
    if (-not $published -and $ownsArtifacts) {
        Stop-RagProcess $tunnel
        Stop-RagProcess $watcher
        Remove-RagPublicArtifacts $target
    }
    if ($service -and (Get-RagMatchingProcess $service)) {
        foreach ($token in $tokens) {
            try { Invoke-RestMethod -Uri "$base/logout" -Method Post -Headers @{Authorization=('Bearer '+$token)} -TimeoutSec 2 | Out-Null } catch { }
        }
    }
    $tokens.Clear(); $studentLogin = $null; $login = $null; $accessNote = $null
    if ($passwords) { $passwords.Clear() }
    $launchLock.Dispose()
}

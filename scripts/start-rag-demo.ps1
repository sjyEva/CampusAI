#Requires -Version 7.4
param([ValidateRange(1,65535)][int]$Port = 18080, [switch]$SkipBuild, [switch]$Background, [switch]$PublicAccess)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rag-processes.ps1')
$repo = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repo 'backend'
$target = Join-Path $backend 'target'
$statePath = Join-Path $target 'rag-demo.state.json'
$pidPath = Join-Path $target 'rag-demo.pid'
$tunnelExecutable = Join-Path $env:LOCALAPPDATA 'CampusAI/tools/cloudflared.exe'
$launchLock = Enter-RagLock $target
$started = [Collections.Generic.List[object]]::new()
$childEnvironment = @{}
$accounts = $null
$service = $null
$launcher = $null
$ready = $false
$generation = [guid]::NewGuid().ToString('N')
Push-Location $backend
try {
    if (Get-RagActiveTunnel $target $tunnelExecutable $Port) { throw '公网隧道仍在运行，请先执行 scripts/stop-rag-demo.ps1，再重新启动。' }
    if (Get-RagListener $Port) { throw "端口 $Port 已被占用；请先停止旧实例，不会覆盖现有服务。" }
    $previous = Get-RagState $statePath
    if ($previous -and ((Get-RagMatchingProcess $previous.Backend) -or (Get-RagMatchingProcess $previous.Launcher))) {
        throw '已有演示实例运行，请先执行 scripts/stop-rag-demo.ps1。'
    }
    if (Test-Path -LiteralPath $pidPath) {
        $oldId = 0
        if ([int]::TryParse((Get-Content -LiteralPath $pidPath -Raw).Trim(), [ref]$oldId)) {
            $old = Get-CimInstance Win32_Process -Filter "ProcessId=$oldId" -ErrorAction SilentlyContinue
            if ($old -and $old.CommandLine -like '*campusai-backend-0.1.0-SNAPSHOT.jar*') { throw '旧演示进程尚未退出，请先停止旧实例。' }
        }
    }
    if (-not $SkipBuild) {
        & (Join-Path $backend 'mvnw.cmd') '-q' '-DskipTests' 'package'
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败，请查看上方输出。' }
    }
    $jar = Join-Path $target 'campusai-backend-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw '请先构建后端。' }
    $childEnvironment.CAMPUSAI_PUBLIC_DEMO = $PublicAccess.IsPresent.ToString().ToLowerInvariant()
    if ($IsWindows) {
        $modelPath = Join-Path $env:LOCALAPPDATA 'CampusAI/secrets/model.json'
        if (Test-Path -LiteralPath $modelPath) {
            $model = Get-Content -LiteralPath $modelPath -Raw | ConvertFrom-Json
            $secureKey = ConvertTo-SecureString $model.encryptedApiKey
            try { $childEnvironment.CAMPUSAI_MODEL_API_KEY = [Net.NetworkCredential]::new('', $secureKey).Password }
            finally { $secureKey.Dispose() }
            $childEnvironment.CAMPUSAI_MODEL_BASE_URL = [string]$model.baseUrl
            $childEnvironment.CAMPUSAI_MODEL_NAME = [string]$model.model
            $childEnvironment.CAMPUSAI_MODEL_TIMEOUT_SECONDS = [string]$model.timeoutSeconds
            $childEnvironment.CAMPUSAI_MODEL_JSON_MODE = ([bool]$model.jsonMode).ToString().ToLowerInvariant()
            $childEnvironment.CAMPUSAI_MODEL_DISABLE_THINKING = ([bool]$model.disableThinking).ToString().ToLowerInvariant()
        }
        if ($PublicAccess) {
            $accounts = Read-RagAccountPasswords
            foreach ($name in @('admin','teacher','student','student2')) {
                $childEnvironment['CAMPUSAI_' + $name.ToUpperInvariant() + '_PASSWORD'] = $accounts[$name]
                $childEnvironment['CAMPUSAI_DEMO_PASSWORDS_' + $name.ToUpperInvariant()] = $accounts[$name]
            }
        }
    } elseif ($PublicAccess) { throw '此公网演示启动器使用 Windows 当前用户加密配置。' }
    $javaExecutable = (Get-Command java -ErrorAction Stop).Source
    $javaArguments = @('-Dfile.encoding=UTF-8','-jar','target/campusai-backend-0.1.0-SNAPSHOT.jar',"--server.port=$Port",'--server.address=127.0.0.1','--spring.datasource.url=jdbc:h2:file:./data/rag-demo;MODE=MySQL','--logging.file.name=target/rag-demo.log')
    try {
        $process = Start-Process -FilePath $javaExecutable -ArgumentList $javaArguments -WorkingDirectory $backend -Environment $childEnvironment -WindowStyle Hidden -RedirectStandardOutput (Join-Path $target 'rag-demo.stdout.log') -RedirectStandardError (Join-Path $target 'rag-demo.stderr.log') -PassThru
        $launcher = [pscustomobject]@{ Id = $process.Id; StartedUtcTicks = $process.StartTime.ToUniversalTime().Ticks }
        $started.Add($launcher)
    } finally { $childEnvironment.Clear(); if ($accounts) { $accounts.Clear() }; $model = $null }
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        foreach ($child in @(Get-RagStartedChildren $launcher)) {
            if (-not ($started | Where-Object { $_.Id -eq $child.Id -and $_.StartedUtcTicks -eq $child.StartedUtcTicks })) { $started.Add($child) }
        }
        $listener = Get-RagListener $Port
        if ($listener) {
            if (-not ($started | Where-Object { $_.Id -eq $listener.Id -and $_.StartedUtcTicks -eq $listener.StartedUtcTicks })) { throw '端口被其他进程占用，本次启动已取消。' }
            try { $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/rag-demo.html" -TimeoutSec 2 }
            catch { $response = $null }
            if ($response -and $response.StatusCode -eq 200) {
                Assert-RagBackend $listener $Port
                $service = $listener
                break
            }
        }
        $live = @($started | Where-Object { Get-RagMatchingProcess $_ })
        if ($live.Count -eq 0) { throw '后端启动失败，请查看 backend/target/rag-demo.stderr.log 和 rag-demo.log。' }
        Start-Sleep -Milliseconds 500
    }
    if (-not $service) { throw '后端在 90 秒内未就绪，本次启动已停止。' }
    Set-RagState $statePath ([ordered]@{ Generation = $generation; Port = $Port; PublicAccess = $PublicAccess.IsPresent; Backend = $service; Launcher = $launcher })
    Set-Content -LiteralPath $pidPath -Value $service.Id
    $ready = $true
    Write-Host "演示地址：http://127.0.0.1:$Port/rag-demo.html"
    Write-Host '资料保存在 backend/data/rag-demo；模型密钥未放入启动参数。'
    Write-Host "后端已就绪，实际监听进程：$($service.Id)"
} finally {
    $childEnvironment.Clear()
    if ($accounts) { $accounts.Clear() }
    if (-not $ready -and $started.Count -gt 0) {
        foreach ($identity in @($started.ToArray() | Sort-Object StartedUtcTicks -Descending)) { Stop-RagProcess $identity }
        $saved = Get-RagState $statePath
        if ($saved -and $saved.Generation -eq $generation) {
            Remove-Item -LiteralPath $statePath -Force
            if (Test-Path -LiteralPath $pidPath) { Remove-Item -LiteralPath $pidPath -Force }
        }
    }
    $launchLock.Dispose()
    Pop-Location
}
if (-not $Background) {
    try {
        $serviceProcess = Get-RagMatchingProcess $service
        if ($serviceProcess) { $serviceProcess.WaitForExit(); if ($serviceProcess.ExitCode -ne 0) { throw '服务退出异常，请查看 backend/target/rag-demo.log。' } }
    } finally {
        Stop-RagProcess $service
        Stop-RagProcess $launcher
        $cleanupLock = $null
        for ($attempt = 0; $attempt -lt 50 -and -not $cleanupLock; $attempt++) {
            try { $cleanupLock = Enter-RagLock $target } catch { Start-Sleep -Milliseconds 100 }
        }
        if ($cleanupLock) {
            try {
                $saved = Get-RagState $statePath
                if ($saved -and $saved.Generation -eq $generation) {
                    Remove-Item -LiteralPath $statePath -Force
                    if (Test-Path -LiteralPath $pidPath) { Remove-Item -LiteralPath $pidPath -Force }
                }
            } finally { $cleanupLock.Dispose() }
        }
    }
}

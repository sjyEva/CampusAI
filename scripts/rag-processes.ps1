# Shared helpers for the local demo launchers. No code here runs a service.
function Get-RagProcessIdentity([int]$ProcessId) {
    $candidate = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $candidate) { return $null }
    try {
        if ($candidate.HasExited) { return $null }
        return [pscustomobject]@{ Id = $candidate.Id; StartedUtcTicks = $candidate.StartTime.ToUniversalTime().Ticks }
    } catch { return $null }
}

function Get-RagMatchingProcess($Identity) {
    if ($null -eq $Identity) { return $null }
    $candidate = Get-Process -Id ([int]$Identity.Id) -ErrorAction SilentlyContinue
    try {
        if ($candidate -and -not $candidate.HasExited -and
            $candidate.StartTime.ToUniversalTime().Ticks -eq [long]$Identity.StartedUtcTicks) { return $candidate }
    } catch { }
    return $null
}

function Stop-RagProcess($Identity) {
    $candidate = Get-RagMatchingProcess $Identity
    if ($candidate) {
        try { $candidate.Kill($true); $candidate.WaitForExit(5000) | Out-Null }
        catch { if (Get-RagMatchingProcess $Identity) { throw '无法停止已确认的演示进程，请重试统一停止脚本。' } }
    }
}

function Get-RagListener([int]$Port) {
    $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    $owners = @($listeners.OwningProcess | Sort-Object -Unique)
    if ($owners.Count -gt 1) { throw "端口 $Port 存在多个监听进程，拒绝启动。" }
    if ($owners.Count -eq 0) { return $null }
    return Get-RagProcessIdentity ([int]$owners[0])
}

function Assert-RagBackend($Identity, [int]$Port) {
    $listener = Get-RagListener $Port
    if (-not (Get-RagMatchingProcess $Identity) -or -not $listener -or
        $listener.Id -ne $Identity.Id -or $listener.StartedUtcTicks -ne $Identity.StartedUtcTicks) {
        throw '后端进程或监听端口已变化，公网服务已取消。'
    }
}

function Get-RagState([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        try { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
        catch { throw '演示进程状态文件损坏，拒绝使用其中的进程编号。' }
    }
    return $null
}

function Set-RagState([string]$Path, $State) {
    $temporary = "$Path.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $State | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $temporary -Encoding utf8
        Move-Item -LiteralPath $temporary -Destination $Path -Force
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Enter-RagLock([string]$TargetDirectory) {
    New-Item -ItemType Directory -Path $TargetDirectory -Force | Out-Null
    try { return [IO.File]::Open((Join-Path $TargetDirectory 'rag-launch.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw '另一个演示启动或停止操作正在进行，请等待其结束。' }
}

function Get-RagActiveTunnel([string]$TargetDirectory, [string]$TunnelExecutable, [int]$Port) {
    $urlPattern = [regex]::Escape("http://127.0.0.1:$Port") + '(?=[\s"'']|$)'
    $state = Get-RagState (Join-Path $TargetDirectory 'rag-tunnel.state.json')
    if ($state -and (Get-RagMatchingProcess $state.Tunnel)) { return $state.Tunnel }
    # Recognize tunnels started by the older launcher, which stored only a PID.
    $legacyPath = Join-Path $TargetDirectory 'rag-tunnel.pid'
    if (Test-Path -LiteralPath $legacyPath) {
        $legacyId = 0
        if ([int]::TryParse((Get-Content -LiteralPath $legacyPath -Raw).Trim(), [ref]$legacyId)) {
            $legacy = Get-CimInstance Win32_Process -Filter "ProcessId=$legacyId" -ErrorAction SilentlyContinue
            if ($legacy -and $legacy.ExecutablePath -eq $TunnelExecutable -and $legacy.CommandLine -match $urlPattern) { return Get-RagProcessIdentity $legacyId }
        }
    }
    foreach ($candidate in @(Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" -ErrorAction SilentlyContinue)) {
        if ($candidate.ExecutablePath -eq $TunnelExecutable -and $candidate.CommandLine -match $urlPattern) {
            return Get-RagProcessIdentity ([int]$candidate.ProcessId)
        }
    }
    return $null
}

function Remove-RagPublicArtifacts([string]$TargetDirectory) {
    foreach ($name in @('rag-tunnel.pid','rag-tunnel.state.json','rag-tunnel.watch-ready','rag-public-url.txt','rag-public-access.txt')) {
        $path = Join-Path $TargetDirectory $name
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    }
}

function Read-RagAccountPasswords {
    $path = Join-Path $env:LOCALAPPDATA 'CampusAI/secrets/accounts.json'
    if (-not (Test-Path -LiteralPath $path)) { throw '公网模式缺少本机加密的独立账号密码配置。' }
    $passwords = @{}
    $distinct = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    try {
        $encrypted = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        foreach ($name in @('admin','teacher','student','student2')) {
            try {
                $secure = ConvertTo-SecureString $encrypted.$name
                try { $password = [Net.NetworkCredential]::new('', $secure).Password }
                finally { $secure.Dispose() }
            } catch { throw "无法解密 $name 的独立账号密码。" }
            if ([string]::IsNullOrWhiteSpace($password) -or $password.Length -lt 16) { throw "$name 的密码必须至少 16 字符。" }
            if (-not $distinct.Add($password)) { throw '四个演示账号必须使用互不相同的密码。' }
            if ($password -ceq 'CampusAI-Dev-2026' -or
                ($env:CAMPUSAI_DEV_PASSWORD -and $password -ceq $env:CAMPUSAI_DEV_PASSWORD)) {
                throw '公网账号不能使用开发模式密码。'
            }
            $passwords[$name] = $password
        }
        return $passwords
    } catch { $passwords.Clear(); throw }
    finally { $password = $null; $encrypted = $null; $distinct.Clear() }
}

function Get-RagStartedChildren($Launcher) {
    $identities = [Collections.Generic.List[object]]::new()
    $queue = [Collections.Generic.Queue[object]]::new()
    $queue.Enqueue($Launcher)
    while ($queue.Count -gt 0) {
        $parent = $queue.Dequeue()
        $parentId = [int]$parent.Id
        $currentParent = Get-RagProcessIdentity $parentId
        if ($currentParent -and $currentParent.StartedUtcTicks -ne $parent.StartedUtcTicks) { continue }
        foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$parentId" -ErrorAction SilentlyContinue)) {
            $identity = Get-RagProcessIdentity ([int]$child.ProcessId)
            if ($identity -and $identity.StartedUtcTicks -ge $parent.StartedUtcTicks) {
                $identities.Add($identity)
                $queue.Enqueue($identity)
            }
        }
    }
    return $identities.ToArray()
}

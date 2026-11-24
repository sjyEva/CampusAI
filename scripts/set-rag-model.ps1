#Requires -Version 7.0
param(
    [Security.SecureString]$ApiKey,
    [string]$BaseUrl = 'https://api.deepseek.com',
    [string]$Model = 'deepseek-flash',
    [ValidateRange(1,60)][int]$TimeoutSeconds = 45,
    [bool]$JsonMode = $true,
    [bool]$DisableThinking = $true
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw '此脚本使用 Windows 当前用户加密；其他系统请使用后端环境变量。' }
if (-not $ApiKey) { $ApiKey = Read-Host '请输入模型 API 密钥（不显示输入）' -AsSecureString }
if ($ApiKey.Length -eq 0) { throw 'API 密钥不能为空。' }
$uri = [Uri]$BaseUrl
if (-not $uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
    throw '远程模型地址必须是无凭据、查询参数或片段的 HTTPS 地址。'
}
$secretDirectory = Join-Path $env:LOCALAPPDATA 'CampusAI\secrets'
New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)

$inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
$propagation = [Security.AccessControl.PropagationFlags]::None
$allow = [Security.AccessControl.AccessControlType]::Allow
foreach ($principal in @($sid, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($principal, 'FullControl', $inheritance, $propagation, $allow))
}
[System.IO.FileSystemAclExtensions]::SetAccessControl(
    [System.IO.DirectoryInfo]::new($secretDirectory),
    $acl
)
$config = @{
    baseUrl = $BaseUrl.TrimEnd('/'); model = $Model; timeoutSeconds = $TimeoutSeconds
    jsonMode = $JsonMode; disableThinking = $DisableThinking
    encryptedApiKey = ConvertFrom-SecureString -SecureString $ApiKey
}
$configPath = Join-Path $secretDirectory 'model.json'
$config | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding utf8
Write-Host '模型配置已加密保存到当前 Windows 用户目录；没有写入代码仓库。'
Write-Host "提供方：$($uri.Host)；模型：$Model"

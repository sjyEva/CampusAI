#Requires -Version 7.0
param([int]$Port = 18080, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $repo 'backend'
Push-Location $backend
try {
    if (-not $SkipBuild) {
        & (Join-Path $backend 'mvnw.cmd') '-q' '-DskipTests' 'package'
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败，请查看上方输出。' }
    }
    $jar = Join-Path $backend 'target/campusai-backend-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw '请先构建后端。' }
    Write-Host "演示地址：http://127.0.0.1:$Port/rag-demo.html"
    Write-Host '资料保存在 backend/data/rag-demo；按 Ctrl+C 停止服务。'
    & java '-Dfile.encoding=UTF-8' '-jar' $jar "--server.port=$Port" '--server.address=127.0.0.1' '--spring.datasource.url=jdbc:h2:file:./data/rag-demo;MODE=MySQL'
    if ($LASTEXITCODE -ne 0) { throw '服务退出异常，请检查端口或上方输出。' }
} finally {
    Pop-Location
}

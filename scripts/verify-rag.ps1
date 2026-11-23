#Requires -Version 7.0
param([uri]$BaseUrl = 'http://127.0.0.1:8080', [string]$DemoPassword = $env:CAMPUSAI_DEV_PASSWORD)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrEmpty($DemoPassword)) { $DemoPassword = 'CampusAI-Dev-2026' }
$base = $BaseUrl.AbsoluteUri.TrimEnd('/')
$createdId = $null
$tempFile = Join-Path ([IO.Path]::GetTempPath()) ('campusai-rag-' + [guid]::NewGuid().ToString('N') + '.txt')
$marker = 'ragcheck' + [guid]::NewGuid().ToString('N')
$verificationSucceeded = $false
$cleanupFailure = $null

function Invoke-RagApi {
    param([string]$Path, [string]$Token, [string]$Method = 'GET', [object]$Body, [object]$Form)
    $request = @{ Uri="$base$Path"; Method=$Method; TimeoutSec=70 }
    if ($Token) { $request.Headers = @{Authorization="Bearer $Token"} }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress))
    }
    if ($Form) { $request.Form = $Form }
    Invoke-RestMethod @request
}
function Assert-Code($Response, [int]$Code = 200) {
    if ($Response.code -ne $Code) { throw "期望接口 code=$Code，实际为 $($Response.code)：$($Response.msg)" }
}
function Login([string]$Name) {
    $result = Invoke-RagApi -Path '/login' -Method POST -Body @{username=$Name;password=$DemoPassword}
    Assert-Code $result
    $result.token
}

try {
    $teacher = Login 'teacher'
    $student = Login 'student'
    $other = Login 'student2'
    $prefix = '/campusai/courses/1/rag'
    [IO.File]::WriteAllText($tempFile, "$marker 验证资料：残差连接提供信息和梯度的传播路径。", [Text.UTF8Encoding]::new($false))
    $uploaded = Invoke-RagApi -Path "$prefix/materials" -Token $teacher -Method POST -Form @{file=Get-Item -LiteralPath $tempFile}
    Assert-Code $uploaded
    $createdId = $uploaded.data.id
    if ($uploaded.data.parseStatus -ne 'READY' -or $uploaded.data.published) { throw '上传后应为已解析的未发布草稿。' }
    $query = [uri]::EscapeDataString($marker)
    $draft = Invoke-RagApi -Path "$prefix/search?q=$query" -Token $student
    Assert-Code $draft
    if ($draft.data.hits.Count -ne 0) { throw '草稿不应被学生检索。' }
    Assert-Code (Invoke-RagApi -Path "$prefix/materials/$createdId/publication" -Token $teacher -Method PATCH -Body @{published=$true})
    $found = Invoke-RagApi -Path "$prefix/search?q=$query" -Token $student
    Assert-Code $found
    $source = @($found.data.hits | Where-Object { $_.source.materialId -eq $createdId })[0].source
    if (-not $source.chunkId -or $source.paragraphStart -ne 1 -or $source.text -notlike "*$marker*") { throw '来源定位或真实原文不符。' }
    Assert-Code (Invoke-RagApi -Path "$prefix/sources/$($source.chunkId)" -Token $student)
    Assert-Code (Invoke-RagApi -Path "$prefix/search?q=$query" -Token $other) 404
    Assert-Code (Invoke-RagApi -Path "$prefix/materials/$createdId/file" -Token $other) 404
    Assert-Code (Invoke-RagApi -Path "$prefix/materials/$createdId" -Token $student -Method DELETE) 403
    $retry = Invoke-RagApi -Path "$prefix/materials/$createdId/reprocess" -Token $teacher -Method POST
    Assert-Code $retry
    if ($retry.data.version -ne 1 -or -not $retry.data.published) { throw '成功后的重复处理不应改版本或重复片段。' }
    $answer = Invoke-RagApi -Path "$prefix/ask" -Token $student -Method POST -Body @{question=$marker}
    Assert-Code $answer
    if ($answer.data.status -notin @('generated','retrieval_only','no_evidence')) { throw '问答没有返回明确状态。' }
    Write-Host "PASS 真实上传、发布、检索、来源、问答（$($answer.data.status)）及课程权限"
    Assert-Code (Invoke-RagApi -Path "$prefix/materials/$createdId/publication" -Token $teacher -Method PATCH -Body @{published=$false})
    Assert-Code (Invoke-RagApi -Path "$prefix/sources/$($source.chunkId)" -Token $student) 404
    [IO.File]::WriteAllText($tempFile, 'replacementmarker 软件测试发现缺陷。', [Text.UTF8Encoding]::new($false))
    $replacement = Invoke-RagApi -Path "$prefix/materials/$createdId" -Token $teacher -Method PUT -Form @{file=Get-Item -LiteralPath $tempFile}
    Assert-Code $replacement
    if ($replacement.data.version -ne 2 -or $replacement.data.published) { throw '更新必须产生未发布的新版本。' }
    Assert-Code (Invoke-RagApi -Path "$prefix/sources/$($source.chunkId)" -Token $student) 404
    Write-Host 'PASS 幂等重处理、下架失效、替换新版本和旧引用失效'
    $verificationSucceeded = $true
} finally {
    try {
        if ($createdId -and $teacher) {
            Assert-Code (Invoke-RagApi -Path "/campusai/courses/1/rag/materials/$createdId" -Token $teacher -Method DELETE)
            Write-Host 'PASS 已删除本脚本创建的临时资料'
        }
    } catch {
        $cleanupFailure = $_
        Write-Warning "未能清理本脚本创建的资料 $createdId，请在教师资料列表中核对。"
    } finally {
        if (Test-Path -LiteralPath $tempFile) { Remove-Item -LiteralPath $tempFile }
    }
}
if ($verificationSucceeded -and $cleanupFailure) { throw $cleanupFailure }

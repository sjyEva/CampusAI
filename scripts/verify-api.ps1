#Requires -Version 7.0

param(
    [uri]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$DemoPassword = $env:CAMPUSAI_DEV_PASSWORD
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrEmpty($DemoPassword)) {
    $DemoPassword = 'CampusAI-Dev-2026'
}
$baseAddress = $BaseUrl.AbsoluteUri.TrimEnd('/')

function Invoke-CampusApi {
    param(
        [string]$Path,
        [string]$Method = 'GET',
        [string]$Token,
        [hashtable]$Body
    )

    $request = @{
        Uri = "$baseAddress$Path"
        Method = $Method
        SkipHttpErrorCheck = $true
        TimeoutSec = 10
    }
    if ($Token) {
        $request.Headers = @{ Authorization = "Bearer $Token" }
    }
    if ($Body) {
        $request.ContentType = 'application/json'
        $request.Body = ConvertTo-Json -InputObject $Body -Compress
    }

    $response = Invoke-WebRequest @request
    $payload = if ($response.Content) { ConvertFrom-Json -InputObject $response.Content }
    [pscustomobject]@{ Status = [int]$response.StatusCode; Payload = $payload }
}

function Assert-ApiSuccess {
    param($Response, [string]$Context)

    if ($Response.Status -ne 200 -or $Response.Payload.code -ne 200) {
        throw "$Context failed (HTTP $($Response.Status), API $($Response.Payload.code))."
    }
}

$anonymous = Invoke-CampusApi -Path '/campusai/courses'
if ($anonymous.Status -notin @(401, 403) -and $anonymous.Payload.code -notin @(401, 403)) {
    throw 'Anonymous course list should return 401 or 403.'
}
if ($anonymous.Payload.data) {
    throw 'Anonymous course list returned course data.'
}
$anonymousChapters = Invoke-CampusApi -Path '/campusai/courses/1/chapters'
if ($anonymousChapters.Payload.code -ne 401 -or $anonymousChapters.Payload.data) {
    throw 'Anonymous chapter requests should return code 401 and no data.'
}
Write-Host 'PASS anonymous request denied'

$wrongLogin = Invoke-CampusApi -Path '/login' -Method POST -Body @{
    username = 'student'
    password = "$DemoPassword-incorrect"
}
if ($wrongLogin.Payload.token -or ($wrongLogin.Status -eq 200 -and $wrongLogin.Payload.code -eq 200)) {
    throw 'Invalid password was accepted.'
}
Write-Host 'PASS invalid password denied'

$scenarios = @(
    @{ Username = 'admin'; Role = 'admin'; Courses = @('CS101', 'SE101') }
    @{ Username = 'teacher'; Role = 'teacher'; Courses = @('CS101', 'SE101') }
    @{ Username = 'student'; Role = 'student'; Courses = @('CS101') }
    @{ Username = 'student2'; Role = 'student'; Courses = @('SE101') }
)

$chapterIdsByCourse = @{
    CS101 = '102,101'
    SE101 = '201'
}

foreach ($scenario in $scenarios) {
    $username = $scenario.Username
    $login = Invoke-CampusApi -Path '/login' -Method POST -Body @{
        username = $username
        password = $DemoPassword
    }
    Assert-ApiSuccess $login "$username login"
    $token = $login.Payload.token
    if (-not $token) {
        throw "$username login returned no token."
    }

    $identity = Invoke-CampusApi -Path '/getInfo' -Token $token
    Assert-ApiSuccess $identity "$username identity"
    if ($scenario.Role -notin @($identity.Payload.roles)) {
        throw "$username is missing the expected role $($scenario.Role)."
    }

    $courses = Invoke-CampusApi -Path '/campusai/courses' -Token $token
    Assert-ApiSuccess $courses "$username courses"
    $actual = @($courses.Payload.data | ForEach-Object { $_.courseCode } | Sort-Object)
    $expected = @($scenario.Courses | Sort-Object)
    if (($actual -join ',') -cne ($expected -join ',')) {
        throw "$username courses mismatch. Expected $($expected -join ', '), received $($actual -join ', ')."
    }
    Write-Host "PASS $username login, role and courses ($($actual -join ', '))"

    foreach ($course in $courses.Payload.data) {
        $detail = Invoke-CampusApi -Path "/campusai/courses/$($course.id)" -Token $token
        Assert-ApiSuccess $detail "$username course $($course.id) detail"
        if ($detail.Payload.data.courseCode -cne $course.courseCode) {
            throw "$username course detail does not match the visible course list."
        }
        $chapters = Invoke-CampusApi -Path "/campusai/courses/$($course.id)/chapters" -Token $token
        Assert-ApiSuccess $chapters "$username course $($course.id) chapters"
        $chapterIds = @($chapters.Payload.data | ForEach-Object { $_.id }) -join ','
        if ($chapterIds -ne $chapterIdsByCourse[$course.courseCode]) {
            throw "$username received unexpected chapters or order for $($course.courseCode)."
        }
    }

    $menu = Invoke-CampusApi -Path '/getRouters' -Token $token
    if ($scenario.Role -eq 'student') {
        if ($menu.Payload.code -ne 403) {
            throw "$username should not have access to the management menu."
        }
        $forbiddenId = if ($username -eq 'student') { 2 } else { 1 }
        $forbidden = Invoke-CampusApi -Path "/campusai/courses/$forbiddenId" -Token $token
        if ($forbidden.Payload.code -ne 404 -or $forbidden.Payload.data) {
            throw "$username received an unavailable course detail."
        }
        $forbiddenChapters = Invoke-CampusApi -Path "/campusai/courses/$forbiddenId/chapters" -Token $token
        if ($forbiddenChapters.Payload.code -ne 404 -or $forbiddenChapters.Payload.data) {
            throw "$username received chapter data for a course outside their scope."
        }
    } else {
        Assert-ApiSuccess $menu "$username management menu"
    }
    $missing = Invoke-CampusApi -Path '/campusai/courses/999999' -Token $token
    if ($missing.Payload.code -ne 404 -or $missing.Payload.data) {
        throw "$username should receive no detail for a missing course."
    }
    Write-Host "PASS $username course details, chapters and menu scope"

    $logout = Invoke-CampusApi -Path '/logout' -Method POST -Token $token
    Assert-ApiSuccess $logout "$username logout"
    $revoked = Invoke-CampusApi -Path '/getInfo' -Token $token
    if ($revoked.Payload.code -ne 401 -or $revoked.Payload.user) {
        throw "$username token should be invalid after logout."
    }
    Write-Host "PASS $username logout revokes token"
}

Write-Host 'All CampusAI API checks passed.'

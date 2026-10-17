param(
    [ValidateSet('web-only', 'mysql')]
    [string]$Profile = 'web-only'
)

$ErrorActionPreference = 'Stop'
if ($Profile -eq 'mysql' -and [string]::IsNullOrEmpty($env:DB_PASSWORD)) {
    throw 'Set DB_PASSWORD locally before mysql-profile smoke verification.'
}
if ($Profile -eq 'mysql' -and [string]::IsNullOrEmpty($env:JWT_SECRET_BASE64)) {
    throw 'Set JWT_SECRET_BASE64 locally before mysql-profile smoke verification.'
}
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskJdk = Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\java21') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
    Sort-Object Name -Descending | Select-Object -First 1
$taskJava = if ($taskJdk) { Join-Path $taskJdk.FullName 'bin\java.exe' }
    elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' }
    else { (Get-Command java -ErrorAction Stop).Source }
# --version avoids treating normal version output as a PowerShell 5.1 error.
$taskJavaVersion = (& $taskJava --version | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Cannot query the Java version.' }
if ($taskJavaVersion -notmatch '(?m)^(?:openjdk|java) 21(?:[.\s]|$)') {
    throw 'Java 21 is required for packaged application verification.'
}
$taskJar = Join-Path $taskRoot 'target\campuslife-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Run dev.ps1 -Action Build first.' }
$taskOutput = Join-Path $taskRoot '.cache\smoke.stdout.log'
$taskError = Join-Path $taskRoot '.cache\smoke.stderr.log'
New-Item -ItemType Directory -Path (Join-Path $taskRoot '.cache') -Force | Out-Null
$taskProcess = $null
$taskClient = $null
try {
    $taskProcess = Start-Process -FilePath $taskJava -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru `
        -ArgumentList @('-jar', ('"' + $taskJar + '"'), ('--spring.profiles.active=' + $Profile), '--server.port=0', '--server.address=127.0.0.1') `
        -RedirectStandardOutput $taskOutput -RedirectStandardError $taskError
    $taskPort = $null
    for ($taskAttempt = 0; $taskAttempt -lt 100; $taskAttempt++) {
        if ($taskProcess.HasExited) { throw 'Packaged application exited before startup. Inspect .cache/smoke.*.log.' }
        $taskLog = Get-Content -LiteralPath $taskOutput -Raw -ErrorAction SilentlyContinue
        if ($taskLog -match 'Tomcat started on port (\d+)') { $taskPort = $Matches[1]; break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $taskPort) { throw 'Packaged application did not start within 20 seconds.' }
    Add-Type -AssemblyName System.Net.Http
    $taskClient = [System.Net.Http.HttpClient]::new()
    $taskClient.Timeout = [TimeSpan]::FromSeconds(5)
    $taskBaseUrl = 'http://127.0.0.1:' + $taskPort
    $taskHealthResponse = $taskClient.GetAsync($taskBaseUrl + '/api/v1/system/health').GetAwaiter().GetResult()
    $taskHealth = $taskHealthResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ([int]$taskHealthResponse.StatusCode -ne 200 -or $taskHealth.code -ne 'OK' -or $taskHealth.data.status -ne 'UP') {
        throw 'Packaged health response failed validation.'
    }
    if (@($taskHealthResponse.Headers.GetValues('X-Trace-Id'))[0] -ne $taskHealth.traceId) {
        throw 'Packaged response trace ID does not match its header.'
    }
    if ($taskHealthResponse.Content.Headers.ContentType.CharSet -ine 'UTF-8') {
        throw 'Packaged JSON response must declare UTF-8.'
    }
    # Use the actual shell web client too: .NET HttpClient alone hides PowerShell 5.1 decoding bugs.
    $taskShellHealth = Invoke-RestMethod -Uri ($taskBaseUrl + '/api/v1/system/health')
    $taskExpectedMessage = [string][char]0x6210 + [char]0x529F
    if ($taskShellHealth.code -ne 'OK' -or $taskShellHealth.message -cne $taskExpectedMessage) {
        throw 'PowerShell did not decode the Chinese success message correctly.'
    }
    foreach ($taskPath in @('/api/v1/nonexistent', '/_test/failure')) {
        $taskResponse = $taskClient.GetAsync($taskBaseUrl + $taskPath).GetAwaiter().GetResult()
        $taskFailure = $taskResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $taskExpectedStatus = if ($Profile -eq 'mysql') { 401 } else { 404 }
        $taskExpectedCode = if ($Profile -eq 'mysql') { 'AUTH_REQUIRED' } else { 'RESOURCE_NOT_FOUND' }
        if ([int]$taskResponse.StatusCode -ne $taskExpectedStatus -or $taskFailure.code -ne $taskExpectedCode) {
            throw ('Unexpected packaged endpoint: ' + $taskPath)
        }
        $taskResponse.Dispose()
    }
    $taskHealthResponse.Dispose()
    Write-Host "PASS: Java 21 jar starts in $Profile profile; health/UTF-8/PowerShell decoding/trace/access boundary verified."
    if ($Profile -eq 'web-only') { Write-Host 'Database not checked in web-only profile.' }
} finally {
    if ($taskClient) { $taskClient.Dispose() }
    if ($taskProcess) {
        if (-not $taskProcess.HasExited) { $taskProcess.Kill(); $null = $taskProcess.WaitForExit(5000) }
        $taskProcess.Dispose()
    }
}

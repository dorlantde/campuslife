# Isolated native MySQL fixture; never connects to or stops the user's MySQL80 service.
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskMysql = Get-Command mysql -ErrorAction Stop
$taskMysqld = Get-Command mysqld -ErrorAction Stop
$taskBaseDir = Split-Path -Parent (Split-Path -Parent $taskMysqld.Source)
$taskFixtureParent = Join-Path $taskRoot '.cache\mysql-it'
New-Item -ItemType Directory -Path $taskFixtureParent -Force | Out-Null
$taskFixtureRoot = Join-Path $taskFixtureParent ([Guid]::NewGuid().ToString('N'))
$taskDataDir = Join-Path $taskFixtureRoot 'data'
$taskTmpDir = Join-Path $taskFixtureRoot 'tmp'
New-Item -ItemType Directory -Path $taskDataDir,$taskTmpDir -Force | Out-Null
$taskInitProcess = $null
$taskServerProcess = $null
$taskOriginalEncoding = $OutputEncoding
$taskOriginalEnvironment = @{}
foreach ($taskName in @('DB_HOST','DB_PORT','DB_NAME','DB_USERNAME','DB_PASSWORD','SPRING_PROFILES_ACTIVE','JWT_SECRET_BASE64')) {
    $taskOriginalEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}
$taskExitCode = 1
# PowerShell 5.1 turns redirected native stderr (including warnings) into ErrorRecord objects.
# Capture it here, then let the caller decide success from the native exit code.
function Invoke-FixtureMysql {
    param([string[]]$Arguments = @(), [string]$Sql)
    $ErrorActionPreference = 'Continue'
    $taskCapturedOutput = if ($PSBoundParameters.ContainsKey('Sql')) {
        $Sql | & $taskMysql.Source @taskClientArgs @Arguments 2>&1
    } else {
        & $taskMysql.Source @taskClientArgs @Arguments 2>&1
    }
    [pscustomobject]@{ Output = $taskCapturedOutput; ExitCode = $LASTEXITCODE }
}
try {
    $taskListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $taskListener.Start()
    $taskPort = $taskListener.LocalEndpoint.Port
    $taskListener.Stop()
    $taskCommonArgs = @('--no-defaults', ('--basedir="' + $taskBaseDir + '"'), ('--datadir="' + $taskDataDir + '"'))
    $taskInitProcess = Start-Process -FilePath $taskMysqld.Source -WindowStyle Hidden -PassThru `
        -ArgumentList ($taskCommonArgs + @('--initialize-insecure')) `
        -RedirectStandardOutput (Join-Path $taskFixtureRoot 'init.stdout.log') `
        -RedirectStandardError (Join-Path $taskFixtureRoot 'init.stderr.log')
    # Retain the process handle so Windows PowerShell 5.1 can read ExitCode after it exits.
    $null = $taskInitProcess.Handle
    if (-not $taskInitProcess.WaitForExit(60000)) { throw 'Isolated MySQL initialization timed out.' }
    if ($taskInitProcess.ExitCode -ne 0) { throw ('MySQL initialization failed; inspect ' + $taskFixtureRoot) }
    $taskServerArgs = $taskCommonArgs + @('--bind-address=127.0.0.1', ('--port=' + $taskPort), '--mysqlx=OFF',
        '--skip-log-bin', '--default-time-zone=+00:00', ('--tmpdir="' + $taskTmpDir + '"'),
        ('--log-error="' + (Join-Path $taskFixtureRoot 'server.log') + '"'))
    $taskServerProcess = Start-Process -FilePath $taskMysqld.Source -WindowStyle Hidden -PassThru `
        -ArgumentList $taskServerArgs `
        -RedirectStandardOutput (Join-Path $taskFixtureRoot 'server.stdout.log') `
        -RedirectStandardError (Join-Path $taskFixtureRoot 'server.stderr.log')
    $taskClientArgs = @('--no-defaults','--protocol=TCP','--host=127.0.0.1',('--port=' + $taskPort),'--user=root',
        '--skip-password','--ssl-mode=PREFERRED','--default-character-set=utf8mb4','--batch','--skip-column-names')
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
        if ($taskServerProcess.HasExited) { throw ('Isolated MySQL exited; inspect ' + $taskFixtureRoot) }
        $taskProbeResult = Invoke-FixtureMysql -Arguments @('--execute', 'SELECT 1')
        $taskProbe = $taskProbeResult.Output
        if ($taskProbeResult.ExitCode -eq 0 -and @($taskProbe | Where-Object { $_.ToString().Trim() -eq '1' }).Count -eq 1) {
            $taskReady = $true
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $taskReady) {
        $taskProbe | Out-String | Set-Content -LiteralPath (Join-Path $taskFixtureRoot 'probe.log') -Encoding UTF8
        throw ('Isolated MySQL did not become ready; inspect ' + $taskFixtureRoot)
    }
    $taskGeneratedPassword = [Guid]::NewGuid().ToString('N') + 'Aa1!'
    $taskSql = @"
CREATE DATABASE campuslife_it CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'campuslife'@'127.0.0.1' IDENTIFIED BY '$taskGeneratedPassword';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON campuslife_it.* TO 'campuslife'@'127.0.0.1';
"@
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    $taskSetupResult = Invoke-FixtureMysql -Sql $taskSql
    if ($taskSetupResult.ExitCode -ne 0) { throw 'Cannot provision the isolated project test account.' }
    $taskSql = $null
    $env:DB_HOST = '127.0.0.1'
    $env:DB_PORT = [string]$taskPort
    $env:DB_NAME = 'campuslife_it'
    $env:DB_USERNAME = 'campuslife'
    $env:DB_PASSWORD = $taskGeneratedPassword
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    . (Join-Path $PSScriptRoot 'jwt-env.ps1')
    $env:JWT_SECRET_BASE64 = New-CampusLifeJwtSecret
    Write-Host "Running MySQL integration checks on isolated loopback port $taskPort."
    & (Join-Path $PSScriptRoot 'dev.ps1') -Action DatabaseCheck
    $taskExitCode = $LASTEXITCODE
    if ($taskExitCode -eq 0) {
        $taskExitCode = 1
        & (Join-Path $PSScriptRoot 'smoke.ps1') -Profile mysql
        $taskExitCode = 0
        Write-Host 'PASS: isolated MySQL migration, constraints and mysql-profile jar startup.'
    }
} finally {
    foreach ($taskName in $taskOriginalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($taskName, $taskOriginalEnvironment[$taskName], 'Process')
    }
    $OutputEncoding = $taskOriginalEncoding
    $taskGeneratedPassword = $null
    $taskSql = $null
    # On Windows mysqld may launch a worker process. SQL SHUTDOWN stops the owned instance gracefully.
    $taskOwnedWorkers = @(Get-CimInstance Win32_Process -Filter "Name = 'mysqld.exe'" |
        Where-Object { $_.CommandLine -match ([regex]::Escape('--datadir=' + $taskDataDir) + '(?=["\s]|$)') })
    if ($taskServerProcess -and $taskClientArgs) {
        try { $null = Invoke-FixtureMysql -Arguments @('--execute', 'SHUTDOWN') } catch { }
    }
    foreach ($taskWorker in $taskOwnedWorkers) {
        Wait-Process -Id $taskWorker.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
        $taskStillOwned = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $taskWorker.ProcessId) |
            Where-Object { $_.CommandLine -match ([regex]::Escape('--datadir=' + $taskDataDir) + '(?=["\s]|$)') }
        if ($taskStillOwned) { Stop-Process -Id $taskWorker.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    foreach ($taskProcess in @($taskServerProcess,$taskInitProcess)) {
        if ($taskProcess) {
            if (-not $taskProcess.HasExited) { $taskProcess.Kill(); $null = $taskProcess.WaitForExit(10000) }
            $taskProcess.Dispose()
        }
    }
    if ($taskExitCode -eq 0) {
        $taskResolvedFixture = (Resolve-Path -LiteralPath $taskFixtureRoot).Path
        $taskResolvedParent = (Resolve-Path -LiteralPath $taskFixtureParent).Path
        if (-not $taskResolvedFixture.StartsWith($taskResolvedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) `
            -or (Split-Path -Leaf $taskResolvedFixture) -notmatch '^[a-f0-9]{32}$') {
            throw 'Refusing cleanup outside the generated fixture directory.'
        }
        Remove-Item -LiteralPath $taskResolvedFixture -Recurse -Force
    } else {
        Write-Host "Failed fixture retained for diagnostics: $taskFixtureRoot"
    }
}
exit $taskExitCode

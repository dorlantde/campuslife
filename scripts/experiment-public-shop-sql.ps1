# M01.1: independent fixtures; candidates only exist in test code, no production SQL/index mutation.
param(
    [ValidateRange(1, 2)]
    [int]$Runs = 2,
    [ValidateRange(1, 300)]
    [int]$StartupTimeoutSeconds = 90
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskOriginalEnvironment = @{}
foreach ($taskName in @('MAVEN_ARGS','CAMPUSLIFE_SQL_EXPERIMENT','CAMPUSLIFE_SQL_OUTPUT','CAMPUSLIFE_SQL_RUN')) {
    $taskOriginalEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}
$taskRunName = 'public-shop-sql-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$taskOutput = Join-Path $taskRoot ('output\performance\' + $taskRunName)
New-Item -ItemType Directory -Path $taskOutput -ErrorAction Stop | Out-Null
$taskCompleted = @()
$taskExitCode = 1
Push-Location -LiteralPath $taskRoot
try {
    $env:MAVEN_ARGS = '-Dit.test=PublicShopSqlExperiment'
    $env:CAMPUSLIFE_SQL_EXPERIMENT = 'isolated-fixture'
    $env:CAMPUSLIFE_SQL_OUTPUT = $taskOutput
    foreach ($taskRun in 1..$Runs) {
        $env:CAMPUSLIFE_SQL_RUN = [string]$taskRun
        $taskLog = Join-Path $taskRoot ('.cache\' + $taskRunName + '-run' + $taskRun + '.log')
        Write-Host "SQL experiment run $taskRun/$Runs. Log: $taskLog"
        & (Join-Path $PSScriptRoot 'test-mysql.ps1') -StartupTimeoutSeconds $StartupTimeoutSeconds *> $taskLog
        if ($LASTEXITCODE -ne 0) { throw "SQL experiment failed. Inspect $taskLog" }
        if (-not (Test-Path -LiteralPath (Join-Path $taskOutput ('run' + $taskRun + '.json')))) { throw 'Measurement evidence is missing.' }
        $taskCompleted += [pscustomobject]@{ run=$taskRun; log=('.cache/' + $taskRunName + '-run' + $taskRun + '.log'); evidence=('run' + $taskRun + '.json') }
        Write-Host "PASS: run $taskRun assertions, measurements, ordinary tests, mysql jar smoke and fixture cleanup."
    }
    [pscustomobject]@{ completedAt=[DateTime]::UtcNow.ToString('o'); runs=$taskCompleted } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskOutput 'completed.json') -Encoding UTF8
    Write-Host "PASS: M01.1 experiment. Evidence: $taskOutput"
    $taskExitCode = 0
} finally {
    foreach ($taskName in $taskOriginalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($taskName, $taskOriginalEnvironment[$taskName], 'Process')
    }
    Pop-Location
}
exit $taskExitCode

# Runs the opt-in query baseline inside the existing disposable MySQL fixture.
param(
    [ValidateRange(1, 300)]
    [int]$StartupTimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskPreviousMavenArgs = $env:MAVEN_ARGS
$taskPreviousOptIn = $env:CAMPUSLIFE_QUERY_BASELINE
$taskLogRoot = Join-Path $taskRoot '.cache'
New-Item -ItemType Directory -Path $taskLogRoot -Force | Out-Null
$taskLog = Join-Path $taskLogRoot ('public-shop-baseline-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '.log')
$taskExitCode = 1
Push-Location -LiteralPath $taskRoot
try {
    # Deliberately select this harness only; ordinary Surefire tests still execute.
    $env:MAVEN_ARGS = '-Dit.test=PublicShopQueryBaseline'
    $env:CAMPUSLIFE_QUERY_BASELINE = 'isolated-fixture'
    Write-Host 'Measuring current public-shop queries in a disposable MySQL instance (1,000 and 10,000 shops).'
    Write-Host "Log: $taskLog"
    & (Join-Path $PSScriptRoot 'test-mysql.ps1') -StartupTimeoutSeconds $StartupTimeoutSeconds *> $taskLog
    $taskExitCode = $LASTEXITCODE
    if ($taskExitCode -ne 0) { throw "Query baseline failed. Inspect $taskLog" }
    Write-Host 'PASS: query baseline, fixture assertions, ordinary tests and mysql-profile jar smoke.'
    Write-Host 'Raw measurements: output/performance/public-shop-query-baseline.json'
    Write-Host 'The raw file is replaced on the next successful measurement; retain a copy before comparisons.'
} finally {
    $env:MAVEN_ARGS = $taskPreviousMavenArgs
    $env:CAMPUSLIFE_QUERY_BASELINE = $taskPreviousOptIn
    Pop-Location
}
exit $taskExitCode

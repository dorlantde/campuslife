# M01.2 paired full-HTTP check on a disposable native MySQL instance.
param(
    [ValidateSet('validation','adopted')]
    [string]$Phase = 'adopted',
    [ValidateRange(1,300)]
    [int]$StartupTimeoutSeconds = 90
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskPrevious = @{}
foreach ($taskName in @('MAVEN_ARGS','CAMPUSLIFE_LIST_ADOPTION','CAMPUSLIFE_LIST_OUTPUT','CAMPUSLIFE_LIST_PHASE')) {
    $taskPrevious[$taskName] = [Environment]::GetEnvironmentVariable($taskName,'Process')
}
$taskNamePart = 'public-shop-list-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$taskOutput = Join-Path $taskRoot ('output\performance\' + $taskNamePart)
New-Item -ItemType Directory -Path $taskOutput -ErrorAction Stop | Out-Null
$taskLog = Join-Path $taskRoot ('.cache\' + $taskNamePart + '.log')
Push-Location -LiteralPath $taskRoot
try {
    $env:CAMPUSLIFE_LIST_ADOPTION = 'isolated-fixture'
    $env:CAMPUSLIFE_LIST_OUTPUT = $taskOutput
    $env:CAMPUSLIFE_LIST_PHASE = $Phase
    $env:MAVEN_ARGS = '-Dit.test=PublicShopListAdoption'
    Write-Host "M01.2 $Phase HTTP comparison. Log: $taskLog"
    & (Join-Path $PSScriptRoot 'test-mysql.ps1') -StartupTimeoutSeconds $StartupTimeoutSeconds *> $taskLog
    if ($LASTEXITCODE -ne 0) { throw "M01.2 check failed. Inspect $taskLog" }
    $taskEvidence = Join-Path $taskOutput ($Phase + '.json')
    if (-not (Test-Path -LiteralPath $taskEvidence)) { throw 'HTTP measurement evidence is missing.' }
    [pscustomobject]@{ phase=$Phase;completedAt=[DateTime]::UtcNow.ToString('o');log=('.cache/'+$taskNamePart+'.log');evidence=($Phase+'.json') } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskOutput 'completed.json') -Encoding UTF8
    Write-Host "PASS: M01.2 $Phase equality/RR/HTTP metrics, ordinary tests, mysql JAR and cleanup."
    Write-Host "Evidence: $taskOutput"
} finally {
    foreach ($taskName in $taskPrevious.Keys) { [Environment]::SetEnvironmentVariable($taskName,$taskPrevious[$taskName],'Process') }
    Pop-Location
}

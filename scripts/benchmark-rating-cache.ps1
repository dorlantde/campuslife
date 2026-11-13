# Paired full-HTTP and fault verification on two independently owned MySQL/Redis fixtures.
param([ValidateRange(1,2)][int]$Runs=2)
$ErrorActionPreference='Stop'
for($taskRun=1;$taskRun -le $Runs;$taskRun++){
    Write-Host "M02.3 independent run $taskRun/$Runs"
    & (Join-Path $PSScriptRoot 'test-rating-cache.ps1') -Phase Acceptance
    if($LASTEXITCODE -ne 0){throw "M02.3 run $taskRun failed."}
}

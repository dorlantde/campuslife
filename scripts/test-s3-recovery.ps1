# Isolated C verification only. This script has no real-data migration or deletion option.
param([ValidateSet('All','Faults','Restore')][string]$Phase='All',[ValidateRange(0,1800)][int]$ManualSeconds=0)
$ErrorActionPreference='Stop';$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskSaved=@{};$taskNames=@('S3_RECOVERY_OUTPUT','S3_RECOVERY_MYSQLDUMP','S3_RECOVERY_MYSQL','S3_RECOVERY_MYSQLD','S3_RECOVERY_MANUAL_SECONDS')
foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskFolder=Join-Path $taskRoot ('.cache/s3-recovery/'+[Guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $taskFolder -Force | Out-Null
try {
 $env:S3_RECOVERY_OUTPUT=$taskFolder
 $env:S3_RECOVERY_MANUAL_SECONDS=[string]$ManualSeconds
 if($ManualSeconds -gt 0){Write-Host ('Restored Swagger session details will appear in '+(Join-Path $taskFolder 'manual.json')+'; read-only manual window '+$ManualSeconds+' seconds.')}
 $env:S3_RECOVERY_MYSQLDUMP=(Get-Command mysqldump -ErrorAction Stop).Source
 $env:S3_RECOVERY_MYSQL=(Get-Command mysql -ErrorAction Stop).Source
 $env:S3_RECOVERY_MYSQLD=(Get-Command mysqld -ErrorAction Stop).Source
 $taskPhases=if($Phase -eq 'All'){@('Faults','Restore')}else{@($Phase)}
 foreach($taskPhase in $taskPhases){
  # Backup always uses a new independent DB/S3 fixture, with all application instances explicitly closed offline.
  $taskCases=if($taskPhase -eq 'Faults'){'S3RecoveryMysqlIT'}else{'S3BackupRestoreCheck'}
  & (Join-Path $PSScriptRoot 'test-s3-business.ps1') -Cases $taskCases -UnitCases 'FilePolicyTest,S3StoragePolicyTest,FileCleanupWorkerTest' -Task 'M04.2-C' *> (Join-Path $taskFolder ($taskPhase.ToLowerInvariant()+'.log'))
  if($LASTEXITCODE -ne 0){throw ('S3 '+$taskPhase+' checks failed; evidence: '+$taskFolder)}
  Write-Host ('PASS: '+$taskPhase+' phase. Evidence: '+$taskFolder)
 }
} finally {foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}}
[pscustomobject]@{task='M04.2-C';phase=$Phase;completedAt=[DateTime]::UtcNow.ToString('o');resourcesRemoved=$true} | ConvertTo-Json | Set-Content (Join-Path $taskFolder 'completed.json') -Encoding UTF8
Write-Host ('PASS: selected isolated S3 recovery phases and owned cleanup: '+$taskFolder)

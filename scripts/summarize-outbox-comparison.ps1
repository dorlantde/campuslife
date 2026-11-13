param([Parameter(Mandatory=$true)][string]$InputDirectory,[string]$ReportDate='2026-11-01')
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskInput=(Resolve-Path -LiteralPath $InputDirectory).Path
$taskComparison=Get-Content -LiteralPath (Join-Path $taskInput 'comparison.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if(-not $taskComparison.complete -or ($taskComparison.order -join ',') -ne 'OLD,NEW'){throw 'Completed serial OLD/NEW evidence required.'}
$taskOutput=Join-Path $taskRoot ('docs/reports/outbox-comparison-'+$ReportDate)
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
$taskVersions=@{}
foreach($taskVersion in @('old','new')){
 $taskRaw=Join-Path $taskInput ($taskVersion+'/results.json')
 $taskData=Get-Content -LiteralPath $taskRaw -Raw -Encoding UTF8 | ConvertFrom-Json
 if($taskData.profile -ne 'Measure' -or -not $taskData.complete -or @($taskData.runs).Count -ne 24 -or $taskData.environment.child.production.binary -ne $taskVersion.ToUpperInvariant()){throw 'Expected 24 completed Measure runs with the correct binary identity.'}
 $taskArchive=Join-Path $taskOutput ($taskVersion+'-raw.json');Copy-Item -LiteralPath $taskRaw -Destination $taskArchive
 & (Join-Path $PSScriptRoot 'summarize-coupon-measurement.ps1') -InputFile $taskArchive -OutputDirectory (Join-Path $taskOutput $taskVersion) -ReportDate $ReportDate -Task 'M08.5-C'
 $taskVersions[$taskVersion]=Get-Content -LiteralPath (Join-Path $taskOutput ($taskVersion+'/coupon-measurement-'+$ReportDate+'.json')) -Raw -Encoding UTF8 | ConvertFrom-Json
}
$taskGroups=@()
foreach($taskConcurrency in @(1,5,10)){
 $taskOld=$taskVersions.old.groups | Where-Object {$_.concurrency -eq $taskConcurrency -and $_.mode -eq 'ASYNC'}
 $taskNew=$taskVersions.new.groups | Where-Object {$_.concurrency -eq $taskConcurrency -and $_.mode -eq 'ASYNC'}
 $taskOldSync=$taskVersions.old.groups | Where-Object {$_.concurrency -eq $taskConcurrency -and $_.mode -eq 'SYNC'}
 $taskNewSync=$taskVersions.new.groups | Where-Object {$_.concurrency -eq $taskConcurrency -and $_.mode -eq 'SYNC'}
 if($taskOld.trials -ne 3 -or $taskNew.trials -ne 3 -or $taskOldSync.trials -ne 3 -or $taskNewSync.trials -ne 3){throw 'Each binary/concurrency/mode must contain exactly three pairs.'}
 $taskDrop=100*(1-$taskNew.completionP95Ms/$taskOld.completionP95Ms)
 $taskGroups+=[pscustomobject][ordered]@{concurrency=$taskConcurrency;oldAsync=$taskOld;newAsync=$taskNew;oldSync=$taskOldSync;newSync=$taskNewSync;completeP95ReductionPercent=$taskDrop;goalPassed=($taskNew.completionP95Ms -le 5000 -and $taskDrop -ge 70);syncReferenceRatio=($taskNewSync.completionP95Ms/$taskOldSync.completionP95Ms)}
}
$taskResult=[ordered]@{date=$ReportDate;task='M08.5-C';complete=$true;order=$taskComparison.order;identity=$taskComparison.identity;versions=@{old=@{environment=$taskVersions.old.environment;rawSha256=$taskVersions.old.rawSha256;runs=24;posts=($taskVersions.old.runs | Measure-Object posts -Sum).Sum};new=@{environment=$taskVersions.new.environment;rawSha256=$taskVersions.new.rawSha256;runs=24;posts=($taskVersions.new.runs | Measure-Object posts -Sum).Sum}};goal='each concurrency median of three ASYNC per-run complete p95 <=5000ms and >=70% reduction vs OLD';goalPassed=(@($taskGroups | Where-Object {-not $_.goalPassed}).Count -eq 0);groups=$taskGroups;limitations=@('OLD then NEW sequentially on the same isolated dependencies; time drift remains, inspect SYNC references','No production capacity or multi-node HA inference','one fixed configuration comparison, no parameter search','all 48 runs retained including warmup and supplementary scenarios; costs include polling and background work')}
$taskResult | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath (Join-Path $taskOutput 'comparison.json') -Encoding UTF8
$taskGroups | Select-Object concurrency,@{n='oldP95Ms';e={$_.oldAsync.completionP95Ms}},@{n='newP95Ms';e={$_.newAsync.completionP95Ms}},completeP95ReductionPercent,goalPassed | Format-Table -AutoSize
Write-Output ('Archived fixed comparison: '+$taskOutput)

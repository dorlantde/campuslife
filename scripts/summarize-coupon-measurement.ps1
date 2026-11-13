param(
 [Parameter(Mandatory=$true)][string]$InputFile,
 [string]$OutputDirectory='docs/reports',
 [string]$ReportDate='2026-11-01',
 [string]$Task='M08.4-B'
)
$ErrorActionPreference='Stop'
$taskData=Get-Content -LiteralPath $InputFile -Raw -Encoding UTF8 | ConvertFrom-Json
if(-not $taskData.complete -or @($taskData.runs | Where-Object {$_.invariants -ne 'passed' -or @($_.errors).Count -gt 0}).Count){throw 'Incomplete/invalid runs cannot produce a completed measurement summary. Retain and inspect raw results.'}
function Get-CouponQuantiles {
 param([object[]]$Values)
 $taskSorted=@($Values | Where-Object {$null -ne $_} | ForEach-Object {[double]$_} | Sort-Object)
 if(-not $taskSorted.Count){return [pscustomobject]@{count=0;p50=$null;p95=$null;max=$null}}
 [pscustomobject]@{count=$taskSorted.Count;p50=$taskSorted[[Math]::Max(0,[Math]::Ceiling($taskSorted.Count*0.5)-1)];p95=$taskSorted[[Math]::Max(0,[Math]::Ceiling($taskSorted.Count*0.95)-1)];max=$taskSorted[-1]}
}
function Get-CouponCounter {
 param($Run,[string]$Name)
 $taskCounter=@($Run.sql | Where-Object {$_.digest -eq ('global:'+$Name)})
 if($taskCounter.Count){return [double]$taskCounter[0].count}
 return $null
}
$taskRuns=@();$taskCsv=@()
foreach($taskRun in $taskData.runs){
 $taskAccepted=@($taskRun.admissions | Where-Object {$_.http -in @(201,202) -and $_.replay -ne 'true'})
 $taskComplete=@($taskRun.observations | Where-Object {$_.state -eq 'SUCCEEDED' -and $null -ne $_.completeMs})
 $taskSql=@($taskRun.sql | Where-Object {$_.digest -notlike 'global:*'})
 $taskWrites=@($taskSql | Where-Object {$_.text -match '^\s*(INSERT|UPDATE|DELETE|REPLACE)\b'})
 $taskCounts=[ordered]@{};foreach($taskCode in @(-1,0,200,201,202,409,401,403,404,429,500,503)){$taskCounts[[string]$taskCode]=@($taskRun.admissions | Where-Object {$_.http -eq $taskCode}).Count}
 $taskPostEnd=($taskRun.admissions | ForEach-Object {$_.offsetMs+$_.latencyMs} | Measure-Object -Maximum).Maximum
 $taskSuccessEnd=($taskComplete | Measure-Object cohortCompleteMs -Maximum).Maximum
 $taskDrain=[Math]::Max($taskPostEnd,$taskSuccessEnd)
 $taskHeap=@($taskRun.samples | ForEach-Object {[double]$_.process.heapUsed})+@([double]$taskRun.processBefore.heapUsed,[double]$taskRun.processAfter.heapUsed)
 $taskSummary=[ordered]@{
  trial=$taskRun.trial;kind=$taskRun.kind;pair=$taskRun.pair;mode=$taskRun.mode;concurrency=$taskRun.concurrency;people=$taskRun.people;stock=$taskRun.stock
  posts=$taskRun.posts;gets=$taskRun.getRequests;httpCounts=$taskCounts;awarded=$taskComplete.Count;failed=@($taskRun.observations | Where-Object {$_.state -eq 'FAILED'}).Count;censored=@($taskRun.observations | Where-Object {$null -eq $_.completeMs}).Count
  allPostMs=(Get-CouponQuantiles @($taskRun.admissions.latencyMs));firstAcceptedPostMs=(Get-CouponQuantiles @($taskAccepted.latencyMs));completionMs=(Get-CouponQuantiles @($taskComplete.completeMs))
  postBatchMs=$taskPostEnd;batchDrainedMs=$taskDrain;successfulPerSecond=if($taskDrain -gt 0){$taskComplete.Count*1000.0/$taskDrain}else{$null};windowMs=$taskRun.windowMs;switchMs=$taskRun.switchMs
  sqlCount=($taskSql | Measure-Object count -Sum).Sum;dmlCount=($taskWrites | Measure-Object count -Sum).Sum;sqlWaitMs=(($taskSql | Measure-Object waitPs -Sum).Sum/1e9);statementLockMs=(($taskSql | Measure-Object lockPs -Sum).Sum/1e9);rowsExamined=($taskSql | Measure-Object examined -Sum).Sum
  rowLockWaits=(Get-CouponCounter $taskRun 'Innodb_row_lock_waits');rowLockWaitMs=(Get-CouponCounter $taskRun 'Innodb_row_lock_time');redoBytes=(Get-CouponCounter $taskRun 'Innodb_os_log_written')
  pendingPeak=($taskRun.samples | Measure-Object pending -Maximum).Maximum;outboxBacklogPeak=($taskRun.samples | Measure-Object outboxBacklog -Maximum).Maximum;queueReadyPeak=($taskRun.samples | Measure-Object queueReady -Maximum).Maximum;queueUnackedPeak=($taskRun.samples | Measure-Object queueUnacked -Maximum).Maximum
  appCpuMs=($taskRun.processAfter.cpuNanos-$taskRun.processBefore.cpuNanos)/1e6;appSampledHeapPeakBytes=($taskHeap | Measure-Object -Maximum).Maximum
  redisCommandDelta=($taskRun.redisAfter.total_commands_processed-$taskRun.redisBefore.total_commands_processed);redisRuntimeMemoryDelta=($taskRun.redisAfter.used_memory-$taskRun.redisBefore.used_memory);redisCpuMs=(($taskRun.redisAfter.used_cpu_sys+$taskRun.redisAfter.used_cpu_user)-($taskRun.redisBefore.used_cpu_sys+$taskRun.redisBefore.used_cpu_user))*1000
  maxObservedPollGapMs=($taskRun.observations | Measure-Object maxPollGapMs -Maximum).Maximum;postWindowBrokerDrainMs=$taskRun.postWindowBrokerDrainMs;invariants=$taskRun.invariants
 }
 $taskRuns+=[pscustomobject]$taskSummary
 foreach($taskRequest in $taskRun.admissions){
  $taskObservation=@($taskRun.observations | Where-Object {$_.id -eq $taskRequest.claimId}) | Select-Object -First 1
  $taskCsv+=[pscustomobject]@{trial=$taskRun.trial;kind=$taskRun.kind;pair=$taskRun.pair;mode=$taskRun.mode;concurrency=$taskRun.concurrency;studentIndex=$taskRequest.studentIndex;http=$taskRequest.http;code=$taskRequest.code;replay=$taskRequest.replay;offsetMs=$taskRequest.offsetMs;postMs=$taskRequest.latencyMs;claimId=$taskRequest.claimId;claimCompletionMs=$taskObservation.completeMs;claimState=$taskObservation.state}
 }
}
$taskGroups=@()
foreach($taskConcurrency in @(1,5,10)){foreach($taskMode in @('SYNC','ASYNC')){
 $taskSelected=@($taskRuns | Where-Object {$_.kind -eq 'scarce' -and $_.concurrency -eq $taskConcurrency -and $_.mode -eq $taskMode})
 $taskGroups+=[pscustomobject]@{
  concurrency=$taskConcurrency;mode=$taskMode;trials=$taskSelected.Count
  acceptedP95Ms=(Get-CouponQuantiles @($taskSelected | ForEach-Object {$_.firstAcceptedPostMs.p95})).p50
  allPostP95Ms=(Get-CouponQuantiles @($taskSelected | ForEach-Object {$_.allPostMs.p95})).p50
  completionP95Ms=(Get-CouponQuantiles @($taskSelected | ForEach-Object {$_.completionMs.p95})).p50
  batchSuccessfulPerSecond=(Get-CouponQuantiles @($taskSelected.successfulPerSecond)).p50
  sqlCount=(Get-CouponQuantiles @($taskSelected.sqlCount)).p50;dmlCount=(Get-CouponQuantiles @($taskSelected.dmlCount)).p50
  rowLockWaits=(Get-CouponQuantiles @($taskSelected.rowLockWaits)).p50;rowLockWaitMs=(Get-CouponQuantiles @($taskSelected.rowLockWaitMs)).p50
  appCpuMs=(Get-CouponQuantiles @($taskSelected.appCpuMs)).p50;redisCommandDelta=(Get-CouponQuantiles @($taskSelected.redisCommandDelta)).p50
 }
}}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$taskName='coupon-measurement-'+$ReportDate+$(if($taskData.profile -ne 'Measure'){'-pilot'}else{''})
$taskResult=[ordered]@{date=$ReportDate;task=$Task;profile=$taskData.profile;complete=$taskData.complete;operations=75;migration='V23';tables=32;rawFile=[IO.Path]::GetFullPath($InputFile);rawSha256=(Get-FileHash -LiteralPath $InputFile -Algorithm SHA256).Hash;environment=$taskData.environment;quantile='nearest rank; group values are medians of per-trial values, not pooled percentiles';groups=$taskGroups;runs=$taskRuns;limitations=@('single host; three pairs per concurrency in Measure','closed concurrency loop, no open-arrival capacity claim','250ms completion GET polls included in SQL cost','fixed observation window includes scheduler/observer cost; process CPU samples +/-1s','heap is not RSS; Broker management statistics refresh asynchronously','runtime Redis memory delta excludes pre-window initialization; previous datasets retained')}
$taskResult | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath (Join-Path $OutputDirectory ($taskName+'.json')) -Encoding UTF8
$taskCsv | Export-Csv -LiteralPath (Join-Path $OutputDirectory ($taskName+'-requests.csv')) -NoTypeInformation -Encoding UTF8
$taskGroups | Format-Table -AutoSize
Write-Output ('Generated '+$taskName+' JSON and request CSV; raw SHA256 '+$taskResult.rawSha256)

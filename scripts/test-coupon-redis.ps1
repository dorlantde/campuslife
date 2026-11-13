param([ValidateSet('Core','Messaging','Cache')][string]$Phase='Core',[string]$Cases='CouponRedisIT,CouponAsyncMysqlIT,MysqlIntegrationIT,OpenApiMysqlIT',[string]$UnitCases='CouponRedisRepairJobTest')
$ErrorActionPreference='Stop';$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'coupon-redis-env.ps1')
$taskIdentity=[Guid]::NewGuid().ToString('N');$taskFolder=Join-Path $taskRoot ('.cache\coupon-redis-it\'+$taskIdentity);$taskState=$null;$taskSuccess=$false;$taskSaved=@{}
$taskNames=@('MAVEN_ARGS','COUPON_REDIS_ENABLED','COUPON_REDIS_NAMESPACE','COUPON_REDIS_HOST','COUPON_REDIS_PORT','COUPON_REDIS_USERNAME','COUPON_REDIS_PASSWORD','COUPON_REDIS_SCHEDULING_ENABLED','CAMPUSLIFE_COUPON_REDIS_IT','CAMPUSLIFE_COUPON_REDIS_CONTAINER','CAMPUSLIFE_COUPON_REDIS_IDENTITY','CAMPUSLIFE_COUPON_REDIS_ADMIN_PASSWORD','RATING_CACHE_ENABLED')
foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
 $taskState=New-CampusLifeCouponRedis $taskFolder $taskIdentity 'test';Wait-CampusLifeCouponRedis $taskState
 $env:COUPON_REDIS_ENABLED='true';$env:COUPON_REDIS_NAMESPACE=$taskState.namespace;$env:COUPON_REDIS_HOST='127.0.0.1';$env:COUPON_REDIS_PORT=[string]$taskState.port;$env:COUPON_REDIS_USERNAME=$taskState.username;$env:COUPON_REDIS_PASSWORD=$taskState.password;$env:COUPON_REDIS_SCHEDULING_ENABLED='false';$env:RATING_CACHE_ENABLED='false'
 $env:CAMPUSLIFE_COUPON_REDIS_IT='owned-isolated-fixture';$env:CAMPUSLIFE_COUPON_REDIS_CONTAINER=$taskState.container;$env:CAMPUSLIFE_COUPON_REDIS_IDENTITY=$taskIdentity;$env:CAMPUSLIFE_COUPON_REDIS_ADMIN_PASSWORD=$taskState.adminPassword
 Write-Host ('Owned AOF/noeviction coupon Redis ready at loopback port '+$taskState.port+'.')
 if($Phase -eq 'Messaging'){
  & (Join-Path $PSScriptRoot 'test-messaging.ps1') -Cases $Cases -UnitCases $UnitCases
 }elseif($Phase -eq 'Cache'){
  & (Join-Path $PSScriptRoot 'test-rating-cache.ps1') -Phase Cache -AdditionalCases 'CouponRedisIsolationIT' -UnitCases 'RatingCacheTest'
 }else{
  $env:MAVEN_ARGS='-Dit.test='+$Cases;if($UnitCases){$env:MAVEN_ARGS+=' -Dtest='+$UnitCases}else{$env:MAVEN_ARGS+=' -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false'}
  & (Join-Path $PSScriptRoot 'test-mysql.ps1') -StartupTimeoutSeconds 90
 }
 if($LASTEXITCODE -ne 0){throw 'Coupon Redis checks failed; inspect test reports.'}
 $taskSuccess=$true
}finally{
 if($taskState){Remove-CampusLifeCouponRedis $taskState}
 foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}
}
if($taskSuccess){[pscustomobject]@{completedAt=[DateTime]::UtcNow.ToString('o');phase=$Phase;cases=$Cases;image=$CampusLifeCouponRedisImage;containerRemoved=$true;volumeRemoved=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskFolder 'completed.json') -Encoding UTF8;Write-Host ('PASS: owned coupon Redis container/volume and MySQL cleaned. Evidence: '+$taskFolder)}

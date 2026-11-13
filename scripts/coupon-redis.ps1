# Local reservation Redis lifecycle only. No campaign mode changes or SQL credential access.
param([ValidateSet('Start','Stop','Status')][string]$Action='Status')
$ErrorActionPreference='Stop';$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'coupon-redis-env.ps1')
$taskFolder=Join-Path $taskRoot '.local\coupon-redis';$taskManifest=Join-Path $taskFolder 'manifest.json';$taskProperties=Join-Path $taskRoot '.local\coupon-redis.properties';$taskState=$null
if(Test-Path -LiteralPath $taskManifest){
 $taskState=Get-Content -LiteralPath $taskManifest -Raw | ConvertFrom-Json
 Assert-CampusLifeCouponRedisOwner $taskState
 if($taskState.kind -ne 'dev'){throw 'Local manifest must refer to a dev instance.'}
}elseif($Action -ne 'Start'){Write-Host 'No project-owned local coupon Redis has been created.';exit 0}
elseif(Test-Path -LiteralPath $taskProperties){throw 'Preserve existing manual coupon Redis properties; no ownership manifest was found.'}
if($Action -eq 'Start'){
 if(-not $taskState){$taskState=New-CampusLifeCouponRedis $taskFolder ([Guid]::NewGuid().ToString('N')) 'dev'}else{& docker start $taskState.container | Out-Null;if($LASTEXITCODE -ne 0){throw 'Cannot start the verified coupon Redis.'}}
 Wait-CampusLifeCouponRedis $taskState
 @('campuslife.coupon-redis.enabled=${COUPON_REDIS_ENABLED:true}',('campuslife.coupon-redis.namespace=${COUPON_REDIS_NAMESPACE:'+$taskState.namespace+'}'),'campuslife.coupon-redis.host=${COUPON_REDIS_HOST:127.0.0.1}',('campuslife.coupon-redis.port=${COUPON_REDIS_PORT:'+$taskState.port+'}'),('campuslife.coupon-redis.username=${COUPON_REDIS_USERNAME:'+$taskState.username+'}'),('campuslife.coupon-redis.password=${COUPON_REDIS_PASSWORD:'+$taskState.password+'}')) | Set-Content -LiteralPath $taskProperties -Encoding ASCII
 Write-Host ('PASS: dedicated AOF/noeviction coupon Redis ready at 127.0.0.1:'+$taskState.port+'. Restart the application to apply local opt-in.')
 Write-Host 'This does not enable ASYNC campaigns; public async admission remains unavailable in C.'
}elseif($Action -eq 'Stop'){
 & docker stop --time 2 $taskState.container | Out-Null;if($LASTEXITCODE -ne 0){throw 'Cannot stop the verified coupon Redis.'}
 @('campuslife.coupon-redis.enabled=${COUPON_REDIS_ENABLED:false}') | Set-Content -LiteralPath $taskProperties -Encoding ASCII
 Write-Host 'PASS: coupon Redis stopped; its owned AOF volume is retained. Local opt-in disabled; restart the application.'
}else{
 $taskStatus=(& docker inspect --format '{{.State.Status}}' $taskState.container | Out-String).Trim()
 if($LASTEXITCODE -ne 0){throw 'Cannot inspect the verified coupon Redis.'}
 Write-Host $taskStatus
 if($taskStatus -eq 'running'){& docker port $taskState.container '6379/tcp'}else{Write-Host ('Configured loopback port: '+$taskState.port+' (stopped; not listening).')}
}

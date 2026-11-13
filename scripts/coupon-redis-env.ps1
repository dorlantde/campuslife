# Owns only explicitly labelled reservation Redis resources; never reads DB/JWT or rating config.
$CampusLifeCouponRedisImage='redis:8.2.4@sha256:320e9ce6d5c940815a84d989f0e53e3a4d065c0d9132cd9e1222265e78eb6080'
function Assert-CampusLifeCouponRedisOwner($State) {
 if($State.identity -notmatch '^[a-f0-9]{32}$' -or $State.container -notmatch '^[a-f0-9]{64}$' -or $State.kind -notin @('test','dev') -or $State.volume -ne ('campuslife-coupon-'+$State.kind+'-'+$State.identity)){throw 'Invalid coupon Redis ownership manifest.'}
 $taskLabels=(& docker inspect --format '{{json .Config.Labels}}' $State.container | Out-String).Trim() | ConvertFrom-Json
 if($LASTEXITCODE -ne 0 -or $taskLabels.'campuslife.coupon.identity' -ne $State.identity -or $taskLabels.'campuslife.coupon.kind' -ne $State.kind){throw 'Coupon Redis container ownership mismatch.'}
 $taskBindings=(& docker inspect --format '{{json .HostConfig.PortBindings}}' $State.container | Out-String).Trim() | ConvertFrom-Json
 $taskBinding=$taskBindings.'6379/tcp'
 if($LASTEXITCODE -ne 0 -or @($taskBinding).Count -ne 1 -or $taskBinding[0].HostIp -ne '127.0.0.1' -or $taskBinding[0].HostPort -ne [string]$State.port){throw 'Coupon Redis loopback binding mismatch.'}
 $taskVolume=(& docker volume inspect --format '{{json .Labels}}' $State.volume | Out-String).Trim() | ConvertFrom-Json
 if($LASTEXITCODE -ne 0 -or $taskVolume.'campuslife.coupon.identity' -ne $State.identity){throw 'Coupon Redis volume ownership mismatch.'}
}
function Wait-CampusLifeCouponRedis($State) {
 Assert-CampusLifeCouponRedisOwner $State
 for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++){
  $taskPing=(& docker exec --env ('REDISCLI_AUTH='+$State.password) $State.container redis-cli --user $State.username --raw PING | Out-String).Trim()
  if($LASTEXITCODE -eq 0 -and $taskPing -eq 'PONG'){return}
  Start-Sleep -Milliseconds 250
 }
 throw 'Owned coupon Redis did not become ready.'
}
function Get-CampusLifeCouponRedisHash([string]$Value) {
 $taskHash=[Security.Cryptography.SHA256]::Create()
 try {return ([BitConverter]::ToString($taskHash.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-','').ToLowerInvariant()} finally {$taskHash.Dispose()}
}
function New-CampusLifeCouponRedis([string]$Folder,[string]$Identity,[ValidateSet('test','dev')][string]$Kind) {
 if($Identity -notmatch '^[a-f0-9]{32}$'){throw 'Invalid coupon fixture identity.'}
 & docker version --format '{{.Server.Version}}' | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Start the existing Docker Desktop Linux engine before creating coupon resources.'}
 New-Item -ItemType Directory -Path $Folder -Force | Out-Null
 $taskUser='coupon_'+$Identity;$taskPassword=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N');$taskAdminPassword=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N');$taskNamespace=$Kind+'_'+$Identity
 $taskConfig=Join-Path $Folder 'redis.conf';$taskAcl=Join-Path $Folder 'users.acl'
 @('bind 0.0.0.0','protected-mode yes','save ""','appendonly yes','appendfsync everysec','maxmemory 128mb','maxmemory-policy noeviction','dir /data','aclfile /usr/local/etc/redis/users.acl') | Set-Content -LiteralPath $taskConfig -Encoding ASCII
 @('user default off',('user '+$taskUser+' on #'+(Get-CampusLifeCouponRedisHash $taskPassword)+' ~coupon:'+$taskNamespace+':* +@read +@write +@scripting +@connection +info +ping -flushdb -flushall -config -shutdown -acl'),('user fixture_admin on #'+(Get-CampusLifeCouponRedisHash $taskAdminPassword)+' ~* +@all')) | Set-Content -LiteralPath $taskAcl -Encoding ASCII
 $taskListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$taskListener.Start();$taskPort=$taskListener.LocalEndpoint.Port;$taskListener.Stop()
 $taskVolume='campuslife-coupon-'+$Kind+'-'+$Identity
 & docker volume create --label ('campuslife.coupon.identity='+$Identity) --label ('campuslife.coupon.kind='+$Kind) $taskVolume | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Cannot create owned coupon Redis volume.'}
 $taskContainer=(& docker run -d --name $taskVolume --label ('campuslife.coupon.identity='+$Identity) --label ('campuslife.coupon.kind='+$Kind) -p ('127.0.0.1:'+$taskPort+':6379') --mount ('type=volume,source='+$taskVolume+',target=/data') --mount ('type=bind,source='+$taskConfig+',target=/usr/local/etc/redis/redis.conf,readonly') --mount ('type=bind,source='+$taskAcl+',target=/usr/local/etc/redis/users.acl,readonly') $CampusLifeCouponRedisImage redis-server /usr/local/etc/redis/redis.conf | Out-String).Trim()
 if($LASTEXITCODE -ne 0 -or $taskContainer -notmatch '^[a-f0-9]{64}$'){throw 'Cannot create coupon Redis container; labelled volume retained.'}
 $taskState=[pscustomobject]@{identity=$Identity;kind=$Kind;container=$taskContainer;volume=$taskVolume;image=$CampusLifeCouponRedisImage;port=$taskPort;namespace=$taskNamespace;username=$taskUser;password=$taskPassword;adminPassword=$taskAdminPassword}
 $taskState | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Folder 'manifest.json') -Encoding UTF8
 return $taskState
}
function Remove-CampusLifeCouponRedis($State) {
 Assert-CampusLifeCouponRedisOwner $State
 if($State.kind -ne 'test'){throw 'Dev coupon Redis is retained; use Stop instead.'}
 & docker rm -f $State.container | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Cannot remove owned coupon test container.'}
 & docker volume rm $State.volume | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Cannot remove owned coupon test volume.'}
}

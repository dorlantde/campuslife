# Local cache environment only; never reads database/JWT credentials or VS Code launch settings.
param([ValidateSet('Start','Stop','Status')][string]$Action='Status')
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskFolder=Join-Path $taskRoot '.local\rating-redis'
$taskManifest=Join-Path $taskFolder 'manifest.json'
$taskProperties=Join-Path $taskRoot '.local\rating-cache.properties'
$taskImage='redis:8.2.4@sha256:320e9ce6d5c940815a84d989f0e53e3a4d065c0d9132cd9e1222265e78eb6080'
$taskState=$null
if(Test-Path -LiteralPath $taskManifest){
    $taskState=Get-Content -LiteralPath $taskManifest -Raw | ConvertFrom-Json
    if($taskState.identity -notmatch '^[a-f0-9]{32}$' -or $taskState.container -notmatch '^[a-f0-9]{64}$' `
        -or $taskState.namespace -notmatch '^dev_[a-f0-9]{32}$') {throw 'Local cache manifest is invalid; refusing to control a container.'}
    $taskLabels=(& docker inspect --format '{{json .Config.Labels}}' $taskState.container | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or ($taskLabels | ConvertFrom-Json).'campuslife.local-rating' -ne $taskState.identity){
        throw 'Cannot verify the saved local Redis container. No other container will be changed.'
    }
}elseif($Action -ne 'Start'){
    Write-Host 'No project-owned local Redis has been created.'
    exit 0
}elseif(Test-Path -LiteralPath $taskProperties){
    throw 'A local rating-cache.properties already exists without this script manifest; preserve it and configure Redis manually.'
}
if($Action -eq 'Start'){
    & docker version --format '{{.Server.Version}}'
    if($LASTEXITCODE -ne 0){throw 'Start Docker Desktop Linux engine first.'}
    if(-not $taskState){
        New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
        $taskIdentity=[Guid]::NewGuid().ToString('N')
        $taskConfig=Join-Path $taskFolder 'redis.conf'
        @('bind 0.0.0.0','protected-mode no','save ""','appendonly no','maxmemory 128mb','maxmemory-policy allkeys-lru') |
            Set-Content -LiteralPath $taskConfig -Encoding ASCII
        $taskListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
        $taskListener.Start();$taskRedisPort=$taskListener.LocalEndpoint.Port;$taskListener.Stop()
        $taskContainer=(& docker run -d --name ('campuslife-rating-dev-'+$taskIdentity) --label ('campuslife.local-rating='+$taskIdentity) `
            -p ('127.0.0.1:'+$taskRedisPort+':6379') --mount ('type=bind,source='+$taskConfig+',target=/usr/local/etc/redis/redis.conf,readonly') `
            $taskImage redis-server /usr/local/etc/redis/redis.conf | Out-String).Trim()
        if($LASTEXITCODE -ne 0 -or $taskContainer -notmatch '^[a-f0-9]{64}$'){throw 'Cannot create local Redis.'}
        $taskState=[pscustomobject]@{identity=$taskIdentity;container=$taskContainer;namespace=('dev_'+[Guid]::NewGuid().ToString('N'));image=$taskImage}
        $taskState | ConvertTo-Json | Set-Content -LiteralPath $taskManifest -Encoding UTF8
    }else{
        & docker start $taskState.container | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Cannot start the verified local Redis.'}
    }
    $taskPortText=(& docker port $taskState.container '6379/tcp' | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or $taskPortText -notmatch '^127\.0\.0\.1:(\d+)$'){throw 'Unexpected port mapping; local settings not changed.'}
    $taskPort=$Matches[1]
    $taskReady=$false
    for($taskAttempt=0;$taskAttempt -lt 30;$taskAttempt++){
        $taskPing=(& docker exec $taskState.container redis-cli --raw PING | Out-String).Trim()
        if($LASTEXITCODE -eq 0 -and $taskPing -eq 'PONG'){$taskReady=$true;break}
        Start-Sleep -Milliseconds 100
    }
    if(-not $taskReady){throw 'Local Redis did not become ready.'}
    # Environment overrides keep isolated tests independent from this local opt-in file.
    @('campuslife.rating-cache.enabled=${RATING_CACHE_ENABLED:true}',
      ('campuslife.rating-cache.namespace=${RATING_CACHE_NAMESPACE:'+$taskState.namespace+'}'),
      'campuslife.rating-cache.host=${RATING_CACHE_HOST:127.0.0.1}',
      ('campuslife.rating-cache.port=${RATING_CACHE_PORT:'+$taskPort+'}')) |
        Set-Content -LiteralPath $taskProperties -Encoding ASCII
    Write-Host "PASS: local Redis 8.2.4 PONG at 127.0.0.1:$taskPort. Cache opt-in saved under .local."
    Write-Host 'Restart the application with the existing MySQL launch configuration. No database/JWT credentials were changed.'
}elseif($Action -eq 'Stop'){
    & docker stop --time 2 $taskState.container | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Cannot stop the verified local Redis.'}
    @('campuslife.rating-cache.enabled=${RATING_CACHE_ENABLED:false}') |
        Set-Content -LiteralPath $taskProperties -Encoding ASCII
    Write-Host 'PASS: local Redis stopped and local cache opt-in disabled. Restart the application to use the original query path.'
}else{
    & docker inspect --format '{{.State.Status}}' $taskState.container
    if($LASTEXITCODE -ne 0){throw 'Cannot read the verified local Redis status.'}
    & docker port $taskState.container '6379/tcp'
    if($LASTEXITCODE -ne 0){throw 'Cannot read local Redis port.'}
}

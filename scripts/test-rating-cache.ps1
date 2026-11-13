# Only creates/removes a labelled, randomly named Redis container owned by this invocation.
param([ValidateSet('All','Cache','Upgrade','Regression','Acceptance')][string]$Phase='All',[string]$AdditionalCases='',[string]$UnitCases='')
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskIdentity=[Guid]::NewGuid().ToString('N')
$taskParent=Join-Path $taskRoot '.cache\redis-it'
$taskFolder=Join-Path $taskParent $taskIdentity
$taskContainer=$null
$taskSaved=@{}
foreach($taskName in @('MAVEN_ARGS','CAMPUSLIFE_CACHE_IT','CAMPUSLIFE_CACHE_CONTAINER_ID','CAMPUSLIFE_CACHE_FIXTURE_ID','CAMPUSLIFE_CACHE_OUTPUT','RATING_CACHE_ENABLED','RATING_CACHE_NAMESPACE','RATING_CACHE_HOST','RATING_CACHE_PORT')){
    $taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')
}
$taskImage='redis:8.2.4@sha256:320e9ce6d5c940815a84d989f0e53e3a4d065c0d9132cd9e1222265e78eb6080'
$taskSuccess=$false
Push-Location -LiteralPath $taskRoot
try {
    & docker version --format '{{.Server.Version}}'
    if($LASTEXITCODE -ne 0){throw 'Docker Linux engine is unavailable. Start Docker Desktop before this check.'}
    New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
    $taskConfig=Join-Path $taskFolder 'redis.conf'
    # Docker forwards the host loopback through a bridge address, which Redis protected-mode rejects.
    # The published host port is explicitly 127.0.0.1 only; this dedicated fixture contains disposable aggregates.
    @('bind 0.0.0.0','protected-mode no','save ""','appendonly no','maxmemory 128mb','maxmemory-policy allkeys-lru') |
        Set-Content -LiteralPath $taskConfig -Encoding ASCII
    # Pick a free port once, then bind it explicitly so stop/start preserves the endpoint.
    $taskListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $taskListener.Start();$taskRedisPort=$taskListener.LocalEndpoint.Port;$taskListener.Stop()
    $taskContainer=(& docker run -d --name ('campuslife-rating-it-'+$taskIdentity) --label ('campuslife.fixture='+$taskIdentity) `
        -p ('127.0.0.1:'+$taskRedisPort+':6379') --mount ('type=bind,source='+$taskConfig+',target=/usr/local/etc/redis/redis.conf,readonly') `
        $taskImage redis-server /usr/local/etc/redis/redis.conf | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or $taskContainer -notmatch '^[a-f0-9]{64}$'){throw 'Cannot create the isolated Redis container.'}
    $taskPortText=(& docker port $taskContainer '6379/tcp' | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or $taskPortText -notmatch '^127\.0\.0\.1:(\d+)$'){throw 'Unexpected Redis port mapping.'}
    $env:RATING_CACHE_PORT=$Matches[1]
    $env:RATING_CACHE_HOST='127.0.0.1'
    $env:RATING_CACHE_NAMESPACE='it_'+$taskIdentity
    $env:RATING_CACHE_ENABLED='false'
    $env:CAMPUSLIFE_CACHE_IT='isolated-fixture'
    $env:CAMPUSLIFE_CACHE_CONTAINER_ID=$taskContainer
    $env:CAMPUSLIFE_CACHE_FIXTURE_ID=$taskIdentity
    $env:CAMPUSLIFE_CACHE_OUTPUT=$taskFolder
    $taskReady=$false
    for($taskAttempt=0;$taskAttempt -lt 30;$taskAttempt++){
        $taskPing=(& docker exec $taskContainer redis-cli --raw PING | Out-String).Trim()
        if($LASTEXITCODE -eq 0 -and $taskPing -eq 'PONG'){$taskReady=$true;break}
        Start-Sleep -Milliseconds 100
    }
    if(-not $taskReady){throw 'Redis did not become ready.'}
    Write-Host "Redis 8.2.4 fixture ready on loopback port $env:RATING_CACHE_PORT."
    $taskPhases=if($Phase -eq 'All'){@('Cache','Upgrade','Regression')}else{@($Phase)}
    foreach($taskCheck in $taskPhases){
        $env:MAVEN_ARGS=switch($taskCheck){
            'Cache' {'-Dit.test=RatingCacheIntegration'}
            'Upgrade' {'-Dit.test=RatingRevisionUpgradeCheck'}
            'Regression' {'-Dit.test=OrderReviewsMysqlIT,AdminReviewHideMysqlIT,PublicShopCatalogMysqlIT,PublicShopReviewsMysqlIT,BookingJourneyMysqlIT,PrintJourneyMysqlIT,OpenApiMysqlIT,MysqlIntegrationIT'}
            'Acceptance' {'-Dit.test=RatingCacheAcceptance'}
        }
        if($taskCheck -eq 'Cache' -and $AdditionalCases){$env:MAVEN_ARGS+=','+$AdditionalCases}
        if($UnitCases){$env:MAVEN_ARGS+=' -Dtest='+$UnitCases}
        $taskLog=Join-Path $taskFolder ($taskCheck.ToLowerInvariant()+'.log')
        Write-Host "$taskCheck checks; log: $taskLog"
        & (Join-Path $PSScriptRoot 'test-mysql.ps1') -StartupTimeoutSeconds 90 *> $taskLog
        if($LASTEXITCODE -ne 0){throw "$taskCheck failed. Inspect $taskLog"}
        if($taskCheck -eq 'Acceptance' -and -not(Test-Path -LiteralPath (Join-Path $taskFolder 'acceptance.json'))){throw 'Cache acceptance evidence is missing.'}
    }
    $taskSuccess=$true
    Write-Host 'PASS: selected cache checks, mysql JAR smoke and owned MySQL cleanup.'
} finally {
    if($taskContainer -match '^[a-f0-9]{64}$'){
        $taskLabels=(& docker inspect --format '{{json .Config.Labels}}' $taskContainer | Out-String).Trim()
        if($LASTEXITCODE -ne 0 -or ($taskLabels | ConvertFrom-Json).'campuslife.fixture' -ne $taskIdentity){throw 'Refusing to remove a container without the exact owned label.'}
        & docker rm -f $taskContainer | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Owned Redis container cleanup failed.'}
    }
    foreach($taskName in $taskSaved.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}
    Pop-Location
}
if($taskSuccess){
    [pscustomobject]@{completedAt=[DateTime]::UtcNow.ToString('o');phases=@($taskPhases);image=$taskImage;redisRemoved=$true} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskFolder 'completed.json') -Encoding UTF8
    Write-Host "PASS: owned Redis removed. Logs retained: $taskFolder"
}

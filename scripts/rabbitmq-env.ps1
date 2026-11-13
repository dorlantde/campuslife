# Shared only by owned local/test RabbitMQ scripts. Never reads application DB/JWT credentials.
$CampusLifeRabbitImage='rabbitmq:4.3.6-management@sha256:54cb013d76cd1fd6c0a0708f34062ad61c452bb6f016b434c6c33f1a765d2b58'
function Assert-CampusLifeRabbitOwner($State) {
    if($State.identity -notmatch '^[a-f0-9]{32}$' -or $State.container -notmatch '^[a-f0-9]{64}$' -or $State.volume -ne ('campuslife-mq-'+$State.kind+'-'+$State.identity) -or $State.kind -notin @('dev','test')){throw 'Invalid RabbitMQ ownership manifest.'}
    $taskLabels=(& docker inspect --format '{{json .Config.Labels}}' $State.container | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or ($taskLabels | ConvertFrom-Json).'campuslife.rabbit.identity' -ne $State.identity -or ($taskLabels | ConvertFrom-Json).'campuslife.rabbit.kind' -ne $State.kind){throw 'RabbitMQ container ownership mismatch.'}
    $taskBindings=(& docker inspect --format '{{json .HostConfig.PortBindings}}' $State.container | Out-String).Trim() | ConvertFrom-Json
    foreach($taskPortCheck in @(@('5672/tcp',[string]$State.port),@('15672/tcp',[string]$State.managementPort))){
        $taskBinding=$taskBindings.($taskPortCheck[0])
        if(@($taskBinding).Count -ne 1 -or $taskBinding[0].HostIp -ne '127.0.0.1' -or $taskBinding[0].HostPort -ne $taskPortCheck[1]){throw 'RabbitMQ loopback port mapping mismatch.'}
    }
    $taskVolumeLabels=(& docker volume inspect --format '{{json .Labels}}' $State.volume | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or ($taskVolumeLabels | ConvertFrom-Json).'campuslife.rabbit.identity' -ne $State.identity){throw 'RabbitMQ volume ownership mismatch.'}
}
function Wait-CampusLifeRabbit($State) {
    Assert-CampusLifeRabbitOwner $State
    $taskDeadline=[DateTime]::UtcNow.AddSeconds(90)
    $taskAuth=[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($State.username+':'+$State.password))
    while([DateTime]::UtcNow -lt $taskDeadline){
        try {
            $taskRequest=[Net.HttpWebRequest]::Create('http://127.0.0.1:'+$State.managementPort+'/api/overview')
            $taskRequest.Headers.Add('Authorization','Basic '+$taskAuth);$taskRequest.Timeout=2000
            $taskResponse=$taskRequest.GetResponse();$taskResponse.Close();return
        }catch {Start-Sleep -Milliseconds 500}
    }
    throw 'Owned RabbitMQ did not become ready within 90 seconds.'
}
function New-CampusLifeRabbit([string]$Folder,[string]$Identity,[ValidateSet('dev','test')][string]$Kind) {
    if($Identity -notmatch '^[a-f0-9]{32}$'){throw 'Invalid new fixture identity.'}
    New-Item -ItemType Directory -Path $Folder -Force | Out-Null
    $taskUsername='campuslife_'+$Identity;$taskPassword=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N');$taskVhost='campuslife_'+$Identity
    $taskEnv=Join-Path $Folder 'rabbit.env'
    @(('RABBITMQ_DEFAULT_USER='+$taskUsername),('RABBITMQ_DEFAULT_PASS='+$taskPassword),('RABBITMQ_DEFAULT_VHOST='+$taskVhost)) | Set-Content -LiteralPath $taskEnv -Encoding ASCII
    $taskPorts=@();for($taskN=0;$taskN -lt 2;$taskN++){$taskListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$taskListener.Start();$taskPorts+=@($taskListener.LocalEndpoint.Port);$taskListener.Stop()}
    $taskVolume='campuslife-mq-'+$Kind+'-'+$Identity
    & docker volume create --label ('campuslife.rabbit.identity='+$Identity) --label ('campuslife.rabbit.kind='+$Kind) $taskVolume | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Cannot create the owned RabbitMQ volume.'}
    $taskContainer=(& docker run -d --name ('campuslife-mq-'+$Kind+'-'+$Identity) --hostname ('campuslife-'+$Identity) --label ('campuslife.rabbit.identity='+$Identity) --label ('campuslife.rabbit.kind='+$Kind) --env-file $taskEnv -p ('127.0.0.1:'+$taskPorts[0]+':5672') -p ('127.0.0.1:'+$taskPorts[1]+':15672') --mount ('type=volume,source='+$taskVolume+',target=/var/lib/rabbitmq') $CampusLifeRabbitImage | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or $taskContainer -notmatch '^[a-f0-9]{64}$'){throw 'Cannot create the owned RabbitMQ container; the labelled volume was retained.'}
    $taskState=[pscustomobject]@{identity=$Identity;kind=$Kind;container=$taskContainer;volume=$taskVolume;image=$CampusLifeRabbitImage;host='127.0.0.1';port=$taskPorts[0];managementPort=$taskPorts[1];username=$taskUsername;password=$taskPassword;vhost=$taskVhost}
    $taskState | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Folder 'manifest.json') -Encoding UTF8
    return $taskState
}
function Remove-CampusLifeRabbit($State) {
    Assert-CampusLifeRabbitOwner $State
    & docker rm -f $State.container | Out-Null;if($LASTEXITCODE -ne 0){throw 'Cannot remove the verified owned RabbitMQ container.'}
    # Recheck the volume immediately before removal; never use a broad prune.
    $taskLabels=(& docker volume inspect --format '{{json .Labels}}' $State.volume | Out-String).Trim()
    if($LASTEXITCODE -ne 0 -or ($taskLabels | ConvertFrom-Json).'campuslife.rabbit.identity' -ne $State.identity){throw 'Refusing to remove an unverified RabbitMQ volume.'}
    & docker volume rm $State.volume | Out-Null;if($LASTEXITCODE -ne 0){throw 'Cannot remove the verified owned RabbitMQ volume.'}
}

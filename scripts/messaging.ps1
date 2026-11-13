param([ValidateSet('Start','Stop','Status')][string]$Action='Status')
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'rabbitmq-env.ps1')
$taskFolder=Join-Path $taskRoot '.local\rabbitmq';$taskManifest=Join-Path $taskFolder 'manifest.json';$taskProperties=Join-Path $taskRoot '.local\messaging.properties'
$taskState=$null
if(Test-Path -LiteralPath $taskManifest){$taskState=Get-Content -LiteralPath $taskManifest -Raw | ConvertFrom-Json;if($taskState.kind -ne 'dev'){throw 'Local manifest must identify a dev container.'};Assert-CampusLifeRabbitOwner $taskState}
elseif($Action -ne 'Start'){Write-Host 'No owned local RabbitMQ exists.';exit 0}
elseif(Test-Path -LiteralPath $taskProperties){throw 'Preserve existing messaging.properties; no script-owned manifest was found.'}
if($Action -eq 'Start'){
    & docker version --format '{{.Server.Version}}';if($LASTEXITCODE -ne 0){throw 'Start Docker Desktop Linux engine first.'}
    if(-not $taskState){$taskState=New-CampusLifeRabbit $taskFolder ([Guid]::NewGuid().ToString('N')) 'dev'}else{& docker start $taskState.container | Out-Null;if($LASTEXITCODE -ne 0){throw 'Cannot start the verified RabbitMQ container.'}}
    Wait-CampusLifeRabbit $taskState
    @('campuslife.messaging.enabled=${MESSAGING_ENABLED:true}',('campuslife.messaging.host=${MESSAGING_HOST:127.0.0.1}'),('campuslife.messaging.port=${MESSAGING_PORT:'+$taskState.port+'}'),('campuslife.messaging.username=${MESSAGING_USERNAME:'+$taskState.username+'}'),('campuslife.messaging.password=${MESSAGING_PASSWORD:'+$taskState.password+'}'),('campuslife.messaging.vhost=${MESSAGING_VHOST:'+$taskState.vhost+'}')) | Set-Content -LiteralPath $taskProperties -Encoding ASCII
    Write-Host ('PASS: owned RabbitMQ ready. AMQP 127.0.0.1:'+$taskState.port+'; management http://127.0.0.1:'+$taskState.managementPort)
    Write-Host 'Local transport opt-in saved. Restart the backend; this batch does not create student notifications yet.'
}elseif($Action -eq 'Stop'){
    & docker stop --time 5 $taskState.container | Out-Null;if($LASTEXITCODE -ne 0){throw 'Cannot stop the verified RabbitMQ container.'}
    'campuslife.messaging.enabled=${MESSAGING_ENABLED:false}' | Set-Content -LiteralPath $taskProperties -Encoding ASCII
    Write-Host 'PASS: owned RabbitMQ stopped; local transport opt-in disabled. Messages and volume retained. Restart backend to apply.'
}else{
    & docker inspect --format '{{.State.Status}}' $taskState.container
    & docker port $taskState.container
}

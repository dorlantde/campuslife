# A randomly labelled RabbitMQ container/volume and the existing isolated MySQL runner only.
param([string]$Cases='NotificationMysqlIT,NotificationRabbitIT,OutboxMysqlIT,OutboxRabbitIT,MysqlIntegrationIT,OpenApiMysqlIT',[string]$UnitCases='OutboxPublisherTest,RabbitOutboxTransportTest,NotificationListenerTest')
$ErrorActionPreference='Stop';$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'rabbitmq-env.ps1')
$taskIdentity=[Guid]::NewGuid().ToString('N');$taskFolder=Join-Path $taskRoot ('.cache\rabbit-it\'+$taskIdentity);$taskState=$null;$taskSuccess=$false;$taskSaved=@{}
$taskNames=@('MAVEN_ARGS','MESSAGING_ENABLED','MESSAGING_HOST','MESSAGING_PORT','MESSAGING_USERNAME','MESSAGING_PASSWORD','MESSAGING_VHOST','MESSAGING_SCHEDULING_ENABLED','MESSAGING_CONSUMER_ENABLED','CAMPUSLIFE_MQ_IT','CAMPUSLIFE_MQ_CONTAINER','CAMPUSLIFE_MQ_IDENTITY','CAMPUSLIFE_MQ_MANAGEMENT_PORT','RATING_CACHE_ENABLED')
foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
    $taskState=New-CampusLifeRabbit $taskFolder $taskIdentity 'test';Wait-CampusLifeRabbit $taskState
    $env:MESSAGING_ENABLED='true';$env:MESSAGING_HOST='127.0.0.1';$env:MESSAGING_PORT=[string]$taskState.port;$env:MESSAGING_USERNAME=$taskState.username;$env:MESSAGING_PASSWORD=$taskState.password;$env:MESSAGING_VHOST=$taskState.vhost;$env:MESSAGING_SCHEDULING_ENABLED='false';$env:MESSAGING_CONSUMER_ENABLED='false';$env:RATING_CACHE_ENABLED='false'
    $env:CAMPUSLIFE_MQ_IT='owned-isolated-fixture';$env:CAMPUSLIFE_MQ_CONTAINER=$taskState.container;$env:CAMPUSLIFE_MQ_IDENTITY=$taskIdentity;$env:CAMPUSLIFE_MQ_MANAGEMENT_PORT=[string]$taskState.managementPort
    $env:MAVEN_ARGS='-Dit.test='+$Cases;if($UnitCases){$env:MAVEN_ARGS+=' -Dtest='+$UnitCases}
    Write-Host ('Owned RabbitMQ fixture ready on loopback '+$taskState.port+'; only selected checks will run.')
    & (Join-Path $PSScriptRoot 'test-mysql.ps1') -StartupTimeoutSeconds 90
    if($LASTEXITCODE -ne 0){throw 'Messaging checks failed; inspect the test reports.'}
    $taskSuccess=$true
}finally{
    if($taskState){Remove-CampusLifeRabbit $taskState}
    foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}
}
if($taskSuccess){[pscustomobject]@{completedAt=[DateTime]::UtcNow.ToString('o');cases=$Cases;image=$CampusLifeRabbitImage;containerRemoved=$true;volumeRemoved=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskFolder 'completed.json') -Encoding UTF8;Write-Host ('PASS: owned RabbitMQ container/volume and MySQL cleaned. Evidence: '+$taskFolder)}

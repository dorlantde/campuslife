param()
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'seaweedfs-env.ps1')
$taskIdentity=[Guid]::NewGuid().ToString('N');$taskFolder=Join-Path $taskRoot ('.cache/s3-it/'+$taskIdentity)
$taskState=$null;$taskSaved=@{};$taskExit=1
$taskNames=@('JAVA_HOME','PATH','MAVEN_ARGS','CAMPUSLIFE_S3_IT','S3_PROBE_NAME','S3_PROBE_IDENTITY','S3_PROBE_ENDPOINT','S3_PROBE_BUCKET','S3_PROBE_NAMESPACE','S3_PROBE_ADMIN_KEY','S3_PROBE_ADMIN_SECRET','S3_PROBE_APP_KEY','S3_PROBE_APP_SECRET','S3_PROBE_OUTPUT')
foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
Push-Location -LiteralPath $taskRoot
try{
 $taskState=New-CampusLifeS3 $taskFolder $taskIdentity
 $env:CAMPUSLIFE_S3_IT='owned-isolated-fixture';$env:S3_PROBE_NAME=$taskState.name;$env:S3_PROBE_IDENTITY=$taskIdentity;$env:S3_PROBE_ENDPOINT='http://127.0.0.1:'+$taskState.port;$env:S3_PROBE_BUCKET=$taskState.bucket;$env:S3_PROBE_NAMESPACE=$taskState.namespace
 $env:S3_PROBE_ADMIN_KEY=$taskState.adminKey;$env:S3_PROBE_ADMIN_SECRET=$taskState.adminSecret;$env:S3_PROBE_APP_KEY=$taskState.appKey;$env:S3_PROBE_APP_SECRET=$taskState.appSecret;$env:S3_PROBE_OUTPUT=$taskFolder
 $env:JAVA_HOME=(Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java21') -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'bin/java.exe')} | Sort-Object Name -Descending | Select-Object -First 1).FullName
 if(-not $env:JAVA_HOME){throw 'Project Java21 toolchain is required'}
 $env:PATH=(Join-Path $env:JAVA_HOME 'bin')+[IO.Path]::PathSeparator+$taskSaved.PATH
 [Environment]::SetEnvironmentVariable('MAVEN_ARGS',$null,'Process')
 Write-Host 'Owned private S3 fixture ready; only the isolated SDK probe will run.'
 & mvn -B -Ps3-probe '-Dtest=S3CompatibilityProbeTest' package
 $taskExit=$LASTEXITCODE
 if($taskExit -ne 0){throw 'Isolated S3 probe failed; evidence retained, owned resources will be cleaned'}
}finally{
 try{if($taskState){Remove-CampusLifeS3 $taskState}}
 finally{
  foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}
  Pop-Location
 }
}
if($taskExit -eq 0){
 [pscustomobject]@{task='M04.2-A';image=$CampusLifeS3Image;sdk='2.57.8';java='21';resourcesRemoved=$true;completedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content (Join-Path $taskFolder 'completed.json') -Encoding UTF8
 Write-Host ('PASS: isolated S3 compatibility; container/volume/network cleaned. Evidence: '+$taskFolder)
}
exit $taskExit

# Owns only explicitly labelled S3 test resources. No development credentials or file roots are read.
$CampusLifeS3Image='chrislusf/seaweedfs:4.51@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8'
function Assert-CampusLifeS3Resource([string]$Type,[string]$Name,[string]$Identity) {
 if($Identity -notmatch '^[a-f0-9]{32}$' -or $Name -ne ('campuslife-s3-test-'+$Identity)){throw 'Invalid S3 ownership identity.'}
 $taskFormat=if($Type -eq 'container'){'{{json .Config.Labels}}'}else{'{{json .Labels}}'}
 $taskJson=if($Type -eq 'container'){& docker inspect --format $taskFormat $Name}else{& docker $Type inspect --format $taskFormat $Name}
 if($LASTEXITCODE -ne 0){throw ('Cannot inspect owned S3 '+$Type)}
 $taskLabels=($taskJson | Out-String).Trim() | ConvertFrom-Json
 if($taskLabels.'campuslife.s3.identity' -ne $Identity -or $taskLabels.'campuslife.s3.kind' -ne 'test'){throw ('S3 '+$Type+' ownership mismatch')}
}
function Assert-CampusLifeS3($State) {
 foreach($taskType in @('container','volume','network')){Assert-CampusLifeS3Resource $taskType $State.name $State.identity}
 $taskBindings=(& docker inspect --format '{{json .HostConfig.PortBindings}}' $State.name | Out-String).Trim() | ConvertFrom-Json
 if($LASTEXITCODE -ne 0 -or @($taskBindings.PSObject.Properties).Count -ne 1){throw 'Unexpected S3 published ports'}
 $taskBinding=$taskBindings.'8333/tcp'
 if(@($taskBinding).Count -ne 1 -or $taskBinding[0].HostIp -ne '127.0.0.1' -or $taskBinding[0].HostPort -ne [string]$State.port){throw 'S3 endpoint must be the owned loopback binding'}
 $taskNetwork=(& docker network inspect --format '{{json .}}' $State.name | Out-String).Trim() | ConvertFrom-Json
 if($LASTEXITCODE -ne 0 -or $taskNetwork.Driver -ne 'bridge' -or $taskNetwork.Internal -or @($taskNetwork.Containers.PSObject.Properties).Count -ne 1){throw 'Owned S3 must be alone on its dedicated bridge'}
 $taskEffective=(& docker inspect --format '{{json .NetworkSettings.Ports}}' $State.name | Out-String).Trim() | ConvertFrom-Json
 $taskEffectiveBinding=$taskEffective.'8333/tcp'
 if($LASTEXITCODE -ne 0 -or @($taskEffectiveBinding).Count -ne 1 -or $taskEffectiveBinding[0].HostIp -ne '127.0.0.1' -or $taskEffectiveBinding[0].HostPort -ne [string]$State.port){throw 'S3 loopback port mapping is not effective'}
 $taskActualArgs=(& docker inspect --format '{{json .Args}}' $State.name | Out-String).Trim() | ConvertFrom-Json
 foreach($taskRequired in @('-ip=127.0.0.1','-ip.bind=0.0.0.0','-s3.config=/etc/campuslife-s3.json','-s3.autoCreateBucket=false','-admin.ui=false','-webdav=false')){if($taskActualArgs -notcontains $taskRequired){throw 'S3 flags were split or lost; refusing to run without explicit authentication/configuration'}}
}
function New-CampusLifeS3([string]$Folder,[string]$Identity) {
 if($Identity -notmatch '^[a-f0-9]{32}$'){throw 'Invalid fixture identity'}
 & docker version --format '{{.Server.Version}}' | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Start the installed Docker Desktop Linux engine first'}
 New-Item -ItemType Directory -Path $Folder -Force | Out-Null
 $taskName='campuslife-s3-test-'+$Identity
 $taskListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$taskListener.Start();$taskPort=$taskListener.LocalEndpoint.Port;$taskListener.Stop()
 $taskBucket='campuslife-'+$Identity;$taskNamespace=[Guid]::NewGuid().ToString()
 $taskState=[pscustomobject]@{identity=$Identity;name=$taskName;image=$CampusLifeS3Image;port=$taskPort;bucket=$taskBucket;namespace=$taskNamespace;adminKey=('admin'+$Identity);adminSecret=([Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N'));appKey=('app'+$Identity);appSecret=([Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N'));folder=$Folder}
 $taskConfig=[ordered]@{identities=@(@{name='fixture_admin';credentials=@(@{accessKey=$taskState.adminKey;secretKey=$taskState.adminSecret});actions=@('Admin','Read','Write','List')},@{name='fixture_app';credentials=@(@{accessKey=$taskState.appKey;secretKey=$taskState.appSecret});actions=@(('Read:'+$taskBucket+'/'+$taskNamespace+'/objects/*'),('Write:'+$taskBucket+'/'+$taskNamespace+'/objects/*'),('Read:'+$taskBucket+'/_storage-id*'))})}
 $taskConfig | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Folder 's3.json') -Encoding ASCII
 $taskState | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Folder 'manifest.json') -Encoding UTF8
 $taskNetwork=$false;$taskVolume=$false;$taskContainer=$false
 try {
  & docker network create --driver bridge --label ('campuslife.s3.identity='+$Identity) --label 'campuslife.s3.kind=test' $taskName | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Cannot create owned S3 network'};$taskNetwork=$true
  & docker volume create --label ('campuslife.s3.identity='+$Identity) --label 'campuslife.s3.kind=test' $taskName | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Cannot create owned S3 volume'};$taskVolume=$true
  $taskMiniArgs=@('mini','-dir=/data','-ip=127.0.0.1','-ip.bind=0.0.0.0','-s3.config=/etc/campuslife-s3.json','-s3.autoCreateBucket=false','-webdav=false','-admin.ui=false','-s3.port.iceberg=0','-s3.port.lance=0','-volume.max=16')
  & docker run -d --name $taskName --network $taskName --label ('campuslife.s3.identity='+$Identity) --label 'campuslife.s3.kind=test' --memory 768m --cpus 2 -p ('127.0.0.1:'+$taskPort+':8333') --mount ('type=volume,source='+$taskName+',target=/data') --mount ('type=bind,source='+(Join-Path $Folder 's3.json')+',target=/etc/campuslife-s3.json,readonly') --entrypoint weed $CampusLifeS3Image @taskMiniArgs | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Cannot start owned S3 fixture'};$taskContainer=$true
  Assert-CampusLifeS3 $taskState
  return $taskState
 }catch{
  # A failed docker run can leave a created/stopped container even when it returned no ID.
  if(-not $taskContainer){$taskLeft=@(& docker ps -aq --filter ('name=^'+$taskName+'$') --filter ('label=campuslife.s3.identity='+$Identity) | Where-Object {$_});$taskContainer=($taskLeft.Count -eq 1)}
  if($taskContainer){Assert-CampusLifeS3Resource 'container' $taskName $Identity;& docker rm -f $taskName | Out-Null}
  if($taskVolume){Assert-CampusLifeS3Resource 'volume' $taskName $Identity;& docker volume rm $taskName | Out-Null}
  if($taskNetwork){Assert-CampusLifeS3Resource 'network' $taskName $Identity;& docker network rm $taskName | Out-Null}
  throw
 }
}
function Remove-CampusLifeS3($State) {
 # Cleanup must also handle a container whose launch flags were rejected by the stricter runtime guard.
 foreach($taskType in @('container','volume','network')){Assert-CampusLifeS3Resource $taskType $State.name $State.identity}
 & docker logs $State.name *> (Join-Path $State.folder 'server.log')
 & docker rm -f $State.name | Out-Null;if($LASTEXITCODE -ne 0){throw 'Cannot remove owned S3 container'}
 & docker volume rm $State.name | Out-Null;if($LASTEXITCODE -ne 0){throw 'Cannot remove owned S3 volume'}
 & docker network rm $State.name | Out-Null;if($LASTEXITCODE -ne 0){throw 'Cannot remove owned S3 network'}
}

param(
 [Parameter(Mandatory=$true)][ValidateSet('Build','Init','Start','Status','Stop','Admin','DestroyTest','Coupon','Message','StorageInit','Observe')][string]$Action,
 [string]$Instance,
 [ValidateRange(1024,65535)][int]$Port=18080,
 [switch]$Docs,
 [ValidateSet('base','components','showcase')][string]$Mode='base',
 [switch]$DemoMoney,
 [switch]$Observe,
 [ValidateSet('standard','compact4g')][string]$ResourceProfile='standard',
 [string]$Metric,
 [string]$Operation,
 [string]$Id,
 [string]$Version,
 [switch]$AllInstancesUpgraded
)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskSavedJava=$env:JAVA_HOME;$taskSavedPath=$env:PATH
try {
 if($Action -eq 'Build'){
  $taskJdk=Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java21') -Directory -ErrorAction SilentlyContinue |
   Where-Object {Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe')} | Sort-Object Name -Descending | Select-Object -First 1
  if($taskJdk){$env:JAVA_HOME=$taskJdk.FullName;$env:PATH=(Join-Path $taskJdk.FullName 'bin')+[IO.Path]::PathSeparator+$taskSavedPath}
 }
 $taskArgs=@((Join-Path $PSScriptRoot 'deploy_base.py'),$Action.ToLowerInvariant())
 if($ResourceProfile -ne 'standard'){$taskArgs+=@('--resource-profile',$ResourceProfile)}
 if($Instance){$taskArgs+=@('--instance',$Instance)}
 if($Action -eq 'Init'){$taskArgs+=@('--port',[string]$Port,'--mode',$Mode);if($Docs){$taskArgs+='--docs'};if($DemoMoney){$taskArgs+='--demo-money'};if($Observe){$taskArgs+='--observe'}}
 if($Action -eq 'Observe'){$taskArgs+=@('--operation',$Operation);if($Metric){$taskArgs+=@('--metric',$Metric)}}
 if($Action -in @('Coupon','Message')){
  $taskArgs+=@('--operation',$Operation,'--id',$Id)
  if($Version){$taskArgs+=@('--version',$Version)}
  if($AllInstancesUpgraded){$taskArgs+='--all-instances-upgraded'}
 }
 & python @taskArgs
 $taskResult=$LASTEXITCODE
}finally{$env:JAVA_HOME=$taskSavedJava;$env:PATH=$taskSavedPath}
exit $taskResult

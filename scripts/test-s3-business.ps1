# Owned S3 plus the existing isolated MySQL fixture. No developer database/files/credentials are used.
param(
 [string]$Cases='S3FileChainMysqlIT,FileUploadMysqlIT,FileDownloadMysqlIT,PrintOrdersMysqlIT,PrintPaymentsMysqlIT,MerchantPrintConfirmationMysqlIT,MerchantPrintCancellationMysqlIT,MerchantPrintDownloadMysqlIT,MerchantPrintFulfillmentMysqlIT,StudentPrintPickupMysqlIT,AdminPrintExceptionMysqlIT,CouponOrdersMysqlIT,MysqlIntegrationIT,OpenApiMysqlIT',
 [string]$UnitCases='FilePolicyTest,S3StoragePolicyTest,FileCleanupWorkerTest,PrintOrderPolicyTest',
 [ValidateSet('M04.2-B','M04.2-C')][string]$Task='M04.2-B'
)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'seaweedfs-env.ps1')
$taskIdentity=[Guid]::NewGuid().ToString('N');$taskFolder=Join-Path $taskRoot ('.cache/s3-it/'+$taskIdentity)
$taskState=$null;$taskSaved=@{};$taskSuccess=$false
$taskBegan=[DateTime]::UtcNow
$taskNames=@('MAVEN_ARGS','SERVER_ADDRESS','CAMPUSLIFE_S3_BUSINESS_IT','S3_PROBE_NAME','S3_PROBE_IDENTITY','S3_PROBE_ENDPOINT','S3_PROBE_BUCKET','S3_PROBE_NAMESPACE','S3_PROBE_ADMIN_KEY','S3_PROBE_ADMIN_SECRET','S3_PROBE_APP_KEY','S3_PROBE_APP_SECRET')
foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
 $env:SERVER_ADDRESS='127.0.0.1'
 $taskState=New-CampusLifeS3 $taskFolder $taskIdentity
 $env:CAMPUSLIFE_S3_BUSINESS_IT='owned-isolated-fixture';$env:S3_PROBE_NAME=$taskState.name;$env:S3_PROBE_IDENTITY=$taskIdentity
 $env:S3_PROBE_ENDPOINT='http://127.0.0.1:'+$taskState.port;$env:S3_PROBE_BUCKET=$taskState.bucket;$env:S3_PROBE_NAMESPACE=$taskState.namespace
 $env:S3_PROBE_ADMIN_KEY=$taskState.adminKey;$env:S3_PROBE_ADMIN_SECRET=$taskState.adminSecret;$env:S3_PROBE_APP_KEY=$taskState.appKey;$env:S3_PROBE_APP_SECRET=$taskState.appSecret
 $env:MAVEN_ARGS='-Dit.test='+$Cases+' -Dtest='+$UnitCases
 Write-Host 'Owned S3 and isolated MySQL business regression; original paths stay LOCAL in their own contexts.'
 & (Join-Path $PSScriptRoot 'test-mysql.ps1') -StartupTimeoutSeconds 90
 if($LASTEXITCODE -ne 0){throw 'S3 business regression failed; reports retained'}
 $taskSuites=@()
 foreach($taskSelection in @(@{names=$Cases;folder='target/failsafe-reports'},@{names=$UnitCases;folder='target/surefire-reports'})){
  foreach($taskCase in ($taskSelection.names -split ',' | Where-Object {$_})){
   if($taskCase -notmatch '^[A-Za-z][A-Za-z0-9]+$'){throw 'Select explicit test class names only'}
   $taskReports=@(Get-ChildItem -LiteralPath (Join-Path $taskRoot $taskSelection.folder) -Filter ('TEST-*.'+$taskCase+'.xml') | Where-Object {$_.LastWriteTimeUtc -ge $taskBegan})
   if($taskReports.Count -ne 1){throw ('Missing fresh test evidence for '+$taskCase)}
   $taskSuite=([xml](Get-Content -LiteralPath $taskReports[0].FullName -Raw)).testsuite
   if([int]$taskSuite.tests -lt 1 -or [int]$taskSuite.failures -ne 0 -or [int]$taskSuite.errors -ne 0 -or [int]$taskSuite.skipped -ne 0){throw ('Selected suite did not pass: '+$taskCase)}
   $taskSuites+=[pscustomobject]@{name=[string]$taskSuite.name;tests=[int]$taskSuite.tests;failures=[int]$taskSuite.failures;errors=[int]$taskSuite.errors;skipped=[int]$taskSuite.skipped}
  }
 }
 $taskSuites | ConvertTo-Json -Depth 3 | Set-Content (Join-Path $taskFolder 'business-suites.json') -Encoding UTF8
 $taskSuccess=$true
} finally {
 try {if($taskState){Remove-CampusLifeS3 $taskState}}
 finally {foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}}
}
if($taskSuccess){[pscustomobject]@{task=$Task;cases=$Cases;units=$UnitCases;resourcesRemoved=$true;completedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content (Join-Path $taskFolder 'business-completed.json') -Encoding UTF8;Write-Host ('PASS: S3/MySQL business checks and owned resource cleanup. Evidence: '+$taskFolder)}



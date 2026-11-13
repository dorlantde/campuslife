param(
 [ValidateSet('Inspect','Pause','Async','Sync','Resume')][string]$Action='Inspect',
 [Parameter(Mandatory=$true)][ValidatePattern('^[1-9][0-9]{0,18}$')][string]$CampaignId,
 [ValidatePattern('^[0-9]{1,19}$')][string]$ExpectedControlVersion,
 [switch]$AllInstancesUpgraded
)
$ErrorActionPreference='Stop'
if($Action -ne 'Inspect' -and -not $PSBoundParameters.ContainsKey('ExpectedControlVersion')){throw 'Read Inspect first and supply ExpectedControlVersion.'}
if($Action -eq 'Async' -and -not $AllInstancesUpgraded){throw 'Stop old application versions, upgrade all instances, then explicitly supply AllInstancesUpgraded.'}
$taskNames=@('DB_PASSWORD','COUPON_MAINTENANCE_ACTION','COUPON_CAMPAIGN_ID','COUPON_CONTROL_VERSION','COUPON_ALL_INSTANCES_UPGRADED','MAVEN_ARGS');$taskSaved=@{}
foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
 if(-not $env:DB_PASSWORD){$taskPassword=Read-Host 'Enter the campuslife database account password locally' -AsSecureString;$env:DB_PASSWORD=[Net.NetworkCredential]::new('',$taskPassword).Password}
 $env:COUPON_MAINTENANCE_ACTION=$Action;$env:COUPON_CAMPAIGN_ID=$CampaignId;$env:COUPON_CONTROL_VERSION=$ExpectedControlVersion;$env:COUPON_ALL_INSTANCES_UPGRADED=$AllInstancesUpgraded.IsPresent.ToString().ToLowerInvariant()
 $env:MAVEN_ARGS='-Dcampuslife.main-class=com.campuslife.coupon.bootstrap.CouponMaintenanceBootstrap'
 & (Join-Path $PSScriptRoot 'dev.ps1') -Action Run
 if($LASTEXITCODE -ne 0){throw 'Local coupon maintenance failed; Inspect the durable gate before retrying.'}
}finally{foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}}

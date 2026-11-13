# Current-code comparison, or explicit frozen OLD/NEW publisher comparison, on owned temporary dependencies.
param([ValidateSet('Pilot','Measure')][string]$Phase='Pilot',[switch]$ComparePublisher)
$ErrorActionPreference='Stop'
$taskMarker=[Environment]::GetEnvironmentVariable('CAMPUSLIFE_COUPON_BENCH','Process')
$taskProfile=[Environment]::GetEnvironmentVariable('COUPON_BENCH_PROFILE','Process')
$taskCompare=[Environment]::GetEnvironmentVariable('COUPON_BENCH_COMPARE','Process')
try {
 if($ComparePublisher -and -not [string]::IsNullOrEmpty($env:MESSAGING_PUBLISHER_BATCH_SIZE) -and $env:MESSAGING_PUBLISHER_BATCH_SIZE -ne '32'){throw 'Fixed OLD/NEW comparison requires the NEW default batch32; remove the override rather than silently measuring another quota.'}
 $env:CAMPUSLIFE_COUPON_BENCH='owned-isolated-fixture';$env:COUPON_BENCH_PROFILE=$Phase
 if($ComparePublisher){$env:COUPON_BENCH_COMPARE='OLD_NEW'}else{[Environment]::SetEnvironmentVariable('COUPON_BENCH_COMPARE',$null,'Process')}
 $taskUnits='CouponRedisRepairJobTest,CouponIssueListenerTest';if($ComparePublisher){$taskUnits+=',PublisherComparisonClasspathTest'}
 & (Join-Path $PSScriptRoot 'test-coupon-redis.ps1') -Phase Messaging -Cases 'CouponMeasurementIT' -UnitCases $taskUnits
 if($LASTEXITCODE -ne 0){throw 'Coupon measurement failed; retained results include incomplete/invalid samples.'}
}finally{
 [Environment]::SetEnvironmentVariable('CAMPUSLIFE_COUPON_BENCH',$taskMarker,'Process')
 [Environment]::SetEnvironmentVariable('COUPON_BENCH_PROFILE',$taskProfile,'Process')
 [Environment]::SetEnvironmentVariable('COUPON_BENCH_COMPARE',$taskCompare,'Process')
}

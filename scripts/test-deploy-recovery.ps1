param()
$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'test_deploy_recovery.py')
exit $LASTEXITCODE

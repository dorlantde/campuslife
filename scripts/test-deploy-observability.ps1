param()
$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'test_deploy_observability.py')
exit $LASTEXITCODE

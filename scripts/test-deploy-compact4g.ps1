param()
$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'test_deploy_compact4g.py')
exit $LASTEXITCODE

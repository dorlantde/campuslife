param()
$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'test_deploy_showcase.py')
exit $LASTEXITCODE

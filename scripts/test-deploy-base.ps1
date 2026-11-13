$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'test_deploy_base.py')
exit $LASTEXITCODE

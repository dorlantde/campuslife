param([ValidateSet('All','Components','Base')][string]$Phase='All')
$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'test_deploy_components.py') --phase $Phase
exit $LASTEXITCODE
